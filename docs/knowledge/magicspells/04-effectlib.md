# 04. EffectLib (MagicSpells 셰이드판) — 실측 레퍼런스

**측정 대상**: MagicSpells `4.0-Beta-18` (`/tmp/msx/plugin.yml`).
EffectLib 은 `com.nisovin.magicspells.shaded.effectlib` 로 셰이드되어 들어 있다.

**측정 방법**: 모든 필드 목록은 위키가 아니라 jar 에서 직접 뽑았다.

```bash
javap -p /tmp/msx/com/nisovin/magicspells/shaded/effectlib/Effect.class
javap -p /tmp/msx/com/nisovin/magicspells/shaded/effectlib/effect/ArcEffect.class
```

(`javap` 는 `run/jdk-21/bin/javap`. 위키는 설명·기본값 출처로만 쓰고, **필드의 존재 여부는 jar 이 정본**이다.)

---

## 0. 왜 public 필드가 곧 YAML 옵션인가

두 로더가 모두 **`Class.getField(...)`** 로 필드를 찾는다. `getField` 는 **public 필드만** 반환한다
(상속된 public 도 포함, `protected`/`private` 는 절대 안 잡힘).

| 로더 | 위치 | 하는 일 |
|:---|:---|:---|
| MagicSpells `EffectLibEffect.resolveOptions` | `/tmp/msx/com/nisovin/magicspells/spelleffects/effecttypes/EffectLibEffect.class` | 값이 **String 이거나 ConfigurationSection 인 키만** 훑어서 표현식(`ConfigData`)으로 미리 해석 |
| EffectLib `EffectManager.setField` | `/tmp/msx/com/nisovin/magicspells/shaded/effectlib/EffectManager.class` | 실제 인스턴스에 값을 꽂음 |

### 키 이름 정규화 (실측)

두 로더 모두 같은 정규화를 한다 (`EffectLibEffect.formatKey`, `EffectManager` 바이트코드 offset 56~97):

1. `-` → `_` 로 치환
2. `_` 가 있으면 Guava `CaseFormat.LOWER_UNDERSCORE → LOWER_CAMEL`

즉 **`radiusGrow`, `radius_grow`, `radius-grow` 셋 다 같은 필드에 꽂힌다.** 정본은 camelCase 로 쓰되,
케밥으로 써도 안 깨진다는 뜻이다.

### 필드가 아닌데 유효한 키 (`CLASS_STRINGS`)

`EffectLibEffect` 의 static initializer 에서 확인:

```
class, effectClass, subEffectClass, subEffectAtEndClass, subEffectAtEndCachedClass
```

이 5개는 public 필드가 없어도 통과한다 (클래스 선택용). 그 외 필드 없는 키는 전부 아래의 실패 경로를 탄다.

---

## 1. 실패 모드 — 존재하지 않는 옵션을 쓰면

**핵심: 오타 옵션 하나면 그 EffectLib 이펙트는 통째로 안 나온다.** 다만 로그가 두 군데서 다르게 찍힌다.

### (a) MagicSpells 쪽 (String / ConfigurationSection 값일 때만)

`EffectLibEffect.resolveOptions` 바이트코드 89~117:

```
89:  Class.getField(formattedKey)
     ↓ NoSuchFieldException
104: MagicSpells.error("Invalid option '<key>' on EffectLib effect.")
114: e.printStackTrace()
117: goto 18          ← continue. 여기서는 안 죽는다
```

로그 문구 (jar 문자열 상수 실측): `Invalid option '<key>' on EffectLib effect.` + 스택트레이스.

### (b) EffectLib 쪽 — ❗바이트코드만 보고 낸 예상이 **실측에서 틀렸다**

`EffectManager.getEffect` 바이트코드 230~237 에는 이런 경로가 있다:

```
230: setField(...)  → boolean
233: ifne 238
236: aconst_null
237: areturn        ← setField 가 false 면 이펙트 자체를 null 로 반환
```

여기만 보면 "잘못된 옵션 하나가 이펙트를 통째로 죽인다"로 읽힌다.
**그런데 인게임 실측은 그렇지 않았다.**

