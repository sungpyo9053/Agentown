package com.agentvillage.builder

import com.agentvillage.builder.application.*
import com.agentvillage.builder.domain.*
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
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@EnabledIfEnvironmentVariable(named = "REAL_OUTPUT_QUALITY_PACKAGE", matches = "true")
class OutputQualityPackageRealTest {
    @Test
    fun `generate reusable proposal packages for output quality evaluation`() {
        val cases = mapOf(
            "app" to "앱을 기획하려 한다. 대학생의 실제 문제를 인터뷰에서 정의하고 요구사항 분석, 구체적인 화면·사용 흐름 제안, 실현 가능성 검토, 독립 검토를 거쳐 텍스트 기획서를 만든다. 매번 다른 인터뷰를 입력하므로 초기 예시의 문제나 제외 조건을 모든 실행에 고정하지 않는다. 입력 JSON은 필수 문자열 interviewMemo 하나다. 실제 구현·외부 접속·구매는 하지 않는다.",
            "shoe" to "신발을 기획하려 한다. 사용자 불편과 제약을 입력받아 구체적인 제품 후보안과 소재·구조 선택의 장단점을 제안하고 독립 검토 후 제조업체 상담용 텍스트 기획서와 확인 질문을 만든다. 각 실행에서 다른 사용자 조건을 받는다. 시험하지 않은 성능·인증·의료 효능·양산 가능성은 보장하지 않는다. 입력 JSON은 필수 문자열 userMessage 하나다. 실제 제작·외부 검색·구매는 하지 않는다.",
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            executor.invokeAll(cases.map { (id, objective) -> Callable {
                val mapper = jacksonObjectMapper()
                val command = requireNotNull(listOf(Path.of(System.getProperty("user.home"), ".local/bin/codex"), Path.of("/usr/local/bin/codex"), Path.of("/opt/homebrew/bin/codex")).firstOrNull(Files::isExecutable))
                val runner = CodexCliRunner(command.toString(), 240, Path.of(System.getProperty("user.home"), ".codex").toString())
                val runs = mock<MetaAgentRunRepository>().also { whenever(it.save(any())).thenAnswer { call -> call.arguments[0] } }
                val pipeline = StructuredMetaAgentPipeline(CodexCliMetaAgentModel(mock(), runner, mapper, "gpt-5.6-luna"), mapper, MetaAgentAuditService(runs), mock())
                val request = "$objective 다운로드하여 반복 실행하는 팀 패키지로 제공하고, 역할 수 자체를 늘리지 말고 각 역할의 기여가 최종안에 반영되게 해줘."
                val context = PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
                val catalog = WorkflowNodeCatalog()
                val translator = WorkflowGraphTranslator(catalog)
                val validator = WorkflowGraphValidator(catalog, mapper)
                val replayRoot = System.getenv("OUTPUT_QUALITY_REPLAY_ROOT")
                var bundle = if (replayRoot != null) mapper.readValue(
                    Path.of(replayRoot, id, "design-bundle.json").toFile(), MetaAgentDesignBundle::class.java,
                ) else pipeline.generateDesign(
                    context,
                    agentDevelopmentPrompt(request), StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
                    userInstruction = request,
                )
                fun validate() = validator.validate(
                    translator.translate(context.workflowId, bundle.proposal), bundle.requirement,
                    bundle.proposal, bundle.agentDefinitions, request,
                )
                var validation = validate()
                repeat(2) {
                    if (!validation.valid) {
                        bundle = pipeline.generateDesign(
                            context, agentDevelopmentPrompt(request), StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
                            validationFeedback = validation.issues, previousBundle = bundle, userInstruction = request,
                        )
                        validation = validate()
                    }
                }
                assertThat(validation.issues).describedAs("Generated package must pass the production graph gate").isEmpty()
                assertThat(validation.valid).isTrue()
                assertThat(bundle.clarificationQuestions).isEmpty()
                val files = HarnessPackageRenderer(mapper).render(bundle)
                val directory = Path.of(System.getenv("OUTPUT_QUALITY_PACKAGE_ROOT") ?: "build/reports/output-quality-packages-validated", id)
                files.forEach { (relative, content) ->
                    val target = directory.resolve(relative)
                    Files.createDirectories(target.parent)
                    Files.writeString(target, content)
                }
                mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("design-bundle.json").toFile(), bundle)
                assertThat(mapper.readTree(files.getValue("runtime-status.json"))["runtimeConfigured"].asBoolean()).isTrue()
                // Execution and semantic review are performed by quality_comparison.py;
                // schema-valid generation alone is deliberately not a quality pass.
            }}).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
    }
}
