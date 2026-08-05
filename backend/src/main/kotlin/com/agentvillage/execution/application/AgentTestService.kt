package com.agentvillage.execution.application

import com.agentvillage.agent.application.AgentDirectory
import com.agentvillage.common.exception.ConflictException
import com.agentvillage.llmcredential.application.CredentialDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

data class AgentTestResult(
    val agentId: UUID,
    val provider: String,
    val model: String,
    val content: String,
    val tokenUsage: TokenUsage,
    val providerRequestId: String?,
    val stub: Boolean,
)

@Service
class AgentTestService(
    private val agents: AgentDirectory,
    private val credentials: CredentialDirectory,
    private val gateways: AiModelGatewayRegistry,
    @Value("\${execution.stub-enabled:true}") private val stubEnabled: Boolean,
) {
    suspend fun test(agentId: UUID, ownerId: UUID, input: String, stub: Boolean): AgentTestResult {
        val agent = agents.describeOwned(agentId, ownerId)
        if (stub && !stubEnabled) throw ConflictException("STUB_DISABLED", "Stub 실행은 이 환경에서 비활성화되어 있습니다.")
        val response = if (stub) {
            AiModelResponse("stub:$input", TokenUsage(1, 1), "stub-agent-test")
        } else {
            val credentialId = agent.credentialId ?: throw ConflictException("LLM_CREDENTIAL_NOT_FOUND", "이 구성원에 API 자격증명을 연결해야 합니다.")
            withTimeout(agent.timeoutSeconds * 1000L) {
                withContext(Dispatchers.IO) {
                    credentials.withDecrypted(credentialId, ownerId, agent.provider) { secret, options ->
                        DecryptedCredential(agent.provider, secret, options).use { credential ->
                            gateways.get(agent.provider).execute(credential, AiModelRequest(
                                agent.model, agent.systemPrompt, input, agent.temperature, agent.maxOutputTokens,
                                agent.timeoutSeconds, agent.providerOptions,
                            ))
                        }
                    }
                }
            }
        }
        return AgentTestResult(agent.id, agent.provider.name, agent.model, response.content, response.tokenUsage, response.providerRequestId, stub)
    }
}
