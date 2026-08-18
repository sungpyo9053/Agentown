"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useSyncExternalStore } from "react";
import { useQuery } from "@tanstack/react-query";
import { LayoutDashboard, Building2, Users, Settings, PanelLeftClose, PanelLeftOpen } from "lucide-react";
import { SessionNav } from "@/components/SessionNav";
import { AgentownLogo } from "@/components/MarketingShell";
import { blockFontClassName } from "@/lib/fonts";
import { api } from "@/lib/api";

const SIDEBAR_KEY = "agentown.sidebar.collapsed";

/* 사이드바 접힘 상태를 localStorage에 기억시키는 작은 스토어.
   서버 렌더에서는 항상 '펼침'을 반환해 hydration 불일치를 피합니다. */
const sidebarListeners = new Set<() => void>();
const sidebarStore = {
  subscribe(listener: () => void) {
    sidebarListeners.add(listener);
    return () => { sidebarListeners.delete(listener); };
  },
  get: () => window.localStorage.getItem(SIDEBAR_KEY) === "1",
  getServerSnapshot: () => false,
  toggle() {
    window.localStorage.setItem(SIDEBAR_KEY, this.get() ? "0" : "1");
    sidebarListeners.forEach((listener) => listener());
  },
};

type HomeSummary = { title?: string };

// 사이드바 4개 그룹. 첫 그룹의 라벨은 회사명으로 대체됩니다.
const groups = [
  { key: "board", icon: LayoutDashboard, label: "회사 보드", href: "/dashboard", items: [] as { href: string; label: string }[] },
  // Management = 오피스 관리, Assemble = 사람(직원)과 그들의 일하는 방식(하네스·가이드) 관리
  // 하위 메뉴는 각각 독립 페이지 — 선택한 메뉴의 내용만 보입니다.
  { key: "management", icon: Building2, label: "Management", href: "/management/interior", items: [
    { href: "/management/interior", label: "공간 인테리어" },
    { href: "/management/departments", label: "부서 관리" },
    { href: "/management/agenda", label: "아젠다 관리" },
  ] },
  { key: "assemble", icon: Users, label: "Assemble", href: "/assemble/hire", items: [
    { href: "/assemble/hire", label: "직원 뽑기" },
    { href: "/assemble/guides", label: "가이드 관리" },
    { href: "/assemble/harness", label: "하네스 구성" },
  ] },
  { key: "settings", icon: Settings, label: "Setting", href: "/settings/subscription", items: [
    { href: "/settings/subscription", label: "구독 관리" },
    { href: "/settings/preferences", label: "환경 설정" },
    { href: "/settings/credentials", label: "AI 연결" },
  ] },
];

const groupPrefix: Record<string, string> = { board: "/dashboard", management: "/management", assemble: "/assemble", settings: "/settings" };

export function AppShell({ title, kicker, children }: { title: string; kicker: string; children: React.ReactNode }) {
  const pathname = usePathname() ?? "";
  const home = useQuery({ queryKey: ["home"], queryFn: () => api<HomeSummary>("/mini-homes/me"), retry: false });
  const companyName = home.data?.title ?? "내 회사";

  // 접힘 상태는 브라우저에 기억시켜 페이지를 옮겨도 유지합니다.
  const collapsed = useSyncExternalStore(sidebarStore.subscribe, sidebarStore.get, sidebarStore.getServerSnapshot);
  const toggle = () => sidebarStore.toggle();

  return <div className="flex min-h-screen bg-cloud">
    <aside className={`sticky top-0 hidden h-screen shrink-0 flex-col bg-ink py-6 text-white transition-[width] duration-200 lg:flex ${collapsed ? "w-20 px-3" : "w-64 px-4"}`}>
      <div className={`flex items-center ${collapsed ? "justify-center" : "justify-between gap-2 px-2"}`}>
        {!collapsed && <Link href="/dashboard" className="flex items-center gap-2 text-white">
          <AgentownLogo className="h-6 w-6" />
          <span className={`${blockFontClassName} text-2xl leading-none`}>Agentown</span>
        </Link>}
        <button type="button" onClick={toggle} aria-label={collapsed ? "메뉴 펼치기" : "메뉴 접기"} title={collapsed ? "메뉴 펼치기" : "메뉴 접기"}
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-pill text-stone transition hover:bg-white/10 hover:text-white">
          {collapsed ? <PanelLeftOpen className="h-5 w-5" /> : <PanelLeftClose className="h-5 w-5" />}
        </button>
      </div>

      <nav className={`flex-1 space-y-6 ${collapsed ? "mt-8" : "mt-10"}`}>
        {groups.map((group) => {
          const Icon = group.icon;
          const inGroup = pathname.startsWith(groupPrefix[group.key]);
          const label = group.key === "board" ? companyName : group.label;
          return <div key={group.key}>
            <Link href={group.href} title={collapsed ? label : undefined} aria-label={label}
              className={`flex items-center rounded-pill text-sm font-medium transition ${collapsed ? "justify-center px-0 py-3" : "gap-3 px-3 py-2.5"} ${inGroup ? "bg-white text-ink" : "text-stone hover:text-white"}`}>
              <Icon className="h-5 w-5 shrink-0" aria-hidden="true" />
              {!collapsed && <span className="truncate">{label}</span>}
            </Link>
            {/* 하위 메뉴는 펼쳐진 상태에서, 해당 그룹에 들어와 있을 때만 보입니다 */}
            {!collapsed && inGroup && group.items.length > 0 && <div className="mt-1 space-y-0.5 pl-10">
              {group.items.map((item) => <Link key={item.href} href={item.href}
                className={`block py-1.5 text-sm transition ${pathname === item.href ? "font-medium text-white" : "text-stone hover:text-white"}`}>{item.label}</Link>)}
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
