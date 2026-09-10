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

/** Synthetic reproduction of an invented knowledge connector, not a production browser E2E. */
@EnabledIfEnvironmentVariable(named = "REAL_LOCAL_EVIDENCE_DESIGN", matches = "true")
class RealLocalEvidenceDesignTest {
    @Test
    fun `intake clarifies unavailable document creation before promising a runnable team`() {
        val mapper = jacksonObjectMapper()
        val runner = CodexCliRunner(System.getenv("REAL_CODEX_COMMAND") ?: "codex", 180,
            System.getenv("REAL_CODEX_HOME") ?: Path.of(System.getProperty("user.home"), ".codex").toString())
        require(runner.hasSharedAuth())
        val runs = mock<MetaAgentRunRepository>().also { whenever(it.save(any())).thenAnswer { call -> call.arguments[0] } }
        val pipeline = StructuredMetaAgentPipeline(CodexCliMetaAgentModel(mock<CredentialDirectory>(), runner, mapper,
            System.getenv("REAL_CODEX_MODEL") ?: "gpt-5.6-luna"), mapper, MetaAgentAuditService(runs), mock())
        val context = PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        val result = pipeline.defineAgentDevelopmentProblem(context, "주제만 넣으면 보고서 ppt 만들어줘.", 10)
        val root = Path.of("build/reports/real-local-evidence-design")
        Files.createDirectories(root)
        mapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("ppt-intake.json").toFile(), result)
        assertThat(result.recommendedApproach).isEqualTo(AgentDevelopmentApproach.CLARIFY)
        assertThat(result.readyForDesign).isFalse()
        val questions = result.clarificationQuestions.joinToString("\n") { it.question }
        assertThat(questions).containsPattern("지원하지|지원되지|불가|할 수 없|연결되어 있지|미지원|지원하지는")
        assertThat(questions).containsPattern("파일|PPT|웹")
        assertThat(result.clarificationQuestions.size).isBetween(1, 3)
    }

    @Test
    fun `local source analysis generates and repairs without inventing a knowledge connector`() {
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
        val instruction = "사용자가 대화에 제공한 고객 의견 원문 중심으로 문제와 개선안을 분석하는 팀을 만들어줘. " +
            "분석 담당과 독립 검수 담당을 분리하고 원문 근거와 미확인 사항을 포함한 최종 보고서를 대화 화면에 반환한다. " +
            "입력은 feedbackText 문자열 하나다. 외부 검색이나 서비스 연결은 하지 않는다."
        val root = Path.of("build/reports/real-local-evidence-design")
        Files.createDirectories(root)
        fun validate(bundle: MetaAgentDesignBundle) = validator.validate(translator.translate(context.workflowId, bundle.proposal),
            bundle.requirement, bundle.proposal, bundle.agentDefinitions, instruction)
        fun save(name: String, bundle: MetaAgentDesignBundle) = mapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve(name).toFile(), bundle)
        var bundle = pipeline.generateDesign(context, instruction, StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT, userInstruction = instruction)
        save("initial.json", bundle)
        var validation = validate(bundle)
        repeat(2) {
            if (!validation.valid) {
                bundle = pipeline.generateDesign(context, instruction, StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
                    validation.issues, bundle, instruction)
                validation = validate(bundle)
            }
        }
        save("generated.json", bundle)
        assertThat(validation.issues).isEmpty()
        assertThat(bundle.proposal.graphPlan!!.nodes.map { it.nodeType }).noneMatch { it.contains(".mock") || it.startsWith("notion.") }

        // Inject the reported defect into an otherwise valid graph, retaining its original data bindings.
        val plan = bundle.proposal.graphPlan!!
        val edge = plan.edges.first { it.source == plan.entryNodeId }
        val broken = bundle.copy(proposal = bundle.proposal.copy(graphPlan = plan.copy(
            nodes = plan.nodes + WorkflowNodePlan("invented-knowledge", "knowledge.search.mock", "자료 검색", mapOf("source" to "제공된 자료")),
            edges = plan.edges.map { if (it.id == edge.id) it.copy(source = "invented-knowledge") else it } +
                WorkflowEdgePlan("invented-in", edge.source, "invented-knowledge", bindings = edge.bindings.map { WorkflowFieldBinding(it.sourceField, it.sourceField) }),
        )))
        val issues = validate(broken).issues
        assertThat(issues.map { it.message }).anyMatch { it.contains("queryField") }
        assertThat(issues.map { it.code }).contains("MEANING_UNREQUESTED_INTEGRATION")
        save("injected-defect.json", broken)
        val repaired = pipeline.generateDesign(context, instruction, StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
            issues, broken, instruction)
        save("repaired.json", repaired)
        assertThat(validate(repaired).issues).isEmpty()
        assertThat(repaired.proposal.graphPlan!!.nodes.map { it.nodeType }).noneMatch { it.contains(".mock") || it.startsWith("notion.") }
        HarnessPackageRenderer(mapper).render(repaired).forEach { (name, content) ->
            val target = root.resolve("package").resolve(name).normalize()
            require(target.startsWith(root.resolve("package")))
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }
    }
}
