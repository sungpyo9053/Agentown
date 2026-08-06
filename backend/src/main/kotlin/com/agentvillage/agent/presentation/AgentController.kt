package com.agentvillage.agent.presentation

import com.agentvillage.agent.application.AgentService
import com.agentvillage.agent.application.AgentDefinitionService
import com.agentvillage.agent.application.GenerateDefinitionCommand
import com.agentvillage.agent.application.SaveAgentCommand
import com.agentvillage.agent.domain.Agent
import com.agentvillage.common.domain.Visibility
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import com.agentvillage.llmcredential.domain.LlmProvider
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class SaveAgentRequest(
    @field:NotBlank @field:Size(max = 40) val name: String,
    @field:NotBlank @field:Size(max = 100) val role: String,
    @field:Size(max = 500) val personality: String? = null,
    @field:Pattern(regexp = "^(writer|reviewer|designer|developer|manager)$") val characterKey: String,
    @field:Size(max = 20_000) val systemPrompt: String? = null,
    @field:NotBlank @field:Size(max = 20_000) val script: String,
    @field:Size(max = 20_000) val guide: String? = null,
    val modelProvider: LlmProvider = LlmProvider.OPENAI,
    @field:NotBlank @field:Size(max = 80) val modelName: String = "gpt-4o-mini",
    val credentialId: UUID? = null,
    @field:DecimalMin("0.0") @field:DecimalMax("2.0") val temperature: BigDecimal = BigDecimal("0.70"),
    @field:Min(1) @field:Max(32768) val maxOutputTokens: Int = 2048,
    @field:Min(1) @field:Max(600) val timeoutSeconds: Int = 60,
    val providerOptions: Map<String, Any> = emptyMap(),
    val visibility: Visibility = Visibility.PRIVATE,
) {
    fun toCommand() = SaveAgentCommand(
        name, role, personality, characterKey, systemPrompt, script, guide,
        modelProvider, modelName, credentialId, temperature, maxOutputTokens, timeoutSeconds, providerOptions, visibility,
    )
}

data class AgentResponse(
    val id: UUID,
    val name: String,
    val role: String,
    val personality: String?,
    val characterKey: String,
    val systemPrompt: String?,
    val script: String,
    val guide: String?,
    val modelProvider: LlmProvider,
    val modelName: String,
    val credentialId: UUID?,
    val temperature: BigDecimal,
    val maxOutputTokens: Int,
    val timeoutSeconds: Int,
    val providerOptions: Map<String, Any>,
    val visibility: Visibility,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(agent: Agent) = AgentResponse(
            agent.id, agent.name, agent.role, agent.personality, agent.characterKey,
            agent.systemPrompt, agent.script, agent.guide, agent.modelProvider, agent.modelName, agent.credentialId,
            agent.temperature, agent.maxOutputTokens, agent.timeoutSeconds, agent.providerOptions,
            agent.visibility, agent.createdAt, agent.updatedAt,
        )
    }
}

@RestController
@RequestMapping("/api/agents")
class AgentController(private val agentService: AgentService) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal principal: AuthenticatedUser,
        @Valid @RequestBody request: SaveAgentRequest,
    ) = AgentResponse.from(agentService.create(principal.userId, request.toCommand()))

    @GetMapping
    fun list(@AuthenticationPrincipal principal: AuthenticatedUser) =
        agentService.list(principal.userId).map(AgentResponse::from)

    @GetMapping("/{id}")
    fun get(@AuthenticationPrincipal principal: AuthenticatedUser, @PathVariable id: UUID) =
        AgentResponse.from(agentService.getOwned(id, principal.userId))

    @PatchMapping("/{id}")
    fun update(
        @AuthenticationPrincipal principal: AuthenticatedUser,
        @PathVariable id: UUID,
        @Valid @RequestBody request: SaveAgentRequest,
    ) = AgentResponse.from(agentService.update(id, principal.userId, request.toCommand()))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal principal: AuthenticatedUser, @PathVariable id: UUID) {
        agentService.delete(id, principal.userId)
    }
}

data class GenerateDefinitionRequest(
    @field:Size(max = 20_000) val taskDescription: String = "",
    @field:Size(max = 10_000) val desiredOutput: String = "",
    @field:Size(max = 10_000) val prohibitions: String = "",
    val inputSchema: Map<String, Any>? = null,
    val outputSchema: Map<String, Any>? = null,
    @field:Size(max = 10_000) val requiredEvidence: String = "",
    @field:Size(max = 10_000) val outputStyle: String = "",
    @field:Size(max = 10_000) val rewriteCriteria: String = "",
    @field:Size(max = 10_000) val approvalCriteria: String = "",
)

@RestController
@RequestMapping("/api/agents")
class AgentDefinitionController(private val definitions: AgentDefinitionService) {
    @PostMapping("/{id}/generate-definition")
    fun generate(
        @AuthenticationPrincipal principal: AuthenticatedUser,
        @PathVariable id: UUID,
        @Valid @RequestBody request: GenerateDefinitionRequest,
    ) = definitions.generate(id, principal.userId, GenerateDefinitionCommand(
        request.taskDescription,
        request.desiredOutput,
        request.prohibitions,
        request.inputSchema,
        request.outputSchema,
        request.requiredEvidence,
        request.outputStyle,
        request.rewriteCriteria,
        request.approvalCriteria,
    ))

    @GetMapping("/{id}/definition")
    fun get(@AuthenticationPrincipal principal: AuthenticatedUser, @PathVariable id: UUID) =
        definitions.get(id, principal.userId)
}
