import Link from "next/link";
import {SessionNav} from "@/components/SessionNav";

const team = [
  { initial: "모", name: "모모", role: "콘텐츠 작가", status: "작성 중", accent: true },
  { initial: "루", name: "루루", role: "팩트 검수자", status: "대기" },
  { initial: "도", name: "도도", role: "이미지 디자이너", status: "대기" },
];

export default function Home() {
  return (
    <main>
      <header className="mx-auto flex max-w-6xl items-center justify-between px-6 py-6"><Link href="/" className="text-lg font-semibold tracking-tight">Agentown</Link><nav className="flex items-center gap-5 text-sm font-medium"><Link href="/dashboard" className="text-zinc-600 hover:text-ink">내 AI 회사</Link><SessionNav/></nav></header>
      <section className="mx-auto grid max-w-6xl gap-12 px-6 pb-24 pt-14 lg:grid-cols-[1.05fr_.95fr] lg:items-center">
        <div>
          <span className="rounded-full border border-coral/20 bg-coral/5 px-4 py-1.5 text-xs font-semibold uppercase tracking-wide text-coral">AI 팀이 일하는 나만의 회사</span>
          <h1 className="mt-7 max-w-2xl text-5xl font-semibold leading-[1.12] tracking-tight md:text-6xl">
            함께 일할 AI 팀을<br /><span className="text-coral">사람처럼</span> 만나세요.
          </h1>
          <p className="mt-7 max-w-xl text-lg leading-8 text-zinc-600">하고 싶은 일을 설명하면 설계 AI가 필요한 구성원, 가이드와 실행 순서를 제안합니다. 승인한 AI 회사를 오피스에 배치하고 실제 업무를 실행하세요.</p>
          <div className="mt-9 flex flex-wrap gap-3">
            <Link href="/signup" className="rounded-lg bg-coral px-6 py-3 font-semibold text-white transition hover:bg-coral/90">내 AI 회사 만들기</Link>
            <Link href="/dashboard" className="rounded-lg border border-zinc-200 bg-white px-6 py-3 font-semibold text-ink hover:border-zinc-300">미리 둘러보기</Link>
          </div>
        </div>
        <div className="overflow-hidden rounded-2xl border border-zinc-200 bg-white">
          <div className="flex items-center gap-2 border-b border-zinc-200 px-5 py-3">
            <span className="h-2.5 w-2.5 rounded-full bg-zinc-200" /><span className="h-2.5 w-2.5 rounded-full bg-zinc-200" /><span className="h-2.5 w-2.5 rounded-full bg-zinc-200" />
            <span className="ml-2 text-xs font-medium text-zinc-500">콘텐츠 스튜디오</span>
          </div>
          <div className="space-y-2 p-5">
            {team.map((member) => <div key={member.name} className="flex items-center gap-3 rounded-lg border border-zinc-100 bg-zinc-50/60 px-4 py-3">
              <div className={`grid h-9 w-9 shrink-0 place-items-center rounded-full text-sm font-semibold ${member.accent ? "bg-coral/10 text-coral" : "bg-zinc-100 text-zinc-600"}`}>{member.initial}</div>
              <div className="min-w-0 flex-1"><b className="block text-sm">{member.name}</b><span className="text-xs text-zinc-500">{member.role}</span></div>
              <span className={`shrink-0 rounded-full px-2.5 py-1 text-xs font-medium ${member.accent ? "bg-coral/10 text-coral" : "bg-zinc-100 text-zinc-500"}`}>{member.status}</span>
            </div>)}
          </div>
        </div>
      </section>
      <section className="bg-ink px-6 py-20 text-white">
        <div className="mx-auto grid max-w-6xl gap-6 md:grid-cols-3">
          {[['01','구성원을 만들고','이름, 역할, 말투와 업무 가이드를 알려주세요.'],['02','방에 배치하고','드래그해 나만의 AI 조직을 한눈에 꾸며보세요.'],['03','함께 나누세요','검증한 팀을 공개하고 다른 사람의 팀을 복제하세요.']].map(([n,t,d]) => <article key={n} className="rounded-2xl border border-white/10 p-7"><span className="text-sm font-semibold text-coral">{n}</span><h2 className="mt-8 text-xl font-semibold">{t}</h2><p className="mt-3 text-sm text-zinc-400">{d}</p></article>)}
        </div>
      </section>
    </main>
  );
}
