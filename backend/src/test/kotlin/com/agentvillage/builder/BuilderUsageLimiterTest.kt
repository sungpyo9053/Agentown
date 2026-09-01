package com.agentvillage.builder

import com.agentvillage.builder.application.BuilderUsageLimiter
import com.agentvillage.builder.infrastructure.BuilderUsageRecordRepository
import com.agentvillage.common.domain.UserRole
import com.agentvillage.identity.application.UserDirectory
import com.agentvillage.identity.application.UserIdentity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

class BuilderUsageLimiterTest {
    private val records = mock<BuilderUsageRecordRepository>()
    private val users = mock<UserDirectory>()

    @Test
    fun `admin account can use shared Codex without UUID allowlist`() {
        val ownerId = UUID.randomUUID()
        whenever(users.require(ownerId)).thenReturn(UserIdentity(ownerId, "admin@reviewdr.kr", "admin", "운영자", UserRole.ADMIN))

        val limiter = BuilderUsageLimiter(records, users, "")

        assertThat(limiter.isUnlimited(ownerId)).isTrue()
    }

    @Test
    fun `regular account is still limited when not allowlisted`() {
        val ownerId = UUID.randomUUID()
        whenever(users.require(ownerId)).thenReturn(UserIdentity(ownerId, "user@example.com", "user", "사용자", UserRole.USER))

        val limiter = BuilderUsageLimiter(records, users, "")

        assertThat(limiter.isUnlimited(ownerId)).isFalse()
    }

    @Test
    fun `failed generation releases its one-time usage slot`() {
        val ownerId = UUID.randomUUID()
        val key = "message-1"
        val record = mock<com.agentvillage.builder.domain.BuilderUsageRecord>()
        whenever(records.findByOwnerIdAndIdempotencyKey(ownerId, key)).thenReturn(record)

        BuilderUsageLimiter(records, users, "").releaseFailedClaim(ownerId, key)

        verify(records).delete(record)
    }
}
