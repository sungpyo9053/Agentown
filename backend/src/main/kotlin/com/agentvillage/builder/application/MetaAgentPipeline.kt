package com.agentvillage.builder.application

import com.agentvillage.builder.domain.*
import com.agentvillage.common.exception.ApiException
import com.agentvillage.common.exception.BadRequestException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.UUID

interface MetaAgentModel {
    val executorName: String
    val modelName: String
    fun preflight(context: PipelineContext) = Unit
    fun generate(context: PipelineContext, stage: String, input: Map<String, Any?>): String
}

/** Transport DTO kept separate from the trusted domain model. */
private data class LlmMetaAgentDesignDto(
    val requirement: JsonNode,
    val clarificationQuestions: JsonNode,
    val proposal: JsonNode,
    val agentDefinitions: JsonNode,
    val guideDefinitions: JsonNode,
) {
    fun toDomain(mapper: ObjectMapper) = MetaAgentDesignBundle(
        mapper.treeToValue(requirement, AutomationRequirement::class.java),
        mapper.convertValue(clarificationQuestions, mapper.typeFactory.constructCollectionType(List::class.java, ClarificationQuestion::class.java)),
        mapper.treeToValue(proposal, AutomationProposal::class.java),
        mapper.convertValue(agentDefinitions, mapper.typeFactory.constructCollectionType(List::class.java, AgentDefinition::class.java)),
        mapper.convertValue(guideDefinitions, mapper.typeFactory.constructCollectionType(List::class.java, GuideDefinition::class.java)),
    )
}

@Component
@ConditionalOnProperty(name = ["builder.meta-agent.mode"], havingValue = "mock")
class DeterministicMockMetaAgentModel(private val mapper: ObjectMapper) : MetaAgentModel {
    override val executorName = "deterministic-test-mock"
    override val modelName = "mock"

    override fun generate(context: PipelineContext, stage: String, input: Map<String, Any?>): String {
        val instruction = input["instruction"]?.toString().orEmpty()
        val slack = instruction.contains("Slack", true) || instruction.contains("슬랙")
        val faq = instruction.contains("Notion", true) || instruction.contains("노션") || instruction.contains("FAQ", true)
        val bundle = if (slack && faq) faqBundle(instruction) else genericBundle(instruction)
        return mapper.writeValueAsString(bundle)
    }

    private fun faqBundle(instruction: String) = MetaAgentDesignBundle(
            requirement = AutomationRequirement(
                objective = instruction,
                trigger = "Slack 고객 문의 수신",
                inputs = listOf("고객 문의"), outputs = listOf("승인된 답변 초안"),
                steps = listOf("문의 수신", "Notion FAQ 검색", "AI 답변 초안", "담당자 승인", "Slack 스레드 답변"),
                decisions = listOf("관련 FAQ 선택", "담당자 승인 여부"), exceptions = listOf("FAQ 검색 결과 없음", "승인 거절"), humanApprovalRequired = true,
            ),
            clarificationQuestions = emptyList(),
            proposal = AutomationProposal(
                "Slack FAQ 답변 자동화",
                "Slack 문의를 FAQ 근거로 답변 초안화하고 승인 후 회신합니다.",
                listOf("문의 수신", "FAQ 검색", "답변 생성", "사람 승인", "답변 미리보기"),
                listOf("Slack Mock", "Notion Mock"),
                listOf("Slack 답변 직전"),
                "실패한 노드에서 중단하고 원인을 표시",
                faqGraphPlan(),
            ),
            agentDefinitions = listOf(
                AgentDefinition("faq-searcher", "FAQ 검색 담당", "고객 문의에서 검색어를 정리하고 Notion 검색 결과 중 관련 근거를 선택한다.", listOf(FieldDefinition("message", "string", true, "고객 문의")), listOf(FieldDefinition("notionResult", "string", true, "관련 FAQ 근거")), listOf("문의 의도를 보존한다", "가장 관련 높은 FAQ를 선택한다"), listOf("FAQ 내용을 변경하지 않는다", "검색 결과가 없으면 만들지 않는다"), listOf("선택한 FAQ 제목과 문장")),
                AgentDefinition("faq-answer-writer", "FAQ 답변 작성자", "Notion 검색 근거만 사용해 고객 답변 초안을 작성한다.", listOf(FieldDefinition("message", "string", true, "고객 문의"), FieldDefinition("notionResult", "string", true, "FAQ 검색 결과")), listOf(FieldDefinition("draft", "string", true, "답변 초안")), listOf("FAQ 근거를 우선한다", "불확실하면 명시한다"), listOf("근거 없는 정책을 만들지 않는다", "외부 전송을 수행하지 않는다"), listOf("사용한 FAQ 문장")),
            ),
            guideDefinitions = listOf(
                GuideDefinition("slack-mock", "Slack 연결 설정", "MVP에서는 실제 Slack 계정 대신 Mock 채널과 스레드를 사용합니다.", listOf(GuideField("channel", "문의 채널", "text", true, false, "예: #customer-support"), GuideField("approver", "승인자", "text", true, false, "답변을 검토할 담당자"))),
                GuideDefinition("notion-mock", "Notion FAQ 연결 설정", "MVP에서는 실제 Notion 계정 대신 Mock FAQ 결과를 사용합니다.", listOf(GuideField("database", "FAQ 데이터베이스", "text", true, false, "검색할 FAQ 데이터베이스 이름"))),
            ),
        )

