package com.agentvillage.builder

import com.agentvillage.builder.application.*
import com.agentvillage.builder.domain.*
import com.agentvillage.builder.infrastructure.MetaAgentRunRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

class AgentCompilerUserAcceptanceTest {
    private val mapper = jacksonObjectMapper()
    private val runs = mock<MetaAgentRunRepository>().also { whenever(it.save(any())).thenAnswer { call -> call.arguments[0] } }
    private val pipeline = StructuredMetaAgentPipeline(DeterministicMockMetaAgentModel(mapper), mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>())

    @Test fun `TC01 FAQ design has evidence branch and answer contract`() {
        val bundle = compile("고객 문의가 들어오면 FAQ를 검색해서 답변 초안을 만들고, 근거가 없으면 담당자 확인이 필요하다고 표시하는 에이전트를 만들어줘.")
        val plan = bundle.proposal.graphPlan!!
        assertThat(plan.nodes.map { it.nodeType }).contains("knowledge.search.mock", "condition.branch", "ai.generate")
        assertThat(plan.edges.filter { it.source == plan.nodes.single { it.nodeType == "condition.branch" }.id }.map { it.condition })
            .containsExactlyInAnyOrder("evidenceFound=true", "evidenceFound=false")
    }

    @Test fun `TC02 news design is scheduled approved and Slack rendered`() {
        val bundle = compile("매일 오전 8시에 AI 뉴스를 검색해서 중요한 뉴스 3개를 한국어로 요약하고, 내가 승인하면 Slack으로 보내줘.")
        assertThat(bundle.proposal.graphPlan!!.nodes.map { it.nodeType }).containsSubsequence(
            "schedule.trigger", "news.search.mock", "ai.generate", "template.render", "human.approval", "slack.send.mock",
        )
        assertThat(bundle.proposal.graphPlan!!.nodes.filter { it.nodeType in setOf("template.render", "slack.send.mock") })
            .allSatisfy { assertThat(it.config["rendererKey"]).isNotNull }
    }

    @Test fun `TC04 CSV comparison is deterministic and has no approval`() {
        val bundle = compile("두 개의 CSV 파일을 비교해서 추가·삭제·수정된 행을 찾고 결과를 표로 만들어주는 에이전트를 만들어줘.")
        assertThat(bundle.clarificationQuestions).isEmpty()
        assertThat(bundle.proposal.graphPlan!!.nodes.map { it.nodeType }).contains("data.csv.compare").doesNotContain("ai.generate", "human.approval")
        assertThat(bundle.proposal.inputSchema.map { it.name }).containsExactly("csvA", "csvB", "keyColumns")
        assertThat(bundle.proposal.inputSchema.filter { it.required }.map { it.name }).containsExactly("csvA", "csvB")
        assertThat(bundle.proposal.inputSchema.single { it.name == "keyColumns" }.type).isEqualTo("array")
    }

    @Test fun `TC06 competitor research never falls back to a fake parallel map`() {
        val bundle = compile("경쟁사 세 곳의 최근 제품 발표와 가격 변화를 각각 조사하고, 조사가 끝나면 하나의 비교 보고서로 합쳐줘.")
        assertThat(bundle.proposal.graphPlan!!.nodes.map { it.nodeType }).contains("ai.generate").doesNotContain("parallel.map.mock", "human.approval")
    }

    @Test fun `TC07 GitHub issue classification is not misclassified as deployment`() {
        val bundle = compile("GitHub 이슈가 등록되면 버그, 기능 요청, 질문으로 분류하고 버그인 경우에만 재현 절차 초안을 작성해줘.")
        assertThat(bundle.clarificationQuestions).isEmpty()
        assertThat(bundle.proposal.graphPlan!!.nodes.map { it.nodeType }).contains("github.issue.mock", "ai.classify", "condition.branch", "ai.generate")
    }

