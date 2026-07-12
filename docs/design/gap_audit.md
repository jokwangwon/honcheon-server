# 공백 감사 — 설계가 약속한 것이 실제로 굴러가는가

> 방법: 네 겹을 따로 묻는다 — **설계**(docs/design) → **config**(*.yml) → **엔진**(core·mvt·bot이 그
> 규칙을 *로드하고 쓰는가*) → **플레이어**(그 코드를 부르는 진입점이 있는가).
> 로드만 하고 안 쓰면 엔진 ❌. 등록만 되고 아무도 안 부르면 플레이어 ❌ — **죽은 코드**다.
> 기계 재실행: `python3 tools/gap_audit.py`
>
> **스냅숏**: 커밋 `84baced` (2026-07-12). 작업자 5명이 동시에 일하는 중이라 트리가 움직인다 —
> 감사 중에도 dead-code 수가 129 → 118로 줄었다. 진행 중인 것은 **🔨 작업 중**으로 표시했다.
>
> **이 문서의 값은 불편한 진실에 있다.** 반쪽을 완성이라 적지 않았다.

---

## 1. 판정표

판정: ✅ 완성 / ⚠ 반쪽 / ❌ 없음

| 항목 | 설계 | config | 엔진 | 플레이어 체감 | 판정 | 다음 한 걸음 |
|---|---|---|---|---|---|---|
| 판정·전투(2d6) | ✅ | ✅ | ✅ | `/사냥`·`/비무` | ✅ | — |
| 기 발현(격) | ✅ | ✅ | ✅ | 실전 배선 | ✅ | — |
| 3D 모션(참격선) | ✅ | ✅ | ✅ | 보인다 | ✅ | 요약 블록 수치 갱신(§4) |
| 지형 계층 | ✅ | ✅ | ✅ | 산·사막·설원 | ✅ | — |
| 세계 순도 | ✅ | ✅ | ✅ | 스폰 통제 | ✅ | — |
| 인구(populace) | ✅ | ✅ | ✅ | 28인 일과 | ✅ | — |
| 죽음과 유산 | ✅ | ✅ | ✅ | `/구조`·`/전장`·상속 | ✅ | MC 측 죽음 절차 0 (§5-c) |
| 소문·세력·지역 | ✅ | ✅ | ✅ | `/소문`·`/명분` | ✅ | region_state 임계 미배선 |
| **경공(輕功)** | ✅ | ✅ | **❌** | **❌** | **❌** | **자바에 한 줄도 없다 — §3-①** |
| **방어구 채널** | ✅ | ✅ | ⚠ 테스트만 | **❌** | **❌** | 감쇄·회피가 피해식에 없다 — §3-① |
| **세월(시간 비용)** | ✅ | ✅ | **❌** | **❌** | **❌** | 달력 15배가 1:1 — §3-② |
| **기연(fortune)** | ✅ | ✅ | **❌ yml 미로드** | ⚠ 하드코딩 1종 | **⚠** | config 패치가 코드에 없다 — §3-③ |
| **혈채(blood_debt)** | ✅ | ✅ | ✅ | **❌ 호출자 0** | **❌** 🔨 | Bridge·GameListener 배선 |
| **파티·협동** | ✅ | ✅ | ✅ | **디버그 1개** | **❌** | 결성·합격진·배신 전무 |
| **문파 생활** | ✅ | ✅ | ⚠ 1개 절 | ❌ | **❌** | 공적·봉록·문규·파문 미구현 |
| **의뢰 생성기** | ✅ | ✅ | ⚠ 사다리만 | ⚠ 정적 5종 | **⚠** | 세계 상태→의뢰가 없다 |
| 나이·수명 | ✅ | ⚠ 일부 | ⚠ 생성만 | 시작 시점만 | **⚠** | 나이가 자라지 않는다 |
| 경제 | ✅ | ✅ | ⚠ | `/전장`·상점 | **⚠** | 면제·인출지연·봉인 미구현 |
| LLM 서술 | ✅ | ✅ | ⚠ model 1줄 | 서사 렌더 | **⚠** | 캐싱·스키마·프리페치 0 |
| 성정 시험 | ✅ | ✅ | ⚠ | `/시작` | **⚠** | `scoring` 절 미판독 |
| **장(章) 사건** | ✅ | ✅ | **❌** | **❌** | **❌** | 아무도 안 읽는다 |
| **마을 등급** | ✅ | ✅ | **❌ 손복사** | 고정 중촌 | **❌** | yml→조성기 배선 |
| **용어 정책** | ✅ | ✅ | **❌** | ❌ | **❌** | 치환표 미적용 |
| **인터페이스** | ✅ | ✅ | **❌** | — | **❌** | 결정 기록 — 무해 |
| 새외무림 | ✅ | ✅ | ❌ | ❌ | **❌** | 데이터만, 자바 0건 |
| MC↔봇 신원 접합 | ✅ | ✅ | ⚠ 봇 반쪽 | ❌ | **⚠** 🔨 | 코드 발급자가 없다 |

