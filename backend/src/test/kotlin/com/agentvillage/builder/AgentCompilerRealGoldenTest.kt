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

@EnabledIfEnvironmentVariable(named = "REAL_AGENT_COMPILER_GOLDEN", matches = "true")
class AgentCompilerRealGoldenTest {
    private val mapper = jacksonObjectMapper()
    @Test fun `real Codex produces distinct validated packages`() {
        val command = System.getenv("REAL_CODEX_COMMAND") ?: requireNotNull(findCodex())
        val home = System.getenv("REAL_CODEX_HOME") ?: Path.of(System.getProperty("user.home"), ".codex").toString()
        val runner = CodexCliRunner(command, 180, home)
        require(runner.hasSharedAuth())
        val model = CodexCliMetaAgentModel(mock<CredentialDirectory>(), runner, mapper, System.getenv("REAL_CODEX_MODEL") ?: "gpt-5.6-luna")
        val runs = mock<MetaAgentRunRepository>().also { whenever(it.save(any())).thenAnswer { call -> call.arguments[0] } }
        val pipeline = StructuredMetaAgentPipeline(model, mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>())
        val validator = WorkflowGraphValidator(WorkflowNodeCatalog(), mapper)
        val renderer = HarnessPackageRenderer(mapper)
        val inputs = linkedMapOf(
            "A" to "고객 문의가 들어오면 FAQ를 검색해서 답변 초안을 만들고, 근거가 없으면 담당자 확인이 필요하다고 표시하는 에이전트를 만들어줘.",
            "B" to "두 개의 CSV 파일을 비교해서 추가·삭제·수정된 행을 찾고 결과를 표로 만들어주는 에이전트를 만들어줘.",
            "C" to "매일 오전 8시에 AI 뉴스를 검색해서 중요한 뉴스 3개를 한국어로 요약하고, 내가 승인하면 Slack으로 보내줘.",
            "D" to "경쟁사 세 곳의 최근 제품 발표와 가격 변화를 각각 조사하고, 조사가 끝나면 하나의 비교 보고서로 합쳐줘.",
            "E" to "GitHub 이슈가 등록되면 버그, 기능 요청, 질문으로 분류하고 버그인 경우에만 재현 절차 초안을 작성해줘.",
            "F" to "사내 인사 시스템 PeopleMagic에서 휴가 잔여일을 조회해 알려주는 에이전트를 만들어줘.",
            "G" to "항공권이 20만 원 이하가 될 때까지 1초마다 계속 검색하고 발견하면 바로 결제해줘.",
        )
        val results = inputs.mapValues { (id, text) ->
            val bundle = pipeline.generateDesign(context(), text)
            assertThat(bundle.clarificationQuestions).describedAs(id).isEmpty()
            val plan = requireNotNull(bundle.proposal.graphPlan)
            val graph = WorkflowGraph(workflowId = UUID.randomUUID(), entryNodeId = plan.entryNodeId, nodes = plan.nodes.mapIndexed { i, n -> WorkflowNode(n.id, n.nodeType, n.label, NodePosition(i * 260.0, 100.0), n.config) }, edges = plan.edges.map { e -> WorkflowEdge(e.id, e.source, e.target, e.condition, e.bindings.associate { it.targetField to it.sourceField }) })
            val validation = validator.validate(graph, bundle.requirement, bundle.proposal, bundle.agentDefinitions, text)
            assertThat(validation.valid).describedAs("$id ${validation.issues}").isTrue()
            val files = renderer.render(bundle)
            assertThat(files.keys).contains("agent.yaml", "workflow.yaml", "tools/tools.yaml", "README.md")
            mapOf("instruction" to text, "bundle" to bundle, "graph" to graph, "packageFiles" to files.keys.sorted())
        }
        val graphs = results.values.map { (it.getValue("graph") as WorkflowGraph).nodes.map(WorkflowNode::nodeType) }
        assertThat(graphs.distinct()).hasSize(7)
        assertThat(graphs[0]).contains("knowledge.search.mock", "condition.branch", "ai.generate")
        assertThat(graphs[1]).contains("data.csv.compare").doesNotContain("ai.generate")
        assertThat(graphs[2]).contains("schedule.trigger", "human.approval", "slack.send.mock")
        assertThat(graphs[3]).contains("parallel.map.mock", "ai.generate").doesNotContain("human.approval")
        assertThat(graphs[4]).contains("github.issue.mock", "ai.classify", "condition.branch", "ai.generate")
        assertThat(graphs[5]).contains("tool.unresolved").doesNotContain("knowledge.search.mock", "notion.search.mock")
        assertThat(graphs[6]).contains("schedule.trigger", "condition.branch", "human.approval", "tool.unresolved").doesNotContain("notion.search.mock")
        val target = Path.of("build/reports/agent-compiler-real-golden.json"); Files.createDirectories(target.parent); mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), results)
    }
    private fun context() = PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    private fun findCodex() = listOf(Path.of(System.getProperty("user.home"), ".local", "bin", "codex"), Path.of("/usr/local/bin/codex"), Path.of("/opt/homebrew/bin/codex")).firstOrNull(Files::isExecutable)?.toString()
}
