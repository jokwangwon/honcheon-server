# Codex 활성 작업: B-001~B-003

## 2026-07-14 - 조사

- 목적: 감사기가 현재 아키텍처를 정확히 읽고 실제 위반이 있으면 종료 코드 1로 자동화를 막게 한다.
- 소유 파일: `tools/lint_config.py`, `tools/bridge_audit.py`, 관련 self-test
- 가설: B-001은 세력 display name 대신 id를 읽어야 한다. B-002는 봇뿐 아니라 domain 호출 사슬을 봐야 한다. B-003은 위반 시 종료 코드 계약이 없다.
- 검증 계획: 기존 실패 재현, self-test 확인, 최소 수정, 위반 주입 self-test, 관련 감사, `backlog_audit.py`.
- 현재 변경: 프로덕션 수정 없음.
- 인계 지점: `python3 tools/lint_config.py`와 `python3 tools/bridge_audit.py; echo $?`부터 재현한다.

