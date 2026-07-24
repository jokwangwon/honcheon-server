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

    # 과제가 문을 잠그면 안 된다
    if (ante.get("lessons") or {}).get("gating"):
        rep.bad("lessons.gating: true — 과제가 문을 잠근다. 과제 하나가 깨진 날 사람이 나루에 갇힌다")
    else:
        rep.good("lessons.gating: false — 과제는 문을 잠그지 않는다")


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
    rep.say("  ③ 거짓말 — 가르치는 조작 vs 실제 엔진 (★ 답을 손으로 쓰지 않는다. 등록부에서 읽어 대조한다)")
    lessons = {l["id"]: l for l in ((ante.get("lessons") or {}).get("list") or []) if "id" in l}
    if not lessons:
        rep.bad("과제가 하나도 없다 — 대기실이 아무것도 안 가르친다")
        return

    # ── 몸짓: combat.yml defender_stance_mc.gestures 가 정본
    combat = load_yaml("combat.yml")
    truth_gestures = set()
    node = combat
    for key in ("attack", "defense", "combat"):
        if isinstance(node.get(key), dict) and "defender_stance_mc" in node[key]:
            node = node[key]
            break
    dsm = None
    def find_dsm(d):
        if not isinstance(d, dict):
            return None
        if "defender_stance_mc" in d:
            return d["defender_stance_mc"]
        for v in d.values():
            got = find_dsm(v)
            if got:
                return got
        return None
    dsm = find_dsm(combat)
    if not dsm or not dsm.get("gestures"):
        rep.bad("combat.yml 에서 defender_stance_mc.gestures 를 못 찾았다 — 대조할 정본이 없다")
    elif "태세" not in lessons:
        # ★3차 개정 (2026-07-24) — 태세 과제는 본토 뿌리내림(B-178)으로 이관됐다. 과제가 없으면
        #   가르침 대조도 없다 — 다만 침묵하지 않고 어디로 갔는지 말한다 (tutorial_rooting.md §3)
        rep.good("태세 과제 없음 — 몸짓은 본토 뿌리내림(B-178)이 가르친다 (3차 개정)")
    else:
        truth_gestures = set(dsm["gestures"].values())
        taught = set((lessons.get("태세") or {}).get("gestures") or [])
        if taught != truth_gestures:
            rep.bad(f"태세 과제가 가르치는 몸짓 {sorted(taught)} ≠ combat.yml 의 정본 "
                    f"{sorted(truth_gestures)} — 화면이 세계에 대해 거짓말한다")
        else:
            rep.good(f"태세 몸짓 = combat.yml 정본 {sorted(truth_gestures)}")
        # 그리고 코드가 그 술어를 **실제로** 평가하는가 (config 만 맞고 배선이 없으면 그것도 거짓말이다)
        for g in truth_gestures:
            method = "player." + g[0].lower() + g[1:] + "()"   # isBlocking -> player.isBlocking()
            if not re.search(r'case\s+"' + re.escape(g) + r'"\s*->\s*player\.' + re.escape(g) + r"\(\)", code):
                rep.bad(f"몸짓 {g} 를 코드가 평가하지 않는다 (기대: case \"{g}\" -> player.{g}()) "
                        f"— config 는 가르치는데 엔진은 안 본다")
            else:
                rep.good(f"코드가 {g} 를 실제로 본다")
        # 한글 태세 이름도 대조 — 판에 적힌 '막기/흘리기/회피' 가 combat.yml 의 그것과 같아야 한다
        how = (lessons.get("태세") or {}).get("how", "")
        for ko, pred in dsm["gestures"].items():
            if ko not in how:
                rep.warn(f"태세 과제 문장에 '{ko}' 가 없다 (combat.yml 에는 있다: {ko}={pred})")

    # ── 경공: gyeonggong.yml activate 가 정본
    gg = load_yaml("gyeonggong.yml")
    def find_activate(d):
        if not isinstance(d, dict):
            return None
        if "activate" in d and isinstance(d["activate"], str):
            return d["activate"]
        for v in d.values():
            got = find_activate(v)
            if got:
                return got
        return None
    activate = find_activate(gg) or ""
    how_gg = (lessons.get("경공") or {}).get("how", "")
    if not activate:
        rep.bad("gyeonggong.yml 에서 activate 를 못 찾았다 — 대조할 정본이 없다")
    elif "경공" not in lessons:
        # ★3차 개정 — 경공 예고 관문은 제거됐다 (새 몸은 전원 범인 · 아무도 못 하는 예고 전용이었다)
        rep.good("경공 과제 없음 — 예고는 개화 때 세계가 한다 (3차 개정)")
    else:
        # 정본에서 조작의 낱말을 뽑아 대기실 문장에 다 들어 있는지 본다 (손으로 '달리며 점프' 라 안 쓴다)
        words = [w for w in re.findall(r"[가-힣]+", activate.split("(")[0]) if len(w) >= 2]
        missing = [w for w in words if w not in how_gg.replace("§f", "").replace("§7", "")]
        if missing:
            rep.bad(f"경공 과제 문장이 gyeonggong.yml activate({activate.split('(')[0].strip()!r}) 와 "
                    f"어긋난다 — 빠진 말: {missing}")
        else:
            rep.good(f"경공 과제 = gyeonggong.yml activate ({' '.join(words)})")
        # 코드가 실제로 그 조건을 보는가 — ★ 발동이 **손가락**으로 옮겨간 뒤로는 '흉내'를 보면 안 된다.
        #   구판은 isSprinting() && !isOnGround() 를 봤다: 그건 그냥 **달리다 뛴 몸**이다.
        #   지금은 경공이 **실제로 켜졌는가**를 그 주인(GyeonggongListener.riding)에게 묻는다.
        if not re.search(r"\.riding\(player\)[\s\S]{0,80}?!\s*player\.isOnGround\(\)", code):
            rep.bad("경공 감지가 '발동'이 아니라 '흉내'를 본다 — "
                    "gyeonggong().riding(player) && !isOnGround() 를 안 본다 "
                    "(달리며 점프하는 흉내로 과제가 통과되면, 과제가 가르치는 것이 거짓이 된다)")
        else:
            rep.good("코드가 달림+뜸을 본다")

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

    for lid, l in lessons.items():
        if l.get("detect") != "명령":
            continue
        cmd = l.get("command")
        # 과제는 **마크에서 친다** (PlayerCommandPreprocessEvent 가 본다) — 디스코드 것으로 때울 수 없다
        if cmd not in mark:
            rep.bad(f"과제 '{lid}' 가 마크에 없는 명령을 가르친다: /혼천 {cmd} "
                    f"(MvtCommand 에 case \"{cmd}\" 가 없다)")
        else:
            rep.good(f"/혼천 {cmd} — MvtCommand 에 실재한다")

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

    # ── 손/격 조작이 SkillListener 의 조작표와 같은가 (★과제가 있을 때만 — 3차 개정 이관)
    gyeok = lessons.get("격") or {}
    sl = source("SkillListener.java")
    table = sl[:6000]
    if "격" not in lessons:
        rep.good("격 과제 없음 — 격 두름은 개화 뒤의 것이다 (3차 개정)")
    elif "Shift + 우클릭" in table:
        if "Shift + 우클릭" not in gyeok.get("how", ""):
            rep.bad("격 과제가 SkillListener 조작표('Shift + 우클릭')와 다른 조작을 가르친다: "
                    f"{gyeok.get('how','')!r}")
        else:
            rep.good("격 = Shift + 우클릭 (SkillListener 조작표와 일치)")
    else:
        rep.warn("SkillListener 조작표에서 'Shift + 우클릭' 을 못 찾았다 — 대조를 못 했다")

    son = lessons.get("손") or {}
    if "손" not in lessons:
        rep.good("손 과제 없음 — 때리는 법은 본토 첫 사냥이 가르친다 (3차 개정)")
    elif "좌클릭" in table and "좌클릭" not in son.get("how", ""):
        rep.bad("손 과제가 조작표('좌클릭')와 다른 조작을 가르친다")
    else:
        rep.good("손 = 좌클릭 (조작표와 일치)")

    # ── 읽는 것이 아니라 해 보는 것인가: 모든 과제에 실제 감지가 배선돼 있는가
    detectors = {
        "허수아비_타격": r"KEY_DUMMY[\s\S]{0,600}?bump\(player,\s*\"손\"\)",
        "방어_몸짓": r"watchGestures[\s\S]{0,900}?bump\(",
        "격_태세_순환": r"watchArmed[\s\S]{0,900}?bump\(",
        "경공_발동": r"watchGyeonggong[\s\S]{0,600}?bump\(",
        "명령": r"PlayerCommandPreprocessEvent[\s\S]{0,900}?bump\(",
    }
    for lid, l in lessons.items():
        det = l.get("detect")
        pat = detectors.get(det)
        if not pat:
            rep.bad(f"과제 '{lid}' 의 detect({det!r}) 가 코드에 감지기가 없다 — 표지판일 뿐이다")
        elif not re.search(pat, code):
            rep.bad(f"과제 '{lid}' 의 감지기({det})가 코드에서 bump() 로 이어지지 않는다 — "
                    "해도 안 닫힌다 (읽는 것으로 끝난다)")
        else:
            rep.good(f"과제 '{lid}' — {det} 를 실제로 본다")

    audit_combo(rep, lessons, sl)
    audit_capability(rep, ante, lessons, code)
    audit_discord(rep, ante, lessons)


