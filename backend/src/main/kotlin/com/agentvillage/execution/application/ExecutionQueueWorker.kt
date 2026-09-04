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
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private class ExecutionNoLongerRunningException : RuntimeException()
private data class StepExecutionResult(
    val output: Map<String, Any>,
    val metadata: StepCompletionMetadata = StepCompletionMetadata(),
)

@Service
class ExecutionProcessor(
    private val executions: ExecutionRepository, private val executionSteps: ExecutionStepRepository,
    private val snapshots: ExecutionSnapshotReader,
    private val credentials: CredentialDirectory, private val gateways: AiModelGatewayRegistry,
    private val coordinator: ExecutionStateCoordinator,
    private val metrics: ExecutionMetrics,
    @Value("\${execution.heartbeat-interval-ms:30000}") private val heartbeatIntervalMs: Long,
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
        if (!coordinator.recordWhileRunning(id, "EXECUTION_STARTED", null, mapOf("status" to "RUNNING"))) return
        val plan = snapshots.read(execution.executionSnapshotJson)
        val priorSteps = executionSteps.findAllByExecutionIdOrderByStartedAtAsc(id)
        val completedKeys = priorSteps.filter { it.status == StepStatus.SUCCEEDED }.map { it.stepKey }.toSet()
        var current: Map<String, Any> = priorSteps.lastOrNull { it.status == StepStatus.SUCCEEDED }?.outputJson
            ?: execution.inputJson.filterKeys { it != "_stubMode" }
        val stubMode = execution.inputJson["_stubMode"] == true
        try {
            for (step in plan.steps) {
                if (step.key in completedKeys) continue
                val agent = step.agentKey?.let(plan.agents::get)
                val executionStep = coordinator.beginStep(
                    id, step.key, step.type.name, current, agent?.provider?.name, agent?.model, agent?.sourceAgentId,
                ) ?: return
                if (step.type == HarnessStepType.APPROVAL) {
                    coordinator.waitForApproval(id, executionStep.id, current, agent?.sourceAgentId)
                    return
                }
                val result = withHeartbeat(id) { executeWithRetry(step.maxRetries, executionStep, agent?.sourceAgentId) {
                    withTimeout(step.timeoutSeconds * 1000L) { when (step.type) {
                        HarnessStepType.LLM -> llmSemaphore.withPermit { executeLlm(execution, agent!!, current, stubMode) }
                        HarnessStepType.EXTERNAL_API -> externalSemaphore.withPermit {
                            if (!coordinator.recordWhileRunning(id, "TOOL_CALLED", agent?.sourceAgentId, mapOf("stepKey" to step.key, "tool" to "EXTERNAL_API"))) throw ExecutionNoLongerRunningException()
                            StepExecutionResult(mapOf("result" to "external-api-stub", "input" to current))
                        }
                        HarnessStepType.DOWNLOAD -> downloadSemaphore.withPermit {
                            if (!coordinator.recordWhileRunning(id, "TOOL_CALLED", agent?.sourceAgentId, mapOf("stepKey" to step.key, "tool" to "DOWNLOAD"))) throw ExecutionNoLongerRunningException()
                            StepExecutionResult(mapOf("result" to current))
                        }
                        HarnessStepType.APPROVAL -> error("Approval step must be handled before execution")
                    }}
                } }
                current = current + mapOf(step.key to result.output) + result.output
                if (!coordinator.completeStep(id, executionStep.id, current, result.metadata, agent?.sourceAgentId, step.requiresApproval)) return
                if (step.requiresApproval) return
            }
            coordinator.completeExecution(id, current)
        } catch (e: Exception) {
            if (e !is ExecutionNoLongerRunningException) {
                coordinator.failExecution(id, e is TimeoutCancellationException, e.message)
            }
        }
    }

    private suspend fun executeWithRetry(maxRetries: Int, step: ExecutionStep, agentId: UUID?, block: suspend () -> StepExecutionResult): StepExecutionResult {
        var last: Throwable? = null
        repeat(maxRetries + 1) { index ->
            try { return block() } catch (error: Throwable) {
                last = error
                if (error is ExecutionNoLongerRunningException) throw error
                val active = coordinator.recordAttemptFailure(
                    step.executionId,
                    step.id,
                    agentId,
                    index + 1,
                    if (error is TimeoutCancellationException) "STEP_TIMEOUT" else "STEP_EXECUTION_FAILED",
                    error.message,
                    index < maxRetries,
                )
                if (!active) throw ExecutionNoLongerRunningException()
            }
        }
        throw requireNotNull(last)
    }

    internal suspend fun <T> withHeartbeat(
        executionId: UUID,
        intervalMs: Long = heartbeatIntervalMs,
        block: suspend () -> T,
    ): T = coroutineScope {
        val heartbeat = launch {
            while (isActive) {
                delay(intervalMs.coerceAtLeast(100))
                if (!coordinator.refreshHeartbeat(executionId)) return@launch
            }
        }
        try {
            block()
        } finally {
            heartbeat.cancelAndJoin()
        }
    }

    private fun executeLlm(execution: Execution, agent: SnapshotAgentConfig,
                           input: Map<String, Any>, stubMode: Boolean): StepExecutionResult {
        if (!coordinator.recordWhileRunning(execution.id, "MODEL_REQUEST_SENT", agent.sourceAgentId, mapOf("provider" to agent.provider.name, "model" to agent.model))) throw ExecutionNoLongerRunningException()
        val response = if (stubMode) AiModelResponse(stubContent(agent, input), TokenUsage(1, 1), "stub-request") else {
            val credentialId = execution.credentialBindingsJson[agent.key]?.let(UUID::fromString)
                ?: throw IllegalStateException("${agent.name} 구성원의 실행 자격증명 연결이 없습니다.")
            credentials.withDecrypted(credentialId, execution.ownerId, agent.provider) { secret, options ->
                DecryptedCredential(agent.provider, secret, options).use { credential -> gateways.get(agent.provider).execute(credential,
                    AiModelRequest(agent.model, agent.systemPrompt, input.toString(), agent.temperature, agent.maxOutputTokens, agent.timeoutSeconds, agent.providerOptions)) }
            }
        }
        return StepExecutionResult(
            mapOf("result" to response.content, "stub" to stubMode, "agent" to agent.name),
            StepCompletionMetadata(
                inputTokens = response.tokenUsage.inputTokens,
                outputTokens = response.tokenUsage.outputTokens,
                estimatedCost = BigDecimal.ZERO,
                providerRequestId = response.providerRequestId,
            ),
        )
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
class ExecutionQueueWorker(
    private val executions: ExecutionRepository,
    private val processor: ExecutionProcessor,
    private val reconciler: ExecutionRecoveryReconciler,
    @Value("\${execution.lease-seconds:120}") private val leaseSeconds: Long,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val errors = CoroutineExceptionHandler { _, error -> logger.error("Execution queue worker failed", error) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errors)
    private val total = Semaphore(20)
    private val activeUsers = ConcurrentHashMap.newKeySet<UUID>()

    @PreDestroy
    fun shutdown() { scope.cancel() }

    @PostConstruct
    fun recover() { reconcile() }

    @Scheduled(fixedDelayString = "\${execution.recovery-interval-ms:30000}")
    fun reconcile(): Int = reconciler.reconcile(leaseSeconds = leaseSeconds)

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
