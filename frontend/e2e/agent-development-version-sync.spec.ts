import { expect, test } from "@playwright/test";

test("develop patch refreshes the server version before changing the canvas", async ({ page }) => {
  let reads = 0;
  let patchBody: Record<string, unknown> | undefined;
  const snapshot = (version: number) => ({
    conversationId: "conversation-1",
    workflowId: "workflow-1",
    status: "READY_TO_SIMULATE",
    proposal: { name: "FAQ 에이전트", summary: "근거 기반 답변", capabilities: [], resourcePlan: { bindings: [], uncoveredCapabilities: [], simulationReady: true, productionReady: false } },
    agentDefinitions: [{ key: "faq-agent", name: "FAQ 답변 에이전트", role: "근거만 사용", behaviorRules: [], forbiddenRules: [], evidenceRequirements: [], toolKeys: [], skillKeys: [], memoryScope: "NONE" }],
    graph: { nodes: [{ id: "start", nodeType: "manual.trigger", label: "시작", position: { x: 0, y: 0 }, config: {} }], edges: [] },
    currentVersionId: `version-${version}`,
    validation: { valid: true, graphHash: `hash-${version}` },
    messages: [],
    versions: [{ id: `version-${version}`, versionNo: version, graphHash: `hash-${version}`, changeSummary: "최신 설계", approved: true, createdAt: "2026-09-02T00:00:00Z" }],
  });
  await page.addInitScript(() => localStorage.setItem("agentown.agent-development.session.v1", "conversation-1"));
  await page.route("**/api/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (value: unknown) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(value) });
    if (path === "/api/auth/me") return json({ displayName: "검증 사용자", role: "USER" });
    if (path === "/api/mini-homes/me") return json({ title: "검증 회사" });
    if (path === "/api/agent-development/sessions") return json([{ conversationId: "conversation-1", workflowId: "workflow-1", title: "FAQ 에이전트", status: "READY_TO_SIMULATE", currentVersionNo: 1, updatedAt: "2026-09-02T00:00:00Z" }]);
    if (path === "/api/agent-development/sessions/conversation-1" && request.method() === "GET") return json(snapshot(++reads === 1 ? 1 : 2));
    if (path === "/api/agent-development/sessions/conversation-1/patches") {
      patchBody = request.postDataJSON();
      return json(snapshot(3));
    }
    return route.fulfill({ status: 404, contentType: "application/json", body: "{}" });
  });

  await page.goto("/develop");
  await page.getByLabel("에이전트 개발 요청").fill("근거가 없으면 담당자 확인으로 바꿔줘");
  await page.getByRole("button", { name: "보내기" }).click();

  await expect.poll(() => patchBody).toMatchObject({ baseVersionId: "version-2", expectedGraphHash: "hash-2" });
  await expect(page.getByRole("link", { name: "에이전트 패키지" })).toBeVisible();
  await page.getByRole("button", { name: "버전" }).click();
  await expect(page.getByText("Version 3")).toBeVisible();
});
