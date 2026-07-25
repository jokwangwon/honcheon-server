#!/usr/bin/env python3
"""입도진 감사 — 대기실의 눈.

대기실은 **첫인상**이다. 여기가 거짓말하면 그 사람은 세계 전체를 의심한다.
그래서 이 눈은 세 가지만, 대신 **집요하게** 묻는다:

  ① **나갈 수 있는가**      문이 있는가. 무엇이 그 문을 여는가. 그 문이 코드에 실제로 배선돼 있는가.
  ② **갇히는 경우가 없는가** 봇이 꺼졌을 때 · 접합이 영영 안 될 때 · 월드가 안 열릴 때 · 목적지가 없을 때.
                            ★ 대기실은 초대다. 한 갈래라도 감옥이면 그것은 사고다.
  ③ **화면이 세계에 대해 거짓말하지 않는가**
                            대기실이 가르치는 조작이 **실제 엔진의 조작과 같은가.**
                            이 프로젝트는 `/혼천 협공` 이 "캡 +3"이라 찍는데 config 는 2였던 적이 있다.
                            그 병이 대기실에서 재발하면, 사람은 **틀린 조작을 몸에 익히고** 강호로 나간다.

  ④ (덤) **세계에 남는 것이 없는가** — TextDisplay 는 상주 엔티티다. 재조성 때 글이 겹치거나,
                            서버가 내려갈 때 걷히지 않으면 "보이지 않는 호랑이"가 또 남는다.

★ 이 눈은 **손으로 답을 써 넣지 않는다.** 기대값을 이 파일에 적지 않고, 언제나 **다른 등록부에서 읽어 대조한다**:
    · 몸짓 술어      ← config/combat.yml   defender_stance_mc.gestures
    · 경공 발동      ← config/gyeonggong.yml  activate
    · 명령의 존재    ← server-mvt/.../MvtCommand.java 의 `case "..."` 목록
    · 격의 가부      ← config/internal_energy.yml (경지 게이트) — 범인이 격을 두를 수 있는가
  대기실 config 가 이 넷 중 무엇과라도 어긋나면 위반이다.

config 를 고치지 않는다 — 재기만 한다.

사용법:
    python3 tools/antechamber_audit.py
    python3 tools/antechamber_audit.py --verbose

종료 코드: 위반(❌) 1건 이상이면 1, 아니면 0.
"""

from __future__ import annotations

import argparse
import os
import re
import sys

try:
    import yaml
except ImportError:
    print("PyYAML 이 필요하다: pip install pyyaml", file=sys.stderr)
    sys.exit(2)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CFG = os.path.join(ROOT, "config")
SRC = os.path.join(ROOT, "server-mvt", "src", "main", "java", "com", "honcheon", "mvt")

FAIL = "❌"
WARN = "⚠️"
OK = "✅"


class Report:
    def __init__(self, verbose: bool) -> None:
        self.violations: list[str] = []
        self.warnings: list[str] = []
        self.lines: list[str] = []
        self.verbose = verbose

    def say(self, text: str = "") -> None:
        self.lines.append(text)

    def bad(self, text: str) -> None:
        self.violations.append(text)
        self.say(f"    {FAIL} {text}")

    def warn(self, text: str) -> None:
        self.warnings.append(text)
        self.say(f"    {WARN} {text}")

    def good(self, text: str) -> None:
        if self.verbose:
            self.say(f"    {OK} {text}")

    def dump(self) -> None:
        print("\n".join(self.lines))


def load_yaml(name: str) -> dict:
    path = os.path.join(CFG, name)
    if not os.path.isfile(path):
        return {}
    with open(path, encoding="utf-8") as fh:
        return yaml.safe_load(fh) or {}


def source(name: str) -> str:
    path = os.path.join(SRC, name)
    if not os.path.isfile(path):
        return ""
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def body_of(java: str, signature_re: str) -> str | None:
    """메서드 **속**을 꺼낸다 (중괄호 짝맞춤).

    ★ 이 눈이 처음에 두 번 거짓말했다: `getSpawnLocation()` 이 파일 어딘가에 있다고 통과시켰고
      (정작 destination() 은 null 을 돌려주고 있었다), `clearPanels+spawnPanels` 쌍이 파일 어딘가에
      있다고 통과시켰다 (정작 build() 는 걷지 않고 세우고 있었다).
      **이름을 보고 속을 안 본 것**이다. 그래서 이 함수가 생겼다.
    """
    m = re.search(signature_re + r"\s*\{", java)
    if not m:
        return None
    i = java.index("{", m.start())
    depth = 0
    for j in range(i, len(java)):
        if java[j] == "{":
            depth += 1
        elif java[j] == "}":
            depth -= 1
            if depth == 0:
                return java[i + 1:j]
    return None


def strip_comments(java: str) -> str:
    """주석은 코드가 아니다 — 주석에 적힌 약속을 배선으로 착각하면 눈이 거짓말한다.

    (이 프로젝트에서 눈이 여섯 번 거짓말했다. 그중 하나가 '이름만 보고 속을 안 본' 것이다.)
    """
    java = re.sub(r"/\*.*?\*/", "", java, flags=re.S)
    java = re.sub(r"//[^\n]*", "", java)
    return java


# ═══════════════════════════════════════════════════════════════════════════
#  ① 문 — 나갈 수 있는가
# ═══════════════════════════════════════════════════════════════════════════

def audit_gate(rep: Report, ante: dict, code: str) -> None:
    rep.say("  ① 문 — 나갈 수 있는가")
    gate = ante.get("gate") or {}

    if gate.get("open_when") != "linked":
        rep.bad(f"문의 조건이 등록돼 있지 않다 (gate.open_when = {gate.get('open_when')!r}). "
                "무엇이 문을 여는지 config 가 말해야 한다")
    else:
        rep.good("gate.open_when: linked — 강호에 이름이 오르면 열린다")

    # 문이 코드에 실제로 배선돼 있는가 (주석 말고 코드)
    if not re.search(r"public\s+void\s+cross\s*\(\s*Player", code):
        rep.bad("cross(Player) 가 없다 — 문이 코드에 없다")
    if not re.search(r"ledger\([^)]*\)\.linked\(\)", code):
        rep.bad("cross 가 linked() 를 보지 않는다 — 문의 조건이 코드에 없다")
    else:
        rep.good("cross() 가 PlayerLedger.linked() 를 본다")

    # 종 = 문의 손잡이. 우클릭이 cross 로 이어지는가
    bell = re.search(r"Material\.BELL[\s\S]{0,400}?cross\(", code)
    if not bell:
        rep.bad("종(BELL) 우클릭이 cross() 로 이어지지 않는다 — 손잡이 없는 문이다")
    else:
        rep.good("종(BELL) 우클릭 → cross()")

    # 내리는 자리
    dests = gate.get("destinations") or []
    if not dests:
        rep.bad("gate.destinations 가 비었다 — 건너도 내릴 자리가 없다")
    else:
        rep.good(f"내리는 자리 등록: {dests}")
    # ★ 이름만 보지 않는다 — **속을 본다.** (getSpawnLocation 이 파일 어딘가에 있는 것으로는 부족하다.
    #   destination() 이 null 을 돌려줄 수 있으면 그 순간 사람은 못 내린다.)
    # ★ 서명을 못 박지 않는다 — destination() 은 인자를 받게 바뀌었다(Player). 옛 눈은 `destination\(\)`
    #   만 찾다가 **없다**고 소리쳤다. 눈이 코드보다 늦으면, 그 눈은 거짓 경보를 울린다.
    dest = body_of(code, r"private Location destination\([^)]*\)")
    if dest is None:
        rep.bad("destination() 를 못 찾았다")
    elif "getSpawnLocation()" not in dest:
        rep.bad("destination() 안에 세계 스폰 최종 보루가 없다 — 앵커가 없는 서버에서 못 내린다")
    elif re.search(r"return\s+null\s*;", dest):
        rep.bad("destination() 이 null 을 돌려줄 수 있다 — 건너도 내릴 자리가 없다 (갇힌다)")
    else:
        rep.good("destination() 은 언제나 자리를 준다 (앵커가 전부 없어도 세계 스폰 — 최종 보루)")

    # ★5차 개정 (2026-07-24 사용자 지시) — 과제는 폐지됐다. **lessons 절이 되살아나는 것 자체가 위반**이다
    #   (옛 눈은 gating 만 봤다 — 이제 나루는 시험하지 않으므로 절의 존재가 곧 역행이다)
    if ante.get("lessons"):
        rep.bad("lessons 절이 남아 있다 — 과제는 폐지됐다 (★5차 · 가르침은 본토 뿌리내림 B-178). "
                "나루는 시험하지 않는다: 문지방의 말은 안내판(stations[].panel·arrival)뿐이다")
    else:
        rep.good("과제 없음 — 나루는 시험하지 않는다 (★5차 · 문은 이름이 연다)")


# ═══════════════════════════════════════════════════════════════════════════
#  ② 갇힘 — 한 갈래라도 감옥인가
# ═══════════════════════════════════════════════════════════════════════════

def audit_trap(rep: Report, ante: dict, code: str) -> None:
    rep.say()
    rep.say("  ② 갇힘 — 봇이 꺼졌을 때 · 월드가 안 열릴 때")
    gate = ante.get("gate") or {}
    down = gate.get("bridge_down") or {}

    if not down.get("allow_passage"):
        rep.bad("gate.bridge_down.allow_passage 가 참이 아니다 — 봇이 꺼지면 접합이 원리적으로 불가능하고, "
                "그때 문을 잠그면 사람은 영원히 나루에 갇힌다")
    else:
        rep.good("bridge_down.allow_passage: true")

    # 코드가 실제로 봇의 죽음을 보고 문을 여는가 (worldDay() <= 0 → depart)
    bypass = re.search(r"worldDay\(\)\s*<=\s*0[\s\S]{0,200}?depart\(", code)
    if not bypass:
        rep.bad("worldDay() <= 0 (봇 꺼짐) 우회가 코드에 없다 — config 만 약속하고 배선이 없다")
    else:
        rep.good("worldDay() <= 0 → depart() — 사공이 그냥 건넨다")

    # 월드가 안 열릴 때: enter() 는 아무것도 하지 않아야 한다 (텔레포트 금지)
    body = body_of(code, r"public void enter\(Player player\)")
    if body is None:
        rep.bad("enter(Player) 를 못 찾았다")
    else:
        null_guard = re.search(r"if\s*\(\s*w\s*==\s*null\s*\)\s*\{[\s\S]{0,200}?return;", body)
        if not null_guard:
            rep.bad("enter() 에 월드 실패 가드가 없다 — 나루가 안 열리면 사람이 어디로 가는지 알 수 없다")
        else:
            # 가드가 return 보다 앞에 있고, 그 앞에 teleport 가 없어야 한다
            before = body[: null_guard.start()]
            if "teleport" in before:
                rep.bad("enter() 가 월드 실패 가드보다 먼저 teleport 한다 — 없는 월드로 보낸다")
            else:
                rep.good("월드가 안 열리면 enter() 는 아무것도 하지 않는다 (원래 자리에 그대로 선다)")

    # 나루 안에서 재접속한 접합자 — ★규약 개정 (2026-07-24 실사용 "우클릭 하기도 전에 청하현으로"):
    #   옛 규약은 「접속하는 순간 건네준다」(depart)였다. 그 자동 출항이 재방문자(사공에게 볼일
    #   있는 몸)를 말 걸기도 전에 실어 갔다. 새 규약: **끌고 가지 않되 침묵하지 않는다** —
    #   재방문 표식(revisiting)을 달고 안내(revisitLine — "종을 울리면 언제든")를 말한다.
    #   갇힘 금지의 보증은 depart 가 아니라 **종**(cross — 아래 ⑧ 손잡이 검사)이다.
    jbody = body_of(code, r"public void onJoin\([^)]*\)")
    if jbody is None or not re.search(
            r"(?<!!)isAntechamber\(player\.getWorld\(\)\)[\s\S]*?revisiting\.add\(", jbody):
        rep.bad("onJoin 의 '나루에 서 있는 접합자' 경로가 재방문 표식(revisiting)을 안 단다 — "
                "자동 출항 시계(watchGate)가 이 몸을 첫 건넘으로 오인해 끌고 간다")
    elif not re.search(r"revisiting\.add\(player\.getUniqueId\(\)\);[\s\S]{0,300}?sendMessage\(", jbody):
        rep.bad("나루 안 재접속 접합자에게 아무 말도 안 한다 — 왜 배가 안 뜨는지 침묵한다 "
                "(안내 없는 잔류는 갇힘으로 읽힌다)")
    else:
        rep.good("나루 안 재접속 접합자 = 재방문 (표식 + 안내 — 문은 종이다)")

    # 물안개(leash)는 벽이 아니라 되돌림이어야 한다 — 죽이면 안 된다
    mist = ante.get("mist") or {}
    if not mist.get("leash_blocks"):
        rep.warn("mist.leash_blocks 가 없다 — 나루 밖으로 무한히 걸어 청크를 만들 수 있다")
    if re.search(r"leash[\s\S]{0,400}?(setHealth\(0|damage\(|kill\(\))", code):
        rep.bad("물안개가 사람을 죽인다 — 되돌림이어야 한다")
    else:
        rep.good("물안개는 되돌림이다 (죽이지 않는다)")

    # 대기실에서 죽지 않는다
    for rule in ("FALL_DAMAGE", "DROWNING_DAMAGE"):
        if f"GameRule.{rule}, false" not in code:
            rep.warn(f"{rule} 를 끄지 않았다 — 대기실에서 죽을 수 있다")


# ═══════════════════════════════════════════════════════════════════════════
#  ③ 거짓말 — 가르치는 조작이 실제 조작인가
# ═══════════════════════════════════════════════════════════════════════════

def audit_truth(rep: Report, ante: dict, code: str) -> None:
    rep.say()
    rep.say("  ③ 거짓말 — 문장이 말하는 조작 vs 실제 세계 (★ 답을 손으로 쓰지 않는다. 등록부에서 읽어 대조한다)")
    # 【묘비】 과제 대조 — 태세·경공·격·손 조작표 대조, detect 감지기 배선, 능(能)·requires·예고
    #   (audit_capability), 콤보 오해(audit_combo) — ★5차 개정 (2026-07-24)으로 과제가 폐지돼
    #   표적이 소멸했다. 절의 부활은 audit_gate ①이 잰다. 과제를 되살리는 날 그 눈들도 함께
    #   되살려라 (git: 2026-07-24 이전 antechamber_audit.py).

    # ── 명령: 두 등록부가 정본이다.
    #    `/혼천 X` 는 **마크**(MvtCommand)에도 있고 **디스코드**(봇)에도 있다 — 서로 다른 명령들이다.
    #    한쪽만 보면 눈이 거짓말한다 (`/혼천 시작` 은 디스코드 것이지 마크 것이 아니다).
    mvt = strip_comments(source("MvtCommand.java"))
    mark = set(re.findall(r'case\s+"([^"]+)"', mvt))
    bot_dir = os.path.join(ROOT, "server-bot", "src", "main", "java", "com", "honcheon", "bot")
    discord: set[str] = set()
    if os.path.isdir(bot_dir):
        for fn in os.listdir(bot_dir):
            if fn.endswith(".java"):
                with open(os.path.join(bot_dir, fn), encoding="utf-8") as fh:
                    discord |= set(re.findall(r'case\s+"([^"]+)"', strip_comments(fh.read())))
    pending = set((ante.get("wiring") or {}).get("pending_commands") or {})

    if not mark:
        rep.bad("MvtCommand.java 에서 case 목록을 못 읽었다 — 대조할 정본이 없다")
    if not discord:
        rep.warn("server-bot 에서 명령 목록을 못 읽었다 — 디스코드 쪽 대조를 못 했다")
    rep.good(f"정본 둘 — 마크 {len(mark)}개 · 디스코드 {len(discord)}개 · 배선 대기 {sorted(pending)}")

    # ★ 과제뿐 아니라 **이 config 의 모든 문장**을 훑는다.
    #   '/혼천 협공 캡 +3' 병은 안내 문구에서 났지 과제에서 나지 않았다.
    blob: list[str] = []
    def walk(node):
        if isinstance(node, str):
            blob.append(node)
        elif isinstance(node, dict):
            for v in node.values():
                walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)
    walk(ante)
    for cmd in sorted(set(re.findall(r"/혼천\s+([가-힣]+)", " ".join(blob)))):
        if cmd in mark:
            rep.good(f"문장의 /혼천 {cmd} — 마크에 실재한다")
        elif cmd in discord:
            rep.good(f"문장의 /혼천 {cmd} — 디스코드에 실재한다")
        elif cmd in pending:
            # 배선 대기 — 부모가 MvtCommand 에 넣기로 한 것. ★ 문은 여기에 달려 있지 않다 (문은 종이다)
            rep.warn(f"/혼천 {cmd} — 배선 대기 (MvtCommand 에 아직 없다). "
                     f"부모가 넣기 전까지 사람이 치면 아무 일도 안 일어난다. "
                     f"단 문(종)은 이것과 무관하게 열린다")
        else:
            rep.bad(f"antechamber.yml 의 문장이 없는 명령을 말한다: /혼천 {cmd} "
                    f"(마크에도 디스코드에도 없다) — 사람이 치면 아무 일도 안 일어난다")

    # 배선 대기 명령이 붙을 **공개 손**이 코드에 있는가 (없으면 부모가 배선할 것이 없다)
    for cmd, hook in ((ante.get("wiring") or {}).get("pending_commands") or {}).items():
        method = re.match(r"Antechamber\.(\w+)\(", hook)
        if not method:
            rep.warn(f"wiring.pending_commands['{cmd}'] 가 어느 메서드에 붙는지 안 적혀 있다")
        elif not re.search(r"public\s+void\s+" + re.escape(method.group(1)) + r"\s*\(\s*Player", code):
            rep.bad(f"배선 대기 명령 /혼천 {cmd} 이 붙을 손이 없다 — "
                    f"Antechamber.{method.group(1)}(Player) 가 public 이 아니다")
        else:
            rep.good(f"/혼천 {cmd} → Antechamber.{method.group(1)}(Player) — 손은 준비됐다")

    audit_discord(rep, ante)


