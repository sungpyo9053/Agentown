package com.agentvillage.execution

import com.agentvillage.execution.application.AgentExecutionConfig
import com.agentvillage.execution.application.AiModelGatewayRegistry
import com.agentvillage.execution.application.ExecutionPreflightValidator
import com.agentvillage.execution.application.LlmConfigurationException
import com.agentvillage.execution.application.StubAiModelGateway
import com.agentvillage.llmcredential.application.CredentialDirectory
import com.agentvillage.llmcredential.application.CredentialMetadata
import com.agentvillage.llmcredential.domain.CredentialStatus
import com.agentvillage.llmcredential.domain.LlmProvider
import com.agentvillage.llmcredential.application.SupportedModelCatalog
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class ExecutionPreflightValidatorTest {
    private val owner = UUID.randomUUID()
    private val activeIds = LlmProvider.entries.associateWith { UUID.randomUUID() }
    private val directory = object : CredentialDirectory {
        override fun requireOwned(credentialId: UUID, ownerId: UUID, provider: LlmProvider) = requireActive(credentialId, ownerId, provider)
        override fun requireActive(credentialId: UUID, ownerId: UUID, provider: LlmProvider): CredentialMetadata {
            if (ownerId != owner || activeIds[provider] != credentialId) error("credential unavailable")
            return CredentialMetadata(credentialId, ownerId, provider, CredentialStatus.ACTIVE)
        }
        override fun <T> withDecrypted(
            credentialId: UUID,
            ownerId: UUID,
            provider: LlmProvider,
            block: (CharArray, Map<String, Any>) -> T,
        ): T {
            requireActive(credentialId, ownerId, provider)
            val secret = "test-secret".toCharArray()
            return try { block(secret, emptyMap()) } finally { secret.fill('\u0000') }
        }
    }
    private val validator = ExecutionPreflightValidator(
        directory, AiModelGatewayRegistry(LlmProvider.entries.map(::StubAiModelGateway)), SupportedModelCatalog(),
    )

    @Test
    fun `different providers can pass sequential preflight`() {
        val models = mapOf(
            LlmProvider.OPENAI to "gpt-5-mini",
            LlmProvider.ANTHROPIC to "claude-sonnet-4-0",
            LlmProvider.GOOGLE to "gemini-2.5-flash",
        )
        val configs = LlmProvider.entries.map { provider ->
            AgentExecutionConfig(UUID.randomUUID(), provider, models.getValue(provider), activeIds[provider])
        }
        assertThatCode { validator.validate(owner, configs) }.doesNotThrowAnyException()
    }

    @Test
    fun `missing credential is blocked before execution with agent details`() {
        val agentId = UUID.randomUUID()
        assertThatThrownBy {
            validator.validate(owner, listOf(AgentExecutionConfig(agentId, LlmProvider.OPENAI, "model", null)))
        }.isInstanceOfSatisfying(LlmConfigurationException::class.java) { exception ->
                org.assertj.core.api.Assertions.assertThat(exception.code).isEqualTo("LLM_CREDENTIAL_NOT_FOUND")
                org.assertj.core.api.Assertions.assertThat(exception.details["agentId"]).isEqualTo(agentId)
        }
    }
}
