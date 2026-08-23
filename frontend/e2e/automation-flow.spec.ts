import { expect, test } from "@playwright/test";

async function signup(page: import("@playwright/test").Page, prefix: string) {
  const suffix = `${Date.now()}-${Math.floor(Math.random() * 10000)}`;
  const email = `${prefix}_${suffix}@example.com`;
  await page.goto("/signup");
  await page.getByLabel("이름").fill("Builder E2E 사용자");
  await page.getByPlaceholder("name@example.com").fill(email);
  await Promise.all([
    page.waitForResponse((response) => response.url().includes("/api/auth/availability") && response.ok()),
    page.getByRole("button", { name: "중복 확인" }).click(),
  ]);
  await page.getByRole("button", { name: "이메일 인증번호 발송" }).click();
  await expect(page.getByText(/개발 인증번호:/)).toBeVisible();
  await page.getByRole("button", { name: "인증 확인" }).click();
  await expect(page.getByText("이메일 인증이 완료되었습니다.")).toBeVisible();
  await page.getByLabel("비밀번호").fill("Agentown!2026");
  await page.getByRole("button", { name: "인증하고 가입하기" }).click();
  await expect(page).toHaveURL(/\/onboarding\/company$/);
}

test("Builder 자연어 설계부터 캔버스와 승인 중단 재개까지 실제 API DB 경로가 동작한다", async ({ page }) => {
  test.setTimeout(120_000);
  await page.setViewportSize({ width: 1680, height: 1050 });
  await signup(page, "builder_e2e");
  await page.goto("/assemble/automation");
  await expect(page.getByText("BUILDER MVP · MOCK CONNECTORS")).toBeVisible();
  await page.getByLabel("업무 설명 또는 수정 요청").fill("저는 회사에서 고객 문의를 담당하고 있습니다. Slack의 #customer-support 채널에 문의가 올라오면, Notion의 고객 FAQ 데이터베이스에서 관련 내용을 찾아 답변 초안을 만들고 있습니다. 답변은 바로 보내지 말고 제가 검토하고 승인한 경우에만 해당 Slack 메시지의 스레드로 전송되게 자동화하고 싶습니다.");
  await page.getByRole("button", { name: "분석 시작" }).click();
  await expect(page.getByRole("heading", { name: "자동화 설계안" })).toBeVisible();
  await expect(page.getByText("FAQ 답변 작성자")).toBeVisible();
  await expect(page.getByText("FAQ 검색 담당")).toBeVisible();
  await expect(page.getByText("Slack 연결 설정")).toBeVisible();
  await expect(page.getByText("Notion FAQ 연결 설정")).toBeVisible();
  await page.getByTestId("approve-design").click();

  await expect(page.getByTestId("builder-canvas")).toBeVisible();
  await expect(page.getByText("Slack 문의 수신 (Mock)")).toBeVisible();
  await expect(page.getByText("Notion FAQ 검색 (Mock)")).toBeVisible();
  await expect(page.getByText("AI 답변 초안")).toBeVisible();
  await expect(page.getByText("담당자 승인")).toBeVisible();
  await expect(page.getByText("Slack 스레드 답변 (Mock)")).toBeVisible();
  await page.getByRole("button", { name: "설계 · 대화" }).click();
  await page.getByLabel("업무 설명 또는 수정 요청").fill("답변을 바로 보내지 말고, 반드시 제가 내용을 확인하고 승인한 경우에만 전송하도록 바꿔주세요.");
  await page.getByRole("button", { name: "Graph Patch 요청" }).click();
  await expect(page.getByText("Workflow Version 2")).toBeVisible();

  await page.getByRole("button", { name: "시뮬레이션" }).click();
  await page.getByLabel("시뮬레이션 문의").fill("환불은 언제 처리되나요?");
  await page.getByTestId("start-simulation").click();
  await expect(page.getByText("담당자 승인 대기")).toBeVisible();
  await expect(page.getByText("notion.search.mock")).toBeVisible();
  await page.getByTestId("approve-execution").click();
  await expect(page.getByText("시뮬레이션 완료")).toBeVisible();
  await expect(page.getByText(/요구사항 일치: 통과/)).toBeVisible();
  await expect(page.getByText("slack.reply.mock")).toBeVisible();
  await expect(page.getByText(/"externalCallPerformed": false/)).toBeVisible();
  await page.screenshot({ path: "test-results/builder-mvp-complete.png", fullPage: true });

  await page.reload();
  await expect(page.getByText("READY_TO_ACTIVATE")).toBeVisible();
  await page.getByRole("button", { name: "전체 캔버스" }).click();
  await expect(page.getByTestId("builder-canvas")).toBeVisible();
  await expect(page.getByText("Workflow Version 2")).toBeVisible();
});

test("정보가 부족한 문의 자동화는 네 가지 필수 질문 후 설계를 멈춘다", async ({ page }) => {
  test.setTimeout(90_000);
  await signup(page, "builder_scope");
  await page.goto("/assemble/automation");
  await page.getByLabel("업무 설명 또는 수정 요청").fill("고객 문의 답변하는 일을 자동화하고 싶어요.");
  await page.getByRole("button", { name: "분석 시작" }).click();
  await expect(page.getByText("고객 문의는 어느 서비스와 채널로 들어오나요?", { exact: true }).last()).toBeVisible();
  await expect(page.getByText("답변에 사용할 자료는 어느 서비스와 데이터베이스에서 찾나요?", { exact: true }).last()).toBeVisible();
  await expect(page.getByText("답변을 바로 보낼까요, 담당자 승인 후 보낼까요?", { exact: true }).last()).toBeVisible();
  await expect(page.getByText("완성된 답변은 어느 서비스와 대화 위치로 전송할까요?", { exact: true }).last()).toBeVisible();
  await expect(page.getByText("NEEDS_CLARIFICATION")).toBeVisible();
  await expect(page.getByRole("button", { name: "전체 캔버스" })).toBeDisabled();
  await expect(page.getByText("발행 하네스 선택 필요")).toHaveCount(0);
});
