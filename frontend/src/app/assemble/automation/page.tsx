"use client";
/* eslint-disable react-hooks/set-state-in-effect -- hydrate the server-owned Builder conversation id from browser storage once */

import { FormEvent, useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ReactFlow, Background, Controls, MiniMap, type Edge, type Node, type NodeMouseHandler } from "@xyflow/react";
import { AlertTriangle, Bot, Check, ChevronRight, CirclePlay, FileText, GitBranch, MessageSquare, Pause, Play, Send, ShieldCheck, Sparkles, Workflow } from "lucide-react";
import { AppShell } from "@/components/AppShell";
import { api } from "@/lib/api";

type FieldDefinition = { name: string; type: string; required: boolean; description: string };
type AgentDefinition = { key: string; name: string; role: string; inputSchema: FieldDefinition[]; outputSchema: FieldDefinition[]; behaviorRules: string[]; forbiddenRules: string[]; evidenceRequirements: string[] };
type GuideDefinition = { key: string; title: string; description: string; fields: Array<{ key: string; label: string; type: string; required: boolean; help: string }> };
type WorkflowNode = { id: string; nodeType: string; label: string; position: { x: number; y: number }; config: Record<string, unknown> };
type WorkflowGraph = { schemaVersion: string; workflowId: string; entryNodeId: string; nodes: WorkflowNode[]; edges: Array<{ id: string; source: string; target: string }> };
type Snapshot = {
  workspaceId: string; conversationId: string; workflowId: string; status: string;
  requirement?: { objective: string; trigger: string; inputs: string[]; outputs: string[]; steps: string[]; decisions: string[]; exceptions: string[] };
  clarificationQuestions: Array<{ id: string; field: string; question: string }>;
  proposal?: { name: string; summary: string; capabilities: string[]; integrations: string[]; approvalPoints: string[]; failurePolicy: string };
  agentDefinitions: AgentDefinition[]; agentMarkdown: string[]; guideDefinitions: GuideDefinition[]; guideMarkdown: string[];
  graph?: WorkflowGraph; validation?: { valid: boolean; graphHash: string; validatorVersion: string; issues: Array<{ code: string; message: string; nodeId?: string }> };
  currentVersionId?: string; approvedVersionId?: string;
  messages: Array<{ id: string; role: string; content: string; workflowVersionId?: string; createdAt: string }>;
  versions: Array<{ id: string; versionNo: number; graphHash: string; changeSummary: string; approved: boolean; createdAt: string }>;
};
type Run = { id: string; status: string; currentNodeId?: string; output?: Record<string, unknown>; requirementMatched?: boolean; pendingApprovalId?: string; steps: Array<{ nodeId: string; nodeType: string; sequenceNo: number; status: string; input: Record<string, unknown>; output?: Record<string, unknown>; errorMessage?: string }> };
type Tab = "design" | "canvas" | "simulation";

const storageKey = "agentown.builder.conversation.v1";
const sampleRequest = "저는 회사에서 고객 문의를 담당하고 있습니다. Slack의 #customer-support 채널에 문의가 올라오면, Notion의 고객 FAQ 데이터베이스에서 관련 내용을 찾아 답변 초안을 만들고 있습니다. 답변은 바로 보내지 말고 제가 검토하고 승인한 경우에만 해당 Slack 메시지의 스레드로 전송되게 자동화하고 싶습니다.";

function key(prefix: string) { return `${prefix}-${crypto.randomUUID()}`; }

