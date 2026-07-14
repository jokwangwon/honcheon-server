#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
침묵의 눈 — **조성은 반드시 소리를 낸다.**

★ 겪은 일 (2026-07-14 09:46)
  관리자가 RCON 콘솔에서 `/혼천 지형조성 hwasan` 을 쳤다. "부지를 찾는다" 한 줄 뒤로
  **12분간 아무 말도 없었다.** 사람은 조성이 죽은 줄 알았다 — 죽지 않았다. 729초 뒤 멀쩡히 끝났다.

  진짜 병: 진행 보고와 **실패 보고**의 수신처가 `sender::sendMessage` 였다.
  ★★ **RCON 의 sender 는 명령이 반환되는 순간 죽는다** (소켓이 닫힌다). 그 뒤의 말은 전부 허공이다.
  조성이 정말 터졌어도 **아무도 몰랐을 것이다.**

무엇을 재는가 (두 축)
  ① 조성 경로의 말이 **Announce 를 거치는가** — 로그에 남지 않는 말은 RCON 에서 사라진다
  ② TickBudget.build 의 **진행(log)·실패(err) 콜백**이 sender 로 직행하지 않는가
     ← 이것이 진범이었다. 여기 하나만 뚫려도 12분의 침묵이 돌아온다

돌리는 법:  python3 tools/silence_audit.py
자기 시험:  python3 tools/silence_audit.py --selftest   (★ 일부러 어겨서 눈이 짖는지 본다)
"""
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / "server-mvt/src/main/java/com/honcheon/mvt/MvtCommand.java"

# 조성의 경로 — 이 메서드들 안에서는 말이 로그에 남아야 한다
FORGE_METHODS = ("region", "preloadThenBuild", "finishRegion")

RE_METHOD = re.compile(r"^\s{4}(?:private|public|static|final|\s)*[\w<>\[\], .]+\s+(\w+)\s*\(")
RE_RAW_SEND = re.compile(r"\bsender\.sendMessage\b|\bsender::sendMessage\b")


def methods_of(src: str) -> dict:
    """대충 나눈 메서드 블록 — 들여쓰기 4칸에서 시작해 다음 메서드 전까지"""
    lines = src.splitlines()
    out, cur, buf = {}, None, []
    for line in lines:
        m = RE_METHOD.match(line)
        if m and "=" not in line.split("(")[0]:
            if cur:
                out[cur] = buf
            cur, buf = m.group(1), []
        if cur:
            buf.append(line)
    if cur:
        out[cur] = buf
    return out


def audit(path: Path):
    """(위반, 통과) — 위반은 (축, 말) 목록"""
    src = path.read_text(encoding="utf-8")
    bad, ok = [], []

    # ── 축 ① 조성 경로에 날것의 sender.sendMessage 가 남아 있는가
    blocks = methods_of(src)
    for name in FORGE_METHODS:
        body = blocks.get(name)
        if body is None:
            bad.append(("①", f"조성 경로 `{name}` 를 못 찾았다 — 눈이 엉뚱한 데를 보고 있다"))
            continue
        raw = [(i, l.strip()) for i, l in enumerate(body) if RE_RAW_SEND.search(l)]
        if raw:
            for _, l in raw[:4]:
                bad.append(("①", f"{name} — 로그에 안 남는 말: {l[:72]}"))
        else:
            ok.append(f"① {name} — 말이 전부 Announce 를 거친다 (로그에 남는다)")

    # ── 축 ② TickBudget.build 의 진행·실패 콜백이 sender 로 직행하는가 (★ 진범)
    #    ★ 창(window)으로 대충 자르면 **옆 메서드를 잘못 문다** (실제로 census() 를 물었다).
    #      괄호를 세어 **그 호출문만** 정확히 떼어 낸다 — 눈이 엉뚱한 것을 잡으면 그것도 결함이다.
    calls = 0
    for m in re.finditer(r"TickBudget\.build\(", src):
        i = m.end() - 1
        depth, end = 0, len(src)
        for j in range(i, len(src)):
            if src[j] == "(":
                depth += 1
            elif src[j] == ")":
                depth -= 1
                if depth == 0:
                    end = j + 1
                    break
        call = src[i:end]
        calls += 1
        if RE_RAW_SEND.search(call):
            bad.append(("②", "★ TickBudget.build 의 콜백이 sender 로 직행한다 "
                             "— RCON 이면 진행도 실패도 **허공으로 간다**"))
    if calls == 0:
        bad.append(("②", "TickBudget.build 호출을 못 찾았다 — 눈이 엉뚱한 데를 보고 있다"))
    elif not any(a == "②" for a, _ in bad):
        ok.append(f"② TickBudget.build {calls}곳 — 진행·실패가 로그로 간다 (RCON 이 끊겨도 남는다)")

    # ── 축 ③ Announce 자체가 로그를 먼저 적는가 (창구가 새면 전부 샌다)
    ann = (ROOT / "server-mvt/src/main/java/com/honcheon/mvt/Announce.java")
    if not ann.is_file():
        bad.append(("③", "Announce 가 없다 — 말하는 창구가 아예 없다"))
    else:
        a = ann.read_text(encoding="utf-8")
        for fn in ("say", "warn", "fail"):
            body = re.search(rf"static void {fn}\(.*?\n    }}", a, re.S)
            if not body or "getLogger()" not in body.group(0):
                bad.append(("③", f"Announce.{fn} 이 로그에 안 적는다 — 사람이 나가면 말이 사라진다"))
        if not any(a2 == "③" for a2, _ in bad):
            ok.append("③ Announce.say/warn/fail 이 **먼저 로그에 적는다**")
    return bad, ok


def selftest():
    """★ 일부러 어긴다 — 진범을 되살려 놓고 눈이 짖는지 본다"""
    src = TARGET.read_text(encoding="utf-8")
    broken = src.replace("line -> Announce.progress(plugin, sender, line))));",
                         "sender::sendMessage)));")
    broken = broken.replace(
        'err -> Announce.fail(plugin, sender, "★ 조성 실패 — " + place.id() + ": " + err),',
        'err -> sender.sendMessage("조성 실패: " + err),')
    if broken == src:
        print("  ✖ 되살릴 구판을 못 찾았다 — 이 시험이 낡았다")
        return 1
    with tempfile.TemporaryDirectory() as d:
        p = Path(d) / "MvtCommand.java"
        p.write_text(broken, encoding="utf-8")
        bad, _ = audit(p)
    caught = any(axis == "②" for axis, _ in bad)
    print("  " + ("✔" if caught else "✖")
          + " ★ 일부러 어긴다 — 진행·실패를 sender 로 되돌리면 눈이 짖는다"
          + ("" if caught else "  ← 눈이 멀었다"))
    return 0 if caught else 1


def main():
    if "--selftest" in sys.argv:
        print("침묵의 눈 — 자기 시험 (눈을 만들었으면 눈을 시험하라)\n")
        sys.exit(selftest())

    print("침묵의 눈 — 조성은 반드시 소리를 낸다\n")
    bad, ok = audit(TARGET)
    for line in ok:
        print(f"  ✔ {line}")
    for axis, line in bad:
        print(f"  ✖ [{axis}] {line}")
    print()
    if bad:
        print(f"✖ 위반 {len(bad)}건 — **조성이 조용히 죽을 수 있다**")
        sys.exit(1)
    print("✔ 전부 통과 — 관문이 막으면 막았다고, 미리보기면 미리보기라고, 실패면 왜 실패했는지 말한다")


if __name__ == "__main__":
    main()
