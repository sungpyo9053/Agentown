import { useEffect, useState } from "react";

const labels: Record<string, string> = {
  REQUEST_ACCEPTED: "요청 접수", PROBLEM_DEFINING: "문제 정의와 질문 준비", CODEX_ANALYZING: "역할과 입출력 설계",
  DESIGN_REPAIRING: "검증 결과에 따라 설계 보완", RETRYING: "응답 오류 후 재시도", STRUCTURE_VALIDATING: "구조 검증", DESIGN_SAVING: "버전 저장",
};

export function AgentWorkProgress({ stage, elapsed, executing }: { stage?: string; elapsed?: number; executing: boolean }) {
  const [seconds, setSeconds] = useState(0);
  useEffect(() => { const start = Date.now(); const timer = setInterval(() => setSeconds(Math.floor((Date.now() - start) / 1000)), 1000); return () => clearInterval(timer); }, []);
  return <div role="status">
    <p className="text-sm font-medium">{executing ? "입력 자료로 에이전트를 실행하고 있습니다" : "요청을 처리하고 있습니다"}</p>
    <p className="mt-1 text-xs text-mute">{executing ? "역할별 처리와 결과 검증" : stage ? labels[stage] ?? stage : "서버 응답 대기"} · {elapsed ?? seconds}초 경과</p>
    <p className="mt-2 max-w-sm text-xs leading-5 text-mute">{(elapsed ?? seconds) >= 60 ? "예상보다 오래 걸리고 있습니다. 중복 요청 없이 결과를 기다려 주세요. 완료 시간을 보장할 수는 없습니다." : "역할 수와 검증 과정에 따라 시간이 달라집니다. 처리 단계가 바뀌면 여기에 표시됩니다."}</p>
  </div>;
}
