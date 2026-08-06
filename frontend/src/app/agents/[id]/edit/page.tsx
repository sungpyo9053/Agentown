"use client";
/* eslint-disable react-hooks/set-state-in-effect */

import { FormEvent, useEffect, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { AppShell } from "@/components/AppShell";
import { CharacterPicker } from "@/components/AgentCharacter";
import { api } from "@/lib/api";

type Agent = { id:string; name:string; role:string; personality?:string; characterKey:string; systemPrompt?:string; script:string; guide?:string; modelProvider:string; modelName:string; credentialId?:string; temperature:number; maxOutputTokens:number; timeoutSeconds:number; providerOptions:Record<string,unknown>; visibility:string; updatedAt:string };
type Definition = { agentMarkdown:string; guideMarkdown:string; inputSchema:Record<string,unknown>; outputSchema:Record<string,unknown> };
type Credential = { id:string; provider:string; maskedSecret:string; status:string };
type Model = { id:string; displayName:string };
type GuideAnswers = { key:string; taskDescription:string; desiredOutput:string; requiredEvidence:string; outputStyle:string; prohibitions:string; rewriteCriteria:string; approvalCriteria:string };

const guideExamples: Record<string, GuideAnswers> = {
  writer: {
    key: "writer",
    taskDescription: "검증된 조사 자료를 읽고 독자의 핵심 질문을 한 문장으로 정한 뒤, 근거와 한계를 구분해 기술 글 초안을 작성한다.",
    desiredOutput: "제목, 요약, 본문 H2/H3, 적용 판단과 참고 근거를 포함한 Markdown 문서",
    requiredEvidence: "직접 실행 결과와 공식 자료를 구분한다. 수치·가격·기능·날짜는 제공된 근거에서만 사용한다.",
    outputStyle: "형식적인 인사 없이 핵심 문제로 시작하고, -다/-한다 설명체로 작성한다. 기능 나열보다 구조·흐름·선택 이유를 설명한다.",
    prohibitions: "가짜 경험, 확인하지 않은 수치, 존재하지 않는 출처, 키워드 반복, 내용과 무관한 FAQ를 만들지 않는다.",
    rewriteCriteria: "독자의 질문에 답하지 못하거나, 근거 없는 주장이 있거나, 사실·해석·미검증 범위가 섞인 경우 다시 작성한다.",
    approvalCriteria: "핵심 질문에 답하고, 모든 주요 주장이 근거와 연결되며, 적용 대상·비추천 조건·남은 한계가 드러나면 승인한다.",
  },
  reviewer: {
    key: "reviewer",
    taskDescription: "원본 자료와 결과물을 대조해 사실성, 요청 충족, 출력 형식, 금지사항을 항목별로 검사하고 승인 또는 수정 요청을 결정한다.",
    desiredOutput: "APPROVED 또는 REJECTED 판정, 실패 항목, 근거 위치, 수정 지시를 포함한 검수 보고서",
    requiredEvidence: "결과물의 주장마다 제공된 원본 근거가 있는지 확인하고 직접 검증과 추정을 구분한다.",
    outputStyle: "판정을 첫 줄에 표시하고, 문제 위치·이유·수정 방법을 짧고 구체적인 목록으로 작성한다.",
    prohibitions: "근거 없이 승인하거나 글을 대신 새로 쓰지 않는다. 원문에 없는 사실과 정책을 추가하지 않는다.",
    rewriteCriteria: "필수 근거 누락, 사용자 요구 누락, 형식 오류, 금지사항 위반 중 하나라도 있으면 반려한다.",
    approvalCriteria: "모든 필수 요구와 출력 스키마를 충족하고 중대한 사실 오류나 근거 누락이 없을 때만 승인한다.",
  },
  general: {
    key: "general",
    taskDescription: "입력을 확인하고 필요한 작업을 순서대로 수행한 뒤, 누락과 오류를 자체 점검해 결과를 전달한다.",
    desiredOutput: "사용자가 바로 확인하고 다음 단계에 전달할 수 있는 구조화된 결과",
    requiredEvidence: "결과의 근거와 확인하지 못한 내용을 구분하고 사용한 입력을 추적할 수 있게 한다.",
    outputStyle: "결론을 먼저 제시하고 세부 내용과 남은 문제를 명확한 항목으로 구분한다.",
    prohibitions: "비밀정보를 출력하거나 확인하지 않은 사실을 확정적으로 말하지 않는다.",
    rewriteCriteria: "필수 입력 누락, 결과 형식 불일치, 요청 미충족 또는 근거 없는 내용이 있으면 다시 작성한다.",
    approvalCriteria: "요청한 결과가 빠짐없이 있고 다음 담당자가 추가 해석 없이 사용할 수 있으면 승인한다.",
  },
};

export default function Page({ params }: { params:Promise<{id:string}> }) {
  const [id, setId] = useState("");
  const [character, setCharacter] = useState("writer");
  const [provider, setProvider] = useState("OPENAI");
  const [answers, setAnswers] = useState<GuideAnswers>(guideExamples.general);
  const router = useRouter();
  useEffect(() => { params.then((value) => setId(value.id)); }, [params]);
  const agent = useQuery({ queryKey:["agent", id], queryFn:() => api<Agent>(`/agents/${id}`), enabled:!!id });
  useEffect(() => { if (agent.data) { setCharacter(agent.data.characterKey); setProvider(agent.data.modelProvider); } }, [agent.data]);
  const credentials = useQuery({ queryKey:["credentials"], queryFn:() => api<Credential[]>("/llm-credentials") });
  const models = useQuery({ queryKey:["models", provider], queryFn:() => api<Model[]>(`/llm-models?provider=${provider}`) });
  const definition = useQuery({ queryKey:["definition", id], queryFn:() => api<Definition>(`/agents/${id}/definition`), enabled:!!id, retry:false });
  const save = useMutation({ mutationFn:(body:unknown) => api<Agent>(`/agents/${id}`, { method:"PATCH", body:JSON.stringify(body) }), onSuccess:() => agent.refetch() });
  const generate = useMutation({ mutationFn:(body:unknown) => api<Definition>(`/agents/${id}/generate-definition`, { method:"POST", body:JSON.stringify(body) }), onSuccess:() => definition.refetch() });
  const remove = useMutation({ mutationFn:() => api(`/agents/${id}`, { method:"DELETE" }), onSuccess:() => router.push("/home") });

  function submit(event:FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = new FormData(event.currentTarget);
    save.mutate({ name:form.get("name"), role:form.get("role"), personality:form.get("personality"), characterKey:character, systemPrompt:form.get("systemPrompt"), script:form.get("script"), guide:form.get("guide"), modelProvider:provider, modelName:form.get("modelName"), credentialId:form.get("credentialId") || null, temperature:Number(form.get("temperature")), maxOutputTokens:Number(form.get("maxOutputTokens")), timeoutSeconds:Number(form.get("timeoutSeconds")), providerOptions:{}, visibility:form.get("visibility") });
  }
  function makeDefinition(event:FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = new FormData(event.currentTarget);
    generate.mutate(Object.fromEntries(form.entries()));
  }
  if (!agent.data) return <AppShell kicker="AGENT EDITOR" title="구성원 불러오는 중"><p>{agent.error?.message}</p></AppShell>;
  const current = agent.data;
  return <AppShell kicker="AGENT EDITOR" title={`${current.name} 업무 설정`}>
    <div className="grid gap-6 xl:grid-cols-2">
      <form key={current.updatedAt as never} onSubmit={submit} className="space-y-4 rounded-3xl bg-white p-7 shadow-card">
        <CharacterPicker value={character} onChange={setCharacter}/>
        <div className="grid gap-3 sm:grid-cols-2"><Field label="이름"><input name="name" defaultValue={current.name} required/></Field><Field label="역할"><input name="role" defaultValue={current.role} required/></Field></div>
        <Field label="성격"><input name="personality" defaultValue={current.personality}/></Field>
        <Field label="해야 할 일"><textarea name="script" defaultValue={current.script} rows={5} required/></Field>
        <Field label="가이드"><textarea name="guide" defaultValue={current.guide} rows={4}/></Field>
        <Field label="시스템 지침"><textarea name="systemPrompt" defaultValue={current.systemPrompt} rows={3}/></Field>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Provider"><select value={provider} onChange={(event) => setProvider(event.target.value)}><option>OPENAI</option><option>ANTHROPIC</option><option>GOOGLE</option></select></Field>
          <Field label="Model"><select name="modelName" defaultValue={current.modelName}>{models.data?.map((model) => <option key={model.id} value={model.id}>{model.displayName}</option>)}</select></Field>
          <Field label="API 키"><select name="credentialId" defaultValue={current.credentialId ?? ""}><option value="">연결 안 함</option>{credentials.data?.filter((credential) => credential.provider === provider && credential.status === "ACTIVE").map((credential) => <option key={credential.id} value={credential.id}>{credential.maskedSecret}</option>)}</select></Field>
          <Field label="공개"><select name="visibility" defaultValue={current.visibility}><option>PRIVATE</option><option>FRIENDS</option><option>PUBLIC</option></select></Field>
          <Field label="Temperature"><input name="temperature" type="number" min="0" max="2" step="0.1" defaultValue={current.temperature}/></Field>
          <Field label="출력 토큰"><input name="maxOutputTokens" type="number" defaultValue={current.maxOutputTokens}/></Field>
          <Field label="Timeout(초)"><input name="timeoutSeconds" type="number" defaultValue={current.timeoutSeconds}/></Field>
        </div>
        <button className="w-full rounded-xl bg-ink p-3 font-bold text-white">구성원 저장</button>{save.isSuccess && <p className="text-leaf">저장했습니다.</p>}
      </form>
      <section className="space-y-5">
        <form key={answers.key} onSubmit={makeDefinition} className="space-y-4 rounded-3xl bg-cream p-7">
          <div><p className="text-xs font-black text-coral">질문에 답하면 파일은 Agentown이 작성합니다</p><h2 className="mt-1 text-xl font-black">agent.md · guide.md 자동 설계</h2><p className="mt-2 text-sm leading-6 text-stone-600">실제 기술 블로그의 Writer·Reviewer 운영 기준을 쉬운 질문으로 바꿨습니다. 아래 예시를 선택한 뒤 필요한 부분만 고치세요.</p></div>
          <div className="flex flex-wrap gap-2"><PresetButton onClick={() => setAnswers(guideExamples.writer)}>✍️ 글쓰기 예시</PresetButton><PresetButton onClick={() => setAnswers(guideExamples.reviewer)}>🔎 검수 예시</PresetButton><PresetButton onClick={() => setAnswers(guideExamples.general)}>🧩 일반 업무 예시</PresetButton></div>
          <Field label="1. 이 구성원은 무슨 일을 어떤 순서로 해야 하나요?" hint="예: 조사 자료 확인 → 독자 질문 정의 → 초안 작성 → 사실과 형식 자체 점검"><textarea name="taskDescription" rows={4} defaultValue={answers.taskDescription}/></Field>
          <Field label="2. 최종 결과물은 어떤 모습이어야 하나요?" hint="예: 제목·본문·근거·한계가 포함된 Markdown 문서"><textarea name="desiredOutput" rows={3} defaultValue={answers.desiredOutput}/></Field>
          <Field label="3. 반드시 확인해야 하는 근거는 무엇인가요?" hint="예: 공식 문서, 실제 명령과 출력, 제공된 조사 자료"><textarea name="requiredEvidence" rows={3} defaultValue={answers.requiredEvidence}/></Field>
          <Field label="4. 말투와 출력 형식은 어떻게 할까요?" hint="예: -다 설명체, 결론 먼저, H2로 구분"><textarea name="outputStyle" rows={3} defaultValue={answers.outputStyle}/></Field>
          <Field label="5. 절대 하면 안 되는 일은 무엇인가요?" hint="예: 가짜 경험·수치·출처 생성 금지, API 키 출력 금지"><textarea name="prohibitions" rows={3} defaultValue={answers.prohibitions}/></Field>
          <Field label="6. 어떤 경우 다시 작성해야 하나요?" hint="예: 근거 누락, 요청 미충족, 출력 형식 오류"><textarea name="rewriteCriteria" rows={3} defaultValue={answers.rewriteCriteria}/></Field>
          <Field label="7. 어떤 조건이면 완료로 승인할까요?" hint="예: 필수 요구를 모두 충족하고 중대한 사실 오류가 없을 때"><textarea name="approvalCriteria" rows={3} defaultValue={answers.approvalCriteria}/></Field>
          <button disabled={generate.isPending} className="w-full rounded-xl bg-coral p-3 font-bold text-white">답변으로 정의 파일 생성</button>
          {generate.error && <p className="text-sm text-red-600">{generate.error.message}</p>}
        </form>
        {definition.data && <div className="space-y-4 rounded-3xl bg-white p-6 shadow-card"><h3 className="font-black">agent.md</h3><pre className="max-h-72 overflow-auto whitespace-pre-wrap rounded-xl bg-stone-950 p-4 text-xs text-stone-100">{definition.data.agentMarkdown}</pre><h3 className="font-black">guide.md</h3><pre className="max-h-72 overflow-auto whitespace-pre-wrap rounded-xl bg-stone-950 p-4 text-xs text-stone-100">{definition.data.guideMarkdown}</pre><details><summary className="cursor-pointer font-bold">입력·출력 Schema 보기</summary><pre className="mt-3 overflow-auto rounded-xl bg-stone-100 p-4 text-xs">{JSON.stringify({input:definition.data.inputSchema, output:definition.data.outputSchema}, null, 2)}</pre></details></div>}
        <button onClick={() => remove.mutate()} className="text-sm font-bold text-red-600">구성원 삭제</button>
      </section>
    </div>
  </AppShell>;
}

function Field({ label, hint, children }: { label:string; hint?:string; children:React.ReactElement }) { return <label className="block text-sm font-bold">{label}{hint && <small className="mt-1 block font-normal leading-5 text-stone-500">{hint}</small>}<span className="mt-2 block [&>*]:w-full [&>*]:rounded-xl [&>*]:border [&>*]:p-3">{children}</span></label>; }
function PresetButton({ onClick, children }: { onClick:()=>void; children:React.ReactNode }) { return <button type="button" onClick={onClick} className="rounded-full border border-stone-300 bg-white px-4 py-2 text-xs font-black hover:border-coral">{children}</button>; }
