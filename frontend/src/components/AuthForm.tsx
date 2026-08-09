"use client";

import {zodResolver} from "@hookform/resolvers/zod";
import Link from "next/link";
import {useRouter} from "next/navigation";
import {useState} from "react";
import {useForm} from "react-hook-form";
import {z} from "zod";
import {api} from "@/lib/api";

const schema=z.object({
 handle:z.string().regex(/^[a-z0-9_]{3,30}$/,"아이디는 영문 소문자, 숫자, 밑줄 3~30자로 입력하세요."),
 password:z.string().min(8,"비밀번호는 8자 이상이어야 합니다."),
 displayName:z.string().min(1).max(40).optional(),
 phone:z.string().optional(),
 verificationCode:z.string().optional(),
});
type FormValues=z.infer<typeof schema>;
type CodeResponse={verificationId:string;expiresInSeconds:number;developmentCode?:string};

export function AuthForm({mode}:{mode:"login"|"signup"}){
 const router=useRouter();const [serverError,setServerError]=useState("");const [idCheck,setIdCheck]=useState<{value:string;available:boolean}|null>(null);const [verificationId,setVerificationId]=useState("");const [phoneVerified,setPhoneVerified]=useState(false);const [developmentCode,setDevelopmentCode]=useState("");
 const {register,handleSubmit,getValues,setValue,formState:{errors,isSubmitting}}=useForm<FormValues>({resolver:zodResolver(schema)});
 async function checkId(){setServerError("");const value=getValues("handle")?.trim()??"";if(!/^[a-z0-9_]{3,30}$/.test(value)){setServerError("아이디 형식을 먼저 확인해 주세요.");return}try{const result=await api<{handleAvailable?:boolean}>(`/auth/availability?handle=${encodeURIComponent(value)}`);setIdCheck({value,available:result.handleAvailable===true})}catch(error){setServerError(error instanceof Error?error.message:"아이디 확인에 실패했습니다.")}}
 async function sendCode(){setServerError("");setPhoneVerified(false);try{const result=await api<CodeResponse>("/auth/phone/send-code",{method:"POST",body:JSON.stringify({phone:getValues("phone")})});setVerificationId(result.verificationId);if(result.developmentCode){setDevelopmentCode(result.developmentCode);setValue("verificationCode",result.developmentCode)}}catch(error){setServerError(error instanceof Error?error.message:"인증번호 발송에 실패했습니다.")}}
 async function verifyCode(){setServerError("");try{await api("/auth/phone/verify-code",{method:"POST",body:JSON.stringify({verificationId,code:getValues("verificationCode")})});setPhoneVerified(true)}catch(error){setServerError(error instanceof Error?error.message:"휴대폰 인증에 실패했습니다.")}}
 const submit=handleSubmit(async values=>{setServerError("");try{if(mode==="signup"){if(idCheck?.value!==values.handle||!idCheck.available){setServerError("아이디 중복 확인을 완료해 주세요.");return}if(!phoneVerified||!verificationId){setServerError("휴대폰 인증을 완료해 주세요.");return}await api("/auth/signup",{method:"POST",body:JSON.stringify({handle:values.handle,password:values.password,displayName:values.displayName,phone:values.phone,phoneVerificationId:verificationId})})}await api("/auth/login",{method:"POST",body:JSON.stringify({loginId:values.handle,password:values.password})});router.push("/dashboard");router.refresh()}catch(error){setServerError(error instanceof Error?error.message:"오류가 발생했습니다.")}});
 const input="mt-2 w-full rounded-2xl border border-stone-200 bg-white px-4 py-3 outline-none transition focus:border-coral focus:ring-4 focus:ring-coral/10";
 return <form onSubmit={submit} className="w-full rounded-[2rem] bg-white p-8 shadow-card"><p className="text-sm font-bold text-coral">AGENT VILLAGE</p><h1 className="mt-2 text-3xl font-black">{mode==="login"?"지금 만나요":"새 AI 회사 만들기"}</h1><p className="mt-2 text-sm text-stone-500">{mode==="login"?"아이디로 내 AI 팀에 들어가세요.":"휴대폰 인증 후 나만의 AI 회사를 시작하세요."}</p><div className="mt-7 space-y-4">
  {mode==="signup"&&<label className="block text-sm font-semibold">이름<input className={input} placeholder="표시할 이름" {...register("displayName")}/><small className="text-red-600">{errors.displayName?.message}</small></label>}
  <label className="block text-sm font-semibold">아이디<span className="mt-2 flex gap-2"><input className={`${input} mt-0`} autoComplete="username" placeholder="my_village" {...register("handle")}/>{mode==="signup"&&<button type="button" onClick={checkId} className="shrink-0 rounded-2xl border px-4 text-xs font-black">중복 확인</button>}</span><small className={idCheck?.available?"text-leaf":"text-red-600"}>{errors.handle?.message||idCheck&&(idCheck.available?"사용 가능한 아이디입니다.":"이미 사용 중인 아이디입니다.")}</small></label>
  <label className="block text-sm font-semibold">비밀번호<input className={input} type="password" autoComplete={mode==="login"?"current-password":"new-password"} {...register("password")}/><small className="text-red-600">{errors.password?.message}</small></label>
  {mode==="signup"&&<><label className="block text-sm font-semibold">휴대폰 번호<span className="mt-2 flex gap-2"><input className={`${input} mt-0`} inputMode="tel" placeholder="010-1234-5678" {...register("phone")}/><button type="button" onClick={sendCode} className="shrink-0 rounded-2xl border px-4 text-xs font-black">인증번호 발송</button></span></label>{verificationId&&<label className="block text-sm font-semibold">인증번호<span className="mt-2 flex gap-2"><input className={`${input} mt-0`} inputMode="numeric" maxLength={6} {...register("verificationCode")}/><button type="button" onClick={verifyCode} className="shrink-0 rounded-2xl border px-4 text-xs font-black">인증 확인</button></span><small className={phoneVerified?"text-leaf":"text-stone-500"}>{phoneVerified?"휴대폰 인증이 완료되었습니다.":developmentCode&&`로컬 인증번호: ${developmentCode}`}</small></label>}</>}
 </div>{serverError&&<p className="mt-4 rounded-xl bg-red-50 p-3 text-sm text-red-700">{serverError}</p>}<button disabled={isSubmitting} className="mt-6 w-full rounded-2xl bg-ink py-4 font-bold text-white disabled:opacity-50">{isSubmitting?"잠시만요…":mode==="login"?"로그인":"인증하고 가입하기"}</button><div className="mt-5 flex justify-center gap-4 text-sm font-bold text-stone-500">{mode==="login"?<><Link href="/forgot-password">비밀번호 찾기</Link><Link href="/signup">회원가입</Link></>:<Link href="/login">이미 계정이 있어요</Link>}</div></form>;
}
