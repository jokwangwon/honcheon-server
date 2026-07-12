#!/usr/bin/env python3
"""성장 감사 — 혼천 빌드(build)의 눈과 자.

`combat_audit.py` 는 **한 명의 표준 무인**이 몇 합에 끝나는가를 잰다.
이 도구는 다른 것을 묻는다: **여러 명의 다른 무인**이 있는가.

  성장 축(training.yml curriculum)은 플레이어에게 다섯 개의 선택지를 준다 —
  외공(몸) · 내공(단전) · 초식(손) · 신법(발) · 심안(눈).
  선택지가 선택지이려면 세 가지가 참이어야 한다:

    ① **지배 전략이 없다**   하나가 언제나 옳으면 그건 선택이 아니라 정답이다.
    ② **모든 축이 쓸모 있다** 어느 축도 '아무 상황에서도 최선이 아닌' 축이면 안 된다 (죽은 축).
    ③ **분산이 손해다**       다 키우는 것이 몰빵과 같으면 고를 이유가 없다.

  그리고 넷째, 불가침:

    ④ **combat_audit 밴드를 깬 빌드가 없다** — 내구 빌드가 TTK 20합을 만들면 실패다.
       빌드는 전투의 길이를 바꿀 수 있지만, 전투를 **없애거나 늘어뜨릴 수는 없다**.

전투 수학(2d6 해석 · Fighter · duel · 포위 규칙)은 combat_audit.py 를 **그대로 재사용한다** —
전투를 두 번 구현하면 두 개의 진실이 생긴다. 이 도구가 새로 갖는 것은 **방어 선택**뿐이다
(combat.yml attack.defender_choice — 회피/막기/흘리기. 셋이 세 능력치에 걸려 있고, 그것이 빌드의 뒷면이다).

config 를 고치지 않는다 — 재기만 한다. 수치는 전부 config 에서 읽는다 (하드코딩 금지).

사용법:
    python3 tools/growth_audit.py                 # 전체
    python3 tools/growth_audit.py --lint-only     # ① 등록 정합 린트만
    python3 tools/growth_audit.py --sim-only      # ② 빌드 시뮬만
    python3 tools/growth_audit.py --budget 1800   # 수련 예산(일치) — 기본 1800 (= 5년 몰빵)

종료 코드: 위반(❌) 1건 이상이면 1, 아니면 0.
"""

from __future__ import annotations

import argparse
import os
import sys
from fractions import Fraction

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from game_audit import (  # noqa: E402  — 문법·출력 형식 계승 (읽기 전용 재사용)
    FAIL,
    Report,
    YamlError,
    dig,
    load_all,
    num,
    realm_names,
)
from combat_audit import (  # noqa: E402  — 전투 수학은 하나뿐이다 (복제 금지)
    DICE_ITEMS,
    Fighter,
    durability,
    engaged,
    gang_rules,
    guard_soak,
    net_mod,
    pool_of,
    qi_casts,
    combat_regen,
    top_band,
)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

#: 전투에 서는 능력치 5축 (judgment.yml attributes 중 — 화술·지혜는 전투 밖)
COMBAT_ATTRS = ["근력", "민첩", "체력", "내공", "감각"]

#: 방어 태세 — combat.yml attack.defender_choice 의 키 (도구가 고르지 않는다)
STANCES = ["회피", "막기", "흘리기"]


# ══════════════════════════════════════════════════════════════════════════════
#  config 판독 — 성장 축 (등록제. 도구는 수치를 갖지 않는다)
# ══════════════════════════════════════════════════════════════════════════════

def curriculum(cfg):
    """training.yml curriculum — 과목 등록부."""
    return dig(cfg, "training.yml", "curriculum", default={}) or {}


def subjects(cfg):
    return dig(curriculum(cfg), "subjects", default={}) or {}


def days_per_segment(cfg):
    return num(dig(curriculum(cfg), "days_per_segment"), 0.2)


def segments_per_day(cfg):
    """정본은 time.yml — training.yml 값은 대조용 사본이다 (린트가 둘의 일치를 본다)."""
    return int(num(dig(cfg, "time.yml", "segments_per_day"), 5))


def attr_cap(cfg, realm):
    caps = dig(cfg, "player_creation.yml", "attribute_cap_by_realm", default={}) or {}
    return int(num(caps.get(realm), 3))


def attr_cost_days(cfg):
    """능력치 +1 = 몇 일치인가 — training.yml attribute_progression.cost ('집중 단련 1년')."""
    txt = str(dig(cfg, "training.yml", "attribute_progression", "cost", default="1년"))
    return 360.0 if "년" in txt else 30.0


def skill_ladder(cfg):
    """숙련 n→n+1 일치 — training.yml skill_progression_cost (환산표 그대로)."""
    raw = dig(cfg, "training.yml", "skill_progression_cost", default={}) or {}
    out = {}
    for key, val in raw.items():
        k = str(key)
        if "_to_" not in k:
            continue
        lo = int(k.split("_to_")[0])
        txt = str(val)
        days = 0
        if "년" in txt:
            days = int(txt.replace("년", "")) * 360
        elif "개월" in txt:
            days = int(txt.replace("개월", "")) * 30
        out[lo] = float(days)
    return out


def naegong_cost_days(cfg, level):
    """내공 n→n+1 = n년 (0→1 = 1년) — simbeop.yml dantian.accumulation_cost. 유일한 배증형."""
    _ = cfg
    return max(1.0, float(level)) * 360.0


def defense_rules(cfg):
    """combat.yml attack.defender_choice — 태세별 (능력치, 경감, 판정 비용)."""
    dc = dig(cfg, "combat.yml", "attack", "defender_choice", default={}) or {}
    out = {}
    for stance in STANCES:
        spec = dc.get(stance) or {}
        out[stance] = {
            "attr": str(spec.get("attribute", "민첩")),
            "soak": num(spec.get("damage_reduction"), 0),
            "penalty": num(spec.get("penalty"), 0),
        }
    return out


def attacker_attr(cfg, weapon="검"):
    """★ 병기가 능력치를 정한다 — combat.yml attack.attacker_attribute (계열 → 능력치).

    이 한 줄이 '외공 지배 전략'을 푼 매듭이다: 근력 하나가 공격·막기·경감을 다 사면
    그 과목이 정답이 된다 (= 노션 원설계의 병). 병기가 능력치를 나누면 빌드가 병기를 고른다.
    """
    spec = dig(cfg, "combat.yml", "attack", "attacker_attribute", default={}) or {}
    for attr, weapons in spec.items():
        if isinstance(weapons, list) and weapon in weapons:
            return str(attr)
    return str(spec.get("default", "근력"))


def gyeol_of(cfg, simbeop_id):
    """simbeop.yml <id>.gyeol — 결(結). 방어 태세 판정 가산 (없으면 {})."""
    spec = dig(cfg, "simbeop.yml", "simbeop", simbeop_id, default={}) or {}
    raw = spec.get("gyeol") or {}
    return {str(k).replace("_판정", ""): num(v, 0) for k, v in raw.items()} if isinstance(raw, dict) else {}


def simbeop_all(cfg):
    return dig(cfg, "simbeop.yml", "simbeop", default={}) or {}


def obtainable_simbeop(cfg, stance):
    """그 방어를 사는 결(結)을 가진 심법 중 **실제로 얻을 수 있는 것** — 일인전승은 제외한다.

    ★ 세계에 한 자루뿐인 심법(sole_transmission)은 빌드의 뼈대가 될 수 없다 —
      그 빌드를 고를 수 있는 플레이어가 서버에 한 명뿐이라면 그건 선택지가 아니다.
    """
    for sid, spec in simbeop_all(cfg).items():
        if not isinstance(spec, dict) or spec.get("demonic"):
            continue
        if spec.get("sole_transmission"):
            continue
        if gyeol_of(cfg, sid).get(stance, 0) > 0:
            return sid
    return None


def sole_only_stances(cfg):
    """★ 결이 일인전승 심법에만 있는 방어 — 그 축의 뼈대에 아무도 닿을 수 없다."""
    out = []
    for stance in STANCES:
        holders = [sid for sid, spec in simbeop_all(cfg).items()
                   if isinstance(spec, dict) and gyeol_of(cfg, sid).get(stance, 0) > 0]
        if holders and all((simbeop_all(cfg)[s] or {}).get("sole_transmission") for s in holders):
            out.append((stance, holders))
    return out


# ══════════════════════════════════════════════════════════════════════════════
#  성장 모델 — 예산(일치)을 배분표대로 쓴다. 캡에 닿으면 차선으로 흐른다
# ══════════════════════════════════════════════════════════════════════════════

#: 캡에 닿았을 때 구간이 흘러갈 곳 — 이성적인 플레이어는 배분을 바꾼다 (버리지 않는다)
FALLBACK = {"외공": ["초식", "심안"], "내공": ["초식", "외공"], "초식": ["외공", "심안"],
            "신법": ["심안", "초식"], "심안": ["신법", "초식"]}


