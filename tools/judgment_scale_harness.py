#!/usr/bin/env python3
"""옛/새 눈금 동등 대조 하네스 — B-137 C안 제곱 환산층 (판정치 = floor(√원장) · 옛 X ↔ 원장 X²).

★ 무엇을 재나: 같은 판정 상황(능력치·기술·상황보정·NPC 저항)을 옛 눈금과 새 눈금으로
굴려 **등급 분포·성공권·기대 마진이 한 톨도 다르지 않은지** 잰다. 2d6 은 36가지가 전부다 —
해석적 전수 대조, 몬테카를로 불요 (judgment.yml 등급 재설계 주석과 같은 방법).

등록부에서 읽는 것 (하드코딩 금지):
    config/judgment.yml        — result_tiers · npc_fixed_bonus · situation_modifier_cap ·
                                 static_difficulty · attribute 눈금(1~10)
    config/player_creation.yml — attribute_cap_by_realm (경지 캡) · 성별 ±1 (gender.gates.attributes)
    config/internal_energy.yml — 내력 풀 공식 계수 (pool_per_year) — 파생치 보존 검사용

대조 항목:
    ① 판정 전수 대조 — 옛 능력치 1~10 × 기술 × 보정 × 저항 스윕: 등급 분포 동일성
    ② 중간 원장 — 원장 X² ~ (X+1)²−1 구간 전체가 판정치 X 를 내는가 (floor 계약)
    ③ 경지 캡 — cap → cap² 환산이 판정 천장을 보존하는가
    ④ 성별 ±1 — 판정치 산출 **뒤** ±1 (판정층 유지)이 옛 규칙과 동률인가
    ⑤ 마이그레이션 — 옛 실수 원장 x → 새 원장 x²(실수 보존안) · floor(x²)(정수안) 이
       판정치를 보존하는가 (경계값 포함)
    ⑥ 파생치 — 실수치 = √원장 이 옛 실수치를 보존하는가 (내력 풀 공식 왕복 검산)

★ 자기 시험 (--selftest): 일부러 어긋난 환산 네 개를 심어 하네스가 잡는지 본다 —
    선형 ×10 환산 · round(√) 판정 · round(x²) 마이그레이션(경계 침범) · 성별을 √ 앞에 넣기.
    그리고 바른 환산은 위반 0 이어야 한다. "시험 없는 눈은 눈이 아니다."

사용법:  python3 tools/judgment_scale_harness.py            # 전수 대조 + 요약
         python3 tools/judgment_scale_harness.py --selftest  # 눈을 시험하는 눈
"""
import argparse
import math
import os
import sys
from collections import Counter

import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load(rel):
    with open(os.path.join(ROOT, "config", rel), encoding="utf-8") as f:
        return yaml.safe_load(f)


def find_key(d, key):
    """중첩 어디에 있든 그 키의 절을 찾는다 (xp_pacing.py 와 같은 관례)."""
    if isinstance(d, dict):
        if key in d:
            return d[key]
        for v in d.values():
            hit = find_key(v, key)
            if hit is not None:
                return hit
    elif isinstance(d, list):
        for v in d:
            hit = find_key(v, key)
            if hit is not None:
                return hit
    return None


# ─── 등록부 (모듈 로드 시 1회) ───
JUDGMENT = load("judgment.yml")
TIERS = JUDGMENT["result_tiers"]                       # 순서 = 마진 내림차순, 마지막 min null
NPC_BONUS = JUDGMENT["formula"]["npc_fixed_bonus"]     # 7
MOD_CAP = JUDGMENT["formula"]["situation_modifier_cap"]  # 5
ATTR_MIN = JUDGMENT["scales"]["attribute"]["min"]      # 1
ATTR_MAX = JUDGMENT["scales"]["attribute"]["max"]      # 10
CREATION = load("player_creation.yml")
REALM_CAPS = CREATION["attribute_cap_by_realm"]
GENDER_CAP = find_key(CREATION, "gender")["gates"]["attributes"]["cap"]  # 1
POOL_PER_YEAR = find_key(load("internal_energy.yml"), "pool_per_year")   # 3

DICE_2D6 = [(a + b) for a in range(1, 7) for b in range(1, 7)]  # 36가지 전부


def tier_of(margin):
    for t in TIERS:
        if t["min_margin"] is not None and margin >= t["min_margin"]:
            return t["id"]
    return TIERS[-1]["id"]


def clamp_mod(m):
    return max(-MOD_CAP, min(MOD_CAP, m))


def profile(judgment_value, skill, mod, resistance):
    """판정치 하나 → (등급 분포 Counter, 성공권 %, 기대 마진). 2d6 전수."""
    fixed = judgment_value + skill + clamp_mod(mod)
    tiers, succ, total_margin = Counter(), 0, 0
    for d in DICE_2D6:
        margin = fixed + d - resistance
        tiers[tier_of(margin)] += 1
        succ += margin >= 0
        total_margin += margin
    return tiers, succ / 36.0, total_margin / 36.0


