import { expect, test } from "@playwright/test";

const draft = {
  id:"11111111-1111-1111-1111-111111111111", brandName:"공간연구소", topic:"수원 32평 주방 리모델링",
  audience:"수원에서 구축 아파트 리모델링을 준비하는 가족", channel:"NAVER", photoReferenceUrl:"https://drive.google.com/example",
  photoNotes:"1. 시공 전 주방\n2. 철거 후 배관\n3. 완공 사진", title:"수원 32평 주방 리모델링 현장 기록",
  bodyMarkdown:"## 시공 전 확인한 문제\n\n수납과 동선을 확인했습니다.\n\n## 선택한 방법\n\n제공한 자재표 안에서 정리했습니다.\n\n## 완공 사진을 보는 순서\n\n사진 순서대로 변화를 확인할 수 있습니다.",
  seoTitle:"수원 32평 주방 리모델링", metaDescription:"확인 가능한 현장 기록입니다.", targetKeywords:["수원 주방 리모델링"],
  evidenceUsed:["현장 메모","자재표"], warnings:[], generationSource:"AGENTOWN_AI", provider:"OPENAI", model:"gpt-5.6-luna",
  inputTokens:0, outputTokens:0, qualityScore:90,
  qualityChecks:[{key:"evidence",label:"근거 연결",passed:true,score:25,detail:"근거 메모와 사용 근거 목록"}],
  status:"DRAFT", createdAt:"2026-08-30T00:00:00Z", updatedAt:"2026-08-30T00:00:00Z",
};

test("콘텐츠 운영 탭에서 초안을 편집 승인하고 네이버 붙여넣기용으로 복사한다", async ({ page, context }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"], { origin:"http://127.0.0.1:3100" });
  let generateBody:Record<string,unknown>|null=null;
  let current:Record<string,unknown>={...draft};
  await page.route("**/api/**", async route => {
    const request=route.request(); const path=new URL(request.url()).pathname;
    const json=(body:unknown)=>route.fulfill({status:200,contentType:"application/json",body:JSON.stringify(body)});
    if(path==="/api/auth/me")return json({role:"USER",email:"content@reviewdr.kr",displayName:"콘텐츠 운영자"});
    if(path==="/api/mini-homes/me")return json({title:"공간연구소"});
    if(path==="/api/content-operations/usage")return json({used:3,limit:30,remaining:27});
    if(path==="/api/content-operations/drafts"&&request.method()==="GET")return json([]);
    if(path==="/api/llm-credentials")return json([]);
    if(path==="/api/llm-models")return json([]);
    if(path==="/api/content-operations/drafts/generate") { generateBody=request.postDataJSON(); current={...draft}; return json(current); }
    if(path.endsWith("/approve")) { current={...current,status:"APPROVED",approvedAt:"2026-08-30T01:00:00Z"}; return json(current); }
    if(path===`/api/content-operations/drafts/${draft.id}`&&request.method()==="PATCH") { current={...current,...request.postDataJSON()}; return json(current); }
    if(path==="/api/auth/csrf")return json({token:"test",headerName:"X-XSRF-TOKEN"});
    return route.fulfill({status:404,contentType:"application/json",body:"{}"});
  });

  await page.goto("/content");
  await expect(page.getByRole("heading",{name:"콘텐츠 운영"})).toBeVisible();
  await expect(page.getByLabel("콘텐츠 운영").first()).toBeVisible();
  await page.getByLabel("업체·블로그 이름").fill("공간연구소");
  await page.getByLabel("글 주제").fill("수원 32평 주방 리모델링");
  await page.getByLabel("주요 독자").fill("수원에서 구축 아파트 리모델링을 준비하는 가족");
  await page.getByLabel("실제 현장 메모").fill("수납 공간이 부족하고 조리대 동선이 겹쳤다. 기존 배관 위치를 유지하면서 고객이 제공한 자재표 안에서 변경 범위를 확인했다.");
  await page.getByLabel("가격·자재·일정 근거").fill("고객 제공 자재표와 현장 실측 메모");
  await page.getByLabel("구글 드라이브 사진 폴더").fill("https://drive.google.com/example");
  await page.getByLabel("사진 순서와 설명").fill("1. 시공 전 주방 2. 철거 후 배관 3. 완공 사진");
  await page.getByLabel("기존 블로그 말투").fill("과장 없이 쉽게 설명한다.");
  await page.getByRole("button",{name:"콘텐츠 초안 만들기"}).click();
  await expect(page.getByRole("heading",{name:"초안 편집"})).toBeVisible();
  expect(generateBody).toMatchObject({channel:"NAVER",usePersonalAi:false,credentialId:null,model:"gpt-5.6-luna"});

  await page.getByRole("checkbox",{name:"가격·자재·기간과 현장 설명을 확인했습니다."}).check();
  await page.getByRole("checkbox",{name:"사진 사용 권한과 공개 범위를 확인했습니다."}).check();
  await page.getByRole("button",{name:"발행 준비 승인"}).click();
  await expect(page.getByRole("heading",{name:"네이버 블로그에 바로 붙여넣기"})).toBeVisible();
  await page.getByRole("button",{name:"1. 제목 복사"}).click();
  await expect.poll(()=>page.evaluate(()=>navigator.clipboard.readText())).toBe(draft.title);
  await page.getByRole("button",{name:"2. 본문 서식 복사"}).click();
  await expect(page.getByRole("status")).toContainText("본문");
  await expect(page.getByRole("link",{name:"3. 네이버 블로그 열기"})).toHaveAttribute("href","https://blog.naver.com/");
});
