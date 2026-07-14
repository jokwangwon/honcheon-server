#!/usr/bin/env python3
"""동시성의 눈의 자기 시험 — 자물쇠를 되살리고, 반납을 뜯고, 원자를 풀어 본다.

pg_concurrency_audit.py 에게 일부러 거짓말을 먹인다: Db 에 synchronized 를 되살리고,
가면의 반납을 뜯고, 겨룸 메서드의 감쌈을 풀고, 재시도를 무한으로 만들고, 시험의 축을 지운다 —
그때마다 눈이 실제로 잡는지 본다. 끝나면 전부 되돌린다.

사용법:  python3 tools/pg_concurrency_audit_selftest.py
종료 코드: 눈이 하나라도 놓치면 1.
"""
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BOT = os.path.join(ROOT, "server-bot/src/main/java/com/honcheon/bot")
DB = os.path.join(BOT, "Db.java")
ROUTER = os.path.join(BOT, "RoutingConnection.java")
PG = os.path.join(BOT, "PostgresqlDialect.java")
TEST = os.path.join(ROOT, "tools/PgConcurrencySelfTest.java")
AUDIT = os.path.join(ROOT, "tools/pg_concurrency_audit.py")

# (이름, 파일, 원본조각, 바꿀조각, 잡아야 하는 말의 조각)
MUTATIONS = [
    ("① 자물쇠를 되살린다 (Db 에 synchronized 하나)", DB,
     "    public int worldDay() throws SQLException {",
     "    public synchronized int worldDay() throws SQLException {",
     "synchronized"),

    ("② PostgreSQL 을 한 손으로 되돌린다 (pooled=false)", PG,
     "    public boolean pooled() {\n        return true;",
     "    public boolean pooled() {\n        return false;",
     "pooled=true"),

    ("③ 문장의 반납을 뜯는다 (close 가 연결을 안 돌려준다)", ROUTER,
     "                        } finally {\n                            if (closing) {\n                                source.release(real);   // 문장이 닫히면 연결이 샘으로 돌아간다\n                            }\n                        }",
     "                        } finally {\n                            if (closing) {\n                                ;\n                            }\n                        }",
     "문장이 닫히면"),

    ("④ 겨룸 메서드의 감쌈을 푼다 (advanceDay 가 맨몸으로 겨룬다)", DB,
     "        return atomicallySql(() -> {\n            int next = worldDay() + 1;",
     "        {\n            int next = worldDay() + 1;",
     "advanceDay"),

    ("⑤ 재시도를 무한으로 만든다 (조용한 정지)", DB,
     "if (!dialect.isRetryableConflict(failure) || attempt >= 8) {",
     "if (!dialect.isRetryableConflict(failure)) {",
     "상한"),

    ("⑥ 시험에서 SQLite 회귀를 지운다", TEST,
     "                hammerSameAggregate(db, \"sqlite\");",
     "                ;",
     "SQLite 로도"),
]


def run_audit():
    r = subprocess.run([sys.executable, AUDIT], capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr


def caught(out, needle):
    """그 말이 ❌ 로 찍혔는가 — ✅ 로 찍힌 같은 말에 속으면 안 된다."""
    return any(needle in line and "❌" in line for line in out.splitlines())


def main():
    print("═" * 74)
    print("  동시성의 눈 — 자기 시험 (자물쇠를 되살려 보고, 눈이 잡는지 본다)")
    print("═" * 74)

    base_rc, base_out = run_audit()
    if base_rc != 0:
        print("\n❌ 시작부터 위반이 있다 — 먼저 그것부터 고쳐라. 시험을 멈춘다.\n")
        print(base_out)
        return 1
    print("\n  기준선: 위반 0건 — 이제 하나씩 뜯어 본다\n")

    backups = {}
    for path in (DB, ROUTER, PG, TEST):
        backups[path] = path + ".selftest.bak"
        shutil.copy2(path, backups[path])

    missed = []
    try:
        for name, path, old, new, needle in MUTATIONS:
            with open(path, encoding="utf-8") as fh:
                original = fh.read()
            if old not in original:
                print(f"  ⚠️  {name} — 원본 조각을 못 찾았다 (시험이 낡았다): {old[:48]!r}")
                missed.append(name + " (시험이 낡음)")
                continue
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(original.replace(old, new, 1))
            rc, out = run_audit()
            with open(path, "w", encoding="utf-8") as fh:   # 즉시 되돌린다
                fh.write(original)

            if rc != 0 and caught(out, needle):
                print(f"  ✅ {name} — 눈이 잡았다")
            elif rc != 0:
                print(f"  ⚠️  {name} — 위반은 났는데 **엉뚱한 것을 잡았다** (기대: “{needle}”)")
                missed.append(name + " (엉뚱한 것을 잡음)")
            else:
                print(f"  ❌ {name} — **눈이 못 잡았다** (위반 0건으로 통과시켰다)")
                missed.append(name)
    finally:
        for path, bak in backups.items():
            shutil.copy2(bak, path)
            os.remove(bak)

    rc, _ = run_audit()
    print("\n" + "─" * 74)
    if rc != 0:
        print("  ❌ 되돌리기 실패 — 파일이 원래대로가 아니다. 손으로 확인하라.")
        return 1
    print("  되돌렸다 — 소스는 손대지 않은 상태다 (재감사: 위반 0건)")
    if missed:
        print(f"  총평: ❌ 눈이 {len(missed)}건을 놓쳤다 — " + " · ".join(missed))
        print("─" * 74)
        return 1
    print(f"  총평: ✅ {len(MUTATIONS)}건 전부 잡았다 — 이 눈은 볼 수 있다")
    print("─" * 74)
    return 0


if __name__ == "__main__":
    sys.exit(main())
