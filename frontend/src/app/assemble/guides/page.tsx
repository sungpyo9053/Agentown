"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { api } from "@/lib/api";
import { AppShell, Panel } from "@/components/AppShell";

type Agent = { id: string; name: string; role: string; department?: string; modelProvider: string };

export default function GuidesPage() {
  const agents = useQuery({ queryKey: ["agents"], queryFn: () => api<Agent[]>("/agents") });

  return <AppShell kicker="ASSEMBLE" title="가이드 관리">
    <Panel action={<Link href="/assemble/hire" className="rounded-pill bg-cloud px-6 py-3 text-sm font-medium text-ink transition active:scale-95">직원 뽑기</Link>}>
      <p className="mb-5 text-sm leading-6 text-charcoal">직원마다 무엇을 어떤 순서로 하고, 무엇을 하면 안 되며, 언제 완료로 볼지를 정합니다. 질문에 답하면 agent.md · guide.md가 자동으로 만들어집니다.</p>
      <div className="divide-y divide-hairline">
        {agents.data?.map((agent) => <Link key={agent.id} href={`/agents/${agent.id}/edit`} className="flex items-center justify-between gap-4 py-4">
          <span>
            <b className="block text-base font-medium text-ink">{agent.name}</b>
            <small className="text-sm text-mute">{agent.department || "미배정"} · {agent.role}</small>
          </span>
          <span className="shrink-0 text-sm font-medium text-ink underline">가이드 편집</span>
        </Link>)}
        {agents.data?.length === 0 && <p className="py-12 text-center text-sm text-mute">직원을 먼저 뽑으면 가이드를 만들 수 있어요.</p>}
      </div>
    </Panel>
  </AppShell>;
}
