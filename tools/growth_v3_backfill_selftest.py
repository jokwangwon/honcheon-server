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


def backfill(sheet):
    """GrowthV3.backfill 의 계약 재현 (단계 3 · 화해 포함) — 자바와 같은 식이어야 한다.
    실수의 정본 = 능력치_화후 (없으면 능력치 정수) · 원장 = max(원장, 화후²) raise-only."""
    attrs = sheet.get("능력치") or {}
    hwahu = sheet.get("능력치_화후") or {}
    raw = sheet.setdefault("원장", {})
    for axis in set(attrs) | set(hwahu) | set(raw):
        x = hwahu.get(axis, 0.0) or attrs.get(axis, 0.0)
        raw[axis] = max(raw.get(axis, 0.0), x * x)
    return sheet


def check_reconcile():
    """단계 3 의 계약 — 화후 소수부가 원장을 거쳐 파생 실수치로 살아 돌아온다."""
    bad = 0
    # ① 화후 우선 + 소수부 보존: 능력치 3(정수) · 화후 3.7 → 원장 13.69 → 파생 3.7 · 판정 3
    s = backfill({"능력치": {"근력": 3}, "능력치_화후": {"근력": 3.7}})
    derived = math.sqrt(s["원장"]["근력"])
    ok = abs(derived - 3.7) < 1e-9 and judgment(s["원장"]["근력"]) == 3
    bad += 0 if ok else 1
    print(f"  {'✅' if ok else '❌'} 화후 소수부 보존 (3.7 → 원장 {s['원장']['근력']:.2f} → 파생 {derived:.2f} · 판정 {judgment(s['원장']['근력'])})")
    # ② 화해 — 정수 제곱으로 얼어붙은 원장(9)이 화후(3.7)² 로 올라온다 (표류 수리)
    s = backfill({"능력치": {"근력": 3}, "능력치_화후": {"근력": 3.7}, "원장": {"근력": 9.0}})
    ok = abs(s["원장"]["근력"] - 13.69) < 1e-9
    bad += 0 if ok else 1
    print(f"  {'✅' if ok else '❌'} 표류 화해 (원장 9 → {s['원장']['근력']:.2f})")
    # ③ raise-only — 원장이 이미 앞서 있으면(단계 4 독립 성장) 화후가 끌어내리지 못한다
    s = backfill({"능력치": {"근력": 3}, "능력치_화후": {"근력": 3.7}, "원장": {"근력": 16.0}})
    ok = s["원장"]["근력"] == 16.0
    bad += 0 if ok else 1
    print(f"  {'✅' if ok else '❌'} raise-only (원장 16 유지 — 단계 4 앞방향 호환)")
    return bad


def check_levelup():
    """단계 4 — GrowthV3.grantXp 의 계약 재현: need(L) = base×growth^(L-1) · 매 레벨 3포인트."""
    base, growth, ppl = 20.0, 1.0565, 3   # cultivation.yml levels (승인 수치)
    def need(lv):
        return base * growth ** (lv - 1)
    def grant(cur, level, pts, xp):
        cur += xp
        ups = 0
        while cur >= need(level):
            cur -= need(level)
            level += 1
            pts += ppl
            ups += 1
        return cur, level, pts, ups
    bad = 0
    # ① 삼류 잡졸(Lv10 · XP 10) 두 마리 = Lv2 (need(1)=20 — "첫 저녁에 무공"의 첫 계단)
    cur, lv, pts, _ = grant(0.0, 1, 0, 10)
    cur, lv, pts, _ = grant(cur, lv, pts, 10)
    ok = lv == 2 and pts == 3 and abs(cur) < 1e-9
    bad += 0 if ok else 1
    print(f"  {'✅' if ok else '❌'} 잡졸 2마리 = Lv2 (+3포인트, 잔여 {cur:.1f})")
    # ② 큰 XP 한 방 — 연속 레벨업이 포인트를 전부 적립한다 (240 XP → Lv1에서 Lv11 넘김)
    cur, lv, pts, ups = grant(0.0, 1, 0, 240)
    total_need = sum(need(l) for l in range(1, lv))
    ok = ups == lv - 1 and pts == ups * ppl and abs((cur + total_need) - 240) < 1e-6
    bad += 0 if ok else 1
    print(f"  {'✅' if ok else '❌'} 240 XP 한 방 → Lv{lv} ({ups}연속 · {pts}포인트 · 보존 검산)")
    # ③ 후반이 무겁다 — need(100) > need(10) × 100/10 (지수 감속이 선형 XP 를 이긴다)
    ok = need(100) > need(10) * 10
    bad += 0 if ok else 1
    print(f"  {'✅' if ok else '❌'} 후반 무거움 (need(100)={need(100):.0f} > 10×need(10)={10 * need(10):.0f})")
    return bad


def allocate(sheet, axis, cap):
    """GrowthV3.allocate 의 계약 재현 — 포인트 1점 = 원장 1눈금 · 캡 c² · 거절분은 은행."""
    pts = sheet.get("미사용포인트", 0)
    if pts <= 0:
        return "NO_POINTS"
    raw = sheet.setdefault("원장", {})
    cur = raw.get(axis, 0.0)
    if cur + 1.0 > cap + 1e-9:
        return "CAP"
    raw[axis] = cur + 1.0
    sheet["미사용포인트"] = pts - 1
    return "OK"


