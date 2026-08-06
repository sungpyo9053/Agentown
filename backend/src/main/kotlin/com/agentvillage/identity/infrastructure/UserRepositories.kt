package com.agentvillage.identity.infrastructure

import com.agentvillage.identity.domain.Profile
import com.agentvillage.identity.domain.UserAccount
import com.agentvillage.identity.domain.PhoneVerification
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserAccountRepository : JpaRepository<UserAccount, UUID> {
    fun findByEmailIgnoreCase(email: String): UserAccount?
    fun findByHandle(handle: String): UserAccount?
    fun findByPhoneHash(phoneHash: String): UserAccount?
    fun existsByEmailIgnoreCase(email: String): Boolean
    fun existsByHandle(handle: String): Boolean
    fun findTop100ByOrderByCreatedAtDesc(): List<UserAccount>
}

interface ProfileRepository : JpaRepository<Profile, UUID>

interface PhoneVerificationRepository : JpaRepository<PhoneVerification, UUID>
