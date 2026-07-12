#!/usr/bin/env bash
set -euo pipefail
# 혼천 리소스팩 자동 배포 — 서버 → 클라이언트 (접속 시 자동 다운로드)
#
#   팩 컴파일(tools/build_resourcepack.py) → zip → SHA-1 → LAN HTTP 서버(:8123) 서빙
#   → run/mvt/server.properties 의 resource-pack / resource-pack-sha1 갱신
#
# 사용:
#   scripts/serve_resourcepack.sh            # 빌드 + 서빙 (이미 떠 있으면 재사용)
#   scripts/serve_resourcepack.sh --stop     # HTTP 서버 정지
#   scripts/serve_resourcepack.sh --restart  # 강제 재기동
#   PACK_PORT=9123 scripts/serve_resourcepack.sh   # 포트 변경
#
# ┌─ 보안 (엄수) ────────────────────────────────────────────────────────────┐
# │ 이 HTTP 서버는 LAN 전용이다. 공유기에서 8123 포트를 절대 포워딩하지 마라.  │
# │ 외부 공개 대상은 25565(마인크래프트) 하나뿐이다.                          │
# │ 인증·TLS 가 없는 평문 파일 서버라 외부에 열면 그대로 노출된다.            │
# │ 서빙 디렉터리는 zip 하나만 든 전용 폴더(run/pack-http)다 — server.properties│
# │ (rcon 비밀번호 포함) 가 있는 run/mvt 를 통째로 서빙하지 않는다.            │
# └──────────────────────────────────────────────────────────────────────────┘
#
# 팩 게이트 불가침: require-resource-pack=false — 팩을 거절해도 플레이는 된다.

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/run/mvt"
SERVE_DIR="$ROOT/run/pack-http"        # HTTP 로 노출되는 유일한 폴더 (zip 만 둔다)
ZIP_NAME="honcheon_pack.zip"
ZIP="$SERVE_DIR/$ZIP_NAME"
PORT="${PACK_PORT:-8123}"
PIDFILE="$SERVE_DIR/http.pid"
LOGFILE="$SERVE_DIR/http.log"

stop_server() {
  if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
    kill "$(cat "$PIDFILE")" 2>/dev/null || true
    echo "[팩] HTTP 서버 정지 (pid $(cat "$PIDFILE"))"
  else
    echo "[팩] 떠 있는 HTTP 서버 없음"
  fi
  rm -f "$PIDFILE"
}

case "${1:-}" in
  --stop) stop_server; exit 0 ;;
  --restart) stop_server ;;
esac

mkdir -p "$SERVE_DIR"

# ─── 1. 팩 컴파일 ───
echo "[팩 1/5] 리소스팩 컴파일 (tools/build_resourcepack.py)"
python3 "$ROOT/tools/build_resourcepack.py"

# ─── 수동 설치 모드 (PACK_MODE=manual, 기본값) ───
# 클라이언트가 서버의 LAN 주소에 도달하지 못하는 경우(라우터 너머 접속)에는 자동 배포가
# 실패하고 접속 때마다 실패 팝업이 뜬다. 그럴 땐 zip 만 만들어 두고 사람이 옮겨 넣는다.
# 자동 배포를 되살리려면: PACK_MODE=serve scripts/serve_resourcepack.sh
if [ "${PACK_MODE:-manual}" = "manual" ]; then
  MANUAL_ZIP="$ROOT/run/honcheon_pack.zip"
  python3 - "$ROOT/resourcepack" "$MANUAL_ZIP" <<'PY'
