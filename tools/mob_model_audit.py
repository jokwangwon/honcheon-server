#!/usr/bin/env python3
"""몹 형체 감사 — 적이 제 모습으로 서 있는가를 재는 자.

무공에는 `motion_audit.py`, 팩에는 `texture_audit.py`, 전투에는 `combat_audit.py` 가 있다.
**몹의 형체**에는 없었다. 호랑이는 라바저였고, 아무도 그것을 세지 않았다.

이 도구는 config/mob_models.yml · npcs/cheongha_npcs.yml · HuntingGrounds.java · resourcepack/ 를
읽어 여섯 가지를 잰다:

  ① 커버리지  — 등록부의 적 전부가 형체를 갖는가 (replace 든 **근거를 적은 vanilla** 든).
                근거 없는 vanilla 는 형체가 아니라 **방치**다.
  ② 본체 정합 — mob_models.yml 의 body 가 실제로 스폰되는 몸(HuntingGrounds.DEFAULT_ENTITY ·
                npcs 의 mc_entity)과 같은가. 【감출 몸을 잘못 알면 엉뚱한 몸이 투명해진다】
  ③ 모델 키    — 키가 팩에 구워져 있는가 (resourcepack/assets/honcheon/models/ 대조).
                안 구워졌으면 **팩 담당에게 넘길 목록**을 그대로 출력한다 (형체 요구 포함).
  ④ 예산       — 사냥터 정원으로 형체 수를 세어 degrade_at·global_cap 을 넘지 않는가.
                performance.yml 의 npc_logic 예산 안에 이 층의 몫이 들어가는가.
  ⑤ 형태 요구  — size·offset·파츠 수가 등록부의 상한과 상식 안에 있는가.
  ⑥ 배선       — MobDisplay 가 등록부를 읽는가 · 스폰이 형체에 배선됐는가 ·
                **코드에 모델 키·치수가 하드코딩되지 않았는가** (등록제 규약).

config 를 고치지 않는다 — 재기만 한다.

사용법:
    python3 tools/mob_model_audit.py                 # 전체
    python3 tools/mob_model_audit.py --pack-list     # 팩 담당에게 넘길 목록만

외부 라이브러리 없음 (game_audit.py 의 YAML 서브셋 파서·Report 를 그대로 계승).
종료 코드: 위반(❌) 1건 이상이면 1, 아니면 0.
"""

from __future__ import annotations

import argparse
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
PACK = os.path.join(ROOT, "resourcepack", "assets", "honcheon")

# 짐승·사람의 상식 치수 (m) — 이 밖이면 등록부가 실수한 것이다
SIZE_MIN, SIZE_MAX = 0.05, 6.0
OFFSET_MAX = 3.0


# ══════════════════════════════════════════════════════════════════════════════
#  판독 도우미 — 코드에서 등록 대기 수치를 캐낸다 (HuntingGrounds 가 아직 정본이다)
# ══════════════════════════════════════════════════════════════════════════════

def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def hunting_source():
    return read(os.path.join(MVT, "HuntingGrounds.java"))


def default_entities(src):
    """HuntingGrounds.DEFAULT_ENTITY — 실제로 스폰되는 바닐라 몸 (config 등록 대기 표)."""
    block = re.search(r"DEFAULT_ENTITY\s*=\s*Map\.of\((.*?)\);", src, re.S)
    if not block:
        return {}
    return {
        m.group(1): m.group(2)
        for m in re.finditer(r'"([a-z_]+)"\s*,\s*EntityType\.([A-Z_]+)', block.group(1))
    }


def populations(src):
    """HuntingGrounds.POPULATIONS — 구역별 낮/밤 정원 (형체 수를 세는 근거)."""
    block = re.search(r"POPULATIONS\s*=\s*Map\.of\((.*?)\n    \);?", src, re.S)
    if not block:
        block = re.search(r"POPULATIONS\s*=\s*Map\.of\((.*?)\)\);", src, re.S)
    if not block:
        return {}
    out = {}
    for m in re.finditer(r'new Quota\("([a-z_]+)",\s*(\d+),\s*(\d+)\)', block.group(1)):
        out[m.group(1)] = (int(m.group(2)), int(m.group(3)))
    return out


