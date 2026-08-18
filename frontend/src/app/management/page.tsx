"use client";

import { FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { api } from "@/lib/api";
import { AppShell, Panel } from "@/components/AppShell";

type Home = { id: string; title: string; introduction?: string; backgroundKey?: string; visibility?: string };
type Agent = { id: string; name: string; role: string; department?: string };

export default function ManagementPage() {
  const client = useQueryClient();
  const home = useQuery({ queryKey: ["home"], queryFn: () => api<Home>("/mini-homes/me") });
  const agents = useQuery({ queryKey: ["agents"], queryFn: () => api<Agent[]>("/agents") });
  const saveAgenda = useMutation({
    mutationFn: (body: unknown) => api<Home>("/mini-homes/me", { method: "PATCH", body: JSON.stringify(body) }),
    onSuccess: (data) => client.setQueryData(["home"], data),
  });

  const departments = Object.entries((agents.data ?? []).reduce<Record<string, Agent[]>>((groups, agent) => {
    const key = agent.department?.trim() || "미배정";
    (groups[key] ??= []).push(agent);
    return groups;
  }, {}));

  function submitAgenda(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    saveAgenda.mutate({
      title: form.get("title"),
      introduction: form.get("introduction"),
      backgroundKey: home.data?.backgroundKey ?? "office-warm",
      visibility: home.data?.visibility ?? "PUBLIC",
    });
  }

  return <AppShell kicker="MANAGEMENT" title="회사 관리">
    <div className="space-y-2">
      <Panel title="공간 인테리어" action={<Link href="/home/edit" className="rounded-pill bg-ink px-6 py-3 text-sm font-medium text-white transition active:scale-95 active:opacity-50">인테리어 편집</Link>}>
        <p className="text-sm leading-6 text-charcoal">벽·바닥 테마를 고르고 구성원의 자리를 직접 배치합니다. 공간은 회사 보드에 그대로 반영됩니다.</p>
      </Panel>

      <section id="departments" className="scroll-mt-24">
        <Panel title="부서 관리" action={<Link href="/assemble#hire" className="text-sm font-medium text-ink underline">직원 뽑기</Link>}>
          {departments.length === 0 && <p className="py-8 text-center text-sm text-mute">아직 구성원이 없어 부서가 없습니다.</p>}
          <div className="grid gap-2 sm:grid-cols-2">
            {departments.map(([department, members]) => <div key={department} className="border border-hairline p-5">
              <p className="text-xs font-medium uppercase tracking-[.2em] text-mute">{department}</p>
              <p className="mt-2 text-base font-medium text-ink">{members.length}명</p>
              <div className="mt-3 divide-y divide-hairline">
                {members.map((member) => <Link key={member.id} href={`/agents/${member.id}/edit`} className="flex items-center justify-between py-2.5">
                  <span className="text-sm font-medium text-ink">{member.name}</span>
                  <span className="text-sm text-mute">{member.role}</span>
                </Link>)}
              </div>
            </div>)}
          </div>
        </Panel>
      </section>

      <section id="agenda" className="scroll-mt-24">
        <Panel title="아젠다 관리">
          <p className="mb-5 text-sm leading-6 text-charcoal">우리 회사가 무엇을 하는 곳인지 정의합니다. 이 내용은 구성원이 일할 때의 기준이 됩니다.</p>
          <form key={home.data?.id} onSubmit={submitAgenda} className="space-y-4">
            <label className="block text-sm font-medium text-ink">회사 이름
              <input name="title" defaultValue={home.data?.title} required maxLength={60} className="mt-2 w-full border border-hairline px-4 py-3 outline-none focus:border-ink" />
            </label>
            <label className="block text-sm font-medium text-ink">회사가 하는 일
              <textarea name="introduction" defaultValue={home.data?.introduction} rows={4} placeholder="예: 리서치부터 초안, 검수, 발행까지 맡아 하는 콘텐츠 팀" className="mt-2 w-full border border-hairline px-4 py-3 outline-none focus:border-ink" />
            </label>
            <button disabled={saveAgenda.isPending} className="rounded-pill bg-ink px-8 py-4 text-sm font-medium text-white transition active:scale-95 active:opacity-50 disabled:opacity-40">{saveAgenda.isPending ? "저장 중…" : "아젠다 저장"}</button>
            {saveAgenda.isSuccess && <p className="text-sm text-leaf">저장했습니다.</p>}
            {saveAgenda.error && <p className="text-sm text-sale">{saveAgenda.error.message}</p>}
          </form>
        </Panel>
      </section>
    </div>
  </AppShell>;
}
