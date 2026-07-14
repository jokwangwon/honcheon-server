#!/usr/bin/env python3
"""접합 검산(檢算) — **닉네임만으로는 절대 안 이어지는가.**

이 눈이 지키는 문장은 하나다:

    ★ 몸과 이름을 잇는 손은 **그 몸의 게임 화면에서 누른 [잇는다]** 하나뿐이다.

디스코드에서 닉네임을 대는 것은 **청(請)** 일 뿐이다 — 그것은 아무것도 열지 않는다.
닉네임은 공개 정보이고, 남의 닉을 대는 것은 막을 수도 없고 막을 필요도 없다.
막아야 하는 것은 **닉네임이 곧 열쇠가 되는 것**이다. 그 문이 열리는 길은 셋뿐이다:

  ① 디스코드 쪽에 **잇는 손**이 생긴다      (askLink 가 곧장 linkMvt/completeLink 를 부른다)
  ② 게임 안 수락이 **몸을 대조하지 않는다**  (남의 토큰으로 남의 몸을 이을 수 있다)
  ③ 봇이 다리를 **그냥 믿는다**              (jsonl 은 파일이다 — 한 줄 끼워 넣으면 끝난다)

셋 다 **소스에서** 잡는다 (DB 가 비어 있어도 잰다 — 사람이 오기 전에 세계가 준비돼 있어야 하므로).
그리고 자물쇠 다섯 (TTL · 1회성 · 1:1 · 연타 · 감사)이 아직 서 있는지 함께 본다.

★ 이 눈 자체를 시험하는 눈: tools/link_audit_selftest.py (일부러 어겨서, 잡는지 본다)

사용법:
    python3 tools/link_audit.py                     # 소스 + run/bot/honcheon.db
    python3 tools/link_audit.py --no-db             # 소스만 (DB 없이)
종료 코드: 자물쇠가 하나라도 부러졌으면 1.
"""

import argparse
import re
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MVT = ROOT / "server-mvt/src/main/java/com/honcheon/mvt"
BOT = ROOT / "server-bot/src/main/java/com/honcheon/bot"
DEFAULT_DB = ROOT / "run" / "bot" / "honcheon.db"

OK, NO, WARN = "✅", "❌", "⚠️"


