# BACKLOG 상태 오버레이 - 2026-07-14

> 기존 `docs/BACKLOG.md`를 대체하지 않는다. 편집 샌드박스 오류로 canonical 상태를 갱신하지 못한 항목만 기록한다.

| 항목 | 코드 상태 | 증거 | canonical 동기화 |
|---|---|---|---|
| B-001 | 완료 | `lint_config` 오류 0건, self-test 3/3 | 대기 |
| B-002 | 완료 | 실제 감사의 지역 회복 ✅, self-test domain 단절 탐지 | 대기 |
| B-003 | 완료 | B-009 실제 위반 2건에서 exit 1, self-test 4/4 | 대기 |

페이블 또는 이후 Codex 세션은 기존 파일 편집이 가능한 환경에서 `docs/BACKLOG.md`의 세 항목을 닫고 `python3 tools/backlog_audit.py --run`을 실행한다. B-002와 B-003의 검증 명령은 각각의 self-test로 바꾸면 B-009가 열려 있어도 독립적으로 재검증할 수 있다.

