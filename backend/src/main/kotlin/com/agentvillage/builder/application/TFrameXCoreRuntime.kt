package com.agentvillage.builder.application

import com.agentvillage.builder.domain.AgentDefinition
import com.agentvillage.builder.domain.NodeType
import com.agentvillage.builder.domain.WorkflowGraph
import com.agentvillage.builder.domain.WorkflowGraphPlan
import com.agentvillage.builder.domain.WorkflowCondition
import com.agentvillage.builder.domain.WorkflowNode
import com.agentvillage.builder.domain.WorkflowEdge
import com.agentvillage.builder.domain.NodePosition
import java.util.UUID
import com.agentvillage.common.exception.BadRequestException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

const val TFRAMEX_COMMIT = "23d7a45dd9e2e52f54f44ff8f63c6dff28ef8603"

data class TFrameXRuntimeResult(
    val status: String,
    val output: Map<String, Any?> = emptyMap(),
    val trace: List<Map<String, Any?>> = emptyList(),
    val code: String? = null,
    val message: String? = null,
)

@Component
class TFrameXDefinitionCompiler(private val mapper: ObjectMapper) {
    private val passThroughTypes = setOf(
        NodeType.MANUAL_TRIGGER.wireName,
        NodeType.TEXT_INPUT.wireName,
        NodeType.WORKFLOW_END.wireName,
    )
    private val aiTypes = setOf(NodeType.AI_GENERATE.wireName, NodeType.AI_CLASSIFY.wireName)
    private val toolTypes = setOf(NodeType.DATA_CSV_COMPARE.wireName, NodeType.QUALITY_CHECK.wireName, NodeType.TEMPLATE_RENDER.wireName)
    private val patternTypes = setOf(NodeType.CONDITION_BRANCH.wireName)

