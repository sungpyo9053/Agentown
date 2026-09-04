package com.agentvillage.builder

import com.agentvillage.builder.application.BuilderUsageLimiter
import com.agentvillage.builder.application.PipelineContext
import com.agentvillage.builder.domain.BuilderUsageRecord
import com.agentvillage.builder.infrastructure.BuilderUsageRecordRepository
import com.agentvillage.common.domain.UserRole
import com.agentvillage.common.exception.ConflictException
import com.agentvillage.identity.application.UserDirectory
import com.agentvillage.identity.application.UserIdentity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
        val conversationId = UUID.randomUUID()
        val workflowId = UUID.randomUUID()
        val key = "message-1"
        val record = BuilderUsageRecord(ownerId = ownerId, conversationId = conversationId, workflowId = workflowId, limitSlot = null, idempotencyKey = key)
        whenever(records.findByOwnerIdAndIdempotencyKey(ownerId, key)).thenReturn(record)

        BuilderUsageLimiter(records, users, "").releaseFailedClaim(ownerId, conversationId, workflowId, key)

        verify(records).delete(record)
    }

    @Test
    fun `usage idempotency rejects another conversation and purpose operation`() {
        val ownerId = UUID.randomUUID()
        val automationConversationId = UUID.randomUUID()
        val automationWorkflowId = UUID.randomUUID()
        val agentContext = PipelineContext(UUID.randomUUID(), ownerId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        val claimKey = "cross-purpose-claim"
        val revisionKey = "cross-purpose-revision"
        whenever(records.findByOwnerIdAndIdempotencyKey(ownerId, claimKey)).thenReturn(BuilderUsageRecord(
            ownerId = ownerId, conversationId = automationConversationId, workflowId = automationWorkflowId,
            limitSlot = null, idempotencyKey = claimKey,
        ))
        whenever(records.findByOwnerIdAndIdempotencyKey(ownerId, revisionKey)).thenReturn(BuilderUsageRecord(
            ownerId = ownerId, conversationId = automationConversationId, workflowId = automationWorkflowId,
            limitSlot = null, idempotencyKey = revisionKey,
        ))
        val limiter = BuilderUsageLimiter(records, users, "")

        assertThatThrownBy { limiter.claim(agentContext, claimKey) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("다른 대화")
        assertThatThrownBy { limiter.claimRevision(agentContext, revisionKey) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("다른 대화")
    }
}
