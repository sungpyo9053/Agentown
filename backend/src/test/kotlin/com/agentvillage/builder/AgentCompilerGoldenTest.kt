package com.agentvillage.builder

import com.agentvillage.builder.application.BuilderJobProgressService
import com.agentvillage.builder.application.DeterministicMockMetaAgentModel
import com.agentvillage.builder.application.MetaAgentAuditService
import com.agentvillage.builder.application.PipelineContext
import com.agentvillage.builder.application.StructuredMetaAgentPipeline
import com.agentvillage.builder.infrastructure.MetaAgentRunRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

class AgentCompilerGoldenTest {
    private val mapper = jacksonObjectMapper()
    private val runs = mock<MetaAgentRunRepository>().also {
        whenever(it.save(any())).thenAnswer { invocation -> invocation.arguments[0] }
    }
    private val pipeline = StructuredMetaAgentPipeline(
        DeterministicMockMetaAgentModel(mapper),
        mapper,
        MetaAgentAuditService(runs),
        mock<BuilderJobProgressService>(),
    )

    @Test
    fun `A B C compile into distinct executable graph shapes`() {
        val a = compile("FAQ를 검색해 고객 답변 초안을 만들어줘.")
        val b = compile("CSV 파일 두 개를 정확하게 비교해 변경된 행을 알려줘.")
        val c = compile("매일 오전 8시에 AI 뉴스 세 개를 요약해 Slack으로 보내되 전송 전에 승인받아줘.")

        val shapes = listOf(a, b, c).map { bundle ->
            bundle.proposal.graphPlan!!.nodes.map { it.nodeType }
        }
        assertThat(shapes.distinct()).hasSize(3)
        assertThat(shapes[0]).contains("knowledge.search.mock", "ai.generate")
        assertThat(shapes[1]).contains("data.csv.compare")
        assertThat(shapes[1]).doesNotContain("ai.generate", "ai.classify")
        assertThat(shapes[2]).contains("schedule.trigger", "human.approval", "slack.send.mock")
    }

    @Test
    fun `every generated edge carries explicit input output bindings`() {
        val bundles = listOf(
            compile("FAQ를 검색해 고객 답변 초안을 만들어줘."),
            compile("CSV 파일 두 개를 정확하게 비교해 변경된 행을 알려줘."),
            compile("매일 오전 8시에 AI 뉴스 세 개를 요약해 Slack으로 보내되 전송 전에 승인받아줘."),
        )

        bundles.flatMap { it.proposal.graphPlan!!.edges }
            .forEach { edge -> assertThat(edge.bindings).describedAs(edge.id).isNotEmpty }
    }

    private fun compile(instruction: String) = pipeline.generateDesign(
        PipelineContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(),
        ),
        instruction,
    )
}
