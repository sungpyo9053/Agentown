package com.agentvillage.builder

import com.agentvillage.builder.application.BuilderGenerationRequested
import com.agentvillage.builder.application.BuilderGenerationWorker
import com.agentvillage.builder.application.BuilderJobProgressService
import com.agentvillage.builder.application.BuilderService
import com.agentvillage.builder.application.BuilderUsageLimiter
import com.agentvillage.builder.domain.BuilderGenerationJob
import com.agentvillage.common.exception.BadRequestException
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

class BuilderGenerationWorkerRetryTest {
    private val builder = mock<BuilderService>()
    private val progress = mock<BuilderJobProgressService>()
    private val usageLimiter = mock<BuilderUsageLimiter>()

    @Test
    fun `one transient Codex start failure retries the same durable generation job`() {
        val ownerId = UUID.randomUUID()
        val job = job()
        whenever(progress.requireJob(job.id)).thenReturn(job)
        whenever(builder.sendMessage(ownerId, job.conversationId, job.instruction, job.idempotencyKey, job.id))
            .thenThrow(BadRequestException("BUILDER_CODEX_START_FAILED", "Codex 시작에 실패했습니다."))
            .thenReturn(mock())

        BuilderGenerationWorker(builder, progress, usageLimiter).execute(BuilderGenerationRequested(ownerId, job.id))

        verify(builder, times(2)).sendMessage(ownerId, job.conversationId, job.instruction, job.idempotencyKey, job.id)
        verify(progress).complete(job.id)
        verify(progress, never()).fail(any(), any(), any())
        verify(usageLimiter, never()).releaseFailedClaim(any(), any(), any(), any())
        verify(builder, never()).recordGenerationFailure(any(), any(), any(), any(), any())
    }

    @Test
    fun `timeout preserves input and releases usage without replaying the whole generation`() {
        val ownerId = UUID.randomUUID()
        val job = job()
        val message = "Codex 분석 제한 시간을 초과했습니다."
        whenever(progress.requireJob(job.id)).thenReturn(job)
        whenever(builder.sendMessage(ownerId, job.conversationId, job.instruction, job.idempotencyKey, job.id))
            .thenThrow(BadRequestException("BUILDER_CODEX_TIMEOUT", message))

        BuilderGenerationWorker(builder, progress, usageLimiter).execute(BuilderGenerationRequested(ownerId, job.id))

        verify(builder, times(1)).sendMessage(ownerId, job.conversationId, job.instruction, job.idempotencyKey, job.id)
        verify(progress, never()).complete(job.id)
        verify(usageLimiter).releaseFailedClaim(ownerId, job.conversationId, job.workflowId, job.idempotencyKey)
        verify(builder).recordGenerationFailure(ownerId, job.conversationId, job.instruction, job.idempotencyKey, message)
        verify(progress).fail(job.id, "BUILDER_CODEX_TIMEOUT", message)
    }

    @Test
    fun `non retryable cancellation is never restarted`() {
        val ownerId = UUID.randomUUID()
        val job = job()
        whenever(progress.requireJob(job.id)).thenReturn(job)
        whenever(builder.sendMessage(ownerId, job.conversationId, job.instruction, job.idempotencyKey, job.id))
            .thenThrow(BadRequestException("BUILDER_GENERATION_CANCELLED", "사용자가 중지했습니다."))

        BuilderGenerationWorker(builder, progress, usageLimiter).execute(BuilderGenerationRequested(ownerId, job.id))

        verify(builder).sendMessage(ownerId, job.conversationId, job.instruction, job.idempotencyKey, job.id)
        verify(progress).cancel(job.id)
        verify(usageLimiter, never()).releaseFailedClaim(any(), any(), any(), any())
        verify(builder, never()).recordGenerationFailure(any(), any(), any(), any(), any())
    }

    @Test
    fun `inapplicable requests fail the job without destroying a valid workflow`() {
        val ownerId = UUID.randomUUID()
        val job = job()
        whenever(progress.requireJob(job.id)).thenReturn(job)
        whenever(builder.sendMessage(ownerId, job.conversationId, job.instruction, job.idempotencyKey, job.id))
            .thenThrow(com.agentvillage.common.exception.ConflictException("BUILDER_MESSAGE_NOT_APPLICABLE", "현재 단계에서는 처리할 수 없습니다."))

        BuilderGenerationWorker(builder, progress, usageLimiter).execute(BuilderGenerationRequested(ownerId, job.id))

        verify(progress).fail(job.id, "BUILDER_MESSAGE_NOT_APPLICABLE", "현재 단계에서는 처리할 수 없습니다.")
        verify(builder, never()).recordGenerationFailure(any(), any(), any(), any(), any())
        verify(progress, never()).complete(any())
    }

    private fun job() = BuilderGenerationJob(
        workspaceId = UUID.randomUUID(),
        conversationId = UUID.randomUUID(),
        workflowId = UUID.randomUUID(),
        instruction = "서로 독립적인 세 분석을 병렬 수행한 뒤 결과를 합쳐줘",
        idempotencyKey = "generation-retry-${UUID.randomUUID()}",
    )
}
