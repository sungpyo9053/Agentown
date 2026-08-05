"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { CharacterPicker } from "@/components/AgentCharacter";
import { OfficeRoom } from "@/components/OfficeRoom";

type RoomItem = { agentId?: string; itemType: "AGENT" | "ASSET"; positionX: number; positionY: number; width: number; height: number; zIndex: number; rotation: number };
type Home = { id: string; handle: string; title: string; introduction?: string; backgroundKey?: string; visitCount: number; items: RoomItem[] };
type Agent = { id: string; name: string; role: string; characterKey: string; modelProvider: string; modelName: string };
type Credential = { id: string; provider: string; maskedSecret: string; status: string; lastVerifiedAt?: string };
type Provider = "OPENAI" | "ANTHROPIC" | "GOOGLE";
type ModelOption = { id: string; displayName: string };

export default function DashboardPage() {
  const router = useRouter();
  const client = useQueryClient();
  const [agentOpen, setAgentOpen] = useState(false);
  const [credentialOpen, setCredentialOpen] = useState(false);
  const home = useQuery({ queryKey: ["home"], queryFn: () => api<Home>("/mini-homes/me") });
  const agents = useQuery({ queryKey: ["agents"], queryFn: () => api<Agent[]>("/agents") });
  const credentials = useQuery({ queryKey: ["credentials"], queryFn: () => api<Credential[]>("/llm-credentials") });

  if (home.error instanceof ApiError && home.error.status === 401) return <main className="mx-auto max-w-xl px-6 py-24 text-center"><h1 className="text-3xl font-black">로그인이 필요해요</h1><Link href="/login" className="mt-6 inline-block rounded-full bg-ink px-6 py-3 text-white">로그인하기</Link></main>;
  return <main className="mx-auto max-w-6xl px-6 pb-20 pt-8">
    <div className="flex flex-wrap items-end justify-between gap-4">
      <div><p className="text-sm font-bold text-coral">MY MINI HOME</p><h1 className="mt-1 text-4xl font-black">{home.data?.title ?? "마을을 불러오는 중…"}</h1><p className="mt-2 text-stone-500">@{home.data?.handle} · 방문 {home.data?.visitCount ?? 0}</p></div>
      <div className="flex flex-wrap gap-2"><Link href="/harnesses" className="rounded-full border bg-white px-5 py-3 font-bold">AI 팀</Link><Link href="/home/edit" className="rounded-full border bg-white px-5 py-3 font-bold">🏢 회사 꾸미기</Link><button onClick={() => setCredentialOpen(!credentialOpen)} className="rounded-full border bg-white px-5 py-3 font-bold">🔐 API 키</button><button onClick={() => setAgentOpen(!agentOpen)} className="rounded-full bg-coral px-5 py-3 font-bold text-white">+ 새 구성원</button></div>
    </div>

    {credentialOpen && <CredentialForm done={() => { setCredentialOpen(false); client.invalidateQueries({ queryKey: ["credentials"] }); }} />}
    {agentOpen && <AgentForm credentials={credentials.data ?? []} done={() => { setAgentOpen(false); client.invalidateQueries({ queryKey: ["agents"] }); }} />}

    <section className="mt-8 grid gap-6 lg:grid-cols-[1fr_310px]">
      <div className="overflow-hidden rounded-[2rem] border-[8px] border-white bg-white shadow-card">
        <div className="border-b bg-white px-6 py-4"><b>{home.data?.title}</b><p className="text-sm text-stone-500">{home.data?.introduction || "우리 팀이 일하는 디지털 오피스입니다."}</p></div>
        <OfficeRoom title={home.data?.title ?? "AI OFFICE"} agents={agents.data ?? []} items={home.data?.items ?? []} backgroundKey={home.data?.backgroundKey} onAgentClick={(id)=>router.push(`/agents/${id}/edit`)} />
        {!agents.isLoading && agents.data?.length === 0 && <button onClick={() => setAgentOpen(true)} className="m-6 w-[calc(100%-3rem)] rounded-3xl border-2 border-dashed border-leaf/30 bg-white p-8 font-bold text-leaf">첫 AI 구성원을 채용하세요 +</button>}
      </div>
      <aside className="space-y-4">
        <div className="rounded-3xl bg-ink p-6 text-white"><p className="text-xs font-bold text-coral">TEAM STATUS</p><p className="mt-3 text-4xl font-black">{agents.data?.length ?? 0}</p><p className="text-sm text-stone-300">함께 일하는 AI 구성원</p></div>
        <div className="rounded-3xl bg-white p-6 shadow-card"><h2 className="font-bold">연결된 모델 키</h2><div className="mt-4 space-y-3">{credentials.data?.map((item) => <div key={item.id} className="rounded-2xl bg-stone-50 p-3 text-sm"><b>{item.provider}</b><br /><span className="text-stone-500">{item.maskedSecret}</span><span className="ml-2 text-xs text-leaf">{item.status}</span></div>)}{credentials.data?.length === 0 && <p className="text-sm text-stone-500">BYOK 키를 연결하면 실제 작업을 시작할 수 있어요.</p>}</div></div>
        <div className="rounded-3xl border border-stone-200 bg-cream p-6"><h2 className="font-bold">다음 단계</h2><p className="mt-2 text-sm leading-6 text-stone-600">구성원을 만들고 각자의 역할과 스크립트를 설정하세요. API 키는 복제하거나 공유할 때 포함되지 않습니다.</p></div>
      </aside>
    </section>
  </main>;
}

