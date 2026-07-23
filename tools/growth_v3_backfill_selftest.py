#!/usr/bin/env python3
"""성장 v3 원장 backfill 자기 시험 (B-135 단계 1의 눈).

GrowthV3.backfill 의 계약을 파이썬으로 독립 재현해 검증한다 (같은 식):
  원장 = 옛 능력치 실수 x 의 제곱 (실수 저장) · 판정치 = floor(√원장)
계약: **판정치 보존** — floor(√(x²)) == floor(x) == 옛 판정치(정수부). 전 구간 위반 0.
일부러 어긋난 환산(round·×10)을 심어 눈이 뜨였는지도 본다 (judgment_scale_harness 와 같은 방법).
"""
import math
import sys


def raw_of(x):           # backfill: 원장 = x²
    return x * x


def judgment(raw):       # 판정치 = floor(√원장)
    return int(math.floor(math.sqrt(max(0.0, raw))))


def old_judgment(x):     # v2: 판정 가산은 정수부 (화후 규칙)
    return int(math.floor(x))


def check():
    bad = 0
    # 능력치 실수 0.0 ~ 10.0 (0.01 간격) — 경계 3.99·4.999·9.99 포함
    x = 0.0
    while x <= 10.0001:
        if judgment(raw_of(x)) != old_judgment(x):
            print(f"  ✗ 판정치 불일치 x={x:.2f}: 새 {judgment(raw_of(x))} ≠ 옛 {old_judgment(x)}")
            bad += 1
        x = round(x + 0.01, 2)
    print(f"  {'✅' if bad == 0 else '❌'} 판정치 보존 (x=0~10, 0.01 간격): 위반 {bad}건")
    return bad


def selftest():
    """일부러 어긋난 환산을 심어 눈이 잡는지."""
    bad = 0
    # ① round(√) 오배선 — 경계 원장이 판정을 승격시킨다 (x=3.99 → round(3.99)=4)
    wrong = int(round(math.sqrt(raw_of(3.99))))
    bad += 0 if wrong == 4 else 1   # round 는 4 로 승격 → 눈이 이 오배선을 '틀림'으로 봐야 함
    print(f"  {'✅' if wrong == 4 else '❌'} round(√) 오배선 감지 (x=3.99 → {wrong}, floor 는 3)")
    # ② 원장=10x 오배선 — floor(√(10x)) ≠ floor(x)
    x = 5.0
    wrong2 = judgment(10 * x)
    bad += 0 if wrong2 != old_judgment(x) else 1
    print(f"  {'✅' if wrong2 != old_judgment(x) else '❌'} 원장=10x 오배선 감지 "
          f"(x=5 → 판정 {wrong2} ≠ 옛 {old_judgment(x)})")
    return bad


if __name__ == "__main__":
    print("═══ 성장 v3 원장 backfill 자기 시험 ═══")
    fail = check()
    print("  ── 눈을 시험하는 눈 (오배선 심기) ──")
    fail += selftest()
    print(f"\n총 위반/오류: {fail}건 — {'통과' if fail == 0 else '실패'}")
    sys.exit(1 if fail else 0)