---

## 2. 가장 큰 구멍 다섯 — 무엇부터 채워야 세계가 세계가 되는가

### ① 경공이 없다 — 그리고 그 부재가 방어 체계를 통째로 무너뜨린다
**근거.** 경공은 설계·config에 **풍부하게** 있다: `skills.yml`에 경공 무공 3종(제운종 포함),
`internal_energy.yml:116` 경신 소모, `equipment.yml:60` 철갑 `restrictions: [경공_불가]`,
`combat_system.md:38` — *"방어측 = **회피(민첩+경공)** 또는 막기 중 방어자 선택"*,
`mc_action_mapping.md:24` — 달리며 점프 = 경공 모드.
**그런데 자바 프로덕션 코드에 "경공"이 0건이다.**

그 결과가 단순한 결손이 아니다. `SkillListener.defend()`가 실제로 아는 방어는 **호신강기·패링·태극반격**
셋뿐 — **회피 분기가 존재하지 않는다.** 그리고 `EquipmentEngine.armorMitigation()` /
`armorDodgePenalty()`는 **테스트만 부른다**(프로덕션 0회). MVT 피해 파이프라인에 `mitigation`이 0건이다.

즉 `equipment.yml`의 대원칙 — **"갑옷은 회피를 판다"** — 은 **양쪽 다 구현되지 않았다.**
갑옷은 감쇄를 주지 않고, 회피는 애초에 없어서 팔 것도 없다. 파리티 테스트는 초록이다. 아무도 그 규칙을 겪지 않을 뿐이다.

> 무협에서 담을 못 넘는 건 치명적이다. 지붕을 타지 못하고, 추격을 뿌리치지 못하고,
> 경신으로 내력을 태우지 못한다. 엔드 도시를 끈 이유가 "겉날개가 경공 설계를 무너뜨린다"였는데 —
> **무너질 경공이 아직 없다.** 겉날개를 막아 지킨 자리가 비어 있다.

### ② 세월이 흐르지 않는다 — 압축 성장의 전제가 빠졌다
`training.yml`·`training_and_time.md`는 **현실 1일 = 게임 15일**을 전제로 성장 곡선을 짰다.
`HoncheonBot.java:223`은 **자정마다 세계일 +1 — 1:1**이다. 15배 달력이 없다.

그 위에서 시간 축 전체가 유령이다 — `time.yml`의 `cost_classes`(행동의 시간 비용) ·`fatigue`(피로)
·`deadline_events`(기한) ·`time_layers`, `training.yml`의 `world_calendar` ·`efficiency_bonuses`
·`internal_energy_progression` ·`return_report` — **자바 참조 전부 0건.**
`/혼천 수련`은 효율 인자 0개짜리 **평탄 적립**(하루 1회)이다. 폐관도, 수확 체감도, 오프라인 수련도 없다.

세월은 지금 **숫자 카운터**지 시스템이 아니다. 무협의 성장은 세월이다 — 그 축이 비면 나이도(§표) 안 자라고,
기한 있는 의뢰도, 폐관 후 귀환도 성립하지 않는다.

### ③ config 패치가 코드에 도달하지 않는다 — 밸런스 결정이 무효가 된다
가장 위험한 종류의 구멍이다. **고쳤다고 믿는데 안 고쳐졌다.**

* **취걸개 기연**: `fortune_encounters.yml`은 2026-07 패치로 **폐사당 30회 · 의뢰 15건**으로 올렸다
  (주석에 사유까지: *"구: 3회+2건 → 3일차에 열렸다… 문이 아니라 문턱이었다"*).
  `GameListener.java:1519-1520`은 여전히 **`SHRINE_VISITS_REQUIRED = 3` · `GOOD_DEEDS_REQUIRED = 2`**.
  **애초에 `fortune_encounters.yml`을 로드하는 자바가 0건이다.** 취걸개는 지금도 3일차에 열린다.