def src(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else ""


def strip_comments(text: str) -> str:
    """주석은 코드가 아니다 — **주석에 적힌 약속은 자물쇠가 아니다.**

    이 눈이 처음 스스로 속은 자리가 여기다: 주석에 `db.linkMvt(...)` 라고 적혀 있으면
    '잇는 손이 있다'고 읽었다. 그래서 코드만 남기고 본다.
    """
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def method_body(text: str, name: str) -> str:
    """메서드 하나의 몸통 — 이름으로 찾아 중괄호를 세어 닫는다 (없으면 빈 문자열)."""
    m = re.search(r"\b(?:void|boolean|String|Decision|Component)\s+" + re.escape(name) + r"\s*\(",
                  text)
    if not m:
        return ""
    i = text.find("{", m.end())
    if i < 0:
        return ""
    depth, j = 0, i
    while j < len(text):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[i:j + 1]
        j += 1
    return text[i:]


def load_cfg():
    try:
        import yaml
    except ImportError:
        sys.exit("pyyaml 이 필요하다: pip install pyyaml")
    return yaml.safe_load((ROOT / "config" / "world_bridge.yml").read_text(encoding="utf-8"))


def checks():
    """(구획, 이름, 통과?, 어긋나면 무슨 뜻인가)"""
    cfg = load_cfg()
    identity = cfg.get("identity", {})
    events = cfg.get("events", {})
    transport = cfg.get("transport", {})

    game = strip_comments(src(BOT / "GameListener.java"))
    bridge = strip_comments(src(BOT / "Bridge.java"))
    db = strip_comments(src(BOT / "Db.java"))
    bot_cmd = strip_comments(src(BOT / "HoncheonBot.java"))
    wb = strip_comments(src(MVT / "WorldBridge.java"))
    gate = strip_comments(src(MVT / "LinkGate.java"))
    mvt_cmd = strip_comments(src(MVT / "MvtCommand.java"))

    ask = method_body(game, "askLink")
    complete = method_body(game, "completeLink")
    decide = method_body(wb, "linkDecision")
    confirm = method_body(bridge, "linkConfirm")
    burn = method_body(db, "burnLinkRequest")
    mvt_link = method_body(mvt_cmd, "link")

    out = []

    # ═══ ① 옛 코드 길은 죽었는가 (사용자가 폐기를 골랐다) ═══
    # ★ 이 눈이 처음 스스로 거짓말한 자리 — `link_requests`(새 청 파일)가 `link_request`(죽은 kind)를
    #   **부분 문자열로 품는다.** 그래서 멀쩡한 코드에 ❌ 를 찍었다. 죽은 것을 **정확히 이름으로** 부른다:
    #   발급 메서드(requestLink)와 죽은 kind 의 발신(emit("link_request", …)) 둘 다.
    out.append(("① 코드 폐기", "MVT 가 코드를 내지 않는다",
                not re.search(r"\brequestLink\s*\(", wb)
                and not re.search(r'emit\(\s*"link_request"', wb),
                "WorldBridge 에 코드 발급이 살아 있다 — 사용자가 폐기한 길이다"))
    out.append(("① 코드 폐기", "[코드 복사] 가 없다",
                "copyToClipboard" not in gate,
                "LinkGate 가 아직 클립보드에 코드를 담는다"))
    out.append(("① 코드 폐기", "봇에 코드 확정 손이 없다",
                "linkWithCode" not in game and "pendLinkCode" not in db
                and "burnLinkCode" not in db,
                "봇에 코드로 잇는 손이 남아 있다 — 두 번째 문이다"))
    out.append(("① 코드 폐기", "슬래시 옵션이 닉네임이다",
                '"닉네임"' in bot_cmd and '"코드"' not in bot_cmd,
                "/혼천 접속 이 아직 코드를 받는다"))
    out.append(("① 코드 폐기", "등록부가 새 방향이다",
                identity.get("direction") == "discord_asks_body_accepts"
                and "link_request" not in events and "link_confirm" in events,
                "world_bridge.yml 이 아직 코드 방향(mvt_issues_discord_confirms)이다"))

    # ═══ ★★ ② 닉네임만으로는 안 이어진다 — 이 눈의 심장 ═══
    #
    # 몸↔캐릭터를 잇는 유일한 손은 db.linkMvt(…, characterId) 다 (3번째 인자가 null 이 아닌 것).
    # 그 손이 **디스코드에서 곧장 닿을 수 있으면** 닉네임이 곧 열쇠가 된다.
    #
    # ★ **봇의 모든 파일**을 본다 (GameListener·Bridge 만이 아니라). 오늘 Reset.java 가 옆에서 생겼듯,
    #   내일 누가 새 파일에 잇는 손을 파 놓아도 이 눈은 그것을 본다. 자물쇠는 파일이 아니라 **행위**에 건다.
    binding = []          # 캐릭터를 몸에 붙이는 호출 (3번째 인자가 null 이 아니다)
    for path in sorted(BOT.glob("*.java")):
        if path.name == "Db.java":
            continue      # 장부 자신 (여기가 그 손의 구현이다 — 부르는 자를 본다)
        body_src = strip_comments(src(path))
        for call in re.findall(r"db\.linkMvt\(([^;]*?)\)\s*;", body_src):
            if not call.rstrip().endswith("null"):
                binding.append((path.name, call.strip()))
    out.append(("② 수락 없이는 못 잇는다", "★ 봇 전체에서 잇는 손은 completeLink 하나뿐",
                len(binding) == 1 and binding[0][0] == "GameListener.java"
                and re.search(r"db\.linkMvt\([^;]*?\)\s*;", complete) is not None,
                f"몸을 잇는 손이 completeLink 밖에 있다 — **수락 없이 이어지는 문**이다: {binding}"))

    # 청을 앉히는 손(askLink)은 절대 잇지 않는다
    out.append(("② 수락 없이는 못 잇는다", "★ askLink 는 아무것도 잇지 않는다",
                bool(ask) and "linkMvt" not in ask and "completeLink" not in ask
                and "mergeBloodDebt" not in ask,
                "디스코드의 닉네임 입력이 **곧장 잇는다** — 닉네임이 열쇠가 됐다 (도용 가능)"))

    # completeLink 를 부르는 자는 다리(=게임 안 수락)뿐이다
    callers = [p.name for p in BOT.glob("*.java")
               if "completeLink(" in strip_comments(src(p)) and p.name != "GameListener.java"]
    out.append(("② 수락 없이는 못 잇는다", "★ completeLink 를 부르는 자는 다리뿐",
                callers == ["Bridge.java"],
                f"게임 안 수락(Bridge) 말고 다른 자가 접합을 부른다: {callers}"))

    # ═══ ③ 몸을 대조하는가 — 남의 청은 못 받는다 (두 겹) ═══
    #
    # ★ 이 눈이 두 번째로 스스로 거짓말한 자리. 처음엔 "메서드 안에 req.mcUuid().equals(uuid) 가
    #   **있기만 하면** ✅" 이었다. 그런데 그 문자열은 **취소 갈래에도** 있다 (초기화가 제 몸을 고를 때).
    #   그래서 진짜 문지기를 뜯어내도 눈은 웃고 있었다 — selftest 가 그것을 잡았다.
    #   이제 **부정형 문지기**(다르면 즉시 물러난다)를 찾는다. 비교가 있는 것과 막는 것은 다른 일이다.
    out.append(("③ 몸 대조", "★ 마크: 청을 받은 그 몸만 수락한다",
                bool(decide)
                and re.search(r"if\s*\(\s*!\s*req\.body\(\)\.equals\(player\)\s*\)", decide) is not None
                and "NOT_YOURS" in decide,
                "WorldBridge.linkDecision 이 몸을 대조하지 않는다 — **남의 토큰으로 남의 몸을 잇는다**"))
    out.append(("③ 몸 대조", "★ 봇: 다리를 믿지 않고 다시 대조한다",
                bool(confirm)
                and re.search(r"if\s*\(\s*!\s*req\.mcUuid\(\)\.equals\(uuid\)\s*\)\s*\{[^}]*return;",
                              confirm, re.S) is not None,
                "Bridge.linkConfirm 이 몸을 안 본다 — jsonl 한 줄이면 남의 캐릭터를 가져간다"))

    # ═══ ④ TTL — 청은 오래 살지 않는다 ═══
    ttl = identity.get("ttl_seconds", 0)
    out.append(("④ TTL", f"청의 수명이 짧다 ({ttl}초)",
                isinstance(ttl, int) and 0 < ttl <= 300,
                "청이 너무 오래 산다 — 지금 화면 앞의 사람에게 묻는 것이다 (≤300초)"))
    out.append(("④ TTL", "봇이 만료를 판정한다",
                "expired(" in confirm and "livingLinkRequests" in db and "'만료'" in db,
                "만료 검사가 없다 — 어제의 청으로 오늘의 몸을 잇는다"))

    # ═══ ⑤ 1회성 — 한 번 답하면 죽는다 ═══
    out.append(("⑤ 1회성", "★ 수락은 한 번만 (WHERE state='대기')",
                bool(burn) and "state = '대기'" in burn and "executeUpdate() > 0" in burn,
                "burnLinkRequest 가 이미 답한 청을 또 태운다 — 재생·연타로 두 번 이어진다"))
    out.append(("⑤ 1회성", "수락이 태우기를 통과해야 잇는다",
                re.search(r"if\s*\(!db\.burnLinkRequest\([^)]*\)\)\s*\{", confirm) is not None,
                "Bridge 가 태우기 실패(=이미 답함)를 무시하고 잇는다"))

    # ═══ ⑥ 1:1 — 몸 하나 = 캐릭터 하나 (청할 때 **그리고** 수락할 때) ═══
    out.append(("⑥ 1:1", "청할 때 본다",
                "mcOfCharacter" in ask and "rawCharacterOfMc" in ask,
                "askLink 가 이미 이어진 몸/이름을 안 본다"))
    out.append(("⑥ 1:1", "★ 수락할 때 **다시** 본다 (2분 사이에 세상이 바뀐다)",
                "mcOfCharacter" in complete and "rawCharacterOfMc" in complete,
                "completeLink 가 재검사를 안 한다 — 청이 뜬 사이 남이 그 몸을 이었을 수 있다"))

    # ═══ ⑦ 연타 — 남의 화면을 물음으로 덮지 못한다 ═══
    out.append(("⑦ 연타", "쿨다운이 있다",
                "linkCooldown" in ask and "linkCooldownSeconds" in ask
                and "lastLinkRequestTo" in ask,
                "청을 도배할 수 있다 — 남의 화면이 물음으로 덮인다"))
    cooldown = identity.get("cooldown_seconds", 0)
    out.append(("⑦ 연타", f"쿨다운이 등록부에 있다 ({cooldown}초)",
                isinstance(cooldown, int) and cooldown > 0,
                "identity.cooldown_seconds 가 0 이다 — 쿨다운이 없다"))

    # ═══ ⑧ 감사 — 누가 누구를 언제 이었는가 ═══
    out.append(("⑧ 감사", "접합이 장부에 남는다",
                'logEvent("접합"' in complete,
                "접합이 events 에 안 남는다 — 나중에 아무도 못 캔다"))
    out.append(("⑧ 감사", "청도 장부에 남는다 (도용 시도의 자국)",
                'logEvent("접합_청"' in ask,
                "청이 안 남는다 — 누가 남의 닉을 댔는지 알 수 없다"))
    out.append(("⑧ 감사", "남의 청을 가로챈 시도가 남는다",
                'logEvent("접합_거부"' in confirm,
                "Bridge 가 몸이 다른 답을 조용히 버린다 — 공격의 자국이 안 남는다"))

    # ═══ ⑨ 명부 비노출 — 누가 접속했는지 캐는 도구가 되면 안 된다 ═══
    out.append(("⑨ 명부", "★ 없는 이름과 오프라인이 같은 말이다",
                identity.get("reveal_roster") is False
                and "강호에 없다" in ask,
                "오프라인·없는 이름을 갈라 말한다 — 이 문이 이름 캐기 도구가 된다"))
    out.append(("⑨ 명부", "봇은 명부를 통째로 안 쓴다 (있다/없다만)",
                "roster" not in game,
                "GameListener 가 명부를 직접 만진다 — 목록이 새어 나갈 자리다"))

    # ═══ ⑩ 초기화 — 발판을 다시 밟으면 낡은 청이 죽는다 (사용자 지시) ═══
    out.append(("⑩ 초기화", "★ /혼천 접속 이 낡은 청을 죽인다",
                "linkReset" in mvt_link,
                "다시 불러도 낡은 청이 살아 있다 — 죽은 줄 알았던 창을 나중에 수락하게 된다"))
    out.append(("⑩ 초기화", "봇이 취소를 받는다",
                '"취소"' in confirm and "폐기" in confirm,
                "Bridge 가 취소를 모른다 — 마크만 지우고 장부엔 청이 남는다 (두 정본)"))
    out.append(("⑩ 초기화", "한 몸에 살아 있는 청은 하나 (등록부·장부)",
                identity.get("one_pending_per_body") is True
                and "mc_uuid = ? OR character_id = ?" in db,
                "pendLinkRequest 가 옛 청을 안 죽인다 — 두 청이 경쟁한다"))
    out.append(("⑩ 초기화", "발판은 명령을 대신 칠 뿐 (제 로직이 없다)",
                "performCommand" in strip_comments(src(MVT / "Antechamber.java")),
                "발판이 제 손으로 접합을 만진다 — 손과 발판이 어긋날 자리다"))

    # ═══ ⑪ 다리의 두 파일이 등록돼 있는가 ═══
    out.append(("⑪ 배선", "청·명부 파일이 등록부에 있다",
                "link_requests" in transport and "roster" in transport,
                "transport 에 link_requests/roster 가 없다 — 코드가 파일 이름을 지어내고 있다"))
    out.append(("⑪ 배선", "MVT 가 명부를 찍고 청을 읽는다",
                "writeRoster" in wb and "readLinkRequests" in wb,
                "마크가 명부를 안 찍는다 — 봇은 늘 '그 이름은 강호에 없다' 고 답한다"))

    return out


def audit_db(path: Path):
    """장부 — 실제로 이어진 것들이 **수락을 거쳐** 이어졌는가."""
    verdicts = []
    if not path.exists():
        print(f"{WARN} DB 가 없다 ({path}) — 소스만 쟀다")
        return verdicts
    conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    conn.row_factory = sqlite3.Row
    tables = {r[0] for r in conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table'")}

    if "mvt_link_request" not in tables:
        print(f"{WARN} mvt_link_request 표가 없다 — 마이그레이션 007 을 아직 안 돌렸다")
        print("      ↳ python3 tools/migrate_db.py run/bot/honcheon.db  (백업은 러너가 뜬다)")
        verdicts.append(f"{WARN} 007 미적용 — 봇이 뜰 때 schema.sql 이 표를 만들지만, 러너로 버전을 맞춰라")
        conn.close()
        return verdicts

    rows = conn.execute("SELECT state, COUNT(*) c FROM mvt_link_request GROUP BY state").fetchall()
    print("접합 청: " + (" · ".join(f"{r['state']} {r['c']}건" for r in rows) or "아직 없다"))

    # ★ 이어진 몸마다 — 그 접합이 **게임 안 수락**을 거쳤는가 (경로가 장부에 적혀 있다)
    linked = conn.execute("SELECT mc_uuid, mc_name, character_id FROM mvt_link "
                          "WHERE character_id IS NOT NULL").fetchall()
    bad = []
    for r in linked:
        ev = conn.execute(
            "SELECT data_json FROM events WHERE type='접합' AND actor_id=? "
            "ORDER BY id DESC LIMIT 1", (str(r["character_id"]),)).fetchone()
        if not ev:
            bad.append((r["mc_name"], "접합 기록이 없다"))
        elif '"경로"' in ev["data_json"] and "게임내_수락" not in ev["data_json"]:
            bad.append((r["mc_name"], "수락을 거치지 않은 접합"))
    print(f"이어진 몸 {len(linked)}개 — 수락을 거친 것 {len(linked) - len(bad)}개")
    for name, why in bad:
        print(f"  {WARN} {name}: {why} (옛 코드 방식으로 이어진 것일 수 있다 — 그때는 그것이 규칙이었다)")

    # 대기 중인데 만료가 지난 청 (봇이 안 치우고 있다)
    stale = conn.execute("SELECT COUNT(*) FROM mvt_link_request "
                         "WHERE state='대기' AND expires_at < ?",
                         (int(__import__("time").time() * 1000),)).fetchone()[0]
    if stale:
        print(f"  {WARN} 만료가 지났는데 '대기'인 청 {stale}건 — 봇이 꺼져 있는 동안 쌓인 것이다 "
              f"(켜면 첫 발행에서 '만료'로 죽는다)")
    conn.close()
    return verdicts


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("db", nargs="?", default=str(DEFAULT_DB))
    ap.add_argument("--no-db", action="store_true")
    args = ap.parse_args()

    print("=" * 78)
    print("접합 검산 — 닉네임만으로는 절대 안 이어지는가")
    print("=" * 78)

    failed = 0
    section = None
    for sec, name, ok, why in checks():
        if sec != section:
            print(f"\n{sec}")
            section = sec
        print(f"  {OK if ok else NO} {name}")
        if not ok:
            print(f"      ↳ {why}")
            failed += 1

    if not args.no_db:
        print("\n⑫ 장부")
        audit_db(Path(args.db))

    print("\n" + "=" * 78)
    if failed:
        print(f"{NO} 자물쇠가 {failed}군데 부러졌다. **닉네임이 열쇠가 되는 길이 열려 있다.**")
        sys.exit(1)
    print(f"{OK} 닉네임은 수신인일 뿐이다. 잇는 손은 그 몸의 화면 하나뿐이다.")


if __name__ == "__main__":
    main()
