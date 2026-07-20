# MagicSpells — 주문 정의 문법 (Spell Syntax)

> 이 문서는 백과사전이 아니라 **작업용 참조**다. 검기 VFX·기술 체계를 짜면서 손이 닿는 것만 담는다.
> 모든 주장 뒤에 출처를 붙였다 — `wiki/<파일>.md` 는 `scratch/msdocs/wiki/` 아래, `jar:<경로>` 는 `/tmp/msx/` 아래.
> 검증 명령: `run/jdk-21/bin/javap -p -cp /tmp/msx <FQCN>`

---

## 1. 주문 파일은 어디에 놓는가 (로딩 규칙)

- 주문 파일 = MagicSpells 플러그인 폴더 안에서 **이름이 `spell` 로 시작하고 `.yml` 로 끝나는** 모든 파일.
  `spells-regular.yml`, `spells-command.yml`, `spells-honcheon.yml` 전부 자동으로 읽힌다.
- 이름이 **`spells` 로 시작하는 폴더**(예: `spellsFireMagic/`)에 넣어도 읽힌다. 하위 폴더는 **4.0 Beta 19 이후** 지원.
- `spellconfig/` 안에는 "mini spell config" 를 둘 수 있다. 파일 하나 = 주문 하나, **파일명이 곧 주문 이름**,
  내용은 주문 이름 섹션 없이 최상위(top-level)에 바로 쓴다. 변수 선언 등은 읽히지 않는다.
- `defaults.yml` (**4.0 Beta 13 이후**) 에서 하드코딩 기본값을 클래스 단위로 덮어쓸 수 있다.
  키는 **주문 클래스 이름**이며, 부모 클래스도 쓸 수 있다 — `"com.nisovin.magicspells.Spell"` 은 전체 주문,
  `".BuffSpell"` 은 모든 버프 주문에 적용된다.

> 출처: `wiki/Spell-Configuration.md` (Configuration Files / Mini Spell Files / Default Options),
> 실물 `run/mvt-test/plugins/MagicSpells/defaults.yml` (주석에 같은 예시가 그대로 들어 있다)

### 실물 확인 — 우리 서버

```
run/mvt-test/plugins/MagicSpells/
├── defaults.yml        # 전부 주석 처리된 빈 파일
├── general.yml         # ops-have-all-spells: true, global-cooldown: 500, los-* 기본값
├── mana.yml            # enable-mana-system: true, default-max-mana: 100, regen 5/20t
├── spells-regular.yml  # 셰이커 기본 예제 주문 (42KB)
├── spells-command.yml
└── spells-honcheon.yml # 우리 것
```

---

## 2. 주문 항목의 보편 형태

```yml
<internal-spell-name>:            # ← YAML 키. 이것이 "internal name". 하위 주문 참조는 항상 이 이름으로 한다.
    spell-class: ".instant.DummySpell"   # ← 유일한 필수 항목
    name: "&b검기"                 # ← "external name". 목록·메시지에 표시. 색 코드 지원.
    ...
```

- **필수는 `spell-class` 하나뿐**이다. 나머지 전부 선택. 주문 이름은 YAML 키가 담당한다.
- `spell-class` 는 **완전한 자바 클래스명**이지만, MagicSpells 내장 클래스면 `com.nisovin.magicspells.spells`
  접두사를 생략해 `.instant.DummySpell` 처럼 점으로 시작하게 쓴다.
- **주문 이름에 비-ASCII(한글)를 써도 된다.** YAML 키·`effects` 하위 키 모두 마찬가지다.
  근거: `spells-honcheon.yml` 의 `검기_호:` 주문과 그 안의 `참격:` / `반짝:` / `소리:` 이펙트 키가
  실제로 로드되어 돈다. 다만 `/cast` 로 부를 때 **external `name` 에 색 코드가 들어 있으면**
  색 코드를 뺀 이름을 따옴표로 감싸 넘겨야 한다 (`wiki/Spell-Configuration.md`, `name` 항목).

> 출처: `wiki/Spell-Configuration.md` (Common Options), `jar:com/nisovin/magicspells/Spell.class`
> — `protected java.lang.String internalName;` 과 `protected java.lang.String name;` 이 별도 필드로 존재

---

## 3. 모든 주문이 공유하는 옵션

아래 표의 필드명은 전부 `jar:com/nisovin/magicspells/Spell.class` 의 `javap -p` 출력과 대조했다.
표기: **REQ** = 필수, 나머지는 전부 선택(기본값 표기).

### 3-1. 핵심 (Common)

