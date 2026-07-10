#!/usr/bin/env python3
"""판정 시스템 밸런스 검증 시뮬레이터.

config/judgment.yml의 등급 경계를 그대로 읽어, 2d6 전수 열거(36가지)로
각 판정 시나리오의 결과 등급 확률 분포를 계산한다.

사용법: python3 tools/simulate_judgment.py
"""

import os
import yaml

ROOT = os.path.join(os.path.dirname(__file__), "..")

with open(os.path.join(ROOT, "config", "judgment.yml"), encoding="utf-8") as f:
    RULES = yaml.safe_load(f)

# 2d6 분포 (전수 열거)
DICE = {}
for a in range(1, 7):
    for b in range(1, 7):
        DICE[a + b] = DICE.get(a + b, 0) + 1
TOTAL = 36

# config의 result_tiers를 (이름, 최소 마진) 목록으로 변환 (마진 내림차순)
TIERS = [(t["name"], t["min_margin"]) for t in RULES["result_tiers"]]


def tier_of(margin):
    for name, min_margin in TIERS:
        if min_margin is not None and margin >= min_margin:
            return name
    return TIERS[-1][0]  # 치명적 실패


def distribution(exec_base, resist):
    """실행력 기본치(주사위 제외)와 저항값으로 등급 분포를 계산."""
    dist = {}
    for roll, count in DICE.items():
        t = tier_of(exec_base + roll - resist)
        dist[t] = dist.get(t, 0) + count
    return {t: c / TOTAL for t, c in dist.items()}


def fmt(dist):
    order = [name for name, _ in TIERS]
    return "  ".join(f"{t} {dist[t]*100:4.1f}%" for t in order if t in dist)


def at_least_partial(dist):
    """부분 성공 이상(무언가는 얻는) 확률."""
    ok = ("대성공", "성공", "아슬아슬한 성공", "부분 성공")
    return sum(v for t, v in dist.items() if t in ok)


def report(title, exec_base, resist, note=""):
    dist = distribution(exec_base, resist)
    print(f"\n■ {title}")
    print(f"  실행력 기본 {exec_base} + 2d6  vs  저항값 {resist}   (기대 마진 {exec_base + 7 - resist:+d})")
    if note:
        print(f"  {note}")
    print(f"  {fmt(dist)}")
    print(f"  → 부분 성공 이상: {at_least_partial(dist)*100:.1f}%")
    return dist


print("=" * 72)
print("혼천 판정 시스템 밸런스 검증 (등급 경계: config/judgment.yml)")
print("=" * 72)

print("\n[1] 첫 10턴 정본 시나리오 — 협의형 낭인 지망생")
print("    (근3 민3 체3 내3 감3 화3 지2 / 조사2 은신1 추적1)")

report("턴 3: 한백 설득", 4, 14,
       "화술3+교섭0+접근+1 vs 위험회피5+공포+1+불신+1+7")
report("턴 4: 야간 잠복", 7, 10,
       "감각3+은신1+어둠+2+단서+1 vs 묵삼 감각4+목적집중-1+7")
report("턴 5: 묵삼 미행", 6, 15,
       "감각3+추적1+환경+2 vs 감각4+미행감지4+7 — 프로 상대 최고 난도")
report("턴 6: 창고 조사", 5, 12, "감각3+조사2 vs 비대립 '보통'")
report("턴 7: 엿듣기", 6, 11, "감각3+은신1+엄폐+2 vs 흑랑측 감각3+경계1+7")
report("턴 8: 곽진 설득 (증거 2개)", 5, 12,
       "화술3+교섭0+단서+2 vs 위협감지3+낯선이+2+7")

print("\n[2] 정보/준비 보정의 가치 — 곽진 설득, 단서 개수별")
for clues in (0, 1, 2):
    report(f"곽진 설득: 핵심 단서 {clues}개", 3 + clues, 12)

print("\n[3] 빌드 격차 — 묵삼 미행 (저항 15)")
report("협의형 낭인 (감3+추적1+환경2)", 6, 15)
report("은밀형 하오문 말단 (감4+추적0+환경2)", 6, 15, "시작 시점엔 협의형과 대등")
report("성장 후: 추적 3, 변장 준비 +1 (감4+추적3+환경2+준비1)", 10, 15,
       "기술 성장 + 준비가 프로 상대를 상대 가능하게 만든다")

print("\n[4] 폭력 루트의 유혹 — 턴 4 밤, 묵삼 기습")
d = report("묵삼 기습 (민첩3+은신1+어둠+2)", 6, 11,
           "vs 감각4+위협감지0+7 — 설득(58%)보다 훨씬 쉽다: 이것이 의도된 긴장")
print("  ※ 단, 성공해도 소문 4계열 생성 + 사파 반응 상승 + 흑랑 연결선 소멸")

print("\n[5] 판정 생략 경계 확인")
print(f"  자동 성공 기준: 기대 마진 ≥ +{RULES['auto_resolution']['auto_success_expected_margin']}")
print(f"  자동 실패 기준: 기대 마진 ≤ {RULES['auto_resolution']['auto_fail_expected_margin']}")
report("생사경 고수가 삼류 도적 상대 은신 (예시)", 17, 12,
       "기대 마진 +12 → 판정 생략, 자동 성공으로 처리되어야 함")
