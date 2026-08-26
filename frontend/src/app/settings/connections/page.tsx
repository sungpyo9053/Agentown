"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AppShell } from "@/components/AppShell";
import { api } from "@/lib/api";

type Connection = { id: string; teamId: string; teamName: string; scopes: string[]; status: string; connectedAt: string };
type SlackStatus = { configured: boolean; connected: boolean; connections: Connection[]; eventRequestUrl: string };
type NotionConnection = { id: string; workspaceId: string; workspaceName: string; status: string; lastVerifiedAt?: string; connectedAt: string };
type NotionStatus = { configured: boolean; connected: boolean; connections: NotionConnection[] };
type NotionVerification = { connectionId: string; botName?: string; workspaceName: string; accessibleItems: { id: string; objectType: string; title: string; url?: string }[]; verifiedAt: string };

const upcoming = [
  { name: "GitHub", detail: "Issue·PR·커밋 워크플로우", state: "준비 예정" },
  { name: "Google Drive", detail: "문서 입력·결과 저장", state: "준비 예정" },
];

export default function ConnectionsPage() {
  const queryClient = useQueryClient();
  const slack = useQuery({ queryKey: ["slack-connection"], queryFn: () => api<SlackStatus>("/connectors/slack") });
  const notion = useQuery({ queryKey: ["notion-connection"], queryFn: () => api<NotionStatus>("/connectors/notion") });
  const connect = useMutation({
    mutationFn: () => api<{ authorizationUrl: string }>("/connectors/slack/oauth/start", { method: "POST", body: "{}" }),
    onSuccess: ({ authorizationUrl }) => { window.location.assign(authorizationUrl); },
  });
  const revoke = useMutation({
    mutationFn: (id: string) => api(`/connectors/slack/${id}`, { method: "DELETE" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["slack-connection"] }),
  });
  const connectNotion = useMutation({
    mutationFn: () => api<{ authorizationUrl: string }>("/connectors/notion/oauth/start", { method: "POST", body: "{}" }),
    onSuccess: ({ authorizationUrl }) => { window.location.assign(authorizationUrl); },
  });
  const revokeNotion = useMutation({
    mutationFn: (id: string) => api(`/connectors/notion/${id}`, { method: "DELETE" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["notion-connection"] }),
  });
  const verifyNotion = useMutation({
    mutationFn: (id: string) => api<NotionVerification>(`/connectors/notion/${id}/verify`, { method: "POST", body: JSON.stringify({ query: "" }) }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["notion-connection"] }),
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
      <article className="border border-hairline bg-white p-6">
        <div className="flex items-start justify-between gap-4"><div><p className="text-xs font-semibold tracking-[.14em] text-coral">READ · APPROVED WRITE</p><h2 className="mt-2 text-2xl font-medium">Notion</h2><p className="mt-2 text-sm text-mute">공유된 페이지 검색 · 미리보기 승인 후 새 페이지 발행</p></div><span className={`rounded-pill px-3 py-1 text-xs ${notion.data?.connected ? "bg-green-100 text-green-800" : "bg-cloud text-charcoal"}`}>{notion.data?.connected ? "연결됨" : notion.data?.configured ? "연결 가능" : "서버 설정 필요"}</span></div>
        {notion.data?.connections.map(item => <div key={item.id} className="mt-4 border border-hairline p-4"><div className="flex items-center"><div><b>{item.workspaceName}</b><p className="mt-1 text-xs text-mute">{item.status}{item.lastVerifiedAt ? ` · 읽기 검증 ${new Date(item.lastVerifiedAt).toLocaleString("ko-KR")}` : " · 읽기 검증 전"}</p></div><button onClick={() => revokeNotion.mutate(item.id)} className="ml-auto text-sm font-medium text-sale">연결 해제</button></div>{item.status === "ACTIVE" && <button onClick={() => verifyNotion.mutate(item.id)} disabled={verifyNotion.isPending} className="mt-3 rounded-pill border border-ink px-4 py-2 text-xs font-medium disabled:opacity-40">실제 읽기 검증</button>}</div>)}
        {verifyNotion.data && <div className="mt-4 bg-cloud p-4 text-xs leading-5"><b>읽기 검증 완료 · {verifyNotion.data.accessibleItems.length}개 확인</b>{verifyNotion.data.accessibleItems.slice(0, 5).map(item => <p key={item.id} className="mt-1 text-mute">{item.objectType} · {item.title}</p>)}</div>}
        <p className="mt-4 text-xs leading-5 text-mute">쓰기 작업은 대상 페이지와 결과 미리보기를 확인한 뒤에만 실행됩니다. 같은 승인 키는 한 번만 발행되며 실패·결과가 기록됩니다.</p>
        <button disabled={!notion.data?.configured || connectNotion.isPending} onClick={() => connectNotion.mutate()} className="mt-5 w-full rounded-pill bg-ink px-5 py-3 text-sm font-medium text-white disabled:opacity-40">{connectNotion.isPending ? "Notion으로 이동 중…" : "Notion 워크스페이스 연결"}</button>
        {(connectNotion.error || notion.error || verifyNotion.error) && <p role="alert" className="mt-3 text-sm text-sale">{(connectNotion.error || notion.error || verifyNotion.error)?.message}</p>}
        {!notion.data?.configured && <p className="mt-3 text-xs leading-5 text-amber-800">서버 운영자가 Notion Public Integration의 Client ID와 Client Secret을 등록하면 활성화됩니다.</p>}
      </article>
      <section className="space-y-3">{upcoming.map(item => <article key={item.name} className="flex items-center border border-hairline bg-white p-5"><div><h2 className="font-medium">{item.name}</h2><p className="mt-1 text-sm text-mute">{item.detail}</p></div><span className="ml-auto rounded-pill bg-cloud px-3 py-1 text-xs text-mute">{item.state}</span></article>)}</section>
    </div>
    <p className="mt-6 text-xs leading-5 text-mute">커넥터 자체 설치 비용과 각 서비스의 API·워크스페이스 요금은 별개입니다. Agentown은 실제 활성화 전에 필요한 권한과 비용 발생 가능성을 표시합니다.</p>
  </AppShell>;
}
