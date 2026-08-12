"use client";
import Link from "next/link";
import {useQuery} from "@tanstack/react-query";
import {AppShell} from "@/components/AppShell";
import {api} from "@/lib/api";
type Harness={id:string;name:string;description?:string;status:string;visibility:string};
export default function Page(){const query=useQuery({queryKey:["harnesses"],queryFn:()=>api<Harness[]>("/harnesses")});return <AppShell kicker="MY GOALS" title="목표"><div className="mb-6 flex justify-end"><Link href="/harnesses/new" className="rounded-full bg-coral px-5 py-3 font-bold text-white">+ 새 목표</Link></div><div className="grid gap-4 md:grid-cols-2">{query.data?.map(item=><Link key={item.id} href={`/harnesses/${item.id}/edit`} className="rounded-3xl bg-white p-6 shadow-card transition hover:-translate-y-1"><div className="flex justify-between"><b className="text-xl">{item.name}</b><span className="text-xs font-bold text-leaf">{item.status}</span></div><p className="mt-3 text-stone-500">{item.description||"설명 없음"}</p></Link>)}{query.data?.length===0&&<div className="rounded-3xl border-2 border-dashed p-10 text-center text-stone-500">팀원을 먼저 채용한 뒤, 첫 목표를 만들고 실행 순서를 연결하세요.</div>}</div></AppShell>}
