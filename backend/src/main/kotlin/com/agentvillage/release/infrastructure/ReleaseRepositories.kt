package com.agentvillage.release.infrastructure

import com.agentvillage.release.domain.ReleaseEvent
import com.agentvillage.release.domain.ReleaseRecord
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ReleaseRepository : JpaRepository<ReleaseRecord, UUID> {
    fun findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId: UUID): List<ReleaseRecord>
    fun findByIdAndWorkspaceId(id: UUID, workspaceId: UUID): ReleaseRecord?
    fun findByWorkspaceIdAndApprovalIdempotencyKey(workspaceId: UUID, key: String): ReleaseRecord?
    fun findByWorkspaceIdAndReleaseKey(workspaceId: UUID, releaseKey: String): ReleaseRecord?
}
interface ReleaseEventRepository : JpaRepository<ReleaseEvent, UUID> {
    fun findAllByReleaseIdAndWorkspaceIdOrderByCreatedAt(releaseId: UUID, workspaceId: UUID): List<ReleaseEvent>
}
