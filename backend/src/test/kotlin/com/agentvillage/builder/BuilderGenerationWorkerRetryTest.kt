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
    fun `one transient Codex timeout retries the same durable generation job`() {
        val ownerId = UUID.randomUUID()
        val job = job()
        whenever(progress.requireJob(job.id)).thenReturn(job)
        whenever(builder.sendMessage(ownerId, job.conversationId, job.instruction, job.idempotencyKey, job.id))
            .thenThrow(BadRequestException("BUILDER_CODEX_TIMEOUT", "Codex 분석 제한 시간을 초과했습니다."))
            .thenReturn(mock())

        BuilderGenerationWorker(builder, progress, usageLimiter).execute(BuilderGenerationRequested(ownerId, job.id))

        verify(builder, times(2)).sendMessage(ownerId, job.conversationId, job.instruction, job.idempotencyKey, job.id)
        verify(progress).complete(job.id)
        verify(progress, never()).fail(any(), any(), any())
        verify(usageLimiter, never()).releaseFailedClaim(any(), any(), any(), any())
        verify(builder, never()).recordGenerationFailure(any(), any(), any(), any(), any())
    }

    @Test
    fun `exhausted transient retry records one durable failed result`() {
        val ownerId = UUID.randomUUID()
        val job = job()
        val message = "Codex 분석 제한 시간을 초과했습니다."
        whenever(progress.requireJob(job.id)).thenReturn(job)
        whenever(builder.sendMessage(ownerId, job.conversationId, job.instruction, job.idempotencyKey, job.id))
            .thenThrow(BadRequestException("BUILDER_CODEX_TIMEOUT", message))

        BuilderGenerationWorker(builder, progress, usageLimiter).execute(BuilderGenerationRequested(ownerId, job.id))

        verify(builder, times(2)).sendMessage(ownerId, job.conversationId, job.instruction, job.idempotencyKey, job.id)
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

    private fun job() = BuilderGenerationJob(
        workspaceId = UUID.randomUUID(),
        conversationId = UUID.randomUUID(),
        workflowId = UUID.randomUUID(),
        instruction = "서로 독립적인 세 분석을 병렬 수행한 뒤 결과를 합쳐줘",
        idempotencyKey = "generation-retry-${UUID.randomUUID()}",
    )
}
