#!/usr/bin/env python3
"""경공 감사 — 몸이 땅을 딛는 법의 눈과 자.

`growth_audit.py` 는 **빌드가 선택지인가**를 재고, `combat_audit.py` 는 **한 판이 몇 합인가**를 잰다.
이 도구는 세 번째 것을 묻는다: **몸이 가벼워지는 것이 정답이 되지 않는가.**

  경공(gyeonggong.yml)은 신법(발) 축에 '몸'을 달아 준다 — 속도·도약·낙법·벽·물.
  그 몸이 세계를 안 부수려면 넷이 참이어야 한다:

    ① **경지가 천장이다**      삼류가 지붕을 날면 안 된다. 개화 전 실효값은 0 이어야 한다.
    ② **공짜로 나는 몸이 없다** 어떤 심법·보법도 유지 코스트를 0 으로 만들면 안 된다.
                              그리고 경공은 **전투 자원(내력)을 먹는다** — 나는 자는 그만큼 못 태운다.
    ③ **지배 전략이 없다**      경공 몰빵(신법)이 지속·속도·높이 세 축을 다 1등 하면 그건 정답이다.
                              ★ 나는 데는 **연료(내공)** 가 든다 — 그래서 발만 판 자는 오래 못 난다.
    ④ **떨어지면 아프다**       내력이 끊긴 몸은 낙법이 안 듣는다 (qi.depleted.grace_multiplier = 0).
                              그 낙하가 내구의 절반을 못 깎으면 '극적인 자리'는 연출일 뿐이다.

전투·성장 수학(내력 풀 · 내구 · 빌드 적립)은 combat_audit / growth_audit 를 **그대로 재사용한다** —
같은 것을 두 번 구현하면 두 개의 진실이 생긴다. 이 도구가 새로 갖는 것은 **몸의 물리**뿐이다.

config 를 고치지 않는다 — 재기만 한다. 수치는 전부 config 에서 읽는다 (하드코딩 금지).

사용법:
    python3 tools/gyeonggong_audit.py                # 전체
    python3 tools/gyeonggong_audit.py --lint-only    # ① 등록 정합 린트만
    python3 tools/gyeonggong_audit.py --sim-only     # ② 몸 시뮬만
    python3 tools/gyeonggong_audit.py --budget 1800  # 수련 예산(일치) — 기본 1800 (= 5년 몰빵)

종료 코드: 위반(❌) 1건 이상이면 1, 아니면 0.
"""

from __future__ import annotations

import argparse
import math
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from game_audit import (  # noqa: E402  — 문법·출력 형식 계승 (읽기 전용 재사용)
    FAIL,
    ROOT,
    Report,
    YamlError,
    dig,
    load_all,
    num,
    realm_names,
)
from combat_audit import durability, pool_of  # noqa: E402  — 내구·내력 풀은 하나뿐이다
from growth_audit import standard_builds  # noqa: E402  — 빌드 적립은 성장 감사가 정본

GG = "gyeonggong.yml"


# ══════════════════════════════════════════════════════════════════════════════
#  config 판독 — 도구는 수치를 갖지 않는다
# ══════════════════════════════════════════════════════════════════════════════

def gg(cfg, *path, default=None):
    return dig(cfg, GG, *path, default=default)


def band(cfg):
    """이 모드가 무는 격 — 경신 (internal_energy.yml cost_bands)."""
    return str(gg(cfg, "mode", "band", default="경신"))


def qi_band_range(cfg):
    raw = dig(cfg, "internal_energy.yml", "cost_bands", band(cfg), "cost", default=[1, 2])
    if isinstance(raw, list) and len(raw) == 2:
        return int(num(raw[0], 1)), int(num(raw[1], 2))
    return int(num(raw, 1)), int(num(raw, 1))


def gated_realms(cfg):
    """'경신'이 열린 경지 — internal_energy.yml realm_gates 가 정본 (경공의 문은 저기서 연다)."""
    gates = dig(cfg, "internal_energy.yml", "realm_gates", default={}) or {}
    return [r for r, bands in gates.items() if isinstance(bands, list) and band(cfg) in bands]


def ceiling(cfg, realm):
    return gg(cfg, "realm_ceiling", realm, default={}) or {}


def per_agility(cfg, key):
    return num(gg(cfg, "growth", "per_agility", key), 0.0)


def attr_cap(cfg, realm):
    caps = dig(cfg, "player_creation.yml", "attribute_cap_by_realm", default={}) or {}
    return int(num(caps.get(realm), 3))


def mc(cfg, key, default=0.0):
    return num(gg(cfg, "mc", key), default)


def landmark(cfg, name):
    return num(gg(cfg, "audit", "landmarks", name), 0.0)


def contract(cfg, name, default=None):
    return gg(cfg, "audit", "contracts", name, default=default)


def bobeop_catalog(cfg):
    """보법 = skills.yml 의 category 경공. **도구도 목록을 갖지 않는다** (등록부가 정본)."""
    out = {}
    for sid, spec in (dig(cfg, "skills.yml", "martial_arts", default={}) or {}).items():
        if isinstance(spec, dict) and spec.get("category") == "경공":
            mech = dig(cfg, "skill_mechanics.yml", "skills", sid, default={}) or {}
            out[sid] = {
                "name": spec.get("name", sid),
                "faction": spec.get("faction", ""),
                "realm": spec.get("required_realm", ""),
                "distance": num(mech.get("distance"), 0.0),
                "vertical": bool(mech.get("vertical")),
                "irregular": mech.get("irregular") is not None,
            }
    return out


def sim_discount_from_prose(cfg, sid):
    """simbeop.yml qi_profile.연비 원문에서 경신 할인을 읽는다 — **정본은 저기다.**

    프로즈를 파싱하는 것은 위험하다. 그래서 엔진은 안 하고 **감사가 한다** —
    깨질 자리를 값싼 쪽(파이썬)에 둔다. 어긋나면 여기가 운다.
    """
    prose = str(dig(cfg, "simbeop.yml", "simbeop", sid, "qi_profile", "연비", default=""))
    if band(cfg) not in prose:
        return 0
    m = re.search(r"-\s*(\d+)", prose)
    return int(m.group(1)) if m else 0


def sim_fall_immune_from_prose(cfg, sid):
    passive = str(dig(cfg, "simbeop.yml", "simbeop", sid, "passive", default=""))
    return "낙하 피해 무효" in passive


