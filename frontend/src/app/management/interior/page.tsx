"use client";
/* eslint-disable react-hooks/set-state-in-effect */

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FormEvent, PointerEvent, useEffect, useRef, useState } from "react";
import { AppShell, Panel } from "@/components/AppShell";
import { OfficeAgent, OfficeRoom, OfficeRoomItem, PlacedAsset, resolveAgentPosition } from "@/components/OfficeRoom";
import { assetCategories, officeAssets, AssetCategory, assetByKey } from "@/components/OfficeAssets";
import { api } from "@/lib/api";

type Home = { id: string; handle: string; title: string; introduction?: string; backgroundKey: string; visibility: string; items: OfficeRoomItem[] };

const themes = [
  { key: "office-warm", label: "웜 우드" },
  { key: "office-green", label: "그린 스튜디오" },
  { key: "office-night", label: "나이트 랩" },
  { key: "office-mono", label: "모노 톤" },
  { key: "office-sunset", label: "선셋" },
  { key: "office-mint", label: "민트" },
  { key: "office-sakura", label: "사쿠라" },
];

let assetSeq = 0;
const nextId = () => `asset-${Date.now()}-${assetSeq++}`;

export default function Page() {
  const client = useQueryClient();
  const roomRef = useRef<HTMLDivElement>(null);
  const dragAgent = useRef<string | null>(null);
  const dragAsset = useRef<string | null>(null);
  const home = useQuery({ queryKey: ["home"], queryFn: () => api<Home>("/mini-homes/me") });
  const agents = useQuery({ queryKey: ["agents"], queryFn: () => api<OfficeAgent[]>("/agents") });

  const [positions, setPositions] = useState<Record<string, { x: number; y: number }>>({});
  const [placed, setPlaced] = useState<PlacedAsset[]>([]);
  const [selectedAgent, setSelectedAgent] = useState<string | null>(null);
  const [selectedAsset, setSelectedAsset] = useState<string | null>(null);
  const [category, setCategory] = useState<AssetCategory>("furniture");
  const [theme, setTheme] = useState("office-warm");

  useEffect(() => {
    if (!home.data || !agents.data) return;
    setPositions(Object.fromEntries(agents.data.map((agent, index) => [agent.id, resolveAgentPosition(agent.id, index, home.data.items)])));
    setPlaced(home.data.items.filter((item) => item.itemType === "ASSET" && item.assetKey).map((item) => ({
      id: nextId(), assetKey: item.assetKey!, x: Number(item.positionX), y: Number(item.positionY),
      width: Number(item.width), height: Number(item.height), zIndex: item.zIndex, rotation: Number(item.rotation),
    })));
    setTheme(home.data.backgroundKey ?? "office-warm");
  }, [home.data, agents.data]);

  const saveLayout = useMutation({
    mutationFn: () => api<Home>("/mini-homes/me/items", {
      method: "PUT",
      body: JSON.stringify([
        ...(agents.data ?? []).map((agent, index) => ({
          agentId: agent.id, itemType: "AGENT",
          positionX: positions[agent.id]?.x ?? .2, positionY: positions[agent.id]?.y ?? .6,
          width: .13, height: .28, zIndex: 40 + index, rotation: 0,
        })),
        ...placed.map((asset) => ({
          assetKey: asset.assetKey, itemType: "ASSET",
          positionX: asset.x, positionY: asset.y, width: asset.width, height: asset.height,
          zIndex: asset.zIndex, rotation: asset.rotation,
        })),
      ]),
    }),
    onSuccess: (data) => client.setQueryData(["home"], data),
  });
  const saveHome = useMutation({
    mutationFn: (body: unknown) => api<Home>("/mini-homes/me", { method: "PATCH", body: JSON.stringify(body) }),
    onSuccess: (data) => client.setQueryData(["home"], data),
  });

  function clamp(value: number, min: number, max: number) { return Math.min(max, Math.max(min, value)); }
  function pointToRoom(clientX: number, clientY: number) {
    const rect = roomRef.current!.getBoundingClientRect();
    return { x: (clientX - rect.left) / rect.width, y: (clientY - rect.top) / rect.height };
  }

  function moveAgent(event: PointerEvent<HTMLButtonElement>, id: string) {
    if (dragAgent.current !== id || !roomRef.current) return;
    const point = pointToRoom(event.clientX, event.clientY);
    setPositions((current) => ({ ...current, [id]: { x: clamp(point.x, .06, .94), y: clamp(point.y, .48, .91) } }));
  }
  function moveAsset(event: PointerEvent<HTMLButtonElement>, id: string) {
    if (dragAsset.current !== id || !roomRef.current) return;
    const point = pointToRoom(event.clientX, event.clientY);
    setPlaced((current) => current.map((asset) => asset.id === id ? { ...asset, x: clamp(point.x, .04, .96), y: clamp(point.y, .42, .98) } : asset));
  }

  function addAsset(key: string) {
    const spec = assetByKey.get(key)!;
    setPlaced((current) => {
      const next = { id: nextId(), assetKey: key, x: .5, y: .72, width: spec.width, height: spec.height, zIndex: 10 + current.length, rotation: 0 };
      setSelectedAsset(next.id);
      return [...current, next];
    });
  }
  function updateSelected(patch: Partial<PlacedAsset>) {
    setPlaced((current) => current.map((asset) => asset.id === selectedAsset ? { ...asset, ...patch } : asset));
  }
  function removeSelected() {
    setPlaced((current) => current.filter((asset) => asset.id !== selectedAsset));
    setSelectedAsset(null);
  }

  function submitSettings(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    saveHome.mutate({ title: form.get("title"), introduction: form.get("introduction"), backgroundKey: theme, visibility: form.get("visibility") });
  }

  const current = placed.find((asset) => asset.id === selectedAsset) ?? null;

  return <AppShell kicker="MANAGEMENT" title="공간 인테리어">
    <div className="grid gap-2 xl:grid-cols-[1fr_340px]">
      <div className="border border-hairline bg-white">
        <OfficeRoom
          title={home.data?.title ?? "AI OFFICE"} agents={agents.data ?? []} items={home.data?.items ?? []}
          positionOverrides={positions} backgroundKey={theme} editable roomRef={roomRef}
          selectedAgentId={selectedAgent} assets={placed} selectedAssetId={selectedAsset}
          onAgentPointerDown={(e, id) => { dragAgent.current = id; setSelectedAgent(id); setSelectedAsset(null); e.currentTarget.setPointerCapture(e.pointerId); }}
          onAgentPointerMove={moveAgent}
          onAgentPointerUp={(e) => { dragAgent.current = null; if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId); }}
          onAssetPointerDown={(e, id) => { dragAsset.current = id; setSelectedAsset(id); setSelectedAgent(null); e.currentTarget.setPointerCapture(e.pointerId); }}
          onAssetPointerMove={moveAsset}
          onAssetPointerUp={(e) => { dragAsset.current = null; if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId); }}
        />
        <div className="flex flex-wrap items-center justify-between gap-4 border-t border-hairline p-4">
          <p className="text-sm text-mute">소품과 직원을 드래그해 자리를 정하세요. 소품을 누르면 크기·각도를 조절할 수 있어요.</p>
          <button disabled={saveLayout.isPending} onClick={() => saveLayout.mutate()} className="shrink-0 rounded-pill bg-ink px-8 py-4 text-sm font-medium text-white transition active:scale-95 active:opacity-50 disabled:opacity-40">
            {saveLayout.isPending ? "저장 중…" : "배치 저장"}
          </button>
        </div>
        {saveLayout.isSuccess && <p className="border-t border-hairline px-4 py-3 text-sm text-leaf">배치를 저장했습니다.</p>}
        {saveLayout.error && <p className="border-t border-hairline px-4 py-3 text-sm text-sale">{saveLayout.error.message}</p>}
      </div>

      <div className="space-y-2">
        <Panel title="소품 놓기">
          <div className="flex flex-wrap gap-2">
            {assetCategories.map(([key, label]) => <button key={key} type="button" onClick={() => setCategory(key)}
              className={`rounded-pill px-4 py-2 text-sm font-medium transition ${category === key ? "bg-ink text-white" : "border border-hairline text-ink"}`}>{label}</button>)}
          </div>
          <div className="mt-4 grid grid-cols-3 gap-2">
            {officeAssets.filter((asset) => asset.category === category).map((asset) => <button key={asset.key} type="button" onClick={() => addAsset(asset.key)}
              className="border border-hairline p-2 transition hover:border-ink active:scale-95">
              <span className="block h-14 [&>svg]:h-full [&>svg]:w-full">{asset.render()}</span>
              <small className="mt-1 block text-center text-xs font-medium text-mute">{asset.label}</small>
            </button>)}
          </div>
        </Panel>

        {current && <Panel title="선택한 소품" action={<button type="button" onClick={removeSelected} className="text-sm font-medium text-sale underline">삭제</button>}>
          <label className="block text-sm font-medium text-ink">크기
            <input type="range" min={0.03} max={0.4} step={0.005} value={current.width}
              onChange={(e) => { const w = Number(e.target.value); const spec = assetByKey.get(current.assetKey)!; updateSelected({ width: w, height: w * (spec.height / spec.width) }); }}
              className="mt-2 w-full" />
          </label>
          <label className="mt-4 block text-sm font-medium text-ink">각도
            <input type="range" min={-30} max={30} step={1} value={current.rotation} onChange={(e) => updateSelected({ rotation: Number(e.target.value) })} className="mt-2 w-full" />
          </label>
          <div className="mt-4 flex gap-2">
            <button type="button" onClick={() => updateSelected({ zIndex: Math.min(39, current.zIndex + 1) })} className="flex-1 rounded-pill border border-hairline py-2.5 text-sm font-medium">앞으로</button>
            <button type="button" onClick={() => updateSelected({ zIndex: Math.max(1, current.zIndex - 1) })} className="flex-1 rounded-pill border border-hairline py-2.5 text-sm font-medium">뒤로</button>
          </div>
        </Panel>}

        <Panel title="배경 스킨">
          <div className="grid grid-cols-2 gap-2">
            {themes.map((item) => <button key={item.key} type="button" onClick={() => setTheme(item.key)}
              className={`border p-3 text-sm font-medium transition ${theme === item.key ? "border-ink bg-cloud text-ink" : "border-hairline text-mute"}`}>{item.label}</button>)}
          </div>
          <p className="mt-3 text-xs text-mute">스킨은 아래 &lsquo;회사 정보 저장&rsquo;을 눌러야 반영됩니다.</p>
        </Panel>

        <Panel title="회사 정보">
          <form key={home.data?.id} onSubmit={submitSettings} className="space-y-4">
            <label className="block text-sm font-medium text-ink">회사 이름
              <input name="title" defaultValue={home.data?.title} required maxLength={60} className="mt-2 w-full border border-hairline px-4 py-3 outline-none focus:border-ink" />
            </label>
            <label className="block text-sm font-medium text-ink">회사가 하는 일
              <textarea name="introduction" defaultValue={home.data?.introduction} rows={3} className="mt-2 w-full border border-hairline px-4 py-3 outline-none focus:border-ink" />
            </label>
            <label className="block text-sm font-medium text-ink">공개 범위
              <select name="visibility" defaultValue={home.data?.visibility ?? "PUBLIC"} className="mt-2 w-full border border-hairline px-4 py-3 outline-none focus:border-ink">
                <option value="PRIVATE">비공개</option>
                <option value="FRIENDS">일촌 공개</option>
                <option value="PUBLIC">전체 공개</option>
              </select>
            </label>
            <button disabled={saveHome.isPending} className="w-full rounded-pill bg-ink py-4 text-sm font-medium text-white transition active:scale-95 active:opacity-50 disabled:opacity-40">회사 정보 저장</button>
            {saveHome.isSuccess && <p className="text-sm text-leaf">저장했습니다.</p>}
            {saveHome.error && <p className="text-sm text-sale">{saveHome.error.message}</p>}
          </form>
        </Panel>
      </div>
    </div>
  </AppShell>;
}
