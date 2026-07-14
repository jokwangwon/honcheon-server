# Codex 활성 작업: B-009

## 2026-07-14 - 진행

- 목적: `bandit_camp_cleared`, `bandit_boss_succeeded`가 봇 장부와 지역 상태에 도달하게 한다.
- 소유 파일: `server-bot/src/main/java/com/honcheon/bot/Bridge.java`, `tools/bridge_audit.py`, `tools/bridge_audit_selftest.py`
- 확인한 사실: MVT 발신과 `world_bridge.yml` 등록은 존재하지만 `Bridge.apply` case가 없다.
- 확인한 사실: 등록부는 각각 `도적_부분_소탕`, `도적_두목_승계`라는 `region_event`를 지정한다.
- 설계: 공통 처리기가 등록부의 사건 이름을 기록하고 `RegionService.applyEvent`에 넘긴다. 코드에서 델타를 만들지 않는다.
- 검증 계획: bridge self-test에 두 handler 존재와 제거 주입을 추가하고, 실제 `bridge_audit` 위반 0건 및 Java 21 빌드를 확인한다.
- 인계 지점: 아직 B-009 프로덕션 수정 전이다. `Bridge.apply` switch와 `slain` 인접 영역을 보면 된다.

