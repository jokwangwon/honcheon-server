#!/usr/bin/env python3
"""안내판 감사의 **자기 시험** — 눈을 시험하는 눈.

"위반 0건"은 두 가지 뜻이다: **판이 성하다**, 또는 **눈이 멀었다.** 둘은 화면에서 똑같이 보인다.
그래서 **일부러 어긴다** — 죽은 버튼을 심고, 문장을 지우고, 배선을 끊고, 눈이 잡는지 본다.

★ 이 시험의 심장은 ①과 ③이다:
   ① **죽은 버튼** — 몸이 없는 사람에게 [몸을 끊는다] 를 내민다. 누르면 "이어진 몸이 없다" 만 나온다.
   ③ **침묵** — 못 누르는 이유의 문장을 지운다. 사람은 빈 판을 보고 혼자 추측하게 된다.

끝나면 전부 되돌린다 (등록부·소스는 손대지 않은 상태로 남는다).

사용법:  python3 tools/panel_audit_selftest.py
종료 코드: 눈이 하나라도 놓치면 1.
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CFG = os.path.join(ROOT, "config/discord_panel.yml")
GAME = os.path.join(ROOT, "server-bot/src/main/java/com/honcheon/bot/GameListener.java")
BOOT = os.path.join(ROOT, "server-bot/src/main/java/com/honcheon/bot/HoncheonBot.java")
AUDIT = os.path.join(ROOT, "tools/panel_audit.py")

# (이름, 파일, 원본조각, 바꿀조각, 눈이 뱉어야 하는 말의 조각)
MUTATIONS = [
    # ══ ★★ ⓪ 처음 온 사람이 **두 번** 눌러야 한다 (사용자가 명시적으로 금한 것) ══
    ("★ 판에서 시작 버튼을 뺀다 (처음 온 사람이 [내 자리] 를 거쳐 **두 번** 누르게 된다)", GAME,
     'Button.primary("np:start", rules.panelBoard("start_label", "강호에 들다")),\n'
     '                        Button.secondary("np:me"',
     'Button.secondary("np:me"',
     "**두 번** 눌러야 한다"),

    ("★ 이미 태어난 사람이 [강호에 들다] 를 눌렀을 때 **침묵한다**", GAME,
     'event.reply(rules.panelBoard("already", "이미 캐릭터가 있다 — [내 자리] 를 눌러라.")',
     'event.reply(("이미 캐릭터가 있다"',
     "침묵 금지"),

    ("판의 시작 버튼 이름을 등록부에서 지운다 (코드가 이름을 지어내게 된다)", CFG,
     '    start_label: "강호에 들다 — 처음이라면 여기"',
     "    start_label_낡음: 1",
     "`board.start_label` 가 **비어 있다**"),

    # ══ ★★ ① 죽은 버튼 — 이 시험의 이유 ══
    ("★ 몸이 없는 사람에게 [몸을 끊는다] 를 내민다", CFG,
     "        buttons: [link, sheet, reset]\n      서장_접합:",
     "        buttons: [link, sheet, reset, unlink]\n      서장_접합:",
     "죽은 버튼"),

    ("★ 이미 이어진 사람에게 [몸을 잇는다] 를 또 내민다 (relink 거부 — 그 자리에서 튕긴다)", CFG,
     "        buttons: [sheet, unlink, reset]\n      강호_미접합:",
     "        buttons: [sheet, unlink, reset, link]\n      강호_미접합:",
     "죽은 버튼"),

    ("★ 캐릭터가 이미 있는데 [강호에 들다] 를 내민다", CFG,
     "        buttons: [sheet, unlink, reset]\n\n    # ★ 옛 길",
     "        buttons: [sheet, unlink, reset, start]\n\n    # ★ 옛 길",
     "죽은 버튼"),

    # ══ ② 상태를 속인다 — 그 사람은 **빈 판**을 본다 ══
    ("한 상태를 등록부에서 지운다 (그 사람은 빈 판을 본다)", CFG,
     "      강호_미접합:",
     "      강호_미접합_낡은이름:",
     "등록부에 없다"),

    # ══ ★★ ③ 침묵 — 못 누르는 이유를 지운다 ══
    ("★ 못 누르는 이유의 문장을 지운다 (사람은 혼자 추측한다)", CFG,
     "    마크_꺼짐:",
     "    마크_꺼짐_낡음:",
     "등록되지 않은 잠금 문장"),

    # ══ ④ 배선 — 버튼은 뜨는데 아무도 안 받는다 ══
    ("버튼을 등록해 놓고 처리기를 안 만든다", CFG,
     "  buttons:\n    start:",
     "  buttons:\n    유령:\n      label: \"없는 문\"\n      style: primary\n    start:",
     "아무도 받지 않는다"),

    ("버튼의 이름(label)을 지운다 — 코드가 이름을 지어내게 된다", CFG,
     '    sheet:\n      label: "내 시트"',
     "    sheet:\n      style_only: true",
     "이름(label)이 없다"),

    ("모르는 style 을 적는다", CFG,
     '    reset:\n      label: "처음부터 다시"\n      style: danger',
     '    reset:\n      label: "처음부터 다시"\n      style: 붉은색',
     "모르는 값"),

    # ══ ⑤ 판을 세우는 손을 끊는다 ══
    ("버튼 라우팅을 끊는다 (판은 서지만 아무것도 안 눌린다)", GAME,
     'case "np" -> onPanel(event, id);',
     'case "np_dead" -> onPanel(event, id);',
     "라우팅되지 않는다"),

    ("명령을 만들어 놓고 안 싣는다 (명령이 안 뜬다)", BOOT,
     "addCommands(honcheon, gate, wipe, panel)",
     "addCommands(honcheon, gate, wipe)",
     "addCommands 에 안 실었다"),

    # ══ ★★ ⑦ 판이 거짓말한다 — 옛 길(B-080) ══
    ("★ 판이 **뒷문을 숨긴다** (`/혼천 사냥` 은 되는데 안 적는다)", CFG,
     "`/혼천 사냥` `/혼천 수련`",
     "`/혼천 수련`",
     "뒷문을 숨긴다"),

    ("★ 판이 **죽은 명령을 가리킨다** (옮겨서 지웠는데 계속 안내한다)", GAME,
     'case "탐방" -> visitShrine(event);',
     'case "탐방_옮겨감" -> visitShrine(event);',
     "죽은 명령을 가리킨다"),

    # ══ ★★ ⑧ 결을 섞는다 — 「소문·전장」은 **옮겨지는 것이 아니라 없어진다** (B-083) ══
    ("★ 판이 `소문` 을 «옮겨진다» 문단에 도로 넣는다 (형태의 소멸을 이관으로 속인다)", CFG,
     "`/혼천 탐방` `/혼천 출행` `/혼천 의방` `/혼천 구조`",
     "`/혼천 탐방` `/혼천 출행` `/혼천 의방` `/혼천 구조` `/혼천 소문`",
     "판이 **거짓말한다**"),

    ("★ 한 명령을 이관이자 NPC전환으로 적는다 (결이 둘이다)", CFG,
     "      NPC전환: [소문, 전장]",
     "      NPC전환: [소문, 전장, 의방]",
     "결은 하나여야 한다"),

    # ══ ⑥ 25칸 — 26번째를 넣으면 봇이 **기동조차 못 한다** ══
    ("서브커맨드를 26개로 만든다 (봇이 기동조차 못 한다)", BOOT,
     'new SubcommandData("도움말", "명령과 규칙 안내"));',
     'new SubcommandData("도움말", "명령과 규칙 안내"),\n'
     '                        new SubcommandData("스물여섯", "상한을 넘긴다"));',
     "상한은 25"),
]


def run_audit():
    r = subprocess.run([sys.executable, AUDIT], capture_output=True, text=True, cwd=ROOT)
    return r.returncode, r.stdout + r.stderr


def main():
    print("═══ 안내판 감사의 자기 시험 — 일부러 어겨서, 눈이 잡는지 본다 ═══\n")

    code, out = run_audit()
    if code != 0:
        print("❌ 성한 판에서 이미 짖는다 — 눈이 거짓말한다 (또는 판이 정말 깨졌다)\n")
        print(out)
        sys.exit(1)
    print("  ✅ 기준선 — 성한 판에서는 조용하다\n")

    missed = 0
    for name, path, old, new, want in MUTATIONS:
        with open(path, encoding="utf-8") as f:
            original = f.read()
        if old not in original:
            print(f"  ⚠️  심을 자리를 못 찾았다 (시험이 낡았다): {name}")
            print(f"      찾던 것: {old[:60]!r}")
            missed += 1
            continue
        try:
            with open(path, "w", encoding="utf-8") as f:
                f.write(original.replace(old, new, 1))
            code, out = run_audit()
            caught = code != 0 and want in out
            print(f"  {'✅' if caught else '❌'} {name}")
            if not caught:
                missed += 1
                print(f"      ☠ 눈이 **못 잡았다** (종료 코드 {code}, '{want}' 를 못 뱉었다)")
        finally:
            with open(path, "w", encoding="utf-8") as f:
                f.write(original)

    code, out = run_audit()
    if code != 0:
        print("\n❌ 되돌린 뒤에도 짖는다 — 시험이 파일을 망가뜨렸다")
        print(out)
        sys.exit(1)

    print(f"\n{'❌' if missed else '✅'} {len(MUTATIONS) - missed}/{len(MUTATIONS)} 잡았다"
          + (" — 눈이 멀지 않았다" if not missed else " — **눈에 구멍이 있다**"))
    sys.exit(1 if missed else 0)


if __name__ == "__main__":
    main()
