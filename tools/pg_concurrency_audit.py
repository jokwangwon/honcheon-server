#!/usr/bin/env python3
"""동시성의 눈 — PG-006 이 걷어낸 전역 직렬화가 **되살아나지 않는가**를 잰다.

진짜 시험(실제 PostgreSQL 16 · 8손 망치질 · 동시 선점)은 `tools/PgConcurrencySelfTest.java` 가
한다 — 도커가 필요하고, 완료 문서에 재현 명령이 있다. 이 눈은 도커 없이 언제든 도는
**소스 계약 감사**다: 자물쇠의 시체, 반납 없는 빌림, 감싸지 않은 겨룸을 잡는다.

  ① **자물쇠의 시체 금지**      Db 에 synchronized 가 한 곳도 없다 (전역 직렬화는 죽었다)
  ② **방언이 샘을 고른다**      SQLite 한 손 · PostgreSQL 풀 + SERIALIZABLE
  ③ **가면의 반납 짝**          문장 close→반납 · 트랜잭션 끝→반납 · 열다 실패도 반납
  ④ **겹친 빌림은 같은 연결**   BoundConnectionSource 가 깊이를 센다
  ⑤ **겨룸 메서드는 원자다**    읽고-계산하고-쓰는 여덟이 전부 감싸였다
  ⑥ **재시도는 유한하다**       충돌만 · 상한 있음 (무한 재시도는 조용한 정지다)
  ⑦ **풀은 소리낸다**           고갈이 침묵하지 않는다 · 지표가 있다
  ⑧ **충돌 시험의 넓이**        동시 열림 · 같은 뭉치 망치질 · 동시 선점 · SQLite 회귀

사용법:  python3 tools/pg_concurrency_audit.py
눈을 시험하려면:  python3 tools/pg_concurrency_audit_selftest.py
종료 코드: 위반(❌) 1건 이상이면 1.
"""

from __future__ import annotations

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from game_audit import Report  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BOT = os.path.join(ROOT, "server-bot/src/main/java/com/honcheon/bot")
DB = os.path.join(BOT, "Db.java")
ROUTER = os.path.join(BOT, "RoutingConnection.java")
BOUND = os.path.join(BOT, "BoundConnectionSource.java")
POOL = os.path.join(BOT, "PooledConnectionSource.java")
SQLITE = os.path.join(BOT, "SqliteDialect.java")
PG = os.path.join(BOT, "PostgresqlDialect.java")
TEST = os.path.join(ROOT, "tools/PgConcurrencySelfTest.java")

# 읽고-계산하고-쓰는 여덟 — 전역 자물쇠가 사라진 뒤 스스로 원자여야 하는 손들
CONTENDED = ("advanceDay", "addSectBurden", "addMandate", "addBloodDebt",
             "mergeBloodDebt", "pendLinkRequest", "livingLinkRequests", "addMyeongbun")


def source(path):
    try:
        with open(path, encoding="utf-8") as fh:
            return fh.read()
    except OSError:
        return None


def strip_comments(src):
    """주석은 말이지 코드가 아니다 — 문자열 속 // 는 산 채로 둔다."""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    out = []
    for line in src.splitlines():
        cut, quoted, i = len(line), False, 0
        while i < len(line) - 1:
            ch = line[i]
            if ch == '"' and (i == 0 or line[i - 1] != "\\"):
                quoted = not quoted
            elif not quoted and ch == "/" and line[i + 1] == "/":
                cut = i
                break
            i += 1
        out.append(line[:cut])
    return "\n".join(out)


