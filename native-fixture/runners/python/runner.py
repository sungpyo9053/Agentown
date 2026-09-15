#!/usr/bin/env python3
import asyncio, importlib.util, json, os, shutil, sys, webbrowser
from pathlib import Path

for stream in (sys.stdout, sys.stderr):
    if stream is not None and hasattr(stream, "reconfigure"):
        stream.reconfigure(encoding="utf-8")

bundled_runtime = bool(getattr(sys, "frozen", False))
root = Path(sys.executable).resolve().parent if bundled_runtime else Path(__file__).resolve().parents[2]
if bundled_runtime and sys.platform == "darwin" and root.name == "MacOS" and root.parent.name == "Contents":
    root = root.parents[2]  # Package data belongs beside the signed app, not inside it.
# A bundled launcher shares the same workflow interpreter for every
# package. Double-clicking must wait for real input, never run examples.
if bundled_runtime and "--check" not in sys.argv and "--office-input" not in sys.argv:
    sys.argv.append("--office-input")
def setup_failure(code, message):
    print(json.dumps({"status": "EXECUTION_NOT_CONFIGURED", "code": code, "message": message}, ensure_ascii=False, indent=2))
    raise SystemExit(2)

if sys.version_info < (3, 11):
    setup_failure("PYTHON_VERSION_UNSUPPORTED", "Python 3.11 이상이 필요합니다. 해당 Python으로 START_HERE.md의 가상환경을 생성해 주세요.")
missing = [name for name in ("tframex", "jsonschema", "mcp") if importlib.util.find_spec(name) is None]
if missing:
    setup_failure("RUNTIME_DEPENDENCIES_MISSING", "실행 환경이 준비되지 않았습니다: " + ", ".join(missing) + ". START_HERE.md에 따라 패키지 폴더에 가상환경을 만들고 pip install ./runtime을 실행하세요.")
if not bundled_runtime:
    sys.path.insert(0, str(root / "runtime"))
try:
    from agentown_tframex_adapter import AgentownTFrameXAdapter, CodexCliLLMWrapper, ExecutionNotConfigured
    from agentown_tframex_adapter.capabilities import BUILTIN_TOOLS
except ImportError:
    setup_failure("RUNTIME_IMPORT_FAILED", "설치된 실행 도구가 호환되지 않습니다. START_HERE.md의 전용 가상환경에서 pip install ./runtime을 다시 실행하세요.")

status = json.loads((root / "runtime-status.json").read_text(encoding="utf-8"))
if not status.get("runtimeConfigured"):
    print(json.dumps({"status": "EXECUTION_NOT_CONFIGURED", "code": status.get("code"), "message": status.get("message")}, ensure_ascii=False, indent=2))
    raise SystemExit(2)
definition = json.loads((root / "runtime-definition.json").read_text(encoding="utf-8"))
needs_research = any(item.get("toolName") == "local.web.research" for item in definition.get("agents", []))
needs_ai = needs_research or any(item.get("kind") != "tool" for item in definition.get("agents", []))
needs_artifacts = any(item.get("toolName") == "local.artifact.render" for item in definition.get("agents", []))
artifact_modules = {"pptx":["pptx"],"xlsx":["openpyxl"],"docx":["docx"],"bundle":["pptx","openpyxl","docx"]}
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
    definition["input"] = json.dumps(json.loads((root / "examples/sample-input.json").read_text(encoding="utf-8")), ensure_ascii=False)
office = None
if "--office" in sys.argv or interactive_input:
    from agentown_tframex_adapter.office import LocalOffice, OfficeTrace
    office = LocalOffice(root, input_schema=json.loads((root / "schemas/input.schema.json").read_text(encoding="utf-8")) if interactive_input else None)
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
