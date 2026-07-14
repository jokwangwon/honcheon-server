#!/usr/bin/env bash
set -euo pipefail
# 혼천 봇 — PostgreSQL 백엔드 기동 (운영 컷오버 2026-07-14 이후의 정상 경로)
# 접속 정보는 run/bot/pg.env 가 정본이다 (600 · git 밖). 나머지는 run_bot.sh 와 같다:
# DISCORD_TOKEN 환경 변수 필요 · 절차와 복귀는 docs/bot/pg_cutover_runbook.md
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [ ! -f "$ROOT/run/bot/pg.env" ]; then
  echo "오류: run/bot/pg.env 가 없다 — PostgreSQL 접속 정보가 정본이다." >&2
  echo "만드는 법: docs/bot/pg_cutover_runbook.md §전제 (컷오버 때 생성된다)" >&2
  exit 1
fi
set -a
# shellcheck disable=SC1091
. "$ROOT/run/bot/pg.env"
set +a
exec "$ROOT/scripts/run_bot.sh"
