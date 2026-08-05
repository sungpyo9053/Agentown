package com.agentvillage.identity.presentation

import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.RegisterUserCommand
import com.agentvillage.identity.application.UserIdentity
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

data class SignupRequest(
    @field:Email @field:NotBlank val email: String,
    @field:Size(min = 8, max = 72) val password: String,
    @field:Pattern(regexp = "^[a-z0-9_]{3,30}$") val handle: String,
    @field:Size(min = 1, max = 40) val displayName: String,
)

data class LoginRequest(
    @field:Email @field:NotBlank val email: String,
    @field:NotBlank val password: String,
)

data class AuthResponse(
    val id: String,
    val email: String,
    val handle: String,
    val displayName: String,
) {
    companion object {
        fun from(identity: UserIdentity) = AuthResponse(
            identity.id.toString(), identity.email, identity.handle, identity.displayName,
        )
    }
}

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val identities: IdentityService,
    private val authenticationManager: AuthenticationManager,
) {
    private val securityContextRepository = HttpSessionSecurityContextRepository()

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    fun signup(@Valid @RequestBody request: SignupRequest): AuthResponse =
        AuthResponse.from(
            identities.register(
                RegisterUserCommand(request.email, request.password, request.handle, request.displayName),
            ),
        )

    @PostMapping("/login")
    fun login(
        @Valid @RequestBody body: LoginRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): AuthResponse {
        val authentication = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(body.email, body.password),
        )
        request.changeSessionIdIfPresent()
        val context = SecurityContextHolder.createEmptyContext().also { it.authentication = authentication }
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, request, response)
        val principal = authentication.principal as AuthenticatedUser
        return AuthResponse.from(identities.require(principal.userId))
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: AuthenticatedUser): AuthResponse =
        AuthResponse.from(identities.require(principal.userId))

    @GetMapping("/csrf")
    fun csrf(token: CsrfToken): Map<String, String> = mapOf("token" to token.token, "headerName" to token.headerName)
}

private fun HttpServletRequest.changeSessionIdIfPresent() {
    if (getSession(false) != null) changeSessionId() else getSession(true)
}

