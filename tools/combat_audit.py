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
    "권법": "맨손", "장법": "맨손", "박투": "맨손", "금나술": "맨손",
    "독공": "맨손", "합격진": "검",
    "궁술": "활", "암기": "암기", "암살술": "단검",
    "경공": None, "신법": None, "은신술": None, "의술": None, "심법": None,
}
NO_DAMAGE_CATEGORIES = {"경공", "신법", "은신술", "의술", "심법"}


def realm_axis(cfg):
    """경지별 표준 무인 — cultivation.yml 승급 요건에서 직접 읽는다."""
    names = realm_names(cfg)
    caps = dig(cfg, "player_creation.yml", "attribute_cap_by_realm", default={}) or {}
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
        cap = int(num(caps.get(nm), 3))
        axis[nm] = {
            "cap": cap,
            "attr": max(1, cap - 1),      # 표준 무인 = 상한 -1 (상한을 찍은 자는 표준이 아니다)
            "skill": skill,
            "naegong": naegong,
            "req_naegong_declared": naegong > 0,
        }
    return names, axis


def pool_of(naegong):
    """내력 풀 = round(내공 실수치 × 3) — internal_energy.yml 정본."""
    return int(round(naegong * 3))


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


def durability(cfg, che):
    f = str(dig(cfg, "combat.yml", "durability", "formula", default=""))
    if "체력" in f and "2" in f:
        return int(round(10 + che * 2))
    return int(round(10 + che * 2))


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
    lint_qi_gates(cfg, rep)
    lint_energy_budget(cfg, rep)
    lint_action_data(cfg, rep)
    lint_qi_ladder(cfg, rep)
    lint_weapon_break(cfg, rep)
    lint_npc_and_beasts(cfg, rep)


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
        pool = pool_of(axis[rr]["naegong"]) if rr in axis else 0
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
        if rr == "일류" and ng == 0:
            ng = 1.0
        p = pool_of(ng)
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
                 tech_grade=None, is_npc=False, naegong=None, stats=None):
        _, axis = realm_axis(cfg)
        a = axis.get(realm, {"attr": 3, "skill": 0, "naegong": 0.0})
        self.cfg = cfg
        self.name = name
        self.realm = realm
        self.stats = stats or {}
        self.atk_stat = num(self.stats.get("근력"), attr if attr is not None else a["attr"])
        self.def_stat = num(self.stats.get("민첩"), attr if attr is not None else a["attr"])
        self.che = num(self.stats.get("체력"), attr if attr is not None else a["attr"])
        self.skill = skill if skill is not None else a["skill"]
        self.is_npc = is_npc
        self.naegong = naegong if naegong is not None else a["naegong"]
        self.weapon = weapon
        wp = weapon_power(cfg, weapon)
        self.wpower = wp if wp is not None else 1.0
        tg = tech_grade or realm
        tp = tech_power(cfg, tg)
        self.tpower = tp if tp is not None else 2.0     # 일류급 구멍 — 도구의 대체값
        self.tpower_assumed = tp is None
        self.dur = durability(cfg, self.che)
        self.pool = pool_of(self.naegong)

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


