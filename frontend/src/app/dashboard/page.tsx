"use client";

import { FormEvent, useEffect, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { PixelOffice } from "@/components/PixelOffice";
import { placedAssets } from "@/components/OfficeRoom";
import { AppShell, Panel } from "@/components/AppShell";
import { blockFontClassName } from "@/lib/fonts";

type RoomItem = { agentId?: string; assetKey?: string; itemType: "AGENT" | "ASSET"; positionX: number; positionY: number; width: number; height: number; zIndex: number; rotation: number };
type Home = { id: string; handle: string; title: string; introduction?: string; backgroundKey?: string; visitCount: number; items: RoomItem[] };
type Agent = { id: string; name: string; role: string; department?: string; characterKey: string; modelProvider: string; modelName: string };
type Execution = { id: string; status: string; currentStepKey?: string; createdAt: string };
type Harness = { id: string; name: string; description?: string; status: string; visibility: string };
type Runner = { id: string; provider: "CODEX" | "CLAUDE"; deviceName: string; status: string; lastSeenAt?: string };

export default function DashboardPage() {
  const router = useRouter();
  const home = useQuery({ queryKey: ["home"], queryFn: () => api<Home>("/mini-homes/me") });
  const agents = useQuery({ queryKey: ["agents"], queryFn: () => api<Agent[]>("/agents") });
  const executions = useQuery({ queryKey: ["executions"], queryFn: () => api<Execution[]>("/executions") });
  const harnesses = useQuery({ queryKey: ["harnesses"], queryFn: () => api<Harness[]>("/harnesses") });
  const runners = useQuery({ queryKey: ["local-runners"], queryFn: () => api<Runner[]>("/local-runners"), refetchInterval: 5_000 });
  const startWork = useMutation({
    mutationFn: ({ harnessId, input }: { harnessId: string; input: Record<string, string> }) => api<{ id: string }>(`/harnesses/${harnessId}/executions`, {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey() },
      body: JSON.stringify({ input, stubMode: false, executionMode: "LOCAL_CLI" }),
    }),
    onSuccess: data => router.push(`/executions/${data.id}`),
  });

  if (home.isPending) return <AppShell kicker="COMPANY BOARD" title="불러오는 중"><div className="h-96 animate-pulse bg-white" /></AppShell>;
  if (home.error instanceof ApiError && home.error.status === 401) return <AppShell kicker="SESSION EXPIRED" title="로그인이 필요해요"><p className="text-mute">서버가 재시작되었거나 로그인 세션이 만료되었습니다.</p><Link href="/login" className="mt-6 inline-block rounded-pill bg-ink px-8 py-4 font-medium text-white">다시 로그인하기</Link></AppShell>;
  if (home.error || !home.data) return <AppShell kicker="LOAD FAILED" title="회사를 불러오지 못했습니다"><p className="border border-hairline bg-white p-6 text-sale">{home.error?.message ?? "회사 공간 응답이 없습니다."}</p><button onClick={() => home.refetch()} className="mt-4 rounded-pill bg-ink px-8 py-4 font-medium text-white">다시 시도</button></AppShell>;

  const running = executions.data?.filter(item => item.status === "RUNNING").length ?? 0;

  return <AppShell kicker="COMPANY BOARD" title={home.data.title}>
    <p className="-mt-6 mb-8 text-sm font-medium text-mute">@{home.data.handle} · 방문 {home.data.visitCount}</p>

    <div className="grid gap-2 sm:grid-cols-3">
      <Stat label="구성원" value={agents.data?.length ?? 0} />
      <Stat label="진행 중인 목표" value={harnesses.data?.filter(item => item.status === "ACTIVE").length ?? 0} />
      <Stat label="실행 중" value={running} />
    </div>

    <div className="mt-2 border border-hairline bg-white">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-hairline px-6 py-4">
        <div><b className="text-base font-medium text-ink">{home.data.title}</b><p className="text-sm text-mute">{home.data.introduction || "우리 팀이 일하는 디지털 오피스입니다."}</p></div>
        <Link href="/management/interior" className="rounded-pill bg-cloud px-6 py-3 text-sm font-medium text-ink transition active:scale-95 active:opacity-50">공간 인테리어</Link>
      </div>
      <PixelOffice agents={agents.data ?? []} items={placedAssets(home.data.items)} backgroundKey={home.data.backgroundKey} onAgentClick={id => router.push(`/agents/${id}/edit`)} />
      {!agents.isLoading && agents.data?.length === 0 && <div className="border-t border-hairline p-8 text-center"><p className="text-sm text-mute">아직 구성원이 없습니다.</p><Link href="/assemble/hire" className="mt-4 inline-block rounded-pill bg-ink px-8 py-4 text-sm font-medium text-white">직원 뽑기</Link></div>}
    </div>

    <WorkPanel harnesses={harnesses.data ?? []} runners={runners.data ?? []} pending={startWork.isPending} error={startWork.error} onSubmit={(harnessId, input) => startWork.mutate({ harnessId, input })} />

    <div className="mt-2 grid gap-2 lg:grid-cols-2">
      <Panel title="진행 중인 목표" action={<Link href="/assemble/harness" className="text-sm font-medium text-ink underline">전체 보기</Link>}>
        <div className="divide-y divide-hairline">{harnesses.data?.slice(0, 5).map(item => <Link key={item.id} href={`/harnesses/${item.id}/edit`} className="flex items-center justify-between py-4"><span className="text-base font-medium text-ink">{item.name}</span><span className="text-sm font-medium text-mute">{item.status}</span></Link>)}{harnesses.data?.length === 0 && <p className="py-8 text-center text-sm text-mute">아직 목표가 없습니다.</p>}</div>
      </Panel>
      <Panel title="최근 실행">
        <div className="divide-y divide-hairline">{executions.data?.slice(0, 5).map(item => <Link key={item.id} href={`/executions/${item.id}`} className="flex items-center justify-between py-4"><span className="text-base font-medium text-ink">{item.currentStepKey ?? "실행"}</span><span className="text-sm font-medium text-mute">{item.status}</span></Link>)}{executions.data?.length === 0 && <p className="py-8 text-center text-sm text-mute">아직 실행 기록이 없습니다.</p>}</div>
      </Panel>
    </div>
  </AppShell>;
}

