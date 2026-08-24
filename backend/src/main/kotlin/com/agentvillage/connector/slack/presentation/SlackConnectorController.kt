package com.agentvillage.connector.slack.presentation

import com.agentvillage.connector.slack.application.SlackConnectorService
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

@RestController
@RequestMapping("/api/connectors/slack")
class SlackConnectorController(
    private val service: SlackConnectorService,
    @Value("\${connectors.slack.frontend-result-url:http://localhost:3000/settings/connections}") private val frontendResultUrl: String,
) {
    @GetMapping fun status(@AuthenticationPrincipal user: AuthenticatedUser) = service.status(user.userId)
    @PostMapping("/oauth/start") fun start(@AuthenticationPrincipal user: AuthenticatedUser) = service.start(user.userId)
    @GetMapping("/oauth/callback")
    fun callback(@RequestParam(required = false) code: String?, @RequestParam(required = false) state: String?, @RequestParam(required = false) error: String?, response: HttpServletResponse) {
        val result = runCatching { service.complete(code, state, error) }
        val query = if (result.isSuccess) "slack=connected" else "slack=failed&reason=${URLEncoder.encode(result.exceptionOrNull()?.message ?: "unknown", StandardCharsets.UTF_8)}"
        response.sendRedirect("$frontendResultUrl?$query")
    }
    @DeleteMapping("/{connectionId}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    fun revoke(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable connectionId: UUID) = service.revoke(user.userId, connectionId)

    @PostMapping("/events")
    fun events(@RequestHeader("X-Slack-Request-Timestamp", required = false) timestamp: String?, @RequestHeader("X-Slack-Signature", required = false) signature: String?, @RequestBody rawBody: String): ResponseEntity<Any> {
        val (challenge, receipt) = service.receive(timestamp, signature, rawBody)
        return ResponseEntity.ok(challenge ?: receipt)
    }
}
