"use client";

import { useEffect, useRef } from "react";
import { AgentVisualStatus } from "@/components/AgentCharacter";
import { spriteRects, PALETTE } from "@/components/pixel/sprites";

export type PixelAgent = { id: string; name: string; role: string; characterKey: string };
export type PixelItem = { id: string; assetKey: string; x: number; y: number; width: number; height: number; zIndex: number; rotation: number };

/* 좌표는 모두 0~1 정규화 — 기존 저장 포맷을 그대로 씁니다. */
const VIEW = { w: 384, h: 240 };          // 내부 픽셀 해상도 (CSS로 확대)
const FLOOR_TOP = 0.28;                    // 이 아래가 바닥(걸을 수 있는 영역)
const WALK_SPEED = 0.16;                   // 초당 이동 비율

type Dir = "down" | "up" | "left" | "right";

type Runtime = {
  x: number; y: number;          // 현재 위치 (0~1)
  tx: number; ty: number;        // 목표 위치
  dir: Dir;
  phase: number;                 // 애니메이션 위상
  sitting: boolean;
  home: { x: number; y: number };   // 자리(책상)
  nextWander: number;
};

const skins: Record<string, { hair: string; shirt: string; skin: string; pants: string }> = {
  writer:   { hair: "#51372f", shirt: "#c8503f", skin: "#f0c19a", pants: "#3d4a5c" },
  reviewer: { hair: "#2f343c", shirt: "#4f7cac", skin: "#e0b189", pants: "#33404f" },
  designer: { hair: "#7b4158", shirt: "#8f6bc0", skin: "#efbd96", pants: "#3a3550" },
  developer:{ hair: "#272c33", shirt: "#3f9a92", skin: "#d6a179", pants: "#2f4048" },
  manager:  { hair: "#5f3b2f", shirt: "#e3b23c", skin: "#eebb94", pants: "#4a3b2c" },
};
const skinFor = (key: string) => skins[key] ?? skins.manager;

