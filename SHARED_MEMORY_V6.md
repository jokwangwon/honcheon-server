# 혼천 공유 메모리 v6

> 현재 Codex-Fable 협업 진입점이다. v5 이전 문서는 이력이다.

## 현재 방향

- ★ **운영 저장소: PostgreSQL** (2026-07-14 16:38 컷오버 완료 — `docs/collaboration/FABLE_CUTOVER_20260714.md`)
- 봇 기동: `scripts/run_bot_pg.sh` (접속 정보 정본: `run/bot/pg.env` · git 밖) · SQLite 는 복귀 보존 (PG-008 전 삭제 금지)
- 완료 단계: PG-001 기준선, PG-002A 초기화·메타 읽기 포트, PG-002B 브리지 저장소 포트, PG-002C 게임 원장 업무 포트, PG-003 트랜잭션·SQL 방언 경계, PG-004 PostgreSQL 구현, PG-005 export/import·검산, PG-006 연결 풀·동시성 제어, PG-007 전환·복귀 훈련
- 다음 단계: PG-008 (SQLite 경로 제거 — 복귀 보존 기간 종료 후, 기간은 사람이 정한다)
- 병렬 디자인 트랙: SJ-000 서장 시네마틱 서책 청사진 완료, 다음 SJ-001 조판 엔진 (`docs/design/seojang_presentation.md`)
- 컷오버 절차: `docs/bot/pg_cutover_runbook.md` (2026-07-14 훈련으로 재현됨)
- ★ 마크 세우기 순서: `docs/design/world_standup_order.md` — D-0 경험 정본 확정됨 (`docs/design/experience_design.md`), 청하현 기립 실행 대기
- ★ RP 공동 평가 개설: `docs/collaboration/RP_REVIEW_BOARD.md` — Fable 평가 완료(`RP_REVIEW_FABLE.md` + 시안 2), **Codex 는 `RP_REVIEW_CODEX.md` 로 평가를 적으라** (몹·스킬: MythicMobs 사용/모방 허용 — 사용자 방향)
- 주 담당 인계 (2026-07-14): PG-005부터 Fable 이 설계·구현·검증·커밋, Codex 는 diff 검토·독립 재시험
- 작업 장부: `docs/collaboration/ACTIVE_V6.md`
- 전환 설계: `docs/design/postgresql_migration.md`
- Codex 브리지 완료: `docs/collaboration/CODEX_BRIDGE_ATOMIC_COMPLETE.md`
- Codex PG-001 완료: `docs/collaboration/CODEX_PG001_COMPLETE.md`
- Codex PG-002A 완료: `docs/collaboration/CODEX_PG002A_COMPLETE.md`
- Codex PG-002B 완료: `docs/collaboration/CODEX_PG002B_COMPLETE.md`
- Fable 병렬 작업: B-004 리소스팩 실물 게이트 감사
- Codex PG-002C 완료: `docs/collaboration/CODEX_PG002C_COMPLETE.md`
- Codex PG-003 완료: `docs/collaboration/CODEX_PG003_COMPLETE.md`
- Codex PG-004 완료: `docs/collaboration/CODEX_PG004_COMPLETE.md`
- Fable B-004 완료: `docs/collaboration/FABLE_B004_COMPLETE.md`
- Fable PG-005 완료: `docs/collaboration/FABLE_PG005_COMPLETE.md`
- Fable PG-006 완료: `docs/collaboration/FABLE_PG006_COMPLETE.md`
- Fable PG-007 완료: `docs/collaboration/FABLE_PG007_COMPLETE.md`
- Codex RP 평가 완료: docs/collaboration/RP_REVIEW_CODEX.md
- ★ Fable 밤 병렬 R6~R8 검토 요청 (커밋 16건 + 대형 트랙 배정 제안): `docs/collaboration/FABLE_R6R8_REVIEW_REQUEST.md` — **Codex 는 이것부터 읽으라** (PG-005~007 검토가 그보다 먼저다)
- ★★ 헌법 개정 (2026-07-15): 경험치·레벨·스탯 포인트 도입 — 정본 `docs/design/cultivation_v3_levels.md` (페이싱 승인됨 · 구현 관문 B-137 저울 재설계). 다음 세션 진입점: `docs/HANDOFF.md` §4-새벽 (병렬 웨이브-1 지도 포함)
- ★★ 헌법 재개정 + v5 대전환 (2026-07-15 주간): §2.4 → 3계층 (지도(건축 포함 정본) → 땅 → 건축) · 평지 밑감+연속 설계+단계 제작 확정 · 정본 `docs/design/map_charter_v5.md` + 화산 문서 셋(사용자 직접 작성) + `docs/design/hwasan_brief_v5.md`
- ✔ 헌장 §3 역반영 순환 완료 (2026-07-15): 요청 `FABLE_CHARTER_S3_REVIEW_REQUEST.md` → Codex 회신 `CODEX_CHARTER_S3_REVIEW.md` (총판정: 그대로 반영 반대 · 관문 6) → Fable 수정·반영 커밋 `4a82053` (관문 1~5). 남긴 것: 관문 6 = B-150(봉인 고지대 눈 + 재건축 2회 멱등) · B-151(hidden 독자) · 브리프 lint 구현 — 코드 후속
- ★★ Fable 웨이브-2 새 미결 인계 + 산출 검토 요청 (2026-07-15 밤): `docs/collaboration/FABLE_WAVE2_MIGYEOL_REQUEST.md` — **Codex 는 이것을 읽으라.** 관문 6(B-150·B-151·brief_lint) 코드 후속은 이 세션에서 섰다(커밋 `6a150c7`·`b4b6e8d`). 새 미결: ① 헌장 §2.3 광역 지형 단계 실행 계약(Q7 — Codex 정본 몫) ② 청하현 해자 CH-13/14/15 형식 ③ 산세 확장 갈래·잠정 노선(기계 ③ 등산로 설계와 한 몸) ④ 경공 일류 진입/램프. 회신: `CODEX_WAVE2_MIGYEOL_REVIEW.md`
- ✔ Codex 회신 도착 (2026-07-15, `CODEX_WAVE2_MIGYEOL_REVIEW.md`): 헌장 §2.3 문안 초안(7항) + CH13 해자 전용 moats 문법·CH15 급수형 수문·산길 ③ 인터페이스 승인·일류=절정 동일 진입. ★독립 검토 판정: **B-151 실패**(access 축 독자 부재 — 비op id 직행이 관문 우회, 조율자 코드 재확인됨) · **B-150 조건부 통과**(true→true 지하 변경 위양성 — 명시적 권한 마스크 필요) · RP-4 표현 통과/측정척도 조건부 실패. Fable 반영: B-151 정정(닫힘 아님)·B-150 조건부 표기·신규 B-156(범위/장소 장부 분리 — 화산 배선 관문)·B-157(RP-4 측정/생명주기). ★사용자 소유 결정 8건(회신 §11): 헌장 §2.3 승인·lift 160 적용시점·CH14 교량묶음·CH15 수문형·③ 방향/경유점·능선 규모·일류 램프 단축·B-151 임시정책(비op id 직행 차단 여부 — G-1 재결정)

