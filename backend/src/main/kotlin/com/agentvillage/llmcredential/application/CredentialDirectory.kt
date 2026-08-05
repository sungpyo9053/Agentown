package com.agentvillage.llmcredential.application

import com.agentvillage.llmcredential.domain.CredentialStatus
import com.agentvillage.llmcredential.domain.LlmProvider
import java.util.UUID

data class CredentialMetadata(
    val id: UUID,
    val ownerId: UUID,
    val provider: LlmProvider,
    val status: CredentialStatus,
)

interface CredentialDirectory {
    fun requireOwned(credentialId: UUID, ownerId: UUID, provider: LlmProvider): CredentialMetadata
    fun requireActive(credentialId: UUID, ownerId: UUID, provider: LlmProvider): CredentialMetadata
    fun <T> withDecrypted(credentialId: UUID, ownerId: UUID, provider: LlmProvider, block: (CharArray, Map<String, Any>) -> T): T
}
