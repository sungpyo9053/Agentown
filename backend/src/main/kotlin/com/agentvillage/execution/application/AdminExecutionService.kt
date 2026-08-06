package com.agentvillage.execution.application

import com.agentvillage.execution.domain.ExecutionStatus
import com.agentvillage.execution.infrastructure.ExecutionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class AdminExecutionView(
    val id: UUID,
    val harnessId: UUID,
    val ownerId: UUID,
    val status: ExecutionStatus,
    val currentStepKey: String?,
    val errorCode: String?,
    val errorMessage: String?,
    val queuedAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
)

data class AdminExecutionSummary(
    val total: Long,
    val queued: Long,
    val running: Long,
    val waitingApproval: Long,
    val succeeded: Long,
    val failed: Long,
)

@Service
class AdminExecutionService(private val executions: ExecutionRepository) {
    @Transactional(readOnly = true)
    fun list() = executions.findTop100ByOrderByCreatedAtDesc().map {
        AdminExecutionView(
            it.id, it.harnessId, it.ownerId, it.status, it.currentStepKey,
            it.errorCode, it.errorMessage, it.queuedAt, it.startedAt, it.finishedAt,
        )
    }

    @Transactional(readOnly = true)
    fun summary() = AdminExecutionSummary(
        total = executions.count(),
        queued = executions.countByStatus(ExecutionStatus.QUEUED),
        running = executions.countByStatus(ExecutionStatus.RUNNING),
        waitingApproval = executions.countByStatus(ExecutionStatus.WAITING_APPROVAL),
        succeeded = executions.countByStatus(ExecutionStatus.SUCCEEDED),
        failed = executions.countByStatus(ExecutionStatus.FAILED) + executions.countByStatus(ExecutionStatus.TIMEOUT),
    )
}
