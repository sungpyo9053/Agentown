"use client";

import Link from "next/link";
import { useState } from "react";
import {
  ArrowRight,
  Check,
  ChevronRight,
  Download,
  FileSpreadsheet,
  GitBranch,
  MessageSquareText,
  Play,
  ShieldCheck,
  Sparkles,
} from "lucide-react";
import { blockFontClassName } from "@/lib/fonts";

type Scenario = {
  id: "faq" | "csv" | "notion";
  label: string;
  eyebrow: string;
  prompt: string;
  nodes: Array<{ name: string; detail: string; kind: "input" | "logic" | "ai" | "approval" | "output" }>;
  resultTitle: string;
  result: string;
  note: string;
};

const scenarios: Scenario[] = [
  {
    id: "faq",
    label: "FAQ 답변",
    eyebrow: "근거가 없으면 사람에게",
    prompt: "고객 질문을 FAQ에서 검색하고, 근거가 있을 때만 답변 초안을 만들어줘.",
    nodes: [
      { name: "고객 질문", detail: "manual.trigger", kind: "input" },
      { name: "FAQ 검색", detail: "knowledge.search", kind: "logic" },
      { name: "근거 확인", detail: "condition.branch", kind: "logic" },
      { name: "답변 초안", detail: "ai.generate", kind: "ai" },
      { name: "완료", detail: "workflow.end", kind: "output" },
    ],
    resultTitle: "근거가 부족해 담당자 확인으로 분기했습니다",
    result: '{ "needsAssigneeReview": true, "externalCallPerformed": false }',
    note: "근거 없는 답변을 만들지 않고 안전한 분기로 종료합니다.",
  },
  {
    id: "csv",
    label: "CSV 비교",
    eyebrow: "AI 없이 정확하게",
    prompt: "두 CSV를 id 기준으로 비교해 추가·수정·삭제된 행을 JSON으로 정리해줘.",
    nodes: [
      { name: "CSV 입력", detail: "manual.trigger", kind: "input" },
      { name: "행 비교", detail: "data.csv.compare", kind: "logic" },
      { name: "결과 정리", detail: "template.render", kind: "logic" },
      { name: "완료", detail: "workflow.end", kind: "output" },
    ],
    resultTitle: "변경된 행 3건을 찾았습니다",
    result: '{ "added": 1, "modified": 1, "removed": 1 }',
    note: "결정론적 작업에는 AI 대신 검증 가능한 Function을 사용합니다.",
  },
  {
    id: "notion",
    label: "Notion 등록",
    eyebrow: "외부 전송 전 승인",
    prompt: "회의 요약을 검토받은 뒤 승인된 내용만 Notion 데이터베이스에 등록해줘.",
    nodes: [
      { name: "회의 요약", detail: "manual.trigger", kind: "input" },
      { name: "내용 정리", detail: "template.render", kind: "logic" },
      { name: "사람 승인", detail: "human.approval", kind: "approval" },
      { name: "Notion 등록", detail: "notion.page.create", kind: "output" },
    ],
    resultTitle: "승인을 기다리고 있습니다",
    result: '{ "status": "WAITING_APPROVAL", "externalCallPerformed": false }',
    note: "연결과 전송은 사용자가 인증하고 승인한 뒤에만 실행합니다.",
  },
];

const kindStyle: Record<Scenario["nodes"][number]["kind"], string> = {
  input: "border-zinc-300 bg-white",
  logic: "border-zinc-900 bg-zinc-900 text-white",
  ai: "border-indigo-300 bg-indigo-50",
  approval: "border-amber-300 bg-amber-50",
  output: "border-emerald-300 bg-emerald-50",
};

