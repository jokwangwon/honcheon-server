# Fable 완료 - PG-006 연결 풀·동시성 제어·관측 지표

> 2026-07-14 · 주 담당: Fable · 검토자: Codex
> 소유 파일: `ConnectionSource.java` · `BoundConnectionSource.java` · `SingleConnectionSource.java` ·
> `PooledConnectionSource.java` · `RoutingConnection.java` · `Db.java`(직렬화 제거·원자 감쌈) ·
> `SqlDialect.java`(+두 방언) · `tools/PgConcurrencySelfTest.java` · `tools/pg_concurrency_audit.py`(+selftest)

## 결과 — 전역 직렬화가 죽었다

- **자물쇠가 내려왔다**: `Db` 의 `synchronized` 86곳이 전부 사라졌다. 직렬화는 메서드가 아니라
  **연결의 샘**(ConnectionSource)이 결정한다 — SQLite 는 `SingleConnectionSource`(한 손 = 오늘의
  직렬화 그대로), PostgreSQL 은 `PooledConnectionSource`(풀, 기본 4 · `HONCHEON_DB_POOL` 로 조절).
- **가면(RoutingConnection)**: `Db` 의 87곳 `conn.` 이 그대로 산다. 문장을 열면 빌리고 닫으면
  돌려주며(try-with-resources 가 반납을 보증), `setAutoCommit(false)`~`(true)` 사이는 한 연결로
  고정된다. 모르는 몸짓은 소리내어 거절한다 (조용한 오동작 금지). 스냅숏처럼 연결 상태를 직접
  만지는 손은 가면 너머가 아니라 `source.borrow()` 로 실제 연결을 받는다.
- **충돌이 순서를 판정한다**: PostgreSQL 연결은 전부 SERIALIZABLE 로 태어난다 (설계 불변식:
  "행 잠금이나 버전 충돌로 순서를 판정한다"). 읽고-계산하고-쓰는 **여덟 메서드**(advanceDay ·
  addSectBurden · addMandate · addBloodDebt · mergeBloodDebt · pendLinkRequest ·
  livingLinkRequests · addMyeongbun)는 `atomically()` 로 감쌌다 — 이미 트랜잭션 안이면 합류하고,
  스스로 열었다 충돌(40001·40P01)하면 지수 백오프+지터로 최대 8회 물러나 다시 잰다.
  `inTransaction`(공개 계약)은 재시도하지 않는다 — 브리지 poll 루프가 그 몫이고, 그 재시도는
  멱등 선점 덕에 안전하다 (브리지 원자성 설계 그대로).
- **관측 지표**: `Db.storageStats()` — 풀 크기·생성·사용 중·최고 동시·대여·대기(최장 ms)·죽은 연결 교체.
  풀 고갈은 30초 뒤 이유를 말하고 죽는다 (침묵 금지).

## 검증 (전부 직접 실행, 2026-07-14)

- `PgConcurrencySelfTest` — **6눈, 3회 연속 전부 통과** (실제 PostgreSQL 16):
  - 서로 다른 뭉치의 두 트랜잭션이 **동시에 열려 있었다** — 옛 `synchronized` 아래서는 교착으로
    죽는 시험이다. 이것이 "전역 직렬화 없음"의 실증.
  - 같은 뭉치(sect_state 한 행)를 **8손 × 25회** 동시 증가 → 정확히 200 (잃어버린 갱신 0 ·
    도구 안 재시도가 전부 흡수, 시험 쪽 추가 재시도 0회).
  - 같은 사건의 동시 선점 → 처리기는 정확히 1번.
  - 같은 망치질을 SQLite 로도 → 정확히 200 (한 손 회귀).
  - 실측 지표: `풀 4 (생성 4) · 최고 동시 4 · 대여 284회 · 대기 25회 (최장 85ms)`.
- **회귀 전부 통과**: 계약 SQLite 7/7 · Reset 41/41 · 브리지 전달 10/10 · **이관 21/21** ·
  PostgreSQL 계약 10/10 (스냅숏은 이제 가면이 아니라 실제 연결을 빌려 쓴다 — 같은 결과).
