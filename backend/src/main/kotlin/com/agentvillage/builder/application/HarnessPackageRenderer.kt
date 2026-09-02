package com.agentvillage.builder.application

import com.agentvillage.builder.domain.AgentDefinition
import com.agentvillage.builder.domain.GuideDefinition
import com.agentvillage.builder.domain.MetaAgentDesignBundle
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

@Component
class HarnessPackageRenderer(private val mapper: ObjectMapper) {
    fun render(bundle: MetaAgentDesignBundle): Map<String, String> {
        val withResources = if (bundle.proposal.resourcePlan == null) bundle.copy(
            proposal = bundle.proposal.copy(resourcePlan = BuilderCapabilityResolver().resolve(bundle)),
        ) else bundle
        val normalized = if (withResources.proposal.agentDesign == null) withResources.copy(
            proposal = withResources.proposal.copy(agentDesign = AgentDesignAssembler().assemble(withResources)),
        ) else withResources
        val plan = requireNotNull(normalized.proposal.graphPlan) { "proposal.graphPlan is required" }
        val agents = normalized.agentDefinitions
        val resources = requireNotNull(normalized.proposal.resourcePlan)
        return linkedMapOf<String, String>().apply {
            put("agent.yaml", agentYaml(normalized))
            put("workflow.yaml", workflowYaml(normalized))
            put("prompts/system.md", agents.takeIf { it.isNotEmpty() }?.joinToString("\n\n---\n\n") { agentMarkdown(it) }
                ?: "# Deterministic package\n\n이 패키지는 AI Agent 없이 검증된 Function만 실행합니다.\n")
            put("prompts/reviewer.md", reviewerPrompt(normalized))
            put("schemas/input.schema.json", pretty(outputSchema(agents.flatMap { it.inputSchema }.distinctBy { it.name })))
            put("schemas/output.schema.json", pretty(outputSchema(agents.lastOrNull()?.outputSchema ?: normalized.proposal.outputSchema)))
            put("skills/README.md", "# Skills\n\n이 패키지에 고정된 Skill이 있으면 이 폴더에 추가합니다. 현재는 서버 카탈로그의 Template Skill만 참조합니다.\n")
            put("tools/tools.yaml", toolsYaml(resources))
            put("mcp.json", pretty(mapOf("mcpServers" to emptyMap<String, Any>())))
            put("examples/sample-input.json", pretty(normalized.proposal.agentDesign?.simulationScenarios?.firstOrNull()?.input ?: mapOf("text" to "샘플 입력")))
            put("runtime-targets.json", pretty(mapOf(
                "targets" to listOf(
                    mapOf("key" to "python-local", "mode" to "MOCK_TEST_ONLY", "entrypoint" to "runners/python/runner.py"),
                    mapOf("key" to "generic-package", "mode" to "CONTRACT_EXPORT", "entrypoint" to "agent.yaml"),
                ),
            )))
            put("runners/python/runner.py", pythonMockRunner())
            put(".env.example", environmentExample(resources))
            put("README.md", packageReadme(normalized))
            put("design-bundle.json", pretty(normalized))
            put("workflow.json", pretty(linkedMapOf(
                "schemaVersion" to "1.0", "name" to normalized.proposal.name,
                "entryNodeId" to plan.entryNodeId, "nodes" to plan.nodes, "edges" to plan.edges,
                "agentKeys" to normalized.agentDefinitions.map { it.key },
                "guideKeys" to normalized.guideDefinitions.map { it.key },
            )))
            put("CODEX.md", orchestration(normalized))
            put("AGENTS.md", entrypoint())
            put("manifest.json", pretty(linkedMapOf(
                "format" to "agentown-agent-package/v1", "name" to normalized.proposal.name,
                "agentCount" to normalized.agentDefinitions.size, "guideCount" to normalized.guideDefinitions.size,
                "templateSelection" to normalized.proposal.templateSelection,
                "economics" to normalized.proposal.economics,
                "executionReadiness" to normalized.proposal.agentDesign?.executionReadiness,
                "supportedRuntimeTargets" to listOf("python-local", "generic-package"),
                "validationRequiredBeforeImport" to true,
            )))
            put("schemas/final-output.schema.json", pretty(outputSchema(normalized.proposal.outputSchema)))
            put("templates/output-template.json", pretty(linkedMapOf(
                "templateSelection" to normalized.proposal.templateSelection,
                "executionContract" to normalized.proposal.executionContract,
                "contentSchema" to outputSchema(normalized.proposal.outputSchema),
            )))
            put("policies/permissions.json", pretty(linkedMapOf(
                "arbitraryCodeAllowed" to false,
                "secretsInWorkflowAllowed" to false,
                "externalWritesRequireApproval" to true,
            )))
            put("policies/ai-budget.json", pretty(linkedMapOf(
                "agentCount" to bundle.agentDefinitions.size,
                "estimatedAiCallsPerRun" to (bundle.proposal.economics?.estimatedAiCallsPerRun ?: bundle.proposal.graphPlan?.nodes.orEmpty().count { it.nodeType.startsWith("ai.") }),
                "separationRationale" to bundle.proposal.economics?.separationRationale.orEmpty(),
            )))
            put("policies/quality-rules.json", pretty(bundle.proposal.executionContract?.qualityRules ?: emptyMap<String, Any>()))
            normalized.agentDefinitions.forEach { put("agents/${it.key}.md", agentMarkdown(it)) }
            if (normalized.agentDefinitions.isEmpty()) put("agents/README.md", "# Agents\n\n이 패키지는 결정론적 Function만 사용하며 AI Agent가 없습니다.\n")
            normalized.guideDefinitions.forEach { put("guides/${it.key}.md", guideMarkdown(it)) }
        }
    }

