package com.agentvillage.automation.application

import com.agentvillage.harness.application.HarnessService
import com.agentvillage.harness.domain.Harness
import com.agentvillage.harness.domain.HarnessStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

enum class AutomationNodeKind { MANUAL_TRIGGER, SLACK_TRIGGER, HARNESS, NOTION_ACTION, SLACK_ACTION }

data class AutomationAgentRole(val key: String, val name: String, val responsibility: String)
data class AutomationAgentAnalysis(
    val agentKey: String,
    val agentName: String,
    val summary: String,
    val findings: List<String>,
)
data class AutomationNodeProposal(
    val key: String,
    val kind: AutomationNodeKind,
    val label: String,
    val config: Map<String, String> = emptyMap(),
)
data class AutomationEdgeProposal(val source: String, val target: String)
data class AutomationDesignProposal(
    val name: String,
    val reply: String,
    val handledBy: List<AutomationAgentRole>,
    val analysis: List<AutomationAgentAnalysis>,
    val matchedHarnessId: UUID?,
    val matchedHarnessName: String?,
    val nodes: List<AutomationNodeProposal>,
    val edges: List<AutomationEdgeProposal>,
    val warnings: List<String>,
)

@Service
class AutomationArchitectService(private val harnesses: HarnessService) {
    @Transactional(readOnly = true)
    fun design(ownerId: UUID, instruction: String): AutomationDesignProposal {
        val request = instruction.trim()
        val normalized = request.lowercase()
        val published = harnesses.list(ownerId).filter { it.status == HarnessStatus.PUBLISHED }
        val matched = matchHarness(normalized, published)
        val slack = containsAny(normalized, "slack", "슬랙", "채널")
        val notion = containsAny(normalized, "notion", "노션")
        val slackTrigger = slack && containsAny(normalized, "오면", "오면은", "받으면", "수신", "새 메시지", "문의가", "들어오면")
        val slackAction = slack && containsAny(normalized, "보내", "전송", "답변", "응답", "알림", "올려", "게시", "공유")

        val nodes = mutableListOf<AutomationNodeProposal>()
        nodes += if (slackTrigger) {
            AutomationNodeProposal("trigger", AutomationNodeKind.SLACK_TRIGGER, "Slack 메시지 수신")
        } else {
            AutomationNodeProposal("trigger", AutomationNodeKind.MANUAL_TRIGGER, "수동 시작", mapOf("sampleInput" to request))
        }
        nodes += AutomationNodeProposal(
            "harness", AutomationNodeKind.HARNESS, matched?.name ?: "발행 하네스 선택 필요",
            matched?.let { mapOf("harnessId" to it.id.toString()) } ?: emptyMap(),
        )
        if (notion) nodes += AutomationNodeProposal("notion", AutomationNodeKind.NOTION_ACTION, "Notion 페이지 생성", mapOf("contentSource" to "harness.result"))
        if (slackAction) nodes += AutomationNodeProposal("slack-action", AutomationNodeKind.SLACK_ACTION, "Slack 메시지 전송")

        val edges = nodes.zipWithNext().map { (source, target) -> AutomationEdgeProposal(source.key, target.key) }
        val warnings = buildList {
            if (matched == null) add("사용 가능한 발행 하네스가 없습니다. 하네스를 발행한 뒤 실행 노드에서 선택해 주세요.")
            if (slack) add("Slack 계정 연결과 채널 권한은 아직 확인되지 않았습니다.")
            if (notion) add("Notion 계정 연결과 대상 데이터베이스 권한은 아직 확인되지 않았습니다.")
        }
        val flow = nodes.joinToString(" → ") { it.label }
        val reply = if (matched == null) {
            "요청을 이해했습니다. $flow 흐름으로 구성했지만 실행할 발행 하네스를 먼저 선택해야 합니다."
        } else {
            "요청을 이해했습니다. '${matched.name}' 하네스를 사용해 $flow 순서로 자동화 초안을 제안합니다."
        }
        val analysis = listOf(
            AutomationAgentAnalysis(
                "intent-analyst", "업무 의도 분석가",
                if (slackTrigger) "Slack 메시지를 시작 조건으로 판단했습니다." else "사용자가 필요할 때 시작하는 업무로 판단했습니다.",
                buildList {
                    add("요청 길이 ${request.length}자")
                    if (slack) add("Slack 언급 감지")
                    if (notion) add("Notion 언급 감지")
                },
            ),
            AutomationAgentAnalysis(
                "harness-analyst", "보유 하네스 분석가",
                matched?.let { "발행 하네스 ${published.size}개 중 '${it.name}'을 선택했습니다." }
                    ?: "선택할 수 있는 발행 하네스가 없습니다.",
                matched?.let { listOf("이름과 설명을 요청의 핵심어와 비교", "선택 ID ${it.id}") }
                    ?: listOf("DRAFT 하네스는 실행 후보에서 제외"),
            ),
            AutomationAgentAnalysis(
                "workflow-architect", "워크플로우 설계가",
                "${nodes.size}개 노드와 ${edges.size}개 연결을 순차 흐름으로 구성했습니다.",
                listOf(nodes.joinToString(" → ") { it.label }),
            ),
            AutomationAgentAnalysis(
                "connection-reviewer", "연결·권한 검수자",
                if (warnings.isEmpty()) "현재 초안에서 추가 연결 경고를 찾지 못했습니다." else "실행 전에 ${warnings.size}개 항목을 확인해야 합니다.",
                warnings.ifEmpty { listOf("외부 앱 노드 없음") },
            ),
        )
        return AutomationDesignProposal(
            name = request.take(36).ifBlank { "새 업무 자동화" },
            reply = reply,
            handledBy = builtInRoles,
            analysis = analysis,
            matchedHarnessId = matched?.id,
            matchedHarnessName = matched?.name,
            nodes = nodes,
            edges = edges,
            warnings = warnings,
        )
    }

    private fun matchHarness(instruction: String, candidates: List<Harness>): Harness? {
        if (candidates.isEmpty()) return null
        val tokens = instruction.split(Regex("[^0-9a-zA-Z가-힣]+"))
            .map(String::trim).filter { it.length >= 2 && it !in stopWords }.distinct()
        return candidates.maxByOrNull { harness ->
            val corpus = "${harness.name} ${harness.description.orEmpty()}".lowercase()
            tokens.count(corpus::contains) * 10 + if (instruction.contains(harness.name.lowercase())) 100 else 0
        }
    }

    private fun containsAny(value: String, vararg candidates: String) = candidates.any(value::contains)

    companion object {
        private val stopWords = setOf("해줘", "하고", "해서", "으로", "에서", "결과", "업무", "자동화", "만들어")
        val builtInRoles = listOf(
            AutomationAgentRole("intent-analyst", "업무 의도 분석가", "자연어에서 시작 조건과 원하는 결과를 찾습니다."),
            AutomationAgentRole("harness-analyst", "보유 하네스 분석가", "사용자가 발행한 하네스 중 요청에 맞는 팀을 고릅니다."),
            AutomationAgentRole("workflow-architect", "워크플로우 설계가", "분석 결과를 실행 가능한 노드와 엣지로 바꿉니다."),
            AutomationAgentRole("connection-reviewer", "연결·권한 검수자", "외부 앱 연결과 실행 전 확인사항을 점검합니다."),
        )
    }
}
