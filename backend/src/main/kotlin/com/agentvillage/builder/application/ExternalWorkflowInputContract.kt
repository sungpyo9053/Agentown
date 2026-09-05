package com.agentvillage.builder.application

import com.agentvillage.builder.domain.AgentDefinition
import com.agentvillage.builder.domain.AutomationProposal
import com.agentvillage.builder.domain.FieldDefinition
import com.agentvillage.common.exception.BadRequestException

internal object ExternalWorkflowInputContract {
    fun resolve(proposal: AutomationProposal, agentDefinitions: List<AgentDefinition>): List<FieldDefinition> {
        if (proposal.inputSchema.isNotEmpty()) return proposal.inputSchema
        val plan = requireNotNull(proposal.graphPlan)
        if (plan.nodes.any { it.nodeType == "data.csv.compare" }) return listOf(
            FieldDefinition("csvA", "string", true, "비교 기준 CSV"),
            FieldDefinition("csvB", "string", true, "비교 대상 CSV"),
        )
        val agents = agentDefinitions.associateBy { it.key }
        val nodes = plan.nodes.associateBy { it.id }
        val result = linkedMapOf<String, FieldDefinition>()
        fun add(sourceField: String, targetNodeId: String, targetField: String) {
            if (targetField.contains('.') || targetField.contains('[')) {
                throw BadRequestException(
                    "EXECUTION_NOT_CONFIGURED",
                    "중첩 target binding '$targetField'의 외부 입력 타입은 명시적 proposal.inputSchema가 필요합니다.",
                )
            }
            val tokens = sourceField.split('.', '[', limit = 3).filter { it.isNotBlank() }
            val name = if (tokens.firstOrNull() == "request") tokens.getOrNull(1) else tokens.firstOrNull()
            if (name.isNullOrBlank() || name in setOf("context", "result", "results", "output", "success")) return
            val targetAgent = nodes[targetNodeId]?.config?.get("agentKey")?.toString()?.let(agents::get)
            val contract = targetAgent?.inputSchema?.firstOrNull { it.name == rootField(targetField) }
            result.putIfAbsent(name, contract?.copy(
                name = name, required = true,
                description = contract.description.ifBlank { "사용자 실행 입력 $name" },
            ) ?: FieldDefinition(name, "string", true, "사용자 실행 입력 $name"))
        }
        val sourceIds = plan.nodes.filter { it.nodeType.endsWith("trigger") || it.nodeType == "text.input" }.map { it.id }.toSet()
        plan.edges.filter { it.source in sourceIds }.forEach { edge ->
            edge.bindings.forEach { add(it.sourceField, edge.target, it.targetField) }
        }
        plan.nodes.filter { it.nodeType.startsWith("ai.") }.forEach { node ->
            val agent = node.config["agentKey"]?.toString()?.let(agents::get) ?: return@forEach
            val boundTargets = plan.edges.filter { it.target == node.id }.flatMap { edge -> edge.bindings.map { rootField(it.targetField) } }.toSet()
            val defaults = (node.config["inputDefaults"] as? Map<*, *>)?.keys?.map { rootField(it.toString()) }.orEmpty().toSet()
            agent.inputSchema.filter { it.required && it.name !in boundTargets && it.name !in defaults }
                .forEach { result.putIfAbsent(it.name, it) }
        }
        return result.values.toList()
    }

    private fun rootField(value: String): String = value.removePrefix("request.").substringBefore('.').substringBefore('[')
}
