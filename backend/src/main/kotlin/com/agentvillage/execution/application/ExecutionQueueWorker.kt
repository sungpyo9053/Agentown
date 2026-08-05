package com.agentvillage.execution.application

import com.agentvillage.agent.application.AgentDirectory
import com.agentvillage.execution.domain.*
import com.agentvillage.execution.infrastructure.ExecutionRepository
import com.agentvillage.execution.infrastructure.ExecutionStepRepository
import com.agentvillage.harness.application.HarnessDirectory
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class ExecutionProcessor(
    private val executions: ExecutionRepository, private val executionSteps: ExecutionStepRepository,
    private val harnesses: HarnessDirectory, private val agents: AgentDirectory,
    private val credentials: CredentialDirectory, private val gateways: AiModelGatewayRegistry,
    private val service: ExecutionService,
) {
    private val llmSemaphore = Semaphore(50)
    private val externalSemaphore = Semaphore(100)
    private val downloadSemaphore = Semaphore(10)

    suspend fun process(id: UUID) {
        val execution = executions.findById(id).orElse(null) ?: return
        if (execution.status != ExecutionStatus.QUEUED) return
        execution.status = ExecutionStatus.RUNNING; execution.startedAt = Instant.now(); execution.heartbeatAt = Instant.now()
        executions.save(execution)
        service.record(id, "EXECUTION_STARTED", null, mapOf("status" to "RUNNING"))
        val harness = harnesses.requireOwnedView(execution.harnessId, execution.ownerId)
        var current: Map<String, Any> = execution.inputJson.filterKeys { it != "_stubMode" }
        val stubMode = execution.inputJson["_stubMode"] == true
        try {
            for (step in harness.steps) {
                if (execution.status == ExecutionStatus.CANCELLED) return
                execution.currentStepKey = step.stepKey; execution.heartbeatAt = Instant.now(); executions.save(execution)
                val agent = step.agentId?.let { agents.describeOwned(it, execution.ownerId) }
                val executionStep = executionSteps.save(ExecutionStep(executionId = id, harnessStepId = step.id,
                    stepKey = step.stepKey, stepType = step.stepType.name, inputJson = current, startedAt = Instant.now(), status = StepStatus.RUNNING,
                    provider = agent?.provider?.name, model = agent?.model))
                service.record(id, "STEP_STARTED", agent?.id, mapOf("stepKey" to step.stepKey, "type" to step.stepType.name))
                val output = when (step.stepType) {
                    HarnessStepType.LLM -> llmSemaphore.withPermit { executeLlm(execution, agent!!, current, stubMode, executionStep) }
                    HarnessStepType.EXTERNAL_API -> externalSemaphore.withPermit { mapOf("result" to "external-api-stub", "input" to current) }
                    HarnessStepType.DOWNLOAD -> downloadSemaphore.withPermit { mapOf("result" to current) }
                    HarnessStepType.APPROVAL -> { execution.status = ExecutionStatus.WAITING_APPROVAL; executionStep.status = StepStatus.WAITING_APPROVAL; executions.save(execution); executionSteps.save(executionStep); service.record(id, "WAITING_APPROVAL", agent?.id, mapOf("stepKey" to step.stepKey)); return }
                }
                executionStep.outputJson = output; executionStep.status = StepStatus.SUCCEEDED; executionStep.finishedAt = Instant.now()
                executionSteps.save(executionStep)
                current = output
                service.record(id, "STEP_OUTPUT_CREATED", agent?.id, mapOf("stepKey" to step.stepKey))
                service.record(id, "STEP_COMPLETED", agent?.id, mapOf("stepKey" to step.stepKey))
                if (step.requiresApproval) { execution.status = ExecutionStatus.WAITING_APPROVAL; executions.save(execution); service.record(id, "WAITING_APPROVAL", agent?.id, mapOf("stepKey" to step.stepKey)); return }
            }
            execution.outputJson = current; execution.status = ExecutionStatus.SUCCEEDED; execution.finishedAt = Instant.now()
            executions.save(execution)
            service.record(id, "EXECUTION_COMPLETED", null, mapOf("status" to "SUCCEEDED"))
        } catch (e: Exception) {
            execution.status = ExecutionStatus.FAILED; execution.errorCode = "STEP_EXECUTION_FAILED"
            execution.errorMessage = e.message?.take(1000); execution.finishedAt = Instant.now()
            executions.save(execution)
            service.record(id, "EXECUTION_FAILED", null, mapOf("errorCode" to "STEP_EXECUTION_FAILED"))
        }
    }

    private fun executeLlm(execution: Execution, agent: com.agentvillage.agent.application.AgentDescriptor,
                           input: Map<String, Any>, stubMode: Boolean, step: ExecutionStep): Map<String, Any> {
        service.record(execution.id, "MODEL_REQUEST_SENT", agent.id, mapOf("provider" to agent.provider.name, "model" to agent.model))
        val response = if (stubMode) AiModelResponse("stub:${input.values.joinToString(" ")}", TokenUsage(1, 1), "stub-request") else {
            val credentialId = requireNotNull(agent.credentialId)
            credentials.withDecrypted(credentialId, execution.ownerId, agent.provider) { secret, options ->
                DecryptedCredential(agent.provider, secret, options).use { credential -> gateways.get(agent.provider).execute(credential,
                    AiModelRequest(agent.model, agent.systemPrompt, input.toString(), agent.temperature, agent.maxOutputTokens, agent.timeoutSeconds, agent.providerOptions)) }
            }
        }
        step.inputTokens = response.tokenUsage.inputTokens; step.outputTokens = response.tokenUsage.outputTokens
        step.estimatedCost = BigDecimal.ZERO; step.providerRequestId = response.providerRequestId
        executionSteps.save(step)
        return mapOf("result" to response.content)
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
        executions.findTop20ByStatusOrderByQueuedAt(ExecutionStatus.QUEUED).forEach { execution ->
            if (activeUsers.add(execution.ownerId)) scope.launch { total.withPermit {
                try { processor.process(execution.id) } finally { activeUsers.remove(execution.ownerId) }
            } }
        }
    }
}
