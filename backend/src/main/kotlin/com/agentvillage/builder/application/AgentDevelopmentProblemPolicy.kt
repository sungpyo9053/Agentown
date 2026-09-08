package com.agentvillage.builder.application

import com.agentvillage.builder.domain.ClarificationQuestion
import com.agentvillage.builder.domain.ValidationIssue

enum class AgentDevelopmentApproach { CLARIFY, PROMPT_ONLY, AGENT_TEAM }

data class AgentDevelopmentRoutingRule(
    val id: String,
    val condition: String,
    val result: AgentDevelopmentApproach,
)

/**
 * The extension point for problem-to-agent routing. New product policy belongs here as one rule
 * or one small resolver function; the conversation and design engines must not gain scenario cases.
 */
object AgentDevelopmentProblemPolicy {
    const val MAX_CLARIFICATION_QUESTIONS = 10
    const val MAX_CLARIFICATION_ROUNDS = 10
    private val routingRules = listOf(
        AgentDevelopmentRoutingRule(
            id = "resolve-topic-boundary-first",
            condition = "기존 대화와 최신 요청의 주제 또는 원하는 결과가 달라 보이고 사용자가 둘의 관계를 확인하지 않았다면, 새 요청인지 기존 문제의 확장인지 한 질문으로 먼저 확인한다. 이 답변 전에는 기존 주제의 세부 질문을 함께 묻거나 두 요청을 합치지 않는다. 별도 요청이라고 확인하면 이전 주제의 조건은 현재 기획서에서 제외한다",
            result = AgentDevelopmentApproach.CLARIFY,
        ),
        AgentDevelopmentRoutingRule(
            id = "clarify-material-ambiguity",
            condition = "문제의 대상, 실제 불편 또는 목적, 원하는 결과, 해결 범위가 역할 분해를 바꿀 만큼 빠져 있다. 특히 불편이나 바람만 말한 짧은 요청은 도메인을 추론할 수 있어도 먼저 확인한다. 선택 카드의 넓은 대상 분류와 결과물 형식만으로 실제 불편이 확인됐다고 판단하지 않는다. 핵심 사용 조건이 미확인이라면 이를 가정으로 떠넘긴 PROMPT_ONLY보다 추가 질문을 우선한다",
            result = AgentDevelopmentApproach.CLARIFY,
        ),
        AgentDevelopmentRoutingRule(
            id = "prefer-one-shot-prompt",
            condition = "핵심 사용 조건과 실제 목적이 확인되었고, 필요한 작업을 먼저 분해해 본 결과 하나의 작성·변환 작업으로 충분하며 별도 근거 수집·전문 검토·독립 검증이 결과의 수용 기준에 필요하지 않다. 단발성 요청 또는 기획서·시안이라는 결과물 형식만으로 PROMPT_ONLY를 고르지 않는다. 단순 문장 교정·번역 등 실제로 한 번에 끝나는 요청에는 불필요한 에이전트를 만들지 않는다",
            result = AgentDevelopmentApproach.PROMPT_ONLY,
        ),
        AgentDevelopmentRoutingRule(
            id = "create-agent-team-only-for-agentic-work",
            condition = "도구, 지속 상태, 반복 실행, 병렬 작업, 독립 검증 또는 분리된 전문 책임 중 하나 이상이 결과에 실질적으로 필요하다. 사용자가 전문 역할을 명시하지 않아도 수용 가능한 결과에 필요한 책임을 도출한다. 이미 대상과 범위가 확정된 복합 문제는 한 번만 수행하는 기획 작업이어도 역할별 산출물과 상호 검토가 필요하면 AGENT_TEAM으로 판단한다",
            result = AgentDevelopmentApproach.AGENT_TEAM,
        ),
    )

    fun promptInstructions(): String = routingRules.joinToString("\n") { rule ->
        "- ${rule.condition}: recommendedApproach=${rule.result.name}"
    }

    fun remainingQuestions(asked: Int, completedRounds: Int): Int =
        if (completedRounds >= MAX_CLARIFICATION_ROUNDS) 0
        else (MAX_CLARIFICATION_QUESTIONS - asked).coerceAtLeast(0)

    fun requireValid(problem: AgentDevelopmentProblemDefinition, remainingQuestions: Int) {
        require(problem.problemStatement.isNotBlank())
        require(problem.clarificationQuestions.size <= minOf(3, remainingQuestions))
        require(problem.clarificationQuestions.map { it.field }.distinct().size == problem.clarificationQuestions.size)
        require((problem.recommendedApproach == AgentDevelopmentApproach.CLARIFY) == !problem.readyForDesign)
        require(problem.readyForDesign == problem.clarificationQuestions.isEmpty())
        if (problem.readyForDesign) {
            require(problem.targetUser.isNotBlank())
            require(problem.desiredOutcome.isNotBlank())
            require(problem.scope.isNotBlank())
        }
        if (problem.recommendedApproach == AgentDevelopmentApproach.PROMPT_ONLY) require(problem.suggestedPrompt.isNotBlank())
        if (remainingQuestions == 0) require(problem.recommendedApproach != AgentDevelopmentApproach.CLARIFY)
    }

    /**
     * Canonicalize redundant transport fields without changing the model's routing decision.
     * The JSON contract intentionally carries both an approach and UI convenience fields; a
     * disagreement between them must not turn an otherwise usable answer into a failed project.
     */
    fun canonicalize(problem: AgentDevelopmentProblemDefinition, remainingQuestions: Int): AgentDevelopmentProblemDefinition {
        val questions = if (problem.recommendedApproach == AgentDevelopmentApproach.CLARIFY) {
            problem.clarificationQuestions
                .distinctBy { it.field }
                .take(minOf(3, remainingQuestions))
        } else {
            emptyList()
        }
        return problem.copy(
            readyForDesign = problem.recommendedApproach != AgentDevelopmentApproach.CLARIFY,
            clarificationQuestions = questions,
        )
    }

    fun semanticFallback(issues: List<ValidationIssue>): List<ClarificationQuestion> =
        if (issues.isNotEmpty() && issues.all { it.code.startsWith("MEANING_") }) listOf(ClarificationQuestion(
            id = "problem-scope",
            field = "problemScope",
            question = "제가 문제의 범위를 잘못 추정하지 않도록, 해결하려는 실제 문제와 원하는 최종 결과를 조금 더 구체적으로 알려주세요.",
        )) else emptyList()
}