| 옵션 | 타입 | 기본 | 비고 |
|:--|:--|:--|:--|
| `spell-class` | String | — | **REQ**. 이것 하나만 필수 |
| `name` | String | (internal name) | 표시용 이름. `Spell.name` |
| `description` | String | — | HelpSpell 이 출력. `Spell.description` |
| `helper-spell` | Boolean | `false` | 아래 §6 참조. `Spell.helperSpell` |
| `always-granted` | Boolean | `false` | 전원에게 자동 지급. `Spell.alwaysGranted` |
| `permission-name` | String | internal name | 여러 주문이 같은 값을 공유해도 된다. `Spell.permName` |
| `aliases` | String List | — | internal name 대체. 하위 주문 참조에는 못 쓴다 |
| `incantations` | String List | — | 채팅으로 치면 시전. `one_word *` 형식이면 뒤 토큰이 `%arg` 로 들어온다 |
| `effects` | Config section 또는 List | — | → `03-spell-effects.md` |
| `cost` | String List | — | reagent 목록. `mana 25`, `anvil 1` 같은 꼴 |
| `str-cost` | Rich Text | — | HelpSpell 의 Cost 줄 |
| `modifiers` | String List | — | 시전 가부 조건. `Spell.modifierStrings` |
| `target-modifiers` | String List | — | 타깃 쪽 조건 |
| `location-modifiers` | String List | — | 타깃 위치 조건 |
| `variable-mods-cast` | String List | — | **시전 시도 시** 무조건 적용 (실패해도) |
| `variable-mods-casted` | String List | — | **성공했을 때만** 적용 |
| `variable-mods-target` | String List | — | 타깃의 변수 수정 |
| `tags` | String List | — | Spell Filter (`#tag`) 로 묶어 부를 때 쓴다 |
| `broadcast-range` | Integer | `general.yml` | `str-cast-others` 전파 거리 |
| `debug` | Boolean | `false` | 주문 단위 디버그 |

> `mana` 는 별도 옵션이 아니라 **`cost` 의 reagent 한 종류**다 (`cost: [mana 25]`).
> 마나 시스템 자체는 `mana.yml` 의 `enable-mana-system` 과 `magicspells.rank.<rank>` 권한으로 조절한다.
> 출처: `wiki/Spell-Configuration.md`, `wiki/Permissions.md` (Mana Ranks), 실물 `mana.yml`

### 3-2. 쿨다운

| 옵션 | 타입 | 기본 | 비고 |
|:--|:--|:--|:--|
| `cooldown` | Float | `0` | **초** 단위. `0-5` 처럼 범위(max 배타)도 된다. 비-범위는 4.0 Beta 17부터 expression 지원 |
| `use-precise-cooldowns` | Boolean | `false` | 범위 쿨다운을 반올림 없이 |
| `server-cooldown` | Float | `0` | 서버 전체에 적용. 범위 미지원 |
| `shared-cooldowns` | List | — | `filter` + `cooldown` 섹션 리스트 (4.0 Beta 17~) 또는 `"<주문> <초>"` 문자열 |
| `ignore-global-cooldown` | Boolean | `false` | `general.yml` 의 `global-cooldown: 500`(ms) 무시 |
| `charges` | Integer | `0` | 이 횟수만큼 쓴 뒤에야 쿨다운이 걸린다 |
| `str-on-cooldown` | Rich Text | `general.yml` | `%s`=이름, `%c`=남은 초 |
| `sound-on-cooldown` | String | `general.yml` | |

`general.yml` 의 `cooldowns-persist-through-reload: true` 때문에 리로드해도 쿨다운이 남는다.
초기화는 `/ms resetcd`.

> 출처: `wiki/Spell-Configuration.md` (Cooldown Options), 실물 `general.yml` 105~110행

### 3-3. 캐스트 타임 (선딜)

| 옵션 | 타입 | 기본 |
|:--|:--|:--|
| `cast-time` | Integer (**틱**) | `0` |
| `interrupt-on-move` | Boolean | `true` |
| `interrupt-on-teleport` | Boolean | `true` |
| `interrupt-on-cast` | Boolean | `true` |
| `interrupt-on-damage` | Boolean | `false` |
| `interrupt-filter` | Spell Filter | — |
| `spell-on-interrupt` | String | — |
| `str-interrupted` | Rich Text | — |

`cast-time > 0` 일 때만 `startcast` 이펙트 위치가 의미를 가진다.
**주의**: `cooldown` 은 초, `cast-time` 은 틱이다. 단위가 다르다.

> 출처: `wiki/Spell-Configuration.md` (Cast Time Options)

### 3-4. 타기팅