function AgentForm({ credentials, done }: { credentials: Credential[]; done: () => void }) {
  const [provider, setProvider] = useState<Provider>("OPENAI");
  const [characterKey, setCharacterKey] = useState("writer");
  const models = useQuery({
    queryKey: ["llm-models", provider],
    queryFn: () => api<ModelOption[]>(`/llm-models?provider=${provider}`),
  });
  const mutation = useMutation({ mutationFn: (body: unknown) => api("/agents", { method: "POST", body: JSON.stringify(body) }), onSuccess: done });
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const data = new FormData(event.currentTarget);
    mutation.mutate({ name: data.get("name"), role: data.get("role"), characterKey, script: data.get("script"), guide: data.get("guide"), modelProvider: data.get("provider"), modelName: data.get("model"), credentialId: data.get("credentialId") || null, maxOutputTokens: 2048, timeoutSeconds: 60, visibility: "PRIVATE" });
  };
  return <form onSubmit={submit} className="mt-7 grid gap-4 rounded-3xl bg-white p-6 shadow-card md:grid-cols-2">
    <h2 className="text-xl font-black md:col-span-2">새 AI 구성원</h2>
    <Field label="이름"><input name="name" required maxLength={40} /></Field><Field label="역할"><input name="role" required maxLength={100} /></Field>
    <div className="md:col-span-2"><span className="text-sm font-bold">사람 캐릭터</span><div className="mt-2"><CharacterPicker value={characterKey} onChange={setCharacterKey} /></div></div>
    <Field label="제공자"><select name="provider" value={provider} onChange={(event) => setProvider(event.target.value as Provider)}><option>OPENAI</option><option>ANTHROPIC</option><option>GOOGLE</option></select></Field>
    <Field label="모델"><select name="model" key={provider} required disabled={models.isLoading}><option value="" disabled>{models.isLoading ? "모델 불러오는 중…" : "모델 선택"}</option>{models.data?.map((model) => <option key={model.id} value={model.id}>{model.displayName}</option>)}</select></Field>
    <Field label="자격증명"><select name="credentialId" key={`credential-${provider}`}><option value="">나중에 연결 (실행 불가)</option>{credentials.filter((item) => item.provider === provider && item.status === "ACTIVE").map((item) => <option key={item.id} value={item.id}>{item.provider} · {item.maskedSecret}</option>)}</select></Field>
    <Field label="스크립트" wide><textarea name="script" required rows={3} placeholder="주어진 주제로 초안을 작성한다." /></Field>
    <Field label="가이드" wide><textarea name="guide" rows={2} placeholder="확인되지 않은 사실은 추측이라고 표시한다." /></Field>
    {mutation.error && <p className="text-sm text-red-600 md:col-span-2">{mutation.error.message}</p>}<button className="rounded-2xl bg-ink py-3 font-bold text-white md:col-span-2">구성원 추가</button>
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