import sys, zipfile
from pathlib import Path
src, out = Path(sys.argv[1]), Path(sys.argv[2])
files = sorted(p for p in src.rglob('*') if p.is_file())
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for p in files:
        info = zipfile.ZipInfo(str(p.relative_to(src)), date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        z.writestr(info, p.read_bytes())
print(f"[팩] 수동 설치용 zip — {out} ({len(files)}개 파일, {out.stat().st_size} bytes)")
PY
  # 자동 다운로드 끄기 — 안 그러면 접속마다 "리소스팩 다운로드 실패" 팝업이 뜬다
# ★ server.properties 에 URL 을 박지 않는다 — 여기가 "다운로드만 실패" 의 진원지였다.
#   이 기계는 주소가 여럿이다 (LAN 192.168.x · Tailscale 100.x · 공인 IP). 그런데 이 파일엔 URL 을
#   **하나**만 적을 수 있다. LAN 주소를 박아 두면, LAN 밖에서 들어온 사람은 게임(25565)은 붙는데
#   팩(8123)만 실패한다 — 그 사람 컴퓨터에서 **닿지 않는 주소**이기 때문이다. 로그엔 아무것도 안 남는다.
#
#   그래서 배급은 이제 플러그인이 한다 (PackPusher.java + config/resource_pack.yml).
#   플러그인은 **그 사람이 접속할 때 친 주소**로 팩을 준다 — 방금 그걸로 들어왔으니 닿는 것이 증명된 주소다.
#   sha1 도 플러그인이 실물에서 매 기동 다시 잰다 (사람이 옮겨 적으면 언젠가 낡고, 낡으면 조용히 거절된다).
#
#   이 스크립트가 할 일은 둘뿐이다: **팩을 굽고, 8123 에서 내주는 것.**
if [ -f "$RUN/server.properties" ]; then
  python3 - "$RUN/server.properties" <<'PY'
import sys
p = sys.argv[1]
keys = ('resource-pack=', 'resource-pack-sha1=', 'resource-pack-id=', 'resource-pack-prompt=')
out = []
for line in open(p, encoding='utf-8'):
    if line.startswith(keys):
        out.append(line.split('=', 1)[0] + '=\n')       # 비워 둔다 — 배급은 플러그인의 일이다
    elif line.startswith('require-resource-pack='):
        out.append('require-resource-pack=false\n')     # 팩 게이트 불가침
    else:
        out.append(line)
open(p, 'w', encoding='utf-8').write(''.join(out))
PY
  echo "      server.properties — 팩 항목을 비웠다 (배급은 플러그인이 접속 주소를 따라 한다)"
fi
  stop_server
  echo "[팩] 수동 설치 모드 — 자동 다운로드 꺼짐 (접속 실패 팝업 없음)"
  echo "     → 이 zip 을 마인크래프트 PC 의 .minecraft/resourcepacks/ 에 넣고 게임에서 켜라"
  exit 0
fi

# ─── 2. zip (결정론) ───
# 파일명 정렬 + 고정 타임스탬프로 묶는다 → 내용이 같으면 SHA-1 도 같다.
# (mtime 이 섞이면 팩이 안 바뀌었는데도 sha1 이 달라져 클라이언트가 매번 재다운로드한다)
echo "[팩 2/5] zip 패키징 (결정론 — 내용이 같으면 sha1 동일)"
python3 - "$ROOT/resourcepack" "$ZIP" <<'PY'
import sys, zipfile
from pathlib import Path
src, out = Path(sys.argv[1]), Path(sys.argv[2])
files = sorted(p for p in src.rglob('*') if p.is_file())
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for p in files:
        info = zipfile.ZipInfo(str(p.relative_to(src)), date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        z.writestr(info, p.read_bytes())
print(f"      {out} — {len(files)}개 파일, {out.stat().st_size} bytes")
PY

# ─── 3. SHA-1 ───
SHA1="$(sha1sum "$ZIP" | cut -d' ' -f1)"
echo "[팩 3/5] SHA-1 = $SHA1"

# ─── 4. LAN IP 탐지 ───
# localhost/127.0.0.1 을 쓰면 같은 PC 클라이언트만 받는다 — 다른 기기는 못 받는다.
# ★ 결정론적 IP 탐지 — `hostname -I` 의 첫 토큰은 **순서가 보장되지 않는다.**
#   이 기계엔 docker 브리지가 4개(172.17~172.20) 있어서, 그것이 앞에 오는 날 아무도 팩을 못 받는다.
#   기본 경로의 소스 주소를 물으면 항상 진짜 LAN IP 가 나온다.
#
# ★ 그리고 팩 다운로드 실패의 진짜 원인은 **네트워크가 아니라 주소**였다:
#   http.log 는 200 OK 를 기록했다 — 클라이언트는 정상으로 받았다. 문제는 **다른 경로로 접속한 클라이언트**다.
#   Tailscale(100.x)로 들어오면 팩 URL(192.168.x)에 못 닿는다.
#   → 플레이어가 실제로 접속하는 주소를 PACK_HOST 로 못박아라 (예: PACK_HOST=100.102.41.122).
LAN_IP="${PACK_HOST:-$(ip route get 1.1.1.1 2>/dev/null | grep -oP 'src \K\S+')}"
if [ -z "$LAN_IP" ]; then
  LAN_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
fi
if [ -z "$LAN_IP" ]; then
  echo "경고: LAN IP 탐지 실패 — 127.0.0.1 로 폴백한다 (같은 PC 클라이언트만 팩을 받는다)" >&2
  LAN_IP="127.0.0.1"
fi
URL="http://$LAN_IP:$PORT/$ZIP_NAME"
echo "[팩 4/5] LAN IP $LAN_IP → $URL"

# ─── 5. HTTP 서버 (이미 떠 있으면 재사용) ───
port_busy() { (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; }

if port_busy; then
  # 우리 팩 서버가 맞는지 확인 — 남의 프로세스가 8123 을 쥐고 있으면 덮지 않는다.
  if curl -sfI --max-time 3 "http://127.0.0.1:$PORT/$ZIP_NAME" >/dev/null 2>&1; then
    echo "[팩 5/5] HTTP 서버 재사용 — :$PORT 이미 서빙 중 (zip 파일은 방금 교체됨 → 즉시 반영)"
  else
    echo "오류: 포트 $PORT 를 다른 프로세스가 점유 중이다 (우리 팩 서버가 아니다)." >&2
    echo "      PACK_PORT=<다른포트> 로 바꾸거나 점유 프로세스를 정리하라." >&2
    exit 1
  fi
else
  # --directory 로 서빙 폴더를 못박는다 (cwd 유출 방지). --bind 0.0.0.0 = LAN 노출 (의도된 범위).
  nohup python3 -m http.server "$PORT" --bind 0.0.0.0 --directory "$SERVE_DIR" \
    > "$LOGFILE" 2>&1 &
  echo $! > "$PIDFILE"
  sleep 0.5
  if ! kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
    echo "오류: HTTP 서버 기동 실패 — $LOGFILE 확인" >&2
    exit 1
  fi
  echo "[팩 5/5] HTTP 서버 기동 — pid $(cat "$PIDFILE"), :$PORT, 서빙 폴더 $SERVE_DIR"
fi

# ─── 자가검진 — 스크립트가 그 자리에서 실패를 말한다 ───
# 구판은 "클라이언트에 팝업이 뜬다"는 미궁을 남겼다. 여기서 **LAN IP 로** 직접 받아 SHA-1 을 대조한다
# (127.0.0.1 로 받으면 아무것도 증명 못 한다 — 같은 기계니까).
FETCHED="$(curl -sf --max-time 5 "$URL" 2>/dev/null | sha1sum | cut -d' ' -f1)"
if [ "$FETCHED" != "$SHA1" ]; then
  echo "❌ 자가검진 실패 — $URL 로 받은 것이 우리 zip 이 아니다 (받은 sha1=$FETCHED)" >&2
  echo "   플레이어가 다른 경로(예: Tailscale 100.x)로 접속한다면 PACK_HOST 로 그 주소를 못박아라." >&2
  echo "   자동 배포를 끄고 수동 설치로 폴백한다 — 접속 실패 팝업은 뜨지 않는다." >&2
  sed -i 's|^resource-pack=.*|resource-pack=|; s|^resource-pack-sha1=.*|resource-pack-sha1=|' "$RUN/server.properties" 2>/dev/null || true
  stop_server
  exit 0
fi
echo "[팩] 자가검진 통과 — $URL 에서 받은 sha1 이 우리 zip 과 같다"

# ─── server.properties 갱신 ───
# 주의: 서버가 떠 있는 동안 고쳐도 소용없다 (부팅 시 1회 로드 + 종료 시 되쓰기).
#       반드시 서버 정지 상태에서 고치고 기동하라 — redeploy_mvt.sh 가 그 순서를 지킨다.
if [ ! -f "$RUN/server.properties" ]; then
  echo "경고: $RUN/server.properties 가 없다 — 서버를 한 번 기동해 생성한 뒤 다시 실행하라" >&2
  exit 0
fi
if pgrep -f "paper.jar" >/dev/null 2>&1; then
  echo "경고: 서버가 떠 있다 — 지금 쓴 server.properties 는 종료 시 덮어써질 수 있다." >&2
  echo "      팩 설정을 확실히 먹이려면 서버를 내리고 다시 실행하라 (scripts/redeploy_mvt.sh)." >&2
fi

# ★ 여기서 URL 을 박지 않는다 — 이 자리가 "다운로드만 실패" 의 진원지였다.
#   이 파일엔 URL 을 **하나**만 적을 수 있는데, 이 기계는 주소가 여럿이다
#   (LAN 192.168.x · Tailscale 100.x · 공인 IP). LAN 주소를 박으면 LAN 밖에서 들어온 사람은
#   게임(25565)은 붙는데 팩(8123)만 죽는다 — 그 사람 컴퓨터에서 **닿지 않는 주소**라서다.
#   서버 로그엔 아무것도 안 남는다. 그래서 이 실패는 눈에 안 보인다.
#
#   배급은 이제 플러그인이 한다 (PackPusher.java + config/resource_pack.yml):
#   **그 사람이 접속할 때 친 주소**로 준다 — 방금 그걸로 들어왔으니 닿는 것이 증명된 주소다.
#   sha1 도 실물에서 매 기동 다시 잰다 (사람이 옮겨 적으면 언젠가 낡고, 낡으면 조용히 거절된다).
python3 - "$RUN/server.properties" <<'PY'
import sys
from pathlib import Path
path = Path(sys.argv[1])
blank = ('resource-pack', 'resource-pack-sha1', 'resource-pack-id', 'resource-pack-prompt')
out = []
for line in path.read_text(encoding='utf-8').splitlines():
    key = line.split('=', 1)[0] if '=' in line and not line.startswith('#') else None
    if key in blank:
        out.append(key + '=')                       # 비워 둔다 — 바닐라는 팩을 밀지 않는다
    elif key == 'require-resource-pack':
        out.append('require-resource-pack=false')   # 팩 게이트 불가침
    else:
        out.append(line)
path.write_text('\n'.join(out) + '\n', encoding='utf-8')
PY
echo "      server.properties — 팩 항목을 비웠다 (배급은 플러그인이 접속 주소를 따라 한다)"

echo
echo "→ 클라이언트는 접속 시 팩을 자동으로 받는다 (수동 설치 불필요)."
echo "→ 렌더러도 같은 팩을 읽는다: HONCHEON_PACK_DIR=$ROOT/resourcepack (미지정 시 자동 탐색)"
echo "⚠ 보안: $PORT 는 LAN 전용이다. 공유기 포트포워딩은 25565 만. $PORT 는 절대 열지 마라."
