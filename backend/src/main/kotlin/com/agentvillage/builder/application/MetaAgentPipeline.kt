package com.agentvillage.builder.application

import com.agentvillage.builder.domain.*
import com.agentvillage.builder.infrastructure.MetaAgentRunRepository
import com.agentvillage.common.exception.BadRequestException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.util.UUID

interface MetaAgentModel {
    fun generate(stage: String, input: Map<String, Any?>): String
}

@Component
class DeterministicMockMetaAgentModel(private val mapper: ObjectMapper) : MetaAgentModel {
    override fun generate(stage: String, input: Map<String, Any?>): String {
        val instruction = input["instruction"]?.toString().orEmpty()
        val value: Any = when (stage) {
            "analyze_business_process" -> AutomationRequirement(
                objective = instruction,
                trigger = if (instruction.contains("Slack", true) || instruction.contains("슬랙")) "Slack 고객 문의 수신" else "수동 실행",
                inputs = listOf("고객 문의"), outputs = listOf("승인된 답변 초안"),
                steps = listOf("문의 수신", "Notion FAQ 검색", "AI 답변 초안", "담당자 승인", "Slack 스레드 답변"),
                decisions = listOf("관련 FAQ 선택", "담당자 승인 여부"), exceptions = listOf("FAQ 검색 결과 없음", "승인 거절"), humanApprovalRequired = true,
            )
            "clarify_requirements" -> buildList {
                val slack = instruction.contains("Slack", true) || instruction.contains("슬랙")
                val notion = instruction.contains("Notion", true) || instruction.contains("노션")
                val approval = instruction.contains("담당자") || instruction.contains("승인") || instruction.contains("검토")
                if (!slack) add(ClarificationQuestion("inbound", "inbound", "고객 문의는 어느 서비스와 채널로 들어오나요?"))
                if (!notion) add(ClarificationQuestion("knowledge-source", "knowledgeSource", "답변에 사용할 자료는 어느 서비스와 데이터베이스에서 찾나요?"))
                if (!approval) add(ClarificationQuestion("approval-policy", "approvalPolicy", "답변을 바로 보낼까요, 담당자 승인 후 보낼까요?"))
                if (!slack) add(ClarificationQuestion("destination", "destination", "완성된 답변은 어느 서비스와 대화 위치로 전송할까요?"))
            }
            "design_automation" -> AutomationProposal("Slack FAQ 답변 자동화", "Slack 문의를 FAQ 근거로 답변 초안화하고 승인 후 회신합니다.", listOf("문의 수신", "FAQ 검색", "답변 생성", "사람 승인", "답변 미리보기"), listOf("Slack Mock", "Notion Mock"), listOf("Slack 답변 직전"), "실패한 노드에서 중단하고 원인을 표시")
            "design_agents" -> listOf(
                AgentDefinition("faq-searcher", "FAQ 검색 담당", "고객 문의에서 검색어를 정리하고 Notion 검색 결과 중 관련 근거를 선택한다.", listOf(FieldDefinition("message", "string", true, "고객 문의")), listOf(FieldDefinition("notionResult", "string", true, "관련 FAQ 근거")), listOf("문의 의도를 보존한다", "가장 관련 높은 FAQ를 선택한다"), listOf("FAQ 내용을 변경하지 않는다", "검색 결과가 없으면 만들지 않는다"), listOf("선택한 FAQ 제목과 문장")),
                AgentDefinition("faq-answer-writer", "FAQ 답변 작성자", "Notion 검색 근거만 사용해 고객 답변 초안을 작성한다.", listOf(FieldDefinition("message", "string", true, "고객 문의"), FieldDefinition("notionResult", "string", true, "FAQ 검색 결과")), listOf(FieldDefinition("draft", "string", true, "답변 초안")), listOf("FAQ 근거를 우선한다", "불확실하면 명시한다"), listOf("근거 없는 정책을 만들지 않는다", "외부 전송을 수행하지 않는다"), listOf("사용한 FAQ 문장")),
            )
            "design_guides" -> listOf(
                GuideDefinition("slack-mock", "Slack 연결 설정", "MVP에서는 실제 Slack 계정 대신 Mock 채널과 스레드를 사용합니다.", listOf(GuideField("channel", "문의 채널", "text", true, help = "예: #customer-support"), GuideField("approver", "승인자", "text", true, help = "답변을 검토할 담당자"))),
                GuideDefinition("notion-mock", "Notion FAQ 연결 설정", "MVP에서는 실제 Notion 계정 대신 Mock FAQ 결과를 사용합니다.", listOf(GuideField("database", "FAQ 데이터베이스", "text", true, help = "검색할 FAQ 데이터베이스 이름"))),
            )
            else -> input
        }
        return mapper.writeValueAsString(value)
    }
}

