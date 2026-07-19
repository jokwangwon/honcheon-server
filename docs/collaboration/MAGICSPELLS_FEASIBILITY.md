# MagicSpells 플러그인 도입 타당성 조사

> 트랙: 조사·설계 리포트 전용 (구현/설치 금지). MagicSpells 설치·jar 변경·config 변경·git·서버 조작 없음.
> 대상 서버: Paper `api-version: '1.21'` (운영 1.21.11) · 자체 무공 플러그인 **HoncheonMVT**.
> 작성 기준: 실제 코드/설정 인용 (파일:줄) + MagicSpells 공식 문서 (URL). 지어낸 수치 없음.

---

## 0. 결론 먼저 (TL;DR)

- **우리 서버는 이미 완성도 높은 자체 무공 시스템을 가지고 있다.** 근접 콤보·격(格) 사다리·투사체(발출)·범위 오의·버프(호신강기)·돌진(경공)·판정(2d6+숙련)·자원(내력)·연출(파티클+3D 디스플레이 엔티티)이 전부 config 등록제로 돌아간다.
- MagicSpells가 제공하는 것(투사체·AoE·버프·텔레포트·소환·마나·완드·YAML 스펠) 중 **상당수는 우리가 이미 자체 구현**했고, 겹치지 않는 것(소환·순간이동·복잡한 조건부 스펠 체인)만 순수 이득이다.
- **입력 예산이 완전히 포화**되어 있다 (좌/우클릭·Shift 조합·F·핫바 6~9가 전부 무공에 배정됨 — `mc_action_mapping.md`). MagicSpells를 완드/우클릭으로 얹으면 **같은 입력을 두 시스템이 다툰다.**
- **권고: 안 (A) 이펙트·유틸·소환 한정 도입, 또는 도입 보류.** (근거는 §5·§6 — MagicSpells 연구 회신 반영 후 확정)

---

## 1. 현행 무공 시스템 (HoncheonMVT) — 구조 요약

### 1.1 아키텍처 3층
무공은 **규칙 / 배선 / 연출**의 세 층으로 갈라져 있고, 세 층 모두 `config/*.yml` 등록부가 정본이다 (코드가 config보다 앞서지 않는다).

| 층 | 클래스 | 역할 |
|---|---|---|
| 규칙 (순수, Bukkit 의존 0) | `SkillEngine.java` (2937줄) | 무공 카탈로그·격 사다리·내력·판정·프레임을 config에서 읽어 계산. 부수효과 없음(테스트 가능) — `SkillEngine.java:19-35` |
| 판정·배선 | `SkillListener.java` (4475줄) | 조작(이벤트)을 실제 시전으로. 히트박스·피해·경직·넉백·안전지역 게이트 — `SkillListener.java:41-62` |
| 절기(오의 직전) 시전 | `SkillCast.java` (850줄) | 삼문(承·間·虛) 게이트로 발동하는 파생 절기 — `SkillCast.java:40-66` |
| 연출 (3D) | `SkillDisplay.java` | 파티클 위에 얹는 디스플레이-엔티티 층(날의 기·참격선·나는 물건) — `SkillDisplay.java` 클래스 doc |

핵심 엔진 4개는 core에 위임: `InternalEnergyEngine`(내력)·`QiManifestationEngine`(격)·`JudgmentEngine`(판정)·`EquipmentEngine`(장비) — `SkillEngine.java:64-68, 223-226`.

### 1.2 무공이 어떻게 **정의**되는가 (2층 config 구조)
`config/skills.yml`이 카탈로그(등급·요구 경지·계보·무기 계열), `config/skill_mechanics.yml`이 히트박스·프레임·경직·쿨다운·콤보를 담는다 — `skills.yml` schema 주석 (`action_data: "... = skill_mechanics.yml skills[id] (2층 구조 — 카탈로그 30종 전수 정의)"`).

- **무기 계열 결합**: `skills.yml weapon_classes` — 검법=검, 도법=도, 권법/장법=[맨손,권갑] … 무공은 자기 계열의 병장기를 들었을 때만 온전히 나가고, 아니면 "맨 기술"로 다운캐스트.
- **비용 정책**: 초식 자체는 내력 0 — 내력을 먹는 것은 **격(格)**이다 (`skills.yml cost_policy`, `combat.yml internal_energy.skill_cost_rule`).

