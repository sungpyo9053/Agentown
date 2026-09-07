import { expect, test } from "@playwright/test";

function baseSnapshot(overrides: Record<string, unknown> = {}) {
  return {
    conversationId: "conversation-card",
    workflowId: "workflow-card",
    status: "NEEDS_CLARIFICATION",
    clarificationQuestions: [
      { id: "audience", field: "targetUser", question: "누가 사용할 신발이며 어떤 불편을 해결하나요?", required: true, options: ["일상 보행", "야외 활동", "아직 모르겠음", "기타 — 직접 입력"], multiple: false, customPlaceholder: "사용자와 불편을 적어주세요" },
      { id: "result", field: "desiredOutcome", question: "어떤 결과물이 필요한가요?", required: true, options: ["제품 기획서", "디자인 콘셉트", "제작 가이드", "아직 모르겠음"], multiple: true, customPlaceholder: "다른 결과물을 적어주세요" },
    ],
    agentDefinitions: [], messages: [], versions: [], sampleInput: {},
    packageStatus: "EXECUTION_NOT_CONFIGURED", interactiveStatus: "EXECUTION_NOT_CONFIGURED", automationStatus: "EXECUTION_NOT_CONFIGURED",
    ...overrides,
  };
}

async function mockShell(page: import("@playwright/test").Page, snapshot: Record<string, unknown>, onMessage?: (body: Record<string, unknown>) => void) {
  await page.addInitScript(() => localStorage.setItem("agentown.agent-development.session.v1", "conversation-card"));
  await page.route("**/api/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (value: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(value) });
    if (path === "/api/auth/me") return json({ displayName: "검증 사용자", role: "USER" });
    if (path === "/api/mini-homes/me") return json({ title: "검증 회사" });
    if (path === "/api/agent-development/sessions") return json([]);
    if (path === "/api/agent-development/sessions/conversation-card" && request.method() === "GET") return json(snapshot);
    if (path === "/api/agent-development/sessions/conversation-card/messages") { onMessage?.(request.postDataJSON()); return json({ id: "job-1", conversationId: "conversation-card", status: "RUNNING", stage: "REQUEST_ACCEPTED", elapsedSeconds: 0, remainingSeconds: 120 }); }
    if (path === "/api/agent-development/jobs/job-1") return json({ id: "job-1", conversationId: "conversation-card", status: "RUNNING", stage: "CODEX_ANALYZING", elapsedSeconds: 1, remainingSeconds: 119 });
    if (path === "/api/agent-development/sessions/conversation-card/package") return route.fulfill({ status: 200, contentType: "application/zip", body: "PK-test-package" });
    return json({}, 404);
  });
}

test("clarification cards accept choices and direct input without rebuilding a chat message", async ({ page }) => {
  let messageBody: Record<string, unknown> | undefined;
  await mockShell(page, baseSnapshot(), body => { messageBody = body; });
  await page.goto("/develop");

  await expect(page.getByRole("region", { name: "설계 입력 카드" })).toBeVisible();
  await page.getByRole("button", { name: "일상 보행" }).click();
  await page.getByPlaceholder("다른 결과물을 적어주세요").fill("치수 포함 설계도");
  await page.getByRole("button", { name: "이 조건으로 설계 시작" }).click();

  await expect.poll(() => String(messageBody?.content ?? "")).toContain("일상 보행");
  expect(String(messageBody?.content)).toContain("치수 포함 설계도");
  await expect(page.getByLabel("에이전트 개발 요청")).toHaveValue("");
});

test("team view hides developer graph, deduplicates resources, and downloads a blob package", async ({ page }) => {
  await mockShell(page, baseSnapshot({
    status: "READY_TO_SIMULATE", clarificationQuestions: [], currentVersionId: "version-1",
    proposal: { name: "신발 기획 팀", summary: "근거 기반 기획", capabilities: [], resourcePlan: { bindings: [
      { resourceKind: "TOOL", resourceKey: "platform.structured-ai", label: "Agentown 제공 AI", availability: "INSTALLED", reason: "구조화 출력", requiresUserAction: false },
      { resourceKind: "TOOL", resourceKey: "platform.structured-ai", label: "Agentown 제공 AI", availability: "INSTALLED", reason: "구조화 출력", requiresUserAction: false },
    ], uncoveredCapabilities: [], simulationReady: true, productionReady: true } },
  }));
  await page.goto("/develop");

  await expect(page.getByRole("button", { name: "구조" })).toHaveCount(0);
  await page.getByRole("button", { name: "리소스" }).click();
  await expect(page.getByText("Agentown 제공 AI")).toHaveCount(1);
  await page.getByRole("button", { name: "팀" }).click();
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "에이전트 패키지 다운로드" }).click();
  await expect((await download).suggestedFilename()).toBe("agentown-agent-conversa.zip");
});
