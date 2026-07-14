# PostgreSQL 컷오버·롤백 런북 (PG-007)

> 이 절차는 2026-07-14 훈련으로 재현됐고(기록: `docs/collaboration/FABLE_PG007_DRILL.md`),
> **같은 날 16:38 운영 컷오버에 실전 사용됐다** (기록: `docs/collaboration/FABLE_CUTOVER_20260714.md`).
> 운영 인스턴스: 컨테이너 `honcheon-postgres` · 볼륨 `honcheon-pgdata` · `127.0.0.1:5435` ·
> 열쇠 정본 `run/bot/pg.env` · 기동 `scripts/run_bot_pg.sh`.
> 원칙: **정지 → 백업 → 이관 → 검산 → 전환.** dual-write 없음. 어느 단계든 실패하면 §롤백으로 간다.

## 전제 (하나라도 어긋나면 시작하지 않는다)

- [ ] PostgreSQL 16 이 **영속 볼륨**으로 떠 있다 (훈련은 컨테이너로 족하나, 운영 전환은
      `--rm` 컨테이너에 하지 않는다 — 컨테이너가 죽으면 세계가 죽는다)
- [ ] 최신 jar 가 빌드돼 있다: `JAVA_HOME=$PWD/run/jdk-21 ./gradlew :server-bot:jar`
- [ ] 감사 초록: `pg_migration_audit` · `pg_concurrency_audit` · `postgresql_audit` 전부 위반 0
- [ ] **DISCORD_TOKEN 확보** — 봇을 죽이기 전에 반드시:
      `tr '\0' '\n' < /proc/<봇PID>/environ > <안전한 곳>` (chmod 600).
      ★ 토큰은 프로세스 env 에만 산다. 죽인 뒤에는 없다 (실사고 이력 있음).
- [ ] 봇의 나머지 env 도 같은 파일에서 확보: `HONCHEON_LLM_URL` · `HONCHEON_LLM_MODEL` ·
      `HONCHEON_GUILD_ID` · `HONCHEON_CONFIG`

## 컷오버

1. **봇 정지** — `kill <봇PID>` 후 **정지 확인**: `ps -p <봇PID>` 가 빈손일 때까지 기다린다.
   ★ 봇이 살아 있으면 이관 시점 이후의 쓰기가 대상에 없다 (원본은 읽기 전용이라 오염은 없지만,
   유실은 있다). **정지 확인 없이 다음 단계로 가지 않는다.**
2. **정지 시점 상태 기록** — 나중에 검산의 기준이 된다:
   `다리:커서` · `현재일` · `bridge_inbox` 행 수 · `characters` 행 수.
3. **SQLite 백업** — `cp run/bot/honcheon.db run/bot/backup-pg007-<시각>/` (`-wal`·`-shm` 이
   있으면 함께). 이 백업이 롤백의 마지막 방어선이다.
4. **이관** — PG-005 도구 (원본은 읽기 전용으로만 연다):
   ```bash
   run/jdk-21/bin/javac -cp server-bot/build/libs/server-bot-0.1.0.jar -d /tmp/pg007 tools/PgMigrate.java
   run/jdk-21/bin/java -cp /tmp/pg007:server-bot/build/libs/server-bot-0.1.0.jar \
     com.honcheon.bot.PgMigrate from-sqlite run/bot/honcheon.db \
     "$PG_URL" "$PG_USER" "$PG_PASSWORD" run/bot/pg007_cutover_report.md
   ```
   **exit 0 + 보고서 「총평 ✅」가 아니면 여기서 멈춘다** (대상은 롤백돼 있다 → §롤백 5번으로).
5. **PostgreSQL 백엔드로 기동**:
   ```bash
   export DISCORD_TOKEN=… HONCHEON_GUILD_ID=… HONCHEON_CONFIG=config   # 확보해 둔 값
   export HONCHEON_DB_BACKEND=postgresql
   export HONCHEON_DATABASE_URL="$PG_URL" HONCHEON_DATABASE_USER="$PG_USER" HONCHEON_DATABASE_PASSWORD="$PG_PASSWORD"
   export HONCHEON_SCHEMA=db/postgresql/schema.sql
   cd <저장소 루트> && nohup run/jdk-21/bin/java -jar server-bot/build/libs/server-bot-0.1.0.jar \
     >> run/bot/bot.log 2>&1 & echo $!
   ```
6. **가동 검증** (전부 맞아야 전환 성공):
   - [ ] 로그에 `혼천 봇 기동 — … DB: postgresql`
   - [ ] `다리:커서` 가 **PostgreSQL 에서** 전진한다 (MVT 가 돌고 있다면 수 분 내)
   - [ ] SQLite 파일 mtime 이 **더 이상 안 변한다** (얼어붙었다 = 봇이 정말 옮겨 탔다)
   - [ ] 디스코드에서 슬래시 명령 하나 실행 (사람 눈)
7. **관찰** — 최소 한 브리지 사건이 PostgreSQL 에 적히는 것을 본 뒤 전환을 선언한다.
   SQLite 원본은 **지우지 않는다** (PG-008 전까지 복귀 경로다).

## 롤백 (컷오버의 역방향 — 언제든 갈 수 있어야 한다)

1. **PG 봇 정지** — kill 후 정지 확인 (컷오버 1번과 같은 엄격함).
2. **SQLite 백엔드로 재기동** — 확보해 둔 env 그대로 (`HONCHEON_DB_BACKEND` 없이):
   `HONCHEON_DB=run/bot/honcheon.db` · `HONCHEON_SCHEMA=db/schema.sql`.
3. **검증**: 로그 `DB: sqlite` · `현재일` 등 상태가 정지 시점 기록과 일치.
4. **★ 브리지가 스스로 따라잡는다**: SQLite 의 `다리:커서` 는 컷오버 시점에 멈춰 있다 —
   봇이 그 뒤의 JSONL 을 다시 읽고, PG 시절 사건들이 SQLite 로 **처음** 적힌다 (멱등 선점이
   이미 있는 것은 걸러 준다). 커서가 현재 줄까지 오는 것을 확인한다.
5. **이관이 4번에서 실패했던 경우**: 대상 PostgreSQL 은 도구가 이미 롤백했다 (빈 상태).
   봇을 SQLite 로 재기동하면 끝이다. 백업(3번)은 그대로 둔다.
6. PG 시절에 쓰인 데이터는 SQLite 에 없다 (브리지 사건 제외 — 4번). 디스코드 직접 명령의
   결과가 그 사이에 있었다면 **사람이 판단해 손으로 옮긴다** — 그래서 관찰(컷오버 7번)이
   짧을수록 롤백이 싸다.

## 중단 기준

- 이관 검산 불일치 → 도구가 스스로 멈춘다 (커밋 안 됨). 원인 규명 전 재시도 금지.
- PG 기동 후 브리지 사건이 10분 넘게 안 적힌다 → 롤백.
- 풀 고갈 로그(`풀 고갈`) 또는 반복 40001 폭주 → 롤백 후 `HONCHEON_DB_POOL` 재검토.