* **협공 상한**: `party.yml:38` `cap: 2` ↔ 문서 "+3" ↔ `MvtCommand.java:186`이 화면에 찍는 문구 **"(캡 +3)"**.
  **세 곳이 서로 다르다.**
* **마을 등급**: `CheonghaBuilder.java`는 `village_tiers.yml`을 안 읽는다 — **`.md` 문서를 주석으로 인용하며
  수치를 자바에 손으로 옮겼다.** yml을 고쳐도 아무 일도 일어나지 않는다.

### ④ 혈채·파티·문파 — 규칙은 완성됐고, 부르는 자가 없다
* **혈채**: 설계 497줄 · config 4개 yml · `db/schema.sql:205` 테이블 · `BloodDebt.java` 323줄 —
  **그리고 호출자 0.** `die()`도 `Bridge.registerDeath()`도 혈채를 적립하지 않는다. 🔨 작업 중.
* **파티**: `PartyEngine` 5개 메서드 중 **1개**만, 그것도 `/혼천 협공 <n>` — 숫자를 출력하는 디버그 명령에서
  불린다. 결성·결의·합격진·분배·배신 — 코드 없음.
* **문파 생활**: `sect_life.yml`의 `merit`·`benefits.봉록_월`·`obligations.문규`·`expulsion`·`brotherhood` —
  **자바 참조 전부 0건.** 읽히는 절은 `sect_state.internal_burden` 하나뿐이고, 그건 세력 연합의 브레이크용이다.
  정파 플레이의 본체가 통째로 비어 있다.

### ⑤ 의뢰가 세계에서 나오지 않는다
`quest_generation.md`의 핵심 약속은 **"의뢰는 세계 상태의 증상"** — region_state 임계·NPC 생애·시세·세력이
의뢰를 낳는다. 실제 `Quests.java:39-76`은 **하드코딩 5종 정적 풀**을 `worldDay % 5`로 회전시킨다.
`RegionStateEngine`은 **테스트에서만** 쓰인다 — 임계 자체가 굴러가지 않으므로 발생원 ①이 성립할 수 없다.
살아 있는 동적 주입(`Injections.java`)은 `quest_generation.yml`이 아닌 **다른 config**에서 온다.

---

## 3. 우선순위 — 왜 이 순서인가

```text
P0  ① 경공 + 회피 + 방어구 감쇄  ← 셋은 한 덩어리다
    회피가 없으면 갑옷이 팔 것이 없고, 갑옷이 감쇄를 안 주면 입을 이유가 없다.
    지금 전투는 "호신강기를 켰는가"만 묻는다 — 무림인의 몸이 없다.
    경공은 그 위에 얹히는 이동·추격·이탈의 축이다. 담을 넘지 못하는 무협은 무협이 아니다.

P0  ③ config→코드 배선 감사 (취걸개·협공캡·마을등급)
    싸다. 그리고 이것을 방치하면 **다른 모든 밸런스 작업이 헛일이 된다.**
    "고쳤는데 안 고쳐졌다"는 상태가 계속되면 config는 정본이 아니라 장식이 된다.
    → tools/gap_audit.py 를 CI에 걸면 재발이 막힌다.

P1  ② 세월 (15배 달력 + 수련 효율 + 피로)
    성장·기한·폐관·나이가 전부 여기 매달려 있다. 달력 한 줄이 네 시스템을 켠다.

P1  ④ 혈채 배선 (🔨) → 파티 결성 → 문파 생활
    혈채는 코드가 이미 있다 — 부르기만 하면 된다 (가장 싼 P1).
    파티·문파는 다인 플레이의 본체. 규칙이 완비돼 있어 배선이 곧 기능이다.

P2  ⑤ 의뢰 생성기 (region_state 임계 배선이 선행)
P2  마을 등급 yml 배선 · 용어 치환표 · 새외 자바화
P3  interface.yml (결정 기록물 — 코드가 읽을 것이 없다. 폐기하거나 문서로 접어라)
```

---

## 4. 서로 모순되는 것

