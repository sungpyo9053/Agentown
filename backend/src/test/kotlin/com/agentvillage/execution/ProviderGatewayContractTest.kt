package com.agentvillage.execution

import com.agentvillage.execution.application.AiModelRequest
import com.agentvillage.execution.application.DecryptedCredential
import com.agentvillage.execution.infrastructure.AnthropicModelGateway
import com.agentvillage.execution.infrastructure.GoogleModelGateway
import com.agentvillage.execution.infrastructure.OpenAiModelGateway
import com.agentvillage.llmcredential.domain.LlmProvider
import com.agentvillage.llmcredential.infrastructure.AnthropicCredentialVerifier
import com.agentvillage.llmcredential.infrastructure.GoogleCredentialVerifier
import com.agentvillage.llmcredential.infrastructure.OpenAiCredentialVerifier
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class ProviderGatewayContractTest {
    private val request = AiModelRequest("test-model", "system", "hello", BigDecimal("0.2"), 256, 30)

    @Test
    fun `OpenAI Responses contract sends bearer and parses output`() = withServer { server, requests ->
        server.createContext("/v1/responses") { exchange ->
            requests += Captured(exchange)
            exchange.json(200, """{"id":"resp_1","output_text":"openai-result","usage":{"input_tokens":3,"output_tokens":4}}""")
        }
        val gateway = OpenAiModelGateway(RestClient.builder(), server.url())
        val response = DecryptedCredential(LlmProvider.OPENAI, "test-openai".toCharArray()).use { gateway.execute(it, request) }
        assertThat(response.content).isEqualTo("openai-result")
        assertThat(response.tokenUsage.inputTokens).isEqualTo(3)
        assertThat(requests.single().authorization).isEqualTo("Bearer test-openai")
        assertThat(requests.single().body).contains("\"model\":\"test-model\"").contains("\"max_output_tokens\":256")
    }

    @Test
    fun `Anthropic Messages contract sends API key and parses content`() = withServer { server, requests ->
        server.createContext("/v1/messages") { exchange ->
            requests += Captured(exchange)
            exchange.json(200, """{"id":"msg_1","content":[{"type":"text","text":"claude-result"}],"usage":{"input_tokens":5,"output_tokens":6}}""")
        }
        val gateway = AnthropicModelGateway(RestClient.builder(), server.url())
        val response = DecryptedCredential(LlmProvider.ANTHROPIC, "test-anthropic".toCharArray()).use { gateway.execute(it, request) }
        assertThat(response.content).isEqualTo("claude-result")
        assertThat(requests.single().apiKey).isEqualTo("test-anthropic")
        assertThat(requests.single().body).contains("\"messages\"").contains("\"max_tokens\":256")
    }

    @Test
    fun `Google generateContent contract sends key and parses candidate`() = withServer { server, requests ->
        server.createContext("/v1beta/models/test-model:generateContent") { exchange ->
            requests += Captured(exchange)
            exchange.json(200, """{"candidates":[{"content":{"parts":[{"text":"gemini-result"}]}}],"usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":8}}""")
        }
        val gateway = GoogleModelGateway(RestClient.builder(), server.url())
        val response = DecryptedCredential(LlmProvider.GOOGLE, "test-google".toCharArray()).use { gateway.execute(it, request) }
        assertThat(response.content).isEqualTo("gemini-result")
        assertThat(response.tokenUsage.outputTokens).isEqualTo(8)
        assertThat(requests.single().googleKey).isEqualTo("test-google")
    }

    @Test
    fun `credential verifier contracts accept provider model endpoints`() = withServer { server, requests ->
        server.createContext("/v1/models") { exchange -> requests += Captured(exchange); exchange.json(200, "{}") }
        server.createContext("/v1beta/models") { exchange -> requests += Captured(exchange); exchange.json(200, "{}") }
        val url = server.url()
        assertThat(OpenAiCredentialVerifier(RestClient.builder(), url).verify("openai-key".toCharArray(), emptyMap()).valid).isTrue()
        assertThat(AnthropicCredentialVerifier(RestClient.builder(), url).verify("anthropic-key".toCharArray(), emptyMap()).valid).isTrue()
        assertThat(GoogleCredentialVerifier(RestClient.builder(), url).verify("google-key".toCharArray(), emptyMap()).valid).isTrue()
        assertThat(requests).hasSize(3)
    }

    private fun withServer(block: (HttpServer, MutableList<Captured>) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<Captured>()
        try { server.start(); block(server, requests) } finally { server.stop(0) }
    }

    private fun HttpServer.url() = "http://127.0.0.1:${address.port}"
    private data class Captured(val body: String, val authorization: String?, val apiKey: String?, val googleKey: String?) {
        constructor(exchange: HttpExchange) : this(
            exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8),
            exchange.requestHeaders.getFirst("Authorization"), exchange.requestHeaders.getFirst("x-api-key"),
            exchange.requestHeaders.getFirst("x-goog-api-key"),
        )
    }
    private fun HttpExchange.json(status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
