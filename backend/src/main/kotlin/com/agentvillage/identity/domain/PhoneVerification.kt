package com.agentvillage.identity.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "phone_verifications")
class PhoneVerification(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "phone_hash", nullable = false, length = 64) val phoneHash: String,
    @Column(name = "code_hash", nullable = false, length = 64) val codeHash: String,
    @Column(name = "expires_at", nullable = false) val expiresAt: Instant,
    @Column(name = "verified_at") var verifiedAt: Instant? = null,
    @Column(name = "consumed_at") var consumedAt: Instant? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)
