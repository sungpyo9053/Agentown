"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { CharacterPicker } from "@/components/AgentCharacter";
import { OfficeRoom } from "@/components/OfficeRoom";
import { AppShell } from "@/components/AppShell";

type RoomItem = { agentId?: string; itemType: "AGENT" | "ASSET"; positionX: number; positionY: number; width: number; height: number; zIndex: number; rotation: number };
type Home = { id: string; handle: string; title: string; introduction?: string; backgroundKey?: string; visitCount: number; items: RoomItem[] };
type Agent = { id: string; name: string; role: string; department?: string; characterKey: string; modelProvider: string; modelName: string };
type Credential = { id: string; provider: string; maskedSecret: string; status: string; lastVerifiedAt?: string };
type Provider = "OPENAI" | "ANTHROPIC" | "GOOGLE";
type ModelOption = { id: string; displayName: string };
type Execution = { id:string; status:string; currentStepKey?:string; createdAt:string };
type Harness = { id: string; name: string; description?: string; status: string; visibility: string };

// 실제 일정 데이터 없이, 목표별로 항상 같은 값을 보여주는 장식적 진행률 (id 해시 기반)
function weeklyProgress(id: string) {
  let hash = 0;
  for (let i = 0; i < id.length; i++) hash = (hash * 31 + id.charCodeAt(i)) >>> 0;
  return 20 + (hash % 70);
}

