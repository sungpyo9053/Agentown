package com.agentvillage.llmcredential.infrastructure

import com.agentvillage.llmcredential.application.CredentialVerificationResult
import com.agentvillage.llmcredential.application.CredentialVerifierRegistry
import com.agentvillage.llmcredential.application.ProviderCredentialVerifier
import com.agentvillage.llmcredential.domain.LlmProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

abstract class HttpCredentialVerifier(
    private val provider: LlmProvider,
    private val restClient: RestClient,
) : ProviderCredentialVerifier {
    override fun supports(provider: LlmProvider) = this.provider == provider

    protected fun perform(request: (RestClient) -> Unit): CredentialVerificationResult = try {
        request(restClient)
        CredentialVerificationResult(true)
    } catch (exception: RestClientException) {
        CredentialVerificationResult(false, "제공자가 자격증명을 거부했거나 응답하지 않았습니다.")
    }
}

@Component
class OpenAiCredentialVerifier(builder: RestClient.Builder, @Value("\${providers.openai.base-url:https://api.openai.com}") baseUrl: String) :
    HttpCredentialVerifier(LlmProvider.OPENAI, builder.baseUrl(baseUrl).build()) {
    override fun verify(secret: CharArray, providerOptions: Map<String, Any>) = perform { client ->
        client.get().uri("/v1/models")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${String(secret)}")
            .retrieve().onStatus(HttpStatusCode::isError) { _, response -> throw IllegalStateException("HTTP ${response.statusCode}") }
            .toBodilessEntity()
    }
}

@Component
class AnthropicCredentialVerifier(builder: RestClient.Builder, @Value("\${providers.anthropic.base-url:https://api.anthropic.com}") baseUrl: String) :
    HttpCredentialVerifier(LlmProvider.ANTHROPIC, builder.baseUrl(baseUrl).build()) {
    override fun verify(secret: CharArray, providerOptions: Map<String, Any>) = perform { client ->
        client.get().uri("/v1/models")
            .header("x-api-key", String(secret)).header("anthropic-version", "2023-06-01")
            .retrieve().onStatus(HttpStatusCode::isError) { _, response -> throw IllegalStateException("HTTP ${response.statusCode}") }
            .toBodilessEntity()
    }
}

@Component
class GoogleCredentialVerifier(builder: RestClient.Builder, @Value("\${providers.google.base-url:https://generativelanguage.googleapis.com}") baseUrl: String) :
    HttpCredentialVerifier(LlmProvider.GOOGLE, builder.baseUrl(baseUrl).build()) {
    override fun verify(secret: CharArray, providerOptions: Map<String, Any>) = perform { client ->
        client.get().uri("/v1beta/models").header("x-goog-api-key", String(secret))
            .retrieve().onStatus(HttpStatusCode::isError) { _, response -> throw IllegalStateException("HTTP ${response.statusCode}") }
            .toBodilessEntity()
    }
}

@Configuration
class CredentialVerifierConfiguration {
    @Bean
    fun credentialVerifierRegistry(verifiers: List<ProviderCredentialVerifier>) = CredentialVerifierRegistry(verifiers)
}
