import { expect, test, type Page } from "@playwright/test";

type MockVersion = {
  id: string;
  versionNo: number;
  graphHash: string;
  changeSummary: string;
  approved: boolean;
  createdAt: string;
};

const initialVersions: MockVersion[] = [
  { id: "version-1", versionNo: 1, graphHash: "hash-1", changeSummary: "최초 고객 문의 자동화 설계", approved: false, createdAt: "2026-08-25T01:00:00Z" },
  { id: "version-3", versionNo: 3, graphHash: "hash-3", changeSummary: "응답 형식 간소화", approved: false, createdAt: "2026-08-27T03:00:00Z" },
  { id: "version-2", versionNo: 2, graphHash: "hash-2", changeSummary: "담당자 승인 단계 추가", approved: true, createdAt: "2026-08-26T02:00:00Z" },
];

function snapshot(status: string, versions: MockVersion[], currentVersionId: string, approvedVersionId?: string) {
  return {
    workspaceId: "workspace-version-history",
    conversationId: "conversation-version-history",
    workflowId: "workflow-version-history",
    status,
    requirement: { objective: "고객 문의 답변", trigger: "Slack 문의", inputs: ["문의"], outputs: ["답변"], steps: ["검색", "작성"], decisions: [], exceptions: [] },
    clarificationQuestions: [],
    proposal: { name: "고객 문의 자동화", summary: "FAQ를 찾아 답변을 작성합니다.", capabilities: [], integrations: [], approvalPoints: [], failurePolicy: "중단" },
    agentDefinitions: [],
    agentMarkdown: [],
    guideDefinitions: [],
    guideMarkdown: [],
    graph: {
      schemaVersion: "1",
      workflowId: "workflow-version-history",
      entryNodeId: "draft",
      nodes: [{ id: "draft", nodeType: "ai.generate", label: "답변 초안", position: { x: 0, y: 0 }, config: {} }],
      edges: [],
    },
    validation: { valid: true, graphHash: versions.find(version => version.id === currentVersionId)?.graphHash ?? "hash", validatorVersion: "1", issues: [] },
    currentVersionId,
    approvedVersionId,
    messages: [],
    versions,
  };
}

async function mockBuilder(page: Page, initialSnapshot: ReturnType<typeof snapshot>, restoredSnapshot?: ReturnType<typeof snapshot>) {
  let restoreRequests = 0;
  let idempotencyKey = "";
  const unexpectedWritePaths: string[] = [];

  await page.addInitScript(() => window.localStorage.setItem("agentown.builder.conversation.v1", "conversation-version-history"));
  await page.route("**/api/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });

    if (path === "/api/builder/workflows/workflow-version-history/versions/version-1/restore") {
      restoreRequests += 1;
      idempotencyKey = request.headers()["idempotency-key"] ?? "";
      return json(restoredSnapshot ?? initialSnapshot);
    }
    if (request.method() === "POST" && path !== "/api/auth/csrf") unexpectedWritePaths.push(path);
    if (path === "/api/builder/conversations/conversation-version-history") return json(initialSnapshot);
    if (path === "/api/builder/conversations") return json([{ conversationId: initialSnapshot.conversationId, workflowId: initialSnapshot.workflowId, title: "버전 복구 자동화", status: initialSnapshot.status, currentVersionNo: initialSnapshot.versions.find(version => version.id === initialSnapshot.currentVersionId)?.versionNo, updatedAt: "2026-08-27T03:00:00Z" }]);
    if (path === "/api/connectors/notion") return json({ configured: false, connected: false, connections: [] });
    if (path === "/api/connectors/slack") return json({ configured: false, connected: false });
    if (path === "/api/mini-homes/me") return json({ title: "버전 복구 회사" });
    if (path === "/api/auth/me") return json({ displayName: "복구 사용자", handle: "restore-user", role: "USER" });
    if (path === "/api/auth/csrf") return json({});
    return route.fulfill({ status: 404, contentType: "application/json", body: "{}" });
  });

  return {
    restoreRequests: () => restoreRequests,
    idempotencyKey: () => idempotencyKey,
    unexpectedWritePaths,
  };
}