def main():
    rep = Report()
    rep.say("═" * 74)
    rep.say("  동시성의 눈 — 전역 직렬화가 되살아나지 않는가 (PG-006 · 소스 감사)")
    rep.say("  실행 증거는 PgConcurrencySelfTest — 이 눈은 도커 없이 손의 존재를 잰다")
    rep.say("═" * 74)

    files = {}
    for name, path in (("Db", DB), ("가면", ROUTER), ("결속", BOUND), ("풀", POOL),
                       ("SQLite 방언", SQLITE), ("PG 방언", PG), ("충돌 시험", TEST)):
        raw = source(path)
        if raw is None:
            rep.fail(f"{name} 이 없다 — {path}")
            rep.dump()
            return 1
        files[name] = strip_comments(raw)

    db, router, bound = files["Db"], files["가면"], files["결속"]
    pool, sqlite, pg, test = files["풀"], files["SQLite 방언"], files["PG 방언"], files["충돌 시험"]

    # ── ① 자물쇠의 시체 금지 ────────────────────────────────────────────────
    rep.head("① Db 에 synchronized 가 한 곳도 없다 — 전역 직렬화는 죽었다")
    corpses = re.findall(r"\bsynchronized\b", db)
    rep.verdict(not corpses,
                f"Db 의 synchronized: {len(corpses)}곳 — 하나라도 살아나면 풀이 무의미해진다")

    # ── ② 방언이 샘을 고른다 ────────────────────────────────────────────────
    rep.head("② 방언이 샘을 고른다 — SQLite 한 손 · PostgreSQL 풀 + SERIALIZABLE")
    rep.verdict(re.search(r"pooled\(\)\s*\{\s*return\s+false", sqlite) is not None,
                "SQLite 는 한 손이다 (pooled=false — 직렬화가 곧 SQLite 의 사실)")
    rep.verdict(re.search(r"pooled\(\)\s*\{\s*return\s+true", pg) is not None,
                "PostgreSQL 은 풀이다 (pooled=true)")
    rep.verdict("TRANSACTION_SERIALIZABLE" in pg,
                "PostgreSQL 연결은 SERIALIZABLE — 버전 충돌이 순서를 판정한다")
    rep.verdict('"40001"' in pg and '"40P01"' in pg,
                "충돌의 낯(40001 직렬화 실패 · 40P01 교착)을 방언이 안다")
    rep.verdict("PooledConnectionSource" in db and "SingleConnectionSource" in db
                and "dialect.pooled()" in db,
                "Db 가 방언의 말대로 샘을 고른다")

    # ── ③ 가면의 반납 짝 ────────────────────────────────────────────────────
    rep.head("③ 가면(RoutingConnection)의 빌림에는 반납 짝이 있다 — 짝 없는 빌림은 누수다")
    rep.verdict(router.count("source.release(real)") >= 6,
                f"반납이 모든 길에 있다 (지금 {router.count('source.release(real)')}곳 —"
                " 문장 close · 열다 실패 · 트랜잭션 끝 ×2 · commit/rollback)")
    stmt = router[router.find("private Object statement("):router.find("private Object setAutoCommit(")]
    rep.verdict(re.search(r"if\s*\(closing\)\s*\{\s*source\.release", stmt) is not None,
                "문장이 닫히면 연결이 돌아간다 (close 의 finally 가 직접 반납한다)")
    off = router[router.find("private Object setAutoCommit("):router.find("private Object onBound(")]
    rep.verdict(off.count("source.release(real)") >= 3,
                "트랜잭션의 끝(setAutoCommit(true))이 고정의 짝까지 반납한다")

    # ── ④ 겹친 빌림은 같은 연결 ─────────────────────────────────────────────
    rep.head("④ 겹친 빌림은 같은 연결이다 — 아니면 풀에서 자기 자신과 교착한다")
    rep.verdict("ThreadLocal" in bound and "depth" in bound,
                "BoundConnectionSource 가 스레드마다 깊이를 센다")
    rep.verdict(re.search(r"if\s*\(\s*--b\.depth\s*==\s*0\s*\)", bound) is not None,
                "마지막 짝이 풀리는 순간에만 샘으로 돌아간다")

    # ── ⑤ 겨룸 메서드는 원자다 ──────────────────────────────────────────────
    rep.head("⑤ 읽고-계산하고-쓰는 여덟이 전부 감싸였다 — 자물쇠가 사라진 자리의 원자성")
    for name in CONTENDED:
        pattern = rf"public\s+[\w<>,\s]+\b{name}\s*\([^)]*\)[^{{]*\{{\s*(?:return\s+)?atomically"
        wrapped = re.search(pattern, db, flags=re.S)
        rep.verdict(wrapped is not None, f"{name} — atomically 로 감싸였다 (겨룸의 손)")

    # ── ⑥ 재시도는 유한하다 ─────────────────────────────────────────────────
    rep.head("⑥ 재시도는 충돌에만, 유한하게 — 무한 재시도는 조용한 정지다")
    rep.verdict("isRetryableConflict" in db, "충돌(40001·40P01)만 다시 잰다 — 진짜 오류는 그대로 던진다")
    rep.verdict(re.search(r"attempt\s*>=\s*\d+", db) is not None, "재시도에 상한이 있다")
    rep.verdict("source.inTransaction()" in db,
                "이미 트랜잭션 안이면 합류한다 — 중첩을 만들지 않는다")

    # ── ⑦ 풀은 소리낸다 ─────────────────────────────────────────────────────
    rep.head("⑦ 풀은 소리낸다 — 고갈도 지표도 침묵하지 않는다")
    rep.verdict("풀 고갈" in pool, "고갈이 침묵하지 않는다 — 기다리다 끝나면 이유를 말하고 죽는다")
    rep.verdict("setTransactionIsolation" in pool, "격리는 연결이 태어날 때 걸린다 — 매번 다시 걸 필요가 없다")
    rep.verdict("describe()" in pool and "최고 동시" in pool,
                "지표가 있다 (Db.storageStats — 사람이 풀에게 물을 수 있다)")

    # ── ⑧ 충돌 시험의 넓이 ─────────────────────────────────────────────────
    rep.head("⑧ 충돌 시험이 성공만 겪지 않는다")
    for needle, why in (("inside.await", "두 트랜잭션이 서로의 **안**을 기다린다 — 전역 직렬화면 굳는다"),
                        ("hammerSameAggregate(db, \"postgresql\")", "같은 뭉치를 여덟 손이 두드린다 (PostgreSQL)"),
                        ("hammerSameAggregate(db, \"sqlite\")", "같은 망치질을 SQLite 로도 한다 (한 손 회귀)"),
                        ("applyBridgeEvent", "같은 사건의 동시 선점 — 처리기는 한 번"),
                        ("storageStats", "지표를 실제로 읽는다")):
        rep.verdict(needle in test, why)

    rep.say()
    rep.say("─" * 74)
    if rep.violations:
        rep.say(f"  총평: ❌ 위반 {len(rep.violations)}건 — 전역 직렬화가 없다고 말할 수 없다")
    else:
        rep.say("  총평: ✅ 위반 0건 — 자물쇠는 죽었고, 겨룸은 판정되고, 빌림에는 짝이 있다")
    rep.say("─" * 74)
    rep.dump()
    return 1 if rep.violations else 0


if __name__ == "__main__":
    sys.exit(main())