### 1.3 어떻게 **발동·판정**되는가 (입력 매핑 — 예산 포화 상태)
`docs/design/mc_action_mapping.md` 1장이 전 입력을 못박아 두었고, `SkillListener`/`SkillCast`가 그대로 배선했다:

| 입력 | 배정된 무공 동작 | 코드 근거 |
|---|---|---|
| 좌클릭 | 기본 무공 콤보 (육합검 3타 등) | `SkillListener.java:1344` (LEFT_CLICK_AIR) |
| 우클릭 (Shift 없이) | 방어 선언/패링 (active_guard) **또는** 겨눈 절기 시전 | `SkillListener.java:1333` · `SkillCast.java:271-291` |
| Shift+우클릭 | 격 태세 순환 (외공→발경→검기→강기) | `SkillListener.java:49`, `1334` |
| Shift+좌클릭 (검기+ 태세) | 기 발출(쏨) — 검기 참격 / 강기 포 | `SkillListener.java:2085-2100` |
| Shift 탭+방향 | 회피 (dodge) | `mc_action_mapping.md:16` |
| F (스왑) | 오의 (발동권 시에만) | `mc_action_mapping.md:19` |
| 핫바 6~9 | 절기 겨눔 4슬롯 | `SkillCast.java:82-83, 229-267` |
| 점프(달리며) | 경공 (이동) | `mc_action_mapping.md:24` |

**판정 규약**: 플레이어는 2d6+실행치를 굴리고 NPC는 기댓값(+7 고정)으로 선다 (`SkillListener.java:74-79`). 실행치 = 숙련 + 무기 판정 보정 + 경지차 보정 (`SkillCast.java:527-536`). 히트박스 기하는 **한 벌뿐** — `SkillListener.inArc/inLine/inCone/inCircle`을 판정도 눈(디버그)도 같이 부른다 (`SkillCast.java:579-589`). "보이는 것 = 맞는 것" 불변식.

### 1.4 자원 (내력) 과 격
- **내력(內力)** = 후천진기 자원 풀. 풀 = `round(내공 × (내공+1)/2 × 3)` (`internal_energy.yml:86-88`). 회복은 운기조식(정지)·조식(전투 중 격 안 실은 합) — `SkillEngine.java:86-95`.
- **격(格) 사다리**: 외공기 → 발경 → 검기 → 강기 … 격이 내력을 먹는다. 발출형은 1회 대량 소모(`qi_manifestation.yml:30-31` 검기_참격 cost 3, 강기_포 cost 6).
- **HUD**: 내력/내공은 **보스바**로 표시 (`EnergyBossBar`, `SkillListener.java:88-93`). ★ 최근 사용자 확정(2026-07-15): **XP바 = v3 경험/레벨**로 이사, 내력은 XP바에서 보스바로 옮겼고 **바닐라 XP는 전면 절연**(XpEconomyGuard).

### 1.5 이미 자체 구현된 "스펠"급 능력 (중요 — MagicSpells와의 중복 판단 근거)
자체 시스템이 이미 커버하는 범위:

- **투사체/원거리**: 발출(쏨) — 검기_참격(선/투사체), 강기_포(관통 투사체) `qi_manifestation.yml:30-31`.
- **범위(AoE)**: 오의 — 원 히트박스 반경 최대 10, 다단 최대 13~24타 (`ultimate_arts.yml` 매화만개 원/6/5타, 이십사수 원/5/8타, 선/24m 등).
- **선형 빔**: 선(width·length), 최장 24m (`ultimate_arts.yml:216`).
- **버프/방어**: 호신강기(두름_몸)·슈퍼아머(RESISTANCE)·경직면역 — `SkillCast.java:453-456`.
- **돌진/이동**: 돌(type) 순간 전진, 경공 — `SkillCast.java:463-466`.
- **연출**: 파티클 예산 시스템 + 3D 디스플레이 엔티티(참격선·날의 기·나는 물건) + inks 색 사다리(격이 오를수록 화려) — `SkillDisplay.java`, `skill_motion.yml`.

