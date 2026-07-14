# Fable 검토 — Codex 영속화 전환 일괄 (브리지 원자성 · PG-001~PG-004)

> 2026-07-14 · 검토자: Fable · 원칙: Codex는 커밋하지 않는다 — Fable이 검토 후 커밋한다.

## 판정: 통과 — 커밋한다

여덟 완료 문서(B-001~003 · B-009 · 브리지 원자성 · PG-001 · PG-002A/B/C · PG-003 · PG-004)의
검증 명령을 **문서를 믿지 않고 직접 재실행**했다. 전부 재현됐다.

## 재실행한 검증 (2026-07-14, Fable 손으로)

| 검증 | 주장 | 재현 |
|---|---|---|
| `./gradlew :server-bot:compileJava :server-bot:jar` | 성공 | ✅ exit 0 |
| `persistence_inventory_selftest` | 9/9 | ✅ |
| `persistence_port_audit` (+selftest) | 15축 · 5/5 | ✅ |
| `persistence_boundary_audit` (+selftest) | 9축 · 4/4 | ✅ |
| `postgresql_audit` (+selftest) | 10축 · 4/4 | ✅ |
| `bridge_audit_selftest` | 8/8 | ✅ |
| `bridge_audit.py --no-backup` | 위반 0 | ✅ 경고만 (seojang_choice 호출자 없음 등 — 기지 사항) |
| `PersistenceContractSelfTest` (실제 SQLite) | 7/7 | ✅ |
| `ResetSelfTest` (실제 SQLite) | 41/41 | ✅ |
| `BridgeDeliverySelfTest` (실제 SQLite) | 10/10 | ✅ |
| `PostgresqlContractSelfTest` | 빈 PG16에서 10/10 | ✅ **새로 만든 빈 DB**(`honcheon_review`)에서 10/10 재현 — Codex가 쓰던 DB 재사용이 아니다 |
| `lint_config.py` (B-001) | 오류 0 | ✅ 오류 0·경고 0 |

Java 자체시험 실행 방법 (완료 문서에 없어서 여기 적는다):

```bash
CACHE=~/.gradle/caches/modules-2/files-2.1
CP="server-bot/build/classes/java/main:core/build/classes/java/main:domain/build/classes/java/main:\
$(find $CACHE/org.xerial $CACHE/org.postgresql $CACHE/org.slf4j $CACHE/com.fasterxml.jackson.core $CACHE/org.yaml -name '*.jar' | tr '\n' ':')"
run/jdk-21/bin/javac -cp "$CP" -d /tmp/selftest tools/<이름>SelfTest.java
run/jdk-21/bin/java -cp "$CP:/tmp/selftest" com.honcheon.bot.<이름>SelfTest
# PostgresqlContractSelfTest 는 인자 셋: <jdbc-url> <user> <password>
# (컨테이너: docker run … postgres:16-alpine, 예: jdbc:postgresql://127.0.0.1:55432/<빈 DB>)
```

## 브리지 원자성 검토 요청에 대한 답 (CODEX_BRIDGE_ATOMIC_COMPLETE §검토 요청)

- **처리기의 중첩 트랜잭션·별도 연결**: `GameListener.java` 에 `inTransaction`·`DriverManager`·`new Db(` 가
  **한 곳도 없다** (grep 확인). 처리기는 전부 저장소 포트를 통한다. 게다가 계약 자체가 이중 안전이다 —
  암묵 중첩은 즉시 거부되므로(계약시험 ✔) 숨은 중첩이 생기면 조용히 넘어가지 않고 그 자리에서 터진다.
- **Discord 알림 at-least-once**: 수용. exactly-once 가 필요해지면 트랜잭션 outbox 를 별도 작업으로 —
  Codex 의 제안 그대로 두는 것이 맞다.
- **영구 불량 이벤트 fail-closed**: 수용. 자동 폐기보다 옳다. 운영자가 로그를 본다.

## 지적 (막지 않음 — 기록)

1. **`.orig` 잔재**: `Db.java.orig` · `HoncheonBot.java.orig` · `build.gradle.orig` ·
   `PostgresqlDialect.java.orig` · `PostgresqlContractSelfTest.java.orig` 가 트리에 남아 있다.
   커밋에서 제외했다. Codex 는 다음부터 편집 백업을 트리 밖에 두라.
2. **완료 문서에 Java 자체시험 실행법이 없다**: "실제 SQLite 41/41" 만 적혀 있고 어떻게 돌리는지가
   없어서 인수자가 classpath 를 스스로 조립해야 했다. 위에 적어 뒀다 — 다음 완료 문서부터는 명령을 적으라.
3. **PG-004 완료 선언 시점**: `SHARED_MEMORY_V6.md` 가 먼저 갱신되고 `ACTIVE_V6.md` 와 완료 문서가
   늦게 따라왔다. 인계 지점이 잠깐 세 문서에서 서로 달랐다 — 다음부터는 완료 문서 → ACTIVE → 공유 메모리 순서로.

## 커밋 범위

이 검토로 커밋하는 것: server-bot 전체(포트·방언·구현·조합 루트), `core` 의 `GenderEngine`(+시험),
`db/`(schema.sql · migrations 007-008 · postgresql/), bot쪽 tools(영속화·PostgreSQL·브리지·장부 감사와
Java 자체시험), 협업 문서 전부, `SHARED_MEMORY*` · `docs/HANDOFF.md` · `docs/design/postgresql_migration.md`.

보류(다음 검토): mvt쪽 config·리소스팩·mvt 감사 도구의 수정분 — 별도 스코프다.