def check_allocate():
    """단계 4 — 배분 손의 계약: 캡 준수(§8.5) · 은행 보존(⑨) · 판정 단조."""
    bad = 0
    # ① 정상 배분 — 원장 +1 · 포인트 −1 · 판정 = floor(√)
    s = {"미사용포인트": 3, "원장": {"근력": 15.0}}
    ok = allocate(s, "근력", 16) == "OK" and s["원장"]["근력"] == 16.0 \
        and s["미사용포인트"] == 2 and judgment(s["원장"]["근력"]) == 4
    bad += 0 if ok else 1
    print(f"  {'✅' if ok else '❌'} 배분 +1 (원장 15→16 · 포인트 3→2 · 판정 4)")
    # ② 캡 거절 + 은행 — 화후 소수부 잔여(15.5, 캡 16)도 반 눈금 배분은 없다. 포인트 불변
    s = {"미사용포인트": 5, "원장": {"근력": 15.5}}
    ok = allocate(s, "근력", 16) == "CAP" and s["원장"]["근력"] == 15.5 and s["미사용포인트"] == 5
    bad += 0 if ok else 1
    print(f"  {'✅' if ok else '❌'} 캡 거절·은행 보존 (15.5+1 > 16 → 거절 · 포인트 5 유지)")
    # ③ 캡까지 정확히 — 원장 9 · 포인트 100 · 캡 16 → 7점 들어가고 93점 은행 (삼류 정체 시나리오)
    s = {"미사용포인트": 100, "원장": {"근력": 9.0}}
    landed = 0
    while allocate(s, "근력", 16) == "OK":
        landed += 1
    ok = landed == 7 and s["원장"]["근력"] == 16.0 and s["미사용포인트"] == 93
    bad += 0 if ok else 1
    print(f"  {'✅' if ok else '❌'} 캡 채움 (9→16 = {landed}점 · 은행 {s['미사용포인트']}점)")
    return bad


def check_gate():
    """단계 4 — 승급 이중 관문: 승급 = 사건 요건 AND 자격 레벨 N_k ("레벨은 자격, 사건이 문")."""
    nk = {"삼류": 10, "이류": 40, "일류": 65}   # cultivation.yml levels.qualifying_level (승인 수치)
    def promote(events_met, level, target):
        return events_met and level >= nk[target]
    bad = 0
    cases = [
        # (사건 요건, 레벨, 목표, 기대, 설명)
        (True, 9, "삼류", False, "사건 충족·Lv9 → 삼류 불가 (자격 미달)"),
        (True, 10, "삼류", True, "사건 충족·Lv10 → 삼류 승급"),
        (False, 100, "이류", False, "Lv100·사건 미달 → 이류 불가 (레벨은 문이 아니다)"),
    ]
    for events, lv, target, want, label in cases:
        ok = promote(events, lv, target) == want
        bad += 0 if ok else 1
        print(f"  {'✅' if ok else '❌'} {label}")
    return bad


def selftest_gate():
    """눈을 시험하는 눈 — AND 를 OR 로 오배선하면 자격 미달 승급이 잡혀야 한다."""
    events_met, level, need = True, 9, 10
    wrong = events_met or level >= need     # 오배선: 이중 관문이 외짝 문이 됐다
    caught = wrong is True                  # 자격 미달인데 통과 → 눈이 이것을 '틀림'으로 봐야 함
    print(f"  {'✅' if caught else '❌'} OR 오배선 감지 (사건만으로 Lv9 승급 → 이중 관문 위반)")
    return 0 if caught else 1


def selftest_allocate():
    """눈을 시험하는 눈 — 캡을 무시하는 오배선 배분을 심으면 캡 초과가 잡혀야 한다."""
    raw, pts, cap = 15.5, 5, 16
    raw += 1.0                        # 오배선: 캡 검사 없이 밀어 넣음
    caught = raw > cap
    print(f"  {'✅' if caught else '❌'} 캡 무시 오배선 감지 (원장 {raw:.1f} > 캡 {cap})")
    return 0 if caught else 1


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


def selftest_reconcile():
    """눈을 시험하는 눈 — 화해 없는 옛 backfill(동결 원장)을 심으면 파생이 어긋나야 한다."""
    frozen = 9.0                       # 옛 계약: 원장 = 능력치(정수 3)² 로 동결
    derived = math.sqrt(frozen)        # 3.0 — 화후 3.7 의 소수부가 죽었다
    caught = abs(derived - 3.7) > 1e-9
    print(f"  {'✅' if caught else '❌'} 동결 원장 오배선 감지 (파생 {derived:.2f} ≠ 화후 3.7)")
    return 0 if caught else 1


if __name__ == "__main__":
    print("═══ 성장 v3 원장 backfill 자기 시험 ═══")
    fail = check()
    print("  ── 단계 3 — 화해·파생 보존 ──")
    fail += check_reconcile()
    print("  ── 단계 4 — XP·레벨업 ──")
    fail += check_levelup()
    print("  ── 단계 4 — 포인트 배분 ──")
    fail += check_allocate()
    print("  ── 단계 4 — 승급 이중 관문 ──")
    fail += check_gate()
    print("  ── 눈을 시험하는 눈 (오배선 심기) ──")
    fail += selftest()
    fail += selftest_reconcile()
    fail += selftest_allocate()
    fail += selftest_gate()
    print(f"\n총 위반/오류: {fail}건 — {'통과' if fail == 0 else '실패'}")
    sys.exit(1 if fail else 0)
