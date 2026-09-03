package com.agentvillage.builder.application

import com.agentvillage.builder.domain.*
import com.agentvillage.common.exception.BadRequestException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.security.MessageDigest

data class NodeSimulation(val output: Map<String, Any?>, val pauses: Boolean = false)

private object FixedOutputRenderer {
    fun render(rendererKey: String, input: Map<String, Any?>): String = when (rendererKey) {
        "slack.market-news.v1" -> input["report"]?.toString() ?: input["result"]?.toString() ?: "시장 뉴스 보고서 생성 결과가 없습니다."
        "article.plain-text.v1", "plain-text.v1" -> input["result"]?.toString() ?: input["draft"]?.toString().orEmpty()
        "table.markdown.v1" -> {
            val rows = input["changedRows"] as? List<*> ?: emptyList<Any>()
            if (rows.isEmpty()) "변경된 행이 없습니다." else rows.joinToString(prefix = "| 변경 내용 |\n|---|\n", separator = "\n") { "| ${it.toString().replace("|", "\\|")} |" }
        }
        else -> throw BadRequestException("OUTPUT_RENDERER_NOT_ALLOWED", "등록되지 않은 출력 렌더러입니다: $rendererKey")
    }
}

interface WorkflowNodeContract {
    val type: NodeType
    val requiredPermissions: Set<String>
    val riskLevel: String get() = type.riskLevel
    fun validateConfig(config: Map<String, Any?>): List<String>
    fun validateInput(input: Map<String, Any?>): List<String>
    fun simulate(config: Map<String, Any?>, input: Map<String, Any?>): NodeSimulation
    fun execute(config: Map<String, Any?>, input: Map<String, Any?>): NodeSimulation = simulate(config, input)
}

private class SimpleNodeContract(
    override val type: NodeType,
    override val requiredPermissions: Set<String> = emptySet(),
    private val requiredConfig: Set<String> = emptySet(),
    private val inputValidator: (Map<String, Any?>) -> List<String> = { emptyList() },
    private val simulator: (Map<String, Any?>, Map<String, Any?>) -> NodeSimulation,
) : WorkflowNodeContract {
    override fun validateConfig(config: Map<String, Any?>) = requiredConfig.filter { config[it] == null || config[it].toString().isBlank() }.map { "필수 설정 '$it'이 없습니다." }
    override fun validateInput(input: Map<String, Any?>) = inputValidator(input)
    override fun simulate(config: Map<String, Any?>, input: Map<String, Any?>) = simulator(config, input)
}

private fun csvRows(value: Any?): List<Map<String, String>> = when (value) {
    is List<*> -> value.mapNotNull { row ->
        (row as? Map<*, *>)?.entries?.associate { it.key.toString() to (it.value?.toString() ?: "") }
    }
    is String -> {
        val lines = value.lineSequence().filter(String::isNotBlank).toList()
        if (lines.isEmpty()) emptyList() else {
            val header = parseCsvLine(lines.first())
            lines.drop(1).map { line -> header.zip(parseCsvLine(line) + List(header.size) { "" }).take(header.size).toMap() }
        }
    }
    else -> emptyList()
}

private fun parseCsvLine(line: String): List<String> {
    val fields = mutableListOf<String>(); val current = StringBuilder(); var quoted = false; var index = 0
    while (index < line.length) {
        val char = line[index]
        when {
            char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> { current.append('"'); index++ }
            char == '"' -> quoted = !quoted
            char == ',' && !quoted -> { fields += current.toString(); current.clear() }
            else -> current.append(char)
        }
        index++
    }
    fields += current.toString()
    return fields
}

