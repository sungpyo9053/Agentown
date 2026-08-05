package com.agentvillage.agent.application

import com.agentvillage.common.domain.Visibility
import com.agentvillage.llmcredential.domain.LlmProvider
import java.math.BigDecimal
import java.util.UUID

data class AgentDescriptor(
    val id: UUID, val name: String, val role: String, val script: String, val guide: String?,
    val provider: LlmProvider, val model: String, val temperature: BigDecimal, val maxOutputTokens: Int,
    val timeoutSeconds: Int, val providerOptions: Map<String, Any>, val credentialId: UUID? = null,
    val systemPrompt: String? = null,
)

interface AgentDirectory {
    fun requireOwned(agentId: UUID, ownerId: UUID)
    fun describeOwned(agentId: UUID, ownerId: UUID): AgentDescriptor
    fun cloneFrom(source: AgentDescriptor, ownerId: UUID): UUID
}
