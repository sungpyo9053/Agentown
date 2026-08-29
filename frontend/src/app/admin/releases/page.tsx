"use client";

import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AppShell } from "@/components/AppShell";
import { api } from "@/lib/api";

type Release = {
  id: string;
  releaseKey: string;
  purpose: string;
  userSummary: string;
  status: string;
  riskLevel: string;
  currentSha?: string;
  candidateSha: string;
  includedTaskCount: number;
  hasMigration: boolean;
  stagingStatus: string;
  scheduledAt?: string;
  approvedAt?: string;
  actualDeployedSha?: string;
  createdAt: string;
  detail: { environmentContract?: { configured?: boolean; reason?: string } };
};

const labels: Record<string, string> = {
  CANDIDATE: "배포 후보",
  APPROVAL_REQUIRED: "승인 대기",
  SCHEDULED: "예약됨",
  DEPLOYING: "배포 중",
  VERIFYING: "운영 검증 중",
  RELEASED: "배포 성공",
  FAILED: "배포 실패",
  HELD: "보류",
  ROLLBACK_REQUIRED: "롤백 필요",
  ROLLED_BACK: "롤백 완료",
  DISCARDED: "거절·폐기",
  HUMAN_DECISION_REQUIRED: "사람 확인 필요",
};

const terminalStatuses = new Set(["RELEASED", "DISCARDED", "ROLLED_BACK"]);

