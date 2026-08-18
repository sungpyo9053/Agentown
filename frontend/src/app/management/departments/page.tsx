"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { api } from "@/lib/api";
import { AppShell, Panel } from "@/components/AppShell";

type Agent = { id: string; name: string; role: string; department?: string };

export default function DepartmentsPage() {
  const agents = useQuery({ queryKey: ["agents"], queryFn: () => api<Agent[]>("/agents") });

  const departments = Object.entries((agents.data ?? []).reduce<Record<string, Agent[]>>((groups, agent) => {
    const key = agent.department?.trim() || "미배정";
    (groups[key] ??= []).push(agent);
    return groups;
  }, {}));

  return <AppShell kicker="MANAGEMENT" title="부서 관리">
    <Panel action={<Link href="/assemble/hire" className="rounded-pill bg-ink px-6 py-3 text-sm font-medium text-white transition active:scale-95 active:opacity-50">직원 뽑기</Link>}>
      <p className="mb-5 text-sm leading-6 text-charcoal">부서별로 어떤 직원이 무슨 역할을 맡고 있는지 확인합니다. 부서는 직원 정보에서 지정할 수 있어요.</p>
      {departments.length === 0 && <p className="py-12 text-center text-sm text-mute">아직 구성원이 없어 부서가 없습니다.</p>}
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
  </AppShell>;
}
