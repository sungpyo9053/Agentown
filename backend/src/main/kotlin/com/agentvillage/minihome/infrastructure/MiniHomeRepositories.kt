package com.agentvillage.minihome.infrastructure

import com.agentvillage.minihome.domain.MiniHome
import com.agentvillage.minihome.domain.RoomItem
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MiniHomeRepository : JpaRepository<MiniHome, UUID> {
    fun findByUserId(userId: UUID): MiniHome?
}

interface RoomItemRepository : JpaRepository<RoomItem, UUID> {
    fun findAllByMiniHomeIdOrderByZIndex(miniHomeId: UUID): List<RoomItem>
    fun deleteAllByMiniHomeId(miniHomeId: UUID)
}

