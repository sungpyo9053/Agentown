package com.agentvillage.builder.application

import com.agentvillage.builder.domain.AgentDefinition
import com.agentvillage.builder.domain.FieldDefinition
import com.agentvillage.builder.domain.GuideDefinition
import com.agentvillage.builder.domain.MetaAgentDesignBundle
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

@Component
class HarnessPackageRenderer(
    private val mapper: ObjectMapper,
    private val tframexCompiler: TFrameXDefinitionCompiler = TFrameXDefinitionCompiler(mapper),
) {
    fun render(bundle: MetaAgentDesignBundle, automationValidated: Boolean = false): Map<String, String> {
        val withResources = if (bundle.proposal.resourcePlan == null) bundle.copy(
            proposal = bundle.proposal.copy(resourcePlan = BuilderCapabilityResolver().resolve(bundle)),
        ) else bundle
        val normalized = if (withResources.proposal.agentDesign == null) withResources.copy(
            proposal = withResources.proposal.copy(agentDesign = AgentDesignAssembler().assemble(withResources)),
        ) else withResources
        val plan = requireNotNull(normalized.proposal.graphPlan) { "proposal.graphPlan is required" }
        val agents = normalized.agentDefinitions
        val resources = requireNotNull(normalized.proposal.resourcePlan)
        val externalInputs = ExternalWorkflowInputContract.resolve(normalized.proposal, normalized.agentDefinitions)
        val generatedSampleInput = sampleInput(normalized)
        WorkflowInputContract.valueIssue(externalInputs, generatedSampleInput)?.let {
            throw IllegalStateException("PACKAGE_SAMPLE_INPUT_INVALID: $it")
        }
        val runtimeDefinition = runCatching {
            tframexCompiler.compilePlan(
                normalized.proposal.name,
                plan,
                normalized.agentDefinitions,
                generatedSampleInput,
                normalized.proposal.outputSchema.takeIf { it.isNotEmpty() },
                externalInputs,
            )
        }
        @Suppress("UNCHECKED_CAST")
        val effectiveOutputSchema = runtimeDefinition.getOrNull()?.get("finalOutputSchema") as? List<FieldDefinition>
            ?: normalized.proposal.outputSchema
        return linkedMapOf<String, String>().apply {
            put("agent.yaml", agentYaml(normalized))
            put("workflow.yaml", workflowYaml(normalized))
            put("prompts/system.md", agents.takeIf { it.isNotEmpty() }?.joinToString("\n\n---\n\n") { agentMarkdown(it) }
                ?: "# Deterministic package\n\n이 패키지는 AI Agent 없이 검증된 Function만 실행합니다.\n")
            put("prompts/reviewer.md", reviewerPrompt(normalized))
            put("schemas/input.schema.json", pretty(outputSchema(externalInputs)))
            put("schemas/output.schema.json", pretty(outputSchema(effectiveOutputSchema)))
            put("skills/README.md", "# Skills\n\n이 패키지에 고정된 Skill이 있으면 이 폴더에 추가합니다. 현재는 서버 카탈로그의 Template Skill만 참조합니다.\n")
            put("tools/tools.yaml", toolsYaml(resources))
            put("mcp.json", pretty(mapOf("mcpServers" to emptyMap<String, Any>())))
            put("examples/sample-input.json", pretty(generatedSampleInput))
            put("runtime-targets.json", pretty(mapOf(
                "targets" to listOf(
                    mapOf("key" to "python-local", "mode" to "TFRAMEX_PINNED", "entrypoint" to "runners/python/runner.py"),
                    mapOf("key" to "generic-package", "mode" to "CONTRACT_EXPORT", "entrypoint" to "agent.yaml"),
                ),
            )))
            put("runtime-definition.json", pretty(runtimeDefinition.getOrElse { emptyMap<String, Any?>() }))
            put("runtime-status.json", pretty(if (runtimeDefinition.isSuccess) mapOf(
                "configured" to automationValidated,
                "runtimeConfigured" to true,
                "packageStatus" to "PACKAGE_VALIDATED",
                "interactiveStatus" to "INTERACTIVE_READY",
                "automationStatus" to if (automationValidated) "AUTOMATION_READY" else "EXECUTION_NOT_CONFIGURED",
                "code" to if (automationValidated) null else "EXECUTION_NOT_CONFIGURED",
                "message" to if (automationValidated) null else "유효한 샘플의 실제 Runner 성공 검증이 아직 없습니다.",
            ) else mapOf(
                "configured" to false,
                "runtimeConfigured" to false,
                "packageStatus" to "PACKAGE_VALIDATED",
                "interactiveStatus" to "INTERACTIVE_READY",
                "automationStatus" to "EXECUTION_NOT_CONFIGURED",
                "code" to "EXECUTION_NOT_CONFIGURED",
                "message" to (runtimeDefinition.exceptionOrNull()?.message ?: "TFrameX 실행이 구성되지 않았습니다."),
            )))
            put("runners/python/runner.py", pythonTFrameXRunner())
            put("runtime/pyproject.toml", TFrameXRuntimeResources.read("pyproject.toml"))
            listOf("__init__.py", "adapter.py", "codex_llm.py", "capabilities.py", "server.py", "office.py", "artifacts.py", "research.py").forEach { name ->
                put("runtime/agentown_tframex_adapter/$name", TFrameXRuntimeResources.read("agentown_tframex_adapter/$name"))
            }
            put("company/index.html", TFrameXRuntimeResources.read("agentown_tframex_adapter/office.html"))
            put("company/README.md", "# 내 PC에서 회사 보기\n\nSTART_HERE.md의 Python 실행 환경을 준비하고 패키지 루트에서 `.venv/bin/python runners/python/runner.py --office-input`를 실행하세요. 회사 화면에서 실제 자료를 입력하고 실행 버튼을 누르세요. 터미널에 표시된 로컬 주소에서 직원별 실제 진행 상태와 결과를 볼 수 있습니다. 실행 종료 후에도 화면은 유지되며 Ctrl+C로 닫습니다. 애니메이션은 내 PC에서 실행되지만 AI 호출에는 설정된 제공자와 인터넷이 필요합니다. 웹에서 실행한 작업이나 Codex 채팅은 이 로컬 실행기 화면에 연결되지 않습니다.\n")
            put(".env.example", environmentExample(resources))
            put("README.md", packageReadme(normalized))
            put("START_HERE.md", startHere(normalized, resources, generatedSampleInput))
            put("design-bundle.json", pretty(normalized))
            put("workflow.json", pretty(linkedMapOf(
                "schemaVersion" to "1.0", "name" to normalized.proposal.name,
                "entryNodeId" to plan.entryNodeId, "nodes" to plan.nodes, "edges" to plan.edges,
                "agentKeys" to normalized.agentDefinitions.map { it.key },
                "guideKeys" to normalized.guideDefinitions.map { it.key },
            )))
            put("AGENTS.md", orchestration(normalized))
            put("CODEX.md", clientEntrypoint("Codex"))
            put("CLAUDE.md", clientEntrypoint("Claude Code"))
            put("manifest.json", pretty(linkedMapOf(
                "format" to "agentown-agent-package/v1", "name" to normalized.proposal.name,
                "agentCount" to normalized.agentDefinitions.size, "guideCount" to normalized.guideDefinitions.size,
                "templateSelection" to normalized.proposal.templateSelection,
                "economics" to normalized.proposal.economics,
                "executionReadiness" to normalized.proposal.agentDesign?.executionReadiness,
                "supportedRuntimeTargets" to listOf("python-local", "generic-package"),
                "validationRequiredBeforeImport" to true,
            )))
            put("schemas/final-output.schema.json", pretty(outputSchema(effectiveOutputSchema)))
            put("templates/output-template.json", pretty(linkedMapOf(
                "templateSelection" to normalized.proposal.templateSelection,
                "executionContract" to normalized.proposal.executionContract,
                "contentSchema" to outputSchema(effectiveOutputSchema),
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

    private fun sampleInput(bundle: MetaAgentDesignBundle): Map<String, Any?> {
        return SchemaSampleGenerator.generate(ExternalWorkflowInputContract.resolve(bundle.proposal, bundle.agentDefinitions))
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

        ## Local TFrameX test

        `python3 -m venv .venv && .venv/bin/pip install ./runtime` installs the pinned TFrameX runtime.
        `.venv/bin/python runners/python/runner.py` executes this package through the same Agentown TFrameX Adapter used by the service.
        If an Agent, Tool, connector, or Codex authentication is unavailable, execution returns `EXECUTION_NOT_CONFIGURED` and never substitutes Mock output.
    """.trimIndent() + "\n"

    private fun startHere(
        bundle: MetaAgentDesignBundle,
        resources: com.agentvillage.builder.domain.ResourcePlan,
        sample: Map<String, Any?>,
    ) = """
        # 시작하기

        이 패키지는 **${bundle.requirement.objective}** 업무를 생성된 Agent 역할과 Workflow에 따라 수행합니다.

        ## 대화형 실행

        ```bash
        unzip agentown-agent.zip
        cd agentown-agent
        codex
        # 또는
        claude
        ```

        첫 요청 예시: `examples/sample-input.json의 입력으로 이 업무를 실행해 줘.`

        `examples/sample-input.json`은 입력 형식 확인용 예시입니다. 실행 전에 예시 값을 실제 업무 자료로 바꿔 주세요. 예시 실행 성공은 실제 업무 결과의 품질을 보장하지 않습니다.

        Codex와 Claude Code는 `AGENTS.md`를 공통 실행 계약으로 사용합니다. 입력 예시는 아래와 같습니다.

        ```json
        ${pretty(sample).trim().replace("\n", "\n        ")}
        ```

        ## 환경변수와 제한

        필요한 환경변수: ${requiredEnvironment(resources).ifEmpty { listOf("없음") }.joinToString(", ") { "`$it`" }}

        외부 Connector가 연결되지 않았으면 실제 결과를 만들 수 없습니다. Mock으로 성공을 꾸미지 말고 `EXECUTION_NOT_CONFIGURED`로 중단합니다.

        ## 고급 자동 Runner

        Python 3.11 이상과 로그인된 Codex CLI가 필요합니다. 먼저 설치 상태만 점검하세요.
        `python3 runners/python/runner.py --check`는 AI를 호출하거나 도구를 설치하지 않습니다.

        ```bash
        python3 -m venv .venv
        .venv/bin/python -m pip install './runtime[artifacts]'
        .venv/bin/python runners/python/runner.py --office-input
        ```

        위 명령은 파일 제작 도구까지 전용 가상환경에 설치합니다. 시스템 Python이나 기존 프로젝트의 패키지는 변경하지 않습니다.
        실제 파일은 패키지의 `results` 아래 매번 새 폴더에 생성합니다. 기존 파일은 덮어쓰지 않습니다.
        파일 제작은 로컬 실행기 전용이며, 생성 성공이 내용·출처·레이아웃 검토 완료를 뜻하지는 않습니다.

        ## 내 PC에서 직원들이 일하는 회사 화면 보기

        위 실행 환경을 준비한 뒤 `.venv/bin/python runners/python/runner.py --office-input`로 실행하세요.
        회사 화면에 실제 자료를 입력하고 ‘이 자료로 직원들 실행’을 누르세요. 누르기 전에는 AI를 호출하지 않으며 예시 자료를 자동 실행하지 않습니다.
        Windows PowerShell에서는 `py -3 -m venv .venv`, `.venv\Scripts\python.exe -m pip install './runtime[artifacts]'`, `.venv\Scripts\python.exe runners/python/runner.py --office-input` 순서로 실행합니다. Python 3.11 이상이 필요합니다.
        기존 예시 파일을 사용하는 고급 실행은 `--office`로 유지됩니다.
        브라우저의 로컬 회사 화면에서 실제 에이전트별 진행 상태와 결과를 봅니다. AI 호출에는 설정된 제공자와 인터넷이 필요합니다.
        실행이 끝나면 Ctrl+C로 로컬 화면 서버를 종료합니다. 웹 실행이나 별도 채팅의 작업 상태는 표시하지 않습니다.
    """.trimIndent() + "\n"

    private fun pythonTFrameXRunner() = """
        #!/usr/bin/env python3
        import asyncio, importlib.util, json, os, shutil, sys, webbrowser
        from pathlib import Path

        root = Path(__file__).resolve().parents[2]
        def setup_failure(code, message):
            print(json.dumps({"status": "EXECUTION_NOT_CONFIGURED", "code": code, "message": message}, ensure_ascii=False, indent=2))
            raise SystemExit(2)

        if sys.version_info < (3, 11):
            setup_failure("PYTHON_VERSION_UNSUPPORTED", "Python 3.11 이상이 필요합니다. 해당 Python으로 START_HERE.md의 가상환경을 생성해 주세요.")
        missing = [name for name in ("tframex", "jsonschema", "mcp") if importlib.util.find_spec(name) is None]
        if missing:
            setup_failure("RUNTIME_DEPENDENCIES_MISSING", "실행 환경이 준비되지 않았습니다: " + ", ".join(missing) + ". START_HERE.md에 따라 패키지 폴더에 가상환경을 만들고 pip install ./runtime을 실행하세요.")
        sys.path.insert(0, str(root / "runtime"))
        try:
            from agentown_tframex_adapter import AgentownTFrameXAdapter, CodexCliLLMWrapper, ExecutionNotConfigured
            from agentown_tframex_adapter.capabilities import BUILTIN_TOOLS
        except ImportError:
            setup_failure("RUNTIME_IMPORT_FAILED", "설치된 실행 도구가 호환되지 않습니다. START_HERE.md의 전용 가상환경에서 pip install ./runtime을 다시 실행하세요.")

        status = json.loads((root / "runtime-status.json").read_text())
        if not status.get("runtimeConfigured"):
            print(json.dumps({"status": "EXECUTION_NOT_CONFIGURED", "code": status.get("code"), "message": status.get("message")}, ensure_ascii=False, indent=2))
            raise SystemExit(2)
        definition = json.loads((root / "runtime-definition.json").read_text())
        needs_research = any(item.get("toolName") == "local.web.research" for item in definition.get("agents", []))
        needs_ai = needs_research or any(item.get("kind") != "tool" for item in definition.get("agents", []))
        needs_artifacts = any(item.get("toolName") == "local.artifact.render" for item in definition.get("agents", []))
        artifact_modules = ${mapper.writeValueAsString(LocalArtifactContract.runtimeModules)}
        required_modules = {name for item in definition.get("agents", []) if item.get("toolName") == "local.artifact.render"
                            for name in artifact_modules.get((item.get("inputDefaults") or {}).get("artifactFormat"), [])}
        if any(importlib.util.find_spec(name) is None for name in required_modules):
            setup_failure("ARTIFACT_TOOLS_MISSING", "파일 제작 도구가 필요합니다. 전용 가상환경에서 pip install './runtime[artifacts]'를 실행하세요.")
        if needs_ai and not shutil.which(os.environ.get("AGENTOWN_CODEX_COMMAND", "codex")):
            setup_failure("CODEX_CLI_MISSING", "Codex CLI를 찾을 수 없습니다. 설치·로그인 후 다시 실행하세요. 이 점검은 자동 설치하지 않습니다.")
        if "--check" in sys.argv:
            print(json.dumps({"status": "ENVIRONMENT_READY", "authentication": "NOT_CHECKED", "message": "로컬 실행 도구를 확인했습니다. 로그인·실제 실행·결과물 품질은 별도 검증이 필요합니다."}, ensure_ascii=False, indent=2))
            raise SystemExit(0)
        interactive_input = "--office-input" in sys.argv
        if not interactive_input:
            definition["input"] = json.dumps(json.loads((root / "examples/sample-input.json").read_text()), ensure_ascii=False)
        office = None
        if "--office" in sys.argv or interactive_input:
            from agentown_tframex_adapter.office import LocalOffice, OfficeTrace
            office = LocalOffice(root, input_schema=json.loads((root / "schemas/input.schema.json").read_text()) if interactive_input else None)
            office.start()
            office.finish("IDLE" if interactive_input else "RUNNING")
            print("로컬 회사 화면: " + office.url, file=sys.stderr, flush=True)
            try:
                webbrowser.open(office.url)
            except webbrowser.Error:
                print("브라우저에서 위 로컬 주소를 직접 여세요.", file=sys.stderr)

        async def execute():
            llm = CodexCliLLMWrapper(
                command=os.environ.get("AGENTOWN_CODEX_COMMAND", "codex"),
                model=os.environ.get("AGENTOWN_CODEX_MODEL", "gpt-5.6-luna"),
            )
            tools = dict(BUILTIN_TOOLS)
            output_validators = {}
            if needs_research:
                from agentown_tframex_adapter.research import local_research_tools
                tools.update(local_research_tools(command=os.environ.get("AGENTOWN_CODEX_COMMAND", "codex"), model=os.environ.get("AGENTOWN_CODEX_MODEL", "gpt-5.6-luna")))
            if needs_artifacts:
                from agentown_tframex_adapter.artifacts import local_artifact_tools, validate_artifact_output
                tools.update(local_artifact_tools(root))
                output_validators["local.artifact.spec"] = validate_artifact_output
            adapter = AgentownTFrameXAdapter(llm=llm, tools=tools, output_validators=output_validators)
            if office:
                adapter.trace = OfficeTrace(office)
            return await adapter.run(definition)

        try:
            if interactive_input:
                office.input_ready.wait()
                definition["input"] = json.dumps(office.submitted_input, ensure_ascii=False)
            result = asyncio.run(execute())
            final = result.get("final") or ""
            try: output = json.loads(final)
            except json.JSONDecodeError: output = {"result": final}
            print(json.dumps({"status": "SUCCEEDED", "output": output, **result}, ensure_ascii=False, indent=2, default=str))
            if office: office.finish("SUCCEEDED", output=output)
        except ExecutionNotConfigured as error:
            if office: office.finish("EXECUTION_NOT_CONFIGURED", str(error))
            print(json.dumps({"status": "EXECUTION_NOT_CONFIGURED", "code": "EXECUTION_NOT_CONFIGURED", "message": str(error)}, ensure_ascii=False, indent=2))
            raise SystemExit(2)
        except Exception as error:
            if office: office.finish("FAILED", str(error))
            print(json.dumps({"status": "FAILED", "code": "TFRAMEX_EXECUTION_FAILED", "message": str(error)}, ensure_ascii=False, indent=2))
            raise SystemExit(1)
        except KeyboardInterrupt:
            if office:
                office.finish("INTERRUPTED")
                office.close()
                office = None
            raise SystemExit(130)
        finally:
            if office:
                print("회사 화면이 열려 있습니다. Ctrl+C로 종료합니다.", file=sys.stderr, flush=True)
                try:
                    import threading
                    threading.Event().wait()
                except KeyboardInterrupt:
                    pass
                finally:
                    office.close()
    """.trimIndent() + "\n"

    private fun yaml(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""

    private fun orchestration(bundle: MetaAgentDesignBundle): String {
        val plan = requireNotNull(bundle.proposal.graphPlan)
        val flow = plan.nodes.joinToString(" -> ") { "${it.label} [${it.nodeType}]" }
        return """
            # ${bundle.proposal.name}

            ## 시작 순서와 Source of truth

            1. `START_HERE.md`와 이 공통 지침을 읽습니다.
            2. `schemas/input.schema.json`과 `examples/sample-input.json`을 읽고 필수 입력을 확인합니다.
            3. `workflow.json`을 실행 가능한 Source of truth로 사용합니다. Agent와 Guide Markdown은 파생 계약입니다.

            ## Objective

            ${bundle.requirement.objective}

            ## Execution flow

            $flow

            ## Orchestration rules

            1. Read `workflow.json`, every `agents/*.md`, and every `guides/*.md`.
            2. Ask the user for every required input that is still missing. Never invent an important value.
            3. Execute from `entryNodeId`, honor every dependency, and pass outputs using declared bindings and schemas.
            4. Run independent nodes concurrently when the graph allows it. Run a Join successor only after every predecessor succeeded.
            5. Validate each Agent input/output, final output schema, and `policies/quality-rules.json`.
            6. Apply the user-confirmed Guide values to every relevant Agent output.
            7. If any parallel task fails, preserve its failure and do not report the overall run as `SUCCEEDED` or execute its Join successor.
            8. Stop when a decision branch has no matching edge and report the missing or failed items.
            9. Pause at `human.approval`; do not treat a draft as approved without an explicit decision.
            10. Do not execute arbitrary code, persist secrets, or perform undeclared external writes.
            11. If an Agent, Tool, runtime, API key, or Connector is unavailable, return `EXECUTION_NOT_CONFIGURED`; never invent Mock output.
            12. Preserve evidence, dates, and source URLs through every binding and in the final result when declared by the schemas.

            ## Failure policy

            ${bundle.proposal.failurePolicy}
        """.trimIndent() + "\n"
    }

    private fun clientEntrypoint(client: String) = """
        # $client entrypoint

        `AGENTS.md` is the single common execution contract. Read it together with `START_HERE.md`, then follow the declared Agent roles, Workflow dependencies, schemas, quality rules, and failure policy.
        Treat the user's natural-language request as a workflow run, not as a request to modify this package.
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
        val properties = fields.associate { field -> field.name to fieldSchema(field) }
        return linkedMapOf(
            "\$schema" to "https://json-schema.org/draft/2020-12/schema",
            "type" to "object",
            "additionalProperties" to false,
            "properties" to properties,
            "required" to fields.filter { it.required }.map { it.name },
        )
    }

    private fun fieldSchema(field: com.agentvillage.builder.domain.FieldDefinition): Map<String, Any> = linkedMapOf<String, Any>(
            "type" to when (field.type.lowercase()) {
                "array" -> "array"
                "object" -> "object"
                "number" -> "number"
                "integer" -> "integer"
                "boolean" -> "boolean"
                else -> "string"
            },
            "description" to field.description,
        ).apply {
            field.minItems?.let { put("minItems", it) }
            field.maxItems?.let { put("maxItems", it) }
            field.format?.let { put("format", it) }
            field.enumValues?.let { put("enum", it) }
            field.minimum?.let { put("minimum", it) }
            field.maximum?.let { put("maximum", it) }
            field.minLength?.let { put("minLength", it) }
            field.uniqueItems?.let { put("uniqueItems", it) }
            field.uniqueBy?.let { put("x-agentown-uniqueBy", it) }
            field.objectSchema?.let { nested -> putAll(linkedMapOf(
                "additionalProperties" to false,
                "properties" to nested.associate { it.name to fieldSchema(it) },
                "required" to nested.filter { it.required }.map { it.name },
            )) }
            field.itemType?.let { itemType ->
                put("items", if (itemType.equals("object", true) && !field.itemSchema.isNullOrEmpty()) linkedMapOf(
                    "type" to "object",
                    "additionalProperties" to false,
                    "properties" to field.itemSchema.associate { it.name to fieldSchema(it) },
                    "required" to field.itemSchema.filter { it.required }.map { it.name },
                ) else linkedMapOf<String, Any>("type" to jsonType(itemType)).apply {
                    field.itemFormat?.let { put("format", it) }
                    field.itemMinLength?.let { put("minLength", it) }
                })
            }
        }

    private fun jsonType(type: String): String = when (type.lowercase()) {
        "array" -> "array"
        "object" -> "object"
        "number" -> "number"
        "integer" -> "integer"
        "boolean" -> "boolean"
        else -> "string"
    }

    private fun inputSchema(bundle: MetaAgentDesignBundle): Map<String, Any> = linkedMapOf(
        "\$schema" to "https://json-schema.org/draft/2020-12/schema",
        "type" to "object",
        "additionalProperties" to true,
        "description" to bundle.requirement.inputs.joinToString(", "),
    )

    private fun toolManifest(bundle: MetaAgentDesignBundle): Map<String, Any> = mapOf(
        "tools" to bundle.proposal.graphPlan.orEmptyNodes().filterNot { it.nodeType.startsWith("ai.") || it.nodeType.endsWith("trigger") || it.nodeType == "human.approval" }.map { node ->
            mapOf("id" to node.nodeType, "config" to node.config, "mock" to node.nodeType.endsWith(".mock"), "connection_status" to (node.config["connectionStatus"] ?: if (node.nodeType.endsWith(".mock")) "MOCK_ONLY" else "BUILT_IN"))
        },
    )

    private fun requiredEnvironment(bundle: MetaAgentDesignBundle): List<String> = buildList {
        val types = bundle.proposal.graphPlan.orEmptyNodes().map { it.nodeType }
        if (types.any { it.startsWith("slack.") }) add("SLACK_BOT_TOKEN")
        if (types.any { it.startsWith("notion.") }) add("NOTION_TOKEN")
        if (types.any { it.startsWith("email.") }) add("EMAIL_CONNECTION")
    }

    private fun com.agentvillage.builder.domain.WorkflowGraphPlan?.orEmptyNodes() = this?.nodes.orEmpty()
}
