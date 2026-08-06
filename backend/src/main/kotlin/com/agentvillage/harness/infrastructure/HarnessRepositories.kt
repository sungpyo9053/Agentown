package com.agentvillage.harness.infrastructure

import com.agentvillage.harness.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface HarnessRepository : JpaRepository<Harness, UUID> {
    fun findByIdAndOwnerId(id: UUID, ownerId: UUID): Harness?
    fun findAllByOwnerIdOrderByCreatedAtDesc(ownerId: UUID): List<Harness>
    fun findTop100ByOrderByCreatedAtDesc(): List<Harness>
    fun countByStatus(status: HarnessStatus): Long
}
interface HarnessStepRepository : JpaRepository<HarnessStep, UUID> {
    fun findAllByHarnessIdOrderBySequenceNo(harnessId: UUID): List<HarnessStep>
    fun deleteAllByHarnessId(harnessId: UUID)
}
interface HarnessEdgeRepository : JpaRepository<HarnessEdge, UUID> {
    fun findAllByHarnessId(harnessId: UUID): List<HarnessEdge>
    fun deleteAllByHarnessId(harnessId: UUID)
}
interface HarnessVersionRepository : JpaRepository<HarnessVersion, UUID> {
    fun findFirstByHarnessIdOrderByCreatedAtDesc(harnessId: UUID): HarnessVersion?
}
