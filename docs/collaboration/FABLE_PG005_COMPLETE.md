# Fable 완료 - PG-005 SQLite export/import 및 검산

> 2026-07-14 · 주 담당: Fable (PG-005부터 인계) · 검토자: Codex
> 소유 파일: `tools/PgMigrate.java` · `tools/PgMigrateSelfTest.java` · `tools/pg_migration_audit.py`(+selftest)

## 결과

- `tools/PgMigrate.java` — SQLite 원본을 PostgreSQL 로 **일회성** 이관하는 도구. 두 모드:
  - `from-sqlite <honcheon.db> <jdbc-url> <user> <password> [보고서.md]`
  - `from-snapshot <honcheon-postgresql.zip> …` — PG-004 의 CSV ZIP 논리 스냅숏 복원 경로
- 원본 SQLite 는 **읽기 전용**으로만 연다 (`SQLiteConfig.setReadOnly(true)`) — 쓰는 길 자체가 없다.
- import 전체가 **PostgreSQL 트랜잭션 하나**다. 어디서 실패하든 대상은 통째로 롤백된다.
- 대상이 비어 있지 않으면 **손대기 전에 거절**한다 (world_meta 의 스키마 씨앗만 예외) — 재실행 보호.
- **검산 다섯 축**을 커밋 **전에** 같은 트랜잭션에서 잰다: ① 표별 행 수 ② world_meta 전체
  ③ SUM(wallet) ④ SUM(balance) ⑤ bridge_inbox 멱등 키(정렬된 event_id 의 SHA-256).
  하나라도 어긋나면 롤백. FK 는 두 겹: 원본 `PRAGMA foreign_key_check` + 대상 제약의 행 단위 강제.
- **BIGSERIAL 시퀀스 보정**: 표마다 `setval(max(id))` — COUNT 가 아니라 MAX 다
  (실DB 실증: characters 행 4개·max id 6). 빈 표는 `setval(1,false)`.
- TIMESTAMPTZ 변환은 **UTC 를 명시**한다 — SQLite `datetime('now')` 는 UTC 텍스트인데,
  시간대를 밝히지 않으면 PostgreSQL 이 세션 시간대로 읽어 모든 시각이 조용히 어긋난다.
- 모르는 표를 만나면 **멈춘다** (TABLE_ORDER 대조) — 조용히 빼놓은 표는 조용히 사라진 데이터다.
- 성공이든 실패든 **사람이 읽는 보고서**(markdown)가 남는다.

## 검증 (전부 직접 실행, 2026-07-14)

- `PgMigrateSelfTest` — **21눈 전부 통과** (실제 SQLite fixture + 빈 PostgreSQL 16):
  고아 FK 사전 거절 · 적재 도중 실패(썩은 타임스탬프) 전체 롤백 · 원본 SHA-256 불변(거절 실행·이관·재실행 통틀어) ·
  성공 시 검산 다섯 축 일치 · **이관 후 실제 `Db.open` 으로 `createCharacter` 성공**(시퀀스 보정 실증, id 5>4) ·
  재실행 거절·무변경 · 스냅숏 복원 후 행 수 동일 + 복원본에서도 INSERT 성공
- **실데이터 리허설**: 운영 `run/bot/honcheon.db`(20표·events 312·bridge_inbox 166) → 빈 `honcheon_dryrun`
  이관 성공, 다섯 축 전부 ✅, 멱등 키 지문 `0a77755c…` 양쪽 일치.
  보고서: `run/bot/pg005_migration_report.md`
- `python3 tools/pg_migration_audit.py` — 10축 위반 0건 (도커 없이 도는 소스 계약 감사)
- `python3 tools/pg_migration_audit_selftest.py` — **9변이 전부 잡음** (처음 눈은 2건을 놓쳤다 —
  롤백이 한 길에만 남은 것, 원본 불변 비교 제거. 눈을 고쳐 9/9)

재현 명령:

