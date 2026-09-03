package com.agentvillage.builder.domain

import com.agentvillage.common.domain.AuditedEntity
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity @Table(name = "builder_workspaces")
class BuilderWorkspace(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "owner_id", nullable = false) val ownerId: UUID,
    @Column(nullable = false, length = 120) var name: String = "내 워크스페이스",
) : AuditedEntity()

@Entity @Table(name = "builder_conversations")
class BuilderConversation(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "workspace_id", nullable = false) val workspaceId: UUID,
    @Column(name = "workflow_id", nullable = false) val workflowId: UUID,
    @Column(nullable = false, length = 160) var title: String = "새 업무 자동화",
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) val purpose: BuilderConversationPurpose = BuilderConversationPurpose.AUTOMATION,
    @Column(name = "idempotency_key", nullable = false, length = 120) val idempotencyKey: String,
) : AuditedEntity()

enum class BuilderConversationPurpose { AUTOMATION, AGENT_DEVELOPMENT }

@Entity @Table(name = "builder_messages")
class BuilderMessage(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "conversation_id", nullable = false) val conversationId: UUID,
    @Column(nullable = false, length = 20) val role: String,
    @Column(nullable = false, columnDefinition = "text") val content: String,
    @Column(name = "workflow_version_id") val workflowVersionId: UUID? = null,
    @Column(name = "idempotency_key", length = 120) val idempotencyKey: String? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)

@Entity @Table(name = "builder_requirements")
class BuilderRequirementEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "conversation_id", nullable = false, unique = true) val conversationId: UUID,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "structured_json", nullable = false, columnDefinition = "jsonb")
    var structuredJson: Map<String, Any?>,
) : AuditedEntity()

@Entity @Table(name = "builder_proposals")
class BuilderProposalEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "conversation_id", nullable = false, unique = true) val conversationId: UUID,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "proposal_json", nullable = false, columnDefinition = "jsonb") var proposalJson: Map<String, Any?>,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "agent_definitions_json", nullable = false, columnDefinition = "jsonb") var agentDefinitionsJson: List<Map<String, Any?>>,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "guide_definitions_json", nullable = false, columnDefinition = "jsonb") var guideDefinitionsJson: List<Map<String, Any?>>,
) : AuditedEntity()

enum class AgentGenerationDraftState { STARTED, GENERATED, VALIDATION_FAILED, COMPLETED, FAILED }

@Entity @Table(name = "builder_agent_generation_drafts")
class AgentGenerationDraftEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "conversation_id", nullable = false, unique = true) val conversationId: UUID,
    @Column(name = "workflow_id", nullable = false) val workflowId: UUID,
    @Column(name = "source_instruction", nullable = false, columnDefinition = "text") var sourceInstruction: String,
    @Column(name = "design_mode", nullable = false, length = 40) var designMode: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) var state: AgentGenerationDraftState = AgentGenerationDraftState.STARTED,
    @Column(nullable = false) var attempt: Int = 0,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "bundle_json", columnDefinition = "jsonb") var bundleJson: Map<String, Any?>? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "validation_issues_json", nullable = false, columnDefinition = "jsonb") var validationIssuesJson: List<Map<String, Any?>> = emptyList(),
    @Column(name = "error_message", length = 500) var errorMessage: String? = null,
) : AuditedEntity()

@Entity @Table(name = "builder_workflows")
class BuilderWorkflow(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "workspace_id", nullable = false) val workspaceId: UUID,
    @Column(name = "conversation_id", nullable = false, unique = true) val conversationId: UUID,
    @Column(nullable = false, length = 160) var name: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) var status: WorkflowStatus = WorkflowStatus.DRAFT,
    @Column(name = "current_version_id") var currentVersionId: UUID? = null,
    @Column(name = "approved_version_id") var approvedVersionId: UUID? = null,
    @Version @Column(name = "lock_version", nullable = false) var lockVersion: Long = 0,
) : AuditedEntity()

@Entity @Table(name = "builder_workflow_versions")
class BuilderWorkflowVersion(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "workflow_id", nullable = false) val workflowId: UUID,
    @Column(name = "version_no", nullable = false) val versionNo: Int,
    @Column(name = "parent_version_id") val parentVersionId: UUID? = null,
    @Column(name = "template_version_id") val templateVersionId: UUID?,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "execution_contract_json", nullable = false, columnDefinition = "jsonb") val executionContractJson: Map<String, Any?>,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "graph_json", nullable = false, columnDefinition = "jsonb") val graphJson: Map<String, Any?>,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "design_snapshot_json", nullable = false, columnDefinition = "jsonb") val designSnapshotJson: Map<String, Any?> = emptyMap(),
    @Column(name = "graph_hash", nullable = false, length = 64) val graphHash: String,
    @Column(name = "change_summary", nullable = false, length = 500) val changeSummary: String,
    @Column(nullable = false) var approved: Boolean = false,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)

@Entity @Table(name = "builder_approvals")
class BuilderApproval(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "workspace_id", nullable = false) val workspaceId: UUID,
    @Column(name = "workflow_id", nullable = false) val workflowId: UUID,
    @Column(name = "run_id") val runId: UUID? = null,
    @Enumerated(EnumType.STRING) @Column(name = "approval_type", nullable = false, length = 30) val approvalType: ApprovalType,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) var status: ApprovalStatus = ApprovalStatus.PENDING,
    @Column(name = "idempotency_key", nullable = false, length = 120) var idempotencyKey: String,
    @Column(name = "decided_by") var decidedBy: UUID? = null,
    @Column(name = "decided_at") var decidedAt: Instant? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)

