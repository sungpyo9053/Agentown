from __future__ import annotations

from dataclasses import dataclass
import json
import re
from typing import Any, Callable, Mapping, Optional, Type

from tframex import Flow, TFrameXApp
from tframex.agents import BaseAgent
from tframex.agents import LLMAgent
from tframex.models.primitives import Message, ToolParameters
from tframex.patterns import (
    BasePattern,
    DiscussionPattern,
    ParallelPattern,
    RouterPattern,
    SequentialPattern,
)
from tframex.flows.flow_context import FlowContext
from tframex.util.engine import Engine
from tframex.util.llms import BaseLLMWrapper


class DefinitionError(ValueError):
    pass


class ExecutionNotConfigured(RuntimeError):
    code = "EXECUTION_NOT_CONFIGURED"


@dataclass(frozen=True)
class RegisteredTool:
    function: Callable[..., Any]
    description: Optional[str] = None
    parameters: Optional[ToolParameters] = None


class StructuredParallelPattern(BasePattern):
    """Expose full upstream ParallelPattern artifacts to the following Agent."""

    def __init__(self, pattern_name: str, tasks: list[str | BasePattern], result_field: str = "results"):
        super().__init__(pattern_name)
        self.delegate = ParallelPattern(pattern_name, tasks)
        self.result_field = result_field

    async def execute(self, flow_ctx: FlowContext, engine: Engine, agent_call_kwargs=None) -> FlowContext:
        result = await self.delegate.execute(flow_ctx, engine, agent_call_kwargs=agent_call_kwargs)
        artifacts = result.shared_data.get(f"{self.pattern_name}_results", [])
        failures = []
        values = []
        for artifact in artifacts:
            parts = artifact.get("parts") or []
            if parts and parts[0].get("type") == "text":
                failures.append(parts[0].get("text"))
                continue
            data = parts[0].get("data") if parts else None
            content = data.get("content") if isinstance(data, dict) else None
            try:
                values.append(json.loads(content) if isinstance(content, str) else content)
            except json.JSONDecodeError:
                values.append(content)
        if failures:
            raise RuntimeError(f"Parallel pattern '{self.pattern_name}' failed: {'; '.join(failures)}")
        initial = result.shared_data.get("_agentown_initial_input")
        envelope = dict(initial) if isinstance(initial, dict) else {}
        envelope.setdefault("request", initial if isinstance(initial, dict) else {})
        envelope.update({self.result_field: values, "failures": failures})
        result.update_current_message(Message(
            role="assistant",
            content=json.dumps(envelope, ensure_ascii=False),
        ))
        return result


class TracingLLMAgent(LLMAgent):
    async def run(self, input_message: Any, **kwargs: Any):
        content = input_message.content if isinstance(input_message, Message) else str(input_message)
        content = _apply_input_bindings(
            content,
            self.config.get("input_bindings") or [],
            self.config.get("input_defaults") or {},
        )
        name = self.agent_id.split("_ctx", 1)[0]
        trace = self.config.get("trace_sink")
        if trace is not None:
            trace.append({"kind": "agent_start", "agent": name, "input": content})
        try:
            self._validate_json_contract(content, self.config.get("input_schema") or [], "input")
            bound_message = Message(
                role=input_message.role if isinstance(input_message, Message) else "user",
                content=content,
            )
            result = await super().run(bound_message, **kwargs)
            self._validate_json_contract(result.content or "", self.config.get("output_schema") or [], "output")
        except Exception as exc:
            if trace is not None:
                trace.append({
                    "kind": "agent_error", "agent": name, "error": str(exc),
                    "code": getattr(exc, "code", None),
                })
            raise
        if trace is not None:
            trace.append({"kind": "agent_end", "agent": name, "output": result.content})
        return result

    @staticmethod
    def _validate_json_contract(content: str, fields: list[dict[str, Any]], label: str) -> None:
        if not fields:
            return
        try:
            value = json.loads(content)
        except json.JSONDecodeError as exc:
            raise ValueError(f"Agent {label} is not a JSON object") from exc
        if not isinstance(value, dict):
            raise ValueError(f"Agent {label} is not a JSON object")
        expected = {"string": str, "array": list, "object": dict, "boolean": bool, "number": (int, float), "integer": int}
        for field in fields:
            key = field["name"]
            if field.get("required") and key not in value:
                raise ValueError(f"Agent {label} is missing required field '{key}'")
            if key in value and field.get("type") in expected and not isinstance(value[key], expected[field["type"]]):
                raise ValueError(f"Agent {label} field '{key}' has invalid type")