    private fun agentYaml(bundle: MetaAgentDesignBundle) = buildString {
        appendLine("format: agentown-agent-package/v1")
        appendLine("name: ${yaml(bundle.proposal.name)}")
        appendLine("version: 1")
        appendLine("goal: ${yaml(bundle.requirement.objective)}")
        appendLine("purpose: ${yaml(bundle.requirement.objective)}")
        appendLine("input_schema: schemas/input.schema.json")
        appendLine("output_schema: schemas/output.schema.json")
        appendLine("status: ${bundle.proposal.agentDesign?.status}")
        appendLine("execution_readiness: ${bundle.proposal.agentDesign?.executionReadiness}")
        appendLine("agents:")
        bundle.agentDefinitions.forEach { agent ->
            appendLine("  - key: ${yaml(agent.key)}")
            appendLine("    name: ${yaml(agent.name)}")
            appendLine("    prompt: ${yaml("prompts/system.md")}")
            appendLine("    memory_scope: ${yaml(agent.memoryScope)}")
            appendLine("    tools: [${agent.toolKeys.joinToString(", ") { yaml(it) }}]")
            appendLine("    connectors: [${agent.connectorKeys.joinToString(", ") { yaml(it) }}]")
        }
        appendLine("workflow: [${bundle.proposal.graphPlan?.nodes.orEmpty().joinToString(", ") { yaml(it.id) }}]")
        appendLine("required_environment: [${requiredEnvironment(requireNotNull(bundle.proposal.resourcePlan)).joinToString(", ") { yaml(it) }}]")
    }

    private fun workflowYaml(bundle: MetaAgentDesignBundle) = buildString {
        val plan = requireNotNull(bundle.proposal.graphPlan)
        appendLine("format: agentown-workflow/v1")
        appendLine("entry_node_id: ${yaml(plan.entryNodeId)}")
        appendLine("nodes:")
        plan.nodes.forEach { node ->
            appendLine("  - id: ${yaml(node.id)}")
            appendLine("    type: ${yaml(node.nodeType)}")
            appendLine("    label: ${yaml(node.label)}")
            node.config["agentKey"]?.let { appendLine("    agent_key: ${yaml(it.toString())}") }
        }
        appendLine("edges:")
        plan.edges.forEach { edge -> appendLine("  - { source: ${yaml(edge.source)}, target: ${yaml(edge.target)}, condition: ${yaml(edge.condition)} }") }
    }

    private fun toolsYaml(resources: com.agentvillage.builder.domain.ResourcePlan) = buildString {
        appendLine("format: agentown-tools/v1")
        appendLine("tools:")
        resources.bindings.forEach { tool ->
            appendLine("  - key: ${yaml(tool.resourceKey)}")
            appendLine("    kind: ${tool.resourceKind}")
            appendLine("    availability: ${tool.availability}")
            appendLine("    simulation_only: ${tool.simulationOnly}")
            appendLine("    requires_user_action: ${tool.requiresUserAction}")
        }
    }

