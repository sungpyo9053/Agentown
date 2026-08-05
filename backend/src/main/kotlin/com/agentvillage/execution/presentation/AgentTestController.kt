package com.agentvillage.execution.presentation

import com.agentvillage.execution.application.AgentTestService
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class AgentTestRequest(
    @field:NotBlank @field:Size(max = 20_000) val input: String,
    val stubMode: Boolean = false,
)

@RestController
class AgentTestController(private val tests: AgentTestService) {
    @PostMapping("/api/agents/{id}/test")
    suspend fun test(
        @AuthenticationPrincipal principal: AuthenticatedUser,
        @PathVariable id: UUID,
        @Valid @RequestBody request: AgentTestRequest,
    ) = tests.test(id, principal.userId, request.input, request.stubMode)
}
