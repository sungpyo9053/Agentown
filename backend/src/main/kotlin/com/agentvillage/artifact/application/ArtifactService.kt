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

    @Transactional(readOnly = true) fun requireOwned(id: UUID, userId: UUID): Artifact {
        val a = artifacts.findByIdAndOwnerUserId(id, userId) ?: throw NotFoundException("ARTIFACT_NOT_FOUND", "결과물을 찾을 수 없습니다.")
        if (a.expiresAt?.isBefore(Instant.now()) == true) { a.status = ArtifactStatus.EXPIRED; throw BadRequestException("ARTIFACT_URL_EXPIRED", "다운로드 URL이 만료되었습니다.") }
        validateUrl(a.externalUrl); return a
    }
    private fun validateUrl(url: String) {
        val uri = runCatching { URI(url) }.getOrElse { throw BadRequestException("ARTIFACT_URL_INVALID", "잘못된 결과 URL입니다.") }
        if (uri.scheme != "https" || uri.userInfo != null || uri.host.isNullOrBlank()) throw BadRequestException("ARTIFACT_URL_INVALID", "HTTPS 결과 URL만 허용됩니다.")
        val address = runCatching { InetAddress.getByName(uri.host) }.getOrNull() ?: return
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) throw BadRequestException("ARTIFACT_URL_BLOCKED", "내부 네트워크 URL은 사용할 수 없습니다.")
    }
}
