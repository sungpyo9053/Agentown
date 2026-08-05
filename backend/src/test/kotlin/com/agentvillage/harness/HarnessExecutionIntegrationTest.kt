package com.agentvillage.harness

import com.agentvillage.IntegrationTestSupport
import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.RegisterUserCommand
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import com.agentvillage.execution.application.ExecutionService
import com.agentvillage.execution.application.ExecutionQueueWorker
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.util.UUID

@AutoConfigureMockMvc
class HarnessExecutionIntegrationTest : IntegrationTestSupport() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var identities: IdentityService
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var executionService: ExecutionService
    @Autowired lateinit var queueWorker: ExecutionQueueWorker

    @Test
    fun `definition harness publish clone zip and stub execution work end to end`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val user = identities.register(RegisterUserCommand("flow-$suffix@example.com", "password123", "flow_$suffix", "흐름 검증"))
        val principal = AuthenticatedUser(user.id, user.email, "unused", true)
        fun postJson(path: String, json: String, vararg headers: Pair<String, String>) = mvc.perform(
            post(path).with(user(principal)).with(csrf()).contentType(MediaType.APPLICATION_JSON).apply {
                headers.forEach { header(it.first, it.second) }
            }.content(json),
        )
        val agentJson = postJson("/api/agents", """{
          "name":"Writer","role":"기술 작가","characterKey":"writer","script":"입력을 초안으로 작성한다",
          "guide":"사실을 검증한다","modelProvider":"OPENAI","modelName":"gpt-4o-mini","visibility":"PRIVATE"
        }""").andExpect(status().isCreated).andReturn().response.contentAsString
        val agentId = mapper.readTree(agentJson)["id"].asText()

        postJson("/api/agents/$agentId/generate-definition", """{
          "taskDescription":"검증된 기술 초안을 작성한다","desiredOutput":"Markdown 문서","prohibitions":"근거 없는 수치 금지"
        }""").andExpect(status().isOk)
            .andExpect(jsonPath("$.agentMarkdown").value(org.hamcrest.Matchers.containsString("## 완료 조건")))
            .andExpect(jsonPath("$.guideMarkdown").value(org.hamcrest.Matchers.containsString("근거 없는 수치 금지")))
            .andExpect(jsonPath("$.inputSchema.type").value("object"))

        val harnessJson = postJson("/api/harnesses", """{"name":"기술 글쓰기 팀","description":"통합 흐름"}""")
            .andExpect(status().isCreated).andReturn().response.contentAsString
        val harnessId = mapper.readTree(harnessJson)["id"].asText()
        postJson("/api/harnesses/$harnessId/connect", """{"agentIds":["$agentId"],"approvalAfterLast":false}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.steps.length()").value(1))
        postJson("/api/harnesses/$harnessId/validate", "{}").andExpect(status().isOk).andExpect(jsonPath("$.valid").value(true))
        val version = postJson("/api/harnesses/$harnessId/publish", "{}").andExpect(status().isOk)
            .andExpect(jsonPath("$.snapshotJson.agents[0].credentialId").doesNotExist()).andReturn().response.contentAsString
        assertThat(version).doesNotContain("encryptedSecret", "apiKey")
        postJson("/api/harnesses/$harnessId/clone", "{}").andExpect(status().isOk)
        mvc.perform(get("/api/harnesses/$harnessId/download").with(user(principal)))
            .andExpect(status().isOk).andExpect(header().string("Content-Type", "application/zip"))

        val executionJson = postJson("/api/harnesses/$harnessId/executions", """{"input":{"topic":"Kotlin"},"stubMode":true}""",
            "Idempotency-Key" to UUID.randomUUID().toString()).andExpect(status().isOk).andReturn().response.contentAsString
        val executionId = mapper.readTree(executionJson)["id"].asText()
        queueWorker.poll()
        var body = ""
        repeat(30) {
            Thread.sleep(150)
            body = mvc.perform(get("/api/executions/$executionId").with(user(principal))).andReturn().response.contentAsString
            if (mapper.readTree(body)["execution"]["status"].asText() == "SUCCEEDED") return@repeat
        }
        assertThat(mapper.readTree(body)["execution"]["status"].asText()).isEqualTo("SUCCEEDED")
        val events = executionService.history(UUID.fromString(executionId), user.id).map { it.eventType }
        assertThat(events).containsSubsequence("EXECUTION_QUEUED", "EXECUTION_STARTED", "STEP_STARTED", "MODEL_REQUEST_SENT", "STEP_COMPLETED", "EXECUTION_COMPLETED")

        postJson("/api/harnesses/$harnessId/connect", """{"agentIds":["$agentId"],"approvalAfterLast":true}""")
            .andExpect(status().isOk)
        val approvalExecution = postJson("/api/harnesses/$harnessId/executions", """{"input":{"topic":"승인"},"stubMode":true}""",
            "Idempotency-Key" to UUID.randomUUID().toString()).andExpect(status().isOk).andReturn().response.contentAsString
        val approvalId = mapper.readTree(approvalExecution)["id"].asText()
        Thread.sleep(200)
        queueWorker.poll()
        var approvalBody = ""
        repeat(30) {
            Thread.sleep(100)
            approvalBody = mvc.perform(get("/api/executions/$approvalId").with(user(principal))).andReturn().response.contentAsString
            if (mapper.readTree(approvalBody)["execution"]["status"].asText() == "WAITING_APPROVAL") return@repeat
        }
        assertThat(mapper.readTree(approvalBody)["execution"]["status"].asText()).isEqualTo("WAITING_APPROVAL")
        postJson("/api/executions/$approvalId/approve", "{}").andExpect(status().isOk)
        Thread.sleep(200)
        queueWorker.poll()
        repeat(30) {
            Thread.sleep(100)
            approvalBody = mvc.perform(get("/api/executions/$approvalId").with(user(principal))).andReturn().response.contentAsString
            if (mapper.readTree(approvalBody)["execution"]["status"].asText() == "SUCCEEDED") return@repeat
        }
        assertThat(mapper.readTree(approvalBody)["execution"]["status"].asText()).isEqualTo("SUCCEEDED")
    }
}
