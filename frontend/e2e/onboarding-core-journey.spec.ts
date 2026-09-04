import { expect, test, type Page } from "@playwright/test";

const home = {
  id: "home-new-user",
  handle: "new-workspace",
  title: "새로운 회사",
  introduction: "반복 업무를 자동화하는 회사",
  backgroundKey: "office-warm",
  visibility: "PUBLIC",
  visitCount: 0,
  items: [],
};

const sampleRequest = "저는 회사에서 고객 문의를 담당하고 있습니다. Slack의 #customer-support 채널에 문의가 올라오면, Notion의 고객 FAQ 데이터베이스에서 관련 내용을 찾아 답변 초안을 만들고 있습니다. 답변은 바로 보내지 말고 제가 검토하고 승인한 경우에만 해당 Slack 메시지의 스레드로 전송되게 자동화하고 싶습니다.";

function observeBuilderPosts(page: Page) {
  const conversationCreates: string[] = [];
  const analysisBodies: unknown[] = [];
  page.on("request", request => {
    const path = new URL(request.url()).pathname;
    if (request.method() === "POST" && path === "/api/builder/conversations") conversationCreates.push(path);
    if (request.method() === "POST" && path.endsWith("/messages")) analysisBodies.push(request.postDataJSON());
  });
  return { conversationCreates, analysisBodies };
}

async function mockNewWorkspace(page: Page) {
  await page.route("**/api/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (body: unknown) => route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(body),
    });

    if (path === "/api/auth/availability") return json({ emailAvailable: true });
    if (path === "/api/auth/email/send-code") return json({ verificationId: "verification-new-user", expiresInSeconds: 600, developmentCode: "123456" });
    if (["/api/auth/email/verify-code", "/api/auth/signup", "/api/auth/login", "/api/auth/csrf"].includes(path)) return json({});
    if (path === "/api/auth/me") return json({ displayName: "새 사용자", handle: "new-user", role: "USER" });
    if (path === "/api/mini-homes/me") return json(home);
    if (path === "/api/builder/conversations" && request.method() === "POST") return json({
      conversationId: "conversation-new-user",
      workflowId: "workflow-new-user",
    });
    if (path === "/api/builder/conversations/conversation-new-user/messages") return json({
      id: "generation-new-user",
      conversationId: "conversation-new-user",
      workflowId: "workflow-new-user",
      status: "FAILED",
      stage: "mocked-browser-proof",
      estimatedSeconds: 0,
      elapsedSeconds: 0,
      remainingSeconds: 0,
    });
    if (["/api/agents", "/api/executions", "/api/harnesses", "/api/local-runners", "/api/builder/active-automation-teams", "/api/builder/conversations"].includes(path)) return json([]);
    if (path === "/api/connectors/slack") return json({ configured: false, connected: false });
    if (path === "/api/connectors/notion") return json({ configured: false, connected: false, connections: [] });
    return route.fulfill({ status: 404, contentType: "application/json", body: "{}" });
  });
}

async function completeSignup(page: Page, next?: string) {
  const suffix = `${Date.now()}-${Math.floor(Math.random() * 10_000)}`;
  const query = next === undefined ? "" : `?next=${encodeURIComponent(next)}`;
  await page.goto(`/signup${query}`);
  await page.getByLabel("이름").fill("새 사용자");
  await page.getByPlaceholder("name@example.com").fill(`onboarding-${suffix}@example.com`);
  await page.getByRole("button", { name: "중복 확인" }).click();
  await expect(page.getByText("사용 가능한 이메일입니다.")).toBeVisible();
  await page.getByRole("button", { name: "이메일 인증번호 발송" }).click();
  await expect(page.getByText("개발 인증번호: 123456")).toBeVisible();
  await page.getByRole("button", { name: "인증 확인" }).click();
  await expect(page.getByText("이메일 인증이 완료되었습니다.")).toBeVisible();
  await page.getByLabel("비밀번호").fill("Agentown!2026");
  await page.getByRole("button", { name: "인증하고 가입하기" }).click();
  await expect(page).toHaveURL(/\/onboarding\/company(?:\?.*)?$/);
}

