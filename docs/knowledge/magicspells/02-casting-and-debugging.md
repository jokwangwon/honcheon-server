# 시전 경로와 「조용한 실패」 — MagicSpells 디버깅

이 문서는 **2026-07-20 에 실제로 막혔던 것**을 끝까지 파서 남긴 기록이다.
백과사전이 아니라 **사고 조사 보고서**다. 다음에 같은 자리에서 막히면 여기부터 읽어라.

출처 표기: `wiki/<파일>` = `scratch/msdocs/wiki/` · `jar:<클래스>` = MagicSpells.jar 4.0-Beta-18 역어셈블

---

## 0. 한 줄 결론

> `ms cast as kigibot 검기_호` 는 **실패한 적이 없다.** 매번 정상 시전됐다.
> 안 보인 것은 **ArcEffect 가 target 없이는 스스로 `cancel()` 하기 때문**이고,
> `position: caster` 에는 target 이 없다. 오타(`locationOffset` 등)는 **원인이 아니었다.**

---

## 1. 시전 경로 — `ms cast` 4종

`wiki/Commands.md`

| 명령 | 뜻 | 권한 |
|---|---|---|
| `/ms cast self <spell>` (별칭 `/cast`, `/c`) | 자기가 시전 | `magicspells.command.cast.self` (전원) |
| `/ms cast as <player/UUID> <spell>` | **그 플레이어가 시전하게 만든다** (시전자 = 그 사람) | `...cast.as` (OP) |
| `/ms cast on <player/UUID> <spell>` | 그 대상**에게** 건다 (대상 = 그 사람, 시전자 = 콘솔) | `...cast.on` (OP) |
| `/ms cast at <spell> <world> <x> <y> <z> [pitch] [yaw]` | 좌표에 건다 | `...cast.at` (OP) |

- 위력 인자: **4.0 Beta 19 이상** `--power 1.5` / `-p 1.5`, **그 이전** `-p:1.5`.
  이 서버는 **Beta-18** 이므로 `-p:1.5` 형식이다.
- `ms reload` 로 스펠 재적재, `ms reloadeffectlib` 로 EffectLib 만 재적재.

### 우리 환경에서 시전하는 법

```bash
python3 scripts/kigi_rcon.py "ms cast as kigibot 검기_호"   # 테스트 25576 전용
```

---

## 2. 「조용함」은 실패가 아니다 — 실측으로 가른다

이게 이번 사고의 절반이었다. **성공하면 아무 말도 안 하고, 실패하면 오히려 말을 한다.**

RCON 실측:

| 보낸 명령 | 돌아온 것 | 뜻 |
|---|---|---|
| `ms cast as kigibot 검기_호` | *(무응답)* | ✅ **정상 시전됨** |
| `ms cast as kigibot fireball` | *(무응답)* | ✅ 기본 제공 스펠도 똑같이 조용하다 |
| `ms cast as kigibot totallyfakespell123` | `No matching spell found: 'totallyfakespell123'` | ❌ 이름이 틀리면 **말해 준다** |
| `ms cast on kigibot 검기_호` | `Error: Spell is not a targeted entity spell.` | ✅ 이름은 **정상 해석됐다** (한글 이름 문제 없음) |

**여기서 두 가지가 동시에 증명된다:**
1. 무응답 = 성공. 실패는 시끄럽다.
2. `ms cast on` 이 "targeted entity spell 이 아니다"라고 답했다는 것은
   **`검기_호` 라는 이름이 제대로 찾아졌다**는 뜻이다 → **한글 스펠 이름은 잘 동작한다.**
   (RCON 인코딩·YAML 한글 키 모두 문제 없음.)

⇒ 그러므로 "조용히 실패했다"는 처음의 진단 자체가 틀렸다. 시전은 되고 있었고,
**그려지지 않은 것은 이펙트뿐**이었다.

---

## 3. 진짜 원인 — ArcEffect 는 target 이 없으면 자살한다

`jar:shaded/effectlib/effect/ArcEffect.onRun()` 의 첫 동작:

```
getLocation()  →  origin
getTarget()    →  target
if (target == null) { cancel(); return; }   ← ★ 여기서 끝난다
if (origin == null) { cancel(); return; }
```

그리고 `position: caster` 는 위치를 **하나만** 넘긴다 —
`EffectManager.start(String, ConfigurationSection, Location)` 은 target 자리에 `null` 을 넣는다.