# ══════════════════════════════════════════════════════════════════════════════
#  몸의 물리 — 등록부가 정한 산술 (Gyeonggong.java 와 같은 셈)
# ══════════════════════════════════════════════════════════════════════════════

def grow(cfg, key, agility, cap_value):
    """천장 안에서 자란다 — min(경지 천장, 민첩 × 눈금). Gyeonggong.grow 와 같은 식."""
    return min(cap_value, per_agility(cfg, key) * agility)


def profile(cfg, realm, agility, simbeop=None, bobeop=None, mastery=0):
    """이 몸의 경공 — 경지(천장) × 신법(성장) × 심법(연비) × 보법(발)."""
    c = ceiling(cfg, realm)
    if realm not in gated_realms(cfg):
        return {"open": False, "speed": 0.0, "jump": 0.0, "grace": 0.0,
                "wall": 0.0, "water": False, "interval": math.inf, "immune": False}
    eff = gg(cfg, "simbeop_effects", simbeop, default={}) if simbeop else {}
    disc = int(num((eff or {}).get("경신_할인"), 0))
    return {
        "open": True,
        "speed": grow(cfg, "speed_bonus", agility, num(c.get("speed_bonus"), 0)),
        "jump": grow(cfg, "jump_bonus", agility, num(c.get("jump_bonus"), 0)),
        "grace": grow(cfg, "fall_grace_m", agility, num(c.get("fall_grace_m"), 0)),
        "wall": grow(cfg, "wall_climb_m", agility, num(c.get("wall_climb_m"), 0))
                if (bobeop and bobeop.get("vertical")) else 0.0,
        "water": bool(c.get("water_run")),
        "interval": num(gg(cfg, "qi", "upkeep", "interval_ticks"), 20) * (1 + disc),
        "immune": bool((eff or {}).get("낙하_무효")),
    }


def jump_height(cfg, jump_bonus):
    """도달 높이 ≈ 1.25 × (1 + jump)² 블록 (gyeonggong.yml mc.jump_height_m)."""
    return mc(cfg, "jump_height_m", 1.25) * (1 + jump_bonus) ** 2


def flight_seconds(cfg, pool, prof, on_water=False):
    """이 내력으로 몇 초를 나는가 — 유지비 1 / interval 틱 (심법 할인이 interval 을 늘린다)."""
    if not prof["open"] or pool <= 0:
        return 0.0
    up = num(gg(cfg, "qi", "upkeep", "cost"), 1)
    if on_water:
        up *= num(gg(cfg, "qi", "water_upkeep_multiplier"), 2)
    ticks = (pool / up) * prof["interval"]
    return ticks / 20.0


def flight_meters(cfg, seconds, prof):
    return seconds * mc(cfg, "sprint_speed_mps", 5.6) * (1 + prof["speed"])


def fall_damage(cfg, height):
    """바닐라 낙하 피해 = 낙하 − 3 (내구와 같은 눈금 — Vitality 가 최대 체력을 내구로 세운다)."""
    return max(0.0, height - mc(cfg, "vanilla_safe_fall_m", 3))


# ══════════════════════════════════════════════════════════════════════════════
#  ① 등록 정합 린트 — 등록부끼리 어긋나면 세계가 두 개가 된다
# ══════════════════════════════════════════════════════════════════════════════

def lint(cfg, rep):
    lint_gate(cfg, rep)
    lint_qi(cfg, rep)
    lint_ceiling(cfg, rep)
    lint_bobeop(cfg, rep)
    lint_simbeop(cfg, rep)
    lint_armor(cfg, rep)
    lint_budget(cfg, rep)
    lint_activation(cfg, rep)


def lint_gate(cfg, rep):
    rep.head("① 경지의 문 — 경공을 여는 것은 internal_energy.yml 이다 (여기가 아니다)")
    gated = gated_realms(cfg)
    rep.say(f"     realm_gates 에 '{band(cfg)}'이 열린 경지: {', '.join(gated)}")

    nonzero = [r for r in realm_names(cfg)
               if any(num(ceiling(cfg, r).get(k), 0) > 0
                      for k in ("speed_bonus", "jump_bonus", "fall_grace_m", "wall_climb_m"))
               or ceiling(cfg, r).get("water_run")]
    rep.say(f"     realm_ceiling 이 0 이 아닌 경지:      {', '.join(nonzero)}")

    rep.verdict(set(gated) == set(nonzero),
                "경공의 문 = 경신의 문 — 두 등록부가 같은 경지를 말한다 "
                "(삼류·이류는 내력이 없다 → 경공도 없다. 그것이 개화의 보상이다)"
                if set(gated) == set(nonzero) else
                f"경공 천장과 경신 게이트가 어긋난다: 천장만 있는 경지 {set(nonzero) - set(gated)} · "
                f"게이트만 있는 경지 {set(gated) - set(nonzero)}")

    declared = str(gg(cfg, "mode", "gate_realm_from", default=""))
    first = next((r for r in realm_names(cfg) if r in gated), None)
    rep.verdict(declared == first,
                f"mode.gate_realm_from = {declared} — realm_gates 의 첫 경지와 같다"
                if declared == first else
                f"mode.gate_realm_from({declared}) ≠ realm_gates 의 첫 경지({first})")

    # 개화 전 경지에 천장이 하나라도 있으면 그것은 '삼류가 지붕을 나는' 세계다
    if contract(cfg, "pre_flowering_is_zero"):
        bad = [r for r in realm_names(cfg) if r not in gated and r in nonzero]
        rep.verdict(not bad, "개화 전(범인·삼류·이류)의 경공 천장이 전부 0"
                    if not bad else f"개화 전 경지에 경공 천장이 있다: {bad}")


