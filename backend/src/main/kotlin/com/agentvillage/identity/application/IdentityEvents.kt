package com.agentvillage.identity.application

import java.util.UUID

data class UserRegistered(
    val userId: UUID,
    val handle: String,
    val displayName: String,
)

