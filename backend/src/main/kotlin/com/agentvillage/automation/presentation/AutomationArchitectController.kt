package com.agentvillage.automation.presentation

import com.agentvillage.automation.application.AutomationArchitectService
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class DesignAutomationRequest(@field:NotBlank @field:Size(max = 2_000) val instruction: String)

@RestController
@RequestMapping("/api/automations")
class AutomationArchitectController(private val architect: AutomationArchitectService) {
    @PostMapping("/design")
    fun design(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @Valid @RequestBody request: DesignAutomationRequest,
    ) = architect.design(user.userId, request.instruction)
}
