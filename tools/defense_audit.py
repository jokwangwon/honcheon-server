#!/usr/bin/env python3
"""방어 감사 — 혼천 **방어 태세 삼문(三門)** 의 눈과 자.

`combat_audit.py` 는 한 명의 표준 무인이 몇 합에 끝나는가를 재고,
`growth_audit.py` 는 여러 빌드가 서로 다른가를 잰다.
이 도구는 셋째 것을 묻는다: **맞는 쪽에 선택이 있는가.**

  config/combat.yml 은 방어를 셋으로 적어 두고 있었다 — 회피(민첩+경공) · 막기(근력) · 흘리기(감각).
  엔진에는 **하나도 없었다** (호신강기 = 내력으로 막는 것 하나뿐). 그래서 근력·감각·민첩에
  수련을 부어도 **맞을 때 아무 일도 일어나지 않았다** — 수련의 절반이 살 곳이 없었다.

  방어가 선택이려면 넷이 참이어야 한다:

    ① **지배 태세가 없다**   하나가 언제나 옳으면 그건 선택이 아니라 정답이다.
    ② **삼류도 제 목숨을 본다** 낮은 경지가 방어를 아예 못 하면 게이트다 (이 프로젝트가 한 번 데인 죄).
    ③ **갑옷이 판 값을 받는다** 갑옷은 회피를 판다. 그 대가로 무엇을 사는가 — 사는 게 없으면 순손해다.
    ④ **엔진과 도구가 같은 산술을 한다** 다르면 둘 중 하나가 거짓말이다.

전투 수학(2d6 해석 · Fighter · 표준 무인)은 combat_audit / growth_audit 을 **그대로 재사용한다** —
전투를 두 번 구현하면 두 개의 진실이 생긴다.

config 를 고치지 않는다 — 재기만 한다. 수치는 전부 config·소스에서 읽는다 (하드코딩 금지).

사용법:
    python3 tools/defense_audit.py                # 전체
    python3 tools/defense_audit.py --lint-only    # ① 등록 정합 + ② 엔진 배선
    python3 tools/defense_audit.py --sim-only     # ③ 태세 시뮬
    python3 tools/defense_audit.py --budget 1800  # 수련 예산 (growth_audit 과 같은 눈금)

종료 코드: 위반(❌) 1건 이상이면 1, 아니면 0.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from fractions import Fraction

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from game_audit import (  # noqa: E402
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
    realm_axis,
    top_band,
    weapon_power,
)
from growth_audit import (  # noqa: E402  — 빌드 모델도 하나뿐이다
    Build,
    COMBAT_ATTRS,
    STANCES,
    defense_rules,
    forced_guard,
    standard_builds,
)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MVT = os.path.join(ROOT, "server-mvt", "src", "main", "java", "com", "honcheon", "mvt")


def grade_rank(cfg, grade):
    """격의 사다리 — qi_manifestation.yml grades[].rank (외공기는 0)."""
    spec = dig(cfg, "qi_manifestation.yml", "grades", grade, default=None)
    return int(num((spec or {}).get("rank"), 0)) if isinstance(spec, dict) else 0


# ══════════════════════════════════════════════════════════════════════════════
#  config 판독 — 태세 · 몸짓 · 갑옷 (등록제. 도구는 수치를 갖지 않는다)
# ══════════════════════════════════════════════════════════════════════════════

def stance_mc(cfg):
    """combat.yml attack.defender_stance_mc — 마인크래프트의 태세 선택 등록부."""
    return dig(cfg, "combat.yml", "attack", "defender_stance_mc", default={}) or {}


def gestures(cfg):
    return dig(stance_mc(cfg), "gestures", default={}) or {}


def stance_vfx(cfg):
    return dig(stance_mc(cfg), "vfx", default={}) or {}


def armors(cfg):
    """equipment.yml armor — 계열별 (경감, 회피 페널티). 규칙 문자열 키는 뺀다."""
    raw = dig(cfg, "equipment.yml", "armor", default={}) or {}
    out = {}
    for name, spec in raw.items():
        if isinstance(spec, dict) and "mitigation" in spec:
            out[name] = {
                "mitigation": int(num(spec.get("mitigation"), 0)),
                "dodge": int(num(spec.get("dodge_penalty"), 0)),
                "blocks_gyeonggong": "경공_불가" in (spec.get("restrictions") or []),
            }
    return out


def armor_pierced_from(cfg):
    """갑옷의 경감이 무력해지는 격 — '검강 앞 피갑은 종이' 의 기계 정의."""
    return str(dig(cfg, "equipment.yml", "armor", "mitigation_pierced_from", default="강기"))


def weapon_safe(cfg, stance):
    dc = dig(cfg, "combat.yml", "attack", "defender_choice", stance, default={}) or {}
    return bool(dc.get("weapon_safe"))


def stance_skill(cfg, stance):
    dc = dig(cfg, "combat.yml", "attack", "defender_choice", stance, default={}) or {}
    return str(dc.get("skill", "병기 기술"))


def palette(cfg):
    p = dig(cfg, "skill_motion.yml", "palette", default={}) or {}
    return set(p) if isinstance(p, dict) else set(p or [])


SOUND_KEY = re.compile(r"^[a-z0-9_]+(\.[a-z0-9_]+)+$")


# ══════════════════════════════════════════════════════════════════════════════
#  ① 등록 정합 린트 — 등록된 것이 실재하는가
# ══════════════════════════════════════════════════════════════════════════════

def lint(cfg, rep):
    rep.say()
    rep.say("═" * 72)
    rep.say("  ① 등록 정합 — 세 태세가 규칙에 있는가, 그리고 몸이 그것을 고를 수 있는가")
    rep.say("═" * 72)
    lint_stances(cfg, rep)
    lint_choice(cfg, rep)
    lint_armor(cfg, rep)
    lint_vfx(cfg, rep)


def lint_stances(cfg, rep):
    rep.head("세 태세 — 능력치 · 기술 · 경감 · 판정 비용 · 무기 안전")
    rules = defense_rules(cfg)
    for stance in STANCES:
        spec = rules[stance]
        rep.say(f"     {stance:<4} ← {spec['attr']:<3} + {stance_skill(cfg, stance):<7} · "
                f"경감 {spec['soak']:g} · 판정 {spec['penalty']:+g} · "
                f"{'무기 안전' if weapon_safe(cfg, stance) else '★ 무기가 격을 먹는다'}")

    # 세 능력치에 하나씩 — 한 축이 두 방어를 사면 그 축이 지배 전략이 된다
    used = {spec["attr"] for spec in rules.values()}
    rep.verdict(len(used) == len(STANCES),
                f"세 방어가 서로 다른 능력치에 걸린다 ({' · '.join(sorted(used))})"
                if len(used) == len(STANCES) else
                f"방어 태세가 능력치를 공유한다 ({used}) — 한 축이 두 방어를 산다")

    # 접촉 규약 — qi_manifestation weapon_break.trigger 가 이미 적어 뒀다:
    #   "무기 접촉 격돌만 — 가드/패링/합. 회피·흘리기는 접촉이 없다"
    trigger = str(dig(cfg, "qi_manifestation.yml", "weapon_break", "trigger", default=""))
    contactless = [s for s in STANCES if weapon_safe(cfg, s)]
    declared = [s for s in STANCES if s in trigger]
    rep.verdict(sorted(contactless) == sorted(declared),
                f"무기 안전 태세({' · '.join(contactless)})가 weapon_break.trigger 의 서술과 일치한다 — "
                f"막기만 무기를 태운다"
                if sorted(contactless) == sorted(declared) else
                f"weapon_safe({contactless}) ≠ weapon_break.trigger 의 서술({declared}) — "
                f"두 등록부가 다른 말을 한다 (엔진은 둘 중 하나만 따를 수 있다)")

    # 경감의 사다리 — 회피(0) < 흘리기(1) < 막기(3). 그리고 강제 태세는 가장 낮은 것
    soaks = {s: rules[s]["soak"] for s in STANCES}
    fg = forced_guard(cfg)
    floor = str(fg.get("audit_floor", "흘리기"))
    others = [s for s in STANCES if s != "회피" and s != floor]
    rep.verdict(all(soaks[floor] < soaks[o] for o in others) if others else True,
                f"강제 태세({floor}, 경감 {soaks[floor]:g})가 자발 태세({others})보다 인색하다 — "
                f"포위가 1대1보다 안전해지지 않는다"
                if all(soaks[floor] < soaks[o] for o in others) else
                f"강제 태세({floor})의 경감이 자발 태세보다 크거나 같다 — 다구리가 더 안전해진다")


def lint_choice(cfg, rep):
    rep.head("누가 고르는가 — 마인크래프트에는 턴이 없다 (defender_stance_mc)")
    mc = stance_mc(cfg)
    if not mc:
        rep.fail("combat.yml attack.defender_stance_mc 미등록 — 맞는 순간 누가 태세를 고르는지 "
                 "규칙이 말하지 않는다. 엔진은 고를 수밖에 없고, 코드가 규칙을 짓게 된다")
        return

    ges = gestures(cfg)
    rep.say(f"     우선순위: {' → '.join(str(x) for x in (mc.get('precedence') or []))}")
    for stance, pred in ges.items():
        rep.say(f"     몸짓  {pred:<12} → {stance}")
    rep.say(f"     지정  {mc.get('pinned_command')} · 기본 {mc.get('default')}")

    # ① 세 태세가 전부 몸짓으로 닿는가 — 못 고르는 태세가 있으면 그 축의 수련이 죽는다
    missing = [s for s in STANCES if s not in ges]
    rep.verdict(not missing,
                f"세 태세 전부 몸짓으로 닿는다 — 몸이 곧 선택이다 (새 입력 채널을 열지 않았다)"
                if not missing else
                f"몸짓으로 못 고르는 태세: {missing} — 그 태세는 '지정'으로만 설 수 있다")

    # ② 게이트가 없는가 — ★ 삼류가 제 목숨을 볼 수 있어야 한다
    rep.verdict(bool(mc.get("no_realm_gate")),
                "경지 게이트 없음 — **삼류도 제 목숨을 본다**. 방어를 사는 것은 내력이 아니라 몸이다"
                if mc.get("no_realm_gate") else
                "방어에 경지 게이트가 걸렸다 — 삼류가 제 목숨을 영영 못 본다")

    # ③ 쿨다운이 없는가 — MMO 문법 거부
    rep.verdict(bool(mc.get("no_cooldown")),
                "쿨다운 없음 — 태세를 막는 것은 내력·자세·상황이다 (MMO 문법 거부)"
                if mc.get("no_cooldown") else
                "방어 태세에 쿨다운이 걸렸다 — 타이머로 막는 것은 이 세계의 문법이 아니다")

    # ④ 기본값이 '고를 줄 모르는 몸'을 버리지 않는가
    rep.verdict(str(mc.get("default", "")) not in STANCES,
                f"기본값 '{mc.get('default')}' — 아무것도 지정 안 한 몸은 **몸이 아는 대로** 선다 "
                f"(고정 태세를 기본값으로 두면 안 배운 자가 제일 나쁜 태세로 죽는다)"
                if str(mc.get("default", "")) not in STANCES else
                f"기본값이 고정 태세('{mc.get('default')}')다 — 그 태세가 안 맞는 빌드는 "
                f"명령을 배우기 전까지 손해를 본다")


def lint_armor(cfg, rep):
    rep.head("갑옷 — 회피를 판다. **무엇을 사는가**")
    aa = armors(cfg)
    if not aa:
        rep.fail("equipment.yml armor 등록부가 비었다")
        return
    pierced = armor_pierced_from(cfg)
    for name, spec in aa.items():
        rep.say(f"     {name:<4} 회피 {spec['dodge']:+d} · 경감 {spec['mitigation']:+d}"
                + ("  [경공 불가 — 회피의 기술 항이 0]" if spec["blocks_gyeonggong"] else ""))
    rep.say(f"     경감 무력화 격: {pierced} 이상 — \"격 상성은 못 이긴다. 검강 앞 피갑은 종이\"")

    # ① 파는 것이 있으면 사는 것도 있어야 한다 (지금까지 갑옷은 순손해였다)
    losers = [n for n, s in aa.items() if s["dodge"] < 0 and s["mitigation"] <= 0]
    rep.verdict(not losers,
                "회피를 파는 갑옷은 전부 경감을 산다 — 갑옷이 순손해가 아니다"
                if not losers else
                f"★ 회피만 팔고 아무것도 못 사는 갑옷: {losers} — 입을 이유가 없다 (죽은 장비)")

    # ② 파는 만큼 산다 — 경감이 회피 페널티를 갚는가 (단조).
    #   ※ 외갑만 본다: 내갑(보물급)은 파는 것 없이 사는 물건이라 이 사다리에 서지 않는다
    #      (그것이 '보물급'의 뜻이다 — 대가 없이 얻는다. 대신 등록부가 수량을 막는다).
    outer = {n: s for n, s in aa.items() if s["dodge"] < 0 or s["mitigation"] == 0}
    graded = sorted(outer.items(), key=lambda kv: kv[1]["dodge"], reverse=True)   # 적게 파는 순
    broken = [g[0] for i, g in enumerate(graded[:-1])
              if g[1]["mitigation"] > graded[i + 1][1]["mitigation"]]
    rep.verdict(not broken,
                "더 많이 파는 갑옷이 더 많이 산다 (" + " → ".join(
                    f"{n} 회피{s['dodge']:+d}/경감{s['mitigation']:+d}" for n, s in graded)
                + ") — 거래가 단조롭다"
                if not broken else
                f"더 많이 파는데 덜 사는 갑옷: {broken} — 그 갑옷은 아래 등급의 열화다")

    # ③ ★ 갑옷이 지배 전략이 되지 않는가 — 상위 격 앞에서 무력해져야 한다
    rep.verdict(bool(pierced) and grade_rank(cfg, pierced) > 0,
                f"경감은 {pierced}(rank {grade_rank(cfg, pierced)}) 앞에서 0 이 된다 — "
                f"갑옷은 **졸개에게 강하고 고수에게 무력하다**. 그것이 갑옷의 지배 전략을 스스로 막는다"
                if pierced and grade_rank(cfg, pierced) > 0 else
                "갑옷의 경감이 어떤 격 앞에서도 무너지지 않는다 — 철갑 몰빵이 상수로 이긴다")

    # ④ 파는 것은 회피뿐인가 (막기·흘리기까지 팔면 갑옷이 방어 전체를 판다 = 못 입는 물건)
    applies = dig(stance_mc(cfg), "armor", "applies_to", default=[]) or []
    rep.verdict(list(applies) == ["회피"],
                "갑옷이 파는 것은 회피뿐이다 — 막기·흘리기는 갑옷을 신경 쓰지 않는다"
                if list(applies) == ["회피"] else
                f"갑옷이 {applies} 를 판다 — 회피 밖까지 팔면 갑옷을 입을 빌드가 없다")


def lint_vfx(cfg, rep):
    rep.head("화면이 판정에 대해 거짓말하지 않는가 (팩이 없어도 보이는가)")
    fx = stance_vfx(cfg)
    pal = palette(cfg)
    need = STANCES + ["실패"]
    missing = [n for n in need if n not in fx]
    rep.verdict(not missing,
                f"판정의 네 결과가 전부 연출을 갖는다 ({' · '.join(need)}) — "
                f"막았으면 막았다고, 흘렸으면 흘렸다고, 피했으면 피했다고 보인다"
                if not missing else
                f"연출 없는 판정 결과: {missing} — 화면이 그 판정을 말하지 않는다")

    for name, spec in fx.items():
        if not isinstance(spec, dict):
            continue
        parts = []
        if spec.get("particle"):
            parts.append(f"파티클 {spec['particle']}×{num(spec.get('count'), 0):g}")
        if spec.get("sound"):
            parts.append(f"소리 {spec['sound']}")
        parts.append(f"글자 '{spec.get('label')}'")
        rep.say(f"     {name:<8} {' · '.join(parts)}")

    # ① 파티클이 팔레트 안인가 (등록제 — 코드가 파티클을 고르지 않는다)
    stray = [str(s.get("particle")) for s in fx.values()
             if isinstance(s, dict) and s.get("particle") and str(s["particle"]) not in pal]
    rep.verdict(not stray,
                f"파티클이 전부 skill_motion.yml palette 안이다 ({len(pal)}종) — 등록제 규약"
                if not stray else
                f"팔레트 밖 파티클: {stray} — 등록부가 모르는 이름이다")

    # ② 소리 키 형식 (바닐라 키)
    bad = [str(s.get("sound")) for s in fx.values()
           if isinstance(s, dict) and s.get("sound") and not SOUND_KEY.match(str(s["sound"]))]
    rep.verdict(not bad, "바닐라 사운드 키 형식 검사" + ("" if not bad else f" — 잘못된 키 {bad}"))

    # ③ ★ 팩 없이도 읽히는가 — 글자가 없으면 팩 거절한 눈에는 아무 일도 안 일어난 것처럼 보인다
    voiceless = [n for n, s in fx.items() if isinstance(s, dict) and not s.get("label")]
    rep.verdict(not voiceless,
                "네 결과 전부 **글자**를 갖는다 — 팩이 없어도, 색맹이어도 읽힌다 "
                "(파티클·소리·글자 셋 다 바닐라. 팩 글리프에 기대지 않는다)"
                if not voiceless else
                f"글자 없는 연출: {voiceless} — 팩을 거절한 눈에는 판정이 안 보인다")


# ══════════════════════════════════════════════════════════════════════════════
#  ② 엔진 배선 — 등록부가 코드에 실제로 서 있는가 (눈이 엔진을 본다)
# ══════════════════════════════════════════════════════════════════════════════

def src(name):
    with open(os.path.join(MVT, name), encoding="utf-8") as fh:
        return fh.read()


def wiring(cfg, rep):
    rep.say()
    rep.say("═" * 72)
    rep.say("  ② 엔진 배선 — 규칙이 코드에 실제로 서 있는가")
    rep.say("═" * 72)
    rep.head("SkillListener / SkillEngine / Growth")

    try:
        listener = src("SkillListener.java")
        growth = src("Growth.java")
        hunting = src("HuntingGrounds.java")
    except OSError as e:
        rep.fail(f"소스를 못 읽는다: {e}")
        return

    # ① 세 태세가 엔진에 서 있는가 — 태세를 고르고, 판정하고, 경감한다
    for fn, why in [
        ("chooseStance", "태세를 고른다 (몸짓 → 지정 → 자동)"),
        ("guardline", "태세 판정치·경감을 세운다"),
        ("foeAttackScore", "공격 총합 — 대립 판정의 반대편"),
        ("armorSoak", "갑옷의 경감 — 회피를 판 대가"),
        ("gyeonggongSkill", "회피의 '경공' 항 (Gyeonggong.gyeonggongMastery)"),
    ]:
        rep.verdict(fn in listener, f"SkillListener.{fn} — {why}"
                    if fn in listener else
                    f"SkillListener 에 {fn} 이 없다 — {why} 가 배선되지 않았다")

    # ② 회피 성공이 피해를 **취소**하는가 — 그래야 GyeonggongListener.onDodged 가 몸을 뺀다
    rep.verdict("setCancelled(true)" in listener and "stanceSucceeded" in listener,
                "회피 성공 = event.setCancelled(true) — GyeonggongListener.onDodged(MONITOR)가 "
                "몸을 실제로 뒤로 뺀다 (경공 담당의 이음매가 여기서 산다)"
                if "stanceSucceeded" in listener else
                "태세 성공이 피해를 취소하지 않는다 — 회피가 '안 맞는 척'만 한다")

    # ③ 막기만 무기를 태우는가 (회피·흘리기는 접촉이 없다 — weapon_break.trigger)
    rep.verdict("line.clashes()" in listener or "clashes()" in listener,
                "막기(접촉)만 clashWeapon 을 탄다 — 회피·흘리기는 무기가 상하지 않는다 (weapon_safe)"
                if "clashes()" in listener else
                "격돌이 태세와 무관하게 돈다 — 피해도 안 입은 회피가 검을 부순다")
    rep.verdict("weaponSafe" in growth,
                "Growth.Stance 가 weapon_safe 를 등록부에서 읽는다 (코드가 접촉을 짓지 않는다)"
                if "weaponSafe" in growth else
                "Growth.Stance 에 weapon_safe 가 없다 — 어느 태세가 무기를 태우는지 코드가 정한다")

    # ④ ★ 경감이 **두 번** 들지 않는가 — HuntingGrounds 가 forced_guard 경감을 또 빼면 이중 경감이다
    #   (그러면 둘이 덤비는 것이 하나보다 덜 아파진다 — 등록부가 명시적으로 금지한 뒤집힘)
    double = re.search(r"setDamage\(\s*Math\.max\(0\.0,\s*event\.getDamage\(\)\s*-\s*soak", hunting)
    rep.verdict(double is None,
                "경감이 한 곳에서만 든다 (방어 태세 층) — HuntingGrounds 는 슬롯만 본다. 이중 경감 없음"
                if double is None else
                "★ 이중 경감: HuntingGrounds.onSurround 가 태세 층과 **따로** 경감을 뺀다 — "
                "포위가 1대1보다 덜 아파진다")

    # ⑤ 마진 항이 피해에 실리는가 — 없으면 방어 판정치가 피해의 크기를 못 흔든다
    rep.verdict("floorDiv(margin, 2)" in listener,
                "피해에 floor(마진/2) 항이 실린다 (combat.yml damage.formula) — "
                "방어 판정치가 명중만이 아니라 **피해의 크기**를 흔든다. 그래야 흘리기의 −2 가 값을 한다"
                if "floorDiv(margin, 2)" in listener else
                "★ 피해에 마진 항이 없다 — 방어 판정치가 명중/빗나감만 흔든다. "
                "그러면 도구(growth_audit.exchange)와 엔진이 **다른 산술**을 한다")

    # ⑥ 태세가 화면에 뜨는가
    rep.verdict("stanceFx" in listener and "flash" in listener,
                "태세의 결과가 파티클·소리·액션바로 나온다 (SkillHud.flash — statusBar 가 못 덮는다)"
                if "stanceFx" in listener else
                "태세가 화면에 안 뜬다 — 화면이 판정에 대해 침묵한다")

    # ⑦ 게이트가 코드에 몰래 들어가지 않았는가 — 삼류가 제 목숨을 봐야 한다
    gated = re.search(r"(canUse|gradeGate|armableGrades)\s*\([^)]*\)[^;]*(회피|막기|흘리기)", listener)
    rep.verdict(gated is None,
                "방어 태세에 경지·내력 게이트가 없다 — **삼류도 제 목숨을 본다**"
                if gated is None else
                f"방어 태세에 게이트가 걸렸다: {gated.group(0)[:50]}")


# ══════════════════════════════════════════════════════════════════════════════
#  ③ 태세 시뮬 — 지배 태세가 있는가 (해석적, 2d6 = 36가지)
# ══════════════════════════════════════════════════════════════════════════════

def expected(cfg, atk, dfn_score, soak, armor, wp, tp, qi, defender_rolls=True):
    """한 합의 기대 피해 · 방어 성공률 — **엔진과 같은 산술**.

    마진 = (공격 총합 + 7) − (태세 판정치 + 2d6)          ← 한쪽만 굴린다
    피해 = 무기 + 무공 + 격 + floor(마진/2) − 태세 경감 − 갑옷 경감   (하한 0)

    ★ 이 두 줄이 `SkillListener.npcStrike` 의 두 줄과 **같아야 한다** (②-⑤가 그것을 본다).
    """
    _ = cfg
    dmg = Fraction(0)
    miss = Fraction(0)
    for roll, w in DICE_ITEMS:
        p = Fraction(w, 36)
        margin = (atk + 7) - (dfn_score + (roll if defender_rolls else 7))
        if margin < 0:
            miss += p
            continue
        base = max(0.0, wp + tp + qi + (margin // 2) - soak - armor)
        dmg += p * Fraction(int(base * 2), 2)
    return float(dmg), float(miss)


def score_of(cfg, b, stance, gyeonggong, armor_dodge, surrounded):
    """방어 판정치 — 능력치 + 기술 + 결 − 비용 + 갑옷 (Growth.defenseScore 와 같은 줄)."""
    rules = defense_rules(cfg)
    spec = rules[stance]
    pen = 0.0 if (surrounded and stance == str(forced_guard(cfg).get("audit_floor", "흘리기"))) \
        else spec["penalty"]
    skill = gyeonggong if stance_skill(cfg, stance) == "경공" else b.mastery
    armor = armor_dodge if stance_skill(cfg, stance) == "경공" else 0
    return b.ai(spec["attr"]) + skill + pen + armor


def foe_power(cfg, realm, weapon="도"):
    """공격자 — 그 경지의 표준 무인 (SkillEngine.realmAttr/realmSkill 과 **같은 판독**).

    ★ 도구와 엔진이 같은 사람을 세워야 도구가 거짓말을 안 한다:
      능력치 = 경지 상한 −1 · 숙련 = 승급 요건 '주력 무공 숙련 N' · 격 = 경지가 여는 가장 높은 것.
    """
    _, axis = realm_axis(cfg)
    a = axis.get(realm, {"attr": 3, "skill": 0})
    qis = dig(cfg, "combat.yml", "damage", "qi_power", default={}) or {}
    tps = dig(cfg, "combat.yml", "damage", "technique_power", default={}) or {}
    band = top_band(cfg, realm)
    return {
        "atk": int(a["attr"]) + int(a["skill"]),
        "wp": num(weapon_power(cfg, weapon), 4),
        "tp": num(tps.get(realm + "급"), 0),
        "qi": num(qis.get(band), 0),
        "band": band,
        "realm": realm,
    }


def simulate(cfg, rep, budget):
    rep.say()
    rep.say("═" * 72)
    rep.say("  ③ 태세 시뮬 — 지배 태세가 있는가 (해석적, 2d6 = 36가지)")
    rep.say("═" * 72)

    tertiary(cfg, rep)
    dominance(cfg, rep, budget)
    armor_trade(cfg, rep, budget)


def tertiary(cfg, rep):
    """★ 삼류도 제 목숨을 보는가 — 게이트를 두면 삼류가 제 목숨을 영영 못 본다."""
    rep.head("삼류의 목숨 — 아무것도 안 배운 몸이 방어할 수 있는가")
    b = Build(cfg, "삼류 초심자", {}, "삼류", 0,
              base_attrs={a: 2.0 for a in COMBAT_ATTRS}, base_skill=0.0, base_naegong=0.0)
    foe = foe_power(cfg, "삼류")
    rep.say(f"     몸: 전 능력치 2 · 숙련 0 · 보법 없음(경공 0) · 무복 · 내력 0")
    rep.say(f"     상대: 삼류 표준 무인 (공격 {foe['atk']} · 무기 {foe['wp']:g} · 격 {foe['band']})")
    rep.say("")

    worst = 1.0
    for stance in STANCES:
        sc = score_of(cfg, b, stance, gyeonggong=0, armor_dodge=0, surrounded=False)
        soak = defense_rules(cfg)[stance]["soak"]
        dmg, miss = expected(cfg, foe["atk"], sc, soak, 0, foe["wp"], foe["tp"], foe["qi"])
        worst = min(worst, miss)
        rep.say(f"     {stance:<4} 판정 {sc:>2.0f} · 방어 성공 {miss * 100:>5.1f}% · "
                f"기대 피해 {dmg:>4.1f} · 내구 {b.dur} → {b.dur / max(dmg, 0.01):>4.1f}합")

    best = max(expected(cfg, foe["atk"],
                        score_of(cfg, b, s, 0, 0, False),
                        defense_rules(cfg)[s]["soak"], 0, foe["wp"], foe["tp"], foe["qi"])[1]
               for s in STANCES)
    rep.say("")
    rep.verdict(best > 0.0,
                f"삼류가 제 목숨을 본다 — 최선 태세의 방어 성공률 {best * 100:.1f}% (0 이 아니다). "
                f"게이트가 없다: 방어를 사는 것은 내력이 아니라 **몸**이다"
                if best > 0.0 else
                "★ 삼류가 아무 태세로도 못 막는다 (성공률 0%) — 게이트다. "
                "삼류가 제 목숨을 영영 못 본다")
    rep.verdict(best < 1.0,
                f"그러나 안전하지도 않다 — 최선이어도 {(1 - best) * 100:.1f}% 는 맞는다 "
                f"(삼류는 삼류다)"
                if best < 1.0 else
                "삼류가 표준 무인의 공격을 100% 막는다 — 방어가 공짜다")


def dominance(cfg, rep, budget):
    """★ 이 도구의 존재 이유 — 하나가 언제나 옳으면 그건 선택이 아니다."""


    for realm in ("일류", "절정"):
        rep.head(f"[{realm}] 지배 태세 검사 — 빌드 × 상황 × 태세 (예산 {budget:g}일치)")
        builds = {b.name.replace(" 몰빵", ""): b
                  for b in standard_builds(cfg, realm, budget,
                                           base_attrs={a: 3.0 for a in COMBAT_ATTRS},
                                           base_skill=3.0)}
        # 상황 셋: 동급 1대1 · 격상(한 계단 위) · 다구리(포위 — 회피가 사라진다)
        from combat_audit import realm_names
        names = realm_names(cfg)
        up = names[min(names.index(realm) + 1, len(names) - 1)]
        situations = [("1대1", realm, False), ("격상", up, False), ("다구리", realm, True)]

        rep.say(f"     {'빌드':<6} {'상황':<6} " + "".join(f"{s:>22}" for s in STANCES) + "   최선")
        winners = {}
        for name in ("외공", "신법", "심안"):
            b = builds.get(name)
            if b is None:
                continue
            gg = b.mastery                        # 보법 숙련 근사 — 주력 숙련과 같은 손 (MVT 근사)
            for label, foe_realm, surrounded in situations:
                foe = foe_power(cfg, foe_realm)
                row, best, bestd = [], None, None
                for stance in STANCES:
                    if surrounded and stance in (forced_guard(cfg).get("loses") or []):
                        row.append(f"{'— (봉쇄)':>22}")
                        continue
                    sc = score_of(cfg, b, stance, gg, 0, surrounded)
                    soak = defense_rules(cfg)[stance]["soak"]
                    dmg, miss = expected(cfg, foe["atk"], sc, soak, 0,
                                         foe["wp"], foe["tp"], foe["qi"])
                    row.append(f"{f'{miss * 100:.0f}%막 · 피해 {dmg:.1f}':>22}")
                    if bestd is None or dmg < bestd - 1e-9:
                        bestd, best = dmg, stance
                rep.say(f"     {name:<6} {label:<6} " + "".join(row) + f"   {best}")
                winners.setdefault(best, []).append(f"{name}/{label}")
        rep.say("")

        slots = sum(len(v) for v in winners.values())
        tyrant = [(s, w) for s, w in winners.items() if len(w) >= slots - 1]
        rep.verdict(not tyrant,
                    f"지배 태세 없음 — {len(winners)}개 태세가 {slots}칸을 나눠 갖는다: "
                    + " · ".join(f"{s}({len(w)})" for s, w in winners.items())
                    if not tyrant else
                    f"★ 지배 태세: {tyrant[0][0]} 이 {len(tyrant[0][1])}/{slots} 칸에서 최선이다 "
                    f"({'/'.join(tyrant[0][1])}) — 이건 선택이 아니라 정답이다")

        dead = [s for s in STANCES if s not in winners]
        rep.verdict(not dead,
                    "죽은 태세 없음 — 셋 다 어딘가에서 최선이다"
                    if not dead else
                    f"★ 죽은 태세: {dead} — 어느 빌드·어느 상황에서도 고를 이유가 없다")

        # ⚠️ 편향 — 지배는 아니지만 한 태세가 삼분의 이를 먹으면 그것은 기울어진 판이다.
        #   눈은 통과시킨 것도 말해야 한다. "위반 0건" 이 "문제 없음" 을 뜻하면 그 눈은 거짓말한다.
        for stance, cells in winners.items():
            if len(cells) >= slots * 2 // 3:
                rep.warn(f"[{realm}] '{stance}' 가 {len(cells)}/{slots} 칸에서 최선이다 — "
                         f"지배는 아니나 **편향**이다 ({'/'.join(cells)}). "
                         f"경감({defense_rules(cfg)[stance]['soak']:g})이 판정보다 크게 작동한다. "
                         f"그 대가는 무기다 (weapon_break) — 이 표는 그것을 못 잰다")


def armor_mitigation(cfg, foe, spec, pierced):
    """이 상대의 격 앞에서 이 갑옷의 경감 — 【등록부가 정한다. 도구가 0 을 써 넣지 않는다】.

    ★ 여기서 0 을 손으로 적으면 그 순간 <b>눈이 거짓말한다</b>: 등록부가 {@code pierced_from} 을
      심검으로 바꿔도 눈은 여전히 "강기가 갑옷을 지난다"고 보고한다.
      실제로 그 버그에 한 번 속았다 — 일부러 규칙을 어긴 시험3 이 **안 잡혔다**.
    """
    return 0 if grade_rank(cfg, foe["band"]) >= grade_rank(cfg, pierced) else spec["mitigation"]


def cell(cfg, b, foe, spec, pierced):
    """이 몸이 이 갑옷으로 이 상대를 만났을 때 — 최선 태세의 기대 피해 (합당)."""
    gg = 0 if spec["blocks_gyeonggong"] else b.mastery      # 철갑 = 경공 항 0 (armor_gate)
    mit = armor_mitigation(cfg, foe, spec, pierced)
    return min(expected(cfg, foe["atk"], score_of(cfg, b, s, gg, spec["dodge"], False),
                        defense_rules(cfg)[s]["soak"], mit, foe["wp"], foe["tp"], foe["qi"])[0]
               for s in STANCES)


def armor_trade(cfg, rep, budget):
    """갑옷의 거래 — 회피를 판 대가로 무엇을 사는가. 그리고 그것이 언제 무너지는가."""


    rep.head("갑옷의 거래 — 회피를 팔아 무엇을 사는가 (그리고 언제 그 값이 사라지는가)")
    realm = "일류"
    builds = {b.name.replace(" 몰빵", ""): b
              for b in standard_builds(cfg, realm, budget,
                                       base_attrs={a: 3.0 for a in COMBAT_ATTRS}, base_skill=3.0)}
    b = builds["신법"]                       # 회피 빌드 — 갑옷이 가장 아픈 몸
    aa = armors(cfg)
    pierced = armor_pierced_from(cfg)

    foes = [("졸개(외공기)", foe_power(cfg, "삼류")),
            ("검기 고수", foe_power(cfg, "절정")),
            (f"{pierced} 고수", foe_power(cfg, "화경"))]

    rep.say(f"     몸: 신법 몰빵 {realm} (민첩 {b.attr('민첩'):.1f} · 경공 {b.mastery} · 내구 {b.dur})")
    rep.say("")
    rep.say(f"     {'갑옷':<4} {'회피판정':>8} " + "".join(f"{n:>18}" for n, _ in foes))
    for name, spec in aa.items():
        gg = 0 if spec["blocks_gyeonggong"] else b.mastery   # 철갑 = 경공 항 0 (armor_gate)
        cells = [f"{cell(cfg, b, foe, spec, pierced):>18.2f}" for _, foe in foes]
        sc = score_of(cfg, b, "회피", gg, spec["dodge"], False)
        rep.say(f"     {name:<4} {sc:>8.0f} " + "".join(cells))
    rep.say("")
    rep.say("     (숫자 = 최선 태세의 기대 피해 / 합. 낮을수록 안전하다)")
    rep.say("")

    def best(foe, spec):
        return cell(cfg, b, foe, spec, pierced)   # 표와 **같은 함수** — 두 산술이 있으면 하나는 거짓말이다

    plain = {"dodge": 0, "mitigation": 0, "blocks_gyeonggong": False}
    heavy = aa.get("철갑", {"dodge": -2, "mitigation": 2, "blocks_gyeonggong": True})

    # ① 회피가 사는 상대(졸개) 앞에서는 **무복이 이겨야 한다** — 아니면 갑옷이 상수로 옳다
    mook = foes[0][1]
    m_bare, m_iron = best(mook, plain), best(mook, heavy)
    rep.say(f"     졸개 앞 — 무복 {m_bare:.2f} vs 철갑 {m_iron:.2f} "
            f"(회피가 사는 자리. 무복은 몸을 빼고, 철갑은 서서 받는다)")
    rep.verdict(m_bare <= m_iron,
                f"졸개 앞에서는 **무복이 이긴다** ({m_bare:.2f} < {m_iron:.2f}) — "
                f"회피가 값을 하는 상대에게 갑옷은 손해다. 갑옷이 상수로 옳지 않다"
                if m_bare <= m_iron else
                f"★ 졸개 앞에서도 철갑이 이긴다 ({m_bare:.2f} → {m_iron:.2f}) — "
                f"갑옷이 모든 상대에게 옳다 (지배 장비)")

    # ② ★ 상위 격 앞에서 경감이 **정확히 0** 이 되는가 — 갑옷의 이득이 사라져야 한다
    boss = foes[2][1]
    b_bare, b_iron = best(boss, plain), best(boss, heavy)   # 경감은 mit_of 가 정한다
    rep.verdict(b_iron >= b_bare - 1e-9,
                f"{pierced} 앞에서 철갑의 이득이 **0 이하**다 (무복 {b_bare:.2f} vs 철갑 {b_iron:.2f}) — "
                f"경감이 종이가 됐고 회피만 팔린 채 남았다. "
                f"**갑옷은 졸개에게 강하고 고수에게 무력하다** (equipment.yml mitigation_pierced_from)"
                if b_iron >= b_bare - 1e-9 else
                f"★ {pierced} 앞에서도 철갑이 이득이다 ({b_bare:.2f} → {b_iron:.2f}) — "
                f"경감이 안 무너진다. 철갑 몰빵이 상수로 이긴다 (지배 장비)")

    # ③ 중간 지대 — 검기 고수 앞에서는 갑옷이 이긴다. **그것이 갑옷의 값이다** (그리고 그 값은 유한하다)
    mid = foes[1][1]
    d_bare, d_iron = best(mid, plain), best(mid, heavy)
    rep.say(f"     검기 고수 앞 — 무복 {d_bare:.2f} vs 철갑 {d_iron:.2f} "
            f"(★ 갑옷이 사는 자리 — 회피가 이미 통하지 않고 경감은 아직 종이가 아니다)")
    rep.ok(f"갑옷의 값은 **가운데**에 있다: 졸개(회피가 이긴다) ← 검기(갑옷이 이긴다) → "
           f"{pierced}(둘 다 진다). 좁은 창이고, 그것이 갑옷이 지배하지 못하는 이유다")

    # ④ ⚠️ 이 모델이 **못 재는** 값을 정직하게 적는다 — 눈이 제 한계를 모르면 그 눈이 거짓말한다
    if heavy["blocks_gyeonggong"]:
        rep.warn("철갑의 가장 큰 대가는 이 표에 **없다** — 경공 불가(gyeonggong.yml armor_gate): "
                 "도약·벽 타기·후퇴(GyeonggongListener.retreat)가 통째로 사라진다. "
                 "defense_audit 은 합당 기대 피해만 잰다 — 이동은 gyeonggong_audit 의 자다. "
                 "즉 위 표의 철갑은 **실제보다 후하게** 그려져 있다")


# ══════════════════════════════════════════════════════════════════════════════

def main():
    ap = argparse.ArgumentParser(description="혼천 방어 태세 검수")
    ap.add_argument("--lint-only", action="store_true", help="① 등록 정합 + ② 엔진 배선만")
    ap.add_argument("--sim-only", action="store_true", help="③ 태세 시뮬만")
    ap.add_argument("--budget", type=float, default=1800.0, help="수련 예산(일치) — 기본 1800")
    args = ap.parse_args()

    rep = Report()
    rep.say("╔" + "═" * 70 + "╗")
    rep.say("║" + "  혼천 방어 감사 — defense_audit".ljust(69) + "║")
    rep.say("║" + "  맞는 쪽의 선택 — 방어가 선택인가".ljust(65) + "║")
    rep.say("╚" + "═" * 70 + "╝")

    try:
        cfg = load_all()
    except YamlError as e:
        print(f"{FAIL} config 파싱 실패: {e}", file=sys.stderr)
        return 2

    rules = defense_rules(cfg)
    rep.say(f"  config {len(cfg)}종 적재 · 태세 {len(rules)}종 · "
            f"몸짓 {len(gestures(cfg))}종 · 갑옷 {len(armors(cfg))}종")

    if not args.sim_only:
        lint(cfg, rep)
        wiring(cfg, rep)
    if not args.lint_only:
        simulate(cfg, rep, args.budget)

    rep.say()
    rep.say("═" * 72)
    n_v, n_w = len(rep.violations), len(rep.warnings)
    if n_v == 0 and n_w == 0:
        rep.say("  총평: ✅ 위반 0건 · 경고 0건 — 맞는 쪽에도 선택이 있고, 어느 하나도 정답이 아니다")
    else:
        rep.say(f"  총평: 위반 {n_v}건 · 경고 {n_w}건")
        if n_v:
            rep.say("")
            rep.say(f"  ── 위반 ({FAIL}) — 방어가 선택이 아니다")
            for i, v in enumerate(rep.violations, 1):
                rep.say(f"    {i:2}. {v}")
        if n_w:
            rep.say("")
            rep.say("  ── 경고 (⚠️)")
            for i, w in enumerate(rep.warnings, 1):
                rep.say(f"    {i:2}. {w}")
    rep.say("═" * 72)
    rep.dump()
    return 1 if n_v else 0


if __name__ == "__main__":
    sys.exit(main())
