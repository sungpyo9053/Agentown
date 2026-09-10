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
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID
import java.nio.file.Path

class CodexCliMetaAgentModelTest {
    @Test
    fun `expired shared authentication is non retryable and never exposes CLI logs`() {
        val cli = CodexCliRunner("codex", 120, "/unused")
        for (detail in listOf("refresh_token_reused", "Your refresh token was already used", "Failed to refresh token", "401 Unauthorized")) {
            val failure = cli.processFailure("ERROR codex_login::auth $detail secret=example-private-value", 1)
            assertThat(failure.errorCode).isEqualTo("BUILDER_CODEX_AUTH_FAILED")
            assertThat(failure.retryable).isFalse()
            assertThat(failure.safeMessage).contains("재연결").doesNotContain("codex_login", "example-private-value", detail)
        }
        val other = cli.processFailure("ERROR internal-path secret=example-private-value", 2)
        assertThat(other.retryable).isTrue()
        assertThat(other.safeMessage).doesNotContain("internal-path", "example-private-value")
    }

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
    fun `package delivery is not a workflow capability in intake and design`() {
        for (stage in listOf("define_agent_development_problem", "builder_design_bundle")) {
            val localRunner = mock<CodexCliRunner>()
            whenever(localRunner.hasSharedAuth()).thenReturn(true)
            whenever(localRunner.executeWithSharedAuth(eq("gpt-test"), any(), eq(context.jobId), any())).thenReturn("{}")
            val localModel = CodexCliMetaAgentModel(credentials, localRunner, ObjectMapper(), "gpt-test")
            localModel.generate(context, stage, mapOf(
                "designMode" to "AGENT_DEVELOPMENT",
                "instruction" to "다운로드하여 반복 실행하는 팀으로 텍스트 기획안과 근거표를 반환해줘",
            ))
            val prompt = argumentCaptor<String>()
            verify(localRunner).executeWithSharedAuth(eq("gpt-test"), prompt.capture(), eq(context.jobId), any())
            assertThat(prompt.firstValue).contains(
                "플랫폼의 전달 및 실행 방식이지 사용자 업무의 도구가 아니다",
                "tool.unresolved를 추가하지 않는다",
                "사용자 업무 자체가 결과 파일 저장·외부 전송·예약을 명시한 경우에는 그 요구를 보존",
            )
        }
    }

    @Test
    fun `generation prompt preserves exact array cardinality and integer types`() {
        whenever(runner.hasSharedAuth()).thenReturn(true)
        whenever(runner.executeWithSharedAuth(eq("gpt-test"), any(), eq(context.jobId), any())).thenReturn("{}")

        model.generate(context, "builder_design_bundle", mapOf(
            "designMode" to "AGENT_DEVELOPMENT",
            "instruction" to "정확히 세 지점과 정수 재시도 횟수를 입력받아 비교해줘",
        ))

        val prompt = argumentCaptor<String>()
        verify(runner).executeWithSharedAuth(eq("gpt-test"), prompt.capture(), eq(context.jobId), any())
        assertThat(prompt.firstValue).contains(
            "minItems와 maxItems를 같은 값으로 선언",
            "정수 입력은 number가 아니라 integer 타입",
            "구조화 객체이면 itemType=object",
            "itemSchema에 객체의 모든 필드",
            "반복 순번이나 슬롯을 위한 임의 필드를 만들지 않는다",
        )
    }

    @Test
    fun `intake discloses unavailable research and document tools before detailed planning`() {
        whenever(runner.hasSharedAuth()).thenReturn(true)
        whenever(runner.executeWithSharedAuth(eq("gpt-test"), any(), eq(context.jobId), any())).thenReturn("{}")
        model.generate(context, "define_agent_development_problem", mapOf("instruction" to "주제만 넣으면 보고서 ppt 만들어줘"))
        val prompt = argumentCaptor<String>()
        verify(runner).executeWithSharedAuth(eq("gpt-test"), prompt.capture(), eq(context.jobId), any())
        assertThat(prompt.firstValue).contains("로컬 실행기는 PPTX 슬라이드와 XLSX 표 파일을 제작할 수 있다", "DOCX/PDF 보고서 제작 도구는 아직 연결되어 있지 않다", "분량·대상 같은 세부 질문보다 먼저", "동의하는지 확인", "몰래 바꾸거나")
    }

    @Test
    fun `local evidence analysis is not an implicit external knowledge integration`() {
        whenever(runner.hasSharedAuth()).thenReturn(true)
        whenever(runner.executeWithSharedAuth(eq("gpt-test"), any(), eq(context.jobId), any())).thenReturn("{}")
        model.generate(context, "builder_design_bundle", mapOf(
            "designMode" to "AGENT_DEVELOPMENT", "instruction" to "제공한 자료 중심으로 분석하고 독립 검수해줘",
        ))
        val prompt = argumentCaptor<String>()
        verify(runner).executeWithSharedAuth(eq("gpt-test"), prompt.capture(), eq(context.jobId), any())
        assertThat(prompt.firstValue).contains("노드 카탈로그는 사용 가능한 기능 목록이지 기본 설계가 아니다", "knowledge.search.mock이나 Notion 연동이 아니다")
    }