| # | 곳 A | 곳 B | 실태 |
|---|---|---|---|
| 1 | `fortune_encounters.yml` 30회/15건 | `GameListener.java:1519` 3회/2건 | **코드가 이긴다** — yml은 아무도 안 읽는다 |
| 2 | `training.yml world_calendar` 1일=15일 | `HoncheonBot.java:223` 1일=1일 | **코드가 이긴다** — 15배가 없다 |
| 3 | `party_and_cooperation.md` 캡 +3 | `party.yml:38` `cap: 2` | 문서가 낡음. 게다가 `MvtCommand.java:186`은 **"+3"을 화면에 찍는다** |
| 4 | `village_tiers.md`(문서) | `village_tiers.yml`(무시됨) | 조성기가 **문서를 보고 손으로 옮겼다** |
| 5 | `resourcepack_design.yml` 유령 키 `blade_arc`·`blade_heavy` | `skill_motion.yml` 실제 필요 `slash_*` 4장 | 🔨 팩 작업자가 지금 삭제 중 |
| 6 | `skill_motion.md:102` 강등선 90 | `:298` 강등선 100 / yml `degrade_at: 100` | **문서가 자기 자신과 모순** |
| 7 | `skill_motion.md:103` 모델 11장 | `:307` 표는 9 / yml은 13 | 세 수가 다르다 |
| 8 | `llm.yml` 턴 서술 400~700자 | `LlmRenderer.java:32` "300~500자" 하드코딩 | 프롬프트가 config를 안 읽는다 |
| 9 | `saeoe_murim.md:60-90` "사막·설원을 만들 줄 모른다" | `TerrainForge.java:605` `enum Surface {자연,사막,설원}` | **문서가 낡았다** — 이미 구현됨 |
| 10 | `lint_config` 오류 4건 | `ultimate_arts.yml`의 문파 hwasan·mudang·namgung·hyeolgyo | `factions.yml`에 그 세력이 없다 |
| 11 | `mob_spawning.md` → `config/hunting_grounds.yml` | 그런 파일 없음 | 죽은 약속 |
| 12 | `npc_visual_design.md` → `config/npc_visual.yml` | 그런 파일 없음 | 죽은 약속 |
| 13 | `EconomyEngine.java:56,62` javadoc "시세 50%·타지 3배" | config 0.4 · 2배 | 주석만 거짓(동작은 정상) |

---

## 5. 죽은 것들

### a. 죽은 config — 엔진이 아무도 안 읽는다 (7종)
| config | 상태 |
|---|---|
| `chapter_events.yml` | 검산 도구만 읽는다. 장(章) 사건 = 세계에 없다 |
| `fortune_encounters.yml` | 검산 도구만 읽는다. 기연 = 자바 하드코딩 1종 |
| `village_tiers.yml` | **아무도 안 읽는다.** 수치는 자바에 손복사됨 |
| `terminology.yml` | **아무도 안 읽는다.** "등록제 치환표"인데 치환이 코드에 박혀 있다 |
| `interface.yml` | **아무도 안 읽는다.** 결정 기록물 — 실질 무해 |
| `region_populace.yml` | 🔨 신규 — 지역 인구 작업자 진행 중 |
| `map_spec/cheongha_hyeon_map.yml` | 빌드타임 파이프라인(mapgen)이 쓴다 — **정상**(런타임 config 아님) |

### b. 유령 절 — config에 규칙으로 적혀 있고, 엔진도 안 쓰고, 어떤 도구도 재지 않는다 (110개)
가장 큰 덩어리:
* `time.yml` — `cost_classes` `fatigue` `deadline_events` `time_layers` `day_segments`
* `training.yml` — `world_calendar` `efficiency_bonuses` `internal_energy_progression` `return_report` `bottlenecks` `location_safety`
* `sect_life.yml` — `merit` `obligations` `expulsion` `brotherhood` `npc_symmetry`
* `skill_lifecycle.yml` — `acquisition_paths` `entry_duration_by_grade` `learning_limits`
* `simbeop.yml` — `accumulation_methods` `conversion` `poison_response` `visualization`
* `world_map.yml` — `fortune_sites` `officialdom` `expansion_gaps` 외 6
* `faction_reaction.yml` — `saeoe_reaction` `blood_debt`(🔨)

