package com.agentvillage.social.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.IdClass
import java.time.Instant
import java.util.UUID

enum class FriendshipStatus { PENDING, ACCEPTED, REJECTED }

@Entity
@Table(name = "friendships")
class Friendship(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "requester_id", nullable = false) val requesterId: UUID,
    @Column(name = "addressee_id", nullable = false) val addresseeId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) var status: FriendshipStatus = FriendshipStatus.PENDING,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "responded_at") var respondedAt: Instant? = null,
)

@Entity
@Table(name = "user_blocks")
@IdClass(UserBlockId::class)
class UserBlock(
    @Id @Column(name = "blocker_id") val blockerId: UUID,
    @Id @Column(name = "blocked_id", nullable = false) val blockedId: UUID,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)

data class UserBlockId(var blockerId: UUID? = null, var blockedId: UUID? = null) : java.io.Serializable