def zone_cap(src):
    m = re.search(r"ZONE_ENTITY_CAP\s*=\s*(\d+)", src)
    return int(m.group(1)) if m else 18


def foe_ids(cfg):
    """전투에 서는 적 — DEFAULT_ENTITY 에 몸을 징발당한 npcs 등록부의 개체."""
    return default_entities(hunting_source())


def model_paths(key):
    """honcheon:mob/horangi/body → 팩이 구워야 할 두 장."""
    if ":" not in key:
        return None, None
    ns, path = key.split(":", 1)
    return (
        os.path.join(ROOT, "resourcepack", "assets", ns, "models", path + ".json"),
        os.path.join(ROOT, "resourcepack", "assets", ns, "items", path + ".json"),
    )


def parts_of(entry):
    p = entry.get("parts")
    return p if isinstance(p, list) else []


# ══════════════════════════════════════════════════════════════════════════════
#  ① 커버리지 — 등록부의 적 전부가 형체를 갖는가
# ══════════════════════════════════════════════════════════════════════════════

def audit_coverage(mm, foes, rep):
    rep.head("① 커버리지 — 등록부의 적 전부가 형체를 갖는가")
    reg = mm.get("foes") or {}
    missing = [f for f in foes if f not in reg]
    rep.verdict(not missing,
                f"적 {len(foes)}종 중 {len(foes) - len(missing)}종이 mob_models.yml 에 등록됐다"
                + (f" — 누락: {', '.join(missing)}" if missing else ""))

    replaced, vanilla = [], []
    for fid, entry in reg.items():
        if not isinstance(entry, dict):
            rep.fail(f"{fid}: 등록부의 한 줄이 매핑이 아니다")
            continue
        shape = entry.get("shape")
        if shape == "replace":
            replaced.append(fid)
            if not parts_of(entry):
                rep.fail(f"{fid}: shape=replace 인데 parts 가 없다 — 감출 몸만 있고 실을 형체가 없다")
        elif shape == "vanilla":
            vanilla.append(fid)
            # 【근거 없는 vanilla 는 형체가 아니라 방치다】
            if not str(entry.get("reason") or "").strip():
                rep.fail(f"{fid}: shape=vanilla 인데 reason 이 없다 — 그대로 두는 데도 근거가 필요하다")
        else:
            rep.fail(f"{fid}: shape 가 replace|vanilla 가 아니다 ({shape!r})")

    rep.say(f"     · 형체 교체(replace) {len(replaced)}종: {', '.join(replaced) or '없음'}")
    rep.say(f"     · 그대로 둠(vanilla) {len(vanilla)}종: {', '.join(vanilla) or '없음'}")
    covered = len(foes) - len(missing)
    return (100.0 * covered / len(foes)) if foes else 0.0, replaced, vanilla


# ══════════════════════════════════════════════════════════════════════════════
#  ② 본체 정합 — 감출 몸을 잘못 알면 엉뚱한 몸이 투명해진다
# ══════════════════════════════════════════════════════════════════════════════

def audit_body(mm, cfg, rep):
    rep.head("② 본체 정합 — 등록부의 body 가 실제로 스폰되는 몸과 같은가")
    src = hunting_source()
    actual = default_entities(src)
    npcs = dig(cfg, "npcs/cheongha_npcs.yml", "npcs", default={}) or {}
    for fid, entry in (mm.get("foes") or {}).items():
        if not isinstance(entry, dict):
            continue
        want = str(entry.get("body") or "").upper()
        # npcs 의 mc_entity 가 등록되면 그쪽이 이긴다 (HuntingGrounds.entityTypeOf)
        registered = dig(npcs, fid, "mc_entity")
        real = str(registered or actual.get(fid) or "").upper()
        if not real:
            rep.fail(f"{fid}: 스폰되는 몸을 찾을 수 없다 (DEFAULT_ENTITY·mc_entity 어디에도 없다)")
        elif want != real:
            rep.fail(f"{fid}: 등록부 body={want} 인데 실제로 스폰되는 몸은 {real} 다 "
                     f"— 감출 몸을 잘못 알면 엉뚱한 몸이 투명해진다")
        else:
            rep.ok(f"{fid}: {real} — 등록부와 코드가 같은 몸을 본다")


