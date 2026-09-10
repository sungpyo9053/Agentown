package com.agentvillage.builder

import com.agentvillage.builder.application.BuilderCapabilityResolver
import com.agentvillage.builder.domain.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BuilderCapabilityResolverTest {
    private val resolver = BuilderCapabilityResolver()
    @Test
    fun `public web research is a local read capability not an installed hosted search`() {
        val result = resolver.resolve(bundle(WorkflowNodePlan("research", "local.web.research", "공개 웹 검색")))
        assertThat(result.simulationReady).isFalse()
        assertThat(result.productionReady).isFalse()
        assertThat(result.uncoveredCapabilities).isEmpty()
        val binding = result.bindings.single { it.capabilityKey == "research" }
        assertThat(binding.source).isEqualTo("DOWNLOADED_PACKAGE")
        assertThat(binding.requiresUserAction).isTrue()
    }
    @Test
    fun `local artifact renderer is installed in package but not hosted simulation ready`() {
        val result = resolver.resolve(bundle(WorkflowNodePlan("render", "local.artifact.render", "파일 제작", mapOf("format" to "pptx"))))
        assertThat(result.simulationReady).isFalse()
        assertThat(result.productionReady).isFalse()
        assertThat(result.uncoveredCapabilities).isEmpty()
        assertThat(result.bindings.single { it.capabilityKey == "render" }.requiresUserAction).isTrue()
    }

    @Test
    fun `mock connectors are simulation ready but never production ready`() {
        val bundle = bundle(
            WorkflowNodePlan("slack", "slack.new_message.mock", "Slack 문의"),
            WorkflowNodePlan("notion", "notion.search.mock", "Notion 검색"),
            WorkflowNodePlan("write", "ai.generate", "답변 작성"),
            WorkflowNodePlan("approve", "human.approval", "담당자 승인"),
            WorkflowNodePlan("reply", "slack.reply.mock", "Slack 답변"),
        )

        val result = resolver.resolve(bundle)

        assertThat(result.simulationReady).isTrue()
        assertThat(result.productionReady).isFalse()
        assertThat(result.uncoveredCapabilities).isEmpty()
        assertThat(result.bindings.filter { it.simulationOnly }.map { it.resourceKey })
            .containsExactlyInAnyOrder("connector.slack.mock", "connector.notion.mock", "connector.slack.mock")
        assertThat(result.bindings).anyMatch { it.resourceKey == "platform.structured-ai" }
        assertThat(result.bindings).anyMatch { it.resourceKey == "builtin.human-approval" }
    }

    @Test
    fun `deterministic workflow uses no AI and is production ready`() {
        val result = resolver.resolve(bundle(
            WorkflowNodePlan("manual", "manual.trigger", "수동 시작"),
            WorkflowNodePlan("dedupe", "data.deduplicate", "중복 제거"),
        ))

        assertThat(result.productionReady).isTrue()
        assertThat(result.bindings).noneMatch { it.resourceKey == "platform.structured-ai" }
        assertThat(result.requirements.map { it.executionStrategy }).containsOnly(ExecutionStrategy.DETERMINISTIC)
    }

    @Test
    fun `unknown resources are explicitly uncovered`() {
        val result = resolver.resolve(bundle(WorkflowNodePlan("publish", "wordpress.publish", "WordPress 발행")))

        assertThat(result.simulationReady).isFalse()
        assertThat(result.productionReady).isFalse()
        assertThat(result.uncoveredCapabilities).containsExactly("publish")
        assertThat(result.bindings.single { it.capabilityKey == "publish" }.availability).isEqualTo(ResourceAvailability.MISSING)
    }

    private fun bundle(vararg nodes: WorkflowNodePlan): MetaAgentDesignBundle {
        val list = nodes.toList()
        return MetaAgentDesignBundle(
            AutomationRequirement("테스트", "수동", listOf("입력"), listOf("출력"), list.map { it.label }, emptyList(), emptyList(), false),
            emptyList(),
            AutomationProposal(
                "테스트", "테스트 자동화", list.map { it.label }, emptyList(), emptyList(), "실패 시 중단",
                WorkflowGraphPlan(list.first().id, list, list.zipWithNext().mapIndexed { index, pair -> WorkflowEdgePlan("e$index", pair.first.id, pair.second.id) }),
                HarnessTemplateSelection("test", 1, "BUILT_IN", "테스트"),
            ),
            listOf(AgentDefinition("agent", "테스트", "테스트", listOf(FieldDefinition("input", "string", true, "입력")), listOf(FieldDefinition("output", "string", true, "출력")), listOf("규칙"), listOf("금지"), listOf("근거"))),
            listOf(GuideDefinition("guide", "가이드", "가이드", listOf(GuideField("input", "입력", "text", true, false, "입력")))),
        )
    }
}
