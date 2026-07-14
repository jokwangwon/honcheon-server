# 청구서 (BACKLOG) — 혼천 강호

> **이것은 문서가 아니라 장부다.** 한 번 쓰고 죽는 목록이 아니라, 다음 사람이 **읽고 · 일하고 · 닫고 · 더 적는** 것이다.
>
> 작성: 2026-07-14 · 마지막 검산: 2026-07-14 (`tools/backlog_audit.py --run`)

---

## 0. 쓰는 법 (운영 규약)

**다음 사람이 규칙을 짐작하지 않아도 되도록** 여기 적어 둔다.

### 0.1 항목의 모양

모든 항목은 `### B-NNN · 제목` 으로 시작하고 **여섯 개의 필수 항목**을 갖는다. 기계가 이 형식을 읽는다 —
형식을 어기면 `tools/backlog_audit.py` 가 짖는다.

```
### B-NNN · 무엇이 잘못됐는가 (한 줄로)
- **상태**: 열림
- **분류**: ★세계
- **단계**: P1
- **위치**: `경로/파일.java:123`          ← 유령이면 안 된다. 기계가 실재를 확인한다
- **의존**: B-001                          ← 없으면 —
- **닫는 조건**: 무엇이 참이 되면 닫히는가
- **검증**: `python3 tools/xxx_audit.py`   ← ★ 무엇을 돌려서 확인하는가
- **닫힘**: —                              ← 닫을 때만 적는다
```

> ★ 위 보기의 ID 를 일부러 `B-NNN` 으로 둔 이유: 처음엔 `B-042` 라고 적었더니 **감사가 이 보기를 진짜 항목으로 읽고
> "ID 가 겹친다"고 짖었다.** 눈은 보기와 항목을 구별하지 못한다 — 그리고 **그것이 옳다.** 장부에 `B-042` 가 두 번 있으면
> 그것이 보기든 아니든 사람은 헷갈린다.

### 0.2 상태 — 다섯 가지뿐이다

| 상태 | 뜻 |
|---|---|
| **열림** | 살아 있다. 아무도 손대지 않았다 |
| **진행** | 지금 누가 고치고 있다 (누구인지 본문에 적어라) |
| **닫힘** | 끝났다 — **그리고 증거가 있다** (§0.3) |
| **보류** | 사람이 정해야 한다. 또는 지금 고칠 수 없다 (설계 한계) |
| **미확인** | 참인지 거짓인지 **아직 모른다**. ★ 추측을 사실처럼 적지 마라 |

### 0.3 ★ 닫는 법 — 무엇이 증거인가

> **"고쳤다" 는 증거가 아니다.**

**「닫힘」으로 바꿀 때는 `닫힘:` 칸에 반드시 세 가지를 적는다: ① **언제**(날짜) · ② **무엇으로**(증거) · ③ 그래서 무엇을 봤는가.**

증거로 **인정하는 것**은 셋뿐이다:

1. **감사 명령** — `2026-07-14 · \`python3 tools/pack_gate_audit.py\` → 위반 0건` ← 가장 좋다. 기계가 다시 잰다
2. **사람이 본 것** — `2026-07-14 · 인게임에서 눈으로 봤다 (나루 글판 6장이 서 있다)` ← 기계가 못 재는 것에만
3. **파일** — `2026-07-14 · \`config/skill_motion.yml:1499\` 에 stroke_origin 이 섰다`

증거로 **인정하지 않는 것**: "고쳤다" · "완료" · "됐다" · 날짜 없는 말.

**★ 닫힌 항목은 지우지 마라.** 지우면 *왜* 닫혔는지가 사라지고, 같은 병이 돌아왔을 때 아무도 못 알아본다.

### 0.4 ★ 장부도 거짓말한다 — 그래서 눈이 있다

2026-07-14, 우리는 하루 종일 **같은 병**을 잡았다:

> 로그는 "허수아비 3"이라 했고 — **세계엔 0마리**였다.
> 감사는 "위반 0건"이라 했고 — 사용자는 **보라 큐브**를 보고 있었다.
> 봇은 "마크가 꺼졌다"고 했고 — **서버는 돌고 있었다**.

**청구서에 「닫힘」이라 적어 놓고 실제로는 안 닫힌 것** — 이것이 같은 병의 다음 얼굴이다.
그리고 이 얼굴이 **가장 위험하다**: 다른 병은 증상이 있지만, 이 병은 **증상이 없다.**
장부는 평온하게 "전부 닫힘"이라 말하고, 아무도 그 말을 재지 않고, 몇 주가 지난다.

그러므로 **이 장부를 재는 눈**이 있다:

```bash
python3 tools/backlog_audit.py            # 모양만 — 빠르다
python3 tools/backlog_audit.py --run      # ★ 「닫힘」 항목의 감사를 실제로 돌린다
python3 tools/backlog_audit_selftest.py   # 눈을 시험하는 눈 (거짓말 11종을 심는다)
```

이 눈이 재는 것:
① 문법 · ② 어휘 · ③ **「닫힘」이 증거를 대는가** · ④ **그 증거가 실재하는가** ·
⑤ **「위치」가 유령이 아닌가** · ⑥ 의존 · ⑦ ★ **「닫힘」이라 적힌 감사를 돌려 보면 정말 조용한가**

**⑦ 이 심장이다.** ①~⑥ 은 장부의 *모양*을 보지만, ⑦ 은 장부의 **말이 참인지**를 본다.
자기 시험(`--selftest`)에서 "「닫힘」인데 감사가 실제로는 짖는" 거짓말은 **`--run` 없이는 잡히지 않았다.** 그래서 `--run` 이 있다.

> ★ **함정 하나를 알고 있다.** 이 저장소의 감사 일부는 **위반을 보고하면서도 종료 코드 0** 을 낸다
> (`bridge_audit` · `map_lint` — 2026-07-14 실측 → **B-003**). 그래서 ⑦ 은 종료 코드만 믿지 않고
> 출력에서 「위반 N건」을 읽는다.

### 0.5 새 항목을 더하는 법

- ID 는 **다음 번호**를 쓴다. **번호를 재사용하지 마라** (닫힌 항목이 그 번호를 갖고 있다).
- **추측을 사실처럼 적지 마라.** 확인 못 했으면 **상태: 미확인** 이고, 본문에 *무엇을 확인 못 했는지* 적어라.
- 단계(P)는 **의존이 정한다**. 앞 단계가 뒤 단계에 기대면 안 된다 (기계가 잡는다).

### 0.6 분류

| 분류 | 뜻 |
|---|---|
| **★세계** | 세계를 깨뜨린다. 이것이 살아 있으면 강호가 강호가 아니다 |
| **결함** | 사람이 겪는다. 플레이어가 만난다 |
| **빚** | 기술부채. 지금은 굴러가지만 언젠가 물린다 |
| **미완** | 반쯤 됐다. 시작했고 안 끝났다 |
| **결정** | ★ **사람이 정해야 한다.** 코드로 풀 수 없다 |

---

## 1. 단계 — 무엇을 먼저 하면 무엇이 풀리는가

```
P0  눈을 고친다 ─────────────► 감사가 거짓말하는 동안엔 아무것도 "닫혔다"고 말할 수 없다
     │                          (B-001 ~ B-004)                      ★ 전부 닫힘 (2026-07-14)
     ▼
P1  세계를 깨뜨리는 것 ───────► 전투의 심장 · 헌법의 이중성 · 데이터 손실
     │                          (B-005 ~ B-014)
     ▼
P2  ★ 사람이 정해야 한다 ─────► 코드로 못 푼다. **묻는 것은 싸고, 답을 기다리는 동안 P3·P4 를 한다**
     │                          (B-047 ~ B-062, B-067)               ★ P5 의 절반을 막고 있다
     ▼
P3  사람이 겪는 결함 ─────────► 플레이어가 지금 만나는 것
     │                          (B-015 ~ B-023, B-064 ~ B-066)
     ▼
P4  빚 ──────────────────────► 굴러는 가지만 언젠가 물린다
     │                          (B-024 ~ B-034)
     ▼
P5  미완 — 자산·조성 ─────────► 손과 시간의 문제. **P2 가 답해야 끝난다**
                                (B-035 ~ B-046, B-059, B-063)
```

**★ 가장 중요한 의존 하나: P2 가 P5 를 막고 있다.** 조성 53곳 중 **16곳이 조용히 아무것도 안 선다** —
그 중 **15곳은 코드가 없어서가 아니라 원형(archetype)을 사람이 안 정해서**다.
그래서 **결정(P2)을 앞으로 끌어냈다**: 묻는 것은 **싸고 오래 걸린다.** 먼저 묻고, 답을 기다리는 동안 P3·P4 를 한다.

> ★ **이 순서는 기계가 고쳐 준 것이다.** 처음엔 결정을 **맨 뒤(P5)** 에 두고 본문에는 *"P5 가 P4 를 막는다"* 라고 적었다 —
> **말과 번호가 모순이었다.** `backlog_audit` 의 축 ⑥ (단계 역행) 이 그것을 잡았다:
> `❌ 역행: B-039(P4) → B-047(P5)`. **장부가 자기 자신에 대해 거짓말한 첫 사례이고, 눈이 첫 끼니를 먹었다.**

**★ 그리고 P0 는 전부를 막고 있었다 — 이제 닫혔다 (2026-07-14).** 두 감사의 거짓말(B-001 · B-002,
합쳐서 22건의 거짓 위반)은 교정됐고, 종료 코드(B-003)와 팩 실물의 눈(B-004)도 섰다.

---

# P0 — 눈을 고친다 (감사가 거짓말한다) — **전부 닫힘**

> 이 단계가 먼저인 이유: **이 장부의 「닫힘」은 감사가 증명한다.** 감사가 거짓말하면 장부도 거짓말한다.
> 두 감사가 실제로 거짓말하고 있었다 — 둘 다 리팩터가 진실을 옮겼는데 **눈이 따라가지 않아서**였다.

P0 네 항목(B-001 ~ B-004)은 **전부 닫혔다** (2026-07-14) — 「닫힌 것」 절에 있다. 눈이 다시 볼 수 있다.

---

# P1 — 세계를 깨뜨리는 것

### B-007 · ★ **헌법이 둘이다** — 팩 게이트의 옛 조항이 14곳에 살아 있다
- **상태**: 열림
- **분류**: ★세계
- **단계**: P1
- **위치**: `config/mob_models.yml:60`
- **의존**: B-004
- **닫는 조건**: 옛 조항(「팩 없어도 보인다」)을 강제·전제하는 축과 코드 경로가 **전부 걷힌다**
- **검증**: `python3 tools/pack_gate_audit.py` · `python3 tools/model_key_audit.py` · `python3 tools/motion_audit.py`
- **닫힘**: —

2026-07-13 헌법이 바뀌었다: **팩 필수.** `pack_gate_audit.py` 가 새 헌법을 강제한다.
그러나 **옛 헌법을 강제하거나 전제하는 것이 아직 14곳**이다 — 그리고 **둘이 서로 모순된다**.

**감사 축 (5)** — 옛 조항을 *강제*한다:
| 도구 | 줄 | 무엇 |
|---|---|---|
| `tools/motion_audit.py` | `:587` | `ALLOWED_FALLBACK` (바닐라 폴백 허용 목록) |
| `tools/model_key_audit.py` | `:350` | 축 ④·⑤ (팩 게이트 · 바닐라 폴백) |
| `tools/defense_audit.py` | `:277` | 「팩이 없어도 보이는가」 |
| `tools/mob_model_audit.py` | `:387` | 팩 수락 여부를 듣는가 |
| `tools/build_resourcepack.py` | `:2200` | `write_vanilla_dispatch()` — "팩 없는 눈은 폴백을 본다" |

**코드 경로 (9)** — 폴백을 위해 **엔티티 예산을 절반 쓴다**:
`SkillDisplay`(`withPack` 관람석 분리 — ★ **팩 필수면 예산 절반 회수 가능**) · `MobDisplay.verdict()` ·
`SkillListener:452` · `SkillEngine:631` · `SkillHud`(주석) · `Vitality.java:197`(주석이 폐지된 조항을 이름으로 인용) ·
`Weapons.java:116`(item_model 대신 CMD 우회) · `Goods.java:152`(같음) · `TownRender.java:62`

**등록부 (3)**: `mob_models.yml:60` `pack_gate:` · `skill_motion.yml` `fallback:` (15개 모델) · `resourcepack_design.yml` `팩없음:` 열

> ★ **그리고 등록부가 낡은 사실을 말한다**: `mob_models.yml:64` — "지금 서버는 팩을 배포하지 않는다 … 형체 0개".
> **거짓이다.** `config/resource_pack.yml` 에 `url:` 과 `required: true` 가 있다. `SkillDisplay.java:891` 도 같은 거짓말을 한다.

### B-010 · ★ 아미의 성별 문이 **없는 문**을 지킨다
- **상태**: 열림
- **분류**: ★세계
- **단계**: P1
- **위치**: `config/player_creation.yml:94`
- **의존**: —
- **닫는 조건**: `ami_entry` 루트가 생긴다 — 또는 아미 게이트를 거둔다
- **검증**: `config/faction_entry_routes.yml` 에 `ami_entry` 가 있는가
- **닫힘**: —

시스템의 **유일한 성별 게이트**가 `ami: [여]` 다 (`player_creation.yml:96`). `GenderEngine.factionAllowed` 가 강제한다.

그런데 `config/faction_entry_routes.yml` 의 `routes:` 에는 **16개 루트가 있고 `ami_entry` 는 없다.**
**들어갈 길이 없는 문파의 문을 성별로 지키고 있다.** 이 게이트는 **도달 불가능**하다.

> 씨앗 정정: "그 파일엔 자바 평가기가 없다" 는 **거짓**이다 —
> `server-bot/src/main/java/com/honcheon/bot/Rules.java:102` 가 읽고 `Routes.java` 가 평가한다 (`/혼천 출행`).

### B-011 · ★ 연무장 금고 — **ItemStack 왕복이 한 번도 실측 안 됐다** (짐이 사라질 수 있다)
- **상태**: 열림
- **분류**: ★세계
- **단계**: P1
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/Dojang.java:220`
- **의존**: —
- **닫는 조건**: **진짜 `ItemStack`** 이 `serializeItemsAsBytes` → `deserializeItemsFromBytes` 를 왕복해 **같은 것**으로 돌아옴을 본다
- **검증**: `tools/DojangVaultSelfTest.java` — ★ 지금 이것은 **가짜를 왕복시킨다**
- **닫힘**: —

`Dojang.java:223` 이 진짜로 하는 일: `Base64(ItemStack.serializeItemsAsBytes(items))`.
`Dojang.java:232` 가 되돌린다: `deserializeItemsFromBytes(...)`.

**그런데 자기 시험은 이 둘을 한 번도 부르지 않는다.** `tools/DojangVaultSelfTest.java:93` 이 왕복시키는 것은 **문자열**이다:

```java
before.realItems = "REAL:칠성검,비급,은자7971";   // (진짜 서버에서는 ItemStack Base64)
```

시험이 증명하는 것: *"YAML 이 불투명한 줄을 잃지 않는다."*
시험이 **증명하지 않는 것**: *"재기동을 건너 플레이어의 짐이 살아 돌아온다."*

★ **이것은 조용히 실패하고 사람의 물건을 잃는다.** 그리고 **과거 데이터 손실은 증명 불가**다 — 인벤토리 기록이 없다 (**B-012**).

### B-012 · 과거 데이터 손실을 **증명할 수 없다** (인벤토리 기록이 없다)
- **상태**: 보류
- **분류**: 빚
- **단계**: P1
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/Dojang.java:220`
- **의존**: B-011
- **닫는 조건**: 앞으로의 손실은 잴 수 있다 — 금고 입출입에 **기록**이 남는다
- **검증**: ★ 사람이 정해야 한다 — 과거는 못 되돌린다. 미래를 잴 것인가
- **닫힘**: —

**과거는 닫을 수 없다.** 적어 두는 이유는 하나 — *"손실이 없었다"* 고 **말하지 않기 위해서**다. 우리는 모른다.

### B-013 · 접속 중 텔레포트 · 핸들러 **순서 계약**이 미실측이다
- **상태**: 미확인
- **분류**: ★세계
- **단계**: P1
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/Dojang.java:716`
- **의존**: —
- **닫는 조건**: 접속 직후 텔레포트가 **실제로 착지하는지** 본다 · 핸들러 순서가 깨져도 안전한지 본다
- **검증**: ★ 인게임 — 기계로 못 잰다 (서버가 필요하다)
- **닫힘**: —

두 핸들러가 접속 중 옮긴다: `Antechamber.java:1523` (2틱 뒤) · `Dojang.java:716` (`LOWEST`, 다음 틱).
둘 다 **이미 미루고 있다** — 그러나 그 이유가 **주장뿐**이다:

> `Dojang.java:755` — "순간이동은 다음 틱에 한 번 더 (접속 직후의 이동은 **씹힐 수 있다** — 확실히 내린다)"

*"씹힐 수 있다"* 는 **가설**이다. 재 본 사람이 없다.

★ 더 무서운 것은 **주석에만 있는 계약**이다 (`Dojang.java:717-720`): Dojang 이 `LOWEST` 로 `SkillListener.onJoin`·
`Antechamber.onJoin` 보다 **먼저** 돌아야 한다 — 안 그러면 *"일류 무인이 나루로 끌려간다."*
**이 순서를 지키는 눈이 없다.** 누가 핸들러 우선순위를 바꾸면 조용히 깨진다.

### B-014 · 인게임 클릭으로 **끝까지 안 밟아 봤다** (접합 종단)
- **상태**: 미확인
- **분류**: 결함
- **단계**: P1
- **위치**: `server-bot/src/main/java/com/honcheon/bot/Bridge.java:278`
- **의존**: —
- **닫는 조건**: 인게임에서 클릭 → **명부에 찍히는가** · **청이 2초 안에 뜨는가** 를 눈으로 본다
- **검증**: ★ 인게임 — 기계로 못 잰다
- **닫힘**: —

`bridge_audit` 은 **코드가 이어졌는가**를 잰다. **사람이 겪는가**는 안 잰다.
`[혼천 접속]` 클릭(`62b6844`)이 끝까지 도는 것을 **아무도 눈으로 안 봤다.**

---

# P2 — 사람이 겪는 결함

### B-103 · 소문의 지역이 **표시명**이었다 — PostgreSQL 이 잡은 첫 물고기
- **상태**: 닫힘
- **분류**: 결함
- **단계**: P1
- **위치**: `server-bot/src/main/java/com/honcheon/bot/WorldStore.java:7` (`PRIMARY_REGION`)
- **의존**: —
- **닫는 조건**: 소문 파종이 regions.id(`cheongha_hyeon`)로 참조한다 — FK 아래서 심긴다
- **검증**: `server-bot/src/main/java/com/honcheon/bot/WorldStore.java:15` (id 로 참조) · `server-bot/src/main/java/com/honcheon/bot/Db.java:95` (원천 단일화)
- **닫힘**: 2026-07-14 · `server-bot/src/main/java/com/honcheon/bot/WorldStore.java:15` 의 `PRIMARY_REGION` 이 표시명 "청하현"으로 박혀 있었다 (포트 추출 때 갈라진 값 — 기존 소문 42건은 전부 id `cheongha_hyeon`). id 로 교정하고 `Db.java:95` 의 `REGION` 을 이 상수에 묶어 원천을 하나로 만들었다. 실증(본 것): 스테이징 PG 하네스에서 `spread()` → 1망 파종, `region=cheongha_hyeon` FK 통과 · 수리 jar 운영 배포(16:50) 후 기동 로그 `DB: postgresql` 정상. ★ 이 병은 **컷오버 직후 PostgreSQL 이 잡았다** — SQLite 는 FK 를 안 지켜서(꺼짐) 고아 소문을 조용히 만들었을 자리다. **전환의 첫 배당금이다.** 발견 로그(`run/bot/bot.log`): "탄생 소문을 심지 못했다 (아이는 태어났다): … violates foreign key constraint rumors_region_fkey" (아이 = 캐릭터 7 디돈 — 무사히 태어났다)

병이 보이던 방식: 탄생·의뢰·개화·출행 — **모든** 소문 파종이 PostgreSQL 에서 FK 위반으로 죽는다
(다행히 파종 실패는 생성을 죽이지 않게 돼 있었다). SQLite 였다면 위반 없이 심기되,
읽는 쪽은 id 로 거르므로 **아무에게도 들리지 않는 유령 소문**이 됐을 것이다.

### B-102 · ★ 세계 상태 발행이 **계속 실패한다** — 봇→마크 되먹임이 끊겼다
- **상태**: 닫힘
- **분류**: 결함
- **단계**: P3
- **위치**: `server-bot/src/main/java/com/honcheon/bot/Bridge.java:869` (publishQuietly → publish)
- **의존**: —
- **닫는 조건**: `publish()` 가 ClassCastException 없이 `world_state.json` 을 갱신한다 · 실패가 재발하면 **원인 문자열**(어느 키가 String 인데 Map 로 읽었는가)이 로그에 남는다
- **검증**: `run/bot/bot.log` 에 「세계 상태 발행 실패」가 더 안 찍힌다 · `run/bridge/world_state.json` mtime 이 주기적으로 갱신된다
- **닫힘**: 2026-07-14 · 원인은 `Rules.defaultRegion()` — 스칼라(`mvt_start.default_region: cheongha_hyeon`)를 Map 전용 `RulesConfig.section()` 으로 읽어 ClassCastException (재현 하네스 스택: `Rules.java:862` ← `startAnchor` ← `mvtSheet` ← `publish`). `get()` 으로 교정. 아울러 `publishQuietly` 가 이제 예외의 낯과 첫 발자국(파일:줄)을 말한다 — getMessage 만 찍던 침묵이 이 병을 15시간 키웠다 (`world_state.json` 01:43 동결 → 16:26 부활). 실측: 수리 배포 후 발행 실패 0건 · 16:26:20 → 16:26:40 주기 갱신 재개 (world_day 24 · sheet 2명 · rumor_tags 13)

PG-007 훈련(2026-07-14)이 발견했다 — 훈련이 만든 병이 **아니다**: 훈련 전(구 jar·SQLite)에도,
훈련 중(신 jar·PostgreSQL)에도, 복귀 후(신 jar·SQLite)에도 똑같이 찍힌다. 백엔드 무관.

`세계 상태 발행 실패: class java.lang.String cannot be cast to class java.util.Map` —
`publish()` 가 어떤 JSON 값을 Map 로 기대하는데 String 이 온다 (state_json 안의 어느 키일 것이다).
`publishQuietly` 가 조용히 삼키므로 **되먹임(봇→마크 스냅숏)이 끊긴 채 세계가 돈다** —
마크는 낡은 `world_state.json` 을 본다. ★ **운영 컷오버 전에 고쳐야 한다** (PG-007 완료 문서 §남은 위험).

### B-016 · `/혼천 대화` 가 **줄을 안 선다** (Scribe 를 우회한다)
- **상태**: 닫힘
- **분류**: 결함
- **단계**: P3
- **위치**: `server-bot/src/main/java/com/honcheon/bot/GameListener.java:1976`
- **의존**: —
- **닫는 조건**: 대화도 `Scribe` 의 한 줄(lane)을 탄다 — GPU 를 두 길이 다투지 않는다
- **검증**: `config/llm.yml:44` 의 `serialize: true` 가 대화 경로에도 걸리는가
- **닫힘**: 2026-07-14 · 병렬 R1 트랙 을. 대화가 서장과 **같은 배**를 탄다 — `GameListener.java:2465` 가 `renderer.chat` 직접 호출 대신 `scribe.chat(...)` 을 부르고, `Scribe.java:108-150` 의 신설 `chat()` 이 기존 단일 스레드 lane(`Scribe.java:42`) 위에서 돈다 (Scribe 는 **순수 추가만**, +43/−0 — 서장 트랙 소유권 존중). 스레드가 하나이므로 대화·서장의 동시 GPU 호출은 구조적으로 1건이다. 줄이 밀리면 등록부 문장(`llm.yml` runtime.chat_queue_notice)으로 차례를 알린다. 컴파일 exit 0 · lint_config 0건 (Fable 재실행 확인)

서장은 **고쳐졌다** (**B-054 닫힘**): `Scribe.java:42` 가 단일 스레드 lane 을 세웠다.
그런데 `/혼천 대화` 는 `Scribe` 를 **건너뛰고** `renderer.chat(...)` 을 직접 부른다 (`GameListener.java:1976`) →
`LlmRenderer.java:158` 의 `sendAsync` 가 **직렬화 없이** 발사된다.

**같은 GPU 를 서장 lane 과 대화가 다툰다.** 씨앗의 절반이 살아 있다.


### B-017 · `/혼천 대화` 의 **폴백이 사람에게 안 보인다**
- **상태**: 닫힘
- **분류**: 결함
- **단계**: P3
- **위치**: `server-bot/src/main/java/com/honcheon/bot/GameListener.java:1976`
- **의존**: B-016
- **닫는 조건**: 대화가 폴백으로 떨어지면 **사람이 안다** (서장의 `fallback_mark` 처럼)
- **검증**: `config/seojang.yml` 의 `fallback_mark` 에 해당하는 것이 대화에도 있는가
- **닫힘**: 2026-07-14 · 병렬 R1 트랙 을. 대화 폴백에 표식이 생겼다 — `llm.yml` runtime.chat_fallback_mark ("*(붓이 더디어 몸짓만 돌아왔다)*" — 서장 fallback_mark 문법 차용, 디스코드라 마크다운) 를 `GameListener.java:2480-2489` 가 답변에 덧붙이고, 장부에 `서사_폴백` 사건(경로:대화·상대·사유)을 적는다 — 서장의 persistScene 과 대칭. 문구는 등록부가 정본이다 (코드가 말을 지어내지 않는다)

서장은 **고쳐졌다** (**B-055 닫힘**) — `SeojangBook.java:213` 이 `§8(붓이 더디어 옛 필사본이 왔다)` 를 찍는다.
대화(`GameListener.java:1976-1987`)는 **조용히** 폴백으로 갈아탄다. 표식이 없다.


### B-026 · `SkillDisplay` 의 `Material.PAPER` 3건 — 감사가 짖는다
- **상태**: 닫힘
- **분류**: 빚
- **단계**: P4
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/SkillDisplay.java:1230`
- **의존**: —
- **닫는 조건**: `motion_audit` 위반 0건 — 등록하거나, 감사가 `[대조]` 를 이해하거나
- **검증**: `python3 tools/motion_audit.py`
- **닫힘**: 2026-07-14 · 병렬 R1 트랙 정. 길 ①: 감사가 [대조]를 알아본다 — 예외 목록이 아니라 **줄 단위 청구제**다 (`tools/motion_audit.py` hardcoded_enums: 그 줄 주석에 `[대조]` 를 적어 청구한 줄만 면제, 면제 수를 세어 보고. 문자열 리터럴 속 표식은 안 쳐준다). 대조군이 등록부를 타면 등록부가 병들 때 대조의 뜻이 죽는다 — 그래서 ①이 옳다. 눈의 시험 신설: `tools/motion_audit_selftest.py` 6/6 ("표식 없는 하드코딩은 여전히 잡는다" 변이 포함). 실측: motion_audit 위반 0·exit 0 (Fable 재실행)

