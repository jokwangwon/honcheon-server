# Codex 완료 - PG-003 트랜잭션·SQL 방언 경계

## 결과

- `TransactionRunner`가 업무 작업의 커밋, 롤백, 자동 커밋 회복, 중첩 거부 계약을 소유한다.
- 초기화는 수동 `beginTx/endTx` 대신 `inTransaction` 콜백을 사용한다.
- 브리지의 inbox·세계 변경·커서 원자성도 같은 트랜잭션 실행기를 사용한다.
- `SqlDialect`와 `SqliteDialect`를 추가해 JDBC URL, SQLite insert-ignore, 테이블 존재 확인, 스냅숏을 `Db` 밖으로 격리했다.
- 주석을 제외한 `Db` 런타임 코드에는 SQLite 전용 문법이 남지 않았다.
- PostgreSQL 구현과 스키마는 아직 도입하지 않았다.

## 계약

- 성공한 업무는 전체 커밋된다.
- 실패한 업무는 전체 롤백된다.
- 롤백 실패는 원래 예외에 suppressed exception으로 보존된다.
- 트랜잭션 종료 뒤 연결은 자동 커밋 상태로 회복된다.
- 암묵 중첩 트랜잭션은 즉시 거부된다.
- DB별 연결, 멱등 insert, 메타 초기화, 표 조회, 스냅숏은 방언 구현이 담당한다.

## 검증

- `JAVA_HOME=$PWD/run/jdk-21 ./gradlew :server-bot:compileJava` - 성공
- `JAVA_HOME=$PWD/run/jdk-21 ./gradlew :server-bot:jar` - 성공
- `tools/PersistenceContractSelfTest.java` - 실제 SQLite 7/7
- `tools/ResetSelfTest.java` - 실제 SQLite 41/41
- `tools/BridgeDeliverySelfTest.java` - 실제 SQLite 10/10
- `python3 tools/persistence_boundary_audit.py` - 9축 통과
- `python3 tools/persistence_boundary_audit_selftest.py` - 4/4
- 저장소 포트 감사 15축 및 자체시험 5/5 - 성공
- 브리지 감사 자체시험 8/8 - 성공

## 다음 작업 PG-004

PostgreSQL 스키마와 저장소 구현을 추가하고 빈 PostgreSQL에서 같은 포트 계약 시험을 통과시킨다. SQLite 구현은 데이터 이관과 복귀 훈련이 끝날 때까지 제거하지 않는다.
