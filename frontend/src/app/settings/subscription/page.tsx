"use client";

import { useState } from "react";
import { AppShell, Panel } from "@/components/AppShell";
import { blockFontClassName } from "@/lib/fonts";

export default function SubscriptionPage() {
  const [requested, setRequested] = useState(false);

  return <AppShell kicker="SETTING" title="구독 관리">
    <Panel>
      <div className="grid gap-2 md:grid-cols-2">
        <div className="bg-ink p-6 text-white">
          <p className="text-xs font-medium uppercase tracking-[.2em] text-stone">Trial</p>
          <p className={`${blockFontClassName} mt-3 text-5xl`}>무료 체험 중</p>
          <p className="mt-3 text-sm text-stone">가입 후 1개월간 모든 기능을 무료로 사용하실 수 있어요.</p>
        </div>
        <div className="border border-hairline p-6">
          <p className="text-xs font-medium uppercase tracking-[.2em] text-mute">오피스 월세</p>
          <p className={`${blockFontClassName} mt-3 text-5xl text-ink`}>₩19,000<span className="text-2xl text-mute"> / 월</span></p>
          <p className="mt-3 text-sm text-mute">첫 1개월 이후, 실제 사무실 월세처럼 매달 오피스를 유지하는 구독료를 받을 예정이에요. 가격은 정식 출시 전까지 바뀔 수 있어요.</p>
          <button onClick={() => setRequested(true)} disabled={requested} className="mt-5 w-full rounded-pill bg-ink py-4 text-sm font-medium text-white transition active:scale-95 active:opacity-50 disabled:opacity-40">{requested ? "알림 신청 완료" : "구독하기"}</button>
          {requested && <p className="mt-3 text-sm text-mute">실제 결제 연동은 아직 준비 중이에요. 열리면 가장 먼저 알려드릴게요.</p>}
        </div>
      </div>
    </Panel>
  </AppShell>;
}
