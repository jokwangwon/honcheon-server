# 혼천 공유 메모리

> Codex와 페이블이 같은 저장소에서 병행 개발할 때 사용하는 단일 진입점이다.
> 이 문서는 요약만 담는다. 작업의 진실은 `docs/BACKLOG.md`와 검증 명령에 있다.

## 읽는 순서

1. `docs/collaboration/PROTOCOL.md` - 충돌 방지와 작업 규약
2. `docs/collaboration/ACTIVE.md` - 지금 누가 무엇을 소유하는가
3. `docs/HANDOFF.md` - 시스템 헌법, 운영 상태, 과거 맥락
4. `docs/BACKLOG.md` - 기계가 검사하는 전체 작업 장부
5. 자신의 로그
   - Codex: `docs/collaboration/CODEX_LOG.md`
   - 페이블: `docs/collaboration/FABLE_LOG.md`

## 현재 공통 인식

- 제품은 Minecraft가 플레이의 몸, Discord가 인증과 소셜, 봇과 DB가 세계 장부인 공유세계 무협 RPG다.
- `config/*.yml`이 이름과 수치의 정본이다. 근거가 없는 설계값은 만들지 않고 사용자에게 묻는다.
- 봇만 SQLite를 쓴다. MVT는 `run/bridge`의 JSONL 사건과 JSON 스냅숏으로 통신한다.
- 작업 트리는 대규모 미커밋 상태다. 다른 주체가 만든 변경을 되돌리거나 정리하지 않는다.
- 우선순위는 `docs/BACKLOG.md`의 단계가 정한다. 현재 P0는 기능 구현보다 감사기의 거짓말을 먼저 고치는 단계다.
- 감사 결과만 보고 결함으로 단정하지 않는다. B-001과 B-002처럼 감사기가 리팩터를 따라가지 못한 사례가 있다.

## 세션 종료 조건

작업을 끝낸 주체는 자신의 로그에 다음을 남긴다.

- 다룬 백로그 ID와 결과
- 변경한 파일 목록
- 실행한 검증 명령과 실제 결과
- 남은 위험과 다음 주체가 할 일
- 사용자 결정이 필요하면 정확한 질문