# 【묘비】 ③-a 콤보 오해의 눈(audit_combo) · ③-b 능(能)의 눈(audit_capability) — ★5차 개정
#   (2026-07-24)으로 과제가 폐지돼 표적이 소멸했다. 그 계율은 남는다:
#   · "이것은 콤보가 아니다" — 그림의 리듬을 입력의 문법이라 가르치지 마라
#   · "못 하는 것을 시키지 마라" — requires/예고 문법의 상속자는 본토 뿌리내림의 「막기 예고」다
#   과제를 되살리는 날 이 두 눈도 함께 되살려라 (git: 2026-07-24 이전 판).

# ═══════════════════════════════════════════════════════════════════════════
#  ③-c 접합 — ★ 디스코드가 되돌려보내는 문을 대기실이 말하는가
# ═══════════════════════════════════════════════════════════════════════════
#
# 접속 과제는 "발판 밟아라 → 코드 나온다 → 디스코드에서 확정한다" 라고만 적혀 있었다.
# 그런데 코드를 받는 쪽(server-bot GameListener.linkWithCode)은 그 **앞에 문을 둘 더** 두고 있다:
#
#     "캐릭터가 없다. `/혼천 시작`부터."          ← 이름·성별·서장은 **디스코드에서** 만든다
#     "아직 서장 중이다. 서장 스레드를 끝내야 출도한다."
#
# 그 둘을 안 적으면, 발판을 밟고 코드를 붙여넣은 사람이 **거기서 튕긴다** — 그리고 대기실은
# 아무 예고도 안 했다. 이 눈은 **봇의 거절 문구**를 읽어, 대기실이 그 선행 문을 말하는지 본다.

def audit_discord(rep: Report, ante: dict) -> None:
    # ★5차 재표적 — 선행 문의 눈은 산다. 옛 표적(접속 과제 how)이 폐지돼, 이제 그 문장을 이은
    #   **부두 관문의 안내판**(stations[나루].panel — 종을 품은 관문)을 읽는다.
    bell0 = ((ante.get("dock") or {}).get("bell") or [26, 0])[0]
    dock_st = next((s for s in (ante.get("stations") or [])
                    if abs(bell0 - s.get("x", 0)) <= s.get("half", 4)), None)
    panel = (dock_st or {}).get("panel") or []
    how = re.sub(r"[§][0-9a-fk-or]", "", "\n".join(panel))
    if not how:
        rep.bad("부두 관문의 안내판이 비었다 — 접속으로 가는 길을 아무도 말해 주지 않는다")

    bot = os.path.join(ROOT, "server-bot", "src", "main", "java", "com", "honcheon", "bot",
                       "GameListener.java")
    if not os.path.isfile(bot):
        rep.warn("server-bot GameListener.java 가 없다 — 접합의 선행 문을 대조 못 했다")
        return
    with open(bot, encoding="utf-8") as fh:
        gl = fh.read()

    # 봇이 코드를 받기 전에 되돌려보내는 문 — **그 문구에서 명령을 읽는다** (손으로 안 쓴다)
    prereqs = sorted(set(re.findall(r"캐릭터가 없다[^\"]*?`/혼천 ([가-힣]+)`", gl)))
    if not prereqs:
        rep.warn("봇의 '캐릭터가 없다' 거절 문구를 못 찾았다 — 접합의 선행 문을 대조 못 했다")
    for cmd in prereqs:
        if cmd not in how:
            rep.bad(f"안내판이 **선행 문**을 말하지 않는다 — 봇은 캐릭터가 없으면 "
                    f"'/혼천 {cmd} 부터' 라며 되돌려보낸다. 판이 그것을 **밟기 전에** 안 적으면, "
                    f"발판을 밟은 사람이 **거기서 튕긴다**")
        else:
            rep.good(f"안내판이 선행 문(/혼천 {cmd})을 밟기 전에 말한다")

    # ★★ **접합의 흐름을 대기실이 적지 않는다** — 그것은 접합 담당의 몫이고, **지금 바뀌는 중이다**
    #   (코드 방식 폐기 → 초대 링크 + 닉네임 + 수락 창). 발판은 `/혼천 접속` 을 **대신 쳐 줄 뿐**이고,
    #   그 명령이 무엇을 말하든 그대로 흐른다. 여기에 흐름을 적어 두면 **다음 주에 거짓말이 된다.**
    doomed = [w for w in ("코드 복사", "코드 칸", "붙여넣", "1회용", "10분") if w in how]
    if doomed:
        rep.bad(f"안내판이 **접합의 흐름**을 적고 있다 {doomed} — 그것은 대기실의 몫이 아니다. "
                "접합 방식은 바뀐다 (코드 → 초대 링크·닉네임·수락 창). 발판은 명령을 대신 쳐 줄 뿐이고, "
                "**화면이 말하게 두어야** 대기실이 늙지 않는다")
    else:
        rep.good("안내판은 접합의 흐름을 적지 않는다 (화면이 말한다 — 대기실은 안 늙는다)")

    # ★ 「접속」 발판은 **종 앞**이어야 한다 (사용자: "종 앞 발판 밟으면 디코 접속 메시지 뜨도록")
    #   = 같은 길(z) 위에서, 종에 닿기 **전에** 밟히는 자리
    bell = (ante.get("dock") or {}).get("bell") or []
    road_z = (ante.get("road") or {}).get("z", 0)
    plate = next((p for p in ((ante.get("plates") or {}).get("list") or [])
                  if p.get("command", "").endswith("접속")), None)
    if not plate or len(bell) < 2:
        rep.bad("「접속」 발판이나 종이 등록부에 없다 — 종 앞에서 이름을 올릴 자리가 없다")
    else:
        px, pz = plate["pos"]
        if pz != road_z or not (0 < bell[0] - px <= 6):
            rep.bad(f"「접속」 발판(x {px}, z {pz})이 **종 앞이 아니다** (종 x {bell[0]}, 길 z {road_z}) — "
                    "종으로 걸어가는 길 위에서, 종에 닿기 전에 밟히는 자리여야 한다")
        else:
            rep.good(f"「접속」 발판은 종 앞이다 (x {px} → 종 x {bell[0]} · 길 z {road_z})")


# ═══════════════════════════════════════════════════════════════════════════
#  ⑫ 조성 — ★★ **반쯤 선 것을 "서 있다"고 하는가**
# ═══════════════════════════════════════════════════════════════════════════
#
# 오늘 크래시가 나루를 **반쯤 지어 놓고** 죽였다. 다음 기동에서 조성기는 *"이미 서 있다"* 며
# 건너뛰었다. 왜냐하면 built() 가 본 것이 **블록 하나**였기 때문이다:
#
#     return w.getBlockAt(bell...).getType() == Material.BELL;
#
# 종 하나가 놓였다는 것은 **종 하나가 놓였다는 뜻**이지 나루가 섰다는 뜻이 아니다.
# 수만 칸짜리 판을 한 칸으로 판단했다 — **한 칸은 표본이 아니다.**
#
# 이 눈은 조성 완결성의 판단이 **세계에게 묻는 것**인지 본다: 판을 훑어 세고, 등록부의 문턱과
# 견주고, 못 미치면 다시 짓는가. (그리고 그 문턱이 config 에 있는가 — 코드가 숫자를 지어내면 안 된다)

def audit_wholeness(rep: Report, ante: dict, code: str) -> None:
    rep.say()
    rep.say("  ⑫ 조성 — ★ 반쯤 선 나루를 '서 있다'고 하는가")

    # ★ 판은 같은 칸을 한 번만 적어야 한다 (실측 2026-07-24 — 갈대가 물을, 고사목이 공기를
    #   겹쳐 써 완결성 검증이 **제 판에 속아** 94%: 세계는 성한데 눈이 "반쯤 섰다"며
    #   매 진입마다 다시 지을 뻔했다)
    plan_body = body_of(code, r"private List<Place> plan\(int gy\)")
    if plan_body is None:
        rep.bad("plan() 을 못 찾았다 — 조성 판이 코드에 없다")
    elif ("dedup.remove(key)" not in plan_body
            or "return new ArrayList<>(dedup.values());" not in plan_body):
        rep.bad("plan() 이 같은 칸의 겹쳐 쓰기를 안 걷어낸다 — 판이 제 자신과 어긋나 "
                "완결성 검증이 성한 세계를 「반쯤 섰다」고 오진한다 (그리고 얹히는 것이 "
                "받침을 앞지른다)")
    else:
        rep.good("판은 같은 칸을 한 번만 적는다 (마지막 기록·마지막 자리 — 검증이 제 판에 안 속는다)")

    b = ante.get("build") or {}
    sample = b.get("verify_sample")
    minpct = b.get("verify_min_pct")
    if not sample or not minpct:
        rep.bad("build.verify_sample / verify_min_pct 가 등록돼 있지 않다 — "
                "조성이 '끝났는가'의 눈금을 코드가 지어내게 된다")
        return
    rep.good(f"조성 완결성 눈금: 표본 1/{sample} · 문턱 {minpct}%")

    if not isinstance(minpct, int) or not 50 <= minpct <= 100:
        rep.bad(f"build.verify_min_pct 가 이상하다: {minpct!r} (50~100 이어야 한다)")

    # ① 완결성을 **세는가** — 판을 훑어 세계와 대조하는가
    body = body_of(code, r"private int completeness\([^)]*\)")
    if body is None:
        rep.bad("completeness(World) 가 없다 — 조성이 끝났는지 **세는 곳**이 없다")
        return
    if not re.search(r"plan\(", body) or not re.search(r"getBlockAt\(", body):
        rep.bad("completeness() 가 조성 판(plan)을 세계(getBlockAt)와 대조하지 않는다 — "
                "무엇을 세고 있는지 모르겠다")
    else:
        rep.good("completeness() 가 조성 판을 세계와 한 칸씩 대조한다")

    # ② ★ 결정론 — 난수 표본이면 같은 세계가 매번 다른 점수를 받는다
    if re.search(r"(Random|random\(|ThreadLocalRandom)", body):
        rep.bad("completeness() 가 **난수**로 표본을 집는다 — 같은 세계가 매번 다른 점수를 받는다 "
                "(결정론 규약 위반: 조성이 켜졌다 꺼졌다 한다)")
    else:
        rep.good("표본은 결정론이다 (고정 간격 — 같은 세계는 같은 점수)")

    # ③ **한 칸으로 판단하지 않는가** — 옛 병 그대로가 남아 있는가
    old = re.search(r"private boolean built\s*\([^)]*\)\s*\{[^}]*getBlockAt[^}]*Material\.BELL", code)
    if old:
        rep.bad("★ built() 가 여전히 **블록 하나(BELL)**를 보고 나루가 섰다고 판단한다 — "
                "반쯤 선 나루가 '이미 서 있다'로 읽힌다 (오늘의 병 그대로다)")
    else:
        rep.good("블록 하나로 나루를 판단하지 않는다")

    # ④ 못 미치면 **다시 짓는가** (세기만 하고 안 지으면 눈은 있고 손이 없는 것이다)
    bb = body_of(code, r"void build\([^)]*\)")
    if bb is None:
        rep.bad("build() 를 못 찾았다")
    elif not re.search(r"completeness\(", bb) or not re.search(r"verifyMinPct", bb):
        rep.bad("build() 가 완결성 점수를 등록부의 문턱(verify_min_pct)과 견주지 않는다 — "
                "세어 놓고 그 답을 안 쓴다")
    else:
        rep.good("build() 가 문턱에 못 미치면 처음부터 다시 짓는다")

    # ⑤ 그리고 **소리를 내는가** — 침묵이 성공으로 읽히면 안 된다 (오늘 그 침묵이 병이었다)
    if bb and not re.search(r"(warning|severe)\(", bb):
        rep.warn("build() 가 반쯤 선 나루를 만났을 때 로그에 아무 말도 안 한다 — "
                 "침묵은 '잘 지어졌다'로 읽힌다")

    # ══════════════════════════════════════════════════════════════════
    #  ★★ 이정표 — **표본은 부피를 재지 의미를 재지 않는다**
    # ══════════════════════════════════════════════════════════════════
    #
    # 2026-07-13. 사용자: **"발판 밟아도 메시지가 안 뜬다."** 재 보니 나루에 압력판이 **0개**.
    # 그런데 완결도는 **97%** 였고 조성기는 "이미 서 있다"며 건너뛰었다.
    #
    # 왜인가: 조성 판은 **늪(물·자갈·허공)이 99% 를 차지한다.** 발판 6칸은 4만 칸 중 6칸 —
    # **0.015%** 다. 표본(1/61)이 그것을 집을 확률은 거의 0 이다. 그래서 **발판이 하나도 없어도
    # 표본은 97% 를 준다.** 문턱을 넘으니 영영 안 깔렸다.
    #
    # 드물지만 없으면 튜토리얼이 통째로 죽는 것(발판·종)은 **세지 않고 전수 검사**해야 한다.
    lm = body_of(code, r"private boolean landmarksStand\([^)]*\)")
    if lm is None:
        rep.bad("★★ 이정표(발판·종) 전수 검사가 없다 — 표본은 **부피를 재지 의미를 재지 않는다.** "
                "발판 6칸은 4만 칸 중 6칸(0.015%)이라 표본이 못 본다. "
                "**발판이 0개인데 완결도 97% 로 '이미 서 있다'가 된다** (오늘의 병 그 자체)")
    elif not re.search(r"countPlates\(", lm):
        rep.bad("이정표 검사가 발판을 세지 않는다 — 밟을 것이 없는 나루가 '섰다'로 읽힌다")
    else:
        rep.good("이정표(발판·종)는 표본이 아니라 **전수 검사**한다")

    if bb and lm is not None and not re.search(r"landmarksStand\(", bb):
        rep.bad("build() 가 이정표를 안 본다 — 세어 놓고 그 답을 안 쓴다 "
                "(발판 0개인 나루가 '이미 서 있다'로 통과한다)")

    # ⑥ ★ 발판을 **세계에게 묻는가** — plates.size() 는 **등록부의 개수**다 (오늘 이것이 거짓말했다)
    cp = body_of(code, r"private int countPlates\([^)]*\)")
    if cp is None:
        rep.bad("countPlates(World) 가 없다 — 세계에 발판이 몇 개 깔렸는지 **묻는 곳이 없다**")
    elif not re.search(r"getBlockAt\(", cp):
        rep.bad("countPlates() 가 세계에게 묻지 않는다 — 등록부의 개수를 세고 있다")
    else:
        rep.good("countPlates() 는 세계에게 묻는다 (깔린 것을 센다)")

    cen = body_of(strip_comments(code), r"private void census\(World w, String head\)")
    if cen is None:
        rep.bad("census() 를 못 찾았다")
    elif re.search(r'"\s*·\s*발판\s*"\s*\+\s*plates\.size\(\)', cen):
        rep.bad("★ 조성 로그가 발판을 **등록부에서** 센다 (`plates.size()`) — "
                "세계에 0개가 깔려 있어도 '발판 6' 이라 말한다. 오늘 사용자가 밟을 것이 없었던 이유를 "
                "로그가 **가려 주고 있었다**. 세계에게 물어 `N/M` 꼴로 찍어라")
    elif not re.search(r"countPlates\(", cen):
        rep.bad("조성 로그가 발판을 세계에게 묻지 않는다 — 침묵(과 등록부)이 성공으로 읽힌다")
    else:
        rep.good("조성 로그는 발판도 세계에게 묻는다 (발판 N/M)")


# ═══════════════════════════════════════════════════════════════════════════
#  ④ 글판 — TextDisplay 가 세계에 남지 않는가 · 판이 과제와 같은 말을 하는가
# ═══════════════════════════════════════════════════════════════════════════

