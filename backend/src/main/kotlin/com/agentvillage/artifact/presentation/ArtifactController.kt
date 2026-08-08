package com.agentvillage.artifact.presentation

import com.agentvillage.artifact.application.ArtifactService
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.util.UUID
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.nio.charset.StandardCharsets

@RestController @RequestMapping("/api/artifacts")
class ArtifactController(private val service: ArtifactService, private val proxy: com.agentvillage.artifact.application.ArtifactProxyService) {
    @GetMapping fun list(
        @AuthenticationPrincipal p: AuthenticatedUser,
        @RequestParam executionId: UUID,
    ) = service.listOwned(executionId, p.userId)
    @GetMapping("/{id}") fun get(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.requireOwned(id, p.userId)
    @GetMapping("/{id}/download") fun download(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID,
                                                @RequestParam(defaultValue = "false") proxyDownload: Boolean): ResponseEntity<*> {
        val a = service.requireOwned(id, p.userId)
        if (!proxyDownload) return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, URI(a.externalUrl).toASCIIString()).build<Void>()
        val (artifact, handle) = proxy.open(id, p.userId)
        val stream = StreamingResponseBody { output -> handle.use { it.input.copyTo(output, 64 * 1024) } }
        val builder = ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, org.springframework.http.ContentDisposition.attachment()
                .filename(artifact.fileName, StandardCharsets.UTF_8).build().toString())
            .contentType(org.springframework.http.MediaType.parseMediaType(artifact.mimeType))
        handle.contentLength?.let(builder::contentLength)
        return builder.body(stream)
    }
}
