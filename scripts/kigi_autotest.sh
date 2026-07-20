#!/usr/bin/env bash
# 검기(劍氣) 시각효과 — 사람 없는 자동 검증 하네스 (한 방 실행)
#
#   scripts/kigi_autotest.sh                # 기본 8회 스윙
#   scripts/kigi_autotest.sh --swings 12 --night
#   scripts/kigi_autotest.sh --restart-client
#
# ★ 라이브(25565·run/mvt)를 건드리지 않는다. 테스트는 25566/25576 · run/mvt-test 뿐이다.
# ★ 25566·25576 을 공유기에 포워딩하지 마라 (online-mode=false · 인증 없음).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 "$ROOT/scripts/kigi_autotest.py" "$@"
