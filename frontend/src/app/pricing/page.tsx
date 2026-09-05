import Link from "next/link";
import { Check, Minus } from "lucide-react";
import { MarketingPage } from "@/components/MarketingShell";
import { blockFontClassName } from "@/lib/fonts";

export const metadata = { title: "Pricing · Agentown" };

const rows = [
  ["새 에이전트 설계", "1개", "제한 확대 예정"],
  ["설계 수정", "에이전트당 2회", "제한 확대 예정"],
  ["샘플 실행·실패 검증", "포함", "포함"],
  ["Codex·Claude 패키지", "포함", "포함"],
  ["실제 Connector 자동 실행", "연결된 기능만", "연결된 기능만"],
  ["팀 운영·확장 한도", "기본", "확대 예정"],
];

export default function PricingPage() {
  return <MarketingPage kicker="PRICING" title="먼저 무료로, 쓸모를 확인하세요" description="첫 에이전트를 실제로 만들고 시험한 뒤 결정하세요. 현재 제한 베타의 핵심 기능은 무료이며, 유료 결제는 아직 받지 않습니다.">
    <div className="grid gap-5 lg:grid-cols-2">
      <Plan eyebrow="FREE BETA" name="무료" price="₩0" description="처음부터 패키지 다운로드까지 직접 확인" featured>
        <Item>새 에이전트 1개 설계</Item>
        <Item>설계 수정 2회</Item>
        <Item>유효 샘플로 실행 검증</Item>
        <Item>Codex·Claude용 패키지 다운로드</Item>
        <Link href="/signup" className="mt-7 flex w-full items-center justify-center rounded-pill bg-white px-5 py-3.5 text-sm font-semibold text-ink">무료로 시작하기</Link>
      </Plan>
      <Plan eyebrow="PRO · 준비 중" name="더 많이 사용" price="₩19,000 / 월" description="첫 사용 데이터로 한도와 구성을 확정합니다">
        <Item>더 많은 에이전트와 수정 횟수</Item>
        <Item>팀 운영과 장기 실행 기록</Item>
        <Item>연결된 Connector 자동화 확장</Item>
        <Item>결제 전 한도·가격 사전 고지</Item>
        <div className="mt-7 rounded-pill border border-hairline px-5 py-3.5 text-center text-sm font-semibold text-mute">아직 결제하지 않습니다</div>
      </Plan>
    </div>

    <section className="mt-20" aria-labelledby="comparison-title">
      <p className="text-xs font-semibold uppercase tracking-[.2em] text-mute">PLAN COMPARISON</p>
      <h2 id="comparison-title" className={`${blockFontClassName} mt-3 text-4xl md:text-5xl`}>무료 범위를 숨기지 않습니다</h2>
      <div className="mt-8 overflow-x-auto rounded-xl border border-hairline">
        <table className="w-full min-w-[42rem] text-left text-sm">
          <thead className="bg-cloud"><tr><th className="p-4">기능</th><th className="p-4">무료 베타</th><th className="p-4">Pro 출시 방향</th></tr></thead>
          <tbody>{rows.map(([feature, free, pro]) => <tr key={feature} className="border-t border-hairline"><th className="p-4 font-medium">{feature}</th><td className="p-4">{free}</td><td className="p-4 text-mute">{pro}</td></tr>)}</tbody>
        </table>
      </div>
    </section>

    <section className="mt-20 grid gap-4 md:grid-cols-3">
      <Note title="카드 등록 없음">무료 베타를 시작할 때 결제 정보를 요구하지 않습니다.</Note>
      <Note title="Mock 성공 없음">연결되지 않은 외부 기능은 성공으로 꾸미지 않고 미설정 상태로 멈춥니다.</Note>
      <Note title="유료 전환 전 고지">실제 결제가 열리면 가격·한도·해지 조건을 결제 전에 명확히 표시합니다.</Note>
    </section>
  </MarketingPage>;
}

function Plan({ eyebrow, name, price, description, featured = false, children }: { eyebrow: string; name: string; price: string; description: string; featured?: boolean; children: React.ReactNode }) {
  return <section className={`rounded-[1.5rem] border p-6 md:p-8 ${featured ? "border-ink bg-ink text-white" : "border-hairline bg-white"}`}>
    <p className={`text-xs font-semibold tracking-[.18em] ${featured ? "text-zinc-400" : "text-mute"}`}>{eyebrow}</p>
    <div className="mt-4 flex flex-wrap items-end justify-between gap-3"><h2 className="text-2xl font-bold">{name}</h2><p className={`${blockFontClassName} text-4xl`}>{price}</p></div>
    <p className={`mt-3 text-sm leading-6 ${featured ? "text-zinc-300" : "text-mute"}`}>{description}</p>
    <div className="mt-7 space-y-3 text-sm" role="list">{children}</div>
  </section>;
}

function Item({ children }: { children: React.ReactNode }) { return <div role="listitem" className="flex items-start gap-2"><Check className="mt-0.5 h-4 w-4 shrink-0 text-leaf" />{children}</div>; }
function Note({ title, children }: { title: string; children: React.ReactNode }) { return <article className="rounded-xl bg-cloud p-5"><div className="flex items-center gap-2"><Minus className="h-4 w-4"/><h3 className="font-semibold">{title}</h3></div><p className="mt-3 text-sm leading-6 text-mute">{children}</p></article>; }
