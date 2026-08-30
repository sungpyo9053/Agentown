import { expect, test } from "@playwright/test";

test("email verification rate limit shows the exact retry countdown", async ({ page, context }) => {
  await context.addCookies([{ name: "XSRF-TOKEN", value: "test-csrf", domain: "127.0.0.1", path: "/" }]);
  await page.route("**/api/auth/availability?email=signup%40example.com", route => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ emailAvailable: true }),
  }));
  await page.route("**/api/auth/email/send-code", route => route.fulfill({
    status: 429,
    headers: { "Retry-After": "125" },
    contentType: "application/json",
    body: JSON.stringify({ code: "RATE_LIMITED", message: "요청이 너무 많습니다." }),
  }));

  await page.goto("/signup");
  await page.getByLabel("이메일").fill("signup@example.com");
  await page.getByRole("button", { name: "중복 확인" }).click();
  await expect(page.getByText("사용 가능한 이메일입니다.")).toBeVisible();
  await page.getByRole("button", { name: "이메일 인증번호 발송" }).click();

  await expect(page.getByText("인증번호 요청 한도를 초과했습니다. 2분 5초 후 다시 시도해 주세요.")).toBeVisible();
  await expect(page.getByRole("button", { name: /후 재발송/ })).toBeDisabled();
});
