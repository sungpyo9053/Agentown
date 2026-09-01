package com.agentvillage.builder.application

import com.agentvillage.builder.domain.*

/** Builds the user-facing, runtime-neutral Agent Package contract from trusted domain data. */
class AgentDesignAssembler {
    fun assemble(bundle: MetaAgentDesignBundle): AgentDesign {
        val resources = requireNotNull(bundle.proposal.resourcePlan)
        val workflow = compose(requireNotNull(bundle.proposal.graphPlan))
        val issues = review(bundle, resources, workflow)
        val aiCalls = bundle.proposal.economics?.estimatedAiCallsPerRun
            ?: bundle.proposal.graphPlan.nodes.count { it.nodeType.startsWith("ai.") }
        val review = AgentDesignReview(issues.none { it.severity == DesignIssueSeverity.ERROR }, issues, aiCalls)
        val executionReadiness = if (resources.productionReady) AgentDesignStatus.READY_TO_SIMULATE else AgentDesignStatus.EXECUTION_NOT_CONFIGURED
        return AgentDesign(
            status = if (review.passed) AgentDesignStatus.READY_FOR_REVIEW else AgentDesignStatus.NEEDS_REVISION,
            purpose = bundle.requirement.objective,
            naturalLanguageSummary = bundle.proposal.summary,
            agentKeys = bundle.agentDefinitions.map { it.key },
            toolRequirements = resources.bindings.map {
                ToolRequirement(it.resourceKey, it.label, it.resourceKind, true, it.availability, it.reason)
            },
            workflow = workflow,
            approvalPolicy = ApprovalPolicy(
                required = bundle.requirement.humanApprovalRequired,
                beforeNodeIds = bundle.proposal.graphPlan.nodes.filter { it.nodeType.startsWith("slack.") && it.nodeType.endsWith("mock") }.map { it.id },
                reason = bundle.proposal.approvalPoints.joinToString(", "),
            ),
            retryPolicy = RetryPolicy(maxAttempts = 1, retryableErrors = listOf("MOCK_CONNECTOR_TEMPORARY_FAILURE")),
            assumptions = bundle.requirement.assumptions,
            unresolvedQuestions = bundle.requirement.unresolvedQuestions,
            review = review,
            simulationScenarios = listOf(SimulationScenario(
                name = "기본 Mock 시나리오",
                input = mapOf("text" to "샘플 입력", "message" to "환불은 언제 처리되나요?"),
                expectedStages = bundle.proposal.graphPlan.nodes.map { it.label },
            )),
            executionReadiness = executionReadiness,
        )
    }

    private fun compose(plan: WorkflowGraphPlan): WorkflowDefinition {
        val runtimeNodes = plan.nodes.map { node ->
            WorkflowDesignNode(node.id, kind(node.nodeType), node.label, node.nodeType, node.config["agentKey"]?.toString())
        }
        val start = WorkflowDesignNode("design-start", DesignNodeKind.START, "시작")
        val endPresent = runtimeNodes.any { it.kind == DesignNodeKind.END }
        val end = WorkflowDesignNode("design-end", DesignNodeKind.END, "종료")
        val outgoing = plan.edges.map { it.source }.toSet()
        val tails = runtimeNodes.filter { it.id !in outgoing && it.kind != DesignNodeKind.END }
        return WorkflowDefinition(
            nodes = listOf(start) + runtimeNodes + if (endPresent) emptyList() else listOf(end),
            edges = listOf(WorkflowEdgePlan("design-start-edge", start.id, plan.entryNodeId)) + plan.edges +
                if (endPresent) emptyList() else tails.mapIndexed { index, node -> WorkflowEdgePlan("design-end-edge-$index", node.id, end.id) },
        )
    }

    private fun kind(nodeType: String): DesignNodeKind = when {
        nodeType.endsWith("trigger") -> DesignNodeKind.TRIGGER
        nodeType.startsWith("ai.") -> DesignNodeKind.AGENT
        nodeType in setOf("data.normalize", "data.deduplicate", "data.csv.compare", "quality.check") -> DesignNodeKind.FUNCTION
        nodeType == "template.render" -> DesignNodeKind.TEMPLATE
        nodeType == "condition.branch" -> DesignNodeKind.CONDITION
        nodeType == "human.approval" -> DesignNodeKind.USER_APPROVAL
        nodeType == "workflow.end" -> DesignNodeKind.END
        nodeType.startsWith("slack.") && (nodeType.contains("reply") || nodeType.contains("send")) -> DesignNodeKind.OUTPUT
        else -> DesignNodeKind.TOOL
    }

    private fun review(bundle: MetaAgentDesignBundle, resources: ResourcePlan, workflow: WorkflowDefinition): List<DesignReviewIssue> = buildList {
        resources.bindings.filter { it.availability == ResourceAvailability.MISSING }.forEach {
            add(DesignReviewIssue("RESOURCE_NOT_FOUND", DesignIssueSeverity.ERROR, "사용 가능한 자원을 찾지 못했습니다: ${it.label}", it.resourceKey))
        }
        if (bundle.agentDefinitions.any { it.key !in workflow.nodes.mapNotNull(WorkflowDesignNode::agentKey) }) {
            add(DesignReviewIssue("UNUSED_AGENT", DesignIssueSeverity.ERROR, "실행 그래프에 연결되지 않은 Agent가 있습니다."))
        }
        val aiCalls = bundle.proposal.graphPlan.orEmptyNodes().count { it.nodeType.startsWith("ai.") }
        if (aiCalls > 3) add(DesignReviewIssue("AI_CALL_COST_RISK", DesignIssueSeverity.WARNING, "실행당 AI 호출이 $aiCalls 회입니다. 결정적 단계로 줄일 수 있는지 검토하세요."))
        if (!resources.productionReady) add(DesignReviewIssue("EXECUTION_NOT_CONFIGURED", DesignIssueSeverity.INFO, "설계와 Mock 테스트는 가능하지만 실제 Connector 실행은 설정되지 않았습니다."))
    }

    private fun WorkflowGraphPlan?.orEmptyNodes() = this?.nodes.orEmpty()
}
