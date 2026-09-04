package com.agentvillage.builder

import com.agentvillage.builder.application.WorkflowGraphTranslator
import com.agentvillage.builder.application.WorkflowNodeCatalog
import com.agentvillage.builder.domain.AutomationProposal
import com.agentvillage.builder.domain.NodeType
import com.agentvillage.builder.domain.WorkflowEdgePlan
import com.agentvillage.builder.domain.WorkflowGraphPlan
import com.agentvillage.builder.domain.WorkflowNodePlan
import com.agentvillage.common.exception.BadRequestException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class WorkflowGraphTranslatorTest {
    private val translator = WorkflowGraphTranslator(WorkflowNodeCatalog())

    @Test fun `graph is translated in stable topological order and preserves bindings`() {
        val plan = WorkflowGraphPlan(
            entryNodeId = "start",
            nodes = listOf(
                WorkflowNodePlan("end", NodeType.WORKFLOW_END.wireName, "완료"),
                WorkflowNodePlan("right", NodeType.QUALITY_CHECK.wireName, "오른쪽", mapOf("rules" to listOf("근거 확인"))),
                WorkflowNodePlan("start", NodeType.MANUAL_TRIGGER.wireName, "시작"),
                WorkflowNodePlan("left", NodeType.DATA_NORMALIZE.wireName, "왼쪽", mapOf("rules" to listOf("trim"))),
            ),
            edges = listOf(
                WorkflowEdgePlan("e3", "right", "end", bindings = listOf(com.agentvillage.builder.domain.WorkflowFieldBinding("checked", "result"))),
                WorkflowEdgePlan("e1", "start", "left", bindings = listOf(com.agentvillage.builder.domain.WorkflowFieldBinding("context", "context"))),
                WorkflowEdgePlan("e2", "start", "right", bindings = listOf(com.agentvillage.builder.domain.WorkflowFieldBinding("context", "context"))),
                WorkflowEdgePlan("e4", "left", "end", bindings = listOf(com.agentvillage.builder.domain.WorkflowFieldBinding("normalized", "result"))),
            ),
        )
        val graph = translator.translate(UUID.randomUUID(), proposal(plan))

        assertThat(graph.nodes.map { it.id }).containsExactly("start", "left", "right", "end")
        assertThat(graph.edges.single { it.id == "e3" }.bindings).containsEntry("result", "checked")
        assertThat(graph.nodes.single { it.id == "end" }.position.x).isGreaterThan(graph.nodes.single { it.id == "right" }.position.x)
    }

    @Test fun `cyclic visual plan is rejected before execution`() {
        val plan = WorkflowGraphPlan(
            entryNodeId = "a",
            nodes = listOf(
                WorkflowNodePlan("a", NodeType.MANUAL_TRIGGER.wireName, "시작"),
                WorkflowNodePlan("b", NodeType.WORKFLOW_END.wireName, "완료"),
            ),
            edges = listOf(WorkflowEdgePlan("e1", "a", "b"), WorkflowEdgePlan("e2", "b", "a")),
        )
        assertThatThrownBy { translator.translate(UUID.randomUUID(), proposal(plan)) }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("순환")
    }

    private fun proposal(plan: WorkflowGraphPlan) = AutomationProposal("테스트", "테스트", listOf("테스트"), emptyList(), emptyList(), "중단", plan)
}
