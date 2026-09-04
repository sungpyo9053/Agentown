import { expect, test, type Page } from "@playwright/test";

const unsupportedMessage = "요청한 변경은 아직 지원하지 않습니다. 현재 가능한 수정은 출력 템플릿 조정, Slack 전송을 이메일로 변경, Slack 답변 전 담당자 승인 추가입니다. 기존 자동화는 변경되지 않았습니다.";

const snapshot = {
  workspaceId: "workspace-unsupported-patch",
  conversationId: "conversation-unsupported-patch",
  workflowId: "workflow-unsupported-patch",
  status: "READY_TO_SIMULATE",
  requirement: {
    objective: "Slack 문의 답변 자동화",
    trigger: "Slack 문의 수신",
    inputs: ["Slack 문의"],
    outputs: ["Slack 스레드 답변"],
    steps: ["문의 수신", "FAQ 검색", "답변 초안", "담당자 승인", "Slack 답변"],
    decisions: [],
    exceptions: [],
  },
  clarificationQuestions: [],
  proposal: {
    name: "Slack FAQ 답변 자동화",
    summary: "담당자 승인 후 Slack 스레드에 답변합니다.",
    capabilities: ["FAQ 검색", "답변 초안", "담당자 승인", "Slack 답변"],
    integrations: ["Slack Mock", "Notion Mock"],
    approvalPoints: ["Slack 답변 전 담당자 승인"],
    failurePolicy: "실패 시 중단",
  },
  agentDefinitions: [],
  agentMarkdown: [],
  guideDefinitions: [],
  guideMarkdown: [],
  graph: {
    schemaVersion: "1",
    workflowId: "workflow-unsupported-patch",
    entryNodeId: "slack-in",
    nodes: [
      { id: "slack-in", nodeType: "slack.new_message.mock", label: "Slack 문의 수신 (Mock)", position: { x: 0, y: 0 }, config: {} },
      { id: "draft", nodeType: "ai.generate", label: "AI 답변 초안", position: { x: 240, y: 0 }, config: {} },
      { id: "approval", nodeType: "human.approval", label: "담당자 승인", position: { x: 480, y: 0 }, config: {} },
      { id: "slack-out", nodeType: "slack.reply.mock", label: "Slack 스레드 답변 (Mock)", position: { x: 720, y: 0 }, config: {} },
    ],
    edges: [
      { id: "e1", source: "slack-in", target: "draft" },
      { id: "e2", source: "draft", target: "approval" },
      { id: "e3", source: "approval", target: "slack-out" },
    ],
  },
  validation: { valid: true, graphHash: "approved-graph-hash", validatorVersion: "1", issues: [] },
  currentVersionId: "version-1",
  approvedVersionId: "version-1",
  messages: [
    { id: "message-1", role: "ASSISTANT", content: "설계 승인과 서버 검증이 완료되었습니다. 캔버스와 샘플 시뮬레이션을 사용할 수 있습니다.", workflowVersionId: "version-1", createdAt: "2026-09-04T03:00:00Z" },
  ],
  versions: [
    { id: "version-1", versionNo: 1, graphHash: "approved-graph-hash", changeSummary: "최초 승인 설계 컴파일", approved: true, createdAt: "2026-09-04T03:00:00Z" },
  ],
};

async function mockApprovedBuilder(page: Page) {
  const patchRequests: Array<Record<string, unknown>> = [];
  await page.addInitScript(() => window.localStorage.setItem("agentown.builder.conversation.v1", "conversation-unsupported-patch"));
  await page.route("**/api/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });

    if (request.method() === "POST" && path === "/api/builder/workflows/workflow-unsupported-patch/patches") {
      patchRequests.push(request.postDataJSON());
      return json({ code: "UNSUPPORTED_GRAPH_PATCH", message: unsupportedMessage, details: {} }, 400);
    }
    if (path === "/api/builder/conversations/conversation-unsupported-patch") return json(snapshot);
    if (path === "/api/builder/conversations") return json([{ conversationId: snapshot.conversationId, workflowId: snapshot.workflowId, title: snapshot.proposal.name, status: snapshot.status, currentVersionNo: 1, updatedAt: "2026-09-04T03:00:00Z" }]);
    if (path === "/api/builder/workflows/workflow-unsupported-patch/production-runs") return json([]);
    if (path === "/api/builder/conversations/conversation-unsupported-patch/generation-jobs/latest-recoverable") return route.fulfill({ status: 204 });
    if (path === "/api/connectors/notion") return json({ configured: false, connected: false, connections: [] });
    if (path === "/api/connectors/slack") return json({ configured: false, connected: false });
    if (path === "/api/mini-homes/me") return json({ title: "수정 검증 회사" });
    if (path === "/api/auth/me") return json({ displayName: "수정 검토 사용자", handle: "patch-reviewer", role: "USER" });
    if (path === "/api/auth/csrf") return json({});
    return json({}, 404);
  });
  return patchRequests;
}

test("unsupported Slack deletion shows recoverable guidance without a phantom version or success", async ({ page }) => {
  const patchRequests = await mockApprovedBuilder(page);

  await page.goto("/assemble/automation");
  await expect(page.getByTestId("workflow-version-1")).toContainText("Version 1");
  await page.getByLabel("업무 설명 또는 수정 요청").fill("Slack 노드를 삭제해줘.");
  await page.getByRole("button", { name: "Graph Patch 요청" }).click();

  const productErrorPanel = page.locator('[role="alert"]:not(#__next-route-announcer__)').filter({ hasText: unsupportedMessage });
  await expect(productErrorPanel).toHaveCount(1);
  await expect(productErrorPanel).toContainText(unsupportedMessage);
  await expect(page.getByTestId("workflow-version-1")).toContainText("Version 1");
  await expect(page.getByTestId("workflow-version-2")).toHaveCount(0);
  await expect(page.getByTestId("builder-conversation")).not.toContainText("Graph Patch를 검증해 새 버전");
  expect(patchRequests).toEqual([{ instruction: "Slack 노드를 삭제해줘.", baseVersionId: "version-1", expectedGraphHash: "approved-graph-hash" }]);
});
