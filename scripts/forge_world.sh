#!/usr/bin/env bash
set -uo pipefail
# 혼천 — **전체 맵 조성** (지형 계층)
#
#   등록부의 장소를 우선순위대로 판다. 땅은 **한 번만** 선다 (TerrainLedger).
#   각 조성 후 검수를 돌려 위반을 적는다. 서버는 안 멈춘다 (TickBudget).
#
# 사용:  scripts/forge_world.sh [단계]    단계: rivers | sects | cities | all
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
STAGE="${1:-all}"
LOG="run/forge-$(date +%Y%m%d-%H%M%S).log"

rcon() { python3 - "$@" <<'PY'
import socket, struct, sys
pw = next(l.strip().split('=',1)[1] for l in open('run/mvt/server.properties', encoding='utf-8')
          if l.startswith('rcon.password='))
try: s = socket.create_connection(('127.0.0.1', 25575), timeout=600)
except OSError: sys.exit(1)
def send(t,b):
    p = struct.pack('<ii',0,t)+b.encode()+b'\x00\x00'; s.send(struct.pack('<i',len(p))+p)
def recv():
    ln = struct.unpack('<i', s.recv(4))[0]; d=b''
    while len(d)<ln: d += s.recv(ln-len(d))
    return d[8:-2].decode('utf-8','replace')
send(3,pw); recv(); send(2,' '.join(sys.argv[1:])); print(recv())
PY
}

# 우선순위 — 담당이 매긴 순서 (섬 2곳은 뺀다: 반도에 앉는다)
RIVERS="jangang_suroche muhan soju gangnam_sangro namgung wisu_galdae"
SECTS="hwasan jongnam sorimsa mudang gonryun jeomchang cheongseong ami dangga paengga jegal moyong"
CITIES="jangan nakyang gaebong taewon gyeongsa nangyeong nokrim_sochae nokrim_jungchae nokrim_chongchae magyo_jinryeong magyo_bonkyo"

case "$STAGE" in
  rivers) LIST="$RIVERS" ;;
  sects)  LIST="$SECTS" ;;
  cities) LIST="$CITIES" ;;
  all)    LIST="$RIVERS $SECTS $CITIES" ;;
  *)      LIST="$STAGE" ;;
esac

echo "══ 조성 — $(echo $LIST | wc -w)곳" | tee -a "$LOG"
for id in $LIST; do
  echo "── $id" | tee -a "$LOG"
  start=$(date +%s)
  rcon 혼천 지역조성 "$id" >/dev/null 2>&1
  # 끝날 때까지 기다린다 (조성 완료 또는 중단)
  for _ in $(seq 1 240); do
    grep -qE "지역조성\] $id|조성:$id (중단|실패)" run/mvt/server-console.log && break
    sleep 5
  done
  took=$(( $(date +%s) - start ))
  grep -E "\[지형\]|\[지형/강\]|지역조성\] $id|조성:$id (중단|실패)" run/mvt/server-console.log \
      | tail -3 | sed 's/.*MVT\] //' | sed "s/^/    /" | tee -a "$LOG"
  echo "    (${took}초)" | tee -a "$LOG"
  # 검수
  rcon 혼천 환경검수 "$id" 2>&1 | sed 's/§.//g' | grep -E "^(✅|❌|✗) 위반" | sed 's/^/    /' | tee -a "$LOG"
done
echo "══ 지도 검수" | tee -a "$LOG"
rcon 혼천 지도검수 2>&1 | sed 's/§.//g' | grep -E "땅이 있다|아직 땅이 없다|침묵|미해결|총평" | tee -a "$LOG"
echo "→ 로그: $LOG"
