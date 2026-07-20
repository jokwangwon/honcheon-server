# MagicSpells — `effects:` 절 (Spell Effects)

> 검기 VFX 를 짜기 위한 작업용 참조.
> 출처 표기: `wiki/<파일>.md` = `scratch/msdocs/wiki/`, `jar:<FQCN>` = `/tmp/msx/` 에 푼 클래스.
> 검증 명령: `run/jdk-21/bin/javap -p -cp /tmp/msx <FQCN>` (바이트코드까지 보려면 `-c` 추가)

---

## 0. 3초 요약 — 우리가 밟은 지뢰

`position: caster` 는 **위치를 하나만** 넘긴다. `effectlib` 의 `ArcEffect`/`LineEffect` 는
**출발점과 도착점 두 개**를 요구한다. 도착점이 없으면 `onRun()` 첫 줄에서 `cancel()` 되어
**아무것도 그려지지 않는다.**

바이트코드 증거 (`jar:...shaded.effectlib.effect.ArcEffect`, `javap -c`):
```
public void onRun();
   1: invokevirtual  // Method getLocation:()Lorg/bukkit/Location;
   6: invokevirtual  // Method getTarget:()Lorg/bukkit/Location;
  15: invokevirtual  // Method cancel:()V     ← null 이면 여기로 간다
  24: invokevirtual  // Method cancel:()V
```
`getTarget()` 은 내부 `DynamicLocation target` 이 null 이면 null 을 돌려준다
(`jar:...shaded.effectlib.Effect`, `getTarget()` 의 `ifnonnull` 분기).
그리고 `position: caster` 경로는 `EffectLibEffect.playEffectLibLocation(Location, SpellData)`
→ `EffectManager.start(String, ConfigurationSection, Location)` 를 타는데,
그 오버로드의 바이트코드는 나머지 인자를 **전부 `aconst_null`** 로 채운다:
```
public Effect start(String, ConfigurationSection, Location);
   4: aconst_null    ← target Location
   5: aconst_null    ← origin Entity
   6: aconst_null    ← target Entity
   7: aconst_null    ← parameter Map
```

**즉 `.instant.DummySpell` + `position: caster` + `class: ArcEffect` 조합은 구조적으로 렌더 불가다.**
이게 `run/mvt-test/plugins/MagicSpells/spells-honcheon.yml` 의 `검기_호` 가 안 보이는 이유다.
해법은 §3-1 · §7 을 보라.

---

## 1. `effects:` 의 두 가지 형태

### 맵 형태 (전통)

```yml
검기_호:
    spell-class: ".targeted.DummySpell"
    effects:
      1:
        position: target
        effect: lightning
      2:
        position: caster
        effect: particles
        particle-name: end_rod
        horiz-spread: .5
        vert-spread: .5
        speed: .1
        count: 10
```

맵 키(`1`, `2`)는 **아무 의미도 없는 이름표**다. `참격`, `반짝` 처럼 한글로 써도 되고 실제로 우리
`spells-honcheon.yml` 이 그렇게 쓰고 있다. 유일한 제약은 **같은 키를 두 번 쓰지 말 것**.

### 리스트 형태 (**4.0 Beta 17 이후**)

```yml
검기_호:
    spell-class: ".targeted.DummySpell"
    effects:
      - position: target
        effect: lightning
      - position: caster
        effect: particles
        particle-name: end_rod
        count: 10
```

키 중복 걱정이 없어 **같은 이펙트를 파라미터만 바꿔 여러 번 쌓을 때** 이쪽이 편하다.

> 출처: `wiki/Spell-Effects.md` (Description)

### 로드 조건

이펙트 하나가 로드되려면 **`position` 과 `effect` 두 개가 유효한 값으로 있어야 한다.** 둘 다 필수.

> 출처: `wiki/Spell-Effects.md` (Configuration),
> jar: `SpellEffect.loadFromConfiguration(ConfigurationSection)` 가 final 로 공통 옵션을 먼저 읽고
> 추상 `loadFromConfig()` 로 타입별 옵션을 넘긴다

---

## 2. 모든 이펙트가 공유하는 옵션

| 옵션 | 설명 | 타입 | 기본 | expression |
|:--|:--|:--|:--|:--|
| `delay` | 재생까지 지연 (**틱**) | Integer | `0` | O |
| `chance` | 재생 확률, `(0, 1)` 구간 | Double | — | O |
| `offset` | 절대 벡터 오프셋 | Vector | `"0,0,0"` | O |
| `relative-offset` | **시선 기준** 상대 오프셋 | Vector | `"0,0,0"` | O |
| `z-offset` | Z축 오프셋 | Double | `0` | O |
| `height-offset` | Y축 오프셋 | Double | `0` | O |
| `forward-offset` | X축 오프셋 | Double | `0` | O |
| `yaw` / `pitch` | 위치의 방향 회전 (Angle) | Angle | `~0` | O |
| `modifiers` | 재생 여부 조건 | Modifier List | — | X |
| `caster-modifiers` | 시전자에 대해서만 검사 | Modifier List | — | X |
| `target-modifiers` | 타깃에 대해서만 검사 | Modifier List | — | X |
| `location-modifiers` | 타깃 위치에 대해서만 검사 | Modifier List | — | X |

