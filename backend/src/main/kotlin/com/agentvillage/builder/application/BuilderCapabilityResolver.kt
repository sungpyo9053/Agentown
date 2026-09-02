package com.agentvillage.builder.application

import com.agentvillage.builder.domain.*

/**
 * Resolves trusted server resources after the LLM design has been normalized.
 * The model never gets to claim that a connector or tool is installed.
 */
class BuilderCapabilityResolver {
    fun resolve(bundle: MetaAgentDesignBundle): ResourcePlan {
        val plan = bundle.proposal.graphPlan
        if (plan == null) return ResourcePlan(emptyList(), emptyList(), listOf("workflow.graph"), false, false)

        val requirements = plan.nodes.map { node -> requirement(node) }
        val bindings = plan.nodes.map { node -> binding(node) }
            .plus(templateBinding(bundle))
            .filterNotNull()
            .distinctBy { "${it.capabilityKey}:${it.resourceKey}" }
        val uncovered = bindings.filter { it.availability == ResourceAvailability.MISSING }
            .map { it.capabilityKey }
            .distinct()
        val simulationReady = bindings.none { it.availability == ResourceAvailability.MISSING && !it.simulationOnly }
        val productionReady = bindings.all {
            it.availability == ResourceAvailability.INSTALLED && !it.simulationOnly && !it.requiresUserAction
        }
        return ResourcePlan(requirements, bindings, uncovered, simulationReady, productionReady)
    }

    private fun requirement(node: WorkflowNodePlan): CapabilityRequirement {
        val type = NodeType.fromWire(node.nodeType)
        val strategy = when (type) {
            NodeType.MANUAL_TRIGGER, NodeType.SCHEDULE_TRIGGER, NodeType.TEXT_INPUT,
            NodeType.DATA_DEDUPLICATE, NodeType.DATA_NORMALIZE, NodeType.QUALITY_CHECK,
            NodeType.DATA_CSV_COMPARE,
            NodeType.TEMPLATE_RENDER, NodeType.WORKFLOW_END, NodeType.CONDITION_BRANCH -> ExecutionStrategy.DETERMINISTIC
            NodeType.AI_CLASSIFY, NodeType.AI_GENERATE -> ExecutionStrategy.AI
            NodeType.HUMAN_APPROVAL -> ExecutionStrategy.HUMAN_APPROVAL
            else -> ExecutionStrategy.API
        }
        return CapabilityRequirement(
            key = node.id,
            capability = node.label,
            reason = "워크플로 노드 ${node.nodeType} 실행에 필요",
            executionStrategy = strategy,
            searchTerms = listOf(node.nodeType, node.label),
        )
    }

    private fun binding(node: WorkflowNodePlan): ResourceBinding {
        val capability = node.id
        return when (NodeType.fromWire(node.nodeType)) {
            NodeType.MANUAL_TRIGGER, NodeType.SCHEDULE_TRIGGER, NodeType.TEXT_INPUT,
            NodeType.DATA_DEDUPLICATE, NodeType.DATA_NORMALIZE, NodeType.QUALITY_CHECK,
            NodeType.DATA_CSV_COMPARE,
            NodeType.TEMPLATE_RENDER, NodeType.WORKFLOW_END, NodeType.CONDITION_BRANCH -> installed(capability, ResourceKind.TOOL, "builtin.${node.nodeType}", node.label, "Agentown 결정론적 런타임")
            NodeType.AI_CLASSIFY, NodeType.AI_GENERATE -> installed(capability, ResourceKind.TOOL, "platform.structured-ai", "Agentown 제공 AI", "구조화 출력과 호출 한도를 적용한 플랫폼 AI")
            NodeType.HUMAN_APPROVAL -> installed(capability, ResourceKind.TOOL, "builtin.human-approval", "사용자 승인", "승인 전 외부 쓰기를 차단하는 서버 상태 머신")
            NodeType.SLACK_NEW_MESSAGE_MOCK, NodeType.SLACK_REPLY_MOCK, NodeType.SLACK_SEND_MOCK -> mock(capability, "connector.slack.mock", "Slack Mock", "실제 Slack OAuth 연결 전 안전한 시뮬레이션")
            NodeType.NOTION_SEARCH_MOCK, NodeType.NOTION_READ_PAGE_MOCK -> mock(capability, "connector.notion.mock", "Notion Mock", "실제 Notion OAuth 연결 전 안전한 시뮬레이션")
            NodeType.KNOWLEDGE_SEARCH_MOCK -> mock(capability, "connector.knowledge.mock", "FAQ/지식 검색 Mock", "실제 지식 소스 연결 전 안전한 시뮬레이션")
            NodeType.FLIGHT_SEARCH_MOCK -> mock(capability, "connector.flight-search.mock", "항공권 검색 Mock", "실제 항공권 검색 연결 전 안전한 가격 조건 시뮬레이션")
            NodeType.EMAIL_SEND_MOCK -> mock(capability, "connector.email.mock", "Email Mock", "실제 이메일 연결 전 안전한 시뮬레이션")
            NodeType.NEWS_SEARCH_MOCK -> mock(capability, "connector.news.mock", "News Mock", "실제 뉴스 소스 연결 전 샘플 데이터 시뮬레이션")
            NodeType.GITHUB_ISSUE_MOCK -> mock(capability, "connector.github.mock", "GitHub Mock", "실제 GitHub 연결 전 샘플 이슈 시뮬레이션")
            NodeType.PARALLEL_MAP_MOCK -> mock(capability, "connector.web-research.mock", "Web Research Mock", "실제 검색 소스 연결 전 병렬 조사 시뮬레이션")
            NodeType.UNRESOLVED_TOOL -> ResourceBinding(capability, ResourceKind.TOOL, "missing.${node.config["toolName"]}", node.label, ResourceAvailability.MISSING, "USER_REQUEST", node.config["reason"]?.toString() ?: "연결 또는 도구 구현 필요")
            null -> ResourceBinding(capability, ResourceKind.TOOL, "missing.${node.nodeType}", node.label, ResourceAvailability.MISSING, "SERVER_CATALOG", "서버 허용 노드 카탈로그에 없는 기능")
        }
    }

    private fun templateBinding(bundle: MetaAgentDesignBundle): ResourceBinding? = bundle.proposal.templateSelection?.let {
        installed("output-template", ResourceKind.SKILL, "template.${it.templateKey}.v${it.version}", "${it.templateKey} v${it.version}", "승인된 출력 스키마·렌더러·품질 규칙")
    }

    private fun installed(capability: String, kind: ResourceKind, key: String, label: String, reason: String) = ResourceBinding(
        capability, kind, key, label, ResourceAvailability.INSTALLED, "SERVER_CATALOG", reason,
    )

    private fun mock(capability: String, key: String, label: String, reason: String) = ResourceBinding(
        capability, ResourceKind.CONNECTOR, key, label, ResourceAvailability.INSTALLED, "SERVER_CATALOG", reason,
        simulationOnly = true, requiresUserAction = true,
    )
}
