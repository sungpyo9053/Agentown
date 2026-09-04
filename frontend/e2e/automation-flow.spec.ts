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
  await expect(page.getByText("AGENTOWN · AGENT COMPILER")).toBeVisible();
  await page.getByLabel("업무 설명 또는 수정 요청").fill("저는 회사에서 고객 문의를 담당하고 있습니다. Slack의 #customer-support 채널에 문의가 올라오면, Notion의 고객 FAQ 데이터베이스에서 관련 내용을 찾아 답변 초안을 만들고 있습니다. 답변은 바로 보내지 말고 제가 검토하고 승인한 경우에만 해당 Slack 메시지의 스레드로 전송되게 자동화하고 싶습니다.");
  await page.getByRole("button", { name: "분석 시작" }).click();
  await expect(page.getByRole("heading", { name: "자동화 설계안" })).toBeVisible();
  const faqTeamCard = page.getByRole("article").filter({ hasText: "업무 자동화 팀 · AI 팀원 1명" });
  await expect(faqTeamCard).toBeVisible();
  await expect(faqTeamCard.getByText("고객 답변 작성자", { exact: true })).toBeVisible();
  await expect(page.getByText("Notion Mock", { exact: true })).toBeVisible();
  await expect(page.getByText("Slack 연결 설정")).toBeVisible();
  await expect(page.getByText("Notion FAQ 설정")).toBeVisible();
  await page.getByTestId("approve-design").click();

  await expect(page.getByTestId("builder-canvas")).toBeVisible();
  await expect(page.getByText("Slack 문의 수신 (Mock)")).toBeVisible();
  await expect(page.getByText("Notion FAQ 검색 (Mock)")).toBeVisible();
  await expect(page.getByText("답변 초안 작성")).toBeVisible();
  await expect(page.getByText("담당자 승인")).toBeVisible();
  await expect(page.getByText("Slack 스레드 답변 (Mock)")).toBeVisible();
  await page.getByRole("button", { name: "설계 · 대화" }).click();
  await page.getByLabel("업무 설명 또는 수정 요청").fill("답변을 바로 보내지 말고, 반드시 제가 내용을 확인하고 승인한 경우에만 전송하도록 바꿔주세요.");
  await page.getByRole("button", { name: "Graph Patch 요청" }).click();
  await expect(page.getByText("Workflow Version 2")).toBeVisible();

  await page.getByRole("button", { name: "샘플 테스트" }).click();
  await page.getByLabel("시뮬레이션 문의").fill("환불은 언제 처리되나요?");
  await page.getByTestId("start-simulation").click();
  await expect(page.getByText("담당자 승인 대기")).toBeVisible();
  await expect(page.getByText("notion.search.mock")).toBeVisible();
  await page.getByTestId("approve-execution").click();
  await expect(page.getByText("시뮬레이션 완료")).toBeVisible();
  await expect(page.getByText(/요구사항 일치: 통과/)).toBeVisible();
  await expect(page.getByText("slack.reply.mock")).toBeVisible();
  await expect(page.getByText(/"externalCallPerformed": false/).first()).toBeVisible();
  await page.getByTestId("activate-workflow").click();
  await expect(page.getByText("ACTIVE", { exact: true })).toBeVisible();
  await page.goto("/dashboard");
  await expect(page.getByRole("heading", { name: "Slack FAQ 답변 에이전트 팀" })).toBeVisible();
  await expect(page.getByText("실제 직원 1명")).toBeVisible();
  await expect(page.getByTitle("고객 답변 작성자")).toBeVisible();
  await page.screenshot({ path: "test-results/builder-mvp-complete.png", fullPage: true });

  await page.goto("/assemble/automation");
  await expect(page.getByText("ACTIVE", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "전체 캔버스" }).click();
  await expect(page.getByTestId("builder-canvas")).toBeVisible();
  await expect(page.getByText("Workflow Version 2")).toBeVisible();
  await expect(page.getByRole("link", { name: "Agent Package 다운로드" })).toBeVisible();
});

test("정보가 부족한 문의 자동화는 네 가지 필수 질문 후 설계를 멈춘다", async ({ page }) => {
  test.setTimeout(90_000);
  await signup(page, "builder_scope");
  await page.goto("/assemble/automation");
  await page.getByLabel("업무 설명 또는 수정 요청").fill("고객 문의 답변하는 일을 자동화하고 싶어요.");
  await page.getByRole("button", { name: "분석 시작" }).click();
  await expect(page.getByText("자동화할 업무는 어떤 입력이나 이벤트로 시작되며, 어느 서비스에서 들어오나요?", { exact: true }).last()).toBeVisible();
  await expect(page.getByText("결과를 만들 때 참고할 자료는 어느 서비스나 데이터베이스에 있나요?", { exact: true }).last()).toBeVisible();
  await expect(page.getByText("완성된 결과를 바로 실행할까요, 담당자 검토와 승인 후 실행할까요?", { exact: true }).last()).toBeVisible();
  await expect(page.getByText("완성된 결과는 어느 서비스의 어느 위치로 전달하거나 저장할까요?", { exact: true }).last()).toBeVisible();
  await expect(page.getByText("NEEDS_CLARIFICATION", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "전체 캔버스" })).toBeDisabled();
  await expect(page.getByText("발행 하네스 선택 필요")).toHaveCount(0);
});

