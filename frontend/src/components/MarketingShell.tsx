import Link from "next/link";
import { SessionNav } from "@/components/SessionNav";

// 마케팅(비로그인) 페이지 공통 메뉴. 앱 내부 화면은 AppShell을 사용합니다.
export const marketingNav = [
  { href: "/", label: "Home" },
  { href: "/features", label: "Feature" },
  { href: "/pricing", label: "Pricing" },
  { href: "/about", label: "About" },
  { href: "/contact", label: "Contact" },
];

export function AgentownLogo() {
  return <svg width="22" height="22" viewBox="0 0 22 22" fill="none" aria-hidden="true">
    <rect x="1" y="1" width="9" height="9" rx="2" className="fill-coral" />
    <rect x="12" y="1" width="9" height="9" rx="2" className="fill-zinc-200" />
    <rect x="1" y="12" width="9" height="9" rx="2" className="fill-zinc-200" />
    <rect x="12" y="12" width="9" height="9" rx="2" className="fill-ink" />
  </svg>;
}

export function MarketingHeader() {
  return <header className="border-b border-zinc-200">
    <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-6 py-5">
      <Link href="/" className="flex shrink-0 items-center gap-2 text-lg font-semibold tracking-tight"><AgentownLogo />Agentown</Link>
      <nav className="hidden items-center gap-6 text-sm font-medium text-zinc-500 md:flex">
        {marketingNav.map((item) => <Link key={item.href} href={item.href} className="hover:text-ink">{item.label}</Link>)}
      </nav>
      <div className="flex items-center gap-3"><SessionNav /></div>
    </div>
  </header>;
}

export function MarketingFooter() {
  return <footer className="border-t border-zinc-200 bg-white">
    <div className="mx-auto max-w-6xl px-6 py-12">
      <Link href="/" className="flex items-center gap-2 text-lg font-semibold tracking-tight"><AgentownLogo />Agentown</Link>
      <p className="mt-3 max-w-sm text-sm leading-6 text-zinc-500">Assemble your AI team. 문제를 풀고 싶은 사람이 회사를 차리고, 팀원을 뽑아 함께 해결합니다.</p>
      <p className="mt-10 border-t border-zinc-100 pt-6 text-xs text-zinc-400">© {new Date().getFullYear()} Agentown. All rights reserved.</p>
    </div>
  </footer>;
}

// Feature/About/Contact 등 아직 내용을 채우지 않은 페이지용 골격
export function MarketingPage({ kicker, title, description, children }: { kicker: string; title: string; description?: string; children?: React.ReactNode }) {
  return <main className="flex min-h-screen flex-col">
    <MarketingHeader />
    <section className="mx-auto w-full max-w-6xl flex-1 px-6 py-20">
      <p className="text-xs font-semibold uppercase tracking-wide text-coral">{kicker}</p>
      <h1 className="mt-2 text-3xl font-semibold tracking-tight text-ink md:text-4xl">{title}</h1>
      {description && <p className="mt-4 max-w-2xl text-base leading-7 text-zinc-600">{description}</p>}
      <div className="mt-10">{children ?? <div className="rounded-2xl border border-dashed border-zinc-200 bg-white p-16 text-center text-sm text-zinc-400">준비 중인 내용입니다.</div>}</div>
    </section>
    <MarketingFooter />
  </main>;
}
