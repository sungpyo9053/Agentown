"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { OfficeRoom, placedAssets } from "@/components/OfficeRoom";
import { AppShell, Panel } from "@/components/AppShell";
import { blockFontClassName } from "@/lib/fonts";

type RoomItem = { agentId?: string; assetKey?: string; itemType: "AGENT" | "ASSET"; positionX: number; positionY: number; width: number; height: number; zIndex: number; rotation: number };
type Home = { id: string; handle: string; title: string; introduction?: string; backgroundKey?: string; visitCount: number; items: RoomItem[] };
type Agent = { id: string; name: string; role: string; department?: string; characterKey: string; modelProvider: string; modelName: string };
type Execution = { id: string; status: string; currentStepKey?: string; createdAt: string };
type Harness = { id: string; name: string; description?: string; status: string; visibility: string };

export default function DashboardPage() {
  const router = useRouter();
  const home = useQuery({ queryKey: ["home"], queryFn: () => api<Home>("/mini-homes/me") });
  const agents = useQuery({ queryKey: ["agents"], queryFn: () => api<Agent[]>("/agents") });
  const executions = useQuery({ queryKey: ["executions"], queryFn: () => api<Execution[]>("/executions") });
  const harnesses = useQuery({ queryKey: ["harnesses"], queryFn: () => api<Harness[]>("/harnesses") });

  if (home.isPending) return <AppShell kicker="COMPANY BOARD" title="불러오는 중"><div className="h-96 animate-pulse bg-white" /></AppShell>;
  if (home.error instanceof ApiError && home.error.status === 401) return <AppShell kicker="SESSION EXPIRED" title="로그인이 필요해요"><p className="text-mute">서버가 재시작되었거나 로그인 세션이 만료되었습니다.</p><Link href="/login" className="mt-6 inline-block rounded-pill bg-ink px-8 py-4 font-medium text-white">다시 로그인하기</Link></AppShell>;
  if (home.error || !home.data) return <AppShell kicker="LOAD FAILED" title="회사를 불러오지 못했습니다"><p className="border border-hairline bg-white p-6 text-sale">{home.error?.message ?? "회사 공간 응답이 없습니다."}</p><button onClick={() => home.refetch()} className="mt-4 rounded-pill bg-ink px-8 py-4 font-medium text-white">다시 시도</button></AppShell>;

  const running = executions.data?.filter((item) => item.status === "RUNNING").length ?? 0;

  return <AppShell kicker="COMPANY BOARD" title={home.data.title}>
    <p className="-mt-6 mb-8 text-sm font-medium text-mute">@{home.data.handle} · 방문 {home.data.visitCount}</p>

    <div className="grid gap-2 sm:grid-cols-3">
      <Stat label="구성원" value={agents.data?.length ?? 0} />
      <Stat label="진행 중인 목표" value={harnesses.data?.filter((item) => item.status === "ACTIVE").length ?? 0} />
      <Stat label="실행 중" value={running} />
    </div>

    {/* 회사 공간 — 업무가 실제로 이뤄지는 인터페이스 */}
    <div className="mt-2 border border-hairline bg-white">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-hairline px-6 py-4">
        <div>
          <b className="text-base font-medium text-ink">{home.data.title}</b>
          <p className="text-sm text-mute">{home.data.introduction || "우리 팀이 일하는 디지털 오피스입니다."}</p>
        </div>
        <Link href="/management/interior" className="rounded-pill bg-cloud px-6 py-3 text-sm font-medium text-ink transition active:scale-95 active:opacity-50">공간 인테리어</Link>
      </div>
      <OfficeRoom title={home.data.title} agents={agents.data ?? []} items={home.data.items ?? []} assets={placedAssets(home.data.items)} backgroundKey={home.data.backgroundKey} onAgentClick={(id) => router.push(`/agents/${id}/edit`)} />
      {!agents.isLoading && agents.data?.length === 0 && <div className="border-t border-hairline p-8 text-center">
        <p className="text-sm text-mute">아직 구성원이 없습니다.</p>
        <Link href="/assemble/hire" className="mt-4 inline-block rounded-pill bg-ink px-8 py-4 text-sm font-medium text-white transition active:scale-95 active:opacity-50">직원 뽑기</Link>
      </div>}
    </div>

    <div className="mt-2 grid gap-2 lg:grid-cols-2">
      <Panel title="진행 중인 목표" action={<Link href="/assemble/harness" className="text-sm font-medium text-ink underline">전체 보기</Link>}>
        <div className="divide-y divide-hairline">
          {harnesses.data?.slice(0, 5).map((item) => <Link key={item.id} href={`/harnesses/${item.id}/edit`} className="flex items-center justify-between py-4">
            <span className="text-base font-medium text-ink">{item.name}</span>
            <span className="text-sm font-medium text-mute">{item.status}</span>
          </Link>)}
          {harnesses.data?.length === 0 && <p className="py-8 text-center text-sm text-mute">아직 목표가 없습니다.</p>}
        </div>
      </Panel>

      <Panel title="최근 실행">
        <div className="divide-y divide-hairline">
          {executions.data?.slice(0, 5).map((item) => <Link key={item.id} href={`/executions/${item.id}`} className="flex items-center justify-between py-4">
            <span className="text-base font-medium text-ink">{item.currentStepKey ?? "실행"}</span>
            <span className="text-sm font-medium text-mute">{item.status}</span>
          </Link>)}
          {executions.data?.length === 0 && <p className="py-8 text-center text-sm text-mute">아직 실행 기록이 없습니다.</p>}
        </div>
      </Panel>
    </div>
  </AppShell>;
}

function Stat({ label, value }: { label: string; value: number }) {
  return <div className="border border-hairline bg-white p-6">
    <p className="text-sm font-medium text-mute">{label}</p>
    <p className={`${blockFontClassName} mt-2 text-6xl text-ink`}>{value}</p>
  </div>;
}
