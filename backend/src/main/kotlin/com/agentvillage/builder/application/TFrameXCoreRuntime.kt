package com.agentvillage.builder.application

import com.agentvillage.builder.domain.AgentDefinition
import com.agentvillage.builder.domain.FieldDefinition
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
        finalOutputSchema: List<FieldDefinition>? = null,
        workflowInputSchema: List<FieldDefinition> = emptyList(),
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
        val nodesById = graph.nodes.associateBy { it.id }
        fun toolName(node: WorkflowNode): String = when (node.nodeType) {
            NodeType.DATA_CSV_COMPARE.wireName -> "data.csv.compare"
            NodeType.QUALITY_CHECK.wireName -> "quality.check"
            NodeType.TEMPLATE_RENDER.wireName -> when (node.config["rendererKey"]) {
                "table.markdown.v1" -> "template.markdown.table"
                "plain-text.v1", "plain-text" -> "template.plain-text"
                else -> throw BadRequestException("EXECUTION_NOT_CONFIGURED", "지원되지 않는 TFrameX 렌더러입니다.")
            }
            else -> throw BadRequestException("EXECUTION_NOT_CONFIGURED", "지원되지 않는 TFrameX Tool입니다.")
        }
        fun nearestExecutableAncestors(nodeId: String): List<WorkflowNode> {
            val found = linkedMapOf<String, WorkflowNode>()
            val pending = ArrayDeque(incomingEdges[nodeId].orEmpty().map { it.source })
            val visited = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                val currentId = pending.removeFirst()
                if (!visited.add(currentId)) continue
                val current = nodesById[currentId] ?: continue
                if (current.nodeType in aiTypes || current.nodeType in toolTypes) {
                    found[current.id] = current
                } else {
                    incomingEdges[current.id].orEmpty().forEach { pending.add(it.source) }
                }
            }
            return found.values.toList()
        }
        fun isTerminalExecutable(node: WorkflowNode): Boolean = descendants(node.id, outgoing)
            .none { descendant -> nodesById[descendant]?.nodeType in (aiTypes + toolTypes) }
        fun nearestDownstreamAgentArrayContract(nodeId: String): FieldDefinition? {
            val pending = ArrayDeque<Pair<String, Int>>()
            outgoing[nodeId].orEmpty().forEach { pending.add(it.target to 1) }
            val visited = mutableSetOf<String>()
            val fields = linkedMapOf<String, FieldDefinition>()
            var foundDepth: Int? = null
            while (pending.isNotEmpty()) {
                val (currentId, currentDepth) = pending.removeFirst()
                if (foundDepth != null && currentDepth > foundDepth) break
                if (!visited.add(currentId)) continue
                val current = nodesById[currentId] ?: continue
                if (current.nodeType in aiTypes) {
                    definitions[current.config["agentKey"]?.toString()]
                        ?.inputSchema?.firstOrNull { it.type.equals("array", true) }
                        ?.let { fields.putIfAbsent(it.name, it) }
                    foundDepth = currentDepth
                    continue
                }
                outgoing[currentId].orEmpty().forEach { pending.add(it.target to currentDepth + 1) }
            }
            return fields.values.singleOrNull()
        }
        fun parallelResultFieldFor(node: WorkflowNode): String = if (node.nodeType in aiTypes) {
            definitions[node.config["agentKey"]?.toString()]?.inputSchema
                ?.firstOrNull { it.type.equals("array", true) }?.name ?: "results"
        } else {
            nearestDownstreamAgentArrayContract(node.id)?.name ?: "results"
        }
        lateinit var outputSchemaFor: (WorkflowNode) -> List<FieldDefinition>
        fun upstreamMessageSchema(node: WorkflowNode): List<FieldDefinition> {
            val ancestors = nearestExecutableAncestors(node.id)
            if (ancestors.size > 1) {
                val resultField = parallelResultFieldFor(node)
                val resultContract = nearestDownstreamAgentArrayContract(node.id)
                    ?: FieldDefinition(resultField, "array", true, "parallel task results")
                return (workflowInputSchema + listOf(
                    resultContract,
                    FieldDefinition("failures", "array", true, "parallel task failures"),
                )).distinctBy { it.name }
            }
            return ancestors.singleOrNull()?.let(outputSchemaFor)
                ?: workflowInputSchema.takeIf {
                    incomingEdges[node.id].orEmpty().any { edge ->
                        nodesById[edge.source]?.nodeType in passThroughTypes
                    }
                }.orEmpty()
        }
        outputSchemaFor = { node ->
            when {
                node.nodeType in aiTypes -> definitions[node.config["agentKey"]?.toString()]?.outputSchema.orEmpty()
                isTerminalExecutable(node) && finalOutputSchema != null -> finalOutputSchema
                node.nodeType == NodeType.DATA_CSV_COMPARE.wireName -> listOf(
                    FieldDefinition("changedRows", "array", true, "deterministic changed rows"),
                )
                node.nodeType == NodeType.QUALITY_CHECK.wireName -> upstreamMessageSchema(node) +
                    FieldDefinition("qualityPassed", "boolean", true, "quality gate result")
                toolName(node) == "template.markdown.table" -> listOf(
                    FieldDefinition("changedRows", "array", true, "deterministic changed rows"),
                    FieldDefinition("summary", "string", false, "optional change summary"),
                    FieldDefinition("rendered", "string", true, "rendered Markdown table"),
                )
                else -> upstreamMessageSchema(node)
                    .filter { it.name in setOf("content", "report", "response") }
                    .ifEmpty { listOf(FieldDefinition("content", "string", false, "content to render")) } +
                    FieldDefinition("renderedResponse", "string", true, "rendered plain text")
            }.distinctBy { it.name }
        }
        val executableLayers = executable.groupBy { depth[it.id] ?: 0 }.values
        val parallelScopeByNode = executableLayers.flatMap { layer ->
            if (layer.size < 2) emptyList() else layer.sortedBy { it.id }.mapIndexed { index, node ->
                node.id to (index + 1 to layer.size)
            }
        }.toMap()
        val runtimeAgents = executable.map { node ->
            val inputBindings = incomingEdges[node.id].orEmpty().flatMap { edge ->
                edge.bindings.map { (targetField, sourceField) ->
                    mapOf("sourceField" to sourceField, "targetField" to indexedArrayRoot(targetField))
                }
            }.distinct()
            if (node.nodeType in aiTypes) {
                val source = definitions.getValue(node.config.getValue("agentKey").toString())
                val inputDefaults = (node.config["inputDefaults"] as? Map<*, *>)
                    ?.entries?.associate { it.key.toString() to it.value }.orEmpty().toMutableMap()
                parallelScopeByNode[node.id]?.let { (index, size) ->
                    inputDefaults["_agentownParallelIndex"] = index
                    inputDefaults["_agentownParallelSize"] = size
                }
                mapOf(
                    "name" to effectiveByNode.getValue(node.id), "description" to source.role,
                    "systemPrompt" to systemPrompt(
                        source, node.label, node.config["instruction"]?.toString(), parallelScopeByNode[node.id],
                    ),
                    "tools" to source.toolKeys, "inputSchema" to source.inputSchema, "outputSchema" to source.outputSchema,
                    "inputBindings" to inputBindings, "inputDefaults" to inputDefaults,
                )
            } else {
                val toolName = toolName(node)
                val upstreamSchema = upstreamMessageSchema(node)
                val inputSchema = when (node.nodeType) {
                    NodeType.DATA_CSV_COMPARE.wireName -> listOf(
                        FieldDefinition("csvA", "string", true, "comparison baseline CSV"),
                        FieldDefinition("csvB", "string", true, "comparison target CSV"),
                        FieldDefinition("keyColumns", "array", false, "optional key columns"),
                    )
                    NodeType.QUALITY_CHECK.wireName -> upstreamSchema
                    NodeType.TEMPLATE_RENDER.wireName -> when (toolName) {
                        "template.markdown.table" -> (upstreamSchema + listOf(
                            FieldDefinition("changedRows", "array", true, "deterministic changed rows"),
                            FieldDefinition("summary", "string", false, "optional change summary"),
                        )).distinctBy { it.name }
                        else -> upstreamSchema.ifEmpty { listOf(
                            FieldDefinition("content", "string", false, "content to render"),
                            FieldDefinition("report", "string", false, "report to render"),
                            FieldDefinition("response", "string", false, "response to render"),
                        ) }
                    }
                    else -> emptyList()
                }
                val outputSchema = outputSchemaFor(node)
                val inputDefaults = (node.config["inputDefaults"] as? Map<*, *>)
                    ?.entries?.associate { it.key.toString() to it.value }.orEmpty().toMutableMap()
                if (node.nodeType == NodeType.QUALITY_CHECK.wireName || toolName == "template.plain-text") {
                    inputDefaults["agentownOutputContract"] = outputSchema
                }
                if (node.nodeType == NodeType.QUALITY_CHECK.wireName) {
                    inputDefaults["agentownInputContract"] = inputSchema
                    val hasExplicitRouter = outgoing[node.id].orEmpty().any { edge ->
                        nodesById[edge.target]?.nodeType == NodeType.CONDITION_BRANCH.wireName
                    }
                    inputDefaults["agentownFailClosed"] = !hasExplicitRouter
                }
                if (node.nodeType == NodeType.QUALITY_CHECK.wireName && nearestExecutableAncestors(node.id).size > 1) {
                    inputDefaults["agentownResultFields"] = listOf(parallelResultFieldFor(node))
                }
                mapOf(
                    "name" to effectiveByNode.getValue(node.id), "kind" to "tool", "toolName" to toolName,
                    "tools" to listOf(toolName), "inputSchema" to inputSchema, "outputSchema" to outputSchema,
                    "inputBindings" to inputBindings,
                    "inputDefaults" to inputDefaults,
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
            val nextNode = layers.getOrNull(index + 1)?.singleOrNull()
            val nextAgent = nextNode?.takeIf { it.nodeType in aiTypes }?.config?.get("agentKey")?.toString()?.let(definitions::get)
            val resultField = nextNode?.let(::parallelResultFieldFor) ?: "results"
            if (names.size == 1) names.single() else {
                val successFields = layer.flatMap { node -> outgoing[node.id].orEmpty() }
                    .mapNotNull { edge -> branchNodes.firstOrNull { it.id == edge.target } }
                    .flatMap { branch -> outgoing[branch.id].orEmpty() }
                    .mapNotNull { edge -> runCatching { parseCondition(edge.condition) }.getOrNull() }
                    .filter { it.value.equals("true", true) || it.value.equals("false", true) }
                    .map { it.field }
                    .distinct()
                val resultBindings = layer.associate { node ->
                    effectiveByNode.getValue(node.id) to outgoing[node.id].orEmpty()
                        .filter { edge -> nextNode != null && edge.target == nextNode.id }
                        .flatMap { edge -> edge.bindings
                            .filterNot { (targetField, sourceField) -> targetField == "context" && sourceField == "context" }
                            .map { (targetField, sourceField) ->
                            if (sourceField.contains('.') || sourceField.contains('[') ||
                                targetField.contains('.') || (targetField.contains('[') && indexedArrayRoot(targetField) == targetField)
                            ) {
                                throw BadRequestException("EXECUTION_NOT_CONFIGURED", "병렬 Join은 최상위 필드 binding만 지원합니다: $sourceField -> $targetField")
                            }
                            val effectiveTargetField = if (nextAgent == null) resultField else indexedArrayRoot(targetField)
                            val targetContract = if (nextAgent == null) {
                                nextNode?.let(::upstreamMessageSchema)?.firstOrNull { it.name == effectiveTargetField }
                            } else {
                                nextAgent.inputSchema.firstOrNull { it.name == effectiveTargetField }
                            }
                            if (targetContract == null || !targetContract.type.equals("array", true)) {
                                throw BadRequestException("EXECUTION_NOT_CONFIGURED", "병렬 Join target '$effectiveTargetField'은 array 입력이어야 합니다.")
                            }
                            val sourceContract = outputSchemaFor(node).firstOrNull { it.name == sourceField }
                            val effectiveSourceField: String
                            val aggregationMode: String
                            val compatible: Boolean
                            if (sourceContract == null && sourceField == "result" &&
                                objectItemCompatible(outputSchemaFor(node), targetContract)
                            ) {
                                effectiveSourceField = "${'$'}output"
                                aggregationMode = "APPEND_ITEM"
                                compatible = true
                            } else {
                                if (sourceContract == null) {
                                    throw BadRequestException("EXECUTION_NOT_CONFIGURED", "병렬 Join source '$sourceField'이 Task 출력 계약에 없습니다.")
                                }
                                effectiveSourceField = sourceField
                                aggregationMode = if (sourceContract.type.equals("array", true)) "APPEND_ARRAY_ITEMS" else "APPEND_ITEM"
                                compatible = if (aggregationMode == "APPEND_ARRAY_ITEMS") {
                                    bindingFieldsCompatible(sourceContract, targetContract)
                                } else scalarItemCompatible(sourceContract, targetContract)
                            }
                            if (!compatible) throw BadRequestException(
                                "EXECUTION_NOT_CONFIGURED",
                                "병렬 Join '$sourceField'과 '$targetField'의 array item 계약이 일치하지 않습니다.",
                            )
                            mapOf(
                                "sourceField" to effectiveSourceField,
                                "targetField" to effectiveTargetField,
                                "aggregationMode" to aggregationMode,
                            )
                        } }
                }
                if (resultBindings.values.any { it.isNotEmpty() } && resultBindings.values.any { it.isEmpty() }) {
                    throw BadRequestException("EXECUTION_NOT_CONFIGURED", "병렬 Join의 모든 Task에 결과 binding이 필요합니다.")
                }
                if (nextAgent != null && resultBindings.values.all { it.isEmpty() }) {
                    throw BadRequestException("EXECUTION_NOT_CONFIGURED", "병렬 Agent Join에는 각 Task의 명시적 array 결과 binding이 필요합니다.")
                }
                mapOf(
                "type" to "ParallelPattern",
                "name" to "parallel-layer-$index",
                "tasks" to names,
                "taskOutputSchemas" to layer.associate { node ->
                    effectiveByNode.getValue(node.id) to outputSchemaFor(node)
                },
                "taskResultBindings" to resultBindings.filterValues { it.isNotEmpty() },
                "structuredFanIn" to true,
                "resultField" to resultField,
                "successFields" to successFields,
            )
            }
        }.toMutableList<Any>()
        val terminalRouteAgents = linkedMapOf<String, Map<String, Any?>>()
        val routerAgents = branchNodes.map { branch ->
            val routes = outgoing[branch.id].orEmpty().associate { edge ->
                val key = edge.condition
                val terminalName = "workflow-end__${edge.target}"
                val step = routeStep(edge.target, graph.nodes.associateBy { it.id }, outgoing, effectiveByNode, terminalName)
                if (step == terminalName) {
                    terminalRouteAgents.putIfAbsent(terminalName, mapOf(
                        "name" to terminalName,
                        "kind" to "tool",
                        "toolName" to "workflow.end",
                        "tools" to listOf("workflow.end"),
                        "inputSchema" to emptyList<FieldDefinition>(),
                        "outputSchema" to emptyList<FieldDefinition>(),
                        "inputBindings" to emptyList<Map<String, String>>(),
                        "inputDefaults" to emptyMap<String, Any?>(),
                    ))
                }
                key to step
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
                    val qualityFallback = nearestExecutableAncestors(branch.id).singleOrNull()
                        ?.takeIf { it.nodeType == NodeType.QUALITY_CHECK.wireName }
                        ?.let { "qualityPassed" }
                    mapOf(
                        "key" to edge.condition,
                        "field" to condition.field,
                        "operator" to condition.operator.name,
                        "value" to condition.value,
                        "fallbackField" to qualityFallback,
                    )
                },
            )
        }
        val derivedFinalOutputSchema = prefixExecutable
            .maxWithOrNull(compareBy<WorkflowNode> { depth[it.id] ?: -1 }.thenBy { it.id })
            ?.let(outputSchemaFor)
        return mapOf(
            "flowName" to flowName,
            "agents" to runtimeAgents + routerAgents + terminalRouteAgents.values,
            "pattern" to mapOf("type" to "SequentialPattern", "name" to "agentown-flow", "steps" to steps),
            "input" to mapper.writeValueAsString(input),
            "workflowInputSchema" to workflowInputSchema,
            "finalOutputSchema" to (finalOutputSchema ?: derivedFinalOutputSchema.orEmpty()),
        )
    }

    private fun indexedArrayRoot(field: String): String =
        Regex("^([A-Za-z][A-Za-z0-9_]*)\\[\\d+]$").matchEntire(field)?.groupValues?.get(1) ?: field

    private fun bindingFieldsCompatible(source: FieldDefinition, target: FieldDefinition): Boolean {
        fun typeCompatible(sourceType: String, targetType: String): Boolean =
            sourceType.equals(targetType, true) || (sourceType.equals("integer", true) && targetType.equals("number", true))
        if (target.required && !source.required) return false
        if (!typeCompatible(source.type, target.type)) return false
        if (source.type.equals("object", true)) {
            val sourceFields = source.objectSchema?.associateBy { it.name } ?: return false
            val targetFields = target.objectSchema?.associateBy { it.name } ?: return false
            return sourceFields.all { (name, sourceField) ->
                targetFields[name]?.let { bindingFieldsCompatible(sourceField, it) } == true
            } && targetFields.values.filter { it.required }.all { it.name in sourceFields }
        }
        if (!source.type.equals("array", true)) return true
        val targetItemType = target.itemType ?: return true
        val sourceItemType = source.itemType ?: return false
        if (!typeCompatible(sourceItemType, targetItemType)) return false
        if (!targetItemType.equals("object", true)) return true
        val sourceItems = source.itemSchema?.associateBy { it.name } ?: return false
        val targetItems = target.itemSchema?.associateBy { it.name } ?: return false
        return sourceItems.all { (name, sourceField) ->
            targetItems[name]?.let { bindingFieldsCompatible(sourceField, it) } == true
        } && targetItems.values.filter { it.required }.all { it.name in sourceItems }
    }

    private fun scalarItemCompatible(source: FieldDefinition, target: FieldDefinition): Boolean {
        val targetItemType = target.itemType ?: return true
        if (source.type.equals("object", true) && targetItemType.equals("object", true)) {
            val targetFields = target.itemSchema?.associateBy { it.name } ?: return true
            val sourceFields = source.objectSchema?.associateBy { it.name } ?: return false
            return sourceFields.all { (name, sourceField) ->
                targetFields[name]?.let { bindingFieldsCompatible(sourceField, it) } == true
            } && targetFields.values.filter { it.required }.all { it.name in sourceFields }
        }
        return source.type.equals(targetItemType, true) ||
            (source.type.equals("integer", true) && targetItemType.equals("number", true))
    }

    private fun objectItemCompatible(source: List<FieldDefinition>, target: FieldDefinition): Boolean {
        val targetItemType = target.itemType ?: return true
        if (!targetItemType.equals("object", true)) return false
        val targetItems = target.itemSchema ?: return source.isNotEmpty()
        val sourceItems = source.associateBy { it.name }
        return source.all { sourceField ->
            targetItems.firstOrNull { it.name == sourceField.name }
                ?.let { targetField -> bindingFieldsCompatible(sourceField, targetField) } == true
        } && targetItems.filter { it.required }.all { it.name in sourceItems }
    }

    fun compilePlan(
        flowName: String,
        plan: WorkflowGraphPlan,
        agents: List<AgentDefinition>,
        input: Map<String, Any?>,
        finalOutputSchema: List<FieldDefinition>? = null,
        workflowInputSchema: List<FieldDefinition> = emptyList(),
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
        finalOutputSchema,
        workflowInputSchema,
    )

    private fun systemPrompt(
        agent: AgentDefinition,
        nodeLabel: String,
        nodeInstruction: String?,
        parallelScope: Pair<Int, Int>? = null,
    ): String = buildString {
        appendLine("역할: ${agent.role}")
        appendLine("현재 실행 단계: $nodeLabel")
        nodeInstruction?.let { appendLine("현재 단계 지시: $it") }
        parallelScope?.let { (index, size) ->
            appendLine("병렬 작업 인덱스: $index / $size")
            appendLine("런타임이 _agentownAssignedInput에 배정한 ${index}번째 항목만 처리하고 다른 배열 항목의 결과를 대신 만들지 않는다.")
        }
        appendLine("행동 규칙:")
        agent.behaviorRules.forEach { appendLine("- $it") }
        appendLine("금지 규칙:")
        agent.forbiddenRules.forEach { appendLine("- $it") }
        appendLine("필요 근거:")
        agent.evidenceRequirements.forEach { appendLine("- $it") }
        appendLine("입력 계약(JSON, 중첩 itemSchema 포함):")
        appendLine(mapper.writeValueAsString(agent.inputSchema))
        appendLine("반드시 JSON 객체만 반환하고 아래 출력 계약 전체를 재귀적으로 준수한다. itemSchema의 필수 필드를 포함하고 선언되지 않은 필드는 반환하지 않는다:")
        appendLine(mapper.writeValueAsString(agent.outputSchema))
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
        terminalFallback: String? = null,
    ): Any {
        val steps = mutableListOf<String>()
        var current: String? = start
        val visited = mutableSetOf<String>()
        while (current != null && visited.add(current)) {
            effectiveByNode[current]?.let(steps::add)
            val next = outgoing[current].orEmpty()
            if (next.size > 1 && next.all { nodes[it.target]?.nodeType == NodeType.WORKFLOW_END.wireName }) {
                current = null
                continue
            }
            if (next.size > 1 || nodes[current]?.nodeType == NodeType.CONDITION_BRANCH.wireName) {
                throw BadRequestException("EXECUTION_NOT_CONFIGURED", "중첩 RouterPattern 경로는 아직 구성되지 않았습니다.")
            }
            current = next.singleOrNull()?.target
        }
        if (steps.isEmpty()) return terminalFallback
            ?: throw BadRequestException("INVALID_TFRAMEX_DEFINITION", "RouterPattern 경로에 실행 단계가 없습니다.")
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

    fun execute(
        flowName: String,
        graph: WorkflowGraph,
        agents: List<AgentDefinition>,
        input: Map<String, Any?>,
        finalOutputSchema: List<FieldDefinition>?,
        workflowInputSchema: List<FieldDefinition> = emptyList(),
    ): TFrameXRuntimeResult {
        val definition = compiler.compile(flowName, graph, agents, input, finalOutputSchema, workflowInputSchema)
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
