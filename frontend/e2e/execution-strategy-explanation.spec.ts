import { expect, test, type Page } from "@playwright/test";

type StrategyCase = {
  name: string;
  aiCalls: number;
  rationale: string;
  nodes: Array<{ id: string; nodeType: string; label: string }>;
};

const cases: StrategyCase[] = [
  {
    name: "zero AI proposal",
    aiCalls: 0,
    rationale: "AI를 호출하지 않습니다. ‘CSV 두 파일 입력’, ‘CSV 행 결정적 비교’ 단계는 일반 코드와 규칙으로 처리합니다.",
    nodes: [
      { id: "manual-input", nodeType: "manual.trigger", label: "CSV 두 파일 입력" },
      { id: "csv-compare", nodeType: "data.csv.compare", label: "CSV 행 결정적 비교" },
    ],
  },
  {
    name: "bounded AI proposal",
    aiCalls: 1,
    rationale: "AI는 ‘답변 초안 작성’ 단계에만 사용하며 실행당 1회 호출합니다. ‘고객 문의 입력’ 단계는 일반 코드와 규칙으로 처리합니다. ‘담당자 승인’ 단계는 사람이 확인하고 승인합니다. ‘FAQ 검색’ 단계는 연결 도구로 처리합니다.",
    nodes: [
      { id: "manual-input", nodeType: "manual.trigger", label: "고객 문의 입력" },
      { id: "faq-search", nodeType: "knowledge.search.mock", label: "FAQ 검색" },
      { id: "answer-draft", nodeType: "ai.generate", label: "답변 초안 작성" },
      { id: "approval", nodeType: "human.approval", label: "담당자 승인" },
    ],
  },
];

function snapshot(strategy: StrategyCase) {
  const nodes = strategy.nodes.map((node, index) => ({ ...node, position: { x: index * 240, y: 0 }, config: {} }));
  return {
    workspaceId: "workspace-strategy",
    conversationId: "conversation-strategy",
    workflowId: "workflow-strategy",
    status: "WAITING_DESIGN_APPROVAL",
    requirement: { objective: "실행 방법 설명", trigger: "수동 입력", inputs: [], outputs: [], steps: nodes.map(node => node.label), decisions: [], exceptions: [] },
    clarificationQuestions: [],
    proposal: {
      name: "실행 방법 검토",
      summary: "승인 전에 실행 방법과 AI 사용 범위를 확인합니다.",
      capabilities: nodes.map(node => node.label),
      integrations: [],
      approvalPoints: [],
      failurePolicy: "입력이 올바르지 않으면 중단",
      templateSelection: { templateKey: "strategy-preview", version: 1, source: "GENERATED", matchReason: "요청에 맞춘 실행 방법" },
      economics: { agentCount: strategy.aiCalls, estimatedAiCallsPerRun: strategy.aiCalls, separationRationale: [strategy.rationale] },
    },
    agentDefinitions: [],
    agentMarkdown: [],
    guideDefinitions: [],
    guideMarkdown: [],
    graph: { schemaVersion: "1", workflowId: "workflow-strategy", entryNodeId: nodes[0].id, nodes, edges: [] },
    validation: { valid: true, graphHash: "strategy-hash", validatorVersion: "1", issues: [] },
    currentVersionId: "version-strategy",
    messages: [],
    versions: [{ id: "version-strategy", versionNo: 1, graphHash: "strategy-hash", changeSummary: "최초 설계", approved: false, createdAt: "2026-09-04T01:00:00Z" }],
  };
}

async function mockBuilder(page: Page, strategy: StrategyCase) {
  const writeRequests: string[] = [];
  const proposal = snapshot(strategy);
  await page.addInitScript(() => window.localStorage.setItem("agentown.builder.conversation.v1", "conversation-strategy"));
  await page.route("**/api/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });

    if (request.method() !== "GET" && path !== "/api/auth/csrf") writeRequests.push(`${request.method()} ${path}`);
    if (path === "/api/builder/conversations/conversation-strategy") return json(proposal);
    if (path === "/api/builder/conversations") return json([{ conversationId: proposal.conversationId, workflowId: proposal.workflowId, title: proposal.proposal.name, status: proposal.status, currentVersionNo: 1, updatedAt: "2026-09-04T01:00:00Z" }]);
    if (path === "/api/builder/workflows/workflow-strategy/production-runs") return json([]);
    if (path === "/api/builder/conversations/conversation-strategy/generation-jobs/latest-recoverable") return json(null);
    if (path === "/api/connectors/notion") return json({ configured: false, connected: false, connections: [] });
    if (path === "/api/connectors/slack") return json({ configured: false, connected: false });
    if (path === "/api/mini-homes/me") return json({ title: "실행 방법 회사" });
    if (path === "/api/auth/me") return json({ displayName: "검토 사용자", handle: "strategy-user", role: "USER" });
    if (path === "/api/auth/csrf") return json({});
    return route.fulfill({ status: 404, contentType: "application/json", body: "{}" });
  });
  return writeRequests;
}

for (const strategy of cases) {
  test(`${strategy.name} shows a truthful explanation before approval without writing`, async ({ page }) => {
    const writeRequests = await mockBuilder(page, strategy);

    await page.goto("/assemble/automation");

    const explanation = page.getByTestId("execution-strategy-explanation");
    const approve = page.getByTestId("approve-design");
    await expect(explanation.getByRole("heading", { name: "이 실행 방법을 선택한 이유" })).toBeVisible();
    await expect(explanation).toContainText(strategy.rationale);
    await expect(page.getByText(`실행당 AI ${strategy.aiCalls}회`, { exact: true })).toBeVisible();
    await expect(approve).toBeVisible();
    expect(await explanation.evaluate((node, approval) => Boolean(node.compareDocumentPosition(approval as Node) & Node.DOCUMENT_POSITION_FOLLOWING), await approve.elementHandle())).toBe(true);
    expect(writeRequests).toEqual([]);
  });
}