**`offset` vs `relative-offset` 이 검기에서는 결정적이다.**
`offset` 은 월드 축 고정이고, `relative-offset` 은 시전자가 바라보는 방향을 따라 돈다.
"몸 앞 1.2m, 가슴 높이" 같은 건 반드시 `relative-offset: 0,1.1,1.2` 로 써야 한다.

> 출처: `wiki/Spell-Effects.md` (General Configuration)
> jar 대조 (`javap -p jar:com.nisovin.magicspells.spelleffects.SpellEffect`):
> `ConfigData<Integer> delay` · `ConfigData<Double> chance` · `zOffset` · `heightOffset` ·
> `forwardOffset` · `ConfigData<Vector> offset` · `relativeOffset` · `ConfigData<Angle> yaw`/`pitch` ·
> `modifiers`/`casterModifiers`/`targetModifiers`/`locationModifiers` (`ModifierSet`)
> — 위키에 없는 `yaw`/`pitch` 도 공통 옵션으로 실재한다 (`applyOffsets(..., Angle, Angle)` 시그니처)

---

## 3. 이펙트 위치 (`position`) 전수표

jar 의 `EffectPosition` enum 은 **20개 상수**를 가지며, 각각 복수의 별칭 문자열을 갖는다.
아래 별칭 목록은 `javap -c jar:com.nisovin.magicspells.spelleffects.EffectPosition` 의
문자열 상수풀에서 뽑은 것 — **이게 정본이다.**

실측된 전체 별칭 집합:
`start`, `startcast`, `caster`, `actor`, `target`, `startpos`, `startposition`, `pos1`, `position1`,
`endpos`, `endposition`, `pos2`, `position2`, `line`, `trail`, `reverse_line`, `reverseline`, `rline`,
`buff`, `active`, `buffeffectlib`, `orbit`, `orbiteffectlib`, `disabled`, `delayed`, `special`,
`projectile`, `casterprojectile`, `casterprojectileline`, `blockdestroy`, `blockdestruction`,
`cooldown`, `missingreagents`, `chargeuse`

### 3-1. 위치별 요구조건 — **타깃이 필요한가**

| `position` (별칭) | enum 상수 | 넘어가는 위치 개수 | 타깃 필요? | 언제 뜨나 / 무엇이 필요한가 |
|:--|:--|:--|:--|:--|
| `start`, `startcast` | `START_CAST` | 1 (시전자) | X | 시전 **시작** 시. `cast-time` 완료를 기다리지 않는다 → `cast-time: 0` 이면 `caster` 와 사실상 동시 |
| `caster`, `actor` | `CASTER` | **1** | X | 시전 성공 시 시전자 위치. **두 점을 쓰는 EffectLib 클래스는 여기서 절대 안 그려진다** |
| `target` | `TARGET` | **1** | **O** | 엔티티 타깃 또는 겨냥 위치가 **잡혔을 때만**. Instant 계열은 타깃이 없으므로 아예 안 뜬다 |
| `line`, `trail` | `TRAIL` | **2** (시전자→타깃) | **O** | 두 점 사이를 `distance-between` 간격으로 순회하며 재생. §5 옵션 |
| `reverse_line`, `reverseline`, `rline` | `REVERSE_LINE` | **2** (타깃→시전자) | **O** | 위의 역방향 |
| `startposition`, `startpos`, `pos1`, `position1` | `START_POSITION` | 1 | 주문에 따라 | 주문이 정의하는 "시작점"(시전자 위치가 아닐 수 있다). 지원 여부는 주문 문서에 명시 |
| `endposition`, `endpos`, `pos2`, `position2` | `END_POSITION` | 1 | 주문에 따라 | 위와 짝. "끝점" |
| `buff`, `active` | `BUFF` | 1 (대상 추적) | 버프 대상 | **BuffSpell 전용.** 버프가 끝날 때까지 `effect-interval` 틱마다 반복. §6 |
| `buffeffectlib` | `BUFF_EFFECTLIB` | 1 (대상 추적) | 버프 대상 | 위와 같되 EffectLib 전용. 매 반복마다 이펙트를 새로 만들지 않고 **한 번 비동기 생성 후 위치만 갱신** — EffectLib 을 버프에 쓸 땐 **반드시 이쪽** |
| `orbit` | `ORBIT` | 1 (궤도 추적) | 버프 대상 | 궤도 위치에서 반복 재생. §6-2 |
| `orbiteffectlib` | `ORBIT_EFFECTLIB` | 1 | 버프 대상 | 위의 EffectLib 판. 같은 이유로 EffectLib 은 이쪽 |
| `cooldown` | `COOLDOWN` | 1 | X | 쿨다운 중 시전을 시도했을 때 |
| `missingreagents` | `MISSING_REAGENTS` | 1 | X | `cost` 가 모자랄 때 |
| `chargeuse` | `CHARGE_USE` | 1 | X | `charges` 가 하나 소모될 때 |
| `disabled` | `DISABLED` | 1 | 주문별 | **버프 종료 시.** 주문별 지원 |
| `delayed` | `DELAYED` | 1 | 주문별 | 주문별 지원 |
| `special` | `SPECIAL` | 1 | 주문별 | 주문이 임의로 정의. 예: `EntombSpell` 은 무덤 블록마다 중앙에서 재생 |
| `projectile` | `PROJECTILE` | 1 (투사체 추적) | X | 투사체 주문 전용. 투사체 위치를 따라간다. `armorstand` 이펙트는 **여기서만** 동작 |
| `casterprojectile`, `casterprojectileline` | `DYNAMIC_CASTER_PROJECTILE_LINE` | **2** (시전자↔투사체) | 투사체 | **`effect: effectlibline` 하고만 동작.** 시전자와 날아가는 투사체를 잇는 선 |
| `blockdestroy`, `blockdestruction` | `BLOCK_DESTRUCTION` | 1 | 주문별 | 블록이 제거될 때 |

