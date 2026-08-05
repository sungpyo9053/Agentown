import Link from "next/link";

const team = [
  { emoji: "✍️", name: "모모", role: "콘텐츠 작가", color: "bg-amber-100" },
  { emoji: "🔎", name: "루루", role: "팩트 검수자", color: "bg-emerald-100" },
  { emoji: "🎨", name: "도도", role: "이미지 디자이너", color: "bg-sky-100" },
];

export default function Home() {
  return (
    <main>
      <section className="mx-auto grid max-w-6xl gap-12 px-6 pb-24 pt-14 lg:grid-cols-[1.05fr_.95fr] lg:items-center">
        <div>
          <span className="rounded-full border border-leaf/20 bg-emerald-50 px-4 py-2 text-sm font-bold text-leaf">AI 팀이 사는 나만의 마을</span>
          <h1 className="mt-7 max-w-2xl text-5xl font-black leading-[1.08] tracking-[-.04em] md:text-7xl">
            함께 일할 AI 팀을<br /><span className="text-coral">사람처럼</span> 만나세요.
          </h1>
          <p className="mt-7 max-w-xl text-lg leading-8 text-stone-600">작가, 검수자, 디자이너를 만들고 미니홈에 배치하세요. 복잡한 노드 대신 익숙한 역할과 순서로 자동화를 완성합니다.</p>
          <div className="mt-9 flex flex-wrap gap-3">
            <Link href="/signup" className="rounded-full bg-coral px-7 py-4 font-bold text-white shadow-card">내 마을 만들기</Link>
            <Link href="/dashboard" className="rounded-full border border-stone-300 bg-white px-7 py-4 font-bold">미리 둘러보기</Link>
          </div>
        </div>
        <div className="relative overflow-hidden rounded-[2.5rem] border-[10px] border-white bg-white shadow-card">
          <div className="room-grid relative aspect-[4/3] overflow-hidden rounded-[1.9rem] p-6">
            <div className="absolute inset-x-0 bottom-0 h-[38%] bg-[#c49b70] [clip-path:polygon(0_25%,100%_0,100%_100%,0_100%)]" />
            <div className="absolute left-8 top-8 rounded-2xl bg-white/85 px-4 py-3 backdrop-blur"><b>콘텐츠 스튜디오</b><br /><small className="text-stone-500">오늘도 아이디어가 자라요</small></div>
            <div className="absolute bottom-[18%] left-[8%] right-[8%] flex items-end justify-around">
              {team.map((member) => <div key={member.name} className="relative z-10 text-center">
                <div className={`mx-auto grid h-20 w-20 place-items-center rounded-[2rem] ${member.color} text-4xl shadow-md`}>{member.emoji}</div>
                <div className="mt-2 rounded-xl bg-white/95 px-3 py-2 text-xs shadow"><b>{member.name}</b><br />{member.role}</div>
              </div>)}
            </div>
          </div>
        </div>
      </section>
      <section className="bg-ink px-6 py-20 text-white">
        <div className="mx-auto grid max-w-6xl gap-6 md:grid-cols-3">
          {[['01','구성원을 만들고','이름, 역할, 말투와 업무 가이드를 알려주세요.'],['02','방에 배치하고','드래그해 나만의 AI 조직을 한눈에 꾸며보세요.'],['03','함께 나누세요','검증한 팀을 공개하고 다른 사람의 팀을 복제하세요.']].map(([n,t,d]) => <article key={n} className="rounded-3xl border border-white/15 p-7"><span className="text-coral">{n}</span><h2 className="mt-8 text-2xl font-bold">{t}</h2><p className="mt-3 text-stone-300">{d}</p></article>)}
        </div>
      </section>
    </main>
  );
}

