"use client";

import { FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AppShell } from "@/components/AppShell";
import { api } from "@/lib/api";

type Credential = { id:string; provider:string; maskedSecret:string; status:string; lastVerifiedAt?:string };

export default function Page() {
  const client = useQueryClient();
  const list = useQuery({ queryKey:["credentials"], queryFn:() => api<Credential[]>("/llm-credentials") });
  const create = useMutation({ mutationFn:(body:unknown) => api<Credential>("/llm-credentials", { method:"POST", body:JSON.stringify(body) }), onSuccess:() => client.invalidateQueries({ queryKey:["credentials"] }) });
  const remove = useMutation({ mutationFn:(id:string) => api(`/llm-credentials/${id}`, { method:"DELETE" }), onSuccess:() => client.invalidateQueries({ queryKey:["credentials"] }) });
  function submit(event:FormEvent<HTMLFormElement>) { event.preventDefault(); const form = new FormData(event.currentTarget); create.mutate({ provider:form.get("provider"), secret:form.get("secret"), providerOptions:{} }); }
  return <AppShell kicker="BYOK CONNECTION" title="Codex · Claude API 연결">
    <div className="grid gap-6 lg:grid-cols-[400px_1fr]">
      <form onSubmit={submit} className="h-fit space-y-4 rounded-3xl bg-white p-6 shadow-card">
        <div><h2 className="text-xl font-black">내 API로 연결하기</h2><p className="mt-2 text-sm leading-6 text-stone-600">Agentown이 공급자의 실제 Models API를 호출해 키를 검증합니다. 성공한 키만 암호화해 저장하며 원문은 다시 반환하지 않습니다.</p></div>
        <label className="block text-sm font-bold">서비스<select name="provider" className="mt-2 w-full rounded-xl border p-3"><option value="OPENAI">Codex / OpenAI API</option><option value="ANTHROPIC">Claude / Anthropic API</option><option value="GOOGLE">Gemini / Google API</option></select></label>
        <label className="block text-sm font-bold">API 키<input name="secret" type="password" autoComplete="off" required placeholder="등록 후 원문은 표시되지 않습니다" className="mt-2 w-full rounded-xl border p-3"/></label>
        <button disabled={create.isPending} className="w-full rounded-xl bg-ink p-3 font-bold text-white">{create.isPending ? "실제 API 연결 확인 중…" : "연결 테스트 후 암호화 저장"}</button>
        {create.isSuccess && <p className="rounded-xl bg-emerald-50 p-3 text-sm font-bold text-leaf">✓ 연결 테스트를 통과해 저장했습니다.</p>}
        {create.error && <p className="rounded-xl bg-red-50 p-3 text-sm text-red-600">연결 실패: {create.error.message}</p>}
        <div className="rounded-2xl bg-cream p-4 text-xs leading-6 text-stone-600">AES-256-GCM 암호화 · 원문 조회 불가 · 로그/복제/내보내기 제외 · 플랫폼 공용 키 미사용</div>
      </form>
      <section className="space-y-3">
        <div className="rounded-3xl border border-stone-200 bg-white p-5"><b>연결 상태 기준</b><p className="mt-2 text-sm text-stone-500">`ACTIVE`만 AI 회사 설계와 실제 실행에서 선택할 수 있습니다. 키 폐기 후 연결된 구성원 실행도 차단됩니다.</p></div>
        {list.data?.map((item) => <div key={item.id} className="flex items-center gap-4 rounded-2xl bg-white p-5 shadow-card"><div className="grid h-12 w-12 place-items-center rounded-xl bg-emerald-50 font-black text-leaf">✓</div><div><b>{item.provider === "OPENAI" ? "Codex / OpenAI" : item.provider === "ANTHROPIC" ? "Claude / Anthropic" : "Gemini / Google"}</b><p className="text-sm text-stone-500">{item.maskedSecret}</p><p className="mt-1 text-xs font-bold text-leaf">연결 완료 · {item.status}{item.lastVerifiedAt ? ` · ${new Date(item.lastVerifiedAt).toLocaleString("ko-KR")}` : ""}</p></div><button onClick={() => remove.mutate(item.id)} className="ml-auto text-sm font-bold text-red-600">연결 폐기</button></div>)}
        {list.data?.length === 0 && <p className="rounded-3xl border-2 border-dashed p-10 text-center text-stone-500">연결된 API가 없습니다.<br/>Stub으로 설계·실행 흐름은 비용 없이 테스트할 수 있습니다.</p>}
      </section>
    </div>
  </AppShell>;
}
