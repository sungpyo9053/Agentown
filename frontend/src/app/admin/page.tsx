"use client";

import {useMemo,useState} from "react";
import Link from "next/link";
import {useMutation,useQuery,useQueryClient} from "@tanstack/react-query";
import {AppShell} from "@/components/AppShell";
import {api} from "@/lib/api";

type Me={id:string;email:string;handle:string;displayName:string;role:"USER"|"ADMIN"};
type User={id:string;email?:string;handle:string;displayName:string;role:"USER"|"ADMIN";status:"ACTIVE"|"BLOCKED"|"WITHDRAWN";createdAt:string};
type UserSummary={total:number;active:number;blocked:number;admins:number};
type Execution={id:string;harnessId:string;ownerId:string;status:string;currentStepKey?:string;errorCode?:string;errorMessage?:string;queuedAt:string;finishedAt?:string};
type ExecutionSummary={total:number;queued:number;running:number;waitingApproval:number;succeeded:number;failed:number};
type Harness={id:string;ownerId:string;name:string;visibility:string;status:string;createdAt:string;updatedAt:string};
type HarnessSummary={total:number;draft:number;published:number;blocked:number};
type Product={id:string;creatorId:string;title:string;category:string;official:boolean;cloneCount:number;likeCount:number;createdAt:string};
type SystemStatus={status:string;checkedAt:string;uptimeSeconds:number;processors:number;heapUsedMb:number;heapMaxMb:number;workerMode:string};
type Tab="overview"|"users"|"harnesses"|"executions"|"market";

