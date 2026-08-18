/* 회사 꾸미기용 소품 카탈로그.
   백엔드 room_items(itemType=ASSET, assetKey)에 그대로 저장됩니다.
   width/height는 방 크기 대비 비율(0~1)입니다. */

export type AssetCategory = "furniture" | "plant" | "decor" | "pet";

export type AssetSpec = {
  key: string;
  label: string;
  category: AssetCategory;
  width: number;
  height: number;
  render: () => React.ReactElement;
};

const CATEGORY_LABELS: Record<AssetCategory, string> = {
  furniture: "가구",
  plant: "식물",
  decor: "소품",
  pet: "반려동물",
};

export const assetCategories = Object.entries(CATEGORY_LABELS) as [AssetCategory, string][];

// SVG는 모두 viewBox 0 0 100 100 기준, 바닥에 닿는 형태로 그립니다.
export const officeAssets: AssetSpec[] = [
  {
    key: "desk", label: "책상", category: "furniture", width: 0.16, height: 0.18,
    render: () => <svg viewBox="0 0 100 100"><rect x="6" y="46" width="88" height="14" rx="3" fill="#e8d2a9" /><rect x="6" y="60" width="88" height="7" fill="#9a704e" /><rect x="12" y="67" width="8" height="28" fill="#9a704e" /><rect x="80" y="67" width="8" height="28" fill="#9a704e" /><rect x="34" y="20" width="34" height="23" rx="3" fill="#33414c" /><rect x="38" y="24" width="26" height="15" fill="#9cdbde" /><rect x="47" y="43" width="8" height="6" fill="#33414c" /></svg>,
  },
  {
    key: "chair", label: "의자", category: "furniture", width: 0.08, height: 0.14,
    render: () => <svg viewBox="0 0 100 100"><rect x="22" y="16" width="56" height="40" rx="14" fill="#557f78" /><rect x="18" y="52" width="64" height="16" rx="8" fill="#365c55" /><rect x="46" y="68" width="8" height="18" fill="#4b4b4d" /><rect x="28" y="86" width="44" height="7" rx="3" fill="#4b4b4d" /></svg>,
  },
  {
    key: "sofa", label: "소파", category: "furniture", width: 0.18, height: 0.14,
    render: () => <svg viewBox="0 0 100 100"><rect x="6" y="34" width="88" height="34" rx="12" fill="#e5a665" /><rect x="12" y="24" width="34" height="26" rx="10" fill="#edb579" /><rect x="54" y="24" width="34" height="26" rx="10" fill="#edb579" /><rect x="6" y="62" width="88" height="14" rx="5" fill="#bb7545" /><rect x="14" y="76" width="9" height="12" fill="#8a5530" /><rect x="77" y="76" width="9" height="12" fill="#8a5530" /></svg>,
  },
  {
    key: "meeting-table", label: "회의 테이블", category: "furniture", width: 0.2, height: 0.13,
    render: () => <svg viewBox="0 0 100 100"><ellipse cx="50" cy="48" rx="46" ry="24" fill="#eee0bf" stroke="#b68a5e" strokeWidth="6" /><rect x="34" y="38" width="32" height="18" rx="4" fill="#fff" /><rect x="44" y="70" width="12" height="20" fill="#b68a5e" /><ellipse cx="50" cy="92" rx="22" ry="6" fill="#9a704e" /></svg>,
  },
  {
    key: "bookshelf", label: "책장", category: "furniture", width: 0.12, height: 0.2,
    render: () => <svg viewBox="0 0 100 100"><rect x="8" y="8" width="84" height="88" rx="4" fill="#8b6548" stroke="#6d4a35" strokeWidth="6" /><rect x="16" y="46" width="68" height="5" fill="#6d4a35" /><rect x="20" y="18" width="10" height="26" fill="#f27b62" /><rect x="32" y="22" width="10" height="22" fill="#4f7cac" /><rect x="44" y="16" width="10" height="28" fill="#e3a83b" /><rect x="56" y="24" width="10" height="20" fill="#57a58a" /><rect x="20" y="56" width="10" height="34" fill="#57a58a" /><rect x="32" y="62" width="10" height="28" fill="#f27b62" /><rect x="44" y="58" width="10" height="32" fill="#4f7cac" /></svg>,
  },
  {
    key: "whiteboard", label: "화이트보드", category: "furniture", width: 0.14, height: 0.17,
    render: () => <svg viewBox="0 0 100 100"><rect x="8" y="10" width="84" height="58" rx="3" fill="#fff" stroke="#9e9ea0" strokeWidth="5" /><path d="M20 28 H62 M20 40 H50 M20 52 H68" stroke="#4f7cac" strokeWidth="4" strokeLinecap="round" /><rect x="24" y="68" width="6" height="28" fill="#9e9ea0" /><rect x="70" y="68" width="6" height="28" fill="#9e9ea0" /></svg>,
  },
  {
    key: "coffee-machine", label: "커피머신", category: "furniture", width: 0.08, height: 0.13,
    render: () => <svg viewBox="0 0 100 100"><rect x="20" y="10" width="60" height="76" rx="6" fill="#39393b" /><rect x="28" y="20" width="44" height="20" rx="3" fill="#707072" /><rect x="34" y="50" width="32" height="22" rx="3" fill="#111" /><path d="M42 50 h16 v10 a8 8 0 0 1 -16 0 z" fill="#fff" /><rect x="16" y="86" width="68" height="8" rx="3" fill="#111" /></svg>,
  },
  {
    key: "plant-tall", label: "큰 화분", category: "plant", width: 0.07, height: 0.18,
    render: () => <svg viewBox="0 0 100 100"><path d="M50 62 C30 52 24 30 34 14 C48 20 54 40 50 62Z" fill="#468054" /><path d="M50 62 C70 50 76 28 66 12 C52 20 46 40 50 62Z" fill="#579264" /><path d="M50 64 C50 44 50 26 50 16" stroke="#3d6b47" strokeWidth="4" /><path d="M32 64 h36 l-5 30 h-26 z" fill="#b96e4f" /></svg>,
  },
  {
    key: "plant-small", label: "작은 화분", category: "plant", width: 0.05, height: 0.09,
    render: () => <svg viewBox="0 0 100 100"><circle cx="36" cy="38" r="18" fill="#6aac73" /><circle cx="60" cy="32" r="15" fill="#579264" /><circle cx="52" cy="50" r="16" fill="#468054" /><path d="M34 62 h34 l-4 30 h-26 z" fill="#d98b62" /></svg>,
  },
  {
    key: "cactus", label: "선인장", category: "plant", width: 0.05, height: 0.11,
    render: () => <svg viewBox="0 0 100 100"><rect x="40" y="20" width="20" height="48" rx="10" fill="#57a58a" /><rect x="20" y="34" width="16" height="10" rx="5" fill="#57a58a" /><rect x="20" y="34" width="10" height="24" rx="5" fill="#57a58a" /><rect x="64" y="42" width="16" height="10" rx="5" fill="#57a58a" /><rect x="70" y="42" width="10" height="20" rx="5" fill="#57a58a" /><path d="M32 68 h36 l-4 26 h-28 z" fill="#e3a83b" /></svg>,
  },
  {
    key: "rug", label: "러그", category: "decor", width: 0.18, height: 0.08,
    render: () => <svg viewBox="0 0 100 100"><ellipse cx="50" cy="50" rx="48" ry="30" fill="#d56f5e" /><ellipse cx="50" cy="50" rx="34" ry="20" fill="#eee0bf" /><ellipse cx="50" cy="50" rx="18" ry="10" fill="#d56f5e" /></svg>,
  },
  {
    key: "clock", label: "벽시계", category: "decor", width: 0.06, height: 0.1,
    render: () => <svg viewBox="0 0 100 100"><circle cx="50" cy="50" r="42" fill="#faf5e8" stroke="#fff" strokeWidth="8" /><circle cx="50" cy="50" r="42" fill="none" stroke="#cacacb" strokeWidth="2" /><path d="M50 50 V24" stroke="#4b443e" strokeWidth="6" strokeLinecap="round" /><path d="M50 50 L68 60" stroke="#4b443e" strokeWidth="5" strokeLinecap="round" /></svg>,
  },
  {
    key: "picture", label: "액자", category: "decor", width: 0.08, height: 0.1,
    render: () => <svg viewBox="0 0 100 100"><rect x="8" y="12" width="84" height="76" rx="3" fill="#fff" stroke="#9a704e" strokeWidth="8" /><rect x="20" y="24" width="60" height="52" fill="#9dd7ed" /><path d="M20 76 L44 46 L60 62 L72 52 L80 76 Z" fill="#579264" /><circle cx="66" cy="36" r="7" fill="#e3a83b" /></svg>,
  },
  {
    key: "trophy", label: "트로피", category: "decor", width: 0.05, height: 0.09,
    render: () => <svg viewBox="0 0 100 100"><path d="M30 14 h40 v22 a20 20 0 0 1 -40 0 z" fill="#e3a83b" /><path d="M30 20 h-12 v8 a12 12 0 0 0 12 12" fill="none" stroke="#e3a83b" strokeWidth="6" /><path d="M70 20 h12 v8 a12 12 0 0 1 -12 12" fill="none" stroke="#e3a83b" strokeWidth="6" /><rect x="44" y="56" width="12" height="18" fill="#c98f2c" /><rect x="30" y="74" width="40" height="12" rx="3" fill="#8b6548" /></svg>,
  },
  {
    key: "aquarium", label: "어항", category: "decor", width: 0.09, height: 0.1,
    render: () => <svg viewBox="0 0 100 100"><rect x="10" y="24" width="80" height="56" rx="8" fill="#9dd7ed" stroke="#7fc3dd" strokeWidth="4" /><path d="M10 62 q20 -8 40 0 t40 0 v14 a8 8 0 0 1 -8 8 h-64 a8 8 0 0 1 -8 -8 z" fill="#e8d2a9" /><path d="M38 46 l12 -7 v14 z" fill="#f27b62" /><circle cx="34" cy="46" r="3" fill="#f27b62" /><rect x="16" y="80" width="68" height="10" rx="3" fill="#39393b" /></svg>,
  },
  {
    key: "lamp", label: "스탠드 조명", category: "decor", width: 0.06, height: 0.15,
    render: () => <svg viewBox="0 0 100 100"><path d="M28 10 h44 l10 26 h-64 z" fill="#e3a83b" /><rect x="46" y="36" width="8" height="48" fill="#707072" /><ellipse cx="50" cy="88" rx="24" ry="8" fill="#39393b" /></svg>,
  },
  {
    key: "water-cooler", label: "정수기", category: "decor", width: 0.06, height: 0.13,
    render: () => <svg viewBox="0 0 100 100"><path d="M32 6 h36 v26 h-36 z" fill="#9dd7ed" stroke="#7fc3dd" strokeWidth="3" /><rect x="26" y="32" width="48" height="56" rx="5" fill="#f5f5f5" stroke="#cacacb" strokeWidth="3" /><rect x="40" y="46" width="20" height="8" rx="3" fill="#4f7cac" /><rect x="22" y="88" width="56" height="7" rx="3" fill="#9e9ea0" /></svg>,
  },
  {
    key: "banner", label: "회사 배너", category: "decor", width: 0.07, height: 0.16,
    render: () => <svg viewBox="0 0 100 100"><rect x="24" y="6" width="52" height="72" rx="3" fill="#111" /><path d="M24 78 l26 -14 l26 14 z" fill="#111" /><rect x="34" y="20" width="32" height="6" rx="3" fill="#fff" /><rect x="34" y="32" width="22" height="5" rx="2" fill="#707072" /><rect x="34" y="42" width="28" height="5" rx="2" fill="#707072" /></svg>,
  },
  {
    key: "dog", label: "강아지", category: "pet", width: 0.06, height: 0.08,
    render: () => <svg viewBox="0 0 100 100"><ellipse cx="52" cy="66" rx="32" ry="20" fill="#d9a066" /><circle cx="28" cy="46" r="20" fill="#e0ad76" /><ellipse cx="16" cy="40" rx="7" ry="12" fill="#b57c45" /><circle cx="22" cy="44" r="3" fill="#3b2a1a" /><circle cx="34" cy="44" r="3" fill="#3b2a1a" /><ellipse cx="27" cy="52" rx="5" ry="4" fill="#3b2a1a" /><path d="M82 58 q12 -6 6 -18" stroke="#d9a066" strokeWidth="8" strokeLinecap="round" fill="none" /></svg>,
  },
  {
    key: "cat", label: "고양이", category: "pet", width: 0.06, height: 0.08,
    render: () => <svg viewBox="0 0 100 100"><ellipse cx="54" cy="68" rx="30" ry="18" fill="#9e9ea0" /><circle cx="30" cy="48" r="19" fill="#b4b4b6" /><path d="M14 38 l4 -16 l12 10 z" fill="#b4b4b6" /><path d="M46 38 l-4 -16 l-12 10 z" fill="#b4b4b6" /><circle cx="24" cy="47" r="3" fill="#2f2f2f" /><circle cx="36" cy="47" r="3" fill="#2f2f2f" /><path d="M28 54 h4" stroke="#2f2f2f" strokeWidth="3" strokeLinecap="round" /><path d="M82 62 q14 -10 4 -26" stroke="#9e9ea0" strokeWidth="7" strokeLinecap="round" fill="none" /></svg>,
  },
];

export const assetByKey = new Map(officeAssets.map((asset) => [asset.key, asset]));

export function OfficeAsset({ assetKey }: { assetKey: string }) {
  const asset = assetByKey.get(assetKey);
  if (!asset) return null;
  return asset.render();
}
