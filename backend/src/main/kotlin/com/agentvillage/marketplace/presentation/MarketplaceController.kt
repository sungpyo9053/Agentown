package com.agentvillage.marketplace.presentation
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import com.agentvillage.common.domain.UserRole
import com.agentvillage.common.exception.ForbiddenException
import com.agentvillage.marketplace.application.MarketplaceService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.UUID
data class CreateProductRequest(val harnessId:UUID,@field:jakarta.validation.constraints.NotBlank @field:jakarta.validation.constraints.Size(max=120) val title:String,
 @field:jakarta.validation.constraints.Size(max=1500) val description:String?=null,@field:jakarta.validation.constraints.NotBlank @field:jakarta.validation.constraints.Size(max=60) val category:String="OTHER",val official:Boolean=false)
data class ReviewProductRequest(@field:jakarta.validation.constraints.Min(1) @field:jakarta.validation.constraints.Max(5) val rating:Int,
 @field:jakarta.validation.constraints.NotBlank @field:jakarta.validation.constraints.Size(max=1000) val content:String)
@RestController @RequestMapping("/api/market/products") class MarketplaceController(private val service:MarketplaceService){
 @GetMapping fun list(@RequestParam(required=false) query:String?,@RequestParam(defaultValue="latest") order:String,@RequestParam(required=false) category:String?)=service.list(query,order,category)
 @GetMapping("/{id}") fun get(@PathVariable id:UUID)=service.get(id)
 @PostMapping @ResponseStatus(HttpStatus.CREATED) fun create(@AuthenticationPrincipal p:AuthenticatedUser,@jakarta.validation.Valid @RequestBody r:CreateProductRequest): Any {
   if(r.official && p.role != UserRole.ADMIN) throw ForbiddenException("ADMIN_REQUIRED", "공식 하네스 등록은 관리자만 가능합니다.")
   return service.create(p.userId,r.harnessId,r.title,r.description,r.category,r.official)
 }
 @PostMapping("/{id}/clone") fun clone(@AuthenticationPrincipal p:AuthenticatedUser,@PathVariable id:UUID)=service.clone(id,p.userId)
 @PostMapping("/{id}/likes") fun like(@AuthenticationPrincipal p:AuthenticatedUser,@PathVariable id:UUID)=service.like(id,p.userId)
 @DeleteMapping("/{id}/likes") @ResponseStatus(HttpStatus.NO_CONTENT) fun unlike(@AuthenticationPrincipal p:AuthenticatedUser,@PathVariable id:UUID)=service.unlike(id,p.userId)
 @GetMapping("/{id}/reviews") fun reviews(@PathVariable id:UUID)=service.reviews(id)
 @PostMapping("/{id}/reviews") fun review(@AuthenticationPrincipal p:AuthenticatedUser,@PathVariable id:UUID,@jakarta.validation.Valid @RequestBody r:ReviewProductRequest)=service.review(id,p.userId,r.rating,r.content)
}
