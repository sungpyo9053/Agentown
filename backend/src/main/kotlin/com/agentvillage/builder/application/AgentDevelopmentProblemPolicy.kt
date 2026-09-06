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
    private val routingRules = listOf(
        AgentDevelopmentRoutingRule(
            id = "clarify-material-ambiguity",
            condition = "문제의 대상, 실제 불편 또는 목적, 원하는 결과, 해결 범위가 역할 분해를 바꿀 만큼 빠져 있다",
            result = AgentDevelopmentApproach.CLARIFY,
        ),
        AgentDevelopmentRoutingRule(
            id = "prefer-one-shot-prompt",
            condition = "도구, 지속 상태, 반복 실행, 병렬 작업, 독립 검증, 분리된 전문 책임 없이 한 번의 좋은 지시로 해결할 수 있다",
            result = AgentDevelopmentApproach.PROMPT_ONLY,
        ),
        AgentDevelopmentRoutingRule(
            id = "create-agent-team-only-for-agentic-work",
            condition = "도구, 지속 상태, 반복 실행, 병렬 작업, 독립 검증 또는 분리된 전문 책임 중 하나 이상이 결과에 실질적으로 필요하다",
            result = AgentDevelopmentApproach.AGENT_TEAM,
        ),
    )

    fun promptInstructions(): String = routingRules.joinToString("\n") { rule ->
        "- ${rule.condition}: recommendedApproach=${rule.result.name}"
    }

    fun requireValid(problem: AgentDevelopmentProblemDefinition) {
        require(problem.problemStatement.isNotBlank())
        require(problem.clarificationQuestions.size <= 3)
        require(problem.clarificationQuestions.map { it.field }.distinct().size == problem.clarificationQuestions.size)
        require((problem.recommendedApproach == AgentDevelopmentApproach.CLARIFY) == !problem.readyForDesign)
        require(problem.readyForDesign == problem.clarificationQuestions.isEmpty())
        if (problem.recommendedApproach == AgentDevelopmentApproach.PROMPT_ONLY) require(problem.suggestedPrompt.isNotBlank())
    }

    fun semanticFallback(issues: List<ValidationIssue>): List<ClarificationQuestion> =
        if (issues.any { it.code.startsWith("MEANING_") }) listOf(ClarificationQuestion(
            id = "problem-scope",
            field = "problemScope",
            question = "제가 문제의 범위를 잘못 추정하지 않도록, 해결하려는 실제 문제와 원하는 최종 결과를 조금 더 구체적으로 알려주세요.",
        )) else emptyList()
}
