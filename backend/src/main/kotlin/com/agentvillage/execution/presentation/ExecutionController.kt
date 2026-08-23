package com.agentvillage.execution.presentation

import com.agentvillage.execution.application.ExecutionService
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.harness.domain.HarnessResultFormat
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.util.HtmlUtils
import java.nio.charset.StandardCharsets
import java.util.UUID

data class CreateExecutionRequest(val input: Map<String, Any> = emptyMap(), val stubMode: Boolean = false, val executionMode: com.agentvillage.execution.domain.ExecutionMode? = null)

@RestController
class ExecutionController(private val service: ExecutionService, private val mapper: ObjectMapper) {
    @GetMapping("/api/executions") fun list(@AuthenticationPrincipal p: AuthenticatedUser) = service.list(p.userId)
    @PostMapping("/api/harnesses/{id}/executions")
    fun create(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID,
               @RequestHeader("Idempotency-Key") key: String, @RequestBody request: CreateExecutionRequest) =
        service.create(id, p.userId, key, request.input, request.stubMode, request.executionMode ?: if (request.stubMode) com.agentvillage.execution.domain.ExecutionMode.STUB else com.agentvillage.execution.domain.ExecutionMode.CLOUD_API)
    @GetMapping("/api/executions/{id}") fun get(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) =
        ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(service.get(id, p.userId))
    @PostMapping("/api/executions/{id}/cancel") fun cancel(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.cancel(id, p.userId)
    @PostMapping("/api/executions/{id}/approve") fun approve(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.approve(id, p.userId)
    @PostMapping("/api/executions/{id}/reject") fun reject(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.reject(id, p.userId)
    @GetMapping("/api/executions/{id}/history") fun history(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) =
        ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(service.history(id, p.userId))
    @GetMapping("/api/executions/{id}/download")
    fun download(
        @AuthenticationPrincipal p: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestParam(required = false) format: String?,
    ): ResponseEntity<ByteArray> {
        val result = service.result(id, p.userId)
        if (format.equals("debug-json", ignoreCase = true)) return downloadable(
            id, "execution.json", MediaType.APPLICATION_JSON,
            mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result.output), "DEBUG_JSON",
        )
        val source = extractResult(result.output, result.resultStepKey)
        val configured = resolveFormat(result.format, source)
        val requested = format?.let(::parseFormat) ?: configured
        if (requested != configured) throw BadRequestException("RESULT_FORMAT_MISMATCH", "이 하네스의 결과 형식은 ${configured.name}입니다.")
        if (configured == HarnessResultFormat.EXTERNAL) throw BadRequestException("RESULT_EXTERNAL_ONLY", "외부 생성 결과는 결과물 목록에서 내려받아 주세요.")
        val body = when (configured) {
            HarnessResultFormat.JSON -> mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(source)
            HarnessResultFormat.HTML -> renderHtml(sourceText(source)).toByteArray(StandardCharsets.UTF_8)
            HarnessResultFormat.CSV -> renderCsv(source).toByteArray(StandardCharsets.UTF_8)
            else -> sourceText(source).toByteArray(StandardCharsets.UTF_8)
        }
        val extension = extension(configured)
        val contentType = mediaType(configured)
        val filename = "agentown-result-$id.$extension"
        return downloadable(id, filename, contentType, body, configured.name)
    }

    private fun downloadable(id: UUID, filename: String, contentType: MediaType, body: ByteArray, format: String): ResponseEntity<ByteArray> =
        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
            .header("X-Agentown-Result-Format", format)
            .contentType(contentType)
            .body(body)
    @GetMapping("/api/executions/{id}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) =
        ResponseEntity.ok()
            .cacheControl(org.springframework.http.CacheControl.noStore())
            .header("X-Accel-Buffering", "no")
            .body(service.subscribe(id, p.userId))

    private fun extractResult(output: Map<String, Any>, resultStepKey: String?): Any {
        val configured = resultStepKey?.let(output::get)
        if (configured is Map<*, *>) return configured["result"] ?: configured
        if (configured != null) return configured
        val stages = output.values.filterIsInstance<Map<*, *>>()
        val writer = stages.firstOrNull { stage ->
            val agent = stage["agent"]?.toString()?.lowercase().orEmpty()
            val result = stage["result"]?.toString().orEmpty()
            "writer" in agent || "작가" in agent || "작성" in agent || result.startsWith("---\n")
        }
        return writer?.get("result") ?: output["result"] ?: output
    }

    private fun resolveFormat(configured: HarnessResultFormat, source: Any): HarnessResultFormat = when {
        configured != HarnessResultFormat.AUTO -> configured
        source !is String -> HarnessResultFormat.JSON
        source.trimStart().startsWith("<html", true) || source.trimStart().startsWith("<!doctype html", true) -> HarnessResultFormat.HTML
        source.lines().any { it.startsWith("# ") || it.startsWith("## ") } -> HarnessResultFormat.MARKDOWN
        else -> HarnessResultFormat.TEXT
    }

    private fun parseFormat(value: String): HarnessResultFormat = runCatching {
        HarnessResultFormat.valueOf(value.uppercase().let { if (it == "MD") "MARKDOWN" else it })
    }.getOrElse { throw BadRequestException("RESULT_FORMAT_NOT_SUPPORTED", "지원하지 않는 결과 형식입니다.") }

    private fun extension(format: HarnessResultFormat) = when (format) {
        HarnessResultFormat.TEXT, HarnessResultFormat.AUTO -> "txt"
        HarnessResultFormat.MARKDOWN -> "md"
        HarnessResultFormat.HTML -> "html"
        HarnessResultFormat.JSON -> "json"
        HarnessResultFormat.CSV -> "csv"
        HarnessResultFormat.EXTERNAL -> "bin"
    }

    private fun mediaType(format: HarnessResultFormat) = when (format) {
        HarnessResultFormat.TEXT, HarnessResultFormat.AUTO -> MediaType.parseMediaType("text/plain;charset=UTF-8")
        HarnessResultFormat.MARKDOWN -> MediaType.parseMediaType("text/markdown;charset=UTF-8")
        HarnessResultFormat.HTML -> MediaType.TEXT_HTML
        HarnessResultFormat.JSON -> MediaType.APPLICATION_JSON
        HarnessResultFormat.CSV -> MediaType.parseMediaType("text/csv;charset=UTF-8")
        HarnessResultFormat.EXTERNAL -> MediaType.APPLICATION_OCTET_STREAM
    }

    private fun sourceText(source: Any) = if (source is String) source else mapper.writerWithDefaultPrettyPrinter().writeValueAsString(source)

    private fun renderCsv(source: Any): String {
        if (source is String) return source
        val rows = source as? List<*> ?: return sourceText(source)
        val maps = rows.filterIsInstance<Map<*, *>>()
        if (maps.isEmpty()) return sourceText(source)
        val headers = maps.flatMap { it.keys.map(Any?::toString) }.distinct()
        fun cell(value: Any?) = "\"${value?.toString().orEmpty().replace("\"", "\"\"")}\""
        return buildString {
            appendLine(headers.joinToString(",", transform = ::cell))
            maps.forEach { row -> appendLine(headers.joinToString(",") { cell(row[it]) }) }
        }
    }

    private fun renderHtml(markdown: String): String {
        val rawLines = markdown.lines()
        val lines = if (rawLines.firstOrNull()?.trim() == "---") {
            val closing = rawLines.drop(1).indexOfFirst { it.trim() == "---" }
            if (closing >= 0) rawLines.drop(closing + 2) else rawLines
        } else rawLines
        val article = StringBuilder()
        var inList = false
        fun closeList() { if (inList) { article.append("</ul>\n"); inList = false } }
        lines.forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("### ") -> { closeList(); article.append("<h3>").append(escape(trimmed.drop(4))).append("</h3>\n") }
                trimmed.startsWith("## ") -> { closeList(); article.append("<h2>").append(escape(trimmed.drop(3))).append("</h2>\n") }
                trimmed.startsWith("# ") -> { closeList(); article.append("<h1>").append(escape(trimmed.drop(2))).append("</h1>\n") }
                trimmed.startsWith("- ") -> {
                    if (!inList) { article.append("<ul>\n"); inList = true }
                    article.append("<li>").append(escape(trimmed.drop(2))).append("</li>\n")
                }
                trimmed.isBlank() -> closeList()
                else -> { closeList(); article.append("<p>").append(escape(trimmed)).append("</p>\n") }
            }
        }
        closeList()
        val title = lines.firstOrNull { it.trim().startsWith("# ") }?.trim()?.drop(2)?.ifBlank { null } ?: "Agentown 실행 결과"
        return """<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escape(title)}</title><style>body{margin:0;background:#f6f1e8;color:#25221f;font-family:system-ui,-apple-system,sans-serif;line-height:1.75}main{max-width:820px;margin:48px auto;padding:48px;background:#fff;border-radius:24px;box-shadow:0 16px 48px #4b39221a}h1{font-size:2.35rem;line-height:1.2}h2{margin-top:2rem}h3{margin-top:1.5rem}li{margin:.35rem 0}@media(max-width:700px){main{margin:0;padding:28px;border-radius:0}}</style></head>
<body><main>$article</main></body></html>"""
    }

    private fun escape(value: String) = HtmlUtils.htmlEscape(value, StandardCharsets.UTF_8.name())
}
