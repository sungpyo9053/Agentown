package com.agentvillage.connector.slack.application

import com.agentvillage.builder.application.BuilderWorkspaceAccess
import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.common.exception.ConflictException
import com.agentvillage.common.exception.NotFoundException
import com.agentvillage.common.exception.UnauthorizedException
import com.agentvillage.connector.slack.domain.*
import com.agentvillage.connector.slack.infrastructure.*
import com.agentvillage.llmcredential.application.SecretEncryptor
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class SlackConnectionView(val id: UUID, val provider: String, val teamId: String, val teamName: String, val scopes: List<String>, val status: ConnectorStatus, val connectedAt: Instant)
data class SlackConnectorStatus(val configured: Boolean, val connected: Boolean, val connections: List<SlackConnectionView>, val eventRequestUrl: String)
data class SlackOauthStart(val authorizationUrl: String, val expiresAt: Instant)
data class SlackEventReceipt(val accepted: Boolean, val duplicate: Boolean = false, val ignoredReason: String? = null)

@Service
class SlackConnectorService(
    private val workspaces: BuilderWorkspaceAccess,
    private val oauthStates: ConnectorOauthStateRepository,
    private val connections: ConnectorConnectionRepository,
    private val events: ConnectorEventRepository,
    private val exchange: SlackTokenExchange,
    private val encryptor: SecretEncryptor,
    private val verifier: SlackSignatureVerifier,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    @Value("\${connectors.slack.enabled:false}") private val enabled: Boolean,
    @Value("\${connectors.slack.client-id:}") private val clientId: String,
    @Value("\${connectors.slack.client-secret:}") private val clientSecret: String,
    @Value("\${connectors.slack.signing-secret:}") private val signingSecret: String,
    @Value("\${connectors.slack.redirect-uri:http://localhost:8080/api/connectors/slack/oauth/callback}") private val redirectUri: String,
    @Value("\${connectors.slack.scopes:channels:history,chat:write}") private val configuredScopes: String,
    @Value("\${connectors.slack.event-request-url:http://localhost:8080/api/connectors/slack/events}") private val eventRequestUrl: String,
) {
    private val random = SecureRandom()

    @Transactional
    fun start(ownerId: UUID): SlackOauthStart {
        requireConfigured()
        val workspaceId = workspaces.requireWorkspaceId(ownerId)
        val rawState = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val expires = clock.instant().plus(Duration.ofMinutes(10))
        oauthStates.save(ConnectorOauthState(workspaceId = workspaceId, provider = ConnectorProvider.SLACK, stateHash = sha256(rawState), redirectUri = redirectUri, expiresAt = expires))
        val query = linkedMapOf("client_id" to clientId, "scope" to configuredScopes, "redirect_uri" to redirectUri, "state" to rawState)
            .entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        return SlackOauthStart("https://slack.com/oauth/v2/authorize?$query", expires)
    }

    @Transactional
    fun complete(code: String?, rawState: String?, error: String?): ConnectorConnection {
        if (!error.isNullOrBlank()) throw BadRequestException("SLACK_OAUTH_DENIED", "Slack 연결이 승인되지 않았습니다.")
        requireConfigured()
        if (code.isNullOrBlank() || rawState.isNullOrBlank()) throw BadRequestException("SLACK_OAUTH_INVALID", "Slack OAuth 응답이 올바르지 않습니다.")
        val state = oauthStates.findByStateHash(sha256(rawState)) ?: throw UnauthorizedException("SLACK_OAUTH_STATE_INVALID", "Slack 연결 상태값이 유효하지 않습니다.")
        if (state.consumedAt != null) throw ConflictException("SLACK_OAUTH_STATE_USED", "이미 처리된 Slack 연결 요청입니다.")
        if (!state.expiresAt.isAfter(clock.instant())) throw UnauthorizedException("SLACK_OAUTH_STATE_EXPIRED", "Slack 연결 요청이 만료되었습니다. 다시 시작해 주세요.")
        state.consumedAt = clock.instant()
        val result = exchange.exchange(code, state.redirectUri)
        if (!result.ok || result.accessToken.isBlank() || result.team.id.isBlank()) throw BadRequestException("SLACK_OAUTH_EXCHANGE_FAILED", "Slack 토큰 교환에 실패했습니다: ${result.error ?: "invalid_response"}")
        val token = result.accessToken.toCharArray()
        val encrypted = try { encryptor.encrypt(token) } finally { token.fill('\u0000') }
        val scopes = result.scope.split(',').map(String::trim).filter(String::isNotBlank)
        val metadata = mapOf("botUserId" to result.botUserId, "appId" to result.appId, "authedUserId" to result.authedUser.id)
        val existing = connections.findByWorkspaceIdAndProviderAndExternalAccountId(state.workspaceId, ConnectorProvider.SLACK, result.team.id)
        return if (existing == null) connections.save(ConnectorConnection(workspaceId = state.workspaceId, provider = ConnectorProvider.SLACK, externalAccountId = result.team.id, displayName = result.team.name.ifBlank { result.team.id }, encryptedAccessToken = encrypted.cipherText, keyVersion = encrypted.keyVersion, scopes = scopes, metadata = metadata))
        else existing.apply { displayName = result.team.name.ifBlank { result.team.id }; encryptedAccessToken = encrypted.cipherText; keyVersion = encrypted.keyVersion; this.scopes = scopes; this.metadata = metadata; status = ConnectorStatus.ACTIVE }
    }

    @Transactional(readOnly = true)
    fun status(ownerId: UUID): SlackConnectorStatus {
        val workspaceId = workspaces.findWorkspaceId(ownerId)
        val list = workspaceId?.let { connections.findAllByWorkspaceIdOrderByCreatedAtDesc(it) }.orEmpty().map(::view)
        return SlackConnectorStatus(isConfigured(), list.any { it.status == ConnectorStatus.ACTIVE }, list, eventRequestUrl)
    }

    @Transactional
    fun revoke(ownerId: UUID, connectionId: UUID) {
        val workspaceId = workspaces.findWorkspaceId(ownerId) ?: throw NotFoundException("WORKSPACE_NOT_FOUND", "워크스페이스를 찾을 수 없습니다.")
        val connection = connections.findByIdAndWorkspaceId(connectionId, workspaceId) ?: throw NotFoundException("SLACK_CONNECTION_NOT_FOUND", "Slack 연결을 찾을 수 없습니다.")
        connection.status = ConnectorStatus.REVOKED
    }

    @Transactional
    fun receive(timestamp: String?, signature: String?, rawBody: String): Pair<Map<String, Any?>?, SlackEventReceipt> {
        if (!verifier.isValid(timestamp, signature, rawBody)) throw UnauthorizedException("SLACK_SIGNATURE_INVALID", "Slack 요청 서명이 유효하지 않습니다.")
        val body: Map<String, Any?> = try { mapper.readValue(rawBody, object : TypeReference<Map<String, Any?>>() {}) } catch (_: Exception) { throw BadRequestException("SLACK_EVENT_INVALID", "Slack 이벤트 JSON이 올바르지 않습니다.") }
        if (body["type"] == "url_verification") return mapOf("challenge" to body["challenge"]) to SlackEventReceipt(true)
        if (body["type"] != "event_callback") return null to SlackEventReceipt(true, ignoredReason = "unsupported_wrapper")
        val eventId = body["event_id"]?.toString()?.takeIf(String::isNotBlank) ?: throw BadRequestException("SLACK_EVENT_ID_MISSING", "Slack event_id가 없습니다.")
        if (events.existsByProviderEventId(eventId)) return null to SlackEventReceipt(true, duplicate = true)
        val teamId = body["team_id"]?.toString().orEmpty()
        val connection = connections.findFirstByProviderAndExternalAccountIdAndStatus(ConnectorProvider.SLACK, teamId, ConnectorStatus.ACTIVE)
            ?: return null to SlackEventReceipt(true, ignoredReason = "connection_not_found")
        @Suppress("UNCHECKED_CAST") val event = body["event"] as? Map<String, Any?> ?: return null to SlackEventReceipt(true, ignoredReason = "event_missing")
        if (event["type"] != "message" || event["subtype"] != null || event["bot_id"] != null) return null to SlackEventReceipt(true, ignoredReason = "unsupported_event")
        val text = event["text"]?.toString()?.trim().orEmpty()
        if (text.isBlank()) return null to SlackEventReceipt(true, ignoredReason = "empty_message")
        val safePayload = mapOf("text" to text.take(4000), "channel" to event["channel"], "user" to event["user"], "ts" to event["ts"], "threadTs" to event["thread_ts"])
        return try {
            events.saveAndFlush(ConnectorEvent(connectionId = connection.id, providerEventId = eventId, eventType = "message", channelId = event["channel"]?.toString(), actorExternalId = event["user"]?.toString(), messageTs = event["ts"]?.toString(), threadTs = event["thread_ts"]?.toString(), payload = safePayload))
            null to SlackEventReceipt(true)
        } catch (_: DataIntegrityViolationException) { null to SlackEventReceipt(true, duplicate = true) }
    }

    private fun requireConfigured() { if (!isConfigured()) throw BadRequestException("SLACK_CONNECTOR_NOT_CONFIGURED", "서버의 Slack 앱 설정이 완료되지 않았습니다.") }
    private fun isConfigured() = enabled && clientId.isNotBlank() && clientSecret.isNotBlank() && signingSecret.isNotBlank() && redirectUri.startsWith("https://")
    private fun view(it: ConnectorConnection) = SlackConnectionView(it.id, it.provider.name, it.externalAccountId, it.displayName, it.scopes, it.status, it.createdAt)
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
