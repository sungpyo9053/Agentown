import Link from "next/link";
import { SessionNav } from "@/components/SessionNav";
import { blockFontClassName } from "@/lib/fonts";

// 마케팅(비로그인) 페이지 공통 메뉴. 앱 내부 화면은 AppShell을 사용합니다.
export const marketingNav = [
  { href: "/", label: "Home" },
  { href: "/features", label: "Feature" },
  { href: "/pricing", label: "Pricing" },
  { href: "/about", label: "About" },
  { href: "/contact", label: "Contact" },
];

// 로고: 한 점에서 모여드는 세 개의 획 = "Assemble".
// 시스템 규칙대로 단색(ink)만 사용하고, 워드마크는 디스플레이 서체로 붙입니다.
export function AgentownLogo({ className = "h-6 w-6" }: { className?: string }) {
  return <svg viewBox="0 0 32 32" fill="none" aria-hidden="true" className={className}>
    <path d="M4 25 L16 4 L28 25" stroke="currentColor" strokeWidth="4" strokeLinecap="square" />
    <path d="M11 25 L16 16 L21 25" stroke="currentColor" strokeWidth="4" strokeLinecap="square" />
  </svg>;
}

export function AgentownWordmark({ className = "" }: { className?: string }) {
  return <span className={`flex items-center gap-2 text-ink ${className}`}>
    <AgentownLogo />
    <span className={`${blockFontClassName} text-2xl leading-none tracking-tight`}>Agentown</span>
  </span>;
}

export function MarketingHeader() {
  return <header className="sticky top-0 z-30 bg-white shadow-hairline">
    <div className="mx-auto flex h-16 max-w-[1440px] items-center justify-between gap-4 px-6 md:px-10">
      <Link href="/" className="shrink-0"><AgentownWordmark /></Link>
      <nav className="hidden items-center gap-8 text-base font-medium text-ink md:flex">
        {marketingNav.map((item) => <Link key={item.href} href={item.href} className="py-1 hover:opacity-60">{item.label}</Link>)}
      </nav>
      <div className="flex items-center gap-3"><SessionNav /></div>
    </div>
  </header>;
}

export function MarketingFooter() {
  return <footer className="border-t border-hairline bg-white">
    <div className="mx-auto max-w-[1440px] px-6 py-12 md:px-10">
      <Link href="/"><AgentownWordmark /></Link>
      {/* 문장(마침표) 단위로 줄바꿈 */}
      <p className="mt-4 max-w-md text-sm leading-6 text-mute">
        <span className="block">Assemble your AI team.</span>
        <span className="block">문제를 풀고 싶은 사람이 회사를 차리고, 팀원을 뽑아 함께 해결합니다.</span>
      </p>
      <p className="mt-12 text-[9px] leading-relaxed text-mute">© {new Date().getFullYear()} Agentown. All rights reserved.</p>
    </div>
  </footer>;
}

// Feature/About/Contact 등 아직 내용을 채우지 않은 페이지용 골격
export function MarketingPage({ kicker, title, description, children }: { kicker: string; title: string; description?: string; children?: React.ReactNode }) {
  return <main className="flex min-h-screen flex-col bg-white">
    <MarketingHeader />
    <section className="mx-auto w-full max-w-[1440px] flex-1 px-6 py-12 md:px-10">
      <p className="text-xs font-medium uppercase tracking-wide text-mute">{kicker}</p>
      <h1 className={`${blockFontClassName} mt-2 text-5xl text-ink md:text-6xl`}>{title}</h1>
      {description && <p className="mt-4 max-w-2xl text-base leading-6 text-charcoal">{description}</p>}
      <div className="mt-12">{children ?? <div className="border-t border-hairline py-24 text-center text-sm text-mute">준비 중인 내용입니다.</div>}</div>
    </section>
    <MarketingFooter />
  </main>;
}
