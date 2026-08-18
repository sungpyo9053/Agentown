import Link from "next/link";
import { MarketingHeader, MarketingFooter } from "@/components/MarketingShell";
import { blockFontClassName } from "@/lib/fonts";

// 팀원 타일 — 제품 카드와 같은 문법: 라디우스 0, 그림자 0, soft-cloud 위에 올림
const teamCards = [
  { initial: "R", role: "리서처", dept: "리서치팀" },
  { initial: "W", role: "작가", dept: "콘텐츠팀" },
  { initial: "Q", role: "검수자", dept: "품질관리팀" },
  { initial: "P", role: "발행 담당", dept: "운영팀" },
];

export default function Home() {
  return (
    <main className="flex min-h-screen flex-col bg-white">
      <MarketingHeader />

      {/* 캠페인 히어로 — 디스플레이 락업을 화면에 그대로 태움 */}
      <section className="bg-cloud">
        <div className="mx-auto max-w-[1440px] px-6 py-20 md:px-10 md:py-28">
          <p className="text-xs font-medium uppercase tracking-[.2em] text-mute">Agentown</p>
          <h1 className={`${blockFontClassName} mt-6 text-[64px] text-ink md:text-[128px]`}>
            I&apos;m a CEO.<br />Everyone has<br />an AI now.
          </h1>
          <p className={`${blockFontClassName} mt-8 text-3xl text-ink md:text-5xl`}>Assemble your AI team.</p>
          <p className="mt-6 max-w-xl text-base leading-6 text-charcoal">
            혼자 다 하지 않아도 됩니다. 내 회사를 만들고, 필요한 AI 팀원을 뽑고, 목표를 맡기세요. 풀고 싶은 문제 하나면 시작할 수 있어요.
          </p>
          <div className="mt-10 flex flex-wrap items-center gap-3">
            <Link href="/signup" className="rounded-pill bg-ink px-8 py-4 text-base font-medium text-white transition active:scale-95 active:opacity-50">시작하기</Link>
            <Link href="/pricing" className="rounded-pill bg-white px-8 py-4 text-base font-medium text-ink transition active:scale-95 active:opacity-50">요금 보기</Link>
          </div>
          <p className="mt-6 text-sm font-medium text-mute">첫 1개월 무료 · 신용카드 없이 시작</p>
        </div>
      </section>

      {/* 팀원 그리드 — 8px 거터, 무라디우스, 무그림자 */}
      <section className="mx-auto w-full max-w-[1440px] px-6 py-12 md:px-10">
        <h2 className={`${blockFontClassName} text-3xl text-ink md:text-4xl`}>Your team</h2>
        <div className="mt-6 grid grid-cols-2 gap-2 md:grid-cols-4">
          {teamCards.map((card) => <TeamCard key={card.initial} {...card} />)}
        </div>
      </section>

      <MarketingFooter />
    </main>
  );
}

function TeamCard({ initial, role, dept }: { initial: string; role: string; dept: string }) {
  return <article className="bg-white">
    <div className="flex aspect-square items-center justify-center bg-cloud">
      <span className={`${blockFontClassName} text-6xl text-ink md:text-8xl`}>{initial}</span>
    </div>
    <b className="mt-2 block text-base font-medium text-ink">{role}</b>
    <small className="text-sm font-medium text-mute">{dept}</small>
  </article>;
}
