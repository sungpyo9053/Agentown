package com.agentvillage.builder

import com.agentvillage.builder.application.WorkflowGraphPlanNormalizer
import com.agentvillage.builder.domain.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkflowGraphPlanNormalizerTest {
    @Test
    fun `fills registered renderer and normalizes branch edge syntax without changing semantics`() {
        val plan = WorkflowGraphPlan(
            entryNodeId = "branch",
            nodes = listOf(
                WorkflowNodePlan("branch", NodeType.CONDITION_BRANCH.wireName, "유형 분기", mapOf("expression" to "category == BUG")),
                WorkflowNodePlan("render", NodeType.TEMPLATE_RENDER.wireName, "결과 렌더링"),
                WorkflowNodePlan("send", NodeType.SLACK_SEND_MOCK.wireName, "Slack 전송", mapOf("channel" to "#alerts")),
            ),
            edges = listOf(
                WorkflowEdgePlan("bug", "branch", "render", "BUG", emptyList()),
                WorkflowEdgePlan("feature", "branch", "send", "FEATURE"),
            ),
        )

        val normalized = WorkflowGraphPlanNormalizer.normalize(plan)

        assertThat(normalized.edges.map { it.condition }).containsExactly("category=BUG", "category=FEATURE")
        assertThat(normalized.edges).allSatisfy { assertThat(it.bindings).isNotEmpty }
        assertThat(normalized.nodes.filter { it.id in setOf("render", "send") })
            .allSatisfy { assertThat(it.config["rendererKey"]).isEqualTo("plain-text.v1") }
    }

    @Test
    fun `does not disguise ambiguous duplicate default branch conditions`() {
        val plan = WorkflowGraphPlan(
            entryNodeId = "branch",
            nodes = listOf(
                WorkflowNodePlan("branch", NodeType.CONDITION_BRANCH.wireName, "분기", mapOf("expression" to "approved")),
                WorkflowNodePlan("yes", NodeType.WORKFLOW_END.wireName, "승인"),
                WorkflowNodePlan("no", NodeType.WORKFLOW_END.wireName, "거절"),
            ),
            edges = listOf(
                WorkflowEdgePlan("edge-yes", "branch", "yes"),
                WorkflowEdgePlan("edge-no", "branch", "no"),
            ),
        )

        assertThat(WorkflowGraphPlanNormalizer.normalize(plan).edges.map { it.condition })
            .containsExactly("success", "success")
    }
}
