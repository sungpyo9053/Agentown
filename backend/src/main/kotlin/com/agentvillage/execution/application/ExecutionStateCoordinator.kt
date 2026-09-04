package com.agentvillage.execution.application

import com.agentvillage.execution.domain.Execution
import com.agentvillage.execution.domain.ExecutionMode
import com.agentvillage.execution.domain.ExecutionStatus
import com.agentvillage.execution.domain.ExecutionStep
import com.agentvillage.execution.domain.StepStatus
import com.agentvillage.execution.infrastructure.ExecutionRepository
import com.agentvillage.execution.infrastructure.ExecutionStepRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class StepCompletionMetadata(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val estimatedCost: BigDecimal? = null,
    val providerRequestId: String? = null,
)

@Service
class ExecutionStateCoordinator(
    private val executions: ExecutionRepository,
    private val executionSteps: ExecutionStepRepository,
    private val service: ExecutionService,
    private val metrics: ExecutionMetrics,
) {
    @Transactional
    fun beginStep(
        executionId: UUID,
        stepKey: String,
        stepType: String,
        input: Map<String, Any>,
        provider: String?,
        model: String?,
        agentId: UUID?,
        now: Instant = Instant.now(),
    ): ExecutionStep? {
        val execution = executions.findByIdForUpdate(executionId) ?: return null
        if (execution.status != ExecutionStatus.RUNNING) return null
        if (execution.timeoutAt?.let { !it.isAfter(now) } == true) {
            timeoutLocked(execution, now, RecoveryReason.DEADLINE_EXCEEDED)
            return null
        }
        execution.currentStepKey = stepKey
        execution.heartbeatAt = now
        execution.updatedAt = now
        val step = executionSteps.save(
            ExecutionStep(
                executionId = executionId,
                harnessStepId = null,
                stepKey = stepKey,
                stepType = stepType,
                inputJson = input,
                startedAt = now,
                status = StepStatus.RUNNING,
                provider = provider,
                model = model,
            ),
        )
        service.record(executionId, "STEP_STARTED", agentId, mapOf("stepKey" to stepKey, "type" to stepType))
        return step
    }

    @Transactional
    fun recordWhileRunning(executionId: UUID, type: String, agentId: UUID?, payload: Map<String, Any>): Boolean {
        val execution = executions.findByIdForUpdate(executionId) ?: return false
        if (execution.status != ExecutionStatus.RUNNING) return false
        val now = Instant.now()
        if (execution.timeoutAt?.let { !it.isAfter(now) } == true) {
            timeoutLocked(execution, now, RecoveryReason.DEADLINE_EXCEEDED)
            return false
        }
        service.record(executionId, type, agentId, payload)
        return true
    }

    @Transactional
    fun waitForApproval(executionId: UUID, stepId: UUID, output: Map<String, Any>, agentId: UUID?): Boolean {
        val execution = executions.findByIdForUpdate(executionId) ?: return false
        if (execution.status != ExecutionStatus.RUNNING) return false
        val step = executionSteps.findById(stepId).orElse(null) ?: return false
        if (step.status != StepStatus.RUNNING) return false
        val now = Instant.now()
        if (execution.timeoutAt?.let { !it.isAfter(now) } == true) {
            timeoutLocked(execution, now, RecoveryReason.DEADLINE_EXCEEDED)
            return false
        }
        step.status = StepStatus.WAITING_APPROVAL
        step.outputJson = output
        execution.status = ExecutionStatus.WAITING_APPROVAL
        execution.heartbeatAt = now
        service.record(executionId, "WAITING_APPROVAL", agentId, mapOf("stepKey" to step.stepKey))
        return true
    }

    @Transactional
    fun completeStep(
        executionId: UUID,
        stepId: UUID,
        output: Map<String, Any>,
        metadata: StepCompletionMetadata,
        agentId: UUID?,
        pauseForApproval: Boolean,
    ): Boolean {
        val execution = executions.findByIdForUpdate(executionId) ?: return false
        if (execution.status != ExecutionStatus.RUNNING) return false
        val step = executionSteps.findById(stepId).orElse(null) ?: return false
        if (step.status != StepStatus.RUNNING) return false
        val now = Instant.now()
        step.outputJson = output
        step.status = StepStatus.SUCCEEDED
        step.finishedAt = now
        step.inputTokens = metadata.inputTokens
        step.outputTokens = metadata.outputTokens
        step.estimatedCost = metadata.estimatedCost
        step.providerRequestId = metadata.providerRequestId
        execution.heartbeatAt = now
        service.record(executionId, "STEP_OUTPUT_CREATED", agentId, mapOf("stepKey" to step.stepKey))
        service.record(executionId, "STEP_COMPLETED", agentId, mapOf("stepKey" to step.stepKey))
        if (execution.timeoutAt?.let { !it.isAfter(now) } == true) {
            execution.currentStepKey = null
            timeoutLocked(execution, now, RecoveryReason.DEADLINE_EXCEEDED)
            return false
        }
        if (pauseForApproval) {
            execution.status = ExecutionStatus.WAITING_APPROVAL
            service.record(executionId, "WAITING_APPROVAL", agentId, mapOf("stepKey" to step.stepKey))
        } else {
            execution.currentStepKey = null
        }
        return true
    }

    @Transactional
    fun recordAttemptFailure(
        executionId: UUID,
        stepId: UUID,
        agentId: UUID?,
        attempt: Int,
        errorCode: String,
        errorMessage: String?,
        willRetry: Boolean,
    ): Boolean {
        val execution = executions.findByIdForUpdate(executionId) ?: return false
        if (execution.status != ExecutionStatus.RUNNING) return false
        val step = executionSteps.findById(stepId).orElse(null) ?: return false
        if (step.status != StepStatus.RUNNING) return false
        val now = Instant.now()
        if (execution.timeoutAt?.let { !it.isAfter(now) } == true) {
            timeoutLocked(execution, now, RecoveryReason.DEADLINE_EXCEEDED)
            return false
        }
        step.attempt = attempt
        step.errorCode = errorCode
        step.errorMessage = errorMessage?.take(1000)
        service.record(
            executionId,
            "STEP_FAILED",
            agentId,
            mapOf("stepKey" to step.stepKey, "attempt" to attempt, "willRetry" to willRetry),
        )
        return true
    }

    @Transactional
    fun completeExecution(executionId: UUID, output: Map<String, Any>): Boolean {
        val execution = executions.findByIdForUpdate(executionId) ?: return false
        if (execution.status != ExecutionStatus.RUNNING) return false
        val now = Instant.now()
        if (execution.timeoutAt?.let { !it.isAfter(now) } == true) {
            timeoutLocked(execution, now, RecoveryReason.DEADLINE_EXCEEDED)
            return false
        }
        execution.outputJson = output
        execution.status = ExecutionStatus.SUCCEEDED
        execution.currentStepKey = null
        execution.finishedAt = now
        execution.heartbeatAt = now
        service.record(executionId, "EXECUTION_COMPLETED", null, mapOf("status" to "SUCCEEDED"))
        metrics.completed(execution.startedAt, "SUCCEEDED")
        return true
    }

    @Transactional
    fun failExecution(executionId: UUID, timeout: Boolean, errorMessage: String?): Boolean {
        val execution = executions.findByIdForUpdate(executionId) ?: return false
        if (execution.status != ExecutionStatus.RUNNING) return false
        val now = Instant.now()
        if (execution.timeoutAt?.let { !it.isAfter(now) } == true) {
            timeoutLocked(execution, now, RecoveryReason.DEADLINE_EXCEEDED)
            return true
        }
        val errorCode = if (timeout) "EXECUTION_TIMEOUT" else "STEP_EXECUTION_FAILED"
        val safeMessage = if (timeout) {
            "허용된 실행 시간을 초과해 작업을 안전하게 중단했습니다."
        } else {
            errorMessage?.take(1000) ?: "단계를 실행하지 못했습니다."
        }
        executionSteps.findByExecutionIdAndStepKey(executionId, execution.currentStepKey.orEmpty())
            ?.takeIf { it.status == StepStatus.RUNNING }
            ?.also { step ->
                step.status = if (timeout) StepStatus.TIMEOUT else StepStatus.FAILED
                step.errorCode = if (timeout) "STEP_TIMEOUT" else errorCode
                step.errorMessage = safeMessage
                step.finishedAt = now
            }
        execution.status = if (timeout) ExecutionStatus.TIMEOUT else ExecutionStatus.FAILED
        execution.errorCode = errorCode
        execution.errorMessage = safeMessage
        execution.currentStepKey = null
        execution.finishedAt = now
        execution.heartbeatAt = now
        service.record(executionId, "EXECUTION_FAILED", null, mapOf("errorCode" to errorCode))
        metrics.completed(execution.startedAt, execution.status.name)
        return true
    }

    fun refreshHeartbeat(executionId: UUID, now: Instant = Instant.now()): Boolean =
        executions.refreshHeartbeat(executionId, now) == 1

    @Transactional
    fun recoverOne(
        executionId: UUID,
        now: Instant = Instant.now(),
        leaseCutoff: Instant = now.minus(2, ChronoUnit.MINUTES),
    ): Boolean {
        val execution = executions.findByIdForUpdate(executionId) ?: return false
        val deadlineExpired = execution.timeoutAt?.let { !it.isAfter(now) } == true
        val deadlineRecoveryEligible =
            (execution.status == ExecutionStatus.QUEUED && execution.executionMode in DEADLINE_RECOVERABLE_QUEUED_MODES) ||
                (execution.status == ExecutionStatus.RUNNING && execution.executionMode in WORKER_RECOVERABLE_MODES)
        val leaseExpired = execution.executionMode in WORKER_RECOVERABLE_MODES &&
            execution.status == ExecutionStatus.RUNNING &&
            (execution.heartbeatAt == null || !execution.heartbeatAt!!.isAfter(leaseCutoff))
        val reason = when {
            deadlineExpired && deadlineRecoveryEligible -> RecoveryReason.DEADLINE_EXCEEDED
            leaseExpired -> RecoveryReason.WORKER_LEASE_EXPIRED
            else -> return false
        }
        timeoutLocked(execution, now, reason)
        return true
    }

    private fun timeoutLocked(execution: Execution, now: Instant, reason: RecoveryReason) {
        val interruptedStepKey = execution.currentStepKey
        if (execution.status == ExecutionStatus.RUNNING && interruptedStepKey != null) {
            executionSteps.findByExecutionIdAndStepKey(execution.id, interruptedStepKey)
                ?.takeIf { it.status == StepStatus.RUNNING }
                ?.also { step ->
                    step.status = StepStatus.TIMEOUT
                    step.errorCode = "INTERRUPTED_STEP_OUTCOME_UNKNOWN"
                    step.errorMessage = when (reason) {
                        RecoveryReason.DEADLINE_EXCEEDED ->
                            "전체 실행 제한 시간이 지난 시점에 진행 중이어서 이 단계의 완료 여부를 확인할 수 없습니다. 자동 재실행하지 않습니다."
                        RecoveryReason.WORKER_LEASE_EXPIRED ->
                            "작업자 연결이 끊겨 이 단계의 완료 여부를 확인할 수 없습니다. 자동 재실행하지 않습니다."
                    }
                    step.finishedAt = now
                }
        }
        val errorCode = when (reason) {
            RecoveryReason.DEADLINE_EXCEEDED -> "EXECUTION_DEADLINE_EXCEEDED"
            RecoveryReason.WORKER_LEASE_EXPIRED -> "WORKER_LEASE_EXPIRED"
        }
        val message = when (reason) {
            RecoveryReason.DEADLINE_EXCEEDED -> "전체 실행 제한 시간이 지나 안전하게 종료했습니다. 완료된 단계 결과는 보존됩니다."
            RecoveryReason.WORKER_LEASE_EXPIRED -> "작업자 응답이 끊겨 실행을 안전하게 종료했습니다. 진행 중이던 단계는 자동 재실행하지 않습니다."
        }
        execution.status = ExecutionStatus.TIMEOUT
        execution.errorCode = errorCode
        execution.errorMessage = message
        execution.currentStepKey = null
        execution.finishedAt = now
        execution.heartbeatAt = now
        execution.updatedAt = now
        service.record(
            execution.id,
            "EXECUTION_TIMEOUT_RECOVERED",
            null,
            mapOf(
                "status" to "TIMEOUT",
                "reason" to reason.name,
                "errorCode" to errorCode,
                "interruptedStepKey" to (interruptedStepKey ?: "NONE"),
                "automaticRetry" to false,
            ),
        )
        metrics.completed(execution.startedAt, "TIMEOUT")
    }

    companion object {
        val WORKER_RECOVERABLE_MODES = listOf(ExecutionMode.CLOUD_API, ExecutionMode.STUB)
        val DEADLINE_RECOVERABLE_QUEUED_MODES = WORKER_RECOVERABLE_MODES + ExecutionMode.LOCAL_CLI
    }
}

enum class RecoveryReason { DEADLINE_EXCEEDED, WORKER_LEASE_EXPIRED }

@Service
class ExecutionRecoveryReconciler(
    private val executions: ExecutionRepository,
    private val coordinator: ExecutionStateCoordinator,
) {
    fun reconcile(now: Instant = Instant.now(), leaseSeconds: Long = 120, batchSize: Int = 100): Int {
        val leaseCutoff = now.minusSeconds(leaseSeconds)
        return executions.findRecoveryCandidateIds(
            ExecutionStateCoordinator.WORKER_RECOVERABLE_MODES,
            now,
            leaseCutoff,
            PageRequest.of(0, batchSize.coerceIn(1, 100)),
        ).count { coordinator.recoverOne(it, now, leaseCutoff) }
    }
}
