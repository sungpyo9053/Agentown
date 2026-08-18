import Link from "next/link";
import { MarketingHeader, MarketingFooter } from "@/components/MarketingShell";
import { blockFontClassName } from "@/lib/fonts";

// 히어로 주변에 떠 있는 팀원 카드 — "팀을 모은다"를 시각적으로
const teamCards = [
  { initial: "R", role: "리서처", dept: "리서치팀", tone: "bg-coral" },
  { initial: "W", role: "작가", dept: "콘텐츠팀", tone: "bg-leaf" },
  { initial: "Q", role: "검수자", dept: "품질관리팀", tone: "bg-ink" },
  { initial: "P", role: "발행 담당", dept: "운영팀", tone: "bg-amber-500" },
];

export default function Home() {
  return (
    <main className="flex min-h-screen flex-col">
      <MarketingHeader />

      <section className="relative mx-auto w-full max-w-6xl px-6 pb-24 pt-16 md:pt-24">
        {/* 데스크톱: 히어로 좌우에 떠 있는 팀원 카드 */}
        <div className="pointer-events-none absolute inset-x-0 top-10 hidden justify-between px-6 lg:flex">
          <div className="space-y-6 pt-6">
            <TeamCard {...teamCards[0]} className="-rotate-3" />
            <TeamCard {...teamCards[1]} className="ml-8 rotate-2" />
          </div>
          <div className="space-y-6 pt-2">
            <TeamCard {...teamCards[2]} className="rotate-3" />
            <TeamCard {...teamCards[3]} className="mr-8 -rotate-2" />
          </div>
        </div>

        <div className="relative mx-auto max-w-2xl text-center">
          <span className="inline-block rounded-full bg-coral/10 px-3 py-1 text-xs font-semibold text-coral">Beta</span>
          <h1 className={`${blockFontClassName} mt-6 text-4xl leading-tight tracking-tight text-ink md:text-6xl`}>
            I&apos;m a CEO.<br />
            <span className="text-coral">Everyone has an AI now.</span>
          </h1>
          <p className="mt-6 text-lg font-semibold text-ink md:text-xl">Assemble your AI team.</p>
          <p className="mx-auto mt-4 max-w-xl text-base leading-7 text-zinc-600">
            혼자 다 하지 않아도 됩니다. 내 회사를 만들고, 필요한 AI 팀원을 뽑고, 목표를 맡기세요. 풀고 싶은 문제 하나면 시작할 수 있어요.
          </p>
          <div className="mt-8 flex flex-wrap justify-center gap-3">
            <Link href="/signup" className="rounded-lg bg-coral px-6 py-3 font-semibold text-white transition hover:bg-coral/90">무료로 시작하기</Link>
            <Link href="/pricing" className="rounded-lg border border-zinc-200 bg-white px-6 py-3 font-semibold text-ink hover:border-zinc-300">요금 보기</Link>
          </div>
          <p className="mt-6 text-sm text-zinc-400">첫 1개월 무료 · 신용카드 없이 시작</p>
        </div>

        {/* 모바일·태블릿: 카드 가로 스크롤 */}
        <div className="mt-14 flex gap-4 overflow-x-auto pb-2 lg:hidden">
          {teamCards.map((card) => <TeamCard key={card.initial} {...card} className="shrink-0" />)}
        </div>
      </section>

      <MarketingFooter />
    </main>
  );
}

function TeamCard({ initial, role, dept, tone, className = "" }: { initial: string; role: string; dept: string; tone: string; className?: string }) {
  return <div className={`w-52 rounded-xl border border-zinc-200 bg-white p-4 shadow-[0_16px_32px_-18px_rgba(24,24,27,.35)] ${className}`}>
    <div className="flex items-center gap-3">
      <span className={`flex h-10 w-10 items-center justify-center rounded-full text-sm font-bold text-white ${tone}`}>{initial}</span>
      <span>
        <b className="block text-sm text-ink">{role}</b>
        <small className="text-xs text-zinc-500">{dept}</small>
      </span>
    </div>
  </div>;
}
