package com.agentvillage.connector.notion.application

import com.agentvillage.builder.application.BuilderWorkspaceAccess
import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.common.exception.ConflictException
import com.agentvillage.common.exception.NotFoundException
import com.agentvillage.common.exception.UnauthorizedException
import com.agentvillage.connector.notion.infrastructure.*
import com.agentvillage.connector.notion.domain.*
import com.agentvillage.connector.slack.domain.*
import com.agentvillage.connector.slack.infrastructure.ConnectorConnectionRepository
import com.agentvillage.connector.slack.infrastructure.ConnectorOauthStateRepository
import com.agentvillage.llmcredential.application.EncryptedSecret
import com.agentvillage.llmcredential.application.SecretEncryptor
import org.springframework.beans.factory.annotation.Value
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

data class NotionConnectionView(val id: UUID, val workspaceId: String, val workspaceName: String, val status: ConnectorStatus, val lastVerifiedAt: Instant?, val connectedAt: Instant)
data class NotionConnectorStatus(val configured: Boolean, val connected: Boolean, val connections: List<NotionConnectionView>)
data class NotionOauthStart(val authorizationUrl: String, val expiresAt: Instant)
data class NotionReadVerification(val connectionId: UUID, val botName: String?, val workspaceName: String, val accessibleItems: List<NotionSearchItem>, val verifiedAt: Instant)
data class NotionPagePreviewRequest(val parentPageId: String = "", val title: String = "", val paragraphs: List<String> = emptyList())
data class NotionPageWriteView(
    val id: UUID, val connectionId: UUID, val parentPageId: String, val title: String, val paragraphs: List<String>,
    val status: NotionPageWriteStatus, val notionPageId: String?, val notionUrl: String?, val failureCode: String?, val failureMessage: String?,
)