> 출처: `wiki/Spell-Effects.md` (Effect Positions),
> jar: `com.nisovin.magicspells.spelleffects.EffectPosition` (enum 상수 20개 + 별칭 문자열)

### 3-2. 함정 — "위치 1개" vs "위치 2개"

`SpellEffect` 에는 **인자 개수가 다른 두 계열의 재생 메서드**가 있다
(`javap -p jar:com.nisovin.magicspells.spelleffects.SpellEffect`):

```java
public final Runnable playEffect(Location);                      // 1점 계열
public final Runnable playEffect(Location, SpellData);
public        Runnable playEffect(Location, Location);           // 2점 계열
public        Runnable playEffect(Location, Location, SpellData);
```

- `caster` / `target` / `start` / `buff` / `projectile` 등은 **1점 계열**을 부른다.
- `line` / `reverse_line` / `casterprojectileline` 만 **2점 계열**을 부른다.
- 2점 계열을 **override 하는 클래스는 `EffectLibLineEffect` 뿐이다**
  (`jar:...effecttypes.EffectLibLineEffect` 의 `playEffect(Location, Location, SpellData)`).

따라서:

> **`effect: effectlib` 로는 두 점을 쓰는 EffectLib 클래스(Arc, Line, …)를 절대 제대로 못 그린다.**
> `effect: effectlibline` + `position: line`(또는 `casterprojectileline`) 조합만이 origin 과 target 을
> 모두 채운다.

그리고 `position: line` 은 **타깃이 있어야** 두 번째 점이 생긴다 → **주문이 `.targeted.*` 여야 한다.**
`.instant.DummySpell` 에는 타깃이 없으므로 `line` 자체가 재생되지 않는다.

---

## 4. 이펙트 타입 (`effect:`) 전수표

jar 의 `spelleffects/effecttypes/` 에는 내부 클래스를 뺀 **36개** 이펙트 클래스가 있다.
YAML 에서 쓰는 이름은 클래스에 붙은 `@Name` 어노테이션 값이며,
`SpellEffectManager.addSpellEffect(Class)` 가 그 값을 키로 등록한다
(`javap -c jar:com.nisovin.magicspells.util.managers.SpellEffectManager`).

| `effect:` 이름 | 클래스 | 요약 |
|:--|:--|:--|
| `actionbartext` | `ActionBarTextEffect` | 액션바 메시지 |
| `armorstand` | `ArmorStandEffect` | 갑옷거치대 소환·추적 (**`projectile` 위치 전용**) |
| `blockbreak` | `BlockBreakEffect` | 블록 균열(파괴 단계) 오버레이 |
| `bossbar` | `BossBarEffect` | 보스바 |
| `broadcast` | `BroadcastEffect` | 채팅 브로드캐스트 |
| `cloud` | `CloudEffect` | 구름 |
| `completeusingitem` | `CompleteUsingItemEffect` | 아이템 사용 완료 애니메이션 |
| `dragondeath` | `DragonDeathEffect` | 엔더드래곤 사망 연출 |
| `effectlib` | `EffectLibEffect` | EffectLib 이펙트 (**1점**) |
| `effectlibentity` | `EffectLibEntityEffect` | EffectLib 이펙트를 엔티티에 붙여 따라다니게 |
| `effectlibline` | `EffectLibLineEffect` | EffectLib 이펙트 (**2점** — `line`/`casterprojectileline` 용) |
| `ender` | `EnderSignalEffect` | 엔더의 눈이 깨질 때의 연출 |
| `entity` | `EntityEffect` | 엔티티 소환 |
| `explosion` | `ExplosionEffect` | 작은 폭발. **옵션 없음** |
| `fireworks` | `FireworksEffect` | 폭죽 |
| `gametestaddmarker` | `GameTestAddMarkerEffect` | 디버그 마커 추가 |
| `gametestclearmarkers` | `GameTestClearMarkersEffect` | 디버그 마커 제거 |
| `itemcooldown` | `ItemCooldownEffect` | 아이템 쿨다운 표시 |
| `itemspray` | `ItemSprayEffect` | 아이템 분사 (주울 수 없고 시간 뒤 사라짐) |
| `lightning` | `LightningEffect` | 번개. **옵션 없음** |
| `nova` | `NovaEffect` | 퍼져 나가는 블록 노바 |
| `particlecloud` | `ParticleCloudEffect` | 파티클 구름 |
| `particles` | `ParticlesEffect` | 파티클 |
| `particlespersonal` | `ParticlesPersonalEffect` | 특정 플레이어에게만 보이는 파티클 |
| `potion` | `PotionEffect` | 물약 효과 부여 |
| `smokeswirl` | `SmokeSwirlEffect` | 연기 소용돌이 |
| `smoketrail` | `SmokeTrailEffect` | 연기 궤적 |
| `sound` | `SoundEffect` | 소리 |
| `soundpersonal` | `SoundPersonalEffect` | 특정 플레이어에게만 들리는 소리 |
| `spawn` | `MobSpawnerEffect` | 스포너가 몹을 낳을 때의 연출 |
| `splash` | `SplashPotionEffect` | 투척 물약 연출 |
| `startusingitem` | `StartUsingItemEffect` | 아이템 사용 시작 애니메이션 |
| `stopusingitem` | `StopUsingItemEffect` | 아이템 사용 중지 애니메이션 |
| `swinghand` | `SwingHandEffect` | 손 휘두르기 애니메이션 |
| `title` | `TitleEffect` | 화면 타이틀/서브타이틀 |
| `toast` | `ToastEffect` | 발전과제 토스트 |

