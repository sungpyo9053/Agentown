package com.agentvillage.builder

import com.agentvillage.IntegrationTestSupport
import com.agentvillage.builder.application.BuilderService
import com.agentvillage.builder.application.TFRAMEX_COMMIT
import com.agentvillage.builder.application.TFrameXFlowImport
import com.agentvillage.builder.domain.BuilderConversationPurpose
import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.RegisterUserCommand
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class AgentDevelopmentFlowRoundTripIntegrationTest : IntegrationTestSupport() {
    @Autowired lateinit var service: BuilderService
    @Autowired lateinit var identities: IdentityService

    @Test
    fun `exported TFrameX flow imports as a validated immutable version`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("flow-$suffix@example.com", "password123", "flow_$suffix", "Flow 검증"))
        var snapshot = service.createConversation(owner.id, "flow-session-$suffix", BuilderConversationPurpose.AGENT_DEVELOPMENT)
        snapshot = service.sendMessage(
            owner.id,
            snapshot.conversationId,
            "사용자 입력을 분석해 구조화된 요약을 반환하는 에이전트를 만들어줘.",
            "flow-design-$suffix",
        )
        snapshot = service.decideDesign(owner.id, snapshot.workflowId, true, "flow-approve-$suffix")
        val originalVersion = snapshot.currentVersionId!!
        val originalHash = snapshot.validation!!.graphHash
        val exported = service.exportTFrameXFlow(owner.id, snapshot.workflowId)

        assertThat(exported["format"]).isEqualTo("agentown-tframex-flow/v1")
        assertThat(exported["tframexCommit"]).isEqualTo(TFRAMEX_COMMIT)
        @Suppress("UNCHECKED_CAST")
        val imported = service.importTFrameXFlow(owner.id, snapshot.workflowId, TFrameXFlowImport(
            baseVersionId = originalVersion,
            expectedGraphHash = originalHash,
            tframexCommit = exported.getValue("tframexCommit").toString(),
            designBundle = exported.getValue("designBundle") as Map<String, Any?>,
            runtimeDefinition = exported.getValue("runtimeDefinition") as Map<String, Any?>,
        ), "flow-import-$suffix")

        assertThat(imported.currentVersionId).isNotEqualTo(originalVersion)
        assertThat(imported.versions).hasSize(2)
        assertThat(imported.validation!!.graphHash).isEqualTo(originalHash)
        assertThat(imported.messages.last().content).contains("TFrameX Flow", "새 버전 2")
    }

    @Test
    fun `flow import rejects a runtime definition that differs from its design bundle`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("tamper-$suffix@example.com", "password123", "tamper_$suffix", "Flow 변조 검증"))
        var snapshot = service.createConversation(owner.id, "tamper-session-$suffix", BuilderConversationPurpose.AGENT_DEVELOPMENT)
        snapshot = service.sendMessage(owner.id, snapshot.conversationId, "입력을 한 문장으로 요약하는 에이전트", "tamper-design-$suffix")
        snapshot = service.decideDesign(owner.id, snapshot.workflowId, true, "tamper-approve-$suffix")
        val exported = service.exportTFrameXFlow(owner.id, snapshot.workflowId)
        @Suppress("UNCHECKED_CAST")
        val definition = (exported.getValue("runtimeDefinition") as Map<String, Any?>).toMutableMap().apply { this["flowName"] = "tampered" }

        assertThatThrownBy {
            service.importTFrameXFlow(owner.id, snapshot.workflowId, TFrameXFlowImport(
                snapshot.currentVersionId!!,
                snapshot.validation!!.graphHash,
                TFRAMEX_COMMIT,
                exported.getValue("designBundle") as Map<String, Any?>,
                definition,
            ), "tamper-import-$suffix")
        }.hasMessageContaining("일치하지 않습니다")
    }
}
