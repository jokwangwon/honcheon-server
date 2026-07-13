#!/usr/bin/env bash
set -uo pipefail
# 혼천 — 세계 검증 하네스 (루프의 눈을 한 번에 돌린다)
#
#   빌드 → 새 월드(자연 동굴 없음 데이터팩) → 청하현 조성 → 지역 조성(산채·화산) →
#   검수 4종을 전부 돌려 요약한다:
#     · 마을 검수 (TownAudit 12종)      — 길·처마·물매·소품·수묵·야간·계약
#     · 환경 검수 (TerrainAudit 6종)    — 바닥 공동·수역·경계·연결성·부유·지하 동굴
#     · 지역 검수 (RegionAudit 5종)     — 도달성·구조 계약·허공·광원·수묵
#     · 정적 검산 (game/combat/texture/motion)
#
# 사용:
#   scripts/verify_world.sh              # 전부
#   scripts/verify_world.sh --town       # 청하현만 (빠름)
#   scripts/verify_world.sh --keep       # 월드를 지우지 않고 재사용 (조성만 다시)
#
# 규약: 이 스크립트만이 서버를 띄운다 (병렬 작업자는 서버를 만지지 않는다).

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/run/mvt"
export JAVA_HOME="$ROOT/run/jdk-21"
cd "$ROOT"

TOWN_ONLY=0
KEEP=0
for arg in "$@"; do
  case "$arg" in
    --town) TOWN_ONLY=1 ;;
    --keep) KEEP=1 ;;
  esac
done

rcon() {   # 콘솔 명령 — 서버가 떠 있을 때만
  python3 - "$@" <<'PY'
import socket, struct, sys
pw = None
for line in open('run/mvt/server.properties', encoding='utf-8'):
    if line.startswith('rcon.password='):
        pw = line.strip().split('=', 1)[1]
try:
    s = socket.create_connection(('127.0.0.1', 25575), timeout=180)
except OSError:
    sys.exit(0)
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
PY
}

stop_server() {
  rcon stop >/dev/null 2>&1 || true
  for _ in $(seq 1 40); do pgrep -f "paper.jar" >/dev/null || break; sleep 1; done
}

echo "══ [1/6] 빌드"
if ! ./gradlew :server-mvt:build -q 2>&1 | grep -E "error:" ; then
  echo "  빌드 통과"
else
  echo "  ❌ 빌드 실패 — 위 오류를 보라 (다른 작업자가 편집 중일 수 있다)"
  exit 1
fi

echo "══ [2/6] 서버 정지 · 월드 준비"
stop_server
cp "$ROOT"/server-mvt/build/libs/server-mvt-*.jar "$RUN/plugins/"
rm -rf "$RUN/plugins/HoncheonMVT/config" && cp -r "$ROOT/config" "$RUN/plugins/HoncheonMVT/config"
if [ "$KEEP" = "0" ]; then
  rm -rf "$RUN/honcheon" "$RUN/plugins/HoncheonMVT/anchors.yml" \
         "$RUN/plugins/HoncheonMVT/zones.yml" "$RUN/plugins/HoncheonMVT/regions.yml"
  mkdir -p "$RUN/honcheon/datapacks"
  cp -r "$ROOT/worldgen/honcheon_no_caves" "$RUN/honcheon/datapacks/"   # 자연 동굴 없음
  echo "  새 월드 (자연 동굴 없음 데이터팩 적용)"
else
  echo "  기존 월드 재사용"
fi

echo "══ [3/6] 서버 기동"
(cd "$RUN" && nohup "$JAVA_HOME/bin/java" -Xms2G -Xmx2G -jar paper.jar nogui > "$RUN/server-console.log" 2>&1 &)
for _ in $(seq 1 120); do
  grep -qE 'Done \(|Failed to load registries' "$RUN/server-console.log" 2>/dev/null && break
  sleep 3
done
if grep -q "Failed to load registries" "$RUN/server-console.log"; then
  echo "  ❌ 레지스트리 로딩 실패 — 데이터팩(worldgen)이 깨졌다"
  grep -A3 "Registry loading errors" "$RUN/server-console.log" | head -6
  exit 1
fi
echo "  기동 완료"

echo "══ [4/6] 조성"
rcon 혼천 세계조성 >/dev/null 2>&1
for _ in $(seq 1 80); do grep -q "\[세계조성\]" "$RUN/server-console.log" && break; sleep 3; done
grep "\[세계조성\]" "$RUN/server-console.log" | tail -1 | sed 's/.*HoncheonMVT\] //'

if [ "$TOWN_ONLY" = "0" ]; then
  for place in nokrim_sochae hwasan; do
    rcon 혼천 지역조성 "$place" >/dev/null 2>&1
    for _ in $(seq 1 120); do grep -q "지역조성\] $place" "$RUN/server-console.log" && break; sleep 4; done
    grep "지역조성\] $place" "$RUN/server-console.log" | tail -1 | sed 's/.*HoncheonMVT\] //'
    grep "\[지형/동굴\] $place" "$RUN/server-console.log" | tail -1 | sed 's/.*HoncheonMVT\] //'
  done
fi

echo "══ [5/6] 검수 (인게임)"
strip() { sed 's/§.//g'; }
echo "── 마을 검수 (12종)"
rcon 혼천 검수 2>&1 | strip | grep -E "^(❌|⚠)|위반|암흑|주 동선|광원 밀도" | head -12
echo "── 환경 검수 (6종) — 청하현"
rcon 혼천 환경검수 2>&1 | strip | grep -E "^(❌|⚠)|위반|지하|경계 표본|밑이 빈" | head -10
if [ "$TOWN_ONLY" = "0" ]; then
  for place in nokrim_sochae hwasan; do
    echo "── 지역 검수 — $place"
    rcon 혼천 지역검수 "$place" 2>&1 | strip | grep -E "위반|걸어" | head -4
    echo "── 환경 검수 — $place"
    rcon 혼천 환경검수 "$place" 2>&1 | strip | grep -E "위반|진입|지하" | head -4
  done
fi

echo "══ [6/6] 정적 검산 (config·팩·전투)"
for tool in game_audit combat_audit texture_audit motion_audit model_key_audit; do
  [ -f "$ROOT/tools/$tool.py" ] || continue
  line=$(python3 "$ROOT/tools/$tool.py" 2>&1 | grep -E "총평" | tail -1)
  printf "  %-16s %s\n" "$tool" "${line:-(총평 없음)}"
done

echo
echo "→ 조감도: /혼천 조감 [지역id]  ·  로그: $RUN/server-console.log"
