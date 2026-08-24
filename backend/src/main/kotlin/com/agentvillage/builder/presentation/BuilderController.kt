package com.agentvillage.builder.presentation

import com.agentvillage.builder.application.BuilderService
import com.agentvillage.builder.application.WorkflowNodeCatalog
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

data class BuilderMessageRequest(@field:NotBlank @field:Size(max = 4_000) val content: String)
data class DesignDecisionRequest(val approve: Boolean)
data class GraphPatchRequest(@field:NotBlank @field:Size(max = 2_000) val instruction: String, val baseVersionId: UUID, @field:NotBlank val expectedGraphHash: String)
data class SimulationRequest(val input: Map<String, Any?> = emptyMap())
data class ExecutionDecisionRequest(val approve: Boolean)

@RestController
@RequestMapping("/api/builder")
class BuilderController(private val service: BuilderService, private val generation: com.agentvillage.builder.application.BuilderGenerationService, private val catalog: WorkflowNodeCatalog) {
    @PostMapping("/conversations")
    fun create(@AuthenticationPrincipal user: AuthenticatedUser, @RequestHeader("Idempotency-Key") key: String) = service.createConversation(user.userId, key)

    @GetMapping("/conversations/{conversationId}")
    fun get(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable conversationId: UUID) = service.snapshot(user.userId, conversationId)

    @GetMapping("/conversations")
    fun conversations(@AuthenticationPrincipal user: AuthenticatedUser) = service.listConversations(user.userId)

    @PostMapping("/conversations/{conversationId}/messages")
    fun message(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable conversationId: UUID, @RequestHeader("Idempotency-Key") key: String, @Valid @RequestBody request: BuilderMessageRequest) =
        generation.enqueue(user.userId, conversationId, request.content, key)

    @GetMapping("/generation-jobs/{jobId}")
    fun generationJob(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable jobId: UUID) = generation.get(user.userId, jobId)

    @PostMapping("/generation-jobs/{jobId}/cancel")
    fun cancelGeneration(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable jobId: UUID, @RequestHeader("Idempotency-Key") key: String) = generation.cancel(user.userId, jobId, key)

    @GetMapping("/conversations/{conversationId}/requirement")
    fun requirement(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable conversationId: UUID) = service.snapshot(user.userId, conversationId).requirement

    @GetMapping("/conversations/{conversationId}/proposal")
    fun proposal(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable conversationId: UUID) = service.snapshot(user.userId, conversationId).let { mapOf("proposal" to it.proposal, "agents" to it.agentDefinitions, "guides" to it.guideDefinitions) }

    @PostMapping("/workflows/{workflowId}/design-decision")
    fun decideDesign(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable workflowId: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody request: DesignDecisionRequest) =
        service.decideDesign(user.userId, workflowId, request.approve, key)

    @GetMapping("/workflows/{workflowId}/graph")
    fun graph(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable workflowId: UUID) = service.workflowSnapshot(user.userId, workflowId).let { mapOf("graph" to it.graph, "validation" to it.validation, "currentVersionId" to it.currentVersionId) }

    @GetMapping("/workflows/{workflowId}/package")
    fun downloadPackage(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable workflowId: UUID): ResponseEntity<ByteArray> {
        val files = service.harnessPackage(user.userId, workflowId)
        val bytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                files.toSortedMap().forEach { (path, content) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("agentown-workflow-$workflowId.zip").build().toString())
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(bytes)
    }

    @GetMapping("/workflows/{workflowId}/versions")
    fun versions(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable workflowId: UUID) = service.workflowSnapshot(user.userId, workflowId).versions

    @PostMapping("/workflows/{workflowId}/patches")
    fun patch(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable workflowId: UUID, @RequestHeader("Idempotency-Key") key: String, @Valid @RequestBody request: GraphPatchRequest) =
        service.applyPatch(user.userId, workflowId, request.instruction, request.baseVersionId, request.expectedGraphHash, key)

    @PostMapping("/workflows/{workflowId}/versions/{versionId}/restore")
    fun restore(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable workflowId: UUID, @PathVariable versionId: UUID, @RequestHeader("Idempotency-Key") key: String) =
        service.restoreVersion(user.userId, workflowId, versionId, key)

    @PostMapping("/workflows/{workflowId}/simulations")
    fun simulate(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable workflowId: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody request: SimulationRequest) =
        service.startSimulation(user.userId, workflowId, request.input, key)

    @GetMapping("/simulations/{runId}")
    fun simulation(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable runId: UUID) = service.getRun(user.userId, runId)

    @PostMapping("/simulations/{runId}/approval")
    fun executionApproval(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable runId: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody request: ExecutionDecisionRequest) =
        service.decideExecution(user.userId, runId, request.approve, key)

    @GetMapping("/conversations/{conversationId}/activation-readiness")
    fun readiness(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable conversationId: UUID) = service.snapshot(user.userId, conversationId).let {
        mapOf("ready" to (it.status.name == "READY_TO_ACTIVATE"), "status" to it.status, "blockingReasons" to if (it.status.name == "READY_TO_ACTIVATE") emptyList<String>() else listOf("검증된 시뮬레이션을 완료해야 합니다."))
    }

    @PostMapping("/workflows/{workflowId}/activate")
    fun activate(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable workflowId: UUID, @RequestHeader("Idempotency-Key") key: String) = service.activate(user.userId, workflowId, key)

    @PostMapping("/workflows/{workflowId}/stop")
    fun stop(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable workflowId: UUID, @RequestHeader("Idempotency-Key") key: String) = service.stop(user.userId, workflowId, key)

    @GetMapping("/active-automation-teams")
    fun activeTeams(@AuthenticationPrincipal user: AuthenticatedUser) = service.activeAutomationTeams(user.userId)

    @GetMapping("/node-catalog")
    fun nodeCatalog() = catalog.allowedTypes().sorted()
}
