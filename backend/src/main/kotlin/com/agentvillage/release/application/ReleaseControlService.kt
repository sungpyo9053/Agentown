package com.agentvillage.release.application

import com.agentvillage.builder.application.BuilderWorkspaceAccess
import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.common.exception.ConflictException
import com.agentvillage.common.exception.NotFoundException
import com.agentvillage.release.domain.*
import com.agentvillage.release.infrastructure.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class ReleaseApprovalCommand(val commitSha: String, val environment: String = "PRODUCTION", val scheduledAt: Instant? = null)
data class ReleaseActionCommand(val reason: String? = null)
data class ReleaseVerificationCommand(val approvedSha: String, val observedSha: String?, val healthPassed: Boolean, val readinessPassed: Boolean, val apiSmokePassed: Boolean, val journeyE2ePassed: Boolean, val migrationPassed: Boolean, val errorRateNormal: Boolean, val uncertainOutcome: Boolean = false)
data class ReleaseCandidateCommand(val releaseKey: String, val purpose: String, val userSummary: String, val currentSha: String?, val candidateSha: String, val includedTaskCount: Int, val riskLevel: String, val hasMigration: Boolean, val stagingStatus: String, val preflightHash: String, val detail: Map<String, Any?>)

@Service
class ReleaseControlService(private val releases: ReleaseRepository, private val events: ReleaseEventRepository, private val workspaces: BuilderWorkspaceAccess) {
    @Transactional
    fun publishCandidate(ownerId: UUID, command: ReleaseCandidateCommand): ReleaseRecord {
        requireKoreanUserText(command.purpose, "변경 목적", 5, 300)
        requireKoreanUserText(command.userSummary, "사용자 변화", 10, 500)
        val workspaceId = workspaces.requireWorkspaceId(ownerId)
        val existing = releases.findByWorkspaceIdAndReleaseKey(workspaceId, command.releaseKey)
        val ready = command.stagingStatus == "PASSED" && (command.detail["environmentContract"] as? Map<*, *>)?.get("configured") == true
        if (existing == null) {
            val created = releases.save(ReleaseRecord(workspaceId = workspaceId, releaseKey = command.releaseKey, purpose = command.purpose, userSummary = command.userSummary, status = if (ready) ReleaseStatus.APPROVAL_REQUIRED else ReleaseStatus.CANDIDATE, riskLevel = command.riskLevel, currentSha = command.currentSha, candidateSha = command.candidateSha, includedTaskCount = command.includedTaskCount, hasMigration = command.hasMigration, stagingStatus = command.stagingStatus, preflightHash = command.preflightHash, detail = command.detail))
            event(created, null, null, created.status, "CREATED", "Release Agent 후보 생성")
            return created
        }
        existing.purpose = command.purpose
        existing.userSummary = command.userSummary
        existing.stagingStatus = command.stagingStatus
        existing.detail = command.detail
        if (existing.candidateSha != command.candidateSha || existing.preflightHash != command.preflightHash) {
            val previous = existing.status
            existing.candidateSha = command.candidateSha; existing.preflightHash = command.preflightHash
            existing.approvalIdempotencyKey = null; existing.approvalEnvironment = null; existing.approvedBy = null; existing.approvedAt = null; existing.approvalPreflightHash = null; existing.scheduledAt = null; existing.status = ReleaseStatus.CANDIDATE
            event(existing, null, previous, existing.status, "APPROVAL_INVALIDATED", "후보 SHA 또는 사전검증 결과 변경")
        } else if (existing.status == ReleaseStatus.CANDIDATE && ready) {
            val previous = existing.status
            existing.status = ReleaseStatus.APPROVAL_REQUIRED
            event(existing, null, previous, existing.status, "READY", "실제 환경 계약과 스테이징 검증 확인")
        }
        return existing
    }
    @Transactional(readOnly = true) fun list(adminId: UUID) = releases.findAllByWorkspaceIdOrderByCreatedAtDesc(workspaces.requireWorkspaceId(adminId))
    @Transactional(readOnly = true) fun internalByKey(ownerId: UUID, releaseKey: String) = releases.findByWorkspaceIdAndReleaseKey(workspaces.requireWorkspaceId(ownerId), releaseKey) ?: notFound()
    @Transactional(readOnly = true) fun get(adminId: UUID, id: UUID) = owned(adminId, id)
    @Transactional(readOnly = true) fun history(adminId: UUID, id: UUID): List<ReleaseEvent> { val r = owned(adminId, id); return events.findAllByReleaseIdAndWorkspaceIdOrderByCreatedAt(r.id, r.workspaceId) }

