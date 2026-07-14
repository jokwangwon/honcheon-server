#!/usr/bin/env python3
"""안내판 검산(檢算) — **죽은 버튼이 없는가. 그리고 침묵하지 않는가.**

이 눈이 지키는 문장은 셋이다:

    ① **죽은 버튼 금지.** 판에 뜨는 버튼은 전부 **실재하는 흐름**을 가리킨다
       (np:<키> 마다 onPanel 에 처리기가 있고, 그 처리기가 부르는 함수가 실재한다).

    ② **상태를 속이지 않는다.** 코드가 만들 수 있는 상태 다섯(없음·서장/강호 × 접합/미접합)이
       전부 등록부에 있다. 그리고 각 상태의 버튼은 **그 상태에서 실제로 눌러지는 것**뿐이다
       — 몸이 없는데 [몸을 끊는다] 를 내밀면 그것이 죽은 버튼이다.

    ③ **★★ 침묵 금지.** 못 누르는 것은 **왜 못 누르는지 말한다.** 코드가 이름을 대는 lock 키가
       전부 등록부에 있어야 한다. 없으면 사람은 빈 판을 보고 혼자 추측한다.

그리고 등록제: **코드가 문장을 지어내지 않는다** — 버튼의 이름·판의 글·못 누르는 이유는 전부
`config/discord_panel.yml` 에 있다 (`/접합문` 의 gate.discord 와 같은 문법).

★ 이 눈 자체를 시험하는 눈: `tools/panel_audit_selftest.py` (일부러 어겨서, 잡는지 본다)

사용법:
    python3 tools/panel_audit.py
    python3 tools/panel_audit.py --config config --bot server-bot/src/main/java/com/honcheon/bot
종료 코드: 위반이 하나라도 있으면 1.
"""

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OK, NO, WARN = "✅", "❌", "⚠️"

# ─── 코드가 만들 수 있는 상태 — GameListener.myPlace 가 짓는 이름 그대로 ───
#   !has            → "없음"
#   (강호|서장) + (_접합|_미접합)
STATES = ["없음", "서장_미접합", "서장_접합", "강호_미접합", "강호_접합"]

# ─── 각 상태에서 **눌러지면 안 되는** 버튼 (= 죽은 버튼의 정의) ───
#   이 표가 이 눈의 심장이다. "왜 죽었는가"까지 적는다 — 짖을 때 사람에게 그대로 보여 준다.
FORBIDDEN = {
    "없음": {
        "sheet": "캐릭터가 없는데 시트를 내민다",
        "link": "캐릭터가 없는데 몸을 이으라 한다 (askLink 가 그 자리에서 거절한다)",
        "unlink": "캐릭터가 없는데 끊으라 한다",
        "reset": "지울 것이 없는데 되돌리라 한다",
    },
    "서장_미접합": {
        "start": "이미 캐릭터가 있는데 만들라 한다 (startCreation 이 거절한다)",
        "unlink": "이어진 몸이 없는데 끊으라 한다 (unlinkAccount 가 '이어진 몸이 없다')",
    },
    "서장_접합": {
        "start": "이미 캐릭터가 있는데 만들라 한다",
        "link": "이미 이어져 있는데 또 이으라 한다 (relink 거부 — 그 자리에서 튕긴다)",
    },
    "강호_미접합": {
        "start": "이미 캐릭터가 있는데 만들라 한다",
        "unlink": "이어진 몸이 없는데 끊으라 한다",
    },
    "강호_접합": {
        "start": "이미 캐릭터가 있는데 만들라 한다",
        "link": "이미 이어져 있는데 또 이으라 한다 (relink 거부)",
    },
}

# ─── 버튼 키 → 그 버튼이 실제로 부르는 함수 (실재해야 한다) ───
HANDLER_OF = {
    "me": "myPlace",
    "start": "startCreation",
    "sheet": "showSheet",
    "link": "openLinkModal",
    "unlink": "unlinkAccount",
    "reset": "resetPick",
    "rs": "resetConfirm",
}


def load_yaml(path: Path):
    try:
        import yaml
    except ImportError:
        sys.exit("pyyaml 이 필요하다: pip install pyyaml")
    if not path.exists():
        return None
    with path.open(encoding="utf-8") as f:
        return yaml.safe_load(f)


