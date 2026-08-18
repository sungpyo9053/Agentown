"use client";

import { FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { AppShell, Panel } from "@/components/AppShell";

type Home = { id: string; title: string; introduction?: string; backgroundKey?: string; visibility?: string };

export default function AgendaPage() {
  const client = useQueryClient();
  const home = useQuery({ queryKey: ["home"], queryFn: () => api<Home>("/mini-homes/me") });
  const saveAgenda = useMutation({
    mutationFn: (body: unknown) => api<Home>("/mini-homes/me", { method: "PATCH", body: JSON.stringify(body) }),
    onSuccess: (data) => client.setQueryData(["home"], data),
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    saveAgenda.mutate({
      title: form.get("title"),
      introduction: form.get("introduction"),
      backgroundKey: home.data?.backgroundKey ?? "office-warm",
      visibility: home.data?.visibility ?? "PUBLIC",
    });
  }

  return <AppShell kicker="MANAGEMENT" title="아젠다 관리">
    <Panel>
      <p className="mb-5 text-sm leading-6 text-charcoal">우리 회사가 무엇을 하는 곳인지 정의합니다. 이 내용은 구성원이 일할 때의 기준이 됩니다.</p>
      <form key={home.data?.id} onSubmit={submit} className="max-w-2xl space-y-4">
        <label className="block text-sm font-medium text-ink">회사 이름
          <input name="title" defaultValue={home.data?.title} required maxLength={60} className="mt-2 w-full border border-hairline px-4 py-3 outline-none focus:border-ink" />
        </label>
        <label className="block text-sm font-medium text-ink">회사가 하는 일
          <textarea name="introduction" defaultValue={home.data?.introduction} rows={5} placeholder="예: 리서치부터 초안, 검수, 발행까지 맡아 하는 콘텐츠 팀" className="mt-2 w-full border border-hairline px-4 py-3 outline-none focus:border-ink" />
        </label>
        <button disabled={saveAgenda.isPending} className="rounded-pill bg-ink px-8 py-4 text-sm font-medium text-white transition active:scale-95 active:opacity-50 disabled:opacity-40">{saveAgenda.isPending ? "저장 중…" : "아젠다 저장"}</button>
        {saveAgenda.isSuccess && <p className="text-sm text-leaf">저장했습니다.</p>}
        {saveAgenda.error && <p className="text-sm text-sale">{saveAgenda.error.message}</p>}
      </form>
    </Panel>
  </AppShell>;
}
