package com.agentvillage.connector.notion.application

import com.agentvillage.builder.application.BuilderWorkspaceAccess
import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.common.exception.ConflictException
import com.agentvillage.common.exception.NotFoundException
import com.agentvillage.common.exception.ServiceUnavailableException
import com.agentvillage.common.exception.UnauthorizedException
import com.agentvillage.connector.notion.infrastructure.*
import com.agentvillage.connector.notion.domain.*
import com.agentvillage.connector.slack.domain.*
import com.agentvillage.connector.slack.infrastructure.ConnectorConnectionRepository
import com.agentvillage.connector.slack.infrastructure.ConnectorOauthStateRepository
import com.agentvillage.llmcredential.application.EncryptedSecret
import com.agentvillage.llmcredential.application.SecretEncryptor
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.HttpClientErrorException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

private const val NOTION_CONNECTION_EXPIRED_CODE = "NOTION_CONNECTION_EXPIRED"
private const val NOTION_CONNECTION_EXPIRED_MESSAGE = "Notion 연결이 만료되었습니다. 업무 연결에서 다시 연결한 뒤 재시도해 주세요."
private const val NOTION_PAGE_CREATE_AMBIGUOUS_CODE = "NOTION_PAGE_CREATE_AMBIGUOUS"
private const val NOTION_PAGE_CREATE_AMBIGUOUS_MESSAGE =
    "Notion 발행 결과를 확인하지 못했습니다. 중복 페이지 생성을 막기 위해 이 실행은 재시도할 수 없습니다. 대상 Notion 페이지에서 생성 여부를 확인해 주세요."