class Build:
    """한 사람의 성장 결과 — 배분표 + 예산 → 능력치·숙련·내공.

    ★ 캡 처리가 이 모델의 심장이다: 캡에 닿은 과목은 적립이 멈추고, 이성적인 플레이어는 남은 구간을
      **차선 과목**으로 돌린다 (FALLBACK). 그래서 몰빵은 캡에서 저절로 꺾인다 —
      "지배 전략을 막는 것은 밸런스 수치가 아니라 천장과 시간이다" 라는 설계 주장이 여기서 검증된다.
      (버려진 일치를 세는 것은 배분을 **안 바꾼 자**의 손실을 재기 위해서다 — wasted)
    """

    def __init__(self, cfg, name, alloc, realm, budget, simbeop=None,
                 base_attrs=None, base_skill=3.0, base_naegong=1.0 / 3.0, blurb=""):
        self.cfg = cfg
        self.name = name
        self.alloc = dict(alloc)          # 과목 → 구간 수 (합 = segments_per_day)
        self.realm = realm
        self.simbeop = simbeop
        self.blurb = blurb
        self.cap = attr_cap(cfg, realm)
        self.weapon = "검"                # ★ 빌드가 병기를 고른다 (standard_builds 가 덮어쓴다)
        self.attrs = {a: float(num((base_attrs or {}).get(a), 3.0)) for a in COMBAT_ATTRS}
        self.attrs["내공"] = base_naegong
        self.ladder = skill_ladder(cfg)
        # ★ 숙련은 이미 치른 몫에서 출발한다 (일류 = 주력 숙련 3). 원장은 '일치'이므로 되돌려 심는다
        self.skill_days = self._days_for(self.ladder, base_skill)
        self.skill = float(base_skill)
        self.wasted = 0.0                 # 캡에 닿았는데 배분을 안 바꿨다면 버려졌을 일치
        self.capped_at = {}               # 과목 → 캡에 닿은 예산 지점(일치)
        self._grow(cfg, budget)

    # ── 배분표대로 예산을 흘린다 (하루 단위 — 뭉텅이로 넣으면 캡을 넘긴다) ──────
    def _grow(self, cfg, budget):
        segs = segments_per_day(cfg)
        subj = subjects(cfg)
        cost1 = attr_cost_days(cfg)
        spent = 0.0
        day_days = segs * days_per_segment(cfg)     # 하루 = 1.0일치
        while spent + 1e-9 < budget:
            step = min(day_days, budget - spent)
            for name, n_seg in self.alloc.items():
                if n_seg > 0:
                    self._pour(cfg, subj, cost1, name, step * (n_seg / segs), spent, depth=0)
            spent += step

    def _pour(self, cfg, subj, cost1, name, days, spent, depth):
        """한 과목에 days 일치를 붓는다. 캡에 닿으면 차선 과목으로 흘린다 (FALLBACK)."""
        if days <= 0 or depth > 3:
            self.wasted += max(0.0, days)
            return
        overflow = 0.0
        spec = subj.get(name) or {}
        ledgers = spec.get("ledger") or []

        if name == "초식":
            self.skill_days += days                 # 숙련에는 캡이 없다 — 비용이 곧 천장이다
            self.skill = self._skill_of(self.ladder, self.skill_days)
            return

        if name == "내공":
            if self.simbeop is None:                # 심법 게이트 — 개화 못 한 몸은 이 과목이 0
                overflow = days
            else:
                cur = self.attrs["내공"]
                capacity = num(dig(cfg, "simbeop.yml", "simbeop", self.simbeop, "capacity"), 4)
                room = min(self.cap, capacity)      # 경지 캡 ∧ 단전 용량 — 둘 중 낮은 쪽
                if cur >= room:
                    overflow = days
                    self.capped_at.setdefault(name, spent)
                else:
                    self.attrs["내공"] = min(room, cur + days / naegong_cost_days(cfg, int(cur)))
        else:
            # 능력치 과목 (외공은 근력·체력 두 축을 split 으로 나눠 진다)
            split = spec.get("split") or [1.0 / max(1, len(ledgers))] * len(ledgers)
            for attr, frac in zip(ledgers, split):
                share = days * num(frac, 1.0)
                cur = self.attrs.get(attr, 0.0)
                if cur >= self.cap:
                    overflow += share
                    self.capped_at.setdefault(name, spent)
                    continue
                self.attrs[attr] = min(self.cap, cur + share / cost1)

        if overflow > 1e-9:
            self.wasted += overflow                 # 배분을 안 바꿨다면 여기서 버려졌다
            for nxt in FALLBACK.get(name, []):
                if nxt in subj:
                    self._pour(cfg, subj, cost1, nxt, overflow, spent, depth + 1)
                    return

    @staticmethod
    def _skill_of(ladder, days):
        """누적 일치 → 숙련 실수치 (환산표 걷기 — PlayerLedger.levelOf 와 같은 걸음)."""
        remaining, level = days, 0
        while True:
            cost = ladder.get(level)
            if cost is None or cost <= 0:
                return float(level)                # 환산표 상한 — 수련만으로는 여기까지 (beyond)
            if remaining < cost:
                return level + remaining / cost
            remaining -= cost
            level += 1

    @staticmethod
    def _days_for(ladder, level):
        """숙련 n 에 이미 든 일치 (되돌려 심기 — 원장은 언제나 일치가 정본이다)."""
        return sum(ladder.get(i, 0.0) for i in range(int(level)))

    # ── 파생치 — 전부 등록부의 공식 ────────────────────────────────────────────
    def attr(self, name):
        return self.attrs.get(name, 0.0)

    def ai(self, name):
        """판정에 서는 것은 정수부다 (simbeop.yml hwahu.judgment_adders) — 분산의 절벽이 여기 있다."""
        return int(self.attr(name))

    @property
    def dur(self):
        return durability(self.cfg, self.attr("체력"), self.realm)

    @property
    def pool(self):
        return pool_of(self.attr("내공"), self.cfg)

    @property
    def mastery(self):
        return int(self.skill)

    def stance(self, cfg, forced=False):
        """이 몸이 고를 방어 — 기대 피해가 가장 작은 태세. 포위면 회피를 못 고른다 (forced_guard)."""
        rules = defense_rules(cfg)
        pool = [s for s in STANCES if not (forced and s == "회피")]
        return pool, rules

    def def_score(self, cfg, stance, forced=False):
        """방어 판정치 = 능력치 + 병기 기술(숙련) + 결 − 판정 비용.

        · 회피는 '경공'을, 막기·흘리기는 '병기 기술'을 쓴다 — 도구는 둘 다 주력 숙련으로 근사한다
          (MVT 에 별도 경공 원장이 없다. 근사임을 명시한다).
        · 포위 강제 시 흘리기의 −2 는 물지 않는다 (forced_guard.waives 판정_비용).
        """
        rules = defense_rules(cfg)
        spec = rules[stance]
        pen = 0.0 if (forced and stance == "흘리기") else spec["penalty"]
        gyeol = gyeol_of(cfg, self.simbeop).get(stance, 0) if self.simbeop else 0
        return self.ai(spec["attr"]) + self.mastery + gyeol + pen

    def soak(self, cfg, stance):
        return defense_rules(cfg)[stance]["soak"]


# ══════════════════════════════════════════════════════════════════════════════
#  전투 — 방어 선택을 얹은 해석적 교환 (전투 수학은 combat_audit 그대로)
# ══════════════════════════════════════════════════════════════════════════════

