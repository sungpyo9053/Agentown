"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { api } from "@/lib/api";

const schema = z.object({
  email: z.string().email("이메일 형식을 확인해 주세요."),
  password: z.string().min(8, "비밀번호는 8자 이상이어야 합니다."),
  handle: z.string().regex(/^[a-z0-9_]{3,30}$/, "영문 소문자, 숫자, 밑줄 3~30자로 입력하세요.").optional(),
  displayName: z.string().min(1).max(40).optional(),
});
type FormValues = z.infer<typeof schema>;

export function AuthForm({ mode }: { mode: "login" | "signup" }) {
  const router = useRouter();
  const [serverError, setServerError] = useState("");
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<FormValues>({ resolver: zodResolver(schema) });
  const submit = handleSubmit(async (values) => {
    setServerError("");
    try {
      if (mode === "signup") {
        await api("/auth/signup", { method: "POST", body: JSON.stringify(values) });
      }
      await api("/auth/login", { method: "POST", body: JSON.stringify({ email: values.email, password: values.password }) });
      router.push("/dashboard"); router.refresh();
    } catch (error) { setServerError(error instanceof Error ? error.message : "오류가 발생했습니다."); }
  });
  const input = "mt-2 w-full rounded-2xl border border-stone-200 bg-white px-4 py-3 outline-none transition focus:border-coral focus:ring-4 focus:ring-coral/10";
  return <form onSubmit={submit} className="w-full rounded-[2rem] bg-white p-8 shadow-card">
    <p className="text-sm font-bold text-coral">AGENT VILLAGE</p>
    <h1 className="mt-2 text-3xl font-black">{mode === "login" ? "다시 만나요" : "새 마을 만들기"}</h1>
    <p className="mt-2 text-sm text-stone-500">{mode === "login" ? "내 AI 팀이 기다리고 있어요." : "첫 번째 AI 구성원을 맞이할 준비를 해요."}</p>
    <div className="mt-7 space-y-4">
      <label className="block text-sm font-semibold">이메일<input className={input} type="email" {...register("email")} /><small className="text-red-600">{errors.email?.message}</small></label>
      <label className="block text-sm font-semibold">비밀번호<input className={input} type="password" {...register("password")} /><small className="text-red-600">{errors.password?.message}</small></label>
      {mode === "signup" && <>
        <label className="block text-sm font-semibold">핸들<input className={input} placeholder="my_village" {...register("handle")} /><small className="text-red-600">{errors.handle?.message}</small></label>
        <label className="block text-sm font-semibold">표시 이름<input className={input} placeholder="마을지기" {...register("displayName")} /></label>
      </>}
    </div>
    {serverError && <p className="mt-4 rounded-xl bg-red-50 p-3 text-sm text-red-700">{serverError}</p>}
    <button disabled={isSubmitting} className="mt-6 w-full rounded-2xl bg-ink py-4 font-bold text-white disabled:opacity-50">{isSubmitting ? "잠시만요…" : mode === "login" ? "로그인" : "가입하고 시작하기"}</button>
  </form>;
}

