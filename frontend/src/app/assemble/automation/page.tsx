"use client";
/* eslint-disable react-hooks/set-state-in-effect */

import { PointerEvent, useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Bot, BrainCircuit, Cable, Check, ChevronRight, CirclePlay, FileText, GripVertical, Hash, MessageSquare, Plus, Save, Send, Sparkles, Trash2, Unplug, Workflow } from "lucide-react";
import { AppShell } from "@/components/AppShell";
import { api } from "@/lib/api";

type NodeKind = "MANUAL_TRIGGER" | "SLACK_TRIGGER" | "HARNESS" | "NOTION_ACTION" | "SLACK_ACTION";
type Harness = { id: string; name: string; description?: string; status: string; resultFormat?: string };
type AutomationNode = {
  id: string;
  kind: NodeKind;
  x: number;
  y: number;
  config: { harnessId?: string; sampleInput?: string; channel?: string; database?: string; contentSource?: string };
};
type AutomationEdge = { id: string; source: string; target: string };
type Draft = { name: string; nodes: AutomationNode[]; edges: AutomationEdge[] };
type AutomationDesignProposal = {
  name: string;
  reply: string;
  handledBy: Array<{ key: string; name: string; responsibility: string }>;
  analysis: Array<{ agentKey: string; agentName: string; summary: string; findings: string[] }>;
  matchedHarnessId?: string;
  matchedHarnessName?: string;
  nodes: Array<{ key: string; kind: NodeKind; label: string; config: Record<string, string> }>;
  edges: Array<{ source: string; target: string }>;
  warnings: string[];
};

const STORAGE_KEY = "agentown.automation.draft.v1";
const NODE_WIDTH = 224;
const NODE_HEIGHT = 132;
const CANVAS_WIDTH = 1040;
const CANVAS_HEIGHT = 640;

const nodeCatalog: Array<{ kind: NodeKind; label: string; detail: string }> = [
  { kind: "MANUAL_TRIGGER", label: "수동 시작", detail: "버튼으로 자동화를 시작합니다." },
  { kind: "SLACK_TRIGGER", label: "Slack 메시지 수신", detail: "채널의 새 메시지를 입력으로 받습니다." },
  { kind: "HARNESS", label: "내 하네스 실행", detail: "발행한 하네스를 실제 업무 엔진으로 사용합니다." },
  { kind: "NOTION_ACTION", label: "Notion 페이지 생성", detail: "하네스 결과를 Notion에 전달합니다." },
  { kind: "SLACK_ACTION", label: "Slack 메시지 전송", detail: "결과나 승인 요청을 채널에 전송합니다." },
];

const kindMeta: Record<NodeKind, { eyebrow: string; title: string; tone: string; icon: typeof CirclePlay }> = {
  MANUAL_TRIGGER: { eyebrow: "TRIGGER", title: "수동 시작", tone: "bg-blue-50 text-blue-700", icon: CirclePlay },
  SLACK_TRIGGER: { eyebrow: "SLACK · TRIGGER", title: "메시지 수신", tone: "bg-violet-50 text-violet-700", icon: Hash },
  HARNESS: { eyebrow: "AGENTOWN", title: "내 하네스 실행", tone: "bg-orange-50 text-coral", icon: Bot },
  NOTION_ACTION: { eyebrow: "NOTION · ACTION", title: "페이지 생성", tone: "bg-stone-100 text-stone-700", icon: FileText },
  SLACK_ACTION: { eyebrow: "SLACK · ACTION", title: "메시지 전송", tone: "bg-violet-50 text-violet-700", icon: MessageSquare },
};

const emptyDraft: Draft = { name: "새 업무 자동화", nodes: [], edges: [] };

