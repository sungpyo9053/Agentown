package com.agentvillage.llmcredential.domain

import com.agentvillage.common.domain.AuditedEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

enum class LlmProvider { OPENAI, ANTHROPIC, GOOGLE }
enum class CredentialStatus { UNVERIFIED, ACTIVE, INVALID, REVOKED }

@Entity
@Table(name = "llm_credentials")
class LlmCredential(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "owner_id", nullable = false)
    val ownerId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val provider: LlmProvider,

    @Column(name = "encrypted_secret", nullable = false, columnDefinition = "text")
    val encryptedSecret: String,

    @Column(name = "masked_secret", nullable = false, length = 100)
    val maskedSecret: String,

    @Column(name = "key_version", nullable = false, length = 30)
    val keyVersion: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: CredentialStatus,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provider_options", nullable = false, columnDefinition = "jsonb")
    val providerOptions: Map<String, Any> = emptyMap(),

    @Column(name = "last_verified_at")
    var lastVerifiedAt: Instant? = null,
) : AuditedEntity()

