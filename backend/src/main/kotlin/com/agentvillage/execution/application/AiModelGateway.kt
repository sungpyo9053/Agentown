package com.agentvillage.execution.application

import com.agentvillage.llmcredential.domain.LlmProvider
import java.math.BigDecimal

class DecryptedCredential(
    val provider: LlmProvider,
    val secret: CharArray,
    val providerOptions: Map<String, Any> = emptyMap(),
) : AutoCloseable {
    override fun close() {
        secret.fill('\u0000')
    }
}

data class AiModelRequest(
    val model: String,
    val systemPrompt: String?,
    val input: String,
    val temperature: BigDecimal,
    val maxOutputTokens: Int,
    val timeoutSeconds: Int,
    val providerOptions: Map<String, Any> = emptyMap(),
)

data class TokenUsage(val inputTokens: Long, val outputTokens: Long)

data class AiModelResponse(
    val content: String,
    val tokenUsage: TokenUsage,
    val providerRequestId: String?,
)

interface AiModelGateway {
    fun supports(provider: LlmProvider): Boolean
    fun supportsModel(model: String): Boolean = true
    fun execute(credential: DecryptedCredential, request: AiModelRequest): AiModelResponse
}

class AiModelGatewayRegistry(gateways: List<AiModelGateway>) {
    private val gatewaysByProvider = LlmProvider.entries.associateWith { provider ->
        gateways.singleOrNull { it.supports(provider) }
            ?: error("Exactly one AI model gateway is required for $provider")
    }

    fun get(provider: LlmProvider): AiModelGateway = gatewaysByProvider.getValue(provider)
}

class StubAiModelGateway(private val provider: LlmProvider) : AiModelGateway {
    override fun supports(provider: LlmProvider) = this.provider == provider
    override fun execute(credential: DecryptedCredential, request: AiModelRequest) =
        AiModelResponse("stub:${request.input}", TokenUsage(1, 1), "stub-request")
}

