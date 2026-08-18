"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { LayoutDashboard, Building2, Users, Settings } from "lucide-react";
import { SessionNav } from "@/components/SessionNav";
import { AgentownLogo } from "@/components/MarketingShell";
import { blockFontClassName } from "@/lib/fonts";
import { api } from "@/lib/api";

type HomeSummary = { title?: string };

// 사이드바 4개 그룹. 첫 그룹의 라벨은 회사명으로 대체됩니다.
const groups = [
  { key: "board", icon: LayoutDashboard, label: "회사 보드", href: "/dashboard", items: [] as { href: string; label: string }[] },
  { key: "management", icon: Building2, label: "Management", href: "/management", items: [
    { href: "/home/edit", label: "공간 인테리어" },
    { href: "/management#departments", label: "부서 업무 관리" },
    { href: "/management#agenda", label: "아젠다 관리" },
  ] },
  { key: "assemble", icon: Users, label: "Assemble", href: "/assemble", items: [
    { href: "/assemble#work", label: "회사 업무 관리" },
    { href: "/assemble#hire", label: "직원 뽑기" },
  ] },
  { key: "settings", icon: Settings, label: "Setting", href: "/settings", items: [
    { href: "/settings#subscription", label: "구독 관리" },
    { href: "/settings#preferences", label: "환경 설정" },
  ] },
];

export function AppShell({ title, kicker, children }: { title: string; kicker: string; children: React.ReactNode }) {
  const pathname = usePathname() ?? "";
  const home = useQuery({ queryKey: ["home"], queryFn: () => api<HomeSummary>("/mini-homes/me"), retry: false });
  const companyName = home.data?.title ?? "내 회사";

  return <div className="flex min-h-screen bg-cloud">
    <aside className="sticky top-0 hidden h-screen w-64 shrink-0 flex-col bg-ink px-4 py-6 text-white lg:flex">
      <Link href="/dashboard" className="flex items-center gap-2 px-2 text-white">
        <AgentownLogo className="h-6 w-6" />
        <span className={`${blockFontClassName} text-2xl leading-none`}>Agentown</span>
      </Link>

      <nav className="mt-10 flex-1 space-y-6">
        {groups.map((group) => {
          const Icon = group.icon;
          const active = pathname === group.href || (group.key !== "board" && pathname.startsWith(group.href));
          const label = group.key === "board" ? companyName : group.label;
          return <div key={group.key}>
            <Link href={group.href} className={`flex items-center gap-3 rounded-pill px-3 py-2.5 text-sm font-medium transition ${active ? "bg-white text-ink" : "text-stone hover:text-white"}`}>
              <Icon className="h-4 w-4 shrink-0" aria-hidden="true" />
              <span className="truncate">{label}</span>
            </Link>
            {group.items.length > 0 && <div className="mt-1 space-y-0.5 pl-10">
              {group.items.map((item) => <Link key={item.href} href={item.href} className="block py-1.5 text-sm text-stone transition hover:text-white">{item.label}</Link>)}
            </div>}
          </div>;
        })}
      </nav>
    </aside>

    <div className="flex min-w-0 flex-1 flex-col">
      <header className="sticky top-0 z-20 bg-white shadow-hairline">
        <div className="flex h-16 items-center justify-between gap-4 px-6 md:px-10">
          <Link href="/dashboard" className="flex items-center gap-2 text-ink lg:hidden">
            <AgentownLogo className="h-5 w-5" />
            <span className={`${blockFontClassName} text-xl leading-none`}>Agentown</span>
          </Link>
          <nav className="hidden items-center gap-6 text-sm font-medium text-ink lg:flex">
            {groups.map((group) => <Link key={group.key} href={group.href} className="hover:opacity-60">{group.key === "board" ? companyName : group.label}</Link>)}
          </nav>
          <SessionNav compact showAdminLink hideGreeting />
        </div>
      </header>

      <main className="flex-1 px-6 py-10 md:px-10">
        <p className="text-xs font-medium uppercase tracking-[.2em] text-mute">{kicker}</p>
        <h1 className={`${blockFontClassName} mt-2 text-5xl text-ink md:text-6xl`}>{title}</h1>
        <section className="mt-10">{children}</section>
      </main>
    </div>
  </div>;
}

// Nike 문법의 기본 컨테이너: 라디우스 0, 그림자 0, 흰 배경 + 헤어라인
export function Panel({ title, action, children, className = "" }: { title?: string; action?: React.ReactNode; children: React.ReactNode; className?: string }) {
  return <section className={`border border-hairline bg-white p-6 ${className}`}>
    {(title || action) && <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
      {title && <h2 className="text-base font-medium text-ink">{title}</h2>}
      {action}
    </div>}
    {children}
  </section>;
}

export function EmptyPanel({ children }: { children: React.ReactNode }) {
  return <div className="border border-hairline bg-white p-8 text-mute">{children}</div>;
}
