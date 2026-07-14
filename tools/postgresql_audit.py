#!/usr/bin/env python3
"""PG-004 PostgreSQL 스키마, 방언, 런타임 선택과 계약시험을 감사한다."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BOT = Path("server-bot/src/main/java/com/honcheon/bot")
SCHEMA = Path("db/postgresql/schema.sql")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else ""


def checks(root: Path = ROOT) -> dict[str, bool]:
    bot = root / BOT
    schema = read(root / SCHEMA)
    dialect = read(bot / "PostgresqlDialect.java")
    sql_dialect = read(bot / "SqlDialect.java")
    db = read(bot / "Db.java")
    main = read(bot / "HoncheonBot.java")
    reset = read(bot / "Reset.java")
    build = read(root / "server-bot/build.gradle")
    contract = read(root / "tools/PostgresqlContractSelfTest.java")
    tables = re.findall(r"CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS\s+([a-z_]+)", schema, re.I)
    sqlite_schema_markers = re.compile(
        r"\bPRAGMA\b|\bAUTOINCREMENT\b|datetime\s*\(\s*['\"]now['\"]|\bINSERT\s+OR\s+IGNORE\b",
        re.I,
    )
    return {
        "jdbc_dependency": "org.postgresql:postgresql:" in build,
        "postgres_schema_exists": len(tables) == 20 and len(set(tables)) == 20,
        "postgres_schema_is_native": (
            sqlite_schema_markers.search(schema) is None
            and "BIGSERIAL" in schema and "TIMESTAMPTZ" in schema
            and "ON CONFLICT(key) DO NOTHING" in schema
        ),
        "postgres_dialect_exists": (
            "class PostgresqlDialect implements SqlDialect" in dialect
            and "Class.forName(\"org.postgresql.Driver\")" in dialect
        ),
        "postgres_atomic_operations": (
            "ON CONFLICT(event_id) DO NOTHING" in dialect
            and "information_schema.tables" in dialect
        ),
        "postgres_snapshot_is_consistent": (
            "TRANSACTION_REPEATABLE_READ" in dialect and "CopyManager" in dialect
            and "ZipOutputStream" in dialect and "connection.commit()" in dialect
        ),
        "snapshot_contract_is_backend_neutral": (
            "snapshotFileName" in sql_dialect and "restoreInstructions" in sql_dialect
            and "db.snapshotFileName()" in reset and "db.restoreInstructions(snapshot)" in reset
        ),
        "runtime_selects_backend": (
            "HONCHEON_DB_BACKEND" in db and "HONCHEON_DATABASE_URL" in db
            and "new PostgresqlDialect" in db and "Db.open(System.getenv())" in main
        ),
        "postgres_contract_test_exists": (
            "class PostgresqlContractSelfTest" in contract and "Db.open(environment)" in contract
            and "PostgreSQL 계약 눈" in contract
        ),
        "sqlite_fallback_remains": (
            'getOrDefault("HONCHEON_DB_BACKEND", "sqlite")' in db
            and "new SqliteDialect()" in db
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    result = checks(args.root.resolve())
    labels = {
        "jdbc_dependency": "PostgreSQL JDBC 드라이버가 런타임에 있다",
        "postgres_schema_exists": "최신 통합 스키마에 20개 표가 있다",
        "postgres_schema_is_native": "PostgreSQL 스키마에 SQLite 문법이 없다",
        "postgres_dialect_exists": "PostgreSQL 방언 구현이 있다",
        "postgres_atomic_operations": "멱등 insert와 표 조회가 PostgreSQL 문법이다",
        "postgres_snapshot_is_consistent": "반복 읽기 논리 스냅숏이 있다",
        "snapshot_contract_is_backend_neutral": "초기화 백업 계약이 백엔드 중립이다",
        "runtime_selects_backend": "환경변수로 저장소 백엔드를 선택한다",
        "postgres_contract_test_exists": "실제 PostgreSQL 계약시험이 있다",
        "sqlite_fallback_remains": "SQLite 기본 경로가 유지된다",
    }
    print("PG-004 PostgreSQL 구현 감사")
    for key, ok in result.items():
        print(("✓ " if ok else "✗ ") + labels[key])
    return 0 if all(result.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
