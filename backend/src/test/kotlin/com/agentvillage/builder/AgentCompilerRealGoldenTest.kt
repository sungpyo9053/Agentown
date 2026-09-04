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

    @Test
    fun `real Codex produces distinct validated packages for A B and C`() {
        val command = System.getenv("REAL_CODEX_COMMAND") ?: requireNotNull(findCodex()) { "codex command not found" }
        val codexHome = System.getenv("REAL_CODEX_HOME") ?: Path.of(System.getProperty("user.home"), ".codex").toString()
        val runner = CodexCliRunner(command, 180, codexHome)
        require(runner.hasSharedAuth()) { "shared Codex auth is missing: $codexHome/auth.json" }
        val credentials = mock<CredentialDirectory>()
        val limiter = mock<BuilderUsageLimiter>()
        whenever(limiter.isUnlimited(any())).thenReturn(true)
        val model = CodexCliMetaAgentModel(credentials, runner, limiter, mapper, System.getenv("REAL_CODEX_MODEL") ?: "gpt-5.6-luna")
        val runs = mock<MetaAgentRunRepository>()
        whenever(runs.save(any())).thenAnswer { it.arguments[0] }
        val pipeline = StructuredMetaAgentPipeline(model, mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>())
        val catalog = WorkflowNodeCatalog()
        val validator = WorkflowGraphValidator(catalog, mapper)
        val renderer = HarnessPackageRenderer(mapper)
        val inputs = linkedMapOf(
            "A" to "FAQ를 검색해 고객 답변 초안을 만들어줘.",
            "B" to "CSV 파일 두 개를 정확하게 비교해 변경된 행을 알려줘.",
            "C" to "매일 오전 8시에 AI 뉴스 세 개를 요약해 Slack으로 보내되 전송 전에 승인받아줘.",
        )
        val results = inputs.mapValues { (id, instruction) ->
            val bundle = pipeline.generateDesign(context(), instruction)
            assertThat(bundle.clarificationQuestions).describedAs("$id clarification").isEmpty()
            val graph = graph(bundle)
            val validation = validator.validate(graph, bundle.requirement, bundle.proposal, bundle.agentDefinitions, instruction)
            assertThat(validation.valid).describedAs("$id validation: ${validation.issues}").isTrue()
            val files = renderer.render(bundle)
            validatePackage(files)
            mapOf("instruction" to instruction, "bundle" to bundle, "graph" to graph, "packageFiles" to files.keys.sorted())
        }
        val aTypes = (results.getValue("A").getValue("graph") as WorkflowGraph).nodes.map(WorkflowNode::nodeType)
        val bGraph = results.getValue("B").getValue("graph") as WorkflowGraph
        val cTypes = (results.getValue("C").getValue("graph") as WorkflowGraph).nodes.map(WorkflowNode::nodeType)
        assertThat(listOf(aTypes, bGraph.nodes.map(WorkflowNode::nodeType), cTypes).distinct()).hasSize(3)
        assertThat(aTypes).contains(NodeType.KNOWLEDGE_SEARCH_MOCK.wireName, NodeType.AI_GENERATE.wireName)
        assertThat(bGraph.nodes.map(WorkflowNode::nodeType)).contains(NodeType.DATA_CSV_COMPARE.wireName).doesNotContain(NodeType.AI_GENERATE.wireName)
        assertThat((results.getValue("B").getValue("bundle") as MetaAgentDesignBundle).agentDefinitions).isEmpty()
        assertThat(cTypes).contains(NodeType.SCHEDULE_TRIGGER.wireName, NodeType.HUMAN_APPROVAL.wireName, NodeType.SLACK_SEND_MOCK.wireName)
        val target = Path.of("build/reports/agent-compiler-real-golden.json")
        Files.createDirectories(target.parent)
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), results)
    }

    private fun graph(bundle: MetaAgentDesignBundle): WorkflowGraph {
        val plan = requireNotNull(bundle.proposal.graphPlan)
        return WorkflowGraph(
            workflowId = UUID.randomUUID(), entryNodeId = plan.entryNodeId,
            nodes = plan.nodes.mapIndexed { index, node -> WorkflowNode(node.id, node.nodeType, node.label, NodePosition(index * 260.0, 100.0), node.config) },
            edges = plan.edges.map { edge -> WorkflowEdge(edge.id, edge.source, edge.target, edge.condition, edge.bindings.associate { it.targetField to it.sourceField }) },
        )
    }

    private fun validatePackage(files: Map<String, String>) {
        assertThat(files.keys).contains(
            "agent.yaml", "workflow.yaml", "schemas/input.schema.json", "schemas/output.schema.json",
            "tools/tools.yaml", "mcp.json", "examples/sample-input.json", ".env.example", "README.md",
        )
        files.filterKeys { it.endsWith(".json") }.forEach { (name, content) ->
            runCatching { mapper.readTree(content) }.getOrElse { throw AssertionError("invalid package JSON $name", it) }
        }
    }

    private fun context() = PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    private fun findCodex() = listOf(
        Path.of(System.getProperty("user.home"), ".local", "bin", "codex"), Path.of("/usr/local/bin/codex"), Path.of("/opt/homebrew/bin/codex"),
    ).firstOrNull(Files::isExecutable)?.toString()
}
