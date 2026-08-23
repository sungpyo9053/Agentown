package com.agentvillage.builder.application

import com.agentvillage.builder.domain.*
import com.agentvillage.common.exception.ApiException
import com.agentvillage.common.exception.BadRequestException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.UUID

interface MetaAgentModel {
    val executorName: String
    val modelName: String
    fun preflight(context: PipelineContext) = Unit
    fun generate(context: PipelineContext, stage: String, input: Map<String, Any?>): String
}

@Component
@ConditionalOnProperty(name = ["builder.meta-agent.mode"], havingValue = "mock")
class DeterministicMockMetaAgentModel(private val mapper: ObjectMapper) : MetaAgentModel {
    override val executorName = "deterministic-test-mock"
    override val modelName = "mock"

    override fun generate(context: PipelineContext, stage: String, input: Map<String, Any?>): String {
        val instruction = input["instruction"]?.toString().orEmpty()
        val slack = instruction.contains("Slack", true) || instruction.contains("슬랙")
        val notion = instruction.contains("Notion", true) || instruction.contains("노션")
        val approval = instruction.contains("담당자") || instruction.contains("승인") || instruction.contains("검토")
        val questions = buildList {
            if (!slack) add(ClarificationQuestion("inbound", "inbound", "고객 문의는 어느 서비스와 채널로 들어오나요?"))
            if (!notion) add(ClarificationQuestion("knowledge-source", "knowledgeSource", "답변에 사용할 자료는 어느 서비스와 데이터베이스에서 찾나요?"))
            if (!approval) add(ClarificationQuestion("approval-policy", "approvalPolicy", "답변을 바로 보낼까요, 담당자 승인 후 보낼까요?"))
            if (!slack) add(ClarificationQuestion("destination", "destination", "완성된 답변은 어느 서비스와 대화 위치로 전송할까요?"))
        }
        val bundle = MetaAgentDesignBundle(
            requirement = AutomationRequirement(
                objective = instruction,
                trigger = if (slack) "Slack 고객 문의 수신" else "수동 실행",
                inputs = listOf("고객 문의"), outputs = listOf("승인된 답변 초안"),
                steps = listOf("문의 수신", "Notion FAQ 검색", "AI 답변 초안", "담당자 승인", "Slack 스레드 답변"),
                decisions = listOf("관련 FAQ 선택", "담당자 승인 여부"), exceptions = listOf("FAQ 검색 결과 없음", "승인 거절"), humanApprovalRequired = true,
            ),
            clarificationQuestions = questions,
            proposal = AutomationProposal("Slack FAQ 답변 자동화", "Slack 문의를 FAQ 근거로 답변 초안화하고 승인 후 회신합니다.", listOf("문의 수신", "FAQ 검색", "답변 생성", "사람 승인", "답변 미리보기"), listOf("Slack Mock", "Notion Mock"), listOf("Slack 답변 직전"), "실패한 노드에서 중단하고 원인을 표시"),
            agentDefinitions = listOf(
                AgentDefinition("faq-searcher", "FAQ 검색 담당", "고객 문의에서 검색어를 정리하고 Notion 검색 결과 중 관련 근거를 선택한다.", listOf(FieldDefinition("message", "string", true, "고객 문의")), listOf(FieldDefinition("notionResult", "string", true, "관련 FAQ 근거")), listOf("문의 의도를 보존한다", "가장 관련 높은 FAQ를 선택한다"), listOf("FAQ 내용을 변경하지 않는다", "검색 결과가 없으면 만들지 않는다"), listOf("선택한 FAQ 제목과 문장")),
                AgentDefinition("faq-answer-writer", "FAQ 답변 작성자", "Notion 검색 근거만 사용해 고객 답변 초안을 작성한다.", listOf(FieldDefinition("message", "string", true, "고객 문의"), FieldDefinition("notionResult", "string", true, "FAQ 검색 결과")), listOf(FieldDefinition("draft", "string", true, "답변 초안")), listOf("FAQ 근거를 우선한다", "불확실하면 명시한다"), listOf("근거 없는 정책을 만들지 않는다", "외부 전송을 수행하지 않는다"), listOf("사용한 FAQ 문장")),
            ),
            guideDefinitions = listOf(
                GuideDefinition("slack-mock", "Slack 연결 설정", "MVP에서는 실제 Slack 계정 대신 Mock 채널과 스레드를 사용합니다.", listOf(GuideField("channel", "문의 채널", "text", true, false, "예: #customer-support"), GuideField("approver", "승인자", "text", true, false, "답변을 검토할 담당자"))),
                GuideDefinition("notion-mock", "Notion FAQ 연결 설정", "MVP에서는 실제 Notion 계정 대신 Mock FAQ 결과를 사용합니다.", listOf(GuideField("database", "FAQ 데이터베이스", "text", true, false, "검색할 FAQ 데이터베이스 이름"))),
            ),
        )
        return mapper.writeValueAsString(bundle)
    }
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
            val bundle = normalize(mapper.readValue(raw, MetaAgentDesignBundle::class.java), instruction)
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
        if (bundle.proposal.name.isBlank() || bundle.proposal.capabilities.isEmpty()) invalid()
        if (bundle.agentDefinitions.size != 2 || bundle.agentDefinitions.map { it.key }.distinct().size != bundle.agentDefinitions.size) invalid()
        if (bundle.guideDefinitions.isEmpty() || bundle.guideDefinitions.size > 5 || bundle.guideDefinitions.map { it.key }.distinct().size != bundle.guideDefinitions.size) invalid()
        if (bundle.agentDefinitions.any { it.behaviorRules.isEmpty() || it.forbiddenRules.isEmpty() || it.evidenceRequirements.isEmpty() }) invalid()
    }

    private fun normalize(bundle: MetaAgentDesignBundle, instruction: String): MetaAgentDesignBundle {
        val questions = buildList {
            val hasInbound = instruction.contains("Slack", true) || instruction.contains("슬랙") || instruction.contains("이메일") || instruction.contains("채팅")
            val hasKnowledge = instruction.contains("Notion", true) || instruction.contains("노션") || instruction.contains("FAQ", true) || instruction.contains("문서") || instruction.contains("데이터베이스")
            val hasApproval = instruction.contains("승인") || instruction.contains("검토") || instruction.contains("확인 후") || instruction.contains("바로 보내")
            val hasDestination = instruction.contains("스레드") || instruction.contains("thread", true) || instruction.contains("전송") || instruction.contains("회신") || instruction.contains("답변을 보내") ||
                ((instruction.contains("Slack", true) || instruction.contains("슬랙")) && instruction.contains("답변"))
            if (!hasInbound) add(ClarificationQuestion("inbound", "inbound", "자동화할 업무는 어떤 입력이나 이벤트로 시작되며, 어느 서비스에서 들어오나요?"))
            if (!hasKnowledge) add(ClarificationQuestion("knowledge-source", "knowledgeSource", "결과를 만들 때 참고할 자료는 어느 서비스나 데이터베이스에 있나요?"))
            if (!hasApproval) add(ClarificationQuestion("approval-policy", "approvalPolicy", "완성된 결과를 바로 실행할까요, 담당자 검토와 승인 후 실행할까요?"))
            if (!hasDestination) add(ClarificationQuestion("destination", "destination", "완성된 결과는 어느 서비스의 어느 위치로 전달하거나 저장할까요?"))
        }
        val eligibleAgents = bundle.agentDefinitions.filter { agent ->
            val text = "${agent.key} ${agent.name} ${agent.role}"
            val excluded = listOf("승인", "라우팅", "게시", "전송", "분류").any(text::contains)
            !excluded && (text.contains("검색") || text.contains("FAQ", true) || text.contains("답변") || text.contains("초안"))
        }
        val search = eligibleAgents.firstOrNull { "${it.name} ${it.role}".contains("검색") || "${it.name} ${it.role}".contains("FAQ", true) }
        val answer = eligibleAgents.firstOrNull { it != search && ("${it.name} ${it.role}".contains("답변") || "${it.name} ${it.role}".contains("초안")) }
        val agents = listOfNotNull(search, answer)
        return bundle.copy(clarificationQuestions = questions, agentDefinitions = agents)
    }

    private fun invalid(): Nothing = throw BadRequestException("INVALID_STRUCTURED_OUTPUT", "메타 에이전트 결과가 승인된 스키마와 일치하지 않습니다.")
    private fun summary(input: Map<String, Any?>) = mapOf("fieldCount" to input.size, "instructionChars" to input["instruction"]?.toString()?.length)
    private fun failure(exception: Exception, durationMs: Long) = when (exception) {
        is MetaAgentExecutionException -> MetaAgentFailure(exception.errorCode, exception.errorType, exception.retryable, durationMs, exception.cliExitCode, exception.safeMessage)
        is ApiException -> MetaAgentFailure(exception.code, exception::class.simpleName ?: "ApiException", false, durationMs, safeMessage = exception.message)
        else -> MetaAgentFailure("INVALID_STRUCTURED_OUTPUT", exception::class.simpleName ?: "Exception", false, durationMs, safeMessage = "구조화 출력 검증 실패")
    }
}
