"use client";
/* eslint-disable react-hooks/set-state-in-effect */

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FormEvent, PointerEvent, useEffect, useRef, useState } from "react";
import { AppShell } from "@/components/AppShell";
import { OfficeAgent, OfficeRoom, OfficeRoomItem, resolveAgentPosition } from "@/components/OfficeRoom";
import { api } from "@/lib/api";
import {useRouter} from "next/navigation";

type Home = { id:string; handle:string; title:string; introduction?:string; backgroundKey:string; visibility:string; items:OfficeRoomItem[] };
type Profile = { displayName:string; bio?:string; avatarUrl?:string };

export default function Page() {
  const router = useRouter();
  const client = useQueryClient();
  const roomRef = useRef<HTMLDivElement>(null);
  const dragId = useRef<string | null>(null);
  const home = useQuery({queryKey:["home"],queryFn:()=>api<Home>("/mini-homes/me")});
  const agents = useQuery({queryKey:["agents"],queryFn:()=>api<OfficeAgent[]>("/agents")});
  const profile = useQuery({queryKey:["profile-me"],queryFn:()=>api<Profile>("/users/me")});
  const [positions,setPositions] = useState<Record<string,{x:number;y:number}>>({});
  const [selected,setSelected] = useState<string|null>(null);

  useEffect(()=>{
    if (!home.data || !agents.data) return;
    setPositions(Object.fromEntries(agents.data.map((agent,index)=>[agent.id,resolveAgentPosition(agent.id,index,home.data.items)])));
  },[home.data,agents.data]);

  const saveItems = useMutation({mutationFn:()=>api<Home>("/mini-homes/me/items",{method:"PUT",body:JSON.stringify((agents.data??[]).map((agent,index)=>({agentId:agent.id,itemType:"AGENT",positionX:positions[agent.id]?.x??.2,positionY:positions[agent.id]?.y??.6,width:.13,height:.28,zIndex:index+10,rotation:0})))}),onSuccess:(data)=>client.setQueryData(["home"],data)});
  const saveHome = useMutation({mutationFn:(body:unknown)=>api<Home>("/mini-homes/me",{method:"PATCH",body:JSON.stringify(body)}),onSuccess:(data)=>client.setQueryData(["home"],data)});
  const saveProfile = useMutation({mutationFn:(body:unknown)=>api<Profile>("/users/me",{method:"PATCH",body:JSON.stringify(body)}),onSuccess:(data)=>client.setQueryData(["profile-me"],data)});
  const changePassword = useMutation({mutationFn:(body:unknown)=>api<void>("/users/me/password",{method:"PATCH",body:JSON.stringify(body)})});
  const withdraw = useMutation({mutationFn:(body:unknown)=>api<void>("/users/me",{method:"DELETE",body:JSON.stringify(body)}),onSuccess:()=>router.push("/")});

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
        <label className="block text-sm font-bold">소개<textarea name="introduction" defaultValue={home.data?.introduction} rows={3} className="mt-2 w-full rounded-xl border p-3" /></label>
        <label className="block text-sm font-bold">벽·바닥 테마<select name="backgroundKey" defaultValue={home.data?.backgroundKey??"office-warm"} className="mt-2 w-full rounded-xl border p-3"><option value="office-warm">웜 우드 오피스</option><option value="office-green">그린 스튜디오</option><option value="office-night">나이트 랩</option></select></label>
        <label className="block text-sm font-bold">공개 범위<select name="visibility" defaultValue={home.data?.visibility??"PUBLIC"} className="mt-2 w-full rounded-xl border p-3"><option>PRIVATE</option><option>FRIENDS</option><option>PUBLIC</option></select></label>
        <button className="w-full rounded-xl bg-coral p-3 font-bold text-white">설정 저장</button>
        {(saveHome.error||saveItems.error)&&<p className="text-sm text-red-600">{(saveHome.error||saveItems.error)?.message}</p>}
        <hr/>
        <h2 className="text-xl font-black">내 프로필</h2>
        <label className="block text-sm font-bold">표시 이름<input value={profile.data?.displayName??""} onChange={e=>client.setQueryData<Profile>(["profile-me"],old=>({...old!,displayName:e.target.value}))} className="mt-2 w-full rounded-xl border p-3" /></label>
        <label className="block text-sm font-bold">소개<textarea value={profile.data?.bio??""} onChange={e=>client.setQueryData<Profile>(["profile-me"],old=>({...old!,bio:e.target.value}))} rows={3} className="mt-2 w-full rounded-xl border p-3" /></label>
        <button type="button" onClick={()=>saveProfile.mutate({displayName:profile.data?.displayName,bio:profile.data?.bio,avatarUrl:profile.data?.avatarUrl??""})} className="w-full rounded-xl border p-3 font-bold">프로필 저장</button>
        {saveProfile.error&&<p className="text-sm text-red-600">{saveProfile.error.message}</p>}
      </form>
    </div>
    <section className="mt-7 grid gap-6 md:grid-cols-2"><form onSubmit={e=>{e.preventDefault();const f=new FormData(e.currentTarget);changePassword.mutate({currentPassword:f.get("currentPassword"),newPassword:f.get("newPassword")})}} className="space-y-3 rounded-3xl bg-white p-6 shadow-card"><h2 className="font-black">비밀번호 변경</h2><input type="password" name="currentPassword" minLength={8} required placeholder="현재 비밀번호" className="w-full rounded-xl border p-3"/><input type="password" name="newPassword" minLength={8} required placeholder="새 비밀번호" className="w-full rounded-xl border p-3"/><button className="rounded-xl bg-ink px-5 py-3 font-bold text-white">변경</button>{changePassword.isSuccess&&<p className="text-sm text-leaf">비밀번호를 변경했습니다.</p>}{changePassword.error&&<p className="text-sm text-red-600">{changePassword.error.message}</p>}</form><form onSubmit={e=>{e.preventDefault();if(!window.confirm("계정을 탈퇴하면 다시 로그인할 수 없습니다. 계속할까요?"))return;const f=new FormData(e.currentTarget);withdraw.mutate({currentPassword:f.get("currentPassword")})}} className="space-y-3 rounded-3xl border border-red-100 bg-red-50 p-6"><h2 className="font-black text-red-700">계정 탈퇴</h2><p className="text-sm text-red-600">로그인을 차단하고 이메일 인증정보를 제거합니다.</p><input type="password" name="currentPassword" minLength={8} required placeholder="현재 비밀번호 확인" className="w-full rounded-xl border p-3"/><button className="rounded-xl border border-red-300 bg-white px-5 py-3 font-bold text-red-700">탈퇴</button>{withdraw.error&&<p className="text-sm text-red-600">{withdraw.error.message}</p>}</form></section>
  </AppShell>;
}
