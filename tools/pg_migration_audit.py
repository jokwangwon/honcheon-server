#!/usr/bin/env python3
"""이관의 눈 — PG-005 export/import 도구가 **계약을 지키는 손을 갖고 있는가**를 잰다.

진짜 시험(실제 SQLite fixture · 실제 빈 PostgreSQL 16 · 성공/실패/재실행)은
`tools/PgMigrateSelfTest.java` 가 한다 — 도커가 필요하고, 완료 문서에 재현 명령이 있다.
이 눈은 **도커 없이 언제든 도는 소스 계약 감사**다: 계약을 이루는 손이 뜯기면 잡는다.
(눈 둘의 분업은 pack_gate_audit 와 같다 — 소스의 손을 재는 눈, 실행으로 세계를 재는 눈.)

  ① **원본은 읽기 전용**       setReadOnly(true) — 이관 도구가 원본에 쓰는 길 자체가 없다
  ② **한 트랜잭션·롤백**       setAutoCommit(false) · rollback · 검산이 커밋보다 먼저
  ③ **빈 대상만**              requireEmptyTarget — 두 길(이관·복원) 모두 · 재실행 겹쳐 쓰기 없음
  ④ **시퀀스 보정**            fixSequences — 두 길 모두 · setval 로 max(id) 까지
  ⑤ **검산 다섯 축**           행 수 · world_meta · SUM(wallet) · SUM(balance) · 멱등 키 SHA-256
  ⑥ **FK 두 겹**               원본 PRAGMA foreign_key_check · 대상은 제약이 강제
  ⑦ **모르는 표 거부**         TABLE_ORDER 밖의 표를 조용히 빼놓지 않는다
  ⑧ **스냅숏 복원 경로**       PG-004 CSV ZIP → COPY FROM STDIN
  ⑨ **보고서**                 성공이든 실패든 사람이 읽을 보고서가 남는다
  ⑩ **자기 시험의 넓이**       실패 주입 · 재실행 · 원본 불변(SHA-256) · 스냅숏을 전부 겪는가

사용법:  python3 tools/pg_migration_audit.py
눈을 시험하려면:  python3 tools/pg_migration_audit_selftest.py
종료 코드: 위반(❌) 1건 이상이면 1.
"""

from __future__ import annotations

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from game_audit import Report  # noqa: E402  — 문법·출력 형식 계승

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOL = os.path.join(ROOT, "tools/PgMigrate.java")
SELFTEST = os.path.join(ROOT, "tools/PgMigrateSelfTest.java")


def source(path):
    try:
        with open(path, encoding="utf-8") as fh:
            return fh.read()
    except OSError:
        return None


