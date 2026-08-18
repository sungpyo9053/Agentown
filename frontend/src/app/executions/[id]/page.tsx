"use client";
/* eslint-disable react-hooks/set-state-in-effect */

import { useMutation, useQuery } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import { AppShell } from "@/components/AppShell";
import { AgentVisualStatus } from "@/components/AgentCharacter";
import { OfficeAgent, OfficeRoom, OfficeRoomItem, placedAssets, resolveAgentPosition } from "@/components/OfficeRoom";
import { api } from "@/lib/api";

type Execution = {id:string;harnessId:string;status:string;currentStepKey?:string;outputJson?:Record<string,unknown>;errorCode?:string;errorMessage?:string;startedAt?:string;finishedAt?:string};
type ExecutionView = {execution:Execution;steps:{id:string;stepKey:string;status:string;provider?:string;model?:string;outputJson?:Record<string,unknown>}[]};
type EventItem = {id:string;sequenceNo:number;eventType:string;agentId?:string;payload:Record<string,unknown>;createdAt:string};
type ResultFormat="AUTO"|"TEXT"|"MARKDOWN"|"HTML"|"JSON"|"CSV"|"EXTERNAL";
type HarnessView = {harness:{id:string;name:string;resultFormat:ResultFormat;resultStepKey?:string};steps:{id:string;stepKey:string;agentId?:string;sequenceNo:number}[]};
type Home = {title:string;backgroundKey:string;items:OfficeRoomItem[]};
type Artifact = {id:string;type:string;fileName:string;mimeType:string;expiresAt?:string;status:string};

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
  const artifacts=useQuery({queryKey:["artifacts",id],queryFn:()=>api<Artifact[]>(`/artifacts?executionId=${id}`),enabled:!!id&&execution.data?.execution.status==="SUCCEEDED"});
  useEffect(()=>{if(history.data)setEvents(history.data)},[history.data]);
  useEffect(()=>{if(execution.data)history.refetch()},[execution.data?.execution.status]); // eslint-disable-line react-hooks/exhaustive-deps

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
  const result=extractExecutionResult(execution.data?.execution.outputJson,harness.data?.harness.resultStepKey);
  const resultFormat=resolveResultFormat(harness.data?.harness.resultFormat??"AUTO",result);

  return <AppShell kicker="LIVE ORCHESTRATION" title={harness.data?.harness.name??"AI 팀 실행 관제"}>
    <div className="overflow-hidden rounded-[2rem] border-8 border-white bg-white shadow-card"><OfficeRoom title="ORCHESTRATION FLOOR" agents={workflowAgents} items={home.data?.items??[]} assets={placedAssets(home.data?.items??[])} statuses={scene.statuses} positionOverrides={scene.positions} backgroundKey={home.data?.backgroundKey} /></div>
    <div className="mt-6 grid gap-6 lg:grid-cols-[1fr_340px]">
      <section className="rounded-3xl bg-white p-6 shadow-card"><div className="flex items-center justify-between"><h2 className="text-xl font-black">실제 실행 이벤트</h2><span className="rounded-full bg-ink px-4 py-2 text-xs font-black text-white">{status}</span></div>
        <div className="mt-5 max-h-96 space-y-2 overflow-auto">{events.map(event=><div key={event.id} className="flex gap-3 rounded-xl bg-stone-50 p-3 text-sm"><b className="w-7 text-coral">{event.sequenceNo}</b><span className="font-bold">{eventLabel(event.eventType)}</span><span className="ml-auto text-stone-500">{event.agentId?agentMap[event.agentId]?.name:"오케스트레이터"}</span></div>)}{!events.length&&<p className="text-stone-500">Queue 이벤트를 기다리는 중입니다.</p>}</div>
      </section>
      <aside className="space-y-4"><div className="rounded-3xl bg-ink p-6 text-white"><p className="text-xs text-stone-300">CURRENT STEP</p><p className="mt-2 text-2xl font-black">{execution.data?.execution.currentStepKey??"대기"}</p></div>
        {status==="WAITING_APPROVAL"&&<div className="rounded-3xl bg-amber-50 p-5"><b>사용자 승인이 필요합니다</b><div className="mt-4 flex gap-2"><button onClick={()=>action.mutate("approve")} className="rounded-full bg-leaf px-4 py-2 font-bold text-white">승인</button><button onClick={()=>action.mutate("reject")} className="rounded-full border px-4 py-2 font-bold">반려</button></div></div>}
        {["QUEUED","RUNNING","WAITING_APPROVAL"].includes(status)&&<button onClick={()=>action.mutate("cancel")} className="w-full rounded-full border border-red-200 p-3 font-bold text-red-600">실행 취소</button>}
        {execution.data?.execution.errorCode&&<div className="rounded-3xl bg-red-50 p-5 text-red-700"><b>{execution.data.execution.errorCode}</b><p className="mt-2 text-sm">{execution.data.execution.errorMessage}</p></div>}
      </aside>
    </div>
    {result&&<section className="mt-6 rounded-3xl bg-white p-7 shadow-card"><div className="flex flex-wrap items-center justify-between gap-3"><div><p className="text-xs font-black text-coral">PRIVATE RESULT · {resultFormat}</p><h2 className="text-xl font-black">내 실행 결과물</h2></div><div className="flex flex-wrap gap-2">{resultFormat!=="EXTERNAL"&&<a href={`/api/executions/${id}/download?format=${resultFormat.toLowerCase()}`} className="rounded-full bg-ink px-4 py-2 text-sm font-bold text-white">최종 {resultFormatLabel(resultFormat)} 다운로드</a>}<a href={`/api/executions/${id}/download?format=debug-json`} className="rounded-full border px-4 py-2 text-sm font-bold">실행 기록 .json</a></div></div><p className="mt-2 text-xs text-stone-500">결과 형식과 담당 단계는 하네스가 결정합니다. 실행 결과는 실행한 계정만 접근할 수 있습니다.</p><div className="mt-5">{resultFormat==="JSON"?<pre className="max-h-[34rem] overflow-auto whitespace-pre-wrap rounded-2xl bg-stone-950 p-5 text-xs text-stone-100">{typeof result==="string"?result:JSON.stringify(result,null,2)}</pre>:resultFormat==="EXTERNAL"?<p className="rounded-2xl bg-amber-50 p-5 text-sm">외부 서비스가 만든 파일은 아래 결과물 목록에서 MIME 형식 그대로 내려받습니다.</p>:<MarkdownResult content={typeof result==="string"?result:JSON.stringify(result,null,2)}/>}</div>{(artifacts.data?.length??0)>0&&<div className="mt-6 border-t pt-5"><h3 className="font-black">외부 생성 파일</h3><div className="mt-3 grid gap-3 sm:grid-cols-2">{artifacts.data?.map(item=><a key={item.id} href={`/api/artifacts/${item.id}/download`} className="rounded-2xl border p-4 hover:border-coral"><b className="block">{item.fileName}</b><small className="text-stone-500">{item.mimeType} · {item.status}</small></a>)}</div></div>}<details className="mt-5 text-xs text-stone-500"><summary className="cursor-pointer font-bold">기술 상세 JSON</summary><pre className="mt-3 max-h-72 overflow-auto whitespace-pre-wrap">{JSON.stringify(execution.data?.execution.outputJson,null,2)}</pre></details></section>}
  </AppShell>;
}

