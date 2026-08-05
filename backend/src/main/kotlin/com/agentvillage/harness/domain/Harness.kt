package com.agentvillage.harness.domain

import com.agentvillage.common.domain.AuditedEntity
import com.agentvillage.common.domain.Visibility
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

enum class HarnessStatus { DRAFT, PUBLISHED, DEPRECATED, BLOCKED }
enum class HarnessStepType { LLM, EXTERNAL_API, DOWNLOAD, APPROVAL }
enum class EdgeCondition { SUCCESS, APPROVE, REJECT, FAILURE }

@Entity @Table(name = "harnesses")
class Harness(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "owner_id", nullable = false) val ownerId: UUID,
    @Column(nullable = false, length = 100) var name: String,
    @Column(length = 1000) var description: String? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) var visibility: Visibility = Visibility.PRIVATE,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) var status: HarnessStatus = HarnessStatus.DRAFT,
) : AuditedEntity()

@Entity @Table(name = "harness_steps")
class HarnessStep(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "harness_id", nullable = false) val harnessId: UUID,
    @Column(name = "agent_id") val agentId: UUID? = null,
    @Column(name = "step_key", nullable = false) val stepKey: String,
    @Enumerated(EnumType.STRING) @Column(name = "step_type", nullable = false) val stepType: HarnessStepType,
    @Column(name = "sequence_no", nullable = false) val sequenceNo: Int,
    @Column(name = "max_retries", nullable = false) val maxRetries: Int = 0,
    @Column(name = "timeout_seconds", nullable = false) val timeoutSeconds: Int = 120,
    @Column(name = "requires_approval", nullable = false) val requiresApproval: Boolean = false,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "input_mapping", nullable = false, columnDefinition = "jsonb")
    val inputMapping: Map<String, Any> = emptyMap(),
)

@Entity @Table(name = "harness_edges")
class HarnessEdge(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "harness_id", nullable = false) val harnessId: UUID,
    @Column(name = "source_step_id", nullable = false) val sourceStepId: UUID,
    @Column(name = "target_step_id") val targetStepId: UUID?,
    @Enumerated(EnumType.STRING) @Column(name = "condition_type", nullable = false) val conditionType: EdgeCondition = EdgeCondition.SUCCESS,
)

@Entity @Table(name = "harness_versions")
class HarnessVersion(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "harness_id", nullable = false) val harnessId: UUID,
    @Column(nullable = false, length = 30) val version: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val status: HarnessStatus = HarnessStatus.PUBLISHED,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "snapshot_json", nullable = false, columnDefinition = "jsonb")
    val snapshotJson: Map<String, Any>,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)