**motion_audit 의 유일한 위반**이다 (실측 2026-07-14).

★ 그러나 **셋 다 `strikeTest(Player)` 안의 `[대조]`(대조군)** 이다 — 진단용 시험대의 **맨 종이 · 유령 키 · 병기 키** 세 줄.
진짜 렌더는 `engine.displayModels()` 에서 온다 (`:1237`).

**둘 중 하나를 골라야 한다**: ① 감사가 `[대조]` 를 알아보게 하거나, ② 대조군도 등록부를 타게 하거나.
**지금은 감사가 옳지도 그르지도 않다** — 그리고 그것이 위반 1건으로 남아 **다른 진짜 위반을 가린다.**


### B-027 · `hand()` (날의 기) 가 **하드코딩** — 획과 같은 병
- **상태**: 닫힘
- **분류**: 빚
- **단계**: P4
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/SkillDisplay.java:523`
- **의존**: —
- **닫는 조건**: 손의 원점이 **등록부에서** 온다 (획의 `stroke_origin` 처럼)
- **검증**: `python3 tools/motion_audit.py`
- **닫힘**: 2026-07-14 · 병렬 R1 트랙 정. 손의 원점이 등록부에서 온다 — `config/skill_motion.yml:1550` `display.stroke_origin.날의_기` (forward 0.55 · height 1.47 · lateral 0) 를 `SkillDisplay.java` hand() 가 획과 **완전히 같은 문법**(engine.strokeOrigin)으로 읽는다. sheaths 레코드는 고정 필드라 새 키를 못 실어 stroke_origin 칸으로 세운 것 — SkillEngine 무수정. 1.62−1.47=0.15, 옛 리터럴과 수치 동일(행동 불변, 단위 환산 근거 주석). 실측: motion_audit 위반 0 (Fable 재실행)

`:525` — `body.getEyeLocation().add(dir.clone().multiply(0.55)).subtract(0, 0.15, 0)`.
**`0.55` 도 `0.15` 도 리터럴**이다.

획은 **고쳐졌다** (**B-053 닫힘**) — `config/skill_motion.yml:1499` 의 `stroke_origin` 을 읽는다.
그런데 `skill_motion.yml:1909` 의 `sheaths:` 는 `scale`·`brightness` 만 등록한다 — **원점 키가 없다.**
**칼집(날의 기)의 원점은 등록부에 자리가 없다.**


### B-028 · `perf_audit` — **죽은 예산 19건** (아무도 안 읽는 수치)
- **상태**: 닫힘
- **분류**: 빚
- **단계**: P4
- **위치**: `config/performance.yml`
- **의존**: —
- **닫는 조건**: `perf_audit` 위반 0건 — 읽거나, 지우거나
- **검증**: `python3 tools/perf_audit.py`
- **닫힘**: 2026-07-14 · 병렬 R1 트랙 정. 19건 중 **5건은 죽은 게 아니라 눈이 멀었던 것** — subsystem_budget_ms 4키는 Metrics.java:79-105 의 metrics.probes 간접 배선이 소비하고, load_test.combat_cluster_size 는 motion/mob_model_audit 이 읽는다 → `tools/perf_audit.py` 에 두 간접 배선을 가르쳤다 (B-001·B-002 와 같은 "리팩터가 진실을 옮겼는데 눈이 안 따라간" 병의 세 번째 사례). 나머지 **14건 삭제** — 원값은 `config/performance.yml` 상단 묘비 주석에 보존 ("다시 살릴 때는 읽는 코드와 함께"). max_range_default 갈라짐은 삭제로 해소 (정본 `config/skill_mechanics.yml:18` — 단 그것도 지금은 감사만 읽는다). 눈의 시험 신설: `tools/perf_audit_selftest.py` 10/10. 실측: perf_audit 예산 21/21 읽힘·죽은 예산 0·exit 0 (Fable 재실행)

19건이 **읽는 자가 없다**: `tick_budget.server_target_mspt` · `subsystem_budget_ms.*` (5) ·
`tick_slicing.preload_radius.*` (3) · `skills.*` (4) · `netcode.counter_window_grace_ticks` · `load_test.*` (5).

**등록부가 규칙을 적어 놓고 아무도 안 지킨다.** 그리고 그 사실을 **아무도 안 잰다** — 이 감사가 잰다.
★ `skills.max_range_default: 24` 는 `skill_mechanics.yml` 에도 **같은 값이 적혀 있다** — *"지금은 같다. 언젠가 갈라진다."*


### B-074 · **`Narration.hunt/duel` 의 문장이 아직 자바에 박혀 있다** (서장은 등록부로 나왔다)
- **상태**: 닫힘
- **분류**: 빚
- **단계**: P3
- **위치**: `server-bot/src/main/java/com/honcheon/bot/Narration.java:26`
- **의존**: —
- **닫는 조건**: 사냥·비무의 폴백 산문이 등록부로 나간다 (서장이 `config/seojang.yml` 로 나간 것과 같은 방식).
  **코드가 이야기를 지고 있으면 안 된다** — 서장의 8종 도입부·7종 집안 감각·10종 에필로그가 그랬다
- **검증**: `server-bot/src/main/java/com/honcheon/bot/Narration.java` (한글 10자+ 산문 grep 0) · `config/narration.yml`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 을'. 사냥 7종·비무 2종 산문이 `config/narration.yml`(신설)로 나갔다 — 한 글자도 바꾸지 않은 이관 (리플렉션 9케이스 옛 출력과 **바이트 동일** 검증). 치환 자리 규약({짐승}·{승자}·{패자})은 등록부 머리 주석에 문서화. 등록부 부재 시 severe 로 소리내고 짧은 생존 문장으로 버틴다 (침묵 금지·불사 — 빈 config 구동 실증). 사설 Grade enum 을 걷고 환산점을 Seojang.grade 하나로 통일 (Seojang 무수정). 실측: `grep -cE '"[가-힣]{10,}' server-bot/src/main/java/com/honcheon/bot/Narration.java` → **0** (닫는 조건 그대로) · 컴파일 0 · lint 0 (Fable 재실행)


### B-104 · game_audit 에 **눈의 시험이 없다** — 오독 교정을 지킬 자동 눈이 없다
- **상태**: 닫힘
- **분류**: 빚
- **단계**: P4
- **위치**: `tools/game_audit.py`
- **의존**: —
- **닫는 조건**: game_audit 의 selftest 가 선다 — 최소 세 변이(스키마에 없는 `by:` 값 · 미등록 NPC · 미등록 장소)를 잡는다 (닫을 때 검증란을 그 selftest 로 바꾼다)
- **검증**: `python3 tools/game_audit_selftest.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 병'. `tools/game_audit_selftest.py` 신설 — pack_gate 문법 그대로 (변이 → ❌ 확인 → finally 원복 → 재감사). 변이 3종 3/3: 스키마에 없는 `by:` 오탈자 · 유령 NPC · 없는 장소. 원복 재감사 위반 0 · 잔여 .bak 없음. 실측 exit 0 (Fable 재실행). 시뮬 축은 무시험으로 남음 (후속 후보)

B-008 을 닫으며 드러났다 (2026-07-14). 모든 감사에 눈의 시험이 있는데 game_audit 만 없다 —
B-008 의 오독 교정(schema_columns 대조)을 지킬 회귀 방어가 없다. 트랙 병이 변이 3종을
수동으로 확인하고 원복했으나, 그 변이들이 selftest 로 박제돼야 한다.


### B-106 · **max_targets_default 가 두 등록부에 산다** — 지금은 같다, 언젠가 갈라진다
- **상태**: 닫힘
- **분류**: 빚
- **단계**: P4
- **위치**: `config/performance.yml` → `skills.max_targets_default` · `config/skill_mechanics.yml` → `global_rules.max_targets_default`
- **의존**: —
- **닫는 조건**: 정본이 하나가 된다 (읽히는 쪽만 남기고, 다른 쪽은 지우거나 참조 표기)
- **검증**: `python3 tools/perf_audit.py` · `config/skill_mechanics.yml`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 병'. ★ 브리핑이 뒤집혔다 — 산 키는 `config/skill_mechanics.yml` `global_rules.max_targets_default` 다 (`SkillEngine.java:231` 이 읽는다 · motion_audit.py:410 동일). performance.yml 쪽이 죽은 사본이었고, perf_audit 의 ✓ 는 leaf 이름만 대조한 **허위 매칭**이었다 (→ B-107 신설). performance.yml 한 줄 삭제 + 묘비 (max_range_default 전례 그대로) · skill_mechanics 쪽 주석을 「★ 정본」으로. 실측: perf/combat/motion/game_audit 전부 exit 0 (Fable 재실행)

B-028 정리 중 발견 (2026-07-14, 트랙 정). 같은 값 8 이 두 곳에 적혀 있고, SkillEngine 이 읽는
산 키는 performance.yml 쪽이다. max_range_default 는 같은 병으로 이미 한 번 갈라질 뻔했다 —
이번에 삭제로 풀었지만, 이 쌍이 하나 더 남아 있다. perf_audit 은 읽히는 키의 중복은 안 잰다.


### B-101 · 신규 SQLite 가 **없는 표를 가진 척** 버전 8 로 스탬프된다
- **상태**: 닫힘
- **분류**: 빚
- **단계**: P4
- **위치**: `server-bot/src/main/java/com/honcheon/bot/Db.java:114` · `db/schema.sql`
- **의존**: —
- **닫는 조건**: 새로 만든 SQLite DB 에 `houses` 가 실제로 선다 (schema.sql 이 008 을 포함하거나,
  신규 스탬프가 버전이 아니라 **실제 표의 존재**를 확인한다)
- **검증**: `tools/PgMigrateSelfTest.java` (fixture 가 신규 schema.sql 만으로 houses 를 얻는다) · `python3 tools/persistence_boundary_audit.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 정'. 길 (a) — `db/schema.sql` (경로 실재: `server-bot/src/main/java/com/honcheon/bot/Db.java` 가 읽는 그 파일) 에 008 최종 상태 편입 (+34/-2): houses 표를 characters **앞에**(FK 순서·PG 스키마와 동순), house_id 열·인덱스, 그리고 **조건부** 버전 스탬프 (버전 행도 캐릭터도 없을 때만 — 구 DB 의 진짜 버전을 안 덮고 v0 구 DB 를 거짓 스탬프하지 않음, 실증). migrate_db.py 는 순수 버전 판정이라 (b)로는 못 고친다는 근거 포함. 실증: 신규 SQLite 에서 houses SELECT·house_id INSERT OK · 스키마_버전 8 · 재기동 멱등 · migrate --status "대기 없음" · ResetSelfTest 41 · 계약 7 · boundary 0 · **이관 21눈**(fixture 의 낡은 수동 008 을 검토자가 걷은 뒤) 전부 통과 (Fable 재실행)

PG-005 이관 자기 시험이 fixture 를 만들다 밟았다 (2026-07-14). `db/schema.sql` 은 **19표**다 —
`houses` 는 마이그레이션 008 에만 있다. 그런데 `Db.schemaVersionGate` 는 캐릭터가 0명인 신규 DB 를
"스키마가 이미 최신이므로 소급 불필요"라며 **버전 8 로 스탬프**한다. 표가 없는데 있다고 적는 것이다.

그 뒤는 이렇게 굴러간다: `tools/migrate_db.py` 는 버전을 보고 "최신"이라 판단해 008 을 건너뛰고,
`HouseStore` 가 `houses` 를 묻는 순간 죽는다. **운영 DB 는 무사하다** (008 을 실제로 맞았다) —
무너지는 것은 **새로 태어나는 DB** 다. SQLite 는 PG-008 전까지 기본 백엔드이므로 아직 빚이다.
(PostgreSQL 통합 스키마는 처음부터 20표라 이 병이 없다.)
### B-015 · **패링·가드·회피** — 우클릭이 비어 있다
- **상태**: 닫힘
- **분류**: 결함
- **단계**: P3
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/SkillListener.java:997`
- **의존**: B-005
- **닫는 조건**: 우클릭이 방어 태세를 부른다 · `SkillCast` 의 虛 관문이 근사(近似)를 그만둔다
- **검증**: `python3 tools/defense_audit.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

코드가 자기 입으로 말한다:
- `SkillListener.java:997` — `// 평범한 우클릭 — 상호작용은 세계의 몫 (가드·패링은 후속 배선)`
- `SkillCast.java:399` — `// 【배선 대기】 가드·패링·회피는 SkillListener 에 아직 없다 ("후속 배선").`

지금 우클릭에 묶인 것은 **Shift+우클릭**(`cycleArmed`) 하나뿐. 맨 우클릭은 **즉시 return** 한다.
`SkillCast` 의 `"패링"·"회피"·"반격"` 관문은 전부 **피격 후 창**(`lastHurt` + `COUNTER_WINDOW`)으로 **근사**된다 —
즉 **막는 행위가 아니라 맞은 뒤의 보상**이다.

### B-018 · 나루(입도진)를 **아무도 눈으로 못 봤다**
- **상태**: 미확인
- **분류**: 결함
- **단계**: P3
- **위치**: `tools/antechamber_audit.py:1693`
- **의존**: —
- **닫는 조건**: 인게임에서 나루의 **길 · 조명 · 글판**을 눈으로 본다
- **검증**: ★ 인게임 — `tools/antechamber_audit.py` 는 **블록을 안 읽는다**
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

`antechamber_audit.py` 의 11개 축 중 **①~⑩ 은 전부 정적**이다 — 자기 입으로 말한다 (`:1693`):
> *"위의 눈은 전부 **정적**이다 — config 와 소스만 읽는다."*

- **길** (`audit_road:1136`) — 파이썬 안에 `Grid` 를 세우고 BFS 를 돈다 (**시뮬레이션**)
- **빛** (`audit_light:1319`) — **파이썬 조명 모델**이다. 세계의 블록 광량을 **안 읽는다**
- **글판** (`audit_panels:841`) — 함수가 **존재하고 호출되는지**만 본다

축 ⑪ (`audit_world:1703`) 만 진짜 세계를 만진다 — `run/mvt/<world>/entities/*.mca` 를 열어
`ipdo_dummy`·`ipdo_panel` **바이트를 센다**. 그러나 **엔티티만** 센다. **블록은 하나도 안 읽는다.**

★ 그래서 **길·빛·글판의 기하는 여전히 재구성으로만 검증됐다.** 그리고 이 감사는 그 위험을 안다 (`:1694`):
코드도 config 도 로그도 "허수아비 셋"이라 했는데 — **세계엔 0마리**였다.

### B-019 · 나루에 허수아비가 **12몸** — 등록부는 6몸
- **상태**: 닫힘
- **분류**: 결함
- **단계**: P3
- **위치**: `tools/antechamber_audit.py:1703`
- **의존**: —
- **닫는 조건**: `ensureDummies()` 가 `==` 로 판단한다 (`>=` 가 아니라) → 감사 위반 0건
- **검증**: `python3 tools/antechamber_audit.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

실측 (2026-07-14): **세계에 12몸.** 재조성이 몸을 **쌓았다**.
겹쳐 선 허수아비는 히트박스가 겹쳐 **타격 계측을 망친다** — 허수아비는 **계기**다.

> **많은 것도 틀린 것이다.** (감사의 말)

### B-020 · `/혼천` 서브커맨드가 **25/25** — 더 못 넣는다
- **상태**: 보류
- **분류**: 빚
- **단계**: P3
- **위치**: `server-bot/src/main/java/com/honcheon/bot/HoncheonBot.java:93`
- **의존**: —
- **닫는 조건**: ★ 사람이 정해야 한다 — 서브커맨드 **그룹**으로 갈 것인가, 명령을 쪼갤 것인가
- **검증**: `server-bot/src/main/java/com/honcheon/bot/HoncheonBot.java:93` 의 `SubcommandData` 개수 < 25
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

정확히 **25개**다 (시작·정보·원장·사냥·비무·수련·사사·의뢰·대화·탐방·운기·출행·소문·의방·구조·전장·접속·접속해제·지역등록·정산·사선·사망·명분·사정·도움말).
디스코드의 **하드 상한이 25**다. **다음 기능은 들어갈 자리가 없다.**

> 탈출구는 있다: **서브커맨드 그룹**(미사용) · `gate`·`wipe` 는 별도 최상위 명령이라 여유가 있다.

> ★ **2026-07-14 갱신 — 탈출구가 둘 다 실증됐다** (그러나 **칸은 아직 25다** — 그래서 안 닫는다):
>
> ① **최상위로 빼면 칸을 안 먹는다.** `/안내판` 을 최상위로 세웠다 (`/접합문`·`/초기화` 와 같은 방식).
>    최상위 명령의 상한은 **길드당 100** 이고 지금 **4개**(`/혼천`·`/접합문`·`/초기화`·`/안내판`)뿐이다.
>    **압박은 서브커맨드 안에서만 존재한다.**
> ② **버튼으로 옮기면 칸이 준다.** 안내판의 버튼([강호에 들다]·[내 시트]·[몸을 잇는다]·[몸을 끊는다]·
>    [처음부터 다시])은 **슬래시와 같은 함수를 부른다** — 새 칸을 하나도 안 썼다.
>    ★ 다만 **옛 슬래시를 지우지 않았다**: 사용자의 지시다 (*"버튼이 안 뜨거나 모바일에서 막힐 때의
>    **뒷문**이 필요하다"*). 그래서 **버튼이 늘어도 칸은 안 준다** — 칸이 주는 것은 **지울 때**뿐이다.
>
> **★ 실제로 칸을 줄이는 길은 B-080 + B-083 이다** — 그리고 **둘의 결이 다르다**:
> · **B-080** (몸의 일 11종, `대화` 포함) — 마크로 **옮기고** 지운다
> · **B-083** (`소문`·`전장` 2종) — 옮기는 것이 아니라 **NPC 의 입·손으로 나오고**, 명령은 **죽는다**
> 둘 다 끝나면 **25 → 12칸**. 그때까지 이 항목은 **보류**다:
> 지금 당장 새 서브커맨드가 필요하면 **최상위로 빼라** (①).

### B-021 · 히트스톱이 **클라이언트 프레임을 못 멈춘다** (설계 한계)
- **상태**: 보류
- **분류**: 빚
- **단계**: P3
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/SkillListener.java:2680`
- **의존**: —
- **닫는 조건**: ★ 바닐라로는 못 닫는다 — 클라 모드 없이는 근사가 최선이다
- **검증**: ★ 인게임 — 감각의 문제다
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

`freezeTick()` (`:2680-2694`) 이 매 틱 `setVelocity(new Vector())` 로 **속도를 못질**한다.
코드가 한계를 안다 (`:2673`): *"서버는 클라이언트의 애니메이션 프레임을 멈출 수 없다. 그러나 몸의 속도는 서버의 것이다."*

**가만히 선 몸에선 거의 안 보인다** — 멈출 속도가 이미 0이므로.

