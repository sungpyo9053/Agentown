import { expect, test } from "@playwright/test";

test("Agentown 제공 Codex 경로는 개인 모델이나 API 키 없이 회사를 만든다", async ({ page }) => {
  let designBody: Record<string, unknown> | null = null;
  await page.route("**/api/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
    if (path === "/api/auth/me") return json({ role: "USER", email: "ops-test@reviewdr.kr", displayName: "운영 테스트" });
    if (path === "/api/mini-homes/me") return json({ title: "운영 테스트 회사" });
    if (path === "/api/llm-models") return json([]);
    if (path === "/api/llm-credentials") return json([]);
    if (path === "/api/designer/companies/design") {
      designBody = request.postDataJSON();
      return json({ valid: true, errors: [], draft: {
        companyName: "글쓰기 회사", goal: "글을 작성한다", designSource: "AGENTOWN_AI", approvalAfterLast: true,
        resultAgentKey: "writer", outputFormat: "MARKDOWN",
        agents: [{ key: "writer", name: "작가", role: "작가", responsibility: "작성", taskDescription: "글 작성", desiredOutput: "글", requiredEvidence: "입력", guide: "근거 표시", prohibitions: "추측 금지", rewriteCriteria: "근거 누락", approvalCriteria: "검수 완료", characterKey: "writer", provider: "OPENAI", recommendedModel: "gpt-5.6-luna" }],
        steps: [{ key: "step-1", agentKey: "writer", sequence: 1, maxRetries: 1 }],
      }});
    }
    if (path === "/api/designer/companies/apply") return json({ harnessId: "11111111-1111-1111-1111-111111111111" });
    if (path === "/api/auth/csrf") return json({ token: "test", headerName: "X-XSRF-TOKEN" });
    return route.fulfill({ status: 404, contentType: "application/json", body: "{}" });
  });

  await page.goto("/harnesses/new");
  await expect(page.getByText("Agentown 제공 Codex")).toBeVisible();
  await page.getByLabel("회사 이름은 무엇인가요?").fill("글쓰기 회사");
  await page.getByLabel("이 회사가 해결할 문제는 무엇인가요?").fill("글을 작성한다");
  await page.getByLabel("사용자가 무엇을 입력하나요?").fill("주제");
  await page.getByLabel("최종 결과물은 무엇인가요?").fill("Markdown 글");
  await page.getByRole("button", { name: "Agentown으로 회사 초안 만들기" }).click();
  await expect(page.getByRole("button", { name: "이 조직을 승인하고 내 회사에 저장" })).toBeVisible();
  expect(designBody).toMatchObject({ model: "gpt-5.6-luna", credentialId: null, stubMode: true });
  await page.getByRole("button", { name: "이 조직을 승인하고 내 회사에 저장" }).click();
  await expect(page).toHaveURL(/\/harnesses\/11111111-1111-1111-1111-111111111111\/edit$/);
});