class ToolExecutorAgent(BaseAgent):
    async def run(self, input_message: Any, **kwargs: Any):
        content = input_message.content if isinstance(input_message, Message) else str(input_message)
        content = _apply_input_bindings(
            content,
            self.config.get("input_bindings") or [],
            self.config.get("input_defaults") or {},
        )
        name = self.agent_id.split("_ctx", 1)[0]
        trace = self.config.get("trace_sink")
        tool_name = self.config["tool_name"]
        if trace is not None:
            trace.append({"kind": "agent_start", "agent": name, "input": content})
            trace.append({"kind": "tool_start", "agent": name, "tool": tool_name, "input": content})
        result = await self.config["engine"].execute_tool_by_llm_definition(tool_name, content)
        if isinstance(result, dict) and "error" in result:
            if trace is not None:
                trace.append({"kind": "tool_error", "agent": name, "tool": tool_name, "error": result["error"]})
            raise RuntimeError(str(result["error"]))
        output = json.dumps(result, ensure_ascii=False)
        if trace is not None:
            trace.append({"kind": "tool_end", "agent": name, "tool": tool_name, "output": output})
            trace.append({"kind": "agent_end", "agent": name, "output": output})
        return Message(role="assistant", content=output)


class ConditionRouterAgent(BaseAgent):
    """Deterministic route selector invoked by TFrameX RouterPattern."""

    async def run(self, input_message: Any, **kwargs: Any):
        content = input_message.content if isinstance(input_message, Message) else str(input_message)
        value = _json_object(content)
        if value is None:
            raise ValueError("Router input is not a JSON object")
        name = self.agent_id.split("_ctx", 1)[0]
        trace = self.config.get("trace_sink")
        if trace is not None:
            trace.append({"kind": "agent_start", "agent": name, "input": content})
        for condition in self.config.get("route_conditions") or []:
            actual = _resolve_path(value, str(condition.get("field") or ""))
            if actual is not _MISSING and _condition_matches(
                actual,
                str(condition.get("operator") or "EQUALS"),
                condition.get("value"),
            ):
                selected = str(condition["key"])
                if trace is not None:
                    trace.append({"kind": "router_select", "agent": name, "route": selected})
                    trace.append({"kind": "agent_end", "agent": name, "output": selected})
                return Message(role="assistant", content=selected)
        if trace is not None:
            trace.append({"kind": "agent_error", "agent": name, "error": "No route condition matched"})
        raise ValueError("No route condition matched")


