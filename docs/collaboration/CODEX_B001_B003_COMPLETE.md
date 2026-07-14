# Codex 완료 스냅숏: B-001~B-003

## 결과

- `lint_config`는 `factions.yml`의 group/member id와 명시적 exempt만 참조값으로 받는다.
- `bridge_audit`는 `GameListener.regions.recover`와 `RegionService.recoveryDeltas`를 함께 확인한다.
- `bridge_audit`는 실제 위반 verdict가 있으면 종료 코드 1을 반환한다.
- `map_lint`는 이미 정상 종료 코드와 56개 self-test를 갖고 있어 수정하지 않았다.

## 변경 파일

- `tools/lint_config.py`
- `tools/bridge_audit.py`
- `tools/lint_config_selftest.py`
- `tools/bridge_audit_selftest.py`

## 검증 증거

```bash
python3 tools/lint_config_selftest.py
# 3/3 통과: 정상 id 허용, 미등록 id 거부, 표시명 거부

python3 tools/bridge_audit_selftest.py
# 4/4 통과: 실제 사슬, 끊긴 domain, 위반 exit 1, 경고 exit 0

python3 tools/lint_config.py
# 오류 0건, 경고 0건

python3 tools/bridge_audit.py --no-backup
# 지역 회복 ✅, B-009 두 사건만 실제 위반, 프로세스 exit 1

python3 -m compileall -q tools/lint_config.py tools/bridge_audit.py \
  tools/lint_config_selftest.py tools/bridge_audit_selftest.py
# 통과
```

## 상대 검토 요청

- 거짓 양성 제거가 미등록 faction id 탐지력을 약화시키지 않았는지 본다.
- 지역 회복 검사가 bot 문자열 하나가 아니라 bot-domain 두 단계 계약을 보는지 확인한다.
- 경고만 있는 경우 0, 실제 위반은 1이라는 종료 계약을 확인한다.
- 의견은 `docs/collaboration/FABLE_REVIEW.md`에 기록한다.

## 인계 지점

구현과 검증은 끝났다. 위 명령만 재실행하면 단독으로 재검증할 수 있다. `bridge_audit.py`에는 Codex 착수 전부터 있던 접합 프로토콜 변경도 함께 존재하므로, 이번 diff의 소유 범위는 domain 판독과 종료 코드 부분이다.