@Component
class WorkflowNodeCatalog {
    private val contracts = listOf(
        SimpleNodeContract(NodeType.MANUAL_TRIGGER) { _, input ->
            val inquiry = input["customerInquiry"] ?: input["question"] ?: input["message"] ?: input["text"]
            NodeSimulation(if (inquiry == null) input else input + mapOf(
                "customerInquiry" to (input["customerInquiry"] ?: inquiry),
                "question" to (input["question"] ?: inquiry),
                "message" to (input["message"] ?: inquiry),
                "text" to (input["text"] ?: inquiry),
            ))
        },
        SimpleNodeContract(NodeType.SCHEDULE_TRIGGER, requiredConfig = setOf("cron", "timezone")) { config, input -> NodeSimulation(input + mapOf("scheduledFor" to config["cron"], "timezone" to config["timezone"])) },
        SimpleNodeContract(NodeType.TEXT_INPUT) { _, input -> NodeSimulation(input) },
        SimpleNodeContract(NodeType.NEWS_SEARCH_MOCK, requiredConfig = setOf("source", "query", "lookbackHours")) { config, input ->
            NodeSimulation(input + ("newsItems" to listOf(
                mapOf("title" to "주요 시장 뉴스", "summary" to "검증용 시장 뉴스 요약", "url" to "https://example.com/mock-news", "publishedAt" to "2026-08-26T08:00:00+09:00", "source" to config["source"]),
            )))
        },
        SimpleNodeContract(NodeType.KNOWLEDGE_SEARCH_MOCK, requiredConfig = setOf("source", "queryField", "connectionStatus")) { config, input ->
            val supplied = input["mockSearchResults"] as? List<*>
            val candidates = supplied ?: listOf(mapOf("title" to "배송 FAQ", "content" to "배송은 주문 후 영업일 기준 2~3일 이내 도착하며 지연 시 담당자가 배송 상태를 확인합니다.", "source" to config["source"]))
            val inquiry = (input[config["queryField"]?.toString()] ?: input["customerInquiry"] ?: input["question"] ?: input["message"] ?: input["text"] ?: "").toString()
            val inquiryTerms = relevantTerms(inquiry)
            val results = candidates.mapNotNull { it as? Map<*, *> }.filter { result ->
                val content = listOf(result["title"], result["content"]).joinToString(" ") { it?.toString().orEmpty() }.trim()
                content.isNotBlank() && inquiryTerms.intersect(relevantTerms(content)).isNotEmpty()
            }
            NodeSimulation(input + mapOf(
                "customerInquiry" to inquiry,
                "faqResults" to results,
                "evidenceFound" to results.isNotEmpty(),
                "needsAssigneeReview" to results.isEmpty(),
                "externalCallPerformed" to false,
            ))
        },
        SimpleNodeContract(NodeType.FLIGHT_SEARCH_MOCK, requiredConfig = setOf("source", "connectionStatus", "maximumPrice")) { config, input ->
            val price = (input["mockFlightPrice"] as? Number)?.toLong() ?: 250_000L
            val maximumPrice = (config["maximumPrice"] as Number).toLong()
            NodeSimulation(input + mapOf(
                "price" to price,
                "priceWithinBudget" to (price <= maximumPrice),
                "searchResult" to mapOf("price" to price, "currency" to "KRW", "source" to config["source"]),
                "externalCallPerformed" to false,
            ))
        },
        SimpleNodeContract(NodeType.GITHUB_ISSUE_MOCK, requiredConfig = setOf("repository", "connectionStatus")) { _, input ->
            NodeSimulation(input + mapOf("issueTitle" to (input["issueTitle"] ?: "로그인 시 오류가 발생합니다"), "issueBody" to (input["issueBody"] ?: "재현 가능한 오류 내용"), "externalCallPerformed" to false))
        },
        SimpleNodeContract(NodeType.PARALLEL_MAP_MOCK, requiredConfig = setOf("items", "operation", "maxConcurrency")) { config, input ->
            val items = (config["items"] as? List<*>)?.map { it.toString() }.orEmpty()
            NodeSimulation(input + mapOf("parallelResults" to items.map { mapOf("subject" to it, "result" to "$it 제품 발표·가격 변화 Mock 조사 결과") }, "externalCallPerformed" to false))
        },
        SimpleNodeContract(NodeType.UNRESOLVED_TOOL, requiredConfig = setOf("toolName", "connectionStatus", "reason")) { config, input ->
            NodeSimulation(input + mapOf("requiresUserAction" to true, "unresolvedTool" to config["toolName"], "reason" to config["reason"], "externalCallPerformed" to false))
        },
        SimpleNodeContract(NodeType.DATA_CSV_COMPARE, requiredConfig = setOf("keyColumns", "comparisonMode"), inputValidator = { input ->
            buildList {
                if (input["csvA"] == null && input["rowsA"] == null) add("CSV 비교 입력 'csvA' 또는 'rowsA'가 필요합니다.")
                if (input["csvB"] == null && input["rowsB"] == null) add("CSV 비교 입력 'csvB' 또는 'rowsB'가 필요합니다.")
            }
        }) { config, input ->
            val before = csvRows(input["csvA"] ?: input["rowsA"])
            val after = csvRows(input["csvB"] ?: input["rowsB"])
            val requestedKeys = (config["keyColumns"] as? List<*>)?.map { it.toString() }.orEmpty()
            val keyColumns = requestedKeys.filterNot { it == "사용자 지정 키" }.ifEmpty { (before.firstOrNull() ?: after.firstOrNull()).orEmpty().keys.take(1) }
            val keyOf: (Map<String, String>) -> String = { row -> keyColumns.joinToString("|") { row[it].orEmpty() } }
            val beforeByKey = before.associateBy(keyOf); val afterByKey = after.associateBy(keyOf)
            val added = (afterByKey.keys - beforeByKey.keys).sorted().map { mapOf("changeType" to "ADDED", "key" to it, "after" to afterByKey.getValue(it)) }
            val removed = (beforeByKey.keys - afterByKey.keys).sorted().map { mapOf("changeType" to "REMOVED", "key" to it, "before" to beforeByKey.getValue(it)) }
            val modified = (beforeByKey.keys intersect afterByKey.keys).sorted().filter { beforeByKey[it] != afterByKey[it] }.map { mapOf("changeType" to "MODIFIED", "key" to it, "before" to beforeByKey.getValue(it), "after" to afterByKey.getValue(it)) }
            NodeSimulation(input + mapOf("addedRows" to added, "removedRows" to removed, "modifiedRows" to modified, "changedRows" to (added + removed + modified), "externalCallPerformed" to false))
        },
        SimpleNodeContract(NodeType.DATA_DEDUPLICATE, requiredConfig = setOf("key")) { _, input ->
            val items = (input["newsItems"] as? List<*>)?.distinctBy { it.toString() }.orEmpty()
            NodeSimulation(input + ("newsItems" to items) + ("deduplicatedCount" to items.size))
        },
        SimpleNodeContract(NodeType.DATA_NORMALIZE) { _, input -> NodeSimulation(input + mapOf("normalizedText" to (input["message"] ?: input["text"] ?: "").toString().trim())) },
        SimpleNodeContract(NodeType.QUALITY_CHECK) { _, input -> NodeSimulation(input + mapOf("qualityPassed" to input.values.any { it.toString().isNotBlank() })) },
        SimpleNodeContract(NodeType.TEMPLATE_RENDER, requiredConfig = setOf("rendererKey")) { config, input -> NodeSimulation(input + mapOf("rendered" to FixedOutputRenderer.render(config["rendererKey"].toString(), input))) },
        SimpleNodeContract(NodeType.WORKFLOW_END) { _, input -> NodeSimulation(input) },
        SimpleNodeContract(NodeType.CONDITION_BRANCH, requiredConfig = setOf("expression")) { _, input -> NodeSimulation(input) },
        SimpleNodeContract(NodeType.AI_CLASSIFY, requiredConfig = setOf("categories")) { config, input -> NodeSimulation(input + ("category" to (config["categories"] as? List<*>)?.firstOrNull().toString())) },
        SimpleNodeContract(NodeType.AI_GENERATE, requiredConfig = setOf("instruction")) { config, input ->
            val evidence = (input["faqResults"] as? List<*>)
                ?.mapNotNull { (it as? Map<*, *>)?.get("content")?.toString() }
                ?.filter(String::isNotBlank)
                .orEmpty()
            val generated = when {
                evidence.isNotEmpty() -> "FAQ 근거에 따르면 ${evidence.joinToString(" ")}"
                input["newsItems"] != null -> "주요 AI 뉴스 3개를 한국어로 요약한 샘플 결과입니다. 각 항목의 출처와 핵심 내용을 확인해 주세요."
                input["parallelResults"] != null -> "세 경쟁사의 제품 발표와 가격 변화를 비교한 샘플 보고서입니다. 조사 근거별 차이를 함께 표시합니다."
                input["changedRows"] != null -> "결정적 CSV 비교 결과에서 영향이 큰 변경 행을 우선 정리했습니다."
                input["issueBody"] != null -> "제공된 버그 이슈를 재현하기 위한 사전 조건, 실행 순서, 기대 결과와 실제 결과 초안입니다."
                else -> "제공된 입력을 출력 스키마에 맞춰 처리한 샘플 결과입니다."
            }
            val outputField = config["outputField"]?.toString()
            val generatedFields = if (outputField.isNullOrBlank()) mapOf("result" to generated, "draft" to generated, "summary" to generated, "report" to generated, "reproductionSteps" to generated) else mapOf(outputField to generated)
            NodeSimulation(input + generatedFields + if (outputField == "draftResponse") mapOf("needsAssigneeReview" to false) else emptyMap())
        },
        SimpleNodeContract(NodeType.HUMAN_APPROVAL, requiredConfig = setOf("approver")) { _, input -> NodeSimulation(input, pauses = true) },
        SimpleNodeContract(NodeType.SLACK_NEW_MESSAGE_MOCK, setOf("slack:messages:read")) { _, input -> NodeSimulation(input + ("message" to (input["message"] ?: ""))) },
        SimpleNodeContract(NodeType.SLACK_REPLY_MOCK, setOf("slack:messages:write")) { _, input -> NodeSimulation(input + mapOf("wouldSend" to true, "message" to (input["draft"] ?: input["message"] ?: ""), "externalCallPerformed" to false)) },
        SimpleNodeContract(NodeType.SLACK_SEND_MOCK, setOf("slack:messages:write"), setOf("channel", "rendererKey")) { config, input -> NodeSimulation(input + mapOf(
            "wouldSend" to true, "channel" to config["channel"],
            "message" to FixedOutputRenderer.render(config["rendererKey"].toString(), input), "externalCallPerformed" to false,
        )) },
        SimpleNodeContract(NodeType.EMAIL_SEND_MOCK, setOf("email:send"), setOf("recipient", "rendererKey", "connectionStatus")) { config, input -> NodeSimulation(input + mapOf(
            "wouldSend" to true, "recipient" to config["recipient"], "message" to FixedOutputRenderer.render(config["rendererKey"].toString(), input), "externalCallPerformed" to false,
        )) },
        SimpleNodeContract(NodeType.NOTION_SEARCH_MOCK, setOf("notion:read"), setOf("database")) { _, input -> NodeSimulation(input + ("notionResult" to "환불은 승인 후 영업일 기준 3~5일 이내 처리됩니다.")) },
        SimpleNodeContract(NodeType.NOTION_READ_PAGE_MOCK, setOf("notion:read"), setOf("pageId")) { _, input -> NodeSimulation(input) },
    ).associateBy { it.type.wireName }

