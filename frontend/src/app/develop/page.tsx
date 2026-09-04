"use client";
/* eslint-disable react-hooks/set-state-in-effect -- restore the server-owned session id once on mount */

import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Background, Controls, ReactFlow, type Edge, type Node } from "@xyflow/react";
import { Bot, Boxes, CheckCircle2, ChevronRight, CircleStop, Database, FileCode2, GitBranch, History, PanelRight, Play, Plus, RotateCcw, Save, Send, Sparkles, TestTube2, Users, Wrench, X } from "lucide-react";
import { AppShell } from "@/components/AppShell";
import { api } from "@/lib/api";

type Agent = { key: string; name: string; role: string; behaviorRules: string[]; forbiddenRules: string[]; evidenceRequirements: string[]; toolKeys: string[]; skillKeys: string[]; memoryScope: string };
type Resource = { resourceKind: "TOOL" | "SKILL" | "CONNECTOR" | "MEMORY"; resourceKey: string; label: string; availability: string; reason: string; requiresUserAction: boolean };
type GraphNode = { id: string; nodeType: string; label: string; position: { x: number; y: number }; config: Record<string, unknown> };
type Graph = { nodes: GraphNode[]; edges: Array<{ id: string; source: string; target: string }> };
type Snapshot = {
  conversationId: string; workflowId: string; status: string;
  proposal?: { name: string; summary: string; capabilities: string[]; resourcePlan?: { bindings: Resource[]; uncoveredCapabilities: string[]; simulationReady: boolean; productionReady: boolean }; agentDesign?: { naturalLanguageSummary: string; assumptions: Array<{ key: string; value: string; reason: string }>; simulationScenarios: Array<{ name: string; input: Record<string, unknown>; expectedStages: string[] }>; review: { passed: boolean; issues: Array<{ code: string; message: string }> } } };
  agentDefinitions: Agent[]; graph?: Graph; currentVersionId?: string; validation?: { valid: boolean; graphHash: string };
  messages: Array<{ id: string; role: string; content: string; createdAt: string }>;
  versions: Array<{ id: string; versionNo: number; graphHash: string; changeSummary: string; approved: boolean; createdAt: string }>;
};
type Session = { conversationId: string; workflowId: string; title: string; status: string; currentVersionNo?: number; updatedAt: string };
type Job = { id: string; conversationId: string; status: "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED"; stage: string; elapsedSeconds: number; remainingSeconds: number; errorMessage?: string };
type Run = { id: string; status: string; currentNodeId?: string; output?: Record<string, unknown>; requirementMatched?: boolean; pendingApprovalId?: string; failureCode?: string; failureMessage?: string; steps: Array<{ nodeId: string; nodeType: string; sequenceNo: number; status: string; output?: Record<string, unknown>; errorMessage?: string }> };

const storageKey = "agentown.agent-development.session.v1";
const starter = "어떤 AI 에이전트를 만들고 싶으신가요? 역할과 원하는 결과를 자연스럽게 설명해 주세요.";
const examples = ["업로드한 계약서의 위험 조항을 찾고 근거와 함께 설명하는 에이전트", "매주 회의록을 읽고 결정사항과 담당자별 할 일을 정리하는 에이전트", "고객 인터뷰를 분석해 반복되는 문제와 제품 기회를 찾는 에이전트"];
function key(prefix: string) { return `${prefix}-${crypto.randomUUID()}`; }
function defaultTestInput(snapshot?: Snapshot): Record<string, unknown> {
  const nodeTypes = new Set(snapshot?.graph?.nodes.map(node => node.nodeType) ?? []);
  if (nodeTypes.has("data.csv.compare")) return { csvA: "id,name\n1,old\n2,remove\n", csvB: "id,name\n1,new\n3,add\n" };
  if (nodeTypes.has("knowledge.search.mock")) return { customerInquiry: "사내 복지포인트는 언제 지급되나요?", mockSearchResults: [] };
  const scenario = snapshot?.proposal?.agentDesign?.simulationScenarios?.[0]?.input;
  if (scenario && Object.keys(scenario).length > 0) return scenario;
  return { text: "검증할 샘플 입력" };
}
function parseTestInput(value: string, snapshot?: Snapshot): Record<string, unknown> {
  if (!value.trim()) return defaultTestInput(snapshot);
  try {
    const parsed: unknown = JSON.parse(value);
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) return parsed as Record<string, unknown>;
  } catch { /* plain text is a valid conversational input */ }
  return { text: value.trim() };
}