export default function AutomationBuilderPage() {
  const queryClient = useQueryClient();
  const [conversationId, setConversationId] = useState<string>();
  const [tab, setTab] = useState<Tab>("design");
  const [message, setMessage] = useState(sampleRequest);
  const [simulationInput, setSimulationInput] = useState("환불은 언제 처리되나요?");
  const [run, setRun] = useState<Run>();
  const [selectedNode, setSelectedNode] = useState<WorkflowNode>();

  useEffect(() => { setConversationId(window.localStorage.getItem(storageKey) ?? undefined); }, []);
  const snapshotQuery = useQuery({ queryKey: ["builder", conversationId], queryFn: () => api<Snapshot>(`/builder/conversations/${conversationId}`), enabled: Boolean(conversationId) });
  const snapshot = snapshotQuery.data;
  function store(next: Snapshot) {
    window.localStorage.setItem(storageKey, next.conversationId); setConversationId(next.conversationId);
    queryClient.setQueryData(["builder", next.conversationId], next);
  }

  const create = useMutation({ mutationFn: () => api<Snapshot>("/builder/conversations", { method: "POST", headers: { "Idempotency-Key": key("conversation") }, body: "{}" }), onSuccess: store });
  const send = useMutation({
    mutationFn: async (content: string) => {
      let current = snapshot;
      if (!current) current = await api<Snapshot>("/builder/conversations", { method: "POST", headers: { "Idempotency-Key": key("conversation") }, body: "{}" });
      return api<Snapshot>(`/builder/conversations/${current.conversationId}/messages`, { method: "POST", headers: { "Idempotency-Key": key("message") }, body: JSON.stringify({ content }) });
    },
    onSuccess: (next) => { store(next); setMessage(""); if (next.graph) setTab("canvas"); },
  });
  const decideDesign = useMutation({ mutationFn: (approve: boolean) => api<Snapshot>(`/builder/workflows/${snapshot!.workflowId}/design-decision`, { method: "POST", headers: { "Idempotency-Key": key("design") }, body: JSON.stringify({ approve }) }), onSuccess: (next) => { store(next); if (next.graph) setTab("canvas"); } });
  const patch = useMutation({ mutationFn: (instruction: string) => api<Snapshot>(`/builder/workflows/${snapshot!.workflowId}/patches`, { method: "POST", headers: { "Idempotency-Key": key("patch") }, body: JSON.stringify({ instruction, baseVersionId: snapshot!.currentVersionId, expectedGraphHash: snapshot!.validation!.graphHash }) }), onSuccess: (next) => { store(next); setMessage(""); setTab("canvas"); } });
  const simulate = useMutation({ mutationFn: () => api<Run>(`/builder/workflows/${snapshot!.workflowId}/simulations`, { method: "POST", headers: { "Idempotency-Key": key("simulation") }, body: JSON.stringify({ input: { message: simulationInput } }) }), onSuccess: (next) => { setRun(next); setTab("simulation"); } });
  const approveRun = useMutation({ mutationFn: (approve: boolean) => api<Run>(`/builder/simulations/${run!.id}/approval`, { method: "POST", headers: { "Idempotency-Key": key("execution-approval") }, body: JSON.stringify({ approve }) }), onSuccess: setRun });

  function submit(event: FormEvent) {
    event.preventDefault(); if (!message.trim()) return;
    if (snapshot?.graph && snapshot.currentVersionId) patch.mutate(message); else send.mutate(message);
  }

  const flowNodes = useMemo<Node[]>(() => snapshot?.graph?.nodes.map((node) => ({
    id: node.id, position: node.position, data: { label: <NodeCard node={node} /> },
    style: { width: 210, border: node.nodeType === "human.approval" ? "2px solid #ea725c" : "1px solid #d4d4d0", borderRadius: 12, background: "white", padding: 0, boxShadow: "0 8px 24px rgba(17,17,17,.06)" },
  })) ?? [], [snapshot?.graph]);
  const flowEdges = useMemo<Edge[]>(() => snapshot?.graph?.edges.map((edge) => ({ ...edge, animated: true, style: { stroke: "#ea725c", strokeWidth: 2 } })) ?? [], [snapshot?.graph]);
  const onNodeClick: NodeMouseHandler = (_, node) => setSelectedNode(snapshot?.graph?.nodes.find((item) => item.id === node.id));
  const pending = create.isPending || send.isPending || decideDesign.isPending || patch.isPending || simulate.isPending || approveRun.isPending;
  const error = create.error || send.error || decideDesign.error || patch.error || simulate.error || approveRun.error || snapshotQuery.error;

  return <AppShell kicker="ASSEMBLE · BUILDER" title="업무 자동화">
    <div className="mb-5 flex flex-wrap items-center justify-between gap-3 border border-hairline bg-white px-5 py-4">
      <div><p className="text-xs font-semibold tracking-[.14em] text-coral">BUILDER MVP · MOCK CONNECTORS</p><p className="mt-1 text-sm text-mute">자연어 설계와 캔버스가 동일한 서버 Workflow Version을 사용합니다.</p></div>
      <div className="flex items-center gap-2"><StatusBadge status={snapshot?.status ?? "NEW"} /><button type="button" onClick={() => { window.localStorage.removeItem(storageKey); setConversationId(undefined); setRun(undefined); create.mutate(); }} className="rounded-pill border border-hairline px-4 py-2 text-xs font-medium">새 자동화</button></div>
    </div>

    <nav aria-label="Builder 화면" className="mb-5 grid grid-cols-3 border border-hairline bg-white p-1">
      <TabButton active={tab === "design"} onClick={() => setTab("design")} icon={MessageSquare} label="설계 · 대화" />
      <TabButton active={tab === "canvas"} onClick={() => setTab("canvas")} icon={Workflow} label="전체 캔버스" disabled={!snapshot?.graph} />
      <TabButton active={tab === "simulation"} onClick={() => setTab("simulation")} icon={CirclePlay} label="시뮬레이션" disabled={!snapshot?.graph} />
    </nav>

    {error && <div role="alert" className="mb-5 flex gap-2 border border-red-200 bg-red-50 p-4 text-sm text-red-800"><AlertTriangle className="h-5 w-5 shrink-0" />{error.message}</div>}

    {tab === "design" && <DesignTab snapshot={snapshot} message={message} setMessage={setMessage} submit={submit} pending={pending} decide={(approve) => decideDesign.mutate(approve)} />}
    {tab === "canvas" && snapshot?.graph && <section data-testid="builder-canvas" className="relative h-[680px] overflow-hidden border border-hairline bg-[#f6f6f3]">
      <ReactFlow nodes={flowNodes} edges={flowEdges} onNodeClick={onNodeClick} fitView fitViewOptions={{ padding: .2 }} minZoom={.35} maxZoom={1.5} nodesDraggable={false} nodesConnectable={false}>
        <Background gap={20} color="#d4d4d0" /><Controls /><MiniMap pannable zoomable nodeColor={(node) => node.id.includes("approval") ? "#ea725c" : "#111"} />
      </ReactFlow>
      <div className="absolute left-5 top-5 z-10 rounded-lg border border-hairline bg-white/95 px-4 py-3 shadow-sm"><p className="text-xs font-semibold text-ink">Workflow Version {snapshot.versions[0]?.versionNo}</p><p className="mt-1 text-[11px] text-mute">{snapshot.versions[0]?.changeSummary} · {snapshot.validation?.validatorVersion}</p></div>
      {selectedNode && <aside className="absolute bottom-4 right-4 top-4 z-10 w-80 overflow-auto rounded-xl border border-hairline bg-white p-5 shadow-xl">
        <button className="float-right text-xs text-mute" onClick={() => setSelectedNode(undefined)}>닫기</button><p className="text-xs font-semibold tracking-[.12em] text-coral">NODE SETTINGS</p><h3 className="mt-2 text-lg font-medium">{selectedNode.label}</h3><p className="mt-1 font-mono text-xs text-mute">{selectedNode.nodeType}</p>
        <div className="mt-5 space-y-3">{Object.entries(selectedNode.config).length ? Object.entries(selectedNode.config).map(([name, value]) => <label key={name} className="block text-xs font-medium">{name}<input readOnly value={String(value)} className="mt-1 w-full border border-hairline bg-cloud px-3 py-2 font-normal" /></label>) : <p className="text-sm text-mute">Mock 단계라 별도 연결 계정이 필요하지 않습니다.</p>}</div>
        <div className="mt-5 border-t border-hairline pt-4 text-xs leading-5 text-mute"><ShieldCheck className="mb-2 h-5 w-5 text-green-700" />토큰은 Graph에 저장되지 않습니다. 실제 OAuth는 후속 단계입니다.</div>
      </aside>}
    </section>}
    {tab === "simulation" && snapshot?.graph && <SimulationTab input={simulationInput} setInput={setSimulationInput} run={run} pending={pending} start={() => simulate.mutate()} decide={(approve) => approveRun.mutate(approve)} />}
  </AppShell>;
}