# ═══════════════════════════════════════════════════════════════════════════
#  ③-a 콤보 — ★ **그림의 리듬을 입력의 문법이라 가르치면 안 된다**
# ═══════════════════════════════════════════════════════════════════════════
#
# 2026-07-13. 공격이 **참격**이 됐다: 획이 호를 그리며 돌고, 연타하면 방향이 바뀐다.
# 그런데 옛 손 과제는 이렇게 적혀 있었다:
#
#     "검을 든 손이 알아서 초식을 낸다 (1·2타 → 3타)"
#
# 그것은 **육합검을 배운 손**의 이야기다. 나루에 서는 몸은 무공이 백지고, 그 손은
# SkillListener.basicSwing 을 탄다 — 그 코드가 제 주석에 **못 박아 뒀다**:
#
#     "【함정 ②를 지킨다】 이것은 **콤보가 아니다.** … 우리 전투의 문법은 **몸짓이 곧 선택**이고,
#      콤보 창은 그 삼문을 잡아먹는다. 여기 있는 것은 **숫자 하나**다: 연타하면 획의 **방향만** 바뀐다."
#
# 코드가 "콤보가 아니다"라고 세 번 적어 둔 것을 **튜토리얼이 콤보라고 가르치고 있었다.**
# 이 눈은 그 어긋남을 잡는다 — 손으로 답을 쓰지 않고, **코드의 선언**과 **등록부의 순번**에서 읽는다.

