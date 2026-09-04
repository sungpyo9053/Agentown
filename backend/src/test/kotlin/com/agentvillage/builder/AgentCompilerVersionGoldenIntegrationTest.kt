package com.agentvillage.builder

import com.agentvillage.IntegrationTestSupport
import com.agentvillage.builder.application.BuilderService
import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.RegisterUserCommand
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class AgentCompilerVersionGoldenIntegrationTest : IntegrationTestSupport() {
    @Autowired lateinit var service: BuilderService
    @Autowired lateinit var identities: IdentityService
    @Autowired lateinit var mapper: ObjectMapper

    @Test
    fun `follow up delivery replacement creates a new version and preserves graph topology`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("golden-$suffix@example.com", "password123", "golden_$suffix", "Golden 검증"))
        var snapshot = service.createConversation(owner.id, "golden-conversation-$suffix")
        snapshot = service.sendMessage(
            owner.id,
            snapshot.conversationId,
            "Slack 문의를 Notion FAQ에서 찾아 답변 초안을 만들고 담당자 승인 후 원래 Slack 스레드로 전송한다.",
            "golden-design-$suffix",
        )
        snapshot = service.decideDesign(owner.id, snapshot.workflowId, true, "golden-approve-$suffix")
        val originalVersion = snapshot.currentVersionId!!
        val originalNodeIds = snapshot.graph!!.nodes.map { it.id }
        val originalEdges = snapshot.graph!!.edges

        snapshot = service.applyPatch(
            owner.id,
            snapshot.workflowId,
            "아까 만든 에이전트에서 Slack 전송을 이메일 전송으로 바꿔줘.",
            originalVersion,
            snapshot.validation!!.graphHash,
            "golden-patch-$suffix",
        )

        assertThat(snapshot.currentVersionId).isNotEqualTo(originalVersion)
        assertThat(snapshot.versions).hasSize(2)
        assertThat(snapshot.graph!!.nodes.map { it.id }).containsExactlyElementsOf(originalNodeIds)
        assertThat(snapshot.graph!!.edges).isEqualTo(originalEdges)
        assertThat(snapshot.graph!!.nodes.map { it.nodeType }).contains("email.send.mock").doesNotContain("slack.reply.mock")
        assertThat(snapshot.graph!!.nodes.single { it.nodeType == "email.send.mock" }.config["connectionStatus"]).isEqualTo("UNRESOLVED")

        val packageFiles = service.harnessPackage(owner.id, snapshot.workflowId)
        assertThat(packageFiles.keys).contains(
            "agent.yaml", "workflow.yaml", "schemas/input.schema.json", "schemas/output.schema.json",
            "tools/tools.yaml", "mcp.json", "examples/sample-input.json", ".env.example", "README.md", "version.json",
        )
        val packageVersion = mapper.readTree(packageFiles.getValue("version.json"))["workflowVersionId"].asText()
        assertThat(packageVersion).isEqualTo(snapshot.currentVersionId.toString())
        assertThat(packageFiles.getValue("tools/tools.yaml")).contains("email.send.mock", "UNRESOLVED")
    }
}
