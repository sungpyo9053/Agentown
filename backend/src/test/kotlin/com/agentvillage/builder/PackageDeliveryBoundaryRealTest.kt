package com.agentvillage.builder

import com.agentvillage.builder.application.*
import com.agentvillage.builder.infrastructure.MetaAgentRunRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@EnabledIfEnvironmentVariable(named = "REAL_PACKAGE_DELIVERY_BOUNDARY", matches = "true")
class PackageDeliveryBoundaryRealTest {
    @Test
    fun `downloadable text team does not invent local file tools`() {
        val mapper = jacksonObjectMapper()
        val command = requireNotNull(listOf(Path.of(System.getProperty("user.home"), ".local/bin/codex").toString(), "/opt/homebrew/bin/codex", "/usr/local/bin/codex")
            .firstOrNull { Files.isExecutable(Path.of(it)) })
        val runner = CodexCliRunner(command, 240, Path.of(System.getProperty("user.home"), ".codex").toString())
        val model = CodexCliMetaAgentModel(mock(), runner, mapper, "gpt-5.6-luna")
        val runs = mock<MetaAgentRunRepository>().also { whenever(it.save(any())).thenAnswer { call -> call.arguments[0] } }
        val pipeline = StructuredMetaAgentPipeline(model, mapper, MetaAgentAuditService(runs), mock())
        val request = """
            손목 움직임이 제한된 오른손 사용 성인이 사무실에서 문서 작업에 쓸 컴퓨터 마우스의 텍스트 기획안을 원한다.
            다운로드하여 반복 실행할 에이전트 팀으로 사용자 메모를 받아 요구사항 분석, 산업디자인 제안, 독립 접근성 검토를
            각각 맡고 마지막에 충돌을 조정한 기획안과 근거표를 반환해줘.
            실제 제작·외부 검색·구매는 범위 밖이며 의료적 효능은 보장하지 않고 미확인 사항은 가정과 추가 확인으로 표시한다.
            입력은 사용자 메모 하나이며 결과는 화면에 반환하는 텍스트다.
        """.trimIndent()
        val bundle = pipeline.generateDesign(
            PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
            agentDevelopmentPrompt(request), StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
            userInstruction = request,
        )
        assertThat(bundle.clarificationQuestions).isEmpty()
        val nodes = requireNotNull(bundle.proposal.graphPlan).nodes
        assertThat(nodes.map { it.nodeType }).doesNotContain("tool.unresolved", "schedule.trigger", "human.approval")
        assertThat(bundle.agentDefinitions.size).isGreaterThanOrEqualTo(3)
        val files = HarnessPackageRenderer(mapper).render(bundle, automationValidated = true)
        val directory = Path.of("build/reports/package-delivery-boundary")
        files.forEach { (relative, content) ->
            val target = directory.resolve(relative)
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }
        assertThat(mapper.readTree(files.getValue("runtime-status.json"))["runtimeConfigured"].asBoolean()).isTrue()
    }
}
