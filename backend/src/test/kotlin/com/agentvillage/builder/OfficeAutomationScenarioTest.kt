package com.agentvillage.builder

import com.agentvillage.builder.application.*
import com.agentvillage.builder.domain.*
import com.agentvillage.builder.infrastructure.MetaAgentRunRepository
import com.agentvillage.common.exception.BadRequestException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

class OfficeAutomationScenarioTest {
    private val mapper = jacksonObjectMapper()
    private val catalog = WorkflowNodeCatalog()
    private val validator = WorkflowGraphValidator(catalog, mapper)
    private val renderer = HarnessPackageRenderer(mapper)
    private val pipeline = run {
        val runs = mock<MetaAgentRunRepository>()
        whenever(runs.save(any())).thenAnswer { it.arguments[0] }
        StructuredMetaAgentPipeline(
            DeterministicMockMetaAgentModel(mapper), mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>(),
        )
    }

    @Test
    fun `supported office scenarios compile validate package and simulate continuously`() {
        val fixture = javaClass.getResourceAsStream("/builder/office-automation-fixtures.json")!!.use { mapper.readTree(it) }
        val scenarios = listOf(
            "회의 텍스트를 사용자가 입력해 수동으로 시작하고 핵심 결정 사항, 담당자, 기한, 미결 이슈를 정리한 뒤 담당자 승인 후 화면에 표시해줘." to fixture["meetingTranscript"].asText(),
            "기존 자료를 사용자가 입력해 수동으로 시작하고 근거 수치를 보존한 보고서 초안을 작성한 뒤 담당자 승인 후 화면에 표시해줘." to fixture["reportSources"].asText(),
            "캠페인 브리프를 사용자가 입력해 수동으로 시작하고 블로그, SNS, 광고용 콘텐츠 문구로 변형한 뒤 담당자 승인 후 화면에 표시해줘." to fixture["campaignBrief"].asText(),
            "주간 성과 지표를 사용자가 입력해 수동으로 시작하고 이상 지점과 인사이트 보고서를 작성한 뒤 담당자 승인 후 화면에 표시해줘." to fixture["weeklyMetrics"].asText(),
            "직무기술서와 지원서를 사용자가 입력해 수동으로 시작하고 필수 요건 기준으로 지원서를 1차 분류한 뒤 채용 담당자 승인 후 화면에 표시해줘." to listOf(fixture["jobDescription"].asText(), fixture["resumeA"].asText(), fixture["resumeB"].asText()).joinToString("\n"),
            "고객 미팅 내용과 과거 제안서를 사용자가 입력해 수동으로 시작하고 맞춤형 제안서 초안을 작성한 뒤 영업 담당자 승인 후 화면에 표시해줘." to listOf(fixture["salesMeeting"].asText(), fixture["pastProposal"].asText()).joinToString("\n"),
        )

        scenarios.forEach { (instruction, input) ->
            val bundle = pipeline.generateDesign(context(), instruction)
            assertThat(bundle.clarificationQuestions).isEmpty()
            assertThat(bundle.agentDefinitions).hasSizeBetween(1, 2)
            assertThat(bundle.proposal.economics?.estimatedAiCallsPerRun).isBetween(1, 2)
            val graph = graph(bundle)
            assertThat(validator.validate(graph, bundle.requirement, bundle.proposal, bundle.agentDefinitions, instruction).valid).isTrue()
            val files = renderer.render(bundle)
            assertThat(files.keys).contains("workflow.json", "templates/output-template.json", "schemas/final-output.schema.json", "policies/quality-rules.json")
            var value: Map<String, Any?> = mapOf("text" to input)
            graph.nodes.forEach { node ->
                val contract = catalog.require(node.nodeType)
                assertThat(contract.validateConfig(node.config)).isEmpty()
                value = contract.simulate(node.config, value).output
            }
        }
    }

    @Test
    fun `missing audio and CRM connectors are explicit instead of fake designs`() {
        assertThatThrownBy {
            pipeline.generateDesign(context(), "미팅 녹음 파일을 업로드해 텍스트로 변환하고 회의록을 만든 뒤 담당자 승인 후 화면에 표시해줘.")
        }.isInstanceOf(BadRequestException::class.java).hasMessageContaining("음성·녹음 파일 전사")

        assertThatThrownBy {
            pipeline.generateDesign(context(), "고객 미팅 내용을 사용해 제안서 초안을 만들고 승인 후 CRM 상담 일지에 자동 반영해줘.")
        }.isInstanceOf(BadRequestException::class.java).hasMessageContaining("CRM 쓰기")
    }

    @Test
    fun `realistic fixture set contains evidence for every office scenario`() {
        val fixture = javaClass.getResourceAsStream("/builder/office-automation-fixtures.json")!!.use { mapper.readTree(it) }
        assertThat(fixture.fieldNames().asSequence().toList()).containsExactlyInAnyOrder(
            "meetingTranscript", "reportSources", "campaignBrief", "weeklyMetrics", "jobDescription",
            "resumeA", "resumeB", "salesMeeting", "pastProposal",
        )
        assertThat(fixture["weeklyMetrics"].asText()).contains("2026-W34", "410")
    }

    private fun context() = PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    private fun graph(bundle: MetaAgentDesignBundle): WorkflowGraph {
        val plan = requireNotNull(bundle.proposal.graphPlan)
        return WorkflowGraph(
            workflowId = UUID.randomUUID(), entryNodeId = plan.entryNodeId,
            nodes = plan.nodes.mapIndexed { index, node -> WorkflowNode(node.id, node.nodeType, node.label, NodePosition(index * 240.0, 100.0), node.config) },
            edges = plan.edges.map { WorkflowEdge(it.id, it.source, it.target, it.condition) },
        )
    }
}