    @Transactional
    fun approve(adminId: UUID, id: UUID, key: String, command: ReleaseApprovalCommand): ReleaseRecord {
        val workspaceId = workspaces.requireWorkspaceId(adminId)
        releases.findByWorkspaceIdAndApprovalIdempotencyKey(workspaceId, key)?.let { return it }
        val r = releases.findByIdAndWorkspaceId(id, workspaceId) ?: notFound()
        if (r.approvedAt != null) throw ConflictException("RELEASE_ALREADY_APPROVED", "이미 승인된 배포입니다. 변경하려면 승인을 취소한 뒤 다시 검토해 주세요.")
        if (r.status != ReleaseStatus.APPROVAL_REQUIRED) throw ConflictException("RELEASE_NOT_APPROVABLE", "현재 상태에서는 운영 배포를 승인할 수 없습니다.")
        if (r.candidateSha != command.commitSha) throw ConflictException("RELEASE_SHA_MISMATCH", "배포 후보 SHA가 변경되어 다시 확인해야 합니다.")
        if (command.environment != "PRODUCTION") throw BadRequestException("RELEASE_ENVIRONMENT_INVALID", "운영 승인 대상은 PRODUCTION이어야 합니다.")
        val previous = r.status
        r.approvalIdempotencyKey = key; r.approvalEnvironment = command.environment; r.approvedBy = adminId; r.approvedAt = Instant.now(); r.approvalPreflightHash = r.preflightHash; r.scheduledAt = command.scheduledAt
        r.status = if (command.scheduledAt == null) ReleaseStatus.APPROVAL_REQUIRED else ReleaseStatus.SCHEDULED
        event(r, adminId, previous, r.status, "APPROVED", if (command.scheduledAt == null) "관리자 화면 즉시 배포 승인" else "관리자 화면 예약 배포 승인")
        return r
    }

    @Transactional fun cancel(adminId: UUID, id: UUID, reason: String?) = controlledTransition(adminId, id, ReleaseStatus.APPROVAL_REQUIRED, "CANCELLED", reason)
    @Transactional fun hold(adminId: UUID, id: UUID, reason: String?) = controlledTransition(adminId, id, ReleaseStatus.HELD, "HELD", reason)
    @Transactional fun discard(adminId: UUID, id: UUID, reason: String?) = controlledTransition(adminId, id, ReleaseStatus.DISCARDED, "DISCARDED", reason)

    @Transactional
    fun recordVerification(adminId: UUID, id: UUID, command: ReleaseVerificationCommand): ReleaseRecord {
        val r = owned(adminId, id)
        if (command.approvedSha != r.candidateSha) throw ConflictException("RELEASE_SHA_MISMATCH", "승인 SHA와 검증 대상 SHA가 다릅니다.")
        val previous = r.status
        r.actualDeployedSha = command.observedSha; r.uncertainOutcome = command.uncertainOutcome
        val passed = !command.uncertainOutcome && command.observedSha == command.approvedSha && command.healthPassed && command.readinessPassed && command.apiSmokePassed && command.journeyE2ePassed && command.migrationPassed && command.errorRateNormal
        r.status = if (passed) ReleaseStatus.RELEASED else if (command.uncertainOutcome) ReleaseStatus.HUMAN_DECISION_REQUIRED else ReleaseStatus.ROLLBACK_REQUIRED
        r.detail = r.detail + ("productionVerification" to mapOf("approvedSha" to command.approvedSha, "observedSha" to command.observedSha, "healthPassed" to command.healthPassed, "readinessPassed" to command.readinessPassed, "apiSmokePassed" to command.apiSmokePassed, "journeyE2ePassed" to command.journeyE2ePassed, "migrationPassed" to command.migrationPassed, "errorRateNormal" to command.errorRateNormal, "uncertainOutcome" to command.uncertainOutcome, "verifiedAt" to Instant.now().toString()))
        event(r, adminId, previous, r.status, if (passed) "PASSED" else "FAILED", if (command.observedSha != command.approvedSha) "승인 SHA와 운영 SHA 불일치" else null)
        return r
    }

    private fun controlledTransition(adminId: UUID, id: UUID, next: ReleaseStatus, result: String, reason: String?): ReleaseRecord {
        val r = owned(adminId, id)
        if (r.status in setOf(ReleaseStatus.DEPLOYING, ReleaseStatus.VERIFYING, ReleaseStatus.RELEASED)) throw ConflictException("RELEASE_TRANSITION_REJECTED", "진행 중이거나 완료된 배포는 변경할 수 없습니다.")
        val previous = r.status
        r.approvalIdempotencyKey = null; r.approvalEnvironment = null; r.approvedBy = null; r.approvedAt = null; r.approvalPreflightHash = null; r.scheduledAt = null; r.status = next
        event(r, adminId, previous, next, result, reason)
        return r
    }
    private fun requireKoreanUserText(value: String, label: String, minimum: Int, maximum: Int) {
        if (value.length !in minimum..maximum || value.count { it in '가'..'힣' } < 3) {
            throw BadRequestException("RELEASE_KOREAN_TEXT_REQUIRED", "$label 문구는 완결된 한국어로 작성해야 합니다.")
        }
    }
    private fun owned(adminId: UUID, id: UUID): ReleaseRecord = releases.findByIdAndWorkspaceId(id, workspaces.requireWorkspaceId(adminId)) ?: notFound()
    private fun notFound(): Nothing = throw NotFoundException("RELEASE_NOT_FOUND", "배포 정보를 찾을 수 없습니다.")
    private fun event(r: ReleaseRecord, actor: UUID?, previous: ReleaseStatus?, next: ReleaseStatus, result: String, reason: String?) = events.save(ReleaseEvent(releaseId = r.id, workspaceId = r.workspaceId, actorId = actor, actorLabel = if (actor == null) "RELEASE_AGENT" else "ADMIN_UI", previousStatus = previous, nextStatus = next, commitSha = r.candidateSha, result = result, reason = reason))
}