def lint_qi(cfg, rep):
    rep.head("② 내력 — 공짜로 나는 몸은 무협이 아니다")
    lo, hi = qi_band_range(cfg)
    rep.say(f"     internal_energy.yml cost_bands.{band(cfg)} = [{lo}, {hi}] "
            f"(하단 = 두름·유지 · 상단 = 쏨·발출)")

    costs = {
        "유지(upkeep)": num(gg(cfg, "qi", "upkeep", "cost"), 1),
        "수상비 유지": num(gg(cfg, "qi", "upkeep", "cost"), 1)
                       * num(gg(cfg, "qi", "water_upkeep_multiplier"), 2),
        "도약(leap)": num(gg(cfg, "qi", "leap"), 1),
        "벽 딛기(wall_kick)": num(gg(cfg, "qi", "wall_kick"), 1),
        "물러남(retreat)": num(gg(cfg, "qi", "retreat"), 1),
        "파고듦(close_in)": num(gg(cfg, "qi", "close_in"), 1),
        "낙법 완충(soften)": num(gg(cfg, "qi", "soften", "cost"), 1),
    }
    out = [f"{k} {int(v)}" for k, v in costs.items()]
    rep.say("     " + " · ".join(out))
    bad = {k: v for k, v in costs.items() if not (lo <= v <= hi)}
    rep.verdict(not bad,
                f"경공의 모든 코스트가 경신 밴드 [{lo}, {hi}] 안 — "
                f"경공은 자기만의 눈금을 만들지 않았다 (등록부의 밴드를 쓴다)"
                if not bad else f"경신 밴드를 벗어난 코스트: {bad}")

    floor = int(num(gg(cfg, "qi", "floor"), 0))
    if contract(cfg, "no_free_flight"):
        rep.verdict(floor >= max(1, lo),
                    f"코스트 하한 {floor} ≥ 밴드 하단 {lo} — **어떤 할인도 경공을 공짜로 만들지 못한다**"
                    if floor >= max(1, lo) else
                    f"코스트 하한이 {floor} — 심법 할인이 코스트를 0 으로 만들 수 있다 (공짜로 나는 몸)")

    applies = gg(cfg, "discount_rule", "applies_to", default=[]) or []
    rep.verdict(applies == ["upkeep_interval"],
                "심법 할인은 **유지 간격**만 늘린다 — '얼마를 내는가'가 아니라 "
                "'얼마나 오래 버티는가'를 산다 (곤륜 −2 → 같은 내력으로 3배를 난다)"
                if applies == ["upkeep_interval"] else
                f"discount_rule.applies_to = {applies} — 할인이 단발 코스트에 물리면 곤륜(−2)의 경공이 공짜가 된다")

    dep = num(gg(cfg, "qi", "depleted", "grace_multiplier"), 1)
    rep.verdict(dep == 0,
                "★ 고갈 시 낙법 배율 0 — **내력이 끊기면 돌처럼 떨어진다.** 그 순간이 이 게임의 극적인 자리다"
                if dep == 0 else f"고갈 시에도 낙법이 {dep} 만큼 듣는다 — 떨어지는 것이 안 무섭다")


def lint_ceiling(cfg, rep):
    rep.head("③ 경지 천장 — 높은 경지가 더 가벼운가 (사다리가 뒤집히지 않는가)")
    keys = ["speed_bonus", "jump_bonus", "fall_grace_m", "wall_climb_m"]
    ladder = [r for r in realm_names(cfg) if r in gated_realms(cfg)]
    bad = []
    for k in keys:
        vals = [num(ceiling(cfg, r).get(k), 0) for r in ladder]
        for i in range(1, len(vals)):
            if vals[i] < vals[i - 1]:
                bad.append(f"{k}: {ladder[i - 1]}({vals[i - 1]}) → {ladder[i]}({vals[i]})")
    for r in ladder:
        c = ceiling(cfg, r)
        rep.say(f"     {r:<4} 속도 +{num(c.get('speed_bonus'), 0):.2f} · "
                f"도약 +{num(c.get('jump_bonus'), 0):.2f} (높이 {jump_height(cfg, num(c.get('jump_bonus'), 0)):.1f}m) · "
                f"낙법 {num(c.get('fall_grace_m'), 0):.0f}m · 벽 {num(c.get('wall_climb_m'), 0):.0f}m · "
                f"수상비 {'○' if c.get('water_run') else '×'}")
    rep.verdict(not bad, "경지가 오를수록 몸이 가벼워진다 (다섯 축 전부 단조 증가)"
                if not bad else f"천장 사다리가 뒤집힌 자리: {bad}")

    # 신법 수련이 천장에 실제로 닿는가 — 안 닿으면 '경지가 천장'이라는 말이 거짓이다
    stuck = []
    for r in ladder:
        cap = attr_cap(cfg, r)
        for k in keys:
            ceil_v = num(ceiling(cfg, r).get(k), 0)
            if ceil_v <= 0:
                continue
            at_cap = per_agility(cfg, k) * cap
            if at_cap < ceil_v - 1e-9:
                stuck.append(f"{r}.{k} (민첩 캡 {cap} 로도 {at_cap:.2f} < 천장 {ceil_v})")
    rep.verdict(not stuck,
                "신법을 캡까지 몰면 모든 경지에서 천장에 닿는다 — "
                "**경지가 천장을 열고 수련이 그 천장까지 자란다**는 말이 참이다"
                if not stuck else
                f"수련으로 못 닿는 천장이 있다 (그 경지의 천장은 장식이다): {stuck}")


def lint_bobeop(cfg, rep):
    rep.head("④ 문파 보법 — 지어내지 않았는가 (skills.yml 이 정본)")
    cat = bobeop_catalog(cfg)
    if not cat:
        rep.fail("skills.yml 에 category 경공 인 무공이 없다 — 보법이 없으면 경공은 발이 없다")
        return
    for sid, b in cat.items():
        flags = []
        if b["vertical"]:
            flags.append("수직(벽)")
        if b["irregular"]:
            flags.append("변칙(±30°)")
        rep.say(f"     {b['name']:<6} ({b['faction']:<8}) 요구 {b['realm']:<3} · "
                f"이동 {b['distance']:>4.1f}m {'· ' + ' · '.join(flags) if flags else ''}")
    missing = [sid for sid, b in cat.items() if b["distance"] <= 0]
    rep.verdict(not missing,
                f"보법 {len(cat)}종 전부 skill_mechanics.yml 에 거리가 등록돼 있다 — "
                f"경공은 그 값을 **읽지, 짓지 않는다**"
                if not missing else f"거리가 없는 보법: {missing} (엔진이 기본값으로 때운다)")

    # 우리 config 가 보법 id 를 복제했는가 — 복제하면 두 개의 진실이 생긴다
    dup = [k for k in (gg(cfg, "bobeop", default={}) or {}) if k in cat]
    rep.verdict(not dup,
                "gyeonggong.yml 이 보법 목록·수치를 복제하지 않는다 (source_catalog · source_mechanics 로 가리킬 뿐)"
                if not dup else f"보법 수치가 두 곳에 있다 (등록부가 갈라진다): {dup}")

    vertical = [b["name"] for b in cat.values() if b["vertical"]]
    rep.verdict(bool(vertical) and len(vertical) < len(cat),
                f"벽을 딛는 보법은 일부뿐이다 ({', '.join(vertical)}) — "
                f"**문파마다 발이 다르다.** 화산·개방의 발로는 벽이 벽이다"
                if vertical and len(vertical) < len(cat) else
                "수직 보법이 없거나 전부다 — 문파 차이가 사라졌다")


