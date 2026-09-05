"use client";

import Link from "next/link";
import { useEffect } from "react";
import { Check, ExternalLink } from "lucide-react";
import { useQuery } from "@tanstack/react-query";
import { AppShell, Panel } from "@/components/AppShell";
import { blockFontClassName } from "@/lib/fonts";
import { api } from "@/lib/api";

type Usage = { plan: "FREE_BETA" | "UNLIMITED"; designLimit?: number; designUsed: number; designRemaining?: number; revisionLimitPerAgent?: number; unlimited: boolean; checkoutAvailable: boolean };

export default function SubscriptionPage() {
  const usage = useQuery({ queryKey: ["agent-development-usage"], queryFn: () => api<Usage>("/agent-development/usage") });
  const quota = usage.data;
  useEffect(() => {
    void api("/agent-development/events", { method: "POST", body: JSON.stringify({ eventType: "UPGRADE_VIEWED" }) }).catch(() => undefined);
  }, []);
  return <AppShell kicker="SETTING" title="구독 관리">
    <Panel>
      <div className="grid gap-2 md:grid-cols-2">
        <div className="bg-ink p-6 text-white">
          <p className="text-xs font-medium uppercase tracking-[.2em] text-stone">Free beta</p>
          <p className={`${blockFontClassName} mt-3 text-5xl`}>{quota?.unlimited ? "제한 없음" : "무료 사용 중"}</p>
          <p className="mt-3 text-sm leading-6 text-stone">카드 등록 없이 첫 에이전트를 설계하고, 수정·테스트·패키지 다운로드까지 확인할 수 있습니다.</p>
          {usage.isError ? <p className="mt-6 rounded-md bg-white/10 p-3 text-sm">현재 사용량을 불러오지 못했습니다. 새로고침 후 다시 확인해 주세요.</p> : <ul className="mt-6 space-y-2 text-sm">
            <li className="flex gap-2"><Check className="h-4 w-4 text-leaf"/>{quota?.unlimited ? "새 설계 제한 없음" : `새 설계 ${quota ? `${quota.designUsed}/${quota.designLimit}` : "확인 중"}`}</li>
            <li className="flex gap-2"><Check className="h-4 w-4 text-leaf"/>{quota?.unlimited ? "설계 수정 제한 없음" : `에이전트당 설계 수정 ${quota?.revisionLimitPerAgent ?? "-"}회`}</li>
            <li className="flex gap-2"><Check className="h-4 w-4 text-leaf"/>샘플 실행과 패키지</li>
          </ul>}
          {!quota?.unlimited && quota && <p className="mt-5 border-t border-white/20 pt-4 text-sm font-semibold">새 설계 {quota.designRemaining}회 남음</p>}
        </div>
        <div className="border border-hairline p-6">
          <p className="text-xs font-medium uppercase tracking-[.2em] text-mute">Pro · 준비 중</p>
          <p className={`${blockFontClassName} mt-3 text-5xl text-ink`}>₩19,000<span className="text-2xl text-mute"> / 월</span></p>
          <p className="mt-3 text-sm leading-6 text-mute">현재는 결제를 받지 않습니다. 실제 유료화 전 가격, 사용 한도, 해지 조건을 이 화면에서 먼저 안내합니다.</p>
          <Link href="/pricing" className="mt-5 flex w-full items-center justify-center gap-2 rounded-pill border border-ink py-4 text-sm font-medium text-ink">무료·유료 범위 보기 <ExternalLink className="h-4 w-4"/></Link>
          <p className="mt-3 text-xs leading-5 text-mute">결제되지 않는 버튼을 구독 완료처럼 표시하지 않습니다.</p>
        </div>
      </div>
    </Panel>
  </AppShell>;
}
