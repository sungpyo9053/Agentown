package com.agentvillage.builder

import com.agentvillage.builder.application.*
import com.agentvillage.builder.domain.*
import com.agentvillage.builder.infrastructure.MetaAgentRunRepository
import com.agentvillage.llmcredential.application.CredentialDirectory
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

/** Actual model generation and package export. Local execution is a separate acceptance gate. */
@EnabledIfEnvironmentVariable(named = "REAL_LOCAL_ARTIFACT_DESIGN", matches = "true")
class RealLocalArtifactDesignTest {
    @Test
    fun `provided evidence creates a locally executable file-producing team`() {
        val mapper = jacksonObjectMapper()
        val runner = CodexCliRunner(System.getenv("REAL_CODEX_COMMAND") ?: "codex", 180,
            System.getenv("REAL_CODEX_HOME") ?: Path.of(System.getProperty("user.home"), ".codex").toString())
        require(runner.hasSharedAuth())
        val runs = mock<MetaAgentRunRepository>().also { whenever(it.save(any())).thenAnswer { call -> call.arguments[0] } }
        val pipeline = StructuredMetaAgentPipeline(CodexCliMetaAgentModel(mock<CredentialDirectory>(), runner, mapper,
            System.getenv("REAL_CODEX_MODEL") ?: "gpt-5.6-luna"), mapper, MetaAgentAuditService(runs), mock())
        val catalog = WorkflowNodeCatalog()
        val validator = WorkflowGraphValidator(catalog, mapper)
        val translator = WorkflowGraphTranslator(catalog)
        val context = PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        val format = System.getenv("REAL_LOCAL_ARTIFACT_FORMAT") ?: "pptx"
        require(format in setOf("pptx", "xlsx"))
        val deliverable = if (format == "pptx") "실제 PPTX 파일 3장: 확인된 사실, 검토 가능한 개선안, 추가 확인 사항" else
            "실제 XLSX 파일: 원문 ID, 관찰된 사실, 제안, 근거, 확인 필요 사항의 5개 열과 원문별 3개 행"
        val instruction = "사용자가 제공한 고객 의견을 분석하고 독립 검수한 뒤 $deliverable 을 만드는 반복용 팀을 다운로드해 로컬에서 실행하고 싶다. " +
            "대상은 제품 의사결정자다. 입력은 sourceText 문자열 하나. 웹 조사, 외부 전송, Notion/FAQ 연동은 하지 않는다. " +
            "근거 없는 빈도·인과·수치를 만들지 않고 제안과 사실을 구분한다. 실제 파일 제작 노드는 local.artifact.render(format=$format)을 사용한다."
        val started = System.nanoTime()
        var bundle = pipeline.generateDesign(context, instruction, StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT, userInstruction = instruction)
        val initialMillis = (System.nanoTime() - started) / 1_000_000
        fun validate() = validator.validate(translator.translate(context.workflowId, bundle.proposal), bundle.requirement,
            bundle.proposal, bundle.agentDefinitions, instruction)
        var validation = validate()
        val root = Path.of(System.getenv("REAL_LOCAL_ARTIFACT_REPORT") ?: "build/reports/real-local-artifact-design/$format")
        Files.createDirectories(root)
        mapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("initial.json").toFile(), bundle)
        val initialValid = validation.valid
        if (!initialValid) {
            bundle = pipeline.generateDesign(context, instruction, StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
                validation.issues, bundle, instruction)
            validation = validate()
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("validation.json").toFile(), validation)
        mapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("timing.json").toFile(), mapOf(
            "initialMillis" to initialMillis, "totalMillis" to (System.nanoTime() - started) / 1_000_000,
            "initialValid" to initialValid, "finalValid" to validation.valid,
        ))
        mapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("design-bundle.json").toFile(), bundle)
        assertThat(validation.issues).isEmpty()
        assertThat(bundle.clarificationQuestions).isEmpty()
        assertThat(bundle.proposal.graphPlan!!.nodes).anyMatch { it.nodeType == "local.artifact.render" && it.config["format"] == format }
        HarnessPackageRenderer(mapper).render(bundle).forEach { (name, content) ->
            val target = root.resolve("package").resolve(name).normalize()
            require(target.startsWith(root.resolve("package")))
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("package/examples/sample-input.json").toFile(),
            mapOf("sourceText" to "F1: 구매 영수증을 잃어버려 반품 접수가 어려웠다. F2: 날짜별 구매 내역 필터가 없어 찾기 어렵다. F3: 화면 색상이 마음에 든다. 세 사람 각각의 단일 의견이며 빈도·비율·매출 효과는 알 수 없다."))
    }
}