export default function DashboardPage() {
  const router = useRouter();
  const client = useQueryClient();
  const [agentOpen, setAgentOpen] = useState(false);
  const [credentialOpen, setCredentialOpen] = useState(false);
  const home = useQuery({ queryKey: ["home"], queryFn: () => api<Home>("/mini-homes/me") });
  const agents = useQuery({ queryKey: ["agents"], queryFn: () => api<Agent[]>("/agents") });
  const credentials = useQuery({ queryKey: ["credentials"], queryFn: () => api<Credential[]>("/llm-credentials") });
  const executions = useQuery({ queryKey: ["executions"], queryFn: () => api<Execution[]>("/executions") });
  const harnesses = useQuery({ queryKey: ["harnesses"], queryFn: () => api<Harness[]>("/harnesses") });

  if (home.isPending) return <AppShell kicker="MY AI OFFICE" title="회사를 불러오는 중…"><div className="h-96 animate-pulse rounded-[2rem] bg-white shadow-card"/></AppShell>;
  if (home.error instanceof ApiError && home.error.status === 401) return <AppShell kicker="SESSION EXPIRED" title="로그인이 필요해요"><p className="text-stone-500">서버가 재시작되었거나 로그인 세션이 만료되었습니다.</p><Link href="/login" className="mt-6 inline-block rounded-full bg-ink px-6 py-3 text-white">다시 로그인하기</Link></AppShell>;
  if (home.error || !home.data) return <AppShell kicker="LOAD FAILED" title="회사를 불러오지 못했습니다"><p className="rounded-2xl bg-red-50 p-5 text-red-700">{home.error?.message??"회사 공간 응답이 없습니다."}</p><button onClick={()=>home.refetch()} className="mt-4 rounded-full bg-ink px-5 py-3 font-bold text-white">다시 시도</button></AppShell>;
  return <AppShell kicker="MY AI OFFICE" title={home.data.title}>
    <div className="flex flex-wrap items-end justify-between gap-4">
      <div><p className="text-stone-500">@{home.data.handle} · 방문 {home.data.visitCount}</p></div>
    </div>

    <section id="company" className="mt-10 scroll-mt-24">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div><p className="text-xs font-bold uppercase tracking-wide text-coral">STEP 1</p><h2 className="mt-1 text-2xl font-black">회사 만들기</h2><p className="mt-1 text-sm text-stone-500">회사 이름과 오피스를 꾸미며 우리 팀의 공간을 만드세요.</p></div>
        <Link href="/home/edit" className="rounded-full border bg-white px-5 py-3 font-bold">🏢 회사 꾸미기</Link>
      </div>

      <div className="mt-6 overflow-hidden rounded-[2rem] border-[8px] border-white bg-white shadow-card">
        <div className="border-b bg-white px-6 py-4"><b>{home.data?.title}</b><p className="text-sm text-stone-500">{home.data?.introduction || "우리 팀이 일하는 디지털 오피스입니다."}</p></div>
        <OfficeRoom title={home.data?.title ?? "AI OFFICE"} agents={agents.data ?? []} items={home.data?.items ?? []} backgroundKey={home.data?.backgroundKey} onAgentClick={(id)=>router.push(`/agents/${id}/edit`)} />
        {!agents.isLoading && agents.data?.length === 0 && <button onClick={() => setAgentOpen(true)} className="m-6 w-[calc(100%-3rem)] rounded-3xl border-2 border-dashed border-leaf/30 bg-white p-8 font-bold text-leaf">첫 AI 구성원을 채용하세요 +</button>}
      </div>
    </section>

    <section id="team" className="mt-14 scroll-mt-24">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div><p className="text-xs font-bold uppercase tracking-wide text-coral">STEP 2</p><h2 className="mt-1 text-2xl font-black">팀원 구성하기</h2><p className="mt-1 text-sm text-stone-500">함께 일할 AI 팀원을 채용하고 API 키를 연결하세요.</p></div>
        <div className="flex flex-wrap gap-2"><button onClick={() => setCredentialOpen(!credentialOpen)} className="rounded-full border bg-white px-5 py-3 font-bold">🔐 API 키</button><button onClick={() => setAgentOpen(!agentOpen)} className="rounded-full bg-coral px-5 py-3 font-bold text-white">+ 새 구성원</button></div>
      </div>

      {credentialOpen && <CredentialForm done={() => { setCredentialOpen(false); client.invalidateQueries({ queryKey: ["credentials"] }); }} />}
      {agentOpen && <AgentForm credentials={credentials.data ?? []} done={() => { setAgentOpen(false); client.invalidateQueries({ queryKey: ["agents"] }); }} />}

      <div className="mt-6 grid gap-4 sm:grid-cols-2">
        <div className="rounded-3xl bg-ink p-6 text-white"><p className="text-xs font-bold text-stone">TEAM STATUS</p><p className="mt-3 text-4xl font-black">{agents.data?.length ?? 0}</p><p className="text-sm text-stone-300">함께 일하는 AI 구성원</p></div>
        <div className="rounded-3xl bg-white p-6 shadow-card"><h2 className="font-bold">연결된 모델 키</h2><div className="mt-4 space-y-3">{credentials.data?.map((item) => <div key={item.id} className="rounded-2xl bg-stone-50 p-3 text-sm"><b>{item.provider}</b><br /><span className="text-stone-500">{item.maskedSecret}</span><span className="ml-2 text-xs text-leaf">{item.status}</span></div>)}{credentials.data?.length === 0 && <p className="text-sm text-stone-500">BYOK 키를 연결하면 실제 작업을 시작할 수 있어요.</p>}</div></div>
      </div>

      {agents.data && agents.data.length > 0 && <div className="mt-4 rounded-3xl bg-white p-6 shadow-card">
        <h2 className="font-bold">부서별 팀</h2>
        <div className="mt-4 grid gap-3 sm:grid-cols-2">
          {Object.entries(agents.data.reduce<Record<string, Agent[]>>((groups, agent) => {
            const key = agent.department?.trim() || "미배정";
            (groups[key] ??= []).push(agent);
            return groups;
          }, {})).map(([department, members]) => (
            <div key={department} className="rounded-2xl bg-stone-50 p-4">
              <p className="text-xs font-bold text-coral">{department}</p>
              <p className="mt-1 text-sm text-stone-600">{members.map((member) => member.name).join(", ")}</p>
            </div>
          ))}
        </div>
      </div>}
    </section>

    <section className="mt-14">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div><p className="text-xs font-bold uppercase tracking-wide text-coral">STEP 3</p><h2 className="mt-1 text-2xl font-black">목표 설정하기</h2><p className="mt-1 text-sm text-stone-500">팀원들이 이룰 목표를 정하고 실행 순서를 연결하세요.</p></div>
        <Link href="/harnesses/new" className="rounded-full bg-coral px-5 py-3 font-bold text-white">+ 새 목표</Link>
      </div>

      {harnesses.data && harnesses.data.length > 0 && <div className="mt-6 rounded-3xl bg-white p-6 shadow-card">
        <div className="flex items-center justify-between"><h2 className="font-bold">이번 주 진행 현황</h2><div className="hidden gap-3 text-[10px] font-bold text-stone-400 sm:flex">{["월","화","수","목","금","토","일"].map(day=><span key={day} className="w-6 text-center">{day}</span>)}</div></div>
        <div className="mt-4 space-y-3">{harnesses.data.map(item=>{const pct=weeklyProgress(item.id);const active=item.status==="ACTIVE";return <div key={item.id}><div className="flex justify-between text-xs font-bold"><span>{item.name}</span><span className={active?"text-leaf":"text-stone-400"}>{item.status}</span></div><div className="mt-1.5 h-2.5 w-full overflow-hidden rounded-full bg-stone-100"><div className={`h-full rounded-full ${active?"bg-leaf":"bg-stone-300"}`} style={{width:`${pct}%`}} /></div></div>;})}</div>
      </div>}

      <div className="mt-6 grid gap-6 lg:grid-cols-[1fr_310px]">
        <div className="grid gap-4 md:grid-cols-2">
          {harnesses.data?.map(item => <Link key={item.id} href={`/harnesses/${item.id}/edit`} className="rounded-3xl bg-white p-6 shadow-card transition hover:-translate-y-1"><div className="flex justify-between"><b className="text-xl">{item.name}</b><span className="text-xs font-bold text-leaf">{item.status}</span></div><p className="mt-3 text-stone-500">{item.description || "설명 없음"}</p></Link>)}
          {harnesses.data?.length === 0 && <div className="rounded-3xl border-2 border-dashed p-10 text-center text-stone-500 md:col-span-2">구성원을 먼저 채용한 뒤, 첫 목표를 만들어 연결하세요.</div>}
        </div>
        <aside className="space-y-4">
          <div className="rounded-3xl bg-white p-6 shadow-card"><h2 className="font-bold">최근 실행</h2><div className="mt-3 space-y-2">{executions.data?.slice(0,5).map(item=><Link key={item.id} href={`/executions/${item.id}`} className="flex justify-between rounded-xl bg-stone-50 p-3 text-xs"><span>{item.currentStepKey??"실행"}</span><b className="text-leaf">{item.status}</b></Link>)}{executions.data?.length===0&&<p className="text-sm text-stone-500">아직 실행 기록이 없습니다.</p>}</div></div>
          <div className="rounded-3xl border border-stone-200 bg-cream p-6"><h2 className="font-bold">다음 단계</h2><p className="mt-2 text-sm leading-6 text-stone-600">목표를 발행하면 구조만 내보내집니다. API 키와 실행 결과는 포함되지 않아요.</p></div>
        </aside>
      </div>
    </section>
  </AppShell>;
}

