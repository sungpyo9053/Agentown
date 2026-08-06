package com.agentvillage.identity.presentation

import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.RegisterUserCommand
import com.agentvillage.identity.application.UserIdentity
import com.agentvillage.identity.application.PhoneAuthService
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import com.agentvillage.common.domain.UserRole
import com.agentvillage.common.exception.UnauthorizedException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
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
    @field:Pattern(regexp = "^[a-z0-9_]{3,30}$") val handle: String,
    @field:Size(min = 1, max = 40) val displayName: String,
    @field:NotBlank val phone: String,
    val phoneVerificationId: java.util.UUID,
)

data class LoginRequest(
    @field:NotBlank val loginId: String,
    @field:NotBlank val password: String,
)

data class SendPhoneCodeRequest(@field:NotBlank val phone: String)
data class VerifyPhoneCodeRequest(val verificationId: java.util.UUID, @field:Pattern(regexp = "^[0-9]{6}$") val code: String)
data class TemporaryPasswordRequest(@field:NotBlank val loginId: String, @field:NotBlank val phone: String)

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
    private val phoneAuth: PhoneAuthService,
) {
    private val securityContextRepository = HttpSessionSecurityContextRepository()

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    fun signup(@Valid @RequestBody request: SignupRequest): AuthResponse {
        val verified = phoneAuth.requireVerified(request.phoneVerificationId, request.phone)
        val identity = identities.register(
            RegisterUserCommand(
                email = null,
                password = request.password,
                handle = request.handle,
                displayName = request.displayName,
                phoneHash = verified.hash,
                phoneMasked = verified.masked,
                phoneVerifiedAt = verified.verifiedAt,
            ),
        )
        phoneAuth.consume(request.phoneVerificationId)
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
                UsernamePasswordAuthenticationToken.unauthenticated(body.loginId, body.password),
            )
        } catch (_: AuthenticationException) {
            throw UnauthorizedException("INVALID_CREDENTIALS", "아이디 또는 비밀번호가 올바르지 않습니다.")
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
        @org.springframework.web.bind.annotation.RequestParam(required = false) handle: String?,
    ) = identities.availability(handle)

    @PostMapping("/phone/send-code")
    fun sendPhoneCode(@Valid @RequestBody request: SendPhoneCodeRequest) = phoneAuth.sendCode(request.phone)

    @PostMapping("/phone/verify-code")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun verifyPhoneCode(@Valid @RequestBody request: VerifyPhoneCodeRequest) =
        phoneAuth.verify(request.verificationId, request.code)

    @PostMapping("/password/temporary")
    fun issueTemporaryPassword(@Valid @RequestBody request: TemporaryPasswordRequest) =
        phoneAuth.issueTemporaryPassword(request.loginId, request.phone)

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: AuthenticatedUser): AuthResponse =
        AuthResponse.from(identities.require(principal.userId))

    @GetMapping("/csrf")
    fun csrf(token: CsrfToken): Map<String, String> = mapOf("token" to token.token, "headerName" to token.headerName)
}

private fun HttpServletRequest.changeSessionIdIfPresent() {
    if (getSession(false) != null) changeSessionId() else getSession(true)
}
