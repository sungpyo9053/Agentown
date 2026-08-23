package com.agentvillage.execution.application

import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.common.exception.NotFoundException
import com.agentvillage.execution.domain.*
import com.agentvillage.execution.infrastructure.ExecutionRepository
import com.agentvillage.execution.infrastructure.ExecutionStepRepository
import com.agentvillage.execution.infrastructure.LocalRunnerConnectionRepository
import com.agentvillage.harness.domain.HarnessStepType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class LocalRunnerView(val id: UUID, val provider: LocalRunnerProvider, val deviceName: String, val status: LocalRunnerStatus, val lastSeenAt: Instant?, val createdAt: Instant)
data class PairLocalRunnerResult(val connection: LocalRunnerView, val pairingToken: String)
data class RunnerAgentJob(val stepKey: String, val agentId: UUID, val name: String, val role: String, val systemPrompt: String?, val task: String, val guide: String?, val model: String, val timeoutSeconds: Int)
data class RunnerExecutionJob(
    val executionId: UUID,
    val harnessName: String,
    val input: Map<String, Any>,
    val agents: List<RunnerAgentJob>,
    val approvalRequired: Boolean,
)

@Service
class LocalRunnerService(
    private val connections: LocalRunnerConnectionRepository,
    private val executions: ExecutionRepository,
    private val executionSteps: ExecutionStepRepository,
    private val snapshots: ExecutionSnapshotReader,
    private val executionService: ExecutionService,
) {
    @Transactional
    fun pair(ownerId: UUID, provider: LocalRunnerProvider, deviceName: String): PairLocalRunnerResult {
        val token = ByteArray(32).also(SecureRandom()::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val connection = connections.save(LocalRunnerConnection(ownerId = ownerId, provider = provider, deviceName = deviceName.trim().take(100), tokenHash = hash(token)))
        return PairLocalRunnerResult(connection.view(), token)
    }

    @Transactional(readOnly = true)
    fun list(ownerId: UUID) = connections.findAllByOwnerIdOrderByCreatedAtDesc(ownerId).map { it.view() }

    @Transactional
    fun revoke(ownerId: UUID, id: UUID) {
        val connection = connections.findByIdAndOwnerId(id, ownerId) ?: throw NotFoundException("LOCAL_RUNNER_NOT_FOUND", "로컬 Runner를 찾을 수 없습니다.")
        connection.status = LocalRunnerStatus.REVOKED
    }

    @Transactional
    fun heartbeat(token: String): LocalRunnerView {
        val connection = authenticate(token)
        connection.status = LocalRunnerStatus.ACTIVE; connection.lastSeenAt = Instant.now(); connection.updatedAt = Instant.now()
        return connection.view()
    }

    @Transactional
    fun claim(token: String): RunnerExecutionJob? {
        val connection = authenticate(token)
        connection.status = LocalRunnerStatus.ACTIVE; connection.lastSeenAt = Instant.now()
        val execution = executions.findFirstByOwnerIdAndStatusAndExecutionModeOrderByQueuedAt(connection.ownerId, ExecutionStatus.QUEUED, ExecutionMode.LOCAL_CLI) ?: return null
        val plan = snapshots.read(execution.executionSnapshotJson)
        if (plan.steps.any { it.type == HarnessStepType.APPROVAL }) {
            throw BadRequestException("LOCAL_RUNNER_MID_APPROVAL_UNSUPPORTED", "실제 Local Runner 실행은 현재 전체 작업 완료 후 승인만 지원합니다. 승인 위치를 '전체 작업 완료 전'으로 변경해 새 버전을 발행해 주세요.")
        }
        val jobs = plan.steps.filter { it.type == HarnessStepType.LLM }.map { step ->
            val agent = plan.agents.getValue(requireNotNull(step.agentKey))
            val expected = if (connection.provider == LocalRunnerProvider.CODEX) "OPENAI" else "ANTHROPIC"
            if (agent.provider.name != expected) throw BadRequestException("RUNNER_PROVIDER_MISMATCH", "${agent.name} 구성원은 ${agent.provider} 연결이 필요합니다.")
            RunnerAgentJob(step.key, agent.sourceAgentId, agent.name, agent.role, agent.systemPrompt, agent.script, agent.guide, agent.model, agent.timeoutSeconds)
        }
        execution.status = ExecutionStatus.RUNNING; execution.runnerConnectionId = connection.id; execution.startedAt = Instant.now(); execution.heartbeatAt = Instant.now()
        executionService.record(execution.id, "EXECUTION_STARTED", null, mapOf("mode" to "LOCAL_CLI", "runner" to connection.deviceName))
        return RunnerExecutionJob(
            execution.id,
            plan.name,
            execution.inputJson.filterKeys { !it.startsWith("_") },
            jobs,
            plan.steps.any { it.requiresApproval },
        )
    }

    @Transactional
    fun complete(token: String, executionId: UUID, output: Map<String, Any>) {
        val connection = authenticate(token)
        val execution = executions.findByIdAndOwnerId(executionId, connection.ownerId) ?: throw NotFoundException("EXECUTION_NOT_FOUND", "실행을 찾을 수 없습니다.")
        if (execution.runnerConnectionId != connection.id || execution.status != ExecutionStatus.RUNNING) throw BadRequestException("RUNNER_JOB_NOT_OWNED", "이 Runner가 처리 중인 실행이 아닙니다.")
        val plan = snapshots.read(execution.executionSnapshotJson)
        execution.outputJson = output
        execution.heartbeatAt = Instant.now()
        if (plan.steps.any { it.requiresApproval }) {
            execution.status = ExecutionStatus.WAITING_APPROVAL
            executionService.record(execution.id, "WAITING_APPROVAL", plan.steps.lastOrNull()?.agentKey?.let(plan.agents::get)?.sourceAgentId,
                mapOf("stepKey" to (execution.currentStepKey ?: "final"), "mode" to "LOCAL_CLI"))
        } else {
            execution.status = ExecutionStatus.SUCCEEDED
            execution.finishedAt = Instant.now()
            executionService.record(execution.id, "EXECUTION_COMPLETED", null, mapOf("status" to "SUCCEEDED", "mode" to "LOCAL_CLI"))
        }
    }

    @Transactional
    fun progress(token: String, executionId: UUID, eventType: String, agentId: UUID?, stepKey: String, output: Map<String, Any>?) {
        val connection = authenticate(token)
        val execution = executions.findByIdAndOwnerId(executionId, connection.ownerId) ?: throw NotFoundException("EXECUTION_NOT_FOUND", "실행을 찾을 수 없습니다.")
        if (execution.runnerConnectionId != connection.id || execution.status != ExecutionStatus.RUNNING) throw BadRequestException("RUNNER_JOB_NOT_OWNED", "이 Runner가 처리 중인 실행이 아닙니다.")
        val allowed = setOf("STEP_STARTED", "MODEL_REQUEST_SENT", "STEP_OUTPUT_CREATED", "STEP_COMPLETED")
        if (eventType !in allowed) throw BadRequestException("RUNNER_EVENT_INVALID", "허용되지 않은 Runner 이벤트입니다.")
        execution.currentStepKey = stepKey; execution.heartbeatAt = Instant.now()
        val plan = snapshots.read(execution.executionSnapshotJson)
        val planStep = plan.steps.firstOrNull { it.key == stepKey }
            ?: throw BadRequestException("RUNNER_STEP_NOT_FOUND", "발행된 실행본에 없는 단계입니다.")
        val agent = planStep.agentKey?.let(plan.agents::get)
        val expectedAgentId = agent?.sourceAgentId
        if (agentId != null && agentId != expectedAgentId) {
            throw BadRequestException("RUNNER_AGENT_MISMATCH", "해당 단계의 구성원과 Runner 이벤트의 구성원이 일치하지 않습니다.")
        }
        val step = executionSteps.findByExecutionIdAndStepKey(executionId, stepKey)
            ?: executionSteps.save(ExecutionStep(
                executionId = executionId,
                harnessStepId = null,
                stepKey = stepKey,
                stepType = planStep.type.name,
                status = StepStatus.RUNNING,
                inputJson = execution.inputJson.filterKeys { !it.startsWith("_") },
                provider = agent?.provider?.name,
                model = agent?.model,
                startedAt = Instant.now(),
            ))
        when (eventType) {
            "STEP_STARTED" -> step.status = StepStatus.RUNNING
            "STEP_OUTPUT_CREATED" -> if (output != null) step.outputJson = output
            "STEP_COMPLETED" -> {
                step.status = StepStatus.SUCCEEDED
                step.finishedAt = Instant.now()
            }
        }
        executionSteps.save(step)
        executionService.record(executionId, eventType, expectedAgentId, mapOf("stepKey" to stepKey, "mode" to "LOCAL_CLI"))
    }

    @Transactional
    fun fail(token: String, executionId: UUID, code: String, message: String) {
        val connection = authenticate(token)
        val execution = executions.findByIdAndOwnerId(executionId, connection.ownerId) ?: throw NotFoundException("EXECUTION_NOT_FOUND", "실행을 찾을 수 없습니다.")
        if (execution.runnerConnectionId != connection.id) throw BadRequestException("RUNNER_JOB_NOT_OWNED", "이 Runner가 처리 중인 실행이 아닙니다.")
        execution.status = ExecutionStatus.FAILED; execution.errorCode = code.take(80); execution.errorMessage = message.take(1000); execution.finishedAt = Instant.now()
        executionService.record(execution.id, "EXECUTION_FAILED", null, mapOf("errorCode" to execution.errorCode!!, "mode" to "LOCAL_CLI"))
    }

    private fun authenticate(token: String): LocalRunnerConnection {
        if (token.length < 32) throw BadRequestException("RUNNER_TOKEN_INVALID", "Runner 토큰이 올바르지 않습니다.")
        val connection = connections.findByTokenHash(hash(token)) ?: throw BadRequestException("RUNNER_TOKEN_INVALID", "Runner 토큰이 올바르지 않습니다.")
        if (connection.status == LocalRunnerStatus.REVOKED) throw BadRequestException("RUNNER_REVOKED", "폐기된 Runner입니다.")
        return connection
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun LocalRunnerConnection.view() = LocalRunnerView(id, provider, deviceName, status, lastSeenAt, createdAt)
}
