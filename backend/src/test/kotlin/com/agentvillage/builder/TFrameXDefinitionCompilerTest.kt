package com.agentvillage.builder

import com.agentvillage.builder.application.TFrameXDefinitionCompiler
import com.agentvillage.builder.domain.*
import com.agentvillage.common.exception.BadRequestException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class TFrameXDefinitionCompilerTest {
    private val compiler = TFrameXDefinitionCompiler(jacksonObjectMapper())

    @Test
    fun `fan out and fan in graph becomes real structured TFrameX parallel pattern`() {
        val workers = (1..3).map { index ->
            AgentDefinition(
                key = "worker-$index", name = "Worker $index", role = "Process one independent item",
                inputSchema = listOf(FieldDefinition("items", "array", true, "bounded inputs")),
                outputSchema = listOf(FieldDefinition("value", "string", true, "result")),
                behaviorRules = listOf("Use assigned scope"), forbiddenRules = listOf("Do not invent inputs"),
                evidenceRequirements = listOf("Input item"),
            )
        }
        val collector = AgentDefinition(
            key = "collector", name = "Collector", role = "Collect all results",
            inputSchema = listOf(FieldDefinition("workerResults", "array", true, "all child results")),
            outputSchema = listOf(FieldDefinition("result", "string", true, "final")),
            behaviorRules = listOf("Wait for every result"), forbiddenRules = listOf("Do not omit failures"),
            evidenceRequirements = listOf("All child outputs"),
        )
        val nodes = listOf(
            WorkflowNode("start", "manual.trigger", "Start", NodePosition(0.0, 0.0)),
            *workers.mapIndexed { index, agent -> WorkflowNode("task-${index + 1}", "ai.generate", "Task ${index + 1}", NodePosition(0.0, 0.0), mapOf("agentKey" to agent.key, "instruction" to "Process item ${index + 1}")) }.toTypedArray(),
            WorkflowNode("collect", "ai.generate", "Collect", NodePosition(0.0, 0.0), mapOf("agentKey" to "collector")),
            WorkflowNode("end", "workflow.end", "End", NodePosition(0.0, 0.0)),
        )
        val edges = buildList {
            (1..3).forEach { add(WorkflowEdge("start-$it", "start", "task-$it")) }
            (1..3).forEach { add(WorkflowEdge("task-$it-collect", "task-$it", "collect")) }
            add(WorkflowEdge("collect-end", "collect", "end"))
        }
        val definition = compiler.compile("generic", WorkflowGraph(workflowId = UUID.randomUUID(), entryNodeId = "start", nodes = nodes, edges = edges), workers + collector, mapOf("items" to listOf("a", "b", "c")))
        val root = definition["pattern"] as Map<*, *>
        val steps = root["steps"] as List<*>
        val parallel = steps.first() as Map<*, *>

        assertThat(parallel["type"]).isEqualTo("ParallelPattern")
        assertThat(parallel["structuredFanIn"]).isEqualTo(true)
        assertThat(parallel["resultField"]).isEqualTo("workerResults")
        assertThat(parallel["tasks"] as List<*>).hasSize(3)
        assertThat(steps.last()).isEqualTo("collector__collect")
    }

    @Test
    fun `unconfigured capability cannot become a successful mock execution`() {
        val graph = WorkflowGraph(
            workflowId = UUID.randomUUID(), entryNodeId = "lookup",
            nodes = listOf(WorkflowNode("lookup", "knowledge.search.mock", "Lookup", NodePosition(0.0, 0.0))),
            edges = emptyList(),
        )
        assertThatThrownBy { compiler.compile("unconfigured", graph, emptyList(), emptyMap()) }
            .isInstanceOf(BadRequestException::class.java)
            .extracting("code").isEqualTo("EXECUTION_NOT_CONFIGURED")
    }
}
