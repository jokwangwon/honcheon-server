#!/usr/bin/env bash
set -euo pipefail
# config 만 바꿨을 때 — 재빌드 없이 정지→config동기화→재기동 (Java 무변경 전용)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/run/mvt"
# ★ 이 스크립트는 재빌드를 안 한다 — JAVA_HOME 이 곧 서버 런타임이다.
#   BetterModel 3.x(class 69) 때문에 25 로 올렸다 (2026-07-20).
export JAVA_HOME="$ROOT/run/jdk-25"
rcon(){ python3 - "$@" <<'PY'
import socket,struct,sys
pw=None
for line in open('run/mvt/server.properties',encoding='utf-8'):
    if line.startswith('rcon.password='): pw=line.strip().split('=',1)[1]
try: s=socket.create_connection(('127.0.0.1',25575),timeout=5)
except OSError: sys.exit(0)
def snd(t,b): p=struct.pack('<ii',0,t)+b.encode()+b'\x00\x00'; s.send(struct.pack('<i',len(p))+p)
def rcv():
    ln=struct.unpack('<i',s.recv(4))[0]; d=b''
    while len(d)<ln: d+=s.recv(ln-len(d))
    return d[8:-2].decode('utf-8','replace')
snd(3,pw); rcv(); snd(2,' '.join(sys.argv[1:])); print(rcv()); s.close()
PY
}
cd "$ROOT"
echo "[1/3] 정지"
rcon "say config 재적용 — 잠시 후 재기동" >/dev/null 2>&1 || true
rcon stop >/dev/null 2>&1 || true
for _ in $(seq 1 30); do pgrep -f paper.jar >/dev/null || break; sleep 1; done
pgrep -f paper.jar >/dev/null && { echo "안 멈춤"; exit 1; }
echo "[2/3] config 동기화 (jar·팩 그대로)"
rm -rf "$RUN/plugins/HoncheonMVT/config"; cp -r "$ROOT/config" "$RUN/plugins/HoncheonMVT/config"
echo "[3/3] 재기동"
cd "$RUN" && nohup "$JAVA_HOME/bin/java" -Xms2G -Xmx2G -DHONCHEON_PACK_DIR="$ROOT/resourcepack" -jar paper.jar nogui > "$RUN/server-console.log" 2>&1 &
for _ in $(seq 1 60); do grep -q 'Done (' "$RUN/server-console.log" 2>/dev/null && break; sleep 1; done
grep -E 'Done \(' "$RUN/server-console.log" | tail -1