def lint_simbeop(cfg, rep):
    rep.head("⑤ 심법의 결 — simbeop.yml 원문과 어긋나지 않는가 (프로즈 ↔ 기계 키)")
    ours = gg(cfg, "simbeop_effects", default={}) or {}
    theirs = {sid: sim_discount_from_prose(cfg, sid)
              for sid in (dig(cfg, "simbeop.yml", "simbeop", default={}) or {})}
    theirs = {k: v for k, v in theirs.items() if v}

    for sid, d in sorted(theirs.items(), key=lambda kv: -kv[1]):
        name = dig(cfg, "simbeop.yml", "simbeop", sid, "name", default=sid)
        mine = int(num((ours.get(sid) or {}).get("경신_할인"), 0))
        mark = "" if mine == d else "  ← ★어긋남"
        rep.say(f"     {name:<8} simbeop.yml '{band(cfg)} 소모 -{d}'  ↔  "
                f"gyeonggong.yml 경신_할인 {mine}{mark}")

    bad = [sid for sid, d in theirs.items()
           if int(num((ours.get(sid) or {}).get("경신_할인"), 0)) != d]
    bad += [sid for sid in ours if sid not in theirs
            and int(num((ours[sid] or {}).get("경신_할인"), 0)) > 0]
    rep.verdict(not bad,
                "심법의 경신 할인이 simbeop.yml 원문과 정확히 같다 — "
                "**번역이지 발명이 아니다** (프로즈를 기계 키로 옮겼을 뿐)"
                if not bad else f"simbeop.yml 원문과 어긋난 심법: {sorted(set(bad))}")

    immune_prose = {sid for sid in (dig(cfg, "simbeop.yml", "simbeop", default={}) or {})
                    if sim_fall_immune_from_prose(cfg, sid)}
    immune_ours = {sid for sid, e in ours.items() if (e or {}).get("낙하_무효")}
    rep.verdict(immune_prose == immune_ours,
                f"낙하 무효(답운)를 가진 심법이 원문과 같다: "
                f"{', '.join(dig(cfg, 'simbeop.yml', 'simbeop', s, 'name', default=s) for s in immune_ours) or '없음'} "
                f"— 그리고 그것도 **고갈되면 안 듣는다** (discount_rule.fall_immunity)"
                if immune_prose == immune_ours else
                f"낙하 무효가 어긋난다: 원문 {immune_prose} ↔ 우리 {immune_ours}")


def lint_armor(cfg, rep):
    rep.head("⑥ 갑옷이 회피를 판다 — equipment.yml 의 대원칙, 경공 쪽 절반")
    blocked_by = str(gg(cfg, "armor_gate", "blocked_by", default="경공_불가"))
    armor = dig(cfg, "equipment.yml", "armor", default={}) or {}
    blocking = [name for name, spec in armor.items()
                if isinstance(spec, dict) and blocked_by in (spec.get("restrictions") or [])]
    rep.say(f"     equipment.yml 에서 '{blocked_by}' 인 갑: {', '.join(blocking) or '없음'}")

    mapped = set((gg(cfg, "armor_gate", "by_chestplate", default={}) or {}).values())
    rep.say(f"     바닐라 흉갑 → 갑 계열 매핑: {', '.join(sorted(mapped))} "
            f"(안 입은 몸 = {gg(cfg, 'armor_gate', 'none', default='무복')})")

    rep.verdict(bool(blocking) and set(blocking) <= mapped,
                f"철갑({', '.join(blocking)})을 입으면 경공이 안 열린다 — "
                f"equipment.yml 의 restrictions 가 처음으로 **몸에 닿는다** "
                f"(gap_audit §3-①: '갑옷은 회피를 판다 — 양쪽 다 미구현')"
                if blocking and set(blocking) <= mapped else
                f"'{blocked_by}' 갑이 바닐라 흉갑에 매핑되지 않았다 — 규칙이 여전히 문서로만 있다")

    unknown = [c for c in mapped if c not in armor]
    rep.verdict(not unknown, "매핑된 갑 계열이 전부 equipment.yml 등록부 안"
                if not unknown else f"등록부에 없는 갑 계열: {unknown}")


def lint_budget(cfg, rep):
    rep.head("⑦ 예산 — 티커를 만들었으면 재는 자도 만들었는가")
    probe = dig(cfg, "performance.yml", "metrics", "probes", "gyeonggong")
    budgets = dig(cfg, "performance.yml", "tick_budget", "subsystem_budget_ms", default={}) or {}
    rep.verdict(probe in budgets,
                f"metrics.probes.gyeonggong → {probe} ({num(budgets.get(probe), 0):.0f}ms) — "
                f"경공 티커가 예산에 등록됐다 (초과하면 콘솔이 운다)"
                if probe in budgets else
                "performance.yml metrics.probes 에 gyeonggong 이 없다 — "
                "티커가 예산 밖에서 돈다 (재는 자가 없으면 그것은 예산이 아니다)")

    per_view = num(dig(cfg, "performance.yml", "particles", "per_player_view_per_tick"), 600)
    mine = num(gg(cfg, "vfx", "budget_per_tick"), 0)
    rep.verdict(0 < mine <= per_view * 0.1,
                f"경공 파티클 상한 {mine:.0f}/틱 ≤ 시야 예산({per_view:.0f})의 10% — "
                f"연출이 예산을 먹지 않는다"
                if 0 < mine <= per_view * 0.1 else
                f"경공 파티클 상한({mine:.0f})이 시야 예산({per_view:.0f})에 비해 크다")


# ══════════════════════════════════════════════════════════════════════════════
#  ⑧ 발동 — **손가락이 켜는가, 아니면 나에게 일어나는가**
# ══════════════════════════════════════════════════════════════════════════════
# 이 축은 config 로 못 잰다. 발동은 **코드에 있다.** 그래서 이 눈은 소스를 읽는다.
#   "조건이 맞으면 알아서 발동한다"면 그것은 내가 쓰는 것이 아니라 **나에게 일어나는 것**이다.
#   경공은 무공이다. 무공은 손가락이 낸다.
# ─────────────────────────────────────────────────────────────────────────────

