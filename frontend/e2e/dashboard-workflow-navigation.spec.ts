import { expect, test, type Page } from "@playwright/test";

const conversations = Array.from({ length: 20 }, (_, index) => ({
  conversationId: index === 0 ? "conversation-a" : `recent-conversation-${index}`,
  workflowId: index === 0 ? "workflow-a" : `recent-workflow-${index}`,
  title: `최근 업무 ${index + 1}`,
  status: "DRAFT",
  updatedAt: `2026-09-06T${String(index + 1).padStart(2, "0")}:00:00Z`,
}));

function snapshot(conversationId: string, workflowId: string, message: string) {
  return {
    workspaceId: "workspace-owner",
    conversationId,
    workflowId,
    status: "ACTIVE",
    clarificationQuestions: [],
    agentDefinitions: [],
    agentMarkdown: [],
    guideDefinitions: [],
    guideMarkdown: [],
    messages: [{ id: `message-${workflowId}`, role: "ASSISTANT", content: message, createdAt: "2026-09-06T02:00:00Z" }],
    versions: [],
  };
}

async function mockDashboardAndBuilder(page: Page, savedConversation = true) {
  const conversationRequests: string[] = [];
  if (savedConversation) {
    await page.addInitScript(() => window.localStorage.setItem("agentown.builder.conversation.v1", "conversation-a"));
  }
  await page.route("**/api/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
    const directConversation = path.match(/^\/api\/builder\/conversations\/([^/]+)$/);
    if (request.method() === "GET" && directConversation) conversationRequests.push(decodeURIComponent(directConversation[1]));

    if (path === "/api/mini-homes/me") return json({ id: "home-owner", handle: "owner", title: "워크플로 회사", introduction: "", visitCount: 1, items: [] });
    if (path === "/api/auth/me") return json({ displayName: "워크플로 사용자", handle: "owner", role: "USER" });
    if (path === "/api/auth/csrf") return json({});
    if (path === "/api/agents" || path === "/api/executions" || path === "/api/harnesses" || path === "/api/local-runners") return json([]);
    if (path === "/api/builder/active-automation-teams") return json([
      { teamId: "team-a", workflowId: "workflow-a", conversationId: "conversation-a", workflowVersionId: "version-a", versionNo: 1, category: "지원", teamName: "첫 번째 팀", workflowName: "첫 번째 업무", employees: [] },
      { teamId: "team-b", workflowId: "workflow-b", conversationId: "conversation-b", workflowVersionId: "version-b", versionNo: 2, category: "운영", teamName: "두 번째 팀", workflowName: "두 번째 업무", employees: [] },
    ]);
    if (path === "/api/builder/conversations") return json(conversations);
    if (path === "/api/builder/conversations/conversation-a") return json(snapshot("conversation-a", "workflow-a", "첫 번째 워크플로 내용"));
    if (path === "/api/builder/conversations/conversation-b") return json(snapshot("conversation-b", "workflow-b", "두 번째 워크플로 내용"));
    if (/^\/api\/builder\/conversations\/conversation-[ab]\/generation-jobs\/latest-recoverable$/.test(path)) return json(null);
    if (/^\/api\/builder\/workflows\/workflow-[ab]\/production-runs$/.test(path)) return json([]);
    if (path === "/api/connectors/notion") return json({ configured: false, connected: false, connections: [] });
    if (path === "/api/connectors/slack") return json({ configured: false, connected: false });
    return route.fulfill({ status: 404, contentType: "application/json", body: "{}" });
  });
  return conversationRequests;
}

test("an active team omitted from the 20-item history opens its direct conversation despite stale Builder storage", async ({ page }) => {
  const conversationRequests = await mockDashboardAndBuilder(page);
  await page.goto("/dashboard");

  const firstTeam = page.getByRole("heading", { name: "첫 번째 팀" }).locator("xpath=ancestor::section[1]");
  await expect(firstTeam.getByRole("link", { name: "Workflow 보기" })).toHaveAttribute("href", "/assemble/automation?conversationId=conversation-a&workflowId=workflow-a");
  const secondTeam = page.getByRole("heading", { name: "두 번째 팀" }).locator("xpath=ancestor::section[1]");
  const workflowLink = secondTeam.getByRole("link", { name: "Workflow 보기" });
  await expect(workflowLink).toHaveAttribute("href", "/assemble/automation?conversationId=conversation-b&workflowId=workflow-b");
  await workflowLink.click();

  await expect(page).toHaveURL(/\/assemble\/automation\?conversationId=conversation-b&workflowId=workflow-b$/);
  await expect(page.getByLabel("저장된 업무 자동화")).toHaveValue("conversation-b");
  await expect(page.getByTestId("builder-conversation")).toContainText("두 번째 워크플로 내용");
  await expect.poll(() => page.evaluate(() => window.localStorage.getItem("agentown.builder.conversation.v1"))).toBe("conversation-b");
  expect(conversationRequests).toContain("conversation-b");
  expect(conversationRequests).not.toContain("conversation-a");
});

for (const target of ["/assemble/automation", "/assemble/automation?workflowId=workflow-missing"]) {
  test(`saved conversation remains the fallback for ${target}`, async ({ page }) => {
    const conversationRequests = await mockDashboardAndBuilder(page);
    await page.goto(target);

    await expect(page.getByLabel("저장된 업무 자동화")).toHaveValue("conversation-a");
    await expect(page.getByTestId("builder-conversation")).toContainText("첫 번째 워크플로 내용");
    await expect.poll(() => page.evaluate(() => window.localStorage.getItem("agentown.builder.conversation.v1"))).toBe("conversation-a");
    expect(conversationRequests).toContain("conversation-a");
    expect(conversationRequests).not.toContain("workflow-missing");
  });
}

test("no direct target or saved conversation keeps the Builder empty", async ({ page }) => {
  const conversationRequests = await mockDashboardAndBuilder(page, false);
  await page.goto("/assemble/automation");

  await expect(page.getByLabel("저장된 업무 자동화")).toHaveValue("");
  await expect(page.getByTestId("builder-conversation")).toContainText("내 업무를 직접 설명하거나 아래 예시를 선택해 시작하세요.");
  await expect.poll(() => page.evaluate(() => window.localStorage.getItem("agentown.builder.conversation.v1"))).toBeNull();
  expect(conversationRequests).toEqual([]);
});
