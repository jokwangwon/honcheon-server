# Codex 완료 - PG-002A 초기화·메타 읽기 포트

## 결과

- `ResetStore`가 초기화의 백업, 범위 조회·삭제, 트랜잭션, 감사 기록 계약을 소유한다.
- `Reset`은 구체 `Db`를 더 이상 참조하지 않는다.
- 자정 스케줄러는 `WorldMetaReader`만 받아 채널 메타를 읽는다.
- 현재 SQLite `Db`가 두 포트를 구현하며 SQL과 런타임 동작은 바꾸지 않았다.
- PG-001 인벤토리의 주석 오탐을 수정했다. `Rules`와 `Rumors`의 Javadoc은 실제 소비자가 아니다.

## 결합도 변화

- 실제 직접 `Db` 소비자: 4개 → 3개
- 남은 소비자: composition root `HoncheonBot`, `Bridge`, `GameListener`
- 새 포트: `ResetStore`, `WorldMetaReader`
- `synchronized`와 SQL 실행 지점은 그대로다. 이번 단계는 동시성 변경이 아니다.

## 검증

- `JAVA_HOME=$PWD/run/jdk-21 ./gradlew :server-bot:compileJava` - 성공
- `python3 tools/persistence_port_audit.py` - 6축 통과
- `python3 tools/persistence_port_audit_selftest.py` - 3/3
- `python3 tools/persistence_inventory_selftest.py` - 9/9
- `tools/ResetSelfTest.java` - 실제 SQLite 41/41
- Python compileall 및 관련 파일 `git diff --check` - 성공

## 다음 작업 PG-002B

`Bridge`가 요구하는 inbox·커서·신원·세계 스냅숏 조회를 브리지 업무 포트로 분리한다. 브리지 트랜잭션 원자성을 유지하며 `Db.LinkRequest`, `Db.Debt` 같은 구체 DTO 결합을 먼저 공용 계약 타입으로 옮긴다.
