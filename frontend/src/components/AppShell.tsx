import Link from "next/link";
import {SessionNav} from "@/components/SessionNav";
export function AppShell({ title, kicker, children }: { title: string; kicker: string; children: React.ReactNode }) {
 return <main className="mx-auto max-w-6xl px-6 py-8"><nav className="mb-10 flex flex-wrap items-center gap-4 rounded-3xl bg-white px-6 py-3 shadow-card"><Link href="/home" className="font-black">AGENTOWN</Link><Link href="/harnesses/new" className="font-bold text-coral">AI 회사 만들기</Link><Link href="/harnesses">내 하네스</Link><Link href="/market">마켓</Link><Link href="/friends">일촌</Link><Link href="/settings/credentials">AI 연결</Link><div className="ml-auto"><SessionNav compact showAdminLink/></div></nav><p className="text-sm font-black text-coral">{kicker}</p><h1 className="mt-2 text-4xl font-black">{title}</h1><section className="mt-8">{children}</section></main>
}
export function EmptyPanel({ children }: { children: React.ReactNode }) { return <div className="rounded-3xl border border-stone-200 bg-white p-8 shadow-card">{children}</div> }