    fun compile(
        flowName: String,
        graph: WorkflowGraph,
        agents: List<AgentDefinition>,
        input: Map<String, Any?>,
    ): Map<String, Any?> {
        val unsupported = graph.nodes.filter { it.nodeType !in passThroughTypes && it.nodeType !in aiTypes && it.nodeType !in toolTypes && it.nodeType !in patternTypes }
        if (unsupported.isNotEmpty()) {
            throw BadRequestException(
                "EXECUTION_NOT_CONFIGURED",
                "TFrameX 실행에 연결되지 않은 Capability가 있습니다: ${unsupported.joinToString { it.nodeType }}",
            )
        }
        val definitions = agents.associateBy { it.key }
        val executable = graph.nodes.filter { it.nodeType in aiTypes || it.nodeType in toolTypes }
        if (executable.isEmpty()) {
            throw BadRequestException("EXECUTION_NOT_CONFIGURED", "TFrameX에서 실행할 Agent가 없습니다.")
        }

        val incoming = graph.nodes.associate { it.id to 0 }.toMutableMap()
        val outgoing = graph.edges.groupBy { it.source }
        graph.edges.forEach { edge -> incoming[edge.target] = incoming.getValue(edge.target) + 1 }
        val ready = java.util.PriorityQueue<String>()
        incoming.filterValues { it == 0 }.keys.forEach(ready::add)
        val depth = mutableMapOf(graph.entryNodeId to 0)
        val ordered = mutableListOf<String>()
        while (ready.isNotEmpty()) {
            val current = ready.remove()
            ordered += current
            outgoing[current].orEmpty().forEach { edge ->
                depth[edge.target] = maxOf(depth[edge.target] ?: 0, (depth[current] ?: 0) + 1)
                incoming[edge.target] = incoming.getValue(edge.target) - 1
                if (incoming.getValue(edge.target) == 0) ready += edge.target
            }
        }
        if (ordered.size != graph.nodes.size) throw BadRequestException("INVALID_TFRAMEX_DEFINITION", "순환 그래프는 TFrameX Flow로 변환할 수 없습니다.")

        val effectiveByNode = executable.associate { node ->
            val key = if (node.nodeType in aiTypes) node.config["agentKey"]?.toString()
                ?: throw BadRequestException("INVALID_TFRAMEX_DEFINITION", "${node.id}에 agentKey가 없습니다.") else node.nodeType
            if (node.nodeType in aiTypes && key !in definitions) throw BadRequestException("INVALID_TFRAMEX_DEFINITION", "등록되지 않은 Agent입니다: $key")
            node.id to "${key.replace('.', '-')}__${node.id}"
        }
        val incomingEdges = graph.edges.groupBy { it.target }
        val runtimeAgents = executable.map { node ->
            val inputBindings = incomingEdges[node.id].orEmpty().flatMap { edge ->
                edge.bindings.map { (targetField, sourceField) ->
                    mapOf("sourceField" to sourceField, "targetField" to targetField)
                }
            }
            if (node.nodeType in aiTypes) {
                val source = definitions.getValue(node.config.getValue("agentKey").toString())
                mapOf(
                    "name" to effectiveByNode.getValue(node.id), "description" to source.role,
                    "systemPrompt" to systemPrompt(source, node.label, node.config["instruction"]?.toString()),
                    "tools" to source.toolKeys, "inputSchema" to source.inputSchema, "outputSchema" to source.outputSchema,
                    "inputBindings" to inputBindings, "inputDefaults" to (node.config["inputDefaults"] ?: emptyMap<String, Any?>()),
                )
            } else {
                val toolName = when (node.nodeType) {
                    NodeType.DATA_CSV_COMPARE.wireName -> "data.csv.compare"
                    NodeType.QUALITY_CHECK.wireName -> "quality.check"
                    NodeType.TEMPLATE_RENDER.wireName -> when (node.config["rendererKey"]) {
                        "table.markdown.v1" -> "template.markdown.table"
                        "plain-text.v1", "plain-text" -> "template.plain-text"
                        else -> throw BadRequestException("EXECUTION_NOT_CONFIGURED", "지원되지 않는 TFrameX 렌더러입니다.")
                    }
                    else -> throw BadRequestException("EXECUTION_NOT_CONFIGURED", "지원되지 않는 TFrameX Tool입니다.")
                }
                mapOf(
                    "name" to effectiveByNode.getValue(node.id), "kind" to "tool", "toolName" to toolName,
                    "tools" to listOf(toolName), "inputBindings" to inputBindings,
                    "inputDefaults" to (node.config["inputDefaults"] ?: emptyMap<String, Any?>()),
                )
            }
        }
        val branchNodes = graph.nodes.filter { it.nodeType == NodeType.CONDITION_BRANCH.wireName }
        if (branchNodes.size > 1) {
            throw BadRequestException("EXECUTION_NOT_CONFIGURED", "중첩 또는 다중 RouterPattern 변환은 아직 구성되지 않았습니다.")
        }
        val branchDescendants = branchNodes.flatMap { branch -> descendants(branch.id, outgoing) }.toSet()
        val prefixExecutable = executable.filter { it.id !in branchDescendants }
        val layers = prefixExecutable.groupBy { depth[it.id] ?: 0 }.toSortedMap().values.toList()
        val steps = layers.mapIndexed { index, layer ->
            val names = layer.sortedBy { it.id }.map { effectiveByNode.getValue(it.id) }
            val nextAgent = layers.getOrNull(index + 1)?.singleOrNull()?.takeIf { it.nodeType in aiTypes }?.config?.get("agentKey")?.toString()?.let(definitions::get)
            val resultField = nextAgent?.inputSchema?.firstOrNull { it.type.equals("array", true) }?.name ?: "results"
            if (names.size == 1) names.single() else mapOf(
                "type" to "ParallelPattern",
                "name" to "parallel-layer-$index",
                "tasks" to names,
                "structuredFanIn" to true,
                "resultField" to resultField,
            )
        }.toMutableList<Any>()
        val routerAgents = branchNodes.map { branch ->
            val routes = outgoing[branch.id].orEmpty().associate { edge ->
                val key = edge.condition
                key to routeStep(edge.target, graph.nodes.associateBy { it.id }, outgoing, effectiveByNode)
            }
            if (routes.isEmpty() || routes.keys.any { it.isBlank() }) {
                throw BadRequestException("INVALID_TFRAMEX_DEFINITION", "${branch.id} RouterPattern 경로가 비어 있습니다.")
            }
            val routerName = "condition-router__${branch.id}"
            steps += mapOf(
                "type" to "RouterPattern",
                "name" to "router-${branch.id}",
                "routerAgentName" to routerName,
                "routes" to routes,
            )
            mapOf(
                "name" to routerName,
                "kind" to "router",
                "routeConditions" to outgoing[branch.id].orEmpty().map { edge ->
                    val condition = parseCondition(edge.condition)
                    mapOf(
                        "key" to edge.condition,
                        "field" to condition.field,
                        "operator" to condition.operator.name,
                        "value" to condition.value,
                    )
                },
            )
        }
        val finalAgent = prefixExecutable.lastOrNull { it.nodeType in aiTypes }
            ?.config?.get("agentKey")?.toString()?.let(definitions::get)
        return mapOf(
            "flowName" to flowName,
            "agents" to runtimeAgents + routerAgents,
            "pattern" to mapOf("type" to "SequentialPattern", "name" to "agentown-flow", "steps" to steps),
            "input" to mapper.writeValueAsString(input),
            "finalOutputSchema" to finalAgent?.outputSchema.orEmpty(),
        )
    }

