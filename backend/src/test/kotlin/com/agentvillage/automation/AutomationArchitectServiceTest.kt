package com.agentvillage.automation

import com.agentvillage.automation.application.AutomationArchitectService
import com.agentvillage.automation.application.AutomationNodeKind
import com.agentvillage.harness.application.HarnessService
import com.agentvillage.harness.domain.Harness
import com.agentvillage.harness.domain.HarnessStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

class AutomationArchitectServiceTest {
    private val harnesses = mock<HarnessService>()
    private val service = AutomationArchitectService(harnesses)
    private val ownerId = UUID.randomUUID()

    @Test
    fun `natural language selects the closest published harness and builds app flow`() {
        val support = Harness(ownerId = ownerId, name = "고객 문의 응대팀", description = "고객 문의 답변을 작성하고 검수한다").apply { status = HarnessStatus.PUBLISHED }
        val news = Harness(ownerId = ownerId, name = "뉴스 요약팀", description = "뉴스 기사를 요약한다").apply { status = HarnessStatus.PUBLISHED }
        whenever(harnesses.list(ownerId)).thenReturn(listOf(news, support))

        val result = service.design(ownerId, "슬랙에 고객 문의가 오면 답변을 만들고 노션에 기록해줘")

        assertThat(result.matchedHarnessId).isEqualTo(support.id)
        assertThat(result.nodes.map { it.kind }).containsExactly(
            AutomationNodeKind.SLACK_TRIGGER,
            AutomationNodeKind.HARNESS,
            AutomationNodeKind.NOTION_ACTION,
            AutomationNodeKind.SLACK_ACTION,
        )
        assertThat(result.edges).hasSize(3)
        assertThat(result.handledBy.map { it.key }).containsExactly("intent-analyst", "harness-analyst", "workflow-architect", "connection-reviewer")
        assertThat(result.analysis.map { it.agentKey }).containsExactlyElementsOf(result.handledBy.map { it.key })
        assertThat(result.analysis[1].summary).contains("고객 문의 응대팀")
        assertThat(result.warnings).hasSize(2)
    }

    @Test
    fun `no published harness returns an honest incomplete proposal`() {
        whenever(harnesses.list(ownerId)).thenReturn(emptyList())

        val result = service.design(ownerId, "매주 보고서를 요약해줘")

        assertThat(result.matchedHarnessId).isNull()
        assertThat(result.nodes.map { it.kind }).containsExactly(AutomationNodeKind.MANUAL_TRIGGER, AutomationNodeKind.HARNESS)
        assertThat(result.warnings.single()).contains("발행 하네스")
        assertThat(result.reply).contains("먼저 선택")
    }
}