### 1.6 확장 지점 — 새 무공 추가가 지금 얼마나 쉬운가
- **쉬움 (config만)**: 기존 계열/격/판정 틀 안의 새 무공·절기·오의는 `skills.yml`+`skill_mechanics.yml`(+선택 `skill_motion.yml`)에 등록하면 코드 수정 0으로 추가된다. 절기는 `skill_mechanics.yml`의 `art:true` + `cast_gate`(삼문)만 적으면 `SkillCast`가 자동 등록 (`SkillCast.java:145-187`).
- **어려움 (코드 필요)**: **새로운 발동 문법**(예: 채널링 스펠, 순간이동, 소환, 스펠 체인/콤보 트리거)이나 **새 히트박스 기하**는 자바를 손봐야 한다. 히트박스는 4종(호·선·원·시)으로 고정, 입력은 위 표대로 포화. → **이 "어려움" 영역이 MagicSpells가 메울 수 있는 후보 지점**이다.

---

## 2. MagicSpells 능력·요구 (공식 문서 기반)

> 정체: `TheComputerGeek2/MagicSpells` (원작 nisovin의 계승판). 표어 "Magic without writing Java" — **Paper 전용**, YAML만으로 스펠 제작.
> ★ 혼동 주의: elBukkit의 별개 플러그인 **"Magic"(MagicPlugin)**과 다르다. (`magic_wand`/Vault 브러시 등은 그쪽 것.)

### 2.1 스펠 종류 (스톡 클래스 ~150+) — [Spell-List wiki](https://github.com/TheComputerGeek2/MagicSpells/wiki/Spell-List)
YAML에서 `spell-class`를 고르고 옵션을 얹는다.

| 범주 | 예시 클래스 | 우리 시스템에 있나? |
|---|---|---|
| 즉발(Instant) | `ConjureSpell`(아이템 지급)·`LeapSpell`·`VelocitySpell`·`ForcepushSpell`·`ProjectileSpell`·`ParticleProjectileSpell`·`WallSpell`·`GateSpell`·`RecallSpell`·`RitualSpell`·`SteedSpell` | 일부 (돌진=경공/돌, 밀치기=넉백) |
| 타겟(Targeted) | `DamageSpell`·`FireballSpell`·`ExplodeSpell`·`LightningSpell`·`DotSpell`·`TeleportSpell`·`BlinkSpell`·`ShadowstepSpell`·`StunSpell`·`DisarmSpell`·`SummonSpell`·`HomingMissileSpell`·`NovaSpell`·`AreaEffectSpell`·`ChainSpell`·`VolleySpell` | **투사체·AoE·다단·경직은 있음** / 순간이동·소환·체인·홈잉은 **없음** |
| 버프(Buff) | `ArmorSpell`·`HasteSpell`·`InvisibilitySpell`·`InvulnerabilitySpell`·`ReflectSpell`·`ResistSpell`·`StealthSpell`·`FlySpell`·`MinionSpell` | 일부 (호신강기·슈퍼아머·경공) |
| 명령/전수 | `TeachSpell`·`BindSpell`·`TomeSpell`·`ScrollSpell`·`SpellbookSpell`·`ImbueSpell` | 다름 (우리는 스승/비급 습득) |
| 메타/복합 | `MultiSpell`·`TargetedMultiSpell`·`RandomSpell`·`MenuSpell`·`ExternalCommandSpell`·`PassiveSpell` | **복합 스펠 체인은 없음** |

→ 투사체·AoE·버프·순간이동·소환·DoT·유틸·복합 스펠이 전부 스톡. **우리가 없는 것: 소환(미니언)·순간이동/포탈/리콜·스펠 체인·복합 조건부 스펠.**

