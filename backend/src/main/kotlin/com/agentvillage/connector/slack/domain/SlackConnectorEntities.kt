package com.agentvillage.connector.slack.domain

import com.agentvillage.common.domain.AuditedEntity
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

enum class ConnectorProvider { SLACK, NOTION }
enum class ConnectorStatus { ACTIVE, REVOKED, INVALID }
enum class ConnectorEventStatus { RECEIVED, IGNORED }

@Entity @Table(name = "connector_oauth_states")
class ConnectorOauthState(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "workspace_id", nullable = false) val workspaceId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) val provider: ConnectorProvider,
    @Column(name = "state_hash", nullable = false, unique = true, length = 64) val stateHash: String,
    @Column(name = "redirect_uri", nullable = false, length = 500) val redirectUri: String,
    @Column(name = "expires_at", nullable = false) val expiresAt: Instant,
    @Column(name = "consumed_at") var consumedAt: Instant? = null,
) : AuditedEntity()

@Entity @Table(name = "connector_connections")
class ConnectorConnection(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "workspace_id", nullable = false) val workspaceId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) val provider: ConnectorProvider,
    @Column(name = "external_account_id", nullable = false, length = 120) val externalAccountId: String,
    @Column(name = "display_name", nullable = false, length = 200) var displayName: String,
    @Column(name = "encrypted_access_token", nullable = false, columnDefinition = "text") var encryptedAccessToken: String,
    @Column(name = "encrypted_refresh_token", columnDefinition = "text") var encryptedRefreshToken: String? = null,
    @Column(name = "key_version", nullable = false, length = 30) var keyVersion: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") var scopes: List<String>,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb") var metadata: Map<String, Any?>,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) var status: ConnectorStatus = ConnectorStatus.ACTIVE,
    @Column(name = "last_verified_at") var lastVerifiedAt: Instant? = null,
) : AuditedEntity()

@Entity @Table(name = "connector_events")
class ConnectorEvent(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "connection_id", nullable = false) val connectionId: UUID,
    @Column(name = "provider_event_id", nullable = false, unique = true, length = 160) val providerEventId: String,
    @Column(name = "event_type", nullable = false, length = 80) val eventType: String,
    @Column(name = "channel_id", length = 120) val channelId: String?,
    @Column(name = "actor_external_id", length = 120) val actorExternalId: String?,
    @Column(name = "message_ts", length = 80) val messageTs: String?,
    @Column(name = "thread_ts", length = 80) val threadTs: String?,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb") val payload: Map<String, Any?>,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) val status: ConnectorEventStatus = ConnectorEventStatus.RECEIVED,
    @Column(name = "received_at", nullable = false) val receivedAt: Instant = Instant.now(),
)
