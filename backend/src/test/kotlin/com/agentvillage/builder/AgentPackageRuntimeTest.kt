package com.agentvillage.builder

import com.agentvillage.builder.application.*
import com.agentvillage.builder.infrastructure.MetaAgentRunRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class AgentPackageRuntimeTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `validated agent package runs in fixed python mock runtime without external calls`() {
        assumeTrue(runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }.getOrDefault(false))
        val mapper = jacksonObjectMapper()
        val runs = mock<MetaAgentRunRepository>()
        whenever(runs.save(any())).thenAnswer { it.arguments[0] }
        val pipeline = StructuredMetaAgentPipeline(
            DeterministicMockMetaAgentModel(mapper), mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>(),
        )
        val bundle = pipeline.generateDesign(
            PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
            "Slack #customer-support 문의를 Notion FAQ에서 찾아 답변 초안을 만들고 담당자 승인 후 원래 Slack 스레드로 전송한다.",
        )
        val files = HarnessPackageRenderer(mapper).render(bundle)
        files.forEach { (path, content) ->
            val target = directory.resolve(path)
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }

        val process = ProcessBuilder("python3", directory.resolve("runners/python/runner.py").toString(), "--approve")
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()

        assertThat(process.waitFor()).isZero()
        assertThat(output).contains("\"status\": \"SUCCEEDED\"", "\"externalCallPerformed\": false")
        assertThat(output).doesNotContain("https://slack.com", "api.notion.com")
    }
}
