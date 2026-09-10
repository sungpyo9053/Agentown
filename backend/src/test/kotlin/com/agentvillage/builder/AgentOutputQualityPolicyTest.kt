package com.agentvillage.builder

import com.agentvillage.builder.application.AgentOutputQualityPolicy
import com.agentvillage.builder.application.agentDevelopmentPrompt
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AgentOutputQualityPolicyTest {
    @Test
    fun `quality guidance preserves facts while enabling scoped recommendations`() {
        assertThat(AgentOutputQualityPolicy.instructions).contains(
            "대상 식별자는 원자료의 값을 그대로 유지한다",
            "전체 요약이나 검수 메모를 새 대상 식별자로 만들어 결과 행에 추가하지 않는다",
            "함께 발생한 사건만으로 인과관계를 확정하지 않는다",
            "원문에 확정된 절차·제약·약속은 미확정으로 낮추지 않는다",
            "구체적인 안과 선택 이유",
            "단순 추출·번역·원문 요약 역할에는 새 기획",
            "새 JSON 필드를 만들지 않는다",
            "필수 조건이 충족되지 않으면 해당 안은 미충족 또는 진행 불가",
            "조건 완화·대체안 채택에는 사용자의 별도 결정",
        )
    }

    @Test
    fun `design policy adds no fixed role graph or external capability`() {
        val prompt = agentDevelopmentPrompt("사용자가 제공한 자료를 검토해줘")
        assertThat(prompt).contains(AgentOutputQualityPolicy.instructions)
        assertThat(prompt).contains("별도 에이전트나 도구를 추가하지 마세요")
        assertThat(prompt).contains("실제 안을 만드는 책임", "제안 자체를 막는 규칙을 넣지 마세요")
        assertThat(prompt).endsWith("사용자가 제공한 자료를 검토해줘")
    }
}