LISTENER = os.path.join(
    ROOT, "server-mvt/src/main/java/com/honcheon/mvt/GyeonggongListener.java")

#  '경공을 켠다'의 정의 — Ride 를 새로 만드는 것. 이 짓은 발동점 하나에서만 일어나야 한다
IGNITE = (r"riding\.put\s*\(", r"riding\.computeIfAbsent\s*\(")


def method_body(src, signature_fragment):
    """`signature_fragment` 로 시작하는 메서드의 본문을 중괄호 균형으로 잘라 온다 (없으면 None)."""
    i = src.find(signature_fragment)
    if i < 0:
        return None
    j = src.find("{", i)
    if j < 0:
        return None
    depth, k = 0, j
    while k < len(src):
        if src[k] == "{":
            depth += 1
        elif src[k] == "}":
            depth -= 1
            if depth == 0:
                return src[j:k + 1]
        k += 1
    return None


def strip_comments(src):
    """주석은 코드가 아니다 — 주석에 적힌 'isSprinting' 이 눈을 속이면 안 된다."""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def lint_activation(cfg, rep):
    rep.head("⑧ 발동 — **내 손가락이 켜는가** (자동이면 그것은 무공이 아니다)")
    if not os.path.isfile(LISTENER):
        rep.verdict(False, f"{os.path.basename(LISTENER)} 가 없다 — 발동을 잴 곳이 없다")
        return
    with open(LISTENER, encoding="utf-8") as f:
        raw = f.read()
    src = strip_comments(raw)

    event = str(gg(cfg, "mode", "activate_event", default=""))
    rep.say(f"     gyeonggong.yml mode.activate = {gg(cfg, 'mode', 'activate', default='?')}")
    rep.say(f"     mode.activate_event = {event or '(등록 없음)'}")
    rep.say("")

    # ── ① 손가락이 켠다 — 등록부가 말한 이벤트의 핸들러가 실제로 있는가
    if contract(cfg, "finger_activated"):
        handler = re.search(r"@EventHandler[^)]*\)?\s*public\s+void\s+(\w+)\s*\(\s*"
                            + re.escape(event) + r"\s+\w+\s*\)", src) if event else None
        rep.verdict(bool(handler),
                    f"{event} 핸들러가 코드에 있다 ({handler.group(1) if handler else '-'}) — "
                    f"**공중에서 점프 키를 한 번 더 눌러야** 경공이 나간다"
                    if handler else
                    f"mode.activate_event({event or '미등록'}) 의 핸들러가 "
                    f"GyeonggongListener 에 없다 — 손가락이 켤 자리가 없다")
        if not handler:
            return
        body = method_body(src, f"void {handler.group(1)}(") or ""

        # 그 핸들러가 **정말로 이벤트를 훔치는가** (날게 두면 그것은 경공이 아니라 크리에이티브다)
        steals = "setCancelled(true)" in body
        rep.verdict(steals,
                    "그 핸들러가 비행 이벤트를 **취소하며 훔친다** — 날게 두지 않고 신호만 가져온다"
                    if steals else
                    "발동 핸들러가 event.setCancelled(true) 를 안 한다 — **진짜로 난다** (경공이 아니다)")

        # 값이 든다 — 내력을 확인하고 깎는가 (공짜로 나는 몸은 없다)
        pays = "leapCost()" in body and re.search(r"energy\s*-=", body) is not None
        rep.verdict(pays,
                    "허공을 딛을 때마다 qi.leap 을 태운다 — **내력이 없으면 안 나간다**"
                    if pays else
                    "발동 핸들러가 qi.leap(leapCost) 을 확인·차감하지 않는다 — 공짜로 나는 몸이다")

        # 침묵 금지 — 왜 안 나갔는지 말하는가
        talks = all(k in body for k in ('message("air_spent")', 'message("depleted")')) \
            and "blockedBy()" in body
        rep.verdict(talks,
                    "안 나가면 **왜 안 나갔는지 말한다** (허공 소진 · 내력 고갈 · 개화 전/철갑)"
                    if talks else
                    "발동 실패가 침묵한다 — air_spent · depleted · blockedBy 중 화면에 안 뜨는 것이 있다")

        # 그리고 **켜는 곳은 여기 하나뿐**이어야 한다
        elsewhere = [m for pat in IGNITE for m in re.finditer(pat, src)
                     if not (src.find(body) <= m.start() < src.find(body) + len(body))]
        rep.verdict(not elsewhere,
                    "경공을 켜는 자리(riding.put)는 **발동 핸들러 하나뿐**이다 — 다른 문이 없다"
                    if not elsewhere else
                    f"발동 핸들러 밖에서 경공이 켜진다 ({len(elsewhere)}곳) — 뒷문이 있으면 그것이 자동 발동이다")

    # ── ② 자동 발동 금지 — 지상 점프가 켜면 그것은 '나에게 일어나는 것'이다
    if contract(cfg, "no_auto_activation"):
        jump = method_body(src, "void onJump(") or ""
        auto = [w for w in ("isSprinting()", "computeIfAbsent") if w in jump] \
            + (["riding.put"] if "riding.put" in jump else [])
        rep.verdict(not auto,
                    "지상 점프(PlayerJumpEvent)는 **켜지 않는다** — 이미 켜진 몸에만 신법을 싣는다. "
                    "구판의 '달리며 점프하면 알아서 발동'은 죽었다"
                    if not auto else
                    f"PlayerJumpEvent 가 경공을 **자동으로 켠다** ({', '.join(auto)}) — "
                    f"조건이 맞으면 저절로 발동한다면 그것은 내가 쓰는 것이 아니다")

    # ── ③ 크리에이티브 불가침 — 연무장(Dojang.enter)이 크리에이티브다. 거기선 날 수 있어야 한다
    if contract(cfg, "creative_untouched"):
        guard = method_body(src, "boolean creativeFlight(") or ""
        both = "GameMode.CREATIVE" in guard and "GameMode.SPECTATOR" in guard
        wings = method_body(src, "void wings(") or ""
        # setAllowFlight 를 부르는 모든 메서드가 creativeFlight 가드를 통과했는가
        calls = [m for m in re.finditer(r"setAllowFlight\s*\(", src)]
        guarded = "creativeFlight(" in wings and "setAllowFlight" in wings \
            and len(calls) == len(re.findall(r"setAllowFlight\s*\(", wings))
        rep.verdict(both and guarded,
                    "setAllowFlight 는 오직 wings() 안에서만 불리고, wings() 는 "
                    "**크리에이티브·스펙테이터에서 즉시 돌아선다** — 연무장에서는 진짜로 난다"
                    if both and guarded else
                    "크리에이티브/스펙테이터 가드가 없거나 setAllowFlight 가 가드 밖에서 불린다 — "
                    "연무장(Dojang.enter = CREATIVE)의 비행이 깨진다")

        handler_body = method_body(src, "void onAirJump(") or ""
        early = "creativeFlight(player)" in handler_body
        rep.verdict(early,
                    "발동 핸들러도 크리에이티브면 **이벤트를 훔치지 않고 돌아선다** (그 비행은 우리 것이 아니다)"
                    if early else
                    "발동 핸들러가 크리에이티브의 비행 토글까지 취소한다 — 연무장에서 못 난다")

    # ── ④ 몇 번 딛는가는 경지가 정한다 (등록부 축)
    if contract(cfg, "air_jumps_gated_by_realm"):
        gated = gated_realms(cfg)
        bad = [r for r in realm_names(cfg)
               if r not in gated and num(ceiling(cfg, r).get("air_jumps"), 0) > 0]
        table = " · ".join(f"{r} {int(num(ceiling(cfg, r).get('air_jumps'), 0))}회"
                           for r in realm_names(cfg) if r in gated)
        rep.say(f"     realm_ceiling.air_jumps — {table}")
        rep.verdict(not bad,
                    "개화 전(범인·삼류·이류)은 허공에 발 디딜 곳이 없다 — "
                    "**같은 허공에서 삼류와 절정이 다른 사람이 된다**"
                    if not bad else f"개화 전 경지가 허공을 딛는다: {bad}")
        rising = [int(num(ceiling(cfg, r).get("air_jumps"), 0))
                  for r in realm_names(cfg) if r in gated]
        rep.verdict(rising == sorted(rising),
                    "경지가 오를수록 허공을 더 딛는다 (횟수는 승급이 판다 — 신법이 못 산다)"
                    if rising == sorted(rising) else
                    f"경지가 올랐는데 도약 횟수가 줄어드는 자리가 있다: {rising}")