**위키와의 불일치 1건**: `wiki/Spell-Effects.md` 는 `experience`(→`wiki/Experience-Effect.md`)를
목록에 올려 두었지만, **이 jar 에는 `ExperienceEffect` 클래스가 없다**
(`find /tmp/msx -iname '*Experience*'` → `ExperienceBarManager`, `ExperienceVariable`,
`ExperienceLevelVariable` 뿐). 이 빌드에서는 쓸 수 없다고 봐야 한다.

> 근거: `ls /tmp/msx/com/nisovin/magicspells/spelleffects/effecttypes/*.class | grep -v '\$'` → 36개,
> `@Name` 값은 `javap -v` 의 `RuntimeVisibleAnnotations` / `Lcom/nisovin/magicspells/util/Name;` 에서 추출

---

## 5. `line` 위치 전용 옵션

| 옵션 | 설명 | 타입 | 기본 | expr |
|:--|:--|:--|:--|:--|
| `max-distance` | 두 점 사이 거리가 이 값을 넘으면 **재생하지 않는다** | Double | `100` | O |
| `distance-between` | 점 사이 간격(블록). 다음 이펙트를 찍기까지의 거리 | Double | `1` | O |
| `start-location-height-offset` | 시작점 Y 보정 (4.0 Beta 13~) | Double | `0` | O |
| `end-location-height-offset` | 끝점 Y 보정 (4.0 Beta 13~) | Double | `0` | O |

`distance-between` 을 줄이면 선이 촘촘해지지만 그만큼 이펙트 인스턴스가 늘어난다
— 검기처럼 3m 짜리 궤적이면 `0.15`~`0.3` 정도가 현실적인 시작점이다.

> 출처: `wiki/Spell-Effects.md` (Line position-specific options)
> jar 대조: `SpellEffect.maxDistance`, `distanceBetween`, `startLocationHeightOffset`,
> `endLocationHeightOffset` (모두 `ConfigData<Double>`)

---

## 6. `buff` 위치 전용 옵션

| 옵션 | 설명 | 타입 | 기본 | expr |
|:--|:--|:--|:--|:--|
| `effect-interval` | 반복 간격 (**틱**). 버프가 끝날 때까지 |  Integer | `20` | O |
| `drag-entity` | `effect: entity` 일 때 매 틱 새로 소환하지 않고 **한 번 소환 후 텔레포트** (4.0 Beta 13~) | Boolean | `false` | O |

기본값 `20` = 1초에 한 번. 매끄러운 지속 이펙트를 원하면 `effect-interval: 1`~`5` 로 내린다.

> 출처: `wiki/Spell-Effects.md` (Buff position-specific options)
> jar 대조: `SpellEffect.effectInterval` (`ConfigData<Integer>`), `SpellEffect.dragEntity` /
> `isDraggingEntity()`

### 6-2. `orbit` 위치 전용 옵션 (buff 옵션을 상속)

`orbit-x-axis` / `orbit-y-axis` / `orbit-z-axis` (Float, `0`) — 각 축 회전
`orbit-radius` (Float, `1`) · `orbit-y-offset` (Float, `0`) · `orbit-horiz-offset` (Float, `0`)
`orbit-horiz-expand-radius` + `orbit-horiz-expand-delay` — 수평 반경을 주기적으로 확장
`orbit-vert-expand-radius` + `orbit-vert-expand-delay` — 수직 반경 확장
`orbit-seconds-per-revolution` (Float, `3`) — 한 바퀴에 걸리는 초

jar 에는 위키에 없는 `counterClockwise`(`isCounterClockwise()`) 도 있다 — 회전 방향 반전용.

