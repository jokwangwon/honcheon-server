#!/usr/bin/env bash
set -euo pipefail
# 혼천 MVT 빠른 재배포 — 건축 반복(루프) 전용
# 정지(RCON) → 빌드 → jar 교체 → 재기동. 월드는 보존한다 (조성은 인게임에서 /혼천 조성).
# 사용: scripts/redeploy_mvt.sh [--backup]
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/run/mvt"
export JAVA_HOME="$ROOT/run/jdk-21"

rcon() {   # 콘솔 명령 (서버가 떠 있을 때만)
  python3 - "$@" <<'PY'
import socket, struct, sys, re
pw = None
for line in open('run/mvt/server.properties', encoding='utf-8'):
    if line.startswith('rcon.password='):
        pw = line.strip().split('=', 1)[1]
try:
    s = socket.create_connection(('127.0.0.1', 25575), timeout=5)
except OSError:
    sys.exit(0)   # 서버가 안 떠 있다 — 정지할 것도 없다
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
send(2, ' '.join(sys.argv[1:])); print(recv())
s.close()
PY
}

cd "$ROOT"

if [ "${1:-}" = "--backup" ]; then
  BK="$RUN/backups/world-$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$(dirname "$BK")"
  cp -r "$RUN/world" "$BK"
  echo "[백업] $BK"
fi

echo "[1/4] 서버 정지"
rcon "say 재배포 — 잠시 후 재기동" >/dev/null 2>&1 || true
rcon stop >/dev/null 2>&1 || true
for _ in $(seq 1 30); do
  pgrep -f "paper.jar" >/dev/null || break
  sleep 1
done
pgrep -f "paper.jar" >/dev/null && { echo "경고: 서버가 안 멈춘다 — 수동 확인 필요"; exit 1; }

echo "[2/4] 빌드 (:server-mvt)"
./gradlew :server-mvt:build -q

echo "[3/4] jar 교체 + config 동기화"
cp "$ROOT"/server-mvt/build/libs/server-mvt-*.jar "$RUN/plugins/"
rm -rf "$RUN/plugins/HoncheonMVT/config"
cp -r "$ROOT/config" "$RUN/plugins/HoncheonMVT/config"

echo "[4/4] 재기동 (백그라운드)"
cd "$RUN" && nohup "$JAVA_HOME/bin/java" -Xms2G -Xmx2G -jar paper.jar nogui \
  > "$RUN/server-console.log" 2>&1 &
for _ in $(seq 1 60); do
  grep -q 'Done (' "$RUN/server-console.log" 2>/dev/null && break
  sleep 1
done
grep -E 'Done \(|HoncheonMVT\] 혼천' "$RUN/server-console.log" | tail -2
echo
echo "→ 인게임에서 광장 중앙에 서서: /혼천 조성   (재조성은 덮어쓴다)"
