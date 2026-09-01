package com.agentvillage.builder.application

import com.agentvillage.builder.domain.BuilderGenerationJob
import com.agentvillage.builder.domain.BuilderGenerationStatus
import com.agentvillage.builder.domain.BuilderGenerationStage
import com.agentvillage.builder.infrastructure.BuilderConversationRepository
import com.agentvillage.builder.infrastructure.BuilderGenerationJobRepository
import com.agentvillage.builder.infrastructure.BuilderWorkflowRepository
import com.agentvillage.builder.infrastructure.BuilderWorkspaceRepository
import com.agentvillage.common.exception.ApiException
import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.common.exception.NotFoundException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class BuilderGenerationJobView(
    val id: UUID,
    val conversationId: UUID,
    val workflowId: UUID,
    val status: BuilderGenerationStatus,
    val stage: BuilderGenerationStage,
    val estimatedSeconds: Int,
    val elapsedSeconds: Long,
    val remainingSeconds: Long,
    val errorCode: String?,
    val errorMessage: String?,
)

data class BuilderGenerationRequested(val ownerId: UUID, val jobId: UUID)

@Service
class BuilderGenerationService(
    private val workspaces: BuilderWorkspaceRepository,
    private val conversations: BuilderConversationRepository,
    private val workflows: BuilderWorkflowRepository,
    private val jobs: BuilderGenerationJobRepository,
    private val publisher: ApplicationEventPublisher,
    private val runner: CodexCliRunner,
) {
    @Transactional
    fun enqueue(ownerId: UUID, conversationId: UUID, instruction: String, idempotencyKey: String): BuilderGenerationJobView {
        if (idempotencyKey.isBlank() || idempotencyKey.length > 120) throw BadRequestException("IDEMPOTENCY_KEY_REQUIRED", "유효한 Idempotency-Key가 필요합니다.")
        val workspace = workspaces.findByOwnerId(ownerId) ?: throw NotFoundException("WORKSPACE_NOT_FOUND", "워크스페이스를 찾을 수 없습니다.")
        val conversation = conversations.findByIdAndWorkspaceId(conversationId, workspace.id) ?: throw NotFoundException("BUILDER_CONVERSATION_NOT_FOUND", "대화를 찾을 수 없습니다.")
        val workflow = workflows.findByConversationId(conversation.id) ?: throw NotFoundException("WORKFLOW_NOT_FOUND", "워크플로우를 찾을 수 없습니다.")
        jobs.findByWorkspaceIdAndIdempotencyKey(workspace.id, idempotencyKey)?.let { return view(it) }
        val job = jobs.save(BuilderGenerationJob(workspaceId = workspace.id, conversationId = conversation.id, workflowId = workflow.id, instruction = instruction.trim(), idempotencyKey = idempotencyKey))
        publisher.publishEvent(BuilderGenerationRequested(ownerId, job.id))
        return view(job)
    }

    @Transactional(readOnly = true)
    fun get(ownerId: UUID, jobId: UUID): BuilderGenerationJobView {
        val workspace = workspaces.findByOwnerId(ownerId) ?: throw NotFoundException("WORKSPACE_NOT_FOUND", "워크스페이스를 찾을 수 없습니다.")
        return view(jobs.findByIdAndWorkspaceId(jobId, workspace.id) ?: throw NotFoundException("BUILDER_GENERATION_JOB_NOT_FOUND", "분석 작업을 찾을 수 없습니다."))
    }

    @Transactional
    fun cancel(ownerId: UUID, jobId: UUID, idempotencyKey: String): BuilderGenerationJobView {
        if (idempotencyKey.isBlank() || idempotencyKey.length > 120) throw BadRequestException("IDEMPOTENCY_KEY_REQUIRED", "유효한 Idempotency-Key가 필요합니다.")
        val workspace = workspaces.findByOwnerId(ownerId) ?: throw NotFoundException("WORKSPACE_NOT_FOUND", "워크스페이스를 찾을 수 없습니다.")
        val job = jobs.findByIdAndWorkspaceId(jobId, workspace.id) ?: throw NotFoundException("BUILDER_GENERATION_JOB_NOT_FOUND", "분석 작업을 찾을 수 없습니다.")
        if (job.status !in setOf(BuilderGenerationStatus.SUCCEEDED, BuilderGenerationStatus.FAILED, BuilderGenerationStatus.CANCELLED)) {
            job.status = BuilderGenerationStatus.CANCELLED
            job.stage = BuilderGenerationStage.CANCELLED
            job.errorCode = "BUILDER_GENERATION_CANCELLED"
            job.errorMessage = "사용자가 Codex 설계를 중지했습니다."
            job.finishedAt = Instant.now()
            runner.cancel(job.id)
        }
        return view(job)
    }

    private fun view(job: BuilderGenerationJob): BuilderGenerationJobView {
        val started = job.startedAt ?: job.createdAt
        val ended = job.finishedAt ?: Instant.now()
        val elapsed = ChronoUnit.SECONDS.between(started, ended).coerceAtLeast(0)
        return BuilderGenerationJobView(job.id, job.conversationId, job.workflowId, job.status, job.stage, job.estimatedSeconds, elapsed, (job.estimatedSeconds - elapsed).coerceAtLeast(0), job.errorCode, job.errorMessage)
    }
}

@Component
class BuilderGenerationWorker(
    private val builder: BuilderService,
    private val progress: BuilderJobProgressService,
    private val usageLimiter: BuilderUsageLimiter,
) {
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun execute(event: BuilderGenerationRequested) {
        try {
            builder.sendMessage(event.ownerId, progressJob(event.jobId).conversationId, progressJob(event.jobId).instruction, progressJob(event.jobId).idempotencyKey, event.jobId)
            progress.complete(event.jobId)
        } catch (exception: Exception) {
            val code = (exception as? ApiException)?.code ?: "BUILDER_GENERATION_FAILED"
            if (code == "BUILDER_GENERATION_CANCELLED") progress.cancel(event.jobId)
            else {
                val job = progressJob(event.jobId)
                usageLimiter.releaseFailedClaim(event.ownerId, job.idempotencyKey)
                progress.fail(event.jobId, code, exception.message ?: "업무 분석에 실패했습니다.")
            }
        }
    }

    private fun progressJob(jobId: UUID): BuilderGenerationJob = progress.requireJob(jobId)
}