> **즉 `position: caster` + `ArcEffect` 는 오타를 전부 고쳐도 영원히 한 픽셀도 안 그린다.**
> 이건 설정 실수가 아니라 **구조적 불가능**이다.

### 인과 분리 실험 (짐작이 아니라 실측)

`scripts/ms_vfx_test.py` · side 6m · 초록 마스크(HUD 제외) · 대조군 포함

| 스펠 | 무엇을 시험하나 | 최대 검출 면적 |
|---|---|---|
| `시험_공백` (이펙트 없음) | **대조군** — 검출기가 거짓말하는가 | **0px** ✅ 깨끗 |
| `시험_원본` (원래 정의 그대로) | 원래 무엇이 일어났나 | **0px** — 한 프레임도 없음 |
| `시험_아크_정상옵션` (ArcEffect, **오타 0개**, `position: caster`) | 오타가 원인인가? | **0px** ← ★ **아니다** |
| `시험_원_가짜숫자` (CircleEffect + `arcLength`·`arcHeight` 얹음) | 가짜 숫자 옵션이 이펙트를 죽이나? | **3536px** ← 안 죽는다 |
| `시험_원_가짜문자` (CircleEffect + `locationOffset` 얹음) | 로그에 뜨는 오타가 죽이나? | **3655px** ← 안 죽는다 |

**결론:** 오타는 전부 무해했다. 유일한 사인은 **target 없음**이다.
(옵션을 하나도 안 틀린 ArcEffect 조차 0px 인 것이 결정적 증거다.)

---

## 4. 오타는 왜 조용한가 — 숫자 옵션은 아예 검사되지 않는다

이건 앞으로 계속 물릴 함정이라 따로 적는다.

`jar:spelleffects/effecttypes/EffectLibEffect.resolveOptions()` 의 실제 순환:

```java
for (String key : section.getKeys(false)) {
    // ★★ 값이 String 이나 섹션이 아니면 여기서 그냥 건너뛴다
    if (!section.isString(key) && !section.isConfigurationSection(key)) continue;
    String formatted = formatKey(key);
    if (CLASS_STRINGS.contains(formatted)) continue;   // class/effectClass/subEffectClass 등 5개
    try { clazz.getField(formatted); }
    catch (NoSuchFieldException e) {
        MagicSpells.error("Invalid option '" + key + "' on EffectLib effect.");
        continue;                                       // ← 죽이지 않는다. 넘어간다
    }
    ...
}
```

그래서 **오타의 운명이 값의 타입에 따라 갈린다**:

| 설정 | 값 타입 | 결과 |
|---|---|---|
| `locationOffset: 0,1.1,1.2` | String | 🔊 `Invalid option 'locationOffset'` **로그에 뜬다** |
| `arcLength: 3.2` | 숫자 | 🔇 **완전 무음** — 경고도 없고 적용도 안 된다 |
| `arcHeight: 1.1` | 숫자 | 🔇 완전 무음 |
| `zRotation: 75` | 숫자 | 🔇 완전 무음 (ArcEffect 엔 없는 필드) |

실측 확인: 서버 로그 전체에서 `Invalid option` 은 **`locationOffset` 하나뿐**이었다.
`arcLength`·`arcHeight`·`zRotation` 은 한 번도 언급되지 않았다.
(`Error assigning EffectLib property` 라는 메시지는 이 판본 로그에 **한 번도 등장하지 않았다** —
찾지 마라.)

> **교훈: EffectLib 옵션 이름을 지어내지 마라.** 숫자 옵션은 틀려도 아무도 안 알려 준다.
> 반드시 `javap -p` 로 **그 클래스의 public 필드**를 먼저 확인하라 (→ `04-effectlib.md`).

같은 함정이 MagicSpells 자체 옵션에도 있다:
원래 정의의 `horizontal-spread` / `vertical-spread` 는 **가짜 이름**이다.
진짜는 `horiz-spread` / `vert-spread` (`wiki/Particles-Effect.md`). 이것도 조용히 무시된다.

### 이름 정규화 (알아두면 헷갈릴 일이 준다)
`formatKey()` 는 `-` → `_` 로 바꾼 뒤 `LOWER_UNDERSCORE` → `LOWER_CAMEL` 로 변환한다.
따라서 `radius-grow` · `radius_grow` · `radiusGrow` 는 **모두 같은 것**이다.
다만 EffectLib 블록 안에서는 **camelCase 를 정본으로 쓴다** (공식 문서가 그렇게 쓴다).

