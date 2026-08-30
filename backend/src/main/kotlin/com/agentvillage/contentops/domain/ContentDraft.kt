package com.agentvillage.contentops.domain

import com.agentvillage.common.domain.AuditedEntity
import com.agentvillage.llmcredential.domain.LlmProvider
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

enum class ContentChannel { WORDPRESS, NAVER, OTHER }
enum class ContentGenerationSource { AGENTOWN_AI, USER_AI, SAFE_TEMPLATE }
enum class ContentDraftStatus { DRAFT, APPROVED }

@Entity
@Table(name = "content_drafts")
class ContentDraft(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "owner_id", nullable = false) val ownerId: UUID,
    @Column(name = "idempotency_key", nullable = false, length = 120) val idempotencyKey: String,
    @Column(name = "brand_name", nullable = false, length = 120) val brandName: String,
    @Column(nullable = false, length = 200) val topic: String,
    @Column(nullable = false, length = 300) val audience: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) val channel: ContentChannel,
    @Column(name = "source_notes", nullable = false, columnDefinition = "text") val sourceNotes: String,
    @Column(name = "evidence_notes", nullable = false, columnDefinition = "text") val evidenceNotes: String,
    @Column(name = "photo_reference_url", length = 1000) val photoReferenceUrl: String?,
    @Column(name = "photo_notes", nullable = false, columnDefinition = "text") val photoNotes: String,
    @Column(name = "style_notes", nullable = false, columnDefinition = "text") val styleNotes: String,
    @Column(nullable = false, length = 200) var title: String,
    @Column(name = "body_markdown", nullable = false, columnDefinition = "text") var bodyMarkdown: String,
    @Column(name = "seo_title", nullable = false, length = 200) var seoTitle: String,
    @Column(name = "meta_description", nullable = false, length = 500) var metaDescription: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "target_keywords", nullable = false, columnDefinition = "jsonb") var targetKeywords: List<String>,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "evidence_used", nullable = false, columnDefinition = "jsonb") var evidenceUsed: List<String>,
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") var warnings: List<String>,
    @Enumerated(EnumType.STRING) @Column(name = "generation_source", nullable = false, length = 40) val generationSource: ContentGenerationSource,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) val provider: LlmProvider,
    @Column(nullable = false, length = 80) val model: String,
    @Column(name = "input_tokens", nullable = false) val inputTokens: Long = 0,
    @Column(name = "output_tokens", nullable = false) val outputTokens: Long = 0,
    @Column(name = "quality_score", nullable = false) var qualityScore: Int = 0,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "quality_checks", nullable = false, columnDefinition = "jsonb") var qualityChecks: List<Map<String, Any>> = emptyList(),
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) var status: ContentDraftStatus = ContentDraftStatus.DRAFT,
    @Column(name = "approved_by") var approvedBy: UUID? = null,
    @Column(name = "approved_at") var approvedAt: Instant? = null,
    @Version var version: Long = 0,
) : AuditedEntity()
