import Link from "next/link";
import { Check, ExternalLink } from "lucide-react";
import { AppShell, Panel } from "@/components/AppShell";
import { blockFontClassName } from "@/lib/fonts";

export default function SubscriptionPage() {
  return <AppShell kicker="SETTING" title="구독 관리">
    <Panel>
      <div className="grid gap-2 md:grid-cols-2">
        <div className="bg-ink p-6 text-white">
          <p className="text-xs font-medium uppercase tracking-[.2em] text-stone">Free beta</p>
          <p className={`${blockFontClassName} mt-3 text-5xl`}>무료 사용 중</p>
          <p className="mt-3 text-sm leading-6 text-stone">카드 등록 없이 첫 에이전트를 설계하고, 수정·테스트·패키지 다운로드까지 확인할 수 있습니다.</p>
          <ul className="mt-6 space-y-2 text-sm"><li className="flex gap-2"><Check className="h-4 w-4 text-leaf"/>새 설계 1개</li><li className="flex gap-2"><Check className="h-4 w-4 text-leaf"/>설계 수정 2회</li><li className="flex gap-2"><Check className="h-4 w-4 text-leaf"/>샘플 실행과 패키지</li></ul>
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
