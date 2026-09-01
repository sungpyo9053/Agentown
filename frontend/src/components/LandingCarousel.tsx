"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { ArrowLeft, ArrowRight } from "lucide-react";
import { blockFontClassName } from "@/lib/fonts";

/* 가로로 넘어가는 히어로. 첫 장은 기존 랜딩 그대로 두고
   뒤로 메시지 장을 이어 붙여 슬로건을 단계적으로 강조합니다. */

const slideLabels = ["Intro", "말하기", "설계", "테스트", "시작"];

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

      {/* 1 — 기존 히어로. CTA는 오른쪽 열로 빼서 세로 높이를 줄입니다. */}
      <Slide label={slideLabels[0]} index={0} count={count}>
        <div className="grid gap-10 lg:grid-cols-[1.25fr_0.75fr] lg:items-start lg:gap-12">
          <div>
            <p className="text-xs font-medium uppercase tracking-[.2em] text-mute">Agentown · Agent Compiler</p>
            <h1 className={`${blockFontClassName} font-block-tight mt-6 text-[clamp(2.75rem,7.5vw,8rem)] text-ink`}>
              말하면,<br />AI 팀이<br />완성됩니다.
            </h1>
            <p className={`${blockFontClassName} font-block-tight mt-6 text-[clamp(1.75rem,3.2vw,3rem)] text-ink`}>Describe it. Test it. Take it anywhere.</p>
            <Body className="mt-6" sentences={["하고 싶은 일을 자연어로 설명하세요.", "필요한 에이전트와 도구·워크플로를 자동으로 설계합니다.", "샘플로 시험하고 실행 패키지로 가져갈 수 있어요."]} />
          </div>
          <div className="lg:flex lg:flex-col lg:items-end lg:pt-1">
            <div className="flex flex-wrap items-center gap-3 lg:justify-end">
              <Link href="/signup" className="rounded-pill bg-ink px-7 py-3 text-sm font-medium text-white transition active:scale-95 active:opacity-50">시작하기</Link>
              <Link href="/pricing" className="rounded-pill bg-white px-7 py-3 text-sm font-medium text-ink transition active:scale-95 active:opacity-50">요금 보기</Link>
            </div>
          </div>
        </div>
      </Slide>

      {/* 2 — 자연어 입력 */}
      <Slide label={slideLabels[1]} index={1} count={count}>
        <p className="text-xs font-medium uppercase tracking-[.2em] text-mute">Step 01 · Describe</p>
        <h2 className={`${blockFontClassName} mt-6 text-[clamp(2.5rem,6.8vw,7rem)] text-ink`}>
          기술 대신<br /><span className="text-mute">하고 싶은 일만</span><br />말하세요.
        </h2>
        <Body className="mt-7" sentences={["노드, 프롬프트, API를 먼저 배울 필요가 없습니다.", "모호한 부분만 쉬운 질문으로 확인하고 요구사항을 구조화합니다."]} />
      </Slide>

      {/* 3 — 에이전트 설계 */}
      <Slide label={slideLabels[2]} index={2} count={count}>
        <p className="text-xs font-medium uppercase tracking-[.2em] text-mute">Step 02 · Compile</p>
        <h2 className={`${blockFontClassName} mt-6 text-[clamp(2.5rem,6.8vw,7rem)] text-ink`}>
          필요한 것만<br />골라서<br />설계합니다.
        </h2>
        <Body className="mt-7" sentences={["AI 에이전트, 일반 기능, 도구, 승인 단계를 비용에 맞게 조합합니다.", "입력·출력 스키마와 워크플로를 같은 서버 버전으로 검증합니다."]} />
      </Slide>

      {/* 4 — 샘플 테스트 */}
      <Slide label={slideLabels[3]} index={3} count={count}>
        <p className="text-xs font-medium uppercase tracking-[.2em] text-mute">Step 03 · Test</p>
        <h2 className={`${blockFontClassName} font-block-tight mt-6 text-[clamp(2.5rem,6.8vw,7rem)] text-ink`}>
          연결 전에<br />샘플로<br />돌려보세요.
        </h2>
        <Body className="mt-7" sentences={["Mock 데이터로 단계별 입력·출력과 최종 결과를 먼저 확인합니다.", "실제 외부 연결이 필요한 지점과 미해결 항목도 숨기지 않습니다."]} />
      </Slide>

      {/* 5 — 시작 */}
      <Slide label={slideLabels[4]} index={4} count={count}>
        <div className="grid gap-10 lg:grid-cols-[1.25fr_0.75fr] lg:items-start lg:gap-12">
          <div>
            <p className="text-xs font-medium uppercase tracking-[.2em] text-mute">Step 04 · Export</p>
            <h2 className={`${blockFontClassName} mt-6 text-[clamp(2.5rem,6.8vw,7rem)] text-ink`}>
              만들고,<br />시험하고,<br />가져가세요.
            </h2>
            <Body className="mt-7" sentences={["검증된 Agent Package와 Python Mock 실행기를 내려받을 수 있습니다.", "Slack·Notion 같은 실제 연결은 사용자의 인증 설정 뒤 선택한 환경에서 실행합니다."]} />
          </div>
          <div className="lg:flex lg:flex-col lg:items-end lg:pt-1">
            <div className="flex flex-wrap items-center gap-3 lg:justify-end">
              <Link href="/signup" className="rounded-pill bg-ink px-7 py-3 text-sm font-medium text-white transition active:scale-95 active:opacity-50">무료로 시작하기</Link>
              <Link href="/features" className="rounded-pill bg-white px-7 py-3 text-sm font-medium text-ink transition active:scale-95 active:opacity-50">기능 보기</Link>
            </div>
          </div>
        </div>
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

/** 본문은 문장(마침표) 단위로 줄을 나눕니다. */
function Body({ sentences, className = "" }: { sentences: string[]; className?: string }) {
  return <p className={`max-w-2xl text-base leading-6 text-charcoal ${className}`}>
    {sentences.map((sentence) => <span key={sentence} className="block">{sentence}</span>)}
  </p>;
}

function Slide({ children, label, index, count }: { children: React.ReactNode; label: string; index: number; count: number }) {
  return <div role="group" aria-roledescription="slide" aria-label={`${index + 1} / ${count} ${label}`}
    className="flex h-[calc(100dvh-4rem)] min-h-[34rem] w-full shrink-0 snap-start snap-always flex-col justify-center overflow-hidden px-6 pb-28 pt-10 md:px-10">
    <div className="mx-auto w-full max-w-[1440px]">{children}</div>
  </div>;
}
