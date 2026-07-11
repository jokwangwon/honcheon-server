#!/usr/bin/env python3
"""혼천 SQLite DB 마이그레이션 러너 — 순수 표준 라이브러리.

배경: 시트 스키마가 진화할 때(예: 집안 기술 grants 신설) 구 캐릭터가 소급을 못 받아
요건을 영구히 못 채우는 문제 (docs/bot/bot_alpha_guide.md '마이그레이션 선례').
절차 규정: docs/design/db_migration.md

버전 저장소  : world_meta 키 '스키마_버전' (행이 없으면 0으로 간주)
등록부      : db/migrations/NNN_설명.sql (순수 SQL)
              db/migrations/NNN_설명.py  (파이썬 훅 — sheet_json 등 JSON 소급용,
                                          def migrate(conn) 를 정의해야 한다)
백업        : 각 마이그레이션 적용 직전 <db>.pre-migrate-N-backup 생성 (sqlite backup API —
              WAL 미체크포인트 내용까지 포함)
트랜잭션    : 단계마다 BEGIN IMMEDIATE → (마이그레이션 + 버전 스탬프) → COMMIT.
              실패 시 ROLLBACK 후 중단 — DB는 직전 버전 상태 그대로.

사용법:
    python3 tools/migrate_db.py <db경로>            # 현재 버전 → 최신까지 순차 적용
    python3 tools/migrate_db.py <db경로> --status   # 현재 버전·대기 목록만 출력
    python3 tools/migrate_db.py <db경로> --dry-run  # 적용 대상 나열만 (변경 없음)

주의: 봇(단일 작성자)이 켜진 채 실행하지 말 것 — 봇 정지 → migrate → 재기동.
"""

import argparse
import importlib.util
import re
import sqlite3
import sys
from pathlib import Path

VERSION_KEY = "스키마_버전"
ROOT = Path(__file__).resolve().parent.parent
DEFAULT_MIGRATIONS_DIR = ROOT / "db" / "migrations"

FILENAME_RE = re.compile(r"^(\d{3})_(.+)\.(sql|py)$")


def discover_migrations(migrations_dir: Path):
    """번호 순으로 정렬된 [(번호, 경로)] — 번호 중복은 즉시 오류."""
    if not migrations_dir.is_dir():
        return []
    found = {}
    for path in sorted(migrations_dir.iterdir()):
        m = FILENAME_RE.match(path.name)
        if not m:
            continue  # README 등 비마이그레이션 파일 무시
        num = int(m.group(1))
        if num in found:
            sys.exit(f"오류: 마이그레이션 번호 {num:03d} 중복 — "
                     f"{found[num].name} / {path.name}")
        found[num] = path
    return sorted(found.items())


def current_version(conn: sqlite3.Connection) -> int:
    """world_meta의 스키마_버전 — 키가 없거나 테이블 자체가 없으면 0."""
    try:
        row = conn.execute(
            "SELECT value FROM world_meta WHERE key = ?", (VERSION_KEY,)
        ).fetchone()
    except sqlite3.OperationalError:
        # world_meta 미존재 = schema.sql조차 안 돈 DB. 버전 0으로 간주하되,
        # 마이그레이션은 schema.sql 적용을 전제한다 (아래 apply에서 재확인).
        return 0
    return int(row[0]) if row else 0


def stamp_version(conn: sqlite3.Connection, version: int):
    conn.execute(
        "INSERT INTO world_meta(key, value) VALUES(?, ?) "
        "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        (VERSION_KEY, str(version)),
    )


def backup_db(db_path: Path, next_num: int) -> Path:
    """적용 직전 스냅샷 — sqlite backup API 사용 (WAL 내용 포함, 파일 복사보다 안전)."""
    dest = db_path.with_name(f"{db_path.name}.pre-migrate-{next_num}-backup")
    src = sqlite3.connect(db_path)
    dst = sqlite3.connect(dest)
    try:
        with dst:
            src.backup(dst)
    finally:
        src.close()
        dst.close()
    return dest


def run_sql_file(conn: sqlite3.Connection, path: Path):
    """SQL 파일을 문장 단위로 실행.

    executescript()는 실행 전에 암묵 COMMIT을 발행해 단계 트랜잭션을 깨므로 쓰지 않는다.
    sqlite3.complete_statement 로 문장 경계를 판별한다 (문자열 내 세미콜론 안전).
    """
    buf = ""
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        buf += raw_line + "\n"
        if sqlite3.complete_statement(buf):
            stmt = buf.strip()
            buf = ""
            if stmt:
                conn.execute(stmt)
    tail = buf.strip()
    if tail and not all(l.strip().startswith("--") or not l.strip()
                        for l in tail.splitlines()):
        conn.execute(tail)  # 말미 세미콜론 누락 문장도 실행