    private fun reviewerPrompt(bundle: MetaAgentDesignBundle) = """
        # Design Reviewer

        Verify the output schema, evidence requirements, forbidden rules, and approval policy before accepting an output.
        Never claim that a Mock Connector performed a real external action.

        Design status: ${bundle.proposal.agentDesign?.status}
        Execution readiness: ${bundle.proposal.agentDesign?.executionReadiness}
    """.trimIndent() + "\n"

    private fun environmentExample(resources: com.agentvillage.builder.domain.ResourcePlan): String = buildString {
        appendLine("# Agentown does not include secrets in Agent Packages.")
        requiredEnvironment(resources).forEach { appendLine("$it=") }
    }

    private fun requiredEnvironment(resources: com.agentvillage.builder.domain.ResourcePlan): List<String> = buildList {
        if (resources.bindings.any { it.resourceKey.contains("slack") }) add("SLACK_BOT_TOKEN")
        if (resources.bindings.any { it.resourceKey.contains("notion") }) add("NOTION_TOKEN")
        if (resources.bindings.any { it.resourceKey.contains("email") }) add("EMAIL_CONNECTION")
        if (resources.bindings.any { it.resourceKey.contains("news") && !it.simulationOnly }) add("NEWS_API_KEY")
        resources.bindings.filter { it.availability == com.agentvillage.builder.domain.ResourceAvailability.MISSING }.forEach {
            add(it.label.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_') + "_CONNECTION")
        }
    }

    private fun packageReadme(bundle: MetaAgentDesignBundle) = """
        # ${bundle.proposal.name}

        ${bundle.proposal.summary}

        ## What Agentown verified

        - Structured Agent and Workflow contracts
        - Server catalog resource references
        - Graph connectivity and approval gates
        - Safe Mock simulation contract

        ## Execution boundary

        ${if (bundle.proposal.resourcePlan?.productionReady == true) "Configured resources can be connected by a compatible runtime." else "Design complete, real execution not configured. Mock connectors never perform external writes."}

        Import `agent.yaml` and `workflow.yaml` into a compatible runner. Provide secrets through the runner, never by editing prompts or workflow files.

        ## Local Mock test

        `python3 runners/python/runner.py` validates the package and runs the sample without external network calls.
        Add `--approve` to continue past the sample human-approval node. This runner is a test adapter, not a production runtime.
    """.trimIndent() + "\n"

    private fun pythonMockRunner() = """
        #!/usr/bin/env python3
        "Fixed Agentown mock runner. It never performs network or arbitrary-code execution."
        import argparse, csv, io, json, re
        from pathlib import Path

        def fail(reason, steps=None):
            print(json.dumps({"status": "FAILED", "reason": reason, "externalCallPerformed": False, "steps": steps or []}, ensure_ascii=False, indent=2))
            raise SystemExit(2)

        def valid_type(value, expected):
            return {"string": isinstance(value, str), "array": isinstance(value, list), "object": isinstance(value, dict), "boolean": isinstance(value, bool), "number": isinstance(value, (int, float)) and not isinstance(value, bool), "integer": isinstance(value, int) and not isinstance(value, bool)}.get(expected, True)

        def validate_fields(fields, value, label):
            for field in fields:
                name = field["name"]
                if field.get("required") and name not in value: fail(f"{label}: required field {name} is missing", steps)
                if name in value and not valid_type(value[name], field.get("type", "string")): fail(f"{label}: field {name} has invalid type", steps)

        def validate_schema(schema, value, label):
            for name in schema.get("required", []):
                if name not in value: fail(f"{label}: required field {name} is missing", steps)
            properties = schema.get("properties", {})
            if schema.get("additionalProperties") is False and any(name not in properties for name in value): fail(f"{label}: undeclared field is present", steps)
            for name, item in properties.items():
                if name in value and not valid_type(value[name], item.get("type", "string")): fail(f"{label}: field {name} has invalid type", steps)

        def complete_agent_output(agent, data):
            generated = next((data.get(name) for name in ("draftResponse", "draft", "result", "summary", "report", "reproductionSteps") if isinstance(data.get(name), str)), "제공된 근거와 입력을 선언된 출력 계약에 맞춰 처리한 검증용 결과입니다.")
            defaults = {"string": generated, "array": [], "object": {}, "boolean": False, "number": 0, "integer": 0}
            for field in agent.get("outputSchema", []):
                data.setdefault(field["name"], defaults.get(field.get("type", "string"), generated))

        def terms(value):
            stop = {"언제", "어떻게", "해주세요", "알려주세요", "문의", "faq", "관련", "대한"}
            return {re.sub(r"(에서|으로|에게|부터|까지|은|는|이|가|을|를|과|와|도|만)$", "", item) for item in re.findall(r"[가-힣A-Za-z0-9]{2,}", str(value).lower()) if item not in stop}

        def csv_rows(value):
            if isinstance(value, list): return [row for row in value if isinstance(row, dict)]
            if isinstance(value, str) and value.strip(): return list(csv.DictReader(io.StringIO(value)))
            return []

        SAFE = {"manual.trigger", "schedule.trigger", "text.input", "news.search.mock", "knowledge.search.mock", "flight.search.mock", "github.issue.mock", "parallel.map.mock", "tool.unresolved", "data.csv.compare", "data.deduplicate", "data.normalize", "quality.check", "template.render", "workflow.end", "condition.branch", "ai.classify", "ai.generate", "human.approval", "slack.new_message.mock", "slack.reply.mock", "slack.send.mock", "email.send.mock", "notion.search.mock", "notion.read_page.mock"}
        root = Path(__file__).resolve().parents[2]
        graph = json.loads((root / "workflow.json").read_text())
        design = json.loads((root / "design-bundle.json").read_text())
        agents = {agent["key"]: agent for agent in design.get("agentDefinitions", [])}
        final_schema = json.loads((root / "schemas/final-output.schema.json").read_text())
        data = json.loads((root / "examples/sample-input.json").read_text())
        args = argparse.ArgumentParser()
        args.add_argument("--approve", action="store_true")
        approved = args.parse_args().approve
        steps = []
        nodes = {node["id"]: node for node in graph["nodes"]}
        outgoing = {}
        for edge in graph["edges"]: outgoing.setdefault(edge["source"], []).append(edge)
        node_id = graph["entryNodeId"]
        traversed = 0
        while node_id is not None:
            traversed += 1
            if traversed > len(nodes) + 1: raise SystemExit("unsafe graph traversal")
            node = nodes[node_id]
            kind = node["nodeType"]
            config = node.get("config", {})
            if kind not in SAFE:
                raise SystemExit(f"unsupported node: {kind}")
            agent = agents.get(str(config.get("agentKey", "")))
            if agent: validate_fields(agent.get("inputSchema", []), data, f"node {node['id']} input")
            if kind == "manual.trigger":
                inquiry = data.get("customerInquiry", data.get("question", data.get("message", data.get("text"))))
                if inquiry is not None:
                    for alias in ("customerInquiry", "question", "message", "text"): data.setdefault(alias, inquiry)
            elif kind == "notion.search.mock": data["notionResult"] = "환불은 승인 후 영업일 기준 3~5일 이내 처리됩니다."
            elif kind == "knowledge.search.mock":
                inquiry = data.get(config.get("queryField"), data.get("customerInquiry", data.get("question", data.get("message", data.get("text", "")))))
                candidates = data.get("mockSearchResults", [{"title": "배송 FAQ", "content": "배송은 주문 후 영업일 기준 2~3일 이내 도착합니다."}])
                inquiry_terms = terms(inquiry)
                results = [item for item in candidates if isinstance(item, dict) and str(item.get("content", "")).strip() and inquiry_terms.intersection(terms(str(item.get("title", "")) + " " + str(item.get("content", ""))))]
                data.update({"customerInquiry": inquiry, "faqResults": results, "evidenceFound": bool(results), "needsAssigneeReview": not bool(results), "externalCallPerformed": False})
            elif kind == "flight.search.mock":
                price = int(data.get("mockFlightPrice", 250000)); maximum = int(config.get("maximumPrice", 200000))
                data.update({"price": price, "priceWithinBudget": price <= maximum, "searchResult": {"price": price, "currency": "KRW"}, "externalCallPerformed": False})
            elif kind == "github.issue.mock": data.update({"issueTitle": "Mock issue", "issueBody": "Mock issue body"})
            elif kind == "parallel.map.mock": data["parallelResults"] = [{"subject": item, "result": "Mock research"} for item in config.get("items", [])]
            elif kind == "tool.unresolved": data.update({"requiresUserAction": True, "unresolvedTool": config.get("toolName"), "externalCallPerformed": False})
            elif kind == "data.csv.compare":
                before_rows = csv_rows(data.get("csvA", data.get("rowsA", [])))
                after_rows = csv_rows(data.get("csvB", data.get("rowsB", [])))
                requested_keys = [key for key in config.get("keyColumns", []) if key != "사용자 지정 키"]
                key_columns = requested_keys or list((before_rows[0] if before_rows else after_rows[0] if after_rows else {}).keys())[:1]
                key_of = lambda row, i: "|".join(str(row.get(key, "")) for key in key_columns) if key_columns else str(i)
                before = {key_of(row, i): row for i, row in enumerate(before_rows)}
                after = {key_of(row, i): row for i, row in enumerate(after_rows)}
                added = [{"changeType": "ADDED", "key": key, "after": after[key]} for key in sorted(after.keys() - before.keys())]
                removed = [{"changeType": "REMOVED", "key": key, "before": before[key]} for key in sorted(before.keys() - after.keys())]
                modified = [{"changeType": "MODIFIED", "key": key, "before": before[key], "after": after[key]} for key in sorted(before.keys() & after.keys()) if before[key] != after[key]]
                data.update({"addedRows": added, "removedRows": removed, "modifiedRows": modified, "changedRows": added + removed + modified, "externalCallPerformed": False})
            elif kind == "data.normalize":
                data["normalizedText"] = str(data.get("message", data.get("text", ""))).strip()
            elif kind == "news.search.mock": data["newsItems"] = [{"title": "Mock AI news", "url": "https://example.com/mock"}]
            elif kind == "ai.generate":
                evidence = " ".join(item.get("content", "") for item in data.get("faqResults", []))
                result = ("FAQ 근거에 따르면 " + evidence) if evidence else "제공된 입력을 출력 스키마에 맞춰 처리한 샘플 결과입니다."
                output_field = config.get("outputField")
                if output_field:
                    data[output_field] = result
                    if output_field == "draftResponse": data["needsAssigneeReview"] = False
                else:
                    data.update({"draft": result, "result": result, "summary": result, "report": result, "reproductionSteps": result})
                if agent: complete_agent_output(agent, data)
            elif kind == "quality.check": data["qualityPassed"] = any(str(value).strip() for value in data.values())
            elif kind == "template.render": data["rendered"] = str(data.get("report", data.get("result", data.get("draft", data.get("draftResponse", json.dumps(data.get("changedRows", []), ensure_ascii=False))))))
            elif kind == "human.approval" and not approved:
                steps.append({"node": node["id"], "status": "WAITING_APPROVAL", "output": data})
                print(json.dumps({"status": "WAITING_APPROVAL", "externalCallPerformed": False, "steps": steps}, ensure_ascii=False, indent=2))
                raise SystemExit(0)
            elif kind.startswith("slack.") and ("reply" in kind or "send" in kind): data.update({"wouldSend": True, "message": data.get("draft", data.get("rendered", "")), "externalCallPerformed": False})
            elif kind == "email.send.mock": data.update({"wouldSend": True, "message": data.get("draft", data.get("rendered", "")), "externalCallPerformed": False})
            if agent: validate_fields(agent.get("outputSchema", []), data, f"node {node['id']} output")
            steps.append({"node": node["id"], "status": "SUCCEEDED", "output": data})
            edges = outgoing.get(node_id, [])
            if kind == "condition.branch":
                matched = []
                for edge in edges:
                    field, expected = edge.get("condition", "").split("=", 1)
                    if str(data.get(field)).lower() == expected.lower(): matched.append(edge)
                if len(matched) != 1:
                    print(json.dumps({"status": "FAILED", "reason": "condition branch did not match exactly one edge", "externalCallPerformed": False, "steps": steps}, ensure_ascii=False, indent=2))
                    raise SystemExit(2)
                selected = matched[0]
                node_id = selected["target"]
            else:
                selected = edges[0] if edges else None
                node_id = selected["target"] if selected else None
            if selected:
                for binding in selected.get("bindings", []):
                    source = binding.get("sourceField")
                    target = binding.get("targetField")
                    if source == "context": continue
                    if source not in data: fail(f"edge {selected.get('id')} binding source {source} is missing", steps)
                    data[target] = data[source]
        final_output = {name: data[name] for name in final_schema.get("properties", {}) if name in data}
        validate_schema(final_schema, final_output, "final output schema")
        print(json.dumps({"status": "SUCCEEDED", "externalCallPerformed": False, "output": final_output, "steps": steps}, ensure_ascii=False, indent=2))
    """.trimIndent() + "\n"

    private fun yaml(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""

    private fun orchestration(bundle: MetaAgentDesignBundle): String {
        val plan = requireNotNull(bundle.proposal.graphPlan)
        val flow = plan.nodes.joinToString(" -> ") { "${it.label} [${it.nodeType}]" }
        return """
            # ${bundle.proposal.name}

            ## Source of truth

            `workflow.json` is the executable source. Agent and Guide Markdown files are derived contracts.

            ## Objective

            ${bundle.requirement.objective}

            ## Execution flow

            $flow

            ## Orchestration rules

            1. Read `workflow.json`, every `agents/*.md`, and every `guides/*.md`.
            2. Ask the user for every required input that is still missing. Never invent an important value.
            3. Execute from `entryNodeId` and pass each output to the next Agent using the declared contracts.
            4. Apply the user-confirmed Guide values to every relevant Agent output.
            5. Stop when a decision branch has no matching edge and report the missing or failed items.
            6. Pause at `human.approval`; do not treat a draft as approved without an explicit decision.
            7. Do not execute arbitrary code, install packages, persist secrets, or perform undeclared external writes.

            ## Failure policy

            ${bundle.proposal.failurePolicy}
        """.trimIndent() + "\n"
    }

    private fun entrypoint() = """
        # Generated Agentown Harness

        For each natural-language request, read and follow `CODEX.md`, `workflow.json`, `agents/*.md`, and `guides/*.md`.
        Treat the request as a harness run, not as a request to modify these files. Ask for missing required inputs and pause for declared human approval.
    """.trimIndent() + "\n"

    private fun agentMarkdown(agent: AgentDefinition) = buildString {
        appendLine("# ${agent.name}")
        appendLine(); appendLine("## Key"); appendLine(); appendLine("`${agent.key}`")
        appendLine(); appendLine("## Role"); appendLine(); appendLine(agent.role)
        appendFields("Input contract", agent.inputSchema.map { "`${it.name}` (${it.type}, ${if (it.required) "required" else "optional"}): ${it.description}" })
        appendFields("Output contract", agent.outputSchema.map { "`${it.name}` (${it.type}, ${if (it.required) "required" else "optional"}): ${it.description}" })
        appendFields("Behavior rules", agent.behaviorRules)
        appendFields("Forbidden", agent.forbiddenRules)
        appendFields("Evidence requirements", agent.evidenceRequirements)
    }

    private fun guideMarkdown(guide: GuideDefinition) = buildString {
        appendLine("# ${guide.title}")
        appendLine(); appendLine("## Key"); appendLine(); appendLine("`${guide.key}`")
        appendLine(); appendLine("## Purpose"); appendLine(); appendLine(guide.description)
        appendFields("User-confirmed output controls", guide.fields.map {
            "`${it.key}` (${it.type}, ${if (it.required) "required" else "optional"}${if (it.secret) ", secret" else ""}): ${it.help}"
        })
    }

    private fun StringBuilder.appendFields(title: String, values: List<String>) {
        appendLine(); appendLine("## $title"); appendLine()
        values.forEach { appendLine("- $it") }
    }

    private fun pretty(value: Any) = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n"

    private fun outputSchema(fields: List<com.agentvillage.builder.domain.FieldDefinition>): Map<String, Any> {
        val properties = fields.associate { field -> field.name to mapOf(
            "type" to when (field.type) { "array" -> "array"; "object" -> "object"; "number" -> "number"; "boolean" -> "boolean"; else -> "string" },
            "description" to field.description,
        ) }
        return linkedMapOf(
            "\$schema" to "https://json-schema.org/draft/2020-12/schema",
            "type" to "object",
            "additionalProperties" to false,
            "properties" to properties,
            "required" to fields.filter { it.required }.map { it.name },
        )
    }
}
