package com.agentvillage.execution

import com.agentvillage.IntegrationTestSupport
import com.agentvillage.execution.application.ExecutionRecoveryReconciler
import com.agentvillage.execution.application.ExecutionStateCoordinator
import com.agentvillage.execution.application.ExecutionQueueWorker
import com.agentvillage.execution.application.LocalRunnerService
import com.agentvillage.execution.application.StepCompletionMetadata
import com.agentvillage.execution.application.AiModelGateway
import com.agentvillage.execution.application.AiModelGatewayRegistry
import com.agentvillage.execution.application.AiModelRequest
import com.agentvillage.execution.application.AiModelResponse
import com.agentvillage.execution.application.DecryptedCredential
import com.agentvillage.execution.application.TokenUsage
import com.agentvillage.execution.application.StubAiModelGateway
import com.agentvillage.execution.domain.Execution
import com.agentvillage.execution.domain.ExecutionMode
import com.agentvillage.execution.domain.ExecutionStatus
import com.agentvillage.execution.domain.ExecutionStep
import com.agentvillage.execution.domain.LocalRunnerProvider
import com.agentvillage.execution.domain.StepStatus
import com.agentvillage.execution.infrastructure.ExecutionEventRepository
import com.agentvillage.execution.infrastructure.ExecutionRepository
import com.agentvillage.execution.infrastructure.ExecutionStepRepository
import com.agentvillage.harness.domain.Harness
import com.agentvillage.harness.infrastructure.HarnessRepository
import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.RegisterUserCommand
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import com.agentvillage.llmcredential.application.CredentialDirectory
import com.agentvillage.llmcredential.application.CredentialMetadata
import com.agentvillage.llmcredential.domain.CredentialStatus
import com.agentvillage.llmcredential.domain.LlmProvider
import jakarta.annotation.PostConstruct
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.AutowireCapableBeanFactory
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@AutoConfigureMockMvc
@Import(ExecutionQueueRecoveryTestConfiguration::class)
@TestPropertySource(properties = [
    "execution.heartbeat-interval-ms=100",
    "execution.lease-seconds=1",
    "execution.recovery-interval-ms=86400000",
    "execution.poll-interval-ms=60000",
])
class ExecutionQueueRecoveryTest : IntegrationTestSupport() {
    @Autowired lateinit var executions: ExecutionRepository
    @Autowired lateinit var executionSteps: ExecutionStepRepository
    @Autowired lateinit var events: ExecutionEventRepository
    @Autowired lateinit var harnesses: HarnessRepository
    @Autowired lateinit var identities: IdentityService
    @Autowired lateinit var coordinator: ExecutionStateCoordinator
    @Autowired lateinit var reconciler: ExecutionRecoveryReconciler
    @Autowired lateinit var worker: ExecutionQueueWorker
    @Autowired lateinit var localRunner: LocalRunnerService
    @Autowired lateinit var mvc: MockMvc
    @Autowired private lateinit var blockingGateway: ControlledAiModelGateway
    @Autowired lateinit var transactionManager: PlatformTransactionManager
    @Autowired lateinit var beanFactory: AutowireCapableBeanFactory

    @BeforeEach
    fun resetGateway() {
        blockingGateway.reset()
    }

