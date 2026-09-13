import { expect, test } from "@playwright/test";

test("runtime input is explicit and nested JSON results are readable with raw data retained", async ({ page }, testInfo) => {
  await mockShell(page, baseSnapshot({ status: "READY_TO_SIMULATE", clarificationQuestions: [], currentVersionId: "v1", sampleInput: { userMemo: "제품 사용 목적과 불편 예시 1" } }));
  await page.route("**/api/agent-development/sessions/conversation-card/simulations", route => route.fulfill({ contentType: "application/json", body: JSON.stringify({
    id: "run-readable", status: "SUCCEEDED", requirementMatched: true, steps: [],
    output: { renderedResponse: JSON.stringify({ finalPlan: "기획안\n낮은 클릭 힘\n\n근거표\n손목 움직임 제한", evidence: "<img src=x onerror=alert(1)>" }) },
  }) }));
  await page.goto("/develop");
  await page.getByRole("button", { name: "테스트", exact: true }).click();
  await expect(page.getByRole("button", { name: "테스트 실행", exact: true })).toBeDisabled();
  await page.getByRole("button", { name: "입력 예시 채우기" }).click();
  await expect(page.getByRole("textbox", { name: "테스트 입력" })).toHaveValue(/제품 사용 목적과 불편/);
  await page.getByRole("textbox", { name: "테스트 입력" }).fill(JSON.stringify({ userMemo: "손목 움직임 제한" }));
  await page.getByRole("button", { name: "테스트 실행", exact: true }).click();
  const result = page.getByRole("region", { name: "읽기 쉬운 실행 결과" });
  await expect(result).toContainText("낮은 클릭 힘");
  await expect(result.locator("pre").first()).toContainText("근거표");
  expect(await result.locator("pre").first().textContent()).not.toContain("\\n");
  await expect(result.locator("img")).toHaveCount(0);
  await page.screenshot({ path: testInfo.outputPath("readable-result.png"), fullPage: true });
  await expect(result.locator("details")).not.toHaveAttribute("open", "");
  await result.getByText("개발자용 원본 JSON", { exact: true }).click();
  await expect(result.locator("details pre")).toContainText("renderedResponse");
});

test("slow generation shows the actual retry stage rather than a false completion estimate", async ({ page }) => {
  await mockShell(page, baseSnapshot());
  await page.route("**/api/agent-development/jobs/job-1", route => route.fulfill({ contentType: "application/json", body: JSON.stringify({ id: "job-1", conversationId: "conversation-card", status: "RUNNING", stage: "RETRYING", elapsedSeconds: 70, remainingSeconds: 50 }) }));
  await page.goto("/develop");
  await page.getByRole("textbox", { name: "에이전트 개발 요청" }).fill("추가 조건입니다");
  await page.getByRole("button", { name: "보내기", exact: true }).click();
  await expect(page.getByText("응답 오류 후 재시도 · 70초 경과")).toBeVisible();
  await expect(page.getByText(/예상보다 오래 걸리고 있습니다/)).toBeVisible();
  await expect(page.getByRole("button", { name: "중지", exact: true })).toBeEnabled();
});

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
  const sessionId = String(snapshot.conversationId);
  await page.addInitScript(id => localStorage.setItem("agentown.agent-development.session.v1", id), sessionId);
  await page.route("**/api/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (value: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(value) });
    if (path === "/api/auth/me") return json({ displayName: "검증 사용자", role: "USER" });
    if (path === "/api/mini-homes/me") return json({ title: "검증 회사" });
    if (path === "/api/agent-development/sessions") return json([]);
    if (path === `/api/agent-development/sessions/${sessionId}` && request.method() === "GET") return json(snapshot);
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
  await expect(page.getByText("내 PC 실행에는 도구 설치와 AI 서비스 로그인이 필요할 수 있습니다. AI 이용 조건·요금은 해당 서비스 기준을 따릅니다.")).toBeVisible();
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "에이전트 패키지 다운로드" }).click();
  await expect((await download).suggestedFilename()).toBe("agentown-agent-conversa.zip");
});