@Entity @Table(name = "builder_runs")
class BuilderRun(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "workspace_id", nullable = false) val workspaceId: UUID,
    @Column(name = "workflow_id", nullable = false) val workflowId: UUID,
    @Column(name = "workflow_version_id", nullable = false) val workflowVersionId: UUID,
    @Column(name = "template_version_id") val templateVersionId: UUID?,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) var status: BuilderRunStatus = BuilderRunStatus.RUNNING,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "input_json", nullable = false, columnDefinition = "jsonb") val inputJson: Map<String, Any?>,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "output_json", columnDefinition = "jsonb") var outputJson: Map<String, Any?>? = null,
    @Column(name = "current_node_id", length = 100) var currentNodeId: String? = null,
    @Column(name = "idempotency_key", nullable = false, length = 120) val idempotencyKey: String,
    @Column(name = "requirement_matched") var requirementMatched: Boolean? = null,
) : AuditedEntity()

@Entity @Table(name = "builder_step_runs")
class BuilderStepRun(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "run_id", nullable = false) val runId: UUID,
    @Column(name = "node_id", nullable = false, length = 100) val nodeId: String,
    @Column(name = "node_type", nullable = false, length = 80) val nodeType: String,
    @Column(name = "sequence_no", nullable = false) val sequenceNo: Int,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) var status: BuilderStepStatus,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "input_json", nullable = false, columnDefinition = "jsonb") val inputJson: Map<String, Any?>,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "output_json", columnDefinition = "jsonb") var outputJson: Map<String, Any?>? = null,
    @Column(name = "error_message", length = 1000) var errorMessage: String? = null,
) : AuditedEntity()

@Entity @Table(name = "builder_meta_agent_runs")
class MetaAgentRun(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "trace_id", nullable = false) val traceId: UUID,
    @Column(name = "workspace_id", nullable = false) val workspaceId: UUID,
    @Column(name = "conversation_id", nullable = false) val conversationId: UUID,
    @Column(name = "workflow_id", nullable = false) val workflowId: UUID,
    @Column(nullable = false, length = 60) val stage: String,
    @Column(nullable = false, length = 20) val status: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "input_summary", nullable = false, columnDefinition = "jsonb") val inputSummary: Map<String, Any?>,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "output_summary", columnDefinition = "jsonb") val outputSummary: Map<String, Any?>? = null,
    @Column(name = "error_code", length = 80) val errorCode: String? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "failure_summary", columnDefinition = "jsonb") val failureSummary: Map<String, Any?>? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)

enum class BuilderGenerationStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
enum class BuilderGenerationStage { REQUEST_ACCEPTED, CODEX_ANALYZING, STRUCTURE_VALIDATING, DESIGN_SAVING, COMPLETED, FAILED, CANCELLED }

@Entity @Table(name = "builder_generation_jobs")
class BuilderGenerationJob(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "workspace_id", nullable = false) val workspaceId: UUID,
    @Column(name = "conversation_id", nullable = false) val conversationId: UUID,
    @Column(name = "workflow_id", nullable = false) val workflowId: UUID,
    @Column(nullable = false, columnDefinition = "text") val instruction: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) var status: BuilderGenerationStatus = BuilderGenerationStatus.QUEUED,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) var stage: BuilderGenerationStage = BuilderGenerationStage.REQUEST_ACCEPTED,
    @Column(name = "estimated_seconds", nullable = false) val estimatedSeconds: Int = 90,
    @Column(name = "idempotency_key", nullable = false, length = 120) val idempotencyKey: String,
    @Column(name = "error_code", length = 80) var errorCode: String? = null,
    @Column(name = "error_message", length = 500) var errorMessage: String? = null,
    @Column(name = "started_at") var startedAt: Instant? = null,
    @Column(name = "finished_at") var finishedAt: Instant? = null,
) : AuditedEntity()

@Entity @Table(name = "builder_usage_records")
class BuilderUsageRecord(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "owner_id", nullable = false) val ownerId: UUID,
    @Column(name = "conversation_id", nullable = false) val conversationId: UUID,
    @Column(name = "workflow_id", nullable = false) val workflowId: UUID,
    @Column(name = "usage_type", nullable = false, length = 40) val usageType: String = "BUILDER_CODEX_DESIGN",
    @Column(name = "limit_slot", length = 20) val limitSlot: String?,
    @Column(name = "idempotency_key", nullable = false, length = 120) val idempotencyKey: String,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)

@Entity @Table(name = "builder_automation_teams")
class BuilderAutomationTeam(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "workspace_id", nullable = false) val workspaceId: UUID,
    @Column(name = "workflow_id", nullable = false) val workflowId: UUID,
    @Column(name = "workflow_version_id", nullable = false, unique = true) val workflowVersionId: UUID,
    @Column(nullable = false, length = 80) val name: String,
    @Column(nullable = false, length = 40) val category: String = "업무 자동화",
) : AuditedEntity()

@Entity @Table(name = "builder_automation_team_members")
class BuilderAutomationTeamMember(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "team_id", nullable = false) val teamId: UUID,
    @Column(name = "agent_id", nullable = false, unique = true) val agentId: UUID,
    @Column(name = "agent_key", nullable = false, length = 60) val agentKey: String,
    @Column(name = "sequence_no", nullable = false) val sequenceNo: Int,
    @Column(name = "agent_markdown", nullable = false, columnDefinition = "text") val agentMarkdown: String,
    @Column(name = "guide_markdown", nullable = false, columnDefinition = "text") val guideMarkdown: String,
) : AuditedEntity()