function DesignTab({ snapshot, message, setMessage, submit, pending, decide }: { snapshot?: Snapshot; message: string; setMessage: (value: string) => void; submit: (event: FormEvent) => void; pending: boolean; decide: (approve: boolean) => void }) {
  return <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_minmax(340px,.75fr)]">
    <section className="border border-hairline bg-white">
      <div className="border-b border-hairline bg-ink p-5 text-white"><div className="flex items-center gap-3"><Sparkles className="h-5 w-5 text-coral" /><div><h2 className="font-medium">업무를 자연어로 설명하세요</h2><p className="mt-1 text-xs text-stone-300">고정 메타 에이전트가 구조화된 계약으로 분석합니다.</p></div></div></div>
      <div data-testid="builder-conversation" className="max-h-[430px] min-h-60 space-y-3 overflow-auto p-5">{snapshot?.messages.length ? snapshot.messages.map((item) => <div key={item.id} className={`max-w-[88%] rounded-xl px-4 py-3 text-sm leading-6 ${item.role === "USER" ? "ml-auto bg-ink text-white" : "bg-cloud text-ink"}`}><p className="text-[10px] font-semibold tracking-wider opacity-60">{item.role === "USER" ? "나" : "BUILDER"}</p>{item.content}{item.workflowVersionId && <p className="mt-1 text-[10px] opacity-60">Version {item.workflowVersionId.slice(0, 8)}</p>}</div>) : <div className="py-14 text-center text-sm text-mute"><Bot className="mx-auto mb-3 h-9 w-9 text-coral" />예시 문장을 그대로 보내도 됩니다.</div>}</div>
      <form onSubmit={submit} className="border-t border-hairline p-4"><textarea aria-label="업무 설명 또는 수정 요청" value={message} onChange={(event) => setMessage(event.target.value)} rows={4} maxLength={4000} className="w-full resize-none border border-hairline p-3 text-sm leading-6 outline-none focus:border-coral" placeholder={snapshot?.graph ? "예: Slack 답변 전 담당자 승인을 추가해줘." : "자동화할 업무를 설명해 주세요."} /><button disabled={pending || !message.trim()} className="mt-3 flex w-full items-center justify-center gap-2 rounded-pill bg-coral px-5 py-3 text-sm font-medium text-white disabled:opacity-50"><Send className="h-4 w-4" />{pending ? "처리 중…" : snapshot?.graph ? "Graph Patch 요청" : "분석 시작"}</button></form>
    </section>
    <section className="space-y-4">
      {snapshot?.clarificationQuestions.map((question) => <article key={question.id} className="border border-amber-200 bg-amber-50 p-5"><p className="text-xs font-semibold text-amber-800">추가 정보 필요</p><p className="mt-2 text-sm">{question.question}</p></article>)}
      {snapshot?.requirement && <Card title="업무 분석" icon={GitBranch}><p className="text-sm leading-6">{snapshot.requirement.objective}</p><FlowPills items={snapshot.requirement.steps} /></Card>}
      {snapshot?.proposal && <Card title="자동화 설계안" icon={Workflow}><p className="text-sm leading-6 text-charcoal">{snapshot.proposal.summary}</p><div className="mt-4 grid gap-2 sm:grid-cols-2">{snapshot.proposal.capabilities.map((item) => <span key={item} className="border border-hairline bg-cloud px-3 py-2 text-xs">{item}</span>)}</div><p className="mt-4 text-xs text-mute">승인 지점: {snapshot.proposal.approvalPoints.join(", ")}</p></Card>}
      {snapshot?.agentDefinitions.length ? <Card title={`AI Agent ${snapshot.agentDefinitions.length}`} icon={Bot}>{snapshot.agentDefinitions.map((agent) => <div key={agent.key} className="border-l-2 border-coral pl-3"><p className="text-sm font-medium">{agent.name}</p><p className="mt-1 text-xs leading-5 text-mute">{agent.role}</p></div>)}</Card> : null}
      {snapshot?.guideDefinitions.length ? <Card title={`설정 Guide ${snapshot.guideDefinitions.length}`} icon={FileText}>{snapshot.guideDefinitions.map((guide) => <div key={guide.key}><p className="text-sm font-medium">{guide.title}</p><p className="mt-1 text-xs text-mute">{guide.fields.map((field) => field.label).join(" · ")}</p></div>)}</Card> : null}
      {snapshot?.status === "WAITING_DESIGN_APPROVAL" && <div className="grid grid-cols-2 gap-2"><button onClick={() => decide(false)} disabled={pending} className="rounded-pill border border-hairline px-5 py-3 text-sm">수정 요청</button><button data-testid="approve-design" onClick={() => decide(true)} disabled={pending} className="rounded-pill bg-ink px-5 py-3 text-sm text-white">설계 승인</button></div>}
    </section>
  </div>;
}

