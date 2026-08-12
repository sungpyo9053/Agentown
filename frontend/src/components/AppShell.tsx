"use client";
import Link from "next/link";
import {useQuery} from "@tanstack/react-query";
import {SessionNav} from "@/components/SessionNav";
import {api} from "@/lib/api";

type HomeSummary = { title?: string };

export function AppShell({ title, kicker, children }: { title: string; kicker: string; children: React.ReactNode }) {
 // 탭 구조: {회사이름}(=오피스/목표) · 구성원 · ...(스페이서)... · 마이페이지
 // 목표(하네스)·API 키는 더 이상 최상단 탭이 아니라 회사이름 탭(대시보드) 안의 STEP 2/3 섹션으로 재배치됨.
 // 마켓·일촌은 현재 스코프에서 제외 — 페이지·백엔드는 유지, 필요 시 이 nav에 다시 추가
 const home = useQuery({ queryKey: ["home"], queryFn: () => api<HomeSummary>("/mini-homes/me"), retry: false });
 return <main className="mx-auto max-w-6xl px-6 py-8"><nav className="mb-10 flex flex-wrap items-center gap-5 rounded-2xl border border-zinc-200 bg-white px-6 py-3 text-sm"><Link href="/dashboard" className="font-semibold tracking-tight">{home.data?.title ?? "Agentown"}</Link><Link href="/dashboard#team" className="font-medium text-zinc-600 hover:text-ink">구성원</Link><div className="ml-auto flex items-center gap-4"><Link href="/me" className="font-medium text-zinc-600 hover:text-ink">마이페이지</Link><SessionNav compact showAdminLink hideGreeting/></div></nav><p className="text-xs font-semibold uppercase tracking-wide text-coral">{kicker}</p><h1 className="mt-2 text-3xl font-semibold tracking-tight">{title}</h1><section className="mt-8">{children}</section></main>
}
export function EmptyPanel({ children }: { children: React.ReactNode }) { return <div className="rounded-2xl border border-zinc-200 bg-white p-8">{children}</div> }