def audit_panels(rep: Report, ante: dict, code: str) -> None:
    rep.say()
    rep.say("  ④ 글판 — TextDisplay (표지판이 아니다)")
    td = ante.get("text_display") or {}

    cap = td.get("max_panels")
    if not cap:
        rep.bad("text_display.max_panels 가 없다 — 상주 엔티티에 상한이 없다")

    # 표지판을 쓰지 않는다 (사용자 판정)
    if re.search(r"Material\.(OAK|SPRUCE|BIRCH|DARK_OAK|ACACIA|JUNGLE)_SIGN", code):
        rep.bad("아직 표지판(Sign)을 세운다 — 글자가 작고 네 줄에 묶인다. TextDisplay 를 쓰라")
    else:
        rep.good("표지판(Sign)을 쓰지 않는다")

    # TextDisplay 의 값어치: 크기 · 정면 · 배경
    for need, why in (
        (r"setBillboard\(", "billboard — 어디서 보든 정면으로 서지 않는다 (각도를 맞춰야 읽힌다)"),
        (r"setTransformation\([\s\S]{0,200}?Vector3f\(\s*panelSpec\.scale\(\)", "scale — 글자가 안 커진다 (표지판과 같아진다)"),
        (r"setDefaultBackground\(false\)", "배경 — 바닐라 반투명 검정 상자가 뜬다 (수묵 세계가 아니다)"),
        (r"setBackgroundColor\(Color\.fromARGB\(", "배경색 — config 의 수묵 팔레트를 안 쓴다"),
        (r"setViewRange\(", "view_range — 얼마나 멀리서 읽히는지 정하지 않았다"),
    ):
        if not re.search(need, code):
            rep.bad(f"TextDisplay 에 {why}")
        else:
            rep.good(f"TextDisplay {need.split('(')[0]} — 있다")

    for key in ("scale", "view_range", "background_argb", "billboard"):
        if key not in td:
            rep.bad(f"text_display.{key} 가 config 에 없다 — 코드가 지어낸다")

    # ★ 정리 — 재조성 때 겹치지 않는가 · 내려갈 때 걷는가
    if not re.search(r"void clearPanels\(", code):
        rep.bad("clearPanels() 가 없다 — 글판을 걷을 손이 없다")
    if not re.search(r"public void shutdown\(\)[\s\S]{0,600}?clearPanels\(", code):
        rep.bad("shutdown() 이 clearPanels() 를 안 부른다 — 플러그인이 내려가도 글이 세계에 남는다")
    else:
        rep.good("shutdown() 이 글판을 걷는다")
    # ★ 이름만 보지 않는다 — **조성기의 속을 본다.** (파일 어딘가에 clearPanels+spawnPanels 쌍이 있는 것으로는
    #   부족하다. ensurePanels 에 그 쌍이 있다고 build 가 안전한 것이 아니다.)
    build_body = body_of(code, r"void build\(World w, boolean force, Runnable onDone\)")
    if build_body is None:
        rep.bad("build() 를 못 찾았다")
    else:
        # 직접 호출이든 단위 격리(stage(w, ..., this::spawnPanels))든 — **이름이 나오는 순서**를 본다.
        # (재조성 경로에서 걷기가 세우기보다 **뒤에** 오면 글이 두 겹으로 겹친다.)
        rebuild = build_body[build_body.find("building = true"):] or build_body
        ci = rebuild.find("clearPanels")
        si = rebuild.find("spawnPanels")
        if si < 0:
            rep.bad("build() 가 글판을 세우지 않는다")
        elif ci < 0 or ci > si:
            rep.bad("build() 가 걷기 전에 세운다 — 다시 지으면 글이 두 겹으로 겹친다")
        else:
            rep.good("재조성은 걷고 나서 세운다 (겹치지 않는다)")
    if not re.search(r"KEY_PANEL", code) or not re.search(
            r"getPersistentDataContainer\(\)\.set\(KEY_PANEL", code):
        rep.bad("글판에 표식(PDC)이 없다 — 걷을 때 무엇이 우리 것인지 알 수 없다")
    else:
        rep.good("글판에 표식(KEY_PANEL)이 있다 — 우리 것만 걷는다")

    # ★ 판이 등록부와 **같은 말**을 하는가 (★5차 재표적 — 과제 폐지 후 판은 안내판이다).
    #   v2 에서 판은 config 에 따로 배치하지 않는다 — **관문(stations)이 곧 판의 자리**다.
    #     ① 판의 문장은 등록부(Station.panel · 맞이는 arrival.lines)에서 **그대로** 나온다
    #     ② 안내판 없는 관문이 없다 (침묵하는 관문은 관문이 아니다)
    pt = body_of(code, r"private List<String> panelText\(Station s\)")
    if pt is None:
        rep.bad("panelText(Station) 을 못 찾았다 — 판의 문장이 어디서 오는지 알 수 없다")
    elif "return s.panel();" not in pt or "arrivalLines" not in pt:
        rep.bad("판의 문장이 등록부(Station.panel · arrival)에서 나오지 않는다 — 판이 딴말을 할 수 있다")
    else:
        rep.good("판의 문장 = 등록부(Station.panel · arrival) 그대로 (panelText 가 유일한 출처 — 딴말이 불가능하다)")

    arrival_id = str(td.get("arrival_id") or "맞이")
    for st in (ante.get("stations") or []):
        if not st.get("panel") and st.get("id") != arrival_id:
            rep.bad(f"관문 '{st.get('id')}' 의 안내판이 비었다 — 문지방이 아무 말도 하지 않는다 "
                    "(arrival 관문이 아니면 panel 이 있어야 한다)")
        else:
            rep.good(f"관문 '{st.get('id')}' — 판의 문장이 등록부에 있다 "
                     f"({'arrival.lines' if st.get('id') == arrival_id else 'panel'})")

    # 【묘비】 두 판(가능/예고) 배타 검사 — 예고 변형은 과제와 함께 걷혔다 (★5차). 판은 늘 보인다.
    if "_없음" in strip_comments(code):
        rep.bad("예고 판(_없음) 잔재가 코드에 남아 있다 — 과제 폐지(★5차) 뒤에 예고할 것이 없다")

    expected = len(ante.get("stations") or [])
    if cap and expected > cap:
        rep.bad(f"글판 {expected}개 > 상한 {cap}")
    else:
        rep.good(f"글판 {expected}개 (관문마다 안내판 하나) ≤ 상한 {cap}")


# ═══════════════════════════════════════════════════════════════════════════
#  ⑤ 규약 — 결정론 · PUA
# ═══════════════════════════════════════════════════════════════════════════

def audit_conventions(rep: Report, code: str, raw_cfg: str) -> None:
    rep.say()
    rep.say("  ⑤ 규약 — 결정론 · PUA · 틱 슬라이싱")
    body = strip_comments(code)
    for bad in ("ThreadLocalRandom", "Math.random", "new Random"):
        if bad in body:
            rep.bad(f"조성기에 난수가 있다: {bad} — 대기실은 결정론이어야 한다")
    if "Math.floorMod" not in body:
        rep.warn("Math.floorMod 해시를 안 쓴다 — 무늬가 결정론인지 확인이 필요하다")
    else:
        rep.good("무늬는 좌표 해시(Math.floorMod) — 난수 없음")

    if not re.search(r"TickBudget\.slice\(", body):
        rep.bad("조성이 틱 슬라이싱을 안 탄다 — 한 틱에 다 지으면 서버가 선다")
    else:
        rep.good("조성이 TickBudget.slice 를 탄다")

    pua = [c for c in (code + raw_cfg) if 0xE000 <= ord(c) <= 0xF8FF]
    if pua:
        rep.bad(f"PUA 문자 리터럴이 있다 ({len(pua)}자) — 금지")
    else:
        rep.good("PUA 문자 리터럴 없음")

    if "getHighestBlockYAt" not in body:
        rep.bad("지면을 월드에게 묻지 않는다 — 평면 월드의 지면은 y5 가 아니라 y-61 이다 (낙사)")
    else:
        rep.good("지면을 월드에게 묻는다 (getHighestBlockYAt)")



# ═══════════════════════════════════════════════════════════════════════════
#  기하 — config 에서 나루의 몸을 다시 세운다 (감사가 걸어 볼 수 있게)
# ═══════════════════════════════════════════════════════════════════════════
#
# ★ 손으로 답을 쓰지 않는다. 좌표는 **config 가 정본**이고(등록제), 이 클래스는 그 정본에서
#   마른 땅·막힌 칸·광원을 **다시 계산한다**. 그리고 Antechamber.java 가 같은 규칙으로 짓는지는
#   audit_road 의 코드 검사(isDeck 의 구성)가 따로 지킨다.