function AgentForm({ credentials, done }: { credentials: Credential[]; done: () => void }) {
  const [provider, setProvider] = useState<Provider>("OPENAI");
  const [characterKey, setCharacterKey] = useState("writer");
  const [draft,setDraft]=useState({name:"",role:"",department:"",script:"",guide:""});
  const examples=[
    {key:"writer",label:"✍️ 기술 작가",name:"Writer",role:"기술 콘텐츠 작가",department:"콘텐츠팀",script:"리서치 결과를 바탕으로 독자가 이해하기 쉬운 Markdown 초안을 작성한다.",guide:"근거 없는 수치를 만들지 않고, 한계와 검증 범위를 명시한다."},
    {key:"reviewer",label:"🔎 검수자",name:"Reviewer",role:"사실·품질 검수자",department:"품질관리팀",script:"초안의 사실성, 논리, 출력 형식을 검토하고 승인 또는 수정 요청을 작성한다.",guide:"확인하지 못한 주장은 승인하지 않고 구체적인 수정 이유를 남긴다."},
    {key:"developer",label:"🧭 리서처",name:"Researcher",role:"기술 리서처",department:"리서치팀",script:"주제에 필요한 근거와 제약사항을 조사해 구조화된 메모로 전달한다.",guide:"출처와 직접 검증 여부를 구분하고 추측은 명확히 표시한다."},
    {key:"manager",label:"📤 발행 담당",name:"Publisher",role:"최종 발행 담당자",department:"운영팀",script:"승인된 결과만 지정된 외부 서비스로 전달하고 발행 상태를 기록한다.",guide:"사용자 승인 없이는 외부 게시·전송을 수행하지 않는다."},
  ];
  const models = useQuery({
    queryKey: ["llm-models", provider],
    queryFn: () => api<ModelOption[]>(`/llm-models?provider=${provider}`),
  });
  const mutation = useMutation({ mutationFn: (body: unknown) => api("/agents", { method: "POST", body: JSON.stringify(body) }), onSuccess: done });
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const data = new FormData(event.currentTarget);
    mutation.mutate({ name: data.get("name"), role: data.get("role"), department: data.get("department") || null, characterKey, script: data.get("script"), guide: data.get("guide"), modelProvider: data.get("provider"), modelName: data.get("model"), credentialId: data.get("credentialId") || null, maxOutputTokens: 2048, timeoutSeconds: 60, visibility: "PRIVATE" });
  };
  return <form onSubmit={submit} className="mt-7 grid gap-6 rounded-3xl bg-white p-6 shadow-card lg:grid-cols-[1fr_300px]">
    <div className="grid gap-4 md:grid-cols-2">
    <h2 className="text-xl font-black md:col-span-2">새 AI 구성원</h2>
    <div className="md:col-span-2"><p className="text-sm font-bold">역할 예시로 시작하기</p><div className="mt-2 flex flex-wrap gap-2">{examples.map(example=><button type="button" key={example.name} onClick={()=>{setDraft(example);setCharacterKey(example.key)}} className="rounded-full border px-4 py-2 text-xs font-black hover:border-coral">{example.label}</button>)}</div></div>
    <Field label="이름"><input name="name" value={draft.name} onChange={event=>setDraft({...draft,name:event.target.value})} required maxLength={40} /></Field><Field label="역할"><input name="role" value={draft.role} onChange={event=>setDraft({...draft,role:event.target.value})} required maxLength={100} /></Field>
    <Field label="부서 (선택)"><input name="department" value={draft.department} onChange={event=>setDraft({...draft,department:event.target.value})} maxLength={60} placeholder="예: 콘텐츠팀" /></Field>
    <div className="md:col-span-2"><span className="text-sm font-bold">사람 캐릭터</span><div className="mt-2"><CharacterPicker value={characterKey} onChange={setCharacterKey} /></div></div>
    <Field label="제공자"><select name="provider" value={provider} onChange={(event) => setProvider(event.target.value as Provider)}><option>OPENAI</option><option>ANTHROPIC</option><option>GOOGLE</option></select></Field>
    <Field label="모델"><select name="model" key={provider} required disabled={models.isLoading}><option value="" disabled>{models.isLoading ? "모델 불러오는 중…" : "모델 선택"}</option>{models.data?.map((model) => <option key={model.id} value={model.id}>{model.displayName}</option>)}</select></Field>
    <Field label="자격증명"><select name="credentialId" key={`credential-${provider}`}><option value="">나중에 연결 (실행 불가)</option>{credentials.filter((item) => item.provider === provider && item.status === "ACTIVE").map((item) => <option key={item.id} value={item.id}>{item.provider} · {item.maskedSecret}</option>)}</select></Field>
    <Field label="스크립트" wide><textarea name="script" value={draft.script} onChange={event=>setDraft({...draft,script:event.target.value})} required rows={3} placeholder="주어진 주제로 초안을 작성한다." /></Field>
    <Field label="가이드" wide><textarea name="guide" value={draft.guide} onChange={event=>setDraft({...draft,guide:event.target.value})} rows={2} placeholder="확인되지 않은 사실은 추측이라고 표시한다." /></Field>
    {mutation.error && <p className="text-sm text-red-600 md:col-span-2">{mutation.error.message}</p>}<button className="rounded-2xl bg-ink py-3 font-bold text-white md:col-span-2">구성원 추가</button></div>
    <aside className="rounded-3xl bg-stone-950 p-5 text-stone-100"><p className="text-xs font-black text-stone">생성 예정 표준 구조</p><pre className="mt-4 whitespace-pre-wrap text-xs leading-6">{`내-AI-회사/\n├── AGENTS.md\n├── CLAUDE.md\n├── harness.md\n├── harness.json\n├── agents/\n│   └── ${draft.name||"agent"}.md\n├── guides/\n│   └── ${draft.name||"agent"}-guide.md\n└── schemas/\n    ├── ${draft.name||"agent"}-input.json\n    └── ${draft.name||"agent"}-output.json`}</pre><p className="mt-5 text-xs leading-5 text-stone-400">사용자는 폴더나 MD를 직접 만들 필요가 없습니다. Agentown이 입력 내용을 파일로 자동 생성하며 API 키와 실행 결과는 내보내기에 포함하지 않습니다.</p></aside>
  </form>;
}

