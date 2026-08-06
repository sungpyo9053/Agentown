package com.agentvillage.identity.domain

import com.agentvillage.common.domain.AuditedEntity
import com.agentvillage.common.domain.Visibility
import com.agentvillage.common.domain.UserRole
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import java.util.UUID

enum class UserStatus { ACTIVE, BLOCKED, WITHDRAWN }

@Entity
@Table(name = "users")
class UserAccount(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false, unique = true, length = 320)
    var email: String,

    @Column(name = "password_hash", nullable = false, length = 100)
    var passwordHash: String,

    @Column(nullable = false, unique = true, length = 30)
    var handle: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: UserStatus = UserStatus.ACTIVE,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: UserRole = UserRole.USER,
) : AuditedEntity()

@Entity
@Table(name = "profiles")
class Profile(
    @Id
    @Column(name = "user_id")
    val userId: UUID,

    @Column(name = "display_name", nullable = false, length = 40)
    var displayName: String,

    @Column(length = 300)
    var bio: String? = null,

    @Column(name = "avatar_url", length = 500)
    var avatarUrl: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var visibility: Visibility = Visibility.PUBLIC,
) : AuditedEntity()
