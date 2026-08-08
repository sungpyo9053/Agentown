package com.agentvillage.artifact.application

import com.agentvillage.artifact.domain.Artifact
import com.agentvillage.artifact.domain.ArtifactStatus
import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.common.exception.NotFoundException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.Semaphore
import java.time.Instant
import java.util.UUID

interface ArtifactRepository : JpaRepository<Artifact, UUID> {
    fun findByIdAndOwnerUserId(id: UUID, ownerUserId: UUID): Artifact?
    fun findAllByExecutionIdAndOwnerUserIdOrderByCreatedAtDesc(executionId: UUID, ownerUserId: UUID): List<Artifact>
}
@Service
class ArtifactService(private val artifacts: ArtifactRepository) {
    @Transactional(readOnly = true)
    fun listOwned(executionId: UUID, userId: UUID): List<Artifact> =
        artifacts.findAllByExecutionIdAndOwnerUserIdOrderByCreatedAtDesc(executionId, userId)

    @Transactional fun requireOwned(id: UUID, userId: UUID): Artifact {
        val a = artifacts.findByIdAndOwnerUserId(id, userId) ?: throw NotFoundException("ARTIFACT_NOT_FOUND", "결과물을 찾을 수 없습니다.")
        if (a.expiresAt?.isBefore(Instant.now()) == true) { a.status = ArtifactStatus.EXPIRED; throw BadRequestException("ARTIFACT_URL_EXPIRED", "다운로드 URL이 만료되었습니다.") }
        validateUrl(a.externalUrl); return a
    }
    fun validateUrl(url: String): URI {
        val uri = runCatching { URI(url) }.getOrElse { throw BadRequestException("ARTIFACT_URL_INVALID", "잘못된 결과 URL입니다.") }
        if (uri.scheme != "https" || uri.userInfo != null || uri.host.isNullOrBlank()) throw BadRequestException("ARTIFACT_URL_INVALID", "HTTPS 결과 URL만 허용됩니다.")
        val addresses = runCatching { InetAddress.getAllByName(uri.host).toList() }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: throw BadRequestException("ARTIFACT_URL_INVALID", "결과 URL의 호스트를 확인할 수 없습니다.")
        if (addresses.any { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress }) {
            throw BadRequestException("ARTIFACT_URL_BLOCKED", "내부 네트워크 URL은 사용할 수 없습니다.")
        }
        return uri
    }
}

class ArtifactProxyHandle(
    val input: InputStream,
    val contentLength: Long?,
    private val permit: Semaphore,
) : AutoCloseable {
    override fun close() { try { input.close() } finally { permit.release() } }
}

@Service
class ArtifactProxyService(private val artifacts: ArtifactService) {
    private val permits = Semaphore(10, true)
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    fun open(id: UUID, userId: UUID): Pair<Artifact, ArtifactProxyHandle> {
        val artifact = artifacts.requireOwned(id, userId)
        val uri = artifacts.validateUrl(artifact.externalUrl)
        if (!permits.tryAcquire()) throw com.agentvillage.common.exception.ConflictException("DOWNLOAD_PROXY_BUSY", "다운로드 프록시가 혼잡합니다. 잠시 후 다시 시도해 주세요.")
        try {
            val request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                response.body().close()
                throw BadRequestException("ARTIFACT_PROVIDER_ERROR", "외부 결과 제공자가 다운로드를 거부했습니다.")
            }
            val length = response.headers().firstValueAsLong("Content-Length").orElse(-1).takeIf { it >= 0 }
            return artifact to ArtifactProxyHandle(response.body(), length, permits)
        } catch (error: Exception) {
            permits.release()
            if (error is com.agentvillage.common.exception.ApiException) throw error
            throw BadRequestException("ARTIFACT_PROVIDER_UNAVAILABLE", "외부 결과 파일에 연결할 수 없습니다.")
        }
    }
}
