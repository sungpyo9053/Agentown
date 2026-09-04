"use client";

import { FormEvent, useMemo, useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AppShell, Panel } from "@/components/AppShell";
import { api } from "@/lib/api";

type Provider = "OPENAI" | "ANTHROPIC" | "GOOGLE";
type Model = { id:string; displayName:string };
type Credential = { id:string; provider:Provider; maskedSecret:string; status:string };
type QualityCheck = { key:string; label:string; passed:boolean; score:number; detail:string };
type Draft = {
  id:string; brandName:string; topic:string; audience:string; channel:"NAVER"|"WORDPRESS"|"OTHER";
  photoReferenceUrl?:string; photoNotes:string; title:string; bodyMarkdown:string; seoTitle:string; metaDescription:string;
  targetKeywords:string[]; evidenceUsed:string[]; warnings:string[]; generationSource:"AGENTOWN_AI"|"USER_AI"|"SAFE_TEMPLATE";
  provider:Provider; model:string; inputTokens:number; outputTokens:number; qualityScore:number; qualityChecks:QualityCheck[];
  status:"DRAFT"|"APPROVED"; approvedAt?:string; createdAt:string; updatedAt:string;
};
type Usage = { used:number; limit:number; remaining:number };

export default function ContentOperationsPage() {
  const queryClient = useQueryClient();
  const [provider, setProvider] = useState<Provider>("OPENAI");
  const [usePersonalAi, setUsePersonalAi] = useState(false);
  const [credentialId, setCredentialId] = useState("");
  const [active, setActive] = useState<Draft | null>(null);
  const [copyNotice, setCopyNotice] = useState("");
  const [evidenceConfirmed, setEvidenceConfirmed] = useState(false);
  const [photoRightsConfirmed, setPhotoRightsConfirmed] = useState(false);
  const drafts = useQuery({ queryKey:["content-drafts"], queryFn:() => api<Draft[]>("/content-operations/drafts") });
  const usage = useQuery({ queryKey:["content-usage"], queryFn:() => api<Usage>("/content-operations/usage") });
  const models = useQuery({ queryKey:["content-models",provider], queryFn:() => api<Model[]>(`/llm-models?provider=${provider}`), enabled:usePersonalAi });
  const credentials = useQuery({ queryKey:["credentials"], queryFn:() => api<Credential[]>("/llm-credentials"), enabled:usePersonalAi });
  const selectedCredentials = credentials.data?.filter(item => item.provider === provider && item.status === "ACTIVE") ?? [];

  const generate = useMutation({
    mutationFn:(body:unknown) => api<Draft>("/content-operations/drafts/generate", {
      method:"POST", headers:{"Idempotency-Key":idempotencyKey()}, body:JSON.stringify(body),
    }),
    onSuccess:(draft) => { setActive(draft); setEvidenceConfirmed(false); setPhotoRightsConfirmed(false); queryClient.invalidateQueries({queryKey:["content-drafts"]}); queryClient.invalidateQueries({queryKey:["content-usage"]}); },
  });
  const save = useMutation({
    mutationFn:(draft:Draft) => api<Draft>(`/content-operations/drafts/${draft.id}`, { method:"PATCH", body:JSON.stringify({
      title:draft.title, bodyMarkdown:draft.bodyMarkdown, seoTitle:draft.seoTitle, metaDescription:draft.metaDescription, targetKeywords:draft.targetKeywords,
    })}),
    onSuccess:(draft) => { setActive(draft); queryClient.invalidateQueries({queryKey:["content-drafts"]}); },
  });
  const approve = useMutation({
    mutationFn:(draft:Draft) => api<Draft>(`/content-operations/drafts/${draft.id}/approve`, { method:"POST", body:JSON.stringify({evidenceConfirmed,photoRightsConfirmed}) }),
    onSuccess:(draft) => { setActive(draft); queryClient.invalidateQueries({queryKey:["content-drafts"]}); },
  });

  function submit(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    generate.mutate({
      brandName:form.get("brandName"), topic:form.get("topic"), audience:form.get("audience"), channel:"NAVER",
      sourceNotes:form.get("sourceNotes"), evidenceNotes:form.get("evidenceNotes"), photoReferenceUrl:form.get("photoReferenceUrl") || null,
      photoNotes:form.get("photoNotes"), styleNotes:form.get("styleNotes"), provider,
      model:usePersonalAi ? form.get("model") : "gpt-5.6-luna", credentialId:usePersonalAi ? credentialId || null : null, usePersonalAi,
    });
  }

  async function copyTitle() {
    if (!active) return;
    await navigator.clipboard.writeText(active.title);
    setCopyNotice("제목을 복사했습니다.");
  }

  async function copyBody() {
    if (!active) return;
    const plain = markdownToPlainText(active.bodyMarkdown);
    const html = markdownToRichHtml(active.bodyMarkdown);
    try {
      if (typeof ClipboardItem !== "undefined" && navigator.clipboard.write) {
        await navigator.clipboard.write([new ClipboardItem({
          "text/html":new Blob([html],{type:"text/html"}),
          "text/plain":new Blob([plain],{type:"text/plain"}),
        })]);
      } else await navigator.clipboard.writeText(plain);
      setCopyNotice("본문을 서식과 함께 복사했습니다. 네이버 글쓰기 본문에 붙여넣으세요.");
    } catch {
      await navigator.clipboard.writeText(plain);
      setCopyNotice("본문을 일반 텍스트로 복사했습니다.");
    }
  }

  return <AppShell kicker="CONTENT OPERATIONS" title="콘텐츠 운영">
    <div className="grid gap-2 sm:grid-cols-3">
      <Stat label="이번 달 Agentown AI" value={usage.data ? `${usage.data.used}/${usage.data.limit}` : "-"}/>
      <Stat label="작성 중" value={String(drafts.data?.filter(item=>item.status==="DRAFT").length ?? 0)}/>
      <Stat label="발행 준비" value={String(drafts.data?.filter(item=>item.status==="APPROVED").length ?? 0)}/>
    </div>

    <div className="mt-6 grid gap-6 xl:grid-cols-[390px_minmax(0,1fr)]">
      <div className="space-y-6">
        <form onSubmit={submit} className="space-y-4 border border-hairline bg-white p-6">
          <div><p className="text-xs font-semibold tracking-[.16em] text-coral">NEW NAVER CONTENT</p><h2 className="mt-2 text-xl font-semibold">사진과 현장 메모로 초안 만들기</h2><p className="mt-2 text-sm leading-6 text-mute">제공한 사실 안에서만 작성합니다. 생성 후 직접 수정하고 승인해야 복사할 수 있습니다.</p></div>
          <Field label="업체·블로그 이름"><input name="brandName" required maxLength={120}/></Field>
          <Field label="글 주제"><input name="topic" required maxLength={200} placeholder="예: 32평 아파트 주방 리모델링"/></Field>
          <Field label="주요 독자"><input name="audience" required maxLength={300} placeholder="예: 수원에서 구축 아파트 리모델링을 준비하는 가족"/></Field>
          <Field label="실제 현장 메모" hint="시공 전 문제, 선택한 자재, 이유, 기간과 결과 중 공개 가능한 사실을 적어주세요."><textarea name="sourceNotes" required rows={6}/></Field>
          <Field label="가격·자재·일정 근거" hint="견적서·제품명·실측값처럼 확인 가능한 내용만 적어주세요."><textarea name="evidenceNotes" rows={4}/></Field>
          <Field label="구글 드라이브 사진 폴더"><input name="photoReferenceUrl" type="url" placeholder="https://drive.google.com/…"/></Field>
          <Field label="사진 순서와 설명"><textarea name="photoNotes" rows={4} placeholder="1. 시공 전 주방 / 2. 철거 후 배관 / 3. 완공 사진"/></Field>
          <Field label="기존 블로그 말투"><textarea name="styleNotes" rows={3} placeholder="예: 전문용어는 풀어서 설명하고 과장 표현은 쓰지 않음"/></Field>
          <div className="border border-hairline bg-cloud p-4"><p className="text-sm font-semibold">기본 · Agentown 제공 AI</p><p className="mt-1 text-xs leading-5 text-mute">별도 API 키 없이 바로 사용합니다. AI 장애 시에는 확인 필요 항목이 표시된 안전 템플릿을 만듭니다.</p><label className="mt-3 flex items-center gap-2 text-sm"><input type="checkbox" checked={usePersonalAi} onChange={event=>setUsePersonalAi(event.target.checked)}/><span>내가 연결한 AI 사용</span></label>{usePersonalAi&&<div className="mt-3 grid gap-2"><select aria-label="AI 공급자" value={provider} onChange={event=>{setProvider(event.target.value as Provider);setCredentialId("");}}><option value="OPENAI">OpenAI</option><option value="ANTHROPIC">Claude</option><option value="GOOGLE">Gemini</option></select><select name="model" aria-label="AI 모델" required><option value="">모델 선택</option>{models.data?.map(item=><option key={item.id} value={item.id}>{item.displayName}</option>)}</select><select aria-label="연결 완료 AI" value={credentialId} onChange={event=>setCredentialId(event.target.value)} required><option value="">연결 선택</option>{selectedCredentials.map(item=><option key={item.id} value={item.id}>{item.maskedSecret}</option>)}</select><Link href="/settings/credentials" className="text-xs font-semibold underline">AI 연결 관리</Link></div>}</div>
          <button disabled={generate.isPending||(usePersonalAi&&!credentialId)} className="w-full bg-ink p-4 text-sm font-semibold text-white">{generate.isPending?"근거를 확인하며 작성 중…":"콘텐츠 초안 만들기"}</button>
          {generate.error&&<p className="bg-red-50 p-3 text-sm text-sale">{generate.error.message}</p>}
        </form>

        <Panel title="최근 콘텐츠">
          <div className="divide-y divide-hairline">{drafts.data?.map(item=><button type="button" key={item.id} onClick={()=>{setActive(item);setEvidenceConfirmed(false);setPhotoRightsConfirmed(false);}} className="block w-full py-3 text-left"><span className="block text-sm font-medium">{item.title}</span><span className="mt-1 block text-xs text-mute">{item.status==="APPROVED"?"발행 준비":"작성 중"} · {new Date(item.updatedAt).toLocaleDateString("ko-KR")}</span></button>)}{drafts.data?.length===0&&<p className="py-5 text-sm text-mute">아직 생성한 콘텐츠가 없습니다.</p>}</div>
        </Panel>
      </div>

      <section>
        {!active&&<div className="border-2 border-dashed border-hairline bg-white p-12 text-center"><p className="text-5xl">✍️</p><h2 className="mt-5 text-2xl font-semibold">초안과 품질 검사가 여기에 표시됩니다</h2><p className="mt-3 text-sm leading-6 text-mute">일반 GPT 답변과 달리 입력 근거, 현장·사진 반영, 읽기 구조와 발행 안전을 함께 확인합니다.</p></div>}
        {active&&<Editor draft={active} onChange={setActive} onSave={()=>save.mutate(active)} saving={save.isPending} saveError={save.error}/>}
        {active&&<div className="mt-6 grid gap-6 lg:grid-cols-2">
          <Panel title={`발행 준비도 ${active.qualityScore}/100`}><div className="space-y-3">{active.qualityChecks.map(check=><div key={check.key} className="flex items-start justify-between gap-3 border-b border-hairline pb-3"><div><b className="text-sm">{check.passed?"✓":"△"} {check.label}</b><p className="mt-1 text-xs text-mute">{check.detail}</p></div><span className={check.passed?"text-emerald-700":"text-amber-700"}>{check.score}</span></div>)}</div>{active.warnings.length>0&&<div className="mt-4 bg-amber-50 p-4 text-sm text-amber-900">{active.warnings.map(item=><p key={item}>• {item}</p>)}</div>}</Panel>
          <Panel title="검수하고 발행 준비"><p className="text-sm leading-6 text-mute">내용을 저장한 뒤 근거와 사진 권리를 직접 확인하세요. 안전 템플릿의 빈칸이나 준비도 70점 미만 글은 승인할 수 없습니다.</p><label className="mt-4 flex gap-2 text-sm"><input type="checkbox" checked={evidenceConfirmed} onChange={event=>setEvidenceConfirmed(event.target.checked)}/><span>가격·자재·기간과 현장 설명을 확인했습니다.</span></label><label className="mt-3 flex gap-2 text-sm"><input type="checkbox" checked={photoRightsConfirmed} onChange={event=>setPhotoRightsConfirmed(event.target.checked)}/><span>사진 사용 권한과 공개 범위를 확인했습니다.</span></label><button disabled={active.status==="APPROVED"||approve.isPending||!evidenceConfirmed||!photoRightsConfirmed} onClick={()=>approve.mutate(active)} className="mt-5 w-full bg-leaf p-3 text-sm font-semibold text-white disabled:opacity-40">{active.status==="APPROVED"?"발행 준비 승인됨":approve.isPending?"승인 확인 중…":"발행 준비 승인"}</button>{approve.error&&<p className="mt-3 bg-red-50 p-3 text-sm text-sale">{approve.error.message}</p>}</Panel>
        </div>}
        {active?.status==="APPROVED"&&<section className="mt-6 border border-hairline bg-ink p-6 text-white"><p className="text-xs font-semibold tracking-[.16em] text-coral">NAVER PASTE READY</p><h2 className="mt-2 text-2xl font-semibold">네이버 블로그에 바로 붙여넣기</h2><p className="mt-2 text-sm leading-6 text-stone-300">제목과 본문을 차례로 복사하세요. 본문은 소제목·문단 서식을 함께 복사하며, 사진 위치 표시는 직접 사진으로 교체합니다.</p><div className="mt-5 flex flex-wrap gap-2"><button onClick={copyTitle} className="bg-white px-5 py-3 text-sm font-semibold text-ink">1. 제목 복사</button><button onClick={copyBody} className="bg-coral px-5 py-3 text-sm font-semibold text-white">2. 본문 서식 복사</button><a href="https://blog.naver.com/" target="_blank" rel="noopener noreferrer" className="border border-white/30 px-5 py-3 text-sm font-semibold">3. 네이버 블로그 열기</a></div>{copyNotice&&<p role="status" className="mt-4 text-sm text-emerald-300">{copyNotice}</p>}{active.photoReferenceUrl&&<a href={active.photoReferenceUrl} target="_blank" rel="noopener noreferrer" className="mt-5 block text-sm font-semibold underline">사진 폴더 열기 →</a>}<pre className="mt-3 whitespace-pre-wrap border border-white/15 p-4 text-xs text-stone-300">{active.photoNotes||"등록된 사진 순서가 없습니다."}</pre></section>}
      </section>
    </div>
  </AppShell>;
}

