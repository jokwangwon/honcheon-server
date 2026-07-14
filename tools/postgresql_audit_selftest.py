#!/usr/bin/env python3
"""postgresql_audit가 구현 퇴행을 탐지하는지 시험한다."""

from __future__ import annotations

import importlib.util
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location("postgresql_audit", ROOT / "tools/postgresql_audit.py")
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


def write_fixture(root: Path, sqlite_schema: bool = False, missing_runtime: bool = False) -> None:
    bot = root / "server-bot/src/main/java/com/honcheon/bot"
    schema_dir = root / "db/postgresql"
    tools = root / "tools"
    bot.mkdir(parents=True)
    schema_dir.mkdir(parents=True)
    tools.mkdir()
    names = [f"table_{chr(ord('a') + i)}" for i in range(20)]
    schema = "\n".join(f"CREATE TABLE IF NOT EXISTS {name}(id BIGSERIAL PRIMARY KEY);" for name in names)
    schema += "\n-- TIMESTAMPTZ\nON CONFLICT(key) DO NOTHING;"
    if sqlite_schema:
        schema += "\nPRAGMA foreign_keys=ON;"
    (schema_dir / "schema.sql").write_text(schema, encoding="utf-8")
    (root / "server-bot/build.gradle").write_text(
        "implementation 'org.postgresql:postgresql:42.6.0'", encoding="utf-8")
    (bot / "SqlDialect.java").write_text(
        "interface SqlDialect { String snapshotFileName(); String restoreInstructions(Path p); }",
        encoding="utf-8")
    (bot / "PostgresqlDialect.java").write_text(
        "class PostgresqlDialect implements SqlDialect { CopyManager c; ZipOutputStream z; "
        "void x(){ Class.forName(\"org.postgresql.Driver\"); "
        "sql(\"ON CONFLICT(event_id) DO NOTHING information_schema.tables\"); "
        "isolation(TRANSACTION_REPEATABLE_READ); connection.commit(); } }", encoding="utf-8")
    runtime = "" if missing_runtime else (
        'HONCHEON_DB_BACKEND HONCHEON_DATABASE_URL new PostgresqlDialect '
        'getOrDefault("HONCHEON_DB_BACKEND", "sqlite") new SqliteDialect()')
    (bot / "Db.java").write_text(runtime, encoding="utf-8")
    (bot / "HoncheonBot.java").write_text("Db.open(System.getenv());", encoding="utf-8")
    (bot / "Reset.java").write_text(
        "db.snapshotFileName(); db.restoreInstructions(snapshot);", encoding="utf-8")
    (tools / "PostgresqlContractSelfTest.java").write_text(
        "class PostgresqlContractSelfTest { void x(){ Db.open(environment); } "
        "String result = \"PostgreSQL 계약 눈\"; }", encoding="utf-8")


def main() -> int:
    cases = [("실제 PostgreSQL 구현을 본다", all(AUDIT.checks(ROOT).values()))]
    with tempfile.TemporaryDirectory(prefix="honcheon-pg-valid-") as td:
        root = Path(td)
        write_fixture(root)
        cases.append(("정상 구현을 통과시킨다", all(AUDIT.checks(root).values())))
    with tempfile.TemporaryDirectory(prefix="honcheon-pg-schema-leak-") as td:
        root = Path(td)
        write_fixture(root, sqlite_schema=True)
        cases.append(("PostgreSQL 스키마에 SQLite 문법이 새면 잡는다",
                      not AUDIT.checks(root)["postgres_schema_is_native"]))
    with tempfile.TemporaryDirectory(prefix="honcheon-pg-runtime-") as td:
        root = Path(td)
        write_fixture(root, missing_runtime=True)
        cases.append(("런타임 선택 배선을 지우면 잡는다",
                      not AUDIT.checks(root)["runtime_selects_backend"]))

    ok = True
    print("══ postgresql_audit 눈을 시험한다 ══")
    for name, passed in cases:
        print(("✓ " if passed else "✗ ") + name)
        ok &= passed
    print("── " + ("✓ PostgreSQL 눈이 전부 잡았다" if ok else "✗ PostgreSQL 눈이 놓쳤다"))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
