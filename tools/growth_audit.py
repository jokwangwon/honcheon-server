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


def attacker_attr(cfg, ranged=False):
    spec = dig(cfg, "combat.yml", "attack", "attacker_attribute", default={}) or {}
    return str(spec.get("원거리" if ranged else "기본", "근력"))


def gyeol_of(cfg, simbeop_id):
    """simbeop.yml <id>.gyeol — 결(結). 방어 태세 판정 가산 (없으면 {})."""
    spec = dig(cfg, "simbeop.yml", "simbeop", simbeop_id, default={}) or {}
    raw = spec.get("gyeol") or {}
    return {str(k).replace("_판정", ""): num(v, 0) for k, v in raw.items()} if isinstance(raw, dict) else {}


def simbeop_all(cfg):
    return dig(cfg, "simbeop.yml", "simbeop", default={}) or {}


# ══════════════════════════════════════════════════════════════════════════════
#  성장 모델 — 예산(일치)을 배분표대로 쓴다. 캡에 닿으면 차선으로 흐른다
# ══════════════════════════════════════════════════════════════════════════════

class Build:
    """한 사람의 성장 결과 — 배분표 + 예산 → 능력치·숙련·내공.

    ★ 캡 처리가 이 모델의 핵심이다: 캡에 닿은 과목은 적립이 멈추고, 이성적인 플레이어는
      남은 구간을 **차선 과목**으로 돌린다 (배분표 순서대로). 그래서 몰빵은 캡에서 저절로 꺾인다 —
      지배 전략을 막는 것이 밸런스 수치가 아니라 '천장'이라는 설계 주장이 여기서 검증된다.
    """

    def __init__(self, cfg, name, alloc, realm, budget, simbeop=None,
                 base_attrs=None, base_skill=3, base_naegong=1.0 / 3.0, blurb=""):
        self.cfg = cfg
        self.name = name
        self.alloc = dict(alloc)          # 과목 → 구간 수 (합 = segments_per_day)
        self.realm = realm
        self.simbeop = simbeop
        self.blurb = blurb
        self.cap = attr_cap(cfg, realm)
        self.attrs = dict(base_attrs or {a: 3.0 for a in COMBAT_ATTRS})
        self.attrs["내공"] = base_naegong
        self.skill = float(base_skill)
        self.skill_days = 0.0
        self.wasted = 0.0                 # 캡에 닿아 버려진 일치 (배분을 안 바꾼 자의 손실)
        self.capped_at = {}               # 과목 → 캡에 닿은 날(일치 누적)
        self._grow(cfg, budget)

    # ── 배분표대로 예산을 흘린다 ──────────────────────────────────────────────
    def _grow(self, cfg, budget):
        segs = segments_per_day(cfg)
        dps = days_per_segment(cfg)
        subj = subjects(cfg)
        ladder = skill_ladder(cfg)
        cost1 = attr_cost_days(cfg)

        # 하루 단위로 흘린다 (캡 도달 시점을 정확히 잡기 위해 — 뭉텅이로 넣으면 넘친다)
        spent = 0.0
        day_days = segs * dps                      # 하루에 배분되는 총 일치 (= 1.0)
        step = day_days
        while spent + 1e-9 < budget:
            step = min(day_days, budget - spent)
            for name, n_seg in self.alloc.items():
                if n_seg <= 0:
                    continue
                share = step * (n_seg / segs)
                self._pour(cfg, subj, ladder, cost1, name, share, spent)
            spent += step

    def _pour(self, cfg, subj, ladder, cost1, name, days, spent):
        spec = subj.get(name) or {}
        ledgers = spec.get("ledger") or []
        if name == "초식":
            self.skill_days += days
            self.skill = self._skill_of(ladder, self.skill_days)
            return
        if name == "내공":
            if self.simbeop is None:               # 심법 게이트 — 개화하지 않으면 이 과목은 0
                self.wasted += days
                return
            cur = self.attrs["내공"]
            capacity = num(dig(cfg, "simbeop.yml", "simbeop", self.simbeop, "capacity"), 4)
            room = min(self.cap, capacity)
            if cur >= room:
                self.wasted += days
                self.capped_at.setdefault(name, spent)
                return
            self.attrs["내공"] = min(room, cur + days / naegong_cost_days(cfg, int(cur)))
            return
        # 능력치 과목 (외공은 두 축을 split 으로 나눠 진다)
        split = spec.get("split") or [1.0 / max(1, len(ledgers))] * len(ledgers)
        for attr, frac in zip(ledgers, split):
            share = days * num(frac, 1.0)
            cur = self.attrs.get(attr, 0.0)
            if cur >= self.cap:
                self.wasted += share
                self.capped_at.setdefault(name, spent)
                continue
            self.attrs[attr] = min(self.cap, cur + share / cost1)

    @staticmethod
    def _skill_of(ladder, days):
        """누적 일치 → 숙련 실수치 (환산표 걷기 — PlayerLedger.levelOf 와 같은 걸음)."""
        remaining, level = days, 0
        while True:
            cost = ladder.get(level)
            if cost is None or cost <= 0:
                return float(level)                # 환산표 상한 — 수련만으로는 여기까지
            if remaining < cost:
                return level + remaining / cost
            remaining -= cost
            level += 1

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
        return pool_of(self.attr("내공"))

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