function buildScene(events:EventItem[],agents:OfficeAgent[],items:OfficeRoomItem[]){
  const statuses:Record<string,AgentVisualStatus>=Object.fromEntries(agents.map(agent=>[agent.id,"IDLE"]));
  const positions:Record<string,{x:number;y:number}>=Object.fromEntries(agents.map((agent,index)=>[agent.id,resolveAgentPosition(agent.id,index,items)]));
  for(const event of events){const id=event.agentId;if(event.eventType==="EXECUTION_COMPLETED")agents.forEach(agent=>{statuses[agent.id]="SUCCESS";positions[agent.id]=resolveAgentPosition(agent.id,agents.indexOf(agent),items)});if(!id)continue;
    if(event.eventType==="STEP_STARTED"){statuses[id]="WORKING";positions[id]={x:.5,y:.61}}
    if(event.eventType==="MODEL_REQUEST_SENT")statuses[id]="THINKING";
    if(event.eventType==="TOOL_CALLED"||event.eventType==="STEP_OUTPUT_CREATED")statuses[id]="TOOL";
    if(event.eventType==="STEP_COMPLETED"){const index=agents.findIndex(agent=>agent.id===id);statuses[id]="SUCCESS";positions[id]={x:.27+(Math.max(index,0)*.115),y:.76}}
    if(event.eventType==="WAITING_APPROVAL")statuses[id]="WAITING";
    if(event.eventType==="STEP_FAILED")statuses[id]="FAILED";
  }
  return {statuses,positions};
}
function eventLabel(type:string){return ({EXECUTION_QUEUED:"대기열 등록",EXECUTION_STARTED:"업무 시작",STEP_STARTED:"담당자가 작업 구역으로 이동",MODEL_REQUEST_SENT:"모델에 요청",TOOL_CALLED:"외부 도구 사용",STEP_OUTPUT_CREATED:"결과물 생성",STEP_COMPLETED:"다음 담당자에게 전달",STEP_FAILED:"단계 실패",WAITING_APPROVAL:"승인 요청",EXECUTION_COMPLETED:"전체 업무 완료",EXECUTION_FAILED:"실행 실패"} as Record<string,string>)[type]??type}