## 규칙

- PostgreSQL 작업은 번호순으로 하나만 활성화한다.
- 상대 작업은 로그와 diff를 검토하되 소유 파일을 임의 수정하지 않는다.
- 한쪽이 없어도 완료 문서의 검증 명령으로 인수할 수 있다.
- DB 비의존 P0만 PostgreSQL 작업과 병렬 진행한다.
- ★ Fable 화산 산세 「돌산 인상」 아이디어 요청 (2026-07-16): `docs/collaboration/FABLE_HWASAN_TERRAIN_IDEAS_REQUEST.md` — 사용자가 화산 산세를 도보 3회 보고 "인상이 이상하다". 목표=華山/화산귀환식 다봉 돌산(여러 산 조율적으로 붙음·건물은 봉우리에 감싸인 낮은 품). 여정: 단일원뿔완만→발치볼록→돌산다봉(큰산에봉붙음)→군집(현재 mesa캡+soft-max첨봉5+중앙court118). ★코드 튜닝 아닌 **생성 접근 아이디어** 청함(ridged multifractal·도메인워핑·보로노이·침식근사 등 · 제약: superflat·난수0해시결정론·B-155연속성·평탄건축자리·envelope lift160/r444). 회신: `CODEX_HWASAN_TERRAIN_IDEAS.md`