export function PixelOffice({
  agents, statuses = {}, items = [], backgroundKey = "office-warm",
  editable = false, selectedItemId, onSelectItem, onMoveItem, onAgentClick, className = "",
}: {
  agents: PixelAgent[];
  statuses?: Record<string, AgentVisualStatus>;
  items?: PixelItem[];
  backgroundKey?: string;
  editable?: boolean;
  selectedItemId?: string | null;
  onSelectItem?: (id: string | null) => void;
  onMoveItem?: (id: string, x: number, y: number) => void;
  onAgentClick?: (id: string) => void;
  className?: string;
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const runtime = useRef<Map<string, Runtime>>(new Map());
  const dragging = useRef<string | null>(null);
  // 렌더 루프가 항상 최신 props를 보도록 ref에 동기화합니다.
  const props = useRef({ agents, statuses, items, backgroundKey, editable, selectedItemId });
  useEffect(() => {
    props.current = { agents, statuses, items, backgroundKey, editable, selectedItemId };
  }, [agents, statuses, items, backgroundKey, editable, selectedItemId]);

  useEffect(() => {
    const canvas = canvasRef.current!;
    const ctx = canvas.getContext("2d")!;
    ctx.imageSmoothingEnabled = false;
    let raf = 0;
    let last = performance.now();

    function tick(now: number) {
      const dt = Math.min(0.05, (now - last) / 1000);
      last = now;
      const { agents, statuses, items, backgroundKey, editable, selectedItemId } = props.current;

      // --- 상태에 따른 행동 갱신 ---
      agents.forEach((agent, index) => {
        let unit = runtime.current.get(agent.id);
        if (!unit) {
          const home = deskSlot(index);
          unit = { x: home.x, y: home.y + 0.10, tx: home.x, ty: home.y + 0.10, dir: "down", phase: 0, sitting: false, home, nextWander: now + 2000 + Math.random() * 4000 };
          runtime.current.set(agent.id, unit);
        }
        const status = statuses[agent.id] ?? "IDLE";
        const busy = status === "WORKING" || status === "THINKING" || status === "TOOL";

        if (busy) {
          // 일할 때는 자기 자리로 가서 앉습니다.
          unit.tx = unit.home.x; unit.ty = unit.home.y + 0.06;
        } else if (status === "WAITING") {
          unit.tx = 0.5; unit.ty = 0.78;            // 승인 대기 → 회의 테이블 쪽으로
        } else if (now > unit.nextWander && !dragging.current) {
          unit.tx = clamp(unit.home.x + (Math.random() - 0.5) * 0.34, 0.06, 0.94);
          unit.ty = clamp(unit.home.y + (Math.random() - 0.5) * 0.28, FLOOR_TOP + 0.04, 0.94);
          unit.nextWander = now + 4000 + Math.random() * 6000;
        }

        const dx = unit.tx - unit.x, dy = unit.ty - unit.y;
        const dist = Math.hypot(dx, dy);
        if (dist > 0.004) {
          const step = Math.min(dist, WALK_SPEED * dt);
          unit.x += (dx / dist) * step;
          unit.y += (dy / dist) * step;
          unit.dir = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? "right" : "left") : (dy > 0 ? "down" : "up");
          unit.phase += dt * 8;
          unit.sitting = false;
        } else {
          unit.sitting = busy;
          unit.phase += dt * (busy ? 6 : 2);
        }
      });

      // --- 그리기 ---
      drawRoom(ctx, backgroundKey);
      const drawables: { z: number; draw: () => void }[] = [];

      items.forEach((item) => drawables.push({
        z: item.y,
        draw: () => {
          const w = item.width * VIEW.w, h = item.height * VIEW.h;
          const x = item.x * VIEW.w - w / 2, y = item.y * VIEW.h - h;
          drawSprite(ctx, item.assetKey, x, y, w, h);
          if (editable && selectedItemId === item.id) {
            ctx.strokeStyle = "#111"; ctx.lineWidth = 1;
            ctx.strokeRect(Math.round(x) - 1.5, Math.round(y) - 1.5, Math.round(w) + 3, Math.round(h) + 3);
          }
        },
      }));

      agents.forEach((agent) => {
        const unit = runtime.current.get(agent.id)!;
        const status = props.current.statuses[agent.id] ?? "IDLE";
        drawables.push({ z: unit.y, draw: () => drawAgent(ctx, agent, unit, status) });
      });

      drawables.sort((a, b) => a.z - b.z).forEach((entry) => entry.draw());
      raf = requestAnimationFrame(tick);
    }

    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, []);

  function toLocal(event: React.PointerEvent<HTMLCanvasElement>) {
    const rect = canvasRef.current!.getBoundingClientRect();
    return { x: (event.clientX - rect.left) / rect.width, y: (event.clientY - rect.top) / rect.height };
  }
  function hitAgent(point: { x: number; y: number }) {
    return agents.find((agent) => {
      const unit = runtime.current.get(agent.id);
      if (!unit) return false;
      return Math.abs(point.x - unit.x) < 0.035 && point.y < unit.y + 0.02 && point.y > unit.y - 0.13;
    });
  }
  function hitItem(point: { x: number; y: number }) {
    return [...items].reverse().find((item) =>
      point.x > item.x - item.width / 2 && point.x < item.x + item.width / 2 &&
      point.y < item.y && point.y > item.y - item.height);
  }

  function onPointerDown(event: React.PointerEvent<HTMLCanvasElement>) {
    const point = toLocal(event);
    if (editable) {
      const item = hitItem(point);
      onSelectItem?.(item?.id ?? null);
      if (item) { dragging.current = item.id; event.currentTarget.setPointerCapture(event.pointerId); }
      return;
    }
    const agent = hitAgent(point);
    if (agent) { onAgentClick?.(agent.id); return; }
    // 빈 바닥을 누르면 가장 가까운 직원이 그 자리로 걸어갑니다.
    if (point.y > FLOOR_TOP) {
      let nearest: string | null = null, best = Infinity;
      runtime.current.forEach((unit, id) => {
        const d = Math.hypot(unit.x - point.x, unit.y - point.y);
        if (d < best) { best = d; nearest = id; }
      });
      const unit = nearest ? runtime.current.get(nearest) : undefined;
      if (unit) { unit.tx = clamp(point.x, .04, .96); unit.ty = clamp(point.y, FLOOR_TOP + .02, .96); unit.nextWander = performance.now() + 8000; }
    }
  }
  function onPointerMove(event: React.PointerEvent<HTMLCanvasElement>) {
    if (!dragging.current || !editable) return;
    const point = toLocal(event);
    onMoveItem?.(dragging.current, clamp(point.x, .04, .96), clamp(point.y, FLOOR_TOP, .99));
  }
  function onPointerUp(event: React.PointerEvent<HTMLCanvasElement>) {
    dragging.current = null;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
  }

  return <canvas
    ref={canvasRef} width={VIEW.w} height={VIEW.h}
    className={`pixel-office ${editable ? "pixel-office--editable" : ""} ${className}`}
    onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp}
    role="img" aria-label="회사 오피스"
  />;
}

