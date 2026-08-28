package com.agentvillage.execution

import com.agentvillage.IntegrationTestSupport
import com.agentvillage.execution.domain.Execution
import com.agentvillage.execution.domain.ExecutionEvent
import com.agentvillage.execution.domain.ExecutionMode
import com.agentvillage.execution.domain.ExecutionStatus
import com.agentvillage.execution.domain.ExecutionStep
import com.agentvillage.execution.domain.StepStatus
import com.agentvillage.execution.infrastructure.ExecutionEventRepository
import com.agentvillage.execution.infrastructure.ExecutionRepository
import com.agentvillage.execution.infrastructure.ExecutionStepRepository
import com.agentvillage.harness.domain.Harness
import com.agentvillage.harness.infrastructure.HarnessRepository
import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.RegisterUserCommand
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

@AutoConfigureMockMvc
class ExecutionCancellationIntegrationTest : IntegrationTestSupport() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var identities: IdentityService
    @Autowired lateinit var harnesses: HarnessRepository
    @Autowired lateinit var executions: ExecutionRepository
    @Autowired lateinit var executionSteps: ExecutionStepRepository
    @Autowired lateinit var events: ExecutionEventRepository

    @Test
    fun `cancelling active execution seals only unfinished steps and emits one event`() {
        val fixture = fixture("active")
        val startedAt = Instant.parse("2026-08-28T10:00:00Z")
        val execution = executions.save(execution(fixture, ExecutionStatus.RUNNING).also {
            it.startedAt = startedAt
            it.currentStepKey = "running"
        })
        val completedOutput = mapOf<String, Any>("result" to "보존할 완료 결과")
        val completedAt = Instant.parse("2026-08-28T10:01:00Z")
        val completed = executionSteps.save(step(execution.id, "completed", StepStatus.SUCCEEDED).also {
            it.outputJson = completedOutput
            it.finishedAt = completedAt
        })
        val failed = executionSteps.save(step(execution.id, "failed", StepStatus.FAILED).also {
            it.outputJson = mapOf("partial" to "보존")
            it.errorCode = "PROVIDER_FAILED"
            it.finishedAt = completedAt
        })
        val pending = executionSteps.save(step(execution.id, "pending", StepStatus.PENDING))
        val running = executionSteps.save(step(execution.id, "running", StepStatus.RUNNING))
        val waiting = executionSteps.save(step(execution.id, "approval", StepStatus.WAITING_APPROVAL))

        cancel(execution.id, fixture.principal)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.currentStepKey").doesNotExist())
            .andExpect(jsonPath("$.finishedAt").isNotEmpty)

        val cancelled = executions.findById(execution.id).orElseThrow()
        assertThat(cancelled.status).isEqualTo(ExecutionStatus.CANCELLED)
        assertThat(cancelled.currentStepKey).isNull()
        assertThat(cancelled.finishedAt).isNotNull()
        assertThat(cancelled.startedAt).isEqualTo(startedAt)

        val preservedCompleted = executionSteps.findById(completed.id).orElseThrow()
        assertThat(preservedCompleted.status).isEqualTo(StepStatus.SUCCEEDED)
        assertThat(preservedCompleted.outputJson).isEqualTo(completedOutput)
        assertThat(preservedCompleted.finishedAt).isEqualTo(completedAt)
        val preservedFailed = executionSteps.findById(failed.id).orElseThrow()
        assertThat(preservedFailed.status).isEqualTo(StepStatus.FAILED)
        assertThat(preservedFailed.outputJson).isEqualTo(mapOf("partial" to "보존"))
        assertThat(preservedFailed.errorCode).isEqualTo("PROVIDER_FAILED")
        listOf(pending, running, waiting).forEach { unfinished ->
            val sealed = executionSteps.findById(unfinished.id).orElseThrow()
            assertThat(sealed.status).isEqualTo(StepStatus.CANCELLED)
            assertThat(sealed.finishedAt).isEqualTo(cancelled.finishedAt)
        }
        assertThat(events.findAllByExecutionIdOrderBySequenceNo(execution.id).map { it.eventType })
            .containsExactly("EXECUTION_FAILED")
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionStatus::class, names = ["SUCCEEDED", "FAILED", "TIMEOUT"])
    fun `terminal execution is immutable when cancellation is requested`(terminalStatus: ExecutionStatus) {
        val fixture = fixture(terminalStatus.name.lowercase())
        val finishedAt = Instant.parse("2026-08-28T11:00:00Z")
        val executionOutput = mapOf<String, Any>("result" to "최종 결과")
        val execution = executions.save(execution(fixture, terminalStatus).also {
            it.startedAt = Instant.parse("2026-08-28T10:00:00Z")
            it.finishedAt = finishedAt
            it.currentStepKey = "final-step"
            it.outputJson = executionOutput
            it.errorCode = "ORIGINAL_ERROR"
            it.errorMessage = "원래 실패 기록"
        })
        val stepOutput = mapOf<String, Any>("result" to "단계 결과")
        val step = executionSteps.save(step(execution.id, "final-step", StepStatus.SUCCEEDED).also {
            it.outputJson = stepOutput
            it.finishedAt = finishedAt
        })
        events.save(
            ExecutionEvent(
                executionId = execution.id,
                sequenceNo = 1,
                eventType = "EXECUTION_COMPLETED",
                payload = mapOf("status" to terminalStatus.name),
            ),
        )
        val beforeEvents = events.findAllByExecutionIdOrderBySequenceNo(execution.id)
        val originalUpdatedAt = execution.updatedAt

        cancel(execution.id, fixture.principal)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("EXECUTION_NOT_CANCELLABLE"))

        val unchanged = executions.findById(execution.id).orElseThrow()
        assertThat(unchanged.status).isEqualTo(terminalStatus)
        assertThat(unchanged.startedAt).isEqualTo(execution.startedAt)
        assertThat(unchanged.finishedAt).isEqualTo(finishedAt)
        assertThat(unchanged.currentStepKey).isEqualTo("final-step")
        assertThat(unchanged.outputJson).isEqualTo(executionOutput)
        assertThat(unchanged.errorCode).isEqualTo("ORIGINAL_ERROR")
        assertThat(unchanged.errorMessage).isEqualTo("원래 실패 기록")
        assertThat(unchanged.updatedAt).isEqualTo(originalUpdatedAt)
        val unchangedStep = executionSteps.findById(step.id).orElseThrow()
        assertThat(unchangedStep.status).isEqualTo(StepStatus.SUCCEEDED)
        assertThat(unchangedStep.outputJson).isEqualTo(stepOutput)
        assertThat(unchangedStep.finishedAt).isEqualTo(finishedAt)
        assertThat(events.findAllByExecutionIdOrderBySequenceNo(execution.id).map { it.id })
            .containsExactlyElementsOf(beforeEvents.map { it.id })
    }

    @Test
    fun `duplicate cancellation returns conflict without another event`() {
        val fixture = fixture("duplicate")
        val execution = executions.save(execution(fixture, ExecutionStatus.WAITING_APPROVAL).also {
            it.currentStepKey = "approval"
        })

        cancel(execution.id, fixture.principal).andExpect(status().isOk)
        val firstFinishedAt = executions.findById(execution.id).orElseThrow().finishedAt
        val firstEventIds = events.findAllByExecutionIdOrderBySequenceNo(execution.id).map { it.id }

        cancel(execution.id, fixture.principal)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("EXECUTION_NOT_CANCELLABLE"))

        val unchanged = executions.findById(execution.id).orElseThrow()
        assertThat(unchanged.status).isEqualTo(ExecutionStatus.CANCELLED)
        assertThat(unchanged.finishedAt).isEqualTo(firstFinishedAt)
        assertThat(events.findAllByExecutionIdOrderBySequenceNo(execution.id).map { it.id })
            .containsExactlyElementsOf(firstEventIds)
    }

    @Test
    fun `different owner receives not found and cannot change execution`() {
        val owner = fixture("owner")
        val stranger = fixture("stranger")
        val execution = executions.save(execution(owner, ExecutionStatus.RUNNING).also {
            it.currentStepKey = "running"
        })
        val step = executionSteps.save(step(execution.id, "running", StepStatus.RUNNING))

        cancel(execution.id, stranger.principal)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("EXECUTION_NOT_FOUND"))

        assertThat(executions.findById(execution.id).orElseThrow().status).isEqualTo(ExecutionStatus.RUNNING)
        assertThat(executions.findById(execution.id).orElseThrow().currentStepKey).isEqualTo("running")
        assertThat(executionSteps.findById(step.id).orElseThrow().status).isEqualTo(StepStatus.RUNNING)
        assertThat(events.findAllByExecutionIdOrderBySequenceNo(execution.id)).isEmpty()
    }

    private fun cancel(id: UUID, principal: AuthenticatedUser) = mvc.perform(
        post("/api/executions/$id/cancel").with(user(principal)).with(csrf()),
    )

    private fun fixture(label: String): Fixture {
        val suffix = UUID.randomUUID().toString().take(8)
        val identity = identities.register(
            RegisterUserCommand("cancel-$label-$suffix@example.com", "password123", "cancel_$suffix", "취소 검증"),
        )
        val harness = harnesses.save(Harness(ownerId = identity.id, name = "취소 테스트 $suffix"))
        return Fixture(identity.id, harness.id, AuthenticatedUser(identity.id, identity.email, "unused", true))
    }

    private fun execution(fixture: Fixture, status: ExecutionStatus) = Execution(
        harnessId = fixture.harnessId,
        harnessVersionId = null,
        ownerId = fixture.ownerId,
        idempotencyKey = UUID.randomUUID().toString(),
        status = status,
        executionMode = ExecutionMode.STUB,
        inputJson = mapOf("topic" to "cancellation"),
    )

    private fun step(executionId: UUID, key: String, status: StepStatus) = ExecutionStep(
        executionId = executionId,
        harnessStepId = null,
        stepKey = key,
        stepType = "LLM",
        status = status,
    )

    private data class Fixture(
        val ownerId: UUID,
        val harnessId: UUID,
        val principal: AuthenticatedUser,
    )
}
