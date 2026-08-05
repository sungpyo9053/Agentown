package com.agentvillage.agent.application

import com.agentvillage.agent.domain.Agent
import com.agentvillage.agent.infrastructure.AgentRepository
import com.agentvillage.common.domain.Visibility
import com.agentvillage.common.exception.NotFoundException
import com.agentvillage.llmcredential.application.CredentialDirectory
import com.agentvillage.llmcredential.domain.LlmProvider
import com.agentvillage.llmcredential.application.ProviderOptionsPolicy
import com.agentvillage.llmcredential.application.SupportedModelCatalog
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

data class SaveAgentCommand(
    val name: String,
    val role: String,
    val personality: String?,
    val characterKey: String,
    val systemPrompt: String?,
    val script: String,
    val guide: String?,
    val modelProvider: LlmProvider,
    val modelName: String,
    val credentialId: UUID?,
    val temperature: BigDecimal,
    val maxOutputTokens: Int,
    val timeoutSeconds: Int,
    val providerOptions: Map<String, Any>,
    val visibility: Visibility,
)

@Service
class AgentService(
    private val agents: AgentRepository,
    private val credentials: CredentialDirectory,
    private val optionsPolicy: ProviderOptionsPolicy,
    private val modelCatalog: SupportedModelCatalog,
) : AgentDirectory {
    @Transactional
    fun create(ownerId: UUID, command: SaveAgentCommand): Agent {
        validateCredential(ownerId, command)
        return agents.save(
            Agent(
                ownerId = ownerId,
                name = command.name.trim(),
                role = command.role.trim(),
                personality = command.personality?.trim()?.takeIf { it.isNotEmpty() },
                characterKey = command.characterKey,
                systemPrompt = command.systemPrompt?.trim()?.takeIf { it.isNotEmpty() },
                script = command.script.trim(),
                guide = command.guide?.trim()?.takeIf { it.isNotEmpty() },
                modelProvider = command.modelProvider,
                modelName = command.modelName,
                credentialId = command.credentialId,
                temperature = command.temperature,
                maxOutputTokens = command.maxOutputTokens,
                timeoutSeconds = command.timeoutSeconds,
                providerOptions = command.providerOptions,
                visibility = command.visibility,
            ),
        )
    }

    @Transactional(readOnly = true)
    fun list(ownerId: UUID): List<Agent> = agents.findAllByOwnerIdOrderByCreatedAtDesc(ownerId)

    @Transactional(readOnly = true)
    fun getOwned(id: UUID, ownerId: UUID): Agent = findOwned(id, ownerId)

    @Transactional
    fun update(id: UUID, ownerId: UUID, command: SaveAgentCommand): Agent {
        validateCredential(ownerId, command)
        val agent = findOwned(id, ownerId)
        agent.name = command.name.trim()
        agent.role = command.role.trim()
        agent.personality = command.personality?.trim()?.takeIf { it.isNotEmpty() }
        agent.characterKey = command.characterKey
        agent.systemPrompt = command.systemPrompt?.trim()?.takeIf { it.isNotEmpty() }
        agent.script = command.script.trim()
        agent.guide = command.guide?.trim()?.takeIf { it.isNotEmpty() }
        agent.modelProvider = command.modelProvider
        agent.modelName = command.modelName
        agent.credentialId = command.credentialId
        agent.temperature = command.temperature
        agent.maxOutputTokens = command.maxOutputTokens
        agent.timeoutSeconds = command.timeoutSeconds
        agent.providerOptions = command.providerOptions
        agent.visibility = command.visibility
        return agent
    }

    @Transactional
    fun delete(id: UUID, ownerId: UUID) {
        agents.delete(findOwned(id, ownerId))
    }

    @Transactional(readOnly = true)
    override fun requireOwned(agentId: UUID, ownerId: UUID) {
        if (!agents.existsByIdAndOwnerId(agentId, ownerId)) {
            throw NotFoundException("AGENT_NOT_FOUND", "에이전트를 찾을 수 없습니다.")
        }
    }

    @Transactional(readOnly = true)
    override fun describeOwned(agentId: UUID, ownerId: UUID): AgentDescriptor = findOwned(agentId, ownerId).let {
        AgentDescriptor(it.id, it.name, it.role, it.script, it.guide, it.modelProvider, it.modelName,
            it.temperature, it.maxOutputTokens, it.timeoutSeconds, it.providerOptions, it.credentialId, it.systemPrompt)
    }

    @Transactional
    override fun cloneFrom(source: AgentDescriptor, ownerId: UUID): UUID = agents.save(Agent(
        ownerId = ownerId, name = source.name, role = source.role, characterKey = "manager",
        script = source.script, guide = source.guide, modelProvider = source.provider, modelName = source.model,
        credentialId = null, temperature = source.temperature, maxOutputTokens = source.maxOutputTokens,
        timeoutSeconds = source.timeoutSeconds, providerOptions = source.providerOptions, systemPrompt = source.systemPrompt,
        visibility = Visibility.PRIVATE,
    )).id

    @Transactional(readOnly = true)
    override fun listVisible(agentIds: Collection<UUID>, ownerId: UUID, allowedVisibilities: Set<Visibility>) =
        agents.findAllById(agentIds).filter { it.ownerId == ownerId && it.visibility in allowedVisibilities }
            .map { MiniHomeAgentDescriptor(it.id, it.name, it.role, it.characterKey) }

    private fun findOwned(id: UUID, ownerId: UUID): Agent =
        agents.findByIdAndOwnerId(id, ownerId)
            ?: throw NotFoundException("AGENT_NOT_FOUND", "에이전트를 찾을 수 없습니다.")

    private fun validateCredential(ownerId: UUID, command: SaveAgentCommand) {
        optionsPolicy.validate(command.providerOptions)
        modelCatalog.requireSupported(command.modelProvider, command.modelName)
        command.credentialId?.let { credentials.requireOwned(it, ownerId, command.modelProvider) }
    }
}
