"use client";

import Link from "next/link";
import {useMutation,useQuery,useQueryClient} from "@tanstack/react-query";
import {useRouter} from "next/navigation";
import {api} from "@/lib/api";

type Me={displayName:string;handle:string;role:"USER"|"ADMIN"};

export function SessionNav({compact=false,showAdminLink=false}:{compact?:boolean;showAdminLink?:boolean}){
 const router=useRouter();const qc=useQueryClient();
 const me=useQuery({queryKey:["me"],queryFn:()=>api<Me>("/auth/me"),retry:false});
 const logout=useMutation({mutationFn:()=>api<void>("/auth/logout",{method:"POST"}),onSuccess:()=>{qc.clear();router.push("/");router.refresh()}});
 if(me.isLoading)return <span className="text-xs text-stone-400">세션 확인 중</span>;
 if(!me.data)return <Link href="/login" className="rounded-full bg-ink px-4 py-2 text-sm font-bold text-white">로그인</Link>;
 return <div className={`flex items-center ${compact?"gap-2":"gap-3"}`}>{showAdminLink&&me.data.role==="ADMIN"&&<Link href="/admin" className="text-sm font-black text-coral">전체 관리</Link>}<Link href="/home" className="text-sm font-bold"><span className="text-coral">{me.data.displayName}</span>님, 안녕하세요</Link><button onClick={()=>logout.mutate()} disabled={logout.isPending} className="rounded-full border bg-white px-4 py-2 text-xs font-black">로그아웃</button></div>;
}
