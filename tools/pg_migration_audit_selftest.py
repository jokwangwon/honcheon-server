#!/usr/bin/env python3
"""이관의 눈의 자기 시험 — 계약의 손을 하나씩 뜯고, 눈이 잡는지 본다.

"위반 0건"은 문이 닫혔다는 뜻일 수도, 눈이 멀었다는 뜻일 수도 있다.
그래서 pg_migration_audit.py 에게 일부러 거짓말을 먹인다: 읽기 전용을 풀고,
롤백을 뜯고, 시퀀스 보정을 한 길에서만 하고, 검산 축을 지우고, 실패 주입을 없앤다 —
그때마다 눈이 실제로 잡는지 본다. 끝나면 전부 되돌린다.

사용법:  python3 tools/pg_migration_audit_selftest.py
종료 코드: 눈이 하나라도 놓치면 1.
"""
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOL = os.path.join(ROOT, "tools/PgMigrate.java")
SELFTEST = os.path.join(ROOT, "tools/PgMigrateSelfTest.java")
AUDIT = os.path.join(ROOT, "tools/pg_migration_audit.py")

# (이름, 파일, 원본조각, 바꿀조각, 잡아야 하는 말의 조각)
MUTATIONS = [
    ("① 원본을 쓰기 가능으로 연다", TOOL,
     "config.setReadOnly(true);", "config.setReadOnly(false);",
     "setReadOnly(true)"),

    ("② 롤백을 뜯는다 (실패해도 되돌리지 않는다)", TOOL,
     "                rollback(dst, failure);\n                throw failure;\n            } finally {\n                dst.setAutoCommit(true);\n            }\n        }\n    }\n\n    /** 원본은 읽기 전용으로 연다",
     "                throw failure;\n            } finally {\n                dst.setAutoCommit(true);\n            }\n        }\n    }\n\n    /** 원본은 읽기 전용으로 연다",
     "rollback"),

    ("③ 검산을 커밋 뒤로 미룬다 (어긋난 채로 커밋된다)", TOOL,
     "                verify(src, dst, loaded);\n                if (!failures.isEmpty()) {\n                    throw new IllegalStateException(\"검산 불일치 \" + failures.size() + \"건 — \"\n                            + String.join(\" · \", failures));\n                }\n                dst.commit();",
     "                dst.commit();\n                verify(src, dst, loaded);\n                if (!failures.isEmpty()) {\n                    throw new IllegalStateException(\"검산 불일치 \" + failures.size() + \"건 — \"\n                            + String.join(\" · \", failures));\n                }",
     "커밋보다 먼저"),

    ("④ 한 길에서만 빈 대상을 요구한다 (복원 길이 겹쳐 쓴다)", TOOL,
     "            ensureSchema(dst);\n            requireEmptyTarget(dst);\n            dst.setAutoCommit(false);\n            try {\n                try (Statement st = dst.createStatement()) {\n                    st.executeUpdate(\"DELETE FROM world_meta\");\n                }\n                CopyManager copy",
     "            ensureSchema(dst);\n            dst.setAutoCommit(false);\n            try {\n                try (Statement st = dst.createStatement()) {\n                    st.executeUpdate(\"DELETE FROM world_meta\");\n                }\n                CopyManager copy",
     "빈 대상을 요구"),

    ("⑤ 한 길에서만 시퀀스를 보정한다 (복원본의 첫 INSERT 가 충돌한다)", TOOL,
     "                    loaded.put(table, rows);\n                }\n                fixSequences(dst, loaded);",
     "                    loaded.put(table, rows);\n                }",
     "시퀀스를 보정"),

    ("⑥ 멱등 키 지문을 지운다 (행 수만 세고 내용은 안 본다)", TOOL,
     "        String keysSrc = bridgeKeyDigest(src);", "        String keysSrc = \"\";",
     "멱등 키 지문"),

    ("⑦ MAX(id) 를 COUNT 로 바꾼다 (죽은 행이 있으면 시퀀스가 모자란다)", TOOL,
     "SELECT MAX(id) FROM ", "SELECT COUNT(id) FROM ",
     "MAX(id)"),

    ("⑧ 자기 시험에서 실패 주입을 없앤다 (성공만 겪는 시험)", SELFTEST,
     "'어제쯤'", "'2026-01-01 00:00:00'",
     "썩은 타임스탬프"),

    ("⑨ 자기 시험에서 원본 불변의 눈을 없앤다", SELFTEST,
     "java.util.Arrays.equals(cleanFixture, sha256(sqlite))",
     "true",
     "SHA-256"),
]


def run_audit():
    r = subprocess.run([sys.executable, AUDIT], capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr


def caught(out, needle):
    """그 말이 ❌ 로 찍혔는가 — ✅ 로 찍힌 같은 말에 속으면 안 된다."""
    return any(needle in line and "❌" in line for line in out.splitlines())


def main():
    print("═" * 74)
    print("  이관의 눈 — 자기 시험 (계약의 손을 하나씩 뜯고, 눈이 잡는지 본다)")
    print("═" * 74)

    base_rc, base_out = run_audit()
    if base_rc != 0:
        print("\n❌ 시작부터 위반이 있다 — 먼저 그것부터 고쳐라. 시험을 멈춘다.\n")
        print(base_out)
        return 1
    print("\n  기준선: 위반 0건 — 이제 하나씩 뜯어 본다\n")

    backups = {}
    for path in (TOOL, SELFTEST):
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
    print("  되돌렸다 — 도구·자기 시험은 손대지 않은 상태다 (재감사: 위반 0건)")
    if missed:
        print(f"  총평: ❌ 눈이 {len(missed)}건을 놓쳤다 — " + " · ".join(missed))
        print("─" * 74)
        return 1
    print(f"  총평: ✅ {len(MUTATIONS)}건 전부 잡았다 — 이 눈은 볼 수 있다")
    print("─" * 74)
    return 0


if __name__ == "__main__":
    sys.exit(main())
