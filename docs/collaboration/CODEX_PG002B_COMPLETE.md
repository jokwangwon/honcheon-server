# Codex 완료 - PG-002B 브리지 저장소 포트

## 결과

- `BridgeStore`가 브리지 inbox·커서·신원·세계 상태 업무 계약을 소유한다.
- `Bridge`는 구체 `Db`를 더 이상 참조하지 않는다.
- `Db.LinkRequest`, `Db.Debt`, `Db.BridgeWork` 결합을 각각 `LinkRequest`, `BloodDebtEntry`, `BridgeStore.Work`로 옮겼다.
- 현재 SQLite `Db`가 `BridgeStore`를 구현하며 SQL과 트랜잭션 경계는 바꾸지 않았다.
- inbox 선점, 세계 변경, JSONL 커서는 계속 하나의 `applyBridgeEvent` 트랜잭션으로 처리된다.

## 결합도 변화

- 실제 직접 `Db` 소비자: 3개 -> 2개
- 남은 소비자: composition root `HoncheonBot`, `GameListener`
- 새 포트: `BridgeStore`
- 이번 단계는 저장소 계약 분리이며 PostgreSQL 드라이버나 SQL 방언은 아직 도입하지 않았다.

## 검증

- `JAVA_HOME=$PWD/run/jdk-21 ./gradlew :server-bot:compileJava` - 성공
- `JAVA_HOME=$PWD/run/jdk-21 ./gradlew :server-bot:jar` - 성공
- `python3 tools/persistence_port_audit.py` - 10축 통과
- `python3 tools/persistence_port_audit_selftest.py` - 4/4
- `tools/BridgeDeliverySelfTest.java` - 실제 SQLite 10/10
- `python3 tools/bridge_audit_selftest.py` - 8/8
- `python3 tools/bridge_audit.py --no-backup` - 전달 원자성 통과
- Python compileall - 성공

## 다음 작업 PG-002C

`GameListener`의 직접 `Db` 사용을 업무별 저장소 포트로 분해한다. 전체 메서드를 한 거대 인터페이스로 옮기지 않고 캐릭터, 접합, 사건·혈채 등 실제 호출 묶음별 계약을 만든다. 완료 후 런타임 조립점인 `HoncheonBot`만 구체 `Db`를 소유해야 한다.