> **실측 (2026-07-20 · `scripts/ms_vfx_test.py`, 대조군 0px 확인):**
> | 스펠 | 최대 검출 면적 |
> |---|---|
> | 정상 `CircleEffect` | 3000px 안팎 |
> | 같은 것 + 가짜 **숫자** 옵션(`arcLength`·`arcHeight`) | **3536px** — 멀쩡하다 |
> | 같은 것 + 가짜 **문자열** 옵션(`locationOffset`) | **3655px** — 멀쩡하다 |
>
> 그리고 서버 로그 전체에서 `Error assigning EffectLib property` 는
> **단 한 번도 출력되지 않았다.**

**정정된 결론**: 잘못된 옵션은 **이펙트를 죽이지 않는다.** 그냥 무시된다.
위 `aconst_null` 경로는 이 판본에서 우리가 쓰는 설정 경로로는 도달하지 않는 것으로 보인다.
(정확한 도달 조건은 미확인 — 확인하기 전까지 "옵션 오타 = 이펙트 사망"이라고 쓰지 마라.)

⇒ 실무적 함의는 오히려 **더 나쁘다**: 오타는 경고도 없고 벌도 없이 **그냥 안 먹는다.**
값이 안 먹는데 이펙트는 나오므로 "왜 이 값만 무시되지?"로 한참 헤매게 된다.
**로그를 믿지 말고 `javap -p` 로 필드를 대조하라.**

grep 은 `Invalid option '.*' on EffectLib effect` 하나만 실효가 있다
(문자열 값 오타만 잡힌다. 숫자 오타는 어디에도 안 찍힌다).

전체 사고 조사는 → `02-casting-and-debugging.md` §4

---

## 2. ArcEffect — 오해가 잦은 지점 (반드시 읽을 것)

`javap -p /tmp/msx/.../effect/ArcEffect.class` 실측 결과, **클래스 고유 public 필드는 딱 둘이다**:

| 필드 | 타입 | 기본값 (wiki `EffectLib-Arc.md`) |
|:---|:---|:---|
| `height` | `float` | `2` |
| `particles` | `int` | `100` |

(`protected int step` 은 내부 상태라 YAML 로 못 건드린다.)

### 존재하지 않는 옵션 — 쓰면 이펙트가 안 나온다

```
arcLength   arcHeight   xRotation   zRotation   locationOffset
```

**이 다섯은 ArcEffect 에 없다.** 위 §1 경로를 그대로 타서
`Invalid option 'arcLength' on EffectLib effect.` 가 찍히고 이펙트는 로드 실패한다.
(`xRotation`/`zRotation` 은 `CircleEffect`·`HeartEffect`·`DonutEffect`·`HelixEffect` 등 **다른** 클래스에는 실재한다.
그래서 예제를 잘못 옮겨 붙이면 이 함정에 빠진다.)

### 실제 기하 (`ArcEffect.onRun` 바이트코드)

```
getLocation() / getTarget()  → 둘 중 null 이거나 월드가 다르면 cancel()
v = target.toVector() - origin.toVector()
length = v.length()
계수 = 4 * height / length^2          (Math.pow(length, 2))
step 을 0..particles 로 훑으며:
    p = origin + v.normalize() * (length * i / particles)
    p.y += 계수 * (i*length/particles) * (length - i*length/particles) 꼴의 포물선 항
    display(particle, p)
```

즉 **origin → target 을 잇는 포물선**이며, `height` 는 중점에서의 최대 융기 높이(블록).
**타겟이 없으면 즉시 `cancel()`** 된다 — 스펠 이펙트 쪽에서 `position` 이 타겟 로케이션을 주는지 반드시 확인하라.
Arc 는 "방향을 내가 정하는 호"가 아니라 "두 점을 잇는 호"다. 검기처럼 **방향만 주고 호를 그리고 싶으면
Arc 가 아니라 `Equation` / `Circle(maxAngle)` 를 써야 한다** (§5 참조).

---

## 3. 공통 옵션 (base `Effect` 클래스의 public 필드)

출처: `javap -p /tmp/msx/com/nisovin/magicspells/shaded/effectlib/Effect.class`
설명·기본값 출처: `wiki/Effectlib-Effect.md`

> **camelCase 이고 `effectlib:` 블록 안에 넣는다.**
> MagicSpells 자신의 SpellEffect 옵션(`position`, `relative-offset`, `delay`, `modifiers` …)은 **케밥케이스**이며
> `effectlib:` 보다 **한 단계 위**에 온다. 이름이 겹치는 짝이 있으니 층을 헷갈리면 안 된다:
>
> ```yml
> Effect1:
>   position: caster          # ← MagicSpells SpellEffect 층 (kebab-case)
>   relative-offset: 1,1,0    # ← MagicSpells 가 이펙트 재생 위치를 옮김
>   effect: effectlib
>   effectlib:                # ← 여기서부터 EffectLib 층 (camelCase)
>     class: ArcEffect
>     relativeOffset: 0,0,2   # ← EffectLib 이 자기 origin 을 추가로 옮김
>     height: 2
>     particles: 100
> ```

