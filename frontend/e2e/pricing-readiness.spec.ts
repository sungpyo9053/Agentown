import { expect, test } from "@playwright/test";

test.beforeEach(async ({ page }) => {
  await page.route("**/api/auth/me", route => route.fulfill({
    status: 401,
    contentType: "application/json",
    body: JSON.stringify({ message: "Unauthenticated" }),
  }));
  await page.route("**/api/agent-development/events", route => route.fulfill({ status: 202, body: "" }));
});

test("가격 페이지가 무료 범위와 아직 열리지 않은 결제를 정직하게 구분한다", async ({ page }) => {
  await page.goto("/pricing");

  await expect(page.getByRole("heading", { name: "먼저 무료로, 쓸모를 확인하세요" })).toBeVisible();
  await expect(page.getByText("새 에이전트 1개 설계")).toBeVisible();
  await expect(page.getByText("설계 수정 2회")).toBeVisible();
  await expect(page.getByRole("link", { name: "무료로 시작하기" })).toHaveAttribute("href", "/signup");
  await expect(page.getByText("아직 결제하지 않습니다")).toBeVisible();
  await expect(page.getByRole("button", { name: /구독|결제/ })).toHaveCount(0);
});

test("모바일에서도 무료 시작과 유료 준비 상태가 먼저 보인다", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/pricing");

  await expect(page.getByText("FREE BETA")).toBeVisible();
  await expect(page.getByRole("link", { name: "무료로 시작하기" })).toBeVisible();
  await expect(page.getByText("PRO · 준비 중")).toBeVisible();
});

test("구독 화면은 서버 사용량을 표시하고 결제를 성공처럼 꾸미지 않는다", async ({ page }) => {
  await page.route("**/api/agent-development/usage", route => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ plan: "FREE_BETA", designLimit: 1, designUsed: 1, designRemaining: 0, revisionLimitPerAgent: 2, unlimited: false, checkoutAvailable: false }),
  }));
  await page.goto("/settings/subscription");

  await expect(page.getByText("새 설계 1/1")).toBeVisible();
  await expect(page.getByText("새 설계 0회 남음")).toBeVisible();
  await expect(page.getByText("결제되지 않는 버튼을 구독 완료처럼 표시하지 않습니다.")).toBeVisible();
  await expect(page.getByRole("button", { name: /구독|결제/ })).toHaveCount(0);
});
