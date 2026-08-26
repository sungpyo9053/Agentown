package com.agentvillage.connector.notion.infrastructure

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.nio.charset.StandardCharsets
import java.util.Base64

data class NotionOauthResult(
    @JsonProperty("access_token") val accessToken: String = "",
    @JsonProperty("refresh_token") val refreshToken: String? = null,
    @JsonProperty("bot_id") val botId: String = "",
    @JsonProperty("workspace_id") val workspaceId: String = "",
    @JsonProperty("workspace_name") val workspaceName: String? = null,
    @JsonProperty("workspace_icon") val workspaceIcon: String? = null,
    val owner: Map<String, Any?> = emptyMap(),
)

data class NotionBot(val id: String = "", val name: String? = null, val type: String = "", val bot: Map<String, Any?>? = null)
data class NotionSearchItem(val id: String, val objectType: String, val title: String, val url: String?)
data class NotionCreatedPage(val id: String, val url: String?)

interface NotionOauthGateway {
    fun exchange(code: String, redirectUri: String): NotionOauthResult
    fun refresh(refreshToken: String): NotionOauthResult
    fun revoke(accessToken: String)
    fun self(accessToken: String): NotionBot
    fun search(accessToken: String, query: String, pageSize: Int): List<NotionSearchItem>
    fun createPage(accessToken: String, parentPageId: String, title: String, paragraphs: List<String>): NotionCreatedPage
}

class NotionTokenInvalidException : RuntimeException("Notion access token is invalid")

@Component
class HttpNotionOauthGateway(
    builder: RestClient.Builder,
    @Value("\${connectors.notion.api-base-url:https://api.notion.com}") baseUrl: String,
    @Value("\${connectors.notion.client-id:}") private val clientId: String,
    @Value("\${connectors.notion.client-secret:}") private val clientSecret: String,
    @Value("\${connectors.notion.api-version:2026-03-11}") private val apiVersion: String,
) : NotionOauthGateway {
    private val client = builder.baseUrl(baseUrl.removeSuffix("/")).build()

    override fun exchange(code: String, redirectUri: String) = token(mapOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to redirectUri))
    override fun refresh(refreshToken: String) = token(mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken))
    override fun revoke(accessToken: String) {
        val basic = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray(StandardCharsets.UTF_8))
        client.post().uri("/v1/oauth/revoke").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Basic $basic").header("Notion-Version", apiVersion)
            .body(mapOf("token" to accessToken)).retrieve().toBodilessEntity()
    }

    override fun self(accessToken: String): NotionBot = authorized {
        client.get().uri("/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken").header("Notion-Version", apiVersion)
            .retrieve().body(NotionBot::class.java) ?: error("Notion bot response was empty")
    }

    override fun search(accessToken: String, query: String, pageSize: Int): List<NotionSearchItem> = authorized {
        val body = client.post().uri("/v1/search").header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken").header("Notion-Version", apiVersion).contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("query" to query.take(200), "page_size" to pageSize.coerceIn(1, 20)))
            .retrieve().body(Map::class.java) as? Map<*, *> ?: error("Notion search response was empty")
        (body["results"] as? List<*>)?.mapNotNull { raw ->
            val item = raw as? Map<*, *> ?: return@mapNotNull null
            val id = item["id"]?.toString() ?: return@mapNotNull null
            val objectType = item["object"]?.toString().orEmpty()
            val title = extractTitle(item).ifBlank { "제목 없음" }
            NotionSearchItem(id, objectType, title, item["url"]?.toString())
        }.orEmpty()
    }

    override fun createPage(accessToken: String, parentPageId: String, title: String, paragraphs: List<String>): NotionCreatedPage = authorized {
        val richText: (String) -> List<Map<String, Any>> = { value -> listOf(mapOf("type" to "text", "text" to mapOf("content" to value))) }
        val children = paragraphs.map { text ->
            mapOf("object" to "block", "type" to "paragraph", "paragraph" to mapOf("rich_text" to richText(text)))
        }
        val body = mapOf(
            "parent" to mapOf("type" to "page_id", "page_id" to parentPageId),
            "properties" to mapOf("title" to mapOf("type" to "title", "title" to richText(title))),
            "children" to children,
        )
        val response = client.post().uri("/v1/pages").header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
            .header("Notion-Version", apiVersion).contentType(MediaType.APPLICATION_JSON).body(body)
            .retrieve().body(Map::class.java) as? Map<*, *> ?: error("Notion page response was empty")
        NotionCreatedPage(response["id"]?.toString() ?: error("Notion page id was empty"), response["url"]?.toString())
    }

    private fun token(body: Map<String, String>): NotionOauthResult {
        val basic = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray(StandardCharsets.UTF_8))
        return client.post().uri("/v1/oauth/token").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Basic $basic").header("Notion-Version", apiVersion)
            .body(body).retrieve().body(NotionOauthResult::class.java) ?: error("Notion token response was empty")
    }

    private fun <T> authorized(block: () -> T): T = try {
        block()
    } catch (error: org.springframework.web.client.HttpClientErrorException.Unauthorized) {
        throw NotionTokenInvalidException()
    }

    private fun extractTitle(item: Map<*, *>): String {
        val properties = item["properties"] as? Map<*, *> ?: return ""
        return properties.values.asSequence().mapNotNull { it as? Map<*, *> }
            .firstOrNull { it["type"] == "title" }?.get("title").let { it as? List<*> }
            ?.mapNotNull { (it as? Map<*, *>)?.get("plain_text")?.toString() }?.joinToString("").orEmpty()
    }
}