# ══════════════════════════════════════════════════════════════════════════════
#  ③ 모델 키 — 팩에 구워졌는가 · 안 구워졌으면 넘길 목록
# ══════════════════════════════════════════════════════════════════════════════

def audit_keys(mm, rep, pack_list_only=False):
    if not pack_list_only:
        rep.head("③ 모델 키 — 팩이 그 키로 3D 를 구웠는가")
    todo = []
    seen = set()
    for fid, entry in (mm.get("foes") or {}).items():
        if not isinstance(entry, dict) or entry.get("shape") != "replace":
            continue
        for part in parts_of(entry):
            if not isinstance(part, dict):
                continue
            key = part.get("key")
            if not key or ":" not in str(key):
                rep.fail(f"{fid}/{part.get('id')}: 모델 키가 없거나 네임스페이스가 없다 ({key!r})")
                continue
            if key in seen:
                rep.fail(f"{fid}/{part.get('id')}: 모델 키가 중복이다 ({key}) — 두 조각이 한 몸을 쓴다")
            seen.add(key)
            model_json, item_json = model_paths(key)
            if os.path.isfile(model_json) and os.path.isfile(item_json):
                if not pack_list_only:
                    rep.ok(f"{fid}/{part.get('id')}: {key} — 팩에 있다")
            else:
                todo.append((fid, entry, part, key, model_json, item_json))

    if todo and not pack_list_only:
        rep.warn(f"팩에 없는 모델 키 {len(todo)}개 — 팩 담당이 아직 굽지 않았다 (형체 층은 조용히 잠든다)")
    return todo


def print_pack_list(todo, mm, rep):
    """팩 담당에게 그대로 넘길 목록 — 키 · 형체 · 크기 · 자세 요구."""
    rep.head("팩 담당에게 넘길 모델 키 목록 (아직 굽지 않은 것)")
    if not todo:
        rep.ok("전부 구워져 있다 — 넘길 것이 없다")
        return
    rep.say("  【치수 계약】 모델은 1×1×1 블록 정육면체(16px 그리드)에 맞춰 굽는다.")
    rep.say("  엔진이 size 를 Transformation.scale 로 그대로 곱한다 ⇒ size = 실제 미터 치수.")
    rep.say("  【방향 계약】 코가 +Z(남쪽), 등이 +Y, 오른쪽이 +X. 【원점】 발이 딛는 바닥의 정중앙.")
    rep.say("  팩은 두 장을 굽는다: assets/honcheon/items/<경로>.json · assets/honcheon/models/<경로>.json")
    last = None
    for fid, entry, part, key, model_json, item_json in todo:
        if fid != last:
            rep.say("")
            rep.say(f"  ── {entry.get('name', fid)} ({fid}) — 본체 {entry.get('body')} "
                    f"→ 커스텀 형체")
            rep.say(f"     사유: {str(entry.get('reason') or '').strip()}")
            last = fid
        size = part.get("size") or []
        off = part.get("offset") or []
        rep.say(f"     · {key}")
        rep.say(f"         파츠: {part.get('id')} ({part.get('role')}) · base={part.get('base')}")
        rep.say(f"         크기(길이·높이·폭, m): {size}   오프셋(우·상·전, m): {off}")
        pose = str(part.get("pose") or "").strip()
        if pose:
            rep.say(f"         자세: {pose}")
        rep.say(f"         구울 곳: {os.path.relpath(item_json, ROOT)}")
        rep.say(f"                  {os.path.relpath(model_json, ROOT)}")

    # 예약분 — 지금은 vanilla 지만 팩 담당이 구우면 shape 한 줄로 켜진다
    reserved = [(fid, e) for fid, e in (mm.get("foes") or {}).items()
                if isinstance(e, dict) and e.get("shape") == "vanilla" and e.get("reserved_model")]
    if reserved:
        rep.say("")
        rep.say("  ── 예약(선택) — 구우면 shape: vanilla → replace 한 줄로 켜진다 (코드 수정 없음)")
        for fid, e in reserved:
            rep.say(f"     · {e['reserved_model']}  ({e.get('name', fid)}, "
                    f"크기 {e.get('reserved_size')})")