data class PipelineContext(val traceId: UUID, val workspaceId: UUID, val conversationId: UUID, val workflowId: UUID)

@Component
class StructuredMetaAgentPipeline(
    private val model: MetaAgentModel,
    private val mapper: ObjectMapper,
    private val runs: MetaAgentRunRepository,
) {
    fun analyze(context: PipelineContext, instruction: String): Pair<AutomationRequirement, List<ClarificationQuestion>> {
        val input = mapOf("instruction" to instruction)
        val requirement = invoke(context, "analyze_business_process", input, AutomationRequirement::class.java)
        val questions: List<ClarificationQuestion> = invokeList(context, "clarify_requirements", input)
        return requirement to questions
    }

    fun design(context: PipelineContext, instruction: String): Triple<AutomationProposal, List<AgentDefinition>, List<GuideDefinition>> {
        val input = mapOf("instruction" to instruction)
        val proposal = invoke(context, "design_automation", input, AutomationProposal::class.java)
        val agents: List<AgentDefinition> = invokeList(context, "design_agents", input)
        val guides: List<GuideDefinition> = invokeList(context, "design_guides", input)
        require(proposal.capabilities.isNotEmpty() && agents.isNotEmpty() && guides.isNotEmpty()) { "empty structured design" }
        return Triple(proposal, agents, guides)
    }

    fun record(context: PipelineContext, stage: String, inputCount: Int, outputCount: Int) {
        runs.save(MetaAgentRun(traceId = context.traceId, workspaceId = context.workspaceId, conversationId = context.conversationId, workflowId = context.workflowId, stage = stage, status = "SUCCEEDED", inputSummary = mapOf("fieldCount" to inputCount), outputSummary = mapOf("itemCount" to outputCount)))
    }

    private fun <T> invoke(context: PipelineContext, stage: String, input: Map<String, Any?>, type: Class<T>): T = try {
        val output = mapper.readValue(model.generate(stage, input), type)
        record(context, stage, input.size, 1); output
    } catch (exception: Exception) {
        runs.save(MetaAgentRun(traceId = context.traceId, workspaceId = context.workspaceId, conversationId = context.conversationId, workflowId = context.workflowId, stage = stage, status = "FAILED", inputSummary = mapOf("fieldCount" to input.size), errorCode = "INVALID_STRUCTURED_OUTPUT"))
        throw BadRequestException("INVALID_STRUCTURED_OUTPUT", "$stage 결과가 승인된 스키마와 일치하지 않습니다.")
    }

    private inline fun <reified T> invokeList(context: PipelineContext, stage: String, input: Map<String, Any?>): List<T> = try {
        val output: List<T> = mapper.readValue(model.generate(stage, input), object : TypeReference<List<T>>() {})
        record(context, stage, input.size, output.size); output
    } catch (exception: Exception) {
        runs.save(MetaAgentRun(traceId = context.traceId, workspaceId = context.workspaceId, conversationId = context.conversationId, workflowId = context.workflowId, stage = stage, status = "FAILED", inputSummary = mapOf("fieldCount" to input.size), errorCode = "INVALID_STRUCTURED_OUTPUT"))
        throw BadRequestException("INVALID_STRUCTURED_OUTPUT", "$stage 결과가 승인된 스키마와 일치하지 않습니다.")
    }
}
