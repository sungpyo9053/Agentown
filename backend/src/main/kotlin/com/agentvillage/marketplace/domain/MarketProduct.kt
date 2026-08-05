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
