import { expect, test } from "@playwright/test";

test("mocked Notion expiry hides retry until the same connection is active again", async ({ page }) => {
  let oauthStarts = 0;
  let connectionStatus = "ACTIVE";
  let productionStarted = false;
  let retries = 0;
  const expiredConnection = {
    id: "notion-preserved",
    workspaceId: "workspace-preserved",
    workspaceName: "만료된 Notion",
    status: "INVALID",
    connectedAt: "2026-08-27T00:00:00Z",
  };
  const otherConnection = {
    id: "notion-other-active",
    workspaceId: "workspace-other",
    workspaceName: "다른 Notion",
    status: "ACTIVE",
    connectedAt: "2026-08-27T00:00:00Z",
  };
  const snapshot = {
    workspaceId: "workspace-1",
    conversationId: "conversation-expiry",
    workflowId: "workflow-expiry",
    status: "ACTIVE",
    clarificationQuestions: [],
    agentDefinitions: [],
    agentMarkdown: [],
    guideDefinitions: [],
    guideMarkdown: [],
    messages: [],
    versions: [],
    graph: { schemaVersion: "1", workflowId: "workflow-expiry", entryNodeId: "generate", nodes: [], edges: [] },
  };
  const failedRun = {
    id: "production-expired",
    status: "FAILED",
    mode: "PRODUCTION",
    attemptCount: 1,
    failureCode: "NOTION_CONNECTION_EXPIRED",
    failureMessage: "Notion 연결이 만료되었습니다. 업무 연결에서 다시 연결한 뒤 재시도해 주세요.",
    output: {
      title: "보존된 주간 보고서",
      paragraphs: ["승인된 미리보기 본문은 실패 후에도 보존됩니다."],
      evidence: ["사용자 입력"],
    },
    steps: [
      { nodeId: "generate", nodeType: "ai.generate", sequenceNo: 1, status: "SUCCEEDED", input: {}, output: { title: "보존된 주간 보고서" } },
      { nodeId: "write", nodeType: "notion.create_page", sequenceNo: 2, status: "FAILED", input: {}, errorMessage: "Notion 연결이 만료되었습니다. 업무 연결에서 다시 연결한 뒤 재시도해 주세요." },
    ],
  };

  await page.addInitScript(() => window.localStorage.setItem("agentown.builder.conversation.v1", "conversation-expiry"));
  await page.route("**/api/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
    if (path === "/api/connectors/notion/oauth/start") {
      oauthStarts += 1;
      await json({ authorizationUrl: "/settings/connections?notion=reconnect-started" });
    } else if (path === "/api/connectors/notion") {
      const connection = { ...expiredConnection, status: connectionStatus };
      await json({ configured: true, connected: true, connections: [connection, otherConnection] });
    } else if (path === "/api/connectors/slack") {
      await json({ configured: false, connected: false, connections: [], eventRequestUrl: "" });
    } else if (path === "/api/builder/conversations/conversation-expiry") {
      await json(snapshot);
    } else if (path === "/api/builder/conversations") {
      await json([{ conversationId: "conversation-expiry", workflowId: "workflow-expiry", title: "만료 복구 자동화", status: "ACTIVE", currentVersionNo: 1, updatedAt: "2026-08-27T00:00:00Z" }]);
    } else if (path === "/api/builder/workflows/workflow-expiry/production-runs") {
      productionStarted = true;
      connectionStatus = "INVALID";
      await json(failedRun);
    } else if (path === "/api/builder/production-runs/production-expired/retry") {
      retries += 1;
      await json({ ...failedRun, status: "QUEUED", failureCode: undefined, failureMessage: undefined });
    } else if (path === "/api/mini-homes/me") {
      await json({ title: "만료 복구 회사" });
    } else if (path === "/api/auth/me") {
      await json({ displayName: "복구 사용자", handle: "recovery", role: "USER" });
    } else if (path === "/api/auth/csrf") {
      await json({});
    } else {
      await route.fulfill({ status: 404, contentType: "application/json", body: "{}" });
    }
  });

  await page.goto("/settings/connections");
  connectionStatus = "INVALID";
  await page.reload();
  await expect(page.getByText("다시 연결 필요", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("Notion 연결이 만료되었습니다. 다시 연결한 뒤 보존된 실행을 재시도해 주세요.")).toBeVisible();
  const notionPanel = page.locator("article").filter({
    has: page.getByRole("heading", { name: "Notion", exact: true }),
  });
  const expiredConnectionCard = notionPanel.locator("div.mt-4.border.p-4").filter({
    has: page.getByText("만료된 Notion", { exact: true }),
  });
  const activeConnectionCard = notionPanel.locator("div.mt-4.border.p-4").filter({
    has: page.getByText("다른 Notion", { exact: true }),
  });
  await expect(expiredConnectionCard).toHaveCount(1);
  await expect(activeConnectionCard).toHaveCount(1);
  await expect(expiredConnectionCard.getByRole("button", { name: "실제 읽기 검증" })).toHaveCount(0);
  await expect(activeConnectionCard.getByRole("button", { name: "실제 읽기 검증" })).toHaveCount(1);
  await page.getByRole("button", { name: "Notion 다시 연결" }).click();
  await expect.poll(() => oauthStarts).toBe(1);
  await expect(page).toHaveURL(/\/settings\/connections\?notion=reconnect-started$/);

  connectionStatus = "ACTIVE";
  await page.goto("/assemble/automation");
  await page.getByRole("button", { name: "실제 실행" }).click();
  await expect(page.getByLabel("Notion 연결")).toHaveValue("notion-preserved");
  await page.getByPlaceholder("Notion에서 공유한 상위 페이지 ID").fill("12345678901234567890123456789012");
  await page.getByLabel("실제 업무 입력").fill("만료 복구 화면을 검증할 입력");
  await page.getByRole("button", { name: "실제 결과 생성" }).click();
  await expect.poll(() => productionStarted).toBe(true);

  await expect(page.getByRole("heading", { name: "보존된 주간 보고서" })).toBeVisible();
  await expect(page.getByText("Notion 다시 연결 필요", { exact: true })).toBeVisible();
  await expect(page.getByText("이 실행과 미리보기는 보존되었습니다. 지금 재시도하지 말고 Notion을 다시 연결한 뒤 돌아와 재시도하세요.")).toBeVisible();
  await expect(page.getByRole("button", { name: /재시도/ })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "업무 연결에서 Notion 다시 연결" })).toHaveAttribute("href", "/settings/connections");

  await page.getByLabel("Notion 연결").selectOption("notion-other-active");
  await expect(page.getByRole("button", { name: /재시도/ })).toHaveCount(0);

  connectionStatus = "ACTIVE";
  await expect(page.getByText("Notion 다시 연결 완료", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "재시도 (1/3)" }).click();
  await expect.poll(() => retries).toBe(1);
});