> 출처: `wiki/Spell-Effects.md` (Orbit position-specific options),
> jar: `SpellEffect.orbitXAxis`/`orbitYAxis`/`orbitZAxis`/`orbitRadius`/`orbitYOffset`/
> `horizOffset`/`horizExpandRadius`/`vertExpandRadius`/`secondsPerRevolution`/
> `horizExpandDelay`/`vertExpandDelay`/`counterClockwise`

---

## 7. 타입별 필드 참조 (우리가 실제로 쓸 것)

### 7-1. `particles`

```yml
effect: particles
particle-name: end_rod
count: 10
speed: 0.1
horiz-spread: 0.5
vert-spread: 0.5
```

| 옵션 | 설명 | 타입 | 기본 |
|:--|:--|:--|:--|
| `particle-name` | 파티클 이름 | String | — |
| `count` | 개수 | Integer | `5` |
| `radius` | 이 반경 안의 플레이어에게만 보임 (4.0 Beta 13~) | Integer | `50` |
| `speed` | 속도 | Double | `0.2` |
| `horiz-spread` | 수평 퍼짐 | Double | `0.2` |
| `vert-spread` | 수직 퍼짐 | Double | `0.2` |
| `x-spread` / `y-spread` / `z-spread` | 축별 퍼짐. `horiz`/`vert` 보다 우선 | Double | 위 값 |
| `force` | 32블록 대신 512블록까지 강제 전송 | Boolean | `false` |
| `yaw` / `pitch` | 4.0 Beta 15~ | Angle | `~0` |

**옵션 이름을 틀리면 조용히 무시된다.** `horizontal-spread` 는 존재하지 않는 키다 —
`horiz-spread` 가 맞다. (우리 `spells-honcheon.yml` 의 `반짝` 이펙트가 이 오타를 갖고 있다.)

파티클별 특수 데이터:
- `dust` → `color` (Color, `ff0000`), `size` (Integer, `1`)
- `dust_color_transition` → `color`, `to-color` (`000000`), `size`
- `item` → `material`
- `block`, `falling_dust`, `dust_pillar`, `block_crumble`, `block_marker` → `material` (Block Data)
- `effect`, `instant_effect` → `power` (Float), `color` (4.0 Beta 18~)
- `entity_effect`, `tinted_leaves`, `flash` → `color` / `argb-color`
- `entity_effect`, `ambient_entity_effect`, `note` 는 **`count: 0` 으로 두고 `x/y/z-spread` 로
  색을 지정**하는 특수 규약이 있다 (4.0 Beta 13 이후 `red / 255` 같은 수식을 그대로 써도 된다)
- 방향성 파티클: `count: 0`, `speed: 1`, `x/y/z-spread` 가 **운동 벡터**가 된다
- `vibration` → `vibration-origin`/`vibration-destination` (`caster`/`target`/`position`),
  `vibration-offset`, `vibration-relative-offset`, `static-destination`, `arrival-time`
- `trail` (4.0 Beta 17~) → `trail:` 섹션 안에 `origin`, `target`, `color`, `duration`,
  `target-offset`, `target-relative-offset`
- `sculk_charge` → `sculk-charge-rotation` · `shriek` → `shriek-delay`
- `dragon_breath` → `dragon-breath-power` (4.0 Beta 18~)
- `geyser`/`geyser_plume`/`geyser_base`/`geyser_poof` → `geyser:` 섹션 (4.0 Beta 19~)

> 출처: `wiki/Particles-Effect.md`
> jar 대조 (`javap -p jar:...effecttypes.ParticlesEffect`): `particle`, `count`, `radius`, `speed`,
> `xSpread`/`ySpread`/`zSpread`, `force`, `rgbColor`, `argbColor`, `material`, `blockData`,
> `dustOptions`, `spellOptions`, `dustTransition`, `vibrationOffset`, `vibrationRelativeOffset`,
> `vibrationOrigin`, `vibrationDestination`, `staticDestination`, `arrivalTime`, `trailColor`,
> `trailDuration`, `trailOrigin`, `trailTarget`, `trailTargetOffset`, `trailTargetRelativeOffset`,
> `dragonBreathPower`, `sculkChargeRotation`, `shriekDelay` — 위키 표와 1:1로 맞는다

### 7-2. `effectlib` (1점)

`effectlib:` 라는 **하위 섹션** 안에 EffectLib 설정을 넣는다. `position` 과 같은 레벨이 아니다.

```yml
effects:
  구체:
    position: caster
    effect: effectlib
    effectlib:
      class: SphereEffect
      particle: FLAME
      particles: 20
      radius: 2.0
      iterations: 40
      period: 1
```

자주 쓰는 것:

