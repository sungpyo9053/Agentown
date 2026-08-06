package com.agentvillage.profile.presentation

import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.UserIdentity
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class UpdateProfileRequest(
    @field:jakarta.validation.constraints.NotBlank @field:jakarta.validation.constraints.Size(max = 40) val displayName: String,
    @field:jakarta.validation.constraints.Size(max = 300) val bio: String? = null,
    @field:jakarta.validation.constraints.Pattern(regexp = "^$|^https://.*") val avatarUrl: String? = null,
)
data class ChangePasswordRequest(
    @field:jakarta.validation.constraints.Size(min = 8, max = 72) val currentPassword: String,
    @field:jakarta.validation.constraints.Size(min = 8, max = 72) val newPassword: String,
)
data class WithdrawRequest(@field:jakarta.validation.constraints.Size(min = 8, max = 72) val currentPassword: String)

@RestController
@RequestMapping("/api/users")
class ProfileController(private val users: IdentityService) {
    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: AuthenticatedUser): UserIdentity = users.require(principal.userId)

    @PatchMapping("/me")
    fun update(@AuthenticationPrincipal principal: AuthenticatedUser, @jakarta.validation.Valid @RequestBody request: UpdateProfileRequest) =
        users.updateProfile(principal.userId, request.displayName, request.bio, request.avatarUrl)

    @PatchMapping("/me/password")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    fun changePassword(@AuthenticationPrincipal principal: AuthenticatedUser, @jakarta.validation.Valid @RequestBody request: ChangePasswordRequest) =
        users.changePassword(principal.userId, request.currentPassword, request.newPassword)

    @DeleteMapping("/me")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    fun withdraw(@AuthenticationPrincipal principal: AuthenticatedUser, @jakarta.validation.Valid @RequestBody request: WithdrawRequest) =
        users.withdraw(principal.userId, request.currentPassword)

    @GetMapping("/{handle}")
    fun get(@PathVariable handle: String) = users.publicProfile(handle)
}