| 옵션 | 타입 | 기본 | 비고 |
|:--|:--|:--|:--|
| `range` | Integer | `20` | 최대 사거리(블록) |
| `min-range` | Integer | `0` | |
| `spell-power-affects-range` | Boolean | `false` | |
| `obey-los` | Boolean | `true` | 시선 차단 준수 |
| `can-target` | String List/String | — | `target-players`/`target-non-players` 를 덮어쓴다. `self`, `players`, `monsters` 등 |
| `target-self` | Boolean | `false` | 일관성이 낮다 — **`can-target: self` 를 써라** (위키 권고) |
| `beneficial` | Boolean | `false` | 스코어보드 팀 아군 판정에 쓰인다 |
| `always-activate` | Boolean | `false` | 타깃을 못 찾아도 cost·쿨다운을 소모 |
| `play-fizzle-sound` | Boolean | `false` | |
| `spell-on-fail` | String | — | 실패 시 하위 주문 |
| `str-no-target` | Rich Text | — | |
| `los-ray-size` | Double | `general.yml` (0.2) | 4.0 Beta 14~ |
| `los-transparent-blocks` | String List | `general.yml` ([]) | 빔·파티클 투사체가 관통할 블록 |
| `los-ignore-passable-blocks` | Boolean | `general.yml` (true) | 4.0 Beta 14~ |
| `los-fluid-collision-mode` | enum | `general.yml` (always) | 4.0 Beta 14~ |

> 출처: `wiki/Spell-Configuration.md` (Targeting Options / Block Target), 실물 `general.yml` 100~103행
> jar 대조: `Spell.range`, `Spell.minRange`, `Spell.obeyLos`, `Spell.validTargetList`,
> `Spell.losRaySize`, `Spell.losIgnorePassableBlocks`, `Spell.losFluidCollisionMode`

### 3-5. 시전 아이템

`cast-item` / `cast-items` / `left-click-cast-item(s)` / `right-click-cast-item(s)` /
`consume-cast-item(s)`, 그리고 `cast-with-left-click`, `cast-with-right-click`,
`require-cast-item-on-command`, `bindable`(기본 `true`), `bindable-items`, `spell-icon`.

jar 대조: `Spell.castItems`, `Spell.leftClickCastItems`, `Spell.rightClickCastItems`,
`Spell.consumeCastItems`, `Spell.bindableItems`, `Spell.spellIcon` (모두 `CastItem[]`).

> 출처: `wiki/Spell-Configuration.md` (Cast Item Options)

### 3-6. 그 밖

`experience`, `recharge-sound`, `prerequisites`, `replaces`, `precludes`,
`restrict-to-worlds`, `sound-missing-reagents`, 그리고 메시지 계열
`str-cast-self` / `str-cast-others` / `str-cast-target` / `str-cant-cast` /
`str-wrong-world` / `str-cast-start` / `str-on-teach` / `str-cast-cancelled` /
`str-modifier-failed` / `str-missing-reagents`.

**4.0 Beta 13 이후** 상당수 옵션이 expression(수식·문자열 치환)을 지원한다.
jar 에서 이것을 구분하는 방법: 필드 타입이 `ConfigData<T>` 면 expression 지원,
평범한 `int`/`String` 이면 로드 시각에 한 번만 읽는다.
예: `ConfigData<Float> cooldown` → 지원 / `float serverCooldown` → 미지원 / `int charges` → 미지원.

> 출처: `jar:com/nisovin/magicspells/Spell.class` (`javap -p`)

---

## 4. 네 갈래 계열 (spell-class families)

`spell-class` 앞의 패키지가 곧 계열이다.

| 계열 | 접두사 | 상위 클래스 | 무엇이 다른가 |
|:--|:--|:--|:--|
| Instant | `.instant.` | `InstantSpell` | 타깃 없이 시전자 기준으로 즉시 발동. `can-cast-with-item`, `can-cast-by-command` 옵션이 추가된다 |
| Targeted | `.targeted.` | `TargetedSpell` / `TargetedEntitySpell` / `TargetedLocationSpell` | **타깃(엔티티 또는 위치)을 먼저 찾는다.** §3-4 타기팅 옵션이 실제로 작동하는 유일한 계열 |
| Buff | `.buff.` | `BuffSpell` (← `InstantSpell`) | 켜고 끄는 지속 상태. `duration`, `toggle`, `cancel-on-*`, `num-uses` 계열 추가. 종료 시 `disabled` 위치 이펙트 |
| Command | `.command.` | `CommandSpell` | 인자를 받아 관리 작업을 한다 (가르치기·바인딩·목록). 대개 `/cast` 로만 부른다 |

패키지 밖(루트 `com.nisovin.magicspells.spells.*`)에도 계열에 속하지 않는 "general" 주문이 있다:
`.MultiSpell`, `.TargetedMultiSpell`, `.PassiveSpell`, `.RandomSpell`, `.MenuSpell`,
`.PlayerMenuSpell`, `.BowSpell`, `.ExternalCommandSpell`, `.LocationSpell`,
`.OffhandCooldownSpell`, `.PermissionSpell` — 이들은 앞에 점 하나만 붙인다 (`spell-class: ".MultiSpell"`).

