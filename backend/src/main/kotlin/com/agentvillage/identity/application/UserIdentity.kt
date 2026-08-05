package com.agentvillage.identity.application

import java.util.UUID

data class UserIdentity(
    val id: UUID,
    val email: String,
    val handle: String,
    val displayName: String,
)

interface UserDirectory {
    fun require(userId: UUID): UserIdentity
    fun findByHandle(handle: String): UserIdentity?
}

