package com.agentvillage.profile.presentation

import com.agentvillage.identity.application.UserDirectory
import com.agentvillage.identity.application.UserIdentity
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/users")
class ProfileController(private val users: UserDirectory) {
    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: AuthenticatedUser): UserIdentity = users.require(principal.userId)

    @GetMapping("/{handle}")
    fun get(@PathVariable handle: String): UserIdentity? = users.findByHandle(handle)
}
