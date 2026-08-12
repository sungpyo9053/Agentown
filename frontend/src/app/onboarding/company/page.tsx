"use client";

import { FormEvent } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";

type Home = { id: string; title: string; introduction?: string; backgroundKey?: string; visibility?: string };

export default function OnboardingCompanyPage() {
  const router = useRouter();
  const home = useQuery({ queryKey: ["home"], queryFn: () => api<Home>("/mini-homes/me") });
  const save = useMutation({
    mutationFn: (body: unknown) => api<Home>("/mini-homes/me", { method: "PATCH", body: JSON.stringify(body) }),
    onSuccess: () => router.push("/dashboard"),
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    save.mutate({
      title: form.get("title"),
      introduction: form.get("introduction"),
      backgroundKey: home.data?.backgroundKey ?? "office-warm",
      visibility: home.data?.visibility ?? "PUBLIC",
    });
  }

  return <main className="mx-auto flex min-h-screen max-w-xl flex-col justify-center px-6 py-16">
    <p className="text-xs font-semibold uppercase tracking-wide text-coral">STEP 0 · WELCOME</p>
    <h1 className="mt-2 text-3xl font-semibold tracking-tight text-ink">회사 만들기</h1>
    <p className="mt-2 text-sm text-zinc-500">회사 이름과 하는 일을 정해주세요. 이 내용은 나중에 언제든 다시 수정할 수 있어요.</p>
    <form key={home.data?.id} onSubmit={submit} className="mt-8 space-y-5 rounded-2xl border border-zinc-200 bg-white p-8">
      <label className="block text-sm font-medium text-zinc-700">회사 이름
        <input name="title" defaultValue={home.data?.title} required maxLength={60} placeholder="예: 블록기획 AI 오피스" className="mt-2 w-full rounded-lg border border-zinc-200 px-4 py-3 outline-none transition focus:border-coral focus:ring-4 focus:ring-coral/10" />
      </label>
      <label className="block text-sm font-medium text-zinc-700">이 회사가 하는 일
        <textarea name="introduction" defaultValue={home.data?.introduction} rows={3} placeholder="예: 리서치부터 초안, 검수, 발행까지 맡아 하는 콘텐츠 팀" className="mt-2 w-full rounded-lg border border-zinc-200 px-4 py-3 outline-none transition focus:border-coral focus:ring-4 focus:ring-coral/10" />
      </label>
      {save.error && <p className="rounded-lg bg-red-50 p-3 text-sm text-red-700">{save.error.message}</p>}
      <button disabled={save.isPending} className="w-full rounded-lg bg-coral py-3 font-semibold text-white transition hover:bg-coral/90 disabled:opacity-50">{save.isPending ? "만드는 중…" : "회사 만들고 시작하기"}</button>
    </form>
  </main>;
}
