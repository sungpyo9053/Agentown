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
import org.springframework.test.context.TestPropertySource
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID

@AutoConfigureMockMvc
@TestPropertySource(properties = ["auth.sms.expose-development-values=true"])
class AuthIntegrationTest : IntegrationTestSupport() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper

    @Test
    fun `signup creates user profile and mini home then login establishes session`() {
        val suffix = UUID.randomUUID().toString().filter(Char::isDigit).padEnd(8, '7').take(8)
        val loginId = "village_${UUID.randomUUID().toString().take(6)}"
        val phone = "010$suffix"
        val codeResponse = mvc.perform(
            post("/api/auth/phone/send-code").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"phone":"$phone"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val codeJson = mapper.readTree(codeResponse)
        val verificationId = codeJson["verificationId"].asText()
        val developmentCode = codeJson["developmentCode"].asText()

        mvc.perform(
            post("/api/auth/phone/verify-code").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"verificationId":"$verificationId","code":"$developmentCode"}"""),
        ).andExpect(status().isNoContent)

        mvc.perform(
            post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(
                """{"password":"password123","handle":"$loginId","displayName":"빌리지","phone":"$phone","phoneVerificationId":"$verificationId"}""",
            ),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.handle", equalTo(loginId)))
            .andExpect(jsonPath("$.role", equalTo("USER")))

        val login = mvc.perform(
            post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(
                """{"loginId":"$loginId","password":"password123"}""",
            ),
        ).andExpect(status().isOk).andReturn()

        mvc.perform(get("/api/auth/me").session(login.request.session as MockHttpSession))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.role", equalTo("USER")))

        mvc.perform(get("/api/mini-homes/me").session(login.request.session as MockHttpSession))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.handle", equalTo(loginId)))
            .andExpect(jsonPath("$.title", equalTo("빌리지의 AI 회사")))

        val temporary = mvc.perform(
            post("/api/auth/password/temporary").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"loginId":"$loginId","phone":"$phone"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val temporaryPassword = mapper.readTree(temporary)["developmentTemporaryPassword"].asText()
        mvc.perform(
            post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"loginId":"$loginId","password":"$temporaryPassword"}"""),
        ).andExpect(status().isOk)
    }
}