test("이전 버전을 확인하고 원본을 보존한 새 검토 버전으로 한 번 복원한다", async ({ page }) => {
  const initialSnapshot = snapshot("ACTIVE", initialVersions, "version-3", "version-2");
  const restoredVersion: MockVersion = { id: "version-4", versionNo: 4, graphHash: "hash-1", changeSummary: "버전 1 복원", approved: false, createdAt: "2026-08-28T13:10:00Z" };
  const restoredSnapshot = snapshot("READY_TO_SIMULATE", [restoredVersion, ...initialVersions], "version-4", "version-2");
  const requests = await mockBuilder(page, initialSnapshot, restoredSnapshot);

  await page.goto("/assemble/automation");
  await expect(page.getByRole("heading", { name: "버전 기록" })).toBeVisible();
  await expect(page.locator("[data-testid^='workflow-version-']")).toHaveCount(3);
  await expect(page.locator("[data-testid^='workflow-version-'] p:first-child")).toHaveText(["Version 3", "Version 2", "Version 1"]);
  await expect(page.locator("[data-testid^='workflow-version-'] time")).toHaveCount(3);

  const current = page.getByTestId("workflow-version-3");
  await expect(current.getByText("현재 버전", { exact: true })).toBeVisible();
  await expect(current.getByText("현재 검토 버전", { exact: true })).toBeVisible();
  await expect(current.getByRole("button", { name: /복원/ })).toHaveCount(0);
  await expect(page.getByTestId("workflow-version-2").getByText("승인됨", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Version 1 복원" })).toBeEnabled();

  let confirmation = "";
  page.once("dialog", async dialog => {
    confirmation = dialog.message();
    await dialog.accept();
  });
  await page.getByRole("button", { name: "Version 1 복원" }).click();

  await expect.poll(requests.restoreRequests).toBe(1);
  expect(requests.idempotencyKey()).not.toBe("");
  expect(confirmation).toContain("선택한 버전은 수정하거나 다시 활성화하지 않습니다");
  expect(confirmation).toContain("새 검토 버전");
  expect(confirmation).toContain("시뮬레이션과 승인을 다시 진행");
  await expect(page.getByText("READY_TO_SIMULATE", { exact: true })).toBeVisible();
  await expect(page.getByTestId("workflow-version-4").getByText("현재 버전", { exact: true })).toBeVisible();
  await expect(page.getByTestId("workflow-version-4").getByText("버전 1 복원", { exact: true })).toBeVisible();
  await expect(page.locator("[data-testid^='workflow-version-'] p:first-child")).toHaveText(["Version 4", "Version 3", "Version 2", "Version 1"]);
  expect(requests.restoreRequests()).toBe(1);
  expect(requests.unexpectedWritePaths).toEqual([]);
});

test("중지된 자동화는 기록을 보여주되 이전 버전 복원을 활성화하지 않는다", async ({ page }) => {
  const requests = await mockBuilder(page, snapshot("STOPPED", initialVersions.slice(1), "version-3", "version-2"));
  await page.goto("/assemble/automation");

  await expect(page.locator("[data-testid^='workflow-version-']")).toHaveCount(2);
  await expect(page.getByTestId("workflow-version-2").getByRole("button", { name: "Version 2 복원" })).toBeDisabled();
  expect(requests.restoreRequests()).toBe(0);
});

test("버전이 하나뿐인 자동화는 기록만 보여주고 복원 동작을 노출하지 않는다", async ({ page }) => {
  const onlyVersion = initialVersions[0];
  const requests = await mockBuilder(page, snapshot("READY_TO_SIMULATE", [onlyVersion], onlyVersion.id));
  await page.goto("/assemble/automation");

  await expect(page.getByTestId("workflow-version-1")).toBeVisible();
  await expect(page.getByRole("button", { name: /Version \d+ 복원/ })).toHaveCount(0);
  expect(requests.restoreRequests()).toBe(0);
});
