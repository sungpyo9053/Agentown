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
        SimpleNodeContract(NodeType.NOTION_CREATE_PAGE, setOf("notion:insert"), setOf("targetMode", "rendererKey")) { config, input -> NodeSimulation(input + mapOf(
            "wouldCreate" to true, "targetMode" to config["targetMode"],
            "title" to (input["title"] ?: "검토용 결과"), "content" to FixedOutputRenderer.render(config["rendererKey"].toString(), input),
            "externalCallPerformed" to false,
        )) },
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
            if (node.connectionId != null && node.nodeType.endsWith(".mock")) issues += ValidationIssue("MOCK_CONNECTION_ONLY", "Mock 노드는 connection_id를 사용하지 않습니다.", node.id)
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
        graph.nodes.filter { it.nodeType in setOf(NodeType.SLACK_REPLY_MOCK.wireName, NodeType.SLACK_SEND_MOCK.wireName, NodeType.EMAIL_SEND_MOCK.wireName, NodeType.NOTION_CREATE_PAGE.wireName) }.forEach { reply ->
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
            contractSchemaIssues(proposal, agents) +
            sourceInstruction?.takeIf(String::isNotBlank).orEmpty().let { source -> if (source.isBlank()) emptyList() else requirementFidelityIssues(source, requirement) } +
            sourceInstruction?.takeIf(String::isNotBlank).orEmpty().let { source -> if (source.isBlank()) emptyList() else explicitInputFieldIssues(source, proposal) } +
            sourceInstruction?.takeIf(String::isNotBlank).orEmpty().let { source -> if (source.isBlank()) emptyList() else explicitArrayItemTypeIssues(source, proposal) } +
            sourceInstruction?.takeIf(String::isNotBlank).orEmpty().let { source -> if (source.isBlank()) emptyList() else inputCardinalityIssues(source, proposal) } +
            semanticIssues(graph, requirement, proposal, agents)
        return structural.copy(valid = issues.isEmpty(), issues = issues)
    }

    private fun contractSchemaIssues(
        proposal: AutomationProposal,
        agents: List<AgentDefinition>,
    ): List<ValidationIssue> = buildList {
        WorkflowInputContract.schemaIssue(proposal.inputSchema)?.let {
            add(ValidationIssue("INVALID_WORKFLOW_INPUT_SCHEMA", it))
        }
        WorkflowInputContract.schemaIssue(proposal.outputSchema)?.let {
            add(ValidationIssue("INVALID_WORKFLOW_OUTPUT_SCHEMA", it))
        }
        agents.forEach { agent ->
            WorkflowInputContract.schemaIssue(agent.inputSchema)?.let {
                add(ValidationIssue("INVALID_AGENT_INPUT_SCHEMA", "Agent '${agent.key}' input: $it"))
            }
            WorkflowInputContract.schemaIssue(agent.outputSchema)?.let {
                add(ValidationIssue("INVALID_AGENT_OUTPUT_SCHEMA", "Agent '${agent.key}' output: $it"))
            }
        }
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
        val sourceApproval = requestsHumanApproval(source)
        compareFacet("사람 승인", sourceApproval, requirement.humanApprovalRequired)
        return issues
    }

    private fun requestsHumanApproval(source: String): Boolean {
        if (containsAny(source, "승인 없이", "검토 없이", "승인 불필요")) return false
        if (containsAny(source, "승인", "approval")) return true

        // An Agent reviewing data is ordinary workflow work, not a runtime human gate.
        // Only treat review/confirmation wording as approval when the request names a
        // human actor who performs that review before the workflow may continue.
        return Regex(
            "(사람|사용자|담당자|관리자|운영자|승인자|human|operator)" +
                "(가|이|은|는|의|에게)?\\s*.{0,20}(검토|확인)\\s*(후|하고|한 뒤|를 거쳐|가 끝나면|완료 후)",
        ).containsMatchIn(source)
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
        val hasNotionRead = hasNotionReadNode || hasKnowledgeSearch
        val hasNotionWrite = NodeType.NOTION_CREATE_PAGE.wireName in types
        val hasNotion = hasNotionRead || hasNotionWrite
        val hasNews = NodeType.NEWS_SEARCH_MOCK.wireName in types
        val hasApproval = NodeType.HUMAN_APPROVAL.wireName in types
        val hasClassification = NodeType.AI_CLASSIFY.wireName in types
        val hasGeneration = NodeType.AI_GENERATE.wireName in types
        val hasManualTrigger = types.any { it == NodeType.MANUAL_TRIGGER.wireName || it == NodeType.TEXT_INPUT.wireName }
        val requestsSlack = containsAny(requested, "slack", "슬랙")
        val mentionsNotion = containsAny(requested, "notion", "노션")
        val requestsNotionWrite = mentionsNotion && containsAny(outputAndSteps, "저장", "발행", "페이지 생성", "페이지로", "기록", "올려", "create", "publish", "save")
        val requestsNotionRead = containsAny(requested, "faq", "데이터베이스") ||
            (mentionsNotion && containsAny(requested, "검색", "조회", "읽", "참고", "자료에서", "search", "read"))
        val requestsNotion = requestsNotionRead || requestsNotionWrite
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
        if (requestsNotionRead && !hasNotionRead) mismatch("MEANING_SOURCE_MISSING", "요구사항의 Notion/FAQ 자료 조회 단계가 그래프에 없습니다.")
        if (requestsNotionWrite && !hasNotionWrite) mismatch("MEANING_OUTPUT_MISSING", "요구사항의 Notion 페이지 저장 단계가 그래프에 없습니다.")
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
        val agentsByKey = agents.associateBy { it.key }
        val referencedAgentKeys = graph.nodes.mapNotNull { it.config["agentKey"]?.toString()?.takeIf(String::isNotBlank) }.toSet()
        graph.nodes.forEach { node ->
            val agentKey = node.config["agentKey"]?.toString()
            if (agentKey != null && agentKey !in agentKeys) mismatch("MEANING_UNKNOWN_AGENT", "그래프가 존재하지 않는 Agent '$agentKey'를 참조합니다.", node.id)
            if (node.nodeType in setOf(NodeType.AI_CLASSIFY.wireName, NodeType.AI_GENERATE.wireName) && agentKey.isNullOrBlank()) {
                mismatch("MEANING_AGENT_MISSING", "AI 노드에 담당 Agent가 연결되지 않았습니다.", node.id)
            }
        }
        agents.filter { it.key !in referencedAgentKeys }.forEach { mismatch("MEANING_UNUSED_AGENT", "Agent '${it.key}'가 실행 그래프에 연결되지 않았습니다.") }

        val proposalInputNames = proposal.inputSchema.map { it.name }.toSet()
        if (proposal.inputSchema.map { it.name }.distinct().size != proposal.inputSchema.size) {
            mismatch("MEANING_DUPLICATE_WORKFLOW_INPUT", "Workflow 외부 입력 스키마의 필드명이 중복됩니다.")
        }
        if (proposalInputNames.isNotEmpty()) {
            val nodesById = graph.nodes.associateBy { it.id }
            val incomingByTarget = graph.edges.groupBy { it.target }
            graph.nodes.filter { it.nodeType in setOf(NodeType.AI_CLASSIFY.wireName, NodeType.AI_GENERATE.wireName) }.forEach { node ->
                val agent = node.config["agentKey"]?.toString()?.let(agentsByKey::get) ?: return@forEach
                val incoming = incomingByTarget[node.id].orEmpty()
                val boundTargets = incoming
                    .flatMap { it.bindings.keys }
                    .map(::rootField)
                    .toSet()
                val defaults = (node.config["inputDefaults"] as? Map<*, *>)?.keys.orEmpty().map { rootField(it.toString()) }.toSet()
                (node.config["inputDefaults"] as? Map<*, *>)?.forEach { (rawName, value) ->
                    val name = rawName.toString()
                    if (name.contains('.') || name.contains('[')) return@forEach
                    val field = agent.inputSchema.firstOrNull { it.name == name }
                    if (field == null) {
                        mismatch("MEANING_AGENT_INPUT_UNDECLARED", "Agent '${agent.key}'의 선언되지 않은 입력 '$name'에 inputDefaults를 설정했습니다.", node.id)
                    } else {
                        WorkflowInputContract.valueIssue(listOf(field.copy(required = true)), mapOf(name to value))?.let { issue ->
                            mismatch("MEANING_AGENT_DEFAULT_INVALID", "Agent '${agent.key}' inputDefaults가 입력 계약을 위반합니다: $issue", node.id)
                        }
                    }
                }
                val receivesInitialInput = node.id == graph.entryNodeId || incoming.any { edge ->
                    nodesById[edge.source]?.nodeType in setOf(NodeType.MANUAL_TRIGGER.wireName, NodeType.TEXT_INPUT.wireName)
                }
                val directExternalInputs = if (receivesInitialInput) proposalInputNames else emptySet()
                agent.inputSchema.filter { it.required && it.name !in directExternalInputs && it.name !in boundTargets && it.name !in defaults }
                    .forEach { field -> mismatch("MEANING_AGENT_INPUT_UNBOUND", "Agent '${agent.key}'의 필수 입력 '${field.name}'이 외부 입력, edge binding 또는 inputDefaults에 연결되지 않았습니다.", node.id) }
            }
            graph.edges.forEach { edge ->
                val sourceNode = nodesById[edge.source] ?: return@forEach
                val targetNode = nodesById[edge.target]
                val sourceAgent = sourceNode.config["agentKey"]?.toString()?.let(agentsByKey::get)
                val targetAgent = targetNode?.config?.get("agentKey")?.toString()?.let(agentsByKey::get)
                edge.bindings.forEach { (target, source) ->
                    val sourceRoot = rootField(source)
                    val targetRoot = rootField(target)
                    if (sourceNode.nodeType in setOf(NodeType.MANUAL_TRIGGER.wireName, NodeType.TEXT_INPUT.wireName) &&
                        sourceRoot !in proposalInputNames && sourceRoot !in RESERVED_BINDING_FIELDS
                    ) mismatch("MEANING_WORKFLOW_INPUT_UNDECLARED", "edge '${edge.id}'가 선언되지 않은 외부 입력 '$sourceRoot'을 참조합니다.", edge.target)
                    if (sourceAgent != null && sourceRoot !in sourceAgent.outputSchema.map { it.name } && sourceRoot !in RESERVED_BINDING_FIELDS) {
                        mismatch("MEANING_AGENT_OUTPUT_UNDECLARED", "edge '${edge.id}'가 Agent '${sourceAgent.key}'의 선언되지 않은 출력 '$sourceRoot'을 참조합니다.", edge.source)
                    }
                    if (targetAgent != null && targetRoot !in targetAgent.inputSchema.map { it.name } && targetRoot !in RESERVED_BINDING_FIELDS) {
                        mismatch("MEANING_AGENT_INPUT_UNDECLARED", "edge '${edge.id}'가 Agent '${targetAgent.key}'의 선언되지 않은 입력 '$targetRoot'에 연결됩니다.", edge.target)
                    }
                    if (!source.contains('.') && !source.contains('[') && !target.contains('.') && !target.contains('[')) {
                        val sourceField = when {
                            sourceNode.nodeType in setOf(NodeType.MANUAL_TRIGGER.wireName, NodeType.TEXT_INPUT.wireName) ->
                                proposal.inputSchema.firstOrNull { it.name == sourceRoot }
                            sourceAgent != null -> sourceAgent.outputSchema.firstOrNull { it.name == sourceRoot }
                            else -> null
                        }
                        val targetField = targetAgent?.inputSchema?.firstOrNull { it.name == targetRoot }
                        if (sourceField != null && targetField != null && !bindingFieldsCompatible(sourceField, targetField)) {
                            mismatch(
                                "MEANING_BINDING_TYPE_MISMATCH",
                                "edge '${edge.id}'의 '$sourceRoot'(${sourceField.type})을 '$targetRoot'(${targetField.type})에 연결할 수 없습니다.",
                                edge.target,
                            )
                        }
                    }
                }
            }
        }

        val proposalIntegrations = proposal.integrations.joinToString(" ").lowercase()
        if ((hasSlackTrigger || hasSlackReply) && !containsAny(proposalIntegrations, "slack", "슬랙")) mismatch("MEANING_PROPOSAL_GRAPH_MISMATCH", "설계안의 연동 목록에 Slack이 없습니다.")
        if ((hasNotionReadNode || hasNotionWrite) && !containsAny(proposalIntegrations, "notion", "노션")) mismatch("MEANING_PROPOSAL_GRAPH_MISMATCH", "설계안의 연동 목록에 Notion이 없습니다.")
        if (hasKnowledgeSearch && !containsAny(proposalIntegrations, "faq", "지식", "knowledge")) mismatch("MEANING_PROPOSAL_GRAPH_MISMATCH", "설계안의 연동 목록에 FAQ/지식 검색 소스가 없습니다.")
        return issues
    }

    private fun containsAny(value: String, vararg candidates: String) = candidates.any(value::contains)

    private fun rootField(value: String): String = value.removePrefix("request.").substringBefore('.').substringBefore('[')

    private fun bindingTypesCompatible(source: String, target: String): Boolean {
        val normalizedSource = source.lowercase()
        val normalizedTarget = target.lowercase()
        return normalizedSource == normalizedTarget || (normalizedSource == "integer" && normalizedTarget == "number")
    }

    private fun bindingFieldsCompatible(source: FieldDefinition, target: FieldDefinition): Boolean {
        if (target.required && !source.required) return false
        if (!bindingTypesCompatible(source.type, target.type)) return false
        if (!source.type.equals("array", true)) return true
        val targetItemType = target.itemType ?: return true
        val sourceItemType = source.itemType ?: return false
        if (!bindingTypesCompatible(sourceItemType, targetItemType)) return false
        if (!targetItemType.equals("object", true)) return true
        val sourceItems = source.itemSchema?.associateBy { it.name } ?: return false
        val targetItems = target.itemSchema?.associateBy { it.name } ?: return false
        return sourceItems.all { (name, sourceField) ->
            targetItems[name]?.let { bindingFieldsCompatible(sourceField, it) } == true
        } && targetItems.values.filter { it.required }.all { it.name in sourceItems }
    }

    private fun explicitArrayCardinalities(instruction: String): Map<String, Int> {
        val clause = explicitInputClause(instruction) ?: return emptyMap()
        val pattern = Regex(
            """([A-Za-z][A-Za-z0-9]{0,59})\s*(?:(?:문자열|string|정수|integer|숫자|number|불리언|boolean|객체|object)\s*)?(?:배열|array)\s*(?:을|를)?\s*\(?\s*(?:정확히|exactly)\s*(\d+)\s*(?:개|items?)?""",
            RegexOption.IGNORE_CASE,
        )
        return pattern.findAll(clause).mapNotNull { match ->
            match.groupValues[2].toIntOrNull()?.let { match.groupValues[1] to it }
        }.toMap()
    }

    private fun inputCardinalityIssues(instruction: String, proposal: AutomationProposal): List<ValidationIssue> =
        explicitArrayCardinalities(instruction).mapNotNull { (name, count) ->
            val field = proposal.inputSchema.firstOrNull { it.name == name }
            if (field != null && field.type.equals("array", true) && field.minItems == count && field.maxItems == count) null
            else ValidationIssue(
                "MEANING_INPUT_CARDINALITY_MISMATCH",
                "사용자가 '$name' 입력을 정확히 ${count}개로 지정했지만 외부 입력 스키마에 같은 minItems/maxItems 제약이 없습니다.",
            )
        }

    private fun explicitInputFieldIssues(instruction: String, proposal: AutomationProposal): List<ValidationIssue> {
        val clause = explicitInputClause(instruction) ?: return emptyList()
        val ignored = setOf(
            "a", "an", "the", "array", "exactly", "items", "item", "and", "file", "files",
            "string", "integer", "number", "boolean", "object", "csv",
        )
        val expected = clause
            .split(Regex(""",|\band\b|(?<=[A-Za-z0-9])(?:와|과)(?=[A-Za-z])""", RegexOption.IGNORE_CASE))
            .mapNotNull { segment ->
                Regex("[A-Za-z][A-Za-z0-9]{0,59}").findAll(segment)
                    .map { it.value }
                    .firstOrNull { candidate ->
                        candidate.lowercase() !in ignored
                    }
            }.toSet()
        if (expected.isEmpty()) return emptyList()
        val actual = proposal.inputSchema.map { it.name }.toSet()
        if (actual == expected) return emptyList()
        val missing = expected - actual
        val unexpected = actual - expected
        return listOf(ValidationIssue(
            "MEANING_EXPLICIT_INPUT_FIELDS_MISMATCH",
            "사용자가 외부 입력 필드를 ${expected.sorted()}로 명시했지만 설계가 일치하지 않습니다. 누락=${missing.sorted()}, 추가=${unexpected.sorted()}. 추가 필드의 edge와 Agent 입력도 외부 입력으로 가장하지 않도록 함께 교정하세요.",
        ))
    }

    private fun explicitArrayItemTypeIssues(instruction: String, proposal: AutomationProposal): List<ValidationIssue> {
        val clause = explicitInputClause(instruction) ?: return emptyList()
        val typeNames = mapOf(
            "문자열" to "string", "string" to "string",
            "정수" to "integer", "integer" to "integer",
            "숫자" to "number", "number" to "number",
            "불리언" to "boolean", "boolean" to "boolean",
            "객체" to "object", "object" to "object",
        )
        val pattern = Regex(
            """([A-Za-z][A-Za-z0-9]{0,59})\s*(문자열|string|정수|integer|숫자|number|불리언|boolean|객체|object)\s*(?:배열|array)""",
            RegexOption.IGNORE_CASE,
        )
        return pattern.findAll(clause).mapNotNull { match ->
            val name = match.groupValues[1]
            val expectedType = typeNames.getValue(match.groupValues[2].lowercase())
            val field = proposal.inputSchema.firstOrNull { it.name == name }
            if (field?.type.equals("array", true) && field?.itemType.equals(expectedType, true)) null
            else ValidationIssue(
                "MEANING_INPUT_ITEM_TYPE_MISMATCH",
                "사용자가 '$name' 입력을 $expectedType 배열로 지정했지만 외부 입력 스키마의 itemType이 일치하지 않습니다.",
            )
        }.toList()
    }

    private fun explicitInputClause(instruction: String): String? {
        val clause = Regex(
            """(?:입력(?:\s*필드)?은|inputs?\s*(?:are|:))\s*([^\n.]+?)(?:이고|이며|이다|입니다|$)""",
            RegexOption.IGNORE_CASE,
        ).find(instruction)?.groupValues?.get(1) ?: return null
        return clause.split(
            Regex(
                """\s*(?:받아야\s*하고\s*)?(?:각\s*(?:항목|요소)(?:에는|은|는)?|each\s+(?:item|element)|items?\s+(?:contain|include|have))""",
                RegexOption.IGNORE_CASE,
            ),
            limit = 2,
        ).first().trim()
    }

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

    private companion object {
        val RESERVED_BINDING_FIELDS = setOf("context", "request", "result", "results", "output", "success")
    }
}
