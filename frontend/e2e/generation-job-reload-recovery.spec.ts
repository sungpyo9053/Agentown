import { expect, test } from "@playwright/test";

test("reload restores recoverable generation and conversation changes clear it without job mutations", async ({ page }) => {
  let recoveryRequests = 0;
  let generationPolls = 0;
  const generationMutations: string[] = [];

  const snapshot = (conversationId: string, workflowId: string) => ({
    workspaceId: "workspace-owner",
    conversationId,
    workflowId,
    status: "NEW",
    clarificationQuestions: [],
    agentDefinitions: [],
    agentMarkdown: [],
    guideDefinitions: [],
    guideMarkdown: [],
    messages: [],
    versions: [],
  });
  const runningJob = {
    id: "generation-running-a",
    conversationId: "conversation-a",
    workflowId: "workflow-a",
    status: "RUNNING",
    stage: "CODEX_ANALYZING",
    estimatedSeconds: 90,
    elapsedSeconds: 24,
    remainingSeconds: 66,
  };
  const failedJob = {
    id: "generation-failed-b",
    conversationId: "conversation-b",
    workflowId: "workflow-b",
    status: "FAILED",
    stage: "FAILED",
    estimatedSeconds: 90,
    elapsedSeconds: 31,
    remainingSeconds: 59,
    errorCode: "BUILDER_GENERATION_SAFE_FAILURE",
    errorMessage: "저장된 설계 작업이 안전하게 중단되었습니다.",
  };
  const conversations = [
    { conversationId: "conversation-a", workflowId: "workflow-a", title: "진행 중 자동화", status: "NEW", updatedAt: "2026-08-30T05:00:00Z" },
    { conversationId: "conversation-b", workflowId: "workflow-b", title: "실패한 자동화", status: "NEW", updatedAt: "2026-08-30T04:00:00Z" },
  ];

  await page.addInitScript(() => window.localStorage.setItem("agentown.builder.conversation.v1", "conversation-a"));
  await page.route("**/api/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });

    if (request.method() !== "GET" && (path.includes("/generation-jobs/") || path.endsWith("/messages"))) {
      generationMutations.push(`${request.method()} ${path}`);
      return route.fulfill({ status: 409, contentType: "application/json", body: JSON.stringify({ message: "복구 중 자동 변경 금지" }) });
    }
    const recovery = path.match(/^\/api\/builder\/conversations\/(conversation-[abc])\/generation-jobs\/latest-recoverable$/);
    if (recovery) {
      recoveryRequests += 1;
      if (recovery[1] === "conversation-a") return json(runningJob);
      if (recovery[1] === "conversation-b") return json(failedJob);
      return route.fulfill({ status: 204 });
    }
    if (path === "/api/builder/generation-jobs/generation-running-a") {
      generationPolls += 1;
      return json({ ...runningJob, elapsedSeconds: 27, remainingSeconds: 63 });
    }
    if (path === "/api/builder/generation-jobs/generation-failed-b") return json(failedJob);
    if (path === "/api/builder/conversations/conversation-a") return json(snapshot("conversation-a", "workflow-a"));
    if (path === "/api/builder/conversations/conversation-b") return json(snapshot("conversation-b", "workflow-b"));
    if (path === "/api/builder/conversations/conversation-c") return json(snapshot("conversation-c", "workflow-c"));
    if (path === "/api/builder/conversations" && request.method() === "POST") {
      conversations.push({ conversationId: "conversation-c", workflowId: "workflow-c", title: "새 자동화", status: "NEW", updatedAt: "2026-08-30T06:00:00Z" });
      return json(snapshot("conversation-c", "workflow-c"));
    }
    if (path === "/api/builder/conversations") return json(conversations);
    if (/^\/api\/builder\/workflows\/workflow-[abc]\/production-runs$/.test(path)) return json([]);
    if (path === "/api/connectors/notion") return json({ configured: false, connected: false, connections: [] });
    if (path === "/api/connectors/slack") return json({ configured: false, connected: false });
    if (path === "/api/mini-homes/me") return json({ title: "설계 복구 회사" });
    if (path === "/api/auth/me") return json({ displayName: "복구 사용자", handle: "recovery-user", role: "USER" });
    if (path === "/api/auth/csrf") return json({});
    return route.fulfill({ status: 404, contentType: "application/json", body: "{}" });
  });

  await page.goto("/assemble/automation");
  await expect(page.getByText("실제 Codex 메타 에이전트 팀이 설계 중입니다")).toBeVisible();
  await expect(page.getByText(/업무 분석·에이전트 설계 · 경과 2[47]초/)).toBeVisible();
  await expect(page.getByRole("button", { name: "실행 중지" })).toBeVisible();
  await expect(page.getByRole("button", { name: "처리 중…" })).toBeDisabled();
  await expect.poll(() => generationPolls).toBeGreaterThanOrEqual(1);
  expect(generationMutations).toEqual([]);

  await page.reload();
  await expect(page.getByText("실제 Codex 메타 에이전트 팀이 설계 중입니다")).toBeVisible();
  await expect(page.getByRole("button", { name: "실행 중지" })).toBeVisible();
  await expect.poll(() => recoveryRequests).toBeGreaterThanOrEqual(2);
  expect(generationMutations).toEqual([]);

  await page.getByLabel("저장된 업무 자동화").selectOption("conversation-b");
  await expect(page.getByText("Codex 설계를 시작하지 못했습니다")).toBeVisible();
  await expect(page.getByText("BUILDER_GENERATION_SAFE_FAILURE · 저장된 설계 작업이 안전하게 중단되었습니다.")).toBeVisible();
  await expect(page.getByText("실제 Codex 메타 에이전트 팀이 설계 중입니다")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "실행 중지" })).toHaveCount(0);
  expect(generationMutations).toEqual([]);

  await page.getByRole("button", { name: "새 자동화" }).click();
  await expect(page.getByLabel("저장된 업무 자동화")).toHaveValue("conversation-c");
  await expect(page.getByText("Codex 설계를 시작하지 못했습니다")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "분석 시작" })).toBeEnabled();
  expect(generationMutations).toEqual([]);
});
