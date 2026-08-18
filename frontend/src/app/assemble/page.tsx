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
type Harness = { id: string; name: string; description?: string; status: string; visibility: string };

export default function AssemblePage() {
  const client = useQueryClient();
  const [hireOpen, setHireOpen] = useState(false);
  const agents = useQuery({ queryKey: ["agents"], queryFn: () => api<Agent[]>("/agents") });
  const credentials = useQuery({ queryKey: ["credentials"], queryFn: () => api<Credential[]>("/llm-credentials") });
  const harnesses = useQuery({ queryKey: ["harnesses"], queryFn: () => api<Harness[]>("/harnesses") });

  return <AppShell kicker="ASSEMBLE" title="직원 관리">
    <div className="space-y-2">
      <section id="hire" className="scroll-mt-24">
        <Panel
          title="직원 뽑기"
          action={<button onClick={() => setHireOpen(!hireOpen)} className="rounded-pill bg-ink px-6 py-3 text-sm font-medium text-white transition active:scale-95 active:opacity-50">{hireOpen ? "닫기" : "새 구성원"}</button>}
        >
          {hireOpen && <AgentForm credentials={credentials.data ?? []} done={() => { setHireOpen(false); client.invalidateQueries({ queryKey: ["agents"] }); }} />}
          <div className="divide-y divide-hairline">
            {agents.data?.map((agent) => <Link key={agent.id} href={`/agents/${agent.id}/edit`} className="flex items-center justify-between gap-4 py-4">
              <span>
                <b className="block text-base font-medium text-ink">{agent.name}</b>
                <small className="text-sm text-mute">{agent.role}{agent.department ? ` · ${agent.department}` : ""}</small>
              </span>
              <span className="shrink-0 text-sm font-medium text-mute">{agent.modelProvider}</span>
            </Link>)}
            {agents.data?.length === 0 && !hireOpen && <p className="py-8 text-center text-sm text-mute">아직 구성원이 없습니다.</p>}
          </div>
        </Panel>
      </section>

      {/* 가이드 = 각 직원이 일하는 기준(agent.md · guide.md). 이 서비스의 핵심 산출물. */}
      <section id="guides" className="scroll-mt-24">
        <Panel title="가이드 관리">
          <p className="mb-5 text-sm leading-6 text-charcoal">직원마다 무엇을 어떤 순서로 하고, 무엇을 하면 안 되며, 언제 완료로 볼지를 정합니다. 질문에 답하면 agent.md · guide.md가 자동으로 만들어집니다.</p>
          <div className="divide-y divide-hairline">
            {agents.data?.map((agent) => <Link key={agent.id} href={`/agents/${agent.id}/edit`} className="flex items-center justify-between gap-4 py-4">
              <span>
                <b className="block text-base font-medium text-ink">{agent.name}</b>
                <small className="text-sm text-mute">{agent.department || "미배정"} · {agent.role}</small>
              </span>
              <span className="shrink-0 text-sm font-medium text-ink underline">가이드 편집</span>
            </Link>)}
            {agents.data?.length === 0 && <p className="py-8 text-center text-sm text-mute">직원을 먼저 뽑으면 가이드를 만들 수 있어요.</p>}
          </div>
        </Panel>
      </section>

      {/* 하네스 = 직원들을 순서대로 엮어 하나의 목표를 끝내는 실행 구조 */}
      <section id="harness" className="scroll-mt-24">
        <Panel title="하네스 구성" action={<Link href="/harnesses/new" className="rounded-pill bg-ink px-6 py-3 text-sm font-medium text-white transition active:scale-95 active:opacity-50">새 하네스</Link>}>
          <p className="mb-5 text-sm leading-6 text-charcoal">직원들을 순서대로 연결하고 사람이 승인할 지점을 정해, 하나의 목표를 끝까지 처리하는 실행 구조를 만듭니다.</p>
          <div className="divide-y divide-hairline">
            {harnesses.data?.map((item) => <Link key={item.id} href={`/harnesses/${item.id}/edit`} className="flex items-center justify-between gap-4 py-4">
              <span>
                <b className="block text-base font-medium text-ink">{item.name}</b>
                <small className="text-sm text-mute">{item.description || "설명 없음"}</small>
              </span>
              <span className="shrink-0 text-sm font-medium text-mute">{item.status}</span>
            </Link>)}
            {harnesses.data?.length === 0 && <p className="py-8 text-center text-sm text-mute">직원을 먼저 뽑은 뒤, 첫 하네스를 만들어 연결하세요.</p>}
          </div>
        </Panel>
      </section>
    </div>
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
