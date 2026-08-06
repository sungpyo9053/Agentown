package com.agentvillage.marketplace.application

import com.agentvillage.common.exception.NotFoundException
import com.agentvillage.harness.application.HarnessService
import com.agentvillage.marketplace.domain.*
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface MarketProductRepository : JpaRepository<MarketProduct, UUID> {
 fun findAllByTitleContainingIgnoreCase(query: String, sort: Sort): List<MarketProduct>
 fun findAllByTitleContainingIgnoreCaseAndCategoryIgnoreCase(query: String, category: String, sort: Sort): List<MarketProduct>
 fun findByHarnessVersionId(harnessVersionId: UUID): MarketProduct?
}
interface ProductLikeRepository : JpaRepository<ProductLike, ProductLikeId> { fun existsByProductIdAndUserId(productId: UUID, userId: UUID): Boolean; fun deleteByProductIdAndUserId(productId: UUID, userId: UUID) }
interface ProductCloneRepository : JpaRepository<ProductClone, UUID> { fun existsByProductIdAndUserId(productId: UUID, userId: UUID): Boolean }
interface ProductReviewRepository : JpaRepository<ProductReview, UUID> {
 fun findAllByProductIdOrderByCreatedAtDesc(productId: UUID): List<ProductReview>
 fun findByProductIdAndUserId(productId: UUID, userId: UUID): ProductReview?
}
@Service class MarketplaceService(private val products: MarketProductRepository, private val likes: ProductLikeRepository,
 private val clones: ProductCloneRepository, private val reviews: ProductReviewRepository, private val harnesses: HarnessService) {
 @Transactional(readOnly=true) fun list(query: String?, order: String, category: String?) : List<MarketProduct> {
   val sort = Sort.by(Sort.Direction.DESC, if(order=="popular") "likeCount" else "createdAt")
   return category?.takeIf { it.isNotBlank() }?.let { products.findAllByTitleContainingIgnoreCaseAndCategoryIgnoreCase(query.orEmpty(), it, sort) }
     ?: products.findAllByTitleContainingIgnoreCase(query.orEmpty(), sort)
 }
 @Transactional(readOnly=true) fun get(id: UUID)=products.findById(id).orElseThrow{NotFoundException("PRODUCT_NOT_FOUND","상품을 찾을 수 없습니다.")}
 @Transactional fun create(userId: UUID, harnessId: UUID, title: String, description: String?, category: String, official: Boolean): MarketProduct {
   val v=harnesses.publishToMarket(harnessId,userId)
   if(products.findByHarnessVersionId(v.id)!=null) throw com.agentvillage.common.exception.ConflictException("MARKET_PRODUCT_ALREADY_EXISTS","이미 마켓에 게시된 하네스 버전입니다.")
   return products.save(MarketProduct(harnessVersionId=v.id, creatorId=userId,title=title.trim(),description=description?.trim(),category=category.trim().uppercase(),official=official))
 }
 @Transactional fun clone(id: UUID,userId: UUID): Any { val p=get(id); val h=harnesses.clone(harnesses.latestPublishedId(p.harnessVersionId),userId); p.cloneCount++; clones.save(ProductClone(productId=id,userId=userId,clonedHarnessId=h.id)); return h }
 @Transactional fun like(id:UUID,userId:UUID): MarketProduct { val p=get(id); if(!likes.existsByProductIdAndUserId(id,userId)){likes.save(ProductLike(id,userId));p.likeCount++};return p }
 @Transactional fun unlike(id:UUID,userId:UUID){val p=get(id);if(likes.existsByProductIdAndUserId(id,userId)){likes.deleteByProductIdAndUserId(id,userId);p.likeCount=(p.likeCount-1).coerceAtLeast(0)}}
 @Transactional(readOnly=true) fun reviews(id:UUID): List<ProductReview> { get(id); return reviews.findAllByProductIdOrderByCreatedAtDesc(id) }
 @Transactional fun review(id:UUID,userId:UUID,rating:Int,content:String): ProductReview {
   get(id)
   if(!clones.existsByProductIdAndUserId(id,userId)) throw com.agentvillage.common.exception.ForbiddenException("PRODUCT_REVIEW_REQUIRES_CLONE","복제해 사용한 하네스만 후기를 작성할 수 있습니다.")
   val review=reviews.findByProductIdAndUserId(id,userId) ?: ProductReview(productId=id,userId=userId,rating=rating,content=content.trim())
   review.rating=rating; review.content=content.trim(); review.updatedAt=java.time.Instant.now(); return reviews.save(review)
 }
 @Transactional(readOnly=true) fun adminList() = products.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
 @Transactional fun setOfficial(id:UUID, official:Boolean): MarketProduct { val product=get(id); product.official=official; return product }
 @Transactional fun adminDelete(id:UUID) { val product=get(id); products.delete(product) }
}
