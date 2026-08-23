package com.agentvillage.builder.domain

import java.util.UUID

enum class WorkflowStatus {
    DRAFT, NEEDS_CLARIFICATION, PROPOSAL_READY, WAITING_DESIGN_APPROVAL, APPROVED,
    COMPILING, VALIDATING, READY_TO_SIMULATE, SIMULATING, SIMULATION_FAILED,
    READY_TO_ACTIVATE, ACTIVE, FAILED,
}

enum class BuilderRunStatus { RUNNING, WAITING_APPROVAL, SUCCEEDED, FAILED }
enum class BuilderStepStatus { PENDING, RUNNING, WAITING_APPROVAL, SUCCEEDED, FAILED }
enum class ApprovalType { DESIGN, EXECUTION, ACTIVATION }
enum class ApprovalStatus { PENDING, APPROVED, REJECTED }

enum class NodeType(val wireName: String, val riskLevel: String) {
    MANUAL_TRIGGER("manual.trigger", "LOW"),
    TEXT_INPUT("text.input", "LOW"),
    CONDITION_BRANCH("condition.branch", "LOW"),
    AI_CLASSIFY("ai.classify", "MEDIUM"),
    AI_GENERATE("ai.generate", "MEDIUM"),
    HUMAN_APPROVAL("human.approval", "MEDIUM"),
    SLACK_NEW_MESSAGE_MOCK("slack.new_message.mock", "LOW"),
    SLACK_REPLY_MOCK("slack.reply.mock", "HIGH"),
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
)

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
