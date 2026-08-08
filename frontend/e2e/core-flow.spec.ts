import { expect, test } from "@playwright/test";

test("가입부터 AI 회사 설계, 발행, 실행 결과 다운로드까지 동작한다", async ({ page }) => {
  const suffix = `${Date.now()}`.slice(-10);
  const handle = `e2e_${suffix}`;
  const phone = `010${suffix.slice(-8)}`;

  await page.goto("/signup");
  await page.getByLabel("이름").fill("브라우저 검증 사용자");
  await page.getByLabel("아이디").fill(handle);
  await Promise.all([
    page.waitForResponse((response) => response.url().includes("/api/auth/availability") && response.ok()),
    page.getByRole("button", { name: "중복 확인" }).click(),
  ]);
  await expect(page.getByText("사용 가능한 아이디입니다.")).toBeVisible({ timeout: 15_000 });
  await page.getByLabel("비밀번호").fill("Agentown!2026");
  await page.getByLabel("휴대폰 번호").fill(phone);
  await page.getByRole("button", { name: "인증번호 발송" }).click();
  await expect(page.getByText(/로컬 인증번호:/)).toBeVisible();
  await page.getByRole("button", { name: "인증 확인" }).click();
  await expect(page.getByText("휴대폰 인증이 완료되었습니다.")).toBeVisible();
  await page.getByRole("button", { name: "인증하고 가입하기" }).click();
  await expect(page).toHaveURL(/\/dashboard$/);

  await page.goto("/harnesses/new");
  await page.getByLabel("회사 이름은 무엇인가요?").fill("E2E 블로그 회사");
  await page.getByLabel("이 회사가 해결할 문제는 무엇인가요?").fill("검증 가능한 기술 블로그 글을 작성하고 검수한다.");
  await page.getByLabel("사용자가 무엇을 입력하나요?").fill("글 주제와 독자");
  await page.getByLabel("최종 결과물은 무엇인가요?").fill("근거가 표시된 Markdown 기술 글");
  await page.getByLabel("결과 파일 형식").selectOption("HTML");
  await page.getByLabel("반드시 확인할 근거는 무엇인가요?").fill("공식 문서와 실행 결과");
  await page.getByLabel("절대 하면 안 되는 일은 무엇인가요?").fill("검증하지 않은 사실을 단정하지 않는다.");
  await page.getByLabel("언제 사람의 승인을 받아야 하나요?").fill("최종 발행 직전");
  const model = page.locator('select[name="model"]');
  await expect(model.locator("option")).not.toHaveCount(1);
  await model.selectOption({ index: 1 });
  await page.getByRole("button", { name: "AI 회사 전체 초안 만들기" }).click();
  await expect(page.getByRole("button", { name: "이 조직을 승인하고 내 회사에 저장" })).toBeVisible();
  await page.getByRole("button", { name: "이 조직을 승인하고 내 회사에 저장" }).click();
  await expect(page).toHaveURL(/\/harnesses\/[^/]+\/edit$/);

  await page.getByRole("button", { name: "2. 하네스 검증" }).click();
  await expect(page.getByText(/검증 통과/)).toBeVisible();
  await page.getByRole("button", { name: "3. 버전 발행" }).click();
  await expect(page.getByText("불변 버전을 발행했습니다.")).toBeVisible();
  await page.getByRole("button", { name: "Stub으로 실행 검증" }).click();
  await expect(page).toHaveURL(/\/executions\/[^/]+$/);

  await expect.poll(async () => page.locator("body").innerText(), { timeout: 30_000 })
    .toMatch(/WAITING_APPROVAL|SUCCEEDED/);
  const approve = page.getByRole("button", { name: "승인", exact: true });
  if (await approve.isVisible()) await approve.click();
  await expect.poll(async () => page.locator("body").innerText(), { timeout: 30_000 })
    .toContain("SUCCEEDED");
  await expect(page.getByRole("heading", { name: "내 실행 결과물" })).toBeVisible();
  const htmlDownload = page.getByRole("link", { name: "최종 HTML (.html) 다운로드" });
  await expect(htmlDownload).toBeVisible();
  await expect(htmlDownload).toHaveAttribute("href", /format=html/);
  const downloadPromise = page.waitForEvent("download");
  await htmlDownload.click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toMatch(/\.html$/);
  const stream = await download.createReadStream();
  let html = "";
  for await (const chunk of stream) html += chunk.toString();
  expect(html).toContain("<!doctype html>");
  expect(html).toContain("Agentown 실행 테스트");
});