| 옵션 | 설명 | 타입 | 기본 |
|:--|:--|:--|:--|
| `class` | EffectLib 클래스명 (**필수**) | String | — |
| `type` | `delayed` / `instant` / `repeating` | String | 클래스별 |
| `period` | 반복 간격 (틱) | Integer | `1` |
| `iterations` | 반복 횟수 | Integer | `0` |
| `particle` | 파티클 이름 | String | — |
| `particles` | 한 iteration 당 점 개수 (클래스별 필드) | Integer | 클래스별 |
| `particleCount` | 한 점당 파티클 수. `-1` 이면 파티클 끄기 (subEffect 전용일 때) | Integer | `1` |
| `speed` | 파티클 속도 | Float | `0` |
| `asynchronous` | 비동기 여부. `TurnEffect`/`JumpEffect` 는 미지원 | Boolean | `true` |
| `offset` | 시전자 기준 절대 오프셋 (시선 무관) | String `"x,y,z"` | `"0,0,0"` |
| `relativeOffset` | 시전자 **시선 기준** 오프셋 | String `"x,y,z"` | `"0,0,0"` |
| `targetOffset` | 타깃 위치 보정 | String | `"0,0,0"` |
| `yaw` / `yawOffset` / `pitch` / `pitchOffset` | 원점 방향 조정 | Float | `0` |
| `duration` | **밀리초**. 설정하면 iterations 를 역산 | Integer | — |
| `delay` | 재생 전 지연 (틱) | Integer | `0` |
| `probability` | 매 iteration 재생 확률 | Float (0-1) | `1` |
| `visibleRange` | 표시 반경 | Float | `32` |
| `color` | 파티클 색 (hex, `#` 선택, RGB 6자리 / ARGB 8자리는 4.0 Beta 16~, `random` 가능) | String | — |
| `colors` | 쉼표로 구분된 색 목록 중 무작위 | String | — |
| `autoOrient` / `updateLocations` | 타깃이 있으면 두 위치가 서로 마주보게 | Boolean | `false` / `true` |
| `updateDirections` | 엔티티 결속 방향의 갱신 여부 | Boolean | `true` |
| `disappearWithOriginEntity` / `disappearWithTargetEntity` | 해당 엔티티가 사라지면 중단 | Boolean | `false` |
| `subEffect` + `subEffectClass` | 각 파티클 위치에서 재생되는 하위 이펙트 (중첩 가능) | Section | — |
| `blockData` + `blockDuration` | 파티클 자리에 가짜 블록을 `blockDuration` 틱 배치 | — | — |

**EffectLib 은 camelCase 다.** MagicSpells 본체는 kebab-case(`relative-offset`),
`effectlib:` 섹션 안은 camelCase(`relativeOffset`). 섞으면 조용히 무시된다.

**`ArcEffect` 의 실제 필드는 단 두 개**다 (`javap -p jar:...shaded.effectlib.effect.ArcEffect`):
```java
public float height;    // 호의 높이 (블록). wiki 기본 2
public int   particles; // 호를 이루는 점의 수. wiki 기본 100
```
`arcLength`, `arcHeight`, `xRotation`, `yRotation`, `zRotation`, `locationOffset` 같은 키는
**존재하지 않는다** — 우리 `spells-honcheon.yml` 이 그것들을 쓰고 있으며 전부 무시되고 있다.
`ArcEffect` 는 `type: repeating`, `particle: flame`, `period: 1`, `iterations: 200` 이 기본이다.

`LineEffect` 필드 (`javap -p jar:...shaded.effectlib.effect.LineEffect`):
`isZigZag`(Boolean), `zigZags`(Integer, 10), `zigZagOffset`(`"0,0.1,0"`),
`zigZagRelativeOffset`, `particles`(100), `length`(0 — 0이 아니면 타깃 대신 이 길이를 쓴다),
`maxLength`(0), `subEffectAtEnd` + `subEffectAtEndClass`.
기본 `type: repeating`, `particle: flame`, `period: 1`, `iterations: 1`.

`length` 를 0 이 아닌 값으로 주면 **타깃 없이도 길이가 정해진다** — 그래도 `getTarget()` 이 null 이면
`LineEffect.onRun()` 이 취소되므로 타깃 자체는 여전히 필요하다.

> 출처: `wiki/Effectlib-Effect.md`, `wiki/EffectLib-Arc.md`, `wiki/EffectLib-Line.md`,
> 클래스 목록은 `wiki/List-of-Effectlib-Classes.md` (개별 페이지는 `wiki/EffectLib-*.md` 55종)

### 7-3. `effectlibline` (2점)

`effectlib` 의 모든 옵션을 상속하고 두 개를 더한다.

| 옵션 | 설명 | 타입 | 기본 |
|:--|:--|:--|:--|
| `static-origin-location` | `false` 로 두면 선의 시작점이 **시전자를 계속 따라간다** | Boolean | `true` |
| `static-target-location` | `true` 로 두면 타깃의 **최초 위치**만 쓰고 추적하지 않는다 | Boolean | `false` |

이 둘은 `effectlib:` 섹션 **밖**, `position` 과 같은 레벨에 쓴다 (실물 `spells-regular.yml` 참조).

> 출처: `wiki/Effectlib-Line-Effect.md`
> jar 대조: `EffectLibLineEffect.forceStaticOriginLocation` / `forceStaticTargetLocation`
> (`ConfigData<Boolean>`), 그리고 이 클래스만이 `playEffect(Location, Location, SpellData)` 를 override 한다

셰이커 기본 설정에 실제로 도는 예 (`spells-regular.yml`, `volley` 주문):