    private fun genericBundle(instruction: String): MetaAgentDesignBundle {
        val normalized = instruction.lowercase()
        val classification = listOf("분류", "카테고리", "감성", "자격 판정", "우선순위").any(normalized::contains)
        val writing = listOf("글쓰기", "블로그", "원고", "콘텐츠 작성").any(normalized::contains)
        val multiAgent = listOf("분석 담당과", "작성 담당이", "검토 담당이").any(normalized::contains)
        val approval = (instruction.contains("승인") || instruction.contains("검토 후")) &&
            listOf("승인 없이", "검토 없이", "승인 불필요").none(instruction::contains)
        val agents = if (writing) {
            listOf(
                genericAgent("source-analyst", "자료 분석가", "입력 자료에서 사실, 의견, 근거와 부족한 항목을 분석한다.", "analysis"),
                genericAgent("content-planner", "콘텐츠 기획자", "독자와 목적에 맞는 제목, 요약, 본문 구조를 설계한다.", "plan"),
                genericAgent("draft-writer", "초안 작성자", "확정된 근거와 기획만 사용해 요청 형식의 초안을 작성한다.", "draft"),
                genericAgent("fact-editor", "팩트체커·편집자", "초안의 근거, 사실과 의견 구분, 문체와 구조를 검수한다.", "result"),
            )
        } else if (multiAgent) {
            listOf(
                genericAgent("analyst", "분석 담당", "제공된 입력에서 사실, 기준, 핵심 항목을 분석한다.", "analysis"),
                genericAgent("writer", "결과 작성 담당", "앞 단계 분석을 사용해 요청된 최종 결과를 작성한다.", "result"),
            )
        } else {
            val key = if (classification) "classifier" else "processor"
            listOf(genericAgent(key, if (classification) "분류 담당" else "업무 처리 담당", instruction, if (classification) "category" else "result"))
        }
        val aiNodes = when {
            writing -> listOf(
                WorkflowNodePlan("source-analysis", NodeType.AI_GENERATE.wireName, "자료 분석", mapOf("instruction" to "입력 자료의 사실과 근거 분석", "agentKey" to "source-analyst")),
                WorkflowNodePlan("content-plan", NodeType.AI_GENERATE.wireName, "콘텐츠 기획", mapOf("instruction" to "독자와 목적에 맞는 콘텐츠 구조 기획", "agentKey" to "content-planner")),
                WorkflowNodePlan("draft-write", NodeType.AI_GENERATE.wireName, "초안 작성", mapOf("instruction" to instruction, "agentKey" to "draft-writer")),
                WorkflowNodePlan("fact-edit", NodeType.AI_GENERATE.wireName, "팩트체크와 편집", mapOf("instruction" to "근거와 품질 기준에 따라 초안 검수", "agentKey" to "fact-editor")),
            )
            multiAgent -> listOf(
                WorkflowNodePlan("analyze", NodeType.AI_GENERATE.wireName, "입력 분석", mapOf("instruction" to "요청 목적에 맞게 입력을 분석", "agentKey" to "analyst")),
                WorkflowNodePlan("produce", NodeType.AI_GENERATE.wireName, "결과 작성", mapOf("instruction" to instruction, "agentKey" to "writer")),
            )
            classification -> listOf(WorkflowNodePlan("classify", NodeType.AI_CLASSIFY.wireName, "입력 분류", mapOf("categories" to listOf("유형 A", "유형 B", "기타"), "agentKey" to "classifier")))
            else -> listOf(WorkflowNodePlan("process", NodeType.AI_GENERATE.wireName, "요청 처리", mapOf("instruction" to instruction, "agentKey" to "processor")))
        }
        val nodes = buildList {
            add(WorkflowNodePlan("manual", NodeType.MANUAL_TRIGGER.wireName, "수동 시작"))
            addAll(aiNodes)
            if (approval) add(WorkflowNodePlan("approval", NodeType.HUMAN_APPROVAL.wireName, "담당자 승인", mapOf("approver" to "담당자")))
        }
        return MetaAgentDesignBundle(
            requirement = AutomationRequirement(
                objective = instruction,
                trigger = "수동 실행",
                inputs = listOf("사용자 제공 입력"),
                outputs = listOf(if (classification) "분류 결과" else "요청된 처리 결과"),
                steps = nodes.map { it.label },
                decisions = if (classification) listOf("입력 유형 선택") else emptyList(),
                exceptions = listOf("입력이 비어 있으면 실행하지 않음"),
                humanApprovalRequired = approval,
            ),
            clarificationQuestions = emptyList(),
            proposal = AutomationProposal(
                name = instruction.take(40),
                summary = "사용자가 제공한 입력을 요청한 방식으로 처리해 화면에서 확인합니다.",
                capabilities = nodes.map { it.label },
                integrations = emptyList(),
                approvalPoints = if (approval) listOf("최종 결과 확인 후") else emptyList(),
                failurePolicy = "실패한 단계에서 중단하고 원인을 표시",
                graphPlan = WorkflowGraphPlan(
                    entryNodeId = "manual",
                    nodes = nodes,
                    edges = nodes.zipWithNext().mapIndexed { index, (source, target) -> WorkflowEdgePlan("edge-${index + 1}", source.id, target.id) },
                ),
            ),
            agentDefinitions = agents,
            guideDefinitions = listOf(GuideDefinition("input", "입력 준비", "처리할 원문을 직접 입력합니다.", listOf(GuideField("text", "입력", "textarea", true, false, "원문을 입력하세요")))),
        )
    }

    private fun genericAgent(key: String, name: String, role: String, output: String) = AgentDefinition(
        key, name, role,
        listOf(FieldDefinition("text", "string", true, "사용자 제공 입력")),
        listOf(FieldDefinition(output, "string", true, "처리 결과")),
        listOf("사용자 요청의 의미를 보존한다", "제공된 입력만 사용한다"),
        listOf("근거 없는 내용을 만들지 않는다", "외부 작업을 수행하지 않는다"),
        listOf("사용한 입력과 판단 근거"),
    )