def strip_comments(src):
    """주석은 말이지 코드가 아니다 — 주석에 남은 옛 문장에 눈이 속으면 안 된다.

    ★ 줄 주석은 **문자열 밖에서만** 걷는다 — `"jdbc:postgresql://…"` 의 `//` 를
      주석으로 읽으면 살아 있는 코드를 걷어낸 자리에서 오보가 난다.
    """
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    out = []
    for line in src.splitlines():
        cut, quoted = len(line), False
        i = 0
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
    rep.say("  이관의 눈 — PG-005 도구가 계약의 손을 갖고 있는가 (소스 감사)")
    rep.say("  실행 시험은 PgMigrateSelfTest 가 한다 — 이 눈은 도커 없이 손의 존재를 잰다")
    rep.say("═" * 74)

    tool_raw = source(TOOL)
    selftest_raw = source(SELFTEST)
    if tool_raw is None:
        rep.fail(f"도구가 없다 — {TOOL}")
        rep.dump()
        return 1
    if selftest_raw is None:
        rep.fail(f"자기 시험이 없다 — {SELFTEST}")
        rep.dump()
        return 1
    src = strip_comments(tool_raw)
    test = strip_comments(selftest_raw)

    # ── ① 원본은 읽기 전용 ─────────────────────────────────────────────────
    rep.head("① 원본은 읽기 전용으로만 연다")
    rep.verdict("setReadOnly(true)" in src,
                "openSqliteReadOnly 가 setReadOnly(true) 로 연다 — 원본에 쓰는 길이 없다")
    rep.verdict(not re.search(r"executeUpdate|INSERT INTO|UPDATE |DELETE FROM",
                              body_between(src, "private static Connection openSqliteReadOnly", "}")or ""),
                "읽기 전용 연결에 쓰는 문장이 없다")

    # ── ② 한 트랜잭션 · 롤백 · 검산이 커밋보다 먼저 ──────────────────────────
    rep.head("② import 전체가 한 트랜잭션이다 — 실패는 통째로 되돌린다")
    rep.verdict(src.count("setAutoCommit(false)") >= 2,
                f"두 길(이관·복원) 모두 setAutoCommit(false) 로 연다 (지금 {src.count('setAutoCommit(false)')}곳)")
    rep.verdict(src.count("rollback(dst, failure)") == 2,
                f"두 길 모두 실패하면 rollback 한다 — 반쯤 이관된 세계는 없다"
                f" (지금 {src.count('rollback(dst, failure)')}곳)")
    v, c = src.find("verify(src, dst, loaded)"), src.find("dst.commit()")
    rep.verdict(0 <= v < c, "검산이 커밋보다 먼저다 — 어긋난 채로 커밋되는 길이 없다")
    rep.verdict(re.search(r"failures\.isEmpty\(\)", src) is not None,
                "검산 불일치가 하나라도 있으면 던진다 (failures 게이트)")

    # ── ③ 빈 대상만 ─────────────────────────────────────────────────────────
    rep.head("③ 빈 대상만 받는다 — 이관은 일회성, 겹쳐 쓰기는 없다")
    rep.verdict(src.count("requireEmptyTarget(dst)") == 2,
                f"두 길 모두 빈 대상을 요구한다 (지금 {src.count('requireEmptyTarget(dst)')}곳)")
    rep.verdict("대상이 비어 있지 않다" in src,
                "비어 있지 않으면 사람이 읽을 말로 거절한다")

    # ── ④ 시퀀스 보정 ───────────────────────────────────────────────────────
    rep.head("④ BIGSERIAL 시퀀스를 max(id) 로 민다 — 안 밀면 첫 INSERT 가 충돌한다")
    rep.verdict(src.count("fixSequences(dst, loaded)") == 2,
                f"두 길 모두 시퀀스를 보정한다 (지금 {src.count('fixSequences(dst, loaded)')}곳)")
    rep.verdict("setval" in src and "pg_get_serial_sequence" in src,
                "setval · pg_get_serial_sequence 로 보정하고 다시 읽어 확인한다")
    rep.verdict("MAX(id)" in src,
                "COUNT 가 아니라 MAX(id) 기준이다 — 죽은 행이 있으면 둘은 다르다")

    # ── ⑤ 검산 다섯 축 ─────────────────────────────────────────────────────
    rep.head("⑤ 검산 다섯 축 — 양쪽에서 같은 것을 재서 대조한다")
    for needle, why in (
            ("count(src, table)", "① 표별 행 수를 양쪽에서 센다"),
            ("worldMeta(src)", "② world_meta 전체를 대조한다"),
            ("SUM(wallet)", "③ 전낭 합계를 대조한다"),
            ("SUM(balance)", "④ 은행 합계를 대조한다"),
            ("bridgeKeyDigest(src)", "⑤ 멱등 키 지문(SHA-256)을 대조한다 — 행 수가 같아도 내용이 다르면 잡는다")):
        rep.verdict(needle in src, why)

    # ── ⑥ FK 두 겹 ─────────────────────────────────────────────────────────
    rep.head("⑥ FK — 원본에서 미리 재고, 대상에서 제약이 강제한다")
    rep.verdict("foreign_key_check" in src,
                "원본 PRAGMA foreign_key_check — SQLite 는 FK 를 꺼 두었을 수 있다")
    rep.verdict("원본 FK 위반" in src, "위반이 있으면 대상을 건드리기 전에 멈춘다")

    # ── ⑦ 모르는 표 거부 ────────────────────────────────────────────────────
    rep.head("⑦ 모르는 표를 조용히 빼놓지 않는다")
    rep.verdict("requireKnownTables" in src and "TABLE_ORDER" in src,
                "표 목록을 TABLE_ORDER 와 대조한다 — 조용히 빼놓은 표는 조용히 사라진 데이터다")

    # ── ⑧ 스냅숏 복원 경로 ─────────────────────────────────────────────────
    rep.head("⑧ PG-004 CSV ZIP 스냅숏도 복원된다")
    rep.verdict("from-snapshot" in src, "from-snapshot 모드가 있다")
    rep.verdict("FROM STDIN WITH (FORMAT CSV" in src, "COPY FROM STDIN 으로 CSV 를 적재한다")

    # ── ⑨ 보고서 ───────────────────────────────────────────────────────────
    rep.head("⑨ 사람이 읽을 보고서가 남는다 — 성공이든 실패든")
    rep.verdict("Files.writeString(reportPath" in src,
                "보고서를 파일로 쓴다 (실패해도 쓴다 — try 밖이다)")

    # ── ⑩ 자기 시험의 넓이 ─────────────────────────────────────────────────
    rep.head("⑩ 자기 시험이 성공만 겪지 않는다")
    for needle, why in (
            ("어제쯤", "적재 도중 실패(썩은 타임스탬프)를 주입해 롤백을 본다"),
            ("424242", "고아 FK 를 주입해 사전 거절을 본다"),
            ("재실행", "재실행 거절을 본다"),
            ("sha256(sqlite)", "원본 불변을 SHA-256 으로 본다"),
            (None, "원본 불변의 **비교**가 두 번 있다 (거절 실행 · 이관/재실행) — 재기만 하고 안 비교하면 눈이 아니다"),
            ("from-snapshot", "스냅숏 복원 길도 겪는다"),
            ("createCharacter", "이관된 DB 를 실제 Db 로 열어 새 행을 만든다 — 시퀀스 보정의 실증")):
        if needle is None:
            rep.verdict(test.count("Arrays.equals(") >= 2, why + " — SHA-256 대조")
        else:
            rep.verdict(needle in test, why)

    rep.say()
    rep.say("─" * 74)
    if rep.violations:
        rep.say(f"  총평: ❌ 위반 {len(rep.violations)}건 — 이관 도구의 손이 온전하다고 말할 수 없다")
    else:
        rep.say("  총평: ✅ 위반 0건 — 계약의 손이 전부 제자리에 있다"
                " (실행 증거는 PgMigrateSelfTest 21눈)")
    rep.say("─" * 74)
    rep.dump()
    return 1 if rep.violations else 0


def body_between(src, start_marker, end_marker):
    """마커 다음의 첫 블록 대략 — 정밀한 중괄호 셈이 필요할 만큼 깊게 보지 않는다."""
    start = src.find(start_marker)
    if start < 0:
        return None
    end = src.find("\n    }", start)
    return src[start:end] if end > start else src[start:]


if __name__ == "__main__":
    sys.exit(main())