function Editor({draft,onChange,onSave,saving,saveError}:{draft:Draft;onChange:(draft:Draft)=>void;onSave:()=>void;saving:boolean;saveError:Error|null}) {
  const preview = useMemo(()=>markdownToRichHtml(draft.bodyMarkdown),[draft.bodyMarkdown]);
  const disabled=draft.status==="APPROVED";
  return <div className="border border-hairline bg-white"><div className="flex flex-wrap items-center justify-between gap-3 border-b border-hairline p-5"><div><p className="text-xs font-semibold text-coral">{sourceLabel(draft.generationSource)} · {draft.provider}/{draft.model}</p><h2 className="mt-1 text-xl font-semibold">초안 편집</h2></div><button disabled={disabled||saving} onClick={onSave} className="bg-ink px-5 py-3 text-sm font-semibold text-white disabled:opacity-40">{saving?"저장 중…":"수정 내용 저장"}</button></div><div className="grid lg:grid-cols-2"><div className="space-y-4 border-b border-hairline p-6 lg:border-b-0 lg:border-r"><Field label="제목"><input disabled={disabled} value={draft.title} onChange={event=>onChange({...draft,title:event.target.value})}/></Field><Field label="본문 Markdown"><textarea disabled={disabled} rows={24} value={draft.bodyMarkdown} onChange={event=>onChange({...draft,bodyMarkdown:event.target.value})}/></Field><Field label="검색 제목"><input disabled={disabled} value={draft.seoTitle} onChange={event=>onChange({...draft,seoTitle:event.target.value})}/></Field><Field label="검색 설명"><textarea disabled={disabled} rows={3} value={draft.metaDescription} onChange={event=>onChange({...draft,metaDescription:event.target.value})}/></Field><Field label="키워드 (쉼표 구분)"><input disabled={disabled} value={draft.targetKeywords.join(", ")} onChange={event=>onChange({...draft,targetKeywords:event.target.value.split(",").map(item=>item.trim()).filter(Boolean)})}/></Field>{saveError&&<p className="bg-red-50 p-3 text-sm text-sale">{saveError.message}</p>}</div><div className="p-6"><p className="text-xs font-semibold tracking-[.16em] text-mute">NAVER PREVIEW</p><h1 className="mt-4 text-3xl font-semibold leading-tight">{draft.title}</h1><article className="mt-6 space-y-4 text-[15px] leading-8 text-stone-700" dangerouslySetInnerHTML={{__html:preview}}/></div></div></div>;
}

