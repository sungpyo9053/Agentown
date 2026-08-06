package com.agentvillage.marketplace.domain

import com.agentvillage.common.domain.AuditedEntity
import jakarta.persistence.*
import java.util.UUID

@Entity @Table(name = "market_products")
class MarketProduct(@Id val id: UUID = UUID.randomUUID(), @Column(name="harness_version_id", nullable=false) val harnessVersionId: UUID,
 @Column(name="creator_id", nullable=false) val creatorId: UUID, @Column(nullable=false) var title: String,
 var description: String? = null, @Column(nullable=false) var category: String, @Column(nullable=false) var official: Boolean = false,
 @Column(name="clone_count", nullable=false) var cloneCount: Long = 0, @Column(name="like_count", nullable=false) var likeCount: Long = 0) : AuditedEntity()

@Entity @Table(name="product_likes") @IdClass(ProductLikeId::class)
class ProductLike(@Id @Column(name="product_id") val productId: UUID = UUID.randomUUID(), @Id @Column(name="user_id") val userId: UUID = UUID.randomUUID())
data class ProductLikeId(var productId: UUID? = null, var userId: UUID? = null) : java.io.Serializable

@Entity @Table(name="product_clones")
class ProductClone(@Id val id: UUID = UUID.randomUUID(), @Column(name="product_id", nullable=false) val productId: UUID,
 @Column(name="user_id", nullable=false) val userId: UUID, @Column(name="cloned_harness_id", nullable=false) val clonedHarnessId: UUID,
 @Column(name="created_at", nullable=false) val createdAt: java.time.Instant = java.time.Instant.now())

@Entity @Table(name="product_reviews", uniqueConstraints=[UniqueConstraint(columnNames=["product_id", "user_id"])])
class ProductReview(@Id val id: UUID = UUID.randomUUID(), @Column(name="product_id", nullable=false) val productId: UUID,
 @Column(name="user_id", nullable=false) val userId: UUID, @Column(nullable=false) var rating: Int,
 @Column(nullable=false, length=1000) var content: String, @Column(name="created_at", nullable=false) val createdAt: java.time.Instant = java.time.Instant.now(),
 @Column(name="updated_at", nullable=false) var updatedAt: java.time.Instant = java.time.Instant.now())