data class NotionConnectionView(val id: UUID, val workspaceId: String, val workspaceName: String, val status: ConnectorStatus, val lastVerifiedAt: Instant?, val connectedAt: Instant)
data class NotionConnectorStatus(val configured: Boolean, val connected: Boolean, val connections: List<NotionConnectionView>)
data class NotionOauthStart(val authorizationUrl: String, val expiresAt: Instant)
data class NotionReadVerification(val connectionId: UUID, val botName: String?, val workspaceName: String, val accessibleItems: List<NotionSearchItem>, val verifiedAt: Instant)
data class NotionPagePreviewRequest(val parentPageId: String = "", val title: String = "", val paragraphs: List<String> = emptyList())
data class NotionPageWriteView(
    val id: UUID, val connectionId: UUID, val parentPageId: String, val title: String, val paragraphs: List<String>,
    val status: NotionPageWriteStatus, val notionPageId: String?, val notionUrl: String?, val failureCode: String?, val failureMessage: String?,
)
private data class NotionPagePublishClaim(
    val view: NotionPageWriteView,
    val dispatch: Boolean,
    val connectionId: UUID? = null,
    val parentPageId: String? = null,
    val title: String? = null,
    val paragraphs: List<String> = emptyList(),
)
private data class NotionDispatchOutcome(
    val status: NotionPageWriteStatus,
    val page: NotionCreatedPage? = null,
    val error: Exception? = null,
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
    transactionManager: PlatformTransactionManager,
    @Value("\${connectors.notion.enabled:false}") private val enabled: Boolean,
    @Value("\${connectors.notion.client-id:}") private val clientId: String,
    @Value("\${connectors.notion.client-secret:}") private val clientSecret: String,
    @Value("\${connectors.notion.redirect-uri:http://localhost:8080/api/connectors/notion/oauth/callback}") private val redirectUri: String,
) {
    private val random = SecureRandom()
    private val transactions = TransactionTemplate(transactionManager)
    private val invalidationTransactions = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

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

    @Transactional(readOnly = true)
    fun requireWritableConnection(ownerId: UUID, connectionId: UUID) {
        val connection = requireOwned(ownerId, connectionId)
        if (connection.status == ConnectorStatus.INVALID) throw expiredError()
        if (connection.status != ConnectorStatus.ACTIVE || !connection.scopes.contains("insert_content")) {
            throw ConflictException("NOTION_CONNECTION_NOT_WRITABLE", "쓰기 권한이 있는 활성 Notion 연결이 필요합니다.")
        }
    }

    @Transactional
    fun verifyRead(ownerId: UUID, connectionId: UUID, query: String = ""): NotionReadVerification {
        val connection = requireOwned(ownerId, connectionId)
        if (connection.status == ConnectorStatus.INVALID) throw expiredError()
        if (connection.status != ConnectorStatus.ACTIVE) {
            throw ConflictException("NOTION_CONNECTION_NOT_ACTIVE", "활성 Notion 연결만 읽기 검증할 수 있습니다.")
        }
        val result = try {
            withToken(connection) { token ->
                val bot = gateway.self(token)
                val items = gateway.search(token, query, 10)
                bot to items
            }
        } catch (error: HttpClientErrorException) {
            if (error.statusCode.value() == 429) {
                throw ServiceUnavailableException("NOTION_RATE_LIMITED", "Notion 요청이 일시적으로 제한되었습니다. 잠시 후 다시 시도해 주세요.")
            }
            throw error
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

    fun approvePage(ownerId: UUID, requestId: UUID, idempotencyKey: String): NotionPageWriteView {
        requireIdempotency(idempotencyKey)
        val workspaceId = workspaces.findWorkspaceId(ownerId)
            ?: throw NotFoundException("WORKSPACE_NOT_FOUND", "워크스페이스를 찾을 수 없습니다.")
        val claim = transactions.execute { claimPage(ownerId, workspaceId, requestId, idempotencyKey) }
            ?: throw IllegalStateException("Notion 발행 의도를 저장하지 못했습니다.")
        if (!claim.dispatch) return claim.view

        var dispatched = false
        val outcome = try {
            val connection = requireOwned(ownerId, requireNotNull(claim.connectionId))
            val page = withToken(connection, onTokenRejected = { dispatched = false }) {
                dispatched = true
                gateway.createPage(it, requireNotNull(claim.parentPageId), requireNotNull(claim.title), claim.paragraphs)
            }
            NotionDispatchOutcome(NotionPageWriteStatus.SUCCEEDED, page = page)
        } catch (error: Exception) {
            val knownRejection = error is HttpClientErrorException && error.statusCode.isKnownProviderRejection()
            val status = if (!dispatched || knownRejection) NotionPageWriteStatus.FAILED else NotionPageWriteStatus.AMBIGUOUS
            NotionDispatchOutcome(status, error = error)
        }
        return transactions.execute { completePage(workspaceId, requestId, outcome.status, outcome.page, outcome.error) }
            ?: throw IllegalStateException("Notion 발행 결과를 저장하지 못했습니다.")
    }

    private fun claimPage(ownerId: UUID, workspaceId: UUID, requestId: UUID, idempotencyKey: String): NotionPagePublishClaim {
        val request = pageWrites.findOwnedForUpdate(requestId, workspaceId)
            ?: throw NotFoundException("NOTION_WRITE_NOT_FOUND", "Notion 발행 요청을 찾을 수 없습니다.")
        if (request.approvalIdempotencyKey != null && request.approvalIdempotencyKey != idempotencyKey) {
            throw ConflictException("NOTION_WRITE_ALREADY_DECIDED", "이미 다른 승인 요청으로 처리된 Notion 발행입니다.")
        }
        if (request.status != NotionPageWriteStatus.PREVIEWED) {
            return NotionPagePublishClaim(view(request), false)
        }
        requireOwned(ownerId, request.connectionId)
        request.approvalIdempotencyKey = idempotencyKey
        request.approvedBy = ownerId
        request.approvedAt = clock.instant()
        request.status = NotionPageWriteStatus.PUBLISHING
        pageWrites.flush()
        return NotionPagePublishClaim(
            view(request), true, request.connectionId, request.parentPageId, request.title, paragraphs(request),
        )
    }

    private fun completePage(
        workspaceId: UUID,
        requestId: UUID,
        status: NotionPageWriteStatus,
        page: NotionCreatedPage?,
        error: Exception?,
    ): NotionPageWriteView {
        val request = pageWrites.findOwnedForUpdate(requestId, workspaceId)
            ?: throw NotFoundException("NOTION_WRITE_NOT_FOUND", "Notion 발행 요청을 찾을 수 없습니다.")
        if (request.status != NotionPageWriteStatus.PUBLISHING) return view(request)
        request.status = status
        if (status == NotionPageWriteStatus.SUCCEEDED) {
            request.notionPageId = requireNotNull(page).id
            request.notionUrl = page.url
        } else if (status == NotionPageWriteStatus.FAILED) {
            if (error is ConflictException && error.code == NOTION_CONNECTION_EXPIRED_CODE) {
                request.failureCode = NOTION_CONNECTION_EXPIRED_CODE
                request.failureMessage = NOTION_CONNECTION_EXPIRED_MESSAGE
            } else {
                request.failureCode = "NOTION_PAGE_CREATE_REJECTED"
                request.failureMessage = sanitizeFailure(error?.message ?: "Notion이 페이지 생성을 거부했습니다.")
            }
        } else {
            request.failureCode = "NOTION_PAGE_CREATE_AMBIGUOUS"
            request.failureMessage = "Notion 요청은 전송되었지만 페이지 생성 여부를 확인할 수 없습니다. 대상 페이지를 확인한 뒤 운영자에게 문의하세요."
        }
        return view(request)
    }

    @Transactional
    fun reconcileStalePublishing(workspaceId: UUID, requestId: UUID, staleBefore: Instant): Boolean {
        val request = pageWrites.findOwnedForUpdate(requestId, workspaceId) ?: return false
        if (request.status != NotionPageWriteStatus.PUBLISHING || !request.updatedAt.isBefore(staleBefore)) return false
        request.status = NotionPageWriteStatus.AMBIGUOUS
        request.failureCode = NOTION_PAGE_CREATE_AMBIGUOUS_CODE
        request.failureMessage = NOTION_PAGE_CREATE_AMBIGUOUS_MESSAGE
        return true
    }

    private fun HttpStatusCode.isKnownProviderRejection(): Boolean = is4xxClientError && value() != 408 && value() != 429

    private fun sanitizeFailure(value: String): String = value
        .replace(Regex("(?i)(access[_ -]?token|refresh[_ -]?token|api[_ -]?key|secret|password)\\s*[:=]\\s*[^\\s,;]+"), "$1=***")
        .take(500)

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

    private fun <T> withToken(connection: ConnectorConnection, onTokenRejected: () -> Unit = {}, action: (String) -> T): T {
        if (connection.status == ConnectorStatus.INVALID) throw expiredConnection(connection)
        fun accessToken(): CharArray = encryptor.decrypt(EncryptedSecret(connection.encryptedAccessToken, connection.keyVersion))
        var chars = accessToken()
        try {
            return try { action(String(chars)) } catch (_: NotionTokenInvalidException) {
                onTokenRejected()
                chars.fill('\u0000')
                val refreshCipher = connection.encryptedRefreshToken ?: throw expiredConnection(connection)
                val refreshChars = encryptor.decrypt(EncryptedSecret(refreshCipher, connection.keyVersion))
                val refreshed = try {
                    gateway.refresh(String(refreshChars))
                } catch (_: NotionTokenInvalidException) {
                    throw expiredConnection(connection)
                } catch (error: HttpClientErrorException) {
                    if (error.statusCode.isKnownProviderRejection()) throw expiredConnection(connection)
                    throw error
                } finally {
                    refreshChars.fill('\u0000')
                }
                if (refreshed.accessToken.isBlank()) throw expiredConnection(connection)
                val result = try {
                    action(refreshed.accessToken)
                } catch (_: NotionTokenInvalidException) {
                    onTokenRejected()
                    throw expiredConnection(connection)
                }
                val access = encrypt(refreshed.accessToken)
                connection.encryptedAccessToken = access.cipherText; connection.keyVersion = access.keyVersion
                refreshed.refreshToken?.takeIf(String::isNotBlank)?.let { connection.encryptedRefreshToken = encrypt(it).cipherText }
                connections.saveAndFlush(connection)
                result
            }
        } finally { chars.fill('\u0000') }
    }

    private fun expiredConnection(connection: ConnectorConnection): ConflictException {
        invalidationTransactions.executeWithoutResult {
            val stored = connections.findByIdAndWorkspaceId(connection.id, connection.workspaceId)
                ?.takeIf { it.provider == ConnectorProvider.NOTION }
            if (stored != null) {
                stored.status = ConnectorStatus.INVALID
                connections.saveAndFlush(stored)
            }
        }
        connection.status = ConnectorStatus.INVALID
        return expiredError()
    }

    private fun expiredError() = ConflictException(NOTION_CONNECTION_EXPIRED_CODE, NOTION_CONNECTION_EXPIRED_MESSAGE)

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