**개수 (jar 실측)**: instant 37 · targeted 93 · buff 30 · command 14 = **174**,
여기에 루트 패키지의 구상/추상 클래스 19개(`InstantSpell`, `BuffSpell`, `TargetedSpell`,
`TargetedEntitySpell`, `TargetedLocationSpell`, `TargetedEntityFromLocationSpell`,
`CommandSpell`, `DamageSpell`, `MultiSpell`, `TargetedMultiSpell`, `PassiveSpell`,
`RandomSpell`, `MenuSpell`, `PlayerMenuSpell`, `BowSpell`, `ExternalCommandSpell`,
`LocationSpell`, `OffhandCooldownSpell`, `PermissionSpell`)를 더하면 **193**.
`spells/passive/` 디렉터리는 주문이 아니라 `PassiveSpell` 용 리스너 클래스 모음이다.

> 근거: `ls /tmp/msx/com/nisovin/magicspells/spells/{instant,targeted,buff,command}/*Spell.class`

### 4-1. `.instant.*` (37)

| 클래스 | 하는 일 |
|:--|:--|
| `BeamSpell` | 즉시 끝점까지 뻗는 직선 빔 |
| `BlockBeamSpell` | 위와 같되 블록 이펙트로 그린다 |
| `CastAtMarkSpell` | Mark 위치에 주문을 시전 |
| `ConfusionSpell` | 주변 몹끼리 서로 공격하게 |
| `ConjureBookSpell` | 설정대로 서식화된 책을 생성 |
| `ConjureFireworkSpell` | 설정대로 폭죽을 생성 |
| `ConjureSpell` | 아이템 생성 (여러 방식) |
| `CraftSpell` | 작업대 없이 제작창 열기 |
| `DowseSpell` | 반경 내 블록/엔티티 탐지 |
| `DummySpell` | **아무것도 안 한다.** 이펙트·변수 조작 껍데기로 쓴다 |
| `EnchantSpell` | 손의 아이템에 인챈트 부여 |
| `EnderchestSpell` | 엔더상자 열기 |
| `FlightPathSpell` | 엔티티에 태워 지정 좌표로 이동 |
| `FoodSpell` | 허기·포화도 회복 |
| `ForcepushSpell` | 주변 엔티티를 밀어냄 |
| `GateSpell` | 지정 월드·좌표로 순간이동 (`SPAWN`/`EXACTSPAWN` 가능) |
| `ItemProjectileSpell` | 아이템을 투사체처럼 던짐 |
| `LeapSpell` | 시전자를 도약시킴 (`VelocitySpell` 과 비교) |
| `MagnetSpell` | 주변 아이템을 끌어당김 |
| `ManaSpell` | 마나 회복 |
| `MarkSpell` | 위치를 표시. `TargetedLocationSpell` 이기도 하다 |
| `ParticleProjectileSpell` | 파티클 투사체를 쏴 명중 시 주문 발동 |
| `PhaseSpell` | 벽 통과 순간이동 |
| `PortalSpell` | Mark 위치로 가는 포털을 잠시 연다 |
| `ProjectileSpell` | 일반 엔티티 투사체 발사 |
| `PurgeSpell` | 주변 몹·동물 전멸 |
| `RecallSpell` | Mark 위치로 귀환 |
| `RepairSpell` | 내구도 수리 |
| `RitualSpell` | 여러 명이 모여야 완성되는 시전 |
| `RoarSpell` | 주변 적의 어그로를 끈다 |
| `SteedSpell` | 탈것 소환 |
| `ThrowBlockSpell` | 블록(기본 모루)을 던진다 |
| `TimeSpell` | 월드 시간 변경 |
| `UnconjureSpell` | 인벤토리에서 지정 아이템 제거 |
| `VariableCastSpell` | 변수에 저장된 주문 이름을 하드 캐스트 |
| `VelocitySpell` | 바라보는 방향으로 속도 부여 |
| `WallSpell` | 벽 생성 |

### 4-2. `.targeted.*` (93)

