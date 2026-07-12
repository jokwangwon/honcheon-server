#!/usr/bin/env bash
set -euo pipefail
# 계측 — 추측을 숫자로 바꾼다.
#
# 이 서버는 한 번도 계측된 적이 없다. 부채는 알고 있었지만(조성기의 한 틱 폭탄, 매 틱 도는 여섯),
# **아무도 재지 않았다.** 이 스크립트가 RCON 으로 재는 자다.
#
# spark 는 이미 있다 — Paper 1.21 이 번들로 싣고 있고, 배경 프로파일러가 이미 돌고 있다
# (run/mvt/plugins/spark/config.json: backgroundProfiler=true). 설치할 것이 없다.
#
# 사용:
#   scripts/perf_probe.sh tps                 — 지금 상태 (TPS·MSPT·힙)
#   scripts/perf_probe.sh baseline [초]       — ① 무부하 기준선 (이걸 모르면 나머지가 뜻이 없다)
#   scripts/perf_probe.sh build               — ② 조성 폭탄 (프로파일을 켠 채 /혼천 조성)
#   scripts/perf_probe.sh watch [초]          — ③ 그냥 지켜본다 (플레이어가 노는 동안)
#   scripts/perf_probe.sh cmd "혼천 계측"      — 아무 콘솔 명령
#   scripts/perf_probe.sh profile <초>        — spark 프로파일만 (결과는 웹 링크)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

rcon() {   # 콘솔 명령 한 줄 → 응답을 그대로 뱉는다
  python3 - "$1" <<'PY'
import socket, struct, sys
pw = None
for line in open('run/mvt/server.properties', encoding='utf-8'):
    if line.startswith('rcon.password='):
        pw = line.strip().split('=', 1)[1]
try:
    s = socket.create_connection(('127.0.0.1', 25575), timeout=10)
except OSError:
    print('✗ 서버가 안 떠 있다 (scripts/run_mvt_server.sh)', file=sys.stderr)
    sys.exit(1)
def send(t, body):
    p = struct.pack('<ii', 0, t) + body.encode() + b'\x00\x00'
    s.send(struct.pack('<i', len(p)) + p)
def recv():
    ln = struct.unpack('<i', s.recv(4))[0]
    d = b''
    while len(d) < ln:
        d += s.recv(ln - len(d))
    return d[8:-2].decode('utf-8', 'replace')
send(3, pw); recv()
send(2, sys.argv[1])
print(recv())
s.close()
PY
}

say() { printf '\n\033[1m── %s\033[0m\n' "$*"; }

case "${1:-tps}" in

  tps)
    say "지금 상태"
    rcon "spark tps"
    rcon "spark healthreport"
    ;;

  # ① 무부하 기준선 — 아무도 없고 아무 일도 없을 때의 MSPT.
  #    매 틱 도는 여섯(SkillListener·SkillCast·SkillDisplay·MobDisplay·Populace·Incidents)이
  #    **가만히 있을 때 얼마를 먹는지**가 여기서 나온다. 이 값이 모든 비교의 원점이다.
  baseline)
    SECS="${2:-60}"
    say "기준선 ${SECS}초 — 계기를 0으로 놓고, 아무것도 하지 않는다"
    rcon "혼천 계측 초기화"
    rcon "spark profiler start --timeout ${SECS} --only-ticks-over 40"
    sleep "$((SECS + 5))"
    rcon "spark tps"
    say "티커별 예산 대조 (/혼천 계측)"
    rcon "혼천 계측"
    ;;

  # ② 조성 폭탄 — 가장 큰 부채. 슬라이싱 배선 전/후로 **같은 명령**을 돌려 비교하라.
  #    배선 전: 서버가 수 초~수십 초 멈춘다 (워치독). spark 는 그 스택을 그대로 잡는다.
  #    배선 후: TPS 가 20 근처를 유지한 채 조성이 수십 초에 걸쳐 끝난다.
  build)
    say "조성 — 프로파일을 켜고 /혼천 조성"
    rcon "혼천 계측 초기화"
    rcon "spark profiler start --timeout 180 --thread * --only-ticks-over 40"
    rcon "혼천 조성"
    echo "  ... 조성 중 (배선 전이면 여기서 서버가 멈춘다 — 그게 관측하려는 것이다)"
    sleep 185
    rcon "spark tps"
    rcon "혼천 계측"
    ;;

  # ③ 사람이 노는 동안 지켜본다 (부하 시험 ③④⑤ — 플레이어가 있어야 서는 층)
  watch)
    SECS="${2:-120}"
    say "관측 ${SECS}초 — 지금부터 연무장에서 놀아라 (몹·허수아비·무공 난사)"
    rcon "혼천 계측 초기화"
    rcon "spark profiler start --timeout ${SECS} --thread * --only-ticks-over 40"
    sleep "$((SECS + 5))"
    rcon "spark tps"
    rcon "혼천 계측"
    ;;

  profile)
    SECS="${2:-60}"
    say "spark 프로파일 ${SECS}초"
    rcon "spark profiler start --timeout ${SECS} --thread *"
    sleep "$((SECS + 5))"
    rcon "spark profiler stop"
    ;;

  cmd)
    rcon "${2:?명령을 다오}"
    ;;

  *)
    sed -n '2,20p' "$0"
    exit 1
    ;;
esac
