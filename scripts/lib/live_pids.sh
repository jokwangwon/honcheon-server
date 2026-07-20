# 라이브 서버 프로세스를 찾는 **정본** — 명령줄이 아니라 **cwd** 로 찾는다.
#
# 왜 명령줄이 아닌가 (두 번 물린 자리다, 2026-07-20):
#   ① `pgrep -f paper.jar` → 테스트 서버(run/mvt-test)까지 잡혔다.
#   ② 그래서 `-Xms2G` 로 좁혔다 → 테스트 서버도 그 값으로 뜨자 **다시** 겹쳤다.
#   플래그는 언제든 바뀐다. 바뀌지 않는 것은 **어느 폴더에서 도는가** 뿐이다.
#
# 겹침이 부르는 해악은 조용하고 고약하다:
#   · restart_config_only.sh — 라이브를 **멈춘 뒤** 테스트가 잡혀 "안 멈춤"으로 읽고 exit 1.
#     재기동 단계에 못 가서 **라이브가 꺼진 채로 남았다.**
#   · fresh_start.sh — 대기 루프가 헛돌고 나서, 정말 멈췄는지 **확인 없이** playerdata 를 지운다.
#     라이브가 살아 있는 채로 지우면 그 데이터는 되돌아오지 않는다.
#
# 쓰는 법:
#   source "$ROOT/scripts/lib/live_pids.sh"
#   live_pids            # $RUN 폴더에서 도는 java PID 들 (없으면 빈 문자열)
#   live_pids /some/dir  # 폴더를 직접 준다
# ★ **모르는 상태를 「멈췄다」로 읽히게 하지 않는다** (fail closed).
#   부르는 쪽은 하나같이 `[ -z "$(live_pids)" ]` 로 「멈췄다」를 판정한다. 그러니 실패했을 때
#   빈 문자열을 돌려주면 그건 **「안전하다」는 거짓말**이 된다 — fresh_start.sh 는 그 말을 믿고
#   playerdata 를 지운다. `set -e` 도 못 잡는다: 명령치환의 종료코드는 [ ] 가 삼킨다.
#   그래서 모를 때는 **표를 하나 뱉는다.** 빈 문자열이 아니면 부르는 쪽은 「아직 돈다」로 읽고 멈춘다.
live_pids() {
  local want="${1:-${RUN:-}}"
  if [ -z "$want" ]; then
    echo "live_pids: 볼 폴더가 없다 (RUN 을 정하거나 인자로 주라)" >&2
    echo "UNKNOWN"; return 2
  fi
  # 심볼릭 링크를 편다 — /proc/<pid>/cwd 는 이미 편 경로를 준다. 안 맞추면 영영 0건이다.
  if ! want="$(cd "$want" 2>/dev/null && pwd -P)"; then
    echo "live_pids: 그런 폴더가 없다: ${1:-$RUN}" >&2
    echo "UNKNOWN"; return 2
  fi
  local out="" p
  for p in $(pgrep -x java 2>/dev/null); do
    [ "$(readlink /proc/"$p"/cwd 2>/dev/null)" = "$want" ] && out="$out $p"
  done
  echo $out
}
