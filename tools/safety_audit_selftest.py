#!/usr/bin/env python3
"""안전 지역 감사의 **자기 시험** — 눈을 시험하는 눈 (B-006).

"위반 0건"은 두 가지 뜻이다: **문이 닫혔다**, 또는 **눈이 멀었다.** 둘은 화면에서 똑같이 보인다.
이 프로젝트에서 눈은 이미 여러 번 거짓말했다 (B-001·B-002 — 리팩터가 진실을 옮겼는데
눈이 안 따라간 감사가 지금도 둘 있다). 그래서 safety_audit.py 에게 **일부러 거짓말을 먹인다**:
독자를 죽이고, 길목의 문을 뜯고, 어휘를 비틀고, 합의의 예외를 지운다 —
그때마다 눈이 **실제로 잡는지** 본다. 잡으면 ✅, 못 잡으면 ❌. 끝나면 전부 되돌린다.

사용법:  python3 tools/safety_audit_selftest.py
종료 코드: 눈이 하나라도 놓치면 1.
"""
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CFG = os.path.join(ROOT, "config/training.yml")
SRC = os.path.join(ROOT, "server-mvt/src/main/java/com/honcheon/mvt/SkillListener.java")
AUDIT = os.path.join(ROOT, "tools/safety_audit.py")

# (이름, 파일, 원본조각, 바꿀조각, 잡아야 하는 말의 조각)
MUTATIONS = [
    ("① 독자를 죽인다 — 코드가 다른 섹션을 연다 (등록부는 그대로인데 아무도 안 읽는다)", SRC,
     '                    "location_safety");',
     '                    "location_safety_GHOST");',
     "location_safety 섹션"),

    ("② 길목 맨 앞의 문을 뜯는다 — onMelee 가 더는 묻지 않는다 (바닐라·화살이 샌다)", SRC,
     "        if (assailant != null && safetyBlocks(assailant, target)) {",
     "        if (false) {",
     "onMelee — 길목의 맨 앞"),

    ("③ 초식의 문을 뜯는다 — admit 이 안전 지역의 사람을 도로 담는다 (광역이 샌다)", SRC,
     "            if (t instanceof Player && safetyBlocks(player, t)) {",
     "            if (false) {",
     "admit — 초식의 히트박스"),

    ("④ 어휘를 비튼다 — 등록부의 「안전」 이 사라진다 (게이트 기준어가 붕 뜬다)", CFG,
     "    level: 안전",
     "    level: 안심",
     "정본 어휘"),

    ("⑤ 합의의 예외를 지운다 — 문파 내부의 비무 서열전이 죽는다 (sect_life)", SRC,
     "        if (bouts != null && bouts.isSparring(attacker) && bouts.isSparring(victim)) {",
     "        if (bouts != null && false) {",
     "비무(합의)의 예외"),

    ("⑥ 어디가 안전인지 지운다 — level 은 남는데 매칭 어휘가 빈다 (아무 데도 안전이 아니다)", CFG,
     "    zone_keywords: [관아]",
     "    zone_keywords: []",
     None),   # ★ 이 변이는 archetypes 가 남아 있으므로 **위반이 아니어야** 한다 — 아래 특례

    ("⑦ 유령 원형을 부른다 — 등록부에 없는 원형 이름 (등록제 위반)", CFG,
     "    archetypes: [산채, 녹림석채, 은신처, 수로채, 흑성, 천막, 유배지]",
     "    archetypes: [산채, 녹림석채, 은신처, 수로채, 흑성, 천막, 유배지, 유령원형]",
     "유령"),
]


def run_audit():
    r = subprocess.run([sys.executable, AUDIT], capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr


def caught(out, needle):
    """그 말이 **❌ 로** 찍혔는가 — ✅ 로 찍힌 같은 말에 속으면 안 된다."""
    return any(needle in line and "❌" in line for line in out.splitlines())


def main():
    print("═" * 74)
    print("  안전 지역 감사 — 자기 시험 (일부러 문을 열어 두고, 눈이 잡는지 본다)")
    print("═" * 74)

    base_rc, base_out = run_audit()
    if base_rc != 0:
        print("\n❌ 시작부터 위반이 있다 — 먼저 그것부터 고쳐라. 시험을 멈춘다.\n")
        print(base_out)
        return 1
    print("\n  기준선: 위반 0건 (문이 닫혀 있다) — 이제 하나씩 열어 본다\n")

    backups = {}
    for path in (CFG, SRC):
        backups[path] = path + ".selftest.bak"
        shutil.copy2(path, backups[path])

    missed = []
    try:
        for name, path, old, new, needle in MUTATIONS:
            with open(path, encoding="utf-8") as fh:
                original = fh.read()
            if old not in original:
                print(f"  ⚠️  {name} — 원본 조각을 못 찾았다 (시험이 낡았다): {old[:48]!r}")
                missed.append(name + " (시험이 낡음)")
                continue
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(original.replace(old, new, 1))
            rc, out = run_audit()
            with open(path, "w", encoding="utf-8") as fh:   # 즉시 되돌린다
                fh.write(original)

            if needle is None:
                # ★ 특례 (⑥): 한 어휘를 지워도 다른 어휘(archetypes)가 남으면 문은 산 문이다 —
                #   그때 짖는 눈은 **거짓 경보를 내는 눈**이다. 여기서는 침묵이 정답이다.
                if rc == 0:
                    print(f"  ✅ {name} — 눈이 침묵했다 (남은 어휘가 문을 지킨다 — 거짓 경보 없음)")
                else:
                    print(f"  ❌ {name} — **거짓 경보** (archetypes 가 남아 있는데 짖었다)")
                    missed.append(name + " (거짓 경보)")
                continue

            if rc != 0 and caught(out, needle):
                print(f"  ✅ {name} — 눈이 잡았다")
            elif rc != 0:
                print(f"  ⚠️  {name} — 위반은 났는데 **엉뚱한 것을 잡았다** (기대: “{needle}”)")
                missed.append(name + " (엉뚱한 것을 잡음)")
            else:
                print(f"  ❌ {name} — **눈이 못 잡았다** (위반 0건으로 통과시켰다)")
                missed.append(name)
    finally:
        for path, bak in backups.items():
            shutil.copy2(bak, path)
            os.remove(bak)

    rc, _ = run_audit()
    print("\n" + "─" * 74)
    if rc != 0:
        print("  ❌ 되돌리기 실패 — 파일이 원래대로가 아니다. 손으로 확인하라.")
        return 1
    print("  되돌렸다 — config·소스는 손대지 않은 상태다 (재감사: 위반 0건)")
    if missed:
        print(f"  총평: ❌ 눈이 {len(missed)}건을 놓쳤다 — " + " · ".join(missed))
        print("─" * 74)
        return 1
    print(f"  총평: ✅ {len(MUTATIONS)}건 전부 잡았다 — 이 눈은 볼 수 있다")
    print("─" * 74)
    return 0


if __name__ == "__main__":
    sys.exit(main())
