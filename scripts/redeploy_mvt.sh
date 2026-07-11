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

echo "[1/5] 서버 정지"
rcon "say 재배포 — 잠시 후 재기동" >/dev/null 2>&1 || true
rcon stop >/dev/null 2>&1 || true
for _ in $(seq 1 30); do
  pgrep -f "paper.jar" >/dev/null || break
  sleep 1
done
pgrep -f "paper.jar" >/dev/null && { echo "경고: 서버가 안 멈춘다 — 수동 확인 필요"; exit 1; }

echo "[2/5] 리소스팩 빌드·서빙 (서버 정지 상태에서 server.properties 갱신)"
# HTTP 서버가 이미 떠 있으면 재사용한다 (재배포마다 중복 기동하지 않는다 — 스크립트가 포트를 확인).
# 보안: 8123 은 LAN 전용. 공유기 포워딩은 25565 만 — 8123 은 절대 열지 마라.
bash "$ROOT/scripts/serve_resourcepack.sh"

echo "[3/5] 빌드 (:server-mvt)"
./gradlew :server-mvt:build -q

echo "[4/5] jar 교체 + config 동기화"
cp "$ROOT"/server-mvt/build/libs/server-mvt-*.jar "$RUN/plugins/"
rm -rf "$RUN/plugins/HoncheonMVT/config"
cp -r "$ROOT/config" "$RUN/plugins/HoncheonMVT/config"

echo "[5/5] 재기동 (백그라운드)"
# -DHONCHEON_PACK_DIR — 팩 인식 렌더러(TownRender)가 읽을 팩 경로. 없으면 렌더러가 상대경로로
# 자동 탐색하고, 그것도 실패하면 하드코딩 팔레트로 폴백한다 (팩 없이도 렌더는 돈다).
cd "$RUN" && nohup "$JAVA_HOME/bin/java" -Xms2G -Xmx2G \
  -DHONCHEON_PACK_DIR="$ROOT/resourcepack" -jar paper.jar nogui \
  > "$RUN/server-console.log" 2>&1 &
for _ in $(seq 1 60); do
  grep -q 'Done (' "$RUN/server-console.log" 2>/dev/null && break
  sleep 1
done
grep -E 'Done \(|HoncheonMVT\] 혼천' "$RUN/server-console.log" | tail -2
echo
echo "→ 인게임에서 광장 중앙에 서서: /혼천 조성   (재조성은 덮어쓴다)"
