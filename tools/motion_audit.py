#!/usr/bin/env python3
"""모션 감사 — 무공이 눈에 보이는가를 재는 자.

맵에는 `/혼천 검수`, 팩에는 `texture_audit.py`, 전투에는 `combat_audit.py` 가 있다.
**모션**에는 없었다. 무공을 배워도 눈으로는 구별되지 않았다 — 격(格)별로만 파티클이 갈렸으니까.

이 도구는 config/ 를 읽어 여섯 가지를 잰다:

  ① 커버리지  — skills.yml · skill_mechanics.yml · qi_manifestation.yml · ultimate_arts.yml 의
                모든 무공·초식·격·형태·오의가 skill_motion.yml 에 모션을 갖는가 (목표 100%)
  ② 궤적 정합 — 보이는 모양(trail)과 맞는 모양(hitbox type)이 같은가
                【보이는 것과 맞는 것이 다르면 그건 거짓말이다】
  ③ 격 사다리 — 격이 오를수록 파티클 수·밝기가 **단조 증가**하는가
                (강해진 것이 눈에 덜 띄면 격은 장식이다)
  ④ 팔레트    — 등록된 바닐라 파티클 13종 밖의 것을 쓰지 않는가 (팩이 못 따라간 획은 없어야 한다)
  ⑤ 예산      — 한 지점·한 틱 상한, 한 시전 총량 (performance.yml F-P1 을 넘지 않는가)
  ⑥ 배선      — 등록부의 사건이 코드에 배선됐는가 · 코드에 파티클·소리가 하드코딩됐는가 (등록제 규약)

config 를 고치지 않는다 — 재기만 한다.

사용법:
    python3 tools/motion_audit.py                 # 전체
    python3 tools/motion_audit.py --coverage-only

외부 라이브러리 없음 (game_audit.py 의 YAML 서브셋 파서·Report 를 그대로 계승).
종료 코드: 위반(❌) 1건 이상이면 1, 아니면 0.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from game_audit import (  # noqa: E402  — 문법·출력 형식 계승 (읽기 전용 재사용)
    FAIL,
    Report,
    YamlError,
    dig,
    load_all,
)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MVT = os.path.join(ROOT, "server-mvt", "src", "main", "java", "com", "honcheon", "mvt")

# 비타격형 히트박스 — global_rules.hitbox_types 에는 없지만 skill_mechanics 가 type 으로 쓴다
NON_HIT_TYPES = ["태세", "가드태세", "시전"]
SOUND_KEY = re.compile(r"^[a-z0-9_]+(\.[a-z0-9_]+)+$")
# 오의 hitbox → 궤적 (반격 오의는 벨 것을 찾지 않는다 — 기다리는 태세다)
ULT_TRAIL = {"원": "원", "선": "선", "반격_오의": "가드태세"}


# ══════════════════════════════════════════════════════════════════════════════
#  판독 도우미
# ══════════════════════════════════════════════════════════════════════════════

def mech_types(spec):
    """무공 하나의 초식별 히트박스 type 목록 (콤보면 여러 칸, 단발이면 한 칸)."""
    combo = spec.get("combo")
    if isinstance(combo, list):
        return [str(h.get("type", "호")) for h in combo]
    return [str(spec.get("type", "호"))]


def mech_frames(spec):
    """초식별 프레임 [선딜, 지속, 후딜]."""
    combo = spec.get("combo")
    if isinstance(combo, list):
        return [list(h.get("frames", [0, 0, 0])) for h in combo]
    return [list(spec.get("frames", [0, 0, 0]))]


def steps_of(motion):
    s = motion.get("steps")
    return s if isinstance(s, list) else []


def particles_in(node, out):
    """등록부 전체에서 파티클 이름을 긁는다 (팔레트 대조용)."""
    if isinstance(node, dict):
        for k, v in node.items():
            if k in ("particle", "arc", "thrust", "trail", "beam", "core", "swing") \
                    and isinstance(v, str):
                out.add(v)
            elif k == "trail" and isinstance(v, str):
                out.add(v)
            else:
                particles_in(v, out)
    elif isinstance(node, list):
        for v in node:
            particles_in(v, out)
    return out


def sounds_in(node, out):
    if isinstance(node, dict):
        if "key" in node and isinstance(node["key"], str):
            out.add(node["key"])
        for v in node.values():
            sounds_in(v, out)
    elif isinstance(node, list):
        for v in node:
            sounds_in(v, out)
    return out


def form_leaves(forms):
    """qi_manifestation.yml forms 의 잎 = 실제 형태 (note 같은 서술 키는 뺀다)."""
    leaves = []
    for group, body in (forms or {}).items():
        if not isinstance(body, dict):
            continue
        for name, spec in body.items():
            if isinstance(spec, dict):
                leaves.append(name)
    return leaves


# ══════════════════════════════════════════════════════════════════════════════
#  ① 커버리지 — 빠진 무공이 없는가
# ══════════════════════════════════════════════════════════════════════════════

def audit_coverage(cfg, mo, rep):
    rep.head("① 커버리지 — 등록되지 않은 모션은 존재하지 않는 모션이다")

    arts = dig(cfg, "skills.yml", "martial_arts", default={}) or {}
    mechs = dig(cfg, "skill_mechanics.yml", "skills", default={}) or {}
    grades = dig(cfg, "qi_manifestation.yml", "grades", default={}) or {}
    forms = dig(cfg, "qi_manifestation.yml", "forms", default={}) or {}
    ults = dig(cfg, "ultimate_arts.yml", "legacy_arts", default={}) or {}

    m_skills = mo.get("skills", {}) or {}
    m_grades = mo.get("grades", {}) or {}
    m_forms = mo.get("forms", {}) or {}
    m_ults = mo.get("ultimates", {}) or {}

    total = hit = 0

    # 카탈로그 무공 — 액션 데이터가 있는 것은 모션도 있어야 한다
    missing_mech = [k for k in arts if k not in mechs]
    rep.verdict(not missing_mech,
                f"카탈로그 {len(arts)}종 → 액션 데이터"
                + ("" if not missing_mech else f" — 누락 {missing_mech}"))

    for sid in mechs:
        total += 1
        if sid in m_skills:
            hit += 1
        else:
            rep.fail(f"무공 모션 누락: {sid} (skill_mechanics 에 있는데 skill_motion 에 없다)")

    # 초식(콤보 칸) 단위 커버리지 — 3타 검법이 모션 한 줄이면 3타가 다 같아 보인다
    step_total = step_hit = 0
    for sid, spec in mechs.items():
        types = mech_types(spec)
        steps = steps_of(m_skills.get(sid, {}))
        step_total += len(types)
        if len(steps) != len(types):
            rep.fail(f"초식 수 불일치: {sid} — 액션 {len(types)}칸 vs 모션 {len(steps)}칸")
            step_hit += min(len(steps), len(types))
        else:
            step_hit += len(types)

    for g in grades:
        total += 1
        if g in m_grades:
            hit += 1
        else:
            rep.fail(f"격 모션 누락: {g}")

    leaves = form_leaves(forms)
    for f in leaves:
        total += 1
        if f in m_forms:
            hit += 1
        else:
            rep.fail(f"형태 모션 누락: {f} (qi_manifestation forms)")

    for u in ults:
        total += 1
        if u in m_ults:
            hit += 1
        else:
            rep.fail(f"오의 모션 누락: {u}")

    # 미참조 — 등록부에만 있고 아무도 안 부르는 키
    orphan = [k for k in m_skills if k not in mechs]
    if orphan:
        rep.fail(f"미참조 모션: {orphan} (skill_mechanics 에 없는 무공)")
    # 외공기는 '격'이 아니라 소모 밴드다 (internal_energy.yml cost_bands) — 사다리의 0번 칸으로 등록한다
    bands = dig(cfg, "internal_energy.yml", "cost_bands", default={}) or {}
    for g in m_grades:
        if g in grades or g in bands:
            continue
        rep.fail(f"미참조 격 모션: {g} (qi_manifestation grades · internal_energy cost_bands 어느 쪽에도 없다)")
    for u in m_ults:
        if u not in ults:
            rep.fail(f"미참조 오의 모션: {u}")

    pct = 100.0 * hit / total if total else 0.0
    spct = 100.0 * step_hit / step_total if step_total else 0.0
    rep.verdict(hit == total,
                f"커버리지 {hit}/{total} = {pct:.1f}%"
                f"  (무공 {len(mechs)} · 격 {len(grades)} · 형태 {len(leaves)} · 오의 {len(ults)})")
    rep.verdict(step_hit == step_total,
                f"초식(콤보 칸) 커버리지 {step_hit}/{step_total} = {spct:.1f}%"
                " — 3타 검법의 세 타는 서로 달라야 한다")
    return pct, spct


# ══════════════════════════════════════════════════════════════════════════════
#  ② 궤적 정합 — 보이는 모양 = 맞는 모양
# ══════════════════════════════════════════════════════════════════════════════

def audit_trajectory(cfg, mo, rep):
    rep.head("② 궤적 정합 — 보이는 것과 맞는 것이 다르면 그건 거짓말이다")

    mechs = dig(cfg, "skill_mechanics.yml", "skills", default={}) or {}
    hitbox_types = dig(cfg, "skill_mechanics.yml", "global_rules", "hitbox_types", default=[]) or []
    ults = dig(cfg, "ultimate_arts.yml", "legacy_arts", default={}) or {}
    traj = mo.get("trajectories", {}) or {}
    m_skills = mo.get("skills", {}) or {}
    m_ults = mo.get("ultimates", {}) or {}
    m_forms = mo.get("forms", {}) or {}

    missing = [t for t in list(hitbox_types) + NON_HIT_TYPES if t not in traj]
    rep.verdict(not missing,
                f"히트박스 {len(hitbox_types)}종 + 비타격 {len(NON_HIT_TYPES)}종 → 궤적 등록"
                + ("" if not missing else f" — 누락 {missing}"))

    bad = 0
    for sid, spec in mechs.items():
        types = mech_types(spec)
        steps = steps_of(m_skills.get(sid, {}))
        for i, t in enumerate(types):
            if i >= len(steps):
                break
            trail = str(steps[i].get("trail", ""))
            if trail != t:
                bad += 1
                rep.fail(f"궤적 불일치: {sid}[{i + 1}타] — 히트박스 '{t}' 인데 궤적 '{trail}'")
            elif trail not in traj:
                bad += 1
                rep.fail(f"미등록 궤적: {sid}[{i + 1}타] — '{trail}' 이 trajectories 에 없다")
    rep.verdict(bad == 0, f"초식 궤적 ↔ 히트박스 일치 (불일치 {bad}건)")

    # 오의 — ultimate_arts type.hitbox / type.form
    ubad = 0
    for uid, spec in ults.items():
        t = spec.get("type", {}) or {}
        want = ULT_TRAIL.get(str(t.get("hitbox") or t.get("form") or ""))
        got = str((m_ults.get(uid) or {}).get("trail", ""))
        if want and got != want:
            ubad += 1
            rep.fail(f"오의 궤적 불일치: {uid} — 정본 '{t.get('hitbox') or t.get('form')}' → '{want}' 인데 '{got}'")
    rep.verdict(ubad == 0, f"오의 궤적 ↔ ultimate_arts hitbox 일치 (불일치 {ubad}건)")

    # 발출(쏨) — qi_manifestation forms.쏨.hitbox 서술("선(참격) 또는 시(투사체)")과 궤적
    shot_hit = str(dig(cfg, "qi_manifestation.yml", "forms", "쏨", "검기_참격", "hitbox", default=""))
    shot_trail = str((m_forms.get("검기_참격") or {}).get("trail", ""))
    rep.verdict(shot_trail and shot_trail in shot_hit,
                f"발출 궤적 '{shot_trail}' ↔ forms.쏨.hitbox '{shot_hit}'")

    # 미참조 궤적
    used = {str(s.get("trail")) for m in m_skills.values() for s in steps_of(m)}
    used |= {str(u.get("trail")) for u in m_ults.values()}
    used |= {str(f.get("trail")) for f in m_forms.values() if f.get("trail")}
    unused = [t for t in traj if t not in used]
    if unused:
        rep.warn(f"미참조 궤적: {unused} (등록됐지만 아무 초식도 쓰지 않는다)")
    return bad + ubad


# ══════════════════════════════════════════════════════════════════════════════
#  ③ 격의 사다리 — 시각적 단조 증가
# ══════════════════════════════════════════════════════════════════════════════

def audit_ladder(cfg, mo, rep):
    rep.head("③ 격의 사다리 — 강해질수록 눈에 띄어야 한다 (단조 증가)")

    grades = dig(cfg, "qi_manifestation.yml", "grades", default={}) or {}
    m_grades = mo.get("grades", {}) or {}

    # rank 정합 — 외공기는 qi_manifestation 에 없다 (격이 아니라 밴드다). rank 0 으로 선다
    bad = 0
    for g, spec in grades.items():
        mg = m_grades.get(g)
        if not mg:
            continue
        if int(mg.get("rank", -1)) != int(spec.get("rank", -2)):
            bad += 1
            rep.fail(f"격 rank 불일치: {g} — 정본 {spec.get('rank')} vs 모션 {mg.get('rank')}")
    rep.verdict(bad == 0, f"격 rank ↔ qi_manifestation 정본 일치 (불일치 {bad}건)")

    order = sorted(m_grades.items(), key=lambda kv: int(kv[1].get("rank", 0)))
    axes = [
        ("밝기(brightness)", lambda s: int(s.get("brightness", 0))),
        ("응집 파티클(charge)", lambda s: int((s.get("charge") or {}).get("count", 0))),
        ("타격 파티클(impact)", lambda s: int((s.get("impact") or {}).get("count", 0))),
        ("두름 잔광(aura)", lambda s: int((s.get("aura") or {}).get("count", 0))),
    ]
    fails = 0
    for label, get in axes:
        seq = [(g, get(s)) for g, s in order]
        mono = all(seq[i][1] < seq[i + 1][1] for i in range(len(seq) - 1))
        line = " < ".join(f"{g} {v}" for g, v in seq)
        if mono:
            rep.ok(f"{label}: {line}")
        else:
            fails += 1
            rep.fail(f"{label} 단조 증가 위반: {line}")

    # 소리 — 격마다 타격음이 있어야 한다 (눈을 감아도 격이 읽힌다)
    silent = [g for g, s in order if not (s.get("sounds") or {}).get("impact")]
    rep.verdict(not silent, "격마다 타격음 등록" + ("" if not silent else f" — 없음: {silent}"))
    # 응집음 — 외공기 말고는 전부 예고음이 있어야 한다 (응집을 보고/듣고 물러설 수 있다)
    quiet = [g for g, s in order
             if int(s.get("rank", 0)) > 0 and not (s.get("sounds") or {}).get("charge")]
    rep.verdict(not quiet, "격(외공기 제외)마다 응집음 등록" + ("" if not quiet else f" — 없음: {quiet}"))
    return bad + fails + len(silent) + len(quiet)


# ══════════════════════════════════════════════════════════════════════════════
#  ④ 팔레트 — 팩이 다시 그릴 13장 밖으로 나가지 않는다
# ══════════════════════════════════════════════════════════════════════════════

def audit_palette(mo, rep):
    rep.head("④ 팔레트 — 팩이 못 따라간 획은 없어야 한다 (수묵 규약)")

    palette = mo.get("palette", {}) or {}
    used = particles_in({k: v for k, v in mo.items() if k != "palette"}, set())
    used.discard(None)
    # trajectories/skills 의 'trail' 은 궤적 이름(호·선…)이지 파티클이 아니다 — 팔레트 대조에서 뺀다
    traj_names = set(mo.get("trajectories", {}) or {})
    used = {p for p in used if p not in traj_names}

    unknown = sorted(p for p in used if p not in palette)
    rep.verdict(not unknown,
                f"파티클 참조 {len(used)}종 ⊆ 팔레트 {len(palette)}종"
                + ("" if not unknown else f" — 팔레트 밖: {unknown}"))

    unused = sorted(p for p in palette if p not in used)
    if unused:
        rep.warn(f"미사용 팔레트: {unused} (팩이 그릴 이유가 없는 텍스처)")

    # 채색 예외는 명시된 것뿐이어야 한다 (수묵 규약)
    colored = [p for p in used if p in ("crimson_spore",)]
    rep.ok(f"채색 예외 {len(colored)}종 — {colored or '없음'} (마공의 혈점: 예외 자체가 정보다)")
    return len(unknown)


# ══════════════════════════════════════════════════════════════════════════════
#  ⑤ 예산 — 한 시전이 수백 개를 뿌리면 서버가 죽는다
# ══════════════════════════════════════════════════════════════════════════════

def audit_budget(cfg, mo, rep):
    rep.head("⑤ 파티클 예산 (performance.yml F-P1)")

    view = int(dig(cfg, "performance.yml", "particles", "per_player_view_per_tick", default=600))
    cluster = int(dig(cfg, "performance.yml", "load_test", "combat_cluster_size", default=20))
    b = mo.get("budget", {}) or {}
    point_max = int(b.get("per_point_tick_max", 30))
    cast_max = int(b.get("per_cast_max", 160))
    tele_pool = int(b.get("telegraph_pool", 32))
    trail_pool = int(b.get("trail_pool", 24))
    impact_pool = int(b.get("impact_pool", 48))
    ult_tick = int(b.get("ultimate_per_tick_max", 48))
    ult_cast = int(b.get("ultimate_per_cast_max", 320))

    rep.verdict(point_max * cluster <= view,
                f"한 지점·한 틱 {point_max} × 군집 {cluster}인 = {point_max * cluster}"
                f" ≤ 시야 예산 {view}/틱")

    grades = mo.get("grades", {}) or {}
    events = mo.get("events", {}) or {}
    crit = int((events.get("대성공") or {}).get("count", 0))

    # 가장 비싼 한 지점 = 최상위 격의 타격 + 강조 + 대성공
    worst_g, worst_n = None, 0
    for g, s in grades.items():
        n = int((s.get("impact") or {}).get("count", 0)) + int((s.get("accent") or {}).get("count", 0)) + crit
        if n > worst_n:
            worst_g, worst_n = g, n
    rep.verdict(worst_n <= point_max,
                f"가장 비싼 타격 지점: {worst_g} {worst_n}개 (타격+강조+대성공) ≤ {point_max}")

    # 응집 한 발 (격 응집 + telegraph_boost 최대치)
    boost = max([int(s.get("telegraph_boost", 0))
                 for m in (mo.get("skills") or {}).values() for s in steps_of(m)] or [0])
    worst_charge = max(int((s.get("charge") or {}).get("count", 0)) for s in grades.values()) + boost
    rep.verdict(worst_charge <= point_max,
                f"가장 비싼 응집 1회: {worst_charge}개 (최상위 격 + 텔레그래프 가중 {boost}) ≤ {point_max}")

    # 한 시전 총량 (단일 대상 최악치)
    worst_cast = tele_pool + trail_pool + 1 + worst_n
    rep.verdict(worst_cast <= cast_max,
                f"한 시전 총량 최악치: 응집풀 {tele_pool} + 궤적풀 {trail_pool} + 강조 1"
                f" + 타격 {worst_n} = {worst_cast} ≤ {cast_max}")

    # 다중 대상 — 타격 풀을 나눠 쓴다
    max_targets = int(dig(cfg, "skill_mechanics.yml", "global_rules", "max_targets_default", default=8))
    per_target = max(int(b.get("min_impact_per_target", 3)), impact_pool // max_targets)
    rep.verdict(per_target * max_targets <= point_max * max_targets,
                f"광역 {max_targets}인: 타격풀 {impact_pool} ÷ {max_targets} = 대상당 {per_target}개"
                f" (하한 {b.get('min_impact_per_target')} — 맞았다는 사실은 언제나 보인다)")

    # 궤적 — 점당 개수 × 표본점이 풀을 넘으면 코드가 솎아야 한다 (등록부는 그 상한을 알린다)
    traj = mo.get("trajectories", {}) or {}
    over = []
    for m_id, m in (mo.get("skills") or {}).items():
        for i, s in enumerate(steps_of(m)):
            pts = int((traj.get(str(s.get("trail"))) or {}).get("points", 0))
            n = pts * int(s.get("count", 1))
            if n > trail_pool:
                over.append(f"{m_id}[{i + 1}타] {n}")
    # 궤적이 풀을 넘으면 엔진이 표본점을 솎는다 (SkillListener.trail: points = trail_pool / per).
    # 위반이 아니라 설계다 — 사거리 12m 선을 그려도 파티클은 24개를 넘지 않는다.
    rep.ok(f"궤적 ≤ 궤적풀 {trail_pool}: 점당 개수로 표본점을 솎는 초식 {len(over)}건"
           + (f" (예: {over[:3]})" if over else " — 없음"))

    # 오의
    ubad = 0
    for uid, u in (mo.get("ultimates") or {}).items():
        c = u.get("charge") or {}
        ring = int(c.get("ring_points", 0)) * int(c.get("per_point", 0)) + int(c.get("core_count", 0))
        burst = int((u.get("burst") or {}).get("count", 0)) + int((u.get("accent") or {}).get("count", 0))
        if ring > ult_tick or burst > ult_tick:
            ubad += 1
            rep.fail(f"오의 예산 초과: {uid} — 응집 {ring} / 폭발 {burst} > {ult_tick}")
    rep.verdict(ubad == 0,
                f"오의 {len(mo.get('ultimates') or {})}종: 응집·폭발 ≤ 한 틱 {ult_tick}"
                f" (우선권 — 생략이 아니라 감축)")
    # 오의 총량 (선딜 20틱 = 10회 응집 + 폭발 + 타격풀)
    ult_worst = 0
    for uid, u in (mo.get("ultimates") or {}).items():
        c = u.get("charge") or {}
        ring = int(c.get("ring_points", 0)) * int(c.get("per_point", 0)) + int(c.get("core_count", 0))
        frames = dig(cfg, "ultimate_arts.yml", "legacy_arts", uid, "frames", default=[0, 0, 0])
        emits = int(frames[0]) // int(b.get("telegraph_step_ticks", 2))
        total = ring * emits + int((u.get("burst") or {}).get("count", 0)) + impact_pool
        ult_worst = max(ult_worst, total)
    rep.verdict(ult_worst <= ult_cast,
                f"오의 한 시전 총량 최악치 {ult_worst} ≤ {ult_cast}")

    fails = int(worst_n > point_max) + int(worst_charge > point_max) + int(worst_cast > cast_max) \
        + ubad + int(ult_worst > ult_cast) + int(point_max * cluster > view)
    return fails


# ══════════════════════════════════════════════════════════════════════════════
#  ⑥ 무기 계열 · 소리 · 배선
# ══════════════════════════════════════════════════════════════════════════════

def audit_weapon_and_sound(cfg, mo, rep):
    rep.head("⑥ 무기 계열 · 소리 — 검은 베고 창은 찌른다")

    arts = dig(cfg, "skills.yml", "martial_arts", default={}) or {}
    registry = dig(cfg, "skills.yml", "weapon_classes", "registry", default=[]) or []
    styles = mo.get("weapon_styles", {}) or {}
    m_skills = mo.get("skills", {}) or {}

    missing = [w for w in registry if w not in styles]
    rep.verdict(not missing,
                f"무기 계열 {len(registry)}종 → 모션 등록"
                + ("" if not missing else f" — 누락 {missing}"))

    bad = 0
    for sid, m in m_skills.items():
        style = str(m.get("style", ""))
        if style not in styles:
            bad += 1
            rep.fail(f"미등록 무기 모션: {sid} — style '{style}'")
            continue
        wc = (arts.get(sid) or {}).get("weapon_class")
        if wc is None:
            continue                        # NPC 표본·심법 — 카탈로그에 없다
        allowed = wc if isinstance(wc, list) else [wc]
        if style not in allowed:
            bad += 1
            rep.fail(f"무기 계열 불일치: {sid} — 카탈로그 {allowed} vs 모션 '{style}'")
    rep.verdict(bad == 0, f"무공 style ↔ skills.yml weapon_class 일치 (불일치 {bad}건)")

    # 궤적의 '동사'가 계열마다 갈리는가 — 검(벤다)·창(찌른다)이 같은 파티클이면 구별되지 않는다
    verbs = {w: (s.get("arc"), s.get("thrust")) for w, s in styles.items()}
    same = [w for w in ("검", "창") if w in verbs]
    if len(same) == 2 and verbs["검"] == verbs["창"]:
        bad += 1
        rep.fail("검과 창의 궤적 파티클이 같다 — 베는 것과 찌르는 것이 구별되지 않는다")
    else:
        rep.ok(f"검 {verbs.get('검')} ≠ 창 {verbs.get('창')} — 베기와 찌르기가 갈린다")

    # 소리 키 형식 (바닐라 사운드 키 — Sound 는 1.21 에서 인터페이스라 문자열로 재생한다)
    # display 절은 뺀다 — 거기의 key 는 소리가 아니라 **모델 키**(honcheon:qi/…)다 (⑧이 따로 검사한다)
    keys = sorted(sounds_in({k: v for k, v in mo.items() if k != "display"}, set()))
    malformed = [k for k in keys if not SOUND_KEY.match(k)]
    rep.verdict(not malformed,
                f"바닐라 사운드 키 {len(keys)}종 형식 검사"
                + ("" if not malformed else f" — 잘못된 키 {malformed}"))

    # 같은 무공 안에서 초식마다 음높이가 갈리는가 (귀로 콤보를 센다)
    flat = []
    for sid, m in m_skills.items():
        steps = steps_of(m)
        if len(steps) < 2:
            continue
        pitches = [(s.get("sound") or {}).get("pitch") for s in steps]
        if len(set(pitches)) == 1:
            flat.append(sid)
    if flat:
        rep.warn(f"콤보 음높이가 평평하다: {flat} — 타수를 귀로 셀 수 없다")
    else:
        rep.ok("콤보마다 타수별 음높이가 다르다 — 1타·2타·마무리가 귀로 갈린다")
    return bad + len(malformed)


def audit_wiring(mo, rep):
    rep.head("⑦ 배선 — 등록제 규약 (코드에 파티클·소리를 하드코딩하지 않는다)")

    listener = os.path.join(MVT, "SkillListener.java")
    hud = os.path.join(MVT, "SkillHud.java")
    engine = os.path.join(MVT, "SkillEngine.java")
    disp = os.path.join(MVT, "SkillDisplay.java")
    src = {}
    for path in (listener, hud, engine, disp):
        try:
            with open(path, encoding="utf-8") as fh:
                src[os.path.basename(path)] = fh.read()
        except OSError:
            rep.fail(f"소스 없음: {path}")
            return 1

    fails = 0
    # 하드코딩 — Particle.X / Sound.X / Material.X 리터럴. 이름→enum 해석기(SkillHud·SkillDisplay)만 예외다
    for name in ("SkillListener.java", "SkillEngine.java", "SkillDisplay.java"):
        body = src[name]
        hard = re.findall(r"\bParticle\.[A-Z_]+", body) + re.findall(r"\bSound\.[A-Z_]+", body) \
            + re.findall(r"\bMaterial\.[A-Z_]+", body)
        if hard:
            fails += 1
            rep.fail(f"{name}: 파티클·소리·아이템 하드코딩 {len(hard)}건 — {sorted(set(hard))[:5]}")
        else:
            rep.ok(f"{name}: 파티클·소리·아이템 하드코딩 0건 (등록부만 읽는다)")

    body = src["SkillListener.java"]
    # 등록부의 사건이 코드에 배선됐는가 (등록만 하고 안 쓰면 그건 죽은 모션이다)
    dead = [e for e in (mo.get("events") or {}) if f'"{e}"' not in body]
    if dead:
        fails += 1
        rep.fail(f"미배선 사건: {dead} — 등록부에만 있고 코드가 부르지 않는다")
    else:
        rep.ok(f"사건 {len(mo.get('events') or {})}종 전부 배선됨 (SkillListener 가 이름으로 부른다)")

    if "skill_motion.yml" not in src["SkillEngine.java"]:
        fails += 1
        rep.fail("SkillEngine 이 skill_motion.yml 을 적재하지 않는다")
    else:
        rep.ok("SkillEngine 이 skill_motion.yml 을 적재한다 (단일 진실 원천)")

    # 3D 층 — 판정이 준 사거리·각도를 그대로 받는가 (등록부가 사거리를 지어내면 거짓말이 된다)
    body = src["SkillDisplay.java"]
    honest = re.search(r"boolean slash\([^)]*double range, double angle", body) \
        and "range * sw.reach()" in body
    if not honest:
        fails += 1
        rep.fail("SkillDisplay 가 히트박스(range·angle)를 받아 쓰지 않는다 — 획이 제 사거리를 지어낸다")
    else:
        rep.ok("참격선의 길이 = 판정의 range × reach(≤1.0) — 등록부는 **줄일 수만** 있다 (불변식 ㅂ)")
    return fails


# ══════════════════════════════════════════════════════════════════════════════
#  ⑧ 3D 모션 — 세 층 (손의 병기 · 날의 기 · 참격선)
# ══════════════════════════════════════════════════════════════════════════════

# 폴백 아이템 허용 목록 — 팩이 없어도 **무채(수묵)로 읽혀야 한다**. 채색 예외는 마공의 혈점뿐이다.
#   None(null) = 폴백 없음 → 팩 없는 눈에는 안 띄운다 (그 자리는 파티클이 지킨다).
#   【참격선·날의 기가 그렇다】 억지 폴백(검을 복제한 획)보다 파티클이 낫다 — 사용자가 그것을 거부했다.
ALLOWED_FALLBACK = {"IRON_SWORD", "AMETHYST_SHARD", "END_ROD", "REDSTONE", None, "None"}
ALLOWED_BASE = {"PAPER"}
ALLOWED_POSE = {"없음", "SWIMMING", "SNEAKING", "SPIN_ATTACK"}   # 바닐라가 가진 자세만 (팔다리 각도는 못 준다)
PACK = os.path.join(ROOT, "resourcepack", "assets", "honcheon")


def audit_display(cfg, mo, rep):
    rep.head("⑧ 3D 모션 — 검을 그리지 않는다. 지나간 자리를 그린다 (만화의 검격)")

    dp = mo.get("display", {}) or {}
    if not dp:
        rep.fail("display 절이 없다 — 3D 층이 등록되지 않았다")
        return 1
    b = dp.get("budget", {}) or {}
    models = dp.get("models", {}) or {}
    motions = dp.get("motions", {}) or {}
    slashes = dp.get("slashes", {}) or {}
    ink = dp.get("grade_ink", {}) or {}
    sheaths = dp.get("sheaths", {}) or {}
    swings = dp.get("swings", {}) or {}
    body = dp.get("body", {}) or {}
    blend = dp.get("blend", {}) or {}
    bind = dp.get("bind", {}) or {}
    fails = 0

    traj = mo.get("trajectories", {}) or {}
    grades = dig(cfg, "qi_manifestation.yml", "grades", default={}) or {}
    ults = dig(cfg, "ultimate_arts.yml", "legacy_arts", default={}) or {}
    forms = dig(cfg, "qi_manifestation.yml", "forms", default={}) or {}
    registry = dig(cfg, "skills.yml", "weapon_classes", "registry", default=[]) or []

    # ── 참격선 — 모든 궤적이 획을 갖는가 (null = 획이 없는 것이 옳은 자리: 투사체·태세) ──
    miss_slash = [t for t in traj if t not in slashes]
    fails += len(miss_slash)
    rep.verdict(not miss_slash,
                f"참격선 — 궤적 {len(traj)}종 전수 등록 (획 있음"
                f" {sum(1 for v in slashes.values() if v)} · 없음"
                f" {sum(1 for v in slashes.values() if not v)})"
                + ("" if not miss_slash else f" — 누락 {miss_slash}"))

    # ── 격의 먹(ink) — **외공기도 획을 그린다**. 굵기·밝기가 단조 증가하는가 ──
    m_grades = mo.get("grades", {}) or {}
    miss_ink = [g for g in m_grades if g not in ink]
    fails += len(miss_ink)
    rep.verdict(not miss_ink,
                f"격의 먹 — 격 {len(m_grades)}종 전수 (외공기 포함: **무공이 없어도 획은 뜬다**)"
                + ("" if not miss_ink else f" — 누락 {miss_ink}"))

    order = sorted(((g, s) for g, s in ink.items() if g in m_grades),
                   key=lambda kv: int((m_grades.get(kv[0]) or {}).get("rank", 0)))
    thicks = [(g, float(s.get("thickness", 0))) for g, s in order]
    mono = all(thicks[i][1] < thicks[i + 1][1] for i in range(len(thicks) - 1))
    fails += int(not mono)
    rep.verdict(mono, "획의 굵기가 격을 따라 단조 증가: "
                + " < ".join(f"{g} {v}" for g, v in thicks))
    brights = [(g, int((s.get("brightness") or [0])[0])) for g, s in order]
    bmono = all(brights[i][1] <= brights[i + 1][1] for i in range(len(brights) - 1))
    fails += int(not bmono)
    rep.verdict(bmono, "획의 밝기가 격을 따라 비감소: "
                + " ≤ ".join(f"{g} {v}" for g, v in brights))

    # ── 날의 기 — 격을 두르면 **병기에 서린다** (전의 규칙 '격_목격'의 전제) ──
    need_sheath = [g for g in m_grades if int((m_grades.get(g) or {}).get("rank", 0)) >= 1]
    miss_sh = [g for g in need_sheath if not sheaths.get(g)]
    fails += len(miss_sh)
    rep.verdict(not miss_sh,
                f"날의 기 — 발경 이상 {len(need_sheath)}종 전수 (태세만 잡아도 남이 안다)"
                + ("" if not miss_sh else f" — 누락 {miss_sh}"))

    # ── 계열의 손 · 기본 초식 — **무공이 없어도 병기는 궤적을 그린다** ──
    basic = (mo.get("basic_strike") or {}).get("by_class", {}) or {}
    miss_sw = [w for w in registry if w not in swings]
    miss_bs = [w for w in registry if w not in basic]
    fails += len(miss_sw) + len(miss_bs)
    rep.verdict(not miss_sw, f"계열의 손 — 무기 계열 {len(registry)}종 전수"
                + ("" if not miss_sw else f" — 누락 {miss_sw}"))
    rep.verdict(not miss_bs,
                f"기본 초식 — 무기 계열 {len(registry)}종 전수 (무공을 안 배운 손이 가장 흔한 손이다)"
                + ("" if not miss_bs else f" — 누락 {miss_bs}"))

    bad_trail = [(w, s.get("trail")) for w, s in basic.items()
                 if isinstance(s, dict) and str(s.get("trail")) not in traj]
    fails += len(bad_trail)
    rep.verdict(not bad_trail, "기본 초식의 궤적 ⊆ trajectories"
                + ("" if not bad_trail else f" — 미등록 {bad_trail}"))

    # ── 【불변식 ㅂ】 획은 히트박스보다 길 수 없다 ──
    over = [(w, s.get("reach")) for w, s in swings.items()
            if isinstance(s, dict) and float(s.get("reach", 0)) > 1.0]
    fails += len(over)
    rep.verdict(not over,
                "reach ≤ 1.0 — 등록부는 판정이 준 사거리를 **줄일 수만** 있다 (거짓말을 구조가 막는다)"
                + ("" if not over else f" — 초과 {over}"))
    long_span = [(w, s.get("span_ratio")) for w, s in swings.items()
                 if isinstance(s, dict) and float(s.get("span_ratio", 0)) > 1.0]
    fails += len(long_span)
    rep.verdict(not long_span, "span_ratio ≤ 1.0 — 획이 제 스윙 간격보다 오래 살면 형체가 쌓인다"
                + ("" if not long_span else f" — 초과 {long_span}"))

    # ── 몸의 자세 — 되는 것만. 조작감을 해치는 값이 등록부에 없는가 ──
    limits = body.get("limits", {}) or {}
    max_lunge = float(limits.get("max_lunge", 0.45))
    max_pose = int(limits.get("max_pose_ticks", 8))
    kick_max = float(limits.get("kick_max", 12))
    rows = {**(body.get("by_class") or {}), **(body.get("by_trail") or {})}
    rows = {k: v for k, v in rows.items() if isinstance(v, dict)}

    def beats_of(spec):
        """한 계열의 몸이 지나는 칸들 — 예비(windup) + 줄기(script)"""
        out = []
        if isinstance(spec.get("windup"), dict):
            out.append(("windup", spec["windup"]))
        for i, b in enumerate(spec.get("script") or []):
            if isinstance(b, dict):
                out.append((f"script[{i}]", b))
        return out

    bad_pose = [(k, v.get("pose")) for k, v in rows.items()
                if str(v.get("pose", "없음")) not in ALLOWED_POSE]
    bad_pose += [(f"{k}.{w}", b.get("pose")) for k, v in rows.items() for w, b in beats_of(v)
                 if b.get("pose") is not None and str(b["pose"]) not in ALLOWED_POSE]
    fails += len(bad_pose)
    rep.verdict(not bad_pose,
                f"자세 ⊆ 바닐라가 가진 자세 {sorted(ALLOWED_POSE)}"
                " (**팔다리 각도는 서버가 못 준다** — 없는 것을 등록하면 거짓말이다)"
                + ("" if not bad_pose else f" — 규약 밖 {bad_pose}"))
    over_body = [(k, v.get("lunge")) for k, v in rows.items()
                 if abs(float(v.get("lunge", 0))) > max_lunge]
    over_body += [(k, v.get("pose_ticks")) for k, v in rows.items()
                  if int(v.get("pose_ticks", 0)) > max_pose]
    over_body += [(f"{k}.{w}", b.get("lunge")) for k, v in rows.items() for w, b in beats_of(v)
                  if abs(float(b.get("lunge", 0) or 0)) > max_lunge]
    fails += len(over_body)
    rep.verdict(not over_body,
                f"전진 ≤ {max_lunge} · 자세 강제 ≤ {max_pose}틱 (미끄러지면 조준이 깨지고, 길면 몸이 굳는다)"
                + ("" if not over_body else f" — 초과 {over_body}"))

    # ── 【불변식 — 몸은 돌아온다】 회전은 반동이지 **조준 훼손이 아니다** ──
    #   예전 규약은 "시야 회전 전부 0" 이었다 (조준을 해치므로). 그러나 그것은 **공짜 축을 버리는 것**이었다.
    #   축을 켜되 못을 박는다: ① 마지막 칸은 반드시 원점 (0,0) — 회전이 **순증하지 않는다**
    #                        ② 어떤 칸도 kick_max 를 넘지 않는다 — 반동이지 멀미가 아니다
    #                        ③ 엔진이 **차분만** 더한다 (그 사이의 마우스 입력은 보존된다)
    scripted = {k: v for k, v in rows.items() if v.get("script")}
    no_home = []
    for k, v in scripted.items():
        last = [b for b in v["script"] if isinstance(b, dict)][-1]
        if float(last.get("at", 0)) < 1.0 or float(last.get("yaw", 0) or 0) != 0.0 \
                or float(last.get("pitch", 0) or 0) != 0.0:
            no_home.append((k, last))
    fails += len(no_home)
    rep.verdict(not no_home,
                f"**몸은 돌아온다** — 자세 줄기 {len(scripted)}종 전수, 마지막 칸이 at 1.0 · 회전 원점(0,0)"
                " (회전은 순증하지 않는다 = 조준 훼손이 아니라 반동이다)"
                + ("" if not no_home else f" — 안 돌아오는 몸 {no_home}"))
    over_kick = [(f"{k}.{w}", b.get("yaw"), b.get("pitch")) for k, v in rows.items()
                 for w, b in beats_of(v)
                 if abs(float(b.get("yaw", 0) or 0)) > kick_max
                 or abs(float(b.get("pitch", 0) or 0)) > kick_max]
    fails += len(over_kick)
    rep.verdict(not over_kick, f"회전 ≤ {kick_max}도 (반동이지 멀미가 아니다)"
                + ("" if not over_kick else f" — 초과 {over_kick}"))
    fails += int(limits.get("kick_recover") is not True)
    rep.verdict(limits.get("kick_recover") is True,
                "kick_recover — 엔진이 **차분만** 더하고 마지막에 정확히 되돌린다 (마우스 입력은 보존된다)")

    # ── 【눈을 시험한다】 11계열이 **정말로 다른 몸으로 서는가** ──
    #   전엔 by_class 14칸 중 자세가 바뀌는 건 3칸뿐이었고 나머지는 lunge 숫자 하나만 달랐다
    #   (= 몸이 앞으로 조금 미끄러질 뿐, 전부 같은 자세로 서 있었다). 그것을 다시 못 하게 못을 박는다.
    cls_rows = {k: v for k, v in (body.get("by_class") or {}).items()
                if isinstance(v, dict) and v.get("script")}
    sigs = {}
    for k, v in cls_rows.items():
        sigs.setdefault(json.dumps(v["script"], sort_keys=True, ensure_ascii=False), []).append(k)
    twins = [g for g in sigs.values() if len(g) > 1]
    fails += len(twins)
    rep.verdict(not twins,
                f"**계열마다 다른 몸** — 자세 줄기를 가진 {len(cls_rows)}계열이 전부 서로 다르다"
                " (전에는 lunge 숫자 하나만 달랐다: 몸은 전부 같은 자세로 서 있었다)"
                + ("" if not twins else f" — 같은 몸을 쓰는 계열 {twins}"))
    # 자세(pose)·회전·팔 — 셋 중 하나도 안 쓰는 계열은 '뻣뻣한 몸'이다 (활은 예외: 바닐라가 이미 그린다)
    stiff = [k for k, v in cls_rows.items()
             if not any(b.get("pose") or b.get("yaw") or b.get("pitch") or b.get("hand")
                        for _, b in beats_of(v))]
    fails += len(stiff)
    rep.verdict(not stiff, "뻣뻣한 몸 0 — 모든 계열이 자세·회전·팔 중 적어도 하나를 쓴다"
                + ("" if not stiff else f" — 전진만 하는 계열 {stiff}"))

    # ── ★ 암기는 **던진다** (사용자가 이름을 대어 청구한 자리) ──
    dart = (body.get("by_class") or {}).get("암기") or {}
    dbeats = [b for _, b in beats_of(dart)] if isinstance(dart, dict) else []
    release = next((b for b in dbeats if float(b.get("at", -1)) == 0.0), None)
    throws = bool(release) and float(release.get("pitch", 0) or 0) > 0 \
        and float(release.get("lunge", 0) or 0) > 0 and release.get("hand") is not None
    recock = any(float(b.get("at", 0)) > 0.4 and float(b.get("pitch", 0) or 0) < 0 for b in dbeats)
    fails += int(not (throws and recock))
    rep.verdict(throws and recock,
                "★ 암기가 **던진다** — 사출(at 0.0: 상체가 앞으로 꺾이고 pitch+ · 체중이 실리고 lunge+ · 팔이 나간다)"
                " → 따라 나감 → **다시 벼른다**(pitch−: 뒤로 젖히며 뽑는다 = 다음 던짐의 예비)"
                + ("" if throws and recock else f" — 손목만 쓴다 {dbeats}"))
    # '시'(던지고 쏨)가 by_trail 에 있으면 **계열의 몸을 먹는다** — 암기의 던짐이 영영 안 나온다
    trail_shot = (body.get("by_trail") or {}).get("시")
    fails += int(trail_shot is not None)
    rep.verdict(trail_shot is None,
                "by_trail.'시' 없음 — 던지는 몸은 **계열이 안다** (암기는 던지고 활은 선다)."
                " 궤적이 계열을 이기는 것은 몸을 통째로 던지는 궤적(돌·원)뿐이다"
                + ("" if trail_shot is None else f" — 시가 계열을 먹고 있다 {trail_shot}"))

    # ── 배선 무결 — 가리키는 모션·모델이 실재하는가 ──
    dangling = []
    for k, v in slashes.items():
        if v and v not in motions:
            dangling.append(f"slashes.{k} → {v}")
    for k, v in (sheaths or {}).items():
        if isinstance(v, dict) and str(v.get("motion")) not in motions:
            dangling.append(f"sheaths.{k} → {v.get('motion')}")
    for table, name in ((bind.get("forms", {}) or {}, "forms"),
                        (bind.get("ultimates", {}) or {}, "ultimates")):
        for k, v in table.items():
            if v and v not in motions:
                dangling.append(f"bind.{name}.{k} → {v}")
    for mid, m in motions.items():
        if str(m.get("model")) not in models:
            dangling.append(f"motions.{mid} → model {m.get('model')}")
    thr = (dp.get("throw") or {}).get("motion")
    if thr and thr not in motions:
        dangling.append(f"throw → {thr}")
    fails += len(dangling)
    rep.verdict(not dangling, f"배선 무결 — 모션 {len(motions)} · 모델 {len(models)}"
                + ("" if not dangling else f" — 끊긴 배선 {dangling}"))

    miss_u = [u for u in ults if not (bind.get("ultimates") or {}).get(u)]
    fails += len(miss_u)
    rep.verdict(not miss_u, f"오의 3D — {len(ults)}종 전수"
                + ("" if not miss_u else f" — 누락 {miss_u}"))
    need_form = []
    for group, spec in (forms or {}).items():
        if group in ("쏨", "두름_몸", "부림") and isinstance(spec, dict):
            need_form += [n for n, v in spec.items() if isinstance(v, dict)]
    miss_f = [f for f in need_form if not (bind.get("forms") or {}).get(f)]
    fails += len(miss_f)
    rep.verdict(not miss_f, f"형태 3D — 쏨·두름_몸·부림 {len(need_form)}종 전수"
                + ("" if not miss_f else f" — 누락 {miss_f}"))

    # ── 팩 대조 — 【위반이 아니라 청구서다】 팩이 없어도 파티클로 읽히는 것이 규약이다 ──
    pending, keyed = [], []
    for mid, m in models.items():
        if m.get("use_held"):
            continue                       # 던진 물건 그 자체 — 팩에 요구할 것이 없다
        key = str(m.get("key", ""))
        keyed.append(mid)
        if not key.startswith("honcheon:"):
            fails += 1
            rep.fail(f"모델 키 형식 오류: {mid} — '{key}' (honcheon:<경로> 여야 한다)")
            continue
        path = key.split(":", 1)[1]
        if not (os.path.exists(os.path.join(PACK, "items", path + ".json"))
                and os.path.exists(os.path.join(PACK, "models", "item", path + ".json"))):
            size = "·".join(str(x) for x in (m.get("size") or []))
            pending.append(f"{key}  [{size}m]")
    if pending:
        rep.warn(f"팩 미구움 모델 {len(pending)}/{len(keyed)}종 — 팩 담당 청구서 (지금은 파티클로 읽힌다):")
        for k in pending:
            rep.warn(f"    · {k}")
    else:
        rep.ok(f"모델 키 {len(keyed)}종 전부 팩에 존재")

    bad_fb = [(mid, m.get("fallback")) for mid, m in models.items()
              if not m.get("use_held") and m.get("fallback") not in ALLOWED_FALLBACK]
    fails += len(bad_fb)
    rep.verdict(not bad_fb,
                f"폴백 ⊆ {sorted(x for x in ALLOWED_FALLBACK if x)} 또는 null(파티클이 지킨다)"
                + ("" if not bad_fb else f" — 규약 밖 {bad_fb}"))
    bad_base = [(mid, m.get("base")) for mid, m in models.items()
                if not m.get("use_held") and str(m.get("base")) not in ALLOWED_BASE]
    fails += len(bad_base)
    rep.verdict(not bad_base, f"바탕 ⊆ {sorted(ALLOWED_BASE)}"
                + ("" if not bad_base else f" — 규약 밖 {bad_base}"))

    # ── 예산 (performance.yml vfx_entities) ──
    vfx_cap = int(dig(cfg, "performance.yml", "vfx_entities", "global_cap", default=120))
    vfx_life = int(dig(cfg, "performance.yml", "vfx_entities", "max_lifetime_ticks", default=60))
    cluster = int(dig(cfg, "performance.yml", "load_test", "combat_cluster_size", default=20))
    cap = int(b.get("global_cap", 120))
    life = int(b.get("max_lifetime_ticks", 60))
    degrade = int(b.get("degrade_at", 90))
    reserve = int(b.get("reserve_for_ultimate", 16))
    per_player = int(b.get("per_player_max", 8))

    rep.verdict(cap <= vfx_cap and life <= vfx_life,
                f"3D 예산 ≤ performance.yml vfx_entities — 상한 {cap}/{vfx_cap} · 수명 {life}/{vfx_life}")
    fails += int(cap > vfx_cap or life > vfx_life)
    over_life = [(mid, m.get("lifetime")) for mid, m in motions.items()
                 if int(m.get("lifetime", 0)) > life]
    fails += len(over_life)
    rep.verdict(not over_life, f"모션 수명 ≤ {life}틱"
                + ("" if not over_life else f" — 초과 {over_life}"))

    ring = max([int(m.get("count", 1)) for m in motions.values()
                if str(m.get("kind")) == "고리"] or [0])
    # 한 몸: 공격 태세(참격선 1 + 날의 기 1) **또는** 방어 태세(고리 N) — armed 는 하나다. 둘 다일 수 없다
    need_player = max(2 + 1, ring + 1)          # +1 = 던진 암기/발출 한 발
    rep.verdict(per_player >= need_player,
                f"한 몸의 상한 {per_player} ≥ max(참격선1+날의기1+투사1, 고리{ring}+투사1) = {need_player}")
    fails += int(per_player < need_player)

    slash_n = cluster                            # 참격선 1개/인 (수명 ≈ 스윙 간격)
    sheath_n = cluster                           # 날의 기 1개/인 (지속)
    bolt_life = max([int(m.get("lifetime", 0)) for m in motions.values()
                     if str(m.get("kind")) == "투사"] or [0])
    shot_cd = int(dig(cfg, "skill_motion.yml", "forms", "검기_참격", "cooldown_ticks", default=40))
    bolts = cluster * bolt_life // max(1, shot_cd)
    base_load = slash_n + sheath_n + bolts
    rep.verdict(base_load + reserve <= degrade,
                f"20인 군집 정상 상태: 참격선 {slash_n} + 날의 기 {sheath_n} + 투사 {bolts}"
                f" + 오의 예약 {reserve} = {base_load + reserve} ≤ 강등선 {degrade} ≤ 전역 {cap}")
    fails += int(base_load + reserve > degrade)
    room = degrade - (slash_n + bolts)           # 방어 태세는 날의 기를 쓰지 않는다 (armed 는 하나다)
    people = room // max(1, ring) if ring else cluster
    if people < cluster:
        rep.warn(f"호신강기 고리는 {people}인분 (군집 {cluster}인 전원이 두르면 나머지는 파티클 고리로 강등"
                 " — over_cap 규약대로다. 켜져 있다는 사실은 그래도 보인다)")
    else:
        rep.ok(f"호신강기 고리 {people}인분 여유 (군집 {cluster}인 전원 수용)")

    ratio = float(blend.get("trail_particle_ratio", 1.0))
    minimum = int(blend.get("trail_particle_min", 0))
    ok_blend = 0.0 < ratio < 1.0 and minimum >= 1 and blend.get("keep_impact") is True
    fails += int(not ok_blend)
    rep.verdict(ok_blend,
                f"파티클 조율 — 궤적 {int(ratio * 100)}% 감축 · 하한 {minimum} (0 이면 강등이 아니라 실종이다)"
                f" · 타격 불가침 {blend.get('keep_impact')}")
    return fails


# ══════════════════════════════════════════════════════════════════════════════
#  ⑨ 가장 흔한 경로 — 무공 없이 휘둘러도 획이 뜨는가
# ══════════════════════════════════════════════════════════════════════════════
# 지금까지의 검수는 **등록부만** 봤다. 등록부는 완벽했는데 **코드가 그 경로를 부르지 않았다** —
# 3D 층이 무공 시전 경로 안에만 살아 있어서, 무공을 안 배운 손(가장 흔한 손)은 아무것도 못 봤다.
# 사용자가 그것을 인게임에서 먼저 발견했다. 검수가 못 잡은 것이 부끄러운 자리다. 이 축이 그 자리다.

def audit_common_path(rep):
    rep.head("⑨ 가장 흔한 경로 — 무공을 안 배운 손이 휘둘러도 획이 뜨는가")

    path = os.path.join(MVT, "SkillListener.java")
    try:
        with open(path, encoding="utf-8") as fh:
            src = fh.read()
    except OSError:
        rep.fail(f"소스 없음: {path}")
        return 1

    fails = 0
    checks = [
        ("basicSwing", "기본 초식 경로(basicSwing)가 있다 — 무공이 없어도 손은 움직인다"),
        ("basicStrike", "기본 초식의 히트박스를 **등록부에서** 읽는다 (하드코딩 금지)"),
        ("display.slash", "참격선을 발행한다 (검을 복제하지 않는다)"),
        ("display.sheath", "날의 기를 발행한다 (격을 두르면 병기에 서린다)"),
        ("display.thrown", "던진 암기가 날아간다"),
        ("posture", "몸의 자세를 얹는다 (전진·pose — 되는 것만)"),
        ("body.script()", "자세가 **한 줄기로 흐른다** — 획이 그려지는 동안 몸이 beat 를 지난다"),
        ("windup(", "축세(蓄勢) — 선딜이 있는 초식은 몸이 **먼저 벼른다**"),
        ("setRotation", "허리가 돈다 — 몸의 회전 축 (**차분만** 더한다)"),
        ("swingOffHand", "왼팔이 나간다 — 서버가 줄 수 있는 유일한 사지 애니메이션 (권갑의 원-투)"),
        ("turn(player, st, 0.0f, 0.0f)", "몸이 **원점으로 돌아온다** — 조준을 빌리고 그대로 갚는다"),
    ]
    for token, why in checks:
        if token in src:
            rep.ok(why)
        else:
            fails += 1
            rep.fail(f"미배선: {token} — {why}")

    # 무공이 없을 때(skillId == null) 그냥 return 하고 끝나면 그것이 바로 그 버그다
    dead = re.search(r"skillId\s*==\s*null\s*\)\s*\{\s*return", src)
    if dead:
        fails += 1
        rep.fail("무공이 없으면 **그냥 돌아간다** — 가장 흔한 손이 아무것도 못 본다 (그 버그가 돌아왔다)")
    else:
        rep.ok("무공이 없는 손도 basicSwing 으로 흘러간다 (경로가 비어 있지 않다)")

    # 하드코딩된 무공표 — 원장이 골라야 한다 (검을 들면 누구나 육합검이 나가던 자리)
    if "SKILL_BY_WEAPON_CLASS" in src:
        fails += 1
        rep.fail("하드코딩된 무공표(SKILL_BY_WEAPON_CLASS)가 남아 있다 — 원장이 골라야 한다")
    else:
        rep.ok("주무공을 원장이 고른다 (배운 무공만 나간다 · 매화검법·나한권도 경로를 얻었다)")

    # 쿨다운 — planCombo 가 cooldown_ticks 를 읽는가 (읽지 않던 시절 Cast.cooldownTicks() 는 늘 0 이었다)
    eng = os.path.join(MVT, "SkillEngine.java")
    with open(eng, encoding="utf-8") as fh:
        ebody = fh.read()
    m = re.search(r"public Cast planCombo\([\s\S]*?\n    \}", ebody)
    if m and "cooldown_ticks" in m.group(0):
        rep.ok("planCombo 가 cooldown_ticks 를 읽는다 (태조장권 60 · 매화검법 90 이 이제 적용된다)")
    else:
        fails += 1
        rep.fail("planCombo 가 cooldown_ticks 를 읽지 않는다 — 콤보 무공의 쿨다운이 늘 0 이다")
    return fails


# ══════════════════════════════════════════════════════════════════════════════
# ⑩ 판정의 눈 — **눈이 판정을 속일 수 있는가**
#
# 이 눈의 값어치는 오직 하나에 달렸다: **그리는 코드와 맞히는 코드가 하나인가.**
# 눈이 제 히트박스를 따로 그리는 순간, 그 그림은 판정에 대해 거짓말할 수 있고 —
# 거짓말할 수 있는 눈은 **없느니만 못하다** (틀린 그림을 믿고 판정을 고치게 되므로).
# 그래서 구조로 못을 박는다: 판정도·눈도·절기도 **같은 함수**를 부른다.

def audit_eye(rep):
    rep.head("⑩ 판정의 눈 — 그리는 코드와 맞히는 코드가 하나인가")
    fails = 0
    src = {}
    for name in ("SkillListener.java", "SkillCast.java"):
        try:
            with open(os.path.join(MVT, name), encoding="utf-8") as fh:
                src[name] = fh.read()
        except OSError:
            rep.fail(f"소스 없음: {name}")
            return 1

    lis, cast = src["SkillListener.java"], src["SkillCast.java"]

    # ① 기하는 한 벌뿐인가 — 판정의 세 함수가 존재하는가
    geom = ["inArc", "inLine", "inCircle", "inCone"]
    missing = [g for g in geom if f"static boolean {g}(" not in lis]
    fails += len(missing)
    rep.verdict(not missing, f"히트박스의 기하 한 벌 — {geom} (판정·눈·절기가 함께 쓴다)"
                + ("" if not missing else f" — 없는 함수 {missing}"))

    # ② 판정이 그 함수를 부르는가 (제 손으로 각도를 다시 재면 두 개의 진실이 생긴다)
    for fn, caller in (("inArc", "arcTargets"), ("inLine", "lineTargets"), ("inCircle", "circleTargets")):
        m = re.search(r"private List<LivingEntity> " + caller + r"\([\s\S]*?\n    \}", lis)
        ok = bool(m) and fn + "(" in m.group(0)
        fails += int(not ok)
        rep.verdict(ok, f"판정({caller})이 {fn}() 을 부른다 — 각도를 제 손으로 다시 재지 않는다")

    # ③ ★ 눈이 **판정의 함수에 물어보는가** — 이것이 이 감사의 심장이다
    m = re.search(r"private void eyeHitbox\([\s\S]*?\n    \}", lis)
    eye_asks = bool(m) and all(f + "(" in m.group(0) for f in ("inArc", "inLine", "inCircle"))
    fails += int(not eye_asks)
    rep.verdict(eye_asks,
                "★ **눈이 판정에게 물어본다** — eyeHitbox 가 표본 점을 inArc·inLine·inCircle 에 넣고,"
                " '맞는 자리'라고 답한 점만 찍는다 (눈이 제 기하를 가지면 그림이 판정에 대해 거짓말한다)")

    # ④ 절기(SkillCast)도 같은 기하를 쓰는가 — 두 개의 히트박스는 곧 하나의 거짓말이다
    own_geom = re.search(r"Math\.toDegrees\(.*angle\(|Math\.tan\(Math\.toRadians", cast)
    shares = "SkillListener.inArc(" in cast and "SkillListener.inCone(" in cast
    fails += int(not shares or bool(own_geom))
    rep.verdict(shares and not own_geom,
                "절기(SkillCast)도 같은 기하를 쓴다 — 제 원뿔을 따로 갖지 않는다"
                + ("" if shares and not own_geom else " — 【두 개의 히트박스가 살아 있다】"))

    # ⑤ 꺼져 있을 때 공짜인가 — 눈은 판정 경로에 if 한 줄 이상을 요구하면 안 된다
    off_free = "eyes.contains(player.getUniqueId())" in lis and "boolean eye =" in lis
    fails += int(not off_free)
    rep.verdict(off_free, "꺼져 있으면 비용 0 — 판정 경로의 눈은 if 한 줄이다 (디버그가 전투를 느리게 하면 안 된다)")

    # ⑥ 예산 — 눈이 서버를 죽이지 않는가 (등록부가 상한을 쥔다)
    has_cap = "eye.maxPoints()" in lis and "drawn < eye.maxPoints()" in lis
    fails += int(not has_cap)
    rep.verdict(has_cap, "표본 점 상한(max_points)을 등록부가 쥔다 — 눈이 파티클로 서버를 죽이면 안 된다")
    return fails


# ══════════════════════════════════════════════════════════════════════════════

def main():
    ap = argparse.ArgumentParser(description="혼천 무공 모션 감사")
    ap.add_argument("--coverage-only", action="store_true")
    args = ap.parse_args()

    rep = Report()
    rep.say("══ 무공 모션 감사 ══")
    rep.say("  \"팩이 없어도, 파티클과 소리만으로 무엇이 일어났는지 읽혀야 한다\"")

    try:
        cfg = load_all()
    except YamlError as e:
        print(f"{FAIL} config 파싱 실패: {e}", file=sys.stderr)
        return 2

    mo = cfg.get("skill_motion.yml")
    if not mo:
        print(f"{FAIL} config/skill_motion.yml 이 없다", file=sys.stderr)
        return 2

    pct, spct = audit_coverage(cfg, mo, rep)
    if not args.coverage_only:
        audit_trajectory(cfg, mo, rep)
        audit_ladder(cfg, mo, rep)
        audit_palette(mo, rep)
        audit_budget(cfg, mo, rep)
        audit_weapon_and_sound(cfg, mo, rep)
        audit_wiring(mo, rep)
        audit_display(cfg, mo, rep)
        audit_common_path(rep)
        audit_eye(rep)

    rep.say()
    rep.say("═" * 72)
    n_v, n_w = len(rep.violations), len(rep.warnings)
    rep.say(f"  커버리지: 무공·격·형태·오의 {pct:.1f}% · 초식 {spct:.1f}%")
    if n_v == 0 and n_w == 0:
        rep.say("  총평: ✅ 위반 0건 · 경고 0건 — 팩이 없어도 무공은 눈에 보인다")
    else:
        rep.say(f"  총평: 위반 {n_v}건 · 경고 {n_w}건")
        if n_v:
            rep.say("")
            rep.say(f"  ── 위반 ({FAIL}) — 모션이 등록부와 어긋나거나, 눈에 보이지 않는다")
            for i, v in enumerate(rep.violations, 1):
                rep.say(f"    {i:2}. {v}")
        if n_w:
            rep.say("")
            rep.say("  ── 경고 (⚠️) — 굴러가지만 의도한 그림이 아닐 수 있다")
            for i, w in enumerate(rep.warnings, 1):
                rep.say(f"    {i:2}. {w}")
    rep.say("═" * 72)
    rep.dump()
    return 1 if n_v else 0


if __name__ == "__main__":
    sys.exit(main())