---

## 5. 두 층의 옵션을 헷갈리지 마라

한 이펙트 안에 **표기법이 다른 두 층**이 겹쳐 있다. 이게 오타의 온상이다.

```yaml
effects:
    참격:
        position: caster          # ← MagicSpells SpellEffect 층
        effect: effectlib         #    kebab-case
        relative-offset: 1,1.1,0  #    ★ kebab
        delay: 0
        effectlib:                # ← EffectLib 층 (이 아래는 다른 세계)
            class: CircleEffect   #    camelCase
            relativeOffset: 1,-0.2,0  # ★ camel — 위의 것과 **다른 옵션이다**
            maxAngle: 2.4
```

| 층 | 표기 | 검증 | 문서 |
|---|---|---|---|
| MagicSpells `SpellEffect` | `kebab-case` | 대체로 조용히 무시 | `wiki/Spell-Effects.md` |
| EffectLib (`effectlib:` 아래) | `camelCase` | 문자열만 검사 (§4) | `jar` 의 public 필드 |

---

## 6. ArcEffect 를 **제대로** 쓰려면

target 을 주는 경로는 하나뿐이다: `effect: effectlibline` + `position: line`.
`jar:effecttypes/EffectLibLineEffect` 만이 두 좌표를 받는 `playEffect(Location, Location, SpellData)` 를
구현한다. `position: line` 은 시전자→표적 사이를 잇는 자리이므로 **표적이 있는 스펠**이어야 한다.

```yaml
검기_포물:
    spell-class: ".targeted.DummySpell"   # ← 표적이 있어야 한다
    range: 20
    effects:
        아크:
            position: line
            effect: effectlibline          # ← effectlib 이 아니다
            effectlib:
                class: ArcEffect
                particle: happy_villager
                particles: 60
                height: 2                  # ★ ArcEffect 의 옵션은 height·particles 둘뿐
```

**다만 용도를 착각하지 마라.** ArcEffect 는 두 점 사이의 **포물선**이다
(`v = target − origin`, 계수 `4·height/length²`). "날아가는 기"에는 맞지만
**칼이 쓸고 간 자국(초승달)에는 맞지 않는다.**
칼자국의 올바른 원시도형은 **`CircleEffect` 의 부분 호**다 (`maxAngle` + `wholeCircle: false`).

---

## 7. 실제로 작동하는 최소 검기

정본: `run/mvt-test/plugins/MagicSpells/spells-honcheon.yml` 의 `검기_호`

```yaml
검기_호:
    spell-class: ".instant.DummySpell"
    cooldown: 0
    effects:
        참격:
            position: caster
            effect: effectlib
            effectlib:
                class: CircleEffect
                particle: happy_villager
                particles: 44
                radius: 1.4
                maxAngle: 2.4          # 라디안 ≈ 137°
                wholeCircle: false     # ← 이게 있어야 온 원이 아니라 초승달
                zRotation: 1.5708      # 가로 원반을 세워 세로 베기 평면으로
                iterations: 20         # 20틱에 걸쳐 자란다
                period: 1
                relativeOffset: 1.0,-0.2,0
```

### 배치에서 물린 것
`zRotation: 90°` 면 호가 **원점 바로 위에서 시작해** 아래로 쓸어내린다.
그래서 처음에 `radius: 2.4` + `relativeOffset y: 1.15` 로 뒀더니
호가 **3.5m 상공**에 떴다 (실제 촬영으로 확인). 중심을 `y: -0.2` 까지 내려야
칼끝 높이에 걸린다. → **면적만 보지 말고 반드시 중심 좌표(cx,cy)도 봐라.**
`ms_vfx_test.py` 가 중심을 같이 찍는 이유가 이것이다.

---

## 8. 각도별 실측 — 그리고 기대가 빗나간 곳

`scripts/ms_vfx_test.py --angle {side,back,front,high}` · 6m · 1280×720 · 대조군 전부 0px

| 각도 | MagicSpells `검기_호` | 우리 자체 검기 (ItemDisplay) |
|---|---|---|
| front | **4144px** | 383px |
| side | **1931px** | 1761px |
| high | 428px | 495px |
| back | **45~56px** | 47px |

