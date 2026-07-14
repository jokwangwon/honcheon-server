# Codex 완료 - PG-002C 게임 원장 업무 포트

## 결과

- `GameListener`의 구체 `Db` 의존을 `GameStore` 조합 포트로 교체했다.
- 조합 포트는 캐릭터, 가문, 사건, 정치·소문, 접합·혈채, 세계 상태의 여섯 업무 포트로 나뉜다.
- `Db.House`, `Db.Issue`, `Db.Arrival`을 `HouseEntry`, `MyeongbunIssue`, `RumorArrival`로 분리했다.
- 현재 SQLite `Db`가 모든 업무 포트를 구현하며 기존 SQL과 트랜잭션 동작은 바꾸지 않았다.
- 구체 `Db`를 아는 파일은 런타임 조립점 `HoncheonBot` 하나뿐이다.

## 결합도 변화

- 실제 직접 `Db` 소비자: 2개 -> 1개
- 남은 소비자: composition root `HoncheonBot`
- 새 포트: `GameCharacterStore`, `HouseStore`, `EventStore`, `PoliticsStore`, `IdentityStore`, `WorldStore`, `GameStore`
- 새 독립 DTO: `HouseEntry`, `MyeongbunIssue`, `RumorArrival`

## 검증

- `JAVA_HOME=$PWD/run/jdk-21 ./gradlew :server-bot:compileJava` - 성공
- `JAVA_HOME=$PWD/run/jdk-21 ./gradlew :server-bot:jar` - 성공
- `python3 tools/persistence_port_audit.py` - 15축 통과
- `python3 tools/persistence_port_audit_selftest.py` - 5/5
- `python3 tools/persistence_inventory_selftest.py` - 9/9
- `tools/HouseKinSelfTest.java`, `tools/SiblingTimeSelfTest.java` - 성공
- `tools/ResetSelfTest.java` - 실제 SQLite 41/41
- `tools/BridgeDeliverySelfTest.java` - 실제 SQLite 10/10
- 브리지 감사 및 자체시험 8/8 - 성공
- Python compileall - 성공

## 다음 작업 PG-003

업무 포트별 트랜잭션 경계를 계약으로 올리고 SQLite 전용 SQL 방언을 저장소 구현 내부의 명시적 경계로 격리한다. PostgreSQL 구현은 아직 만들지 않으며, 먼저 동일한 포트 계약 시험이 SQLite에서 재현되게 한다.
