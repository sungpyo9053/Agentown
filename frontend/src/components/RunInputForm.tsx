"use client";
import { useState } from "react";
import { buildRunInput, runInputDraft, RunInputField } from "@/lib/runInput";

export function RunInputForm({ fields, pending, initialInput, setInput }: { fields: RunInputField[]; pending: boolean; initialInput: string; setInput: (value: string) => void }) {
  const [draft, setDraft] = useState<Record<string, string>>(() => runInputDraft(initialInput));
  const [touched, setTouched] = useState(false);
  const result = buildRunInput(fields, draft);
  const style = "mt-1 w-full rounded-md border border-hairline bg-white px-3 py-2 text-sm font-normal";
  function change(name: string, value: string) {
    const next = { ...draft, [name]: value };
    setDraft(next); setTouched(true);
    const parsed = buildRunInput(fields, next);
    // Keep partial drafts across tab changes; the editor disables execution until valid.
    setInput(JSON.stringify(parsed.value));
  }
  return <div className="mt-3 space-y-4">
    {fields.map(field => <label key={field.name} className="block text-xs font-semibold">
      {field.description || field.name}{field.required ? " (필수)" : " (선택)"}
      {field.type === "boolean" || field.enumValues?.length ? <select disabled={pending} className={style} value={draft[field.name] ?? ""} onChange={event => change(field.name, event.target.value)}><option value="">선택해 주세요</option>{field.type === "boolean" ? <><option value="true">예</option><option value="false">아니요</option></> : field.enumValues!.map(value => <option key={value} value={value}>{value}</option>)}</select>
        : ["number", "integer"].includes(field.type) ? <input disabled={pending} type="number" step={field.type === "integer" ? 1 : "any"} min={field.minimum} max={field.maximum} className={style} value={draft[field.name] ?? ""} onChange={event => change(field.name, event.target.value)} />
        : <textarea disabled={pending} rows={3} className={style} value={draft[field.name] ?? ""} placeholder={field.type === "array" ? "한 줄에 하나씩 입력해 주세요." : "실제 조건이나 자료를 입력해 주세요."} onChange={event => change(field.name, event.target.value)} />}
    </label>)}
    {touched && result.errors.length > 0 && <p role="status" className="text-xs text-amber-800">{result.errors[0]}</p>}
  </div>;
}
