#!/usr/bin/env python3
"""persistence_inventory가 주요 SQLite 결합과 포트 경계를 실제로 찾는지 시험한다."""

from __future__ import annotations

import importlib.util
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location(
    "persistence_inventory", ROOT / "tools/persistence_inventory.py")
INVENTORY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INVENTORY)


def fixture(base: Path) -> Path:
    bot = base / "server-bot/src/main/java/com/honcheon/bot"
    bot.mkdir(parents=True)
    (base / "db/migrations").mkdir(parents=True)
    (base / "tools").mkdir()
    (bot / "Db.java").write_text(
        """class Db implements FactionLedger, RegionLedger {
        synchronized void one() { DriverManager.getConnection(\"jdbc:sqlite:x\");
        prepareStatement(\"INSERT OR IGNORE INTO x VALUES(1)\"); }
        void two() { createStatement(); sqlite_master(); }
        }""",
        encoding="utf-8",
    )
    (bot / "UsesDb.java").write_text("class UsesDb { Db db; }", encoding="utf-8")
    (bot / "NoDb.java").write_text("class NoDb {}", encoding="utf-8")
    (bot / "CommentOnly.java").write_text(
        "/** Db는 주석에만 있다. */ class CommentOnly {}", encoding="utf-8")
    (base / "db/schema.sql").write_text(
        "PRAGMA foreign_keys=ON;\nCREATE TABLE IF NOT EXISTS one(id INTEGER PRIMARY KEY AUTOINCREMENT);\n",
        encoding="utf-8",
    )
    (base / "db/migrations/001.sql").write_text(
        "INSERT OR REPLACE INTO one VALUES(1); SELECT datetime('now');",
        encoding="utf-8",
    )
    (base / "tools/read.py").write_text(
        "import sqlite3\nsqlite3.connect('x')\nSELECT = \"json_extract(data, '$.x')\"",
        encoding="utf-8",
    )
    return base


def main() -> int:
    cases = []
    with tempfile.TemporaryDirectory(prefix="honcheon-persistence-eye-") as td:
        report = INVENTORY.collect(fixture(Path(td)))
        impl = report["db_implementation"]
        markers = report["sqlite_markers"]
        cases.extend([
            ("테이블을 센다", report["schema"]["table_count"] == 1),
            ("직접 Db 소비자를 찾는다", report["direct_db_consumer_count"] == 1),
            ("동기화와 SQL 실행 지점을 센다",
             impl["synchronized_sites"] == 1 and impl["sql_statement_sites"] == 2),
            ("SQLite JDBC를 찾는다", markers["jdbc_sqlite"]["occurrences"] == 1),
            ("SQLite upsert 방언을 찾는다",
             markers["insert_or_ignore"]["occurrences"] == 1
             and markers["insert_or_replace"]["occurrences"] == 1),
            ("스키마·메타데이터 종속을 찾는다",
             markers["pragma"]["occurrences"] == 1
             and markers["sqlite_master"]["occurrences"] == 1
             and markers["autoincrement"]["occurrences"] == 1),
            ("Python SQLite와 JSON 함수를 찾는다",
             markers["python_sqlite3"]["occurrences"] == 2
             and markers["json_extract"]["occurrences"] == 1),
            ("기존 도메인 포트를 기록한다",
             report["declared_ports"] == ["FactionLedger", "RegionLedger"]),
        ])

    with tempfile.TemporaryDirectory(prefix="honcheon-persistence-missing-") as td:
        missing_caught = False
        try:
            INVENTORY.collect(Path(td))
        except INVENTORY.InventoryError:
            missing_caught = True
        cases.append(("필수 소스가 없으면 실패한다", missing_caught))

    ok = True
    print("══ persistence_inventory 눈을 시험한다 ══")
    for name, passed in cases:
        print(("✓ " if passed else "✗ ") + name)
        ok &= passed
    print("── " + ("✓ 영속화 기준선 눈이 전부 잡았다" if ok else "✗ 기준선 눈이 놓쳤다"))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
