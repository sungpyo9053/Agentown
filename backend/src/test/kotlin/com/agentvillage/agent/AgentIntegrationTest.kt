package com.agentvillage.agent

import com.agentvillage.IntegrationTestSupport
import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.RegisterUserCommand
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request

@AutoConfigureMockMvc
class AgentIntegrationTest : IntegrationTestSupport() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var identities: IdentityService

    @Test
    fun `owner creates lists and deletes an agent`() {
        val identity = identities.register(RegisterUserCommand("agent@example.com", "password123", "agent_user", "에이전트 유저"))
        val principal = AuthenticatedUser(identity.id, identity.email, "unused", true)
        val content = """{
            "name":"모모","role":"블로그 작가","personality":"차분함","characterKey":"writer",
            "systemPrompt":"정확하게 쓴다","script":"주제로 초안을 작성한다","guide":"출처를 표시한다",
            "modelProvider":"OPENAI","modelName":"gpt-4o-mini","temperature":0.7,"maxOutputTokens":2048,"visibility":"PRIVATE"
        }"""

        val created = mvc.perform(
            post("/api/agents").with(user(principal)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(content),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.name", equalTo("모모")))
            .andReturn()

        val id = Regex("\"id\":\"([^\"]+)\"").find(created.response.contentAsString)!!.groupValues[1]
        mvc.perform(get("/api/agents").with(user(principal)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id", equalTo(id)))

        val async = mvc.perform(post("/api/agents/{id}/test", id).with(user(principal)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("""{"input":"검증 입력","stubMode":true}"""))
            .andExpect(request().asyncStarted()).andReturn()
        mvc.perform(asyncDispatch(async)).andExpect(status().isOk)
            .andExpect(jsonPath("$.content", equalTo("stub:검증 입력")))
            .andExpect(jsonPath("$.stub", equalTo(true)))

        mvc.perform(delete("/api/agents/{id}", id).with(user(principal)).with(csrf()))
            .andExpect(status().isNoContent)
        mvc.perform(get("/api/agents/{id}", id).with(user(principal))).andExpect(status().isNotFound)
    }
}
