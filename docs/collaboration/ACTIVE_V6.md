# 활성 작업 소유권 v6

| 작업 | 소유자 | 검토자 | 상태 | 소유 파일 | 인계 지점 |
|---|---|---|---|---|---|
| B-004 팩 실물 게이트 감사 | Fable | Codex | 완료, 검토 가능 (2026-07-14) | `tools/pack_gate_audit.py`, self-test | `FABLE_B004_COMPLETE.md` |
| PG-001 영속화 기준선 | Codex | Fable | 완료, 검토 가능 | `tools/persistence_inventory*.py`, PostgreSQL 설계 문서 | `CODEX_PG001_COMPLETE.md` |
| PG-002A 초기화·메타 읽기 포트 | Codex | Fable | 완료, 검토 가능 | `ResetStore.java`, `WorldMetaReader.java`, 포트 감사 | `CODEX_PG002A_COMPLETE.md` |
| PG-002B 브리지 포트 | Codex | Fable | 완료, 검토 가능 | `BridgeStore.java`, 브리지 계약 타입과 포트 | `CODEX_PG002B_COMPLETE.md` |
| PG-002C 게임 원장 포트 | Codex | Fable | 완료, 검토 가능 | `GameStore.java`, 업무별 저장소 포트와 DTO | `CODEX_PG002C_COMPLETE.md` |
| PG-003 트랜잭션·SQL 방언 경계 | Codex | Fable | 완료, 검토 가능 | `TransactionRunner.java`, `SqlDialect.java`, 계약 시험 | `CODEX_PG003_COMPLETE.md` |
| PG-004 PostgreSQL 구현 | Codex | Fable | 다음 작업, 착수 전 | PostgreSQL 스키마, 저장소 구현, 계약 시험 | 실행 환경·의존성 확인부터 시작 |
| 브리지 전달 원자성 | Codex | Fable | 수정 완료, 검토 가능 | `Bridge.java`, `Db.java`, bridge 감사 | `CODEX_BRIDGE_ATOMIC_COMPLETE.md` |

PG-004 이후 작업과 전체 의존성은 `docs/design/postgresql_migration.md`를 따른다. 한 번에 PostgreSQL 전환 작업 하나만 활성화한다.
