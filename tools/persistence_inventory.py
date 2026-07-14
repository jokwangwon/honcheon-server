#!/usr/bin/env python3
"""SQLite → PostgreSQL 전환 전에 영속화 결합도를 재는 읽기 전용 기준선 도구."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DB_REL = Path("server-bot/src/main/java/com/honcheon/bot/Db.java")
BOT_REL = Path("server-bot/src/main/java/com/honcheon/bot")
SCHEMA_REL = Path("db/schema.sql")

SQLITE_MARKERS = {
    "jdbc_sqlite": r"jdbc:sqlite:",
    "pragma": r"\bPRAGMA\b",
    "insert_or_ignore": r"\bINSERT\s+OR\s+IGNORE\b",
    "insert_or_replace": r"\bINSERT\s+OR\s+REPLACE\b",
    "sqlite_master": r"\bsqlite_master\b",
    "vacuum_into": r"\bVACUUM\s+INTO\b",
    "datetime_now": r"datetime\s*\(\s*['\"]now['\"]\s*\)",
    "json_extract": r"\bjson_extract\s*\(",
    "autoincrement": r"\bAUTOINCREMENT\b",
    "python_sqlite3": r"(?:\bimport\s+sqlite3\b|\bsqlite3\.connect\s*\()",
}


class InventoryError(RuntimeError):
    pass


def _text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _java_code(source: str) -> str:
    return re.sub(r"/\*.*?\*/|//[^\r\n]*", "", source, flags=re.DOTALL)


def _source_files(root: Path) -> list[Path]:
    paths = list((root / "server-bot/src/main/java").rglob("*.java"))
    paths += list((root / "db").rglob("*.sql"))
    paths += list((root / "tools").glob("*.py"))
    return sorted(set(paths))


def collect(root: Path = ROOT) -> dict[str, object]:
    db_path = root / DB_REL
    schema_path = root / SCHEMA_REL
    bot_dir = root / BOT_REL
    missing = [str(p.relative_to(root)) for p in (db_path, schema_path, bot_dir) if not p.exists()]
    if missing:
        raise InventoryError("필수 영속화 소스가 없다: " + ", ".join(missing))

    db_src = _text(db_path)
    schema = _text(schema_path)
    sources = _source_files(root)
    texts = {path: _text(path) for path in sources}

    markers: dict[str, dict[str, object]] = {}
    for name, pattern in SQLITE_MARKERS.items():
        hits = {path: len(re.findall(pattern, text, re.IGNORECASE))
                for path, text in texts.items()}
        hits = {path: count for path, count in hits.items() if count}
        markers[name] = {
            "occurrences": sum(hits.values()),
            "files": [str(path.relative_to(root)) for path in hits],
        }

    consumers = []
    for path in sorted(bot_dir.glob("*.java")):
        if path != db_path and re.search(r"\bDb\b", _java_code(_text(path))):
            consumers.append(str(path.relative_to(root)))

    tables = re.findall(
        r"^\s*CREATE\s+TABLE(?:\s+IF\s+NOT\s+EXISTS)?\s+['\"]?([^\s('\"]+)",
        schema,
        re.IGNORECASE | re.MULTILINE,
    )
    ports = re.findall(r"implements\s+([^\{]+)\{", db_src)

    return {
        "db_implementation": {
            "lines": len(db_src.splitlines()),
            "synchronized_sites": len(re.findall(r"\bsynchronized\b", db_src)),
            "sql_statement_sites": len(re.findall(r"(?:prepareStatement|createStatement)\s*\(", db_src)),
            "jdbc_connections": len(re.findall(r"DriverManager\.getConnection\s*\(", db_src)),
        },
        "schema": {"tables": tables, "table_count": len(tables)},
        "direct_db_consumers": consumers,
        "direct_db_consumer_count": len(consumers),
        "new_db_sites": sum(len(re.findall(r"\bnew\s+Db\s*\(", _java_code(text)))
                            for path, text in texts.items()
                            if path.suffix == ".java"),
        "declared_ports": [part.strip() for group in ports for part in group.split(",")],
        "sqlite_markers": markers,
    }


def print_human(report: dict[str, object]) -> None:
    impl = report["db_implementation"]
    schema = report["schema"]
    print("PostgreSQL 전환 기준선")
    print(f"- Db.java: {impl['lines']}줄 · synchronized {impl['synchronized_sites']} · "
          f"SQL 실행 지점 {impl['sql_statement_sites']} · JDBC 연결 생성 {impl['jdbc_connections']}")
    print(f"- 스키마: {schema['table_count']}개 테이블")
    print(f"- Db 직접 소비자: {report['direct_db_consumer_count']}개 파일 · new Db {report['new_db_sites']}곳")
    print(f"- 이미 분리된 포트: {', '.join(report['declared_ports']) or '없음'}")
    print("- SQLite 전용 결합:")
    for name, evidence in report["sqlite_markers"].items():
        if evidence["occurrences"]:
            print(f"  {name}: {evidence['occurrences']}건 / {len(evidence['files'])}개 파일")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    try:
        report = collect(args.root.resolve())
    except InventoryError as exc:
        print(f"오류: {exc}", file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print_human(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
