"use client";

export type CharacterKey = "writer" | "reviewer" | "designer" | "developer" | "manager";
export type AgentVisualStatus = "IDLE" | "WORKING" | "THINKING" | "TOOL" | "SUCCESS" | "WAITING" | "FAILED";

const palettes: Record<CharacterKey, { hair: string; shirt: string; accent: string; skin: string }> = {
  writer: { hair: "#51372f", shirt: "#f27b62", accent: "#ffd166", skin: "#f4c7a1" },
  reviewer: { hair: "#2f343c", shirt: "#4f7cac", accent: "#9ee6cf", skin: "#e7b98f" },
  designer: { hair: "#7b4158", shirt: "#9b6fd0", accent: "#ffb4c8", skin: "#f1c09a" },
  developer: { hair: "#272c33", shirt: "#368f8b", accent: "#89e0dc", skin: "#dca77d" },
  manager: { hair: "#5f3b2f", shirt: "#d29b45", accent: "#fff0b3", skin: "#efbd96" },
};

export const characterLabels: Record<CharacterKey, string> = {
  writer: "코랄 스타일",
  reviewer: "블루 스타일",
  designer: "바이올렛 스타일",
  developer: "민트 스타일",
  manager: "골드 스타일",
};

function normalizedKey(value: string): CharacterKey {
  return value in palettes ? value as CharacterKey : "manager";
}

export function AgentCharacter({ characterKey, status = "IDLE", className = "" }: {
  characterKey: string;
  status?: AgentVisualStatus;
  className?: string;
}) {
  const key = normalizedKey(characterKey);
  const color = palettes[key];
  return <div className={`agent-character agent-character--${status.toLowerCase()} ${className}`} role="img" aria-label={`${characterLabels[key]} 외형 · ${status}`}>
    <svg viewBox="0 0 140 190" aria-hidden="true">
      <ellipse cx="70" cy="181" rx="40" ry="7" fill="#2d2926" opacity=".12" />
      <g className="agent-character__body">
        <path className="agent-character__leg agent-character__leg--left" d="M55 132 48 173 61 173 69 133Z" fill="#34455a" />
        <path className="agent-character__leg agent-character__leg--right" d="M72 133 80 173 93 173 85 132Z" fill="#29394d" />
        <path d="M43 82 Q70 68 97 82 L91 139 Q70 148 49 139Z" fill={color.shirt} />
        <path d="M63 78 70 92 78 78" fill={color.accent} />
        <g className="agent-character__arm agent-character__arm--left">
          <path d="M45 88 Q31 105 27 125" fill="none" stroke={color.shirt} strokeWidth="14" strokeLinecap="round" />
          <circle cx="27" cy="126" r="7" fill={color.skin} />
        </g>
        <g className="agent-character__arm agent-character__arm--right">
          <path d="M95 88 Q109 105 113 125" fill="none" stroke={color.shirt} strokeWidth="14" strokeLinecap="round" />
          <circle cx="113" cy="126" r="7" fill={color.skin} />
        </g>
        <rect x="60" y="67" width="20" height="18" rx="8" fill={color.skin} />
        <circle cx="70" cy="48" r="31" fill={color.skin} />
        <path d="M39 48 Q39 13 72 14 Q101 15 102 49 L94 41 Q80 28 48 37Z" fill={color.hair} />
        {key === "designer" && <path d="M42 31 Q70 5 97 31" fill="none" stroke={color.accent} strokeWidth="6" strokeLinecap="round" />}
        {key === "manager" && <path d="M52 20 Q70 7 89 22 L83 31 58 31Z" fill={color.accent} />}
        <circle cx="59" cy="51" r="3.2" fill="#2d2926" />
        <circle cx="81" cy="51" r="3.2" fill="#2d2926" />
        <path d="M63 64 Q70 69 78 63" fill="none" stroke="#9f5b55" strokeWidth="2.5" strokeLinecap="round" />
        {key === "reviewer" && <g fill="none" stroke="#2d2926" strokeWidth="2"><circle cx="58" cy="51" r="9" /><circle cx="82" cy="51" r="9" /><path d="M67 51h6" /></g>}
        {key === "developer" && <g><rect x="48" y="108" width="44" height="29" rx="3" fill="#263444" /><path d="m63 119-6 5 6 5m14-10 6 5-6 5" fill="none" stroke={color.accent} strokeWidth="2" /></g>}
        {key === "writer" && <g className="agent-character__tool"><rect x="88" y="112" width="26" height="33" rx="3" fill="#fffaf0" stroke="#d6c9ac" /><path d="m92 138 16-22" stroke="#514a46" strokeWidth="4" /></g>}
        {key === "reviewer" && <g className="agent-character__tool"><circle cx="105" cy="123" r="13" fill="none" stroke="#334d6e" strokeWidth="5" /><path d="m114 133 10 12" stroke="#334d6e" strokeWidth="6" strokeLinecap="round" /></g>}
        {key === "designer" && <g className="agent-character__tool"><path d="M92 112q26 4 20 25-12 12-27 0Z" fill="#fff5e8" stroke="#674b5e" strokeWidth="3" /><circle cx="100" cy="124" r="3" fill="#ef6f6c" /><circle cx="109" cy="130" r="3" fill="#55a7a0" /></g>}
        {key === "manager" && <g className="agent-character__tool"><rect x="91" y="110" width="28" height="34" rx="3" fill="#fff" stroke="#8b6a35" strokeWidth="3" /><path d="M97 120h16m-16 8h16m-16 8h10" stroke="#d29b45" strokeWidth="2" /></g>}
      </g>
      {status === "THINKING" && <g className="agent-character__thought" fill="#fff"><circle cx="105" cy="37" r="6" /><circle cx="118" cy="25" r="9" /><circle cx="130" cy="10" r="13" /></g>}
      {status === "SUCCESS" && <g className="agent-character__status"><circle cx="118" cy="24" r="16" fill="#54a96b" /><path d="m110 24 6 6 11-13" fill="none" stroke="white" strokeWidth="4" strokeLinecap="round" /></g>}
      {status === "FAILED" && <g className="agent-character__status"><circle cx="118" cy="24" r="16" fill="#d85e55" /><path d="m111 17 14 14m0-14-14 14" stroke="white" strokeWidth="4" strokeLinecap="round" /></g>}
      {status === "WAITING" && <g className="agent-character__status"><circle cx="118" cy="24" r="16" fill="#e3a83b" /><path d="M118 14v11l7 4" fill="none" stroke="white" strokeWidth="4" strokeLinecap="round" /></g>}
    </svg>
  </div>;
}

export function CharacterPicker({ value, onChange }: { value: string; onChange: (value: CharacterKey) => void }) {
  return <div className="grid grid-cols-5 gap-2" role="radiogroup" aria-label="사람 캐릭터 선택">
    {(Object.keys(characterLabels) as CharacterKey[]).map((key) => <button
      type="button"
      role="radio"
      aria-checked={value === key}
      aria-label={characterLabels[key]}
      key={key}
      onClick={() => onChange(key)}
      className={`rounded-2xl border p-2 transition ${value === key ? "border-coral bg-orange-50 ring-2 ring-coral/20" : "border-stone-200 bg-white hover:border-stone-400"}`}
    >
      <AgentCharacter characterKey={key} className="mx-auto w-full max-w-16" />
      <span className="mt-1 block text-center text-[11px] font-bold">{characterLabels[key]}</span>
    </button>)}
  </div>;
}
