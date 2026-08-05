package com.agentvillage.llmcredential

import com.agentvillage.IntegrationTestSupport
import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.RegisterUserCommand
import com.agentvillage.identity.infrastructure.AuthenticatedUser
import com.agentvillage.llmcredential.application.CredentialVerificationResult
import com.agentvillage.llmcredential.application.CredentialVerifierRegistry
import com.agentvillage.llmcredential.domain.LlmProvider
import com.agentvillage.llmcredential.domain.LlmCredential
import com.agentvillage.llmcredential.domain.CredentialStatus
import com.agentvillage.llmcredential.infrastructure.LlmCredentialRepository
import com.agentvillage.llmcredential.application.LlmCredentialService
import com.agentvillage.execution.application.AgentExecutionConfig
import com.agentvillage.execution.application.ExecutionPreflightValidator
import com.agentvillage.execution.application.LlmConfigurationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@AutoConfigureMockMvc
class LlmCredentialIntegrationTest : IntegrationTestSupport() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var identities: IdentityService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var credentials: LlmCredentialRepository
    @Autowired lateinit var credentialService: LlmCredentialService
    @Autowired lateinit var preflight: ExecutionPreflightValidator
    @MockBean lateinit var verifier: CredentialVerifierRegistry

    @Test
    fun `secret is encrypted in database and never returned by API`() {
        val identity = identities.register(RegisterUserCommand("key@example.com", "password123", "key_owner", "키 주인"))
        val principal = AuthenticatedUser(identity.id, identity.email, "unused", true)
        whenever(verifier.verify(eq(LlmProvider.OPENAI), any(), any()))
            .thenReturn(CredentialVerificationResult(true))
        val rawSecret = "sk-plain-secret-1234"

        mvc.perform(
            post("/api/llm-credentials").with(user(principal)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"provider":"OPENAI","secret":"$rawSecret","providerOptions":{}}"""),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.maskedSecret").value("sk-••••••••1234"))
            .andExpect(jsonPath("$.encryptedSecret").doesNotExist())

        val stored = jdbc.queryForObject("SELECT encrypted_secret FROM llm_credentials WHERE owner_id = ?", String::class.java, identity.id)
        assertThat(stored).isNotEqualTo(rawSecret).doesNotContain(rawSecret)

        val body = mvc.perform(get("/api/llm-credentials").with(user(principal)))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(body).doesNotContain(rawSecret, "encryptedSecret", "keyVersion")
    }

    @Test
    fun `another user credential cannot be connected to agent`() {
        val owner = identities.register(RegisterUserCommand("owner@example.com", "password123", "cred_owner", "소유자"))
        val attacker = identities.register(RegisterUserCommand("attacker@example.com", "password123", "cred_attacker", "다른 사용자"))
        val credential = credentials.save(
            LlmCredential(
                ownerId = owner.id, provider = LlmProvider.OPENAI, encryptedSecret = "cipher-only",
                maskedSecret = "sk-••••1234", keyVersion = "test-v1", status = CredentialStatus.ACTIVE,
            ),
        )
        val principal = AuthenticatedUser(attacker.id, attacker.email, "unused", true)

        mvc.perform(
            post("/api/agents").with(user(principal)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(
                """{"name":"침입","role":"작가","characterKey":"writer","script":"작성","modelProvider":"OPENAI","modelName":"gpt-4o-mini","credentialId":"${credential.id}"}""",
            ),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `deleted or provider mismatched credential blocks execution preflight`() {
        val owner = identities.register(RegisterUserCommand("runner@example.com", "password123", "cred_runner", "실행자"))
        val credential = credentials.save(
            LlmCredential(
                ownerId = owner.id, provider = LlmProvider.OPENAI, encryptedSecret = "cipher-only",
                maskedSecret = "sk-••••1234", keyVersion = "test-v1", status = CredentialStatus.ACTIVE,
            ),
        )
        val agentId = java.util.UUID.randomUUID()

        org.assertj.core.api.Assertions.assertThatThrownBy {
            preflight.validate(owner.id, listOf(AgentExecutionConfig(agentId, LlmProvider.ANTHROPIC, "claude", credential.id)))
        }.isInstanceOf(LlmConfigurationException::class.java)

        credentialService.delete(credential.id, owner.id)
        org.assertj.core.api.Assertions.assertThatThrownBy {
            preflight.validate(owner.id, listOf(AgentExecutionConfig(agentId, LlmProvider.OPENAI, "gpt-4o-mini", credential.id)))
        }.isInstanceOf(LlmConfigurationException::class.java)
    }
}
