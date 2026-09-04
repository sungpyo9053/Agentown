package com.agentvillage.builder

import com.agentvillage.IntegrationTestSupport
import com.agentvillage.builder.application.BuilderService
import com.agentvillage.builder.domain.BuilderConversationPurpose
import com.agentvillage.builder.domain.BuilderGenerationJob
import com.agentvillage.builder.domain.BuilderGenerationStage
import com.agentvillage.builder.domain.BuilderGenerationStatus
import com.agentvillage.builder.infrastructure.BuilderActivityEventRepository
import com.agentvillage.builder.infrastructure.BuilderGenerationJobRepository
import com.agentvillage.common.domain.UserRole
import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.RegisterUserCommand
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@AutoConfigureMockMvc
class AdminAgentDevelopmentIntegrationTest : IntegrationTestSupport() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var identities: IdentityService
    @Autowired lateinit var builder: BuilderService
    @Autowired lateinit var jobs: BuilderGenerationJobRepository
    @Autowired lateinit var activities: BuilderActivityEventRepository

    @Test
    fun `admin sees retained natural language with pseudonymous identity and audit events`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("audit-owner-$suffix@example.com", "password123", "audit_owner_$suffix", "감사 기록 사용자"))
        val ownerPrincipal = AuthenticatedUser(owner.id, owner.email, "unused", true)

        mvc.perform(
            post("/api/agent-development/sessions")
                .with(user(ownerPrincipal)).with(csrf())
                .header("Idempotency-Key", "audit-session-$suffix"),
        ).andExpect(status().isOk)

        val conversation = builder.listConversations(owner.id, BuilderConversationPurpose.AGENT_DEVELOPMENT).first()
        val snapshot = builder.snapshot(owner.id, conversation.conversationId)
        val secretInstruction = "외부에 노출되면 안 되는 자연어 요청 $suffix"
        jobs.save(
            BuilderGenerationJob(
                workspaceId = snapshot.workspaceId,
                conversationId = snapshot.conversationId,
                workflowId = snapshot.workflowId,
                instruction = secretInstruction,
                status = BuilderGenerationStatus.SUCCEEDED,
                stage = BuilderGenerationStage.COMPLETED,
                idempotencyKey = "audit-generation-$suffix",
            ),
        )

        assertThat(activities.findTop200ByOrderByCreatedAtDesc().any { it.eventType == "SESSION_CREATED" && it.workspaceId == snapshot.workspaceId }).isTrue()

        val admin = AuthenticatedUser(UUID.randomUUID(), "admin-$suffix@example.com", "unused", true, UserRole.ADMIN)
        val metrics = mvc.perform(get("/api/admin/agent-development/metrics?days=30").with(user(admin)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.naturalLanguageInputs").isNumber)
            .andExpect(jsonPath("$.privacy.rawNaturalLanguageStored").value(true))
            .andExpect(jsonPath("$.privacy.rawContentExposedToAdmin").value(true))
            .andExpect(jsonPath("$.recentInputs[0].workspaceAlias").isString)
            .andExpect(jsonPath("$.recentInputs[0].instruction").value(secretInstruction))
            .andReturn().response.contentAsString
        val activity = mvc.perform(get("/api/admin/agent-development/activities").with(user(admin)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].workspaceAlias").isString)
            .andExpect(jsonPath("$[0].eventType").isString)
            .andReturn().response.contentAsString

        assertThat(metrics).doesNotContain(owner.email, owner.id.toString(), snapshot.workspaceId.toString())
        assertThat(activity).doesNotContain(secretInstruction, owner.email, owner.id.toString(), snapshot.workspaceId.toString())
    }

    @Test
    fun `non admin cannot access agent development observability`() {
        val user = AuthenticatedUser(UUID.randomUUID(), "ordinary@example.com", "unused", true)
        mvc.perform(get("/api/admin/agent-development/metrics").with(user(user))).andExpect(status().isForbidden)
        mvc.perform(get("/api/admin/agent-development/activities").with(user(user))).andExpect(status().isForbidden)
    }
}