# ─── 환산층 — C안 정본 ───
def to_ledger(old_x):
    """옛 값 X → 원장 X² (attribute_scale_v3.md §3 · 사용자 확정 C안)."""
    return old_x * old_x


def judge(ledger):
    """원장 → 판정치 = floor(√원장)."""
    return math.floor(math.sqrt(ledger))


def compare(convert=to_ledger, judgment_fn=judge, gender_pre_sqrt=False, verbose=False):
    """옛/새 전수 대조. 위반 목록을 돌려준다 (빈 목록 = 동등)."""
    bad = []
    skills = [0, 2, 5, 8, 10]
    mods = [-5, -3, 0, 2, 5]
    resistances = range(8, 31)  # NPC능력치+기술+경계+의심+상태+7 의 실전역
    genders = [0, GENDER_CAP]   # 성별 보정 없음 / ±1 (히든 — 판정이 읽을 때만)

    for old_x in range(ATTR_MIN, ATTR_MAX + 1):
        for g in genders:
            for sk in skills:
                for mod in mods:
                    for res in resistances:
                        old_j = old_x + g                       # 옛: 능력치에 그대로 얹는다
                        if gender_pre_sqrt:                     # (자기 시험용 오배선)
                            new_j = judgment_fn(convert(old_x) + g)
                        else:                                   # 정본: 판정치 산출 뒤 ±1
                            new_j = judgment_fn(convert(old_x)) + g
                        p_old = profile(old_j, sk, mod, res)
                        p_new = profile(new_j, sk, mod, res)
                        if p_old != p_new:
                            bad.append(("판정", old_x, g, sk, mod, res, p_old[1], p_new[1]))

    # ② 중간 원장 — 제곱 사이의 모든 원장이 판정치를 넘치게 하지 않는가
    for old_x in range(ATTR_MIN, ATTR_MAX + 1):
        lo, hi = old_x * old_x, (old_x + 1) * (old_x + 1) - 1
        for ledger in (lo, (lo + hi) // 2, hi):
            if judgment_fn(ledger) != old_x:
                bad.append(("중간원장", old_x, ledger, judgment_fn(ledger)))

    if verbose:
        print(f"  판정 전수 대조: 능력치 {ATTR_MAX - ATTR_MIN + 1} × 성별 {len(genders)} × 기술 {len(skills)}"
              f" × 보정 {len(mods)} × 저항 {len(resistances)} = "
              f"{(ATTR_MAX - ATTR_MIN + 1) * len(genders) * len(skills) * len(mods) * len(resistances):,} 시나리오"
              f" × 36 눈 전수")
    return bad


def check_caps(convert=to_ledger, judgment_fn=judge):
    """③ 경지 캡: 옛 캡 c → 원장 c² 이 판정 천장 c 를 그대로 내는가."""
    bad = []
    for realm, cap in REALM_CAPS.items():
        if judgment_fn(convert(cap)) != cap:
            bad.append((realm, cap, judgment_fn(convert(cap))))
    return bad


def check_migration(square=lambda x: x * x, verbose=False):
    """⑤ 옛 실수 원장 x → 새 원장. 실수 보존안(x²)과 정수안(floor(x²)) 둘 다
    판정치 floor(x) 를 보존해야 한다. 경계값(3.99 류)이 시험의 핵심이다."""
    bad = []
    samples = [0.33, 1.0, 1.5, 2.0, 2.5, 3.0, 3.4, 3.99, 4.999, 6.7, 7.0, 9.99, 10.0]
    for x in samples:
        want = math.floor(x)
        real_case = judge(square(x))                    # 실수 보존안
        int_case = judge(math.floor(square(x)))         # 정수안 (floor 저장)
        if real_case != want:
            bad.append(("실수안", x, real_case, want))
        if int_case != want:
            bad.append(("정수안", x, int_case, want))
    if verbose:
        print(f"  마이그레이션 표본 {len(samples)}건 (경계 3.99 · 4.999 · 9.99 포함)")
    return bad


def check_derived():
    """⑥ 파생치: 실수치 = √원장 이 옛 실수치와 같아야 내력 풀·이속·내구가 보존된다.
    내력 풀 = round(x(x+1)/2 × pool_per_year) 왕복 검산 (internal_energy.yml)."""
    bad = []
    for x in [0.33, 1.0, 2.0, 3.0, 3.4, 5.0, 7.0, 9.0, 10.0]:
        old_real = x
        new_real = math.sqrt(x * x)                     # 실수 보존안의 √원장
        pool_old = round(old_real * (old_real + 1) / 2 * POOL_PER_YEAR)
        pool_new = round(new_real * (new_real + 1) / 2 * POOL_PER_YEAR)
        if abs(new_real - old_real) > 1e-9 or pool_old != pool_new:
            bad.append((x, old_real, new_real, pool_old, pool_new))
    return bad


def run(verbose=True):
    bad_total = 0
    print("\n═══ B-137 C안 제곱 환산층 — 옛/새 눈금 동등 대조 (2d6 해석적 전수) ═══\n")

    bad = compare(verbose=verbose)
    bad_total += len(bad)
    print(f"  {'✅' if not bad else '❌'} ①② 판정 전수 + 중간 원장: 위반 {len(bad)}건")
    for b in bad[:5]:
        print(f"      ✗ {b}")

    bad = check_caps()
    bad_total += len(bad)
    print(f"  {'✅' if not bad else '❌'} ③ 경지 캡 제곱 환산 ({len(REALM_CAPS)}단): 위반 {len(bad)}건")

    bad = check_migration(verbose=verbose)
    bad_total += len(bad)
    print(f"  {'✅' if not bad else '❌'} ⑤ 마이그레이션 판정치 보존 (실수안·정수안): 위반 {len(bad)}건")

    bad = check_derived()
    bad_total += len(bad)
    print(f"  {'✅' if not bad else '❌'} ⑥ 파생치 실수치 보존 (내력 풀 왕복): 위반 {len(bad)}건")

    # 대표 시나리오 — judgment.yml 재설계 주석의 대등 판정을 두 눈금으로 나란히
    print("\n  대표: 대등 판정 (옛 능력치 5 · 기술 0 · 보정 0 · 저항 13 ↔ 원장 25)")
    t_old, s_old, m_old = profile(5, 0, 0, 13)
    t_new, s_new, m_new = profile(judge(to_ledger(5)), 0, 0, 13)
    order = [t["id"] for t in TIERS]
    for tid in order:
        print(f"    {tid:<18} 옛 {t_old.get(tid, 0):>2}/36   새 {t_new.get(tid, 0):>2}/36")
    print(f"    성공권(마진≥0)     옛 {s_old:.1%}   새 {s_new:.1%}   ·   기대 마진 옛 {m_old:+.2f} 새 {m_new:+.2f}")

    print(f"\n  총 위반: {bad_total}건 — {'옛/새 눈금 동등' if bad_total == 0 else '동등하지 않다'}\n")
    return bad_total


def selftest():
    """눈을 시험하는 눈 — 일부러 어긋난 환산을 심어서 하네스가 잡는지 본다."""
    bad = 0
    ok = lambda cond, msg: print(("  ✅ " if cond else "  ❌ ") + msg) or (0 if cond else 1)

    print("\n═══ 자기 시험 — 어긋난 환산 4종을 하네스가 잡는가 ═══\n")

    # 0) 바른 환산은 깨끗해야 한다 (깨끗한 것을 더럽다고 하는 눈도 병든 눈이다)
    clean = (len(compare()) + len(check_caps()) + len(check_migration()) + len(check_derived())) == 0
    bad += ok(clean, "바른 환산(X², floor√, 성별 후치)은 위반 0")

    # 1) 선형 ×10 환산 (A안의 원장을 C안 √에 꽂은 오배선)
    bad += ok(len(compare(convert=lambda x: 10 * x)) > 0,
              "원장 = 10X 오배선을 잡는다 (floor(√10X) ≠ X)")

    # 2) round(√) — floor 계약 위반 (중간 원장 상단이 한 칸 넘친다)
    bad += ok(len(compare(judgment_fn=lambda L: round(math.sqrt(L)))) > 0,
              "판정치 = round(√원장) 오배선을 잡는다 (원장 (X+1)²−1 이 X+1 로 넘친다)")

    # 3) round(x²) 마이그레이션 — 경계값이 제곱수를 타넘는다 (3.99² = 15.92 → 16 → 판정 4)
    bad += ok(len(check_migration(square=lambda x: round(x * x))) > 0,
              "마이그레이션 round(x²) 오배선을 잡는다 (x=3.99 가 판정 4 로 승격)")

    # 4) 성별 ±1 을 √ 앞(원장)에 넣기 — 보정이 통째로 증발한다
    bad += ok(len(compare(gender_pre_sqrt=True)) > 0,
              "성별 ±1 을 원장에 넣는 오배선을 잡는다 (floor(√(X²+1)) = X — 보정 증발)")

    print(f"\n  자기 시험: {'전부 통과' if bad == 0 else f'{bad}건 실패'}\n")
    return bad


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--selftest", action="store_true", help="눈을 시험하는 눈")
    a = ap.parse_args()
    sys.exit(selftest() if a.selftest else (1 if run() else 0))


if __name__ == "__main__":
    main()
