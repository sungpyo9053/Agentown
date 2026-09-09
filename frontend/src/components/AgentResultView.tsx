import { useState } from "react";

function decoded(value: unknown): unknown {
  for (let attempt = 0; attempt < 4 && typeof value === "string"; attempt++) {
    try { value = JSON.parse(value); } catch { break; }
  }
  return value;
}

function readable(value: unknown, depth = 0): string {
  if (depth > 12) return JSON.stringify(value, null, 2);
  const item = decoded(value);
  if (item === null || item === undefined) return "없음";
  if (typeof item !== "object") return String(item);
  if (Array.isArray(item)) return item.length ? item.map((entry, index) => `${index + 1}. ${readable(entry, depth + 1)}`).join("\n\n") : "없음";
  const entries = Object.entries(item);
  if (entries.length === 1 && entries[0][0] === "renderedResponse") return readable(entries[0][1], depth + 1);
  return entries.map(([key, entry]) => `${key}\n${readable(entry, depth + 1)}`).join("\n\n");
}

export function AgentResultView({ output }: { output: unknown }) {
  const [copyStatus, setCopyStatus] = useState("");
  const text = readable(output);
  return <section aria-label="읽기 쉬운 실행 결과" className="mt-3 space-y-3">
    <button className="rounded-md border border-hairline px-3 py-1.5 text-xs" onClick={async () => {
      try { await navigator.clipboard.writeText(text); setCopyStatus("복사했습니다."); }
      catch { setCopyStatus("복사하지 못했습니다. 본문을 선택해 복사해 주세요."); }
    }}>결과 복사</button>
    <span role="status" className="ml-2 text-xs text-mute">{copyStatus}</span>
    <pre className="max-h-[32rem] overflow-auto whitespace-pre-wrap break-words rounded-md bg-[#f5f5f2] p-4 text-xs leading-6">{text}</pre>
    <details><summary className="cursor-pointer text-xs text-mute">개발자용 원본 JSON</summary><pre className="mt-2 max-h-72 overflow-auto whitespace-pre-wrap break-words text-[11px]">{JSON.stringify(output, null, 2)}</pre></details>
  </section>;
}
