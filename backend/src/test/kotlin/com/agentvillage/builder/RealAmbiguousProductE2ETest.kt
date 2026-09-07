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
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@EnabledIfEnvironmentVariable(named = "REAL_AMBIGUOUS_PRODUCT_E2E", matches = "true")
class RealAmbiguousProductE2ETest {
    private val mapper = jacksonObjectMapper()

    data class Problem(
        val id: String,
        val category: String,
        val vagueRequest: String,
        val clarificationAnswer: String,
        val expected: AgentDevelopmentApproach,
    )

    data class Result(
        val id: String,
        val category: String,
        val expected: String,
        val actual: String,
        val passed: Boolean,
        val questionCount: Int,
        val durationMs: Long,
        val agentCount: Int = 0,
        val packageDownloaded: Boolean = false,
        val runnerStatus: String? = null,
        val issues: List<String> = emptyList(),
    )

    @Test
    fun `one hundred vague paid-product journeys finish with a prompt or a downloaded runnable package`() {
        val modelName = System.getenv("REAL_CODEX_MODEL") ?: "gpt-5.6-luna"
        val command = System.getenv("REAL_CODEX_COMMAND") ?: requireNotNull(findCodex()) { "codex command not found" }
        val codexHome = System.getenv("REAL_CODEX_HOME") ?: Path.of(System.getProperty("user.home"), ".codex").toString()
        val concurrency = (System.getenv("REAL_META_AGENT_CONCURRENCY") ?: "4").toInt().coerceIn(1, 6)
        val limit = (System.getenv("REAL_META_AGENT_CASE_LIMIT") ?: "100").toInt().coerceIn(1, 100)
        val selected = System.getenv("REAL_META_AGENT_CASE_IDS")?.split(',')?.map(String::trim)?.filter(String::isNotBlank)?.toSet().orEmpty()
        val executePackages = (System.getenv("REAL_EXECUTE_PACKAGES") ?: "true").toBoolean()
        val resumePassed = (System.getenv("REAL_META_AGENT_RESUME") ?: "false").toBoolean()
        val replayFailedPackages = (System.getenv("REAL_META_AGENT_REPLAY_FAILED_PACKAGES") ?: "false").toBoolean()
        val runtimePython = if (executePackages) prepareRuntime() else null
        val runner = CodexCliRunner(command, 180, codexHome)
        require(runner.hasSharedAuth()) { "shared Codex auth is missing: $codexHome/auth.json" }
        val credentials = mock<CredentialDirectory>()
        val model = CodexCliMetaAgentModel(credentials, runner, mapper, modelName)
        val runs = mock<MetaAgentRunRepository>().also { whenever(it.save(any())).thenAnswer { call -> call.arguments[0] } }
        val pipeline = StructuredMetaAgentPipeline(model, mapper, MetaAgentAuditService(runs), mock<BuilderJobProgressService>())
        val catalog = WorkflowNodeCatalog()
        val validator = WorkflowGraphValidator(catalog, mapper)
        val translator = WorkflowGraphTranslator(catalog)
        val renderer = HarnessPackageRenderer(mapper)
        val problems = problems().filter { selected.isEmpty() || it.id in selected }.take(limit)
        if (selected.isEmpty() && limit == 100) require(problems.size == 100) { "The paid-product corpus must contain exactly 100 journeys" }
        val started = Instant.now()
        val pool = Executors.newFixedThreadPool(concurrency)
        val results = try {
            pool.invokeAll(problems.map { problem -> Callable {
                val resumed = if (resumePassed) previouslyPassed(problem) else null
                val replayed = if (resumed == null && replayFailedPackages) {
                    replayFailedPackage(problem, pipeline, renderer, runtimePython, command, modelName, codexHome)
                } else null
                resumed ?: replayed ?: evaluate(problem, pipeline, validator, translator, renderer, runtimePython, command, modelName, codexHome)
            }}).map { it.get() }
        } finally {
            pool.shutdownNow()
        }
        val passed = results.count(Result::passed)
        val report = mapOf(
            "mode" to "real-ambiguous-paid-product-e2e",
            "model" to modelName,
            "total" to results.size,
            "passed" to passed,
            "failed" to results.size - passed,
            "passRate" to passed.toDouble() / results.size,
            "promptOnlyCount" to results.count { it.actual == AgentDevelopmentApproach.PROMPT_ONLY.name },
            "downloadedPackageCount" to results.count(Result::packageDownloaded),
            "runnerSucceededCount" to results.count { it.runnerStatus == "SUCCEEDED" },
            "durationSeconds" to Duration.between(started, Instant.now()).seconds,
            "results" to results,
        )
        val target = Path.of("build/reports/real-ambiguous-product-e2e.json")
        Files.createDirectories(target.parent)
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), report)
        assertThat(passed).withFailMessage("Paid-product E2E pass rate was $passed/${results.size}; see $target")
            .isEqualTo(results.size)
    }

    private fun evaluate(
        problem: Problem,
        pipeline: StructuredMetaAgentPipeline,
        validator: WorkflowGraphValidator,
        translator: WorkflowGraphTranslator,
        renderer: HarnessPackageRenderer,
        runtimePython: Path?,
        codexCommand: String,
        modelName: String,
        codexHome: String,
    ): Result {
        val started = System.nanoTime()
        return runCatching {
            val context = PipelineContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
            var cumulative = problem.vagueRequest
            var remaining = AgentDevelopmentProblemPolicy.MAX_CLARIFICATION_QUESTIONS
            var definition = modelCall { pipeline.defineAgentDevelopmentProblem(context, cumulative, remaining) }
            if (definition.recommendedApproach != AgentDevelopmentApproach.CLARIFY) {
                return@runCatching failed(problem, definition, 0, started, "AMBIGUOUS_REQUEST_NOT_CLARIFIED:${definition.rationale}")
            }
            var asked = 0
            var rounds = 0
            while (definition.recommendedApproach == AgentDevelopmentApproach.CLARIFY && remaining > 0) {
                rounds += 1
                asked += definition.clarificationQuestions.size
                remaining = AgentDevelopmentProblemPolicy.remainingQuestions(asked, rounds)
                val routeContext = if (problem.expected == AgentDevelopmentApproach.AGENT_TEAM) {
                    "대상 사용자는 나와 우리 팀이고 반복 사용한다. 입력은 사용자가 대화에서 직접 제공한다. 분석과 독립 검수를 분리하고 결과는 대화 화면에서 받는다. 외부 전송은 하지 않는다."
                } else {
                    "대상 사용자는 나 자신이고 이번 한 번만 처리한다. 도구, 외부 연동, 반복 실행, 상태 저장, 독립 검수는 필요 없다. 결과 텍스트만 받으면 된다."
                }
                cumulative += "\n추가 답변: ${problem.clarificationAnswer} $routeContext"
                definition = modelCall { pipeline.defineAgentDevelopmentProblem(context, cumulative, remaining) }
            }
            if (asked > AgentDevelopmentProblemPolicy.MAX_CLARIFICATION_QUESTIONS) {
                return@runCatching failed(problem, definition, asked, started, "QUESTION_LIMIT_EXCEEDED")
            }
            if (definition.recommendedApproach != problem.expected) {
                return@runCatching failed(problem, definition, asked, started, "ROUTE_MISMATCH:${definition.rationale}")
            }
            if (definition.recommendedApproach == AgentDevelopmentApproach.PROMPT_ONLY) {
                val ok = definition.suggestedPrompt.isNotBlank()
                return@runCatching Result(
                    problem.id, problem.category, problem.expected.name, definition.recommendedApproach.name,
                    ok, asked, elapsed(started), issues = if (ok) emptyList() else listOf("PROMPT_MISSING"),
                ).also(::record)
            }

            lateinit var bundle: MetaAgentDesignBundle
            lateinit var validation: WorkflowValidationResult
            while (true) {
                bundle = modelCall { pipeline.generateDesign(
                    context,
                    agentDevelopmentPrompt(definition.designBrief(cumulative)),
                    StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
                    userInstruction = cumulative,
                ) }
                validation = validateGeneratedDesign(translator, validator, context.workflowId, bundle, cumulative)
                repeat(2) {
                    if (validation.valid) return@repeat
                    bundle = modelCall { pipeline.generateDesign(
                        context,
                        agentDevelopmentPrompt(definition.designBrief(cumulative)),
                        StructuredMetaAgentPipeline.DesignMode.AGENT_DEVELOPMENT,
                        validationFeedback = validation.issues,
                        previousBundle = bundle,
                        userInstruction = cumulative,
                    ) }
                    validation = validateGeneratedDesign(translator, validator, context.workflowId, bundle, cumulative)
                }
                if (validation.valid) break
                val fallback = AgentDevelopmentProblemPolicy.semanticFallback(validation.issues)
                if (fallback.isNotEmpty() && rounds < AgentDevelopmentProblemPolicy.MAX_CLARIFICATION_ROUNDS && asked + fallback.size <= AgentDevelopmentProblemPolicy.MAX_CLARIFICATION_QUESTIONS) {
                    rounds += 1
                    asked += fallback.size
                    cumulative += "\n설계 확인 답변: ${problem.clarificationAnswer}"
                    continue
                }
                recordInvalidBundle(problem.id, bundle, validation)
                return@runCatching Result(
                    problem.id, problem.category, problem.expected.name, "INVALID_DESIGN", false, asked,
                    elapsed(started), bundle.agentDefinitions.size, issues = validation.issues.map { it.code },
                ).also(::record)
            }
            val packageRoot = writePackage(problem.id, renderer.render(bundle))
            val zip = zipAndVerify(packageRoot, bundle)
            val execution = if (runtimePython == null) RunnerResult("SKIPPED", emptyList())
                else executePackage(runtimePython, packageRoot, codexCommand, modelName, codexHome)
            val passed = zip && execution.status == "SUCCEEDED" && bundle.agentDefinitions.isNotEmpty()
            Result(
                problem.id, problem.category, problem.expected.name, definition.recommendedApproach.name,
                passed, asked, elapsed(started), bundle.agentDefinitions.size, zip, execution.status, execution.issues,
            ).also(::record)
        }.getOrElse { error ->
            Result(
                problem.id, problem.category, problem.expected.name, "ERROR", false, 0, elapsed(started),
                issues = listOf("${error::class.simpleName}:${error.message}"),
            ).also(::record)
        }
    }

    private data class RunnerResult(val status: String, val issues: List<String>)

    private fun validateGeneratedDesign(
        translator: WorkflowGraphTranslator,
        validator: WorkflowGraphValidator,
        workflowId: UUID,
        bundle: MetaAgentDesignBundle,
        sourceInstruction: String,
    ): WorkflowValidationResult = runCatching {
        validator.validate(
            translator.translate(workflowId, bundle.proposal),
            bundle.requirement,
            bundle.proposal,
            bundle.agentDefinitions,
            sourceInstruction,
        )
    }.getOrElse { error ->
        WorkflowValidationResult(
            valid = false,
            graphHash = "",
            issues = listOf(ValidationIssue("WORKFLOW_GRAPH_TRANSLATION_FAILED", error.message ?: "그래프 변환에 실패했습니다.")),
        )
    }

    private fun recordInvalidBundle(id: String, bundle: MetaAgentDesignBundle, validation: WorkflowValidationResult) {
        val target = Path.of("build/reports/real-ambiguous-product-progress", "$id-invalid-bundle.json")
        Files.createDirectories(target.parent)
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), mapOf("bundle" to bundle, "validation" to validation))
    }

    private fun <T> modelCall(block: () -> T): T {
        var last: ApiException? = null
        repeat(2) {
            try {
                return block()
            } catch (error: ApiException) {
                if (error.code !in RETRYABLE_MODEL_CODES) throw error
                last = error
            }
        }
        throw requireNotNull(last)
    }

    private fun executePackage(runtimePython: Path, packageRoot: Path, codexCommand: String, modelName: String, codexHome: String): RunnerResult {
        val output = packageRoot.resolve("runner-output.json")
        val process = ProcessBuilder(runtimePython.toString(), "runners/python/runner.py")
            .directory(packageRoot.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .apply {
                environment()["AGENTOWN_CODEX_COMMAND"] = codexCommand
                environment()["AGENTOWN_CODEX_MODEL"] = modelName
                environment()["CODEX_HOME"] = codexHome
            }
            .start()
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return RunnerResult("TIMEOUT", listOf("RUNNER_TIMEOUT"))
        }
        val text = Files.readString(output)
        val json = runCatching { mapper.readTree(text) }.getOrNull()
        val status = json?.path("status")?.asText()?.takeIf(String::isNotBlank) ?: "INVALID_OUTPUT"
        val issues = buildList {
            if (process.exitValue() != 0) add("RUNNER_EXIT_${process.exitValue()}")
            json?.path("code")?.asText()?.takeIf(String::isNotBlank)?.let(::add)
            if (json == null) add(text.takeLast(1000))
        }
        return RunnerResult(status, issues)
    }

    private fun prepareRuntime(): Path {
        val working = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        val repository = if (Files.isDirectory(working.resolve("core-runtime"))) working else requireNotNull(working.parent)
        val pythonCommand = listOf(Path.of("/usr/local/bin/python3.11"), Path.of("/opt/homebrew/bin/python3.11"))
            .firstOrNull(Files::isExecutable)?.toString() ?: "python3"
        val venv = Path.of("build/reports/real-ambiguous-product-venv-py311").toAbsolutePath()
        val python = venv.resolve("bin/python")
        val installed = venv.resolve(".runtime-installed")
        if (Files.isExecutable(python) && Files.isRegularFile(installed)) return python
        Files.createDirectories(venv.parent)
        require(runProcess(listOf(pythonCommand, "-m", "venv", venv.toString()), Path.of("."), 120) == 0) { "venv creation failed" }
        require(runProcess(listOf(venv.resolve("bin/pip").toString(), "install", repository.resolve("core-runtime").toString()), repository, 900) == 0) { "runtime install failed" }
        Files.writeString(installed, "ok\n")
        return python
    }

    private fun runProcess(command: List<String>, cwd: Path, timeoutSeconds: Long): Int {
        val log = Path.of("build/reports/real-ambiguous-runtime-install.log")
        val process = ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) process.destroyForcibly()
        return if (process.isAlive) -1 else process.exitValue()
    }

    private fun writePackage(id: String, files: Map<String, String>): Path {
        val root = Path.of("build/reports/real-ambiguous-product-packages", id).toAbsolutePath()
        files.forEach { (relative, content) ->
            val target = root.resolve(relative)
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }
        return root
    }

    private fun zipAndVerify(root: Path, bundle: MetaAgentDesignBundle): Boolean {
        val zipPath = root.resolveSibling("${root.fileName}.zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zip ->
            Files.walk(root).filter(Files::isRegularFile).forEach { file ->
                zip.putNextEntry(ZipEntry(root.relativize(file).toString()))
                Files.copy(file, zip)
                zip.closeEntry()
            }
        }
        val expected = buildSet {
            addAll(listOf("manifest.json", "workflow.json", "runtime-definition.json", "runners/python/runner.py"))
            bundle.agentDefinitions.forEach { add("agents/${it.key}.md") }
        }
        ZipFile(zipPath.toFile()).use { zip ->
            val actual = zip.entries().asSequence().map { it.name }.toSet()
            return Files.size(zipPath) > 0 && actual.containsAll(expected)
        }
    }

    private fun failed(problem: Problem, definition: AgentDevelopmentProblemDefinition, asked: Int, started: Long, issue: String) =
        Result(problem.id, problem.category, problem.expected.name, definition.recommendedApproach.name, false, asked, elapsed(started), issues = listOf(issue)).also(::record)

    private fun record(result: Result) {
        val target = Path.of("build/reports/real-ambiguous-product-progress", "${result.id}.json")
        Files.createDirectories(target.parent)
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), result)
    }

    private fun previouslyPassed(problem: Problem): Result? {
        val target = Path.of("build/reports/real-ambiguous-product-progress", "${problem.id}.json")
        if (!Files.isRegularFile(target)) return null
        return runCatching { mapper.readValue(target.toFile(), Result::class.java) }.getOrNull()
            ?.takeIf { it.passed && it.expected == problem.expected.name }
            ?.copy(actual = problem.expected.name)
    }

    private fun replayFailedPackage(
        problem: Problem,
        pipeline: StructuredMetaAgentPipeline,
        renderer: HarnessPackageRenderer,
        runtimePython: Path?,
        codexCommand: String,
        modelName: String,
        codexHome: String,
    ): Result? {
        val resultPath = Path.of("build/reports/real-ambiguous-product-progress", "${problem.id}.json")
        val bundlePath = Path.of("build/reports/real-ambiguous-product-packages", problem.id, "design-bundle.json")
        if (!Files.isRegularFile(resultPath) || !Files.isRegularFile(bundlePath)) return null
        val previous = runCatching { mapper.readValue(resultPath.toFile(), Result::class.java) }.getOrNull()
            ?.takeIf { !it.passed && it.expected == problem.expected.name } ?: return null
        val bundle = runCatching { mapper.readValue(bundlePath.toFile(), MetaAgentDesignBundle::class.java) }.getOrNull()
            ?.let(pipeline::normalizeBoundAgentSchemas)
            ?.let { normalized -> normalized.copy(proposal = normalized.proposal.copy(
                graphPlan = normalized.proposal.graphPlan?.let(WorkflowGraphPlanNormalizer::normalize),
            )) } ?: return null
        val started = System.nanoTime()
        val packageRoot = writePackage(problem.id, renderer.render(bundle))
        val zip = zipAndVerify(packageRoot, bundle)
        val execution = if (runtimePython == null) RunnerResult("SKIPPED", emptyList())
            else executePackage(runtimePython, packageRoot, codexCommand, modelName, codexHome)
        return Result(
            problem.id, problem.category, problem.expected.name, problem.expected.name,
            zip && execution.status == "SUCCEEDED" && bundle.agentDefinitions.isNotEmpty(),
            previous.questionCount, elapsed(started), bundle.agentDefinitions.size, zip, execution.status, execution.issues,
        ).also(::record)
    }

    private fun problems(): List<Problem> {
        data class Base(val key: String, val category: String, val vague: String, val answer: String, val promptOnly: Boolean = false)
        val bases = listOf(
            Base("meeting", "회의 혼란", "회의하고 나면 맨날 엉망이야", "매주 반복 사용하며 회의록에서 결정사항, 담당자, 기한, 미결 이슈를 추출하고 별도 검수 역할이 근거 누락을 확인하게 해줘."),
            Base("feedback", "고객 반응", "고객들이 뭐라는지 모르겠어", "매일 고객 피드백 여러 건을 분석 담당들이 나눠 보고 반복 문제와 제품 기회를 집계 담당이 근거와 함께 정리하게 해줘."),
            Base("contract", "계약 위험", "계약서가 좀 무서워", "계약서 여러 건에서 위험 조항과 근거 문장을 독립 분석하고 검수 담당이 누락과 추측을 확인하는 반복용 팀이 필요해."),
            Base("proposal", "제안서", "제안서 좀 어떻게 안 되나", "고객 브리프를 분석, 초안 작성, 독립 검수로 나눠 반복 사용하고 목표, 범위, 일정, 근거가 포함된 제안서를 만들고 싶어."),
            Base("research", "자료 조사", "뭘 좀 조사해야 하는데 막막해", "여러 자료를 병렬 조사한 뒤 출처를 검증하고 공통점, 차이점, 기회를 집계하는 반복 가능한 팀을 만들어줘."),
            Base("interview", "인터뷰", "인터뷰는 많이 했는데 모르겠어", "고객 인터뷰를 건별로 독립 분석하고 반복 문제와 제품 기회를 근거 인용과 함께 합치는 팀으로 매번 쓰고 싶어."),
            Base("shoe", "신발 기획", "신발 만들고 싶어", "반복해서 신발을 기획할 거야. 사용자 불편 조사, 제품 요구사항, 소재와 착화 제약 검토, 독립 검수를 나눠 근거가 포함된 제품 기획서를 만들고 싶어."),
            Base("incident", "장애", "장애 나면 다 정신없어", "장애 기록을 원인, 영향, 대응, 재발 방지로 분석하고 독립 검수 후 보고서로 합치는 반복용 에이전트 팀이 필요해."),
            Base("hiring", "채용", "지원자가 너무 많아", "지원서를 독립 평가하되 내가 제공한 기준만 사용하고 검수 담당이 근거 누락을 확인한 뒤 비교표로 집계하는 팀이 필요해."),
            Base("sales", "영업", "영업 인수인계가 계속 새어", "여러 지점 영업 기록을 따로 분석하고 고객 요구, 위험, 후속 행동을 검수해 하나의 인수인계표로 합치는 반복 팀을 원해."),
            Base("app", "앱 기획", "앱 만들고 싶어", "팀에서 반복 사용할 앱 기획 도구가 필요해. 사용자 문제 분석, 기능 요구사항, 화면 흐름, 기술·보안 제약, 독립 검수를 나눠 근거 있는 앱 기획서를 만들고 싶어."),
            Base("notepad", "메모장 기획", "메모장 만들고 싶어", "업무용 메모장 앱을 반복 기획·개선할 거야. 사용자 시나리오, 기능 우선순위, 데이터 저장과 동기화 제약, 검수를 분리해 구현 가능한 기획서와 테스트 조건을 만들고 싶어."),
            Base("content", "콘텐츠", "글을 계속 써야 해서 힘들어", "자료 분석, 콘텐츠 기획, 초안 작성, 팩트 검수를 분리해 근거 기반 글을 반복 제작하는 팀을 만들고 싶어."),
            Base("product", "제품 기획", "나 컵 만들고 싶다", "텀블러 제품을 반복 기획할 거야. 사용자 조사, 요구사항 정리, 소재와 제조 제약 검토, 독립 검수를 분리해 제품 요구사항을 만들고 싶어."),
            Base("travel", "여행 계획", "여행 가고 싶은데 모르겠어", "여러 후보를 예산, 이동, 일정 기준으로 독립 조사하고 근거를 검수해 비교 일정으로 합치는 반복용 팀이 필요해."),
            Base("release", "배포 검토", "배포할 때마다 겁나", "변경 분석, 회귀 검증, 배포 체크리스트 검수를 분리하되 실제 배포는 하지 않고 반복 사용 가능한 검토 패키지를 원해."),
            Base("translate", "번역", "이 영어 좀 어떻게 해줘", "아래 영어 문서를 고유명사와 의미를 보존해 자연스러운 한국어로 번역하고 번역문만 출력해줘.", true),
            Base("proofread", "문장 교정", "이 문장 좀 이상해", "아래 한국어 문장의 의미는 유지하고 맞춤법과 어색한 표현만 고쳐 최종 문장만 출력해줘.", true),
            Base("summarize", "짧은 요약", "이거 너무 길어", "아래 글을 핵심 사실을 빠뜨리지 않는 세 문장으로 요약하고 요약문만 출력해줘.", true),
            Base("email", "이메일 문장", "메일 뭐라고 쓰지", "아래 내용을 정중하고 간결한 한국어 업무 이메일 한 통으로 작성하고 제목과 본문만 출력해줘.", true),
        )
        val vagueVariants = listOf("", " 진짜 답답해", " 뭐부터 해야 해?", " 이것 좀 해결하고 싶어", " 좋은 방법 없나")
        return bases.flatMapIndexed { baseIndex, base ->
            vagueVariants.mapIndexed { variantIndex, suffix ->
                Problem(
                    id = "AP${(baseIndex * 5 + variantIndex + 1).toString().padStart(3, '0')}",
                    category = base.category,
                    vagueRequest = base.vague + suffix,
                    clarificationAnswer = base.answer,
                    expected = if (base.promptOnly) AgentDevelopmentApproach.PROMPT_ONLY else AgentDevelopmentApproach.AGENT_TEAM,
                )
            }
        }
    }

    private fun elapsed(started: Long) = (System.nanoTime() - started) / 1_000_000
    private fun findCodex() = listOf(
        Path.of(System.getProperty("user.home"), ".local", "bin", "codex"),
        Path.of("/usr/local/bin/codex"),
        Path.of("/opt/homebrew/bin/codex"),
    ).firstOrNull(Files::isExecutable)?.toString()

    private companion object {
        val RETRYABLE_MODEL_CODES = setOf(
            "BUILDER_CODEX_TIMEOUT",
            "BUILDER_CODEX_EMPTY_OUTPUT",
            "BUILDER_CODEX_EXEC_FAILED",
            "BUILDER_CODEX_START_FAILED",
        )
    }
}
