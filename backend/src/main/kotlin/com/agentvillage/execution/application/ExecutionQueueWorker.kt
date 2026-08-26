package com.agentvillage.execution.application

import com.agentvillage.execution.domain.*
import com.agentvillage.execution.infrastructure.ExecutionRepository
import com.agentvillage.execution.infrastructure.ExecutionStepRepository
import com.agentvillage.harness.domain.HarnessStepType
import com.agentvillage.llmcredential.application.CredentialDirectory
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private class ExecutionExpiredException(message: String) : RuntimeException(message)

@Service
class ExecutionProcessor(
    private val executions: ExecutionRepository, private val executionSteps: ExecutionStepRepository,
    private val snapshots: ExecutionSnapshotReader,
    private val credentials: CredentialDirectory, private val gateways: AiModelGatewayRegistry,
    private val service: ExecutionService,
    private val metrics: ExecutionMetrics,
) {
    private val llmSemaphore = Semaphore(50)
    private val externalSemaphore = Semaphore(100)
    private val downloadSemaphore = Semaphore(10)

    suspend fun process(id: UUID) {
        // activeUsers limits fairness inside one application instance, but production can have
        // multiple workers (and integration tests can have multiple Spring contexts). Claim the
        // queued row atomically so only one worker may execute a workflow version.
        if (executions.claimQueued(id, Instant.now()) != 1) return
        val execution = executions.findById(id).orElse(null) ?: return
        metrics.started()
        service.record(id, "EXECUTION_STARTED", null, mapOf("status" to "RUNNING"))
        val plan = snapshots.read(execution.executionSnapshotJson)
        val priorSteps = executionSteps.findAllByExecutionIdOrderByStartedAtAsc(id)
        val completedKeys = priorSteps.filter { it.status == StepStatus.SUCCEEDED }.map { it.stepKey }.toSet()
        var current: Map<String, Any> = priorSteps.lastOrNull { it.status == StepStatus.SUCCEEDED }?.outputJson
            ?: execution.inputJson.filterKeys { it != "_stubMode" }
        val stubMode = execution.inputJson["_stubMode"] == true
        try {
            for (step in plan.steps) {
                if (step.key in completedKeys) continue
                if (execution.status == ExecutionStatus.CANCELLED) return
                if (execution.timeoutAt?.isBefore(Instant.now()) == true) throw ExecutionExpiredException("전체 실행 시간이 초과되었습니다.")
                execution.currentStepKey = step.key; execution.heartbeatAt = Instant.now(); executions.save(execution)
                val agent = step.agentKey?.let(plan.agents::get)
                val executionStep = executionSteps.save(ExecutionStep(executionId = id, harnessStepId = null,
                    stepKey = step.key, stepType = step.type.name, inputJson = current, startedAt = Instant.now(), status = StepStatus.RUNNING,
                    provider = agent?.provider?.name, model = agent?.model))
                service.record(id, "STEP_STARTED", agent?.sourceAgentId, mapOf("stepKey" to step.key, "type" to step.type.name))
                if (step.type == HarnessStepType.APPROVAL) {
                    execution.status = ExecutionStatus.WAITING_APPROVAL
                    executionStep.status = StepStatus.WAITING_APPROVAL
                    executionStep.outputJson = current
                    executions.save(execution); executionSteps.save(executionStep)
                    service.record(id, "WAITING_APPROVAL", agent?.sourceAgentId, mapOf("stepKey" to step.key)); return
                }
                val output = executeWithRetry(step.maxRetries, executionStep, agent?.sourceAgentId) {
                    withTimeout(step.timeoutSeconds * 1000L) { when (step.type) {
                        HarnessStepType.LLM -> llmSemaphore.withPermit { executeLlm(execution, agent!!, current, stubMode, executionStep) }
                        HarnessStepType.EXTERNAL_API -> externalSemaphore.withPermit { service.record(id, "TOOL_CALLED", agent?.sourceAgentId, mapOf("stepKey" to step.key, "tool" to "EXTERNAL_API")); mapOf("result" to "external-api-stub", "input" to current) }
                        HarnessStepType.DOWNLOAD -> downloadSemaphore.withPermit { service.record(id, "TOOL_CALLED", agent?.sourceAgentId, mapOf("stepKey" to step.key, "tool" to "DOWNLOAD")); mapOf("result" to current) }
                        HarnessStepType.APPROVAL -> error("Approval step must be handled before execution")
                    }}
                }
                current = current + mapOf(step.key to output) + output
                executionStep.outputJson = current; executionStep.status = StepStatus.SUCCEEDED; executionStep.finishedAt = Instant.now()
                executionSteps.save(executionStep)
                service.record(id, "STEP_OUTPUT_CREATED", agent?.sourceAgentId, mapOf("stepKey" to step.key))
                service.record(id, "STEP_COMPLETED", agent?.sourceAgentId, mapOf("stepKey" to step.key))
                if (step.requiresApproval) { execution.status = ExecutionStatus.WAITING_APPROVAL; executions.save(execution); service.record(id, "WAITING_APPROVAL", agent?.sourceAgentId, mapOf("stepKey" to step.key)); return }
            }
            execution.outputJson = current; execution.status = ExecutionStatus.SUCCEEDED; execution.finishedAt = Instant.now()
            executions.save(execution)
            service.record(id, "EXECUTION_COMPLETED", null, mapOf("status" to "SUCCEEDED"))
            metrics.completed(execution.startedAt, "SUCCEEDED")
        } catch (e: Exception) {
            execution.status = if (e is TimeoutCancellationException || e is ExecutionExpiredException) ExecutionStatus.TIMEOUT else ExecutionStatus.FAILED
            execution.errorCode = if (execution.status == ExecutionStatus.TIMEOUT) "EXECUTION_TIMEOUT" else "STEP_EXECUTION_FAILED"
            execution.errorMessage = e.message?.take(1000); execution.finishedAt = Instant.now()
            executions.save(execution)
            service.record(id, "EXECUTION_FAILED", null, mapOf("errorCode" to execution.errorCode!!))
            metrics.completed(execution.startedAt, execution.status.name)
        }
    }

    private suspend fun executeWithRetry(maxRetries: Int, step: ExecutionStep, agentId: UUID?, block: suspend () -> Map<String, Any>): Map<String, Any> {
        var last: Throwable? = null
        repeat(maxRetries + 1) { index ->
            step.attempt = index + 1
            try { return block() } catch (error: Throwable) {
                last = error
                step.errorCode = if (error is TimeoutCancellationException) "STEP_TIMEOUT" else "STEP_EXECUTION_FAILED"
                step.errorMessage = error.message?.take(1000)
                executionSteps.save(step)
                service.record(step.executionId, "STEP_FAILED", agentId, mapOf("stepKey" to step.stepKey, "attempt" to step.attempt, "willRetry" to (index < maxRetries)))
            }
        }
        throw requireNotNull(last)
    }

    private fun executeLlm(execution: Execution, agent: SnapshotAgentConfig,
                           input: Map<String, Any>, stubMode: Boolean, step: ExecutionStep): Map<String, Any> {
        service.record(execution.id, "MODEL_REQUEST_SENT", agent.sourceAgentId, mapOf("provider" to agent.provider.name, "model" to agent.model))
        val response = if (stubMode) AiModelResponse(stubContent(agent, input), TokenUsage(1, 1), "stub-request") else {
            val credentialId = execution.credentialBindingsJson[agent.key]?.let(UUID::fromString)
                ?: throw IllegalStateException("${agent.name} 구성원의 실행 자격증명 연결이 없습니다.")
            credentials.withDecrypted(credentialId, execution.ownerId, agent.provider) { secret, options ->
                DecryptedCredential(agent.provider, secret, options).use { credential -> gateways.get(agent.provider).execute(credential,
                    AiModelRequest(agent.model, agent.systemPrompt, input.toString(), agent.temperature, agent.maxOutputTokens, agent.timeoutSeconds, agent.providerOptions)) }
            }
        }
        step.inputTokens = response.tokenUsage.inputTokens; step.outputTokens = response.tokenUsage.outputTokens
        step.estimatedCost = BigDecimal.ZERO; step.providerRequestId = response.providerRequestId
        executionSteps.save(step)
        return mapOf("result" to response.content, "stub" to stubMode, "agent" to agent.name)
    }

    private fun stubContent(agent: SnapshotAgentConfig, input: Map<String, Any>): String {
        val topic = input["topic"]?.toString() ?: "Agentown 글쓰기 하네스 검증"
        val identity = "${agent.name} ${agent.role}".lowercase()
        return when {
            "planner" in identity || "기획" in identity || "편집장" in identity -> """
                # Topic Selection (STUB)
                - selected_topic: $topic
                - category: Harness Engineering
                - content_type: build_log_operations
                - verification_mode: controlled_stub
                - external_write: false
            """.trimIndent()
            "research" in identity || "리서치" in identity || "조사" in identity -> """
                # Research (STUB)
                - topic: $topic
                - verification_mode: not_directly_tested
                - evidence: Agentown 내부 Stub 실행 이벤트와 테스트 결과만 사용
                - limitations: 웹 검색과 외부 출처 확인은 수행하지 않았으므로 실제 발행용 근거로 사용할 수 없음
                - external_write: false
            """.trimIndent()
            "writer" in identity || "작가" in identity || "작성" in identity -> """
                ---
                title: "$topic"
                category: "Harness Engineering"
                status: "stub-draft"
                ---

                # $topic

                ## 20초 요약

                이 문서는 Agentown의 선언형 글쓰기 하네스가 단계별 결과를 전달하는지 확인하기 위한 Stub 초안이다. 외부 조사나 실제 발행은 수행하지 않았다.

                ## 검증 범위

                Queue 실행, 에이전트 순서, Reviewer 승인 대기, 승인 뒤 Publisher 재개만 검증한다.

                ## 한계

                실제 LLM과 웹 리서치를 사용하지 않았으므로 콘텐츠 품질과 사실 정확성은 검증 대상이 아니다.
            """.trimIndent()
            "review" in identity || "검수" in identity -> {
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.toString().toByteArray())
                    .joinToString("") { "%02x".format(it) }
                """
                    # Review Record (STUB)
                    - status: APPROVED_STUB
                    - content_sha256: $digest
                    - approval_scope: orchestration_test_only
                    - publish_allowed: false
                    - reason: 외부 근거가 없는 Stub 초안이므로 실제 WordPress 발행은 금지
                """.trimIndent()
            }
            "publish" in identity || "발행" in identity -> """
                # Publish Result (STUB)
                - status: DRAFT_READY
                - validation: reviewer_approval_received
                - wordpress_request_sent: false
                - external_write: false
                - result: Agentown 내부 실행 경로 검증 완료. 실제 발행에는 BYOK와 새 Reviewer 승인이 필요함.
            """.trimIndent()
            else -> "[STUB] ${agent.name} 단계 완료: 외부 호출 및 외부 쓰기 없음"
        }
    }
}

