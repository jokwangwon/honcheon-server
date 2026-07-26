#!/usr/bin/env python3
"""세계 시계 검산(檢算) — **막 등록부와 봇 배선이 서로 거짓말하지 않는가** (B-110).

`config/world_clock.yml` 은 메인스토리의 막(幕) 등록부이고, 그것을 읽는 코드는
`server-bot` 의 `WorldClockEngine` 이다 (정본: docs/design/world_clock.md).
※ `tools/clock_audit.py` 는 **다른 눈**이다 — 그쪽은 마크와 봇이 같은 달력을 사는지를 잰다.
  이 눈은 그 달력 위에 얹힌 **이야기의 시계**를 잰다. 둘을 합치지 않는 이유: 눈은 작을수록 정직하다.

이 도구가 묻는 것은 넷이다:

  ① 등록부   막 사슬이 스스로 모순 없는가 — order 연속, gate 등록값, requires_beat 이
             직전 막의 실존 박을 가리키는가, do 유형이 등록된 다섯뿐인가.
  ② 이웃     등록부가 이웃 등록부 밖의 이름을 지어내지 않았는가 — 소문 망(rumor.yml),
             명분 대상(faction_politics.yml roster), 지역(WorldStore.PRIMARY_REGION)·눈금.
  ③ 배선     코드가 실제로 이 파일을 읽고 도는가 — advanceWorld 편입(tick), /막개전 등록·
             권한(MANAGE_SERVER), 등록부의 수치(tempo_clamp·herald_lead_days)와
             상태 키·원장 타입을 코드가 그대로 쓰는가.
  ④ 표식     파일이 제 처지에 대해 정직한가 — 읽는 코드가 있으면 unwired 표식이 없어야 하고,
             없으면 있어야 한다 (양방향).

config 를 고치지 않는다 — 재기만 한다.

사용법:
    python3 tools/world_clock_audit.py
    python3 tools/world_clock_audit.py --json    # 기계 판독용

종료 코드: 위반(❌) 1건 이상이면 1, 아니면 0.
자기 시험: tools/world_clock_audit_selftest.py — 일부러 어겨서 이 눈이 잡는지 본다.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BOT = ROOT / "server-bot" / "src" / "main" / "java" / "com" / "honcheon" / "bot"
CONFIG = ROOT / "config"

ENGINE = BOT / "WorldClockEngine.java"
LISTENER = BOT / "GameListener.java"
BOOT = BOT / "HoncheonBot.java"
WORLDSTORE = BOT / "WorldStore.java"
YML = CONFIG / "world_clock.yml"

OK, NO, WARN = "✅", "❌", "⚠️"

DO_KINDS = {"rumor", "chapter_open", "myeongbun", "region_delta", "world_event"}
GATES = {"auto", "human"}


class Report:
    def __init__(self) -> None:
        self.violations: list[str] = []
        self.warnings: list[str] = []
        self.lines: list[str] = []
        self.facts: dict = {}

    def say(self, text: str = "") -> None:
        self.lines.append(text)

    def bad(self, text: str) -> None:
        self.violations.append(text)
        self.say(f"  {NO} {text}")

    def warn(self, text: str) -> None:
        self.warnings.append(text)
        self.say(f"  {WARN} {text}")

    def good(self, text: str) -> None:
        self.say(f"  {OK} {text}")


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"{NO} 눈이 엉뚱한 곳을 보고 있다 — 파일이 없다: {path}")
    return path.read_text(encoding="utf-8")


def load_yaml(path: Path) -> dict:
    try:
        import yaml
    except ImportError:
        sys.exit("pyyaml 이 필요하다: pip install pyyaml")
    return yaml.safe_load(read(path)) or {}


def decomment(src: str) -> str:
    """주석만 지운 자바 소스 — 문자열 리터럴은 남긴다 (clock_audit.py 의 교훈 그대로:
    문자열까지 지운 소스에서 리터럴을 찾으면 멀쩡한 배선을 '없음'으로 신고하게 된다)."""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            while i < n and src[i] != "\n":
                out.append(" ")
                i += 1
        elif c == "/" and nxt == "*":
            while i < n and not (src[i] == "*" and i + 1 < n and src[i + 1] == "/"):
                out.append("\n" if src[i] == "\n" else " ")
                i += 1
            out.append("  ")
            i += 2
        elif c in ('"', "'"):
            quote = c
            out.append(c)
            i += 1
            while i < n:
                out.append(src[i])
                if src[i] == "\\" and i + 1 < n:
                    out.append(src[i + 1])
                    i += 2
                    continue
                if src[i] == quote:
                    i += 1
                    break
                i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def carve(src: str, signature: str) -> str | None:
    """메서드 본문을 도려낸다 — 부분 문자열은 증거가 아니다, **그 메서드 안**을 봐야 한다."""
    m = re.search(signature + r"[^{]*\{", src)
    if not m:
        return None
    depth, start = 1, m.end()
    for i in range(start, len(src)):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[start:i]
    return src[start:]


# ═══════════════ ① 등록부 — 막 사슬이 스스로 모순 없는가 ═══════════════

def audit_registry(rep: Report, cfg: dict) -> dict:
    rep.say("── ① 등록부 — 막 사슬의 자기 검산 (config/world_clock.yml)")
    rep.say()

    acts = cfg.get("acts") or {}
    if not acts:
        rep.bad("acts 절이 없거나 비었다 — 막 없는 세계 시계는 시계가 아니다")
        return {}

    clock = cfg.get("clock") or {}
    for key in ("tempo_clamp", "herald_lead_days"):
        v = clock.get(key)
        if not isinstance(v, int) or v <= 0:
            rep.bad(f"clock.{key} 가 없거나 양의 정수가 아니다: {v!r}")

    ordered = sorted(acts.items(), key=lambda kv: (kv[1] or {}).get("order", 99))
    orders = [(a or {}).get("order") for _, a in ordered]
    if orders != list(range(len(ordered))):
        rep.bad(f"막 order 가 0부터 연속이 아니다: {orders}")
    else:
        rep.good(f"막 {len(ordered)}개 — order 0~{len(ordered) - 1} 연속")

    beats_of = {aid: [b.get("key") for b in (a or {}).get("beats", []) or []]
                for aid, a in ordered}
    for i, (aid, act) in enumerate(ordered):
        entry = (act or {}).get("entry") or {}
        gate = entry.get("gate")
        if gate not in GATES:
            rep.bad(f"막 {aid} — 등록되지 않은 gate: {gate!r} (auto/human 뿐이다)")
        if gate == "human":
            approval = entry.get("approval") or {}
            if approval.get("command") != "/막개전":
                rep.bad(f"막 {aid} — human gate 인데 approval.command 가 /막개전 이 아니다: "
                        f"{approval.get('command')!r}")
            if approval.get("permission") != "MANAGE_SERVER":
                rep.bad(f"막 {aid} — approval.permission 이 MANAGE_SERVER 가 아니다")
        req = entry.get("requires_beat")
        if i == 0:
            if req:
                rep.warn(f"첫 막 {aid} 에 requires_beat 이 있다 — 아무도 그 박을 발화해 줄 수 없다")
        else:
            prev_id = ordered[i - 1][0]
            want = [f"{prev_id}.{k}" for k in beats_of[prev_id]]
            if req not in want:
                rep.bad(f"막 {aid} — requires_beat 이 직전 막({prev_id})의 실존 박이 아니다: {req!r}")
        for beat in (act or {}).get("beats", []) or []:
            key, at = beat.get("key"), beat.get("at")
            if not key or not isinstance(at, int) or at < 0:
                rep.bad(f"막 {aid} — 박의 key/at 이 성치 않다: key={key!r} at={at!r}")
            unknown = set((beat.get("do") or {}).keys()) - DO_KINDS
            if unknown:
                rep.bad(f"막 {aid} 박 {key} — 등록되지 않은 do 유형: {sorted(unknown)}")
    if not rep.violations:
        rep.good("gate·requires_beat·박 do 유형 — 전부 등록값 안이다")
    rep.facts["acts"] = [aid for aid, _ in ordered]
    return dict(ordered)


# ═══════════════ ② 이웃 등록부 — 이름을 지어내지 않았는가 ═══════════════

def audit_neighbors(rep: Report, acts: dict) -> None:
    rep.say()
    rep.say("── ② 이웃 등록부 — 망·세력·지역·눈금이 전부 등재된 것인가")
    rep.say()

    networks = set((load_yaml(CONFIG / "rumor.yml").get("networks") or {}).keys())
    roster = set((load_yaml(CONFIG / "faction_politics.yml").get("roster") or {}).keys())
    stats: set[str] = set()
    for deltas in (load_yaml(CONFIG / "region_state.yml").get("event_deltas") or {}).values():
        stats |= set((deltas or {}).keys())
    m = re.search(r'PRIMARY_REGION\s*=\s*"([^"]+)"', decomment(read(WORLDSTORE)))
    primary = m.group(1) if m else None
    interests: set[str] = set()
    for net in (load_yaml(CONFIG / "rumor.yml").get("networks") or {}).values():
        interests |= set((net or {}).get("interests") or [])

    n_rumor = n_mb = n_rd = 0
    for aid, act in acts.items():
        rumors = []
        for beat in (act or {}).get("beats", []) or []:
            do = beat.get("do") or {}
            if "rumor" in do:
                rumors.append((f"박 {beat.get('key')}", do["rumor"]))
            mb = do.get("myeongbun")
            if mb:
                n_mb += 1
                issue = str(mb.get("issue", ""))
                target = mb.get("target") or (issue.split(":")[-1] if ":" in issue else None)
                for fid in [target, *(mb.get("victims_add") or [])]:
                    if fid not in roster:
                        rep.bad(f"막 {aid} 박 {beat.get('key')} — 명분의 세력이 "
                                f"faction_politics.yml roster 에 없다: {fid!r}")
            rd = do.get("region_delta")
            if rd:
                n_rd += 1
                if primary and rd.get("region") != primary:
                    rep.bad(f"막 {aid} 박 {beat.get('key')} — 등록 지역이 아니다: "
                            f"{rd.get('region')!r} (PRIMARY_REGION={primary})")
                ghost = set(rd.keys()) - {"region"} - stats
                if ghost:
                    rep.bad(f"막 {aid} 박 {beat.get('key')} — region_state.yml 에 없는 눈금: "
                            f"{sorted(ghost)}")
        for h in (act or {}).get("heralds", []) or []:
            rumors.append(("전조", (h or {}).get("rumor") or {}))
        for where, rumor in rumors:
            n_rumor += 1
            net = rumor.get("망")
            if net not in networks:
                rep.bad(f"막 {aid} {where} — rumor.yml 에 없는 망: {net!r}")
            강도 = rumor.get("강도")
            if not isinstance(강도, int) or not 1 <= 강도 <= 5:
                rep.bad(f"막 {aid} {where} — 강도가 1~5 정수가 아니다: {강도!r}")
            if not rumor.get("문안키"):
                rep.bad(f"막 {aid} {where} — 문안키가 없다 (소문에 내용이 없다)")
            orphan = set(rumor.get("태그") or []) - interests
            if orphan:
                rep.warn(f"막 {aid} {where} — 어떤 망의 관심에도 없는 태그 {sorted(orphan)} "
                         f"(발원망 밖으로 건너가지 못한다)")
    if not any(v.startswith("막") for v in rep.violations):
        rep.good(f"소문 {n_rumor}건(망·강도·문안키) · 명분 {n_mb}건(세력) · 지역 델타 {n_rd}건 — "
                 f"전부 이웃 등록부 안이다")
    rep.facts.update({"rumors": n_rumor, "myeongbun": n_mb, "region_deltas": n_rd})


# ═══════════════ ③ 배선 — 코드가 실제로 이 파일을 읽고 도는가 ═══════════════

def audit_wiring(rep: Report, cfg: dict) -> None:
    rep.say()
    rep.say("── ③ 배선 — 등록부를 읽는 코드가 실제로 서 있는가")
    rep.say()

    if not ENGINE.is_file():
        rep.bad("WorldClockEngine.java 가 없다 — 등록부를 읽는 자가 없다 (세계에 막은 없다)")
        return
    engine = decomment(read(ENGINE))
    listener = decomment(read(LISTENER))
    boot = decomment(read(BOOT))

    checks = [
        ("엔진이 등록부 파일을 연다 (world_clock.yml)", '"world_clock.yml"' in engine),
        ("clamp 를 등록부에서 읽는다 (tempo_clamp — 코드에 20 을 박지 않았다)",
         '"tempo_clamp"' in engine),
        ("전조 시차를 등록부에서 읽는다 (herald_lead_days)", '"herald_lead_days"' in engine),
    ]
    # 상태 키·원장 타입 — 등록부 선언과 코드 사용이 겹치는가
    meta = cfg.get("meta") or {}
    declared_keys = [str(k).split(":")[0] for k in (meta.get("state_keys") or [])] \
        + [str(k).split(":")[0] for k in (meta.get("bookkeeping_keys") or [])]
    for key in declared_keys:
        checks.append((f"상태 키 '{key}' — 등록부 선언 = 코드 사용", f'"{key}' in engine))
    for etype in meta.get("event_types") or []:
        checks.append((f"원장 타입 '{etype}' — 등록부 선언 = 코드 append", f'"{etype}"' in engine))
    for label, ok in checks:
        (rep.good if ok else rep.bad)(label + ("" if ok else "  ← 끊겼다"))

    # advanceWorld 편입 — **그 메서드 안**에서 tick 을 불러야 한다 (부분 문자열은 증거가 아니다)
    body = carve(listener, r"Dawn\s+advanceWorld\s*\(\s*\)")
    if body is None:
        rep.bad("GameListener.advanceWorld 를 못 찾았다 — 눈이 엉뚱한 데를 보고 있을 수 있다")
    elif re.search(r"worldClock\s*\.\s*tick\s*\(", body):
        rep.good("자정 정산에 편입됐다 — advanceWorld 본문이 worldClock.tick(day) 을 부른다")
    else:
        rep.bad("advanceWorld 본문에 worldClock.tick( 호출이 없다 — 시계가 자정에 돌지 않는다")

    # /막개전 — 등록(HoncheonBot) · 분기(GameListener) · 권한(MANAGE_SERVER)
    if re.search(r'Commands\.slash\s*\(\s*"막개전"', boot):
        rep.good("HoncheonBot 이 /막개전 을 최상위 명령으로 등록한다 (B-020 25칸 상한 밖)")
        wired = all("actGate" in line for line in boot.splitlines() if ".addCommands(" in line)
        (rep.good if wired else rep.bad)(
            "등록된 명령 묶음(addCommands)에 막개전이 실려 있다" if wired
            else "Commands.slash(\"막개전\") 은 있는데 addCommands 에 안 실렸다 — 유령 명령이다")
    else:
        rep.bad("HoncheonBot 에 /막개전 등록이 없다 — human gate 를 열 손이 없다 (설계 §4)")

    if '"막개전".equals(event.getName())' in listener:
        rep.good("GameListener 가 /막개전 을 최상위에서 분기한다")
    else:
        rep.bad("GameListener 에 /막개전 분기가 없다 — 명령이 등록만 되고 대답하지 않는다")
    gate_body = carve(listener, r"void\s+approveActGate\s*\(")
    if gate_body is None:
        rep.bad("approveActGate 처리기가 없다")
    else:
        if "MANAGE_SERVER" in gate_body:
            rep.good("승인에 서버 관리 권한을 요구한다 (MANAGE_SERVER — settleDay 와 같은 문법)")
        else:
            rep.bad("approveActGate 가 권한을 검사하지 않는다 — 아무나 최종장을 연다")
        if re.search(r"worldClock\s*\.\s*approve\s*\(", gate_body):
            rep.good("승인이 엔진의 approve 로 흐른다")
        else:
            rep.bad("approveActGate 가 worldClock.approve( 를 부르지 않는다 — 빈 손잡이다")


# ═══════════════ ④ 표식 — 파일이 제 처지에 정직한가 ═══════════════

def audit_marker(rep: Report, cfg: dict) -> None:
    rep.say()
    rep.say("── ④ 표식 — unwired 표식과 실제 배선이 어긋나지 않는가")
    rep.say()

    engine_reads = ENGINE.is_file() and '"world_clock.yml"' in decomment(read(ENGINE))
    has_marker = "unwired" in cfg
    if engine_reads and has_marker:
        rep.bad("코드가 등록부를 읽는데 unwired 표식이 남아 있다 — **파일이 거짓말한다** (배선이 섰으면 걷어라)")
    elif not engine_reads and not has_marker:
        rep.bad("읽는 코드가 없는데 unwired 표식도 없다 — **파일이 거짓말한다** (미배선이면 표식을 세워라)")
    else:
        rep.good("표식과 배선이 일치한다 — "
                 + ("배선됨 · 표식 없음" if engine_reads else "미배선 · 표식 있음"))


# ═══════════════ ⑤ 엔딩 분기 — 넷 중 하나가 반드시 뽑히는가 ═══════════════

def audit_endings(rep: Report, cfg: dict, acts: dict) -> None:
    """엔딩 분기 문법의 눈 (2026-07-26 신설 — 사용자 확정 「분기 문법 늘리기」).

    acts 는 선형이라(order 연속 + requires_beat 사슬) 엔딩 넷을 담을 수 없다.
    그래서 별도 절이 됐고, 별도 절에는 별도 눈이 필요하다. 이 눈이 재는 것은 셋이다:
      ① 판정 시점이 **실존하는 마지막 막의 실존 박**인가
      ② **정확히 하나가 뽑히는가** — fallback(when 이 빈 것)이 있고, 그것이 마지막 우선순위인가
         ★이것이 이 눈의 핵심이다: fallback 이 없으면 '아무 엔딩도 없는 세계'가 가능해진다
      ③ do·소문·지역 눈금이 이웃 등록부 안인가 (막의 박과 같은 규칙)
    """
    rep.say()
    rep.say("── ⑤ 엔딩 분기 — 넷 중 하나가 반드시 뽑히는가")
    rep.say()

    end = cfg.get("endings") or {}
    if not end:
        rep.bad("endings 절이 없다 — 정본(story_summary v2)이 엔딩 4분기를 말하는데 등록부에 없다")
        return

    meta = end.get("meta") or {}
    world = end.get("world") or []
    personal = end.get("personal") or []

    # ① 판정 시점 — 마지막 막의 실존 박이어야 한다
    ordered = sorted(acts.items(), key=lambda kv: (kv[1] or {}).get("order", 99))
    last_id, last_act = (ordered[-1] if ordered else (None, {}))
    decided = str(meta.get("decided_at") or "")
    last_beats = [b.get("key") for b in (last_act or {}).get("beats", []) or []]
    want = {f"{last_id}.{k}" for k in last_beats}
    if decided not in want:
        rep.bad(f"endings.meta.decided_at 이 마지막 막({last_id})의 실존 박이 아니다: {decided!r} "
                f"— 후보: {sorted(want)}")
    else:
        rep.good(f"판정 시점 — 마지막 막의 종결박 {decided}")

    # ② 정확히 하나 — fallback 의 존재와 자리
    if not world:
        rep.bad("endings.world 가 비었다 — 세계 엔딩이 하나도 없다")
        return
    prios = [e.get("priority") for e in world]
    if sorted(p for p in prios if isinstance(p, int)) != list(range(1, len(world) + 1)):
        rep.bad(f"세계 엔딩 priority 가 1부터 연속이 아니다: {prios}")
    fallbacks = [e for e in world if not (e.get("when") or {})]
    if len(fallbacks) != 1:
        rep.bad(f"조건 없는 엔딩(fallback)이 정확히 하나가 아니다 ({len(fallbacks)}개) — "
                f"★없으면 '아무 엔딩도 없는 세계'가 가능해지고, 둘이면 어느 것인지 정해지지 않는다")
    else:
        fb = fallbacks[0]
        if fb.get("priority") != max(p for p in prios if isinstance(p, int)):
            rep.bad(f"fallback({fb.get('id')})이 마지막 우선순위가 아니다 — "
                    f"앞에 두면 조건부 엔딩이 영영 안 뽑힌다")
        else:
            rep.good(f"정확히 하나가 뽑힌다 — 세계 엔딩 {len(world)}개 · "
                     f"fallback = {fb.get('id')} (마지막 우선순위)")

    seen_ids = set()
    for e in world + personal:
        eid = e.get("id")
        if not eid or eid in seen_ids:
            rep.bad(f"엔딩 id 가 없거나 겹친다: {eid!r}")
        seen_ids.add(eid)
        if not e.get("name") or not e.get("outcome"):
            rep.bad(f"엔딩 {eid} — name/outcome 이 없다 (이름 없는 끝은 끝이 아니다)")

    # ★개인 엔딩은 세계 엔딩과 배타가 아니다 — scope 로 갈렸는지 본다
    for e in personal:
        if e.get("scope") != "personal":
            rep.bad(f"개인 엔딩 {e.get('id')} — scope 가 'personal' 이 아니다 "
                    f"(세계 엔딩과 배타로 오해된다)")
    if personal:
        rep.good(f"개인 엔딩 {len(personal)}개 — scope=personal (세계 엔딩과 동시 성립)")

    # ③ do 유형·이웃 등록부 (막의 박과 같은 규칙)
    networks = set((load_yaml(CONFIG / "rumor.yml").get("networks") or {}).keys())
    stats: set[str] = set()
    for deltas in (load_yaml(CONFIG / "region_state.yml").get("event_deltas") or {}).values():
        stats |= set((deltas or {}).keys())
    m = re.search(r'PRIMARY_REGION\s*=\s*"([^"]+)"', decomment(read(WORLDSTORE)))
    primary = m.group(1) if m else None

    n_do = 0
    for e in world + personal:
        do = e.get("do") or {}
        unknown = set(do.keys()) - DO_KINDS
        if unknown:
            rep.bad(f"엔딩 {e.get('id')} — 등록되지 않은 do 유형: {sorted(unknown)}")
        r = do.get("rumor")
        if r:
            n_do += 1
            if r.get("망") not in networks:
                rep.bad(f"엔딩 {e.get('id')} — rumor.yml 에 없는 망: {r.get('망')!r}")
            if not isinstance(r.get("강도"), int) or not 1 <= r["강도"] <= 5:
                rep.bad(f"엔딩 {e.get('id')} — 강도가 1~5 정수가 아니다: {r.get('강도')!r}")
            if not r.get("문안키"):
                rep.bad(f"엔딩 {e.get('id')} — 문안키가 없다")
        rd = do.get("region_delta")
        if rd:
            n_do += 1
            if primary and rd.get("region") != primary:
                rep.bad(f"엔딩 {e.get('id')} — 등록 지역이 아니다: {rd.get('region')!r}")
            ghost = set(rd.keys()) - {"region"} - stats
            if ghost:
                rep.bad(f"엔딩 {e.get('id')} — region_state.yml 에 없는 눈금: {sorted(ghost)}")

    # ④ 판정 입력 — 가리키는 박이 실존하는가
    for name, spec in (end.get("inputs") or {}).items():
        src = str((spec or {}).get("from") or "")
        hit = any(f"{aid}.{b.get('key')}" in src
                  for aid, a in acts.items() for b in (a or {}).get("beats", []) or [])
        if not hit and "faction_politics" not in src and "명분" not in src:
            rep.warn(f"엔딩 판정 입력 '{name}' 의 출처가 실존 박도 기존 축도 아니다: {src[:48]!r}")

    if meta.get("wiring_status") == "미배선":
        rep.good("★미배선을 스스로 밝혔다 (wiring_status: 미배선) — "
                 "등록부가 먼저 서고 코드가 따라오는 순서. 거짓말이 아니다")
    rep.facts["endings"] = {"world": len(world), "personal": len(personal), "do": n_do}


def main() -> int:
    ap = argparse.ArgumentParser(description="세계 시계 검산 — 막 등록부와 봇 배선의 대조 (B-110)")
    ap.add_argument("--json", action="store_true", help="기계 판독용 요약")
    args = ap.parse_args()

    rep = Report()
    rep.say("═" * 78)
    rep.say("  세계 시계 검산 — 막 등록부(world_clock.yml)와 봇 배선은 서로 정직한가 (B-110)")
    rep.say("═" * 78)
    rep.say()

    cfg = load_yaml(YML)
    acts = audit_registry(rep, cfg)
    if acts:
        audit_neighbors(rep, acts)
    audit_wiring(rep, cfg)
    audit_marker(rep, cfg)
    audit_endings(rep, cfg, acts)

    rep.say()
    rep.say("═" * 78)
    n_v, n_w = len(rep.violations), len(rep.warnings)
    if n_v == 0 and n_w == 0:
        rep.say("  총평: ✅ 위반 0건 · 경고 0건 — 등록부가 시계이고, 시계가 등록부다.")
        rep.say("        (믿기 전에 자기 시험을 돌려라: tools/world_clock_audit_selftest.py)")
    else:
        rep.say(f"  총평: 위반 {n_v}건 · 경고 {n_w}건")
        if n_v:
            rep.say("")
            rep.say(f"  ── 위반 ({NO})")
            for i, v in enumerate(rep.violations, 1):
                rep.say(f"    {i:2}. {v}")
        if n_w:
            rep.say("")
            rep.say(f"  ── 경고 ({WARN})")
            for i, w in enumerate(rep.warnings, 1):
                rep.say(f"    {i:2}. {w}")
    rep.say("═" * 78)

    if args.json:
        print(json.dumps({"violations": rep.violations, "warnings": rep.warnings,
                          "facts": rep.facts}, ensure_ascii=False, indent=2))
    else:
        print("\n".join(rep.lines))
    return 1 if n_v else 0


if __name__ == "__main__":
    sys.exit(main())
