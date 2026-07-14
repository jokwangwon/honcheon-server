# 추가 발견 - 2026-07-14

## 브리지 사건의 claim-before-apply 유실 가능성

- **상태**: 미확인, 재현 필요
- **위치**: `server-bot/src/main/java/com/honcheon/bot/Bridge.java`의 `apply`와 drain 루프
- **관찰**: `db.claimBridgeEvent(id, kind)`가 handler switch보다 먼저 실행된다.
- **관찰**: handler 예외는 drain 루프에서 로그만 남기고, 파일 커서는 다음 줄로 전진한다.
- **위험 가설**: handler가 일시적으로 실패하면 `bridge_inbox`에는 적용 완료처럼 남고 재생되지 않아 사건이 유실될 수 있다.
- **다음 검증**: 의도적으로 handler를 실패시키는 임시 DB/JSONL 통합 테스트에서 inbox, cursor, 세계 상태를 대조한다.
- **주의**: 아직 재현하지 않았으므로 결함으로 단정하지 않는다. B-009와 섞어 수정하지 않았다.

