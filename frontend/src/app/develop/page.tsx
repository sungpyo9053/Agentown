"use client";
/* eslint-disable react-hooks/set-state-in-effect -- restore the server-owned session id once on mount */

import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Background, Controls, ReactFlow, type Edge, type Node } from "@xyflow/react";
import { Bot, Boxes, CheckCircle2, ChevronRight, CircleStop, FileCode2, GitBranch, History, Plus, Send, Sparkles, Users, Wrench } from "lucide-react";
import { AppShell } from "@/components/AppShell";
import { api } from "@/lib/api";

type Agent = { key: string; name: string; role: string; behaviorRules: string[]; forbiddenRules: string[]; evidenceRequirements: string[] };
type GraphNode = { id: string; nodeType: string; label: string; position: { x: number; y: number }; config: Record<string, unknown> };
type Graph = { nodes: GraphNode[]; edges: Array<{ id: string; source: string; target: string }> };
type Snapshot = {
  conversationId: string; workflowId: string; status: string;
  proposal?: { name: string; summary: string; capabilities: string[]; agentDesign?: { naturalLanguageSummary: string; assumptions: Array<{ key: string; value: string; reason: string }>; review: { passed: boolean; issues: Array<{ code: string; message: string }> } } };
  agentDefinitions: Agent[]; graph?: Graph; currentVersionId?: string; validation?: { valid: boolean; graphHash: string };
  messages: Array<{ id: string; role: string; content: string; createdAt: string }>;
  versions: Array<{ id: string; versionNo: number; changeSummary: string; approved: boolean; createdAt: string }>;
};
type Session = { conversationId: string; workflowId: string; title: string; status: string; currentVersionNo?: number; updatedAt: string };
type Job = { id: string; conversationId: string; status: "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED"; stage: string; elapsedSeconds: number; remainingSeconds: number; errorMessage?: string };

const storageKey = "agentown.agent-development.session.v1";
const starter = "어떤 AI 에이전트를 만들고 싶으신가요? 역할과 원하는 결과를 자연스럽게 설명해 주세요.";
const examples = ["업로드한 계약서의 위험 조항을 찾고 근거와 함께 설명하는 에이전트", "매주 회의록을 읽고 결정사항과 담당자별 할 일을 정리하는 에이전트", "고객 인터뷰를 분석해 반복되는 문제와 제품 기회를 찾는 에이전트"];
function key(prefix: string) { return `${prefix}-${crypto.randomUUID()}`; }