test("반려한 설계를 자연어로 수정하고 같은 자동화에서 다시 검토한다", async ({ page }) => {
  test.setTimeout(120_000);
  await signup(page, "builder_revision");
  await page.goto("/assemble/automation");

  const originalInstruction = "사용자가 수동 입력한 텍스트를 카테고리로 분류하고 담당자 승인 후 결과를 화면에 표시합니다.";
  await page.getByLabel("업무 설명 또는 수정 요청").fill(originalInstruction);
  await page.getByRole("button", { name: "분석 시작" }).click();
  await expect(page.getByRole("heading", { name: "자동화 설계안" })).toBeVisible();
  await expect(page.getByText("분류 담당", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "수정 요청" }).click();
  await expect(page.getByText("설계가 반려되었습니다. 수정할 내용을 자연어로 알려 주세요.")).toBeVisible();
  await expect(page.getByText("DRAFT", { exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "자동화 설계안" })).toBeVisible();

  const revision = "분석 담당과 작성 담당이 함께 처리하도록 바꿔 주세요.";
  await page.getByLabel("업무 설명 또는 수정 요청").fill(revision);
  await page.getByRole("button", { name: "분석 시작" }).click();

  await expect(page.getByText("WAITING_DESIGN_APPROVAL", { exact: true })).toBeVisible();
  await expect(page.getByText("분석 담당", { exact: true })).toBeVisible();
  await expect(page.getByText("결과 작성 담당", { exact: true })).toBeVisible();
  await expect(page.getByTestId("builder-conversation")).toContainText(originalInstruction);
  await expect(page.getByTestId("builder-conversation")).toContainText(revision);
  await expect(page.getByText(/Workflow Version/)).toHaveCount(0);
  await expect(page.getByTestId("approve-design")).toBeVisible();
});

test("글쓰기 표준 하네스는 불필요한 역할 분리 없이 한 명의 작성자를 배치한다", async ({ page }) => {
  test.setTimeout(120_000);
  await signup(page, "writing_team");
  await page.goto("/assemble/automation");
  await page.getByLabel("업무 설명 또는 수정 요청").fill("글쓰기 자동화를 수동으로 시작하고 사용자가 제공한 주제와 원문만 사용해 일반 독자용 한국어 블로그 초안을 작성한다. 콘텐츠 담당자 승인 후 화면에 표시한다.");
  await page.getByRole("button", { name: "분석 시작" }).click();
  const writingTeamCard = page.getByRole("article").filter({ hasText: "업무 자동화 팀 · AI 팀원 1명" });
  await expect(writingTeamCard).toBeVisible();
  await expect(writingTeamCard.getByText("콘텐츠 작성자", { exact: true })).toBeVisible();
  await page.getByTestId("approve-design").click();
  await expect(page.getByRole("button", { name: "샘플 테스트" })).toBeEnabled();
  await page.reload();
  await expect(page.getByRole("button", { name: "샘플 테스트" })).toBeEnabled();
  await page.getByRole("button", { name: "샘플 테스트" }).click();
  await expect(page.getByLabel("시뮬레이션 문의")).toHaveValue("검증할 주제와 근거 원문을 입력하세요.");
  await expect(page.getByText("현재 Workflow Graph를 안전한 Mock 계약으로 실행합니다. 외부 저장·전송은 발생하지 않습니다.")).toBeVisible();
  await page.getByLabel("시뮬레이션 문의").fill("검증용 글 주제와 참고 원문");
  await page.getByTestId("start-simulation").click();
  await expect(page.getByText("담당자 승인 대기")).toBeVisible();
  await page.getByTestId("approve-execution").click();
  await expect(page.getByText("시뮬레이션 완료")).toBeVisible();
  await page.getByTestId("activate-workflow").click();
  await expect(page.getByText("ACTIVE", { exact: true })).toBeVisible();

  await page.goto("/dashboard");
  await expect(page.getByRole("heading", { name: "글쓰기 자동화 팀" })).toBeVisible();
  await expect(page.getByText("실제 직원 1명")).toBeVisible();
  await expect(page.getByTitle("콘텐츠 작성자")).toBeVisible();
});
