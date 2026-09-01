package com.agentvillage.builder

import com.agentvillage.builder.application.CodexCliMetaAgentModel
import com.agentvillage.builder.application.CodexCliRunner
import com.agentvillage.builder.application.PipelineContext
import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.llmcredential.application.CredentialDirectory
import com.agentvillage.llmcredential.domain.LlmProvider
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

class CodexCliMetaAgentModelTest {
    private val credentials = mock<CredentialDirectory>()
    private val runner = mock<CodexCliRunner>()
    private val model = CodexCliMetaAgentModel(credentials, runner, ObjectMapper(), "gpt-test")
    private val context = PipelineContext(
        traceId = UUID.randomUUID(),
        ownerId = UUID.randomUUID(),
        workspaceId = UUID.randomUUID(),
        conversationId = UUID.randomUUID(),
        workflowId = UUID.randomUUID(),
        jobId = UUID.randomUUID(),
    )

    @Test
    fun `platform shared Codex is the default even without a personal credential`() {
        whenever(runner.hasSharedAuth()).thenReturn(true)
        whenever(credentials.findLatestActive(context.ownerId, LlmProvider.OPENAI)).thenReturn(null)
        whenever(runner.executeWithSharedAuth(eq("gpt-test"), any(), eq(context.jobId), any())).thenReturn("{}")

        assertDoesNotThrow { model.preflight(context) }
        assertThat(model.generate(context, "builder_design_bundle", mapOf("instruction" to "글쓰기 자동화"))).isEqualTo("{}")

        verify(runner).executeWithSharedAuth(eq("gpt-test"), any(), eq(context.jobId), eq("/builder/meta-agent-design-bundle.schema.json"))
    }

    @Test
    fun `missing platform and personal AI reports configuration failure`() {
        whenever(runner.hasSharedAuth()).thenReturn(false)
        whenever(credentials.findLatestActive(context.ownerId, LlmProvider.OPENAI)).thenReturn(null)

        val error = assertThrows<BadRequestException> { model.preflight(context) }

        assertThat(error.code).isEqualTo("BUILDER_AI_NOT_CONFIGURED")
    }
}
