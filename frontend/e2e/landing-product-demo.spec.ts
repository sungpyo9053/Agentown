import { expect, test } from "@playwright/test";

test.beforeEach(async ({ page }) => {
  await page.route("**/api/auth/me", route => route.fulfill({
    status: 401,
    contentType: "application/json",
    body: JSON.stringify({ message: "Unauthenticated" }),
  }));
});

test("메인에서 자연어 에이전트의 설계와 실행 흐름을 보여준다", async ({ page }) => {
  await page.goto("/");

  await expect(page.getByRole("heading", { name: "말 한 줄이 실행 가능한 AI 팀이 됩니다." })).toBeVisible();
  await expect(page.getByText("FAQ 기반 고객 답변")).toBeVisible();
  await expect(page.getByRole("link", { name: "무료로 시작하기" }).first()).toHaveAttribute("href", "/signup");
  await expect(page.getByRole("link", { name: "작동 방식 보기" })).toHaveAttribute("href", "#product-demo");

  await expect(page.getByRole("tab", { name: "FAQ 답변" })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByText("근거가 부족해 담당자 확인으로 분기했습니다")).toBeVisible();

  await page.getByRole("tab", { name: "CSV 비교" }).click();
  await expect(page.getByText("변경된 행 3건을 찾았습니다")).toBeVisible();
  await expect(page.getByText("data.csv.compare")).toBeVisible();

  await page.getByRole("tab", { name: "Notion 등록" }).click();
  await expect(page.getByText("승인을 기다리고 있습니다")).toBeVisible();
  await expect(page.getByText("WAITING_APPROVAL", { exact: true })).toBeVisible();
  await expect(page.getByText('"externalCallPerformed": false')).toBeVisible();
});

test("모바일에서도 핵심 설명과 CTA가 먼저 보인다", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");

  await expect(page.getByRole("heading", { name: "말 한 줄이 실행 가능한 AI 팀이 됩니다." })).toBeVisible();
  await expect(page.getByRole("link", { name: "무료로 시작하기" }).first()).toBeVisible();
  await expect(page.getByText("그래프 자동 설계")).toBeVisible();
  await expect(page.getByText("에이전트 개발 · Version 1")).toBeVisible();
});
