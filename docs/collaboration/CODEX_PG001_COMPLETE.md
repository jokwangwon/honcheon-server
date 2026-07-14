# Codex 완료 - PG-001 영속화 기준선

## 결과

- PostgreSQL 전환을 8개 순차 작업으로 분리했다.
- 읽기 전용 `tools/persistence_inventory.py`가 저장소 결합도를 반복 측정한다.
- 자체 시험은 주요 SQLite 문법, Python 도구 결합, 직접 `Db` 소비자, 기존 포트를 일부러 심어 탐지한다.

## 실제 기준선

- `Db.java`: 1,767줄
- `synchronized`: 86곳
- SQL 실행 지점: 87곳
- JDBC 연결 생성: 1곳
- 스키마: 19개 테이블
- 직접 `Db` 소비자: 실제 코드 4개 Java 파일
- 정정: 최초 6개는 `Rules`, `Rumors`의 Javadoc 안 `Db` 언급까지 센 오탐이었다. PG-002A에서 탐지기를 수정했다.
- `new Db`: 1곳
- 기존 포트: `AutoCloseable`, `FactionLedger`, `RegionLedger`
- SQLite 결합: JDBC 3, PRAGMA 6, OR IGNORE 5, OR REPLACE 2, sqlite_master 9, VACUUM INTO 4, datetime(now) 4, json_extract 4, AUTOINCREMENT 4, Python sqlite3 12

## 검증

- `python3 tools/persistence_inventory_selftest.py` - 9/9
- `python3 tools/persistence_inventory.py` - 성공
- `python3 -m compileall -q tools/persistence_inventory.py tools/persistence_inventory_selftest.py` - 성공
- 관련 파일 `git diff --check` - 성공

## 다음 작업 PG-002

직접 `Db` 소비자 4개가 요구하는 메서드를 기능별로 분류하고 포트를 정의한다. 첫 단계에서는 SQL을 옮기거나 동시성을 바꾸지 않는다. `Db`가 새 포트를 구현하게 하여 동작을 보존하고, 소비자의 구체 타입 의존만 줄인다.

Fable은 포트가 업무 경계인지 단순 테이블 CRUD 묶음인지 검토한다. 테이블별 DAO 분해는 세계 트랜잭션을 찢을 수 있으므로 피한다.
