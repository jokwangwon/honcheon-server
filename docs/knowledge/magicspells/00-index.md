# MagicSpells 지식베이스 — 색인

혼천 작업에 쓰려고 만든 **MagicSpells 4.0-Beta-18** 참고서.
백과사전이 아니다 — **우리가 실제로 쓸 것**만 골라 담았고, 각 주장에 출처를 남겼다.

## 먼저 이렇게 물어라

```bash
python3 scripts/ms_ask.py "maxAngle"
python3 scripts/ms_ask.py "ArcEffect 파라미터" -n 3
python3 scripts/ms_ask.py "ms cast as console"
```

BM25 어휘 검색이다. 위키 558문서 + 아래 정리본을 함께 뒤지고 **출처(파일 + 제목 경로)** 를 같이 낸다.
한글·영문 질의 모두 된다. 색인은 `scratch/ms-index/` 에 캐시되고 원본이 바뀌면 자동 재구축된다.

> **왜 임베딩이 아니라 BM25 인가:** 여기서 찾는 것은 대부분 **정확한 식별자**다 —
> `maxAngle`, `relativeOffset`, `spell-class`, `.instant.DummySpell`.
> 이런 건 의미 검색보다 어휘 검색이 **더 정확하다.** 모델 내려받기·API 키·GPU 가 전부 필요 없다.

## 문서

| 문서 | 무엇이 있나 | 언제 읽나 |
|---|---|---|
| **[02-casting-and-debugging.md](02-casting-and-debugging.md)** | 시전 경로(`ms cast as/on/at`) · **「조용한 실패」의 정체** · 사고 조사 · 각도별 실측 | **이펙트가 안 보일 때 여기부터** |
| [01-spell-syntax.md](01-spell-syntax.md) | 스펠 정의 문법 · 공통 옵션 · 4계열 193클래스 목록 · 최소 예제 | 새 스펠을 쓸 때 |
| [03-spell-effects.md](03-spell-effects.md) | `effects:` 절 · **position 전체표**(target 필요 여부 포함) · 이펙트 36종 필드 | 연출을 붙일 때 |
| [04-effectlib.md](04-effectlib.md) | EffectLib 48클래스의 **실제 public 필드**(jar 에서 추출) · 공통 옵션 · 위키 오류 목록 | 기하 이펙트를 만들 때 |
| [05-honcheon-comparison.md](05-honcheon-comparison.md) | 우리 무공 시스템 ↔ MagicSpells 대조 · **우리가 놓친 개념** · 권고 | 설계를 정할 때 |

## 세 줄 요약 (이번에 막혔던 것)

1. **`ms cast as` 는 성공하면 아무 말도 안 한다.** 조용함은 실패가 아니다 —
   실패하면 오히려 `No matching spell found` 라고 **말해 준다.** 한글 스펠 이름도 잘 된다.
2. **`ArcEffect` 는 target 이 없으면 스스로 `cancel()` 한다.** `position: caster` 에는 target 이 없다.
   ⇒ 이 조합은 오타를 다 고쳐도 **영원히 안 그려진다.** 칼자국에는 `CircleEffect` 의 부분 호를 써라.
3. **EffectLib 의 숫자 옵션 오타는 완전 무음이다.** (문자열 오타만 로그에 뜬다.)
   옵션 이름을 지어내지 말고 `javap -p` 로 그 클래스의 public 필드를 확인하라.

## 소스와 그 신뢰 순서

| 순위 | 소스 | 위치 |
|---|---|---|
| 1 | **jar 의 클래스 필드** — 가장 정확한 진실 | `run/mvt-test/plugins/MagicSpells.jar` · `run/jdk-21/bin/javap -p <클래스>` |
| 2 | **인게임 실측** — 문서와 어긋나면 이쪽이 이긴다 | `scripts/ms_vfx_test.py` |
| 3 | 공식 위키 558문서 | `scratch/msdocs/wiki/*.md` (gitignore 됨) |
| 4 | 기본 설정 실물 | `run/mvt-test/plugins/MagicSpells/*.yml` |
| ✗ | `niblexis/ms-examples` (2020년) | **정본 취급 금지** — 4.0 문법과 다르다 |

위키가 틀린 사례를 실제로 여럿 찾았다 (`04-effectlib.md` 의 「위키 오류」 절).
**문서와 jar 가 다르면 jar 가 맞다.**

## 도구

```bash
# 문서 검색
python3 scripts/ms_ask.py "<질문>" [-n 5] [--rebuild]

# 스펠의 시각효과를 실제로 재기 (대조군 + 무대 검사 포함)
python3 scripts/ms_vfx_test.py --spells 시험_공백,검기_호 --angle side

# 테스트 서버 콘솔 (25576 전용 — 라이브 25575 는 거부된다)
python3 scripts/kigi_rcon.py "ms reload"

# jar 에서 진짜 옵션 뽑기
run/jdk-21/bin/javap -p com/nisovin/magicspells/shaded/effectlib/effect/CircleEffect.class
```

## 경계

- MagicSpells 는 **테스트 서버 전용**이다 (25566/25576 · `run/mvt-test`).
  라이브(25565 · `run/mvt` · 저장소 `config/`)에는 **없고, 올리지 않는다.**
- 우리 자체 무공 시스템은 **지우지 않는다.** 견줄 대상으로 나란히 둔다.
- 우리 검기 정의: `run/mvt-test/plugins/MagicSpells/spells-honcheon.yml` (실험대는 `-lab.yml`)