export function LandingExperience() {
  const [selectedId, setSelectedId] = useState<Scenario["id"]>("faq");
  const selected = scenarios.find((scenario) => scenario.id === selectedId) ?? scenarios[0];

  return <>
    <section className="overflow-hidden bg-white">
      <div className="mx-auto grid max-w-[1440px] gap-12 px-6 pb-20 pt-16 md:px-10 md:pt-24 lg:grid-cols-[0.88fr_1.12fr] lg:items-center lg:gap-16 lg:pb-28">
        <div>
          <p className="inline-flex items-center gap-2 rounded-pill border border-hairline px-4 py-2 text-xs font-semibold text-charcoal">
            <span className="h-2 w-2 rounded-full bg-leaf" /> 제한 베타 · 실제 실행까지 검증
          </p>
          <h1 className={`${blockFontClassName} mt-7 text-[clamp(3.4rem,7vw,7.6rem)] leading-[.9] text-ink`}>
            말 한 줄이<br />실행 가능한<br /><span className="text-mute">AI 팀이 됩니다.</span>
          </h1>
          <p className="mt-8 max-w-xl text-lg leading-8 text-charcoal">
            하고 싶은 업무만 설명하세요. Agentown이 필요한 에이전트와 도구, 조건과 승인 단계를 설계하고 실제 결과까지 시험합니다.
          </p>
          <div className="mt-9 flex flex-wrap gap-3">
            <Link href="/signup" className="inline-flex items-center gap-2 rounded-pill bg-ink px-7 py-4 text-sm font-semibold text-white transition hover:opacity-80 active:scale-95">
              무료로 시작하기 <ArrowRight className="h-4 w-4" />
            </Link>
            <a href="#product-demo" className="inline-flex items-center gap-2 rounded-pill border border-ink px-7 py-4 text-sm font-semibold text-ink transition hover:bg-cloud">
              작동 방식 보기 <Play className="h-4 w-4" />
            </a>
          </div>
          <ul className="mt-9 grid gap-3 text-sm text-charcoal sm:grid-cols-3 lg:grid-cols-1 xl:grid-cols-3">
            {["그래프 자동 설계", "실행 전 사람 승인", "패키지 다운로드"].map((item) => <li key={item} className="flex items-center gap-2"><Check className="h-4 w-4 text-leaf" />{item}</li>)}
          </ul>
        </div>

        <HeroProductWindow />
      </div>
    </section>

    <section className="border-y border-hairline-soft bg-cloud">
      <div className="mx-auto grid max-w-[1440px] gap-px bg-hairline-soft md:grid-cols-3">
        <Proof label="자연어에서 실행까지" value="한 작업공간에서" />
        <Proof label="FAQ · CSV" value="운영 E2E 통과" />
        <Proof label="외부 전송" value="승인 전에는 실행 안 함" />
      </div>
    </section>

    <section className="mx-auto max-w-[1440px] px-6 py-24 md:px-10 md:py-32">
      <p className="text-xs font-semibold uppercase tracking-[.2em] text-mute">왜 Agentown인가요?</p>
      <h2 className={`${blockFontClassName} mt-5 max-w-5xl text-[clamp(2.8rem,6vw,6rem)] leading-[.94] text-ink`}>
        챗봇의 답변이 아니라,<br />계속 실행할 수 있는 업무 시스템을 만듭니다.
      </h2>
      <div className="mt-16 grid gap-5 md:grid-cols-3">
        <ValueCard number="01" title="설명하면 구조화" body="모호한 요청은 확인하고, 목적에 맞는 Agent Graph와 입력·출력 계약으로 바꿉니다." icon={<MessageSquareText />} />
        <ValueCard number="02" title="흐름대로 실행" body="노드 연결, 조건, 데이터 바인딩과 스키마를 실제로 평가해 맞는 결과만 성공으로 처리합니다." icon={<GitBranch />} />
        <ValueCard number="03" title="검증하고 가져가기" body="샘플 실행 기록을 확인하고, 버전이 고정된 Agent Package를 내려받을 수 있습니다." icon={<Download />} />
      </div>
    </section>

    <section id="product-demo" className="scroll-mt-20 bg-[#111] text-white">
      <div className="mx-auto max-w-[1440px] px-6 py-24 md:px-10 md:py-32">
        <div className="grid gap-8 lg:grid-cols-[.75fr_1.25fr] lg:items-end">
          <div>
            <p className="text-xs font-semibold uppercase tracking-[.2em] text-zinc-400">제품 데모</p>
            <h2 className={`${blockFontClassName} mt-5 text-[clamp(3rem,6vw,6rem)] leading-[.92]`}>업무를 고르면<br />작동 흐름이<br />보입니다.</h2>
          </div>
          <p className="max-w-2xl text-base leading-7 text-zinc-300 lg:justify-self-end">
            아래는 지원 범위를 감추지 않은 실제 운영 흐름 예시입니다. 업무 성격에 따라 AI, 결정론적 Function, 사람 승인을 다르게 조합합니다.
          </p>
        </div>

        <div className="mt-14 flex flex-wrap gap-2" role="tablist" aria-label="업무 예시">
          {scenarios.map((scenario) => <button key={scenario.id} type="button" role="tab" aria-selected={selected.id === scenario.id} onClick={() => setSelectedId(scenario.id)}
            className={`rounded-pill px-5 py-3 text-sm font-semibold transition ${selected.id === scenario.id ? "bg-white text-ink" : "border border-zinc-700 text-zinc-300 hover:border-zinc-400"}`}>
            {scenario.label}
          </button>)}
        </div>

        <div className="mt-6 overflow-hidden rounded-[1.75rem] border border-zinc-700 bg-zinc-950" role="tabpanel">
          <div className="flex items-center justify-between border-b border-zinc-800 px-5 py-4">
            <div className="flex gap-1.5"><i className="h-2.5 w-2.5 rounded-full bg-zinc-700"/><i className="h-2.5 w-2.5 rounded-full bg-zinc-700"/><i className="h-2.5 w-2.5 rounded-full bg-zinc-700"/></div>
            <span className="text-xs text-zinc-500">Agentown · 에이전트 개발</span>
          </div>
          <div className="grid lg:grid-cols-[.72fr_1.28fr]">
            <div className="border-b border-zinc-800 p-6 lg:border-b-0 lg:border-r lg:p-8">
              <span className="text-xs font-semibold text-zinc-500">요청</span>
              <p className="mt-4 text-xl font-semibold leading-8">“{selected.prompt}”</p>
              <div className="mt-8 border-t border-zinc-800 pt-6">
                <span className="text-xs font-semibold text-zinc-500">설계 판단</span>
                <p className="mt-2 text-sm text-zinc-300">{selected.eyebrow}</p>
              </div>
              <div className="mt-8 flex items-center justify-between rounded-xl bg-zinc-900 px-4 py-3 text-sm">
                <span>Version 1</span><span className="flex items-center gap-1.5 text-emerald-400"><Check className="h-4 w-4"/> 테스트 가능</span>
              </div>
            </div>
            <div className="bg-zinc-100 p-5 text-ink md:p-8">
              <div className="overflow-x-auto pb-3">
                <div className="flex min-w-max items-center gap-2">
                  {selected.nodes.map((node, index) => <div key={`${selected.id}-${node.name}`} className="contents">
                    <div className={`w-36 rounded-xl border p-3 ${kindStyle[node.kind]}`}>
                      <b className="block text-sm">{node.name}</b><span className="mt-1 block text-[10px] opacity-60">{node.detail}</span>
                    </div>
                    {index < selected.nodes.length - 1 && <ChevronRight className="h-5 w-5 shrink-0 text-zinc-400" />}
                  </div>)}
                </div>
              </div>
              <div className="mt-7 rounded-2xl border border-zinc-300 bg-white p-5">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <span className="text-xs font-semibold uppercase tracking-[.16em] text-zinc-500">실행 결과</span>
                  <span className={`rounded-pill px-3 py-1 text-xs font-bold ${selected.id === "notion" ? "bg-amber-100 text-amber-800" : "bg-emerald-100 text-emerald-800"}`}>{selected.id === "notion" ? "WAITING_APPROVAL" : "SUCCEEDED"}</span>
                </div>
                <h3 className="mt-4 text-lg font-bold">{selected.resultTitle}</h3>
                <pre className="mt-4 overflow-x-auto rounded-xl bg-zinc-950 p-4 text-xs leading-6 text-zinc-200">{selected.result}</pre>
                <p className="mt-4 text-sm leading-6 text-charcoal">{selected.note}</p>
              </div>
            </div>
          </div>
        </div>
        <p className="mt-4 text-xs text-zinc-500">이해를 돕기 위해 실제 제품 구조와 검증된 실행 결과를 개인정보 없이 재구성한 데모입니다.</p>
      </div>
    </section>

    <section className="mx-auto max-w-[1440px] px-6 py-24 md:px-10 md:py-32">
      <div className="grid gap-16 lg:grid-cols-[.72fr_1.28fr]">
        <div className="lg:sticky lg:top-28 lg:self-start">
          <p className="text-xs font-semibold uppercase tracking-[.2em] text-mute">작동 방식</p>
          <h2 className={`${blockFontClassName} mt-5 text-[clamp(3rem,5vw,5.5rem)] leading-[.94]`}>처음부터<br />운영까지<br />네 단계.</h2>
          <Link href="/login?next=%2Fdevelop" className="mt-8 inline-flex items-center gap-2 rounded-pill bg-ink px-6 py-3.5 text-sm font-semibold text-white">직접 만들어보기 <ArrowRight className="h-4 w-4"/></Link>
        </div>
        <ol className="border-t border-hairline">
          <Step number="01" title="업무를 자연어로 설명합니다" body="기술 용어 없이 해야 할 일, 입력 자료와 원하는 결과를 적습니다." />
          <Step number="02" title="Agentown이 실행 그래프를 설계합니다" body="AI가 필요한 부분과 일반 Function으로 처리할 부분, 조건과 승인 단계를 나눕니다." />
          <Step number="03" title="설계를 승인하고 샘플로 시험합니다" body="실제 외부 전송 전에 단계별 입력·출력과 실패 분기를 먼저 확인합니다." />
          <Step number="04" title="버전을 보존하고 패키지로 가져갑니다" body="수정 전 버전을 남기고, 승인된 버전의 실행 패키지와 계약 파일을 내려받습니다." />
        </ol>
      </div>
    </section>

    <section className="bg-cloud">
      <div className="mx-auto grid max-w-[1440px] gap-12 px-6 py-24 md:px-10 lg:grid-cols-2 lg:items-center lg:py-28">
        <div className="flex aspect-[4/3] items-center justify-center rounded-[2rem] bg-white p-8 md:p-14">
          <div className="w-full max-w-md">
            <div className="flex items-center gap-3"><ShieldCheck className="h-10 w-10"/><span className="font-bold">실행 전 안전 확인</span></div>
            <div className="mt-8 space-y-3">
              {["입력·출력 스키마 검증", "근거 부족 시 사람 확인 분기", "외부 전송 전 명시적 승인", "실패·재시도 실행 기록 보존"].map((item) => <div key={item} className="flex items-center justify-between border-b border-hairline-soft pb-3 text-sm"><span>{item}</span><Check className="h-4 w-4 text-leaf"/></div>)}
            </div>
          </div>
        </div>
        <div>
          <p className="text-xs font-semibold uppercase tracking-[.2em] text-mute">통제 가능한 자동화</p>
          <h2 className={`${blockFontClassName} mt-5 text-[clamp(3rem,5.5vw,6rem)] leading-[.94]`}>모르는 사이에<br />실행되지 않도록.</h2>
          <p className="mt-7 max-w-xl text-base leading-7 text-charcoal">Agentown은 성공처럼 보이는 실패를 숨기지 않습니다. 스키마를 통과하고 요구한 분기로 끝난 실행만 성공으로 기록합니다.</p>
        </div>
      </div>
    </section>

    <section className="bg-ink px-6 py-24 text-center text-white md:px-10 md:py-32">
      <Sparkles className="mx-auto h-8 w-8" />
      <h2 className={`${blockFontClassName} mx-auto mt-6 max-w-5xl text-[clamp(3.2rem,7vw,7.5rem)] leading-[.9]`}>첫 번째 AI 팀을<br />말로 만들어보세요.</h2>
      <p className="mx-auto mt-7 max-w-xl text-base leading-7 text-zinc-300">제한 베타에서 자연어 설계, 샘플 실행과 패키지 다운로드까지 직접 확인할 수 있습니다.</p>
      <Link href="/signup" className="mt-9 inline-flex items-center gap-2 rounded-pill bg-white px-8 py-4 text-sm font-semibold text-ink transition hover:bg-zinc-200">무료로 시작하기 <ArrowRight className="h-4 w-4"/></Link>
    </section>
  </>;
}

