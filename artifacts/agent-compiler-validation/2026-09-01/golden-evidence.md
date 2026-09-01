# Golden test evidence

## Before correction

The first Golden run failed before the production corrections were applied:

- strict schema rejected `news.search.mock` because `lookbackHours` was absent;
- edge bindings could not be represented by the strict LLM DTO;
- generic FAQ text was incorrectly forced into the Notion-specific path;
- CSV comparison was modeled as an AI step instead of a deterministic function;
- the Slack-to-email follow-up did not produce a structural replacement and a new synchronized package version.

During correction, the version Golden also exposed two server defects rather than being bypassed:

1. after replacing outbound Slack with email, semantic validation combined the inbound Slack trigger and email send step and falsely demanded a Slack output;
2. the graph was patched but the package resource plan remained stale and still exported the Slack connector.

The recorded failing assertions were `WORKFLOW_REQUIREMENT_MISMATCH` and missing `connector.email.mock` in `tools/tools.yaml`. Both were corrected in production code.

## After correction

Commands and outcomes:

- `./gradlew :backend:test --tests com.agentvillage.builder.AgentCompilerGoldenTest --tests com.agentvillage.builder.AgentCompilerVersionGoldenIntegrationTest --tests com.agentvillage.builder.MetaAgentSchemaStrictnessTest` — PASS, 3 tests.
- `REAL_AGENT_COMPILER_GOLDEN=true REAL_CODEX_MODEL=gpt-5.6-luna ./gradlew :backend:test --tests com.agentvillage.builder.AgentCompilerRealGoldenTest` — PASS in 1m44s using actual Codex structured output.
- `./gradlew :backend:test :backend:build` — PASS.
- Node 20 `npm run typecheck`, `npm run lint`, and `npm run build` — PASS; lint reports one pre-existing font warning and no errors.

The real-model report is generated at `backend/build/reports/agent-compiler-real-golden.json`. The compact, reviewable result is preserved in `actual-graph-results.json`.
