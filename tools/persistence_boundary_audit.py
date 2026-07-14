#!/usr/bin/env python3
"""PG-003 트랜잭션 실행 계약과 SQL 방언 경계를 감사한다."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BOT = Path("server-bot/src/main/java/com/honcheon/bot")


def java_code(source: str) -> str:
    return re.sub(r"/\*.*?\*/|//[^\r\n]*", "", source, flags=re.DOTALL)


def checks(root: Path = ROOT) -> dict[str, bool]:
    bot = root / BOT
    read = lambda name: (bot / name).read_text(encoding="utf-8") if (bot / name).exists() else ""
    tx = read("TransactionRunner.java")
    dialect = read("SqlDialect.java")
    sqlite = read("SqliteDialect.java")
    reset_port = java_code(read("ResetStore.java"))
    reset = java_code(read("Reset.java"))
    db = java_code(read("Db.java"))
    contract_path = root / "tools/PersistenceContractSelfTest.java"
    contract = contract_path.read_text(encoding="utf-8") if contract_path.exists() else ""
    sqlite_markers = re.compile(
        r"jdbc:sqlite:|\bINSERT\s+OR\s+(?:IGNORE|REPLACE)\b|\bsqlite_master\b|\bVACUUM\s+INTO\b",
        re.IGNORECASE,
    )
    return {
        "transaction_runner_exists": (
            "interface TransactionRunner" in tx and "<T> T inTransaction" in tx
        ),
        "reset_uses_transaction_runner": (
            "extends TransactionRunner" in reset_port and "db.inTransaction(" in reset
            and "beginTx(" not in reset and "endTx(" not in reset
        ),
        "db_owns_transaction_lifecycle": (
            "<T> T inTransaction(" in db and "conn.commit()" in db
            and "conn.rollback()" in db and "conn.setAutoCommit(true)" in db
            and "중첩 트랜잭션" in db
        ),
        "bridge_uses_transaction_runner": (
            "applyBridgeEvent" in db and "return inTransaction(() ->" in db
        ),
        "dialect_boundary_exists": (
            "interface SqlDialect" in dialect and "class SqliteDialect implements SqlDialect" in sqlite
        ),
        "db_uses_dialect": (
            "private final SqlDialect dialect" in db and "new SqliteDialect()" in db
            and "dialect.open(" in db and "dialect.tableExists(" in db
            and "dialect.snapshot(" in db and "dialect.claimBridgeEvent(" in db
        ),
        "db_has_no_sqlite_runtime_syntax": sqlite_markers.search(db) is None,
        "sqlite_syntax_is_isolated": (
            "jdbc:sqlite:" in sqlite and "INSERT OR IGNORE" in sqlite
            and "sqlite_master" in sqlite and "VACUUM INTO" in sqlite
        ),
        "contract_test_exists": (
            "class PersistenceContractSelfTest" in contract
            and "실패한 업무는 롤백된다" in contract
            and "암묵 중첩 트랜잭션을 거부한다" in contract
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    result = checks(args.root.resolve())
    labels = {
        "transaction_runner_exists": "콜백형 트랜잭션 실행 포트가 있다",
        "reset_uses_transaction_runner": "초기화가 수동 begin/end를 노출하지 않는다",
        "db_owns_transaction_lifecycle": "Db가 커밋·롤백·연결 회복을 소유한다",
        "bridge_uses_transaction_runner": "브리지 원자성이 공통 실행기를 사용한다",
        "dialect_boundary_exists": "SQL 방언 경계와 SQLite 구현이 있다",
        "db_uses_dialect": "Db가 SQLite 연산을 방언에 위임한다",
        "db_has_no_sqlite_runtime_syntax": "Db 런타임 코드에 SQLite 전용 문법이 없다",
        "sqlite_syntax_is_isolated": "SQLite 전용 문법이 SqliteDialect에 모여 있다",
        "contract_test_exists": "SQLite 포트 계약 시험이 있다",
    }
    print("PG-003 영속화 경계 감사")
    for key, ok in result.items():
        print(("✓ " if ok else "✗ ") + labels[key])
    return 0 if all(result.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