def audit_combo(rep: Report, lessons: dict, skill_listener: str) -> None:
    if "손" not in lessons:
        return   # 3차 개정 — 손 과제 이관 (콤보 오해의 눈은 과제 문장이 있을 때의 것이다)
    son = lessons.get("손") or {}
    how = son.get("how", "")

    # ① 코드가 "콤보가 아니다"라고 선언했는가 — 그것이 이 눈의 근거다 (없으면 대조할 정본이 없다)
    declares = re.search(r"이것은\s*</?b>?\s*콤보가\s*아니다", strip_comments(skill_listener) or "") \
        or "콤보가 아니다" in skill_listener
    if not declares:
        rep.warn("SkillListener 가 '콤보가 아니다'라고 선언하지 않았다 — 기본 손이 콤보인지 아닌지 "
                 "대조할 정본이 없다 (전투 담당이 문법을 바꿨다면 이 눈을 고쳐라)")
        return
    rep.good("SkillListener 선언: 기본 손은 **콤보가 아니다** (획의 방향만 바뀐다)")

    # ② 그런데 과제가 콤보를 가르치는가
    liars = [w for w in ("콤보", "1타", "2타", "3타", "1·2타", "연계기") if w in how]
    if liars:
        rep.bad(f"손 과제가 **콤보**를 가르친다 {liars} — 코드는 '이것은 콤보가 아니다'라고 못 박았다. "
                "무공 없는 손(나루의 모든 손)은 basicSwing 을 타고, 연타는 **획의 방향만** 바꾼다. "
                "외울 입력 문법이 없는데 있다고 가르치면 그것이 거짓말이다")
    else:
        rep.good("손 과제가 콤보 문법을 가르치지 않는다")

    # ③ 순번의 **그림**은 등록부가 정본이다 — skill_motion.yml swing_arcs.cycle
    motion = load_yaml("skill_motion.yml")
    def find_cycle(d):
        if not isinstance(d, dict):
            return None
        if "swing_arcs" in d and isinstance(d["swing_arcs"], dict):
            return d["swing_arcs"].get("cycle")
        for v in d.values():
            got = find_cycle(v)
            if got:
                return got
        return None
    cycle = find_cycle(motion) or []
    if not cycle:
        rep.warn("skill_motion.yml 에서 swing_arcs.cycle 을 못 찾았다 — 획의 순번을 대조 못 했다")
        return
    # 획 이름의 **머리말**이 과제 문장에 있는가 (횡_좌우 → '횡' · 올려베기 → '올려베기')
    plain = re.sub(r"[§][0-9a-fk-or]", "", how)
    missing = [c for c in cycle if c.split("_")[0] not in plain]
    if missing:
        rep.bad(f"손 과제가 획의 순번을 말하지 않는다 — skill_motion.yml swing_arcs.cycle={cycle} "
                f"인데 문장에 없는 획: {missing}. 연타하면 눈앞에서 방향이 바뀌는데 "
                "과제가 그것을 설명 안 하면, 사람은 그것을 **콤보로 오해한다**")
    else:
        rep.good(f"손 과제 = swing_arcs.cycle {cycle} (그림의 리듬 — 입력 문법 아님)")


