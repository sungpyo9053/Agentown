package com.agentvillage.execution.application

import com.agentvillage.agent.application.AgentDirectory
import com.agentvillage.common.exception.ConflictException
import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.common.exception.NotFoundException
import com.agentvillage.execution.domain.*
import com.agentvillage.execution.infrastructure.*
import com.agentvillage.harness.application.HarnessDirectory
import com.agentvillage.harness.domain.HarnessStepType
import com.agentvillage.harness.domain.HarnessResultFormat
import com.agentvillage.llmcredential.application.CredentialDirectory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class ExecutionView(val execution: Execution, val steps: List<ExecutionStep>)
data class ExecutionResultPayload(
    val output: Map<String, Any>,
    val format: HarnessResultFormat,
    val resultStepKey: String?,
)

@Service
class ExecutionService(
    private val executions: ExecutionRepository, private val executionSteps: ExecutionStepRepository,
    private val events: ExecutionEventRepository, private val stream: ExecutionEventStream,
    private val harnesses: HarnessDirectory, private val agents: AgentDirectory,
    private val snapshots: ExecutionSnapshotReader,
    private val preflight: ExecutionPreflightValidator,
    private val localRunners: LocalRunnerConnectionRepository,
    private val metrics: ExecutionMetrics,
    @Value("\${execution.stub-enabled:true}") private val stubEnabled: Boolean,
) {
    @Transactional
    fun create(harnessId: UUID, ownerId: UUID, idempotencyKey: String, input: Map<String, Any>, stubMode: Boolean, executionMode: ExecutionMode = if (stubMode) ExecutionMode.STUB else ExecutionMode.CLOUD_API): Execution {
        executions.findByOwnerIdAndIdempotencyKey(ownerId, idempotencyKey)?.let { return it }
        val queued = executions.countByOwnerIdAndStatusIn(ownerId, listOf(ExecutionStatus.QUEUED))
        if (queued >= 3) throw ConflictException("EXECUTION_QUEUE_LIMIT", "사용자당 대기 실행은 최대 3개입니다.")
        val published = harnesses.publishedExecutionPlan(harnessId, ownerId)
        val plan = snapshots.read(published.snapshot)
        if (executionMode == ExecutionMode.LOCAL_CLI) validateLocalRunner(ownerId, plan)
        val credentialBindings = if (executionMode == ExecutionMode.CLOUD_API && !stubMode) {
            plan.agents.values.mapNotNull { snapshot ->
                val live = agents.describeOwned(snapshot.sourceAgentId, ownerId)
                if (snapshot.provider != live.provider) throw BadRequestException("HARNESS_CREDENTIAL_REBIND_REQUIRED", "${snapshot.name} 구성원의 Provider가 발행 버전과 달라 새 버전을 발행해야 합니다.")
                live.credentialId?.let { snapshot.key to it.toString() }
            }.toMap()
        } else emptyMap()
        if (stubMode && !stubEnabled) throw ConflictException("STUB_DISABLED", "Stub 실행은 이 환경에서 비활성화되어 있습니다.")
        if (!stubMode && executionMode == ExecutionMode.CLOUD_API) {
            preflight.validate(ownerId, plan.steps.filter { it.type == HarnessStepType.LLM }.map { step ->
                val snapshot = plan.agents.getValue(requireNotNull(step.agentKey))
                AgentExecutionConfig(snapshot.sourceAgentId, snapshot.provider, snapshot.model, credentialBindings[snapshot.key]?.let(UUID::fromString))
            })
        }
        val execution = executions.save(Execution(harnessId = harnessId, harnessVersionId = published.versionId,
            ownerId = ownerId, idempotencyKey = idempotencyKey, executionMode = executionMode,
            inputJson = input + ("_stubMode" to stubMode), executionSnapshotJson = published.snapshot,
            credentialBindingsJson = credentialBindings, timeoutAt = Instant.now().plus(30, ChronoUnit.MINUTES)))
        record(execution.id, "EXECUTION_QUEUED", null, mapOf("status" to "QUEUED", "mode" to executionMode.name))
        metrics.queued()
        return execution
    }
    @Transactional(readOnly = true) fun get(id: UUID, ownerId: UUID) = ExecutionView(requireOwned(id, ownerId), executionSteps.findAllByExecutionIdOrderByStartedAtAsc(id))
    @Transactional(readOnly = true) fun list(ownerId: UUID) = executions.findTop20ByOwnerIdOrderByCreatedAtDesc(ownerId)
    @Transactional
    fun cancel(id: UUID, ownerId: UUID): Execution {
        val execution = executions.findByIdForUpdate(id)
            ?.takeIf { it.ownerId == ownerId }
            ?: throw NotFoundException("EXECUTION_NOT_FOUND", "실행을 찾을 수 없습니다.")
        if (execution.status !in CANCELLABLE_STATUSES) {
            throw ConflictException("EXECUTION_NOT_CANCELLABLE", "이미 종료된 실행은 취소할 수 없습니다.")
        }

        val finishedAt = Instant.now()
        execution.status = ExecutionStatus.CANCELLED
        execution.currentStepKey = null
        execution.finishedAt = finishedAt
        executionSteps.findAllByExecutionIdOrderByStartedAtAsc(id)
            .filter { it.status in UNFINISHED_STEP_STATUSES }
            .forEach {
                it.status = StepStatus.CANCELLED
                it.finishedAt = finishedAt
            }
        record(id, "EXECUTION_FAILED", null, mapOf("status" to "CANCELLED"))
        metrics.completed(execution.startedAt, "CANCELLED")
        return execution
    }
    @Transactional fun approve(id: UUID, ownerId: UUID): Execution {
        val e = requireOwned(id, ownerId)
        if (e.status != ExecutionStatus.WAITING_APPROVAL) throw ConflictException("EXECUTION_NOT_WAITING_APPROVAL", "승인 대기 중인 실행이 아닙니다.")
        if (e.executionMode == ExecutionMode.LOCAL_CLI && e.outputJson != null) {
            e.status = ExecutionStatus.SUCCEEDED
            e.finishedAt = Instant.now()
            record(id, "EXECUTION_COMPLETED", null, mapOf("status" to "SUCCEEDED", "approved" to true, "mode" to "LOCAL_CLI"))
            metrics.completed(e.startedAt, "SUCCEEDED")
            return e
        }
        executionSteps.findAllByExecutionIdOrderByStartedAtAsc(id).lastOrNull { it.status == StepStatus.WAITING_APPROVAL }?.let {
            it.status = StepStatus.SUCCEEDED; it.finishedAt = Instant.now(); executionSteps.save(it)
        }
        e.status = ExecutionStatus.QUEUED; e.finishedAt = null
        record(id, "EXECUTION_QUEUED", null, mapOf("status" to "QUEUED", "approved" to true)); return e
    }
    @Transactional fun reject(id: UUID, ownerId: UUID): Execution {
        val e = requireOwned(id, ownerId)
        if (e.status != ExecutionStatus.WAITING_APPROVAL) throw ConflictException("EXECUTION_NOT_WAITING_APPROVAL", "승인 대기 중인 실행이 아닙니다.")
        e.status = ExecutionStatus.FAILED; e.errorCode = "USER_REJECTED"; e.finishedAt = Instant.now()
        record(id, "EXECUTION_FAILED", null, mapOf("errorCode" to "USER_REJECTED")); metrics.completed(e.startedAt, "FAILED"); return e
    }
    @Transactional(readOnly = true) fun history(id: UUID, ownerId: UUID): List<ExecutionEvent> { requireOwned(id, ownerId); return events.findAllByExecutionIdOrderBySequenceNo(id) }
    fun subscribe(id: UUID, ownerId: UUID) = stream.subscribe(id, history(id, ownerId))
    @Transactional(readOnly = true)
    fun result(id: UUID, ownerId: UUID): ExecutionResultPayload {
        val execution = requireOwned(id, ownerId)
        val output = execution.outputJson
            ?: throw BadRequestException("EXECUTION_RESULT_NOT_READY", "아직 다운로드할 실행 결과가 없습니다.")
        val plan = snapshots.read(execution.executionSnapshotJson)
        return ExecutionResultPayload(output, plan.resultFormat, plan.resultStepKey)
    }
    fun requireOwned(id: UUID, ownerId: UUID) = executions.findByIdAndOwnerId(id, ownerId) ?: throw NotFoundException("EXECUTION_NOT_FOUND", "실행을 찾을 수 없습니다.")

    private fun validateLocalRunner(ownerId: UUID, plan: ImmutableExecutionPlan) {
        if (plan.steps.any { it.type == HarnessStepType.APPROVAL }) {
            throw BadRequestException("LOCAL_RUNNER_MID_APPROVAL_UNSUPPORTED", "실제 직원 실행은 현재 전체 작업 완료 후 승인만 지원합니다. 승인 위치를 변경해 새 버전을 발행해 주세요.")
        }
        val providers = plan.steps.filter { it.type == HarnessStepType.LLM }.mapNotNull { step ->
            step.agentKey?.let(plan.agents::get)?.provider?.name
        }.toSet()
        if (providers.size != 1 || providers.singleOrNull() !in setOf("OPENAI", "ANTHROPIC")) {
            throw BadRequestException("LOCAL_RUNNER_PROVIDER_UNSUPPORTED", "한 번의 실제 실행에는 Codex 직원만 또는 Claude 직원만 연결할 수 있습니다.")
        }
        val required = if (providers.single() == "OPENAI") LocalRunnerProvider.CODEX else LocalRunnerProvider.CLAUDE
        val active = localRunners.findAllByOwnerIdOrderByCreatedAtDesc(ownerId).any {
            it.provider == required && it.status == LocalRunnerStatus.ACTIVE &&
                it.lastSeenAt?.isAfter(Instant.now().minus(30, ChronoUnit.SECONDS)) == true
        }
        if (!active) throw BadRequestException(
            "LOCAL_RUNNER_NOT_ACTIVE",
            "${if (required == LocalRunnerProvider.CODEX) "Codex" else "Claude"} Runner가 실행 중이 아닙니다. AI 연결 화면에서 Runner를 연결하고 시작해 주세요.",
        )
    }
    fun record(executionId: UUID, type: String, agentId: UUID?, payload: Map<String, Any>): ExecutionEvent {
        val event = events.save(ExecutionEvent(executionId = executionId, sequenceNo = events.countByExecutionId(executionId) + 1, eventType = type, agentId = agentId, payload = payload))
        if (TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = stream.publish(event)
            })
        } else {
            stream.publish(event)
        }
        return event
    }

    private companion object {
        val CANCELLABLE_STATUSES = setOf(
            ExecutionStatus.QUEUED,
            ExecutionStatus.RUNNING,
            ExecutionStatus.WAITING_RUNNER,
            ExecutionStatus.WAITING_APPROVAL,
        )
        val UNFINISHED_STEP_STATUSES = setOf(
            StepStatus.PENDING,
            StepStatus.RUNNING,
            StepStatus.WAITING_APPROVAL,
        )
    }
}
