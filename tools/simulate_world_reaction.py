#!/usr/bin/env python3
"""세계 반응 루프 검증 시뮬레이터.

config/rumor.yml, faction_reaction.yml, region_state.yml의 규칙만으로
설계 검토(design_review.md 3장)의 '경로 B: 폭력 루트'를 하루 단위로 재생한다.
검토 당시 GM 재량으로 메꿨던 6개 지점이 전부 기계 계산으로 대체되는지 확인한다.

사용법: python3 tools/simulate_world_reaction.py
"""

import os
import yaml

ROOT = os.path.join(os.path.dirname(__file__), "..")


def load(name):
    with open(os.path.join(ROOT, "config", name), encoding="utf-8") as f:
        return yaml.safe_load(f)


RUMOR = load("rumor.yml")
FACTION = load("faction_reaction.yml")
REGION = load("region_state.yml")

NETWORKS = RUMOR["networks"]
ACCESS = RUMOR["faction_awareness"]["network_access"]
INPUTS = FACTION["inputs"]
THRESHOLDS = sorted(FACTION["stage_thresholds"], key=lambda t: -t["min_score"])


def stage_of(score):
    for t in THRESHOLDS:
        if score >= t["min_score"]:
            return t["stage"], t["name"]
    return 0, "무관심"


def accuracy_band(acc):
    for name, band in sorted(RUMOR["accuracy_bands"].items(), key=lambda b: -b[1]["min"]):
        if acc >= band["min"]:
            return name
    return "괴담"


class Rumor:
    def __init__(self, rid, day, origin_net, intensity, accuracy, tags, text, direct_party=None):
        self.rid, self.day, self.origin_net = rid, day, origin_net
        self.intensity, self.accuracy, self.tags, self.text = intensity, accuracy, tags, text
        self.direct_party = direct_party    # 사건 당사자 세력 — 소문 가산 제외
        self.dead = False
        # 도달 스케줄: 발원망은 즉시, 강도 2+면 관심 일치 망으로 전파 (speed_days 후, distortion 적용)
        self.arrivals = {origin_net: (day, accuracy)}
        if intensity >= 2:
            for net, spec in NETWORKS.items():
                if net != origin_net and set(spec["interests"]) & set(tags):
                    self.arrivals[net] = (day + spec["speed_days"],
                                          accuracy - spec["distortion"])


class World:
    def __init__(self):
        self.region = {"치안": 50, "경제": 48, "민심": 55}   # 청하현 초기값
        self.factions = {}      # (세력, 대상) -> 점수
        self.rumors = []
        self.scored = set()     # (rumor, faction) 중복 가산 방지
        self.log = []

    def note(self, day, msg):
        self.log.append(f"  D+{day} │ {msg}")

    def faction_input(self, day, faction, target, key, override=None, why=""):
        pts = override if override is not None else INPUTS[key]
        k = (faction, target)
        before = self.factions.get(k, 0)
        after = max(0, min(FACTION["score"]["range"][1], before + pts))
        self.factions[k] = after
        s0, _ = stage_of(before)
        s1, name1 = stage_of(after)
        arrow = f" → ★단계 {s0}→{s1} [{name1}]" if s1 != s0 else ""
        self.note(day, f"{faction}({target} 대상) {key} {pts:+d} → 점수 {after}{arrow} {why}")

    def region_delta(self, day, key):
        delta = REGION["event_deltas"][key]
        parts = []
        for stat, d in delta.items():
            self.region[stat] = max(0, min(100, self.region[stat] + d))
            parts.append(f"{stat} {d:+d}→{self.region[stat]}")
        self.note(day, f"지역 상태 [{key}]: " + ", ".join(parts))

    def add_rumor(self, r):
        self.rumors.append(r)
        self.note(r.day, f"소문 발생 {r.rid} \"{r.text}\" (강도 {r.intensity}, 정확도 {r.accuracy})")

    def process_day(self, day):
        # 1. 소문 도달 → 세력 인지 점수
        for r in self.rumors:
            if r.dead:
                continue
            for faction, nets in ACCESS.items():
                if (r.rid, faction) in self.scored or faction == r.direct_party:
                    continue    # 세력당 1회 가산, 사건 당사자는 제외 (no_double_count)
                hits = [(d, a) for net, (d, a) in r.arrivals.items() if net in nets and d == day]
                if hits:
                    d, acc = min(hits)
                    self.scored.add((r.rid, faction))
                    key = "소문_도달_고정확도" if acc >= 70 else "소문_도달_관심일치"
                    self.faction_input(day, faction, "미상의 낭인" if faction != "sangdan" else "습격 사건",
                                       key, why=f"({r.rid} 정확도 {acc}: {accuracy_band(acc)})")
        # 2. 소문 감쇠 (3일마다 -1)
        for r in self.rumors:
            if not r.dead and day > r.day and (day - r.day) % RUMOR["propagation"]["decay"]["every_days"] == 0:
                r.intensity -= 1
                if r.intensity <= 0:
                    r.dead = True
                    self.note(day, f"소문 {r.rid} 소멸 — 접촉 NPC의 기억 태그로만 잔존")
                else:
                    self.note(day, f"소문 {r.rid} 감쇠 → 강도 {r.intensity}")


