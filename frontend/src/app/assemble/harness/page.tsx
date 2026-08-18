"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { api } from "@/lib/api";
import { AppShell, Panel } from "@/components/AppShell";

type Harness = { id: string; name: string; description?: string; status: string; visibility: string };

export default function HarnessPage() {
  const harnesses = useQuery({ queryKey: ["harnesses"], queryFn: () => api<Harness[]>("/harnesses") });

  return <AppShell kicker="ASSEMBLE" title="하네스 구성">
    <Panel action={<Link href="/harnesses/new" className="rounded-pill bg-ink px-6 py-3 text-sm font-medium text-white transition active:scale-95 active:opacity-50">새 하네스</Link>}>
      <p className="mb-5 text-sm leading-6 text-charcoal">직원들을 순서대로 연결하고 사람이 승인할 지점을 정해, 하나의 목표를 끝까지 처리하는 실행 구조를 만듭니다.</p>
      <div className="divide-y divide-hairline">
        {harnesses.data?.map((item) => <Link key={item.id} href={`/harnesses/${item.id}/edit`} className="flex items-center justify-between gap-4 py-4">
          <span>
            <b className="block text-base font-medium text-ink">{item.name}</b>
            <small className="text-sm text-mute">{item.description || "설명 없음"}</small>
          </span>
          <span className="shrink-0 text-sm font-medium text-mute">{item.status}</span>
        </Link>)}
        {harnesses.data?.length === 0 && <p className="py-12 text-center text-sm text-mute">직원을 먼저 뽑은 뒤, 첫 하네스를 만들어 연결하세요.</p>}
      </div>
    </Panel>
  </AppShell>;
}
