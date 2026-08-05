package com.agentvillage.marketplace.presentation
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import com.agentvillage.marketplace.application.MarketplaceService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.UUID
data class CreateProductRequest(val harnessId:UUID,val title:String,val description:String?=null,val category:String="OTHER",val official:Boolean=false)
@RestController @RequestMapping("/api/market/products") class MarketplaceController(private val service:MarketplaceService){
 @GetMapping fun list(@RequestParam(required=false) query:String?,@RequestParam(defaultValue="latest") order:String)=service.list(query,order)
 @GetMapping("/{id}") fun get(@PathVariable id:UUID)=service.get(id)
 @PostMapping @ResponseStatus(HttpStatus.CREATED) fun create(@AuthenticationPrincipal p:AuthenticatedUser,@RequestBody r:CreateProductRequest)=service.create(p.userId,r.harnessId,r.title,r.description,r.category,false)
 @PostMapping("/{id}/clone") fun clone(@AuthenticationPrincipal p:AuthenticatedUser,@PathVariable id:UUID)=service.clone(id,p.userId)
 @PostMapping("/{id}/likes") fun like(@AuthenticationPrincipal p:AuthenticatedUser,@PathVariable id:UUID)=service.like(id,p.userId)
 @DeleteMapping("/{id}/likes") @ResponseStatus(HttpStatus.NO_CONTENT) fun unlike(@AuthenticationPrincipal p:AuthenticatedUser,@PathVariable id:UUID)=service.unlike(id,p.userId)
}
