"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { ArrowLeft, ArrowRight } from "lucide-react";
import { blockFontClassName } from "@/lib/fonts";

/* 가로로 넘어가는 히어로. 첫 장은 기존 랜딩 그대로 두고
   뒤로 메시지 장을 이어 붙여 슬로건을 단계적으로 강조합니다. */

const teamCards = [
  { initial: "R", role: "리서처", dept: "리서치팀" },
  { initial: "W", role: "작가", dept: "콘텐츠팀" },
  { initial: "Q", role: "검수자", dept: "품질관리팀" },
  { initial: "P", role: "발행 담당", dept: "운영팀" },
];

const slideLabels = ["Intro", "혼자", "회사", "팀원", "시작"];

const AUTOPLAY_MS = 6000;

export function LandingCarousel() {
  const trackRef = useRef<HTMLDivElement>(null);
  const [index, setIndex] = useState(0);
  const [paused, setPaused] = useState(false);
  const count = slideLabels.length;

  // 현재 보이는 슬라이드 추적
  useEffect(() => {
    const track = trackRef.current;
    if (!track) return;
    const onScroll = () => setIndex(Math.round(track.scrollLeft / track.clientWidth));
    track.addEventListener("scroll", onScroll, { passive: true });
    return () => track.removeEventListener("scroll", onScroll);
  }, []);

  // 자동 재생 — 마지막 장 다음에는 처음으로 돌아옵니다.
  useEffect(() => {
    if (paused) return;
    const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (reduceMotion) return;
    const timer = window.setInterval(() => {
      const track = trackRef.current;
      if (!track) return;
      if (document.hidden) return;                      // 다른 탭이면 멈춤
      const current = Math.round(track.scrollLeft / track.clientWidth);
      const next = (current + 1) % count;
      track.scrollTo({ left: next * track.clientWidth, behavior: "smooth" });
    }, AUTOPLAY_MS);
    return () => window.clearInterval(timer);
  }, [paused, count]);

  function goTo(next: number) {
    const track = trackRef.current;
    if (!track) return;
    const target = Math.max(0, Math.min(count - 1, next));
    track.scrollTo({ left: target * track.clientWidth, behavior: "smooth" });
  }

  function onKeyDown(event: React.KeyboardEvent) {
    if (event.key === "ArrowRight") { event.preventDefault(); goTo(index + 1); }
    if (event.key === "ArrowLeft") { event.preventDefault(); goTo(index - 1); }
  }

  return <section
    className="relative bg-cloud" aria-roledescription="carousel" aria-label="Agentown 소개"
    onPointerEnter={() => setPaused(true)} onPointerLeave={() => setPaused(false)}
    onFocusCapture={() => setPaused(true)} onBlurCapture={() => setPaused(false)}
  >
    <div ref={trackRef} onKeyDown={onKeyDown} tabIndex={0} className="landing-track flex snap-x snap-mandatory overflow-x-auto outline-none">

      {/* 1 — 기존 히어로 */}
      <Slide label={slideLabels[0]} index={0} count={count}>
        <p className="text-xs font-medium uppercase tracking-[.2em] text-mute">Agentown</p>
        <h1 className={`${blockFontClassName} mt-6 text-[clamp(2.75rem,7.5vw,8rem)] text-ink`}>
          I&apos;m a CEO.<br />Everyone has<br />an AI now.
        </h1>
        <p className={`${blockFontClassName} mt-6 text-[clamp(1.75rem,3.2vw,3rem)] text-ink`}>Assemble your AI team.</p>
        <p className="mt-6 max-w-xl text-base leading-6 text-charcoal">
          혼자 다 하지 않아도 됩니다. 내 회사를 만들고, 필요한 AI 팀원을 뽑고, 목표를 맡기세요. 풀고 싶은 문제 하나면 시작할 수 있어요.
        </p>
        <div className="mt-10 flex flex-wrap items-center gap-3">
          <Link href="/signup" className="rounded-pill bg-ink px-8 py-4 text-base font-medium text-white transition active:scale-95 active:opacity-50">시작하기</Link>
          <Link href="/pricing" className="rounded-pill bg-white px-8 py-4 text-base font-medium text-ink transition active:scale-95 active:opacity-50">요금 보기</Link>
        </div>
        <p className="mt-6 text-sm font-medium text-mute">첫 1개월 무료 · 신용카드 없이 시작</p>
      </Slide>

      {/* 2 — 문제 제기 */}
      <Slide label={slideLabels[1]} index={1} count={count}>
        <p className="text-xs font-medium uppercase tracking-[.2em] text-mute">The problem</p>
        <h2 className={`${blockFontClassName} mt-6 text-[clamp(2.5rem,6.8vw,7rem)] leading-[0.92] text-ink`}>
          혼자 다 하려니까<br /><span className="text-mute">아무것도</span><br />끝나지 않는다.
        </h2>
        <p className="mt-7 max-w-xl text-base leading-6 text-charcoal">
          기획도, 조사도, 검수도, 발행도 전부 한 사람 몫이었죠. AI를 써봐도 매번 처음부터 다시 설명해야 했고요.
        </p>
      </Slide>

      {/* 3 — 회사를 만든다 */}
      <Slide label={slideLabels[2]} index={2} count={count}>
        <p className="text-xs font-medium uppercase tracking-[.2em] text-mute">Step 01</p>
        <h2 className={`${blockFontClassName} mt-6 text-[clamp(2.5rem,6.8vw,7rem)] leading-[0.92] text-ink`}>
          그래서<br />회사를<br />차렸습니다.
        </h2>
        <p className="mt-7 max-w-xl text-base leading-6 text-charcoal">
          이름을 정하고, 무슨 일을 하는 곳인지 적고, 사무실을 꾸밉니다. 문제 하나면 회사 하나가 시작돼요.
        </p>
      </Slide>

      {/* 4 — 팀원을 모은다 */}
      <Slide label={slideLabels[3]} index={3} count={count}>
        <p className="text-xs font-medium uppercase tracking-[.2em] text-mute">Step 02</p>
        <h2 className={`${blockFontClassName} mt-6 text-[clamp(2.5rem,6.8vw,7rem)] leading-[0.92] text-ink`}>
          Assemble<br />your<br />AI team.
        </h2>
        <p className="mt-7 max-w-xl text-base leading-6 text-charcoal">
          리서처, 작가, 검수자, 발행 담당. 필요한 역할을 뽑고 각자의 일하는 기준을 정해주면 팀이 완성됩니다.
        </p>
        <div className="mt-8 grid w-full max-w-2xl grid-cols-2 gap-2 md:grid-cols-4">
          {teamCards.map((card) => <article key={card.initial} className="bg-white">
            <div className="flex aspect-square items-center justify-center bg-cloud">
              <span className={`${blockFontClassName} text-[clamp(1.75rem,4vw,3.5rem)] text-ink`}>{card.initial}</span>
            </div>
            <b className="mt-2 block text-sm font-medium text-ink">{card.role}</b>
            <small className="text-xs font-medium text-mute">{card.dept}</small>
          </article>)}
        </div>
      </Slide>

      {/* 5 — 시작 */}
      <Slide label={slideLabels[4]} index={4} count={count}>
        <p className="text-xs font-medium uppercase tracking-[.2em] text-mute">Step 03</p>
        <h2 className={`${blockFontClassName} mt-6 text-[clamp(2.5rem,6.8vw,7rem)] leading-[0.92] text-ink`}>
          이제<br />맡기고<br />퇴근하세요.
        </h2>
        <p className="mt-7 max-w-xl text-base leading-6 text-charcoal">
          목표를 정해두면 팀이 순서대로 일합니다. 중요한 순간에만 당신의 승인을 기다려요.
        </p>
        <div className="mt-10 flex flex-wrap items-center gap-3">
          <Link href="/signup" className="rounded-pill bg-ink px-8 py-4 text-base font-medium text-white transition active:scale-95 active:opacity-50">무료로 시작하기</Link>
          <Link href="/features" className="rounded-pill bg-white px-8 py-4 text-base font-medium text-ink transition active:scale-95 active:opacity-50">기능 보기</Link>
        </div>
        <p className="mt-6 text-sm font-medium text-mute">첫 1개월 무료 · 신용카드 없이 시작</p>
      </Slide>
    </div>

    {/* 컨트롤 */}
    <div className="pointer-events-none absolute inset-x-0 bottom-8 z-10">
      <div className="mx-auto flex max-w-[1440px] items-center justify-between gap-6 px-6 md:px-10">
        <div className="pointer-events-auto flex items-center gap-2">
          {slideLabels.map((label, i) => <button key={label} type="button" onClick={() => goTo(i)} aria-label={`${i + 1}번째 슬라이드: ${label}`} aria-current={index === i}
            className={`h-1.5 overflow-hidden rounded-pill transition-all ${index === i ? "w-10 bg-hairline" : "w-4 bg-hairline hover:bg-mute"}`}>
            {/* 현재 슬라이드에는 남은 시간을 바로 표시 */}
            {index === i && <span key={`${i}-${paused}`} className={`block h-full rounded-pill bg-ink ${paused ? "w-full" : "landing-progress"}`} style={{ animationDuration: `${AUTOPLAY_MS}ms` }} />}
          </button>)}
        </div>
        <div className="pointer-events-auto flex items-center gap-2">
          <span className={`${blockFontClassName} mr-2 hidden text-2xl text-mute sm:block`}>{String(index + 1).padStart(2, "0")} / {String(count).padStart(2, "0")}</span>
          <button type="button" onClick={() => goTo(index - 1)} disabled={index === 0} aria-label="이전"
            className="flex h-12 w-12 items-center justify-center rounded-pill bg-white text-ink transition active:scale-95 disabled:opacity-30"><ArrowLeft className="h-5 w-5" /></button>
          <button type="button" onClick={() => goTo(index + 1)} disabled={index === count - 1} aria-label="다음"
            className="flex h-12 w-12 items-center justify-center rounded-pill bg-ink text-white transition active:scale-95 disabled:opacity-30"><ArrowRight className="h-5 w-5" /></button>
        </div>
      </div>
    </div>
  </section>;
}

function Slide({ children, label, index, count }: { children: React.ReactNode; label: string; index: number; count: number }) {
  return <div role="group" aria-roledescription="slide" aria-label={`${index + 1} / ${count} ${label}`}
    className="flex h-[calc(100dvh-4rem)] min-h-[34rem] w-full shrink-0 snap-start snap-always flex-col justify-center overflow-hidden px-6 pb-28 pt-10 md:px-10">
    <div className="mx-auto w-full max-w-[1440px]">{children}</div>
  </div>;
}
