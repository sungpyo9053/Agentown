package com.agentvillage.release.presentation

import com.agentvillage.identity.infrastructure.AuthenticatedUser
import com.agentvillage.common.exception.ForbiddenException
import com.agentvillage.release.application.*
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController @RequestMapping("/api/admin/releases")
class AdminReleaseController(private val service: ReleaseControlService) {
    @GetMapping fun list(@AuthenticationPrincipal admin: AuthenticatedUser) = service.list(releaseOwner(admin).userId)
    @GetMapping("/{id}") fun get(@AuthenticationPrincipal admin: AuthenticatedUser, @PathVariable id: UUID) = service.get(releaseOwner(admin).userId, id)
    @GetMapping("/{id}/summary") fun summary(@AuthenticationPrincipal admin: AuthenticatedUser, @PathVariable id: UUID) = service.get(releaseOwner(admin).userId, id).detail
    @GetMapping("/{id}/verification") fun verification(@AuthenticationPrincipal admin: AuthenticatedUser, @PathVariable id: UUID) = service.get(releaseOwner(admin).userId, id).detail["productionVerification"] ?: emptyMap<String, Any?>()
    @GetMapping("/{id}/events") fun events(@AuthenticationPrincipal admin: AuthenticatedUser, @PathVariable id: UUID) = service.history(releaseOwner(admin).userId, id)
    @PostMapping("/{id}/approve") fun approve(@AuthenticationPrincipal admin: AuthenticatedUser, @PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody command: ReleaseApprovalCommand) = service.approve(releaseOwner(admin).userId, id, key, command)
    @PostMapping("/{id}/cancel") fun cancel(@AuthenticationPrincipal admin: AuthenticatedUser, @PathVariable id: UUID, @RequestBody command: ReleaseActionCommand) = service.cancel(releaseOwner(admin).userId, id, command.reason)
    @PostMapping("/{id}/hold") fun hold(@AuthenticationPrincipal admin: AuthenticatedUser, @PathVariable id: UUID, @RequestBody command: ReleaseActionCommand) = service.hold(releaseOwner(admin).userId, id, command.reason)
    @PostMapping("/{id}/discard") fun discard(@AuthenticationPrincipal admin: AuthenticatedUser, @PathVariable id: UUID, @RequestBody command: ReleaseActionCommand) = service.discard(releaseOwner(admin).userId, id, command.reason)
    @PostMapping("/{id}/verification") fun verify(@AuthenticationPrincipal admin: AuthenticatedUser, @PathVariable id: UUID, @RequestBody command: ReleaseVerificationCommand) = service.recordVerification(releaseOwner(admin).userId, id, command)

    private fun releaseOwner(admin: AuthenticatedUser): AuthenticatedUser {
        if (!admin.username.equals("admin@reviewdr.kr", ignoreCase = true)) throw ForbiddenException("RELEASE_ADMIN_REQUIRED", "배포 관리는 admin@reviewdr.kr 계정만 사용할 수 있습니다.")
        return admin
    }
}
