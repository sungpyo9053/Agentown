package com.agentvillage.builder.presentation

import com.agentvillage.builder.application.BuilderGenerationService
import com.agentvillage.builder.application.BuilderService
import com.agentvillage.builder.application.AgentDefinitionUpdate
import com.agentvillage.builder.application.TFrameXFlowImport
import com.agentvillage.builder.domain.BuilderConversationPurpose
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/agent-development")
class AgentDevelopmentController(
    private val service: BuilderService,
    private val generation: BuilderGenerationService,
) {
    data class AgentDefinitionUpdateRequest(
        val name: String,
        val role: String,
        val behaviorRules: List<String> = emptyList(),
        val forbiddenRules: List<String> = emptyList(),
        val evidenceRequirements: List<String> = emptyList(),
        val toolKeys: List<String> = emptyList(),
        val skillKeys: List<String> = emptyList(),
        val memoryScope: String = "NONE",
    )
    data class TFrameXFlowImportRequest(
        val baseVersionId: UUID,
        val expectedGraphHash: String,
        val tframexCommit: String,
        val designBundle: Map<String, Any?>,
        val runtimeDefinition: Map<String, Any?>,
    )
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
        return generation.enqueue(user.userId, sessionId, request.content, key, BuilderConversationPurpose.AGENT_DEVELOPMENT)
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

    @PutMapping("/sessions/{sessionId}/agents/{agentKey}")
    fun updateAgent(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable sessionId: UUID,
        @PathVariable agentKey: String,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody request: AgentDefinitionUpdateRequest,
    ): Any {
        service.requireConversationPurpose(user.userId, sessionId, BuilderConversationPurpose.AGENT_DEVELOPMENT)
        val snapshot = service.snapshot(user.userId, sessionId)
        return service.updateAgentDefinition(user.userId, snapshot.workflowId, agentKey, AgentDefinitionUpdate(
            request.name, request.role, request.behaviorRules, request.forbiddenRules,
            request.evidenceRequirements, request.toolKeys, request.skillKeys, request.memoryScope,
        ), key)
    }

    @PostMapping("/sessions/{sessionId}/simulations")
    fun simulate(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable sessionId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody request: SimulationRequest,
    ): Any {
        service.requireConversationPurpose(user.userId, sessionId, BuilderConversationPurpose.AGENT_DEVELOPMENT)
        val snapshot = service.snapshot(user.userId, sessionId)
        return service.startSimulation(user.userId, snapshot.workflowId, request.input, key)
    }

    @GetMapping("/runs/{runId}")
    fun run(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable runId: UUID): Any {
        service.requireRunPurpose(user.userId, runId, BuilderConversationPurpose.AGENT_DEVELOPMENT)
        return service.getRun(user.userId, runId)
    }

    @PostMapping("/runs/{runId}/decision")
    fun decideRun(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable runId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody request: ExecutionDecisionRequest,
    ): Any {
        service.requireRunPurpose(user.userId, runId, BuilderConversationPurpose.AGENT_DEVELOPMENT)
        return service.decideExecution(user.userId, runId, request.approve, key)
    }

    @PostMapping("/sessions/{sessionId}/versions/{versionId}/restore")
    fun restoreVersion(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable sessionId: UUID,
        @PathVariable versionId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
    ): Any {
        service.requireConversationPurpose(user.userId, sessionId, BuilderConversationPurpose.AGENT_DEVELOPMENT)
        val snapshot = service.snapshot(user.userId, sessionId)
        return service.restoreVersion(user.userId, snapshot.workflowId, versionId, key)
    }

    @GetMapping("/jobs/{jobId}")
    fun job(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable jobId: UUID) = generation.get(user.userId, jobId, BuilderConversationPurpose.AGENT_DEVELOPMENT)

    @PostMapping("/jobs/{jobId}/cancel")
    fun cancel(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable jobId: UUID, @RequestHeader("Idempotency-Key") key: String) =
        generation.cancel(user.userId, jobId, key, BuilderConversationPurpose.AGENT_DEVELOPMENT)

    @GetMapping("/sessions/{sessionId}/package")
    fun downloadPackage(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable sessionId: UUID): ResponseEntity<ByteArray> {
        service.requireConversationPurpose(user.userId, sessionId, BuilderConversationPurpose.AGENT_DEVELOPMENT)
        val snapshot = service.snapshot(user.userId, sessionId)
        val files = service.harnessPackage(user.userId, snapshot.workflowId)
        val bytes = AgentPackageArchive.create(files)
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(AgentPackageArchive.FILE_NAME).build().toString())
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(bytes)
    }

    @GetMapping("/sessions/{sessionId}/flow")
    fun exportFlow(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable sessionId: UUID): Any {
        service.requireConversationPurpose(user.userId, sessionId, BuilderConversationPurpose.AGENT_DEVELOPMENT)
        val snapshot = service.snapshot(user.userId, sessionId)
        return service.exportTFrameXFlow(user.userId, snapshot.workflowId)
    }

    @PostMapping("/sessions/{sessionId}/flow/import")
    fun importFlow(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable sessionId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody request: TFrameXFlowImportRequest,
    ): Any {
        service.requireConversationPurpose(user.userId, sessionId, BuilderConversationPurpose.AGENT_DEVELOPMENT)
        val snapshot = service.snapshot(user.userId, sessionId)
        return service.importTFrameXFlow(user.userId, snapshot.workflowId, TFrameXFlowImport(
            request.baseVersionId,
            request.expectedGraphHash,
            request.tframexCommit,
            request.designBundle,
            request.runtimeDefinition,
        ), key)
    }
}
