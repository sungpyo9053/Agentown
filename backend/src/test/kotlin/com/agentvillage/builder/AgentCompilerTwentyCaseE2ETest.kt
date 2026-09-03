package com.agentvillage.builder

import com.agentvillage.builder.application.*
import com.agentvillage.builder.infrastructure.MetaAgentRunRepository
import com.agentvillage.common.exception.BadRequestException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class AgentCompilerTwentyCaseE2ETest {
    @TempDir lateinit var root: Path
    private val mapper = jacksonObjectMapper()
    private val runs = mock<MetaAgentRunRepository>().also { whenever(it.save(any())).thenAnswer { call -> call.arguments[0] } }
    private val pipeline = StructuredMetaAgentPipeline(DeterministicMockMetaAgentModel(mapper), mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>())

    data class Case(
        val id: String,
        val group: String,
        val instruction: String,
        val expectedNodes: Set<String> = emptySet(),
        val forbiddenNodes: Set<String> = emptySet(),
        val rejected: Boolean = false,
    )

    @TestFactory
    fun `ten similar and ten distinct natural language requests complete compiler package E2E`() = cases().map { case ->
        DynamicTest.dynamicTest("${case.group}-${case.id}") {
            val result = runCatching {
                pipeline.generateDesign(
                    PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                    case.instruction,
                    StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
                )
            }
            if (case.rejected) {
                assertThat(result.exceptionOrNull()).isInstanceOf(BadRequestException::class.java)
                return@dynamicTest
            }

            val bundle = result.getOrThrow()
            val nodeTypes = bundle.proposal.graphPlan!!.nodes.map { it.nodeType }
            assertThat(nodeTypes).containsAll(case.expectedNodes)
            if (case.forbiddenNodes.isNotEmpty()) {
                assertThat(nodeTypes).doesNotContainAnyElementsOf(case.forbiddenNodes)
            }
            val files = HarnessPackageRenderer(mapper).render(bundle)
            assertThat(files.getValue("examples/sample-input.json")).doesNotContain("다음 요청은 업무 자동화 배치가 아니라")
            val directory = root.resolve(case.id)
            files.forEach { (relative, content) ->
                val target = directory.resolve(relative)
                Files.createDirectories(target.parent)
                Files.writeString(target, content)
            }
            val process = ProcessBuilder("python3", directory.resolve("runners/python/runner.py").toString(), "--approve")
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertThat(process.waitFor()).withFailMessage("${case.id}: $output").isZero()
            assertThat(output).contains("\"status\": \"SUCCEEDED\"", "\"externalCallPerformed\": false")
                .doesNotContain("다음 요청은 업무 자동화 배치가 아니라")
        }
    }

    private fun cases() = listOf(
        Case("S01", "SIMILAR", "FAQ를 찾아 고객 질문에 답하고 근거가 없으면 담당자 검토로 보내는 에이전트", setOf("knowledge.search.mock", "condition.branch", "ai.generate")),
        Case("S02", "SIMILAR", "고객 문의와 관련된 도움말을 검색해 답변 초안을 만들되 자료가 없으면 사람 확인이 필요하다고 표시해줘", setOf("knowledge.search.mock", "condition.branch", "ai.generate")),
        Case("S03", "SIMILAR", "FAQ 근거가 있을 때만 한국어 답변을 작성하고 무근거 질문은 담당자에게 넘겨줘", setOf("knowledge.search.mock", "condition.branch", "ai.generate")),
        Case("S04", "SIMILAR", "지원 문서 검색 결과로 고객 응답을 만들고 관련 문서가 없으면 답하지 않는 에이전트", setOf("knowledge.search.mock", "condition.branch", "ai.generate")),
        Case("S05", "SIMILAR", "고객 질문을 FAQ에서 조회하고 찾은 근거와 답변 또는 상담원 확인 필요 상태를 반환해줘", setOf("knowledge.search.mock", "condition.branch", "ai.generate")),
        Case("S06", "SIMILAR", "두 CSV를 ID 열로 비교해 추가 삭제 수정 행을 표로 보여줘", setOf("data.csv.compare", "template.render"), setOf("ai.generate")),
        Case("S07", "SIMILAR", "엑셀에서 내보낸 CSV 이전본과 최신본의 달라진 레코드를 키 기준으로 찾아줘", setOf("data.csv.compare"), setOf("ai.generate")),
        Case("S08", "SIMILAR", "CSV A와 CSV B 사이에 생긴 행 추가 제거 값 변경을 정확하게 비교해줘", setOf("data.csv.compare"), setOf("ai.generate")),
        Case("S09", "SIMILAR", "두 데이터 CSV 파일을 기본키 기준으로 diff해서 변경 목록을 만들어줘", setOf("data.csv.compare"), setOf("ai.generate")),
        Case("S10", "SIMILAR", "고객 목록 CSV 두 버전을 비교하고 ADDED REMOVED MODIFIED 결과를 출력해줘", setOf("data.csv.compare"), setOf("ai.generate")),
        Case("D01", "DISTINCT", "매일 오전 8시에 AI 뉴스 세 개를 요약하고 승인 후 Slack으로 보내줘", setOf("schedule.trigger", "news.search.mock", "human.approval")),
        Case("D02", "DISTINCT", "경쟁사 세 곳의 제품 발표와 가격을 병렬 조사해 비교 보고서로 합쳐줘", setOf("parallel.map.mock", "ai.generate")),
        Case("D03", "DISTINCT", "GitHub 이슈를 버그 기능 요청 질문으로 분류하고 버그 재현 절차를 써줘", setOf("github.issue.mock", "ai.classify", "condition.branch")),
        Case("D04", "DISTINCT", "항공권이 20만 원 이하면 결제 전 승인을 요청하는 모니터링 에이전트", setOf("flight.search.mock", "condition.branch", "human.approval")),
        Case("D05", "DISTINCT", "회의록에서 결정사항 담당자 기한 미결 이슈를 추출해 정리해줘", setOf("ai.generate")),
        Case("D06", "DISTINCT", "계약서에서 의무 기한 위험 문구와 확인 질문을 찾아 검토 초안을 작성해줘", setOf("ai.generate")),
        Case("D07", "DISTINCT", "설문 응답에서 반복 주제와 불편 사항 개선 요구를 분석해줘", setOf("ai.generate")),
        Case("D08", "DISTINCT", "영어 문서를 고유명사를 보존해 자연스러운 한국어로 번역해줘", setOf("ai.generate")),
        Case("D09", "DISTINCT", "PeopleMagic에서 휴가 잔여일을 조회해 알려줘", setOf("tool.unresolved"), setOf("knowledge.search.mock")),
        Case("D10", "DISTINCT", "백엔드 변경을 커밋하고 운영 배포까지 자동화해줘", rejected = true),
    )
}
