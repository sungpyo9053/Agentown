package com.agentvillage.execution.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class LocalRunnerProvider { CODEX, CLAUDE }
enum class LocalRunnerStatus { PENDING, ACTIVE, OFFLINE, REVOKED }

@Entity
@Table(name = "local_runner_connections")
class LocalRunnerConnection(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "owner_id", nullable = false) val ownerId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val provider: LocalRunnerProvider,
    @Column(name = "device_name", nullable = false) val deviceName: String,
    @Column(name = "token_hash", nullable = false, unique = true) val tokenHash: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: LocalRunnerStatus = LocalRunnerStatus.PENDING,
    @Column(name = "last_seen_at") var lastSeenAt: Instant? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
)
