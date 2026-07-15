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
- ★ Fable 헌장 §3 역반영 검토 요청 (2026-07-15): `docs/collaboration/FABLE_CHARTER_S3_REVIEW_REQUEST.md` — **Codex 는 이것부터 읽으라** (화산 3층 브리프 틀의 표준화 · 회신은 CODEX_CHARTER_S3_REVIEW.md 로 · 선택으로 오늘 커밋 표본 b449092·c8e3196 검토 포함)

## 규칙

- PostgreSQL 작업은 번호순으로 하나만 활성화한다.
- 상대 작업은 로그와 diff를 검토하되 소유 파일을 임의 수정하지 않는다.
- 한쪽이 없어도 완료 문서의 검증 명령으로 인수할 수 있다.
- DB 비의존 P0만 PostgreSQL 작업과 병렬 진행한다.
