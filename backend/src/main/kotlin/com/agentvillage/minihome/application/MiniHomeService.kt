package com.agentvillage.minihome.application

import com.agentvillage.agent.application.AgentDirectory
import com.agentvillage.common.domain.Visibility
import com.agentvillage.common.exception.ForbiddenException
import com.agentvillage.common.exception.NotFoundException
import com.agentvillage.identity.application.UserDirectory
import com.agentvillage.identity.application.UserRegistered
import com.agentvillage.minihome.domain.MiniHome
import com.agentvillage.minihome.domain.RoomItem
import com.agentvillage.minihome.domain.RoomItemType
import com.agentvillage.minihome.infrastructure.MiniHomeRepository
import com.agentvillage.minihome.infrastructure.RoomItemRepository
import com.agentvillage.social.application.FriendshipQuery
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

data class RoomItemCommand(
    val agentId: UUID?,
    val assetKey: String?,
    val itemType: RoomItemType,
    val positionX: BigDecimal,
    val positionY: BigDecimal,
    val width: BigDecimal,
    val height: BigDecimal,
    val zIndex: Int,
    val rotation: BigDecimal,
)

@Service
class MiniHomeService(
    private val homes: MiniHomeRepository,
    private val roomItems: RoomItemRepository,
    private val userDirectory: UserDirectory,
    private val agentDirectory: AgentDirectory,
    private val friendshipQuery: FriendshipQuery,
) {
    @EventListener
    @Transactional
    fun provision(event: UserRegistered) {
        if (homes.findByUserId(event.userId) == null) {
            homes.save(MiniHome(userId = event.userId, title = "${event.displayName}의 AI 마을"))
        }
    }

    @Transactional(readOnly = true)
    fun getMine(userId: UUID): MiniHomeView = view(requireHome(userId), userDirectory.require(userId).handle, Visibility.entries.toSet())

    @Transactional
    fun getPublic(handle: String, viewerId: UUID?): MiniHomeView {
        val owner = userDirectory.findByHandle(handle)
            ?: throw NotFoundException("MINI_HOME_NOT_FOUND", "미니홈을 찾을 수 없습니다.")
        val home = requireHome(owner.id)
        val isOwner = viewerId == owner.id
        val canView = isOwner || home.visibility in setOf(Visibility.PUBLIC, Visibility.MARKET) ||
            (home.visibility == Visibility.FRIENDS && viewerId != null && friendshipQuery.areFriends(owner.id, viewerId))
        if (!canView) {
            throw ForbiddenException("MINI_HOME_PRIVATE", "공개되지 않은 미니홈입니다.")
        }
        if (!isOwner) home.visitCount += 1
        val allowed = when {
            isOwner -> Visibility.entries.toSet()
            viewerId != null && friendshipQuery.areFriends(owner.id, viewerId) -> setOf(Visibility.FRIENDS, Visibility.PUBLIC, Visibility.MARKET)
            else -> setOf(Visibility.PUBLIC, Visibility.MARKET)
        }
        return view(home, owner.handle, allowed)
    }

    @Transactional
    fun update(userId: UUID, title: String, introduction: String?, backgroundKey: String, visibility: Visibility): MiniHomeView {
        val home = requireHome(userId)
        home.title = title.trim()
        home.introduction = introduction?.trim()?.takeIf { it.isNotEmpty() }
        home.backgroundKey = backgroundKey
        home.visibility = visibility
        return view(home, userDirectory.require(userId).handle, Visibility.entries.toSet())
    }

    @Transactional
    fun replaceItems(userId: UUID, commands: List<RoomItemCommand>): MiniHomeView {
        val home = requireHome(userId)
        commands.filter { it.itemType == RoomItemType.AGENT }.forEach {
            agentDirectory.requireOwned(requireNotNull(it.agentId), userId)
        }
        roomItems.deleteAllByMiniHomeId(home.id)
        roomItems.flush()
        roomItems.saveAll(
            commands.map { item ->
                RoomItem(
                    miniHomeId = home.id,
                    agentId = item.agentId,
                    assetKey = item.assetKey,
                    itemType = item.itemType,
                    positionX = item.positionX,
                    positionY = item.positionY,
                    width = item.width,
                    height = item.height,
                    zIndex = item.zIndex,
                    rotation = item.rotation,
                )
            },
        )
        return view(home, userDirectory.require(userId).handle, Visibility.entries.toSet())
    }

    private fun requireHome(userId: UUID) = homes.findByUserId(userId)
        ?: throw NotFoundException("MINI_HOME_NOT_FOUND", "미니홈을 찾을 수 없습니다.")

    private fun view(home: MiniHome, handle: String, allowedVisibilities: Set<Visibility>): MiniHomeView {
        val items = roomItems.findAllByMiniHomeIdOrderByZIndex(home.id)
        val ownerId = home.userId
        return MiniHomeView(
        id = home.id,
        handle = handle,
        title = home.title,
        introduction = home.introduction,
        backgroundKey = home.backgroundKey,
        visibility = home.visibility,
        visitCount = home.visitCount,
        items = items,
        agents = agentDirectory.listVisible(items.mapNotNull { it.agentId }, ownerId, allowedVisibilities),
    )}
}

data class MiniHomeView(
    val id: UUID,
    val handle: String,
    val title: String,
    val introduction: String?,
    val backgroundKey: String,
    val visibility: Visibility,
    val visitCount: Long,
    val items: List<RoomItem>,
    val agents: List<com.agentvillage.agent.application.MiniHomeAgentDescriptor>,
)