def strip_comments(text: str) -> str:
    """주석은 코드가 아니다 — **주석에 적힌 약속은 배선이 아니다.**

    link_audit 이 처음 스스로 속은 자리가 여기다 (주석의 `db.linkMvt(...)` 를 '잇는 손'으로 읽었다).
    """
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def audit(config_dir: Path, bot_dir: Path):
    fails, warns = [], []
    say = []

    cfg_path = config_dir / "discord_panel.yml"
    root = load_yaml(cfg_path)
    if not root or "panel" not in root:
        return [f"등록부가 없다 (또는 `panel:` 절이 없다): {cfg_path}"], [], say
    panel = root["panel"]

    listener = strip_comments((bot_dir / "GameListener.java").read_text(encoding="utf-8"))
    boot = strip_comments((bot_dir / "HoncheonBot.java").read_text(encoding="utf-8"))

    # ═══ ⓪ 판을 세우는 손이 있는가 — 명령 등록 · 라우팅 ═══
    if not re.search(r'Commands\.slash\(\s*"안내판"', boot):
        fails.append("`/안내판` 이 **등록되지 않았다** (HoncheonBot.Commands.slash)")
    # ★ 여기에 눈의 구멍이 있었다 (2026-07-14, 자기 시험이 잡았다): `addCommands` 는 **두 곳**이다
    #   (길드 스코프 · 글로벌). "어느 하나라도 panel 을 실었으면 통과"로 짜 놨더니, 한쪽에서 빼도
    #   눈이 조용했다 — 그러면 **글로벌로 뜬 봇에서는 명령이 없다.** 그래서 **전부** 본다.
    calls = re.findall(r"addCommands\(([^)]*)\)", boot)
    if not calls:
        fails.append("명령을 **아무 데도 안 싣는다** (addCommands 가 없다)")
    for i, args in enumerate(calls):
        if "panel" not in args:
            fails.append(f"`/안내판` 을 만들어 놓고 **addCommands 에 안 실었다** "
                         f"({i + 1}번째 등록 — `{args.strip()}`). 그 경로로 뜬 봇에는 명령이 없다")
    if not re.search(r'"안내판"\.equals\(event\.getName\(\)\)', listener):
        fails.append("`/안내판` 의 **라우팅이 없다** (GameListener.onSlashCommandInteraction)")
    if not re.search(r'case "np" ->', listener):
        fails.append("안내판의 버튼(`np:*`)이 **onButtonInteraction 에서 라우팅되지 않는다** — 죽은 판")
    say.append(f"{OK} 판을 세우는 손 — /안내판 등록 · 버튼 라우팅(np)")

    # ═══ ① 등록부 — 버튼·상태·잠금 문장이 있는가 ═══
    buttons = panel.get("buttons") or {}
    states = ((panel.get("me") or {}).get("states")) or {}
    locks = panel.get("locks") or {}

    for key, spec in buttons.items():
        if not (spec or {}).get("label"):
            fails.append(f"버튼 `{key}` 에 **이름(label)이 없다** — 코드가 이름을 지어내면 안 된다")
        style = (spec or {}).get("style", "secondary")
        if style not in ("primary", "secondary", "danger"):
            fails.append(f"버튼 `{key}` 의 style 이 모르는 값이다: `{style}` "
                         f"(primary·secondary·danger 뿐)")

    for key in ("title", "body", "button_label", "start_label", "link_label", "already"):
        if not (panel.get("board") or {}).get(key):
            fails.append(f"판의 `board.{key}` 가 **비어 있다** (코드가 문장을 지어내게 된다)")

    # ═══ ★★ ①-b **처음 온 사람은 몇 번 누르는가** — 답은 **한 번**이어야 한다 ═══
    #
    #   사용자: *"캐릭터가 없을 시 **버튼으로 시작 버튼**을 만들기 (**명령어 치는 거 제거**)"*
    #   옛 판은 [내 자리] 하나였고, 그것을 눌러야 그 **안에서** [강호에 들다] 가 보였다 — **두 번**이었다.
    #   이 절이 그 수를 **실제로 센다** (등록부의 약속이 아니라 **코드가 박는 버튼**을 본다).
    board_ids = set(re.findall(r'Button\.\w+\(\s*"np:(\w+)"', _method_body(listener, "postPanel")))
    if "start" in board_ids:
        clicks = 1
    elif "me" in board_ids:
        clicks = 2   # [내 자리] → 그 안의 [강호에 들다]
    else:
        clicks = 0   # 길이 없다
    if clicks == 1:
        say.append(f"{OK} ★ 처음 온 사람 — 판에서 **한 번** 누르면 캐릭터를 만든다 "
                   f"(`np:start` 가 판에 박혀 있다)")
    elif clicks == 2:
        fails.append("★ 처음 온 사람이 **두 번** 눌러야 한다 — 판에 `np:start` 가 없어서 "
                     "[내 자리] 를 거쳐야 [강호에 들다] 에 닿는다. **한 번**이어야 한다")
    else:
        fails.append("★ 판에 **아무 버튼도 없다** — 처음 온 사람은 캐릭터를 만들 길이 없다")
    if "me" not in board_ids:
        fails.append("판에 `np:me`([내 자리])가 없다 — 이미 태어난 사람이 갈 곳이 없다")
    # ★ B-117 (2026-07-14 실측): "접속이 명령어 타이핑이다." 잇기도 판의 버튼이어야 한다 —
    #   np:link → openLinkModal → lk:submit 모달 → askLink (`/혼천 접속` 과 같은 파이프).
    if "link" not in board_ids:
        fails.append("★ 판에 `np:link`([마크와 잇기])가 없다 — 접속이 도로 **명령어 타이핑**이 된다 "
                     "(B-117: 버튼 → 닉네임 모달 → askLink, 명령과 같은 파이프)")
    else:
        say.append(f"{OK} ★ 잇기 — 판에 `np:link` 가 박혀 있다 (버튼 → 모달 → askLink — "
                   f"명령과 같은 파이프, B-117)")

    # ★★ 침묵 금지 — [강호에 들다] 는 공용 버튼이라 **이미 태어난 사람도 누른다.**
    #    그때 startCreation 이 **등록부의 말로** 대답하는가 (조용히 넘기거나 코드가 문장을 지어내면 위반)
    if 'panelBoard("already"' not in _method_body(listener, "startCreation"):
        fails.append("이미 태어난 사람이 [강호에 들다] 를 눌렀을 때 **등록부의 말로 대답하지 않는다** "
                     "(`startCreation` 이 `panelBoard(\"already\")` 를 안 부른다) — 침묵 금지·등록제 위반")

    # ═══ ② 상태 — 코드가 만드는 다섯이 전부 있는가. 그리고 말이 있는가 ═══
    for state in STATES:
        if state not in states:
            fails.append(f"상태 `{state}` 가 **등록부에 없다** — 그 사람은 **빈 판**을 본다 "
                         f"(코드는 이 상태를 만든다: GameListener.myPlace)")
            continue
        if not (states[state] or {}).get("say"):
            fails.append(f"상태 `{state}` 에 **말이 없다**(say) — 침묵 금지")
        for b in (states[state] or {}).get("buttons") or []:
            if b not in buttons:
                fails.append(f"상태 `{state}` 가 **등록되지 않은 버튼**을 부른다: `{b}`")
    for state in states:
        if state not in STATES:
            warns.append(f"등록부에 **코드가 만들지 않는 상태**가 있다: `{state}` "
                         f"(아무도 못 본다 — 낡은 칸인가?)")
    say.append(f"{OK} 상태 {len(STATES)}종 — 전부 등록돼 있고 전부 말을 한다")

    # ═══ ③ ★★ 죽은 버튼 — 그 상태에서 **실제로 눌리는가** ═══
    dead = 0
    for state, forbidden in FORBIDDEN.items():
        listed = (states.get(state) or {}).get("buttons") or []
        for b in listed:
            if b in forbidden:
                dead += 1
                fails.append(f"☠ **죽은 버튼** — `{state}` 에 `{b}` 가 있다: {forbidden[b]}")
    if not dead:
        say.append(f"{OK} 죽은 버튼 0 — 뜨는 버튼은 전부 그 상태에서 **실제로** 눌린다")

    # ═══ ④ 배선 — np:<키> 마다 처리기가 있고, 그 처리기가 실재하는가 ═══
    routed = set(re.findall(r'case "(\w+)" ->', _method_body(listener, "onPanel")))
    need = set(buttons) | {"me", "rs"}
    for key in sorted(need):
        if key not in routed:
            fails.append(f"버튼 `np:{key}` 를 **아무도 받지 않는다** (onPanel 에 case 가 없다) — 죽은 버튼")
            continue
        fn = HANDLER_OF.get(key)
        if fn and not re.search(r"\b(?:void|Optional|String)\s+" + re.escape(fn) + r"\s*\(", listener):
            fails.append(f"`np:{key}` 가 부르는 **함수가 없다**: {fn}()")
    for key in sorted(routed - need - {"default"}):
        warns.append(f"onPanel 이 **등록부에 없는 버튼**을 받는다: `{key}` (아무도 못 누른다)")
    say.append(f"{OK} 배선 — np:{{{', '.join(sorted(need))}}} 전부 처리기가 있다")

    # ═══ ⑤ ★★ 침묵 금지 — 코드가 대는 lock 키가 전부 등록부에 있는가 ═══
    asked = set(re.findall(r'panelLock\(\s*"([^"]+)"', listener))
    for key in sorted(asked):
        if key not in locks:
            fails.append(f"코드가 **등록되지 않은 잠금 문장**을 부른다: `{key}` — "
                         f"못 누르는 이유를 **코드가 지어내게 된다** (침묵 금지의 반대말)")
    for key in sorted(set(locks) - asked):
        warns.append(f"등록부의 잠금 `{key}` 를 **아무도 안 부른다** (죽은 문장)")
    if asked and not (set(asked) - set(locks)):
        say.append(f"{OK} 침묵 금지 — 못 누르는 이유 {len(asked)}종이 전부 등록부에 있다")

    # ═══ ★★ ⑥ 옛 길 — 판이 **거짓말하지 않는가** (B-080) ═══
    #
    #   안내판은 출도한 사람에게 *"옛 길 — 아직 디스코드에서도 된다 (곧 강호로 옮겨진다)"* 라고 말하고
    #   그 명령들을 나열한다. 그 목록이 **실제로 남아 있는 것과 다르면 판이 거짓말한다** —
    #   ① 옮겨서 지운 명령을 계속 안내하면 → **죽은 명령을 가리킨다**
    #   ② 남아 있는 명령을 안 적으면 → **뒷문이 있는데 없다고 말한다** (사용자가 지키라 한 뒷문이다)
    #   기계가 이것을 재지 않으면, 누가 B-080 을 반쯤 하다 말았을 때 **아무도 모른다.**
    me = panel.get("me") or {}
    kinds = me.get("legacy_kinds") or {}
    moving = list(kinds.get("이관") or [])       # 몸의 일 — 마크로 **옮겨진다**
    becoming = list(kinds.get("NPC전환") or [])  # ★ 형태의 소멸 — 백엔드 + NPC 의 입/손
    legacy_all = moving + becoming
    if not legacy_all:
        fails.append("`panel.me.legacy_kinds` 가 **비었다** — 옛 길의 결을 아무도 모른다 "
                     "(이관인가, NPC 전환인가)")
    for c in sorted(set(moving) & set(becoming)):
        fails.append(f"`{c}` 가 **이관이면서 NPC전환**이다 — 결은 하나여야 한다")

    slash = _method_body(listener, "onSlashCommandInteraction")
    alive = [c for c in legacy_all if re.search(r'case "' + c + r'"', slash)]
    note = me.get("legacy_note") or ""
    claimed = [c for c in legacy_all if f"/혼천 {c}" in note]

    # ═══ ★★ 결이 섞이지 않았는가 — 「소문·전장」은 **옮겨지는 것이 아니라 없어진다** ═══
    #
    #   2026-07-14 사용자: *"소문과 전장은 이제 **백엔드로 가서 내부적으로 처리**가 되고,
    #   **NPC를 통해 표현**이 되어야 합니다"* — 즉 「명령」이라는 **형태 자체**가 없어진다.
    #   판이 그것을 "곧 강호로 옮겨진다" 라고 말하면 **거짓말**이다. legacy_note 는 사람이 읽는 글이라
    #   문단이 섞이기 쉽다 — 그래서 **문단 단위로** 잰다 (빈 줄이 문단을 가른다).
    paras = [p for p in re.split(r"\n\s*\n", note) if p.strip()]
    for c in becoming:
        for p in paras:
            if f"/혼천 {c}" in p and "옮겨진다" in p:
                fails.append(f"판이 **거짓말한다**: `/혼천 {c}` 를 «옮겨진다» 문단에 넣었다. "
                             f"소문·전장은 **옮겨지는 것이 아니라 「명령」이라는 형태가 없어진다** "
                             f"(백엔드에 남고 NPC 의 입·손으로 만난다 — B-083)")
    for c in moving:
        for p in paras:
            if f"/혼천 {c}" in p and "옮겨진다" not in p:
                warns.append(f"`/혼천 {c}` 가 «옮겨진다» 문단 밖에 있다 — 결이 섞였는가?")
    if becoming and not fails:
        say.append(f"{OK} 결 — 이관 {len(moving)}종 · **NPC 전환 {len(becoming)}종**"
                   f"({'·'.join(becoming)})이 섞이지 않았다")
    if alive and not note:
        fails.append(f"옛 길이 **{len(alive)}개 살아 있는데** 판은 그것을 **말하지 않는다** "
                     f"(`panel.me.legacy_note` 가 비었다) — 뒷문이 있는데 없다고 말한다")
    for c in sorted(set(alive) - set(claimed)):
        fails.append(f"판이 **뒷문을 숨긴다**: `/혼천 {c}` 는 아직 되는데 `legacy_note` 에 없다")
    for c in sorted(set(claimed) - set(alive)):
        fails.append(f"판이 **죽은 명령을 가리킨다**: `legacy_note` 의 `/혼천 {c}` 는 "
                     f"이제 없다 (옮겨졌다 — 판을 고쳐라)")
    if alive:
        say.append(f"{OK} 옛 길 {len(alive)}종 — 판이 **숨기지도 지어내지도 않는다** "
                   f"(B-080: 이것이 0 이 되면 닫힌다)")
    else:
        say.append(f"{OK} 옛 길 0 — 강호의 일이 전부 마크로 갔다 (B-080 닫힘)")

    # ═══ ⑦ 25칸 — 안내판은 /혼천 의 칸을 **먹지 않았는가** (B-020) ═══
    subs = len(re.findall(r"new SubcommandData\(", boot))
    if subs > 25:
        fails.append(f"/혼천 서브커맨드가 **{subs}개** — 디스코드 상한은 25다. **봇이 기동조차 못 한다**")
    else:
        say.append(f"{OK} /혼천 서브커맨드 {subs}/25 — 안내판은 **최상위 명령**이라 칸을 먹지 않았다")

    return fails, warns, say