### 3.1 자주 쓰는 것

| 옵션 | 타입 | 기본값 | 설명 |
|:---|:---|:---|:---|
| `class` | String | — | 필드 아님(`CLASS_STRINGS`). 이펙트 클래스 선택 |
| `type` | `EffectType` | 클래스마다 다름 | `instant` / `repeating` / `delayed` (enum 실측: 이 셋뿐) |
| `period` | int | `1` | 반복 간 틱 간격 |
| `iterations` | int | `0` | 반복 횟수 |
| `particle` | `Particle` | 클래스마다 다름 | 파티클 종류 |
| `speed` | float | `0` | 파티클 속도 |
| `delay` | int | `0` | 재생 지연 (틱) |
| `asynchronous` | boolean | `true` | `TurnEffect`·`JumpEffect` 는 비동기 미지원 |
| `particleCount` | int | `1` | 한 지점당 파티클 수. **`-1` 이면 파티클 끔** (subEffect 전용일 때) |
| `visibleRange` | float | `32` | 이 반경 안의 플레이어에게만 보임 |

### 3.2 위치·방향

| 옵션 | 타입 | 기본값 | 설명 |
|:---|:---|:---|:---|
| `offset` | `Vector` | `0,0,0` | 절대 오프셋 (시선 무관) |
| `relativeOffset` | `Vector` | `0,0,0` | 시선 기준 상대 오프셋 |
| `targetOffset` | `Vector` | `0,0,0` | 타겟 로케이션 오프셋 |
| `yaw` / `pitch` | Float | — | origin 방향을 **절대값으로 덮어씀** |
| `yawOffset` / `pitchOffset` | float | `0` | origin 방향에 **더함** |
| `autoOrient` | boolean | `false` | origin/target 이 서로를 바라보게 함 |
| `updateLocations` | boolean | `true` | 재생 중 로케이션 갱신 |
| `updateDirections` | boolean | `true` | 엔티티 방향 갱신 |
| `disappearWithOriginEntity` | boolean | `false` | origin 엔티티 무효화 시 중단 |
| `disappearWithTargetEntity` | boolean | `false` | target 엔티티 무효화 시 중단 |

### 3.3 서브이펙트 / 지속

| 옵션 | 타입 | 설명 |
|:---|:---|:---|
| `subEffect` | `ConfigurationSection` | 파티클 위치마다 재생되는 하위 이펙트. 안에서는 `class` 대신 `subEffectClass` |
| `duration` | Integer (ms) | 설정 시 `iterations` 를 역산해 지속시간을 맞춤 |
| `probability` | double | `0`~`1`, 반복마다 재생 확률 |
| `particleData` | float | `speed` 의 구식 전신. 아직 동작 |

### 3.4 파티클 종류별 옵션

| 옵션 | 타입 | 대상 파티클 |
|:---|:---|:---|
| `color` / `colors` | `Color` / String | `entity_effect`, `dust`, `dust_color_transition`, `flash`, `tinted_leaves`, `trail`, `effect`… |
| `toColor` / `toColors` | `Color` / String | `dust_color_transition` |
| `particleSize` | float (기본 `1`) | `dust`, `dust_color_transition` |
| `material` / `materialData` | `Material` / byte | `item`, `block`, `falling_dust`, `dust_pillar`, `block_crumble`, `block_marker` |
| `blockData` / `blockDuration` | String / long | 파티클 위치에 가짜 블록을 `blockDuration` 틱 배치 |
| `particleOffsetX/Y/Z` | float | 파티클 산포 오프셋 |
| `spellPower` | float | `effect`, `instant_effect` |
| `dragonBreathPower` | float | `dragon_breath` |
| `sculkChargeRotation` | float | `sculk_charge` |
| `arrivalTime` | int | `vibration` |
| `shriekDelay` | int | `shriek` |
| `trailDuration` | int | `trail` |

### 3.5 base 클래스 — 위키 vs jar 불일치