function extractExecutionResult(output?:Record<string,unknown>,resultStepKey?:string):string|null{
 if(!output)return null;
 const configured=resultStepKey?output[resultStepKey]:undefined;
 if(configured&&typeof configured==="object"&&!Array.isArray(configured)){const value=configured as Record<string,unknown>;return typeof value.result==="string"?value.result:JSON.stringify(value,null,2)}
 if(configured!==undefined)return typeof configured==="string"?configured:JSON.stringify(configured,null,2);
 const stages=Object.values(output).filter((value):value is Record<string,unknown>=>!!value&&typeof value==="object"&&!Array.isArray(value));
 const finalText=typeof output.result==="string"?output.result:undefined;
 const writer=stages.find(stage=>{
  if(typeof stage.result!=="string")return false;
  const agent=typeof stage.agent==="string"?stage.agent.toLowerCase():"";
  return agent.includes("writer")||agent.includes("작가")||agent.includes("작성")||stage.result.startsWith("---\n");
 });
 const publishResult=finalText&&(/publish result|draft_ready|external_write/i.test(finalText))?finalText:undefined;
 const article=typeof writer?.result==="string"?writer.result:publishResult?undefined:finalText;
 return article??publishResult??finalText??JSON.stringify(output,null,2);
}

function resolveResultFormat(configured:ResultFormat,result:unknown):ResultFormat{if(configured!=="AUTO")return configured;if(typeof result!=="string")return "JSON";const value=result.trimStart();if(/^\{|^\[/.test(value))return "JSON";if(/^<!doctype html|^<html/i.test(value))return "HTML";if(value.split("\n").some(line=>/^#{1,3} /.test(line)))return "MARKDOWN";return "TEXT"}
function resultFormatLabel(format:ResultFormat){return ({AUTO:"파일",TEXT:"텍스트 (.txt)",MARKDOWN:"Markdown (.md)",HTML:"HTML (.html)",JSON:"JSON (.json)",CSV:"CSV (.csv)",EXTERNAL:"외부 파일"} as Record<ResultFormat,string>)[format]}

function MarkdownResult({content}:{content:string}){
 const rawLines=content.split("\n");const frontmatterEnd=rawLines[0]?.trim()==="---"?rawLines.slice(1).findIndex(line=>line.trim()==="---"):-1;const lines=frontmatterEnd>=0?rawLines.slice(frontmatterEnd+2):rawLines;
 return <article className="mt-4 space-y-2 text-sm leading-7 text-stone-700">{lines.map((line,index)=>{if(line.startsWith("### "))return <h4 key={index} className="pt-3 text-base font-black text-ink">{line.slice(4)}</h4>;if(line.startsWith("## "))return <h3 key={index} className="pt-4 text-lg font-black text-ink">{line.slice(3)}</h3>;if(line.startsWith("# "))return <h2 key={index} className="pt-2 text-2xl font-black text-ink">{line.slice(2)}</h2>;if(line.startsWith("- "))return <p key={index} className="pl-3">• {line.slice(2)}</p>;if(!line.trim())return <span key={index} className="block h-1"/>;return <p key={index}>{line}</p>})}</article>
}
