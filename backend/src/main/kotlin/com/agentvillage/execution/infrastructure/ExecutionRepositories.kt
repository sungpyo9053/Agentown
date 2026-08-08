package com.agentvillage.execution.infrastructure

import com.agentvillage.execution.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import jakarta.persistence.LockModeType
import java.time.Instant
import java.util.UUID

interface ExecutionRepository : JpaRepository<Execution, UUID> {
    fun findByOwnerIdAndIdempotencyKey(ownerId: UUID, idempotencyKey: String): Execution?
    fun findByIdAndOwnerId(id: UUID, ownerId: UUID): Execution?
    fun countByOwnerIdAndStatusIn(ownerId: UUID, statuses: Collection<ExecutionStatus>): Long
    fun findTop20ByStatusAndExecutionModeInOrderByQueuedAt(status: ExecutionStatus, modes: Collection<ExecutionMode>): List<Execution>
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findFirstByOwnerIdAndStatusAndExecutionModeOrderByQueuedAt(ownerId: UUID, status: ExecutionStatus, executionMode: ExecutionMode): Execution?
    fun findAllByStatusAndHeartbeatAtBefore(status: ExecutionStatus, before: Instant): List<Execution>
    fun findTop100ByOrderByCreatedAtDesc(): List<Execution>
    fun findTop20ByOwnerIdOrderByCreatedAtDesc(ownerId: UUID): List<Execution>
    fun countByStatus(status: ExecutionStatus): Long
}
interface LocalRunnerConnectionRepository : JpaRepository<LocalRunnerConnection, UUID> {
    fun findAllByOwnerIdOrderByCreatedAtDesc(ownerId: UUID): List<LocalRunnerConnection>
    fun findByIdAndOwnerId(id: UUID, ownerId: UUID): LocalRunnerConnection?
    fun findByTokenHash(tokenHash: String): LocalRunnerConnection?
}
interface ExecutionStepRepository : JpaRepository<ExecutionStep, UUID> { fun findAllByExecutionIdOrderByStartedAtAsc(executionId: UUID): List<ExecutionStep> }
interface ExecutionEventRepository : JpaRepository<ExecutionEvent, UUID> {
    fun findAllByExecutionIdOrderBySequenceNo(executionId: UUID): List<ExecutionEvent>
    fun countByExecutionId(executionId: UUID): Long
}