# ══════════════════════════════════════════════════════════════════════════════
#  ② 몸 시뮬 — 무엇을 할 수 있고, 무엇을 못 하는가
# ══════════════════════════════════════════════════════════════════════════════

def simulate(cfg, rep, budget):
    sim_reach(cfg, rep)
    sim_flight(cfg, rep, budget)
    sim_dominance(cfg, rep, budget)
    sim_opportunity(cfg, rep, budget)
    sim_depleted(cfg, rep)


def sim_reach(cfg, rep):
    """★ 삼류가 지붕을 날면 안 된다 — 그리고 **아무도 지붕을 '뛰어서' 오르면 안 된다.**"""
    rep.head("[몸] 닿는 곳 — 담(3m)과 지붕(5m)은 어떻게 오르는가")
    wall_h, roof_h = landmark(cfg, "담"), landmark(cfg, "지붕")
    rep.say(f"     담 {wall_h:.0f}m · 지붕 {roof_h:.0f}m · 절벽 {landmark(cfg, '절벽'):.0f}m "
            f"(gyeonggong.yml audit.landmarks)")
    rep.say("")

    jumped_roof = []
    for realm in realm_names(cfg):
        cap = attr_cap(cfg, realm)
        p = profile(cfg, realm, cap, bobeop={"vertical": True})   # 최대치: 캡 민첩 + 수직 보법
        h = jump_height(cfg, p["jump"]) if p["open"] else jump_height(cfg, 0)
        air = int(num(ceiling(cfg, realm).get("air_jumps"), 0)) if p["open"] else 0
        # ★ 더블 점프의 체공 — 지상 점프(바닐라 높이) 위에 **허공을 딛은 만큼** 더 오른다
        chain = jump_height(cfg, 0) + air * h if p["open"] else h
        total = chain + p["wall"]
        if p["open"] and h >= roof_h:
            jumped_roof.append(realm)
        rep.say(f"     {realm:<4} 지상도약 {h:>4.1f}m {'(담 넘음)' if h >= wall_h else '        '} "
                f"· 허공 {air}회 → 체공 {chain:>5.1f}m "
                f"+ 벽 딛기 {p['wall']:>4.1f}m = {total:>5.1f}m "
                f"{'← 지붕에 오른다' if total >= roof_h else ''}")

    rep.say("")
    if contract(cfg, "jump_never_reaches_roof"):
        rep.verdict(not jumped_roof,
                    "★ **어떤 경지도 (지상) 도약 한 번으로는 지붕(5m)에 못 닿는다** — "
                    "지붕은 그냥 뛰어오르는 것이 아니라 **허공을 딛거나 벽을 딛고** 오르는 것이다. "
                    "그 둘은 **내력을 태우고, 손가락이 눌러야** 나간다"
                    if not jumped_roof else
                    f"도약만으로 지붕에 닿는 경지: {jumped_roof} — 벽 딛기가 무의미해진다")

    pre = [r for r in realm_names(cfg) if r not in gated_realms(cfg)]
    rep.ok(f"개화 전({', '.join(pre)})은 바닐라 그대로 — 담도 못 넘는다. "
           f"**같은 담 앞에서 삼류와 절정이 다른 사람이 되는 것**, 그것이 경공이다")


def builds_of(cfg, realm, budget):
    """growth_audit 의 다섯 몰빵 + 균형 — 빌드 적립은 저 도구가 정본이다 (복제 금지)."""
    return standard_builds(cfg, realm, budget)


def body_of(cfg, b, realm, bobeop):
    """한 빌드의 몸 — 민첩(신법)이 크기를, 내공(단전)이 연료를 산다."""
    agility = b.attrs.get("민첩", 0.0)
    naegong = b.attrs.get("내공", 0.0)
    p = profile(cfg, realm, agility, simbeop=b.simbeop, bobeop=bobeop)
    pool = pool_of(naegong, cfg)
    secs = flight_seconds(cfg, pool, p)
    return {
        "name": b.name, "agility": agility, "pool": pool, "p": p,
        "seconds": secs, "meters": flight_meters(cfg, secs, p),
        "grace": p["grace"], "height": jump_height(cfg, p["jump"]) + p["wall"],
    }