### 2.2 발동 — [Magic-Items](https://github.com/TheComputerGeek2/MagicSpells/wiki/Magic-Items) · [PassiveSpell](https://github.com/TheComputerGeek2/MagicSpells/wiki/PassiveSpell)
1. **명령**: `/cast <spell>`.
2. **완드/캐스트 아이템**: `magic-items:`로 정의, 스펠별 `cast-item`/`left-click-cast-item`/`right-click-cast-item`. 전역 토글 `cast-with-left-click`(기본 **true**)·`cast-with-right-click`(기본 false)·`cast-on-animate`.
3. **패시브 트리거**(`PassiveSpell`) — 다른 플레이어 행동에서 발화. 트리거 ~90종: `leftclickitem, rightclickitem, rightclickentity, takedamage, givedamage, fataldamage, kill, death, jump, startsneak, startsprint, playermove, input, ticks, manachange` 등. **좌/우클릭·피해·이동·틱을 전부 후킹**.

### 2.3 자원 — [Mana-Configuration](https://github.com/TheComputerGeek2/MagicSpells/wiki/Mana-Configuration)
- **마나**: `mana.yml` 네이티브 풀. `default-max-mana`(100)·`default-regen-amount`(5)·`default-regen-interval`(20틱). 퍼미션별 **마나 랭크**로 티어링.
  - **HUD**: `show-mana-on-experience-bar`(**기본 true — 바닐라 XP바를 덮는다**)·`show-mana-on-action-bar`·`show-mana-on-hunger-bar`.
  - **끌 수 있다**: `enable-mana-system: false` → 마나 대신 MagicSpells **변수(variables)**로 비용 처리.
- **재료(reagents)**: 스펠별 `cost:` — **마나·체력·허기·경험치·아이템 내구·인벤 아이템·변수** 조합.
- **쿨다운**: 스펠별 `cooldown`(초, 범위 지원)·`charges`(N회 후 쿨)·`cast-time`(선딜 틱).

### 2.4 이펙트 — [Spell-Effects](https://github.com/TheComputerGeek2/MagicSpells/wiki/Spell-Effects)
YAML만의 시청각 층: `particles`·`particlecloud`·`sound`·`entity`·`armorstand`·`fireworks`·`itemspray`·**EffectLib**(`effectlib`/`effectlibline`/`effectlibentity` — 기하/애니메이션)·`lightning`·`explosion`·`nova`·`actionbartext`·**`bossbar`**·`title`·`toast`·`swinghand`·`itemcooldown`. 부착 위치: caster·target·startpos/endpos·line/trail·buff·orbit. EffectLib 번들.

### 2.5 조건·타겟 — [Modifiers](https://github.com/TheComputerGeek2/MagicSpells/wiki/Modifiers)
**Modifiers** = `(condition) [var] (action) [var]`. 조건 200+종(날씨·시각·바이옴·광량·체력·마나·물약·자세·지역·태그·변수·퍼미션·확률·쿨다운). 액션: `require`/`deny`/`power`(위력 배율)/`cooldown`/`castinstead`. LoS 옵션·PvP 게이팅(`check-world-pvp-flag`·`check-scoreboard-teams`)·**No-Magic Zones**(구역 시전 금지). → **조건부 게이팅은 우리 삼문보다 어휘가 넓다.**

### 2.6 연동
- **EffectLib**(번들)·**PlaceholderAPI**(전용 PAPI 확장 + 자체 placeholder/variable). 
- **Vault**: MagicSpells에는 **확인 안 됨**(Vault 브러시는 별개 MagicPlugin). 경제 비용은 reagents/variables로.
- **전투 플러그인 1급 연동**: 문서상 없음 — Bukkit 피해 이벤트·PvP 플래그로 간접.

### 2.7 버전·유지보수 — [Releases](https://github.com/TheComputerGeek2/MagicSpells/releases) · [Modrinth](https://modrinth.com/plugin/magicspells)
- **Paper 전용**. 최신 **`4.0-Beta-18` (2026-01-16)** — **MC 1.21.10·1.21.11 테스트 확인**. → **우리 1.21.11 호환 O.**
- Modrinth 지원: 1.21.10–1.21.11, 1.21.3–1.21.8, 1.21–1.21.1, 1.20.x, 1.19.x.
- 대략 6~7개월마다 릴리스(최신 ~6개월 전), Discord 활성. **★ 다만 버전 라벨이 "Beta" (4.0 라인 pre-1.0)** — 의존성 리스크로 명기.

