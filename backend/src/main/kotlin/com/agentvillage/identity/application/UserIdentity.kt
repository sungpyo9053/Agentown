package com.agentvillage.identity.application

import com.agentvillage.common.domain.UserRole
import java.util.UUID

data class UserIdentity(
    val id: UUID,
    val email: String,
    val handle: String,
    val displayName: String,
    val role: UserRole,
)

interface UserDirectory {
    fun require(userId: UUID): UserIdentity
    fun findByHandle(handle: String): UserIdentity?
    fun findByEmail(email: String): UserIdentity?
}
