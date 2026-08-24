package com.agentvillage.builder

import com.agentvillage.builder.application.WorkflowGraphValidator
import com.agentvillage.builder.application.WorkflowNodeCatalog
import com.agentvillage.builder.domain.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WorkflowGraphValidatorTest {
    private val validator = WorkflowGraphValidator(WorkflowNodeCatalog(), jacksonObjectMapper())

    private fun graph(approval: Boolean = true): WorkflowGraph {
        val nodes = mutableListOf(
            WorkflowNode("trigger", "slack.new_message.mock", "trigger", NodePosition(0.0, 0.0)),
            WorkflowNode("search", "notion.search.mock", "search", NodePosition(1.0, 0.0), mapOf("database" to "FAQ")),
            WorkflowNode("generate", "ai.generate", "generate", NodePosition(2.0, 0.0), mapOf("instruction" to "answer")),
        )
        if (approval) nodes += WorkflowNode("approval", "human.approval", "approval", NodePosition(3.0, 0.0), mapOf("approver" to "owner"))
        nodes += WorkflowNode("reply", "slack.reply.mock", "reply", NodePosition(4.0, 0.0))
        return WorkflowGraph(workflowId = UUID.randomUUID(), entryNodeId = "trigger", nodes = nodes, edges = nodes.zipWithNext().mapIndexed { index, pair -> WorkflowEdge("e$index", pair.first.id, pair.second.id) })
    }

    @Test fun `valid workflow graph is accepted`() = assertThat(validator.validate(graph()).valid).isTrue()

    @Test fun `unknown node is rejected`() {
        val graph = graph().let { it.copy(nodes = it.nodes + WorkflowNode("evil", "code.python", "evil", NodePosition(0.0, 0.0)), edges = it.edges + WorkflowEdge("evil-edge", "reply", "evil")) }
        assertThat(validator.validate(graph).issues.map { it.code }).contains("NODE_TYPE_NOT_ALLOWED")
    }

    @Test fun `edge to missing node is rejected`() {
        val graph = graph().let { it.copy(edges = it.edges + WorkflowEdge("bad", "reply", "missing")) }
        assertThat(validator.validate(graph).issues.map { it.code }).contains("INVALID_EDGE")
    }

    @Test fun `required config is validated`() {
        val graph = graph().let { original -> original.copy(nodes = original.nodes.map { if (it.id == "search") it.copy(config = emptyMap()) else it }) }
        assertThat(validator.validate(graph).issues.map { it.code }).contains("INVALID_NODE_CONFIG")
    }

    @Test fun `every path to external write requires approval`() {
        assertThat(validator.validate(graph(approval = false)).issues.map { it.code }).contains("WRITE_REQUIRES_APPROVAL")
        val bypass = graph().let { it.copy(edges = it.edges + WorkflowEdge("bypass", "generate", "reply")) }
        assertThat(validator.validate(bypass).issues.map { it.code }).contains("WRITE_REQUIRES_APPROVAL")
    }

    @Test fun `FAQ template is rejected when requirement does not request Slack or Notion`() {
        val requirement = AutomationRequirement(
            objective = "사용자가 입력한 문장을 분류한다.",
            trigger = "수동 실행",
            inputs = listOf("분류할 문장"),
            outputs = listOf("분류 결과"),
            steps = listOf("문장 분류"),
            decisions = listOf("카테고리 선택"),
            exceptions = emptyList(),
            humanApprovalRequired = false,
        )
        val proposal = AutomationProposal("문장 분류", "문장을 분류한다.", listOf("문장 분류"), emptyList(), emptyList(), "실패 시 중단")
        val agent = AgentDefinition("classifier", "분류 담당", "문장을 분류한다.", emptyList(), emptyList(), listOf("분류한다"), listOf("추측하지 않는다"), listOf("입력 문장"))

        val result = validator.validate(graph(), requirement, proposal, listOf(agent))

        assertThat(result.valid).isFalse()
        assertThat(result.issues.map { it.code }).contains(
            "MEANING_TRIGGER_MISSING",
            "MEANING_DECISION_MISSING",
            "MEANING_UNREQUESTED_INTEGRATION",
        )
    }

    @Test fun `dynamic manual classification graph matches its requirement`() {
        val requirement = AutomationRequirement(
            objective = "사용자가 입력한 문장을 분류한다.",
            trigger = "수동 실행",
            inputs = listOf("분류할 문장"),
            outputs = listOf("분류 결과"),
            steps = listOf("문장 분류"),
            decisions = listOf("카테고리 선택"),
            exceptions = emptyList(),
            humanApprovalRequired = false,
        )
        val proposal = AutomationProposal("문장 분류", "문장을 분류한다.", listOf("문장 분류"), emptyList(), emptyList(), "실패 시 중단")
        val agent = AgentDefinition("classifier", "분류 담당", "문장을 분류한다.", emptyList(), emptyList(), listOf("분류한다"), listOf("추측하지 않는다"), listOf("입력 문장"))
        val nodes = listOf(
            WorkflowNode("manual", NodeType.MANUAL_TRIGGER.wireName, "수동 시작", NodePosition(0.0, 0.0)),
            WorkflowNode("classify", NodeType.AI_CLASSIFY.wireName, "문장 분류", NodePosition(1.0, 0.0), mapOf("categories" to listOf("문의", "요청"), "agentKey" to "classifier")),
        )
        val dynamic = WorkflowGraph(
            workflowId = UUID.randomUUID(),
            entryNodeId = "manual",
            nodes = nodes,
            edges = listOf(WorkflowEdge("edge", "manual", "classify")),
        )

        assertThat(validator.validate(dynamic, requirement, proposal, listOf(agent)).valid).isTrue()
    }

    @Test fun `structured requirement cannot add Slack FAQ meaning absent from user request`() {
        val requirement = AutomationRequirement(
            objective = "Slack 문의를 Notion FAQ로 답변한다.",
            trigger = "Slack 문의 수신",
            inputs = listOf("고객 문의", "Notion FAQ"),
            outputs = listOf("답변 초안"),
            steps = listOf("FAQ 검색", "답변 작성"),
            decisions = emptyList(),
            exceptions = emptyList(),
            humanApprovalRequired = false,
        )
        val proposal = AutomationProposal("FAQ 답변", "FAQ로 답변한다.", listOf("FAQ 답변"), listOf("Slack Mock", "Notion Mock"), emptyList(), "실패 시 중단")

        val result = validator.validate(graph(approval = false), requirement, proposal, emptyList(), "사용자가 입력한 문장을 분류한다.")

        assertThat(result.issues.map { it.code }).contains("MEANING_REQUIREMENT_ADDED")
        assertThat(result.issues.map { it.message }).anyMatch { it.contains("Slack") }
        assertThat(result.issues.map { it.message }).anyMatch { it.contains("Notion/FAQ") }
    }
}
