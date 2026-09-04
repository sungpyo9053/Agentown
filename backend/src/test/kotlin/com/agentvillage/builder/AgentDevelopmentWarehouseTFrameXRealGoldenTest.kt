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

@EnabledIfEnvironmentVariable(named = "REAL_AGENT_DEVELOPMENT_WAREHOUSE_GOLDEN", matches = "true")
class AgentDevelopmentWarehouseTFrameXRealGoldenTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `blind warehouse request creates independent workers and a joined evidence report`(@TempDir directory: Path) {
        val codex = System.getenv("REAL_CODEX_COMMAND") ?: findCodex()
        val codexHome = System.getenv("REAL_CODEX_HOME") ?: Path.of(System.getProperty("user.home"), ".codex").toString()
        val python = requireNotNull(System.getenv("TFRAMEX_TEST_PYTHON"))
        val runner = CodexCliRunner(codex, 180, codexHome)
        require(runner.hasSharedAuth())
        val model = CodexCliMetaAgentModel(mock<CredentialDirectory>(), runner, mapper, System.getenv("REAL_CODEX_MODEL") ?: "gpt-5.6-luna")
        val runs = mock<MetaAgentRunRepository>().also { whenever(it.save(any())).thenAnswer { call -> call.arguments[0] } }
        val pipeline = StructuredMetaAgentPipeline(model, mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>())
        val request = """
            동부, 서부, 중앙 세 창고의 재고 스냅샷을 각각 독립 Agent가 동시에 점검하고, 세 결과가 모두 도착한 뒤 총괄 Agent가 품절 위험과 창고 간 재배치 제안을 종합해줘.
            입력은 warehouses 문자열 배열(정확히 3개), asOfDate, fixture이고 외부 시스템을 조회하지 말고 제공된 fixture와 evidenceIds만 근거로 사용해.
            창고별 결과에는 warehouse, shortageSkus, surplusSkus, observedAt, evidenceIds, status, error를 포함해.
            총괄 Agent 입력은 warehouseResults 배열을 필수로 받고 최종 결과에는 warehouseResults, transferRecommendations, summary, status를 포함해.
        """.trimIndent()
        val pipelineContext = context()
        var bundle = System.getenv("REAL_AGENT_DEVELOPMENT_BUNDLE_FILE")?.let { path ->
            mapper.readValue(Path.of(path).toFile(), MetaAgentDesignBundle::class.java)
        } ?: pipeline.generateDesign(
            pipelineContext, agentDevelopmentPrompt(request), StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
            userInstruction = request,
        )
        val catalog = WorkflowNodeCatalog()
        val translator = WorkflowGraphTranslator(catalog)
        val validator = WorkflowGraphValidator(catalog, mapper)
        fun validate(candidate: MetaAgentDesignBundle) = validator.validate(
            translator.translate(pipelineContext.workflowId, candidate.proposal),
            candidate.requirement,
            candidate.proposal,
            candidate.agentDefinitions,
            request,
        )
        var validation = validate(bundle)
        for (repairAttempt in 1..2) {
            if (validation.valid) break
            bundle = pipeline.generateDesign(
                pipelineContext, agentDevelopmentPrompt(request), StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
                validationFeedback = validation.issues, previousBundle = bundle, userInstruction = request,
            )
            validation = validate(bundle)
        }
        assertThat(validation.issues).isEmpty()
        val bundleReport = Path.of("build/reports/agent-development-warehouse-tframex-real-golden-bundle.json")
        Files.createDirectories(bundleReport.parent)
        mapper.writerWithDefaultPrettyPrinter().writeValue(bundleReport.toFile(), bundle)
        val plan = requireNotNull(bundle.proposal.graphPlan)
        assertThat(bundle.proposal.inputSchema.map { it.name })
            .containsExactly("warehouses", "asOfDate", "fixture")
        val warehousesInput = bundle.proposal.inputSchema.single { it.name == "warehouses" }
        assertThat(warehousesInput.minItems).isEqualTo(3)
        assertThat(warehousesInput.maxItems).isEqualTo(3)
        val aiNodes = plan.nodes.filter { it.nodeType in setOf(NodeType.AI_GENERATE.wireName, NodeType.AI_CLASSIFY.wireName) }
        val incoming = plan.edges.groupBy { it.target }
        val reporter = aiNodes.single { incoming[it.id].orEmpty().map { edge -> edge.source }.distinct().size == 3 }
        val expectedWarehouseFields = listOf(
            "warehouse", "shortageSkus", "surplusSkus", "observedAt", "evidenceIds", "status", "error",
        )
        fun assertWarehouseResultsContract(field: FieldDefinition) {
            assertThat(field.itemType).isEqualTo("object")
            assertThat(field.itemSchema?.map { it.name }).containsExactlyInAnyOrderElementsOf(expectedWarehouseFields)
            assertThat(field.itemSchema?.single { it.name == "evidenceIds" }?.itemType).isEqualTo("string")
        }
        assertWarehouseResultsContract(bundle.proposal.outputSchema.single { it.name == "warehouseResults" })
        val reporterDefinition = bundle.agentDefinitions.single { it.key == reporter.config.getValue("agentKey") }
        assertWarehouseResultsContract(reporterDefinition.inputSchema.single { it.name == "warehouseResults" })
        val workerIds = incoming.getValue(reporter.id).map { it.source }.toSet()
        assertThat(workerIds).hasSize(3)
        assertThat(aiNodes).hasSize(4)
        assertThat(plan.nodes.map { it.nodeType }).doesNotContain(NodeType.PARALLEL_MAP_MOCK.wireName)
        val packageRoot = directory.resolve("downloaded-package")
        val packageFiles = HarnessPackageRenderer(mapper).render(bundle)
        assertThat(mapper.readTree(packageFiles.getValue("schemas/input.schema.json"))["required"].map { it.asText() })
            .containsExactly("warehouses", "asOfDate", "fixture")
        packageFiles.forEach { (relativePath, content) ->
            val target = packageRoot.resolve(relativePath)
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(packageRoot.resolve("examples/sample-input.json").toFile(), fixtureInput())
        val definition = mapper.readValue(packageRoot.resolve("runtime-definition.json").toFile(), Map::class.java)
        assertThat(mapper.writeValueAsString(definition)).contains("ParallelPattern", "structuredFanIn")
        val process = ProcessBuilder(python, packageRoot.resolve("runners/python/runner.py").toString())
            .directory(packageRoot.toFile())
            .redirectErrorStream(true)
            .also {
                it.environment()["PYTHONPATH"] = Path.of("core-runtime").toAbsolutePath().normalize().toString()
                it.environment()["CODEX_HOME"] = codexHome
                it.environment()["AGENTOWN_CODEX_COMMAND"] = codex
                it.environment()["AGENTOWN_CODEX_MODEL"] = System.getenv("REAL_CODEX_MODEL") ?: "gpt-5.6-luna"
            }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val report = Path.of("build/reports/agent-development-warehouse-tframex-real-golden.log")
        Files.createDirectories(report.parent)
        Files.writeString(report, output)
        assertThat(process.waitFor()).describedAs(output).isZero()
        val jsonStart = output.indexOf('{')
        assertThat(jsonStart).describedAs(output).isGreaterThanOrEqualTo(0)
        val result = mapper.readTree(output.substring(jsonStart))
        assertThat(result["status"].asText()).isEqualTo("SUCCEEDED")
        val trace = result["trace"]
        val effectiveReporter = "${reporter.config.getValue("agentKey").toString().replace('.', '-')}__${reporter.id}"
        val reporterStart = trace.indexOfFirst { it["kind"].asText() == "agent_start" && it["agent"].asText() == effectiveReporter }
        val workerStarts = trace.withIndex().filter { it.value["kind"].asText() == "agent_start" && it.value["agent"].asText() != effectiveReporter }.map { it.index }
        val workerEndEvents = trace.withIndex().filter { it.value["kind"].asText() == "agent_end" && it.value["agent"].asText() != effectiveReporter }
        val workerEnds = workerEndEvents.map { it.index }
        assertThat(workerStarts).hasSize(3).allMatch { it < reporterStart }
        assertThat(workerEnds).hasSize(3).allMatch { it < reporterStart }
        val workerOutputFieldByAgent = workerIds.associate { nodeId ->
            val node = plan.nodes.single { it.id == nodeId }
            val effectiveName = "${node.config.getValue("agentKey").toString().replace('.', '-')}__${node.id}"
            val outputField = plan.edges.single { it.source == nodeId && it.target == reporter.id }
                .bindings.single { it.targetField == "warehouseResults" }.sourceField
            effectiveName to outputField
        }
        val workerWarehouses = workerEndEvents.flatMap { event ->
            val outputField = workerOutputFieldByAgent.getValue(event.value["agent"].asText())
            mapper.readTree(event.value["output"].asText())[outputField].also { results -> assertThat(results).hasSize(1) }
                .map { it["warehouse"].asText() }
        }
        assertThat(workerWarehouses).containsExactlyInAnyOrder("동부", "서부", "중앙")
        val finalOutput = mapper.readTree(result["final"].asText())
        assertThat(finalOutput.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrderElementsOf(bundle.proposal.outputSchema.map { it.name })
        assertThat(finalOutput.toString()).contains("동부", "서부", "중앙", "E-WH-001", "W-WH-001", "C-WH-001")
    }

    private fun fixtureInput() = mapOf(
        "warehouses" to listOf("동부", "서부", "중앙"),
        "asOfDate" to "2026-09-05",
        "fixture" to mapOf(
            "동부" to facts(listOf("SKU-A"), listOf("SKU-C"), "E-WH-001"),
            "서부" to facts(listOf("SKU-B"), listOf("SKU-A"), "W-WH-001"),
            "중앙" to facts(emptyList(), listOf("SKU-B"), "C-WH-001"),
        ),
    )

    private fun facts(shortage: List<String>, surplus: List<String>, evidence: String) = mapOf(
        "shortageSkus" to shortage,
        "surplusSkus" to surplus,
        "observedAt" to "2026-09-05T09:00:00+09:00",
        "evidenceIds" to listOf(evidence),
    )

    private fun context() = PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    private fun findCodex() = listOf(
        Path.of(System.getProperty("user.home"), ".local", "bin", "codex"), Path.of("/usr/local/bin/codex"), Path.of("/opt/homebrew/bin/codex"),
    ).first(Files::isExecutable).toString()
}