export default function AgentDevelopmentPage() {
  const queryClient = useQueryClient();
  const [sessionId, setSessionId] = useState<string>();
  const [message, setMessage] = useState("");
  const [jobId, setJobId] = useState<string>();
  const [panel, setPanel] = useState<"team" | "graph" | "versions">("team");
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => setSessionId(window.localStorage.getItem(storageKey) ?? undefined), []);
  const sessions = useQuery({ queryKey: ["agent-development-sessions"], queryFn: () => api<Session[]>("/agent-development/sessions") });
  const snapshotQuery = useQuery({ queryKey: ["agent-development", sessionId], queryFn: () => api<Snapshot>(`/agent-development/sessions/${sessionId}`), enabled: Boolean(sessionId) });
  const snapshot = snapshotQuery.data;
  const job = useQuery({ queryKey: ["agent-development-job", jobId], queryFn: () => api<Job>(`/agent-development/jobs/${jobId}`), enabled: Boolean(jobId), refetchInterval: query => ["SUCCEEDED", "FAILED", "CANCELLED"].includes(query.state.data?.status ?? "") ? false : 1200 });

  function store(next: Snapshot) {
    setSessionId(next.conversationId);
    window.localStorage.setItem(storageKey, next.conversationId);
    queryClient.setQueryData(["agent-development", next.conversationId], next);
    queryClient.invalidateQueries({ queryKey: ["agent-development-sessions"] });
  }
  useEffect(() => {
    if (job.data?.status !== "SUCCEEDED") return;
    api<Snapshot>(`/agent-development/sessions/${job.data.conversationId}`).then(next => { store(next); setJobId(undefined); setPanel("team"); });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [job.data?.status]);
  useEffect(() => { scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: "smooth" }); }, [snapshot?.messages.length, job.data?.status]);

  const create = useMutation({ mutationFn: () => api<Snapshot>("/agent-development/sessions", { method: "POST", headers: { "Idempotency-Key": key("agent-session") }, body: "{}" }), onSuccess: store });
  const send = useMutation({
    mutationFn: async (content: string) => {
      let current = snapshot;
      if (!current) current = await api<Snapshot>("/agent-development/sessions", { method: "POST", headers: { "Idempotency-Key": key("agent-session") }, body: "{}" });
      return api<Job>(`/agent-development/sessions/${current.conversationId}/messages`, { method: "POST", headers: { "Idempotency-Key": key("agent-message") }, body: JSON.stringify({ content }) });
    },
    onSuccess: next => { setJobId(next.id); setMessage(""); },
  });
  const patch = useMutation({ mutationFn: (content: string) => api<Snapshot>(`/agent-development/sessions/${snapshot!.conversationId}/patches`, { method: "POST", headers: { "Idempotency-Key": key("agent-patch") }, body: JSON.stringify({ instruction: content, baseVersionId: snapshot!.currentVersionId, expectedGraphHash: snapshot!.validation!.graphHash }) }), onSuccess: next => { store(next); setMessage(""); setPanel("team"); } });
  const decideDesign = useMutation({ mutationFn: (approve: boolean) => api<Snapshot>(`/agent-development/sessions/${snapshot!.conversationId}/design-decision`, { method: "POST", headers: { "Idempotency-Key": key("agent-design") }, body: JSON.stringify({ approve }) }), onSuccess: next => { store(next); if (next.graph) setPanel("graph"); } });
  const cancel = useMutation({ mutationFn: () => api<Job>(`/agent-development/jobs/${jobId}/cancel`, { method: "POST", headers: { "Idempotency-Key": key("agent-cancel") }, body: "{}" }), onSuccess: next => queryClient.setQueryData(["agent-development-job", next.id], next) });
  function submit(event: FormEvent) { event.preventDefault(); if (!message.trim() || pending) return; if (snapshot?.graph && snapshot.currentVersionId && snapshot.validation) patch.mutate(message.trim()); else send.mutate(message.trim()); }
  function newSession() { window.localStorage.removeItem(storageKey); setSessionId(undefined); setMessage(""); setJobId(undefined); create.mutate(); }

  const pending = create.isPending || send.isPending || patch.isPending || decideDesign.isPending || Boolean(jobId && !["SUCCEEDED", "FAILED", "CANCELLED"].includes(job.data?.status ?? ""));
  const error = create.error || send.error || patch.error || decideDesign.error || snapshotQuery.error || (job.data?.status === "FAILED" ? new Error(job.data.errorMessage ?? "에이전트 생성에 실패했습니다.") : null);
  const flowNodes = useMemo<Node[]>(() => snapshot?.graph?.nodes.map(node => ({ id: node.id, position: node.position, data: { label: node.label }, style: { width: 180, borderRadius: 6, border: "1px solid #d4d4d0", fontSize: 12, padding: 12, background: "white" } })) ?? [], [snapshot?.graph]);
  const flowEdges = useMemo<Edge[]>(() => snapshot?.graph?.edges.map(edge => ({ ...edge, animated: true })) ?? [], [snapshot?.graph]);

  return <AppShell kicker="DEVELOP" title="에이전트 개발" workspace>
    <div className="grid h-full min-h-0 bg-[#f5f5f2] lg:grid-cols-[250px_minmax(420px,1fr)_360px]">
      <aside className="hidden min-h-0 border-r border-hairline bg-white lg:flex lg:flex-col">
        <div className="flex h-14 items-center justify-between border-b border-hairline px-4"><div className="flex items-center gap-2 text-sm font-semibold"><Sparkles className="h-4 w-4 text-coral" />에이전트 개발</div><button onClick={newSession} title="새 에이전트" aria-label="새 에이전트" className="flex h-8 w-8 items-center justify-center rounded-md hover:bg-cloud"><Plus className="h-4 w-4" /></button></div>
        <div className="min-h-0 flex-1 overflow-auto p-2">{sessions.data?.map(item => <button key={item.conversationId} onClick={() => { setSessionId(item.conversationId); window.localStorage.setItem(storageKey, item.conversationId); setJobId(undefined); }} className={`mb-1 w-full rounded-md px-3 py-3 text-left ${sessionId === item.conversationId ? "bg-ink text-white" : "hover:bg-cloud"}`}><p className="truncate text-sm font-medium">{item.title}</p><p className={`mt-1 truncate text-[11px] ${sessionId === item.conversationId ? "text-stone-300" : "text-mute"}`}>{item.currentVersionNo ? `Version ${item.currentVersionNo}` : "설계 전"} · {koStatus(item.status)}</p></button>)}</div>
        <div className="border-t border-hairline p-3 text-[11px] leading-5 text-mute">대화, 에이전트 구성, 버전 기록이 프로젝트별로 보존됩니다.</div>
      </aside>

      <main className="flex min-h-0 min-w-0 flex-col bg-white">
        <header className="flex h-14 shrink-0 items-center justify-between border-b border-hairline px-4"><div className="min-w-0"><p className="truncate text-sm font-semibold">{snapshot?.proposal?.name ?? sessions.data?.find(item => item.conversationId === sessionId)?.title ?? "새 AI 에이전트"}</p><p className="text-[11px] text-mute">{koStatus(snapshot?.status ?? "DRAFT")}</p></div><button onClick={newSession} className="flex items-center gap-2 rounded-md border border-hairline px-3 py-2 text-xs lg:hidden"><Plus className="h-3.5 w-3.5" />새로 만들기</button></header>
        <div ref={scrollRef} className="min-h-0 flex-1 overflow-auto px-4 py-6 md:px-8">
          <div className="mx-auto max-w-3xl space-y-5">
            {!snapshot?.messages.length && <div className="py-10"><div className="mx-auto flex h-12 w-12 items-center justify-center rounded-md bg-ink text-white"><Bot className="h-6 w-6" /></div><h1 className="mt-5 text-center text-2xl font-semibold">만들고 싶은 에이전트를 설명하세요</h1><p className="mx-auto mt-2 max-w-lg text-center text-sm leading-6 text-mute">{starter}</p><div className="mt-8 grid gap-2">{examples.map(example => <button key={example} onClick={() => setMessage(example)} className="flex items-center justify-between rounded-md border border-hairline px-4 py-3 text-left text-sm hover:border-charcoal hover:bg-cloud"><span>{example}</span><ChevronRight className="h-4 w-4 shrink-0 text-mute" /></button>)}</div></div>}
            {snapshot?.messages.map(item => <article key={item.id} className={`flex gap-3 ${item.role === "USER" ? "justify-end" : "justify-start"}`}>{item.role !== "USER" && <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-ink text-white"><Bot className="h-4 w-4" /></span>}<div className={`max-w-[82%] rounded-md px-4 py-3 text-sm leading-6 ${item.role === "USER" ? "bg-[#e9e9e4] text-ink" : "border border-hairline bg-white"}`}>{item.content}</div></article>)}
            {pending && <article className="flex gap-3"><span className="flex h-8 w-8 items-center justify-center rounded-md bg-ink text-white"><Bot className="h-4 w-4" /></span><div className="min-w-64 rounded-md border border-hairline p-4"><div className="flex items-center justify-between gap-4"><p className="text-sm font-medium">에이전트를 구성하고 있습니다</p><span className="text-[11px] text-mute">{job.data ? `${stageLabel(job.data.stage)} · ${job.data.elapsedSeconds}초` : "요청 준비"}</span></div><div className="mt-3 h-1.5 overflow-hidden rounded-full bg-cloud"><div className="h-full w-2/3 animate-pulse rounded-full bg-coral" /></div><button onClick={() => cancel.mutate()} disabled={!jobId} className="mt-3 flex items-center gap-1 text-xs text-mute hover:text-sale"><CircleStop className="h-3.5 w-3.5" />중지</button></div></article>}
            {error && <div className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">{error.message}</div>}
          </div>
        </div>
        <form onSubmit={submit} className="shrink-0 border-t border-hairline bg-white p-4"><div className="mx-auto flex max-w-3xl items-end gap-2 rounded-md border border-hairline bg-white p-2 focus-within:border-charcoal"><textarea aria-label="에이전트 개발 요청" value={message} onChange={event => setMessage(event.target.value)} rows={2} maxLength={4000} placeholder="에이전트의 역할, 지식, 도구, 결과를 자연어로 설명하세요" className="min-h-12 flex-1 resize-none border-0 px-2 py-2 text-sm leading-6 outline-none" /><button disabled={pending || !message.trim()} title="보내기" aria-label="보내기" className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md bg-ink text-white disabled:opacity-35"><Send className="h-4 w-4" /></button></div></form>
      </main>

      <aside className="hidden min-h-0 border-l border-hairline bg-[#fafaf8] lg:flex lg:flex-col">
        <div className="grid h-14 shrink-0 grid-cols-3 border-b border-hairline"><InspectorTab active={panel === "team"} onClick={() => setPanel("team")} icon={Users} label="팀" /><InspectorTab active={panel === "graph"} onClick={() => setPanel("graph")} icon={GitBranch} label="구조" /><InspectorTab active={panel === "versions"} onClick={() => setPanel("versions")} icon={History} label="버전" /></div>
        <div className="min-h-0 flex-1 overflow-auto p-4">
          {panel === "team" && <TeamPanel snapshot={snapshot} pending={pending} decide={approve => decideDesign.mutate(approve)} />}
          {panel === "graph" && <div className="h-full min-h-[420px] border border-hairline bg-white">{snapshot?.graph ? <ReactFlow nodes={flowNodes} edges={flowEdges} fitView proOptions={{ hideAttribution: true }}><Background gap={18} size={1} /><Controls showInteractive={false} /></ReactFlow> : <Empty icon={Boxes} text="대화로 에이전트를 만들면 협업 구조가 표시됩니다." />}</div>}
          {panel === "versions" && <div className="space-y-2">{snapshot?.versions.length ? snapshot.versions.map(version => <article key={version.id} className="rounded-md border border-hairline bg-white p-3"><div className="flex items-center justify-between"><p className="text-sm font-medium">Version {version.versionNo}</p>{version.approved && <CheckCircle2 className="h-4 w-4 text-emerald-600" />}</div><p className="mt-2 text-xs leading-5 text-mute">{version.changeSummary}</p></article>) : <Empty icon={History} text="완성된 설계 버전이 여기에 쌓입니다." />}</div>}
        </div>
      </aside>
    </div>
  </AppShell>;
}

function TeamPanel({ snapshot, pending, decide }: { snapshot?: Snapshot; pending: boolean; decide: (approve: boolean) => void }) {
  if (!snapshot?.agentDefinitions.length) return <Empty icon={Users} text="역할에 맞는 에이전트 팀이 여기에 구성됩니다." />;
  return <div className="space-y-3"><div className="rounded-md border border-hairline bg-white p-4"><p className="text-xs font-semibold text-coral">AGENT PACKAGE</p><p className="mt-2 text-sm leading-6">{snapshot.proposal?.summary}</p><div className="mt-3 flex flex-wrap gap-1">{snapshot.proposal?.capabilities.map(item => <span key={item} className="rounded-md bg-cloud px-2 py-1 text-[11px]">{item}</span>)}</div></div>{snapshot.agentDefinitions.map(agent => <article key={agent.key} className="rounded-md border border-hairline bg-white p-4"><div className="flex items-center gap-2"><span className="flex h-8 w-8 items-center justify-center rounded-md bg-ink text-white"><Bot className="h-4 w-4" /></span><div><p className="text-sm font-semibold">{agent.name}</p><p className="text-[11px] text-mute">{agent.key}</p></div></div><p className="mt-3 text-xs leading-5 text-charcoal">{agent.role}</p>{agent.behaviorRules.length > 0 && <div className="mt-3 border-t border-hairline pt-3"><p className="flex items-center gap-1 text-[11px] font-semibold"><Wrench className="h-3 w-3" />행동 규칙</p>{agent.behaviorRules.slice(0, 3).map(rule => <p key={rule} className="mt-1 text-[11px] leading-5 text-mute">· {rule}</p>)}</div>}</article>)}{snapshot.status === "WAITING_DESIGN_APPROVAL" && <div className="grid grid-cols-2 gap-2 pt-1"><button disabled={pending} onClick={() => decide(false)} className="rounded-md border border-hairline px-3 py-2.5 text-xs disabled:opacity-40">수정 요청</button><button disabled={pending} onClick={() => decide(true)} className="rounded-md bg-ink px-3 py-2.5 text-xs text-white disabled:opacity-40">설계 승인</button></div>}{snapshot.currentVersionId && <a href={`/api/builder/workflows/${snapshot.workflowId}/package`} className="flex items-center justify-center gap-2 rounded-md border border-hairline bg-white px-3 py-2.5 text-xs"><FileCode2 className="h-3.5 w-3.5" />에이전트 패키지</a>}</div>;
}
function InspectorTab({ active, onClick, icon: Icon, label }: { active: boolean; onClick: () => void; icon: typeof Users; label: string }) { return <button onClick={onClick} className={`flex items-center justify-center gap-1.5 border-b-2 text-xs ${active ? "border-ink bg-white text-ink" : "border-transparent text-mute hover:text-ink"}`}><Icon className="h-3.5 w-3.5" />{label}</button>; }
function Empty({ icon: Icon, text }: { icon: typeof Users; text: string }) { return <div className="flex h-full min-h-52 flex-col items-center justify-center px-6 text-center text-xs leading-5 text-mute"><Icon className="mb-3 h-7 w-7" />{text}</div>; }
function koStatus(status: string) { return ({ DRAFT: "새 에이전트", NEEDS_CLARIFICATION: "추가 대화 필요", WAITING_DESIGN_APPROVAL: "설계 검토", READY_TO_SIMULATE: "테스트 준비", ACTIVE: "사용 중", STOPPED: "중지됨" } as Record<string, string>)[status] ?? status; }
function stageLabel(stage: string) { return ({ REQUEST_ACCEPTED: "요청 접수", CODEX_ANALYZING: "역할과 도구 설계", STRUCTURE_VALIDATING: "구조 검증", DESIGN_SAVING: "버전 저장" } as Record<string, string>)[stage] ?? stage; }
