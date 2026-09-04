"""First red differential test for the pinned TFrameX runtime contract.

Run with TFrameX 1.1.0 on PYTHONPATH. This deliberately contains no business-
specific prompt or result rule. The test remains red until the thin Agentown
adapter exists and executes this same definition through pinned TFrameX.
"""

import asyncio
import time
import unittest

from tframex import Flow, TFrameXApp
from tframex.agents import BaseAgent
from tframex.models.primitives import Message
from tframex.patterns import ParallelPattern, SequentialPattern


DEFINITION = {
    "flowName": "generic-differential",
    "agents": ["left", "right", "reporter"],
    "pattern": {
        "type": "SequentialPattern",
        "name": "sequence",
        "steps": [
            {"type": "ParallelPattern", "name": "parallel", "tasks": ["left", "right"]},
            "reporter",
        ],
    },
    "input": "fixture-input",
}


class TraceAgent(BaseAgent):
    trace = None

    async def run(self, input_message, **kwargs):
        content = input_message.content if isinstance(input_message, Message) else str(input_message)
        name = self.agent_id.split("_ctx", 1)[0]
        self.trace.append(("start", name, content, time.monotonic()))
        if name in {"left", "right"}:
            await asyncio.sleep(0.05)
        output = f"{name}({content})"
        self.trace.append(("end", name, output, time.monotonic()))
        return Message(role="assistant", content=output)


async def run_upstream():
    trace = []
    TraceAgent.trace = trace
    app = TFrameXApp(default_llm=None)
    for name in DEFINITION["agents"]:
        app.agent(name=name, agent_class=TraceAgent)(lambda: None)
    parallel_definition = DEFINITION["pattern"]["steps"][0]
    parallel = ParallelPattern(pattern_name=parallel_definition["name"], tasks=parallel_definition["tasks"])
    sequence = SequentialPattern(pattern_name=DEFINITION["pattern"]["name"], steps=[parallel, "reporter"])
    flow = Flow(flow_name=DEFINITION["flowName"]).add_step(sequence)
    app.register_flow(flow)
    async with app.run_context() as runtime:
        result = await runtime.run_flow(flow, Message(role="user", content=DEFINITION["input"]))
    return {"trace": trace, "final": result.current_message.content}


async def run_candidate():
    from agentown_tframex_adapter import AgentownTFrameXAdapter

    trace = []
    TraceAgent.trace = trace
    adapter = AgentownTFrameXAdapter(agent_classes={name: TraceAgent for name in DEFINITION["agents"]})
    result = await adapter.run(DEFINITION)
    return {"trace": trace, "final": result["final"]}


class CoreRuntimeDifferentialTest(unittest.TestCase):
    def test_same_definition_has_source_parity(self):
        upstream = asyncio.run(run_upstream())
        candidate = asyncio.run(run_candidate())
        self.assertEqual([event[:3] for event in candidate["trace"]], [event[:3] for event in upstream["trace"]])
        self.assertEqual(candidate["final"], upstream["final"])

        starts = {name: timestamp for kind, name, _, timestamp in candidate["trace"] if kind == "start"}
        ends = {name: timestamp for kind, name, _, timestamp in candidate["trace"] if kind == "end"}
        self.assertLess(starts["left"], ends["right"])
        self.assertLess(starts["right"], ends["left"])
        self.assertGreater(starts["reporter"], max(ends["left"], ends["right"]))


if __name__ == "__main__":
    unittest.main()
