"""Differential contracts between direct TFrameX and the Agentown adapter."""

import asyncio
import json
import unittest

from tframex import Flow, TFrameXApp
from tframex.agents import BaseAgent
from tframex.models.primitives import Message
from tframex.patterns import DiscussionPattern, ParallelPattern, RouterPattern, SequentialPattern
from tframex.util.llms import BaseLLMWrapper

from agentown_tframex_adapter import AgentownTFrameXAdapter, DefinitionError
from agentown_tframex_adapter.adapter import TracingLLMAgent
from agentown_tframex_adapter.capabilities import BUILTIN_TOOLS


class JsonFixtureLLM(BaseLLMWrapper):
    def __init__(self):
        super().__init__("fixture")

    async def chat_completion(self, messages, stream=False, **kwargs):
        value = json.loads(messages[-1].content)
        if "researchResults" in value:
            return Message(role="assistant", content=json.dumps({
                "competitors": value["researchResults"],
                "asOfDate": value["asOfDate"],
            }))
        competitor = value["competitor"]
        return Message(role="assistant", content=json.dumps({
            "competitor": competitor,
            "source": value["fixture"][competitor],
        }))


class RecordingLLM(BaseLLMWrapper):
    def __init__(self):
        super().__init__("recording-fixture")
        self.messages = []

    async def chat_completion(self, messages, stream=False, **kwargs):
        self.messages.append([message.content for message in messages])
        return Message(role="assistant", content='{"result":"ok"}')


class WrappedParallelLLM(BaseLLMWrapper):
    def __init__(self):
        super().__init__("wrapped-parallel-fixture")

    async def chat_completion(self, messages, stream=False, **kwargs):
        value = json.loads(messages[-1].content)
        if "workerResults" in value:
            return Message(role="assistant", content=json.dumps({"count": len(value["workerResults"])}))
        return Message(role="assistant", content=json.dumps({"workerResult": [{"name": value["name"]}]}))


class ScalarParallelLLM(BaseLLMWrapper):
    def __init__(self):
        super().__init__("scalar-parallel-fixture")

    async def chat_completion(self, messages, stream=False, **kwargs):
        value = json.loads(messages[-1].content)
        if "workerResults" in value:
            return Message(role="assistant", content=json.dumps({"joined": value["workerResults"]}))
        return Message(role="assistant", content=json.dumps({"workerResult": value["name"]}))


class ScriptAgent(BaseAgent):
    trace = None
    scripts = None

    async def run(self, input_message, **kwargs):
        content = input_message.content if isinstance(input_message, Message) else str(input_message)
        name = self.agent_id.split("_ctx", 1)[0]
        self.trace.append(("agent", name, content))
        action = self.scripts[name]
        if isinstance(action, Exception):
            raise action
        if callable(action):
            output = await action(self, content)
        else:
            output = action.format(input=content)
        return Message(role="assistant", content=output)


