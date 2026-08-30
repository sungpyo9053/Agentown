package com.agentvillage.contentops.infrastructure

import com.agentvillage.contentops.domain.ContentDraft
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface ContentDraftRepository : JpaRepository<ContentDraft, UUID> {
    fun findByIdAndOwnerId(id: UUID, ownerId: UUID): ContentDraft?
    fun findByOwnerIdAndIdempotencyKey(ownerId: UUID, idempotencyKey: String): ContentDraft?
    fun findTop50ByOwnerIdOrderByUpdatedAtDesc(ownerId: UUID): List<ContentDraft>
    fun countByOwnerIdAndGenerationSourceAndCreatedAtGreaterThanEqual(ownerId: UUID, source: com.agentvillage.contentops.domain.ContentGenerationSource, since: Instant): Long
}