| 클래스 | 하는 일 |
|:--|:--|
| `AgeSpell` | 동물 나이 설정 |
| `AreaEffectSpell` | 범위 안 대상들에게 주문 시전 (AoE) |
| `AreaScanSpell` | 반경 내 블록을 찾아 주문 시전 |
| `BlinkSpell` | 겨냥한 블록으로 순간이동 |
| `BombSpell` | 블록을 놓고 지연 후 주문 발동 |
| `BuildSpell` | 원거리에서 인벤토리 블록으로 건축 |
| `CaptureSpell` | 몹을 알로 포획 |
| `CarpetSpell` | 겨냥 위치에 지뢰형 블록 — 밟으면 주문 발동 |
| `ChainSpell` | 대상에서 대상으로 튄다 (연쇄) |
| `CleanseSpell` | 버프/디버프·스턴 등 제거 |
| `CloseInventorySpell` | 대상 인벤토리 닫기 |
| `CollisionSpell` | 엔티티 충돌 여부 설정 |
| `CombustSpell` | 대상 점화 |
| `ConversationSpell` | 채팅 프롬프트로 응답을 변수에 저장 |
| `CreatureTargetSpell` | 시전자가 몹이면 그 몹의 타깃에 주문 시전 |
| `CrippleSpell` | 이동 봉쇄 (둔화 전용 축약형) |
| `CustomNameVisibilitySpell` | 커스텀 이름 표시 토글 |
| `DamageSpell` | 바닐라 damage type 기반 피해 |
| `DataSpell` | 특정 데이터를 변수에 담는다 |
| `DestroySpell` | 대량 파괴 (플러그인 보호 무시 — **위험**) |
| `DisarmSpell` | 대상의 손 아이템 제거 |
| `DiscoverRecipeSpell` | 레시피 해금/봉인 |
| `DotSpell` | 지속 피해 |
| `DrainlifeSpell` | 대상에서 자원을 흡수 |
| `DummySpell` | **아무것도 안 한다.** 단, 타깃을 잡는다 — 이펙트 실험용으로 이게 정답 |
| `EntityEditSpell` | 엔티티 attribute 편집 |
| `EntitySilenceSpell` | 몹 소리 토글 |
| `EntombSpell` | 대상을 블록으로 가둠 |
| `ExplodeSpell` | 겨냥 블록에 폭발 |
| `FarmSpell` | 주변 작물 성장 |
| `FireballSpell` | 파이어볼 발사 |
| `FlySpell` | 비행 상태 설정 |
| `ForcebombSpell` | 겨냥 블록에서 넉백 폭발 |
| `ForcetossSpell` | 대상을 밀쳐냄 (음수면 당김) |
| `GeyserSpell` | 블록 간헐천으로 적을 띄움 |
| `GlideSpell` | 활공 상태 설정 |
| `GlowSpell` | 대상 발광 |
| `GripSpell` | 대상을 내 위치로 끌어옴 |
| `HealSpell` | 회복 |
| `HoldRightSpell` | 우클릭 유지 동안 주문 반복 시전 |
| `HomingMissileSpell` | 파티클 유도탄 |
| `HomingProjectileSpell` | 투사체 유도탄 |
| `LevitateSpell` | 대상을 마우스 따라 띄움 |
| `LightningSpell` | 번개 |
| `LoopSpell` | 일정 시간·횟수로 주문 반복 |
| `MagicBondSpell` | 시전자의 모든 시전을 대상도 따라 하게 묶는다 |
| `MaterializeSpell` | 겨냥 위치에 블록/패턴 생성 |
| `MobGoalEditSpell` | 몹 Goal 수정 |
| `ModifyCooldownSpell` | 지정 주문의 쿨다운 조작 |
| `MountSpell` | 대상에 탑승 (또는 반대) |
| `NovaSpell` | 퍼져 나가는 블록 노바 — 경로 위에 주문 시전 |
| `OffsetLocationSpell` | 오프셋 위치에서 다른 타깃 주문을 시전 |
| `OrbitSpell` | 대상 주위를 도는 궤도 생성 |
| `PainSpell` | 직접 피해 |
| `ParseSpell` | 문자열 변수 편집 |
| `ParticleCloudSpell` | 파티클 구름 + 물약 효과 |
| `PasteSpell` | WorldEdit `.schem` 붙여넣기 |
| `PoseSpell` | 엔티티 Pose 설정 |
| `PotionEffectSpell` | 물약 효과 부여 |
| `ProjectileModifySpell` | 근처 파티클 투사체를 수정 |
| `PulserSpell` | 블록을 놓고 주기적으로 주문 발동 |
| `RegrowSpell` | 양털 재생 |
| `RemoveMarksSpell` | 근처 Mark 제거 |
| `ReplaceSpell` | 겨냥 블록 치환 |
| `ResourcePackSpell` | 리소스팩 요청 전송 |
| `RewindSpell` | 지연 후 대상을 과거 위치로 되돌림 |
| `RiptideSpell` | 격류 애니메이션 재생 |
| `RotateSpell` | 대상의 yaw/pitch 회전, 서로 마주보게 하기 |
| `ScoreboardDataSpell` | 스코어보드 점수를 변수에 저장 |
| `ShadowstepSpell` | 대상 뒤로 순간이동 |
| `ShearSpell` | 양털 깎기 |
| `SilenceSpell` | 대상의 시전 능력 봉인 |
| `SkinSpell` | 스킨 변경 |
| `SlimeSizeSpell` | 슬라임 크기 |
| `SlotSelectSpell` | 대상의 핫바 슬롯 강제 선택 |
| `SneakSpell` | 웅크리기 설정 |
| `SpawnEntitySpell` | 엔티티 소환 |
| `SpawnTntSpell` | TNT 소환 |
| `SprintSpell` | 달리기 설정 |
| `StructureSpell` | 마인크래프트 구조물 배치 |
| `StunSpell` | 이동·시점 모두 봉쇄 |
| `SummonSpell` | 플레이어를 소환(텔레포트) |
| `SwitchHealthSpell` | 체력 교환 |
| `SwitchSpell` | 대상과 위치 교환 |
| `TagEntitySpell` | 스코어보드 태그 관리 |
| `TelekinesisSpell` | 레버·압력판·버튼 원격 조작 |
| `TeleportSpell` | 대상에게 순간이동 |
| `TotemSpell` | 갑옷거치대 토템을 세워 주문을 펄스 |
| `TransmuteSpell` | 겨냥 블록의 재질 변경 |
| `TreeSpell` | 나무 성장 |
| `VinesSpell` | 덩굴 성장 |
| `VolleySpell` | 화살 일제 사격 |
| `ZapSpell` | 겨냥 블록 즉시 파괴 |

