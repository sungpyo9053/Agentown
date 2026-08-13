package com.agentvillage.harness.application

import com.agentvillage.agent.application.SaveAgentCommand
import com.agentvillage.common.domain.Visibility
import org.springframework.stereotype.Component

@Component
class AgentClonePolicy {
    fun commandFrom(snapshot: SnapshotAgent): SaveAgentCommand = SaveAgentCommand(
        name = snapshot.name,
        role = snapshot.role,
        personality = null,
        department = null,
        characterKey = "writer",
        systemPrompt = null,
        script = snapshot.script,
        guide = snapshot.guide,
        modelProvider = snapshot.provider,
        modelName = snapshot.recommendedModel,
        credentialId = null,
        temperature = snapshot.temperature,
        maxOutputTokens = snapshot.maxOutputTokens,
        timeoutSeconds = snapshot.timeoutSeconds,
        providerOptions = snapshot.providerOptions,
        visibility = Visibility.PRIVATE,
    )
}