class DifferentialContractTest(unittest.TestCase):
    def execute(self, definition, direct_pattern, scripts, tools=None):
        async def run_direct():
            trace = []
            ScriptAgent.trace = trace
            ScriptAgent.scripts = scripts
            app = TFrameXApp(default_llm=None, mcp_config_file=None)
            for name, function in (tools or {}).items():
                app.tool(name=name)(function)
            for agent in definition["agents"]:
                config = {"name": agent} if isinstance(agent, str) else agent
                app.agent(name=config["name"], tools=config.get("tools", []), agent_class=ScriptAgent)(ScriptAgent)
            flow = Flow(definition["flowName"]).add_step(direct_pattern)
            app.register_flow(flow)
            async with app.run_context() as runtime:
                result = await runtime.run_flow(flow, Message(role="user", content=definition["input"]))
            return result.current_message.content, trace

        async def run_candidate():
            trace = []
            ScriptAgent.trace = trace
            ScriptAgent.scripts = scripts
            names = [item if isinstance(item, str) else item["name"] for item in definition["agents"]]
            adapter = AgentownTFrameXAdapter(
                agent_classes={name: ScriptAgent for name in names},
                tools=tools,
            )
            result = await adapter.run(definition)
            return result["final"], trace

        direct = asyncio.run(run_direct())
        candidate = asyncio.run(run_candidate())
        self.assertEqual(candidate, direct)
        return candidate

    def test_router_selection_matches(self):
        definition = {
            "flowName": "router-differential",
            "agents": ["router", "blue", "default"],
            "pattern": {
                "type": "RouterPattern",
                "name": "router-pattern",
                "routerAgentName": "router",
                "routes": {"blue": "blue"},
                "defaultRoute": "default",
            },
            "input": "route-me",
        }
        pattern = RouterPattern("router-pattern", "router", {"blue": "blue"}, "default")
        final, trace = self.execute(definition, pattern, {"router": "blue", "blue": "selected:{input}", "default": "wrong"})
        # TFrameX uses the router output only as the key; the selected agent
        # receives the original current message.
        self.assertEqual(final, "selected:route-me")
        self.assertEqual([item[1] for item in trace], ["router", "blue"])

    def test_literal_json_system_prompt_survives_tframex_template_expansion(self):
        llm = RecordingLLM()
        definition = {
            "flowName": "literal-system-prompt",
            "agents": [{
                "name": "worker",
                "systemPrompt": 'contract: {"name":"value"}',
                "outputSchema": [{"name": "result", "type": "string", "required": True}],
            }],
            "steps": ["worker"],
            "input": '{"request":"run"}',
        }

        result = asyncio.run(AgentownTFrameXAdapter(llm=llm).run(definition))

        self.assertEqual(json.loads(result["final"]), {"result": "ok"})
        self.assertTrue(any('contract: {"name":"value"}' in message for message in llm.messages[0]))

    def test_discussion_turns_match(self):
        definition = {
            "flowName": "discussion-differential",
            "agents": ["first", "second"],
            "pattern": {
                "type": "DiscussionPattern",
                "name": "discussion",
                "participantAgentNames": ["first", "second"],
                "discussionRounds": 2,
            },
            "input": "topic",
        }
        pattern = DiscussionPattern("discussion", ["first", "second"], discussion_rounds=2)
        final, trace = self.execute(definition, pattern, {"first": "first({input})", "second": "second({input})"})
        self.assertEqual([item[1] for item in trace], ["first", "second", "first", "second"])
        self.assertEqual(final, "second(first(second(first(topic))))")

    def test_native_tool_dispatch_matches(self):
        tool_trace = []

        def echo_tool(value: str):
            tool_trace.append(value)
            return {"echo": value}

        async def call_tool(agent, content):
            result = await agent.config["engine"].execute_tool_by_llm_definition(
                "echo_tool", json.dumps({"value": content})
            )
            return json.dumps(result, sort_keys=True)

        definition = {
            "flowName": "tool-differential",
            "agents": [{"name": "tool-user", "tools": ["echo_tool"]}],
            "steps": ["tool-user"],
            "input": "payload",
        }
        pattern = SequentialPattern("tool-differential_sequence", ["tool-user"])
        final, _ = self.execute(definition, pattern, {"tool-user": call_tool}, {"echo_tool": echo_tool})
        self.assertEqual(final, '{"echo": "payload"}')
        self.assertEqual(tool_trace, ["payload", "payload"])

    def test_parallel_failure_propagation_matches(self):
        definition = {
            "flowName": "failure-differential",
            "agents": ["ok", "broken"],
            "pattern": {"type": "ParallelPattern", "name": "parallel-failure", "tasks": ["ok", "broken"]},
            "input": "payload",
        }
        pattern = ParallelPattern("parallel-failure", ["ok", "broken"])
        final, trace = self.execute(definition, pattern, {"ok": "ok:{input}", "broken": RuntimeError("boom")})
        self.assertIn("Task 'broken' failed: boom", final)
        self.assertEqual({item[1] for item in trace}, {"ok", "broken"})

    def test_edge_bindings_and_join_preserve_initial_context(self):
        worker_schema = [
            {"name": "competitor", "type": "string", "required": True},
            {"name": "fixture", "type": "object", "required": True},
        ]
        definition = {
            "flowName": "bound-parallel",
            "agents": [
                {"name": "alpha", "inputSchema": worker_schema, "inputBindings": [
                    {"sourceField": "request.competitors[0]", "targetField": "competitor"},
                    {"sourceField": "request.fixture", "targetField": "fixture"},
                ]},
                {"name": "beta", "inputSchema": worker_schema, "inputBindings": [
                    {"sourceField": "request.competitors[1]", "targetField": "competitor"},
                    {"sourceField": "request.fixture", "targetField": "fixture"},
                ]},
                {"name": "gamma", "inputSchema": worker_schema, "inputBindings": [
                    {"sourceField": "request.competitors[2]", "targetField": "competitor"},
                    {"sourceField": "request.fixture", "targetField": "fixture"},
                ]},
                {"name": "reporter", "inputSchema": [
                    {"name": "researchResults", "type": "array", "required": True},
                    {"name": "asOfDate", "type": "string", "required": True},
                ]},
            ],
            "pattern": {"type": "SequentialPattern", "name": "all", "steps": [
                {"type": "ParallelPattern", "name": "research", "tasks": ["alpha", "beta", "gamma"],
                 "structuredFanIn": True, "resultField": "researchResults"},
                "reporter",
            ]},
            "input": json.dumps({
                "competitors": ["Alpha", "Beta", "Gamma"],
                "asOfDate": "2026-09-04",
                "fixture": {"Alpha": "a", "Beta": "b", "Gamma": "c"},
            }),
        }
        result = asyncio.run(AgentownTFrameXAdapter(llm=JsonFixtureLLM()).run(definition))
        worker_inputs = {
            item["agent"]: json.loads(item["input"])["competitor"]
            for item in result["trace"] if item["kind"] == "agent_start" and item["agent"] != "reporter"
        }
        self.assertEqual(worker_inputs, {"alpha": "Alpha", "beta": "Beta", "gamma": "Gamma"})
        final = json.loads(result["final"])
        self.assertEqual(final["asOfDate"], "2026-09-04")
        self.assertEqual([item["competitor"] for item in final["competitors"]], ["Alpha", "Beta", "Gamma"])

    def test_parallel_join_applies_worker_source_to_reporter_target_bindings(self):
        definition = {
            "flowName": "bound-result-parallel",
            "agents": [
                {"name": "left", "inputSchema": [{"name": "name", "type": "string", "required": True}],
                 "inputBindings": [{"sourceField": "request.names[0]", "targetField": "name"}],
                 "outputSchema": [{"name": "workerResult", "type": "array", "required": True}]},
                {"name": "right", "inputSchema": [{"name": "name", "type": "string", "required": True}],
                 "inputBindings": [{"sourceField": "request.names[1]", "targetField": "name"}],
                 "outputSchema": [{"name": "workerResult", "type": "array", "required": True}]},
                {"name": "reporter", "inputSchema": [{"name": "workerResults", "type": "array", "required": True}]},
            ],
            "pattern": {"type": "SequentialPattern", "name": "all", "steps": [
                {"type": "ParallelPattern", "name": "workers", "tasks": ["left", "right"],
                 "structuredFanIn": True, "resultField": "workerResults",
                 "taskOutputSchemas": {
                     "left": [{"name": "workerResult", "type": "array", "required": True}],
                     "right": [{"name": "workerResult", "type": "array", "required": True}],
                 },
                 "taskResultBindings": {
                     "left": [{"sourceField": "workerResult", "targetField": "workerResults", "aggregationMode": "APPEND_ARRAY_ITEMS"}],
                     "right": [{"sourceField": "workerResult", "targetField": "workerResults", "aggregationMode": "APPEND_ARRAY_ITEMS"}],
                 }},
                "reporter",
            ]},
            "input": json.dumps({"names": ["left", "right"]}),
        }

        result = asyncio.run(AgentownTFrameXAdapter(llm=WrappedParallelLLM()).run(definition))

        self.assertEqual(json.loads(result["final"]), {"count": 2})
        reporter_input = next(
            json.loads(item["input"]) for item in result["trace"]
            if item["kind"] == "agent_start" and item["agent"] == "reporter"
        )
        self.assertEqual(reporter_input["workerResults"], [{"name": "left"}, {"name": "right"}])

    def test_parallel_join_appends_each_scalar_worker_result_as_one_array_item(self):
        definition = {
            "flowName": "scalar-bound-result-parallel",
            "agents": [
                {"name": "left", "inputSchema": [{"name": "name", "type": "string", "required": True}],
                 "inputBindings": [{"sourceField": "request.names[0]", "targetField": "name"}],
                 "outputSchema": [{"name": "workerResult", "type": "string", "required": True}]},
                {"name": "right", "inputSchema": [{"name": "name", "type": "string", "required": True}],
                 "inputBindings": [{"sourceField": "request.names[1]", "targetField": "name"}],
                 "outputSchema": [{"name": "workerResult", "type": "string", "required": True}]},
                {"name": "reporter", "inputSchema": [{"name": "workerResults", "type": "array", "itemType": "string", "required": True}],
                 "outputSchema": [{"name": "joined", "type": "array", "itemType": "string", "required": True}]},
            ],
            "pattern": {"type": "SequentialPattern", "name": "all", "steps": [
                {"type": "ParallelPattern", "name": "workers", "tasks": ["left", "right"],
                 "structuredFanIn": True, "resultField": "workerResults",
                 "taskOutputSchemas": {
                     "left": [{"name": "workerResult", "type": "string", "required": True}],
                     "right": [{"name": "workerResult", "type": "string", "required": True}],
                 },
                 "taskResultBindings": {
                     "left": [{"sourceField": "workerResult", "targetField": "workerResults", "aggregationMode": "APPEND_ITEM"}],
                     "right": [{"sourceField": "workerResult", "targetField": "workerResults", "aggregationMode": "APPEND_ITEM"}],
                 }},
                "reporter",
            ]},
            "input": json.dumps({"names": ["left", "right"]}),
            "finalOutputSchema": [{"name": "joined", "type": "array", "itemType": "string", "required": True}],
        }

        result = asyncio.run(AgentownTFrameXAdapter(llm=ScalarParallelLLM()).run(definition))

        self.assertEqual(json.loads(result["final"]), {"joined": ["left", "right"]})
        reporter_input = next(
            json.loads(item["input"]) for item in result["trace"]
            if item["kind"] == "agent_start" and item["agent"] == "reporter"
        )
        self.assertEqual(reporter_input["workerResults"], ["left", "right"])

    def test_parallel_join_rejects_nested_scalar_and_partial_bindings(self):
        base = {
            "flowName": "invalid-bound-result-parallel",
            "agents": ["left", "right", "reporter"],
            "input": "fixture",
        }
        cases = {
            "nested": {
                "left": [{"sourceField": "result.value", "targetField": "workerResults", "aggregationMode": "APPEND_ARRAY_ITEMS"}],
                "right": [{"sourceField": "result.value", "targetField": "workerResults", "aggregationMode": "APPEND_ARRAY_ITEMS"}],
            },
            "scalar-mode": {
                "left": [{"sourceField": "result", "targetField": "workerResults", "aggregationMode": "REPLACE"}],
                "right": [{"sourceField": "result", "targetField": "workerResults", "aggregationMode": "REPLACE"}],
            },
            "partial": {
                "left": [{"sourceField": "result", "targetField": "workerResults", "aggregationMode": "APPEND_ARRAY_ITEMS"}],
            },
        }
        for name, bindings in cases.items():
            with self.subTest(name=name):
                definition = dict(base)
                definition["pattern"] = {
                    "type": "SequentialPattern", "name": "all", "steps": [{
                        "type": "ParallelPattern", "name": "workers", "tasks": ["left", "right"],
                        "structuredFanIn": True, "resultField": "workerResults",
                        "taskResultBindings": bindings,
                        "taskOutputSchemas": {
                            "left": [{"name": "result", "type": "array", "required": True}],
                            "right": [{"name": "result", "type": "array", "required": True}],
                        },
                    }, "reporter"],
                }
                with self.assertRaises(DefinitionError):
                    asyncio.run(AgentownTFrameXAdapter(agent_classes={
                        "left": ScriptAgent, "right": ScriptAgent, "reporter": ScriptAgent,
                    }).run(definition))

        scalar_definition = dict(base)
        scalar_definition["pattern"] = {
            "type": "ParallelPattern", "name": "workers", "tasks": ["left", "right"],
            "structuredFanIn": True, "resultField": "workerResults",
            "taskOutputSchemas": {
                "left": [{"name": "result", "type": "string", "required": True}],
                "right": [{"name": "result", "type": "string", "required": True}],
            },
            "taskResultBindings": {
                task: [{"sourceField": "result", "targetField": "workerResults", "aggregationMode": "APPEND_ARRAY_ITEMS"}]
                for task in ("left", "right")
            },
        }
        with self.assertRaisesRegex(DefinitionError, "declared array output"):
            asyncio.run(AgentownTFrameXAdapter(agent_classes={
                "left": ScriptAgent, "right": ScriptAgent, "reporter": ScriptAgent,
            }).run(scalar_definition))

        nested_definition = dict(base)
        nested_definition["pattern"] = {
            "type": "ParallelPattern", "name": "workers",
            "tasks": ["left", {"type": "SequentialPattern", "name": "nested", "steps": ["right"]}],
            "structuredFanIn": True, "resultField": "workerResults",
            "taskOutputSchemas": {"left": [{"name": "result", "type": "array", "required": True}]},
            "taskResultBindings": {
                "left": [{"sourceField": "result", "targetField": "workerResults", "aggregationMode": "APPEND_ARRAY_ITEMS"}],
            },
        }
        with self.assertRaisesRegex(DefinitionError, "named tasks only"):
            asyncio.run(AgentownTFrameXAdapter(agent_classes={
                "left": ScriptAgent, "right": ScriptAgent, "reporter": ScriptAgent,
            }).run(nested_definition))

    def test_structured_join_does_not_run_reporter_after_child_failure(self):
        definition = {
            "flowName": "failed-join",
            "agents": [
                {"name": "worker", "inputSchema": [{"name": "competitor", "type": "string", "required": True}]},
                {"name": "reporter"},
            ],
            "pattern": {"type": "SequentialPattern", "name": "all", "steps": [
                {"type": "ParallelPattern", "name": "research", "tasks": ["worker"],
                 "structuredFanIn": True, "resultField": "researchResults"},
                "reporter",
            ]},
            "input": json.dumps({"competitors": ["Alpha"]}),
        }
        adapter = AgentownTFrameXAdapter(llm=JsonFixtureLLM())
        with self.assertRaisesRegex(RuntimeError, "missing required field 'competitor'"):
            asyncio.run(adapter.run(definition))
        self.assertNotIn("reporter", [item["agent"] for item in adapter.trace if item["kind"] == "agent_start"])

    def test_schema_valid_semantic_child_failures_suppress_reporter(self):
        child_schema = [
            {"name": "status", "type": "string", "required": True},
            {"name": "error", "type": "string", "required": True},
            {"name": "evidenceIds", "type": "array", "required": True},
            {"name": "observedAt", "type": "string", "required": True},
        ]
        cases = {
            "failed-status": {"status": "FAILED", "error": "", "evidenceIds": ["fixture-1"], "observedAt": "2026-09-05"},
            "partial-status": {"status": "PARTIAL", "error": "", "evidenceIds": ["fixture-1"], "observedAt": "2026-09-05"},
            "nonempty-error": {"status": "READY", "error": "source unavailable", "evidenceIds": ["fixture-1"], "observedAt": "2026-09-05"},
            "empty-evidence": {"status": "READY", "error": "", "evidenceIds": [], "observedAt": "2026-09-05"},
            "blank-evidence-item": {"status": "READY", "error": "", "evidenceIds": [""], "observedAt": "2026-09-05"},
        }
        for name, child_output in cases.items():
            with self.subTest(name=name):
                definition = {
                    "flowName": f"semantic-{name}",
                    "agents": ["worker", "reporter"],
                    "pattern": {"type": "SequentialPattern", "name": "all", "steps": [
                        {
                            "type": "ParallelPattern", "name": "children", "tasks": ["worker"],
                            "structuredFanIn": True, "resultField": "results",
                            "taskOutputSchemas": {"worker": child_schema},
                        },
                        "reporter",
                    ]},
                    "input": "fixture",
                }
                ScriptAgent.trace = []
                ScriptAgent.scripts = {
                    "worker": json.dumps(child_output).replace("{", "{{").replace("}", "}}"),
                    "reporter": "must-not-run",
                }
                adapter = AgentownTFrameXAdapter(agent_classes={"worker": ScriptAgent, "reporter": ScriptAgent})
                with self.assertRaisesRegex(RuntimeError, "failed semantic contract"):
                    asyncio.run(adapter.run(definition))
                self.assertNotIn("reporter", [item[1] for item in ScriptAgent.trace])

    def test_deterministic_quality_gate_uses_real_router_pattern(self):
        definition = {
            "flowName": "quality-route",
            "agents": [
                {"name": "quality", "kind": "tool", "toolName": "quality.check", "tools": ["quality.check"]},
                {"name": "router", "kind": "router", "routeConditions": [
                    {"key": "qualityPassed=true", "field": "qualityPassed", "operator": "EQUALS", "value": "true"},
                    {"key": "qualityPassed=false", "field": "qualityPassed", "operator": "EQUALS", "value": "false"},
                ]},
                {"name": "complete", "kind": "tool", "toolName": "template.plain-text", "tools": ["template.plain-text"]},
                {"name": "partial", "kind": "tool", "toolName": "template.plain-text", "tools": ["template.plain-text"]},
            ],
            "pattern": {"type": "SequentialPattern", "name": "all", "steps": [
                "quality",
                {"type": "RouterPattern", "name": "route", "routerAgentName": "router", "routes": {
                    "qualityPassed=true": "complete", "qualityPassed=false": "partial",
                }},
            ]},
            "input": json.dumps({"reportStatus": "READY", "report": "all records joined", "missingLocations": []}),
            "finalOutputSchema": [
                {"name": "report", "type": "string", "required": True},
                {"name": "renderedResponse", "type": "string", "required": True},
            ],
        }
        result = asyncio.run(AgentownTFrameXAdapter(tools=BUILTIN_TOOLS).run(definition))
        self.assertEqual(json.loads(result["final"]), {
            "report": "all records joined", "renderedResponse": "all records joined",
        })
        self.assertEqual(
            [item["route"] for item in result["trace"] if item["kind"] == "router_select"],
            ["qualityPassed=true"],
        )

    def test_compiled_builtin_csv_contract_runs_without_context_leakage(self):
        definition = {
            "flowName": "strict-csv",
            "agents": [
                {
                    "name": "compare", "kind": "tool", "toolName": "data.csv.compare",
                    "tools": ["data.csv.compare"],
                    "inputSchema": [
                        {"name": "csvA", "type": "string", "required": True},
                        {"name": "csvB", "type": "string", "required": True},
                    ],
                    "outputSchema": [{"name": "changedRows", "type": "array", "required": True}],
                },
                {
                    "name": "render", "kind": "tool", "toolName": "template.markdown.table",
                    "tools": ["template.markdown.table"],
                    "inputSchema": [{"name": "changedRows", "type": "array", "required": True}],
                    "outputSchema": [
                        {"name": "changedRows", "type": "array", "required": True},
                        {"name": "rendered", "type": "string", "required": True},
                    ],
                },
            ],
            "steps": ["compare", "render"],
            "input": json.dumps({
                "csvA": "id,name\n1,old\n2,remove\n",
                "csvB": "id,name\n1,new\n3,add\n",
            }),
            "workflowInputSchema": [
                {"name": "csvA", "type": "string", "required": True},
                {"name": "csvB", "type": "string", "required": True},
            ],
            "finalOutputSchema": [
                {"name": "changedRows", "type": "array", "required": True},
                {"name": "rendered", "type": "string", "required": True},
            ],
        }
        result = asyncio.run(AgentownTFrameXAdapter(tools=BUILTIN_TOOLS).run(definition))
        final = json.loads(result["final"])
        self.assertEqual({item["changeType"] for item in final["changedRows"]}, {"ADDED", "REMOVED", "MODIFIED"})
        self.assertIn("| changeType | key |", final["rendered"])

    def test_quality_contract_preserves_structured_parallel_results(self):
        async def worker(agent, _):
            name = agent.agent_id.split("_ctx", 1)[0]
            return json.dumps({"worker": name, "status": "READY"})

        output_schema = [
            {"name": "results", "type": "array", "required": True},
            {"name": "failures", "type": "array", "required": True},
            {"name": "qualityPassed", "type": "boolean", "required": True},
        ]
        definition = {
            "flowName": "parallel-quality",
            "agents": [
                "east", "west", "central",
                {
                    "name": "quality", "kind": "tool", "toolName": "quality.check",
                    "tools": ["quality.check"], "outputSchema": output_schema,
                    "inputDefaults": {"agentownOutputContract": output_schema},
                },
            ],
            "pattern": {"type": "SequentialPattern", "name": "all", "steps": [
                {"type": "ParallelPattern", "name": "workers", "tasks": ["east", "west", "central"],
                 "structuredFanIn": True, "resultField": "results"},
                "quality",
            ]},
            "input": json.dumps({"asOfDate": "2026-09-05"}),
            "finalOutputSchema": output_schema,
        }
        ScriptAgent.trace = []
        ScriptAgent.scripts = {name: worker for name in ("east", "west", "central")}
        result = asyncio.run(AgentownTFrameXAdapter(
            agent_classes={name: ScriptAgent for name in ("east", "west", "central")},
            tools=BUILTIN_TOOLS,
        ).run(definition))
        final = json.loads(result["final"])
        self.assertEqual(len(final["results"]), 3)
        self.assertEqual(final["failures"], [])
        self.assertTrue(final["qualityPassed"])

    def test_quality_contract_preserves_preceding_tool_output(self):
        quality_schema = [
            {"name": "changedRows", "type": "array", "required": True},
            {"name": "qualityPassed", "type": "boolean", "required": True},
        ]
        definition = {
            "flowName": "tool-quality",
            "agents": [
                {"name": "compare", "kind": "tool", "toolName": "data.csv.compare", "tools": ["data.csv.compare"]},
                {
                    "name": "quality", "kind": "tool", "toolName": "quality.check",
                    "tools": ["quality.check"], "outputSchema": quality_schema,
                    "inputDefaults": {"agentownOutputContract": quality_schema},
                },
            ],
            "steps": ["compare", "quality"],
            "input": json.dumps({"csvA": "id,v\n1,a\n", "csvB": "id,v\n1,b\n"}),
            "finalOutputSchema": quality_schema,
        }
        result = asyncio.run(AgentownTFrameXAdapter(tools=BUILTIN_TOOLS).run(definition))
        final = json.loads(result["final"])
        self.assertEqual(final["changedRows"][0]["changeType"], "MODIFIED")
        self.assertTrue(final["qualityPassed"])

    def test_plain_text_contract_supports_content_report_and_response(self):
        for source_field in ("content", "report", "response"):
            with self.subTest(source_field=source_field):
                output_schema = [
                    {"name": source_field, "type": "string", "required": True},
                    {"name": "renderedResponse", "type": "string", "required": True},
                ]
                definition = {
                    "flowName": f"plain-{source_field}",
                    "agents": [{
                        "name": "render", "kind": "tool", "toolName": "template.plain-text",
                        "tools": ["template.plain-text"], "outputSchema": output_schema,
                        "inputDefaults": {"agentownOutputContract": output_schema},
                    }],
                    "steps": ["render"],
                    "input": json.dumps({source_field: "fixture text", "internal": "must not leak"}),
                    "finalOutputSchema": output_schema,
                }
                result = asyncio.run(AgentownTFrameXAdapter(tools=BUILTIN_TOOLS).run(definition))
                self.assertEqual(json.loads(result["final"]), {
                    source_field: "fixture text", "renderedResponse": "fixture text",
                })

    def test_agent_output_contract_rejects_undeclared_fields(self):
        with self.assertRaisesRegex(ValueError, "unexpected fields.*internalPrompt"):
            TracingLLMAgent._validate_json_contract(
                json.dumps({"answer": "ok", "internalPrompt": "must not leak"}),
                [{"name": "answer", "type": "string", "required": True}],
                "output",
            )

    def test_json_number_contract_does_not_accept_boolean(self):
        with self.assertRaisesRegex(ValueError, "invalid type"):
            TracingLLMAgent._validate_json_contract(
                json.dumps({"count": True}),
                [{"name": "count", "type": "integer", "required": True}],
                "output",
            )

    def test_nested_object_array_contract_rejects_malformed_items_and_evidence(self):
        contract = [{
            "name": "warehouseResults", "type": "array", "required": True,
            "itemType": "object",
            "itemSchema": [
                {"name": "warehouse", "type": "string", "required": True},
                {"name": "evidenceIds", "type": "array", "required": True, "itemType": "string"},
                {"name": "status", "type": "string", "required": True},
            ],
        }]
        with self.assertRaisesRegex(ValueError, r"warehouseResults\[0\].*invalid type"):
            TracingLLMAgent._validate_json_contract(
                json.dumps({"warehouseResults": ["malformed"]}), contract, "output",
            )
        with self.assertRaisesRegex(ValueError, r"warehouseResults\[0\]\.evidenceIds"):
            TracingLLMAgent._validate_json_contract(
                json.dumps({"warehouseResults": [{"warehouse": "East", "status": "SUCCEEDED"}]}),
                contract, "output",
            )
        with self.assertRaisesRegex(ValueError, r"warehouseResults\[0\]\.evidenceIds\[0\].*invalid type"):
            TracingLLMAgent._validate_json_contract(
                json.dumps({"warehouseResults": [{
                    "warehouse": "East", "evidenceIds": [101], "status": "SUCCEEDED",
                }]}),
                contract, "output",
            )

    def test_final_semantic_gate_checks_required_evidence_and_date_inside_array_items(self):
        contract = [{
            "name": "warehouseResults", "type": "array", "required": True,
            "itemType": "object",
            "itemSchema": [
                {"name": "warehouse", "type": "string", "required": True},
                {"name": "evidenceIds", "type": "array", "required": True, "itemType": "string"},
                {"name": "observedAt", "type": "string", "required": True},
                {"name": "status", "type": "string", "required": True},
                {"name": "error", "type": "string", "required": True},
            ],
        }]
        invalid = {"warehouseResults": [{
            "warehouse": "East", "evidenceIds": [], "observedAt": "",
            "status": "SUCCEEDED", "error": "",
        }]}
        definition = {
            "flowName": "nested-semantic-final", "agents": ["worker"], "steps": ["worker"],
            "input": "fixture", "finalOutputSchema": contract,
        }
        ScriptAgent.trace = []
        ScriptAgent.scripts = {
            "worker": json.dumps(invalid).replace("{", "{{").replace("}", "}}"),
        }
        adapter = AgentownTFrameXAdapter(agent_classes={"worker": ScriptAgent})
        with self.assertRaisesRegex(ValueError, r"warehouseResults\[0\].*(evidenceIds|observedAt)"):
            asyncio.run(adapter.run(definition))

    def test_declared_tool_output_contract_rejects_undeclared_fields(self):
        definition = {
            "flowName": "strict-tool-output",
            "agents": [{
                "name": "tool-step",
                "kind": "tool",
                "toolName": "strict-tool",
                "tools": ["strict-tool"],
                "outputSchema": [{"name": "answer", "type": "string", "required": True}],
            }],
            "steps": ["tool-step"],
            "input": json.dumps({"question": "fixture"}),
        }
        adapter = AgentownTFrameXAdapter(
            tools={"strict-tool": lambda **_: {"answer": "ok", "internalPrompt": "must not leak"}},
        )
        with self.assertRaisesRegex(RuntimeError, "unexpected fields.*internalPrompt"):
            asyncio.run(adapter.run(definition))

    def test_workflow_input_contract_rejects_undeclared_fields_before_execution(self):
        definition = {
            "flowName": "strict-workflow-input",
            "agents": ["worker"],
            "steps": ["worker"],
            "input": json.dumps({"request": "fixture", "internalPrompt": "must not enter"}),
            "workflowInputSchema": [
                {"name": "request", "type": "string", "required": True},
            ],
        }
        ScriptAgent.trace = []
        ScriptAgent.scripts = {"worker": "unused"}
        adapter = AgentownTFrameXAdapter(agent_classes={"worker": ScriptAgent})
        with self.assertRaisesRegex(ValueError, "workflow input.*unexpected fields.*internalPrompt"):
            asyncio.run(adapter.run(definition))
        self.assertEqual(ScriptAgent.trace, [])

    def test_explicit_empty_workflow_input_contract_is_not_treated_as_unspecified(self):
        definition = {
            "flowName": "zero-input",
            "agents": ["worker"],
            "steps": ["worker"],
            "input": json.dumps({"unexpected": "must fail"}),
            "workflowInputSchema": [],
        }
        ScriptAgent.trace = []
        ScriptAgent.scripts = {"worker": "unused"}
        adapter = AgentownTFrameXAdapter(agent_classes={"worker": ScriptAgent})
        with self.assertRaisesRegex(ValueError, "workflow input.*unexpected fields.*unexpected"):
            asyncio.run(adapter.run(definition))
        self.assertEqual(ScriptAgent.trace, [])

    def test_workflow_input_contract_enforces_array_cardinality_before_execution(self):
        definition = {
            "flowName": "cardinality-workflow-input",
            "agents": ["worker"],
            "steps": ["worker"],
            "workflowInputSchema": [{
                "name": "items", "type": "array", "required": True,
                "minItems": 3, "maxItems": 3,
            }],
        }
        ScriptAgent.trace = []
        ScriptAgent.scripts = {"worker": "unused"}
        adapter = AgentownTFrameXAdapter(agent_classes={"worker": ScriptAgent})
        for items, message in ((["a", "b"], "fewer than 3"), (["a", "b", "c", "d"], "more than 3")):
            definition["input"] = json.dumps({"items": items})
            with self.assertRaisesRegex(ValueError, message):
                asyncio.run(adapter.run(definition))
        self.assertEqual(ScriptAgent.trace, [])

    def test_final_output_contract_rejects_instead_of_silently_projecting(self):
        definition = {
            "flowName": "strict-final-output",
            "agents": ["worker"],
            "steps": ["worker"],
            "input": "fixture",
            "finalOutputSchema": [
                {"name": "answer", "type": "string", "required": True},
            ],
        }
        ScriptAgent.trace = []
        ScriptAgent.scripts = {
            "worker": '{{"answer": "ok", "internalPrompt": "must not leak"}}',
        }
        adapter = AgentownTFrameXAdapter(agent_classes={"worker": ScriptAgent})
        with self.assertRaisesRegex(ValueError, "final output.*unexpected fields.*internalPrompt"):
            asyncio.run(adapter.run(definition))

    def test_reused_adapter_does_not_carry_a_previous_failure(self):
        class FailingOnceAgent(BaseAgent):
            calls = 0

            async def run(self, input_message, **kwargs):
                FailingOnceAgent.calls += 1
                if FailingOnceAgent.calls == 1:
                    raise RuntimeError("first run failed")
                return Message(role="assistant", content="recovered")

        definition = {
            "flowName": "retry-isolation",
            "agents": ["worker"],
            "steps": ["worker"],
            "input": "payload",
        }
        adapter = AgentownTFrameXAdapter(agent_classes={"worker": FailingOnceAgent})
        first = asyncio.run(adapter.run(definition))
        self.assertIn("first run failed", first["final"])
        second = asyncio.run(adapter.run(definition))
        self.assertEqual(second["final"], "recovered")
        self.assertEqual(second["trace"], [])


if __name__ == "__main__":
    unittest.main()
