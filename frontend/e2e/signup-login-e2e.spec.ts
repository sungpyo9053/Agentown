import { expect, test } from "@playwright/test";

test("email verification signup and a fresh login work through the real API", async ({ page }) => {
  test.setTimeout(120_000);
  const email = `signup_login_${Date.now()}@example.com`;
  const password = "Agentown!2026";

  await page.goto("/signup");
  await page.getByLabel("이름").fill("가입 로그인 검증");
  await page.getByPlaceholder("name@example.com").fill(email);
  await page.getByRole("button", { name: "중복 확인" }).click();
  await expect(page.getByText("사용 가능한 이메일입니다.")).toBeVisible();
  await page.getByRole("button", { name: "이메일 인증번호 발송" }).click();
  await expect(page.getByText(/개발 인증번호:/)).toBeVisible();
  await page.getByRole("button", { name: "인증 확인" }).click();
  await expect(page.getByText("이메일 인증이 완료되었습니다.")).toBeVisible();
  await page.getByLabel("비밀번호").fill(password);
  await page.getByRole("button", { name: "인증하고 가입하기" }).click();
  await expect(page).toHaveURL(/\/onboarding\/company$/);

  await page.goto("/dashboard");
  await page.getByRole("button", { name: "로그아웃" }).click();
  await expect(page).toHaveURL(/\/$/);
  await page.goto("/login");
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill(password);
  await page.getByRole("button", { name: "로그인", exact: true }).click();
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByText("가입 로그인 검증님, 안녕하세요")).toBeVisible();
});
