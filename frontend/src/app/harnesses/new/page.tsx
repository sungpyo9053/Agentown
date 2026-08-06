"use client";

import { FormEvent, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { AppShell } from "@/components/AppShell";
import { AgentCharacter } from "@/components/AgentCharacter";
import { api } from "@/lib/api";

type Provider = "OPENAI" | "ANTHROPIC" | "GOOGLE";
type Model = { id:string; displayName:string };
type Credential = { id:string; provider:Provider; maskedSecret:string; status:string; lastVerifiedAt?:string };
type DesignedAgent = { key:string; name:string; role:string; responsibility:string; taskDescription:string; desiredOutput:string; requiredEvidence:string; guide:string; prohibitions:string; rewriteCriteria:string; approvalCriteria:string; characterKey:string; provider:Provider; recommendedModel:string };
type DesignDraft = { companyName:string; goal:string; agents:DesignedAgent[]; steps:{key:string;agentKey:string;sequence:number;maxRetries:number}[]; approvalAfterLast:boolean; designSource:string };
type DesignResult = { draft:DesignDraft; valid:boolean; errors:string[] };

export default function Page() {
  const router = useRouter();
  const [provider, setProvider] = useState<Provider>("OPENAI");
  const [stubMode, setStubMode] = useState(true);
  const [draft, setDraft] = useState<DesignDraft | null>(null);
  const [selectedCredential, setSelectedCredential] = useState("");
  const models = useQuery({ queryKey:["llm-models", provider], queryFn:() => api<Model[]>(`/llm-models?provider=${provider}`) });
  const credentials = useQuery({ queryKey:["credentials"], queryFn:() => api<Credential[]>("/llm-credentials") });
  const design = useMutation({ mutationFn:(body:unknown) => api<DesignResult>("/designer/companies/design", { method:"POST", body:JSON.stringify(body) }), onSuccess:(result) => setDraft(result.draft) });
  const apply = useMutation({ mutationFn:() => api<{harnessId:string}>("/designer/companies/apply", { method:"POST", body:JSON.stringify({ draft, credentialId:selectedCredential || null, stubMode }) }), onSuccess:(result) => router.push(`/harnesses/${result.harnessId}/edit`) });

  function submit(event:FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = new FormData(event.currentTarget);
    design.mutate({
      companyName:form.get("companyName"), goal:form.get("goal"), primaryInput:form.get("primaryInput"), desiredOutput:form.get("desiredOutput"),
      requiredEvidence:form.get("requiredEvidence"), prohibitions:form.get("prohibitions"), approvalPolicy:form.get("approvalPolicy"),
      provider, model:form.get("model"), credentialId:selectedCredential || null, stubMode,
    });
  }
  function updateAgent(key:string, patch:Partial<DesignedAgent>) {
    setDraft((current) => current ? { ...current, agents:current.agents.map((agent) => agent.key === key ? { ...agent, ...patch } : agent) } : current);
  }

  return <AppShell kicker="AI COMPANY DESIGNER" title="질문으로 AI 회사 만들기">
    <div className="grid gap-6 xl:grid-cols-[420px_1fr]">
      <form onSubmit={submit} className="h-fit space-y-5 rounded-3xl bg-white p-7 shadow-card">
        <div><p className="text-sm leading-6 text-stone-600">스크립트나 JSON을 직접 쓰지 않아도 됩니다. 실제 블로그 하네스의 조직·가이드·검수 구조를 바탕으로 다른 업종의 AI 회사를 설계합니다.</p></div>
        <Question number="1" label="회사 이름은 무엇인가요?" example="예: 쇼핑몰 고객지원팀"><input name="companyName" required maxLength={100}/></Question>
        <Question number="2" label="이 회사가 해결할 문제는 무엇인가요?" example="예: 고객 문의를 분류하고 정책에 맞는 답변을 작성·검수한다."><textarea name="goal" required rows={3}/></Question>
        <Question number="3" label="사용자가 무엇을 입력하나요?" example="예: 고객 문의, 주문 정보, 환불 정책"><textarea name="primaryInput" required rows={2}/></Question>
        <Question number="4" label="최종 결과물은 무엇인가요?" example="예: 바로 전송 가능한 고객 답변과 처리 분류"><textarea name="desiredOutput" required rows={2}/></Question>
        <Question number="5" label="반드시 확인할 근거는 무엇인가요?" example="예: 주문 상태와 최신 환불 정책"><textarea name="requiredEvidence" rows={2}/></Question>
        <Question number="6" label="절대 하면 안 되는 일은 무엇인가요?" example="예: 환불 가능 여부를 추측하거나 개인정보를 답변에 노출하지 않는다."><textarea name="prohibitions" rows={2}/></Question>
        <Question number="7" label="언제 사람의 승인을 받아야 하나요?" example="예: 외부 전송 전 최종 답변과 정책 적용을 승인한다."><textarea name="approvalPolicy" rows={2}/></Question>
        <div className="rounded-2xl bg-cream p-4"><p className="text-sm font-black">설계 모델</p><div className="mt-3 grid gap-3 sm:grid-cols-2"><select value={provider} onChange={(event) => { setProvider(event.target.value as Provider); setSelectedCredential(""); }} className="rounded-xl border bg-white p-3"><option value="OPENAI">Codex / OpenAI</option><option value="ANTHROPIC">Claude / Anthropic</option><option value="GOOGLE">Gemini / Google</option></select><select name="model" required className="rounded-xl border bg-white p-3"><option value="">모델 선택</option>{models.data?.map((model) => <option key={model.id} value={model.id}>{model.displayName}</option>)}</select></div><select value={selectedCredential} onChange={(event) => setSelectedCredential(event.target.value)} disabled={stubMode} className="mt-3 w-full rounded-xl border bg-white p-3"><option value="">연결 완료 API 키 선택</option>{credentials.data?.filter((item) => item.provider === provider && item.status === "ACTIVE").map((item) => <option key={item.id} value={item.id}>{item.maskedSecret} · 연결 완료</option>)}</select><label className="mt-3 flex items-center gap-2 text-sm"><input type="checkbox" checked={stubMode} onChange={(event) => setStubMode(event.target.checked)}/><span>API 비용 없이 Stub으로 전체 설계 흐름 테스트</span></label>{!stubMode && !selectedCredential && <p className="mt-2 text-xs font-bold text-amber-700">실제 설계에는 설정에서 검증·저장된 API 키가 필요합니다.</p>}</div>
        <button disabled={design.isPending || (!stubMode && !selectedCredential)} className="w-full rounded-xl bg-coral p-4 font-black text-white">{design.isPending ? "AI 회사 설계 중…" : "AI 회사 전체 초안 만들기"}</button>
        {design.error && <p className="rounded-xl bg-red-50 p-3 text-sm text-red-600">{design.error.message}</p>}
      </form>

      <section className="space-y-5">
        {!draft && <div className="rounded-3xl border-2 border-dashed border-stone-300 bg-white/60 p-10 text-center"><p className="text-5xl">🏢</p><h2 className="mt-5 text-2xl font-black">답변에서 회사 조직 전체를 만듭니다</h2><p className="mt-3 leading-7 text-stone-500">필요한 사람, 역할 문서, 공통 가이드, 입력·출력 Schema, 실행 순서와 승인 위치를 한 번에 제안합니다.</p><TreePreview/></div>}
        {draft && <><div className="rounded-3xl bg-ink p-7 text-white"><p className="text-xs font-black text-coral">{draft.designSource} DESIGN DRAFT</p><h2 className="mt-2 text-3xl font-black">{draft.companyName}</h2><p className="mt-3 text-stone-300">{draft.goal}</p><p className="mt-3 text-xs text-stone-400">아래 구성원 이름·역할·작업·가이드를 수정한 뒤 승인할 수 있습니다.</p></div><div className="space-y-4">{[...draft.steps].sort((a,b) => a.sequence-b.sequence).map((step, index) => { const agent = draft.agents.find((item) => item.key === step.agentKey)!; return <AgentDraftCard key={agent.key} agent={agent} index={index} maxRetries={step.maxRetries} onChange={(patch) => updateAgent(agent.key, patch)}/>; })}</div><div className="rounded-3xl border border-leaf/30 bg-emerald-50 p-6"><b>서버 검증 후 저장됩니다</b><p className="mt-2 text-sm leading-6 text-stone-600">최대 5명, 순차 실행, 제한된 재시도, 존재하는 Agent 연결, 지원 모델, API 키 소유권과 위험 작업을 다시 검사합니다.</p><button disabled={apply.isPending} onClick={() => apply.mutate()} className="mt-5 w-full rounded-xl bg-leaf p-4 font-black text-white">{apply.isPending ? "내 회사에 저장 중…" : "이 조직을 승인하고 내 회사에 저장"}</button>{apply.error && <p className="mt-3 text-sm text-red-600">{apply.error.message}</p>}</div><TreePreview/></>}
      </section>
    </div>
  </AppShell>;
}

function Question({ number, label, example, children }: { number:string; label:string; example:string; children:React.ReactElement }) { return <label className="block text-sm font-black"><span className="mr-2 text-coral">{number}</span>{label}<small className="mt-1 block font-normal text-stone-500">{example}</small><span className="mt-2 block [&>*]:w-full [&>*]:rounded-xl [&>*]:border [&>*]:p-3">{children}</span></label>; }
function AgentDraftCard({ agent, index, maxRetries, onChange }: { agent:DesignedAgent; index:number; maxRetries:number; onChange:(patch:Partial<DesignedAgent>)=>void }) { const input="w-full rounded-xl border border-stone-200 bg-white p-3 text-sm"; return <div className="rounded-3xl bg-white p-6 shadow-card"><div className="flex gap-4"><AgentCharacter characterKey={agent.characterKey} className="h-24 w-20 shrink-0"/><div className="grid flex-1 gap-3 sm:grid-cols-2"><p className="text-xs font-black text-coral sm:col-span-2">STEP {index + 1} · {agent.provider} · 재시도 최대 {maxRetries}회</p><label className="text-xs font-bold">구성원 이름<input className={`${input} mt-1`} value={agent.name} onChange={(event)=>onChange({name:event.target.value})}/></label><label className="text-xs font-bold">역할<input className={`${input} mt-1`} value={agent.role} onChange={(event)=>onChange({role:event.target.value})}/></label></div></div><details className="mt-4 rounded-2xl bg-stone-50 p-4"><summary className="cursor-pointer text-sm font-bold">agent.md · guide.md 내용 확인 및 수정</summary><div className="mt-4 grid gap-3"><DraftField label="책임" value={agent.responsibility} onChange={(value)=>onChange({responsibility:value})}/><DraftField label="작업 순서와 내용" value={agent.taskDescription} onChange={(value)=>onChange({taskDescription:value})}/><DraftField label="원하는 결과" value={agent.desiredOutput} onChange={(value)=>onChange({desiredOutput:value})}/><DraftField label="필수 근거" value={agent.requiredEvidence} onChange={(value)=>onChange({requiredEvidence:value})}/><DraftField label="작업 가이드" value={agent.guide} onChange={(value)=>onChange({guide:value})}/><DraftField label="금지사항" value={agent.prohibitions} onChange={(value)=>onChange({prohibitions:value})}/><DraftField label="재작성 기준" value={agent.rewriteCriteria} onChange={(value)=>onChange({rewriteCriteria:value})}/><DraftField label="승인 기준" value={agent.approvalCriteria} onChange={(value)=>onChange({approvalCriteria:value})}/></div></details></div>; }
function DraftField({ label, value, onChange }: { label:string; value:string; onChange:(value:string)=>void }) { return <label className="text-xs font-bold">{label}<textarea rows={2} value={value} onChange={(event)=>onChange(event.target.value)} className="mt-1 w-full rounded-xl border border-stone-200 bg-white p-3 text-sm font-normal"/></label>; }
function TreePreview() { return <pre className="mx-auto mt-7 max-w-md overflow-auto rounded-2xl bg-stone-950 p-5 text-left text-xs leading-6 text-stone-200">{`내-AI-회사/
├── AGENTS.md       # Codex CLI
├── CLAUDE.md       # Claude Code
├── harness.md
├── harness.json
├── agents/
├── guides/
└── schemas/`}</pre>; }