def run_py_hook(conn: sqlite3.Connection, path: Path):
    """NNN_설명.py 를 로드해 migrate(conn) 호출. 반환 문자열은 요약으로 출력."""
    spec = importlib.util.spec_from_file_location(f"migration_{path.stem}", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    if not hasattr(module, "migrate"):
        raise RuntimeError(f"{path.name}: migrate(conn) 함수가 없다")
    return module.migrate(conn)


def apply_migrations(db_path: Path, migrations, dry_run: bool) -> int:
    conn = sqlite3.connect(db_path, isolation_level=None)  # 명시적 트랜잭션 제어
    try:
        version = current_version(conn)
        pending = [(n, p) for n, p in migrations if n > version]
        print(f"현재 스키마_버전: {version} / 최신: "
              f"{migrations[-1][0] if migrations else version}")

        if not pending:
            print("적용할 마이그레이션 없음 — 이미 최신.")
            return 0

        for num, path in pending:
            print(f"  대기: {num:03d} — {path.name}")
        if dry_run:
            print("(dry-run — 변경 없음)")
            return 0

        # 마이그레이션은 schema.sql이 적용된 DB를 전제한다
        has_meta = conn.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='world_meta'"
        ).fetchone()
        if not has_meta:
            sys.exit("오류: world_meta 테이블이 없다 — schema.sql이 적용되지 않은 DB. "
                     "봇을 한 번 기동(스키마 실행)한 뒤 마이그레이션하라.")

        for num, path in pending:
            bak = backup_db(db_path, num)
            print(f"[{num:03d}] 백업 생성: {bak.name}")
            conn.execute("BEGIN IMMEDIATE")
            try:
                if path.suffix == ".sql":
                    run_sql_file(conn, path)
                    summary = None
                else:
                    summary = run_py_hook(conn, path)
                stamp_version(conn, num)
                conn.execute("COMMIT")
            except Exception as e:
                conn.execute("ROLLBACK")
                print(f"[{num:03d}] 실패 — 롤백·중단: {e}", file=sys.stderr)
                print(f"       DB는 버전 {num - 1 if num > version else version} 상태 "
                      f"그대로. 백업: {bak}", file=sys.stderr)
                return 1
            note = f" ({summary})" if summary else ""
            print(f"[{num:03d}] 적용 완료 → 스키마_버전 = {num}{note}")

        print(f"완료: 스키마_버전 {version} → {pending[-1][0]}")
        return 0
    finally:
        conn.close()


def show_status(db_path: Path, migrations):
    conn = sqlite3.connect(db_path)
    try:
        version = current_version(conn)
    finally:
        conn.close()
    latest = migrations[-1][0] if migrations else 0
    print(f"DB: {db_path}")
    print(f"현재 스키마_버전: {version}")
    print(f"등록부 최신 번호: {latest}")
    pending = [(n, p) for n, p in migrations if n > version]
    if pending:
        print("대기 마이그레이션:")
        for num, path in pending:
            print(f"  {num:03d} — {path.name}")
    else:
        print("대기 마이그레이션 없음 — 최신 상태.")


def main():
    parser = argparse.ArgumentParser(
        description="혼천 SQLite DB 마이그레이션 러너 (docs/design/db_migration.md)")
    parser.add_argument("db", type=Path, help="SQLite DB 파일 경로 (예: data/honcheon.db)")
    parser.add_argument("--dry-run", action="store_true",
                        help="적용 대상만 나열하고 변경하지 않는다")
    parser.add_argument("--status", action="store_true",
                        help="현재 버전과 대기 목록을 출력한다")
    parser.add_argument("--migrations-dir", type=Path, default=DEFAULT_MIGRATIONS_DIR,
                        help="마이그레이션 등록부 디렉터리 (기본: db/migrations)")
    args = parser.parse_args()

    if not args.db.is_file():
        sys.exit(f"오류: DB 파일이 없다 — {args.db}")

    migrations = discover_migrations(args.migrations_dir)
    if args.status:
        show_status(args.db, migrations)
        return
    sys.exit(apply_migrations(args.db, migrations, dry_run=args.dry_run))


if __name__ == "__main__":
    main()
