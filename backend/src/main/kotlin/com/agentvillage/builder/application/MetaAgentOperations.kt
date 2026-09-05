package com.agentvillage.builder.application

import com.agentvillage.builder.domain.BuilderGenerationStage
import com.agentvillage.builder.domain.BuilderGenerationStatus
import com.agentvillage.builder.domain.BuilderUsageRecord
import com.agentvillage.builder.domain.MetaAgentRun
import com.agentvillage.builder.infrastructure.BuilderGenerationJobRepository
import com.agentvillage.builder.infrastructure.BuilderUsageRecordRepository
import com.agentvillage.builder.infrastructure.MetaAgentRunRepository
import com.agentvillage.common.exception.ConflictException
import com.agentvillage.common.domain.UserRole
import com.agentvillage.identity.application.UserDirectory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class MetaAgentFailure(
    val errorCode: String,
    val errorType: String,
    val retryable: Boolean,
    val durationMs: Long? = null,
    val cliExitCode: Int? = null,
    val safeMessage: String? = null,
)

data class BuilderUsageSummary(
    val plan: String,
    val designLimit: Int?,
    val designUsed: Long,
    val designRemaining: Long?,
    val revisionLimitPerAgent: Int?,
    val unlimited: Boolean,
    val checkoutAvailable: Boolean = false,
)

@Service
class MetaAgentAuditService(private val runs: MetaAgentRunRepository) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(
        context: PipelineContext,
        stage: String,
        status: String,
        inputSummary: Map<String, Any?>,
        outputSummary: Map<String, Any?>? = null,
        failure: MetaAgentFailure? = null,
    ) {
        runs.save(MetaAgentRun(
            traceId = context.traceId,
            workspaceId = context.workspaceId,
            conversationId = context.conversationId,
            workflowId = context.workflowId,
            stage = stage,
            status = status,
            inputSummary = inputSummary,
            outputSummary = outputSummary,
            errorCode = failure?.errorCode,
            failureSummary = failure?.let {
                mapOf(
                    "errorType" to it.errorType,
                    "retryable" to it.retryable,
                    "durationMs" to it.durationMs,
                    "cliExitCode" to it.cliExitCode,
                    "message" to it.safeMessage?.take(300),
                ).filterValues { value -> value != null }
            },
        ))
    }
}

