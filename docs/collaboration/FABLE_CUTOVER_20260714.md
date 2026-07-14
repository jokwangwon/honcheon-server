# 운영 컷오버 기록 — SQLite → PostgreSQL (2026-07-14)

> 절차: `docs/bot/pg_cutover_runbook.md` (PG-007 훈련으로 검증된 그 절차 그대로)
> 수행: Fable · 사람 눈: 사용자 (마크 접속 + 디스코드 행동 명령) · 결정: 사용자 ("진행해주세요")

## 전제 충족

- B-102(세계 상태 발행) 치유 — 커밋 75b5cfd, 전환 전 20초 주기 갱신 확인
- **영속 PostgreSQL**: 컨테이너 `honcheon-postgres` · 볼륨 `honcheon-pgdata` ·
  `127.0.0.1:5435` · 재시작 정책 unless-stopped · 접속 정보 정본 `run/bot/pg.env` (600 · git 밖)
- 최신 jar (PG-006 동시성 + B-102 수리) · 감사 전부 초록

## 시간표 (실측)

| 시각 | 단계 | 증거 |
|---|---|---|
| 16:31 | 영속 PG 기동 + `pg.env` 생성 | 볼륨 honcheon-pgdata · unless-stopped |
| 16:33:16 | 토큰 확보 → 봇 정지 확인 → 백업 | `run/bot/backup-cutover-20260714-163316/` |
| 16:33 | 정지 시점 기록 | 커서 27051 · 현재일 24 · inbox 166 · characters 4 · events 312 · rumors 42 |
| 16:33 | 이관 (PgMigrate) | exit 0 · 검산 다섯 축 ✅ · `run/bot/cutover_20260714-163316_report.md` |
| 16:34 | PostgreSQL 백엔드 기동 (PID 1597920) | `혼천 봇 기동 — DB: postgresql` · `Login Successful!` |
| 16:34:30 | 되먹임 확인 | `world_state.json` 이 PG 봇에서 갱신 (B-102 수리가 PG 경로에서도 산다) |
| 16:34 | PG 상태 = 정지 시점 기록 | 6/6 일치 |
| ~16:37 | **사람 눈 + 실사용** | 사용자가 마크 접속 + 행동 명령 실행 |
| 16:38 | **전 루프 가동 확인 — 전환 선언** | 아래 |

## 전환 선언의 근거 (전부 PostgreSQL 위에서)

- **마크 → 봇**: 브리지 커서 27051 → **27502** 전진 · bridge_inbox 166 → **168** (사건 2건 소비)
- **쓰기**: events 312 → **318** (접합_청 → 접합 → 판정)
- **★ 새 캐릭터 id 7 탄생** — 이관 전 max id 6. BIGSERIAL 시퀀스 보정의 **운영 실증**
  (보정이 없었다면 이 첫 INSERT 가 PK 충돌로 죽었다)
- **봇 → 마크**: `world_state.json` 주기 갱신 지속
- 오류 0: 발행 실패 · 풀 고갈 · 직렬화 충돌(40001) 로그 없음
- SQLite 동결: `-wal` mtime 14:45 그대로 (컷오버 후 한 바이트도 안 변함)

## 이후 운영

- **기동은 `scripts/run_bot_pg.sh`** — `run/bot/pg.env` 를 읽어 PostgreSQL 로 올린다
  (옛 `run_bot.sh` 단독 실행 = SQLite 복귀 경로. 복귀 절차는 런북 §롤백)
- `run/bot/pg.env` 가 **열쇠의 정본**이다 — 잃으면 `docker inspect honcheon-postgres` 의 env 에서 복구
- **SQLite 는 지우지 않는다** (PG-008 전 복귀 보존): `run/bot/honcheon.db` = 16:33 시점 동결본
- ★ 롤백은 이제 **비용이 있다**: PG 에만 있는 기록(캐릭터 7 의 접합·판정 등)은 SQLite 로
  자동으로 돌아가지 않는다 (브리지 사건은 재생되지만 디스코드發 기록은 아니다). 런북 §롤백 6 참조
- 호스트 재부팅 시: 컨테이너는 스스로 뜬다(unless-stopped) · **봇은 사람이 올린다** (`run_bot_pg.sh`)

## 남은 일

- PG-008: 복귀 보존 기간이 끝나면 SQLite 경로 제거 (사람이 기간을 정한다)
- Codex 검토: PG-005 · PG-006 · PG-007 · B-102 · 이 컷오버 기록
