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
    validation: { valid: true, graphHash: `recomputed-hash-${version}` },
    messages: [],
    versions: [{ id: `version-${version}`, versionNo: version, graphHash: `stored-hash-${version}`, changeSummary: "최신 설계", approved: true, createdAt: "2026-09-02T00:00:00Z" }],
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

  await expect.poll(() => patchBody).toMatchObject({ baseVersionId: "version-2", expectedGraphHash: "stored-hash-2" });
  await expect(page.getByRole("link", { name: "에이전트 패키지" })).toBeVisible();
  await page.getByRole("button", { name: "버전" }).click();
  await expect(page.getByText("Version 3")).toBeVisible();
});

test("deterministic agent design without AI team members can be approved", async ({ page }) => {
  let decisionBody: Record<string, unknown> | undefined;
  const snapshot = {
    conversationId: "csv-conversation",
    workflowId: "csv-workflow",
    status: "WAITING_DESIGN_APPROVAL",
    proposal: { name: "CSV 변경 행 비교", summary: "ID 기준으로 추가 수정 삭제 행을 비교합니다.", capabilities: ["CSV 비교"], resourcePlan: { bindings: [], uncoveredCapabilities: [], simulationReady: true, productionReady: true } },
    agentDefinitions: [],
    messages: [],
    versions: [],
  };
  await page.addInitScript(() => localStorage.setItem("agentown.agent-development.session.v1", "csv-conversation"));
  await page.route("**/api/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (value: unknown) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(value) });
    if (path === "/api/auth/me") return json({ displayName: "검증 사용자", role: "USER" });
    if (path === "/api/mini-homes/me") return json({ title: "검증 회사" });
    if (path === "/api/agent-development/sessions") return json([]);
    if (path === "/api/agent-development/sessions/csv-conversation" && request.method() === "GET") return json(snapshot);
    if (path === "/api/agent-development/sessions/csv-conversation/design-decision") {
      decisionBody = request.postDataJSON();
      return json({ ...snapshot, status: "READY_TO_SIMULATE", currentVersionId: "csv-version-1" });
    }
    return route.fulfill({ status: 404, contentType: "application/json", body: "{}" });
  });

  await page.goto("/develop");
  await expect(page.getByText("CSV 변경 행 비교")).toBeVisible();
  await page.getByRole("button", { name: "설계 승인" }).click();
  await expect.poll(() => decisionBody).toEqual({ approve: true });
});

test("develop CSV test sends structured sample input without exposing compiler instructions", async ({ page }) => {
  let simulationBody: Record<string, unknown> | undefined;
  const snapshot = {
    conversationId: "csv-conversation",
    workflowId: "csv-workflow",
    status: "READY_TO_SIMULATE",
    proposal: {
      name: "CSV 변경 행 비교",
      summary: "두 CSV를 비교합니다.",
      capabilities: [],
      resourcePlan: { bindings: [], uncoveredCapabilities: [], simulationReady: true, productionReady: true },
      agentDesign: {
        naturalLanguageSummary: "CSV 비교",
        assumptions: [],
        simulationScenarios: [{ name: "검증", input: { text: "다음 요청은 업무 자동화 배치가 아니라 내부 컴파일 지시문" }, expectedStages: [] }],
        review: { passed: true, issues: [] },
      },
    },
    agentDefinitions: [],
    graph: {
      nodes: [
        { id: "manual", nodeType: "manual.trigger", label: "입력", position: { x: 0, y: 0 }, config: {} },
        { id: "compare", nodeType: "data.csv.compare", label: "비교", position: { x: 200, y: 0 }, config: {} },
      ],
      edges: [{ id: "edge", source: "manual", target: "compare" }],
    },
    currentVersionId: "csv-version-1",
    validation: { valid: true, graphHash: "hash" },
    messages: [],
    versions: [{ id: "csv-version-1", versionNo: 1, graphHash: "hash", changeSummary: "최초", approved: true, createdAt: "2026-09-04T00:00:00Z" }],
  };
  await page.addInitScript(() => localStorage.setItem("agentown.agent-development.session.v1", "csv-conversation"));
  await page.route("**/api/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (value: unknown) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(value) });
    if (path === "/api/auth/me") return json({ displayName: "검증 사용자", role: "USER" });
    if (path === "/api/mini-homes/me") return json({ title: "검증 회사" });
    if (path === "/api/agent-development/sessions") return json([]);
    if (path === "/api/agent-development/sessions/csv-conversation" && request.method() === "GET") return json(snapshot);
    if (path === "/api/agent-development/sessions/csv-conversation/simulations") {
      simulationBody = request.postDataJSON();
      return json({ id: "run", status: "SUCCEEDED", output: {}, steps: [] });
    }
    return route.fulfill({ status: 404, contentType: "application/json", body: "{}" });
  });

  await page.goto("/develop");
  await page.getByRole("button", { name: "테스트" }).click();
  const input = page.getByLabel("테스트 입력");
  await expect(input).toHaveAttribute("placeholder", /csvA/);
  await expect(input).not.toHaveAttribute("placeholder", /업무 자동화 배치가 아니라/);
  await page.getByRole("button", { name: "테스트 실행" }).click();
  await expect.poll(() => simulationBody).toEqual({ input: { csvA: "id,name\n1,old\n2,remove\n", csvB: "id,name\n1,new\n3,add\n" } });
});