print("=" * 74)
print("세계 반응 루프 검증 — 경로 B(폭력 루트) 재생")
print("규칙 출처: config/rumor.yml · faction_reaction.yml · region_state.yml")
print("=" * 74)

w = World()

for day in range(0, 8):
    # ── 시나리오 고정 사건 (플레이어 행동과 그 직접 결과만 수동 입력) ──
    if day == 0:
        w.note(0, "【밤】 플레이어가 청하객잔에서 묵삼을 기습 제압 (판정: 성공 — 흔적 남음)")
        w.add_rumor(Rumor("R1", 0, "inn_net", intensity=2, accuracy=70,
                          tags=["폭력", "사파", "무인", "치안", "조직원"],
                          text="객잔에서 낭인이 사파 놈을 잡았다더군"))
        w.region_delta(0, "증거_없는_폭행")
        w.faction_input(0, "haomun", "미상의 낭인", "연락책_연락두절")
    if day == 2:
        w.note(2, "【낮】 흑랑이 접선 무산을 확인하고 지부에 보고 (연락두절→피해 확인: 차액만 가산)")
        w.faction_input(2, "haomun", "미상의 낭인", "조직원_사망_또는_중상_확인",
                        override=1, why="(연락두절 +3 기가산, 차액 +1)")
    if day == 3:
        w.note(3, "【해질녘】 북쪽 산길 상단 습격 발생 — 정보는 이미 흑랑에게 넘어가 있었다")
        w.region_delta(3, "상단_습격_성공")
        w.add_rumor(Rumor("R2", 3, "sangdan_net", intensity=3, accuracy=90,
                          tags=["도적", "물류", "금전", "치안", "폭력"],
                          text="북쪽 산길에서 상단이 당했다", direct_party="sangdan"))
        w.faction_input(3, "sangdan", "습격 사건", "금전_손실_대규모")
        w.faction_input(3, "gwan_gun", "습격 사건", "관할_치안_중대사건")

    # ── 이하 전부 자동: 소문 전파/감쇠, 세력 인지 점수 ──
    w.process_day(day)

print()
for line in w.log:
    print(line)

print("\n" + "=" * 74)
print("D+7 종료 상태")
print("=" * 74)
print(f"\n지역 상태: 치안 {w.region['치안']} / 경제 {w.region['경제']} / 민심 {w.region['민심']}"
      f"   (초기 50/48/55)")
print("\n세력 반응:")
for (faction, target), score in sorted(w.factions.items()):
    s, name = stage_of(score)
    print(f"  {faction:10s} (대상: {target})  점수 {score:2d} → {s}단계 [{name}]")
print("\n소문:")
for r in w.rumors:
    state = "소멸(기억 태그 잔존)" if r.dead else f"강도 {r.intensity}"
    print(f"  {r.rid} \"{r.text}\" — {state}")
    for net, (d, acc) in sorted(r.arrivals.items(), key=lambda x: x[1][0]):
        print(f"      D+{d} {NETWORKS[net]['name']:12s} 정확도 {acc} ({accuracy_band(acc)})")

print("\n검증 목표 (design_review.md 3장에서 GM 재량이었던 지점):")
print("  ① 소문이 며칠 만에 어디까지 → 도달 스케줄로 계산됨")
print("  ② 사파 반응 0→2의 근거     → 연락두절+3, 피해확인+1, 소문+1 = 점수 5")
print("  ③ 치안 -5의 근거           → region_state.yml event_deltas")
print("  ④ 소연(정파망)의 인지 경로  → orthodox_net D+3 도달")
print("  ⑤ 습격까지의 시간 경과      → 흑랑 보고(D+2) → 습격(D+3 해질녘)")
print("  ⑥ 하오문 점수의 귀속        → '미상의 낭인' 대상, 정체 확인 시 병합")