def _method_body(text: str, name: str) -> str:
    """메서드 하나의 몸통 — 이름으로 찾아 중괄호를 세어 닫는다 (없으면 빈 문자열)."""
    m = re.search(r"\bvoid\s+" + re.escape(name) + r"\s*\(", text)
    if not m:
        return ""
    i = text.find("{", m.end())
    if i < 0:
        return ""
    depth, j = 0, i
    while j < len(text):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[i:j + 1]
        j += 1
    return text[i:]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default=str(ROOT / "config"))
    ap.add_argument("--bot", default=str(ROOT / "server-bot/src/main/java/com/honcheon/bot"))
    args = ap.parse_args()

    print("═══ 안내판 검산 — 죽은 버튼이 없는가 · 침묵하지 않는가 ═══\n")
    fails, warns, say = audit(Path(args.config), Path(args.bot))
    for line in say:
        print(" ", line)
    if warns:
        print()
        for w in warns:
            print(" ", WARN, w)
    if fails:
        print()
        for f in fails:
            print(" ", NO, f)
        print(f"\n{NO} 위반 {len(fails)}건")
        sys.exit(1)
    print(f"\n{OK} 위반 0건 — 판의 버튼은 전부 살아 있고, 못 누르는 것은 전부 이유를 말한다")


if __name__ == "__main__":
    main()