class Geo:
    def __init__(self, ante: dict) -> None:
        r = ante.get("road") or {}
        self.rz = r.get("z", 0)
        self.half = r.get("half_width", 1)
        self.x0 = r.get("from", -30)
        self.x1 = r.get("to", 26)
        self.deck = r.get("deck_y", 1)          # 지면 기준 — 발은 deck+1 에 선다
        self.gaps = r.get("gaps") or []
        self.spawn = tuple(ante.get("spawn") or [self.x0, self.rz])
        self.stations = ante.get("stations") or []
        m = ante.get("marsh") or {}
        self.mx = m.get("x", [-38, 34])
        self.mz = m.get("z", [-22, 22])
        h = ante.get("hut") or {}
        self.hx = h.get("x", [19, 24])
        self.hz = h.get("z", [5, 10])
        self.wall_h = h.get("wall_h", 3)
        li = ante.get("lighting") or {}
        self.post_every = li.get("post_every", 9)
        self.post_alt = li.get("post_alternate", True)
        self.post_z = li.get("post_z", 2)
        self.brazier_st = set(li.get("brazier_stations") or [])
        self.hut_lantern = li.get("hut_lantern", True)
        self.eave = h.get("eave", 2)
        # ★명계 개정 — 저승 구간(넋등)의 리듬 (경계는 stations 등록부에서 유도)
        so = li.get("soul") or {}
        self.soul_until = so.get("until_station", "")
        self.soul_every = so.get("post_every", 5)
        self.soul_dark = so.get("dark_pct") or [40, 92]
        # ★삼도천 화폭 — 옛 잔교의 석등 (습지 어둠 계산에 실린다)
        cv = ante.get("canvas") or {}
        rl = cv.get("relics") or {}
        self.relic_z = rl.get("z", -9)
        self.relic_lanterns = rl.get("stone_lanterns") or []
        d = ante.get("dock") or {}
        self.bell = tuple(d.get("bell") or [26, 0])
        self.plates = ((ante.get("plates") or {}).get("list")) or []

    # ── 마른 땅 ──
    def on_bypass(self, x, z):
        for g in self.gaps:
            bz = g.get("bypass_z") or []
            if len(bz) == 2 and g["from"] <= x <= g["to"] and min(bz) <= z <= max(bz):
                return True
        return False

    def in_gap(self, x, z):
        for g in self.gaps:
            if g["from"] <= x <= g["to"] and not self.on_bypass(x, z):
                return True
        return False

    def on_road(self, x, z):
        return self.x0 <= x <= self.x1 and abs(z - self.rz) <= self.half

    def station_at(self, x, z):
        for s in self.stations:
            if abs(x - s["x"]) <= s["half"] and abs(z - self.rz) <= s["half"]:
                return s
        return None

    def on_hut(self, x, z):
        return self.hx[0] <= x <= self.hx[1] and self.hz[0] <= z <= self.hz[1]

    def abuts_hut(self, x, z):
        return self.on_hut(x, z + 1) or self.on_hut(x, z - 1)

    def soul_boundary(self):
        # 저승 구간의 동쪽 끝 — soul.until_station 관문의 서쪽 끝 (Java soulBoundary 와 같은 규칙).
        # 등록부가 모르는 이름이면 저승 구간이 없다 (전부 이승) — audit_canvas 가 그 오배선을 잡는다.
        for s in self.stations:
            if s["id"] == self.soul_until:
                return s["x"] - s["half"]
        return self.x0

    def lamp_side(self, x):
        if not (self.x0 <= x <= self.x1):
            return None
        n = x - self.x0
        # ★명계 개정 — 두 리듬: 저승(넋등)은 촘촘, 이승(등롱)은 성글다 (Java lampSide 와 같은 규칙)
        every = self.soul_every if x < self.soul_boundary() else self.post_every
        if n % every != 0:
            return None
        if not self.post_alt:
            return self.post_z
        return self.post_z if (n // every) % 2 == 0 else -self.post_z

    def on_lamp_bracket(self, x, z):
        side = self.lamp_side(x)
        return side is not None and z == self.rz + side

    def is_deck(self, x, z):
        if self.in_gap(x, z):
            return False
        return (self.on_road(x, z) or self.station_at(x, z) is not None
                or self.on_bypass(x, z) or self.on_hut(x, z) or self.on_lamp_bracket(x, z))

    def deck_cells(self):
        return {(x, z)
                for x in range(self.mx[0], self.mx[1] + 1)
                for z in range(self.mz[0], self.mz[1] + 1)
                if self.is_deck(x, z)}

    def brazier_at(self, s):
        return (s["x"] + s["half"] - 1, self.rz - (s["half"] - 1))

    # ── 딛을 수 없는 칸 (deck+1 에 뭔가 서 있다) ──
    def blocked(self):
        out = set()
        for s in self.stations:                     # 난간
            for x in range(s["x"] - s["half"], s["x"] + s["half"] + 1):
                for side in (-s["half"], s["half"]):
                    z = self.rz + side
                    if (self.on_hut(x, z) or self.on_lamp_bracket(x, z)
                            or self.abuts_hut(x, z) or self.in_gap(x, z)):
                        continue
                    out.add((x, z))
        for x in range(self.x0, self.x1 + 1):       # 등롱 기둥
            side = self.lamp_side(x)
            if side is not None:
                out.add((x, self.rz + side))
        for s in self.stations:                     # 화톳불
            if s["id"] in self.brazier_st:
                out.add(self.brazier_at(s))
        mid = (self.hx[0] + self.hx[1]) // 2
        for x in range(self.hx[0], self.hx[1] + 1):   # 집의 벽 (문만 뚫려 있다)
            for z in range(self.hz[0], self.hz[1] + 1):
                edge = x in (self.hx[0], self.hx[1]) or z in (self.hz[0], self.hz[1])
                door = z == self.hz[0] and mid <= x <= mid + 1
                if edge and not door:
                    out.add((x, z))
        out.add(self.bell)                          # 종도 블록이다
        return out

    def walkable(self):
        return self.deck_cells() - self.blocked()

    # ── 빛 (해석 모형: 밝기 = 광원세기 − 맨해튼거리, 7 미만이면 암흑) ──
    #    ★명계 개정 — 광원마다 세기가 다르다: 넋등(SOUL_LANTERN) 10 · 등롱/화톳불 15.
    #    세기를 뭉뚱그리면 눈이 저승을 이승만큼 밝다고 잰다 (그 거짓말을 막으려 개정했다)
    def sources(self):
        out = []
        b = self.soul_boundary()
        for x in range(self.x0, self.x1 + 1):
            side = self.lamp_side(x)
            if side is not None:
                out.append((x, self.deck + 2, self.rz + side, 10 if x < b else 15))
        for s in self.stations:
            if s["id"] in self.brazier_st:
                bx, bz = self.brazier_at(s)
                out.append((bx, self.deck + 1, bz, 15))
        if self.hut_lantern:
            out.append(((self.hx[0] + self.hx[1]) // 2, self.deck + self.wall_h,
                        (self.hz[0] + self.hz[1]) // 2, 15))
        return out

    def relic_sources(self):
        # 옛 잔교의 석등 (넋등 10) — 잔교의 등불이 아니라 습지의 화폭이다:
        # 습지 어둠(②)에는 실리고, 광원 밀도(③ — 등롱 도배의 눈)에는 안 실린다
        return [(lx, self.deck + 1, self.relic_z, 10) for lx in self.relic_lanterns]

    def light_at(self, x, z, srcs):
        foot = self.deck + 1
        best = 0
        for sx, sy, sz, power in srcs:
            lvl = power - (abs(sx - x) + abs(sy - foot) + abs(sz - z))
            if lvl > best:
                best = lvl
        return best


# ═══════════════════════════════════════════════════════════════════════════
#  ⑥ 길 — 하나인가 · 걸어서 끝까지 가는가 · 갈림길은 없는가
# ═══════════════════════════════════════════════════════════════════════════

def audit_road(rep: Report, ante: dict, code: str) -> None:
    rep.say()
    rep.say("  ⑥ 길 — 한 길인가 (도달성 BFS · 갈림길 · 관문 순서)")
    g = Geo(ante)

    if not g.stations:
        rep.bad("관문이 없다 — 길에 아무것도 없다")
        return

    # 관문은 길 위에 있고, x 는 오름차순이어야 한다 (순서가 곧 길이다)
    xs = [s["x"] for s in g.stations]
    if xs != sorted(xs):
        rep.bad(f"관문의 x 가 오름차순이 아니다 {xs} — 길을 걸으면 순서가 뒤엉킨다")
    else:
        rep.good(f"관문 순서 = 길 순서 {[s['id'] for s in g.stations]}")
    for s in g.stations:
        if not (g.x0 <= s["x"] <= g.x1):
            rep.bad(f"관문 '{s['id']}' (x={s['x']}) 가 길 밖에 있다 [{g.x0}..{g.x1}] — 아무도 못 간다")

    # 첫 관문(맞이 글판) — ★4차 개정 (사용자 실측 "코앞이면 못 읽는다"): 스폰과 같은 자리가
    #   아니라 **열 걸음쯤 앞**이어야 한다 (원 지시도 "소환 위치에서 10걸음 범위 정도").
    #   뒤(서쪽)에 있으면 아무도 못 보고, 너무 멀면 안내가 늦는다.
    gap0 = g.stations[0]["x"] - g.spawn[0]
    if gap0 < 4 or gap0 > 14:
        rep.warn(f"맞이 글판(x={g.stations[0]['x']}) 이 스폰(x={g.spawn[0]}) 에서 {gap0}칸 — "
                 "4~14칸(열 걸음 안팎) 앞이어야 읽힌다 (코앞도 등 뒤도 안 된다)")

    # ★ 걸어서 끝까지 가는가 — BFS. 뛰지 않고, 물에 안 빠지고
    #   (RegionAudit.groundStandY 의 교훈: 걷는 검사가 지붕에서 출발하면 무엇을 재든 거짓이다.
    #    여기서는 출발점이 **잔교 널판** 임을 먼저 확인한다.)
    walk = g.walkable()
    start = tuple(g.spawn)
    if start not in walk:
        rep.bad(f"눈 뜨는 자리 {list(start)} 가 딛을 수 있는 땅이 아니다 — 물에 떨어뜨리거나 벽에 끼워 넣는다")
        return
    rep.good(f"출발점 {list(start)} 은 잔교 널판 위다 (지붕에서 출발하지 않는다)")

    seen = {start}
    queue = [start]
    while queue:
        x, z = queue.pop()
        for nx, nz in ((x + 1, z), (x - 1, z), (x, z + 1), (x, z - 1)):
            if (nx, nz) in walk and (nx, nz) not in seen:
                seen.add((nx, nz))
                queue.append((nx, nz))

    bx, bz = g.bell
    at_bell = [c for c in ((bx - 1, bz), (bx + 1, bz), (bx, bz - 1), (bx, bz + 1)) if c in walk]
    if not at_bell:
        rep.bad(f"종 {list(g.bell)} 옆에 설 자리가 없다 — 문에 손이 안 닿는다")
    elif not any(c in seen for c in at_bell):
        rep.bad(f"걸어서 종에 닿을 수 없다 — 잔교가 끊겼는데 우회로가 없다 (★ 갇힌다). "
                f"도달 {len(seen)}칸 / 전체 {len(walk)}칸")
    else:
        rep.good(f"걸어서 종까지 간다 — 뛰지 않고 (도달 {len(seen)}칸 / 마른 땅 {len(walk)}칸)")

    # 끊긴 자리마다 우회로가 있어야 한다 (없으면 못 뛰는 사람이 갇힌다)
    for gap in g.gaps:
        bz2 = gap.get("bypass_z") or []
        if len(bz2) != 2:
            rep.bad(f"끊긴 자리 x{gap['from']}~{gap['to']} 에 우회로가 없다 — "
                    "점프를 못 하는 사람은 여기서 영영 못 간다 (★ 함정)")
            continue
        width = gap["to"] - gap["from"] + 1
        if width > 4:
            rep.bad(f"끊긴 폭 {width} 칸 — 달리며 점프로 못 건넌다 (바닐라 한계 ~4)")
        else:
            rep.good(f"끊긴 폭 {width} 칸 (달리며 점프로 건넌다) + 우회로 z{bz2}")

    # ★ 갈림길 — 마른 땅이 **한 덩어리**여야 한다. 섬이 있으면 그건 길이 아니라 파편이다
    islands = len(walk) - len(seen)
    if islands > 0:
        rep.bad(f"마른 땅에 닿을 수 없는 섬이 {islands}칸 있다 — 길이 한 덩어리가 아니다")
    else:
        rep.good("마른 땅이 한 덩어리다 (떠 있는 섬이 없다)")

    # 길 밖으로 새는 마른 땅이 없는가 — **물이 길을 하나로 만든다**는 전제의 검산
    deck = g.deck_cells()
    stray = [(x, z) for (x, z) in deck
             if not (g.on_road(x, z) or g.station_at(x, z) or g.on_bypass(x, z)
                     or g.on_hut(x, z) or g.on_lamp_bracket(x, z))]
    if stray:
        rep.bad(f"등록되지 않은 마른 땅 {len(stray)}칸 — 갈림길이다 (예: {stray[:3]})")
    else:
        rep.good(f"마른 땅은 전부 등록된 것뿐 — 잔교·마당·우회로·집·등롱 ({len(deck)}칸). "
                 "나머지는 물이다 (★ 벽 없이 길이 하나다)")

    # 코드가 정말 이 규칙으로 짓는가 — isDeck 이 다섯 술어 + 끊긴 자리로 이뤄져야 한다
    body = body_of(code, r"private boolean isDeck\(int x, int z\)")
    if body is None:
        rep.bad("isDeck() 를 못 찾았다 — 마른 땅의 정의가 코드에 없다")
    else:
        if "inGap" not in body:
            rep.bad("isDeck() 가 끊긴 자리를 먼저 빼지 않는다 — "
                    "경공 관문의 마당이 구멍을 도로 메운다 (잔교가 안 끊긴다)")
        for need in ("onRoad", "onStation", "onBypass", "onHut", "onLampBracket"):
            if need not in body:
                rep.bad(f"isDeck() 가 {need} 를 안 본다 — 마른 땅의 정의가 config 와 다르다")
        if all(n in body for n in ("inGap", "onRoad", "onStation", "onBypass", "onHut")):
            rep.good("isDeck() = 끊긴 자리 제외 + 등록된 다섯 (config 와 같은 규칙으로 짓는다)")

    # ★★ 코드가 좌표를 **몰래 지어내지 않는가.**
    #    이 감사는 config 에서 나루의 몸을 다시 세워 BFS 를 돈다. 그러니 코드가 config 에 없는 마른 땅을
    #    한 칸이라도 만들면 **눈이 그것을 영영 못 본다** (자기 시험이 이 구멍을 잡아냈다).
    #    그래서 기하 술어에는 **박힌 숫자가 없어야 한다** — 전부 등록부에서 온 값이어야 한다.
    for fn, sig, allowed in (
            ("isDeck", r"private boolean isDeck\(int x, int z\)", {0, 1}),
            ("inGap", r"private boolean inGap\(int x, int z\)", {0, 1}),
            ("onRoad", r"private boolean onRoad\(int x, int z\)", {0, 1}),
            ("onStation", r"private boolean onStation\(int x, int z\)", {0, 1}),
            ("onBypass", r"private boolean onBypass\(int x, int z\)", {0, 1}),
            ("onHut", r"private boolean onHut\(int x, int z\)", {0, 1}),
            ("lampSide", r"private Integer lampSide\(int x\)", {0, 1, 2}),
    ):
        b = body_of(strip_comments(code), sig)
        if b is None:
            rep.bad(f"{fn}() 를 못 찾았다 — 기하 규칙이 코드에 없다")
            continue
        lits = {int(n) for n in re.findall(r"(?<![\w.])(-?\d+)(?![\w.])", b)}
        stray = sorted(lits - allowed)
        if stray:
            rep.bad(f"{fn}() 에 박힌 숫자가 있다: {stray} — 코드가 좌표/간격을 지어낸다 "
                    "(config 가 정본이어야 하고, 안 그러면 감사가 그 땅을 못 본다)")
        else:
            rep.good(f"{fn}() 에 박힌 숫자가 없다 — 전부 등록부에서 온다")


# ═══════════════════════════════════════════════════════════════════════════
#  ⑦ 흐름 — 한 번에 하나만 보이는가
# ═══════════════════════════════════════════════════════════════════════════

def audit_flow(rep: Report, ante: dict, code: str) -> None:
    rep.say()
    rep.say("  ⑦ 흐름 — 문지방에는 시험이 없다 (판은 안내판 · 문은 이름이 연다)")
    # 【묘비】 one_at_a_time·순차 공개(refreshPanels/show)·passed/currentStation — ★5차 개정으로
    #   과제와 함께 걷혔다. 「한 길」의 흐름은 판의 가림이 아니라 물이 만든다 (나머지가 전부 물이다).

    # ① 과제 기계의 잔재가 코드에 남아 있으면 그것이 위반이다 (사용자 지시의 눈)
    stripped = strip_comments(code)
    relics = [r for r in ("bump(", "creditCommand", "watchGestures", "watchArmed(",
                          "watchGyeonggong", "currentStation(", "applicable(", "flashCount(",
                          "Lesson ") if r in stripped]
    if relics:
        rep.bad(f"과제 기계의 잔재가 코드에 남아 있다: {relics} — 과제는 폐지됐다 (★5차 · "
                "나루는 시험하지 않는다)")
    else:
        rep.good("과제 기계의 잔재 없음 — 진척 장부·감지·순차 공개가 코드에서 걷혔다")

    # ② ★★ 글판이 안 보이는 것과 **문이 잠기는 것**은 다른 것이다. 종은 언제나 울려야 한다
    cross = body_of(code, r"public void cross\(Player player\)")
    if cross is None:
        rep.bad("cross() 를 못 찾았다")
    elif any(w in cross for w in ("currentStation", "refreshPanels", "passed(", "shownThrough",
                                  "progress")):
        rep.bad("cross() 가 과제 진척을 본다 — ★ 과제가 문을 잠근다. "
                "글판은 안내이지 자물쇠가 아니다 (과제 하나가 깨진 날 사람이 갇힌다)")
    else:
        rep.good("cross() 는 이름만 본다 — 판이 무슨 말을 하든 종은 울린다")


# ═══════════════════════════════════════════════════════════════════════════
#  ⑧ 빛 — 길을 만드는가 (야간 3축 + 균일 금지)
# ═══════════════════════════════════════════════════════════════════════════

def audit_light(rep: Report, ante: dict, code: str) -> None:
    rep.say()
    rep.say("  ⑧ 빛 — 길을 만드는가 (TownAudit 야간 3축과 같은 눈금 · 균일 금지)")
    li = ante.get("lighting") or {}

    # ★ 눈금을 손으로 쓰지 않는다 — TownAudit.java 의 상수에서 읽어 대조한다
    ta = source("TownAudit.java")
    truth = {}
    for name, key in (("DARK_MIN_PCT", "dark_min_pct"), ("DARK_MAX_PCT", "dark_max_pct"),
                      ("MAIN_DARK_MAX_PCT", "main_dark_max_pct"),
                      ("LAMP_DENSITY_MAX", "lamp_density_max_pct")):
        m = re.search(r"double\s+" + name + r"\s*=\s*([0-9.]+)", ta)
        if m:
            v = float(m.group(1))
            if name == "LAMP_DENSITY_MAX":
                v *= 100.0        # TownAudit 는 비율(0.06), config 는 퍼센트(6)
            truth[key] = v
    if not truth:
        rep.warn("TownAudit.java 에서 야간 3축 상수를 못 읽었다 — 눈금 대조를 못 했다")
    for key, want in truth.items():
        got = li.get(key)
        if got is None:
            rep.bad(f"lighting.{key} 가 없다 (TownAudit 는 {want} 라고 적어 뒀다)")
        elif abs(float(got) - want) > 1e-6:
            rep.bad(f"lighting.{key} = {got} ≠ TownAudit 의 {want} — "
                    "두 등록부가 서로 다른 밤을 말한다")
        else:
            rep.good(f"lighting.{key} = {got} (TownAudit 와 같은 눈금)")

    g = Geo(ante)
    srcs = g.sources()
    walk = g.walkable()
    if not walk or not srcs:
        rep.bad("빛을 잴 표본이 없다")
        return

    # ① 주 동선(잔교·마당) — ★명계 개정 (2026-07-24 사용자 확정): **구간을 갈랐다.**
    #   이승(부두 관문부터 동쪽)은 TownAudit 눈금 그대로 밝아야 하고,
    #   저승(그 서쪽·넋등)은 **어둑함이 정본**이다 — 창(soul.dark_pct) 밖이면 위반.
    b = g.soul_boundary()
    east = [(x, z) for (x, z) in walk if x >= b]
    west = [(x, z) for (x, z) in walk if x < b]
    if not east or not west:
        rep.bad("저승/이승 구간이 비었다 — soul.until_station 이 등록부의 관문을 가리키는가")
        return
    east_lv = [g.light_at(x, z, srcs) for (x, z) in east]
    main_dark = 100.0 * sum(1 for v in east_lv if v < 7) / len(east_lv)
    lim = float(li.get("main_dark_max_pct", 15))
    if main_dark > lim:
        rep.bad(f"주 동선 암흑(이승 구간) {main_dark:.1f}% > {lim:.0f}% — 걸어야 할 길이 어둡다 "
                "(빛이 길을 안 가리킨다)")
    else:
        rep.good(f"주 동선 암흑(이승 구간) {main_dark:.1f}% ≤ {lim:.0f}% — 부두는 밝다")

    # ①-b 저승 구간 — 이승처럼 밝아도, 넋등 없이 전맹이어도 위반이다
    west_lv = [g.light_at(x, z, srcs) for (x, z) in west]
    west_dark = 100.0 * sum(1 for v in west_lv if v < 7) / len(west_lv)
    lo_s, hi_s = float(g.soul_dark[0]), float(g.soul_dark[1])
    if west_dark < lo_s:
        rep.bad(f"저승 구간이 이승처럼 밝다 — 암흑 {west_dark:.1f}% < {lo_s:.0f}% "
                "(넋등의 어둑함이 정본이다 · 명계 개정)")
    elif west_dark > hi_s:
        rep.bad(f"저승 구간이 전맹이다 — 암흑 {west_dark:.1f}% > {hi_s:.0f}% "
                "(넋등의 리듬마저 없다 — 어둑함과 캄캄함은 다르다)")
    else:
        rep.good(f"저승 구간 암흑 {west_dark:.1f}% ∈ [{lo_s:.0f}, {hi_s:.0f}] — 넋등이 어스름을 지킨다")

    # ② 어둠의 하한 — ★ 어두운 곳이 **없으면 그것도 실패다** (등롱 도배) · 석등도 합산한다
    allc = [(x, z) for x in range(g.mx[0], g.mx[1] + 1) for z in range(g.mz[0], g.mz[1] + 1)]
    all_srcs = srcs + g.relic_sources()
    all_lv = [g.light_at(x, z, all_srcs) for (x, z) in allc]
    all_dark = 100.0 * sum(1 for v in all_lv if v < 7) / len(all_lv)
    lo = float(li.get("dark_min_pct", 12))
    if all_dark < lo:
        rep.bad(f"암흑 {all_dark:.1f}% < {lo:.0f}% — 등롱이 습지를 도배했다 (밤이 밤이 아니다). "
                "어두운 곳이 있어야 밝은 곳이 길로 읽힌다")
    else:
        rep.good(f"습지 암흑 {all_dark:.1f}% ≥ {lo:.0f}% — 갈대밭은 어둡다 (그래서 잔교가 길로 읽힌다)")

    # ③ 광원 밀도 — 등불 도배 금지
    dens = 100.0 * len(srcs) / len(walk)
    dlim = float(li.get("lamp_density_max_pct", 6))
    if dens > dlim:
        rep.bad(f"광원 밀도 {dens:.1f}% > {dlim:.0f}% — 등롱이 다닥다닥 붙었다")
    else:
        rep.good(f"광원 밀도 {dens:.1f}% ≤ {dlim:.0f}% (광원 {len(srcs)}개 / 마른 땅 {len(walk)}칸) "
                 "— 등롱이 리듬이다")

    # ④ ★ 균일 금지 — 사용자가 정확히 이것을 말했다: "조명도 너무 균일"
    line = [g.light_at(x, g.rz, srcs) for x in range(g.x0, g.x1 + 1) if (x, g.rz) in walk]
    span = max(line) - min(line) if line else 0
    need = int(li.get("main_light_span_min", 3))
    if span < need:
        rep.bad(f"길 위 광량이 {min(line)}~{max(line)} (편차 {span}) < {need} — "
                "조명이 **균일하다**. 격자는 아무것도 안 가리킨다")
    else:
        rep.good(f"길 위 광량 {min(line)}~{max(line)} (편차 {span} ≥ {need}) — "
                 "밝은 웅덩이와 어둑한 구간이 번갈아 온다 (등롱이 화살표다)")

    # 격자 금지 — 코드가 등롱을 격자로 깔면 안 된다 (1차판이 6칸 격자였다)
    body = body_of(code, r"private Integer lampSide\(int x\)")
    if body is None:
        rep.bad("lampSide() 가 없다 — 등롱 배치 규칙이 코드에 없다")
    elif "postEvery" not in body:
        rep.bad("lampSide() 가 config 의 post_every 를 안 쓴다 — 코드가 간격을 지어낸다")
    else:
        rep.good("등롱 간격은 config(post_every) 가 정한다 (코드가 안 지어낸다)")
    if re.search(r"lantern_every|floorMod\(z - yardZ", code):
        rep.bad("아직 격자 등롱(lantern_every)을 깐다 — 격자는 아무것도 안 가리킨다")


# ═══════════════════════════════════════════════════════════════════════════
#  ⑧-2 ★삼도천 화폭 — 명계의 근경·중경·원경 (2026-07-24 사용자 확정 · tutorial_rooting.md §7)
# ═══════════════════════════════════════════════════════════════════════════

def _knot(gx: int, gz: int) -> float:
    return ((gx * 73856093 + gz * 19349663) % 1024) / 1024.0


def _grain(x: int, z: int) -> float:
    """Antechamber.grain 의 거울 — 8칸 격자점 해시의 쌍선형 보간 (결정론)"""
    g = 8
    gx, gz = x // g, z // g
    fx, fz = (x - gx * g) / g, (z - gz * g) / g
    a = _knot(gx, gz) + (_knot(gx + 1, gz) - _knot(gx, gz)) * fx
    b = _knot(gx, gz + 1) + (_knot(gx + 1, gz + 1) - _knot(gx, gz + 1)) * fx
    return a + (b - a) * fz


REED_THRESHOLD = 0.66   # 갈대 띠의 문턱 (Antechamber plan ① — n > 0.66 이 갈대다)
DAWN_WINDOW = (22331, 23800)   # 새벽 창 — 하늘이 밝기 시작해 동트기 직전까지 (마크 시각)


def audit_canvas(rep: Report, ante: dict, code: str) -> None:
    rep.say()
    rep.say("  ⑧-2 삼도천 화폭 — 서=저승 청백 · 동=이승 주홍 (의미의 축이 서 있는가)")
    cv = ante.get("canvas") or {}
    if not cv:
        rep.bad("canvas 절이 없다 — 화폭이 등록부에 없다 (tutorial_rooting.md §7 이 정본이다)")
        return
    g = Geo(ante)

    # ── 넋등 — 저승 구간의 등롱은 넋등이어야 한다 (경계는 등록부의 관문)
    ids = {s["id"] for s in (ante.get("stations") or [])}
    if g.soul_until not in ids:
        rep.bad(f"soul.until_station = {g.soul_until!r} — 등록부가 모르는 관문이다 "
                "(경계를 지어내면 저승 구간이 통째로 사라진다)")
    else:
        rep.good(f"저승 구간 경계 = 관문 「{g.soul_until}」 서쪽 끝 x{g.soul_boundary()} (등록부 유도)")
    if re.search(r"x\s*<\s*soulBoundary\(\)\s*\?\s*Material\.SOUL_LANTERN\s*:\s*Material\.LANTERN",
                 code):
        rep.good("등롱 재질이 구간을 따른다 — 서쪽 넋등 · 부두 등롱 (의미의 축)")
    else:
        rep.bad("넋등이 없다 — 저승 구간 등롱이 전부 따뜻하다 (의미의 축이 죽었다)")
    # 넋등의 리듬 — 어둑함(dark_pct 창)만으로는 못 잡는다: 동쪽 불빛이 서쪽으로 번져
    # 넋등이 사라져도 창 안에 남을 수 있다. 리듬은 등록부에서 직접 잰다.
    if g.soul_every > g.post_every:
        rep.bad(f"넋등 간격 {g.soul_every} > 등롱 간격 {g.post_every} — 저승의 넋등은 이승의 "
                "등롱보다 촘촘해야 한다 (리듬이 풀리면 어둑함이 전맹이 된다)")
    else:
        rep.good(f"넋등 간격 {g.soul_every} ≤ 등롱 간격 {g.post_every} — 강가에 늘어선 넋등의 리듬")

    # ── 시각 — 새벽녘 고정 (동쪽 하늘만 주홍 — 축과 하늘이 같은 말을 해야 한다)
    t = int(cv.get("fixed_time", -1))
    if DAWN_WINDOW[0] <= t <= DAWN_WINDOW[1]:
        rep.good(f"fixed_time {t} — 새벽 창 {DAWN_WINDOW} 안 (동쪽만 주홍으로 물든다)")
    else:
        rep.bad(f"fixed_time {t} — 새벽 창 {DAWN_WINDOW} 밖이다 (하늘이 축과 딴말을 한다: "
                "황혼은 서쪽이 주홍이다)")
    if "setTime(fixedTime)" not in code or re.search(r"setTime\(\s*\d", code):
        rep.bad("시각을 코드가 지어낸다 — configure() 는 canvas.fixed_time 을 읽어야 한다")
    else:
        rep.good("시각은 등록부(canvas.fixed_time)가 정한다")

    # ── 고사목 군락 — 남쪽에만 (비대칭) · 잔교/집을 안 찌른다 · 비어 있지 않다
    pg = cv.get("pale_grove") or {}
    z_from = pg.get("z_from", 6)
    spacing = max(2, pg.get("spacing", 4))
    threshold = pg.get("threshold", 0.55)
    shift = (pg.get("grain_shift") or [120, 120])[:2]
    hmin, hmax = (pg.get("height") or [3, 6])[:2]
    if z_from <= g.post_z:
        rep.bad(f"pale_grove.z_from = {z_from} — 잔교 곁(±{g.post_z})·북쪽을 침범한다 "
                "(고사목은 남쪽 물가에만 — 비대칭이 화폭의 계약이다)")
    trees = []
    for x in range(g.mx[0], g.mx[1] + 1):
        for z in range(max(z_from, g.mz[0]), g.mz[1] + 1):
            if (x % spacing != 0 or z % spacing != 0
                    or _grain(x + shift[0], z + shift[1]) < threshold):
                continue
            tx = x + (x * 31 + z * 17) % 3 - 1
            tz = z + (x * 13 + z * 41) % 3 - 1
            if (tz < z_from or tz > g.mz[1] or tx < g.mx[0] or tx > g.mx[1]
                    or g.is_deck(tx, tz)
                    or (g.hx[0] - g.eave - 1 <= tx <= g.hx[1] + g.eave + 1
                        and g.hz[0] - g.eave - 1 <= tz <= g.hz[1] + g.eave + 1)):
                continue
            trees.append((tx, tz))
    if not trees:
        rep.bad("고사목 군락이 비었다 — 화폭에 중경이 없다 (threshold 가 너무 높은가)")
    elif any(tz <= 0 for _, tz in trees):
        rep.bad("고사목이 북쪽(z ≤ 0)에 선다 — 비대칭(남쪽만)이 깨졌다")
    else:
        west = sum(1 for tx, _ in trees if tx < g.soul_boundary())
        rep.good(f"고사목 {len(trees)}그루 — 전부 남쪽 물가 · 저승 구간(서) {west}그루 "
                 f"(키 {hmin}~{hmax} · 군락은 grain_shift 위상이 가른다)")
    if "PALE_OAK_LOG" not in code:
        rep.bad("고사목이 말뿐이다 — 코드에 PALE_OAK_LOG 조성이 없다")

    # ── 진흙 둔덕 — 갈대 띠(> 0.66)와 불가침 · 수련잎 띠(< 0.30)와도 안 겹친다
    band = (cv.get("mud_band") or [0.475, 0.525])[:2]
    if band[1] >= REED_THRESHOLD:
        rep.bad(f"진흙 둔덕 띠 {band} 가 갈대 띠(> {REED_THRESHOLD})를 침범한다 — "
                "두 띠가 같은 칸을 두고 다툰다")
    elif band[0] < 0.30:
        rep.warn(f"진흙 둔덕 띠 {band} 가 수련잎 띠(< 0.30)와 겹친다 — 둔덕 위 수련잎은 어색하다")
    else:
        rep.good(f"진흙 둔덕 띠 {band} — 갈대·수련잎 띠와 불가침")
    if "Material.MUD" not in code:
        rep.bad("진흙 둔덕이 말뿐이다 — 코드에 MUD 조성이 없다")

    # ── 옛 잔교 — 북쪽 물가 · 습지 안 · 석등은 그 선 위에만
    rl = cv.get("relics") or {}
    rx = (rl.get("x") or [-4, 18])[:2]
    rz = rl.get("z", -9)
    if rz >= -(g.post_z + 1):
        rep.bad(f"relics.z = {rz} — 옛 잔교가 잔교 곁까지 올라왔다 (북쪽 물가여야 한다)")
    elif not (g.mz[0] <= rz and g.mx[0] <= rx[0] <= rx[1] <= g.mx[1]):
        rep.bad(f"옛 잔교(x {rx} · z {rz})가 습지 밖이다 — 장벽 너머 잔해는 아무도 못 본다")
    else:
        rep.good(f"옛 잔교 — 북쪽 물가 z{rz} · x {rx[0]}~{rx[1]} (끊긴 말뚝 · 건너간 혼들의 흔적)")
    for lx in (rl.get("stone_lanterns") or []):
        if not (rx[0] <= lx <= rx[1]):
            rep.bad(f"석등 x{lx} 가 옛 잔교 선(x {rx[0]}~{rx[1]})을 벗어났다 — "
                    "석등은 그 잔교의 유물이다")
    if "COBBLED_DEEPSLATE_WALL" not in code:
        rep.bad("석등이 말뿐이다 — 코드에 석등 조성이 없다")

    # ── 원경 — **동쪽에만** (이승의 불빛 한 점 + 실루엣). 서쪽 저승엔 아무것도 없다
    hor = cv.get("horizon") or {}
    hl = (hor.get("light") or [58, 0])[:2]
    hrx = (hor.get("ridge_x") or [56, 68])[:2]
    if hl[0] <= g.mx[1]:
        rep.bad(f"이승의 불빛 x{hl[0]} — 동쪽 원경(습지 동쪽 끝 {g.mx[1]} 너머)이 아니다. "
                "원경은 동쪽에만 — 서쪽은 세계의 없음이다")
    elif hrx[0] <= g.mx[1]:
        rep.bad(f"실루엣 둔덕 x{hrx} — 습지를 침범한다 (원경은 장벽 밖, 눈으로만 가는 땅)")
    else:
        rep.good(f"원경 — 동쪽 x{hrx[0]}~{hrx[1]} 실루엣 + 불빛 한 점 x{hl[0]} (삿대가 향하는 곳)")
    if "BLACKSTONE" not in code:
        rep.bad("실루엣이 말뿐이다 — 코드에 원경 둔덕 조성이 없다")

    # ── 수련잎 최소 — 성김은 등록부(lily_hash)가 정한다
    if "lilyHash" not in code:
        rep.bad("수련잎 성김을 코드가 지어낸다 — marsh.lily_hash 를 읽어야 한다")
    else:
        rep.good(f"수련잎 성김 = marsh.lily_hash {(ante.get('marsh') or {}).get('lily_hash')} "
                 "(먹빛 강에 수련잎은 최소다)")


# ═══════════════════════════════════════════════════════════════════════════
#  ⑧-3 ★기억의 회랑 — 강을 건너는 동안이 곧 서장인가 (B-179 · seojang_presentation.md §0)
# ═══════════════════════════════════════════════════════════════════════════

def audit_voyage(rep: Report, ante: dict, code: str) -> None:
    rep.say()
    rep.say("  ⑧-3 기억의 회랑 — 한 배 위의 서장인가 (B-179 ★5차 — 서장 월드의 나룻배)")
    vo = ante.get("voyage") or {}
    if not vo:
        rep.bad("voyage 절이 없다 — 회랑이 등록부에 없다 (seojang_presentation.md §0 이 정본이다)")
        return

    # ① ★서장 월드 (5차 — 사용자 확정 "별도의 서장 월드"): 이름이 있고, 나루와 다른 세계고,
    #   칠흑의 밤이다 (13000~23000 창 — 낮이면 달빛 컨셉이 죽는다)
    wo = vo.get("world") or {}
    wname = str(wo.get("name") or "").strip()
    naru = str(ante.get("world") or "honcheon_ipdo")
    ft = wo.get("fixed_time")
    if not wname or wname == naru:
        rep.bad(f"서장 월드 이름이 없다/나루와 같다 ({wname!r}) — 별도 월드가 5차의 뼈대다")
    else:
        rep.good(f"서장 월드 = {wname} (나루 {naru} 와 딴 세계)")
    if not isinstance(ft, int) or not (13000 <= ft <= 23000):
        rep.bad(f"서장 월드가 밤이 아니다 — fixed_time {ft!r} (칠흑+달빛 확정 · 13000~23000 창)")
    else:
        rep.good(f"칠흑 + 달빛 — fixed_time {ft}")

    # ② ★나룻배 등록부 — 중선 (전장 > 전폭 · 무대·패가 설 안칸이 있어야 한다)
    ba = vo.get("barge") or {}
    hl, hw = ba.get("half_len"), ba.get("half_w")
    if not isinstance(hl, int) or hl < 4 or not isinstance(hw, int) or hw < 2 or hl <= hw:
        rep.bad(f"나룻배 치수가 없다/눕는다 — barge.half_len(≥4·전폭보다 길게)·half_w(≥2) "
                f"(지금 {hl!r}×{hw!r}) — 배 없는 바다에 사람을 내려놓게 된다")
    else:
        rep.good(f"나룻배 — 전장 {2 * hl + 1} × 전폭 {2 * hw + 1} (중선)")

    # ③ ★묘비 부활 감시 (5차 계율 — "배는 하나뿐"): 3~4차의 나루 물길 등록부·조성이
    #   되살아나면 배가 여러 척이 된다 — 부활 자체가 위반이다
    dead = [k for k in ("stations_x", "shore_x", "frame_z", "moored") if k in vo]
    plan_src = strip_comments(code)
    if dead or "voyage.stationsX()" in plan_src or "voyage.mooredWest()" in plan_src:
        rep.bad(f"묘비가 부활했다 — voyage {dead} / plan 의 정거장·정박 갑판: 3~4차 나루 물길의 "
                "것이다 (5차: 배는 서장 월드에 하나뿐 — 1장 배에서 2장 배가 보이면 위반)")
    else:
        rep.good("묘비는 잠들어 있다 — 나루 물길 등록부·조성 부활 없음 (배는 하나뿐)")

    # ④ 도하 등록부 — 가짜 항해 (4차 승계: 흐름·노 박자·눈깜빡임) + 침묵 금지
    tr = vo.get("transit") or {}
    if not str(tr.get("line") or "").strip():
        rep.bad("도하가 침묵한다 — transit.line 이 비었다 (물 위에서 아무도 말하지 않는다)")
    else:
        rep.good("도하의 한 마디가 있다 (transit.line)")
    flow = tr.get("flow_ticks")
    blink = tr.get("blink_ticks")
    rowp = tr.get("row_period")
    if not isinstance(flow, int) or flow < 40 \
            or not isinstance(blink, int) or not (4 <= blink <= 40):
        rep.bad("도하에 항해의 몸이 없다 — flow_ticks(≥40)·blink_ticks(4~40) 등록부가 없거나 "
                "창 밖 (4차: 단발 암전은 도하가 아니다 — 세계가 흘러야 한다)")
    else:
        rep.good(f"도하 = 흐름 {flow}틱 + 눈깜빡임 {blink}틱 — 세계가 흐른다")
    if not isinstance(rowp, int) or not (6 <= rowp <= (flow if isinstance(flow, int) else 200)):
        rep.bad("노 박자가 없다 — row_period 가 없거나 흐름 밖이다 (노 없는 배는 떠내려가는 "
                "널빤지다)")
    else:
        rep.good(f"노 박자 {rowp}틱 — 좌우 번갈아 젓는다")
    # ★안개 링 (5차 — 장막의 상속자): 빈 수평선을 안개가 감싼다
    fog = vo.get("fog") or {}
    if not isinstance(fog.get("radius"), int) or fog.get("radius", 0) < 6 \
            or not isinstance(fog.get("height"), int) or fog.get("height", 0) < 2 \
            or not isinstance(fog.get("density"), int) or fog.get("density", 0) < 1:
        rep.bad("안개 링 등록부(fog: radius≥6·height≥2·density≥1)가 없다 — "
                "빈 수평선이 세계를 좁힌다 (가림도 등록부다)")
    else:
        rep.good(f"안개 링 — 반경 {fog['radius']} · 키 {fog['height']} · 짙기 {fog['density']}")

    # ⑤ 배선 — 승선 세 길 · 배가 선다 · 배 위의 장 · 기슭의 문 · 침묵 금지
    n_embark = strip_comments(code).count("voyage.embark(player)")
    if n_embark < 4:
        rep.bad(f"승선 문이 {n_embark}곳뿐이다 — 접합 직후(watchGate)·명단 지각(시계)·종(cross)·"
                "서장 월드 재접속(3방어) 네 길이 모두 배를 띄워야 한다 "
                "(하나라도 빠지면 그 길은 부두 대기 또는 방치다)")
    else:
        rep.good(f"승선 문 {n_embark}곳 — 어느 길로 와도 배가 뜬다")
    sbk = source("SeojangBook.java")
    if "voyage().defer(player, scene)" not in sbk:
        rep.bad("책이 배를 모른다 — SeojangBook.deliver 가 항해에 묻지 않는다 "
                "(책이 아무 데서나 열린다)")
    else:
        rep.good("항해 중의 책은 배 위에서 열린다 (deliver → Voyage.defer)")
    voy = source("Voyage.java")
    if not voy:
        rep.bad("Voyage.java 가 없다 — 도하 기계가 말뿐이다")
        return
    if "WorldCreator(seaName)" not in voy or "minecraft:water" not in voy:
        rep.bad("서장 월드가 말뿐이다 — Voyage 가 물의 세계를 열지 않는다 (FLAT + 물 층)")
    else:
        rep.good("서장 월드는 Voyage 가 연다 (FLAT + 물 층 · 못 열면 null = 안 가둔다)")
    if "buildBarge(sea);" not in voy:
        rep.bad("승선이 배를 안 세운다 — 배 없는 바다에 사람을 내려놓는다 (embark 의 멱등 조성)")
    else:
        rep.good("승선이 배를 세운다 (buildBarge — 멱등)")
    if "voyage.buildBarge(sea);" not in code:
        rep.bad("기동이 배를 안 세운다 — 서장 월드 미리 열기(1차 방어)에 배가 빠졌다")
    else:
        rep.good("기동 때 바다가 열리고 배가 선다 (1차 방어)")
    if not re.search(r"seojangHolds\(body\)\)\s*\{\s*\n\s*transit\(player, r, -1\)", voy) \
            or "ante.depart(p, List.of());" not in voy:
        rep.bad("기슭의 문이 없다 — 명단이 끝나도(출도·봇 죽음) 마지막 도하가 출도로 못 잇는다 "
                "(영원한 항해 = 갇힘)")
    else:
        rep.good("명단이 끝나면 마지막 도하 — 눈을 뜨면 강호다 (갇힘 금지)")
    if "PotionEffectType.DARKNESS" not in voy or "p.teleport(bargeAnchor(" not in voy:
        rep.bad("도하가 연출 없는 순간이동이다 — 눈깜빡임·닻 재정렬 없이 장만 바뀐다 (도하는 의식이다)")
    else:
        rep.good("도하 = 눈깜빡임 + 같은 배의 닻에서 눈뜸")
    if "startFlow(player, r);" not in voy or "rowPeriod" not in voy \
            or "Particle.CLOUD" not in voy:
        rep.bad("가짜 항해가 말뿐이다 — startFlow(물살·노 박자·마중 안개)가 도하를 안 젓는다 "
                "(4차: 암전 사이에 항해의 몸이 있어야 한다)")
    else:
        rep.good("가짜 항해 — 물살이 흐르고 노가 박자를 젓는다 (startFlow)")
    if "fogRing(player);" not in voy:
        rep.bad("안개 링이 말뿐이다 — 시계(tick)가 링을 안 피운다 (수평선이 훤히 빈다)")
    else:
        rep.good("안개 링은 시계가 피운다 (fogRing — 본인에게만)")
    if ("return (Antechamber.isAntechamber(player.getWorld()) || isSea(player.getWorld()))"
            not in voy):
        rep.bad("승선 전의 책을 안 붙든다 — 다리(2초)가 승선(5틱)을 이기면 책이 **부두에서** "
                "열린다 (실기동 2026-07-25: \"이으니까 바로 책을 받고 읽기 시작\")")
    else:
        rep.good("승선 전의 책은 승선을 기다린다 (접합 직후의 경주 봉인 — 나루·바다 공통)")
    if "open(p, still, scene);" not in voy:
        rep.bad("도하 뒤 장이 안 열린다 — 눈을 떠도 무대가 침묵한다")
    else:
        rep.good("눈을 뜨면 장이 열린다 (open — 무대 또는 강등 책)")
    dp = body_of(code, r"void depart\(Player player, List<String> extra\)")
    if dp is None or "voyage.disembark" not in dp:
        rep.bad("출도가 배를 안 걷는다 — 명단이 배 위에 쌓인다")
    else:
        rep.good("출도는 배를 걷는다 (항해는 메모리뿐)")
    if "Voyage.isSea(player.getWorld())" not in code:
        rep.bad("서장 월드의 재접속을 아무도 안 집는다 — 배 위에서 나간 몸이 돌아와도 명단 밖이다 "
                "(3방어의 구멍)")
    else:
        rep.good("서장 월드 재접속 = 그 자리 재승선 (3방어)")
    # ★튜토리얼 침묵 (실기동 2026-07-25 "배 위에서 우클릭 하니까 과제 » … 문구") — 뿌리내림은
    #   본토의 과정이다: 서장의 몸에 과제가 말을 걸면 위반이다
    tut = source("TutorialGuide.java")
    if tut.count("silenced(player)") < 4 or "boolean silenced(Player player)" not in tut:
        rep.bad("서장의 몸에 과제가 말을 건다 — TutorialGuide 침묵 게이트(silenced)가 "
                "훅(bump·gesture·mirror)을 안 지킨다 (배 위의 우클릭이 태세 가르침으로 세인다)")
    else:
        rep.good("서장의 몸에 뿌리내림은 침묵한다 (silenced — bump·gesture·mirror)")
    if "tutorial.silenced(player)" not in source("HoncheonMvt.java"):
        rep.bad("항해 중에도 뿌리내림 트래커가 뜬다 — 사이드바가 침묵 게이트를 안 지난다")
    else:
        rep.good("항해 중 사이드바 트래커도 침묵한다")
    # ★선택 패의 세계 (실기동 2026-07-25 "2장이 다시 시작되지도 않아" — 나루-검사만 있어서
    #   서장 월드의 몸에게 패가 영영 안 걸렸다: 선택 불가 = 갇힘)
    sjs = source("SeojangStage.java")
    if "Voyage.isSea(player.getWorld())" not in sjs:
        rep.bad("패가 서장 월드를 모른다 — offerChoices 예약이 나루-검사뿐이다 (서장 월드의 "
                "몸에게 패가 영영 안 걸린다 · 선택 불가 = 갇힘)")
    else:
        rep.good("패는 서장 월드의 배 위에도 걸린다 (offerChoices — 나루·바다 공통)")
    # ★사공 (실기동 2026-07-25 "배에 뱃사공도 없어" — 2차 확정의 승계)
    fm = vo.get("ferryman") or {}
    if not str(fm.get("name") or "").strip():
        rep.bad("사공 등록부(ferryman.name)가 없다 — 사공 없는 배는 어색하다 (실기동이 말했다)")
    elif "ensureFerryman(sea);" not in voy:
        rep.bad("사공이 말뿐이다 — 승선이 사공을 안 세운다 (ensureFerryman)")
    else:
        rep.good(f"사공이 고물에 탄다 — {fm.get('name')} (한 배에 한 사공)")
    # ★물에 빠진 몸 (사용자 제안 2026-07-25 "뛰어내릴 경우 올라올 방법이 없음"):
    #   1차 손 = 뱃전 사다리 (조성) · 안전망 = 사공의 삿대 (깊이·표류 회수)
    if "minecraft:ladder[facing=" not in voy:
        rep.bad("사다리가 없다 — 물에 빠진 몸이 배로 못 오른다 (뱃전 허리 양옆)")
    else:
        rep.good("뱃전 사다리 — 물에서 붙잡고 오른다")
    rc = vo.get("rescue") or {}
    if "rescueIfAdrift(player);" not in voy or not str(rc.get("line") or "").strip():
        rep.bad("사공의 삿대가 없다 — 가라앉거나 떠내려간 몸이 밤바다에 남는다 "
                "(rescue 등록부 + rescueIfAdrift · 밤바다는 벽이 아니라 되돌림이다)")
    else:
        rep.good(f"사공의 삿대 — 깊이·{rc.get('beyond', '?')}칸 밖은 갑판으로 되돌린다")
    # ★서사 글판 + 명패형 패 (사용자 확정 2026-07-25 "선택지만 뜨니까 무슨 내용인지 모르겠음" ·
    #   "명패처럼 디자인")
    stg = load_yaml("seojang_stage.yml").get("stage") or {}
    # ★서사 = 한월풍 대화 채팅 (2차 빨간펜 "서사 글판이 너무 난잡" — 전문 글판은 묘비):
    #   장식 틀 + 문장 단위 타자기. 패는 마지막 문장 뒤 (읽기 전에 안 걸린다)
    dlg = stg.get("dialogue") or {}
    if not dlg or dlg.get("enabled") is False \
            or "dlgEnabled && scene.narration()" not in sjs \
            or "sentenceBeats(" not in sjs:
        rep.bad("서사가 안 흐른다 — dialogue 등록부/타자기 배선(sentenceBeats)이 없다 "
                "(무엇에서 고르는지 패만 안다)")
    else:
        rep.good(f"서사 = 대화 타자기 — 문장마다 {dlg.get('sentence_interval_ticks', '?')}틱, "
                 f"최대 {dlg.get('max_beats', '?')}숨")
    # ★기억첩 글리프 배선 (SJ-002 의 미결 — "리소스팩·UI 개선으로 확 와닿게"):
    #   붓선(E0B0)이 틀, 빈 인장(E0B3)이 패, 찍힌 인장(E0B2)이 확정 — 등록된 용도 그대로
    la_stg = stg.get("lanterns") or {}
    if '\ue0b0' not in str(dlg.get("head_format") or "") \
            or '\ue0b3' not in str(la_stg.get("label_format") or "") \
            or '\ue0b2' not in str(la_stg.get("pick_line") or ""):
        rep.bad("기억첩 글리프가 안 실렸다 — 붓선(E0B0 틀)·빈 인장(E0B3 패)·찍힌 인장"
                "(E0B2 확정)이 등록 용도대로 배선돼야 한다 (resourcepack_design.yml E0B0_E0BF)")
    else:
        rep.good("기억첩 글리프 배선 — 붓선 틀 · 빈 인장 패 · 찍힌 인장 확정 (SJ-002 완결)")
    # ★세로 목록 (실기동 스샷 2026-07-25 "명패가 없고" — 긴 문장 라벨의 가로 나열은 겹친다)
    if not isinstance(la_stg.get("row_gap"), (int, float)) \
            or "lanternRowGap" not in sjs or "lanternSpread" in sjs:
        rep.bad("명패가 세로 열이 아니다 — row_gap 등록부/lanternRowGap 배선이 없거나 "
                "가로 나열(spread)이 부활했다 (문장 라벨 셋이 한 줄로 뭉개진다)")
    else:
        rep.good(f"명패 세로 열 — 줄 간격 {la_stg.get('row_gap')} (첫 선택이 맨 위)")
    # ★붓의 로마자 안전망 (실기동 2026-07-25 「길을 건넌 days」 — 프롬프트만으로는 로컬 붓이
    #   가끔 어긴다): 재집필 1회 + 마지막 세척. 봇의 것이라 여기서 직접 읽는다
    lr = ""
    lr_path = os.path.join(ROOT, "server-bot", "src", "main", "java", "com", "honcheon",
                           "bot", "LlmRenderer.java")
    if os.path.isfile(lr_path):
        with open(lr_path, encoding="utf-8") as fh:
            lr = fh.read()
    if "|| !hasLatin(text)) {" not in lr or "재집필" not in lr:
        rep.bad("붓의 로마자 안전망이 없다 — 서사에 영어가 섞여도 아무도 안 잡는다 "
                "(hasLatin 재집필 1회 + 세척 · LlmRenderer.render)")
    else:
        rep.good("붓의 로마자 안전망 — 감지 → 재집필 1회 → 세척 (규칙 5의 눈)")
    # ★갈림길을 붓에 싣는가 (실기동 2026-07-25 "3장 내용과 선택지가 연결 안 됨" — 붓이
    #   무슨 갈림인지 모르면 제 갈림을 지어낸다: 전문과 패가 딴 장을 산다)
    glb_path = os.path.join(ROOT, "server-bot", "src", "main", "java", "com", "honcheon",
                            "bot", "GameListener.java")
    glb = ""
    if os.path.isfile(glb_path):
        with open(glb_path, encoding="utf-8") as fh:
            glb = fh.read()
    if "이 장의 끝에서 플레이어가 고를 갈림길" not in glb \
            or "sceneFacts(ch, title, prevTier, base, rank, hState, hRegion, forks)" not in glb:
        rep.bad("붓이 갈림길을 모른다 — sceneFacts 에 선택지가 안 실린다 "
                "(서사가 제 갈림을 지어내 등록부의 패와 딴 장을 산다)")
    else:
        rep.good("붓은 갈림길을 안다 — 서사가 등록부의 세 길 위에서 멈춘다")
    # ★내린 뒤의 세 손 (실기동 2026-07-25 "뭘 해야할지 모르겠어 · 리스폰이 이상한 곳"):
    #   첫걸음 안내 · 리스폰=내리는 자리 · 비무 상대는 설 자리를 재고 선다
    tut_cfg = load_yaml("tutorial.yml").get("tutorial") or {}
    if not tut_cfg.get("arrival_lines") or "tutorial().arrivalHint(player);" not in code:
        rep.bad("첫걸음 안내가 없다 — 내린 몸이 무엇을 할지 모른다 (arrival_lines 등록부 + "
                "depart 배선 · 첫 정거장 전의 몸에게 한 번)")
    else:
        rep.good("첫걸음 안내 — 내린 자리에서 트래커와 섭구를 말해 준다")
    if "Location down = destination(event.getPlayer());" not in code:
        rep.bad("리스폰이 아무도 고르지 않은 자리로 간다 — 건넌 몸의 리스폰도 내리는 자리"
                "(destination 한 벌)를 써야 한다")
    else:
        rep.good("리스폰 = 내리는 자리 (집안 앵커·Standing 한 벌)")
    hg = source("HuntingGrounds.java")
    if "Standing.landing(at, 8)" not in body_of(hg, r"private void partnerUpkeep\(\)"):
        rep.bad("비무 상대가 설 자리를 안 재고 선다 — 벽 속 소환 = 질식 루프 "
                "(2026-07-25: 곽진이 10초마다 죽었다)")
    else:
        rep.good("비무 상대는 설 자리를 재고 선다 (Standing — 못 서면 로그가 말한다)")
    # ★서장의 몸에는 강호의 하루가 흐르지 않는다 (실기동 2026-07-25 — 항해 무대에
    #   「수련 N일치가 흩어졌다」): 일일 정산은 내린 뒤로 미룬다 (장부 손실 0)
    skl = source("SkillListener.java")
    st_body = body_of(skl, r"private void settleTraining\(Player player, SkillEngine.State state\)")
    if st_body is None or "Voyage.isSea(player.getWorld())" not in st_body:
        rep.bad("수련 정산이 서장의 몸에 말을 건다 — settleTraining 이 나루·서장 월드·명단을 "
                "안 본다 (무대 한가운데 「흩어졌다」가 찍힌다)")
    else:
        rep.good("수련 정산은 내린 뒤에 온다 (서장의 몸에는 강호의 하루가 안 흐른다)")
    la = stg.get("lanterns") or {}
    if "{label}" not in str(la.get("label_format") or "") \
            or "Material.DARK_OAK_PLANKS" in sjs:
        rep.bad("패가 명패형이 아니다 — label_format({label} 문법) 또는 판목 몸의 부활 "
                "(명패형 + 먹 테 · 판목 BlockDisplay 는 묘비다)")
    else:
        rep.good("패 = 명패형 + 먹 테 (판목 묘비는 잠들어 있다)")

    # 【묘비】 옛 ⑧-3 의 눈들 — 물길 기하(정거장 오름차순·장벽 밖)·기슭=이승의 불빛 대조·
    #   정거장 수=장면 수·정박 갑판(문설주 안)·조성 판 ⑤-6/⑤-7 — ★5차(별도 서장 월드 ·
    #   배 한 척)로 표적 소멸. 위 ③(부활 감시)이 무덤을 지킨다. git 2026-07-25 낮 판이 정본.


# ═══════════════════════════════════════════════════════════════════════════
#  ⑧-4 ★기억의 무대 — 몸으로 겪는 서장인가 (B-179 2차 · 사용자 확정 2026-07-25)
# ═══════════════════════════════════════════════════════════════════════════

def audit_stage(rep: Report, ante: dict, code: str) -> None:
    rep.say()
    rep.say("  ⑧-4 기억의 무대 — 몸으로 겪는 서장인가 (글은 맥박 · 선택은 등불 · 전문은 필사본)")
    stg = load_yaml("seojang_stage.yml").get("stage") or {}
    if not stg:
        rep.bad("seojang_stage.yml stage 절이 없다 — 무대가 등록부에 없다 (강등 스위치도 없다)")
        return
    scenes = load_yaml("seojang.yml").get("scenes") or {}
    sets = stg.get("sets") or {}

    # ① 무대 ↔ 장면 — 제목 글자 그대로 대조 (계열 판별이 이 대조로 돈다) + 에필로그 무대 존재
    for name, sc in scenes.items():
        stages = sets.get(name)
        if not stages:
            rep.warn(f"계열 '{name}' 의 무대가 없다 — 중립 무대(안개의 고동)로 강등된다")
            continue
        if len(stages) != len(sc) + 1:
            rep.bad(f"계열 '{name}' 무대 {len(stages)}장 ≠ 장면 {len(sc)}장+에필로그 — "
                    "어느 장은 무대 없이 지나간다")
        ok = True
        for i, s in enumerate(sc):
            reg = str(stages[i].get("title", "")) if i < len(stages) else ""
            if reg != str(s.get("title", "")):
                ok = False
                rep.bad(f"무대 '{name}'[{i + 1}] 제목 {reg!r} ≠ 장면 제목 {s.get('title')!r} — "
                        "무대가 장면을 못 알아본다 (계열 판별 = 제목 대조)")
        if ok:
            rep.good(f"계열 '{name}' — 무대 {len(stages)}장 (장면 {len(sc)} + 에필로그) · 제목 일치")

    # ② 침묵 금지 — 모든 무대(중립 포함)에 맥박(숨)이 있다
    if not ((stg.get("neutral") or {}).get("pulse")):
        rep.bad("중립 무대에 숨(pulse)이 없다 — 등록부 밖 계열이 침묵 속을 지난다")
    for name, stages in sets.items():
        for i, e in enumerate(stages or []):
            if not e.get("pulse"):
                rep.bad(f"무대 '{name}'[{i + 1}] 에 숨(pulse)이 없다 — 조형만 있는 침묵")
    rep.good("맥박 — 모든 무대가 숨을 쉰다 (조형이 없어도 침묵은 아니다)")

    # ②-b ★발단별 첫 장 — 모든 경우의 수 (사용자 확정 2026-07-25 · 키는 player_creation 이 정본)
    incs = stg.get("incidents") or {}

    def find_key(d, key):
        if not isinstance(d, dict):
            return None
        if key in d:
            return d[key]
        for v in d.values():
            got = find_key(v, key)
            if got is not None:
                return got
        return None
    truth_inc = find_key(load_yaml("player_creation.yml"), "inciting_incidents") or {}
    fake = [k for k in incs if k not in truth_inc]
    if fake:
        rep.bad(f"지어낸 발단이 무대에 있다: {fake} — player_creation.inciting_incidents 가 정본이다")
    missing = [k for k in truth_inc if k not in incs]
    if missing:
        rep.bad(f"발단 {missing} 의 첫 장 무대가 없다 — **모든 경우의 수**가 계약이다 "
                "(역병의 그날 밤과 화재의 그날 밤은 다른 기억이다)")
    elif truth_inc:
        rep.good(f"발단 {len(truth_inc)}종 전부 첫 장 무대가 있다 (모든 경우의 수 · 사전 제작)")
    for k, e in incs.items():
        if not (e or {}).get("pulse"):
            rep.bad(f"발단 무대 '{k}' 에 숨(pulse)이 없다 — 조형만 있는 침묵")
    bot_gl = os.path.join(ROOT, "server-bot", "src", "main", "java", "com", "honcheon", "bot",
                          "GameListener.java")
    bot_src = open(bot_gl, encoding="utf-8").read() if os.path.isfile(bot_gl) else ""
    if 'put("incident"' not in bot_src:
        rep.bad("봇이 발단을 안 싣는다 — 다리에 발단이 없어 첫 장 무대가 영영 계열 폴백이다")
    elif '"incident"' not in source("WorldBridge.java"):
        rep.bad("다리가 발단을 안 읽는다 — 봇이 실어도 마크가 버린다")
    else:
        rep.good("발단이 다리를 건넌다 (봇 put → 다리 parse → 무대 resolve)")

    # ②-c ★가문 전용 발단은 제 벌을 산다 (B-181 일반화 — 출분·세가 공통):
    #   family_only 가 붙은 발단이 기본(재난) 벌로 떨어지면, 그 뼈대의 선택지가 그 아이에게
    #   거짓말이 된다 (「식구들부터 깨운다」를 담 넘은 아이가 고르는 병)
    branch_map = load_yaml("seojang.yml").get("branch_of") or {}
    stray = [k for k, v in truth_inc.items()
             if (v or {}).get("family_only") and branch_map.get(k) in (None, "기본")]
    if stray:
        rep.bad(f"가문 전용 발단 {stray} 이 재난 벌로 떨어진다 — 그 뼈대의 선택지가 "
                "그 아이에게 거짓말이 된다 (B-181)")
    else:
        rep.good("가문 전용 발단(수행·출분·세가)은 전부 제 벌을 산다 (branch_of)")
    for k, b in branch_map.items():
        if b not in scenes:
            rep.bad(f"branch_of[{k}] = {b!r} — 없는 벌을 가리킨다 (그 발단은 기본으로 강등된다)")
    bot_sj = os.path.join(ROOT, "server-bot", "src", "main", "java", "com", "honcheon", "bot",
                          "Seojang.java")
    bot_sj_src = open(bot_sj, encoding="utf-8").read() if os.path.isfile(bot_sj) else ""
    if '"branch_of"' not in bot_sj_src:
        rep.bad("봇이 갈래 등록부(branch_of)를 안 읽는다 — 등록부가 있어도 출분은 재난 벌이다")
    else:
        rep.good("갈래는 등록부가 정한다 (봇 Seojang.branchOf ← branch_of)")

    # ③ 배선 — 정거장=무대 · 강등 문 · 재배달 억제 · 등불의 손 · 격리 · 필사본
    voy = source("Voyage.java")
    if "ante.stage().play(" not in voy:
        rep.bad("정거장이 무대를 안 연다 — Voyage 가 stage.play 를 안 부른다 (책 그릇 그대로)")
    elif "SeojangBook.get().deliver(player, scene)" not in voy:
        rep.bad("강등 문이 없다 — 무대가 꺼진 날 책도 안 온다 (침묵은 그릇이 아니다)")
    else:
        rep.good("정거장 = 무대 (꺼져 있으면 책으로 강등 — 어느 날도 침묵은 없다)")
    if "return ante.stage().enabled();" not in voy:
        rep.bad("무대 그릇인데 2초 재배달이 책을 몰래 쥐여 줄 수 있다 — 그릇이 둘이 된다")
    else:
        rep.good("무대 그릇에서는 책이 흐르지 않는다 (재배달 억제)")
    if "SeojangBook.get().settle(player);" not in voy:
        rep.bad("붓을 내려놨는데 「적고 있다」가 안 걷힌다 — 무대 길은 deliver 를 안 지나므로 "
                "settle 이 기다림 기계를 걷어야 한다 (화면의 거짓말)")
    else:
        rep.good("붓이 내려오면 기다림 기계가 걷힌다 (settle — 남은 기다림은 항해다)")

    # ⑦ ★재기동·죽음의 낙하 3방어 (실사용 2026-07-25 "재접속 하니까 땅에 끼임 그리고 죽어버림" ·
    #   "리스폰 하니까 스폰 위치도 이상함") — 항해 중의 몸은 어느 문으로 나가도 나루로 돌아온다
    st_body = body_of(code, r"void start\(\)")
    if st_body is None or "world();" not in st_body:
        rep.bad("나루를 미리 안 연다 — 재기동 직후 재접속한 항해자의 몸이 기본 월드 스폰"
                "(광장 우물 기둥)에 낙하해 질식한다 (지연 로드의 함정)")
    else:
        rep.good("나루는 기동 때 미리 열린다 — 재접속 낙하가 원천 소멸 (1차 방어)")
    jb = body_of(code, r"public void onJoin\([^)]*\)")
    if jb is None or not re.search(r"!isAntechamber[\s\S]{0,300}?seojangHolds[\s\S]{0,400}?spawnAt",
                                   jb):
        rep.bad("나루 밖에 선 항해자를 onJoin 이 안 집는다 — 낙하한 몸이 영영 방치된다 (2차 방어 부재)")
    else:
        rep.good("나루 밖의 항해자는 onJoin 이 되돌린다 (2차 방어)")
    rb = body_of(code, r"public void onRespawn\([^)]*\)")
    if rb is None or not re.search(r"seojangHolds[\s\S]{0,300}?setRespawnLocation\(spawnAt", rb):
        rep.bad("강을 건너다 죽은 넋이 나루로 못 돌아온다 — 리스폰이 본세계 자리에 세운다 (서장 단절)")
    else:
        rep.good("죽은 넋은 나루로 돌아온다 (리스폰 귀항 — 3차 방어)")
    sjs = source("SeojangStage.java")
    if not sjs:
        rep.bad("SeojangStage.java 가 없다 — 무대가 말뿐이다")
    else:
        if "WorldBridge.seojangChoice(" not in sjs:
            rep.bad("등불이 다리에 안 얹는다 — 우클릭해도 아무 일도 없다 (선택이 몸을 잃었다)")
        else:
            rep.good("등불 우클릭 → 다리(seojangChoice) — 판정은 여전히 봇의 것")
        if "incidents.get(scene.incident())" not in sjs:
            rep.bad("첫 장이 발단을 안 본다 — 역병의 밤도 불타는 집이 된다 (resolve 오배선)")
        else:
            rep.good("첫 장은 발단이 가른다 (resolve — 발단 → 계열 → 중립 강등 사다리)")
        if "hideEntity" not in sjs:
            rep.bad("무대가 남에게도 보인다 — 「본인에게만」이 격리 확정의 계약이다")
        else:
            rep.good("무대·등불은 본인에게만 보인다 (hideEntity)")
    if "registerEvents(stage" not in code:
        rep.bad("등불의 손이 등록되지 않았다 — 우클릭이 허공을 친다 (Listener 미등록)")
    if "stage.sweep(" not in code:
        rep.bad("주인 잃은 무대를 걷는 손이 없다 — 조형이 강 위에 쌓인다")
    if (stg.get("memoir") or {}).get("give") and "SeojangBook.get().memoir(p," not in voy:
        rep.bad("필사본이 말뿐이다 — 기슭에서 전문을 안 준다 (개인 서사가 증발한다)")
    else:
        rep.good("기슭의 필사본 — LLM 개인 서사는 잃지 않는다")


# ═══════════════════════════════════════════════════════════════════════════
#  ⑨ 발판 — 밟으면 그 명령이 정말 쳐지는가
# ═══════════════════════════════════════════════════════════════════════════

def audit_plates(rep: Report, ante: dict, code: str) -> None:
    rep.say()
    rep.say("  ⑨ 발판 — 화면에 뜬 명령과 실제로 쳐진 명령이 같은가")
    pl = ante.get("plates") or {}
    plates = pl.get("list") or []
    if not plates:
        rep.bad("발판이 하나도 없다 — 명령어 문법을 손으로 외우게 한다 (진입 장벽)")
        return

    # ★★ 화면 = 세계. echo 에 뜨는 글자와 performCommand 에 넘기는 글자가 **같은 변수**여야 한다.
    #    (이 프로젝트는 `/혼천 협공` 이 "캡 +3"이라 찍는데 config 는 2였던 적이 있다.)
    body = body_of(code, r"private void stepPlate\(Player player, Plate plate\)")
    if body is None:
        rep.bad("stepPlate() 를 못 찾았다 — 발판이 코드에 없다")
    else:
        if not re.search(r"String cmd = plate\.command\(\);", body):
            rep.bad("stepPlate() 가 명령을 한 변수에 담지 않는다")
        echoes = re.search(r'sendMessage\(plateEcho\.replace\("\{command\}", (\w+)\)\)', body)
        runs = re.search(r"performCommand\((\w+)\)", body)
        if not echoes or not runs:
            rep.bad("stepPlate() 가 명령을 보여주거나(echo) 실행(performCommand)하지 않는다")
        elif echoes.group(1) != runs.group(1):
            rep.bad(f"★ 화면에 뜨는 것({echoes.group(1)})과 실행되는 것({runs.group(1)})이 "
                    "다른 변수다 — 화면이 세계에 대해 거짓말할 수 있다")
        else:
            rep.good(f"보여주는 것 = 치는 것 (같은 변수 '{echoes.group(1)}') — 거짓말할 수가 없다")
        if re.search(r'performCommand\(\s*"', body):
            rep.bad("performCommand 에 문자열이 직접 박혀 있다 — config 가 정본이 아니게 된다")
        # ★ 주석에 "Action.PHYSICAL 이다" 라고 적어 놓은 것으로 통과시키면 안 된다.
        #   **주석을 벗기고, onInteract 의 속을 본다.**
        oi = body_of(strip_comments(code), r"public void onInteract\(PlayerInteractEvent event\)")
        if oi is None or "Action.PHYSICAL" not in oi:
            rep.bad("발판을 **밟는** 것(Action.PHYSICAL)으로 안 본다 — 밟아도 아무 일도 안 일어난다")
        else:
            rep.good("발판은 밟는 것이다 (Action.PHYSICAL)")

    # 【묘비】 손/발판 기입 경로 합일(creditCommand) — ★5차 과제 폐지로 기입할 장부가 없다.
    #   발판의 계약(echo = cmd 한 변수)은 위 눈이 계속 잰다.

    # 발판이 없는 명령을 치면 안 된다 (MvtCommand 가 정본)
    mvt = strip_comments(source("MvtCommand.java"))
    cases = set(re.findall(r'case\s+"([^"]+)"', mvt))
    for p in plates:
        cmd = (p.get("command") or "").split()
        if len(cmd) < 2:
            rep.bad(f"발판 '{p.get('id')}' 의 명령이 비었다")
            continue
        if cmd[0] != "혼천":
            rep.bad(f"발판 '{p.get('id')}' 이 혼천 명령이 아니다: {p.get('command')}")
        if cmd[1] not in cases:
            rep.bad(f"발판 '{p.get('id')}' 이 없는 명령을 친다: /혼천 {cmd[1]} (MvtCommand 에 없다) "
                    "— 밟아도 아무 일도 안 일어난다")
        else:
            rep.good(f"발판 '{p.get('id')}' → /{p.get('command')} (MvtCommand 에 실재한다)")

    # ★ 발판을 밟을 수 있는 자리에 놨는가 (물 위나 등롱 기둥 속에 놓으면 영영 못 밟는다)
    g = Geo(ante)
    walk = g.walkable()
    for p in plates:
        pos = tuple(p.get("pos") or [])
        if len(pos) != 2:
            rep.bad(f"발판 '{p.get('id')}' 에 자리가 없다")
        elif pos not in walk:
            reason = "물 위다" if not g.is_deck(*pos) else "등롱·난간·화톳불이 이미 서 있다"
            rep.bad(f"발판 '{p.get('id')}' {list(pos)} 을 밟을 수 없다 — {reason}")
        else:
            rep.good(f"발판 '{p.get('id')}' {list(pos)} — 딛는 자리에 있다")

    # ★ 배분값은 지어낸 것이 아니다 — player_creation.yml 이 정본
    pc = load_yaml("player_creation.yml")
    cur = ((pc.get("mvt_onboarding") or {}).get("default_curriculum")) or {}
    taught = {}
    for p in plates:
        parts = (p.get("command") or "").split()
        if len(parts) == 4 and parts[1] == "수련":
            try:
                taught[parts[2]] = int(parts[3])
            except ValueError:
                rep.bad(f"발판 '{p.get('id')}' 의 구간이 숫자가 아니다: {parts[3]}")
    if not cur:
        rep.warn("player_creation.yml 의 default_curriculum 을 못 읽었다 — 배분 대조를 못 했다")
    elif taught and taught != {k: v for k, v in cur.items() if k in taught}:
        rep.bad(f"발판이 가르치는 배분 {taught} ≠ player_creation.yml default_curriculum "
                f"{dict(cur)} — 발판이 config 와 다른 빌드를 깐다")
    elif taught:
        rep.good(f"발판의 배분 {taught} = player_creation.yml default_curriculum (지어내지 않았다)")


# ═══════════════════════════════════════════════════════════════════════════
#  ⑩ 허수아비 — ★ 때릴 상대가 **서 있는가**
# ═══════════════════════════════════════════════════════════════════════════
#
# 이 눈은 2026-07-13 에 생겼다. 그날 사용자가 말했다:
#   "인증 전까지 때릴 상대가 없습니다. 아직 입도진에 허수아비가 안 보여요."
#
# 그때까지 이 감사는 허수아비를 **한 번도 보지 않았다.** 문·갇힘·거짓말·글판·길·빛·발판을
# 다 재면서, 정작 **때릴 상대가 서 있는가**는 안 물었다. 위반 0건이었고, 마당은 비어 있었다.
# (유일한 언급은 detect '허수아비_타격' 의 배선 검사였다 — 즉 "때리면 세는가"만 봤지
#  "때릴 것이 있는가"는 안 봤다. 셈틀은 멀쩡했고 대상이 없었다.)
#
# 병은 둘이었고 **둘 다 조용했다**:
#   ① Difficulty.PEACEFUL — 평화는 몬스터를 매 틱 지운다. 허수아비의 몸은 좀비다.
#      예외도 로그도 없다. 태어나서 사라졌다.
#   ② 조성 로그가 `dummySpots.size()` (등록부의 수)를 찍었다 — **선 것의 수가 아니다.**
#      로그는 "허수아비 3"이라 말했고 세계에는 0이 서 있었다. **침묵이 아니라 거짓말이었다.**

def audit_dummies(rep: Report, ante: dict, code: str) -> None:
    rep.say()
    rep.say("  ⑩ 허수아비 — 때릴 상대가 서 있는가 (★ 이 눈이 없어서 마당이 비어 있었다)")
    du = ante.get("dummies") or {}
    dummies = du.get("list") or []

    if not dummies:
        # ★3차 개정 — 허수아비는 타격 과제와 한 몸이다: 과제가 있는데 상대가 없으면 거짓말이고,
        #   과제가 없으면(순수 문지방) 0몸이 맞다 (때리는 법은 본토 첫 사냥이 가르친다).
        #   ★return 하지 않는다 — 사람 보호(damage_players)·코드 형태(평화·체력·격리·조성 로그)
        #   검사는 허수아비 유무와 무관하다 (조기 return 이 그 눈들을 같이 감았던 적 있다)
        # ★5차 — 과제 자체가 폐지돼 「과제 있는데 상대 없음」의 표적도 소멸했다 (0몸이 정본)
        rep.good("허수아비 0몸 — 과제가 없다 (★5차: 나루는 시험하지 않는다)")
    else:
        rep.good(f"허수아비 {len(dummies)}몸 등록")

    # ★3차 개정 추기 — 사공의 몸: 등록됐으면 세우는 손이 코드에 있어야 한다
    #   ("있다고 말만 하고" — 사공의 집과 같은 병이 몸에서 재발하지 않게)
    fman = ante.get("ferryman") or {}
    if fman.get("pos"):
        # 이름만 훑으면 호출부 문자열(this::ensureFerryman)에 속는다 — **정의**를 본다
        if not re.search(r"void\s+ensureFerryman\s*\(\s*World", strip_comments(code)):
            rep.bad("ferryman 이 등록됐는데 세우는 손(ensureFerryman)이 코드에 없다 — "
                    "사공이 있다고 말만 한다 (집 지을 때와 같은 병)")
        else:
            rep.good("사공의 몸 — 등록부(ferryman.pos)와 세우는 손(ensureFerryman)이 있다")

    # ★★ ① 평화(PEACEFUL) 는 몬스터를 지운다 — 허수아비의 몸이 좀비인 한, 평화 = 허수아비 없음.
    #    예외도 로그도 없이 조용히. **이것이 오늘의 병이었고, 이 줄이 그 눈이다.**
    body = strip_comments(code)
    spawns_monster = re.search(r"spawn\([^;]{0,120}?(Zombie|Skeleton|Husk|Zoglin|Monster)\.class", body)
    peaceful = re.search(r"setDifficulty\(\s*Difficulty\.PEACEFUL", body)
    if spawns_monster and peaceful:
        rep.bad("★ 나루가 PEACEFUL 인데 허수아비의 몸이 몬스터(좀비)다 — "
                "평화는 몬스터를 **매 틱 조용히 지운다** (예외도 로그도 없고 setPersistent 도 소용없다). "
                "허수아비는 태어나자마자 사라진다. 난이도를 올리고, 사람은 피해 취소로 지켜라")
    elif spawns_monster:
        rep.good("허수아비의 몸은 몬스터(좀비)이고, 나루는 평화가 아니다 (지워지지 않는다)")
    cfg_diff = str(ante.get("difficulty", "")).upper()
    if cfg_diff == "PEACEFUL":
        rep.bad("★ antechamber.difficulty: PEACEFUL — 평화는 허수아비(좀비)를 지운다")
    elif not cfg_diff:
        rep.bad("antechamber.difficulty 가 등록돼 있지 않다 — 코드가 난이도를 지어낸다 "
                "(그리고 그 값이 PEACEFUL 이면 허수아비가 조용히 사라진다)")
    else:
        rep.good(f"난이도 = {cfg_diff} (등록부가 정한다 · 평화가 아니다)")

    # 평화를 버렸으면 사람은 무엇이 지키는가 — 약속은 그대로여야 한다 ("나루에서는 죽지 않는다")
    if ante.get("damage_players") is not False:
        rep.bad("damage_players 가 false 가 아니다 — 평화를 버렸는데 사람을 지킬 손이 등록돼 있지 않다")
    elif not re.search(r"EntityDamageEvent[\s\S]{0,600}?setCancelled\(true\)", body):
        rep.bad("사람에게 오는 피해를 끊는 손이 코드에 없다 — "
                "난이도를 올린 대가로 사람이 나루에서 죽을 수 있다")
    else:
        rep.good("나루에서 사람은 안 죽는다 — 난이도가 아니라 피해 취소가 지킨다 (허수아비는 산다)")

    # ★★ ② 체력을 손으로 넣지 않는가 — 2048 병의 재발 방지.
    #    MAX_HEALTH 특성의 범위는 …1024 다. 숫자를 그대로 setHealth 에 넣는 순간 언젠가 또 터진다.
    for name, sig in (("입도진", r"private void spawnDummy\(World w, Dummy d, int y\)"),
                      ("연무장", r"void dummy\(Player player, int durability\)")):
        src = code if name == "입도진" else source("Dojang.java")
        b = body_of(strip_comments(src), sig)
        if b is None:
            rep.bad(f"{name}의 허수아비 조성기를 못 찾았다")
            continue
        sh = re.search(r"setHealth\((.*)\);", b)   # ★ getValue() 의 괄호까지 물어야 한다 ([^)]* 로는 못 본다)
        if not sh:
            rep.warn(f"{name}의 허수아비가 체력을 안 세운다")
        elif re.search(r"^\s*-?[\d_.]+\s*$|DUMMY_HEALTH", sh.group(1)):
            rep.bad(f"★ {name}의 허수아비가 체력에 숫자를 손으로 넣는다: setHealth({sh.group(1).strip()}) "
                    "— MAX_HEALTH 특성의 범위(…1024)를 넘으면 예외가 나고 **좀비가 아예 안 태어난다**. "
                    "특성에게 물어라: setHealth(attr.getValue())")
        elif "getValue()" not in sh.group(1):
            rep.warn(f"{name}의 setHealth({sh.group(1).strip()}) 가 특성값에서 오지 않는다")
        else:
            rep.good(f"{name}의 허수아비 체력 = 특성의 실효값 (상한이 몇이든 안 터진다)")

    # ★ ③ 하나가 죽어도 나머지는 서는가 (과거 병: 하나의 예외가 대기실 전체를 죽였다)
    ed = body_of(strip_comments(code), r"private void ensureDummies\(World w\)")
    if ed is None:
        rep.bad("ensureDummies() 를 못 찾았다")
    elif "catch" not in ed:
        rep.bad("★ 허수아비를 하나씩 격리해 세우지 않는다 — 한 몸이 예외를 던지면 "
                "나머지도, 글판도, 발판도 다 안 선다 (2026-07-13 오전의 병 그대로)")
    else:
        rep.good("허수아비는 하나씩 제 울타리 안에서 선다 (하나가 죽어도 나머지는 선다)")

    # ★★ ③-b **많은 것도 틀린 것이다** — 등록부는 6인데 세계에 24 가 서 있었다.
    #    옛 코드: `if (countDummies(w) >= dummies.size()) return;`
    #    재조성이 돌 때마다 6씩 쌓였고, 한 번 넘치고 나면 `24 >= 6` 이라 **영영 안 치웠다.**
    #    겹쳐 선 허수아비는 히트박스가 겹쳐 **타격 계측을 망친다** (허수아비는 계기다).
    if ed and re.search(r"countDummies\(w\)\s*>=\s*dummies\.size\(\)", ed):
        rep.bad("★ ensureDummies() 가 `>=` 로 판단한다 — **많은 것도 틀린 것이다.** "
                "재조성이 허수아비를 쌓고(등록부 6 → 세계 24), 넘치고 나면 영영 안 치운다. "
                "겹쳐 선 몸은 히트박스가 겹쳐 계측을 망친다. `!=` 여야 한다")
    elif ed and re.search(r"countDummies\(w\)\s*==\s*dummies\.size\(\)", ed):
        rep.good("ensureDummies() 는 `==` 로 판단한다 (모자라도 넘쳐도 다시 세운다)")
    else:
        rep.warn("ensureDummies() 가 등록부의 개수와 세계의 개수를 어떻게 견주는지 못 읽었다")

    # ★ ④ 로그가 **선 것**을 세는가 — 등록부의 개수를 찍으면 그것은 거짓말이다
    cen = body_of(strip_comments(code), r"private void census\(World w, String head\)")
    if cen is None:
        rep.bad("census() 가 없다 — 조성이 무엇을 몇 개 세웠는지 말하지 않는다 (침묵이 성공으로 읽힌다)")
    elif not (re.search(r"liveDummies = countDummies\(", cen)
              and re.search(r"livePanels = countPanels\(", cen)):
        rep.bad("★ 조성 로그가 **세계에게 묻지 않는다** — 등록부의 개수를 찍으면 "
                "허수아비가 0인 날에도 로그는 '허수아비 6'이라 말한다 (로그가 거짓말한다)")
    else:
        rep.good("조성 로그는 세계에게 묻는다 (countDummies/countPanels — 등록부가 아니라 선 것을 센다)")

    # ★ ⑤ 등급은 지어낸 것이 아니다 — DojangGui 의 등급표가 정본이다
    gui = source("DojangGui.java")
    m = re.search(r"int\[\]\s+durabilities\s*=\s*\{([^}]*)\}", gui)
    lm = re.search(r"String\[\]\s+labels\s*=\s*\{([^}]*)\}", gui)
    if not m:
        rep.warn("DojangGui.java 에서 등급별 내구를 못 읽었다 — 대조할 정본이 없다")
    else:
        truth = {int(x) for x in re.findall(r"\d+", m.group(1))}
        taught = {d.get("durability") for d in dummies}
        stray = sorted(x for x in taught if x not in truth)
        if stray:
            rep.bad(f"허수아비 내구 {stray} 가 DojangGui 의 등급표 {sorted(truth)} 에 없다 "
                    "— 코드/등록부가 등급을 지어낸다")
        else:
            rep.good(f"허수아비 내구 {sorted(taught)} ⊆ DojangGui 등급표 {sorted(truth)} (지어내지 않았다)")
        if lm:
            names = {re.sub(r"\(.*", "", s).strip() for s in re.findall(r'"([^"]+)"', lm.group(1))}
            for d in dummies:
                if d.get("label") not in names:
                    rep.bad(f"허수아비 '{d.get('id')}' 의 이름 {d.get('label')!r} 이 "
                            f"DojangGui 의 등급 이름 {sorted(names)} 에 없다")

    # ★ ⑥ 명패가 다섯 눈금을 말하는가 (최근·누적·합수·평균·TTK — Dojang 의 명패와 같다)
    hit = du.get("hit") or ""
    for token in ("{last}", "{total}", "{hits}", "{avg}", "{ttk}"):
        if token not in hit:
            rep.bad(f"허수아비 명패에 {token} 이 없다 — 맞은 것을 다 말하지 않는다 "
                    "(최근·누적·합수·평균·TTK 다섯이 있어야 타격감을 잴 수 있다)")
    hn = body_of(strip_comments(code), r"private String hitName\(String label, int durability, double\[\] t\)")
    if hn is None:
        rep.bad("hitName() 이 없다 — 명패의 문장이 어디서 오는지 알 수 없다")
    elif "dummyHit" not in hn:
        rep.bad("명패 서식이 등록부(dummies.hit)에서 오지 않는다 — 코드가 문장을 지어낸다")
    else:
        rep.good("명패 = 등록부의 서식 + 실제로 맞은 값 (최근·누적·합수·평균·TTK)")

    # ★ ⑦ 설 수 있는 자리인가 — 물 위나 난간·등롱·화톳불 속에 세우면 영영 못 만난다
    g = Geo(ante)
    walk = g.walkable()
    seen: dict[tuple, str] = {}
    for d in dummies:
        pos = tuple(d.get("pos") or [])
        did = d.get("id")
        if len(pos) != 2:
            rep.bad(f"허수아비 '{did}' 에 자리가 없다")
            continue
        if pos in seen:
            rep.bad(f"허수아비 '{did}' 가 '{seen[pos]}' 와 같은 칸에 선다 {list(pos)} — 겹친다")
        seen[pos] = did
        if pos not in walk:
            why = "물 위다" if not g.is_deck(*pos) else "난간·등롱·화톳불이 이미 서 있다"
            rep.bad(f"허수아비 '{did}' {list(pos)} 를 세울 수 없다 — {why}")
        else:
            st = g.station_at(*pos)
            rep.good(f"허수아비 '{did}' {list(pos)} — 딛는 자리에 선다 "
                     f"(관문 '{st['id'] if st else '길'}')")

    # 【묘비】 손 과제 관문-허수아비 대조 — ★5차 과제 폐지로 표적 소멸 (과제를 되살리는 날 함께)


# ═══════════════════════════════════════════════════════════════════════════
#  ⑪ 세계 — ★ **정말로 서 있는가** (등록부가 아니라 저장된 세계에게 묻는다)
# ═══════════════════════════════════════════════════════════════════════════
#
# 위의 눈은 전부 **정적**이다 — config 와 소스만 읽는다. 그래서 오늘의 병을 못 봤다:
# 코드도 config 도 "허수아비 셋"이라 말했고, 조성 로그도 "허수아비 3"이라 말했고,
# **세계에는 0이 서 있었다.** 아무도 세계에게 묻지 않았다.
#
# 이 눈은 저장된 월드(run/mvt/<world>/entities/*.mca)를 열어 **PDC 표식을 직접 센다.**
# 이것이 내가 오늘 병을 잡은 방법 그대로다 — 글판 8, 허수아비 0.
#
# ★ 한계는 정직하게: 이것은 **마지막 저장 시점**의 세계다. 서버가 돌고 있고 아직 저장이
#   안 됐으면 낡은 값을 본다. 그래도 "등록부가 그렇다더라"보다는 언제나 세계에 가깝다.

def marker_census(data: bytes) -> dict:
    """청크 NBT 바이트에서 PDC 표식을 센다.

    ★★ 'ipdo_dummy' 는 'ipdo_dummy_label' **안에도 산다** (부분 문자열).
    몸 하나가 키 둘(ipdo_dummy + ipdo_dummy_label)을 지니므로, 그냥 세면
    **몸 하나가 두 번 세어진다** — 등록부 6 이 세계 12 로 읽혔다 (2026-07-14 실증:
    갓 지은 월드 · 조성 1회 · 라이브 계수 6 인데 이 눈만 12 라 했다. 쌓인 몸이 아니라
    **눈의 오독**이었다). 긴 키를 먼저 세어 짧은 키에서 뺀다."""
    label = data.count(b"ipdo_dummy_label")
    return {
        "dummy": data.count(b"ipdo_dummy") - label,
        "panel": data.count(b"ipdo_panel"),
    }


def audit_world(rep: Report, ante: dict) -> None:
    import struct
    import zlib

    rep.say()
    rep.say("  ⑪ 세계 — 저장된 나루에 **정말로** 무엇이 서 있는가 (등록부가 아니라 세계에게 묻는다)")
    world = ante.get("world") or "honcheon_ipdo"
    ent = os.path.join(ROOT, "run", "mvt", world, "entities")
    if not os.path.isdir(ent):
        rep.warn(f"저장된 나루가 없다 ({ent}) — 아직 한 번도 안 지어졌거나 서버를 안 돌렸다. "
                 "세계 축은 재지 못했다")
        return

    want_dummies = len((ante.get("dummies") or {}).get("list") or [])
    stations = ante.get("stations") or []
    want_panels = len(stations)   # ★5차 — 판은 관문마다 하나 (예고 변형 소멸)

    found = {"dummy": 0, "panel": 0}
    chunks = 0
    for fn in sorted(os.listdir(ent)):
        if not fn.endswith(".mca"):
            continue
        raw = open(os.path.join(ent, fn), "rb").read()
        for i in range(1024):
            off = int.from_bytes(raw[i * 4:i * 4 + 3], "big")
            if off == 0:
                continue
            start = off * 4096
            if start + 5 > len(raw):
                continue
            ln = int.from_bytes(raw[start:start + 4], "big")
            comp = raw[start + 4]
            blob = raw[start + 5:start + 4 + ln]
            try:
                if comp == 1:
                    data = zlib.decompress(blob, 16 + zlib.MAX_WBITS)
                elif comp == 2:
                    data = zlib.decompress(blob)
                elif comp == 3:
                    data = blob
                else:
                    rep.warn(f"{fn} 청크 {i}: 모르는 압축 방식 {comp} — 못 셌다")
                    continue
            except zlib.error as e:
                rep.warn(f"{fn} 청크 {i}: 풀지 못했다 ({e})")
                continue
            chunks += 1
            for key, n in marker_census(data).items():
                found[key] += n

    dummies_in_world = found["dummy"]
    panels_in_world = found["panel"]
    rep.say(f"    · 저장된 엔티티 청크 {chunks} — 허수아비 {dummies_in_world} · 글판 {panels_in_world}")

    if dummies_in_world < want_dummies:
        rep.bad(f"★ 세계에 허수아비가 {dummies_in_world}몸뿐이다 (등록부는 {want_dummies}몸). "
                "때릴 상대가 없다 — 조성 로그가 뭐라 찍었든 **세계가 진실이다**. "
                "(마지막 저장 시점 기준. 서버를 다시 돌리고 /혼천 입도 재조성 뒤 다시 재라)")
    elif dummies_in_world > want_dummies:
        # ★★ **많은 것도 틀린 것이다.** 이 눈은 여태 '모자람'만 봤다 — 그래서 등록부 6인데
        #   세계에 12 가 서 있어도 ✅ 였다. 겹쳐 선 몸은 히트박스가 겹쳐 **타격 계측을 망친다**
        #   (허수아비는 계기다). ensureDummies() 의 `==` 와 marker_census() 의 부분 문자열
        #   차감이 둘 다 선 지금, 이 갈래가 우는 것은 **진짜 쌓인 몸**이다.
        rep.bad(f"★ 세계에 허수아비가 {dummies_in_world}몸이다 — 등록부는 {want_dummies}몸. "
                "**많은 것도 틀린 것이다**: 몸이 쌓였다. 겹쳐 선 허수아비는 히트박스가 겹쳐 "
                "타격 계측을 망친다 (허수아비는 계기다). "
                "서버가 도는 상태에서 /혼천 입도 재조성 하면 걷힌다 (마지막 저장 기준임에 유의)")
    else:
        rep.good(f"세계에 허수아비 {dummies_in_world}몸이 서 있다 (등록부 {want_dummies}) — 때릴 상대가 있다")

    # ★「없는 것」과 「걷힌 것」은 다르다 (2026-07-24 실증 — 서장 눈의 병이 여기서 재발할 뻔):
    #   shutdown() 이 글판을 **설계대로 걷는다** ("세계에 아무것도 남기지 않는다" — 다음 입장의
    #   ensurePanels 가 다시 세운다). 그래서 정상 종료된 나루의 저장본은 글판 0개가 **정본**이다.
    #   0 = 걷힌 세계 · 등록부 수 = 산 세계의 스냅숏 — 그 밖의 수만 병이다 (누락 또는 겹침).
    if panels_in_world == 0:
        rep.good(f"세계에 글판 0개 — 걷힌 상태 (shutdown 계약 · 입장 때 {want_panels}개가 다시 선다)")
    elif panels_in_world < want_panels:
        rep.bad(f"세계에 글판이 {panels_in_world}개뿐이다 (등록부는 {want_panels}개 · 걷힘도 아니다) "
                "— 반쯤 걷혔거나 반쯤 섰다")
    elif panels_in_world > want_panels:
        rep.warn(f"세계에 글판이 {panels_in_world}개 — 등록부({want_panels})보다 많다. 겹쳤을 수 있다")
    else:
        rep.good(f"세계에 글판 {panels_in_world}개 (등록부와 같다)")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--verbose", "-v", action="store_true", help="통과 항목도 보인다")
    ap.add_argument("--no-world", action="store_true",
                    help="세계 축(⑪)을 건너뛴다 — 정적 검사만 (자기 시험이 쓴다)")
    args = ap.parse_args()

    rep = Report(args.verbose)
    rep.say("═" * 72)
    rep.say("  입도진 감사 — 대기실의 눈")
    rep.say("═" * 72)
    rep.say()

    root = load_yaml("antechamber.yml")
    ante = root.get("antechamber") or {}
    path = os.path.join(CFG, "antechamber.yml")
    raw_cfg = open(path, encoding="utf-8").read() if os.path.isfile(path) else ""
    code = source("Antechamber.java")

    if not ante:
        rep.bad("config/antechamber.yml 이 없거나 antechamber 절이 없다")
    if not code:
        rep.bad("server-mvt/.../Antechamber.java 가 없다")
    if not ante or not code:
        rep.say("═" * 72)
        rep.dump()
        return 1

    audit_gate(rep, ante, code)
    audit_trap(rep, ante, code)
    audit_truth(rep, ante, code)
    audit_wholeness(rep, ante, code)
    audit_panels(rep, ante, code)
    audit_road(rep, ante, code)
    audit_flow(rep, ante, code)
    audit_light(rep, ante, code)
    audit_canvas(rep, ante, code)
    audit_voyage(rep, ante, code)
    audit_stage(rep, ante, code)
    audit_plates(rep, ante, code)
    audit_dummies(rep, ante, code)
    audit_conventions(rep, code, raw_cfg)
    if not args.no_world:
        audit_world(rep, ante)

    rep.say()
    rep.say("═" * 72)
    n_v, n_w = len(rep.violations), len(rep.warnings)
    if n_v == 0 and n_w == 0:
        rep.say("  총평: ✅ 위반 0건 · 경고 0건 — 문이 있고, 갇히지 않고, 거짓말하지 않는다")
    else:
        rep.say(f"  총평: 위반 {n_v}건 · 경고 {n_w}건")
        if n_v:
            rep.say("")
            rep.say(f"  ── 위반 ({FAIL}) — 대기실이 감옥이거나, 거짓말한다")
            for i, v in enumerate(rep.violations, 1):
                rep.say(f"    {i:2}. {v}")
        if n_w:
            rep.say("")
            rep.say(f"  ── 경고 ({WARN})")
            for i, w in enumerate(rep.warnings, 1):
                rep.say(f"    {i:2}. {w}")
    rep.say("═" * 72)
    rep.dump()
    return 1 if n_v else 0


if __name__ == "__main__":
    sys.exit(main())
