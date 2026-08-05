package com.agentvillage.harness.application

import com.agentvillage.agent.domain.Agent
import com.agentvillage.llmcredential.domain.LlmProvider
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

data class SnapshotAgent(
    val sourceAgentId: UUID,
    val name: String,
    val role: String,
    val script: String,
    val guide: String?,
    val provider: LlmProvider,
    val recommendedModel: String,
    val temperature: BigDecimal,
    val maxOutputTokens: Int,
    val timeoutSeconds: Int,
    val providerOptions: Map<String, Any>,
)

@Component
class HarnessSnapshotPolicy {
    fun snapshot(agent: Agent) = SnapshotAgent(
        sourceAgentId = agent.id,
        name = agent.name,
        role = agent.role,
        script = agent.script,
        guide = agent.guide,
        provider = agent.modelProvider,
        recommendedModel = agent.modelName,
        temperature = agent.temperature,
        maxOutputTokens = agent.maxOutputTokens,
        timeoutSeconds = agent.timeoutSeconds,
        providerOptions = agent.providerOptions,
    )
}