@Component
class ExecutionQueueWorker(private val executions: ExecutionRepository, private val processor: ExecutionProcessor) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val errors = CoroutineExceptionHandler { _, error -> logger.error("Execution queue worker failed", error) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errors)
    private val total = Semaphore(20)
    private val activeUsers = ConcurrentHashMap.newKeySet<UUID>()

    @PreDestroy
    fun shutdown() { scope.cancel() }

    @PostConstruct
    @Transactional
    fun recover() {
        executions.findAllByStatusAndHeartbeatAtBefore(ExecutionStatus.RUNNING, Instant.now().minus(2, ChronoUnit.MINUTES)).forEach { it.status = ExecutionStatus.QUEUED }
    }

    @Scheduled(fixedDelayString = "\${execution.poll-interval-ms:500}")
    fun poll() {
        executions.findTop20ByStatusAndExecutionModeInOrderByQueuedAt(ExecutionStatus.QUEUED, listOf(ExecutionMode.CLOUD_API, ExecutionMode.STUB)).forEach { execution ->
            if (activeUsers.add(execution.ownerId)) scope.launch { total.withPermit {
                try { processor.process(execution.id) } finally {
                    activeUsers.remove(execution.ownerId)
                    // Close the approval race: if approval re-queues while the previous coroutine is
                    // still unwinding, dispatch it immediately instead of waiting for another tick.
                    poll()
                }
            } }
        }
    }
}
