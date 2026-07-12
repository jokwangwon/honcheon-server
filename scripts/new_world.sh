#!/usr/bin/env bash
set -euo pipefail
# 혼천 — 전용 월드 `honcheon` 만들기
#
#   기존 `world` 는 건드리지 않는다. 새 월드를 하나 더 만들고 서버가 그걸 쓰게 바꾼다.
#   (섞어 쓰지 마라 — 청하현은 world_map.yml 원점 (0, ?, 0) 에 서고, 기존 world 는 이미 흩뜨려져 있다)
#
# 사용법:
#   scripts/new_world.sh [시드]            # 시드 생략 시 map_spec 고정 시드 20260710
#   scripts/new_world.sh 20260710
#
# 시드를 고르는 법:
#   ① 서버를 띄우고  /혼천 시드검사 20260710 12345 99999
#      → 등록 좌표(청하현=평지 · 사냥터=산 · 흑수나루=물가 · 폐사당=마른 땅)의 지형을 실측해 점수로 보고한다
#   ② 최고점 시드를 골라  scripts/new_world.sh <그 시드>
#   ③ 서버 재기동 →  /혼천 세계조성
#
# ★ 서버를 먼저 끄고 실행하라 (level-name 은 기동 시에만 읽힌다).

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/run/mvt"
PROPS="$RUN/server.properties"
WORLD_NAME="honcheon"
SEED="${1:-20260710}"      # 기본값 = config/map_spec/cheongha_hyeon_map.yml 의 고정 시드

if [ ! -f "$PROPS" ]; then
  echo "오류: $PROPS 가 없다 — 서버를 한 번은 띄워야 한다 (scripts/run_mvt_server.sh)" >&2
  exit 1
fi

if pgrep -f "paper.jar" >/dev/null 2>&1; then
  echo "오류: MVT 서버가 돌고 있다. 먼저 끄라 (콘솔에서 stop)." >&2
  echo "      level-name 은 기동 시에만 읽히므로, 켜 둔 채로 바꾸면 반영되지 않는다." >&2
  exit 1
fi

echo "[1/3] server.properties — level-name=$WORLD_NAME · level-seed=$SEED"
cp "$PROPS" "$PROPS.bak"                      # 되돌릴 수 있게 (기존 world 로 돌아가려면 .bak 복원)
set_prop() {   # key value — 있으면 갈고, 없으면 붙인다
  if grep -qE "^$1=" "$PROPS"; then
    sed -i "s|^$1=.*|$1=$2|" "$PROPS"
  else
    printf '%s=%s\n' "$1" "$2" >> "$PROPS"
  fi
}
set_prop level-name "$WORLD_NAME"
set_prop level-seed "$SEED"
set_prop level-type "minecraft\\:normal"
set_prop spawn-protection 0                    # ★ 청하현이 원점(0,0)에 선다 — 스폰 보호가 조성을 막으면 안 된다
set_prop generate-structures true

echo "[2/3] 기존 월드 확인"
if [ -d "$RUN/$WORLD_NAME" ]; then
  echo "      ⚠ run/mvt/$WORLD_NAME 이 이미 있다 — 시드를 바꿔도 **기존 청크는 그대로다**."
  echo "        시드를 진짜로 갈려면 지워야 한다:  rm -rf '$RUN/$WORLD_NAME'"
  echo "        (조성만 다시 하려면 지울 필요 없다 — /혼천 세계조성 은 결정론이다)"
else
  echo "      새 월드가 생성된다: run/mvt/$WORLD_NAME (시드 $SEED)"
fi
echo "      기존 'world' 는 그대로 남는다 — run/mvt/world (되돌리려면 $PROPS.bak 복원)"

echo "[3/3] 다음 절차"
cat <<EOF

  1) 서버 기동          scripts/run_mvt_server.sh
  2) 관리자 권한        콘솔에서:  op <닉네임>
  3) 세계 조성          게임/RCON:  /혼천 세계조성
       → 청하현이 **원점 (0, ?, 0)** 에 선다 (친 자리가 아니라 등록된 자리에).
         폐사당·북쪽 산길 사냥터·흑수나루는 CheonghaBuilder 가 같이 짓는다.
  4) 지도 확인          /혼천 지도          — 등록 좌표·거리·여정 일수
                        /혼천 검수          — 마을 규칙 린트
                        /혼천 조감          — 조감도 PNG

  원거리 지역(화산 5일 · 사천당가 26일 · 경사 28일 …)은 **좌표만 등록**돼 있다.
  조성기가 생기는 날, 좌표는 이미 거기 있다 — 그것이 config/world_map.yml 의 목적이다.

EOF
