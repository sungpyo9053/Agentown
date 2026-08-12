import Link from "next/link";
import {MessageCircleQuestion, ListChecks, Layers, ClipboardCheck} from "lucide-react";
import {SessionNav} from "@/components/SessionNav";
import {blockFontClassName} from "@/lib/fonts";

const steps = [
  { icon: MessageCircleQuestion, title: "질문에 답하기", sub: "AI가 던지는 블록 질문" },
  { icon: ListChecks, title: "선택하고 다듬기", sub: "내 방향에 맞게 조정" },
  { icon: Layers, title: "블록이 쌓이고", sub: "생각이 구조를 갖춰요" },
  { icon: ClipboardCheck, title: "내 기획 완성", sub: "AI가 아닌, 내가 만든 기획" },
];

const questionCards = [
  { num: "01", text: "무엇을 만들고 싶나요?" },
  { num: "02", text: "누구를 위한 건가요?" },
  { num: "03", text: "완성되면 무엇이 달라지나요?" },
];

export default function Home() {
  return (
    <main>
      <header className="border-b border-zinc-200"><div className="mx-auto flex max-w-6xl items-center justify-between px-6 py-5"><Link href="/" className="flex items-center gap-2 text-lg font-semibold tracking-tight"><BlockLogo/>블록기획</Link><nav className="flex items-center gap-4 text-sm font-medium text-zinc-500"><Link href="/dashboard" className="hover:text-ink">내 AI 회사</Link><span className="text-zinc-200">|</span><SessionNav/></nav></div></header>

      <section className="mx-auto max-w-6xl px-6 pb-24 pt-10">
        <div className="overflow-hidden rounded-2xl border border-zinc-200 bg-white">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-zinc-200 px-6 py-4 md:px-10">
            <p className="text-sm font-semibold text-coral">AI를 잘 쓰는 사람은, 질문부터 다릅니다.</p>
            <span className="rounded-full bg-coral/10 px-3 py-1 text-xs font-semibold text-coral">Beta</span>
          </div>

          <div className="grid gap-8 border-b border-zinc-200 px-6 py-10 md:grid-cols-2 md:px-10 md:py-14">
            <div>
              <h1 className={`${blockFontClassName} text-4xl leading-tight tracking-tight text-ink md:text-5xl`}>AI가 만든 게 아니라,<br /><span className="text-coral">당신이 기획한 게</span> 됩니다.</h1>
              <p className="mt-6 max-w-xl text-base leading-7 text-zinc-600">뚝딱 만들어진 결과물은 대개 만든 사람도 잘 모릅니다. 그래서 유지도, 수정도 어려워지죠. 블록기획은 AI가 던지는 질문 블록에 하나씩 답하며, 무엇을 만들지 스스로 명확히 하는 과정을 도와줍니다.</p>
            </div>
            <div className="relative hidden min-h-[260px] md:block">
              {questionCards.map((card, i) => <QuestionCard key={card.num} {...card} className={["right-4 top-0 w-44 rotate-3", "right-0 top-[6.5rem] w-60 -rotate-2 border-coral/40", "right-24 top-[13.5rem] w-40 rotate-1"][i]} />)}
            </div>
            <div className="space-y-3 md:hidden">
              {questionCards.map((card) => <QuestionCard key={card.num} {...card} />)}
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-4 px-6 py-6 md:px-10">
            <Link href="/signup" className="rounded-lg bg-coral px-6 py-3 font-semibold text-white transition hover:bg-coral/90">무료로 시작하기</Link>
            <Link href="/dashboard" className="rounded-lg border border-zinc-200 bg-white px-6 py-3 font-semibold text-ink hover:border-zinc-300">둘러보기</Link>
          </div>
        </div>
      </section>

      <section className="mx-auto max-w-6xl px-6 pb-24">
        <div className="overflow-hidden rounded-2xl border border-white/10 bg-ink text-white">
          <div className="border-b border-white/10 px-6 py-4 md:px-10"><p className="text-xs font-semibold uppercase tracking-wide text-coral">How it works</p></div>
          <div className="flex items-end divide-x divide-white/10 overflow-x-auto">
            {steps.map((step, i) => {
              const Icon = step.icon;
              const isLast = i === steps.length - 1;
              const topPad = ["pt-6", "pt-12", "pt-20", "pt-28"][i];
              return <article key={step.title} className={`min-w-[140px] flex-1 px-5 pb-8 md:px-7 ${topPad} ${isLast ? "bg-coral" : ""}`}>
                <span className={`${blockFontClassName} ${isLast ? "text-white" : "text-coral"}`}>{String(i + 1).padStart(2, "0")}</span>
                <Icon className={`mt-3 h-5 w-5 ${isLast ? "text-white" : "text-coral"}`} aria-hidden="true" />
                <h2 className="mt-3 text-sm font-semibold leading-snug">{step.title}</h2>
                <p className={`mt-1 text-xs ${isLast ? "text-white/70" : "text-zinc-400"}`}>{step.sub}</p>
              </article>;
            })}
          </div>
        </div>
      </section>
    </main>
  );
}

function QuestionCard({ num, text, className = "" }: { num: string; text: string; className?: string }) {
  const positioned = className.length > 0;
  return <div className={`rounded-xl border border-zinc-200 bg-white p-4 shadow-[0_16px_32px_-18px_rgba(24,24,27,.35)] ${positioned ? `absolute ${className}` : "w-full"}`}>
    <span className="text-xs font-semibold text-coral">{num}</span>
    <p className="mt-2 text-sm font-medium leading-5 text-ink">{text}</p>
  </div>;
}

function BlockLogo() {
  return <svg width="22" height="22" viewBox="0 0 22 22" fill="none" aria-hidden="true">
    <rect x="1" y="1" width="9" height="9" rx="2" className="fill-coral" />
    <rect x="12" y="1" width="9" height="9" rx="2" className="fill-zinc-200" />
    <rect x="1" y="12" width="9" height="9" rx="2" className="fill-zinc-200" />
    <rect x="12" y="12" width="9" height="9" rx="2" className="fill-ink" />
  </svg>;
}