    @Test
    fun `repair prompt is compact and preserves only trusted correction contracts`() {
        whenever(runner.hasSharedAuth()).thenReturn(true)
        whenever(runner.executeWithSharedAuth(eq("gpt-test"), any(), eq(context.jobId), any())).thenReturn("{}")

        model.generate(context, "builder_design_bundle", mapOf(
            "designMode" to "AGENT_DEVELOPMENT",
            "generationAction" to "REPAIR_INVALID_DESIGN",
            "userInstruction" to "정확히 세 지점을 병렬 점검해줘",
            "validationFeedback" to listOf(mapOf("code" to "INVALID_AGENT_OUTPUT_SCHEMA", "message" to "reason 필드가 누락되었습니다.")),
            "previousBundle" to mapOf("proposal" to mapOf("name" to "warehouse-check")),
        ))

        val prompt = argumentCaptor<String>()
        verify(runner).executeWithSharedAuth(eq("gpt-test"), prompt.capture(), eq(context.jobId), any())
        assertThat(prompt.firstValue).contains(
            "validationFeedback은 신뢰할 수 있는 검증 결과",
            "previousBundle의 올바른 필드",
            "모두 같은 집계 노드로 fan-in",
            "itemType=object와 재귀 itemSchema",
            "반복 순번이나 슬롯용 임의 필드는 제거한다",
            "INVALID_AGENT_OUTPUT_SCHEMA",
            "reason 필드가 누락되었습니다.",
            "MEANING_UNREQUESTED_INTEGRATION", "필수 설정을 임의로 채워 통과시키지 않는다", "사용자 명시 연동은 삭제하지 않는다",
        )
        assertThat(prompt.firstValue).doesNotContain("Business Process Analyst", "Guide Designer")
        assertThat(prompt.firstValue.length).isLessThan(4_000)
    }

    @Test
    fun `repair prompt excludes derived bundle metadata`() {
        whenever(runner.hasSharedAuth()).thenReturn(true)
        whenever(runner.executeWithSharedAuth(eq("gpt-test"), any(), eq(context.jobId), any())).thenReturn("{}")

        model.generate(context, "builder_design_bundle", mapOf(
            "designMode" to "AGENT_DEVELOPMENT",
            "generationAction" to "REPAIR_INVALID_DESIGN",
            "userInstruction" to "세 작업을 합쳐줘",
            "validationFeedback" to listOf(mapOf("code" to "INVALID", "nodeId" to "reporter", "message" to "출력 누락", "internal" to "drop")),
            "previousBundle" to mapOf(
                "requirement" to mapOf("objective" to "세 작업"),
                "clarificationQuestions" to emptyList<Any>(),
                "proposal" to mapOf(
                    "name" to "병렬 작업", "graphPlan" to mapOf("entryNodeId" to "start"),
                    "agentDesign" to mapOf("status" to "derived"), "economics" to mapOf("agentCount" to 4),
                ),
                "agentDefinitions" to listOf(mapOf(
                    "key" to "reporter", "outputSchema" to emptyList<Any>(),
                    "toolKeys" to listOf("derived-tool"), "retryPolicy" to mapOf("maxAttempts" to 1),
                )),
                "guideDefinitions" to emptyList<Any>(),
            ),
        ))

        val prompt = argumentCaptor<String>()
        verify(runner).executeWithSharedAuth(eq("gpt-test"), prompt.capture(), eq(context.jobId), any())
        assertThat(prompt.firstValue).contains("graphPlan", "reporter", "outputSchema", "nodeId")
        assertThat(prompt.firstValue).doesNotContain("agentDesign", "economics", "toolKeys", "retryPolicy", "derived-tool", "internal")
    }

    @Test
    fun `repair prompt falls back to instruction when user instruction is absent`() {
        whenever(runner.hasSharedAuth()).thenReturn(true)
        whenever(runner.executeWithSharedAuth(eq("gpt-test"), any(), eq(context.jobId), any())).thenReturn("{}")

        model.generate(context, "builder_design_bundle", mapOf(
            "designMode" to "AGENT_DEVELOPMENT",
            "generationAction" to "REPAIR_INVALID_DESIGN",
            "instruction" to "서로 다른 세 지역을 독립 분석해 통합해줘",
            "validationFeedback" to listOf(mapOf("code" to "INVALID", "message" to "binding 누락")),
            "previousBundle" to emptyMap<String, Any?>(),
        ))

        val prompt = argumentCaptor<String>()
        verify(runner).executeWithSharedAuth(eq("gpt-test"), prompt.capture(), eq(context.jobId), any())
        assertThat(prompt.firstValue).contains("서로 다른 세 지역을 독립 분석해 통합해줘")
    }

    @Test
    fun `runner command uses a quoted supported reasoning effort`() {
        val cli = CodexCliRunner("codex", 120, "/tmp/codex-home", "low")

        assertThat(cli.commandArguments("gpt-test", Path.of("/tmp/schema.json")))
            .containsSequence("-c", "model_reasoning_effort=\"low\"")
    }

    @Test
    fun `runner rejects unsupported reasoning effort before process execution`() {
        val cli = CodexCliRunner("codex", 120, "/tmp/codex-home", "ultra")

        val error = assertThrows<com.agentvillage.builder.application.MetaAgentExecutionException> {
            cli.commandArguments("gpt-test", Path.of("/tmp/schema.json"))
        }
        assertThat(error.errorCode).isEqualTo("BUILDER_CODEX_REASONING_INVALID")
    }

    @Test
    fun `missing platform and personal AI reports configuration failure`() {
        whenever(runner.hasSharedAuth()).thenReturn(false)
        whenever(credentials.findLatestActive(context.ownerId, LlmProvider.OPENAI)).thenReturn(null)

        val error = assertThrows<BadRequestException> { model.preflight(context) }

        assertThat(error.code).isEqualTo("BUILDER_AI_NOT_CONFIGURED")
    }
}