| 항목 | 판정 |
|:---|:---|
| `geyserWaterBlocks`, `geyserBurstImpulse` | ⚠️ **위키에는 있으나 이 jar 에는 public 필드가 없다.** 위키가 "Since 4.0 Beta 19" 라고 명시하는데 이 서버 jar 는 **Beta-18**. 쓰면 §1 실패 경로 → 이펙트 안 나옴. jar 업그레이드 전까지 금지 |
| `colorList`, `toColorList` | jar 에 public 이지만 위키 미문서화. `colors`/`toColors` 문자열 파싱 결과를 담는 내부용이라 YAML 에서 직접 쓸 이유는 없다 |
| `targetPlayers` (`List<Player>`), `callback` (`Runnable`) | jar 에 public 이지만 YAML 로 값이 안 들어가는 API 전용 필드. 문서화 안 된 게 정상 |
| `subEffectClass` | 위키는 문서화하지만 jar 에서는 **`protected`** — `getField` 로는 안 잡힌다. `CLASS_STRINGS` 에 들어 있어서 통과할 뿐 |
| `maxIterations` | jar 에서 `protected`. YAML 옵션 아님 |

---

## 4. 48개 클래스 전수 표

각 행의 필드는 **`javap -p` 로 뽑은 클래스 고유 public 필드**다 (§3 의 상속 필드는 전부 추가로 쓸 수 있으므로 생략).
설명은 각 `wiki/EffectLib-*.md` 의 Description.