### c. 죽은 코드 — 등록만 되고 아무도 안 부르는 public 메서드 (118/564)
* **테스트만 부른다 (38개)** — "검증된 죽음". 파리티 테스트가 초록이어도 플레이어는 그 규칙을 겪지 않는다.
  `EquipmentEngine.armorMitigation`/`armorDodgePenalty` · `PartyEngine.assistBonus`/`groupCheckPower`/`maxPartySize` ·
  `EconomyEngine.eventAdjustedPrice`(시세 이벤트) · `RegionStateEngine.applyEvent`(지역 임계) ·
  `SimbeopEngine.canPurgeOthers`/`purificationPower` · `NpcLifecycleEngine.*` · `JudgmentEngine.isAutoFail`
* **테스트조차 안 부른다** — `Db.addBloodDebt`/`bloodDebt`/`bloodDebts`(🔨) · `Db.pendLinkCode`(코드 발급자가 없다) ·
  `RegionStateEngine.recoveryStep`/`snapshot` · `Goods.*` 물품 팩토리 9종 · `SkillEngine.formPower`/`gangUpBonus` 외
* **MC 죽음 절차**: `server-mvt`에 `PlayerDeathEvent` 핸들러가 **없다** — `death_and_legacy.md §5`(90초 타이머·
  지혈 우클릭·부축·확인 사살·의방 이송)가 전부 미구현. 바닐라 리스폰이 그대로 산다.

### d. 반쪽 배선 — MC↔봇 신원 접합 🔨
`world_bridge.yml`이 정한 프로토콜: MVT가 6자리 코드를 발급 → 디스코드 `/혼천 접속`이 확정 → 혈채 병합.
* 봇 쪽: **완비** (`GameListener.linkAccount()` — 코드 소진·도난 방지·`mergeBloodDebt`까지).
* MVT 쪽: **없다.** `link_request`는 `world_bridge.yml:179`에 등록됐지만 **MVT가 발신하지 않고 Bridge가 수신하지 않는다.**
  `Db.pendLinkCode()` 호출자 0 — **코드를 발급하는 자가 없다.**
* 결과: 접합이 성립하지 않으면 `fallback: mc_name` — 마크에서 한 일이 *이름*에게만 적힌다.
  **마크의 몸과 디스코드의 이름이 아직 같은 사람이 아니다.**

---

## 6. 🔨 지금 작업 중 (병렬 작업자 5명 — 이 감사 도중에도 움직였다)
* **봇 다리/혈채** — `BloodDebt.java`·`Metrics.java`·`db/migrations/006` untracked. 접합·혈채 배선 진행 중.
* **3D 모션/팩** — `blade_arc`·`blade_heavy` 유령 모델 삭제 중 (§4-5 자동 해소 예정).
* **지형·지역 인구·성능** — `region_populace.yml`·`performance.yml` 진행 중.
> 위 항목의 판정은 **커밋 `84baced` 시점**이다. 이 셋은 곧 바뀐다.

---

## 6-b. 죽은 등록부 처분 (2026-07) — 살렸거나, 묻었거나, 서명하고 미뤘다

§5-a 의 죽은 config 와 §4-11·12 의 죽은 약속을 **한 건도 남기지 않고** 처분했다.
원칙은 하나다: *"아무도 안 읽는 규칙은 규칙이 아니다."* 그러니 읽히게 하거나, 규칙이 아니라고
선언하거나, **언제 규칙이 되는지 서명**한다. 조용히 놔두는 것만이 금지다.

