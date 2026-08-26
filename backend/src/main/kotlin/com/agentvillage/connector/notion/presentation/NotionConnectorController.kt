package com.agentvillage.connector.notion.presentation

import com.agentvillage.connector.notion.application.NotionConnectorService
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

@RestController
@RequestMapping("/api/connectors/notion")
class NotionConnectorController(
    private val service: NotionConnectorService,
    @Value("\${connectors.notion.frontend-result-url:http://localhost:3000/settings/connections}") private val frontendResultUrl: String,
) {
    @GetMapping fun status(@AuthenticationPrincipal user: AuthenticatedUser) = service.status(user.userId)
    @PostMapping("/oauth/start") fun start(@AuthenticationPrincipal user: AuthenticatedUser) = service.start(user.userId)
    @GetMapping("/oauth/callback")
    fun callback(@RequestParam(required = false) code: String?, @RequestParam(required = false) state: String?, @RequestParam(required = false) error: String?, response: HttpServletResponse) {
        val result = runCatching { service.complete(code, state, error) }
        val query = if (result.isSuccess) "notion=connected" else "notion=failed&reason=${URLEncoder.encode(result.exceptionOrNull()?.message ?: "unknown", StandardCharsets.UTF_8)}"
        response.sendRedirect("$frontendResultUrl?$query")
    }
    @PostMapping("/{connectionId}/verify") fun verify(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable connectionId: UUID, @RequestBody(required = false) body: Map<String, String>?) =
        service.verifyRead(user.userId, connectionId, body?.get("query").orEmpty())
    @DeleteMapping("/{connectionId}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    fun revoke(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable connectionId: UUID) = service.revoke(user.userId, connectionId)
}
