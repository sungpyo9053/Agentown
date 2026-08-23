package com.agentvillage.llmcredential.application

import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.common.exception.NotFoundException
import com.agentvillage.llmcredential.domain.CredentialStatus
import com.agentvillage.llmcredential.domain.LlmCredential
import com.agentvillage.llmcredential.domain.LlmProvider
import com.agentvillage.llmcredential.infrastructure.LlmCredentialRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class CredentialView(
    val id: UUID,
    val provider: LlmProvider,
    val maskedSecret: String,
    val status: CredentialStatus,
    val providerOptions: Map<String, Any>,
    val lastVerifiedAt: Instant?,
    val createdAt: Instant,
)

data class VerifyCredentialResult(
    val provider: LlmProvider,
    val maskedSecret: String,
    val status: CredentialStatus,
    val verifiedAt: Instant?,
    val message: String?,
)

@Service
class LlmCredentialService(
    private val credentials: LlmCredentialRepository,
    private val encryptor: SecretEncryptor,
    private val verifierRegistry: CredentialVerifierRegistry,
    private val optionsPolicy: ProviderOptionsPolicy,
) : CredentialDirectory {
    @Transactional(readOnly = true)
    override fun findLatestActive(ownerId: UUID, provider: LlmProvider): CredentialMetadata? =
        credentials.findFirstByOwnerIdAndProviderAndStatusOrderByCreatedAtDesc(ownerId, provider, CredentialStatus.ACTIVE)?.toMetadata()

    fun verify(provider: LlmProvider, secret: CharArray, providerOptions: Map<String, Any>): VerifyCredentialResult {
        optionsPolicy.validate(providerOptions)
        return try {
            val result = verifierRegistry.verify(provider, secret, providerOptions)
            val verifiedAt = if (result.valid) Instant.now() else null
            VerifyCredentialResult(
                provider, mask(secret), if (result.valid) CredentialStatus.ACTIVE else CredentialStatus.INVALID,
                verifiedAt, result.message,
            )
        } finally {
            secret.fill('\u0000')
        }
    }

    @Transactional
    fun create(ownerId: UUID, provider: LlmProvider, secret: CharArray, providerOptions: Map<String, Any>): CredentialView {
        optionsPolicy.validate(providerOptions)
        val masked = mask(secret)
        return try {
            val verification = verifierRegistry.verify(provider, secret, providerOptions)
            if (!verification.valid) {
                throw BadRequestException("LLM_CREDENTIAL_INVALID", verification.message ?: "유효하지 않은 자격증명입니다.")
            }
            val encrypted = encryptor.encrypt(secret)
            credentials.save(
                LlmCredential(
                    ownerId = ownerId,
                    provider = provider,
                    encryptedSecret = encrypted.cipherText,
                    maskedSecret = masked,
                    keyVersion = encrypted.keyVersion,
                    status = CredentialStatus.ACTIVE,
                    providerOptions = providerOptions,
                    lastVerifiedAt = Instant.now(),
                ),
            ).toView()
        } finally {
            secret.fill('\u0000')
        }
    }

    @Transactional(readOnly = true)
    fun list(ownerId: UUID): List<CredentialView> =
        credentials.findAllByOwnerIdOrderByCreatedAtDesc(ownerId).map { it.toView() }

    @Transactional
    fun delete(id: UUID, ownerId: UUID) {
        val credential = credentials.findByIdAndOwnerId(id, ownerId)
            ?: throw NotFoundException("LLM_CREDENTIAL_NOT_FOUND", "LLM 자격증명을 찾을 수 없습니다.")
        credentials.delete(credential)
    }

    @Transactional(readOnly = true)
    override fun requireOwned(credentialId: UUID, ownerId: UUID, provider: LlmProvider): CredentialMetadata {
        val credential = credentials.findByIdAndOwnerId(credentialId, ownerId)
            ?: throw NotFoundException("LLM_CREDENTIAL_NOT_FOUND", "소유한 LLM 자격증명을 찾을 수 없습니다.")
        if (credential.provider != provider) {
            throw BadRequestException("LLM_CREDENTIAL_INVALID", "에이전트 제공자와 자격증명 제공자가 일치하지 않습니다.")
        }
        return credential.toMetadata()
    }

    @Transactional(readOnly = true)
    override fun requireActive(credentialId: UUID, ownerId: UUID, provider: LlmProvider): CredentialMetadata {
        val credential = requireOwned(credentialId, ownerId, provider)
        if (credential.status != CredentialStatus.ACTIVE) {
            throw BadRequestException("LLM_CREDENTIAL_INVALID", "활성 상태의 LLM 자격증명이 필요합니다.")
        }
        return credential
    }

    @Transactional(readOnly = true)
    override fun <T> withDecrypted(credentialId: UUID, ownerId: UUID, provider: LlmProvider, block: (CharArray, Map<String, Any>) -> T): T {
        requireActive(credentialId, ownerId, provider)
        val credential = credentials.findByIdAndOwnerId(credentialId, ownerId)
            ?: throw NotFoundException("LLM_CREDENTIAL_NOT_FOUND", "LLM 자격증명을 찾을 수 없습니다.")
        val plain = encryptor.decrypt(EncryptedSecret(credential.encryptedSecret, credential.keyVersion))
        return try { block(plain, credential.providerOptions) } finally { plain.fill('\u0000') }
    }

    private fun mask(secret: CharArray): String {
        if (secret.isEmpty()) return "••••"
        val prefixLength = secret.indexOf('-').takeIf { it in 1..5 }?.plus(1) ?: minOf(3, secret.size)
        val suffixLength = minOf(4, (secret.size - prefixLength).coerceAtLeast(0))
        val prefix = secret.copyOfRange(0, prefixLength).concatToString()
        val suffix = if (suffixLength > 0) secret.copyOfRange(secret.size - suffixLength, secret.size).concatToString() else ""
        return "$prefix••••••••$suffix"
    }

    private fun LlmCredential.toView() = CredentialView(
        id, provider, maskedSecret, status, providerOptions, lastVerifiedAt, createdAt,
    )

    private fun LlmCredential.toMetadata() = CredentialMetadata(id, ownerId, provider, status)
}
