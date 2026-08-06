package com.agentvillage.designer.presentation

import com.agentvillage.designer.application.CompanyDesignCommand
import com.agentvillage.designer.application.CompanyDesignDraft
import com.agentvillage.designer.application.CompanyDesignerService
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import com.agentvillage.llmcredential.domain.LlmProvider
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class DesignCompanyRequest(
    @field:NotBlank @field:Size(max = 100) val companyName: String,
    @field:NotBlank @field:Size(max = 4_000) val goal: String,
    @field:NotBlank @field:Size(max = 4_000) val primaryInput: String,
    @field:NotBlank @field:Size(max = 4_000) val desiredOutput: String,
    @field:Size(max = 4_000) val requiredEvidence: String = "",
    @field:Size(max = 4_000) val prohibitions: String = "",
    @field:Size(max = 4_000) val approvalPolicy: String = "",
    val provider: LlmProvider = LlmProvider.OPENAI,
    @field:NotBlank @field:Size(max = 80) val model: String,
    val credentialId: UUID? = null,
    val stubMode: Boolean = false,
) {
    fun toCommand() = CompanyDesignCommand(companyName, goal, primaryInput, desiredOutput, requiredEvidence, prohibitions, approvalPolicy, provider, model, credentialId, stubMode)
}

data class ApplyCompanyDesignRequest(val draft: CompanyDesignDraft, val credentialId: UUID? = null, val stubMode: Boolean = false)

@RestController
@RequestMapping("/api/designer")
class CompanyDesignerController(private val designer: CompanyDesignerService) {
    @PostMapping("/companies/design")
    fun design(@AuthenticationPrincipal user: AuthenticatedUser, @Valid @RequestBody request: DesignCompanyRequest) =
        designer.design(user.userId, request.toCommand())

    @PostMapping("/companies/validate")
    fun validate(@AuthenticationPrincipal user: AuthenticatedUser, @RequestBody request: ApplyCompanyDesignRequest): Map<String, Any> {
        val errors = designer.validateDraft(user.userId, request.draft, request.credentialId, request.stubMode)
        return mapOf("valid" to errors.isEmpty(), "errors" to errors)
    }

    @PostMapping("/companies/apply")
    fun apply(@AuthenticationPrincipal user: AuthenticatedUser, @RequestBody request: ApplyCompanyDesignRequest) =
        designer.apply(user.userId, request.draft, request.credentialId, request.stubMode)
}
