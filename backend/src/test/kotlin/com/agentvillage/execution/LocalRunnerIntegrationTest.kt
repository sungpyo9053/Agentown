package com.agentvillage.execution

import com.agentvillage.IntegrationTestSupport
import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.execution.application.ExecutionService
import com.agentvillage.execution.application.LocalRunnerService
import com.agentvillage.execution.domain.ExecutionStatus
import com.agentvillage.execution.domain.StepStatus
import com.agentvillage.execution.infrastructure.ExecutionEventRepository
import com.agentvillage.execution.infrastructure.ExecutionRepository
import com.agentvillage.execution.infrastructure.ExecutionStepRepository
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID

@AutoConfigureMockMvc
class LocalRunnerIntegrationTest : IntegrationTestSupport() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var identities: IdentityService
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var connections: LocalRunnerConnectionRepository
    @Autowired lateinit var executions: ExecutionRepository
    @Autowired lateinit var executionSteps: ExecutionStepRepository
    @Autowired lateinit var events: ExecutionEventRepository
    @Autowired lateinit var executionService: ExecutionService
    @Autowired lateinit var localRunnerService: LocalRunnerService
    @Autowired lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `subscription runner token is hashed and can claim and complete only its owners job`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val account = identities.register(RegisterUserCommand("runner-$suffix@example.com", "password123", "runner_$suffix", "Runner 검증"))
        val principal = AuthenticatedUser(account.id, account.email, "unused", true)
        fun postUser(path: String, body: String, vararg headers: Pair<String,String>) = mvc.perform(post(path).with(user(principal)).with(csrf()).contentType(MediaType.APPLICATION_JSON).apply { headers.forEach { header(it.first,it.second) } }.content(body))
        val agentId = mapper.readTree(postUser("/api/agents", """{"name":"Writer","role":"작가","characterKey":"writer","script":"글 작성","guide":"결과만 반환","modelProvider":"OPENAI","modelName":"gpt-5.6-sol","visibility":"PRIVATE"}""").andExpect(status().isCreated).andReturn().response.contentAsString)["id"].asText()
        val harnessId = mapper.readTree(postUser("/api/harnesses", """{"name":"Pro 구독 글쓰기"}""").andExpect(status().isCreated).andReturn().response.contentAsString)["id"].asText()
        postUser("/api/harnesses/$harnessId/connect", """{"agentIds":["$agentId"],"approvalAfterLast":true}""").andExpect(status().isOk)
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
        mvc.perform(post("/api/runner/jobs/$executionId/events").header("X-Runner-Token", token).contentType(MediaType.APPLICATION_JSON).content("""{"eventType":"STEP_STARTED","agentId":"${UUID.randomUUID()}","stepKey":"step-1"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("RUNNER_AGENT_MISMATCH"))
        mvc.perform(post("/api/runner/jobs/$executionId/events").header("X-Runner-Token", token).contentType(MediaType.APPLICATION_JSON).content("""{"eventType":"STEP_STARTED","agentId":"$agentId","stepKey":"step-1"}"""))
            .andExpect(status().isNoContent)
        mvc.perform(post("/api/runner/jobs/$executionId/events").header("X-Runner-Token", token).contentType(MediaType.APPLICATION_JSON).content("""{"eventType":"STEP_OUTPUT_CREATED","agentId":"$agentId","stepKey":"step-1","output":{"result":"구독 계정 결과","agent":"Writer"}}"""))
            .andExpect(status().isNoContent)
        mvc.perform(post("/api/runner/jobs/$executionId/events").header("X-Runner-Token", token).contentType(MediaType.APPLICATION_JSON).content("""{"eventType":"STEP_COMPLETED","agentId":"$agentId","stepKey":"step-1"}"""))
            .andExpect(status().isNoContent)
        mvc.perform(post("/api/runner/jobs/$executionId/complete").header("X-Runner-Token", token).contentType(MediaType.APPLICATION_JSON).content("""{"output":{"result":"구독 계정 결과","step-1":{"result":"구독 계정 결과"}}}"""))
            .andExpect(status().isNoContent)
        mvc.perform(get("/api/executions/$executionId").with(user(principal))).andExpect(status().isOk)
            .andExpect(jsonPath("$.execution.status").value("WAITING_APPROVAL"))
            .andExpect(jsonPath("$.execution.executionMode").value("LOCAL_CLI"))
            .andExpect(jsonPath("$.steps[0].status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.steps[0].outputJson.result").value("구독 계정 결과"))
        postUser("/api/executions/$executionId/approve", "{}").andExpect(status().isOk)
        mvc.perform(get("/api/executions/$executionId").with(user(principal))).andExpect(status().isOk)
            .andExpect(jsonPath("$.execution.status").value("SUCCEEDED"))
        mvc.perform(post("/api/runner/jobs/$executionId/fail").header("X-Runner-Token", token).contentType(MediaType.APPLICATION_JSON).content("""{"code":"LATE_FAILURE","message":"late"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("RUNNER_JOB_NOT_OWNED"))
        mvc.perform(get("/api/executions/$executionId").with(user(principal))).andExpect(status().isOk)
            .andExpect(jsonPath("$.execution.status").value("SUCCEEDED"))
        mvc.perform(post("/api/runner/heartbeat").header("X-Runner-Token", "x".repeat(43)).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `late runner callbacks after cancellation preserve all durable evidence`() {
        val job = claimedJob("late-callback")
        runnerEvent(job, "STEP_STARTED").andExpect(status().isNoContent)
        runnerEvent(job, "STEP_OUTPUT_CREATED", ""","output":{"result":"preserved step output"}""")
            .andExpect(status().isNoContent)
        executions.save(executions.findById(job.executionId).orElseThrow().also {
            it.outputJson = mapOf("partial" to "preserved execution output")
        })

        mvc.perform(post("/api/executions/${job.executionId}/cancel").with(user(job.principal)).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))

        val cancelled = executions.findById(job.executionId).orElseThrow()
        val sealedStep = executionSteps.findByExecutionIdAndStepKey(job.executionId, "step-1")!!
        val eventIds = events.findAllByExecutionIdOrderBySequenceNo(job.executionId).map { it.id }
        val finishedAt = cancelled.finishedAt

        runnerEvent(job, "STEP_COMPLETED")
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("RUNNER_JOB_NOT_OWNED"))
        mvc.perform(post("/api/runner/jobs/${job.executionId}/complete").header("X-Runner-Token", job.token).contentType(MediaType.APPLICATION_JSON).content("""{"output":{"result":"late completion"}}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("RUNNER_JOB_NOT_OWNED"))
        mvc.perform(post("/api/runner/jobs/${job.executionId}/fail").header("X-Runner-Token", job.token).contentType(MediaType.APPLICATION_JSON).content("""{"code":"LATE_FAILURE","message":"late failure"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("RUNNER_JOB_NOT_OWNED"))

        val unchanged = executions.findById(job.executionId).orElseThrow()
        assertThat(unchanged.status).isEqualTo(ExecutionStatus.CANCELLED)
        assertThat(unchanged.outputJson).isEqualTo(mapOf("partial" to "preserved execution output"))
        assertThat(unchanged.finishedAt).isEqualTo(finishedAt)
        assertThat(unchanged.errorCode).isNull()
        assertThat(unchanged.errorMessage).isNull()
        val unchangedStep = executionSteps.findById(sealedStep.id).orElseThrow()
        assertThat(unchangedStep.status).isEqualTo(StepStatus.CANCELLED)
        assertThat(unchangedStep.outputJson).isEqualTo(mapOf("result" to "preserved step output"))
        assertThat(unchangedStep.finishedAt).isEqualTo(finishedAt)
        assertThat(events.findAllByExecutionIdOrderBySequenceNo(job.executionId).map { it.id })
            .containsExactlyElementsOf(eventIds)
    }

    @Test
    fun `cancellation holding the execution lock wins over a concurrent completion callback`() {
        val job = claimedJob("cancel-race")
        runnerEvent(job, "STEP_STARTED").andExpect(status().isNoContent)
        val cancellationLocked = CountDownLatch(1)
        val callbackReady = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val cancellation = pool.submit {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    executions.findByIdForUpdate(job.executionId) ?: error("execution missing")
                    cancellationLocked.countDown()
                    check(callbackReady.await(10, TimeUnit.SECONDS)) { "completion callback did not become ready" }
                    executionService.cancel(job.executionId, job.principal.userId)
                }
            }
            check(cancellationLocked.await(10, TimeUnit.SECONDS)) { "cancellation did not acquire the execution lock" }
            val completion = pool.submit {
                callbackReady.countDown()
                localRunnerService.complete(job.token, job.executionId, mapOf("result" to "racing completion"))
            }

            cancellation.get(10, TimeUnit.SECONDS)
            val callbackFailure = runCatching { completion.get(10, TimeUnit.SECONDS) }.exceptionOrNull()
            assertThat(callbackFailure).isNotNull()
            assertThat(callbackFailure!!.cause).isInstanceOf(BadRequestException::class.java)
            assertThat((callbackFailure.cause as BadRequestException).code).isEqualTo("RUNNER_JOB_NOT_OWNED")
        } finally {
            pool.shutdownNow()
        }

        val terminal = executions.findById(job.executionId).orElseThrow()
        assertThat(terminal.status).isEqualTo(ExecutionStatus.CANCELLED)
        assertThat(terminal.outputJson).isNull()
        assertThat(executionSteps.findByExecutionIdAndStepKey(job.executionId, "step-1")!!.status)
            .isEqualTo(StepStatus.CANCELLED)
        assertThat(events.findAllByExecutionIdOrderBySequenceNo(job.executionId).map { it.eventType }
            .filter { it == "EXECUTION_COMPLETED" || it == "EXECUTION_FAILED" })
            .containsExactly("EXECUTION_FAILED")
    }

    private fun claimedJob(label: String): ClaimedJob {
        val suffix = UUID.randomUUID().toString().take(8)
        val account = identities.register(RegisterUserCommand("runner-$label-$suffix@example.com", "password123", "runner_$suffix", "Runner 검증"))
        val principal = AuthenticatedUser(account.id, account.email, "unused", true)
        val agentId = UUID.fromString(mapper.readTree(postUser(principal, "/api/agents", """{"name":"Writer","role":"작가","characterKey":"writer","script":"글 작성","guide":"결과만 반환","modelProvider":"OPENAI","modelName":"gpt-5.6-sol","visibility":"PRIVATE"}""").andExpect(status().isCreated).andReturn().response.contentAsString)["id"].asText())
        val harnessId = mapper.readTree(postUser(principal, "/api/harnesses", """{"name":"Runner callback $label"}""").andExpect(status().isCreated).andReturn().response.contentAsString)["id"].asText()
        postUser(principal, "/api/harnesses/$harnessId/connect", """{"agentIds":["$agentId"],"approvalAfterLast":true}""").andExpect(status().isOk)
        postUser(principal, "/api/harnesses/$harnessId/validate", "{}").andExpect(status().isOk)
            .andExpect(jsonPath("$.valid").value(true))
        postUser(principal, "/api/harnesses/$harnessId/publish", "{}").andExpect(status().isOk)
        val pair = mapper.readTree(postUser(principal, "/api/local-runners/pair", """{"provider":"CODEX","deviceName":"$label Mac"}""").andExpect(status().isOk).andReturn().response.contentAsString)
        val token = pair["pairingToken"].asText()
        mvc.perform(post("/api/runner/heartbeat").header("X-Runner-Token", token).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk)
        val executionId = UUID.fromString(mapper.readTree(postUser(principal, "/api/harnesses/$harnessId/executions", """{"input":{"topic":"$label"},"executionMode":"LOCAL_CLI"}""", "Idempotency-Key" to UUID.randomUUID().toString()).andExpect(status().isOk).andReturn().response.contentAsString)["id"].asText())
        mvc.perform(post("/api/runner/jobs/claim").header("X-Runner-Token", token).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.executionId").value(executionId.toString()))
        return ClaimedJob(token, executionId, agentId, principal)
    }

    private fun runnerEvent(job: ClaimedJob, eventType: String, extraJson: String = "") = mvc.perform(
        post("/api/runner/jobs/${job.executionId}/events")
            .header("X-Runner-Token", job.token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"eventType":"$eventType","agentId":"${job.agentId}","stepKey":"step-1"$extraJson}"""),
    )

    private fun postUser(principal: AuthenticatedUser, path: String, body: String, vararg headers: Pair<String, String>) = mvc.perform(
        post(path).with(user(principal)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .apply { headers.forEach { header(it.first, it.second) } }.content(body),
    )

    private data class ClaimedJob(
        val token: String,
        val executionId: UUID,
        val agentId: UUID,
        val principal: AuthenticatedUser,
    )
}
