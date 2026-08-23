import { expect, test } from "@playwright/test";

test("발행한 내 하네스를 캔버스에서 연결하고 초안을 복원한다", async ({ page }) => {
  test.setTimeout(120_000);
  await page.setViewportSize({ width: 1800, height: 1100 });
  const suffix = `${Date.now()}`.slice(-10);
  const email = `automation_${suffix}@example.com`;
  const harnessName = `자동화 검증 회사 ${suffix}`;

  await page.goto("/signup");
  await page.getByLabel("이름").fill("자동화 검증 사용자");
  await page.getByPlaceholder("name@example.com").fill(email);
  await Promise.all([
    page.waitForResponse((response) => response.url().includes("/api/auth/availability") && response.ok()),
    page.getByRole("button", { name: "중복 확인" }).click(),
  ]);
  await page.getByRole("button", { name: "이메일 인증번호 발송" }).click();
  await expect(page.getByText(/개발 인증번호:/)).toBeVisible();
  await page.getByRole("button", { name: "인증 확인" }).click();
  await page.getByLabel("비밀번호").fill("Agentown!2026");
  await page.getByRole("button", { name: "인증하고 가입하기" }).click();
  await expect(page).toHaveURL(/\/onboarding\/company$/);

  await page.goto("/settings/credentials");
  await expect(page.getByText("처음 실행할 때 macOS 보안 안내")).toBeVisible();
  await expect(page.getByText(/control-클릭 → 열기 → 열기/)).toBeVisible();

  await page.goto("/harnesses/new");
  await page.getByLabel("회사 이름은 무엇인가요?").fill(harnessName);
  await page.getByLabel("이 회사가 해결할 문제는 무엇인가요?").fill("제품 업데이트를 정리하고 Notion에 전달한다.");
  await page.getByLabel("사용자가 무엇을 입력하나요?").fill("제품 업데이트 원문");
  await page.getByLabel("최종 결과물은 무엇인가요?").fill("검증된 제품 업데이트 요약");
  await page.getByLabel("결과 파일 형식").selectOption("MARKDOWN");
  await page.getByLabel("반드시 확인할 근거는 무엇인가요?").fill("입력 원문과 공식 변경 기록");
  await page.getByLabel("절대 하면 안 되는 일은 무엇인가요?").fill("없는 변경 내용을 만들지 않는다.");
  await page.getByLabel("언제 사람의 승인을 받아야 하나요?").fill("외부 서비스 전달 전");
  const model = page.locator('select[name="model"]');
  await expect(model.locator("option")).not.toHaveCount(1);
  await model.selectOption({ index: 1 });
  await page.getByRole("button", { name: "AI 회사 전체 초안 만들기" }).click();
  await page.getByRole("button", { name: "이 조직을 승인하고 내 회사에 저장" }).click();
  await expect(page).toHaveURL(/\/harnesses\/[^/]+\/edit$/);
  await page.getByRole("button", { name: "1. 연결 구조 생성" }).click();
  await expect(page.getByText("연결 구조를 생성했습니다.")).toBeVisible();
  await page.getByRole("button", { name: "2. 목표 검증" }).click();
  await expect(page.getByText(/검증 통과/)).toBeVisible();
  await page.getByRole("button", { name: "3. 버전 발행" }).click();
  await expect(page.getByText("불변 버전을 발행했습니다.")).toBeVisible();

  await page.goto("/assemble/automation");
  await expect(page.getByRole("heading", { name: "업무 자동화" })).toBeVisible();
  await page.getByLabel("자동화 요청").fill("슬랙에 제품 업데이트가 오면 답변을 만들고 Notion에 기록해줘");
  await page.getByRole("button", { name: "분석 요청" }).click();
  const analysis = page.getByTestId("automation-analysis");
  await expect(analysis).toContainText("업무 의도 분석가");
  await expect(analysis).toContainText("보유 하네스 분석가");
  await expect(analysis).toContainText("워크플로우 설계가");
  await expect(analysis).toContainText("연결·권한 검수자");
  await expect(analysis).toContainText(harnessName);
  await page.getByRole("button", { name: "캔버스에 적용" }).click();
  await expect(page.getByTestId("node-slack_trigger")).toBeVisible();
  await expect(page.getByTestId("node-harness")).toContainText(harnessName);
  await expect(page.getByTestId("node-notion_action")).toBeVisible();
  await expect(page.getByTestId("node-slack_action")).toBeVisible();
  await expect(page.getByRole("button", { name: /연결 선택/ })).toHaveCount(3);
  await page.getByTestId("node-notion_action").click();
  await page.getByLabel("Notion 데이터베이스").fill("콘텐츠 운영 DB");

  const harnessNode = page.getByTestId("node-harness");
  const before = await harnessNode.boundingBox();
  await page.getByLabel("내 하네스 실행 노드 이동").dragTo(page.getByLabel("업무 자동화 캔버스"), { targetPosition: { x: 460, y: 300 } });
  const after = await harnessNode.boundingBox();
  expect(before && after && Math.abs(after.x - before.x) > 40).toBeTruthy();

  await page.getByRole("button", { name: "연결 검증" }).click();
  await expect(page.getByRole("status")).toContainText("검증 통과");
  await page.screenshot({ path: "test-results/automation-canvas-validated.png", fullPage: true });
  await page.getByRole("button", { name: "초안 저장" }).click();
  await expect(page.getByRole("status")).toContainText("이 브라우저에 자동화 초안을 저장했습니다.");

  await page.reload();
  await expect(page.getByTestId("node-slack_trigger")).toBeVisible();
  await expect(page.getByTestId("node-harness")).toContainText(harnessName);
  await expect(page.getByTestId("node-notion_action")).toContainText("콘텐츠 운영 DB");
  await expect(page.getByTestId("node-slack_action")).toBeVisible();
});
