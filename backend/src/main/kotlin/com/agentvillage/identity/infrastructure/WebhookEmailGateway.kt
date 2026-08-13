package com.agentvillage.identity.infrastructure

import com.agentvillage.identity.application.EmailGateway
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
@ConditionalOnProperty(name = ["auth.email.provider"], havingValue = "webhook")
class WebhookEmailGateway(
    builder: RestClient.Builder,
    @Value("\${auth.email.webhook-url}") webhookUrl: String,
    @Value("\${auth.email.webhook-token}") private val token: String,
) : EmailGateway {
    private val client = builder.baseUrl(requireHttps(webhookUrl)).build()

    override fun send(to: String, subject: String, body: String) {
        client.post()
            .header("Authorization", "Bearer $token")
            .body(mapOf("to" to to, "subject" to subject, "body" to body))
            .retrieve()
            .toBodilessEntity()
    }

    private fun requireHttps(url: String): String {
        require(url.startsWith("https://")) { "auth.email.webhook-url must use HTTPS" }
        return url.removeSuffix("/")
    }
}