| 등록부 | 처분 | 왜 |
|---|---|---|
| `fortune_encounters.yml` | **살렸다 (A)** | `Fortunes.java`(봇) 신설 → `Rules.fortunes` → `GameListener`. 취걸개 관문(방문 30·의뢰 15·이류 이하·사흘 연속)·전수 심법·인연 태그가 **전부 등록부에서** 온다 |
| `config/hunting_grounds.yml` | **살렸다 (A) — 신설** | 죽은 약속(§4-11)이었다. `HuntingGrounds.loadGrounds()` 가 읽는다: 정원(낮·밤)·재생 주기·마을 무스폰·전리품 확률. 코드 상수는 폴백으로 강등 |
| `terminology.yml` | **묻었다 (B)** | 그것은 config 가 아니라 **용어집**이었다. 엔진이 런타임에 치환하지 않는다(할 수도 없다 — 표시 문자열은 사람이 쓴 문장 안에 있다). 표는 `terminology_policy.md` §4·5 로 이관 |
| `interface.yml` | **묻었다 (B)** | **결정 기록물**이다 — 규칙이 아니라 역사. 봇은 "채널 = 지역"이라는 config 를 읽고 그렇게 행동하는 게 아니라 그렇게 지어졌다. 내용은 `interface_decision.md` 에 이미 전부 있다 |
| `village_tiers.yml` | **미뤘다 (C)** | 조성기 등급 인자화는 **회귀 기준선(middle == v6.8, 블록 단위)** 이 먼저다. 그리고 빈촌·부촌은 무대가 없다(흑수나루 미조성, 부유 마을 0). 조건은 yml `unwired` 절 + `village_tiers.md` 머리에 서명 |
| `chapter_events.yml` | **미뤘다 (C)** | 장의 방아쇠가 셋 다 없다: 지역 임계(RegionStateEngine 은 **테스트에서만** 돈다) · 사건 상태 원장 · 데드라인 시계. 없이 열면 "트리거 없이 사람이 여는 대본" — 이 파일이 거부하는 바로 그것 |
| `map_spec/cheongha_hyeon_map.yml` | **미뤘다 (C)** | 빌드타임 프로토타입만 읽고, 그 산출물은 세계에 놓이지 않는다. **정본이 둘이고 이미 어긋나 있다**: 스펙 성곽 520 ↔ 실제 마을 반경 60. M2b 월드 어댑터 전에는 배선 금지 |
| `npc_visual_design.md` → `config/npc_visual.yml` | **미뤘다 (C) — 약속을 정직하게 고쳤다** | 파일은 없었고 문서는 있는 척했다. 이제 세 참조 전부 `(미신설)` 로 표시하고 신설 조건을 머리에 박았다. 파일만 먼저 만들면 죽은 config 가 하나 늘 뿐이다 |

**같이 뽑은 가시 — 정본이 둘이던 두 자리.**
* `GameListener` 3회/2건 ↔ yml 30회/15건 (§4-1). **코드가 이기고 있었다.** 이제 정본은 yml 하나이고,
  `lint_config` 가 산문(trigger)과 기계 판독(gate)의 수가 어긋나면 잡는다.
* `village_tiers.yml audit_invariants.dark_max_pct: 15` ↔ `TownAudit.DARK_MAX_PCT = 40.0`.
  검수는 피드백으로 40 이 됐고 사본은 15 에 머물렀다 — 아무도 안 읽으니 아무도 안 고쳤다.
  **사본을 지웠다.** 잣대는 검수하는 자가 갖는다.

**눈(`gap_audit.py`)에 더한 두 가지 — 그리고 더하지 *않은* 것.**
* `unwired:` 표식 (reason·condition·doc **셋 다** 필수, doc 은 실재해야 한다) → ❌ 대신 ⚠️.
  이것은 죽음을 부정하는 뒷문이 아니라 **죽음에 서명**하는 자리다. 셋 중 하나라도 없으면 표식은
  무효이고 그냥 죽은 규칙이다. 조건 없는 '미룸'은 미룸이 아니라 방치다.
* 문서의 `(미신설)`·`(폐기)` 참조 — "있다"고 말하면 거짓말, "아직 없다/묻었다"고 말하면 계획·기록이다.
* **하지 않은 것**: 죽은 config 를 살았다고 부르는 예외 목록(whitelist). 미배선 3건은 매 실행마다
  ⚠️ 로 조건과 함께 다시 읽힌다 — 잊히지 않는 것이 요점이다.

---

## 7. `tools/gap_audit.py` — 이 감사를 기계가 반복한다

```bash
python3 tools/gap_audit.py            # 전체
python3 tools/gap_audit.py --graph    # ① config → 엔진 참조 그래프
python3 tools/gap_audit.py --dead     # ② 죽은 코드 (테스트만 부르는 것을 구분한다)
python3 tools/gap_audit.py --docs     # ③ 문서가 약속한 config/키가 실재하는가
python3 tools/gap_audit.py --coverage # ④ 어떤 도구도 재지 않는 유령 절
```
`game_audit.py`가 config끼리의 정합을 잰다면, 이 도구는 **그 위층 — 설계와 구현 사이**를 잰다.
죽은 config가 1건이라도 있으면 종료 코드 1. **CI에 걸면 §4-③(패치가 코드에 도달하지 않는 병)이 재발하지 않는다.**