function WorkPanel({ harnesses, runners, pending, error, onSubmit }: { harnesses: Harness[]; runners: Runner[]; pending: boolean; error: Error | null; onSubmit: (harnessId: string, input: Record<string, string>) => void }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 5_000);
    return () => window.clearInterval(timer);
  }, []);
  const published = harnesses.filter(item => item.status === "PUBLISHED");
  const active = runners.filter(runner => runner.status === "ACTIVE" && runner.lastSeenAt && now - new Date(runner.lastSeenAt).getTime() < 30_000);
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    onSubmit(String(form.get("harnessId")), { task: String(form.get("task") ?? ""), context: String(form.get("context") ?? ""), expectedOutput: String(form.get("expectedOutput") ?? "") });
  }
  return <section className="mt-2 border border-hairline bg-white p-6">
    <div className="flex flex-wrap items-start justify-between gap-3"><div><p className="text-xs font-semibold uppercase tracking-wide text-coral">Real employee work</p><h2 className="mt-1 text-2xl font-semibold">AI 직원에게 실제 업무 지시</h2><p className="mt-2 text-sm text-mute">발행된 팀을 선택하면 내 컴퓨터의 Codex 또는 Claude가 직원별 역할과 가이드에 따라 실제 결과를 만듭니다.</p></div><div className="border border-hairline px-4 py-3 text-sm"><b>Runner</b><p className={active.length ? "text-emerald-700" : "text-sale"}>{active.length ? `${active.map(item => item.deviceName).join(", ")} 연결됨` : "실행 중인 Runner 없음"}</p></div></div>
    {published.length && active.length ? <form onSubmit={submit} className="mt-6 grid gap-4 lg:grid-cols-2"><label className="text-sm font-medium">업무를 맡길 AI 팀<select name="harnessId" required className="mt-2 w-full border border-hairline bg-white p-3">{published.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><label className="text-sm font-medium">원하는 최종 결과<textarea name="expectedOutput" required rows={2} className="mt-2 w-full border border-hairline p-3" /></label><label className="text-sm font-medium lg:col-span-2">실제 업무 지시<textarea name="task" required rows={4} className="mt-2 w-full border border-hairline p-3" /></label><label className="text-sm font-medium lg:col-span-2">참고 정보와 제약사항<textarea name="context" rows={3} className="mt-2 w-full border border-hairline p-3" /></label><button disabled={pending} className="rounded-pill bg-ink p-4 font-medium text-white lg:col-span-2">{pending ? "직원들에게 업무 전달 중…" : "실제 AI 직원 업무 시작"}</button>{error && <p className="bg-red-50 p-4 text-sm text-sale lg:col-span-2">{error.message}</p>}</form> : <div className="mt-5 bg-cloud p-5 text-sm text-mute">{!published.length ? <p>먼저 AI 회사를 만들고 검증한 버전을 발행해 주세요. <Link href="/harnesses/new" className="font-medium text-ink underline">AI 회사 만들기</Link></p> : <p>실제 업무를 시작하려면 Runner를 먼저 실행해 주세요. <Link href="/settings/credentials" className="font-medium text-ink underline">AI 연결</Link></p>}</div>}
  </section>;
}

function Stat({ label, value }: { label: string; value: number }) {
  return <div className="border border-hairline bg-white p-6"><p className="text-sm font-medium text-mute">{label}</p><p className={`${blockFontClassName} mt-2 text-6xl text-ink`}>{value}</p></div>;
}

function idempotencyKey() {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, value => value.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
