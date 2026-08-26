package com.agentvillage.connector.notion.infrastructure

import com.agentvillage.connector.notion.domain.NotionPageWriteRequest
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface NotionPageWriteRepository : JpaRepository<NotionPageWriteRequest, UUID> {
    fun findByWorkspaceIdAndIdempotencyKey(workspaceId: UUID, idempotencyKey: String): NotionPageWriteRequest?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from NotionPageWriteRequest request where request.id = :id and request.workspaceId = :workspaceId")
    fun findOwnedForUpdate(id: UUID, workspaceId: UUID): NotionPageWriteRequest?
}
