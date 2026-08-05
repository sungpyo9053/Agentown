package com.agentvillage.social.application

import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.common.exception.ConflictException
import com.agentvillage.common.exception.ForbiddenException
import com.agentvillage.common.exception.NotFoundException
import com.agentvillage.social.domain.Friendship
import com.agentvillage.social.domain.FriendshipStatus
import com.agentvillage.social.domain.UserBlock
import com.agentvillage.social.infrastructure.FriendshipRepository
import com.agentvillage.social.infrastructure.UserBlockRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface FriendshipQuery {
    fun areFriends(a: UUID, b: UUID): Boolean
}

@Service
class FriendshipService(
    private val friendships: FriendshipRepository,
    private val blocks: UserBlockRepository,
) : FriendshipQuery {
    @Transactional
    fun request(requesterId: UUID, addresseeId: UUID): Friendship {
        if (requesterId == addresseeId) throw BadRequestException("SELF_FRIENDSHIP", "본인에게 일촌을 신청할 수 없습니다.")
        if (blocks.existsByBlockerIdAndBlockedId(requesterId, addresseeId) || blocks.existsByBlockerIdAndBlockedId(addresseeId, requesterId)) {
            throw ForbiddenException("USER_BLOCKED", "차단 관계에서는 일촌을 신청할 수 없습니다.")
        }
        if (friendships.findPair(requesterId, addresseeId) != null) {
            throw ConflictException("FRIENDSHIP_ALREADY_EXISTS", "이미 일촌 관계 또는 요청이 존재합니다.")
        }
        return friendships.save(Friendship(requesterId = requesterId, addresseeId = addresseeId))
    }

    @Transactional
    fun respond(id: UUID, addresseeId: UUID, accept: Boolean): Friendship {
        val friendship = requireForAddressee(id, addresseeId)
        if (friendship.status != FriendshipStatus.PENDING) throw ConflictException("FRIENDSHIP_ALREADY_RESPONDED", "이미 처리된 요청입니다.")
        friendship.status = if (accept) FriendshipStatus.ACCEPTED else FriendshipStatus.REJECTED
        friendship.respondedAt = Instant.now()
        return friendship
    }

    @Transactional
    fun remove(id: UUID, userId: UUID) {
        val friendship = friendships.findById(id).orElseThrow { NotFoundException("FRIENDSHIP_NOT_FOUND", "일촌 관계를 찾을 수 없습니다.") }
        if (friendship.requesterId != userId && friendship.addresseeId != userId) throw ForbiddenException("FRIENDSHIP_FORBIDDEN", "일촌 관계를 변경할 권한이 없습니다.")
        friendships.delete(friendship)
    }

    @Transactional(readOnly = true)
    fun list(userId: UUID): List<Friendship> = friendships.findAllForUser(userId)

    @Transactional(readOnly = true)
    override fun areFriends(a: UUID, b: UUID): Boolean = a == b || friendships.existsPairWithStatus(a, b, FriendshipStatus.ACCEPTED)

    @Transactional
    fun block(blockerId: UUID, blockedId: UUID) {
        if (blockerId == blockedId) throw BadRequestException("SELF_BLOCK", "본인을 차단할 수 없습니다.")
        friendships.findPair(blockerId, blockedId)?.let(friendships::delete)
        if (!blocks.existsByBlockerIdAndBlockedId(blockerId, blockedId)) blocks.save(UserBlock(blockerId, blockedId))
    }

    @Transactional fun unblock(blockerId: UUID, blockedId: UUID) = blocks.deleteByBlockerIdAndBlockedId(blockerId, blockedId)

    private fun requireForAddressee(id: UUID, addresseeId: UUID): Friendship {
        val friendship = friendships.findById(id).orElseThrow { NotFoundException("FRIENDSHIP_NOT_FOUND", "일촌 요청을 찾을 수 없습니다.") }
        if (friendship.addresseeId != addresseeId) throw ForbiddenException("FRIENDSHIP_FORBIDDEN", "요청을 처리할 권한이 없습니다.")
        return friendship
    }
}