| 클래스 | 설명 | 클래스 고유 public 필드 |
|:---|:---|:---|
| `AnimatedBallEffect` | 구 형태를 따라 도는 애니메이션 소용돌이 | `particles`(int), `particlesPerIteration`(int), `size`(f), `xFactor`/`yFactor`/`zFactor`(f), `xOffset`/`yOffset`/`zOffset`(f), `xRotation`/`yRotation`/`zRotation`(d) |
| `ArcEffect` | origin→target 포물선 호 | **`height`(f), `particles`(int) — 이 둘뿐** (§2) |
| `AtomEffect` | 핵 + 궤도로 된 원자 모형 | `particleNucleus`, `colorNucleus`, `particleOrbital`, `colorOrbital`, `radius`(d), `radiusNucleus`(f), `particlesNucleus`(int), `particlesOrbital`(int), `orbitals`(int), `rotation`(d), `orient`(b), `angularVelocity`(d) |
| `BigBangEffect` | 큰 폭발 + 굉음 | `fireworkType`, `color2`, `color3`, `fadeColor`, `intensity`(int), `radius`(f), `explosions`(int), `soundInterval`(int), `sound`, `soundVolume`(f), `soundPitch`(f) |
| `BleedEffect` | 대상이 피 흘리는 연출 (+선택적 hurt) | `hurt`(b), `height`(d), `material` |
| `CircleEffect` | 2D 원 | `orient`(b), `xRotation`/`yRotation`/`zRotation`(d), `angularVelocityX`/`Y`/`Z`(d), `radius`(f), `maxAngle`(d), `resetCircle`(b), `xSubtract`/`ySubtract`/`zSubtract`(d), `enableRotation`(b), `particles`(int), `wholeCircle`(b) |
| `CloudEffect` | 뭉게구름 | `cloudParticle`, `cloudColor`, `cloudSpeed`(f), `cloudParticles`(int), `mainParticle`, `mainParticles`(int), `cloudSize`(f), `particleRadius`(f), `yOffset`(d), `increaseHeight`(b) |
| `ColoredImageEffect` | 이미지 픽셀을 픽셀 색으로 칠해 파티클로 표시 | **고유 필드 없음.** `BaseImageEffect` 상속 (§4.1) |
| `ConeEffect` | origin 에서 뻗는 소용돌이 원뿔 | `lengthGrow`(f), `angularVelocity`(d), `particles`(int), `radiusGrow`(f), `particlesCone`(int), `rotation`(d), `randomize`(b), **`solid`(b)**, **`strands`(int)** |
| `CubeEffect` | 와이어프레임 정육면체 | `edgeLength`(f), `angularVelocityX`/`Y`/`Z`(d), `particles`(int), `enableRotation`(b), `outlineOnly`(b), `orient`(b) |
| `CuboidEffect` | 와이어프레임 직육면체 | `particles`(int), `xLength`/`yLength`/`zLength`(d), `padding`(d), `blockSnap`(b) |
| `CylinderEffect` | 원기둥 (속 빈/찬) | `radius`(f), `height`(f), `angularVelocityX`/`Y`/`Z`(d), `rotationX`/`rotationY`/`rotationZ`(d), `particles`(int), `enableRotation`(b), `solid`(b), `orient`(b) |
| `DiscoBallEffect` | 하늘의 디스코볼 + 광선 | `sphereRadius`(f), `max`(int), `sphereParticle`, `lineParticle`, `sphereColor`, `lineColor`, `maxLines`(int), `lineParticles`(int), `sphereParticles`(int), `direction`(enum) |
| `DnaEffect` | 나선 DNA 가닥 | `particleHelix`, `colorHelix`, `particleBase1`, `colorBase1`, `particleBase2`, `colorBase2`, `radials`(d), `radius`(f), `particlesHelix`(int), `particlesBase`(int), `length`(f), `grow`(f), `baseInterval`(f) |
| `DonutEffect` | 토러스 | `particlesCircle`(int), `circles`(int), `radiusDonut`(f), `radiusTube`(f), `xRotation`/`yRotation`/`zRotation`(d) |
| `DragonEffect` | 호를 그리며 뻗는 불꽃 가닥들 | `pitch`(**float**), `arcs`(int), `particles`(int), `stepsPerIteration`(int), `length`(f) ⚠️ 아래 주의 |
| `EarthEffect` | 지구 모양 구체 | `particleLand`, `particleOcean`, `colorLand`, `colorOcean`, `particlesLand`(int), `particlesOcean`(int), `speedLand`(f), `speedOcean`(f), `precision`(int), `particles`(int), `radius`(f), `mountainHeight`(f) |
| `EquationEffect` | 매개변수 방정식으로 매 틱 파티클 배치 | `xEquation`/`yEquation`/`zEquation`(S), `variable`(S), `particles`(int), `x2Equation`/`y2Equation`/`z2Equation`(S), `variable2`(S), `particles2`(int), `orient`(b), `orientPitch`(b), `maxSteps`(int), `cycleMiniStep`(b) |
| `ExplodeEffect` | 큰 폭발 | `particle1`, `particle2`, `amount`(int), `sound` |
| `FlameEffect` | 불꽃 오라 | `particles`(int) |
| `FountainEffect` | 분수 | `strands`(int), `particlesStrand`(int), `particlesSpout`(int), `radius`(f), `radiusSpout`(f), `height`(f), `heightSpout`(f), `rotation`(d) |
| `GridEffect` | 2D 와이어프레임 격자 | `rows`(int), `columns`(int), `widthCell`(f), `heightCell`(f), `particlesWidth`(int), `particlesHeight`(int), `rotation`(d), `rotationX`(d), `rotationZ`(d), `center`(b) |
| `HeartEffect` | 3D 공간의 2D 하트 | `particles`(int), `xRotation`/`yRotation`/`zRotation`(d), `yFactor`(d), `xFactor`(d), `factorInnerSpike`(d), `compressYFactorTotal`(d), `compilation`(f) |
| `HelixEffect` | 납작한 나선 | `strands`(int), `particles`(int), `radius`(f), `curve`(f), `rotation`(d), `orient`(b), `enableRotation`(b), `xRotation`/`yRotation`/`zRotation`(d), `angularVelocityX`/`Y`/`Z`(d) |
| `HillEffect` | 물결치는 언덕 | `height`(f), `particles`(**float**), `edgeLength`(f), `yRotation`(d) ⚠️ `particles` 가 float 인 유일한 클래스 |
| `IconEffect` | 대상 위에 파티클 하나 | `yOffset`(int) |
| `ImageEffect` | 이미지 픽셀을 파티클로 표시 | `invert`(b) + `BaseImageEffect` (§4.1) |
| `JumpEffect` | 대상에 속도 부여 | `power`(f) |
| `LineEffect` | origin→target 직선 | `isZigZag`(b), `zigZags`(int), `zigZagOffset`(Vector), `zigZagRelativeOffset`(Vector), `particles`(int), `length`(d), `maxLength`(d), `subEffectAtEnd`(Section), **`subEffectAtEndCached`(Section)** |
| `LoveEffect` | 대상에서 하트가 피어오름 | **고유 필드 없음** |
| `ModifiedEffect` | 다른 이펙트의 임의 파라미터를 방정식으로 변조 | `effect`(Section), `effectClass`(S), `xEquation`/`yEquation`/`zEquation`(S), `variableA`(d), `variableB`(d), `orient`(b), `orientPitch`(b), `parameters`(`Map<String,String>`) |
| `MusicEffect` | 음표 고리 | `radialsPerStep`(d), `radius`(f) |
| `ParticleEffect` | 파티클 하나 (`type` 에 따라 instant/repeating/delayed) | **고유 필드 없음** — §3 공통 옵션만으로 쓴다 |
| `PlotEffect` | 방정식 그래프 (디버그용) | `xEquation`/`yEquation`/`zEquation`(S), `xScale`/`yScale`/`zScale`(d), `persistent`(b) |
| `PyramidEffect` | 피라미드 | `particles`(int), `radius`(d) |
| `ShieldEffect` | 대상을 감싸는 돔 | `radius`(d), `particles`(int), `sphere`(b), `reverse`(b) |
| `SkyRocketEffect` | 대상을 하늘로 쏘아 올림 | **고유 필드 없음.** `JumpEffect` 상속 → `power`(f) 사용 가능 |
| `SmokeEffect` | 대상이 연기 나는 연출 | `particles`(int) |
| `SoundEffect` | 소리 재생 | `sound`(`CustomSound`) |
| `SphereEffect` | 구 | `radius`(d), `yOffset`(d), `particles`(int), `radiusIncrease`(d), `particleIncrease`(int) |
| `SquareEffect` | 2D 정사각형 | `radius`(d), `yOffset`(d), `particles`(int), `radiusIncrease`(d), `particleIncrease`(int) |
| `StarEffect` | 2D 별 | `particles`(int), `spikeHeight`(f), `spikesHalf`(int), `innerRadius`(f) |
| `TextEffect` | 파티클로 글자 렌더 | `text`(S), `invert`(b), `stepX`(int), `stepY`(int), `size`(f), `realtime`(b), `font`(`java.awt.Font`) |
| `TornadoEffect` | 소용돌이 토네이도 | `tornadoParticle`, **`tornadoColor`**, `cloudParticle`, `cloudColor`, `cloudSpeed`(f), `cloudSize`(f), `yOffset`(d), `tornadoHeight`(f), `maxTornadoRadius`(f), `showCloud`(b), `showTornado`(b), `distance`(d), `circleParticles`(int), `cloudParticles`(int), `circleHeight`(d) |
| `TraceEffect` | 대상 엔티티 뒤에 자취 | `refresh`(int), `maxWayPoints`(int) |
| `TurnEffect` | 대상 엔티티의 시선 방향을 돌림 | `step`(f) |
| `VortexEffect` | 소용돌이 | `radius`(f), **`radiusGrow`(f)**, **`initRange`(f)**, `grow`(f), `radials`(d), `circles`(int), `helixes`(int) |
| `WarpEffect` | 텔레포터 패드처럼 내려오는 고리들 | `radius`(f), `particles`(int), `grow`(f), `rings`(int) |
| `WaveEffect` | 물결치는 수평면 | `mainParticle`, `cloudParticle`, `cloudColor`, `velocity`(Vector), `particlesFront`(int), `particlesBack`(int), `rows`(int), `lengthFront`(f), `lengthBack`(f), `depthFront`(f), `heightBack`(f), `height`(f), `width`(f) |

