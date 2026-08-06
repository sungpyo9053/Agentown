package com.agentvillage.execution.infrastructure

import com.agentvillage.execution.application.AiModelGateway
import com.agentvillage.execution.application.AiModelGatewayRegistry
import com.agentvillage.execution.application.AiModelRequest
import com.agentvillage.execution.application.AiModelResponse
import com.agentvillage.execution.application.DecryptedCredential
import com.agentvillage.execution.application.TokenUsage
import com.agentvillage.llmcredential.domain.LlmProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

abstract class JsonAiModelGateway(
    private val provider: LlmProvider,
    protected val client: RestClient,
) : AiModelGateway {
    override fun supports(provider: LlmProvider) = this.provider == provider

    protected fun number(value: Any?): Long = (value as? Number)?.toLong() ?: 0L
    @Suppress("UNCHECKED_CAST")
    protected fun map(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()
    @Suppress("UNCHECKED_CAST")
    protected fun list(value: Any?): List<Any?> = value as? List<Any?> ?: emptyList()
}

@Component
class OpenAiModelGateway(builder: RestClient.Builder) :
    JsonAiModelGateway(LlmProvider.OPENAI, builder.baseUrl("https://api.openai.com").build()) {
    override fun execute(credential: DecryptedCredential, request: AiModelRequest): AiModelResponse {
        val body = linkedMapOf<String, Any>().apply {
            putAll(request.providerOptions.filterKeys { it !in openAiReservedFields })
            put("model", request.model)
            put("input", request.input)
            request.systemPrompt?.let { put("instructions", it) }
            put("max_output_tokens", request.maxOutputTokens)
            if (request.model.startsWith("gpt-4")) put("temperature", request.temperature)
        }
        val response = client.post().uri("/v1/responses")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${String(credential.secret)}")
            .body(body).retrieve().body(Map::class.java) ?: emptyMap<Any, Any>()
        val usage = map(response["usage"])
        val content = response["output_text"]?.toString()?.takeIf(String::isNotBlank)
            ?: list(response["output"]).asSequence()
                .map(::map)
                .flatMap { list(it["content"]).asSequence() }
                .map(::map)
                .filter { it["type"]?.toString() == "output_text" }
                .mapNotNull { it["text"]?.toString() }
                .joinToString("")
        return AiModelResponse(
            content,
            TokenUsage(number(usage["input_tokens"]), number(usage["output_tokens"])),
            response["id"]?.toString(),
        )
    }

    companion object {
        private val openAiReservedFields = setOf("model", "input", "instructions", "max_output_tokens", "temperature", "messages", "max_tokens")
    }
}

@Component
class AnthropicModelGateway(builder: RestClient.Builder) :
    JsonAiModelGateway(LlmProvider.ANTHROPIC, builder.baseUrl("https://api.anthropic.com").build()) {
    override fun execute(credential: DecryptedCredential, request: AiModelRequest): AiModelResponse {
        val body = linkedMapOf<String, Any>(
            "model" to request.model,
            "messages" to listOf(mapOf("role" to "user", "content" to request.input)),
            "max_tokens" to request.maxOutputTokens,
            "temperature" to request.temperature,
        ).apply {
            request.systemPrompt?.let { put("system", it) }
            putAll(request.providerOptions)
        }
        val response = client.post().uri("/v1/messages")
            .header("x-api-key", String(credential.secret)).header("anthropic-version", "2023-06-01")
            .body(body).retrieve().body(Map::class.java) ?: emptyMap<Any, Any>()
        val content = map(list(response["content"]).firstOrNull())["text"]?.toString().orEmpty()
        val usage = map(response["usage"])
        return AiModelResponse(
            content, TokenUsage(number(usage["input_tokens"]), number(usage["output_tokens"])), response["id"]?.toString(),
        )
    }
}

@Component
class GoogleModelGateway(builder: RestClient.Builder) :
    JsonAiModelGateway(LlmProvider.GOOGLE, builder.baseUrl("https://generativelanguage.googleapis.com").build()) {
    override fun supportsModel(model: String) = model.matches(Regex("^[A-Za-z0-9._-]+$"))

    override fun execute(credential: DecryptedCredential, request: AiModelRequest): AiModelResponse {
        require(supportsModel(request.model)) { "Invalid Google model name" }
        val prompt = listOfNotNull(request.systemPrompt, request.input).joinToString("\n\n")
        val body = linkedMapOf<String, Any>(
            "contents" to listOf(mapOf("parts" to listOf(mapOf("text" to prompt)))),
            "generationConfig" to mapOf(
                "temperature" to request.temperature,
                "maxOutputTokens" to request.maxOutputTokens,
            ),
        ).apply { putAll(request.providerOptions) }
        val response = client.post().uri("/v1beta/models/${request.model}:generateContent")
            .header("x-goog-api-key", String(credential.secret)).body(body)
            .retrieve().body(Map::class.java) ?: emptyMap<Any, Any>()
        val candidate = map(list(response["candidates"]).firstOrNull())
        val part = map(list(map(candidate["content"])["parts"]).firstOrNull())
        val usage = map(response["usageMetadata"])
        return AiModelResponse(
            part["text"]?.toString().orEmpty(),
            TokenUsage(number(usage["promptTokenCount"]), number(usage["candidatesTokenCount"])),
            null,
        )
    }
}

@Configuration
class AiModelGatewayConfiguration {
    @Bean
    fun aiModelGatewayRegistry(gateways: List<AiModelGateway>) = AiModelGatewayRegistry(gateways)
}
