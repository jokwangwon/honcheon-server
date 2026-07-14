#!/usr/bin/env python3
"""안전 지역 감사 — **관아 앞마당에서 정말 칼이 서지 않는가**를 재는 눈 (B-006).

training.yml 의 location_safety 는 오래 적혀 있었는데 읽는 자바가 0줄이었다 — 등록부는
"관아는 안전"이라 말하는데 세계에선 관아 앞마당에서 사람이 베였다. PvP 를 막는 코드는
Sparring(합의)뿐이었고, 합의는 안전이 아니다. 설계는 명시적이다:
"PvP는 상시 가능(공유 세계) — 단 안전 지역(관아·문파 내부)은 예외 (location_safety)"
(docs/design/party_and_cooperation.md §6).

이 문도 **조용히 열릴 수 있는 종류의 문**이다: 게이트가 죽어도 아무 증상이 없다 —
관아에서 베인 사람이 "원래 그런 줄" 알면 신고도 없다. 그러므로 이 도구가 지킨다.

  ① **독자가 있는가**        SkillListener 가 training.yml 의 location_safety 를 정말 읽는가 —
                            그리고 매칭 어휘(zone_keywords · archetypes)까지 읽는가 (반쪽 독자는 독자가 아니다)
  ② **길목에 문이 서 있는가**  세 판정길 전부: onMelee(맨 앞 — 바닐라·화살) · basicJudged(베는 순간) ·
                            admit(초식의 히트박스). 문이 하나라도 빠지면 그 길로 칼이 샌다
  ③ **어휘가 정합한가**       config 의 level 어휘(안전·보통·위험)가 서 있고, 안전 분류에 매칭 어휘가
                            있으며, archetypes 가 등록된 원형(RemoteBuilder.Archetype)만 부르는가.
                            그리고 코드가 게이트 기준으로 삼는 말("안전")이 config 에 실재하는가
  ④ **문이 죽은 가지가 아닌가** safetyBlocks 가 정말 안전도를 묻고(safetyLevel) · 정말 취소하는가
                            (setCancelled) · 비무(합의)의 예외가 서 있는가 (sect_life 의 비무 서열전)

config·소스를 고치지 않는다 — 재기만 한다. 어휘·지명은 전부 등록부에서 읽는다.

사용법:  python3 tools/safety_audit.py
눈을 시험하려면:  python3 tools/safety_audit_selftest.py
종료 코드: 위반(❌) 1건 이상이면 1.
"""

from __future__ import annotations

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from game_audit import Report, load_all, dig  # noqa: E402  — 문법·출력 형식 계승

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LISTENER = os.path.join(ROOT, "server-mvt/src/main/java/com/honcheon/mvt/SkillListener.java")
REMOTE = os.path.join(ROOT, "server-mvt/src/main/java/com/honcheon/mvt/RemoteBuilder.java")

# 정본 어휘 — training.yml location_safety 의 level (이 세 마디가 전부다)
LEVELS = ("안전", "보통", "위험")
SAFE = "안전"

# 게이트가 서야 하는 세 길목 — 사람에게 피해가 실리기 전의 자리들
GATES = (
    ("onMelee — 길목의 맨 앞 (바닐라 평타·화살이 이 문을 지난다)",
     r"public void onMelee\(EntityDamageByEntityEvent(?:(?!\n    \}).)*?safetyBlocks\("),
    ("basicJudged — 벼른 뒤 베는 순간 (선딜 사이의 이동을 본다)",
     r"private BasicHit basicJudged\([^{]*\{\s*(?://[^\n]*\n\s*)*if \(safetyBlocks\("),
    ("admit — 초식의 히트박스가 가려낼 때 (광역이 이 문을 지난다)",
     r"List<LivingEntity> admit\(.*?safetyBlocks\("),
)


def source() -> str:
    with open(LISTENER, encoding="utf-8") as f:
        return f.read()


def registered_archetypes() -> set:
    """RemoteBuilder.Archetype — 등록된 원형의 이름들 (enum 본문에서 읽는다)"""
    with open(REMOTE, encoding="utf-8") as f:
        text = f.read()
    m = re.search(r"enum Archetype \{(.*?)\n    \}", text, re.S)
    if not m:
        return set()
    names = set()
    for line in m.group(1).splitlines():
        line = line.split("//")[0]
        for tok in re.findall(r"[가-힣]+", line):
            names.add(tok)
    return names