export default function AutomationPage() {
  const harnesses = useQuery({ queryKey: ["harnesses"], queryFn: () => api<Harness[]>("/harnesses") });
  const architect = useMutation({ mutationFn: (instruction: string) => api<AutomationDesignProposal>("/automations/design", { method: "POST", body: JSON.stringify({ instruction }) }) });
  const publishedHarnesses = useMemo(() => harnesses.data?.filter((item) => item.status === "PUBLISHED") ?? [], [harnesses.data]);
  const [instruction, setInstruction] = useState("");
  const [draft, setDraft] = useState<Draft>(emptyDraft);
  const [selectedNodeId, setSelectedNodeId] = useState<string>();
  const [selectedEdgeId, setSelectedEdgeId] = useState<string>();
  const [pendingSource, setPendingSource] = useState<string>();
  const [dragging, setDragging] = useState<{ id: string; offsetX: number; offsetY: number; pointerId: number }>();
  const [notice, setNotice] = useState("노드를 추가하고 출력 포트에서 다음 노드의 입력 포트로 연결하세요.");
  const canvasRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    if (!stored) return;
    try {
      const parsed = JSON.parse(stored) as Draft;
      if (Array.isArray(parsed.nodes) && Array.isArray(parsed.edges)) setDraft(parsed);
    } catch {
      window.localStorage.removeItem(STORAGE_KEY);
    }
  }, []);

  const selectedNode = draft.nodes.find((node) => node.id === selectedNodeId);
  const selectedEdge = draft.edges.find((edge) => edge.id === selectedEdgeId);

  function addNode(kind: NodeKind) {
    const index = draft.nodes.length;
    const node: AutomationNode = {
      id: crypto.randomUUID(), kind,
      x: 40 + (index % 4) * 245,
      y: 55 + Math.floor(index / 4) * 175,
      config: {},
    };
    setDraft((current) => ({ ...current, nodes: [...current.nodes, node] }));
    setSelectedNodeId(node.id);
    setSelectedEdgeId(undefined);
    setNotice(`${kindMeta[kind].title} 노드를 추가했습니다.`);
  }

  function updateNode(config: Partial<AutomationNode["config"]>) {
    if (!selectedNodeId) return;
    setDraft((current) => ({ ...current, nodes: current.nodes.map((node) => node.id === selectedNodeId ? { ...node, config: { ...node.config, ...config } } : node) }));
  }

  function removeSelection() {
    if (selectedNodeId) {
      setDraft((current) => ({ ...current, nodes: current.nodes.filter((node) => node.id !== selectedNodeId), edges: current.edges.filter((edge) => edge.source !== selectedNodeId && edge.target !== selectedNodeId) }));
      setSelectedNodeId(undefined);
      setPendingSource(undefined);
      setNotice("노드와 연결된 엣지를 삭제했습니다.");
      return;
    }
    if (selectedEdgeId) {
      setDraft((current) => ({ ...current, edges: current.edges.filter((edge) => edge.id !== selectedEdgeId) }));
      setSelectedEdgeId(undefined);
      setNotice("엣지를 삭제했습니다.");
    }
  }

  function connect(target: string) {
    if (!pendingSource || pendingSource === target) return;
    const duplicate = draft.edges.some((edge) => edge.source === pendingSource && edge.target === target);
    if (duplicate) {
      setNotice("이미 연결된 노드입니다.");
      setPendingSource(undefined);
      return;
    }
    if (createsCycle(draft.edges, pendingSource, target)) {
      setNotice("순환 연결은 만들 수 없습니다.");
      setPendingSource(undefined);
      return;
    }
    setDraft((current) => ({ ...current, edges: [...current.edges, { id: crypto.randomUUID(), source: pendingSource, target }] }));
    setPendingSource(undefined);
    setNotice("노드를 엣지로 연결했습니다. 엣지를 선택하면 연결을 삭제할 수 있습니다.");
  }

  function validate() {
    const errors: string[] = [];
    const triggers = draft.nodes.filter((node) => node.kind === "MANUAL_TRIGGER" || node.kind === "SLACK_TRIGGER");
    if (triggers.length !== 1) errors.push("시작 노드는 정확히 1개여야 합니다.");
    if (!draft.nodes.some((node) => node.kind === "HARNESS")) errors.push("내 하네스 실행 노드가 필요합니다.");
    for (const node of draft.nodes) {
      const incoming = draft.edges.some((edge) => edge.target === node.id);
      const outgoing = draft.edges.some((edge) => edge.source === node.id);
      const trigger = node.kind === "MANUAL_TRIGGER" || node.kind === "SLACK_TRIGGER";
      if (!trigger && !incoming) errors.push(`${kindMeta[node.kind].title}: 입력 연결이 없습니다.`);
      if (trigger && !outgoing) errors.push(`${kindMeta[node.kind].title}: 출력 연결이 없습니다.`);
      if (node.kind === "HARNESS" && !node.config.harnessId) errors.push("하네스 실행 노드에서 발행 버전을 선택하세요.");
    }
    setNotice(errors.length ? errors.join(" ") : "검증 통과: 실행 순서와 필수 설정이 모두 연결되었습니다.");
  }

  function applyProposal(proposal: AutomationDesignProposal) {
    const idByKey = new Map<string, string>();
    const nodes = proposal.nodes.map((proposed, index) => {
      const id = crypto.randomUUID();
      idByKey.set(proposed.key, id);
      return {
        id,
        kind: proposed.kind,
        x: 40 + (index % 4) * 245,
        y: 55 + Math.floor(index / 4) * 175,
        config: {
          harnessId: proposed.config.harnessId,
          sampleInput: proposed.config.sampleInput,
          channel: proposed.config.channel,
          database: proposed.config.database,
          contentSource: proposed.config.contentSource,
        },
      } satisfies AutomationNode;
    });
    const edges = proposal.edges.flatMap((edge) => {
      const source = idByKey.get(edge.source); const target = idByKey.get(edge.target);
      return source && target ? [{ id: crypto.randomUUID(), source, target }] : [];
    });
    setDraft({ name: proposal.name, nodes, edges });
    setSelectedNodeId(nodes.find((node) => node.kind === "HARNESS")?.id);
    setSelectedEdgeId(undefined);
    setPendingSource(undefined);
    setNotice("분석 에이전트의 제안을 캔버스에 적용했습니다. 외부 앱 설정을 확인한 뒤 연결 검증을 실행하세요.");
  }

  function save() {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(draft));
    setNotice("이 브라우저에 자동화 초안을 저장했습니다. 서버 저장과 실제 Slack·Notion 실행은 다음 개발 단계입니다.");
  }

  function startDrag(event: PointerEvent<HTMLButtonElement>, node: AutomationNode) {
    const bounds = canvasRef.current?.getBoundingClientRect();
    if (!bounds) return;
    canvasRef.current?.setPointerCapture(event.pointerId);
    setDragging({ id: node.id, offsetX: event.clientX - bounds.left - node.x, offsetY: event.clientY - bounds.top - node.y, pointerId: event.pointerId });
    setSelectedNodeId(node.id);
    setSelectedEdgeId(undefined);
  }

  function moveDrag(event: PointerEvent<HTMLDivElement>) {
    if (!dragging) return;
    const bounds = canvasRef.current?.getBoundingClientRect();
    if (!bounds) return;
    const x = clamp(event.clientX - bounds.left - dragging.offsetX, 12, CANVAS_WIDTH - NODE_WIDTH - 12);
    const y = clamp(event.clientY - bounds.top - dragging.offsetY, 12, CANVAS_HEIGHT - NODE_HEIGHT - 12);
    setDraft((current) => ({ ...current, nodes: current.nodes.map((node) => node.id === dragging.id ? { ...node, x, y } : node) }));
  }

  function stopDrag(event: PointerEvent<HTMLDivElement>) {
    if (!dragging) return;
    if (canvasRef.current?.hasPointerCapture(dragging.pointerId)) canvasRef.current.releasePointerCapture(dragging.pointerId);
    setDragging(undefined);
    event.preventDefault();
  }

  return <AppShell kicker="ASSEMBLE · AUTOMATION" title="업무 자동화">
    <section className="mb-5 border border-hairline bg-ink p-6 text-white">
      <div className="flex items-start gap-3"><span className="rounded-full bg-coral p-2"><BrainCircuit className="h-5 w-5" /></span><div><p className="text-xs font-semibold tracking-[.14em] text-coral">AGENTOWN ANALYSIS TEAM</p><h2 className="mt-1 text-xl font-medium">하고 싶은 업무를 자연어로 말해 주세요</h2><p className="mt-2 text-sm leading-6 text-stone-300">내장 분석 에이전트들이 요청을 나눠 보고, 발행된 내 하네스와 연결 노드로 구현안을 제안합니다.</p></div></div>
      <form className="mt-5 flex flex-col gap-3 lg:flex-row" onSubmit={(event) => { event.preventDefault(); if (instruction.trim()) architect.mutate(instruction); }}>
        <textarea aria-label="자동화 요청" value={instruction} onChange={(event) => setInstruction(event.target.value)} maxLength={2000} rows={3} placeholder="예: 슬랙에 고객 문의가 오면 내 고객응대 하네스로 답변을 만들고 Notion에 기록해줘" className="min-h-24 flex-1 resize-none border border-stone-600 bg-white px-4 py-3 text-sm leading-6 text-ink outline-none placeholder:text-stone-400 focus:border-coral" />
        <button type="submit" disabled={!instruction.trim() || architect.isPending} className="flex min-w-40 items-center justify-center gap-2 rounded-pill bg-coral px-6 py-3 font-medium text-white disabled:opacity-50"><Send className="h-4 w-4" />{architect.isPending ? "분석 중…" : "분석 요청"}</button>
      </form>
      {architect.error && <p className="mt-3 bg-red-950/50 p-3 text-sm text-red-200">{architect.error.message}</p>}
    </section>

    {architect.data && <section data-testid="automation-analysis" className="mb-5 border border-hairline bg-white p-6">
      <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between"><div><div className="flex items-center gap-2 text-coral"><Sparkles className="h-4 w-4" /><span className="text-xs font-semibold tracking-[.12em]">분석 에이전트 팀 응답</span></div><p className="mt-3 max-w-4xl text-base leading-7 text-ink">{architect.data.reply}</p></div><button type="button" onClick={() => applyProposal(architect.data)} className="shrink-0 rounded-pill bg-ink px-6 py-3 text-sm font-medium text-white">캔버스에 적용</button></div>
      <div className="mt-5 grid gap-3 md:grid-cols-2 xl:grid-cols-4">{architect.data.analysis.map((item) => <article key={item.agentKey} className="border border-hairline bg-cloud p-4"><p className="text-sm font-medium text-ink">{item.agentName}</p><p className="mt-2 text-xs leading-5 text-charcoal">{item.summary}</p><ul className="mt-3 space-y-1 text-xs text-mute">{item.findings.map((finding) => <li key={finding}>· {finding}</li>)}</ul></article>)}</div>
      <div className="mt-4 flex flex-wrap items-center gap-2 text-xs"><span className="font-medium text-ink">제안 흐름</span>{architect.data.nodes.map((node, index) => <span key={node.key} className="flex items-center gap-2"><span className="border border-hairline px-3 py-2">{node.label}</span>{index < architect.data.nodes.length - 1 && <ChevronRight className="h-4 w-4 text-mute" />}</span>)}</div>
      {architect.data.warnings.length > 0 && <div className="mt-4 border border-amber-200 bg-amber-50 p-3 text-xs leading-5 text-amber-900"><b>실행 전 확인</b>{architect.data.warnings.map((warning) => <p key={warning}>· {warning}</p>)}</div>}
    </section>}
    <div className="mb-5 flex flex-wrap items-center justify-between gap-4 border border-hairline bg-white px-5 py-4">
      <div>
        <input aria-label="자동화 이름" value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} className="w-full max-w-md border-0 bg-transparent text-lg font-medium text-ink outline-none" />
        <p className="mt-1 text-sm text-mute">내 하네스를 Slack·Notion과 엣지로 연결하는 캔버스 MVP</p>
      </div>
      <div className="flex gap-2">
        <button type="button" onClick={validate} className="flex items-center gap-2 rounded-pill border border-hairline px-5 py-2.5 text-sm font-medium"><Check className="h-4 w-4" />연결 검증</button>
        <button type="button" onClick={save} className="flex items-center gap-2 rounded-pill bg-ink px-5 py-2.5 text-sm font-medium text-white"><Save className="h-4 w-4" />초안 저장</button>
      </div>
    </div>

    <div className="grid gap-4 xl:grid-cols-[230px_minmax(0,1fr)_300px]">
      <aside className="border border-hairline bg-white p-4">
        <div className="mb-4 flex items-center gap-2"><Plus className="h-4 w-4" /><h2 className="text-sm font-medium">노드 추가</h2></div>
        <div className="space-y-2">
          {nodeCatalog.map((item) => <button type="button" key={item.kind} data-testid={`add-${item.kind.toLowerCase()}`} onClick={() => addNode(item.kind)} className="w-full border border-hairline p-3 text-left transition hover:border-ink">
            <span className="block text-sm font-medium text-ink">{item.label}</span>
            <span className="mt-1 block text-xs leading-5 text-mute">{item.detail}</span>
          </button>)}
        </div>
        <div className="mt-5 border-t border-hairline pt-4 text-xs leading-5 text-mute">
          <p className="font-medium text-ink">연결 방법</p>
          <p className="mt-1">노드 오른쪽 ●을 누르고 다음 노드 왼쪽 ●을 누르세요.</p>
        </div>
      </aside>

      <div className="min-w-0 overflow-auto border border-hairline bg-[#f6f6f3]">
        <div ref={canvasRef} aria-label="업무 자동화 캔버스" onPointerMove={moveDrag} onPointerUp={stopDrag} onPointerCancel={stopDrag}
          className="relative touch-none overflow-hidden" style={{ width: CANVAS_WIDTH, height: CANVAS_HEIGHT, backgroundImage: "radial-gradient(#d4d4d0 1px, transparent 1px)", backgroundSize: "20px 20px" }}>
          <svg aria-hidden="true" className="pointer-events-none absolute inset-0 h-full w-full">
            {draft.edges.map((edge) => {
              const source = draft.nodes.find((node) => node.id === edge.source);
              const target = draft.nodes.find((node) => node.id === edge.target);
              if (!source || !target) return null;
              const startX = source.x + NODE_WIDTH; const startY = source.y + 66;
              const endX = target.x; const endY = target.y + 66;
              const curve = Math.max(70, Math.abs(endX - startX) * .45);
              return <path key={edge.id} d={`M ${startX} ${startY} C ${startX + curve} ${startY}, ${endX - curve} ${endY}, ${endX} ${endY}`} fill="none" stroke={selectedEdgeId === edge.id ? "#ff5c35" : "#202020"} strokeWidth={selectedEdgeId === edge.id ? 4 : 2} />;
            })}
          </svg>
          {draft.edges.map((edge) => {
            const source = draft.nodes.find((node) => node.id === edge.source);
            const target = draft.nodes.find((node) => node.id === edge.target);
            if (!source || !target) return null;
            return <button type="button" aria-label={`${kindMeta[source.kind].title}에서 ${kindMeta[target.kind].title} 연결 선택`} key={`${edge.id}-hit`} onClick={() => { setSelectedEdgeId(edge.id); setSelectedNodeId(undefined); }}
              className="absolute z-10 h-8 w-8 -translate-x-1/2 -translate-y-1/2 rounded-full border border-hairline bg-white text-xs" style={{ left: (source.x + NODE_WIDTH + target.x) / 2, top: (source.y + target.y) / 2 + 66 }}><ChevronRight className="mx-auto h-4 w-4" /></button>;
          })}
          {draft.nodes.map((node) => <CanvasNode key={node.id} node={node} selected={selectedNodeId === node.id} pending={pendingSource === node.id} harness={publishedHarnesses.find((item) => item.id === node.config.harnessId)}
            onSelect={() => { setSelectedNodeId(node.id); setSelectedEdgeId(undefined); }} onStartDrag={(event) => startDrag(event, node)} onOutput={() => { setPendingSource(node.id); setSelectedNodeId(node.id); setNotice("연결할 다음 노드의 왼쪽 입력 포트를 누르세요."); }} onInput={() => connect(node.id)} />)}
          {draft.nodes.length === 0 && <div className="absolute inset-0 flex items-center justify-center text-center"><div><Workflow className="mx-auto h-10 w-10 text-mute" /><p className="mt-4 font-medium text-ink">왼쪽에서 시작 노드를 추가하세요.</p><p className="mt-2 text-sm text-mute">하네스와 외부 앱을 연결해 업무 흐름을 만듭니다.</p></div></div>}
        </div>
      </div>

      <aside className="border border-hairline bg-white p-5">
        <div className="flex items-center justify-between"><h2 className="text-sm font-medium">설정</h2>{(selectedNode || selectedEdge) && <button type="button" onClick={removeSelection} aria-label="선택 삭제" className="p-2 text-sale"><Trash2 className="h-4 w-4" /></button>}</div>
        {!selectedNode && !selectedEdge && <div className="mt-8 text-center text-sm leading-6 text-mute"><Cable className="mx-auto mb-3 h-7 w-7" />노드나 엣지를 선택하면<br />세부 설정이 표시됩니다.</div>}
        {selectedEdge && <div className="mt-6"><p className="text-sm font-medium text-ink">데이터 전달 엣지</p><p className="mt-2 text-sm leading-6 text-mute">앞 노드의 전체 출력을 다음 노드 입력으로 전달합니다. 필드별 매핑은 다음 단계에서 지원합니다.</p></div>}
        {selectedNode && <NodeSettings node={selectedNode} harnesses={publishedHarnesses} update={updateNode} />}
      </aside>
    </div>

    <div role="status" className={`mt-4 border px-5 py-4 text-sm ${notice.startsWith("검증 통과") ? "border-leaf bg-green-50 text-green-800" : "border-hairline bg-white text-charcoal"}`}>
      <span className="font-medium">캔버스 상태</span><span className="mx-2 text-mute">·</span>{notice}
    </div>
  </AppShell>;
}

