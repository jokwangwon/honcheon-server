#!/usr/bin/env bash
set -euo pipefail
# 혼천 디스코드 봇 알파 — 원커맨드 기동 (로컬 PC 전용)
# 요구: Java 21+, gradle(또는 ./gradlew), DISCORD_TOKEN 환경 변수
# 안내: docs/bot/bot_alpha_guide.md (토큰 발급·봇 초대·권한)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/run/bot"
mkdir -p "$RUN"

if [ -z "${DISCORD_TOKEN:-}" ]; then
  echo "오류: DISCORD_TOKEN 환경 변수가 필요하다." >&2
  echo "발급·설정 방법: docs/bot/bot_alpha_guide.md" >&2
  exit 1
fi

# Java 21+ 탐지 — run_mvt_server.sh 와 동일 규약 (run/jdk-21 폴백)
find_java21() {
  local cand ver
  for cand in "${JAVA_HOME:+$JAVA_HOME/bin/java}" java "$ROOT/run/jdk-21/bin/java"; do
    [ -n "$cand" ] && command -v "$cand" >/dev/null 2>&1 || continue
    ver=$("$cand" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')
    if [ "${ver:-0}" -ge 21 ] 2>/dev/null; then echo "$cand"; return 0; fi
  done
  return 1
}
JAVA_BIN="$(find_java21)" || {
  echo "오류: Java 21+ 를 찾지 못했다. JDK 21 을 설치하거나 run/jdk-21 에 풀어 두라." >&2
  exit 1
}
export JAVA_HOME="$(cd "$(dirname "$JAVA_BIN")/.." && pwd)"
echo "      Java: $JAVA_BIN ($("$JAVA_BIN" -version 2>&1 | head -1))"

echo "[1/2] 봇 빌드 (:server-bot)"
if [ -x "$ROOT/gradlew" ]; then GRADLE="$ROOT/gradlew"; else GRADLE="gradle"; fi
(cd "$ROOT" && "$GRADLE" :server-bot:build -q)

echo "[2/2] 봇 기동 — 중지: Ctrl+C / DB: run/bot/honcheon.db"
if [ -z "${ANTHROPIC_API_KEY:-}" ]; then
  echo "      (참고: ANTHROPIC_API_KEY 미설정 — 서사는 폴백 템플릿. LLM 렌더는 키만 넣으면 켜진다)"
fi
cd "$ROOT"   # config·db/schema.sql 상대 경로의 기준
export HONCHEON_CONFIG="${HONCHEON_CONFIG:-config}"
export HONCHEON_DB="${HONCHEON_DB:-run/bot/honcheon.db}"
export HONCHEON_SCHEMA="${HONCHEON_SCHEMA:-db/schema.sql}"
exec "$JAVA_BIN" -jar "$ROOT"/server-bot/build/libs/server-bot-*.jar
