package com.agentvillage.minihome.presentation

import com.agentvillage.common.domain.Visibility
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import com.agentvillage.minihome.application.MiniHomeService
import com.agentvillage.minihome.application.MiniHomeView
import com.agentvillage.minihome.application.RoomItemCommand
import com.agentvillage.minihome.domain.RoomItem
import com.agentvillage.minihome.domain.RoomItemType
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID

data class UpdateMiniHomeRequest(
    @field:NotBlank @field:Size(max = 80) val title: String,
    @field:Size(max = 500) val introduction: String? = null,
    @field:Pattern(regexp = "^[a-z0-9-]{3,60}$") val backgroundKey: String = "village-day",
    val visibility: Visibility = Visibility.PUBLIC,
)

data class RoomItemRequest(
    val agentId: UUID? = null,
    @field:Size(max = 100) val assetKey: String? = null,
    val itemType: RoomItemType,
    @field:DecimalMin("0.0") @field:DecimalMax("1.0") val positionX: BigDecimal,
    @field:DecimalMin("0.0") @field:DecimalMax("1.0") val positionY: BigDecimal,
    @field:DecimalMin(value = "0.0", inclusive = false) @field:DecimalMax("1.0") val width: BigDecimal,
    @field:DecimalMin(value = "0.0", inclusive = false) @field:DecimalMax("1.0") val height: BigDecimal,
    val zIndex: Int = 0,
    val rotation: BigDecimal = BigDecimal.ZERO,
) {
    fun toCommand(): RoomItemCommand {
        require((itemType == RoomItemType.AGENT && agentId != null && assetKey == null) ||
            (itemType == RoomItemType.ASSET && agentId == null && !assetKey.isNullOrBlank())) {
            "itemType에 맞는 agentId 또는 assetKey가 필요합니다."
        }
        return RoomItemCommand(agentId, assetKey, itemType, positionX, positionY, width, height, zIndex, rotation)
    }
}

data class RoomItemResponse(
    val id: UUID,
    val agentId: UUID?,
    val assetKey: String?,
    val itemType: RoomItemType,
    val positionX: BigDecimal,
    val positionY: BigDecimal,
    val width: BigDecimal,
    val height: BigDecimal,
    val zIndex: Int,
    val rotation: BigDecimal,
) {
    companion object {
        fun from(item: RoomItem) = RoomItemResponse(
            item.id, item.agentId, item.assetKey, item.itemType, item.positionX, item.positionY,
            item.width, item.height, item.zIndex, item.rotation,
        )
    }
}

data class MiniHomeResponse(
    val id: UUID,
    val handle: String,
    val title: String,
    val introduction: String?,
    val backgroundKey: String,
    val visibility: Visibility,
    val visitCount: Long,
    val items: List<RoomItemResponse>,
) {
    companion object {
        fun from(view: MiniHomeView) = MiniHomeResponse(
            view.id, view.handle, view.title, view.introduction, view.backgroundKey,
            view.visibility, view.visitCount, view.items.map(RoomItemResponse::from),
        )
    }
}

@RestController
@RequestMapping("/api/mini-homes")
class MiniHomeController(private val miniHomes: MiniHomeService) {
    @GetMapping("/me")
    fun mine(@AuthenticationPrincipal principal: AuthenticatedUser) =
        MiniHomeResponse.from(miniHomes.getMine(principal.userId))

    @PatchMapping("/me")
    fun update(
        @AuthenticationPrincipal principal: AuthenticatedUser,
        @Valid @RequestBody request: UpdateMiniHomeRequest,
    ) = MiniHomeResponse.from(
        miniHomes.update(principal.userId, request.title, request.introduction, request.backgroundKey, request.visibility),
    )

    @PutMapping("/me/items")
    fun replaceItems(
        @AuthenticationPrincipal principal: AuthenticatedUser,
        @Valid @RequestBody items: List<RoomItemRequest>,
    ) = MiniHomeResponse.from(miniHomes.replaceItems(principal.userId, items.map(RoomItemRequest::toCommand)))

    @GetMapping("/{handle}")
    fun publicHome(
        @PathVariable handle: String,
        @AuthenticationPrincipal principal: AuthenticatedUser?,
    ) = MiniHomeResponse.from(miniHomes.getPublic(handle, principal?.userId))
}

