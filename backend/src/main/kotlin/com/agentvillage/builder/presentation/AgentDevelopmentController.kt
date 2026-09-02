package com.agentvillage.builder.presentation

import com.agentvillage.builder.application.BuilderGenerationService
import com.agentvillage.builder.application.BuilderService
import com.agentvillage.builder.domain.BuilderConversationPurpose
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/agent-development")
class AgentDevelopmentController(
    private val service: BuilderService,
    private val generation: BuilderGenerationService,
) {
    @PostMapping("/sessions")
    fun create(@AuthenticationPrincipal user: AuthenticatedUser, @RequestHeader("Idempotency-Key") key: String) =
        service.createConversation(user.userId, key, BuilderConversationPurpose.AGENT_DEVELOPMENT)

    @GetMapping("/sessions")
    fun sessions(@AuthenticationPrincipal user: AuthenticatedUser) =
        service.listConversations(user.userId, BuilderConversationPurpose.AGENT_DEVELOPMENT)

    @GetMapping("/sessions/{sessionId}")
    fun get(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable sessionId: UUID): Any {
        service.requireConversationPurpose(user.userId, sessionId, BuilderConversationPurpose.AGENT_DEVELOPMENT)
        return service.snapshot(user.userId, sessionId)
    }

    @PostMapping("/sessions/{sessionId}/messages")
    fun message(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable sessionId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody request: BuilderMessageRequest,
    ): Any {
        service.requireConversationPurpose(user.userId, sessionId, BuilderConversationPurpose.AGENT_DEVELOPMENT)
        return generation.enqueue(user.userId, sessionId, request.content, key)
    }

    @PostMapping("/sessions/{sessionId}/design-decision")
    fun decideDesign(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable sessionId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody request: DesignDecisionRequest,
    ): Any {
        service.requireConversationPurpose(user.userId, sessionId, BuilderConversationPurpose.AGENT_DEVELOPMENT)
        val snapshot = service.snapshot(user.userId, sessionId)
        return service.decideDesign(user.userId, snapshot.workflowId, request.approve, key)
    }

    @PostMapping("/sessions/{sessionId}/patches")
    fun patch(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable sessionId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody request: GraphPatchRequest,
    ): Any {
        service.requireConversationPurpose(user.userId, sessionId, BuilderConversationPurpose.AGENT_DEVELOPMENT)
        val snapshot = service.snapshot(user.userId, sessionId)
        return service.applyPatch(user.userId, snapshot.workflowId, request.instruction, request.baseVersionId, request.expectedGraphHash, key)
    }

    @GetMapping("/jobs/{jobId}")
    fun job(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable jobId: UUID) = generation.get(user.userId, jobId)

    @PostMapping("/jobs/{jobId}/cancel")
    fun cancel(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable jobId: UUID, @RequestHeader("Idempotency-Key") key: String) =
        generation.cancel(user.userId, jobId, key)
}
