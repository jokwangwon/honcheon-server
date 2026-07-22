#!/usr/bin/env bash
set -euo pipefail
# 혼천 MVT 빠른 재배포 — 건축 반복(루프) 전용
# 정지(RCON) → 빌드 → jar 교체 → 재기동. 월드는 보존한다 (조성은 인게임에서 /혼천 조성).
# 사용: scripts/redeploy_mvt.sh [--backup]
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/run/mvt"
# ★ **빌드와 기동의 JDK 를 가른다** (2026-07-20).
#   빌드는 21 로 남긴다 — 산출 바이트코드의 목표를 바꾸지 않기 위해서다.
#   기동만 25 로 올린다 (BetterModel 3.x 가 class 69 라 21 에서는 못 뜬다).
#   21 로 컴파일한 것을 25 에서 돌리는 것은 안전하다(앞방향 호환). 그 반대가 안 된다.
export JAVA_HOME="$ROOT/run/jdk-21"      # ← gradle 빌드용. 올리지 마라.
SERVER_JAVA="$ROOT/run/jdk-25/bin/java"  # ← 서버 런타임용.
source "$ROOT/scripts/lib/live_pids.sh"   # 라이브 PID 는 cwd 로 찾는다 (정본)

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
  [ -z "$(live_pids)" ] && break
  sleep 1
done
# ★ 라이브만 본다 (2026-07-20): 옛 패턴 "paper.jar" 는 **테스트 서버(run/mvt-test)까지** 잡아
#   라이브가 이미 멈췄는데도 "안 멈춘다"고 오판했다 — 재배포가 1단계에서 죽어 jar·config 가
#   옛것으로 남았고, 서버는 옛 버전으로 돌았다. 그것을 모르고 "배포 완료"라 할 뻔했다.
[ -n "$(live_pids)" ] && { echo "경고: 라이브가 안 멈춘다 (PID:$(live_pids)) — 수동 확인 필요"; exit 1; }

echo "[2/5] 리소스팩 빌드·서빙 (서버 정지 상태에서 server.properties 갱신)"
# HTTP 서버가 이미 떠 있으면 재사용한다 (재배포마다 중복 기동하지 않는다 — 스크립트가 포트를 확인).
# 보안: 8123 은 LAN 전용. 공유기 포워딩은 25565 만 — 8123 은 절대 열지 마라.
bash "$ROOT/scripts/serve_resourcepack.sh"

echo "[3/5] 빌드 (:server-mvt)"
./gradlew :server-mvt:build -q

echo "[4/5] jar 교체 + config 동기화"
cp "$ROOT"/server-mvt/build/libs/server-mvt-*.jar "$RUN/plugins/"
# ★ 재맵 캐시 제거 (2026-07-22) — jar 만 갈고 캐시를 두면 플러그인이 **조용히** 안 실린다
#   (증상: 혼천 명령 불완전·효과 0 — HANDOFF §4-검기 함정 2번)
rm -rf "$RUN/plugins/.paper-remapped"
rm -rf "$RUN/plugins/HoncheonMVT/config"
cp -r "$ROOT/config" "$RUN/plugins/HoncheonMVT/config"

echo "[5/5] 재기동 (백그라운드)"
# -DHONCHEON_PACK_DIR — 팩 인식 렌더러(TownRender)가 읽을 팩 경로. 없으면 렌더러가 상대경로로
# 자동 탐색하고, 그것도 실패하면 하드코딩 팔레트로 폴백한다 (팩 없이도 렌더는 돈다).
cd "$RUN" && nohup "$SERVER_JAVA" -Xms2G -Xmx2G \
  -DHONCHEON_PACK_DIR="$ROOT/resourcepack" -jar paper.jar nogui \
  > "$RUN/server-console.log" 2>&1 &
# ★ 대기 150초 (2026-07-23): 60초는 실부팅(~90~105초)보다 짧아 성공을 exit 1 로 오보했다
for _ in $(seq 1 150); do
  grep -q 'Done (' "$RUN/server-console.log" 2>/dev/null && break
  sleep 1
done
grep -E 'Done \(|HoncheonMVT\] 혼천' "$RUN/server-console.log" | tail -2 \
  || { echo "경고: 150초 안에 Done 이 안 떴다 — 콘솔을 직접 확인하라"; exit 1; }
echo
echo "→ 인게임에서 광장 중앙에 서서: /혼천 조성   (재조성은 덮어쓴다)"
