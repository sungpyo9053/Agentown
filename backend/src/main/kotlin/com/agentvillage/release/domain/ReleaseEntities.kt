package com.agentvillage.release.domain

import com.agentvillage.common.domain.AuditedEntity
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

enum class ReleaseStatus { CANDIDATE, APPROVAL_REQUIRED, SCHEDULED, DEPLOYING, VERIFYING, RELEASED, FAILED, HELD, ROLLBACK_REQUIRED, ROLLED_BACK, DISCARDED, HUMAN_DECISION_REQUIRED }

@Entity @Table(name = "releases")
class ReleaseRecord(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "workspace_id", nullable = false) val workspaceId: UUID,
    @Column(name = "release_key", nullable = false, length = 80) val releaseKey: String,
    @Column(nullable = false, length = 300) val purpose: String,
    @Column(name = "user_summary", nullable = false, length = 500) val userSummary: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) var status: ReleaseStatus = ReleaseStatus.CANDIDATE,
    @Column(name = "risk_level", nullable = false, length = 20) val riskLevel: String,
    @Column(name = "current_sha", length = 40) val currentSha: String?,
    @Column(name = "candidate_sha", nullable = false, length = 40) var candidateSha: String,
    @Column(name = "included_task_count", nullable = false) val includedTaskCount: Int,
    @Column(name = "has_migration", nullable = false) val hasMigration: Boolean,
    @Column(name = "staging_status", nullable = false, length = 40) var stagingStatus: String,
    @Column(name = "scheduled_at") var scheduledAt: Instant? = null,
    @Column(name = "approval_idempotency_key", length = 120) var approvalIdempotencyKey: String? = null,
    @Column(name = "approval_environment", length = 30) var approvalEnvironment: String? = null,
    @Column(name = "approved_by") var approvedBy: UUID? = null,
    @Column(name = "approved_at") var approvedAt: Instant? = null,
    @Column(name = "approval_preflight_hash", length = 64) var approvalPreflightHash: String? = null,
    @Column(name = "preflight_hash", nullable = false, length = 64) var preflightHash: String,
    @Column(name = "actual_deployed_sha", length = 40) var actualDeployedSha: String? = null,
    @Column(name = "uncertain_outcome", nullable = false) var uncertainOutcome: Boolean = false,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "detail_json", nullable = false, columnDefinition = "jsonb") var detail: Map<String, Any?>,
    @Version @Column(nullable = false) var version: Long = 0,
) : AuditedEntity()

@Entity @Table(name = "release_events")
class ReleaseEvent(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "release_id", nullable = false) val releaseId: UUID,
    @Column(name = "workspace_id", nullable = false) val workspaceId: UUID,
    @Column(name = "actor_id") val actorId: UUID?,
    @Column(name = "actor_label", nullable = false, length = 120) val actorLabel: String,
    @Enumerated(EnumType.STRING) @Column(name = "previous_status", length = 40) val previousStatus: ReleaseStatus?,
    @Enumerated(EnumType.STRING) @Column(name = "next_status", nullable = false, length = 40) val nextStatus: ReleaseStatus,
    @Column(name = "commit_sha", nullable = false, length = 40) val commitSha: String,
    @Column(nullable = false, length = 40) val result: String,
    @Column(name = "report_path", length = 500) val reportPath: String? = null,
    @Column(length = 1000) val reason: String? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)