    fun require(type: String): WorkflowNodeContract = contracts[type] ?: throw BadRequestException("NODE_TYPE_NOT_ALLOWED", "허용되지 않은 노드입니다: $type")
    fun allowedTypes(): Set<String> = contracts.keys
}

private fun relevantTerms(value: String): Set<String> = Regex("[가-힣A-Za-z0-9]{2,}")
    .findAll(value.lowercase())
    .map { it.value.replace(Regex("(에서|으로|에게|부터|까지|은|는|이|가|을|를|과|와|도|만)$"), "") }
    .filterNot { it in setOf("언제", "어떻게", "해주세요", "알려주세요", "문의", "faq", "관련", "대한") }
    .toSet()

@Component
class WorkflowGraphValidator(private val catalog: WorkflowNodeCatalog, private val mapper: ObjectMapper) {
    fun hash(graph: WorkflowGraph): String = MessageDigest.getInstance("SHA-256")
        .digest(mapper.writeValueAsBytes(graph)).joinToString("") { "%02x".format(it) }

    fun validate(graph: WorkflowGraph): WorkflowValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        val byId = graph.nodes.associateBy { it.id }
        if (byId.size != graph.nodes.size) issues += ValidationIssue("DUPLICATE_NODE_ID", "노드 ID가 중복됩니다.")
        graph.nodes.forEach { node ->
            val contract = runCatching { catalog.require(node.nodeType) }.getOrElse {
                issues += ValidationIssue("NODE_TYPE_NOT_ALLOWED", it.message ?: "허용되지 않은 노드", node.id); return@forEach
            }
            contract.validateConfig(node.config).forEach { issues += ValidationIssue("INVALID_NODE_CONFIG", it, node.id) }
            if (node.connectionId != null) issues += ValidationIssue("MOCK_CONNECTION_ONLY", "MVP Mock 노드는 connection_id를 사용하지 않습니다.", node.id)
        }
        if (graph.entryNodeId !in byId) issues += ValidationIssue("INVALID_ENTRY", "시작 노드가 그래프에 없습니다.")
        graph.edges.forEach { edge ->
            if (edge.source !in byId || edge.target !in byId) issues += ValidationIssue("INVALID_EDGE", "존재하지 않는 노드를 연결합니다: ${edge.id}")
            if (edge.source == edge.target) issues += ValidationIssue("SELF_EDGE", "자기 자신으로 연결할 수 없습니다.", edge.source)
            if (edge.bindings.isEmpty()) issues += ValidationIssue("EDGE_BINDING_REQUIRED", "노드 사이 입력·출력 바인딩이 필요합니다: ${edge.id}")
        }
        if (hasCycle(graph)) issues += ValidationIssue("CYCLE", "MVP 워크플로우에는 순환을 허용하지 않습니다.")
        val reachable = reachableFrom(graph, graph.entryNodeId)
        graph.nodes.filter { it.id !in reachable }.forEach { issues += ValidationIssue("UNREACHABLE_NODE", "시작점에서 도달할 수 없습니다.", it.id) }
        graph.nodes.filter { it.nodeType in setOf(NodeType.SLACK_REPLY_MOCK.wireName, NodeType.SLACK_SEND_MOCK.wireName, NodeType.EMAIL_SEND_MOCK.wireName) }.forEach { reply ->
            if (pathExistsWithoutApproval(graph, graph.entryNodeId, reply.id)) {
                issues += ValidationIssue("WRITE_REQUIRES_APPROVAL", "Slack 답변 전 모든 경로에 담당자 승인이 필요합니다.", reply.id)
            }
        }
        graph.nodes.filter { it.nodeType == NodeType.CONDITION_BRANCH.wireName }.forEach { branch ->
            val outgoing = graph.edges.filter { it.source == branch.id }
            if (outgoing.isEmpty() || outgoing.any { parseBranchCondition(it.condition) == null }) {
                issues += ValidationIssue("INVALID_BRANCH_EDGE", "조건 분기의 edge condition은 field=value 형식이어야 합니다.", branch.id)
            }
            if (outgoing.map { it.condition }.distinct().size != outgoing.size) {
                issues += ValidationIssue("DUPLICATE_BRANCH_CONDITION", "조건 분기의 edge condition이 중복됩니다.", branch.id)
            }
        }
        return WorkflowValidationResult(issues.isEmpty(), graphHash = hash(graph), issues = issues)
    }

    fun validate(
        graph: WorkflowGraph,
        requirement: AutomationRequirement,
        proposal: AutomationProposal,
        agents: List<AgentDefinition>,
        sourceInstruction: String? = null,
    ): WorkflowValidationResult {
        val structural = validate(graph)
        val issues = structural.issues +
            sourceInstruction?.takeIf(String::isNotBlank).orEmpty().let { source -> if (source.isBlank()) emptyList() else requirementFidelityIssues(source, requirement) } +
            semanticIssues(graph, requirement, proposal, agents)
        return structural.copy(valid = issues.isEmpty(), issues = issues)
    }

    private fun requirementFidelityIssues(sourceInstruction: String, requirement: AutomationRequirement): List<ValidationIssue> {
        val source = sourceInstruction.lowercase()
        val structured = listOf(
            requirement.objective,
            requirement.trigger,
            requirement.inputs.joinToString(" "),
            requirement.outputs.joinToString(" "),
            requirement.steps.joinToString(" "),
            requirement.decisions.joinToString(" "),
        ).joinToString(" ").lowercase()
        val issues = mutableListOf<ValidationIssue>()

        fun compareFacet(label: String, sourceHas: Boolean, structuredHas: Boolean, rejectAddition: Boolean = false) {
            if (sourceHas && !structuredHas) issues += ValidationIssue("MEANING_REQUIREMENT_DROPPED", "사용자 요청의 '$label' 의미가 구조화 요구사항에서 누락되었습니다.")
            if (rejectAddition && !sourceHas && structuredHas) issues += ValidationIssue("MEANING_REQUIREMENT_ADDED", "사용자가 요청하지 않은 '$label' 의미가 구조화 요구사항에 추가되었습니다.")
        }

        compareFacet("Slack", containsAny(source, "slack", "슬랙"), containsAny(structured, "slack", "슬랙"), rejectAddition = true)
        compareFacet(
            "Notion/FAQ",
            containsAny(source, "notion", "노션", "faq", "도움말", "지원 문서", "지식 문서"),
            containsAny(structured, "notion", "노션", "faq", "도움말", "지원 문서", "지식 문서"),
            rejectAddition = true,
        )
        compareFacet("분류", containsAny(source, "분류", "카테고리", "classify", "classification"), containsAny(structured, "분류", "카테고리", "classify", "classification"))
        compareFacet("생성", requestsGeneration(source), requestsGeneration(structured))
        val sourceApproval = !containsAny(source, "승인 없이", "검토 없이", "승인 불필요") && (
            containsAny(source, "승인") ||
                Regex("검토\\s*(후|하고|한 뒤|를 거쳐|가 끝나면)").containsMatchIn(source)
            )
        compareFacet("사람 승인", sourceApproval, requirement.humanApprovalRequired)
        return issues
    }

    private fun semanticIssues(
        graph: WorkflowGraph,
        requirement: AutomationRequirement,
        proposal: AutomationProposal,
        agents: List<AgentDefinition>,
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val trigger = requirement.trigger.lowercase()
        val requested = listOf(
            requirement.objective,
            requirement.trigger,
            requirement.inputs.joinToString(" "),
            requirement.outputs.joinToString(" "),
            requirement.steps.joinToString(" "),
            requirement.decisions.joinToString(" "),
        ).joinToString(" ").lowercase()
        val outputAndSteps = listOf(requirement.objective, requirement.outputs.joinToString(" "), requirement.steps.joinToString(" ")).joinToString(" ").lowercase()
        val deliveryOutputsAndSteps = requirement.outputs + requirement.steps
        val types = graph.nodes.map { it.nodeType }.toSet()
        val hasSlackTrigger = NodeType.SLACK_NEW_MESSAGE_MOCK.wireName in types
        val hasSlackReply = types.any { it == NodeType.SLACK_REPLY_MOCK.wireName || it == NodeType.SLACK_SEND_MOCK.wireName }
        val hasNotionReadNode = types.any { it == NodeType.NOTION_SEARCH_MOCK.wireName || it == NodeType.NOTION_READ_PAGE_MOCK.wireName }
        val hasKnowledgeSearch = NodeType.KNOWLEDGE_SEARCH_MOCK.wireName in types
        val hasNotion = hasNotionReadNode || hasKnowledgeSearch
        val hasNews = NodeType.NEWS_SEARCH_MOCK.wireName in types
        val hasApproval = NodeType.HUMAN_APPROVAL.wireName in types
        val hasClassification = NodeType.AI_CLASSIFY.wireName in types
        val hasGeneration = NodeType.AI_GENERATE.wireName in types
        val hasManualTrigger = types.any { it == NodeType.MANUAL_TRIGGER.wireName || it == NodeType.TEXT_INPUT.wireName }
        val requestsSlack = containsAny(requested, "slack", "슬랙")
        val requestsNotion = containsAny(requested, "notion", "노션", "faq", "데이터베이스")
        val requestsNews = containsAny(requested, "뉴스", "기사", "news", "rss")
        val requestsSlackInbound = containsAny(trigger, "slack", "슬랙")
        val requestsSlackOutbound = deliveryOutputsAndSteps.any { item ->
            val normalized = item.lowercase()
            containsAny(normalized, "slack", "슬랙") &&
                containsAny(normalized, "전송", "회신", "답변", "보내", "게시", "reply", "send", "post")
        }
        val requestsManualTrigger = containsAny(trigger, "수동", "사용자 입력", "필요할 때", "manual", "on demand")
        val requestsClassification = containsAny(requested, "분류", "카테고리", "유형 판단", "classify", "classification", "category")
        val deterministicOnly = NodeType.DATA_CSV_COMPARE.wireName in types && !hasGeneration
        val requestsGeneration = !deterministicOnly && if (requestsClassification) requestsExplicitGeneration(outputAndSteps) else requestsGeneration(outputAndSteps)

        fun mismatch(code: String, message: String, nodeId: String? = null) {
            issues += ValidationIssue(code, message, nodeId)
        }

        if (requestsSlackInbound && !hasSlackTrigger) mismatch("MEANING_TRIGGER_MISSING", "요구사항의 Slack 시작 조건이 그래프에 없습니다.")
        if (requestsSlackOutbound && !hasSlackReply) mismatch("MEANING_OUTPUT_MISSING", "요구사항의 Slack 결과 전달 단계가 그래프에 없습니다.")
        if (requestsNotion && !hasNotion) mismatch("MEANING_SOURCE_MISSING", "요구사항의 Notion/FAQ 자료 조회 단계가 그래프에 없습니다.")
        if (requestsNews && !hasNews) mismatch("MEANING_SOURCE_MISSING", "요구사항의 뉴스 자료 수집 단계가 그래프에 없습니다.")
        if (requirement.humanApprovalRequired && !hasApproval) mismatch("MEANING_APPROVAL_MISSING", "요구사항의 사람 승인 단계가 그래프에 없습니다.")
        if (!requirement.humanApprovalRequired && hasApproval) mismatch("MEANING_UNREQUESTED_APPROVAL", "요구하지 않은 사람 승인 단계가 그래프에 추가되었습니다.")
        if (requestsManualTrigger && !hasManualTrigger) mismatch("MEANING_TRIGGER_MISSING", "요구사항의 수동 시작 조건이 그래프에 없습니다.")
        if (requestsClassification && !hasClassification) mismatch("MEANING_DECISION_MISSING", "요구사항의 분류 판단 단계가 그래프에 없습니다.")
        if (requestsGeneration && !hasGeneration) mismatch("MEANING_GENERATION_MISSING", "요구사항의 생성 단계가 그래프에 없습니다.")
        if (!requestsSlack && (hasSlackTrigger || hasSlackReply)) mismatch("MEANING_UNREQUESTED_INTEGRATION", "요구하지 않은 Slack 연동이 그래프에 추가되었습니다.")
        if (!requestsNotion && hasNotion) mismatch("MEANING_UNREQUESTED_INTEGRATION", "요구하지 않은 Notion/FAQ 연동이 그래프에 추가되었습니다.")
        if (!requestsNews && hasNews) mismatch("MEANING_UNREQUESTED_INTEGRATION", "요구하지 않은 뉴스 수집 단계가 그래프에 추가되었습니다.")
        if (!requestsClassification && hasClassification) mismatch("MEANING_UNREQUESTED_DECISION", "요구하지 않은 분류 단계가 그래프에 추가되었습니다.")
        if (!requestsGeneration && hasGeneration) mismatch("MEANING_UNREQUESTED_GENERATION", "요구하지 않은 생성 단계가 그래프에 추가되었습니다.")

        val agentKeys = agents.map { it.key }.toSet()
        val referencedAgentKeys = graph.nodes.mapNotNull { it.config["agentKey"]?.toString()?.takeIf(String::isNotBlank) }.toSet()
        graph.nodes.forEach { node ->
            val agentKey = node.config["agentKey"]?.toString()
            if (agentKey != null && agentKey !in agentKeys) mismatch("MEANING_UNKNOWN_AGENT", "그래프가 존재하지 않는 Agent '$agentKey'를 참조합니다.", node.id)
            if (node.nodeType in setOf(NodeType.AI_CLASSIFY.wireName, NodeType.AI_GENERATE.wireName) && agentKey.isNullOrBlank()) {
                mismatch("MEANING_AGENT_MISSING", "AI 노드에 담당 Agent가 연결되지 않았습니다.", node.id)
            }
        }
        agents.filter { it.key !in referencedAgentKeys }.forEach { mismatch("MEANING_UNUSED_AGENT", "Agent '${it.key}'가 실행 그래프에 연결되지 않았습니다.") }

        val proposalIntegrations = proposal.integrations.joinToString(" ").lowercase()
        if ((hasSlackTrigger || hasSlackReply) && !containsAny(proposalIntegrations, "slack", "슬랙")) mismatch("MEANING_PROPOSAL_GRAPH_MISMATCH", "설계안의 연동 목록에 Slack이 없습니다.")
        if (hasNotionReadNode && !containsAny(proposalIntegrations, "notion", "노션")) mismatch("MEANING_PROPOSAL_GRAPH_MISMATCH", "설계안의 연동 목록에 Notion이 없습니다.")
        if (hasKnowledgeSearch && !containsAny(proposalIntegrations, "faq", "지식", "knowledge")) mismatch("MEANING_PROPOSAL_GRAPH_MISMATCH", "설계안의 연동 목록에 FAQ/지식 검색 소스가 없습니다.")
        return issues
    }

    private fun containsAny(value: String, vararg candidates: String) = candidates.any(value::contains)

    private fun parseBranchCondition(value: String): Pair<String, String>? {
        val match = Regex("^([A-Za-z][A-Za-z0-9]*)=(true|false|[A-Za-z0-9_-]+)$").matchEntire(value.trim()) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    private fun requestsGeneration(value: String) = containsAny(
        value,
        "초안", "답변", "요약", "작성", "생성", "분석", "추출", "번역", "교정", "정리", "변환", "추천", "계획", "목록", "보고서", "릴리스 노트",
        "draft", "answer", "summary", "generate", "write", "analyze", "extract", "translate", "proofread", "report", "release note",
    )

    private fun requestsExplicitGeneration(value: String) = containsAny(
        value,
        "초안", "답변", "요약", "작성", "생성", "추출", "번역", "교정", "정리", "변환", "추천", "계획", "목록", "보고서", "릴리스 노트",
        "draft", "answer", "summary", "generate", "write", "extract", "translate", "proofread", "report", "release note",
    )

    private fun hasCycle(graph: WorkflowGraph): Boolean {
        val edges = graph.edges.groupBy { it.source }
        val visiting = mutableSetOf<String>(); val visited = mutableSetOf<String>()
        fun visit(id: String): Boolean {
            if (id in visiting) return true
            if (!visited.add(id)) return false
            visiting += id
            val cyclic = edges[id].orEmpty().any { visit(it.target) }
            visiting -= id
            return cyclic
        }
        return graph.nodes.any { visit(it.id) }
    }

    private fun reachableFrom(graph: WorkflowGraph, start: String): Set<String> {
        val edges = graph.edges.groupBy { it.source }; val seen = mutableSetOf<String>(); val queue = ArrayDeque<String>(); queue += start
        while (queue.isNotEmpty()) { val id = queue.removeFirst(); if (seen.add(id)) edges[id].orEmpty().forEach { queue += it.target } }
        return seen
    }

    private fun pathExistsWithoutApproval(graph: WorkflowGraph, start: String, target: String): Boolean {
        val byId = graph.nodes.associateBy { it.id }; val edges = graph.edges.groupBy { it.source }; val seen = mutableSetOf<String>(); val queue = ArrayDeque<String>(); queue += start
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (id == target) return true
            if (!seen.add(id) || (id != start && byId[id]?.nodeType == NodeType.HUMAN_APPROVAL.wireName)) continue
            edges[id].orEmpty().forEach { queue += it.target }
        }
        return false
    }
}