@Service
class NotionConnectorService(
    private val workspaces: BuilderWorkspaceAccess,
    private val oauthStates: ConnectorOauthStateRepository,
    private val connections: ConnectorConnectionRepository,
    private val gateway: NotionOauthGateway,
    private val pageWrites: NotionPageWriteRepository,
    private val encryptor: SecretEncryptor,
    private val clock: Clock,
    @Value("\${connectors.notion.enabled:false}") private val enabled: Boolean,
    @Value("\${connectors.notion.client-id:}") private val clientId: String,
    @Value("\${connectors.notion.client-secret:}") private val clientSecret: String,
    @Value("\${connectors.notion.redirect-uri:http://localhost:8080/api/connectors/notion/oauth/callback}") private val redirectUri: String,
) {
    private val random = SecureRandom()

    @Transactional
    fun start(ownerId: UUID): NotionOauthStart {
        requireConfigured()
        val workspaceId = workspaces.requireWorkspaceId(ownerId)
        val rawState = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val expiresAt = clock.instant().plus(Duration.ofMinutes(10))
        oauthStates.save(ConnectorOauthState(workspaceId = workspaceId, provider = ConnectorProvider.NOTION, stateHash = sha256(rawState), redirectUri = redirectUri, expiresAt = expiresAt))
        val query = linkedMapOf("owner" to "user", "client_id" to clientId, "redirect_uri" to redirectUri, "response_type" to "code", "state" to rawState)
            .entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        return NotionOauthStart("https://api.notion.com/v1/oauth/authorize?$query", expiresAt)
    }

    @Transactional
    fun complete(code: String?, rawState: String?, error: String?): ConnectorConnection {
        if (!error.isNullOrBlank()) throw BadRequestException("NOTION_OAUTH_DENIED", "Notion 연결이 승인되지 않았습니다.")
        requireConfigured()
        if (code.isNullOrBlank() || rawState.isNullOrBlank()) throw BadRequestException("NOTION_OAUTH_INVALID", "Notion OAuth 응답이 올바르지 않습니다.")
        val state = oauthStates.findByStateHash(sha256(rawState)) ?: throw UnauthorizedException("NOTION_OAUTH_STATE_INVALID", "Notion 연결 상태값이 유효하지 않습니다.")
        if (state.provider != ConnectorProvider.NOTION) throw UnauthorizedException("NOTION_OAUTH_STATE_INVALID", "Notion 연결 상태값이 유효하지 않습니다.")
        if (state.consumedAt != null) throw ConflictException("NOTION_OAUTH_STATE_USED", "이미 처리된 Notion 연결 요청입니다.")
        if (!state.expiresAt.isAfter(clock.instant())) throw UnauthorizedException("NOTION_OAUTH_STATE_EXPIRED", "Notion 연결 요청이 만료되었습니다. 다시 시작해 주세요.")
        state.consumedAt = clock.instant()
        val result = gateway.exchange(code, state.redirectUri)
        if (result.accessToken.isBlank() || result.workspaceId.isBlank() || result.botId.isBlank()) throw BadRequestException("NOTION_OAUTH_EXCHANGE_FAILED", "Notion 토큰 교환 응답이 올바르지 않습니다.")
        val access = encrypt(result.accessToken)
        val refresh = result.refreshToken?.takeIf(String::isNotBlank)?.let(::encrypt)
        val metadata = mapOf("botId" to result.botId, "workspaceIcon" to result.workspaceIcon, "ownerType" to result.owner["type"])
        val existing = connections.findByWorkspaceIdAndProviderAndExternalAccountId(state.workspaceId, ConnectorProvider.NOTION, result.workspaceId)
        return if (existing == null) connections.save(ConnectorConnection(
            workspaceId = state.workspaceId, provider = ConnectorProvider.NOTION, externalAccountId = result.workspaceId,
            displayName = result.workspaceName?.takeIf(String::isNotBlank) ?: result.workspaceId,
            encryptedAccessToken = access.cipherText, encryptedRefreshToken = refresh?.cipherText, keyVersion = access.keyVersion,
            scopes = listOf("read_content", "insert_content", "update_content"), metadata = metadata, lastVerifiedAt = null,
        )) else existing.apply {
            displayName = result.workspaceName?.takeIf(String::isNotBlank) ?: result.workspaceId
            encryptedAccessToken = access.cipherText; encryptedRefreshToken = refresh?.cipherText; keyVersion = access.keyVersion
            scopes = listOf("read_content", "insert_content", "update_content"); this.metadata = metadata; status = ConnectorStatus.ACTIVE; lastVerifiedAt = null
        }
    }

    @Transactional(readOnly = true)
    fun status(ownerId: UUID): NotionConnectorStatus {
        val workspaceId = workspaces.findWorkspaceId(ownerId)
        val list = workspaceId?.let { connections.findAllByWorkspaceIdAndProviderOrderByCreatedAtDesc(it, ConnectorProvider.NOTION) }.orEmpty().map(::view)
        return NotionConnectorStatus(isConfigured(), list.any { it.status == ConnectorStatus.ACTIVE }, list)
    }

    @Transactional
    fun verifyRead(ownerId: UUID, connectionId: UUID, query: String = ""): NotionReadVerification {
        val connection = requireOwned(ownerId, connectionId)
        require(connection.status == ConnectorStatus.ACTIVE) { "Notion connection is not active" }
        val result = withToken(connection) { token ->
            val bot = gateway.self(token)
            val items = gateway.search(token, query, 10)
            bot to items
        }
        connection.lastVerifiedAt = clock.instant()
        return NotionReadVerification(connection.id, result.first.name, connection.displayName, result.second, connection.lastVerifiedAt!!)
    }

    @Transactional
    fun previewPage(ownerId: UUID, connectionId: UUID, idempotencyKey: String, request: NotionPagePreviewRequest): NotionPageWriteView {
        requireIdempotency(idempotencyKey)
        val connection = requireOwned(ownerId, connectionId)
        val workspaceId = connection.workspaceId
        pageWrites.findByWorkspaceIdAndIdempotencyKey(workspaceId, idempotencyKey)?.let { return view(it) }
        val normalized = validatePage(request)
        return view(pageWrites.save(NotionPageWriteRequest(
            workspaceId = workspaceId, connectionId = connectionId, idempotencyKey = idempotencyKey,
            parentPageId = request.parentPageId.trim(), title = request.title.trim(), content = mapOf("paragraphs" to normalized),
        )))
    }

    @Transactional
    fun approvePage(ownerId: UUID, requestId: UUID, idempotencyKey: String): NotionPageWriteView {
        requireIdempotency(idempotencyKey)
        val workspaceId = workspaces.findWorkspaceId(ownerId) ?: throw NotFoundException("WORKSPACE_NOT_FOUND", "워크스페이스를 찾을 수 없습니다.")
        val request = pageWrites.findOwnedForUpdate(requestId, workspaceId)
            ?: throw NotFoundException("NOTION_WRITE_NOT_FOUND", "Notion 발행 요청을 찾을 수 없습니다.")
        if (request.approvalIdempotencyKey != null && request.approvalIdempotencyKey != idempotencyKey) {
            throw ConflictException("NOTION_WRITE_ALREADY_DECIDED", "이미 다른 승인 요청으로 처리된 Notion 발행입니다.")
        }
        if (request.status == NotionPageWriteStatus.SUCCEEDED || request.status == NotionPageWriteStatus.FAILED) return view(request)
        if (request.status != NotionPageWriteStatus.PREVIEWED) throw ConflictException("NOTION_WRITE_INVALID_STATE", "미리보기 상태에서만 승인할 수 있습니다.")
        val connection = requireOwned(ownerId, request.connectionId)
        request.approvalIdempotencyKey = idempotencyKey
        request.approvedBy = ownerId
        request.approvedAt = clock.instant()
        request.status = NotionPageWriteStatus.APPROVED
        request.status = NotionPageWriteStatus.PUBLISHING
        return try {
            val result = withToken(connection) { gateway.createPage(it, request.parentPageId, request.title, paragraphs(request)) }
            request.notionPageId = result.id
            request.notionUrl = result.url
            request.status = NotionPageWriteStatus.SUCCEEDED
            view(request)
        } catch (error: Exception) {
            request.status = NotionPageWriteStatus.FAILED
            request.failureCode = "NOTION_PAGE_CREATE_FAILED"
            request.failureMessage = (error.message ?: "Notion 페이지 생성 실패").take(500)
            view(request)
        }
    }

    @Transactional(readOnly = true)
    fun pageWrite(ownerId: UUID, requestId: UUID): NotionPageWriteView {
        val workspaceId = workspaces.findWorkspaceId(ownerId) ?: throw NotFoundException("WORKSPACE_NOT_FOUND", "워크스페이스를 찾을 수 없습니다.")
        return pageWrites.findById(requestId).orElse(null)?.takeIf { it.workspaceId == workspaceId }?.let(::view)
            ?: throw NotFoundException("NOTION_WRITE_NOT_FOUND", "Notion 발행 요청을 찾을 수 없습니다.")
    }

    @Transactional
    fun revoke(ownerId: UUID, connectionId: UUID) {
        val connection = requireOwned(ownerId, connectionId)
        withToken(connection) { gateway.revoke(it) }
        connection.status = ConnectorStatus.REVOKED
        connection.encryptedAccessToken = encrypt("revoked").cipherText
        connection.encryptedRefreshToken = null
    }

    private fun <T> withToken(connection: ConnectorConnection, action: (String) -> T): T {
        fun accessToken(): CharArray = encryptor.decrypt(EncryptedSecret(connection.encryptedAccessToken, connection.keyVersion))
        var chars = accessToken()
        try {
            return try { action(String(chars)) } catch (_: NotionTokenInvalidException) {
                chars.fill('\u0000')
                val refreshCipher = connection.encryptedRefreshToken ?: run { connection.status = ConnectorStatus.INVALID; throw UnauthorizedException("NOTION_TOKEN_INVALID", "Notion 연결이 만료되었습니다. 다시 연결해 주세요.") }
                val refreshChars = encryptor.decrypt(EncryptedSecret(refreshCipher, connection.keyVersion))
                val refreshed = try { gateway.refresh(String(refreshChars)) } finally { refreshChars.fill('\u0000') }
                val access = encrypt(refreshed.accessToken)
                connection.encryptedAccessToken = access.cipherText; connection.keyVersion = access.keyVersion
                refreshed.refreshToken?.takeIf(String::isNotBlank)?.let { connection.encryptedRefreshToken = encrypt(it).cipherText }
                action(refreshed.accessToken)
            }
        } finally { chars.fill('\u0000') }
    }

    private fun requireOwned(ownerId: UUID, id: UUID): ConnectorConnection {
        val workspaceId = workspaces.findWorkspaceId(ownerId) ?: throw NotFoundException("WORKSPACE_NOT_FOUND", "워크스페이스를 찾을 수 없습니다.")
        return connections.findByIdAndWorkspaceId(id, workspaceId)?.takeIf { it.provider == ConnectorProvider.NOTION }
            ?: throw NotFoundException("NOTION_CONNECTION_NOT_FOUND", "Notion 연결을 찾을 수 없습니다.")
    }
    private fun encrypt(value: String): EncryptedSecret = value.toCharArray().let { chars -> try { encryptor.encrypt(chars) } finally { chars.fill('\u0000') } }
    private fun requireConfigured() { if (!isConfigured()) throw BadRequestException("NOTION_CONNECTOR_NOT_CONFIGURED", "서버의 Notion OAuth 앱 설정이 완료되지 않았습니다.") }
    private fun isConfigured() = enabled && clientId.isNotBlank() && clientSecret.isNotBlank() && redirectUri.startsWith("https://")
    private fun view(connection: ConnectorConnection) = NotionConnectionView(connection.id, connection.externalAccountId, connection.displayName, connection.status, connection.lastVerifiedAt, connection.createdAt)
    private fun view(request: NotionPageWriteRequest) = NotionPageWriteView(
        request.id, request.connectionId, request.parentPageId, request.title, paragraphs(request), request.status,
        request.notionPageId, request.notionUrl, request.failureCode, request.failureMessage,
    )
    @Suppress("UNCHECKED_CAST")
    private fun paragraphs(request: NotionPageWriteRequest) = request.content["paragraphs"] as? List<String> ?: emptyList()
    private fun validatePage(request: NotionPagePreviewRequest): List<String> {
        if (!request.parentPageId.trim().matches(Regex("[A-Za-z0-9-]{20,120}"))) throw BadRequestException("NOTION_PARENT_REQUIRED", "유효한 Notion 상위 페이지 ID가 필요합니다.")
        if (request.title.isBlank() || request.title.length > 200) throw BadRequestException("NOTION_TITLE_INVALID", "제목은 1~200자여야 합니다.")
        if (request.paragraphs.isEmpty() || request.paragraphs.size > 100 || request.paragraphs.any { it.isBlank() || it.length > 1900 }) {
            throw BadRequestException("NOTION_CONTENT_INVALID", "본문은 1~100개의 문단이며 각 문단은 1~1900자여야 합니다.")
        }
        return request.paragraphs.map(String::trim)
    }
    private fun requireIdempotency(key: String) { if (key.isBlank() || key.length > 120) throw BadRequestException("IDEMPOTENCY_KEY_REQUIRED", "유효한 Idempotency-Key가 필요합니다.") }
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