### 4-3. `.buff.*` (30)

| 클래스 | 하는 일 |
|:--|:--|
| `ArmorSpell` | 마법 갑옷 착용 |
| `CarpetSpell` | 발밑에 따라다니는 카펫 (점프하면 올라가고 웅크리면 내려감) |
| `ClaritySpell` | 지속 중 주문의 reagent 비용 감소 |
| `DamageEmpowerSpell` | 주문 피해량 증폭 |
| `DodgeSpell` | 날아오는 파티클 투사체를 순간이동으로 회피 |
| `DummySpell` | **아무것도 안 한다.** 지속형 이펙트 껍데기 — `position: buff` 실험의 정석 |
| `EmpowerSpell` | 지속 중 주문 위력(spell power) 배율 증가 |
| `FlamewalkSpell` | 주변 대상을 점화 |
| `FrostwalkSpell` | 발밑 물을 얼린다 (크기 조절 가능한 Frost Walker) |
| `GillsSpell` | 수중 호흡 |
| `HasteSpell` | 달리기 속도 대폭 증가 |
| `ImpactRecordSpell` | 자신을 겨냥한 주문 이름을 문자열 변수에 기록 |
| `InvisibilitySpell` | 완전 투명 |
| `InvulnerabilitySpell` | 지정 damage type에 무적 |
| `LifewalkSpell` | 지나간 자리에 꽃·풀 |
| `LightwalkSpell` | 지나간 자리를 밝힘 |
| `LilywalkSpell` | 수련잎으로 물 위를 걷는다 |
| `ManaRegenSpell` | 마나 회복률 증가 |
| `MinionSpell` | 미니언 소환 (`beneficial: true` 주문으로 겨냥 가능) |
| `ReachSpell` | 원거리 블록 설치·즉시 파괴 |
| `ReflectSpell` | 주문 반사 |
| `ResistSpell` | 지정 피해원에 대한 피해 감소 |
| `SeeHealthSpell` | 대상 체력바 표시 |
| `SpellHasteSpell` | 쿨다운·캐스트 타임 조절 |
| `StealthSpell` | 몹의 어그로 대상에서 제외 (주문 타기팅과는 무관) |
| `StonevisionSpell` | 주변 블록을 클라이언트에서만 다른 블록으로 |
| `WalkwaySpell` | 앞서 깔리는 마법 길 (불안정 — `WindwalkSpell` 권장) |
| `WaterwalkSpell` | 수면 위 부양 |
| `WindglideSpell` | 겉날개 없는 활공 |
| `WindwalkSpell` | 크리에이티브식 비행 |

### 4-4. `.command.*` (14)

| 클래스 | 하는 일 |
|:--|:--|
| `AdminTeachSpell` | 권한 노드로 주문 지급 (**콘솔 전용**, 안전검사 최소) |
| `BindSpell` | 손의 아이템에 주문을 바인딩 |
| `ForgetSpell` | 플레이어에게서 주문 제거 |
| `HelpSpell` | 주문의 시전 정보 출력 |
| `ImbueSpell` | 아이템에 임시 시전 능력 부여 (권한 없이도 쓸 수 있게) |
| `ItemSerializeSpell` | 손의 아이템을 YAML magic item 으로 저장 |
| `KeybindSpell` | 핫바 슬롯에 주문 바인딩 (손 휘두르기로 시전) |
| `ListSpell` | 아는 주문 목록 |
| `ScrollSpell` | 주문이 걸린 두루마리 제작 |
| `SpellbookSpell` | 우클릭하면 주문을 가르치는 블록 설치 |
| `SublistSpell` | `ListSpell` 과 같되 `spells-to-show` 로 명시 지정 |
| `TeachSpell` | 지정 플레이어에게 주문 전수 |
| `TomeSpell` | 읽으면 주문을 배우는 책 제작 |
| `UnbindSpell` | 바인딩 해제 (`*` 로 전체) |

