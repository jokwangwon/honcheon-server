# 🗡 혼천 (混天)

> 강호의 균형이 무너진 시대. 플레이어의 행동이 세계에 흔적을 남기는 무협 RPG 프로젝트.

## 📘 프로젝트 개요

혼천은 **텍스트 기반 무협 RPG를 먼저 만들고, 이후 온라인 멀티플레이 MMORPG로 확장**하는 것을 목표로 하는 게임 프로젝트입니다.

그래픽·실시간 전투·액션 조작부터 만드는 것이 아니라, 텍스트 단계에서 다음 요소들이 실제로 재미있게 작동하는지 먼저 검증합니다.

- 플레이어 자유 행동
- 지역 상태 변화 / NPC 영속성
- 소문 전달 / 세력 반응
- 문파 입문 및 정파·사파·상단·의원·낭인·히든 루트 분기
- 플레이어 선택이 세계에 남기는 흔적

핵심 방향은 **"플레이어가 반드시 강해지는 것만을 목표로 하지 않아도 되는 무협 RPG"**입니다.

```text
내 행동이 세계에 흔적을 남기고,
그 흔적이 다른 플레이어의 세계에도 영향을 주며,
각 플레이어가 강호 속에서 자기만의 삶을 살아가는 것.
```

여러 플레이어가 각자 다른 채팅방에서 플레이하더라도 **하나의 공유 세계 상태**를 사용합니다.

## 📚 기준 문서

