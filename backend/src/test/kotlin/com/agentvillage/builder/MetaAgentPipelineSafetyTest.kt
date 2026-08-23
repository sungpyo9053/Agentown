package com.agentvillage.builder

import com.agentvillage.builder.application.MetaAgentModel
import com.agentvillage.builder.application.MetaAgentAuditService
import com.agentvillage.builder.application.BuilderJobProgressService
import com.agentvillage.builder.application.PipelineContext
import com.agentvillage.builder.application.StructuredMetaAgentPipeline
import com.agentvillage.builder.infrastructure.MetaAgentRunRepository
import com.agentvillage.common.exception.BadRequestException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

class MetaAgentPipelineSafetyTest {
    @Test
    fun `invalid model json is rejected before becoming a domain object`() {
        val model = mock<MetaAgentModel>()
        val runs = mock<MetaAgentRunRepository>()
        whenever(model.executorName).thenReturn("invalid-test")
        whenever(model.modelName).thenReturn("mock")
        whenever(model.generate(any(), any(), any())).thenReturn("this is not json")
        whenever(runs.save(any())).thenAnswer { it.arguments[0] }
        val pipeline = StructuredMetaAgentPipeline(model, jacksonObjectMapper(), MetaAgentAuditService(runs), mock<BuilderJobProgressService>())
        val context = PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

        assertThatThrownBy { pipeline.generateDesign(context, "Slack 문의를 Notion FAQ로 처리") }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("승인된 스키마")
    }
}
