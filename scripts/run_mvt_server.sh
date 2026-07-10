#!/usr/bin/env bash
set -euo pipefail
# 혼천 MVT — 관리자 1인 검증 서버 원커맨드 기동 (로컬 PC 전용)
# 요구: Java 21+, gradle(또는 ./gradlew), curl, python3. 접속: localhost:25565
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/run/mvt"
mkdir -p "$RUN/plugins"

echo "[1/4] 플러그인 빌드 (:server-mvt)"
if [ -x "$ROOT/gradlew" ]; then GRADLE="$ROOT/gradlew"; else GRADLE="gradle"; fi
(cd "$ROOT" && "$GRADLE" :server-mvt:build -q)
cp "$ROOT"/server-mvt/build/libs/server-mvt-*.jar "$RUN/plugins/"

echo "[2/4] config 동기화 (단일 진실 원천 → 플러그인 데이터 폴더)"
mkdir -p "$RUN/plugins/HoncheonMVT"
rm -rf "$RUN/plugins/HoncheonMVT/config"
cp -r "$ROOT/config" "$RUN/plugins/HoncheonMVT/config"

echo "[3/4] Paper 1.21.4 다운로드 (최초 1회)"
if [ ! -f "$RUN/paper.jar" ]; then
  BUILD=$(curl -fsS https://api.papermc.io/v2/projects/paper/versions/1.21.4/builds \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['builds'][-1]['build'])")
  curl -fSo "$RUN/paper.jar" \
    "https://api.papermc.io/v2/projects/paper/versions/1.21.4/builds/$BUILD/downloads/paper-1.21.4-$BUILD.jar"
fi
echo "eula=true" > "$RUN/eula.txt"

echo "[4/4] 기동 — 접속 후 콘솔에서: op <닉네임>  /  테스트 절차: docs/mvt/admin_test_guide.md"
cd "$RUN" && exec java -Xms2G -Xmx2G -jar paper.jar nogui