class AgentownTFrameXAdapter:
    """Register Agentown definitions and execute them with unmodified TFrameX."""

    def __init__(
        self,
        *,
        agent_classes: Optional[Mapping[str, Type[BaseAgent]]] = None,
        tools: Optional[Mapping[str, RegisteredTool | Callable[..., Any]]] = None,
        llm: Optional[BaseLLMWrapper] = None,
    ) -> None:
        self.agent_classes = dict(agent_classes or {})
        self.tools = dict(tools or {})
        self.llm = llm
        self.trace: list[dict[str, Any]] = []

    async def run(self, definition: Mapping[str, Any]) -> dict[str, Any]:
        app = TFrameXApp(default_llm=self.llm, mcp_config_file=None)
        self._register_tools(app)
        self._register_agents(app, definition)
        flow = self.translate(definition)
        app.register_flow(flow)
        initial = definition.get("input", "")
        message = initial if isinstance(initial, Message) else Message(role="user", content=str(initial))
        initial_value = _json_object(message.content)
        shared_data = dict(definition.get("sharedData") or {})
        if initial_value is not None:
            shared_data["_agentown_initial_input"] = initial_value
        async with app.run_context() as runtime:
            context = await runtime.run_flow(
                flow,
                message,
                initial_shared_data=shared_data,
            )
        failures = [item for item in self.trace if item.get("kind") in {"agent_error", "tool_error"}]
        if failures:
            message = "; ".join(str(item.get("error")) for item in failures)
            if any(item.get("code") == ExecutionNotConfigured.code for item in failures):
                raise ExecutionNotConfigured(message)
            raise RuntimeError(message)
        final = context.current_message.content
        final_schema = list(definition.get("finalOutputSchema") or [])
        if final_schema:
            final_value = _json_object(final)
            if final_value is None:
                raise ValueError("Final output is not a JSON object")
            allowed = {str(field["name"]) for field in final_schema}
            final_value = {key: value for key, value in final_value.items() if key in allowed}
            final = json.dumps(final_value, ensure_ascii=False)
            TracingLLMAgent._validate_json_contract(final, final_schema, "final output")
        return {
            "final": final,
            "history": [item.model_dump(exclude_none=True) for item in context.history],
            "sharedData": context.shared_data,
            "trace": self.trace,
        }

    def translate(self, definition: Mapping[str, Any]) -> Flow:
        flow_name = self._required_string(definition, "flowName")
        pattern_definition = definition.get("pattern")
        if pattern_definition is None:
            steps = definition.get("steps")
            if not isinstance(steps, list) or not steps:
                raise DefinitionError("Flow requires a pattern or non-empty steps")
            root: BasePattern = SequentialPattern(pattern_name=f"{flow_name}_sequence", steps=[self._step(item) for item in steps])
        else:
            root = self._pattern(pattern_definition, "root")
        return Flow(flow_name=flow_name).add_step(root)

    def _register_tools(self, app: TFrameXApp) -> None:
        for name, registration in self.tools.items():
            if isinstance(registration, RegisteredTool):
                app.tool(name=name, description=registration.description, parameters_schema=registration.parameters)(registration.function)
            else:
                app.tool(name=name)(registration)

    def _register_agents(self, app: TFrameXApp, definition: Mapping[str, Any]) -> None:
        agents = definition.get("agents")
        if not isinstance(agents, list) or not agents:
            raise DefinitionError("At least one agent is required")
        for item in agents:
            config = {"name": item} if isinstance(item, str) else dict(item)
            name = self._required_string(config, "name")
            agent_class = self.agent_classes.get(name)
            if agent_class is None:
                if config.get("kind") == "tool":
                    agent_class = ToolExecutorAgent
                elif config.get("kind") == "router":
                    agent_class = ConditionRouterAgent
                elif self.llm is None:
                    raise ExecutionNotConfigured(f"Agent '{name}' has no executable implementation")
                else:
                    agent_class = TracingLLMAgent
            tools = config.get("tools") or []
            if any(tool not in self.tools for tool in tools):
                missing = [tool for tool in tools if tool not in self.tools]
                raise ExecutionNotConfigured(f"Agent '{name}' references unconfigured tools: {missing}")
            app.agent(
                name=name,
                description=config.get("description"),
                system_prompt=config.get("systemPrompt"),
                tools=list(tools),
                callable_agents=list(config.get("callableAgents") or []),
                agent_class=agent_class,
                trace_sink=self.trace,
                input_schema=list(config.get("inputSchema") or []),
                output_schema=list(config.get("outputSchema") or []),
                tool_name=config.get("toolName"),
                input_bindings=list(config.get("inputBindings") or []),
                input_defaults=dict(config.get("inputDefaults") or {}),
                route_conditions=list(config.get("routeConditions") or []),
            )(agent_class)

    def _step(self, value: Any) -> str | BasePattern:
        if isinstance(value, str) and value:
            return value
        if isinstance(value, Mapping):
            return self._pattern(value, "nested")
        raise DefinitionError(f"Invalid flow step: {value!r}")

    def _pattern(self, value: Any, suffix: str) -> BasePattern:
        if not isinstance(value, Mapping):
            raise DefinitionError("Pattern definition must be an object")
        kind = self._required_string(value, "type")
        name = str(value.get("name") or f"{kind}_{suffix}")
        if kind == "SequentialPattern":
            steps = value.get("steps")
            if not isinstance(steps, list) or not steps:
                raise DefinitionError("SequentialPattern requires non-empty steps")
            return SequentialPattern(name, [self._step(item) for item in steps])
        if kind == "ParallelPattern":
            tasks = value.get("tasks")
            if not isinstance(tasks, list) or not tasks:
                raise DefinitionError("ParallelPattern requires non-empty tasks")
            translated = [self._step(item) for item in tasks]
            if value.get("structuredFanIn") is True:
                return StructuredParallelPattern(name, translated, str(value.get("resultField") or "results"))
            return ParallelPattern(name, translated)
        if kind == "RouterPattern":
            routes = value.get("routes")
            if not isinstance(routes, Mapping) or not routes:
                raise DefinitionError("RouterPattern requires routes")
            default = value.get("defaultRoute")
            return RouterPattern(
                name,
                router_agent_name=self._required_string(value, "routerAgentName"),
                routes={str(key): self._step(target) for key, target in routes.items()},
                default_route=self._step(default) if default is not None else None,
            )
        if kind == "DiscussionPattern":
            participants = value.get("participantAgentNames")
            if not isinstance(participants, list) or not participants:
                raise DefinitionError("DiscussionPattern requires participantAgentNames")
            return DiscussionPattern(
                name,
                participant_agent_names=[str(item) for item in participants],
                discussion_rounds=int(value.get("discussionRounds", 1)),
                moderator_agent_name=value.get("moderatorAgentName"),
                stop_phrase=value.get("stopPhrase"),
            )
        raise DefinitionError(f"Unsupported TFrameX pattern: {kind}")

    @staticmethod
    def _required_string(value: Mapping[str, Any], key: str) -> str:
        result = value.get(key)
        if not isinstance(result, str) or not result.strip():
            raise DefinitionError(f"'{key}' must be a non-empty string")
        return result