function clamp(value: number, min: number, max: number) { return Math.min(max, Math.max(min, value)); }

const themes: Record<string, { wall: string; wallTrim: string; floor: string; floorAlt: string; window: string }> = {
  "office-warm":   { wall: "#efe6d6", wallTrim: "#c9b596", floor: "#c9975f", floorAlt: "#bd8b55", window: "#8fd0e8" },
  "office-green":  { wall: "#e6efe4", wallTrim: "#a8c3a4", floor: "#b09468", window: "#9fd8c4", floorAlt: "#a4885d" },
  "office-night":  { wall: "#33414f", wallTrim: "#22303c", floor: "#6f5a44", floorAlt: "#65503c", window: "#24405f" },
  "office-mono":   { wall: "#ececec", wallTrim: "#cacaca", floor: "#d2d2d2", floorAlt: "#c6c6c6", window: "#dcdcdc" },
  "office-sunset": { wall: "#ffe4cd", wallTrim: "#dda87f", floor: "#c98f6a", floorAlt: "#bd825e", window: "#ffb27a" },
  "office-mint":   { wall: "#e2f5ef", wallTrim: "#a3cec1", floor: "#a8cfc2", floorAlt: "#9cc3b6", window: "#9fdcd0" },
  "office-sakura": { wall: "#fce6ec", wallTrim: "#dfa8ba", floor: "#e2b3c1", floorAlt: "#d6a7b5", window: "#ffc3d6" },
};

function drawRoom(ctx: CanvasRenderingContext2D, backgroundKey: string) {
  const theme = themes[backgroundKey] ?? themes["office-warm"];
  const wallH = Math.round(VIEW.h * FLOOR_TOP);

  ctx.fillStyle = theme.wall;
  ctx.fillRect(0, 0, VIEW.w, wallH);
  // 창문
  for (let i = 0; i < 3; i++) {
    const x = 40 + i * 110;
    ctx.fillStyle = "#f8f8f6"; ctx.fillRect(x, 14, 76, 38);
    ctx.fillStyle = theme.window; ctx.fillRect(x + 4, 18, 68, 30);
    ctx.fillStyle = "#f8f8f6"; ctx.fillRect(x + 36, 18, 4, 30);
  }
  // 걸레받이
  ctx.fillStyle = theme.wallTrim;
  ctx.fillRect(0, wallH - 6, VIEW.w, 6);

  // 바닥 타일
  const tile = 16;
  for (let y = wallH; y < VIEW.h; y += tile) {
    for (let x = 0; x < VIEW.w; x += tile) {
      const alt = ((x / tile) + (y / tile)) % 2 === 0;
      ctx.fillStyle = alt ? theme.floor : theme.floorAlt;
      ctx.fillRect(x, y, tile, tile);
    }
  }
  ctx.fillStyle = "rgba(0,0,0,.05)";
  for (let x = 0; x < VIEW.w; x += tile) ctx.fillRect(x, wallH, 1, VIEW.h - wallH);
}

function drawSprite(ctx: CanvasRenderingContext2D, key: string, x: number, y: number, w: number, h: number) {
  const rects = spriteRects[key];
  if (!rects) return;
  rects.forEach(([rx, ry, rw, rh, color]) => {
    ctx.fillStyle = color;
    ctx.fillRect(Math.round(x + rx * w), Math.round(y + ry * h), Math.max(1, Math.round(rw * w)), Math.max(1, Math.round(rh * h)));
  });
}

