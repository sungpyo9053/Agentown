package com.agentvillage.execution.application

import com.agentvillage.agent.application.AgentDirectory
import com.agentvillage.common.exception.ConflictException
import com.agentvillage.common.exception.NotFoundException
import com.agentvillage.execution.domain.*
import com.agentvillage.execution.infrastructure.*
import com.agentvillage.harness.application.HarnessDirectory
import com.agentvillage.harness.domain.HarnessStepType
import com.agentvillage.llmcredential.application.CredentialDirectory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class ExecutionView(val execution: Execution, val steps: List<ExecutionStep>)

@Service
class ExecutionService(
    private val executions: ExecutionRepository, private val executionSteps: ExecutionStepRepository,
    private val events: ExecutionEventRepository, private val stream: ExecutionEventStream,
    private val harnesses: HarnessDirectory, private val agents: AgentDirectory,
    private val preflight: ExecutionPreflightValidator,
    @Value("\${execution.stub-enabled:true}") private val stubEnabled: Boolean,
) {
    @Transactional
    fun create(harnessId: UUID, ownerId: UUID, idempotencyKey: String, input: Map<String, Any>, stubMode: Boolean): Execution {
        executions.findByOwnerIdAndIdempotencyKey(ownerId, idempotencyKey)?.let { return it }
        val queued = executions.countByOwnerIdAndStatusIn(ownerId, listOf(ExecutionStatus.QUEUED))
        if (queued >= 3) throw ConflictException("EXECUTION_QUEUE_LIMIT", "사용자당 대기 실행은 최대 3개입니다.")
        val view = harnesses.requireOwnedView(harnessId, ownerId)
        if (stubMode && !stubEnabled) throw ConflictException("STUB_DISABLED", "Stub 실행은 이 환경에서 비활성화되어 있습니다.")
        if (!stubMode) {
            preflight.validate(ownerId, view.steps.filter { it.stepType == HarnessStepType.LLM }.mapNotNull { it.agentId }.map {
                agents.describeOwned(it, ownerId).let { a -> AgentExecutionConfig(a.id, a.provider, a.model, a.credentialId) }
            })
        }
        val execution = executions.save(Execution(harnessId = harnessId, harnessVersionId = view.latestVersion?.id,
            ownerId = ownerId, idempotencyKey = idempotencyKey, inputJson = input + ("_stubMode" to stubMode), timeoutAt = Instant.now().plus(30, ChronoUnit.MINUTES)))
        record(execution.id, "EXECUTION_QUEUED", null, mapOf("status" to "QUEUED"))
        return execution
    }
    @Transactional(readOnly = true) fun get(id: UUID, ownerId: UUID) = ExecutionView(requireOwned(id, ownerId), executionSteps.findAllByExecutionIdOrderByStartedAtAsc(id))
    @Transactional fun cancel(id: UUID, ownerId: UUID): Execution { val e = requireOwned(id, ownerId); e.status = ExecutionStatus.CANCELLED; e.finishedAt = Instant.now(); record(id, "EXECUTION_FAILED", null, mapOf("status" to "CANCELLED")); return e }
    @Transactional fun approve(id: UUID, ownerId: UUID): Execution {
        val e = requireOwned(id, ownerId)
        if (e.status != ExecutionStatus.WAITING_APPROVAL) throw ConflictException("EXECUTION_NOT_WAITING_APPROVAL", "승인 대기 중인 실행이 아닙니다.")
        e.status = ExecutionStatus.SUCCEEDED; e.finishedAt = Instant.now()
        e.outputJson = executionSteps.findAllByExecutionIdOrderByStartedAtAsc(id).lastOrNull()?.outputJson
        record(id, "EXECUTION_COMPLETED", null, mapOf("status" to "SUCCEEDED", "approved" to true)); return e
    }
    @Transactional fun reject(id: UUID, ownerId: UUID): Execution {
        val e = requireOwned(id, ownerId)
        if (e.status != ExecutionStatus.WAITING_APPROVAL) throw ConflictException("EXECUTION_NOT_WAITING_APPROVAL", "승인 대기 중인 실행이 아닙니다.")
        e.status = ExecutionStatus.FAILED; e.errorCode = "USER_REJECTED"; e.finishedAt = Instant.now()
        record(id, "EXECUTION_FAILED", null, mapOf("errorCode" to "USER_REJECTED")); return e
    }
    @Transactional(readOnly = true) fun history(id: UUID, ownerId: UUID): List<ExecutionEvent> { requireOwned(id, ownerId); return events.findAllByExecutionIdOrderBySequenceNo(id) }
    fun subscribe(id: UUID, ownerId: UUID) = stream.subscribe(id, history(id, ownerId))
    fun requireOwned(id: UUID, ownerId: UUID) = executions.findByIdAndOwnerId(id, ownerId) ?: throw NotFoundException("EXECUTION_NOT_FOUND", "실행을 찾을 수 없습니다.")
    fun record(executionId: UUID, type: String, agentId: UUID?, payload: Map<String, Any>): ExecutionEvent {
        val event = events.save(ExecutionEvent(executionId = executionId, sequenceNo = events.countByExecutionId(executionId) + 1, eventType = type, agentId = agentId, payload = payload))
        stream.publish(event); return event
    }
}
