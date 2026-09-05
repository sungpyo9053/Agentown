package com.agentvillage.builder

import com.agentvillage.builder.application.TFrameXDefinitionCompiler
import com.agentvillage.builder.domain.*
import com.agentvillage.common.exception.BadRequestException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class TFrameXDefinitionCompilerTest {
    private val mapper = jacksonObjectMapper()
    private val compiler = TFrameXDefinitionCompiler(mapper)

    @Test
    fun `fan out and fan in graph becomes real structured TFrameX parallel pattern`() {
        val workers = (1..3).map { index ->
            AgentDefinition(
                key = "worker-$index", name = "Worker $index", role = "Process one independent item",
                inputSchema = listOf(FieldDefinition("items", "array", true, "bounded inputs")),
                outputSchema = listOf(FieldDefinition("values", "array", true, "results", itemType = "string")),
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
            (1..3).forEach { add(WorkflowEdge("task-$it-collect", "task-$it", "collect", bindings = mapOf("workerResults" to "values"))) }
            add(WorkflowEdge("collect-end", "collect", "end"))
        }
        val workflowInputs = listOf(FieldDefinition("items", "array", true, "bounded inputs"))
        val definition = compiler.compile(
            "generic",
            WorkflowGraph(workflowId = UUID.randomUUID(), entryNodeId = "start", nodes = nodes, edges = edges),
            workers + collector,
            mapOf("items" to listOf("a", "b", "c")),
            workflowInputSchema = workflowInputs,
        )
        val root = definition["pattern"] as Map<*, *>
        val steps = root["steps"] as List<*>
        val parallel = steps.first() as Map<*, *>

        assertThat(parallel["type"]).isEqualTo("ParallelPattern")
        assertThat(parallel["structuredFanIn"]).isEqualTo(true)
        assertThat(parallel["resultField"]).isEqualTo("workerResults")
        assertThat(parallel["taskResultBindings"]).isEqualTo((1..3).associate { index ->
            "worker-${index}__task-$index" to listOf(mapOf(
                "sourceField" to "values",
                "targetField" to "workerResults",
                "aggregationMode" to "APPEND_ARRAY_ITEMS",
            ))
        })
        assertThat(parallel["tasks"] as List<*>).hasSize(3)
        assertThat(steps.last()).isEqualTo("collector__collect")
        val runtimeAgents = definition["agents"] as List<Map<String, Any?>>
        assertThat(runtimeAgents.first { it["name"] == "collector__collect" }["systemPrompt"].toString())
            .contains("출력 계약 전체를 재귀적으로 준수한다", "선언되지 않은 필드는 반환하지 않는다")
        assertThat(definition["workflowInputSchema"]).isEqualTo(workflowInputs)
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
    fun `final output schema follows terminal execution depth not serialized node order`() {
        val worker = AgentDefinition(
            key = "worker", name = "Worker", role = "Create an intermediate result",
            inputSchema = listOf(FieldDefinition("input", "string", true, "source")),
            outputSchema = listOf(FieldDefinition("intermediate", "string", true, "intermediate")),
            behaviorRules = listOf("Use input"), forbiddenRules = listOf("Do not invent input"),
            evidenceRequirements = listOf("Source input"),
        )
        val reporter = AgentDefinition(
            key = "reporter", name = "Reporter", role = "Create the final result",
            inputSchema = listOf(FieldDefinition("intermediate", "string", true, "intermediate")),
            outputSchema = listOf(FieldDefinition("finalResult", "string", true, "public final result")),
            behaviorRules = listOf("Use intermediate result"), forbiddenRules = listOf("Do not omit result"),
            evidenceRequirements = listOf("Worker output"),
        )
        // Node serialization order is deliberately unrelated to graph execution order.
        val graph = WorkflowGraph(
            workflowId = UUID.randomUUID(), entryNodeId = "start",
            nodes = listOf(
                WorkflowNode("start", "manual.trigger", "Start", NodePosition(0.0, 0.0)),
                WorkflowNode("report", "ai.generate", "Report", NodePosition(0.0, 0.0), mapOf("agentKey" to "reporter")),
                WorkflowNode("work", "ai.generate", "Work", NodePosition(0.0, 0.0), mapOf("agentKey" to "worker")),
                WorkflowNode("end", "workflow.end", "End", NodePosition(0.0, 0.0)),
            ),
            edges = listOf(
                WorkflowEdge("start-work", "start", "work"),
                WorkflowEdge("work-report", "work", "report"),
                WorkflowEdge("report-end", "report", "end"),
            ),
        )

        val definition = compiler.compile("unordered", graph, listOf(worker, reporter), mapOf("input" to "value"))

        assertThat((definition["finalOutputSchema"] as List<FieldDefinition>).map { it.name })
            .containsExactly("finalResult")
    }

    @Test
    fun `quality tool contracts match parallel fan in and preceding tool messages`() {
        val worker = AgentDefinition(
            key = "worker", name = "Worker", role = "Process one item",
            inputSchema = listOf(FieldDefinition("batch", "array", true, "items")),
            outputSchema = listOf(FieldDefinition("finding", "string", true, "finding")),
            behaviorRules = listOf("Use the input"), forbiddenRules = emptyList(), evidenceRequirements = emptyList(),
        )
        val parallelGraph = WorkflowGraph(
            workflowId = UUID.randomUUID(), entryNodeId = "start",
            nodes = listOf(
                WorkflowNode("start", "manual.trigger", "Start", NodePosition(0.0, 0.0)),
                WorkflowNode("left", "ai.generate", "Left", NodePosition(0.0, 0.0), mapOf("agentKey" to "worker")),
                WorkflowNode("right", "ai.generate", "Right", NodePosition(0.0, 0.0), mapOf("agentKey" to "worker")),
                WorkflowNode("quality", "quality.check", "Quality", NodePosition(0.0, 0.0)),
                WorkflowNode("end", "workflow.end", "End", NodePosition(0.0, 0.0)),
            ),
            edges = listOf(
                WorkflowEdge("start-left", "start", "left"), WorkflowEdge("start-right", "start", "right"),
                WorkflowEdge("left-quality", "left", "quality", bindings = emptyMap()),
                WorkflowEdge("right-quality", "right", "quality", bindings = emptyMap()),
                WorkflowEdge("quality-end", "quality", "end"),
            ),
        )
        val workflowInputs = listOf(FieldDefinition("batch", "array", true, "items"))
        val parallelDefinition = compiler.compile(
            "parallel-quality", parallelGraph, listOf(worker), mapOf("batch" to listOf("a", "b")),
            workflowInputSchema = workflowInputs,
        )
        val parallelQuality = (parallelDefinition["agents"] as List<Map<String, Any?>>)
            .single { it["toolName"] == "quality.check" }
        assertThat((parallelQuality["inputSchema"] as List<FieldDefinition>).map { it.name })
            .containsExactly("batch", "results", "failures")
        assertThat((parallelQuality["outputSchema"] as List<FieldDefinition>).map { it.name })
            .containsExactly("batch", "results", "failures", "qualityPassed")

        val toolGraph = WorkflowGraph(
            workflowId = UUID.randomUUID(), entryNodeId = "start",
            nodes = listOf(
                WorkflowNode("start", "manual.trigger", "Start", NodePosition(0.0, 0.0)),
                WorkflowNode("compare", "data.csv.compare", "Compare", NodePosition(0.0, 0.0)),
                WorkflowNode("quality", "quality.check", "Quality", NodePosition(0.0, 0.0)),
                WorkflowNode("end", "workflow.end", "End", NodePosition(0.0, 0.0)),
            ),
            edges = listOf(
                WorkflowEdge("start-compare", "start", "compare"),
                WorkflowEdge("compare-quality", "compare", "quality"),
                WorkflowEdge("quality-end", "quality", "end"),
            ),
        )
        val toolDefinition = compiler.compile("tool-quality", toolGraph, emptyList(), emptyMap())
        val toolQuality = (toolDefinition["agents"] as List<Map<String, Any?>>)
            .single { it["toolName"] == "quality.check" }
        assertThat((toolQuality["inputSchema"] as List<FieldDefinition>).map { it.name })
            .containsExactly("changedRows")
        assertThat((toolQuality["outputSchema"] as List<FieldDefinition>).map { it.name })
            .containsExactly("changedRows", "qualityPassed")
    }

    @Test
    fun `entry quality gate preserves array input through parallel join and aggregate`(@TempDir directory: Path) {
        val rowFields = listOf(
            FieldDefinition("recordId", "string", true, "stable record id", minLength = 1),
            FieldDefinition("score", "number", true, "measured score", minimum = 0.0, maximum = 100.0),
        )
        val recordContract = FieldDefinition(
            "records", "array", true, "independent records", minItems = 3, maxItems = 3,
            itemType = "object", itemSchema = rowFields, uniqueBy = "recordId",
        )
        val worker = AgentDefinition(
            key = "worker", name = "Worker", role = "Process exactly one assigned record",
            inputSchema = listOf(FieldDefinition("record", "object", true, "one record", objectSchema = rowFields)),
            outputSchema = listOf(FieldDefinition("result", "object", true, "one result", objectSchema = rowFields)),
            behaviorRules = listOf("Use only the assigned record"), forbiddenRules = emptyList(),
            evidenceRequirements = emptyList(),
        )
        val aggregator = AgentDefinition(
            key = "aggregator", name = "Aggregator", role = "Aggregate all verified results",
            inputSchema = listOf(FieldDefinition(
                "results", "array", true, "all results", minItems = 3, maxItems = 3,
                itemType = "object", itemSchema = rowFields, uniqueBy = "recordId",
            )),
            outputSchema = listOf(FieldDefinition(
                "summary", "array", true, "final result rows", minItems = 3, maxItems = 3,
                itemType = "object", itemSchema = rowFields, uniqueBy = "recordId",
            )),
            behaviorRules = listOf("Use every result"), forbiddenRules = emptyList(), evidenceRequirements = emptyList(),
        )
        val nodes = buildList {
            add(WorkflowNode("start", "manual.trigger", "Start", NodePosition(0.0, 0.0)))
            add(WorkflowNode("input-quality", "quality.check", "Validate input", NodePosition(0.0, 0.0)))
            repeat(3) { index -> add(WorkflowNode(
                "worker-${index + 1}", "ai.generate", "Worker ${index + 1}", NodePosition(0.0, 0.0),
                mapOf("agentKey" to "worker"),
            )) }
            add(WorkflowNode("result-quality", "quality.check", "Validate results", NodePosition(0.0, 0.0)))
            add(WorkflowNode("route", "condition.branch", "Route", NodePosition(0.0, 0.0)))
            add(WorkflowNode("aggregate", "ai.generate", "Aggregate", NodePosition(0.0, 0.0), mapOf("agentKey" to "aggregator")))
            add(WorkflowNode("failed", "workflow.end", "Failed", NodePosition(0.0, 0.0)))
            add(WorkflowNode("done", "workflow.end", "Done", NodePosition(0.0, 0.0)))
        }
        val edges = buildList {
            add(WorkflowEdge("start-quality", "start", "input-quality", bindings = mapOf("records" to "records")))
            repeat(3) { index -> add(WorkflowEdge(
                "quality-worker-${index + 1}", "input-quality", "worker-${index + 1}",
                bindings = mapOf("record" to "records"),
            )) }
            repeat(3) { index -> add(WorkflowEdge(
                "worker-${index + 1}-quality", "worker-${index + 1}", "result-quality",
                bindings = mapOf("worker${index + 1}Result" to "result"),
            )) }
            add(WorkflowEdge("quality-route", "result-quality", "route", bindings = mapOf("qualityPassed" to "qualityPassed")))
            add(WorkflowEdge("route-success", "route", "aggregate", "qualityPassed=true", mapOf("results" to "results")))
            add(WorkflowEdge("route-failed", "route", "failed", "qualityPassed=false"))
            add(WorkflowEdge("aggregate-done", "aggregate", "done"))
        }

        val definition = compiler.compile(
            "generic-array-fanout",
            WorkflowGraph(workflowId = UUID.randomUUID(), entryNodeId = "start", nodes = nodes, edges = edges),
            listOf(worker, aggregator),
            mapOf("records" to listOf(
                mapOf("recordId" to "r-1", "score" to 10),
                mapOf("recordId" to "r-2", "score" to 20),
                mapOf("recordId" to "r-3", "score" to 30),
            )),
            finalOutputSchema = aggregator.outputSchema,
            workflowInputSchema = listOf(recordContract),
        )

        val agents = definition["agents"] as List<Map<String, Any?>>
        val quality = agents.single { it["name"] == "quality-check__input-quality" }
        assertThat((quality["inputSchema"] as List<FieldDefinition>).map { it.name }).containsExactly("records")
        assertThat((quality["outputSchema"] as List<FieldDefinition>).map { it.name })
            .containsExactly("records", "qualityPassed")
        assertThat((quality["inputDefaults"] as Map<String, Any?>)["agentownInputContract"])
            .isEqualTo(listOf(recordContract))
        assertThat((quality["inputDefaults"] as Map<String, Any?>)["agentownFailClosed"]).isEqualTo(true)
        assertThat(agents.filter { it["name"].toString().startsWith("worker__worker-") })
            .allMatch { agent ->
                (agent["inputBindings"] as List<Map<String, String>>).contains(
                    mapOf("sourceField" to "records", "targetField" to "record"),
                )
            }

        val python = System.getenv("TFRAMEX_TEST_PYTHON") ?: return
        val definitionFile = directory.resolve("definition.json")
        mapper.writeValue(definitionFile.toFile(), definition)
        val script = directory.resolve("execute.py")
        Files.writeString(script, """
            import asyncio, json, os, sys
            from tframex.models.primitives import Message
            from tframex.util.llms import BaseLLMWrapper
            from agentown_tframex_adapter import AgentownTFrameXAdapter
            from agentown_tframex_adapter.capabilities import BUILTIN_TOOLS

            class FixtureLLM(BaseLLMWrapper):
                def __init__(self): super().__init__("fixture")
                async def chat_completion(self, messages, stream=False, **kwargs):
                    value = json.loads(messages[-1].content)
                    if "results" in value:
                        return Message(role="assistant", content=json.dumps({"summary": value["results"]}))
                    if os.environ.get("FAIL_RECORD") == value["record"]["recordId"]:
                        return Message(role="assistant", content=json.dumps({"result": {"recordId": value["record"]["recordId"], "score": "invalid"}}))
                    return Message(role="assistant", content=json.dumps({"result": value["record"]}))

            definition = json.load(open(sys.argv[1]))
            adapter = AgentownTFrameXAdapter(llm=FixtureLLM(), tools=BUILTIN_TOOLS)
            try:
                result = asyncio.run(adapter.run(definition))
                print("RESULT_JSON=" + json.dumps(result, ensure_ascii=False))
            except Exception as error:
                print("RESULT_JSON=" + json.dumps({"error": str(error), "trace": adapter.trace}, ensure_ascii=False))
                raise SystemExit(1)
        """.trimIndent())
        val process = ProcessBuilder(python, script.toString(), definitionFile.toString())
            .directory(directory.toFile()).redirectErrorStream(true)
            .also { processBuilder ->
                val repositoryRoot = System.getenv("AGENTOWN_REPO_ROOT")?.let(Path::of)
                    ?: Path.of(System.getProperty("user.dir"))
                processBuilder.environment()["PYTHONPATH"] = repositoryRoot.resolve("core-runtime").toAbsolutePath().normalize().toString()
            }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertThat(process.waitFor()).describedAs(output).isZero()
        val result = mapper.readTree(output.substringAfterLast("RESULT_JSON="))
        assertThat(mapper.readTree(result["final"].asText())["summary"].map { it["recordId"].asText() })
            .containsExactly("r-1", "r-2", "r-3")

        val failing = ProcessBuilder(python, script.toString(), definitionFile.toString())
            .directory(directory.toFile()).redirectErrorStream(true)
            .also { processBuilder ->
                val repositoryRoot = System.getenv("AGENTOWN_REPO_ROOT")?.let(Path::of)
                    ?: Path.of(System.getProperty("user.dir"))
                processBuilder.environment()["PYTHONPATH"] = repositoryRoot.resolve("core-runtime").toAbsolutePath().normalize().toString()
                processBuilder.environment()["FAIL_RECORD"] = "r-2"
            }
            .start()
        val failureOutput = failing.inputStream.bufferedReader().use { it.readText() }
        assertThat(failing.waitFor()).describedAs(failureOutput).isNotZero()
        val failure = mapper.readTree(failureOutput.substringAfterLast("RESULT_JSON="))
        assertThat(failure["trace"].filter { it["kind"].asText() == "agent_start" }.map { it["agent"].asText() })
            .doesNotContain("aggregator__aggregate")
    }

    @Test
    fun `parallel scalar results fan in through quality and router using downstream agent array contract`(@TempDir directory: Path) {
        val reviewer = AgentDefinition(
            key = "reviewer", name = "Reviewer", role = "Review one independent proposal",
            inputSchema = listOf(FieldDefinition("proposals", "array", true, "four proposals", minItems = 4, maxItems = 4)),
            outputSchema = listOf(FieldDefinition("result", "object", true, "one structured review")),
            behaviorRules = listOf("Review only the assigned item"), forbiddenRules = emptyList(), evidenceRequirements = emptyList(),
        )
        val aggregator = AgentDefinition(
            key = "aggregator", name = "Aggregator", role = "Aggregate every verified review",
            inputSchema = listOf(FieldDefinition(
                "reviewResults", "array", true, "all reviews", minItems = 4, maxItems = 4,
                itemType = "object",
            )),
            outputSchema = listOf(FieldDefinition("selectionTable", "array", true, "final table")),
            behaviorRules = listOf("Wait for all reviews"), forbiddenRules = emptyList(), evidenceRequirements = emptyList(),
        )
        val nodes = buildList {
            add(WorkflowNode("start", "manual.trigger", "Start", NodePosition(0.0, 0.0)))
            repeat(4) { index -> add(WorkflowNode(
                "review-${index + 1}", "ai.generate", "Review ${index + 1}", NodePosition(0.0, 0.0),
                mapOf("agentKey" to "reviewer"),
            )) }
            add(WorkflowNode("quality", "quality.check", "Quality", NodePosition(0.0, 0.0)))
            add(WorkflowNode("route", "condition.branch", "Route", NodePosition(0.0, 0.0)))
            add(WorkflowNode("aggregate", "ai.generate", "Aggregate", NodePosition(0.0, 0.0), mapOf("agentKey" to "aggregator")))
            add(WorkflowNode("failed", "workflow.end", "Failed", NodePosition(0.0, 0.0)))
            add(WorkflowNode("done", "workflow.end", "Done", NodePosition(0.0, 0.0)))
        }
        val edges = buildList {
            repeat(4) { index ->
                val ordinal = index + 1
                add(WorkflowEdge("start-$ordinal", "start", "review-$ordinal"))
                add(WorkflowEdge(
                    "review-$ordinal-quality", "review-$ordinal", "quality",
                    bindings = mapOf("review${ordinal}Result" to "result"),
                ))
            }
            add(WorkflowEdge("quality-route", "quality", "route", bindings = mapOf("qualityPassed" to "qualityPassed")))
            add(WorkflowEdge("route-success", "route", "aggregate", "allSucceeded=true", mapOf("reviewResults" to "reviewResults")))
            add(WorkflowEdge("route-failed", "route", "failed", "allSucceeded=false"))
            add(WorkflowEdge("aggregate-done", "aggregate", "done"))
        }

        val definition = compiler.compile(
            "quality-router-fan-in",
            WorkflowGraph(workflowId = UUID.randomUUID(), entryNodeId = "start", nodes = nodes, edges = edges),
            listOf(reviewer, aggregator),
            mapOf("proposals" to listOf("a", "b", "c", "d")),
            finalOutputSchema = aggregator.outputSchema,
            workflowInputSchema = reviewer.inputSchema,
        )

        val runtimeAgents = definition["agents"] as List<Map<String, Any?>>
        val quality = runtimeAgents.single { it["toolName"] == "quality.check" }
        assertThat((quality["inputSchema"] as List<FieldDefinition>).map { it.name }).contains("reviewResults", "failures")
        assertThat((quality["inputDefaults"] as Map<String, Any?>)["agentownResultFields"])
            .isEqualTo(listOf("reviewResults"))
        val parallel = ((definition["pattern"] as Map<*, *>)["steps"] as List<*>).first() as Map<*, *>
        assertThat(parallel["resultField"]).isEqualTo("reviewResults")
        assertThat((parallel["taskResultBindings"] as Map<*, *>).values.flatMap { it as List<Map<String, String>> })
            .allMatch { it["sourceField"] == "result" && it["targetField"] == "reviewResults" && it["aggregationMode"] == "APPEND_ITEM" }
        assertThat(runtimeAgents.filter { it["name"].toString().startsWith("reviewer__review-") })
            .allMatch { (it["systemPrompt"] as String).contains("병렬 작업 인덱스") }

        val python = System.getenv("TFRAMEX_TEST_PYTHON") ?: return
        val definitionFile = directory.resolve("definition.json")
        mapper.writeValue(definitionFile.toFile(), definition)
        val script = directory.resolve("execute.py")
        Files.writeString(script, """
            import asyncio, json, sys
            from tframex.models.primitives import Message
            from tframex.util.llms import BaseLLMWrapper
            from agentown_tframex_adapter import AgentownTFrameXAdapter
            from agentown_tframex_adapter.capabilities import BUILTIN_TOOLS

            class FixtureLLM(BaseLLMWrapper):
                def __init__(self): super().__init__("fixture")
                async def chat_completion(self, messages, stream=False, **kwargs):
                    value = json.loads(messages[-1].content)
                    if "reviewResults" in value:
                        return Message(role="assistant", content=json.dumps({"selectionTable": value["reviewResults"]}))
                    return Message(role="assistant", content=json.dumps({"result": {
                        "supplier": value["_agentownAssignedInput"]["proposals"], "status": "READY"
                    }}))

            definition = json.load(open(sys.argv[1]))
            result = asyncio.run(AgentownTFrameXAdapter(llm=FixtureLLM(), tools=BUILTIN_TOOLS).run(definition))
            print(json.dumps(result, ensure_ascii=False))
        """.trimIndent())
        val process = ProcessBuilder(python, script.toString(), definitionFile.toString())
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .also {
                val repositoryRoot = System.getenv("AGENTOWN_REPO_ROOT")?.let(Path::of)
                    ?: Path.of(System.getProperty("user.dir"))
                it.environment()["PYTHONPATH"] = repositoryRoot.resolve("core-runtime").toAbsolutePath().normalize().toString()
            }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertThat(process.waitFor()).describedAs(output).isZero()
        val result = mapper.readTree(output.substring(output.indexOf('{')))
        assertThat(mapper.readTree(result["final"].asText())["selectionTable"].map { it["supplier"].asText() })
            .containsExactly("a", "b", "c", "d")
        assertThat(result["trace"].filter { it["kind"].asText() == "router_select" }.map { it["route"].asText() })
            .containsExactly("allSucceeded=true")
    }

    @Test
    fun `plain text terminal contract preserves each supported source field`() {
        listOf("content", "report", "response").forEach { sourceField ->
            val source = AgentDefinition(
                key = "source", name = "Source", role = "Produce text",
                inputSchema = emptyList(), outputSchema = listOf(FieldDefinition(sourceField, "string", true, "text")),
                behaviorRules = emptyList(), forbiddenRules = emptyList(), evidenceRequirements = emptyList(),
            )
            val finalSchema = listOf(
                FieldDefinition(sourceField, "string", true, "source text"),
                FieldDefinition("renderedResponse", "string", true, "rendered text"),
            )
            val graph = WorkflowGraph(
                workflowId = UUID.randomUUID(), entryNodeId = "start",
                nodes = listOf(
                    WorkflowNode("start", "manual.trigger", "Start", NodePosition(0.0, 0.0)),
                    WorkflowNode("source", "ai.generate", "Source", NodePosition(0.0, 0.0), mapOf("agentKey" to "source")),
                    WorkflowNode("render", "template.render", "Render", NodePosition(0.0, 0.0), mapOf("rendererKey" to "plain-text.v1")),
                    WorkflowNode("end", "workflow.end", "End", NodePosition(0.0, 0.0)),
                ),
                edges = listOf(
                    WorkflowEdge("start-source", "start", "source"),
                    WorkflowEdge("source-render", "source", "render"),
                    WorkflowEdge("render-end", "render", "end"),
                ),
            )
            val definition = compiler.compile("plain-$sourceField", graph, listOf(source), emptyMap(), finalSchema)
            val renderer = (definition["agents"] as List<Map<String, Any?>>)
                .single { it["toolName"] == "template.plain-text" }
            assertThat((renderer["inputSchema"] as List<FieldDefinition>).map { it.name }).containsExactly(sourceField)
            assertThat((renderer["outputSchema"] as List<FieldDefinition>).map { it.name })
                .containsExactly(sourceField, "renderedResponse")
        }
    }

    @Test
    fun `parallel join quality gate and conditional routes compile to TFrameX patterns`() {
        val worker = AgentDefinition(
            key = "reviewer", name = "Reviewer", role = "Review one supplied record",
            inputSchema = listOf(FieldDefinition("memo", "string", true, "record"), FieldDefinition("location", "string", true, "scope")),
            outputSchema = listOf(FieldDefinition(
                "reviewResults", "array", true, "reviews", itemType = "object",
                itemSchema = listOf(FieldDefinition("location", "string", true, "scope"), FieldDefinition("finding", "string", true, "finding")),
            )),
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
                add(WorkflowEdge("review-$index-join", "review-$index", "join", bindings = mapOf("reviewResults[$index]" to "reviewResults")))
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
        val collectorRuntime = runtimeAgents.single { it["name"] == "collector__join" }
        assertThat(collectorRuntime["inputBindings"] as List<Map<String, String>>)
            .allMatch { it["targetField"] == "reviewResults" }
        assertThat(runtimeAgents).anyMatch { it["kind"] == "router" }
        assertThat(runtimeAgents).anyMatch { it["toolName"] == "quality.check" }
        assertThat(runtimeAgents.filter { it["kind"] == "tool" })
            .allMatch { (it["inputSchema"] as List<*>).isNotEmpty() && (it["outputSchema"] as List<*>).isNotEmpty() }
        assertThat(runtimeAgents).filteredOn { it["name"] == "reviewer__review-0" }
            .allMatch { (it["inputDefaults"] as Map<*, *>)["location"] == "branch-0" }
    }

    @Test
    fun `parallel agent join rejects implicit bindings and aggregates scalar results`() {
        fun compile(binding: Map<String, String>): Map<String, Any?> {
            val worker = AgentDefinition(
                key = "worker", name = "Worker", role = "work", inputSchema = emptyList(),
                outputSchema = listOf(FieldDefinition("result", "string", true, "scalar result")),
                behaviorRules = listOf("work"), forbiddenRules = listOf("invent"), evidenceRequirements = listOf("input"),
            )
            val reporter = AgentDefinition(
                key = "reporter", name = "Reporter", role = "report",
                inputSchema = listOf(FieldDefinition("results", "array", true, "all results")),
                outputSchema = listOf(FieldDefinition("report", "string", true, "report")),
                behaviorRules = listOf("report"), forbiddenRules = listOf("omit"), evidenceRequirements = listOf("results"),
            )
            val graph = WorkflowGraph(
                workflowId = UUID.randomUUID(), entryNodeId = "start",
                nodes = listOf(
                    WorkflowNode("start", "manual.trigger", "Start", NodePosition(0.0, 0.0)),
                    WorkflowNode("left", "ai.generate", "Left", NodePosition(0.0, 0.0), mapOf("agentKey" to "worker")),
                    WorkflowNode("right", "ai.generate", "Right", NodePosition(0.0, 0.0), mapOf("agentKey" to "worker")),
                    WorkflowNode("report", "ai.generate", "Report", NodePosition(0.0, 0.0), mapOf("agentKey" to "reporter")),
                ),
                edges = listOf(
                    WorkflowEdge("start-left", "start", "left"), WorkflowEdge("start-right", "start", "right"),
                    WorkflowEdge("left-report", "left", "report", bindings = binding),
                    WorkflowEdge("right-report", "right", "report", bindings = binding),
                ),
            )
            return compiler.compile("parallel", graph, listOf(worker, reporter), emptyMap())
        }

        assertThatThrownBy { compile(mapOf("context" to "context")) }
            .isInstanceOf(BadRequestException::class.java).hasMessageContaining("명시적 array 결과 binding")
        val definition = compile(mapOf("results" to "result"))
        val parallel = ((definition["pattern"] as Map<*, *>)["steps"] as List<*>).first() as Map<*, *>
        val bindings = parallel["taskResultBindings"] as Map<*, *>
        assertThat(bindings.values.flatMap { it as List<Map<String, String>> })
            .allMatch { it["aggregationMode"] == "APPEND_ITEM" }
    }

    @Test
    fun `parallel result alias joins the whole structured task output`(@TempDir directory: Path) {
        val resultFields = listOf(
            FieldDefinition("warehouseId", "string", true, "warehouse"),
            FieldDefinition("auditedAt", "string", true, "audit date"),
            FieldDefinition("evidenceUrls", "array", true, "evidence", itemType = "string", minItems = 1),
            FieldDefinition("status", "string", true, "status"),
        )
        val worker = AgentDefinition(
            key = "auditor", name = "Auditor", role = "audit one warehouse", inputSchema = emptyList(),
            outputSchema = resultFields, behaviorRules = listOf("audit"), forbiddenRules = emptyList(), evidenceRequirements = listOf("urls"),
        )
        val aggregator = AgentDefinition(
            key = "aggregator", name = "Aggregator", role = "aggregate audits",
            inputSchema = listOf(FieldDefinition(
                "auditResults", "array", true, "all audits", minItems = 2, maxItems = 2,
                itemType = "object", itemSchema = resultFields,
            )),
            outputSchema = listOf(FieldDefinition("report", "array", true, "report")),
            behaviorRules = listOf("aggregate"), forbiddenRules = emptyList(), evidenceRequirements = listOf("urls"),
        )
        val graph = WorkflowGraph(
            workflowId = UUID.randomUUID(), entryNodeId = "start",
            nodes = listOf(
                WorkflowNode("start", "manual.trigger", "Start", NodePosition(0.0, 0.0)),
                WorkflowNode("left", "ai.generate", "Left", NodePosition(0.0, 0.0), mapOf("agentKey" to "auditor")),
                WorkflowNode("right", "ai.generate", "Right", NodePosition(0.0, 0.0), mapOf("agentKey" to "auditor")),
                WorkflowNode("quality", "quality.check", "Quality", NodePosition(0.0, 0.0)),
                WorkflowNode("route", "condition.branch", "Route", NodePosition(0.0, 0.0)),
                WorkflowNode("aggregate", "ai.generate", "Aggregate", NodePosition(0.0, 0.0), mapOf("agentKey" to "aggregator")),
                WorkflowNode("completed", "workflow.end", "Completed", NodePosition(0.0, 0.0)),
                WorkflowNode("failed", "workflow.end", "Failed", NodePosition(0.0, 0.0)),
            ),
            edges = listOf(
                WorkflowEdge("start-left", "start", "left"), WorkflowEdge("start-right", "start", "right"),
                WorkflowEdge("left-quality", "left", "quality", bindings = mapOf("auditResults" to "result")),
                WorkflowEdge("right-quality", "right", "quality", bindings = mapOf("auditResults" to "result")),
                WorkflowEdge("quality-route", "quality", "route", bindings = mapOf("qualityPassed" to "qualityPassed")),
                WorkflowEdge("route-aggregate", "route", "aggregate", "qualityPassed=true", mapOf("auditResults" to "auditResults")),
                WorkflowEdge("route-failed", "route", "failed", "qualityPassed=false"),
                WorkflowEdge("aggregate-completed", "aggregate", "completed", "status=SUCCEEDED"),
                WorkflowEdge("aggregate-failed", "aggregate", "failed", "status=FAILED"),
            ),
        )

        val definition = compiler.compile(
            "whole-output-fan-in", graph, listOf(worker, aggregator), emptyMap(),
            finalOutputSchema = aggregator.outputSchema,
        )
        val parallel = ((definition["pattern"] as Map<*, *>)["steps"] as List<*>).first() as Map<*, *>
        assertThat((parallel["taskResultBindings"] as Map<*, *>).values.flatMap { it as List<Map<String, String>> })
            .allMatch { it["sourceField"] == "${'$'}output" && it["targetField"] == "auditResults" && it["aggregationMode"] == "APPEND_ITEM" }
        val quality = (definition["agents"] as List<Map<String, Any?>>).single { it["toolName"] == "quality.check" }
        val qualityResult = (quality["inputSchema"] as List<FieldDefinition>).single { it.name == "auditResults" }
        assertThat(qualityResult.itemSchema).containsExactlyElementsOf(resultFields)

        val python = System.getenv("TFRAMEX_TEST_PYTHON") ?: return
        val definitionFile = directory.resolve("definition.json")
        mapper.writeValue(definitionFile.toFile(), definition)
        val script = directory.resolve("execute.py")
        Files.writeString(script, """
            import asyncio, json, sys
            from tframex.models.primitives import Message
            from tframex.util.llms import BaseLLMWrapper
            from agentown_tframex_adapter import AgentownTFrameXAdapter
            from agentown_tframex_adapter.capabilities import BUILTIN_TOOLS

            class FixtureLLM(BaseLLMWrapper):
                def __init__(self): super().__init__("fixture")
                async def chat_completion(self, messages, stream=False, **kwargs):
                    value = json.loads(messages[-1].content)
                    if "auditResults" in value:
                        return Message(role="assistant", content=json.dumps({"report": value["auditResults"]}))
                    index = value["_agentownParallelIndex"]
                    return Message(role="assistant", content=json.dumps({
                        "warehouseId": f"warehouse-{index}", "auditedAt": "2026-09-05",
                        "evidenceUrls": [f"https://example.com/{index}"], "status": "SUCCEEDED"
                    }))

            definition = json.load(open(sys.argv[1]))
            result = asyncio.run(AgentownTFrameXAdapter(llm=FixtureLLM(), tools=BUILTIN_TOOLS).run(definition))
            print(json.dumps(result, ensure_ascii=False))
        """.trimIndent())
        val process = ProcessBuilder(python, script.toString(), definitionFile.toString())
            .directory(directory.toFile()).redirectErrorStream(true)
            .also { it.environment()["PYTHONPATH"] = Path.of("core-runtime").toAbsolutePath().normalize().toString() }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertThat(process.waitFor()).describedAs(output).isZero()
        val result = mapper.readTree(output.substring(output.indexOf('{')))
        assertThat(mapper.readTree(result["final"].asText())["report"].map { it["warehouseId"].asText() })
            .containsExactly("warehouse-1", "warehouse-2")
    }

    @Test
    fun `parallel join rejects incompatible array item contracts`() {
        val worker = AgentDefinition(
            key = "worker", name = "Worker", role = "work", inputSchema = emptyList(),
            outputSchema = listOf(FieldDefinition(
                "results", "array", true, "objects", itemType = "object",
                itemSchema = listOf(
                    FieldDefinition("name", "string", true, "name"),
                    FieldDefinition("internal", "string", false, "undeclared downstream field"),
                ),
            )),
            behaviorRules = listOf("work"), forbiddenRules = listOf("invent"), evidenceRequirements = listOf("input"),
        )
        val reporter = AgentDefinition(
            key = "reporter", name = "Reporter", role = "report",
            inputSchema = listOf(FieldDefinition(
                "results", "array", true, "objects", itemType = "object",
                itemSchema = listOf(FieldDefinition("name", "string", true, "name")),
            )),
            outputSchema = listOf(FieldDefinition("report", "string", true, "report")),
            behaviorRules = listOf("report"), forbiddenRules = listOf("omit"), evidenceRequirements = listOf("results"),
        )
        val graph = WorkflowGraph(
            workflowId = UUID.randomUUID(), entryNodeId = "start",
            nodes = listOf(
                WorkflowNode("start", "manual.trigger", "Start", NodePosition(0.0, 0.0)),
                WorkflowNode("left", "ai.generate", "Left", NodePosition(0.0, 0.0), mapOf("agentKey" to "worker")),
                WorkflowNode("right", "ai.generate", "Right", NodePosition(0.0, 0.0), mapOf("agentKey" to "worker")),
                WorkflowNode("report", "ai.generate", "Report", NodePosition(0.0, 0.0), mapOf("agentKey" to "reporter")),
            ),
            edges = listOf(
                WorkflowEdge("start-left", "start", "left"), WorkflowEdge("start-right", "start", "right"),
                WorkflowEdge("left-report", "left", "report", bindings = mapOf("results" to "results")),
                WorkflowEdge("right-report", "right", "report", bindings = mapOf("results" to "results")),
            ),
        )

        assertThatThrownBy { compiler.compile("incompatible-items", graph, listOf(worker, reporter), emptyMap()) }
            .isInstanceOf(BadRequestException::class.java).hasMessageContaining("array item 계약")

        val optionalWorker = worker.copy(outputSchema = listOf(FieldDefinition(
            "results", "array", true, "objects", itemType = "object",
            itemSchema = listOf(FieldDefinition("name", "string", false, "optional source name")),
        )))
        assertThatThrownBy { compiler.compile("optional-items", graph, listOf(optionalWorker, reporter), emptyMap()) }
            .isInstanceOf(BadRequestException::class.java).hasMessageContaining("array item 계약")
    }
}