export default function AdminPage(){
 const [tab,setTab]=useState<Tab>("overview");const qc=useQueryClient();
 const me=useQuery({queryKey:["me"],queryFn:()=>api<Me>("/auth/me")});
 const enabled=me.data?.role==="ADMIN";
 const users=useQuery({queryKey:["admin-users"],queryFn:()=>api<User[]>("/admin/users"),enabled});
 const userSummary=useQuery({queryKey:["admin-users-summary"],queryFn:()=>api<UserSummary>("/admin/users/summary"),enabled});
 const executions=useQuery({queryKey:["admin-executions"],queryFn:()=>api<Execution[]>("/admin/executions"),enabled});
 const executionSummary=useQuery({queryKey:["admin-executions-summary"],queryFn:()=>api<ExecutionSummary>("/admin/executions/summary"),enabled});
 const harnesses=useQuery({queryKey:["admin-harnesses"],queryFn:()=>api<Harness[]>("/admin/harnesses"),enabled});
 const harnessSummary=useQuery({queryKey:["admin-harnesses-summary"],queryFn:()=>api<HarnessSummary>("/admin/harnesses/summary"),enabled});
 const products=useQuery({queryKey:["admin-products"],queryFn:()=>api<Product[]>("/admin/market/products"),enabled});
 const system=useQuery({queryKey:["admin-system"],queryFn:()=>api<SystemStatus>("/admin/system"),enabled,refetchInterval:10000});
 const updateUser=useMutation({mutationFn:({id,...body}:{id:string;role?:string;status?:string})=>api(`/admin/users/${id}`,{method:"PATCH",body:JSON.stringify(body)}),onSuccess:()=>{qc.invalidateQueries({queryKey:["admin-users"]});qc.invalidateQueries({queryKey:["admin-users-summary"]})}});
 const updateHarness=useMutation({mutationFn:({id,status}:{id:string;status:string})=>api(`/admin/harnesses/${id}/status`,{method:"PATCH",body:JSON.stringify({status})}),onSuccess:()=>{qc.invalidateQueries({queryKey:["admin-harnesses"]});qc.invalidateQueries({queryKey:["admin-harnesses-summary"]})}});
 const updateProduct=useMutation({mutationFn:({id,official}:{id:string;official:boolean})=>api(`/admin/market/products/${id}/official`,{method:"PATCH",body:JSON.stringify({official})}),onSuccess:()=>qc.invalidateQueries({queryKey:["admin-products"]})});
 const deleteProduct=useMutation({mutationFn:(id:string)=>api(`/admin/market/products/${id}`,{method:"DELETE"}),onSuccess:()=>qc.invalidateQueries({queryKey:["admin-products"]})});
 const userMap=useMemo(()=>Object.fromEntries((users.data??[]).map(user=>[user.id,user])),[users.data]);
 if(me.isLoading)return <AppShell kicker="PLATFORM ADMIN" title="운영 권한 확인 중"><p>Agentown 전체 관리자 권한을 확인하고 있습니다.</p></AppShell>;
 if(me.data?.role!=="ADMIN")return <AppShell kicker="ACCESS DENIED" title="플랫폼 관리자 전용"><p className="rounded-3xl bg-red-50 p-6 text-red-700">ADMIN 권한이 필요합니다.</p></AppShell>;
 const cards=[
  ["전체 회원",userSummary.data?.total??0,`${userSummary.data?.blocked??0}명 차단`],
  ["전체 하네스",harnessSummary.data?.total??0,`${harnessSummary.data?.published??0}개 발행`],
  ["전체 실행",executionSummary.data?.total??0,`${executionSummary.data?.failed??0}건 실패`],
  ["마켓 상품",products.data?.length??0,`${products.data?.filter(item=>item.official).length??0}개 공식`],
 ];
 return <AppShell kicker="PLATFORM ADMIN" title="Agentown 전체 운영 콘솔">
  <div className="rounded-[2rem] bg-ink p-6 text-white"><div className="flex flex-wrap items-center justify-between gap-4"><div><p className="text-xs font-black tracking-[.2em] text-stone">SYSTEM CONTROL</p><h2 className="mt-2 text-2xl font-black">서비스 전체 상태</h2><p className="mt-2 text-sm text-stone-300">개인 결과물 내용은 열람하지 않고 운영 메타데이터만 관리합니다.</p></div><div className="rounded-2xl bg-white/10 px-5 py-3 text-right"><b className="text-leaf">● {system.data?.status??"확인 중"}</b><small className="mt-1 block text-stone-300">{system.data?.workerMode??"LOCAL_COROUTINE"} · uptime {formatUptime(system.data?.uptimeSeconds)}</small></div></div></div>
  <div className="mt-5 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">{cards.map(([label,value,detail])=><article key={label} className="rounded-3xl bg-white p-5 shadow-card"><small className="font-bold text-stone-500">{label}</small><strong className="mt-2 block text-3xl font-black">{value}</strong><span className="mt-2 block text-xs text-stone-500">{detail}</span></article>)}</div>
  <nav className="mt-6 flex gap-2 overflow-x-auto rounded-2xl bg-white p-2 shadow-card">{([['overview','개요'],['users','회원'],['harnesses','하네스'],['executions','실행'],['market','마켓']] as [Tab,string][]).map(([key,label])=><button key={key} onClick={()=>setTab(key)} className={`min-w-24 rounded-xl px-4 py-3 text-sm font-black ${tab===key?"bg-coral text-white":"hover:bg-stone-50"}`}>{label}</button>)}</nav>
  <div className="mt-6">{tab==="overview"&&<Overview system={system.data} users={userSummary.data} executions={executionSummary.data}/>} {tab==="users"&&<UsersTable items={users.data??[]} currentId={me.data.id} onUpdate={(id,body)=>updateUser.mutate({id,...body})}/>} {tab==="harnesses"&&<HarnessTable items={harnesses.data??[]} userMap={userMap} onStatus={(id,status)=>updateHarness.mutate({id,status})}/>} {tab==="executions"&&<ExecutionTable items={executions.data??[]} userMap={userMap}/>} {tab==="market"&&<MarketTable items={products.data??[]} userMap={userMap} onOfficial={(id,official)=>updateProduct.mutate({id,official})} onDelete={id=>{if(confirm("이 마켓 상품을 삭제할까요?"))deleteProduct.mutate(id)}}/>}</div>
 </AppShell>;
}

