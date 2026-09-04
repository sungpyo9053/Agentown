package com.agentvillage.builder

import com.agentvillage.builder.application.*
import com.agentvillage.builder.infrastructure.MetaAgentRunRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
        )
        assertThat(files.getValue("runtime/pyproject.toml"))
            .contains("23d7a45dd9e2e52f54f44ff8f63c6dff28ef8603")
        assertThat(files.getValue("runners/python/runner.py"))
            .contains("AgentownTFrameXAdapter")
            .doesNotContain("Fixed Agentown mock runner", "제공된 근거와 입력을 선언된 출력 계약에 맞춰 처리한 검증용 결과입니다")
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

        assertThat(status["configured"].asBoolean()).isTrue()
        assertThat(definition["agents"].map { it["kind"]?.asText() }).containsOnly("tool")
        assertThat(definition.toString()).contains("data.csv.compare", "template.markdown.table")
        assertThat(files.getValue("runtime/agentown_tframex_adapter/capabilities.py"))
            .contains("def data_csv_compare", "def template_markdown_table")
            .doesNotContain("Mock research")
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