# ══════════════════════════════════════════════════════════════════════════════
#  ④ 예산 — 사냥터에 몹 20마리면 디스플레이가 몇 개인가
# ══════════════════════════════════════════════════════════════════════════════

def audit_budget(mm, cfg, rep):
    rep.head("④ 예산 — 사냥터 정원으로 형체 수를 센다 (performance.yml)")
    b = mm.get("budget") or {}
    cap = int(b.get("global_cap") or 0)
    degrade = int(b.get("degrade_at") or 0)
    per_mob = int(b.get("per_mob_max") or 0)
    budget_ms = float(b.get("budget_ms") or 0)

    npc_logic = dig(cfg, "performance.yml", "tick_budget", "subsystem_budget_ms", "npc_logic", default=6)
    rep.verdict(0 < budget_ms <= float(npc_logic),
                f"형체 층 틱 예산 {budget_ms}ms ≤ npc_logic {npc_logic}ms (performance.yml)")
    rep.verdict(0 < degrade < cap,
                f"degrade_at {degrade} < global_cap {cap} — 강등 여유가 있다")

    src = hunting_source()
    pops = populations(src)
    cap_zone = zone_cap(src)
    reg = mm.get("foes") or {}

    def parts_count(fid):
        e = reg.get(fid)
        if not isinstance(e, dict) or e.get("shape") != "replace":
            return 0
        return min(len(parts_of(e)), per_mob or 99)

    for when, idx in (("낮", 0), ("밤", 1)):
        mobs = sum(q[idx] for q in pops.values())
        disp = sum(q[idx] * parts_count(fid) for fid, q in pops.items())
        detail = " · ".join(f"{fid}×{q[idx]}→{q[idx] * parts_count(fid)}"
                            for fid, q in pops.items() if q[idx])
        rep.say(f"     {when} — 몸 {mobs}마리 → 형체 {disp}개   ({detail})")
        rep.verdict(disp <= degrade,
                    f"{when} 정상 상태 형체 {disp}개 ≤ degrade_at {degrade}")

    worst_parts = max([parts_count(f) for f in reg] or [0])
    worst = cap_zone * worst_parts
    rep.verdict(worst <= cap,
                f"최악(구역 상한 {cap_zone}마리가 전부 최대 파츠 {worst_parts}개) → 형체 {worst}개 ≤ global_cap {cap}")
    cluster = dig(cfg, "performance.yml", "load_test", "combat_cluster_size", default=20)
    cluster_worst = int(cluster) * worst_parts
    rep.verdict(cluster_worst <= cap,
                f"부하 시험 군집 {cluster}마리 전부 최대 파츠 → 형체 {cluster_worst}개 ≤ global_cap {cap}")

    # 시야 거리 — 무공 획(cull_beyond 32m)보다 멀되 무한하지 않다
    view = float(b.get("view_range") or 0)
    cull = float(dig(cfg, "performance.yml", "particles", "lod", "cull_beyond", default=32))
    rep.verdict(view * 64 >= cull,
                f"시야 거리 {view} × 64 = {view * 64:.0f}m ≥ 파티클 cull_beyond {cull:.0f}m "
                f"— 짐승은 무공 획보다 멀리서 보여야 한다 (그것이 사냥이다)")
    for fid, e in reg.items():
        if isinstance(e, dict) and e.get("view_range") and float(e["view_range"]) > 1.0:
            rep.warn(f"{fid}: view_range {e['view_range']} > 1.0 (64m 초과) — 엔티티 추적 거리를 넘길 수 있다")


# ══════════════════════════════════════════════════════════════════════════════
#  ⑤ 형태 요구 — size · offset · 파츠 수
# ══════════════════════════════════════════════════════════════════════════════