### 2.8 성능
공식 벤치 없음. 메인 스레드 실행. 커뮤니티 일화상 무거운 축에 언급. 비용 동인: 파티클 대량·`ticks`/`playermove` 패시브(플레이어당 매 틱)·홈잉/오빗·다수 PassiveSpell 리스너 → 플레이어 수 × 활성 이펙트. 완화는 config측(파티클·주기·쿨다운). **정량 수치 없음 — 타겟 하드웨어에서 spark 프로파일 필요.**

### 2.9 YAML로 **못 하는 것** — [Dev-API](https://github.com/TheComputerGeek2/MagicSpells/wiki/Dev-API)
새 근본 동작은 Java: 새 스펠 메커니즘(→`Spell` 확장)·새 조건·새 패시브 트리거·새 변수 타입·새 이펙트 렌더러·맞춤 타겟팅. "기존 스펠을 조건·이펙트·트리거로 조합"이면 YAML로 충분, 진짜 새로운 것은 Java 애드온.

### 2.10 ★ 리스너·자원 충돌 (우리 자체 전투와의 정면) — [General-Config](https://github.com/TheComputerGeek2/MagicSpells/wiki/General-Configuration)
- **자체 리스너 등록 O**: 캐스트 아이템은 **좌클릭/우클릭/스윙 애니메이션**을 가로챈다(`cast-with-left-click` 기본 true). 패시브 `leftclickitem`/`rightclickitem`/`takedamage`/`givedamage`/`kill`은 **상호작용·피해 파이프라인을 후킹**. → 우리 좌/우클릭 전투 핸들러와 **같은 이벤트를 두 플러그인이 받는다.**
- **자체 자원**: 마나 풀이 기본으로 **바닐라 XP바를 덮는다**. 스펠은 **체력**을 reagent로 소모/회복 가능(HP도 만진다).

---

## 3. 통합 설계 안 A / B / C

### 안 (A) — 이펙트·유틸·소환만 MagicSpells (보조), 무공 판정은 자체 유지
**범위**: 무공 근접·격·발출·오의·판정은 100% 자체. MagicSpells는 **우리가 없는 것**만 — 소환(미니언/영수)·순간이동/리콜/포탈/게이트·환경 스펠(날씨/시간)·희귀 유틸 버프. 발동은 `/cast` 또는 우리 입력과 겹치지 않는 전용 아이템/패시브 트리거로 한정. `enable-mana-system: false`로 마나·XP바 충돌 제거, 비용은 변수/재료로.

- 내공 연동: **선택적**. 유틸 스펠이 내력을 안 쓰면 연동 불필요(가장 깨끗). 내력을 쓰게 하려면 PAPI/변수로 우리 내력을 노출→reagent로 읽는 **Java/PAPI 브리지** 필요.
- 참격선/판정/AttackRhythm 충돌: **없음** — MagicSpells가 좌/우클릭·근접 판정을 안 건드림(전용 아이템·명령만).
- 밸런스 이원화: **낮음** — 유틸·이동·소환은 전투 위력표(combat.yml) 밖.
- 마이그레이션 비용: **낮음** — 자체 코드 무변경, config 추가.
- 헌장 정합: 양호 — 무공/판정/world-reaction 불변. 단 소환/순간이동이 무협 세계관에 맞는지는 별개 판단.

### 안 (B) — 특정 오의/장풍(투사체·범위)만 MagicSpells
**전제 검증 실패**: "자체가 약한 부분(투사체·범위)"이라는 전제가 **코드상 거짓**이다. 우리는 이미 발출(검기_참격 선/투사체, 강기_포 관통 투사체 — `qi_manifestation.yml:30-31`)과 원/선 AoE 오의(반경 10·다단 24 — `ultimate_arts.yml`)를 가진다.
- → MagicSpells로 투사체/범위기를 넣으면 **기존 능력과 중복** + 아래 충돌을 전부 부담: 판정 이원화(2d6 vs 스펠 데미지)·world-reaction 우회·밸런스 두 벌·입력 경합.
- 유일한 명분: 홈잉/체인/노바 등 **우리에게 없는 투사 거동**. 그것만이면 (A)의 하위집합으로 흡수하는 게 낫다.
- **약한 안.** 채택 시 마이그레이션 中·리스크 高.

