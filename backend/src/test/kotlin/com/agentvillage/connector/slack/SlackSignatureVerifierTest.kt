package com.agentvillage.connector.slack

import com.agentvillage.connector.slack.application.SlackSignatureVerifier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SlackSignatureVerifierTest {
    private val now = Instant.parse("2026-08-25T00:00:00Z")
    private val verifier = SlackSignatureVerifier("signing-secret", Clock.fixed(now, ZoneOffset.UTC))

    @Test fun `valid signature is accepted and tampered or stale requests are rejected`() {
        val body = "{\"type\":\"event_callback\"}"
        val timestamp = now.epochSecond.toString()
        assertThat(verifier.isValid(timestamp, sign(timestamp, body), body)).isTrue()
        assertThat(verifier.isValid(timestamp, sign(timestamp, body), "$body ")).isFalse()
        val stale = now.minusSeconds(301).epochSecond.toString()
        assertThat(verifier.isValid(stale, sign(stale, body), body)).isFalse()
    }

    private fun sign(timestamp: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("signing-secret".toByteArray(), "HmacSHA256"))
        return "v0=" + mac.doFinal("v0:$timestamp:$body".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