```yml
    effects:
        projectile:
            effect: effectlibentity   # effectlib 에게 엔티티 추적을 맡긴다
            position: projectile
            effectlib:
                class: SphereEffect
                iterations: 100
                disappearWithOriginEntity: true
                particle: FLAME
                radius: 0.1
                particles: 5
                period: 2
        heavy-tracers:
            position: casterprojectileline
            effect: effectlibline
            static-origin-location: true
            effectlib:
                class: ArcEffect
                particles: 20
                particle: FLAME
                period: 1
                iterations: 50
                disappearWithOriginEntity: false
                disappearWithTargetEntity: true
```

**이것이 `ArcEffect` 를 제대로 쓰는 유일한 형태다** — `effectlibline` + 2점 위치.

### 7-4. `effectlibentity`

`EffectLibEffect` 를 상속하며 `playEffectEntity(Entity, SpellData)` 를 override 한다
(`javap -p jar:...effecttypes.EffectLibEntityEffect` — 필드는 하나도 추가하지 않는다).
**이펙트가 엔티티를 따라다녀야 할 때** 쓴다. 위키에는 전용 페이지가 없고
`wiki/Spell-Effects.md` 목록에 한 줄로만 있다.

### 7-5. `sound`

| 옵션 | 설명 | 타입 | 기본 |
|:--|:--|:--|:--|
| `sound` | 사운드 이름. **리소스팩 사운드도 된다** | String | `entity.llama.spit` |
| `volume` | 음량 | Float | `1` |
| `pitch` | 음정 (0-2) | Float | `1` |
| `category` | Bukkit `SoundCategory` | String | `master` |

> 출처: `wiki/Sound-Effect.md`
> jar 대조: `SoundEffect.sound`/`pitch`/`volume`/`category` (`ConfigData<...>`) — 4개 전부 일치

### 7-6. `itemspray`

| 옵션 | 설명 | 타입 | 기본 |
|:--|:--|:--|:--|
| `type` | 떨어뜨릴 재질 | Material | — |
| `amount` | 개수 | Integer | `15` |
| `duration` | 아이템 수명 (틱) | Integer | `10` |
| `force` | 퍼짐 배율. 각 아이템 속도 = `rand(0,1)-0.5` × force | Double | `1` |
| `velocity` | 속도 벡터 (`force` 를 곱한다). 4.0 Beta 14~ | Vector | 축별 `rand(0,1)-0.5` |
| `gravity` | 중력 적용. 4.0 Beta 14~ | Boolean | `true` |
| `remove-item-friction` | 마찰 제거. 4.0 Beta 14~ | Boolean | `false` |
| `resolve-duration-per-item` | 4.0 Beta 14~ | Boolean | `false` |
| `resolve-force-per-item` | 4.0 Beta 13~ | Boolean | `false` |

떨어진 아이템은 **주울 수 없다**.

> 출처: `wiki/Item-Spray-Effect.md`
> jar 대조: `ItemSprayEffect.material`/`velocity`/`force`/`amount`/`duration`/`gravity`/
> `removeItemFriction`/`resolveForcePerItem`/`resolveDurationPerItem` — 9개 전부 일치

### 7-7. `nova`

| 옵션 | 설명 | 타입 | 기본 |
|:--|:--|:--|:--|
| `type` | 블록 데이터 | String | `fire` |
| `types` | 무작위로 섞일 블록 데이터 목록 | String List | — |
| `range` | 표시 거리 | Integer | `20` |
| `radius` | 최종 반경 | Integer | `3` |
| `start-radius` | 시작 반경 | Integer | `0` |
| `expanding-radius-change` | 파동 하나당 커지는 블록 수 | Integer | `1` |
| `expand-interval` | 파동 간격 (틱) | Integer | `5` |
| `height-per-tick` | 파동당 높이 증가 | Integer | `0` |
| `circle-shape` | `true`=원, `false`=사각 | Boolean | `false` |
| `remove-previous-blocks` | 이전 파동 제거 | Boolean | `true` |
| `fake-blocks` | `false` 면 **진짜 블록**을 놓는다 (4.0 Beta 18~) | Boolean | `true` |

> 출처: `wiki/Nova-Effect.md`
> jar 대조: `NovaEffect.blockDataList`/`blockData`/`range`/`radius`/`startRadius`/`heightPerTick`/
> `expandInterval`/`expandingRadiusChange`/`fakeBlocks`/`circleShape`/`removePreviousBlocks` — 일치.
> (`range` 는 위키가 Integer 라고 하지만 jar 는 `ConfigData<Double>` 이다)

### 7-8. `armorstand`

**`position: projectile` 에서만 동작한다.** 갑옷거치대를 소환해 투사체 경로를 따라 텔레포트시킨다.
`armorstand:` 하위 섹션에 Entity Data 를 넣는다.

| 옵션 | 타입 | 기본 |
|:--|:--|:--|
| `gravity` | Boolean | `false` |
| `disable-slots` | Boolean (4.0 Beta 19~) | `true` |
| `head` / `mainhand` / `offhand` | Magic Item String | — |

```yml
effects:
    eff1:
        position: projectile
        effect: armorstand
        armorstand:
            head: pumpkin
            gravity: false
```

> 출처: `wiki/Armorstand-Effect.md`
> jar 대조: `ArmorStandEffect.entityData`/`gravity`/`headItem`/`offhandItem`/`mainhandItem`

