# Codex 완료 - 브리지 전달 원자성

## 결과

- 기존 결함을 확정했다: `bridge_inbox` 선점 뒤 처리기가 실패해도 JSONL 커서가 전진해 사건이 영구 유실됐다.
- `Db.applyBridgeEvent`가 inbox 선점, 세계 DB 변경, 커서 기록을 SQLite 한 트랜잭션으로 처리한다.
- 처리기 예외는 롤백 후 수신 루프 밖으로 전달되며, 커서는 실패한 줄 앞에 남아 다음 poll에서 재시도한다.
- 이미 성공한 event id가 다시 오면 처리기는 실행하지 않고 커서만 같은 트랜잭션으로 전진한다.

## 변경 파일

- `server-bot/src/main/java/com/honcheon/bot/Bridge.java`
- `server-bot/src/main/java/com/honcheon/bot/Db.java`
- `tools/BridgeDeliverySelfTest.java`
- `tools/bridge_audit.py`
- `tools/bridge_audit_selftest.py`

## 검증

- `JAVA_HOME=$PWD/run/jdk-21 ./gradlew :server-bot:compileJava` - 성공
- `tools/BridgeDeliverySelfTest.java` - 실제 임시 JSONL/SQLite 눈 10/10
- `python3 tools/bridge_audit_selftest.py` - 눈 8/8
- `python3 tools/bridge_audit.py --no-backup` - 위반 0, 전달 원자성 정상
- `python3 -m compileall -q tools/bridge_audit.py tools/bridge_audit_selftest.py` - 성공
- 관련 파일 `git diff --check` - 성공

## 검토 요청

- Fable은 DB 트랜잭션 안에서 호출되는 처리기들이 별도 DB 연결이나 중첩 트랜잭션을 시작하지 않는지 확인한다.
- Discord 알림과 즉시 발행 파일은 SQLite 밖의 부수효과다. DB와 커서는 정확히 함께 처리되지만, 프로세스 장애 시 외부 알림은 중복될 수 있어 현재 보장은 at-least-once다.
- 영구적으로 잘못된 등록 이벤트는 fail-closed로 같은 줄에서 멈춘다. 운영자는 로그를 보고 설정/코드를 고쳐야 하며 자동 폐기하지 않는다.

## 단독 인수

Fable이 없어도 위 검증을 다시 실행해 현재 상태를 확인할 수 있다. 후속으로 외부 알림까지 exactly-once가 필요하면 트랜잭션 outbox를 별도 작업으로 설계한다.
