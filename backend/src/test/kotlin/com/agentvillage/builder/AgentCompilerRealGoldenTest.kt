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
        val inputs = linkedMapOf("A" to "FAQ를 검색해 고객 답변 초안을 만들어줘.", "B" to "CSV 파일 두 개를 정확하게 비교해 변경된 행을 알려줘.", "C" to "매일 오전 8시에 AI 뉴스 세 개를 요약해 Slack으로 보내되 전송 전에 승인받아줘.")
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
        assertThat(graphs.distinct()).hasSize(3)
        assertThat(graphs[0]).contains("knowledge.search.mock", "ai.generate")
        assertThat(graphs[1]).contains("data.csv.compare").doesNotContain("ai.generate")
        assertThat(graphs[2]).contains("schedule.trigger", "human.approval", "slack.send.mock")
        val target = Path.of("build/reports/agent-compiler-real-golden.json"); Files.createDirectories(target.parent); mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), results)
    }
    private fun context() = PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    private fun findCodex() = listOf(Path.of(System.getProperty("user.home"), ".local", "bin", "codex"), Path.of("/usr/local/bin/codex"), Path.of("/opt/homebrew/bin/codex")).firstOrNull(Files::isExecutable)?.toString()
}