def audit_shape(mm, rep):
    rep.head("⑤ 형태 요구 — 크기·오프셋·파츠가 상식과 상한 안에 있는가")
    per_mob = int((mm.get("budget") or {}).get("per_mob_max") or 4)
    ok = True
    for fid, entry in (mm.get("foes") or {}).items():
        if not isinstance(entry, dict) or entry.get("shape") != "replace":
            continue
        parts = parts_of(entry)
        if len(parts) > per_mob:
            rep.fail(f"{fid}: 파츠 {len(parts)}개 > per_mob_max {per_mob} — 엔티티 수가 곱절이 된다")
            ok = False
        roles = [p.get("role") for p in parts if isinstance(p, dict)]
        if "body" not in roles:
            rep.fail(f"{fid}: role=body 인 조각이 없다 — 몸통 없는 짐승은 없다")
            ok = False
        for part in parts:
            if not isinstance(part, dict):
                continue
            tag = f"{fid}/{part.get('id')}"
            size = part.get("size")
            if not isinstance(size, list) or len(size) != 3:
                rep.fail(f"{tag}: size 가 [길이, 높이, 폭] 3원소가 아니다")
                ok = False
            elif not all(isinstance(v, (int, float)) and SIZE_MIN <= v <= SIZE_MAX for v in size):
                rep.fail(f"{tag}: size {size} 가 상식 범위({SIZE_MIN}~{SIZE_MAX}m)를 벗어났다")
                ok = False
            off = part.get("offset")
            if not isinstance(off, list) or len(off) != 3:
                rep.fail(f"{tag}: offset 이 3원소가 아니다")
                ok = False
            elif any(abs(v) > OFFSET_MAX for v in off if isinstance(v, (int, float))):
                rep.fail(f"{tag}: offset {off} 이 본체에서 {OFFSET_MAX}m 넘게 떨어졌다 — 형체가 몸을 떠난다")
                ok = False
            if part.get("role") == "head" and not part.get("head_yaw_max"):
                rep.warn(f"{tag}: role=head 인데 head_yaw_max 가 없다 — 고개가 표적을 보지 않는다")
            if part.get("role") == "tail" and not part.get("sway_deg"):
                rep.warn(f"{tag}: role=tail 인데 sway_deg 가 없다 — 꼬리가 살아 있지 않다")
            if not str(part.get("pose") or "").strip():
                rep.warn(f"{tag}: pose 가 없다 — 팩 담당이 무엇을 구울지 모른다")
        # 움직임의 표정 — 하나도 없으면 조각상이다
        mo = entry.get("motion") or {}
        if not any(float(mo.get(k) or 0) for k in
                   ("walk_bob", "walk_roll_deg", "attack_lean_deg", "charge_lean_deg")):
            rep.fail(f"{fid}: motion 이 비어 있다 — 움직임의 표정이 없으면 미끄러지는 조각상이다")
            ok = False
        if not float(mo.get("death_topple_deg") or 0):
            rep.warn(f"{fid}: death_topple_deg 가 없다 — 죽어도 서 있다")
    if ok:
        rep.ok("모든 replace 개체의 크기·오프셋·파츠가 상한 안에 있다")


# ══════════════════════════════════════════════════════════════════════════════
#  ⑥ 배선 — 등록제 규약 (코드가 config 보다 앞서지 않는다)
# ══════════════════════════════════════════════════════════════════════════════

