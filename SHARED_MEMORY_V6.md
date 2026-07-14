# 혼천 공유 메모리 v6

> 현재 Codex-Fable 협업 진입점이다. v5 이전 문서는 이력이다.

## 현재 방향

- 목표 저장소: PostgreSQL
- 완료 단계: PG-001 기준선, PG-002A 초기화·메타 읽기 포트, PG-002B 브리지 저장소 포트, PG-002C 게임 원장 업무 포트, PG-003 트랜잭션·SQL 방언 경계, PG-004 PostgreSQL 구현, PG-005 export/import·검산
- 다음 단계: PG-006 연결 풀·동시성 제어
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

## 규칙

- PostgreSQL 작업은 번호순으로 하나만 활성화한다.
- 상대 작업은 로그와 diff를 검토하되 소유 파일을 임의 수정하지 않는다.
- 한쪽이 없어도 완료 문서의 검증 명령으로 인수할 수 있다.
- DB 비의존 P0만 PostgreSQL 작업과 병렬 진행한다.
