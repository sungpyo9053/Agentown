# 작업 브랜치 정리

이번 UI/UX 개편 작업(총 34커밋)을 기능 단위로 끊어 브랜치를 만들어 뒀습니다.
모두 `origin/main`에서 출발해 **순서대로 쌓인(stacked) 구조**입니다.
즉 뒤 브랜치는 앞 브랜치 내용을 모두 포함합니다.

| 순서 | 브랜치 | 누적 커밋 | 내용 |
|---|---|---|---|
| 1 | `feat/company-concept` | 13 | "회사를 운영한다" 컨셉 도입. 대시보드를 회사 만들기 / 팀원 구성하기 / 목표 설정하기 3단계로 재구성, 신규 가입자 온보딩(`/onboarding/company`), 부서 라벨(백엔드 `V10` 마이그레이션 포함), 구독(오피스 월세) 화면, 마켓·일촌 스코프 제외, 로컬 실행 스크립트 |
| 2 | `feat/agentown-rebrand` | 17 | 브랜드를 블록기획 → **Agentown**으로 교체. 슬로건 "I'm a CEO. Everyone has an AI now." / "Assemble your AI team.", 마케팅 헤더·푸터, `/features` `/pricing` `/about` `/contact` 라우트 신설, **Nike 스타일 디자인 시스템**(흑백 토큰·Bebas 디스플레이·무그림자·알약 CTA·새 로고) |
| 3 | `feat/dashboard-sidebar` | 20 | 앱 셸을 좌측 사이드바 구조로 전환. 회사 보드 / Management / Assemble / Setting 4그룹, Management=오피스 관리 · Assemble=직원·가이드·하네스로 역할 분리 |
| 4 | `feat/office-decoration` | 21 | 미니홈피식 회사 꾸미기. 소품 20종 카탈로그, 드래그 배치·크기·각도·레이어, 배경 스킨 7종 |
| 5 | `feat/nav-subpages` | 23 | 하위 메뉴를 각각 독립 페이지로 분리(선택한 메뉴만 표시), 사이드바 접기/펴기(접으면 아이콘만, 상태 기억) |
| 6 | `feat/pixel-office` | 24 | 오피스를 top-down 픽셀 RPG로 교체. 캐릭터 4방향 걷기, 클릭 이동, 실행 상태에 따른 행동(작업 중 착석·타이핑, 승인 대기 말풍선) |
| 7 | `feat/landing-carousel` | 34 | 랜딩을 가로 슬라이드 5장으로 확장(자동 재생·진행 게이지), 한글 줄바꿈(`word-break: keep-all`)과 라틴/한글 행간 분리, 문장 단위 줄바꿈, CTA 위치·크기 정리 |

`UI` 브랜치는 7번(`feat/landing-carousel`)과 동일한 최신 상태입니다.

## 올리는 방법

전부 한 번에 올리려면:

```bash
cd ~/Desktop/agenttown/Agentown
git push origin UI \
  feat/company-concept feat/agentown-rebrand feat/dashboard-sidebar \
  feat/office-decoration feat/nav-subpages feat/pixel-office feat/landing-carousel
```

리뷰를 나눠서 받고 싶다면 순서대로 PR을 올리시면 됩니다.
`feat/company-concept` → main, 그다음 `feat/agentown-rebrand` → `feat/company-concept` …
이런 식으로 base를 앞 브랜치로 지정하면 각 PR의 diff가 해당 기능만 깔끔하게 보입니다.

한 번에 머지해도 된다면 `UI` 하나만 올려서 main으로 PR을 여는 게 가장 간단합니다.

## 확인 완료

- `npx tsc --noEmit` 통과
- `npx eslint src/` 오류 0 (경고 1건은 한글 웹폰트 `<link>` 관련 기존 항목)
- `npx next build` 성공 — 30개 라우트 전부 빌드됨