test("expired download login preserves the request and retries without regeneration", async ({ page }) => {
  const sessionId = "22222222-2222-4222-8222-222222222222";
  await mockShell(page, baseSnapshot({ conversationId: sessionId, status: "READY_TO_SIMULATE", clarificationQuestions: [], currentVersionId: "version-1", proposal: { name: "다운로드 검증", summary: "입력 보존", capabilities: [] } }));
  let attempts = 0;
  await page.route(`**/api/agent-development/sessions/${sessionId}/package`, route => {
    attempts++;
    return attempts === 1
      ? route.fulfill({ status: 401, contentType: "application/json", body: "{}" })
      : route.fulfill({ contentType: "application/zip", body: "PK-test-package" });
  });
  await page.goto("/develop");
  const input = page.getByLabel("에이전트 개발 요청");
  await input.fill("아직 보내지 않은 추가 요구사항");
  const button = page.getByRole("button", { name: "에이전트 패키지 다운로드" });
  await button.click();
  const login = page.getByRole("link", { name: "새 탭에서 로그인" });
  await expect(login).toBeVisible();
  await expect(login).toHaveAttribute("target", "_blank");
  await expect(login).toHaveAttribute("href", `/login?next=${encodeURIComponent(`/develop?session=${sessionId}&panel=output`)}`);
  await expect(input).toHaveValue("아직 보내지 않은 추가 요구사항");
  const download = page.waitForEvent("download");
  await button.click();
  expect((await download).suggestedFilename()).toBe("agentown-agent-22222222.zip");
  await expect(login).toHaveCount(0);
  await expect(input).toHaveValue("아직 보내지 않은 추가 요구사항");
  expect(attempts).toBe(2);
});

test("a patch error does not leak into a prompt-only project or its follow-up", async ({ page }) => {
  await mockShell(page, baseSnapshot({
    status: "READY_TO_SIMULATE", clarificationQuestions: [], currentVersionId: "version-1",
    graph: { nodes: [], edges: [] }, validation: { valid: true, issues: [] },
    versions: [{ id: "version-1", versionNo: 1, graphHash: "hash-1" }],
  }));
  const errorMessage = "요청한 변경은 아직 지원하지 않습니다.";
  await page.route("**/api/agent-development/sessions/conversation-card/patches", route => route.fulfill({
    status: 400, contentType: "application/json", body: JSON.stringify({ code: "UNSUPPORTED_GRAPH_PATCH", message: errorMessage }),
  }));
  await page.route("**/api/agent-development/sessions", route => route.fulfill({
    contentType: "application/json", body: JSON.stringify([
      { conversationId: "prompt-project", title: "마우스 디자인", status: "DRAFT" },
    ]),
  }));
  await page.route("**/api/agent-development/sessions/prompt-project", route => route.fulfill({
    contentType: "application/json", body: JSON.stringify(baseSnapshot({
      conversationId: "prompt-project", status: "DRAFT", clarificationQuestions: [],
      messages: [{ id: "prompt-message", role: "ASSISTANT", content: "이 프롬프트를 그대로 넣어보세요: 마우스 디자인 시안을 제안해줘." }],
    })),
  }));
  let followUp = false;
  let releaseResponse!: () => void;
  const responseGate = new Promise<void>(resolve => { releaseResponse = resolve; });
  await page.route("**/api/agent-development/sessions/prompt-project/messages", async route => {
    followUp = true;
    await responseGate;
    return route.fulfill({ contentType: "application/json", body: JSON.stringify({ id: "job-1", conversationId: "prompt-project", status: "RUNNING" }) });
  });
  await page.goto("/develop");
  await page.getByLabel("에이전트 개발 요청").fill("새로운 역할도 추가해줘");
  await page.getByRole("button", { name: "보내기", exact: true }).click();
  await expect(page.getByText(errorMessage, { exact: true })).toBeVisible();
  await page.getByRole("button", { name: /마우스 디자인/ }).click();
  await expect(page.getByText(errorMessage, { exact: true })).toHaveCount(0);
  await page.getByLabel("에이전트 개발 요청").fill("손가락 조작이 어려운 사용자를 대상으로 해줘");
  await page.getByRole("button", { name: "보내기", exact: true }).click();
  await expect.poll(() => followUp).toBe(true);
  await expect(page.getByRole("button", { name: "보내기", exact: true })).toBeDisabled();
  releaseResponse();
});
