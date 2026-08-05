package com.agentvillage.minihome.domain

import com.agentvillage.common.domain.AuditedEntity
import com.agentvillage.common.domain.Visibility
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.util.UUID

@Entity
@Table(name = "mini_homes")
class MiniHome(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, unique = true)
    val userId: UUID,

    @Column(nullable = false, length = 80)
    var title: String,

    @Column(length = 500)
    var introduction: String? = null,

    @Column(name = "background_key", nullable = false, length = 60)
    var backgroundKey: String = "village-day",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var visibility: Visibility = Visibility.PUBLIC,

    @Column(name = "visit_count", nullable = false)
    var visitCount: Long = 0,

    @Version
    var version: Long = 0,
) : AuditedEntity()

enum class RoomItemType { AGENT, ASSET }

@Entity
@Table(name = "room_items")
class RoomItem(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "mini_home_id", nullable = false)
    val miniHomeId: UUID,

    @Column(name = "agent_id")
    val agentId: UUID? = null,

    @Column(name = "asset_key", length = 100)
    val assetKey: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 20)
    val itemType: RoomItemType,

    @Column(name = "position_x", nullable = false, precision = 7, scale = 6)
    var positionX: BigDecimal,

    @Column(name = "position_y", nullable = false, precision = 7, scale = 6)
    var positionY: BigDecimal,

    @Column(nullable = false, precision = 7, scale = 6)
    var width: BigDecimal,

    @Column(nullable = false, precision = 7, scale = 6)
    var height: BigDecimal,

    @Column(name = "z_index", nullable = false)
    var zIndex: Int = 0,

    @Column(nullable = false, precision = 7, scale = 2)
    var rotation: BigDecimal = BigDecimal.ZERO,
) : AuditedEntity()

