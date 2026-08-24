package com.agentvillage.builder

import com.agentvillage.builder.application.*
import com.agentvillage.builder.domain.*
import com.agentvillage.builder.infrastructure.MetaAgentRunRepository
import com.agentvillage.common.exception.ApiException
import com.agentvillage.llmcredential.application.CredentialDirectory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.ceil

@EnabledIfEnvironmentVariable(named = "REAL_EXECUTABLE_HARNESS_CORPUS", matches = "true")
class RealExecutableHarnessCorpusTest {
    private val mapper = jacksonObjectMapper()
    data class Scenario(val id: String, val category: String, val instruction: String, val minAgents: Int)
    data class Result(
        val id: String, val category: String, val passed: Boolean, val actual: String, val durationMs: Long,
        val agentCount: Int = 0, val nodeCount: Int = 0, val issues: List<String> = emptyList(),
        val errorCode: String? = null, val packagePath: String? = null,
    )

    @Test
    fun `at least eighty of one hundred executable requests produce runnable packages`() {
        val modelName = System.getenv("REAL_CODEX_MODEL") ?: "gpt-5.6-luna"
        val command = System.getenv("REAL_CODEX_COMMAND") ?: requireNotNull(findCodex()) { "codex command not found" }
        val codexHome = System.getenv("REAL_CODEX_HOME") ?: Path.of(System.getProperty("user.home"), ".codex").toString()
        val concurrency = (System.getenv("REAL_META_AGENT_CONCURRENCY") ?: "4").toInt().coerceIn(1, 8)
        val caseLimit = (System.getenv("REAL_META_AGENT_CASE_LIMIT") ?: "100").toInt().coerceIn(1, 100)
        val runner = CodexCliRunner(command, 180, codexHome)
        require(runner.hasSharedAuth()) { "shared Codex auth is missing: $codexHome/auth.json" }

        val credentials = mock<CredentialDirectory>()
        val limiter = mock<BuilderUsageLimiter>()
        whenever(limiter.isUnlimited(any())).thenReturn(true)
        val model = CodexCliMetaAgentModel(credentials, runner, limiter, mapper, modelName)
        val runs = mock<MetaAgentRunRepository>()
        whenever(runs.save(any())).thenAnswer { it.arguments[0] }
        val pipeline = StructuredMetaAgentPipeline(model, mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>())
        val catalog = WorkflowNodeCatalog()
        val validator = WorkflowGraphValidator(catalog, mapper)
        val renderer = HarnessPackageRenderer(mapper)
        val requestedIds = System.getenv("REAL_META_AGENT_CASE_IDS")?.split(',')?.map(String::trim)?.filter(String::isNotBlank)?.toSet().orEmpty()
        val scenarios = scenarios().filter { requestedIds.isEmpty() || it.id in requestedIds }.take(caseLimit)
        val started = Instant.now()
        val pool = Executors.newFixedThreadPool(concurrency)
        val results = try {
            pool.invokeAll(scenarios.map { scenario -> Callable { evaluate(scenario, pipeline, validator, catalog, renderer) } }).map { it.get() }
        } finally {
            pool.shutdownNow()
        }
        val passed = results.count(Result::passed)
        val report = mapOf(
            "mode" to "real-executable-harness-corpus", "model" to modelName, "total" to results.size,
            "passed" to passed, "failed" to results.size - passed, "passRate" to passed.toDouble() / results.size,
            "packageCount" to results.count { it.packagePath != null }, "threshold" to 0.80,
            "thresholdMet" to (passed >= ceil(results.size * 0.80).toInt()),
            "durationSeconds" to Duration.between(started, Instant.now()).seconds, "results" to results,
        )
        val target = Path.of("build/reports/real-executable-harness-corpus.json")
        Files.createDirectories(target.parent)
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), report)
        assertThat(passed).withFailMessage("Executable harness package pass rate was $passed/${results.size}; see $target")
            .isGreaterThanOrEqualTo(ceil(results.size * 0.80).toInt())
    }

    private fun evaluate(
        scenario: Scenario, pipeline: StructuredMetaAgentPipeline, validator: WorkflowGraphValidator,
        catalog: WorkflowNodeCatalog, renderer: HarnessPackageRenderer,
    ): Result {
        val started = System.nanoTime()
        val result = try {
            val bundle = pipeline.generateDesign(context(), scenario.instruction)
            if (bundle.clarificationQuestions.isNotEmpty()) {
                Result(scenario.id, scenario.category, false, "CLARIFY", elapsed(started), issues = bundle.clarificationQuestions.map { it.field })
            } else {
                val plan = bundle.proposal.graphPlan ?: return record(Result(scenario.id, scenario.category, false, "INVALID_GRAPH", elapsed(started), errorCode = "GRAPH_PLAN_MISSING"))
                val graph = WorkflowGraph(
                    workflowId = UUID.randomUUID(), entryNodeId = plan.entryNodeId,
                    nodes = plan.nodes.mapIndexed { index, node -> WorkflowNode(node.id, node.nodeType, node.label, NodePosition(index * 260.0, 100.0), node.config) },
                    edges = plan.edges.map { WorkflowEdge(it.id, it.source, it.target, it.condition) },
                )
                val validation = validator.validate(graph, bundle.requirement, bundle.proposal, bundle.agentDefinitions, scenario.instruction)
                val contractIssues = if (validation.valid) simulateContracts(graph, catalog) else emptyList()
                val agentCountValid = bundle.agentDefinitions.size >= scenario.minAgents
                if (!validation.valid || contractIssues.isNotEmpty() || !agentCountValid) {
                    Result(
                        scenario.id, scenario.category, false, "INVALID_GRAPH", elapsed(started), bundle.agentDefinitions.size,
                        graph.nodes.size, validation.issues.map { it.code } + contractIssues + if (agentCountValid) emptyList() else listOf("TOO_FEW_AGENTS"),
                    )
                } else {
                    val packagePath = writePackage(scenario.id, renderer.render(bundle))
                    val packageIssues = validatePackage(packagePath, bundle)
                    Result(
                        scenario.id, scenario.category, packageIssues.isEmpty(), if (packageIssues.isEmpty()) "DESIGN" else "INVALID_PACKAGE",
                        elapsed(started), bundle.agentDefinitions.size, graph.nodes.size, packageIssues, packagePath = packagePath,
                    )
                }
            }
        } catch (exception: ApiException) {
            Result(scenario.id, scenario.category, false, "API_ERROR", elapsed(started), errorCode = exception.code, issues = listOfNotNull(exception.message))
        } catch (exception: Exception) {
            Result(scenario.id, scenario.category, false, "ERROR", elapsed(started), errorCode = exception::class.simpleName, issues = listOfNotNull(exception.message))
        }
        return record(result)
    }

    private fun simulateContracts(graph: WorkflowGraph, catalog: WorkflowNodeCatalog): List<String> {
        val issues = mutableListOf<String>()
        val branchValues = graph.edges.mapNotNull { edge ->
            Regex("^([A-Za-z][A-Za-z0-9]*)=(true|false|[A-Za-z0-9_-]+)$").matchEntire(edge.condition)?.let { it.groupValues[1] to it.groupValues[2] }
        }.toMap()
        var input: Map<String, Any?> = mapOf("text" to "검증용 원문", "message" to "검증용 문의") + branchValues
        graph.nodes.forEach { node ->
            val contract = runCatching { catalog.require(node.nodeType) }.getOrElse { issues += "NODE_TYPE_NOT_ALLOWED"; return@forEach }
            issues += contract.validateConfig(node.config).map { "INVALID_NODE_CONFIG" }
            issues += contract.validateInput(input).map { "INVALID_NODE_INPUT" }
            runCatching { contract.simulate(node.config, input) }.onSuccess { input = it.output }.onFailure { issues += "SIMULATION_EXCEPTION" }
        }
        return issues
    }

    private fun validatePackage(root: String, bundle: MetaAgentDesignBundle): List<String> = buildList {
        listOf("AGENTS.md", "CODEX.md", "workflow.json", "design-bundle.json", "manifest.json").forEach {
            if (!Files.isRegularFile(Path.of(root, it))) add("PACKAGE_FILE_MISSING:$it")
        }
        bundle.agentDefinitions.forEach { if (!Files.isRegularFile(Path.of(root, "agents", "${it.key}.md"))) add("AGENT_MARKDOWN_MISSING:${it.key}") }
        bundle.guideDefinitions.forEach { if (!Files.isRegularFile(Path.of(root, "guides", "${it.key}.md"))) add("GUIDE_MARKDOWN_MISSING:${it.key}") }
    }

    private fun writePackage(caseId: String, files: Map<String, String>): String {
        val root = Path.of("build/reports/executable-harness-packages", caseId)
        files.forEach { (relative, content) ->
            val target = root.resolve(relative)
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }
        return root.toAbsolutePath().toString()
    }

    private fun record(result: Result): Result {
        val target = Path.of("build/reports/executable-corpus-progress", "${result.id}.json")
        Files.createDirectories(target.parent)
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), result)
        return result
    }

    private fun scenarios(): List<Scenario> {
        val bases = listOf(
            Triple("meeting", "회의록 정리", "회의록에서 결정사항, 담당자, 기한, 미결 이슈를 추출해 구조화된 요약을 작성"),
            Triple("feedback", "고객 피드백 분류", "고객 피드백을 칭찬, 문의, 불만으로 분류하고 판단 근거를 표시"),
            Triple("survey", "설문 분석", "설문 답변에서 반복 주제, 불편 사항, 개선 요구를 분석해 보고서를 작성"),
            Triple("resume", "이력서 요약", "이력서에서 경력, 기술, 성과, 확인 필요 항목을 추출해 요약"),
            Triple("contract", "계약 검토", "계약 조항에서 의무, 기한, 위험 표현, 확인 질문을 분석해 검토 초안을 작성"),
            Triple("invoice", "송장 추출", "송장 텍스트에서 공급자, 금액, 통화, 지급일을 추출해 구조화"),
            Triple("expense", "경비 분류", "경비 내역을 교통, 식비, 소프트웨어, 기타로 분류하고 합계 근거를 표시"),
            Triple("lead", "리드 판정", "상담 신청 내용을 고의도, 검토, 부적합 리드로 분류하고 근거를 작성"),
            Triple("sales-call", "영업 통화 요약", "영업 통화 기록에서 고객 요구, 예산, 일정, 반대 의견, 후속 조치를 요약"),
            Triple("proposal", "제안서 작성", "고객 브리프를 분석해 목표, 범위, 일정, 기대 결과가 포함된 제안서 초안을 작성"),
            Triple("job-post", "채용 공고 작성", "직무 요건을 바탕으로 역할, 책임, 필수 역량, 우대 조건이 포함된 채용 공고 초안을 작성"),
            Triple("incident", "장애 보고서", "장애 기록을 분석해 원인, 영향, 대응, 재발 방지 항목의 보고서 초안을 작성"),
            Triple("project", "프로젝트 보고", "프로젝트 업데이트에서 진행률, 완료 항목, 위험, 다음 행동을 분석해 상태 보고서를 작성"),
            Triple("review", "제품 리뷰 분류", "제품 리뷰를 긍정, 중립, 부정으로 분류하고 핵심 문장 근거를 표시"),
            Triple("translation", "문서 번역", "영어 원문을 의미와 고유명사를 보존해 자연스러운 한국어로 번역"),
            Triple("proofread", "문서 교정", "한국어 원문을 맞춤법, 문체, 명확성 기준으로 교정하고 변경 이유를 작성"),
            Triple("sop", "업무 매뉴얼", "작업 메모를 준비물, 실행 단계, 확인 기준, 예외 처리 순서의 SOP 초안으로 작성"),
            Triple("risk", "위험 등록부", "프로젝트 메모에서 위험, 발생 가능성, 영향, 대응책, 담당 항목을 분석해 목록 작성"),
            Triple("research", "자료 비교", "세 개의 입력 자료를 공통점, 차이점, 근거, 추가 확인 항목으로 비교 요약"),
            Triple("release", "릴리스 노트", "변경 목록을 사용자 영향, 새 기능, 수정 사항, 주의사항 구조의 릴리스 노트로 작성"),
        )
        val variants = listOf(
            "결과는 화면에 표시하고 승인 없이 종료해줘.",
            "결과는 담당자 승인 후 화면에 표시해줘.",
            "결과는 핵심 요약과 상세 근거로 나눠 화면에 표시하고 승인 없이 종료해줘.",
            "결과는 표 형식 항목과 설명으로 구성해 검토 담당자 승인 후 화면에 표시해줘.",
            "결과는 일반 직원이 이해할 수 있는 한국어로 작성해 화면에 표시하고 승인 없이 종료해줘.",
        )
        return bases.flatMapIndexed { baseIndex, (_, category, task) ->
            variants.mapIndexed { variantIndex, variant ->
                Scenario(
                    id = "EX${(baseIndex * variants.size + variantIndex + 1).toString().padStart(3, '0')}",
                    category = category,
                    instruction = "수동으로 사용자가 제공한 텍스트만 입력으로 사용해서 $task 해줘. 입력 밖의 사실은 만들지 말고 사용한 근거를 결과에 포함해. $variant",
                    minAgents = 1,
                )
            }
        }
    }

    private fun elapsed(started: Long) = (System.nanoTime() - started) / 1_000_000
    private fun context() = PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    private fun findCodex() = listOf(
        Path.of(System.getProperty("user.home"), ".local", "bin", "codex"), Path.of("/usr/local/bin/codex"), Path.of("/opt/homebrew/bin/codex"),
    ).firstOrNull(Files::isExecutable)?.toString()
}