| 문서 | 내용 |
|------|------|
| [`docs/design/text_rpg_design.md`](docs/design/text_rpg_design.md) | **프로젝트 기준 기획 문서** — 세계 구조, 세력, 성장/무공/판정/소문/세력 반응 시스템, 청하현 첫 10턴 테스트 전체 |
| [`docs/design/judgment_system.md`](docs/design/judgment_system.md) | 판정 수치 계산식 — 실행력/저항값 공식, 결과 등급, 보정표, 첫 10턴 재계산 검증, 실패 분기 예시 |
| [`docs/design/character_creation.md`](docs/design/character_creation.md) | 캐릭터 생성 — 능력치 배분 규칙, 성향 7종 프리셋, 시작 신분 8종 |
| [`docs/design/design_review.md`](docs/design/design_review.md) | 설계 검토 보고서 — 판정 밸런스 정량 검증, 3갈래 분기 스트레스 테스트, 공백 목록(G1~G8), 퀄리티 평가 |
| [`docs/design/world_reaction_system.md`](docs/design/world_reaction_system.md) | 세계 반응 시스템 — 소문 수치화, 세력 반응 전이표, 지역 상태 변화량, 시간 5구간제, 경로 B 기계 재생 검증 |
| [`docs/design/gm_modifier_guide.md`](docs/design/gm_modifier_guide.md) | GM 보정 예시집 — 상황 보정 부여 가드레일 (LLM GM 프롬프트 자료) |
| [`docs/design/fortune_and_wanderer.md`](docs/design/fortune_and_wanderer.md) | 낭인 생태계 + 범인(凡人) 시작 + 기연(奇緣) 시스템 — 일반인이 문파에 닿는 세 경로 |
| [`docs/design/combat_system.md`](docs/design/combat_system.md) | 전투 상세 규칙 — 라운드/공방/피해/부상/내력/도주/살상 선택, NPC간 약식 규칙, 기존 테스트 재계산 검증 |
| [`docs/design/minecraft_port_feasibility.md`](docs/design/minecraft_port_feasibility.md) | 마인크래프트 이식 타당성 — 시스템별 이식 지도, 3대 전환(액션 전투/프롤로그 인스턴스/템플릿 서사), 로드맵 |
| [`docs/design/training_and_time.md`](docs/design/training_and_time.md) | 수련과 시간 — 자동 세계 달력에서의 압축 성장(수련 상태=몽타주), F7 환산표, 수련 중 세계 개입 |
| [`docs/design/map_generation.md`](docs/design/map_generation.md) | AI 맵 생성 파이프라인 — "맵은 컴파일한다", 4계층(지형/구조/배치/바인딩), AI 역할 분담, 로드맵 |
| [`docs/design/platform_decision.md`](docs/design/platform_decision.md) | 플랫폼 결정 — Paper 1.21.4 + 서버 리소스팩(바닐라 접속), 자체 모드는 후순위 선택 옵션 |
| [`docs/design/performance_and_netcode.md`](docs/design/performance_and_netcode.md) | 성능·통신 품질 — 장애 유형 예측 15종(F-목록)과 방어책, 스킬 8단계 파이프라인, 예산·부하 테스트 기준 |
| [`docs/design/world_capacity.md`](docs/design/world_capacity.md) | 세계 정원 — 동접 상한(기술)과 세계 정원(디자인)의 분리, 평행 강호, 인구-사건 스케일링 |
| [`docs/design/skill_mechanics.md`](docs/design/skill_mechanics.md) | 스킬 메커니즘 — 히트박스 6유형, 회피 3단 체계, 무적 상한·상성 삼각, 프레임 데이터 예시 6종 |
| [`docs/design/internal_energy.md`](docs/design/internal_energy.md) | 내공 시스템 v2 — 삼원 구조: 선천진기(수명 100년 균등)·원기(생명력, 역혈=원기 증폭)·후천진기(내력), 두 개의 죽음, 마공 분류 기준 |
| [`docs/design/dantian_and_simbeop.md`](docs/design/dantian_and_simbeop.md) | 단전 시각화와 심법 — 그릇 모델(용량·순도·결), 축기 방식 6종 분리, 정순 내공의 정화력(배독·도인도기·해주), 겸수·전환 |
| [`docs/design/balance_audit_cliffs.md`](docs/design/balance_audit_cliffs.md) | 저수치 절벽 감사 — 판정은 정수/자원은 실수/벽은 벽 대원칙, 파생치 전수 처분표 |
| [`docs/design/ultimate_arts.md`](docs/design/ultimate_arts.md) | 오의 시스템 — 경지 4계단(개안→완성→자재→창작), 발동권(흐름), 오의 격돌, 창작 오의 예산 12점+명명, 전승 오의 4종 |
| [`docs/design/age_and_lifepath.md`](docs/design/age_and_lifepath.md) | 시작 나이와 인생 진행 v2 — 전원 유년/소년 시작, 집안·발단 사건 생성, 장(章) 단위 시간 도약(사건이 나이를 먹인다) |
| [`docs/gm/gm_master_prompt.md`](docs/gm/gm_master_prompt.md) | GM 마스터 프롬프트 v2 — 통합 런타임: 생성 절차, 턴 13단계, 전문 절차(전투/독/정화/오의/장 종결/기연), 절대 규칙 |
| [`docs/playtests/PT-001.md`](docs/playtests/PT-001.md) | 첫 실주사위 플레이 테스트 — 턴 3에서 각본과 분기, 실패 연쇄에서도 진행 유지 검증, 발견 과제 F1~F4 |
| [`docs/playtests/PT-002.md`](docs/playtests/PT-002.md) / [`PT-003.md`](docs/playtests/PT-003.md) | 유년 시작·발단·시간 도약 검증(F5~F7) / 내공·심법 규칙 스모크 테스트(F8~F10) |
| [`docs/playtests/PT-004.md`](docs/playtests/PT-004.md) / [`PT-005.md`](docs/playtests/PT-005.md) | 고경지 통합 검증(F11~F12) / 첫 사용자 인터랙티브 세션(F13~F16, 재개 정보 포함) |
| [`docs/design/runtime_architecture.md`](docs/design/runtime_architecture.md) | 런타임 아키텍처 — 2단 파이프라인(엔진 계산→LLM 렌더)·프리페치, 실시간 판정=이벤트 훅, 시간 도약의 운영 등가물과 동기화 규칙 |
| [`docs/story_summary.md`](docs/story_summary.md) | 세계관 및 메인 스토리 요약 (정파/사파/마교/혈교) |

## ⚔ 핵심 시스템 요약

