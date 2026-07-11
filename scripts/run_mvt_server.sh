#!/usr/bin/env bash
set -euo pipefail
# 혼천 MVT — 관리자 1인 검증 서버 원커맨드 기동 (로컬 PC 전용)
# 요구: Java 21+, gradle(또는 ./gradlew), curl, python3. 접속: localhost:25565
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/run/mvt"
mkdir -p "$RUN/plugins"

# Java 21+ 탐지 — Paper 1.21.4 와 :server-mvt 빌드 모두 21이 필요하다.
# 시스템 java 가 21 미만이면 run/jdk-21 (수동 설치본)을 찾는다.
find_java21() {
  local cand ver
  for cand in "${JAVA_HOME:+$JAVA_HOME/bin/java}" java "$ROOT/run/jdk-21/bin/java"; do
    [ -n "$cand" ] && command -v "$cand" >/dev/null 2>&1 || continue
    ver=$("$cand" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')
    if [ "${ver:-0}" -ge 21 ] 2>/dev/null; then echo "$cand"; return 0; fi
  done
  return 1
}
JAVA_BIN="$(find_java21)" || {
  echo "오류: Java 21+ 를 찾지 못했다. JDK 21 을 설치하거나 run/jdk-21 에 풀어 두라." >&2
  echo "예: curl -fsSL -o jdk21.tar.gz 'https://api.adoptium.net/v3/binary/latest/21/ga/linux/$(uname -m | sed s/x86_64/x64/)/jdk/hotspot/normal/eclipse'" >&2
  exit 1
}
export JAVA_HOME="$(cd "$(dirname "$JAVA_BIN")/.." && pwd)"
echo "      Java: $JAVA_BIN ($("$JAVA_BIN" -version 2>&1 | head -1))"

echo "[1/4] 플러그인 빌드 (:server-mvt)"
if [ -x "$ROOT/gradlew" ]; then GRADLE="$ROOT/gradlew"; else GRADLE="gradle"; fi
(cd "$ROOT" && "$GRADLE" :server-mvt:build -q)
cp "$ROOT"/server-mvt/build/libs/server-mvt-*.jar "$RUN/plugins/"

echo "[2/4] config 동기화 (단일 진실 원천 → 플러그인 데이터 폴더)"
mkdir -p "$RUN/plugins/HoncheonMVT"
rm -rf "$RUN/plugins/HoncheonMVT/config"
cp -r "$ROOT/config" "$RUN/plugins/HoncheonMVT/config"

echo "[2.5/4] 리소스팩 컴파일·패키징 (기세·화후 글리프)"
python3 "$ROOT/tools/build_resourcepack.py"
(cd "$ROOT/resourcepack" && rm -f "$RUN/honcheon_pack.zip" && zip -qr "$RUN/honcheon_pack.zip" pack.mcmeta assets)
echo "      → 클라이언트 resourcepacks 폴더에 $RUN/honcheon_pack.zip 를 넣고 활성화하세요"
echo "      (미설치 시 기세·게이지 글리프가 □ 로 보임 — 기능은 동일)"

echo "[3/4] Paper 1.21.4 다운로드 (최초 1회)"
# 구 api.papermc.io v2 는 410 Gone — 후속 Fill API(v3)를 쓴다.
if [ ! -f "$RUN/paper.jar" ]; then
  URL=$(curl -fsS "https://fill.papermc.io/v3/projects/paper/versions/1.21.4/builds/latest" \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['downloads']['server:default']['url'])")
  curl -fSo "$RUN/paper.jar" "$URL"
fi
echo "eula=true" > "$RUN/eula.txt"

# 외부 공개 모드 — HONCHEON_PUBLIC=1 이면 화이트리스트 강제 (포트포워딩 전 필수)
# 안내: docs/mvt/admin_test_guide.md 「외부 공개 체크리스트」
if [ "${HONCHEON_PUBLIC:-}" = "1" ] && [ -f "$RUN/server.properties" ]; then
  sed -i 's/^white-list=.*/white-list=true/; s/^enforce-whitelist=.*/enforce-whitelist=true/' "$RUN/server.properties"
  echo "      공개 모드: white-list 강제 — 콘솔에서 whitelist add <닉네임> 필요"
fi

echo "[4/4] 기동 — 접속 후 콘솔에서: op <닉네임>  /  테스트 절차: docs/mvt/admin_test_guide.md"
cd "$RUN" && exec "$JAVA_BIN" -Xms2G -Xmx2G -jar paper.jar nogui