### B-022 · **"상체가 크게 움직인다"** 는 바닐라로 불가능 (설계 한계)
- **상태**: 보류
- **분류**: 빚
- **단계**: P3
- **위치**: `config/skill_motion.yml`
- **의존**: —
- **닫는 조건**: ★ 못 닫는다 — `setRotation` 이 곧 카메라라 **멀미**가 된다
- **검증**: ★ 사람이 정해야 한다 — 이 감각을 포기할 것인가, 클라 모드로 갈 것인가
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

**기록해 두는 이유**: 누군가 다시 시도하기 전에 **왜 안 되는지** 알라고.

### B-023 · 레이캐스트 — 캡은 **admit** 에 걸리지 raycast **호출**에 안 걸린다
- **상태**: 닫힘
- **분류**: 빚
- **단계**: P3
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/SkillListener.java:2368`
- **의존**: —
- **닫는 조건**: 후보 목록 자체가 유한하다 — 또는 이것이 문제가 아님을 계측으로 보인다
- **검증**: `python3 tools/perf_audit.py` · `Metrics.record("판정_가려내기")` 의 실측
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

> ★ **씨앗이 틀렸다.** "레이캐스트 상한 없음" 은 **거짓**이다 — 캡이 있다:
> `SkillListener.java:2368` → `if (out.size() >= cast.maxTargets())` (`max_targets_default: 8`).

**그러나 좁은 진실이 남는다**: 캡은 **받아들인 표적**(`out.size()`)에 걸린다. `aimedAt` 을 통과했으나 LOS 에 걸린 후보는
`hasLineOfSight` 를 **소비하고도** `out` 을 안 늘린다. 그리고 후보 목록(`getNearbyEntities`, `:2286`)은 **무제한**이다.

실무상 작다 (`aimedAt` 이 걸러낸다). **"벽 뒤 적대 몸이 많으면"** 이라는 걱정은 남지만 **실측이 없다.**

---

# P3 — 빚

### B-024 · `recovery()` 를 **규칙이 안 읽는다** — `total()` 에 접혀 있다
- **상태**: 닫힘
- **분류**: 빚
- **단계**: P4
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/SkillEngine.java:1147`
- **의존**: —
- **닫는 조건**: 후딜이 **독립된 뜻**을 갖는다 (캔슬 창을 열려면 분리해야 한다)
- **검증**: `python3 tools/combat_audit.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

`record Frames(int startup, int active, int recovery)` — `total() = startup + active + recovery`.
**규칙 중 `recovery()` 를 읽는 것은 하나도 없다.** 전부 `total()` 을 쓴다 (`busyUntil`, 콤보 마감).
유일한 독자는 **디버그 HUD** (`SkillListener.java:2815` — `/혼천 타격보기`).

> 씨앗 정정: "어디서도 안 읽힌다" 는 **한 곳 틀렸다** (디버그 HUD가 읽는다). 그러나 **규칙은 하나도 안 읽는다** — 뜻은 같다.

### B-025 · `stagger()` 의 옛 몹 전용 넉백 (되돌림용)
- **상태**: 보류
- **분류**: 빚
- **단계**: P4
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/SkillListener.java:2586`
- **의존**: —
- **닫는 조건**: `impact.enabled` 가 굳으면 옛 길을 지운다
- **검증**: `python3 tools/combat_audit.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

`:2592` — `if (ticks >= 10 && target instanceof Mob)` → `setVelocity(...)`.
**의도적으로 남겼다** (`:2581`): *"impact.enabled: false 로 되돌렸을 때 이 세계가 정확히 옛 동작으로 돌아가야 하므로."*

★ 다만: `impact.enabled` 가 참일 때 **속도를 두 번 쓴다** (히트스톱 해제 전 창에서). 무해한지 **미확인.**

### B-029 · `perf_audit` — **한 틱 폭탄** · 상한 미상 2건
- **상태**: 닫힘
- **분류**: 빚
- **단계**: P4
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/CheonghaBuilder.java:300`
- **의존**: —
- **닫는 조건**: `perf_audit` 위반 0건 — 조성이 틱을 나눠 쓴다
- **검증**: `python3 tools/perf_audit.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

`[폭탄] CheonghaBuilder.build()` (`:300`) · `[상한 미상] SectBuilder.pagoda()` (`SectBuilder.java:118`) ·
`[상한 미상] CheonghaBuilder.generalStore()` (`:6012`).

> 전례: `9fdf956 [perf] 한 틱 폭탄을 해체했다 — MSPT 625ms → 13.9ms`. **같은 종류가 아직 남았다.**

### B-030 · `gap_audit` — **유령 절 112개** · 테스트만 부르는 메서드 39 · 호출자 없는 메서드 60
- **상태**: 닫힘
- **분류**: 빚
- **단계**: P4
- **위치**: `docs/design/gap_audit.md`
- **의존**: —
- **닫는 조건**: 유령 절이 준다 — 배선하거나, 지우거나, **미배선이라고 적거나**
- **검증**: `python3 tools/gap_audit.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

> **유령 절 112개** — *"규칙으로 적혀 있고, 굴러가지 않고, 아무도 그 사실을 재지 않는다."*
> **테스트만 부르는 메서드 39개** — *"파리티 테스트가 초록이어도 플레이어는 그 규칙을 겪지 않는다."*

★ 이것은 **한 항목이 아니라 112개의 항목**이다. 여기 적는 이유는 **재는 눈이 있다**는 것을 기억하기 위해서다.
줄일 때는 `gap_audit` 의 수를 **떨어뜨려** 증명하라.

### B-031 · 동행(party) — 엔진은 있으나 **명부가 없다**. 아군을 **의도**로 가른다
- **상태**: 닫힘
- **분류**: 빚
- **단계**: P4
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/SkillListener.java:2335`
- **의존**: —
- **닫는 조건**: 동행 명부(초대·가입·탈퇴)가 선다 → 아군이 **사실**로 갈린다
- **검증**: `python3 tools/game_audit.py` (party.yml 미참조 절 `time_sync` 가 사라지는가)
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

> 씨앗 정정: "party 시스템이 없다 · 자바 독자 없다" 의 **뒷절반은 거짓**이다 —
> `core/src/main/java/com/honcheon/core/rules/PartyEngine.java` 가 `party.yml` 을 읽는다.

**그러나 그것은 산수일 뿐이다.** 유일한 프로덕션 호출자는 **디버그 명령** `/혼천 협공 <인원>` (`MvtCommand.java:221`) —
인원수를 **타이핑으로 받는다**. **명부도, 초대도, 가입 상태도 없다.**

아군 판별은 코드가 자기 입으로 말한다 (`SkillListener.java:2335`):
> *"【무엇으로 아군을 가르는가 — 파티 시스템이 없다】 동행(party)은 아직 코드에 없다… 대신 **의도(意)**로 가른다"*

### B-032 · `장터` 앵커 = **마을 원점** — 옮기면 마을이 이사한다
- **상태**: 닫힘
- **분류**: 빚
- **단계**: P4
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/CheonghaBuilder.java:429`
- **의존**: —
- **닫는 조건**: **원점이 별도 앵커로 분리된다** (그것이 정답인데 안 했다)
- **검증**: `python3 tools/map_lint.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

`CheonghaBuilder.java:330` — `anchors.put("장터", loc(world, cx, cy + 1, cz))`. 원점에는 **우물**이 있다 — 발밑은 물.
코드가 병과 **고치지 않기로 한 이유**를 함께 적었다 (`:429-435`):

> *"**앵커를 옮기지는 않는다.** `장터` 앵커는 **14곳**에서 마을 원점 표식으로 쓰인다
> (콘솔 재조성은 `anchor("장터").getBlockY() - 1` 을 원점으로 삼는다 — 옮기면 **마을이 이사한다**)."*

지금의 우회는 `Standing.landing()` (`:437`). **정답은 원점을 쪼개는 것**이고, 안 했다.

### B-033 · `combat_audit` 경고 3건 — 숙련 스케일 · 심법의 관문 · **대칭 대결의 선공**
- **상태**: 닫힘
- **분류**: 빚
- **단계**: P4
- **위치**: `config/skills.yml`
- **의존**: —
- **닫는 조건**: `combat_audit` 경고 0건
- **검증**: `python3 tools/combat_audit.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

1. **숙련 스케일 불일치** — `skills.yml` 은 `0~10`, `mastery_ladder` 는 극성 **8** 이 상한, `judgment.yml` 은 `max: 10`.
   **8~10 구간의 뜻이 정의되지 않았다.**
2. **심법이 관문을 안 탄다** — `jeongsim_geomgyeol` 이 `simbeop.yml` 로 이관됐는데,
   *"심법이 액션 코스트 1과 패링 태세를 갖는다는 사실을 **어느 게이트도 검사하지 않는다**."*
3. ★ **대칭 대결에서 선공 규칙이 승자를 못 정한다** — *"민첩+감각 동률 → 경지 높은 쪽"* 인데 **경지도 같으면 그다음 규칙이 없다.**
   그리고 **선공은 전부다**: *"대칭 대결의 승패가 판정이 아니라 선공 결정에서 이미 끝난다 (선공자 내구 20% 잔존)."*

### B-034 · `gap_audit` — 문서가 약속한 키가 등록부에 **없다** (3건)
- **상태**: 닫힘
- **분류**: 빚
- **단계**: P4
- **위치**: `docs/design/gap_audit.md`
- **의존**: —
- **닫는 조건**: `gap_audit` 의 이 세 경고가 사라진다
- **검증**: `python3 tools/gap_audit.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

- `blood_debt.md` 가 `blood_debt.exposure_floor` 를 약속 — `faction_reaction.yml` 에 **그 키가 없다**
- `gyeonggong.md` 가 `messages.on` 을 약속 — `gyeonggong.yml` 에 **없다**
- `weapon_variance.md` 가 `armor.mitigation` 을 약속 — `equipment.yml` 에 **없다**

**문서가 거짓말한다.** (또는 등록부가 뒤처졌다.)

---

# P4 — 미완 (자산 · 조성)

> ★ **이 단계의 절반은 P5 가 막고 있다.** 손이 없어서가 아니라 **사람이 안 정해서** 안 선다.

### B-035 · **`.ogg` 소리 0종** — 파이썬으로 못 만든다
- **상태**: 보류
- **분류**: 미완
- **단계**: P5
- **위치**: `config/resourcepack_design.yml:929`
- **의존**: —
- **닫는 조건**: `.ogg` 가 생긴다 (약 30종) — **사람의 손 또는 외부 도구가 필요하다**
- **검증**: `bash -c "find resourcepack -name '*.ogg' | wc -l"` → 0 이 아니다
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

**실측 (2026-07-14): `.ogg` 파일 0개.** 등록부가 자기 입으로 적어 뒀다 (`:929`):
> *"【현황】 혼천의 소리 = **0종.** assets/*/sounds/ 에 .ogg 가 한 장도 없다."*
> *".ogg(Vorbis) 는 **파이썬으로 생성할 수 없다.** 이 저장소의 팩 파이프라인은 PNG·JSON 만"*

**배경음악 96종 전부 침묵.** ★ **이것은 코드로 못 닫는다.** 정직한 미완이다.

### B-036 · HUD 잔여 — `crosshair` · `air` · `boss_bar`
- **상태**: 닫힘
- **분류**: 미완
- **단계**: P5
- **위치**: `config/resourcepack_design.yml:995`
- **의존**: —
- **닫는 조건**: 세 스프라이트가 선다
- **검증**: `python3 tools/texture_audit.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

등록부가 남은 것을 안다 (`:995`): `남은_것: [crosshair, "air(물속 거품)", "boss_bar(보라색)"]`.
실측: `resourcepack/assets/minecraft/textures/gui/sprites/hud/` 에 hotbar·experience_bar·food·heart 만 있다.

### B-037 · **명병 4문파 미제작** (곤륜·청성·해남·개방)
- **상태**: 닫힘
- **분류**: 미완
- **단계**: P5
- **위치**: `config/resourcepack_design.yml:878`
- **의존**: —
- **닫는 조건**: `MYEONGBYEONG` 이 12문파를 갖는다
- **검증**: `python3 tools/build_resourcepack.py` · `python3 tools/model_key_audit.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

`tools/build_resourcepack.py:1555` 의 `MYEONGBYEONG` 에 **8문파**뿐 (hwasan·jeomchang·jongnam·namgung·mudang·paengga·dangga·sorimsa).
등록부가 이유까지 적었다 (`:878`):
> `gonryun·cheongseong·haenam·gaebang: "청구서 — 시간. **개방은 봉(棒) 계열 자체가 없다** (신설 선행)"`

★ **개방은 병기 계열부터 새로 만들어야 한다** — 다른 셋과 무게가 다르다.

### B-038 · 획(참격선) **2차 확장** — 반월형 궤적 · 문파 색 · 어검/심검
- **상태**: 닫힘
- **분류**: 미완
- **단계**: P5
- **위치**: `config/skill_motion.yml`
- **의존**: —
- **닫는 조건**: 검기 단계 반월형 파티클 궤적 · 문파별 색 분화 · 어검/심검 전용 형체가 선다
- **검증**: `python3 tools/motion_audit.py` · `python3 tools/texture_audit.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

★ **`ult` 판의 채도는 인게임에서 정할 값이다** (기계로 못 정한다 → **B-062**).

### B-039 · **조성 16곳이 조용히 아무것도 안 세운다** (53곳 중 32곳만 선다)
- **상태**: 닫힘
- **분류**: 미완
- **단계**: P5
- **위치**: `config/world_map.yml:841`
- **의존**: B-047, B-048, B-049, B-050
- **닫는 조건**: `map_lint` 의 「선다」가 53곳에 닿는다 (또는 「안 짓기로 함」으로 정직하게 닫힌다)
- **검증**: `python3 tools/map_lint.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

`map_lint` 검산 (2026-07-14):
```
✓ 선다                      32곳
✗ 미결 — 사람이_정해야         15곳   ← ★ P5 가 막고 있다
✗ 청구됨·손이_없다 (기관저택)     1곳   jegal  ← B-040
  안_짓기로_함                 2곳   gyeongsa nangyeong
  전용_조성기                  3곳
★★ 그러므로 16곳은 조성을 쳐도 조용히 아무것도 안 선다.
```

**32 + 15 + 1 + 2 + 3 = 53.** ★ **15곳은 코드가 없어서가 아니라 원형(archetype)을 사람이 안 정해서 안 선다.**

### B-040 · `jegal` — 원형은 **청구됐는데 그것을 지을 손이 없다**
- **상태**: 닫힘
- **분류**: 미완
- **단계**: P5
- **위치**: `config/world_map.yml:1275`
- **의존**: B-050
- **닫는 조건**: `RemoteBuilder.Archetype` 에 `기관저택` 이 선다
- **검증**: `python3 tools/map_lint.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

> ★ **씨앗이 이것을 잘못 분류했다.** `jegal` 은 `pending` 이 **아니다** — `world_map.yml:1275` 에 `archetype: 기관저택` 이 **있다**.
> 병이 다르다: **원형은 정해졌는데 그 원형을 아는 빌더가 없다** (`RemoteBuilder` 의 21개 원형에 `기관저택` 이 없다).

그러나 **먼저 물어야 한다**: *"무엇이 있어야 제갈이 제갈인가"* (**B-050**). 그것 없이는 지어도 제갈이 아니다.

### B-041 · **바다를 파는 손이 없다** — 섬 둘과 해관이 바닐라 해안에 앉는다
- **상태**: 닫힘
- **분류**: 미완
- **단계**: P5
- **위치**: `config/terrain.yml:192`
- **의존**: —
- **닫는 조건**: `TerrainForge` 가 물을 만든다 — 섬이 **섬**이 된다
- **검증**: `python3 tools/map_lint.py` · `server-mvt/src/main/java/com/honcheon/mvt/TerrainAudit.java:416`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

등록부가 빚이라고 적었다 (`terrain.yml:192`):
> *"★ **바다를 파는 손이 없다.** TerrainForge 는 물을 만들지 않는다 (tidyWater 는 정리만).
> … 그러므로 이것은 지형 담당에게 넘기는 빚이다. **그때까지 섬은 바닐라 해안에 앉는다**"*

★ **세 곳이다** (씨앗은 둘이라 했다): `dongyeong_do` · 섬 하나 · 그리고 **`sibaksa_myeongju`**(해관) —
`terrain.yml:204`: *"바다에 서야 하는 해관. 바다는 만들지 않으므로 바닐라 해안에 기댄다 (**보장 없음**)"*

### B-042 · **배가 없다** — 땅이 배를 기다리며 선다
- **상태**: 보류
- **분류**: 미완
- **단계**: P5
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/TerrainAudit.java:416`
- **의존**: B-041
- **닫는 조건**: 배가 생긴다 — 또는 섬에 닿는 다른 길이 생긴다
- **검증**: ★ 인게임 — 섬에 **닿을 수 있는가**
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

`TerrainAudit.java:416` 이 사용자의 말을 인용한다:
> *"★ **배는 아직 없다** (사용자: '배는 아직 없다. 땅이 배를 기다리며 선다'). 그래서 이 축은 **땅만 잰다** — '배가 닿을 수 있는 땅인가'"*

**나루 축(`dock()`)은 세워졌다.** 닿을 **수단**이 없다.

### B-085 · **건축의 미결이 지형을 인질로 잡았다** — 집을 못 정하면 **땅도 못 선다** (2계층 계약 역전)
- **상태**: 진행
- **분류**: 결함
- **단계**: P2
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/TerrainGate.java`
- **의존**: —
- **닫는 조건**: **땅이 건축의 미결과 무관하게 선다** — 사용자가 `/혼천 지형조성` 을 쳐서 확인
- **검증**: `tools/TerrainGateSelfTest.java` (서버 없이 돈다 · 눈 13개) · 인게임 `/혼천 지형조성 gangnam_sangro`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

★ **2026-07-14 (지형 담당) — 게이트를 갈랐다.** 계약(`docs/design/gate_and_watertown.md`)의 순서 그대로다:
`① 지형 게이트 → ② 조성 → ③ 영수증 → ④ 건축 게이트 → ⑤ 건축 / ⑥ 땅만 남기고 사유 출력`.
`MvtCommand.region` 이 더 이상 건축 미결로 **돌아서지 않는다** — `architecture_state: blocked` 는
**정상 상태**로 출력되고 땅은 제 길을 간다. 새 문 `TerrainGate` 가 **땅의 일만** 묻는다.
- ★ **의존을 지웠다**: B-052(등급)·B-084(수향)에 기대지 **않는다** — 기대면 그것이 바로 이 결함이다.
  (감사가 단계 역행으로 짖던 이유이기도 하다: P2 가 P5 를 기다릴 수 없다.)
- **남은 것**: 사용자의 인게임 확인 (서버 기동은 내 권한 밖).

**계약**(사용자 원문): *"**땅에 맞게 건물이 올라가는 것이지, 건축에 맞게 지형이 생기는 게 아니다**"* ·
*"지도로 표현 → 지형 구체화 → **이후** 지역에 맞게 건축"*.

★ **그런데 코드는 정확히 거꾸로 서 있었다.** `MvtCommand.java:472` (`buildRegion`):
```java
java.util.List<String> blockers = RemoteBuilder.unbuildableReasons(place);
if (!blockers.isEmpty()) {
    sender.sendMessage(ChatColor.RED + place.name() + " — " + String.join(" · ", blockers));
    return true;              // ★ 여기서 돌아선다 — 지형·강·굴, 아무것도 안 돈다
}
```
`Terraform.land(...)` 은 이 **뒤**에 있다 (`:663`). 그러므로 **`build_radius` 가 미결이면 땅조차 안 빚어진다.**

★★ **그런데 땅은 `build_radius` 를 쓰지도 않는다.** 지형 반경은 **전역 상수 하나**다 —
`int forgeRadius = plugin.worldMap().forgeRadius();` (`:641`, §1-b `land.forge_radius` = 110).
등록부가 그 이유를 적어 두었다: *"**땅은 세력을 모른다.** 산은 문파가 흥하든 망하든 그 자리에 그만큼 있다"* (`world_map.yml:64`).
⇒ **땅이 알 필요 없는 것 때문에 땅이 못 서고 있었다.**

**증거 (원장)**: `run/mvt/plugins/HoncheonMVT/terrain_built.yml` 에 땅이 **딱 한 곳** 있다 — `jangang_suroche`.
강남 상로는 `rivers.yml` 에 물길이, 지도에 `terrain: 수향` 이 **이미 적혀 있는데도** 땅이 **없다**.

**한 일**: `/혼천 지형조성 <id>` 를 냈다 (`MvtCommand.forgeLand` → `region(…, terrainOnly=true)`).
건축 게이트를 **묻지 않고** 땅을 빚고, **집이 왜 못 서는지는 그대로 말한다** (조용한 성공을 만들지 않는다).
`/혼천 지역조성` 은 **한 줄도 안 바뀌었다** (다른 담당의 흐름에 영향 없음).

★ **남은 것 — 사람이 정할 일**: `지역조성` 자체의 **게이트 순서**는 그대로다 (여전히 땅 앞에서 돌아선다).
문을 하나 더 낸 것이지 **배관을 고친 것이 아니다.** 게이트를 「땅은 빚고 · 집만 거절」로 바꾸면
**미결 16곳 전부**가 땅을 갖게 된다 — 그것은 되돌리기 어려운 변경이라 **묻지 않고 하지 않았다.**

### B-084 · **수향(水鄕)을 빚는 손이 없다** — 「물의 고장」이 코드에선 **강 하나 옆의 마을**과 같다
- **상태**: 닫힘
- **분류**: 미완
- **단계**: P5
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/TerrainForge.java:691`
- **의존**: B-043 (요청 어휘가 문이다) · B-052 (반경이 없으면 땅이 안 선다)
- **닫는 조건**: `수향` 이 `강` 과 **다르게 빚어진다** — 물이 골목이 된다
- **검증**: `python3 tools/map_lint.py` · ★ 인게임 — 청하현과 **한눈에 다른가**
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

**등록부는 세 곳을 `terrain: 수향` 이라 적었다** — `gangnam_sangro`(`:949`) · `soju`(`:921`) · `namgung`(`:1263`).
그런데 **코드에는 수향이 없다.** `TerrainForge.profile()` 의 switch 에 `수향` 이 **없어서** `default` 로 떨어진다:

> `TerrainForge.java:691` — `default -> Profile.그대로;   // 강·수향·섬·밀림 — 빚지 않는다`

그러므로 지금 「물의 고장」이 받는 것은 **`RiverForge` 가 파는 물길 한 줄**이 전부다
(`rivers.yml:103` — 강남: `width: 26 · depth: 6`). 그것은 **`terrain: 강` 과 완전히 같은 대접**이다.

★ **「물이 골목이고 뭍에 집이 있다」는 물길 한 줄이 아니라 물길의 그물이다.** 그 그물을 파는 손이 아무 데도 없다
(`canal`·`운하`·`물골목` — 자바 전체에 **한 글자도 없다**). ★ 이것은 **B-041(바다를 파는 손이 없다)의 형제**다.

★ **문은 이미 있다** — `LandRequest` 의 어휘 `물 · 길`(개울)이 곧 물골목이다
(`land_requests.yml`: `max_width: 6 · max_depth: 12 · max_path: 120 · max_requests: 12`).
**자바를 고칠 일이 아니라 등록부가 주문할 일일 수 있다** (B-043 이 그 칸이 비었다고 말한다).
다만 **요청 12개·폭 6 이 「고을을 관통하는 물골목 그물」에 충분한지는 재 보지 않았다 — 모른다.**

