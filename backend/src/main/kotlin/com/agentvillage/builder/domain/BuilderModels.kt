package com.agentvillage.builder.domain

import java.util.UUID

enum class WorkflowStatus {
    DRAFT, NEEDS_CLARIFICATION, PROPOSAL_READY, WAITING_DESIGN_APPROVAL, APPROVED,
    COMPILING, VALIDATING, READY_TO_SIMULATE, SIMULATING, SIMULATION_FAILED,
    READY_TO_ACTIVATE, ACTIVE, FAILED, STOPPED,
}

enum class BuilderRunStatus { RUNNING, WAITING_APPROVAL, SUCCEEDED, FAILED }
enum class BuilderStepStatus { PENDING, RUNNING, WAITING_APPROVAL, SUCCEEDED, FAILED }
enum class ApprovalType { DESIGN, EXECUTION, ACTIVATION, STOP }
enum class ApprovalStatus { PENDING, APPROVED, REJECTED }

enum class NodeType(val wireName: String, val riskLevel: String) {
    MANUAL_TRIGGER("manual.trigger", "LOW"),
    SCHEDULE_TRIGGER("schedule.trigger", "LOW"),
    TEXT_INPUT("text.input", "LOW"),
    NEWS_SEARCH_MOCK("news.search.mock", "LOW"),
    DATA_DEDUPLICATE("data.deduplicate", "LOW"),
    CONDITION_BRANCH("condition.branch", "LOW"),
    AI_CLASSIFY("ai.classify", "MEDIUM"),
    AI_GENERATE("ai.generate", "MEDIUM"),
    HUMAN_APPROVAL("human.approval", "MEDIUM"),
    SLACK_NEW_MESSAGE_MOCK("slack.new_message.mock", "LOW"),
    SLACK_REPLY_MOCK("slack.reply.mock", "HIGH"),
    SLACK_SEND_MOCK("slack.send.mock", "HIGH"),
    NOTION_SEARCH_MOCK("notion.search.mock", "LOW"),
    NOTION_READ_PAGE_MOCK("notion.read_page.mock", "LOW");

    companion object {
        fun fromWire(value: String): NodeType? = entries.firstOrNull { it.wireName == value }
    }
}

data class AutomationRequirement(
    val objective: String,
    val trigger: String,
    val inputs: List<String>,
    val outputs: List<String>,
    val steps: List<String>,
    val decisions: List<String>,
    val exceptions: List<String>,
    val humanApprovalRequired: Boolean,
)

data class ClarificationQuestion(val id: String, val field: String, val question: String, val required: Boolean = true)

data class AutomationProposal(
    val name: String,
    val summary: String,
    val capabilities: List<String>,
    val integrations: List<String>,
    val approvalPoints: List<String>,
    val failurePolicy: String,
    val graphPlan: WorkflowGraphPlan? = null,
    val templateSelection: HarnessTemplateSelection? = null,
    val economics: HarnessDesignEconomics? = null,
    val outputSchema: List<FieldDefinition> = emptyList(),
    val executionContract: TemplateExecutionContract? = null,
    val templateRevisionPreview: TemplateRevisionPreview? = null,
)

data class HarnessTemplateSelection(
    val templateKey: String,
    val version: Int,
    val source: String,
    val matchReason: String,
)

data class HarnessDesignEconomics(
    val agentCount: Int,
    val estimatedAiCallsPerRun: Int,
    val separationRationale: List<String>,
)

data class TemplateExecutionContract(
    val contentSchemaVersion: String,
    val rendererKey: String,
    val rendererVersion: String,
    val qualityRuleVersion: String,
    val promptVersion: String,
    val modelPolicy: Map<String, Any?>,
    val sourcePolicyVersion: String,
    val qualityRules: Map<String, Any?>,
)

data class TemplateRevisionPreview(
    val baseVersion: Int,
    val previewVersion: Int,
    val request: String,
    val changes: Map<String, Any?>,
)

data class WorkflowNodePlan(
    val id: String,
    val nodeType: String,
    val label: String,
    val config: Map<String, Any?> = emptyMap(),
)
data class WorkflowEdgePlan(val id: String, val source: String, val target: String, val condition: String = "success")
data class WorkflowGraphPlan(val entryNodeId: String, val nodes: List<WorkflowNodePlan>, val edges: List<WorkflowEdgePlan>)

data class FieldDefinition(val name: String, val type: String, val required: Boolean, val description: String)

data class AgentDefinition(
    val key: String,
    val name: String,
    val role: String,
    val inputSchema: List<FieldDefinition>,
    val outputSchema: List<FieldDefinition>,
    val behaviorRules: List<String>,
    val forbiddenRules: List<String>,
    val evidenceRequirements: List<String>,
) {
    fun toMarkdown(): String = buildString {
        appendLine("# $name")
        appendLine(); appendLine("## Role"); appendLine(role)
        appendLine(); appendLine("## Behavior rules"); behaviorRules.forEach { appendLine("- $it") }
        appendLine(); appendLine("## Forbidden"); forbiddenRules.forEach { appendLine("- $it") }
        appendLine(); appendLine("## Evidence"); evidenceRequirements.forEach { appendLine("- $it") }
    }
}

data class GuideField(val key: String, val label: String, val type: String, val required: Boolean, val secret: Boolean = false, val help: String)
data class GuideDefinition(val key: String, val title: String, val description: String, val fields: List<GuideField>) {
    fun toMarkdown(): String = buildString {
        appendLine("# $title"); appendLine(); appendLine(description)
        fields.forEach { appendLine("- ${it.label}: ${it.help}") }
    }
}

data class MetaAgentDesignBundle(
    val requirement: AutomationRequirement,
    val clarificationQuestions: List<ClarificationQuestion>,
    val proposal: AutomationProposal,
    val agentDefinitions: List<AgentDefinition>,
    val guideDefinitions: List<GuideDefinition>,
)

data class NodePosition(val x: Double, val y: Double)
data class WorkflowNode(
    val id: String,
    val nodeType: String,
    val label: String,
    val position: NodePosition,
    val config: Map<String, Any?> = emptyMap(),
    val connectionId: UUID? = null,
)
data class WorkflowEdge(val id: String, val source: String, val target: String, val condition: String = "success")
data class WorkflowGraph(
    val schemaVersion: String = "1.0",
    val workflowId: UUID,
    val entryNodeId: String,
    val nodes: List<WorkflowNode>,
    val edges: List<WorkflowEdge>,
)

sealed interface GraphPatchOperation
data class AddNode(val node: WorkflowNode) : GraphPatchOperation
data class RemoveNode(val nodeId: String) : GraphPatchOperation
data class UpdateNodeConfig(val nodeId: String, val config: Map<String, Any?>) : GraphPatchOperation
data class MoveNode(val nodeId: String, val position: NodePosition) : GraphPatchOperation
data class AddEdge(val edge: WorkflowEdge) : GraphPatchOperation
data class RemoveEdge(val edgeId: String) : GraphPatchOperation
data class GraphPatch(val baseVersionId: UUID, val expectedGraphHash: String, val operations: List<GraphPatchOperation>, val summary: String)

data class ValidationIssue(val code: String, val message: String, val nodeId: String? = null)
data class WorkflowValidationResult(val valid: Boolean, val validatorVersion: String = "builder-validator-1", val graphHash: String, val issues: List<ValidationIssue>)

data class SimulationResult(
    val runId: UUID,
    val status: BuilderRunStatus,
    val currentNodeId: String?,
    val output: Map<String, Any?>?,
    val requirementMatched: Boolean?,
)
