package com.agentvillage.harness

import com.agentvillage.agent.domain.Agent
import com.agentvillage.harness.application.AgentClonePolicy
import com.agentvillage.harness.application.HarnessSnapshotPolicy
import com.agentvillage.llmcredential.domain.LlmProvider
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class HarnessSnapshotPolicyTest {
    @Test
    fun `snapshot never contains credential information`() {
        val agent = Agent(
            ownerId = UUID.randomUUID(), name = "Writer", role = "작가", characterKey = "writer",
            script = "초안을 작성한다", modelProvider = LlmProvider.OPENAI, modelName = "gpt-4o-mini",
            credentialId = UUID.randomUUID(), providerOptions = mapOf("responseFormat" to "json"),
        )
        val json = jacksonObjectMapper().writeValueAsString(HarnessSnapshotPolicy().snapshot(agent))

        assertThat(json).contains("recommendedModel", "responseFormat")
        assertThat(json).doesNotContain("credentialId", "encryptedSecret", "apiKey", "token")

        val cloneCommand = AgentClonePolicy().commandFrom(HarnessSnapshotPolicy().snapshot(agent))
        assertThat(cloneCommand.credentialId).isNull()
    }
}
