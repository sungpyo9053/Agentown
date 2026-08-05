package com.agentvillage.social.presentation

import com.agentvillage.identity.application.UserDirectory
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import com.agentvillage.social.application.FriendshipService
import com.agentvillage.social.domain.Friendship
import com.agentvillage.social.domain.FriendshipStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

data class FriendshipRequest(@field:Pattern(regexp = "^[a-z0-9_]{3,30}$") val handle: String)
data class FriendshipResponse(val id: UUID, val requesterId: UUID, val addresseeId: UUID, val status: FriendshipStatus, val createdAt: Instant) {
    companion object { fun from(f: Friendship) = FriendshipResponse(f.id, f.requesterId, f.addresseeId, f.status, f.createdAt) }
}

@RestController
@RequestMapping("/api/friendships")
class FriendshipController(private val service: FriendshipService, private val users: UserDirectory) {
    @PostMapping("/requests") @ResponseStatus(HttpStatus.CREATED)
    fun request(@AuthenticationPrincipal me: AuthenticatedUser, @Valid @RequestBody body: FriendshipRequest) =
        FriendshipResponse.from(service.request(me.userId, users.findByHandle(body.handle)?.id ?: throw com.agentvillage.common.exception.NotFoundException("USER_NOT_FOUND", "사용자를 찾을 수 없습니다.")))

    @PostMapping("/{id}/accept") fun accept(@AuthenticationPrincipal me: AuthenticatedUser, @PathVariable id: UUID) = FriendshipResponse.from(service.respond(id, me.userId, true))
    @PostMapping("/{id}/reject") fun reject(@AuthenticationPrincipal me: AuthenticatedUser, @PathVariable id: UUID) = FriendshipResponse.from(service.respond(id, me.userId, false))
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) fun remove(@AuthenticationPrincipal me: AuthenticatedUser, @PathVariable id: UUID) = service.remove(id, me.userId)
    @GetMapping fun list(@AuthenticationPrincipal me: AuthenticatedUser) = service.list(me.userId).map(FriendshipResponse::from)
}

data class BlockUserRequest(@field:Pattern(regexp = "^[a-z0-9_]{3,30}$") val handle: String)
@RestController @RequestMapping("/api/blocks")
class UserBlockController(private val service: FriendshipService, private val users: UserDirectory) {
    @PostMapping @ResponseStatus(HttpStatus.NO_CONTENT)
    fun block(@AuthenticationPrincipal me: AuthenticatedUser, @Valid @RequestBody body: BlockUserRequest) =
        service.block(me.userId, users.findByHandle(body.handle)?.id ?: throw com.agentvillage.common.exception.NotFoundException("USER_NOT_FOUND", "사용자를 찾을 수 없습니다."))
    @DeleteMapping("/{handle}") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unblock(@AuthenticationPrincipal me: AuthenticatedUser, @PathVariable handle: String) =
        service.unblock(me.userId, users.findByHandle(handle)?.id ?: throw com.agentvillage.common.exception.NotFoundException("USER_NOT_FOUND", "사용자를 찾을 수 없습니다."))
}