> 위 세 표의 설명은 각 `wiki/<Name>Spell.md` 의 `# Description` 절에서 추출했다
> (`CarpetSpell` 은 `wiki/BuffCarpetSpell.md` / `wiki/TargetedCarpetSpell.md`,
> `FrostwalkSpell`·`WindglideSpell`·`WindwalkSpell` 은 대소문자가 다른
> `wiki/FrostWalkSpell.md` / `WindGlideSpell.md` / `WindWalkSpell.md` 에 있다).

---

## 5. 계열별 최소 동작 예제

### 5-1. Instant — `.instant.DummySpell`

가장 싼 껍데기. **이펙트만 보고 싶을 때, 변수만 바꾸고 싶을 때** 이걸 쓴다.

```yml
검기_시험:
    spell-class: ".instant.DummySpell"
    description: "이펙트 실험용 껍데기"
    cooldown: 0
    cast-item: stick
    effects:
        소리:
            position: caster
            effect: sound
            sound: entity.player.attack.sweep
            volume: 0.9
            pitch: 1.1
```

변수 조작 용도:

```yml
variablechangedummyspell:
    spell-class: ".instant.DummySpell"
    variable-mods-cast:
        - variable +1
```

> 출처: `wiki/DummySpell.md`

### 5-2. Targeted — `.targeted.DummySpell` / `.targeted.PotionEffectSpell`

**타깃이 필요한 이펙트(`position: target`, `position: line`)를 쓰려면 반드시 이 계열이어야 한다.**

```yml
targeted_dummy:
    spell-class: ".targeted.DummySpell"
    range: 20
    can-target: players,monsters
    obey-los: true
    str-no-target: "겨냥할 대상이 없다."
    effects:
        번개:
            position: target
            effect: lightning
```

실물(셰이커 기본 `spells-regular.yml`):

```yml
blind:
    spell-class: ".targeted.PotionEffectSpell"
    cooldown: 15
    cast-item: blaze_rod
    description: Blind your target.
    range: 20
    type: blindness
    strength: 0
    duration: 200
    targeted: true
    can-target: players
    obey-los: true
    cost: [mana 10]
    str-cost: 10 mana
    str-cast-self: You have blinded %t!
```

> 출처: `wiki/DummySpell.md`, `run/mvt-test/plugins/MagicSpells/spells-regular.yml` (`blind`)

### 5-3. Buff — `.buff.DummySpell`

```yml
buff_dummy:
    spell-class: ".buff.DummySpell"
    duration: 20      # 초. 0 이면 무한
    toggle: false
    effects:
        용암:
            position: buff
            effect-interval: 5   # 틱
            effect: particles
            particle-name: lava
            horiz-spread: 0.3
            vert-spread: 0.3
            speed: 0.2
            count: 8
```

버프 전용 옵션 (`wiki/Buff-Spell.md`):
`duration`(초, 0=무한) · `toggle`(기본 `true`) · `targeted` · `real-time-duration`(4.0 Beta 17~) ·
`power-affects-duration`(기본 `true`) · `spell-on-end` · `end-spell-from-target` · `str-fade` ·
`cancel-on-{join,move,death,logout,teleport,spell-cast,take-damage,give-damage,change-world}` ·
`cancel-affects-target` · 그리고 **Uses** 계열 `num-uses`, `use-cost`, `use-cost-interval`,
`spell-on-use-increment`, `spell-on-cost`.
버프가 끝나면 `disabled` 위치 이펙트가 재생된다.

실물:

```yml
armor:
    spell-class: ".buff.ArmorSpell"
    duration: 30
    cast-item: book
    num-uses: 15
    helmet: 'golden_helmet{enchants: {blast_protection: 1, respiration: 1}}'
    ...
```

### 5-4. Command / General — 하위 주문 묶기

```yml
검기_연격:
    spell-class: ".MultiSpell"
    always-granted: true
    cast-item: stick
    spells:
      - 검기_1타          # 기본 partial 모드
      - 검기_3타(mode=full)  # full 모드 강제

검기_1타:
    spell-class: ".instant.DummySpell"
    helper-spell: true
```

