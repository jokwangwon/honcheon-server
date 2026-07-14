#!/usr/bin/env python3
"""세계 다리 검산(檢算) — 마크의 사건이 정말 세계에 닿았는가.

다리가 놓였다고 세계가 이어진 것이 아니다. 물어야 할 것은 넷이다:

  ① 배선     등록된 이벤트 종류마다 — MVT 가 보내고(발신부), 봇이 받고(수신부),
              세계가 바뀌는가(소문·지역·세력·혈채). 셋 중 하나라도 비면 그 사건은 허공에 뜬다.
  ② 신원     ★ **주체 없는 사건의 비율.** 접합되지 않은 몸이 저지른 일은 세계가 못 읽는다 —
              소문에 이름이 안 붙고, 세력이 아무도 주목하지 않는다. 세계의 절반이 남의 일이 된다.
  ③ 혈채     암혈채는 **감쇠하지 않는가.** 무장 상대·관인은 **0인가.** 두 원장이 정합한가.
  ④ 되먹임   봇의 스냅숏이 마크로 내려가는가. ★ **혈채 수치가 새어 나가지는 않는가**
              (blood_debt.visibility: 내부 — 내려가는 것은 세계의 반응뿐이다).
  ⑤ 사슬     ★★ **"죽였다 · 재기동했다 · 아직도 나를 쫓는가?"**
              ①~④ 는 다리를 잰다. 다리가 멀쩡해도 세계는 반쪽일 수 있다 —
              살인 → 혈채 → 수배 → **스냅숏** → 마크가 그것을 **읽는가**.
              그리고 정본이 하나인가 (MVT 가 제 수배·favor·지역 장부를 다시 파지 않았는가).
              한 칸이라도 끊기면 플레이어는 열 명을 죽이고 다시 접속해 **아무 일 없는 마을**을 본다.

규약: **DB 를 읽기 전에 백업한다** (봇은 단일 작성자다. 이 도구는 읽기만 하지만 규약은 규약이다).

사용법:
    python3 tools/bridge_audit.py                      # run/bot/honcheon.db
    python3 tools/bridge_audit.py <db경로> [--no-backup]
"""

import argparse
import json
import re
import shutil
import sqlite3
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DB = ROOT / "run" / "bot" / "honcheon.db"
BRIDGE_DIR = ROOT / "run" / "bridge"

OK, NO, WARN = "✅", "❌", "⚠️"

# ─── 등록부 (config 가 정본 — 여기서 발명하지 않는다) ───────────────────────────


def load_registry():
    """world_bridge.yml events + faction_reaction.yml blood_debt (순수 파서 — pyyaml 없이도 돌게)."""
    try:
        import yaml
    except ImportError:
        sys.exit("pyyaml 이 필요하다: pip install pyyaml")
    bridge = yaml.safe_load((ROOT / "config" / "world_bridge.yml").read_text(encoding="utf-8"))
    reaction = yaml.safe_load((ROOT / "config" / "faction_reaction.yml").read_text(encoding="utf-8"))
    return bridge, reaction.get("blood_debt", {})


# MVT 발신부 — 이 kind 를 **실제로 부르는 자**가 마크에 있는가.
#
# 두 가지 발신 방식을 다 본다 (등록부가 앞서 가고 코드가 따라오므로 kind 목록은 config 에서 온다):
#   ① 이름 붙은 손잡이 — WorldBridge.npcDeath(...) 처럼 kind 마다 파 놓은 문
#   ② 일반 발신      — WorldBridge.emit("surrender", ...) / KIND 표를 거쳐 나가는 것 (Incidents)
# 손잡이가 있어도 **부르는 자가 없으면** 그 사건은 마크에서 영원히 일어나지 않는다.
def mvt_emitters(kinds):
    bridge_src = src_of(MVT_DIR / "WorldBridge.java")
    handles = {"npc_death": "WorldBridge.npcDeath", "bandit_slain": "WorldBridge.banditSlain",
               "beast_slain": "WorldBridge.beastSlain", "qi_manifested": "WorldBridge.qiManifested",
               "sparring": "WorldBridge.sparring",
               # ★ 코드는 죽었다 (2026-07-13) — 접합의 발신은 이제 **그 몸의 수락**이다
               "link_confirm": "WorldBridge.linkDecision",
               "cultivation_logged": "WorldBridge.cultivationLogged"}
    emits, callers = {}, {}
    for kind in kinds:
        handle = handles.get(kind)
        emits[kind] = (f'emit("{kind}"' in bridge_src) or (handle is not None and handle.split(".")[1] + "(" in bridge_src)
        hits = []
        for path in sorted(MVT_DIR.glob("*.java")):
            if path.name == "WorldBridge.java":
                continue
            src = src_of(path)
            named = handle is not None and handle in src
            generic = f'"{kind}"' in src and "WorldBridge.emit(" in src
            if named or generic:
                hits.append(path.name)
        callers[kind] = hits
        if not emits[kind]:
            emits[kind] = bool(hits)   # 일반 발신은 WorldBridge 에 kind 리터럴이 없다 (등록부가 문지기다)
    return emits, callers


