package com.agentvillage.execution

import com.agentvillage.IntegrationTestSupport
import com.agentvillage.execution.infrastructure.LocalRunnerConnectionRepository
import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.RegisterUserCommand
import com.agentvillage.identity.infrastructure.AuthenticatedUser
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
class LocalRunnerIntegrationTest : IntegrationTestSupport() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var identities: IdentityService
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var connections: LocalRunnerConnectionRepository

    @Test
    fun `subscription runner token is hashed and can claim and complete only its owners job`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val account = identities.register(RegisterUserCommand("runner-$suffix@example.com", "password123", "runner_$suffix", "Runner 검증"))
        val principal = AuthenticatedUser(account.id, account.email, "unused", true)
        fun postUser(path: String, body: String, vararg headers: Pair<String,String>) = mvc.perform(post(path).with(user(principal)).with(csrf()).contentType(MediaType.APPLICATION_JSON).apply { headers.forEach { header(it.first,it.second) } }.content(body))
        val agentId = mapper.readTree(postUser("/api/agents", """{"name":"Writer","role":"작가","characterKey":"writer","script":"글 작성","guide":"결과만 반환","modelProvider":"OPENAI","modelName":"gpt-5.6-sol","visibility":"PRIVATE"}""").andExpect(status().isCreated).andReturn().response.contentAsString)["id"].asText()
        val harnessId = mapper.readTree(postUser("/api/harnesses", """{"name":"Pro 구독 글쓰기"}""").andExpect(status().isCreated).andReturn().response.contentAsString)["id"].asText()
        postUser("/api/harnesses/$harnessId/connect", """{"agentIds":["$agentId"]}""").andExpect(status().isOk)
        postUser("/api/harnesses/$harnessId/validate", "{}").andExpect(status().isOk)
            .andExpect(jsonPath("$.valid").value(true))
        postUser("/api/harnesses/$harnessId/publish", "{}").andExpect(status().isOk)
            .andExpect(jsonPath("$.snapshotJson.validation.outcome").value("VALIDATED"))

        val pairBody = postUser("/api/local-runners/pair", """{"provider":"CODEX","deviceName":"테스트 Mac"}""").andExpect(status().isOk).andReturn().response.contentAsString
        val pair = mapper.readTree(pairBody); val token = pair["pairingToken"].asText(); val connectionId = UUID.fromString(pair["connection"]["id"].asText())
        assertThat(token).hasSizeGreaterThan(32)
        assertThat(connections.findById(connectionId).orElseThrow().tokenHash).doesNotContain(token)
        mvc.perform(post("/api/runner/heartbeat").header("X-Runner-Token", token).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.status").value("ACTIVE"))

        val executionId = mapper.readTree(postUser("/api/harnesses/$harnessId/executions", """{"input":{"topic":"구독 실행"},"executionMode":"LOCAL_CLI"}""", "Idempotency-Key" to UUID.randomUUID().toString()).andExpect(status().isOk).andReturn().response.contentAsString)["id"].asText()
        mvc.perform(post("/api/runner/jobs/claim").header("X-Runner-Token", token).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.executionId").value(executionId)).andExpect(jsonPath("$.agents[0].name").value("Writer"))
        mvc.perform(post("/api/runner/jobs/$executionId/events").header("X-Runner-Token", token).contentType(MediaType.APPLICATION_JSON).content("""{"eventType":"STEP_STARTED","agentId":"$agentId","stepKey":"step-1"}"""))
            .andExpect(status().isNoContent)
        mvc.perform(post("/api/runner/jobs/$executionId/complete").header("X-Runner-Token", token).contentType(MediaType.APPLICATION_JSON).content("""{"output":{"result":"구독 계정 결과","step-1":{"result":"구독 계정 결과"}}}"""))
            .andExpect(status().isNoContent)
        mvc.perform(get("/api/executions/$executionId").with(user(principal))).andExpect(status().isOk)
            .andExpect(jsonPath("$.execution.status").value("SUCCEEDED")).andExpect(jsonPath("$.execution.executionMode").value("LOCAL_CLI"))
        mvc.perform(post("/api/runner/heartbeat").header("X-Runner-Token", "x".repeat(43)).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest)
    }
}
