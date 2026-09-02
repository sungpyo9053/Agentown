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

        assertThat(process.waitFor()).withFailMessage(output).isZero()
        assertThat(output).contains("\"status\": \"SUCCEEDED\"", "\"externalCallPerformed\": false")
        assertThat(output).doesNotContain("https://slack.com", "api.notion.com")
    }

    @Test
    fun `package runner follows FAQ evidence branch and rejects final schema violations`() {
        assumeTrue(runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }.getOrDefault(false))
        val mapper = jacksonObjectMapper()
        val runs = mock<MetaAgentRunRepository>().also { whenever(it.save(any())).thenAnswer { call -> call.arguments[0] } }
        val pipeline = StructuredMetaAgentPipeline(
            DeterministicMockMetaAgentModel(mapper), mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>(),
        )
        val bundle = pipeline.generateDesign(
            PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
            "FAQ를 검색해서 고객 문의 답변을 만들고 근거가 없으면 담당자 확인이 필요하다고 알려주는 에이전트",
            StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
        )
        val files = HarnessPackageRenderer(mapper).render(bundle).toMutableMap()
        files["examples/sample-input.json"] = mapper.writeValueAsString(mapOf(
            "customerInquiry" to "사내 복지포인트는 언제 지급되나요?",
            "mockSearchResults" to emptyList<Any>(),
        ))
        files.forEach { (path, content) ->
            val target = directory.resolve(path)
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }

        val missingEvidence = ProcessBuilder("python3", directory.resolve("runners/python/runner.py").toString())
            .redirectErrorStream(true).start()
        val missingOutput = missingEvidence.inputStream.bufferedReader().readText()
        assertThat(missingEvidence.waitFor()).isZero()
        assertThat(missingOutput).contains("\"status\": \"SUCCEEDED\"", "\"needsAssigneeReview\": true")
            .doesNotContain("\"draftResponse\"")

        Files.writeString(directory.resolve("schemas/final-output.schema.json"), """{"type":"object","additionalProperties":false,"required":["impossible"],"properties":{"impossible":{"type":"string"}}}""")
        val invalid = ProcessBuilder("python3", directory.resolve("runners/python/runner.py").toString())
            .redirectErrorStream(true).start()
        val invalidOutput = invalid.inputStream.bufferedReader().readText()
        assertThat(invalid.waitFor()).isNotZero()
        assertThat(invalidOutput).contains("\"status\": \"FAILED\"", "final output schema")
    }

    @Test
    fun `CSV package uses deterministic compare without an AI node and returns schema valid changes`() {
        assumeTrue(runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }.getOrDefault(false))
        val mapper = jacksonObjectMapper()
        val runs = mock<MetaAgentRunRepository>().also { whenever(it.save(any())).thenAnswer { call -> call.arguments[0] } }
        val bundle = StructuredMetaAgentPipeline(
            DeterministicMockMetaAgentModel(mapper), mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>(),
        ).generateDesign(
            PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
            "두 CSV 파일을 ID 기준으로 비교해서 추가 수정 삭제 행을 표로 만들어줘",
            StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
        )
        val files = HarnessPackageRenderer(mapper).render(bundle).toMutableMap()
        files["examples/sample-input.json"] = mapper.writeValueAsString(mapOf(
            "csvA" to "id,name\n1,old\n2,remove\n",
            "csvB" to "id,name\n1,new\n3,add\n",
        ))
        files.forEach { (path, content) ->
            val target = directory.resolve(path)
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }

        assertThat(files.getValue("workflow.json")).contains("data.csv.compare").doesNotContain("ai.generate")
        val process = ProcessBuilder("python3", directory.resolve("runners/python/runner.py").toString())
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()

        assertThat(process.waitFor()).withFailMessage(output).isZero()
        assertThat(output).contains("\"status\": \"SUCCEEDED\"", "\"changeType\": \"ADDED\"", "\"changeType\": \"REMOVED\"", "\"changeType\": \"MODIFIED\"")
            .doesNotContain("instruction")
    }
}
