package com.agentvillage.builder

import com.agentvillage.builder.application.BuilderMvpSupportPolicy
import com.agentvillage.builder.domain.AutomationRequirement
import com.agentvillage.common.exception.BadRequestException
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BuilderMvpSupportPolicyTest {
    @Test
    fun `supported Slack FAQ mock requirement remains allowed`() {
        assertThatCode {
            BuilderMvpSupportPolicy.requireSupported(
                AutomationRequirement(
                    objective = "Slack 문의를 Notion FAQ 근거로 답변한다.",
                    trigger = "Slack 문의 수신",
                    inputs = listOf("고객 문의", "Notion FAQ"),
                    outputs = listOf("승인된 답변 초안"),
                    steps = listOf("문의 수신", "FAQ 검색", "초안 작성", "담당자 승인", "Slack 답변 미리보기"),
                    decisions = listOf("담당자 승인 여부"),
                    exceptions = listOf("FAQ 검색 결과 없음"),
                    humanApprovalRequired = true,
                ),
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `supported schedule news and Slack mock still rejects unsupported local storage`() {
        val requirement = AutomationRequirement(
            objective = "슬랙으로 주식·경제 보고서를 오전 8시에 전송한다.",
            trigger = "오전 8시 정기 실행",
            inputs = listOf("네이버경제뉴스"),
            outputs = listOf("슬랙 메시지", "로컬 저장 결과"),
            steps = listOf("뉴스 자료 수집", "보고서 작성", "사람 승인", "슬랙 전송", "로컬 저장"),
            decisions = listOf("승인 후 전송 여부"),
            exceptions = listOf("정기 뉴스 수집과 네이버경제뉴스 연동은 MVP 범위에 포함되지 않음"),
            humanApprovalRequired = true,
        )

        assertThatThrownBy { BuilderMvpSupportPolicy.requireSupported(requirement) }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("표준 노드 또는 연결")
            .hasMessageContaining("로컬 파일 저장")
    }

    @Test
    fun `draft-only content with explicit no-publish instruction remains supported`() {
        assertThatCode {
            BuilderMvpSupportPolicy.requireSupported(
                AutomationRequirement(
                    objective = "소셜 게시물 문구 초안을 작성하되 실제 게시하지 마.",
                    trigger = "수동 실행",
                    inputs = listOf("캠페인 브리프"),
                    outputs = listOf("화면에 표시할 문구 초안"),
                    steps = listOf("초안 작성", "담당자 승인", "화면 표시"),
                    decisions = emptyList(),
                    exceptions = emptyList(),
                    humanApprovalRequired = true,
                ),
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `commit and production deployment automation is rejected instead of reframed as a ready agent`() {
        assertThatThrownBy {
            BuilderMvpSupportPolicy.requireSupported(
                AutomationRequirement(
                    objective = "커밋과 운영 배포까지 자동화한다.",
                    trigger = "사용자 요청",
                    inputs = listOf("백엔드 변경사항"),
                    outputs = listOf("커밋", "운영 배포"),
                    steps = listOf("커밋 작성", "운영 배포 실행"),
                    decisions = emptyList(),
                    exceptions = emptyList(),
                    humanApprovalRequired = true,
                ),
            )
        }.isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("개발 도구 쓰기·배포")
    }
}
