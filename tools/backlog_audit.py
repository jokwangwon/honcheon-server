#!/usr/bin/env python3
"""청구서 감사 — **장부가 자기 자신에 대해 거짓말하는지** 재는 눈.

2026-07-14. 우리는 하루 종일 같은 병을 잡았다. 병의 이름은 **「보고와 세계의 어긋남」**이다.

  · 로그는 "허수아비 3"이라 했고 — 세계엔 0마리였다
  · 로그는 "발판 6"이라 했고 — 세계엔 0개였다
  · 감사는 "위반 0건"이라 했고 — 사용자는 보라 큐브를 보고 있었다
  · 봇은 "마크가 꺼졌다"고 했고 — 서버는 돌고 있었다

**청구서에 「닫힘」이라 적어 놓고 실제로는 안 닫힌 것** — 이것이 같은 병의 다음 얼굴이다.
그리고 이 얼굴이 가장 위험하다. 다른 병은 증상이 있지만, 이 병은 **증상이 없다**:
장부는 평온하게 "전부 닫힘"이라 말하고, 아무도 그 말을 재지 않고, 몇 주가 지난다.

그러므로 이 도구는 **코드를 재지 않는다. 장부를 잰다.**

  ① **문법**        항목이 필수 항목(상태·분류·단계·위치·검증·닫는 조건)을 갖췄는가 · ID 가 겹치지 않는가
  ② **어휘**        상태가 정해진 다섯(열림·진행·닫힘·보류·미확인) 중 하나인가
  ③ ★ **증거**      「닫힘」이 **검증 수단**을 대는가. *"고쳤다"* 는 증거가 아니다 —
                    감사 명령이거나, 눈으로 본 것(인게임)이거나, 파일이어야 한다
  ④ ★ **실재**      그 검증 수단이 **실제로 있는가** (그 도구·파일이 저장소에 있는가)
  ⑤ ★ **유령**      항목이 가리키는 `파일:줄` 이 실재하는가 — 코드가 옮겨 가면 청구서는 유령을 가리킨다
  ⑥ **의존**        의존하는 ID 가 실재하는가 · 앞 단계가 뒤 단계에 기대고 있지 않은가 (역행)
  ⑦ ★ **실행**      (`--run`) 「닫힘」 항목의 감사를 **실제로 돌린다.** 짖으면 그것은 닫힌 것이 아니다

⑦ 이 이 도구의 심장이다. ①~⑥ 은 장부의 **모양**을 보지만, ⑦ 은 장부의 **말이 참인지**를 본다.

★ 한 가지 함정을 알고 있다: 이 저장소의 감사 도구 일부는 **위반을 보고하면서도 종료 코드 0**을 낸다
   (bridge_audit·map_lint 가 그렇다 — 2026-07-14 실측). 그러므로 ⑦ 은 종료 코드만 믿지 않는다.
   출력에서 「위반 N건」을 읽어 N>0 이면 짖은 것으로 친다.

장부를 고치지 않는다 — 재기만 한다.

사용법:  python3 tools/backlog_audit.py                 (모양만 — 빠르다)
         python3 tools/backlog_audit.py --run           (닫힘 항목의 감사를 실제로 돌린다 — 느리다)
         python3 tools/backlog_audit.py <다른.md>       (다른 장부를 잰다 — 자기 시험이 쓴다)
눈을 시험하려면:  python3 tools/backlog_audit_selftest.py
종료 코드: 위반(❌) 1건 이상이면 1.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from game_audit import Report  # noqa: E402  — 출력 형식을 계승한다

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BACKLOG = os.path.join(ROOT, "docs/BACKLOG.md")

# ── 어휘 ─────────────────────────────────────────────────────────────────────
STATES = {"열림", "진행", "닫힘", "보류", "미확인"}
CLASSES = {"★세계", "결함", "빚", "미완", "결정"}
FIELDS_REQUIRED = ["상태", "분류", "단계", "위치", "닫는 조건", "검증"]

# 「닫힘」이 대는 증거로 인정하지 않는 말 — 이것만 있으면 증거가 아니다
EMPTY_WORDS = ["고쳤다", "고침", "완료", "됐다", "했다", "끝", "수정함", "처리함", "ok", "done"]

# 증거로 인정하는 모양
RE_CMD = re.compile(r"`([^`]*\b(?:python3?|bash|sh|gradle|\./gradlew)\b[^`]*)`")
RE_PATH = re.compile(r"`([\w./\-]+\.(?:py|java|yml|yaml|md|json|png|ogg))(?::(\d+))?`")
RE_DATE = re.compile(r"\b(20\d{2}-\d{2}-\d{2})\b")
RE_EYE = re.compile(r"인게임|눈으로 본|눈으로 봤|스크린샷|실측")

RE_ITEM = re.compile(r"^###\s+(B-\d{3})\s*·\s*(.+?)\s*$")
RE_FIELD = re.compile(r"^[-*]\s+\*\*(.+?)\*\*\s*:\s*(.*)$")
RE_PHASE = re.compile(r"^P(\d+)")
RE_LOC = re.compile(r"`([\w./\-]+):(\d+)`")
RE_VIOL = re.compile(r"위반\s*(\d+)\s*건")


def parse(path):
    """장부를 항목으로 쪼갠다. 형식이 깨진 것도 그대로 물고 온다 (그것이 곧 위반이므로)."""
    items = []
    cur = None
    with open(path, encoding="utf-8") as f:
        for lineno, raw in enumerate(f, 1):
            m = RE_ITEM.match(raw)
            if m:
                cur = {"id": m.group(1), "title": m.group(2), "line": lineno, "fields": {}}
                items.append(cur)
                continue
            if cur is None:
                continue
            if raw.startswith("### ") or raw.startswith("## "):
                cur = None
                continue
            fm = RE_FIELD.match(raw)
            if fm:
                cur["fields"][fm.group(1).strip()] = fm.group(2).strip()
    return items


def exists(rel):
    return os.path.exists(os.path.join(ROOT, rel))


def run_audit(cmd):
    """감사를 돌린다. (짖었는가, 한 줄 요약) 을 낸다.

    ★ 종료 코드만 믿지 않는다 — 위반을 보고하면서 0 을 내는 도구가 이 저장소에 있다."""
    try:
        p = subprocess.run(cmd, shell=True, cwd=ROOT, capture_output=True,
                           text=True, timeout=300)
    except subprocess.TimeoutExpired:
        return True, "시간 초과 (300초)"
    out = (p.stdout or "") + (p.stderr or "")
    barks = [int(m) for m in RE_VIOL.findall(out)]
    worst = max(barks) if barks else 0
    if p.returncode != 0:
        return True, f"종료 코드 {p.returncode}"
    if worst > 0:
        return True, f"위반 {worst}건 (종료 코드는 0 — 도구가 짖고도 통과시킨다)"
    return False, "위반 0건"


def main(argv):
    argv = list(argv[1:])
    do_run = "--run" in argv
    if do_run:
        argv.remove("--run")
    path = os.path.join(ROOT, argv[0]) if argv else BACKLOG

    rep = Report()
    rep.say()
    rep.say("═" * 74)
    rep.say(f"  청구서 감사 — {os.path.relpath(path, ROOT)}")
    rep.say("═" * 74)

    if not os.path.exists(path):
        rep.fail(f"장부가 없다: {os.path.relpath(path, ROOT)}")
        rep.dump()
        return 1

    items = parse(path)
    if not items:
        rep.fail("장부에 항목이 하나도 없다 — `### B-001 · 제목` 형식을 쓰는가")
        rep.dump()
        return 1

    # ── ① 문법 ───────────────────────────────────────────────────────────────
    rep.head("① 문법 — 항목이 일할 수 있는 모양인가")
    seen = {}
    dup = []
    for it in items:
        if it["id"] in seen:
            dup.append(f"{it['id']} (줄 {seen[it['id']]} · {it['line']})")
        seen[it["id"]] = it["line"]
    rep.verdict(not dup, "ID 가 겹치지 않는다" + (f" — 겹침: {', '.join(dup)}" if dup else f" ({len(items)}개)"))

    missing = []
    for it in items:
        gaps = [f for f in FIELDS_REQUIRED if f not in it["fields"]]
        if gaps:
            missing.append(f"{it['id']} → {', '.join(gaps)}")
    rep.verdict(not missing, "모든 항목이 필수 항목을 갖췄다"
                + ("" if not missing else " — 빠짐: " + " · ".join(missing[:6])
                   + (f" 외 {len(missing) - 6}건" if len(missing) > 6 else "")))

    # ── ② 어휘 ───────────────────────────────────────────────────────────────
    rep.head("② 어휘 — 상태·분류가 정해진 말인가")
    badstate = [f"{it['id']}={it['fields'].get('상태')!r}" for it in items
                if it["fields"].get("상태") not in STATES]
    rep.verdict(not badstate, f"상태가 다섯 중 하나다 ({'·'.join(sorted(STATES))})"
                + (f" — 어긋남: {', '.join(badstate[:5])}" if badstate else ""))
    badclass = [f"{it['id']}={it['fields'].get('분류')!r}" for it in items
                if it["fields"].get("분류") not in CLASSES]
    rep.verdict(not badclass, f"분류가 다섯 중 하나다 ({'·'.join(sorted(CLASSES))})"
                + (f" — 어긋남: {', '.join(badclass[:5])}" if badclass else ""))

    closed = [it for it in items if it["fields"].get("상태") == "닫힘"]

    # ── ③ 증거 ★ ─────────────────────────────────────────────────────────────
    rep.head("③ ★ 증거 — 「닫힘」이 무엇으로 닫혔는지 대는가")
    rep.say('     기준: *"고쳤다"* 는 증거가 아니다. **감사 명령**·**본 것**·**파일** 이어야 한다.')
    rep.say()
    if not closed:
        rep.ok("「닫힘」 항목이 없다 — 거짓말할 것도 없다")
    for it in closed:
        ev = it["fields"].get("닫힘", "")
        idt = f"{it['id']} {it['title'][:28]}"
        if not ev or ev in ("—", "-", ""):
            rep.fail(f"{idt} — 「닫힘」인데 **닫힘 근거가 비어 있다**")
            continue
        if not RE_DATE.search(ev):
            rep.fail(f"{idt} — 닫힘 근거에 **날짜가 없다** (언제 닫혔는가): “{ev[:44]}”")
            continue
        has_cmd = bool(RE_CMD.search(ev))
        has_path = bool(RE_PATH.search(ev))
        has_eye = bool(RE_EYE.search(ev))
        if not (has_cmd or has_path or has_eye):
            hollow = next((w for w in EMPTY_WORDS if w in ev), None)
            rep.fail(f"{idt} — **증거가 아니다** (감사 명령도·본 것도·파일도 없다)"
                     + (f": “…{hollow}…”" if hollow else f": “{ev[:44]}”"))
            continue
        kind = "감사 명령" if has_cmd else ("파일" if has_path else "★ 사람이 본 것 (기계가 못 잰다)")
        rep.ok(f"{idt} — 증거: {kind}")

    # ── ③-b 복제 ★ ──────────────────────────────────────────────────────────
    #   ★ 2026-07-14 실사고: 커밋 8a50548 의 일괄 치환이 `- **닫힘**: —` 45곳을
    #   **한 항목의 닫힘 문구로 전부 덮고**, 상태 20건을 열림→닫힘으로 뒤집었다.
    #   장부가 6시간 동안 가짜 닫힘 18건을 품었고 이 감사는 조용했다 — 증거의 꼴만
    #   봤지 **같은 증거가 여러 항목에 사는지** 안 봤다. 한 증거는 한 항목의 것이다.
    rep.head("③-b ★ 복제 — 같은 닫힘 근거가 여러 항목에 사는가 (일괄 치환의 지문)")
    by_ev = {}
    for it in closed:
        ev = (it["fields"].get("닫힘") or "").strip()
        if ev and ev not in ("—", "-"):
            by_ev.setdefault(ev, []).append(it["id"])
    cloned = {ev: ids for ev, ids in by_ev.items() if len(ids) > 1}
    if cloned:
        for ev, ids in cloned.items():
            rep.fail(f"닫힘 근거가 **복제됐다** — {' · '.join(ids)} ({len(ids)}항목이 같은 문장): "
                     f"“{ev[:44]}…” — 일괄 치환 사고를 의심하라 (한 증거는 한 항목의 것이다)")
    else:
        rep.ok(f"닫힘 근거 {len(by_ev)}건이 전부 서로 다르다 — 복제 없음")

    # ── ④ 실재 ★ ─────────────────────────────────────────────────────────────
    rep.head("④ ★ 실재 — 그 검증 수단이 저장소에 있는가")
    for it in items:
        v = it["fields"].get("검증", "")
        idt = f"{it['id']} {it['title'][:28]}"
        refs = [m.group(1) for m in RE_PATH.finditer(v)]
        cmds = RE_CMD.findall(v)
        for c in cmds:
            for tok in re.findall(r"[\w./\-]+\.py", c):
                refs.append(tok)
        if not refs and not cmds:
            if it["fields"].get("상태") == "닫힘":
                rep.fail(f"{idt} — 「닫힘」인데 **검증 수단이 없다** (무엇을 돌려 확인하는가)")
            elif "사람" not in v and "인게임" not in v and v:
                rep.warn(f"{idt} — 검증란이 기계가 못 재는 말이다: “{v[:40]}”")
            continue
        ghosts = [r for r in dict.fromkeys(refs) if not exists(r)]
        if ghosts:
            rep.fail(f"{idt} — 검증이 **없는 것**을 가리킨다: {', '.join(ghosts[:3])}")

    if not rep.violations:
        rep.ok("검증란이 가리키는 도구·파일이 전부 실재한다")

    # ── ⑤ 유령 ★ ─────────────────────────────────────────────────────────────
    rep.head("⑤ ★ 유령 — 「위치」가 실재하는 코드를 가리키는가")
    rep.say("     코드가 옮겨 가면 청구서는 유령을 가리킨다. 유령을 좇는 사람은 시간을 잃는다.")
    rep.say()
    ghost = []
    for it in items:
        loc = it["fields"].get("위치", "")
        for m in RE_LOC.finditer(loc):
            rel, ln = m.group(1), int(m.group(2))
            full = os.path.join(ROOT, rel)
            if not os.path.exists(full):
                ghost.append(f"{it['id']} → {rel} **파일이 없다**")
                continue
            try:
                with open(full, "rb") as f:
                    n = sum(1 for _ in f)
            except OSError:
                continue
            if ln > n:
                ghost.append(f"{it['id']} → {rel}:{ln} **줄이 없다** (그 파일은 {n}줄)")
    rep.verdict(not ghost, "「위치」가 전부 실재한다"
                + ("" if not ghost else " — 유령: " + " · ".join(ghost[:6])
                   + (f" 외 {len(ghost) - 6}건" if len(ghost) > 6 else "")))

    # ── ⑥ 의존 ───────────────────────────────────────────────────────────────
    rep.head("⑥ 의존 — 먼저 할 것이 정말 먼저인가")
    ids = {it["id"] for it in items}
    phase = {}
    for it in items:
        pm = RE_PHASE.match(it["fields"].get("단계", ""))
        if pm:
            phase[it["id"]] = int(pm.group(1))
    bad_dep, backward = [], []
    for it in items:
        dep = it["fields"].get("의존", "")
        for d in re.findall(r"B-\d{3}", dep):
            if d not in ids:
                bad_dep.append(f"{it['id']} → {d} (없는 항목)")
            elif it["id"] in phase and d in phase and phase[d] > phase[it["id"]]:
                backward.append(f"{it['id']}(P{phase[it['id']]}) → {d}(P{phase[d]})")
    rep.verdict(not bad_dep, "의존이 실재하는 항목을 가리킨다"
                + (f" — 유령 의존: {', '.join(bad_dep[:5])}" if bad_dep else ""))
    rep.verdict(not backward, "단계가 역행하지 않는다 (앞 단계가 뒤 단계에 기대지 않는다)"
                + (f" — 역행: {', '.join(backward[:5])}" if backward else ""))

    # ── ⑦ 실행 ★ ─────────────────────────────────────────────────────────────
    rep.head("⑦ ★ 실행 — 「닫힘」이 참인가 (감사를 실제로 돌린다)")
    if not do_run:
        rep.warn(f"돌리지 않았다 — 「닫힘」 {len(closed)}건의 말은 **아직 안 재 봤다**. "
                 "`python3 tools/backlog_audit.py --run` 이 잰다")
    elif not closed:
        rep.ok("「닫힘」 항목이 없다 — 돌릴 것이 없다")
    else:
        for it in closed:
            idt = f"{it['id']} {it['title'][:28]}"
            v = it["fields"].get("검증", "")
            cmds = [c for c in RE_CMD.findall(v) if "tools/" in c]
            if not cmds:
                rep.warn(f"{idt} — 기계가 돌릴 감사가 없다 (사람이 본 것으로 닫혔다면 그대로 둔다)")
                continue
            for c in cmds:
                barked, why = run_audit(c)
                if barked:
                    rep.fail(f"{idt} — 「닫힘」이라 적혔으나 **감사가 짖는다**: `{c}` → {why}")
                else:
                    rep.ok(f"{idt} — `{c}` → {why}")

    # ── 총평 ─────────────────────────────────────────────────────────────────
    rep.say()
    rep.say("─" * 74)
    n_state = {s: sum(1 for it in items if it["fields"].get("상태") == s) for s in STATES}
    rep.say("  항목 " + str(len(items)) + "건 — "
            + " · ".join(f"{s} {n_state[s]}" for s in ["열림", "진행", "닫힘", "보류", "미확인"]))
    if rep.violations:
        rep.say(f"  총평: ❌ 위반 {len(rep.violations)}건 · 경고 {len(rep.warnings)}건"
                " — **장부를 믿을 수 없다**")
    else:
        rep.say(f"  총평: ✅ 위반 0건 · 경고 {len(rep.warnings)}건"
                " — 장부가 자기 자신에 대해 거짓말하지 않는다")
    rep.say("─" * 74)
    rep.dump()
    return 1 if rep.violations else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
