package com.agentvillage.llmcredential.presentation

import com.agentvillage.identity.infrastructure.AuthenticatedUser
import com.agentvillage.llmcredential.application.CredentialView
import com.agentvillage.llmcredential.application.LlmCredentialService
import com.agentvillage.llmcredential.application.VerifyCredentialResult
import com.agentvillage.llmcredential.domain.LlmProvider
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class LlmCredentialRequest(
    val provider: LlmProvider,
    @field:NotEmpty @field:Size(max = 500) val secret: CharArray,
    val providerOptions: Map<String, Any> = emptyMap(),
)

@RestController
@RequestMapping("/api/llm-credentials")
class LlmCredentialController(private val service: LlmCredentialService) {
    @PostMapping("/verify")
    fun verify(@Valid @RequestBody request: LlmCredentialRequest): VerifyCredentialResult =
        service.verify(request.provider, request.secret, request.providerOptions)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal principal: AuthenticatedUser,
        @Valid @RequestBody request: LlmCredentialRequest,
    ): CredentialView = service.create(principal.userId, request.provider, request.secret, request.providerOptions)

    @GetMapping
    fun list(@AuthenticationPrincipal principal: AuthenticatedUser): List<CredentialView> = service.list(principal.userId)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal principal: AuthenticatedUser, @PathVariable id: UUID) {
        service.delete(id, principal.userId)
    }
}