### 7-9. `blockbreak`

| 옵션 | 설명 | 타입 | 기본 |
|:--|:--|:--|:--|
| `range` | 표시 거리 | Integer | `32` |
| `stage` | 균열 단계 `[0,9]`. **범위 밖 값은 오버레이를 지운다** | Integer | `0` |

4.0 Beta 13 도입.
> 출처: `wiki/Block-Break-Effect.md` / jar: `BlockBreakEffect.range`, `stage`

### 7-10. `explosion` / `lightning`

**옵션이 하나도 없다.** `javap -p` 로 확인한 결과 두 클래스 모두 필드가 0개이고
`loadFromConfig()` + `playEffectLocation()` 만 갖는다. `position` 만 정하면 끝.

```yml
effects:
  낙뢰:
    position: target
    effect: lightning
```

> 근거: `jar:...effecttypes.ExplosionEffect`, `jar:...effecttypes.LightningEffect` (필드 없음).
> 위키도 `wiki/Spell-Effects.md` 목록에서 링크 없이 한 줄 설명만 붙였다 — 전용 페이지가 없는 이유다.

### 7-11. `title`

| 옵션 | 타입 | 기본 |
|:--|:--|:--|
| `title` | Rich Text | — |
| `subtitle` | Rich Text | — |
| `fade-in` | Integer (틱) | `10` |
| `stay` | Integer (틱) | `40` |
| `fade-out` | Integer (틱) | `10` |
| `broadcast` | Boolean | `false` |

> 출처: `wiki/Title-Effect.md`
> jar 대조: `TitleEffect.title`/`subtitle`/`times`(`Title$Times`)/`broadcast`,
> **그리고 위키에 없는 `useViewerAsTarget` / `useViewerAsDefault` (`ConfigData<Boolean>`) 두 개가 더 있다**
> — 브로드캐스트 시 `%t` 같은 치환을 보는 사람 기준으로 풀지 정하는 용도로 보인다

### 7-12. `actionbartext`

| 옵션 | 타입 | 기본 |
|:--|:--|:--|
| `message` | Rich Text | — |
| `broadcast` | Boolean | `false` |

> 출처: `wiki/Action-Bar-Text-Effect.md` / jar: `ActionBarTextEffect.message`, `broadcast` — 정확히 일치

---

## 8. 검기 VFX 를 위한 결론

1. **호(arc)를 그리려면 두 점이 필요하다.** 두 점을 만드는 방법은 셋뿐:
   - `.targeted.*` 주문 + `position: line` + `effect: effectlibline`
   - 투사체 주문 + `position: casterprojectileline` + `effect: effectlibline`
   - 주문이 자체적으로 `startpos`/`endpos` 를 제공하는 경우
2. `.instant.DummySpell` + `position: caster` 로는 **어떤 EffectLib 2점 클래스도 안 그려진다.**
   `position: caster` 에서 쓸 수 있는 EffectLib 클래스는 Sphere, Circle, Helix, Cone, Vortex처럼
   **origin 하나로 완결되는 것들**이다.
3. `.instant` 를 유지하면서 호를 그리고 싶다면, 두 갈래가 있다:
   - `effect: particles` 를 여러 개 쌓고 `relative-offset` + `delay` 로 프레임을 나눠 궤적을 손으로 그린다
     (제어는 완전하지만 YAML 이 길어진다)
   - `.targeted.DummySpell` 로 바꾸고 `can-target: self` 또는 `always-activate: true` 를 써서
     타깃 확보를 보장한 뒤 `position: line` 을 쓴다
4. **키 이름 오타는 예외를 던지지 않고 조용히 무시된다.** 새 옵션을 쓸 때는 반드시
   `javap -p` 로 해당 클래스에 그 필드가 실재하는지 확인하라. 위키만 믿지 마라 —
   `experience` 이펙트처럼 위키에는 있는데 jar 에는 없는 것도 있다.
5. 단위를 섞지 마라: `delay`·`effect-interval`·`period`·`iterations`·`cast-time` = **틱**,
   `cooldown`·`duration`(BuffSpell) = **초**, EffectLib 의 `duration` = **밀리초**.

---

## 9. 관련 문서

- `01-spell-syntax.md` — 주문 정의 문법, 계열, 권한
- 위키 원본 (`scratch/msdocs/wiki/`):
  - `Spell-Effects.md` — 정본
  - `List-of-Effectlib-Classes.md` + `EffectLib-*.md` (55종) — EffectLib 클래스별 옵션
  - `Particles-Effect.md` · `Effectlib-Effect.md` · `Effectlib-Line-Effect.md` · `Sound-Effect.md` 등
  - `Registry.md` — 유효한 파티클/사운드 이름
  - `Other-Data-Types.md` — Vector / Color / Block Data / Angle 표기법
  - `Expression.md` — 수식·`%arg`·`%t` 등 플레이스홀더
- 실물 예제: `run/mvt-test/plugins/MagicSpells/spells-regular.yml`
  (`effectlib` 3곳, `effectlibentity` 2곳, `effectlibline` 1곳 — 특히 `volley` 주문)
