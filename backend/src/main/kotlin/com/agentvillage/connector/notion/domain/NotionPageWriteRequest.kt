package com.agentvillage.connector.notion.domain

import com.agentvillage.common.domain.AuditedEntity
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

enum class NotionPageWriteStatus { PREVIEWED, APPROVED, PUBLISHING, SUCCEEDED, FAILED }

@Entity
@Table(name = "notion_page_write_requests")
class NotionPageWriteRequest(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "workspace_id", nullable = false) val workspaceId: UUID,
    @Column(name = "connection_id", nullable = false) val connectionId: UUID,
    @Column(name = "idempotency_key", nullable = false, length = 120) val idempotencyKey: String,
    @Column(name = "approval_idempotency_key", length = 120) var approvalIdempotencyKey: String? = null,
    @Column(name = "parent_page_id", nullable = false, length = 120) val parentPageId: String,
    @Column(nullable = false, length = 200) val title: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "content_json", nullable = false, columnDefinition = "jsonb") val content: Map<String, Any?>,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) var status: NotionPageWriteStatus = NotionPageWriteStatus.PREVIEWED,
    @Column(name = "notion_page_id", length = 120) var notionPageId: String? = null,
    @Column(name = "notion_url", length = 500) var notionUrl: String? = null,
    @Column(name = "failure_code", length = 80) var failureCode: String? = null,
    @Column(name = "failure_message", length = 500) var failureMessage: String? = null,
    @Column(name = "approved_by") var approvedBy: UUID? = null,
    @Column(name = "approved_at") var approvedAt: Instant? = null,
) : AuditedEntity()
