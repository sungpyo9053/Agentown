package com.agentvillage.identity.infrastructure

import com.agentvillage.identity.application.SmsGateway
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Production SMS adapter boundary. The configured HTTPS endpoint receives a
 * minimal provider-neutral payload and is responsible for talking to the
 * selected Korean SMS/PASS vendor. Credentials are supplied only at runtime.
 */
@Component
@ConditionalOnProperty(name = ["auth.sms.provider"], havingValue = "webhook")
class WebhookSmsGateway(
    builder: RestClient.Builder,
    @Value("\${auth.sms.webhook-url}") webhookUrl: String,
    @Value("\${auth.sms.webhook-token}") private val token: String,
) : SmsGateway {
    private val client = builder.baseUrl(requireHttps(webhookUrl)).build()

    override fun send(phone: String, message: String) {
        client.post()
            .header("Authorization", "Bearer $token")
            .body(mapOf("phone" to phone, "message" to message))
            .retrieve()
            .toBodilessEntity()
    }

    private fun requireHttps(url: String): String {
        require(url.startsWith("https://")) { "auth.sms.webhook-url must use HTTPS" }
        return url.removeSuffix("/")
    }
}