### 4.1 `BaseImageEffect` (Image / ColoredImage 공용)

`javap -p /tmp/msx/com/nisovin/magicspells/shaded/effectlib/util/BaseImageEffect.class`

`fileName`(S), `transparency`(b), `frameDelay`(int), `stepX`(int), `stepY`(int), `size`(f),
`enableRotation`(b), `rotation`(Vector), `orient`(b), `orientPitch`(b), `plane`(enum),
`angularVelocityX`/`Y`/`Z`(d)

---

## 5. 위키 ↔ jar 불일치 (전수 대조 결과)

### 5.1 위키에 있는데 jar 에 없다 (= 쓰면 이펙트가 안 나온다)

| 위치 | 옵션 | 실제 |
|:---|:---|:---|
| base (`Effectlib-Effect.md`) | `geyserWaterBlocks`, `geyserBurstImpulse` | Beta-19 도입. 이 jar 는 Beta-18 → **없음** |
| `EffectLib-Tornado.md` | `sphereColor` | jar 의 `TornadoEffect` 필드는 **`tornadoColor`**. `sphereColor` 는 `DiscoBallEffect` 소속. **위키 오기** |
| `EffectLib-Line.md` | `subEffectAtEndClass` | public 필드는 아니지만 `CLASS_STRINGS` 에 있어 **유효함** (거짓말 아님, 예외 처리 케이스) |
| `Effectlib-Effect.md` | `class`, `subEffectClass` | 마찬가지로 `CLASS_STRINGS` 예외. `subEffectClass` 는 jar 에서 `protected` |

