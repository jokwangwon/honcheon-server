#!/usr/bin/env bash
set -uo pipefail
# 혼천 — **첫 입장 환경**으로 되돌린다 (매 수정 후 테스트용)
#
#   빌드 → 서버 정지 → 플레이어 데이터·원장·접합 삭제 → 나루 월드 삭제 → 재기동
#
# 지우는 것: playerdata · stats · advancements · ledgers.yml · mvt_link(봇 DB) · honcheon_ipdo(나루)
# 남기는 것: whitelist · ops · 세계(청하현·지역·사람·사냥터) · 디스코드 캐릭터
#
# ★ 백업은 항상 뜬다 (run/backup-<시각>/). 되돌리려면 거기서 꺼내라.
# ★ DISCORD_TOKEN 은 프로세스 env 에만 있다 — kill 전에 /proc 에서 확보한다. 파일에 절대 안 쓴다.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export JAVA_HOME="$ROOT/run/jdk-21"

rcon() { python3 - "$@" <<'PY'
import socket, struct, sys
pw = next(l.strip().split('=',1)[1] for l in open('run/mvt/server.properties', encoding='utf-8')
          if l.startswith('rcon.password='))
try:
    s = socket.create_connection(('127.0.0.1', 25575), timeout=120)
except OSError:
    sys.exit(0)
def send(t, b):
    p = struct.pack('<ii', 0, t) + b.encode() + b'\x00\x00'
    s.send(struct.pack('<i', len(p)) + p)
def recv():
    ln = struct.unpack('<i', s.recv(4))[0]
    d = b''
    while len(d) < ln:
        d += s.recv(ln - len(d))
    return d[8:-2].decode('utf-8', 'replace')
send(3, pw); recv(); send(2, ' '.join(sys.argv[1:])); print(recv())
PY
}

echo "══ [1/5] 빌드"
if ./gradlew :server-mvt:build -q 2>&1 | grep -E "error:"; then
  echo "  ❌ 빌드 실패"; exit 1
fi
echo "  통과"

echo "══ [2/5] 백업"
TS=$(date +%Y%m%d-%H%M%S); BK="run/backup-$TS"; mkdir -p "$BK"
cp -r run/mvt/honcheon/playerdata "$BK/" 2>/dev/null || true
cp run/mvt/plugins/HoncheonMVT/ledgers.yml "$BK/" 2>/dev/null || true
cp run/bot/honcheon.db "$BK/honcheon.db" 2>/dev/null || true
echo "  → $BK"

echo "══ [3/5] 서버 정지 · 캐릭터 데이터 삭제"
rcon stop >/dev/null 2>&1 || true
for _ in $(seq 1 40); do pgrep -f paper.jar >/dev/null || break; sleep 1; done
for w in honcheon honcheon_dojang honcheon_ipdo; do
  rm -rf "run/mvt/$w/playerdata" "run/mvt/$w/stats" "run/mvt/$w/advancements"
done
rm -f run/mvt/plugins/HoncheonMVT/ledgers.yml
rm -rf run/mvt/honcheon_ipdo          # 나루는 매번 새로 선다 (수정이 반영되게)
echo "  playerdata · stats · ledgers.yml · 나루 월드 삭제 (화이트리스트·세계는 그대로)"

# 봇의 접합 기록 — 봇은 단일 작성자다. 정지시키고 지운다.
BOTPID=$(pgrep -f "server-bot" | head -1 || true)
if [ -n "${BOTPID:-}" ]; then
  TOKEN=$(tr '\0' '\n' < "/proc/$BOTPID/environ" | grep "^DISCORD_TOKEN=" | cut -d= -f2- || true)
  if [ -z "$TOKEN" ]; then
    echo "  ⚠ 토큰을 못 읽었다 — 봇을 건드리지 않는다 (접합 기록은 남는다)"
  else
    kill "$BOTPID"; for _ in $(seq 1 30); do kill -0 "$BOTPID" 2>/dev/null || break; sleep 1; done
    python3 -c "
import sqlite3
c = sqlite3.connect('run/bot/honcheon.db')
n = c.execute('DELETE FROM mvt_link').rowcount
c.commit()
print(f'  mvt_link {n}행 삭제 — /혼천 접속 부터 다시 (디스코드 캐릭터는 그대로)')
"
    DISCORD_TOKEN="$TOKEN" nohup ./run/jdk-21/bin/java -jar server-bot/build/libs/server-bot-0.1.0.jar \
        > run/bot/bot-console.log 2>&1 &
    echo "  봇 재기동"
  fi
fi

echo "══ [4/5] 배포"
cp server-mvt/build/libs/server-mvt-*.jar run/mvt/plugins/
rm -rf run/mvt/plugins/HoncheonMVT/config && cp -r config run/mvt/plugins/HoncheonMVT/config

echo "══ [5/5] 기동"
(cd run/mvt && nohup ../jdk-21/bin/java -Xms2G -Xmx2G -jar paper.jar nogui > server-console.log 2>&1 &)
until grep -qE 'Done \(' run/mvt/server-console.log 2>/dev/null; do sleep 3; done
echo "  기동 완료 · ERROR $(grep -cE '^\[.*(ERROR|SEVERE)\]' run/mvt/server-console.log)줄"
grep -E "\[팩\] 배급 준비" run/mvt/server-console.log | tail -1 | sed 's/.*MVT\] //'
echo
echo "→ 접속하면 **나루로 소환**된다. 첫 입장 환경이다."
