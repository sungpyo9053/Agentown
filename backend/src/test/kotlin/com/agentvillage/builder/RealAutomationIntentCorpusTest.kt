package com.agentvillage.builder

import com.agentvillage.builder.application.*
import com.agentvillage.builder.domain.*
import com.agentvillage.builder.infrastructure.MetaAgentRunRepository
import com.agentvillage.common.exception.ApiException
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
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.ceil

@EnabledIfEnvironmentVariable(named = "REAL_META_AGENT_CORPUS", matches = "true")
class RealAutomationIntentCorpusTest {
    private val mapper = jacksonObjectMapper()

    data class CorpusRow(
        val id: String,
        val category: String,
        val sourceUrl: String,
        val concreteExpected: String,
        val minAgents: Int,
        val concreteInstruction: String,
        val vagueInstruction: String,
    )

    data class CaseResult(
        val id: String,
        val category: String,
        val variant: String,
        val expected: String,
        val actual: String,
        val passed: Boolean,
        val durationMs: Long,
        val agentCount: Int = 0,
        val nodeTypes: List<String> = emptyList(),
        val issues: List<String> = emptyList(),
        val errorCode: String? = null,
        val detail: String? = null,
    )

    @Test
    fun `real Codex meta agent reaches eighty percent across one hundred researched intents`() {
        val rows = loadCorpus()
        val modelName = System.getenv("REAL_CODEX_MODEL") ?: "gpt-5.6-luna"
        val command = System.getenv("REAL_CODEX_COMMAND") ?: requireNotNull(findCodex()) { "codex command not found" }
        val codexHome = System.getenv("REAL_CODEX_HOME") ?: Path.of(System.getProperty("user.home"), ".codex").toString()
        val concurrency = (System.getenv("REAL_META_AGENT_CONCURRENCY") ?: "4").toInt().coerceIn(1, 8)
        val caseLimit = (System.getenv("REAL_META_AGENT_CASE_LIMIT") ?: "100").toInt().coerceIn(1, 100)
        val runner = CodexCliRunner(command, 180, codexHome)
        require(runner.hasSharedAuth()) { "shared Codex auth is missing: $codexHome/auth.json" }

        val credentials = mock<CredentialDirectory>()
        val limiter = mock<BuilderUsageLimiter>()
        whenever(limiter.isUnlimited(any())).thenReturn(true)
        val model = CodexCliMetaAgentModel(credentials, runner, mapper, modelName)
        val runs = mock<MetaAgentRunRepository>()
        whenever(runs.save(any())).thenAnswer { it.arguments[0] }
        val pipeline = StructuredMetaAgentPipeline(model, mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>())
        val validator = WorkflowGraphValidator(WorkflowNodeCatalog(), mapper)
        val cases = rows.flatMap { row ->
            listOf(
                TestCase("${row.id}-A", row, "CONCRETE", row.concreteExpected, row.concreteInstruction, row.minAgents),
                TestCase("${row.id}-B", row, "VAGUE", "CLARIFY", row.vagueInstruction, 0),
            )
        }.take(caseLimit)

        val started = Instant.now()
        val pool = Executors.newFixedThreadPool(concurrency)
        val results = try {
            pool.invokeAll(cases.map { case -> Callable { evaluate(case, pipeline, validator) } }).map { it.get() }
        } finally {
            pool.shutdownNow()
        }
        val passed = results.count(CaseResult::passed)
        val report = mapOf(
            "mode" to "real-codex-cli",
            "model" to modelName,
            "total" to results.size,
            "passed" to passed,
            "failed" to results.size - passed,
            "passRate" to passed.toDouble() / results.size,
            "threshold" to 0.80,
            "thresholdMet" to (passed >= ceil(results.size * 0.80).toInt()),
            "concurrency" to concurrency,
            "durationSeconds" to Duration.between(started, Instant.now()).seconds,
            "results" to results,
        )
        val target = Path.of("build/reports/real-automation-intent-corpus.json")
        Files.createDirectories(target.parent)
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), report)

        val requiredPassed = ceil(results.size * 0.80).toInt()
        assertThat(results).hasSize(caseLimit)
        assertThat(passed)
            .withFailMessage("Real Codex corpus pass rate was $passed/${results.size}; see $target")
            .isGreaterThanOrEqualTo(requiredPassed)
    }

    private fun evaluate(case: TestCase, pipeline: StructuredMetaAgentPipeline, validator: WorkflowGraphValidator): CaseResult {
        val started = System.nanoTime()
        return try {
            val bundle = pipeline.generateDesign(context(), case.instruction)
            if (bundle.clarificationQuestions.isNotEmpty()) {
                result(case, "CLARIFY", case.expected == "CLARIFY", started, detail = bundle.clarificationQuestions.joinToString { it.field })
            } else {
                val plan = bundle.proposal.graphPlan
                if (plan == null) return result(case, "INVALID_GRAPH", false, started, detail = "graphPlan missing")
                val graph = WorkflowGraph(
                    workflowId = UUID.randomUUID(),
                    entryNodeId = plan.entryNodeId,
                    nodes = plan.nodes.mapIndexed { index, node ->
                        WorkflowNode(node.id, node.nodeType, node.label, NodePosition(40.0 + index * 260.0, 100.0), node.config)
                    },
                    edges = plan.edges.map { WorkflowEdge(it.id, it.source, it.target, it.condition) },
                )
                val validation = validator.validate(graph, bundle.requirement, bundle.proposal, bundle.agentDefinitions, case.instruction)
                val meaningPreserved = bundle.agentDefinitions.size >= case.minAgents &&
                    (case.instruction.contains("Slack", true) || graph.nodes.none { it.nodeType.startsWith("slack.") }) &&
                    (case.instruction.contains("Notion", true) || case.instruction.contains("FAQ", true) || graph.nodes.none { it.nodeType.startsWith("notion.") })
                result(
                    case,
                    if (validation.valid && meaningPreserved) "DESIGN" else "INVALID_GRAPH",
                    case.expected == "DESIGN" && validation.valid && meaningPreserved,
                    started,
                    bundle.agentDefinitions.size,
                    graph.nodes.map(WorkflowNode::nodeType),
                    validation.issues.map(ValidationIssue::code),
                )
            }
        } catch (exception: ApiException) {
            val actual = if (exception.code == "AUTOMATION_CAPABILITY_REQUIRED") "CAPABILITY_REQUIRED" else "REJECT"
            result(case, actual, case.expected == actual, started, errorCode = exception.code, detail = exception.message)
        } catch (exception: Exception) {
            result(case, "ERROR", false, started, errorCode = exception::class.simpleName, detail = exception.message)
        }
    }

    private fun result(
        case: TestCase,
        actual: String,
        passed: Boolean,
        started: Long,
        agentCount: Int = 0,
        nodeTypes: List<String> = emptyList(),
        issues: List<String> = emptyList(),
        errorCode: String? = null,
        detail: String? = null,
    ) = CaseResult(
        case.id, case.row.category, case.variant, case.expected, actual, passed,
        (System.nanoTime() - started) / 1_000_000, agentCount, nodeTypes, issues, errorCode, detail,
    )

    private fun context() = PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

    private fun loadCorpus(): List<CorpusRow> {
        val lines = requireNotNull(javaClass.getResourceAsStream("/builder/automation-intent-corpus.tsv"))
            .bufferedReader().use { it.readLines() }.filter(String::isNotBlank)
        return lines.drop(1).map { line ->
            val columns = line.split('\t')
            require(columns.size == 7) { "Invalid corpus row: $line" }
            CorpusRow(columns[0], columns[1], columns[2], if (columns[3] == "REJECT") "CAPABILITY_REQUIRED" else columns[3], columns[4].toInt(), columns[5], columns[6])
        }
    }

    private fun findCodex() = listOf(
        Path.of(System.getProperty("user.home"), ".local", "bin", "codex"),
        Path.of("/usr/local/bin/codex"),
        Path.of("/opt/homebrew/bin/codex"),
    ).firstOrNull(Files::isExecutable)?.toString()

    private data class TestCase(
        val id: String,
        val row: CorpusRow,
        val variant: String,
        val expected: String,
        val instruction: String,
        val minAgents: Int,
    )
}