    @Test
    fun `stale running execution is atomically timed out once without redispatching interrupted step`() {
        val fixture = fixture("stale")
        val now = Instant.now()
        val execution = executions.save(
            execution(fixture.ownerId, fixture.harnessId, ExecutionStatus.RUNNING).also {
                it.startedAt = now.minusSeconds(500)
                it.heartbeatAt = now.minusSeconds(300)
                it.timeoutAt = now.plusSeconds(600)
                it.currentStepKey = "external-write"
            },
        )
        val completedOutput = mapOf<String, Any>("result" to "보존할 완료 결과")
        val completed = executionSteps.save(
            ExecutionStep(
                executionId = execution.id,
                harnessStepId = null,
                stepKey = "research",
                stepType = "LLM",
                status = StepStatus.SUCCEEDED,
                outputJson = completedOutput,
                startedAt = now.minusSeconds(480),
                finishedAt = now.minusSeconds(420),
            ),
        )
        val interrupted = executionSteps.save(
            ExecutionStep(
                executionId = execution.id,
                harnessStepId = null,
                stepKey = "external-write",
                stepType = "EXTERNAL_API",
                status = StepStatus.RUNNING,
                startedAt = now.minusSeconds(300),
            ),
        )

        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val claims = try {
            (1..2).map {
                pool.submit<Boolean> {
                    ready.countDown()
                    start.await()
                    coordinator.recoverOne(execution.id, now, now.minusSeconds(120))
                }
            }.also {
                ready.await()
                start.countDown()
            }.map { it.get() }
        } finally {
            pool.shutdownNow()
        }

        assertThat(claims.count { it }).isEqualTo(1)
        assertThat(coordinator.completeExecution(execution.id, mapOf("should" to "not overwrite timeout"))).isFalse()
        assertThat(coordinator.recoverOne(execution.id, now, now.minusSeconds(120))).isFalse()

        val recovered = executions.findById(execution.id).orElseThrow()
        assertThat(recovered.status).isEqualTo(ExecutionStatus.TIMEOUT)
        assertThat(recovered.finishedAt).isNotNull()
        assertThat(recovered.currentStepKey).isNull()
        assertThat(recovered.errorCode).isEqualTo("WORKER_LEASE_EXPIRED")
        assertThat(executionSteps.findById(completed.id).orElseThrow().outputJson).isEqualTo(completedOutput)
        assertThat(executionSteps.findById(completed.id).orElseThrow().status).isEqualTo(StepStatus.SUCCEEDED)
        assertThat(executionSteps.findById(interrupted.id).orElseThrow().status).isEqualTo(StepStatus.TIMEOUT)
        assertThat(executionSteps.findById(interrupted.id).orElseThrow().errorCode).isEqualTo("INTERRUPTED_STEP_OUTCOME_UNKNOWN")
        assertThat(events.findAllByExecutionIdOrderBySequenceNo(execution.id).count { it.eventType == "EXECUTION_TIMEOUT_RECOVERED" }).isEqualTo(1)

        val owner = AuthenticatedUser(fixture.ownerId, fixture.email, "unused", true)
        mvc.perform(get("/api/executions/${execution.id}").with(user(owner)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.execution.status").value("TIMEOUT"))
            .andExpect(jsonPath("$.execution.errorCode").value("WORKER_LEASE_EXPIRED"))
            .andExpect(jsonPath("$.steps[0].outputJson.result").value("보존할 완료 결과"))
            .andExpect(jsonPath("$.steps[1].status").value("TIMEOUT"))

        val stranger = fixture("stranger")
        mvc.perform(get("/api/executions/${execution.id}").with(user(AuthenticatedUser(stranger.ownerId, stranger.email, "unused", true))))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `expired unclaimed local execution is recovered once without losing evidence or dispatching work`() {
        val fixture = fixture("queued")
        val now = Instant.now()
        val evidence = mapOf<String, Any>("source" to "durable-before-claim")
        val execution = executions.save(
            execution(fixture.ownerId, fixture.harnessId, ExecutionStatus.QUEUED, mode = ExecutionMode.LOCAL_CLI).also {
                it.timeoutAt = now.minusSeconds(1)
                it.outputJson = evidence
            },
        )

        assertThat(executions.claimQueued(execution.id, now)).isZero()
        assertThat(worker.reconcile()).isEqualTo(1)
        assertThat(worker.reconcile()).isZero()

        val recovered = executions.findById(execution.id).orElseThrow()
        assertThat(recovered.status).isEqualTo(ExecutionStatus.TIMEOUT)
        assertThat(recovered.executionMode).isEqualTo(ExecutionMode.LOCAL_CLI)
        assertThat(recovered.errorCode).isEqualTo("EXECUTION_DEADLINE_EXCEEDED")
        assertThat(recovered.startedAt).isNull()
        assertThat(recovered.runnerConnectionId).isNull()
        assertThat(recovered.finishedAt).isNotNull()
        assertThat(recovered.inputJson).isEqualTo(mapOf("topic" to "timeout recovery"))
        assertThat(recovered.outputJson).isEqualTo(evidence)
        assertThat(executionSteps.findAllByExecutionIdOrderByStartedAtAsc(execution.id)).isEmpty()
        assertThat(events.findAllByExecutionIdOrderBySequenceNo(execution.id).map { it.eventType })
            .containsExactly("EXECUTION_TIMEOUT_RECOVERED")
        assertThat(blockingGateway.calls.get()).isZero()
    }

    @Test
    fun `local runner claim skips an expired head job and returns the next unexpired job`() {
        val fixture = fixture("local-claim")
        val now = Instant.now()
        val expired = executions.save(
            execution(fixture.ownerId, fixture.harnessId, ExecutionStatus.QUEUED, mode = ExecutionMode.LOCAL_CLI).also {
                it.timeoutAt = now.minusSeconds(60)
            },
        )
        val valid = executions.save(
            execution(
                fixture.ownerId,
                fixture.harnessId,
                ExecutionStatus.QUEUED,
                snapshot = llmSnapshot(fixture.ownerId),
                mode = ExecutionMode.LOCAL_CLI,
            ).also { it.timeoutAt = now.plusSeconds(600) },
        )
        val token = localRunner.pair(fixture.ownerId, LocalRunnerProvider.CODEX, "claim-filter-runner").pairingToken

        val claimed = localRunner.claim(token)
        reconciler.reconcile()

        assertThat(claimed?.executionId).isEqualTo(valid.id)
        assertThat(executions.findById(valid.id).orElseThrow().status).isEqualTo(ExecutionStatus.RUNNING)
        val expiredAfterClaim = executions.findById(expired.id).orElseThrow()
        assertThat(expiredAfterClaim.status).isEqualTo(ExecutionStatus.TIMEOUT)
        assertThat(expiredAfterClaim.startedAt).isNull()
        assertThat(expiredAfterClaim.runnerConnectionId).isNull()
        assertThat(events.findAllByExecutionIdOrderBySequenceNo(expired.id).map { it.eventType })
            .containsExactly("EXECUTION_TIMEOUT_RECOVERED")
    }

    @Test
    fun `claimed local jobs and approval waits are excluded from worker lease recovery`() {
        val fixture = fixture("local-active")
        val now = Instant.now()
        val runnerConnectionId = localRunner.pair(
            fixture.ownerId,
            LocalRunnerProvider.CODEX,
            "active-recovery-runner",
        ).connection.id
        val running = executions.save(
            execution(fixture.ownerId, fixture.harnessId, ExecutionStatus.RUNNING, mode = ExecutionMode.LOCAL_CLI).also {
                it.startedAt = now.minusSeconds(600)
                it.heartbeatAt = now.minusSeconds(600)
                it.timeoutAt = now.plusSeconds(600)
                it.runnerConnectionId = runnerConnectionId
            },
        )
        val waiting = executions.save(
            execution(fixture.ownerId, fixture.harnessId, ExecutionStatus.WAITING_APPROVAL, mode = ExecutionMode.LOCAL_CLI).also {
                it.startedAt = now.minusSeconds(600)
                it.heartbeatAt = now.minusSeconds(600)
                it.timeoutAt = now.plusSeconds(600)
                it.runnerConnectionId = runnerConnectionId
            },
        )

        assertThat(reconciler.reconcile(now, leaseSeconds = 1)).isZero()
        assertThat(executions.findById(running.id).orElseThrow().status).isEqualTo(ExecutionStatus.RUNNING)
        assertThat(executions.findById(waiting.id).orElseThrow().status).isEqualTo(ExecutionStatus.WAITING_APPROVAL)
        assertThat(events.findAllByExecutionIdOrderBySequenceNo(running.id)).isEmpty()
        assertThat(events.findAllByExecutionIdOrderBySequenceNo(waiting.id)).isEmpty()
    }

    @Test
    fun `expired running execution records deadline reason and seals its current step`() {
        val fixture = fixture("running-deadline")
        val now = Instant.now()
        val execution = executions.save(
            execution(fixture.ownerId, fixture.harnessId, ExecutionStatus.RUNNING).also {
                it.startedAt = now.minusSeconds(600)
                it.heartbeatAt = now
                it.timeoutAt = now.minusSeconds(1)
                it.currentStepKey = "model-call"
            },
        )
        val interrupted = executionSteps.save(
            ExecutionStep(
                executionId = execution.id,
                harnessStepId = null,
                stepKey = "model-call",
                stepType = "LLM",
                status = StepStatus.RUNNING,
                startedAt = now.minusSeconds(30),
            ),
        )

        assertThat(reconciler.reconcile(now, leaseSeconds = 120)).isEqualTo(1)

        val recovered = executions.findById(execution.id).orElseThrow()
        val sealedStep = executionSteps.findById(interrupted.id).orElseThrow()
        assertThat(recovered.status).isEqualTo(ExecutionStatus.TIMEOUT)
        assertThat(recovered.errorCode).isEqualTo("EXECUTION_DEADLINE_EXCEEDED")
        assertThat(sealedStep.status).isEqualTo(StepStatus.TIMEOUT)
        assertThat(sealedStep.errorCode).isEqualTo("INTERRUPTED_STEP_OUTCOME_UNKNOWN")
        assertThat(sealedStep.errorMessage).contains("전체 실행 제한 시간이 지난 시점")
        assertThat(events.findAllByExecutionIdOrderBySequenceNo(execution.id).single().payload["reason"])
            .isEqualTo("DEADLINE_EXCEEDED")
    }

    @Test
    fun `a step result returned after the total deadline is preserved but the execution is sealed`() {
        val fixture = fixture("late-result")
        val now = Instant.now()
        val execution = executions.save(
            execution(fixture.ownerId, fixture.harnessId, ExecutionStatus.RUNNING).also {
                it.startedAt = now.minusSeconds(60)
                it.heartbeatAt = now
                it.timeoutAt = now.minusSeconds(1)
                it.currentStepKey = "model-call"
            },
        )
        val step = executionSteps.save(
            ExecutionStep(
                executionId = execution.id,
                harnessStepId = null,
                stepKey = "model-call",
                stepType = "LLM",
                status = StepStatus.RUNNING,
                startedAt = now.minusSeconds(30),
            ),
        )
        val knownOutput = mapOf<String, Any>("result" to "deadline 직후 확인된 결과")

        assertThat(
            coordinator.completeStep(
                execution.id,
                step.id,
                knownOutput,
                StepCompletionMetadata(providerRequestId = "known-request"),
                null,
                false,
            ),
        ).isFalse()

        val terminal = executions.findById(execution.id).orElseThrow()
        val completedStep = executionSteps.findById(step.id).orElseThrow()
        assertThat(terminal.status).isEqualTo(ExecutionStatus.TIMEOUT)
        assertThat(terminal.errorCode).isEqualTo("EXECUTION_DEADLINE_EXCEEDED")
        assertThat(completedStep.status).isEqualTo(StepStatus.SUCCEEDED)
        assertThat(completedStep.outputJson).isEqualTo(knownOutput)
        assertThat(completedStep.providerRequestId).isEqualTo("known-request")
        assertThat(events.findAllByExecutionIdOrderBySequenceNo(execution.id).map { it.eventType })
            .containsExactly("STEP_OUTPUT_CREATED", "STEP_COMPLETED", "EXECUTION_TIMEOUT_RECOVERED")
    }

    @Test
    fun `an attempt failure observed after the total deadline cannot enter automatic retry`() {
        val fixture = fixture("late-failure")
        val now = Instant.now()
        val execution = executions.save(
            execution(fixture.ownerId, fixture.harnessId, ExecutionStatus.RUNNING).also {
                it.startedAt = now.minusSeconds(60)
                it.heartbeatAt = now
                it.timeoutAt = now.minusSeconds(1)
                it.currentStepKey = "external-write"
            },
        )
        val step = executionSteps.save(
            ExecutionStep(
                executionId = execution.id,
                harnessStepId = null,
                stepKey = "external-write",
                stepType = "EXTERNAL_API",
                status = StepStatus.RUNNING,
                startedAt = now.minusSeconds(30),
            ),
        )

        assertThat(
            coordinator.recordAttemptFailure(
                execution.id,
                step.id,
                null,
                attempt = 1,
                errorCode = "STEP_EXECUTION_FAILED",
                errorMessage = "provider response was not confirmed",
                willRetry = true,
            ),
        ).isFalse()

        val terminal = executions.findById(execution.id).orElseThrow()
        val interrupted = executionSteps.findById(step.id).orElseThrow()
        assertThat(terminal.status).isEqualTo(ExecutionStatus.TIMEOUT)
        assertThat(interrupted.status).isEqualTo(StepStatus.TIMEOUT)
        assertThat(interrupted.attempt).isEqualTo(1)
        assertThat(interrupted.errorCode).isEqualTo("INTERRUPTED_STEP_OUTCOME_UNKNOWN")
        assertThat(events.findAllByExecutionIdOrderBySequenceNo(execution.id).map { it.eventType })
            .containsExactly("EXECUTION_TIMEOUT_RECOVERED")
    }

    @Test
    fun `fresh heartbeat protects a genuinely active long step and recovery is scheduled at startup`() {
        val fixture = fixture("heartbeat")
        val credentialId = UUID.randomUUID()
        val execution = executions.save(
            execution(
                fixture.ownerId,
                fixture.harnessId,
                ExecutionStatus.QUEUED,
                snapshot = llmSnapshot(fixture.ownerId),
                credentialBindings = mapOf("agent" to credentialId.toString()),
                mode = ExecutionMode.CLOUD_API,
            ).also { it.timeoutAt = Instant.now().plusSeconds(30) },
        )

        worker.poll()
        assertThat(blockingGateway.started.await(5, TimeUnit.SECONDS)).isTrue()
        val heartbeatAfterClaim = requireNotNull(executions.findById(execution.id).orElseThrow().heartbeatAt)
        await("processor heartbeat") {
            executions.findById(execution.id).orElseThrow().heartbeatAt?.isAfter(heartbeatAfterClaim) == true
        }
        Thread.sleep(1_100)
        assertThat(reconciler.reconcile(Instant.now(), leaseSeconds = 1)).isZero()
        assertThat(executions.findById(execution.id).orElseThrow().status).isEqualTo(ExecutionStatus.RUNNING)

        blockingGateway.release.countDown()
        await("long processor completion") {
            executions.findById(execution.id).orElseThrow().status == ExecutionStatus.SUCCEEDED
        }
        assertThat(blockingGateway.calls.get()).isEqualTo(1)

        assertThat(ExecutionQueueWorker::class.java.getDeclaredMethod("recover").isAnnotationPresent(PostConstruct::class.java)).isTrue()
        assertThat(ExecutionQueueWorker::class.java.getDeclaredMethod("reconcile").getAnnotation(Scheduled::class.java).fixedDelayString)
            .isEqualTo("\${execution.recovery-interval-ms:30000}")
    }

    @Test
    fun `startup recovery seals persisted work exactly once`() {
        val fixture = fixture("startup-lifecycle")
        val startupCandidate = executions.save(
            execution(fixture.ownerId, fixture.harnessId, ExecutionStatus.RUNNING).also {
                it.startedAt = Instant.now().minusSeconds(30)
                it.heartbeatAt = Instant.now().minusSeconds(10)
                it.timeoutAt = Instant.now().plusSeconds(30)
            },
        )

        val startupWorker = beanFactory.createBean(ExecutionQueueWorker::class.java)
        try {
            assertThat(executions.findById(startupCandidate.id).orElseThrow().status)
                .isEqualTo(ExecutionStatus.TIMEOUT)
            assertThat(events.findAllByExecutionIdOrderBySequenceNo(startupCandidate.id).count {
                it.eventType == "EXECUTION_TIMEOUT_RECOVERED"
            }).isEqualTo(1)
        } finally {
            beanFactory.destroyBean(startupWorker)
        }
    }

    @Test
    fun `poller deadline recovery wins against late normal completion without duplicate attempt or event`() {
        val fixture = fixture("poll-race")
        val credentialId = UUID.randomUUID()
        val execution = executions.save(
            execution(
                fixture.ownerId,
                fixture.harnessId,
                ExecutionStatus.QUEUED,
                snapshot = llmSnapshot(fixture.ownerId),
                credentialBindings = mapOf("agent" to credentialId.toString()),
                mode = ExecutionMode.CLOUD_API,
            ).also { it.timeoutAt = Instant.now().plusSeconds(30) },
        )

        worker.poll()
        assertThat(blockingGateway.started.await(5, TimeUnit.SECONDS)).isTrue()
        val running = executions.findById(execution.id).orElseThrow()
        val deadline = requireNotNull(running.timeoutAt)
        assertThat(reconciler.reconcile(deadline, leaseSeconds = 1)).isEqualTo(1)

        blockingGateway.release.countDown()
        await("late processor unwind") {
            events.findAllByExecutionIdOrderBySequenceNo(execution.id).any { it.eventType == "EXECUTION_TIMEOUT_RECOVERED" }
        }
        Thread.sleep(200)

        val terminal = executions.findById(execution.id).orElseThrow()
        val steps = executionSteps.findAllByExecutionIdOrderByStartedAtAsc(execution.id)
        val terminalEvents = events.findAllByExecutionIdOrderBySequenceNo(execution.id)
        assertThat(terminal.status).isEqualTo(ExecutionStatus.TIMEOUT)
        assertThat(steps).hasSize(1)
        assertThat(steps.single().attempt).isEqualTo(1)
        assertThat(steps.single().status).isEqualTo(StepStatus.TIMEOUT)
        assertThat(blockingGateway.calls.get()).isEqualTo(1)
        assertThat(terminalEvents.count { it.eventType == "EXECUTION_TIMEOUT_RECOVERED" }).isEqualTo(1)
        assertThat(terminalEvents.none { it.eventType == "EXECUTION_COMPLETED" }).isTrue()
    }

    @Test
    fun `local runner cannot claim an expired queued execution while recovery seals it`() {
        val fixture = fixture("queued-race")
        val execution = executions.save(
            execution(fixture.ownerId, fixture.harnessId, ExecutionStatus.QUEUED, mode = ExecutionMode.LOCAL_CLI).also {
                it.timeoutAt = Instant.now().plusSeconds(600)
            },
        )
        val token = localRunner.pair(fixture.ownerId, LocalRunnerProvider.CODEX, "recovery-race-runner").pairingToken

        val recoveryLocked = CountDownLatch(1)
        val claimStarted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val recover = pool.submit<Int> {
                TransactionTemplate(transactionManager).execute {
                    val locked = executions.findByIdForUpdate(execution.id) ?: error("execution missing")
                    locked.timeoutAt = Instant.now().minusSeconds(60)
                    executions.saveAndFlush(locked)
                    recoveryLocked.countDown()
                    check(claimStarted.await(5, TimeUnit.SECONDS)) { "claim did not become ready" }
                    reconciler.reconcile()
                } ?: error("recovery transaction returned no result")
            }
            check(recoveryLocked.await(5, TimeUnit.SECONDS)) { "recovery did not acquire the execution lock" }
            val claim = pool.submit {
                claimStarted.countDown()
                localRunner.claim(token)
            }
            assertThat(recover.get(5, TimeUnit.SECONDS)).isEqualTo(1)
            assertThat(claim.get(5, TimeUnit.SECONDS)).isNull()
        } finally {
            pool.shutdownNow()
        }

        val recovered = executions.findById(execution.id).orElseThrow()
        assertThat(recovered.status).isEqualTo(ExecutionStatus.TIMEOUT)
        assertThat(recovered.executionMode).isEqualTo(ExecutionMode.LOCAL_CLI)
        assertThat(recovered.errorCode).isEqualTo("EXECUTION_DEADLINE_EXCEEDED")
        assertThat(recovered.runnerConnectionId).isNull()
        assertThat(recovered.startedAt).isNull()
        assertThat(recovered.finishedAt).isNotNull()
        assertThat(executionSteps.findAllByExecutionIdOrderByStartedAtAsc(execution.id)).isEmpty()
        assertThat(events.findAllByExecutionIdOrderBySequenceNo(execution.id).map { it.eventType })
            .containsExactly("EXECUTION_TIMEOUT_RECOVERED")
        assertThat(blockingGateway.calls.get()).isZero()
    }

    @Nested
    @TestPropertySource(properties = [
        "execution.heartbeat-interval-ms=100",
        "execution.lease-seconds=1",
        "execution.recovery-interval-ms=500",
        "execution.poll-interval-ms=60000",
    ])
    inner class ScheduledRecoveryLifecycle {
        @Autowired lateinit var lifecycleExecutions: ExecutionRepository
        @Autowired lateinit var lifecycleEvents: ExecutionEventRepository
        @Autowired lateinit var lifecycleEnvironment: Environment

        @Test
        @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
        fun `bounded scheduler recovers persisted work exactly once`() {
            val fixture = fixture("scheduled-lifecycle")
            val scheduledCandidate = lifecycleExecutions.save(
                execution(fixture.ownerId, fixture.harnessId, ExecutionStatus.RUNNING).also {
                    it.startedAt = Instant.now().minusSeconds(30)
                    it.heartbeatAt = Instant.now().minusSeconds(10)
                    it.timeoutAt = Instant.now().plusSeconds(30)
                },
            )
            await("scheduled recovery") {
                lifecycleExecutions.findById(scheduledCandidate.id).orElseThrow().status == ExecutionStatus.TIMEOUT
            }

            assertThat(ExecutionQueueWorker::class.java.getDeclaredMethod("recover").isAnnotationPresent(PostConstruct::class.java)).isTrue()
            assertThat(lifecycleEnvironment.getRequiredProperty("execution.recovery-interval-ms")).isEqualTo("500")
            assertThat(ExecutionQueueWorker::class.java.getDeclaredMethod("reconcile").getAnnotation(Scheduled::class.java).fixedDelayString)
                .isEqualTo("\${execution.recovery-interval-ms:30000}")
            assertThat(lifecycleEvents.findAllByExecutionIdOrderBySequenceNo(scheduledCandidate.id).count { it.eventType == "EXECUTION_TIMEOUT_RECOVERED" })
                .isEqualTo(1)
        }
    }

    private fun fixture(label: String): Fixture {
        val suffix = UUID.randomUUID().toString().take(8)
        val email = "$label-$suffix@example.com"
        val owner = identities.register(RegisterUserCommand(email, "password123", "recovery_$suffix", "복구 검증"))
        val harness = harnesses.save(Harness(ownerId = owner.id, name = "복구 테스트 $suffix"))
        return Fixture(owner.id, email, harness.id)
    }

    private fun execution(
        ownerId: UUID,
        harnessId: UUID,
        status: ExecutionStatus,
        snapshot: Map<String, Any> = emptyMap(),
        credentialBindings: Map<String, String> = emptyMap(),
        mode: ExecutionMode = ExecutionMode.STUB,
    ) = Execution(
        harnessId = harnessId,
        harnessVersionId = null,
        ownerId = ownerId,
        idempotencyKey = UUID.randomUUID().toString(),
        status = status,
        executionMode = mode,
        inputJson = mapOf("topic" to "timeout recovery"),
        executionSnapshotJson = snapshot,
        credentialBindingsJson = credentialBindings,
    )

    private fun llmSnapshot(agentId: UUID): Map<String, Any> = mapOf(
        "formatVersion" to 2,
        "validation" to mapOf("outcome" to "VALIDATED"),
        "name" to "heartbeat test",
        "agents" to listOf(mapOf(
            "key" to "agent",
            "sourceAgentId" to agentId.toString(),
            "name" to "Blocking agent",
            "role" to "test",
            "script" to "wait",
            "provider" to "OPENAI",
            "recommendedModel" to "gpt-test",
            "timeoutSeconds" to 10,
        )),
        "steps" to listOf(mapOf(
            "id" to "long-model-call",
            "agentKey" to "agent",
            "type" to "LLM",
            "sequence" to 1,
            "maxRetries" to 0,
            "timeoutSeconds" to 10,
            "requiresApproval" to false,
        )),
        "result" to mapOf("format" to "TEXT", "stepKey" to "long-model-call"),
    )

    private fun await(description: String, timeoutSeconds: Long = 5, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (!condition()) {
            if (System.nanoTime() >= deadline) throw AssertionError("Timed out waiting for $description")
            Thread.sleep(25)
        }
    }

    private data class Fixture(val ownerId: UUID, val email: String, val harnessId: UUID)
}

@TestConfiguration(proxyBeanMethods = false)
private class ExecutionQueueRecoveryTestConfiguration {
    @Bean
    fun controlledAiModelGateway() = ControlledAiModelGateway()

    @Bean
    @Primary
    fun recoveryTestGatewayRegistry(blocking: ControlledAiModelGateway) = AiModelGatewayRegistry(
        listOf(
            object : AiModelGateway {
                override fun supports(provider: LlmProvider) = provider == LlmProvider.OPENAI
                override fun execute(credential: DecryptedCredential, request: AiModelRequest) =
                    blocking.execute(credential, request)
            },
            StubAiModelGateway(LlmProvider.ANTHROPIC),
            StubAiModelGateway(LlmProvider.GOOGLE),
        ),
    )

    @Bean
    @Primary
    fun recoveryTestCredentialDirectory(): CredentialDirectory = object : CredentialDirectory {
        override fun requireOwned(credentialId: UUID, ownerId: UUID, provider: LlmProvider) =
            CredentialMetadata(credentialId, ownerId, provider, CredentialStatus.ACTIVE)

        override fun requireActive(credentialId: UUID, ownerId: UUID, provider: LlmProvider) =
            requireOwned(credentialId, ownerId, provider)

        override fun <T> withDecrypted(
            credentialId: UUID,
            ownerId: UUID,
            provider: LlmProvider,
            block: (CharArray, Map<String, Any>) -> T,
        ): T = block("test-only".toCharArray(), emptyMap())
    }
}

private class ControlledAiModelGateway {
    @Volatile var started = CountDownLatch(1)
    @Volatile var release = CountDownLatch(1)
    val calls = AtomicInteger()

    fun reset() {
        started = CountDownLatch(1)
        release = CountDownLatch(1)
        calls.set(0)
    }

    fun execute(credential: DecryptedCredential, request: AiModelRequest): AiModelResponse {
        calls.incrementAndGet()
        started.countDown()
        check(release.await(10, TimeUnit.SECONDS)) { "controlled gateway was not released" }
        return AiModelResponse("controlled result", TokenUsage(1, 1), "controlled-request")
    }
}