### 안 (C) — 전면 이관 (무공을 MagicSpells 스펠로 재정의)
- 자체 SkillEngine/Listener/Cast/Display(총 ~8,200줄) + config 규칙 사장.
- **핵심 반증**: `minecraft_port_feasibility.md:26` — *"YAML 설정 12종과 그 규칙이 이 프로젝트의 진짜 자산이고, 그것들은 엔진을 가리지 않는다."* 그 자산을 베타 외부 플러그인 문법으로 재작성하는 것.
- 잃는 것: 격 사다리·조식/내력 수지·삼문 절기·안전지역(B-006)·타격허용(B-119)·수련(training-by-doing)·world-reaction(소문/세력 반응)·참격선 3D 연출·성능 예산 시스템.
- 비용 極高·위험 極高. **비권장.**

---

## 4. 중복·충돌 지점 (핵심)

두 시스템이 같은 자원/입력을 두고 다투는지 — **다툰다**:

| 충돌 축 | 자체 시스템 | MagicSpells | 충돌 성격 |
|---|---|---|---|
| **좌클릭** | 기본 콤보 (`SkillListener.java:1344`) | `cast-with-left-click` 기본 true·패시브 `leftclickitem` | **정면** — 같은 `PlayerInteractEvent`. 이벤트 우선순위/취소로 "누가 클릭의 주인인가" 경합 |
| **우클릭** | 방어/패링·절기 시전 (`SkillListener.java:1333`, `SkillCast.java:271`) | `right-click-cast-item`·패시브 `rightclickitem` | 정면(기본 false라 완화 가능) |
| **자원** | 내력(內力) 풀 — 보스바 (`EnergyBossBar`) | 네이티브 마나 풀 | **이원화** — 두 자원. `enable-mana-system:false` + 변수/PAPI 브리지 필요 |
| **HUD** | XP바=v3 경험/레벨, 내력=보스바 (★2026-07-15 확정, XpEconomyGuard 절연) | 마나 기본으로 **XP바 점령** | **정면** — 반드시 끄거나 재배치 |
| **피해/판정** | 2d6+숙련 vs NPC+7, 히트박스 admit·안전지역(B-006)·타격허용(B-119) (`SkillListener.java:74-79,339`) | `DamageSpell` 등 Bukkit 피해 직접 | **우회** — 스펠 피해가 우리 판정/안전 게이트를 건너뜀. `EntityDamageByEntityEvent`를 우리 `onMelee`(HIGH)가 되잡아 이중 처리 위험 |
| **World-reaction** | 격 목격→소문/세력 반응 (`SkillCast.java:504` `WorldBridge.qiManifested`, `skills.yml public_use_rumor`) | 없음 | **우회** — MagicSpells로 편 무공은 강호가 못 본다(소문 0). 무협 설계의 "공개 사용→소문→세력 반응"이 죽음 |
| **성장** | 숙련=원장/progression, training-by-doing (`SkillCast.java:525`) | 자체 teach/spellbook/마나랭크 | **단절** — MagicSpells 진행이 우리 원장에 안 실림 |
| **연출** | 파티클 예산 + 3D 참격선/디스플레이 엔티티 (`SkillDisplay.java`) | EffectLib 등 자체 이펙트 | 중복(치명적 아님, 둘 다 파티클 예산을 씀 — 합산 부하) |

**요약**: 전투 영역에서 MagicSpells를 얹으면 **입력·자원·HUD·판정·world-reaction·성장 6축이 전부 경합/우회**한다. 비전투 유틸 영역(소환·순간이동·환경)에서는 경합이 거의 없다.

---

## 5. 권고

**권고: 안 (A)로 최소 도입하되, 범위를 "우리에게 없는 비전투 유틸"로 엄격히 한정한다. 전투 무공에는 MagicSpells를 쓰지 않는다. 도입 자체를 보류하는 선택도 정당하다.**

