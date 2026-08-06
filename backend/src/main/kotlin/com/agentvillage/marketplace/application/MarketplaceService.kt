package com.agentvillage.marketplace.application

import com.agentvillage.common.exception.NotFoundException
import com.agentvillage.harness.application.HarnessService
import com.agentvillage.marketplace.domain.*
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface MarketProductRepository : JpaRepository<MarketProduct, UUID> { fun findAllByTitleContainingIgnoreCase(query: String, sort: Sort): List<MarketProduct> }
interface ProductLikeRepository : JpaRepository<ProductLike, ProductLikeId> { fun existsByProductIdAndUserId(productId: UUID, userId: UUID): Boolean; fun deleteByProductIdAndUserId(productId: UUID, userId: UUID) }
@Service class MarketplaceService(private val products: MarketProductRepository, private val likes: ProductLikeRepository, private val harnesses: HarnessService) {
 @Transactional(readOnly=true) fun list(query: String?, order: String) = products.findAllByTitleContainingIgnoreCase(query.orEmpty(), Sort.by(if(order=="popular") Sort.Direction.DESC else Sort.Direction.DESC, if(order=="popular") "likeCount" else "createdAt"))
 @Transactional(readOnly=true) fun get(id: UUID)=products.findById(id).orElseThrow{NotFoundException("PRODUCT_NOT_FOUND","상품을 찾을 수 없습니다.")}
 @Transactional fun create(userId: UUID, harnessId: UUID, title: String, description: String?, category: String, official: Boolean): MarketProduct {
   val v=harnesses.latestPublished(harnessId); return products.save(MarketProduct(harnessVersionId=v.id, creatorId=userId,title=title,description=description,category=category,official=official))
 }
 @Transactional fun clone(id: UUID,userId: UUID): Any { val p=get(id); val h=harnesses.clone(harnesses.latestPublishedId(p.harnessVersionId),userId); p.cloneCount++; return h }
 @Transactional fun like(id:UUID,userId:UUID): MarketProduct { val p=get(id); if(!likes.existsByProductIdAndUserId(id,userId)){likes.save(ProductLike(id,userId));p.likeCount++};return p }
 @Transactional fun unlike(id:UUID,userId:UUID){val p=get(id);if(likes.existsByProductIdAndUserId(id,userId)){likes.deleteByProductIdAndUserId(id,userId);p.likeCount=(p.likeCount-1).coerceAtLeast(0)}}
 @Transactional(readOnly=true) fun adminList() = products.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
 @Transactional fun setOfficial(id:UUID, official:Boolean): MarketProduct { val product=get(id); product.official=official; return product }
 @Transactional fun adminDelete(id:UUID) { val product=get(id); products.delete(product) }
}