function SimulationTab({ input, setInput, run, pending, start, decide }: { input: string; setInput: (value: string) => void; run?: Run; pending: boolean; start: () => void; decide: (approve: boolean) => void }) {
  return <div className="grid gap-5 lg:grid-cols-[360px_minmax(0,1fr)]">
    <section className="border border-hairline bg-white p-5"><p className="text-xs font-semibold tracking-[.12em] text-coral">SAMPLE INPUT</p><h2 className="mt-2 text-lg font-medium">Mock 시뮬레이션</h2><textarea aria-label="시뮬레이션 문의" value={input} onChange={(event) => setInput(event.target.value)} rows={5} className="mt-5 w-full border border-hairline p-3 text-sm" /><p className="mt-3 text-xs leading-5 text-mute">Notion Mock는 환불 3~5일 FAQ를 반환하며, Slack Mock는 실제 전송 없이 예정 메시지만 반환합니다.</p><button data-testid="start-simulation" onClick={start} disabled={pending || !input.trim()} className="mt-4 flex w-full items-center justify-center gap-2 rounded-pill bg-ink px-5 py-3 text-sm text-white disabled:opacity-50"><Play className="h-4 w-4" />시뮬레이션 실행</button></section>
    <section className="border border-hairline bg-white p-5"><div className="flex items-center justify-between"><h2 className="text-lg font-medium">단계별 실행 상태</h2><StatusBadge status={run?.status ?? "NOT_STARTED"} /></div>
      {!run ? <div className="py-28 text-center text-sm text-mute">샘플 입력으로 실행하면 StepRun이 여기에 표시됩니다.</div> : <div className="mt-5 space-y-3">{run.steps.map((step, index) => <article key={`${step.nodeId}-${step.sequenceNo}`} className="flex gap-3 border border-hairline p-4"><span className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-xs ${step.status === "SUCCEEDED" ? "bg-green-100 text-green-800" : step.status === "WAITING_APPROVAL" ? "bg-amber-100 text-amber-800" : "bg-cloud"}`}>{index + 1}</span><div className="min-w-0"><p className="text-sm font-medium">{step.nodeType}</p><p className="mt-1 text-xs text-mute">{step.status}</p>{step.output && <pre className="mt-2 max-h-28 overflow-auto whitespace-pre-wrap bg-cloud p-2 text-[11px]">{JSON.stringify(step.output, null, 2)}</pre>}</div></article>)}
        {run.status === "WAITING_APPROVAL" && <div className="border border-amber-200 bg-amber-50 p-5"><div className="flex gap-3"><Pause className="h-5 w-5 text-amber-700" /><div><p className="text-sm font-medium">담당자 승인 대기</p><p className="mt-1 text-xs text-mute">Slack 답변 Mock 직전에서 영속적으로 중단되었습니다.</p></div></div><div className="mt-4 grid grid-cols-2 gap-2"><button disabled={pending} onClick={() => decide(false)} className="rounded-pill border border-hairline py-2 text-sm">거절</button><button data-testid="approve-execution" disabled={pending} onClick={() => decide(true)} className="rounded-pill bg-coral py-2 text-sm text-white">승인 후 재개</button></div></div>}
        {run.status === "SUCCEEDED" && <div className="border border-green-200 bg-green-50 p-5 text-sm text-green-900"><div className="flex items-center gap-2 font-medium"><Check className="h-5 w-5" />시뮬레이션 완료</div><p className="mt-2 text-xs">요구사항 일치: {run.requirementMatched ? "통과" : "검토 필요"} · 실제 외부 전송 없음</p></div>}
      </div>}
    </section>
  </div>;
}

function NodeCard({ node }: { node: WorkflowNode }) { return <div className="p-4 text-left"><p className="text-[10px] font-semibold uppercase tracking-[.12em] text-coral">{node.nodeType}</p><p className="mt-2 text-sm font-medium text-ink">{node.label}</p><p className="mt-2 text-[11px] text-mute">클릭하여 설정 보기</p></div>; }
function StatusBadge({ status }: { status: string }) { return <span className="rounded-pill border border-hairline bg-cloud px-3 py-1.5 text-[11px] font-semibold tracking-wide text-charcoal">{status}</span>; }
function TabButton({ active, onClick, icon: Icon, label, disabled }: { active: boolean; onClick: () => void; icon: typeof Workflow; label: string; disabled?: boolean }) { return <button type="button" onClick={onClick} disabled={disabled} className={`flex items-center justify-center gap-2 px-4 py-3 text-sm font-medium transition disabled:cursor-not-allowed disabled:opacity-30 ${active ? "bg-ink text-white" : "text-charcoal hover:bg-cloud"}`}><Icon className="h-4 w-4" />{label}</button>; }
function Card({ title, icon: Icon, children }: { title: string; icon: typeof Workflow; children: React.ReactNode }) { return <article className="border border-hairline bg-white p-5"><div className="mb-4 flex items-center gap-2"><Icon className="h-4 w-4 text-coral" /><h2 className="text-sm font-medium">{title}</h2></div>{children}</article>; }
function FlowPills({ items }: { items: string[] }) { return <div className="mt-4 flex flex-wrap items-center gap-1">{items.map((item, index) => <span key={item} className="flex items-center gap-1 text-xs"><span className="border border-hairline bg-cloud px-2 py-1.5">{item}</span>{index < items.length - 1 && <ChevronRight className="h-3 w-3 text-mute" />}</span>)}</div>; }
