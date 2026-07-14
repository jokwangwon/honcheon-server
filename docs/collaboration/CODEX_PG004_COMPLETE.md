# Codex 완료 - PG-004 PostgreSQL 구현

## 결과

- PostgreSQL 16용 통합 스키마 20개 표를 `db/postgresql/schema.sql`에 추가했다.
- `PostgresqlDialect`가 JDBC 연결, 멱등 insert, 표 조회와 반복 읽기 논리 스냅숏을 담당한다.
- `Db.open(environment)`가 `HONCHEON_DB_BACKEND`에 따라 SQLite 또는 PostgreSQL을 선택한다.
- `HoncheonBot` 조합 루트 외의 런타임 코드는 구체 `Db`를 직접 소비하지 않는다.
- PostgreSQL JDBC 드라이버를 봇 런타임 의존성에 포함하고 명시적으로 등록한다.
- SQLite는 기본 백엔드와 복귀 경로로 그대로 유지한다.

## 운영 계약

- PostgreSQL URL은 `jdbc:postgresql:` 형식이며 `HONCHEON_DATABASE_URL`이 필수다.
- 통합 스키마는 `BIGSERIAL`, `TIMESTAMPTZ`, `ON CONFLICT` 등 PostgreSQL 문법만 사용한다.
- 업무 성공은 커밋되고 실패는 롤백되며 브리지 inbox·세계 변경·커서 원자성을 유지한다.
- 스냅숏은 `REPEATABLE READ` 읽기 전용 트랜잭션에서 모든 표를 CSV ZIP으로 묶는다.
- PG-005 importer 전에는 PostgreSQL `/초기화` 스냅숏의 자동 복원을 지원하지 않는다.
- 단일 연결과 전역 직렬화는 PG-006에서 제거하며, PG-007 전까지 운영 전환하지 않는다.

## 검증

- `JAVA_HOME=$PWD/run/jdk-21 ./gradlew :server-bot:jar` - 성공
- PostgreSQL 16 Alpine 빈 컨테이너의 `PostgresqlContractSelfTest` - 10/10
- `PersistenceContractSelfTest` 실제 SQLite - 7/7
- `ResetSelfTest` 실제 SQLite - 41/41
- `BridgeDeliverySelfTest` 실제 SQLite - 10/10
- `python3 tools/postgresql_audit.py` - 10축 통과
- `python3 tools/postgresql_audit_selftest.py` - 4/4
- `python3 tools/persistence_boundary_audit.py` - 9축 통과
- `python3 tools/persistence_boundary_audit_selftest.py` - 4/4
- `python3 tools/persistence_port_audit.py` - 15축 통과
- `python3 tools/persistence_port_audit_selftest.py` - 5/5

재현 명령:

```bash
JAVA_HOME="$PWD/run/jdk-21" ./gradlew :server-bot:jar

run/jdk-21/bin/javac \
  -cp server-bot/build/libs/server-bot-0.1.0.jar \
  -d /tmp/honcheon-pg004-sqlite-test \
  tools/PersistenceContractSelfTest.java tools/ResetSelfTest.java tools/BridgeDeliverySelfTest.java
for test in PersistenceContractSelfTest ResetSelfTest BridgeDeliverySelfTest; do
  run/jdk-21/bin/java \
    -cp /tmp/honcheon-pg004-sqlite-test:server-bot/build/libs/server-bot-0.1.0.jar \
    "com.honcheon.bot.$test"
done

docker run --rm -d --name honcheon-pg004-test \
  -e POSTGRES_USER=honcheon -e POSTGRES_PASSWORD=contract -e POSTGRES_DB=honcheon \
  -p 127.0.0.1:55432:5432 postgres:16-alpine
until docker exec honcheon-pg004-test pg_isready -U honcheon -d honcheon; do sleep 1; done
run/jdk-21/bin/javac -cp server-bot/build/libs/server-bot-0.1.0.jar \
  -d /tmp/honcheon-pg004-test tools/PostgresqlContractSelfTest.java
run/jdk-21/bin/java -cp /tmp/honcheon-pg004-test:server-bot/build/libs/server-bot-0.1.0.jar \
  com.honcheon.bot.PostgresqlContractSelfTest \
  jdbc:postgresql://127.0.0.1:55432/honcheon honcheon contract
docker rm -f honcheon-pg004-test
```


## 다음 작업 PG-005

SQLite 원본을 읽어 PostgreSQL로 적재하는 일회성 export/import 도구를 만든다. 표별 행 수,
외래키, 캐릭터 전낭·은행 합계, 세계 메타, 브리지 멱등 키를 양쪽에서 검산하고 실패 시
PostgreSQL 변경을 롤백한다. PG-005 이관, PG-006 동시성 제어, PG-007 전환·복귀 훈련이
모두 닫히기 전에는 운영 전환이나 SQLite 제거를 하지 않는다.
