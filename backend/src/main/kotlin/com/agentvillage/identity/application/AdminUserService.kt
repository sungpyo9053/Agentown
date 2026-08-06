package com.agentvillage.identity.application

import com.agentvillage.common.domain.UserRole
import com.agentvillage.common.exception.BadRequestException
import com.agentvillage.common.exception.NotFoundException
import com.agentvillage.identity.domain.UserStatus
import com.agentvillage.identity.infrastructure.ProfileRepository
import com.agentvillage.identity.infrastructure.UserAccountRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class AdminUserView(
    val id: UUID,
    val email: String?,
    val handle: String,
    val displayName: String,
    val role: UserRole,
    val status: UserStatus,
    val createdAt: Instant,
)

data class AdminUserSummary(
    val total: Long,
    val active: Long,
    val blocked: Long,
    val admins: Long,
)

@Service
class AdminUserService(
    private val users: UserAccountRepository,
    private val profiles: ProfileRepository,
) {
    @Transactional(readOnly = true)
    fun list(): List<AdminUserView> {
        val accounts = users.findTop100ByOrderByCreatedAtDesc()
        val displayNames = profiles.findAllById(accounts.map { it.id }).associateBy({ it.userId }, { it.displayName })
        return accounts.map { account ->
            AdminUserView(
                account.id, account.email, account.handle, displayNames[account.id].orEmpty(),
                account.role, account.status, account.createdAt,
            )
        }
    }

    @Transactional(readOnly = true)
    fun summary(): AdminUserSummary {
        val accounts = users.findAll()
        return AdminUserSummary(
            total = accounts.size.toLong(),
            active = accounts.count { it.status == UserStatus.ACTIVE }.toLong(),
            blocked = accounts.count { it.status == UserStatus.BLOCKED }.toLong(),
            admins = accounts.count { it.role == UserRole.ADMIN }.toLong(),
        )
    }

    @Transactional
    fun update(adminId: UUID, userId: UUID, role: UserRole?, status: UserStatus?): AdminUserView {
        if (adminId == userId && (role == UserRole.USER || status == UserStatus.BLOCKED)) {
            throw BadRequestException("ADMIN_SELF_LOCKOUT", "현재 관리자 계정은 강등하거나 차단할 수 없습니다.")
        }
        val account = users.findById(userId).orElseThrow {
            NotFoundException("USER_NOT_FOUND", "사용자를 찾을 수 없습니다.")
        }
        role?.let { account.role = it }
        status?.let { account.status = it }
        val profile = profiles.findById(userId).orElse(null)
        return AdminUserView(
            account.id, account.email, account.handle, profile?.displayName.orEmpty(),
            account.role, account.status, account.createdAt,
        )
    }
}
