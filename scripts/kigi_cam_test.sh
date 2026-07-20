#!/usr/bin/env bash
# 검기(劍氣) — 카메라 봇 자동 검증 (한 방 실행)
#
#   scripts/kigi_cam_test.sh                       # back(뒤 6m) · 8회 스윙
#   scripts/kigi_cam_test.sh --angle all
#   scripts/kigi_cam_test.sh --angle side --swings 6 --night
#
# ★ 왜: Xvfb 에서 F5(시점 전환)가 안 먹어 1인칭만 찍힌다. 그래서 두 번째 봇(kigicam)을
#   :98 에 띄워 kigibot 을 **바라보게** 한다 — 그 1인칭 화면이 곧 3인칭이다.
#   카메라 각도는 F5 가 아니라 RCON tp 로 정한다 (tp 는 잘 먹는다).
#
# ★ 라이브(25565·run/mvt)를 건드리지 않는다. 테스트는 25566/25576 · run/mvt-test 뿐이다.
# ★ 25566·25576 을 공유기에 포워딩하지 마라 (online-mode=false · 인증 없음).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 "$ROOT/scripts/kigi_cam_test.py" "$@"
