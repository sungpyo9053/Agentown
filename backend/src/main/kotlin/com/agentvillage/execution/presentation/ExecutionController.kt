package com.agentvillage.execution.presentation

import com.agentvillage.execution.application.ExecutionService
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class CreateExecutionRequest(val input: Map<String, Any> = emptyMap(), val stubMode: Boolean = false)

@RestController
class ExecutionController(private val service: ExecutionService) {
    @PostMapping("/api/harnesses/{id}/executions")
    fun create(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID,
               @RequestHeader("Idempotency-Key") key: String, @RequestBody request: CreateExecutionRequest) =
        service.create(id, p.userId, key, request.input, request.stubMode)
    @GetMapping("/api/executions/{id}") fun get(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.get(id, p.userId)
    @PostMapping("/api/executions/{id}/cancel") fun cancel(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.cancel(id, p.userId)
    @PostMapping("/api/executions/{id}/approve") fun approve(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.approve(id, p.userId)
    @PostMapping("/api/executions/{id}/reject") fun reject(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.reject(id, p.userId)
    @GetMapping("/api/executions/{id}/history") fun history(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.history(id, p.userId)
    @GetMapping("/api/executions/{id}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(@AuthenticationPrincipal p: AuthenticatedUser, @PathVariable id: UUID) = service.subscribe(id, p.userId)
}
