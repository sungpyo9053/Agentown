package com.agentvillage.builder

import com.agentvillage.builder.application.AgentRequirementIntakePolicy
import com.agentvillage.builder.application.AgentRequirementRefinement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AgentRequirementIntakePolicyTest {
    @ParameterizedTest
    @ValueSource(strings = [
        "시바 마우스 만들고 싶어",
        "뭔가 쩌는 봇 하나 만들어줘",
        "이거 자동으로 해줘",
        "AI 하나 만들어",
    ])
    fun `underspecified ideas ask for an agent contract instead of guessing`(conversation: String) {
        val result = AgentRequirementIntakePolicy.enforce(
            AgentRequirementRefinement(true, conversation, emptyList()),
            conversation,
        )

        assertThat(result.ready).isFalse()
        assertThat(result.clarificationQuestions.map { it.field })
            .containsExactly("desiredOutcome", "interactionContract", "successConstraints")
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "날씨를 분석해 옷차림을 추천하는 에이전트를 만들어줘",
        "회의 녹취를 요약하고 결정사항과 담당자를 정리해줘",
        "한국어 문장을 영어로 번역하는 봇을 만들어줘",
        "두 송장을 비교해 금액 불일치를 검수하는 에이전트가 필요해",
    ])
    fun `specific agent behavior proceeds without redundant questions`(conversation: String) {
        val result = AgentRequirementIntakePolicy.enforce(
            AgentRequirementRefinement(true, conversation, emptyList()),
            conversation,
        )

        assertThat(result.ready).isTrue()
        assertThat(result.clarificationQuestions).isEmpty()
    }
}
