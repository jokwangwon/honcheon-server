#!/usr/bin/env python3
"""시계·시트 검산(檢算) — **두 몸이 같은 하루를 사는가, 그리고 같은 사람을 보는가.**

이 저장소에는 몸이 둘이다. 마크(MVT)와 봇. 둘은 `days` 라는 **같은 단어**를 쓰는데
그 단어가 가리키는 시간이 달랐다:

    봇   1일 = 현실 24시간   (자정 스케줄러 — Db.advanceDay)
    마크 1일 = 현실 20분     (getFullTime() / 24000 — 바닐라 낮밤)

그리고 `training.yml attribute_progression.cost` 는 "능력치 +1 = 1년(360일)" 이라고 적혀 있다.
**같은 config 숫자가 72배 다른 두 시계 위에서 돌고 있었다** — 봇에서 1년 걸릴 능력치가
마크에서는 닷새 앉아 있으면 올랐다. 그리고 **어느 검산도 이 둘을 대조하지 않았다.**

화해: **달력은 하나다 — 봇이 굴리고 마크가 읽는다** (world_state.json 의 world_day).
마크의 20분 낮밤은 조명과 밤(隱)의 배경으로만 남는다. 그것으로 화후를 캐낼 수는 없다.

이 도구가 묻는 것은 다섯이다:

  ① 시계   마크의 **원장 시계**가 제 낮밤(getFullTime/24000)을 쓰는 데가 남아 있는가.
  ② 배선   세계일이 스냅숏 → WorldBridge.worldDay() → 원장으로 실제로 흐르는가.
  ③ 자     config 의 `days` 가 두 몸에서 **같은 시간**을 뜻하는가 (배율 = 1 이어야 한다).
  ④ 시트   world_bridge.yml 이 약속한 시트 칸을 봇이 **쓰고** 마크가 **읽는가** (양쪽 다).
  ⑤ 시트   `SkillEngine.State.realm` 에 경지가 **박혀 있지 않은가**, 원장이 **영속하는가**.

config 를 고치지 않는다 — 재기만 한다.

사용법:
    python3 tools/clock_audit.py
    python3 tools/clock_audit.py --json    # 기계 판독용

종료 코드: 위반(❌) 1건 이상이면 1, 아니면 0.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MVT = ROOT / "server-mvt" / "src" / "main" / "java" / "com" / "honcheon" / "mvt"
BOT = ROOT / "server-bot" / "src" / "main" / "java" / "com" / "honcheon" / "bot"
CONFIG = ROOT / "config"

OK, NO, WARN = "✅", "❌", "⚠️"

# 마크의 바닐라 하루 — 24000틱 × 50ms = 20분. 이 숫자는 마인크래프트가 정한다 (config 가 아니다)
MC_DAY_REAL_MINUTES = 20.0
REAL_DAY_MINUTES = 24 * 60.0


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


def load_yaml(name: str) -> dict:
    try:
        import yaml
    except ImportError:
        sys.exit("pyyaml 이 필요하다: pip install pyyaml")
    return yaml.safe_load(read(CONFIG / name)) or {}


def decomment(src: str) -> str:
    """주석만 지운 자바 소스 — **문자열 리터럴은 남긴다.**

    <주의> 정규식으로 주석을 지우면 문자열 안의 `//` (예: "https://…") 를 주석으로 오인한다.
    그래서 한 글자씩 걷는다. 줄 번호를 보존하려고 주석은 같은 길이의 공백으로 갈아 끼운다.

    ★ 이 눈의 첫 판이 바로 여기서 거짓말을 했다: 주석과 **문자열을 함께** 지워 놓고
      그 소스에서 문자열 리터럴(`"realm"`)을 찾았다. 당연히 하나도 못 찾았고,
      **멀쩡히 배선된 시트 칸 9개를 전부 "배선 없음"으로 신고했다.** 눈이 틀리면 위반이 발명된다.
    """
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


def code_only(src: str) -> str:
    """주석과 문자열을 **둘 다** 지운 소스 — 코드의 *구조*만 볼 때 쓴다 (시계 호출부 탐색).

    문자열까지 지우는 이유: 주석에 적힌 `getFullTime()/24000` 을 위반으로 세면 안 되고,
    문자열 안의 그것도 마찬가지다. 리터럴을 **찾을** 때는 이 함수를 쓰면 안 된다 (위의 사고).
    """
    src = decomment(src)
    return re.sub(r'"(\\.|[^"\\\n])*"', '""', src)


# ═══════════════ ① 시계 — 원장이 제 낮밤을 쓰는가 ═══════════════

# 원장의 시계를 움직이는 손잡이. 이것들의 **인자**가 마크의 낮밤이면 그 순간 시계가 갈라진다.
LEDGER_CLOCK_CALLS = ("rollDay", "isNewDay")
MC_CLOCK = re.compile(r"getFullTime\s*\(\s*\)\s*/\s*24000")


def audit_clock(rep: Report) -> None:
    rep.say("── ① 시계 — 원장은 누구의 하루를 사는가")
    rep.say()

    offenders: list[str] = []
    clock_sites: list[tuple[str, int, str]] = []

    for path in sorted(MVT.glob("*.java")):
        raw = read(path)
        code = code_only(raw)
        # 원장 시계 호출부를 줄 단위로 찾는다 (지운 소스에서 — 주석에 속지 않는다)
        for i, line in enumerate(code.splitlines(), 1):
            for call in LEDGER_CLOCK_CALLS:
                if f".{call}(" not in line:
                    continue
                if path.name == "PlayerLedger.java":
                    continue   # 정의부다 (호출부가 아니다)
                clock_sites.append((path.name, i, line.strip()))
                if MC_CLOCK.search(line):
                    offenders.append(f"{path.name}:{i} — 원장 시계에 마크의 낮밤: {line.strip()}")

        # 같은 메서드 안에서 MC 낮밤을 재서 원장 시계로 넘기는 우회도 잡는다:
        # `long day = ...getFullTime()/24000;` 뒤에 `.rollDay(day)` / `.isNewDay(day)`
        if path.name == "PlayerLedger.java":
            continue
        for m in re.finditer(r"(\w+)\s*=\s*[^;]*getFullTime\s*\(\s*\)\s*/\s*24000", code):
            var = m.group(1)
            tail = code[m.end():]
            for call in LEDGER_CLOCK_CALLS:
                if re.search(rf"\.{call}\s*\(\s*{re.escape(var)}\s*\)", tail):
                    offenders.append(
                        f"{path.name} — 마크의 낮밤을 '{var}' 에 담아 원장 시계로 넘긴다 (.{call}({var}))")

    if not clock_sites:
        rep.bad("원장의 시계를 굴리는 자가 **하나도 없다** — rollDay/isNewDay 호출부가 0. "
                "날이 안 바뀌면 수련도 일일 상한도 없다 (눈이 엉뚱한 폴더를 보고 있을 수도 있다)")
        return

    rep.say(f"     원장 시계 호출부 {len(clock_sites)}곳:")
    for name, line_no, text in clock_sites:
        mark = NO if MC_CLOCK.search(text) else OK
        rep.say(f"       {mark} {name}:{line_no}  {text[:78]}")
    rep.say()

    for bad in offenders:
        rep.bad(bad)
    if not offenders:
        rep.good("원장의 시계는 어디서도 마크의 낮밤(getFullTime/24000)을 쓰지 않는다")
    rep.facts["clock_sites"] = len(clock_sites)
    rep.facts["mc_clock_offenders"] = len(offenders)


# ═══════════════ ② 배선 — 세계일이 실제로 흐르는가 ═══════════════

def audit_wiring(rep: Report) -> None:
    rep.say()
    rep.say("── ② 배선 — 봇의 세계일이 마크의 원장까지 흐르는가")
    rep.say()

    bridge = decomment(read(MVT / "WorldBridge.java"))
    bot_bridge = decomment(read(BOT / "Bridge.java"))
    bot_db = decomment(read(BOT / "Db.java"))

    chain = [
        ("봇이 세계일을 굴린다 (Db.advanceDay — 자정 = 현실 하루)",
         "advanceDay" in bot_db),
        ("봇이 스냅숏에 world_day 를 싣는다 (Bridge.publish)",
         'snapshot.put("world_day"' in bot_bridge or "world_day" in bot_bridge),
        ("마크가 스냅숏에서 world_day 를 읽는다 (WorldBridge.readSnapshot)",
         "world_day" in bridge),
        ("마크가 그것을 하나의 손잡이로 낸다 (WorldBridge.worldDay())",
         re.search(r"static\s+long\s+worldDay\s*\(", bridge) is not None),
    ]
    for label, ok in chain:
        (rep.good if ok else rep.bad)(label + ("" if ok else "  ← 끊겼다"))

    # 원장 시계 호출부는 **전부** 그 손잡이에서 날을 받아야 한다
    fed, unfed = [], []
    for path in sorted(MVT.glob("*.java")):
        if path.name == "PlayerLedger.java":
            continue
        code = code_only(read(path))
        if not any(f".{c}(" in code for c in LEDGER_CLOCK_CALLS):
            continue
        (fed if "WorldBridge.worldDay()" in code or "plugin.worldDay()" in code
         else unfed).append(path.name)

    for name in fed:
        rep.good(f"{name} — 세계일을 다리에서 받는다")
    for name in unfed:
        rep.bad(f"{name} — 원장 시계를 굴리는데 **세계일을 어디서도 받지 않는다** "
                f"(WorldBridge.worldDay() 호출 없음)")

    # 봇이 꺼져 있을 때(세계일 0) 장부가 멈추는가 — 장부는 봇의 것이다
    guarded = sum(1 for p in MVT.glob("*.java")
                  if re.search(r"worldDay\s*\(\s*\)\s*;?[\s\S]{0,200}?(<=\s*0|<\s*1)",
                               code_only(read(p))))
    if guarded:
        rep.good(f"봇이 꺼져 있으면(세계일 0) 장부가 멈춘다 — {guarded}곳에서 막는다")
    else:
        rep.warn("세계일이 0(봇 꺼짐)일 때 원장을 막는 곳이 없다 — 봇 없이 화후가 쌓이면 그것은 위조다")
    rep.facts["fed"] = fed
    rep.facts["unfed"] = unfed


# ═══════════════ ③ 자 — config 의 days 가 두 몸에서 같은 시간인가 ═══════════════

def attr_cost_days() -> tuple[float, str]:
    """능력치 +1 에 드는 일치 — training.yml 이 정본 (Growth.java 와 같은 셈)."""
    training = load_yaml("training.yml")
    raw = str(training.get("attribute_progression", {}).get("cost", ""))
    # Growth.java: cost.contains("년") ? 360.0 : 30.0  — **같은 판독을 여기서 재현한다**
    return (360.0 if "년" in raw else 30.0), raw


def audit_ruler(rep: Report) -> None:
    rep.say()
    rep.say("── ③ 자 — 같은 숫자가 두 몸에서 같은 시간인가")
    rep.say()

    cost, raw = attr_cost_days()
    caps = load_yaml("player_creation.yml").get("attribute_cap_by_realm", {})
    ilryu = caps.get("일류", 6)

    # 원장 시계가 마크의 낮밤을 쓰면 하루가 20분이다. 세계일을 쓰면 하루가 24시간이다.
    mc_days_real = cost * MC_DAY_REAL_MINUTES / REAL_DAY_MINUTES     # 현실 며칠인가 (마크 시계)
    bot_days_real = cost                                             # 현실 며칠인가 (봇 시계)
    skew = bot_days_real / mc_days_real if mc_days_real else 0

    rep.say(f"     training.yml attribute_progression.cost = {raw!r} → {cost:.0f} 일치")
    rep.say(f"     능력치 +1 —  봇 시계: 현실 {bot_days_real:.0f}일   "
            f"마크 낮밤: 현실 {mc_days_real:.1f}일  (배율 {skew:.0f}×)")
    rep.say(f"     일류 캡({ilryu}) 한 축을 채우기 —  봇 시계: 현실 {bot_days_real * ilryu / 365:.1f}년")
    rep.say()

    if abs(skew - 72.0) > 1.0:
        rep.warn(f"마크 낮밤과 현실 하루의 배율이 72× 가 아니다 ({skew:.1f}×) — "
                 f"바닐라 낮밤(20분)이 바뀌었는가?")

    # ★ 지금 코드가 어느 시계를 쓰는가 — ① 이 이미 쟀다. 여기서는 그 결과로 실효 배율을 못 박는다
    offenders = rep.facts.get("mc_clock_offenders", 0)
    if offenders:
        rep.bad(f"두 몸의 `days` 가 {skew:.0f}배 다르다 — 마크의 원장이 제 낮밤으로 화후를 잰다. "
                f"봇에서 {bot_days_real:.0f}일 걸릴 능력치가 마크에서는 {mc_days_real:.1f}일이면 오른다")
    else:
        rep.good(f"두 몸의 `days` 가 같은 시간을 뜻한다 (실효 배율 1× — 달력은 봇 하나다). "
                 f"능력치 +1 = 현실 {bot_days_real:.0f}일, 양쪽 모두")
    rep.facts["attr_cost_days"] = cost
    rep.facts["skew_if_mc_clock"] = skew


# ═══════════════ ④ 시트 계약 — 약속한 칸을 봇이 쓰고 마크가 읽는가 ═══════════════

def audit_sheet(rep: Report) -> None:
    rep.say()
    rep.say("── ④ 시트 — 봇의 장부가 마크의 몸에 실리는가")
    rep.say()

    wb = load_yaml("world_bridge.yml")
    fields = (wb.get("feedback", {}).get("world_state", {}) or {}).get("sheet_fields", {})
    if not fields:
        rep.bad("world_bridge.yml 에 feedback.world_state.sheet_fields 가 없다 — "
                "시트가 등록되지 않았으면 마크에 캐릭터는 없다")
        return

    bot_game = decomment(read(BOT / "GameListener.java"))
    bot_bridge = decomment(read(BOT / "Bridge.java"))
    mvt_bridge = decomment(read(MVT / "WorldBridge.java"))
    mvt_ledger = code_only(read(MVT / "PlayerLedger.java"))

    if 'snapshot.put("sheet"' not in bot_bridge:
        rep.bad("봇이 스냅숏에 sheet 블록을 싣지 않는다 (Bridge.publish) — 다리는 섰는데 시트가 안 실린다")
    else:
        rep.good("봇이 스냅숏에 sheet 를 싣는다 (Bridge.publish)")
    if 'root.get("sheet")' not in mvt_bridge:
        rep.bad("마크가 스냅숏의 sheet 를 읽지 않는다 (WorldBridge.readSnapshot)")
    else:
        rep.good("마크가 스냅숏의 sheet 를 읽는다 (WorldBridge.readSnapshot)")

    rep.say()
    rep.say(f"     등록된 시트 칸 {len(fields)}개 — 봇이 쓰는가(write) · 마크가 읽는가(read):")
    for key in fields:
        written = f'"{key}"' in bot_game        # GameListener.mvtSheet 가 내는 칸
        parsed = f'"{key}"' in mvt_bridge       # WorldBridge.sheet(...) 가 받는 칸
        if written and parsed:
            rep.say(f"       {OK} {key:<12} 쓴다 · 읽는다")
        else:
            miss = []
            if not written:
                miss.append("봇이 안 쓴다")
            if not parsed:
                miss.append("마크가 안 읽는다")
            rep.bad(f"시트 칸 '{key}' — {' · '.join(miss)} (등록만 되고 배선이 없다)")

    rep.say()
    # 올라가는 방향 — cultivation_logged 의 payload 도 같은 계약이다
    payload = ((wb.get("events", {}) or {}).get("cultivation_logged", {}) or {}).get("payload", {})
    if not payload:
        rep.bad("world_bridge.yml 에 cultivation_logged 가 등록되지 않았다 — "
                "마크에서 쌓은 것이 장부로 올라갈 길이 없다")
        return
    rep.say(f"     cultivation_logged payload {len(payload)}칸 — 마크가 보내는가 · 봇이 받는가:")
    for key in payload:
        sent = f'"{key}"' in mvt_bridge
        recv = f'"{key}"' in bot_game or f'"{key}"' in bot_bridge
        if sent and recv:
            rep.say(f"       {OK} {key:<14} 보낸다 · 받는다")
        else:
            miss = []
            if not sent:
                miss.append("마크가 안 보낸다")
            if not recv:
                miss.append("봇이 안 받는다")
            rep.bad(f"payload '{key}' — {' · '.join(miss)}")

    # 정본의 방향 — 마크가 시트를 '덮어쓰기'로 받는가 (더하기면 이중 계상이 가능해진다)
    rep.say()
    if "applySheet" in mvt_ledger and "cultivationLogged" in mvt_bridge:
        rep.good("정본의 방향이 하나다 — 마크는 증분을 올리고(cultivationLogged) "
                 "시트는 절대값으로 받는다(applySheet). 두 원장은 갈라질 수 없다")
    else:
        rep.bad("정본의 방향이 배선되지 않았다 (applySheet / cultivationLogged 부재)")


# ═══════════════ ⑤ 몸 — 경지가 박혀 있지 않은가, 원장이 살아남는가 ═══════════════

REALMS = ("범인", "삼류", "이류", "일류", "절정", "초절정", "화경", "현경", "생사경")


def audit_body(rep: Report) -> None:
    rep.say()
    rep.say("── ⑤ 몸 — 경지가 코드에 박혀 있는가 · 원장이 재기동을 넘는가")
    rep.say()

    engine = read(MVT / "SkillEngine.java")
    # State 클래스의 realm 선언부. **문자열은 남긴 소스**로 본다 — 박힌 경지의 *이름*을 읽어야 하니까
    # (문자열을 지운 소스로 보면 `realm = "이류"` 가 `realm = ""` 로 보인다: 잡기는 잡는데 말을 못 한다).
    src = decomment(engine)
    m = re.search(r"class\s+State\s*\{(.*?)\n    \}", src, flags=re.S)
    body = m.group(1) if m else src
    decl = re.search(r"public\s+String\s+realm\s*(=\s*[^;]*)?;", body)
    if decl is None:
        rep.warn("SkillEngine.State.realm 선언을 못 찾았다 — 눈이 엉뚱한 데를 보고 있을 수 있다")
    elif decl.group(1):
        rep.bad(f"SkillEngine.State.realm 에 경지가 박혀 있다: `realm {decl.group(1).strip()}` — "
                f"**모든 플레이어가 영원히 그 경지다.** 경지는 봇의 시트에서 와야 한다")
    else:
        rep.good("SkillEngine.State.realm 에 경지가 박혀 있지 않다 (시트에서 온다)")

    # 경지 이름이 코드에 박힌 다른 자리 — 원장의 주무공 기본값도 같은 병이었다 ("육합검")
    if re.search(r"String\s+baseRealm\s*\(", code_only(engine)):
        rep.good("접합 전의 경지는 등록부의 첫 단에서 온다 (SkillEngine.baseRealm — cultivation.yml)")
    else:
        rep.bad("접합 전의 경지를 config 에서 받는 손잡이가 없다 (SkillEngine.baseRealm)")

    # ─ 영속 — 원장이 디스크에 굽히는가 ─
    #
    # ★ 여기서 이 눈이 두 번째로 거짓말했다. 판정이 `"loadLedgers" in plugin` 이었는데,
    #   메서드 이름을 `loadLedgersDISABLED` 로 바꿔 **적재를 죽여도** 그 문자열은 여전히 들어 있다.
    #   부분 문자열은 증거가 아니다 — **부르는 자리**를 봐야 한다. 그래서 `이름(` 까지 요구한다.
    plugin = code_only(read(MVT / "HoncheonMvt.java"))
    ledger = code_only(read(MVT / "PlayerLedger.java"))
    called = lambda name, src: re.search(rf"\b{name}\s*\(\s*\)", src) is not None
    saves = called("saveLedgers", plugin) and called("loadLedgers", plugin)
    serial = (re.search(r"\bvoid\s+save\s*\(", ledger) is not None
              and re.search(r"\bPlayerLedger\s+load\s*\(", ledger) is not None)
    if saves and serial:
        rep.good("원장이 디스크에 구워진다 — 재기동·로그아웃을 넘어 살아남는다 (ledgers.yml)")
    else:
        miss = []
        if not called("loadLedgers", plugin):
            miss.append("적재(loadLedgers) 호출이 없다")
        if not called("saveLedgers", plugin):
            miss.append("저장(saveLedgers) 호출이 없다")
        if not serial:
            miss.append("PlayerLedger 에 save/load 가 없다")
        rep.bad("원장이 디스크에 굽히지 않는다 — **재기동 = 능력치·내공·숙련·소지금 전멸** "
                f"({' · '.join(miss)})")
    if re.search(r"void\s+onDisable\s*\(\s*\)[\s\S]{0,300}?\bsaveLedgers\s*\(", plugin):
        rep.good("종료 때 원장을 먼저 굽는다 (onDisable)")
    else:
        rep.warn("종료 훅에서 원장을 굽지 않는다 — 정상 종료에도 그날치가 날아갈 수 있다")

    # ─ 죽은 손 — 시트를 심는 네 setter 를 **applySheet 가 실제로 부르는가** ─
    #
    # ★ 이 눈이 세 번째로 거짓말한 자리. 판정이 "MVT 어디에든 setAttr( 가 있는가" 였는데,
    #   HuntingGrounds 에 **이름만 같은 무관한 메서드**가 있다 (setAttr(LivingEntity, Attribute, double)).
    #   그래서 applySheet 에서 능력치 심기를 통째로 지워도 눈은 "호출자 있음"이라고 했다.
    #   이름은 증거가 아니다 — **시트를 심는 그 메서드 안**을 봐야 한다.
    setters = ("setAttr", "setNaegong", "setSimbeop", "setPrimaryArt")
    body_m = re.search(r"void\s+applySheet\s*\([^)]*\)\s*\{", ledger)
    if not body_m:
        rep.bad("PlayerLedger.applySheet 이 없다 — 봇의 시트를 몸에 심는 손이 없다 "
                "(setAttr·setNaegong·setSimbeop·setPrimaryArt 는 호출자 0인 채 기다린다)")
        return
    # 중괄호를 세어 메서드 본문을 정확히 도려낸다 (다음 메서드로 새지 않게)
    start, depth, end = body_m.end(), 1, len(ledger)
    for idx in range(start, len(ledger)):
        if ledger[idx] == "{":
            depth += 1
        elif ledger[idx] == "}":
            depth -= 1
            if depth == 0:
                end = idx
                break
    plant = ledger[start:end]
    dead = [s for s in setters
            if not re.search(rf"\b{s}\s*\(", plant) and f"::{s}" not in plant]
    if dead:
        rep.bad(f"applySheet 이 시트를 다 심지 않는다 — 안 심는 칸: {', '.join(dead)}. "
                f"메서드는 있는데 아무도 부르지 않으면 그 칸은 영원히 0 이다")
    else:
        rep.good("시트를 심는 네 손(setAttr·setNaegong·setSimbeop·setPrimaryArt)을 "
                 "applySheet 이 전부 부른다")

    callers = [p.name for p in MVT.glob("*.java")
               if p.name != "PlayerLedger.java" and "applySheet" in code_only(read(p))]
    if callers:
        rep.good(f"applySheet 을 부르는 자가 있다 ({', '.join(callers)}) — 시트가 실제로 몸에 실린다")
    else:
        rep.bad("applySheet 을 아무도 부르지 않는다 — 시트는 내려오는데 몸에 실리지 않는다")

    # 첫 접속의 목소리
    joins = [p.name for p in MVT.glob("*.java") if "PlayerJoinEvent" in code_only(read(p))]
    onboard = (CONFIG / "player_creation.yml").read_text(encoding="utf-8")
    if "mvt_onboarding" not in onboard:
        rep.bad("player_creation.yml 에 mvt_onboarding 이 없다 — 첫 접속이 할 말이 없다")
    elif not (MVT / "Onboarding.java").is_file():
        rep.bad("mvt_onboarding 을 읽는 코드가 없다 — config 의 유령 절이다 (initial_goal 이 그랬다)")
    else:
        rep.good(f"첫 접속이 말을 건다 (Onboarding — player_creation.yml mvt_onboarding). "
                 f"PlayerJoinEvent 처리기 {len(joins)}개")


def main() -> int:
    ap = argparse.ArgumentParser(description="시계·시트 검산 — 두 몸이 같은 하루를 사는가")
    ap.add_argument("--json", action="store_true", help="기계 판독용 요약")
    args = ap.parse_args()

    rep = Report()
    rep.say("═" * 78)
    rep.say("  시계·시트 검산 — 마크의 몸과 봇의 장부는 같은 하루를 사는가")
    rep.say("═" * 78)
    rep.say()

    audit_clock(rep)
    audit_wiring(rep)
    audit_ruler(rep)
    audit_sheet(rep)
    audit_body(rep)

    rep.say()
    rep.say("═" * 78)
    n_v, n_w = len(rep.violations), len(rep.warnings)
    if n_v == 0 and n_w == 0:
        rep.say("  총평: ✅ 위반 0건 · 경고 0건 — 달력은 하나이고, 장부도 하나다.")
        rep.say("        봇이 날을 굴리고 마크가 그 날을 산다. 시트는 봇에서 와서 몸에 실린다.")
    else:
        rep.say(f"  총평: 위반 {n_v}건 · 경고 {n_w}건")
        if n_v:
            rep.say("")
            rep.say(f"  ── 위반 ({NO}) — 두 몸이 다른 세계를 산다")
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