/* 책상 자리 — 레퍼런스처럼 두 줄로 배치 */
function deskSlot(index: number) {
  const cols = 4;
  const col = index % cols, row = Math.floor(index / cols) % 2;
  return { x: 0.16 + col * 0.23, y: 0.50 + row * 0.26 };
}

function drawAgent(ctx: CanvasRenderingContext2D, agent: PixelAgent, unit: Runtime, status: AgentVisualStatus) {
  const skin = skinFor(agent.characterKey);
  const px = Math.round(unit.x * VIEW.w);
  const py = Math.round(unit.y * VIEW.h);
  const walking = !unit.sitting && (Math.abs(unit.tx - unit.x) > 0.004 || Math.abs(unit.ty - unit.y) > 0.004);
  const bob = walking && Math.floor(unit.phase) % 2 === 0 ? 1 : 0;
  const sit = unit.sitting ? 2 : 0;

  // 그림자
  ctx.fillStyle = "rgba(0,0,0,.18)";
  ctx.fillRect(px - 5, py - 1, 10, 3);

  const top = py - 22 + bob + sit;
  const P = (x: number, y: number, w: number, h: number, c: string) => { ctx.fillStyle = c; ctx.fillRect(px + x, top + y, w, h); };

  // 다리
  if (!unit.sitting) {
    const swing = walking ? (Math.floor(unit.phase) % 2 === 0 ? 1 : -1) : 0;
    P(-4, 17, 3, 5 - Math.abs(swing), skin.pants);
    P(1, 17, 3, 5 - Math.abs(swing ? -swing : 0), skin.pants);
  } else {
    P(-4, 17, 8, 3, skin.pants);
  }
  // 몸통
  P(-5, 9, 10, 9, skin.shirt);
  // 팔 (일할 때 타이핑)
  const typing = unit.sitting && Math.floor(unit.phase) % 2 === 0;
  if (unit.dir === "left") P(-7, 10, 2, 6, skin.shirt);
  else if (unit.dir === "right") P(5, 10, 2, 6, skin.shirt);
  else { P(-7, 10 + (typing ? 1 : 0), 2, 6, skin.shirt); P(5, 10 + (typing ? 0 : 1), 2, 6, skin.shirt); }
  // 머리
  P(-4, 1, 8, 8, skin.skin);
  // 머리카락
  P(-5, 0, 10, 3, skin.hair);
  if (unit.dir !== "up") { P(-5, 0, 2, 5, skin.hair); P(3, 0, 2, 5, skin.hair); }
  // 눈
  if (unit.dir === "down") { P(-3, 5, 1, 2, "#2b2b30"); P(2, 5, 1, 2, "#2b2b30"); }
  else if (unit.dir === "left") P(-3, 5, 1, 2, "#2b2b30");
  else if (unit.dir === "right") P(2, 5, 1, 2, "#2b2b30");

  drawBubble(ctx, px, top, status);

  // 이름표
  ctx.font = "6px monospace";
  ctx.textAlign = "center";
  const label = agent.name.slice(0, 8);
  const w = ctx.measureText(label).width + 6;
  ctx.fillStyle = "rgba(255,255,255,.92)";
  ctx.fillRect(px - w / 2, py + 3, w, 9);
  ctx.fillStyle = PALETTE.black;
  ctx.fillText(label, px, py + 10);
}

function drawBubble(ctx: CanvasRenderingContext2D, px: number, top: number, status: AgentVisualStatus) {
  const marks: Partial<Record<AgentVisualStatus, [string, string]>> = {
    THINKING: ["?", "#4f7cac"], TOOL: ["+", "#3f9a92"], SUCCESS: ["V", "#4f9a5e"],
    WAITING: ["!", "#e3b23c"], FAILED: ["X", "#c8503f"],
  };
  const mark = marks[status];
  if (!mark) return;
  ctx.fillStyle = "#fff";
  ctx.fillRect(px + 3, top - 11, 11, 10);
  ctx.fillStyle = mark[1];
  ctx.font = "bold 8px monospace";
  ctx.textAlign = "center";
  ctx.fillText(mark[0], px + 8, top - 3);
}
