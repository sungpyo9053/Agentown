"use client";
/* eslint-disable react-hooks/set-state-in-effect */

import { useMutation, useQuery } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import { AppShell } from "@/components/AppShell";
import { AgentVisualStatus } from "@/components/AgentCharacter";
import { OfficeAgent, OfficeRoom, OfficeRoomItem, resolveAgentPosition } from "@/components/OfficeRoom";
import { api } from "@/lib/api";

type Execution = {id:string;harnessId:string;status:string;currentStepKey?:string;outputJson?:Record<string,unknown>;errorCode?:string;errorMessage?:string;startedAt?:string;finishedAt?:string};
type ExecutionView = {execution:Execution;steps:{id:string;stepKey:string;status:string;provider?:string;model?:string;outputJson?:Record<string,unknown>}[]};
type EventItem = {id:string;sequenceNo:number;eventType:string;agentId?:string;payload:Record<string,unknown>;createdAt:string};
type HarnessView = {harness:{id:string;name:string};steps:{id:string;stepKey:string;agentId?:string;sequenceNo:number}[]};
type Home = {title:string;backgroundKey:string;items:OfficeRoomItem[]};

export default function Page({params}:{params:Promise<{id:string}>}) {
  const [id,setId]=useState("");
  const [events,setEvents]=useState<EventItem[]>([]);
  useEffect(()=>{params.then(value=>setId(value.id))},[params]);
  const execution=useQuery({queryKey:["execution",id],queryFn:()=>api<ExecutionView>(`/executions/${id}`),enabled:!!id,refetchInterval:2000});
  const history=useQuery({queryKey:["execution-history",id],queryFn:()=>api<EventItem[]>(`/executions/${id}/history`),enabled:!!id});
  const harnessId=execution.data?.execution.harnessId;
  const harness=useQuery({queryKey:["harness",harnessId],queryFn:()=>api<HarnessView>(`/harnesses/${harnessId}`),enabled:!!harnessId});
  const agents=useQuery({queryKey:["agents"],queryFn:()=>api<OfficeAgent[]>("/agents")});
  const home=useQuery({queryKey:["home"],queryFn:()=>api<Home>("/mini-homes/me")});
  useEffect(()=>{if(history.data)setEvents(history.data)},[history.data]);

  useEffect(()=>{
    if(!id)return;
    const source=new EventSource(`/api/executions/${id}/events`);
    const names=["EXECUTION_QUEUED","EXECUTION_STARTED","STEP_STARTED","MODEL_REQUEST_SENT","TOOL_CALLED","STEP_OUTPUT_CREATED","STEP_COMPLETED","STEP_FAILED","WAITING_APPROVAL","EXECUTION_COMPLETED","EXECUTION_FAILED"];
    const listen=(event:MessageEvent)=>{const item=JSON.parse(event.data) as EventItem;setEvents(current=>current.some(old=>old.id===item.id)?current:[...current,item].sort((a,b)=>a.sequenceNo-b.sequenceNo));execution.refetch()};
    names.forEach(name=>source.addEventListener(name,listen as EventListener));
    return()=>source.close();
  },[id]); // eslint-disable-line react-hooks/exhaustive-deps

  const agentMap=useMemo(()=>Object.fromEntries((agents.data??[]).map(agent=>[agent.id,agent])),[agents.data]);
  const workflowAgents=useMemo(()=>{
    const ids=(harness.data?.steps??[]).sort((a,b)=>a.sequenceNo-b.sequenceNo).map(step=>step.agentId).filter((value):value is string=>!!value);
    return ids.map(id=>agentMap[id]).filter(Boolean);
  },[harness.data,agentMap]);
  const scene=useMemo(()=>buildScene(events,workflowAgents,home.data?.items??[]),[events,workflowAgents,home.data?.items]);
  const action=useMutation({mutationFn:(name:"approve"|"reject"|"cancel")=>api(`/executions/${id}/${name}`,{method:"POST"}),onSuccess:()=>execution.refetch()});
  const status=execution.data?.execution.status??"QUEUED";

  return <AppShell kicker="LIVE ORCHESTRATION" title={harness.data?.harness.name??"AI 팀 실행 관제"}>
    <div className="overflow-hidden rounded-[2rem] border-8 border-white bg-white shadow-card"><OfficeRoom title="ORCHESTRATION FLOOR" agents={workflowAgents} items={home.data?.items??[]} statuses={scene.statuses} positionOverrides={scene.positions} backgroundKey={home.data?.backgroundKey} /></div>
    <div className="mt-6 grid gap-6 lg:grid-cols-[1fr_340px]">
      <section className="rounded-3xl bg-white p-6 shadow-card"><div className="flex items-center justify-between"><h2 className="text-xl font-black">실제 실행 이벤트</h2><span className="rounded-full bg-ink px-4 py-2 text-xs font-black text-white">{status}</span></div>
        <div className="mt-5 max-h-96 space-y-2 overflow-auto">{events.map(event=><div key={event.id} className="flex gap-3 rounded-xl bg-stone-50 p-3 text-sm"><b className="w-7 text-coral">{event.sequenceNo}</b><span className="font-bold">{eventLabel(event.eventType)}</span><span className="ml-auto text-stone-500">{event.agentId?agentMap[event.agentId]?.name:"오케스트레이터"}</span></div>)}{!events.length&&<p className="text-stone-500">Queue 이벤트를 기다리는 중입니다.</p>}</div>
      </section>
      <aside className="space-y-4"><div className="rounded-3xl bg-ink p-6 text-white"><p className="text-xs text-stone-300">CURRENT STEP</p><p className="mt-2 text-2xl font-black">{execution.data?.execution.currentStepKey??"대기"}</p></div>
        {status==="WAITING_APPROVAL"&&<div className="rounded-3xl bg-amber-50 p-5"><b>사용자 승인이 필요합니다</b><div className="mt-4 flex gap-2"><button onClick={()=>action.mutate("approve")} className="rounded-full bg-leaf px-4 py-2 font-bold text-white">승인</button><button onClick={()=>action.mutate("reject")} className="rounded-full border px-4 py-2 font-bold">반려</button></div></div>}
        {["QUEUED","RUNNING","WAITING_APPROVAL"].includes(status)&&<button onClick={()=>action.mutate("cancel")} className="w-full rounded-full border border-red-200 p-3 font-bold text-red-600">실행 취소</button>}
        {execution.data?.execution.outputJson&&<div className="rounded-3xl bg-white p-5 shadow-card"><b>최종 결과</b><pre className="mt-3 overflow-auto whitespace-pre-wrap text-xs">{JSON.stringify(execution.data.execution.outputJson,null,2)}</pre></div>}
        {execution.data?.execution.errorCode&&<div className="rounded-3xl bg-red-50 p-5 text-red-700"><b>{execution.data.execution.errorCode}</b><p className="mt-2 text-sm">{execution.data.execution.errorMessage}</p></div>}
      </aside>
    </div>
  </AppShell>;
}

