"""Differential contracts between direct TFrameX and the Agentown adapter."""

import asyncio
import json
import unittest

from tframex import Flow, TFrameXApp
from tframex.agents import BaseAgent
from tframex.models.primitives import Message
from tframex.patterns import DiscussionPattern, ParallelPattern, RouterPattern, SequentialPattern
from tframex.util.llms import BaseLLMWrapper

from agentown_tframex_adapter import AgentownTFrameXAdapter


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
        result = asyncio.run(AgentownTFrameXAdapter(llm=JsonFixtureLLM()).run(definition))
        self.assertIn("failed", result["final"].lower())
        self.assertNotIn("reporter", [item["agent"] for item in result["trace"] if item["kind"] == "agent_start"])


if __name__ == "__main__":
    unittest.main()
