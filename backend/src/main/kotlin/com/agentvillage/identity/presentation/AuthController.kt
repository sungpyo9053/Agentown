package com.agentvillage.identity.presentation

import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.RegisterUserCommand
import com.agentvillage.identity.application.UserIdentity
import com.agentvillage.identity.application.EmailAuthService
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import com.agentvillage.common.domain.UserRole
import com.agentvillage.common.exception.UnauthorizedException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.core.AuthenticationException
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
    @field:Size(min = 8, max = 72) val password: String,
    @field:Email @field:Size(max = 320) val email: String,
    @field:Size(min = 1, max = 40) val displayName: String,
    val emailVerificationId: java.util.UUID,
)

data class LoginRequest(
    @field:Email @field:Size(max = 320) val email: String,
    @field:NotBlank val password: String,
)

data class SendEmailCodeRequest(@field:Email @field:Size(max = 320) val email: String)
data class VerifyEmailCodeRequest(val verificationId: java.util.UUID, @field:jakarta.validation.constraints.Pattern(regexp = "^[0-9]{6}$") val code: String)
data class TemporaryPasswordRequest(@field:Email @field:Size(max = 320) val email: String)

data class AuthResponse(
    val id: String,
    val email: String,
    val handle: String,
    val displayName: String,
    val role: UserRole,
) {
    companion object {
        fun from(identity: UserIdentity) = AuthResponse(
            identity.id.toString(), identity.email, identity.handle, identity.displayName, identity.role,
        )
    }
}

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val identities: IdentityService,
    private val authenticationManager: AuthenticationManager,
    private val emailAuth: EmailAuthService,
) {
    private val securityContextRepository = HttpSessionSecurityContextRepository()

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    fun signup(@Valid @RequestBody request: SignupRequest): AuthResponse {
        val verified = emailAuth.requireVerified(request.emailVerificationId, request.email)
        val identity = identities.register(
            RegisterUserCommand(
                email = verified.email,
                password = request.password,
                handle = null,
                displayName = request.displayName,
                emailVerifiedAt = verified.verifiedAt,
            ),
        )
        emailAuth.consume(request.emailVerificationId)
        return AuthResponse.from(identity)
    }

    @PostMapping("/login")
    fun login(
        @Valid @RequestBody body: LoginRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): AuthResponse {
        val authentication = try {
            authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(body.email, body.password),
            )
        } catch (_: AuthenticationException) {
            throw UnauthorizedException("INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.")
        }
        request.changeSessionIdIfPresent()
        val context = SecurityContextHolder.createEmptyContext().also { it.authentication = authentication }
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, request, response)
        val principal = authentication.principal as AuthenticatedUser
        return AuthResponse.from(identities.require(principal.userId))
    }

    @GetMapping("/availability")
    fun availability(
        @org.springframework.web.bind.annotation.RequestParam(required = false) email: String?,
    ) = identities.availability(email)

    @PostMapping("/email/send-code")
    fun sendEmailCode(@Valid @RequestBody request: SendEmailCodeRequest) = emailAuth.sendCode(request.email)

    @PostMapping("/email/verify-code")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun verifyEmailCode(@Valid @RequestBody request: VerifyEmailCodeRequest) =
        emailAuth.verify(request.verificationId, request.code)

    @PostMapping("/password/temporary")
    fun issueTemporaryPassword(@Valid @RequestBody request: TemporaryPasswordRequest) =
        emailAuth.issueTemporaryPassword(request.email)

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: AuthenticatedUser): AuthResponse =
        AuthResponse.from(identities.require(principal.userId))

    @GetMapping("/csrf")
    fun csrf(token: CsrfToken): Map<String, String> = mapOf("token" to token.token, "headerName" to token.headerName)
}

private fun HttpServletRequest.changeSessionIdIfPresent() {
    if (getSession(false) != null) changeSessionId() else getSession(true)
}