@Service
class BuilderUsageLimiter(
    private val records: BuilderUsageRecordRepository,
    private val users: UserDirectory,
    @Value("\${builder.meta-agent.unlimited-owner-ids:}") unlimitedOwnerIds: String,
    @Value("\${builder.meta-agent.max-revisions-per-conversation:2}") private val maxRevisionsPerConversation: Int = 2,
) {
    private val unlimited: Set<UUID> = unlimitedOwnerIds.split(',')
        .map { value: String -> value.trim() }
        .filter { value: String -> value.isNotBlank() }
        .mapNotNull { value: String -> runCatching { UUID.fromString(value) }.getOrNull() }
        .toSet()

    fun isUnlimited(ownerId: UUID) = ownerId in unlimited || users.require(ownerId).role == UserRole.ADMIN

    @Transactional(readOnly = true)
    fun summary(ownerId: UUID): BuilderUsageSummary {
        val unlimitedOwner = isUnlimited(ownerId)
        val used = records.countByOwnerIdAndLimitSlot(ownerId, "ONLY")
        return BuilderUsageSummary(
            plan = if (unlimitedOwner) "UNLIMITED" else "FREE_BETA",
            designLimit = if (unlimitedOwner) null else 1,
            designUsed = used,
            designRemaining = if (unlimitedOwner) null else (1L - used).coerceAtLeast(0),
            revisionLimitPerAgent = if (unlimitedOwner) null else maxRevisionsPerConversation,
            unlimited = unlimitedOwner,
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claim(context: PipelineContext, idempotencyKey: String) {
        records.findByOwnerIdAndIdempotencyKey(context.ownerId, idempotencyKey)?.let {
            requireSameUsageOperation(it, context)
            return
        }
        try {
            records.saveAndFlush(BuilderUsageRecord(
                ownerId = context.ownerId,
                conversationId = context.conversationId,
                workflowId = context.workflowId,
                limitSlot = if (isUnlimited(context.ownerId)) null else "ONLY",
                idempotencyKey = idempotencyKey,
            ))
        } catch (_: DataIntegrityViolationException) {
            throw ConflictException("BUILDER_CODEX_LIMIT_REACHED", "실제 Codex 업무 분석은 계정당 한 번만 사용할 수 있습니다.")
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claimRevision(context: PipelineContext, idempotencyKey: String) {
        records.findByOwnerIdAndIdempotencyKey(context.ownerId, idempotencyKey)?.let {
            requireSameUsageOperation(it, context)
            return
        }
        if (isUnlimited(context.ownerId)) {
            records.saveAndFlush(BuilderUsageRecord(ownerId = context.ownerId, conversationId = context.conversationId, workflowId = context.workflowId, limitSlot = null, idempotencyKey = idempotencyKey))
            return
        }
        val used = (records.countByOwnerIdAndConversationId(context.ownerId, context.conversationId) - 1).coerceAtLeast(0)
        if (used >= maxRevisionsPerConversation) throw ConflictException("BUILDER_CODEX_REVISION_LIMIT_REACHED", "에이전트 설계 수정 횟수를 모두 사용했습니다.")
        try {
            records.saveAndFlush(BuilderUsageRecord(
                ownerId = context.ownerId,
                conversationId = context.conversationId,
                workflowId = context.workflowId,
                limitSlot = "R-${context.conversationId.toString().take(8)}-${used + 1}",
                idempotencyKey = idempotencyKey,
            ))
        } catch (_: DataIntegrityViolationException) {
            throw ConflictException("BUILDER_CODEX_REVISION_LIMIT_REACHED", "에이전트 설계 수정 횟수를 모두 사용했습니다.")
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun releaseFailedClaim(ownerId: UUID, conversationId: UUID, workflowId: UUID, idempotencyKey: String) {
        records.findByOwnerIdAndIdempotencyKey(ownerId, idempotencyKey)
            ?.takeIf { it.conversationId == conversationId && it.workflowId == workflowId }
            ?.let(records::delete)
    }

    private fun requireSameUsageOperation(record: BuilderUsageRecord, context: PipelineContext) {
        if (record.conversationId != context.conversationId || record.workflowId != context.workflowId) {
            throw ConflictException("IDEMPOTENCY_KEY_REUSED", "다른 대화 또는 제품 흐름에서 사용한 Idempotency-Key입니다.")
        }
    }
}

@Service
class BuilderJobProgressService(private val jobs: BuilderGenerationJobRepository) {
    @Transactional(readOnly = true)
    fun requireJob(jobId: UUID): com.agentvillage.builder.domain.BuilderGenerationJob = jobs.findById(jobId).orElseThrow()
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun running(jobId: UUID?, stage: BuilderGenerationStage) {
        if (jobId == null) return
        val job = jobs.findById(jobId).orElse(null) ?: return
        job.status = BuilderGenerationStatus.RUNNING
        job.stage = stage
        if (job.startedAt == null) job.startedAt = Instant.now()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun complete(jobId: UUID?) {
        if (jobId == null) return
        val job = jobs.findById(jobId).orElse(null) ?: return
        if (job.status == BuilderGenerationStatus.CANCELLED) return
        job.status = BuilderGenerationStatus.SUCCEEDED
        job.stage = BuilderGenerationStage.COMPLETED
        job.finishedAt = Instant.now()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun fail(jobId: UUID?, code: String, message: String) {
        if (jobId == null) return
        val job = jobs.findById(jobId).orElse(null) ?: return
        if (job.status == BuilderGenerationStatus.CANCELLED) return
        job.status = BuilderGenerationStatus.FAILED
        job.stage = BuilderGenerationStage.FAILED
        job.errorCode = code.take(80)
        job.errorMessage = sanitize(message)
        job.finishedAt = Instant.now()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun cancel(jobId: UUID?) {
        if (jobId == null) return
        val job = jobs.findById(jobId).orElse(null) ?: return
        if (job.status in setOf(BuilderGenerationStatus.SUCCEEDED, BuilderGenerationStatus.FAILED, BuilderGenerationStatus.CANCELLED)) return
        job.status = BuilderGenerationStatus.CANCELLED
        job.stage = BuilderGenerationStage.CANCELLED
        job.errorCode = "BUILDER_GENERATION_CANCELLED"
        job.errorMessage = "사용자가 Codex 설계를 중지했습니다."
        job.finishedAt = Instant.now()
    }

    private fun sanitize(value: String) = value
        .replace(Regex("(?i)(api[_-]?key|token|secret|password)\\s*[:=]\\s*\\S+"), "$1=***")
        .take(500)
}
