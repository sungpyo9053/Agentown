package com.agentvillage.identity.application

import com.agentvillage.common.exception.ConflictException
import com.agentvillage.common.exception.NotFoundException
import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.identity.domain.Profile
import com.agentvillage.identity.domain.UserAccount
import com.agentvillage.identity.infrastructure.ProfileRepository
import com.agentvillage.identity.infrastructure.UserAccountRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Locale
import java.util.UUID

data class RegisterUserCommand(
    val email: String,
    val password: String,
    val handle: String?,
    val displayName: String,
    val emailVerifiedAt: java.time.Instant? = null,
)

data class AccountAvailability(val emailAvailable: Boolean?)
data class PublicProfile(val id: UUID, val handle: String, val displayName: String, val bio: String?, val avatarUrl: String?)

@Service
class IdentityService(
    private val users: UserAccountRepository,
    private val profiles: ProfileRepository,
    private val passwordEncoder: PasswordEncoder,
    private val events: ApplicationEventPublisher,
) : UserDirectory {
    @Transactional(readOnly = true)
    fun availability(email: String?): AccountAvailability = AccountAvailability(
        emailAvailable = email?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }?.let { !users.existsByEmailIgnoreCase(it) },
    )

    @Transactional
    fun register(command: RegisterUserCommand): UserIdentity {
        val email = command.email.trim().lowercase(Locale.ROOT)
        val handle = command.handle?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: generateHandle(email)
        if (users.existsByEmailIgnoreCase(email)) {
            throw ConflictException("EMAIL_ALREADY_USED", "이미 사용 중인 이메일입니다.")
        }
        if (users.existsByHandle(handle)) {
            throw ConflictException("HANDLE_ALREADY_USED", "이미 사용 중인 아이디입니다.")
        }
        val user = users.save(
            UserAccount(
                email = email,
                emailVerifiedAt = command.emailVerifiedAt,
                passwordHash = passwordEncoder.encode(command.password),
                handle = handle,
            ),
        )
        val profile = profiles.save(Profile(userId = user.id, displayName = command.displayName.trim()))
        events.publishEvent(UserRegistered(user.id, user.handle, profile.displayName))
        return user.toIdentity(profile)
    }

    @Transactional(readOnly = true)
    override fun require(userId: UUID): UserIdentity {
        val user = users.findById(userId).orElseThrow {
            NotFoundException("USER_NOT_FOUND", "사용자를 찾을 수 없습니다.")
        }
        val profile = profiles.findById(userId).orElseThrow {
            NotFoundException("PROFILE_NOT_FOUND", "프로필을 찾을 수 없습니다.")
        }
        return user.toIdentity(profile)
    }

    @Transactional(readOnly = true)
    override fun findByHandle(handle: String): UserIdentity? {
        val user = users.findByHandle(handle.lowercase(Locale.ROOT)) ?: return null
        val profile = profiles.findById(user.id).orElse(null) ?: return null
        return user.toIdentity(profile)
    }

    @Transactional(readOnly = true)
    override fun findByEmail(email: String): UserIdentity? {
        val user = users.findByEmailIgnoreCase(email.trim()) ?: return null
        val profile = profiles.findById(user.id).orElse(null) ?: return null
        return user.toIdentity(profile)
    }

    @Transactional(readOnly = true)
    fun publicProfile(handle: String): PublicProfile? {
        val user = users.findByHandle(handle.lowercase(Locale.ROOT)) ?: return null
        val profile = profiles.findById(user.id).orElse(null) ?: return null
        return PublicProfile(user.id, user.handle, profile.displayName, profile.bio, profile.avatarUrl)
    }

    @Transactional
    fun updateProfile(userId: UUID, displayName: String, bio: String?, avatarUrl: String?): PublicProfile {
        val user = users.findById(userId).orElseThrow { NotFoundException("USER_NOT_FOUND", "사용자를 찾을 수 없습니다.") }
        val profile = profiles.findById(userId).orElseThrow { NotFoundException("PROFILE_NOT_FOUND", "프로필을 찾을 수 없습니다.") }
        profile.displayName = displayName.trim()
        profile.bio = bio?.trim()?.takeIf { it.isNotEmpty() }
        profile.avatarUrl = avatarUrl?.trim()?.takeIf { it.isNotEmpty() }
        return PublicProfile(user.id, user.handle, profile.displayName, profile.bio, profile.avatarUrl)
    }

    @Transactional
    fun changePassword(userId: UUID, currentPassword: String, newPassword: String) {
        val user = users.findById(userId).orElseThrow { NotFoundException("USER_NOT_FOUND", "사용자를 찾을 수 없습니다.") }
        if (!passwordEncoder.matches(currentPassword, user.passwordHash)) {
            throw BadRequestException("CURRENT_PASSWORD_INVALID", "현재 비밀번호가 올바르지 않습니다.")
        }
        user.passwordHash = passwordEncoder.encode(newPassword)
    }

    @Transactional
    fun withdraw(userId: UUID, currentPassword: String) {
        val user = users.findById(userId).orElseThrow { NotFoundException("USER_NOT_FOUND", "사용자를 찾을 수 없습니다.") }
        if (!passwordEncoder.matches(currentPassword, user.passwordHash)) {
            throw BadRequestException("CURRENT_PASSWORD_INVALID", "현재 비밀번호가 올바르지 않습니다.")
        }
        user.status = com.agentvillage.identity.domain.UserStatus.WITHDRAWN
        user.email = null
        user.emailVerifiedAt = null
        user.passwordHash = passwordEncoder.encode(UUID.randomUUID().toString())
    }

    private fun generateHandle(email: String): String {
        val base = email.substringBefore('@').lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_]"), "_")
            .trim('_')
            .let { if (it.length >= 3) it else "user_$it" }
            .take(23)
        if (!users.existsByHandle(base)) return base
        repeat(20) {
            val candidate = "${base.take(23)}_${UUID.randomUUID().toString().replace("-", "").take(6)}"
            if (!users.existsByHandle(candidate)) return candidate
        }
        throw ConflictException("HANDLE_GENERATION_FAILED", "공개 프로필 주소를 생성하지 못했습니다. 다시 시도해 주세요.")
    }

    private fun UserAccount.toIdentity(profile: Profile) =
        UserIdentity(id = id, email = email.orEmpty(), handle = handle, displayName = profile.displayName, role = role)
}