function Field({label,hint,children}:{label:string;hint?:string;children:React.ReactElement}) { return <label className="block text-sm font-medium"><span>{label}</span>{hint&&<small className="mt-1 block font-normal leading-5 text-mute">{hint}</small>}<span className="mt-2 block [&>*]:w-full [&>*]:border [&>*]:border-hairline [&>*]:bg-white [&>*]:p-3 disabled:[&>*]:bg-cloud">{children}</span></label>; }
function Stat({label,value}:{label:string;value:string}) { return <div className="border border-hairline bg-white p-5"><p className="text-sm text-mute">{label}</p><p className="mt-2 text-3xl font-semibold">{value}</p></div>; }
function sourceLabel(source:Draft["generationSource"]) { return source==="AGENTOWN_AI"?"Agentown AI":source==="USER_AI"?"내 AI":"안전 템플릿"; }
function escapeHtml(value:string) { return value.replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/\"/g,"&quot;"); }
function markdownToPlainText(markdown:string) { return markdown.replace(/^#{2,6}\s+/gm,"").replace(/^[-*]\s+/gm,"• ").replace(/\*\*(.*?)\*\*/g,"$1").trim(); }
function markdownToRichHtml(markdown:string) {
  const blocks=markdown.split(/\n\s*\n/).map(item=>item.trim()).filter(Boolean);
  return blocks.map(block=>{
    const safe=escapeHtml(block);
    if(block.startsWith("### "))return `<h3>${escapeHtml(block.slice(4))}</h3>`;
    if(block.startsWith("## "))return `<h2>${escapeHtml(block.slice(3))}</h2>`;
    if(/^\[사진:/.test(block))return `<p><strong>${safe}</strong></p>`;
    if(block.split("\n").every(line=>/^[-*]\s+/.test(line)))return `<ul>${block.split("\n").map(line=>`<li>${escapeHtml(line.replace(/^[-*]\s+/,""))}</li>`).join("")}</ul>`;
    return `<p>${safe.replace(/\n/g,"<br>")}</p>`;
  }).join("");
}
function idempotencyKey() { return crypto.randomUUID(); }
