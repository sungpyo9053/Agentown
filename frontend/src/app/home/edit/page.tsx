"use client";
/* eslint-disable react-hooks/set-state-in-effect */

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FormEvent, PointerEvent, useEffect, useRef, useState } from "react";
import { AppShell } from "@/components/AppShell";
import { OfficeAgent, OfficeRoom, OfficeRoomItem, resolveAgentPosition } from "@/components/OfficeRoom";
import { api } from "@/lib/api";

type Home = { id:string; handle:string; title:string; introduction?:string; backgroundKey:string; visibility:string; items:OfficeRoomItem[] };

export default function Page() {
  const client = useQueryClient();
  const roomRef = useRef<HTMLDivElement>(null);
  const dragId = useRef<string | null>(null);
  const home = useQuery({queryKey:["home"],queryFn:()=>api<Home>("/mini-homes/me")});
  const agents = useQuery({queryKey:["agents"],queryFn:()=>api<OfficeAgent[]>("/agents")});
  const [positions,setPositions] = useState<Record<string,{x:number;y:number}>>({});
  const [selected,setSelected] = useState<string|null>(null);

  useEffect(()=>{
    if (!home.data || !agents.data) return;
    setPositions(Object.fromEntries(agents.data.map((agent,index)=>[agent.id,resolveAgentPosition(agent.id,index,home.data.items)])));
  },[home.data,agents.data]);

  const saveItems = useMutation({mutationFn:()=>api<Home>("/mini-homes/me/items",{method:"PUT",body:JSON.stringify((agents.data??[]).map((agent,index)=>({agentId:agent.id,itemType:"AGENT",positionX:positions[agent.id]?.x??.2,positionY:positions[agent.id]?.y??.6,width:.13,height:.28,zIndex:index+10,rotation:0})))}),onSuccess:(data)=>client.setQueryData(["home"],data)});
  const saveHome = useMutation({mutationFn:(body:unknown)=>api<Home>("/mini-homes/me",{method:"PATCH",body:JSON.stringify(body)}),onSuccess:(data)=>client.setQueryData(["home"],data)});

  function move(event: PointerEvent<HTMLButtonElement>, id:string) {
    if (dragId.current !== id || !roomRef.current) return;
    const rect=roomRef.current.getBoundingClientRect();
    setPositions(p=>({...p,[id]:{x:Math.min(.94,Math.max(.06,(event.clientX-rect.left)/rect.width)),y:Math.min(.91,Math.max(.48,(event.clientY-rect.top)/rect.height))}}));
  }
  function submit(event:FormEvent<HTMLFormElement>){event.preventDefault();const form=new FormData(event.currentTarget);saveHome.mutate({title:form.get("title"),introduction:form.get("introduction"),backgroundKey:form.get("backgroundKey"),visibility:form.get("visibility")});}

  return <AppShell kicker="OFFICE EDITOR" title="우리 회사 꾸미기">
    <div className="grid gap-6 xl:grid-cols-[1fr_320px]">
      <div className="overflow-hidden rounded-[2rem] border-8 border-white bg-white shadow-card">
        <OfficeRoom title={home.data?.title??"AI OFFICE"} agents={agents.data??[]} items={home.data?.items??[]} positionOverrides={positions} backgroundKey={home.data?.backgroundKey} editable selectedAgentId={selected} roomRef={roomRef}
          onAgentPointerDown={(e,id)=>{dragId.current=id;setSelected(id);e.currentTarget.setPointerCapture(e.pointerId)}} onAgentPointerMove={move}
          onAgentPointerUp={(e)=>{dragId.current=null;if(e.currentTarget.hasPointerCapture(e.pointerId))e.currentTarget.releasePointerCapture(e.pointerId)}} />
        <div className="flex items-center justify-between gap-4 border-t p-4"><p className="text-sm text-stone-500">사람형 캐릭터를 드래그해 자리를 정하세요. 좌표는 0~1로 저장됩니다.</p><button disabled={saveItems.isPending} onClick={()=>saveItems.mutate()} className="shrink-0 rounded-full bg-ink px-5 py-3 font-bold text-white">배치 저장</button></div>
      </div>
      <form key={home.data?.id} onSubmit={submit} className="h-fit space-y-5 rounded-3xl bg-white p-6 shadow-card">
        <h2 className="text-xl font-black">오피스 설정</h2>
        <label className="block text-sm font-bold">회사 이름<input name="title" defaultValue={home.data?.title} required className="mt-2 w-full rounded-xl border p-3" /></label>
        <label className="block text-sm font-bold">회사가 하는 일<textarea name="introduction" defaultValue={home.data?.introduction} rows={3} className="mt-2 w-full rounded-xl border p-3" /></label>
        <label className="block text-sm font-bold">벽·바닥 테마<select name="backgroundKey" defaultValue={home.data?.backgroundKey??"office-warm"} className="mt-2 w-full rounded-xl border p-3"><option value="office-warm">웜 우드 오피스</option><option value="office-green">그린 스튜디오</option><option value="office-night">나이트 랩</option></select></label>
        <label className="block text-sm font-bold">공개 범위<select name="visibility" defaultValue={home.data?.visibility??"PUBLIC"} className="mt-2 w-full rounded-xl border p-3"><option>PRIVATE</option><option>FRIENDS</option><option>PUBLIC</option></select></label>
        <button className="w-full rounded-xl bg-coral p-3 font-bold text-white">설정 저장</button>
        {(saveHome.error||saveItems.error)&&<p className="text-sm text-red-600">{(saveHome.error||saveItems.error)?.message}</p>}
      </form>
    </div>
  </AppShell>;
}