### 5.2 jar 에 있는데 위키에 없다 (= 쓸 수 있는데 아무도 모르는 옵션)

| 클래스 | 미문서화 필드 |
|:---|:---|
| `ConeEffect` | **`solid`**(boolean), **`strands`**(int) — 원뿔을 여러 가닥/속 채움으로 만들 수 있다 |
| `VortexEffect` | **`radiusGrow`**(float), **`initRange`**(float) |
| `LineEffect` | `subEffectAtEndCached`(Section) — 짝 키 `subEffectAtEndCachedClass` 도 `CLASS_STRINGS` 에 존재 |
| base `Effect` | `colorList`, `toColorList`, `targetPlayers`, `callback` (§3.5 — 실질적으로 API 전용) |

### 5.3 타입 함정

- **`HillEffect.particles` 는 `float`** 다 (다른 모든 클래스는 `int`). 위키는 그냥 "Integer" 로 적어 놨다.
- **`DragonEffect.pitch` 는 `float`** 인데, base `Effect.pitch` 는 `java.lang.Float` (nullable) 다.
  `getField` 는 서브클래스 필드를 먼저 잡으므로 `DragonEffect` 위에서 `pitch:` 를 쓰면
  **origin 의 시선 pitch 가 아니라 Dragon 의 발사각 필드에 꽂힌다.** Dragon 에서 시선을 돌리려면 `pitchOffset` 을 써라.
- `IconEffect.yOffset` 은 `int`, `SphereEffect.yOffset`/`CloudEffect.yOffset` 은 `double`.

---

## 6. 검기(칼 휘두르기 호)에 쓸 만한 형상

각 항목의 기하는 위 §4 필드에서 직접 도출한 것이다.