    fun compilePlan(
        flowName: String,
        plan: WorkflowGraphPlan,
        agents: List<AgentDefinition>,
        input: Map<String, Any?>,
    ): Map<String, Any?> = compile(
        flowName,
        WorkflowGraph(
            workflowId = UUID(0, 0),
            entryNodeId = plan.entryNodeId,
            nodes = plan.nodes.map { WorkflowNode(it.id, it.nodeType, it.label, NodePosition(0.0, 0.0), it.config) },
            edges = plan.edges.map { edge ->
                WorkflowEdge(edge.id, edge.source, edge.target, edge.conditionSpec?.serialize() ?: edge.condition, edge.bindings.associate { it.targetField to it.sourceField })
            },
        ),
        agents,
        input,
    )

    private fun systemPrompt(agent: AgentDefinition, nodeLabel: String, nodeInstruction: String?): String = buildString {
        appendLine("역할: ${agent.role}")
        appendLine("현재 실행 단계: $nodeLabel")
        nodeInstruction?.let { appendLine("현재 단계 지시: $it") }
        appendLine("행동 규칙:")
        agent.behaviorRules.forEach { appendLine("- $it") }
        appendLine("금지 규칙:")
        agent.forbiddenRules.forEach { appendLine("- $it") }
        appendLine("필요 근거:")
        agent.evidenceRequirements.forEach { appendLine("- $it") }
        appendLine("반드시 JSON 객체만 반환하고 다음 출력 필드를 준수한다:")
        agent.outputSchema.forEach { appendLine("- ${it.name}: ${it.type}, required=${it.required}, ${it.description}") }
        appendLine("입력에 없는 사실이나 실행 결과를 만들지 않는다.")
    }

    private fun descendants(start: String, outgoing: Map<String, List<WorkflowEdge>>): Set<String> {
        val found = linkedSetOf<String>()
        val pending = ArrayDeque(outgoing[start].orEmpty().map { it.target })
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (found.add(current)) outgoing[current].orEmpty().forEach { pending.add(it.target) }
        }
        return found
    }

    private fun routeStep(
        start: String,
        nodes: Map<String, WorkflowNode>,
        outgoing: Map<String, List<WorkflowEdge>>,
        effectiveByNode: Map<String, String>,
    ): Any {
        val steps = mutableListOf<String>()
        var current: String? = start
        val visited = mutableSetOf<String>()
        while (current != null && visited.add(current)) {
            effectiveByNode[current]?.let(steps::add)
            val next = outgoing[current].orEmpty()
            if (next.size > 1 || nodes[current]?.nodeType == NodeType.CONDITION_BRANCH.wireName) {
                throw BadRequestException("EXECUTION_NOT_CONFIGURED", "중첩 RouterPattern 경로는 아직 구성되지 않았습니다.")
            }
            current = next.singleOrNull()?.target
        }
        if (steps.isEmpty()) throw BadRequestException("INVALID_TFRAMEX_DEFINITION", "RouterPattern 경로에 실행 단계가 없습니다.")
        return if (steps.size == 1) steps.single() else mapOf(
            "type" to "SequentialPattern",
            "name" to "route-${start}",
            "steps" to steps,
        )
    }

    private fun parseCondition(raw: String): WorkflowCondition {
        val match = Regex("^([A-Za-z][A-Za-z0-9]*)(=|<=|>=)([A-Za-z0-9_-]+)$").matchEntire(raw.trim())
            ?: throw BadRequestException("INVALID_TFRAMEX_DEFINITION", "RouterPattern 조건이 field=value 형식이 아닙니다: $raw")
        val operator = when (match.groupValues[2]) {
            "=" -> com.agentvillage.builder.domain.ConditionOperator.EQUALS
            "<=" -> com.agentvillage.builder.domain.ConditionOperator.LESS_THAN_OR_EQUALS
            else -> com.agentvillage.builder.domain.ConditionOperator.GREATER_THAN_OR_EQUALS
        }
        return WorkflowCondition(match.groupValues[1], operator, match.groupValues[3])
    }
}

@Component
class TFrameXCoreRuntimeClient(
    private val compiler: TFrameXDefinitionCompiler,
    private val mapper: ObjectMapper,
    @Value("\${builder.tframex.runtime-url:http://localhost:8090}") runtimeUrl: String,
) {
    private val client = RestClient.builder().baseUrl(runtimeUrl).build()
    private val mapType = object : TypeReference<Map<String, Any?>>() {}

    fun execute(flowName: String, graph: WorkflowGraph, agents: List<AgentDefinition>, input: Map<String, Any?>): TFrameXRuntimeResult {
        val definition = compiler.compile(flowName, graph, agents, input)
        val response = client.post().uri("/execute").contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("definition" to definition))
            .exchange { _, result ->
                result.body.readAllBytes().toString(Charsets.UTF_8)
            }
        val body: Map<String, Any?> = mapper.readValue(response, mapType)
        return TFrameXRuntimeResult(
            status = body["status"]?.toString() ?: "FAILED",
            output = (body["output"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty(),
            trace = (body["trace"] as? List<*>)?.mapNotNull { item ->
                (item as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
            }.orEmpty(),
            code = body["code"]?.toString(),
            message = body["message"]?.toString(),
        )
    }
}
