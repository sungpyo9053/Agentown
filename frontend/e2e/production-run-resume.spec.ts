import { expect, test } from "@playwright/test";

test("reload restores newest durable run and selection stays workflow scoped", async ({ page }) => {
  let historyRequests = 0;
  let productionMutations = 0;
  const snapshot = (conversationId: string, workflowId: string) => ({
    workspaceId: "workspace-owner",
    conversationId,
    workflowId,
    status: "ACTIVE",
    clarificationQuestions: [],
    agentDefinitions: [],
    agentMarkdown: [],
    guideDefinitions: [],
    guideMarkdown: [],
    messages: [],
    versions: [],
    graph: { schemaVersion: "1", workflowId, entryNodeId: "generate", nodes: [], edges: [] },
  });
  const run = (id: string, status: string, title: string, attemptCount: number, extra: Record<string, unknown> = {}) => ({
    id,
    status,
    mode: "PRODUCTION",
    attemptCount,
    output: {
      title,
      paragraphs: [`${title}의 보존된 본문`],
      evidence: ["저장된 실행 근거"],
      ...(status === "SUCCEEDED" ? { notionUrl: `https://notion.so/${id}` } : {}),
    },
    steps: [
      { nodeId: "generate", nodeType: "ai.generate", sequenceNo: 1, status: "SUCCEEDED", input: {}, output: { title } },
      { nodeId: "approval", nodeType: "human.approval", sequenceNo: 2, status: status === "WAITING_APPROVAL" ? "WAITING_APPROVAL" : "SUCCEEDED", input: {} },
    ],
    ...extra,
  });
  const newestWaiting = run("run-newest-waiting", "WAITING_APPROVAL", "새로고침 후 복원된 승인 대기", 1);
  const preservedFailure = run("run-older-failed", "FAILED", "보존된 실패 결과", 2, {
    failureCode: "PRODUCTION_GENERATION_FAILED",
    failureMessage: "입력과 생성 권한을 확인한 뒤 재시도하세요.",
  });
  const reconnectedFailure = run("run-reconnected-failed", "FAILED", "다시 연결한 실행", 1, {
    failureCode: "NOTION_CONNECTION_EXPIRED",
    failureMessage: "Notion 연결이 만료되었습니다. 업무 연결에서 다시 연결한 뒤 재시도해 주세요.",
  });
  const ambiguous = run("run-older-ambiguous", "AMBIGUOUS", "확인이 필요한 모호한 결과", 1, {
    failureCode: "NOTION_PAGE_CREATE_AMBIGUOUS",
    failureMessage: "요청 결과를 확정할 수 없습니다.",
  });
  const succeeded = run("run-oldest-succeeded", "SUCCEEDED", "완료된 과거 결과", 1);
  const otherWorkflowRun = run("run-other-workflow", "SUCCEEDED", "다른 자동화의 완료 결과", 1);
  const histories: Record<string, Array<{ run: ReturnType<typeof run>; destinationConnectionId: string }>> = {
    "workflow-a": [
      { run: newestWaiting, destinationConnectionId: "connection-a" },
      { run: preservedFailure, destinationConnectionId: "connection-a" },
      { run: reconnectedFailure, destinationConnectionId: "connection-reconnected" },
      { run: ambiguous, destinationConnectionId: "connection-a" },
      { run: succeeded, destinationConnectionId: "connection-a" },
    ],
    "workflow-b": [{ run: otherWorkflowRun, destinationConnectionId: "connection-b" }],
  };

  await page.addInitScript(() => window.localStorage.setItem("agentown.builder.conversation.v1", "conversation-a"));
  await page.route("**/api/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
    if (path === "/api/builder/conversations/conversation-a") return json(snapshot("conversation-a", "workflow-a"));
    if (path === "/api/builder/conversations/conversation-b") return json(snapshot("conversation-b", "workflow-b"));
    if (path === "/api/builder/conversations") return json([
      { conversationId: "conversation-a", workflowId: "workflow-a", title: "첫 번째 자동화", status: "ACTIVE", currentVersionNo: 1, updatedAt: "2026-08-29T02:00:00Z" },
      { conversationId: "conversation-b", workflowId: "workflow-b", title: "두 번째 자동화", status: "ACTIVE", currentVersionNo: 1, updatedAt: "2026-08-29T01:00:00Z" },
    ]);
    const historyMatch = path.match(/^\/api\/builder\/workflows\/(workflow-[ab])\/production-runs$/);
    if (historyMatch && request.method() === "GET") {
      historyRequests += 1;
      return json(histories[historyMatch[1]]);
    }
    if (path.includes("/production-runs") && request.method() !== "GET") {
      productionMutations += 1;
      return route.fulfill({ status: 409, contentType: "application/json", body: JSON.stringify({ message: "자동 요청 금지" }) });
    }
    if (path === "/api/connectors/notion") return json({
      configured: true,
      connected: true,
      connections: [
        { id: "connection-a", workspaceName: "첫 Notion", status: "ACTIVE" },
        { id: "connection-reconnected", workspaceName: "다시 연결한 Notion", status: "ACTIVE" },
        { id: "connection-b", workspaceName: "둘째 Notion", status: "ACTIVE" },
      ],
    });
    if (path === "/api/connectors/slack") return json({ configured: false, connected: false });
    if (path === "/api/mini-homes/me") return json({ title: "실행 복구 회사" });
    if (path === "/api/auth/me") return json({ displayName: "복구 사용자", handle: "resume-user", role: "USER" });
    if (path === "/api/auth/csrf") return json({});
    return route.fulfill({ status: 404, contentType: "application/json", body: "{}" });
  });

  await page.goto("/assemble/automation");
  await expect.poll(() => historyRequests).toBeGreaterThanOrEqual(1);
  await page.reload();
  await expect.poll(() => historyRequests).toBeGreaterThanOrEqual(2);
  await page.getByRole("button", { name: "실제 실행" }).click();

  const selector = page.getByLabel("최근 실제 실행");
  await expect(selector).toHaveValue("run-newest-waiting");
  await expect(selector.locator("option")).toHaveText([
    "실행 선택",
    "최신 · WAITING_APPROVAL · 시도 1 · run-newe",
    "FAILED · 시도 2 · run-olde",
    "FAILED · 시도 1 · run-reco",
    "AMBIGUOUS · 시도 1 · run-olde",
    "SUCCEEDED · 시도 1 · run-olde",
  ]);
  await expect(page.getByRole("heading", { name: "새로고침 후 복원된 승인 대기" })).toBeVisible();
  await expect(page.getByRole("button", { name: "승인하고 실제 발행" })).toBeVisible();
  await expect(page.getByRole("button", { name: "거절" })).toBeVisible();
  expect(productionMutations).toBe(0);

  await selector.selectOption("run-older-ambiguous");
  await expect(page.getByRole("heading", { name: "확인이 필요한 모호한 결과" })).toBeVisible();
  await expect(page.getByText("중복 페이지 방지를 위해 이 실행은 재시도할 수 없습니다.", { exact: false })).toBeVisible();
  await expect(page.getByRole("button", { name: /재시도/ })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "승인하고 실제 발행" })).toHaveCount(0);

  await selector.selectOption("run-older-failed");
  await expect(page.getByRole("heading", { name: "보존된 실패 결과" })).toBeVisible();
  await expect(page.getByText("PRODUCTION_GENERATION_FAILED", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "재시도 (2/3)" })).toBeVisible();

  await selector.selectOption("run-reconnected-failed");
  await expect(page.getByRole("heading", { name: "다시 연결한 실행" })).toBeVisible();
  await expect(page.getByText("Notion 다시 연결 완료", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "재시도 (1/3)" })).toBeVisible();
  expect(productionMutations).toBe(0);

  await selector.selectOption("run-oldest-succeeded");
  await expect(page.getByRole("heading", { name: "완료된 과거 결과" })).toBeVisible();
  await expect(page.getByText("실제 업무 완료", { exact: true })).toBeVisible();

  await page.getByLabel("저장된 업무 자동화").selectOption("conversation-b");
  await page.getByRole("button", { name: "실제 실행" }).click();
  await expect(page.getByLabel("최근 실제 실행")).toHaveValue("run-other-workflow");
  await expect(page.getByRole("heading", { name: "다른 자동화의 완료 결과" })).toBeVisible();
  await expect(page.getByText("새로고침 후 복원된 승인 대기")).toHaveCount(0);
  expect(productionMutations).toBe(0);
});