def audit_wiring(rep):
    rep.head("⑥ 배선 — 등록부가 코드에 닿아 있는가 (등록제 규약)")
    md_path = os.path.join(MVT, "MobDisplay.java")
    if not os.path.isfile(md_path):
        rep.fail("MobDisplay.java 가 없다 — 등록부를 읽는 엔진이 없다")
        return
    md = read(md_path)
    hg = hunting_source()

    rep.verdict('"mob_models.yml"' in md, "MobDisplay 가 config/mob_models.yml 을 읽는다")
    rep.verdict("mobDisplay.attach(" in hg, "HuntingGrounds.spawn 이 형체를 붙인다 (attach 배선)")
    rep.verdict("MobDisplay.init(" in hg, "HuntingGrounds.init 이 형체 등록부를 적재한다")
    rep.verdict("setInvisible(true)" in md, "본체를 감춘다 (setInvisible)")
    rep.verdict("setInvisible(false)" in md, "형체가 없으면 본체를 되돌린다 (불변식 ㄱ — 형체는 덧칠이다)")
    rep.verdict("PlayerResourcePackStatusEvent" in md, "팩 수락 여부를 듣는다 (팩 게이트)")
    rep.verdict("setTeleportDuration" in md and "setInterpolationDuration" in md,
                "이동·형체 보간이 배선됐다 (이 둘이 0이면 형체가 튄다)")
    rep.verdict("runTaskTimer" in md and md.count("runTaskTimer") == 1,
                "중앙 티커 1개 (performance.yml F-P2 — 개체별 태스크 생성 금지)")
    rep.verdict("EntityDeathEvent" in md, "죽을 때 쓰러진다 (형체가 몸보다 오래 산다)")

    # 하드코딩 금지 — 모델 키·치수가 코드에 박혀 있으면 등록부는 거짓말이 된다
    keys = re.findall(r'"honcheon:mob/[^"]+"', md)
    rep.verdict(not keys,
                "코드에 모델 키가 하드코딩되지 않았다"
                + (f" — 발견: {', '.join(sorted(set(keys)))}" if keys else ""))
    magic = re.findall(r'new Vector3f\(\s*([0-9]+\.[0-9]+f?)\s*,', md)
    rep.verdict(not magic,
                "코드에 치수가 하드코딩되지 않았다 (Vector3f 리터럴)"
                + (f" — 발견: {', '.join(sorted(set(magic)))}" if magic else ""))


# ══════════════════════════════════════════════════════════════════════════════

def main():
    ap = argparse.ArgumentParser(description="몹 형체 감사")
    ap.add_argument("--pack-list", action="store_true", help="팩 담당에게 넘길 목록만 출력")
    args = ap.parse_args()

    try:
        cfg = load_all()
    except YamlError as e:
        print(f"{FAIL} config 판독 실패: {e}")
        return 1
    mm = cfg.get("mob_models.yml")
    rep = Report()
    if not mm:
        rep.fail("config/mob_models.yml 이 없다 — 몹 형체 등록부가 없다")
        rep.dump()
        return 1

    foes = foe_ids(cfg)
    if args.pack_list:
        todo = audit_keys(mm, rep, pack_list_only=True)
        print_pack_list(todo, mm, rep)
        rep.dump()
        return 0

    rep.say("═" * 72)
    rep.say("  몹 형체 감사 — 적이 제 모습으로 서 있는가")
    rep.say("═" * 72)

    pct, replaced, vanilla = audit_coverage(mm, foes, rep)
    audit_body(mm, cfg, rep)
    todo = audit_keys(mm, rep)
    audit_budget(mm, cfg, rep)
    audit_shape(mm, rep)
    audit_wiring(rep)
    print_pack_list(todo, mm, rep)

    rep.say()
    rep.say("═" * 72)
    n_v, n_w = len(rep.violations), len(rep.warnings)
    rep.say(f"  커버리지: 적 {pct:.1f}% (형체 교체 {len(replaced)}종 · 등록된 예외 {len(vanilla)}종)")
    rep.say(f"  팩 대기: 모델 키 {len(todo)}개")
    if n_v == 0 and n_w == 0:
        rep.say("  총평: ✅ 위반 0건 · 경고 0건 — 적은 제 모습으로 서 있다")
    else:
        rep.say(f"  총평: 위반 {n_v}건 · 경고 {n_w}건")
        if n_v:
            rep.say("")
            rep.say(f"  ── 위반 ({FAIL}) — 형체가 등록부와 어긋나거나, 판정을 위협한다")
            for i, v in enumerate(rep.violations, 1):
                rep.say(f"    {i:2}. {v}")
        if n_w:
            rep.say("")
            rep.say("  ── 경고 (⚠️) — 굴러가지만 아직 그 모습이 아니다")
            for i, w in enumerate(rep.warnings, 1):
                rep.say(f"    {i:2}. {w}")
    rep.say("═" * 72)
    rep.dump()
    return 1 if n_v else 0


if __name__ == "__main__":
    sys.exit(main())
