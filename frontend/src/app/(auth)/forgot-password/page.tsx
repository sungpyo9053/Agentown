"use client";

import Link from "next/link";
import {FormEvent,useState} from "react";
import {api} from "@/lib/api";

type TemporaryPasswordResponse={message:string;developmentTemporaryPassword?:string};

export default function ForgotPasswordPage(){
 const [result,setResult]=useState<TemporaryPasswordResponse|null>(null);const [error,setError]=useState("");
 async function submit(event:FormEvent<HTMLFormElement>){event.preventDefault();setError("");const data=new FormData(event.currentTarget);try{setResult(await api<TemporaryPasswordResponse>("/auth/password/temporary",{method:"POST",body:JSON.stringify({email:data.get("email")})}))}catch(e){setError(e instanceof Error?e.message:"임시 비밀번호 발송에 실패했습니다.")}}
 const input="mt-2 w-full rounded-2xl border border-stone-200 px-4 py-3 outline-none focus:border-coral";
 return <div className="w-full rounded-[2rem] bg-white p-8 shadow-card"><p className="text-sm font-black text-coral">ACCOUNT RECOVERY</p><h1 className="mt-2 text-3xl font-black">비밀번호 찾기</h1><p className="mt-2 text-sm text-stone-500">가입한 이메일로 임시 비밀번호를 전송합니다.</p><form onSubmit={submit} className="mt-7 space-y-4"><label className="block text-sm font-bold">이메일<input name="email" type="email" autoComplete="email" required placeholder="name@example.com" className={input}/></label><button className="w-full rounded-2xl bg-ink py-4 font-bold text-white">임시 비밀번호 발송</button></form>{result&&<div className="mt-5 rounded-2xl bg-emerald-50 p-5"><p className="text-sm text-leaf">{result.message}</p>{result.developmentTemporaryPassword&&<p className="mt-3 font-black">개발 임시 비밀번호: <code>{result.developmentTemporaryPassword}</code></p>}</div>}{error&&<p className="mt-4 rounded-xl bg-red-50 p-3 text-sm text-red-700">{error}</p>}<Link href="/login" className="mt-5 block text-center text-sm font-bold">로그인으로 돌아가기</Link></div>;
}