def strike(att, dfn, att_pen=0, dfn_pen=0, mod=0, qi_power=0.0):
    """기대 피해·명중률·대성공률 (해석적)."""
    dist = margin_dist(att, dfn, att_pen, dfn_pen, mod)
    hit = Fraction(0)
    crit = Fraction(0)
    dmg = Fraction(0)
    for m, p in dist.items():
        if m >= 0:
            hit += p
            base = att.wpower + att.tpower + qi_power + (m // 2)
            dmg += p * Fraction(int(base * 2), 2)
            if m >= 4:
                crit += p
    return float(hit), float(dmg), float(crit)


def duel(att, dfn, max_rounds=25, a_mod=0, d_mod=0, a_qi=0.0, d_qi=0.0,
         a_attacks=1, d_attacks=1, d_immune=False):
    """기대값 결정론 진행 — 라운드마다 양측 기대 피해를 서로 깎는다."""
    hp_a, hp_b = float(att.dur), float(dfn.dur)
    log = []
    for r in range(1, max_rounds + 1):
        # 선공(att)이 먼저 친다 — combat.yml initiative. 눕은 자는 반격하지 않는다
        pa, pb = att.wound_pen(hp_a), dfn.wound_pen(hp_b)
        _, da, _ = strike(att, dfn, pa, pb, a_mod, a_qi)
        if d_immune:
            da = 0.0
        hp_b -= da * a_attacks
        if hp_b > 0:
            _, db, _ = strike(dfn, att, dfn.wound_pen(hp_b), pa, d_mod, d_qi)
            hp_a -= db * d_attacks
        log.append((r, max(hp_a, 0), max(hp_b, 0)))
        if hp_b <= 0 or hp_a <= 0:
            break
    ttk = next((r for r, _, hb in log if hb <= 0), None)
    ttd = next((r for r, ha, _ in log if ha <= 0), None)
    return ttk, ttd, log


def simulate(cfg, rep, max_rounds):
    rep.say()
    rep.say("═" * 72)
    rep.say("  ② 전투 시뮬 — 해석적(2d6 = 36가지). 몬테카를로 없음")
    rep.say("═" * 72)
    sim_ttk(cfg, rep, max_rounds)
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
         Fighter(cfg, "늑대", "삼류", weapon="맨손", is_npc=True), True),
        ("이류 무인 vs 산길 도적(졸개 — npcs 실 데이터)",
         Fighter(cfg, "이류 무인", "이류", weapon="검"),
         Fighter(cfg, "산길 도적", bandit_realm, weapon="도", is_npc=True,
                 skill=bandit_skill, stats=bandit_stats), False),
        ("일류 무인 vs 맹수(호랑이 = 일류 상당)",
         Fighter(cfg, "일류 무인", "일류", weapon="검", naegong=1.0),
         Fighter(cfg, "맹수", "일류", weapon="맨손", is_npc=True), True),
        ("절정 무인 vs 절정 고수",
         Fighter(cfg, "절정 무인", "절정", weapon="검"),
         Fighter(cfg, "절정 고수", "절정", weapon="검", is_npc=True), False),
    ]


def sim_ttk(cfg, rep, max_rounds):
    rep.head("TTK — 몇 합에 끝나는가 (3합 미만 = 전투가 없다 / 20합 초과 = 지루하다)")
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

    # 일류급 무공 위력이 대체값이라는 사실을 드러낸다
    if any(f.tpower_assumed for _, f, _, _ in standard_fighters(cfg)):
        rep.warn("위 표의 일류 무인 피해에는 '일류급 무공 위력' 대체값 2 가 들어갔다 — "
                 "combat.yml technique_power 에 일류급이 없어서다 (① 린트 참조). "
                 "실제 수치가 정해지면 이 줄의 TTK 는 바뀐다")