def sim_flight(cfg, rep, budget):
    rep.head(f"[몸] 얼마나 나는가 — 내력이 곧 날개다 (수련 예산 {budget:.0f}일치 = {budget / 360:.1f}년)")
    lo, hi = gg(cfg, "audit", "flight_seconds_band", default=[1, 30])
    for realm in [r for r in realm_names(cfg) if r in gated_realms(cfg)][:3]:
        rep.say(f"     ── {realm} " + "─" * 50)
        for b in builds_of(cfg, realm, budget):
            body = body_of(cfg, b, realm, {"vertical": True})
            rep.say(f"     {body['name']:<14} 민첩 {body['agility']:>4.1f} · 내력 풀 {body['pool']:>2} → "
                    f"체공 {body['seconds']:>5.1f}초 · 거리 {body['meters']:>6.1f}m · "
                    f"낙법 {body['grace']:>4.1f}m")
    rep.say("")

    bad = []
    for realm in [r for r in realm_names(cfg) if r in gated_realms(cfg)]:
        for b in builds_of(cfg, realm, budget):
            body = body_of(cfg, b, realm, {"vertical": True})
            if body["pool"] > 0 and not (num(lo, 1) <= body["seconds"] <= num(hi, 30)):
                bad.append(f"{realm}/{body['name']} {body['seconds']:.1f}초")
    rep.verdict(not bad,
                f"한 번에 나는 시간이 전 경지·전 빌드에서 {lo}~{hi}초 안 — "
                f"경공은 **이동 수단이지 비행이 아니다** (겉날개를 끈 자리를 대신하지 않는다)"
                if not bad else f"체공 시간이 밴드({lo}~{hi}초) 밖: {bad}")


def sim_dominance(cfg, rep, budget):
    """★★ 이 도구의 심장 — **경공 몰빵이 정답이 되면 안 된다.**"""
    rep.head("[지배 전략] 경공 몰빵(신법)이 정답인가 — 세 축(체공·속도·높이)을 다 먹는가")
    rep.say("     ★ 나는 데는 **연료**가 든다: 크기는 민첩(신법)이 사고, **지속은 내공이 산다.**")
    rep.say("       발만 판 자는 빨리 날지만 오래 못 난다. 단전만 판 자는 오래 날지만 낮게 난다.")
    rep.say("")

    limit = int(num(contract(cfg, "dominance_max_axes", 2), 2))
    violations = []
    for realm in [r for r in realm_names(cfg) if r in gated_realms(cfg)][:3]:
        bodies = [body_of(cfg, b, realm, {"vertical": True})
                  for b in builds_of(cfg, realm, budget)]
        axes = {
            "체공(초)": max(bodies, key=lambda x: x["seconds"]),
            "속도(m/s 가산)": max(bodies, key=lambda x: x["p"]["speed"]),
            "높이(도약+벽)": max(bodies, key=lambda x: x["height"]),
        }
        wins = {}
        for axis, winner in axes.items():
            wins[winner["name"]] = wins.get(winner["name"], 0) + 1
        line = " · ".join(f"{axis} → {w['name']}" for axis, w in axes.items())
        rep.say(f"     {realm:<4} {line}")
        for name, n in wins.items():
            if n > limit:
                violations.append(f"{realm}/{name} ({n}/3축)")

    rep.say("")
    rep.verdict(not violations,
                f"★ 어느 빌드도 세 축을 다 먹지 못한다 (상한 {limit}/3) — "
                f"**경공에도 지배 전략이 없다.** 신법은 크기를 사고 내공은 시간을 산다. "
                f"둘을 다 사려면 둘 다 키워야 하고, 그러면 손(초식)과 몸(외공)이 빈다"
                if not violations else
                f"세 축을 다 먹는 빌드가 있다 — 경공 몰빵이 정답이다: {violations}")

    # 신법 몰빵의 대가 — 그 사람은 무엇을 못 갖는가
    realm = "절정" if "절정" in gated_realms(cfg) else next(iter(gated_realms(cfg)))
    bs = {b.name: b for b in builds_of(cfg, realm, budget)}
    sin = next((b for n, b in bs.items() if n.startswith("신법")), None)
    nae = next((b for n, b in bs.items() if n.startswith("내공")), None)
    if sin and nae:
        sb = body_of(cfg, sin, realm, {"vertical": True})
        nb = body_of(cfg, nae, realm, {"vertical": True})
        rep.say("")
        rep.say(f"     [{realm}] 신법 몰빵: 민첩 {sb['agility']:.1f} · 내력 {sb['pool']} → "
                f"체공 {sb['seconds']:.1f}초 · 거리 {sb['meters']:.0f}m")
        rep.say(f"     [{realm}] 내공 몰빵: 민첩 {nb['agility']:.1f} · 내력 {nb['pool']} → "
                f"체공 {nb['seconds']:.1f}초 · 거리 {nb['meters']:.0f}m")
        rep.verdict(nb["seconds"] > sb["seconds"] and sb["p"]["speed"] > nb["p"]["speed"],
                    "★ **가장 빠른 몸과 가장 오래 나는 몸이 다른 사람이다** — 이것이 축이 둘이라는 증거다"
                    if nb["seconds"] > sb["seconds"] and sb["p"]["speed"] > nb["p"]["speed"] else
                    "신법 몰빵이 체공까지 이긴다 — 내공을 키울 이유가 경공에서 사라졌다")


def sim_opportunity(cfg, rep, budget):
    """경공은 **전투 자원을 먹는다** — 이것이 '내력을 태운다'의 진짜 뜻이다."""
    rep.head("[기회비용] 나는 자는 못 태운다 — 경공이 전투에서 무엇을 가져가는가")
    forms = dig(cfg, "internal_energy.yml", "cost_bands", default={}) or {}
    gigi = forms.get("검기", {}).get("cost", [1, 3])
    발출 = int(num(gigi[-1] if isinstance(gigi, list) else gigi, 3))
    발경 = int(num((forms.get("발경") or {}).get("cost"), 1))
    up = num(gg(cfg, "qi", "upkeep", "cost"), 1)
    interval = num(gg(cfg, "qi", "upkeep", "interval_ticks"), 20)

    rep.say(f"     내력 1 = 발경 1회({발경}) · 검기 발출 {1/발출:.2f}회({발출}) · "
            f"경공 {interval / 20 / up:.1f}초")
    rep.say("")
    for realm in [r for r in realm_names(cfg) if r in gated_realms(cfg)][:3]:
        for b in builds_of(cfg, realm, budget):
            if not b.name.startswith(("신법", "내공")):
                continue
            body = body_of(cfg, b, realm, {"vertical": True})
            if body["pool"] <= 0:
                continue
            rep.say(f"     {realm:<4} {body['name']:<12} 내력 {body['pool']:>2} → "
                    f"발경 {body['pool'] // max(1, 발경):>2}회  또는  "
                    f"검기 발출 {body['pool'] // max(1, 발출):>2}회  또는  "
                    f"경공 {body['seconds']:>4.1f}초 — **셋은 같은 단전에서 나온다**")
    rep.say("")
    rep.ok("경공은 쿨다운이 아니라 **전투 자원과의 경합**으로 과금된다 — "
           "담을 넘은 자는 그 합에 발경을 못 싣는다 (internal_energy 조식: "
           "'내력을 쓴 합에는 단전이 돌지 않는다' — **경공을 켜 둔 자는 숨을 고르지 못한다**)")


