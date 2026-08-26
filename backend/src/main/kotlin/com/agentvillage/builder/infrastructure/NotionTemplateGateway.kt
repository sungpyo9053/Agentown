package com.agentvillage.builder.infrastructure

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

data class NotionTemplateRecord(
    val pageId: String,
    val name: String,
    val templateKey: String,
    val declaredVersion: Int,
    val status: String,
    val category: String,
    val intentExamples: List<String>,
    val requiredFacts: List<String>,
    val templateDefinitionJson: String,
    val outputSchemaJson: String,
    val acceptanceCasesJson: String,
)

interface NotionTemplateSource { fun fetchApproved(): List<NotionTemplateRecord> }

@Component
class HttpNotionTemplateSource(
    builder: RestClient.Builder,
    private val mapper: ObjectMapper,
    @Value("\${notion.template-library.api-base-url:https://api.notion.com}") baseUrl: String,
    @Value("\${notion.template-library.token:}") private val token: String,
    @Value("\${notion.template-library.data-source-id:}") private val dataSourceId: String,
    @Value("\${notion.template-library.api-version:2026-03-11}") private val apiVersion: String,
) : NotionTemplateSource {
    private val client = builder.baseUrl(baseUrl.removeSuffix("/")).build()

    override fun fetchApproved(): List<NotionTemplateRecord> {
        require(token.isNotBlank() && dataSourceId.isNotBlank()) { "Notion template library is not configured" }
        val response = client.post().uri("/v1/data_sources/{id}/query", dataSourceId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token").header("Notion-Version", apiVersion)
            .body(mapOf("filter" to mapOf("property" to "Status", "select" to mapOf("equals" to "APPROVED")), "page_size" to 100))
            .retrieve().body(object : org.springframework.core.ParameterizedTypeReference<Map<String, Any?>>() {}) ?: emptyMap()
        if (response["has_more"] == true) error("Notion template result pagination is not supported yet")
        @Suppress("UNCHECKED_CAST") val pages = response["results"] as? List<Map<String, Any?>> ?: emptyList()
        return pages.map(::parsePage)
    }

    private fun parsePage(page: Map<String, Any?>): NotionTemplateRecord {
        @Suppress("UNCHECKED_CAST") val properties = page["properties"] as? Map<String, Any?> ?: error("Notion properties missing")
        val pageId = page["id"]?.toString() ?: error("Notion page id missing")
        val blocks = blocks(pageId)
        return NotionTemplateRecord(
            pageId, title(properties, "Name"), richText(properties, "Template Key"), number(properties, "Version"), select(properties, "Status"),
            select(properties, "Category"), richText(properties, "Intent Examples").lines().map(String::trim).filter(String::isNotBlank),
            multiSelect(properties, "Required Facts"), blocks.getValue("template-definition.json"), blocks.getValue("final-output.schema.json"), blocks.getValue("acceptance-cases.json"),
        )
    }

    private fun blocks(pageId: String): Map<String, String> {
        val response = client.get().uri("/v1/blocks/{id}/children?page_size=100", pageId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token").header("Notion-Version", apiVersion)
            .retrieve().body(object : org.springframework.core.ParameterizedTypeReference<Map<String, Any?>>() {}) ?: emptyMap()
        if (response["has_more"] == true) error("Notion template block pagination is not supported yet")
        @Suppress("UNCHECKED_CAST") val results = response["results"] as? List<Map<String, Any?>> ?: emptyList()
        return results.mapNotNull { block ->
            @Suppress("UNCHECKED_CAST") val code = block["code"] as? Map<String, Any?> ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST") val caption = code["caption"] as? List<Map<String, Any?>> ?: emptyList()
            @Suppress("UNCHECKED_CAST") val rich = code["rich_text"] as? List<Map<String, Any?>> ?: emptyList()
            val name = caption.joinToString("") { it["plain_text"]?.toString().orEmpty() }.trim()
            val value = rich.joinToString("") { it["plain_text"]?.toString().orEmpty() }
            name.takeIf(String::isNotBlank)?.let { it to value }
        }.toMap()
    }

    @Suppress("UNCHECKED_CAST") private fun property(map: Map<String, Any?>, name: String) = map[name] as? Map<String, Any?> ?: error("Notion property missing: $name")
    @Suppress("UNCHECKED_CAST") private fun title(map: Map<String, Any?>, name: String) = (property(map, name)["title"] as? List<Map<String, Any?>>).orEmpty().joinToString("") { it["plain_text"]?.toString().orEmpty() }.trim()
    @Suppress("UNCHECKED_CAST") private fun richText(map: Map<String, Any?>, name: String) = (property(map, name)["rich_text"] as? List<Map<String, Any?>>).orEmpty().joinToString("") { it["plain_text"]?.toString().orEmpty() }.trim()
    @Suppress("UNCHECKED_CAST") private fun select(map: Map<String, Any?>, name: String) = ((property(map, name)["select"] as? Map<String, Any?>)?.get("name")?.toString()).orEmpty()
    private fun number(map: Map<String, Any?>, name: String) = (property(map, name)["number"] as? Number)?.toInt() ?: error("Notion number missing: $name")
    @Suppress("UNCHECKED_CAST") private fun multiSelect(map: Map<String, Any?>, name: String) = (property(map, name)["multi_select"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { it["name"]?.toString() }
}