> 출처: `wiki/Spell-chaining.md`

---

## 6. 실전 함정

### 6-1. `helper-spell` 이 실제로 하는 일

`helper-spell: true` 인 주문은:
- `ListSpell` 에 안 뜨고 `BindSpell` 로 바인딩 안 된다.
- **권한 노드를 아예 생성하지 않는다.** 보통 주문 하나가 권한 5개를 만들어 로드 시간을 잡아먹는데, 그게 사라진다.
- **모든 플레이어가 주문서(spellbook) 없이 시전할 수 있다.**
- 단, `/cast` 로는 못 부른다 — `magicspells.command.cast.self.helper` 권한이 기본 미지급이기 때문.
  이 권한을 주더라도 `can-cast-by-command: false` 로 막을 수 있다.
- **4.0 Beta 17 이후** helper 로 표시된 PassiveSpell 은 전 플레이어에게 발동한다.

**규칙: 진입점(main) 주문 하나만 남기고 나머지 하위 주문은 전부 `helper-spell: true` 로 둬라.**
그러면 권한은 진입점 하나만 관리하면 된다.

> 출처: `wiki/Spell-chaining.md` (Helper spells / Sub-spells),
> jar 대조: `Spell.helperSpell` (`boolean`)

### 6-2. 플레이어가 시전할 수 있는 조건

권한 경로는 네 갈래 (`wiki/Permissions.md`):
1. `always-granted: true` — 전원에게 자동 전수.
2. `helper-spell: true` — 권한 검사 자체를 건너뛴다 (§6-1).
3. `magicspells.grant.<permission-name>` — 자동 지급. **단 스펠북을 리로드해야 반영**된다
   (`/ms reload <player>` 또는 재접속). 권한을 빼도 주문은 남는다 — 빼려면 `ForgetSpell`.
4. `magicspells.tempgrant.<permission-name>` — 리로드 불필요, 권한을 빼면 즉시 사라진다. **운영 중 토글에 적합**.

추가로 `magicspells.learn.<permission-name>` 이 있어야 배울 수 있고,
`magicspells.cast.<permission-name>` 이 있어야 시전할 수 있다.
`permission-name` 은 기본이 internal name 이지만, **여러 주문이 같은 값을 공유해도 된다** →
기술 묶음 하나에 권한 노드 하나만 두는 데 쓴다.

우리 서버 `general.yml` 에는 `ops-have-all-spells: true` 가 있으므로 **OP는 전부 다 된다** —
권한 설정이 맞는지 검증할 때는 반드시 비-OP 계정으로 확인해야 한다.
`ops-ignore-cooldowns: false` 라 OP도 쿨다운은 받는다.

> 근거: 실물 `run/mvt-test/plugins/MagicSpells/general.yml` 13행, 125행

**와일드카드 경고**: `*` 나 `magicspells.*` 를 주지 마라. `magicspells.notarget`(어떤 타깃 주문의
대상도 되지 않음) 같은 원치 않는 권한이 딸려 온다.

### 6-3. 하위 주문의 cast mode

하위 주문은 기본적으로 `partial` 모드로 돌아 일부 처리를 건너뛴다 —
그래서 `str-cast-self` 같은 메시지가 **안 나온다**. 필요하면 `주문이름(mode=full)` 로 강제한다.

> 출처: `wiki/Spell-chaining.md`, `wiki/Cast-arguments.md`

### 6-4. 그 밖

- `cooldown` 은 **초**, `cast-time`·`effect-interval`·`delay` 는 **틱**. 섞지 마라.
- 하위 주문 참조는 **internal name(YAML 키)** 로 한다. `name` 이나 `aliases` 로는 안 된다.
- `defaults.yml` 에 `"com.nisovin.magicspells.Spell": {cooldown: 5}` 를 넣으면 **모든 주문**에
  적용된다. 디버깅 중 "왜 쿨다운이 걸리지" 의 흔한 원인.
- 필드 타입이 `ConfigData<T>` 가 아닌 옵션(`charges`, `server-cooldown`, `bindable`, `debug` 등)은
  expression 을 못 쓴다 (§3-6).

---

## 7. 관련 문서

- `03-spell-effects.md` — `effects:` 절 전체 (위치·타입·필드)
- 위키 원본: `scratch/msdocs/wiki/` (558 파일)
  - `Spell-Configuration.md` — 공통 옵션 정본
  - `Spell-List.md` — 클래스 색인
  - `Spell-chaining.md` — main/sub/helper
  - `Permissions.md` · `Modifiers.md` · `Variable-Modification.md` · `Expression.md`
  - `Valid-Target-List.md` — `can-target` 값 목록
  - `Other-Data-Types.md` — reagent / vector / cast item / color 표기법