- **세력 구조**: 정파(구파일방·오대세가) / 사파·흑도(하오문·녹림·장강수로채·살막) / 사교·금기(마교·혈교) / 관군·관청 / 상단·민간·정보상 — 단순 정사 대립이 아닌 5계열 이해관계 구도
- **경지**: 삼류 → 이류 → 일류 → 절정 → 초절정 → 화경 → 현경 → 생사경
- **성장 4축**: 신체 성장 / 무공 성장 / 사회적 성장 / 세계 영향 성장
- **무공**: 데이터 단위 관리. 무공은 전투 기술이자 신분과 출신을 드러내는 장치 (공개 사용 → 소문 → 세력 반응)
- **판정**: 위험·저항·숨겨진 정보가 있을 때만. 플레이어 실행력 vs NPC 저항값 비교
- **시간**: 개인 시간 / 지역 누적 시간 / 세계 공통 시간 3계층
- **시작 지역**: 청하현 — 묵삼 사건, 북쪽 산길 도적, 열병 소문 3개 초기 사건

## 🏗 저장소 구조

```plaintext
honcheon-server/
├── docs/
│   ├── design/text_rpg_design.md   # ★ 기준 기획 문서
│   └── story_summary.md            # 세계관 요약
├── config/
│   ├── factions.yml                # 5계열 세력 구조 + 세력 반응 단계
│   ├── cultivation.yml             # 경지 체계 (삼류 ~ 생사경)
│   ├── skills.yml                  # 무공 카탈로그 (스키마 + 예시)
│   ├── judgment.yml                # 판정 계산 규칙 (공식, 등급, 보정, 행동 대응표)
│   ├── player_creation.yml         # 캐릭터 생성 규칙 (배분, 성향 프리셋, 시작 신분)
│   ├── rumor.yml                   # 소문 생성/전파/왜곡/소멸 + 소문망 6종 + NPC 기억 태그
│   ├── faction_reaction.yml        # 세력 반응 점수/전이 임계값/입력표/대상 추적
│   ├── region_state.yml            # 지역 상태 변화량/회복/임계 효과
│   ├── time.yml                    # 하루 5구간제, 행동 시간 비용, 데드라인 규칙
│   ├── gm_modifiers.yml            # GM 상황 보정 가드레일
│   ├── regions/cheongha_hyeon.yml  # 청하현 지역 상태 / 장소 / 초기 사건
│   └── npcs/cheongha_npcs.yml      # 청하현 주요 NPC + 흑랑/갈호/진운
├── core/ server-main/ server-hub/ server-faction/
│   # (레거시) 마인크래프트 플러그인 스캐폴딩 — MMORPG 확장 단계 검토 대상
├── scripts/ docker/ datapack/ resources/
│   # (레거시) 마인크래프트 서버 실행 환경
└── auto_git/                       # AI 커밋 메시지 생성 도구 (requirements.txt는 이 도구용)
```

> ⚠ **폐기된 설정**: 과거 저장소에 있던 원소 속성 4문파(청운문/화염문/태산문/수월문)와
> 그에 딸린 스킬 설정은 잘못 생성된 것으로 폐기되었습니다. `core/`, `server-*/` 등
> 마인크래프트 플러그인 코드에 남아 있는 관련 참조는 MMORPG 확장 단계에서
> 기준 기획 문서에 맞게 재작성해야 합니다.

## 🧭 개발 단계

1. **텍스트 RPG 단계 (현재)** — 자유 행동, 판정, 소문, 세력 반응, NPC 영속성, 문파 입문 루트가 재미있게 작동하는지 검증. 청하현 첫 10턴 대화형 테스트 완료.
2. **MMORPG 확장 단계** — 검증된 구조를 서버, DB, UI, 실시간 전투, 지역 시스템, NPC 스케줄, 세력 이벤트로 이전.

## 📜 라이선스

MIT License

## 👤 제작

개발 및 기획: [@jokwangwon](https://github.com/jokwangwon) (Solo Dev)

> "혼돈의 하늘 아래, 누가 패왕이 될 것인가."
