"use client";

import { useState } from "react";
import Link from "next/link";
import { AppShell } from "@/components/AppShell";

export default function BillingPage() {
  const [requested, setRequested] = useState(false);

  return <AppShell kicker="OFFICE RENT" title="구독 · 오피스 월세">
    <div className="grid gap-6 md:grid-cols-[1fr_320px]">
      <div className="space-y-6">
        <section className="rounded-3xl bg-white p-6 shadow-card">
          <h2 className="font-black">오피스 월세 안내</h2>
          <p className="mt-3 text-sm leading-6 text-stone-600">
            첫 1개월은 무료로 회사와 오피스를 운영해보세요. 이후에는 실제 사무실 월세처럼, 매달 오피스를 유지하는 구독료를 받을 예정이에요.
            구성원 채용, 목표 설정, 실행 결과는 요금제와 무관하게 그대로 유지됩니다.
          </p>
        </section>

        <section className="rounded-3xl bg-white p-6 shadow-card">
          <h2 className="font-black">요금제</h2>
          <div className="mt-4 flex items-end justify-between rounded-2xl bg-cream p-5">
            <div>
              <p className="text-xs font-bold text-coral">오피스 월세</p>
              <p className="mt-1 text-3xl font-black">₩19,000<span className="text-base font-bold text-stone-500"> / 월</span></p>
              <p className="mt-1 text-xs text-stone-500">가격은 정식 출시 전까지 바뀔 수 있어요.</p>
            </div>
          </div>
          <button
            onClick={() => setRequested(true)}
            disabled={requested}
            className="mt-4 w-full rounded-xl bg-coral py-3 font-bold text-white disabled:opacity-60"
          >
            {requested ? "알림 신청 완료" : "구독하기"}
          </button>
          {requested && <p className="mt-3 text-sm text-stone-500">실제 결제 연동은 아직 준비 중이에요. 열리면 가장 먼저 알려드릴게요.</p>}
        </section>
      </div>

      <aside className="space-y-4">
        <div className="rounded-3xl bg-ink p-6 text-white">
          <p className="text-xs font-bold text-stone">TRIAL</p>
          <p className="mt-2 text-lg font-black">무료 체험 중</p>
          <p className="mt-1 text-sm text-stone-300">가입 후 1개월간 모든 기능을 무료로 사용하실 수 있어요.</p>
        </div>
        <Link href="/me" className="block rounded-3xl bg-white p-6 shadow-card"><h2 className="font-bold">← 마이페이지로</h2><p className="mt-2 text-sm text-stone-500">프로필·비밀번호·계정 설정으로 돌아갑니다.</p></Link>
      </aside>
    </div>
  </AppShell>;
}
