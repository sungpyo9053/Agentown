package com.agentvillage.identity.application

import com.agentvillage.common.exception.ConflictException
import com.agentvillage.common.exception.NotFoundException
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
    val email: String?,
    val password: String,
    val handle: String,
    val displayName: String,
    val phoneHash: String? = null,
    val phoneMasked: String? = null,
    val phoneVerifiedAt: java.time.Instant? = null,
)

data class AccountAvailability(val handleAvailable: Boolean?)

@Service
class IdentityService(
    private val users: UserAccountRepository,
    private val profiles: ProfileRepository,
    private val passwordEncoder: PasswordEncoder,
    private val events: ApplicationEventPublisher,
) : UserDirectory {
    @Transactional(readOnly = true)
    fun availability(handle: String?): AccountAvailability = AccountAvailability(
        handleAvailable = handle?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }?.let { !users.existsByHandle(it) },
    )

    @Transactional
    fun register(command: RegisterUserCommand): UserIdentity {
        val email = command.email?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
        val handle = command.handle.trim().lowercase(Locale.ROOT)
        if (email != null && users.existsByEmailIgnoreCase(email)) {
            throw ConflictException("EMAIL_ALREADY_USED", "이미 사용 중인 이메일입니다.")
        }
        if (users.existsByHandle(handle)) {
            throw ConflictException("HANDLE_ALREADY_USED", "이미 사용 중인 아이디입니다.")
        }
        if (command.phoneHash != null && users.findByPhoneHash(command.phoneHash) != null) {
            throw ConflictException("PHONE_ALREADY_USED", "이미 가입된 휴대폰 번호입니다.")
        }

        val user = users.save(
            UserAccount(
                email = email,
                passwordHash = passwordEncoder.encode(command.password),
                handle = handle,
                phoneHash = command.phoneHash,
                phoneMasked = command.phoneMasked,
                phoneVerifiedAt = command.phoneVerifiedAt,
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

    private fun UserAccount.toIdentity(profile: Profile) =
        UserIdentity(id = id, email = email.orEmpty(), handle = handle, displayName = profile.displayName, role = role)
}
