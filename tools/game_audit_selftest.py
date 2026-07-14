#!/usr/bin/env python3
"""게임 감사의 **자기 시험** — 눈을 시험하는 눈 (B-104).

"위반 0건"은 두 가지 뜻이다: **참조가 전부 해소된다**, 또는 **눈이 멀었다.** 둘은 화면에서 똑같다.
이 저장소에서 눈은 이미 여러 번 거짓말했다 (lint_config 21건 · bridge_audit recoveryDeltas —
리팩터가 진실을 옮겼는데 눈이 안 따라갔다). game_audit 도 예외라고 믿을 이유가 없다.

그래서 game_audit.py 에게 **일부러 거짓말을 먹인다**: 스키마에 없는 `by:` 오탈자를 심고,
유령 NPC 를 세우고, 없는 장소로 사람을 보낸다 — 그때마다 눈이 **실제로 잡는지** 본다.
(B-008 트랙이 수동으로 확인했던 세 변이를 그대로 박제한 것이다.)
잡으면 ✅, 못 잡으면 ❌. 끝나면 전부 되돌린다 (config 는 손대지 않은 상태로 남는다).

정합 린트만 시험한다 (--lint-only) — 세 변이 전부 린트 축의 눈이고, 시뮬은 이 참조들을 안 본다.

사용법:  python3 tools/game_audit_selftest.py
종료 코드: 눈이 하나라도 놓치면 1.
"""
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESET = os.path.join(ROOT, "config/reset.yml")
SIMBEOP = os.path.join(ROOT, "config/simbeop.yml")
NPCS = os.path.join(ROOT, "config/npcs/cheongha_npcs.yml")
AUDIT = os.path.join(ROOT, "tools/game_audit.py")

# (이름, 파일, 원본조각, 바꿀조각, 잡아야 하는 말의 조각)
MUTATIONS = [
    ("① 스키마에 없는 by: 오탈자 (reset.yml — DB 축이 아니면 NPC 로 짖어야 한다)", RESET,
     "{by: owner_id,", "{by: ownner_id,",
     "미등록 NPC 'ownner_id'"),

    ("② 유령 NPC 가 정화를 집행한다 (simbeop.yml by: hyegak → 없는 자)", SIMBEOP,
     "{ by: hyegak,", "{ by: hyegak_ghost,",
     "미등록 NPC 'hyegak_ghost'"),

    ("③ NPC 를 없는 장소에 세운다 (cheongha_npcs.yml location 표류)", NPCS,
     "location: cheongha_inn", "location: ghost_pavilion",
     "미등록 장소 'ghost_pavilion'"),
]


def run_audit():
    r = subprocess.run([sys.executable, AUDIT, "--lint-only"],
                       capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr


def caught(out, needle):
    """그 말이 **❌ 로** 찍혔는가 — ✅ 로 찍힌 같은 말에 속으면 안 된다."""
    return any(needle in line and "❌" in line for line in out.splitlines())


def main():
    print("═" * 74)
    print("  게임 감사 — 자기 시험 (일부러 등록부를 찢어 놓고, 눈이 잡는지 본다)")
    print("═" * 74)

    base_rc, base_out = run_audit()
    if base_rc != 0:
        print("\n❌ 시작부터 위반이 있다 — 먼저 그것부터 고쳐라. 시험을 멈춘다.\n")
        print(base_out)
        return 1
    print("\n  기준선: 위반 0건 (참조가 전부 해소된다) — 이제 하나씩 찢어 본다\n")

    backups = {}
    for path in (RESET, SIMBEOP, NPCS):
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
            try:
                rc, out = run_audit()
            finally:
                with open(path, "w", encoding="utf-8") as fh:   # 즉시 되돌린다
                    fh.write(original)

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
    print("  되돌렸다 — config 는 손대지 않은 상태다 (재감사: 위반 0건)")
    if missed:
        print(f"  총평: ❌ 눈이 {len(missed)}건을 놓쳤다 — " + " · ".join(missed))
        print("─" * 74)
        return 1
    print(f"  총평: ✅ {len(MUTATIONS)}건 전부 잡았다 — 이 눈은 볼 수 있다")
    print("─" * 74)
    return 0


if __name__ == "__main__":
    sys.exit(main())
