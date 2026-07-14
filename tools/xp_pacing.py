#!/usr/bin/env python3
"""경험 페이싱 모델 — 수치를 손으로 짓지 않는다 (B-135 · cultivation_v3_levels.md §1).

★ 사용자 확정 (2026-07-15): "경험치 획득 조절을 통해 플레이 시간대별 정보를 분석하고
자격 레벨을 예상하여 계산해야 할 듯" — 곡선·자격 레벨은 **역산의 산출물**이지 손으로
짓는 수가 아니다. 이 도구가 그 역산이다:

    입력  : XP/시간 율 R (실측 — 텔레메트리 전에는 【제안】 초기값)
            경지별 목표 도달 시간 H_k (사람이 승인)
            곡선의 꼴 (base·growth — 사람이 승인)
    등록부 : cultivation.yml cultivation_stages (경지 이름·순서 — 여기서 읽는다. 하드코딩 금지)
            cultivation.yml beast_ranks (짐승 격 → 몹 XP 가중)
            economy.yml 의뢰_보수 (의뢰 XP 는 보수 비례 — 돈이 이미 잰 난이도를 재지 않는다)
    출력  : 레벨 곡선 · 경지 자격 레벨 N_k · 원천별 XP 제안표

★ 전부 【제안】이다 — 승인은 사람. 실측(플레이 시간대별 획득량)이 쌓이면 R 을 갈아 끼우고
다시 돌린다. 격차 규칙은 발명하지 않는다: mc_perception 격차_표시의 「회(압도적 하수) =
적립 0」을 XP 에도 그대로 쓴다 (같은 반농사 못).

사용법:  python3 tools/xp_pacing.py [--rate R] [--hours h1,h2,...] [--base B] [--growth G]
         python3 tools/xp_pacing.py --selftest
"""
import argparse
import os
import sys

import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load(rel):
    with open(os.path.join(ROOT, "config", rel), encoding="utf-8") as f:
        return yaml.safe_load(f)


def stages():
    """경지 이름·순서 — 등록부에서 읽는다. 지어내지 않는다."""
    return [s["name"] for s in load("cultivation.yml")["cultivation_stages"]]


def beast_ranks():
    return load("cultivation.yml")["battle_marks"]["beast_ranks"] \
        if "battle_marks" in load("cultivation.yml") else \
        load("cultivation.yml").get("beast_ranks", {})


def quest_pay():
    return load("economy.yml")["price_table"]["의뢰_보수"] \
        if "price_table" in load("economy.yml") else load("economy.yml")["의뢰_보수"]


def find_key(d, key):
    """중첩 어디에 있든 그 키의 절을 찾는다 (등록부 구조가 바뀌어도 이름이 정본)."""
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


def need(level, base, growth):
    """레벨 L → L+1 에 필요한 XP."""
    return base * (growth ** (level - 1))


def cumulative(level, base, growth):
    return sum(need(l, base, growth) for l in range(1, level))


def level_at(xp, base, growth, cap=200):
    lv = 1
    while lv < cap and cumulative(lv + 1, base, growth) <= xp:
        lv += 1
    return lv


def model(rate, hours, base, growth):
    names = stages()
    if len(hours) != len(names) - 1:
        raise SystemExit(f"목표 시간은 {len(names) - 1}개여야 한다 (범인 제외 경지 수) — {len(hours)}개를 받았다")
    rows = []
    for name, h in zip(names[1:], hours):
        rows.append((name, h, level_at(rate * h, base, growth)))
    return rows


def sources(rate):
    """원천별 XP 제안 — 상대 가중은 등록부에서, 절대 눈금은 R 에서.

    몹: 사냥 지속 시 시간당 XP ≈ R 의 절반이 되게 잰다 (나머지 절반은 의뢰·일과의 몫 —
        한 원천이 전부가 되면 그것이 메타가 된다). 시간당 유효 처치 K=40 【제안·실측 교체 대상】.
    격 가중: beast_ranks 의 상당 경지 간격에서 — 들짐승(삼류)=1 · 맹수(일류)=6 · 영물(절정+)=30
        【제안 — 간격당 배수는 실측 후 재보정】. 회(압도적 하수) = 0 (mc_perception 격차_표시 그대로).
    의뢰: XP = 보수 × 환율 — 돈이 이미 잰 난이도를 다시 재지 않는다. 환율 【제안】 은
        「조사(300문) ≈ 들짐승 30마리」가 되는 0.1.
    """
    k_per_hour = 40
    unit = (rate * 0.5) / k_per_hour
    mob = {"들짐승": round(unit, 1), "맹수": round(unit * 6, 1), "영물": round(unit * 30, 1)}
    pay = quest_pay()
    quest = {}
    for name, v in pay.items():
        won = sum(v) / len(v) if isinstance(v, list) else v
        quest[name] = round(won * 0.1, 1)
    return mob, quest


