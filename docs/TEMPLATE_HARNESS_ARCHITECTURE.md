# Template-first Harness Engineering

## Product decision

Agentown does not create one AI agent for every workflow step. It first matches an approved
workflow template, implements deterministic work with catalog nodes, and creates the minimum
number of AI agents needed for natural-language judgment.

The first reference intent is:

> Collect stock-market news every day, produce a structured impact report, and deliver it to Slack.

Its target design is one report agent, not four specialist agents:

`schedule.trigger -> news.search -> data.deduplicate -> ai.generate -> human.approval -> slack.send`

Collection, scheduling, deduplication, approval persistence, and delivery are node responsibilities.
Only report synthesis is an AI-agent responsibility. A separate verifier is allowed only when the
user requests independent review or the risk policy requires it.

## Sources of truth

1. An Agentown-owned Notion data source is the human authoring and review surface.
2. Notion content is never executed directly.
3. A sync imports an approved template into a staging object and validates every structured field.
4. Only a validated immutable DB version can become `ACTIVE`.
5. A Workflow Version records the selected template key and template version.
6. An already activated workflow continues using its pinned DB version if Notion is unavailable.

Customer Notion OAuth is a different concern. The template library uses one read-only internal
Notion connection owned by Agentown; customer workflows later use per-workspace OAuth connections.

## Automation and output template separation

An automation version defines when to run, what to collect, decisions, approvals, and where to
deliver. An output template version defines how collected data becomes a stable result. They are
separate entities so one market-report template can be reused by a daily Slack workflow and a
weekly email workflow.

`output_template_version_id` pins one immutable execution bundle:

- content schema version;
- renderer key and renderer version;
- quality-rule version and exact rules;
- prompt version;
- model policy, including maximum calls and deterministic settings;
- source-policy version.

The lifecycle is `DRAFT -> PREVIEWED -> APPROVED -> ACTIVE -> DEPRECATED`. Content is immutable
after approval. A natural-language change clones the current version, creates a structured patch,
stores a new draft, renders old/new previews through the production renderer, and switches a
scheduled workflow only after explicit approval. Existing schedules keep their pinned version,
rollback changes only the pin, and versions are deprecated rather than deleted.

## Notion data source contract

Each row represents one template version and contains:

| Property | Type | Required | Meaning |
| --- | --- | --- | --- |
| Name | title | yes | Operator-facing name |
| Template Key | rich text | yes | Stable lowercase key |
| Version | number | yes | Monotonic version |
| Status | select | yes | `DRAFT`, `APPROVED`, or `DEPRECATED` |
| Category | select | yes | Reporting, writing, support, classification, etc. |
| Intent Examples | rich text | yes | Positive matching examples |
| Required Facts | multi-select | yes | Facts that must be clarified before compilation |
| Risk Level | select | yes | `LOW`, `MEDIUM`, `HIGH` |
| Template Definition JSON | page code block | yes | Content, renderer, prompt, model and source-policy versions |
| Output Schema JSON | page code block | yes | Runtime structured output contract |
| Acceptance Cases JSON | page code block | yes | Deterministic positive and negative cases |

Secrets, OAuth tokens, `connection_id` values, and customer identifiers are forbidden.

## Compiler policy

The compiler applies these rules in order:

1. Clarify missing required facts.
2. Match the latest active DB template version.
3. Prefer deterministic nodes for I/O and transformations.
4. Start with one AI agent.
5. Add another agent only with a machine-readable rationale:
   - independent high-risk verification;
   - explicitly different expertise;
   - incompatible input/output contract;
   - user-requested separation of duties.
6. Reject an AI node without an Agent Definition.
7. Reject an Agent Definition not referenced by an AI node.
8. Estimate AI calls per run from reachable AI nodes.
9. Require approval on every path to an external write node.

## Versioned DB snapshot

The registry stores `output_templates` and immutable `output_template_versions`, separately from
`builder_workflows` and `builder_workflow_versions`.
The version includes the Notion page ID, content hash, match rules, graph plan, agent definitions,
guide definitions, output schema, acceptance cases, validation result, and timestamps. Syncing the
same content is idempotent. A changed approved row creates a new version; it never mutates a version
already pinned by a workflow.

## Release slices and gates

### Slice A - compiler core

- Built-in daily news report seed template.
- Template matching and minimum-agent policy.
- Structured output schema in the harness package.
- AI call estimate and separation rationale.
- Mock-only schedule, news, deduplication, and Slack delivery nodes.

Gate: the full daily-news sentence compiles to one agent and one AI node, while vague requests ask
for source, schedule, destination, and approval policy.

### Slice B - Notion template authoring sync

- Read-only internal Notion connection.
- Manual admin sync first; webhook sync later.
- Staging validation, content hashing, immutable activation, and audit logs.

Gate: malformed, unapproved, unknown-node, or secret-bearing rows cannot become active.

### Slice C - real connectors

- Actual news provider, persistent scheduler, and Slack send.
- Per-workspace `connection_id`, idempotency, retry, approval pause/resume, and run logs.

Gate: one scheduled production run performs one collection and at most one approved Slack send.

## Human-only setup

The operator must create the Agentown internal Notion connection, create/share the template data
source, and place the token/data-source ID in the production secret store. The operator must also
create/configure the Slack App and approve its workspace installation. Code, validation, database
migrations, deployment, and operational checks remain automated.
