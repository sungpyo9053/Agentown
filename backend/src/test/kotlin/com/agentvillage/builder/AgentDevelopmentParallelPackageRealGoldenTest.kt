package com.agentvillage.builder

import com.agentvillage.builder.application.*
import com.agentvillage.builder.domain.*
import com.agentvillage.builder.infrastructure.MetaAgentRunRepository
import com.agentvillage.llmcredential.application.CredentialDirectory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@EnabledIfEnvironmentVariable(named = "REAL_AGENT_PARALLEL_PACKAGE_GOLDEN", matches = "true")
class AgentDevelopmentParallelPackageRealGoldenTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `blind multi agent package generates downloads and executes with the pinned runtime`(@TempDir directory: Path) {
        val command = System.getenv("REAL_CODEX_COMMAND") ?: requireNotNull(findCodex())
        val codexHome = System.getenv("REAL_CODEX_HOME") ?: Path.of(System.getProperty("user.home"), ".codex").toString()
        val python = requireNotNull(System.getenv("TFRAMEX_TEST_PYTHON"))
        val runner = CodexCliRunner(command, 240, codexHome)
        require(runner.hasSharedAuth())
        val model = CodexCliMetaAgentModel(
            mock<CredentialDirectory>(), runner, mapper,
            System.getenv("REAL_CODEX_MODEL") ?: "gpt-5.6-luna",
        )
        val runs = mock<MetaAgentRunRepository>().also {
            whenever(it.save(any())).thenAnswer { call -> call.arguments[0] }
        }
        val pipeline = StructuredMetaAgentPipeline(
            model, mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>(),
        )
        val request = """
            정확히 네 개의 연구비 신청 기록을 입력받아 각 기록을 독립된 심사 에이전트가 병렬 검토해줘.
            각 기록에는 applicationId, requestedAmount, documentationComplete, evidenceUrls가 있다.
            documentationComplete=false이거나 requestedAmount가 10000000을 초과하면 REJECTED, 그 외에는 ACCEPTED다.
            네 심사가 모두 성공하고 근거 URL이 있을 때만 총괄 에이전트가 applicationId, decision, requestedAmount,
            evidenceUrls, status, error를 보존한 reviewTable과 요약을 만들어줘. 하나라도 실패하면 총괄을 실행하지 말고 FAILED로 끝내줘.
        """.trimIndent()
        val bundle = pipeline.generateDesign(
            PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
            agentDevelopmentPrompt(request), StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
            userInstruction = request,
        )
        assertThat(bundle.clarificationQuestions).isEmpty()
        val plan = requireNotNull(bundle.proposal.graphPlan)
        val graph = WorkflowGraph(
            workflowId = UUID.randomUUID(), entryNodeId = plan.entryNodeId,
            nodes = plan.nodes.mapIndexed { index, node ->
                WorkflowNode(node.id, node.nodeType, node.label, NodePosition(index * 220.0, 80.0), node.config)
            },
            edges = plan.edges.map { edge ->
                WorkflowEdge(edge.id, edge.source, edge.target, edge.conditionSpec?.serialize() ?: edge.condition,
                    edge.bindings.associate { it.targetField to it.sourceField })
            },
        )
        val validation = WorkflowGraphValidator(WorkflowNodeCatalog(), mapper)
            .validate(graph, bundle.requirement, bundle.proposal, bundle.agentDefinitions, request)
        assertThat(validation.valid).describedAs(validation.issues.toString()).isTrue()
        assertThat(plan.nodes.count { it.nodeType in setOf("ai.generate", "ai.classify") }).isGreaterThanOrEqualTo(5)

        val files = HarnessPackageRenderer(mapper).render(bundle, automationValidated = true)
        files.forEach { (relative, content) ->
            val target = directory.resolve(relative)
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }
        val retainedPackage = Path.of("build/reports/agent-development-parallel-package-latest")
        files.forEach { (relative, content) ->
            val target = retainedPackage.resolve(relative)
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }
        assertThat(files).containsKeys("AGENTS.md", "CODEX.md", "CLAUDE.md", "runtime/pyproject.toml")
        val process = ProcessBuilder(python, directory.resolve("runners/python/runner.py").toString())
            .directory(directory.toFile()).redirectErrorStream(true)
            .also {
                it.environment()["CODEX_HOME"] = codexHome
                it.environment()["AGENTOWN_CODEX_COMMAND"] = command
            }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertThat(process.waitFor()).describedAs(output).isZero()
        val result = mapper.readTree(output.substring(output.indexOf('{')))
        assertThat(result["status"].asText()).isEqualTo("SUCCEEDED")
        assertThat(result["output"].toString()).contains("reviewTable", "evidenceUrls")

        val report = Path.of("build/reports/agent-development-parallel-package-real-golden.json")
        Files.createDirectories(report.parent)
        mapper.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), mapOf(
            "request" to request, "validation" to validation, "graphPlan" to plan,
            "packageFiles" to files.keys.sorted(), "execution" to result,
        ))
    }

    private fun findCodex() = listOf(
        Path.of(System.getProperty("user.home"), ".local", "bin", "codex"),
        Path.of("/usr/local/bin/codex"), Path.of("/opt/homebrew/bin/codex"),
    ).firstOrNull(Files::isExecutable)?.toString()
}