# ═══════════════════════════════════════════════════════════════════════════
#  ③-b 능(能) — ★★ **나루에 오는 몸이 못 하는 조작을 시키고 있는가**
# ═══════════════════════════════════════════════════════════════════════════
#
# 오늘 경공 담당이 이 눈에 걸렸다 (과제 문장이 gyeonggong.yml 과 어긋난다고). 그런데 그 눈은
# **문장만** 봤다. 문장이 등록부와 글자 그대로 같아도, **그 조작을 할 수 있는 몸이 아니면**
# 그것은 여전히 거짓말이다 — 그리고 실제로 그랬다:
#
#   player_creation.yml   starting_realm: 범인
#   gyeonggong.yml        realm_ceiling.범인.air_jumps: 0      ← **허공을 못 딛는다**
#   antechamber.yml       경공 과제: "공중에서 점프를 한 번 더"  ← **모든 신참이 못 하는 조작**
#
# 게다가 그 과제에는 requires 가 없어서 applicable() 이 그것을 셌다 —
# **'몸이 알았다'(all_done)가 영영 안 떴다. 아무도 다 끝낼 수 없는 튜토리얼이었다.**
#
# 그래서 이 눈은 **문장이 아니라 몸을 본다**: 나루에 서는 경지가 이 조작을 할 수 있는가.
# 못 하면 requires 가 있어야 하고, unavailable(예고)이 있어야 하고, 코드에 술어가 있어야 한다.

