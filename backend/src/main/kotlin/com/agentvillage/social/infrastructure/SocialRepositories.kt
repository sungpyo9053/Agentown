package com.agentvillage.social.infrastructure

import com.agentvillage.social.domain.Friendship
import com.agentvillage.social.domain.FriendshipStatus
import com.agentvillage.social.domain.UserBlock
import com.agentvillage.social.domain.UserBlockId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface FriendshipRepository : JpaRepository<Friendship, UUID> {
    @Query("select f from Friendship f where (f.requesterId = :a and f.addresseeId = :b) or (f.requesterId = :b and f.addresseeId = :a)")
    fun findPair(@Param("a") a: UUID, @Param("b") b: UUID): Friendship?

    @Query("select f from Friendship f where f.requesterId = :userId or f.addresseeId = :userId order by f.createdAt desc")
    fun findAllForUser(@Param("userId") userId: UUID): List<Friendship>

    @Query("select count(f) > 0 from Friendship f where f.status = :status and ((f.requesterId = :a and f.addresseeId = :b) or (f.requesterId = :b and f.addresseeId = :a))")
    fun existsPairWithStatus(@Param("a") a: UUID, @Param("b") b: UUID, @Param("status") status: FriendshipStatus): Boolean
}

interface UserBlockRepository : JpaRepository<UserBlock, UserBlockId> {
    fun existsByBlockerIdAndBlockedId(blockerId: UUID, blockedId: UUID): Boolean
    fun deleteByBlockerIdAndBlockedId(blockerId: UUID, blockedId: UUID)
}
