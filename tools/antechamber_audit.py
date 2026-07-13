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
    dest = body_of(code, r"private Location destination\(\)")
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

    # 접합이 오프라인에서 확정된 경우: 나루에 서 있는 linked 는 그대로 건너야 한다
    if not re.search(r"onJoin[\s\S]{0,700}?isAntechamber\(player\.getWorld\(\)\)[\s\S]{0,120}?depart\(", code):
        rep.bad("onJoin 에서 '나루에 서 있는 접합자'를 건네주는 경로가 없다 — "
                "디스코드에서 확정하고 로그아웃한 사람이 나루에 남는다")
    else:
        rep.good("없는 사이에 이름이 올랐으면, 접속하는 순간 건넌다")

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
    else:
        # 정본에서 조작의 낱말을 뽑아 대기실 문장에 다 들어 있는지 본다 (손으로 '달리며 점프' 라 안 쓴다)
        words = [w for w in re.findall(r"[가-힣]+", activate.split("(")[0]) if len(w) >= 2]
        missing = [w for w in words if w not in how_gg.replace("§f", "").replace("§7", "")]
        if missing:
            rep.bad(f"경공 과제 문장이 gyeonggong.yml activate({activate.split('(')[0].strip()!r}) 와 "
                    f"어긋난다 — 빠진 말: {missing}")
        else:
            rep.good(f"경공 과제 = gyeonggong.yml activate ({' '.join(words)})")
        # 코드가 실제로 그 조건을 보는가: 달림(isSprinting) + 뜬 몸(!isOnGround)
        if not re.search(r"isSprinting\(\)[\s\S]{0,80}?!\s*player\.isOnGround\(\)", code):
            rep.bad("경공 감지가 '달리며 점프'가 아니다 — isSprinting() && !isOnGround() 를 안 본다")
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

    # ── 격: 못 하는 것을 가르치지 않는가
    gyeok = lessons.get("격") or {}
    if not gyeok.get("requires_armable_grade"):
        rep.bad("격 과제에 requires_armable_grade 가 없다 — 범인(경지 게이트 미통과)에게 "
                "'Shift+우클릭으로 격을 둘러라'를 가르치게 된다. 그것은 존재하지 않는 조작이다")
    else:
        rep.good("격 과제는 두를 격이 있는 몸에게만 뜬다 (requires_armable_grade)")
    if not re.search(r"armableGrades\(", code):
        rep.bad("코드가 SkillEngine.armableGrades() 를 보지 않는다 — 격의 가부를 지어내고 있다")
    else:
        rep.good("코드가 SkillEngine.armableGrades(경지) 로 격의 가부를 묻는다")
    if not gyeok.get("unavailable"):
        rep.warn("격 과제에 unavailable 문장이 없다 — 못 하는 사람에게 아무 말도 안 한다")

    # ── 손/격 조작이 SkillListener 의 조작표와 같은가
    sl = source("SkillListener.java")
    table = sl[:6000]
    if "Shift + 우클릭" in table:
        if "Shift + 우클릭" not in gyeok.get("how", ""):
            rep.bad("격 과제가 SkillListener 조작표('Shift + 우클릭')와 다른 조작을 가르친다: "
                    f"{gyeok.get('how','')!r}")
        else:
            rep.good("격 = Shift + 우클릭 (SkillListener 조작표와 일치)")
    else:
        rep.warn("SkillListener 조작표에서 'Shift + 우클릭' 을 못 찾았다 — 대조를 못 했다")

    son = lessons.get("손") or {}
    if "좌클릭" in table and "좌클릭" not in son.get("how", ""):
        rep.bad("손 과제가 조작표('좌클릭 = 기본 무공 콤보')와 다른 조작을 가르친다")
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
        ci = build_body.find("clearPanels(w)")
        si = build_body.find("spawnPanels(w)")
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
    elif "_없음" not in rp or "armable" not in rp:
        rep.bad("격의 두 판(가능/불가)을 사람마다 갈라 주지 않는다 — "
                "범인에게 '격을 둘러라'가 보인다 (거짓말)")
    else:
        rep.good("판은 사람마다 보이고 안 보인다 (격: 두를 수 있는 몸에게만 · 없는 몸에겐 대체 판)")

    expected = len(ante.get("stations") or []) + sum(
        1 for st in (ante.get("stations") or [])
        if (lessons.get(st.get("lesson") or "") or {}).get("requires_armable_grade"))
    if cap and expected > cap:
        rep.bad(f"글판 {expected}개 > 상한 {cap}")
    else:
        rep.good(f"글판 {expected}개 (관문 {len(ante.get('stations') or [])} + 격 대체 1) ≤ 상한 {cap}")


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
    elif "requiresArmable" not in ps or "armable(player)" not in ps:
        rep.bad("passed() 가 '못 하는 관문'을 지나가게 하지 않는다 — "
                "범인이 격 관문에서 막히면 그 뒤 관문을 영영 못 본다")
    else:
        rep.good("못 하는 관문(범인의 격)은 '지나간 것'으로 친다 — 길이 안 막힌다")

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


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--verbose", "-v", action="store_true", help="통과 항목도 보인다")
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
    audit_panels(rep, ante, code)
    audit_road(rep, ante, code)
    audit_flow(rep, ante, code)
    audit_light(rep, ante, code)
    audit_plates(rep, ante, code)
    audit_conventions(rep, code, raw_cfg)

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
