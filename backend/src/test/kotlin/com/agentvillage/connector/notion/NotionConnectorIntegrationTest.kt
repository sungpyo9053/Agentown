package com.agentvillage.connector.notion

import com.agentvillage.IntegrationTestSupport
import com.agentvillage.connector.notion.infrastructure.*
import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.RegisterUserCommand
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
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
import java.util.UUID

@AutoConfigureMockMvc
@TestPropertySource(properties = [
    "connectors.notion.enabled=true", "connectors.notion.client-id=notion-client", "connectors.notion.client-secret=notion-secret",
    "connectors.notion.redirect-uri=https://reviewdr.kr/api/connectors/notion/oauth/callback",
    "connectors.notion.frontend-result-url=https://reviewdr.kr/settings/connections",
])
class NotionConnectorIntegrationTest : IntegrationTestSupport() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var identities: IdentityService
    @Autowired lateinit var jdbc: JdbcTemplate
    @MockBean lateinit var gateway: NotionOauthGateway

    @Test fun `oauth tokens are encrypted state is single use and connections are workspace isolated`() {
        val owner = identity("notion-owner")
        val other = identity("notion-other")
        whenever(gateway.exchange(any(), any())).thenReturn(NotionOauthResult("plain-access-token", "plain-refresh-token", "bot-1", "notion-workspace-1", "운영자 Notion"))
        val state = start(owner)

        mvc.perform(get("/api/connectors/notion/oauth/callback").param("code", "code-1").param("state", state))
            .andExpect(status().is3xxRedirection).andExpect(redirectedUrl("https://reviewdr.kr/settings/connections?notion=connected"))
        val stored = jdbc.queryForMap("select encrypted_access_token, encrypted_refresh_token, key_version from connector_connections where external_account_id='notion-workspace-1'")
        assertThat(stored["encrypted_access_token"].toString()).doesNotContain("plain-access-token")
        assertThat(stored["encrypted_refresh_token"].toString()).doesNotContain("plain-refresh-token")
        assertThat(stored["key_version"]).isEqualTo("test-v1")
        mvc.perform(get("/api/connectors/notion").with(user(owner))).andExpect(status().isOk)
            .andExpect(jsonPath("$.connected").value(true)).andExpect(jsonPath("$.connections[0].workspaceName").value("운영자 Notion"))
            .andExpect(jsonPath("$.connections[0].encryptedAccessToken").doesNotExist())
        mvc.perform(get("/api/connectors/notion").with(user(other))).andExpect(status().isOk)
            .andExpect(jsonPath("$.connected").value(false)).andExpect(jsonPath("$.connections.length()").value(0))
        val connectionId = jdbc.queryForObject("select id from connector_connections where external_account_id='notion-workspace-1'", UUID::class.java)!!
        mvc.perform(post("/api/connectors/notion/$connectionId/verify").with(user(other)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound)
        mvc.perform(get("/api/connectors/notion/oauth/callback").param("code", "code-2").param("state", state))
            .andExpect(status().is3xxRedirection).andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("https://reviewdr.kr/settings/connections?notion=failed")))
    }

    @Test fun `real read contract searches shared content and refreshes an invalid access token`() {
        val owner = identity("notion-read")
        whenever(gateway.exchange(any(), any())).thenReturn(NotionOauthResult("expired-access", "refresh-1", "bot-2", "notion-workspace-2", "자료실"))
        connect(owner)
        val connectionId = jdbc.queryForObject("select id from connector_connections where external_account_id='notion-workspace-2'", UUID::class.java)!!
        whenever(gateway.self("expired-access")).thenThrow(NotionTokenInvalidException())
        whenever(gateway.refresh("refresh-1")).thenReturn(NotionOauthResult("fresh-access", "refresh-2", "bot-2", "notion-workspace-2", "자료실"))
        whenever(gateway.self("fresh-access")).thenReturn(NotionBot("bot-2", "Agentown", "bot"))
        whenever(gateway.search("fresh-access", "FAQ", 10)).thenReturn(listOf(NotionSearchItem("page-1", "page", "고객 FAQ", "https://notion.so/page-1")))

        mvc.perform(post("/api/connectors/notion/$connectionId/verify").with(user(owner)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""{"query":"FAQ"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.botName").value("Agentown"))
            .andExpect(jsonPath("$.accessibleItems[0].title").value("고객 FAQ"))
            .andExpect(jsonPath("$.verifiedAt").isNotEmpty)
        val stored = jdbc.queryForMap("select encrypted_access_token, encrypted_refresh_token, last_verified_at from connector_connections where id=?", connectionId)
        assertThat(stored["encrypted_access_token"].toString()).doesNotContain("fresh-access")
        assertThat(stored["encrypted_refresh_token"].toString()).doesNotContain("refresh-2")
        assertThat(stored["last_verified_at"]).isNotNull
    }

    @Test fun `page write is previewed first approved once and workspace isolated`() {
        val owner = identity("notion-write")
        val other = identity("notion-write-other")
        whenever(gateway.exchange(any(), any())).thenReturn(NotionOauthResult("write-access", "write-refresh", "bot-3", "notion-workspace-3", "발행 공간"))
        connect(owner)
        val connectionId = jdbc.queryForObject("select id from connector_connections where external_account_id='notion-workspace-3'", UUID::class.java)!!
        val previewBody = """{"parentPageId":"12345678901234567890123456789012","title":"주간 개발 검증서","paragraphs":["구현 완료","회귀 테스트 통과"]}"""

        val response = mvc.perform(post("/api/connectors/notion/$connectionId/page-previews").with(user(owner)).with(csrf())
            .header("Idempotency-Key", "preview-1").contentType(MediaType.APPLICATION_JSON).content(previewBody))
            .andExpect(status().isOk).andExpect(jsonPath("$.status").value("PREVIEWED"))
            .andReturn().response.contentAsString
        val requestId = UUID.fromString(com.fasterxml.jackson.databind.ObjectMapper().readTree(response)["id"].asText())
        verify(gateway, times(0)).createPage(any(), any(), any(), any())

        mvc.perform(get("/api/connectors/notion/page-writes/$requestId").with(user(other)))
            .andExpect(status().isNotFound)
        mvc.perform(post("/api/connectors/notion/page-writes/$requestId/approve").with(user(other)).with(csrf()).header("Idempotency-Key", "approve-other"))
            .andExpect(status().isNotFound)

        whenever(gateway.createPage("write-access", "12345678901234567890123456789012", "주간 개발 검증서", listOf("구현 완료", "회귀 테스트 통과")))
            .thenReturn(NotionCreatedPage("created-page-1", "https://notion.so/created-page-1"))
        repeat(2) {
            mvc.perform(post("/api/connectors/notion/page-writes/$requestId/approve").with(user(owner)).with(csrf()).header("Idempotency-Key", "approve-1"))
                .andExpect(status().isOk).andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.notionPageId").value("created-page-1"))
        }
        verify(gateway, times(1)).createPage(any(), any(), any(), any())

        mvc.perform(post("/api/connectors/notion/$connectionId/page-previews").with(user(owner)).with(csrf())
            .header("Idempotency-Key", "preview-1").contentType(MediaType.APPLICATION_JSON).content(previewBody))
            .andExpect(status().isOk).andExpect(jsonPath("$.id").value(requestId.toString()))
    }

    @Test fun `invalid page preview is rejected without external write`() {
        val owner = identity("notion-invalid-write")
        whenever(gateway.exchange(any(), any())).thenReturn(NotionOauthResult("access", null, "bot-4", "notion-workspace-4", "검증 공간"))
        connect(owner)
        val connectionId = jdbc.queryForObject("select id from connector_connections where external_account_id='notion-workspace-4'", UUID::class.java)!!
        mvc.perform(post("/api/connectors/notion/$connectionId/page-previews").with(user(owner)).with(csrf())
            .header("Idempotency-Key", "invalid-preview").contentType(MediaType.APPLICATION_JSON)
            .content("""{"parentPageId":"bad","title":"","paragraphs":[]}"""))
            .andExpect(status().isBadRequest)
        verify(gateway, times(0)).createPage(any(), any(), any(), any())
    }

    @Test fun `provider write failure is persisted for analysis`() {
        val owner = identity("notion-failed-write")
        whenever(gateway.exchange(any(), any())).thenReturn(NotionOauthResult("failed-access", null, "bot-5", "notion-workspace-5", "장애 검증"))
        connect(owner)
        val connectionId = jdbc.queryForObject("select id from connector_connections where external_account_id='notion-workspace-5'", UUID::class.java)!!
        val response = mvc.perform(post("/api/connectors/notion/$connectionId/page-previews").with(user(owner)).with(csrf())
            .header("Idempotency-Key", "failed-preview").contentType(MediaType.APPLICATION_JSON)
            .content("""{"parentPageId":"12345678901234567890123456789012","title":"실패 기록","paragraphs":["발행 실패를 기록합니다."]}"""))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val requestId = UUID.fromString(com.fasterxml.jackson.databind.ObjectMapper().readTree(response)["id"].asText())
        whenever(gateway.createPage(any(), any(), any(), any())).thenThrow(IllegalStateException("provider unavailable"))

        mvc.perform(post("/api/connectors/notion/page-writes/$requestId/approve").with(user(owner)).with(csrf()).header("Idempotency-Key", "failed-approval"))
            .andExpect(status().isOk).andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.failureCode").value("NOTION_PAGE_CREATE_FAILED"))
        val stored = jdbc.queryForMap("select status, failure_code, failure_message from notion_page_write_requests where id=?", requestId)
        assertThat(stored["status"]).isEqualTo("FAILED")
        assertThat(stored["failure_code"]).isEqualTo("NOTION_PAGE_CREATE_FAILED")
        assertThat(stored["failure_message"].toString()).contains("provider unavailable")
    }

    private fun identity(prefix: String): AuthenticatedUser {
        val account = identities.register(RegisterUserCommand("$prefix-${UUID.randomUUID()}@example.com", "password123", "${prefix.replace("-", "_")}_${UUID.randomUUID().toString().take(8)}", prefix))
        return AuthenticatedUser(account.id, account.email, "unused", true)
    }
    private fun start(owner: AuthenticatedUser): String {
        val body = mvc.perform(post("/api/connectors/notion/oauth/start").with(user(owner)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val url = com.fasterxml.jackson.databind.ObjectMapper().readTree(body)["authorizationUrl"].asText()
        return URI(url).rawQuery.split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }.getValue("state")
    }
    private fun connect(owner: AuthenticatedUser) {
        val state = start(owner)
        mvc.perform(get("/api/connectors/notion/oauth/callback").param("code", "code").param("state", state)).andExpect(status().is3xxRedirection)
    }
}