def audit() -> Report:
    rep = Report()
    src = source()
    cfg = load_all()
    table = dig(cfg.get("training.yml", {}), "location_safety")

    # ── ① 독자 ──────────────────────────────────────────────────────────────
    rep.head("① 독자 — 자바가 location_safety 를 읽는가")
    rep.verdict(re.search(r'resolve\("training\.yml"\)', src) is not None
                and '"location_safety"' in src,
                "SkillListener 가 training.yml 의 location_safety 섹션을 연다")
    rep.verdict('"zone_keywords"' in src and '"archetypes"' in src,
                "매칭 어휘(zone_keywords · archetypes)까지 읽는다 — 반쪽 독자가 아니다")

    # ── ② 길목 ──────────────────────────────────────────────────────────────
    rep.head("② 길목 — 피해가 사람에게 실리기 전의 세 문")
    for name, pattern in GATES:
        rep.verdict(re.search(pattern, src, re.S) is not None, name)

    # ── ③ 어휘 ──────────────────────────────────────────────────────────────
    rep.head("③ 어휘 — 등록부(training.yml)와 코드가 같은 말을 쓰는가")
    if not isinstance(table, dict) or not table:
        rep.fail("training.yml 에 location_safety 가 없다 — 등록부가 침묵하면 게이트도 침묵한다")
    else:
        levels = {}
        for category, spec in table.items():
            level = dig(spec, "level")
            levels[category] = level
            rep.verdict(level in LEVELS,
                        f"{category}: level 「{level}」 은 정본 어휘다 (안전·보통·위험)")
        safe_cats = [c for c, l in levels.items() if l == SAFE]
        rep.verdict(bool(safe_cats), f"안전({SAFE}) 등급의 분류가 있다: {safe_cats}")
        for c in safe_cats:
            kws = dig(table, c, "zone_keywords") or []
            archs = dig(table, c, "archetypes") or []
            rep.verdict(bool(kws) or bool(archs),
                        f"{c}: 매칭 어휘가 있다 (zone_keywords {len(kws)} · archetypes {len(archs)})"
                        + " — 어디가 안전인지 등록부가 말한다")
        registered = registered_archetypes()
        if registered:
            for category in table:
                ghosts = [a for a in (dig(table, category, "archetypes") or [])
                          if a not in registered]
                rep.verdict(not ghosts,
                            f"{category}: archetypes 가 전부 등록된 원형이다 (RemoteBuilder.Archetype)"
                            + (f" — 유령: {ghosts}" if ghosts else ""))
        else:
            rep.warn("RemoteBuilder.Archetype 을 못 읽었다 — 원형 대조를 건너뛴다")
        rep.verdict(f'SAFE_LEVEL = "{SAFE}"' in src,
                    f"코드의 게이트 기준(SAFE_LEVEL)이 정본 어휘 「{SAFE}」 그대로다")

    # ── ④ 죽은 가지 ─────────────────────────────────────────────────────────
    rep.head("④ 문이 산 문인가 — safetyBlocks 의 몸통")
    block = re.search(r"private boolean safetyBlocks\(.*?\n    \}", src, re.S)
    body = block.group(0) if block else ""
    rep.verdict("safetyLevel(" in body, "안전도를 정말 묻는다 (safetyLevel — 존 체계 zoneAt 경유)")
    rep.verdict("isSparring" in body,
                "비무(합의)의 예외가 서 있다 — 문파 내부의 비무 서열전이 산다 (sect_life.md)")
    on_melee = re.search(r"public void onMelee\(.*?\n    \}", src, re.S)
    rep.verdict(on_melee is not None
                and re.search(r"safetyBlocks\(.*?\)\)\s*\{\s*\n\s*event\.setCancelled\(true\);",
                              on_melee.group(0), re.S) is not None,
                "onMelee 의 문이 정말 닫는다 (setCancelled)")

    return rep


def main() -> int:
    rep = audit()
    rep.dump()
    print()
    if rep.violations:
        print(f"❌ 위반 {len(rep.violations)}건 — 관아 앞마당에서 사람이 베인다")
        return 1
    print("✅ 위반 0건 — 이 판정의 눈이 멀지 않았는지는 safety_audit_selftest.py 가 잰다")
    return 0


if __name__ == "__main__":
    sys.exit(main())