근거:
1. **전투 스펠 공간은 이미 자체로 충분히 덮여 있다** — 투사체·빔·AoE·다단·버프·돌진·경직이 전부 있다(§1.5). MagicSpells의 전투 가치는 대부분 **중복**이다. 안 (B)의 전제("자체가 투사체에 약함")는 코드상 거짓(§3-B).
2. **전투에 얹으면 6축 충돌**(§4) — 특히 world-reaction 우회는 무협 설계의 근간(소문/세력)을 깨고, 판정 우회는 안전지역/타격허용 계약을 무력화한다. 이는 config 토글로 못 막는다(설계적 단절).
3. **순수 이득은 우리가 없는 것뿐** — 소환·순간이동/리콜/포탈·환경 스펠·200+ 조건 modifier. 이것만 (A)로 취하면 충돌 없이 세계 유틸(귀환·소집·기연 연출)이 는다.
4. **비용 대비**: 내력을 스펠에 물리려면 PAPI/변수 브리지(Java) 필요 → (A)에서 내력을 안 쓰는 유틸만 고르면 그 비용도 0.
5. **의존성 리스크**: 4.0-**Beta** 라인 + Paper 전용 + 메인 스레드 파티클 부하. 우리 성능 예산(performance.yml)과 별도로 관리해야 한다.

즉 — **자체 무공은 그대로 두고**, MagicSpells는 (도입한다면) **"세계 유틸 스펠 엔진"**으로만: 문파 소집·귀환진·기연 소환·의식 연출 등, 내력/판정/소문과 무관한 영역. 그조차 필수는 아니며(그런 유틸도 자체 config로 만들 여지 있음), **"당장 도입 안 함"도 합리적 결론**이다.

---

## 6. 미결 / 사용자 결정점

1. **도입 여부 자체** — (A) 최소 유틸 도입 vs 보류. (전투 도입=B/C는 비권장.)
2. **범위 확정** — 도입 시 어떤 유틸? (소환/순간이동/환경 중 무협 세계관에 맞는 것만.) 무협에서 "순간이동/소환"이 세계관 위반인지 사용자 판단 필요.
3. **내력 연동 여부** — 유틸 스펠이 내력을 소모해야 하나? Yes면 PAPI/변수 브리지(Java 작업) 승인 필요. No면 무비용.
4. **입력 채널** — 도입 시 `/cast` 전용 vs 전용 아이템. 좌클릭 캐스트는 반드시 off(`cast-with-left-click:false`)로 우리 콤보 보호.
5. **마나 처리** — `enable-mana-system:false` 확정(XP바·마나 충돌 제거).
6. **성능 예산** — MagicSpells 파티클/패시브를 우리 performance.yml 예산과 어떻게 합산·상한할지.
7. **베타 의존성 수용** — 4.0-Beta 플러그인을 운영에 넣는 리스크 허용 여부.

---

### 부록 — 인용 출처
- 자체 코드: `server-mvt/src/main/java/com/honcheon/mvt/{SkillEngine,SkillListener,SkillCast,SkillDisplay}.java`
- 자체 config: `config/{skills,skill_mechanics,internal_energy,qi_manifestation,ultimate_arts,combat}.yml`
- 자체 설계: `docs/design/{mc_action_mapping,minecraft_port_feasibility,platform_decision}.md`
- MagicSpells: [GitHub wiki](https://github.com/TheComputerGeek2/MagicSpells/wiki) (Spell-List·Magic-Items·PassiveSpell·Mana-Configuration·Spell-Effects·Modifiers·General-Configuration·Dev-API) · [Releases](https://github.com/TheComputerGeek2/MagicSpells/releases) · [Modrinth](https://modrinth.com/plugin/magicspells)

---

## ★ 사용자 결정 (2026-07-19): 보류 — 자체 무공 확장으로 대신

MagicSpells **도입하지 않는다.** 스킬 확장성은 **자체 무공 시스템(SkillEngine·config 등록제)을 늘리는 것**으로 해결한다.
근거: 조사 결론대로 (a) 자체 시스템이 이미 투사체·빔·범위 오의·버프·돌진을 구현해 스펠급 능력이 있고,
(b) 입력·자원(내력)·HUD·판정·세계반응·성장 6축이 이미 통합돼 있어 MagicSpells를 얹으면 이원화·충돌만 는다.
**새 무공/오의는 skill_motion.yml·combat.yml 등록제 확장으로 추가한다** (Java 재작업 없이 config로).
이 문서는 재론 방지용 사료 — MagicSpells 재검토 금지.
