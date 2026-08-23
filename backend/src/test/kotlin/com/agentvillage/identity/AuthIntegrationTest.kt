package com.agentvillage.identity

import com.agentvillage.IntegrationTestSupport
import com.agentvillage.identity.application.IdentityService
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
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
@TestPropertySource(properties = ["auth.email.expose-development-values=true"])
class AuthIntegrationTest : IntegrationTestSupport() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var identities: IdentityService
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `signup creates user profile and mini home then login establishes session`() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        val email = "village_$suffix@example.com"
        val codeResponse = mvc.perform(
            post("/api/auth/email/send-code").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val codeJson = mapper.readTree(codeResponse)
        val verificationId = codeJson["verificationId"].asText()
        val developmentCode = codeJson["developmentCode"].asText()

        mvc.perform(
            post("/api/auth/email/verify-code").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"verificationId":"$verificationId","code":"$developmentCode"}"""),
        ).andExpect(status().isNoContent)

        mvc.perform(
            post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(
                """{"password":"password123","email":"$email","displayName":"빌리지","emailVerificationId":"$verificationId"}""",
            ),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.email", equalTo(email)))
            .andExpect(jsonPath("$.role", equalTo("USER")))

        val login = mvc.perform(
            post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(
                """{"email":"$email","password":"password123"}""",
            ),
        ).andExpect(status().isOk).andReturn()

        mvc.perform(get("/api/auth/me").session(login.request.session as MockHttpSession))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.role", equalTo("USER")))

        mvc.perform(get("/api/mini-homes/me").session(login.request.session as MockHttpSession))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title", equalTo("빌리지의 AI 회사")))

        val temporary = mvc.perform(
            post("/api/auth/password/temporary").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val temporaryPassword = mapper.readTree(temporary)["developmentTemporaryPassword"].asText()
        mvc.perform(
            post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$temporaryPassword"}"""),
        ).andExpect(status().isOk)
    }

    @Test
    fun `duplicate email is rejected before verification code is created`() {
        val email = "duplicate-${UUID.randomUUID()}@example.com"
        identities.register(com.agentvillage.identity.application.RegisterUserCommand(email, "password123", "duplicate_user", "중복"))

        mvc.perform(get("/api/auth/availability").param("email", email))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.emailAvailable", equalTo(false)))

        mvc.perform(
            post("/api/auth/email/send-code").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email"}"""),
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("EMAIL_ALREADY_USED")))
    }

    @Test
    fun `email verification creates six digit codes and stores only hashes`() {
        fun request(email: String) = mapper.readTree(
            mvc.perform(
                post("/api/auth/email/send-code").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"$email"}"""),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
        )

        val first = request("random-a-${UUID.randomUUID()}@example.com")
        val second = request("random-b-${UUID.randomUUID()}@example.com")
        val firstCode = first["developmentCode"].asText()
        val secondCode = second["developmentCode"].asText()
        org.assertj.core.api.Assertions.assertThat(firstCode).matches("^[0-9]{6}$")
        org.assertj.core.api.Assertions.assertThat(secondCode).matches("^[0-9]{6}$")

        val storedHash = jdbc.queryForObject(
            "select code_hash from email_verifications where id = ?::uuid",
            String::class.java,
            first["verificationId"].asText(),
        )
        org.assertj.core.api.Assertions.assertThat(storedHash).hasSize(64).doesNotContain(firstCode)
    }
}
