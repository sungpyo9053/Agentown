package com.agentvillage.security

import com.agentvillage.IntegrationTestSupport
import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.RegisterUserCommand
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@AutoConfigureMockMvc
class SharingSecurityIntegrationTest : IntegrationTestSupport() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var identities: IdentityService
    @Autowired lateinit var mapper: ObjectMapper

    @Test
    fun `public profile hides email and profile can be updated`() {
        val member = identities.register(RegisterUserCommand("private-profile@example.com", "password123", "safe_profile", "안전 프로필"))
        val principal = AuthenticatedUser(member.id, member.handle, "unused", true)

        mvc.perform(patch("/api/users/me").with(user(principal)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("""{"displayName":"새 이름","bio":"공개 소개","avatarUrl":""}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.displayName").value("새 이름"))

        val body = mvc.perform(get("/api/users/safe_profile")).andExpect(status().isOk)
            .andExpect(jsonPath("$.bio").value("공개 소개")).andReturn().response.contentAsString
        assertThat(body).doesNotContain("private-profile@example.com").doesNotContain("password")

        mvc.perform(patch("/api/users/me/password").with(user(principal)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("""{"currentPassword":"password123","newPassword":"password456"}"""))
            .andExpect(status().isNoContent)
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("""{"email":"private-profile@example.com","password":"password456"}"""))
            .andExpect(status().isOk)
        mvc.perform(delete("/api/users/me").with(user(principal)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("""{"currentPassword":"password456"}"""))
            .andExpect(status().isNoContent)
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("""{"email":"private-profile@example.com","password":"password456"}"""))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `private published harness cannot be cloned or listed by another user and market requires ownership`() {
        val owner = identities.register(RegisterUserCommand("share-owner@example.com", "password123", "share_owner", "소유자"))
        val stranger = identities.register(RegisterUserCommand("share-stranger@example.com", "password123", "share_stranger", "외부인"))
        val ownerPrincipal = AuthenticatedUser(owner.id, owner.handle, "unused", true)
        val strangerPrincipal = AuthenticatedUser(stranger.id, stranger.handle, "unused", true)

        fun postJson(path: String, principal: AuthenticatedUser, body: String) = mvc.perform(
            post(path).with(user(principal)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body),
        )
        val agentId = mapper.readTree(postJson("/api/agents", ownerPrincipal, """{
          "name":"공유 검증자","role":"검증","characterKey":"reviewer","script":"결과를 검증한다",
          "modelProvider":"OPENAI","modelName":"gpt-4o-mini","visibility":"PRIVATE"
        }""").andExpect(status().isCreated).andReturn().response.contentAsString)["id"].asText()
        val harnessId = mapper.readTree(postJson("/api/harnesses", ownerPrincipal, """{"name":"비공개 발행본"}""")
            .andExpect(status().isCreated).andReturn().response.contentAsString)["id"].asText()
        postJson("/api/harnesses/$harnessId/connect", ownerPrincipal, """{"agentIds":["$agentId"]}""").andExpect(status().isOk)
        postJson("/api/harnesses/$harnessId/publish", ownerPrincipal, "{}").andExpect(status().isOk)

        postJson("/api/harnesses/$harnessId/clone", strangerPrincipal, "{}").andExpect(status().isForbidden)
        postJson("/api/market/products", strangerPrincipal,
            """{"harnessId":"$harnessId","title":"도용 상품","category":"OTHER"}""").andExpect(status().isNotFound)

        val productId = mapper.readTree(postJson("/api/market/products", ownerPrincipal,
            """{"harnessId":"$harnessId","title":"정상 상품","category":"DOCUMENT"}""").andExpect(status().isCreated)
            .andReturn().response.contentAsString)["id"].asText()
        postJson("/api/harnesses/$harnessId/clone", strangerPrincipal, "{}").andExpect(status().isOk)
        postJson("/api/market/products/$productId/reviews", strangerPrincipal, """{"rating":5,"content":"미복제 후기"}""")
            .andExpect(status().isForbidden)
        postJson("/api/market/products/$productId/clone", strangerPrincipal, "{}").andExpect(status().isOk)
        postJson("/api/market/products/$productId/reviews", strangerPrincipal, """{"rating":5,"content":"복제 후 검증 완료"}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.rating").value(5))
    }
}
