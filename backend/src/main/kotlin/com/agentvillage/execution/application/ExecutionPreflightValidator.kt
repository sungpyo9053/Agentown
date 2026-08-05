package com.agentvillage.execution.application

import com.agentvillage.common.exception.ApiException
import com.agentvillage.llmcredential.application.CredentialDirectory
import com.agentvillage.llmcredential.domain.LlmProvider
import com.agentvillage.llmcredential.application.SupportedModelCatalog
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.util.UUID

data class AgentExecutionConfig(
    val agentId: UUID,
    val provider: LlmProvider,
    val model: String,
    val credentialId: UUID?,
)

class LlmConfigurationException(
    code: String,
    message: String,
    agentId: UUID,
    provider: LlmProvider,
) : ApiException(
    HttpStatus.UNPROCESSABLE_ENTITY,
    code,
    message,
    mapOf("agentId" to agentId, "requiredProvider" to provider),
)

@Service
class ExecutionPreflightValidator(
    private val credentials: CredentialDirectory,
    private val gateways: AiModelGatewayRegistry,
    private val modelCatalog: SupportedModelCatalog,
) {
    fun validate(requesterId: UUID, agents: List<AgentExecutionConfig>) {
        agents.forEach { agent ->
            val credentialId = agent.credentialId ?: throw LlmConfigurationException(
                "LLM_CREDENTIAL_NOT_FOUND", "에이전트에 LLM 자격증명을 연결해 주세요.", agent.agentId, agent.provider,
            )
            try {
                credentials.requireActive(credentialId, requesterId, agent.provider)
            } catch (exception: ApiException) {
                throw LlmConfigurationException(exception.code, exception.message, agent.agentId, agent.provider)
            }
            try {
                modelCatalog.requireSupported(agent.provider, agent.model)
            } catch (exception: ApiException) {
                throw LlmConfigurationException(
                    "LLM_MODEL_NOT_SUPPORTED", "지원되지 않는 모델입니다.", agent.agentId, agent.provider,
                )
            }
            if (!gateways.get(agent.provider).supportsModel(agent.model)) {
                throw LlmConfigurationException(
                    "LLM_MODEL_NOT_SUPPORTED", "Gateway에서 지원되지 않는 모델입니다.", agent.agentId, agent.provider,
                )
            }
        }
    }
}
