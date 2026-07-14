#!/usr/bin/env python3
"""persistence_boundary_audit가 경계 퇴행을 탐지하는지 시험한다."""

from __future__ import annotations

import importlib.util
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location(
    "persistence_boundary_audit", ROOT / "tools/persistence_boundary_audit.py")
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


def write_fixture(root: Path, sqlite_leak: bool = False, manual_reset_tx: bool = False) -> None:
    bot = root / "server-bot/src/main/java/com/honcheon/bot"
    tools = root / "tools"
    bot.mkdir(parents=True)
    tools.mkdir()
    (bot / "TransactionRunner.java").write_text(
        "interface TransactionRunner { <T> T inTransaction(Work<T> work); interface Work<T> {} }",
        encoding="utf-8")
    (bot / "SqlDialect.java").write_text("interface SqlDialect {}", encoding="utf-8")
    (bot / "SqliteDialect.java").write_text(
        "class SqliteDialect implements SqlDialect { String sql = \"jdbc:sqlite: INSERT OR IGNORE "
        "sqlite_master VACUUM INTO\"; }", encoding="utf-8")
    (bot / "ResetStore.java").write_text(
        "interface ResetStore extends TransactionRunner {}", encoding="utf-8")
    reset_call = "db.beginTx(); db.endTx(true);" if manual_reset_tx else "db.inTransaction(() -> null);"
    (bot / "Reset.java").write_text(f"class Reset {{ void run() {{ {reset_call} }} }}", encoding="utf-8")
    leak = ' String leak = "INSERT OR IGNORE";' if sqlite_leak else ""
    (bot / "Db.java").write_text(
        "class Db { private final SqlDialect dialect = new SqliteDialect();" + leak
        + " Db() { dialect.open(); dialect.tableExists(); dialect.snapshot(); "
        "dialect.claimBridgeEvent(); } <T> T inTransaction(TransactionRunner.Work<T> work) { "
        "conn.commit(); conn.rollback(); conn.setAutoCommit(true); throw new Error(\"중첩 트랜잭션\"); } "
        "boolean applyBridgeEvent() { return inTransaction(() -> null); } }",
        encoding="utf-8")
    (tools / "PersistenceContractSelfTest.java").write_text(
        "class PersistenceContractSelfTest { String a = \"실패한 업무는 롤백된다\"; "
        "String b = \"암묵 중첩 트랜잭션을 거부한다\"; }", encoding="utf-8")


def main() -> int:
    cases = [("실제 영속화 경계를 본다", all(AUDIT.checks(ROOT).values()))]
    with tempfile.TemporaryDirectory(prefix="honcheon-boundary-valid-") as td:
        root = Path(td)
        write_fixture(root)
        cases.append(("정상 경계를 통과시킨다", all(AUDIT.checks(root).values())))
    with tempfile.TemporaryDirectory(prefix="honcheon-boundary-leak-") as td:
        root = Path(td)
        write_fixture(root, sqlite_leak=True)
        cases.append(("Db로 SQLite 문법이 새면 잡는다",
                      not AUDIT.checks(root)["db_has_no_sqlite_runtime_syntax"]))
    with tempfile.TemporaryDirectory(prefix="honcheon-boundary-manual-") as td:
        root = Path(td)
        write_fixture(root, manual_reset_tx=True)
        cases.append(("초기화가 수동 트랜잭션으로 돌아가면 잡는다",
                      not AUDIT.checks(root)["reset_uses_transaction_runner"]))

    ok = True
    print("══ persistence_boundary_audit 눈을 시험한다 ══")
    for name, passed in cases:
        print(("✓ " if passed else "✗ ") + name)
        ok &= passed
    print("── " + ("✓ 영속화 경계 눈이 전부 잡았다" if ok else "✗ 경계 눈이 놓쳤다"))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
