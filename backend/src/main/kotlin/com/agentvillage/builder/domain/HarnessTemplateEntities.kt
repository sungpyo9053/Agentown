package com.agentvillage.builder.domain

import com.agentvillage.common.domain.AuditedEntity
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

enum class HarnessTemplateStatus { DRAFT, PREVIEWED, APPROVED, ACTIVE, DEPRECATED }
enum class HarnessTemplateVersionState { DRAFT, PREVIEWED, APPROVED, ACTIVE, DEPRECATED }
enum class HarnessTemplateSource { BUILT_IN, GENERATED, NOTION }

@Entity @Table(name = "output_templates")
class HarnessTemplate(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "template_key", nullable = false, unique = true, length = 100) val templateKey: String,
    @Column(nullable = false, length = 200) var name: String,
    @Column(nullable = false, length = 80) var category: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) var status: HarnessTemplateStatus = HarnessTemplateStatus.DRAFT,
    @Column(name = "active_version_no") var activeVersionNo: Int? = null,
) : AuditedEntity()

@Entity @Table(name = "output_template_versions")
class HarnessTemplateVersion(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "template_id", nullable = false) val templateId: UUID,
    @Column(name = "version_no", nullable = false) val versionNo: Int,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) var state: HarnessTemplateVersionState = HarnessTemplateVersionState.DRAFT,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) val source: HarnessTemplateSource,
    @Column(name = "notion_page_id", length = 100) val notionPageId: String?,
    @Column(name = "content_hash", nullable = false, length = 64) val contentHash: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "intent_examples_json", nullable = false, columnDefinition = "jsonb") val intentExamples: List<String>,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "required_facts_json", nullable = false, columnDefinition = "jsonb") val requiredFacts: List<String>,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "template_definition_json", nullable = false, columnDefinition = "jsonb") val templateDefinition: Map<String, Any?>,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "output_schema_json", nullable = false, columnDefinition = "jsonb") val outputSchema: Map<String, Any?>,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "acceptance_cases_json", nullable = false, columnDefinition = "jsonb") val acceptanceCases: List<Map<String, Any?>>,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "execution_contract_json", nullable = false, columnDefinition = "jsonb") val executionContract: Map<String, Any?>,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "validation_json", nullable = false, columnDefinition = "jsonb") val validation: Map<String, Any?>,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)

@Entity @Table(name = "output_template_sync_runs")
class HarnessTemplateSyncRun(
    @Id val id: UUID = UUID.randomUUID(),
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) val source: HarnessTemplateSource,
    @Column(nullable = false, length = 30) var status: String = "RUNNING",
    @Column(name = "imported_count", nullable = false) var importedCount: Int = 0,
    @Column(name = "rejected_count", nullable = false) var rejectedCount: Int = 0,
    @Column(name = "failure_summary", length = 500) var failureSummary: String? = null,
    @Column(name = "started_at", nullable = false) val startedAt: Instant = Instant.now(),
    @Column(name = "finished_at") var finishedAt: Instant? = null,
)