def sim_energy_curve(cfg, rep, max_rounds):
    rep.head("내력 곡선 — 일류가 발경만 써도 몇 합에 고갈되는가")
    bands = dig(cfg, "internal_energy.yml", "cost_bands", default={}) or {}
    bal = mid(dig(bands, "발경", "cost"), 1)
    depleted = str(dig(cfg, "internal_energy.yml", "internal_energy", "depleted", "state", default="내공 고갈"))
    dep_pen = num(dig(cfg, "judgment.yml", "situation_modifiers", "condition", "내공_고갈"), -2)
    rep.say(f"     발경 = 내력 {bal:g}/합 · 고갈 = '{depleted}'(판정 {dep_pen:g}) + 다운캐스트('맨 기술')")
    rep.say(f"     전투 중 회복 = {dig(cfg, 'internal_energy.yml', 'internal_energy', 'recovery', 'in_combat', default='불가')}")
    rep.say("")
    rep.say("       내공  내력풀   발경 지속 합수    검기_참격(3)   호신강기(전개2+유지2)")
    for ng in (0.33, 1.0, 2.0, 3.0, 5.0, 7.0):
        p = pool_of(ng)
        b = int(p // bal) if bal else 0
        gi = int(p // 3)
        hosin = int((p - 2) // 2) if p >= 2 else 0
        rep.say(f"       {ng:>4.2f}  {p:>5}   {b:>10}합   {gi:>10}회   {hosin:>12}합")

    p_first = pool_of(1.0)
    rounds = int(p_first // bal) if bal else 0
    rep.say("")
    if rounds < 3:
        rep.fail(f"일류(축기 1년, 내공 1.0 → 내력 {p_first})가 발경만 써도 {rounds}합에 고갈된다 — "
                 f"전투는 평균 5~9합인데 내력은 3합을 못 간다. 고갈 후 판정 {dep_pen:g} + 다운캐스트 = "
                 f"발경을 쓴 대가로 나머지 전투를 페널티로 치른다")
    else:
        rep.say(f"     일류(내공 1.0 → 내력 {p_first}): 발경 {rounds}합 지속 후 고갈")

    p_bloom = pool_of(1.0 / 3.0)
    if p_bloom <= bal:
        rep.fail(f"개화 직후(내공 0.33 → 내력 {p_bloom}) = 발경 {int(p_bloom // bal)}회. "
                 f"'개화의 보상'인 발경이 전투당 한 번이다 — 자원 관리가 아니라 형벌")

    # 다운캐스트로 계속 싸울 수 있는가 (고갈 후 능력)
    p1 = Fighter(cfg, "일류(고갈)", "일류", weapon="검", naegong=1.0)
    e1 = Fighter(cfg, "맹수", "일류", weapon="맨손", is_npc=True)
    h_full, d_full, _ = strike(p1, e1)
    h_dep, d_dep, _ = strike(p1, e1, att_pen=int(dep_pen))
    drop = (d_full - d_dep) / d_full * 100 if d_full else 0
    ttk_full, _, _ = duel(p1, e1, max_rounds)
    ttk_dep, ttd_dep, _ = duel(p1, e1, max_rounds, a_mod=int(dep_pen))
    rep.say("")
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
    rep.say("       → 격이 위력 +1 만 가져도 피해는 두 자릿수 % 로 움직인다. 지금은 0 이다")

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


def sim_weapon_grades(cfg, rep, max_rounds):
    rep.head("무기 등급의 값 — 판정 보정 0~1 vs 무기 파괴")
    we = dig(cfg, "equipment.yml", "weapon_grades", default={}) or {}
    breaks_at = int(num(dig(cfg, "qi_manifestation.yml", "weapon_break", "rule", "1격_초과", "breaks_at"), 3))
    trigger = str(dig(cfg, "qi_manifestation.yml", "weapon_break", "trigger", default=""))

    p = Fighter(cfg, "절정 무인", "절정", weapon="검")
    e = Fighter(cfg, "절정 고수", "절정", weapon="검", is_npc=True)

    rep.say("     [A] 판정 보정만 봤을 때 (범철 0 · 정련 0 · 보병 +1 · 신병 +1)")
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
    h1, d1, _ = strike(p, e, mod=1)
    h0, d0, _ = strike(p, e, mod=0)
    dmg_delta = (d1 - d0) / d0 * 100 if d0 else 0
    if dmg_delta < 5:
        rep.warn(f"보병(+1)의 피해 기여 {dmg_delta:+.1f}% — 5% 문턱 아래. 판정 보정만 보면 무기 등급은 장식이다 "
                 f"(설계 의도와 일치: equipment.yml 'note: 가치는 보정이 아니라 생존')")
    else:
        rep.warn(f"보병(+1)의 피해 기여 {dmg_delta:+.1f}% — 판정 보정 +1 이 피해를 {dmg_delta:.0f}% 올린다. "
                 f"equipment.yml 대원칙('장비는 격차가 아니라 속성과 서사를 만든다')과 어긋난다: "
                 f"2d6 판정에서 +1 은 명중률 {h0 * 100:.1f}%→{h1 * 100:.1f}% 이동 + 마진 상승의 이중 효과다. "
                 f"게다가 격의 위력이 0 인 현재 규칙에서는 **보병 한 자루가 강기보다 강하다**")

    rep.say("")
    rep.say(f"     [B] 무기 파괴 — 검기(절정) 상대로 '막기'를 고른다면 (trigger: {trigger})")
    rep.say(f"       범철(감당 발경) vs 검기 = 1격 초과 → {breaks_at}격돌째 파괴")
    rep.say(f"       정련(감당 검기) vs 검기 = 감당 이상 → 손상 0")
    rep.say("")
    # 범철이 부러진 뒤: 무기 위력 검(3) → 맨손(1), 무공 다운캐스트 → 무공 위력 0, 재무장 = 행동 1개(1합 손실)
    broken = Fighter(cfg, "절정(파검)", "절정", weapon="맨손")
    broken.tpower = 0.0     # after_break: 무기 요구 무공 다운캐스트 = 무공 위력 보정 상실
    _, d_ok, _ = strike(p, e)
    _, d_br, _ = strike(broken, e)
    loss = (d_ok - d_br) / d_ok * 100 if d_ok else 0
    t_ok, _, _ = duel(p, e, max_rounds)
    t_br, td_br, _ = duel(broken, e, max_rounds)
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
    rep.head("협공 보정 — 3인 협공은 1인의 몇 배인가 (캡 +3)")
    per = int(num(dig(cfg, "combat.yml", "attack", "gang_up", "per_extra_attacker"), 1))
    cap = int(num(dig(cfg, "combat.yml", "attack", "gang_up", "max"), 3))
    party_cap = int(num(dig(cfg, "party.yml", "combat_coop", "협공_보정", "cap"), 3))
    jin_cap = int(num(dig(cfg, "party.yml", "combat_coop", "합격진", "example_maehwa_geomjin", "effect", "협공_상한"), 5))

    p = Fighter(cfg, "이류 무인", "이류", weapon="검")
    e = Fighter(cfg, "이류 무인", "이류", weapon="검", is_npc=True)      # 동수
    hi = Fighter(cfg, "절정 고수", "절정", weapon="검", is_npc=True)     # 격상
    rep.say(f"     보정 {per:+d}/추가 인원 · 캡 {cap} (party.yml 캡 {party_cap} · 매화검진 상한 {jin_cap})")
    rep.say(f"     기준: 동수 표적(이류 NPC, 내구 {e.dur}) — 공격자 이류 무인 N인")
    rep.say("")
    rep.say("       인원  보정   1인 명중률  1인 피해/합  총 피해/합   1인 대비   TTK")
    base_total = None
    for n in (1, 2, 3, 4, 5, 6):
        mod = min((n - 1) * per, cap)
        h, d, _ = strike(p, e, mod=mod)
        total = d * n
        if base_total is None:
            base_total = total
        t, td, _ = duel(p, e, max_rounds, a_mod=mod, a_attacks=n)
        ratio = total / base_total if base_total else 0
        rep.say(f"       {n:>3}인  {mod:>+3}   {h * 100:8.1f}%  {d:>10.2f}  {total:>10.2f}   "
                f"{ratio:>6.2f}배   {str(t) + '합' if t else '>' + str(max_rounds) + '합':>5}")

    mod3 = min(2 * per, cap)
    _, d1, _ = strike(p, e, mod=0)
    _, d3, _ = strike(p, e, mod=mod3)
    ratio3 = (d3 * 3) / d1 if d1 else 0
    rep.say("")
    rep.say(f"     3인 협공 = 1인의 {ratio3:.2f}배 (인원 3배 × 개인 피해 {(d3 / d1 - 1) * 100:+.0f}%) "
            f"— 1인당 효율 {ratio3 / 3:.2f}배")
    if ratio3 > 4.5:
        rep.warn(f"3인 협공이 동수 상대에게 1인의 {ratio3:.2f}배 — 초선형(3배 초과)")
    else:
        rep.ok(f"3인 협공 {ratio3:.2f}배 — 인원수(3배) 근방. 동수 상대에서는 캡 {cap} 이 제 몫을 한다")

    # 격상 표적 — 명중률 절벽에서 보정이 폭발한다
    _, dh1, _ = strike(p, hi, mod=0)
    _, dh3, _ = strike(p, hi, mod=mod3)
    _, dh4, _ = strike(p, hi, mod=cap)
    ratio_hi3 = (dh3 * 3) / dh1 if dh1 else float("inf")
    rep.say("")
    rep.say(f"     [격상 표적] 이류 → 절정 고수(내구 {hi.dur}): "
            f"1인 명중 {strike(p, hi)[0] * 100:.1f}% · 피해/합 {dh1:.2f}")
    rep.say(f"       3인 협공(보정 {mod3:+d}) → 총 {dh3 * 3:.2f}/합 = 1인의 {ratio_hi3:.1f}배 · "
            f"4인(캡 {cap:+d}) → 총 {dh4 * 4:.2f}/합")
    h_solo = strike(p, hi)[0] * 100
    h_cap = strike(p, hi, mod=cap)[0] * 100
    rep.warn(f"협공 보정이 '명중률 절벽' 위에서 초선형으로 터진다 — 이류 1인의 절정 상대 명중률은 "
             f"{h_solo:.1f}%(마진 ≥0 이 2d6 최대치에서만)인데 캡 {cap:+d} 이 붙으면 {h_cap:.1f}% — "
             f"{h_cap / h_solo:.0f}배다. 4인 협공의 총 피해는 1인의 {(dh4 * 4) / dh1:.0f}배 "
             f"({dh1:.2f} → {dh4 * 4:.2f}/합). 캡 {cap} 은 '보정 총량'을 막을 뿐, 그 보정이 절벽 위에서 갖는 "
             f"**곱셈 효과**를 막지 못한다 (경지 격차의 벽은 머릿수 앞에서 얇다)")

    # 동수 상대 다인 협공의 TTK — 전투가 남아 있는가
    for n in (3, 4):
        mod = min((n - 1) * per, cap)
        t, _, _ = duel(p, e, max_rounds, a_mod=mod, a_attacks=n)
        if t is not None and t < 3:
            rep.fail(f"{n}인 협공(보정 {mod:+d})이 동수 표적을 {t}합에 눕힌다 — TTK 3합 미만 = 전투가 없다. "
                     f"동행 최대 5인(party.yml)인데 {n}인만 모여도 동급 상대와의 전투가 사라진다 "
                     f"(4인이면 1합)")
            break

    # 캡의 실제 물림 지점
    saturate = cap // per + 1 if per else 0
    rep.say(f"     보정 캡({cap})은 {saturate}인째에 포화 — 그 이상은 순수 인원수 선형 증가뿐이다 "
            f"(6인이 5인보다 강한 이유는 보정이 아니라 머릿수)")
    # 실제 조우 — 산길 도적 매복 (count_hint: 4~6명)
    npcs = dig(cfg, "npcs/cheongha_npcs.yml", "npcs", default={}) or {}
    bs = (npcs.get("north_road_bandit") or {}).get("stats") or {}
    bandit = Fighter(cfg, "산길 도적", "삼류", weapon="도", is_npc=True,
                     skill=num(bs.get("도법"), 1), stats=bs)
    hero = Fighter(cfg, "이류 무인", "이류", weapon="검")
    rep.say("")
    rep.say("     [실전] 산길 도적 매복 (npcs count_hint 4~6명) vs 이류 무인 1인")
    rep.say("       도적 수  협공 보정  도적 총 피해/합   이류 피해/합   이류 생존 합수")
    for n in (1, 4, 5, 6):
        mod = min((n - 1) * per, cap)
        _, db, _ = strike(bandit, hero, mod=mod)
        _, dh, _ = strike(hero, bandit)
        rounds_alive = hero.dur / (db * n) if db * n else 99
        rep.say(f"       {n:>5}인  {mod:>+7}   {db * n:>12.2f}   {dh:>11.2f}   {rounds_alive:>10.1f}합")
    _, db5, _ = strike(bandit, hero, mod=min(4 * per, cap))
    alive5 = hero.dur / (db5 * 5) if db5 else 99
    need5 = 5 * bandit.dur / strike(hero, bandit)[1] if strike(hero, bandit)[1] else 99
    if alive5 < need5:
        rep.fail(f"도적 5인 매복은 이류 무인을 {alive5:.1f}합에 눕힌다 — 5인을 다 베는 데는 {need5:.1f}합이 필요하다. "
                 f"졸개 5인 = 이류 확살. 협공 캡 {cap} 은 개별 보정만 막을 뿐, 머릿수의 선형 누적을 막지 않는다 "
                 f"(전의 붕괴 규칙이 수치를 갖지 않으므로 도적은 죽을 때까지 물러나지도 않는다)")
    else:
        rep.ok(f"도적 5인 매복: 이류 무인이 {need5:.1f}합에 소탕 (생존 여유 {alive5:.1f}합)")

    if jin_cap > cap:
        _, d_jin, _ = strike(p, e, mod=jin_cap)
        rep.say(f"     매화검진(협공 상한 {jin_cap}): 5인 피해/합 {d_jin * 5:.2f} = "
                f"평협공 5인({strike(p, e, mod=cap)[1] * 5:.2f}) 대비 "
                f"{(d_jin * 5) / (strike(p, e, mod=cap)[1] * 5) * 100 - 100:+.0f}% — "
                f"문파 비전 진법의 값이 이만큼이다")


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
        if rr == "일류" and ng == 0:
            ng = 1.0
        p = pool_of(ng)
        tp = tech_power(cfg, art.get("grade"))
        reasons = []
        if p < cost:
            reasons.append(f"내력 풀 {p} < 코스트 {cost:g} (시전 불가)")
        if tp == 0:
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
#  진입점
# ══════════════════════════════════════════════════════════════════════════════

def main():
    ap = argparse.ArgumentParser(description="혼천 전투·무공 밸런스 검수")
    ap.add_argument("--lint-only", action="store_true", help="① 전투 정합 린트만")
    ap.add_argument("--sim-only", action="store_true", help="② 전투 시뮬만")
    ap.add_argument("--rounds", type=int, default=25, help="TTK 상한 합 수 (기본 25)")
    args = ap.parse_args()

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