function buildScene(events:EventItem[],agents:OfficeAgent[],items:OfficeRoomItem[]){
  const statuses:Record<string,AgentVisualStatus>=Object.fromEntries(agents.map(agent=>[agent.id,"IDLE"]));
  const positions:Record<string,{x:number;y:number}>=Object.fromEntries(agents.map((agent,index)=>[agent.id,resolveAgentPosition(agent.id,index,items)]));
  let completed=0;
  for(const event of events){const id=event.agentId;if(event.eventType==="EXECUTION_COMPLETED")agents.forEach(agent=>{statuses[agent.id]="SUCCESS";positions[agent.id]=resolveAgentPosition(agent.id,agents.indexOf(agent),items)});if(!id)continue;
    if(event.eventType==="STEP_STARTED"){statuses[id]="WORKING";positions[id]={x:.5,y:.61}}
    if(event.eventType==="MODEL_REQUEST_SENT")statuses[id]="THINKING";
    if(event.eventType==="TOOL_CALLED"||event.eventType==="STEP_OUTPUT_CREATED")statuses[id]="TOOL";
    if(event.eventType==="STEP_COMPLETED"){statuses[id]="SUCCESS";positions[id]={x:.39+((completed++%3)*.11),y:.76}}
    if(event.eventType==="WAITING_APPROVAL")statuses[id]="WAITING";
    if(event.eventType==="STEP_FAILED")statuses[id]="FAILED";
  }
  return {statuses,positions};
}
function eventLabel(type:string){return ({EXECUTION_QUEUED:"대기열 등록",EXECUTION_STARTED:"업무 시작",STEP_STARTED:"담당자가 작업 구역으로 이동",MODEL_REQUEST_SENT:"모델에 요청",TOOL_CALLED:"외부 도구 사용",STEP_OUTPUT_CREATED:"결과물 생성",STEP_COMPLETED:"다음 담당자에게 전달",STEP_FAILED:"단계 실패",WAITING_APPROVAL:"승인 요청",EXECUTION_COMPLETED:"전체 업무 완료",EXECUTION_FAILED:"실행 실패"} as Record<string,string>)[type]??type}