```bash
# PostgreSQL 16 (자기 시험이 pgmigrate_selftest_a/_b 데이터베이스를 만들고 지운다)
docker run --rm -d --name honcheon-pg005-test \
  -e POSTGRES_USER=honcheon -e POSTGRES_PASSWORD=contract -e POSTGRES_DB=honcheon \
  -p 127.0.0.1:55432:5432 postgres:16-alpine
until docker exec honcheon-pg005-test pg_isready -U honcheon -d honcheon -q; do sleep 1; done

JAVA_HOME="$PWD/run/jdk-21" ./gradlew :server-bot:jar
run/jdk-21/bin/javac -cp server-bot/build/libs/server-bot-0.1.0.jar \
  -d /tmp/honcheon-pg005 tools/PgMigrate.java tools/PgMigrateSelfTest.java
run/jdk-21/bin/java -cp /tmp/honcheon-pg005:server-bot/build/libs/server-bot-0.1.0.jar \
  com.honcheon.bot.PgMigrateSelfTest jdbc:postgresql://127.0.0.1:55432/honcheon honcheon contract

# 실데이터 리허설 (원본은 읽기 전용 — 봇이 켜져 있어도 원본은 안전하나, 정지 상태 이관이 계약이다)
docker exec honcheon-pg005-test psql -U honcheon -q -c "CREATE DATABASE honcheon_dryrun;"
run/jdk-21/bin/java -cp /tmp/honcheon-pg005:server-bot/build/libs/server-bot-0.1.0.jar \
  com.honcheon.bot.PgMigrate from-sqlite run/bot/honcheon.db \
  jdbc:postgresql://127.0.0.1:55432/honcheon_dryrun honcheon contract run/bot/pg005_migration_report.md

# 소스 계약 감사 (도커 불필요)
python3 tools/pg_migration_audit.py
python3 tools/pg_migration_audit_selftest.py
```

## 운영 계약

- **일회성이다.** 재실행은 거절된다. 다시 하려면 사람이 대상 DB 를 직접 지운다.
- **정지 → 이관 → 검산 → 전환** 이 계약이다. dual-write 는 없다 (설계 불변식).
- PG-005 가 닫혀도 **운영 전환은 하지 않는다** — PG-006(동시성)·PG-007(전환·복귀 훈련)이 남아 있다.
- 스냅숏 복원(`from-snapshot`)은 원본 DB 가 없으므로 검산이 얕다 (COPY 행 수 대 실제 행 수 + FK + 시퀀스).
  정본 이관은 `from-sqlite` 로 하라. 스냅숏은 재해 복구의 길이다.

## 남은 위험 (기록)

1. **★ 신규 SQLite 의 거짓 스탬프 (B-101 로 올렸다)**: `db/schema.sql` 은 19표(houses 없음)인데
   `Db.schemaVersionGate` 가 신규 DB 를 버전 8 로 스탬프한다 — 새로 만든 SQLite 에서 `HouseStore` 가 죽는다.
   PG-005 자기 시험이 fixture 를 만들다 밟았다 (fixture 는 008 DDL 을 직접 적용해 우회).
   운영 DB·PostgreSQL 은 무사하다.
2. **이관 중 봇 정지는 도구가 강제하지 못한다**: 원본은 읽기 전용이라 오염은 없지만, 봇이 살아
   있으면 이관 시점 이후의 쓰기가 대상에 없다 (리허설에서 실측: bridge_inbox 165→166).
   PG-007 전환 훈련의 체크리스트에 "봇 정지 확인"이 들어가야 한다.
3. **표가 늘면 TABLE_ORDER 도 늘어야 한다**: 모르는 표는 시끄럽게 거절되므로(잊으면 도구가 멈춘다)
   조용한 유실은 없다 — 다만 마이그레이션 009+ 를 만들 때 이 도구도 같이 고쳐야 한다.
4. 리허설 컨테이너 `honcheon-pg005-test` 는 Codex 독립 재시험을 위해 **살려 뒀다**
   (`docker rm -f honcheon-pg005-test` 로 정리 · `--rm` 이라 정지가 곧 삭제다).

## 다음 작업 PG-006

연결 풀과 동시성 제어, 관측 지표. 전역 `synchronized` 직렬화를 걷고 행 잠금/버전 충돌로
순서를 판정한다. 닫는 조건: 전역 직렬화 없이 충돌 시험 통과 (설계 문서 표).