def sim_depleted(cfg, rep):
    """★ 내력이 끊기면 떨어진다 — 그 낙하가 안 아프면 이 규칙은 연출이다."""
    rep.head("[고갈] 내력이 끊기면 떨어진다 — 그 순간이 실제로 아픈가")
    ratio_need = num(contract(cfg, "depleted_fall_hurts_ratio", 0.5), 0.5)
    cliff = landmark(cfg, "절벽")
    bad = []
    for realm in [r for r in realm_names(cfg) if r in gated_realms(cfg)]:
        cap = attr_cap(cfg, realm)
        p = profile(cfg, realm, cap, bobeop={"vertical": True})
        # ★ '그가 다니던 높이' — 처음엔 절벽(20m) 하나로 쟀다. 그러자 현경(내구 38)이 45% 로 계약을 깼고,
        #   나는 낙하 배율을 새로 지어내려 했다. **그건 세계를 고치는 게 아니라 눈을 속이는 것이었다.**
        #   현경은 20m 절벽에서 떨어지지 않는다 — **자기가 오른 높이**에서 떨어진다.
        #   그래서 높이 = max(절벽, 낙법 천장 + 자기 몸이 닿는 높이(도약 + 벽)). 높이 나는 자가 더 크게 떨어진다.
        reach = jump_height(cfg, p["jump"]) + p["wall"]
        height = max(cliff, p["grace"] + reach)
        che = cap - 1                       # 표준 무인 (growth_audit 의 기본 몸 — 체력을 캡 −1 로 둔다)
        dur = durability(cfg, che, realm=realm)
        dmg = fall_damage(cfg, height)                     # ★ 고갈 — 낙법 배율 0. 바닐라 그대로 먹는다
        alive_dmg = fall_damage(cfg, height - p["grace"])  # 기가 도는 몸 — 경지·신법이 산 높이만큼 지운다
        ratio = dmg / max(1, dur)
        alive = "즉사" if dmg >= dur else f"내구의 {ratio * 100:.0f}%"
        rep.say(f"     {realm:<4} 다니던 높이 {height:>4.1f}m · 내구 {dur:>2} │ "
                f"기가 돌면 {alive_dmg:>4.1f} (+내력으로 완충 가능)  →  "
                f"**고갈이면 {dmg:>4.1f} ({alive})**")
        if ratio < ratio_need:
            bad.append(f"{realm} ({ratio * 100:.0f}%)")
    rep.say("")
    rep.verdict(not bad,
                f"★★ 고갈 낙하가 전 경지에서 내구의 {ratio_need * 100:.0f}% 이상을 가져간다 — "
                f"**공중에서 내력이 끊기는 것은 죽음에 가깝다.** "
                f"경공을 켤 때마다 플레이어는 그 값을 안다 (그래서 이것이 자원 게임이다)"
                if not bad else
                f"고갈 낙하가 안 아픈 경지가 있다 (연출일 뿐이다): {bad}")
    rep.ok("그리고 낙법은 **경공을 켜지 않아도** 듣는다 (몸이 아는 것이다) — "
           "다만 **기가 도는 몸에만**. 곤륜의 답운(낙하 무효)조차 고갈되면 안 듣는다")


# ══════════════════════════════════════════════════════════════════════════════

def main():
    ap = argparse.ArgumentParser(description="혼천 경공 검수 — 몸이 땅을 딛는 법")
    ap.add_argument("--lint-only", action="store_true", help="① 등록 정합 린트만")
    ap.add_argument("--sim-only", action="store_true", help="② 몸 시뮬만")
    ap.add_argument("--budget", type=float, default=1800.0, help="수련 예산(일치) — 기본 1800 (5년)")
    args = ap.parse_args()

    rep = Report()
    rep.say("╔" + "═" * 70 + "╗")
    rep.say("║" + "  혼천 경공 감사 — gyeonggong_audit".ljust(69) + "║")
    rep.say("║" + "  몸을 재는 자 — 경지가 천장인가, 공짜로 나는가, 떨어지면 아픈가".ljust(50) + "║")
    rep.say("╚" + "═" * 70 + "╝")

    try:
        cfg = load_all()
    except YamlError as e:
        print(f"{FAIL} config 파싱 실패: {e}", file=sys.stderr)
        return 2
    if GG not in cfg:
        print(f"{FAIL} config/{GG} 가 없다 — 경공의 등록부가 없으면 잴 것이 없다", file=sys.stderr)
        return 2

    cat = bobeop_catalog(cfg)
    rep.say(f"  config {len(cfg)}종 적재 · 경지 {len(realm_names(cfg))}단 · "
            f"경신 개방 {len(gated_realms(cfg))}단 · 문파 보법 {len(cat)}종 "
            f"({', '.join(b['name'] for b in cat.values())})")

    if not args.sim_only:
        lint(cfg, rep)
    if not args.lint_only:
        simulate(cfg, rep, args.budget)

    rep.say()
    rep.say("═" * 72)
    n_v, n_w = len(rep.violations), len(rep.warnings)
    if n_v == 0 and n_w == 0:
        rep.say("  총평: ✅ 위반 0건 · 경고 0건 — 몸이 가볍되, 그 가벼움이 정답은 아니다")
    else:
        rep.say(f"  총평: 위반 {n_v}건 · 경고 {n_w}건")
        if n_v:
            rep.say("")
            rep.say(f"  ── 위반 ({FAIL}) — 경공이 세계를 부순다")
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