`specs/검기_평타.yml` 의 우리 실측과 나란히 놓은 것이다.

**얻은 것:** 정면이 **10배** 나아졌다 (383 → 4144). 납작한 판이 각도에 따라
옆면이 되어 사라지던 문제는 파티클로 옮기면서 **실제로 사라졌다.**

**❗ 얻지 못한 것 — 처음 가설은 틀렸다:**
> "EffectLib 은 파티클로 그리니 판이 아니고, 따라서 **어느 각도에서도 보인다**"

**back 은 45px 로, 우리 자체 구현(47px)과 사실상 똑같다.**
파티클로 바꿔도 등 뒤에서는 여전히 안 보인다.

까닭은 판이 아니라 **가림(occlusion)** 이다 — 호를 `relativeOffset` 으로 **몸 앞에** 두었으니
등 뒤에서 보면 **몸이 가린다.** 이건 파티클이든 판이든 똑같다.
즉 뒤 각도 문제는 「납작한 판」 탓이 아니라 **「이펙트를 몸 앞에 둔 배치」** 탓이었다.
고치려면 원시도형을 바꿀 게 아니라 **호를 몸 둘레로 돌려야** 한다
(`wholeCircle: true` 로 몸을 감싸거나, `relativeOffset` 의 전방 성분을 줄이거나).

⇒ 우리 자체 구현의 `orbit_radius`(공전) 접근이 방향은 맞았다는 뜻이기도 하다.

---

## 9. 측정하다 물린 함정 (하네스 이야기)

이 숫자들을 얻기까지 **네 판이 통째로 거짓이었다.** 전부 「조용히 0 또는 조용히 큰 수」였다.

| 사고 | 증상 | 어떻게 알아챘나 | 막은 방법 |
|---|---|---|---|
| 촬영 간격이 짧아 앞 스펠 파티클이 남음 | 안 그려지는 `시험_원본` 이 **644px** | 대조군을 끼워 넣음 | `--gap 2.0` + `시험_공백` 대조군 |
| 서버가 중간에 재기동 → 두 봇 다 튕김 | RCON `Connection refused` | 예외 | `ensure_server`/`ensure_client` 를 스크립트에 넣음 |
| 클라이언트 중복 기동 | 화면에 `Connection Lost — You logged in from another location`, 배경 **나무**를 초록으로 셈 | **프레임을 눈으로 봤다** | 남은 클라 전부 정리 후 정확히 2개만 기동 |
| 봇이 무대를 벗어나 지하 Y=-60 으로 | 흰 바닥만 찍히고 **조용히 0px** | 프레임을 눈으로 봄 + 좌표 조회 | `ensure_on_stage()` — 어긋나면 되돌리고, 안 되면 **측정 거부** |

> **이 저장소의 원칙 그대로다: 숫자만 보지 마라. 프레임을 봐라.**
> 그리고 **대조군 없는 측정은 측정이 아니다.** 위 표의 사고 중 둘은
> 대조군(`시험_공백`)이 0 이 아닌 것을 보고서야 잡혔다.

---

## 10. 다음에 안 보일 때 밟을 순서

1. `ms cast as ... <스펠>` 이 **말을 하는가?** 말하면 이름/문법 문제다. 조용하면 **시전은 됐다.**
2. 서버 로그에서 `Invalid option` 을 찾아라 — 뜨면 **문자열 옵션** 오타다.
3. 안 떠도 안심하지 마라. **숫자 옵션 오타는 무음이다.** `javap -p` 로 필드를 대조하라.
4. 쓰는 EffectLib 클래스가 **target 을 요구하는가?** (`onRun` 이 `getTarget()` 을 보는가)
   요구하면 `position: caster` 로는 절대 안 된다 → `effectlibline` + `position: line`.
5. 그래도 안 보이면 **잴 대상이 화면에 있는지부터 의심하라** (§9).

---

## 참고 — 이 조사에 쓴 도구

```bash
# 스펠의 시각효과를 실제로 재기 (대조군·무대 검사 포함)
python3 scripts/ms_vfx_test.py --spells 시험_공백,검기_호 --angle side

# jar 에서 진짜 옵션 뽑기 (문서보다 이쪽이 정본)
run/jdk-21/bin/javap -p com/nisovin/magicspells/shaded/effectlib/effect/CircleEffect.class

# 문서 검색
python3 scripts/ms_ask.py "maxAngle"
```
