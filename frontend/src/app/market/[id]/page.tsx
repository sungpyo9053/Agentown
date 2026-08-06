"use client";

import {FormEvent,useEffect,useState} from "react";
import {useMutation,useQuery,useQueryClient} from "@tanstack/react-query";
import {useRouter} from "next/navigation";
import {AppShell} from "@/components/AppShell";
import {api} from "@/lib/api";

type Product={id:string;title:string;description?:string;category:string;official:boolean;cloneCount:number;likeCount:number};
type Review={id:string;rating:number;content:string;createdAt:string};

export default function Page({params}:{params:Promise<{id:string}>}){
 const[id,setId]=useState("");const router=useRouter();const qc=useQueryClient();
 useEffect(()=>{params.then(v=>setId(v.id))},[params]);
 const product=useQuery({queryKey:["product",id],queryFn:()=>api<Product>(`/market/products/${id}`),enabled:!!id});
 const reviews=useQuery({queryKey:["product-reviews",id],queryFn:()=>api<Review[]>(`/market/products/${id}/reviews`),enabled:!!id});
 const clone=useMutation({mutationFn:()=>api<{id:string}>(`/market/products/${id}/clone`,{method:"POST"}),onSuccess:data=>router.push(`/harnesses/${data.id}/edit`)});
 const like=useMutation({mutationFn:()=>api(`/market/products/${id}/likes`,{method:"POST"}),onSuccess:()=>product.refetch()});
 const review=useMutation({mutationFn:(body:unknown)=>api(`/market/products/${id}/reviews`,{method:"POST",body:JSON.stringify(body)}),onSuccess:()=>qc.invalidateQueries({queryKey:["product-reviews",id]})});
 function submit(event:FormEvent<HTMLFormElement>){event.preventDefault();const form=new FormData(event.currentTarget);review.mutate({rating:Number(form.get("rating")),content:form.get("content")})}
 return <AppShell kicker={product.data?.official?"OFFICIAL HARNESS":"COMMUNITY HARNESS"} title={product.data?.title??"무료 하네스"}>
  <div className="grid gap-6 lg:grid-cols-[1fr_320px]"><section className="space-y-6"><div className="rounded-3xl bg-white p-8 shadow-card"><span className="rounded-full bg-cream px-3 py-1 text-xs font-bold">{product.data?.category}</span><p className="mt-6 whitespace-pre-wrap leading-7 text-stone-600">{product.data?.description||"설명이 없습니다."}</p><div className="mt-8 rounded-2xl bg-stone-50 p-5 text-sm"><b>안전한 스냅샷 복제</b><p className="mt-2 text-stone-500">에이전트 정의와 실행 순서만 복제되며 API 키, credentialId, 사용자 입력, 실행 결과는 제외됩니다.</p></div></div>
  <div className="rounded-3xl bg-white p-6 shadow-card"><h2 className="text-xl font-black">사용 후기</h2><div className="mt-4 space-y-3">{reviews.data?.map(item=><div key={item.id} className="rounded-2xl bg-stone-50 p-4"><b>{"★".repeat(item.rating)}</b><p className="mt-2 text-sm text-stone-600">{item.content}</p></div>)}{reviews.data?.length===0&&<p className="text-sm text-stone-500">첫 후기를 남겨 주세요.</p>}</div><form onSubmit={submit} className="mt-5 grid gap-3"><select name="rating" className="rounded-xl border p-3"><option value="5">★★★★★</option><option value="4">★★★★</option><option value="3">★★★</option><option value="2">★★</option><option value="1">★</option></select><textarea name="content" required maxLength={1000} rows={3} placeholder="복제해 사용한 경험을 알려주세요." className="rounded-xl border p-3"/><button className="rounded-xl bg-ink p-3 font-bold text-white">후기 저장</button>{review.error&&<p className="text-sm text-red-600">{review.error.message}</p>}</form></div></section>
  <aside className="h-fit space-y-3 rounded-3xl bg-ink p-6 text-white"><p>복제 {product.data?.cloneCount??0} · 좋아요 {product.data?.likeCount??0}</p><button onClick={()=>clone.mutate()} className="w-full rounded-full bg-coral p-3 font-bold">내 회사로 무료 복제</button><button onClick={()=>like.mutate()} className="w-full rounded-full border border-white/30 p-3 font-bold">좋아요</button>{(clone.error||like.error)&&<p className="text-sm text-red-300">{(clone.error||like.error)?.message}</p>}</aside></div>
 </AppShell>
}