def audit_capability(rep: Report, ante: dict, lessons: dict, code: str) -> None:
    rep.say()
    rep.say("  ③-b 능(能) — ★ 나루에 오는 몸이 **못 하는 조작**을 시키는가")

    # ── 나루에 서는 몸은 누구인가 (등록부가 답한다 — 손으로 '범인'이라 쓰지 않는다)
    pc = load_yaml("player_creation.yml")
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
    realm = find_key(pc, "starting_realm")
    if not realm:
        rep.bad("player_creation.yml 에서 starting_realm 을 못 찾았다 — "
                "나루에 **누가** 서는지 모르면 무엇을 못 하는지도 모른다")
        return
    rep.good(f"나루에 서는 몸 = {realm} (player_creation.yml starting_realm)")

    # ── 그 몸이 무엇을 할 수 있는가 — **등록부 셋에게 묻는다** (코드에 답을 안 적는다)
    #     두를_격   internal_energy.yml realm_gates[경지] ∩ qi_manifestation.yml grades
    #     허공_딛기 gyeonggong.yml realm_ceiling[경지].air_jumps > 0
    gates = (load_yaml("internal_energy.yml").get("realm_gates") or {}).get(realm) or []
    ladder = set((load_yaml("qi_manifestation.yml").get("grades") or {}).keys())
    ceil = (load_yaml("gyeonggong.yml").get("realm_ceiling") or {}).get(realm) or {}
    truth = {
        "두를_격": bool(set(gates) & ladder),
        "허공_딛기": int(ceil.get("air_jumps") or 0) > 0,
    }
    for cap, can in truth.items():
        rep.good(f"{realm} — {cap}: {'가능' if can else '★ 불가'}")

    # ── ① 못 하는 능을 요구하는 과제는 **requires 로 선언**돼 있어야 한다
    #      (과제 id 가 아니라 **능의 이름**으로 묶는다 — 과제 이름을 바꿔도 눈은 안 멀어야 한다)
    needs = {"격": "두를_격", "경공": "허공_딛기"}
    for lid, cap in needs.items():
        l = lessons.get(lid)
        if not l:
            rep.good(f"과제 '{lid}' 없음 — 능({cap}) 대조 불요 (3차 개정 — 못 하는 것을 아예 안 시킨다)")
            continue
        declared = l.get("requires", "")
        if truth[cap]:
            rep.good(f"과제 '{lid}' — {realm} 이 할 수 있다 (requires 는 없어도 된다)")
            continue
        if declared != cap:
            rep.bad(f"★★ 과제 '{lid}' 가 **{realm} 이 못 하는 조작**을 시킨다 ({cap} 불가). "
                    f"requires: {cap} 이 없다 (지금 값 {declared!r}) — "
                    f"나루에 오는 **모든 사람**이 이 과제를 영영 못 닫는다. "
                    f"applicable() 이 그것을 세므로 **all_done('몸이 알았다')이 영영 안 뜬다**")
        else:
            rep.good(f"과제 '{lid}' — 못 하는 몸에게는 예고로 바뀐다 (requires: {cap})")

    # ── ② requires 를 단 과제는 **예고 문장**이 있어야 한다 (못 하는 사람에게 침묵하지 않는다)
    #      그리고 그 예고는 **못 하는 조작을 시키면 안 된다** (예고인데 명령이면 그것도 거짓말이다)
    for lid, l in lessons.items():
        cap = l.get("requires", "")
        if not cap:
            continue
        if not l.get("unavailable"):
            rep.bad(f"과제 '{lid}' 에 requires({cap}) 는 있는데 unavailable(예고)이 없다 — "
                    "못 하는 사람의 판이 **비어 버린다**")
        else:
            plain = re.sub(r"[§][0-9a-fk-or]", "", l["unavailable"])
            if re.search(r"(눌러라|밟아라|쳐라|뛰어라|해 보아라|바꿔라)", plain):
                rep.bad(f"과제 '{lid}' 의 예고(unavailable)가 **명령형**이다: {plain!r} — "
                        "못 하는 사람에게 하라고 시킨다. 예고는 시키는 것이 아니라 **알리는 것**이다")
            else:
                rep.good(f"과제 '{lid}' 예고 — 시키지 않고 알린다")

    # ── ③ 등록부가 적은 능의 이름이 **코드에 술어로 있는가** (지어낸 이름을 잡는다)
    declared_caps = {l.get("requires") for l in lessons.values() if l.get("requires")}
    body = body_of(code, r"private boolean capable\([^)]*\)")
    if body is None:
        rep.bad("Antechamber.capable(Player, Lesson) 이 없다 — "
                "등록부가 능(requires)을 적는데 코드에 그것을 판단할 술어가 없다")
        return
    for cap in sorted(declared_caps):
        if not re.search(r'case\s+"' + re.escape(cap) + r'"\s*->', body):
            rep.bad(f"등록부가 지어낸 능의 이름이다: requires: {cap} — "
                    f"capable() 에 case \"{cap}\" 이 없다. 코드는 이 능을 **모른다** "
                    "(그래서 '못 한다'로 답하고, 그 과제는 아무에게도 안 뜬다)")
        else:
            rep.good(f"능 '{cap}' — capable() 에 술어가 있다")

    # ── ④ 그 술어들이 **제 주인에게 묻는가** (숫자를 여기서 지어내면 등록부가 바뀌어도 안 따라온다)
    owners = {
        "두를_격": (r"armableGrades\(", "SkillEngine.armableGrades(경지)"),
        "허공_딛기": (r"ceiling\([^)]*\)\.airJumps\(\)", "Gyeonggong.ceiling(경지).airJumps()"),
    }
    for cap in sorted(declared_caps):
        pat, who = owners.get(cap, (None, None))
        if not pat:
            continue
        if not re.search(pat, body):
            rep.bad(f"능 '{cap}' 의 술어가 제 주인({who})에게 묻지 않는다 — "
                    "가부를 여기서 지어내고 있다. 등록부가 바뀌면 대기실만 거짓말하게 된다")
        else:
            rep.good(f"능 '{cap}' → {who} 에게 묻는다")

    # ── ⑤ 못 하는 과제를 **세지 않는가** (all_done 이 영영 안 뜨던 바로 그 병)
    app = body_of(code, r"private List<Lesson> applicable\([^)]*\)")
    if app is None:
        rep.bad("applicable() 이 없다 — 못 하는 과제를 걸러내는 곳이 없다")
    elif not re.search(r"(lacks|capable)\(", app):
        rep.bad("applicable() 이 능(requires)을 안 본다 — **못 하는 것을 못 했다고 센다.** "
                "그러면 all_done('몸이 알았다')이 영영 안 뜬다")
    else:
        rep.good("applicable() 이 못 하는 과제를 세지 않는다")


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