function CanvasNode({ node, selected, pending, harness, onSelect, onStartDrag, onOutput, onInput }: { node: AutomationNode; selected: boolean; pending: boolean; harness?: Harness; onSelect: () => void; onStartDrag: (event: PointerEvent<HTMLButtonElement>) => void; onOutput: () => void; onInput: () => void }) {
  const meta = kindMeta[node.kind]; const Icon = meta.icon;
  const detail = node.kind === "HARNESS" ? (harness?.name ?? "발행 하네스 선택 필요") : node.kind.includes("SLACK") ? (node.config.channel || "Slack 연결 설정 필요") : node.kind === "NOTION_ACTION" ? (node.config.database || "Notion DB 설정 필요") : (node.config.sampleInput || "실행 시 입력") ;
  return <article data-testid={`node-${node.kind.toLowerCase()}`} onClick={onSelect} className={`absolute z-20 border bg-white shadow-sm ${selected ? "border-coral ring-2 ring-coral/20" : "border-hairline"}`} style={{ left: node.x, top: node.y, width: NODE_WIDTH, height: NODE_HEIGHT }}>
    <button type="button" onPointerDown={onStartDrag} aria-label={`${meta.title} 노드 이동`} className="flex w-full cursor-grab items-center justify-between border-b border-hairline px-3 py-2 active:cursor-grabbing"><span className="flex items-center gap-2"><GripVertical className="h-4 w-4 text-mute" /><span className="text-[10px] font-semibold tracking-[.14em] text-mute">{meta.eyebrow}</span></span><span className={`rounded-pill px-2 py-1 text-[10px] ${meta.tone}`}>{pending ? "연결 중" : "준비"}</span></button>
    <div className="flex items-start gap-3 px-4 py-3"><span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-full ${meta.tone}`}><Icon className="h-4 w-4" /></span><span className="min-w-0"><b className="block text-sm font-medium text-ink">{meta.title}</b><small className="mt-1 block truncate text-xs text-mute">{detail}</small></span></div>
    <button type="button" aria-label={`${meta.title} 입력 포트`} onClick={(event) => { event.stopPropagation(); onInput(); }} className="absolute -left-2 top-[58px] h-4 w-4 rounded-full border-2 border-white bg-ink" />
    <button type="button" aria-label={`${meta.title} 출력 포트`} onClick={(event) => { event.stopPropagation(); onOutput(); }} className="absolute -right-2 top-[58px] h-4 w-4 rounded-full border-2 border-white bg-coral" />
  </article>;
}

function NodeSettings({ node, harnesses, update }: { node: AutomationNode; harnesses: Harness[]; update: (config: Partial<AutomationNode["config"]>) => void }) {
  const meta = kindMeta[node.kind];
  return <div className="mt-5 space-y-5">
    <div><p className="text-[10px] font-semibold tracking-[.14em] text-mute">{meta.eyebrow}</p><p className="mt-1 text-lg font-medium text-ink">{meta.title}</p></div>
    {node.kind === "HARNESS" && <><Field label="발행된 내 하네스"><select aria-label="발행된 내 하네스" value={node.config.harnessId ?? ""} onChange={(event) => update({ harnessId: event.target.value })}><option value="">하네스 선택</option>{harnesses.map((harness) => <option key={harness.id} value={harness.id}>{harness.name}</option>)}</select></Field>{harnesses.length === 0 && <small className="block text-sale">먼저 하네스를 검증하고 발행해 주세요.</small>}</>}
    {node.kind === "MANUAL_TRIGGER" && <Field label="테스트 입력"><textarea aria-label="테스트 입력" rows={4} value={node.config.sampleInput ?? ""} onChange={(event) => update({ sampleInput: event.target.value })} placeholder="예: 이번 주 제품 업데이트를 요약해 줘" /></Field>}
    {(node.kind === "SLACK_TRIGGER" || node.kind === "SLACK_ACTION") && <><ConnectionNotice service="Slack" /><Field label="채널"><input aria-label="Slack 채널" value={node.config.channel ?? ""} onChange={(event) => update({ channel: event.target.value })} placeholder="#content-team" /></Field></>}
    {node.kind === "NOTION_ACTION" && <><ConnectionNotice service="Notion" /><Field label="데이터베이스"><input aria-label="Notion 데이터베이스" value={node.config.database ?? ""} onChange={(event) => update({ database: event.target.value })} placeholder="콘텐츠 운영 DB" /></Field><Field label="페이지 내용"><select aria-label="Notion 페이지 내용" value={node.config.contentSource ?? "previous.output"} onChange={(event) => update({ contentSource: event.target.value })}><option value="previous.output">이전 노드 전체 출력</option><option value="harness.result">하네스 최종 결과</option></select></Field></>}
  </div>;
}

function ConnectionNotice({ service }: { service: string }) {
  return <div className="border border-amber-200 bg-amber-50 p-3 text-xs leading-5 text-amber-900"><span className="flex items-center gap-2 font-medium"><Unplug className="h-4 w-4" />{service} 계정 연결 전</span><p className="mt-1">이번 MVP에서는 캔버스 구성을 검증합니다. OAuth와 실제 외부 전송은 실행하지 않습니다.</p></div>;
}

function Field({ label, children }: { label: string; children: React.ReactElement }) {
  return <label className="block text-sm font-medium text-ink">{label}<span className="mt-2 block [&>*]:w-full [&>*]:border [&>*]:border-hairline [&>*]:bg-white [&>*]:px-3 [&>*]:py-2.5 [&>*]:text-sm [&>*]:outline-none [&>*]:focus:border-ink">{children}</span></label>;
}

function clamp(value: number, min: number, max: number) { return Math.min(Math.max(value, min), max); }

function createsCycle(edges: AutomationEdge[], source: string, target: string) {
  const graph = new Map<string, string[]>();
  for (const edge of edges) graph.set(edge.source, [...(graph.get(edge.source) ?? []), edge.target]);
  graph.set(source, [...(graph.get(source) ?? []), target]);
  const stack = [target]; const visited = new Set<string>();
  while (stack.length) {
    const current = stack.pop()!;
    if (current === source) return true;
    if (visited.has(current)) continue;
    visited.add(current);
    stack.push(...(graph.get(current) ?? []));
  }
  return false;
}