def exchange(cfg, att, dfn, att_pen=0, dfn_pen=0, mod=0, att_qi=0.0,
             att_is_npc=True, forced=False, dfn_stance=None):
    """한 합의 기대 피해 — 방어자가 태세를 고른다 (기대 피해 최소).

    공격력 = 공격 능력치 + 무공 숙련 + 보정, 방어력 = 태세 판정치.
    피해 = 무기 + 무공 + 격 + floor(마진/2) − 경감. 마진 ≥ 0 에서만 (combat.yml damage).
    """
    a = att["atk"] + att_pen + mod
    wp, tp = att["wpower"], att["tpower"]
    best = None
    stances, _ = (dfn.stance(cfg, forced) if isinstance(dfn, Build) else ([dfn_stance], None))
    for stance in (stances if dfn_stance is None else [dfn_stance]):
        d = dfn.def_score(cfg, stance, forced) + dfn_pen
        soak = dfn.soak(cfg, stance)
        dmg = Fraction(0)
        hit = Fraction(0)
        for roll, w in DICE_ITEMS:
            # NPC 공격자는 고정 +7, 방어자(플레이어)는 2d6 — 한쪽만 굴린다 (combat_audit 규약)
            margin = (a + 7) - (d + roll) if att_is_npc else (a + roll) - (d + 7)
            p = Fraction(w, 36)
            if margin >= 0:
                hit += p
                base = max(0.0, wp + tp + att_qi + (margin // 2) - soak)
                dmg += p * Fraction(int(base * 2), 2)
        cand = (float(dmg), stance, float(hit))
        if best is None or cand[0] < best[0]:
            best = cand
    return best     # (기대 피해, 고른 태세, 명중률)


def build_attack(cfg, b, weapon=None, qi_band=None):
    """빌드의 공격 프로파일 — 병기가 능력치를 정하고(attacker_attribute), 위력표는 그대로다."""
    w = weapon or b.weapon
    wp = num(dig(cfg, "combat.yml", "damage", "weapon_power", w), 4)
    tps = dig(cfg, "combat.yml", "damage", "technique_power", default={}) or {}
    tp = num(tps.get(b.realm + "급"), 1)
    qis = dig(cfg, "combat.yml", "damage", "qi_power", default={}) or {}
    band = qi_band or top_band(cfg, b.realm)
    return {
        "atk": b.ai(attacker_attr(cfg, w)) + b.mastery,   # ★ 병기가 정한 능력치 + 무공 숙련
        "wpower": wp,
        "tpower": tp,
        "qi": num(qis.get(band), 0),
        "band": band,
        "weapon": w,
        "attr": attacker_attr(cfg, w),
    }


def foe_profile(cfg, realm, weapon="도", skill=None):
    """상대 — combat_audit 의 표준 무인 모델 (Fighter) 을 그대로 빌려 온다 (전투 수학은 하나뿐이다)."""
    f = Fighter(cfg, "상대", realm, weapon=weapon, is_npc=True, skill=skill)
    return {
        "atk": int(f.atk_stat) + int(f.skill),
        "wpower": f.wpower,
        "tpower": f.tpower,
        "qi": num(dig(cfg, "combat.yml", "damage", "qi_power", top_band(cfg, realm)), 0),
        "dur": f.dur,
        "def_stat": int(f.def_stat) + int(f.skill),
        "민첩": int(f.def_stat),          # 선공 비교용 (initiative.frontal)
        "감각": int(f.def_stat),          # Fighter 는 감각을 따로 갖지 않는다 — 표준 무인은 균질하다
        "은신": int(f.skill),             # 기습 판정의 저항 (심안의 간파 대상)
        "realm": realm,
    }


def mirror_profile(cfg, mirror, realm):
    """빌드 하나를 '상대'로 세운다 — 동예산·동경지의 사람.

    ★ combat_audit 의 표준 무인(능력치 = 캡 −1)을 상대로 쓰면 그는 ~9년치를 쓴 몸이라
      5년치 빌드가 전원 진다. 그건 빌드 비교가 아니라 **예산 비교**다.
      (예산이 실력인 것은 맞다 — 그러나 그건 빌드의 우열이 아니다.)
    """
    atk = build_attack(cfg, mirror)
    return {
        "atk": atk["atk"],
        "wpower": atk["wpower"],
        "tpower": atk["tpower"],
        "qi": atk["qi"],
        "dur": mirror.dur,
        "def_stat": mirror.ai("민첩") + mirror.mastery,
        "민첩": mirror.ai("민첩"),
        "감각": mirror.ai("감각"),
        "은신": mirror.mastery,
        "realm": realm,
    }


def wound_pen(hp, dur):
    r = hp / dur if dur else 0
    if hp <= 0:
        return -3
    if r < 0.25:
        return -2
    if r < 0.5:
        return -1
    return 0


def initiative(cfg, b, foe):
    """선공 — combat.yml initiative.frontal: "민첩+감각 합 비교 — 높은 쪽 선공,
    동률이면 경지 높은 쪽, 그것도 동률이면 감각 단독 비교".

    ★ 이 규칙을 모델에서 빼면 신법·심안의 공격 기여가 **통째로 사라진다** —
      그 둘이 사는 유일한 공격 자산이 '먼저 친다'는 것이다. 그리고 combat_audit 이 이미
      증언했다: "선공은 전부다 — 대칭 대결의 승패가 판정이 아니라 선공 결정에서 이미 끝난다".
    """
    _ = cfg
    mine = b.ai("민첩") + b.ai("감각")
    theirs = foe["민첩"] + foe["감각"]
    if mine != theirs:
        return mine > theirs
    return b.ai("감각") >= foe["감각"]           # 동률이면 감각 단독 비교


def ambush_detected(cfg, b, foe):
    """기습 간파 확률 — 감각 + 2d6 vs 상대 은신(judgment.yml 보통 12 근사).

    ★ 심안(감각)의 공격적 자산이 여기 있다: **매복을 읽으면 매복이 아니다.**
    """
    dc = num(dig(cfg, "judgment.yml", "static_difficulty", "보통"), 12)
    need = dc + foe.get("은신", 0) - b.ai("감각")
    p = sum(Fraction(w, 36) for roll, w in DICE_ITEMS if roll >= need)
    return float(min(Fraction(1), max(Fraction(0), p)))


def attack_damage(cfg, atk, foe, pen_me, pen_foe, qi):
    """한 합의 기대 피해 (플레이어 → 상대) — 상대는 combat_audit 의 저항치로 막는다."""
    _ = cfg
    a = atk["atk"] + pen_me
    d = foe["def_stat"] + pen_foe
    dmg = Fraction(0)
    for roll, w in DICE_ITEMS:
        margin = (a + roll) - (d + 7)
        if margin >= 0:
            base = max(0.0, atk["wpower"] + atk["tpower"] + qi + (margin // 2))
            dmg += Fraction(w, 36) * Fraction(int(base * 2), 2)
    return float(dmg)


def duel_build(cfg, b, foe, max_rounds=25, qi_band=None, player_first=None, ambush=False):
    """빌드 vs 상대 — 매 합 양측 기대 피해. 방어자는 태세를 고른다.

    선공은 **규칙이 정한다** (민첩+감각). 매복이면 상대가 선공 + 첫 합 방어 −2 (initiative.ambush),
    단 감각으로 간파하면 그 이점이 사라진다 — 확률 가중으로 두 갈래를 섞는다.

    반환: (내가 눕히는 합, 내가 눕는 합, 남은 내구 비율)
    """
    if ambush:
        p_see = ambush_detected(cfg, b, foe)
        seen = _duel(cfg, b, foe, max_rounds, qi_band, first=initiative(cfg, b, foe), pen1=0)
        blind = _duel(cfg, b, foe, max_rounds, qi_band, first=False, pen1=-2)
        # 확률 가중 — 잔존 내구는 섞고, 합수는 나쁜 쪽(당한 쪽)을 보고한다 (감사는 인색해야 한다)
        left = p_see * seen[2] + (1 - p_see) * blind[2]
        return blind[0], blind[1], left
    first = initiative(cfg, b, foe) if player_first is None else player_first
    return _duel(cfg, b, foe, max_rounds, qi_band, first=first, pen1=0)


def _duel(cfg, b, foe, max_rounds, qi_band, first, pen1):
    atk = build_attack(cfg, b, qi_band=qi_band)
    regen, _ = combat_regen(cfg, b.attr("내공"))   # 조식은 내공에 비례한다 (고수는 숨만 쉬어도 단전이 돈다)
    energy = b.pool
    hp_me, hp_foe = float(b.dur), float(foe["dur"])
    ttk = ttd = None

    for r in range(1, max_rounds + 1):
        pen_me = wound_pen(hp_me, b.dur) + (pen1 if r == 1 else 0)
        pen_foe = wound_pen(hp_foe, foe["dur"])

        # 격을 태울 수 있으면 태운다 (내력 예산 · 조식 규칙)
        cost = qi_cost(cfg, atk["band"])
        if cost <= 0 or energy >= cost:
            qi = atk["qi"]
            energy -= max(0, cost)
        else:
            qi = 0.0
            energy = min(b.pool, energy + regen)     # 숨을 고른 합
        mine = attack_damage(cfg, atk, foe, pen_me, pen_foe, qi)

        if first:
            hp_foe -= mine
            if hp_foe <= 0:
                ttk = r
                break
        # 상대 차례 — 내가 태세를 고른다 (회피/막기/흘리기 중 기대 피해 최소)
        hp_me -= exchange(cfg, foe, b, att_pen=pen_foe, dfn_pen=pen_me,
                          att_qi=foe["qi"], att_is_npc=True)[0]
        if hp_me <= 0:
            ttd = r
            break
        if not first:
            hp_foe -= mine
            if hp_foe <= 0:
                ttk = r
                break
    return ttk, ttd, max(0.0, hp_me) / b.dur


def qi_cost(cfg, band):
    spec = dig(cfg, "internal_energy.yml", "cost_bands", band, "cost", default=0)
    if isinstance(spec, list) and spec:
        return num(spec[0], 0)
    return num(spec, 0)


def melee_build(cfg, b, foe, count, max_rounds=25):
    """1대다 — 포위 규칙(engage_slots · 순보정 · forced_guard)을 combat_audit 에서 그대로 빌린다.

    ★ 이 시뮬이 '신법 빌드는 다구리에 벌거벗는다'는 설계 주장의 검산이다.

    ★★ 강제 태세는 **audit_floor(흘리기)** 로 잰다 — combat.yml forced_guard.audit_floor 의 규약:
       "감사는 경감이 가장 낮은 강제 태세로 잰다 — 방어자에게 인색해야 눈이 산다".
       (막기도 고를 수는 있지만 무기가 격을 먹는다(weapon_break). 그 값을 안 매기고 막기를 허용하면
        포위가 1대1보다 안전해진다 — 그것이 등록부가 감사 바닥을 못 박아 둔 이유다.)
    """
    rules = gang_rules(cfg)
    floor = str(forced_guard(cfg).get("audit_floor", "흘리기"))
    atk = build_attack(cfg, b)
    hp_me = float(b.dur)
    hps = [float(foe["dur"])] * count
    regen, _ = combat_regen(cfg, b.attr("내공"))
    energy = b.pool

    for r in range(1, max_rounds + 1):
        alive = [i for i, h in enumerate(hps) if h > 0]
        if not alive:
            return r - 1, hp_me / b.dur, None
        n_eng = engaged(rules, len(alive))
        trigger = num(forced_guard(cfg).get("trigger_extra_attackers"), 1)
        forced = (len(alive) - 1) >= trigger      # 둘 이상에게 잡히는 순간부터

        pen_me = wound_pen(hp_me, b.dur)
        cost = qi_cost(cfg, atk["band"])
        qi = atk["qi"] if (cost <= 0 or energy >= cost) else 0.0
        energy = (energy - cost) if qi else min(b.pool, energy + regen)

        tgt = alive[0]                             # 하나에 집중한다 (머릿수를 줄이는 것이 유일한 길)
        hps[tgt] -= attack_damage(cfg, atk, foe, pen_me, wound_pen(hps[tgt], foe["dur"]), qi)

        alive = [i for i, h in enumerate(hps) if h > 0]
        hands = min(n_eng, len(alive))             # 동시에 칠 수 있는 손은 engage_slots 까지
        mod = net_mod(rules, hands)
        for _ in range(hands):
            hp_me -= exchange(cfg, foe, b, dfn_pen=wound_pen(hp_me, b.dur), mod=mod,
                              att_qi=foe["qi"], att_is_npc=True, forced=forced,
                              dfn_stance=(floor if forced else None))[0]
        if hp_me <= 0:
            return None, 0.0, r
    return None, max(0.0, hp_me) / b.dur, None


def forced_guard(cfg):
    """포위된 자의 태세 — combat.yml attack.outnumbered_defense.forced_guard (gang_up 이 아니다)."""
    return dig(cfg, "combat.yml", "attack", "outnumbered_defense", "forced_guard", default={}) or {}


# ══════════════════════════════════════════════════════════════════════════════
#  ① 등록 정합 린트
# ══════════════════════════════════════════════════════════════════════════════

def lint(cfg, rep):
    rep.say()
    rep.say("═" * 72)
    rep.say("  ① 성장 축 정합 린트 — 등록된 것이 실재하는가")
    rep.say("═" * 72)
    lint_curriculum(cfg, rep)
    lint_defense_axes(cfg, rep)
    lint_gyeol(cfg, rep)
    lint_elixirs(cfg, rep)


def lint_curriculum(cfg, rep):
    rep.head("수련 배분 — 과목이 실재하는 원장에 쌓이는가")
    cur = curriculum(cfg)
    subj = subjects(cfg)
    if not subj:
        rep.fail("training.yml curriculum.subjects 가 없다 — 성장 축이 등록되지 않았다")
        return

    rep.say(f"     과목 {len(subj)}종: {' · '.join(subj)}")

    # ① 구간 산술이 두 정본의 나눗셈인가 (고른 수치가 아니라 강제된 값이어야 한다)
    segs_time = segments_per_day(cfg)
    segs_tr = int(num(cur.get("segments_per_day"), 0))
    dps = days_per_segment(cfg)
    rep.verdict(segs_time == segs_tr,
                f"구간 수 정합 — time.yml {segs_time} = training.yml {segs_tr} (정본은 time.yml)"
                if segs_time == segs_tr else
                f"구간 수 불일치 — time.yml {segs_time} ≠ training.yml {segs_tr}")
    rep.verdict(abs(segs_time * dps - 1.0) < 1e-9,
                f"1구간 = {dps}일치 × {segs_time}구간 = 하루 1.0일치 — "
                f"cultivation.yml daily_cap('수련 1일치/일')과 정확히 맞물린다 "
                f"(이 값은 고른 것이 아니라 두 정본의 나눗셈이다)"
                if abs(segs_time * dps - 1.0) < 1e-9 else
                f"1구간({dps}) × {segs_time}구간 = {segs_time * dps}일치 ≠ 1.0 — "
                f"수련 하루가 1일치가 아니게 된다 (cultivation daily_cap 과 모순)")

    # ② 각 과목의 원장이 실재하는 축인가
    attrs = dig(cfg, "judgment.yml", "attributes", default=[]) or []
    for name, spec in subj.items():
        if not isinstance(spec, dict):
            continue
        ledgers = spec.get("ledger") or []
        for led in ledgers:
            if led == "무공_숙련":
                continue
            if led not in attrs:
                rep.fail(f"과목 '{name}' 의 원장 '{led}' 이 judgment.yml attributes 에 없다 — "
                         f"존재하지 않는 능력치에 쌓고 있다")
        split = spec.get("split")
        if split and abs(sum(num(x, 0) for x in split) - 1.0) > 1e-6:
            rep.fail(f"과목 '{name}' 의 split 합이 1.0 이 아니다 ({split}) — 시간이 새거나 불어난다")

    # ③ 5축이 전부 덮이는가 — 전투 능력치 중 아무도 안 키우는 축이 있으면 그 축은 죽은 축이다
    covered = set()
    for spec in subj.values():
        if isinstance(spec, dict):
            covered.update(spec.get("ledger") or [])
    missing = [a for a in COMBAT_ATTRS if a not in covered]
    rep.verdict(not missing,
                f"전투 능력치 5축이 전부 과목에 걸려 있다 ({' · '.join(COMBAT_ATTRS)}) — 키울 수 없는 축이 없다"
                if not missing else
                f"어느 과목으로도 못 키우는 전투 능력치: {missing} — 죽은 축이다 (판정에는 서는데 성장 경로가 없다)")

    # ④ 실전 적립의 귀속이 한 과목뿐인가 (그라인딩이 빌드를 대신 정하면 안 된다)
    acc = dig(cur, "combat_accrual", default={}) or {}
    goes = acc.get("goes_to")
    rep.verdict(goes in subj,
                f"실전 적립 귀속 = '{goes}' 한 과목뿐 — 사냥은 손만 늘린다 "
                f"(몸·발·눈·단전은 앉아서 만든다). 그라인딩이 빌드를 대신 정하지 않는다"
                if goes in subj else
                f"실전 적립 귀속 과목 '{goes}' 이 등록부에 없다")


def lint_defense_axes(cfg, rep):
    rep.head("세 방어 = 세 능력치 — 빌드의 뒷면이 규칙에 있는가")
    rules = defense_rules(cfg)
    for stance, spec in rules.items():
        rep.say(f"     {stance:<4} ← {spec['attr']:<3} · 경감 {spec['soak']:g} · 판정 {spec['penalty']:+g}")

    used = {spec["attr"] for spec in rules.values()}
    rep.verdict(len(used) == len(STANCES),
                f"세 방어가 서로 다른 능력치에 걸려 있다 ({' · '.join(sorted(used))}) — "
                f"방어 태세 선택이 곧 빌드 선택이다"
                if len(used) == len(STANCES) else
                f"방어 태세가 같은 능력치를 공유한다 ({used}) — 한 축을 키우면 두 방어가 동시에 올라간다 "
                f"(그 축이 지배 전략이 된다)")

    # 포위가 회피를 지우는가 — 신법 빌드의 약점이 규칙에 등록돼 있어야 한다
    fg = forced_guard(cfg)
    loses = fg.get("loses") or []
    rep.verdict("회피" in loses,
                f"포위 시 회피가 봉쇄된다 (forced_guard.loses = {loses}) — "
                f"★ 신법 빌드가 1대다에서 벌거벗는다. 상황이 축을 뒤집는 자리다"
                if "회피" in loses else
                "forced_guard 가 회피를 봉쇄하지 않는다 — 민첩 하나로 1대1과 1대다를 다 사면 지배 전략이 된다")

    # 공격 능력치가 등록돼 있는가 (없으면 엔진이 능력치 항을 통째로 뺀다 — 지금까지 그랬다)
    aa = dig(cfg, "combat.yml", "attack", "attacker_attribute", default={}) or {}
    rep.verdict(bool(aa),
                f"공격 판정의 능력치가 등록됐다 (기본 {aa.get('기본')} · 원거리 {aa.get('원거리')}) — "
                f"combat.yml attack.attacker 의 '능력치'가 비로소 이름을 갖는다"
                if aa else
                "combat.yml attack.attacker_attribute 미등록 — '능력치 + 무공 숙련' 의 '능력치'가 누구인지 "
                "규칙이 말하지 않는다. 엔진은 그 항을 뺄 수밖에 없다 (실제로 그랬다)")


#: 방어를 말하는 낱말 — passive 프로즈가 방어 결인데 기계 정의가 없는 경우만 짚는다 (오탐 방지)
DEFENSE_WORDS = ("회피", "방어", "패링", "막기", "흘리", "반격", "받아")


def lint_gyeol(cfg, rep):
    rep.head("심법의 결(結) — 빌드의 뼈대가 기계로 읽히는가")
    all_sim = simbeop_all(cfg)
    cap = num(dig(cfg, "simbeop.yml", "dantian", "gyeol_axes", "cap"), 1)
    hits = {}
    for sid, spec in all_sim.items():
        if not isinstance(spec, dict):
            continue
        g = gyeol_of(cfg, sid)
        name = str(spec.get("name", sid))
        if g or spec.get("build_affinity"):
            rep.say(f"     {name:<8} 결 {str(g) if g else '없음':<20} "
                    f"궁합 {spec.get('build_affinity')}"
                    f"{'  [일인전승]' if spec.get('sole_transmission') else ''}")
        passive = str(spec.get("passive", ""))
        if not g and any(w in passive for w in DEFENSE_WORDS):
            rep.warn(f"{name}: passive('{passive[:28]}…') 가 방어를 말하는데 기계 정의(gyeol)가 없다 — "
                     f"엔진이 못 읽는 패시브다 (빌드의 뼈대가 프로즈로만 있다)")
        for stance, val in g.items():
            if abs(val) > cap:
                rep.fail(f"{name} 결 {stance} {val:+g} 이 캡({cap:g})을 넘는다 — "
                         f"심법이 격차를 만든다 (심법은 색이지 격차가 아니다)")
            hits.setdefault(stance, []).append(name)

    # ① 한 심법이 방어 둘 이상을 사면 그것이 지배 심법이 된다
    multi = [str(spec.get("name", sid)) for sid, spec in all_sim.items()
             if isinstance(spec, dict) and len(gyeol_of(cfg, sid)) > 1]
    rep.verdict(not multi,
                "결을 둘 이상 가진 심법 없음 — 어떤 심법도 두 방어를 동시에 사지 못한다 (지배 심법 없음)"
                if not multi else
                f"두 방어를 동시에 사는 심법: {multi} — 지배 심법이다")

    # ② 세 방어에 결이 하나씩 걸려 있는가
    covered = set(hits)
    rep.verdict(covered == set(STANCES),
                "세 방어에 결이 하나씩 걸려 있다 — "
                + " · ".join(f"{s}({'/'.join(hits[s])})" for s in STANCES if s in hits)
                if covered == set(STANCES) else
                f"결이 걸리지 않은 방어: {sorted(set(STANCES) - covered)} — "
                f"그 방어를 고르는 심법이 없다 (그 축을 뼈대로 삼는 빌드가 없다)")

    # ③ ★ 얻을 수 있는가 — 일인전승 심법은 빌드의 뼈대가 될 수 없다 (서버에 한 명뿐이다)
    for stance, holders in sole_only_stances(cfg):
        names = [str((all_sim[h] or {}).get("name", h)) for h in holders]
        rep.warn(f"'{stance}' 결이 일인전승 심법에만 있다 ({'/'.join(names)}) — "
                 f"그 빌드의 뼈대를 가질 수 있는 플레이어가 세계에 한 명뿐이다. "
                 f"보급형 대체 심법(일인전승 아님)이 하나 필요하다")
    for stance in STANCES:
        sid = obtainable_simbeop(cfg, stance)
        if sid:
            rep.say(f"     {stance:<4} 보급 뼈대: {(all_sim[sid] or {}).get('name', sid)}")


def lint_elixirs(cfg, rep):
    rep.head("영약 — combat.yml 이 가리키던 등록부가 실재하는가")
    el = dig(cfg, "fortune_encounters.yml", "elixirs", default={}) or {}
    cat = el.get("catalog") or {}
    if not cat:
        rep.fail("fortune_encounters.yml elixirs.catalog 가 없다 — combat.yml durability.elixir 가 "
                 "'기연 등록제'를 가리키는데 그 자리가 비어 있다")
        return

    attrs = dig(cfg, "judgment.yml", "attributes", default=[]) or []
    grades = dig(cfg, "fortune_encounters.yml", "grades", default={}) or {}
    cost1 = attr_cost_days(cfg)
    touched = set()
    for name, spec in cat.items():
        if not isinstance(spec, dict):
            continue
        g = spec.get("grants") or {}
        grade = spec.get("grade")
        if grade not in grades:
            rep.fail(f"영약 '{name}' 의 등급 '{grade}' 이 grades 에 없다")
        for attr, days in g.items():
            if attr not in attrs:
                rep.fail(f"영약 '{name}' 이 없는 능력치 '{attr}' 에 화후를 준다")
            touched.add(attr)
            rep.say(f"     {name:<6} {grade:<4} {attr} +{num(days, 0):g}일치 "
                    f"(= 능력치 +{num(days, 0) / cost1:.2f} · 몰빵 수련 {num(days, 0) / 360:.2f}년)")

    # ① 캡을 넘는가 — 넘으면 '경지가 레벨이다'가 무너진다
    capped = str(el.get("realm_cap", ""))
    rep.verdict("못 넘" in capped or "넘지 못" in capped,
                "영약은 경지 캡을 넘지 못한다 — 캡을 여는 것은 경지뿐이다 ('경지가 레벨이다'의 마지막 못)"
                if ("못 넘" in capped or "넘지 못" in capped) else
                "영약이 경지 캡을 넘을 수 있다 — 기연이 경지를 대체한다")

    # ② 영약이 한 축에만 쏠려 있는가 — 그러면 그 축이 기연으로 공짜 성장한다
    rep.verdict(len(touched) >= 3,
                f"영약이 {len(touched)}개 축에 걸쳐 있다 ({' · '.join(sorted(touched))}) — "
                f"기연이 특정 빌드만 살찌우지 않는다"
                if len(touched) >= 3 else
                f"영약이 {sorted(touched)} 축에만 있다 — 그 축만 기연으로 공짜 성장한다 (축 편향)")

    # ③ 영약의 크기가 수련을 대체하는가 — 최대 영약이 몇 년치인가
    biggest = max((num(d, 0) for s in cat.values() if isinstance(s, dict)
                   for d in (s.get("grants") or {}).values()), default=0)
    rep.verdict(biggest <= 1440,
                f"최대 영약 = {biggest:g}일치 ({biggest / 360:.1f}년치) — 크지만 수련의 종류를 바꾸지 않는다 "
                f"(영약은 시간을 사는 것이지 다른 사다리가 아니다)"
                if biggest <= 1440 else
                f"최대 영약 {biggest:g}일치 ({biggest / 360:.1f}년) — 수련 축을 통째로 대체한다")


# ══════════════════════════════════════════════════════════════════════════════
#  ② 빌드 시뮬
# ══════════════════════════════════════════════════════════════════════════════

#: 각 몰빵이 뼈대로 삼는 방어 — 심법은 그 방어의 결을 가진 **보급형**에서 고른다 (일인전승 제외)
BUILD_STANCE = {"외공": "막기", "내공": "흘리기", "초식": "막기", "신법": "회피", "심안": "흘리기"}

#: ★ 빌드가 병기를 고른다 (combat.yml attacker_attribute) — 키운 능력치로 낼 수 있는 병기를 든다
BUILD_WEAPON = {"외공": "도", "내공": "도", "초식": "도", "신법": "검", "심안": "검"}

BUILD_BLURB = {
    "외공": "몸(근력·체력) — 도를 들고 막기(−3)로 산다",
    "내공": "단전 — 격을 몰아 쓴다",
    "초식": "손 — 명중과 마진 (일찍 싸고 늦게 비싸다)",
    "신법": "발(민첩) — 검. 먼저 치고 안 맞는다",
    "심안": "눈(감각) — 먼저 보고 흘린다",
}


def standard_builds(cfg, realm, budget, base_attrs=None, base_skill=3.0, bloomed=True):
    """다섯 몰빵 + 균형 — 과목 이름은 등록부(training.yml)에서 읽는다 (도구가 짓지 않는다).

    심법은 **플레이어가 실제로 얻을 수 있는 것**만 고른다 (일인전승 제외) — 아무도 못 가지는
    심법에 기대어 축이 산다고 말하면 그건 검산이 아니라 변명이다.
    개화 전(bloomed=False)에는 심법이 없다 → 내공 과목의 구간이 통째로 흘러넘친다 (게이트).
    """
    segs = segments_per_day(cfg)
    all_subj = list(subjects(cfg))
    # ★ 개화 전에는 '내공' 과목에 구간을 넣을 수 없다 (curriculum.내공.requires: 심법) —
    #   그 배분은 **불법**이다. 도구가 불법 배분을 후보로 올리면 그것과 남의 몰빵이 같아 보이고
    #   (구간이 차선 과목으로 흘러가니까), 동점이 지배 전략으로 오독된다. 후보에서 뺀다.
    subj = [s for s in all_subj if bloomed or not (subjects(cfg).get(s) or {}).get("requires")]
    basic = "hyeoncheon_tonapbeop" if bloomed else None
    kw = dict(base_attrs=base_attrs, base_skill=base_skill,
              base_naegong=(1.0 / 3.0 if bloomed else 0.0))
    out = [Build(cfg, f"{name} 몰빵", {name: segs}, realm, budget,
                 simbeop=(obtainable_simbeop(cfg, BUILD_STANCE.get(name, "막기")) or basic)
                 if bloomed else None,
                 blurb=BUILD_BLURB.get(name, ""), **kw)
           for name in subj]
    for b, name in zip(out, subj):
        b.weapon = BUILD_WEAPON.get(name, "검")
    even = Build(cfg, "균형(1구간씩)", {name: 1 for name in subj}, realm, budget,
                 simbeop=basic, blurb="다 조금씩 — 정수부가 안 오른다", **kw)
    even.weapon = "검"
    out.append(even)
    return out


def simulate(cfg, rep, budget, max_rounds):
    rep.say()
    rep.say("═" * 72)
    rep.say("  ② 빌드 시뮬 — 지배 전략이 있는가 (해석적, 2d6 = 36가지)")
    rep.say("═" * 72)

    # ★ 세 시기에서 잰다. 이유: **축마다 비용 곡선이 다르고, 그래서 값이 오는 때가 다르다.**
    #     초식 — 90 → 180 → 360 → 720 → 1440 (일찍 싸고 늦게 비싸다)
    #     능력치 — 360 균일 (언제나 같다)
    #     내공 — n→n+1 = n년 (배증. 늦게 비싸고, 격은 절정에서야 값을 한다)
    #   한 시기만 보면 '늦게 오는 축'을 '죽은 축'으로 오독한다.
    #   세 시기를 겹쳐 봐야 **축의 순환**(무엇을 언제 키우는가)이 보인다 — 그것이 이 게임의 성장 서사다.
    parity(cfg, rep, max_rounds)

    all_champs = {}
    stages = [
        # (경지, 예산, 시작 능력치, 시작 숙련, 개화 여부)
        ("삼류", budget / 3, 2.0, 0.0, False),     # 소년·개화 전 — 심법이 없다 (내공 과목 봉쇄)
        ("일류", budget, 3.0, 3.0, True),          # 개화한 몸 — 발경이 열린다
        ("절정", budget, 3.0, 3.0, True),          # 검기의 경지
    ]
    for realm, bud, base, skill, bloomed in stages:
        builds = standard_builds(cfg, realm, bud,
                                 base_attrs={a: base for a in COMBAT_ATTRS},
                                 base_skill=skill, bloomed=bloomed)
        sheet(cfg, rep, builds, realm, bud, base, skill, bloomed)
        scores = scenarios(cfg, rep, builds, realm, max_rounds)
        champs = dominance(cfg, rep, builds, scores, realm, tutorial=not bloomed)
        for who, axes in champs.items():
            all_champs.setdefault(who, []).extend(f"{realm}:{a}" for a in axes)
        band_guard(cfg, rep, builds, realm, max_rounds)

    dead_axis(cfg, rep, all_champs, budget)
    spread(cfg, rep, budget)


def parity(cfg, rep, max_rounds):
    """★ combat_audit 정합 — 예산 0 의 '표준 무인'이 기존 도구와 같은 몸인가.

    성장 축을 얹고도 **기존 밸런스가 흔들리지 않았다**는 것의 증거. 이게 깨지면 나머지는 볼 필요가 없다.
    """
    _ = max_rounds
    rep.head("정합 — 표준 무인(예산 0)이 combat_audit 과 같은 몸인가")
    for realm in ("삼류", "이류", "일류", "절정"):
        cap = attr_cap(cfg, realm)
        std = {a: float(cap - 1) for a in COMBAT_ATTRS}          # combat_audit realm_axis 의 표준 무인
        b = Build(cfg, "표준", {}, realm, 0, base_attrs=std,
                  base_naegong=Fighter(cfg, "x", realm).naegong)
        f = Fighter(cfg, "표준", realm, weapon="검")
        ok = b.dur == f.dur
        rep.verdict(ok,
                    f"{realm}: 내구 {b.dur} = combat_audit {f.dur} · 체력 {cap - 1} (캡 −1)"
                    if ok else
                    f"{realm}: 내구 {b.dur} ≠ combat_audit {f.dur} — 성장 축이 기존 곡선을 흔들었다")


def sheet(cfg, rep, builds, realm, budget, base, skill, bloomed):
    rep.head(f"[{realm}] 캐릭터 시트 — 캡 {attr_cap(cfg, realm)} · 수련 예산 {budget:g}일치 "
             f"({budget / 360:.1f}년)")
    rep.say(f"     시작: 전 능력치 {base:g} · 주력 숙련 {skill:g} · "
            + ("내공 0.33 (개화 직후)" if bloomed else "★ 개화 전 — 심법 없음 (내공 과목 봉쇄)"))
    rep.say("")
    rep.say(f"     {'빌드':<14} {'병기':>4} {'근력':>5} {'민첩':>5} {'체력':>5} {'감각':>5} "
            f"{'내공':>5} {'숙련':>5} │ {'공격':>5} {'내구':>5} {'내력':>5}")
    for b in builds:
        atk = build_attack(cfg, b)
        rep.say(f"     {b.name:<14} {b.weapon:>4} {b.attr('근력'):>5.1f} {b.attr('민첩'):>5.1f} "
                f"{b.attr('체력'):>5.1f} {b.attr('감각'):>5.1f} {b.attr('내공'):>5.2f} "
                f"{b.skill:>5.2f} │ {atk['atk']:>5} {b.dur:>5} {b.pool:>5}")
    rep.say("")
    rep.say("     공격 = 병기가 정한 능력치 + 무공 숙련 (combat.yml attacker_attribute — "
            "도=근력 · 검=민첩 · 활/암기=감각)")
    rep.say("     ※ 판정에 서는 것은 **정수부**다 (화후 규칙) — 3.9와 4.0은 세계가 다르다")


#: 다섯 상황 — 무협의 전투가 실제로 벌어지는 자리들
AXES = ["1대1", "격상", "다구리", "매복", "지구"]


def scenarios(cfg, rep, builds, realm, max_rounds):
    """다섯 상황 — 각 빌드가 어디서 사는가. ★ 이 표가 '지배 전략 없음'의 증거다.

    ★ 상대는 **같은 예산의 균형 빌드**다 (budget-matched). combat_audit 의 표준 무인(능력치 = 캡 −1)을
      상대로 쓰면 그는 ~9년치를 쓴 몸이라 5년치 빌드가 전원 진다 — 그건 빌드 비교가 아니라 예산 비교다.
      격상 상대만은 combat_audit 의 표준 절정 고수를 그대로 쓴다 (격상은 원래 더 투자한 사람이다).
    """
    rep.head(f"[{realm}] 다섯 상황 — 어느 빌드가 어디서 사는가")

    mirror = next(b for b in builds if "균형" in b.name)      # 동예산 표준 상대
    foe_same = mirror_profile(cfg, mirror, realm)

    # 격상 = **한 계단** 위 (경지 사다리에서 읽는다 — 삼류에게 초절정을 붙이면 그건 시험이 아니라 처형이다)
    names = realm_names(cfg)
    up_realm = names[min(names.index(realm) + 1, len(names) - 1)]
    foe_up = foe_profile(cfg, up_realm)

    # 다구리 — 졸개는 한 계단 아래. 머릿수는 경지가 감당할 만큼 (삼류에게 4인은 그냥 죽음이다)
    mook_realm = names[max(names.index(realm) - 1, 1)]
    mooks = 2 if realm == "삼류" else 4
    foe_mook = foe_profile(cfg, mook_realm, weapon="도")

    bloomed = mirror.pool > 0 or mirror.attr("내공") > 0
    rep.say(f"     상대: 동예산 표준({realm} 균형 배분, 내구 {foe_same['dur']} · "
            f"선공치 {foe_same['민첩'] + foe_same['감각']}) · "
            f"격상({up_realm} 내구 {foe_up['dur']}) · 졸개({mook_realm} ×{mooks})")
    rep.say("     선공은 규칙이 정한다 — 민첩+감각 비교 (combat.yml initiative.frontal)")
    rep.say("")
    rep.say(f"     {'빌드':<12} {'선공':>4} {'1대1':>7} {'잔존':>6} {'격상 생존':>9} "
            f"{'다구리':>12} {'매복(간파)':>11} {'지구(8합)':>9}")

    scores = {}
    for b in builds:
        # ① 동경지 1대1 — 선공은 민첩+감각이 정한다 (그것이 신법·심안의 유일한 공격 자산이다)
        first = initiative(cfg, b, foe_same)
        ttk, ttd, left = duel_build(cfg, b, foe_same, max_rounds)
        won = ttk and (not ttd or ttk <= ttd)
        s_duel = (100 - ttk) + left * 100 if won else left * 10

        # ② 격상 상대 — 이기진 못한다. **몇 합을 버티는가**가 값이다 (도주의 창)
        t2, ttd2, left2 = duel_build(cfg, b, foe_up, max_rounds)
        s_up = (200 if t2 else 0) + (ttd2 or max_rounds) + left2 * 10

        # ③ 다구리 — 포위. 회피가 봉쇄되고 흘리기(감각)만 남는다 ★ 신법의 무덤 · 심안의 집
        clear, left3, dead = melee_build(cfg, b, foe_mook, mooks, max_rounds)
        s_gang = (100 - clear) + left3 * 100 if clear else (dead or 0) + left3 * 10

        # ④ 매복당함 — 선공을 뺏기고 첫 합 −2. 단 감각으로 간파하면 무효 (심안의 자리)
        seen = ambush_detected(cfg, b, foe_same)
        _t4, ttd4, left4 = duel_build(cfg, b, foe_same, max_rounds, ambush=True)
        s_amb = (ttd4 or max_rounds) + left4 * 20

        # ⑤ 지구전 — 8합 동안 격을 몇 번 싣는가. ★ 개화 전에는 내력이 없다 — 이 축은 존재하지 않는다
        atk = build_attack(cfg, b)
        s_long = qi_casts(b.pool, qi_cost(cfg, atk["band"]),
                          combat_regen(cfg, b.attr("내공"))[0], 8) if bloomed else 0

        scores[b.name] = dict(zip(AXES, [s_duel, s_up, s_gang, s_amb, s_long]))
        rep.say(f"     {b.name:<12} {('선' if first else '후'):>4} "
                f"{(str(ttk) + '합' if won else '패배'):>7} "
                f"{left * 100:>5.0f}% "
                f"{(str(t2) + '합 승' if t2 else (str(ttd2) + '합' if ttd2 else '>' + str(max_rounds))):>9} "
                f"{(str(clear) + '합 소탕' if clear else str(dead) + '합에 패배'):>12} "
                f"{((str(ttd4) + '합' if ttd4 else '생존') + f' ({seen * 100:.0f}%)'):>11} "
                f"{(str(s_long) + '회 격' if bloomed else '— (개화 전)'):>9}")
    rep.say("")
    rep.say("     1대1=이기는 합수/잔존 · 격상=버틴 합(이기면 '승') · 다구리=소탕 or 패배 합 · "
            "매복=버틴 합(간파율) · 지구=8합 중 격 실은 횟수")
    if not bloomed:
        rep.say("     ※ 개화 전 — 내력이 없다. '지구' 축은 이 시기에 존재하지 않는다 (내공 과목도 봉쇄)")
    return scores


def dominance(cfg, rep, builds, scores, realm, tutorial=False):
    """★ 이 도구의 존재 이유 — 하나가 언제나 옳으면 그건 선택이 아니다.

    동점은 동점으로 다룬다 — max() 는 사전 순서로 조용히 승자를 지어낸다 (그 버그에 한 번 속았다).

    ★★ 단, **개화 전(삼류·이류)에는 정답이 있는 것이 옳다** (tutorial=True):
      · 숙련 0→1 은 90일, 1→2 는 180일. 능력치는 언제나 360일. **초식이 압도적으로 싸다.**
      · 그리고 규칙이 이미 그렇게 말하고 있다 — 삼류→이류 승급 요건이 '주력 무공 숙련 2' 다.
      · 내력이 없고(개화 전) 심법이 없으니 내공 과목은 잠겨 있다.
      → 무공을 아직 못 익힌 자에게 '무엇을 키울까'는 질문이 아니다. **빌드는 개화부터 시작한다.**
        그래서 이 시기엔 '지배 전략 없음'이 아니라 **'초식이 정답인가'**를 검사한다 (규칙과 수치의 일치).
    """
    _ = cfg
    rep.head(f"[{realm}] "
             + ("입문기 검사 — 초식이 정답인가 (개화 전에는 선택지가 없는 것이 옳다)"
                if tutorial else "지배 전략 검사 — 하나가 언제나 옳은가"))

    champs = {}
    for axis in AXES:
        top = max(scores[b.name][axis] for b in builds)
        best = [b for b in builds if abs(scores[b.name][axis] - top) < 1e-6]
        if len(best) == len(builds):
            # ★ 전원 동점 = 그 축은 빌드를 **가르지 못한다**. 아무도 왕관을 쓰지 않는다.
            #   (전원 동점을 '전원 1등'으로 세면 그 축이 지배 전략을 지어낸다 — 그 버그에 한 번 속았다)
            rep.say(f"     {axis:<5} 최선: {'—':<28} (전원 동점 — 이 축은 빌드를 가르지 못한다)")
            continue
        for b in best:
            champs.setdefault(b.name, []).append(axis)
        label = " = ".join(b.name for b in best)
        rep.say(f"     {axis:<5} 최선: {label:<28} "
                f"{best[0].blurb if len(best) == 1 else '(공동 1등)'}")
    rep.say("")

    if tutorial:
        # 입문기 — '초식이 정답'이어야 한다. 규칙(승급 요건 = 주력 숙련 2)과 수치(환산표 90/180)가
        # 같은 말을 하는가. 여기서 능력치 축이 이기면 규칙과 수치가 서로 다른 말을 하는 것이다.
        won = champs.get("초식 몰빵", [])
        rep.verdict(len(won) >= len(AXES) - 2,
                    f"초식이 입문기의 답이다 ({len(won)}/{len(AXES)} 상황 1등) — "
                    f"환산표(0→1 = 3개월 · 1→2 = 6개월 vs 능력치 1년)와 승급 요건('주력 무공 숙련 2')이 "
                    f"같은 말을 한다. **개화 전에는 고를 것이 없고, 그것이 옳다** — 빌드는 개화부터다"
                    if len(won) >= len(AXES) - 2 else
                    f"입문기에 초식이 답이 아니다 (1등 {len(won)}/{len(AXES)}) — "
                    f"승급 요건은 '주력 숙련 2'를 요구하는데 수치는 다른 축을 가리킨다 (규칙과 수치의 불일치)")
        return champs

    # ① 한 빌드가 (거의) 모든 축에서 1등이면 그건 선택이 아니라 정답이다
    tyrant = [(who, ax) for who, ax in champs.items() if len(ax) >= len(AXES) - 1]
    rep.verdict(not tyrant,
                f"지배 전략 없음 — {len(champs)}개 빌드가 {len(AXES)}개 상황의 1등을 나눠 갖는다: "
                + " · ".join(f"{w}({'/'.join(a)})" for w, a in champs.items())
                if not tyrant else
                f"★ 지배 전략: {tyrant[0][0]} 이 {len(tyrant[0][1])}/{len(AXES)} 상황에서 1등이다 "
                f"({'/'.join(tyrant[0][1])}) — 이건 선택이 아니라 정답이다")

    # ② 균형 빌드가 어느 축에서도 1등이면 안 된다 (분산이 공짜면 고를 필요가 없다)
    even = next(b for b in builds if "균형" in b.name)
    rep.verdict(even.name not in champs,
                "균형 배분은 어느 상황에서도 1등이 아니다 — 분산에는 값이 있다 (고르라는 압력)"
                if even.name not in champs else
                f"★ 균형 배분이 {champs[even.name]} 에서 1등이다 — 다 키우는 것이 최선이면 선택이 없다")
    return champs


def dead_axis(cfg, rep, all_champs, budget):
    """죽은 축 — 두 경지 × 다섯 상황(10칸) 어디에서도 최선이 아닌 과목이 있는가."""
    rep.head("죽은 축 — 구간을 넣을 이유가 없는 과목이 있는가 (일류·절정 통합)")
    subj = list(subjects(cfg))
    for name in subj:
        won = all_champs.get(f"{name} 몰빵", [])
        rep.say(f"     {name:<4} 몰빵 — 최선인 자리 {len(won)}칸: {' · '.join(won) if won else '없음'}")
    rep.say("")

    dead = [n for n in subj if not all_champs.get(f"{n} 몰빵")]
    rep.verdict(not dead,
                f"죽은 축 없음 — 다섯 과목이 전부 어딘가에서 최선이다 (예산 {budget:g}일치 기준). "
                f"모든 축이 쓸모 있다"
                if not dead else
                f"★ 죽은 축: {dead} — 두 경지 어느 상황에서도 최선이 아니다. 그 과목에 구간을 넣을 이유가 없다")

    # ★ '늦게 오는 축' 은 죽은 축이 아니다 — 그것이 두 경지를 재는 이유다
    late = [n for n in subj
            if all_champs.get(f"{n} 몰빵") and not any(w.startswith("일류") for w in all_champs[f"{n} 몰빵"])]
    if late:
        rep.ok(f"늦게 오는 축: {late} — 일류에서는 최선이 아니지만 절정에서 값이 온다. "
               f"배증 비용(내공 n→n+1 = n년)의 뜻이 그것이다: **일찍 심어야 늦게 거둔다**")


def spread(cfg, rep, budget):
    """분산의 값 — 판정은 정수부만 본다. 그 절벽이 실제로 작동하는가."""
    rep.head("분산의 절벽 — 다 키우면 정말 아무것도 안 오르는가")
    realm = "일류"
    builds = standard_builds(cfg, realm, budget)
    even = next(b for b in builds if "균형" in b.name)
    solo = next(b for b in builds if b.name.startswith("신법"))

    seg = segments_per_day(cfg)
    cost1 = attr_cost_days(cfg)
    rep.say(f"     능력치 +1 = {cost1:g}일치 — 몰빵({seg}구간)이면 {cost1 / 360:.1f}년, "
            f"1구간이면 {cost1 * seg / 360:.1f}년 ({seg}배)")
    rep.say(f"     균형: 민첩 {even.attr('민첩'):.2f} → 판정 +{even.ai('민첩')} · "
            f"신법: 민첩 {solo.attr('민첩'):.2f} → 판정 +{solo.ai('민첩')}  (정수부만 주사위 옆에 선다)")

    base = 3
    gained_even = sum(even.ai(a) - base for a in ("근력", "민첩", "체력", "감각")) + (even.mastery - 3)
    gained_solo = sum(solo.ai(a) - base for a in ("근력", "민첩", "체력", "감각")) + (solo.mastery - 3)
    rep.verdict(gained_even < gained_solo,
                f"★ 같은 {budget:g}일치로 균형은 판정 +{gained_even} 를, 몰빵은 +{gained_solo} 를 얻는다 — "
                f"분산은 실수 원장에만 쌓이고 주사위 옆에는 서지 못한다"
                if gained_even < gained_solo else
                f"균형(+{gained_even})이 몰빵(+{gained_solo})에 뒤지지 않는다 — 분산이 손해가 아니다")

    rep.say("")
    rep.say(f"     그러나 자원 축은 다르다: 균형 배분도 내구 {even.dur} · 내력 {even.pool} 은 얻는다 "
            f"(실수 파생 — 정수 절벽이 없다)")
    rep.ok("몸과 단전은 조금씩이라도 자라고, 손·발·눈은 몰아야 자란다 — "
           "화후 규칙('판정은 정수, 자원은 실수')의 귀결이지 새로 만든 페널티가 아니다")

    # 캡에 닿고도 배분을 안 바꾼 자의 손실 — 천장이 실제로 무는가
    rep.say("")
    for b in standard_builds(cfg, "일류", budget):
        if b.capped_at:
            first = min(b.capped_at.values())
            rep.say(f"     {b.name:<12} 캡 도달 {first:.0f}일치 ({first / 360:.1f}년) 지점 — "
                    f"그 뒤 배분을 안 바꿨다면 {b.wasted:.0f}일치가 버려진다")
    rep.ok("천장(경지 캡)이 몰빵을 저절로 꺾는다 — 지배 전략을 막는 것은 밸런스 수치가 아니라 "
           "**천장과 시간**이다 (player_creation.yml attribute_cap_by_realm)")


def band_guard(cfg, rep, builds, realm, max_rounds):
    """★ 불가침 — 어떤 빌드도 combat_audit 의 TTK 밴드를 깨면 안 된다.

    빌드는 전투의 **모양**을 바꿀 수 있다. **길이**를 부수면 실패다 —
    내구 빌드가 20합을 만들거나 위력 빌드가 2합에 끝내면 전투가 사라진다.
    """
    rep.head(f"[{realm}] 불가침 — 빌드가 전투를 없애거나 늘어뜨리는가 (combat_audit 밴드)")
    hard_lo, hard_hi = 3, 12          # combat_audit sim_vitality_curve 와 같은 밴드
    soft_lo, soft_hi = 5, 9
    # ★ **거울 대결** — 같은 빌드끼리 붙인다 (외공 vs 외공 · 신법 vs 신법).
    #   이것이 이 검사의 유일하게 옳은 상대다. 브리프의 걱정("내구 빌드가 TTK 20합을 만들면 실패")은
    #   **대칭 대결에서만** 드러난다: 두꺼운 몸끼리 두꺼운 몸을 치면 판이 늘어지고,
    #   회피끼리 붙으면 서로 안 맞아 영원히 끝나지 않으며, 위력끼리는 두 합에 끝난다.
    #   (약한 상대를 빨리 이기는 것은 밸런스 문제가 아니다. 자기 자신을 못 이기는 것이 문제다.)
    rep.say(f"     거울 대결 (같은 빌드끼리 — 대칭) — 불가침 {hard_lo}~{hard_hi}합 · "
            f"설계 목표 {soft_lo}~{soft_hi}합")
    rep.say("")

    bad, soft = [], []
    for b in builds:
        foe = mirror_profile(cfg, b, realm)      # 나와 똑같은 사람
        ttk, ttd, _ = duel_build(cfg, b, foe, max_rounds, player_first=True)
        decided = min([t for t in (ttk, ttd) if t], default=None)   # 이 판이 끝나는 합
        atk = build_attack(cfg, b)
        rep.say(f"     {b.name:<12} {b.weapon} · 공격 {atk['atk']:>2} vs 방어 {foe['def_stat']:>2} · "
                f"내구 {b.dur:>2} │ 판이 끝나는 합 "
                f"{(str(decided) + '합' if decided else '>' + str(max_rounds) + '합 — 안 끝난다'):>16}")
        if decided is None:
            bad.append((b.name, f">{max_rounds}합"))
        elif decided < hard_lo or decided > hard_hi:
            bad.append((b.name, f"{decided}합"))
        elif not (soft_lo <= decided <= soft_hi):
            soft.append((b.name, f"{decided}합"))

    rep.say("")
    rep.verdict(not bad,
                f"거울 대결 전부 불가침 밴드({hard_lo}~{hard_hi}합) 안 — "
                f"빌드는 전투의 **모양**을 바꾸지 **길이**를 부수지 않는다. "
                f"내구 빌드도 늘어뜨리지 않고, 회피 빌드도 교착시키지 않는다"
                if not bad else
                f"거울 대결이 밴드를 깬다: {bad} — "
                f"내구·회피 빌드가 전투를 늘어뜨렸거나 위력 빌드가 전투를 없앴다")
    if soft:
        rep.warn(f"[{realm}] 거울 대결이 설계 목표({soft_lo}~{soft_hi}합) 밖 (불가침은 아니다): {soft}")
    else:
        rep.ok(f"거울 대결 전부 설계 목표 {soft_lo}~{soft_hi}합 — 빌드를 바꿔도 한 판의 길이는 그대로다")


# ══════════════════════════════════════════════════════════════════════════════

def main():
    ap = argparse.ArgumentParser(description="혼천 성장 축·빌드 검수")
    ap.add_argument("--lint-only", action="store_true", help="① 등록 정합 린트만")
    ap.add_argument("--sim-only", action="store_true", help="② 빌드 시뮬만")
    ap.add_argument("--budget", type=float, default=1800.0, help="수련 예산(일치) — 기본 1800 (5년)")
    ap.add_argument("--rounds", type=int, default=25, help="TTK 상한 합 수")
    args = ap.parse_args()

    rep = Report()
    rep.say("╔" + "═" * 70 + "╗")
    rep.say("║" + "  혼천 성장 감사 — growth_audit".ljust(69) + "║")
    rep.say("║" + "  빌드를 재는 자 — 선택지가 선택지인가".ljust(64) + "║")
    rep.say("╚" + "═" * 70 + "╝")

    try:
        cfg = load_all()
    except YamlError as e:
        print(f"{FAIL} config 파싱 실패: {e}", file=sys.stderr)
        return 2

    subj = subjects(cfg)
    rep.say(f"  config {len(cfg)}종 적재 · 수련 과목 {len(subj)}종 · "
            f"심법 {len(simbeop_all(cfg))}종 · 경지 {len(realm_names(cfg))}단")

    if not args.sim_only:
        lint(cfg, rep)
    if not args.lint_only:
        simulate(cfg, rep, args.budget, args.rounds)

    rep.say()
    rep.say("═" * 72)
    n_v, n_w = len(rep.violations), len(rep.warnings)
    if n_v == 0 and n_w == 0:
        rep.say("  총평: ✅ 위반 0건 · 경고 0건 — 다섯 개의 길이 있고, 어느 하나도 정답이 아니다")
    else:
        rep.say(f"  총평: 위반 {n_v}건 · 경고 {n_w}건")
        if n_v:
            rep.say("")
            rep.say(f"  ── 위반 ({FAIL}) — 선택지가 선택지가 아니다")
            for i, v in enumerate(rep.violations, 1):
                rep.say(f"    {i:2}. {v}")
        if n_w:
            rep.say("")
            rep.say("  ── 경고 (⚠️) — 굴러가지만 의도한 감각이 아닐 수 있다")
            for i, w in enumerate(rep.warnings, 1):
                rep.say(f"    {i:2}. {w}")
    rep.say("═" * 72)
    rep.dump()
    return 1 if n_v else 0


if __name__ == "__main__":
    sys.exit(main())
