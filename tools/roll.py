#!/usr/bin/env python3
"""판정 주사위 도구 — 플레이 테스트용.

실행력 기본치(주사위 제외)와 저항값을 받아 2d6을 실제로 굴리고
config/judgment.yml의 등급 경계로 결과를 판정한다.

사용법: python3 tools/roll.py <실행력기본> <저항값> [라벨]
"""

import os
import sys
import random
import yaml

ROOT = os.path.join(os.path.dirname(__file__), "..")
with open(os.path.join(ROOT, "config", "judgment.yml"), encoding="utf-8") as f:
    RULES = yaml.safe_load(f)

TIERS = [(t["name"], t["min_margin"]) for t in RULES["result_tiers"]]


def tier_of(margin):
    for name, min_margin in TIERS:
        if min_margin is not None and margin >= min_margin:
            return name
    return TIERS[-1][0]


exec_base = int(sys.argv[1])
resist = int(sys.argv[2])
label = sys.argv[3] if len(sys.argv) > 3 else "판정"

d1, d2 = random.randint(1, 6), random.randint(1, 6)
roll = d1 + d2
margin = exec_base + roll - resist
result = tier_of(margin)

# 선택 규칙: 극단 주사위
note = ""
if RULES.get("optional_extreme_dice", {}).get("enabled"):
    order = [name for name, _ in TIERS]
    idx = order.index(result)
    if roll == 2 and idx < len(order) - 1:
        result = order[idx + 1]
        note = " (스네이크아이: 1단계 하락)"
    elif roll == 12 and idx > 0:
        result = order[idx - 1]
        note = " (더블식스: 1단계 상승)"

print(f"[{label}] 2d6 = {d1}+{d2} = {roll} │ 실행력 {exec_base}+{roll}={exec_base+roll} vs 저항 {resist} │ 마진 {margin:+d} → {result}{note}")