    private fun faqGraphPlan() = WorkflowGraphPlan(
        entryNodeId = "slack-trigger",
        nodes = listOf(
            WorkflowNodePlan("slack-trigger", NodeType.SLACK_NEW_MESSAGE_MOCK.wireName, "Slack 문의 수신 (Mock)"),
            WorkflowNodePlan("notion-search", NodeType.NOTION_SEARCH_MOCK.wireName, "Notion FAQ 검색 (Mock)", mapOf("database" to "FAQ", "agentKey" to "faq-searcher")),
            WorkflowNodePlan("answer-draft", NodeType.AI_GENERATE.wireName, "AI 답변 초안", mapOf("instruction" to "FAQ 근거로 답변 초안 작성", "agentKey" to "faq-answer-writer")),
            WorkflowNodePlan("human-approval", NodeType.HUMAN_APPROVAL.wireName, "담당자 승인", mapOf("approver" to "담당자")),
            WorkflowNodePlan("slack-reply", NodeType.SLACK_REPLY_MOCK.wireName, "Slack 스레드 답변 (Mock)"),
        ),
        edges = listOf("slack-trigger", "notion-search", "answer-draft", "human-approval", "slack-reply")
            .zipWithNext()
            .mapIndexed { index, (source, target) -> WorkflowEdgePlan("edge-${index + 1}", source, target) },
    )
}

data class PipelineContext(
    val traceId: UUID,
    val ownerId: UUID,
    val workspaceId: UUID,
    val conversationId: UUID,
    val workflowId: UUID,
    val jobId: UUID? = null,
)

