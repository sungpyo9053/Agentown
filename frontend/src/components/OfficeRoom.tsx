"use client";

import { AgentCharacter, AgentVisualStatus } from "@/components/AgentCharacter";

export type OfficeAgent = { id: string; name: string; role: string; characterKey: string };
export type OfficeRoomItem = { agentId?: string; itemType: "AGENT" | "ASSET"; positionX: number; positionY: number; width: number; height: number; zIndex: number; rotation: number };

function defaultPosition(index: number) {
  const slots = [
    { x: .19, y: .55 }, { x: .42, y: .62 }, { x: .67, y: .54 },
    { x: .81, y: .70 }, { x: .31, y: .77 },
  ];
  return slots[index % slots.length];
}

export function resolveAgentPosition(agentId: string, index: number, items: OfficeRoomItem[]) {
  const saved = items.find((item) => item.itemType === "AGENT" && item.agentId === agentId);
  return saved ? { x: Number(saved.positionX), y: Number(saved.positionY) } : defaultPosition(index);
}

export function OfficeRoom({ title, agents, items = [], statuses = {}, positionOverrides = {}, backgroundKey = "office-warm", editable = false, selectedAgentId, onAgentPointerDown, onAgentPointerMove, onAgentPointerUp, onAgentClick, roomRef }: {
  title: string;
  agents: OfficeAgent[];
  items?: OfficeRoomItem[];
  statuses?: Record<string, AgentVisualStatus>;
  positionOverrides?: Record<string, { x: number; y: number }>;
  backgroundKey?: string;
  editable?: boolean;
  selectedAgentId?: string | null;
  onAgentPointerDown?: (event: React.PointerEvent<HTMLButtonElement>, agentId: string) => void;
  onAgentPointerMove?: (event: React.PointerEvent<HTMLButtonElement>, agentId: string) => void;
  onAgentPointerUp?: (event: React.PointerEvent<HTMLButtonElement>, agentId: string) => void;
  onAgentClick?: (agentId: string) => void;
  roomRef?: React.RefObject<HTMLDivElement | null>;
}) {
  return <div ref={roomRef} className={`office-room office-room--${backgroundKey} ${editable ? "office-room--editable" : ""}`}>
    <div className="office-room__wall" aria-hidden="true">
      <div className="office-room__window"><span /><span /><span /></div>
      <div className="office-room__brand"><span>AGENTOWN</span><b>{title}</b></div>
      <div className="office-room__clock"><i /><em /></div>
      <div className="office-room__shelf"><i /><i /><i /><i /></div>
    </div>
    <div className="office-room__floor" aria-hidden="true" />
    <div className="office-room__desk office-room__desk--left" aria-hidden="true"><span className="office-room__monitor" /><span className="office-room__chair" /></div>
    <div className="office-room__desk office-room__desk--right" aria-hidden="true"><span className="office-room__monitor" /><span className="office-room__chair" /></div>
    <div className="office-room__meeting" aria-hidden="true"><span /><i /><i /><i /></div>
    <div className="office-room__plant office-room__plant--left" aria-hidden="true"><i /><i /><i /><span /></div>
    <div className="office-room__plant office-room__plant--right" aria-hidden="true"><i /><i /><i /><span /></div>
    <div className="office-room__sofa" aria-hidden="true"><span /><i /></div>

    {agents.map((agent, index) => {
      const position = positionOverrides[agent.id] ?? resolveAgentPosition(agent.id, index, items);
      const status = statuses[agent.id] ?? "IDLE";
      return <button
        type="button"
        key={agent.id}
        className={`office-agent ${editable ? "office-agent--draggable" : ""} ${selectedAgentId === agent.id ? "office-agent--selected" : ""}`}
        style={{ left: `${position.x * 100}%`, top: `${position.y * 100}%`, zIndex: 20 + index }}
        onPointerDown={(event) => onAgentPointerDown?.(event, agent.id)}
        onPointerMove={(event) => onAgentPointerMove?.(event, agent.id)}
        onPointerUp={(event) => onAgentPointerUp?.(event, agent.id)}
        onClick={() => onAgentClick?.(agent.id)}
        aria-label={`${agent.name} ${agent.role}${editable ? " 위치 이동" : ""}`}
      >
        <span className="office-agent__status">{statusLabel(status)}</span>
        <AgentCharacter characterKey={agent.characterKey} status={status} className="office-agent__character" />
        <span className="office-agent__name"><b>{agent.name}</b><small>{agent.role}</small></span>
      </button>;
    })}
  </div>;
}

function statusLabel(status: AgentVisualStatus) {
  return ({ IDLE: "대기", WORKING: "작업 중", THINKING: "생각 중", TOOL: "도구 사용", SUCCESS: "완료", WAITING: "승인 대기", FAILED: "실패" } as const)[status];
}
