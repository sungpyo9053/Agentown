package com.agentvillage.builder

import com.agentvillage.builder.application.*
import com.agentvillage.builder.domain.*
import com.agentvillage.builder.infrastructure.MetaAgentRunRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class AgentPackageRuntimeTest {
    private val mapper = jacksonObjectMapper()
    private val runs = mock<MetaAgentRunRepository>().also { whenever(it.save(any())).thenAnswer { call -> call.arguments[0] } }
    private val pipeline = StructuredMetaAgentPipeline(
        DeterministicMockMetaAgentModel(mapper), mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>(),
    )

    @Test
    fun `download package embeds the same pinned TFrameX adapter instead of a fixed mock runner`() {
        val bundle = pipeline.generateDesign(
            PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
            "입력 문서를 분석해 요약 결과를 반환하는 에이전트",
            StructuredMetaAgentPipeline.DesignMode.AUTOMATION,
        )
        val files = HarnessPackageRenderer(mapper).render(bundle)

        assertThat(files).containsKeys(
            "runtime-definition.json", "runtime-status.json", "runtime/pyproject.toml",
            "runtime/agentown_tframex_adapter/adapter.py", "runners/python/runner.py",
            "AGENTS.md", "CODEX.md", "CLAUDE.md", "START_HERE.md",
            "agent.yaml", "workflow.yaml", "workflow.json", "examples/sample-input.json",
            "schemas/input.schema.json", "schemas/output.schema.json",
        )
        assertThat(files.getValue("runtime/pyproject.toml"))
            .contains("23d7a45dd9e2e52f54f44ff8f63c6dff28ef8603")
        assertThat(files.getValue("runners/python/runner.py"))
            .contains("AgentownTFrameXAdapter")
            .doesNotContain("Fixed Agentown mock runner", "제공된 근거와 입력을 선언된 출력 계약에 맞춰 처리한 검증용 결과입니다")
        assertThat(files.getValue("CODEX.md")).contains("`AGENTS.md` is the single common execution contract")
        assertThat(files.getValue("CLAUDE.md")).contains("`AGENTS.md` is the single common execution contract")
        assertThat(files.getValue("AGENTS.md"))
            .contains("Join successor only after every predecessor succeeded", "EXECUTION_NOT_CONFIGURED")
        assertThat(files.getValue("START_HERE.md")).contains("codex", "claude", "examples/sample-input.json")
        val status = mapper.readTree(files.getValue("runtime-status.json"))
        assertThat(status["packageStatus"].asText()).isEqualTo("PACKAGE_VALIDATED")
        assertThat(status["interactiveStatus"].asText()).isEqualTo("INTERACTIVE_READY")
    }

    @Test
    fun `unconnected FAQ package is explicit execution not configured`() {
        val bundle = pipeline.generateDesign(
            PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
            "FAQ를 검색해서 고객 문의 답변을 만들고 근거가 없으면 담당자 확인이 필요하다고 알려주는 에이전트",
            StructuredMetaAgentPipeline.DesignMode.AUTOMATION,
        )
        val files = HarnessPackageRenderer(mapper).render(bundle)
        val status = mapper.readTree(files.getValue("runtime-status.json"))

        assertThat(status["configured"].asBoolean()).isFalse()
        assertThat(status["code"].asText()).isEqualTo("EXECUTION_NOT_CONFIGURED")
        assertThat(status["packageStatus"].asText()).isEqualTo("PACKAGE_VALIDATED")
        assertThat(status["interactiveStatus"].asText()).isEqualTo("INTERACTIVE_READY")
        assertThat(status["automationStatus"].asText()).isEqualTo("EXECUTION_NOT_CONFIGURED")
        assertThat(files.getValue("runtime-definition.json")).doesNotContain("Mock research")
    }

    @Test
    fun `CSV package maps deterministic capabilities to registered TFrameX tools`() {
        val bundle = pipeline.generateDesign(
            PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
            "두 CSV 파일을 ID 기준으로 비교해서 추가 수정 삭제 행을 표로 만들어줘",
            StructuredMetaAgentPipeline.DesignMode.AUTOMATION,
        )
        val files = HarnessPackageRenderer(mapper).render(bundle)
        val status = mapper.readTree(files.getValue("runtime-status.json"))
        val definition = mapper.readTree(files.getValue("runtime-definition.json"))

        assertThat(status["configured"].asBoolean()).isFalse()
        assertThat(status["runtimeConfigured"].asBoolean()).isTrue()
        assertThat(status["automationStatus"].asText()).isEqualTo("EXECUTION_NOT_CONFIGURED")
        val validatedStatus = mapper.readTree(HarnessPackageRenderer(mapper).render(bundle, automationValidated = true).getValue("runtime-status.json"))
        assertThat(validatedStatus["configured"].asBoolean()).isTrue()
        assertThat(validatedStatus["automationStatus"].asText()).isEqualTo("AUTOMATION_READY")
        assertThat(definition["agents"].map { it["kind"]?.asText() }).containsOnly("tool")
        assertThat(definition.toString()).contains("data.csv.compare", "template.markdown.table")
        val inputSchema = mapper.readTree(files.getValue("schemas/input.schema.json"))
        assertThat(inputSchema["properties"].fieldNames().asSequence().toList()).containsExactly("csvA", "csvB", "keyColumns")
        assertThat(inputSchema["required"].map { it.asText() }).containsExactly("csvA", "csvB")
        assertThat(inputSchema["properties"]["keyColumns"]["type"].asText()).isEqualTo("array")
        assertThat(definition["workflowInputSchema"].map { it["name"].asText() }).containsExactly("csvA", "csvB", "keyColumns")
        assertThat(mapper.readTree(definition["input"].asText()).fieldNames().asSequence().toList())
            .containsExactly("csvA", "csvB", "keyColumns")
        assertThat(files.getValue("runtime/agentown_tframex_adapter/capabilities.py"))
            .contains("def data_csv_compare", "def template_markdown_table")
            .doesNotContain("Mock research")
    }

    @Test
    fun `package input schema exposes user fields and not internal agent fields`() {
        val bundle = pipeline.generateDesign(
            PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
            "분석 담당과 작성 담당이 제공된 기록을 순서대로 처리해 결과를 반환한다",
            StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
        )
        val files = HarnessPackageRenderer(mapper).render(bundle)
        val schema = mapper.readTree(files.getValue("schemas/input.schema.json"))
        val sample = mapper.readTree(files.getValue("examples/sample-input.json"))

        assertThat(schema["required"].map { it.asText() }).containsExactly("text")
        assertThat(sample.fieldNames().asSequence().toList()).containsExactly("text")
        assertThat(schema.toString()).doesNotContain("analysis", "result")
    }

    @Test
    fun `runtime definition uses the immutable workflow final output contract`() {
        val generated = pipeline.generateDesign(
            PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
            "입력 기록을 분석해 결과를 반환하는 에이전트",
            StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
        )
        val finalContract = listOf(FieldDefinition("publicResult", "string", true, "immutable public result"))
        val bundle = generated.copy(proposal = generated.proposal.copy(outputSchema = finalContract))

        val files = HarnessPackageRenderer(mapper).render(bundle)
        val runtimeFields = mapper.readTree(files.getValue("runtime-definition.json"))["finalOutputSchema"]
            .map { it["name"].asText() }
        val packageFields = mapper.readTree(files.getValue("schemas/final-output.schema.json"))["required"]
            .map { it.asText() }

        assertThat(runtimeFields).containsExactlyElementsOf(packageFields).containsExactly("publicResult")
        assertThat(mapper.readTree(files.getValue("schemas/output.schema.json"))["required"].map { it.asText() })
            .containsExactly("publicResult")
    }

    @Test
    fun `terminal tool derived schema is identical across every package contract`() {
        val generated = pipeline.generateDesign(
            PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
            "입력 기록을 분석해 결과를 반환하는 에이전트",
            StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
        )
        val originalPlan = generated.proposal.graphPlan!!
        val aiNode = originalPlan.nodes.single { it.nodeType == "ai.generate" }
        val sourceAgent = generated.agentDefinitions.single { it.key == aiNode.config["agentKey"] }
            .copy(outputSchema = listOf(FieldDefinition("content", "string", true, "content")))
        val render = WorkflowNodePlan("render", "template.render", "Render", mapOf("rendererKey" to "plain-text.v1"))
        val plan = originalPlan.copy(
            nodes = originalPlan.nodes + render,
            edges = originalPlan.edges + WorkflowEdgePlan(
                "render-edge", aiNode.id, render.id,
                bindings = listOf(WorkflowFieldBinding("content", "content")),
            ),
        )
        val bundle = generated.copy(
            proposal = generated.proposal.copy(graphPlan = plan, outputSchema = emptyList()),
            agentDefinitions = listOf(sourceAgent),
        )

        val files = HarnessPackageRenderer(mapper).render(bundle)
        val runtimeRequired = mapper.readTree(files.getValue("runtime-definition.json"))["finalOutputSchema"].map { it["name"].asText() }
        val outputRequired = mapper.readTree(files.getValue("schemas/output.schema.json"))["required"].map { it.asText() }
        val finalRequired = mapper.readTree(files.getValue("schemas/final-output.schema.json"))["required"].map { it.asText() }
        val templateRequired = mapper.readTree(files.getValue("templates/output-template.json"))["contentSchema"]["required"].map { it.asText() }

        assertThat(runtimeRequired).containsExactly("content", "renderedResponse")
        assertThat(outputRequired).isEqualTo(runtimeRequired)
        assertThat(finalRequired).isEqualTo(runtimeRequired)
        assertThat(templateRequired).isEqualTo(runtimeRequired)
    }

    @Test
    fun `package JSON schema preserves integer type and exact array cardinality`() {
        val generated = pipeline.generateDesign(
            PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
            "세 지점의 재고를 비교한다",
            StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
        )
        val constrainedInputs = listOf(
            FieldDefinition("warehouses", "array", true, "exactly three warehouses", minItems = 3, maxItems = 3),
            FieldDefinition("attempts", "integer", true, "retry count"),
            FieldDefinition(
                "warehouseResults", "array", false, "structured results", itemType = "object",
                itemSchema = listOf(
                    FieldDefinition("warehouse", "string", true, "warehouse"),
                    FieldDefinition("evidenceIds", "array", true, "evidence", itemType = "string"),
                ),
            ),
        )
        val files = HarnessPackageRenderer(mapper).render(
            generated.copy(proposal = generated.proposal.copy(inputSchema = constrainedInputs)),
        )
        val properties = mapper.readTree(files.getValue("schemas/input.schema.json"))["properties"]
        val sample = mapper.readTree(files.getValue("examples/sample-input.json"))

        assertThat(properties["warehouses"]["type"].asText()).isEqualTo("array")
        assertThat(properties["warehouses"]["minItems"].asInt()).isEqualTo(3)
        assertThat(properties["warehouses"]["maxItems"].asInt()).isEqualTo(3)
        assertThat(properties["attempts"]["type"].asText()).isEqualTo("integer")
        assertThat(sample["warehouses"]).hasSize(3)
        assertThat(sample["attempts"].isIntegralNumber).isTrue()
        assertThat(sample["warehouseResults"].isArray).isTrue()
        val resultItems = properties["warehouseResults"]["items"]
        assertThat(resultItems["additionalProperties"].asBoolean()).isFalse()
        assertThat(resultItems["required"].map { it.asText() }).containsExactly("warehouse", "evidenceIds")
        assertThat(resultItems["properties"]["evidenceIds"]["items"]["type"].asText()).isEqualTo("string")
    }

    @Test
    fun `sample input recursively satisfies an exact three item object array contract`() {
        val generated = pipeline.generateDesign(
            PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
            "서로 독립적인 자료들을 분석한 뒤 하나의 결과로 종합한다",
            StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
        )
        val reportFields = listOf(
            FieldDefinition(
                "reports", "array", true, "분석할 기술 보고서", minItems = 3, maxItems = 3, itemType = "object",
                itemSchema = listOf(
                    FieldDefinition("title", "string", true, "보고서 제목", minLength = 1),
                    FieldDefinition("publishedAt", "string", true, "발행일", format = "date"),
                    FieldDefinition("sourceUrls", "array", true, "근거 URL", minItems = 1, itemType = "string", itemFormat = "uri"),
                ),
                uniqueBy = "title",
            ),
        )
        val files = HarnessPackageRenderer(mapper).render(
            generated.copy(proposal = generated.proposal.copy(inputSchema = reportFields)),
        )
        val sample: Map<String, Any?> = mapper.readValue(files.getValue("examples/sample-input.json"))

        assertThat(WorkflowInputContract.valueIssue(reportFields, sample)).isNull()
        val schemaItems = mapper.readTree(files.getValue("schemas/input.schema.json"))["properties"]["reports"]
        assertThat(schemaItems["x-agentown-uniqueBy"].asText()).isEqualTo("title")
        assertThat(schemaItems["items"]["properties"]["publishedAt"]["format"].asText()).isEqualTo("date")
        assertThat(schemaItems["items"]["properties"]["sourceUrls"]["items"]["format"].asText()).isEqualTo("uri")
        val reports = sample["reports"] as List<*>
        assertThat(reports).hasSize(3)
        assertThat(reports).allSatisfy { report ->
            val value = report as Map<*, *>
            assertThat(value["title"].toString()).isNotBlank()
            assertThat(value["publishedAt"]).isEqualTo("2026-09-05")
            assertThat(value["sourceUrls"] as List<*>).hasSize(1)
        }
    }

    @Test
    fun `downloaded CSV package executes through its embedded pinned TFrameX runtime`(@TempDir directory: Path) {
        val python = System.getenv("TFRAMEX_TEST_PYTHON") ?: return
        val bundle = pipeline.generateDesign(
            PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
            "두 CSV 파일을 ID 기준으로 비교해서 추가 수정 삭제 행을 표로 만들어줘",
            StructuredMetaAgentPipeline.DesignMode.AUTOMATION,
        )
        val files = HarnessPackageRenderer(mapper).render(bundle)
        files.forEach { (relativePath, content) ->
            val target = directory.resolve(relativePath)
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }

        val process = ProcessBuilder(python, directory.resolve("runners/python/runner.py").toString())
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        assertThat(exitCode).describedAs(output).isZero()
        val jsonStart = output.indexOf('{')
        assertThat(jsonStart).describedAs(output).isGreaterThanOrEqualTo(0)
        val result = mapper.readTree(output.substring(jsonStart))
        assertThat(result["status"].asText()).isEqualTo("SUCCEEDED")
        assertThat(result["trace"].map { it["kind"].asText() })
            .contains("agent_start", "tool_start", "tool_end", "agent_end")
        assertThat(result.toString()).contains("MODIFIED", "REMOVED", "ADDED")
    }
}
