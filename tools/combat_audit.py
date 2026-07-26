#!/usr/bin/env python3
"""전투 감사 — 혼천 무공·전투의 눈과 자.

맵에는 `/혼천 검수`, 리소스팩에는 `texture_audit.py`, 게임 시스템에는 `game_audit.py`가 있다.
무공(스킬)과 전투에는 없었다. 이 도구는 config/ 를 읽어 두 가지를 한다:

  ① 전투 정합 린트 — 무공이 참조하는 것(경지·등급·능력치·선행·무기·기술)이 실재하는가.
                     격 게이트 정합(요구 격이 요구 경지에서 실제로 열리는가).
                     내력 수지(그 경지의 내력 풀로 이 무공을 몇 번 쓰는가).
                     액션 데이터(프레임·경직·쿨다운) 커버리지.

  ② 전투 시뮬 (해석적) — 2d6은 36가지뿐이다. 몬테카를로를 쓰지 않는다.
                     TTK(합 수) / 내력 곡선 / 격 상성 / 무기 등급 / 협공 / NPC 전의.

★ 전투 판정 v2 (B-177 · combat.yml combat_v2.enabled) — 등록부가 켜져 있으면 시뮬의 산술이
  v2 다: 명중 = 획(기하 — 판정 없음·항상 타격), 피해 = max(1, 공격력 − 방어력) × 크리 기대,
  공격력 = 무기 위력 + 기술 숙련 + 능력치(병기 축) + 격 — 4항 (무공 위력표·부상/고갈/협공
  판정 보정은 v2 에 없다 — 실릴 판정이 없다). enabled: false 면 v1(2d6) 그대로 잰다 —
  엔진의 복귀 스위치와 같은 문이다. v2 가 라이브인 동안 v1 산술로 재면 「위반 0」이 거짓말이 된다.

봇·엔진 코드를 복제하지 않는다 — config 수치만으로 계산한다.
config 를 고치지 않는다 — 재기만 한다.

사용법:
    python3 tools/combat_audit.py                # 전체
    python3 tools/combat_audit.py --lint-only    # ① 정합 린트만
    python3 tools/combat_audit.py --sim-only     # ② 전투 시뮬만
    python3 tools/combat_audit.py --rounds 30    # TTK 상한 합 수 (기본 25)

외부 라이브러리 없음 (game_audit.py 의 YAML 서브셋 파서·Report 를 그대로 계승).
종료 코드: 위반(❌) 1건 이상이면 1, 아니면 0.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from fractions import Fraction

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from game_audit import (  # noqa: E402  — 문법·출력 형식 계승 (읽기 전용 재사용)
    FAIL,
    Report,
    YamlError,
    dig,
    load_all,
    mid,
    num,
    realm_names,
)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 2d6 — 36가지가 전부다
DICE = {}
for _a in range(1, 7):
    for _b in range(1, 7):
        DICE[_a + _b] = DICE.get(_a + _b, 0) + 1
DICE_ITEMS = sorted(DICE.items())


# ══════════════════════════════════════════════════════════════════════════════
#  config 에서 뽑아내는 축 (모두 정본 파일에서 읽는다 — 하드코딩 금지)
# ══════════════════════════════════════════════════════════════════════════════

# 무공 category → combat.yml weapon_power 의 어느 무기인가 (도구의 매핑 가정)
CATEGORY_WEAPON = {
    "검법": "검", "도법": "도", "창법": "창", "봉법": "봉", "단검술": "단검",
    "편법": "편",     # 鞭 — 당가 편법 (2026-07-26). 15계열째 · 간격 최장 위력 최저
    "권법": "맨손", "장법": "맨손", "박투": "맨손", "금나술": "맨손",
    "퇴법": "맨손",   # 철혈백사십팔퇴 (팽가 확장 2026-07-26) — 발도 맨손의 값이다
    "선법": "부채",   # 학우선법 (제갈 확장 2026-07-26) — 문사의 병기
    "수공": "맨손",   # 일엽수~청죽수 (모용 2026-07-26) — 수공의 명가
    "지법": "맨손",   # 유성지·추혼지 (모용 2026-07-26) — 손끝의 기
    "독공": "맨손", "합격진": "검",
    "음공": None,     # 사자후 (소림 2026-07-26) — 소리에는 병기가 없다. 값이 판정에만 있어 피해축 밖이다
    "궁술": "활", "암기": "암기", "암살술": "단검",
    "경공": None, "신법": None, "은신술": None, "의술": None, "심법": None,
}
NO_DAMAGE_CATEGORIES = {"경공", "신법", "은신술", "의술", "심법", "음공"}


def realm_axis(cfg):
    """경지별 표준 무인 — cultivation.yml 승급 요건에서 직접 읽는다.

    ★ 요건에 '내공 N'이 없는 경지(일류 — 요건이 '개화'다 · 생사경 — 요건이 없다)는
      cultivation.yml realm_naegong_floor 가 정본이다. 그전엔 이 도구에도 엔진에도 '일류면 1.0'이
      손으로 박혀 있었다 — 같은 숫자가 두 군데 살면 언젠가 갈라진다.
    """
    names = realm_names(cfg)
    caps = dig(cfg, "player_creation.yml", "attribute_cap_by_realm", default={}) or {}
    floors = dig(cfg, "cultivation.yml", "realm_naegong_floor", default={}) or {}
    stages = {s.get("name"): s for s in (dig(cfg, "cultivation.yml", "cultivation_stages", default=[]) or [])
              if isinstance(s, dict)}
    axis = {}
    skill, naegong = 0, 0.0
    for nm in names:
        reqs = dig(stages.get(nm, {}), "promotion", "requirements", default=[]) or []
        for r in reqs:
            m = re.search(r"주력 무공 숙련\s*(\d+)", str(r))
            if m:
                skill = int(m.group(1))
            m = re.search(r"내공\s*(\d+)", str(r))
            if m:
                naegong = float(m.group(1))
        if isinstance(floors.get(nm), (int, float)) and float(floors[nm]) > naegong:
            naegong = float(floors[nm])   # 보충 등록 — 요건이 수치를 말하지 않는 경지의 단전
        cap = int(num(caps.get(nm), 3))
        axis[nm] = {
            "cap": cap,
            "attr": max(1, cap - 1),      # 표준 무인 = 상한 -1 (상한을 찍은 자는 표준이 아니다)
            "skill": skill,
            "naegong": naegong,
            "req_naegong_declared": naegong > 0,
        }
    return names, axis


def pool_of(naegong, cfg=None):
    """내력 풀 — internal_energy.yml 정본 (pool_curve · pool_per_year).

    ★ 축기 '세월'에 비례한다: 축기_세월(x) = x(x+1)/2 년 (cultivation.yml accumulation_cost 의 누적).
      단전에 쌓이는 것은 단계가 아니라 앉아 있던 시간이다. 구판(선형 ×3)은 절대 넘치지 않았다.
    cfg 를 주면 config 를 읽고, 안 주면 등록된 기본값(3 · 세월 곡선)으로 선다 — 계산은 한 군데서만 한다.
    """
    if naegong <= 0:
        return 0
    per_year = 3.0
    curve = "누적_축기_년수"
    if cfg is not None:
        inner = dig(cfg, "internal_energy.yml", "internal_energy", default={}) or {}
        per_year = num(inner.get("pool_per_year"), 3)
        curve = str(inner.get("pool_curve") or curve)
    years = naegong * (naegong + 1) / 2 if curve == "누적_축기_년수" else naegong
    return int(round(years * per_year))


def tech_power(cfg, grade):
    """combat.yml technique_power — 무공 등급 → 위력. 없으면 None."""
    tp = dig(cfg, "combat.yml", "damage", "technique_power", default={}) or {}
    for key, val in tp.items():
        k = str(key)
        if k == str(grade) or k == f"{grade}급":
            return num(val, 0)
    return None


def weapon_power(cfg, weapon):
    wp = dig(cfg, "combat.yml", "damage", "weapon_power", default={}) or {}
    return num(wp.get(weapon), 0) if weapon in wp else None


def realm_vit_bonus(cfg, realm):
    """combat.yml durability.realm_bonus — 경지가 몸에 얹는 기혈. 미등록 경지는 0."""
    rb = dig(cfg, "combat.yml", "durability", "realm_bonus", default={}) or {}
    return num(rb.get(realm), 0)


def equip_vit_cap(cfg):
    """장비 유래 내구 상한 — combat.yml durability.equipment_cap (정본)."""
    return int(num(dig(cfg, "combat.yml", "durability", "equipment_cap", default=0), 0))


def durability(cfg, che, realm=None, beast=False, equip=0):
    """내구 = round(10 + 체력×2 + 경지 보정 + 장비 보정) — combat.yml durability 정본.

    · 기본항(10 + 체력×2)은 등록부가 뭐라 하든 이 도구의 고정 모델이다 (기본항이 흔들리면
      npcs 등록 내구·짐승이 전부 흔들린다 — 그때는 도구가 아니라 세계가 틀린 것이다).
    · 경지 보정은 config 에서 **읽는다** — 도구가 수치를 갖지 않는다 (등록제).
    · 짐승은 경지 보정을 받지 않는다: 개화(단전 개방)한 몸만 기혈이 두터워진다.
      짐승의 내구는 npcs/*.yml 등록값이 정본이고, 여기 합성치는 '경지 상당' 대체치다.
    · 장비 보정은 캡에서 잘린다.
    """
    base = 10 + che * 2
    bonus = 0 if beast else realm_vit_bonus(cfg, realm)
    eq = min(equip, equip_vit_cap(cfg))
    return int(round(base + bonus + eq))


def skill_cost(mech):
    """skill_mechanics 액션 데이터에서 시전 코스트(최대치)를 뽑는다."""
    if not isinstance(mech, dict):
        return 0.0
    costs = []
    if "cost" in mech:
        costs.append(num(mech.get("cost"), 0))
    for step in (mech.get("combo") or []):
        if isinstance(step, dict):
            costs.append(num(step.get("cost"), 0))
    return max(costs) if costs else 0.0


def cost_band_of(cfg, cost, category):
    """코스트·계열 → internal_energy.yml cost_bands 중 어느 밴드인가 (요구 격 추정)."""
    if cost <= 0:
        return "외공기"
    if category in ("경공", "신법"):
        return "경신"
    bands = dig(cfg, "internal_energy.yml", "cost_bands", default={}) or {}
    for band in ("발경", "경신", "검기", "강기"):
        b = bands.get(band) or {}
        c = b.get("cost")
        if isinstance(c, list) and len(c) >= 2:
            if num(c[0]) <= cost <= num(c[-1]):
                return band
        elif num(c, -1) == cost:
            return band
    if cost >= 4:
        return "강기"
    if cost >= 2:
        return "검기"
    return "발경"


def combat_regen(cfg, naegong=0.0):
    """조식(調息) — 전투 중 내력 회복. internal_energy.yml recovery.in_combat.조식 (없으면 0 = 회복 없음).

    ★ 2026-07 내력 수지 패스 — 상수 1 이 아니라 **내공에 비례**한다: max(floor(내공 × per_naegong), floor).
      고수는 숨만 쉬어도 단전이 돈다. 이 한 줄이 '어디서부터 넘치기 시작하는가'를 정한다.
    """
    spec = dig(cfg, "internal_energy.yml", "internal_energy", "recovery", "in_combat", "조식", default=None)
    if not isinstance(spec, dict):
        return 0, False
    per = num(spec.get("per_naegong"), 0)
    floor = int(num(spec.get("floor"), 0))
    return max(int(naegong * per), floor), bool(spec.get("only_if_unspent"))


def upkeep_exempt(cfg):
    """두름(병기에 실은 격)의 유지비는 '태운 것'이 아니다 — 조식을 막지 않는다."""
    return bool(dig(cfg, "internal_energy.yml", "internal_energy", "recovery",
                    "in_combat", "조식", "upkeep_exempt", default=False))


def guard_blocks_breath(cfg):
    """호신강기(두름_몸) 전개 중에는 단전이 안 돈다 — 무한 방어를 막는 못."""
    return bool(dig(cfg, "internal_energy.yml", "internal_energy", "recovery",
                    "in_combat", "조식", "blocked_by_guard", default=False))


def qi_casts(pool, cost, regen, rounds):
    """조식 규칙 아래 N합 전투에서 그 격을 몇 번 싣는가 — 탐욕(낼 수 있으면 태우고, 없으면 숨을 고른다).

    조건('격을 태운 합에는 돌지 않는다')이 규칙의 전부다: 조건이 없으면 코스트 1짜리 발경은 공짜가 된다.
    """
    if cost <= 0:
        return rounds
    energy, casts = pool, 0
    for _ in range(rounds):
        if energy >= cost:
            energy -= cost
            casts += 1
        else:
            energy = min(pool, energy + regen)   # 숨을 고른 합 — 내력을 안 썼으니 단전이 돈다
    return casts


# ── 전투 판정 v2 (combat.yml combat_v2 · B-177) — 눈의 기대 모델 ──────────────────
_V2_CACHE = {}


def v2_of(cfg):
    """combat_v2 등록부 판독 — enabled 면 dict, 아니면 None (엔진 SkillEngine.CombatV2.load 와 같은 문).

    ★ 이 판독이 이 눈의 존재 이유다: v2 가 라이브인 동안(2026-07-24 점화) v1 산술로 재면
      「위반 0」은 v2 를 안 잰 것이다 (B-177 남은 빚 — 눈이 세계와 어긋난 채 묵었다).
    """
    key = id(cfg)
    if key in _V2_CACHE:
        return _V2_CACHE[key]
    sec = dig(cfg, "combat.yml", "combat_v2", default={}) or {}
    out = None
    if sec.get("enabled"):
        de = sec.get("defense") or {}
        cr = sec.get("crit") or {}
        out = {
            "from_armor": bool(de.get("from_armor")),
            "per_body": num(de.get("per_body"), 0),
            "body_attr": str(de.get("body_attribute") or "체력"),
            "stance_soak": bool(de.get("stance_soak")),
            "sense_attr": str(cr.get("sense_attribute") or "감각"),
            "wisdom_attr": str(cr.get("wisdom_attribute") or "지혜"),
            "chance_base": num(cr.get("chance_base"), 0),
            "chance_per_sense": num(cr.get("chance_per_sense"), 0),
            "chance_per_wisdom": num(cr.get("chance_per_wisdom"), 0),
            "chance_cap": num(cr.get("chance_cap"), 0),
            "chance_by_weapon": {k: num(v, 0) for k, v in (cr.get("chance_by_weapon") or {}).items()},
            "damage_base": num(cr.get("damage_base"), 0),
            "damage_per_sense": num(cr.get("damage_per_sense"), 0),
            "damage_per_wisdom": num(cr.get("damage_per_wisdom"), 0),
            "damage_amp_by_weapon": {k: num(v, 0)
                                     for k, v in (cr.get("damage_amp_by_weapon") or {}).items()},
        }
    _V2_CACHE[key] = out
    return out


def attack_attr_name(cfg, weapon):
    """병기 계열 → 공격 능력치 축 (combat.yml attack.attacker_attribute — Growth.attackAttribute 와 같은 판독)."""
    reg = dig(cfg, "combat.yml", "attack", "attacker_attribute", default={}) or {}
    for attr, weapons in reg.items():
        if isinstance(weapons, list) and weapon in weapons:
            return attr
    return str(reg.get("default") or "근력")


def crit_ev_raw(v2, sense, wis, weapon):
    """v2 크리 — (확률, 배수). SkillEngine.critChance / critMultiplier 와 같은 줄.

    확률 = clamp(기본 + 감각·지혜 가산 + 무기별, 0, 상한) · 배수 = 기본 + 감각·지혜 + 무기 증강.
    장비 가산(equipment.yml crit 슬롯)은 아직 미등재 — 등재되면 이 눈도 그 항을 실어야 한다.
    """
    p = (v2["chance_base"] + v2["chance_per_sense"] * sense
         + v2["chance_per_wisdom"] * wis + v2["chance_by_weapon"].get(weapon, 0.0))
    p = min(v2["chance_cap"], max(0.0, p))
    mult = (v2["damage_base"] + v2["damage_per_sense"] * sense
            + v2["damage_per_wisdom"] * wis + v2["damage_amp_by_weapon"].get(weapon, 0.0))
    return p, mult


# ── 포위 규칙 (combat.yml attack) — 협공·슬롯·피포위 방어·강제 막기 ─────────────────
def gang_rules(cfg):
    """한 표적을 둘러싼 판의 규칙 전부를 config 에서 읽는다 (하드코딩 금지)."""
    g = dig(cfg, "combat.yml", "attack", "gang_up", default={}) or {}
    o = dig(cfg, "combat.yml", "attack", "outnumbered_defense", default={}) or {}
    fg = o.get("forced_guard") or {}
    # 【검산 규약】 강제 태세 중 **경감이 가장 낮은 것**으로 잰다 (audit_floor) — 방어자에게 인색해야 눈이 산다
    guard_name = str(fg.get("audit_floor") or fg.get("defense") or "흘리기")
    soak = num(dig(cfg, "combat.yml", "attack", "defender_choice", guard_name, "damage_reduction"), 0)
    return {
        "per": int(num(g.get("per_extra_attacker"), 1)),
        "cap": int(num(g.get("max"), 2)),
        "slots": int(num(g.get("engage_slots"), 3)),
        "def_per": int(num(o.get("per_extra_attacker"), 1)),
        "def_cap": int(num(o.get("max"), 2)),
        "guard_from": int(num(fg.get("trigger_extra_attackers"), 0)) if fg else 0,
        "guard_soak": int(soak) if fg else 0,
    }


def engaged(rules, attackers, slots_override=None):
    """동시 교전 인원 — 한 표적을 동시에 칠 수 있는 손의 수 (engage_slots). 나머지는 대기(포위)."""
    return min(attackers, slots_override or rules["slots"])


def net_mod(rules, n_engaged):
    """공격 측 순보정 = 협공 보정 − 피포위 방어 이점. 같은 눈금이므로 0 이다 (combat.yml 정본 주석)."""
    extra = max(0, n_engaged - 1)
    return min(extra * rules["per"], rules["cap"]) - min(extra * rules["def_per"], rules["def_cap"])


def guard_soak(rules, n_engaged):
    """포위된 자는 막는다 — 회피할 자리가 없다 (forced_guard). 피해 −3, 대가는 무기(weapon_break)."""
    if not rules["guard_soak"]:
        return 0
    return rules["guard_soak"] if max(0, n_engaged - 1) >= rules["guard_from"] else 0


def gate_realm(cfg, band, names):
    """internal_energy.yml realm_gates — 그 밴드가 처음 열리는 경지."""
    gates = dig(cfg, "internal_energy.yml", "realm_gates", default={}) or {}
    for nm in names:
        if band in (gates.get(nm) or []):
            return nm
    return None


# ══════════════════════════════════════════════════════════════════════════════
#  ① 전투 정합 린트
# ══════════════════════════════════════════════════════════════════════════════

def lint(cfg, rep):
    rep.say()
    rep.say("═" * 72)
    rep.say("  ① 전투 정합 린트 — 무공이 가리키는 것은 실재하는가")
    rep.say("═" * 72)
    lint_skill_refs(cfg, rep)
    lint_ladder_chain(cfg, rep)
    lint_faction_coverage(cfg, rep)
    lint_qi_gates(cfg, rep)
    lint_energy_budget(cfg, rep)
    lint_action_data(cfg, rep)
    lint_qi_ladder(cfg, rep)
    lint_weapon_break(cfg, rep)
    lint_npc_and_beasts(cfg, rep)
    lint_vitality(cfg, rep)
    lint_combat_v2(cfg, rep)


def lint_combat_v2(cfg, rep):
    """combat_v2 등록 정합 — 가리키는 능력치·무기가 실재하는가 (lint_config 5-d 와 같은 과의 눈).

    v2 는 능력치 이름 셋(체력·감각·지혜)과 무기 계열 표 둘(크리 확률·증강)을 가리킨다.
    오타 하나면 그 항이 조용히 0 이 된다 — 침묵하는 실패의 자리라 이름 대조가 필요하다.
    """
    rep.head("전투 판정 v2 (combat_v2) — 등록이 가리키는 것이 실재하는가")
    sec = dig(cfg, "combat.yml", "combat_v2", default={}) or {}
    if not sec:
        rep.say("     combat_v2 절이 없다 — v1(2d6)만 잰다")
        return
    enabled = bool(sec.get("enabled"))
    rep.say(f"     enabled: {enabled} — 시뮬 산술 = {'v2 (공방·크리)' if enabled else 'v1 (2d6)'}")

    attrs = set(dig(cfg, "judgment.yml", "attributes", default=[]) or [])
    weapons = set(dig(cfg, "combat.yml", "damage", "weapon_power", default={}) or {})
    de = sec.get("defense") or {}
    cr = sec.get("crit") or {}

    bad_attrs = [(k, v) for k, v in (("defense.body_attribute", de.get("body_attribute")),
                                     ("crit.sense_attribute", cr.get("sense_attribute")),
                                     ("crit.wisdom_attribute", cr.get("wisdom_attribute")))
                 if v is not None and str(v) not in attrs]
    if bad_attrs:
        for k, v in bad_attrs:
            rep.fail(f"combat_v2.{k} '{v}' — judgment.yml attributes 에 없다. "
                     f"그 항은 조용히 0 이 된다 (침묵하는 실패)")
    else:
        rep.ok("v2 의 능력치 셋(체력·감각·지혜 자리)이 전부 judgment.yml attributes 에 실재")

    bad_weapons = []
    for table in ("chance_by_weapon", "damage_amp_by_weapon"):
        for w in (cr.get(table) or {}):
            if w not in weapons:
                bad_weapons.append((table, w))
    if bad_weapons:
        for table, w in bad_weapons:
            rep.fail(f"combat_v2.crit.{table} 의 '{w}' — damage.weapon_power 13계열에 없다 "
                     f"(유령 무기 — 아무 손에도 안 실린다)")
    else:
        rep.ok("크리 무기 표 둘의 계열이 전부 weapon_power 등록부에 실재")

    if enabled:
        pb = num(de.get("per_body"), 0)
        rep.verdict(pb > 0,
                    f"방어력에 몸이 산다 — per_body {pb:g} (체력 판정치가 방어력을 움직인다: 성장 체감의 방어 절반)"
                    if pb > 0 else
                    "per_body 0 — 체력을 키워도 방어력이 안 움직인다 (성장의 방어 절반이 죽는다)")


def lint_vitality(cfg, rep):
    """생명 축 — 내구 등록부의 정합 (combat.yml durability ↔ equipment.yml vitality).

    이 린트가 지키는 설계 규약은 둘이다:
      ㄱ. 경지 보정은 **개화(일류)부터** — 삼류·이류가 맷집으로 이기지 않는다
      ㄴ. 외갑(무복·피갑·철갑)은 내구를 **올리지 않는다** — 판금이 몸을 키우는 세계가 아니다
    둘 다 주석이 아니라 기계가 지킨다. 주석은 지워지고 린트는 운다.
    """
    rep.head("생명 축 — 내구 등록부 (경지 보정 · 장비 가산)")

    names = realm_names(cfg)
    rb = dig(cfg, "combat.yml", "durability", "realm_bonus", default={}) or {}
    if not rb:
        rep.fail("combat.yml durability.realm_bonus 가 없다 — 경지가 몸을 바꾸지 않는다")
        return

    # ① 모든 경지가 등록되어 있는가 (빠뜨린 것과 0 은 다르다)
    missing = [nm for nm in names if nm not in rb]
    rep.verdict(not missing,
                f"경지 보정 등록 완결 — {len(names)}단 전부"
                if not missing else f"realm_bonus 미등록 경지: {missing} — 0 과 '빠뜨림'은 다르다")

    # ② 단조 증가 — 경지가 오르는데 몸이 얇아질 수는 없다
    seq = [(nm, num(rb.get(nm), 0)) for nm in names if nm in rb]
    drops = [(a[0], b[0]) for a, b in zip(seq, seq[1:]) if b[1] < a[1]]
    rep.verdict(not drops,
                "경지 보정 단조 증가 — 오를수록 두터워진다"
                if not drops else f"경지 보정이 역행한다: {drops}")

    # ③ 개화 규약 — 삼류·이류는 0 이어야 한다 (cultivation.yml: 일류 = '개화한 몸')
    pre = [nm for nm in ("범인", "삼류", "이류") if num(rb.get(nm), 0) != 0]
    rep.verdict(not pre,
                "개화 전(범인·삼류·이류) 보정 0 — 단전이 열린 몸만 기혈이 두터워진다"
                if not pre else f"개화 전 경지에 내구 보정이 붙었다: {pre} — "
                                "삼류가 맷집으로 이기면 '개화'가 무슨 뜻인가")

    # ④ 장비 캡 — 두 파일이 같은 수를 말하는가 (combat.yml 이 정본)
    cap_c = equip_vit_cap(cfg)
    cap_e = num(dig(cfg, "equipment.yml", "vitality", "cap", default=None), None)
    rep.verdict(cap_e is not None and int(cap_e) == cap_c,
                f"장비 내구 캡 일치 — combat.yml({cap_c}) = equipment.yml({cap_e})"
                if cap_e is not None and int(cap_e) == cap_c
                else f"장비 내구 캡 불일치 — combat.yml({cap_c}) vs equipment.yml({cap_e})")

    # ⑤ 등록된 가산원의 합이 캡을 넘지 않는가
    srcs = dig(cfg, "equipment.yml", "vitality", "sources", default={}) or {}
    total = sum(num((v or {}).get("grants"), 0) for v in srcs.values())
    rep.verdict(total <= cap_c,
                f"가산원 총합 {total} ≤ 캡 {cap_c} — 다 껴입어도 캡을 넘지 못한다"
                if total <= cap_c else f"가산원 총합 {total} > 캡 {cap_c} — 캡이 거짓말이 된다")

    # ⑥ 【무협의 결】 외갑은 내구를 올리지 않는다
    armor = dig(cfg, "equipment.yml", "armor", default={}) or {}
    outer = [nm for nm in ("무복", "피갑", "철갑")
             if num((armor.get(nm) or {}).get("vitality"), 0) != 0]
    rep.verdict(not outer,
                "외갑(무복·피갑·철갑) 내구 가산 0 — 철판은 몸을 키우지 않는다. 갑옷의 값은 경감에 있다"
                if not outer else f"외갑이 내구를 올린다: {outer} — 그러면 '갑옷을 껴입는 무림인'이 "
                                  "최적해가 되고 경공(dodge_penalty)을 파는 축이 죽는다")

    # ⑦ 가산원이 실재하는가 — 등록부가 유령을 가리키지 않는가
    trinkets = dig(cfg, "equipment.yml", "trinkets", default={}) or {}
    ghosts = [nm for nm in srcs if nm not in armor and nm not in trinkets]
    rep.verdict(not ghosts,
                "가산원 전부 실재 — armor/trinkets 에 등록된 것만 내구를 준다"
                if not ghosts else f"등록되지 않은 가산원: {ghosts} — 존재하지 않는 장비가 몸을 키운다")

    # ⑧ 내갑의 두 자리가 같은 수를 말하는가 (armor.내갑.vitality ↔ vitality.sources.내갑.grants)
    a_in = num((armor.get("내갑") or {}).get("vitality"), None)
    s_in = num((srcs.get("내갑") or {}).get("grants"), None)
    if a_in is not None and s_in is not None:
        rep.verdict(int(a_in) == int(s_in),
                    f"내갑 내구 가산 동기 — armor({int(a_in)}) = vitality.sources({int(s_in)})"
                    if int(a_in) == int(s_in)
                    else f"내갑 값이 두 자리에서 다르다 — armor({a_in}) vs sources({s_in})")


def lint_skill_refs(cfg, rep):
    rep.head("무공 참조 — 경지·등급·능력치·선행·무기")
    names, _axis = realm_axis(cfg)
    arts = dig(cfg, "skills.yml", "martial_arts", default={}) or {}
    attrs = set(dig(cfg, "judgment.yml", "attributes", default=[]) or [])
    ladder = dig(cfg, "skill_lifecycle.yml", "mastery_ladder", default={}) or {}
    top = 0
    for v in ladder.values():
        if isinstance(v, dict):
            lv = v.get("level")
            if isinstance(lv, list):
                top = max(top, int(num(lv[-1])))
            elif isinstance(lv, (int, float)):
                top = max(top, int(num(lv)))
    skill_scale_max = int(num(dig(cfg, "judgment.yml", "scales", "skill", "max"), 10))

    rep.say(f"     무공 {len(arts)}종 · 경지 축 {len(names)}단 · 숙련 사다리 상한 {top} (판정 스케일 상한 {skill_scale_max})")
    rep.say("")

    bad_realm, bad_stat, bad_grade = [], [], []
    tech_missing = set()
    weapon_missing = {}
    mastery_no_source = []

    grades_ok = set(names) | {"입문"}
    for sid, s in sorted(arts.items()):
        if not isinstance(s, dict):
            continue
        rr = s.get("required_realm")
        if rr not in names:
            bad_realm.append(f"{sid}({rr})")
        g = s.get("grade")
        if g not in grades_ok:
            bad_grade.append(f"{sid}({g})")
        if tech_power(cfg, g) is None:
            tech_missing.add(str(g))
        for k in (s.get("required_stats") or {}):
            if k not in attrs:
                bad_stat.append(f"{sid}.{k}")
        cat = s.get("category")
        if cat not in NO_DAMAGE_CATEGORIES:
            w = CATEGORY_WEAPON.get(cat, "?")
            if w is not None and weapon_power(cfg, w) is None:
                weapon_missing.setdefault(str(cat), []).append(sid)
        m = num(s.get("required_mastery"), 0)
        if m > 0 and not any(k.startswith("requires_skill") or k == "prerequisite" for k in s):
            mastery_no_source.append(f"{sid}(숙련 {m:g})")

    rep.verdict(not bad_realm, f"요구 경지 — 전 무공이 cultivation 9경지에 실재"
                if not bad_realm else f"요구 경지 미등록: {', '.join(bad_realm)}")
    rep.verdict(not bad_grade, "무공 등급 — 전 무공이 경지 사다리(+입문) 위의 값"
                if not bad_grade else f"등급 미등록: {', '.join(bad_grade)}")
    rep.verdict(not bad_stat, "요구 능력치 — 전부 judgment.yml attributes 소속"
                if not bad_stat else f"미등록 능력치 참조: {', '.join(bad_stat)}")

    if tech_missing:
        holes = sorted(tech_missing)
        rep.fail(f"combat.yml technique_power 에 등급 {holes} 의 무공 위력이 없다 — "
                 f"피해 공식('무기 위력 + 무공 위력 + floor(마진/2)')이 이 등급 무공에서 계산 불가. "
                 f"정의된 것은 {sorted((dig(cfg, 'combat.yml', 'damage', 'technique_power', default={}) or {}).keys())} 뿐 — "
                 f"하필 개화 경지(일류)의 무공 위력이 구멍이다")
    else:
        rep.ok("무공 등급 → 위력 대응 — 전 등급 정의됨")

    if weapon_missing:
        for cat, ids in sorted(weapon_missing.items()):
            rep.fail(f"'{cat}' 계열({', '.join(sorted(ids))})의 무기 위력이 combat.yml weapon_power 에 없다 "
                     f"— 위력표는 {sorted((dig(cfg, 'combat.yml', 'damage', 'weapon_power', default={}) or {}).keys())} 뿐. "
                     f"활·암기의 피해는 계산할 수 없다 (사거리도 skill_mechanics max_range_default 24 뿐 — 궁술 고유값 없음)")
    else:
        rep.ok("무공 계열 → 무기 위력 대응 — 전 계열 정의됨")

    if mastery_no_source:
        rep.fail(f"required_mastery 가 '무엇의 숙련'인지 기계 판독 필드가 없다 ({len(mastery_no_source)}종: "
                 f"{', '.join(mastery_no_source[:6])}{' …' if len(mastery_no_source) > 6 else ''}) — "
                 f"선행 무공은 주석에만 있다(예: '# 육합검 숙련 4'). 엔진은 무엇을 검사해야 하는지 모른다")
    else:
        rep.ok("선행 무공 — 요구 숙련의 대상 무공이 명시됨")

    if top and skill_scale_max != top:
        rep.warn(f"숙련 스케일 불일치 — skills.yml schema 는 '0~10', mastery_ladder 는 극성 {top} 이 상한, "
                 f"judgment.yml scales.skill.max 는 {skill_scale_max}. 8~10 구간의 의미가 정의되지 않았다")


TIER_ORDER = {"기초": 0, "하급": 1, "중급": 2, "상급": 3}


def lint_ladder_chain(cfg, rep):
    """사다리 연결 — requires_skill 을 거슬러 올라가면 **첫 칸**에 닿는가 (B-188 닫는 조건 ③).

    lint_skill_refs 는 '선행이 명시됐는가'를 본다. 그것으로는 부족하다:
      · 선행 id 가 카탈로그에 없어도 (오타·삭제) 명시는 명시다
      · 상급 무공이 선행 없이 홀로 서 있으면 **사다리가 공중에서 시작한다** (개방 봉법이 그랬다)
      · 선행이 자기보다 높은 tier 면 사다리가 뒤집힌다
      · 고리(A→B→A)가 있으면 아무도 첫 칸에 닿지 못한다

    ★이 눈이 재는 것은 수치가 아니라 **길**이다. 무공은 배우는 순서가 곧 설계다.
    """
    rep.head("사다리 연결 — 선행을 거슬러 첫 칸에 닿는가 (chain-walk)")
    arts = dig(cfg, "skills.yml", "martial_arts", default={}) or {}
    # ★정본 키는 legacy_arts 다 (ultimate_arts 가 아니다). 처음 이 눈을 세울 때 키를 헛짚어
    #   "오의 0종 — 전부 실재한다"고 **거짓 합격**을 냈다. 그래서 빈 등록부는 합격이 아니라 실패로 센다
    ults = dig(cfg, "ultimate_arts.yml", "legacy_arts", default={}) or {}

    def prereq(sid):
        s = arts.get(sid)
        if not isinstance(s, dict):
            return None
        rs = s.get("requires_skill")
        if isinstance(rs, dict):
            return rs.get("id")
        return rs if isinstance(rs, str) else None

    # ★심법이 문턱인 무공은 공중에서 시작하는 것이 아니다 (2026-07-26 당가 회차에서 눈이 고쳐졌다).
    #   칠보독장: "독은 손이 아니라 몸으로 배운다 — 심법(칠살음독경)으로 몸을 독에 동화시킨 자만
    #   제 손에 독을 얹는다. 그래서 이 계보에는 하급이 없다." 그것은 구멍이 아니라 **설계**다.
    #   눈이 requires_simbeop 를 못 읽어 「선행 없음」으로 셌다 — 없는 구멍을 지어내고 있었다.
    simbeops = dig(cfg, "simbeop.yml", "simbeop", default={}) or {}
    dangling, cycles, midair, inverted, cross_cat = [], [], [], [], []
    sim_dangling, sim_rooted = [], []
    roots = {}

    for sid, s in sorted(arts.items()):
        if not isinstance(s, dict):
            continue
        pid = prereq(sid)
        tier = s.get("tier")

        # ①-0 심법 문턱 — 선행이 무공이 아니라 심법인 계보 (그 심법이 실재해야 한다)
        rsb = s.get("requires_simbeop")
        sim_id = rsb.get("id") if isinstance(rsb, dict) else (rsb if isinstance(rsb, str) else None)
        if sim_id is not None:
            sim_rooted.append(sid)
            if sim_id not in simbeops:
                sim_dangling.append(f"{sid} → {sim_id}(심법 없음)")

        # ① 공중에서 시작 — 중급·상급인데 선행이 **무공도 심법도** 없다
        if pid is None and sim_id is None and tier in ("중급", "상급"):
            midair.append(f"{sid}({tier})")

        # ② 선행 실재 · 고리 · 뿌리
        seen, cur, chain = set(), sid, []
        while True:
            nxt = prereq(cur)
            if nxt is None:
                roots[sid] = cur
                break
            if nxt not in arts:
                dangling.append(f"{sid} → {nxt}(없음)")
                break
            if nxt in seen or nxt == sid:
                cycles.append(f"{sid}: {' → '.join(chain + [nxt])}")
                break
            seen.add(nxt)
            chain.append(nxt)
            cur = nxt
            if len(chain) > 20:               # 안전핀 — 어떤 사다리도 20칸을 넘지 않는다
                cycles.append(f"{sid}: 20칸 초과 — 고리 의심")
                break

        # ③ 사다리 역행 — 선행의 tier 가 자기 이상
        if pid and pid in arts and isinstance(arts[pid], dict):
            p_tier = arts[pid].get("tier")
            if tier in TIER_ORDER and p_tier in TIER_ORDER and TIER_ORDER[p_tier] >= TIER_ORDER[tier]:
                inverted.append(f"{sid}({tier}) ← {pid}({p_tier})")
            # ④ 계열 넘나듦 — 위반은 아니다 (무당은 권으로 몸을 만들고 검을 잡는다). 다만 보이게 둔다
            if s.get("category") and arts[pid].get("category") and s["category"] != arts[pid]["category"]:
                cross_cat.append(f"{sid}({s['category']}) ← {pid}({arts[pid]['category']})")

    rep.say(f"     무공 {len(arts)}종 · 선행 사슬을 가진 것 {sum(1 for k in arts if prereq(k))}종 · "
            f"첫 칸(뿌리) {len(set(roots.values()))}종")
    rep.say("")

    rep.verdict(not dangling, "선행 실재 — 가리키는 무공이 전부 카탈로그에 있다"
                if not dangling else f"★선행이 허공을 가리킨다: {', '.join(dangling)}")
    if sim_rooted:
        rep.verdict(not sim_dangling,
                    f"심법 문턱 {len(sim_rooted)}종 — 가리키는 심법이 simbeop.yml 에 실재 "
                    f"({', '.join(sim_rooted)})"
                    if not sim_dangling else f"★심법 문턱이 허공을 가리킨다: {', '.join(sim_dangling)}")
    rep.verdict(not cycles, "고리 없음 — 모든 사슬이 첫 칸에서 끝난다"
                if not cycles else f"★선행 고리 — 아무도 첫 칸에 닿지 못한다: {'; '.join(cycles)}")
    rep.verdict(not inverted, "사다리 방향 — 선행이 언제나 자기보다 아래 칸"
                if not inverted else f"★사다리 역행(선행이 같거나 위 칸): {', '.join(inverted)}")
    # ★심각도 주의: 공중시작은 **위반이 아니라 경고**다. 이 도구의 두 칸은 이렇게 갈린다 —
    #   위반 = 자기모순이거나 굴러가지 않는다 (허공 참조·고리·역행) ·
    #   경고 = 굴러가지만 의도한 감각이 아닐 수 있다.
    #   선행 없는 중급 무공은 **굴러간다** (바로 배워진다). 다만 사다리의 첫 칸이 없을 뿐이다 —
    #   그것은 설계 구멍이고, B-188 닫는 조건 ①이 「채우거나 의도된 모양으로 명문화」한다.
    if midair:
        rep.warn(f"사다리가 공중에서 시작한다 (선행 없는 중급·상급 {len(midair)}종): {', '.join(midair)} "
                 f"— 첫 칸을 채우거나 '의도된 모양'으로 명문화해야 한다 (B-188 닫는 조건 ①)")
    else:
        rep.ok("공중에서 시작하는 사다리 없음 — 중급·상급은 전부 딛고 올라선다")

    # ⑤ 오의 선행 — 오의는 별개 사다리다. 그 첫 발판이 실재하는가
    ult_bad = []
    for uid, u in sorted(ults.items()):
        if not isinstance(u, dict):
            continue
        rs = u.get("requires_skill")
        rid = rs.get("id") if isinstance(rs, dict) else (rs if isinstance(rs, str) else None)
        if rid is None:
            ult_bad.append(f"{uid}(선행 미기재)")
        elif rid not in arts:
            ult_bad.append(f"{uid} → {rid}(없음)")
    if not ults:
        rep.fail("오의 등록부가 비었다 — ultimate_arts.yml legacy_arts 를 못 읽었다. "
                 "★빈 등록부에 '전부 통과'를 내주면 그것이 곧 눈의 거짓말이다")
    else:
        rep.verdict(not ult_bad, f"오의 {len(ults)}종 — 전부 실재하는 무공을 발판으로 딛는다"
                    if not ult_bad else f"오의 선행 문제: {', '.join(ult_bad)}")

    # ⑥ tier 미기재 — 이 눈이 셀 수 없는 무공 (B-188 닫는 조건 ②)
    no_tier = sorted(k for k, v in arts.items() if isinstance(v, dict) and not v.get("tier"))
    if no_tier:
        rep.warn(f"tier 미기재 {len(no_tier)}종 — 사다리 눈이 **이들의 층을 못 센다** "
                 f"(역행·공중시작 판정에서 조용히 빠진다): {', '.join(no_tier[:8])}"
                 f"{' …' if len(no_tier) > 8 else ''} · B-188 닫는 조건 ②")
    else:
        rep.ok("tier 전 무공 기재 — 사다리 눈이 전수를 센다")

    if cross_cat:
        rep.say(f"     계열을 넘는 선행 {len(cross_cat)}건 (위반 아님 — 의도일 수 있다): "
                f"{', '.join(cross_cat[:6])}{' …' if len(cross_cat) > 6 else ''}")


# ★컨테이너 id — 무공을 가질 주체가 아니다 (연합·계열·계보). factions.yml 이 그렇게 적어 뒀다:
#   불가는 "문파가 아니라 계보다" · 구파일방/오대세가는 members 를 가진 연합 · 나머지는 분류축.
#   이들을 세력으로 세면 「백지 세력」이 열 곳쯤 늘어난다 — 있지도 않은 구멍이다.
FACTION_CONTAINERS = {
    "orthodox", "unorthodox", "saeoe", "civilian", "authority", "forbidden",
    "gupailbang", "odaesega", "orthodox_heroes", "bulga",
}


def lint_faction_coverage(cfg, rep):
    """세력 백지 지도 — **들어갈 수는 있는데 배울 것이 없는 세력**을 잰다 (B-188 닫는 조건 ④).

    ★이 눈이 재는 것은 무공의 값이 아니라 **약속**이다: 입문 경로(faction_entry_routes)가
    있다는 것은 세계가 "여기 들어올 수 있다"고 말한 것이다. 들어갔는데 배울 것이 없으면
    그 약속이 거짓말이 된다. 그래서 판정의 축은 「무공 0」이 아니라 「문이 열렸는데 무공 0」이다.

    장부(B-188)의 백지 지도는 손으로 센 것이라 낡는다 — 이 눈이 그 표를 대신 센다.
    """
    rep.head("세력 백지 지도 — 문이 열렸는데 배울 것이 있는가")
    arts = dig(cfg, "skills.yml", "martial_arts", default={}) or {}
    sims = dig(cfg, "simbeop.yml", "simbeop", default={}) or {}
    groups = dig(cfg, "factions.yml", "faction_groups", default={}) or {}
    # ★`routes` 절로 파고든다 — 파일 전체를 넘기면 최상단 키가 meta·routes 라서
    #   루트가 하나도 안 잡힌다 (실제로 「입문 경로 0곳」이 나왔다)
    routes = dig(cfg, "faction_entry_routes.yml", "routes", default={}) or {}

    # ① factions.yml 에서 1급 세력 id 를 걷는다 (name 을 가진 dict — 명단(sects/clans)은 로스터라 뺀다)
    known = {}

    def walk(node):
        if isinstance(node, dict):
            for k, v in node.items():
                if isinstance(v, dict) and "name" in v and k not in ("sects", "clans", "members"):
                    known[k] = v.get("name")
                    walk(v)
                elif isinstance(v, (dict, list)):
                    walk(v)
        elif isinstance(node, list):
            for x in node:
                walk(x)
    walk(groups)

    if not known:
        rep.fail("factions.yml faction_groups 에서 세력 id 를 하나도 못 걷었다 — "
                 "★빈 등록부에 '백지 없음'을 내주면 그것이 곧 눈의 거짓말이다")
        return

    # ② 입문 경로가 가리키는 세력 (등록부를 문자열이 아니라 **키·값 전수**로 훑는다)
    #   ★등록부는 세력을 id 로도, **한글 이름으로도** 가리킨다
    #   (magyo_encroachment 의 `faction: 마교` — id 만 보던 눈은 마교를 놓쳤다. 2026-07-26 교정).
    by_name = {v: k for k, v in known.items() if isinstance(v, str)}
    entry = set()

    def resolve(x):
        if not isinstance(x, str):
            return None                      # dict/list 는 이름이 아니다 (unhashable — in known 이 터진다)
        return x if x in known else by_name.get(x)

    # ★★입문이 **닫힌** 루트는 문이 아니다 (2026-07-26 사용자 확정: 새외 입문 폐쇄).
    #   `player_entry: false` 가 달린 절 안에서 가리키는 세력은 entry 로 세지 않고 npc_only 로 센다.
    #   이 구분이 없으면 눈이 **유보를 백지로 착각해** 있지도 않은 약속을 어겼다고 보고한다.
    npc_only = set()

    #   ★폐쇄는 **그 절의 제 세력에만** 적용한다 — 하위로 전파하면 안 된다.
    #     첫 판이 그렇게 했다가, 새외 절의 주석이 언급한 소림·무당·모용까지 「NPC 전용」으로 셌다
    #     (「★화산 5일 · 당가 26일」 같은 대조 문장과 trigger 의 남의 세력 참조가 전부 걸렸다).
    #     한 줄: **닫힌 문이 닫는 것은 그 문뿐이다.**
    #   ★판정의 정밀화 (2026-07-26, 세 판을 거쳐 여기 왔다):
    #     세는 것은 **루트가 제 `faction:` 으로 지목한 세력**뿐이다. 등록부 안의 느슨한 언급은
    #     문이 아니다 — trigger 의 남의 세력 참조나 「★화산 5일 · 당가 26일」 같은 대조 문장까지
    #     세면 상단·소림처럼 **제 루트가 없는 세력이 입문 가능으로 부풀어 오른다** (실제로 그랬다:
    #     루트는 16개인데 눈은 22곳을 셌다). 그리고 반대로 폐쇄를 npc_only 에까지 전파하면
    #     주석이 언급한 소림·무당·모용이 유보로 잡힌다 (그 판도 겪었다).
    #     한 줄: **문의 이름은 그 문에만 적혀 있다.**
    for _rid, _r in routes.items():
        if not isinstance(_r, dict):
            continue
        own = resolve(_r.get("faction"))
        if not own:
            continue                             # saeoe_common 은 kind:공통_규칙 — 제 세력이 없다
        (npc_only if _r.get("player_entry") is False else entry).add(own)
    # saeoe_common 은 kind:공통_규칙 이라 제 faction 이 없다 — applies_to 가 가리키는 루트들이 제 몫을 한다

    n_art, n_sim = {}, {}
    for v in arts.values():
        if isinstance(v, dict):
            n_art[v.get("faction")] = n_art.get(v.get("faction"), 0) + 1
    for v in sims.values():
        if isinstance(v, dict):
            n_sim[v.get("faction")] = n_sim.get(v.get("faction"), 0) + 1

    # ★「무공을 갖지 않는 것이 의도」인 세력 — 기구(무림맹)처럼 제 무학이 없는 주체.
    #   컨테이너와 다르다: 컨테이너는 **주체가 아니어서** 없고, 기구는 **주체이지만** 없다.
    #   등록부가 no_arts_by_design 으로 말해 준다 (코드가 이름을 외우지 않는다).
    by_design = set()

    def scan_design(node):
        if isinstance(node, dict):
            for k, v in node.items():
                if isinstance(v, dict):
                    if v.get("no_arts_by_design") is True and k in known:
                        by_design.add(k)
                    scan_design(v)
                elif isinstance(v, list):
                    scan_design(v)
        elif isinstance(node, list):
            for x in node:
                scan_design(x)
    scan_design(groups)

    sects = {k: v for k, v in known.items()
             if k not in FACTION_CONTAINERS and k not in by_design}
    rep.say(f"     factions.yml 1급 id {len(known)}종 "
            f"(컨테이너 {len(FACTION_CONTAINERS)} · 무공없음이_의도 {len(by_design)} 제외 → "
            f"세력 {len(sects)}) · 입문 경로가 가리키는 세력 {len(entry & set(sects))}")
    if by_design:
        rep.ok("무공 0 이 **의도**인 세력 — "
               + ", ".join(f"{known[f]}({f})" for f in sorted(by_design))
               + " : 기구는 제 무학을 갖지 않는다 (등록부 no_arts_by_design). "
                 "★컨테이너와 다르다 — 컨테이너는 주체가 아니어서 없고, 이쪽은 주체인데 없다")
    rep.say("")

    npc_only -= entry            # 한 세력이 양쪽에 걸리면 **열린 문이 이긴다** (약속이 먼저다)
    open_blank = sorted(f for f in sects if f in entry and n_art.get(f, 0) == 0)
    open_thin = sorted(f for f in sects if f in entry and 0 < n_art.get(f, 0) <= 2)
    closed_blank = sorted(f for f in sects if f not in entry and n_art.get(f, 0) == 0
                          and n_sim.get(f, 0) == 0)

    def label(f):
        n, m = n_art.get(f, 0), n_sim.get(f, 0)
        return f"{known[f]}({f} 무공{n}·심법{m})"

    if open_blank:
        rep.warn(f"★들어갈 수는 있는데 배울 것이 없다 — {len(open_blank)}곳: "
                 f"{', '.join(label(f) for f in open_blank)} "
                 f"— 입문 경로가 있다는 것은 세계가 '들어올 수 있다'고 한 약속이다 "
                 f"(B-188 닫는 조건 ④ · 채움 순서 ⑤~⑨)")
    else:
        rep.ok("입문 경로가 있는 세력은 전부 배울 무공이 있다 — 문과 방이 짝을 이룬다")

    if open_thin:
        rep.warn(f"입문 가능하나 무공 1~2종 — {len(open_thin)}곳: "
                 f"{', '.join(label(f) for f in open_thin)} (사다리라 부를 수 없다 · 채움 순서 ⑩)")
    else:
        rep.ok("입문 가능한 세력 전부 무공 3종 이상")

    if closed_blank:
        rep.say(f"     입문 경로 없이 백지 {len(closed_blank)}곳 (위반 아님 — 아직 문이 없다): "
                f"{', '.join(known[f] for f in closed_blank)}")

    # ★NPC 전용 — 유보를 백지로 착각하지 않기 위한 별도 칸 (경고가 아니다: 약속을 안 했으니 어기지도 않았다)
    if npc_only:
        deferred = sorted(f for f in npc_only if f in sects)
        if deferred:
            rep.ok(f"NPC 전용(입문 폐쇄) {len(deferred)}곳 — 무공 0 은 구멍이 아니라 **유보**다: "
                   + ", ".join(f"{known[f]}(무공{n_art.get(f, 0)})" for f in deferred)
                   + " · 거취는 npc_combat 축 (faction_entry_routes player_entry:false)")

    # ③ 컨테이너에 무공이 붙어 있으면 그것은 오분류다 (연합은 무공을 갖지 않는다)
    #   ★위반이 아니라 경고다 — 컨테이너에 붙은 무공도 **굴러간다** (사파 계열 반응이 그대로 걸린다).
    #     다만 그것이 「무소속」의 뜻인지 오분류인지는 등록부가 말해 주지 않는다. 사람이 정할 일이다.
    misfiled = sorted(f for f in FACTION_CONTAINERS if n_art.get(f, 0) or n_sim.get(f, 0))
    if misfiled:
        who = {f: sorted(k for k, v in arts.items()
                         if isinstance(v, dict) and v.get("faction") == f) for f in misfiled}
        rep.warn("컨테이너(연합·계열·계보)에 무공이 붙어 있다 — "
                 + " · ".join(f"{known.get(f, f)}: {', '.join(who[f])}" for f in misfiled)
                 + " — 「어느 문파도 아닌 무공」이라는 뜻이라면 그 자리를 **1급 id 로** 세워야 하고"
                   "(orthodox_heroes 선례: 「문파에 소속되지 않은 정파 성향 무인들」),"
                   " 아니라면 소속을 고쳐야 한다 (문답)")
    else:
        rep.ok(f"컨테이너({len(FACTION_CONTAINERS)}종)에 붙은 무공·심법 없음 — 연합·계보는 손을 갖지 않는다")

    # ④ 무공이 가리키는 faction 이 factions.yml 에 실재하는가 (오타의 자리)
    ghost = sorted({v.get("faction") for v in arts.values()
                    if isinstance(v, dict) and v.get("faction") and v.get("faction") not in known})
    rep.verdict(not ghost, "무공의 소속 세력 — 전부 factions.yml 에 실재"
                if not ghost else f"★factions.yml 에 없는 세력을 가리키는 무공: {', '.join(ghost)}")


def lint_qi_gates(cfg, rep):
    rep.head("격 게이트 정합 — 열어놓고 못 쓰는 문이 무공 단위로 또 있는가")
    names, axis = realm_axis(cfg)
    arts = dig(cfg, "skills.yml", "martial_arts", default={}) or {}
    mechs = dig(cfg, "skill_mechanics.yml", "skills", default={}) or {}
    gates = dig(cfg, "internal_energy.yml", "realm_gates", default={}) or {}

    rep.say("     무공의 '요구 격'은 skills.yml 에 필드가 없다 → 액션 코스트로 역추정한다")
    rep.say("     (cost 0 = 외공기 / cost>0 = 내력 소모 = 개화 이후에만 존재)")
    rep.say("")
    rep.say("       무공                요구경지  코스트  추정 격   격 개방 경지   판정")

    dead_gates, band_clash = [], []
    for sid, mech in sorted(mechs.items()):
        cost = skill_cost(mech)
        art = arts.get(sid)
        cat = (art or {}).get("category", "?")
        rr = (art or {}).get("required_realm")
        if art is None:
            rr = None
        band = cost_band_of(cfg, cost, cat)
        gr = gate_realm(cfg, band, names) if cost > 0 else names[0]
        pool = pool_of(axis[rr]["naegong"], cfg) if rr in axis else 0
        if rr is None:
            verdict = "무공 카탈로그 밖"
        elif cost <= 0:
            verdict = "✅"
        elif pool <= 0:
            verdict = f"❌ {rr} 내력 0"
            dead_gates.append((sid, rr, cost, band, gr))
        elif gr and names.index(rr) < names.index(gr):
            verdict = f"⚠️ 격 밴드 충돌"
            band_clash.append((sid, rr, cost, band, gr, pool))
        else:
            verdict = "✅"
        rep.say(f"       {sid:<18} {str(rr or '-'):<8} {cost:>5.0f}  {band:<7} {str(gr or '-'):<10}  {verdict}")

    rep.say("")
    for sid, rr, cost, band, gr in dead_gates:
        art = arts.get(sid) or {}
        rep.fail(f"{sid}({art.get('name', sid)}) — 요구 경지 '{rr}'인데 시전 코스트 {cost:g}. "
                 f"realm_gates 상 '{rr}'의 기 운용은 {gates.get(rr)} 뿐이고 내력 풀은 "
                 f"round(내공 {axis[rr]['naegong']:g} × 3) = 0 — 개화('{gr}') 전까지 이 무공은 배워도 "
                 f"온전히 시전되지 않는다. 다운캐스트('맨 기술')로만 나간다. "
                 f"'열어놓고 못 쓰는 문'이 무공 단위로 반복됐다")
    for sid, rr, cost, band, gr, pool in band_clash:
        art = arts.get(sid) or {}
        rep.warn(f"{sid}({art.get('name', sid)}) — 요구 경지 '{rr}'(내력 풀 {pool})인데 코스트 {cost:g} 는 "
                 f"cost_bands 상 '{band}' 밴드(개방 경지 '{gr}')에 걸린다. 지불은 되지만 어느 격으로 나가는지 "
                 f"판별 불가 — skills.yml 에 '요구 격' 필드가 없어서다 (코스트만으로 격을 역추정해야 한다)")
    if not dead_gates and not band_clash:
        rep.ok("격 게이트 — 전 무공의 코스트가 요구 경지에서 실제로 열려 있다")

    orphan = [sid for sid in mechs if sid not in arts and not (mechs[sid] or {}).get("npc_only")]
    if orphan:
        rep.warn(f"액션 데이터는 있는데 무공 카탈로그에 없는 id: {', '.join(sorted(orphan))} "
                 f"(jeongsim_geomgyeol 은 simbeop.yml 로 이관됨 — 의도된 것이나, 심법이 액션 코스트 1과 "
                 f"패링 태세를 갖는다는 사실은 어느 게이트도 검사하지 않는다)")


def lint_energy_budget(cfg, rep):
    rep.head("내력 수지 — 그 경지의 풀로 이 무공을 몇 번 쓰는가")
    names, axis = realm_axis(cfg)
    arts = dig(cfg, "skills.yml", "martial_arts", default={}) or {}
    mechs = dig(cfg, "skill_mechanics.yml", "skills", default={}) or {}

    rep.say("     내력 풀 = round(내공 × 3) · 경지별 내공 = cultivation.yml 승급 요건")
    rep.say("     (일류는 '개화'만 요건이라 내공 수치가 없다 → 개화 직후 0.33 과 축기 1년 1.0 을 둘 다 잰다)")
    rep.say("")
    rep.say("       무공                요구경지  내공   내력풀  코스트   시전 가능 횟수")

    zero_use = []
    for sid, mech in sorted(mechs.items()):
        cost = skill_cost(mech)
        if cost <= 0:
            continue
        art = arts.get(sid)
        if not art:
            continue
        rr = art.get("required_realm")
        if rr not in axis:
            continue
        ng = axis[rr]["naegong"]
        p = pool_of(ng, cfg)
        uses = int(p // cost)
        mark = "❌ 0회" if uses == 0 else ("⚠️ 1회" if uses == 1 else f"{uses}회")
        rep.say(f"       {sid:<18} {rr:<8} {ng:>4.2f}  {p:>5}  {cost:>5.0f}   {mark}")
        if uses == 0:
            zero_use.append((sid, art.get("name", sid), rr, p, cost))

    rep.say("")
    for sid, nm, rr, p, cost in zero_use:
        rep.fail(f"{sid}({nm}) — '{rr}'의 내력 풀 {p} < 시전 코스트 {cost:g}. 한 번도 못 쓴다. "
                 f"자원 관리가 아니라 형벌이다")
    if not zero_use:
        rep.ok("내력 수지 — 전 무공이 요구 경지에서 최소 1회 이상 시전 가능")

    # 격의 위력 — 코스트는 있는데 이득이 수치로 있는가
    rep.say("")
    forms = dig(cfg, "qi_manifestation.yml", "forms", default={}) or {}
    dmg_formula = str(dig(cfg, "combat.yml", "damage", "formula", default=""))
    has_qi_term = any(k in dmg_formula for k in ("발경", "검기", "강기", "기_발현", "격 위력"))
    numeric_effects = 0
    for form in forms.values():
        if not isinstance(form, dict):
            continue
        for spec in form.values():
            if isinstance(spec, dict):
                eff = str(spec.get("effect", ""))
                if re.search(r"[+\-]\s*\d", eff):
                    numeric_effects += 1
    rep.say(f"     피해 공식: \"{dmg_formula}\"")
    if not has_qi_term:
        rep.fail(f"피해 공식에 '격' 항이 없다 — 발경·검기·강기를 실어도 피해는 그대로다. "
                 f"qi_manifestation.yml forms 의 효과는 전부 서술뿐("
                 f"'근접 위력 +무공 보정', '위력 ++') — 수치 효과 {numeric_effects}건. "
                 f"내력을 태우는 이득이 어디에도 수치로 없다")
    else:
        rep.ok("피해 공식에 격 항이 존재")


def lint_action_data(cfg, rep):
    rep.head("액션 데이터 — 프레임·경직·쿨다운이 정의된 무공과 안 된 무공")
    arts = dig(cfg, "skills.yml", "martial_arts", default={}) or {}
    mechs = dig(cfg, "skill_mechanics.yml", "skills", default={}) or {}

    have, missing = [], []
    for sid, art in sorted(arts.items()):
        (have if sid in mechs else missing).append(sid)

    def has_key(mech, key):
        if not isinstance(mech, dict):
            return False
        if key in mech:
            return True
        return any(isinstance(s, dict) and key in s for s in (mech.get("combo") or []))

    frames = [s for s in mechs if has_key(mechs[s], "frames")]
    stagger = [s for s in mechs if has_key(mechs[s], "stagger") or has_key(mechs[s], "on_hit")]
    cooldown = [s for s in mechs if has_key(mechs[s], "cooldown_ticks")]

    rep.say(f"     무공 카탈로그 {len(arts)}종 · 액션 데이터 {len(mechs)}종(NPC 표본 포함)")
    rep.say(f"     프레임 {len(frames)} · 경직 {len(stagger)} · 쿨다운 {len(cooldown)}")
    rep.say("")
    cov = len(have) / len(arts) * 100 if arts else 0
    if cov < 50:
        rep.fail(f"액션 데이터 커버리지 {cov:.0f}% ({len(have)}/{len(arts)}) — "
                 f"{len(missing)}종의 무공은 히트박스·프레임·경직·코스트가 없다. "
                 f"미정의: {', '.join(missing[:8])}{' …' if len(missing) > 8 else ''}")
    else:
        rep.ok(f"액션 데이터 커버리지 {cov:.0f}%")

    if len(cooldown) <= 1:
        rep.fail(f"쿨다운이 정의된 무공이 {len(cooldown)}종뿐({', '.join(cooldown) or '없음'}) — "
                 f"나머지는 내력만 있으면 매 라운드 무한 연타 가능. "
                 f"iframe_rules 는 '무적기는 내력 + 충전/쿨다운 이중 과금'이라 선언하는데, "
                 f"무적기 목록에 쿨다운이 실제로 붙은 것은 매화보뿐이다")
    else:
        rep.ok(f"쿨다운 정의 {len(cooldown)}종")

    # 절정급 무공에 액션 데이터가 없다 = 보스전 문법이 없다
    high = [s for s, a in arts.items()
            if isinstance(a, dict) and a.get("grade") in ("절정", "일류") and s not in mechs]
    if high:
        rep.warn(f"일류·절정급 무공 {len(high)}종에 액션 데이터 없음 — "
                 f"{', '.join(sorted(high)[:8])}{' …' if len(high) > 8 else ''} "
                 f"(고수전의 프레임 문법이 통째로 비어 있다)")


def lint_qi_ladder(cfg, rep):
    rep.head("격 사다리 — qi_manifestation ↔ internal_energy ↔ ultimate_arts")
    names, _ = realm_axis(cfg)
    grades = dig(cfg, "qi_manifestation.yml", "grades", default={}) or {}
    gates = dig(cfg, "internal_energy.yml", "realm_gates", default={}) or {}
    bands = dig(cfg, "internal_energy.yml", "cost_bands", default={}) or {}

    mismatch = []
    for g, spec in grades.items():
        if not isinstance(spec, dict):
            continue
        gate = spec.get("gate")
        opened = gate_realm(cfg, g, names)
        if gate != opened:
            mismatch.append(f"{g}: qi_manifestation gate '{gate}' vs realm_gates 개방 '{opened}'")
    rep.verdict(not mismatch,
                f"격 5단(발경·검기·강기·어검·심검)의 경지 게이트가 realm_gates 와 일치"
                if not mismatch else "격 게이트 불일치: " + " · ".join(mismatch))

    # 형태별 코스트가 밴드 안에 있는가
    forms = dig(cfg, "qi_manifestation.yml", "forms", default={}) or {}
    out_of_band = []
    for fname, form in forms.items():
        if not isinstance(form, dict):
            continue
        for sname, spec in form.items():
            if not isinstance(spec, dict):
                continue
            band = "강기" if ("강기" in sname or "검강" in sname or "호신" in sname) else (
                "검기" if "검기" in sname else ("어검" if "어검" in sname else None))
            if not band or band not in bands:
                continue
            rng = (bands.get(band) or {}).get("cost")
            if not isinstance(rng, list):
                continue
            lo, hi = num(rng[0]), num(rng[-1])
            for key in ("cost", "deploy", "sustain_per_round", "cast"):
                if key in spec:
                    v = num(spec.get(key), 0)
                    if v and not (lo <= v <= hi):
                        out_of_band.append(f"{sname}.{key}={v:g} (밴드 {band} {lo:g}~{hi:g})")
    if out_of_band:
        rep.warn(f"형태별 코스트가 cost_bands 밖: {' · '.join(out_of_band)} — "
                 f"qi_manifestation 이 '본 파일이 우선'이라 선언하나, 엔진이 어느 수치를 밴드 검사에 쓸지 "
                 f"모호하다 (호신강기 = 강기급 방어를 강기 최저 코스트(4)의 절반인 유지 2/라운드로 무한 유지)")
    else:
        rep.ok("형태별 코스트가 전부 cost_bands 안")

    # 오의 사다리
    ladder = dig(cfg, "ultimate_arts.yml", "realm_ladder", default={}) or {}
    bad = [r for r in ladder if r not in names]
    rep.verdict(not bad, "오의 사다리(개안→완성→자재→창작)의 경지가 전부 실재"
                if not bad else f"오의 사다리 미등록 경지: {bad}")


def lint_weapon_break(cfg, rep):
    rep.head("무기 파괴 — 감당 격과 격돌 횟수")
    wq = dig(cfg, "qi_manifestation.yml", "weapon_grades", default={}) or {}
    we = dig(cfg, "equipment.yml", "weapon_grades", default={}) or {}
    grades = dig(cfg, "qi_manifestation.yml", "grades", default={}) or {}

    diff = []
    for g, spec in we.items():
        a = (wq.get(g) or {}).get("withstands")
        b = (spec or {}).get("withstands")
        if a != b:
            diff.append(f"{g}: qi '{a}' vs equipment '{b}'")
    rep.verdict(not diff, f"무기 등급 {len(we)}종의 감당 격이 두 정본에서 동일"
                if not diff else "감당 격 불일치: " + " · ".join(diff))

    bad = [f"{g}→{(s or {}).get('withstands')}" for g, s in we.items()
           if (s or {}).get("withstands") not in grades]
    rep.verdict(not bad, "감당 격이 전부 격 사다리 위의 값"
                if not bad else f"미등록 격 참조: {', '.join(bad)}")

    breaks_at = num(dig(cfg, "qi_manifestation.yml", "weapon_break", "rule", "1격_초과", "breaks_at"), 3)
    note = str((we.get("정련") or {}).get("note", ""))
    if "3합" in note or "3 합" in note:
        rep.warn(f"equipment.yml 정련 note: \"{note}\" — 그런데 정련은 검기를 '감당'하므로 "
                 f"weapon_break 규칙상 절정(검기) 상대 손상은 0이다. '3합을 버틴다'는 "
                 f"범철(1격 초과 → {breaks_at:g}격돌에 파괴)의 서술이다. 서사와 규칙이 어긋난다")

    bonus = {g: num((s or {}).get("judgment_bonus"), 0) for g, s in we.items()}
    spread = max(bonus.values()) - min(bonus.values()) if bonus else 0
    cap = num(dig(cfg, "equipment.yml", "caps", "equipment_judgment_bonus_total"), 2)
    rep.say(f"     판정 보정: {', '.join(f'{g} +{b:g}' for g, b in bonus.items())} (총합 캡 {cap:g})")
    rep.say(f"     → 등급 간 판정 격차는 최대 {spread:g}. 무기의 값은 보정이 아니라 '부러지지 않는 것'에 있다 "
            f"(② 무기 등급 시뮬에서 수치로 확인)")


def lint_npc_and_beasts(cfg, rep):
    rep.head("NPC 전투 — 전의(戰意)와 짐승")
    m = dig(cfg, "npc_combat.yml", "morale", default={}) or {}
    inputs = m.get("inputs") or []
    checks = m.get("break_check") or {}
    outcomes = m.get("outcomes") or []

    rep.say(f"     전의 입력 {len(inputs)}종: {inputs}")
    rep.say(f"     붕괴 판정: {list(checks.keys())} · 결말: {outcomes}")

    numeric = [k for k, v in checks.items() if re.search(r"\d", str(v))]
    if len(numeric) < len(checks) or not checks:
        rep.fail(f"전의에 수치 축이 없다 — 입력은 4종이 선언되어 있으나 문턱을 가진 것은 "
                 f"'졸개: 내구 25% 이하 또는 두목 무력화' 하나뿐이다. "
                 f"전의 게이지·입력별 가중치·판정식이 없어 '아군 수', '상대 위세'는 엔진이 계산할 수 없다")

    # qi_manifestation 이 주장하는 배선 대상이 실재하는가
    claim = str(dig(cfg, "qi_manifestation.yml", "integration", "morale", default=""))
    if claim:
        joined = " ".join(str(i) for i in inputs)
        if "격" not in joined and "강기" not in joined:
            rep.fail(f"qi_manifestation.integration.morale 은 \"{claim}\" 이라고 배선을 주장하는데, "
                     f"npc_combat.yml morale.inputs 의 '상대_위세'는 (오의_목격·경지_격차)만 열거한다 — "
                     f"'격 목격'이라는 입력은 존재하지 않는다. 검강을 봐도 졸개는 도망가지 않는다")

    beasts = dig(cfg, "cultivation.yml", "combat_hwahu", "beast_ranks", default={}) or {}
    rep.say("")
    rep.say(f"     짐승 격: {', '.join(f'{k}={(v or {}).get(chr(114) + chr(97) + chr(110) + chr(107))}' for k, v in beasts.items())}")
    npcs = dig(cfg, "npcs/cheongha_npcs.yml", "npcs", default={}) or {}
    beast_entries = [k for k, v in npcs.items()
                     if isinstance(v, dict) and any(w in str(v.get("role", "")) for w in ("늑대", "맹수", "짐승", "호랑이"))]
    if not beast_entries:
        rep.fail(f"짐승에게 '격'(들짐승·맹수·영물)은 있는데 **능력치·내구·공격 수단이 어디에도 없다** — "
                 f"npcs/*.yml 에 짐승 항목 0건. 사냥은 성장(combat_hwahu)·경제(사냥 부산물)의 기둥인데 "
                 f"'늑대 한 마리'가 몇 합인지 계산할 자료가 없다 (본 도구는 경지 상당치로 대체 계산한다)")
    else:
        rep.ok(f"짐승 스탯 블록 {len(beast_entries)}건")


# ══════════════════════════════════════════════════════════════════════════════
#  ② 전투 시뮬 (해석적 — 2d6은 36가지뿐이다)
# ══════════════════════════════════════════════════════════════════════════════

class Fighter:
    def __init__(self, cfg, name, realm, attr=None, skill=None, weapon="검",
                 tech_grade=None, is_npc=False, naegong=None, stats=None,
                 beast=False, equip=0):
        _, axis = realm_axis(cfg)
        a = axis.get(realm, {"attr": 3, "skill": 0, "naegong": 0.0})
        self.cfg = cfg
        self.name = name
        self.realm = realm
        self.stats = stats or {}
        std = attr if attr is not None else a["attr"]     # 표준 무인 = 상한 −1 (SkillEngine.realmAttr)
        self.atk_stat = num(self.stats.get("근력"), std)
        self.def_stat = num(self.stats.get("민첩"), std)
        self.che = num(self.stats.get("체력"), std)
        # v2 의 축 — 병기가 공격 능력치를 정하고(검=민첩·도=근력·활=감각), 크리는 감각·지혜가 산다
        self.atk_attr = num(self.stats.get(attack_attr_name(cfg, weapon)), std)
        self.sense = num(self.stats.get("감각"), std)
        self.wis = num(self.stats.get("지혜"), std)
        self.skill = skill if skill is not None else a["skill"]
        self.is_npc = is_npc
        self.beast = beast
        self.naegong = naegong if naegong is not None else a["naegong"]
        self.weapon = weapon
        wp = weapon_power(cfg, weapon)
        self.wpower = wp if wp is not None else 1.0
        tg = tech_grade or realm
        tp = tech_power(cfg, tg)
        self.tpower = tp if tp is not None else 2.0     # 일류급 구멍 — 도구의 대체값
        self.tpower_assumed = tp is None
        # 【대칭 원칙】 사람은 플레이어든 NPC든 같은 공식을 쓴다 — 경지 보정도 같이 받는다.
        #   (엔진 배선 주의: HuntingGrounds 가 NPC 내구를 10+체력×2 로 하드코딩하고 있다면
        #    이 모델과 어긋나고 플레이어만 두꺼워진다 — lint_vitality 가 그 규약을 못 보므로 사람이 봐야 한다)
        self.dur = durability(cfg, self.che, realm, beast=beast, equip=equip)
        self.pool = pool_of(self.naegong, cfg)

    def wound_pen(self, hp):
        r = hp / self.dur if self.dur else 0
        if hp <= 0:
            return -3
        if r < 0.25:
            return -2
        if r < 0.5:
            return -1
        return 0


def margin_dist(att, dfn, att_pen=0, dfn_pen=0, mod=0):
    """마진 → 확률(Fraction). 한쪽만 굴린다 (NPC는 고정 +7)."""
    npc_bonus = 7
    out = {}
    a = att.atk_stat + att.skill + att_pen + mod
    d = dfn.def_stat + dfn.skill + dfn_pen
    for roll, w in DICE_ITEMS:
        if att.is_npc and not dfn.is_npc:
            margin = (a + npc_bonus) - (d + roll)
        elif not att.is_npc and dfn.is_npc:
            margin = (a + roll) - (d + npc_bonus)
        elif not att.is_npc and not dfn.is_npc:
            margin = (a + roll) - (d + 7)      # PvP 근사 — 양측 2d6은 별도 취급
        else:
            margin = (a + npc_bonus) - (d + npc_bonus)
        out[margin] = out.get(margin, Fraction(0)) + Fraction(w, 36)
    return out


def strike_v2(att, dfn, qi_power=0.0, soak=0.0):
    """v2 한 합 — 명중 = 획(기하)이 이미 정했다 (항상 1.0). 반환 (명중률, 기대 피해, 크리 확률).

    피해 = max(1, 공격력 − 방어력) × 크리 기대 (SkillEngine.strikeV2 와 같은 줄):
      공격력 = 무기 위력 + 기술 숙련 + 능력치(병기 축) + 격 — 4항 (무공 위력표는 v2 에 없다)
      방어력 = floor(per_body × 체력 판정치) + 태세 경감(soak)  (SkillListener.defenseV2 —
               갑옷 항은 이 눈의 표준 무인이 안 입어 0 · v1 의 soak 근사와 같은 층)
      크리 기대 = (1−p)·1 + p·배수  (crit_ev_raw — 확률·배수 전부 등록부)
    부상·고갈·협공의 판정 보정은 여기 없다 — 실릴 판정이 없다 (v1 인자 att_pen·mod 는 v1 의 것).
    """
    v2 = v2_of(att.cfg)
    atk = att.wpower + att.skill + att.atk_attr + qi_power
    dfn_power = int(v2["per_body"] * dfn.che) + soak
    p, mult = crit_ev_raw(v2, att.sense, att.wis, att.weapon)
    dmg = max(1.0, atk - dfn_power) * ((1 - p) + p * mult)
    return 1.0, dmg, p


def strike(att, dfn, att_pen=0, dfn_pen=0, mod=0, qi_power=0.0, soak=0.0):
    """기대 피해·명중률·대성공률 (해석적). soak = 방어측 경감(막기 -3 등) — 피해에서 곧장 뺀다.

    ★ combat_v2.enabled 면 v2 산술로 갈아탄다 (strike_v2) — 엔진의 enabled 분기와 같은 문.
    """
    if v2_of(att.cfg):
        return strike_v2(att, dfn, qi_power=qi_power, soak=soak)
    dist = margin_dist(att, dfn, att_pen, dfn_pen, mod)
    hit = Fraction(0)
    crit = Fraction(0)
    dmg = Fraction(0)
    for m, p in dist.items():
        if m >= 0:
            hit += p
            base = max(0.0, att.wpower + att.tpower + qi_power + (m // 2) - soak)
            dmg += p * Fraction(int(base * 2), 2)
            if m >= 4:
                crit += p
    return float(hit), float(dmg), float(crit)


def duel(att, dfn, max_rounds=25, a_mod=0, d_mod=0, a_qi=0.0, d_qi=0.0,
         a_attacks=1, d_attacks=1, d_immune=False, a_soak=0.0, d_soak=0.0):
    """기대값 결정론 진행 — 라운드마다 양측 기대 피해를 서로 깎는다.

    a_soak = 방어측(dfn)이 att 의 타격을 경감하는 값 · d_soak = 그 반대.
    """
    hp_a, hp_b = float(att.dur), float(dfn.dur)
    log = []
    for r in range(1, max_rounds + 1):
        # 선공(att)이 먼저 친다 — combat.yml initiative. 눕은 자는 반격하지 않는다
        pa, pb = att.wound_pen(hp_a), dfn.wound_pen(hp_b)
        _, da, _ = strike(att, dfn, pa, pb, a_mod, a_qi, a_soak)
        if d_immune:
            da = 0.0
        hp_b -= da * a_attacks
        if hp_b > 0:
            _, db, _ = strike(dfn, att, dfn.wound_pen(hp_b), pa, d_mod, d_qi, d_soak)
            hp_a -= db * d_attacks
        log.append((r, max(hp_a, 0), max(hp_b, 0)))
        if hp_b <= 0 or hp_a <= 0:
            break
    ttk = next((r for r, _, hb in log if hb <= 0), None)
    ttd = next((r for r, ha, _ in log if ha <= 0), None)
    return ttk, ttd, log


# ══════════════════════════════════════════════════════════════════════════════
#  다대일 난전 — 슬롯·피포위 방어·강제 막기·전의(戰意)를 전부 굴린다
#  구판은 'a_attacks = n' 이 전부였다: 머릿수를 피해에 선형으로 곱하고, 등록된 규칙
#  (engage_slots · outnumbered_defense · morale)을 하나도 모델링하지 않았다. 눈이 얕았다.
# ══════════════════════════════════════════════════════════════════════════════

def morale_gauge(cfg, foe, hp_ratio, allies_alive, enemies_alive, boss_dead, seen_grade=None):
    """전의 = npc_combat.yml morale.weights 를 그대로 판독해 매 라운드 재계산 (누적 아님 — 엔진과 동일)."""
    w = dig(cfg, "npc_combat.yml", "morale", "weights", default={}) or {}
    start = foe["morale_start"]
    gauge = start

    dur_w = w.get("내구_비율") or {}
    if hp_ratio <= 0.25:
        gauge += num(dur_w.get("25%_이하"), -4)
    elif hp_ratio <= 0.50:
        gauge += num(dur_w.get("50%_이하"), -2)

    ally_w = w.get("아군_수") or {}
    if allies_alive == 0:
        gauge += num(ally_w.get("혼자_남음"), -3)
    elif allies_alive > enemies_alive:
        gauge += num(ally_w.get("수적_우세"), 2)
    elif allies_alive < enemies_alive:
        gauge += num(ally_w.get("수적_열세"), -2)

    if boss_dead:
        gauge += num(dig(w, "두목_생사", "두목_사망"), -5)

    if seen_grade:
        gauge += num(dig(w, "상대_위세", "격_목격", seen_grade), 0)
    return gauge


def melee(cfg, hero, foes, max_rounds=25, seen_grade=None, focus_boss=True):
    """1(플레이어) 대 다(NPC) 난전 — config 의 규칙 전부를 굴린다.

    반환: dict(생존 합수 · 소탕 합수 · 이탈 인원 · 결말)
    """
    rules = gang_rules(cfg)
    hp_hero = float(hero.dur)
    state = [{"f": f["fighter"], "hp": float(f["fighter"].dur), "boss": f.get("boss", False),
              "morale_start": f.get("morale_start", 5), "morale_break": f.get("morale_break", 3),
              "out": None} for f in foes]
    total = len(state)
    down, ttk_hero, rounds = None, None, 0

    for r in range(1, max_rounds + 1):
        rounds = r
        alive = [s for s in state if s["out"] is None]
        if not alive:
            ttk_hero = r - 1
            break

        # ① 영웅의 한 합 — 두목 우선(전의를 무너뜨리는 수), 아니면 가장 약해진 자
        target = next((s for s in alive if s["boss"]), None) if focus_boss else None
        if target is None:
            target = min(alive, key=lambda s: s["hp"])
        _, dh, _ = strike(hero, target["f"], att_pen=hero.wound_pen(hp_hero))
        target["hp"] -= dh
        if target["hp"] <= 0:
            target["out"] = "사망"

        alive = [s for s in state if s["out"] is None]
        if not alive:
            ttk_hero = r
            break

        # ② 적의 한 합 — 슬롯이 찬 만큼만 손이 들어온다 (engage_slots)
        n_eng = engaged(rules, len(alive))
        mod = net_mod(rules, n_eng)
        soak = guard_soak(rules, n_eng)
        for s in alive[:n_eng]:
            _, db, _ = strike(s["f"], hero, att_pen=s["f"].wound_pen(s["hp"]),
                              dfn_pen=hero.wound_pen(hp_hero), mod=mod, soak=soak)
            hp_hero -= db
        if hp_hero <= 0 and down is None:
            down = r
            break

        # ③ 전의 — 무너진 자는 물러난다 (npc_combat morale.break_check)
        boss_dead = any(s["boss"] and s["out"] == "사망" for s in state)
        for s in alive:
            others = len([x for x in state if x["out"] is None and x is not s])
            g = morale_gauge(cfg, s, s["hp"] / s["f"].dur, others, 1, boss_dead, seen_grade)
            if g <= s["morale_break"]:
                s["out"] = "이탈"

    killed = len([s for s in state if s["out"] == "사망"])
    routed = len([s for s in state if s["out"] == "이탈"])
    if down is not None:
        outcome = "패배"
    elif killed + routed >= total:
        outcome = "소탕"
    else:
        outcome = "미결"
    return {"outcome": outcome, "down_at": down, "clear_at": ttk_hero, "rounds": rounds,
            "killed": killed, "routed": routed, "total": total, "hp_left": max(hp_hero, 0.0),
            "slots": engaged(rules, total), "net_mod": net_mod(rules, engaged(rules, total)),
            "soak": guard_soak(rules, engaged(rules, total))}


def simulate(cfg, rep, max_rounds):
    rep.say()
    rep.say("═" * 72)
    rep.say("  ② 전투 시뮬 — 해석적(2d6 = 36가지). 몬테카를로 없음")
    rep.say("═" * 72)
    sim_ttk(cfg, rep, max_rounds)
    sim_vitality_curve(cfg, rep, max_rounds)
    sim_energy_curve(cfg, rep, max_rounds)
    sim_qi_counters(cfg, rep, max_rounds)
    sim_weapon_grades(cfg, rep, max_rounds)
    sim_gangup(cfg, rep, max_rounds)
    sim_dead_options(cfg, rep)


def standard_fighters(cfg):
    """표준 전투 4종 — 짐승은 config 에 스탯이 없어 경지 상당치로 대체한다."""
    npcs = dig(cfg, "npcs/cheongha_npcs.yml", "npcs", default={}) or {}
    bandit_stats = (npcs.get("north_road_bandit") or {}).get("stats") or {}
    bandit_realm = (npcs.get("north_road_bandit") or {}).get("realm", "삼류")
    bandit_skill = num(bandit_stats.get("도법"), 1)

    return [
        ("삼류 무인 vs 늑대(들짐승 = 삼류 상당)",
         Fighter(cfg, "삼류 무인", "삼류", weapon="검"),
         Fighter(cfg, "늑대", "삼류", weapon="맨손", is_npc=True, beast=True), True),
        ("이류 무인 vs 산길 도적(졸개 — npcs 실 데이터)",
         Fighter(cfg, "이류 무인", "이류", weapon="검"),
         Fighter(cfg, "산길 도적", bandit_realm, weapon="도", is_npc=True,
                 skill=bandit_skill, stats=bandit_stats), False),
        ("일류 무인 vs 맹수(호랑이 = 일류 상당)",
         Fighter(cfg, "일류 무인", "일류", weapon="검", naegong=1.0),
         Fighter(cfg, "맹수", "일류", weapon="맨손", is_npc=True, beast=True), True),
        ("절정 무인 vs 절정 고수",
         Fighter(cfg, "절정 무인", "절정", weapon="검"),
         Fighter(cfg, "절정 고수", "절정", weapon="검", is_npc=True), False),
    ]


def sim_ttk(cfg, rep, max_rounds):
    v2 = v2_of(cfg)
    rep.head("TTK — 몇 합에 끝나는가 (3합 미만 = 전투가 없다 / 20합 초과 = 지루하다)")
    if v2:
        rep.say("     ★v2 — 피해 = max(1, 공격력 − 방어력) × 크리 기대 · 명중 = 획(항상 100%)")
        rep.say(f"     공격력 = 무기 + 숙련 + 능력치(병기 축) + 격 · 방어력 = floor({v2['per_body']:g}×체력) + 태세")
        rep.say("     내구 = round(10 + 체력×2) · 부상 판정 페널티는 v2 에 없다 (실릴 판정이 없다)")
    else:
        rep.say("     피해 = 무기 위력 + 무공 위력 + floor(마진/2), 마진 ≥ 0 에서만 (부분 성공 = 피해 0)")
        rep.say("     내구 = round(10 + 체력×2) · 부상 페널티(경상 −1 / 중상 −2)는 라운드마다 반영")
    rep.say("")
    rep.say("     전투                                       명중률  피해/합  피격률  피해/합   TTK   피격 TTK")

    verdicts = []
    for label, p, e, synthetic in standard_fighters(cfg):
        h1, d1, _ = strike(p, e)
        h2, d2, _ = strike(e, p)
        ttk, ttd, log = duel(p, e, max_rounds)
        rep.say(f"     {label:<40} {h1 * 100:5.1f}% {d1:6.2f}  {h2 * 100:5.1f}% {d2:6.2f}  "
                f"{str(ttk) + '합' if ttk else '>' + str(max_rounds) + '합':>6} "
                f"{str(ttd) + '합' if ttd else '—':>7}")
        verdicts.append((label, ttk, ttd, p, e, log, synthetic))

    rep.say("")
    for label, ttk, ttd, p, e, log, synthetic in verdicts:
        tag = " (짐승 스탯 = 경지 상당 대체치)" if synthetic else ""
        if ttk is None:
            rep.fail(f"{label}: {max_rounds}합 안에 끝나지 않는다{tag} — 전투가 종결되지 않는 조합")
        elif ttk < 3:
            rep.fail(f"{label}: TTK {ttk}합 < 3합{tag} — 전투가 없다. 한 번 굴리고 끝난다")
        elif ttk > 20:
            rep.fail(f"{label}: TTK {ttk}합 > 20합{tag} — 지루하다")
        else:
            hp_left = log[ttk - 1][1] if ttk <= len(log) else 0
            ratio = hp_left / p.dur * 100
            note = ""
            if ttd is not None and ttd <= ttk:
                note = f" — 그러나 상대도 {ttd}합에 나를 눕힌다 (상호 격침)"
                rep.fail(f"{label}: TTK {ttk}합{tag}{note}")
                continue
            if ratio < 25:
                note = f" — 이기지만 내구 {ratio:.0f}% 만 남는다 (빈사 문턱)"
            rep.ok(f"{label}: TTK {ttk}합 (3~20합){tag}{note}")

    # 선공의 값 — 대칭 대결에서 초기 주도권이 전부를 결정하는가
    rep.say("")
    jp = Fighter(cfg, "절정 A", "절정", weapon="검")
    je = Fighter(cfg, "절정 B", "절정", weapon="검", is_npc=True)
    t_ab, d_ab, log_ab = duel(jp, je, max_rounds)
    left = log_ab[-1][1] if log_ab else 0
    init = str(dig(cfg, "combat.yml", "initiative", "frontal", default=""))
    rep.say(f"     선공의 값 (절정 vs 절정, 완전 대칭): 선공자가 {t_ab}합에 이기고 내구 {left:.1f}/{jp.dur} "
            f"({left / jp.dur * 100:.0f}%) 를 남긴다 — 후공자는 이 대결을 이길 수 없다")
    rep.say(f"     선공 규칙: \"{init}\"")
    rep.warn(f"완전 대칭 대결(같은 경지·같은 능력치)에서 선공 규칙이 승자를 정하지 못한다 — "
             f"'민첩+감각 동률 → 경지 높은 쪽'인데 경지도 같으면 그다음 규칙이 없다. "
             f"그리고 선공은 전부다: 대칭 대결의 승패가 판정이 아니라 선공 결정에서 이미 끝난다 "
             f"(선공자 내구 {left / jp.dur * 100:.0f}% 잔존)")

    # 일류급 무공 위력이 대체값이라는 사실을 드러낸다 (v2 는 무공 위력표를 안 읽는다 — v1 의 경고)
    if not v2 and any(f.tpower_assumed for _, f, _, _ in standard_fighters(cfg)):
        rep.warn("위 표의 일류 무인 피해에는 '일류급 무공 위력' 대체값 2 가 들어갔다 — "
                 "combat.yml technique_power 에 일류급이 없어서다 (① 린트 참조). "
                 "실제 수치가 정해지면 이 줄의 TTK 는 바뀐다")


#: 경지가 실제로 쓰는 격 — internal_energy.yml realm_gates 의 '그 경지에서 가장 높은 공격 격'
_ATTACK_BANDS = ["심검", "어검", "강기", "검기", "발경", "외공기"]


def top_band(cfg, realm):
    """그 경지가 쓸 수 있는 가장 높은 공격 격 (realm_gates 판독 — 도구가 고르지 않는다)."""
    gates = dig(cfg, "internal_energy.yml", "realm_gates", default={}) or {}
    have = gates.get(realm) or []
    for band in _ATTACK_BANDS:
        if band in have:
            return band
    return "외공기"


def sim_vitality_curve(cfg, rep, max_rounds):
    """체력 곡선 — 경지별 내구와 그 내구가 만드는 TTK.

    핵심 질문: **내구를 올렸는데 전투가 늘어지지 않는가.**
    답: 격 사다리(외공기 0 → 심검 5)가 흡수한다. 그래서 두 열을 나란히 잰다 —
        '외공기 TTK'(격을 안 쓴 몸)와 '격 TTK'(그 경지가 실제로 쓰는 격).
        전자는 길어져도 좋다 (내력을 태우라는 압력이다). 후자가 밴드를 벗어나면 그건 설계 실패다.
    """
    rep.head("체력 곡선 — 경지별 내구와 TTK (내구를 올리고도 전투가 늘어지지 않는가)")
    rep.say("     내구 = round(10 + 체력×2 + 경지 보정) · 표준 무인(체력 = 경지 상한 −1) 동경지 대결")
    rep.say("     격 TTK = 그 경지가 실제로 쓰는 격을 실었을 때 (internal_energy realm_gates)")
    rep.say("     ※ 외공기 TTK 가 길어지는 것은 의도다 — 내력을 태우라는 압력. 격 TTK 가 정본이다")
    rep.say("")
    rep.say(f"     {'경지':<6} {'체력':>4} {'내구':>5} {'(기본)':>7} {'격':>5} "
            f"{'피해/합':>8} {'외공TTK':>8} {'격TTK':>7}")

    qi_powers = dig(cfg, "combat.yml", "damage", "qi_power", default={}) or {}
    names = [nm for nm in realm_names(cfg) if nm != "범인"]
    band_lo, band_hi = 5, 9          # 설계 목표 (경고)
    hard_lo, hard_hi = 3, 12         # 불가침 (위반)
    rows = []

    for nm in names:
        p = Fighter(cfg, "무인", nm, weapon="검")
        e = Fighter(cfg, "고수", nm, weapon="검", is_npc=True)
        band = top_band(cfg, nm)
        qi = num(qi_powers.get(band), 0)
        base = int(round(10 + p.che * 2))
        ttk0, _, _ = duel(p, e, max_rounds)
        ttkq, _, _ = duel(p, e, max_rounds, a_qi=qi, d_qi=qi)
        _, dmg, _ = strike(p, e, qi_power=qi)
        rep.say(f"     {nm:<6} {p.che:>4} {p.dur:>5} {'(' + str(base) + ')':>7} {band:>5} "
                f"{dmg:>8.2f} {str(ttk0) + '합' if ttk0 else '>' + str(max_rounds):>8} "
                f"{str(ttkq) + '합' if ttkq else '>' + str(max_rounds):>7}")
        rows.append((nm, p.dur, base, band, ttk0, ttkq))

    rep.say("")

    # ① 격 TTK 가 불가침 밴드 안에 있는가 — 이것이 '내구를 올려도 되는가'의 답이다
    bad = [(nm, t) for nm, _, _, _, _, t in rows
           if t is None or t < hard_lo or t > hard_hi]
    if bad:
        rep.fail(f"격 TTK 가 밴드({hard_lo}~{hard_hi}합)를 벗어난다: {bad} — "
                 "내구를 올렸는데 피해가 따라오지 않았다. 경지 보정을 낮추거나 격 위력을 올려라")
    else:
        rep.ok(f"격 TTK 전 경지 {hard_lo}~{hard_hi}합 이내 — 내구 상승을 격 사다리가 흡수한다")

    # ② 설계 목표 5~9합 (경고 — 불가침은 아니다)
    soft = [(nm, t) for nm, _, _, _, _, t in rows
            if t is not None and not (band_lo <= t <= band_hi)]
    if soft:
        rep.warn(f"격 TTK 가 설계 목표({band_lo}~{band_hi}합) 밖: {soft}")
    else:
        rep.ok(f"격 TTK 전 경지 {band_lo}~{band_hi}합 — 삼류든 생사경이든 한 판의 길이가 같다")

    # ③ 내구가 단조 증가하는가
    durs = [d for _, d, _, _, _, _ in rows]
    rep.verdict(all(b >= a for a, b in zip(durs, durs[1:])),
                f"내구 단조 증가 — {durs[0]} → {durs[-1]} ({durs[-1] / durs[0]:.2f}배)")

    # ④ 【핵심 검산】 내구는 늘었는데 격 TTK 는 평평한가 (= 피해가 함께 컸다는 증거)
    qs = [t for _, _, _, _, _, t in rows if t is not None]
    if qs:
        spread = max(qs) - min(qs)
        rep.verdict(spread <= 3,
                    f"격 TTK 편차 {spread}합 (최소 {min(qs)} · 최대 {max(qs)}) — "
                    f"내구가 {durs[-1] / durs[0]:.2f}배 되는 동안 한 판의 길이는 그대로다. "
                    f"피해(격 사다리)가 내구를 정확히 따라왔다"
                    if spread <= 3 else
                    f"격 TTK 편차 {spread}합 — 경지에 따라 전투 길이가 달라진다 "
                    f"(내구와 피해의 성장 속도가 어긋났다)")

    # ⑤ 장비 캡을 다 두른 몸이 전투를 늘어뜨리는가
    cap = equip_vit_cap(cfg)
    if cap > 0:
        worst = []
        for nm in names:
            p = Fighter(cfg, "무인", nm, weapon="검", equip=cap)
            e = Fighter(cfg, "고수", nm, weapon="검", is_npc=True, equip=cap)
            qi = num(qi_powers.get(top_band(cfg, nm)), 0)
            t, _, _ = duel(p, e, max_rounds, a_qi=qi, d_qi=qi)
            if t is None or t > hard_hi:
                worst.append((nm, t))
        rep.verdict(not worst,
                    f"장비 캡(+{cap}) 완전 무장 양측 대결도 격 TTK ≤ {hard_hi}합 — "
                    f"장비는 전투를 늘어뜨리지 않는다 (캡의 존재 이유)"
                    if not worst else f"완전 무장 시 전투가 늘어진다: {worst} — 캡 {cap} 이 너무 헐겁다")


def sim_energy_curve(cfg, rep, max_rounds):
    rep.head("내력 곡선 — 일류가 발경만 써도 몇 합에 고갈되는가")
    bands = dig(cfg, "internal_energy.yml", "cost_bands", default={}) or {}
    bal = mid(dig(bands, "발경", "cost"), 1)
    depleted = str(dig(cfg, "internal_energy.yml", "internal_energy", "depleted", "state", default="내공 고갈"))
    dep_pen = num(dig(cfg, "judgment.yml", "situation_modifiers", "condition", "내공_고갈"), -2)
    regen, conditional = combat_regen(cfg, 1.0)   # 일류(내공 1)의 숨 — 이 절의 주인공
    fight = 7           # 표준 전투 = 5~9합의 중앙값 (본 도구의 기준 전투)
    if v2_of(cfg):
        rep.say(f"     발경 = 내력 {bal:g}/합 · 고갈 = '{depleted}' → 다운캐스트('맨 기술' — v2 는 판정 페널티 없음)")
    else:
        rep.say(f"     발경 = 내력 {bal:g}/합 · 고갈 = '{depleted}'(판정 {dep_pen:g}) + 다운캐스트('맨 기술')")
    rep.say(f"     전투 중 회복(조식) = 내공에 비례 (일류 = {regen:g}/합)"
            f"{' · 조건: 내력을 태운 합에는 안 돈다' if conditional else ' · 무조건'}"
            f" · 운기조식(앉기) = {dig(cfg, 'internal_energy.yml', 'internal_energy', 'recovery', 'in_combat', '운기조식', default='불가')}")
    rep.say(f"     표준 전투 {fight}합 기준 — '지속 합수'가 아니라 '{fight}합 동안 몇 번 싣는가'를 잰다")
    rep.say("")
    rep.say("       내공  내력풀  조식/합   발경 연발(버스트)   발경/7합   검기_참격(3)/7합   호신강기(전개2+유지2)")
    for ng in (0.33, 1.0, 2.0, 3.0, 5.0, 7.0):
        p = pool_of(ng, cfg)
        rg = combat_regen(cfg, ng)[0]
        burst = int(p // bal) if bal else 0
        b = qi_casts(p, bal, rg, fight)
        gi = qi_casts(p, 3, rg, fight)
        hosin = int((p - 2) // 2) if p >= 2 else 0   # 호신강기 중엔 조식이 멎는다 (blocked_by_guard)
        rep.say(f"       {ng:>4.2f}  {p:>5}  {rg:>5}   {burst:>13}합   {b:>7}회   {gi:>13}회   {hosin:>16}합")

    rep.say("")
    # ① 개화의 보상 — 한 번은 형벌이다. 그러나 매 합이면 격은 공짜다. 그 사이가 '자원 관리'다
    p_bloom = pool_of(1.0 / 3.0, cfg)
    regen_bloom = combat_regen(cfg, 1.0 / 3.0)[0]
    bloom_casts = qi_casts(p_bloom, bal, regen_bloom, fight)
    if bloom_casts < 3:
        rep.fail(f"개화 직후(내공 0.33 → 내력 {p_bloom}) = 표준 전투 {fight}합에서 발경 {bloom_casts}회. "
                 f"'개화의 보상'인 발경이 전투당 {bloom_casts}회다 — 자원 관리가 아니라 형벌 "
                 f"(조식 회복 {regen_bloom:g}/합)")
    elif bloom_casts >= fight:
        rep.fail(f"개화 직후(내력 {p_bloom})가 {fight}합 내내 발경을 싣는다 ({bloom_casts}회) — 격이 공짜다. "
                 f"조식 회복 {regen_bloom:g}/합이 발경 코스트 {bal:g} 이상인데 조건"
                 f"('내력을 안 쓴 합에만')이 {'없다' if not conditional else '있는데도 물지 않았다'}")
    else:
        rep.ok(f"개화 직후(내력 {p_bloom}): {fight}합에서 발경 {bloom_casts}회 — 한 합 태우고 한 합 고른다. "
               f"자원의 리듬이 있다 (전소도 무한도 아니다)")

    # ② 축기의 값 — 총량이 아니라 '연발'이다
    p_first = pool_of(1.0, cfg)
    burst_bloom = int(p_bloom // bal) if bal else 0
    burst_first = int(p_first // bal) if bal else 0
    first_casts = qi_casts(p_first, bal, regen, fight)
    if burst_first <= burst_bloom:
        rep.fail(f"축기 1년(내공 1.0 → 내력 {p_first})의 연발이 개화 직후({p_bloom})와 같다 ({burst_first}합) — "
                 f"축기가 아무것도 사지 않는다")
    else:
        rep.ok(f"축기 1년(내력 {p_first}): 발경 {burst_first}합 연발 · {fight}합에 {first_casts}회 "
               f"(개화 직후 연발 {burst_bloom}합 · {bloom_casts}회) — 축기가 사는 것은 총량이 아니라 "
               f"**몰아 쓸 수 있는 합**이다")

    # 다운캐스트로 계속 싸울 수 있는가 (고갈 후 능력)
    p1 = Fighter(cfg, "일류(고갈)", "일류", weapon="검", naegong=1.0)
    e1 = Fighter(cfg, "맹수", "일류", weapon="맨손", is_npc=True)
    rep.say("")
    if v2_of(cfg):
        # ★v2 — 고갈의 판정 페널티(내공_고갈 −2)는 없다 (실릴 판정이 없다 · 사용자 확정: 제거).
        #   고갈의 대가 = 격 다운캐스트뿐이다: 내지 못한 합의 피해가 외공기 값으로 떨어진다
        #   (combat.yml qi_power_note — "지불이 끊기면 그 합의 피해는 외공기 값으로 떨어진다")
        bal_power = num(dig(cfg, "combat.yml", "damage", "qi_power", "발경"), 1)
        _, d_full, _ = strike(p1, e1, qi_power=bal_power)
        _, d_dep, _ = strike(p1, e1)
        drop = (d_full - d_dep) / d_full * 100 if d_full else 0
        ttk_full, _, _ = duel(p1, e1, max_rounds, a_qi=bal_power)
        ttk_dep, ttd_dep, _ = duel(p1, e1, max_rounds)
        rep.say(f"     ★v2 — 고갈의 대가는 판정 페널티가 아니라 **다운캐스트**다 (격이 외공기로 떨어진다)")
        rep.say(f"     고갈 전(발경 +{bal_power:g}):  피해/합 {d_full:.2f} · TTK {ttk_full}합")
        rep.say(f"     고갈 후(외공기):     피해/합 {d_dep:.2f}(−{drop:.0f}%) · "
                f"TTK {ttk_dep if ttk_dep else '>' + str(max_rounds)}합"
                f"{'  ← 상대가 ' + str(ttd_dep) + '합에 먼저 나를 눕힌다' if ttd_dep and (not ttk_dep or ttd_dep <= ttk_dep) else ''}")
        if ttk_dep is None or (ttd_dep and ttd_dep <= ttk_dep):
            rep.fail("고갈(다운캐스트) 상태로는 같은 상대를 이길 수 없다 — 격을 잃는 순간 그 전투의 "
                     "후반을 저당잡힌다 (내력을 태울지 아낄지가 진짜 선택이 되려면 외공기로도 길은 남아야 한다)")
        else:
            rep.ok(f"고갈 후에도 다운캐스트(외공기)로 전투 지속 가능 (TTK {ttk_full}합 → {ttk_dep}합) — "
                   f"격의 값(−{drop:.0f}%)은 치르되 지는 길은 아니다")
    else:
        h_full, d_full, _ = strike(p1, e1)
        h_dep, d_dep, _ = strike(p1, e1, att_pen=int(dep_pen))
        drop = (d_full - d_dep) / d_full * 100 if d_full else 0
        ttk_full, _, _ = duel(p1, e1, max_rounds)
        ttk_dep, ttd_dep, _ = duel(p1, e1, max_rounds, a_mod=int(dep_pen))
        rep.say(f"     고갈 전:  명중 {h_full * 100:.1f}% · 피해/합 {d_full:.2f} · TTK {ttk_full}합")
        rep.say(f"     고갈 후:  명중 {h_dep * 100:.1f}% · 피해/합 {d_dep:.2f}(−{drop:.0f}%) · "
                f"TTK {ttk_dep if ttk_dep else '>' + str(max_rounds)}합"
                f"{'  ← 상대가 ' + str(ttd_dep) + '합에 먼저 나를 눕힌다' if ttd_dep and (not ttk_dep or ttd_dep <= ttk_dep) else ''}")
        if ttk_dep is None or (ttd_dep and ttd_dep <= ttk_dep):
            rep.fail(f"고갈(판정 {dep_pen:g}) 상태로는 같은 상대를 이길 수 없다 — 다운캐스트는 '계속 싸우는 길'이 "
                     f"아니라 '지는 길'이다. 내력을 태우는 순간 그 전투의 후반을 저당잡힌다")
        else:
            rep.ok(f"고갈 후에도 다운캐스트로 전투 지속 가능 (TTK {ttk_full}합 → {ttk_dep}합)")


def sim_qi_counters(cfg, rep, max_rounds):
    rep.head("격 상성의 실효 — 관통·회피·무기 파괴가 수치로 차이를 만드는가")
    forms = dig(cfg, "qi_manifestation.yml", "forms", default={}) or {}
    hosin = dig(forms, "두름_몸", "호신강기", default={}) or {}
    on_hit = str(hosin.get("on_hit", ""))
    rep.say(f"     원칙 1(관통): {dig(cfg, 'qi_manifestation.yml', 'counters', '원칙_1_관통', default='')}")
    rep.say(f"     원칙 2(회피): {dig(cfg, 'qi_manifestation.yml', 'counters', '원칙_2_회피', default='')}")
    rep.say(f"     호신강기 on_hit: {on_hit}")
    rep.say("")

    # 격의 위력값이 config 에 있는가
    rep.say("     [A] 격을 실었을 때의 피해 증가 — config 가 정의한 값으로")
    p = Fighter(cfg, "절정", "절정", weapon="검")
    e = Fighter(cfg, "절정", "절정", weapon="검", is_npc=True)
    _, d0, _ = strike(p, e, qi_power=0.0)
    rep.say(f"       외공기(격 없음)  피해/합 {d0:.2f}")
    qi_power = dig(cfg, "combat.yml", "damage", "qi_power", default={}) or {}
    if not qi_power:
        rep.fail("격의 위력이 config 에 없다 — 피해 공식에 격 항이 없으면 내력을 태워도 피해가 같다 "
                 "(발경은 쓸 이유가 없는 죽은 선택지가 된다)")
    else:
        base = d0 if d0 else 1.0
        worst = None
        for grade in ("발경", "검기", "강기"):
            power = float(qi_power.get(grade, 0) or 0)
            dmg = d0 + power
            delta = 100.0 * (dmg - base) / base
            rep.say(f"       {grade}(위력 +{power:g})   피해/합 {dmg:.2f}   Δ +{delta:.1f}%")
            worst = delta if worst is None else min(worst, delta)
        if worst is None or worst < 5.0:
            rep.fail(f"격의 피해 기여가 {worst:.1f}% — 5% 미만이면 장식이다 (내력을 태울 이유가 없다)")
        else:
            rep.ok(f"격이 피해를 움직인다 (최소 +{worst:.1f}%) — 내력을 태우면 아프다")

    rep.say("")
    rep.say("     [B] 격이 위력을 갖는다면 — 도구의 참조 제안(발경 +1 / 검기 +2 / 강기 +3)")
    rep.say("       격        피해/합   외공기 대비   TTK(절정 vs 절정)")
    for nm, qp in (("외공기", 0.0), ("발경 +1", 1.0), ("검기 +2", 2.0), ("강기 +3", 3.0)):
        _, dq, _ = strike(p, e, qi_power=qp)
        t, _, _ = duel(p, e, max_rounds, a_qi=qp)
        delta = (dq - d0) / d0 * 100 if d0 else 0
        rep.say(f"       {nm:<9} {dq:>6.2f}   {delta:>+8.1f}%   {t}합")
    rep.say("       → 격 한 단계의 무게를 나란히 보는 참조 표 (정본은 damage.qi_power — [A] 가 그 실측이다)")

    rep.say("")
    rep.say("     [C] 호신강기 — 하위 격 자동 무효의 실제 효과")
    hwa = Fighter(cfg, "화경 고수", "화경", weapon="검", is_npc=True, naegong=7.0)
    jj = Fighter(cfg, "절정 무인", "절정", weapon="검")
    _, d_att, _ = strike(jj, hwa)
    sustain = num(hosin.get("sustain_per_round"), 2)
    deploy = num(hosin.get("deploy"), 2)
    max_sustain = int((hwa.pool - deploy) // sustain) if sustain else 0
    ttk_i, ttd_i, _ = duel(jj, hwa, max_rounds, d_immune=True)
    rep.say(f"       절정(최대 격 = 검기)의 공격 피해/합 = {d_att:.2f}")
    rep.say(f"       호신강기(강기)는 '하위 격 자동 무효' → 절정의 검기·발경·외공기 전부 무효 → 피해/합 = 0.00")
    rep.say(f"       화경 내력 풀 {hwa.pool} → 호신강기 전개 {deploy:g} + 유지 {sustain:g}/합 = "
            f"{max_sustain}합 무한 방어. 그 사이 화경은 {ttd_i}합에 절정을 눕힌다")
    # 상쇄 소모는 **밴드별로** 적혀 있다 (on_hit.하위_격/동격/상위_격). 구판은 on_hit 최상단에서만 찾다가
    # 못 보고 "수치가 없다"고 외쳤다 — 눈이 한 켜 얕았다.
    on_hit = dig(cfg, "qi_manifestation.yml", "forms", "두름_몸", "호신강기", "on_hit", default=None)
    bands = {}
    if isinstance(on_hit, dict):
        for band in ("하위_격", "동격", "상위_격"):
            b = on_hit.get(band)
            if isinstance(b, dict) and b.get("상쇄_소모") is not None:
                bands[band] = num(b.get("상쇄_소모"), 0)
    if not bands.get("하위_격"):
        rep.fail("호신강기가 절대 방어다 — 하위 격을 무효화하는 대가(상쇄_소모)가 수치로 없다. "
                 "하위 격의 답이 규칙에 없으면 승률은 구조적으로 0 이다")
    else:
        drain = bands["하위_격"]
        # 하위 격이 두들겨 강기를 말리는 데 몇 합이 걸리나 = (풀 - 전개) / (유지 + 상쇄)
        per_round = sustain + drain
        strip = int((hwa.pool - deploy) // per_round) if per_round else 0
        rep.ok(f"호신강기는 두들기면 깎인다 — 무효화 1회당 상쇄 소모 {drain:g} "
               f"(동격 {bands.get('동격', 0):g} · 상위 격 {bands.get('상위_격', 0):g}). "
               f"절정이 매 합 두들기면 화경의 강기는 {strip}합에 마른다 (유지 {sustain:g} + 상쇄 {drain:g}/합). "
               f"하위 격의 답 = 소모전이다")
        if strip > max_rounds:
            rep.warn(f"그래도 {strip}합 — 전투 상한({max_rounds}합)보다 길다. 소모전이 이론에만 있다")


def _grades_judgment_v2(cfg, rep, p, e):
    """[A] 의 v2 — 등급 판정 보정(judgment_bonus)은 전투에 실리지 않는다 (실릴 판정이 없다).

    v2 공격력 4항(무기+숙련+능력치+격)에 등급 항이 없다 — SkillEngine.strikeV2 가 그 정본이고,
    weaponJudgmentBonus 는 v1 execBase 의 것이다. 등급의 전투 값 = 감당 격(부러지지 않는 것 —
    [B])뿐이고, 판정 보정은 TRPG 경로(서장·퀘스트)에서만 산다.
    """
    rep.say("     [A] ★v2 — 등급 판정 보정(judgment_bonus)은 전투에 실리지 않는다 (판정이 없다)")
    rep.say("         등급의 전투 값 = 감당 격([B] 무기 파괴 내성)뿐 · 판정 보정은 TRPG 경로의 것")
    # 축 검사 — v1 은 '장비 천장 vs 격 천장'을 견줬다. v2 는 장비 판정의 전투 기여가 구조적으로 0 —
    # 그래도 격 천장이 실제로 피해를 움직이는지는 잰다 (안 움직이면 위력의 축 자체가 없다)
    _qi = dig(cfg, "combat.yml", "damage", "qi_power", default={}) or {}
    _top = max((int(num(v, 0)) for v in _qi.values()), default=0)
    _, d0, _ = strike(p, e)
    _, d_top, _ = strike(p, e, qi_power=_top)
    top_delta = (d_top - d0) / d0 * 100 if d0 else 0
    rep.verdict(top_delta > 0,
                f"위력의 축은 격이다 — 장비 판정의 전투 기여 0% < 격 최대(+{_top}) {top_delta:+.1f}% "
                f"(v2 가 축 뒤집힘을 구조적으로 봉했다)"
                if top_delta > 0 else
                f"격 천장(+{_top})이 피해를 안 움직인다 — 위력의 축이 어디에도 없다")
    # v2 의 무기 개성 — 크리 표가 등급이 아니라 **계열**에 산다 (단검=급소 · 중병기=짓뭉갬)
    v2 = v2_of(cfg)
    profile = " · ".join(
        f"{w} {v2['chance_by_weapon'].get(w, 0) * 100:.0f}%/+{v2['damage_amp_by_weapon'].get(w, 0):g}"
        for w in ("단검", "검", "도", "중병기") if w in v2["chance_by_weapon"])
    rep.say(f"     v2 무기 개성 = 크리 표 (확률/배수 증강): {profile} — 계열의 결이 격 아래에서 산다")


def sim_weapon_grades(cfg, rep, max_rounds):
    rep.head("무기 등급의 값 — 판정 보정 0~1 vs 무기 파괴")
    we = dig(cfg, "equipment.yml", "weapon_grades", default={}) or {}
    breaks_at = int(num(dig(cfg, "qi_manifestation.yml", "weapon_break", "rule", "1격_초과", "breaks_at"), 3))
    trigger = str(dig(cfg, "qi_manifestation.yml", "weapon_break", "trigger", default=""))

    p = Fighter(cfg, "절정 무인", "절정", weapon="검")
    e = Fighter(cfg, "절정 고수", "절정", weapon="검", is_npc=True)

    if v2_of(cfg):
        _grades_judgment_v2(cfg, rep, p, e)
        return _weapon_break_tail(cfg, rep, p, e, breaks_at, trigger, max_rounds)

    # ★ 등급별 보정을 **글자로 박지 말라.** 예전엔 "(범철 0 · 정련 0 · 보병 +1 · 신병 +1)" 이라 적혀 있었는데
    #   config 는 이미 전부 0 으로 내려간 뒤였다 (equipment.yml 2026-07 전투 정합 패스).
    #   그래서 검수는 **없는 죄를 없는 이름으로** 짖었고, 고칠 곳을 찾으러 가면 이미 고쳐져 있었다.
    #   등록부가 정본이다 — 검수도 등록부에서 읽는다.
    _grades = dig(cfg, "equipment.yml", "weapon_grades", default={}) or {}
    _label = " · ".join(f"{g} {int(num((sp or {}).get('judgment_bonus'), 0)):+d}"
                        for g, sp in _grades.items())
    rep.say(f"     [A] 판정 보정만 봤을 때 ({_label})")
    rep.say("       등급    보정   명중률   피해/합    TTK    피해 Δ")
    base = None
    for g, spec in we.items():
        b = int(num((spec or {}).get("judgment_bonus"), 0))
        h, d, _ = strike(p, e, mod=b)
        t, _, _ = duel(p, e, max_rounds, a_mod=b)
        if base is None:
            base = d
        delta = (d - base) / base * 100 if base else 0
        rep.say(f"       {g:<6} {b:>+4}   {h * 100:5.1f}%  {d:6.2f}   {t:>2}합   {delta:>+6.1f}%")
    # 판정 +1 이 피해를 얼마나 올리는가 — **그 +1 을 지금 누가 주는가**를 함께 말해야 한다.
    #   등급이 전부 0 이면 남는 출처는 애병(손에_익다)뿐이다. 이름을 대야 고칠 곳을 안다.
    h1, d1, _ = strike(p, e, mod=1)
    h0, d0, _ = strike(p, e, mod=0)
    dmg_delta = (d1 - d0) / d0 * 100 if d0 else 0
    _givers = [g for g, sp in _grades.items() if int(num((sp or {}).get("judgment_bonus"), 0)) > 0]
    _who = " · ".join(_givers) if _givers else "등급은 아무도 안 준다 — 남은 출처는 애병(손에_익다 +1)뿐"
    _qi = dig(cfg, "combat.yml", "damage", "qi_power", default={}) or {}
    _gang = int(num(_qi.get("강기"), 0))
    # ★ 묻기만 하면 안 된다 — **재야 한다.** "판정 한 칸이 격 한 단계보다 무거운가"는 대답 가능한 질문이다.
    #   같은 잣대(기대 피해)로 나란히 놓는다: 판정 +1 의 증분 vs 격 한 단계(외공기→발경)의 증분.
    #   이 세계의 대원칙은 **위력의 축이 격**이라는 것이다 (combat.yml: "위력의 축은 여전히 격이다").
    #   장비가 격을 이기면 그 축이 뒤집힌다 — 무협이 아니라 장비 게임이 된다.
    _step = int(num(_qi.get("발경"), 1)) - int(num(_qi.get("외공기"), 0))   # 격 한 단계의 값
    _, d_qi, _ = strike(p, e, qi_power=_step)
    qi_delta = (d_qi - d0) / d0 * 100 if d0 else 0
    # ★ 한 칸씩 견주는 것은 **틀린 잣대**다. 판정에는 **상한**이 있고(장비 총합 caps.equipment_judgment_bonus_total),
    #   격은 심검(5)까지 오른다. "한 칸이 더 무겁다"는 사실이어도, 그것만으로 축이 뒤집히지는 않는다 —
    #   축이 뒤집히는 것은 **끝까지 갔을 때 장비가 격을 이길 때**다. 그러므로 천장끼리 견준다.
    #   (한 칸 비교도 함께 적는다. 사실이고, 애병의 무게를 아는 데 쓸모가 있다.)
    _cap = int(num(dig(cfg, "equipment.yml", "caps", "equipment_judgment_bonus_total"), 2))
    _top = max((int(num(v, 0)) for v in _qi.values()), default=0)   # 심검
    _, d_capped, _ = strike(p, e, mod=_cap)
    _, d_top, _ = strike(p, e, qi_power=_top)
    cap_delta = (d_capped - d0) / d0 * 100 if d0 else 0
    top_delta = (d_top - d0) / d0 * 100 if d0 else 0
    rep.say(f"     한 칸: 판정 +1 = {dmg_delta:+.1f}%   ·   격 1단계(+{_step}) = {qi_delta:+.1f}%"
            f"   → 판정 한 칸이 더 무겁다 (사실이다)")
    rep.say(f"     천장: 장비 판정 최대(+{_cap}) = {cap_delta:+.1f}%   ·   격 최대(심검 +{_top}) = {top_delta:+.1f}%")
    if cap_delta >= top_delta:
        rep.warn(f"**장비의 천장이 격의 천장을 이긴다** (판정 +{_cap} → {cap_delta:+.1f}% ≥ 심검 → {top_delta:+.1f}%). "
                 f"combat.yml 은 '위력의 축은 격'이라 못박았는데 그 축이 장비로 뒤집혔다. "
                 f"+1 을 주는 자: {_who}")
    else:
        rep.say(f"     ✅ 위력의 축은 격이다 — 장비는 천장이 낮다 ({cap_delta:+.1f}% < {top_delta:+.1f}%). "
                f"판정 한 칸이 무거운 것은 **애병을 귀하게 만드는 것**이지 축을 뒤집는 것이 아니다 "
                f"(판정 출처: {_who})")

    _weapon_break_tail(cfg, rep, p, e, breaks_at, trigger, max_rounds)


def _weapon_break_tail(cfg, rep, p, e, breaks_at, trigger, max_rounds):
    """[B] 무기 파괴 + 회피 자원 — v1/v2 공통 꼬리 (피해 산술은 strike 가 알아서 갈아탄다)."""
    rep.say("")
    rep.say(f"     [B] 무기 파괴 — 검기(절정) 상대로 '막기'를 고른다면 (trigger: {trigger})")
    rep.say(f"       범철(감당 발경) vs 검기 = 1격 초과 → {breaks_at}격돌째 파괴")
    rep.say(f"       정련(감당 검기) vs 검기 = 감당 이상 → 손상 0")
    rep.say("")
    # 범철이 부러진 뒤: 무기 위력 검(3) → 맨손(1), 무공 다운캐스트 → 무공 위력 0, 재무장 = 행동 1개(1합 손실)
    broken = Fighter(cfg, "절정(파검)", "절정", weapon="맨손")
    broken.tpower = 0.0     # after_break: 무기 요구 무공 다운캐스트 = 무공 위력 보정 상실 (v1 의 항)
    _, d_ok, _ = strike(p, e)
    _, d_br, _ = strike(broken, e)
    loss = (d_ok - d_br) / d_ok * 100 if d_ok else 0
    t_ok, _, _ = duel(p, e, max_rounds)
    t_br, td_br, _ = duel(broken, e, max_rounds)
    if v2_of(cfg):
        # v2 — 무공 위력 열이 없다 (공격력 4항에 그 항이 없다). 파검의 값 = 무기 위력 + 능력치 축 이사
        #   (검=민첩 → 맨손=근력 — 병기가 능력치를 정하므로 검 빌드의 손해가 위력표보다 클 수 있다)
        rep.say("       상태                        무기위력  피해/합    TTK")
        rep.say(f"       정련(안 부러짐)             {p.wpower:>6.0f}  {d_ok:>6.2f}   {t_ok:>2}합")
        rep.say(f"       범철({breaks_at}합째 파괴 → 맨손)   {broken.wpower:>6.0f}  "
                f"{d_br:>6.2f}   {t_br if t_br else '>' + str(max_rounds)}합")
    else:
        rep.say("       상태                        무기위력  무공위력  피해/합    TTK")
        rep.say(f"       정련(안 부러짐)             {p.wpower:>6.0f}  {p.tpower:>7.0f}  {d_ok:>6.2f}   {t_ok:>2}합")
        rep.say(f"       범철({breaks_at}합째 파괴 → 맨손)   {broken.wpower:>6.0f}  {broken.tpower:>7.0f}  "
                f"{d_br:>6.2f}   {t_br if t_br else '>' + str(max_rounds)}합")
    rep.say(f"       → 파검 시 피해 −{loss:.0f}% (+ 재무장 행동 1합 손실)"
            f"{', 상대가 ' + str(td_br) + '합에 먼저 끝낸다' if td_br and (not t_br or td_br <= t_br) else ''}")
    if loss >= 5:
        rep.ok(f"무기 파괴의 영향 −{loss:.0f}% ≥ 5% — 무기 등급은 '부러지지 않는 것'으로 값을 한다. "
               f"단, 그 값은 **막았을 때만** 발생한다")
    else:
        rep.fail(f"무기 파괴의 영향 −{loss:.0f}% < 5% — 무기 등급이 장식이다")

    rep.say("")
    # 무기 파괴는 접촉(가드·패링·합)에만 걸린다 — 회피는 접촉이 없다. 그러면 "늘 회피"가 답인가?
    #   답은 **회피가 자원인가**에 달렸다. 무한 회피면 막기는 죽은 선택지고, 유한하면 선택이 산다.
    #   구판은 이걸 묻지 않고 "막기는 죽었다"고 박아 두었다 — config 를 읽지 않는 결론이었다.
    charges = num(dig(cfg, "skill_mechanics.yml", "dodge", "charges"), 0)
    recharge = num(dig(cfg, "skill_mechanics.yml", "dodge", "recharge_ticks"), 0)
    swings_per_sec = 1.6   # 검 표준 공속 (equipment.yml 계열 공속 — 검 1.6/s)
    rep.say(f"     회피 = 충전 {charges:g}회 · 회복 {recharge:g}틱({recharge / 20:.1f}초) "
            f"→ 회복 한 주기에 회피 {charges:g}회, 그 사이 상대는 {recharge / 20 * swings_per_sec:.1f}타를 낸다")
    if not charges or not recharge:
        rep.fail("회피에 충전·회복이 없다 = 무한 회피. 그러면 상위 격 앞에서 '막기'는 죽은 선택지다 "
                 "(무기 파괴는 접촉에만 걸리므로 안 막으면 무기가 안 부러진다)")
    else:
        covered = charges / max(1e-9, recharge / 20 * swings_per_sec)
        if covered >= 1.0:
            rep.fail(f"회피가 들어오는 타격의 {covered * 100:.0f}% 를 덮는다 = 사실상 무한 회피 — "
                     f"막기가 죽은 선택지다")
        else:
            rep.ok(f"회피는 들어오는 타격의 {covered * 100:.0f}% 만 덮는다 — 나머지는 막거나 맞는다. "
                   f"'상위 격은 피하고 하위 격은 막는다'가 성립한다 (무기 파괴는 그 선택의 값이다)")


def sim_gangup(cfg, rep, max_rounds):
    rep.head("포위 — 다구리는 어떻게 계산되는가 (슬롯 · 피포위 방어 · 강제 태세 · 전의)")
    rules = gang_rules(cfg)
    per, cap, slots = rules["per"], rules["cap"], rules["slots"]
    party_cap = int(num(dig(cfg, "party.yml", "combat_coop", "협공_보정", "cap"), 2))
    jin_cap = int(num(dig(cfg, "party.yml", "combat_coop", "합격진", "example_maehwa_geomjin",
                          "effect", "협공_상한"), 4))
    jin_slots = int(num(dig(cfg, "party.yml", "combat_coop", "합격진", "example_maehwa_geomjin",
                           "effect", "포위_슬롯"), 5))

    p = Fighter(cfg, "이류 무인", "이류", weapon="검")
    e = Fighter(cfg, "이류 무인", "이류", weapon="검", is_npc=True)      # 동수
    hi = Fighter(cfg, "절정 고수", "절정", weapon="검", is_npc=True)     # 격상
    v2 = v2_of(cfg)
    rep.say(f"     협공 보정 {per:+d}/추가 인원(캡 {cap}) · 포위 슬롯 {slots} · "
            f"피포위 방어 {rules['def_per']:+d}/추가 인원(캡 {rules['def_cap']}) · "
            f"강제 태세(회피 상실 → 흘리기) 경감 −{rules['guard_soak']:g}")
    if v2:
        rep.say("     ★v2 — 협공·피포위 판정 보정은 실릴 판정이 없다. 머릿수의 값 = 슬롯(동시에 드는 손)")
        rep.say(f"       + 강제 태세(회피 봉쇄 → 흘리기 경감이 방어력에 든다) — 손은 {slots}개가 상한이다")
    else:
        rep.say(f"     → 순보정 = 협공 − 피포위 방어 = 0 (같은 눈금). 머릿수의 값은 '더 잘 맞히는 것'이 아니라")
        rep.say(f"       '더 많은 손이 동시에 들어가는 것'이다 — 그리고 그 손은 {slots}개가 상한이다")
    rep.say(f"     기준: 동수 표적(이류 NPC, 내구 {e.dur}) — 공격자 이류 무인 N인")
    rep.say("")
    rep.say("       인원  슬롯  순보정  경감   1인 명중률  1인 피해/합  총 피해/합   1인 대비   표적 TTK")
    totals = {}
    for n in (1, 2, 3, 4, 5, 6):
        k = engaged(rules, n)
        mod, soak = net_mod(rules, k), guard_soak(rules, k)
        h, d, _ = strike(p, e, mod=mod, soak=soak)
        total = d * k
        totals[n] = total
        t, _, _ = duel(p, e, max_rounds, a_mod=mod, a_attacks=k, a_soak=soak)
        ratio = total / totals[1] if totals[1] else 0
        rep.say(f"       {n:>3}인  {k:>3}   {mod:>+5}  {-soak:>+4}   {h * 100:8.1f}%  {d:>10.2f}  "
                f"{total:>10.2f}   {ratio:>6.2f}배   {str(t) + '합' if t else '>' + str(max_rounds) + '합':>7}")

    # ① 초선형 금지 — 협공의 이득이 머릿수를 넘어서면 안 된다
    rep.say("")
    ratio3 = totals[3] / totals[1] if totals[1] else 0
    if ratio3 > 3.3:
        rep.fail(f"3인 협공이 동수 상대에게 1인의 {ratio3:.2f}배 — 초선형(머릿수 3배를 넘는다). "
                 f"협공 보정이 판정 위에서 곱셈으로 터진다")
    else:
        rep.ok(f"3인 협공 = 1인의 {ratio3:.2f}배 — 선형 이하. "
               + ("판정 보정이 아예 없고(v2), 남는 것은 손의 수뿐이다" if v2 else
                  f"보정(+{min(2 * per, cap)})이 피포위 방어(+{min(2 * rules['def_per'], rules['def_cap'])})와 "
                  f"상쇄되고, 남는 것은 손의 수뿐이다"))

    # ①-b 단조성 — 둘이 덤비는 것이 하나보다 덜 아프면 규칙이 뒤집힌 것이다
    #      (이 검사가 '포위 강제 태세 = 막기(-3)' 안을 잡아냈다: 총 피해 3.28 → 3.06)
    dips = [n for n in range(2, 7) if totals[n] < totals[n - 1] - 1e-9]
    if dips:
        rep.fail(f"협공의 총 피해가 인원에 대해 단조가 아니다 — {dips[0]}인({totals[dips[0]]:.2f}/합)이 "
                 f"{dips[0] - 1}인({totals[dips[0] - 1]:.2f}/합)보다 약하다. 덤비면 손해인 규칙이다 "
                 f"(피포위 경감 −{rules['guard_soak']:g}이 협공 1인분보다 크다 — 경감은 1인분보다 작아야 한다)")
    else:
        rep.ok(f"협공 총 피해가 인원에 단조 증가 — 덤비면 언제나 이득이다 "
               f"(1인 {totals[1]:.2f} → 2인 {totals[2]:.2f} → 3인 {totals[3]:.2f}/합)")

    # ② 슬롯 상한 — 6인이 6배가 되지 않는다
    if totals[6] > totals[slots] * 1.001:
        rep.fail(f"슬롯({slots})이 물지 않는다 — 6인 총 피해 {totals[6]:.2f} > {slots}인 {totals[slots]:.2f}. "
                 f"머릿수가 피해에 선형으로 쌓인다 (한 사람을 동시에 칠 수 있는 자리는 한정돼 있어야 한다)")
    else:
        rep.ok(f"포위 슬롯 {slots} 이 물린다 — {slots}인({totals[slots]:.2f}/합)과 6인({totals[6]:.2f}/합)이 같다. "
               f"{slots + 1}인째부터는 대기(포위) — 값은 피해가 아니라 도주 봉쇄에 있다")

    # ③ 포위된 자에게 창이 남는가 — 3합은 있어야 도주 판정도, 두목 격파도, 전의 붕괴도 시도한다
    k3 = engaged(rules, 3)
    t3, _, _ = duel(p, e, max_rounds, a_mod=net_mod(rules, k3), a_attacks=k3,
                    a_soak=guard_soak(rules, k3))
    if t3 is not None and t3 < 3:
        rep.fail(f"3인 협공이 동수 표적을 {t3}합에 눕힌다 — TTK 3합 미만 = 전투가 없다. "
                 f"포위된 자에게 한 합의 창도 남지 않는다 (도주·두목 격파·전의 붕괴를 시도할 시간이 없다)")
    else:
        rep.ok(f"3인 협공 동수 표적 TTK {t3}합 — 포위된 자에게 창이 남는다 "
               f"(피포위 방어 +{min(2 * rules['def_per'], rules['def_cap'])} · 강제 흘리기 −{rules['guard_soak']:g})")

    # ④ 격상 표적 — 명중률 절벽 위에서 보정이 곱셈으로 터지는가
    rep.say("")
    rep.say(f"     [격상 표적] 이류 → 절정 고수(내구 {hi.dur}) — '고수가 다수를 상대로 사는 이유'")
    rep.say("       인원  슬롯  순보정   1인 명중률   총 피해/합   1인 대비")
    hi_solo = None
    hi_mod_max = 0
    for n in (1, 3, 4, 5):
        k = engaged(rules, n)
        mod, soak = net_mod(rules, k), guard_soak(rules, k)
        hi_mod_max = max(hi_mod_max, mod)
        h, d, _ = strike(p, hi, mod=mod, soak=soak)
        if hi_solo is None:
            hi_solo = d * k
        rep.say(f"       {n:>3}인  {k:>3}   {mod:>+5}   {h * 100:9.1f}%   {d * k:>10.2f}   "
                f"{(d * k) / hi_solo if hi_solo else 0:>6.2f}배")
    if v2:
        # v2 — 명중률 절벽이 없다 (명중은 획). 격상이 사는 길 = 방어력(체력 파생)이 공격력을 깔아
        #   피해가 하한(1×크리 기대)에 눌리는 것 + 내구. 다수의 이득은 어디까지나 슬롯이다.
        _, d_hi1, _ = strike(p, hi)
        floor_hit = d_hi1 <= 1.0 * (1 + 0.5) + 1e-9   # 하한 1 × 크리 기대(넉넉히) 근방인가 — 표시용
        rep.ok(f"v2 — 격상 표적에 판정 보정이 없다 (실릴 판정이 없다). 이류의 한 손 = {d_hi1:.2f}/합"
               f"{' (방어력이 공격력을 깔아 하한 근방)' if floor_hit else ''} — "
               f"다수의 이득은 슬롯({slots}배)까지다. 고수는 방어력과 내구로 산다")
    elif hi_mod_max > 0:
        rep.fail(f"격상 표적에 순보정 {hi_mod_max:+d} 가 남는다 — 이류의 절정 상대 명중률은 "
                 f"{strike(p, hi)[0] * 100:.1f}%(2d6 최대치에서만 마진 ≥0)인 절벽 위다. 그 위의 +1 은 "
                 f"명중률을 배로 만든다 (선형이 아니라 곱셈)")
    else:
        rep.ok(f"격상 표적의 순보정 0 — 명중률 절벽이 머릿수로 무너지지 않는다 "
               f"({strike(p, hi)[0] * 100:.1f}% 그대로). 다수의 이득은 슬롯({slots}배)까지다 — "
               f"고수는 등을 내주지 않는 한 산다")

    # ⑤ 실전 — 산길 도적 매복 (전의를 굴린다: 붕괴한 졸개는 물러난다)
    npcs = dig(cfg, "npcs/cheongha_npcs.yml", "npcs", default={}) or {}
    bs = (npcs.get("north_road_bandit") or {}).get("stats") or {}
    prof = dig(cfg, "npc_combat.yml", "morale", "gauge", "start_by_tier", default={}) or {}
    grunt_start = int(num(prof.get("졸개"), 5))

    def bandits(n):
        return [{"fighter": Fighter(cfg, "산길 도적", "삼류", weapon="도", is_npc=True,
                                    skill=num(bs.get("도법"), 1), stats=bs),
                 "morale_start": grunt_start, "morale_break": 3} for _ in range(n)]

    hero2 = Fighter(cfg, "이류 무인", "이류", weapon="검")
    hero1 = Fighter(cfg, "일류 무인", "일류", weapon="검", naegong=1.0)
    b1 = bandits(1)[0]["fighter"]
    rep.say("")
    rep.say("     [실전] 산길 도적 매복 (npcs count_hint 4~6명) — 전의(戰意)를 굴린다")
    rep.say(f"       졸개 전의 {grunt_start} 시작 · 붕괴 문턱 3 · 매 합 재계산 (내구·아군 수·두목 생사)")
    rep.say("")
    rep.say("       상대            인원  슬롯  순보정  경감   피격/합   결말      합수   사망/이탈")
    outs = {}
    for who, hero in (("이류 무인", hero2), ("일류 무인", hero1)):
        for n in (1, 5):
            r = melee(cfg, hero, bandits(n), max_rounds)
            k = engaged(rules, n)
            _, db, _ = strike(b1, hero, mod=net_mod(rules, k), soak=guard_soak(rules, k))
            outs[(who, n)] = r
            end = r["down_at"] or r["clear_at"] or r["rounds"]
            rep.say(f"       {who:<14} {n:>3}인  {k:>3}   {r["net_mod"]:>+5}  {-r["soak"]:>+4}   "
                    f"{db * k:>7.2f}   {r['outcome']:<8} {end:>4}합   {r['killed']}/{r['routed']}")

    m2 = outs[("이류 무인", 5)]
    m1 = outs[("일류 무인", 5)]
    solo = strike(b1, hero2, mod=net_mod(rules, 1), soak=guard_soak(rules, 1))[1]
    five = strike(b1, hero2, mod=m2["net_mod"], soak=m2["soak"])[1] * m2["slots"]
    survive2 = m2["down_at"] or m2["rounds"]

    rep.say("")
    # ⓐ 포위된 자에게 창이 있는가 (도주·두목 격파·전의 붕괴)
    if survive2 < 3:
        rep.fail(f"도적 5인 매복이 이류 무인을 {survive2}합에 눕힌다 — 3합 미만. 도주 판정도, 두목을 베는 수도, "
                 f"전의를 꺾을 시간도 없다. 매복은 전투가 아니라 처형이다")
    else:
        rep.ok(f"도적 5인 매복 — 이류 무인은 {survive2}합을 버틴다({m2['outcome']}, {m2['killed']}명 사살). "
               f"슬롯 {m2['slots']} · 순보정 {m2['net_mod']:+d}(상쇄) · 강제 흘리기 −{m2['soak']:g}. "
               f"도주(민첩+도주, 포위 -2)와 두목 격파(전의 -5 → 전원 붕괴)의 창이 있다")

    # ⓑ 머릿수가 무의미해도 안 된다 — 졸개 다섯은 여전히 무섭다 (비율이 아니라 결말로 잰다)
    if m2["outcome"] == "소탕":
        rep.fail(f"이류 무인 단신이 졸개 5인 매복을 소탕한다 — 머릿수가 무의미하다. "
                 f"슬롯·피포위 방어·경감이 과하다 (5인 {five:.2f}/합 = 1인 {solo:.2f}/합의 {five / solo:.1f}배뿐). "
                 f"졸개 다섯은 무서워야 한다")
    else:
        rep.ok(f"졸개 다섯은 여전히 무섭다 — 이류 무인 단신은 {m2['outcome']}한다 "
               f"({m2['killed']}명을 베고 {survive2}합에 무너진다). 5인 {five:.2f}/합 = "
               f"1인({solo:.2f}/합)의 {five / solo:.1f}배 — 슬롯이 물려도 머릿수는 무겁다")

    # ⓒ 경지가 머릿수의 답이다 — 고수가 다수를 상대로 사는 이유가 규칙 안에 있는가
    if m1["outcome"] != "소탕":
        rep.fail(f"일류 무인(개화한 몸)조차 졸개 5인 매복을 소탕하지 못한다({m1['outcome']}) — "
                 f"'고수가 다수를 상대로 사는 이유'가 규칙 안에 없다. 경지가 머릿수에 아무 답도 주지 못한다")
    else:
        end1 = m1["clear_at"] or m1["rounds"]
        rep.ok(f"경지가 머릿수의 답이다 — 일류 무인은 같은 5인 매복을 {end1}합에 소탕한다"
               f"(사살 {m1['killed']} · 이탈 {m1['routed']}, 내구 {m1['hp_left']:.0f}/{hero1.dur} 잔존). "
               f"이류는 무너지고 일류는 벤다: 한 경지가 다섯 자루의 값이다")

    # ⑥ 합격진 — 진법의 값은 보정이 아니라 슬롯이다
    if jin_slots > slots:
        k_plain = engaged(rules, 5)
        k_jin = engaged(rules, 5, jin_slots)
        _, d_plain, _ = strike(p, e, mod=net_mod(rules, k_plain), soak=guard_soak(rules, k_plain))
        mod_jin = min(4 * per, jin_cap) - min(4 * rules["def_per"], rules["def_cap"])
        _, d_jin, _ = strike(p, e, mod=mod_jin, soak=guard_soak(rules, k_jin))
        rep.say("")
        rep.say(f"     매화검진(협공 상한 {jin_cap} · 포위 슬롯 {jin_slots}): 5인 총 피해 "
                f"{d_jin * k_jin:.2f}/합 vs 평협공 5인({d_plain * k_plain:.2f}/합) = "
                f"{(d_jin * k_jin) / (d_plain * k_plain) * 100 - 100:+.0f}%")
        rep.say(f"       → 진법이 사는 것은 보정이 아니라 **다섯이 동시에 벤다**는 것이다 "
                f"(슬롯 {slots} → {jin_slots}"
                + ("" if v2 else f", 순보정 {mod_jin:+d}") + "). 오합지졸은 셋까지다")


def sim_dead_options(cfg, rep):
    rep.head("죽은 선택지 — 아무도 쓰지 않을 무공")
    arts = dig(cfg, "skills.yml", "martial_arts", default={}) or {}
    mechs = dig(cfg, "skill_mechanics.yml", "skills", default={}) or {}
    _, axis = realm_axis(cfg)

    dead = []
    for sid, mech in sorted(mechs.items()):
        art = arts.get(sid)
        if not art:
            continue
        cost = skill_cost(mech)
        if cost <= 0:
            continue
        rr = art.get("required_realm")
        ng = axis.get(rr, {}).get("naegong", 0.0)
        p = pool_of(ng, cfg)
        tp = tech_power(cfg, art.get("grade"))
        reasons = []
        if p < cost:
            reasons.append(f"내력 풀 {p} < 코스트 {cost:g} (시전 불가)")
        if tp == 0 and not v2_of(cfg):
            # v2 는 무공 위력표를 아예 안 읽는다 (공격력 4항) — 등급의 값은 숙련 상한·격 개방에 있다
            reasons.append("무공 위력 0 (코스트만 있고 위력 보정 없음)")
        if reasons:
            dead.append((sid, art.get("name", sid), rr, reasons))

    rep.say("     기준: 요구 경지에서 시전 불가 / 코스트 대비 이득이 수치로 0")
    rep.say("")
    for sid, nm, rr, reasons in dead:
        rep.fail(f"{sid}({nm}, {rr}) — 죽은 선택지: {' · '.join(reasons)}")
    if not dead:
        rep.ok("액션 데이터를 가진 무공 중 죽은 선택지 없음")

    # 발경의 이득 — 수치로 묻는다 (구판은 "이득이 없다"고 박아 두었다. combat.yml damage.qi_power 가 생긴 뒤로 거짓이다)
    rep.say("")
    balgyeong = num(dig(cfg, "combat.yml", "damage", "qi_power", "발경"), 0)
    cost_balgyeong = num(dig(cfg, "internal_energy.yml", "cost_bands", "발경"), 1)
    if balgyeong <= 0:
        rep.fail("발경(코스트 1) — 이득이 config 어디에도 수치로 없다(피해 공식에 격 항 없음). "
                 "내력을 태우고 아무 것도 얻지 못한다면 합리적 플레이어는 발경을 쓰지 않는다")
    else:
        pw = Fighter(cfg, "일류 무인", "일류", weapon="검")
        ew = Fighter(cfg, "일류 무인", "일류", weapon="검", is_npc=True)
        _, base_d, _ = strike(pw, ew, qi_power=0.0)
        _, qi_d, _ = strike(pw, ew, qi_power=balgyeong)
        gain = (qi_d - base_d) / base_d * 100 if base_d else 0
        rep.ok(f"발경(코스트 {cost_balgyeong:g}) — 위력 +{balgyeong:g} → 피해 {base_d:.2f} → {qi_d:.2f} "
               f"({gain:+.1f}%). 내력을 태우면 아프다")


# ══════════════════════════════════════════════════════════════════════════════
#  눈을 시험하는 눈 (--selftest) — v2 기대 모델이 등록부를 정말 읽는가 (뮤테이션 프로브)
# ══════════════════════════════════════════════════════════════════════════════

def _probe_pair(cfg):
    """프로브의 표준 대결 한 쌍 — 절정 vs 절정 (atk ≫ def 라 하한 1 에 안 눌린다)."""
    return (Fighter(cfg, "무인", "절정", weapon="검"),
            Fighter(cfg, "고수", "절정", weapon="검", is_npc=True))


def selftest():
    """일부러 등록부를 비틀어 눈이 움직이는지 잰다 (헌법 2.3 — 시험 없는 눈은 눈이 아니다).

    각 프로브 = 깊은 복사한 config 한 곳을 비틀고, v2 기대 피해/크리가 따라 움직이는지 본다.
    안 움직이면 그 항은 하드코딩이거나 죽은 배선이다. 음성 대조(technique_power)는
    v2 가 그 항을 **안 읽어야** 통과다 — 읽으면 v1 산술이 새고 있는 것이다.
    """
    import copy

    base = load_all()
    keep = []                      # id() 재사용 방지 — 프로브 cfg 를 산 채로 붙잡는다 (_V2_CACHE 가 id 키)

    def fresh(mutate=None):
        cfg = copy.deepcopy(base)
        dig(cfg, "combat.yml", "combat_v2", default={})["enabled"] = True   # 프로브는 v2 산술을 겨눈다
        if mutate:
            mutate(cfg)
        keep.append(cfg)
        return cfg

    results = []

    def probe(name, ok, detail=""):
        results.append((name, ok))
        print(f"  {'✅' if ok else '❌'} {name}{' — ' + detail if detail else ''}")

    cfg0 = fresh()
    p0, e0 = _probe_pair(cfg0)
    h0, d0, c0 = strike(p0, e0)

    # ① v2 의 명중은 획 — 항상 1.0
    probe("① v2 명중 = 1.0 (판정 없음)", abs(h0 - 1.0) < 1e-12, f"hit={h0}")

    # ② per_body 를 비틀면 방어력이 움직인다
    cfg2 = fresh(lambda c: dig(c, "combat.yml", "combat_v2", "defense", default={})
                 .__setitem__("per_body", num(dig(c, "combat.yml", "combat_v2", "defense",
                                                  "per_body", default=0), 0) + 1.0))
    p2, e2 = _probe_pair(cfg2)
    d2 = strike(p2, e2)[1]
    probe("② per_body +1 → 기대 피해 감소 (방어력이 등록부를 읽는다)", d2 < d0 - 1e-9,
          f"{d0:.2f} → {d2:.2f}")

    # ③ damage_base 를 비틀면 크리 배수가 움직인다
    cfg3 = fresh(lambda c: dig(c, "combat.yml", "combat_v2", "crit", default={})
                 .__setitem__("damage_base", num(dig(c, "combat.yml", "combat_v2", "crit",
                                                     "damage_base", default=0), 0) + 1.0))
    p3, e3 = _probe_pair(cfg3)
    d3 = strike(p3, e3)[1]
    probe("③ crit damage_base +1 → 기대 피해 증가 (크리 배수가 등록부를 읽는다)", d3 > d0 + 1e-9,
          f"{d0:.2f} → {d3:.2f}")

    # ④ chance_by_weapon 을 비틀면 크리 확률이 움직인다
    cfg4 = fresh(lambda c: dig(c, "combat.yml", "combat_v2", "crit", "chance_by_weapon", default={})
                 .__setitem__("검", 0.5))
    p4, e4 = _probe_pair(cfg4)
    c4 = strike(p4, e4)[2]
    probe("④ chance_by_weapon[검] 0.5 → 크리 확률 증가", c4 > c0 + 1e-9, f"{c0:.3f} → {c4:.3f}")

    # ⑤ 격(qi_power)이 공격력에 실린다
    d5 = strike(p0, e0, qi_power=2.0)[1]
    probe("⑤ 격 +2 → 기대 피해 증가 (격 보정이 공격력 4항에 실린다)", d5 > d0 + 1e-9,
          f"{d0:.2f} → {d5:.2f}")

    # ⑥ weapon_power 를 비틀면 공격력이 움직인다
    cfg6 = fresh(lambda c: dig(c, "combat.yml", "damage", "weapon_power", default={})
                 .__setitem__("검", num(dig(c, "combat.yml", "damage", "weapon_power",
                                            "검", default=4), 4) + 2))
    p6, e6 = _probe_pair(cfg6)
    d6 = strike(p6, e6)[1]
    probe("⑥ weapon_power[검] +2 → 기대 피해 증가", d6 > d0 + 1e-9, f"{d0:.2f} → {d6:.2f}")

    # ⑦ 병기가 능력치를 정한다 — 검의 축을 민첩 → 근력으로 옮기면 민첩 몰빵의 피해가 준다
    def move_axis(c):
        reg = dig(c, "combat.yml", "attack", "attacker_attribute", default={})
        if isinstance(reg.get("민첩"), list) and "검" in reg["민첩"]:
            reg["민첩"] = [w for w in reg["민첩"] if w != "검"]
        if isinstance(reg.get("근력"), list):
            reg["근력"] = reg["근력"] + ["검"]
    stats = {"민첩": 9, "근력": 2, "체력": 4, "감각": 4, "지혜": 4}
    pa = Fighter(cfg0, "쾌검수", "절정", weapon="검", stats=stats)
    cfg7 = fresh(move_axis)
    pb = Fighter(cfg7, "쾌검수", "절정", weapon="검", stats=stats)
    e7 = Fighter(cfg7, "고수", "절정", weapon="검", is_npc=True)
    da = strike(pa, e0)[1]
    db = strike(pb, e7)[1]
    probe("⑦ 공격 축 이사(검: 민첩→근력) → 민첩 몰빵 피해 감소 (병기가 능력치를 정한다)",
          db < da - 1e-9, f"{da:.2f} → {db:.2f}")

    # ⑧ enabled: false → v1(2d6) 복귀 — 명중이 1.0 미만으로 떨어진다 (복귀 스위치의 실존)
    cfg8 = fresh(lambda c: dig(c, "combat.yml", "combat_v2", default={})
                 .__setitem__("enabled", False))
    p8, e8 = _probe_pair(cfg8)
    h8 = strike(p8, e8)[0]
    probe("⑧ enabled:false → v1 산술 복귀 (명중 < 1.0)", h8 < 1.0 - 1e-9, f"hit={h8:.3f}")

    # ⑨ 음성 대조 — technique_power 를 비틀어도 v2 피해는 그대로다 (v2 는 무공 위력표를 안 읽는다)
    cfg9 = fresh(lambda c: dig(c, "combat.yml", "damage", "technique_power", default={})
                 .__setitem__("절정급", 99))
    p9, e9 = _probe_pair(cfg9)
    d9 = strike(p9, e9)[1]
    probe("⑨ 음성 대조: technique_power 99 → v2 피해 불변 (무공 위력표는 v1 의 것)",
          abs(d9 - d0) < 1e-9, f"{d0:.2f} → {d9:.2f}")

    # ══════════════════════════════════════════════════════════════════════
    #  사다리 눈(chain-walk) 뮤테이션 — B-188 닫는 조건 ③ (2026-07-26)
    #  등록부를 일부러 부러뜨리고 이 눈이 **소리를 내는지** 잰다.
    #  ★기준선이 0 이 아니어도 된다 (공중시작 경고가 이미 있다) — 그래서 절대값이 아니라
    #    **기준선 대비 증가**를 잰다. 그러지 않으면 프로브가 남의 구멍에 얹혀 통과한다.
    # ══════════════════════════════════════════════════════════════════════
    def chain_run(cfg):
        r = Report()
        lint_ladder_chain(cfg, r)
        return r.violations, r.warnings

    v_base, w_base = chain_run(fresh())

    def chain_probe(name, mutate, expect_violation=True, needle=None):
        """★건수가 아니라 **내용**을 잰다.

        공중시작·tier 미기재처럼 여러 건을 **한 줄로 묶어 내는** 항목은 대상이 하나 늘어도
        경고 '건수'가 그대로다 — 건수만 보는 프로브는 그 자리에서 조용히 통과한다
        (⑬ 이 실제로 그렇게 통과할 뻔했다). 그래서 기준선에 **없던 문자열이 생겼는가**를 본다.
        """
        v, w = chain_run(fresh(mutate))
        new_txt = " ".join(v if expect_violation else w)
        base_txt = " ".join(v_base if expect_violation else w_base)
        ok = (needle in new_txt) and (needle not in base_txt) if needle else \
             (len(v) > len(v_base) if expect_violation else len(w) > len(w_base))
        probe(name, ok, f"위반 {len(v_base)}→{len(v)} · 경고 {len(w_base)}→{len(w)}"
                        f"{' · 새 표식 ' + repr(needle) if needle else ''}")

    def arts_of(c):
        return dig(c, "skills.yml", "martial_arts", default={}) or {}

    # ⑩ 선행이 허공을 가리키면 잡는가
    chain_probe("⑩ 선행 id 를 없는 것으로 → 허공 참조 위반",
                lambda c: arts_of(c)["banya_jang"].__setitem__(
                    "requires_skill", {"id": "___없는무공___", "mastery": 5}),
                needle="허공")

    # ⑪ 고리를 만들면 잡는가 (A→B→A — 아무도 첫 칸에 닿지 못한다)
    def make_cycle(c):
        a = arts_of(c)
        a["wita_jang"]["requires_skill"] = {"id": "banya_jang", "mastery": 5}
    chain_probe("⑪ 선행 고리(위타장↔반야장) → 고리 위반", make_cycle, needle="고리")

    # ⑫ 사다리를 뒤집으면 잡는가 (하급이 상급을 선행으로 문다)
    chain_probe("⑫ 사다리 역행(위타장 ← 반야장 tier 조작) → 역행 위반",
                lambda c: arts_of(c)["nahan_sippaljang"].__setitem__("tier", "하급"),
                needle="역행")

    # ⑬ 중급의 선행을 지우면 공중시작 **경고가 는다**
    chain_probe("⑬ 중급 무공의 선행 삭제 → 공중시작 목록에 그 이름이 뜬다",
                lambda c: arts_of(c)["nahan_sippaljang"].pop("requires_skill", None),
                expect_violation=False, needle="nahan_sippaljang")

    # ⑭ 오의의 발판이 사라지면 잡는가
    chain_probe("⑭ 오의 선행 id 를 없는 것으로 → 오의 위반",
                lambda c: (dig(c, "ultimate_arts.yml", "legacy_arts", default={})
                           ["baekbo_singwon"].__setitem__("requires_skill",
                                                          {"id": "___없는무공___", "mastery": 8})),
                needle="오의")

    # ⑮ ★묘비 — 이 눈은 처음 세울 때 **키를 헛짚어 오의 0종에 '전부 통과'를 내줬다**.
    #    빈 등록부에 합격을 주는 눈은 눈이 아니다. 그 버그가 돌아오면 여기서 걸린다.
    chain_probe("⑮ 묘비: 오의 등록부를 비우면 → 합격이 아니라 위반 (빈 등록부 거짓 합격 금지)",
                lambda c: dig(c, "ultimate_arts.yml", default={}).__setitem__("legacy_arts", {}),
                needle="비었다")

    # ⑰ 심법 문턱 — 가리키는 심법이 사라지면 잡는가 (칠보독장: "독은 손이 아니라 몸으로 배운다")
    chain_probe("⑰ 심법 문턱 id 를 없는 것으로 → 심법 허공 위반",
                lambda c: arts_of(c)["chilbo_dokjang"].__setitem__(
                    "requires_simbeop", {"id": "___없는심법___"}),
                needle="심법 문턱이 허공")

    # ⑱ 묘비 — 심법 문턱을 **못 읽던 때** 이 눈은 칠보독장을 「공중에서 시작」으로 셌다.
    #    없는 구멍을 지어내는 눈은 구멍을 놓치는 눈만큼 나쁘다. 문턱을 지우면 그때서야 공중시작이다
    chain_probe("⑱ 묘비: 심법 문턱을 지워야 비로소 공중시작 (있을 땐 세지 않는다)",
                lambda c: arts_of(c)["chilbo_dokjang"].pop("requires_simbeop", None),
                expect_violation=False, needle="chilbo_dokjang")

    # ══════════════════════════════════════════════════════════════════════
    #  세력 백지 지도 뮤테이션 (⑲~㉒) — B-188 닫는 조건 ④
    # ══════════════════════════════════════════════════════════════════════
    def fac_run(cfg):
        r = Report()
        lint_faction_coverage(cfg, r)
        return r.violations, r.warnings

    fv_base, fw_base = fac_run(fresh())

    def fac_probe(name, mutate, needle, in_violation=False, absent=False):
        v, w = fac_run(fresh(mutate))
        txt = " ".join(v if in_violation else w)
        base = " ".join(fv_base if in_violation else fw_base)
        ok = (needle not in txt) and (needle in base) if absent else \
             (needle in txt) and (needle not in base)
        probe(name, ok, f"위반 {len(fv_base)}→{len(v)} · 경고 {len(fw_base)}→{len(w)}")

    # ⑲ 어떤 세력의 무공을 전부 지우면 「배울 것이 없다」 목록에 그 이름이 뜬다
    #    ★대상은 **제 입문 루트가 있는** 세력이어야 한다 — 이 눈의 축이 「무공 0」이 아니라
    #      「문이 열렸는데 무공 0」이기 때문이다. 이 프로브가 두 번 실패하며 알려 준 것:
    #      개방에도 **소림에도** 입문 경로가 없다. 루트는 16개뿐이고 정파는 **화산·당가 둘**이다
    def strip_hwasan(c):
        a = dig(c, "skills.yml", "martial_arts", default={})
        for k in [k for k, v in a.items() if isinstance(v, dict) and v.get("faction") == "hwasan"]:
            del a[k]
    fac_probe("⑲ 화산 무공 전부 삭제 → 백지 목록에 화산이 뜬다 (제 루트가 있는 세력)",
              strip_hwasan, "hwasan")

    # ⑳ ★묘비 — 등록부는 세력을 **한글 이름으로도** 가리킨다 (magyo_encroachment 의 `faction: 마교`).
    #    id 만 보던 첫 판은 마교를 놓쳐 「9곳」이라 답했다. 이름 대조가 죽으면 다시 놓친다.
    #    이것은 뮤테이션이 아니라 **기준선 자체에 대한 단언**이다 (놓치면 조용히 사라지는 종류라서)
    probe("⑳ 묘비: 입문 경로의 한글 이름 대조 — 마교가 백지 목록에 있다",
          any("magyo" in x for x in fw_base),
          "faction: 마교 (id 아님) 로 적힌 경로를 읽는가")

    # ⑳-2 ★입문 폐쇄(player_entry:false)를 열면 그 세력이 「배울 것이 없다」로 넘어온다
    #      — 폐쇄가 실제로 판정을 바꾸는지 (플래그가 장식이 아닌지) 재는 프로브
    def reopen_saeoe(c):
        r = dig(c, "faction_entry_routes.yml", "routes", default={})
        # ★milgyo_entry 만 열면 된다 — saeoe_common 은 kind:공통_규칙 이라 제 faction 이 없어서
        #   판정에 직접 참여하지 않는다 (그 절의 폐쇄 표시는 사람이 읽는 헌장이다)
        if isinstance(r.get("milgyo_entry"), dict):
            r["milgyo_entry"].pop("player_entry", None)
    fac_probe("⑳-2 새외 입문 폐쇄를 풀면 → 설역 밀교가 「배울 것 없다」로 넘어온다",
              reopen_saeoe, "seolyeok_milgyo")

    # ⑳-3 ★묘비 — 유보 칸이 새외 **여섯 전부**를 담는가 (기준선 단언).
    #      폐쇄를 하위로 전파하지 않으면 `gates[].condition.favor.faction` 이 그 세력을 다시
    #      열린 문으로 올린다 — 실제로 그렇게 새어 여섯 중 다섯이 잘못 셌다. 반대로 전파를
    #      npc_only 까지 밀면 주석이 언급한 소림·무당·모용이 유보로 잡힌다 (그 판도 실제로 겪었다).
    #      ★그래서 이 프로브는 **여섯이 다 있고 남이 없는지**를 함께 본다
    _rep = Report()
    lint_faction_coverage(fresh(), _rep)
    _npc_line = " ".join(l for l in _rep.lines if "NPC 전용" in l)
    probe("⑳-3 묘비: 유보 칸 = 새외 6곳 정확히 (다 있고, 남이 없다)",
          all(w in _npc_line for w in ("배화신교", "북막", "동영", "오독교", "설역 밀교", "서역 상맹"))
          and not any(w in _npc_line for w in ("소림", "무당", "모용", "곤륜", "해남")),
          _npc_line[:80] or "NPC 전용 줄이 없다")

    # ⑳-4 ★「무공 0 이 의도」 표시가 실제로 판정을 바꾸는가 (장식이 아닌지).
    #      표시를 지우면 무림맹이 세력 수에 다시 들어오고, 문이 열리면 거짓 경고가 시작된다
    def strip_design(c):
        g = dig(c, "factions.yml", "faction_groups", default={})

        def walk(n):
            if isinstance(n, dict):
                n.pop("no_arts_by_design", None)
                for v in n.values():
                    walk(v)
            elif isinstance(n, list):
                for x in n:
                    walk(x)
        walk(g)
    _r0, _r1 = Report(), Report()
    lint_faction_coverage(fresh(), _r0)
    lint_faction_coverage(fresh(strip_design), _r1)
    _base_n = " ".join(l for l in _r0.lines if "1급 id" in l)
    _mut_n = " ".join(l for l in _r1.lines if "1급 id" in l)
    probe("⑳-4 「무공 0 이 의도」 표시를 지우면 → 세력 수가 늘어난다 (표시가 판정을 바꾼다)",
          "무공없음이_의도 1" in _base_n and "무공없음이_의도 0" in _mut_n
          and "세력 36" in _base_n and "세력 37" in _mut_n,
          f"기준선 「{_base_n.strip()[:44]}」")

    # ㉑ 컨테이너에 무공을 붙이면 오분류로 잡는가
    fac_probe("㉑ 연합(구파일방)에 무공을 붙이면 → 컨테이너 오분류 경고",
              lambda c: dig(c, "skills.yml", "martial_arts", default={})["nahan_kwon"]
              .__setitem__("faction", "gupailbang"),
              "구파일방")

    # ㉒ factions.yml 에 없는 세력을 가리키면 위반 (오타의 자리)
    fac_probe("㉒ 없는 세력을 가리키는 무공 → 위반",
              lambda c: dig(c, "skills.yml", "martial_arts", default={})["nahan_kwon"]
              .__setitem__("faction", "___없는세력___"),
              "___없는세력___", in_violation=True)

    # ㉓ 음성 대조 — 세력 등록부를 비우면 「백지 없음」이 아니라 **실패**여야 한다
    fv, _ = fac_run(fresh(lambda c: dig(c, "factions.yml", default={}).__setitem__("faction_groups", {})))
    probe("㉓ 묘비: 세력 등록부를 비우면 → 합격이 아니라 위반 (빈 등록부 거짓 합격 금지)",
          any("하나도 못 걷었다" in x for x in fv), f"위반 {len(fv_base)}→{len(fv)}")

    # ⑯ 음성 대조 — 사다리와 무관한 값(무기 위력)을 비틀어도 이 눈은 조용해야 한다
    v16, w16 = chain_run(fresh(lambda c: dig(c, "combat.yml", "damage", "weapon_power", default={})
                               .__setitem__("검", 99)))
    probe("⑯ 음성 대조: weapon_power 99 → 사다리 눈 불변 (수치는 길이 아니다)",
          len(v16) == len(v_base) and len(w16) == len(w_base),
          f"위반 {len(v_base)}→{len(v16)} · 경고 {len(w_base)}→{len(w16)}")

    bad = [n for n, ok in results if not ok]
    print()
    if bad:
        print(f"{FAIL} 눈의 시험 {len(results) - len(bad)}/{len(results)} — 실패: {bad}")
        return 1
    print(f"✅ 눈의 시험 {len(results)}/{len(results)} — v2 기대 모델이 등록부를 읽고, "
          f"사다리 눈이 부러진 길에 소리를 낸다")
    return 0


# ══════════════════════════════════════════════════════════════════════════════
#  진입점
# ══════════════════════════════════════════════════════════════════════════════

def main():
    ap = argparse.ArgumentParser(description="혼천 전투·무공 밸런스 검수")
    ap.add_argument("--lint-only", action="store_true", help="① 전투 정합 린트만")
    ap.add_argument("--sim-only", action="store_true", help="② 전투 시뮬만")
    ap.add_argument("--rounds", type=int, default=25, help="TTK 상한 합 수 (기본 25)")
    ap.add_argument("--selftest", action="store_true",
                    help="눈을 시험하는 눈 — v2 기대 모델 뮤테이션 프로브 (헌법 2.3)")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    rep = Report()
    rep.say("╔" + "═" * 70 + "╗")
    rep.say("║" + "  혼천 전투 감사 — combat_audit".ljust(69) + "║")
    rep.say("║" + "  무공과 합(合)을 재는 자 — 고치지 않는다".ljust(63) + "║")
    rep.say("╚" + "═" * 70 + "╝")

    try:
        cfg = load_all()
    except YamlError as e:
        print(f"{FAIL} config 파싱 실패: {e}", file=sys.stderr)
        return 2

    arts = dig(cfg, "skills.yml", "martial_arts", default={}) or {}
    mechs = dig(cfg, "skill_mechanics.yml", "skills", default={}) or {}
    rep.say(f"  config {len(cfg)}종 적재 · 무공 {len(arts)}종 · 액션 데이터 {len(mechs)}종")

    if not args.sim_only:
        lint(cfg, rep)
    if not args.lint_only:
        simulate(cfg, rep, args.rounds)

    rep.say()
    rep.say("═" * 72)
    n_v, n_w = len(rep.violations), len(rep.warnings)
    if n_v == 0 and n_w == 0:
        rep.say("  총평: ✅ 위반 0건 · 경고 0건 — 잴 수 있는 범위에서는 전투가 굴러간다")
    else:
        rep.say(f"  총평: 위반 {n_v}건 · 경고 {n_w}건")
        if n_v:
            rep.say("")
            rep.say(f"  ── 위반 ({FAIL}) — 전투가 자기모순이거나 굴러가지 않는다")
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
