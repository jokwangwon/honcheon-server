# Fable 완료 - SJ-001 폭 기반 BookLayout 조판 엔진

> 2026-07-14 · 병렬 R1 트랙 무 · 청사진: `docs/design/seojang_presentation.md` §SJ-001 (Codex 설계)
> 소유 파일: `server-mvt/src/main/java/com/honcheon/mvt/BookLayout.java`(신설) ·
> `SeojangBook.java`(조판 호출부) · `tools/BookLayoutSelfTest.java`(신설)

## 결과

- 130자 분할이 죽고 **폭 기반 조판**이 섰다: 줄 폭 114px · 쪽 14줄, 한글 9px 균일 ·
  ASCII 폭 표 · 굵은 글씨 +1px (상수마다 출처 주석 — 바닐라 BookViewScreen/유니폰트 매핑).
  비ASCII 는 전각 과대추정 — 잘림보다 낭비가 안전하다.
- 쪽 규칙: 첫 쪽은 머리말(장 번호·제목·여백) 줄 수를 **같은 자로 재서** 뺀 용량 ·
  가운데 쪽 본문만 · 문장 가운데 잘림 방지 우선(안 들어가면 통째로 다음 쪽) · 빈 쪽 금지 ·
  Random·시계 없음(결정론).
- **행동 계약 불변**: 클릭 커맨드·토큰 문법·선택 쪽 구조·간기(폴백 표식)는 한 글자도 안 바꿈 —
  봇 권위 경계(청사진 §2.2) 준수.

## 검증 (Fable 재실행)

- 컴파일 exit 0 · `BookLayoutSelfTest` **35눈 전부 통과** (자 검증 · 빈 본문 · 결정론 ·
  경계 길이 168/169자 · 초장문 무손실 · 머리말 0/3/6/14줄 · 선택 쪽 보존 · 맨글 클릭 생존)
- 실행법: `run/jdk-21/bin/javac -cp server-mvt/build/classes/java/main -d /tmp/sj001 tools/BookLayoutSelfTest.java && run/jdk-21/bin/java -cp /tmp/sj001:server-mvt/build/classes/java/main com.honcheon.mvt.BookLayoutSelfTest`

## 남은 위험 · SJ-002 로 넘길 것

1. **114px·14줄·9px 는 서버에서 실측 불가** (클라이언트 전용 코드) — 모장 매핑+통설 근거.
   인게임 확정은 SJ-002 실기동 눈(GUI 배율 2·3·4)에서 한다. 청사진 §6 그대로.
2. **선택 쪽 자체의 넘침** — 선택지 5개+ 또는 긴 라벨이면 1쪽을 넘을 수 있다 (선택지 수는
   봇 권위). SJ-003 때 선택 쪽 다중화 검토.
3. 하이픈 줄바꿈(구형 FontRenderer 차이)은 실기동 fixture 하나로 확인 권장.
4. 청사진의 `seojang_presentation_audit.py` 는 SJ-002~003 산출물로 남아 있다.
