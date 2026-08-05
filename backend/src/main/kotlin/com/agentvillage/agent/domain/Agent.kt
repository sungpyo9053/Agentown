package com.agentvillage.agent.domain

import com.agentvillage.common.domain.AuditedEntity
import com.agentvillage.common.domain.Visibility
import com.agentvillage.llmcredential.domain.LlmProvider
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.util.UUID

@Entity
@Table(name = "agents")
class Agent(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "owner_id", nullable = false)
    val ownerId: UUID,

    @Column(nullable = false, length = 40)
    var name: String,

    @Column(nullable = false, length = 100)
    var role: String,

    @Column(length = 500)
    var personality: String? = null,

    @Column(name = "character_key", nullable = false, length = 60)
    var characterKey: String,

    @Column(name = "system_prompt", columnDefinition = "text")
    var systemPrompt: String? = null,

    @Column(nullable = false, columnDefinition = "text")
    var script: String,

    @Column(columnDefinition = "text")
    var guide: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "model_provider", nullable = false, length = 30)
    var modelProvider: LlmProvider = LlmProvider.OPENAI,

    @Column(name = "model_name", nullable = false, length = 80)
    var modelName: String,

    @Column(nullable = false, precision = 3, scale = 2)
    var temperature: BigDecimal = BigDecimal("0.70"),

    @Column(name = "credential_id")
    var credentialId: UUID? = null,

    @Column(name = "max_output_tokens", nullable = false)
    var maxOutputTokens: Int = 2048,

    @Column(name = "timeout_seconds", nullable = false)
    var timeoutSeconds: Int = 60,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provider_options", nullable = false, columnDefinition = "jsonb")
    var providerOptions: Map<String, Any> = emptyMap(),

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var visibility: Visibility = Visibility.PRIVATE,
) : AuditedEntity()
