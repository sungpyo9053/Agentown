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

@EnabledIfEnvironmentVariable(named = "REAL_AGENT_DEVELOPMENT_TFRAMEX_GOLDEN", matches = "true")
class AgentDevelopmentTFrameXRealGoldenTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `natural language compiler creates three real parallel agents and a joined reporter`(@TempDir directory: Path) {
        val codex = System.getenv("REAL_CODEX_COMMAND") ?: findCodex()
        val codexHome = System.getenv("REAL_CODEX_HOME") ?: Path.of(System.getProperty("user.home"), ".codex").toString()
        val python = requireNotNull(System.getenv("TFRAMEX_TEST_PYTHON"))
        val runner = CodexCliRunner(codex, 180, codexHome)
        require(runner.hasSharedAuth())
        val model = CodexCliMetaAgentModel(mock<CredentialDirectory>(), runner, mapper, System.getenv("REAL_CODEX_MODEL") ?: "gpt-5.6-luna")
        val runs = mock<MetaAgentRunRepository>().also { whenever(it.save(any())).thenAnswer { call -> call.arguments[0] } }
        val pipeline = StructuredMetaAgentPipeline(model, mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>())
        val request = """
            Alpha, Beta, Gamma 세 경쟁사를 각각 독립 Agent가 병렬 조사하고, 세 결과가 모두 끝난 뒤 Reporter가 비교 보고서를 작성해줘.
            입력은 competitors 배열(정확히 3개), asOfDate, fixture이고 실제 검색 Connector가 없으므로 fixture만 근거로 사용해.
            각 조사 결과에는 competitor, productAnnouncements, priceChanges, publishedAt, sourceUrls, status, error를 포함하고,
            Reporter 입력은 세 조사 결과 배열을 필수로 받으며 최종 결과에는 경쟁사별 결과와 comparisonSummary, status를 포함해.
        """.trimIndent()
        val bundle = System.getenv("REAL_AGENT_DEVELOPMENT_BUNDLE_FILE")?.let { path ->
            mapper.readValue(Path.of(path).toFile(), MetaAgentDesignBundle::class.java)
        } ?: pipeline.generateDesign(
            context(), agentDevelopmentPrompt(request), StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
            userInstruction = request,
        )
        val plan = requireNotNull(bundle.proposal.graphPlan)
        val aiNodes = plan.nodes.filter { it.nodeType in setOf(NodeType.AI_GENERATE.wireName, NodeType.AI_CLASSIFY.wireName) }
        val incoming = plan.edges.groupBy { it.target }
        val reporter = aiNodes.single { incoming[it.id].orEmpty().map { edge -> edge.source }.distinct().size == 3 }
        val workers = incoming.getValue(reporter.id).map { it.source }.toSet()

        assertThat(workers).hasSize(3)
        assertThat(aiNodes).hasSize(4)
        assertThat(bundle.agentDefinitions).hasSizeGreaterThanOrEqualTo(2)
        assertThat(plan.nodes.map { it.nodeType }).doesNotContain(NodeType.PARALLEL_MAP_MOCK.wireName)

        val bundleReport = Path.of("build/reports/agent-development-tframex-real-golden-bundle.json")
        Files.createDirectories(bundleReport.parent)
        mapper.writerWithDefaultPrettyPrinter().writeValue(bundleReport.toFile(), bundle)
        val definition = TFrameXDefinitionCompiler(mapper).compilePlan(bundle.proposal.name, plan, bundle.agentDefinitions, fixtureInput())
        assertThat(mapper.writeValueAsString(definition)).contains("ParallelPattern", "structuredFanIn")
        val definitionFile = directory.resolve("definition.json")
        mapper.writeValue(definitionFile.toFile(), definition)
        val script = directory.resolve("execute.py")
        Files.writeString(script, """
            import asyncio, json, sys
            from agentown_tframex_adapter import AgentownTFrameXAdapter, CodexCliLLMWrapper
            from agentown_tframex_adapter.capabilities import BUILTIN_TOOLS
            definition = json.load(open(sys.argv[1]))
            result = asyncio.run(AgentownTFrameXAdapter(llm=CodexCliLLMWrapper(), tools=BUILTIN_TOOLS).run(definition))
            print("__AGENTOWN_RESULT__" + json.dumps(result, ensure_ascii=False, default=str))
        """.trimIndent())
        val process = ProcessBuilder(python, script.toString(), definitionFile.toString())
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .also {
                it.environment()["PYTHONPATH"] = Path.of("core-runtime").toAbsolutePath().normalize().toString()
                it.environment()["CODEX_HOME"] = codexHome
                it.environment()["AGENTOWN_CODEX_COMMAND"] = codex
                it.environment()["AGENTOWN_CODEX_MODEL"] = System.getenv("REAL_CODEX_MODEL") ?: "gpt-5.6-luna"
            }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val report = Path.of("build/reports/agent-development-tframex-real-golden.log")
        Files.createDirectories(report.parent)
        Files.writeString(report, output)
        assertThat(process.waitFor()).describedAs(output).isZero()
        assertThat(output).describedAs(output).contains("__AGENTOWN_RESULT__")
        val result = mapper.readTree(output.substringAfter("__AGENTOWN_RESULT__").trim())
        val trace = result["trace"]
        val effectiveReporter = "${reporter.config.getValue("agentKey").toString().replace('.', '-')}__${reporter.id}"
        val reporterStart = trace.indexOfFirst { it["kind"].asText() == "agent_start" && it["agent"].asText() == effectiveReporter }
        val workerStarts = trace.withIndex().filter { it.value["kind"].asText() == "agent_start" && it.value["agent"].asText() != effectiveReporter }.map { it.index }
        val workerEnds = trace.withIndex().filter { it.value["kind"].asText() == "agent_end" && it.value["agent"].asText() != effectiveReporter }.map { it.index }
        assertThat(workerStarts).hasSize(3).allMatch { it < reporterStart }
        assertThat(workerEnds).hasSize(3).allMatch { it < reporterStart }
        assertThat(result["final"].asText()).contains("Alpha", "Beta", "Gamma", "https://example.com/alpha")
    }

    private fun fixtureInput() = mapOf(
        "competitors" to listOf("Alpha", "Beta", "Gamma"),
        "asOfDate" to "2026-09-04",
        "fixture" to mapOf(
            "Alpha" to facts(listOf("A1 launch"), listOf("Pro 10 to 12"), "2026-09-01", "https://example.com/alpha"),
            "Beta" to facts(listOf("B2 beta"), emptyList(), "2026-09-02", "https://example.com/beta"),
            "Gamma" to facts(emptyList(), listOf("Basic 5 to 6"), "2026-09-03", "https://example.com/gamma"),
        ),
    )

    private fun facts(products: List<String>, prices: List<String>, date: String, url: String) = mapOf(
        "productAnnouncements" to products, "priceChanges" to prices, "publishedAt" to date, "sourceUrls" to listOf(url),
    )

    private fun context() = PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    private fun findCodex() = listOf(
        Path.of(System.getProperty("user.home"), ".local", "bin", "codex"), Path.of("/usr/local/bin/codex"), Path.of("/opt/homebrew/bin/codex"),
    ).first(Files::isExecutable).toString()
}