| 클래스 | 실제로 나오는 기하 | 검기 적합성 |
|:---|:---|:---|
| **`EquationEffect`** | `xEquation`/`yEquation`/`zEquation` 을 `t`(major)·`t2`(minor) 로 매개변수화. `orient: true` 면 **X축이 "정면"** 이 되어 시전자 시선 기준 좌표계가 된다. `particles` = major step 수, `maxSteps` 로 루프 길이 제한 | ★★★ **1순위.** 호의 반지름·각도범위·두께·기울기를 전부 수식으로 통제 가능. `x=cos(t)*r, y=sin(t)*r, z=0` 에 `orient` 를 걸면 시선 앞 수직 반원 = 세로베기. `maxSteps` + `period:1` 로 스윙 진행률을 프레임 단위로 뽑을 수 있다 |
| **`CircleEffect`** | 반지름 `radius` 원. **`maxAngle`(기본 `2π`) 로 부분 원 = 호**를 만든다. `xRotation`/`yRotation`/`zRotation` 으로 원의 평면 자체를 기울이고, `wholeCircle:true` 면 한 iteration 에 호 전체를 한 번에 그린다. `resetCircle` 은 매 step 시작점을 고정 | ★★★ **가장 싸고 직관적.** `maxAngle` + 3축 회전 + `wholeCircle` 조합이 사실상 "검기 호" 그 자체. Arc 로 하려다 실패하는 일의 정답이 대개 이쪽 |
| **`LineEffect`** | origin→target 직선. `length` 를 0 이 아닌 값으로 주면 **target 을 무시하고 그 길이만큼** 뻗는다 (방향은 origin 의 시선). `maxLength` 는 상한. `isZigZag`/`zigZags` 로 톱니 | ★★☆ 찌르기·직선 참격. `length` 로 타겟 없이도 그릴 수 있는 게 Arc 대비 큰 장점 |
| **`ArcEffect`** | origin→target 포물선, `height` 는 중점 융기 | ★☆☆ **타겟 두 점이 있어야만 성립.** 방향 기반 검기에는 부적합 (§2) |
| **`ConeEffect`** | `lengthGrow`·`radiusGrow` 로 매 step 자라는 원뿔 나선. 미문서화 `strands` 로 가닥 수, `solid` 로 속 채움 | ★★☆ 부채꼴 확산 참격(광역 베기). `strands` 를 3~5 로 두면 여러 겹 검기 |
| **`WaveEffect`** | `lengthFront`/`lengthBack`/`width`/`height`/`rows` 로 정의되는 앞뒤 비대칭 수평 파도면. `velocity` 로 진행 | ★★☆ 지면을 훑는 충격파형 참격 |
| **`StarEffect`** | `spikesHalf`(뿔의 절반 수), `spikeHeight`, `innerRadius` 로 된 평면 별. 호가 아니라 닫힌 다각형 | ★☆☆ 검기 아님. 마법진·타격 마커용 |
| **`TraceEffect`** | `refresh`/`maxWayPoints` 만. **대상 엔티티의 이동 경로**를 웨이포인트로 저장해 자취를 남김. 기하를 직접 못 정한다 | ★★☆ 검을 든 아마스탠드/발사체를 실제로 휘두를 때만 유용. 정적 스펠 이펙트로는 부적합 |
| **`TurnEffect`** | `step`(float) 만. **파티클을 안 그린다** — 대상 엔티티의 yaw 를 매 틱 `step` 만큼 돌리는 조작 이펙트. `iterations` 기본이 `360*5/step` | ☆ 형상 아님. 다만 **회전베기 연출에서 시전자를 실제로 돌리는 용도**로는 유일한 수단 (`asynchronous:false` 필수) |
| **`WarpEffect`** | `rings` 개의 고리가 `grow` 만큼 커지며 `radius` 로 내려옴. 수평 고리 스택 | ☆ 검기 아님. 기 모으기/발동 전조용 |
| **`VortexEffect`** | `helixes` 가닥이 `circles` 바퀴, `radials` 각속도로 감기는 소용돌이. 미문서화 `radiusGrow`/`initRange` 로 나팔 모양 조절 | ★☆☆ 회전 연격·기 회오리. 단일 참격 호는 아님 |
| **`HelixEffect`** | `strands` 가닥 나선, `curve` 로 꼬임, 3축 `rotation`/`angularVelocity`, `orient` 지원 | ★★☆ 검신을 타고 감기는 기운. `orient:true` + 짧은 `iterations` 로 회전 참격 잔상 |

### 권장 조합

1. **정면 호 참격** → `CircleEffect` + `maxAngle`(예: `2.4`) + `zRotation`/`xRotation` 으로 베는 각도 + `wholeCircle: true` + `orient: true`
2. **프레임 단위로 스윙이 진행하는 검기** → `EquationEffect` + `orient: true` + `maxSteps` + `period: 1`
3. **호를 여러 겹으로 두껍게** → 위 둘 중 하나에 `subEffect` 로 작은 `CircleEffect`/`ParticleEffect` 를 물리고 상위는 `particleCount: -1`
4. **직선 찌르기** → `LineEffect` + `length` (target 불필요)

`ArcEffect` 는 "A 지점에서 B 지점으로 날아가는 궤적"(투사체 예고선 등)일 때만 쓴다.

---

## 참고 · 재검증 명령

```bash
JAVAP=/home/delangi/문서/project/category/honcheon-server/run/jdk-21/bin/javap

# base 공통 옵션
$JAVAP -p /tmp/msx/com/nisovin/magicspells/shaded/effectlib/Effect.class

# 특정 클래스의 고유 public 필드만
$JAVAP -p /tmp/msx/com/nisovin/magicspells/shaded/effectlib/effect/CircleEffect.class \
  | grep -E '^\s+public [a-zA-Z].*;$' | grep -v '('

# 48개 전수
for f in /tmp/msx/com/nisovin/magicspells/shaded/effectlib/effect/*Effect.class; do
  echo "== $(basename $f .class)"
  $JAVAP -p "$f" | grep -E '^\s+public [a-zA-Z].*;$' | grep -v '('
done
```

로그에서 실패 확인:

```bash
grep -E "Invalid option '.*' on EffectLib effect|Error assigning EffectLib property" run/logs/latest.log
```

위키 원문: `scratch/msdocs/wiki/Effectlib-Effect.md`, `scratch/msdocs/wiki/List-of-Effectlib-Classes.md`,
`scratch/msdocs/wiki/EffectLib-*.md` (48개 + `EffectLib-Base-Image.md`)