def audit_discord(rep: Report, ante: dict, lessons: dict) -> None:
    link = lessons.get("접속") or {}
    # ★ **how 만 본다** (done 이 아니다). done 은 이미 밟은 뒤에 뜬다 — 튕기고 나서 알려 주는 것은
    #   예고가 아니다. 선행 문은 **밟기 전에** 판(how)에 적혀 있어야 한다.
    how = re.sub(r"[§][0-9a-fk-or]", "", link.get("how", ""))

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
            rep.bad(f"접속 과제가 **선행 문**을 말하지 않는다 — 봇은 캐릭터가 없으면 "
                    f"'/혼천 {cmd} 부터' 라며 되돌려보낸다. 대기실이 그것을 **밟기 전에** 안 적으면, "
                    f"발판을 밟은 사람이 **거기서 튕긴다**")
        else:
            rep.good(f"접속 과제가 선행 문(/혼천 {cmd})을 밟기 전에 말한다")

    # ★★ **접합의 흐름을 대기실이 적지 않는다** — 그것은 접합 담당의 몫이고, **지금 바뀌는 중이다**
    #   (코드 방식 폐기 → 초대 링크 + 닉네임 + 수락 창). 발판은 `/혼천 접속` 을 **대신 쳐 줄 뿐**이고,
    #   그 명령이 무엇을 말하든 그대로 흐른다. 여기에 흐름을 적어 두면 **다음 주에 거짓말이 된다.**
    doomed = [w for w in ("코드 복사", "코드 칸", "붙여넣", "1회용", "10분") if w in how]
    if doomed:
        rep.bad(f"접속 과제가 **접합의 흐름**을 적고 있다 {doomed} — 그것은 대기실의 몫이 아니다. "
                "접합 방식은 바뀐다 (코드 → 초대 링크·닉네임·수락 창). 발판은 명령을 대신 쳐 줄 뿐이고, "
                "**화면이 말하게 두어야** 대기실이 늙지 않는다")
    else:
        rep.good("접속 과제는 접합의 흐름을 적지 않는다 (화면이 말한다 — 대기실은 안 늙는다)")

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
    lessons = {l["id"]: l for l in ((ante.get("lessons") or {}).get("list") or []) if "id" in l}

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

    # ★ 판이 과제와 **같은 말**을 하는가.
    #   v2 에서 판은 config 에 따로 배치하지 않는다 — **관문(stations)이 곧 판의 자리**다.
    #   그래서 "판이 관문과 다른 자리에 있다"는 사고가 원천적으로 불가능하다. 대신 두 가지를 조인다:
    #     ① 판의 문장은 과제의 title/how 에서 **그대로** 나온다 (panelText 가 유일한 출처)
    #     ② 관문이 가리키는 과제가 실재한다
    pt = body_of(code, r"private List<String> panelText\(Station s, boolean unavailableVariant\)")
    if pt is None:
        rep.bad("panelText() 를 못 찾았다 — 판의 문장이 어디서 오는지 알 수 없다")
    elif not (re.search(r"l\.title\(\)", pt) and re.search(r"l\.how\(\)", pt)
              and re.search(r"l\.unavailable\(\)", pt)):
        rep.bad("판의 문장이 과제의 title/how/unavailable 에서 나오지 않는다 — 판이 딴말을 할 수 있다")
    else:
        rep.good("판의 문장 = 과제의 title/how 그대로 (panelText 가 유일한 출처 — 딴말이 불가능하다)")

    for st in (ante.get("stations") or []):
        lid = st.get("lesson") or ""
        if lid and lid not in lessons:
            rep.bad(f"관문 '{st.get('id')}' 이 없는 과제를 가리킨다: {lid}")

    # 같은 자리(관문)에 판이 둘인 경우는 격뿐이고, 둘은 **서로 배타적**이어야 한다
    # (범인에게 "격을 둘러라"가 보이면 그것이 거짓말이다)
    rp = body_of(code, r"void refreshPanels\(Player player\)")
    sh = body_of(code, r"private void show\(Player player, String panelId, boolean visible\)")
    if sh is None or "hideEntity(plugin" not in sh or "showEntity(plugin" not in sh:
        rep.bad("판을 사람마다 감추고 보이는 손(show/hideEntity)이 없다")
    elif rp is None or "show(player" not in rp:
        rep.bad("refreshPanels() 가 판을 사람마다 가르지 않는다")
    elif "_없음" not in rp or not re.search(r"(lacks|capable)\(", rp):
        rep.bad("능(requires) 있는 관문의 두 판(가능/예고)을 사람마다 갈라 주지 않는다 — "
                "못 하는 몸에게 '하라'가 보인다 (거짓말)")
    else:
        rep.good("판은 사람마다 보이고 안 보인다 (할 수 있는 몸에게는 how · 없는 몸에겐 예고)")

    expected = len(ante.get("stations") or []) + sum(
        1 for st in (ante.get("stations") or [])
        if (lessons.get(st.get("lesson") or "") or {}).get("requires"))
    if cap and expected > cap:
        rep.bad(f"글판 {expected}개 > 상한 {cap}")
    else:
        rep.good(f"글판 {expected}개 (관문 {len(ante.get('stations') or [])} "
                 f"+ 예고 판 {expected - len(ante.get('stations') or [])}) ≤ 상한 {cap}")


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

    def lamp_side(self, x):
        if not (self.x0 <= x <= self.x1):
            return None
        n = x - self.x0
        if n % self.post_every != 0:
            return None
        if not self.post_alt:
            return self.post_z
        return self.post_z if (n // self.post_every) % 2 == 0 else -self.post_z

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

    # ── 빛 (해석 모형: 밝기 = 15 − 맨해튼거리, 7 미만이면 암흑) ──
    def sources(self):
        out = []
        for x in range(self.x0, self.x1 + 1):
            side = self.lamp_side(x)
            if side is not None:
                out.append((x, self.deck + 2, self.rz + side))
        for s in self.stations:
            if s["id"] in self.brazier_st:
                bx, bz = self.brazier_at(s)
                out.append((bx, self.deck + 1, bz))
        if self.hut_lantern:
            out.append(((self.hx[0] + self.hx[1]) // 2, self.deck + self.wall_h,
                        (self.hz[0] + self.hz[1]) // 2))
        return out

    def light_at(self, x, z, srcs):
        foot = self.deck + 1
        best = 0
        for sx, sy, sz in srcs:
            lvl = 15 - (abs(sx - x) + abs(sy - foot) + abs(sz - z))
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

    # 첫 관문이 스폰이어야 한다 (눈을 뜬 자리가 곧 첫 관문)
    if g.stations[0]["x"] != g.spawn[0]:
        rep.warn(f"눈 뜨는 자리(x={g.spawn[0]}) 와 첫 관문(x={g.stations[0]['x']}) 이 다르다")

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
    rep.say("  ⑦ 흐름 — 한 번에 하나만 (과제 여섯이 동시에 보이면 그것은 안내가 아니라 선택지다)")
    les = ante.get("lessons") or {}
    if not les.get("one_at_a_time"):
        rep.bad("lessons.one_at_a_time 이 참이 아니다 — 관문이 전부 한꺼번에 보인다 (1차판의 병)")
    else:
        rep.good("lessons.one_at_a_time: true")

    body = body_of(code, r"void refreshPanels\(Player player\)")
    if body is None:
        rep.bad("refreshPanels() 를 못 찾았다")
    else:
        if "currentStation" not in body or "<= current" not in body:
            rep.bad("refreshPanels() 가 관문 번호로 가리지 않는다 — 앞 관문이 안 닫혀도 다음이 보인다")
        else:
            rep.good("refreshPanels() — 지금 관문까지만 보인다 (i <= current)")
        # 감추는 손은 show() 안에 있다 — **이름만 보지 말고 속을 보자** (그 병으로 두 번 데였다)
        sh = body_of(code, r"private void show\(Player player, String panelId, boolean visible\)")
        if sh is None or "hideEntity" not in sh:
            rep.bad("앞 관문의 판을 감추지 않는다 — 과제가 전부 한꺼번에 보인다")
        else:
            rep.good("앞 관문의 판은 감춘다 (hideEntity)")

    cur = body_of(code, r"private int currentStation\(Player player\)")
    if cur is None or "passed(" not in (cur or ""):
        rep.bad("currentStation() 이 '지나온 관문'을 안 본다")
    else:
        rep.good("currentStation() = 아직 안 닫힌 첫 관문")

    # ★ 못 하는 관문(범인의 격)에서 길이 막히면 안 된다 — 그것이 바로 '갇힘'이다
    ps = body_of(code, r"private boolean passed\(Player player, Station s\)")
    if ps is None:
        rep.bad("passed() 를 못 찾았다")
    elif not re.search(r"(lacks|capable)\(", ps):
        rep.bad("passed() 가 '못 하는 관문'을 지나가게 하지 않는다 — "
                "범인이 격·경공 관문에서 막히면 그 뒤 관문을 영영 못 본다")
    else:
        rep.good("못 하는 관문(범인의 격·경공)은 '지나간 것'으로 친다 — 길이 안 막힌다")

    # ★★ 글판이 안 보이는 것과 **문이 잠기는 것**은 다른 것이다. 종은 언제나 울려야 한다
    cross = body_of(code, r"public void cross\(Player player\)")
    if cross is None:
        rep.bad("cross() 를 못 찾았다")
    elif any(w in cross for w in ("currentStation", "refreshPanels", "passed(", "shownThrough",
                                  "progress")):
        rep.bad("cross() 가 과제 진척을 본다 — ★ 과제가 문을 잠근다. "
                "글판은 안내이지 자물쇠가 아니다 (과제 하나가 깨진 날 사람이 갇힌다)")
    else:
        rep.good("cross() 는 과제를 보지 않는다 — 글판이 하나도 안 열려도 종은 울린다")


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

    # ① 주 동선(잔교·마당) — 밝아야 한다
    main_lv = [g.light_at(x, z, srcs) for (x, z) in walk]
    main_dark = 100.0 * sum(1 for v in main_lv if v < 7) / len(main_lv)
    lim = float(li.get("main_dark_max_pct", 15))
    if main_dark > lim:
        rep.bad(f"주 동선 암흑 {main_dark:.1f}% > {lim:.0f}% — 걸어야 할 길이 어둡다 "
                "(빛이 길을 안 가리킨다)")
    else:
        rep.good(f"주 동선 암흑 {main_dark:.1f}% ≤ {lim:.0f}% — 길은 밝다")

    # ② 어둠의 하한 — ★ 어두운 곳이 **없으면 그것도 실패다** (등롱 도배)
    allc = [(x, z) for x in range(g.mx[0], g.mx[1] + 1) for z in range(g.mz[0], g.mz[1] + 1)]
    all_lv = [g.light_at(x, z, srcs) for (x, z) in allc]
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

    # 손으로 친 것과 발판으로 친 것이 같은 문을 지나야 한다
    if not re.search(r"creditCommand\(player, parts\[1\], parts\.length - 2\)", code):
        rep.warn("손/발판 두 경로가 같은 기입 함수를 안 쓴다 — 언젠가 둘이 어긋난다")
    else:
        rep.good("손으로 친 것과 발판으로 친 것이 같은 함수(creditCommand)를 지난다")

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
        hit_lesson = any((l or {}).get("detect") == "허수아비_타격"
                         for l in ((ante.get("lessons") or {}).get("list") or []))
        if hit_lesson:
            rep.bad("허수아비가 하나도 등록돼 있지 않다 — 대기실에서 때릴 상대가 없다 "
                    "(손 과제는 '허수아비를 좌클릭으로 쳐라'라고 가르친다)")
        else:
            rep.good("허수아비 0몸 — 타격 과제가 없다 (3차 개정: 순수 문지방)")
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

    # 손 과제가 있는 관문 마당에 허수아비가 있어야 한다 (가르치는 곳에 상대가 있어야 한다)
    lessons = {l["id"]: l for l in ((ante.get("lessons") or {}).get("list") or []) if "id" in l}
    for st in (ante.get("stations") or []):
        lid = st.get("lesson") or ""
        if (lessons.get(lid) or {}).get("detect") != "허수아비_타격":
            continue
        here = [d for d in dummies
                if g.station_at(*(d.get("pos") or [999, 999])) is not None
                and g.station_at(*(d.get("pos") or [999, 999]))["id"] == st["id"]]
        if not here:
            rep.bad(f"관문 '{st['id']}' 은 허수아비를 치라고 가르치는데 그 마당에 허수아비가 없다")
        else:
            rep.good(f"관문 '{st['id']}' 의 마당에 허수아비 {len(here)}몸 — 가르치는 곳에 상대가 있다")


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
    lessons = {l["id"]: l for l in ((ante.get("lessons") or {}).get("list") or []) if "id" in l}
    want_panels = len(stations) + sum(
        1 for st in stations
        if (lessons.get(st.get("lesson") or "") or {}).get("requires"))

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

    if panels_in_world < want_panels:
        rep.bad(f"세계에 글판이 {panels_in_world}개뿐이다 (등록부는 {want_panels}개)")
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