@Component
class StructuredMetaAgentPipeline(
    private val model: MetaAgentModel,
    private val mapper: ObjectMapper,
    private val audit: MetaAgentAuditService,
    private val progress: BuilderJobProgressService,
) {
    private val capabilityResolver = BuilderCapabilityResolver()
    private val designAssembler = AgentDesignAssembler()
    fun preflight(context: PipelineContext) = model.preflight(context)
    private val designStages = listOf(
        "analyze_business_process",
        "clarify_requirements",
        "design_automation",
        "design_agents",
        "design_guides",
    )

    fun generateDesign(context: PipelineContext, instruction: String): MetaAgentDesignBundle {
        val input = mapOf("instruction" to instruction)
        val startedAt = System.nanoTime()
        designStages.forEach { stage ->
            audit.record(context, stage, "STARTED", summary(input) + mapOf("executor" to model.executorName, "model" to model.modelName))
        }
        progress.running(context.jobId, BuilderGenerationStage.CODEX_ANALYZING)
        return try {
            val raw = model.generate(context, "builder_design_bundle", input)
            progress.running(context.jobId, BuilderGenerationStage.STRUCTURE_VALIDATING)
            val transport = mapper.readValue(raw, LlmMetaAgentDesignDto::class.java)
            val bundle = normalize(transport.toDomain(mapper), instruction)
            BuilderMvpSupportPolicy.requireSupported(instruction, bundle)
            validate(bundle)
            val durationMs = (System.nanoTime() - startedAt) / 1_000_000
            val counts = listOf(1, bundle.clarificationQuestions.size, 1, bundle.agentDefinitions.size, bundle.guideDefinitions.size)
            designStages.zip(counts).forEach { (stage, count) ->
                audit.record(context, stage, "SUCCEEDED", summary(input), mapOf("itemCount" to count, "durationMs" to durationMs, "executor" to model.executorName, "model" to model.modelName))
            }
            bundle
        } catch (exception: Exception) {
            val durationMs = (System.nanoTime() - startedAt) / 1_000_000
            val failure = failure(exception, durationMs)
            val auditStatus = if (failure.errorCode == "BUILDER_GENERATION_CANCELLED") "CANCELLED" else "FAILED"
            designStages.forEach { stage -> audit.record(context, stage, auditStatus, summary(input), failure = failure) }
            when (exception) {
                is ApiException -> throw exception
                is MetaAgentExecutionException -> throw BadRequestException(exception.errorCode, exception.message ?: "Codex 메타 에이전트 실행에 실패했습니다.")
                else -> throw BadRequestException("INVALID_STRUCTURED_OUTPUT", "메타 에이전트 결과가 승인된 스키마와 일치하지 않습니다.")
            }
        }
    }

    fun record(context: PipelineContext, stage: String, inputCount: Int, outputCount: Int) {
        audit.record(context, stage, "SUCCEEDED", mapOf("fieldCount" to inputCount), mapOf("itemCount" to outputCount, "executor" to "server-contract"))
    }

    private fun validate(bundle: MetaAgentDesignBundle) {
        if (bundle.requirement.objective.isBlank() || bundle.requirement.steps.isEmpty()) invalid()
        if (bundle.clarificationQuestions.map { it.field }.distinct().size != bundle.clarificationQuestions.size) invalid()
        if (bundle.clarificationQuestions.isNotEmpty()) return
        if (bundle.proposal.name.isBlank() || bundle.proposal.capabilities.isEmpty()) invalid()
        if (bundle.agentDefinitions.size !in 0..5 || bundle.agentDefinitions.map { it.key }.distinct().size != bundle.agentDefinitions.size) invalid()
        if (bundle.guideDefinitions.isEmpty() || bundle.guideDefinitions.size > 5 || bundle.guideDefinitions.map { it.key }.distinct().size != bundle.guideDefinitions.size) invalid()
        if (bundle.agentDefinitions.any { it.behaviorRules.isEmpty() || it.forbiddenRules.isEmpty() || it.evidenceRequirements.isEmpty() }) invalid()
        val plan = bundle.proposal.graphPlan ?: invalid()
        if (plan.nodes.isEmpty() || plan.nodes.map { it.id }.distinct().size != plan.nodes.size || plan.edges.map { it.id }.distinct().size != plan.edges.size) invalid()
    }

    private fun normalize(bundle: MetaAgentDesignBundle, instruction: String): MetaAgentDesignBundle {
        val instructionLower = instruction.lowercase()
        val deterministicCsv = instructionLower.contains("csv") && instructionLower.contains("비교") && listOf("변경", "차이", "행").any(instructionLower::contains)
        val genericFaqDraft = instructionLower.contains("faq") && listOf("답변", "초안").any(instructionLower::contains) &&
            listOf("slack", "슬랙", "notion", "노션", "전송", "보내").none(instructionLower::contains)
        val writingAutomation = listOf("글쓰기", "글을", "원고", "콘텐츠", "보고서", "article", "content", "writing", "report")
            .any(instruction.lowercase()::contains)
        val scheduled = Regex("\\d{1,2}시").containsMatchIn(instruction) ||
            Regex("매일\\s*(아침|오전|오후|밤|새벽|\\d)").containsMatchIn(instruction) ||
            listOf("예약 실행", "schedule at", "daily at", "weekly at").any(instruction.lowercase()::contains)
        val genericNewsReference = listOf("최신뉴스", "최신 뉴스", "최신 토픽", "뉴스", "기사", "news", "topic")
            .any(instruction.lowercase()::contains)
        val specificNewsSource = listOf("rss", "네이버", "구글 뉴스", "google news", "url", "사이트", "웹사이트", "피드")
            .any(instruction.lowercase()::contains)
        val wordFormatOnly = Regex("(워드|word)(로|문서|파일)?(?:\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(instruction)
        val specificFileDestination = listOf("onedrive", "원드라이브", "sharepoint", "셰어포인트", "google drive", "구글 드라이브", "이메일", "다운로드", "폴더")
            .any(instruction.lowercase()::contains)
        val questions = buildList {
            val hasDirectInput = listOf("수동", "입력", "붙여", "제공", "업로드", "원문", "텍스트")
                .any(instruction.lowercase()::contains)
            val hasInbound = scheduled || hasDirectInput || deterministicCsv || genericFaqDraft || instruction.contains("Slack", true) || instruction.contains("슬랙") || instruction.contains("이메일") || instruction.contains("채팅")
            val hasKnowledge = instruction.contains("Notion", true) || instruction.contains("노션") || instruction.contains("FAQ", true) || instruction.contains("데이터베이스") ||
                genericNewsReference || deterministicCsv || genericFaqDraft || hasDirectInput
            val hasApproval = deterministicCsv || genericFaqDraft || instruction.contains("승인") || instruction.contains("검토") || instruction.contains("확인 후") || instruction.contains("바로 보내") ||
                listOf("승인 없이", "검토 없이", "승인 불필요", "실제 게시하지", "보내지 마").any(instruction::contains)
            val hasDestination = deterministicCsv || genericFaqDraft || instruction.contains("스레드") || instruction.contains("thread", true) || instruction.contains("전송") || instruction.contains("회신") || instruction.contains("답변을 보내") ||
                Regex("(Slack|슬랙)[^\\n]{0,30}(답변|회신)", RegexOption.IGNORE_CASE).containsMatchIn(instruction) || specificFileDestination ||
                listOf("화면", "미리보기", "결과로 표시", "채팅에 표시").any(instruction::contains)
            if (!hasInbound) add(ClarificationQuestion("inbound", "inbound", if (writingAutomation) "글쓰기는 수동으로 시작할까요, 정해진 시간이나 이벤트에 실행할까요?" else "자동화할 업무는 어떤 입력이나 이벤트로 시작되며, 어느 서비스에서 들어오나요?"))
            if (!hasKnowledge) add(ClarificationQuestion("knowledge-source", "knowledgeSource", if (writingAutomation && genericNewsReference) "최신 뉴스는 어느 사이트, RSS 또는 뉴스 서비스에서 수집할까요?" else if (writingAutomation) "글의 주제와 근거 자료는 어느 서비스나 데이터베이스에서 가져올까요?" else "결과를 만들 때 참고할 자료는 어느 서비스나 데이터베이스에 있나요?"))
            if (!hasApproval) add(ClarificationQuestion("approval-policy", "approvalPolicy", if (writingAutomation) "작성된 글을 바로 저장할까요, 담당자가 검토하고 승인한 뒤 저장할까요?" else "완성된 결과를 바로 실행할까요, 담당자 검토와 승인 후 실행할까요?"))
            if (!hasDestination) add(ClarificationQuestion("destination", "destination", if (writingAutomation && wordFormatOnly) "Word 문서는 어느 서비스나 폴더에 저장하거나 누구에게 전달할까요?" else if (writingAutomation) "완성된 글은 어느 서비스의 어느 위치에 저장하거나 발행할까요?" else "완성된 결과는 어느 서비스의 어느 위치로 전달하거나 저장할까요?"))
        }
        val normalized = bundle.copy(
            clarificationQuestions = questions,
            proposal = bundle.proposal.copy(graphPlan = bundle.proposal.graphPlan?.let(::normalizeGraphPlan)),
            agentDefinitions = if (questions.isEmpty()) bundle.agentDefinitions else emptyList(),
        )
        if (questions.isNotEmpty()) return normalized
        val standardized = when {
            deterministicCsv -> compileCsvComparison(normalized, instruction)
            genericFaqDraft -> compileFaqDraft(normalized, instruction)
            scheduled && genericNewsReference && (instruction.contains("Slack", true) || instruction.contains("슬랙")) -> standardizeDailyNewsReport(normalized, instruction)
            (instruction.contains("Slack", true) || instruction.contains("슬랙")) && (instruction.contains("Notion", true) || instruction.contains("노션") || instruction.contains("FAQ", true)) -> standardizeCustomerSupport(normalized)
            writingAutomation -> standardizeWritingTeam(normalized, instruction)
            else -> normalized
        }
        val aiCalls = standardized.proposal.graphPlan?.nodes.orEmpty().count { it.nodeType in setOf(NodeType.AI_GENERATE.wireName, NodeType.AI_CLASSIFY.wireName) }
        val completed = standardized.copy(proposal = standardized.proposal.copy(
            templateSelection = standardized.proposal.templateSelection ?: HarnessTemplateSelection(
                templateKey = "generated-${instruction.hashCode().toUInt().toString(16)}",
                version = 1,
                source = "GENERATED",
                matchReason = "승인된 기본 템플릿과 정확히 일치하지 않아 구조화 계약 안에서 초안을 생성",
            ),
            economics = HarnessDesignEconomics(
                agentCount = standardized.agentDefinitions.size,
                estimatedAiCallsPerRun = aiCalls,
                separationRationale = if (standardized.agentDefinitions.size <= 1) listOf("판단 작업을 한 번의 구조화 AI 호출로 통합") else listOf("서로 독립적인 판단 계약이 필요함"),
            ),
            outputSchema = standardized.agentDefinitions.lastOrNull()?.outputSchema.orEmpty(),
            executionContract = standardized.proposal.executionContract ?: TemplateExecutionContract(
                contentSchemaVersion = "1.0",
                rendererKey = "plain-text",
                rendererVersion = "1",
                qualityRuleVersion = "1",
                promptVersion = "1",
                modelPolicy = mapOf("mode" to "STRUCTURED", "maxCallsPerRun" to aiCalls, "temperature" to 0),
                sourcePolicyVersion = "1",
                qualityRules = mapOf("evidenceRequired" to true, "arbitraryFieldsAllowed" to false),
            ),
        ))
        val resolved = completed.copy(proposal = completed.proposal.copy(resourcePlan = capabilityResolver.resolve(completed)))
        return resolved.copy(proposal = resolved.proposal.copy(agentDesign = designAssembler.assemble(resolved)))
    }

    private fun compileCsvComparison(bundle: MetaAgentDesignBundle, instruction: String): MetaAgentDesignBundle {
        val nodes = listOf(
            WorkflowNodePlan("manual-input", NodeType.MANUAL_TRIGGER.wireName, "CSV 두 파일 입력"),
            WorkflowNodePlan("csv-compare", NodeType.DATA_CSV_COMPARE.wireName, "CSV 행 결정적 비교", mapOf("keyColumns" to listOf("사용자 지정 키"), "comparisonMode" to "EXACT")),
        )
        return bundle.copy(
            requirement = AutomationRequirement(instruction, "수동 파일 입력", listOf("기준 CSV", "비교 CSV"), listOf("추가·수정·삭제된 행"), nodes.map { it.label }, emptyList(), listOf("키 열 누락", "CSV 파싱 실패"), false),
            clarificationQuestions = emptyList(),
            proposal = bundle.proposal.copy(
                name = "CSV 변경 행 비교", summary = "두 CSV를 일반 코드로 정확히 비교하고 변경된 행을 구조화합니다.", capabilities = nodes.map { it.label }, integrations = emptyList(), approvalPoints = emptyList(),
                failurePolicy = "입력 형식 또는 키 열이 유효하지 않으면 비교를 중단하고 원인을 표시",
                graphPlan = WorkflowGraphPlan(nodes.first().id, nodes, listOf(WorkflowEdgePlan("edge-1", nodes[0].id, nodes[1].id, bindings = listOf(WorkflowFieldBinding("files", "files"))))),
                outputSchema = listOf(FieldDefinition("changedRows", "array", true, "추가·수정·삭제된 행 목록")),
            ),
            agentDefinitions = emptyList(),
            guideDefinitions = listOf(GuideDefinition("csv-input", "CSV 비교 설정", "두 파일의 키 열과 인코딩을 설정합니다.", listOf(GuideField("keyColumns", "키 열", "text", true, false, "행을 식별할 하나 이상의 열")))),
        )
    }

    private fun compileFaqDraft(bundle: MetaAgentDesignBundle, instruction: String): MetaAgentDesignBundle {
        val agent = AgentDefinition(
            "answer-writer", "근거 기반 답변 작성자", "검색된 FAQ 근거만 사용해 고객 답변 초안을 작성한다.",
            listOf(FieldDefinition("question", "string", true, "고객 문의"), FieldDefinition("searchResults", "array", true, "FAQ 검색 결과")),
            listOf(FieldDefinition("draft", "string", true, "근거 기반 답변 초안")),
            listOf("질문 의도를 보존한다", "검색 결과를 근거로 답한다"), listOf("근거가 없으면 내용을 만들지 않는다", "외부로 전송하지 않는다"), listOf("사용한 FAQ 항목"),
        )
        val nodes = listOf(
            WorkflowNodePlan("manual-input", NodeType.MANUAL_TRIGGER.wireName, "고객 문의 입력"),
            WorkflowNodePlan("faq-search", NodeType.KNOWLEDGE_SEARCH_MOCK.wireName, "FAQ 검색 (Mock · 연결 필요)", mapOf("source" to "사용자 지정 FAQ", "queryField" to "question", "connectionStatus" to "UNRESOLVED")),
            WorkflowNodePlan("answer-draft", NodeType.AI_GENERATE.wireName, "답변 초안 작성", mapOf("instruction" to "FAQ 검색 근거로 답변 초안 작성", "agentKey" to agent.key)),
        )
        return bundle.copy(
            requirement = AutomationRequirement(instruction, "수동 문의 입력", listOf("고객 문의", "FAQ 자료"), listOf("답변 초안"), nodes.map { it.label }, listOf("관련 FAQ 선택"), listOf("근거 검색 결과 없음"), false),
            clarificationQuestions = emptyList(),
            proposal = bundle.proposal.copy(
                name = "FAQ 기반 고객 답변 초안", summary = "FAQ를 검색하고 근거가 있는 답변 초안만 만듭니다.", capabilities = nodes.map { it.label }, integrations = listOf("FAQ Mock · 연결 필요"), approvalPoints = emptyList(),
                failurePolicy = "근거가 없으면 답변을 만들지 않고 담당자 확인 필요 상태를 반환",
                graphPlan = WorkflowGraphPlan(nodes.first().id, nodes, listOf(
                    WorkflowEdgePlan("edge-1", nodes[0].id, nodes[1].id, bindings = listOf(WorkflowFieldBinding("question", "question"))),
                    WorkflowEdgePlan("edge-2", nodes[1].id, nodes[2].id, bindings = listOf(WorkflowFieldBinding("question", "question"), WorkflowFieldBinding("searchResults", "searchResults"))),
                )),
            ),
            agentDefinitions = listOf(agent),
            guideDefinitions = listOf(GuideDefinition("faq-source", "FAQ 자료 연결", "실제 실행 전에 FAQ 검색 소스를 연결합니다.", listOf(GuideField("source", "FAQ 소스", "text", true, false, "검색할 FAQ 저장소")))),
        )
    }

    private fun standardizeWritingTeam(bundle: MetaAgentDesignBundle, instruction: String): MetaAgentDesignBundle {
        val explicitSeparation = listOf("분석 담당과", "분석 담당이", "작성 담당과", "작성 담당이").count(instruction::contains) >= 2
        val agents = if (explicitSeparation) listOf(
            writingAgent("content-analyst", "콘텐츠 분석자", "입력 자료에서 사실, 수치, 쟁점과 누락 정보를 구조화한다.", "analysis"),
            writingAgent("content-writer", "콘텐츠 작성자", "분석 결과만 근거로 목적과 독자에 맞는 초안을 작성하고 자체 점검한다.", "result"),
        ) else listOf(writingAgent(
            "content-writer", "콘텐츠 작성자",
            "입력 자료의 사실과 근거를 분석하고, 독자와 목적에 맞는 구조를 잡아 초안을 작성한 뒤 자체 점검 결과를 함께 반환한다.",
            "result",
        ))
        val plan = bundle.proposal.graphPlan ?: return bundle.copy(agentDefinitions = agents)
        val aiTypes = setOf(NodeType.AI_GENERATE.wireName, NodeType.AI_CLASSIFY.wireName)
        val firstAi = plan.nodes.indexOfFirst { it.nodeType in aiTypes }.let { if (it < 0) plan.nodes.indexOfFirst { node -> node.nodeType == NodeType.HUMAN_APPROVAL.wireName }.let { approval -> if (approval < 0) plan.nodes.size else approval } else it }
        val retained = plan.nodes.filterNot { it.nodeType in aiTypes }.toMutableList()
        val insertionIndex = plan.nodes.take(firstAi).count { it.nodeType !in aiTypes }
        val writingNodes = if (explicitSeparation) listOf(
            WorkflowNodePlan("content-analyze", NodeType.AI_GENERATE.wireName, "입력 자료 분석", mapOf("instruction" to "입력 자료의 사실과 쟁점을 구조화", "agentKey" to "content-analyst")),
            WorkflowNodePlan("content-write", NodeType.AI_GENERATE.wireName, "분석 근거로 초안 작성", mapOf("instruction" to instruction, "agentKey" to "content-writer")),
        ) else listOf(WorkflowNodePlan(
            "content-write", NodeType.AI_GENERATE.wireName, "분석·구성·작성·자체 점검", mapOf("instruction" to instruction, "agentKey" to "content-writer"),
        ))
        retained.addAll(insertionIndex.coerceIn(0, retained.size), writingNodes)
        val normalizedPlan = WorkflowGraphPlan(
            entryNodeId = retained.firstOrNull()?.id ?: plan.entryNodeId,
            nodes = retained,
            edges = retained.zipWithNext().mapIndexed { index, (source, target) -> WorkflowEdgePlan("edge-${index + 1}", source.id, target.id) },
        )
        val guides = bundle.guideDefinitions.takeIf { definitions -> definitions.any { it.fields.isNotEmpty() } }
            ?: listOf(GuideDefinition("writing-input", "글쓰기 입력과 품질 설정", "작성할 주제, 근거 원문, 독자와 출력 형식을 설정합니다.", listOf(
                GuideField("topic", "주제", "text", true, false, "작성할 글의 주제"),
                GuideField("sourceText", "근거 원문", "textarea", true, false, "에이전트가 사실 근거로 사용할 사용자 제공 자료"),
                GuideField("audience", "대상 독자", "text", true, false, "예: 일반 독자"),
            )))
        return bundle.copy(
            requirement = bundle.requirement.copy(steps = retained.map { it.label }),
            proposal = bundle.proposal.copy(
                capabilities = retained.map { it.label }, graphPlan = normalizedPlan,
                templateSelection = HarnessTemplateSelection("structured-writing", 1, "BUILT_IN", "구조화 글쓰기 요청과 일치"),
                executionContract = TemplateExecutionContract(
                    contentSchemaVersion = "1.0", rendererKey = "article.plain-text", rendererVersion = "1",
                    qualityRuleVersion = "1", promptVersion = "1",
                    modelPolicy = mapOf("mode" to "STRUCTURED", "maxCallsPerRun" to 1, "temperature" to 0),
                    sourcePolicyVersion = "user-input-only-v1",
                    qualityRules = mapOf("evidenceRequired" to true, "factOpinionSeparation" to true, "maxLength" to 5000),
                ),
            ),
            agentDefinitions = agents,
            guideDefinitions = guides,
        )
    }

    private fun standardizeCustomerSupport(bundle: MetaAgentDesignBundle): MetaAgentDesignBundle {
        val designedAgent = AgentDefinition(
            key = "support-answer-writer", name = "고객 답변 작성자",
            role = "정규화된 고객 문의와 Notion FAQ 검색 근거만 사용해 답변 초안을 작성한다.",
            inputSchema = listOf(FieldDefinition("normalizedText", "string", true, "정규화된 고객 문의"), FieldDefinition("notionResult", "string", true, "FAQ 검색 근거")),
            outputSchema = listOf(FieldDefinition("draft", "string", true, "고객 답변 초안")),
            behaviorRules = listOf("FAQ 근거를 보존한다", "고객이 이해하기 쉬운 답변을 작성한다"),
            forbiddenRules = listOf("근거 없는 정책을 만들지 않는다", "직접 외부 전송하지 않는다"),
            evidenceRequirements = listOf("사용한 FAQ 문장"), connectorKeys = listOf("connector.notion.mock"),
            approvalPolicy = ApprovalPolicy(true, listOf("slack-reply"), "Slack 전송 전 담당자 승인"),
        )
        val agent = bundle.agentDefinitions.singleOrNull()?.copy(
            connectorKeys = (bundle.agentDefinitions.single().connectorKeys + "connector.notion.mock").distinct(),
            approvalPolicy = ApprovalPolicy(true, listOf("slack-reply"), "Slack 전송 전 담당자 승인"),
        ) ?: designedAgent
        val nodes = listOf(
            WorkflowNodePlan("slack-trigger", NodeType.SLACK_NEW_MESSAGE_MOCK.wireName, "Slack 문의 수신 (Mock)"),
            WorkflowNodePlan("normalize", NodeType.DATA_NORMALIZE.wireName, "문의 정규화"),
            WorkflowNodePlan("notion-search", NodeType.NOTION_SEARCH_MOCK.wireName, "Notion FAQ 검색 (Mock)", mapOf("database" to "FAQ")),
            WorkflowNodePlan("answer-draft", NodeType.AI_GENERATE.wireName, "답변 초안 작성", mapOf("instruction" to "FAQ 근거만 사용해 답변 초안 작성", "agentKey" to agent.key)),
            WorkflowNodePlan("quality-check", NodeType.QUALITY_CHECK.wireName, "답변 품질 검사"),
            WorkflowNodePlan("human-approval", NodeType.HUMAN_APPROVAL.wireName, "담당자 승인", mapOf("approver" to "담당자")),
            WorkflowNodePlan("slack-reply", NodeType.SLACK_REPLY_MOCK.wireName, "Slack 스레드 답변 (Mock)"),
            WorkflowNodePlan("end", NodeType.WORKFLOW_END.wireName, "완료"),
        )
        val graph = WorkflowGraphPlan(nodes.first().id, nodes, nodes.zipWithNext().mapIndexed { index, (from, to) -> WorkflowEdgePlan("edge-${index + 1}", from.id, to.id) })
        return bundle.copy(
            requirement = bundle.requirement.copy(steps = nodes.map { it.label }, qualityConditions = listOf("FAQ 근거 사용", "승인 전 미전송"), forbiddenConditions = listOf("근거 없는 답변 생성", "승인 없는 외부 전송")),
            proposal = bundle.proposal.copy(
                name = "Slack FAQ 답변 에이전트", summary = "Slack 문의를 정규화하고 FAQ 근거로 답변을 작성해 품질 검사와 담당자 승인 후 Mock 스레드 전송을 시험합니다.",
                capabilities = nodes.map { it.label }, integrations = listOf("Slack Mock", "Notion Mock"), approvalPoints = listOf("Slack 답변 직전"), graphPlan = graph,
            ),
            agentDefinitions = listOf(agent),
            guideDefinitions = listOf(
                GuideDefinition("slack", "Slack 연결 설정", "실행 Runtime에서 Slack 연결을 설정합니다.", listOf(GuideField("channel", "문의 채널", "text", true, false, "#customer-support"), GuideField("approver", "승인자", "text", true, false, "담당자"))),
                GuideDefinition("notion", "Notion FAQ 설정", "실행 Runtime에서 FAQ 데이터베이스 연결을 설정합니다.", listOf(GuideField("database", "FAQ 데이터베이스", "text", true, false, "검색할 데이터베이스"))),
            ),
        )
    }

    private fun standardizeDailyNewsReport(bundle: MetaAgentDesignBundle, instruction: String): MetaAgentDesignBundle {
        val source = listOf("네이버", "Google News", "구글 뉴스", "RSS").firstOrNull { instruction.contains(it, true) } ?: "사용자 지정 뉴스 소스"
        val hour = Regex("(\\d{1,2})\\s*시").find(instruction)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
        val channel = Regex("#[A-Za-z0-9_-]+").find(instruction)?.value ?: "사용자 지정 Slack 채널"
        val agent = AgentDefinition(
            key = "market-news-reporter",
            name = "시장 뉴스 보고서 작성자",
            role = "중복 제거된 경제·주식 뉴스를 근거로 시장 영향 보고서를 한 번의 구조화 호출로 작성한다.",
            inputSchema = listOf(FieldDefinition("newsItems", "array", true, "제목, 요약, URL, 발행 시각, 출처가 포함된 뉴스 목록")),
            outputSchema = listOf(
                FieldDefinition("marketSummary", "string", true, "시장 핵심 요약"),
                FieldDefinition("topNews", "array", true, "주요 뉴스와 출처"),
                FieldDefinition("marketImpact", "array", true, "사실과 해석을 구분한 예상 영향"),
                FieldDefinition("watchlist", "array", true, "관찰할 업종 또는 종목과 이유"),
                FieldDefinition("risks", "array", true, "불확실성과 위험 요인"),
                FieldDefinition("sources", "array", true, "사용한 기사 URL"),
                FieldDefinition("report", "string", true, "Slack 전송용 최종 보고서"),
            ),
            behaviorRules = listOf("기사에 있는 사실과 분석을 구분한다", "중복 기사를 하나의 이슈로 묶는다", "모든 핵심 주장에 출처를 연결한다"),
            forbiddenRules = listOf("기사에 없는 사실을 만들지 않는다", "수익을 보장하거나 매수·매도를 지시하지 않는다", "직접 외부 전송하지 않는다"),
            evidenceRequirements = listOf("기사 제목, URL, 발행 시각, 출처", "영향 분석에 사용한 근거 문장"),
        )
        val nodes = listOf(
            WorkflowNodePlan("daily-schedule", NodeType.SCHEDULE_TRIGGER.wireName, "매일 ${hour}시 실행", mapOf("cron" to "0 0 $hour * * *", "timezone" to "Asia/Seoul")),
            WorkflowNodePlan("news-search", NodeType.NEWS_SEARCH_MOCK.wireName, "$source 뉴스 수집 (Mock)", mapOf("source" to source, "query" to "경제 주식 시장", "lookbackHours" to 24)),
            WorkflowNodePlan("deduplicate", NodeType.DATA_DEDUPLICATE.wireName, "중복 뉴스 제거", mapOf("key" to "canonicalUrl")),
            WorkflowNodePlan("write-report", NodeType.AI_GENERATE.wireName, "시장 영향 보고서 작성", mapOf("instruction" to "승인된 출력 스키마로 일일 시장 영향 보고서 작성", "agentKey" to agent.key)),
            WorkflowNodePlan("render", NodeType.TEMPLATE_RENDER.wireName, "승인된 Slack 템플릿 렌더링", mapOf("rendererKey" to "slack.market-news.v1")),
            WorkflowNodePlan("approval", NodeType.HUMAN_APPROVAL.wireName, "담당자 승인", mapOf("approver" to "담당자")),
            WorkflowNodePlan("slack-send", NodeType.SLACK_SEND_MOCK.wireName, "$channel 전송 (Mock)", mapOf("channel" to channel, "rendererKey" to "slack.market-news.v1")),
            WorkflowNodePlan("end", NodeType.WORKFLOW_END.wireName, "완료"),
        )
        val guide = GuideDefinition("daily-news-settings", "일일 뉴스 보고서 설정", "실행 시간, 뉴스 소스, Slack 채널과 승인자를 설정합니다.", listOf(
            GuideField("schedule", "실행 시간", "text", true, false, "매일 실행할 시간과 시간대"),
            GuideField("newsSource", "뉴스 소스", "text", true, false, "허용된 뉴스 API, RSS 또는 사이트"),
            GuideField("query", "검색 주제", "text", true, false, "예: 경제 주식 시장"),
            GuideField("slackChannel", "Slack 채널", "text", true, false, "보고서를 받을 채널"),
            GuideField("approver", "승인자", "text", true, false, "전송 전 보고서를 검토할 담당자"),
        ))
        return bundle.copy(
            requirement = bundle.requirement.copy(
                trigger = "매일 ${hour}시 예약 실행", inputs = listOf("최근 24시간 경제·주식 뉴스"), outputs = listOf("승인된 시장 영향 보고서"),
                steps = nodes.map { it.label }, decisions = listOf("뉴스 관련성", "시장 영향", "담당자 승인"), humanApprovalRequired = true,
            ),
            proposal = bundle.proposal.copy(
                name = "일일 주식 뉴스 영향 보고서", summary = "뉴스를 수집·중복 제거하고 AI 호출 한 번으로 보고서를 작성해 승인 후 Slack으로 전달합니다.",
                capabilities = nodes.map { it.label }, integrations = listOf("News Mock", "Slack Mock"), approvalPoints = listOf("Slack 전송 직전"),
                graphPlan = WorkflowGraphPlan(nodes.first().id, nodes, nodes.zipWithNext().mapIndexed { index, (from, to) -> WorkflowEdgePlan("edge-${index + 1}", from.id, to.id) }),
                templateSelection = HarnessTemplateSelection("daily-market-news-report", 1, "BUILT_IN", "예약 뉴스 분석과 Slack 보고서 전달 요청이 일치"),
                executionContract = TemplateExecutionContract(
                    contentSchemaVersion = "1.0", rendererKey = "slack.market-news", rendererVersion = "1",
                    qualityRuleVersion = "1", promptVersion = "market-impact-report-v1",
                    modelPolicy = mapOf("mode" to "STRUCTURED", "maxCallsPerRun" to 1, "temperature" to 0),
                    sourcePolicyVersion = "news-evidence-24h-v1",
                    qualityRules = mapOf(
                        "sourceRequired" to true, "factOpinionSeparation" to true, "maxNewsItems" to 5,
                        "duplicateTitlesAllowed" to false, "investmentDisclaimerRequired" to true,
                    ),
                ),
            ),
            agentDefinitions = listOf(agent),
            guideDefinitions = listOf(guide),
        )
    }

    private fun writingAgent(key: String, name: String, role: String, output: String) = AgentDefinition(
        key, name, role,
        listOf(FieldDefinition("text", "string", true, "사용자 제공 입력")),
        listOf(FieldDefinition(output, "string", true, "구조화된 단계 결과")),
        listOf("사용자 요청의 목적과 독자를 보존한다", "제공된 입력과 앞 단계 결과만 사용한다"),
        listOf("근거 없는 내용을 만들지 않는다", "외부 작업을 직접 수행하지 않는다"),
        listOf("사용한 입력과 판단 근거"),
    )

    private fun normalizeGraphPlan(plan: WorkflowGraphPlan): WorkflowGraphPlan {
        val branchById = plan.nodes.filter { it.nodeType == NodeType.CONDITION_BRANCH.wireName }.associateBy { it.id }
        val edgesWithoutRedundantSelfLoops = plan.edges.filterNot { edge ->
            edge.source == edge.target && plan.edges.any { candidate -> candidate.source == edge.source && candidate.target != edge.source }
        }
        val outgoingCounts = edgesWithoutRedundantSelfLoops.groupingBy { it.source }.eachCount()
        val edges = edgesWithoutRedundantSelfLoops.map { edge ->
            val branch = branchById[edge.source] ?: return@map edge
            if (edge.condition != "success" || outgoingCounts[edge.source] != 1) return@map edge
            val expression = branch.config["expression"]?.toString().orEmpty()
            val field = Regex("[A-Za-z][A-Za-z0-9]*").find(expression)?.value ?: return@map edge
            edge.copy(condition = "$field=true")
        }
        return plan.copy(edges = edges.map { edge -> if (edge.bindings.isEmpty()) edge.copy(bindings = listOf(WorkflowFieldBinding("context", "context"))) else edge })
    }

    private fun invalid(): Nothing = throw BadRequestException("INVALID_STRUCTURED_OUTPUT", "메타 에이전트 결과가 승인된 스키마와 일치하지 않습니다.")
    private fun summary(input: Map<String, Any?>) = mapOf("fieldCount" to input.size, "instructionChars" to input["instruction"]?.toString()?.length)
    private fun failure(exception: Exception, durationMs: Long) = when (exception) {
        is MetaAgentExecutionException -> MetaAgentFailure(exception.errorCode, exception.errorType, exception.retryable, durationMs, exception.cliExitCode, exception.safeMessage)
        is ApiException -> MetaAgentFailure(exception.code, exception::class.simpleName ?: "ApiException", false, durationMs, safeMessage = exception.message)
        else -> MetaAgentFailure("INVALID_STRUCTURED_OUTPUT", exception::class.simpleName ?: "Exception", false, durationMs, safeMessage = "구조화 출력 검증 실패")
    }
}