export default function AgentDevelopmentPage() {
  const queryClient = useQueryClient();
  const [sessionId, setSessionId] = useState<string>();
  const [message, setMessage] = useState("");
  const [jobId, setJobId] = useState<string>();
  const [panel, setPanel] = useState<"team" | "resources" | "graph" | "versions" | "output">("team");
  const [mobileInspector, setMobileInspector] = useState(false);
  const [run, setRun] = useState<Run>();
  const [testInput, setTestInput] = useState("");
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
    api<Snapshot>(`/agent-development/sessions/${job.data.conversationId}`).then(next => { store(next); setJobId(undefined); setMessage(""); setPanel("team"); });
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
    onSuccess: next => { setJobId(next.id); },
  });
  const patch = useMutation({
    mutationFn: async (content: string) => {
      const latest = await api<Snapshot>(`/agent-development/sessions/${snapshot!.conversationId}`);
      if (!latest.currentVersionId || !latest.validation) throw new Error("최신 캔버스를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
      const currentVersion = latest.versions.find(version => version.id === latest.currentVersionId);
      if (!currentVersion) throw new Error("최신 버전 기록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
      return api<Snapshot>(`/agent-development/sessions/${latest.conversationId}/patches`, {
        method: "POST",
        headers: { "Idempotency-Key": key("agent-patch") },
        body: JSON.stringify({ instruction: content, baseVersionId: latest.currentVersionId, expectedGraphHash: currentVersion.graphHash }),
      });
    },
    onSuccess: next => { store(next); setMessage(""); setPanel("team"); },
    onError: () => snapshotQuery.refetch(),
  });
  const decideDesign = useMutation({ mutationFn: (approve: boolean) => api<Snapshot>(`/agent-development/sessions/${snapshot!.conversationId}/design-decision`, { method: "POST", headers: { "Idempotency-Key": key("agent-design") }, body: JSON.stringify({ approve }) }), onSuccess: next => { store(next); if (next.graph) setPanel("graph"); } });
  const updateAgent = useMutation({ mutationFn: ({ agentKey, value }: { agentKey: string; value: Agent }) => api<Snapshot>(`/agent-development/sessions/${snapshot!.conversationId}/agents/${agentKey}`, { method: "PUT", headers: { "Idempotency-Key": key("agent-config") }, body: JSON.stringify(value) }), onSuccess: next => store(next) });
  const simulate = useMutation({ mutationFn: () => api<Run>(`/agent-development/sessions/${snapshot!.conversationId}/simulations`, { method: "POST", headers: { "Idempotency-Key": key("agent-test") }, body: JSON.stringify({ input: parseTestInput(testInput, snapshot) }) }), onSuccess: next => { setRun(next); setPanel("output"); } });
  const decideRun = useMutation({ mutationFn: (approve: boolean) => api<Run>(`/agent-development/runs/${run!.id}/decision`, { method: "POST", headers: { "Idempotency-Key": key("agent-run-decision") }, body: JSON.stringify({ approve }) }), onSuccess: setRun });
  const restoreVersion = useMutation({ mutationFn: (versionId: string) => api<Snapshot>(`/agent-development/sessions/${snapshot!.conversationId}/versions/${versionId}/restore`, { method: "POST", headers: { "Idempotency-Key": key("agent-version") }, body: "{}" }), onSuccess: store });
  const cancel = useMutation({ mutationFn: () => api<Job>(`/agent-development/jobs/${jobId}/cancel`, { method: "POST", headers: { "Idempotency-Key": key("agent-cancel") }, body: "{}" }), onSuccess: next => queryClient.setQueryData(["agent-development-job", next.id], next) });
  function submit(event: FormEvent) { event.preventDefault(); if (!message.trim() || pending) return; if (snapshot?.status !== "DRAFT" && snapshot?.graph && snapshot.currentVersionId && snapshot.validation) patch.mutate(message.trim()); else send.mutate(message.trim()); }
  function newSession() { window.localStorage.removeItem(storageKey); setSessionId(undefined); setMessage(""); setJobId(undefined); setRun(undefined); setTestInput(""); create.mutate(); }

  const pending = create.isPending || send.isPending || patch.isPending || decideDesign.isPending || updateAgent.isPending || simulate.isPending || decideRun.isPending || restoreVersion.isPending || Boolean(jobId && !["SUCCEEDED", "FAILED", "CANCELLED"].includes(job.data?.status ?? ""));
  const error = create.error || send.error || patch.error || decideDesign.error || updateAgent.error || simulate.error || decideRun.error || restoreVersion.error || snapshotQuery.error || (job.data?.status === "FAILED" ? new Error(job.data.errorMessage ?? "에이전트 생성에 실패했습니다.") : null);
  const flowNodes = useMemo<Node[]>(() => snapshot?.graph?.nodes.map(node => ({ id: node.id, position: node.position, data: { label: node.label }, style: { width: 180, borderRadius: 6, border: "1px solid #d4d4d0", fontSize: 12, padding: 12, background: "white" } })) ?? [], [snapshot?.graph]);
  const flowEdges = useMemo<Edge[]>(() => snapshot?.graph?.edges.map(edge => ({ ...edge, animated: true })) ?? [], [snapshot?.graph]);

  return <AppShell kicker="DEVELOP" title="에이전트 개발" workspace>
    <div className="relative grid h-full min-h-0 bg-[#f5f5f2] lg:grid-cols-[250px_minmax(420px,1fr)_360px]">
      <aside className="hidden min-h-0 border-r border-hairline bg-white lg:flex lg:flex-col">
        <div className="flex h-14 items-center justify-between border-b border-hairline px-4"><div className="flex items-center gap-2 text-sm font-semibold"><Sparkles className="h-4 w-4 text-coral" />에이전트 개발</div><button onClick={newSession} title="새 에이전트" aria-label="새 에이전트" className="flex h-8 w-8 items-center justify-center rounded-md hover:bg-cloud"><Plus className="h-4 w-4" /></button></div>
        <div className="min-h-0 flex-1 overflow-auto p-2">{sessions.data?.map(item => <button key={item.conversationId} onClick={() => { setSessionId(item.conversationId); window.localStorage.setItem(storageKey, item.conversationId); setJobId(undefined); }} className={`mb-1 w-full rounded-md px-3 py-3 text-left ${sessionId === item.conversationId ? "bg-ink text-white" : "hover:bg-cloud"}`}><p className="truncate text-sm font-medium">{item.title}</p><p className={`mt-1 truncate text-[11px] ${sessionId === item.conversationId ? "text-stone-300" : "text-mute"}`}>{item.currentVersionNo ? `Version ${item.currentVersionNo}` : "설계 전"} · {koStatus(item.status)}</p></button>)}</div>
        <div className="border-t border-hairline p-3 text-[11px] leading-5 text-mute">대화, 에이전트 구성, 버전 기록이 프로젝트별로 보존됩니다.</div>
      </aside>

      <main className="flex min-h-0 min-w-0 flex-col bg-white">
        <header className="flex h-14 shrink-0 items-center justify-between border-b border-hairline px-4"><div className="min-w-0"><p className="truncate text-sm font-semibold">{snapshot?.proposal?.name ?? sessions.data?.find(item => item.conversationId === sessionId)?.title ?? "새 AI 에이전트"}</p><p className="text-[11px] text-mute">{koStatus(snapshot?.status ?? "DRAFT")}</p></div><div className="flex gap-2 lg:hidden"><button onClick={() => setMobileInspector(true)} title="에이전트 상세" aria-label="에이전트 상세" className="flex h-9 w-9 items-center justify-center rounded-md border border-hairline"><PanelRight className="h-4 w-4" /></button><button onClick={newSession} className="flex items-center gap-2 rounded-md border border-hairline px-3 py-2 text-xs"><Plus className="h-3.5 w-3.5" />새로 만들기</button></div></header>
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
        <div className="grid h-14 shrink-0 grid-cols-5 border-b border-hairline"><InspectorTab active={panel === "team"} onClick={() => setPanel("team")} icon={Users} label="팀" /><InspectorTab active={panel === "resources"} onClick={() => setPanel("resources")} icon={Database} label="리소스" /><InspectorTab active={panel === "graph"} onClick={() => setPanel("graph")} icon={GitBranch} label="구조" /><InspectorTab active={panel === "versions"} onClick={() => setPanel("versions")} icon={History} label="버전" /><InspectorTab active={panel === "output"} onClick={() => setPanel("output")} icon={TestTube2} label="테스트" /></div>
        <div className="min-h-0 flex-1 overflow-auto p-4">
          {panel === "team" && <TeamPanel snapshot={snapshot} pending={pending} decide={approve => decideDesign.mutate(approve)} save={(agentKey, value) => updateAgent.mutate({ agentKey, value })} />}
          {panel === "resources" && <ResourcesPanel snapshot={snapshot} />}
          {panel === "graph" && <div className="h-full min-h-[420px] border border-hairline bg-white">{snapshot?.graph ? <ReactFlow nodes={flowNodes} edges={flowEdges} fitView proOptions={{ hideAttribution: true }}><Background gap={18} size={1} /><Controls showInteractive={false} /></ReactFlow> : <Empty icon={Boxes} text="대화로 에이전트를 만들면 협업 구조가 표시됩니다." />}</div>}
          {panel === "versions" && <VersionsPanel snapshot={snapshot} pending={pending} restore={versionId => restoreVersion.mutate(versionId)} />}
          {panel === "output" && <OutputPanel snapshot={snapshot} run={run} input={testInput} setInput={setTestInput} pending={pending} simulate={() => simulate.mutate()} decide={approve => decideRun.mutate(approve)} />}
        </div>
      </aside>

      {mobileInspector && <section className="absolute inset-0 z-30 flex min-h-0 flex-col bg-[#fafaf8] lg:hidden" aria-label="에이전트 상세">
        <header className="flex h-14 shrink-0 items-center border-b border-hairline bg-white"><div className="grid h-full flex-1 grid-cols-5"><InspectorTab active={panel === "team"} onClick={() => setPanel("team")} icon={Users} label="팀" /><InspectorTab active={panel === "resources"} onClick={() => setPanel("resources")} icon={Database} label="리소스" /><InspectorTab active={panel === "graph"} onClick={() => setPanel("graph")} icon={GitBranch} label="구조" /><InspectorTab active={panel === "versions"} onClick={() => setPanel("versions")} icon={History} label="버전" /><InspectorTab active={panel === "output"} onClick={() => setPanel("output")} icon={TestTube2} label="테스트" /></div><button onClick={() => setMobileInspector(false)} title="닫기" aria-label="닫기" className="flex h-10 w-10 shrink-0 items-center justify-center"><X className="h-4 w-4" /></button></header>
        <div className="min-h-0 flex-1 overflow-auto p-4">
          {panel === "team" && <TeamPanel snapshot={snapshot} pending={pending} decide={approve => decideDesign.mutate(approve)} save={(agentKey, value) => updateAgent.mutate({ agentKey, value })} />}
          {panel === "resources" && <ResourcesPanel snapshot={snapshot} />}
          {panel === "graph" && <div className="h-full min-h-[420px] border border-hairline bg-white">{snapshot?.graph ? <ReactFlow nodes={flowNodes} edges={flowEdges} fitView proOptions={{ hideAttribution: true }}><Background gap={18} size={1} /><Controls showInteractive={false} /></ReactFlow> : <Empty icon={Boxes} text="대화로 에이전트를 만들면 협업 구조가 표시됩니다." />}</div>}
          {panel === "versions" && <VersionsPanel snapshot={snapshot} pending={pending} restore={versionId => restoreVersion.mutate(versionId)} />}
          {panel === "output" && <OutputPanel snapshot={snapshot} run={run} input={testInput} setInput={setTestInput} pending={pending} simulate={() => simulate.mutate()} decide={approve => decideRun.mutate(approve)} />}
        </div>
      </section>}
    </div>
  </AppShell>;
}

function TeamPanel({ snapshot, pending, decide, save }: { snapshot?: Snapshot; pending: boolean; decide: (approve: boolean) => void; save: (agentKey: string, value: Agent) => void }) {
  if (!snapshot?.proposal) return <Empty icon={Users} text="역할에 맞는 에이전트 팀이 여기에 구성됩니다." />;
  return <div className="space-y-3"><div className="rounded-md border border-hairline bg-white p-4"><p className="text-xs font-semibold text-coral">AGENT PACKAGE</p><p className="mt-2 text-sm leading-6">{snapshot.proposal.summary}</p><div className="mt-3 flex flex-wrap gap-1">{snapshot.proposal.capabilities.map(item => <span key={item} className="rounded-md bg-cloud px-2 py-1 text-[11px]">{item}</span>)}</div></div>{snapshot.agentDefinitions.length === 0 && <div className="rounded-md border border-hairline bg-white p-4"><p className="text-xs font-semibold">결정론적 Function 워크플로</p><p className="mt-2 text-xs leading-5 text-mute">AI 팀원 없이 선언된 Function과 조건만으로 실행됩니다.</p></div>}{snapshot.agentDefinitions.map(agent => <AgentEditor key={`${agent.key}-${snapshot.currentVersionId ?? "draft"}`} agent={agent} resources={snapshot.proposal?.resourcePlan?.bindings ?? []} disabled={pending || !snapshot.currentVersionId} save={value => save(agent.key, value)} />)}{snapshot.status === "WAITING_DESIGN_APPROVAL" && <div className="grid grid-cols-2 gap-2 pt-1"><button disabled={pending} onClick={() => decide(false)} className="rounded-md border border-hairline px-3 py-2.5 text-xs disabled:opacity-40">수정 요청</button><button disabled={pending} onClick={() => decide(true)} className="rounded-md bg-ink px-3 py-2.5 text-xs text-white disabled:opacity-40">설계 승인</button></div>}{snapshot.currentVersionId && <a href={`/api/agent-development/sessions/${snapshot.conversationId}/package`} className="flex items-center justify-center gap-2 rounded-md border border-hairline bg-white px-3 py-2.5 text-xs"><FileCode2 className="h-3.5 w-3.5" />에이전트 패키지</a>}</div>;
}
function AgentEditor({ agent, resources, disabled, save }: { agent: Agent; resources: Resource[]; disabled: boolean; save: (value: Agent) => void }) {
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(agent);
  const tools = resources.filter(item => item.resourceKind === "TOOL");
  const skills = resources.filter(item => item.resourceKind === "SKILL");
  function toggle(field: "toolKeys" | "skillKeys", keyValue: string) { setValue(current => ({ ...current, [field]: current[field].includes(keyValue) ? current[field].filter(item => item !== keyValue) : [...current[field], keyValue] })); }
  if (!editing) return <article className="rounded-md border border-hairline bg-white p-4"><div className="flex items-center gap-2"><span className="flex h-8 w-8 items-center justify-center rounded-md bg-ink text-white"><Bot className="h-4 w-4" /></span><div className="min-w-0 flex-1"><p className="truncate text-sm font-semibold">{agent.name}</p><p className="truncate text-[11px] text-mute">{agent.key}</p></div><button disabled={disabled} onClick={() => setEditing(true)} className="rounded-md border border-hairline px-2 py-1 text-[11px] disabled:opacity-35">속성</button></div><p className="mt-3 text-xs leading-5 text-charcoal">{agent.role}</p>{agent.behaviorRules.length > 0 && <div className="mt-3 border-t border-hairline pt-3"><p className="flex items-center gap-1 text-[11px] font-semibold"><Wrench className="h-3 w-3" />행동 규칙</p>{agent.behaviorRules.slice(0, 3).map(rule => <p key={rule} className="mt-1 text-[11px] leading-5 text-mute">· {rule}</p>)}</div>}</article>;
  return <article className="space-y-3 rounded-md border border-charcoal bg-white p-4"><div><label className="text-[11px] font-semibold">이름</label><input value={value.name} onChange={event => setValue({ ...value, name: event.target.value })} className="mt-1 w-full rounded-md border border-hairline px-3 py-2 text-xs" /></div><div><label className="text-[11px] font-semibold">역할과 시스템 지침</label><textarea value={value.role} onChange={event => setValue({ ...value, role: event.target.value })} rows={4} className="mt-1 w-full resize-y rounded-md border border-hairline px-3 py-2 text-xs leading-5" /></div><RuleEditor label="행동 규칙" values={value.behaviorRules} onChange={behaviorRules => setValue({ ...value, behaviorRules })} /><RuleEditor label="금지 규칙" values={value.forbiddenRules} onChange={forbiddenRules => setValue({ ...value, forbiddenRules })} /><RuleEditor label="근거 요구" values={value.evidenceRequirements} onChange={evidenceRequirements => setValue({ ...value, evidenceRequirements })} /><ResourceChecks label="도구" items={tools} selected={value.toolKeys} toggle={resourceKey => toggle("toolKeys", resourceKey)} /><ResourceChecks label="스킬" items={skills} selected={value.skillKeys} toggle={resourceKey => toggle("skillKeys", resourceKey)} /><div><label className="text-[11px] font-semibold">메모리</label><select value="NONE" disabled className="mt-1 w-full rounded-md border border-hairline bg-cloud px-3 py-2 text-xs text-mute"><option value="NONE">연결된 메모리 없음</option></select></div><div className="grid grid-cols-2 gap-2"><button onClick={() => { setValue(agent); setEditing(false); }} className="rounded-md border border-hairline py-2 text-xs">취소</button><button onClick={() => { save(value); setEditing(false); }} className="flex items-center justify-center gap-1 rounded-md bg-ink py-2 text-xs text-white"><Save className="h-3.5 w-3.5" />새 버전 저장</button></div></article>;
}
function RuleEditor({ label, values, onChange }: { label: string; values: string[]; onChange: (values: string[]) => void }) { return <div><label className="text-[11px] font-semibold">{label}</label><textarea value={values.join("\n")} onChange={event => onChange(event.target.value.split("\n"))} rows={3} className="mt-1 w-full resize-y rounded-md border border-hairline px-3 py-2 text-xs leading-5" /></div>; }
function ResourceChecks({ label, items, selected, toggle }: { label: string; items: Resource[]; selected: string[]; toggle: (key: string) => void }) { if (!items.length) return null; return <fieldset><legend className="text-[11px] font-semibold">{label}</legend><div className="mt-1 space-y-1">{items.map(item => <label key={item.resourceKey} className="flex items-center gap-2 text-xs"><input type="checkbox" checked={selected.includes(item.resourceKey)} onChange={() => toggle(item.resourceKey)} />{item.label}</label>)}</div></fieldset>; }
function ResourcesPanel({ snapshot }: { snapshot?: Snapshot }) {
  const resources = snapshot?.proposal?.resourcePlan?.bindings ?? [];
  if (!resources.length) return <Empty icon={Database} text="설계가 생성되면 필요한 도구, 스킬, 커넥터와 메모리가 표시됩니다." />;
  return <div className="space-y-3"><div className="grid grid-cols-2 gap-2"><StatusBox label="테스트" ready={snapshot?.proposal?.resourcePlan?.simulationReady ?? false} /><StatusBox label="실행" ready={snapshot?.proposal?.resourcePlan?.productionReady ?? false} /></div>{resources.map(item => <article key={`${item.resourceKind}-${item.resourceKey}`} className="rounded-md border border-hairline bg-white p-3"><div className="flex items-center justify-between gap-2"><p className="truncate text-xs font-semibold">{item.label}</p><span className={`rounded px-1.5 py-0.5 text-[10px] ${item.availability === "INSTALLED" ? "bg-emerald-50 text-emerald-700" : "bg-amber-50 text-amber-800"}`}>{item.availability}</span></div><p className="mt-1 text-[10px] uppercase text-mute">{item.resourceKind} · {item.resourceKey}</p><p className="mt-2 text-[11px] leading-5 text-mute">{item.reason}</p></article>)}</div>;
}
function StatusBox({ label, ready }: { label: string; ready: boolean }) { return <div className="rounded-md border border-hairline bg-white p-3"><p className="text-[10px] text-mute">{label} 준비</p><p className={`mt-1 text-xs font-semibold ${ready ? "text-emerald-700" : "text-amber-800"}`}>{ready ? "가능" : "설정 필요"}</p></div>; }
function VersionsPanel({ snapshot, pending, restore }: { snapshot?: Snapshot; pending: boolean; restore: (versionId: string) => void }) { return <div className="space-y-2">{snapshot?.versions.length ? snapshot.versions.map(version => <article key={version.id} className="rounded-md border border-hairline bg-white p-3"><div className="flex items-center justify-between"><p className="text-sm font-medium">Version {version.versionNo}</p>{version.approved && <CheckCircle2 className="h-4 w-4 text-emerald-600" />}</div><p className="mt-2 text-xs leading-5 text-mute">{version.changeSummary}</p>{version.id !== snapshot.currentVersionId && <button disabled={pending} onClick={() => restore(version.id)} className="mt-3 flex items-center gap-1 text-[11px] font-medium disabled:opacity-35"><RotateCcw className="h-3 w-3" />이 버전 복원</button>}</article>) : <Empty icon={History} text="완성된 설계 버전이 여기에 쌓입니다." />}</div>; }
function OutputPanel({ snapshot, run, input, setInput, pending, simulate, decide }: { snapshot?: Snapshot; run?: Run; input: string; setInput: (value: string) => void; pending: boolean; simulate: () => void; decide: (approve: boolean) => void }) {
  if (!snapshot?.currentVersionId) return <Empty icon={TestTube2} text="설계를 승인하면 실제 버전에 대한 샘플 테스트를 실행할 수 있습니다." />;
  const sample = JSON.stringify(defaultTestInput(snapshot), null, 2);
  return <div className="space-y-3"><div className="rounded-md border border-hairline bg-white p-3"><label className="text-[11px] font-semibold">테스트 입력</label><textarea aria-label="테스트 입력" value={input} onChange={event => setInput(event.target.value)} placeholder={sample} rows={4} className="mt-2 w-full resize-y rounded-md border border-hairline px-3 py-2 text-xs leading-5" /><button disabled={pending} onClick={simulate} className="mt-2 flex w-full items-center justify-center gap-1 rounded-md bg-ink py-2.5 text-xs text-white disabled:opacity-35"><Play className="h-3.5 w-3.5" />테스트 실행</button></div>{run && <div className="rounded-md border border-hairline bg-white p-3"><div className="flex items-center justify-between"><p className="text-xs font-semibold">실행 결과</p><span className="text-[10px] text-mute">{run.status}</span></div>{run.failureMessage && <div className="mt-3 rounded-md border border-amber-200 bg-amber-50 p-3 text-[11px] leading-5 text-amber-900"><p className="font-semibold">{run.failureCode ?? run.status}</p><p className="mt-1">{run.failureMessage}</p></div>}<div className="mt-3 space-y-1">{run.steps.map(step => <div key={`${step.sequenceNo}-${step.nodeId}`} className="flex items-center justify-between border-b border-hairline py-1.5 text-[11px]"><span>{step.sequenceNo}. {step.nodeType}</span><span className="text-mute">{step.status}</span></div>)}</div>{run.status === "WAITING_APPROVAL" && <div className="mt-3 grid grid-cols-2 gap-2"><button onClick={() => decide(false)} className="rounded-md border border-hairline py-2 text-xs">거절</button><button onClick={() => decide(true)} className="rounded-md bg-ink py-2 text-xs text-white">계속 실행</button></div>}<pre className="mt-3 max-h-72 overflow-auto whitespace-pre-wrap rounded-md bg-[#f5f5f2] p-3 text-[11px] leading-5">{JSON.stringify(run.output ?? {}, null, 2)}</pre></div>}</div>;
}
function InspectorTab({ active, onClick, icon: Icon, label }: { active: boolean; onClick: () => void; icon: typeof Users; label: string }) { return <button onClick={onClick} className={`flex items-center justify-center gap-1.5 border-b-2 text-xs ${active ? "border-ink bg-white text-ink" : "border-transparent text-mute hover:text-ink"}`}><Icon className="h-3.5 w-3.5" />{label}</button>; }
function Empty({ icon: Icon, text }: { icon: typeof Users; text: string }) { return <div className="flex h-full min-h-52 flex-col items-center justify-center px-6 text-center text-xs leading-5 text-mute"><Icon className="mb-3 h-7 w-7" />{text}</div>; }
function koStatus(status: string) { return ({ DRAFT: "새 에이전트", FAILED: "생성 실패", NEEDS_CLARIFICATION: "추가 대화 필요", WAITING_DESIGN_APPROVAL: "설계 검토", READY_TO_SIMULATE: "테스트 준비", ACTIVE: "사용 중", STOPPED: "중지됨" } as Record<string, string>)[status] ?? status; }
function stageLabel(stage: string) { return ({ REQUEST_ACCEPTED: "요청 접수", CODEX_ANALYZING: "역할과 도구 설계", STRUCTURE_VALIDATING: "구조 검증", DESIGN_SAVING: "버전 저장" } as Record<string, string>)[stage] ?? stage; }
