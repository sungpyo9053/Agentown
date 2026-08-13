package com.agentvillage.identity.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "email_verifications")
class EmailVerification(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false, length = 320) val email: String,
    @Column(name = "code_hash", nullable = false, length = 64) val codeHash: String,
    @Column(name = "expires_at", nullable = false) val expiresAt: Instant,
    @Column(name = "verified_at") var verifiedAt: Instant? = null,
    @Column(name = "consumed_at") var consumedAt: Instant? = null,
)