def selftest():
    bad = 0
    names = stages()
    ok = lambda cond, msg: print(("  ✅ " if cond else "  ❌ ") + msg) or (0 if cond else 1)
    bad += ok(len(names) >= 5 and names[0] == "범인", f"경지를 등록부에서 읽는다 ({len(names)}단계 · 첫째 {names[0]})")
    bad += ok(all(need(l + 1, 100, 1.15) > need(l, 100, 1.15) for l in range(1, 50)),
              "곡선이 단조 증가다 (레벨이 오를수록 더 든다)")
    hours = list(range(2, 2 + (len(names) - 1) * 3, 3))
    rows = model(200, hours, 100, 1.15)
    bad += ok(all(rows[i][2] <= rows[i + 1][2] for i in range(len(rows) - 1)),
              "자격 레벨이 경지 순서와 같이 는다")
    mob, quest = sources(200)
    bad += ok(mob["들짐승"] < mob["맹수"] < mob["영물"], "몹 XP 가 격 순서를 따른다")
    pay = quest_pay()
    keys = list(pay.keys())
    won = lambda v: sum(v) / len(v) if isinstance(v, list) else v
    order_pay = sorted(keys, key=lambda k: won(pay[k]))
    order_xp = sorted(keys, key=lambda k: quest[k])
    bad += ok(order_pay == order_xp, "의뢰 XP 순서 = 보수 순서 (돈이 잰 난이도를 다시 안 잰다)")
    print(f"\n  자기 시험: {'전부 통과' if bad == 0 else f'{bad}건 실패'}")
    return bad


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--rate", type=float, default=200.0,
                    help="XP/시간 【제안 200 — 텔레메트리 실측으로 교체하라】")
    ap.add_argument("--hours", type=str, default="",
                    help="경지별 목표 도달 누적 시간 (시간 단위, 쉼표 — 범인 제외 경지 수만큼)")
    ap.add_argument("--base", type=float, default=100.0, help="레벨 1→2 필요 XP 【제안 100 = R 로 30분】")
    ap.add_argument("--growth", type=float, default=1.15, help="레벨당 증가율 【제안 1.15】")
    ap.add_argument("--points", type=int, default=1, help="레벨당 스탯 포인트 【제안 1 — 미결 ②】")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        sys.exit(selftest())

    names = stages()
    n = len(names) - 1
    if a.hours:
        hours = [float(x) for x in a.hours.split(",")]
    else:
        # 【제안】 목표 누적 시간 — 앞은 빠르게(손맛), 뒤는 여정으로. 승인은 사람.
        proposal = [2, 8, 25, 60, 120, 250, 500, 900]
        hours = proposal[:n] + [proposal[-1]] * max(0, n - len(proposal))

    print(f"\n═══ 경험 페이싱 모델 (R={a.rate:.0f} XP/h · base={a.base:.0f} · growth={a.growth}) ═══")
    print("★ 전부 【제안】 — 승인은 사람. 실측이 쌓이면 R 을 갈아 끼우고 다시 돌린다.\n")
    print("  경지 자격 레벨 N_k (혼합 승급 — 사건 마크는 별도 그대로):")
    print("  경지        목표 누적 h   자격 레벨   그 레벨까지 누적 XP")
    for name, h, lv in model(a.rate, hours, a.base, a.growth):
        print(f"  {name:<10} {h:>8.0f}h   Lv {lv:>4}   {cumulative(lv, a.base, a.growth):>12,.0f}")
    mob, quest = sources(a.rate)
    print("\n  몹 XP (격차 회색 = 0 — mc_perception 격차_표시 그대로):")
    for k, v in mob.items():
        print(f"    {k:<6} {v:>8}")
    print("\n  의뢰 XP (= 보수 × 0.1 【제안】 — region_multipliers 는 보수에 이미 탄다):")
    for k, v in quest.items():
        print(f"    {k:<12} {v:>8}")
    print(f"\n  레벨당 포인트: {a.points} 【제안 — 미결 ②】\n")


if __name__ == "__main__":
    main()
