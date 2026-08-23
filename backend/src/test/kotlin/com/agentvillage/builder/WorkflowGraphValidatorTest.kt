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
}
