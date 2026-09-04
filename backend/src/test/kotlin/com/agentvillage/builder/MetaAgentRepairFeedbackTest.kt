package com.agentvillage.builder

import com.agentvillage.builder.application.*
import com.agentvillage.builder.domain.ValidationIssue
import com.agentvillage.builder.infrastructure.MetaAgentRunRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

class MetaAgentRepairFeedbackTest {
    @Test fun `repair call carries trusted validation feedback and previous persisted design`() {
        val mapper = jacksonObjectMapper()
        val delegate = DeterministicMockMetaAgentModel(mapper)
        var captured: Map<String, Any?> = emptyMap()
        val model = object : MetaAgentModel {
            override val executorName = "capture"
            override val modelName = "capture"
            override fun generate(context: PipelineContext, stage: String, input: Map<String, Any?>): String {
                captured = input
                return delegate.generate(context, stage, input)
            }
        }
        val runs = mock<MetaAgentRunRepository>().also { whenever(it.save(any())).thenAnswer { call -> call.arguments[0] } }
        val pipeline = StructuredMetaAgentPipeline(model, mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>())
        val context = PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        val instruction = "FAQ 근거로 답하고 근거가 없으면 담당자 확인 상태로 종료해줘"
        val original = pipeline.generateDesign(context, instruction, StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT)
        val issue = ValidationIssue("MEANING_SOURCE_MISSING", "FAQ 검색 노드가 누락되었습니다.")

        pipeline.generateDesign(context, instruction, StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT, listOf(issue), original)

        assertThat(captured["generationAction"]).isEqualTo("REPAIR_INVALID_DESIGN")
        assertThat(captured["validationFeedback"]).isEqualTo(listOf(issue))
        assertThat(captured["previousBundle"]).isEqualTo(original)
    }
}