    @Test fun `TC08 unsupported PeopleMagic is explicit and never replaced by FAQ`() {
        val bundle = compile("사내 인사 시스템 PeopleMagic에서 휴가 잔여일을 조회해 알려주는 에이전트를 만들어줘.")
        assertThat(bundle.proposal.resourcePlan!!.uncoveredCapabilities).isNotEmpty
        assertThat(bundle.proposal.graphPlan!!.nodes.map { it.nodeType }).contains("tool.unresolved").doesNotContain("notion.search.mock", "knowledge.search.mock")
    }

    @Test fun `TC09 flight polling is bounded and payment remains approval gated unresolved`() {
        val bundle = compile("항공권이 20만 원 이하가 될 때까지 1초마다 계속 검색하고 발견하면 바로 결제해줘.")
        val plan = bundle.proposal.graphPlan!!
        assertThat(plan.nodes.map { it.nodeType }).contains("schedule.trigger", "flight.search.mock", "condition.branch", "human.approval", "tool.unresolved")
        assertThat(plan.nodes.single { it.nodeType == "schedule.trigger" }.config["cron"]).isNotEqualTo("*/1 * * * * *")
        assertThat(bundle.requirement.unresolvedQuestions).anyMatch { it.key == "payment-connector" }
    }

    @Test fun `semantic variations keep the same safe intent classes without FAQ fallback`() {
        val csv = compile("엑셀에서 내보낸 CSV 두 표의 차이와 바뀐 행을 정확히 비교해 표로 보여줘.")
        assertThat(csv.proposal.graphPlan!!.nodes.map { it.nodeType }).contains("data.csv.compare").doesNotContain("ai.generate", "human.approval", "notion.search.mock")

        val news = compile("매일 8시에 AI 기사를 세 건 요약하고 내 확인을 받은 뒤 슬랙 채널로 보내줘.")
        assertThat(news.proposal.graphPlan!!.nodes.map { it.nodeType }).contains("schedule.trigger", "human.approval", "slack.send.mock")

        val github = compile("깃허브 이슈가 생기면 버그·기능 요청·질문으로 분류하고 버그만 재현 순서를 작성해줘.")
        assertThat(github.proposal.graphPlan!!.nodes.map { it.nodeType }).contains("github.issue.mock", "condition.branch").doesNotContain("notion.search.mock")
    }

    @Test fun `development mode does not apply keyword-specific graph rewrites to model output`() {
        val faq = compileForDevelopment("FAQ를 검색해서 고객 문의 답변을 만들고 근거가 없으면 담당자 확인이 필요하다고 알려주는 에이전트")
        assertThat(faq.proposal.graphPlan!!.nodes.map { it.nodeType }).contains("ai.generate")
            .doesNotContain("knowledge.search.mock", "condition.branch")

        val csv = compileForDevelopment("CSV 파일 두 개를 비교해서 추가 삭제 수정 행을 정확히 알려주는 에이전트")
        assertThat(csv.proposal.graphPlan!!.nodes.map { it.nodeType }).contains("ai.generate").doesNotContain("data.csv.compare")
        assertThat(csv.agentDefinitions).isNotEmpty

        val unsupported = compileForDevelopment("PeopleMagic에서 휴가 잔여일을 조회하는 에이전트")
        assertThat(unsupported.proposal.graphPlan!!.nodes.map { it.nodeType }).contains("ai.generate")
            .doesNotContain("tool.unresolved", "knowledge.search.mock")
    }

    @Test fun `develop wrapper is model context only and never becomes runtime sample input`() {
        val request = "두 개의 CSV 파일을 ID 기준으로 비교해서 추가·삭제·수정된 행을 표로 만들어주는 에이전트"
        val bundle = pipeline.generateDesign(
            PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
            agentDevelopmentPrompt(request),
            StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
            userInstruction = request,
        )

        assertThat(bundle.requirement.objective).isEqualTo(request)
        assertThat(bundle.proposal.agentDesign!!.simulationScenarios.single().input.values.joinToString(" "))
            .contains(request)
            .doesNotContain("다음 요청은 업무 자동화 배치가 아니라")
    }

    private fun compile(instruction: String) = pipeline.generateDesign(
        PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), instruction,
    )

    private fun compileForDevelopment(instruction: String) = pipeline.generateDesign(
        PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
        instruction,
        StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
    )
}
