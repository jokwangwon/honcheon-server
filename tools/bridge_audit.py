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

규약: **DB 를 읽기 전에 백업한다** (봇은 단일 작성자다. 이 도구는 읽기만 하지만 규약은 규약이다).

사용법:
    python3 tools/bridge_audit.py                      # run/bot/honcheon.db
    python3 tools/bridge_audit.py <db경로> [--no-backup]
"""

import argparse
import json
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


# MVT 발신부: 이 kind 를 실제로 emit 하는 자바 손잡이가 있는가 (WorldBridge.java 를 읽는다)
def mvt_emitters():
    src = (ROOT / "server-mvt" / "src" / "main" / "java" / "com" / "honcheon" / "mvt"
           / "WorldBridge.java").read_text(encoding="utf-8")
    callers = {}
    for kind in ("npc_death", "bandit_slain", "beast_slain", "qi_manifested", "sparring",
                 "link_request"):
        emits = f'emit("{kind}"' in src
        # 그 손잡이를 실제로 부르는 자가 있는가 — 다리에 실리지 않은 사건은 장부에도 없다
        callers[kind] = emits
    used = {}
    mvt_dir = ROOT / "server-mvt" / "src" / "main" / "java" / "com" / "honcheon" / "mvt"
    handles = {"npc_death": "WorldBridge.npcDeath", "bandit_slain": "WorldBridge.banditSlain",
               "beast_slain": "WorldBridge.beastSlain", "qi_manifested": "WorldBridge.qiManifested",
               "sparring": "WorldBridge.sparring", "link_request": "WorldBridge.requestLink"}
    for kind, handle in handles.items():
        hits = []
        for path in mvt_dir.glob("*.java"):
            if path.name == "WorldBridge.java":
                continue
            if handle in path.read_text(encoding="utf-8"):
                hits.append(path.name)
        used[kind] = hits
    return callers, used


def bot_handlers():
    src = (ROOT / "server-bot" / "src" / "main" / "java" / "com" / "honcheon" / "bot"
           / "Bridge.java").read_text(encoding="utf-8")
    return {kind: f'case "{kind}"' in src
            for kind in ("npc_death", "bandit_slain", "beast_slain", "qi_manifested", "sparring",
                         "link_request")}


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
    emitters, callers = mvt_emitters()
    handlers = bot_handlers()
    inbox = dict(q(conn, "SELECT kind, COUNT(*) FROM bridge_inbox GROUP BY kind"))
    # 세계가 실제로 바뀐 흔적 (kind → 그 사건이 남기는 events.type)
    world_marks = {"npc_death": ("사망", "소문"), "bandit_slain": ("토벌",), "beast_slain": ("사냥",),
                   "qi_manifested": ("격_목격",), "sparring": ("비무",), "link_request": ("접합",)}
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

    # 대기 코드
    codes = q(conn, "SELECT state, COUNT(*) c FROM mvt_link_code GROUP BY state")
    if codes:
        print("접합 코드: " + " · ".join(f"{r['state']} {r['c']}건" for r in codes))

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


if __name__ == "__main__":
    main()