function HeroProductWindow() {
  return <div className="relative">
    <div className="absolute -inset-12 -z-10 rounded-full bg-zinc-100 blur-3xl" />
    <div className="overflow-hidden rounded-[1.75rem] border border-zinc-300 bg-white">
      <div className="flex items-center justify-between border-b border-hairline-soft px-4 py-3">
        <div className="flex gap-1.5"><i className="h-2.5 w-2.5 rounded-full bg-zinc-200"/><i className="h-2.5 w-2.5 rounded-full bg-zinc-200"/><i className="h-2.5 w-2.5 rounded-full bg-zinc-200"/></div>
        <span className="text-[10px] font-semibold text-mute">에이전트 개발 · Version 1</span>
      </div>
      <div className="grid min-h-[31rem] grid-cols-[5rem_1fr] sm:grid-cols-[8rem_1fr]">
        <div className="border-r border-hairline-soft bg-cloud p-3 sm:p-4">
          <span className="text-[10px] font-bold">AGENTOWN</span>
          <div className="mt-8 space-y-2 text-[10px] text-mute">
            <p className="rounded-lg bg-white px-2 py-2 text-ink">설계</p><p className="px-2 py-2">팀</p><p className="px-2 py-2">테스트</p><p className="px-2 py-2">버전</p>
          </div>
        </div>
        <div className="p-4 sm:p-6">
          <p className="text-[10px] font-semibold uppercase tracking-[.16em] text-mute">FAQ 기반 고객 답변</p>
          <div className="mt-4 rounded-xl bg-cloud p-4 text-xs leading-5">“FAQ 근거가 있을 때만 답하고, 없으면 담당자에게 넘겨줘.”</div>
          <div className="mt-5 flex flex-col items-center">
            {[{title:"질문 받기",sub:"manual.trigger"},{title:"FAQ 검색",sub:"knowledge.search"},{title:"근거 확인",sub:"condition.branch"},{title:"답변 또는 담당자 확인",sub:"safe output"}].map((node, i) => <div key={node.title} className="contents">
              <div className={`w-full max-w-[17rem] rounded-xl border p-3 ${i === 2 ? "border-zinc-900 bg-zinc-900 text-white" : "border-zinc-300 bg-white"}`}><b className="block text-xs">{node.title}</b><span className="mt-1 block text-[9px] opacity-55">{node.sub}</span></div>
              {i < 3 && <span className="h-4 w-px bg-zinc-300"/>}
            </div>)}
          </div>
          <div className="mt-5 flex items-center justify-between rounded-xl bg-emerald-50 p-3 text-xs"><span className="font-semibold">샘플 실행 완료</span><span className="text-emerald-700">SUCCEEDED</span></div>
        </div>
      </div>
    </div>
    <div className="absolute -bottom-5 -right-3 rounded-xl border border-zinc-200 bg-white px-4 py-3 text-xs font-semibold sm:right-5">✓ 근거 없는 답변 차단</div>
  </div>;
}

function Proof({ label, value }: { label: string; value: string }) {
  return <div className="bg-cloud px-6 py-7 text-center"><b className="block text-lg">{value}</b><span className="mt-1 block text-xs text-mute">{label}</span></div>;
}

function ValueCard({ number, title, body, icon }: { number: string; title: string; body: string; icon: React.ReactNode }) {
  return <article className="flex min-h-72 flex-col rounded-[1.5rem] border border-hairline-soft bg-cloud p-7">
    <div className="flex items-center justify-between"><span className="text-xs font-semibold text-mute">{number}</span><span className="[&>svg]:h-6 [&>svg]:w-6">{icon}</span></div>
    <h3 className="mt-auto text-2xl font-bold">{title}</h3><p className="mt-4 text-sm leading-6 text-charcoal">{body}</p>
  </article>;
}

function Step({ number, title, body }: { number: string; title: string; body: string }) {
  return <li className="grid gap-4 border-b border-hairline py-9 sm:grid-cols-[4rem_1fr] sm:py-12">
    <span className="text-sm font-semibold text-mute">{number}</span><div><h3 className="text-2xl font-bold">{title}</h3><p className="mt-3 max-w-2xl text-sm leading-6 text-charcoal">{body}</p></div>
  </li>;
}
