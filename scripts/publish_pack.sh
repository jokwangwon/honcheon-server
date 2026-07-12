#!/usr/bin/env bash
set -euo pipefail
# 혼천 리소스팩 — 굽고 바깥에 올린다.
#
#   tools/build_resourcepack.py → zip → GitHub Releases(공개) 에 **덮어쓴다** → 주소는 안 바뀐다.
#
# ★ 왜 바깥인가: 팩 배급자(8123)는 인터넷에 열지 않는다 (공개는 25565 하나뿐. 인증도 TLS 도 없는
#   평문 파일 서버다). 그런데 집 안에서도 공인 IP 로 접속하면 사설 주소에 닿지 못하는 경로가 있다
#   — 게임은 붙는데 팩만 죽는다. 그래서 팩을 바깥에 둔다.
#
# ★ 주소가 고정인 이유: 릴리스 태그를 하나(`pack`)로 두고 자산만 덮어쓴다. 그러면 config 를 고칠 일이 없고,
#   클라이언트는 **sha1 이 바뀐 것을 보고** 새로 받는다 (주소가 같아도 캐시를 버린다).
#
# ★ 이 저장소에 올라가는 것은 zip 하나뿐이다 — 서버 소스도, 토큰도, DB 도 올라가지 않는다.
#   (팩 안에는 png·json 만 있다. 올리기 전에 확인한다.)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="${PACK_REPO:-jokwangwon/honcheon-pack}"
TAG="${PACK_TAG:-pack}"
ZIP="$ROOT/run/pack-http/honcheon_pack.zip"

cd "$ROOT"
echo "[팩] 굽는다"
PACK_MODE=serve bash scripts/serve_resourcepack.sh >/dev/null

# 비밀이 섞였는지 본다 — 바깥에 올리는 것은 되돌릴 수 없다
if unzip -l "$ZIP" | grep -qiE "\.env|token|secret|password|\.properties$|\.sql$|\.jar$"; then
  echo "❌ 팩 안에 올려선 안 될 것이 있다 — 올리지 않는다" >&2
  unzip -l "$ZIP" | grep -iE "\.env|token|secret|password|\.properties$|\.sql$|\.jar$" >&2
  exit 1
fi

SHA1="$(sha1sum "$ZIP" | cut -d' ' -f1)"
echo "[팩] sha1 $SHA1 · $(stat -c%s "$ZIP") bytes"
gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1 \
  || gh release create "$TAG" --repo "$REPO" -t "혼천 리소스팩" -n "서버가 자동으로 배급한다."
gh release upload "$TAG" "$ZIP" --repo "$REPO" --clobber
echo "[팩] 올렸다 — https://github.com/$REPO/releases/download/$TAG/honcheon_pack.zip"
echo "     (주소는 안 바뀐다. 클라이언트는 sha1 이 바뀐 것을 보고 새로 받는다.)"
echo "     서버를 다시 띄우면 플러그인이 실물에서 sha1 을 다시 잰다."
