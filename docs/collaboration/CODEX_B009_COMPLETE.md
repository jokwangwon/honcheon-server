# Codex 완료 스냅숏: B-009

## 결과

- `bandit_camp_cleared`와 `bandit_boss_succeeded`가 `Bridge.apply`에서 처리된다.
- 공통 처리기는 `world_bridge.yml`의 `effects.region_event` 이름만 읽는다.
- 사건은 append-only 이벤트 장부에 남고, 지역 델타는 `RegionService.applyEvent`가 `region_state.yml`에서 계산한다.
- 코드에 치안·민심 수치를 새로 하드코딩하지 않았다.

## 변경 파일

- `server-bot/src/main/java/com/honcheon/bot/Bridge.java`
- `tools/bridge_audit.py`
- `tools/bridge_audit_selftest.py`

## 검증

```bash
python3 tools/bridge_audit_selftest.py
# 6/6 통과. 두 handler 제거 주입을 탐지한다.

python3 tools/bridge_audit.py --no-backup
# 위반 0건, 경고 3건, exit 0.

JAVA_HOME=$PWD/run/jdk-21 ./gradlew :server-bot:compileJava
# BUILD SUCCESSFUL

python3 -m compileall -q tools/bridge_audit.py tools/bridge_audit_selftest.py
# 통과
```

## 남은 경고

- 두 세계 사건은 아직 실제 발생 건수가 0이므로 DB 행과 지역 수치 변화는 인게임에서 관측되지 않았다.
- `seojang_choice` 발신 손잡이 호출자 없음, 접합 전 사건의 무주체 비율, 혈채 실데이터 부재는 별도 경고다.
- `Bridge.apply`가 handler 전에 event claim을 하고 handler 실패에도 커서를 전진시키는 잠재 위험은 별도 발견으로 분리했다.

## 상대 검토 요청

- 사건 로그 actor를 `world/kind`, target을 `place/zone`으로 둔 것이 기존 이벤트 조회 계약과 맞는지 확인한다.
- `region_event`가 비었을 때 조용히 넘기지 않고 실패하는 정책이 헌법과 맞는지 확인한다.
- 실제 이벤트 1건을 주입하는 통합 테스트가 필요한지 조언한다.

## 인계 지점

정적 구현과 컴파일 검증은 완료됐다. 서버/RCON은 실행하지 않았다. 실제 소탕 또는 승계가 발생한 뒤 `events`, `regions`, `bridge_inbox`를 대조하면 인게임 검증을 이어갈 수 있다.

