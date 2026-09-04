# Reference implementation analysis

## Tesslate Agent-Builder

- Analyzed commit: `76655ca84cac8c6e63230b7526eb7e24bec29a0a`
- License: MIT
- UI entry: `builder/frontend/src/store.js:784` `sendChatMessageToFlowBuilder()` posts the user message, available components and the current flow to `/chatbot_flow_builder`.
- API entry: `builder/backend/routes/chatbot.py:131` accepts that request and calls `execute_chatbot_logic()`.
- Orchestration: `builder/backend/routes/chatbot.py:19` calls `OrchestratorAgent`; a literal `FLOW_INSTRUCTION:` marker is parsed at line 41 and sent to `FlowBuilderAgent` at line 58.
- Prompt contract: `builder/backend/agents/orchestrator_agent.py:173` defines the instruction-producing prompt. `builder/backend/agents/flow_builder_agent.py:10` asks for ReactFlow JSON using the supplied component definitions and current flow.
- Canvas application: the frontend parses the returned flow, regenerates client IDs and merges it into the ReactFlow store in `builder/frontend/src/store.js` after the API call.
- Validation boundary: the backend checks that the top-level result is a list; important node registration and merge behavior remains on the client.

### Direct execution evidence

The backend was run locally with an authenticated Codex proxy and the reference input. It generated `SequentialPattern`, `QuickResearchAgent`, `ContentGeneratorAgent`, `human_approval_gate`, and `ConversationalAssistant`. The output contained a cycle, did not configure an FAQ tool, and used an unregistered approval node. A follow-up change rewrote the full graph and introduced an unregistered `http_request_tool` rather than producing a structural patch. The full reference frontend build was blocked by its missing `builder/frontend/src/nodes/tframex/TriggerNode` module.

This establishes the architectural pattern, but not a result that can safely be copied as Agentown's server execution contract.

## Nexent

- Analyzed commit: `a7c026a3108f90df41bbda5c1c2282ee74f39dd4`
- License: MIT
- API entry: `backend/apps/agent_app.py:195` exposes `POST /nl2agent/run`.
- Run assembly: `backend/services/nl2agent_service.py:1274` builds the NL-to-agent run contract and line 1379 streams it.
- Agent/tool boundary: `backend/agents/nl2agent_agent.py` defines the generation tools and bounded step count.

Agentown reused the ideas of a runtime-neutral resource/package contract and bounded structured generation. No source code was copied from Tesslate or Nexent, so no third-party source or license text is embedded in this change.

## Behavioral decision

Agentown keeps Tesslate's useful separation of requirement orchestration from graph composition, but moves ID normalization, node catalog checks, field bindings, versioning and package generation to the server. Unsupported resources are recorded as unresolved Mock connectors; they are not invented or displayed as connected.
