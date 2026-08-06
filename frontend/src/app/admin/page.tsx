"use client";

import Link from "next/link";
import {FormEvent} from "react";
import {useMutation,useQuery} from "@tanstack/react-query";
import {AppShell} from "@/components/AppShell";
import {api} from "@/lib/api";

type Me={email:string;handle:string;displayName:string;role:"USER"|"ADMIN"};
type Harness={id:string;name:string;description?:string;status:string};

export default function AdminPage(){
 const me=useQuery({queryKey:["me"],queryFn:()=>api<Me>("/auth/me")});
 const harnesses=useQuery({queryKey:["harnesses"],queryFn:()=>api<Harness[]>("/harnesses"),enabled:me.data?.role==="ADMIN"});
 const register=useMutation({mutationFn:(body:unknown)=>api("/market/products",{method:"POST",body:JSON.stringify(body)})});
 function submit(event:FormEvent<HTMLFormElement>){event.preventDefault();const data=new FormData(event.currentTarget);register.mutate({harnessId:data.get("harnessId"),title:data.get("title"),description:data.get("description"),category:data.get("category"),official:true})}
 if(me.isLoading)return <AppShell kicker="SUPER ADMIN" title="권한 확인 중"><p>관리자 권한을 확인하고 있습니다.</p></AppShell>;
 if(me.data?.role!=="ADMIN")return <AppShell kicker="ACCESS DENIED" title="관리자 전용"><p className="rounded-3xl bg-red-50 p-6 text-red-700">이 화면은 ADMIN 권한이 필요합니다.</p></AppShell>;
 const published=harnesses.data?.filter(item=>item.status==="PUBLISHED")??[];
 return <AppShell kicker="SUPER ADMIN" title="Agentown 운영실"><div className="grid gap-6 lg:grid-cols-[1fr_360px]"><section className="space-y-5"><div className="grid gap-4 sm:grid-cols-3"><Link href="/harnesses/new" className="rounded-3xl bg-coral p-6 font-black text-white">+ 내 하네스 만들기</Link><Link href="/harnesses" className="rounded-3xl bg-white p-6 font-black shadow-card">내 하네스 관리</Link><Link href="/market" className="rounded-3xl bg-ink p-6 font-black text-white">공식 마켓 확인</Link></div><div className="rounded-3xl bg-white p-6 shadow-card"><h2 className="text-xl font-black">운영 원칙</h2><ul className="mt-4 space-y-2 text-sm text-stone-600"><li>• API 키 원문은 관리자도 조회할 수 없습니다.</li><li>• 사용자 실행 결과는 소유자만 볼 수 있습니다.</li><li>• 공식 등록은 관리자가 소유하고 발행한 하네스만 가능합니다.</li><li>• 별도 Worker 서버 없이 로컬 Coroutine Worker를 사용합니다.</li></ul></div></section><form onSubmit={submit} className="space-y-4 rounded-3xl bg-cream p-6"><h2 className="text-xl font-black">공식 하네스 등록</h2><label className="block text-sm font-bold">발행된 내 하네스<select name="harnessId" required className="mt-2 w-full rounded-xl border bg-white p-3"><option value="">선택</option>{published.map(item=><option key={item.id} value={item.id}>{item.name}</option>)}</select></label><label className="block text-sm font-bold">마켓 제목<input name="title" required className="mt-2 w-full rounded-xl border p-3"/></label><label className="block text-sm font-bold">카테고리<select name="category" className="mt-2 w-full rounded-xl border bg-white p-3"><option>CONTENT</option><option>DEVELOPMENT</option><option>BUSINESS</option><option>OTHER</option></select></label><label className="block text-sm font-bold">설명<textarea name="description" rows={4} className="mt-2 w-full rounded-xl border p-3"/></label><button className="w-full rounded-full bg-leaf p-3 font-black text-white">OFFICIAL로 저장</button>{register.isSuccess&&<p className="text-sm font-bold text-leaf">공식 하네스로 저장했습니다.</p>}{register.error&&<p className="text-sm text-red-600">{register.error.message}</p>}</form></div></AppShell>
}
