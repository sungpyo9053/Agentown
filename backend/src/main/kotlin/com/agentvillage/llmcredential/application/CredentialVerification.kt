package com.agentvillage.llmcredential.application

import com.agentvillage.llmcredential.domain.LlmProvider

data class CredentialVerificationResult(val valid: Boolean, val message: String? = null)

interface ProviderCredentialVerifier {
    fun supports(provider: LlmProvider): Boolean
    fun verify(secret: CharArray, providerOptions: Map<String, Any>): CredentialVerificationResult
}

class CredentialVerifierRegistry(verifiers: List<ProviderCredentialVerifier>) {
    private val byProvider = LlmProvider.entries.associateWith { provider ->
        verifiers.singleOrNull { it.supports(provider) }
            ?: error("Exactly one credential verifier is required for $provider")
    }

    fun verify(provider: LlmProvider, secret: CharArray, options: Map<String, Any>) =
        byProvider.getValue(provider).verify(secret, options)
}

