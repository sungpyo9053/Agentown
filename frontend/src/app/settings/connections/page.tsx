"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AppShell } from "@/components/AppShell";
import { api } from "@/lib/api";

type Connection = { id: string; teamId: string; teamName: string; scopes: string[]; status: string; connectedAt: string };
type SlackStatus = { configured: boolean; connected: boolean; connections: Connection[]; eventRequestUrl: string };

const upcoming = [
  { name: "Notion", detail: "FAQ·문서 검색", state: "다음 단계" },
  { name: "GitHub", detail: "Issue·PR·커밋 워크플로우", state: "준비 예정" },
  { name: "Google Drive", detail: "문서 입력·결과 저장", state: "준비 예정" },
];

export default function ConnectionsPage() {
  const queryClient = useQueryClient();
  const slack = useQuery({ queryKey: ["slack-connection"], queryFn: () => api<SlackStatus>("/connectors/slack") });
  const connect = useMutation({
    mutationFn: () => api<{ authorizationUrl: string }>("/connectors/slack/oauth/start", { method: "POST", body: "{}" }),
    onSuccess: ({ authorizationUrl }) => { window.location.assign(authorizationUrl); },
  });
  const revoke = useMutation({
    mutationFn: (id: string) => api(`/connectors/slack/${id}`, { method: "DELETE" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["slack-connection"] }),
  });

  return <AppShell kicker="SETTING · CONNECTORS" title="업무 연결">
    <div className="mb-6 border border-hairline bg-white p-6">
      <h2 className="text-xl font-medium">서버 공용 커넥터 카탈로그</h2>
      <p className="mt-2 text-sm leading-6 text-mute">Agentown이 안전한 표준 노드를 한 번 설치하고, 사용자는 자신의 계정만 OAuth로 연결합니다. 토큰은 암호화되며 Workflow에는 connection_id만 들어갑니다.</p>
    </div>
    <div className="grid gap-5 lg:grid-cols-2">
      <article className="border border-hairline bg-white p-6">
        <div className="flex items-start justify-between gap-4"><div><p className="text-xs font-semibold tracking-[.14em] text-coral">AVAILABLE FIRST</p><h2 className="mt-2 text-2xl font-medium">Slack</h2><p className="mt-2 text-sm text-mute">채널 메시지 수신 · 스레드 답변 권한</p></div><span className={`rounded-pill px-3 py-1 text-xs ${slack.data?.connected ? "bg-green-100 text-green-800" : "bg-cloud text-charcoal"}`}>{slack.data?.connected ? "연결됨" : slack.data?.configured ? "연결 가능" : "서버 설정 필요"}</span></div>
        {slack.data?.connections.map(item => <div key={item.id} className="mt-4 flex items-center border border-hairline p-4"><div><b>{item.teamName}</b><p className="mt-1 text-xs text-mute">{item.status} · {item.scopes.join(", ")}</p></div><button onClick={() => revoke.mutate(item.id)} className="ml-auto text-sm font-medium text-sale">연결 해제</button></div>)}
        <button disabled={!slack.data?.configured || connect.isPending} onClick={() => connect.mutate()} className="mt-5 w-full rounded-pill bg-ink px-5 py-3 text-sm font-medium text-white disabled:opacity-40">{connect.isPending ? "Slack으로 이동 중…" : "Slack 워크스페이스 연결"}</button>
        {(connect.error || slack.error) && <p role="alert" className="mt-3 text-sm text-sale">{(connect.error || slack.error)?.message}</p>}
        {!slack.data?.configured && <p className="mt-3 text-xs leading-5 text-amber-800">서버 운영자가 Slack App의 Client ID, Client Secret, Signing Secret을 등록하면 활성화됩니다.</p>}
      </article>
      <section className="space-y-3">{upcoming.map(item => <article key={item.name} className="flex items-center border border-hairline bg-white p-5"><div><h2 className="font-medium">{item.name}</h2><p className="mt-1 text-sm text-mute">{item.detail}</p></div><span className="ml-auto rounded-pill bg-cloud px-3 py-1 text-xs text-mute">{item.state}</span></article>)}</section>
    </div>
    <p className="mt-6 text-xs leading-5 text-mute">커넥터 자체 설치 비용과 각 서비스의 API·워크스페이스 요금은 별개입니다. Agentown은 실제 활성화 전에 필요한 권한과 비용 발생 가능성을 표시합니다.</p>
  </AppShell>;
}
