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

    @Test
    fun `parallel join quality gate and conditional routes compile to TFrameX patterns`() {
        val worker = AgentDefinition(
            key = "reviewer", name = "Reviewer", role = "Review one supplied record",
            inputSchema = listOf(FieldDefinition("memo", "string", true, "record"), FieldDefinition("location", "string", true, "scope")),
            outputSchema = listOf(FieldDefinition("location", "string", true, "scope"), FieldDefinition("finding", "string", true, "finding")),
            behaviorRules = listOf("Use supplied record"), forbiddenRules = listOf("Do not invent records"), evidenceRequirements = listOf("Original record"),
        )
        val collector = AgentDefinition(
            key = "collector", name = "Collector", role = "Join every review",
            inputSchema = listOf(FieldDefinition("reviewResults", "array", true, "all reviews")),
            outputSchema = listOf(
                FieldDefinition("reportStatus", "string", true, "READY or PARTIAL"),
                FieldDefinition("report", "string", true, "joined report"),
                FieldDefinition("missingLocations", "array", true, "missing scopes"),
            ),
            behaviorRules = listOf("Wait for every review"), forbiddenRules = listOf("Do not hide missing reviews"), evidenceRequirements = listOf("Every review"),
        )
        val nodes = buildList {
            add(WorkflowNode("start", "manual.trigger", "Start", NodePosition(0.0, 0.0)))
            repeat(3) { index -> add(WorkflowNode(
                "review-$index", "ai.generate", "Review $index", NodePosition(0.0, 0.0),
                mapOf("agentKey" to "reviewer", "inputDefaults" to mapOf("location" to "branch-$index")),
            )) }
            add(WorkflowNode("join", "ai.generate", "Join", NodePosition(0.0, 0.0), mapOf("agentKey" to "collector")))
            add(WorkflowNode("quality", "quality.check", "Quality", NodePosition(0.0, 0.0)))
            add(WorkflowNode("route", "condition.branch", "Route", NodePosition(0.0, 0.0)))
            add(WorkflowNode("complete", "template.render", "Complete", NodePosition(0.0, 0.0), mapOf("rendererKey" to "plain-text.v1")))
            add(WorkflowNode("partial", "template.render", "Partial", NodePosition(0.0, 0.0), mapOf("rendererKey" to "plain-text.v1")))
            add(WorkflowNode("done", "workflow.end", "Done", NodePosition(0.0, 0.0)))
        }
        val edges = buildList {
            repeat(3) { index ->
                add(WorkflowEdge("start-$index", "start", "review-$index", bindings = mapOf("memo" to "memo$index")))
                add(WorkflowEdge("review-$index-join", "review-$index", "join", bindings = mapOf("reviewResults" to "results")))
            }
            add(WorkflowEdge("join-quality", "join", "quality", bindings = mapOf("context" to "context")))
            add(WorkflowEdge("quality-route", "quality", "route", bindings = mapOf("context" to "context")))
            add(WorkflowEdge("route-complete", "route", "complete", "qualityPassed=true", mapOf("report" to "report")))
            add(WorkflowEdge("route-partial", "route", "partial", "qualityPassed=false", mapOf("report" to "report")))
            add(WorkflowEdge("complete-done", "complete", "done"))
            add(WorkflowEdge("partial-done", "partial", "done"))
        }

        val definition = compiler.compile(
            "branch-review", WorkflowGraph(workflowId = UUID.randomUUID(), entryNodeId = "start", nodes = nodes, edges = edges),
            listOf(worker, collector), mapOf("memo0" to "a", "memo1" to "b", "memo2" to "c"),
        )
        val steps = (definition["pattern"] as Map<*, *>)["steps"] as List<*>
        assertThat((steps.first() as Map<*, *>)["type"]).isEqualTo("ParallelPattern")
        val router = steps.last() as Map<*, *>
        assertThat(router["type"]).isEqualTo("RouterPattern")
        assertThat((router["routes"] as Map<*, *>).keys).containsExactlyInAnyOrder("qualityPassed=true", "qualityPassed=false")
        val runtimeAgents = definition["agents"] as List<Map<String, Any?>>
        assertThat(runtimeAgents).anyMatch { it["kind"] == "router" }
        assertThat(runtimeAgents).anyMatch { it["toolName"] == "quality.check" }
        assertThat(runtimeAgents).filteredOn { it["name"] == "reviewer__review-0" }
            .allMatch { (it["inputDefaults"] as Map<*, *>)["location"] == "branch-0" }
    }
}
