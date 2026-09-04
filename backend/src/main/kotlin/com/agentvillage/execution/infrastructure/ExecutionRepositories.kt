package com.agentvillage.execution.infrastructure

import com.agentvillage.execution.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.domain.Pageable
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface ExecutionRepository : JpaRepository<Execution, UUID>, LocalRunnerExecutionClaimRepository {
    fun findByOwnerIdAndIdempotencyKey(ownerId: UUID, idempotencyKey: String): Execution?
    fun findByIdAndOwnerId(id: UUID, ownerId: UUID): Execution?
    fun countByOwnerIdAndStatusIn(ownerId: UUID, statuses: Collection<ExecutionStatus>): Long
    fun findTop20ByStatusAndExecutionModeInOrderByQueuedAt(status: ExecutionStatus, modes: Collection<ExecutionMode>): List<Execution>
    fun findTop100ByOrderByCreatedAtDesc(): List<Execution>
    fun findTop20ByOwnerIdOrderByCreatedAtDesc(ownerId: UUID): List<Execution>
    fun countByStatus(status: ExecutionStatus): Long

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        update Execution e
           set e.status = com.agentvillage.execution.domain.ExecutionStatus.RUNNING,
               e.startedAt = coalesce(e.startedAt, :now),
               e.heartbeatAt = :now
         where e.id = :id
           and e.status = com.agentvillage.execution.domain.ExecutionStatus.QUEUED
           and (e.timeoutAt is null or e.timeoutAt > :now)
        """,
    )
    fun claimQueued(@Param("id") id: UUID, @Param("now") now: Instant): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        update Execution e
           set e.heartbeatAt = :now,
               e.updatedAt = :now
         where e.id = :id
           and e.status = com.agentvillage.execution.domain.ExecutionStatus.RUNNING
        """,
    )
    fun refreshHeartbeat(@Param("id") id: UUID, @Param("now") now: Instant): Int

    @Query(
        """
        select e.id
          from Execution e
         where (
               (e.executionMode in :workerModes
                    and e.status = com.agentvillage.execution.domain.ExecutionStatus.QUEUED
                    and e.timeoutAt is not null and e.timeoutAt <= :now)
               or
               (e.executionMode = com.agentvillage.execution.domain.ExecutionMode.LOCAL_CLI
                    and e.status = com.agentvillage.execution.domain.ExecutionStatus.QUEUED
                    and e.timeoutAt is not null and e.timeoutAt <= :now)
               or
               (e.executionMode in :workerModes
                    and e.status = com.agentvillage.execution.domain.ExecutionStatus.RUNNING
                    and ((e.timeoutAt is not null and e.timeoutAt <= :now)
                         or e.heartbeatAt is null
                         or e.heartbeatAt <= :leaseCutoff))
           )
         order by e.queuedAt
        """,
    )
    fun findRecoveryCandidateIds(
        @Param("workerModes") workerModes: Collection<ExecutionMode>,
        @Param("now") now: Instant,
        @Param("leaseCutoff") leaseCutoff: Instant,
        pageable: Pageable,
    ): List<UUID>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Execution e where e.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): Execution?
}

interface LocalRunnerExecutionClaimRepository {
    fun findFirstByOwnerIdAndStatusAndExecutionModeOrderByQueuedAt(
        ownerId: UUID,
        status: ExecutionStatus,
        executionMode: ExecutionMode,
    ): Execution?
}

class LocalRunnerExecutionClaimRepositoryImpl(
    private val entityManager: EntityManager,
) : LocalRunnerExecutionClaimRepository {
    override fun findFirstByOwnerIdAndStatusAndExecutionModeOrderByQueuedAt(
        ownerId: UUID,
        status: ExecutionStatus,
        executionMode: ExecutionMode,
    ): Execution? = entityManager.createQuery(
        """
        select e
          from Execution e
         where e.ownerId = :ownerId
           and e.status = :status
           and e.executionMode = :executionMode
           and (e.timeoutAt is null or e.timeoutAt > :now)
         order by e.queuedAt
        """.trimIndent(),
        Execution::class.java,
    )
        .setParameter("ownerId", ownerId)
        .setParameter("status", status)
        .setParameter("executionMode", executionMode)
        .setParameter("now", Instant.now())
        .setMaxResults(1)
        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
        .resultList
        .firstOrNull()
}

interface LocalRunnerConnectionRepository : JpaRepository<LocalRunnerConnection, UUID> {
    fun findAllByOwnerIdOrderByCreatedAtDesc(ownerId: UUID): List<LocalRunnerConnection>
    fun findByIdAndOwnerId(id: UUID, ownerId: UUID): LocalRunnerConnection?
    fun findByTokenHash(tokenHash: String): LocalRunnerConnection?
}
interface ExecutionStepRepository : JpaRepository<ExecutionStep, UUID> {
    fun findAllByExecutionIdOrderByStartedAtAsc(executionId: UUID): List<ExecutionStep>
    fun findByExecutionIdAndStepKey(executionId: UUID, stepKey: String): ExecutionStep?
}
interface ExecutionEventRepository : JpaRepository<ExecutionEvent, UUID> {
    fun findAllByExecutionIdOrderBySequenceNo(executionId: UUID): List<ExecutionEvent>
    fun countByExecutionId(executionId: UUID): Long
}