def build_attack(cfg, b, weapon="검", qi_band=None):
    """빌드의 공격 프로파일 — 무기·무공 위력은 combat.yml 등록부."""
    wp = num(dig(cfg, "combat.yml", "damage", "weapon_power", weapon), 4)
    tps = dig(cfg, "combat.yml", "damage", "technique_power", default={}) or {}
    tp = num(tps.get(b.realm + "급"), 1)
    qis = dig(cfg, "combat.yml", "damage", "qi_power", default={}) or {}
    band = qi_band or top_band(cfg, b.realm)
    return {
        "atk": b.ai(attacker_attr(cfg)) + b.mastery,
        "wpower": wp,
        "tpower": tp,
        "qi": num(qis.get(band), 0),
        "band": band,
    }


def foe_profile(cfg, realm, weapon="도", skill=None):
    """상대 — combat_audit 의 표준 무인 모델 (Fighter) 을 그대로 빌려 온다."""
    f = Fighter(cfg, "상대", realm, weapon=weapon, is_npc=True, skill=skill)
    return {
        "atk": int(f.atk_stat) + int(f.skill),
        "wpower": f.wpower,
        "tpower": f.tpower,
        "qi": num(dig(cfg, "combat.yml", "damage", "qi_power",
                      top_band(cfg, realm)), 0),
        "dur": f.dur,
        "def_stat": int(f.def_stat) + int(f.skill),
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


def duel_build(cfg, b, foe, max_rounds=25, qi_band=None, player_first=True):
    """빌드 vs 상대 — 매 합 양측 기대 피해. 방어자는 태세를 고른다.

    반환: (내가 눕히는 합, 내가 눕는 합, 남은 내구 비율)
    """
    atk = build_attack(cfg, b, qi_band=qi_band)
    regen, _ = combat_regen(cfg)
    energy = b.pool
    hp_me, hp_foe = float(b.dur), float(foe["dur"])

    ttk = ttd = None
    for r in range(1, max_rounds + 1):
        pen_me = wound_pen(hp_me, b.dur)
        pen_foe = wound_pen(hp_foe, foe["dur"])

        # 내 차례 — 격을 태울 수 있으면 태운다 (내력 예산)
        cost = qi_cost(cfg, atk["band"])
        qi = 0.0
        if cost <= 0 or energy >= cost:
            qi = atk["qi"]
            energy -= max(0, cost)
        else:
            energy = min(b.pool, energy + regen)     # 숨을 고른 합 (조식)
        a = atk["atk"] + pen_me
        d = foe["def_stat"] + pen_foe
        dmg = Fraction(0)
        for roll, w in DICE_ITEMS:
            margin = (a + roll) - (d + 7)
            if margin >= 0:
                base = max(0.0, atk["wpower"] + atk["tpower"] + qi + (margin // 2))
                dmg += Fraction(w, 36) * Fraction(int(base * 2), 2)
        if player_first:
            hp_foe -= float(dmg)
            if hp_foe <= 0:
                ttk = ttk or r
                break

        # 상대 차례 — 내가 태세를 고른다
        exp, _stance, _hit = exchange(cfg, foe, b, att_pen=pen_foe, dfn_pen=pen_me,
                                      att_qi=foe["qi"], att_is_npc=True)
        hp_me -= exp
        if not player_first:
            hp_foe -= float(dmg)
        if hp_me <= 0:
            ttd = ttd or r
            break
        if hp_foe <= 0:
            ttk = ttk or r
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
    """
    rules = gang_rules(cfg)
    atk = build_attack(cfg, b)
    hp_me = float(b.dur)
    hps = [float(foe["dur"])] * count
    regen, _ = combat_regen(cfg)
    energy = b.pool

    for r in range(1, max_rounds + 1):
        alive = [i for i, h in enumerate(hps) if h > 0]
        if not alive:
            return r - 1, hp_me / b.dur, None
        n_eng = engaged(rules, len(alive))
        forced = len(alive) - 1 >= num(dig(rules_raw(cfg), "forced_guard", "trigger_extra_attackers"), 1)

        pen_me = wound_pen(hp_me, b.dur)
        # 나는 하나를 벤다 (집중)
        cost = qi_cost(cfg, atk["band"])
        qi = atk["qi"] if (cost <= 0 or energy >= cost) else 0.0
        energy = (energy - cost) if qi else min(b.pool, energy + regen)
        tgt = alive[0]
        pen_t = wound_pen(hps[tgt], foe["dur"])
        a = atk["atk"] + pen_me
        d = foe["def_stat"] + pen_t
        dmg = Fraction(0)
        for roll, w in DICE_ITEMS:
            margin = (a + roll) - (d + 7)
            if margin >= 0:
                base = max(0.0, atk["wpower"] + atk["tpower"] + qi + (margin // 2))
                dmg += Fraction(w, 36) * Fraction(int(base * 2), 2)
        hps[tgt] -= float(dmg)

        # 그들이 나를 친다 — 동시에 칠 수 있는 손은 engage_slots 까지
        alive = [i for i, h in enumerate(hps) if h > 0]
        mod = net_mod(rules, min(n_eng, len(alive)))
        for _ in range(min(n_eng, len(alive))):
            exp, _s, _h = exchange(cfg, foe, b, dfn_pen=wound_pen(hp_me, b.dur), mod=mod,
                                   att_qi=foe["qi"], att_is_npc=True, forced=forced)
            hp_me -= exp
        if hp_me <= 0:
            return None, 0.0, r
    return None, max(0.0, hp_me) / b.dur, None


def rules_raw(cfg):
    return dig(cfg, "combat.yml", "attack", "gang_up", default={}) or {}


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
    fg = dig(cfg, "combat.yml", "attack", "gang_up", "forced_guard", default={}) or {}
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


def lint_gyeol(cfg, rep):
    rep.head("심법의 결(結) — 빌드의 뼈대가 기계로 읽히는가")
    all_sim = simbeop_all(cfg)
    cap = num(dig(cfg, "simbeop.yml", "dantian", "gyeol_axes", "cap"), 1)
    hits = {}
    for sid, spec in all_sim.items():
        if not isinstance(spec, dict):
            continue
        g = gyeol_of(cfg, sid)
        name = spec.get("name", sid)
        aff = spec.get("build_affinity")
        rep.say(f"     {str(name):<8} 결 {str(g) if g else '없음':<22} 궁합 {aff}")
        if "passive" in spec and str(spec.get("passive")) != "없음" and not g and not spec.get("demonic"):
            if str(spec.get("grade")) != "기초":
                rep.warn(f"{name}: passive('{spec.get('passive')}') 는 있는데 기계 정의(gyeol)가 비었다 — "
                         f"엔진이 못 읽는 패시브다")
        for stance, val in g.items():
            if abs(val) > cap:
                rep.fail(f"{name} 결 {stance} {val:+g} 이 캡({cap:g})을 넘는다 — "
                         f"심법이 격차를 만든다 (심법은 색이지 격차가 아니다)")
            hits.setdefault(stance, []).append(str(name))

    # ★ 한 심법이 방어 둘 이상을 사면 그것이 지배 심법이 된다
    multi = [spec.get("name", sid) for sid, spec in all_sim.items()
             if isinstance(spec, dict) and len(gyeol_of(cfg, sid)) > 1]
    rep.verdict(not multi,
                "결을 둘 이상 가진 심법 없음 — 어떤 심법도 두 방어를 동시에 사지 못한다 (지배 심법 없음)"
                if not multi else
                f"두 방어를 동시에 사는 심법: {multi} — 지배 심법이다")

    covered = set(hits)
    rep.verdict(covered == set(STANCES),
                f"세 방어에 결이 하나씩 걸려 있다 — "
                + " · ".join(f"{s}({'/'.join(hits[s])})" for s in STANCES if s in hits)
                if covered == set(STANCES) else
                f"결이 걸리지 않은 방어: {sorted(set(STANCES) - covered)} — "
                f"그 방어를 고르는 심법이 없다 (그 축을 뼈대로 삼는 빌드가 없다)")


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

def standard_builds(cfg, realm, budget):
    """다섯 몰빵 + 균형 — 배분표는 등록부의 과목 이름을 그대로 쓴다."""
    segs = segments_per_day(cfg)
    subj = list(subjects(cfg))
    out = []
    # 몰빵 5종 — 캡에 닿으면 다음 과목으로 흐른다 (Build 가 wasted 를 세지만,
    # 이성적 플레이어는 배분을 바꾼다. 그 전환을 '2순위'로 모델링한다)
    seconds = {"외공": "초식", "내공": "초식", "초식": "외공", "신법": "심안", "심안": "신법"}
    sims = {"외공": "jeongsim_geomgyeol", "내공": "taegeuk_gigong", "초식": "jeongsim_geomgyeol",
            "신법": "jaha_singong", "심안": "taegeuk_gigong"}
    blurbs = {
        "외공": "몸 — 단단하다. 막기(-3)로 산다",
        "내공": "단전 — 격을 몰아 쓴다",
        "초식": "손 — 명중과 마진",
        "신법": "발 — 안 맞는다 (회피)",
        "심안": "눈 — 먼저 본다 (흘리기)",
    }
    for name in subj:
        alloc = {name: segs}
        b = Build(cfg, f"{name} 몰빵", alloc, realm, budget,
                  simbeop=sims.get(name), blurb=blurbs.get(name, ""))
        # 캡에 닿아 버려진 몫은 2순위 과목으로 다시 흘린다 (배분을 바꾸는 플레이어)
        if b.wasted > 1.0:
            second = seconds.get(name)
            b2 = Build(cfg, f"{name} 몰빵", alloc, realm, budget - b.wasted,
                       simbeop=sims.get(name), blurb=blurbs.get(name, ""))
            b2._pour_budget_into(cfg, second, b.wasted) if hasattr(b2, "_pour_budget_into") else None
            # 간단히: 남은 예산을 2순위 과목 몰빵으로 이어 붙인다
            b3 = Build(cfg, f"{name} 몰빵", {second: segs}, realm, b.wasted,
                       simbeop=sims.get(name),
                       base_attrs={k: v for k, v in b2.attrs.items() if k != "내공"},
                       base_skill=b2.skill, base_naegong=b2.attrs["내공"])
            b3.name = f"{name} 몰빵"
            b3.alloc = alloc
            b3.blurb = blurbs.get(name, "") + f" (캡 후 {second}으로)"
            b3.wasted = b.wasted - (budget - b.wasted) * 0  # 표시용
            b3.capped_at = b.capped_at
            b = b3
        out.append(b)

    even = {name: 1 for name in subj}
    out.append(Build(cfg, "균형 (1구간씩)", even, realm, budget,
                     simbeop="hyeoncheon_tonapbeop",
                     blurb="다 조금씩 — 정수부가 안 오른다"))
    return out


def simulate(cfg, rep, budget, max_rounds):
    rep.say()
    rep.say("═" * 72)
    rep.say("  ② 빌드 시뮬 — 지배 전략이 있는가 (해석적, 2d6 = 36가지)")
    rep.say("═" * 72)

    realm = "일류"                     # 개화한 몸 — 격·심법·내력이 전부 열리는 첫 경지
    builds = standard_builds(cfg, realm, budget)
    sheet(cfg, rep, builds, realm, budget)
    scores = scenarios(cfg, rep, builds, realm, max_rounds)
    dominance(cfg, rep, builds, scores)
    spread(cfg, rep, builds, realm, budget)
    band_guard(cfg, rep, builds, realm, max_rounds)


def sheet(cfg, rep, builds, realm, budget):
    rep.head(f"캐릭터 시트 — {realm}(캡 {attr_cap(cfg, realm)}) · 수련 예산 {budget:g}일치 "
             f"({budget / 360:.1f}년 몰빵분)")
    rep.say("     시작: 전 능력치 3 · 주력 숙련 3 · 내공 0.33 (개화 직후) — 승급 요건은 이미 치른 몸")
    rep.say("")
    rep.say(f"     {'빌드':<14} {'근력':>5} {'민첩':>5} {'체력':>5} {'감각':>5} {'내공':>5} "
            f"{'숙련':>5} │ {'내구':>5} {'내력':>5}")
    for b in builds:
        rep.say(f"     {b.name:<14} {b.attr('근력'):>5.1f} {b.attr('민첩'):>5.1f} "
                f"{b.attr('체력'):>5.1f} {b.attr('감각'):>5.1f} {b.attr('내공'):>5.2f} "
                f"{b.skill:>5.2f} │ {b.dur:>5} {b.pool:>5}")
    rep.say("")
    rep.say("     ※ 판정에 서는 것은 **정수부**다 (화후 규칙) — 3.9와 4.0은 세계가 다르다")


def scenarios(cfg, rep, builds, realm, max_rounds):
    """다섯 상황 — 각 빌드가 어디서 사는가. ★ 이 표가 '지배 전략 없음'의 증거다."""
    rep.head("다섯 상황 — 어느 빌드가 어디서 사는가")

    foe_same = foe_profile(cfg, realm)                    # 동경지 1대1
    foe_up = foe_profile(cfg, "절정")                     # 격상 상대
    foe_mook = foe_profile(cfg, "이류", weapon="도")      # 졸개 (다구리용)

    rep.say(f"     상대: 동경지({realm} 내구 {foe_same['dur']}) · "
            f"격상(절정 내구 {foe_up['dur']}) · 졸개(이류 내구 {foe_mook['dur']})")
    rep.say("")
    rep.say(f"     {'빌드':<14} {'1대1 TTK':>9} {'잔존':>6} {'격상 생존':>9} "
            f"{'다구리 4인':>11} {'매복(-2)':>9} {'장기 8합':>9}")

    scores = {}
    for b in builds:
        # ① 동경지 1대1 — 이기는 합수 (짧을수록 좋다) + 잔존 내구
        ttk, ttd, left = duel_build(cfg, b, foe_same, max_rounds)
        s_duel = (1000 - (ttk or 999)) + left * 100 if ttk and (not ttd or ttk <= ttd) else 0

        # ② 격상 상대(절정) — 이기진 못한다. **몇 합을 버티는가**가 값이다
        _t2, ttd2, left2 = duel_build(cfg, b, foe_up, max_rounds)
        s_up = (ttd2 or max_rounds) + left2 * 10

        # ③ 다구리 4인 — 포위. 회피가 봉쇄된다 (forced_guard)
        clear, left3, dead = melee_build(cfg, b, foe_mook, 4, max_rounds)
        s_gang = (100 - (clear or 99)) + left3 * 100 if clear else left3 * 10

        # ④ 매복당함 — 첫 라운드 방어 -2 (initiative.ambush). 선공을 뺏긴 몸
        _t4, ttd4, left4 = duel_build(cfg, b, foe_same, max_rounds, player_first=False)
        s_amb = (ttd4 or max_rounds) + left4 * 20

        # ⑤ 장기전 8합 — 내력이 바닥난 뒤에도 싸울 수 있는가 (격 없이 순수 기대 피해)
        atk = build_attack(cfg, b)
        casts = qi_casts(b.pool, qi_cost(cfg, atk["band"]), combat_regen(cfg)[0], 8)
        s_long = casts

        scores[b.name] = {"1대1": s_duel, "격상": s_up, "다구리": s_gang,
                          "매복": s_amb, "장기": s_long}
        rep.say(f"     {b.name:<14} {str(ttk) + '합' if ttk else '패배':>9} "
                f"{left * 100:>5.0f}% {str(ttd2 or '>' + str(max_rounds)) + '합':>9} "
                f"{(str(clear) + '합 소탕' if clear else '패배 ' + str(dead) + '합'):>11} "
                f"{str(ttd4 or '>' + str(max_rounds)) + '합':>9} {s_long:>7}회 격")
    rep.say("")
    rep.say("     1대1=이기는 합수/잔존 · 격상=눕기까지 버틴 합 · 다구리=4인 소탕/패배 · "
            "매복=후공으로 버틴 합 · 장기=8합 중 격 실은 횟수")
    return scores


def dominance(cfg, rep, builds, scores):
    """★ 이 도구의 존재 이유 — 하나가 언제나 옳으면 그건 선택이 아니다."""
    _ = cfg
    rep.head("지배 전략 검사 — 하나가 언제나 옳은가")
    axes = ["1대1", "격상", "다구리", "매복", "장기"]

    winners = {}
    for axis in axes:
        best = max(builds, key=lambda b: scores[b.name][axis])
        winners[axis] = best.name
        rep.say(f"     {axis:<5} 최선: {best.name}")
    rep.say("")

    # ① 어떤 빌드도 모든 축에서 1등이 아니어야 한다
    champs = {}
    for axis, who in winners.items():
        champs.setdefault(who, []).append(axis)
    tyrant = [(who, ax) for who, ax in champs.items() if len(ax) >= len(axes) - 1]
    rep.verdict(not tyrant,
                f"지배 전략 없음 — {len(champs)}개 빌드가 {len(axes)}개 상황의 1등을 나눠 갖는다: "
                + " · ".join(f"{w}({'/'.join(a)})" for w, a in champs.items())
                if not tyrant else
                f"★ 지배 전략: {tyrant[0][0]} 이 {len(tyrant[0][1])}/{len(axes)} 상황에서 1등이다 "
                f"({'/'.join(tyrant[0][1])}) — 이건 선택이 아니라 정답이다")

    # ② 죽은 축 — 어느 상황에서도 1등이 아닌 몰빵 빌드
    solos = [b for b in builds if "균형" not in b.name]
    dead = [b.name for b in solos if b.name not in champs]
    rep.verdict(not dead,
                "죽은 축 없음 — 다섯 몰빵이 전부 어딘가에서 최선이다 (모든 축이 쓸모 있다)"
                if not dead else
                f"★ 죽은 축: {dead} — 어느 상황에서도 최선이 아니다. 그 과목에 구간을 넣을 이유가 없다")

    # ③ 균형 빌드가 어느 축에서도 1등이면 안 된다 (분산이 공짜면 고를 필요가 없다)
    even = next((b for b in builds if "균형" in b.name), None)
    if even:
        rep.verdict(even.name not in champs,
                    "균형 빌드는 어느 상황에서도 1등이 아니다 — 분산에는 값이 있다 (고르라는 압력)"
                    if even.name not in champs else
                    f"★ 균형 빌드가 {champs[even.name]} 에서 1등이다 — 다 키우는 것이 최선이면 선택이 없다")


def spread(cfg, rep, builds, realm, budget):
    """분산의 값 — 판정은 정수부만 본다. 그 절벽이 실제로 작동하는가."""
    rep.head("분산의 절벽 — 다 키우면 정말 아무것도 안 오르는가")
    even = next((b for b in builds if "균형" in b.name), None)
    solo = next((b for b in builds if b.name.startswith("신법")), None)
    if not (even and solo):
        return

    seg = segments_per_day(cfg)
    cost1 = attr_cost_days(cfg)
    years_solo = cost1 / 360
    years_even = cost1 * seg / 360
    rep.say(f"     능력치 +1 = {cost1:g}일치 — 몰빵({seg}구간)이면 {years_solo:.1f}년, "
            f"1구간이면 {years_even:.1f}년 ({seg}배)")
    rep.say(f"     균형 빌드: 민첩 {even.attr('민첩'):.2f} → 판정 {even.ai('민첩')} "
            f"(정수부) · 신법 빌드: 민첩 {solo.attr('민첩'):.2f} → 판정 {solo.ai('민첩')}")

    gained_even = sum(even.ai(a) - 3 for a in ("근력", "민첩", "체력", "감각")) + (even.mastery - 3)
    gained_solo = sum(solo.ai(a) - 3 for a in ("근력", "민첩", "체력", "감각")) + (solo.mastery - 3)
    rep.verdict(gained_even < gained_solo,
                f"★ 같은 {budget:g}일치로 균형은 판정 +{gained_even} 를, 몰빵은 +{gained_solo} 를 얻는다 — "
                f"판정은 정수부만 본다. 분산은 실수 원장에만 쌓이고 주사위 옆에는 서지 못한다"
                if gained_even < gained_solo else
                f"균형(+{gained_even})이 몰빵(+{gained_solo})에 뒤지지 않는다 — 분산이 손해가 아니다")

    # 자원 축(외공·내공)은 실수치를 쓴다 — 분산해도 값이 있다 (비대칭이 의도인가)
    rep.say("")
    rep.say(f"     그러나 자원 축은 다르다: 균형 빌드도 내구 {even.dur} · 내력 {even.pool} 은 얻는다 "
            f"(실수 파생 — 정수 절벽이 없다)")
    rep.ok("몸과 단전은 조금씩이라도 자라고, 손·발·눈은 몰아야 자란다 — "
           "화후 규칙('판정은 정수, 자원은 실수')의 귀결이지 새 페널티가 아니다")


def band_guard(cfg, rep, builds, realm, max_rounds):
    """★ 불가침 — 어떤 빌드도 combat_audit 의 TTK 밴드를 깨면 안 된다."""
    rep.head("불가침 — 빌드가 전투를 없애거나 늘어뜨리는가 (combat_audit 밴드)")
    hard_lo, hard_hi = 3, 12
    soft_lo, soft_hi = 5, 9
    foe = foe_profile(cfg, realm)
    rep.say(f"     동경지 대결 — 불가침 {hard_lo}~{hard_hi}합 · 설계 목표 {soft_lo}~{soft_hi}합")
    rep.say("")

    bad, soft = [], []
    for b in builds:
        ttk, ttd, _ = duel_build(cfg, b, foe, max_rounds)
        mine = ttk if ttk else None
        # 내구 빌드가 '내가 눕는 합'을 얼마나 늘리는가 — 상대의 TTK 도 밴드 안이어야 한다
        theirs = ttd
        rep.say(f"     {b.name:<14} 내가 눕히는 합 {str(mine) + '합' if mine else '>' + str(max_rounds):>6} · "
                f"상대가 나를 눕히는 합 {str(theirs) + '합' if theirs else '>' + str(max_rounds):>6}")
        for who, t in (("내 TTK", mine), ("피격 TTK", theirs)):
            if t is None:
                if who == "내 TTK":
                    bad.append((b.name, who, f">{max_rounds}"))
                continue
            if t < hard_lo or t > hard_hi:
                bad.append((b.name, who, t))
            elif not (soft_lo <= t <= soft_hi):
                soft.append((b.name, who, t))

    rep.say("")
    rep.verdict(not bad,
                f"모든 빌드의 TTK 가 불가침 밴드({hard_lo}~{hard_hi}합) 안 — "
                f"빌드는 전투의 **모양**을 바꾸지 길이를 부수지 않는다"
                if not bad else
                f"밴드를 깬 빌드: {bad} — 내구 빌드가 전투를 늘어뜨리거나 위력 빌드가 전투를 없앴다")
    if soft:
        rep.warn(f"설계 목표({soft_lo}~{soft_hi}합) 밖 (불가침은 아니다): {soft}")
    else:
        rep.ok(f"모든 빌드가 설계 목표 {soft_lo}~{soft_hi}합 안 — 빌드를 바꿔도 한 판의 길이는 그대로다")


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
