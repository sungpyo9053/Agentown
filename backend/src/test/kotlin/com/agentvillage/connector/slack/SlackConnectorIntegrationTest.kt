package com.agentvillage.connector.slack

import com.agentvillage.IntegrationTestSupport
import com.agentvillage.connector.slack.infrastructure.*
import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.RegisterUserCommand
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.net.URI
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@AutoConfigureMockMvc
@TestPropertySource(properties = [
    "connectors.slack.enabled=true", "connectors.slack.client-id=client-id", "connectors.slack.client-secret=client-secret",
    "connectors.slack.signing-secret=signing-secret", "connectors.slack.redirect-uri=https://reviewdr.kr/api/connectors/slack/oauth/callback",
    "connectors.slack.frontend-result-url=https://reviewdr.kr/settings/connections", "connectors.slack.event-request-url=https://reviewdr.kr/api/connectors/slack/events",
])
class SlackConnectorIntegrationTest : IntegrationTestSupport() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var identities: IdentityService
    @Autowired lateinit var jdbc: JdbcTemplate
    @MockBean lateinit var exchange: SlackTokenExchange

    @Test fun `oauth token is encrypted and state cannot be reused or read by another workspace`() {
        val owner = identity("slack-owner")
        val other = identity("slack-other")
        whenever(exchange.exchange(any(), any())).thenReturn(SlackOauthResult(ok = true, accessToken = "xoxb-plain-token", scope = "channels:history,chat:write", team = SlackTeam("T123", "Agentown Team"), botUserId = "B123"))
        val startBody = mvc.perform(post("/api/connectors/slack/oauth/start").with(user(owner)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val authorizationUrl = com.fasterxml.jackson.databind.ObjectMapper().readTree(startBody)["authorizationUrl"].asText()
        val state = URI(authorizationUrl).rawQuery.split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }.getValue("state")

        mvc.perform(get("/api/connectors/slack/oauth/callback").param("code", "code-1").param("state", state))
            .andExpect(status().is3xxRedirection).andExpect(redirectedUrl("https://reviewdr.kr/settings/connections?slack=connected"))
        val stored = jdbc.queryForMap("select encrypted_access_token, key_version from connector_connections where external_account_id='T123'")
        assertThat(stored["encrypted_access_token"].toString()).doesNotContain("xoxb-plain-token")
        assertThat(stored["key_version"]).isEqualTo("test-v1")

        mvc.perform(get("/api/connectors/slack").with(user(owner))).andExpect(status().isOk).andExpect(jsonPath("$.connected").value(true)).andExpect(jsonPath("$.connections[0].teamName").value("Agentown Team")).andExpect(jsonPath("$.connections[0].encryptedAccessToken").doesNotExist())
        mvc.perform(get("/api/connectors/slack").with(user(other))).andExpect(status().isOk).andExpect(jsonPath("$.connected").value(false)).andExpect(jsonPath("$.connections.length()").value(0))
        mvc.perform(get("/api/connectors/slack/oauth/callback").param("code", "code-2").param("state", state))
            .andExpect(status().is3xxRedirection).andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("https://reviewdr.kr/settings/connections?slack=failed")))
    }

    @Test fun `signed Slack event is stored once while invalid signature and bot messages are rejected or ignored`() {
        val owner = identity("event-owner")
        whenever(exchange.exchange(any(), any())).thenReturn(SlackOauthResult(ok = true, accessToken = "xoxb-token", scope = "channels:history", team = SlackTeam("TEVENT", "Events")))
        connect(owner)
        val timestamp = Instant.now().epochSecond.toString()
        val body = """{"type":"event_callback","team_id":"TEVENT","event_id":"Ev-1","event":{"type":"message","channel":"C1","user":"U1","text":"환불 문의","ts":"123.45"}}"""
        fun sendEvent(signature: String) = mvc.perform(post("/api/connectors/slack/events").header("X-Slack-Request-Timestamp", timestamp).header("X-Slack-Signature", signature).contentType(MediaType.APPLICATION_JSON).content(body))
        sendEvent("v0=bad").andExpect(status().isUnauthorized)
        sendEvent(sign(timestamp, body)).andExpect(status().isOk).andExpect(jsonPath("$.accepted").value(true))
        sendEvent(sign(timestamp, body)).andExpect(status().isOk).andExpect(jsonPath("$.duplicate").value(true))
        assertThat(jdbc.queryForObject("select count(*) from connector_events where provider_event_id='Ev-1'", Long::class.java)).isEqualTo(1)
    }

    @Test fun `url verification also requires a valid Slack signature`() {
        val body = """{"type":"url_verification","challenge":"challenge-value"}"""
        val timestamp = Instant.now().epochSecond.toString()
        mvc.perform(post("/api/connectors/slack/events").header("X-Slack-Request-Timestamp", timestamp).header("X-Slack-Signature", sign(timestamp, body)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk).andExpect(jsonPath("$.challenge").value("challenge-value"))
    }

    private fun identity(prefix: String): AuthenticatedUser {
        val slug = prefix.replace("-", "_")
        val account = identities.register(RegisterUserCommand("$prefix-${UUID.randomUUID()}@example.com", "password123", "${slug}_${UUID.randomUUID().toString().take(8)}", prefix))
        return AuthenticatedUser(account.id, account.email, "unused", true)
    }
    private fun connect(owner: AuthenticatedUser) {
        val body = mvc.perform(post("/api/connectors/slack/oauth/start").with(user(owner)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andReturn().response.contentAsString
        val url = com.fasterxml.jackson.databind.ObjectMapper().readTree(body)["authorizationUrl"].asText()
        val state = URI(url).rawQuery.split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }.getValue("state")
        mvc.perform(get("/api/connectors/slack/oauth/callback").param("code", "code").param("state", state))
    }
    private fun sign(timestamp: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec("signing-secret".toByteArray(), "HmacSHA256"))
        return "v0=" + mac.doFinal("v0:$timestamp:$body".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
