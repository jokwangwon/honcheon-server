# 활성 작업 소유권 v6

| 작업 | 소유자 | 검토자 | 상태 | 소유 파일 | 인계 지점 |
|---|---|---|---|---|---|
| B-004 팩 실물 게이트 감사 | Fable | Codex | 완료, 검토 가능 (2026-07-14) | `tools/pack_gate_audit.py`, self-test | `FABLE_B004_COMPLETE.md` |
| SJ-000 서장 시네마틱 서책 청사진 | Codex | Fable | 설계 완료, 구현 착수 전 | `docs/design/seojang_presentation.md`, 디자인 로드맵 포인터 | 다음: SJ-001 조판 엔진 |
| PG-001 영속화 기준선 | Codex | Fable | 검토 통과, 커밋 a2c482a | `tools/persistence_inventory*.py`, PostgreSQL 설계 문서 | `CODEX_PG001_COMPLETE.md` |
| PG-002A 초기화·메타 읽기 포트 | Codex | Fable | 검토 통과, 커밋 a2c482a | `ResetStore.java`, `WorldMetaReader.java`, 포트 감사 | `CODEX_PG002A_COMPLETE.md` |
| PG-002B 브리지 포트 | Codex | Fable | 검토 통과, 커밋 a2c482a | `BridgeStore.java`, 브리지 계약 타입과 포트 | `CODEX_PG002B_COMPLETE.md` |
| PG-002C 게임 원장 포트 | Codex | Fable | 검토 통과, 커밋 a2c482a | `GameStore.java`, 업무별 저장소 포트와 DTO | `CODEX_PG002C_COMPLETE.md` |
| PG-003 트랜잭션·SQL 방언 경계 | Codex | Fable | 검토 통과, 커밋 a2c482a | `TransactionRunner.java`, `SqlDialect.java`, 계약 시험 | `CODEX_PG003_COMPLETE.md` |
| PG-004 PostgreSQL 구현 | Codex | Fable | 검토 통과, 커밋 a2c482a | PostgreSQL 스키마, 방언, 런타임 선택, 계약 시험 | `CODEX_PG004_COMPLETE.md` · 검토: `FABLE_PG004_REVIEW.md` |
| PG-005 SQLite export/import·검산 | Fable | Codex | 완료, Codex 검토 대기 (2026-07-14) | `tools/PgMigrate.java`, `PgMigrateSelfTest.java`, `pg_migration_audit*.py` | `FABLE_PG005_COMPLETE.md` (재현 명령 포함 · 리허설 컨테이너 살아 있음) |
| PG-006 연결 풀·동시성 제어 | Fable | Codex | 완료, Codex 검토 대기 (2026-07-14) | `ConnectionSource*` 5파일, `Db.java` 직렬화 제거, `PgConcurrencySelfTest.java`, `pg_concurrency_audit*.py` | `FABLE_PG006_COMPLETE.md` |
| PG-007 스테이징 전환·복귀 훈련 | Fable | Codex | 완료, Codex 검토 대기 (2026-07-14) | `docs/bot/pg_cutover_runbook.md`, 훈련 기록 | `FABLE_PG007_COMPLETE.md` · 기록: `FABLE_PG007_DRILL.md` |
| B-102 세계 상태 발행 수리 | Fable | Codex | 완료, Codex 검토 대기 (커밋 75b5cfd) | `Rules.java`, `Bridge.java` | BACKLOG B-102 닫힘 · 재현 하네스로 진단 |
| ★ 운영 컷오버 | Fable (결정: 사람) | Codex | **전환 완료 (2026-07-14 16:38)** | `scripts/run_bot_pg.sh`, `run/bot/pg.env`(git 밖) | `FABLE_CUTOVER_20260714.md` — 운영 DB 는 이제 PostgreSQL |
| PG-008 SQLite 경로 제거 | Fable | Codex | 보존 기간 종료 대기 (기간은 사람이 정한다) | SQLite 경로, 문서 갱신 | 설계 문서 표 · 컷오버 기록 §남은 일 |
| B-005 평타 대립 판정 | Fable-병렬 R1 | Codex | 완료·커밋 (후속 B-105 신설) | `SkillListener.java` basicJudged, `config/combat.yml` | npcStrike 와 같은 판정층 — 새 수치 0개, 전 항이 기존 등록부 출처 |
| B-016·017 대화 줄서기·폴백 표식 | Fable-병렬 R1 | Codex | 완료·커밋 ab67846 | `GameListener.java` 대화부, `config/llm.yml`, `Scribe.chat()` 추가(+43/−0) | ★Codex 에게: write/chat 동형 중복 — 서장 트랙에서 write 폴백 규칙 바꾸면 chat 도 함께. 소유권 풀리면 공통부 접기 권장 |
| B-008 game_audit 위반 4건 | Fable-병렬 R1 | Codex | 완료·커밋 1cce4fc (후속 B-104 신설) | `config/npcs/cheongha_npcs.yml`, `tools/game_audit.py` | schema.sql 어휘 대조로 오독 교정 — 하드코딩 예외 아님 |
| SJ-001 조판 엔진 (BookLayout) | Fable-병렬 R1 | Codex | 완료·검토 대기 | `BookLayout.java`, `SeojangBook.java`, `BookLayoutSelfTest`(35눈) | `FABLE_SJ001_COMPLETE.md` — 픽셀 상수 실측 확정은 SJ-002 실기동 눈으로 |
| SJ-002 장 전환·리소스팩 자산 | Fable | Codex | 다음 (R2 후보) | 청사진 §SJ-002 | 팩 게이트 결선은 pack_gate_audit(Fable)과 조율 |
| B-026·027·028 등록부·감사 정합 | Fable-병렬 R1 | Codex | 완료·커밋 (후속 B-106 신설) | `SkillDisplay.java`, `skill_motion.yml`, `performance.yml`, motion/perf_audit + selftest 2 신설 | 죽은 예산 5건은 눈이 멀었던 것 — 간접 배선을 눈에 가르침 |
| 브리지 전달 원자성 | Codex | Fable | 검토 통과, 커밋 a2c482a | `Bridge.java`, `Db.java`, bridge 감사 | `CODEX_BRIDGE_ATOMIC_COMPLETE.md` |
| ★ 밤 병렬 R6~R8 (B-113~124·SJ-002·v4 조성기·사고 수리) | Fable | Codex | **검토 완료 — 조건부 승인** (2026-07-15): 14건 통과 · B-119 이행 누락(P1)·B-116 전역 소유권(P2)은 열림 유지·조건 보강 · 조언은 B-129·130 등재 | `TerrainForge.java`, `Antechamber.java`, `SkillListener.java`, `HudLine`, `SeojangBook.java` 외 — 커밋 목록이 정본 | `CODEX_REVIEW.md` §R6~R8 |
| B-110 세계 시계 구현 | Fable | Codex | 수락됨 — 착수 가능 | `WorldClockEngine`(신설), `config/world_clock.yml` 배선 | `FABLE_R6R8_REVIEW_REQUEST.md` §3 · 설계: `docs/design/world_clock.md` |
| SJ-003 선택·판정 피드백 | Codex | Fable | 수락됨 — PG-005~007 검토 뒤 착수 | `docs/design/seojang_presentation.md` §SJ-003 다리 계약 설계 | `FABLE_R6R8_REVIEW_REQUEST.md` §3 |
| B-122 잠행 설계 | Codex | Fable | 수락됨 — B-119 이행 보완 뒤 착수 | `docs/design/experience_design.md` 잠행 절(신설) | `FABLE_R6R8_REVIEW_REQUEST.md` §3 · 의존 B-119 |
| 상단 6곳 시안 (B-047·D-2) | Fable | 사람 (결정) | 수락됨 — 착수 가능 | 시안 문서(신설) — 등록부 반영은 사용자 승인 뒤 | `FABLE_R6R8_REVIEW_REQUEST.md` §3 |

PG-005 이후 작업과 전체 의존성은 `docs/design/postgresql_migration.md`를 따른다. 한 번에 PostgreSQL 전환 작업 하나만 활성화한다.
