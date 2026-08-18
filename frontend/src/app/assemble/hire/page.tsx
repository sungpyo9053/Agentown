"use client";

import { FormEvent, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { api } from "@/lib/api";
import { CharacterPicker } from "@/components/AgentCharacter";
import { AppShell, Panel } from "@/components/AppShell";

type Agent = { id: string; name: string; role: string; department?: string; characterKey: string; modelProvider: string; modelName: string };
type Credential = { id: string; provider: string; maskedSecret: string; status: string };
type Provider = "OPENAI" | "ANTHROPIC" | "GOOGLE";
type ModelOption = { id: string; displayName: string };

export default function HirePage() {
  const client = useQueryClient();
  const [open, setOpen] = useState(false);
  const agents = useQuery({ queryKey: ["agents"], queryFn: () => api<Agent[]>("/agents") });
  const credentials = useQuery({ queryKey: ["credentials"], queryFn: () => api<Credential[]>("/llm-credentials") });

  return <AppShell kicker="ASSEMBLE" title="직원 뽑기">
    <Panel action={<button onClick={() => setOpen(!open)} className="rounded-pill bg-ink px-6 py-3 text-sm font-medium text-white transition active:scale-95 active:opacity-50">{open ? "닫기" : "새 구성원"}</button>}>
      <p className="mb-5 text-sm leading-6 text-charcoal">역할과 맡을 일을 정해 AI 직원을 채용합니다. 채용한 직원은 회사 공간에 바로 나타나요.</p>
      {open && <AgentForm credentials={credentials.data ?? []} done={() => { setOpen(false); client.invalidateQueries({ queryKey: ["agents"] }); }} />}
      <div className="divide-y divide-hairline">
        {agents.data?.map((agent) => <Link key={agent.id} href={`/agents/${agent.id}/edit`} className="flex items-center justify-between gap-4 py-4">
          <span>
            <b className="block text-base font-medium text-ink">{agent.name}</b>
            <small className="text-sm text-mute">{agent.role}{agent.department ? ` · ${agent.department}` : ""}</small>
          </span>
          <span className="shrink-0 text-sm font-medium text-mute">{agent.modelProvider}</span>
        </Link>)}
        {agents.data?.length === 0 && !open && <p className="py-12 text-center text-sm text-mute">아직 구성원이 없습니다.</p>}
      </div>
    </Panel>
  </AppShell>;
}

function AgentForm({ credentials, done }: { credentials: Credential[]; done: () => void }) {
  const [provider, setProvider] = useState<Provider>("OPENAI");
  const [characterKey, setCharacterKey] = useState("writer");
  const [draft, setDraft] = useState({ name: "", role: "", department: "", script: "", guide: "" });
  const examples = [
    { key: "writer", label: "기술 작가", name: "Writer", role: "기술 콘텐츠 작가", department: "콘텐츠팀", script: "리서치 결과를 바탕으로 독자가 이해하기 쉬운 Markdown 초안을 작성한다.", guide: "근거 없는 수치를 만들지 않고, 한계와 검증 범위를 명시한다." },
    { key: "reviewer", label: "검수자", name: "Reviewer", role: "사실·품질 검수자", department: "품질관리팀", script: "초안의 사실성, 논리, 출력 형식을 검토하고 승인 또는 수정 요청을 작성한다.", guide: "확인하지 못한 주장은 승인하지 않고 구체적인 수정 이유를 남긴다." },
    { key: "developer", label: "리서처", name: "Researcher", role: "기술 리서처", department: "리서치팀", script: "주제에 필요한 근거와 제약사항을 조사해 구조화된 메모로 전달한다.", guide: "출처와 직접 검증 여부를 구분하고 추측은 명확히 표시한다." },
    { key: "manager", label: "발행 담당", name: "Publisher", role: "최종 발행 담당자", department: "운영팀", script: "승인된 결과만 지정된 외부 서비스로 전달하고 발행 상태를 기록한다.", guide: "사용자 승인 없이는 외부 게시·전송을 수행하지 않는다." },
  ];
  const models = useQuery({ queryKey: ["llm-models", provider], queryFn: () => api<ModelOption[]>(`/llm-models?provider=${provider}`) });
  const mutation = useMutation({ mutationFn: (body: unknown) => api("/agents", { method: "POST", body: JSON.stringify(body) }), onSuccess: done });

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    mutation.mutate({
      name: data.get("name"), role: data.get("role"), department: data.get("department") || null, characterKey,
      script: data.get("script"), guide: data.get("guide"), modelProvider: data.get("provider"), modelName: data.get("model"),
      credentialId: data.get("credentialId") || null, maxOutputTokens: 2048, timeoutSeconds: 60, visibility: "PRIVATE",
    });
  };

  return <form onSubmit={submit} className="mb-8 border border-hairline p-6">
    <p className="text-sm font-medium text-ink">역할 예시로 시작하기</p>
    <div className="mt-3 flex flex-wrap gap-2">
      {examples.map((example) => <button type="button" key={example.name} onClick={() => { setDraft(example); setCharacterKey(example.key); }} className="rounded-pill border border-hairline px-4 py-2 text-sm font-medium text-ink transition active:scale-95">{example.label}</button>)}
    </div>

    <div className="mt-6 grid gap-4 md:grid-cols-2">
      <Field label="이름"><input name="name" value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} required maxLength={40} /></Field>
      <Field label="역할"><input name="role" value={draft.role} onChange={(e) => setDraft({ ...draft, role: e.target.value })} required maxLength={100} /></Field>
      <Field label="부서 (선택)"><input name="department" value={draft.department} onChange={(e) => setDraft({ ...draft, department: e.target.value })} maxLength={60} placeholder="예: 콘텐츠팀" /></Field>
      <Field label="제공자"><select name="provider" value={provider} onChange={(e) => setProvider(e.target.value as Provider)}><option>OPENAI</option><option>ANTHROPIC</option><option>GOOGLE</option></select></Field>
      <Field label="모델"><select name="model" key={provider} required disabled={models.isLoading}><option value="" disabled>{models.isLoading ? "모델 불러오는 중…" : "모델 선택"}</option>{models.data?.map((model) => <option key={model.id} value={model.id}>{model.displayName}</option>)}</select></Field>
      <Field label="자격증명"><select name="credentialId" key={`credential-${provider}`}><option value="">나중에 연결 (실행 불가)</option>{credentials.filter((item) => item.provider === provider && item.status === "ACTIVE").map((item) => <option key={item.id} value={item.id}>{item.provider} · {item.maskedSecret}</option>)}</select></Field>
      <div className="md:col-span-2">
        <span className="text-sm font-medium text-ink">캐릭터</span>
        <div className="mt-2"><CharacterPicker value={characterKey} onChange={setCharacterKey} /></div>
      </div>
      <Field label="해야 할 일" wide><textarea name="script" value={draft.script} onChange={(e) => setDraft({ ...draft, script: e.target.value })} required rows={3} /></Field>
      <Field label="가이드" wide><textarea name="guide" value={draft.guide} onChange={(e) => setDraft({ ...draft, guide: e.target.value })} rows={2} /></Field>
    </div>

    {mutation.error && <p className="mt-4 text-sm text-sale">{mutation.error.message}</p>}
    <button disabled={mutation.isPending} className="mt-6 w-full rounded-pill bg-ink py-4 text-sm font-medium text-white transition active:scale-95 active:opacity-50">채용하기</button>
  </form>;
}

function Field({ label, children, wide = false }: { label: string; children: React.ReactElement; wide?: boolean }) {
  return <label className={`text-sm font-medium text-ink ${wide ? "md:col-span-2" : ""}`}>{label}
    <span className="mt-2 block [&>*]:w-full [&>*]:border [&>*]:border-hairline [&>*]:px-4 [&>*]:py-3 [&>*]:outline-none [&>*]:focus:border-ink">{children}</span>
  </label>;
}