★ 곁가지: `soju` 는 `axis_offset: 0.30` 을 적으며 *"물길이 골목이면 마을이 물을 끼고 앉는다"* 라 했는데,
**`gangnam_sangro` 에는 `axis_offset` 이 없다** (기본 0.45). 같은 `수향` 인데 마을이 물에서 더 멀다 —
**의도인지 누락인지 모른다. 지어내지 않고 묻는다.**

### B-043 · `land_requests.yml` 의 `requests:` 가 **비어 있다** → 조성이 제 단 안을 팠다
- **상태**: 닫힘
- **분류**: 미완
- **단계**: P5
- **위치**: `config/land_requests.yml:127`
- **의존**: —
- **닫는 조건**: 남궁이 **물을 청구**한다 · 지형 계층이 판다 (조성이 아니라)
- **검증**: `python3 tools/map_lint.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

`land_requests.yml:127` (마지막 줄): **`requests: {}`** — 비었다. 위는 전부 주석 처리된 예시다.
등록부는 남궁이 물이 필요한 걸 안다 (`world_map.yml:3446`): *"★ **물이 필요하다** — 지형 계층에 청구해야 한다 (Q-J)"*

**그런데 조성기가 제 손으로 팠다** (`EstateBuilder.java:243`):
> *"// ★ 연못 — 조성이 깐 단 **안**을 판다. 자연 지형은 안 만진다"*

그리고 스스로 의심한다 (`:228`): *"**그러나 이것이 최선인지는 확신하지 않는다**"*

### B-044 · 미배선 등록부 3종 — `chapter_events` · `village_tiers` · `cheongha_hyeon_map`
- **상태**: 보류
- **분류**: 미완
- **단계**: P5
- **위치**: `docs/design/gap_audit.md`
- **의존**: —
- **닫는 조건**: 각 파일이 문서에 적은 **신설 조건**이 충족되면 배선한다
- **검증**: `python3 tools/gap_audit.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

**셋 다 「★ 명시적 미배선」이다** — *"아무도 안 읽는다. **그리고 그렇다고 적혀 있다.**"*
**거짓말이 아니라 계획된 약속**이므로 위반이 아니다. 조건은 각 문서 머리에 있다.

★ **이 항목은 "지금 하라"가 아니라 "잊지 마라"다.**

### B-045 · `design_review` 의 **G7 · G8** 이 남았다
- **상태**: 닫힘
- **분류**: 미완
- **단계**: P5
- **위치**: `docs/design/design_review.md`
- **의존**: B-005
- **닫는 조건**: G7(전투 상세)이 `combat_system.md` 로 닫힌다 · G8 은 MMO 단계로 미룬다
- **검증**: `python3 tools/combat_audit.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

`design_review.md` 의 공백 목록 G1~G8 중 **G1~G6 은 닫혔다** (문서가 증거와 함께 적어 뒀다 — **좋은 선례다**).
**남은 것**: **G7** (전투 상세 규칙) · **G8** (공유 세계 동시성 — *"MMO 단계 과제로 미뤄도 됨"*).

### B-046 · `game_audit` 경고 6건 — **엔진이 읽는 파일인데 미참조 절**
- **상태**: 닫힘
- **분류**: 미완
- **단계**: P5
- **위치**: `config/economy.yml`
- **의존**: —
- **닫는 조건**: `game_audit` 경고 0건
- **검증**: `python3 tools/game_audit.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

`economy.yml`(unpurchasable, tolls) · `judgment.yml`(npc_modifiers, side_risks, retry) ·
`npc_lifecycle.yml`(emergent_interactions) · `party.yml`(time_sync → **B-031**) ·
`player_creation.yml`(commoner_identities) · `rumor.yml`(rumor_schema)

**규칙을 적어 놓고 엔진이 그 절을 안 편다.**

---

# P5 — ★ 사람이 정해야 한다 (코드로 못 푼다)

> **이 단계가 P4 의 절반을 막고 있다.** 조성 15곳이 여기서 멈춰 있다.
> ★ 여기 있는 것은 **일이 아니라 질문**이다. **답하기 전에는 아무도 못 짓는다.**

### B-047 · 상단(商團) 6곳의 **원형이 없다** — 크기는 풀렸고 형태가 없다
- **상태**: 보류
- **분류**: 결정
- **단계**: P2
- **위치**: `config/world_map.yml:841`
- **의존**: —
- **닫는 조건**: 6곳에 `archetype` 이 정해진다
- **검증**: `python3 tools/map_lint.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

`archetype: pending` — 태원(`:841`) · 무한(`:872`) · 소주(`:905`) · 강남상로(`:931`) · 고창(`:1719`) · 돈황(`:1902`).

### B-048 · 새외(塞外) 4곳의 **원형이 없다**
- **상태**: 보류
- **분류**: 결정
- **단계**: P2
- **위치**: `config/world_map.yml:1763`
- **의존**: —
- **닫는 조건**: 4곳에 `archetype` 이 정해진다
- **검증**: `python3 tools/map_lint.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

성화단(`:1763`) · 설역대사(`:1795`) · 오독채(`:1836`) · 동영도(`:1866`).

### B-049 · **점창 · 청성** — 사용자가 직접 설계하기로 했다
- **상태**: 보류
- **분류**: 결정
- **단계**: P2
- **위치**: `config/world_map.yml:1086`
- **의존**: —
- **닫는 조건**: 사용자가 두 문파의 형태를 정한다
- **검증**: `python3 tools/map_lint.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

점창(`:1086`) · 청성(`:1108`). ★ **다른 사람이 대신 정하면 안 된다** — 사용자가 직접 하기로 한 것이다.

### B-050 · **제갈** — *"무엇이 있어야 제갈이 제갈인가"*
- **상태**: 보류
- **분류**: 결정
- **단계**: P2
- **위치**: `config/world_map.yml:1275`
- **의존**: —
- **닫는 조건**: 기관저택의 **뜻**이 정해진다 → 그 다음에 빌더를 만든다 (**B-040**)
- **검증**: `python3 tools/map_lint.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

**원형 이름은 있다** (`기관저택`). **뜻이 없다.** 기관(機關)이 무엇을 하는가 — 함정인가, 자물쇠인가, 미로인가.
**답이 없으면 지어도 제갈이 아니다.**

### B-051 · 나머지 미결 원형 — 독문 · 마교 낙양분타 · 시박사
- **상태**: 보류
- **분류**: 결정
- **단계**: P2
- **위치**: `config/world_map.yml:1521`
- **의존**: —
- **닫는 조건**: 3곳에 `archetype` 이 정해진다
- **검증**: `python3 tools/map_lint.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

독문(`:1521`) · 마교 낙양분타(`:1602`) · 시박사(`:2831` — ★ 그리고 **바다가 필요하다**, B-041).

### B-052 · `commercial_class` 4곳 · `wealth_tier` 6곳 — **등록부에 근거가 없다**
- **상태**: 보류
- **분류**: 결정
- **단계**: P2
- **위치**: `config/world_map.yml:3775`
- **의존**: —
- **닫는 조건**: 10개 값이 정해진다
- **검증**: `python3 tools/map_lint.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

`commercial_class` **4곳**: donhwang · gangnam_sangro · soju · taewon.
`wealth_tier` **6곳**: 위 넷 + muhan + seoyeok_gochang.

등록부가 **자기 입으로 이유를 적었다** (`:3775-3786`) — 예: 태원 *"'산서 상인의 도시'는 **성격이지 등급이 아니다**. 등록된 길이 하나도 안 지난다"* ·
소주 *"등록부가 상업 기능을 **한 줄도 안 적었다**"*.

**정직한 미결이다.** (`build_radius` 4곳 · `settlement_role` 4곳도 같이 비어 있다.)

### B-053 · **모용(북방저택)** — 들어갈 수 없는 집의 **담 안을 지을 것인가** (Q-C)
- **상태**: 보류
- **분류**: 결정
- **단계**: P2
- **위치**: `config/world_map.yml:1295`
- **의존**: —
- **닫는 조건**: 사용자가 답한다
- **검증**: `python3 tools/map_lint.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

`world_map.yml:1295` — *"★ 원형은 정해졌으나 어디까지 지을지가 미결이다 — sect_lineage.md 5장: **입문 불가**.
**들어갈 수 없는 집의 담 안을 지을 것인가?** (Q-C)"*

★ **막지는 않는다** — `moyong` 은 `archetype: 북방저택` 과 `build_radius: 48` 을 갖고 **오늘 선다**.
**범위**만 안 정해졌다.

### B-054 · ★ **「전성기 영향력」이 등록부에 없다** (Q-I) — `build_radius` 가 잘못된 값을 썼다
- **상태**: 보류
- **분류**: 결정
- **단계**: P2
- **위치**: `config/world_map.yml:3919`
- **의존**: —
- **닫는 조건**: 「전성기 영향력」이 등록부에 선다 → `build_radius` 를 다시 매긴다
- **검증**: `python3 tools/map_lint.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

계약은 명확하다 (`world_map.yml:3918`):
> *"★ 그러므로 `build_radius` 는 **전성기(peak) 값**이다. 지금의 영향력이 아니라 **그 세력이 가장 컸을 때**다"*

그런데 값을 뽑은 곳은 `faction_politics.yml` 의 `roster` — **현재값**이고, `faction_politics` 가 **동적으로 굴린다**.
등록부가 스스로 묻는다 (`:3919`): *"★ 다만 **'전성기 영향력' 이라는 값은 등록부에 없다.** … **물어야 한다** (Q-I)"*

★ **코드 버그가 아니라 데이터 버그다** — 사다리는 **손으로** YAML 에 박혔고, 런타임에 영향력을 읽는 코드는 없다.
**땅은 한 번만 선다.** 잘못 서면 못 옮긴다.

### B-055 · `land.forge_radius: 110` — 옛 상수를 옮긴 것. **110 이 옳은 수인가**
- **상태**: 보류
- **분류**: 결정
- **단계**: P2
- **위치**: `config/world_map.yml:287`
- **의존**: —
- **닫는 조건**: 사용자가 110 을 승인하거나 고친다
- **검증**: `python3 tools/map_lint.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

등록부가 **정직하다** (`:288`):
> *"★ 110 은 **이미 서 있던 값이다** (옛 코드의 비-녹림 상수). ★★ **지어낸 수가 아니다** — 예외(24)를 지웠을 뿐 값을 새로 만들지 않았다."*

**출처는 있고 근거는 없다.** 그리고 `TerrainLedger`/`TerrainSeal` 이 이미 그 반경으로 땅을 **봉인했다** — 옮기기 어렵다.

### B-056 · 성별 **±1 (남 근력 / 여 민첩)** — 지어낸 값. 승인 대기
- **상태**: 보류
- **분류**: 결정
- **단계**: P2
- **위치**: `config/player_creation.yml:80`
- **의존**: —
- **닫는 조건**: 사용자가 승인하거나 고친다
- **검증**: `core/src/test/java/com/honcheon/core/rules/GenderGateTest.java`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

**살아 있다**: `player_creation.yml:80-84` — `남: {근력: 1}` · `여: {민첩: 1}` · `hidden: true` · `applies_to: judgment` · `cap: 1`.
`GenderEngine.attrModifier` 가 먹이고, **유일한 프로덕션 호출처**는 `GameListener.java:1109` (서장 판정).

**시트에서 숨겨져 있다** (`hidden: true`) — `/혼천 정보` 는 이 보정을 안 보여 준다. **의도인지 확인 필요.**

### B-057 · **여자의 민첩 보정을 실제 이속에도 태울 것인가**
- **상태**: 보류
- **분류**: 결정
- **단계**: P2
- **위치**: `config/player_creation.yml:75`
- **의존**: B-056
- **닫는 조건**: 사용자가 정한다 → 정하면 경공 담당이 배선한다
- **검증**: `server-mvt/src/main/java/com/honcheon/mvt/GyeonggongListener.java:585`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

**지금은 안 탄다.** 이속은 `Gyeonggong.java:325` 가 **날 민첩**에서 뽑는다. 성별 +1 은 `judgmentStat` 안에만 있고,
**MVT 플러그인은 `GenderEngine` 을 아예 참조하지 않는다.**

등록부가 인정한다 (`player_creation.yml:75-79`):
> *"실제 이속 가산은 **경공 담당의 파일이 읽는 값**이라 이번에 배선하지 않았다."*

**설계됐고, 문서화됐고, 구현 안 됐다.**

### B-058 · **성별이 무공 계열을 가르는가** (지금은 안 가른다)
- **상태**: 보류
- **분류**: 결정
- **단계**: P2
- **위치**: `config/player_creation.yml:94`
- **의존**: —
- **닫는 조건**: 사용자가 정한다
- **검증**: `core/src/test/java/com/honcheon/core/rules/GenderGateTest.java`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

지금 성별이 가르는 것은 **문파 입문 하나뿐** (`ami: [여]`) — 그리고 그 문은 **도달 불가능하다** (**B-010**).
무공 계열은 **안 가른다.**

### B-059 · `ult` 판의 **채도** — 인게임에서 정할 값
- **상태**: 보류
- **분류**: 결정
- **단계**: P5
- **위치**: `config/skill_motion.yml`
- **의존**: B-038
- **닫는 조건**: 사용자가 인게임에서 보고 정한다
- **검증**: ★ 인게임 — 화면에서만 판단할 수 있다
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

> 전례: `5e7a844 [fix] 팩이 어두웠다 — 수묵을 "어둡다"로 오역했다 (매화 밝기 73 → 170)`
> **화면 밖에서 고른 색은 화면 안에서 틀린다.**

### B-060 · 숙련 스케일 — **8~10 구간의 뜻이 없다**
- **상태**: 보류
- **분류**: 결정
- **단계**: P2
- **위치**: `config/skills.yml`
- **의존**: —
- **닫는 조건**: 상한이 8 인가 10 인가 — 사람이 정한다
- **검증**: `python3 tools/combat_audit.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

`skills.yml` = `0~10` · `mastery_ladder` = 극성 **8** 이 상한 · `judgment.yml scales.skill.max` = **10**.
**세 등록부가 서로 다른 말을 한다.** (→ B-033 의 경고 ①)

### B-061 · ★ **대칭 대결의 승패가 판정이 아니라 선공에서 끝난다**
- **상태**: 보류
- **분류**: 결정
- **단계**: P2
- **위치**: `config/combat.yml`
- **의존**: B-005
- **닫는 조건**: 완전 동률의 **다음 규칙**이 정해진다 (그리고 선공의 무게를 정한다)
- **검증**: `python3 tools/combat_audit.py`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

`combat_audit` 경고 ③ (실측):
> *"완전 대칭 대결(같은 경지·같은 능력치)에서 **선공 규칙이 승자를 정하지 못한다** — '민첩+감각 동률 → 경지 높은 쪽'인데
> **경지도 같으면 그다음 규칙이 없다.** 그리고 **선공은 전부다**: 대칭 대결의 승패가 판정이 아니라
> **선공 결정에서 이미 끝난다** (선공자 내구 20% 잔존)."*

★ **이것은 두 개의 질문이다**: ① 동률을 어떻게 깨는가 ② **선공이 이렇게 강해도 되는가.**
②가 진짜 질문이다.

### B-062 · **소림 · 불가가 남성 전용인가**
- **상태**: 보류
- **분류**: 결정
- **단계**: P2
- **위치**: `config/player_creation.yml:94`
- **의존**: —
- **닫는 조건**: 사용자가 정한다 (그리고 등록부가 그것을 적는다)
- **검증**: `core/src/test/java/com/honcheon/core/rules/GenderGateTest.java`
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. 우클릭이 손을 세운다 — 넷째 태세의 발명 없이 탁상 규칙의 **방어_전념(+2·행동 소모)**을 MC 로 환산했다 (`config/combat.yml` attack.defender_stance_mc.active_guard 신설 · 지속은 realtime.round_ticks 를 그대로 읽어 이중 등록 회피). 선언은 남의 눈에도 보이고(vfx 등록부), 공격하면 깨지며(break_on_attack), 쿨다운 없음 불변식 유지. **패링 = 선언 앞머리 6틱 안에서 태세가 이긴 것** — 재선언은 패링 시계를 안 늘려 연타가 정답이 못 된다. 虛 관문(패링·회피·반격)은 피격 후 창(lastHurt+COUNTER_WINDOW, 코드 상수) 근사를 폐지하고 세 판정길이 전부 지나는 stanceWon 기록(lastParry·lastDodge·lastStanceWin)을 읽는다 — 창은 opening_window_ticks 로 등록부 승계. 실측: 컴파일 0 · defense/combat/motion_audit 위반 0 (Fable 재실행). NPC 쪽 선언 대칭과 감사의 active_guard 층은 B-108 로 올림

> ★ **씨앗이 틀렸다.** "등록부에 근거 없음" 이 아니라 — **등록부가 일부러 비워 뒀다.**

`player_creation.yml:94-99` 의 `gates.faction_entry` 에는 `ami: [여]` **하나뿐**이고,
`sorimsa` · `bulga` 는 **주석으로** `# sorimsa: ?` 라고만 적혀 있다 —
*"등록부는 그것들이 남성 전용이라고 **한 번도 말한 적 없다**"* 는 메모와 함께.

엔진도 그렇게 군다 (`GenderEngine.java:141-150`): 표에 없는 문파는 **누구나 받는다**.

**질문은 열려 있고, 코드는 정직하게 "모른다"고 답하고 있다.** 답할 사람은 사용자다.

---

# 닫힌 것 (지우지 않는다 — 왜 닫혔는지가 증거다)

### B-005 · ★ 기본 초식에 **대립 판정이 없다** — 피해가 바닐라 그대로
- **상태**: 닫힘
- **분류**: ★세계
- **단계**: P1
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/SkillListener.java:1076`
- **의존**: —
- **닫는 조건**: 평타가 판정층을 통과한다 — 태세(회피·막기·흘리기)·마진·격 위력이 피해에 실린다
- **검증**: `python3 tools/combat_audit.py` · `python3 tools/defense_audit.py`
- **닫힘**: 2026-07-14 · 병렬 R1 트랙 갑. 평타가 드디어 판정층을 지난다 — `SkillListener.java` 신설 `basicJudged()` 가 npcStrike(:861-889)와 같은 층 순서(격 지불 → 태세·마진 → 갑옷 → 기 방어)로 즉발·선딜 두 경로를 태운다. 격 위력은 지불한 타격에만 실리고(qiPower — "FX에만 먹인다" 병 종결), 마진은 resolve 의 PvP 근사(+7) 규약 그대로, 막기만 무기를 태운다(soak_rule 두 줄). impact 토글이 판정층을 못 끄게 분리. 새 수치 0개 — `config/combat.yml` attack.basic_strike_judgment 는 각 항의 출처를 적은 등록부 문서다. 실측: 컴파일 0 · `python3 tools/combat_audit.py` 위반 0 · `python3 tools/defense_audit.py` 위반 0 (Fable 재실행 확인 · 잔여 경고 6건은 전부 기존 — B-033 등). 남은 것은 B-105(판정의 눈에 평타 미배선)로 올림

**다음 바퀴의 1순위였다.** `basicMelee` (`SkillListener.java:1063-1116`) 에서
`double raw = event.getDamage()` (`:1076`) 가 **그대로** `target.damage(raw, player)` (`:1109`) 로 간다.
`guardline()` 도 `roll2d6()` 도 `margin` 도 없다. `grade` 는 계산하지만 **FX 에만** 먹인다 —
`engine.qiPower(grade)` 는 피해에 **안 더해진다**.

**결과: 방어자의 태세가 통째로 무시된다.** 플레이어가 평타를 치는 순간 막기도 흘리기도 회피도 없다.

> 범위 정정 (실측): 대립 판정은 **다른 두 길에는 있다** — `npcStrike` (`:861-889`) 와 무공 `resolve` (`:1736-1768`).
> 구멍은 **플레이어 평타 하나**다.

### B-008 · game_audit 위반 4건 — 미등록 장소 `cheongha` · 미등록 NPC 3
- **상태**: 닫힘
- **분류**: 결함
- **단계**: P1
- **위치**: `config/reset.yml`
- **의존**: —
- **닫는 조건**: `game_audit` 위반 0건
- **검증**: `python3 tools/game_audit.py`
- **닫힘**: 2026-07-14 · 병렬 R1 트랙 병. 위반 1~3은 **감사의 오독이 맞았다** — `by:` 는 reset.yml 에선 DB 컬럼(WHERE 축)인데 눈이 NPC 로 읽었다. 하드코딩 예외 대신 **등록부 대조**로 풀었다: `tools/game_audit.py` 의 `schema_columns()` 가 `db/schema.sql` 에서 컬럼 어휘를 파싱해, 실재하는 컬럼명만 DB 축으로 인정한다 (실재하지 않으면 여전히 짖는다 — simbeop.yml 의 `by: hyegak` 같은 진짜 NPC 참조는 탐지 유지, 변이 3종으로 확인). 위반 4는 진짜였다: 가축 4종의 `location: cheongha` 는 지역 id 의 준말 오기 → `cheongha_hyeon` 정정 (`config/npcs/cheongha_npcs.yml:538·552·566·580` — 실제 배치는 hunting_grounds 정원제가 정하므로 등록부용 메타만 바로잡음). 실측: `python3 tools/game_audit.py` → 위반 0건·exit 0 (Fable 재실행 확인)

실측 (2026-07-14) — 위반 4 · 경고 6:
1. 미등록 NPC `character_id` — `reset.yml tables.character_bank.by` 외 3곳
2. 미등록 NPC `mc_uuid` — `reset.yml tables.mvt_link.by` 외 2곳
3. 미등록 NPC `owner_id` — `reset.yml tables.registry.by`
4. **미등록 장소 `cheongha`** — `npcs/cheongha_npcs.yml npcs.dak.location` 외 3곳

1~3 은 **감사의 오독으로 보인다** (`by:` 는 NPC 가 아니라 **DB 컬럼명**이다) — 그렇다면 이것도 P0 의 병이다. **미확인.**
4 는 진짜로 보인다 (`cheongha` 가 장소 등록부에 없다).

### B-009 · 다리가 끊겼다 — `bandit_camp_cleared` · `bandit_boss_succeeded` 를 봇이 안 받는다
- **상태**: 닫힘
- **분류**: ★세계
- **단계**: P1
- **위치**: `server-bot/src/main/java/com/honcheon/bot/Bridge.java:278`
- **의존**: B-002
- **닫는 조건**: `bridge_audit` 위반 0건 — 두 사건이 봇 원장에 닿는다
- **검증**: `python3 tools/bridge_audit.py`
- **닫힘**: 2026-07-14 · Codex 가 두 처리기를 이었다 — `server-bot/src/main/java/com/honcheon/bot/Bridge.java:279` (`bandit_camp_cleared`) · `:280` (`bandit_boss_succeeded`), 공통 처리기는 `world_bridge.yml` 의 `effects.region_event` 이름만 읽는다 (수치 하드코딩 없음). 실측: `python3 tools/bridge_audit.py --no-backup` → 두 사건 배선 전부 ✅ · 위반 0건. 완료 문서 `docs/collaboration/CODEX_B009_COMPLETE.md` · Fable 재검증 후 장부 병합 (오버레이 v2). ★ 다만 **인게임 실발생은 아직 0건**이다 — 실제 소탕·승계가 일어나면 events·regions·bridge_inbox 를 대조하라 (완료 문서 §인계 지점)

플러그인은 **보낸다** (`HuntingGrounds.java:975` · `:1113`). 등록부에도 **있다** (`world_bridge.yml:308` · `:316`).
`Bridge.java:278-289` 의 switch 에는 **case 가 없다** — 10개만 받는다.
사실은 그보다 앞서 죽는다: `Bridge.java:268-271` 의 `kinds()` 등록부 검사에서 "미등록 이벤트 무시"로 버려진다.

**산채를 밀어도 세계가 모른다.**


### B-105 · 판정의 눈이 **평타를 못 본다** — eyeRoll/eyeDamage 는 Cast 만 안다
- **상태**: 닫힘
- **분류**: 빚
- **단계**: P4
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/SkillListener.java`
- **의존**: —
- **닫는 조건**: B-005 의 평타 판정(basicJudged)이 판정의 눈(eyeRoll·eyeDamage 계열)에 뜬다 — 무공과 같은 가시성
- **검증**: `server-mvt/src/main/java/com/honcheon/mvt/SkillListener.java` (basicJudged 에 눈 배선 존재)
- **닫힘**: 2026-07-14 · 병렬 R2 트랙 갑'. eyeRoll 의 **한 번도 안 쓰이던 Cast 파라미터를 제거** — 무공과 평타가 같은 함수·같은 문법으로 눈에 뜬다 (basicJudged 가 판정에 쓴 값 그대로). 맞는 쪽의 눈 eyeStance 신설 (거울상): 태세·판정치·주사위·마진에 선언(+2)·패링 태그까지 — npcStrike·basicJudged·resolve 세 길 모두 배선. 기존 출력 문법 불변. 실측: 컴파일 0 · 감사 3종 위반 0 (Fable 재실행)

