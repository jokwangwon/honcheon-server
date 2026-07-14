#!/usr/bin/env python3
"""motion_audit 의 하드코딩 눈을 일부러 깨뜨려 시험한다.

【왜】 B-026 에서 [대조] 청구 면제(진단 대조군의 리터럴)를 눈에 가르쳤다. 면제를 가르친 눈은
반드시 이 질문에 답해야 한다: **표식 없는 하드코딩은 여전히 잡는가?** — 못 잡으면 그것은
면제가 아니라 실명(失明)이다. 여기의 변이들이 그 답을 강제한다.
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))

import motion_audit  # noqa: E402

DISPLAY = ROOT / "server-mvt" / "src" / "main" / "java" / "com" / "honcheon" / "mvt" / "SkillDisplay.java"


def main():
    cases = []

    def case(name, ok, detail=""):
        cases.append((name, ok, detail))

    # ① ★ 변이 — 표식 없는 하드코딩은 여전히 잡는다 (이 칸이 이 selftest 의 존재 이유다)
    hard, claimed = motion_audit.hardcoded_enums(
        "world.spawnParticle(Particle.FLAME, at, 3);")
    case("표식 없는 하드코딩은 여전히 잡는다",
         hard == ["Particle.FLAME"] and claimed == 0, f"{hard} · 청구 {claimed}")

    # ② [대조] 주석이 붙은 줄만 면제 — 그리고 면제는 **세어진다** (예외 자체가 정보다)
    hard, claimed = motion_audit.hardcoded_enums(
        'rows.add(new Row("맨 종이", new ItemStack(Material.PAPER),   // [대조] 진단 대조군')
    case("[대조] 주석 줄은 면제되고 세어진다",
         hard == [] and claimed == 1, f"{hard} · 청구 {claimed}")

    # ③ 문자열 속 [대조] 는 면제가 아니다 — 면제는 주석의 것이다 (라벨만 붙이고 빠져나가지 못한다)
    hard, claimed = motion_audit.hardcoded_enums(
        'rows.add(new Row("[대조] 맨 종이", new ItemStack(Material.PAPER)));')
    case("문자열 속 [대조] 는 안 쳐준다",
         hard == ["Material.PAPER"] and claimed == 0, f"{hard} · 청구 {claimed}")

    # ④ 청구 한 줄이 딴 줄의 죄를 못 덮는다 — 면제는 줄 단위다
    hard, claimed = motion_audit.hardcoded_enums(
        "a(Material.PAPER);   // [대조] 진단 대조군\n"
        "b(Sound.ENTITY_BLAZE_HURT);")
    case("청구 줄이 다른 줄의 하드코딩을 못 덮는다",
         hard == ["Sound.ENTITY_BLAZE_HURT"] and claimed == 1, f"{hard} · 청구 {claimed}")

    # ⑤ 실물 — SkillDisplay 의 대조군은 정확히 3건이 청구돼 있고, 그 밖의 위반은 없다
    disp = DISPLAY.read_text(encoding="utf-8")
    hard, claimed = motion_audit.hardcoded_enums(disp)
    case("실물 SkillDisplay: 위반 0 · [대조] 청구 3",
         hard == [] and claimed == 3, f"{hard[:5]} · 청구 {claimed}")

    # ⑥ ★ 변이 — 실물에 표식 없는 하드코딩을 주입하면 다시 짖는다
    hard, _ = motion_audit.hardcoded_enums(
        disp + "\n        world.playSound(at, Sound.ENTITY_BLAZE_HURT, 1f, 1f);")
    case("실물 주입 변이를 잡는다", hard == ["Sound.ENTITY_BLAZE_HURT"], str(hard[:5]))

    ok = True
    print("══ motion_audit 의 눈을 시험한다 — [대조] 면제가 탐지력을 죽이지 않는가 ══")
    for name, caught, detail in cases:
        print(("✓ " if caught else "✗ ") + name)
        if not caught:
            print("   " + detail)
        ok &= caught
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
