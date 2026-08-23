package com.agentvillage.builder

import com.agentvillage.IntegrationTestSupport
import com.agentvillage.builder.application.BuilderService
import com.agentvillage.builder.domain.BuilderRunStatus
import com.agentvillage.builder.domain.WorkflowStatus
import com.agentvillage.common.exception.NotFoundException
import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.RegisterUserCommand
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class BuilderMvpIntegrationTest : IntegrationTestSupport() {
    @Autowired lateinit var service: BuilderService
    @Autowired lateinit var identities: IdentityService

    @Test
    fun `natural language design compile version patch and simulation approval resume persist end to end`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("builder-$suffix@example.com", "password123", "builder_$suffix", "Builder 검증"))
        val stranger = identities.register(RegisterUserCommand("builder-other-$suffix@example.com", "password123", "builder_other_$suffix", "다른 워크스페이스"))

        var snapshot = service.createConversation(owner.id, "conversation-$suffix")
        snapshot = service.sendMessage(owner.id, snapshot.conversationId, "Slack으로 고객 문의를 받고 Notion FAQ를 찾아 답변 초안을 만든 뒤 담당자 승인 후 Slack으로 전송하고 싶다.", "message-1-$suffix")
        assertThat(snapshot.status).isEqualTo(WorkflowStatus.WAITING_DESIGN_APPROVAL)
        assertThat(snapshot.requirement?.steps).hasSize(5)
        assertThat(snapshot.proposal).isNotNull
        assertThat(snapshot.agentDefinitions.map { it.key }).containsExactly("faq-searcher", "faq-answer-writer")
        assertThat(snapshot.guideDefinitions.map { it.key }).containsExactly("slack-mock", "notion-mock")
        assertThat(snapshot.graph).isNull()

        snapshot = service.decideDesign(owner.id, snapshot.workflowId, true, "design-approve-$suffix")
        assertThat(snapshot.status).isEqualTo(WorkflowStatus.READY_TO_SIMULATE)
        assertThat(snapshot.graph?.nodes?.map { it.nodeType }).containsExactly("slack.new_message.mock", "notion.search.mock", "ai.generate", "human.approval", "slack.reply.mock")
        assertThat(snapshot.validation?.valid).isTrue()
        assertThat(snapshot.currentVersionId).isEqualTo(snapshot.approvedVersionId)

        val firstVersion = snapshot.currentVersionId!!
        snapshot = service.applyPatch(owner.id, snapshot.workflowId, "Slack 답변 전 담당자 승인을 추가해줘.", firstVersion, snapshot.validation!!.graphHash, "patch-1-$suffix")
        assertThat(snapshot.versions).hasSize(2)
        assertThat(snapshot.currentVersionId).isNotEqualTo(firstVersion)

        var run = service.startSimulation(owner.id, snapshot.workflowId, mapOf("message" to "환불은 언제 처리되나요?", "token" to "must-not-persist"), "simulation-1-$suffix")
        assertThat(run.status).isEqualTo(BuilderRunStatus.WAITING_APPROVAL)
        assertThat(run.steps.map { it.nodeType }).containsExactly("slack.new_message.mock", "notion.search.mock", "ai.generate", "human.approval")
        assertThat(run.steps.first().input["token"]).isEqualTo("***")
        assertThat(run.pendingApprovalId).isNotNull

        val duplicate = service.startSimulation(owner.id, snapshot.workflowId, mapOf("message" to "다른 입력"), "simulation-1-$suffix")
        assertThat(duplicate.id).isEqualTo(run.id)
        run = service.decideExecution(owner.id, run.id, true, "execution-approve-$suffix")
        assertThat(run.status).isEqualTo(BuilderRunStatus.SUCCEEDED)
        assertThat(run.steps.last().nodeType).isEqualTo("slack.reply.mock")
        assertThat(run.output).containsEntry("externalCallPerformed", false)
        assertThat(run.requirementMatched).isTrue()
        assertThat(service.decideExecution(owner.id, run.id, true, "execution-approve-$suffix").id).isEqualTo(run.id)

        assertThatThrownBy { service.snapshot(stranger.id, snapshot.conversationId) }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `missing approver asks only clarification then produces proposal after answer`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("clarify-$suffix@example.com", "password123", "clarify_$suffix", "질문 검증"))
        var snapshot = service.createConversation(owner.id, "clarify-conversation-$suffix")
        snapshot = service.sendMessage(owner.id, snapshot.conversationId, "Slack 문의를 Notion FAQ로 찾아 답변하고 싶다.", "clarify-message-$suffix")
        assertThat(snapshot.status).isEqualTo(WorkflowStatus.NEEDS_CLARIFICATION)
        assertThat(snapshot.clarificationQuestions.single().field).isEqualTo("approvalPolicy")
        snapshot = service.sendMessage(owner.id, snapshot.conversationId, "고객지원 팀장이 승인한다.", "clarify-answer-$suffix")
        assertThat(snapshot.status).isEqualTo(WorkflowStatus.WAITING_DESIGN_APPROVAL)
    }

    @Test
    fun `unsupported developer automation is not compiled into a fake harness`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("unsupported-$suffix@example.com", "password123", "unsupported_$suffix", "범위 검증"))
        var snapshot = service.createConversation(owner.id, "unsupported-conversation-$suffix")
        snapshot = service.sendMessage(owner.id, snapshot.conversationId, "나는 백엔드 개발자인데 커밋까지 자동화해줘", "unsupported-message-$suffix")
        assertThat(snapshot.status).isEqualTo(WorkflowStatus.NEEDS_CLARIFICATION)
        assertThat(snapshot.clarificationQuestions.map { it.field }).containsExactly("inbound", "knowledgeSource", "approvalPolicy", "destination")
        assertThat(snapshot.proposal).isNull()
        assertThat(snapshot.graph).isNull()
    }
}