B-005 를 닫으며 드러났다 (2026-07-14). 판정의 눈은 `Cast` 를 요구해 평타의 마진·태세·격이
디버그 화면에 안 뜬다. 판정이 보이지 않으면 밸런스 조정(저경지 평타 DPS 하락 체감)을 잴 수 없다.
연무장 허수아비가 태세로 평타를 회피하는 것(기존 대칭)의 TTK 영향 계측도 이 눈이 있어야 한다.


### B-073 · **NPC 는 집안의 「결」만 안다 — 가문의 「이름」은 소문 축과 접합되지 않았다**
- **상태**: 닫힘
- **분류**: 미완
- **단계**: P3
- **위치**: `server-bot/src/main/java/com/honcheon/bot/GameListener.java:2135`
- **의존**: B-069
- **닫는 조건**: NPC 가 **소문이 닿았을 때만** 가문 이름을 안다. 지금 프롬프트는
  *"'무가의 아이 같군' 은 되고, 가문 이름을 대는 것은 금지"* 로 **못을 박아 두었다** —
  탄생 소문(B-069)이 실제로 NPC 의 인지로 이어지면 그 빗장을 **소문 강도로** 바꿔야 한다
- **검증**: `config/npc_dialogue.yml` (house_name_by_rumor) · `server-bot/src/main/java/com/honcheon/bot/GameListener.java` (houseNews·houseLine) · 사람 눈: 세가 캐릭터로 `/혼천 대화`
- **닫힘**: 2026-07-14 · 병렬 R3 트랙 병''. NPC 가 **소문이 닿았을 때만** 가문 이름을 안다 — `houseNews()` 가 탄생 소문 군(`탄생:<id>`, 심는 쪽과 같은 열쇠)의 도달·정확도를 NPC 의 망으로 묻고, `houseLine()` 이 빗장을 조립한다. 문턱은 등록부(`config/npc_dialogue.yml` house_name_by_rumor · known_min_accuracy 50 = accuracy_bands.과장.min 정렬): 미도달=기존 금지 그대로 · 뒤틀림(정확도<50)=확신 없는 언급만(**문구에 이름 자리 자체가 없다** — LLM 이 흘릴 수 없다) · 도달=아는 체 허용(들은 것 너머 지어내기 금지). 등록부 소실 시 **닫히는 쪽으로 실패**. 하네스 4케이스 PASS · 컴파일 0 · lint 0 · disposition/house_audit 0 (Fable 재실행). 사람 눈(실서버 대화)은 기립 후 확인


### B-006 · ★ **안전 지역이 없다** — 관아 앞마당에서도 사람을 벤다
- **상태**: 닫힘
- **분류**: ★세계
- **단계**: P1
- **위치**: `config/training.yml:180`
- **의존**: —
- **닫는 조건**: 자바가 `location_safety` 를 읽는다 · 안전 지역에서 PvP 가 막힌다
- **검증**: `python3 tools/safety_audit.py` · `python3 tools/safety_audit_selftest.py`
- **닫힘**: 2026-07-14 · 병렬 R3 트랙 갑''. 자바가 드디어 `location_safety` 를 읽는다 — 새 좌표 체계 없이 존(zoneAt)과 등록 원형(world_map §16)을 재사용하고, 분류는 등록부가 쥔다 (training.yml zone_keywords·archetypes — 코드에 지명 0개). 게이트는 세 길목: `SkillListener.java:1004`(onMelee 맨 앞 — 화살 사수까지) · `:1365`(선딜 뒤 베는 순간) · `:2844`(초식 히트박스 admit — veto 기록·정원 몫 제외). 어휘: 안전=칼이 안 선다 · 보통=PvP+소문 · 위험=PvP+습격. 한쪽이라도 안전이면 막는다 (저격 진지 방지). **합의 비무는 예외** (문파 서열전이 문파 안에서 서는 설계 근거 — 단 명시 조항은 미결로 표기). 눈 신설: safety_audit 4축 + selftest 변이 7종 전부 잡음. 실측: 컴파일 0 · safety/combat/defense 전부 0 (Fable 재실행). 교차 비무 칼 구멍은 B-112 로. 인게임 실측("정말 안 베인다")은 기립 후

`location_safety` 는 **config 에만 있다** (`config/training.yml:180` — `문파_내부_관아: {level: 안전}`).
`party.yml:82` · `sect_life.yml:43` · `faction_entry_routes.yml:120` 이 산문으로 인용한다.

**자바 독자 0.** `location_safety|안전지역|safe_?zone|isSafe` 로 `server-mvt`·`core`·`server-main` 을 훑어 **한 건도 없다.**
지금 PvP 를 막는 유일한 코드는 `Sparring.java:201` — **합의**(비무)이지 장소가 아니다.


### B-107 · perf_audit 의 ✓ 가 **허위 매칭**일 수 있다 — leaf 이름만 대조한다
- **상태**: 닫힘
- **분류**: 빚
- **단계**: P4
- **위치**: `tools/perf_audit.py`
- **의존**: —
- **닫는 조건**: "읽힌다" 판정이 **어느 파일을 열어 읽는지**까지 본다 — 다른 yml 을 읽는 코드가 performance.yml 키를 보증하지 못한다
- **검증**: `python3 tools/perf_audit.py` · `python3 tools/perf_audit_selftest.py`
- **닫힘**: 2026-07-14 · 병렬 R3 트랙 을''. 눈이 파일 차원을 본다 — `perf_read_context()` 오염 흐름 분석: performance.yml 을 여는 문장을 씨앗으로 대입·패턴 변수·람다 인자를 고정점까지 전파하고, leaf 판정을 오염된 문장에만 댄다. 문자열 리터럴이 오염을 번지게 하던 초기 결함(SkillEngine 68% 오염 = 사실상 종전 눈)을 스스로 잡아 리터럴을 벗겼다 (2%로 수렴). 부수 교정: 주석이 키를 살리던 것도 종결. 재판정: 20/20 생존 — 단 skill_execution 의 ✓ 경로가 javadoc 예제(허위)에서 probes 간접 배선(정직)으로 교정. 눈의 시험 +5 (B-106 재현 변이 포함, 눈을 종전으로 되돌리면 문다). 실측: perf_audit 0 · selftest 15케이스 0 (Fable 재실행)

