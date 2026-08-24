package com.agentvillage.connector.slack.application

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class SlackSignatureVerifier(
    @Value("\${connectors.slack.signing-secret:}") private val signingSecret: String,
    private val clock: Clock,
) {
    fun isValid(timestamp: String?, signature: String?, rawBody: String): Boolean {
        if (signingSecret.isBlank() || timestamp == null || signature == null) return false
        val epoch = timestamp.toLongOrNull() ?: return false
        if (kotlin.math.abs(clock.instant().epochSecond - epoch) > 300) return false
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val expected = "v0=" + mac.doFinal("v0:$timestamp:$rawBody".toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(expected.toByteArray(StandardCharsets.US_ASCII), signature.toByteArray(StandardCharsets.US_ASCII))
    }
}
