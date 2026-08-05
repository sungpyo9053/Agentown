package com.agentvillage.execution.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class ExecutionStatus { QUEUED, RUNNING, WAITING_APPROVAL, SUCCEEDED, FAILED, CANCELLED, TIMEOUT }
enum class StepStatus { PENDING, RUNNING, WAITING_APPROVAL, SUCCEEDED, FAILED, CANCELLED, TIMEOUT }

@Entity @Table(name = "executions")
class Execution(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "harness_id", nullable = false) val harnessId: UUID,
    @Column(name = "harness_version_id") val harnessVersionId: UUID?,
    @Column(name = "owner_id", nullable = false) val ownerId: UUID,
    @Column(name = "idempotency_key", nullable = false) val idempotencyKey: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: ExecutionStatus = ExecutionStatus.QUEUED,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "input_json", columnDefinition = "jsonb") val inputJson: Map<String, Any>,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "output_json", columnDefinition = "jsonb") var outputJson: Map<String, Any>? = null,
    @Column(name = "current_step_key") var currentStepKey: String? = null,
    @Column(name = "error_code") var errorCode: String? = null,
    @Column(name = "error_message") var errorMessage: String? = null,
    @Column(name = "queued_at", nullable = false) val queuedAt: Instant = Instant.now(),
    @Column(name = "started_at") var startedAt: Instant? = null,
    @Column(name = "finished_at") var finishedAt: Instant? = null,
    @Column(name = "heartbeat_at") var heartbeatAt: Instant? = null,
    @Column(name = "timeout_at") var timeoutAt: Instant? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
)

@Entity @Table(name = "execution_steps")
class ExecutionStep(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "execution_id", nullable = false) val executionId: UUID,
    @Column(name = "harness_step_id") val harnessStepId: UUID?,
    @Column(name = "step_key", nullable = false) val stepKey: String,
    @Column(name = "step_type", nullable = false) val stepType: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: StepStatus = StepStatus.PENDING,
    @Column(nullable = false) var attempt: Int = 1,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "input_json", columnDefinition = "jsonb") var inputJson: Map<String, Any> = emptyMap(),
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "output_json", columnDefinition = "jsonb") var outputJson: Map<String, Any>? = null,
    @Column var provider: String? = null, @Column var model: String? = null,
    @Column(name = "input_tokens") var inputTokens: Long? = null, @Column(name = "output_tokens") var outputTokens: Long? = null,
    @Column(name = "estimated_cost") var estimatedCost: BigDecimal? = null,
    @Column(name = "provider_request_id") var providerRequestId: String? = null,
    @Column(name = "started_at") var startedAt: Instant? = null, @Column(name = "finished_at") var finishedAt: Instant? = null,
    @Column(name = "error_code") var errorCode: String? = null, @Column(name = "error_message") var errorMessage: String? = null,
)

@Entity @Table(name = "execution_events")
class ExecutionEvent(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "execution_id", nullable = false) val executionId: UUID,
    @Column(name = "sequence_no", nullable = false) val sequenceNo: Long,
    @Column(name = "event_type", nullable = false) val eventType: String,
    @Column(name = "agent_id") val agentId: UUID? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") val payload: Map<String, Any> = emptyMap(),
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)
