"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { api, ApiError } from "@/lib/api";
import { internalPathOrFallback } from "@/lib/internalPath";

const schema = z.object({
  email: z.string().email("올바른 이메일 주소를 입력하세요.").max(320),
  password: z.string().min(8, "비밀번호는 8자 이상이어야 합니다."),
  displayName: z.string().min(1).max(40).optional(),
  verificationCode: z.string().optional(),
});

type FormValues = z.infer<typeof schema>;
type CodeResponse = { verificationId: string; expiresInSeconds: number; developmentCode?: string };

export function AuthForm({ mode, nextPath = "" }: { mode: "login" | "signup"; nextPath?: string }) {
  const router = useRouter();
  const next = internalPathOrFallback(nextPath, "");
  const [serverError, setServerError] = useState("");
  const [emailCheck, setEmailCheck] = useState<{ value: string; available: boolean } | null>(null);
  const [verificationId, setVerificationId] = useState("");
  const [emailVerified, setEmailVerified] = useState(false);
  const [developmentCode, setDevelopmentCode] = useState("");
  const [emailRetryAfter, setEmailRetryAfter] = useState(0);
  const { register, handleSubmit, getValues, setValue, formState: { errors, isSubmitting } } = useForm<FormValues>({ resolver: zodResolver(schema) });

  function normalizedEmail() {
    return (getValues("email") ?? "").trim().toLowerCase();
  }

  useEffect(() => {
    if (emailRetryAfter <= 0) return;
    const timer = window.setInterval(() => setEmailRetryAfter(value => Math.max(0, value - 1)), 1_000);
    return () => window.clearInterval(timer);
  }, [emailRetryAfter]);

  async function checkEmail() {
    setServerError("");
    setEmailVerified(false);
    setVerificationId("");
    const value = normalizedEmail();
    if (!z.string().email().safeParse(value).success) {
      setServerError("이메일 형식을 먼저 확인해 주세요.");
      return;
    }
    try {
      const result = await api<{ emailAvailable?: boolean }>(`/auth/availability?email=${encodeURIComponent(value)}`);
      setEmailCheck({ value, available: result.emailAvailable === true });
    } catch (error) {
      setServerError(error instanceof Error ? error.message : "이메일 확인에 실패했습니다.");
    }
  }

  async function sendCode() {
    setServerError("");
    setEmailVerified(false);
    const email = normalizedEmail();
    if (emailCheck?.value !== email || !emailCheck.available) {
      setServerError("이메일 중복 확인을 먼저 완료해 주세요.");
      return;
    }
    try {
      const result = await api<CodeResponse>("/auth/email/send-code", { method: "POST", body: JSON.stringify({ email }) });
      setEmailRetryAfter(0);
      setVerificationId(result.verificationId);
      if (result.developmentCode) {
        setDevelopmentCode(result.developmentCode);
        setValue("verificationCode", result.developmentCode);
      }
    } catch (error) {
      if (error instanceof ApiError && error.status === 429 && error.retryAfterSeconds) {
        setEmailRetryAfter(error.retryAfterSeconds);
        setServerError(`인증번호 요청 한도를 초과했습니다. ${formatRetry(error.retryAfterSeconds)} 후 다시 시도해 주세요.`);
        return;
      }
      setServerError(error instanceof Error ? error.message : "인증번호 발송에 실패했습니다.");
    }
  }

  async function verifyCode() {
    setServerError("");
    try {
      await api("/auth/email/verify-code", { method: "POST", body: JSON.stringify({ verificationId, code: getValues("verificationCode") }) });
      setEmailVerified(true);
    } catch (error) {
      setServerError(error instanceof Error ? error.message : "이메일 인증에 실패했습니다.");
    }
  }

  const submit = handleSubmit(async values => {
    setServerError("");
    try {
      const email = values.email.trim().toLowerCase();
      if (mode === "signup") {
        if (emailCheck?.value !== email || !emailCheck.available) {
          setServerError("이메일 중복 확인을 완료해 주세요.");
          return;
        }
        if (!emailVerified || !verificationId) {
          setServerError("이메일 인증을 완료해 주세요.");
          return;
        }
        await api("/auth/signup", { method: "POST", body: JSON.stringify({ email, password: values.password, displayName: values.displayName, emailVerificationId: verificationId }) });
      }
      await api("/auth/login", { method: "POST", body: JSON.stringify({ email, password: values.password }) });
      router.push(mode === "signup" ? `/onboarding/company${next ? `?next=${encodeURIComponent(next)}` : ""}` : next || "/dashboard");
      router.refresh();
    } catch (error) {
      setServerError(error instanceof Error ? error.message : "오류가 발생했습니다.");
    }
  });

  const input = "mt-2 w-full rounded-lg border border-zinc-200 bg-white px-4 py-3 outline-none transition focus:border-coral focus:ring-4 focus:ring-coral/10";

  return <form onSubmit={submit} className="w-full rounded-2xl border border-zinc-200 bg-white p-8">
    <p className="text-xs font-semibold uppercase tracking-wide text-coral">Agentown</p>
    <h1 className="mt-2 text-2xl font-semibold tracking-tight">{mode === "login" ? "지금 만나요" : "새 AI 회사 만들기"}</h1>
    <p className="mt-2 text-sm text-zinc-500">{mode === "login" ? "이메일로 내 AI 팀에 들어가세요." : "이메일 인증 후 나만의 AI 회사를 시작하세요."}</p>
    <div className="mt-7 space-y-4">
      {mode === "signup" && <label className="block text-sm font-medium text-zinc-700">이름<input className={input} placeholder="표시할 이름" {...register("displayName")} /><small className="text-red-600">{errors.displayName?.message}</small></label>}
      <label className="block text-sm font-medium text-zinc-700">이메일<span className="mt-2 flex gap-2"><input className={`${input} mt-0`} type="email" autoComplete="email" placeholder="name@example.com" {...register("email")} />{mode === "signup" && <button type="button" onClick={checkEmail} className="shrink-0 rounded-lg border border-zinc-200 px-4 text-xs font-semibold text-zinc-700 hover:border-coral hover:text-coral">중복 확인</button>}</span><small className={emailCheck?.available ? "text-leaf" : "text-red-600"}>{errors.email?.message || emailCheck && (emailCheck.available ? "사용 가능한 이메일입니다." : "이미 사용 중인 이메일입니다.")}</small></label>
      {mode === "signup" && <>{emailCheck?.available && <button type="button" onClick={sendCode} disabled={emailRetryAfter > 0} className="w-full rounded-lg border border-coral py-3 text-sm font-semibold text-coral disabled:cursor-not-allowed disabled:border-zinc-300 disabled:text-zinc-400">{emailRetryAfter > 0 ? `${formatRetry(emailRetryAfter)} 후 재발송` : verificationId ? "인증번호 다시 발송" : "이메일 인증번호 발송"}</button>}{verificationId && <label className="block text-sm font-medium text-zinc-700">인증번호<span className="mt-2 flex gap-2"><input className={`${input} mt-0`} inputMode="numeric" maxLength={6} {...register("verificationCode")} /><button type="button" onClick={verifyCode} className="shrink-0 rounded-lg border border-zinc-200 px-4 text-xs font-semibold text-zinc-700 hover:border-coral hover:text-coral">인증 확인</button></span><small className={emailVerified ? "text-leaf" : "text-zinc-500"}>{emailVerified ? "이메일 인증이 완료되었습니다." : developmentCode && `개발 인증번호: ${developmentCode}`}</small></label>}</>}
      <label className="block text-sm font-medium text-zinc-700">비밀번호<input className={input} type="password" autoComplete={mode === "login" ? "current-password" : "new-password"} {...register("password")} /><small className="text-red-600">{errors.password?.message}</small></label>
    </div>
    {serverError && <p className="mt-4 rounded-lg bg-red-50 p-3 text-sm text-red-700">{serverError}</p>}
    <button disabled={isSubmitting} className="mt-6 w-full rounded-lg bg-coral py-3 font-semibold text-white transition hover:bg-coral/90 disabled:opacity-50">{isSubmitting ? "잠시만요…" : mode === "login" ? "로그인" : "인증하고 가입하기"}</button>
    <div className="mt-5 flex justify-center gap-4 text-sm font-medium text-zinc-500">{mode === "login" ? <><Link href="/forgot-password" className="hover:text-ink">비밀번호 찾기</Link><Link href={next ? `/signup?next=${encodeURIComponent(next)}` : "/signup"} className="hover:text-ink">회원가입</Link></> : <Link href={next ? `/login?next=${encodeURIComponent(next)}` : "/login"} className="hover:text-ink">이미 계정이 있어요</Link>}</div>
  </form>;
}

function formatRetry(seconds: number) {
  const minutes = Math.floor(seconds / 60);
  const remainder = seconds % 60;
  return minutes > 0 ? `${minutes}분 ${remainder}초` : `${remainder}초`;
}
