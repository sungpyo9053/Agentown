#!/usr/bin/env node
import { spawn } from "node:child_process";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

const baseUrl = (process.env.AGENTOWN_SERVER_URL || "http://localhost:8080").replace(/\/$/, "");
const token = process.env.AGENTOWN_RUNNER_TOKEN;
const provider = (process.env.AGENTOWN_RUNNER_PROVIDER || "CODEX").toUpperCase();
const pollMs = Number(process.env.AGENTOWN_RUNNER_POLL_MS || 2000);
if (!token) throw new Error("AGENTOWN_RUNNER_TOKEN이 필요합니다. Agentown의 AI 연결 화면에서 Runner를 연결하세요.");
if (!["CODEX", "CLAUDE"].includes(provider)) throw new Error("Provider는 CODEX 또는 CLAUDE여야 합니다.");

async function request(path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, { ...options, headers: { "content-type": "application/json", "x-runner-token": token, ...(options.headers || {}) } });
  if (!response.ok) throw new Error(`${response.status} ${await response.text()}`);
  if (response.status === 204 || response.headers.get("content-length") === "0") return null;
  const text = await response.text(); return text ? JSON.parse(text) : null;
}

function run(command, args, input, timeoutSeconds) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ["pipe", "pipe", "pipe"], env: process.env });
    let stdout = "", stderr = "";
    child.stdout.on("data", chunk => stdout += chunk);
    child.stderr.on("data", chunk => stderr += chunk);
    const timer = setTimeout(() => { child.kill("SIGTERM"); reject(new Error(`${command} 실행 시간이 초과되었습니다.`)); }, timeoutSeconds * 1000);
    child.on("error", reject);
    child.on("close", code => { clearTimeout(timer); code === 0 ? resolve({ stdout, stderr }) : reject(new Error(`${command} 종료 코드 ${code}: ${stderr.slice(-1000)}`)); });
    child.stdin.end(input);
  });
}

async function executeAgent(agent, input) {
  const prompt = [`당신은 Agentown의 ${agent.name}(${agent.role})입니다.`, agent.systemPrompt || "", `작업: ${agent.task}`, `가이드: ${agent.guide || ""}`, "도구로 파일을 수정하거나 명령을 실행하지 말고 최종 결과만 작성하세요.", `입력:\n${JSON.stringify(input)}`].filter(Boolean).join("\n\n");
  if (provider === "CODEX") {
    const dir = await mkdtemp(join(tmpdir(), "agentown-codex-")); const output = join(dir, "result.txt");
    try {
      const args = ["exec", "--ephemeral", "--skip-git-repo-check", "--sandbox", "read-only"];
      if (process.env.AGENTOWN_RUNNER_FORCE_MODEL === "true" && agent.model) args.push("--model", agent.model);
      args.push("--output-last-message", output, "-");
      await run("codex", args, prompt, agent.timeoutSeconds);
      return await readFile(output, "utf8");
    } finally { await rm(dir, { recursive: true, force: true }); }
  }
  const alias = /opus/i.test(agent.model) ? "opus" : /haiku/i.test(agent.model) ? "haiku" : "sonnet";
  const { stdout } = await run("claude", ["-p", "--no-session-persistence", "--permission-mode", "dontAsk", "--tools", "", "--model", alias, "--output-format", "json"], prompt, agent.timeoutSeconds);
  const parsed = JSON.parse(stdout); return parsed.result || parsed.content || stdout;
}

async function handle(job) {
  let current = job.input; const output = { ...current };
  for (const agent of job.agents) {
    await request(`/api/runner/jobs/${job.executionId}/events`, { method: "POST", body: JSON.stringify({ eventType: "STEP_STARTED", agentId: agent.agentId, stepKey: agent.stepKey }) });
    await request(`/api/runner/jobs/${job.executionId}/events`, { method: "POST", body: JSON.stringify({ eventType: "MODEL_REQUEST_SENT", agentId: agent.agentId, stepKey: agent.stepKey }) });
    const result = await executeAgent(agent, current);
    const stage = { result, agent: agent.name, runner: provider, subscription: true };
    output[agent.stepKey] = stage; output.result = result; current = { ...current, ...stage, [agent.stepKey]: stage };
    await request(`/api/runner/jobs/${job.executionId}/events`, { method: "POST", body: JSON.stringify({ eventType: "STEP_OUTPUT_CREATED", agentId: agent.agentId, stepKey: agent.stepKey, output: stage }) });
    await request(`/api/runner/jobs/${job.executionId}/events`, { method: "POST", body: JSON.stringify({ eventType: "STEP_COMPLETED", agentId: agent.agentId, stepKey: agent.stepKey }) });
  }
  await request(`/api/runner/jobs/${job.executionId}/complete`, { method: "POST", body: JSON.stringify({ output }) });
}

console.log(`Agentown Local Runner 시작: ${provider} → ${baseUrl}`);
for (;;) {
  try {
    await request("/api/runner/heartbeat", { method: "POST", body: "{}" });
    const job = await request("/api/runner/jobs/claim", { method: "POST", body: "{}" });
    if (job) {
      try { await handle(job); console.log(`완료: ${job.executionId}`); }
      catch (error) { await request(`/api/runner/jobs/${job.executionId}/fail`, { method: "POST", body: JSON.stringify({ code: "LOCAL_CLI_FAILED", message: String(error.message || error) }) }); }
    }
  } catch (error) { console.error(new Date().toISOString(), String(error.message || error)); }
  await new Promise(resolve => setTimeout(resolve, pollMs));
}
