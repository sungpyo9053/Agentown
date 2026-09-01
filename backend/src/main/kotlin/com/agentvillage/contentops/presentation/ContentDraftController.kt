package com.agentvillage.contentops.presentation

import com.agentvillage.contentops.application.*
import com.agentvillage.contentops.domain.ContentChannel
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import com.agentvillage.llmcredential.domain.LlmProvider
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class GenerateContentDraftRequest(
    @field:NotBlank @field:Size(max = 120) val brandName: String,
    @field:NotBlank @field:Size(max = 200) val topic: String,
    @field:NotBlank @field:Size(max = 300) val audience: String,
    val channel: ContentChannel = ContentChannel.NAVER,
    @field:NotBlank @field:Size(max = 12_000) val sourceNotes: String,
    @field:Size(max = 12_000) val evidenceNotes: String = "",
    @field:Size(max = 1_000) val photoReferenceUrl: String? = null,
    @field:Size(max = 12_000) val photoNotes: String = "",
    @field:Size(max = 4_000) val styleNotes: String = "",
    val provider: LlmProvider = LlmProvider.OPENAI,
    @field:NotBlank @field:Size(max = 80) val model: String = "gpt-5.6-luna",
    val credentialId: UUID? = null,
    val usePersonalAi: Boolean = false,
) {
    fun toCommand(idempotencyKey: String) = GenerateContentDraftCommand(
        idempotencyKey, brandName, topic, audience, channel, sourceNotes, evidenceNotes,
        photoReferenceUrl, photoNotes, styleNotes, provider, model, credentialId, usePersonalAi,
    )
}

data class UpdateContentDraftRequest(
    @field:NotBlank @field:Size(max = 200) val title: String,
    @field:NotBlank @field:Size(max = 30_000) val bodyMarkdown: String,
    @field:Size(max = 200) val seoTitle: String = "",
    @field:Size(max = 500) val metaDescription: String = "",
    @field:Size(max = 8) val targetKeywords: List<@NotBlank @Size(max = 80) String> = emptyList(),
) {
    fun toCommand() = UpdateContentDraftCommand(title, bodyMarkdown, seoTitle, metaDescription, targetKeywords)
}

data class ApproveContentDraftRequest(val evidenceConfirmed: Boolean = false, val photoRightsConfirmed: Boolean = false) {
    fun toCommand() = ApproveContentDraftCommand(evidenceConfirmed, photoRightsConfirmed)
}

@RestController
@RequestMapping("/api/content-operations")
class ContentDraftController(private val service: ContentDraftService) {
    @GetMapping("/drafts")
    fun list(@AuthenticationPrincipal user: AuthenticatedUser) = service.list(user.userId)

    @GetMapping("/drafts/{id}")
    fun get(@AuthenticationPrincipal user: AuthenticatedUser, @PathVariable id: UUID) = service.get(user.userId, id)

    @GetMapping("/usage")
    fun usage(@AuthenticationPrincipal user: AuthenticatedUser) = service.usage(user.userId)

    @PostMapping("/drafts/generate")
    fun generate(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: GenerateContentDraftRequest,
    ) = service.generate(user.userId, request.toCommand(idempotencyKey))

    @PatchMapping("/drafts/{id}")
    fun update(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateContentDraftRequest,
    ) = service.update(user.userId, id, request.toCommand())

    @PostMapping("/drafts/{id}/approve")
    fun approve(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestBody request: ApproveContentDraftRequest,
    ) = service.approve(user.userId, id, request.toCommand())
}
