package com.agentvillage.identity

import com.agentvillage.IntegrationTestSupport
import com.agentvillage.common.domain.UserRole
import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.RegisterUserCommand
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import com.agentvillage.identity.infrastructure.UserAccountRepository
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@AutoConfigureMockMvc
class AdminPlatformIntegrationTest : IntegrationTestSupport() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var identities: IdentityService
    @Autowired lateinit var users: UserAccountRepository

    @Test
    fun `platform admin can manage all users while normal user is forbidden`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val adminIdentity = identities.register(RegisterUserCommand("admin-$suffix@example.com", "password123", "admin_$suffix", "운영자"))
        users.findById(adminIdentity.id).orElseThrow().also { it.role = UserRole.ADMIN }.let(users::save)
        val member = identities.register(RegisterUserCommand("member-$suffix@example.com", "password123", "member_$suffix", "회원"))
        val admin = AuthenticatedUser(adminIdentity.id, adminIdentity.email, "unused", true, UserRole.ADMIN)
        val normal = AuthenticatedUser(member.id, member.email, "unused", true, UserRole.USER)

        mvc.perform(get("/api/admin/system").with(user(normal)))
            .andExpect(status().isForbidden)

        mvc.perform(get("/api/admin/users/summary").with(user(admin)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.admins").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))

        mvc.perform(
            patch("/api/admin/users/${member.id}").with(user(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"BLOCKED"}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status", equalTo("BLOCKED")))

        mvc.perform(
            patch("/api/admin/users/${adminIdentity.id}").with(user(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"role":"USER"}"""),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("ADMIN_SELF_LOCKOUT")))
    }
}
