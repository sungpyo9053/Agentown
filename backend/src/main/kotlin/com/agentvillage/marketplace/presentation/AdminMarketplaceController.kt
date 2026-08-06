package com.agentvillage.marketplace.presentation

import com.agentvillage.marketplace.application.MarketplaceService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class UpdateOfficialRequest(val official: Boolean)

@RestController
@RequestMapping("/api/admin/market/products")
class AdminMarketplaceController(private val service: MarketplaceService) {
    @GetMapping fun list() = service.adminList()
    @PatchMapping("/{id}/official")
    fun official(@PathVariable id: UUID, @RequestBody request: UpdateOfficialRequest) =
        service.setOfficial(id, request.official)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = service.adminDelete(id)
}