B-106 을 닫으며 드러났다 (2026-07-14, 트랙 병'). max_targets_default 의 ✓ 는 SkillEngine 이
**skill_mechanics.yml 을** 읽는 코드였는데 leaf 이름이 같아 performance.yml 키가 "살아 있다"고
보증됐다. 같은 거짓말이 다른 키에도 있을 수 있다 — 눈이 파일 차원을 봐야 한다.


### B-001 · lint_config 가 21건을 짖는데 **전부 거짓 양성**이다
- **상태**: 닫힘
- **분류**: 빚
- **단계**: P0
- **위치**: `tools/lint_config.py:141`
- **의존**: —
- **닫는 조건**: `lint_config` 가 **id 로** 대조한다 → 위반 0건 (또는 진짜 위반만 남는다)
- **검증**: `python3 tools/lint_config.py` · `python3 tools/lint_config_selftest.py`
- **닫힘**: 2026-07-14 · Codex 가 `lint_config.py` 를 **id 대조**로 교정했다 — `factions.yml` 의 `id_policy` (`reference_key` = id) 를 헌법대로 따르고, 하드코딩 예외 `("무당파",)` 화석도 걷혔다. 실측: `python3 tools/lint_config.py` → **오류 0건·경고 0건** (21건의 거짓 위반이 사라졌다). 눈의 시험: `lint_config_selftest.py` 3/3 (정상 id 허용 · 미등록 id 거부 · 표시명 거부 — 탐지력은 남았다). 완료 문서 `docs/collaboration/CODEX_B001_B003_COMPLETE.md` · Fable 재검증 후 커밋 a2c482a

병이었던 것: `lint_config.py:141-146` 이 `known_names` 를 display name(`화산파`)으로 쌓아 놓고
id(`hwasan`)가 없다고 짖었다. 린터가 낡았고 config 는 옳았다 — 실측 21건 전부 거짓 양성.

### B-002 · bridge_audit 이 「지역이 회복 안 한다」고 짖는데 — **회복한다**
- **상태**: 닫힘
- **분류**: 빚
- **단계**: P0
- **위치**: `tools/bridge_audit.py:142`
- **의존**: —
- **닫는 조건**: `bridge_audit` 이 `domain/` 까지 본다 → 이 축이 조용해진다 (남는 위반은 B-010 둘뿐)
- **검증**: `python3 tools/bridge_audit.py` · `python3 tools/bridge_audit_selftest.py`
- **닫힘**: 2026-07-14 · `bridge_audit.py` 가 이제 `domain/` 트리를 읽는다 (`:119` `DOMAIN_DIR`, `:131` `domain_all`) — 지역 회복은 `regions.recover(`(bot) **와** `recoveryDeltas(`(domain) 의 **두 단계 사슬**로 판정한다 (`:157`). 실측: `python3 tools/bridge_audit.py --no-backup` → 이 축 조용 (지역 회복 ✅ · 위반 0건, 남은 것은 경고뿐). 눈의 시험: `bridge_audit_selftest.py` 8/8 (끊긴 domain 주입 탐지 포함). 완료 문서 `docs/collaboration/CODEX_B001_B003_COMPLETE.md` · Fable 재검증 후 커밋 a2c482a. (B-010 의 두 위반은 B-009 처리기 구현으로 함께 사라졌다)

병이었던 것: 리팩터 `12c19e5` 가 진실을 `domain/` 으로 옮겼는데 눈은 봇 트리만 봤다. 치안은 회복하고 있었다.

### B-003 · 감사가 **짖으면서 종료 코드 0** 을 낸다 — CI 가 못 막는다
- **상태**: 닫힘
- **분류**: 빚
- **단계**: P0
- **의존**: —
- **위치**: `tools/bridge_audit.py:142`
- **닫는 조건**: 위반이 있으면 **반드시 종료 코드 1** — 모든 감사가 그렇다
- **검증**: `python3 tools/bridge_audit_selftest.py` (종료 계약 시험 포함)
- **닫힘**: 2026-07-14 · `bridge_audit` 이 실제 위반 verdict 가 있으면 **exit 1**, 경고만이면 exit 0 을 낸다 — 그 계약을 `bridge_audit_selftest.py` 가 시험한다 (위반 주입 → exit 1 · 경고만 → exit 0, 8/8). `map_lint` 는 재실측 결과 원래 정상이었다 (자체 종료 계약 + 56 self-test — 당시 exit 0 은 위반이 아니라 경고였다). 실측 2026-07-14: `bridge_audit --no-backup` 경고만 → exit 0 (계약대로). 완료 문서 `docs/collaboration/CODEX_B001_B003_COMPLETE.md` · Fable 재검증 후 커밋 a2c482a

병이었던 것: 짖으면서 exit 0 — 자동화가 그 감사를 통과시켰다. `backlog_audit` 은 종료 코드 대신
「위반 N건」을 읽는 우회를 갖고 있었는데, 이제 그 우회 없이도 종료 코드를 믿을 수 있다.

### B-004 · pack_gate_audit 이 **게이트를 실제로 여는 유일한 조건**을 못 본다
- **상태**: 닫힘
- **분류**: ★세계
- **단계**: P0
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/PackPusher.java:112`
- **의존**: —
- **닫는 조건**: `pack_gate_audit` 이 **팩 실물의 존재**를 잰다 (없으면 위반)
- **검증**: `python3 tools/pack_gate_audit.py` · `python3 tools/pack_gate_audit_selftest.py`
- **닫힘**: 2026-07-14 · `tools/pack_gate_audit.py` 에 축 ① 「실물이 있는가」가 섰다 — 등록부 `local_path` 를 코드와 **같은 기준**(서버 폴더 `run/mvt`)으로 풀어 존재·크기·sha1 을 직접 잰다. 실측: `run/pack-http/honcheon_pack.zip` · 411,859바이트 · sha1 `dfb01c0d2010…` (`sha1sum` 과 일치 — 눈과 코드가 같은 실물을 본다). 곁가지도 함께 잰다: 기본 경로 표류(`../pack-http/<file>` 미러) · 실물 부재 시 `enabled=false`+severe 존재 · 등록부 `enabled: false` (배급이 꺼지면 문도 안 선다). **눈의 시험**: selftest 에 변이 ⑫~⑯ 신설 (local_path 표류 · 실물 개명 · enabled 끄기 · 기본 경로 표류 · severe→info) — **16/16 잡음**, 되돌리기 후 재감사 위반 0건. 인수 문서: `docs/collaboration/FABLE_B004_COMPLETE.md`

병이었던 것: `pack_gate_audit.py` 에 `hash`·`sha1`·`exists`·`실물` 이 한 글자도 없었다 — 팩 zip 실물이 없으면
`PackPusher` 가 `enabled = false` 로 배급을 끄고 게이트도 같이 열리는데(`PackPusher.java:112`),
등록부의 `required: true` 만 읽던 눈은 그것을 못 봤다. **등록부는 닫혔다 말하고 세계에선 열려 있었다.**

### B-063 · 획의 **깊이(앞으로 밀기)** 미조정 — 눈높이 평면 관통
- **상태**: 닫힘
- **분류**: 미완
- **단계**: P5
- **위치**: `config/skill_motion.yml:1499`
- **의존**: —
- **닫는 조건**: 획의 원점이 등록부에서 오고, 몸 관통을 감사한다
- **검증**: `config/skill_motion.yml:1499`
- **닫힘**: 2026-07-14 · `config/skill_motion.yml:1499` 에 `stroke_origin` 블록이 섰다 (`forward: 0.85/1.00/0.00` · `limits.forward_max_ratio: 0.45`) — 코드가 읽는다 (`SkillDisplay.java:408` `forwardOf()`, `:466` `eyeOrigin()` 이 몸 관통을 감사한다). 등록부 주석이 이 병을 **과거형**으로 적는다 (`:1499`)

### B-064 · 로컬 LLM 타임아웃 **25초** — 4명이면 전원 폴백
- **상태**: 닫힘
- **분류**: 결함
- **단계**: P3
- **위치**: `config/llm.yml:44`
- **의존**: —
- **닫는 조건**: 타임아웃이 실측(1명 22.4초)을 견딘다 · 등록부에서 온다
- **검증**: `config/llm.yml:44`
- **닫힘**: 2026-07-14 · 하드코딩 `Duration.ofSeconds(25)` 가 사라졌다. `config/llm.yml:44-53` 에 `runtime:` 블록 신설 (`local_timeout_seconds: 60` · `cloud_timeout_seconds: 20`) — `LlmRenderer.java:155` 가 읽는다

### B-065 · 봇이 **줄을 안 세운다** (`sendAsync` 동시 발사) — 병렬이 독이다
- **상태**: 닫힘
- **분류**: 결함
- **단계**: P3
- **위치**: `server-bot/src/main/java/com/honcheon/bot/Scribe.java:42`
- **의존**: —
- **닫는 조건**: 서장이 한 줄로 선다
- **검증**: `server-bot/src/main/java/com/honcheon/bot/Scribe.java:42`
- **닫힘**: 2026-07-14 · `server-bot/src/main/java/com/honcheon/bot/Scribe.java:42` 에 단일 스레드 lane 이 섰다 (`newSingleThreadExecutor` — 구조적으로 동시 1건). 대기 깊이 계수기(`:49`) · `queue_max` 초과 시 폴백(`:83`). ★ **서장만 닫혔다** — `/혼천 대화` 는 아직 우회한다 (**B-016**)

### B-066 · **폴백이 사람에게 안 보인다** (로그에만)
- **상태**: 닫힘
- **분류**: 결함
- **단계**: P3
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/SeojangBook.java:213`
- **의존**: B-065
- **닫는 조건**: 폴백이 사람 눈에 보인다
- **검증**: `server-mvt/src/main/java/com/honcheon/mvt/SeojangBook.java:213`
- **닫힘**: 2026-07-14 · 세 겹으로 보인다 — 운영자(`LlmRenderer.java:207` WARN) · 명부(`Seojang.SHEET_FALLBACK`) · **사람**(`SeojangBook.java:213` 이 `seojang.yml:83` 의 `§8(붓이 더디어 옛 필사본이 왔다)` 를 찍는다). 대기까지 말한다(`ferryNotice`). ★ **서장만 닫혔다** — 대화는 아직 조용하다 (**B-017**)

### B-067 · §17 사다리의 **80 · 40 은 근거가 없다** (Q-H)
- **상태**: 닫힘
- **분류**: 결정
- **단계**: P2
- **위치**: `config/world_map.yml:3906`
- **의존**: —
- **닫는 조건**: 사용자가 띠의 숫자를 확정한다
- **검증**: `python3 tools/map_lint.py`
- **닫힘**: 2026-07-14 · **사용자가 2026-07-13 에 확정했다** — `config/world_map.yml:3906`: *"★★ 띠의 숫자(80·64·48·40)는 확정됐다 — 2026-07-13 사용자 결정: '그대로 둔다'. (옛 Q-H 해소)"*. 유도식도 적혀 있다 (`:3834` — `build_radius ≈ round8(6.4 × 영향력)`, 앵커는 실측된 화산 64)

> ★ **씨앗 정정**: "80·40 은 근거 없음" 은 **과했다.** 64 만 실측인 것은 맞으나,
> 80/48/40 에는 **유도식이 있고 사용자 승인이 있다.** 열린 것은 이것이 아니라 **B-054(전성기 영향력)** 다.

---

## 서장·생성 (2026-07-14 · 서장 담당)

> 서장을 디스코드 스레드에서 **강호의 책(Written Book)** 으로 옮기며 생긴 청구서다.
> 닫힌 것은 여기 적지 않았다 (옮긴 것 자체는 끝났다 — `config/seojang.yml` · `tools/ScribeSelfTest.java`).

### B-068 · **NPC 대화가 아직 디스코드에 있다** — 서장만 옮겼고 대화는 옛 길 그대로다
- **상태**: 열림
- **분류**: 미완
- **단계**: P1
- **위치**: `server-bot/src/main/java/com/honcheon/bot/GameListener.java:181`
- **의존**: —
- **닫는 조건**: `/혼천 대화` 의 자유 입력이 **게임 안**으로 옮겨진다 (NPC 앞에 서면 이야기가 열린다).
  ★ **두 벌이면 하나가 낡는다** — 옮기면 디스코드의 옛 길을 **지워야** 한다 (서장에서 그렇게 했다)
- **검증**: `grep -c '"대화"' server-bot/.../GameListener.java` 가 0, 그리고 게임 안에서 대화가 끝까지 진행됨
- **닫힘**: —

### B-069 · **세가 아이의 탄생 소문 범위가 담당자의 제안값이다** (승인 대기)
- **상태**: 닫힘
- **분류**: 결정
- **단계**: P2
- **위치**: `config/player_creation.yml` → `age_and_lifepath.birth_rumor.house_intensity`
- **의존**: —
- **닫는 조건**: 사용자가 세가 아이의 소문 강도를 확정한다.
  보통의 집은 **강도 2 (현 내 = 해당 마을)** 로 **확정됐다** (2026-07-14 사용자: *"세가를 제외하곤 지역까지만"*).
  세가는 **3 (현 전체 + 인접 현)** 을 제안했다 — 근거는 `families.무가의_자식.scale` 의 *"현·부 단위 향반 무가"* 한 줄뿐이다.
  후보는 `rumor.yml propagation.reach_by_intensity` 의 3 · 4 · 5 뿐이다 (**지어낼 것이 없다**)
- **검증**: `python3 tools/disposition_audit.py` (⑦절 — 강도가 사다리의 실재하는 칸인지, 적자>서자>보통인지 짖는다)
- **닫힘**: 2026-07-14 · **사용자가 확정했고, 새 축이 하나 생겼다.** 원문: *"세가는 좀 더 크게. 「어느 세가의 둘째아들이 태어났다」는 다른 세가나 문파에서도 알 만한 대사건입니다. (대신 적자냐 외자식/서자냐는 **주사위로 결정**되고, **적자인 경우에만 5**, 외자식/서자인 경우는 **3**으로)"* → **적서(嫡庶)를 신설**했다 (`player_creation.yml` `birth_rank`). 담당자의 제안값 3 은 **폐기**됐다 — 이제 적서가 무게를 가른다: 적자 **5**(천하) · 서자 **3**(현+인접 현) · 보통의 집 **2**(현 내). 강도 5·3 은 **사용자가 직접 말한 수**다. 실측: 주사위 1만 회 → 적자 49.2% / 서자 50.8% (등록부 50:50). 눈 시험: 적자·서자를 같은 강도로 만들면 ☠ *"적서가 뒤집혔다"*

### B-070 · **`family_affinity` 의 「은밀형 → 몰락_무가」 한 줄만 근거가 없다**
- **상태**: 닫힘
- **분류**: 결정
- **단계**: P2
- **위치**: `config/player_creation.yml` → `age_and_lifepath.family_affinity.은밀형`
- **의존**: —
- **닫는 조건**: 사용자가 그 짝을 승인하거나 고친다. 나머지 여섯 성향은 등록부에서 **읽어낸** 것이고
  (`disposition_presets.recommended_identities` · `families.grants` · `world_link`), 이 한 줄만 **담당자의 판단**이다.
  대안 후보: `농가의_자식`(아무도 안 보는 아이) · `표국집_자식`(길 위의 눈)
- **검증**: `python3 tools/disposition_audit.py` (④ 절이 대응표를 통째로 찍는다)
- **닫힘**: 2026-07-14 · **사용자가 근거를 주었다** — 짝을 바꾸지 않고 **그대로 둔다**. 원문: *"**몰락 무가에서 복수를 위해 이를 갈고 은밀해졌다** 이런 느낌"*. 즉 **은밀함은 타고난 것이 아니라 만들어진 것**이다 — 집이 무너진 아이가 소리 없이 움직이는 법을 **배웠다**. 화살표(성향 → 집안)와 정확히 맞는다: *"은밀한 아이의 유년은 몰락한 무가에서 자랐다"*. 등록부의 `why` 를 **【판단 · 근거 없음】 → 【사용자 확정 · 서사】** 로 고치고 그 문장을 근거로 박았다 (`player_creation.yml` `family_affinity.은밀형`)

### B-071 · **가출한 무가의 자식에게 전용 발단이 없다** — 재난형 풀을 빌려 쓰고 있다
- **상태**: 닫힘
- **분류**: 미완
- **단계**: P3
- **위치**: `config/player_creation.yml` → `age_and_lifepath.families.가출한_무가의_자식.incident_pool`
- **의존**: —
- **닫는 조건**: `출분(出奔)` 같은 전용 발단이 `inciting_incidents` 에 선다 (**제 발로 나온 것**이 곧 발단이다).
  지금은 `[목격, 아이_거두기, 핏줄의_비밀]` 을 빌려 쓴다 — "집을 나온 뒤 그 일이 닥쳤다" 로 읽히지만
  **재난이 아니라 선택이 발단인 집**에 재난 풀은 결이 어긋난다 (무가의 `수행_파견` 이 그 선례다)
- **검증**: `python3 tools/disposition_audit.py` (⑨절 — 발단의 **결(kind)** 이 집안과 맞는지 짖는다)
- **닫힘**: 2026-07-14 · **사용자 지시("추천으로 진행")로 출분(出奔) 발단 3종을 신설**했다: `담을_넘다` · `아버지의_검` · `파혼` (`player_creation.yml` `inciting_incidents`). 문법은 기존 그대로다 (`family_only` · `cause` · `movement` · `trace` · `long_hook` · `goal_candidates` — 새 문법을 발명하지 않았다). **능력치·기술을 주지 않는다** (헌법). 서장의 첫 문장도 갈렸다 (`seojang.yml` `prose.incident_opening`): 재난형은 *"그날 밤, 모든 것이 달라졌다"*(휩쓸린다) / 출분형은 *"새벽에 담을 넘었다 — 아무도 깨우지 않고"*(저지른다). ★ 그리고 **발단에 `kind`(재난·명령·출분)를 신설**했다 — 눈이 *"택한 아이에게 당한 자의 발단"* 을 잡지 못했기 때문이다 (`family_only` 만으로는 못 잡는다: 재난형은 전용이 아니라 **공용**이므로)

### B-072 · **형제 관계를 세우기만 했고 쓰는 곳이 없다**
- **상태**: 열림
- **분류**: 미완
- **단계**: P2
- **위치**: `server-bot/src/main/java/com/honcheon/bot/GameListener.java:1286`
- **의존**: —
- **닫는 조건**: 형/누나/아우/누이가 **세계에서 실제로 작동한다** — 형이 죽으면 아우가 안다 ·
  NPC 가 *"자네 형이…"* 라고 말한다 · 혈채/유산이 형제에게 흐른다 (`death_and_legacy.yml`).
  지금은 서열을 **세우고 마크에 내려보내기만** 한다 (`mvtSheet.kin`)
- **검증**: 같은 집안 캐릭터 둘을 만들고 `run/bridge/world_state.json` 의 `sheet.<uuid>.kin` 확인
- **닫힘**: —

### B-075 · **동점 성향(「아직 정해지지 않은 아이」)이 행동으로 굳지 않는다**
- **상태**: 열림
- **분류**: 미완
- **단계**: P3
- **위치**: `config/disposition_test.yml` → `scoring.undecided.resolve`
- **의존**: —
- **닫는 조건**: 강호에서 **무엇을 반복하는가**가 성향을 확정한다. 전수 실측으로 **8.25%** 가 3방 이상
  동점이고 (262,144가지 중 21,620), 그들은 지금 **영영 여러 성향을 나란히 지고 산다**
- **검증**: `python3 tools/disposition_audit.py` (② 절이 동점 비율을 찍는다)
- **닫힘**: —


### B-076 · **적서(嫡庶)가 소문 말고는 아무것도 가르지 않는다**
- **상태**: 열림
- **분류**: 미완
- **단계**: P2
- **위치**: `config/player_creation.yml` → `age_and_lifepath.birth_rank`
- **의존**: —
- **닫는 조건**: 적자/서자가 **상속 · 대우 · NPC 반응 · 가출 동기**에서 갈린다.
  지금은 **소문의 무게(5 vs 3)와 서장의 첫 문장**만 가른다 — 시트(`sheet_json.적서`)와 마크 시트(`birth_rank`)에는
  **남아 있으므로** 잇기만 하면 된다. ★ **능력치는 주지 않는다** (헌법 — `grants_attributes: false`, 눈이 지킨다).
  ★ 서자의 설움이 **가출(refuse_house)의 동기**로 이어지면 축이 하나로 닫힌다
- **검증**: `python3 tools/disposition_audit.py` (⑦절)
- **닫힘**: —

### B-077 · ★★ **집안은 「유형」이지 「한 채의 집」이 아니다** — 농가의 아이 둘이 남매가 된다
- **상태**: 닫힘
- **분류**: ★세계
- **단계**: P1
- **위치**: `server-bot/src/main/java/com/honcheon/bot/GameListener.java:1311`
- **의존**: —
- **닫는 조건**: **가문의 실체(house instance — 가문 id·이름)** 가 서고, 남매는 **같은 집**끼리만 잡힌다.
  ★ **담당자가 형제 축을 짜다가 발견한 구멍이다.** 사용자의 말은 *"**같은 세가에** 같이 태어나게 되었다면"* —
  **같은 집**이다. 그런데 세계에 **집의 실체가 없다.** 지금 코드는 **같은 집안 키를 가진 산 자를 전부 남매로 묶는다**:
  · 농가의 아이 둘 = 남매? → **아니다.** 서로 다른 농가다
  · 객잔집 아이 둘 = 남매? → **아니다.** 서로 다른 객잔이다
  · 몰락무가 아이 둘 = 남매? → **아니다.** 서로 다른 무너진 집이다 (`start_region_examples: [각지]`)
  → **무가↔가출(사용자가 확정한 짝)에서는 옳고, 나머지에서는 틀리다.** 그래서 지금은 유형으로 묶어 두었다
- **검증**: `tools/HouseKinSelfTest.java` — **실제 DB 로** 잰다 (config 검사로는 증명 못 한다)
- **진행 (2026-07-14 · 서장 담당)**: 사용자가 **(가) 가문을 실체로 세운다** 로 확정했다. 이번 바퀴에 한 것:
  ① ★ **거짓 형제를 껐다** — 같은 유형을 남매로 묶던 코드를 **가문 실체(`house_id`) 기준**으로 갈았다.
     가문이 서기 전까지 `kin` 은 **비어 있다** (`house_system.enabled: false`). **거짓 형제보다 없는 형제가 낫다.**
     ★ 다행히 아직 아무도 형제를 쓰지 않는다 (B-072) — 그래서 끄는 것이 안전했다.
  ② **그릇을 만들었다** — `db/migrations/008_가문.sql` (`houses` 표 + `characters.house_id`).
     ★ **아직 안 돌렸다.** 규약대로 **사람이** 백업 확인 후 `python3 tools/migrate_db.py` 로 돌린다.
     더하기만 한다 (지우거나 고치는 것 없음) · 다섯 답 **어느 쪽이 와도 담기게** 전부 NULL 허용.
  ③ **눈을 세웠다** — `HouseKinSelfTest`: **다른 집의 두 아이가 남매가 아님**을 실제 DB 로 확인.
     일부러 어겨서(최가의 아이를 이가 호적에 끼워 넣고) 눈이 보는지도 확인했다.
  ★ **안 한 것**: **사람을 집에 앉히지 않았다.** **누가 어느 집에 태어나는가**(배정 규칙)가
  **세계관 결정**이고, 그것을 지어내면 **형제의 존재 조건 자체를 담당자가 발명하는 것**이다 → **B-079**
- **닫는 조건**: 배정 규칙이 서고, 사람이 실제로 가문에 태어나고, `house_system.enabled: true` 로 켜진다
- **닫힘**: 2026-07-14 · **돌려서 확인했다**: `python3 tools/house_audit.py` (exit 0) + `tools/HouseKinSelfTest.java` (exit 0 — 다른 집의 두 아이가 남매로 **안 잡힌다**) + 파일 `db/migrations/008_가문.sql` (적용됨 · 스키마 8). **가문이 섰다.** 사용자가 다섯 물음에 답했고(B-079) 전부 배선했다:
  `houses` 표(마이그레이션 008 적용됨 — 스키마 8) · `characters.house_id` · **배정 주사위**(`assignHouse`) ·
  **성씨**(무가 계열만) · **형태**(흥·쇠·멸, 탄생 고정) · **지역**(설 수 있는 고을에서만).
  ★ 형제는 이제 **같은 `house_id`** 로만 잡힌다. **실측 증거**: `tools/HouseKinSelfTest` —
  같은 유형(무가의_자식)의 **다른 집**(이가·최가) 아이가 **남매로 안 잡힌다**.
  일부러 어겨서(최가 아이를 이가 호적에 끼워 넣고) 눈이 보는지도 확인했다.
  ★ 그 시험이 **진짜 버그 하나를 잡았다**: `playableRegions()` 가 `RulesConfig.section()`(Map 캐스팅)에
  **목록**을 먹여 **탄생 때마다 ClassCastException** 이 날 뻔했다 — 빌드는 통과했었다 (**빌드 통과 ≠ 기동 성공**).
  ★ **증거 (돌려서 확인했다)**: `python3 tools/house_audit.py` → exit 0 ·
  `tools/HouseKinSelfTest.java` → exit 0 (다른 집의 두 아이가 남매로 안 잡힘) ·
  파일 `db/migrations/008_가문.sql` (적용됨 — 스키마 8) · `config/player_creation.yml` `house_system`

### B-079 · ★★ **가문의 배정 규칙이 없다** — 이것이 **형제의 존재 조건**이다
- **상태**: 닫힘
- **분류**: 결정
- **단계**: P1
- **위치**: `config/player_creation.yml` → `age_and_lifepath.house_system.open_questions`
- **의존**: B-077
- **닫는 조건**: 사용자가 **다섯 물음**에 답한다. ★ **③은 이미 답이 나왔다** (등록부가 답했다):
  *"오대세가급 직계 시작은 **계속 배제**"* (`families.무가의_자식.scale`) — 플레이어의 가문은 **향반 무가**뿐이다.
  남은 넷:
  ① **기존 가문에 들어가는가, 새 가문을 만드는가** — ★ **이것 없이는 형제가 영원히 안 생긴다.**
     후보: (가)기존 (나)신설 (다)주사위 (라)**자식 수 상한**이 차면 새 집 ← 담당자가 끌리는 안
  ② **성씨** — ★ 담당자가 찾은 사실: 등록 NPC **32명 전원 성이 없다** (한백·묵삼·곽진 — 두 자 이름).
     그런데 **세가는 성으로 불린다** (남궁·팽·당·제갈·모용). → **성씨는 이 세계에서 「가문이 있다」는 표시 그 자체다.**
     ★ 그러나 **쓸 수 있는 성의 목록이 등록부에 없다** (검색 0건). 오대세가의 성은 **쓰면 안 된다**
  ④ **어디에 있는가** — ★ **완비된 지역은 청하현 하나뿐** (사천·강남·하북은 스텁: "블록도 앵커도 없다").
     **스텁에 가문을 두면 갈 수 없는 집이 된다.** 그리고 청하현에 **무가 NPC 가 0명**이다
  ⑤ **흥망을 열 것인가** — ★ **열면 서장이 거짓말이 된다**: 미리 쓴 서장이 '건재한 가문'을 말했는데
     그 사이 집이 망하면? → 그때는 **미리 쓰기 지문에 가문 상태를 넣어야 한다** (`seojang.yml prerender.fingerprint`).
     **이번에 안 연 이유가 이것이다**
- **검증**: `python3 tools/house_audit.py` (성씨·지역·형태·**형제 실측**)
- **닫힘**: 2026-07-14 · **돌려서 확인했다**: `python3 tools/house_audit.py` (exit 0 — 성씨·지역·형태·형제 실측). **사용자가 다섯에 다 답했다.**
  ① **(다) 주사위** — `assignment` (기존 집 60% · 자식 수 상한 4). ★ **자식 수 상한을 주사위의 가중치로 녹였다**:
     빈자리 0 인 집은 후보에서 빠지고, 후보가 없으면 **새 집이 선다** — 상한 하나가 대가족을 막고 새 집을 낳는다.
     ★ **수는 제안값이다** (근거 없음). `house_audit` 가 **형제율을 실측한 표**를 찍는다 — 보고 고르면 된다.
  ② **성씨 목록 신설** (20개) — 오대세가의 성(남궁·팽·당·제갈·모용) **금지**, 눈이 지킨다.
     ★ 근거: 등록 NPC **32명 전원 성이 없고** 세가만 성으로 불린다 → **성 = 「가문이 있다」는 표시 그 자체.**
     그래서 성은 **무가 계열에만** 붙는다 (농가의 집은 「청하현 농가의 자식」이지 「이씨 농가」가 아니다).
  ③ **오대세가 배제** — 등록부가 이미 답했다 (`families.무가의_자식.scale`).
  ④ **형태는 탄생에 고정** — 미리 쓰기가 안전해졌다 (변하지 않으므로 지문에 넣을 필요가 없다).
     ★ **몰락무가와 겹치는 문제는 파생으로 풀었다**: 집안이 이미 상태를 말하므로(몰락무가 = 멸) **굴리지 않고 받는다.**
  ⑤ **지역 = 가문이 사는 고을. 시작 위치도 거기다** (`mvt_start.by_region` — 지역 × 집안).
     ★ **설 수 있는 고을(`playable`)에서만** 뽑는다. 지금 청하현 하나 — 강남 상로가 서면 **등록부 한 줄**만 더한다.
  ★ **증거 (돌려서 확인했다)**: `python3 tools/house_audit.py` → exit 0 (성씨·지역·형태·형제 실측 전부 통과).
  **일부러 어겨서 시험했다** — 오대세가의 성을 훔치면 ☠ · 안 선 고을에 세우면 ☠ · 몰락무가를 '흥' 으로 하면 ☠ ·
  기존 집 확률 2% 로 낮추면 ☠(형제가 3.7% — (다)를 고른 뜻이 죽는다) · 평민 집에 성을 붙이면 ☠ · 상한 1 이면 ☠

### B-078 · **몰락무가의 아이에게 적서를 적을 것인가**
- **상태**: 보류
- **분류**: 결정
- **단계**: P3
- **위치**: `config/player_creation.yml` → `age_and_lifepath.birth_rank.houses`
- **의존**: B-076
- **닫는 조건**: 사용자가 정한다. 지금 `houses` 는 **`무가의_자식` 하나**다 —
  근거: 집이 **이미 무너진** 집에 아이가 나는 것은 **대사건이 아니다** (소문의 관점).
  그러나 **상속·복수의 명분**에서는 적자/서자가 의미가 있다 (`몰락_무가의_자식` 도 `lineage: 무가` 다).
  ★ 소문은 안 내되 **시트에는 적는** 절충도 가능하다
- **검증**: `python3 tools/disposition_audit.py` (⑦절 — 무가 계열이 아닌 집에 적서를 매기면 짖는다)
- **닫힘**: —


---

## 안내판·디스코드 사용성 (2026-07-14 · 안내판 담당)

> 사용자의 말: *"디스코드에서 사용성 편리함 강화. **디스코드 명령을 치는 게 이상하다 생각함**."*
> 그리고 이 프로젝트의 축: *"디스코드를 **인증 서비스와 소셜**로 두고, 다른 로직을 백엔드로 처리."*
>
> **명령을 외워 치는 것은 로직의 문법이지 소셜의 문법이 아니다.** 이번 바퀴에 **안내판**(`/안내판`)을
> 세웠다 — `/접합문` 과 같은 문법이다 (관리자가 한 번 치면 상시 버튼). 아래는 그 과정에서 **남은 것**이다.

### B-080 · ★ **몸의 일 10종이 아직 디스코드에 있다** — 옮길 곳은 마크다
- **상태**: 열림
- **분류**: 미완
- **단계**: P2
- **위치**: `server-bot/src/main/java/com/honcheon/bot/GameListener.java:176`
- **의존**: —
- **닫는 조건**: 사냥·비무·수련·사사·의뢰·탐방·운기·출행·의방·구조가 **게임 안**으로 옮겨진다.
  (`대화` 는 **B-068** 이 따로 청구했다 — 몸의 일은 합쳐서 **11종**.)
  ★ **두 벌이면 하나가 낡는다** — 옮기면 디스코드의 옛 길을 **지워야** 한다 (서장에서 그렇게 했다:
  `case "tn"` 버튼을 남기지 않았다).
  ★★ **`소문`·`전장` 은 여기 없다** — 그 둘은 이관이 아니라 **형태의 소멸**이다 (**B-083**).
  결을 섞으면 판이 거짓말한다 — 그래서 등록부가 결을 가르고(`panel.me.legacy_kinds`) 눈이 대조한다.
  ★ 25칸(B-020)이 실제로 주는 것은 **이 11종 + B-083 의 2종을 지울 때**다 (25 → 12)
- **검증**: `python3 tools/panel_audit.py` (⑥절이 **아직 남은 옛 길의 수**를 찍는다 — 0 이 되면 닫힌다.
  ★ 그리고 **판이 거짓말하는지도 잰다**: 옮겨서 지운 명령을 계속 안내하거나, 남아 있는 뒷문을 숨기거나,
  **소문·전장을 «옮겨진다» 문단에 도로 넣으면** 짖는다)
- **닫힘**: —

### B-083 · ★★ **소문 · 전장 — 「명령」이라는 형태가 없어진다** (이관이 아니다)
- **상태**: 열림
- **분류**: ★세계
- **단계**: P2
- **위치**: `server-bot/src/main/java/com/honcheon/bot/GameListener.java:185`
- **의존**: B-068
- **닫는 조건**: **명령이 죽고, 그 자리를 NPC 가 대신한다.** 셋이 다 서야 닫힌다:
  ① **소문**을 NPC 가 **제 입으로 말한다** (묻지 않아도 저잣거리에서 흘러나온다)
  ② **전장**의 예치·인출·상속인 지정을 **금서방 앞에 서서** 말로 한다
  ③ 그러고 나서 `/혼천 소문`·`/혼천 전장` 을 **지운다** (★ 그 전에는 **절대 지우지 마라** — 순서가 뒤집히면
     기능이 통째로 사라진다. 그래서 지금은 **뒷문으로 남겨 두고**, 안내판이 *"이제 사람에게 물어야 한다"* 고 말한다)
- **검증**: `python3 tools/panel_audit.py` (⑥절 — 결이 섞이면 짖는다. 명령이 지워지면 옛 길 수가 준다)
- **닫힘**: —

> **★ 사용자의 말 (2026-07-14, 원문):**
> *"소문과 전장은 이제 **백엔드로 가서 내부적으로 처리**가 되고, **NPC를 통해 표현**이 되어야 합니다"*
>
> **이것은 「마크로 옮긴다」가 아니다.** 두 명령은 마크의 명령이 되지 않는다 — **명령이라는 형태 자체가
> 없어진다.** 나는 이 둘을 *"읽기 전용 장부라 소셜에 가깝다"* 고 읽었는데, **그것도 틀렸다.**
> 문제는 **어느 쪽 화면인가**가 아니라 **조회라는 행위 자체**였다.
>
> ```
> 백엔드   : 소문망 · 전장(예치·인출·상속인)  — 계속 돈다. ★ 죽이지 마라
> 앞모습   : NPC 의 입과 손 — 그것을 통해서만 만난다
> 사라지는 것 : 「명령」이라는 형태
> ```
>
> · **소문은 메뉴가 아니다.** 명령을 쳐서 목록을 여는 것이 아니라 **저잣거리에서 누가 말해 주는 것**이다.
> · **전장도 같다.** 돈은 **창구에서 사람에게** 맡긴다.
>
> **★ 이미 서 있는 것 (새로 짓지 마라):**
> · 소문망은 **이미 돈다** — `config/rumor.yml` · `server-bot/.../Rumors.java`
>   (`propagation.reach_by_intensity`). 탄생 소문까지 이미 이 망을 탄다 (B-069)
> · NPC 의 입도 **이미 있다** — `config/npc_dialogue.yml` · `LlmRenderer` 의 페르소나.
>   그리고 `GameListener.npcClue()` 가 **NPC 에게 소문을 물려 주는 손**이다 (`Rumors.Heard`) —
>   **소문을 그 입에 실을 자리가 이미 있다.** 없는 것은 「묻지 않아도 말한다」뿐이다
> · **그러므로 이것은 B-068(대화를 마크로)과 한 몸이다.** 대화가 게임 안으로 들어가는 그 손이
>   소문을 실어 나른다. **따로 짓지 마라** — 두 벌이 되면 하나가 낡는다
>
> **★ 전장은 소문보다 무겁다** (정직하게): 예치·인출은 **돈이 오가는 일**이고, 상속인 지정은
> **죽음의 축**에 걸려 있다 (`death_and_legacy.yml`). NPC 앞의 대화로 옮기려면 **자유 입력이 금액을
> 결정하게 되는데** — LLM 이 숫자를 정하게 두면 안 된다 (헌법). 창구는 **버튼/고정 문답**이어야 한다.
> 이 설계는 아직 **없다.** 짓는 사람이 여기 적어라.

### B-081 · **안내판을 디스코드에서 눈으로 못 봤다** (봇 재기동 전이다)
- **상태**: 미확인
- **분류**: 미완
- **단계**: P1
- **위치**: `server-bot/src/main/java/com/honcheon/bot/GameListener.java:5238`
- **의존**: —
- **닫는 조건**: 사용자가 봇을 다시 띄우고 `/안내판` 을 친 뒤, **눈으로** 넷을 확인한다:
  ① ★★ **처음 온 사람이 판에서 [강호에 들다] 를 «한 번» 눌러 캐릭터를 만드는가**
     (사용자: *"캐릭터가 없을 시 **버튼으로 시작 버튼**을 만들기 (**명령어 치는 거 제거**)"* —
     옛 판은 [내 자리] 를 거쳐 **두 번**이었다. 이제 판에 버튼이 **둘**이다)
  ② **이미 태어난 사람이 [강호에 들다] 를 눌렀을 때 «그대는 이미 태어났다» 고 말하는가**
     (판은 공용 메시지라 그 버튼을 **가릴 수가 없다** — 그러므로 **침묵하면 위반**이다)
  ③ **[내 자리]** 에서 못 누르는 것이 **왜 못 누르는지 말하는가**
  ④ 마크가 꺼져 있을 때 **[몸을 잇는다] 가 사라지고 그 이유를 말하는가**
  ★ **소스는 잰다** (`tools/panel_audit.py` — ①-b 절이 **누르는 횟수를 실제로 센다** · 자기 시험 18/18).
  그러나 **「빌드 통과 ≠ 기동 성공」** 이다 — 이 저장소는 서식 문자 한 줄로 플러그인 전체가 안 켜진 날이
  있었다. **명령 등록은 재기동해야 뜬다.**
- **검증**: `python3 tools/panel_audit.py` (소스) + **인게임/디스코드에서 눈으로** (기계가 못 재는 몫)
- **닫힘**: —

### B-082 · **관리자의 손은 아직 명령이다** (버튼이 아니다)
- **상태**: 보류
- **분류**: 결정
- **단계**: P3
- **위치**: `config/discord_panel.yml` → `panel.admin_note`
- **의존**: B-081
- **닫는 조건**: 사용자가 정한다 — 관리자 명령(`지역등록`·`정산`·`사망`·`사선`·`명분`·`사정`)도
  버튼으로 옮길 것인가. **지금은 안 옮겼다.** 근거: 관리자의 손은 **한 번 치고 마는 것**이고
  (`/안내판`·`/접합문`·`/혼천 지역등록`), 검증용 명령은 **인자가 많다**(`/혼천 사망` 은 옵션 5개 —
  버튼으로 옮기면 모달 5칸이 된다). 사용자의 요구는 *"**처음 온 사람**이 무엇을 눌러야 하는지"* 였다 —
  관리자는 처음 온 사람이 아니다. 안내판은 관리자에게 **그 명령들의 목록만** 보여 준다
- **검증**: ★ 사람이 정한다 (기계가 못 잰다). 지금의 목록: `config/discord_panel.yml` → `panel.admin_note`
- **닫힘**: —


### B-088 · **가문의 수(배정 60% · 자식 상한 4)가 담당자의 제안값이다**
- **상태**: 닫힘
- **분류**: 결정
- **단계**: P2
- **위치**: `config/player_creation.yml` → `age_and_lifepath.house_system.assignment`
- **의존**: —
- **닫는 조건**: 사용자가 `join_existing` 과 `children_cap` 을 확정한다
- **검증**: `python3 tools/house_audit.py` (④절 — **이 코드의 배정 로직 그대로** 실측하고, 문턱을 **등록부에서** 읽는다)
- **닫힘**: 2026-07-14 · **돌려서 확인했다**: `python3 tools/house_audit.py` (exit 0) + `tools/HouseKinSelfTest.java` (exit 0).
  **사용자 확정: `join_existing` 60 → 10** (원문: *"확률을 좀 더 줄였으면 좋겠어. **간혹가다 한 명 두 명씩 있는 거지**,
  너무 다들 형제가 있는 것처럼 느껴져서 별로야"*). `children_cap` 은 **4 그대로** —
  ★ 상한을 줄이면 **새 집이 더 자주 서서 형제가 오히려 더 준다** (같은 방향의 손잡이가 둘이면 조절이 불가능해진다).
  **손잡이는 확률 하나로 족하다.**

  **★ 내 코드의 배정 로직으로 다시 실측한 표** (형제가 있는 사람의 %):

  | 사람 수 | 60%(옛값) | 30% | 20% | **10%(정본)** | 5% |
  |---|---|---|---|---|---|
  | 5 | 28.3 | 14.6 | 9.7 | **5.0** | 2.6 |
  | 10 | 48.2 | 25.7 | 17.2 | **8.9** | 4.5 |
  | **20** | 66.0 | 37.0 | 25.2 | **13.0** | 6.5 |
  | 50 | 78.9 | 46.2 | 32.2 | **16.8** | 8.5 |

  → **20명이면 약 2.6명** — 정확히 *"간혹가다 한 명 두 명"*. 조율자의 독립 측정(12.3%)과 일치한다.

  ★★ **눈의 문턱을 코드에서 등록부로 뺐다** (`assignment.sibling_check`). 전에는 `MIN_SIBLING_PCT = 20.0` 이
  **박혀 있었다** — 그러면 **눈이 새 정본(13.1%)을 위반이라고 짖었을 것이다.** 코드가 취향을 쥐고 있었다.
  **하한 5%** 의 근거: 20명 × 5% = **딱 한 사람** — 이보다 낮으면 20명이 모여도 형제가 **한 명도** 안 생기고,
  그러면 (다) 주사위를 고른 뜻이 죽는다. **취향이 아니라 「형제가 존재하기는 하는가」의 최소선이다.**
  **상한 40%** 는 담당자의 제안(되돌아가는 것을 막는 못) — 사용자가 거부한 60% 는 20명에서 66.0% 였다.
  **일부러 어겨서 시험했다**: 1% ☠(1.4% — 한 명도 안 생긴다) · 60% ☠(66.0% — 다들 형제) ·
  `from` 삭제 ☠(제 형과 남남) · `kin_group` 부활 ☠(두 벌)

### B-089 · **가문의 흥망이 플레이 중에 변하지 않는다** (집이 망하거나 일어서지 않는다)
- **상태**: 열림
- **분류**: 미완
- **단계**: P3
- **위치**: `config/player_creation.yml` → `age_and_lifepath.house_system.state`
- **의존**: B-077
- **닫는 조건**: 가문이 **플레이 중에** 흥하고 망한다 (세력·정치·소문과 닿는다).
  지금은 **탄생에 고정**이다 (사용자 확정 — 그리고 그것이 미리 쓰기를 안전하게 만든다).
  ★★ **여는 사람에게 경고**: 상태가 변하면 **미리 쓴 서장이 거짓말이 된다** (건재한 가문이라고 썼는데 망하면).
  그때는 **반드시 `seojang.yml prerender.fingerprint` 에 가문 상태를 넣어라.** 등록부에도 그 경고를 박아 두었다
- **검증**: `python3 tools/house_audit.py` (③절)
- **닫힘**: —

### B-090 · **무가의 시작 자리가 「전장」인데 집이 같은 고을에 있다** — 걸어가면 되는데 전표를 받는다
- **상태**: 보류
- **분류**: 결정
- **단계**: P3
- **위치**: `config/player_creation.yml` → `age_and_lifepath.families.무가의_자식.start_region_examples`
- **의존**: B-077
- **닫는 조건**: 사용자가 정한다. **무가의 시작 자리는 전장(錢莊)** 이고 그 근거는 발단 `수행_파견` 이다:
  *"가문의 연고가 닿는 **전장에 월례 전표가 와 있다**"* — **집이 돈을 부쳐 준다 = 집이 여기 없다.**
  그런데 지금 배정은 **집을 그 고을에 둔다** (설 수 있는 고을에서만 뽑으므로).
  → **집이 같은 고을에 있는데 전표를 부쳐 받는 것**이 어색하다 (걸어가면 되는데).
  ★ 후보: (가) 무가는 **언제나 다른 고을**에 집을 둔다 (그러면 `수행_파견` 과 완벽히 맞는다 —
  다만 고을이 **둘 이상** 서야 가능하다. 지금은 청하현 하나뿐) · (나) 시작 자리를 바꾼다 ·
  (다) 그냥 둔다 (가문의 저택이 고을 안에 있고 전장은 가문의 돈줄일 뿐이라고 읽는다)
  ★ **강남 상로가 서면 (가)가 가능해진다** — 그때 다시 보라
- **검증**: `python3 tools/house_audit.py`
- **닫힘**: —


### B-097 · **아우가 났다는 소식이 게임 안으로는 가지 않는다** (디스코드 DM 뿐)
- **상태**: 보류
- **분류**: 결정
- **단계**: P3
- **위치**: `server-bot/src/main/java/com/honcheon/bot/GameListener.java:1161`
- **의존**: —
- **닫는 조건**: 사용자가 정한다. 지금 소식은 **디스코드 DM** 으로만 간다
  (`birth_rumor.sibling_news.channel: 디스코드_DM`).
  ★ **근거**: 사용자의 축 — *"디스코드를 **인증 서비스와 소셜**로."* 아우가 났다는 것은 **소식(소셜)**이지
  **이야기의 진행**이 아니다 (고르고 판정하는 것이 아니라 **그냥 알게 되는 것**이다).
  그리고 형은 **접속해 있지 않을 수 있다** — DM 은 언제 봐도 거기 있지만 게임 안 채팅은 그 순간 없으면 사라진다.
  ★★ **그러나 사용자의 큰 뜻은 「마크로 넘어가는 것」이다.** 게임 안에서도 알려 줄 것인가?
  → 지금 다리에는 **「그 몸에게 한 마디 건네는」 일반 통로가 없다** (서장의 `ferry` 는 서장 전용이다).
  그것을 여는 것은 **새 다리 표면**이라 이번에 짓지 않았다 (반쯤 짓지 않는다).
  ★ 후보: (가) DM 만 (지금) · (나) 접속해 있으면 게임 안에도 · (다) 일반 알림 통로를 다리에 신설
- **검증**: `tools/SiblingTimeSelfTest.java` (⑤절 — 아우가 났는데 형이 모르면 짖는다)
- **닫힘**: —


---

# 씨앗 중 **사실이 아니었던 것** (코드로 확인함 — 2026-07-14)

닫힌 것이 아니라 **애초에 참이 아니었던 것**이다. 다시 청구되지 않도록 적어 둔다.

| 씨앗의 주장 | 실제 | 근거 |
|---|---|---|
| 레이캐스트 **상한 없음** | **거짓** — 캡이 있다 (`max_targets`, 기본 8) | `SkillListener.java:2368` (→ 좁은 진실은 **B-023**) |
| `RegionStateEngine.recoveryDeltas` **호출자 0** | **거짓** — 봇이 `recover()` 로 부른다. **감사가 틀렸다** | `GameListener.java:4365` → `RegionService.java:96` (→ **B-002**) |
| party.yml 을 읽는 **자바가 없다** | **거짓** — `PartyEngine` 이 읽는다 (그러나 **명부는 없다**) | `core/.../PartyEngine.java` (→ **B-031**) |
| `faction_entry_routes.yml` 에 **자바 평가기가 없다** (GM 문서일 뿐) | **거짓** — 읽고 평가한다 (`/혼천 출행`) | `Rules.java:102` → `Routes.java` (→ **B-010**) |
| **소림·불가가 남성 전용인가** (등록부에 근거 없음) | **오해** — 등록부가 **일부러 비워 뒀다**. 엔진은 누구나 받는다 | `player_creation.yml:94` · `GenderEngine.java:141` (→ **B-062**) |
| `lint_config` 가 **3건** 짖는다 | **거짓** — **21건**이다. 그리고 **21건 전부 거짓 양성**이다 | `tools/lint_config.py:141` (→ **B-001**) |
| `jegal` 의 **원형이 미결**이다 | **거짓** — 원형은 `기관저택` 으로 **정해졌다**. **빌더가 없다** | `world_map.yml:1275` (→ **B-040**) |
| §17 의 **80·40 은 근거 없음** | **과장** — 유도식 + 사용자 확정이 있다 (Q-H 해소) | `world_map.yml:3906` (→ **B-067**) |
| 섬 **두 곳**이 바닐라 해안에 앉는다 | **축소** — **세 곳**이다 (시박사 해관도) | `terrain.yml:204` (→ **B-041**) |
| `SkillDisplay` 의 `Material.PAPER` = **감사 위반 잔존** | **절반** — 셋 다 **`[대조]` 시험대의 대조군**이다. 감사가 옳은지가 먼저다 | `SkillDisplay.java:1230` (→ **B-026**) |

---

# 못 한 것 · 모르는 것 (2026-07-14)

**★ 추측을 사실처럼 적지 않기 위해 남긴다.**

1. **서버를 못 켰다** (규약: 서버 기동·RCON 금지). 그러므로 **인게임 검증이 필요한 것은 전부 「미확인」**이다 —
   B-013(접속 텔레포트) · B-014(접합 종단) · B-018(나루) · B-021(히트스톱 감각) · B-042(배) · B-059(채도).
2. **`game_audit` 의 위반 1~3 (미등록 NPC `character_id`·`mc_uuid`·`owner_id`)** — 이것이 **감사의 오독**인지
   (`by:` 는 NPC 가 아니라 **DB 컬럼명**으로 보인다) 진짜 위반인지 **확정 못 했다** (B-008).
3. **`stagger()` 의 속도 이중 기록**이 실제로 해로운지 **미확인** (B-025).
4. **`GenderEngine` 의 `hidden: true`** 가 의도인지 (시트에서 성별 보정을 숨기는 것) **확인 못 했다** (B-056).
5. **작업 트리가 101 파일 수정 상태**다 — 다른 담당들이 지금 고치는 중이다.
   **이 장부는 커밋된 HEAD 가 아니라 「지금 이 순간의 작업 트리」를 잰 것이다.** 그들이 끝내면 일부가 저절로 닫힐 수 있다.
6. **`.ogg` 30종**이라는 수는 **씨앗의 말**이다. 등록부는 개수를 못 박지 않았다 (배경음악 96종 침묵은 확인) — **수는 미확인** (B-035).

### B-091 · **지형이 「점처럼 찍혔다」** — 깔끔한 필드가 아니라 한 칸 한 칸 다른 필드
- **상태**: 닫힘
- **분류**: 결함
- **단계**: P1
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/TerrainForge.java` (결(結) 절 · `grain`)
- **의존**: —
- **닫는 조건**: 표본 구획에서 **인접쌍 불일치율**이 등록부의 문턱(`terrain_grain.yml eye.speckle_max`) 이하다.
  그리고 **결정론이 그대로다** (같은 좌표 = 같은 땅 — 난수를 다시 들이지 않았다)
- **검증**: `tools/TerrainGrainSelfTest.java` (서버 없이 돈다 · 눈 23개)
- **닫힘**: 2026-07-14 · 좌표 해시(파장 1칸)가 여섯 자리에서 사라지고 격자 잡음+보간이 섰다 — `TerrainForge.java` 의 `grain`/`grain2`/`undulation`/`edgeDrop`/`sandSkin`/`snowWobble`/`glacier`/`caveGravel`. 수치는 `config/terrain_grain.yml` 이 쥔다. `tools/TerrainGrainSelfTest.java` 눈 23개 전부 통과 (인접쌍 4.6~15.5% · 외톨이 0.0~0.2% · 문턱 30%/3%) — 그리고 **구판을 되살려 놓으면 눈이 전부 잡는다**

**원인 (사실)**: 뿌리는 결정론이 아니라 **파장**이었다. 흔들림을 전부 좌표 해시로 냈는데
(`Math.floorMod(x*7 + z*11, 3) - 1` 따위) **좌표 해시는 파장이 한 칸이다** — 이웃 칸과 아무 상관이 없다.
칸마다 독립으로 뽑히니 **점묘**가 됐다. 여섯 자리에서 그러고 있었다:
전이대(`feather`) · 중턱단(`benchTerrace`) · 들 가장자리(`levelField`) · 사막 표층(`sandColumn`,
자갈 1/29 · 붉은모래 1/11 을 **점점이**) · 설선(`snowColumn`, ±3 이 칸마다 튀어 **소금·후추**) ·
굴 바닥(`floor`, 1/6 자갈).

**고침**: 해시를 **격자점에서만** 뽑고 그 사이를 보간한다 (값 잡음 + smoothstep · 2옥타브).
값이 이웃과 상관을 가지므로 **덩어리**가 선다. **난수는 안 들였다** — 여전히 좌표의 순수 함수다.
수치는 `config/terrain_grain.yml` (덩어리 크기 · 자재 문턱 · 눈의 문턱).

**측정** (128×128 표본 · 인접쌍 불일치 / 외톨이):
| | 구판 (점묘) | 지금 (덩어리) |
|---|---|---|
| 지면 요철 | 100.0% / 96.9% | **5.6% / 0.0%** |
| 들 가장자리 | 50.0% / 0.0% | **7.9% / 0.1%** |
| 사막 표층 | 23.8% / **11.8%** | **4.6% / 0.0%** |
| 설선 | 50.0% / 0.0% | **15.5% / 0.0%** |
| 굴 바닥 | 33.3% / 16.1% | **11.1% / 0.2%** |

★ **눈을 시험했다**: 눈이 구판(칸 단위 해시)을 **전부 잡는다**. `cell: 1` 로 되돌리면 눈이 짖는다.
★ **밋밋함도 잰다** — 온 들을 잔디 한 장으로 덮으면 점묘율은 0 이고, 그것은 땅이 아니다.

### B-092 · **이미 빚어진 땅은 점묘판(v3)으로 서 있다** — 다시 빚을 것인가
- **상태**: 보류
- **분류**: 결정
- **단계**: P2
- **위치**: `run/mvt/plugins/HoncheonMVT/terrain_built.yml`
- **의존**: B-091
- **닫는 조건**: **사용자가 정한다.** 땅은 한 번만 선다(`TerrainLedger`) — 이미 선 땅은 고쳐진 판이 나와도
  **그대로다**. 되빚으려면 `/혼천 땅갈아엎기 <id>` 뒤 다시 조성해야 하고, 그러면 **그 위의 건물이 지워진다**
  (건축은 다시 세워야 한다). 청하현처럼 사람이 사는 땅은 그 대가가 크다.
  ★ 후보: (가) 그대로 둔다 (새로 서는 땅만 새 판) · (나) 사람이 없는 땅만 갈아엎는다 ·
  (다) 전부 갈아엎고 다시 짓는다
- **검증**: `python3 -c "import yaml;print(list(yaml.safe_load(open('run/mvt/plugins/HoncheonMVT/terrain_built.yml')).keys()))"` — 어떤 땅이 이미 굳었는가
- **닫힘**: —

### B-093 · **TerrainForge v4(점묘 수정판)가 아직 승인되지 않았다** — 승인 전에는 땅에 안 쓴다
- **상태**: 보류
- **분류**: 결정
- **단계**: P1
- **위치**: `config/terrain_gate.yml` → `approved_forge_version`
- **의존**: B-091
- **닫는 조건**: 사용자가 조감으로 새 땅을 보고 **승인**한다 → `approved_forge_version: 4`.
  ★ 그전까지 **모든 조성이 preview** 다 (월드에 한 블록도 안 쓴다). 사용자 지시 그대로:
  *"강남의 실제 지형 조성은 점묘 문제가 수정되고 **새 TerrainForge 버전이 승인될 때까지 실행하지 않는다**"*
  ★★ **땅은 한 번만 선다** — 승인이 늦는 것은 안전하고, 이른 것은 되돌릴 수 없다
- **검증**: `tools/TerrainGateSelfTest.java` — 승인 전에는 `writes() == false` 임을 잰다
- **닫힘**: —

### B-094 · **「수향」 프로파일이 없다** — 강남의 물골목은 아직 땅이 아니다
- **상태**: 열림
- **분류**: 미완
- **단계**: P2
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/TerrainForge.java` → `enum Profile`
- **의존**: B-091 · B-093
- **닫는 조건**: 「수향」이 **TerrainForge 의 프로파일**로 선다 (계약 ③ — `land_requests.yml` 이 만드는 시설이 아니다):
  주 물길 · 물골목망(`trunk`/`canal`/`alley`) · 육지 구획 · 수면·둑 높이 · **연결성**(고립 웅덩이 금지) · 전이.
  그리고 **검수 여섯**을 통과한다 (물이 있는가 · 연결됐는가 · 배가 뜨는가 · 내릴 수 있는가 ·
  육지끼리 오갈 수 있는가 · **점묘가 없는가**)
- **검증**: `tools/TerrainGateSelfTest.java` (지금은 `terrain_profile_unresolved` 로 **막는다**) — 구현되면 수향 전용 눈이 필요하다
- **닫힘**: —

★ **이번 바퀴에서 안 했다 (근거)**: 계약의 순서가 `1. 점묘 수정 → 2. 수향 → 3. 미리보기` 이고,
수향의 폭(`trunk`/`canal`/`alley`)은 **"점묘 수정 후 눈 시험·배 통행 시험으로 확정한다"** 고 사용자가 못 박았다.
점묘 수정판이 **아직 승인 전**(B-093)이므로 그 위에 수로 폭을 얹으면 **승인되지 않은 땅 위에 수로를 그리는 셈**이다.
지금은 **관문이 강남을 정확히 막는다** (`terrain_profile_unresolved` · `pointillism_fix_in_progress`) —
그래서 잘못 선 땅이 굳는 일이 없다. **승인 뒤 다음 바퀴에서 짓는다.**

### B-095 · **초기화가 지운 사람이 낡은 스냅숏으로 되살아났다** (사용자 데이터 오염)
- **상태**: 닫힘
- **분류**: 결함
- **단계**: P1
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/WorldBridge.java` (묘비 절)
- **의존**: —
- **닫는 조건**: 초기화 뒤 **파일·메모리·스냅숏 셋 다**에서 사라진다. **접속 중 초기화**를 흉내 내어
  일부러 어겨도 되살아나지 않는다
- **검증**: `tools/ResetTombstoneSelfTest.java` (서버 없이 돈다 · 눈 19개)
- **닫힘**: 2026-07-14 · `WorldBridge.java` 에 묘비(`GRAVES`·`forget`·`forgotten`·`sweepGraves`·`graves.json`)가 섰고 `State.sheet`/`linkedName` 이 그것을 먼저 본다. `Reset.java` 의 `wipe` 가 **가장 먼저** 묘비를 세우고(`WorldBridge.forget`), 지운 뒤 **되읽어 확인한다**(`verify` — 어긋나면 `severe`). `HoncheonMvt.peekLedger` 를 새로 냈다 (`ledger()` 는 `computeIfAbsent` 라 **묻는 것만으로 되살아났다**). `tools/ResetTombstoneSelfTest.java` 눈 19개 통과 — **묘비를 걷으면 사용자가 겪은 그 되살아남이 그대로 재현된다**

**원인 (사실 — 로그·파일로 확인)**: 초기화는 **제대로 지웠다** (`playerdata` 는 지금도 없고, 봇의 장부
`mvt_link`·`characters` 에서도 사라졌다). 범인은 **마크가 읽는 캐시**였다:
`run/bridge/world_state.json` 은 `generated_at: 01:43` — **초기화(01:46)보다 3분 먼저** 구워진 파일인데
그 안에 지워진 몸의 `sheet`·`links` 가 그대로 있었다. 01:49 재접속 → `SkillListener.syncSheet` 가
그 낡은 시트를 실어 `applySheet` → **linked = true** → 01:54 의 5분 타이머(`saveLedgers`)가 그것을 파일에 구웠다
(되살아난 `money: 228` · `흥정: 90` 은 그 스냅숏의 값과 **정확히 일치한다**). `Antechamber.onJoin`(MONITOR)은
그 `linked()` 를 보고 나루를 건너뛰었다. ★ **마크가 지운 것을 마크가 캐시에서 되살렸다.**

**고침 — 묘비(墓碑)**: 초기화가 **지운 시각**을 적는다(`WorldBridge.forget` · `graves.json` 에 굳는다).
그보다 **낡은 스냅숏은 그 몸에 대해 입을 다문다**(`State.sheet`/`linkedName` 이 null 을 준다).
봇이 새 스냅숏을 구우면 묘비는 **스스로 물러난다** — 영구 추방이 아니다. **정본은 언제나 봇이다.**
`Reset.wipe` 는 지운 뒤 **되읽어 확인**하고(`verify`), 어긋나면 `severe` 로 짖는다 —
*"지웠다"고 로그를 찍었으면 실제로 지워져 있어야 한다.*

### B-096 · **사용자의 `ledgers.yml` 이 지금도 오염돼 있다** — 되살아난 원장을 걷어야 한다
- **상태**: 열림
- **분류**: 결함
- **단계**: P1
- **위치**: `run/mvt/plugins/HoncheonMVT/ledgers.yml` → `05909c69-0126-490a-9448-649a19702637`
- **의존**: B-095
- **닫는 조건**: 그 절(節)이 파일과 메모리에서 사라진다. **손으로 지우면 안 된다** — 서버가 돌고 있으면
  5분 타이머(`saveLedgers`)가 메모리에서 **그대로 다시 굽는다**. 순서는 이렇다:
  **① 새 jar 를 올리고 서버를 재기동한다** (묘비 코드가 들어가야 한다) →
  **② 그 몸으로 접속해 `/혼천 초기화 전부` 를 두 번 친다** (30초 안에) →
  ③ 재접속하면 **나루(입도진)** 로 간다.
  ★ 디스코드의 `/초기화` 는 이제 안 통할 수 있다 — 봇의 `mvt_link` 가 이미 지워져 **봇이 그 몸의 uuid 를 모른다**.
  그래서 **마크 안에서** 쳐야 한다 (그 명령은 `player.getUniqueId()` 를 쓴다)
- **검증**: `python3 -c "import yaml;d=yaml.safe_load(open('run/mvt/plugins/HoncheonMVT/ledgers.yml'));print('05909c69-0126-490a-9448-649a19702637' in d)"` → `False` 여야 한다
- **닫힘**: —

### B-099 · **조성이 12분간 아무 말도 안 했다** — RCON 콘솔에서는 진행도 실패도 허공으로 간다
- **상태**: 닫힘
- **분류**: 결함
- **단계**: P1
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/Announce.java`
- **의존**: —
- **닫는 조건**: 조성의 말(관문 판정 · 진행 · 거절 · **실패**)이 **언제나 로그에 남는다**. RCON 이 끊겨도.
- **검증**: `python3 tools/silence_audit.py` · `python3 tools/silence_audit.py --selftest`
- **닫힘**: 2026-07-14 · `Announce` 신설 — **먼저 로그에 적고** 그 다음에 사람에게 보낸다. `MvtCommand` 의 조성 경로(`region`·`preloadThenBuild`·`finishRegion`·`buildWorld`) 전부와 **`TickBudget.build` 2곳의 진행·실패 콜백**을 이 창구로 돌렸다. 청크 적재 실패(`exceptionally`)도 이제 말한다. `tools/silence_audit.py` 통과 (자기 시험 포함)

**원인 (사실 — 로그가 증명한다)**: 화산파는 **죽지 않았다.** 09:46 에 시작해 **09:59:29 에 멀쩡히 끝났다**
(`[틱예산] 조성:hwasan — 연산 27604503건 · 729초` · 영수증 `forge_version: 4` 까지 적혔다).
병은 **말이 갈 곳이 없었다**는 것이다: `TickBudget.build(..., err = sender.sendMessage, log = sender::sendMessage)` —
★★ **RCON 의 sender 는 명령이 반환되는 순간 소켓을 닫는다** (로그의 `RCON Client shutting down` 이 매 명령 직후).
그러니 12분간의 진행 보고도, **실패 보고도 전부 허공으로 갔다.** 조성이 정말 터졌어도 아무도 몰랐을 것이다.
★ 관문(`TerrainGate`)은 제 할 일을 했다 — `commit` 으로 통과시켰다. 다만 **그 판정도 sender 로만 말했다.**

### B-100 · **조성 상한 900초에 화산파가 729초** — 더 큰 땅은 안전핀에 걸린다
- **상태**: 열림
- **분류**: 결함
- **단계**: P2
- **위치**: `config/performance.yml` → `max_seconds: 900`
- **의존**: B-099
- **닫는 조건**: 남은 봉우리 장소(곤륜·소림·무당 등 `lift` 가 화산 이상인 곳)가 **상한 안에** 들거나,
  상한이 근거 있게 올라간다. ★ 지금 여유는 **171초(19%)** 뿐이다 — 더 높은 산은 넘길 수 있다.
  넘기면 `abort` 가 걸려 **땅이 반만 선 채 중단된다** (그리고 그 땅은 원장에 안 적히므로 다시 빚을 수는 있다).
  ★ B-099 덕에 이제 **중단은 소리를 낸다** (`[틱예산] … 중단`) — 조용히 죽지는 않는다
- **검증**: `grep -n "max_seconds" config/performance.yml` · 조성 뒤 `[틱예산]` 초 수를 로그에서 확인
- **닫힘**: —

### B-118 · 새 몸이 **서장 없이** 강호로 나간다 — 접합 직후 대기소가 그냥 보낸다
- **상태**: 열림
- **분류**: 결함
- **단계**: P2
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/Antechamber.java`
- **의존**: —
- **닫는 조건**: 서장을 읽어야 하는 몸(서장 미완)은 자동 출도가 기다린다 — 판정이 "책이 지금 살아 있나"(토큰)가 아니라 "서장이 끝났나"(원장/다리 상태)를 본다. 부계정 재현 시 서장이 먼저 온다
- **검증**: `server-mvt/src/main/java/com/honcheon/mvt/Antechamber.java` · 사람 눈 (새 부계정 접합→서장→출도)
- **닫힘**: —

실사용 (2026-07-14, 부계정): 접합하자마자 서장 없이 청하현으로 이동 — 서사가 통째로
건너뛰어졌다. 자동 출도 게이트가 SeojangBook 토큰(이미 배달된 책)만 보는데, 새 몸은
봇이 서장을 짓는 데 시간이 걸려(LLM) 토큰이 아직 없다 — 경주에서 게이트가 진다.

### B-119 · NPC·동물이 **안 때려진다** — 타격 허용의 조건이 없다
- **상태**: 열림
- **분류**: 결함
- **단계**: P2
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/SkillListener.java`
- **의존**: —
- **닫는 조건**: 무엇이 맞는지(사냥감·비무 상대·적)와 무엇이 안 맞는지(마을 NPC 등)가 등록부의 조건으로 서고, 사냥터의 짐승이 실제로 맞는다
- **검증**: `server-mvt/src/main/java/com/honcheon/mvt/SkillListener.java` · 사람 눈 (산길 짐승 타격)
- **닫힘**: —

실사용 (2026-07-14): "npc와 동물등 안때려짐 (이거에 대한 조건이 필요할듯)" — 사냥이
기본 루프인데 짐승이 안 맞으면 사냥터가 죽은 콘텐츠다.

### B-120 · 세 태세 완료 시 **뒤에 있던 글이 전부 사라진다**
- **상태**: 열림
- **분류**: 결함
- **단계**: P3
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/SkillListener.java`
- **의존**: —
- **닫는 조건**: 재현 → 기전 판명(무엇이 어느 화면의 글을 지우는가) → 완료 후에도 안내가 읽힌다
- **검증**: 사람 눈 (세 태세 완료 재현) · `docs/BACKLOG.md`
- **닫힘**: —

실사용 (2026-07-14): "세가지 태세를 완료시에 뒤에있던 모든 글들이 사라짐" — 위치·기전
미상. 수사부터 (태세 안내 문구가 어디에 어떻게 표시되는지, 완료 이벤트가 무엇을 지우는지).

### B-121 · 입도 안내자가 없다 — "여기는 어디인가"를 말해 줄 자
- **상태**: 열림
- **분류**: 미완
- **단계**: P3
- **위치**: `config/npcs/cheongha_npcs.yml`
- **의존**: —
- **닫는 조건**: 입도(도착) 자리에 안내자 NPC가 서고, 다가가거나 우클릭하면 여기가 어디고 무엇부터 하면 되는지 말한다 — 문구는 등록부, 개인 사슬(B-109)의 첫 마디와 결이 맞는다
- **검증**: `config/npcs/cheongha_npcs.yml` · 사람 눈 (입도 지점 안내자)
- **닫힘**: —

실사용 (2026-07-14): "입도진의 안내자 npc가 있었으면 좋겠음, 여기는 어디인지 안내원 역할로."
경험 정본 §4(첫 1시간 안내선)의 실물 첫 조각.

### B-116 · HUD 텍스트가 **겹친다** — 격 두름·경공(나는발)이 서로를 덮는다
- **상태**: 열림
- **분류**: 결함
- **단계**: P3
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/SkillHud.java`
- **의존**: —
- **닫는 조건**: 액션바를 쓰는 손들이 한 줄을 두고 싸우지 않는다 — 우선순위/합성 규칙이 서고, 격 두름과 경공 표시가 동시에 읽힌다
- **검증**: `server-mvt/src/main/java/com/honcheon/mvt/SkillHud.java` · 실기동 (격 두름 + 나는발 동시)
- **닫힘**: —

실사용 (2026-07-14): 격 두름 텍스트가 다른 텍스트와 겹쳐 판독 불가, 경공 나는발도 동일.
SkillHud 의 actionBar 호출이 여러 곳(즉시 flash·statusBar 틱·:343)에서 한 줄을 덮어쓴다.

### B-117 · 접속이 **명령어 타이핑**이다 — 버튼·모달이어야 한다
- **상태**: 열림
- **분류**: 결함
- **단계**: P3
- **위치**: `server-bot/src/main/java/com/honcheon/bot/HoncheonBot.java`
- **의존**: —
- **닫는 조건**: 안내판(그리고 생성 직후 안내)에 [마크와 잇기] 버튼이 서고, 누르면 모달이 마크 닉네임을 받아 기존 접속 파이프(pend)로 흐른다 — 명령어 타이핑은 보조 경로로 남는다
- **검증**: `server-bot/src/main/java/com/honcheon/bot/HoncheonBot.java` · 사람 눈 (버튼→모달→[잇는다])
- **닫힘**: —

실사용 (2026-07-14): "/혼천 접속 닉네임:<닉> 을 친다" 안내가 명령어 입력 시스템 —
디스코드 UX 는 버튼+Modal 이 정본이다. 생성 직후 안내문에도 같은 버튼을 싣는다.

### B-115 · mob_model_audit 의 정원 눈이 **몸 0마리**를 세고도 조용하다 — 또 하나의 짖지 않는 눈
- **상태**: 열림
- **분류**: 빚
- **단계**: P4
- **위치**: `tools/mob_model_audit.py`
- **의존**: —
- **닫는 조건**: populations 판독이 현행 코드(소문자 populations · hunting_grounds.yml 적재)를 따라가고, "몸 0마리" 같은 공허 표본이면 위반으로 짖는다
- **검증**: `python3 tools/mob_model_audit.py`
- **닫힘**: —

RP-4 파일럿 (2026-07-14, R5 병⁴) 이 발견. 축 ④의 낮/밤 정원 행이 "몸 0마리" — 정규식이
`POPULATIONS = Map.of` 를 찾는데 코드는 리팩터로 소문자 `populations`(hunting_grounds.yml
적재)가 됐다. 리팩터가 진실을 옮겼는데 눈이 안 따라간 병의 **네 번째** 사례 (B-001·B-002·B-106 계보).
공허 표본을 통과로 치는 것 자체도 병이다 — 0마리면 잰 게 아니다.

### B-114 · 환경검수 ⑥이 **용암 바다·수역을 동굴로 센다** — 3.8% 의 정체
- **상태**: 열림
- **분류**: 빚
- **단계**: P4
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/TerrainAudit.java`
- **의존**: —
- **닫는 조건**: 표본이 기반 구조층(y<-54 용암 바다)과 의도된 액체를 제외하거나 따로 센다 — cheese 봉인 후의 새 월드에서 ⑥ 이 통과한다
- **검증**: 새 월드 `/혼천 환경검수` ⑥ · `server-mvt/src/main/java/com/honcheon/mvt/TerrainAudit.java`
- **닫힘**: —

★진행 6차 (2026-07-14 밤): 5차(하늘)는 실측이 기각 — 상한을 물려도 1,513칸 그대로.
대신 눈이 뱉은 좌표를 RCON 으로 열어 보니 **천장이 #leaves · 벽이 #logs** — 진범은 **나무**다.
지면 탐지(surfaceY)가 잎만 지나치고 통나무를 땅으로 읽어, 숲 나무 꼭대기가 지면이 되고
수관 속 공기가 「지하 공동」으로 계수됐다 (숲에 많고 벌목된 마을 중심에 적고 물 0 · 불변 —
전 관측 정합). 세계의 순도는 사실 완벽했다: cheese 식은 cave_cheese=0 이면 최소 +0.27(항상
양수·정본 수식 검산), density 5종 id 참조 확인, 카버 probability 0. isGround/isTreeish 분리
수리 + 하네스 56/56. **닫힘은 실측**: 다음 재기동 후 환경검수 ⑥ ~0%.

★진행 5차 (2026-07-14 저녁, 커밋 74f317e): 진범은 액체도 판굴도 아닌 **하늘**이었다 — 고정
대역 [cy−45, cy−5] 가 지면 낮은 기둥의 열린 하늘을 지하로 셌다 (저지 ~80기둥×~19칸 ≈
1,520 ≈ 실측 1,513). 기둥별 상한 = min(cy, 지면)−5 로 물렸고, 눈을 시험하는 눈
`tools/TerrainAuditSelfTest.java` 46/46. **닫힘은 실측**: 다음 재기동 후 환경검수 ⑥ ~0% 확인.

두 실측이 증거다 (2026-07-14): cheese 잠재움 **전** 3.81% · **후** 3.84% — 같은 시드에서
사실상 동일. 동굴이 진범이면 봉인 후 수치가 움직여야 했다. B-113 트랙의 정적 분석
(기존 팩만으로도 pillars=64 가 max() 로 지하 분기를 돌로 만든다)과 합치면, 이 3.8% 는
**고정 구조층** — y<-54 용암 바다(하드코딩·무해)와 수역이다. 눈이 그것을 "자연 동굴"로
읽는다. B-113(팩 수리·등록제·로딩 PASS)은 이 눈이 서야 실측으로 닫힌다.

### B-113 · no_caves 데이터팩이 **cheese 동굴을 못 끈다** — 새 월드 지하 공동 3.8%
- **상태**: 열림
- **분류**: 결함
- **단계**: P3
- **위치**: `tools/world_purity_audit.py` (no_caves 생성부)
- **의존**: —
- **닫는 조건**: 새로 생성한 월드에서 환경검수 ⑥(지하 공동)이 통과한다 — density_function 덮개에 `caves/cheese`(와 필요 시 underground 관련 잔여)가 포함된다. ★수정 후 스크래치 월드에서 레지스트리 로딩+실측 검증 필수 (noise 수술은 서버 사망 위험 — purity 규약: 필드 하나만)
- **검증**: `python3 tools/world_purity_audit.py` · 새 월드 환경검수 ⑥
- **닫힘**: —  ※수리 완료·실측 대기 (2026-07-14, R4 을³): cheese 는 noise_settings 인라인이나 `noise/cave_cheese` amplitudes 전 0.0 잠재움으로 봉인 (noise_settings 무수술 — 양수=돌 부호 근거). no_caves 가 등록제로 편입(`config/world_purity.yml` no_caves 절 신설 · `cave_contributors()` 공용 눈 — 빌드와 감사가 같은 함수, 버전 갈이 감지 · 감사 ⑥ 신설 + 사보타주 5종 확인 · 시험기 태그 구멍 봉합으로 카버가 진짜 한 필드 편집으로 복귀). 로딩 시험 102파일 PASS. ★트랙의 의심: 정적 분석상 기존 팩으로도 cheese 는 죽었어야 한다 — 3.8% 는 물(검수가 물도 공동으로 셈 + 시드 원점 수역)일 수 있다. 닫힘은 **B-114(눈 보정) 후 실측** — 재생성 실측(2026-07-14)에서 봉인 전후 3.81→3.84% 로 무변동: 3.8% 는 동굴이 아니라 구조층이다. 시드는 시드검사 최고점(20260710=90점)으로 유지 확정

기립 실측 (2026-07-14, 시드 20260710 처녀지): no_caves 가 **로드된 상태에서도** 지하 공동 3.8%.
팩 내용: carver 3종 + density_function 5종(spaghetti_2d·entrances·noodle·pillars·roughness) —
**cheese(큰 공동)가 목록에 없다.** 1.18+ 의 치즈 동굴이 그대로 산다. 수리 후 월드 재생성이
필요하므로 (청크에 이미 새겨짐) **플레이어 0명인 지금이 수리 적기**다. 재생성 때 시드도
교체 후보 (20260710 은 원점에 물 — 조성이 메우고 강행했고 장터 앵커 착지불가 1건이 남는다.
시드검사 실행해 둠).

### B-112 · 안전 지역의 **교차 비무 칼**이 게이트를 지난다 — Sparring 이 짝을 안 알려준다
- **상태**: 열림
- **분류**: 결함
- **단계**: P3
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/Sparring.java`
- **의존**: —
- **닫는 조건**: 안전 지역 게이트가 "같은 판의 비무인가"를 묻는다 — Sparring 에 `partnerOf` 류 공개 API 가 서고, A-B·C-D 두 판이 동시일 때 A→C 의 칼이 막힌다
- **검증**: `python3 tools/safety_audit.py`
- **닫힘**: —

B-006 을 닫으며 남긴 것 (2026-07-14, 트랙 갑''). isSparring 만 공개라 "둘 다 어느 비무 중"이면
게이트를 통과한다 — 안전 지역에서 두 판이 동시에 설 때 다른 판의 상대를 벨 수 있다
(Sparring 이 실피해로 처리). 함께: 안전 지역에서의 비무 허용은 설계 문서에 명시 조항이 없다
(문파 서열전 근거의 추론) — 명문화 한 줄이 필요하다. 간접 살해(독물·낙사 유도)도 게이트 밖.

### B-108 · 선언 태세의 **대칭이 미완**이다 — NPC 는 손을 못 세우고, 눈은 그 층을 모른다
- **상태**: 열림
- **분류**: 빚
- **단계**: P4
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/SkillListener.java`
- **의존**: —
- **닫는 조건**: ① NPC 도 방어_전념을 세울 수 있다 (npcBestStance 확장 — 우클릭 없는 몸의 선언) ② defense_audit 이 active_guard 층(선언 +2 · 패링 창)을 잰다
- **검증**: `python3 tools/defense_audit.py`
- **닫힘**: —

B-015 를 닫으며 남긴 것 (2026-07-14, 트랙 갑'). 플레이어만 선언·패링이 있다 — 대칭 원칙
("몸이 같으면 규칙이 같다")의 미완. 그리고 감사가 이미 경고한 막기 편향(6~7/9) 위에 선언(+2·
막기 강제)이 얹히는데, 그 대가(공격 포기·weapon_break)를 시뮬이 못 잰다 — 눈이 이 층을 배워야
편향 심화 여부를 말할 수 있다.

### B-109 · **개인 메인스토리가 없다** — 첫 1시간의 안내선 (경험 정본 §4)
- **상태**: 열림
- **분류**: 미완
- **단계**: P1
- **위치**: `docs/design/experience_design.md`
- **의존**: —
- **닫는 조건**: 캐릭터마다 발단·집안에서 파생된 **개인 목표 사슬**이 서고, 마크에서 안내선으로 보인다 (튜토리얼 화살표가 아니라 서사) — 첫 1시간의 막막함이 사라진다
- **검증**: `docs/design/experience_design.md` · 신규 캐릭터 실기동 (첫 목표가 보이는가)
- **닫힘**: —  ※설계+구현 최소선 완료 (2026-07-14, R4 병³ 설계 · R5 갑⁴ 구현): `PersonalStory.java` 신설 — 원장이 곧 상태(사슬_마디 이벤트·멱등), done_when 8어휘, 소급 인정, any_entry 문턱(어느 세력군 favor≥4 — 하오문으로 실증), 훅 12곳, /혼천 정보 심중(心中) 한 줄 (수치 은닉 검사 포함). 하네스 `tools/PersonalStorySelfTest.java` 23케이스 전부 통과 (Fable 재실행). ★닫힘은 신규 캐릭터 실기동(사람 눈 — 첫 목표가 보이는가) 후. echo·회상 책은 후속

사용자 확정 (2026-07-14, D-0 Q4): 부드러운 안내선의 실체는 **개인 메인스토리**다.
기존 자산과 접합한다: character_creation 의 발단·목표 · 초기 사건 3개 · 기연.

### B-110 · **세계 시계가 없다** — 메인스토리가 스스로 흐르는 기계 (경험 정본 §1)
- **상태**: 열림
- **분류**: 미완
- **단계**: P1
- **위치**: `docs/story_summary.md`
- **의존**: —
- **닫는 조건**: story_summary 의 단계(마교 암류→삼파전)가 등록부로 서고, 아무도 사건을 안 일으키면 세계가 그 시계로 진행하며, 플레이어 행동이 속도를 가감한다
- **검증**: `docs/story_summary.md` · 등록부 (신설 시 그 파일)
- **닫힘**: —  ※설계 완료 (2026-07-14, R4 정³): `docs/design/world_clock.md` + `config/world_clock.yml` 초안 (unwired 표식 — 읽는 코드가 없음을 파일이 말한다). 닫힘은 구현(WorldClockEngine·advanceWorld 편입·승인 명령)이 서고 세계가 실제로 흐를 때

사용자 확정 (2026-07-14, D-0 Q1): 세상의 흐름은 story_summary 로 진행된다 — 복권.
개인 서사는 창발 유지. 두 층은 박자 관계다.

### B-111 · **쓰러짐·의학당 회수가 배선되지 않았다 — PvE·PvP 공통** (경험 정본 §2)
- **상태**: 열림
- **분류**: 미완
- **단계**: P1
- **위치**: `docs/design/death_and_legacy.md`
- **의존**: —
- **닫는 조건**: **PvE·PvP 모두 항상 부활** — 쓰러짐 판정 → 의학당(의방) 회수가 실제로 돈다 (기존 죽음 파이프의 다운→이송 문법 재사용). 영구 죽음 경로는 최종장 전까지 닫힌다
- **검증**: `docs/design/death_and_legacy.md` · PvP 실기동 (쓰러짐→의방 회수)
- **닫힘**: —

사용자 확정 (2026-07-14, D-0 Q2 + 추가): **PvE 는 항상 부활한다.** 살상(영구 결과)은
메인스토리 최종장의 것이고, 최종장의 죽음 처리(관전 등)는 추후 고민으로 보류됐다.
death_and_legacy 의 "부활 없음" 조항 폐기 — 유산·계승 중 영구 죽음 전제 부분은 최종장 보류함에.