function CredentialForm({ done }: { done: () => void }) {
  const mutation = useMutation({ mutationFn: (body: unknown) => api("/llm-credentials", { method: "POST", body: JSON.stringify(body) }), onSuccess: done });
  const submit = (event: FormEvent<HTMLFormElement>) => { event.preventDefault(); const data = new FormData(event.currentTarget); mutation.mutate({ provider: data.get("provider"), secret: data.get("secret"), providerOptions: {} }); };
  return <form onSubmit={submit} className="mt-7 grid gap-4 rounded-3xl bg-white p-6 shadow-card md:grid-cols-[180px_1fr_auto] md:items-end"><Field label="LLM 제공자"><select name="provider"><option>OPENAI</option><option>ANTHROPIC</option><option>GOOGLE</option></select></Field><Field label="API 키"><input type="password" name="secret" autoComplete="off" required placeholder="등록 후 다시 표시되지 않습니다" /></Field><button disabled={mutation.isPending} className="rounded-2xl bg-leaf px-6 py-3 font-bold text-white">검증하고 저장</button>{mutation.error && <p className="text-sm text-red-600 md:col-span-3">{mutation.error.message}</p>}</form>;
}

function Field({ label, children, wide = false }: { label: string; children: React.ReactElement; wide?: boolean }) {
  return <label className={`text-sm font-bold ${wide ? "md:col-span-2" : ""}`}>{label}<span className="mt-2 block [&>*]:w-full [&>*]:rounded-xl [&>*]:border [&>*]:border-stone-200 [&>*]:px-4 [&>*]:py-3 [&>*]:outline-none [&>*]:focus:border-coral">{children}</span></label>;
}
