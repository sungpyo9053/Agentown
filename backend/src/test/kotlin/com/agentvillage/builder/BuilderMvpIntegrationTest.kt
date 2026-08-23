package com.agentvillage.builder

import com.agentvillage.IntegrationTestSupport
import com.agentvillage.builder.application.BuilderService
import com.agentvillage.builder.domain.BuilderRunStatus
import com.agentvillage.builder.domain.WorkflowStatus
import com.agentvillage.common.exception.NotFoundException
import com.agentvillage.common.exception.ConflictException
import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.RegisterUserCommand
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
        val conversationId = snapshot.conversationId
        val workflowId = snapshot.workflowId
        snapshot = service.sendMessage(owner.id, conversationId, "고객 문의 답변하는 일을 자동화하고 싶어요.", "message-vague-$suffix")
        assertThat(snapshot.status).isEqualTo(WorkflowStatus.NEEDS_CLARIFICATION)
        assertThat(snapshot.clarificationQuestions.map { it.field }).containsExactly("inbound", "knowledgeSource", "approvalPolicy", "destination")
        assertThat(snapshot.proposal).isNull()
        assertThat(snapshot.graph).isNull()

        snapshot = service.sendMessage(owner.id, conversationId, "Slack #customer-support 문의를 Notion FAQ에서 찾아 답변 초안을 만들고 담당자 승인 후 원래 Slack 스레드로 전송한다.", "message-details-$suffix")
        assertThat(snapshot.conversationId).isEqualTo(conversationId)
        assertThat(snapshot.workflowId).isEqualTo(workflowId)
        assertThat(snapshot.status).isEqualTo(WorkflowStatus.WAITING_DESIGN_APPROVAL)
        assertThat(snapshot.requirement?.steps).hasSize(5)
        assertThat(snapshot.proposal).isNotNull
        assertThat(snapshot.agentDefinitions.map { it.key }).containsExactly("faq-searcher", "faq-answer-writer")
        assertThat(snapshot.agentDefinitions).hasSize(2)
        assertThat(snapshot.agentDefinitions.joinToString(" ") { "${it.name} ${it.role}" })
            .doesNotContain("승인 라우팅", "게시 에이전트", "문의 분류")
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

        val stopped = service.stop(owner.id, workflowId, "workflow-stop-$suffix")
        assertThat(stopped.conversationId).isEqualTo(conversationId)
        assertThat(stopped.workflowId).isEqualTo(workflowId)
        assertThat(stopped.currentVersionId).isEqualTo(snapshot.currentVersionId)
        assertThat(stopped.status).isEqualTo(WorkflowStatus.STOPPED)

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
    fun `partial clarification answers accumulate and only unanswered fields are asked again`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("partial-$suffix@example.com", "password123", "partial_$suffix", "누적 답변 검증"))
        var snapshot = service.createConversation(owner.id, "partial-conversation-$suffix")

        snapshot = service.sendMessage(owner.id, snapshot.conversationId, "고객 문의 답변을 자동화하고 싶어요.", "partial-vague-$suffix")
        assertThat(snapshot.clarificationQuestions.map { it.field }).containsExactly("inbound", "knowledgeSource", "approvalPolicy", "destination")

        snapshot = service.sendMessage(owner.id, snapshot.conversationId, "Slack #customer-support 채널로 들어옵니다.", "partial-inbound-$suffix")
        assertThat(snapshot.clarificationQuestions.map { it.field }).containsExactly("knowledgeSource", "approvalPolicy", "destination")

        snapshot = service.sendMessage(owner.id, snapshot.conversationId, "Notion 고객 FAQ 데이터베이스를 참고합니다.", "partial-knowledge-$suffix")
        assertThat(snapshot.clarificationQuestions.map { it.field }).containsExactly("approvalPolicy", "destination")

        snapshot = service.sendMessage(owner.id, snapshot.conversationId, "담당자가 검토하고 승인한 경우에만 원래 Slack 메시지 스레드로 전송합니다.", "partial-final-$suffix")
        assertThat(snapshot.status).isEqualTo(WorkflowStatus.WAITING_DESIGN_APPROVAL)
        assertThat(snapshot.clarificationQuestions).isEmpty()
        assertThat(snapshot.proposal).isNotNull
    }

    @Test
    fun `writing automation partial answer keeps only contextual unanswered questions`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("writing-$suffix@example.com", "password123", "writing_$suffix", "글쓰기 답변 검증"))
        var snapshot = service.createConversation(owner.id, "writing-conversation-$suffix")

        snapshot = service.sendMessage(owner.id, snapshot.conversationId, "최신 토픽으로 글쓰기 자동화하고 싶어요.", "writing-vague-$suffix")
        snapshot = service.sendMessage(owner.id, snapshot.conversationId, "매일 아침 9시 참고는 최신뉴스 결과는 워드로", "writing-partial-$suffix")

        assertThat(snapshot.status).isEqualTo(WorkflowStatus.NEEDS_CLARIFICATION)
        assertThat(snapshot.clarificationQuestions.map { it.field }).containsExactly("knowledgeSource", "approvalPolicy", "destination")
        assertThat(snapshot.clarificationQuestions.map { it.question }).containsExactly(
            "최신 뉴스는 어느 사이트, RSS 또는 뉴스 서비스에서 수집할까요?",
            "작성된 글을 바로 저장할까요, 담당자가 검토하고 승인한 뒤 저장할까요?",
            "Word 문서는 어느 서비스나 폴더에 저장하거나 누구에게 전달할까요?",
        )
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

    @ParameterizedTest(name = "모호한 요청은 설계하지 않고 질문한다 - {0}")
    @ValueSource(strings = [
        "글쓰기를 자동화하고 싶어요.",
        "고객 문의 답변하는 일을 자동화하고 싶어요.",
        "매일 하는 보고 업무를 줄이고 싶어요.",
        "회의 정리를 자동으로 해주세요.",
        "마케팅 업무를 자동화하고 싶습니다.",
        "신규 입사자 안내를 자동화해줘.",
        "영업 후속 작업을 자동으로 처리하고 싶어요.",
        "자료 조사와 요약을 알아서 해주세요.",
        "반복되는 백오피스 업무를 없애고 싶어요.",
        "콘텐츠 발행 과정을 자동화하고 싶습니다.",
    ])
    fun `ten vague requests ask four required questions before design`(instruction: String) {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("vague-$suffix@example.com", "password123", "vague_$suffix", "모호한 요청 검증"))
        var snapshot = service.createConversation(owner.id, "vague-conversation-$suffix")

        snapshot = service.sendMessage(owner.id, snapshot.conversationId, instruction, "vague-message-$suffix")

        assertThat(snapshot.status).isEqualTo(WorkflowStatus.NEEDS_CLARIFICATION)
        assertThat(snapshot.clarificationQuestions.map { it.field })
            .containsExactly("inbound", "knowledgeSource", "approvalPolicy", "destination")
        assertThat(snapshot.proposal).isNull()
        assertThat(snapshot.agentDefinitions).isEmpty()
        assertThat(snapshot.graph).isNull()
        assertThat(snapshot.messages.last().content).isEqualTo("설계를 진행하려면 아래 4가지 정보가 더 필요합니다. 질문별 답변을 한 번에 작성해 주세요.")
        assertThat(snapshot.messages.last().content).doesNotContain(snapshot.clarificationQuestions.first().question)
    }

    @Test
    fun `stopped workflow preserves versions and blocks simulation patch and approval resume`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = identities.register(RegisterUserCommand("stop-$suffix@example.com", "password123", "stop_$suffix", "중지 검증"))
        var snapshot = service.createConversation(owner.id, "stop-conversation-$suffix")
        snapshot = service.sendMessage(owner.id, snapshot.conversationId, "Slack 문의를 Notion FAQ에서 찾아 답변 초안을 만들고 담당자 승인 후 Slack 스레드로 전송한다.", "stop-message-$suffix")
        snapshot = service.decideDesign(owner.id, snapshot.workflowId, true, "stop-design-$suffix")
        val versionId = snapshot.currentVersionId!!
        val versionCount = snapshot.versions.size
        val run = service.startSimulation(owner.id, snapshot.workflowId, mapOf("message" to "환불은 언제 처리되나요?"), "stop-run-$suffix")
        assertThat(run.status).isEqualTo(BuilderRunStatus.WAITING_APPROVAL)

        snapshot = service.stop(owner.id, snapshot.workflowId, "stop-workflow-$suffix")

        assertThat(snapshot.status).isEqualTo(WorkflowStatus.STOPPED)
        assertThat(snapshot.currentVersionId).isEqualTo(versionId)
        assertThat(snapshot.versions).hasSize(versionCount)
        assertThatThrownBy { service.startSimulation(owner.id, snapshot.workflowId, mapOf("message" to "재실행"), "stop-run-again-$suffix") }
            .isInstanceOf(ConflictException::class.java)
        assertThatThrownBy { service.applyPatch(owner.id, snapshot.workflowId, "Slack 답변 전 담당자 승인을 추가해줘.", versionId, snapshot.validation!!.graphHash, "stop-patch-$suffix") }
            .isInstanceOf(ConflictException::class.java)
        assertThatThrownBy { service.decideExecution(owner.id, run.id, true, "stop-approval-$suffix") }
            .isInstanceOf(ConflictException::class.java)
    }
}
