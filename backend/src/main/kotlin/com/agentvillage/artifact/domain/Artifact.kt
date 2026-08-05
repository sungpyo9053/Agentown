package com.agentvillage.artifact.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class ArtifactStatus { AVAILABLE, EXPIRED, FAILED }
@Entity @Table(name = "artifacts")
class Artifact(
    @Id val id: UUID = UUID.randomUUID(), @Column(name = "execution_id", nullable = false) val executionId: UUID,
    @Column(name = "owner_user_id", nullable = false) val ownerUserId: UUID, @Column(nullable = false) val type: String,
    @Column(name = "file_name", nullable = false) val fileName: String, @Column(name = "mime_type", nullable = false) val mimeType: String,
    @Column(name = "external_url", nullable = false, columnDefinition = "text") val externalUrl: String,
    @Column(name = "expires_at") val expiresAt: Instant? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: ArtifactStatus = ArtifactStatus.AVAILABLE,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)
