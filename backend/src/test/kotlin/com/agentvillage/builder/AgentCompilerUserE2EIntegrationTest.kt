package com.agentvillage.builder

import com.agentvillage.IntegrationTestSupport
import com.agentvillage.builder.application.BuilderService
import com.agentvillage.builder.application.AgentGenerationDraftService
import com.agentvillage.builder.application.PipelineContext
import com.agentvillage.builder.application.StructuredMetaAgentPipeline
import com.agentvillage.builder.domain.AgentGenerationDraftState
import com.agentvillage.builder.domain.BuilderRunStatus
import com.agentvillage.builder.domain.BuilderConversationPurpose
import com.agentvillage.builder.domain.WorkflowStatus
import com.agentvillage.identity.application.IdentityService
import com.agentvillage.identity.application.RegisterUserCommand
import com.agentvillage.builder.infrastructure.AgentGenerationDraftRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class AgentCompilerUserE2EIntegrationTest : IntegrationTestSupport() {
    @Autowired lateinit var service: BuilderService
    @Autowired lateinit var identities: IdentityService
    @Autowired lateinit var generationDrafts: AgentGenerationDraftService
    @Autowired lateinit var generationDraftRepository: AgentGenerationDraftRepository

    @Test fun `TC01 evidence found and not found produce truthful structured outcomes`() {
        val owner = owner("faq")
        var snapshot = service.createConversation(owner.id, key("conversation"))
        snapshot = service.sendMessage(owner.id, snapshot.conversationId, "고객 문의가 들어오면 FAQ를 검색해서 답변 초안을 만들고, 근거가 없으면 담당자 확인이 필요하다고 표시하는 에이전트를 만들어줘.", key("message"))
        assertThat(generationDraftRepository.findByConversationId(snapshot.conversationId)?.state).isEqualTo(AgentGenerationDraftState.COMPLETED)
        assertThat(generationDraftRepository.findByConversationId(snapshot.conversationId)?.attempt).isEqualTo(1)
        snapshot = service.decideDesign(owner.id, snapshot.workflowId, true, key("approve"))

        val found = service.startSimulation(owner.id, snapshot.workflowId, mapOf("message" to "배송이 늦어지고 있는데 언제 받을 수 있나요?"), key("found"))
        assertThat(found.status).isEqualTo(BuilderRunStatus.SUCCEEDED)
        assertThat(found.requirementMatched).isTrue()
        assertThat(found.output?.get("draftResponse").toString()).contains("FAQ 근거에 따르면").doesNotContain("[Mock]")

        val missing = service.startSimulation(owner.id, snapshot.workflowId, mapOf("message" to "알 수 없는 질문", "mockSearchResults" to emptyList<Any>()), key("missing"))
        assertThat(missing.status).isEqualTo(BuilderRunStatus.SUCCEEDED)
        assertThat(missing.output?.get("needsAssigneeReview")).isEqualTo(true)
        assertThat(missing.output).doesNotContainKey("draftResponse")
    }

    @Test fun `TC02 and TC03 preserve version one and patch only schedule renderer and delivery`() {
        val owner = owner("news")
        var snapshot = service.createConversation(owner.id, key("conversation"))
        snapshot = service.sendMessage(owner.id, snapshot.conversationId, "매일 오전 8시에 AI 뉴스를 검색해서 중요한 뉴스 3개를 한국어로 요약하고, 내가 승인하면 Slack으로 보내줘.", key("message"))
        snapshot = service.decideDesign(owner.id, snapshot.workflowId, true, key("approve"))
        val version1 = snapshot.currentVersionId!!
        val stableIds = snapshot.graph!!.nodes.filterNot { it.nodeType in setOf("schedule.trigger", "template.render", "slack.send.mock") }.map { it.id }

        snapshot = service.applyPatch(owner.id, snapshot.workflowId, "실행 시간을 오전 9시로 바꾸고 Slack 대신 이메일로 보내줘.", version1, snapshot.validation!!.graphHash, key("patch"))
        assertThat(snapshot.versions).hasSize(2)
        assertThat(snapshot.currentVersionId).isNotEqualTo(version1)
        assertThat(snapshot.graph!!.nodes.single { it.nodeType == "schedule.trigger" }.config["cron"]).isEqualTo("0 0 9 * * *")
        assertThat(snapshot.graph!!.nodes.map { it.nodeType }).contains("email.send.mock", "human.approval").doesNotContain("slack.send.mock")
        assertThat(snapshot.graph!!.nodes.single { it.nodeType == "template.render" }.config["rendererKey"]).isEqualTo("plain-text.v1")
        assertThat(snapshot.graph!!.nodes.filterNot { it.nodeType in setOf("schedule.trigger", "template.render", "email.send.mock") }.map { it.id }).containsExactlyElementsOf(stableIds)
        assertThat(snapshot.requirement!!.unresolvedQuestions).anyMatch { it.key == "email-recipient" }
    }

    @Test fun `TC04 TC05 and TC10 keep deterministic diff add one summary agent and export complete package`() {
        val owner = owner("csv")
        var snapshot = service.createConversation(owner.id, key("conversation"))
        snapshot = service.sendMessage(owner.id, snapshot.conversationId, "두 개의 CSV 파일을 비교해서 추가·삭제·수정된 행을 찾고 결과를 표로 만들어주는 에이전트를 만들어줘.", key("message"))
        snapshot = service.decideDesign(owner.id, snapshot.workflowId, true, key("approve"))
        val version1 = snapshot.currentVersionId!!
        val compareId = snapshot.graph!!.nodes.single { it.nodeType == "data.csv.compare" }.id

        val comparison = service.startSimulation(owner.id, snapshot.workflowId, mapOf(
            "csvA" to "id,name\n1,old\n2,remove\n",
            "csvB" to "id,name\n1,new\n3,add\n",
        ), key("compare"))
        assertThat(comparison.status).isEqualTo(BuilderRunStatus.SUCCEEDED)
        assertThat(comparison.output?.get("changedRows") as List<*>).hasSize(3)
        assertThat(comparison.output?.get("rendered").toString()).contains("MODIFIED", "ADDED", "REMOVED")

        snapshot = service.applyPatch(owner.id, snapshot.workflowId, "변경된 내용 중 중요한 부분만 사람이 이해하기 쉽게 요약하는 단계도 추가해줘.", version1, snapshot.validation!!.graphHash, key("patch"))
        assertThat(snapshot.versions).hasSize(2)
        assertThat(snapshot.graph!!.nodes.single { it.nodeType == "data.csv.compare" }.id).isEqualTo(compareId)
        assertThat(snapshot.graph!!.nodes.map { it.nodeType }).contains("ai.generate", "template.render").doesNotContain("human.approval", "notion.search.mock", "knowledge.search.mock")
        assertThat(snapshot.agentDefinitions.map { it.key }).containsExactly("change-summary-writer")

        val files = service.harnessPackage(owner.id, snapshot.workflowId)
        assertThat(files.keys).contains("agent.yaml", "workflow.yaml", "prompts/system.md", "schemas/input.schema.json", "schemas/output.schema.json", "skills/README.md", "tools/tools.yaml", "mcp.json", "examples/sample-input.json", ".env.example", "README.md", "runtime-targets.json", "version.json")
        assertThat(files.values.joinToString("\n")).doesNotContain("sk-")
    }

    @Test fun `TC09 bounded flight monitoring never pays and only pauses for explicit approval`() {
        val owner = owner("flight")
        var snapshot = service.createConversation(owner.id, key("conversation"))
        snapshot = service.sendMessage(owner.id, snapshot.conversationId, "항공권이 20만 원 이하가 될 때까지 1초마다 계속 검색하고 발견하면 바로 결제해줘.", key("message"))
        snapshot = service.decideDesign(owner.id, snapshot.workflowId, true, key("approve"))

        val expensive = service.startSimulation(owner.id, snapshot.workflowId, mapOf("mockFlightPrice" to 250000), key("expensive"))
        assertThat(expensive.status).isEqualTo(BuilderRunStatus.SUCCEEDED)
        assertThat(expensive.output?.get("priceWithinBudget")).isEqualTo(false)
        assertThat(expensive.output?.get("branchMatched")).isEqualTo(true)
        assertThat(expensive.output?.get("externalCallPerformed")).isEqualTo(false)

        val affordable = service.startSimulation(owner.id, snapshot.workflowId, mapOf("mockFlightPrice" to 190000), key("affordable"))
        assertThat(affordable.status).isEqualTo(BuilderRunStatus.WAITING_APPROVAL)
        assertThat(affordable.steps.last().nodeType).isEqualTo("human.approval")
    }

    @Test fun `failed develop generation preserves the request and exposes FAILED state before retry`() {
        val owner = owner("developfailure")
        val snapshot = service.createConversation(owner.id, key("conversation"), BuilderConversationPurpose.AGENT_DEVELOPMENT)
        val instruction = "CSV 파일 두 개를 정확히 비교하는 에이전트를 만들어줘"

        generationDrafts.start(
            PipelineContext(UUID.randomUUID(), owner.id, snapshot.workspaceId, snapshot.conversationId, snapshot.workflowId),
            instruction,
            StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
        )

        service.recordGenerationFailure(owner.id, snapshot.conversationId, instruction, key("failed-message"), "생성 시간 초과")

        val failed = service.snapshot(owner.id, snapshot.conversationId)
        assertThat(failed.status).isEqualTo(WorkflowStatus.FAILED)
        assertThat(failed.messages.map { it.content }).anyMatch { it == instruction }
        assertThat(failed.messages.map { it.content }).anyMatch { it.contains("입력은 보존되었습니다") }
        val draft = generationDraftRepository.findByConversationId(snapshot.conversationId)!!
        assertThat(draft.state).isEqualTo(AgentGenerationDraftState.FAILED)
        assertThat(draft.sourceInstruction).isEqualTo(instruction)
        assertThat(draft.errorMessage).contains("시간 초과")
    }

    private fun owner(prefix: String) = identities.register(RegisterUserCommand("$prefix-${UUID.randomUUID()}@example.com", "password123", "${prefix}_${UUID.randomUUID().toString().take(8)}", "검증 사용자"))
    private fun key(prefix: String) = "$prefix-${UUID.randomUUID()}"
}
