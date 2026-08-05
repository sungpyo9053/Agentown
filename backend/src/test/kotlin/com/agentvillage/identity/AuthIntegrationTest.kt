package com.agentvillage.identity

import com.agentvillage.IntegrationTestSupport
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@AutoConfigureMockMvc
class AuthIntegrationTest : IntegrationTestSupport() {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `signup creates user profile and mini home then login establishes session`() {
        mvc.perform(
            post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(
                """{"email":"village@example.com","password":"password123","handle":"village","displayName":"빌리지"}""",
            ),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.handle", equalTo("village")))

        val login = mvc.perform(
            post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(
                """{"email":"village@example.com","password":"password123"}""",
            ),
        ).andExpect(status().isOk).andReturn()

        mvc.perform(get("/api/mini-homes/me").session(login.request.session as MockHttpSession))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.handle", equalTo("village")))
            .andExpect(jsonPath("$.title", equalTo("빌리지의 AI 마을")))
    }
}
