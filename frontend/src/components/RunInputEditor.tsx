"use client";
import { useState } from "react";
import { RunInputForm } from "@/components/RunInputForm";
import { buildRunInput, runInputDraft, RunInputField, supportsRunForm } from "@/lib/runInput";

export function RunInputEditor({ fields, sample, input, setInput, pending, simulate }: { fields: RunInputField[]; sample: string; input: string; setInput: (value: string) => void; pending: boolean; simulate: () => void }) {
  const [raw, setRaw] = useState(false);
  const form = supportsRunForm(fields) && !raw;
  return <div className="rounded-md border border-hairline bg-white p-3">
    <p className="text-sm font-semibold">실행할 자료 입력</p>
    <p className="mt-1 text-xs leading-5 text-mute">실제 조건과 자료를 입력하세요. 입력한 자료로 사이트에서 실행하며 별도 AI 로그인이 필요하지 않습니다.</p>
    {form ? <RunInputForm fields={fields} pending={pending} initialInput={input} setInput={setInput} /> : <>
      <button disabled={pending} className="mt-2 rounded border border-hairline px-2 py-1 text-xs" onClick={() => setInput(sample)}>입력 예시 채우기</button>
      <p className="mt-2 text-xs text-mute">예시는 입력 형식 안내입니다. 실제 자료로 바꿔 주세요.</p>
      <textarea disabled={pending} aria-label="테스트 입력" value={input} onChange={event => setInput(event.target.value)} placeholder={sample} rows={6} className="mt-2 w-full rounded-md border border-hairline px-3 py-2 text-xs" />
    </>}
    {supportsRunForm(fields) && <button disabled={pending} className="mt-3 text-xs text-mute underline" onClick={() => { setRaw(!raw); setInput(""); }}>{raw ? "항목별 입력으로 전환 (입력 초기화)" : "개발자용 JSON 입력으로 전환 (입력 초기화)"}</button>}
    <button disabled={pending || !input.trim() || (form && buildRunInput(fields, runInputDraft(input)).errors.length > 0)} onClick={simulate} className="mt-3 w-full rounded-md bg-ink py-2.5 text-sm text-white disabled:opacity-35">{pending ? "실행 중…" : "테스트 실행"}</button>
  </div>;
}