- 감사: `pg_concurrency_audit.py` 8축 위반 0 · 그 눈의 시험 **6변이 전부 잡음**
  (처음 눈은 2건을 놓쳤다 — 반납 개수 문턱이 느슨했던 것, 바늘 불일치. 눈을 고쳐 6/6).
  `persistence_port_audit` 15축 · `persistence_boundary_audit` 9축 · `postgresql_audit` 10축 ·
  `pg_migration_audit` 10축 — 전부 통과.

재현 명령:

```bash
docker run --rm -d --name honcheon-pg006-test \
  -e POSTGRES_USER=honcheon -e POSTGRES_PASSWORD=contract -e POSTGRES_DB=honcheon \
  -p 127.0.0.1:55432:5432 postgres:16-alpine
until docker exec honcheon-pg006-test pg_isready -U honcheon -d honcheon -q; do sleep 1; done

JAVA_HOME="$PWD/run/jdk-21" ./gradlew :server-bot:jar
run/jdk-21/bin/javac -cp server-bot/build/libs/server-bot-0.1.0.jar \
  -d /tmp/honcheon-pg006 tools/PgConcurrencySelfTest.java
run/jdk-21/bin/java -cp /tmp/honcheon-pg006:server-bot/build/libs/server-bot-0.1.0.jar \
  com.honcheon.bot.PgConcurrencySelfTest jdbc:postgresql://127.0.0.1:55432/honcheon honcheon contract

python3 tools/pg_concurrency_audit.py
python3 tools/pg_concurrency_audit_selftest.py
# 회귀: PersistenceContractSelfTest · ResetSelfTest · BridgeDeliverySelfTest ·
#       PostgresqlContractSelfTest · PgMigrateSelfTest (명령은 PG-004·PG-005 완료 문서)
```

## 남은 위험 (기록)

1. **SQLite 의 원자 단위가 좁아졌다**: 옛 `synchronized` 는 메서드 전체를 잠갔고, 지금 한 손 잠금은
   문장·트랜잭션의 수명만 잠근다. 읽고-쓰는 여덟은 감쌌지만, **여러 번 읽기만 하는** 메서드는 두 읽기
   사이에 다른 손의 쓰기가 끼어들 수 있다 (쓰기 파손은 없음 · PostgreSQL 은 SERIALIZABLE 이라 무관).
   SQLite 는 복귀 경로일 뿐이므로 감수했다.
2. **호출자 수준의 읽기-계산-쓰기**: `updateCharacter` 처럼 호출자가 읽고 계산해서 blind write 하는
   패턴은 옛 `synchronized` 아래서도 원자가 아니었다 (호출 사이에 끼어들 수 있었다). PG-006 은
   그것을 악화시키지 않았지만 치유하지도 않았다 — 필요해지면 버전 열(optimistic lock)이 다음 손이다.
3. **재시도는 DB 밖 부수효과와 섞이면 안 된다**: `atomically` 안은 순수 DB 작업만 — 여덟 메서드는
   전부 그렇다. 새 겨룸 메서드를 추가할 때 이 규약을 지켜라 (감사 축 ⑤가 목록을 지킨다).
4. **손수 만든 풀**: 한 자릿수 동시성 규모에 맞춘 최소 구현이다 (겹침 대여 결속 · 죽은 연결 교체 ·
   고갈 소리내기). 규모가 커지면 HikariCP 로 갈아끼운다 — 경계(ConnectionSource)는 서 있다.
5. 시험 컨테이너 `honcheon-pg005-test` 재사용 중 — Codex 재시험 후 `docker rm -f` 로 정리.

## 다음 작업 PG-007

스테이징 전환과 장애 복귀 훈련 — 컷오버와 롤백을 둘 다 재현한다 (봇 정지 확인 체크리스트 포함,
PG-005 완료 문서의 위험 2 참조). PG-007 이 닫히기 전에는 운영 전환하지 않는다.
