import { expect, test } from "@playwright/test";

test("가입부터 AI 회사 설계, 발행, 실행 결과 다운로드까지 동작한다", async ({ page }) => {
  test.setTimeout(120_000);
  const suffix = `${Date.now()}`.slice(-10);
  const email = `e2e_${suffix}@example.com`;

  await page.goto("/signup");
  await page.getByLabel("이름").fill("브라우저 검증 사용자");
  await page.getByPlaceholder("name@example.com").fill(email);
  await Promise.all([
    page.waitForResponse((response) => response.url().includes("/api/auth/availability") && response.ok()),
    page.getByRole("button", { name: "중복 확인" }).click(),
  ]);
  await expect(page.getByText("사용 가능한 이메일입니다.")).toBeVisible({ timeout: 15_000 });
  await page.getByRole("button", { name: "이메일 인증번호 발송" }).click();
  await expect(page.getByText(/개발 인증번호:/)).toBeVisible();
  await page.getByRole("button", { name: "인증 확인" }).click();
  await expect(page.getByText("이메일 인증이 완료되었습니다.")).toBeVisible();
  await page.getByLabel("비밀번호").fill("Agentown!2026");
  await page.getByRole("button", { name: "인증하고 가입하기" }).click();
  await expect(page).toHaveURL(/\/onboarding\/company$/);

  await page.goto("/settings/credentials");
  await expect(page.getByText("ChatGPT Pro · Claude Pro를 그대로 사용하세요")).toBeVisible();
  await page.getByRole("button", { name: "Runner 연결 토큰 만들기" }).click();
  await expect(page.getByText("한 번만 표시되는 Runner 토큰")).toBeVisible();
  await expect(page.getByText("Agentown Runner 연결.command")).toBeVisible();
  const runnerToken = await page.locator("code").textContent();
  expect(runnerToken).toBeTruthy();
  const runnerHeaders = { "X-Runner-Token": runnerToken! };
  const heartbeat = await page.request.post("http://127.0.0.1:8080/api/runner/heartbeat", { headers: runnerHeaders });
  expect(heartbeat.ok()).toBeTruthy();
  await page.getByText("고급 옵션: API 키로 서버에서 바로 실행").click();
  await page.getByLabel("API 공급자").selectOption("ANTHROPIC");
  await expect(page.getByPlaceholder(/sk-ant-api03-/)).toBeVisible();

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

  await page.getByRole("button", { name: "1. 연결 구조 생성" }).click();
  await expect(page.getByText("연결 구조를 생성했습니다.")).toBeVisible();
  await page.getByRole("button", { name: "2. 목표 검증" }).click();
  await expect(page.getByText(/검증 통과/)).toBeVisible();
  await page.getByRole("button", { name: "3. 버전 발행" }).click();
  await expect(page.getByText("불변 버전을 발행했습니다.")).toBeVisible();
  const harnessEditUrl = page.url();

  const freshHeartbeat = await page.request.post("http://127.0.0.1:8080/api/runner/heartbeat", { headers: runnerHeaders });
  expect(freshHeartbeat.ok()).toBeTruthy();
  await page.goto("/dashboard");
  await expect(page.getByRole("heading", { name: "AI 직원에게 실제 업무 지시" })).toBeVisible();
  await expect(page.getByText(/연결됨/)).toBeVisible({ timeout: 15_000 });
  await page.getByLabel("원하는 최종 결과").fill("직원별 검증 결과가 포함된 Markdown");
  await page.getByLabel("실제 업무 지시").fill("실제 직원 실행 회귀 테스트 결과를 작성해 줘.");
  await page.getByLabel("참고 정보와 제약사항").fill("검증 fixture임을 명시한다.");
  await page.getByRole("button", { name: "실제 AI 직원 업무 시작" }).click();
  await expect(page).toHaveURL(/\/executions\/[^/]+$/);

  const claim = await page.request.post("http://127.0.0.1:8080/api/runner/jobs/claim", { headers: runnerHeaders });
  expect(claim.ok()).toBeTruthy();
  const job = await claim.json() as { executionId: string; agents: Array<{ stepKey: string; agentId: string; name: string }> };
  const realOutput: Record<string, unknown> = {};
  for (let index = 0; index < job.agents.length; index += 1) {
    const agent = job.agents[index];
    const stage = { result: `실제 Runner fixture ${index + 1}: ${agent.name} 작업 완료` };
    for (const eventType of ["STEP_STARTED", "MODEL_REQUEST_SENT", "STEP_OUTPUT_CREATED", "STEP_COMPLETED"]) {
      const progress = await page.request.post(`http://127.0.0.1:8080/api/runner/jobs/${job.executionId}/events`, {
        headers: runnerHeaders,
        data: { eventType, agentId: agent.agentId, stepKey: agent.stepKey, output: eventType === "STEP_OUTPUT_CREATED" ? stage : undefined },
      });
      expect(progress.ok()).toBeTruthy();
    }
    realOutput[agent.stepKey] = stage;
    realOutput.result = stage.result;
  }
  const complete = await page.request.post(`http://127.0.0.1:8080/api/runner/jobs/${job.executionId}/complete`, {
    headers: runnerHeaders,
    data: { output: realOutput },
  });
  expect(complete.ok()).toBeTruthy();
  await expect.poll(async () => page.locator("body").innerText(), { timeout: 30_000 }).toContain("WAITING_APPROVAL");
  await expect(page.getByRole("heading", { name: "직원별 실제 작업 결과" })).toBeVisible();
  await expect(page.getByText(`실제 Runner fixture 1: ${job.agents[0].name} 작업 완료`, { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "승인", exact: true }).click();
  await expect.poll(async () => page.locator("body").innerText(), { timeout: 30_000 }).toContain("SUCCEEDED");
  const realDownloadPromise = page.waitForEvent("download");
  await page.getByRole("link", { name: "최종 HTML (.html) 다운로드" }).click();
  const realDownload = await realDownloadPromise;
  const realStream = await realDownload.createReadStream();
  let realHtml = "";
  for await (const chunk of realStream) realHtml += chunk.toString();
  expect(realHtml).toContain("실제 Runner fixture");

  await page.goto(harnessEditUrl);
  await page.getByRole("button", { name: "비용 없이 Stub 검증" }).click();
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