async function saveCompany(page: Page) {
  await page.getByLabel("회사 이름").fill("새로운 회사");
  await page.getByLabel("이 회사가 하는 일").fill("반복 업무를 자동화합니다.");
  await page.getByRole("button", { name: "회사 만들고 시작하기" }).click();
}

test.beforeEach(async ({ page }) => {
  await mockNewWorkspace(page);
});

test("가입과 회사 설정 후 자연어 자동화 설명으로 바로 시작한다", async ({ page }) => {
  const { conversationCreates, analysisBodies } = observeBuilderPosts(page);

  await completeSignup(page);
  await saveCompany(page);

  await expect(page).toHaveURL(/\/assemble\/automation$/);
  const workDescription = page.getByLabel("업무 설명 또는 수정 요청");
  await expect(workDescription).toBeVisible();
  const analyze = page.getByRole("button", { name: "분석 시작" });
  await expect(workDescription).toHaveValue("");
  await expect(analyze).toBeDisabled();
  expect(conversationCreates).toHaveLength(0);
  expect(analysisBodies).toHaveLength(0);

  await workDescription.fill("   ");
  await expect(analyze).toBeDisabled();

  const userDescription = "매주 들어오는 고객 의견을 분류하고 요약하고 싶어요.";
  await workDescription.fill(userDescription);
  await expect(analyze).toBeEnabled();
  const analysisRequest = page.waitForRequest(request =>
    request.method() === "POST"
      && new URL(request.url()).pathname === "/api/builder/conversations/conversation-new-user/messages",
  );
  await analyze.click();
  await analysisRequest;
  expect(conversationCreates).toHaveLength(1);
  expect(analysisBodies).toEqual([{ content: userDescription }]);
});

test("예시를 명시적으로 선택한 뒤 보이는 설명을 한 번만 보낸다", async ({ page }) => {
  const { conversationCreates, analysisBodies } = observeBuilderPosts(page);
  await page.goto("/assemble/automation");

  const workDescription = page.getByLabel("업무 설명 또는 수정 요청");
  const analyze = page.getByRole("button", { name: "분석 시작" });
  await expect(workDescription).toHaveValue("");
  await expect(analyze).toBeDisabled();

  await page.getByRole("button", { name: "고객 문의 자동화 예시 선택" }).click();
  await expect(workDescription).toHaveValue(sampleRequest);
  await expect(analyze).toBeEnabled();
  expect(conversationCreates).toHaveLength(0);
  expect(analysisBodies).toHaveLength(0);

  await analyze.click();
  await expect.poll(() => analysisBodies.length).toBe(1);
  expect(conversationCreates).toHaveLength(1);
  expect(analysisBodies).toEqual([{ content: sampleRequest }]);
});

test("안전한 내부 next 경로를 가입과 회사 설정을 거쳐 보존한다", async ({ page }) => {
  await completeSignup(page, "/dashboard?source=welcome#start");
  expect(new URL(page.url()).searchParams.get("next")).toBe("/dashboard?source=welcome#start");
  await saveCompany(page);

  await expect(page).toHaveURL(/\/dashboard\?source=welcome#start$/);
  await expect(page.getByRole("heading", { name: "자동화하고 싶은 일을 평소 말로 설명해 주세요" })).toBeVisible();
  await expect(page.getByRole("link", { name: "내 업무 설명하기" })).toHaveAttribute("href", "/assemble/automation");
  await expect(page.getByRole("link", { name: "직원 뽑기" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "AI 회사 만들기" })).toHaveCount(0);
});

for (const unsafeNext of ["https://evil.example/steal", "//evil.example/steal", "/\\evil.example/steal"]) {
  test(`안전하지 않은 next 값을 자연어 자동화 경로로 대체한다: ${unsafeNext}`, async ({ page }) => {
    await completeSignup(page, unsafeNext);
    expect(new URL(page.url()).searchParams.has("next")).toBe(false);
    await saveCompany(page);

    await expect(page).toHaveURL(/\/assemble\/automation$/);
    await expect(page.getByLabel("업무 설명 또는 수정 요청")).toBeVisible();
  });
}
