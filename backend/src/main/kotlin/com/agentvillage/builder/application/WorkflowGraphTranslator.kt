package com.agentvillage.builder.application

import com.agentvillage.builder.domain.AutomationProposal
import com.agentvillage.builder.domain.NodePosition
import com.agentvillage.builder.domain.WorkflowEdge
import com.agentvillage.builder.domain.WorkflowGraph
import com.agentvillage.builder.domain.WorkflowNode
import com.agentvillage.common.exception.BadRequestException
import org.springframework.stereotype.Component
import java.util.PriorityQueue
import java.util.UUID

/** Converts the visual plan into a deterministic executable graph. */
@Component
class WorkflowGraphTranslator(private val catalog: WorkflowNodeCatalog) {
    fun translate(workflowId: UUID, proposal: AutomationProposal): WorkflowGraph {
        val plan = proposal.graphPlan
            ?: throw BadRequestException("WORKFLOW_GRAPH_PLAN_MISSING", "설계안에 실행 graphPlan이 없습니다. 설계를 다시 생성해 주세요.")
        val byId = plan.nodes.associateBy { it.id }
        if (byId.size != plan.nodes.size) invalid("노드 ID가 중복됩니다.")
        if (plan.entryNodeId !in byId) invalid("시작 노드가 그래프에 없습니다.")
        plan.nodes.forEach { runCatching { catalog.require(it.nodeType) }.getOrElse { _ -> invalid("허용되지 않은 노드 타입입니다: ${it.nodeType}") } }
        plan.edges.forEach { if (it.source !in byId || it.target !in byId) invalid("존재하지 않는 노드를 연결합니다: ${it.id}") }

        val incoming = plan.nodes.associate { it.id to 0 }.toMutableMap()
        val outgoing = plan.edges.groupBy { it.source }
        plan.edges.forEach { incoming[it.target] = incoming.getValue(it.target) + 1 }
        val ready = PriorityQueue<String>(compareBy<String> { if (it == plan.entryNodeId) 0 else 1 }.thenBy { it })
        incoming.filterValues { it == 0 }.keys.forEach(ready::add)
        val ordered = mutableListOf<String>()
        val depth = mutableMapOf(plan.entryNodeId to 0)
        while (ready.isNotEmpty()) {
            val id = ready.remove()
            ordered += id
            outgoing[id].orEmpty().sortedBy { it.id }.forEach { edge ->
                depth[edge.target] = maxOf(depth[edge.target] ?: 0, (depth[id] ?: 0) + 1)
                incoming[edge.target] = incoming.getValue(edge.target) - 1
                if (incoming.getValue(edge.target) == 0) ready += edge.target
            }
        }
        if (ordered.size != plan.nodes.size) invalid("순환 그래프는 실행할 수 없습니다.")
        val rowByDepth = mutableMapOf<Int, Int>()
        val nodes = ordered.map { id ->
            val node = byId.getValue(id)
            val level = depth[id] ?: 0
            val row = rowByDepth.getOrDefault(level, 0).also { rowByDepth[level] = it + 1 }
            WorkflowNode(node.id, node.nodeType, node.label, NodePosition(40.0 + level * 260.0, 100.0 + row * 150.0), node.config)
        }
        return WorkflowGraph(
            workflowId = workflowId,
            entryNodeId = plan.entryNodeId,
            nodes = nodes,
            edges = plan.edges.map { edge ->
                WorkflowEdge(edge.id, edge.source, edge.target, edge.conditionSpec?.serialize() ?: edge.condition, edge.bindings.associate { it.targetField to it.sourceField })
            },
        )
    }

    private fun invalid(message: String): Nothing = throw BadRequestException("WORKFLOW_GRAPH_TRANSLATION_FAILED", message)
}