def bot_handlers(kinds):
    src = src_of(BOT_DIR / "Bridge.java")
    return {kind: f'case "{kind}"' in src for kind in kinds}


def bridge_delivery_atomic():
    """inbox 선점, 세계 변경, JSONL 커서가 한 트랜잭션인지 정적으로 확인한다."""
    bridge = src_of(BOT_DIR / "Bridge.java")
    db = src_of(BOT_DIR / "Db.java")
    checks = (
        "apply(line, checkpoint)" in bridge,
        "db.applyBridgeEvent(id, kind, cursorKey, checkpoint" in bridge,
        "setMeta(cursorKey, checkpoint)" in db,
        "conn.commit()" in db,
        "conn.rollback()" in db,
    )
    return all(checks), "handler 실패가 inbox·세계 상태·커서 중 일부만 남길 수 있다"


# ─── ⑤ 사슬(鎖) — 살인이 세계에 남긴 자국을 끝까지 따라간다 ────────────────────
#
# ★ 이 절이 이 도구의 이유다. ①~④ 는 **다리**를 잰다 (발신·수신·되먹임).
#   그러나 다리가 멀쩡해도 세계는 반쪽일 수 있다 — 물어야 할 것은 이것이다:
#
#       "마을 사람을 죽였다. 재기동했다. **아직도 나를 쫓는가?**"
#
#   이 물음이 참이 되려면 여섯 칸이 전부 이어져야 한다. 한 칸이라도 끊기면
#   플레이어는 열 명을 죽이고 다시 접속해서 **아무 일도 없는 마을**을 본다.

MVT_DIR = ROOT / "server-mvt" / "src" / "main" / "java" / "com" / "honcheon" / "mvt"
BOT_DIR = ROOT / "server-bot" / "src" / "main" / "java" / "com" / "honcheon" / "bot"
DOMAIN_DIR = ROOT / "domain" / "src" / "main" / "java" / "com" / "honcheon" / "domain"


