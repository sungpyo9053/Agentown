package com.agentvillage.identity.presentation

import com.agentvillage.common.domain.UserRole
import com.agentvillage.identity.application.AdminUserService
import com.agentvillage.identity.domain.UserStatus
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class UpdateAdminUserRequest(val role: UserRole? = null, val status: UserStatus? = null)

@RestController
@RequestMapping("/api/admin/users")
class AdminUserController(private val service: AdminUserService) {
    @GetMapping fun list() = service.list()
    @GetMapping("/summary") fun summary() = service.summary()
    @PatchMapping("/{id}")
    fun update(
        @AuthenticationPrincipal admin: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestBody request: UpdateAdminUserRequest,
    ) = service.update(admin.userId, id, request.role, request.status)
}
