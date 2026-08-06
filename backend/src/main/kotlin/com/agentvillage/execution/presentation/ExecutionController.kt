package com.agentvillage.execution.presentation

import com.agentvillage.execution.application.ExecutionService
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class CreateExecutionRequest(val input: Map<String, Any> = emptyMap(), val stubMode: Boolean = false)

@RestController
class ExecutionController(private val service: ExecutionService, private val mapper: ObjectMapper) {
    @PostMapping("/api/harnesses/{id}/executions")
    fun create(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID,
               @RequestHeader("Idempotency-Key") key: String, @RequestBody request: CreateExecutionRequest) =
        service.create(id, p.userId, key, request.input, request.stubMode)
    @GetMapping("/api/executions/{id}") fun get(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.get(id, p.userId)
    @PostMapping("/api/executions/{id}/cancel") fun cancel(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.cancel(id, p.userId)
    @PostMapping("/api/executions/{id}/approve") fun approve(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.approve(id, p.userId)
    @PostMapping("/api/executions/{id}/reject") fun reject(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.reject(id, p.userId)
    @GetMapping("/api/executions/{id}/history") fun history(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.history(id, p.userId)
    @GetMapping("/api/executions/{id}/download")
    fun download(
        @AuthenticationPrincipal p: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "markdown") format: String,
    ): ResponseEntity<ByteArray> {
        val output = service.result(id, p.userId)
        val markdown = extractArticle(output)
        val isJson = format.equals("json", ignoreCase = true)
        val bytes = if (isJson) mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(output) else markdown.toByteArray()
        val filename = "agentown-result-$id.${if (isJson) "json" else "md"}"
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
            .contentType(if (isJson) MediaType.APPLICATION_JSON else MediaType.parseMediaType("text/markdown;charset=UTF-8"))
            .body(bytes)
    }
    @GetMapping("/api/executions/{id}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.subscribe(id, p.userId)

    private fun extractArticle(output: Map<String, Any>): String {
        val stages = output.values.filterIsInstance<Map<*, *>>()
        val writer = stages.firstOrNull { stage ->
            val agent = stage["agent"]?.toString()?.lowercase().orEmpty()
            val result = stage["result"]?.toString().orEmpty()
            "writer" in agent || "작가" in agent || "작성" in agent || result.startsWith("---\n")
        }
        return writer?.get("result")?.toString() ?: output["result"]?.toString()
        ?: mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output)
    }
}