def src_of(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else ""


def code_links():
    """코드가 사슬을 잇고 있는가 — DB 가 비어 있어도(아무도 안 죽였어도) 잴 수 있는 것들."""
    incidents = src_of(MVT_DIR / "Incidents.java")
    injections = src_of(BOT_DIR / "Injections.java")
    bot_all = "\n".join(src_of(p) for p in BOT_DIR.glob("*.java"))
    domain_all = "\n".join(src_of(p) for p in DOMAIN_DIR.glob("*.java"))
    mvt_all = "\n".join(src_of(p) for p in MVT_DIR.glob("*.java") if p.name != "WorldBridge.java")

    return {
        # ★ MVT 가 봇의 현상금을 **읽는가**. 이것이 재기동을 건너는 유일한 통로다.
        #   State.bounty() 의 호출자가 0이면 봇이 계산한 현상금은 아무도 안 읽는 숫자다.
        # ★ 이름이 아니라 **판독 자체**를 본다. bountyOf() 라는 이름만 남기고 속을 0 으로 비워도
        #   눈이 ✅ 를 찍으면, 그 눈은 없느니만 못하다 (실제로 처음 만들었을 때 그렇게 찍었다).
        "MVT가 장부의 현상금을 읽는다": (
            bool(re.search(r"(WorldBridge\.state\(\)|state)\.bounty\(", mvt_all)),
            "WorldBridge.State.bounty() 호출자 0 — 봇이 방을 붙여도 마크는 모른다"),

        # ★ 정본 단일성 — MVT 가 제 수배·favor·지역 장부를 다시 파지 않는가 (거울의 재발 방지)
        "MVT에 사(私)장부가 없다": (
            not re.search(r"private final Map<UUID, Integer> bounty", incidents)
            and not re.search(r"private final Map<UUID, Map<String, Integer>> favor", incidents)
            and not re.search(r"private final Map<String, Integer> region", incidents),
            "Incidents 가 bounty/favor/region 을 **필드로** 다시 들었다 — 재기동하면 증발하는 두 번째 세계"),

        # ★ 무명은 게시판에 오르지 않는다 (populace.yml quest_source: false)
        "무명이 게시판에 안 오른다": (
            "rules.npcByKey(npcKey) == null" in injections,
            "Injections.deathQuests 가 무명 키를 거르지 않는다 — '장례: 무명:guja 의 상(喪)'"),

        # ★ 지역 자연 회복이 배선됐는가 (region_state.yml recovery — 10일마다 50을 향해)
        "지역이 회복한다": (
            "regions.recover(" in bot_all and "recoveryDeltas(" in domain_all,
            "GameListener → RegionService.recover → RegionStateEngine.recoveryDeltas 사슬이 끊겼다"
            " — 한 번 내려간 치안은 영원히 내려가 있다"),
    }


# ─── 검산 ─────────────────────────────────────────────────────────────────────


def backup(db: Path) -> Path:
    stamp = time.strftime("%Y%m%d-%H%M%S")
    dest = db.with_suffix(db.suffix + f".bak-audit-{stamp}")
    src = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    dst = sqlite3.connect(dest)
    with dst:
        src.backup(dst)   # WAL 미체크포인트 내용까지 (봇이 켜져 있어도 안전하다)
    src.close()
    dst.close()
    return dest


def q(conn, sql, *args):
    return conn.execute(sql, args).fetchall()


def one(conn, sql, *args):
    row = conn.execute(sql, args).fetchone()
    return row[0] if row else 0


def section(title):
    print(f"\n{'═' * 78}\n{title}\n{'═' * 78}")


def result_code(verdicts):
    return 1 if any(v.startswith(NO) for v in verdicts) else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("db", nargs="?", default=str(DEFAULT_DB))
    ap.add_argument("--no-backup", action="store_true", help="백업 생략 (읽기 전용 확인용)")
    args = ap.parse_args()
    db = Path(args.db)
    if not db.exists():
        sys.exit(f"DB 가 없다: {db}")

    bridge_cfg, blood_cfg = load_registry()
    if not args.no_backup:
        print(f"백업: {backup(db).name}  (규약 — DB 를 만지기 전에 뜬다)")

    conn = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    conn.row_factory = sqlite3.Row
    today = int(one(conn, "SELECT value FROM world_meta WHERE key='현재일'") or 1)
    verdicts = []

    # ═══ ① 배선 — 발신 → 수신 → 세계 상태 ═══
    section("① 배선 — 사건은 어디까지 흘렀는가 (MVT 발신 → 봇 수신 → 세계 변화)")
    events = bridge_cfg.get("events", {})
    emitters, callers = mvt_emitters(events.keys())
    handlers = bot_handlers(events.keys())
    inbox = dict(q(conn, "SELECT kind, COUNT(*) FROM bridge_inbox GROUP BY kind"))
    # 세계가 실제로 바뀐 흔적 (kind → 그 사건이 남기는 events.type)
    world_marks = {"npc_death": ("사망", "소문"), "bandit_slain": ("토벌",), "beast_slain": ("사냥",),
                   "bandit_camp_cleared": ("도적_부분_소탕",),
                   "bandit_boss_succeeded": ("도적_두목_승계",),
                   "qi_manifested": ("격_목격",), "sparring": ("비무",), "link_confirm": ("접합",)}
    print(f"{'이벤트':<14} {'등록':<5} {'MVT 발신':<22} {'봇 수신':<8} {'수신 건수':<9} 세계 변화")
    for kind in events:
        registered = OK
        emit = OK if emitters.get(kind) else NO
        who = ", ".join(callers.get(kind, [])) or "—"
        # 발신 손잡이는 있으나 부르는 자가 없으면 그 사건은 마크에서 절대 일어나지 않는다
        emit_label = f"{emit} {who[:18]}"
        recv = OK if handlers.get(kind) else NO
        count = inbox.get(kind, 0)
        marks = sum(one(conn, "SELECT COUNT(*) FROM events WHERE type=?", t)
                    for t in world_marks.get(kind, ()))
        change = f"{OK} {marks}건" if marks else f"{WARN} 0건"
        print(f"{kind:<14} {registered:<5} {emit_label:<22} {recv:<8} {count:<9} {change}")
        if emit == NO or recv == NO:
            verdicts.append(f"{NO} {kind} — 다리가 끊겼다 (발신 {emit} · 수신 {recv})")
        elif not callers.get(kind):
            verdicts.append(f"{WARN} {kind} — 발신 손잡이는 있으나 **부르는 자가 없다** "
                            f"(마크에서 이 사건은 일어나지 않는다)")

    atomic, atomic_why = bridge_delivery_atomic()
    print(f"\n전달 원자성: {OK if atomic else NO} inbox · 세계 변경 · JSONL 커서")
    verdicts.append(f"{OK} 전달 원자성 — 실패한 사건은 같은 줄에서 재시도된다" if atomic
                    else f"{NO} 전달 원자성 — {atomic_why}")

    # ═══ ② 신원 — 주체 없는 사건의 비율 ═══
    section("② 신원 — 세계가 못 읽는 사건은 얼마나 되는가 (★ 이 작업의 본체)")
    links = q(conn, "SELECT mc_uuid, mc_name, character_id FROM mvt_link")
    linked = [r for r in links if r["character_id"] is not None]
    print(f"등록된 몸 {len(links)}개 · 접합된 몸 {len(linked)}개")
    for r in links:
        who = one(conn, "SELECT name FROM characters WHERE id=?", r["character_id"]) \
            if r["character_id"] else None
        print(f"  {r['mc_name']:<16} → {who if who else '(이름 없음 — 세계는 그를 모른다)'}")

    # 마크發 사건 중 주체(character)가 붙은 것의 비율
    mvt_events = q(conn, "SELECT type, actor_type, actor_id FROM events "
                         "WHERE json_extract(data_json, '$.출처') = 'mvt'")
    total = len(mvt_events)
    named = sum(1 for e in mvt_events if e["actor_type"] == "character")
    ratio = 100.0 * named / total if total else 0.0
    anon = total - named
    print(f"\n마크發 사건 {total}건 중 주체(캐릭터)가 붙은 것: {named}건 ({ratio:.1f}%)")
    print(f"주체 없는 사건: {anon}건 — **세계가 못 읽는 사건** (소문에 이름이 없고 세력이 안 본다)")
    if total == 0:
        verdicts.append(f"{WARN} 마크發 사건이 아직 없다 (다리를 아직 안 건넜다)")
    elif anon == 0:
        verdicts.append(f"{OK} 신원 — 마크發 사건 전부에 주체가 붙었다")
    else:
        verdicts.append(f"{WARN} 신원 — 주체 없는 마크發 사건 {anon}건 ({100 - ratio:.1f}%) "
                        f"— 접합 전의 일이거나 몹·미상의 소행")

    # 소문의 주체
    rumors = q(conn, "SELECT content_json FROM rumors")
    with_subject = sum(1 for r in rumors
                       if '"주체_id"' in r["content_json"])
    print(f"소문 {len(rumors)}행 중 주체_id 가 박힌 것: {with_subject}행 "
          f"({100.0 * with_subject / len(rumors) if rumors else 0:.1f}%) "
          f"— 세력이 주목할 수 있는 소문")

    # 접합의 청 (007) — 코드는 죽었다. 이제 장부에 남는 것은 **청과 그 답**이다
    has_req = one(conn, "SELECT COUNT(*) FROM sqlite_master "
                        "WHERE type='table' AND name='mvt_link_request'")
    if has_req:
        reqs = q(conn, "SELECT state, COUNT(*) c FROM mvt_link_request GROUP BY state")
        if reqs:
            print("접합 청: " + " · ".join(f"{r['state']} {r['c']}건" for r in reqs)
                  + "   (수락 = 그 몸이 게임 안에서 눌렀다 — 그것만이 잇는다)")
    codes = q(conn, "SELECT state, COUNT(*) c FROM mvt_link_code GROUP BY state")
    if codes:
        print("☠ 옛 접합 코드 (폐기된 방식): "
              + " · ".join(f"{r['state']} {r['c']}건" for r in codes))

    # ═══ ③ 혈채 — 두 원장의 정합 ═══
    section("③ 혈채 — 감쇠하지 않는 유일한 값 (그리고 0이어야 하는 것들)")
    engine = blood_cfg.get("engine", {})
    ledgers = q(conn, "SELECT * FROM blood_debt ORDER BY hidden DESC")
    if not ledgers:
        print("혈채 원장이 비어 있다 — 아직 아무도 무고한 자를 죽이지 않았다")
    for r in ledgers:
        every = blood_cfg["ledgers"]["현혈채"]["decay"]["every_days"]
        ticks = max(0, (today - r["known_day"]) // every)
        floor = min(r["public_count"] * 2, int(r["known_raw"]))
        known = max(0, max(floor, int(r["known_raw"]) - ticks))
        rung = "없음"
        for step in blood_cfg.get("ladder", []):
            if known >= step["min"]:
                rung = step["name"]
        print(f"  {r['subject']:<16} 암혈채 {r['hidden']:>5.1f} (감쇠 없음) · "
              f"현혈채 {known:>2} [{rung}] · 공개 {r['public_count']}건 · 살인 {r['kills']}건"
              + (f" · 노출하한 {r['exposure_floor']}" if r["exposure_floor"] else ""))

    # 암혈채는 감쇠하지 않는가 — 원장의 hidden 은 오직 더해지기만 한다 (events 로 검산)
    charges = q(conn, "SELECT actor_id, data_json FROM events WHERE type='혈채'")
    by_actor = {}
    zero_violations = []
    for c in charges:
        d = json.loads(c["data_json"])
        by_actor.setdefault(c["actor_id"], []).append(d)
        if d.get("분류") in ("무장_상대", "관인", "비무_사고", "없음") and d.get("암혈채", 0) > 0:
            # 0이어야 할 분류에 값이 붙었는가 (이 이벤트 자체가 남으면 안 된다)
            zero_violations.append(d)
    consistent = True
    for actor, rows in by_actor.items():
        row = next((r for r in ledgers if r["character_id"] and str(r["character_id"]) == actor), None)
        if not row:
            continue
        # 마지막 이벤트의 암혈채 = 원장의 암혈채 (더해지기만 했는가)
        if abs(rows[-1].get("암혈채", 0) - row["hidden"]) > 0.001:
            consistent = False
            print(f"  {NO} {actor} — 원장 {row['hidden']} ≠ 마지막 적립 {rows[-1].get('암혈채')}")
    verdicts.append(f"{OK} 암혈채 — 감쇠 없음 (원장 = 적립 합계)" if consistent
                    else f"{NO} 암혈채 — 원장과 적립 합계가 어긋난다")
    verdicts.append(f"{OK} 무장 상대·관인·비무 — 혈채 0 (빚이 아니다)" if not zero_violations
                    else f"{NO} 0이어야 할 분류에 혈채가 붙었다: {zero_violations}")

    # 다리의 zero_kinds 가 정말 혈채를 안 만들었는가
    zero_kinds = engine.get("classification", {}).get("zero_kinds", [])
    bad = 0
    for kind in zero_kinds:
        bad += one(conn, "SELECT COUNT(*) FROM events WHERE type='혈채' "
                         "AND json_extract(data_json,'$.출처')='mvt' AND target_id IN "
                         "(SELECT target_id FROM events WHERE type IN ('토벌','사냥','비무'))")
    print(f"\n토벌·사냥·비무發 혈채: {bad}건 "
          + (f"{OK} (0 — 서로 죽일 각오로 만난 자에게는 빚이 없다)" if bad == 0 else NO))

    # 사다리의 발화 흔적
    for t, label in (("수배", "관 — 수배·현상금"), ("혈채_세력", "정파 — 등을 돌린다"),
                     ("혈채_명분", "무림 — 명분 발화"), ("혈채_법명분", "관 — 법명분 개방"),
                     ("마공_목격", "★ 마공 운기 목격 (은밀 봉쇄)")):
        n = one(conn, "SELECT COUNT(*) FROM events WHERE type=?", t)
        print(f"  {label:<28} {n}건 " + (OK if n else "—"))

    # ═══ ④ 되먹임 — 마크로 내려가는 것 ═══
    section("④ 되먹임 — 봇의 세계가 마크로 내려가는가 (그리고 혈채는 새지 않는가)")
    snap = BRIDGE_DIR / "world_state.json"
    if not snap.exists():
        print(f"{WARN} 스냅숏이 없다 ({snap}) — 봇이 아직 발행하지 않았다")
        verdicts.append(f"{WARN} 되먹임 — 스냅숏 미발행 (봇이 꺼져 있었나)")
    else:
        state = json.loads(snap.read_text(encoding="utf-8"))
        keys = ("world_day", "rumor_tags", "populace_reactions", "region", "wanted", "favor",
                "links", "bounty")
        missing = [k for k in keys if k not in state]
        print(f"스냅숏 {snap} — 세계일 {state.get('world_day')} · "
              f"소문태그 {len(state.get('rumor_tags', []))} · 반응 {state.get('populace_reactions')}")
        print(f"  신원 {len(state.get('links', {}))}명 · 수배 {len(state.get('wanted', {}))}명 "
              f"· 현상금 {len(state.get('bounty', {}))}명")
        leaked = any(k in json.dumps(state, ensure_ascii=False)
                     for k in ("blood_debt", "hidden", "혈채", "암혈채", "현혈채"))
        verdicts.append(f"{OK} 되먹임 — 스냅숏 정상 (키 {len(keys) - len(missing)}/{len(keys)})"
                        if not missing else f"{NO} 되먹임 — 스냅숏에 빠진 키: {missing}")
        verdicts.append(f"{OK} 혈채 비노출 — 수치는 마크로 내려가지 않는다 (내려가는 것은 반응뿐)"
                        if not leaked else f"{NO} ★ 혈채 수치가 스냅숏으로 새어 나갔다")

    # ═══ ⑤ 사슬 — "죽였다 · 재기동했다 · 아직도 나를 쫓는가" ═══
    section("⑤ 사슬 — 살인이 세계에 남긴 자국 (★ 재기동을 건너는가)")

    # ── 배선 (DB 가 비어 있어도 잰다 — 사람이 오기 전에 세계가 준비돼 있는가) ──
    print("배선 — 코드가 사슬을 잇고 있는가")
    for label, (ok, why) in code_links().items():
        print(f"  {OK if ok else NO} {label}" + ("" if ok else f"\n      ↳ {why}"))
        if not ok:
            verdicts.append(f"{NO} 사슬 — {label}: {why}")

    # 봇이 무명층의 새 kind 를 받는가 (world_bridge.yml 에 등록돼 있는데 처리기가 없으면 조용히 소실된다)
    for kind in ("surrender", "populace_quest"):
        if kind not in events:
            continue
        if handlers.get(kind):
            print(f"  {OK} 봇이 {kind} 를 처리한다")
        else:
            print(f"  {NO} 봇에 {kind} 처리기가 없다"
                  f"\n      ↳ 등록됐는데 case 가 없다 — bridge_inbox 에 못만 박히고 세계는 안 바뀐다")
            verdicts.append(f"{NO} 사슬 — 봇이 {kind} 를 받지 못한다 (Bridge.apply 에 case 없음)")

    # ── 장부 (실제로 누가 죽었고 그 값이 어디까지 갔는가) ──
    populace_deaths = q(conn, "SELECT target_id, data_json FROM events WHERE type='사망' "
                              "AND json_extract(data_json,'$.출처')='mvt'")
    nameless = [r for r in populace_deaths if str(r["target_id"]).startswith(("무명:", "region:"))]
    print(f"\n장부 — 마크發 사망 {len(populace_deaths)}건 (그중 무명 {len(nameless)}건)")

    # 무명이 게시판에 오를 수 있는 상태로 DB 에 남아 있는가 (등록부에 없는 키 = 기계어 제목의 씨앗)
    dead = q(conn, "SELECT id, state_json FROM npcs WHERE status='사망'") \
        if one(conn, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='npcs'") else []
    junk = [r["id"] for r in dead if str(r["id"]).startswith(("무명:", "region:"))]
    if junk:
        print(f"  {WARN} 사망 등록부에 무명 키 {len(junk)}개: {', '.join(junk[:4])}"
              f"\n      ↳ 코드가 거르므로 게시판엔 안 뜬다 (DB 정리는 불필요 — 그들도 죽은 사람이다)")

    # 혈채 → 수배 → 스냅숏 (사슬의 심장)
    chain_ok, chain_bad = [], []
    for r in ledgers:
        every = blood_cfg["ledgers"]["현혈채"]["decay"]["every_days"]
        ticks = max(0, (today - r["known_day"]) // every)
        floor = min(r["public_count"] * 2, int(r["known_raw"]))
        known = max(0, max(floor, int(r["known_raw"]) - ticks))
        bounty_min = engine.get("bounty_min", 3)
        posted = one(conn, "SELECT COUNT(*) FROM events WHERE type='수배' AND actor_id=?",
                     str(r["character_id"])) > 0
        # 스냅숏에 실렸는가 — ★ 재기동을 건너는 유일한 칸
        in_snapshot = False
        snap_file = BRIDGE_DIR / "world_state.json"
        if snap_file.exists() and r["character_id"]:
            snap = json.loads(snap_file.read_text(encoding="utf-8"))
            links_ = snap.get("links", {})
            body = next((b for b, v in links_.items()
                         if v.get("character_id") == r["character_id"]), None)
            in_snapshot = bool(body and snap.get("bounty", {}).get(body, 0) > 0)
        who = r["subject"]
        if known < bounty_min:
            print(f"  · {who:<18} 현혈채 {known} < 문턱 {bounty_min} — 아직 관이 한 사람을 보지 않는다")
            continue
        line = f"  · {who:<18} 현혈채 {known} ≥ {bounty_min} → 수배 {OK if posted else NO}" \
               f" → 스냅숏 {OK if in_snapshot else NO}"
        print(line)
        (chain_ok if posted and in_snapshot else chain_bad).append(who)
        if posted and not in_snapshot:
            print(f"      ↳ {NO} ★ **장부는 그를 쫓는데 마크는 모른다** — 접합이 없거나(links) "
                  f"스냅숏이 낡았다. 재기동하면 세계가 그를 잊는다")

    if not ledgers:
        print("  아직 아무도 무고한 자를 죽이지 않았다 — 사슬은 배선만 잰다 (위)")
        verdicts.append(f"{WARN} 사슬 — 피가 없어 장부를 못 쟀다 (배선은 위에서 쟀다)")
    elif chain_bad:
        verdicts.append(f"{NO} 사슬 — 수배가 마크에 닿지 않는 자 {len(chain_bad)}명: "
                        f"{', '.join(chain_bad)}")
    elif chain_ok:
        verdicts.append(f"{OK} 사슬 — 살인 → 혈채 → 수배 → 스냅숏 ({len(chain_ok)}명) "
                        f"— **재기동해도 세계는 그를 기억한다**")

    # ═══ 총평 ═══
    section("총평")
    for v in verdicts:
        print(" " + v)
    bad_count = sum(1 for v in verdicts if v.startswith(NO))
    warn_count = sum(1 for v in verdicts if v.startswith(WARN))
    print()
    if bad_count:
        print(f"{NO} 끊긴 데가 {bad_count}군데 있다. 다리는 놓였으나 세계는 아직 반쪽이다.")
    elif warn_count:
        print(f"{OK} 배선은 이어졌다. 다만 {warn_count}군데는 아직 아무도 건너지 않았다 "
              f"(사람이 오면 흐른다).")
    else:
        print(f"{OK} 몸에서 벌어진 일이 장부에 닿고, 장부의 세계가 몸으로 돌아온다. 세계는 하나다.")
    conn.close()
    return result_code(verdicts)


if __name__ == "__main__":
    sys.exit(main())