_PATH_TOKEN = re.compile(r"([^.\[\]]+)|\[(\d+)\]")
_MISSING = object()


def _json_object(content: str) -> Optional[dict[str, Any]]:
    try:
        value = json.loads(content)
    except (json.JSONDecodeError, TypeError):
        return None
    return value if isinstance(value, dict) else None


def _resolve_path(value: Any, path: str) -> Any:
    current = value
    for name, index in _PATH_TOKEN.findall(path):
        if name:
            if not isinstance(current, dict) or name not in current:
                return _MISSING
            current = current[name]
        else:
            position = int(index)
            if not isinstance(current, list) or position >= len(current):
                return _MISSING
            current = current[position]
    return current


def _set_path(target: dict[str, Any], path: str, value: Any) -> None:
    tokens = _PATH_TOKEN.findall(path)
    current: Any = target
    for offset, (name, index) in enumerate(tokens):
        token: str | int = name if name else int(index)
        final = offset == len(tokens) - 1
        if isinstance(token, str):
            if final:
                current[token] = value
                return
            next_is_index = bool(tokens[offset + 1][1])
            current = current.setdefault(token, [] if next_is_index else {})
        else:
            while len(current) <= token:
                current.append(None)
            if final:
                current[token] = value
                return
            next_is_index = bool(tokens[offset + 1][1])
            if current[token] is None:
                current[token] = [] if next_is_index else {}
            current = current[token]


def _apply_input_bindings(
    content: str,
    bindings: list[dict[str, Any]],
    defaults: Mapping[str, Any] | None = None,
) -> str:
    value = _json_object(content)
    if value is None:
        return content
    source = dict(value)
    source.setdefault("request", dict(value))
    result = dict(source)
    for target_field, default_value in (defaults or {}).items():
        _set_path(result, str(target_field), default_value)
    for binding in bindings:
        source_field = str(binding.get("sourceField") or "")
        target_field = str(binding.get("targetField") or "")
        if not source_field or not target_field:
            continue
        resolved = _resolve_path(source, source_field)
        if resolved is not _MISSING:
            _set_path(result, target_field, resolved)
    return json.dumps(result, ensure_ascii=False)


def _condition_matches(actual: Any, operator: str, expected: Any) -> bool:
    if isinstance(actual, bool):
        expected_value: Any = str(expected).lower() == "true"
    elif isinstance(actual, (int, float)):
        try:
            expected_value = float(expected)
        except (TypeError, ValueError):
            return False
    else:
        expected_value = str(expected)
        actual = str(actual)
    if operator == "EQUALS":
        return actual == expected_value
    if operator == "LESS_THAN_OR_EQUALS":
        return actual <= expected_value
    if operator == "GREATER_THAN_OR_EQUALS":
        return actual >= expected_value
    raise ValueError(f"Unsupported route operator: {operator}")
