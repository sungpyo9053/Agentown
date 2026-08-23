package com.agentvillage.builder.application

import com.agentvillage.builder.domain.*
import com.agentvillage.common.exception.BadRequestException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.security.MessageDigest

data class NodeSimulation(val output: Map<String, Any?>, val pauses: Boolean = false)

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
    private val simulator: (Map<String, Any?>, Map<String, Any?>) -> NodeSimulation,
) : WorkflowNodeContract {
    override fun validateConfig(config: Map<String, Any?>) = requiredConfig.filter { config[it] == null || config[it].toString().isBlank() }.map { "필수 설정 '$it'이 없습니다." }
    override fun validateInput(input: Map<String, Any?>) = emptyList<String>()
    override fun simulate(config: Map<String, Any?>, input: Map<String, Any?>) = simulator(config, input)
}

@Component
class WorkflowNodeCatalog {
    private val contracts = listOf(
        SimpleNodeContract(NodeType.MANUAL_TRIGGER) { _, input -> NodeSimulation(input) },
        SimpleNodeContract(NodeType.TEXT_INPUT) { _, input -> NodeSimulation(input) },
        SimpleNodeContract(NodeType.CONDITION_BRANCH, requiredConfig = setOf("expression")) { _, input -> NodeSimulation(input) },
        SimpleNodeContract(NodeType.AI_CLASSIFY, requiredConfig = setOf("categories")) { config, input -> NodeSimulation(input + ("category" to (config["categories"] as? List<*>)?.firstOrNull().toString())) },
        SimpleNodeContract(NodeType.AI_GENERATE, requiredConfig = setOf("instruction")) { _, input ->
            val faq = input["notionResult"]?.toString() ?: "관련 FAQ를 찾지 못했습니다."
            NodeSimulation(input + ("draft" to "문의 주셔서 감사합니다. $faq"))
        },
        SimpleNodeContract(NodeType.HUMAN_APPROVAL, requiredConfig = setOf("approver")) { _, input -> NodeSimulation(input, pauses = true) },
        SimpleNodeContract(NodeType.SLACK_NEW_MESSAGE_MOCK, setOf("slack:messages:read")) { _, input -> NodeSimulation(input + ("message" to (input["message"] ?: ""))) },
        SimpleNodeContract(NodeType.SLACK_REPLY_MOCK, setOf("slack:messages:write")) { _, input -> NodeSimulation(mapOf("wouldSend" to true, "message" to (input["draft"] ?: input["message"] ?: ""), "externalCallPerformed" to false)) },
        SimpleNodeContract(NodeType.NOTION_SEARCH_MOCK, setOf("notion:read"), setOf("database")) { _, input -> NodeSimulation(input + ("notionResult" to "환불은 승인 후 영업일 기준 3~5일 이내 처리됩니다.")) },
        SimpleNodeContract(NodeType.NOTION_READ_PAGE_MOCK, setOf("notion:read"), setOf("pageId")) { _, input -> NodeSimulation(input) },
    ).associateBy { it.type.wireName }

    fun require(type: String): WorkflowNodeContract = contracts[type] ?: throw BadRequestException("NODE_TYPE_NOT_ALLOWED", "허용되지 않은 노드입니다: $type")
    fun allowedTypes(): Set<String> = contracts.keys
}

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
        }
        if (hasCycle(graph)) issues += ValidationIssue("CYCLE", "MVP 워크플로우에는 순환을 허용하지 않습니다.")
        val reachable = reachableFrom(graph, graph.entryNodeId)
        graph.nodes.filter { it.id !in reachable }.forEach { issues += ValidationIssue("UNREACHABLE_NODE", "시작점에서 도달할 수 없습니다.", it.id) }
        graph.nodes.filter { it.nodeType == NodeType.SLACK_REPLY_MOCK.wireName }.forEach { reply ->
            if (pathExistsWithoutApproval(graph, graph.entryNodeId, reply.id)) {
                issues += ValidationIssue("WRITE_REQUIRES_APPROVAL", "Slack 답변 전 모든 경로에 담당자 승인이 필요합니다.", reply.id)
            }
        }
        return WorkflowValidationResult(issues.isEmpty(), graphHash = hash(graph), issues = issues)
    }

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