function Overview({system,users,executions}:{system?:SystemStatus;users?:UserSummary;executions?:ExecutionSummary}){return <div className="grid gap-5 lg:grid-cols-2"><section className="rounded-3xl bg-white p-6 shadow-card"><h3 className="text-lg font-black">운영 상태</h3><dl className="mt-5 grid grid-cols-2 gap-4 text-sm"><Metric label="CPU 논리 코어" value={system?.processors??"-"}/><Metric label="JVM Heap" value={system?`${system.heapUsedMb} / ${system.heapMaxMb} MB`:"-"}/><Metric label="활성 회원" value={users?.active??0}/><Metric label="승인 대기 실행" value={executions?.waitingApproval??0}/></dl></section><section className="rounded-3xl bg-white p-6 shadow-card"><h3 className="text-lg font-black">관리 범위</h3><ul className="mt-5 space-y-3 text-sm text-stone-600"><li>✓ 회원 차단·해제 및 관리자 권한 변경</li><li>✓ 전체 하네스 차단·해제·폐기</li><li>✓ 실행 상태와 실패 코드 확인</li><li>✓ 마켓 공식 지정 및 상품 삭제</li><li>— 사용자 프롬프트·API 키·결과물 본문은 열람 불가</li></ul></section></div>}
function Metric({label,value}:{label:string;value:string|number}){return <div className="rounded-2xl bg-stone-50 p-4"><dt className="text-stone-500">{label}</dt><dd className="mt-1 text-lg font-black">{value}</dd></div>}
function UsersTable({items,currentId,onUpdate}:{items:User[];currentId:string;onUpdate:(id:string,body:{role?:string;status?:string})=>void}){return <Panel title="전체 회원" subtitle="이름을 누르면 해당 사용자의 AI 회사로 이동합니다. 현재 계정은 스스로 차단하거나 강등할 수 없습니다."><Table headers={["회원·회사","권한","상태","가입일","관리"]}>{items.map(user=><tr key={user.id}><Cell><Link href={`/users/${user.handle}`} className="group block"><b className="block group-hover:text-coral">{user.displayName||user.handle} ↗</b><small className="mt-1 block text-stone-500">아이디 {user.handle}{user.email?` · ${user.email}`:""}</small></Link></Cell><Cell>{user.role}</Cell><Cell><Status value={user.status}/></Cell><Cell>{date(user.createdAt)}</Cell><Cell><div className="flex flex-wrap gap-2"><button disabled={user.id===currentId} onClick={()=>onUpdate(user.id,{role:user.role==="ADMIN"?"USER":"ADMIN"})} className="admin-action">{user.role==="ADMIN"?"권한 해제":"관리자 지정"}</button><button disabled={user.id===currentId} onClick={()=>onUpdate(user.id,{status:user.status==="BLOCKED"?"ACTIVE":"BLOCKED"})} className="admin-action">{user.status==="BLOCKED"?"차단 해제":"차단"}</button></div></Cell></tr>)}</Table></Panel>}
function HarnessTable({items,userMap,onStatus}:{items:Harness[];userMap:Record<string,User>;onStatus:(id:string,status:string)=>void}){return <Panel title="전체 하네스" subtitle="소유자와 공개 상태를 확인하고 정책 위반 하네스를 차단합니다."><Table headers={["하네스","소유자","공개","상태","관리"]}>{items.map(item=><tr key={item.id}><Cell><b>{item.name}</b><small>{item.id.slice(0,8)}</small></Cell><Cell>{userMap[item.ownerId]?.handle??item.ownerId.slice(0,8)}</Cell><Cell>{item.visibility}</Cell><Cell><Status value={item.status}/></Cell><Cell><button onClick={()=>onStatus(item.id,item.status==="BLOCKED"?"DRAFT":"BLOCKED")} className="admin-action">{item.status==="BLOCKED"?"차단 해제":"차단"}</button></Cell></tr>)}</Table></Panel>}
function ExecutionTable({items,userMap}:{items:Execution[];userMap:Record<string,User>}){return <Panel title="전체 실행" subtitle="결과 내용 없이 상태·단계·실패 코드만 표시합니다."><Table headers={["실행","사용자","상태","현재 단계","오류","요청 시각"]}>{items.map(item=><tr key={item.id}><Cell><b>{item.id.slice(0,8)}</b><small>Harness {item.harnessId.slice(0,8)}</small></Cell><Cell>{userMap[item.ownerId]?.handle??item.ownerId.slice(0,8)}</Cell><Cell><Status value={item.status}/></Cell><Cell>{item.currentStepKey??"-"}</Cell><Cell>{item.errorCode??"-"}</Cell><Cell>{date(item.queuedAt)}</Cell></tr>)}</Table></Panel>}
function MarketTable({items,userMap,onOfficial,onDelete}:{items:Product[];userMap:Record<string,User>;onOfficial:(id:string,value:boolean)=>void;onDelete:(id:string)=>void}){return <Panel title="전체 마켓 상품" subtitle="공식 배지 지정과 상품 노출 제거를 관리합니다."><Table headers={["상품","제작자","카테고리","성과","공식","관리"]}>{items.map(item=><tr key={item.id}><Cell><b>{item.title}</b><small>{date(item.createdAt)}</small></Cell><Cell>{userMap[item.creatorId]?.handle??item.creatorId.slice(0,8)}</Cell><Cell>{item.category}</Cell><Cell>복제 {item.cloneCount} · 좋아요 {item.likeCount}</Cell><Cell><Status value={item.official?"OFFICIAL":"USER"}/></Cell><Cell><div className="flex gap-2"><button onClick={()=>onOfficial(item.id,!item.official)} className="admin-action">{item.official?"공식 해제":"공식 지정"}</button><button onClick={()=>onDelete(item.id)} className="admin-action text-red-600">삭제</button></div></Cell></tr>)}</Table></Panel>}
function Panel({title,subtitle,children}:{title:string;subtitle:string;children:React.ReactNode}){return <section className="overflow-hidden rounded-3xl bg-white shadow-card"><div className="p-6"><h3 className="text-xl font-black">{title}</h3><p className="mt-1 text-sm text-stone-500">{subtitle}</p></div>{children}</section>}
function Table({headers,children}:{headers:string[];children:React.ReactNode}){return <div className="overflow-x-auto"><table className="w-full min-w-[760px] border-collapse text-left text-sm"><thead className="bg-stone-50 text-xs text-stone-500"><tr>{headers.map(item=><th key={item} className="px-5 py-3">{item}</th>)}</tr></thead><tbody className="divide-y">{children}</tbody></table></div>}
function Cell({children}:{children:React.ReactNode}){return <td className="px-5 py-4"><span className="block">{children}</span></td>}
function Status({value}:{value:string}){const good=["ACTIVE","SUCCEEDED","PUBLISHED","OFFICIAL"].includes(value);const bad=["BLOCKED","FAILED","TIMEOUT"].includes(value);return <span className={`rounded-full px-3 py-1 text-xs font-black ${good?"bg-emerald-50 text-leaf":bad?"bg-red-50 text-red-600":"bg-amber-50 text-amber-700"}`}>{value}</span>}
function date(value:string){return new Intl.DateTimeFormat("ko-KR",{dateStyle:"short",timeStyle:"short"}).format(new Date(value))}
function formatUptime(seconds?:number){if(seconds===undefined)return "-";const hours=Math.floor(seconds/3600);return `${hours}h ${Math.floor((seconds%3600)/60)}m`}