export default function ReleasesPage() {
  const qc = useQueryClient();
  const me = useQuery({ queryKey: ["me"], queryFn: () => api<{ role: string; email: string }>("/auth/me") });
  const enabled = me.data?.role === "ADMIN" && me.data.email.toLowerCase() === "admin@reviewdr.kr";
  const releases = useQuery({ queryKey: ["admin-releases"], queryFn: () => api<Release[]>("/admin/releases"), enabled });
  const action = useMutation({
    mutationFn: ({ release, type }: { release: Release; type: "approve" | "discard" }) =>
      api(`/admin/releases/${release.id}/${type}`, {
        method: "POST",
        headers: type === "approve" ? { "Idempotency-Key": crypto.randomUUID() } : {},
        body: JSON.stringify(
          type === "approve"
            ? { commitSha: release.candidateSha, environment: "PRODUCTION", scheduledAt: null }
            : { reason: "관리자 목록 화면에서 배포 후보 거절" },
        ),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["admin-releases"] }),
  });

  if (me.isLoading) return <AppShell kicker="PLATFORM ADMIN" title="Releases"><p>권한을 확인하고 있습니다.</p></AppShell>;
  if (!enabled) return <AppShell kicker="ACCESS DENIED" title="Releases"><p className="bg-red-50 p-6 text-red-700">admin@reviewdr.kr 운영자 계정만 접근할 수 있습니다.</p></AppShell>;

  const all = releases.data ?? [];
  const environmentConfigured = all.some((release) => release.detail.environmentContract?.configured === true);
  const groups = Object.entries(all.reduce<Record<string, Release[]>>((acc, release) => {
    (acc[releaseDate(release.createdAt)] ??= []).push(release);
    return acc;
  }, {}));

  return <AppShell kicker="RELEASE CONTROL PLANE" title="Releases">
    {environmentConfigured ? <section className="border border-emerald-200 bg-emerald-50 p-5">
      <h2 className="font-black text-emerald-950">실제 배포 환경 구성 완료</h2>
      <p className="mt-1 text-sm text-emerald-900">격리 스테이징과 exact-SHA 검증, health·smoke 및 애플리케이션 rollback 계약이 연결되어 있습니다.</p>
      <ul className="mt-3 grid gap-1 text-xs text-emerald-800 md:grid-cols-2">
        <li>✓ 격리된 스테이징 환경</li><li>✓ 배포 SHA/revision 조회</li><li>✓ health·smoke 테스트</li>
        <li>✓ 애플리케이션 rollback</li><li>✓ DB migration 호환성 정책</li><li>✓ 최소 권한 배포 자격증명</li>
      </ul>
    </section> : <section className="border border-amber-200 bg-amber-50 p-5">
      <h2 className="font-black text-amber-950">실제 배포 환경 미구성</h2>
      <p className="mt-1 text-sm text-amber-900">스테이징·revision 확인·rollback 계약이 필요합니다. 현재는 배포 검토만 가능합니다.</p>
    </section>}

    <div className="mt-6 space-y-4">
      {groups.map(([date, items], index) => <details key={date} open={index === 0} className="border border-hairline bg-white">
        <summary className="cursor-pointer bg-stone-50 px-5 py-4 font-black">{date} · {items.length}건</summary>
        <div className="overflow-x-auto"><table className="w-full min-w-[1200px] text-left text-sm">
          <thead className="bg-cloud text-xs text-mute"><tr>{["상태", "변경 목적", "사용자 변화", "운영 / 후보 SHA", "위험·DB", "스테이징", "배포 방식", "결정"].map((label) => <th key={label} className="px-4 py-3">{label}</th>)}</tr></thead>
          <tbody className="divide-y divide-hairline">{items.map((release) => {
            const environmentReady = release.detail.environmentContract?.configured === true;
            const approvalReady = environmentReady && release.status === "APPROVAL_REQUIRED" && !release.approvedAt;
            const completedOrRunning = terminalStatuses.has(release.status) || ["DEPLOYING", "VERIFYING"].includes(release.status);
            const statusLabel = release.status === "APPROVAL_REQUIRED" && release.approvedAt ? "승인 완료" : (labels[release.status] ?? release.status);
            return <tr key={release.id}>
              <td className="px-4 py-4"><span className="rounded-pill bg-cloud px-3 py-1 text-xs font-semibold">{statusLabel}</span><small className="mt-2 block font-mono text-mute">{release.releaseKey}</small></td>
              <td className="px-4 py-4 font-semibold">{release.purpose}</td>
              <td className="max-w-xs px-4 py-4 text-mute">{release.userSummary}</td>
              <td className="px-4 py-4 font-mono text-xs">{short(release.currentSha)} → {short(release.candidateSha)}</td>
              <td className="px-4 py-4">{release.riskLevel} · {release.hasMigration ? "migration 있음" : "없음"}</td>
              <td className="px-4 py-4">{release.stagingStatus}</td>
              <td className="px-4 py-4">{release.scheduledAt ? seoul(release.scheduledAt) : release.approvedAt ? "즉시 배포" : "미승인"}</td>
              <td className="px-4 py-4"><div className="flex flex-wrap gap-2">
                <button title={approvalReady ? "검증된 SHA 즉시 배포 승인" : "현재 상태에서는 새 승인을 기록할 수 없습니다."} disabled={!approvalReady || action.isPending} onClick={() => action.mutate({ release, type: "approve" })} className="rounded-pill bg-ink px-3 py-2 text-xs text-white disabled:opacity-30">승인</button>
                <button disabled={action.isPending || completedOrRunning} onClick={() => action.mutate({ release, type: "discard" })} className="rounded-pill border border-red-200 px-3 py-2 text-xs text-red-700 disabled:opacity-30">거절</button>
                <Link href={`/admin/releases/${release.id}`} className="rounded-pill border border-hairline px-3 py-2 text-xs">상세보기</Link>
              </div>{!environmentReady && !completedOrRunning && <small className="mt-2 block max-w-[180px] text-amber-700">실제 환경·검증 계약 필요</small>}</td>
            </tr>;
          })}</tbody>
        </table></div>
      </details>)}
      {!releases.isLoading && !groups.length && <p className="border border-hairline bg-white p-8 text-center text-sm text-mute">아직 승인 가능한 Release 후보가 없습니다.</p>}
    </div>
  </AppShell>;
}

function short(value?: string) { return value ? value.slice(0, 8) : "미확인"; }
function seoul(value: string) { return new Intl.DateTimeFormat("ko-KR", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Seoul" }).format(new Date(value)) + " KST"; }
function releaseDate(value: string) { return new Intl.DateTimeFormat("ko-KR", { dateStyle: "full", timeZone: "Asia/Seoul" }).format(new Date(value)); }
