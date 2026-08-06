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
- **상태**: 닫힘
- **분류**: ★세계
- **단계**: P1
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/Dojang.java:220`
- **의존**: —
- **닫는 조건**: **진짜 `ItemStack`** 이 `serializeItemsAsBytes` → `deserializeItemsFromBytes` 를 왕복해 **같은 것**으로 돌아옴을 본다
- **검증**: `/혼천 금고시험` — 진짜 ItemStack 9판 왕복 + 눈 시험 3종 (`server-mvt/src/main/java/com/honcheon/mvt/Dojang.java`). 옛 `tools/DojangVaultSelfTest.java` 는 문자열만 왕복시켰다 (증거 아님)
- **닫힘**: 2026-07-20 — `/혼천 금고시험` 9판 전체 통과 실측 (빈 칸·AIR·인챈트·PDC 신병·상자 속 상자·41칸 한 벌) + 눈 시험 3종이 짖는 것을 봤다 (커밋 f4efdb1)

`Dojang.java:223` 이 진짜로 하는 일: `Base64(ItemStack.serializeItemsAsBytes(items))`.
`Dojang.java:232` 가 되돌린다: `deserializeItemsFromBytes(...)`.

**그런데 자기 시험은 이 둘을 한 번도 부르지 않는다.** `tools/DojangVaultSelfTest.java:93` 이 왕복시키는 것은 **문자열**이다:

```java
before.realItems = "REAL:칠성검,비급,은자7971";   // (진짜 서버에서는 ItemStack Base64)
```

시험이 증명하는 것: *"YAML 이 불투명한 줄을 잃지 않는다."*
시험이 **증명하지 않는 것**: *"재기동을 건너 플레이어의 짐이 살아 돌아온다."*

★ **이것은 조용히 실패하고 사람의 물건을 잃는다.** 그리고 **과거 데이터 손실은 증명 불가**다 — 인벤토리 기록이 없다 (**B-012**).

### B-170 · ★★ 나루가 맡은 짐이 **메모리에만 있었다** — 재기동 한 번에 사라졌다
- **상태**: 닫힘
- **분류**: ★세계
- **단계**: P1
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/Antechamber.java:210`
- **의존**: B-011 (이것을 닫다가 나왔다)
- **닫는 조건**: 맡은 짐이 재기동을 건너 살아남는다 — 디스크에 원자적으로 적힌다
- **검증**: `/혼천 짐지문` 으로 전후 지문 대조 + `run/mvt/plugins/HoncheonMVT/ipdo_stow.yml` 실물
- **닫힘**: 2026-07-20 — 테스트 서버 실측: 맡긴 기록이 재기동을 견뎠다 (491바이트 sha1 불변 · 기동 로그 「맡은 짐이 1인 남아 있다」) · 안전 경로에서 봇의 짐(a57ce21e) 무파괴 보존 (커밋 f4efdb1)

`Antechamber.enter()` 는 나루의 꾸러미를 쥐여 주며 **사람의 진짜 짐 전부**를 `stowed` 맵에 옮겼다.
그 맵은 `new HashMap<>()` — **디스크에 적히는 곳이 없었다.** 옆의 주석은
*"서버가 죽어도 사람의 짐은 안 죽는다"* 라고 적혀 있었다. **사실이 아니었다.**

★ 크래시만의 문제가 아니었다. **평범한 재기동 한 번**에 신병·에메랄드23·황금사과3이 통째로 사라졌다
(실측: 지문 `c45b3386…` → `59666ba7…`(나무검+방패)). 진짜 범인은 `onQuit` 이었다 —
끊길 때 짐을 돌려주고 기록을 지우는데, **종료 중의 `setContents` 는 playerdata 까지 못 간다.**
그러면 짐은 인벤토리에도, 기록에도 없다.

**고친 방식** — 연무장 금고와 **같은 손**을 쓴다 (`ipdo_stow.yml`, 임시파일→원자적 이동):
- **맡는 순간** 적는다 · **돌려주는 순간** 지운다 · 기동 때 `loadStow()` 로 되살리고 짖는다
- `onQuit`·`onDisable` 에서는 **짐에 손대지 않는다** — 돌려주는 자리는 `restore()` 하나뿐이다
- 접속 때 안전망: 맡긴 것이 있는데 나루 **밖**에 서 있으면 그 자리에서 돌려준다
- 이미 맡긴 것이 있는데 또 맡으려 하면 **아무것도 파괴하지 않고 물러선다**
  (꾸러미를 못 쥐여 주는 것은 불편이고, 짐을 지우는 것은 손실이다 — 불편을 고른다)

★ 고치는 중에 **두 번 더 잃었다.** ① 첫 수리는 `onQuit` 이 파일을 비웠다(0바이트).
② 두 번째는 "덮어쓰지 않는다"고 거르기만 했는데 바로 아래 `clear()` 가 지금 든 것을 없앴다
(신병 겸·고대잔해·황금사과). **거르는 것과 버리는 것은 다르다** — 그래서 지금은 물러선다.

### B-171 · 검기 국소축 **부호 오류** — 대각 yaw 에서 초승달이 한 줄로 붕괴했다
- **상태**: 닫힘
- **분류**: 결함
- **단계**: P1
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/QiGeometry.java:325`
- **의존**: —
- **닫는 조건**: 어느 yaw 를 보고 베도 초승달 실루엣이 같다 · 베기면이 시선의 pitch 를 따른다
- **검증**: 인게임 세 방향 베기(대각 45°·하늘·발밑) + `server-mvt/src/main/java/com/honcheon/mvt/QiGeometry.java` 기저 직교(fwd·rgt 내적 0)
- **닫힘**: 2026-07-22 · 사람이 본 것 — 사용자 인게임 확인 「시선대로 나감」 (대각·상하 포함)

우측 벡터가 `rgtZ=+sin(f)` 로 틀려 **yaw 0°·90°에서만 fwd 와 직교**했다 (내적 = sin 2f).
45° 대각을 보고 베면 rgt ∥ fwd — 초승달이 반경 방향 한 줄로 무너졌다. 같은 수식이 네 곳에
살았다: slashBand(화면) · emitTemplate(템플릿) · spawnHeavySlash(부 횡참 판정) · kigiBandStrike(검기 판정).
**화면과 판정이 같은 거울이라 함께 틀렸고, 그래서 아무 감사도 못 잡았다.**

★ 촬영 봇(camtest)이 항상 축 정렬 yaw 로만 찍어 13회 실측 루프의 사각이었다 —
**표본이 축에 정렬되면 회전 대칭의 병은 안 보인다.** 다음 실측은 대각 yaw 를 표본에 넣어라.

같은 커밋에서 헌법 소조항 폐지: 「획은 하늘을 보지 않는다」(SkillDisplay.flat) — 사용자 지시
(2026-07-22 「바라보는 방향으로 베지 않음」)로 **베기면이 pitch 를 따른다** (닻은 여전히 수평 앞).
판정(kigiBandStrike)도 같은 기저로 따라 돌고, 세로 탐색 상자를 반경만큼 넓혔다.

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
- **닫힘**: —

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
- **닫힘**: 2026-07-14 밤 (재닫힘 — 진범은 눈이었다). ① `ensureDummies()` 의 `==` 판단은 이미 서 있었다. ② 입도진 월드 재생성(사용자 지시 · 오발 조성 잔해 정리 겸) 뒤 **갓 지은 나루 · 조성 1회 · 조성 로그 6/6** 인데 감사가 또 12 를 셌다 → 실증: 몸 하나가 PDC 키 둘(`ipdo_dummy` + `ipdo_dummy_label`)을 지녀 부분 문자열 계수가 **한 몸을 두 번** 셌다 (엔티티 청크 직접 해부: dummy 12 · label 6 → 실몸 6). **12몸은 쌓인 몸이 아니라 눈의 오독이었다.** ③ 수리: `marker_census()` 차감 계수 + 초과 갈래 처방문 갱신. ④ 눈의 시험: 계수기 합성 바이트 3종 신설 (옛 계수법이면 실패한다) — selftest 70/70 + 계수기 0실패. 실측: `antechamber_audit` **위반 0건 · exit 0** (허수아비 6 · 글판 9)

실측 (2026-07-14): 처음 보고는 **세계에 12몸 — 재조성이 쌓았다**였다. 재수사 결과 그날의
12 는 이중 계수였을 가능성이 크다 (당시 실몸 수는 이제 알 수 없다 — 월드가 재생성됐다).
겹쳐 선 허수아비가 타격 계측을 망친다는 원칙은 그대로다 — 허수아비는 **계기**다.
초과 갈래는 이제 진짜 쌓임에만 운다.

> **많은 것도 틀린 것이다.** (감사의 말)

### B-020 · `/혼천` 서브커맨드가 **25/25** — 더 못 넣는다
- **상태**: 보류
- **분류**: 빚
- **단계**: P3
- **위치**: `server-bot/src/main/java/com/honcheon/bot/HoncheonBot.java:93`
- **의존**: —
- **닫는 조건**: ★ 사람이 정해야 한다 — 서브커맨드 **그룹**으로 갈 것인가, 명령을 쪼갤 것인가
- **검증**: `server-bot/src/main/java/com/honcheon/bot/HoncheonBot.java:93` 의 `SubcommandData` 개수 < 25
- **닫힘**: —

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
- **닫힘**: —

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
- **닫힘**: —

**기록해 두는 이유**: 누군가 다시 시도하기 전에 **왜 안 되는지** 알라고.

### B-023 · 레이캐스트 — 캡은 **admit** 에 걸리지 raycast **호출**에 안 걸린다
- **상태**: 열림
- **분류**: 빚
- **단계**: P3
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/SkillListener.java:2368`
- **의존**: —
- **닫는 조건**: 후보 목록 자체가 유한하다 — 또는 이것이 문제가 아님을 계측으로 보인다
- **검증**: `python3 tools/perf_audit.py` · `Metrics.record("판정_가려내기")` 의 실측
- **닫힘**: —

> ★ **씨앗이 틀렸다.** "레이캐스트 상한 없음" 은 **거짓**이다 — 캡이 있다:
> `SkillListener.java:2368` → `if (out.size() >= cast.maxTargets())` (`max_targets_default: 8`).

**그러나 좁은 진실이 남는다**: 캡은 **받아들인 표적**(`out.size()`)에 걸린다. `aimedAt` 을 통과했으나 LOS 에 걸린 후보는
`hasLineOfSight` 를 **소비하고도** `out` 을 안 늘린다. 그리고 후보 목록(`getNearbyEntities`, `:2286`)은 **무제한**이다.

실무상 작다 (`aimedAt` 이 걸러낸다). **"벽 뒤 적대 몸이 많으면"** 이라는 걱정은 남지만 **실측이 없다.**

---

# P3 — 빚

### B-024 · `recovery()` 를 **규칙이 안 읽는다** — `total()` 에 접혀 있다
- **상태**: 열림
- **분류**: 빚
- **단계**: P4
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/SkillEngine.java:1147`
- **의존**: —
- **닫는 조건**: 후딜이 **독립된 뜻**을 갖는다 (캔슬 창을 열려면 분리해야 한다)
- **검증**: `python3 tools/combat_audit.py`
- **닫힘**: —

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
- **닫힘**: —

`:2592` — `if (ticks >= 10 && target instanceof Mob)` → `setVelocity(...)`.
**의도적으로 남겼다** (`:2581`): *"impact.enabled: false 로 되돌렸을 때 이 세계가 정확히 옛 동작으로 돌아가야 하므로."*

★ 다만: `impact.enabled` 가 참일 때 **속도를 두 번 쓴다** (히트스톱 해제 전 창에서). 무해한지 **미확인.**

### B-029 · `perf_audit` — **한 틱 폭탄** · 상한 미상 2건
- **상태**: 열림
- **분류**: 빚
- **단계**: P4
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/CheonghaBuilder.java:300`
- **의존**: —
- **닫는 조건**: `perf_audit` 위반 0건 — 조성이 틱을 나눠 쓴다
- **검증**: `python3 tools/perf_audit.py`
- **닫힘**: —

`[폭탄] CheonghaBuilder.build()` (`:300`) · `[상한 미상] SectBuilder.pagoda()` (`SectBuilder.java:118`) ·
`[상한 미상] CheonghaBuilder.generalStore()` (`:6012`).

> 전례: `9fdf956 [perf] 한 틱 폭탄을 해체했다 — MSPT 625ms → 13.9ms`. **같은 종류가 아직 남았다.**

### B-030 · `gap_audit` — **유령 절 112개** · 테스트만 부르는 메서드 39 · 호출자 없는 메서드 60
- **상태**: 열림
- **분류**: 빚
- **단계**: P4
- **위치**: `docs/design/gap_audit.md`
- **의존**: —
- **닫는 조건**: 유령 절이 준다 — 배선하거나, 지우거나, **미배선이라고 적거나**
- **검증**: `python3 tools/gap_audit.py`
- **닫힘**: —

> **유령 절 112개** — *"규칙으로 적혀 있고, 굴러가지 않고, 아무도 그 사실을 재지 않는다."*
> **테스트만 부르는 메서드 39개** — *"파리티 테스트가 초록이어도 플레이어는 그 규칙을 겪지 않는다."*

★ 이것은 **한 항목이 아니라 112개의 항목**이다. 여기 적는 이유는 **재는 눈이 있다**는 것을 기억하기 위해서다.
줄일 때는 `gap_audit` 의 수를 **떨어뜨려** 증명하라.

### B-031 · 동행(party) — 엔진은 있으나 **명부가 없다**. 아군을 **의도**로 가른다
- **상태**: 열림
- **분류**: 빚
- **단계**: P4
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/SkillListener.java:2335`
- **의존**: —
- **닫는 조건**: 동행 명부(초대·가입·탈퇴)가 선다 → 아군이 **사실**로 갈린다
- **검증**: `python3 tools/game_audit.py` (party.yml 미참조 절 `time_sync` 가 사라지는가)
- **닫힘**: —

> 씨앗 정정: "party 시스템이 없다 · 자바 독자 없다" 의 **뒷절반은 거짓**이다 —
> `core/src/main/java/com/honcheon/core/rules/PartyEngine.java` 가 `party.yml` 을 읽는다.

**그러나 그것은 산수일 뿐이다.** 유일한 프로덕션 호출자는 **디버그 명령** `/혼천 협공 <인원>` (`MvtCommand.java:221`) —
인원수를 **타이핑으로 받는다**. **명부도, 초대도, 가입 상태도 없다.**

아군 판별은 코드가 자기 입으로 말한다 (`SkillListener.java:2335`):
> *"【무엇으로 아군을 가르는가 — 파티 시스템이 없다】 동행(party)은 아직 코드에 없다… 대신 **의도(意)**로 가른다"*

### B-032 · `장터` 앵커 = **마을 원점** — 옮기면 마을이 이사한다
- **상태**: 열림
- **분류**: 빚
- **단계**: P4
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/CheonghaBuilder.java:429`
- **의존**: —
- **닫는 조건**: **원점이 별도 앵커로 분리된다** (그것이 정답인데 안 했다)
- **검증**: `python3 tools/map_lint.py`
- **닫힘**: —

`CheonghaBuilder.java:330` — `anchors.put("장터", loc(world, cx, cy + 1, cz))`. 원점에는 **우물**이 있다 — 발밑은 물.
코드가 병과 **고치지 않기로 한 이유**를 함께 적었다 (`:429-435`):

> *"**앵커를 옮기지는 않는다.** `장터` 앵커는 **14곳**에서 마을 원점 표식으로 쓰인다
> (콘솔 재조성은 `anchor("장터").getBlockY() - 1` 을 원점으로 삼는다 — 옮기면 **마을이 이사한다**)."*

지금의 우회는 `Standing.landing()` (`:437`). **정답은 원점을 쪼개는 것**이고, 안 했다.

### B-033 · `combat_audit` 경고 3건 — 숙련 스케일 · 심법의 관문 · **대칭 대결의 선공**
- **상태**: 열림
- **분류**: 빚
- **단계**: P4
- **위치**: `config/skills.yml`
- **의존**: —
- **닫는 조건**: `combat_audit` 경고 0건
- **검증**: `python3 tools/combat_audit.py`
- **닫힘**: —

1. **숙련 스케일 불일치** — `skills.yml` 은 `0~10`, `mastery_ladder` 는 극성 **8** 이 상한, `judgment.yml` 은 `max: 10`.
   **8~10 구간의 뜻이 정의되지 않았다.**
2. **심법이 관문을 안 탄다** — `jeongsim_geomgyeol` 이 `simbeop.yml` 로 이관됐는데,
   *"심법이 액션 코스트 1과 패링 태세를 갖는다는 사실을 **어느 게이트도 검사하지 않는다**."*
3. ★ **대칭 대결에서 선공 규칙이 승자를 못 정한다** — *"민첩+감각 동률 → 경지 높은 쪽"* 인데 **경지도 같으면 그다음 규칙이 없다.**
   그리고 **선공은 전부다**: *"대칭 대결의 승패가 판정이 아니라 선공 결정에서 이미 끝난다 (선공자 내구 20% 잔존)."*

### B-034 · `gap_audit` — 문서가 약속한 키가 등록부에 **없다** (3건)
- **상태**: 열림
- **분류**: 빚
- **단계**: P4
- **위치**: `docs/design/gap_audit.md`
- **의존**: —
- **닫는 조건**: `gap_audit` 의 이 세 경고가 사라진다
- **검증**: `python3 tools/gap_audit.py`
- **닫힘**: —

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
- **닫힘**: —

**실측 (2026-07-14): `.ogg` 파일 0개.** 등록부가 자기 입으로 적어 뒀다 (`:929`):
> *"【현황】 혼천의 소리 = **0종.** assets/*/sounds/ 에 .ogg 가 한 장도 없다."*
> *".ogg(Vorbis) 는 **파이썬으로 생성할 수 없다.** 이 저장소의 팩 파이프라인은 PNG·JSON 만"*

**배경음악 96종 전부 침묵.** ★ **이것은 코드로 못 닫는다.** 정직한 미완이다.

### B-036 · HUD 잔여 — `crosshair` · `air` · `boss_bar`
- **상태**: 열림
- **분류**: 미완
- **단계**: P5
- **위치**: `config/resourcepack_design.yml:995`
- **의존**: —
- **닫는 조건**: 세 스프라이트가 선다
- **검증**: `python3 tools/texture_audit.py`
- **닫힘**: —

등록부가 남은 것을 안다 (`:995`): `남은_것: [crosshair, "air(물속 거품)", "boss_bar(보라색)"]`.
실측: `resourcepack/assets/minecraft/textures/gui/sprites/hud/` 에 hotbar·experience_bar·food·heart 만 있다.

### B-037 · **명병 4문파 미제작** (곤륜·청성·해남·개방)
- **상태**: 열림
- **분류**: 미완
- **단계**: P5
- **위치**: `config/resourcepack_design.yml:878`
- **의존**: —
- **닫는 조건**: `MYEONGBYEONG` 이 12문파를 갖는다
- **검증**: `python3 tools/build_resourcepack.py` · `python3 tools/model_key_audit.py`
- **닫힘**: —

`tools/build_resourcepack.py:1555` 의 `MYEONGBYEONG` 에 **8문파**뿐 (hwasan·jeomchang·jongnam·namgung·mudang·paengga·dangga·sorimsa).
등록부가 이유까지 적었다 (`:878`):
> `gonryun·cheongseong·haenam·gaebang: "청구서 — 시간. **개방은 봉(棒) 계열 자체가 없다** (신설 선행)"`

★ **개방은 병기 계열부터 새로 만들어야 한다** — 다른 셋과 무게가 다르다.

### B-038 · 획(참격선) **2차 확장** — 반월형 궤적 · 문파 색 · 어검/심검
- **상태**: 열림
- **분류**: 미완
- **단계**: P5
- **위치**: `config/skill_motion.yml`
- **의존**: —
- **닫는 조건**: 검기 단계 반월형 파티클 궤적 · 문파별 색 분화 · 어검/심검 전용 형체가 선다
- **검증**: `python3 tools/motion_audit.py` · `python3 tools/texture_audit.py`
- **닫힘**: —

★ **`ult` 판의 채도는 인게임에서 정할 값이다** (기계로 못 정한다 → **B-062**).

### B-039 · **조성 16곳이 조용히 아무것도 안 세운다** (53곳 중 32곳만 선다)
- **상태**: 열림
- **분류**: 미완
- **단계**: P5
- **위치**: `config/world_map.yml:841`
- **의존**: B-047, B-048, B-049, B-050
- **닫는 조건**: `map_lint` 의 「선다」가 53곳에 닿는다 (또는 「안 짓기로 함」으로 정직하게 닫힌다)
- **검증**: `python3 tools/map_lint.py`
- **닫힘**: —

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
- **상태**: 열림
- **분류**: 미완
- **단계**: P5
- **위치**: `config/world_map.yml:1275`
- **의존**: B-050
- **닫는 조건**: `RemoteBuilder.Archetype` 에 `기관저택` 이 선다
- **검증**: `python3 tools/map_lint.py`
- **닫힘**: —

> ★ **씨앗이 이것을 잘못 분류했다.** `jegal` 은 `pending` 이 **아니다** — `world_map.yml:1275` 에 `archetype: 기관저택` 이 **있다**.
> 병이 다르다: **원형은 정해졌는데 그 원형을 아는 빌더가 없다** (`RemoteBuilder` 의 21개 원형에 `기관저택` 이 없다).

그러나 **먼저 물어야 한다**: *"무엇이 있어야 제갈이 제갈인가"* (**B-050**). 그것 없이는 지어도 제갈이 아니다.

### B-041 · **바다를 파는 손이 없다** — 섬 둘과 해관이 바닐라 해안에 앉는다
- **상태**: 열림
- **분류**: 미완
- **단계**: P5
- **위치**: `config/terrain.yml:192`
- **의존**: —
- **닫는 조건**: `TerrainForge` 가 물을 만든다 — 섬이 **섬**이 된다
- **검증**: `python3 tools/map_lint.py` · `server-mvt/src/main/java/com/honcheon/mvt/TerrainAudit.java:416`
- **닫힘**: —

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
- **닫힘**: —

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
- **닫힘**: —

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
- **상태**: 열림
- **분류**: 미완
- **단계**: P5
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/TerrainForge.java:691`
- **의존**: B-043 (요청 어휘가 문이다) · B-052 (반경이 없으면 땅이 안 선다)
- **닫는 조건**: `수향` 이 `강` 과 **다르게 빚어진다** — 물이 골목이 된다
- **검증**: `python3 tools/map_lint.py` · ★ 인게임 — 청하현과 **한눈에 다른가**
- **닫힘**: —

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
- **상태**: 열림
- **분류**: 미완
- **단계**: P5
- **위치**: `config/land_requests.yml:127`
- **의존**: —
- **닫는 조건**: 남궁이 **물을 청구**한다 · 지형 계층이 판다 (조성이 아니라)
- **검증**: `python3 tools/map_lint.py`
- **닫힘**: —

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
- **닫힘**: —

**셋 다 「★ 명시적 미배선」이다** — *"아무도 안 읽는다. **그리고 그렇다고 적혀 있다.**"*
**거짓말이 아니라 계획된 약속**이므로 위반이 아니다. 조건은 각 문서 머리에 있다.

★ **이 항목은 "지금 하라"가 아니라 "잊지 마라"다.**

### B-045 · `design_review` 의 **G7 · G8** 이 남았다
- **상태**: 열림
- **분류**: 미완
- **단계**: P5
- **위치**: `docs/design/design_review.md`
- **의존**: B-005
- **닫는 조건**: G7(전투 상세)이 `combat_system.md` 로 닫힌다 · G8 은 MMO 단계로 미룬다
- **검증**: `python3 tools/combat_audit.py`
- **닫힘**: —

`design_review.md` 의 공백 목록 G1~G8 중 **G1~G6 은 닫혔다** (문서가 증거와 함께 적어 뒀다 — **좋은 선례다**).
**남은 것**: **G7** (전투 상세 규칙) · **G8** (공유 세계 동시성 — *"MMO 단계 과제로 미뤄도 됨"*).

### B-046 · `game_audit` 경고 6건 — **엔진이 읽는 파일인데 미참조 절**
- **상태**: 열림
- **분류**: 미완
- **단계**: P5
- **위치**: `config/economy.yml`
- **의존**: —
- **닫는 조건**: `game_audit` 경고 0건
- **검증**: `python3 tools/game_audit.py`
- **닫힘**: —

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
- **닫힘**: —

`archetype: pending` — 태원(`:841`) · 무한(`:872`) · 소주(`:905`) · 강남상로(`:931`) · 고창(`:1719`) · 돈황(`:1902`).

★2026-07-15 갱신: 웨이브-1 시안(`docs/design/sangdan_archetypes.md` — 신설 5종)은 **사용자
보류** — "v5 브리프 프로토콜로 재작성". 상단 6곳은 조사 기반 문서 체계(sect_brief_protocol 의
상업 거점 유형 확장 — 헌장 §3.2)의 첫 사례로 다시 쓴다. 기존 시안은 재료로 흡수. v5 지도
작업(청하현권 뒤 확장 단계)에서 착수.

### B-048 · 새외(塞外) 4곳의 **원형이 없다**
- **상태**: 보류
- **분류**: 결정
- **단계**: P2
- **위치**: `config/world_map.yml:1763`
- **의존**: —
- **닫는 조건**: 4곳에 `archetype` 이 정해진다
- **검증**: `python3 tools/map_lint.py`
- **닫힘**: —

성화단(`:1763`) · 설역대사(`:1795`) · 오독채(`:1836`) · 동영도(`:1866`).

### B-049 · **점창 · 청성** — 사용자가 직접 설계하기로 했다
- **상태**: 보류
- **분류**: 결정
- **단계**: P2
- **위치**: `config/world_map.yml:1086`
- **의존**: —
- **닫는 조건**: 사용자가 두 문파의 형태를 정한다
- **검증**: `python3 tools/map_lint.py`
- **닫힘**: —

점창(`:1086`) · 청성(`:1108`). ★ **다른 사람이 대신 정하면 안 된다** — 사용자가 직접 하기로 한 것이다.

### B-050 · **제갈** — *"무엇이 있어야 제갈이 제갈인가"*
- **상태**: 보류
- **분류**: 결정
- **단계**: P2
- **위치**: `config/world_map.yml:1275`
- **의존**: —
- **닫는 조건**: 기관저택의 **뜻**이 정해진다 → 그 다음에 빌더를 만든다 (**B-040**)
- **검증**: `python3 tools/map_lint.py`
- **닫힘**: —

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
- **닫힘**: —

독문(`:1521`) · 마교 낙양분타(`:1602`) · 시박사(`:2831` — ★ 그리고 **바다가 필요하다**, B-041).

### B-052 · `commercial_class` 4곳 · `wealth_tier` 6곳 — **등록부에 근거가 없다**
- **상태**: 보류
- **분류**: 결정
- **단계**: P2
- **위치**: `config/world_map.yml:3775`
- **의존**: —
- **닫는 조건**: 10개 값이 정해진다
- **검증**: `python3 tools/map_lint.py`
- **닫힘**: —

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
- **닫힘**: —

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
- **닫힘**: —

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
- **닫힘**: —

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
- **닫힘**: —

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
- **닫힘**: —

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
- **닫힘**: —

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
- **닫힘**: —

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
- **닫힘**: —

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
- **닫힘**: —

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
- **닫힘**: —

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
- **검증**: `python3 tools/combat_audit.py --lint-only` · `python3 tools/defense_audit.py` (★2026-07-25 좁힘: combat_audit 시뮬이 눈의 v2 갱신으로 B-177 밸런스 실측을 겸하게 됐다 — 그 위반 7건은 v2 수치 튜닝의 몫이지 이 항목(평타 배선)의 회귀가 아니다. 배선 증명은 린트 + defense_audit ②가 담당)
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
- **상태**: 닫힘
- **분류**: 결함
- **단계**: P2
- **위치**: `config/performance.yml` → `max_seconds: 900`
- **의존**: B-099
- **닫는 조건**: 남은 봉우리 장소(곤륜·소림·무당 등 `lift` 가 화산 이상인 곳)가 **상한 안에** 들거나,
  상한이 근거 있게 올라간다. ★ 지금 여유는 **171초(19%)** 뿐이다 — 더 높은 산은 넘길 수 있다.
  넘기면 `abort` 가 걸려 **땅이 반만 선 채 중단된다** (그리고 그 땅은 원장에 안 적히므로 다시 빚을 수는 있다).
  ★ B-099 덕에 이제 **중단은 소리를 낸다** (`[틱예산] … 중단`) — 조용히 죽지는 않는다
- **검증**: `config/performance.yml:67` (max_seconds 1800) · 조성 뒤 `[틱예산]` 초 수를 로그에서 확인
  (forge-wave1 로그: 곤륜 1054초 · 마교 진령 959초 완주)
- **닫힘**: 2026-07-15 · 사용자 승인으로 900→1800 (`config/performance.yml:67`) 후 전맵 조성 28곳 완주
  (`run/forge-wave1-20260715-023507.log`) — **곤륜 1054초 · 마교 진령 959초**: 옛 상한 900 이면
  중단됐을 두 곳이 상한 안에 섰다. 최장 1054초 — 새 상한의 여유 41%. 중단·[틱예산] 경고 0건

### B-127 · 재조성 뒤 **경계가 끊겼다** — 급단차 8.5% · 진입 0/4
- **상태**: 닫힘
- **분류**: 결함
- **단계**: P3
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/TerrainForge.java`
- **의존**: B-126
- **닫는 조건**: 환경검수 ③(경계 ≤8%)·④(네 방위 진입)가 통과한다 — 회귀 원인(복구 재조성의 기준면 1칸 어긋남 y89→88 추정 · 채움 계약의 가장자리 영향 여부) 판명 포함
- **검증**: 새 월드 `/혼천 환경검수` ③④ · `server-mvt/src/main/java/com/honcheon/mvt/TerrainForge.java`
- **닫힘**: 2026-07-15 00:53. ① 원인 판명: **영수증 소실 재조성이 이음 띠(feather)를 잃었다** — 마을 단(y94~96)과 자연면(y89~90) 사이가 벽이 됐다 (좌표 실측: 네 방위 +3~+7칸 벽 · 턱 46곳). `fill_below_raised` 비인과는 Codex R6~R8 독립 검토가 확인 (直因 기각). ② 수리: `/혼천 경계다듬기` 신설 — 부지 안은 한 칸도 안 건드리고 feather 계약(결정론 · 물 불가침 · 올린 밑 채움)을 경계 띠 r61~83 에 재적용. 표적 해석은 환경검수와 한 함수(resolveTerrainTarget) · 표적 선출력 · sweepTargetSane 핀. ③ 실측 (RCON): 다듬기 2초 → 환경검수 ③ 8.2%→**6.9% ✅** · ④ 0/4→**통과 ✅** · **총평 위반 0건**. 사람 눈(네 방위로 걸어 들어가기)은 환영하되 닫는 조건(③④ 통과)은 기계가 쟀다

【진행 2026-07-15】 눈이 좌표를 얻었다: 환경검수 ③④가 위반 자리(x,y,z)·단차 크기·막힌 이유를
말한다 (TerrainAudit — 판정 로직 불변, ❌ 갈래에서만 출력). 실측 (00:50, RCON):
③ 8.2% · 46회 · 최대 5칸 — 최악 턱 (3,94→89,446)·(-3,94→89,446) 등, 외 38곳.
④ 0/4 — 북 (0,89,323)→+7칸 · 남 (0,89,445)→+7칸 · 동 (61,89,384)→+3칸 · 서 (-61,89,384)→+3칸.
진단: 마을 단(y94~96)과 바깥 자연면(y89~90) 사이 이음 띠(feather)가 사라진 꼴 — 영수증 소실
재조성 가설과 정합. 수리는 경계 띠의 재이음(feather 재적용)이 맞다 — 재조성 금지 유지.

실측 (2026-07-14 밤): 복구 재조성 전 ③ 3.5%·④ 4/4 → 후 ③ 8.5%·④ 0/4. 같은 밤의 변인:
① 손 복원 앵커 y89(원본은 90 추정 — 검수 중심이 89→88로 밀림) ② fill_below_raised 첫 실전
③ 흑수나루 못 파기 (48,524). 지하(⑥ 0.00%)는 완치 — 경계만의 회귀다.

### B-126 · 조성 명령에 **세계 가드가 없다** — 나루에서 치면 나루 심부에 마을이 선다
- **상태**: 열림
- **분류**: 결함
- **단계**: P2
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/MvtCommand.java`
- **의존**: —
- **닫는 조건**: 조성(buildTown)이 본세계가 아닌 곳(나루·연무장)에서 거부하며 이유를 말한다 — "여기는 나루다: 조성은 강호의 땅에서". 앵커 덮어쓰기 전에 세계·기준면 안전핀(sweepTargetSane 결)을 지난다
- **검증**: `server-mvt/src/main/java/com/honcheon/mvt/MvtCommand.java` · 사람 눈 (나루에서 조성 시도 → 거부)
- **닫힘**: —  【진행 2026-07-15】 가드 구현: `forgeWorldBarred()` — 조성·세계조성·지역조성·지형조성·땅갈아엎기 다섯 명령이 나루·연무장·비본세계에서 거부하며 이유를 말한다 ("여기는 나루다 — 조성은 강호의 땅에서"). 기준면 안전핀: 조성 좌표/발밑 y<0 거부 (sweepTargetSane 결 · 실사고의 y=−56). 콘솔은 각 명령의 기본(본세계·앵커의 세계)이 정하므로 안 막는다. 빌드·기동 실측 완료 — 닫힘은 사람 눈(나루에서 시도→거부) 뒤

실사고 (2026-07-14 밤): 나루 세계에서 /혼천 조성 실행 → 마을이 나루 심부(y≈−56)에
재조성되며 앵커 9종·구역·조성 상태 등록부가 통째로 덮였다. 실마을 블록은 무사했으나
등록부 복구에 수동 개입 필요했다. 안내 실수(검토자: "어디서"를 빼먹은 지시)도 공범 —
그러나 명령이 스스로 거부했어야 한다.

### B-125 · `cheongha_hyeon` 이름의 **낡은 구역**이 원점 전고도 상자로 남아 있다
- **상태**: 열림
- **분류**: 빚
- **단계**: P4
- **위치**: `config/world_map.yml`
- **의존**: —
- **닫는 조건**: zones/원장의 청하현 구역이 실제 마을(중심 0,89,384 · 반경 61)을 가리킨다 — 환경검수·지하정리의 지역id 경로가 무인자 경로와 같은 표적을 잰다
- **검증**: `server-mvt/src/main/java/com/honcheon/mvt/MvtCommand.java` · `python3 tools/map_lint.py`
- **닫힘**: —

실사격 (2026-07-14 밤): `혼천 지하정리 cheongha_hyeon` 이 구역을 (−2,−56,−1)·r68 로
풀어 원점 심부에 85,052칸을 채웠다 (실피해 없음 판정 — 마을 밖 심부, 물·용암 보존).
지금은 안전핀(sweepTargetSane)이 이 표적을 거부하지만, 환경검수의 지역 경로도 같은
낡은 구역을 읽으므로 거짓 수치를 낼 수 있다 — 구역·원장 정비가 뿌리 수리다.

### B-123 · 입도진 과제 카운트가 **여전히 겹친다** — 마지막 남은 맨 액션바 손
- **상태**: 열림
- **분류**: 결함
- **단계**: P3
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/Antechamber.java`
- **의존**: B-116
- **닫는 조건**: 입도진의 과제 진행 표시(bump 의 sendActionBar)가 B-116 의 flash 채널을 탄다 — 격 순환 flash 와 과제 카운트가 순서대로 읽힌다. B-116 이 경고한 잔존 손들(Sparring·HuntListener·Incidents 포함) 전수 처리 여부를 판단해 기록
- **검증**: `server-mvt/src/main/java/com/honcheon/mvt/Antechamber.java` · 사람 눈 (입도진 격 순환)
- **닫힘**: —

실사용 (2026-07-14 밤): "입도진에서 격 두름에 여전히 텍스트 겹침." 기전 확정: 격 순환
한 사건에 SkillListener flash("검기 — …")와 Antechamber bump 카운트("격 … n/m")가
같은 줄을 동시에 쓴다 — B-116 보고의 "다른 채널의 손 잔존" 그대로.

### B-124 · 입도진 과제가 **되는 몸과 안 되는 몸**을 말하지 않는다
- **상태**: 열림
- **분류**: 결함
- **단계**: P3
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/Antechamber.java`
- **의존**: —
- **닫는 조건**: 수사로 판명 — ① 격 과제의 capable() 문이 새 몸(범인)에게 닫혀 있으면 "설명대로 해도 안 깨지는" 그 증상이다: 못 하는 과제는 글판이 이유를 말하거나 대상에서 빠진다 ② 이미 건넌 몸의 재방문: 과제 장부가 메모리뿐이라 재기동마다 리셋되는 것도 설계로 확정하고 문구로 상태를 말한다 (침묵 금지)
- **검증**: `server-mvt/src/main/java/com/honcheon/mvt/Antechamber.java` · 사람 눈 (새 몸·건넌 몸 각각)
- **닫힘**: —

실사용 (2026-07-14 밤): "캐릭터 초기화를 하지 않으면 입도진에서 설명대로 해도
안깨지는건가? 싶음" — 과제가 왜 반응하지 않는지 화면이 말해 주지 않는다.

### B-122 · 몰래 죽이기의 문법 — 목격·수배·소문의 잠행 설계
- **상태**: 열림
- **분류**: 미완
- **단계**: P4
- **위치**: `docs/design/experience_design.md`
- **의존**: B-119
- **닫는 조건**: 설계 문서가 선다 — 목격자 판정(누가 봤나)·들킴의 결과(수배·소문·혈채 기존 계 재사용)·안 들킴의 결과(조용한 실종, 소문의 지연 발화)·시체와 흔적. 발명 최소, 기존 업보 계(혈채·수배·소문) 접합 우선
- **검증**: `docs/design/experience_design.md` · `docs/BACKLOG.md`
- **닫힘**: —

사용자 확정 (2026-07-14): "마을 npc도 때려져야합니다 몰래 죽일수도 있어야 해요."
B-119 는 "죽는다 + 죽음이 다리 사건으로 남는다"까지 — 그 사건을 누가 어떻게 알게
되는가(목격·발각·소문)가 이 항목이다.

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
- **닫는 조건**: 살아 있는 것은 다 맞는다(사냥감·가축·마을 NPC — ★사용자 확정: "몰래 죽일수도 있어야") — 예외는 등록부의 빈 목록으로 시작. NPC 죽음은 다리 사건으로 봇에 남는다. 사냥터 짐승이 실제로 맞는다. ★Codex 검토(2026-07-15, R6~R8 조건부 승인 · P1): `TradeListener.onEntitiesLoad` 만으로는 **리스너 등록 전에 이미 적재된 청크의 옛 무적(invulnerable=true) NPC 가 이행되지 않는다** — onEnable 시 이미 적재된 청크를 한 번 순회해 같은 이행 함수에 태워야 닫힌다. 이행 범위는 등록 ID/정해진 NPC 이름 집합으로 제한하라 (임의 무적 주민 오인 방지)
  【진행 2026-07-15】 스윕 구현: `sweepLoadedWorlds()` (등록 직후 호출 — 스윕이 이전을, 이벤트가 이후를) + 이행 함수 공유(`migrateOldCovenant`) + 범위를 `NPC_IDS` 등록 명패로 제한 (등록에 없는 명패는 안 건드린다). 실측: 기동 로그 `[규약이행] 기동 스윕 — 적재된 몸 0 중 옛 무적 NPC 0몸 이행` (00:29 기동 · onEnable 오류 0). 남은 것: 사람 눈 (산길 짐승·마을 NPC 타격)
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
- **단계**: P1
- **위치**: `config/npcs/cheongha_npcs.yml`
- **의존**: —
- **닫는 조건**: 입도(도착) 자리에 안내자 NPC가 서고, 다가가거나 우클릭하면 여기가 어디고 무엇부터 하면 되는지 말한다 — 문구는 등록부, 개인 사슬(B-109)의 첫 마디와 결이 맞는다
- **검증**: `config/npcs/cheongha_npcs.yml` · 사람 눈 (입도 지점 안내자)
- **닫힘**: —

실사용 (2026-07-14): "입도진의 안내자 npc가 있었으면 좋겠음, 여기는 어디인지 안내원 역할로."
경험 정본 §4(첫 1시간 안내선)의 실물 첫 조각.
※단계 P3→P1 (2026-07-24): B-178(P1)이 이 항목에 의존한다 — 「단계는 의존이 정한다」(§0.5).
backlog_audit 축이 역행(B-178(P1)→B-121(P3))으로 잡았다 (B-134 승격과 같은 길).

진행 (2026-07-24 · B-178 회차에서 사실상 완성 — 닫힘은 사람 눈 뒤): 섭구는 등록(seopgu ·
greeting_lines)에 실체까지 서 있었다 — RCON 강제 적재 후 @e 로 부두 실존 확인 (1구). 이번
회차에 안내자 **역할**이 완성: 우클릭 = 뿌리내림 과정 「마중」 정거장 (B-178 첫 손 · 대사는
등록부 그대로 — 어디인지·무엇부터인지·개인 사슬과 결 맞는 일반론). 곽진도 안내 대사 등록부
이관(「다음 벽」 — 승급 안내). 닫는 조건 전부 기계로 충족 — 사람 눈(입도 지점에서 안내자
확인) 후 닫는다.

진행 (2026-07-24 · ★실사용 "나루에 섭구가 없음" — **입도진의 사공이 몸을 얻었다**): B-121
원문("입도진의 안내자")의 자리는 나루 월드였는데 실체는 흑수나루(본토)에만 있었다 — 1~2차판
사공은 글줄뿐 ("있다고 말만 하고" — 집 지을 때와 같은 병). 수리: `antechamber.yml ferryman`
신설 (자리 [24,1]·명패·나루 쪽 대사 — 흑수나루 greeting 과 결이 다르다: 아직 못 건넌 자에게
문을 가리킨다) + `ensureFerryman` (정확히 한 몸 · NoAI·불사 — 나루는 성역, 이행 스윕도 나루는
건너뜀) + TradeListener 나루 분기 (마중 정거장은 건넌 뒤의 것이라 나루에서는 안 센다).
★함께 잡은 결함: **입장 경주** — enter() 가 build 의 onDone 을 안 쓰고 즉시 텔레포트 (잠재
결함 — 느린 조성에서 허공 착지). 수리 = 도착(arrive)을 onDone 뒤로 · 조성 중이면 대기 재시도.
【정정 2026-07-24 늦게】 「y-59 = 동굴 추락」은 오진 — flat 월드 지면이 y-61 이라 나루가 y-60에
정상으로 섰고 사용자는 **그 위에 서 있었다** (조율자가 본세계 좌표를 잘못 읽고 하늘에서
떨어뜨린 것이 진짜 사고 — 다행히 나루는 무피해 규약). 경주 수리 자체는 유효하게 남는다. 눈: audit ferryman
정의 검사 (호출부 문자열에 안 속게 정의를 본다) + selftest 뮤테이션 (71) — **54/54** · 배포 ✓.

진행 (2026-07-24 · ★4차 — 사용자 전체 흐름 설명 회차): ① **물의 세계** — flat 생성 맨 위 층을
물로 (사용자: "평지 필드를 없애고 다 물로" — 잔디 지평선이 "나머지가 전부 물" 약속을 깼다).
수평선까지 물, 갈 길은 잔교 하나. 먼 안개 실루엣 산은 다음 조형 회차 【미결】 ② 도착 글판
문장 = "여기는 나루 — 배를 기다리는 자리" (사용자 설명 그대로) ③ 길이 -30→-14 (57→41칸).
월드 재생성 1회 (백업 후). ④ ★서장 형식 사용자 확정: **기억의 회랑** — B-179 신설 (설계 회차).

진행 (2026-07-24 · ★실사용 "우클릭 하기도 전에 종 근처로 가서 바로 청하현으로" — **재방문
규약 신설**): 자동 출항(6초·부두 마당)은 첫 건넘의 의식인데 재방문자(사공에게 볼일 있는 몸)까지
태웠다 — 사공을 부두 마당에 세우자 그 결함이 드러난 것. 수리: `revisiting` 표식 (이미 건넌 몸이
/혼천 입도·나루 안 재접속으로 들면 담) — watchGate 가 그 몸은 안 태운다. **재방문의 문은 종이다**
(arrival.revisit_line 이 원래 그렇게 말하고 있었다 — "종을 울리면 언제든"). onJoin 의 옛 규약
「접속하는 순간 건네준다」도 이 개정으로 폐지 (표식+안내로 대체 — 침묵 잔류 금지). 갇힘 금지의
보증은 depart 가 아니라 종(cross). 눈: audit onJoin 검사를 새 규약으로 (표식+안내 확인) +
selftest 뮤테이션 (72) — **눈의 시험 55/55** · 배포 ✓ (기동 오류 0).

진행 (2026-07-24 밤 · ★5차 — **삼도천 화폭 구현**, tutorial_rooting.md §7 확정 시안의 조성):
①**명계 개정** (사용자 확정 문답 2건): 넋등(광량 10)은 잔교 밝기 계약(주 동선 암흑 ≤15%)과
물리적으로 양립 불가 → **구간을 갈랐다** — 서=저승(넋등 · 어둑함이 정본 · soul.dark_pct 창
40~92%) · 동=이승(부두 · TownAudit 눈금 그대로). 경계 = 나루 관문 서쪽 끝 x20 (등록부 유도).
「황혼 고정」은 마크에서 서쪽이 주홍으로 물들어 축과 반대말 → **새벽녘 고정**(fixed_time
22900 【제안】 — 동쪽만 주홍 · 환생의 방향) ②화폭: 넋등 잔교(서 촘촘 5칸·부두만 등롱 9칸) ·
창백한 고사목 12그루(남서 물가 — grain_shift 위상으로 저승 쪽에 앉힘 · 죽은 가지) · 잿빛 진흙
둔덕(grain 중간띠 — 갈대·수련잎 띠와 불가침) · 반쯤 잠긴 옛 잔교+석등 2(북 z-9 · 넋등) ·
피안화 점정(둔덕 위 양귀비 — 팩 석산화 후속) · 원경 동쪽만(실루엣 둔덕 x56~68 + 따뜻한 불빛
한 점 x58 — 안개 실루엣 미결 흡수) · 수련잎 최소(lily_hash 9→21) ③문장 저승의 결 (글판·사공
대사 — 전부 【제안】 빨간펜 대상). 난수 0 — 전부 grain/좌표 해시. 눈: audit ⑧ 광원 세기 모형
(넋등 10·등롱 15 — 세기를 뭉뚱그리면 저승을 이승만큼 밝다고 잰다) + ⑧-2 화폭 눈 9종 (축·창·
비대칭·동쪽만·시각 창·등록부 유도) + 뮤테이션 10 신설·2 재표적(㉒㉓ — 표적 잃은 뮤테이션은
묘비를 남기고 재표적) — **눈의 시험 65/65** · lint 0 · 장부 위반 0 · 빌드 ✓ · 배포 ✓ (기동
오류 0) · 월드 백업 후 제거 (backup-20260724-223147) — **첫 입장 때 삼도천 화폭으로 재생성**
(census 로그가 그 순간을 기록한다). 사람 눈 대기: 화폭 체감 (튜토리얼 완성 후 실측 회차).

진행 (2026-07-24 밤 · ★6차 — **과제 폐지 = 순수 문지방 완결**, 사용자 지시 "아직 입도진에
과제가 존재 — 옛날 코드 확인해보고 제거"): 3차 개정이 가르침 5과를 이관하고도 남겨 둔 「접속」
과제와 과제 기계 전부를 걷었다 — 진척 장부(progress·B-124 메모리뿐 설계)·감지 3종(watch
몸짓/격/경공)·명령 기입(creditCommand)·순차 공개(refreshPanels/show·one_at_a_time)·예고 변형
(_없음 판·capable/lacks)·done/all_done 연출·revisit_ledger_line. **나루는 시험하지 않는다**:
판 = 안내판 (stations[나루].panel — 옛 접속 과제 문장을 한 자도 안 바꾸고 승계 · 선행 문 눈
③-c 존속), 문 = 이름(linked), 발판 = 명령 대행. 눈 재정비: lessons 절 **부활 자체가 위반**
(audit_gate) + 잔재 눈(audit_flow — bump/creditCommand/… 코드 잔존 검출) + 표적 잃은 눈·뮤테이션
9건 묘비·재표적 (③⑫⑬⑰㉚㊴(62) 재표적 · ⑱⑲㉟㊹'(59) 폐기 묘비). ★함께 잡은 실기 결함:
**판이 같은 칸을 두 번 적어**(갈대가 물을, 고사목이 공기를 겹쳐 씀) 완결성 검증이 제 판에 속아
94% — 매 진입마다 "반쯤 섰다" 재조성할 뻔 (22:56 콘솔 ERROR가 증거). 수리 = plan() 겹침 걷기
(마지막 기록·마지막 자리 — 얹히는 것이 받침 뒤에 온다) + 눈 ⑫ + 뮤테이션 (83)(84). **눈의 시험
62/62** · 감사·lint 0 · 빌드 ✓ · 재배포 ✓.

### B-116 · HUD 텍스트가 **겹친다** — 격 두름·경공(나는발)이 서로를 덮는다
- **상태**: 열림
- **분류**: 결함
- **단계**: P3
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/SkillHud.java`
- **의존**: —
- **닫는 조건**: 액션바를 쓰는 손들이 한 줄을 두고 싸우지 않는다 — 우선순위/합성 규칙이 서고, 격 두름과 경공 표시가 동시에 읽힌다. ★Codex 검토(2026-07-15, R6~R8 조건부 승인): HudLine 은 SkillHud **내부**만 중재한다 — SkillListener 등급 사다리 · HuntListener · Sparring · Incidents · SeojangBook 대기 표시가 여전히 직접 sendActionBar 를 불러 4틱 HUD 와 덮어쓴다. 전부 공용 중재기(채널·우선순위·TTL)로 보내거나 우선순위 계약을 명시해야 닫힌다. 남은 직접 작성자를 찾는 정적 감사도 추가하라
- **검증**: `server-mvt/src/main/java/com/honcheon/mvt/SkillHud.java` · 실기동 (격 두름 + 나는발 동시)
- **닫힘**: —  【진행 2026-07-15】 입도진 부분 수리 (사용자 결정): 입도진의 몸에는 경지가 없다 — statusBar 가 그 세계에서 격 두름·나는발(rider) 조각을 그리지 않는다 (SkillHud.java 한 분기). 과제 안내와의 겹침 실측 2건 해소 — 사람 눈 확인 대기. 전역 소유권(직접 sendActionBar 잔존 5곳)은 그대로 이 항목의 몫

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
- **상태**: 닫힘
- **분류**: 빚
- **단계**: P4
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/TerrainAudit.java`
- **의존**: —
- **닫는 조건**: 표본이 기반 구조층(y<-54 용암 바다)과 의도된 액체를 제외하거나 따로 센다 — cheese 봉인 후의 새 월드에서 ⑥ 이 통과한다
- **검증**: 새 월드 `/혼천 환경검수` ⑥ · `server-mvt/src/main/java/com/honcheon/mvt/TerrainAudit.java`
- **닫힘**: 2026-07-14 밤 — 실측 `혼천 환경검수` ⑥ 0.00% · 눈을 시험하는 눈
  `tools/TerrainAuditSelfTest.java` 102/102. 눈의 세 거짓말(액체·판굴·측정 대역)을 갈랐고
  (커밋 74f317e·54ede5f), 남은 3.86%는 눈이 아니라 세계의 실결함(묻힌 나무)로 판명 —
  조성기 채움 계약(fill_below_raised)과 치유 명령이 걷었다 (커밋 0d35129·485082a).

★진행 7차 — **진상 완결** (2026-07-14 밤, RCON 기둥 단면 실측): (-61,359) 단면 =
옛 지면(y79 잔디) → 공기 2칸(81-82) → 잎 4층(83-86) → **조성기가 올린 흙·잔디(87-89)**.
**조성기가 서 있는 나무 위로 땅을 올려 나무를 산 채로 묻었다** — 수관과 옛 지면 사이
공기가 「지하 공동」의 정체다. 6차(나무를 땅으로 오독)는 부분 진실: 이 기둥들의 지붕은
진짜 흙이라 눈은 정직했고, 세계에 실결함이 있다(밭을 파면 잎이 나온다 · 묻힌 잎은
서서히 삭아 공동이 자란다). 수리 방향: ① TerrainForge 계약 "올린 땅 밑은 채운다"
② 현 세계 치유 스윕(지면 밑 공기·잎·통나무 → 채움, 원장 판굴 제외) ③ 치유 후 ⑥ 실측.

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
- **상태**: 닫힘
- **분류**: 결함
- **단계**: P3
- **위치**: `tools/world_purity_audit.py` (no_caves 생성부)
- **의존**: —
- **닫는 조건**: 새로 생성한 월드에서 환경검수 ⑥(지하 공동)이 통과한다 — density_function 덮개에 `caves/cheese`(와 필요 시 underground 관련 잔여)가 포함된다. ★수정 후 스크래치 월드에서 레지스트리 로딩+실측 검증 필수 (noise 수술은 서버 사망 위험 — purity 규약: 필드 하나만)
- **검증**: `python3 tools/world_purity_audit.py` · 새 월드 환경검수 ⑥
- **닫힘**: 2026-07-14 밤 — 실측 `혼천 환경검수` ⑥ **공동(공기) 0칸 (0.00%)** (수리 전 3.86%).
  7차 수사의 진상: 동굴이 아니라 **조성기가 산 채로 묻은 나무**(공기+잎+통나무)였다. 봉인
  (density 5종 id 참조 · cave_cheese=0 → cheese 최소 +0.27 · 카버 probability 0)은 처음부터
  유효했다 — `python3 tools/world_purity_audit.py` 통과 + 부호 지뢰 문서화(커밋 48511b9).
  치유: `/혼천 지하정리` 채움 48,519칸 (공기 41,968 · 잎 5,089 · 통나무 1,299 · 초목 163).  ※수리 완료·실측 대기 (2026-07-14, R4 을³): cheese 는 noise_settings 인라인이나 `noise/cave_cheese` amplitudes 전 0.0 잠재움으로 봉인 (noise_settings 무수술 — 양수=돌 부호 근거). no_caves 가 등록제로 편입(`config/world_purity.yml` no_caves 절 신설 · `cave_contributors()` 공용 눈 — 빌드와 감사가 같은 함수, 버전 갈이 감지 · 감사 ⑥ 신설 + 사보타주 5종 확인 · 시험기 태그 구멍 봉합으로 카버가 진짜 한 필드 편집으로 복귀). 로딩 시험 102파일 PASS. ★트랙의 의심: 정적 분석상 기존 팩으로도 cheese 는 죽었어야 한다 — 3.8% 는 물(검수가 물도 공동으로 셈 + 시드 원점 수역)일 수 있다. 닫힘은 **B-114(눈 보정) 후 실측** — 재생성 실측(2026-07-14)에서 봉인 전후 3.81→3.84% 로 무변동: 3.8% 는 동굴이 아니라 구조층이다. 시드는 시드검사 최고점(20260710=90점)으로 유지 확정

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
- **상태**: 진행
- **분류**: 미완
- **단계**: P1
- **위치**: `server-bot/src/main/java/com/honcheon/bot/WorldClockEngine.java`
- **의존**: —
- **닫는 조건**: story_summary 의 단계(마교 암류→삼파전)가 등록부로 서고, 아무도 사건을 안 일으키면 세계가 그 시계로 진행하며, 플레이어 행동이 속도를 가감한다
- **검증**: `python3 tools/world_clock_audit.py` · `python3 tools/world_clock_audit_selftest.py`
- **닫힘**: —  ※설계 완료 (2026-07-14, R4 정³): `docs/design/world_clock.md` + `config/world_clock.yml` 초안 (unwired 표식 — 읽는 코드가 없음을 파일이 말한다). 닫힘은 구현(WorldClockEngine·advanceWorld 편입·승인 명령)이 서고 세계가 실제로 흐를 때
  【진행 2026-07-15, 웨이브-1 트랙 D】 구현이 섰다: `WorldClockEngine`(등록부 자기검산 · tick — 박 발화→전조→막 진입 · approve) + advanceWorld 편입 + `/막개전` 최상위 명령(B-020 탈출구 ①) + 눈 `tools/world_clock_audit.py`(위반 0)·selftest(변이 14/14 — 조율자 재실행 확인) · 봇 컴파일 0. 남은 것: 봇 재기동(★사용자 — 토큰) 후 실기동 검증(`/혼천 정산` 연타 → 막전환·전조 → `/막개전` → sampajeon 진입). §8④ 박자보정 입력은 장 엔진(P2) 선행이라 읽기만 배선(기본 0). 트랙 D가 설계 밖에서 정한 5건(박 소문 정확도=간접_전문 · 명분 발원=직접_목격 · 멱등 키 2종 · 전조 유지=재파종 · 관리자 한 줄=콘솔+원장)은 검토·승인 대상

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


### B-128 · ★ 일괄 치환이 **장부를 독살했다** — 닫힘 근거 45곳 복제 · 상태 20건 전복
- **상태**: 닫힘
- **분류**: 결함
- **단계**: P1
- **위치**: `docs/BACKLOG.md`
- **의존**: —
- **닫는 조건**: 오염 전부 복원 (가짜 닫힘은 열림으로 · 남의 증거는 —로) + 같은 병을 잡는 눈이 선다
- **검증**: `python3 tools/backlog_audit_selftest.py` (③-b 복제 눈 포함 12상처 — ★ 여기에 backlog_audit --run 을 적으면 감사가 저 자신을 되부른다. 재귀는 시간 초과로 죽는다)
- **닫힘**: 2026-07-14 밤. 원인: 커밋 8a50548 (B-015 닫기)의 일괄 치환이 `- **닫힘**: —` 45곳을 **B-015 의 닫힘 문구로 전부 덮고**, 상태 20건을 열림→닫힘으로 뒤집었다. 장부가 6시간 동안 가짜 닫힘 18건을 품었고 감사는 조용했다 (증거의 꼴만 봤다). 발견 경로: B-019 닫힘 문구가 허수아비가 아니라 패링을 말하고 있었다. 복원: 열림·보류·진행 25건의 닫힘 필드 → `—` · 가짜 닫힘 18건 (B-023·024·029·030·031·032·033·034·036·037·038·039·040·041·043·045·046·084) → 8a50548 직전 상태(전부 열림)로. 눈 신설: backlog_audit ③-b (닫힘 근거 복제 = 위반 — 한 증거는 한 항목의 것이다) + selftest ⑫ (복제 심기). 낡은 표본도 수리: ★⑪ 이 lint_config(그새 완치)에 기대다 죽어 있었다 → `tools/selftest_fixtures/always_barks.py` (설계상 언제나 짖는 표본 · 종료 코드 0 함정 재현). 실측: backlog_audit --run 위반 0건 (열림 49 · 닫힘 40 — 진실 복원) · selftest 12/12

계율: **일괄 치환을 장부에 대지 마라** — 닫힘 필드는 그 항목의 것이다. 그리고
**눈의 시험이 남의 병(고쳐질 감사)에 기대면 병이 나을 때 시험도 같이 죽는다** — 표본은 설계상 불변이어야 한다.
### B-129 · 지하정리 안전핀의 문턱이 **코드 발명**이다 — `cy<0` · 괴리 40칸
- **상태**: 열림
- **분류**: 빚
- **단계**: P4
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/MvtCommand.java`
- **의존**: —
- **닫는 조건**: 안전핀 문턱(`cy < 0` · `abs(surface−cy) < 40`)이 등록부(`config/terrain.yml` 또는 세계 경계 등록값)에서 유도되고, 감사가 같은 값을 검증한다. fail-closed 방어 자체는 유지한다. 추가 보강(Codex 조언 3): 중앙 한 열만이 아니라 대상 영역의 대표 경계점도 같은 범위 검사에 태운다
- **검증**: `python3 tools/backlog_audit.py` · `server-mvt/src/main/java/com/honcheon/mvt/MvtCommand.java`
- **닫힘**: —

기원: Codex R6~R8 검토 (2026-07-15, `485082a` 통과(조언)). 40 은 `TerrainAudit.surfaceY` 의
고정 탐색 폭과 우연히 같을 뿐 설정 계약이 아니고, `cy<0` 도 암묵 불변식이다.
사고를 막는 핀은 옳다 — 그 핀의 치수가 등록부에 살아야 한다 (§2.1 등록제).

### B-130 · `bridge_audit --no-backup` 이 **거짓 경고**를 낸다 — seojang_choice 발신자를 못 본다
- **상태**: 열림
- **분류**: 결함
- **단계**: P4
- **위치**: `tools/bridge_audit.py`
- **의존**: —
- **닫는 조건**: 감사의 소스 탐색 범위가 `MvtCommand.java` 의 실제 발신 지점을 보고, seojang_choice 경고가 사라진다 (거짓 경고는 진짜 경고를 안 읽게 만든다)
- **검증**: `python3 tools/bridge_audit.py --no-backup` · `python3 tools/bridge_audit_selftest.py`
- **닫힘**: —

기원: Codex R6~R8 검토 (2026-07-15, 독립 검증 절). 실제 호출은 있는데 눈이 좁아서 없다고 말한다.
### B-131 · 입도진 관문 글판 **두 장이 다 보인다** — 숨김이 적재보다 먼저 달렸다
- **상태**: 닫힘
- **분류**: 결함
- **단계**: P3
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/Antechamber.java`
- **의존**: —
- **닫는 조건**: 격·경공 관문에서 본문 판과 예고 판이 **한 장만** 보인다 — 진입·재접속·재기동 후 전부. (사람 눈)
- **검증**: 사람 눈 (입도진 격·경공 관문 앞) · `server-mvt/src/main/java/com/honcheon/mvt/Antechamber.java`
- **닫힘**: 2026-07-16 · 인게임 사람 눈 (사용자 재접속 — "다 잘 보입니다", 재수리 커밋 382464b
  배포 00:07 기동 오류 0). ↓아래는 1차 수리(2026-07-15)의 기록 — 그 뒤 재발·재수리는 본문 참조.
  1차: onPanelsLoad(EntitiesLoadEvent) — 글판이 실리는 순간 명부 재구축 + 그 세계 사람들에게 가림 재적용 (멱등). 사람 눈 (사용자 재접속): 겹침 소멸 — "첫 글판과 손-한획 글판은 보입니다" = 설계 그대로 (지나온 맞이 + 현재 손, 한 번에 하나만). 부작용처럼 보인 "글판이 사라졌다"는 회귀가 아니라 **여태 조용히 고장 나 있던 순차 공개가 처음으로 작동한 것** — 세계에는 9장 전부 서 있음을 기계가 쟀다 (엔티티 계수 9 · 조성 로그 9/9)

★★ 재발 (2026-07-16, 사용자 실측 · 커밋 382464b): 겹침이 돌아왔다. 진상 = **onPanelsLoad 의
사각지대.** 그 수리는 `EntitiesLoadEvent`(청크 **적재**)만 잡는데, 나루가 완결 96%<97% 문턱에
걸려 **런타임에 반복 재건축**(로그 23:19·23:54 "처음부터 다시 짓는다")되고, 재건축 경로의
`spawnPanels` 가 새 글판(기본값 「보임」)을 `spawn()` 으로 뿌린다 — spawn 은 EntitiesLoadEvent 를
안 쏘므로 가림이 재적용 안 돼 present 플레이어가 두 장을 겹쳐 봤다. 재수리: `spawnPanels` 끝에서
`w.getPlayers()` 에 `refreshPanels` (멱등 — B-131 수리와 같은 정신, spawn 갈래를 덮는다).
방아쇠였던 완결 churn 자체는 B-158 로 갈랐다.

★재수리 확인 닫힘 (2026-07-16, 커밋 382464b · 배포 00:07 기동 오류 0): 사람 눈 (사용자
재접속) — **"다 잘 보입니다"** = 겹침 소멸, 설계대로 표시. spawn 갈래가 덮여 재건축(B-158)이
돌아도 겹치지 않는다. churn(B-158)은 별개로 열려 있다.

실측 (2026-07-15, 사용자): 과제 안내 TextDisplay 글이 겹쳐 보인다 — 격·경공 관문.
기전: 관문 글판은 한 자리에 두 장(how + unavailable)이고 refreshPanels 가 사람마다 한 장을
가리는데, 그 가림은 엔티티가 실려 있어야 걸린다. 진입 직후의 refresh 는 엔티티 청크 비동기
적재보다 빠를 수 있고 show() 는 못 찾으면 조용히 지나갔다 — **기본값이 「보임」이라 침묵의
값이 곧 겹침이었다.** 1차 오진: 액션바(격 두름·나는발 조각)를 먼저 고쳤다 — 그것도 실측 겹침이었으나
글판의 겹침은 별개 기전이었다. 수리: onPanelsLoad(EntitiesLoadEvent) — 실리는 순간 명부 재구축
+ 그 세계 사람들에게 가림 재적용 (멱등 · 재기동 뒤의 빈 명부도 되살린다).
### B-132 · 허수아비가 **대립한다** — 계기가 주사위를 굴려 배우는 손을 조용히 기각했다
- **상태**: 열림
- **분류**: 결함
- **단계**: P2
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/SkillListener.java`
- **의존**: —
- **닫는 조건**: 허수아비 타격은 대립 판정 없이 전부 실린다 — 손 과제가 3타에 닫히고, 명패 누적이 타수와 같다 (사람 눈)
- **검증**: 사람 눈 (입도진 허수아비 3타 → "손이 풀렸다" + 다음 글판) · `server-mvt/src/main/java/com/honcheon/mvt/SkillListener.java`
- **닫힘**: 2026-07-15. 수리: basicMelee 첫머리 Antechamber.dummy(target) 우회 — 계기와는 대립하지 않는다 (커밋 6283d14). 사람 눈 (사용자): "과제 해결했고 다음 관문들도 잘 나타납니다"

실측 (2026-07-15, 사용자 + 명패 검시): 여러 타 중 **누적 1** ("이류 몸 · 1합"). B-005 가 기본
초식에 들인 대립 판정(basicJudged)이 허수아비에게도 굴러 대부분의 타를 **조용히** 기각했다 —
빗나감 표시도 없이. 등록부는 허수아비를 계기로 못박았다 ("안 죽고, 안 움직이고, 맞은 것을
말한다"). 수리: basicMelee 첫머리에서 Antechamber.dummy(target) 면 획만 그리고 판정 없이
바닐라 피해를 그대로 실는다 — 나루의 눈(onDamage)이 센다.
### B-133 · 나루의 허기가 **달림 과제를 막는다** — 바닥(6)과 달리기 문턱(>6)이 같은 자리다
- **상태**: 열림
- **분류**: 결함
- **단계**: P3
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/Antechamber.java`
- **의존**: —
- **닫는 조건**: 나루에서 허기가 줄지 않고(먹는 것은 된다), 들어오는 몸은 배부르다 — 회피(달림) 과제가 허기에 안 막힌다 (사람 눈)
- **검증**: 사람 눈 (입도진 달림 과제) · `server-mvt/src/main/java/com/honcheon/mvt/Antechamber.java`
- **닫힘**: —

실측 (2026-07-15, 사용자): "배가 고프면 달림 과제를 깰 수가 없네요." 기전: 전역 허기 규칙
(world_purity.yml hunger.floor: 6)은 굶어 죽지 않게 하는 바닥인데, 바닐라 달리기는 허기 6
**초과**라야 된다 — 나루의 새 몸이 바닥까지 닳으면 회피 과제가 정확히 그 바닥에 막힌다.
수리: onHunger(줄어드는 변화만 취소 — 먹는 것은 둔다) + enter()에서 배 채움(바닥까지 닳아
들어온 몸도 달린다). "죽지 않는다"(onPlayerDamage)와 같은 결 — 배우는 자리의 계기는 몸이
아니라 손이다.

### B-134 · 수련이 **숙제 같다** — 매일의 상한·배분이 리듬이 아니라 의무로 읽힌다
- **상태**: 보류
- **분류**: 결정
- **단계**: P1
- **위치**: `config/cultivation.yml:159`
- **의존**: —
- **닫는 조건**: ★ 사람이 정해야 한다 — 수련의 형태 (행위 적립인가 · 상한을 두는가 · 숙제의 디제시스인가). 결정 후 설계 문서 + 등록부 개정
- **검증**: `docs/BACKLOG.md` · 결정 후 신설되는 설계 문서
- **닫힘**: —

※단계 P2→P1 (2026-07-15): B-135(P1)가 이 항목에 의존한다 — 「단계는 의존이 정한다」(§0.5).
backlog_audit 축 ⑥이 역행(B-135(P1)→B-134(P2))으로 잡았다.

사용자 원문 (2026-07-15): "수련 시스템을 바꾸고 싶습니다. 매일 해야 하는 숙제처럼 느껴져요."
현행: 하루 5구간 상한(time.yml) · 발판/명령("혼천 수련 외공 2")으로 축(외공·내공·초식·신법·심안)에
배분 (training.yml curriculum · cultivation.yml 성장_축). 매일 같은 발판을 밟는 반복이 숙제의
정체다. ★방향 결정 (2026-07-15, 사용자): **행위가 곧 수련 + 디제시스화(스승·일과)**.
설계 초안: `docs/design/training_by_doing.md` — 행위 감지·스승 효율·일과=의뢰 접합.
★2026-07-15 재편 (B-135 헌법 개정): 행위 적립은 **기술·화후 전용**으로 한정 — 능력치는
v3 레벨 포인트의 것 (병행+영역 분리, 사용자 결정). 하루 5구간 상한은 폐기 방향.
미결(심안의 행위·문턱 N·철거 범위·나루의 가르침)은 v3 미결 목록에 병합 — cultivation_v3_levels.md §5.
### B-135 · ★★ 헌법 개정 — **경험치·레벨·스탯 포인트를 정식 도입한다** (cultivation v3)
- **상태**: 진행
- **분류**: 결정
- **단계**: P1
- **위치**: `config/cultivation.yml:1`
- **의존**: B-134
- **닫는 조건**: v3 설계 문서가 서고(레벨↔경지 관계 · 포인트의 대상 · B-134와의 관계 · 상한 정리), 사용자가 승인하고, 등록부 개정 + 구현 + 눈이 선다
- **검증**: 결정 후 신설되는 설계 문서 · `python3 tools/lint_config.py`
- **닫힘**: —

설계 문서: `docs/design/cultivation_v3_levels.md` — 네 기둥 확정 (혼합 승급 · 능력치 7종 ·
병행+영역 분리 · 시간 헌법 폐기). 미결 수치 7건(§5)이 닫혀야 구현.

진행 (2026-07-24 · 단계 3 — 파생치 √원장): ★단계 1~2 의 **표류 결함 둘을 잡고 고쳤다**:
① backfill 이 정수 `능력치`를 제곱해 **실수 화후의 소수부를 버렸다** (§8.9 ③ "실수 x" 위반)
② 원장이 한 번 서면 얼어붙어 **수련(화후 증가)이 판정에 안 실렸다** (단계 2 뒤 라이브 표류).
수리 = backfill 을 **보장+화해(raise-only · 원장 = max(원장, 화후²))** 로 — 판정 보존 증명
(floor(√화후²)=floor(화후)=능력치) · 단계 4(원장 독립·화후 동결) 뒤 자연 무연산. settle 경로에도
화해 배선. mvtSheet attrs = **√원장**(GrowthV3.realValue — §8.9 ⑩ 파생치 계약, MVT 의 내력
풀·이속·내구·경공은 이미 실수치 독자라 다리만 갈면 끝). 증거: `growth_v3_backfill_selftest.py`
7눈 통과 (화후 소수부·화해·raise-only + 동결 오배선 감지) · `judgment_scale_harness.py` 위반 0 ·
봇 빌드 ✓. 봇 재기동 완료 (2026-07-24 — 조율자가 토큰 확보 절차로 대행 · JDA 로그인·PG 확인).

진행 (2026-07-24 · 단계 4 결정 회차 — **§5 미결 ③·⑥ 닫힘**, 사용자 확정 4건):
① 전투 XP = **클래식 고정값** — 몹 레벨 × 등급 계수(잡졸 1.0·정예 1.5·두목 3.0) · 배수·감쇠·상한
없음 · 미등록 몹 레벨 = 상당 경지 자격 레벨 자동 유도 · 후반 압축 수용(실측 재보정) ② 무명의뢰
XP 80/50/40/30 ③ **사선 보너스 폐지** (마크만 잔존) ④ **내공 축 A안 통일** — 내력 풀 = √원장[내공],
축기·심법은 개화 자격만. 정본: `config/cultivation.yml levels.xp_sources`·`naegong_unified` +
`docs/design/xp_sources_v3.md` §0. lint 0.

진행 (2026-07-24 · 단계 4 첫 배선 — **XP 파이프**): 처치 XP (HuntListener — 몹 레벨×잡졸 계수 ·
등록 NPC 는 npcs/*.yml `level` 우선, 없으면 상당 경지 자격 레벨 · REALM_BY_MOB 들짐승=삼류·맹수=일류)
→ pendXp → 다리 `xp` 칸 (cultivation_logged) → 봇 `GrowthV3.grantXp` (need = base×growth^(L-1) ·
레벨업·3포인트 적립) + 무명의뢰 XP (Bridge.populaceQuest — 성공만 · quests 표). 눈:
`growth_v3_backfill_selftest.py` 단계 4 3눈 (잡졸2=Lv2 · 240XP 연속 레벨업 보존 검산 · 후반 무거움) —
10/10 통과 · 양 모듈 빌드 ✓. ★남은 단계 4: 포인트 **배분** 손(명령/버튼 · 캡 c² · 은행) → 승급
게이트(자격 레벨 N_k 편입) → v2 수련→능력치 동결 → 내력 풀 √원장[내공] 교체(A안) →
정예·두목 등급 필드 등록. 몹 처치 XP 커버리지: HuntListener 경로(짐승+등록 NPC 개체) — 명패
없는 바닐라 잡몹은 표 밖(0 XP).

진행 (2026-07-24 · 단계 4 — **포인트 배분 손**): 시트([내 자리]·`/혼천 정보`)에 [포인트 배분]
버튼 → 축 7버튼(+1) 패널 (`al:` 네임스페이스 — 소유자 검사·캡 도달 축은 비활성 + 이유 표기).
`GrowthV3.allocate` — 포인트 1점 = 원장 1눈금 · **캡 c²**(`cultivation.yml levels.
raw_attribute_cap_by_realm` — Rules.rawCapByRealm 신설, 경지가 표에 없으면 말하고 멈춤) ·
거절분 **은행 무기한**(§8.9 ⑨ — 화후 소수부의 캡 밑 1 미만 잔여도 반 눈금 배분 없이 은행).
시트 표기 = **원장만** + 레벨·경험·미사용포인트 (§8.9 ⑪ — 판정치·성별 보정 비표기, levels off
면 옛 능력치 표기 폴백). 영속 = updateCharacter → 다음 Bridge 스냅샷이 √원장을 마크로 내림
(단계 3 배선 재사용 — 추가 배선 0). 눈: `growth_v3_backfill_selftest.py` 배분 3눈(+1·캡 거절
은행·캡 채움 9→16=7점) + 캡 무시 오배선 감지 — **14/14 통과** · 봇 빌드 ✓. ★배포 대기: 봇
재기동 필요 (jar 재빌드됨 — 도는 봇은 재기동 대상).

진행 (2026-07-24 · 단계 4 — **이중 관문·내공 A안·등급 필드**, 같은 회차):
① 승급 이중 관문 — promoteIfDue 세 관문에 자격 레벨 N_k AND 편입 ("레벨은 자격, 사건이 문" ·
Rules.qualifyingLevel ← `levels.qualifying_level` · levels off 면 v2 그대로). ② 내공 A안 배선 —
`naegong_unified` 선언이 실제 배선됨: poolNaegong = √원장[내공] (개화 후), 소비처 3곳(시트 내공
필드·운기 회복·mvtSheet naegong → 마크의 풀·조식·전투 회복 전부). 축기·심법은 개화 게이트·화후
라벨로만 잔존. ③ 정예·두목 등급 — `npcs/*.yml xp_grade` 필드 배선 (Npc.xpGrade · 미등록 = 잡졸
계수 killXp 폴백). ★개체 지정 닫힘 (2026-07-24 같은 날 · 사용자 확정 — 유도안 전체 승인):
호랑이·반달곰=**정예**(맹수 · 일류 65×1.5≈98 XP) · 갈호=**두목**(도적 두목 · 이류 40×3=120) ·
백영묘=**두목**(영물 · 절정 80×3=240). 산늑대·멧돼지·도적 졸개는 잡졸 그대로. 라이브 반영 =
config 동기화 + `/혼천 모션 재적재` (24ms 무중단 — SkillEngine 재구축이 npcs 재파싱).

진행 (2026-07-24 · ★단계 4 **마감** — v2 수련→능력치 동결 점화): 선행 조건(배분 라이브 확인)
충족 — 사용자가 XP 파이프 끝단(마크 처치→디스코드 시트)을 인게임 실측 확인. `levels.
training_attr_frozen: true` 신설·배선 (settle 의 attr_days 적용 게이트 — 행위는 기술·화후_원장·
축기만 밀고 능력치는 레벨 포인트의 것, 헌법 v3 병행+영역 분리). 동결로 화후² ≤ 원장이 유지되어
backfill 화해가 설계대로 자연 무연산이 됐다. 눈: check_freeze 2눈(동결 무연산·스위치 off=v2) +
동결 누수 오배선 감지 — 26/26 통과. **이로써 단계 4 전 항목이 닫혔다** — 남은 것은 단계 5 UI
(레벨업 연출·승급 조건 표시·칭호 B-176)와 XP 계수 실측 재보정(계약된 유보). 눈: selftest 이중관문 3+내공 2 + OR/√생략 오배선 — **22/22** · lint 5-d(미등록 xp_grade
거부) + lint_selftest 케이스 — 통과 · 양 모듈 빌드 ✓. 남은 단계 4: v2 수련→능력치 동결(★배분
라이브 확인 뒤 — 이것으로 화해가 자연 무연산이 된다) · 단계 5 UI(레벨업 연출·승급 조건 표시).

진행 (2026-07-24 · ★★**B안 개정 — 원장 캡 폐지·판정 캡 분리**, 사용자 확정 · 정본 §8.10):
계기 = 라이브 실측 "캡 걸려서 못 찍는 스탯들이 존재" (범인 캡 9 에 생성 3배분 5축 즉시 도달).
3안 시안 → 사용자 B안 확정: **원장 무캡**(배분 언제나 됨 · 파생 √원장 무캡 — 몸은 계속 자란다) ·
**판정치만 min(floor√원장, 경지 판정 캡)** (정본 player_creation attribute_cap_by_realm) · 천장
너머 원장은 **승급 순간 판정으로 터진다**. 배선: 봇 Rules.judgmentCap + genderStat(realm 편입 —
호출부 9곳) · GrowthV3.allocate 캡 제거 · 배분 패널 천장 표기 / 마크 Growth.attackBonus·
defenseScore·bestStance realm 편입 + judgmentAttrOf(v2 방어체력·크리축) — 파생 독자(내구·이속·
내력 풀)는 캡 문 밖. 묘비: raw_attribute_cap_by_realm·point_bank(B-152 종결)·attackBonus 2항.

진행 (2026-07-24 · ★단계 5 — UI, 사용자 결정 6건 기반): 결정 = 정본 feel §1 준수(**레벨업 조용·
승급이 터진다** — HANDOFF 요약과 어긋나던 것을 정본으로 정리) · 승급 Title=새 경지 이름 ·
부제=cultivation_stages **description 인용** · 칭호=경지 이름 그대로(범인 없음) · 획득 전반
「획득 » …」 통일 · XP 는 액션바. 배선: ① **레벨 거울** — mvtSheet 에 level/xp/xp_need/points
신설 → WorldBridge.Sheet·PlayerLedger 편입 (마크가 처음으로 제 레벨을 안다) ② 레벨업 조용 알림
(syncSheet 델타 감지 → flash · 첫 스냅숏은 도착이지 델타가 아니다) ③ **바닐라 XP바 = 경험**
(setLevel/setExp — XpEconomyGuard 절연 전제 이행) ④ 승급 연출 promotionCeremony (Title+
부제 인용+버스트 end_rod+소리 — `skill_motion.yml promotion_fx` 신설 · 수치 【제안】 실측 대기)
⑤ **시트 「승급」 필드** — promotionGates 리팩터로 **표시=판정 한 해석기** (자동 경지 ✅/❌
현재/필요 · 벽 경지는 등록부 trigger·요건 인용+현재값 병기 — 침묵 boolean 소멸) ⑥ 몹 명패
레벨 칸 [N] (HitFeedback 예약 지점 — mobLevel 같은 해석기) ⑦ 획득 토스트 (줍기 「획득 »
'이름' +N개」 — 이름 있는 물건만 · XP 「획득 » 경험 +N」). 눈: selftest 단계 5 3눈 신설
(한 해석기 32조합 모순 0 · 부족 관문 표기 · 표시 갈라짐 오배선 감지) — **29/29** · lint 0 ·
motion_audit 위반 0 · 양 모듈 빌드 ✓ · 라이브 배포 (MVT 13:37 기동 오류 0 · 봇 JDA 로그인 ✓ ·
스냅샷에 level 실림 실측 — Lv11/30포인트). ★남은 것: 인게임 실측 (레벨업 flash·XP바·승급 연출
체감 · `/혼천 소환` 경로) + promotion_fx 수치 튜닝 (사용자 체감).
눈: selftest B안 4눈 + 판정 캡 생략 오배선 — 23/23 · lint 0 · 양 모듈 빌드 ✓.

진행 (2026-07-24 · 단계 4 — **디스코드 사냥 XP 구멍 수리**, 사용자 실측 "사냥했는데 경험이 안 올라"):
첫 배선의 커버리지 구멍 — 처치 XP 가 마크 HuntListener 경로만 덮고 **봇 자체 `/혼천 사냥`은 빠졌었다**.
수리: Beast 에 상당 경지 필드(여우·늑대=삼류 들짐승 · 곰=일류 맹수 — npc_combat.yml beasts.ranks ·
마크 REALM_BY_MOB 와 같은 규약) + Rules.xpGradeCoef(grade_coefficient 독자) → onHuntChoice 에서
**잡은 것(pelt)만** XP = 자격 레벨 × 잡졸 계수 (여우·늑대 10 · 곰 65) · 승급 판정 앞에 굴려 새
레벨이 이중 관문에 실린다. 레벨업 시 embed 에 포인트·[포인트 배분] 안내. ★닫힘 (2026-07-24 같은 날 · 사용자 확정): **게시판 의뢰 XP 등재** —
`levels.xp_sources.board_quests` 등급별 (잔심부름 20 · 조사_채집 30 · 호위_소탕 50 ·
표행_현상금 80 · 세력_전속 120. 유도: 20=need(1) · 30/50/80=무명의뢰 눈금 동급 · 120만 새 수 —
전부 사용자 승인). onQuestPerform 성공만 · 승급 판정 앞 · 레벨업 안내 표기. 눈: lint 5-e
(사다리 등급 ↔ XP 표 양방향 — 빠지면 조용한 0 XP 를 잡는다) + lint_selftest 케이스.
★사용자 확정 (2026-07-15): "rpg 서버이긴 하니까 스텟처럼 직접 찍어서 올리는 것도 필요 —
몬스터나 퀘스트를 깨고 경험을 얻어, 레벨이 오르면 어느 방향으로 올릴지 같은 느낌." 조율자가
현행 헌법 조항(cultivation.yml v2: "레벨은 없다, 경지가 레벨이다" · "경험치·레벨 없음" ·
"사냥터가 빌드를 대신 정해주지 않는다")과의 충돌을 보였고, 구간권(헌법 내) 대안을 권했으나
**사용자가 개정을 택했다.** 팩 게이트 폐지(2026-07-13)와 같은 격 — 구판 조항은 이 항목이
닫힐 때 묘비 주석으로 남긴다. 구조 미결 4건(레벨↔경지 · 포인트 대상 · B-134 관계 · 상한)은
조율자가 물었다 — 답이 설계 문서를 연다.
### B-136 · 강화·인챈트·수리의 **상점 이관** — 경험은 성장의 것, 돈은 물건의 것
- **상태**: 열림
- **분류**: 미완
- **단계**: P2
- **위치**: `docs/design/cultivation_v3_levels.md`
- **의존**: B-135
- **닫는 조건**: 모루·인챈트대의 레벨 소모가 걷히고, 강화·인챈트·수리가 상점(강화소·무기점)에서 돈으로 돈다. 가격은 등록부 (수리는 economy.yml 수리_범철·수리_정련 기존 값 — 강화·인챈트 가격표는 사용자 승인). 몹 경험 오브는 흡수된다
- **검증**: `python3 tools/lint_config.py` · 사람 눈 (상점 강화 · 모루 레벨 무소모)
- **닫힘**: —

★사용자 확정 (2026-07-15): "레벨 소비처는 제거. 모든 것은 상점(강화소나 무기점 등)에서 —
경험치가 아닌 돈으로 강화와 인챈트 및 수리." v3 바닐라 XP바 채택(§4-b)의 절연 조치를 겸한다.
WeaponShop 이 이미 있다 — 강화소가 새 상점인지 무기점의 기능인지는 설계에서 정한다.
### B-137 · ★★ 능력치 저울 재설계 — 레벨당 3포인트 × Lv100 은 옛 눈금을 못 쓴다
- **상태**: 닫힘
- **분류**: 결정
- **단계**: P1
- **위치**: `config/judgment.yml:10`
- **의존**: B-135
- **닫는 조건**: 새 능력치 눈금이 선다 — ≈300포인트(최종장 기준)를 담고, 판정 공식(실행력 = 능력치+기술+보정+2d6 · 저항 = NPC능력치+…+7)이 새 눈금으로 재환산되며, NPC 수치표·장비 보정·성별 ±1(B-056)까지 연쇄 환산이 끝난다. 수치 전 항목 사용자 승인
- **검증**: `python3 tools/lint_config.py` · 판정 재현 하네스 (신설 — 옛/새 눈금 동등 시나리오 대조)
- **닫힘**: 2026-07-23 — 설계·수치 전 12항목 사용자 승인 (`docs/design/attribute_scale_v3.md` §8.9 · 2026-07-15) + `python3 tools/judgment_scale_harness.py` 재실행 위반 0건 (옛/새 눈금 동등). 저울 결정은 닫힘 — 이 눈금의 **구현**은 B-135 소관 (단계 2에서 판정치=floor√원장 배선됨)

★사용자 확정 (2026-07-15): "매 레벨 3포인트 기준 (설정 능력치를 다 변화시켜서 밸런스
재설계 필요)." 옛 눈금은 ±1 이 유의미한 TRPG 저울 — 2d6 의 분산이 판정을 지배한다.
300포인트 세계에서는 주사위·능력치·기술의 비중을 처음부터 다시 정해야 한다 (예: 능력치
수백 눈금 + 주사위 폭 확대, 또는 능력치→보정 환산층). 방식은 설계에서 — 지어내지 않는다.

★★안 확정 (2026-07-15, 사용자 — 시안 3안 중): **C안 · 제곱 환산층** — 판정치 = floor(√원장),
옛 값 X ↔ 원장 X². 고수일수록 한 끗이 비싸지는 곡선 내장. 조율자가 고지한 유의점: XP 곡선
(후반 무겁게)과 겹치는 **이중 감속** — 페이싱 재보정(xp_pacing.py — 원래 실측 유보)과 함께
다룬다. 다음: C안 구체화 설계 (판정 공식 재환산 · NPC/장비/성별 연쇄 · 경지 캡 환산 ·
옛/새 동등 대조 하네스) → 수치 사용자 승인 → B-135 구현 개방.

진행 (2026-07-15 밤, 웨이브-2 트랙 B — 커밋 0617f79): C안 구체화가 섰다 —
`attribute_scale_v3.md` §8 (환산은 판정이 읽는 순간 1곳·판정치 비저장 · NPC/장비 무환산 실측 ·
성별 ±1 후치 · 경지 캡 제곱표 9단 · 이중 감속 분해: 주인은 XP층 ~200× — 제곱층 사유의 페이싱
재보정 불요 제안). 하네스 `tools/judgment_scale_harness.py` 11,500 시나리오 전수 위반 0 ·
자기 시험 5눈 통과 (조율자 재실행 확인). ★남은 관문: §8.7 승인 대기 12행 사용자 결정 →
그때 B-135 구현 개방. 신규 미결 하나는 B-152 로 갈랐다 (포인트 은행).

★ 결정 완료 (2026-07-15 밤, 결정 회차 B-1 — `attribute_scale_v3.md §8.9`): §8.7 전 12행 닫힘
(마이그레이션 실수 보존 · 포인트 은행 무기한 보유[B-152] · 시트 원장만 표기 · 유도 8항목 일괄
승인). **B-135 구현 관문 개방** — 저장값=원장 하나 · 판정치=매번 floor(√원장) 비저장 · 시트는
원장만. B-137 자체 닫힘은 구현+하네스 회귀 통과 시.
### B-138 · 지형 원장·영수증 파일이 **자취 없이 사라져 있었다** — 기계가 「선 땅」을 잊었다
- **상태**: 진행
- **분류**: ★세계
- **단계**: P1
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/TerrainLedger.java:68`
- **의존**: —
- **닫는 조건**: 복원된 원장을 조성기가 실제로 읽는다 — 전맵 조성에서 화산이 "★ 원장에서 읽었다
  (땅을 안 건드렸다)"로 지나간다 (재조성·이중 깎임 0). 그리고 소실 원인이 판명되거나,
  원장 실재를 재는 눈이 선다 (terrain_built ↔ 세계의 선 땅 대조)
- **검증**: `run/forge-wave1-20260715-023507.log` 의 hwasan 줄 · `run/mvt/plugins/HoncheonMVT/terrain_built.yml`
- **닫힘**: —

### B-140 · 경계다듬기가 **못 고치는 급단차** — 여섯 곳이 검수에 걸린 채 남았다
- **상태**: 열림
- **분류**: 결함
- **단계**: P3
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/TerrainForge.java`
- **의존**: —
- **닫는 조건**: 곤륜(급단차 11%) · 당가(17%) · 팽가(15%) · 낙양(진입불가) · 개봉(22%) ·
  마교 본교(16% + 진입불가) 여섯 곳이 환경검수 ③(경계 ≤8%)·④(네 방위 진입)를 통과한다.
  **다듬기 반복이 답이 아님이 이미 실측됐다** — 원인(봉우리·험 지형에서 feather 띠가 좁거나,
  마교 본교처럼 다듬기가 되레 악화 11.9%→16.0%)이 판명되어야 한다
- **검증**: `/혼천 환경검수 <id>` 여섯 곳 · `run/forge-wave1-20260715-023507.log`
- **닫힘**: —

실측 (2026-07-15, 웨이브-1 F′): 전맵 28곳 중 22곳은 검수 통과(다듬기 1회 포함), 여섯 곳이
남았다. 전부 봉우리·험 계열 지형이다 — B-127 을 닫은 경계다듬기(feather 재적용)는 청하현
(마을 단·들)에서 검증된 손이고, 산의 급단차에는 다른 처방이 필요할 수 있다. ★마교 본교는
다듬기 후 급단차가 **커졌다** (11.9→16.0) — 이 손이 이 지형에서 무엇을 하는지부터 재라.
재조성은 금지다 (땅은 한 번만 선다 — B-127 의 계율).

### B-141 · 강가 부지가 **이미 물이다** — 수로채·대나루의 강을 못 판다
- **상태**: 보류
- **분류**: 결정
- **단계**: P2
- **위치**: `config/world_map.yml`
- **의존**: —
- **닫는 조건**: ★ 사람이 정해야 한다. 장강수로채·무한의 부지가 자연 수면(y62) 위라 강 파기가
  거절됐다 (판 골짜기로 물이 쏟아진다). 기계의 제안은 "terrain_types 가 뭍을 요구해야 한다" —
  그러나 **땅은 이미 섰다**: 지금 고르는 것은 ① 물가 부지를 그대로 받아들이고 강 요청을 걷는다
  ② 갈아엎고(땅갈아엎기) 뭍 요구로 재조성한다, 둘 중 하나다
- **검증**: `run/forge-wave1-20260715-023507.log` 의 `[지형/강] 거절` 2건 · `config/world_map.yml`
- **닫힘**: —

실측 (2026-07-15): `[지형/강] 강을 파지 못했다 — 부지가 이미 물이다 (자연 수면 y62 > 계획
수면 y60)` — 장강수로채(02:46)·무한(02:56). 거절은 소리를 냈고 땅은 섰다 (강만 못 팠다).
수로채는 물의 채(寨)라 물가 부지가 되레 어울릴 수 있다 — 그래서 결정이지 결함이 아니다.

### B-149 · 의뢰소 **게시판(표지판)을 걷는다** — 굳은 블록이 살아 있는 의뢰를 못 담는다 (사용자 확정)
- **상태**: 열림
- **분류**: 미완
- **단계**: P2
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/CheonghaBuilder.java:77`
- **의존**: —
- **닫는 조건**: 의뢰소의 게시 목판(표지판류 소품)이 원형에서 걷히고, 의뢰의 확인·선택이
  **의뢰인 NPC 에게 말을 걸어** 이루어진다 (뱃사공 섭구 B-121 의 우클릭 대화 문법이 선례).
  v5 원형 설계(B-147 브리프)에 게시판 없는 의뢰소가 실린다
- **검증**: 사람 눈 (의뢰소에서 NPC 대화 → 의뢰 확인·선택) · `server-mvt/src/main/java/com/honcheon/mvt/CheonghaBuilder.java`
- **닫힘**: —

★사용자 원문 (2026-07-15): "의뢰소의 경우 게시판(표지판)의 블록은 **건축 시작 이후는 변경할
수 없음으로 제거** (다른 방식으로 표현하던 **의뢰인에게 말걸어서 확인 및 선택**으로 변경)."
원칙이 하나 선 것이다: **살아 움직이는 내용(의뢰·소문·시세)은 굳는 블록에 싣지 않는다** —
블록은 건축의 것이고, 살아 있는 것은 NPC 의 입이나 책(서장 문법)으로 말한다. 다른 원형의
표지판류(북쪽 산길 안내판 등)는 내용이 불변이라 이 원칙에 안 걸린다 — 경계는 「변하는가」다.

### B-150 · ★ 봉인의 **고지대 사각지대** — TerrainSeal 이 기준면 8칸만 재서 높은 단의 흙일을 못 본다
- **상태**: 진행
- **분류**: 빚
- **단계**: P2
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/TerrainSeal.java:78`
- **의존**: B-139
- **닫는 조건**: 봉인이 고지대 열(정상 단·중턱 단)의 깎기·채움을 잰다 — 열별 실지면 기준 계측
  또는 승인된 terrace 요청 목록 대조. 그리고 건축 중 terrace 호출(before 봉인 뒤)이 "지도에
  승인된 단 요청"과 "임의 지형 변경"으로 구별된다 (요청 원장/허용 마스크). B-146 계열 수리의
  "TerrainSeal 계약 유지" 주장은 이 눈이 선 뒤에야 닫힌다
- **검증**: `server-mvt/src/main/java/com/honcheon/mvt/TerrainSeal.java` · 같은 원장 재건축 2회 대조 실측
- **닫힘**: —

기원: Codex 헌장 §3 검토 (2026-07-15, `docs/collaboration/CODEX_CHARTER_S3_REVIEW.md` 축 5).
현 봉인은 모든 열에서 `spec.groundY()` 아래 8칸만 잰다 — c8e3196 이 본전·석조도관·여섯 단을
peakY·실지면으로 올렸으므로 **기준면보다 수십 칸 높은 단의 흙일은 현 눈이 못 본다.**
"계약을 지켰다"가 아니라 "현 눈으로는 증명할 수 없다"가 정직한 판정이다. B-139(평면 가정의
계측 오염)와 같은 뿌리 — 봉인의 눈을 열별 실지면으로 올리면 둘이 함께 닫힐 수 있다.

진행 (2026-07-15 밤, 웨이브-2 트랙 F — 커밋 6a150c7): 수리가 섰다 — 열별 실지면(spec.groundAt)
계측 + 허용 마스크(=지도: surface/buildable 스냅샷 전후 대조) + 멱등 지문(FNV-1a) + B-139 잡음
절연 (마른 열 뼈만 · 젖은 열 고체만). 자기 시험 `tools/TerrainSealSelfTest.java` 17/17 — 구판 눈
되살려 사각지대 실재 재현 포함 (조율자 재실행 확인). 호출부 서명 무변경. ★닫힘은 jar 배포 후
재건축 2회 멱등 실측 (같은 원장 → 지문 동일 · 위반 0 · 단 열수 동일 — 절차는 트랙 F 보고).
알려진 한계 둘은 장부에 갈랐다: B-153 (survey 천장 2칸) · B-154 (세계조성 경로 봉인 미적용).
같은 높이 단의 속 채움 위양성 가능성은 판정문에 대조 안내로 남겼다 — 실측에서 걸리면 terrace
요청 기록이 근본 수리다.

Codex 독립 검토 (2026-07-15, `CODEX_WAVE2_MIGYEOL_REVIEW.md` §7): 실제 소스 + 최소 스텁으로
자기시험 재실행 **17/17 통과**. 고지대·습윤 잡음은 닫혔다. ★**판정: 조건부 통과** — 이미
`buildable: true` 인 열에서 지표 높이는 그대로 두고 지하 공동·속을 채우면(`true→true`) 블록 지문은
바뀌나 명시적 terrace 마스크가 없어 허가/무단을 못 가른다. 현 자기시험의 동일 높이 사례는
`false→true` 라 이 경우를 증명 못 한다. **남은 조건**: 지표·buildable 부수효과로 허가를 추론하지
말고 열별 `land request / terrain delta` 권한 마스크를 봉인 입력에 넣는다 + `true→true` 허가/무단
두 시험. **명시적 변경 권한 없이는 B-150 완전 종결 불가** — 재건축 2회 멱등 실측은 멱등만 증명하지
첫 변경의 정당성은 마스크가 증명한다.

### B-151 · `hidden` 키에 **독자가 없다** — 등록만 되고 아무도 안 숨긴다 · access 축 부재
- **상태**: 진행
- **분류**: 빚
- **단계**: P4
- **위치**: `config/world_map.yml:95`
- **의존**: —
- **닫는 조건**: 비밀 장소·랜드마크의 숨김이 실행 계약이 된다 — `hidden`/`player_map` 을 읽는
  독자(지도 렌더 차단)와 누락 lint 가 선다. 비밀 표기 세 축 분리 유지: `build`(조성 수명주기) ·
  `hidden/player_map`(표시) · `access/reveal`(해금) — `build: never` 를 비밀 표기로 쓰지 않는다
- **검증**: `python3 tools/map_lint.py` · 독자 코드 (신설 시 그 파일)
- **닫힘**: —

기원: Codex 검토 (2026-07-15, 축 4). v5 히든 장소·잔재(B-148 ②안 확정 사항)가 이 키에 기대게
되므로, 등록·표기 문법만 있고 집행이 없는 지금 상태가 빚이 된다.

진행 (2026-07-15 밤, 웨이브-2 트랙 G — 커밋 b4b6e8d): 독자 둘이 섰다 (`/혼천 지도` 렌더 차단 ·
`/혼천 출행` 목록 — id 직행은 해금 축의 몫이라 안 막았다). map_lint 4계열 신설 (숨김누락·표시모순·
표시타입·독자소실 회귀 감시) — 위반 0 · 자기 시험 63/63. 의미 결정 둘 **사용자 확정**
(2026-07-15 밤, 결정 회차 G-1): ① op·콘솔엔 「숨김」 표기로 보인다 (검수 유지) ② 숨긴 곳 출행
id 직행 허용 (표시 축만 차단 — 해금은 access/reveal 별도). jar 배포됨(21:28).

★★ 정정 — 「닫힘 임박」은 거짓이었다 (Codex 독립 검토, 2026-07-15, `CODEX_WAVE2_MIGYEOL_REVIEW.md`
§8): **접근(access) 축에 독자가 없다.** 조율자 코드 재확인: `WorldMap.Place` 에 `access` 성분이
없고(record 18성분 중 부재), `/혼천 출행` 은 "해금은 access 의 몫"이라 주석만 달고 **아무도
access 를 강제하지 않는다** (world_map.yml:92 `access.readers: []`). config 엔 실제 관문이 산다
(北莫 소속·서역 상단 favor 8+ 등) — 그런데 비op 가 id 만 알면 hidden·소문·세력·관문을 **전부
우회해 출행**한다. G-1 의 "id 직행 허용" 결정은 **access 축이 있다는 거짓 전제** 위에서 내려졌다.
표시 축(hidden 독자)만 섰고 세 축 분리는 아직 참이 아니다. **Codex 판정: 실패 — B-151 은 닫히지
않는다.** 남은 일(§12 권고 4): ① `access` 를 닫힌 타입으로 파싱(미지값 거부) ② 플레이어별
발견·소문·세력·관문 공개 장부 ③ 목록·지도·id 직행·타 진입점이 **같은 접근 판정기**를 부른다
④ id 우회·access 로더 누락 자기시험.

★사용자 결정 (2026-07-15, 결정 회차 W2-1): **임시 차단이 아니라 access 판정기 지금 구현**
(정공법 · Codex §12-4). 착수 = 조율자. G-1 「id 직행 허용」은 access 축 존재라는 거짓 전제였으니
철회 — 이제 목록·지도·id 직행·타 진입점이 **같은 접근 판정기**를 부른다.

진행 (2026-07-16, 커밋 86b9149): **우회 구멍 봉인 완료.** `AccessJudge` 신설 — id 직행(travel:1701)·
목록·지도 세 진입점이 같은 판정기를 부른다. access 는 config 어휘에서 유도한 닫힌 타입(미지값 거부).
관문형·미지·미등록은 **비op 거부(안전)+op 통과(검수)**. 자기시험: AccessJudgeSelfTest 45/45 ·
map_lint access 3계열+69/69 · TerrainGateSelfTest 13/13 · 빌드 exit 0 (조율자 재실행 확인).
★남은 것(닫힘 전): **개인별 공개 장부**(발견·소문·세력 favor 임계 원천 — 지금은 임계가 없어
관문형을 비op 거부로 둔다. 현 세계는 청하현=항상뿐이라 실사용 무영향)가 서면 judge 에 배선 ·
schema.enums.access 정식화 · 산문형(개방·하오문) access 토큰. 배포·사람 눈 대기.

### B-152 · C안 저울의 **포인트 은행** — 승급이 늦으면 Lv33부터 포인트가 캡에 막힌다
- **상태**: 닫힘
- **분류**: 결정
- **단계**: P3
- **위치**: `docs/design/attribute_scale_v3.md`
- **의존**: B-137
- **닫는 조건**: 승급 정체 중 레벨업 포인트의 처분이 정해진다 (은행에 쌓였다가 승급 후 소급 배분 ·
  또는 소멸 · 또는 캡 초과 예약) — 사용자 결정 후 §8 에 등재
- **검증**: `python3 tools/judgment_scale_harness.py`
- **닫힘**: 2026-07-15 · `docs/design/attribute_scale_v3.md` §8.9 ⑨ 에 결정이 섰다 —
  **미사용 포인트 무기한 보유**(캡 초과분은 승급 뒤 소비, 사용자 결정 회차 B-1). 닫는 조건이
  "사용자 결정 후 §8 에 등재"였고 그 파일이 존재한다. 구현은 B-135 의 일부.
  ★추기 (2026-07-24 · B안 개정 §8.10): **은행 규약 자체가 소멸했다** — 원장 캡 폐지로
  포인트가 언제나 들어간다. "캡에 막혀 남는 포인트"라는 전제가 사라졌다 (묘비 — 다시 세우지 마라).

기원: 웨이브-2 트랙 B (2026-07-15, attribute_scale_v3.md §8.5). 경지 캡 제곱표 위에서 XP 사다리와
교차 검산 중 발견 — 승급이 제때면 안 막히지만, 자격 레벨만 채우고 사건 마크가 늦은 자는 Lv33부터
포인트를 쓸 곳이 없다. 신규 미결이라 지어내지 않고 사용자 결정에 올린다.

### B-153 · survey 의 **천장이 2칸 낮다** — 최고봉 열은 실지면을 cy+70 까지만 본다
- **상태**: 열림
- **분류**: 빚
- **단계**: P4
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/TerrainForge.java`
- **의존**: —
- **닫는 조건**: surveyAt 의 탐색 천장이 산의 실제 최고 y(cy+72, lift 160 개정 시 그 이상)를 덮는다 —
  또는 천장이 lift 계약에서 유도된다
- **검증**: `tools/TerrainSealSelfTest.java` (열별 기준 대조)
- **닫힘**: —

기원: 웨이브-2 트랙 F (2026-07-15, B-150 수리 중 발견). `surface[i] = naturalGround(world,x,z,cy+70)`
인데 산은 cy+72 까지 선다 — 전/후 일관돼 봉인 오판은 없지만 최고봉 열의 계측 창이 2칸 내려앉는다.
v5 lift 150~165 개정이 오면 간극이 커진다 — lift 개정과 한 몸으로 고치는 것이 맞다.

### B-154 · 세계조성(CheonghaBuilder) 경로는 **봉인을 아예 안 탄다**
- **상태**: 열림
- **분류**: 빚
- **단계**: P3
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/CheonghaBuilder.java`
- **의존**: B-150
- **닫는 조건**: 청하현 전용 조성 경로에도 지형 봉인(전/후 대조)이 선다 — 또는 이 경로가 v5
  청하현 확대 재설계(cheonghagwon_expansion_v5.md)로 대체되며 새 경로가 봉인을 탄다는 결정이 선다
- **검증**: `tools/TerrainSealSelfTest.java` · 조성 로그의 봉인 판정 줄
- **닫힘**: —

기원: 웨이브-2 트랙 F (2026-07-15). B-150 수리는 지역조성/지형조성 경로만 덮는다 — `/혼천 세계조성`
(청하현)은 봉인 없이 빚는다. D-11 확대 재설계가 이 경로의 운명을 정하므로 그 결정에 걸어 둔다.

### B-155 · 산세에 **z=0 급단차** — 산 한복판을 동서로 가르는 33칸 수직 벼랑
- **상태**: 닫힘
- **분류**: 결함
- **단계**: P2
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/forge/RangeField.java:344`
- **의존**: —
- **닫는 조건**: 주봉 북 오프셋(TF:868-870 계승)이 만드는 z=0 이음의 세로 이웃 격차가 물매
  계약(1:1 안 · 계단이 아닌 자연면) 이하로 내려간다 — 오프셋을 매끄럽게 보간하거나 걷는다.
  그리고 RangeField 자기 시험에 **relief 연속성 축**(세로/가로 이웃 급단차 상한)이 선다
  (결정론·구역 분할만 재던 현 self-test 가 이 결함을 못 봤다 — 눈을 만들면 눈을 시험하라)
- **검증**: `server-mvt/src/main/java/com/honcheon/mvt/forge/RangeField.java` self-test 연속성 축 (java 실행 · 서버 무의존)
- **닫힘**: 2026-07-15 · `RangeField.java:344` `mainRidge` 하드 컷(`dz<0→0`)을 걷고 북면을
  `NORTH_STEEP` 배 하강(dz=0 연속)으로 바꿨다 — 남 능선과 후산이 급단차 없이 만난다.
  오프라인 재측: **z=0 밴드 최대 세로 격차 25+→2칸** (`HwasanPreview` PNG 가로선 소멸 · 눈으로 봤다).
  RangeField self-test 에 **연속성 축**(z=0 밴드 급단차 상한 SEAM_STEP_MAX=6) + 눈을 시험하는 눈
  (심은 25칸 턱 검출) 신설 — PASS (88,209 표본). jar 빌드 exit 0.

기원: 화산 오프라인 미리보기 (2026-07-15 밤, `HwasanPreview` — 서버 무접촉). PNG 높이맵에
z=0 가로 어두운 선이 보여 재봤더니 **최대 33칸 급단차** (x=−47, z=−1→0). 예: x=−80 에서
z=−1=70 → z=0=95 (+25). 원인 = `mainRidge` 하드 컷(`dz<0→0`) — 남 능선(정상 높이)과 북 몸체
(kMin 압축)가 z=0 에서 만나 벼랑. (Q6 「북 오프셋 8」은 peakZ 호출자 몫이라 무관했다.)
★"땅은 한 번만 선다 — 되돌릴 수 없는 일은 작게 시작하라"의 산 증거: **오프라인에서 잡았기에
세계를 안 깎고 고쳤다.** ★잔여(B-155 밖): 북 후산 험면 자체가 kMin(peakSteepSlope) 압축으로
발치 부근 ~11칸/칸 가파르다 — z=0 이음이 아니라 기존 험면 설계값이다. 재튜닝은 기계 ③ 등산로·
kMin 검토의 몫 (terrain_forge_v5.md §2 잔여 튜닝).

★★ 재범위 (2026-07-16 · 사용자 도보 진단 "연속성이 문제 — 매끄러운 연속 선이라 험산이 아니다" ·
커밋 후속): 연속성 축의 **검사 범위가 전역 → calm(보행/건축) 셀만**으로 좁혀졌다. SEAM_STEP_MAX=6
상수·의미(보행 계약)는 그대로. 근거: 옛 전역 "모든 인접 단차 ≤6"은 z=0 인공 이음 회귀를 잡는
정당한 눈이었으나 **의도한 화강암 험산 절벽까지 금지**해(華山은 깎아지른 암벽·불연속이 본질)
도보 진단의 뿌리가 됐다. 재범위 뒤: calm(등산로·건물 단·능선 보행선·품)은 여전히 ≤6 연속(옛
버그는 보행선을 가로질렀으므로 calm-only 로도 잡힌다) · 비-calm 절벽 면은 큰 단차 허용(방향성
절리 블록·carve 깎인 벽). 실측 calm 3.96 · 비-calm 절벽 54. 눈을 시험하는 눈에 「험산 확인」
(비-calm 단차가 6 이하면 "절벽 없음" FAIL) 추가. 사유·실측 전문 terrain_forge_v5.md §8.2.

### B-156 · 광역 산세 ↔ 장소 조성 **장부 분리** — prepare 의 이중 조성 위험 (헌장 §2.3)
- **상태**: 열림
- **분류**: ★세계
- **단계**: P2
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/TerrainForge.java`
- **의존**: B-155 · B-148
- **닫는 조건**: 광역 산세 단계와 장소 prepare 단계가 **별도 장부**(범위 id·월드 세대·명세 지문
  키)에 완료를 기록하고, 장소가 완료된 광역 산세에 속하면 prepare 가 **범위 보존 모드**로 들어가
  `shape` 를 부르지 않고 RangeField·봉우리 높이·표면을 소비한다. 자연 표면 탐침(cy+70 조기 반환)은
  방어선일 뿐 완료 판정 권위가 아니다. 통합시험: ① 범위 완료 뒤 prepare 가 shape 안 부름 ②
  동일 id·다른 지문 거부 ③ 월드 재생성·범위 완료 전 장소 실행 거부
- **검증**: `server-mvt/src/main/java/com/honcheon/mvt/TerrainForge.java` 통합시험 (신설) · 화산 조성 로그 "범위에서 읽었다"
- **닫힘**: —

기원: Codex 회신 (2026-07-15, `CODEX_WAVE2_MIGYEOL_REVIEW.md` §1·§12-2). lift 72→160 은 설정
결정이나, 기준면 64+lift 160 봉우리에서 `cy+70` 탐침 시작점이 완성 봉우리 내부가 돼 조기 반환이
성립 안 하고 **두 번째 원뿔을 쌓을 위험**이 있다. 중복 조성을 막는 것은 탐침이 아니라 단계·장부
계약이다 — 화산 시험 조성(배선)의 관문.

★사용자 승인 (2026-07-15, 결정 회차 W2-1): 헌장 §2.3 문안 **승인** → `map_charter_v5.md §2.6`
(광역/장소 소유권 7항)에 반영됨. lift 160 은 배선·테스트 때 적용(W2-1). B-156 구현이 이 계약을 집행한다.

### B-157 · RP-4 A안 데모의 **측정 척도·생명주기 보강** — teleport 대리는 A/B 판정 불가
- **상태**: 열림
- **분류**: 빚
- **단계**: P4
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/rp4/Rp4DemoStage.java`
- **의존**: —
- **닫는 조건**: A/B 판정에 외부 틱 프로파일러·실제 엔티티 수·프로토콜 패킷 계수(또는 캡처)가
  같은 관전자·같은 이동 스크립트·같은 엔티티 수로 함께 쓰인다 (teleport/틱은 보조값). 그리고
  ① showEntity 가 리소스팩 수락 여부를 검사(팩 실패·대체 경로 시험 가능) ② CommandMap 명시적
  해제(reload 잔재·중복 바인딩 방지) ③ 레지스트리 독자가 `interval` 도 읽는다
- **검증**: `server-mvt/src/main/java/com/honcheon/mvt/rp4/Rp4DemoStage.java` · A/B 데모 측정 계획 (rp4_pilot.md §11)
- **닫힘**: —

기원: Codex 회신 (2026-07-15, §9). 수식은 생산 모델과 정적 일치(통과)하나, nanoTime 은 Java
콜백·작업 투입만 재고 패킷·클라 렌더·Display 내부 비용을 못 잰다. 팩 게이트·reload 생명주기
위험도 남는다. **판정: 표현 수식 통과 / 실험 척도·생명주기 조건부 실패.**

### B-158 · 입도진이 **문턱을 스쳐 반복 재건축**된다 — 완결 96% vs 문턱 97% churn
- **상태**: 열림
- **분류**: 결함
- **단계**: P3
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/Antechamber.java:573`
- **의존**: —
- **닫는 조건**: 온전히 선 나루가 재검(진입·재기동)마다 다시 안 지어진다 — 완결 계측이 문턱을
  안정적으로 넘거나(변동 블록 표본 제외/전수 이정표로 갈음), 문턱이 실측 대역에 맞게 정해진다.
  같은 나루를 두 번 재보면 같은 완결%가 나온다 (계측 결정성)
- **검증**: `server-mvt/src/main/java/com/honcheon/mvt/Antechamber.java:573` · 조성 로그 완결% 안정성
- **닫힘**: —

기원: B-131 재발 수사 (2026-07-16). 로그(23:19·23:54)에 나루가 "완결 96% (문턱 97% · 표본 1/61)
· 처음부터 다시 짓는다" → "섰다 완결 97%" 를 **반복**한다. `completeness` 는 40,343칸을 61칸
간격(~660 표본)으로 재는데, 조성 뒤 값이 변하는 블록(물 흐름·중력·잎 등 후보)이 표본에 들면
96↔97% 를 오가며 문턱(97)을 스친다 — 그때마다 4만 칸을 통째로 다시 짓는다. 이 churn 이 B-131
겹침의 방아쇠였다 (재건축 spawn 이 가림을 재적용 안 함 — 그쪽은 382464b 로 닫음). 이정표(발판·종)
는 이미 전수 검사(landmarksStand)라, 부피 문턱은 변동 블록에 더 관대해도 튜토리얼 핵심은 안전하다.
근본 수리 전 어느 블록이 흔들리는지 인게임 확인이 필요하다 (지어내지 않는다).

### B-159 · `/혼천 산세시험` **재실행 비멱등** — baseY 를 주봉에서 재 산 위에 산을 쌓는다
- **상태**: 닫힘
- **분류**: 결함
- **단계**: P3
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/MvtCommand.java:935`
- **의존**: —
- **닫는 조건**: 같은 테스트 월드에 산세시험을 두 번 돌려도 같은 산 (상승고 160 불변) — baseY 가
  재실행에 불변인 평지에서 측정된다
- **검증**: `server-mvt/src/main/java/com/honcheon/mvt/MvtCommand.java:935` · 조성 로그 상승고
- **닫힘**: 2026-07-16 · `MvtCommand.java:935` — baseY 측정을 주봉 (0,0) → **생활권 밖 x=600
  평지**로 옮겼다 (economyR 444 밖이라 산이 안 닿아 언제 재도 FLAT 표면 −61). 인게임 실측: 삭제·
  재기동 뒤 새 조성 **상승고 정확히 160** (기준면 y−61 · 정상 y99, 02:51 로그). 커밋 후속.

기원: 화산 시험 조성 (2026-07-16). 첫 조성은 평지(−61)에서 정상 y99 정상. 그런데 baseY =
`getHighestBlockYAt(0,0)` 인데 (0,0)=주봉은 조성 뒤 산 꼭대기라, 재실행이 그것을 기준면으로 읽어
산 위에 산을 쌓았다 (실측 02:42: 기준면 y259·상승고 60 — 어긋남). MountainRangeForge 의
clearAbove 천장(base+lift+여유)도 잘못된 base 로 쌓인 옛 산을 다 못 걷는다. 조율자 실수(깨끗이
하려고 재실행)가 드러낸 진짜 버그 — 망가진 버리는 월드는 삭제 후 재조성으로 복구.

### B-142 · 서장 토큰의 **공백이 책의 클릭을 죽였다** — 침묵 반환이 공범이었다
- **상태**: 진행
- **분류**: 결함
- **단계**: P3
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/MvtCommand.java:2136`
- **의존**: —
- **닫는 조건**: 판정어에 공백이 든 토큰('부분 성공'·'아슬아슬한 성공')의 책 클릭이 다리에 실린다 —
  수리 jar 배포 후 서장 실측(공백 토큰 장면에서 클릭 → `run/bridge/mvt/*.jsonl` 에 사건) +
  임시 중계기(`run/seojang_relay.py`) 철수
- **검증**: `server-mvt/src/main/java/com/honcheon/mvt/MvtCommand.java` · `run/bridge/mvt/20260715.jsonl`
- **닫힘**: —

실사고 (2026-07-15 08:42, 실플레이 — 디돈 서장): 장면 2 토큰 `8:2:부분 성공:e60f9cb8` 의
판정어 "부분 성공"에 공백 → 책 클릭의 `/혼천 서장 <토큰> <n>` 인자가 갈라져
`Integer.parseInt(args[2])` 가 "성공:e60f9cb8" 을 씹고 **침묵 반환** ("사람이 손으로 친 것"
간주). 클릭 두 번(08:42:49·08:43:41)이 콘솔엔 찍혔는데 다리 inbox 엔 없었다 — 책이 멈춘 채
플레이어가 기다렸다. 장면 0("-")·1("성공")은 공백이 없어 통과 — **판정어가 두 낱말이 되는
순간에만 터지는 병**이라 여태 숨었다. 응급: 사건 손 주입(선택은 사용자에게 물었다 — 1번 장터)
→ 장면 3 도착. 수리: 마지막 인자=번호, 가운데를 도로 이어 토큰으로 (`String.join`) — 컴파일
확인, **배포는 플레이어 퇴장 후** (그때까지 콘솔 원문을 읽는 중계기가 공백 토큰 클릭을 되살린다).

### B-143 · 서장 선택은 **번복 불가**여야 한다 — 첫 클릭이 이긴다 (사용자 확정)
- **상태**: 열림
- **분류**: 결함
- **단계**: P3
- **위치**: `server-bot/src/main/java/com/honcheon/bot/GameListener.java:1521`
- **의존**: B-142
- **닫는 조건**: 한 장면에 유효한 선택은 **첫 클릭 하나** — 뒤 클릭은 거부되고 그 사실을 사람에게
  말한다. 현행 낡은-토큰 필터가 같은 장면 안의 두 번째 클릭(장면이 아직 안 넘어간 창)도 막는지
  실측 포함
- **검증**: 사람 눈 (서장에서 같은 장면 연속 두 클릭 → 둘째가 거부되는가) · `server-bot/src/main/java/com/honcheon/bot/GameListener.java`
- **닫힘**: —

사용자 원문 (2026-07-15): "1번으로 하는데 클릭을 번복할순 없어야 함 추후 수정 목록에 추가."
이번 사고에서 두 클릭(의방→장터)이 서로 달랐던 것은 첫 클릭이 침묵사(B-142)해 응답이 없었기
때문이다 — 정상 경로에서도 봇이 처리하기 전의 짧은 창에 두 클릭이 나란히 들어올 수 있다.
임시 중계기는 (이름, 토큰) 열쇠로 첫 클릭만 살리게 이미 그 결을 따랐다.

### B-147 · ★★ v5 지형-건축 **통합 설계 요건** — 봉우리 하나에 건물을 얹는 것으로 끝이 아니다 (사용자 확정)
- **상태**: 열림
- **분류**: 결정
- **단계**: P1
- **위치**: `docs/design/gate_and_watertown.md`
- **의존**: B-146
- **닫는 조건**: v5 재조성의 지형 설계 요건이 설계 문서로 서고 사용자가 승인한다 — 최소 포함:
  ① 문파/장소마다 **영향권 단위의 지형 브리프** (주변 환경·컨셉·진입로 — 봉우리 하나가 아니라
  영역), ② **정상부·단(터)의 최소 폭이 건축의 실측 footprint 에서 유도**된다 (원형별 건물
  크기·배치·디자인 → 필요한 땅의 치수), ③ 외곽 자연 지형과의 어울림(이음)이 설계 항목이다.
  그리고 §2.4 두 계층 계약과의 관계가 명문화된다 (아래 본문)
- **검증**: 결정 후 신설되는 설계 문서 · `docs/BACKLOG.md`
- **닫힘**: —

★사용자 원문 (2026-07-15, 실플레이 관찰 직후): "건축물 밑에만 한 청크 기준으로 땅이
채워져 있는 것이 아니라 전체적으로 봤을 때 해당 구조물이 외곽 지형과 잘 어울리는지 —
화산이나 종남을 예로 들자면, 지형이 봉우리 하나 설치 후 거기에 건물이 올라간다고 해서
끝이 아니라 **문파 주변의 환경, 영향권 내의 컨셉 등 모든 사항을 고려하여 지형이 지어져야**
하며, 봉우리의 경우들이 **너무 얇게 지어져 건물을 올려 디자인을 하기 힘들게** 만듦.
**건축 크기·디자인·건물 배치 등을 모두 고려하여 설계가 되어야 함**."

★추가 요건 둘 (2026-07-15, 사용자 — 브리프 틀·역산표에 반영할 것):
- **지역별 건물 유형 변주**: "지역별 건물 유형이 조금씩 달랐으면 좋겠음" — 같은 원형이라도
  지역(세력·풍토)에 따라 생김이 갈린다. "여덟 문파가 전부 같은 집" 사고(§2.2)의 적극적 반대말.
- **내부 우선 설계 (객방 건물)**: "객방을 포함한 건물인 경우 **내부 방 인테리어와 위치까지
  설계 후 크기 설계**" — 설계 연쇄가 한 층 더 깊어진다: **방(인테리어·배치) → 건물 크기 →
  부지 footprint → 땅의 치수**. §2.4 의 지도-정본이 실내까지 내려간다.

★헌법과의 관계 — **사용자가 조율자의 「양립」 독법을 기각했다** (2026-07-15, 원문):
"둘 다 양립 안 한다고 생각합니다. **지도가 있고, 지도에 맞게 땅이 지어지고, 건축이 올라가는
것**이지 — 그 지도엔 **건축물도 포함**이며 **맵 디자인 전체를 포함**하는 말입니다."
→ **§2.4 개정 대상**: 옛 표어("땅에 맞게 건물이 올라가는 것이지, 건축에 맞게 지형이 생기는 게
아니다")는 죽는다. 새 위계는 3계층 — **지도(건축 포함 맵 디자인 전체, 정본) → 땅(지도에 맞게
선다) → 건축(그 땅 위에 오른다)**. 집행 안전핀(땅은 한 번만 선다 · 건축 집행이 선 땅을 못
바꾼다)은 지도를 섬기는 기계로 존속하는지 — 개정 문안과 함께 사용자 승인 대기.

### B-148 · ★ 세계의 밑감 결정 — **바닐라 지형 위 조성이냐, 평지에서 전부 제작이냐** (v5)
- **상태**: 진행
- **분류**: 결정
- **단계**: P1
- **위치**: `config/world_map.yml`
- **의존**: B-147
- **닫는 조건**: ★ 사람이 정해야 한다 — v5 세계의 밑감: ① 바닐라 지형 유지(현행 — 측정·깎기·이음
  비용 지속) ② 평지/공허 기반 전부 제작 ③ 자체 청크 생성기. 검토표는 `docs/design/map_charter_v5.md`
  의 밑감 절 — 결정되면 world_map·terrain 등록부와 갈아엎기 절차가 그에 따른다
- **검증**: `docs/design/map_charter_v5.md` · `config/world_map.yml`
- **닫힘**: —

★사용자 제안 (2026-07-15, 원문): "바닐라 지형을 확인 후 측정하고 땅을 깎거나 설치하는 게
힘들다고 판단이 되는데, **평지 맵에서 처음부터 모든 걸 제작하는 형태**는 어떤지 고려."

★사용자 확정 — ②안(평지)의 정의 (2026-07-15, 원문): "장소 간 이동은 **직접 뛸 수도 있어야**
합니다 (그러므로 **디자인이 되어야** 합니다). **강·산·숲을 직접 설계**하고, 중간중간 **적**과
장소 컨셉에 맞게 **숨겨진 잔재라던가 히든 장소**를 배치해도 됩니다. **여백은 — 지역 사이간은
없는 걸로** 제작합니다." → 양식화된 빈 공간(조율자의 여백 안)은 **기각**. 평지 밑감을 고르면
세계 전체가 연속으로 설계된다: 이동 회랑·야생 지형·탐험 콘텐츠(적·잔재·히든 장소)까지 지도의
일부다. 히든 장소도 등록부에는 실린다 (플레이어에게 숨는 것이지 지도에 숨는 것이 아니다).
조율자 소견: 새 §2.4 (지도가 맵 디자인 전체의 정본)와 정합 — 바닐라와 싸운 사고 이력이
이 방향을 지지한다: 경계 급단차·진입불가(B-140) · 부지가 이미 물(B-141) · 산 채로 묻힌 나무
(B-113) · cheese 동굴(B-113) · 물리 정착 계측 오염(B-139) — 전부 「바닐라와의 이음」에서 났다.
이 서버는 이미 바닐라를 대부분 걷어냈다 (야생 스폰 0 · no_caves 데이터팩 · 팩 필수).
남는 진짜 질문은 **장소 사이의 공간(강호의 여백)을 무엇으로 채우는가** — 검토표의 몫.

★딸린 선행 결정 — **세계 축척** (헌장 §2.3 실측이 드러냄): 현 등록부의 노선 12개 합이
**15,364 km** (조성 원 5만 개 상당) — 서사 이동(출행) 시대의 좌표다. ②안(도보 가능 연속
세계)을 고르면 **지역 간 거리 자체를 도보 세계의 자로 다시 정해야 한다** — 축척 재결정 없이
"전부 설계"는 물리적으로 불가능하다 (전면 수공 1.3×10¹³ 칸). v5 지도 그리기의 첫 획이다.

★★결정 (2026-07-15, 사용자 원문): "**세계 전체 연속 설계 방식으로 진행.** 대신 처음 설계 시
너무 큼으로 **특정 필드 세력 지역을 기준으로 단계적으로 세계 제작**." → ②안 채택 + 단계 제작:
한 세력 지역(필드 포함)을 완결로 세우고 검증한 뒤 이웃으로 확장한다 — "되돌릴 수 없는 일은
작게 시작하라"와 같은 결. 남은 것: ⓐ 첫 지역 선정 (사용자) ⓑ 세계 축척 (위) ⓒ 헌장 §2 반영.

★H-7 확정 (2026-07-15, 사용자): 사다리(§17)의 자 = **build_radius (본산 기준)** — 짓는 한
구획의 반경이 서열의 자다 (화산 128 배정 후보 · 비율 파급: 소림 160 · 종남급 96 · 점창급 80).
domain_extent·economy_extent 는 서술 범위로 남는다 (사다리에 안 물린다).

★★결정 — 헌장 §5.3 봉우리 선택지 (2026-07-15, 사용자 원문): "**A — 부지를 키운다.** 대신
이에 맞게 **소림도 커져야 하며 사다리 관계를 재정립**해야 한다." → 봉우리 문파 부지 rad ≈100
방향 + 전 장소 크기 서열(§17 사다리 · scale_systems.md 크기의 자)을 새 자로 재산정한다.
세계 축척(ⓑ)과 한 몸의 재설계다 — 축척·사다리·부지를 따로 정하면 세 번 갈아엎는다.

★★확정 넷 (2026-07-15, 사용자 — 외출 전 즉답):
① **세계 축척**: 이웃 지역 도보 **약 20분** · **경공 수련·사용 시 5분 내 도착** — 경공이
  여행 기술이 된다 (이동 속도 설계가 축척에 묶인다 · B-147 이동 설계에 편입)
  ★D-1 확정 (2026-07-15, 사용자 — 정정 포함): **노선 기준 20분** — 굴곡진 길을 따라 20분,
  직선 환산 약 3,450~4,490블록 (굴곡 1.5 가정). 경공 5분은 같은 길 위에서 정확히 4:1
  ★D-2 확정 (2026-07-15, 사용자): 여행 경공 기전 = **지속 가속(질주) + 도약 연쇄(대도약) 복합**
  — 평지는 내력을 태우며 점점 빨라지는 질주, 절벽·계곡·장애물은 연쇄 도약. 목표 평균 17~22m/s
  (현행 최고 ~7m/s) · 연료 축(내력 유지비 — 현행 21초 한계) 재설계가 딸린 설계 과제.
  현행 경공(더블 점프 1회)은 이 복합의 첫 조각으로 흡수된다. 설계 문서 신설 대상
② **1단계 제작 지역**: **청하현권** (청하현 + 산길 + 나루 + 화산 방면 회랑)
③ **사다리 재정립**: (a) 비율 유지 기계 산출 초안 → 사용자 손질. ★단 "기존 설계했던 화산의
  크기라면 너무 작다" — **화산은 캠퍼스다** (본전만이 아니라 **수련동** 등 시설 일습) —
  사다리의 기준점은 계단 도달 반경이 아니라 **캠퍼스 브리프의 footprint**
④ **정상 평탄부 유도**: 원형별 최대 발자국 기준 (곤륜 도관 → SUMMIT_R ≈32) — 권고대로
⑤ B-137 저울: **사용자가 설명을 원한다** — 결정은 대화 후 (웨이브-1 승인 B군도 귀가 후)
  → ★★H군도 전부 확정됐다 (2026-07-15 저녁, 결정 회차 2 — 화산 브리프 §3 표 대조):
  H-1 겹침 셋 승인 · H-2 본전=본산 최고 단(정상은 수행대·전망대) · H-3 시설 치수 13건 일괄
  (시험 조성 검증) · H-4 숙소 3구분+장로원 일원화 · H-5 객원은 내부 설계 후 · H-6 구역 깊이
  140/30/150 · H-7 build_radius=사다리 자 · H-8 물매 1:1·굴곡 1.5·8칸 격자 · H-9 화산촌 64 ·
  H-10 forge_radius 상위 계약은 **시험 조성 후** 개정 · H-11 **가안 산세 복합** + lift **150~165
  (더 높게 — 대표값은 산세 생성기 설계에서 재산출)** · H-12 기계 11종 **시험 조성 필요분부터
  착수 승인** · H-13 70/20/10 = **면적비** · H-14 팔레트·식생 등록부 이관 승인 · H-15 신규 장소
  **다섯 전부 채택** (화산촌·약초촌·수행 암자·벌목촌·채석촌 — 정착지 6개) · H-16 회랑 종점=
  화산촌 + 흑수나루 물류로 승인. ★남은 둘은 사용자 숙제: H-17 NPC 명단 증원 · H-18 후산·
  랜드마크·비밀 요소의 내용.
  → ★D군 잔여도 확정 (결정 회차 3): D-7 밑감 세부 = **superflat** · D-8 1단계 편입 =
  **위수 갈대밭만** (녹림 소채·종남은 보류 — 핵심 구성으로 작게) · D-9 = **장안 경유 유지**
  (1단계엔 장안 자리만 비워 둠 · 2단계 확장에서 잇는다) · D-11 = **청하현 확대 — 현성답게**
  (재설계 대상 — 화산촌 64 보다 커야 결이 산다). 잔여: D-6(여정 days — 설계 유도) ·
  D-10(필드 내용 — H-18 과 같은 사용자 숙제) · D-12(갈아엎기 최종 승인 — 실행 시점).
  → ★전부 확정됐다 (2026-07-15 저녁, 하나씩 결정 회차): B-137 = **C안 제곱 환산층** ·
  D-1 = **노선 기준 20분** · H-7 = **build_radius 가 사다리의 자** · D-2 = **지속 가속+도약
  연쇄 복합** · 상단 6곳 시안 = **보류, v5 브리프 프로토콜(조사 기반)로 재작성** (기존 시안은
  재료 — 상업 거점 유형 프로토콜의 첫 사례가 된다) · RP-4 = **A/B 비교 데모 둘 다 진행** ·
  보스바 = **평시 파랑·고갈 빨강** · 바닐라 XP 잔여 유입 = **전부 끊는다** (Mending·화로·
  낚시·경험치 병·교배 — 경험은 v3 XP 하나만)

★사용자 문서 등재 셋 (2026-07-15): `docs/design/hwasan_campus_architecture.md` (건축 보강 —
시설 16종·수직 성장·팔레트) + `docs/design/hwasan_domain_design.md` (**영역 설계** — 11구역:
외곽 평원→정상 · 자연 70/건축 20/인공 10 · 구역별 밀도/식생/NPC 표 · 랜드마크 · 생성 순위
8단계) + `docs/design/hwasan_economic_sphere.md` (**경제권** — 화산촌(필수·80~150명)·상업
거리·외문·농경지·약초/벌목/채석·수행 암자 · 정착지 4~6개 권장 · 청하현=광역 거점·흑수나루
물류 연결). 전부 사용자 직접 작성 · 우선순위 계약 내장 (사용자 확정 > 등록부 > 헌장 > 보강 문서).
**B-147 ①(영향권 브리프)의 화산 몫이 정본으로 채워졌다.** 사다리 앵커 R_c 는 본산/영향권/
경제권 세 눈금으로 나눠 제시하고 어느 자인지 사용자 결정 대기 (브리프 초안에 반영 지시됨).
신규 등록부 장소 후보(화산촌·약초촌·수행 암자 등)는 브리프에 표로 — 등록부 반영은 승인 뒤.

★넷째 정본 (2026-07-15): `docs/design/sect_brief_protocol.md` — **문파 브리프 생성 지침**
(조사 우선: 실제 역사·지리·건축을 인터넷 조사, 출처 우선순위 ★5 학술~★1 팬 설정(근거 불가) ·
STEP 1~10 · 문파당 문서 6종(건축/영역/경제권/NPC 생활권/게임플레이/자동 생성) · ★근거 4분류
(사실/문화/무협 통용/MC 결정) 혼합 금지). **헌장 §3 역반영의 내용이 이 프로토콜로 확장됐다**
— 절차는 사용자 지시대로 Codex 정리→검토→수정→진행: `docs/collaboration/
FABLE_CHARTER_S3_REVIEW_REQUEST.md` (SHARED_MEMORY_V6 포인터 등재). ★소급 면제 (사용자
확인 2026-07-15): "화산은 이미 인터넷 조사를 통해 작성된 내용" — 화산 문서 셋·브리프는 조사
pass 불요. 프로토콜은 **다음 문파부터** (소림·무당·청성·아미·곤륜 등) 전면 적용.
시설 후보 16종(산문·외문광장·천계단·연무장·본전·★수련동·숙소·식당·장경각·약방·무기고·객원·
장문인 거처·장로원·매화원·후산) + 수직 성장·자연 우선·블록 팔레트·동선·생성 순서.
1단계 계획의 **D-3(캠퍼스 시설)의 답**이다. 개발 AI 판단 두 가지 (문서의 계약대로 기록):
ⓐ **캠퍼스는 정상 원반이 아니라 산비탈을 오르는 단들의 사슬** (산문→후산 수직 성장 + 대규모
평탄화 지양) — 사다리 앵커 R_c 는 원반 반경이 아니라 **사슬 전체를 담는 부지 반경**으로 잰다.
ⓑ SUMMIT_R(32) 정상 평탄부는 **본전 층만** 담으면 된다 — 연무장은 본전 앞 아랫단으로 (문서의
"본전 앞 배치"와 "대규모 평탄화 지양"이 함께 서는 해석). 현행 도관 원형(정상 원반에 본전+연무장)
은 v5 에서 **다층 캠퍼스 원형으로 재설계 대상**이다.

### B-146 · ★★ 건축이 **땅에 앉지 못한다** — 묻히고(청하현·종남) 뜬다(화산)
- **상태**: 진행
- **분류**: ★세계
- **단계**: P1
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/RemoteBuilder.java:580`
- **의존**: —
- **닫는 조건**: ① 원인 판명 — 건축의 기준면(`spec.groundY()` 평면 가정)과 실지면(v4 지형의
  덩어리 요철·경사)의 어긋남이 어디서 나는가 (청하현은 전용 조성기·화산/종남은 지역조성 —
  **서로 다른 두 경로가 같은 병**이므로 공통 원인부터). ② ★사용자 방향 (2026-07-15):
  **"새 월드에서 지도 설계를 완벽히 끝내고 재건축"** — 그 재건축에서 건물이 땅에 앉은 것을
  사람 눈으로 본다. 재건축 전에 원인이 서야 한다 — 안 그러면 새 월드에서도 같은 병이 돈다
- **검증**: 사람 눈 (묻힘·뜸 좌표) · `server-mvt/src/main/java/com/honcheon/mvt/RemoteBuilder.java`
- **닫힘**: —

실측 (2026-07-15, 실플레이 사용자): "청하현은 건물들이 땅에 묻히고 있고, 화산파의 건물은
공중에 떠 있고, 종남파의 건물들도 땅에 박혀 있는 등 문제가 많음." ★B-139 의 봉인 실측
(장강수로채 — 건축이 땅을 판·돋운 21열)과 정합한다: 건축이 평평한 기준면을 가정하고 서는데
v4 지형은 덩어리로 흔들린다 — 낮은 곳에선 몸이 묻히고 높은 곳에선 뜬다는 가설이 유력.

【진행 2026-07-15 — 원인 수사 완료 (코드 수사 · 실측 대기)】 **건축이 열마다의 실지면을 안 읽고
평면에 못 박는다.** `SiteSpec.groundAt(x,z)` (TerrainForge.java:193 — 열마다의 실지면)가 이미
있는데, 이를 읽는 원형은 `approachPath`(RemoteBuilder.java:694) **하나뿐**이다. 나머지는 전부
고정 평면: 산채 `cy = spec.groundY()` (RemoteBuilder.java:580 — 목책·망루·막사), 문파
`lower/upper` 두 평면 (RemoteBuilder.java:922·945 — 산문·본전·연무장·석등·매화). terrace 는
요청받은 발자국(본전 27×21 등)만 평탄화하므로 그 밖으로 나온 구조물이 뜨거나 박힌다.
② 봉우리(화산·종남)는 원뿔 위 두-평면 근사라 **경사 위 평면의 위치에 따라 방향이 갈린다**
(upper 가 rise=min(peakY−lower, run−4) 로 잘림 — 화산 뜸 ↔ 종남 박힘이 같은 원형에서 반대로
나오는 것과 정합). ③ 청하현은 별개 몸통(CheonghaBuilder — TerrainForge 를 안 탄다): 지면은
자체 고도장 gy 를 따르는데(진폭 ±2) 노면·담·건물은 cy 에 못 박혀 ±2칸 묻힘. ④ 뇌관 하나:
지역조성의 앵커 재측(MvtCommand.java:706 — 세운 산 위를 다시 재 화산 80)은 커밋된 땅에선
원장 64 가 이겨 무해하나, **원장이 또 사라지면(B-138) cy=80 으로 16칸 뜬 산을 새로 빚는다.**
수리 방향 (v5 재건축 전 필수 — 안 고치면 새 월드에서 재발): ①원형 발자국 **전체** 선평탄화
(terrace 요청 확장 — 2계층 계약 정합) + ②소품·담은 groundAt 따라 앉히기 (approachPath 문법
확산) 병행, ③청하현 cy→gy 정합. 봉우리 정상 평탄부(SUMMIT_R=22)는 B-147 의 footprint 역산과
연동해 넓혀야 한다 (본전이 SUMMIT_R 안에 들거나, SUMMIT_R 이 본전에서 유도되거나).

【진행 2026-07-15 — 수리 코드 전부 섰다 (컴파일 0 · 실측은 v5 시험 조성 몫)】 4파일:
① RemoteBuilder — 목책·채문 발자국 terrace 요청 · seatY 신설(부지 안 groundAt + 깔린 단 낯
probe, +2 상한) · 등롱/매화 실지면 착좌 · sect() 의 rise 잘림 폐기 (upper = 원장 peakY ·
계단은 오를 수 있을 때만, 못 오르면 등반로가 길) ② TerrainForge — SUMMIT_R 을 상수에서
건축 footprint 유도식으로 (ceil((17+2)/0.9) = 22 — v4 커밋 땅과 동일값·재조성 무변)
③ SectBuilder — 여섯 원형 이식: 전각(top=peakY)·사찰(축선이 땅을 따라 ±1 걸음으로 오름 —
전각 셋이 그 걸음의 높이에)·암자(seatY 침하 방어)·석조도관(산허리 매장 → 정상 착좌)·
목조검문(단 위 — 무변)·비구니원(「이웃 단 +6 상한」 폐기 — 단들이 제 실지면에) ④ CheonghaBuilder
— FLAT_PLOTS 핀 상자 4확장+1신설(민가 뒷날개·의방 부속·잡화점) · 곁담/활주/굴뚝 발치 gy 착좌
(고도장 수식 무변 — 파이썬 재현 시뮬로 42열 어긋남 소멸 검증). 전부 결정론 유지·건축의 땅
접촉은 terrace 요청 경유(TerrainSeal 정합)·커밋 땅 측량-만 계약 무변. ★남긴 결정 (B-147):
곤륜 석조도관 발자국(대각 25.5)이 본전 유도치(17)를 넘는다 — SUMMIT_R 유도를 **원형별 최대
발자국**으로 넓히면 ≈32 (모든 봉우리가 굵어진다 — 지도 설계 결정, 코드 한 줄). 닫힘은 v5
시험 조성에서 뜸·박힘 0 을 사람 눈으로 볼 때.

【Codex 검토 반영 2026-07-15 — 닫는 조건 강화 (CODEX_CHARTER_S3_REVIEW.md 축 5)】 컴파일은
결정론·봉인 계약을 증명하지 않는다: ① seatY 는 세계 상태를 읽으므로 좌표 순수 함수가 아니라
"고정 초기 상태+고정 순서에서 결정적" — **같은 원장 위 재건축 2회 대조 실측**이 닫힘 요건에
추가된다 (멱등 증명). ② "TerrainSeal 정합" 주장은 현 봉인의 고지대 사각지대(B-150) 때문에
**증명 불가** — B-150 의 눈이 선 뒤에야 그 말이 참이 된다. ③ 후속: 전역 최대 SUMMIT_R 결합을
장소별 정상 시설 footprint 입력으로 바꾸는 일이 헌장 §3 개정에 딸린다 (화산 브리프도 요구).
★사용자 결정 사항이 둘 딸린다: (a) 새 월드 재조성(v5 갈아엎기)의 시점 — **지도 설계 완성 후**
(원형 미결 B-047~050·B-094 수향·B-052 상업 등급이 그 설계의 남은 조각들이다).
(b) 갈아엎기는 되돌릴 수 없다 — 실행 전 백업 + 사용자 최종 승인 (§2 헌법).

### B-144 · **맨손 개체의 죽음이 전리품 굴림에서 터진다** — onDeath 후반(다리 사건)이 잘린다
- **상태**: 진행
- **분류**: 결함
- **단계**: P3
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/HuntingGrounds.java:1666`
- **의존**: —
- **닫는 조건**: 맨손 무장 개체(폴백 무장·이빨 짐승)의 죽음이 예외 없이 끝까지 돈다 — 다리 사건
  (beast_slain·bandit_slain)이 실린다. 수리 jar 배포 후 실측(가축/짐승 처치 → 콘솔 예외 0 + inbox 사건)
- **검증**: `server-mvt/src/main/java/com/honcheon/mvt/HuntingGrounds.java` · `run/mvt/server-console.log`
- **닫힘**: —

실사고 (2026-07-15 08:53, 실플레이): 청하현 가축(소)이 죽자 `IllegalArgumentException: 없는 계열:
맨손` × 9회. 사슬: 폴백 무장(`:729` — 미등록 개체 전부 `["맨손","범철"]`)과 짐승의 이빨(=맨손)이
전리품 굴림을 타고 `Weapons.make("맨손",…)` → `Series.of` 던짐 (맨손은 위력표의 키지 병기
계열이 아니다) → **onDeath 나머지가 통째로 죽어 다리 사건이 조용히 유실**된다. B-119(살아
있는 것은 다 맞는다) 이후 이빨-짐승 처치의 절반(전리품 확률)이 이 지뢰를 밟았을 수 있다.
수리: 전리품 가드에 맨손 제외 (맨손은 떨굴 병기가 없다) — 컴파일 확인, **배포는 플레이어 퇴장 후**.

### B-145 · 청하현 **가축이 벽에 끼여 질식사**한다 — 정원(定員)이 벽 속에서 돈다
- **상태**: 열림
- **분류**: 결함
- **단계**: P3
- **위치**: `config/hunting_grounds.yml:45`
- **의존**: —
- **닫는 조건**: 가축이 벽·건물 안에 끼여 죽지 않는다 — 자리 선정(스폰 지점)이 통행 가능
  공간을 확인하거나, 우리(축사)가 선다. 원인(스폰 위치 vs 배회 후 끼임) 판명 포함
- **검증**: `run/mvt/server-console.log` (suffocated in a wall 0건) · 사람 눈 (장터 가축)
- **닫힘**: —

실측 (2026-07-15 08:53): `소 suffocated in a wall` × 다수 (좌표 -58,88,341 · -41,88,325 —
청하현 구역 안). 가축 정원 리필이 벽 속·건물 안에 놓거나, 배회하다 끼이는 것으로 보인다.
죽을 때마다 B-144 지뢰까지 같이 밟았다 (수리 후에도 질식 자체는 남는다 — 이 항목의 몫).

### B-139 · 봉인이 **건축 없는 조성**을 물었다 — 두 계측 사이에 물리가 낀다
- **상태**: 미확인
- **분류**: 빚
- **단계**: P2  <!-- P4→P2 (2026-07-15): B-150(P2)이 의존 — 단계는 의존이 정한다. 봉인 눈 재설계의 한 몸 -->

- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/TerrainSeal.java`
- **의존**: —
- **닫는 조건**: 봉인의 before/after 가 물리 정착과 격리된다 (같은 시점 스냅샷 또는 정착 대기 후 계측)
  — 건축 없는 조성(terrainOnly)에서 「바뀐 열」이 0 이 된다. 그리고 장강수로채의 21열(판 5·돋운 16)이
  물리 잡음인지 수로채 건축의 실굴착(진짜 2계층 위반)인지 판명된다
- **검증**: `run/forge-wave1-20260715-023507.log` · `run/mvt/server-console.log` 02:46:20(jangang 21열)
  · 02:56:12(muhan 2열) · 03:06:04(soju 0열)
- **닫힘**: —

【진행 2026-07-15 — B-146 수사의 부산물】 유력 기전 판명: `TerrainSeal.seal`(TerrainSeal.java:93)
은 **평면 groundY 아래 8칸**을 잰다 — 원뿔 열에선 산 속(무변)을, 평탄 외곽 열에선 실지표를
재므로 apply_physics 정착(모래·물)이 두 계측 사이에 물린다. 장강수로채 21열도 건축 굴착이
아니라 **평면 가정이 문 물리 잡음**일 공산 — 확인법: 정착 대기 후 동시점 스냅샷 재계측
→ 0 으로 수렴하면 잡음, 남으면 실위반. 병의 뿌리가 B-146 과 같다 (groundY 평면 가정).

실측 (2026-07-15, 웨이브-1 F′): 무한은 **지형조성**(terrainOnly — `built = List.of()`, 건축 코드가
한 줄도 안 돎)인데 봉인이 "✗ 건축이 땅을 바꿨다 — 돋운 2열 (543786,413390)"이라 물었다.
**없는 손을 물었으니 계측이 오염된 것이다** — `apply_physics: true` 의 정착(모래·자갈 낙하,
물 흐름)이 before/after 두 봉인 사이에 낀 것으로 추정. 소주는 같은 조건에서 0열 — 잡음이
비항상적이다. ★장강수로채의 21열은 건축(수로채 원형)이 실제로 돌았으므로 **실위반일 수
있다** — 잡음과 구별해 재수사해야 한다. 이 판정은 Announce 전용이라 아무것도 안 막지만,
거짓 위반은 진짜 위반을 안 읽게 만든다 (B-130 과 같은 결).

발견 (2026-07-15 02:20, 웨이브-1 F′ 개시 전 점검): `run/mvt/plugins/HoncheonMVT/` 에
`terrain_built.yml`·`terrain_receipts.yml` 이 **둘 다 없었다.** 기계 입장에서는 화산의 땅도
"안 선 것" — 이대로 전맵 조성을 돌렸으면 **화산 위에 산을 또 쌓았다** (§4-밤 청하현 삼중
깎임과 같은 병). `backup-standup-20260714-182445/HoncheonMVT/` 에 hwasan 항목이 온전해
두 파일을 그대로 복원하고 02:25 재기동으로 실었다. 소실 시점은 18:24 백업 이후 ~ 01:20
재기동 사이 — §4-밤 나루 조성 사고("지형 영수증까지 덮임") 또는 그 복구 과정이 유력하나
**원인 미상**이다 (덮임이 아니라 삭제였다). 이 부류의 침묵(파일이 없으면 load 가 조용히
빈손으로 돌아온다 — `TerrainLedger.load` 는 소리내지 않는다)이 병의 공범이다.

【진행 2026-07-15 08:33】 닫는 조건 전반부 성립 — 전맵 조성 실측: 지형 관문이 hwasan 에
"terrain_state: committed · **이미 빚어진 땅이다 — 다시 빚지 않는다**"로 즉답 (forge-wave1 로그
03:28:56), `[지형] ★ 원장에서 읽었다 (땅을 안 건드렸다)` (콘솔 03:34:20). 화산 땅 무변 —
318초·1,237만 연산은 건축(도관)만. **남은 것**: 소실 원인 판명 또는 원장 실재를 재는 눈.

### B-160 · **월아산 좌클릭 자동 연속 공격** — 캐는 손의 스윙 패킷이 공격으로 읽혔다
- **상태**: 진행
- **분류**: 결함
- **단계**: P2
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/AttackRhythm.java:1`
- **의존**: —
- **닫는 조건**: 좌클릭 한 번 = 공격 한 번 · 홀드가 계열 공속(월아산 20틱)을 넘는 자동 연발을
  못 만든다 — jar 배포 후 인게임 실측: ① 월아산으로 땅·풀숲을 향해 좌클릭 홀드 → 획·전진·무공이
  연발되지 않는다 ② 몹 타격·일반 좌클릭 공격은 그대로다 (검·도 회귀 없음)
- **검증**: `tools/AttackRhythmSelfTest.java` (서버 없이 돈다 · 눈 14개 — 폭풍 재연 포함) ·
  인게임 실측 (배포 후)
- **닫힘**: —

사용자 실측 (2026-07-17): "월아산으로 좌클릭 시 자동으로 계속 공격이 나간다." **수사 결과
플러그인에는 반복 스케줄러가 없다** — 병은 패킷 층이다: ① 좌클릭을 누르고 있으면 클라이언트가
조준선의 블록을 캐며 **매 틱 스윙 패킷**을 보낸다 ② Paper 는 레이트레이스가 빗나간 **모든**
스윙을 `LEFT_CLICK_AIR` 로 합성한다 (paper-1.21.11 `ServerGamePacketListenerImpl.handleAnimate`
바이트코드 실측 — 패킷마다 `callPlayerInteractEvent`) ③ 블록이 깨진 틱의 스윙은 갓 뚫린 구멍을
지나 빗나간다. **월아산은 유일한 삽 병기**(Weapons.Base.SHOVEL)라 흙·잔디(어디에나 있는 땅)를
2~4틱에 깨고 풀포기는 즉시 깬다 — 홀드하면 "깨짐→빗나간 스윙→합성 이벤트"가 초당 여러 번 돌고
`SkillListener.onInteract` 가 전부 공격(획·전진 0.08·무공 시전)으로 읽었다. 검은 흙을 15틱+에
캐서 같은 병이 안 보였을 뿐 — 부(도끼/원목)·구(곡괭이)·겸(괭이)에도 잠복해 있었다 (이 수리가
같이 덮는다). 수리 (2026-07-17, 컴파일 0 · 자기시험 14눈 · motion/combat/defense_audit 0 ·
HudLine 26눈 · StrikeAdmission 26/26 — **배포는 플레이어 퇴장 후**): ① **채굴의 그림자**
(`SkillListener.java:1350` — 블록을 깬 직후 2틱의 허공 좌클릭은 캐는 손, 공격 입력이 아니다.
같은 마우스 버튼이라 오탐 불가능 · 몹 타격 이벤트는 이 문을 안 지나 전투 회귀 없음) ②
**병기의 박자** (`SkillListener.java:1649` — 기본 초식의 획·체술 간격 = max(등록부 4틱, 계열
공속) — 무공 경로의 `busyUntil = max(frames, swingInterval)` 과 같은 못. 판정·피해는 불변).

### B-161 · ★ 병기가 **세계를 판다** — 월아산은 삽, 부는 도끼, 구는 곡괭이다 (사람이 정해야 한다)
- **상태**: 닫힘
- **분류**: 결정
- **단계**: P3
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/Weapons.java:739`
- **의존**: B-160
- **닫는 조건**: ★ 사용자 결정 — 도구 징발 병기(부=도끼·겸=괭이·월아산=삽·구=곡괭이)가 바닐라
  `minecraft:tool` 채굴 성능을 유지하는가: ① 유지 (월아산이 네더라이트 삽 속도로 땅을 판다 —
  현행) ② 무력화 (병기는 베는 것 — `ItemMeta.setTool` 로 채굴 속도를 맨손 수준으로. B-160 의
  뿌리 하나가 마른다: 캐지 못하면 스윙 폭풍 자체가 안 생긴다) ③ 계열별 개별 결정
- **검증**: `server-mvt/src/main/java/com/honcheon/mvt/BlockCovenant.java` 실물 + 인게임 채굴 시도(안 깨져야 한다)
- **닫힘**: 2026-07-23 · `server-mvt/src/main/java/com/honcheon/mvt/BlockCovenant.java` — ★ 사용자 확정 "채굴은 없는 세상, 블럭 부수기 자체가 없음"
  (선택지 ①②③ 을 넘어선 세계 규칙). 집행: 크리에이티브(관리자) 외 모든 블록 파괴를 취소하고
  이유를 액션바로 말한다 ("세계는 부서지지 않는다"). 병기 개별 무력화가 아니라 규칙이 세계에
  걸렸다. B-160 의 뿌리(채굴 스윙 폭풍)도 함께 마른다. 블록 **설치**의 처분은 미결 —
  weapon_fitness_review.md §4 대기열. 인게임 실측은 배포 후 사람 눈.
  【추기 2026-07-23 2차】 사용자 확정 — **"블록 설치도 없습니다 RPG세상으로 진행됩니다"**:
  BlockCovenant 가 설치(BlockPlaceEvent)까지 취소한다. 플레이어는 깨지도 놓지도 않는다

B-160 수사의 부산물 (2026-07-17): `Weapons.make` 는 재질(STONE~NETHERITE 삽·도끼·곡괭이·괭이)의
`minecraft:tool` 컴포넌트를 그대로 두어 병기가 도구 노릇을 한다 — 신병 월아산 = 금삽(채굴 속도
12, 가장 빠르다). 무협 병기가 땅을 파는 것은 세계관·채집 경제와 부딪히나, 도구를 따로 못 사는
현 경제에선 기능이기도 하다 — 코드로 못 푸는 저울질이라 결정 항목으로 올린다.

【추기 2026-07-23 · B-172】 월아산은 삭제되고 봉이 대체했다 (삽 베이스 승계) — 이 결정의 대상은
이제 **부(도끼)·겸(괭이)·봉(삽)·구(곡괭이)**다. 물음 자체는 그대로 살아 있다.

### B-172 · 월아산 **삭제 → 봉 대체** — 계열·디자인·등록 전부 (사용자 확정)
- **상태**: 닫힘
- **분류**: 결정
- **단계**: P3
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/Weapons.java:236`
- **의존**: B-162
- **닫는 조건**: 계열 월아산이 등록부·코드·팩에서 사라지고 봉이 그 자리(삽 베이스·간격 4.0m)를
  받는다. 이미 나간 옛 아이템(청하현 병기대 등)이 깨지지 않는다
- **검증**: `python3 tools/lint_config.py` + `python3 tools/combat_audit.py --lint-only` + 팩 dispatch 의
  `weapon/wolasan_*` 이주 별칭이 봉 모델을 가리키는지 (`resourcepack/assets/minecraft/items/stone_shovel.json`)
  (★2026-07-25 좁힘: combat_audit 시뮬은 눈의 v2 갱신 후 B-177 밸런스 실측을 겸한다 — 그 위반은
  이 항목(계열 등록)의 회귀가 아니다. 계열 정합은 린트가 담당)
- **닫힘**: 2026-07-23 · `python3 tools/lint_config.py` → 오류 0·경고 0 + `python3 tools/combat_audit.py`
  → 위반 0건 + `resourcepack/assets/minecraft/items/stone_shovel.json` 에 wolasan→bong 이주 분기 실물

사용자 원문: *"월아산은 삭제 봉으로 변경 디자인도 봉으로 변경."* 집행:
- **계열**: `Series.월아산` → `Series.봉`("bong") — 삽 베이스·공속 1.0/s·간격 4.0m 승계 (수치 불변).
  powerKey "봉"과 이름이 한 벌이 되어 B-162 가 함께 닫혔다. 밸런스 변동 0 (위력은 줄곧 봉:3 이었다)
- **이주**: `Series.of("월아산")` → 봉 별칭 (옛 PDC·명령 입력) + 팩 dispatch 에 `weapon/wolasan_*`
  → 봉 모델 별칭 5분기 — 이미 나간 실물의 회수 없이 끝난다
- **디자인**: 아이콘·3D 스펙을 봉(날 없는 장대 — 온몸이 자루, 양끝 쇠테)으로 재작성. 부위 등록
  월아·삽날·삽목·달목 폐기 → 머리테 신설. 개방 타구봉 명병 베이스도 잠정 spear → bong 제자리
- **잔재**: 옛 wolasan 팩 산출물 20파일 git rm · registry 14→13계열

### B-173 · ★ 병기 적합성 **점검 절차** 신설 — "무공을 실을 만한 무기인가"를 재는 손 (사용자 지시)
- **상태**: 열림
- **분류**: 결정
- **단계**: P3
- **위치**: `docs/design/weapon_fitness_review.md`
- **의존**: —
- **닫는 조건**: 절차 정본이 서고(§1 세 물음·§3 절차), 첫 대기열(§4 — 부·구·겸 채굴 B-161 ·
  부·겸·구·권갑 죽은 모션 행 · 활/암기/중병기 실물 부재 · 봉 모션 실측)이 사용자 결정 회차를
  한 바퀴 돈다
- **검증**: `docs/design/weapon_fitness_review.md` §4 표의 각 행이 결정(유지/수정/삭제)을 얻었는가
- **닫힘**: —

사용자 원문 (2026-07-23): *"무공을 사용할만한 무기가 아니다 싶으면 조금 수정하는 점검의 절차를
가지자."* 첫 집행 선례가 B-172 (월아산→봉)다. 절차 정본은 위치의 문서 — 세 물음(무공이 실리는가
· 등록부가 한 벌인가 · 몸이 세계와 충돌하지 않는가)과 최소 처방 사다리(ⓐ이름→ⓑ통합→ⓒ대체→ⓓ삭제).

【1회차 결정 (2026-07-23 새벽 · 사용자)】 ① 채굴 — **"채굴은 없는 세상"** (B-161 닫힘 ·
BlockCovenant) ② 부·겸·구·권갑 죽은 모션 행 — **보류** (현행 거동 유지, 대기열 잔류)
③ 실물 없는 계열 — **활만 먼저 착수** (B-174), 암기·중병기는 계속 대기.
잔여 대기열: 봉 모션 실측 · 블록 설치 처분 · 겸 농기구 서사.

### B-174 · 활 계열 **실물 착수** — 궁술 무공은 있는데 활이 없다 (사용자 확정: 활만 먼저)
- **상태**: 진행
- **분류**: 미완
- **단계**: P3
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/Weapons.java:226`
- **의존**: B-173
- **닫는 조건**: 활이 혼천 병기로 선다 — ① `Series.활` (Base.BOW 신설 · powerKey "활"=3 —
  등록은 이미 combat.yml 에 있다) ② 화살 피해가 판정층을 탄다 (skill_mechanics ranged —
  사거리 40 · 거리를 위력으로 바꾼다) ③ 등급 5계보 발급 경로 (`/혼천 병기 활 …`)
- **검증**: `python3 tools/combat_audit.py` + 인게임 — 활 발급·사격·판정 로그
- **닫힘**: —

★ 팩 함정을 미리 적는다: 바닐라 활의 아이템 정의는 **당김 상태 합성 정의**다 (pulling 분기) —
bundle 을 flat select 로 덮으면 세계의 모든 번들이 고장나던 것과 같은 지뢰 (dispatch.py 서두).
팩 텍스처는 이 합성을 보존하는 정의를 따로 설계해야 한다 — 1단계는 **팩 없이 바닐라 활 그대로**
(modelId null → item_model 미부착 폴백이 이미 그 길이다).

【추기 2026-07-23 2차 · ★방향 수정】 사용자: **"활을 안 썼으면 좋겠어 — 다른 별도의 무기로,
무기 디자인을 활처럼 만들 예정이고 모션도 새로 생성할 예정"** — **바닐라 활 자체를 폐기한다.**
- 살아남는 층: 사선 이펙트·판정 문법 전부 (ranged_fx 등록부 · 먹줄/주행 렌더러 · 벽=엄폐 ·
  도달 틱 판정) — 어느 실물에 실리든 그대로 쓴다
- 갈리는 층: ① 실물 — Base.BOW 폐기 → 활 형상의 별도 무기 (팩 3D · 디자인은 사용자/디자이너
  예정) ② 입력 — EntityShootBowEvent(바닐라 당김) 훅은 **시안 계측용 임시**로 격하, 새 무기의
  몸짓(신규 모션)으로 대체 예정. 이름·베이스 아이템·몸짓은 사용자가 정한다 (등록제 — 묻고 기다린다)
- 미결정 잔여: 사선의 그림 기본 틀 (먹줄 vs 주행 — 1회차 아티팩트 결정 대기. 무기와 독립)

【추기 2026-07-23 · 방식 확정 + 1단계 구현】 사용자: **"활도 스킬 모션으로 공격 이펙트로 활을
쏘는것 처럼 표시 (모든 원거리 무기 전부 이펙트로 표현)"** — 바닐라 화살 엔티티를 쓰지 않는다.
구현(`RangedShot.java`): 발사 이벤트 취소 → 등록부 사선(射線) — 벽이 끊는 레이(엄폐=물리) →
첫 생명체에 `damage()` 재진입(판정 일원화) → weapon_styles.활 이펙트·소리. 사거리 = ranged.활
range 40 × 당김 힘. 탄약(화살)은 수동 소모. `Series.활`(Base.BOW · modelId null · 근접 보정 없음
— min_range "맨손 취급" 집행). **잔여**: interrupt(당김 중 피격) · cover_rule 수치 보정 ·
이동 속도 절반(엽호궁술) · 암기 계열 · 팩 텍스처. 닫기 전 인게임 실측 필요.

### B-162 · 월아산의 **등록부 이름이 갈라져 있다** — PDC 는 봉, 등록부는 월아산 (죽은 등록 5벌)
- **상태**: 닫힘
- **분류**: 빚
- **단계**: P3
- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/Weapons.java:236`
- **의존**: —
- **닫는 조건**: 계열 이름이 한 벌이 된다 — `Series.월아산.powerKey`("봉") 가 등록부의 "월아산"
  과 정렬되거나(위력 3→4 변동은 사용자 승인), 등록부의 월아산 전용 항목들이 봉으로 통합된다.
  같은 병이 든 부("중병기")·겸("단검")·구("검")·권갑("맨손")도 함께 판정. 어느 쪽이든
  combat.yml weapon_power 의 "14계열" 주석과 실제 배선이 일치한다
- **검증**: `python3 tools/combat_audit.py --lint-only` · `config/skill_motion.yml` 의 월아산 항목이
  실제로 읽히는지 (또는 제거됐는지) 코드 대조 (★2026-07-25 좁힘: combat_audit 시뮬은 눈의 v2 갱신
  후 B-177 밸런스 실측을 겸한다 — 그 위반은 이 항목(계열 이름 한 벌)의 회귀가 아니다)
- **닫힘**: 2026-07-23 · `python3 tools/combat_audit.py` → 위반 0건 + `python3 tools/lint_config.py`
  → 오류 0 · 경고 0 — **봉 통합 방향으로 닫혔다** (사용자 확정 「월아산은 삭제 봉으로 변경」 · B-172).
  skill_motion 의 월아산 죽은 등록 5벌은 삭제됐고(살아 있던 봉 행이 정본 — 인게임 거동 불변),
  combat.yml 주석은 13계열로 정정. 봉은 이제 name==powerKey 라 이 병이 재발하지 않는다.
  ★ 함께 판정한 부·겸·구·권갑: **같은 병이 확인됐다** — 모션 조회는 `weapon_class`(powerKey)
  를 쓰므로 (SkillListener weaponClassOf → KEY_CLASS) skill_motion 의 부·겸·구·권갑 자기 이름
  행이 죽은 글자다 (부→중병기·겸→단검·구→검·권갑→맨손 행이 실린다). 밸런스 변동이 걸려
  일방 수정하지 않는다 — **B-173 점검 대기열로 이관** (docs/design/weapon_fitness_review.md §4)

B-160 수사의 부산물 (2026-07-17): 월아산 병기의 PDC `weapon_class` 는 `Series.powerKey` = **"봉"**
(Weapons.java:667) — 그래서 combat.yml `weapon_power.월아산: 4` (등록 주석 "14계열"에 명시)가
아니라 `봉: 3` 이 실리고, skill_motion.yml 에 공들여 적힌 월아산 전용 등록 5벌(§weapon_styles 획
· §swings 두께/기울기 · §body 웅크림 대본 · §basic_strike 프레임 [5,3,9] · §heavy_classes)이
**전부 죽은 글자다** (실제로는 봉의 값이 실린다). 부·겸·구·권갑도 같은 방식으로 다른 계열 이름에
얹혀 있다 — 위력·모션 밸런스가 걸려 있어 일방 수정 대신 장부에 올린다.

---

### B-163 · 무기 오라 — 검·도 파일럿을 나머지 계열·명병 문파색으로 넓힌다
- **상태**: 진행
- **분류**: ★세계
- **단계**: P3
- **위치**: `config/skill_motion.yml` (weapon_aura) · `server-mvt/src/main/java/com/honcheon/mvt/SkillListener.java:4004` (weaponAuraDropped · weaponAuraHeld · spawnWeaponAura)
- **의존**: —
- **닫는 조건**: (1) 창·권갑·단검·부·겸·월아산·구 계열에 악센트색(weapon_aura.series[…].ink)이
  등록되어 손에 들면 오라가 서린다. (2) 명병 문파색(weapon_aura.myeong[hwasan·jeomchang·…])이
  등록되어 계열색을 덮는다 (지금은 `myeong: {}` — 빈 상태라 명병도 계열색으로 돈다).
  (3) dust 결정 조각이 **팩에서 청록 결정 텍스처**로 다시 그려진다 (지금은 바닐라 dust 원형 — 팩의 몫).
- **검증**: `python3 tools/motion_audit.py` (위반 0) · 인게임 `/혼천 병기 도 신병` 지급 후 손에 들고
  주위 파티클 소용돌이 목격 (검=옥/청록 · 도=혈/진홍) · 등급 사다리(범철 없음 → 신병 또렷 → 마병 격렬) 대조
- **닫힘**: —

1차(2026-07-19): 무기 오라 틱 루프 신설. **검·도 계열 · 전 등급 사다리**가 배선됐다 (파일럿).
계열색이 등록된 검(옥)·도(혈)만 오라가 돈다 — 나머지 7계열과 명병 문파색은 등록부 자리만 비워 두었다
(위 닫는 조건). 순수 VFX(판정 불변) · SkillHud 예산 게이트를 그대로 탄다 · 전역 스위치
`weapon_aura.enabled`. 결정 조각=dust(계열 악센트색) · 흰 반짝이=end_rod.

2차(2026-07-19 · 사용자 인게임 실측 반영 — 두 수정):
- **★떨어진/세워진 아이템 오라 신설** (`SkillListener.weaponAuraDropped`): 영상은 held 가 아니라
  **월드에 세워진/떨어진 검** 둘레를 파티클이 돈다. 청크 로드된 dropped `Item`(+ `ItemDisplay`,
  우리 VFX `honcheon:vfx` 제외) 중 혼천 병기를 매 주기 순회해 **아이템 월드 위치**를 궤도 중심으로
  수직 기둥 소용돌이. 거리 컬링(`anyPlayerWithin(cull_beyond)`)·볼 눈 없는 세계 스킵·예산 게이트.
  스위치 `weapon_aura.dropped` · `include_displays`.
- **held 위치 수리** (`weaponAuraHeld`): 눈앞 정면(forward 0.95)이 아니라 **실제 렌더 손 자리**
  (눈에서 앞 0.45 · 오른쪽 0.32 · 아래 0.30 — 우하단)로 옮겨 1인칭 정면을 안 가린다. 스위치 `held`.
- 공통 궤도 로직은 `spawnWeaponAura(center,u,v,w,item,wa)` 로 추출 (held=시선수직평면·시선축,
  dropped=수평평면·수직축). 리터럴 0(등록부 경유) 유지 · 판정 불변.

3차(2026-07-19 · 사용자 인게임 실측 — 세 수정 한 회차):
- **파티클 크기↓ + 종류 판단**: `shard_size 0.7`/`spark_size` 노출(뭉치 제거). `SkillHud.emitSized`
  (DustOptions 색은 등록 먹빛, 크기만 호출자) 신설. **판단: 기본 dust 유지** — 레퍼런스 결정은
  계열색(검=청록·도=진홍)을 띠는데 `electric_spark`는 색을 못 입혀 도/마병 진홍을 못 그린다.
  각진 결정 모양은 팩 리텍스처 몫. `electric_spark`는 config 선택지로 열어 둠(색 포기).
- **held 이동 끊김 수리**: held 를 `held_interval_ticks 1`(매 틱) 발행 → 파티클이 검에서 벗어났다
  붙는 gap 제거. 촘촘해진 만큼 발행당 결정 수를 `held/interval` 비율로 줄여 총량 균형(예산 유지).
  dropped/전시(정지)는 기존 성긴 주기(interval 3) 유지. (dust 수명은 Bukkit API 로 못 줄여
  velocity-carry 도 dust 는 무시 — 매 틱 발행이 실효 수리다.)
- **★큰 병기 전시대 신설** (`/혼천 병기전시` · `SkillListener.weaponStandCommand`): 든 병기를 앞
  지면에 **스케일 3.5배 직립 ItemDisplay** 로 세운다(빈손 재호출=회수). `setPersistent(true)` 라
  재기동해도 병기를 안 잃는다(세계 유일 신병 보호). 표식 `honcheon:weapon_stand`(+owner) — VFX 표식과
  별개라 유령 청소 안 걸리고, dropped 순회가 `aura_scale 2.8` 로 큰 검을 감싸 오라를 두른다.
  회수는 주인만. config `weapon_stand`(scale·rot_*·rise·retrieve_radius·aura_scale). **회전(rot_*)은
  인게임 실측 다듬기 대상** (검 직립 각).

4차(2026-07-19 · 사용자 결정 — 두 수정):
- **★떨어뜨린 병기 자동 확대** (`weaponAuraDropped` ① · `dropped_display` 절): 바닐라 dropped Item 은
  렌더 크기 고정이라 작다 → 혼천 병기를 버리면 작은 아이템을 **치우고**(`item.remove()`) 같은 자리에
  **큰 ItemDisplay(scale 2.5)** 를 세운다(전시대와 `spawnWeaponDisplay` 공유). 병기 안 잃음:
  디스플레이가 실물 병기를 품고 `setPersistent(true)`(재기동 생존) · **줍기**(반경 1.6m 다가가면
  인벤토리 복원 · 줍기지연 1.5s) · **수명**(벽시계 `weapon_drop_born`, `lifetime_seconds 300` =
  바닐라 despawn 5분 정합 · **0 이면 안 사라짐** — 귀한 병기 보호 설정). 표식 `honcheon:weapon_drop_display`
  (VFX·수동전시대와 별개). 성능: 혼천 병기만·throttle(3틱)·거리 컬링·볼 눈 없는 세계 스킵·청크 로드
  엔티티만. 유령 없음(실물 품고 줍힘/수명 관리).
- **helix 세로 비례 수리**: helix 를 대상 크기(aura_scale)에 비례(코드가 이미 `helix × radiusScale`).
  base `helix` 0.9→**0.15**(held·작은 대상 낮게) — 큰 전시/드롭은 aura_scale 배로 커진다. 0.9 가
  held 에 너무 높이 솟던 것을 고쳤다. 큰 기둥을 더 높이려면 `*.aura_scale` 를 올린다.
- ※ 조율자 config 유지: 신병 shards 10·마병 13·rot_z 90. **회전(rot_*)·수명·스케일은 인게임 다듬기 대상.**

### B-175 · ★★ 클라이언트 모드팩 전환 — "전체 수정 느낌" (사용자 확정 2026-07-23)
- **상태**: 진행
- **분류**: 미완
- **단계**: P1
- **위치**: `docs/design/modpack_transition.md`
- **의존**: —
- **닫는 조건**: Phase 1 이 선다 — ① 모드 핀 목록 확정(Fabric 1.21.11 · Sodium/Iris/Xaero/
  Emotecraft/PAL 등) ② 로컬 Prism 인스턴스에서 라이브 접속 실측 ③ Emotecraft Paper 플러그인
  탑재·동기화 확인 ④ 배포물(.mrpack) 생성. Phase 2(모션)·3(UI)은 별도 항목으로 분가한다
- **검증**: 모드 클라로 라이브 접속 스크린샷 + 서버 로그(Emotecraft 채널 등록)
- **닫힘**: —

한월 실측(작업물/레퍼런스/한월_20260723)이 방향을 정했다: 서버는 Paper 그대로, 모드는 전부
클라 사이드다 (Emotecraft Paper 플러그인이 선례). 팩 게이트 → 모드팩 게이트 개정은 Phase 2
핸드셰이크가 생겨야 기술적으로 가능하다. ★사용자 결정 대기 5건(D1~D5)은 설계 문서 §5.

### B-176 · 칭호(稱號) 시스템 — 세계-반응 위엄 + 소문 연계 (사용자 설계 2026-07-23)
- **상태**: 진행
- **분류**: 미완
- **단계**: P2
- **위치**: `docs/design/growth_v3_feel.md §3`
- **의존**: B-135 (성장 v3) · 소문 시스템
- **닫는 조건**: 칭호가 선다 — ① 획득(승급 자동·사건 마크·퀘스트→소문 연계: 매화검·무림맹주·산채정리) ② 남도 보는 명패 표시 + 본인이 달 것 선택 ③ ★힘은 세계-반응으로만 (위세→npc_combat morale · 경제 · 친화 — 순수 전투 능력치 금지, 헌법 "명성=세계 반응" 보존) ④ 중첩 상한
- **검증**: `python3 tools/lint_config.py` + 인게임 (칭호 획득·명패·위세/경제/친화 효과)
- **닫힘**: —

★사용자 확정: 칭호는 겉치레이자 일부는 의미를 담되, **그 힘은 순수 전투 능력치가 아니라
세계-반응(위세·경제·친화)으로만** 흘린다 (헌법 유지 — 판정 공식·하네스 불변). 퀘스트→소문→칭호
삼각형이 한 몸. 명패에 뜨고 남도 본다. 중첩 상한은 대표 칭호 1개 전효과 제안 (확정 대기).

진행 (2026-07-24 · 첫 조각 — 경지 칭호): ★사용자 확정: **칭호 = 경지 이름 그대로** (별도
이름표 없음 — cultivation_stages 가 곧 칭호 등록부다 · 범인은 한월 「평민〈칭호없음〉」 문법으로
칭호 없음). 배선: 「남도 본다」 = updateSidebar 의 보는-사람 스코어보드에 온 몸의 팀 접두
`[경지]` (5초 폴링이 전파 주기 — 승급 반영 ≤5초) + 사이드바 제 줄에 Lv 병기. 힘은 0 (표시만 —
세계반응 3갈래·소문 연계·본인 선택은 다음 회차: 소문 시스템 의존). 증거: 빌드 ✓ · 라이브 배포.

### B-177 · ★★ 전투 판정 재설계 v2 — 2d6 제거·공방 도입 (사용자 설계 2026-07-24)
- **상태**: 진행
- **분류**: 결정
- **단계**: P2
- **위치**: `docs/design/combat_resolution_v2.md`
- **의존**: B-135 (원장 → 공격력)
- **닫는 조건**: 전투가 결정론 공방이 된다 — ① 명중 = 획 히트박스(band_hit, 판정 없음) ② 피해 = max(1, 공격력−방어력) 격차 압도(하한1·상한없음) ③ 치명타 확률(기본0+감각+무기별)·배수 ④ NPC 방어력(갑옷+경지 유도) ⑤ 전투 경로에서 2d6·auto·극단주사위 제거 (TRPG 경로는 2d6 유지) ⑥ 몸짓=방어력/회피 기하. 수치 combat.yml 등재+사용자 승인
- **검증**: `python3 tools/lint_config.py` + 인게임 (격차별 피해·크리·방어력·헛손질 0)
- **닫힘**: —

★사용자 확정: 전투로 주사위는 이상하다 — 명중은 획이 닿으면 무조건, 피해는 공격력 vs 방어력
(격차 곧 압도), 치명타만 확률(감각+무기별·기본0). 판정 개념 제거. TRPG(서장·퀘스트)의 2d6은 유지.
원장 → 공격력 = 몸으로 느끼는 성장(growth_v3_feel §2)의 전투 실현. 미결: 크리 배수(×1.5 제안).

진행 (2026-07-24 · 2단계 코드 배선 완료 — `combat_v2.enabled: false` 라 라이브 무영향):
- 결정 회차 (사용자): 공격력 = 무기+숙련+능력치+격 **4항** (무공 위력표 제외) · 경지 격차 ±2·내공
  고갈 −2 **제거** · 평타/NPC 무기 항 = **바닐라 피해 + 능력치 + 격**. combat.yml combat_v2 주석에 등재
- 배선: `SkillEngine.strikeV2`(공방·크리·개안 절반) + `CombatV2` 등록부 적재 + `critChance/critMultiplier` ·
  `SkillListener` 세 판정길(resolve·basicJudged·npcStrike) enabled 분기 — v2 는 roll2d6·태세 대립·
  이중 경감 없음 (soak·갑옷·체력 파생 = `defenseV2` 방어력 한 곳) · 크리 = 액션 RNG · v2 눈(eyeRollV2) ·
  등급 문법(성공/대성공)은 판정 등록부 재사용(`JudgmentEngine.tierById` + core 테스트 1눈)
- 검증: `:server-mvt:jar` 빌드 ✓ · `:core:test` ✓ · combat_audit·defense_audit·lint_config 위반 0
  (v1 불변 — enabled:false)
- ★3단계 개시 (2026-07-24 · 커밋 768d208): **라이브 점화됨** — `enabled: true`, 기동 경고 실물 확인 ·
  오류 0 · 팩 sha1 불변. 테스트 서버(25566)도 같은 상태. 실측 = **일상 플레이 관찰** (사용자: 평가
  포인트 잡기 어려움 → 체감 한 줄 수집 방식: 한 방/긁힘/크리 빈도). 끄기·튜닝은 config +
  `/혼천 모션 재적재` 로 무재기동
- ★남은 빚: 【제안】 수치 튜닝 (per_body·크리표·상한) + ~~**combat_audit·defense_audit 의 기대 모델을
  v2 로 갱신**~~ (✓ 2026-07-25 아래) + 장비 크리 슬롯(equipment.yml) 별도 등재 + PvP directOpposed
  거취 (설계서 미결 5) + ★growth_audit 도 자체 2d6 산술(v1)이다 — 다음 조각 (이번 회차에서 발견)
  + ★**오의 경로도 v1 산술이다** (2026-07-26 오의 분석에서 발견): planUltimate 주석 공식이
  「무공 위력+⌊마진/2⌋」(v1) · 오의 격돌(clash)은 2d6 실행력 경합 · v2 에선 오의 기본 위력(6)이
  격 보정 칸에 실려 살고 무공 위력 항은 죽는다 — 오의의 v2 재설계는 별도 결정 회차

★진행 (2026-07-25 · 눈의 v2 갱신 — combat_audit·defense_audit 기대 모델 v1→v2):
- **디스패치 = 엔진과 같은 문**: `strike()`/`expected()` 가 `combat_v2.enabled` 를 읽어 갈아탄다
  (v1 산술은 복귀 스위치 보존 — enabled:false 면 그대로 v1). v2 산술은 SkillEngine.strikeV2 ·
  SkillListener.defenseV2 · critChance/critMultiplier 와 같은 줄: 공격력 4항(무기+숙련+능력치
  병기 축+격) · 방어력 = 갑옷 + floor(per_body×체력) + 태세 경감 · 피해 = max(1, 공−방) × 크리 기대
- **v1 전용 개념 재표적**: 고갈 판정 −2 → 다운캐스트(격 상실)로 · 무기 등급 judgment_bonus →
  전투 밖(감당 격만 — [A] v2 절) · 죽은 선택지의 「무공 위력 0」 사유 철회(v2 는 위력표 자체를 안
  읽는다) · 협공 판정 보정 → 슬롯+강제 태세만 · 부상 판정 페널티 제거 (실릴 판정이 없다)
- **눈 신설**: combat_audit `lint_combat_v2`(능력치 3종·크리 무기 표 2종 실재 대조 + per_body>0) ·
  defense_audit 배선 ⑧(strikeV2/defenseV2/critRollV2 실재 + v2 이중 경감 가드 — 스위치 양팔 검사)
- **눈을 시험하는 눈 신설** (`--selftest` — 이 두 감사엔 없었다): combat 9/9 · defense 6/6
  (뮤테이션 프로브 — per_body·크리표·weapon_power·공격 축 이사·관통 회귀 감응 + 음성 대조
  technique_power + enabled 왕복)
- ★★**결과 — 눈이 처음으로 v2 세계를 쟀다: combat_audit 위반 7건 실체** (동경지 TTK 2합 =
  전투 증발 · 3인 협공 1합 · 매복 = 처형 · 일류가 졸개 5인 소탕 못함 · 격 TTK 전 경지 밴드 이탈).
  「위반 0」이 거짓이었음이 확정 — 이 7건이 곧 수치 튜닝 회차(【제안】 per_body·크리표·격 위력)의
  실측 기반. **수치는 사용자 승인 대상이라 이 회차에서 손대지 않았다.** defense_audit 은 위반 0 ·
  경고 2(회피=기하 — 해석 모델 밖임을 정직 고지). 회귀: growth/gyeonggong_audit 0 · lint 0
- B-005 검증 좁힘 (`--lint-only`) — 닫힌 항목의 증거가 B-177 밸런스에 물리지 않게 (그 항목 참조)

### B-178 · 뿌리내림 과정 — 본토 튜토리얼 (B-109 ①마디의 구체화 · 사용자 설계 2026-07-24)
- **상태**: 진행
- **분류**: 미완
- **단계**: P1
- **위치**: `docs/design/tutorial_rooting.md`
- **의존**: B-109 (①마디가 자리) · B-121 (섭구 = 첫 손) · B-135 (레벨 거울이 감지의 눈)
- **닫는 조건**: 출도한 새 몸이 흑수나루에서 기존 NPC 릴레이(섭구→장쇠→산길→곽진)로 생계의 문법(상점·사냥·XP→레벨업→배분·수련·운기·승급 조건)을 걷는다 — ① 정거장 7 등록부(`config/tutorial.yml`) ② 사이드바 트래커 한 줄(기초 과정만 카운터 — personal_story §6.4 부분 개정) ③ 문을 잠그지 않음·행위가 보상·소급 인정 ④ 신규 캐릭터 실기동으로 흐름 확인
- **검증**: `python3 tools/lint_config.py` (안내인 키·감지 어휘 대조) + 신규 캐릭터 실기동 (사람 눈)
- **닫힘**: —

★사용자 확정 8건 (2026-07-24 — 표는 설계서 §1): 튜토리얼 = B-109 안내선으로 갈음 · NPC
안내인 릴레이 · 한월식 상시 트래커(★personal_story §6.4 「화면 점유 트래커 없음」 부분 개정 —
기초 과정 한정) · 내용 4종(사냥+XP+배분·상점·수련·운기·승급 조건) · ①마디=튜토리얼 ·
기존 NPC(새 NPC 0 — 섭구는 이미 등록·대사까지 있음) · 기초 과정만 카운터 · 행위 자체가 보상
(별도 지급 없음 — B-109 §6.2 유지).

진행 (2026-07-24 · 배선 완료 — 전부 라이브): `config/tutorial.yml` 정거장 8 (마중→채비→
첫_사냥3→첫_레벨→배분→수련→운기→다음_벽 · 문장 전부 【제안】) + `TutorialGuide` (상태 =
PlayerLedger.tutorial 영속 · 멱등 계수 · 소급 거울: 레벨≥2 → 첫_레벨, 포인트 0 → 배분) +
훅 6곳 (섭구/곽진 우클릭=TradeListener · 장쇠 구매=WeaponShop · 처치=HuntListener ·
레벨/배분 델타=syncSheet · 수련 성공/운기 시도=MvtCommand — ★운기는 개화 전 거절도 가르침:
"단전이 비어 있다"가 곧 예고, 입도진 「안 되는 몸」 함정 회피) + 사이드바 트래커 한 줄
(첫 미완 정거장 · 끝나면 줄 소멸 — 배우는 동안만 자). 곽진 승급 안내 대사 등록부 이관
(cultivation_stages 요건·자격 레벨 10 인용 — 수치 발명 0). 눈: `tools/tutorial_audit.py`
신설 (등록부↔훅 표류 ①유령 훅 ②죽은 정거장=영영 안 끝나는 튜토리얼 · --selftest 심은 표류
2/2 검출·거짓 짖음 0) — 위반 0 · lint 0 · 빌드 ✓ · 배포 2회 (기동 고지 「정거장 8 (켜짐)」 ·
오류 0) · 안내인 3인 실존 RCON 확인 (강제 적재 후 @e — 섭구/장쇠/곽진 각 1). ★남은 것:
신규 캐릭터 실기동 (사람 눈 — 체감 수정 회차 예약) + 문장 빨간펜.

진행 (2026-07-24 · ★나루 재설계 — 순수 문지방, 사용자 확정 3건): 계기 = "나루에서 불필요한 것
제거 · 목적 파악 후 재설계". 파악: 나루의 환원 불가능한 목적 = 대기실·접합·서장(걷기가 붓 대기
흡수)·의식 4종 — 가르침 5과는 본토 안내가 없던 시절의 보완물이었고, 격·경공은 v3 새 몸(전원
범인)에게 **아무도 못 하는 예고 전용**이었다. 결정: ①순수 문지방(맞이→나루만) ②격·경공 예고
완전 제거(gap 메움) ③지금 바로 반영. 이관: 태세→본토 「몸짓」 정거장 (★흘리기·회피 2문만 —
본토에 방패 취득 경로가 없어 막기는 예고. 사용자: "굳이 방패가 필요한가" — 판매 보류 · 등록 시
1500문 의향) · 손→첫 사냥 흡수 · 수련·시트 발판→본토 중복 제거. 지급품(목검·방패) 폐지 —
빈손으로 건너 장쇠에게 산다. 정거장 8→**9** (몸짓 신설 — Sneak/Sprint 이벤트 · 몸짓별 1회 표식).
눈 갱신: antechamber_audit 과제별 검사를 「있을 때만」으로 (조기 return 이 사람 보호·코드 검사를
같이 감던 병도 수리) + selftest 뮤테이션 재정비 (표적 소멸 23건 폐기·재표적 — ㊲ B-170 표류로
죽어 있던 조각도 수리) — **눈의 시험 53/53 · 놓침 0** · 정적 감사 위반 0 · tutorial_audit 9/9 ·
lint 0. 배포: 나루 월드 백업(run/backup-20260724-150908-나루월드-순수문지방개정) 후 제거 —
첫 입장 때 새 등록부로 재생성 (★§4-밤 사고의 나루 심부 마을 잔해도 이 재생성으로 청산) ·
기동 「정거장 9 (켜짐)」 오류 0.

### B-187 · ★★ 시작의 다원화 — 소속별 시작 마을 (구파일방 산문·세가 장원 · 사용자 확정 2026-07-25)

★진행 (2026-07-31 — 확정 ① 뽑기 풀의 눈): `python3 tools/tutorial_audit.py` 에 시작 마을
뽑기 풀 검사 신설 — mvt_start.playable 의 모든 마을이 start_regions status=완비여야 한다
(스텁 혼입 = 허공 낙하 방지) + 절 이름 상실 가드(공허 통과 방지) + selftest.
**start_regions 의 첫 독자가 생겼다** (그전까지 의도의 기록 — 독자 0명이었다).
운명 뽑기 가시화 ✓ (2026-07-31 — 탄생 임베드에 「시작 마을: X — 운명이 그리 정했다.
마을은 문파를 권할 뿐, 소속은 그대의 것이다」). 남은 것: 장원 시안 빨간펜 6건(사용자) · 스텁 실물
(실물이 서면 playable 에 더하는 순간부터 사람이 거기서 태어난다 — 코드 무변경 · 눈이 지킨다).
★2026-07-31 사용자 결정: **오대세가 입문 루트·장원은 미룬다** — 청하현×화산파 한 곳을
끝까지 완성하고 테스트한 뒤 다른 세력 입문을 본다 (한 곳 완성 → 실측 → 확장의 리듬).
- **상태**: 열림
- **분류**: 결정
- **단계**: P1
- **위치**: `config/player_creation.yml start_regions` (이미 다중 시작 구조 — 청하현만 「완비」)
- **의존**: B-179 (서장 결말이 시작지를 향한다) · B-147 (3계층 헌법 — 지도가 정본)
- **닫는 조건**: 배정(생성)에 따라 시작 마을이 갈린다 — ① 구파일방 각 산문·오대세가 각 장원의
  시작지(지도→땅→건축→앵커) ② 서장의 끝 = 제 시작지 (청하현 고정 폐지) ③ 각 시작지의
  뿌리내림 대응(안내인 릴레이 등가물) ④ 출도 좌표 배정 배선 ⑤ 배정 방식 등록부화
- **검증**: 각 시작지 신규 캐릭터 실기동 (사람 눈) + antechamber/tutorial_audit 재표적
- **닫힘**: —

★사용자 확정 (2026-07-25 밤): 「서장의 끝은 청하현이 아니다 — 배정에 따라 시작 마을이 여러 개
(구파일방에 맞는 각각의 시작 위치) · **세가는 세가대로 진행**」. 맥락: 세가 서장이 자꾸 강호로
떠나는 이야기인 것에 대한 개정 — 세가 내 스토리로 진행하고, 가전 무공 학습의 서사 통로도 산다.
★기존 등록부가 이미 이 방향이다: start_regions (청하현 완비 + 스텁 3 + funnel_rule) ·
factions.yml 구파일방 10 + 오대세가 5. 「단일 시작」은 설계가 아니라 구현 단계였다.
세가 쪽 결은 B-182(세가 실명 루트 · P2)와 맞물린다 — 의존이 아니라 동행 (장원 파일럿 때 합류).

★계율 (침묵하는 실패 방지): **서사가 세계보다 앞서가면 그것이 거짓말이 된다** — 시작지 실물
(지도→땅→건축)이 서기 전에 서장 문장을 「장원에서 시작한다」로 바꾸지 마라 (몸은 아직
청하현에 떨어진다). 문장과 좌표는 한 회차에 함께 간다.

★확정 4건 (2026-07-25 밤 — 위 미결의 답):
① **배정 = 심리 테스트에서 시작 마을만 운명으로 뽑힌다.** 소속은 고정이 아니다 — 마을은
   그 마을에 적합한 문파를 **추천만** 한다. **청하현 고정(유지) 없음** — 청하현도 뽑기 풀의
   하나일 뿐이다.
② **파일럿 순서 = 하북팽가 장원 → 화산 산문.**
③ **지도 시안은 Fable 이 【제안】으로 만들고 사용자가 빨간펜한다.**
④ **C안(가전 무공 입장권) 선행 확정.**

진행 (2026-07-25 밤 · C안 배선 + 하북팽가 장원 시안 1호):
- **C안 라이브**: `great_house_arts` 등록 (남궁=창궁검법·하북팽=개산도법·사천당=당문비수술 —
  전부 카탈로그 실존 id · ★모용·제갈은 가전 무공 미등재라 입장권 없음, 지어내지 않았다) →
  탄생 시 시트 「가전_무공」 기장 (자격이지 힘이 아니다 — 입문·숙련은 스승·수련의 것) →
  시트에 「가전 무공: 개산도법 — 배울 자격이 있다」 표시 → 곽진 사사 거절이 실명 안내가 된다
  ("네 집에는 개산도법이 있지 않으냐"). 눈: lint 5-f (유령 세가·카탈로그 밖 id) + selftest
  케이스 — **lint 눈 6/6**. ★소비처(실제 입문 = 장원 사범 사사)는 장원 회차의 몫.
- **장원 시안 1호**: `docs/design/pengga_estate_v1.md` 【제안】 — 컨셉(도적 소탕이 가업인
  집), 구획도(72×56 제안), 시작 동선(뿌리내림 릴레이의 장원판: 집사·도장고지기·사범 =
  입장권의 소비처), 미결 6건 명시. 빨간펜 대기.

진행 (2026-07-25 밤 · ★팽가 도법 정격 개명 — 사용자 제공 자료):
- 사용자가 하북팽가 보편 전승 자료 제공 (「패도적·실전적 도법의 명가」 + 무공명 목록) →
  ★지어냄 표식이 박혀 있던 임시 도법 3종을 정격으로 개명: 개산도법→**왕자사도**(하급 —
  王 한 자 네 획) · 혼원도법→**철혈적성도**(중급) · 벽력도법→**건곤연환탈백도**(상급).
  급 배치는 【제안】. 오의(오호단문도·혼원벽력도)·심법 3종(소호·건곤미허·혼원벽력신공)은
  이미 정격 등재라 불변. 갈아낀 곳: skills·skill_mechanics·skill_motion(수치·모션 불변 —
  이름과 id 만)·ultimate_arts 선행 2·great_house_arts(입장권 → wangja_sado)·장원 시안 문서.
  묘비: gaesan_do·honwon_do·byeokryeok_do (eval_v6 스냅숏은 사료라 안 건드림).
- 원천의 남은 후보(장법 건곤신장·혼원벽력장 · 권법 파갑추 · 퇴법 철혈백사십팔퇴 · 신법
  어기신풍 · 보법 미허신보·혼원보 · 진법 연환패왕진)는 skills.yml 주석에 기록 — 등재는
  장원 회차의 별도 결정 (지어내지 않았다).
- 눈: lint(+selftest 재표적 6/6)·motion_audit·combat_audit 린트 전부 0 · 빌드 ✓ ·
  재배포 ✓ (MVT Done 33.0s · ERROR 0 · 봇 JDA ✓).

진행 (2026-07-26 · ★팽가 포트폴리오 「전부 등재」 — 사용자 확정 4건):
- ★설계 교정 (사용자): **한 문파 = 한 줄기 사다리가 아니다** — 무기·계열별 다양한 무공
  선택지 + 심법 별개. 문답 확정: ① 전부 등재 ② 오의 거취(오호단문도·혼원벽력도)는 무공
  분석 회차로 **보류** (현행 오의 유지) ③ **기초급 신설 — 삼류부터** ④ 수치는 제안→빨간펜.
- 신규 9종 (이름은 사용자 제공 정격 자료 · 급 배치·수치 전부 【제안】): 도법 기초
  **복호도식**(★이름 지어냄 — 원천에 기초급 명이 없다) · 장법 **건곤신장**(하)·
  **혼원벽력장**(상 — 파격·심법 벽력 passive 의 손) · 권법 **파갑추**(중 — armor_pierce) ·
  퇴법 **철혈백사십팔퇴**(중 — 4연타·category 퇴법 신설) · 보법 **혼원보**(하·삼류)·
  **미허신보**(중) · 신법 **어기신풍**(상 — 시전이 내력) · 도진 **연환패왕진**(2~5인).
  총 팽가 무공 12종(도3+기초1·장2·권1·퇴1·보2·신1·진1) + 심법 3 + 오의 2.
- 배선: skills·mechanics·motion 전부 등재 · 왕자사도에 기초 선행(복호도식 3) ·
  **입장권 → 복호도식으로 내림** (삼류부터 배울 수 있는 첫 칸 — 남궁·당가 기초급은 각
  가문 회차에) · combat_audit 계열 지도에 퇴법 등록 · lint selftest 재표적.
- 눈: lint 0 · combat_audit 린트 0 (신규 전부 격 게이트 ✅) · motion 0 · selftest 6/6 ·
  재배포 ✓ (MVT Done 30.3s · ERROR 0 · 봇 JDA ✓).
- 미결 (다음 회차들): ① ~~오의 체계 분석 후 오호단문도·혼원벽력도 거취~~ (✓ 아래 확정) ②
  연환패왕진 party.yml 협동 수치 정합 (지금은 매화검진 값 차용 【제안】) ③ 습득 기계 —
  장원 사범 사사(입장권 소비처 실물)는 장원 회차 ④ 다른 문파들도 같은 포트폴리오 확장
  (화산부터 — 파일럿 순서 동행 · 구멍 지도는 B-188).

★오의 분석 회차 확정 (2026-07-26 — 「A 확정 · B 권장대로 · C 후순위」):
- **A**: 오호단문도·혼원벽력도 = **오의 유지 확정** (혼원벽력도는 가주 일인전승 —
  sect_life 승계 사다리의 축 · 오호단문도는 전 오의 유일 파격·최광폭).
- **B (균열 수리 배정)**: ① 오의 소유 관문(시트 「오의」 칸·전승 사건 — 지금은 Shift+F 로
  21종 전부 순환되는 검증 수단) → **장원 회차 동행** ② 오의 v2 산술·격돌 재설계 → **전투
  튜닝 회차 동행** (B-177 기입 완료) ③ **숙련 8~10 정의 완료** — mastery_ladder 에 통현(9)·
  입신(10) 신설 (이름·효과 【제안】 · 오의 실전으로만 자람 · 기계 배선은 ②와 한 몸).
  combat_audit 「8~10 미정의」 경고가 이 정의로 닫혔다 (잔여 경고는 심법 이관 건뿐).
- **C**: 창작 오의(생사경 예산 조립)는 후순위 유지 확정.

### B-189 · ★★★ 세계 거대 줄기 v2 — 7단계(무림맹 창설 축) vs 살아 있는 5막 (사용자 제공 2026-07-26)
- **상태**: 진행
- **분류**: 결정
- **위치**: ★★**읽는 정본 `docs/design/world_bible.md`** (2026-07-28 신설 — 여기부터 읽어라) ·
  서사 정본 `docs/story_summary.md` · 배선 `config/factions.yml` · `config/world_clock.yml` acts
  (봇 `WorldClockEngine` 이 **매 자정 읽는다**) · 대화 인박스 `docs/design/world_decisions_inbox.md`
  · ~~`docs/design/world_stages_v2.md`~~ (**낡았다** — 접수 당시 제안서로만 보존)
- **단계**: P1
- **의존**: —
- **닫는 조건**: ① 정본 교체 여부가 결정된다 (교체하면 story_summary 수정 + acts 재작성) ②
  분기 문법(엔딩 A~D)의 거처가 정해진다 ③ 사파 기구의 미결(성원·결정단위)이 확정된다
- **닫는 조건 진행** (2026-07-30 갱신):
  · ① **닫힘** — story_summary 7막 교체 + `acts` 재작성 완료 (커밋 04577f4 이후 여러 차례 개정)
  · ② **닫힘** — `endings:` 절 신설 · A~C(세계) + D 셋(개인, scope=personal) · fallback 정확히 하나
  · ③ **부분** — 결정 단위 **총관** 확정(이름 범소천)·성원 셋 확정.
    ★남은 것: **녹림·장강수로채의 가입 여부** (적림십팔채 폐기로 다시 열렸다)
- **검증**: `python3 tools/world_clock_audit.py` · `python3 tools/world_clock_audit_selftest.py` ·
  `python3 tools/lint_config.py`
  (2026-07-30 기준 **world_clock 위반 0·경고 0 · selftest 20/20 · lint 오류 0**,
   ★lint 경고 1건 = **세력별 `싸우는_이유` 미기입 39곳** — 그 목록이 곧 「안 정한 것」 장부다)
- **닫힘**: — (★결정은 거의 찼고, **배선은 B-190 으로 갈랐다**)

★★진행 (2026-07-27 · **ㄷ 대화 1회차** — 확정 9건 · 사용자 지시 「모르는건 다 물어봐 추측 금지」):
- ★**이름 교체: 사도련 → 사도천(邪道天)**. 수장 호칭 **천주(天主)** 와 이름이 맞아떨어진다
  (사도「천」의 「천」주). 전 파일 개명 완료 (config 2 · docs 3 · tools 2 — id `sadoryeon`→`sadocheon`,
  막 id `sadoryeon_deungjang`→`sadocheon_deungjang` 등. ★라이브 세계막은 `prologue` 라 안전).
- **성원**: 사파 다섯이 통째로 드는 것이 아니라 **일부만** 든다. ★**낭인회는 통째로 든다**
  (문파가 아니라 시장인데도 — 그래서 사도천에 「계보 없는 검」이 통째로 실린다).
  하오문·녹림·장강수로채·살막의 거취는 **자료 대조 대기**.
- ★★**천주 = 무력으로 얻는 자리인데 창설자가 너무 강해 안 바뀐다**:
  제도는 「도전해서 이기면 바뀐다」인데 **아무도 못 이긴다** → 겉으로 실력주의, 속으로 일인 지배.
  ★무림맹과 정확히 대칭: 맹주는 **선출**(제2막 이벤트)이고 장로원이 실질 권력이라 바뀔 수 있다.
  천주는 **선출 이벤트가 없다**(의도) — 그래서 제4막 연합에서 **흔들리는 쪽은 언제나 무림맹**이다.
- **결정 단위 = 칠성사(七星使)** — 천주 아래 일곱 간부 (무림맹 장로원의 대응물. 비어 있던 자리).
  ★검색으로 조각만 얻었다 (나무위키 403 · 미러도 403/500): 천선(정보 수집·조사) ·
  **천권(저울 — 세력의 힘을 재고 균등하게 만든다. 간자·풍자로 첩보)** · 요광(전직 북방 군관).
  ★설계 주목: 천권의 「각 세력이 **동시에** 약해지도록 힘을 쓴다」는 결은 우리
  faction_politics(명분 게이지·연합 계산)와 정면으로 맞물린다 — 사도천이 **명분 계산 자체를
  흔드는 세력**이 될 수 있다 (배선은 별도 회차).
- **입문 불가** — 성원 세력을 통해서만 (무림맹과 같은 문법). `player_entry: false` 기입.
- ★★**제3막 노선은 개인별로 갈린다** — 서버가 한 방향으로 가지 않는다. 플레이어마다 제 노선을
  갖고 세계는 그 **분포**로 반응한다. **이 막의 끝은 승패가 아니라 집계다.**
  → 엔딩 입력 `연대_폭` 이 「협력을 고른 **비율**」이 됐다 (임계값·집계 시점은 미결).
- ★★**무림연합은 기구가 아니라 「상태」다**: 「무림맹주와 사도천주가 **동시적으로 협력한 상태** —
  두 대표를 바탕으로 유지되는 상태」. 그래서 **새 세력 id 를 만들지 않았다** — 연합은 조직이 아니라
  **두 사람의 악수**다. (무림맹 미결 문답 「별개 기구인가 확장인가」가 이것으로 닫혔다 —
  무림맹은 연합 안에서도 **제 이름을 유지한다**. 흡수되지 않는다.)
- ★**해소 조건**: 「**서로의 이유를 알고 옛날만큼의 전쟁까진 아님**」 —
  연합이 풀려도 **옛날로 돌아가지 않는다.** 한 번 안 것은 못 잊으니까.
  → 이 문장은 엔딩 A~C **전부**에 걸린다 (제6막 뒤에도 정·사가 완전 적대로 복귀하지 않는다).
- 눈: lint 0 · combat 린트 0 · motion 0 · game 0 · **world_clock 0** · selftest **26/26 + 20/20**.
  실측: 재배포 ✓ (Done 24.2s · ERROR 0).

★★ㄷ 대화 2회차 (2026-07-27 · 사용자가 **PDF 자료** 제공 — 나무위키 403 을 우회) — 확정 8건 + **정정 1건**:
- ★★**정정: 칠성사는 사도천의 조직이 아니었다.** 검색 조각만 보고 「사도천의 간부」로 안내했는데,
  본문은 「**암천회**에서 중추적인 조직 … 북두칠성 이름의 7명 간부」다. 그리고 이들이 하는 일은
  **잠입**이다 — 천추는 정파(무림맹 기밀부서 수장)에, 천선은 **하오문주로**, 천권은 사파 문파의
  문주로 잠입해 **사도천주를 죽이려** 했다. **칠성사는 사도천을 교란하는 쪽이다.**
  → 사용자 확정: **혈교의 침투 조직으로 들인다.** hyeolgyo.chilseongsa 신설, 사도천에서 회수.
  ★그 구조가 우리 줄기의 관통 실과 **정확히 같은 모양**이었다 (Stage 0 「사파인 척하는 무리」 ·
   1 「위장 침투」 · 3 「스파이 추적」 · 6 「사도천·정파·마교조차 공격」). **혈교가 어떻게 그 일을
   하는지**가 비어 있었는데 칠성사가 그 자리다. 한 줄: **혈교의 손은 일곱이고 전부 남의 옷을 입었다.**
  ★★설계 급소: **천권(저울)** 의 「각 세력이 **동시에** 약해지도록 힘을 쓴다」는
   faction_politics 의 명분 게이지·연합 계산(join_threshold)을 **직접 겨눈다** →
   혈교는 강한 적이기 전에 **연합이 서지 못하게 만드는 적**이다 (제0~2막이 늦는 이유가 된다).
- ★**성원을 자료 축대로 갈랐다** (사용자 「자료대로 가른다」): 원작의 사도천은 **문파형**
  사파의 연합(사도팔문)이고 산적·수적은 **적림십팔채**(녹림구채 9 + 수림구채 9)라는 별개 연합이다.
  → **사도천 = 하오문·살막·낭인회** · **적림십팔채 = 녹림·장강수로채**(신설).
  ★사파에 기구가 **둘**이 됐고 결이 다르다: **사도천은 명분으로 뭉치고, 적림십팔채는 길이
   이어져서 뭉친다** (한 줄: 사도천은 사람을 팔고 적림십팔채는 길을 판다).
- ★**사도천 결정 단위 = 총관(總管)** (자료의 악관태 자리). ★**합의체가 아니라 대리인**이다 —
  무림맹은 장로원(여럿의 합의)이 실질 권력인데 사도천은 한 사람이 실무를 총괄한다.
  → 대조가 한 겹 더 선명해진다: **정파는 합의하고, 사파는 힘이 정하고 한 사람이 집행한다.**
  그리고 취약점도 거기 있다 — 총관 하나가 무너지면 실무가 멎는다 (혈교 침투의 표적).
- ★★**천주의 무력 결 = 다수전 특화** (자료: 1대1보다 1대 다수·다수 대 다수에 특화).
  ★이것이 「사파에는 합격진이 없다」와 정확히 맞물린다 — **천주 혼자가 진 하나보다 낫다.**
  그래서 사파는 대형을 배울 이유가 없었다. combat 축 대응: 포위 슬롯·1대다 방어 이점의 **극단값**
  (적이 많을수록 공격이 세지는 결 — 수치는 npc_combat 회차).
  ★성격 = **실리 우선** (자존심보다 실리 — 제4막에서 손잡는 근거가 여기 있다).
- ★**천주 = 순수한 사파 절대강자** (혈교와 무관) → 사도천은 **속은 피해자**다.
  그래서 제4막의 손잡음이 **진심**이고, 제6막에서 **혈교에 가장 분노하는 자가 천주**다.
- ★**스파이 추적의 성패 = 세 가지 다** (사용자): ① 제6막 피해 규모 ② 제4막 연대 폭
  ③ **사도천의 생존**(실패 누적 시 제6막에는 이미 혈교의 것 — 가장 독한 분기).
  → 제3막에서 가장 먼 데까지 손이 닿는 박이 됐다. **지금 고른 것이 세 막 뒤에 온다.**
- ★★**연합 기술 공유 = 합격진을 섞어 쓸 수 있다.** party.yml 의 「같은 진법 숙련자끼리만」이
  연합에서 정·사 혼성으로 풀린다. ★**무공 사다리를 안 건드린다** — 계보·입문 게이트는 그대로 두고
  **자리(隊形)만** 나눈다 (진법의 값이 원래 「다섯이 동시에 벤다」였으니 연합의 값도 위력이 아니라
  **함께 설 수 있음**이 된다).
- ★그런데 **합격진 5종이 전부 정파 것**이라(화산·팽가·제갈·소림·개방) 교환이 일방적이었다 →
  사용자 확정: **사파는 진 대신 「수(手)」를 준다** (차륜전·은형·정보망).
  **정파는 대형을 주고, 사파는 방법을 준다** — 사파에 진법을 지어 붙이지 않고도 대칭이 선다.
- **외부 세력 결집 = 상단 · 표국** (정보상·의가는 안 골랐다 — 미결).
- 눈: lint 0 · combat 린트 0 · motion 0 · game 0 · world_clock 0 · selftest **26/26 + 20/20** ·
  ★세력 눈이 기구 셋을 「무공 0 이 의도」로 센다 (무림맹·사도천·적림십팔채).
  실측: 재배포 ✓ (Done 25.1s · ERROR 0).

★★★ㄷ 대화 3회차 (2026-07-28) — **자료 대조 → 「너무 닮았다」 판정 → 대개편.** 배선 완료·커밋.

**① 자료(화산전생 등장인물 PDF 18쪽) 전문 대조** — 미결 7묶음 중 다섯에 답이 있었다.
  그런데 답을 읽는 과정에서 **더 큰 것이 드러났다**: 우리가 들인 것이 **작품 고유의 것**이었다.
  사용자 판정: **「너무 화산전생의 스토리와 비슷하다 — 수정이 필요」.**

**② 세 층으로 갈랐다 (이 회차가 남긴 계율)**
  - **층 1 · 장르 공용 자산 (안 뺀다)**: 무림맹·장로원·군사·총관·정파/사파/마교·구파일방·
    오대세가·녹림·하오문·개방·살막·낭인·장강수로채·경지어. 여기까지 빼면 무협이 아니게 된다.
  - **층 2 · 작품 고유 조어 (뺀다)**: 사도천·적림십팔채·칠성사·흑영부·상천십좌·마도이세 · **모든 인명**.
  - **층 3 · ★★구조와 발상 (이름을 갈아도 안 지워진다)**: 우리 혈교가 **「암천회」의 모양**을 입고
    있었다 — 「만악의 근원이 뒤에서 전부 조종했다 → 마지막에 모두가 연합해 하나를 친다」.
    ★한 줄 계율: **이름을 다 갈아도 구조가 남으면 여전히 같은 이야기다.**

**③ ★★★사용자가 층 3 을 한 번에 풀었다 — 네 세력 재정의**
  | 계열 | 정의 | 싸우는 이유 |
  |---|---|---|
  | 정파 | 의롭다 자처하나 **뿌리가 썩어가는 쪽이 존재**(일부) | 힘 있는 자가 힘없는 자를 지켜야 한다 |
  | 사파 | 개인의 과시 · 강해지려 무엇이든 · ★**적정선이 있다** | 제 힘으로 선다 |
  | 마교 | **핍박받아 죽어간 자들의 교단** · 증오에 형태를 준 것이 교리 | 세상이 우리를 죽게 두었다 |
  | 혈교 | 압도적 마기 · 유린 · 제물 · **인간을 인간으로 안 본다** | ★**없다** |
  ★★**세계 주제 확정: 「절대 악은 없다 — 혈교를 제외하고. 서로 이유가 있어 싸운다.」**
    이 문장은 새로 온 게 아니다 — 연합 해소 조건 「**서로의 이유를 알고**」가 이미 주제 선언이었다.
  ★세 눈금: **혈교는 선이 없고, 사파는 선이 낮고, 정파는 선을 말하면서 지키지 못한다.**
  ★★그리고 **주제를 감사 가능하게** 만들었다: `세계_주제` 절 신설 + 모든 세력에 `싸우는_이유`
    필수 필드 · **면제는 혈교 하나** — 그 면제 자체가 등록부에 적힌 주제다.

**④ 걷은 것 (근거를 잃었다 — 정직하게 적는다)**
  - ★★**칠성사 절 전체** (어제 들였다) — 혈교가 조종·잠입을 안 하면 근거가 통째로 없다
  - **적림십팔채** 세력 폐기 — 사파의 기구는 **패도천 하나**로 되돌아갔다
  - 한 줄 「**사도천은 사람을 판다**」 — 사파의 「적정선」과 정면 충돌 (사람을 파는 건 혈교 쪽 말)
  - 제0막 `wijang_tero`(사파인 척하는 무리) → **`bin_maeul`**(마을이 통째로 비고 시신이 없다).
    ★혈교는 위장하지 않는다 — **아직 아무도 그런 것이 있는 줄 모를 뿐**이다
  - 제3막 `hyeolgyo_spy_chujeok` → **`sseogeun_ppuri_chujeok`**
  - ★마교 절에 **이미 적혀 있던 어긋남 둘**을 함께 걷었다 (읽다가 찾았다):
    `소교주 = 교주의 자식`(**혈통 승계**) · `마존 = 전문으로 갈린다`(독마·검마…아홉)
  - ★녹림·장강수로채의 거취는 **미결로 되돌렸다** — 제외 사유(적림십팔채)가 사라졌으므로.
    지어내지 않았다.

**⑤ ★★혈교가 하던 일을 「정파의 썩은 뿌리」가 물려받았다** (이 회차의 설계 핵심)
  추적의 대상 = 혈교 첩자 → **제 편 안에서 썩는 쪽** · 연합이 늦는 이유 = 남이 흔들어서 →
  **제 안이 썩어서** · 개인 노선 = **덮을 것인가·도려낼 것인가·이용할 것인가**(집계 문법 그대로 산다) ·
  성패가 닿는 셋 = ①제6막 피해 ②제4막 연대 폭 ③**무림맹의 생존**(그전엔 「사파 기구의 생존」).
  ★순서가 좋아졌다: 제2막에 맹을 세우고 **제3막에 세우자마자 썩는다.**
  ★★그 죄는 물려받은 게 아니라 **처음부터 제 죄**다 — 우리 무림맹은 **재건이 아니라 창설**이니까
    (참고 작품과 갈리는 자리이고, 어제 「앞머리가 안 맞는다」던 것이 실은 **차이점**이었다).

**⑥ 개명 — 패도천(覇道天) · 패주(覇主)**
  ★까닭 둘: ① 「사도천」은 작품 고유 조어 (「패도」는 왕도의 반대말로 **장르 공용어**이고
  「힘이 정하고 한 사람이 집행한다」와 뜻이 맞는다) ② **「천주」가 마교의 「천마」와 헷갈렸다** —
  ★사용자가 실제로 「천주가 뭔가요?」라고 물었다. **그것이 증거다.**

**⑦ ★★★마교 대개편 (「금기 세력」 → 이유가 있는 세력)**
  - **기원**: 부패한 정파에 핍박받아 굶고·목마르고·얼고·독에·불에·물에 죽어간 자들이 모였다.
    ★**증오하고 저주하는 데 그치지 않고 교단을 만들었다** — 그것이 심장이다.
    ★왜 종교인가: **증오만으로는 못 산다.** → **증오는 오래 못 간다. 그래서 신을 세웠다.**
    ★★**마교는 무림맹이 없던 시절의 청구서다** (막을 자가 없던 게 아니라 막을 **곳**이 없었다).
  - **천마 = 신이 아니라 신의 그릇.** 그릇이 되려면 제 이름을 비워야 한다(**기명각 棄名閣**) ·
    ★**그릇이 되면 신이 들어온다** → 「천마는 언제나 최강자」가 억지가 아니라 기제가 된다.
  - **초대 천마가 아직 살아 있다** (마교는 **1대** · 플레이어가 **2대**).
    ★★그리고 시한부다: **올곧지 못하게 자라 금이 간 그릇**이라 신을 못 버틴다.
    ★★**그 길이 처음 죽이는 자가, 그 길을 연 자다** (마교는 문턱이 없고, 대신 버티지 못하면 죽는다).
    ★**왜 세상을 지배하지 않는가**: **가진 것을 물려줄 사람이 없어서.**
      → 「차지한다」 마존과 정면으로 부딪힌다: *"차지해서 어쩌려고. 내가 죽으면 누가 지키나."*
  - **마존 다섯 — 무공 종류가 아니라 사상으로 갈린다**. **전부 초대의 제자**(형제제자):
    **부순다**(복수) · **차지한다**(지배) · **떠난다**(정착) · **덮는다**(환상) · **거둔다**(구제).
    ★★다섯은 전부 **「어떻게 하면 안 죽는가」의 답**이다 (기원이 죽어간 사람들이니까).
    ★★「붙든다」의 실물 근거: 다섯은 사상이 갈렸는데도 안 나갔다 — **스승이라서**다.
      → **초대가 없어지면 마교가 갈라지는 것은 필연**이고, 2대는 **스승의 권위 없이 사형들을
      붙들어야 한다** (천마만 승계가 「인정」인 이유가 여기서 완성된다).
    ★**거둔다**가 이 다섯의 심장 — 마교에 **치유**가 있고, 제4막 연합에 마교 일부가 낄 유일한 문.
  - **초대의 퇴장 2단**: ①제4막 전후 **위임하고 물러난다**(살아 있다) ②제6막 **혈교가 2대를 노리자
    막아서 이기고, 직후 병으로 죽는다.**
    ★★**혈교는 천마를 이기지 못했다. 다만 기다리면 됐다.** — 격이 안 깎인다.
    ★평생 「이기는 것으로는 아무도 못 살린다」던 사람이 **마지막에 한 사람은 살린다.**
  - ★★제5막의 뜻이 뒤집혔다: **정파가 강해서 막은 게 아니라 천마가 아파서 못 이긴 것**이다
    → **정파의 승리는 승리가 아니다.** 그 자만이 제6막에서 대가를 치른다.
  - ★B-188 마교 회차의 뼈대가 섰다: **마존 다섯 = 계열 다섯** (파괴·군림·신법·환술·수호).

**⑧ ★★세 자리 문법 확정 — 맹주 · 패주 · 천마**
  | 자리 | 되는 법 | 대리인 | 미달 시 |
  |---|---|---|---|
  | 무림맹주 | **뽑힌다**(+실력 문턱) | **군사(軍師)** 신설 | NPC |
  | 패주 | **이긴다** (「아무도 못 이겼다」는 **과거형** — 플레이어가 첫 예외) | 총관 | NPC |
  | 천마 | **지목받는다**(전대의 인정) | — | NPC |
  ★★**인기로 오르거나, 힘으로 오르거나, 인정받아 오른다** — 셋이 세 갈래 플레이의 정점이 된다.
  ★셋 다 **레이드 규격**(플레이어 셋이 붙어야 잡는다) · 전투 배치는 **NPC 자동**
  → ★★**수장은 명령하지 않는다. 앞에 선다.** (장수가 아니라 고수 — 시스템 복잡도를 피한 판단이
    그대로 세계관이 됐다.)
  ★천마 승계는 **제5막이 열리기 전에 끝나 있다** — 그래서 **침공을 이끄는 것은 2대**다.

**⑨ 눈** — lint_config 0 · world_clock **0·0** · **selftest 20/20** · game_audit 0(경고 6 기존) ·
  motion 0(경고 1) · combat 린트 위반 0(경고 5 기존 B-188 빚).
  ★★**눈이 실제 결함 둘을 잡았다**:
  ① 썩은 뿌리 명분의 표적으로 `orthodox`를 걸었더니 **「roster 에 없는 세력」**이라고 막았다 —
     orthodox 는 **컨테이너**다. ★썩은 뿌리가 어디인지 미결이라 **표적을 지어낼 수 없으므로
     명분을 걷고 소문만 남겼다.** 눈이 지어내기를 막았다.
  ② 개명으로 **뮤테이션 앵커가 낡았다**(「사도천의_자칭」). 도구가 「시험 자체가 낡았다」고
     스스로 경고해 줘서 살았다 — 그 경고가 없으면 **낡은 시험이 조용히 통과하는 눈**이 된다.

**⑩ ★★썩은 뿌리 확정 (같은 날 2부 — 사용자 확정 2026-07-28 · 안 C)**
  - **여러 곳에 조금씩** · **밑의 안 보이는 곳**에서 썩는다 · 형태는 **자릿세**(맹의 이름으로 저잣거리에서 뜯는다)
  - ★★**맹주는 썩지 않았다** — 맹은 하나의 목표로 움직인다. 「내가 앉은 자리가 썩었다」가 아니라
    **「위는 깨끗한데 아래가 썩는다」**. → 맹주 플레이어가 악역이 되지 않고 **못 보는 자**가 된다.
  - ★**왜 들어왔나: 대치 때문이다.** 패도천과 맞서려면 머릿수가 필요했다 →
    ★★**맹은 이기려고 커졌고, 커지느라 아무나 받았다.** (맹주의 잘못이 아니다 — 필요했으니까)
  - ★**왜 썩는가**: 겉은 장르 표준 셋(위선과 명예·기득권 유지·도덕적 타락 — 사용자 제공),
    밑은 **「우리가 세운 질서를 지켜야 한다」**이고 **진심으로 믿는다** →
    ★★**탐욕은 설득할 수 있어도 신념은 못 한다.**
  - ★★★**제3막과 제5막이 이어졌다**: 「가난한 무인을 누른다」가 곧 **마교의 기원**이다 →
    **정파가 누른 자들이 마교가 되었고, 제3막에 정파는 다시 누르기 시작한다.**
    못 도려내면 제5막의 침공이 **복수가 아니라 청구**가 된다.
  - ★★★**딜레마가 도덕이 아니라 기계가 됐다**: **도려내면 맹이 약해진다**(그들이 대치의 머릿수다).
    → 제3막의 선택은 **「힘이냐 명분이냐」**이고, faction_politics 가 명분 게이지로 도는 시스템이라
    정면으로 맞물린다.
  - ★배선: 명분(myeongbun)을 **안 걸었다** — 미결이라서가 아니라 **그게 맞아서**다.
    자릿세는 민간을 향하므로 **명분이 아니라 민심으로 드러난다** → `region_delta`(민심 -5·경제 -4)
    + 소문(mingan_market · 문안키 `맹의_이름으로_걷는다`). ★고발장이 아니라 **수치가 증거**다.
  - 눈: lint_config 0 · world_clock **0·0** · selftest **20/20**.

**⑪ ★★★엔딩 재설계 (같은 날 3부 — 사용자 「혈교 대전까지 가는 게 맞는지」)**
  ★사용자의 의심이 정확했다. 엔딩 넷을 나란히 놓자 **셋 다 「이겼다」를 말하지 않았다**:
  A는 **봉인**하고 · B는 폐허가 되고 · C는 다시 **숨는다** →
  ★★**혈교는 죽지 않는다. 얼마나 가뒀는가만 다르다.**
  그런데 종결박은 `jeonmyeonjeon`(**혈교_전면전**)이었다 — **승패를 낳는 그릇**이라 정본과 어긋났다.
  ★★그리고 더 컸던 것: **혈교 대전이 엔딩을 거의 안 정하고 있었다.**
    A/B를 가르는 것은 `마교_승패`(제5막)였고 C는 조건 없는 fallback이었다 —
    **막 하나를 통째로 쓰는데 엔딩은 그 전 막이 정하고 있었다.**
  - **고침 ①**: 종결박 `jeonmyeonjeon` → **`bongin`**(world_event 혈교_봉인).
    **목표가 죽이기에서 가두기로** 내려왔다. ★**혈교는 이기는 적이 아니라 가두는 재난이다.**
  - **고침 ②**: A의 조건을 `혈교_명분: 성립` → **`연대_폭: 넓다`**. **혼자서는 못 가둔다.**
    ★★★그래서 **제3막이 끝까지 닿는다**: 썩은 뿌리 → 제4막 연대 폭 → **제6막 봉인 가능 여부**
      (그전에는 제3막의 선택이 제4막까지밖에 안 닿았다).
    ★두 막이 각각 제 몫으로 가른다: **A/B는 마교가, A/C는 연대가.**
    ★C가 뜻을 얻었다 — 그전엔 「나머지」였는데 이제 **「마교는 물리쳤는데 연대가 좁아 못 가둔 결말」**이다.
  - **고침 ③**: `혈교_명분` 입력에 **`쓰임: 연출`** 을 명시했다.
    ★계율: **「판정 입력」이라 적고 판정에 안 쓰면 그것이 곧 등록부의 거짓말이다.**
  - **고침 ④**: D를 **셋으로 갈랐다**(사용자 「각각 세워주세요」) — `eungeo`(은거·**밖으로**) ·
    `hyeolhwa`(혈화·**아래로**) · `seongyeong`(선경·**위로**). 전부 scope=personal(동시 성립) ·
    조건은 전부 **미결**(지어내지 않는다).
  - 눈: world_clock **0·0** · lint 0 · ★selftest 앵커 **둘이 낡아** 재표적
    (`jeonmyeonjeon`·개인 엔딩 절 재작성) → **20/20**.
    ★재표적 요령 하나 배웠다: **scope 줄만 잡으면 셋 중 어느 것인지 모호해진다 — id와 붙여 잡는다.**

**⑫ ★★정리 회차 (같은 날 4부 — 사용자 「내가 뭘 만들고 싶은지 혼란이 온다」)**
  ★진단: 세계관이 **네 군데에 흩어져 있었다** (factions.yml 주석 · world_clock.yml ·
  story_summary.md · 이 장부). **「지금 우리 세계는 이렇다」를 한자리에서 볼 방법이 없었다.**
  하루에 결정이 스무 건 넘게 쌓였으니 혼란이 오는 게 당연했다.
  - **신설 `docs/design/world_bible.md`** — 확정된 것만, **경위는 빼고**, 읽히는 순서로.
    주제 → 세력 → 세 자리 → 마교 → 7막 → 엔딩 → ★**아직 안 정한 것**.
    ★§7 이 가장 쓸모 있다: 막힌 것 10건 + 세력별 「싸우는 이유」 미기입 39곳.
    ★웹 페이지로도 띄웠다 (터미널 밖에서 읽으라고). **md 를 그대로 띄웠다** — 별도 HTML 로
    만들면 정본과 갈라져 낡는다. 이 프로젝트가 가장 싫어하는 것이 그것이다.
  - **신설 `.claude/commands/세계.md`** — **대화 전용 회차** 장치.
    별도 터미널에서 `/세계` 를 치면 정본을 읽고 문답만 한다. ★**config 를 안 건드린다**
    (두 세션이 동시에 등록부를 갈면 깨진다). 결과는 인박스에 쌓인다.
  - **신설 `docs/design/world_decisions_inbox.md`** — 대화에서 나온 확정이 쌓이는 곳.
    작업 세션이 이걸 읽고 배선한 뒤 `✅ 배선 완료 (커밋 …)` 를 적는다 (지우지 않는다).
  - ★★★**감사 거짓말 하나를 찾아 닫았다 (P0급)**: `세계_주제` 절이
    **「lint_config 가 싸우는_이유 가 빈 세력을 잡는다」**고 적어 뒀는데 **그런 눈이 없었다**
    (07-27 에 선언만 하고 안 만들었다). → `lint_config` ⑩ 신설:
    · 미기입 세력을 **경고**로 센다 (위반이 아닌 까닭: 대부분 미기입이고 **지어내면 안 되므로**.
      ★그래서 이 경고 목록이 곧 **「아직 안 정한 것」 장부**다 — 지금 **39곳**)
    · 면제 세력이 **「없음」이라고 적었는지**를 **위반**으로 잡는다
      (★면제는 **선언**이지 공백이 아니다 — 빈칸은 「아직 안 정했다」와 구별되지 않는다)
    · 계열 컨테이너는 **안 싸운다**(분류다) → 요구하지 않는다. 적용 범위를 그렇게 좁혔다
    ★**변이 시험 2건으로 눈을 검증했다** (면제의 「없음」을 지운다 · 세계_주제 절을 지운다) —
      둘 다 잡고 복원됐다. **위반 0 은 눈을 시험해 본 뒤에만 믿는다.**
    ★계율: **등록부가 제 보증으로 눈을 내세울 때는, 그 눈이 실재하는지 세어 보라.**

**⑬ ★★★인박스 회차 1·2 배선 (2026-07-28 · `/세계` 대화 세션 → 작업 세션)**
  ★**대화/배선 분리가 처음 돌았다.** 별도 세션이 `world_decisions_inbox.md` 에 **확정 25건 +
  정정 3건 + 【제안】 3건**을 쌓았고, 이 세션이 읽고 등록부에 넣었다. **문법이 작동한다.**
  - **마교에 200년이 들어갔다** — `두_겹`(구/신 천마신교) · **대충돌 200년 전** ·
    ★**마교가 먼저 쳤다**(다만 밑이 터진 것) → **가해자는 기억하고, 피해자는 잊었다**
  - ★★**폐기: `전부_초대의_제자: true` → false/동료.** 「스승이라서 안 나갔다」가 무너지고
    **더 강한 근거**가 들어왔다: **「교리의 원본이 아직 숨 쉬고 있어서 못 갈렸다」**
    → 인간관계 논리에서 **종교 논리**로 올라섰고, **2대는 원본이 아니라 후계자**라
    다섯 해석과 동급이 된다 (그래서 못 누른다).
  - ★★**마존이 구/신으로 갈렸다**: 구 다섯 = **창건의 이유**(「죽게 내버려두지 못하게 하는 법」의
    다섯 답 — ★기원 목록에 **아무도 칼에 안 죽었다**) · 신 다섯 = **매슬로 5단계의 비틀린 형태**
    (200년이 안전을 채웠고, **두려움이 빠진 자리에 욕심이 들어왔다**).
    ★**넷은 올라갔고 하나(거둔다)는 떨어졌다 — 떨어진 것이 심장이었다**
    (연합에 마교가 낄 유일한 문이 가장 깊이 썩었다).
  - ★정정: 「옛 방법을 못 버린다」 → **「방법은 남고 뜻은 갔다」**.
    ★그 한 칸이 혈교와 갈린다 — **혈교는 처음부터 뜻이 없었고, 신 마존은 뜻이 있었는데 잊었다.**
  - ★★★**혈교 기원 전면 교체 — 「주워졌다」**: 창시자 없음 · 세상에 뿌려진 혈 무구·무공서 ·
    교리는 **수렴한 것** → **창시자가 없으면 목적도 없다**. `싸우는_이유: 없음` 이
    설정이 아니라 **구조**가 됐다. **조직이 아니라 감염**이고, `점조직/암약: false · 산발: true`.
    ★대화 중 「점조직·암약」 안이 나왔으나 **§8 계율 정면 위반**(=한 번 걷어낸 암천회 모양)이라
    그 세션이 스스로 걷어냈다 — **장치가 제 일을 했다.**
  - ★★**봉인 = 회수**로 근거가 채워졌다: **세상에 흩어진 것을 거두려면 세상만큼 넓어야 한다**
    → 「연대의 폭이 조건」이 자명해졌다. 그리고 **무당이 500년째 그 일을 하고 있었다**
    (★봉인법이 하늘에서 안 떨어진다 · 무당이 맹 정치에서 빠진 이유도 됨).
  - ★D-2 「혈화」가 채워졌다 — **플레이어도 책을 줍고 매료된다** →
    **혈교가 적이면서 동시에 유혹**이 된다 (조직으로는 못 만드는 관계다).
  - ★`world_clock.yml` 에 **`전사:` 절 신설** (`wired: false` — 박이 아니라 **서사의 근거**다).
  - ★【제안】은 전부 **【제안】 표시를 달고** 들어갔다 (200년·500년·250살·계보 대수·신 마존 이름).
    **신 마존 이름은 「미승인」으로 명시** — 쓰지 않는다는 뜻이다.
  - 눈: lint 0(경고 1=미기입 39) · world_clock **0·0** · selftest **20/20** · game 0 · backlog 0

**⑭ ★★마교가 사회로 내려왔다 (2026-07-28 · ★인박스 아니라 **작업 세션 대화**에서 나왔다)**
  - ★★**신 마존이 두 종류가 됐다**: **이은 계보**(뜻만 잃었다) / **끊긴 계보**(대충돌에서 전승이
    끊겨 자리가 비었고, **파편을 조금만 잇고** 교도가 제 교리로 채워 올라왔다).
    ★내가 「밖에서 자리를 뺏은 자」로 잡았다가 **사용자 안이 더 나아 폐기**했다 —
    **찬탈이 아니라 빈자리**라야 「스스로를 정통이라 믿는다」가 자연스럽고,
    ★**아무도 그를 부정할 수 없다**(대조할 원본이 없으니까).
    ★★**2대의 무기(원본)가 누구에게는 통하고 누구에게는 안 통한다** → 붙드는 일이 **두 종류의 문제**.
    ★★대충돌의 마교 쪽 대가가 정해졌다: **그 전쟁에서 마교가 잃은 것은 사람이 아니라 말이었다.**
    ★★초대의 시한부가 한 겹 무거워졌다: **끊긴 교리를 복원할 수 있는 건 초대뿐이다.**
  - ★**첫 실물 = 「덮는다」** (끊긴 계보 · **성(性)의 교리를 가진 여성 교도**가 올랐다).
    ★★결을 **「유혹」이 아니라 「소유」**로 잡아 클리셰(마교의 요녀)를 벗었다:
    **감기게 하는 법만 남고, 왜 감겨야 하는지가 사라졌다** ·
    **원래 그 손은 죽는 사람을 편하게 놓아 주는 손이었다. 지금은 아무도 못 떠나게 한다.**
    (★하오문 「기녀=색공」과 층이 다르다 — 저쪽은 **직업**, 이쪽은 **교리**)
  - ★★★**계율이 한 번 더 일했다**: 「**하오문 문주가 알고 보니 마존이었다**」 안을 **걷어냈다.**
    원작 자료의 「천선이 하오문주로 위장했다」와 **글자 그대로 같은 구조**이고(주체만 혈교→마교),
    게다가 **하오문은 패도천 성원**이라 **제4막 연합이 통째로 함정**이 된다
    (「패주가 혈교였다면…그 길을 안 갔다」와 같은 실수).
    → 대신 **위장 없이 원하는 것을 전부 주는 형태**로 갈았다:
    > **마존은 하오문에 숨어들지 않았다. 하오문의 어떤 가게가 마존의 것일 뿐이다.**
    ★**숨는 것과 아무도 안 묻는 것은 다르다** (혈교의 「숨는 것과 안 보이는 것은 다르다」와 같은 문법).
    **문주가 아니라 가게 주인** — 그래서 「개인의 유흥」이 맞는 말이 되고 신 마존의 정의와 맞는다.
    ★부수 소득: **마교는 200년을 어떻게 먹고살았나**에 답이 생겼다 — **침투가 아니라 살림이다.**
    ★제5막 그림도 바뀌었다: **이미 안에 있던 가게들이 어느 날 문을 닫는다.**
  - ★★★**플레이어 천마 루트가 열렸다** — 비어 있던 「초대가 어떻게 후보를 찾는가」가 채워졌다:
    **하오문에서 정보를 산다**(잠입이 아니다 — 하오문은 원래 판다). 전황·인재·**그릇 찾기**.
    ★**왜 지금인가**: **200년을 기다렸는데 이제 시간이 없다**(시한부가 행동으로 나타난다).
    ★**무엇을 찾는가**: 그릇은 이름을 비울 수 있는 자다 →
    **문파는 제자를 고르고, 마교는 버릴 것이 없는 자를 찾는다** (하오문이 밑바닥을 아니까).
    ★★세 자리 진입 문법이 갈렸다: **정파=입문한다 · 사파=값을 낸다 · 마교=저쪽이 먼저 본다.**
    ★★**가드를 걸었다** (없으면 마교 전용 편의가 된다): 하오문은 **사는 사람을 안 따진다** →
    **저잣거리에서 한 일은 세 곳이 다 듣는다.** 세 자리 진입이 **같은 문에서 갈린다.**
  - ★배선 사고 1건(자가 수습): `★구신_대조가_기계다` 키를 덮어 두 블록이 **한 블록으로 합쳐졌다.**
    YAML 은 통과했는데 **내용이 엉뚱한 키에 붙어 있었다** — 파싱 뒤 키 목록을 대조해서 잡았다.
    ★계율: **블록 스칼라 위의 키를 갈 때는, 갈고 나서 키 목록을 파싱해 대조하라.**
  - 눈: lint 0(경고 1=미기입 39) · world_clock **0·0** · selftest **20/20**

**⑮ ★★마교의 살림 다섯 + 하오문의 한 팔 (2026-07-28 · 작업 세션 대화 이어서)**
  - ★★★**계율이 또 일했다 (이 세션 세 번째)**: 「**하오문 전체가 애초에 천마신교 것이었다**」를
    걷어냈다. 셋이 깨진다 — ① 하오문은 **패도천 성원**이라 **제4막 연합이 통째로 함정**
    ② 방금 세운 가드(**저잣거리에서 한 일은 세 곳이 다 듣는다**)가 죽는다
    ③ ★**마교가 혈교보다 음험해진다**(200년 조종) → **절대악이 둘**이 된다.
    → 대신 **「한 팔」**로 갔고, 원하던 것(깊이 박힘·갈라서는 계기·색공 계보)은 **전부 살렸다.**
  - ★★**기루 계열 = 「덮는다」의 것 · 하오문 본체는 모른다.**
    ★위장이 아니다 — 등록부에 이미 *「점조직이라 수뇌부의 자리를 아무도 모른다」*가 있었다.
    ★★**뿌리가 끊긴 계보와 이어졌다**: 대충돌에서 전승이 끊긴 「덮는다」의 잔존 문도가
    저잣거리로 흘러들어 기루가 됐다 →
    **산에서 죽은 계보가, 저잣거리에서 다른 것이 되어 돌아왔다.**
    ★그래서 지금 마존(성 교리의 여성)은 **뿌리가 처음부터 저잣거리**다 — 내려온 게 아니라 **올라갔다.**
  - ★★★**증거가 이미 등록부에 있었다**: 하오문의 **「기녀는 색공·음공」** — 그전엔 직업 묘사였는데
    이제 **그 계보에서 흘러나온 것**이 됐다.
    > **증거는 200년 동안 저잣거리에 널려 있었다. 아무도 그것이 무엇인지 안 물었을 뿐이다.**
  - ★**갈라섬이 더 아프다**: 「전부 적이었다」가 아니라 **한 팔이 남의 것이었다** →
    하오문이 **안에서 갈라진다**(자르자 / 못 자른다 — 200년 장사 동료다).
    ★**정파의 썩은 뿌리와 같은 모양**: **도려내면 제가 약해진다.**
  - ★★**다섯의 살림** (사용자: 「하오문 하나로는 못 먹여 살린다」).
    ★규율을 세웠다: **살림은 그 계보의 무공에서 나와야 한다** (안 그러면 붙임딱지다):
    거둔다=**약재·영약**(치유의 손) · 떠난다=**관문 안 지나는 길**(신법·은둔) ·
    덮는다=**기루**(환술·음공) · 차지한다=**노역판·광산**(군림·다수제압) ·
    부순다=**병장기·화약**(파괴의 손).
    ★★**「부순다」만 못 번다** — 거래처가 무서워서 안 산다 →
    **가장 과격한 계보가 가장 가난하고 남이 벌어 온 것으로 산다.**
    갈등이 사상만이 아니라 **장부에서도** 생긴다.
  - ★★**살림이 뜻보다 오래 갔다**: *뜻은 200년에 흐려졌는데, 장부는 하루도 안 쉬었다* →
    신 마존이 저를 정통이라 믿는 근거가 하나 더 (**실제로 교단을 먹여 살린다**).
  - ★★★**숙청 = 밥그릇 깨기**: *마존 하나를 치는 것은 교단의 밥그릇 하나를 깨는 것이다.*
    → 「붙듦 ↔ 숙청」에 실물이 생겼고, **정파의 딜레마와 정확한 거울**이 됐다
    (정파는 도려내면 약해지고, 마교는 치면 굶는다 — **같은 딜레마를 다른 이유로**).
  - 눈: lint 0(경고 1=미기입 39) · world_clock **0·0** · selftest **20/20**

**⑯ ★★★마교가 나라가 됐다 + 「부순다」 재정의 (2026-07-28 · 작업 세션 대화 이어서)**
  - **하오문 문주 = 어렴풋이 알고 안 묻는다** (확정).
    ★**모르는 것도 아니고 아는 것도 아니다 — 묻지 않기로 한 것**이고, 그것이 하오문답다
    (*사는 사람을 안 따진다* = 200년을 살아남은 방법).
    > **묻지 않은 것은 모른 것이 아니다.**
    ★★**정파 맹주와 정확한 짝**: **맹주는 못 본 자이고, 하오문주는 안 본 자다.**
  - ★★**마교에 백성이 산다 · 마존이 영역을 관리한다 · 영향력에 따라 크기가 다르다.**
    → **마교가 문파가 아니라 나라에 가까워졌다.** 살림 다섯이 말이 되고,
    「거둔다」가 심장인 까닭이 커지고(백성을 먹인다),
    ★**2대의 일이 「다섯을 붙드는 것」만이 아니게 된다 — 사람을 먹여야 한다.**
    ★★★**제5막 침공의 뜻이 바뀐다: 군대가 아니라 나라가 움직인다.**
    ★영역 크기는 「부순다=가장 큼」만 확정, 나머지 넷은 **살림에서 파생한 【제안】**으로 달았다.
  - ★★★**「부순다」 재정의 — 충돌을 풀었다.**
    사용자 확정(교리를 명확히 따름 · 제대로 된 승계 · 천마의 친우 · **가장 큰 영역**)이
    등록부의 **「가장 가난하다」**와 부딪혔다. → ★**영역과 현금을 갈랐다**:
    > **가장 많은 사람을 지키는 계보가, 가장 적게 번다.**
    땅은 넓고 먹일 입은 많은데 팔 것이 안 팔린다 → 「남이 벌어 온 것으로 산다」가
    **놀아서가 아니라 입이 많아서**가 되고, **목소리가 큰 것도 정당해진다.**
    그리고 갈등이 **누가 옳으냐가 아니라 누가 더 무거우냐**의 다툼이 된다.
  - ★★그리고 **「안 변한 이유」가 우연에서 의지로 바뀌었다.**
    그전엔 *「부수는 데는 챙길 것이 없으니까」*(우연)였는데 → **약속을 지켜서**(의지)다.
    ★자기실현이 **타락이 아니라 완주**가 된다 — 구의 답(세상이 없어지면 아무도 안 버린다)을
    이루려면 끝까지 강해져야 하니까.
    ★★그래서 매슬로 표가 **세 종류**가 됐다:
    > **셋은 올라갔고, 하나는 떨어졌고, 하나는 그 자리에 있다.**
  - ★★★**2대에게 처음부터 우군이 하나 생겼다** — 다섯을 다 적으로 두면 시작이 불가능한데
    발판이 된다. 그리고 규칙을 유일하게 지킨 곳이라
    **2대의 승계 정당성을 뒷받침할 유일한 증인**이다
    (나머지 넷은 「왜 그자인가」를 물을 자격이 오히려 약하다 — 저희가 규칙을 안 지켰으니까).
    ★★**그런데 그것이 위험이기도 하다**: **가장 충직한 자가 가장 과격한 교리를 들고 있다** →
    2대가 「여섯 번째 답」을 낼 때 **가장 아프게 반대할 자도 이쪽**이다.
  - ★【제안】 수정: `부순다: 4대` → **2대**. 근거가 뒤집혔다 —
    **잦은 승계는 불안정의 표시**이고, 제대로 이은 곳은 대수가 적어야 맞다.
    ★그리고 **대수가 곧 그 계보의 안정도**라는 읽기가 생겼다.
  - 눈: lint 0(경고 1=미기입 39) · world_clock **0·0** · selftest **20/20**

**⑰ ★★마존 다섯의 이름 + 당가 줄기 (2026-07-28 · 작업 세션 대화 이어서)**
  - ★★**별호 = 구/신 짝**: 활인(活人)→**불사(不死)** · 낙토(樂土)→**철벽(鐵壁)** ·
    안혼(安魂)→**혼박(魂縛)**(★魂 공유) · 호세(護世)→**군림(君臨)** ·
    **파천(破天)→파천(破天)**(★한 글자도 안 바뀌었다).
    ★★★**파천은 200년째 같은 별호다** — 이름째로 물려받았고, **「제대로 된 승계」의 증거**가 된다.
  - ★**두 번 갈아엎었다.** 1판(멸세·훼세·엄목·환주)은 조어라 안 붙었고,
    2판(탈수·연수·정세·안심·폐문)은 ★★**현대 한국어와 동음이의**라 힘이 빠졌다
    (脫水·硏修·政勢…). **파천만 그 문제가 없었고 그것이 기준이 됐다.**
    ★계율: **별호는 일상 한국어와 동음이의인 말을 쓰지 마라.**
  - ★★**성명·성별 확정**: 위인보(어질다·남)/불사 · 석원달(멀리 이른다·남)/철벽 ·
    **소청화(맑고 온화하다·여)**/혼박 · **당서온(펴고 따뜻하다·여)**/군림 ·
    뇌천강(하늘처럼 굳세다·남)/파천.
    ★★★**이름과 별호가 싸운다** — *어진 자*가 목숨을 뺏고 *맑은 자*가 넋을 묶는다:
    > **이름은 스승이 지었고, 별호는 세상이 붙였다.** 그 거리가 곧 그가 얼마나 변했는가다.
    ★**뇌천강만 안 싸우고**, 이름의 天이 별호 破天과 겹친다 (다섯 중 유일) —
    **이름 층에서도 「부순다만 안 변했다」가 증명된다.**
    ★성별은 **여성 둘을 정반대 결로** 두었다(혼박=묶는 자 / 군림=누르는 자) —
    홍일점 하나면 「마교의 요녀」 클리셰가 오히려 굳는다.
    ★★그리고 **천마는 이름이 없다**(버린 자다): **천마는 이름을 버리고, 마존은 이름을 받는다.**
  - ★★★**군림 계보에 사천당가의 피가 섞였다** (사용자 발안):
    2대 군림마존이 **당가에서 내쳐진 자**였고, 당서온(3대)은 그 후계라 **당가를 본 적이 없다** →
    > **성은 남았는데 집은 없다.**
    ★「계보가 성을 준다」의 **첫 실물** — 당(唐)이 핏줄이 아니라 **문중의 이름**이 됐다.
    ★내쳐진 이유【제안】: **재가 없이 독을 만졌다** (근거는 등록부에 있었다 —
    *당가는 재가로 독을 쥐고 독문은 재가 없이 흘러나간 구결로 쥔다*).
    ★**독문에서도 안 받아 줬다**【제안】 →
    > **정파가 내치고 사파도 안 받으면, 남는 문은 하나뿐이다.**
    ★★**독이 「군림」과 맞물린다** — 살림이 노역판인데 사람을 어떻게 붙들어 두나:
    > **그의 이름이 무서운 것이 아니라, 그의 해독제가 필요할 뿐이다.**
    「이름만 듣고 무릎 꿇게 한다」에 **실체**가 생겼다.
    ★★★**당가는 기억하지 못한다** → **정파는 잊었고, 마교는 잊지 않았다**가
    **한 가문 스케일로 되풀이**된다 (법칙이 강화된다).
    ★★★가장 큰 것: **마교의 기원이 200년 전에만 있었던 게 아니다 — 지금도 사람이 버려지고 있다.**
    마교가 **과거의 유물이 아니라 살아 있는 청구서**가 되고,
    정파의 썩은 뿌리에 **구체적 얼굴**이 하나 생겼다.
  - 눈: lint 0(경고 1=미기입 39) · world_clock **0·0** · selftest **20/20**

**⑱ ★★구 마존 성명 + 당가 재설계 + ★독의 한계 (2026-07-28 · 작업 세션 대화 이어서)**
  - **구 마존 다섯의 이름**: **자(慈)·야(野)·정(靜)·당직(唐直)·열(烈)**.
    ★★**넷은 외자이고 성이 없다** — **성은 집이 있는 자의 것**이니까.
    > **그들은 이름 한 자로 살았고, 후대는 없던 성을 지었다.**
    ★**당직만 성이 있다** → **넷은 성을 만들었고, 하나는 성을 가져왔다.**
    ★★이름의 층이 셋: **천마는 없고, 창시자는 한 자, 지금은 세 자** →
    > **이름이 길어지는 동안, 뜻은 짧아졌다.**
    ★그리고 파천 계보는 **구 「열(烈)」 / 신 「뇌천강」 둘 다 험하다** —
    넷은 이름이 순해지며 속이 험해졌는데 **여기만 처음부터 끝까지 그대로**다 (또 하나의 증거).
  - ★★★**당가 줄기 재설계 (사용자 지적: 「당서온이 신 마존으로 온 게 어색」)**.
    지적이 맞았다 — **계보의 성이 중간에 갈리는 것은 문중 논리상 이상하다.**
    → **창시자부터 당가 사람**으로 갈았다. 그러자 셋이 맞아떨어졌다:
    ① ★**진단이 경험에서 나온다** — *「자리에 앉은 자가 따로 있다」*는 **명문가 출신이라야** 나오는 말이다.
       **안에서 봤어야 안다** → 다섯 중 유일하게 **위에서 아래를 본 적 있는 자**.
    ② ★**독이 그 답의 손**이 된다 (아래 한계 참조).
    ③ ★★★**제5막에 표적이 생긴다**: *200년 전에 제 집을 등진 자의 계보가, 200년 뒤에 그 집으로
       돌아온다* → 군림 계보가 노리는 곳이 **사천**이다.
  - ★★**「왜 나왔나」가 더 좋아졌다**: 「재가 없이 독을 만졌다」(죄) →
    **「내쳐진 것이 아니라 아무것도 시키지 않은 것」**.
    > **당가는 그를 내치지 않았다. 다만 아무것도 시키지 않았다.**
    ★★★그리고 이것이 **마교의 기원과 글자 그대로 같다** —
    마교는 *죽임당한 게 아니라 죽게 내버려졌고*, 당직은 *내쳐진 게 아니라 아무것도 못 하게 되었다.*
    ★그리고 **버려지는 데 신분은 상관없다** — 「문턱이 없다」가 밑바닥만 뜻하는 게 아니게 된다.
  - ★★★**당가의 최고는 독이 아니라 암기다** (사용자 확정).
    > **세상은 당가를 독으로 알고, 당가는 암기로 산다.**
    ★등록부가 이미 그렇게 생겼다 — *「방계 전승이 **독문으로** 흘러나갔다」* →
    **흘러나간 것이 독이고 안 흘러나간 것이 암기**다 (그래서 「암기문」은 없다).
    ★★그리고 「아무것도 안 시켰다」에 **내용**이 생겼다: **독은 만지게 두고 암기는 안 가르쳤다** →
    > **그는 당가의 손이 아니라 당가의 그림자를 가져갔다.**
    ★제5막 귀결: **200년 만에 돌아갔더니, 집에는 그가 배운 적 없는 손이 있었다.**
  - ★★★**독의 한계 — 세계가 서는 규칙** (사용자 확정):
    **독은 고수를 죽일 수 있지만 절대고수는 죽이지 못한다.**
    ★한계가 없으면 **당가가 진작 천하를 먹었어야 한다** → 그것이 **당가가 최고가 아닌 이유**다.
    > **당가는 누구든 죽일 수 있었지만, 아무도 이길 수 없었다.**
    ★★**세 자리와 맞물린다**: 맹주·패주·천마 = 레이드 규격 = **절대고수** → **독으로 못 죽인다.**
    **그것이 세 자리가 절대적인 까닭**이 된다 (무공이 세서만이 아니라 **독조차 안 통해서**다).
    ★★★그리고 **구 「호세」의 답이 실패한 이유**가 됐다:
    > **독은 앉은 자를 죽일 수 있어도, 자리를 못 바꾼다.** (가주는 죽여도 가문은 안 죽는다)
    → 「다섯의 답이 전부 틀렸다」에 이 계보의 **구체적 실패**가 생겼고,
    지금 당서온이 천마를 넘볼 때도 같은 벽이라 — **그가 노리는 것은 천마의 목숨이 아니라
    천마가 없는 틈**이다.
  - 눈: lint 0(경고 1=미기입 39) · world_clock **0·0** · selftest **20/20**

**⑲ ★★패주 확정 + ★★★무력의 층 교정 (2026-07-28 · 작업 세션 대화 이어서)**
  - ★★**교정: 패주는 「다수전 특화」가 아니라 「그냥 강하다」** (사용자).
    ★그전 값은 **원작 사도천주의 심상구현에서 빌려온 것**이었다 → 걷었다 (§8 층 3).
    → **특화가 없는 것이 특징이다 — 약점이 없다.**
    > **그는 무엇에 강한 것이 아니라 그냥 강하다.**
  - ★★**다수전은 천마 쪽으로 갔다.** 그리고 세 자리의 무력이 **세력의 결과 같은 모양**으로 갈렸다:
    > ★★★**맹주는 함께 싸우고, 패주는 혼자 싸우고, 천마는 여럿을 상대한다.**
    (정파는 합의하고 · 사파는 개인이고 · 마교는 나라다)
    ★천마가 다수전인 근거: **신은 한 사람을 겨누지 않는다** · 천마군림보(위압)가 이미 그 결 ·
    제5막에 **나라가 움직인다**.
  - ★★★**천마는 다른 층이다 — 맹주와 패주가 합쳐도 이기기 힘들다** (사용자 확정).
    ★「셋 다 레이드 규격」이라 적어 둔 것이 **셋을 대등해 보이게** 만들고 있었다.
    설정 근거는 이미 있었다(*그릇이 되면 신이 들어온다*):
    > ★★★**사람 중에 가장 강한 자가 패주이고, 천마는 사람이 아니다.**
    → 패주의 「혼자 싸울 때 가장 강하다」와 **안 부딪힌다.**
      그리고 마교가 「금기 계열」인 까닭도 여기다 — **사람이 아닌 것을 섬긴다.**
    ★★이 층이 넷을 설명한다:
      ① 왜 마교가 200년 안전했나 (천마 하나가 있으니)
      ② ★★왜 정파가 200년 안 쳤나 — **못 이겨서**다 →
         > **마교는 미안해서 안 갔고, 정파는 못 이겨서 안 갔다.** (같은 200년을 다르게 살았다)
      ③ 제5막이 비등한 까닭이 더 정확해진다 — 초대가 **아파서**다 (온전했으면 일방적)
      ④ ★★★**제4막 연합의 진짜 이유**: 손을 잡아도 못 이긴다 →
         > **둘이 손을 잡은 것은 이기기 위해서가 아니라, 혼자서는 하루도 못 버티기 때문이다.**
    ★밸런스: **2대(플레이어)는 처음부터 그 힘이 아니다** (지목 직후엔 조금 이고 제5막쯤 많이 인다).
  - ★**패주 = 오중산(吳重山) · 별호 없음** (사용자 확정).
    > **별호는 여럿일 때 필요하다. 그는 하나였다.**
    별호는 **구별하려고** 붙이는 것인데 비교할 대상이 없으니 안 생긴다.
    ★★초대 천마와 **짝**: **천마는 이름을 버렸고, 패주는 별호가 안 생겼다.**
    ★오(吳)=흔한 성(명문이 아니라는 표시) · 중산(重山)=밀리지 않는 산.
    ★마존은 「순한 이름 ↔ 험한 별호」인데 **패주는 안 싸운다** →
    > **사파는 이름을 안 바꾼다. 바꿀 만한 집이 없었으니까.**
    (*빌린 이름으로 서지 않는다*와 정확히 맞고, 이름을 주고 버리는 마교와 대비된다)
    ★폐기: 별호 **일인진(一人陣)** — 다수전 특화에서 나온 이름이라 근거가 사라졌다.
  - 눈: lint 0(경고 1=미기입 39) · world_clock **0·0** · selftest **20/20**

**⑳ ★★총관 = 범소천(철산) · 패주의 의형제 (2026-07-28 · 작업 세션 대화 이어서)**
  - ★★**이 자리의 정체가 정해졌다**: 패도천의 문법은 「힘이 정한다」인데 **총관은 실무**다 →
    > **힘으로 정하는 곳에서 딱 한 자리만 힘으로 안 정한다. 그리고 그 자리가 없으면 아무것도 안 굴러간다.**
    ★그래서 기존 취약점(*총관 하나가 무너지면 실무가 멎는다*)이 자명해졌다.
    왜 하나뿐이냐 — **사파는 회의를 안 한다. 그래서 한 사람이 다 한다.**
    ★패주가 **그냥 강하기만** 하니: **패주는 패도천을 만들었고, 총관이 패도천을 굴린다.**
    (*패주는 실리 우선*이라 했는데 — **그 실리를 재는 것이 총관**이다)
  - ★**내가 낸 「도전했다 진 자」 안은 폐기.** 사용자 안(**의형제**)이 낫다:
    ★★**비어 있던 자리를 메운다** — 등록부에 **패도천 창설 이야기가 없었다.**
    혼자 만들지 않았다: **마교는 다섯이 세웠고, 패도천은 둘이 세웠다**(사파는 뭉치는 게 서툴다).
    ★★**의형제인 것이 사파답다**: **계보가 없는 자들이 계보를 만드는 유일한 방법이 의형제다.**
    ★★★**총관이 형인데 아우 밑에 앉는다** → **사파에서는 형도 아우 밑에 앉는다. 힘이 정하니까.**
  - ★**초대 천마와 짝**: 마교 「파천」 계보도 초대의 친우였는데 —
    > **천마의 친우는 200년 전에 죽었고, 패주의 친우는 아직 옆에 있다.**
    > **천마는 200년째 혼자이고, 패주는 아직 둘이다.**
  - ★★★**플레이어 패주에게 처음부터 가시가 딸린다** (패주는 **이겨서** 얻는 자리다):
    > **패주가 되면 부하가 하나 딸려 오는데, 그는 당신이 죽인 자의 형제다.**
    > ★★**천마는 우군을 물려받고, 패주는 원한을 물려받는다.**
    ★그가 **절대 배신은 안 한다**는 것이 오히려 무섭다 — 실무를 쥔 채, 원한을 품은 채,
    **끝까지 제 일을 한다.**
  - ★이름: **범소천(范素川)** · 별호 **철산(鐵算)**(★별호가 **무력이 아니라 일**에서 나온다).
    **소천(내) ↔ 중산(산)** — **패주는 산이고 총관은 내다. 산은 안 움직이고, 내가 흐른다.**
    ★별호의 유무도 둘을 가른다: 패주는 하나뿐이라 안 생겼고, 총관은 **하는 일이 있어서** 붙었다.
  - 눈: lint 0(경고 1=미기입 39) · world_clock **0·0** · selftest **20/20**

**㉑ ★혈교의 그것 — 이름은 있는데 아무도 모른다 (2026-07-28)**
  - ★한 번 **「이름이 없다」**로 잡았다가 사용자가 갈았다: **「있겠지만 아무도 모른다」**.
    ★그게 낫다 — **「없다」면 처음부터 사람이 아니었던 것 같다.**
    **있는데 아무도 모른다**여야 **그도 한때 사람이었다**가 남는다.
    > ★★★**그에게도 이름이 있었다. 다만 그 이름을 기억하는 자가 아무도 안 남았다.**
  - ★왜 안 남았나: 혈교가 지나간 자리는 **마을이 통째로 비고 시신도 안 남는다** →
    > **그를 아는 사람이 다 죽어서, 그의 이름도 같이 죽었다.**
    ★그리고 **책이 그를 골랐다**(*혈교는 사람을 꾀지 않는다. 책이 사람을 고른다*)와 맞물린다.
  - ★별호도 안 붙는다 — **이름은 남이 불러 주는 것인데 부르는 자가 없다**
    (조직이 아니라 감염이라 *서로를 알아보기는 하는데 부르지는 않는다*).
    ★그가 수장인 것도 **아무도 정해 주지 않았다**:
    > **아무도 그를 수장으로 세우지 않았다. 그냥 아무도 그보다 앞에 서지 못했을 뿐이다.**
    ★그래서 **「수장」이라는 말도 안 맞는다**: *혈교에 우두머리는 없다. 가장 깊이 빠진 자가 있을 뿐이다.*
  - ★★★**세 자리의 「이름 문제」가 각각 다른 이유를 갖게 됐다** (안 그러면 결이 흐려진다):
    · **초대 천마** — **버렸다**(그릇이 되려고). 그런데 **모두가 안다**
    · **패주** — 별호가 **안 생겼다**(비교 대상이 없어서). 그런데 **제 이름으로 불린다**
    · **혈교의 그것** — **있는데 아무도 모른다**(아는 자가 다 죽어서)
    > ★★**하나는 이름을 버렸는데 모두가 알고, 하나는 이름을 안 버렸는데 아무도 모른다.**
  - ★배선 사고 1건(자가 수습): world_bible 에서 혈교 절을 **마교 장 안에** 끼워 넣었다.
    제목 구조를 뽑아 보고 잡았다. ★계율: **긴 문서에 절을 넣을 때는 넣고 나서 제목 목록을 뽑아라.**
  - 눈: lint 0(경고 1=미기입 39) · world_clock **0·0** · selftest **20/20**

**㉒ ★★맹주 후보 + 정파 세 수장 (2026-07-28~29)**
  - ★**명단**: 후보 **소림·남궁** · **무당은 사퇴** · 뺀 곳 **개방**(사용자) ·
    **제갈**(군사를 낸다 — 맹주 옆에 앉는다) · **화산**(플레이어 입문 문파라 편향).
    ★★그리고 **남궁이 맹주가 된다** (사용자: 「웬만하면 남궁」).
    > ★★★**셋 다 될 수 있었다. 하나는 원하지 않았고, 하나는 말할 수 없었고, 하나만 남았다.**
    ★**가장 강해서가 아니라 남아서**다 — *맹은 이기려고 커졌고 커지느라 아무나 받았다*와 같은 결이고,
    **누구도 원해서 만든 게 아니다**가 된다.
  - ★★**셋이 썩은 뿌리 앞에서 각각 하나만 잘한다** (엔딩 입력 `맹주_진영`이 실제로 일하게 된다):
    **소림은 옳게 하려다 늦고, 남궁은 빨리 자르다 약해진다** (개방은 뺐다).
  - ★★★**「직접적인 작명」을 걷어냈다** (사용자 지적).
    1판 **수현(守玄)·은검(隱劍)** 은 **500년 당직을 이름이 말하고 있었다** —
    ★**아무도 모르는 일이 이름에 적혀 있으면 안 된다.**
    → **원명(圓明)·불괴 / 남궁호(南宮浩)·창검 / 현담(玄潭)·유검**.
    ★그리고 **「이름과 별호가 같은 말을 한다」는 내 제안 자체를 폐기**했다 — 그게 병의 원인이었다.
    ★이름은 **태어날 때 받은 것**이니 그 사람이 어떻게 될지 모르고 지은 것이어야 한다.
  - ★★**이름 문법이 세 갈래로 갈렸다**:
    > **중은 성을 버리고, 세가는 성이 곧 이름이고, 도사는 이름을 새로 받는다.**
    (*정파는 세력이 아니라 연합의 이름이다*와 맞물린다 — **하나가 아니다**)
    ★★★그리고 **별호 붙이는 법이 세 세력을 가른다**:
    > **정파는 무공으로 불리고, 사파는 하는 일로 불리고, 마교는 무엇이 되었는지로 불린다.**
  - ★★**앞서 지은 것을 훑었다** (사용자 요청) — 둘을 찾았고 **둘 다 남기기로 했다**:
    ① 구 마존 외자 이름이 **별호의 되풀이**(자↔활인 · 야↔낙토 · 정↔안혼 · 열↔파천)
    ② **뇌천강**이 별호 **파천(破天)**과 **天을 공유**
    → ★사용자 판단: **괜찮다. 그대로 둔다.**
    ★★★그리고 그 판단을 **등록부에 적었다**.
    ★계율: **검토하고 남기기로 한 것은 남긴다고 적어라** —
    안 적으면 다음 사람이 같은 것을 또 「발견」해서 고치려 든다.
  - 눈: lint 0(경고 1=미기입 39) · world_clock **0·0** · selftest **20/20**

**㉓ ★★군사 = 제갈태(필진) — 자리를 섬기는 자 (2026-07-29)**
  - ★**제갈이 낸다** (그동안 「자연스러우나 미확정」이었다). ★가주와는 **다른 사람**이다.
  - ★★★**총관과 정확히 반대다**:
    총관은 **의형제**(사적)이고 **패주 하나**를 섬기는데, 군사는 **가문이 보낸 사람**(공적)이고
    **맹**을 섬긴다(맹주가 아니다) →
    > **총관은 사람을 섬기고, 군사는 자리를 섬긴다.**
    ★그것이 두 세력의 결과 같다 — **사파는 사람이고, 정파는 기구다.**
  - ★★**맹주가 바뀌어도 남는다.** 플레이어 맹주에게 딸려 오는데 —
    > **그는 당신을 좋아하지도 싫어하지도 않는다. 다음 맹주에게도 똑같이 할 것이다.**
    ★총관이 **원한**을 딸려 보내는 것과 달리 군사는 **무관심**을 딸려 보낸다.
  - ★★★**썩은 뿌리의 맨 앞자리가 됐다.** 무림맹만 자리가 셋이고(맹주+장로원+군사)
    *「합의체는 책임이 흩어진다」*가 이미 적혀 있었다 →
    > **맹주는 앞에 서고, 장로원은 결정하고, 군사만 책임진다.**
    그런데 **자리를 섬기는 자는 자리를 지키려 한다** →
    > ★★★**맹을 지키는 것이 그의 일이다. 그래서 맹의 흠도 그가 덮는다.**
    ★기존 확정(*왜 썩는가 — 맹을 지키려다 덮는다*)에 **이름 있는 얼굴**이 하나 생겼다.
    ★★**그가 악당이 아니라는 것**이 중요하다 — 진심으로 맹을 지킨다 (주제와 맞는다).
  - ★**제갈이 군사를 낸다 = 맹주를 안 한다**는 뜻이다 →
    > **제갈은 맹주를 못 한 것이 아니라 안 한 것이다.**
    (맹주는 앞에 서고 표적이 되는데 군사는 뒤에서 오래 남는다 — 세가의 실리이고 살짝 서늘하다)
  - ★**별호가 정파 문법에서 벗어난다**: 정파는 무공으로 불리는데 그는 무인이 아니다 →
    > **정파는 무공으로 불리는데, 그만 하는 일로 불린다.**
    ★**무인들 사이의 비무인**임을 별호가 보여 준다 (그리고 그건 **사파 문법** — 총관 「철산」과 같다).
  - ★이름은 한 번 **제갈서(諸葛曙)**로 냈다가 사용자 요청으로 **제갈태(諸葛泰)**로 갈았다
    (결·별호는 그대로 승인).
  - 눈: lint 0(경고 1=미기입 39) · world_clock **0·0** · selftest **20/20**

**㉔ ★★통독 전 훑기 — 어긋남 3 · 구멍 1 · 안 적힌 연결 2 (2026-07-30)**
  ★사용자가 정본을 읽기 전에 훑어 달라 했다. **오늘 여러 번 뒤집힌 자리**부터 봤고, 여섯을 찾았다.
  - **어긋남 ①** `초대_천마.왜_지배하지_않는가` 에 **「스승이 제자를 막는 말」** —
    마존은 **제자가 아니라 동료**로 정정됐는데 **이 줄이 안 따라왔다.** → 「동료를 막는 말」로.
  - **어긋남 ②** `endings.inputs.맹주_진영: values: [미정]` —
    ★**명단은 이미 확정됐는데 등록부가 「미확정」이라 말하고 있었다.**
    → `[sorimsa, namgung, npc, player]` + 주석(무당은 사퇴라 판정값에 없다).
  - **어긋남 ③** `paeju` 에 **전투_규격이 없었다** (맹주·천마엔 있다). → `레이드` 기입.
  - ★★**구멍 ④ — 「사파에는 합격진이 없다」의 이유가 날아갔다.**
    그전 이유는 *「패주 혼자가 진 하나보다 나으니까」*였는데, 패주의 무력이
    **다수전 특화 → 그냥 강함**으로 교정되면서 **근거가 사라지고 사실만 남아 있었다.**
    → 새 이유를 **사파의 정의에서** 뽑았다 (패주와 무관하게):
    > ★★★**진(陣) 속에서는 누가 셌는지 아무도 모른다.**
    **못 배운 게 아니라 안 배웠다** — 다섯이 하나처럼 움직이면 **이름이 지워진다.**
    ★★그리고 제4막이 무거워졌다:
    > **사파가 진을 배우는 것은 무공을 배우는 것이 아니라 이름을 내려놓는 것이다.**
    ★그것이 *「사파는 진 대신 「수(手)」를 준다」*의 근거이기도 하다 — 수는 혼자 쓰니 이름이 안 지워진다.
  - **연결 ⑤** 구 「차지한다」 무공_결에 `(+독)`을 넣었는데 **구신_대조 표에는 독이 없었다.**
    → 대조표에 **「위를 끌어내리던 독 → 아래를 못 떠나게 하는 독」**을 기입.
  - **연결 ⑥** **무당이 창설 제안자인데 사퇴한다**는 연결이 안 적혀 있었다.
    → `★제안자_셋의_거취` 신설:
    > **셋이 만들자고 했는데, 하나만 앉았다.**
    > **만들자고 한 셋 중 둘이 물러났고, 만들자고 안 한 하나는 원하지 않았다.**
  - ★★계율 하나: **큰 값을 교정하면 그 값을 근거로 삼던 문장들을 따라가라.**
    패주의 무력을 갈았을 때 「일인진」은 지웠는데, **그 무력을 근거로 서 있던 「사파에 합격진이
    없는 까닭」은 못 따라갔다.** 지운 것만 세면 안 되고 **기대고 있던 것**을 세야 한다.
  - 눈: lint 0(경고 1=미기입 39) · world_clock **0·0** · selftest **20/20**

**㉕ 남은 문답 (다음 회차)**
  ② 녹림·장강수로채가 패도천에 드는가 ③ 패주·혈교 수장·초대 천마의 이름·내력
  ④ 혈교에 계층이 있는가 ⑤ 「수(手)」 목록 ⑥ 군사를 어느 세가가 내는가 · 맹주 후보 명단
  ⑦ D 엔딩 조건 · 집계 단위·임계값 ⑧ ★**마교 = 「SSS급 죽어야 사는 헌터」 천마실록 각색** —
     검색 조각만 얻었고 **나무위키 본문은 403**이다. 자료(PDF) 요청 중

★ㄷ 대화 1·2회차에서 못 받았던 것 (일부는 위 ⑩으로 이월 · 칠성사 관련은 폐기로 소멸):
  ① 천주의 **이름·내력** (혈교가 아니라는 것만 확정) ② 칠성사 일곱을 다 쓸 것인가 ·
  천추가 잠입한 「정파 기밀부서」의 대응물(무림맹 하위 정보 조직?) · **천선이 하오문주로 위장했다면
  혈교가 사도천 성원 하나를 통째로 쥔 셈인가** ③ 적림십팔채: 알파에 이미 있는가 · 총채주는 누구인가 ·
  **제3막에서 사도천 편인가 중립인가**(별개 기구라 따로 정해야 한다) · 제4막 연합에 끼는가
  ④ 「수(手)」로 주는 것의 구체 목록 ⑤ 맹주 후보 목록 · D 엔딩 조건 ⑥ 개인 노선·추적 성패의
  집계 단위·임계값 ⑦ Stage 4 에서 갈라질 때 어느 성원이 정파 쪽으로

★★진행 (2026-07-26 · ㄱㄴㄹ 확정 → **정본 교체 + 7막 재작성 + 분기 문법 신설**):
- **ㄱ 답**: 「맹이 없는 게 당연하다 — 각 정파가 그냥 대립만 했다가 **홀로 힘으로는 부족하다는 걸
  깨닫고** 무림맹이 창설되는 형태」 → **연합은 시작이 아니라 결과다.** 옛 제1장의
  「정파 연합이 1차 침략을 격퇴」가 폐기됐다 (격퇴할 연합이 그때는 없다).
  → 제1막에 박 `gakja_ui_daeeung` 신설: **각자 막아섰고 각자 부족했다** — 그것이 창설의 동력이다
  (위협의 크기가 아니라 **제 힘의 한계**를 본 것이 맹을 만든다).
- **ㄴ 답**: 「처음부터 위장. **하지만 혈교가 계기일 뿐** — 하나만 뭐가 터졌어도 이미 다툼이
  일어나는 상황이었다」 → 혈교는 원인이 아니라 **방아쇠**다.
  → 제0막에 박 `munpa_ui_gyeongjaeng`(후계 다툼·이권·앙숙) 신설 — **혈교와 무관한 갈등**이
  먼저 깔린다. 그것이 「하나만 터졌어도 났다」의 기계적 근거다.
- **ㄷ 보류**: 제3·4막(사도천 대립·무림연합) 세부는 「다시 대화를 통해 자세히」 — **골격만** 세우고
  값은 전부 【제안】으로 표시했다.
- **ㄹ 답**: 「분기 문법 늘리기 신설」 → 아래.

★★안전 확인 먼저 (재작성 전 실측 — 이것을 안 하면 라이브 세계가 멎었다):
- `world_meta 세계막=prologue · 막진입일 36 · 현재일 36` · **박발화 키 0건**
  (prologue 박은 +12/+15 = 세계일 48/51 이라 아직 안 터졌다 → **완전히 깨끗한 판**).
- ★`WorldClockEngine:252` 는 등록부 밖의 막 id 를 만나면 **예외를 던진다** →
  그래서 `prologue` **id 를 보존**하고 내용만 갈았다 (이름은 「제0막 — 혼란의 서막」).

★정본 교체: `docs/story_summary.md` 「메인 스토리 흐름」 5단계 → **7막 + 엔딩 4분기**.
  머리에 ㄱㄴ 두 문장을 전제로 박았다 (이 이야기가 왜 이 모양인지가 거기 있다).

★`world_clock.yml` acts 7막 재작성 (전부 눈을 통과하는 문법으로):
  0 prologue(제0막 혼란의 서막) → 1 sadocheon_deungjang → 2 murimmaeng_changseol(human gate) →
  3 sadocheon_daeripp(★ㄷ 골격) → 4 murim_yeonhap(★ㄷ 골격) → 5 magyo_chimgong(human · 살상 PvP 개방) →
  6 hyeolgyo_siltche(human)
- ★옛 정본의 「마교 격퇴는 확정」이 **폐기**됐다: 제5막 종결박 `daejeon_gyeolgwa` 의 해소가
  승패를 남기고, 그것이 엔딩 입력이 된다.
- 제6막 `bonsaek` 박에 `victims_add: [magyo]` — **마교가 피해자 명단에 오른다.**
  「사도천·정파·마교조차 공격한다」가 명분 계산으로 옮겨진 자리다 (진영 무관 협동의 근거).

★★**엔딩 분기 문법 신설** (`endings:` 절):
- **왜 acts 로는 안 되는가**: acts 는 선형이다 (order 0..N-1 연속 + requires_beat 사슬 —
  눈 ①이 강제한다). 엔딩 넷은 **동시에 후보**이고 하나만 실현된다 → order 를 줄 수 없다.
- 원칙 다섯: ① 마지막 막의 종결박 해소가 판정 시점 ② 입력은 **기존 축에서만** 읽는다(발명 없음)
  ③ **정확히 하나** — priority 낮은 수부터 첫 성립을 취하고 **마지막은 fallback**(조건 없음)이라
  「아무 엔딩도 없는 세계」가 불가능하다 ④ 수치·엔딩 id 노출 금지 ⑤ ★**D(개별 문파)는 개인 엔딩**이라
  세계 엔딩과 **동시에 성립한다** → `scope: personal` 로 갈랐다.
- ★옛 에필로그(혼돈의 재)는 **C 혈교 암약**으로 흡수됐다 — 다음 시즌의 씨앗은 그 엔딩이 심는다.
- ★`wiring_status: 미배선` 을 **스스로 밝혔다** (엔진은 마지막 막에서 return 으로 끝난다).
  등록부가 먼저 서고 코드가 따라오는 순서 — tempo 절이 이미 그렇게 살고 있다.

★눈: `world_clock_audit` **⑤ 엔딩 분기** 절 신설 (판정 시점 실존 · **fallback 정확히 하나와 그 자리** ·
  do·소문·지역 눈금 · 입력 출처 실존) + **뮤테이션 6건** 신설.
- ★그리고 **옛 뮤테이션 5건이 낡았다** — acts 를 갈았으니 앵커가 옮겨졌다. 도구가 「시험 자체가
  낡았다」를 스스로 경고해 줘서 전부 새 막으로 갱신했다 (기대 문장의 옛 막 id 까지).
  ★이 경고가 없었으면 **낡은 시험이 조용히 통과하는 눈**이 됐다.
- `world_clock_audit` 위반 0 · 경고 0 · **selftest 20/20**.

★실측: MVT 재배포 ✓ (Done 22.6s · ERROR 0) · **봇 재기동 ✓ — 예외 0** (7막 등록부를 읽고
  `세계막=prologue` 가 그대로 유효 · JDA Login Successful · DB postgresql).
  ★봇 재기동이 이번 회차의 진짜 실측이다: 막 id 를 잘못 갈았으면 여기서 IllegalStateException 이 났다.

★남은 것: **ㄷ 대화**(제3·4막 세부) · 맹주 후보 목록(엔딩 입력 `맹주_진영` 이 미정) ·
  D 엔딩 조건 · **무림맹·사도천이 `faction_politics.yml roster` 에 들어가야 하는가**
  (지금은 없다 — 그래서 사도천을 겨눈 명분을 쓰지 못했다. roster 는 「명분 계산 참여 세력」이고
  기구는 명분을 **내는** 쪽이라, 같은 표에 담는 것이 옳은지 자체가 문답이다) ·
  강시 부대(npc_lifecycle·npc_combat) · 연합 기술 공유 기능.

★★사용자가 7단계 거대 줄기를 줬다 (Stage 0~6 + 엔딩 4분기). **아직 배선하지 않았다** —
`world_clock.yml` 은 살아 있고(자정마다 박이 터진다) 눈이 대조하므로, 결정 전에 옮기면
세계 시계가 거짓말을 한다. 전문은 `docs/design/world_stages_v2.md` 에 **【제안·미승인】**으로 옮겼다.

**한 줄**: 기존 정본은 「마교 → 혈교 → 삼파전」이고, 새 안은 「혈교 위장 → 무림맹 창설 → 사도천
→ 정사연합 → 마교 침공 → 혈교 실체」다. 두 축의 차이는 순서가 아니라 **무림맹이 전제인가
사건인가**에 있다.

★충돌 4건 (임의로 고르지 않았다):
- **ㄱ 무림맹이 전제인가 사건인가** ← 가장 큰 것. 기존 제1장은 「**정파 연합이 1차 침략을 격퇴**」로
  끝나는데, 새 안에서는 Stage 2 까지 맹이 없다 → **격퇴할 연합이 없다.** 표현이 아니라 배선의
  문제다 (`magyo_amryu.chimryak_yego` 가 명분을 세우고 `ilcha_chimryak_gyeoktoe` 가 방어전을
  여는데 그 주체가 사라진다).
- **ㄴ 마교·혈교 순서 역전.** 기존은 혈교가 중간의 신흥 위협이고 마교가 최종 보스. 새 안은
  혈교가 **Stage 0 부터 위장해 깔려 있고 마지막에 실체**를 드러낸다 (Stage 0 의 테러가 6 에서
  회수된다 — 서사적으로 더 강하다. 다만 **다른 이야기다**).
- **ㄷ 정사 연대의 상대**: 기존 정파+마교(2장) → 새 안 정파+**사도천**(Stage 4).
- **ㄹ 에필로그 단선 → 4분기.** `world_clock.yml` 에는 **분기 문법이 없다** (acts = order 선형 +
  requires_beat 사슬) → 분기를 넣으면 막 등록부 문법을 늘려야 한다.

★결정 없이도 확정돼 기입한 것:
- **무림맹 문답 2건 닫힘**: 「알파 시점에 서 있는가」 → **아니다** (Stage 0 = 창설 전).
  그 귀결로 「맹주 실존 여부」도 닫힌다 — **맹은 없고 맹주도 없다.** 선출은 Stage 2 이벤트.
  기계 판독: `exists_at_alpha: false` · `founded_in_stage: 2` · `maengju: 없음` ·
  `founding_proposers: [남궁, 제갈, 무당]`(셋 중 누구인지는 미결).
  ★그래서 지금 무림맹 등재는 **미래의 자리**다 — 그러나 등록부에 있어야 한다:
  Stage 0 의 담론(「무림을 하나로 묶어야 한다」)이 가리키는 것이 이것이고, 소문·의뢰가 그것을 실어야 한다.
- **★★사도천(邪道天) 신설** — 무림맹의 **대칭**. 무림맹을 세울 때 만든 축(분류/명단/기구)이
  그대로 적용된다: `unorthodox` 는 분류이고, 하오문·녹림·수로채·살막·낭인회는 각자의 세력이고,
  **사도천이 기구다**. ★사파에 기구가 없던 것은 새 줄기 때문이 아니라 **원래 구멍**이었다 —
  정파는 토벌령을 낼 수 있게 됐는데(무림맹) 사파는 **그것에 응답할 주체가 없었다.**
  결: 명분은 「정파는 위선이다」 · `no_arts_by_design`(성원의 무공을 쓴다 — 힘이 **명분**에 있다) ·
  `infiltrated_by: hyeolgyo`(★일부가 혈교 위장 — 사도천 자신도 모른다. 그래서 「누가 진짜
  사파인가?」는 플레이어의 질문이자 **사도천의 질문**이다) · Stage 4 에서 **갈라진다**
  (통째로 적이 아니다 — 그 갈라짐이 콘텐츠다).
- 눈: lint 0 · combat 린트 0 · motion 0 · game 0 · **world_clock 0**(안 건드렸다는 증거) ·
  selftest **26/26** (⑳-4 프로브가 숫자를 박아 뒀다가 사도천 등재로 깨져 **상대 비교**로 고쳤다).

★신설이 더 필요한 것 (문답): **무림연합**(Stage 4 — 무림맹과 별개인가 확장인가) ·
**강시(僵屍) 부대**(Stage 6 — npc_lifecycle·npc_combat 축).
★원문이 「전체 흐름 도식 (최종)」에서 **끊겼다** — 도식은 받지 못했다.

### B-196 · ★★★ 화산파 건축 강화 — 20구역 마스터플랜 캠퍼스 (사용자 확정 2026-08-02)
- **상태**: 진행
- **부분 승인**: ★산문 구역은 **승인·동결** (2026-08-06 — 아래 「승인 기록」)

#### ★★승인 기록 — 산문 (2026-08-06)
```
B-196 산문 구조: 승인      치수와 좌표: 동결      입면 판독 문제: 해결
lint 체계: 승인            최종 색·재료: 리소스팩 적용 후로 보류
목표 사진 재현도: 아직 외관·조경 단계 필요
```
- **증거**: `selftest 250/250` (그중 「★동결」 눈 5건이 치수를 잠근다) ·
  캠퍼스시험 `검수 깨끗 (열 18002 · 유출 0)` · 입면 lint 4/4 ·
  같은 좌표 회귀 사진 `run/stage_render/grn_1산문정면.png`
- **정본**: `config/blueprints/hwasan_gate.yml` (`design_intent` 절에 까닭이 남는다) ·
  `docs/design/hwasan/01_산문/` (도면 5종 — `tools/blueprint_draw.py` 가 정본에서 뽑는다)
- **동결값**: 중앙 통로 7 · 적주 간격 3·3·5·(7)·5·3·3 · 정면 좌우 대칭 ·
  계단 폭 9→11→7 · 표고 h46→h148 (총 102칸)
- **★표고는 안 올린다**: 콘셉트 수치(Y90→Y280)는 설계 치수가 아니라 과장된 시각 비례다
  (사용자 판단). 웅장함은 주변 산세로 낸다.
- **다음**: 산문을 더 고치지 않는다 → **② 외원/입구 광장 + 산문 주변 절벽·정원·옹벽 분절**

#### ★★문전 비움 — 결정 ㉦ 이 닫혔다 (2026-08-06 · 사용자 확정)
사용자 진단: 「문제는 실제 보행 폭이 아니라 **문간의 시각적 폭**이다. 통로가 7 이어도
정면에서는 「깃대|적주|문살|통로|적주|등롱」으로 읽혀 5칸으로 압축돼 보인다.」
- 문 앞 i 0~17: 깃대 **금지** · 높은 독립 등롱 **금지** · 낮은 석등(3칸) **한 쌍만**(대칭)
- 깃대 첫 자리: 11칸 전이 참(i18~21)의 외곽 모서리 **±8** (참의 난간 ±6 밖)
- 하단 계단(i22~)의 깃대·등롱 리듬은 **유지** (자리만 옮겼다 — 주기 9·11 그대로)
- 소나무 줄기: 산문 정면 투영 밖으로 (문전 구간에서 ±12 → **±16**)
- **증거**: `selftest 262/262` (문전·투영 눈 12건 신설) · **변이 시험**: 깃대를 옛 자리(6)로,
  소나무를 옛 오프셋으로 되돌리면 눈 5건이 짖는다 (조성만 고치고 눈은 조용한 일이 없다) ·
  같은 좌표 회귀 사진 `run/stage_render/산문정면.png` (문 앞에 낮은 석등 한 쌍만 선다)

#### ★문 앞을 가리던 것은 소나무가 아니라 **바위 기둥**이었다 — 깎았다 (사용자 확정)
사진의 「오른쪽 큰 줄기」를 **실측**했더니 우리 소나무가 아니었다:
**축선 +5 · i 4~9 에 선 주상절리 돌기둥**(minecraft:stone)이 포장(y-15)에서 **y+8 까지**
곧게 서고 (산문 지붕 마루가 y+2 — **지붕보다 6칸 높다**), 그 꼭대기에 산군 소나무가 얹혀
수관이 축선 +2~+3(통로 위)까지 드리운다. 정면 사진의 회백색 기둥이 이것이다.
- **까닭**: 산세·산군이 먼저 서고 캠퍼스가 뒤에 깎는데, `clearAbove` 는 **제 보행 폭만**
  비운다. 난간 한 칸 밖의 20칸 바위는 아무도 건드리지 않는다.
- **사용자 확정**: **정면 투영을 통째로 깎는다.** `clearHalf(i)` 가 문전 구간에서 ±14 를
  표고까지 비운다 (그 밖은 종전대로 보행면+난간만 — 계단 곁 절벽·숲은 그대로다)
- **★한 번 더 물렸다**: 바위를 걷어내자 **그 자리에 조경 소나무가 남았다** — 캠퍼스 조경은
  접근로 포장 **뒤에** 심으므로 깎아 놓은 자리에 도로 꽂는다 (산문 자리 축선 ±5·6).
  → `inGateFacade` 를 **비우는 손과 심는 손이 함께** 읽게 하고 자리를 ±16 으로 밀었다
- **★계율**: **가리는 것은 재료를 안 가린다.** 「나무가 건축을 가리지 않는다」가 **나무만**
  보고 있었다 — 바위도, 조경도, 소품도 같은 투영을 지켜야 한다
- **증거**: `selftest 269/269` · 신선 월드 재조성 · 검수 깨끗(열 18002 · 유출 0) ·
  `run/stage_render/산문정면.png`(정면이 통째로 읽힌다) · `문전평면.png`(문전이 비었다)

- **분류**: 결정
- **위치**: `docs/design/hwasan_build_enhancement_v1.md` (시안 v2) · `화산파/` (레퍼런스 13장 —
  미추적 바이너리, 마스터플랜 = `…07_55_36.png`) · `server-mvt/.../forge/TerraceForge.java` ·
  `MvtCommand` 캠퍼스시험 · `tools/TerraceForgeSelfTest.java`
- **단계**: P1
- **의존**: B-146 (건물이 뜬다 — 테라스가 그 처방) · B-148 (산세 골격 확정값)
- **닫는 조건**: ① 20구역 캠퍼스가 시험 월드(`sanse_test_hwasan`)에 선다 ② 주변 산 건축
  (⑱운무교·⑲절벽 전망대·⑳부속 암자 — 곁봉 패드) ③ 절벽 결·조경 패스 ④ 사용자 빨간펜
  통과 → 본세계 재조성 회차 (기존 「본전 한 채」 대체)
- **검증**: `tools/TerraceForgeSelfTest.java` + 캠퍼스시험 조성 직후 검수 눈 3종(평탄·접지·보행)
  + 렌더/실측 빨간펜
- **닫힘**: —

한 줄 (사용자 확정 2026-08-02): 「이미지대로 화산파 건축 강화 + **주변 산들도 건축**이 되고
전체 디자인은 마스터플랜(20구역·3색 조닝)이 정본. 기존 문법이 해치면 수정.」
★사용자 확정 (2026-08-02 밤): **바닐라 우선 — 리소스팩은 빼고 건축하고, 리소스팩 연출은
이후 추가.** 레퍼런스 이미지의 질감(곡선 처마·기와 디테일)은 팩 몫이니 지금 좇지 않는다 —
판정 기준은 바닐라 블록으로 구도·배치·규모가 서는가. 판정: 산세 골격
(C 파이프라인·H-6/H-7 확정값)은 **지킨다** — 남→북 척추·동편 곁봉·서편 협곡이 마스터플랜과
같은 결. 없던 넷(척추 테라스·곁봉 패드·운무교·절벽 결)을 새로 짓는다 — 시안 v2 §2-b.
★사용자 결정 대기: build_radius 64→128 등록부 개정 · 폭포 수계 · 본세계 재조성 시점 (시안 §6).

진행 (2026-08-02 · 슬라이스 1 — TerraceForge): 석축 테라스 기계 신설 — **패드(중심·크기·
목표고) 목록 + 계단 링크 파라메트릭** (마스터플랜의 척추+좌우 로브 형태를 그대로 담는다).
기본 캠퍼스 = 척추 공공 단 6 (1산문→2외원→6종문→9본전→12장로회→13정상) + 낙차 상한이 끼운
계단참 2 + 로브 단 7 (3·4·5·7·8·14·17) — 패드 15 · 링크 14. 좌표·높이는 RangeSpec.hwasan
골격(창룡령 crest 보간·건물 품)에서 유도한 잠정값 【제안】. 옹벽·계단 몸체가 전 열을
실지형까지 내려 채워 **뜬 열이 구조적으로 없다** (B-146 처방) · 계획이 지형 어긋남 >10 이면
소리친다 (잠정 높이 빨간펜의 입력). barrel·light 금지 (B-195). `/혼천 캠퍼스시험 hwasan`
배선 (산세시험과 같은 가드 — sanse_test_ 접두·단일 실행 잠금·틱 분할·조성 직후 검수 3종
소리침). 검증: jar 빌드 0 오류 · TerraceForgeSelfTest **29/29** (조율자 재실행 확인 — 기본
캠퍼스 앉힘·계단 자재기·고의 위반 6종 거절·팔레트).

진행 (2026-08-02 · 슬라이스 1.5 — 실기동 첫 판독과 수리): 테스트 서버(run/mvt-test ·
25566)에서 실기동 — 라이브는 무접촉 (사용자 접속 중이라 재기동 안 함). 1차 판독: 조성 29단계
완주 · 평탄 0 · **접지 0 (B-146 처방 실전 통과)** · 그러나 ①보행 단차 6건 (계단이 아래로는
접지하며 위로는 지형 혹을 안 깎았다) ②지형 어긋남 경고 9건 (골격 유도 잠정 높이가 실지형보다
11~29 높음 — 기계가 p85 실측을 스스로 재줌). 수리: ①clearAbove — 디딤·측석 열마다 실지면을
재서 위 2칸 또는 머리 공간 8칸까지 걷는다 ②로브 7+산문·외원을 p85±2 로 하향 (옹벽이 석축
감 4~12 로 복귀) · 창-안 척추 앵커 4(종문 92·본전 116·장로회 128·정상 148)는 selftest 에
고정 · 외원↔종문 낙차 34 는 계단참 103 신설로 12+22 분할 (22 = 레퍼런스 1호 긴 천계단 문법) ·
링크 셋 상하 반전 (마스터플랜은 평면도 — 상하는 지형이 정한다). 검증: selftest **34/34** ·
jar 0 오류 · ★재판독 (월드 삭제 후 산세 재조성 → 캠퍼스시험): **지형 어긋남 0 · 검수 3종
위반 0** — 패드 16·계단 15 (척추 6+계단참 3+로브 7).

진행 (2026-08-02 · 슬라이스 2~2.7 — 구역 건물 배치기): `HwasanCampusBuilder` 신설 — 마스터플랜
전 구역에 건물이 앉았다 (산문·종문·측문 문루 3 [현판은 빈 판 — 작명은 사용자 몫] · 본전 월대
+2층 중루 [유일한 적벽 — 조닝 3색] · 연무장 2 모래 마당+목인+시렁 · 훈련장 · 강당·생활 3채·
창고 [chest 4 — barrel 금지] · 장로회 · 정상 사당+정자 · 정원 연못+매화 · 망루 3층 탑) +
신설 패드 3 (10 정원·11 망루·16 측문) — 패드 19·계단 18. SectBuilder 부품은 private+SiteSpec
결합이라 같은 치수·재료 문법을 패드 좌표계로 재구현. 실기동 빨간펜 4회(2→2.5→2.6→2.7)의
눈이 잡은 것: ①측문 스커트가 이웃 패드 가장자리를 파먹음 (접지 32) → 스커트/계단의 남의 패드
침범을 계획 단계에서 거절 ②**건물 처마가 계단 통로를 덮음** (보행 5 — 망루 3층 처마·강당 처마)
→ 「구조물∩계단 몸체」 순수 검증 신설 — 이 눈이 도입 즉시 사람이 놓친 4번째 겹침(목인 줄)을
잡았다 ③검수 보행 스캔이 문루 지붕을 발판으로 오독 → 「걷는 자의 눈」(이전 발판 +2 에서 하강
탐색)으로 교정 ④이사 패드 높이 3차 수렴 (강당 h68→48 · 링크 재배선 포함). ★검수 표본은
종류별 쿼터 4건 (한 종류의 독식 방지). 검증: selftest **40/40** · jar 0 오류 · ★최종 재판독
(신선 산세): **검수 깨끗 — 평탄·접지·보행·유출 0 · 창 밖 높이 경고 0**. 【제안】 빨간펜 대기:
본전 적색 재료(MANGROVE_LOG+RED_TERRACOTTA) · 문루 절제(조닝 계율상 산문·종문은 석재+흑목 —
레퍼런스는 붉다) · 정원·망루 Δ9~10 위계 근거.

진행 (2026-08-02 · 슬라이스 3~3.5 — 주변 산의 건축): 곁봉 패드 3 (19 절벽 전망대 = Es 어깨 ·
20 부속 암자 = Em 남 어깨 · 105 서교 착지 = Wm 남동 어깨 — 전부 마루 아래 「산이 먼저」) +
**운무교 3** (동일 79칸·동이 60칸·서 64칸 — 석교 교대+목교 상판+교각 돌기둥+등롱). 검수 문법
확장: **「의도된 허공」**(등록된 다리 상판 아래만 — 침묵 예외가 아니라 다리 기하로 판정) ·
다리 보행·교대·교각 접지 눈 ④ · 유출 눈이 다리 몸체(covers)를 안다 · **expectedLift 계약**
(다리 끝 패드만 — 19 = Δ31 「석탑 위 전각」 = 레퍼런스 9호의 정체를 계약화 · 105 = Δ17 ·
맨땅 패드가 쓰면 거절하는 은폐 방지 가드). ★서교는 마스터플랜 위치(연무장 상 곁)를 지형이
거부 (그 위도 서벽 너머 전부 저지) → 장로회 서면 재정박 【제안·빨간펜】. 실기동 빨간펜 2회
(유출 8 = 다리 난간 오탐 → 0 · 곁봉 높이 Δ67·Δ31 → 계약 안). 검증: selftest **50/50** ·
jar 0 오류 · ★최종 재판독: **검수 깨끗 — 평탄·접지·보행·유출 0 · 경고 0** (패드 22 ·
계단 18 · 다리 3 · 건물: 홀 9·문루 3·정자 5·목인 10·시렁 4·탑 1).

진행 (2026-08-02 · 슬라이스 4~4.5 — 조경·절벽 결 · ★4슬라이스 완주): 조성 5상(패드→계단→
다리→건물→조경). **소품 표(decors)가 정본** — 조성·발자국 상자·순수 검증이 한 표에서 갈라져
나온다 (눈이 개발 중 배치 실수와 측문 회전 담의 스킵 상자 누락을 각각 잡았다 — 회귀 시험
④-c 동반). 앉힌 것 (전부 【제안·빨간펜】): 매화 점정 11그루 (군락 아님 — 단 모서리·문 곁) ·
옹벽 덩굴 5%·지의 2% (뒤덮지 않는다 — 자연 70) · 바위턱 소나무 ≤13 (자리가 구조물이면
조용히 접는다 · ★SPRUCE_WOOD 로 건물 재료와 층위 분리) · 연무장 홍기(무지 — 문양·작명은
사용자 몫)+화로 · 창고 상자 더미·빨래줄·밭 5×4. **유출 눈 계약**: 조경 팔레트 ∩ 유출 스캔
표 = ∅ 을 눈이 잰다. 검증: selftest **53/53** · jar 0 오류 · ★최종 재판독: **검수 깨끗 —
평탄·접지·보행·유출 0 · 경고 0**.

진행 (2026-08-02~03 밤 · 슬라이스 5~5.6 — ★이미지 실측 재구성): 사용자 판정 「산도 없고
전체 형태가 아니다」 + 지시 **「이미지의 블록을 세서 그 크기 그대로」** → ①**실측표 신설**
(`docs/design/hwasan_block_measurements.md` — 레퍼런스 13장 블록 실측: 대계단 9 · 산문 전면
29·총고 17·**적주+백벽 (문루도 붉다 — 이미지가 정본, 빨간펜 닫힘)** · 본전 33~37 · 연무장
모래 30~40 · 산:건축 비 2~3배) ②**통단(帶) 재구성** — 패드 사슬 폐기 → 산 전폭 단 7대·17칸
(폭 94~118 · 같은 단의 칸이 잇닿아 옹벽·여장이 이어진 성곽 실루엣 · 칸 사이 회랑 담+자동
개구) · **폭 35 상한(H-3) 폐지** (사용자 지시 근거 · 안전핀 128) ③건물 실측 재척도 (산문
gateGrand 29 등) ④expectedLift 가 통단 계약으로 확장 (성곽 옹벽 양수 6 · 의도된 깎기 음수 2 ·
한도 ±40). 실기동 빨간펜 3회의 눈이 잡은 것 전부 **상자↔실물 불일치**였다 (정자 이사 누락 ·
장로회 북벽이 램프 착지선 침범 · 정원 내장 매화 상자 한 칸 어긋남) — 지형 문제는 pavePad
걷기가 이미 다 처리하고 있었다. 검증: selftest **55/55** · jar 0 오류 · ★최종 재판독: **검수
깨끗 — 전 검수 0 · 경고 0** (통단 20패드 · 계단 6 · 다리 3 · 열 17,142 — 이전 7,966 의 2.2배
발자국).

진행 (2026-08-03 · 슬라이스 6~6.5 — ★산군(山群) 전체 구성): 사용자 확정 「산이 없다 — 임의
금지, 캠퍼스 내외 산들이 전부 구성돼야 한다」. 실측 (7·8·9·12·13호): 배후봉 = 정상단
+60~100 · 근경 침봉 = 가장자리 8~30칸 · 세장비 1:4~8 · 원경 켜 ≥3 · 캠퍼스 = 산 중턱.
`SpireField` 신설 — **기존 지형 위 max 오버레이** (RangeSpec 개정 대신 — 표고·expectedLift
계약 보존이 근거): 배후봉 4 (Pm h228 = 정상단+80) + 침봉 3켜 (r130~620 · 셀 26 격자 · 밀도
62% · 방해석 띠 암질) · 협곡은 운해 자리로 보존 · 캠퍼스·다리 발자국 29 사각 무침범 가드.
`/혼천 산군시험` 신설 (순서: 산세→산군→캠퍼스) — 실기동 융기 1,191만 블록 · 154초.
★실기동이 잡은 병: **기준면 실측점(x600)이 침봉 필드(r620) 안** → 캠퍼스가 54칸 떠서 앉음
(경고 눈이 「Δ72 vs 계약 Δ18」로 표류를 정확히 소리침 — 계약 문법의 첫 실전). 수리: 프로브
정본 통일 (SpireField.PROBE_X=800 · 다섯 명령이 한 함수 — 도보길·식생시험도 같은 병이라 함께)
+ 이중 가드 (순수 무침범 검증 + 산세시험이 기준면 기록·후속 명령이 어긋나면 거부). 검증:
selftest **63/63** · jar 0 오류 · ★재판독: 기준면 y-61 복원 · **검수 깨끗 · 경고 0**.
★다음: 슬라이스 7 — 건물 형태 충실도 (사용자 확정 2026-08-03: 「의도적 크기 축소·단순화
금지, 올바르게」 — 겹처마·열주·중층을 이미지 실측대로).

진행 (2026-08-03 · 슬라이스 7~7.5 — 건물 형태 충실도): 형태 실측 (§3-b — 처마 내밈 2~3 ·
모서리 들림 1~2단 · 용마루+치미 · 적주 열주 간격 3 · 월대 3켜) → **공용 문법 3종**
(sweepRoof 팔작 근사 · eaveRing 겹처마 스커트 · colonnade 적주 열주+격자창) 으로 전 건물
재구축: 본전 = 월대→퇴칸 회랑→1층→스커트→2층→팔작+치미 (총고 21 실측 상한) · 산문 상층
난간 회랑 · 망루 층별 겹처마 · 홀 열주+3폭 문. 줄인 것 없음 (사용자 계율) — 자리 부족은
배치 이동·패드 확장으로. 실기동 빨간펜 1회: 평탄 95 = 처마가 자랐는데 검수 상자가 손으로
적혀 안 자람 (5.6 동병) → ★**마른 조성** — put 깔때기에 발자국 채집 분기, parts() 목록이
조성·상자의 **공동 정본** (상자를 손으로 적을 자리가 구조적으로 소멸) · 층위 구분 (평탄
스킵 = 전고 처마 포함 · 통로 검증 = 지상만). 검증: selftest **66/66** · jar 0 오류 ·
★재판독 (산세→산군→캠퍼스): **검수 깨끗 · 경고 0**. ★빌더 에이전트 1호 수명 마감(68만
토큰)·2호 교대. ★남은 것: 사람 눈 빨간펜 · 본세계 재조성.

진행 (2026-08-03 새벽 · 슬라이스 6.7 + 대전경 판정): 촬영 판정 「침봉=종유석·배후봉=원뿔」
→ 프로파일 교체 (몸통 ~78% 유지 돔+주상절리 홈+바위 턱 · 배후봉 병풍 능선 — 실측표 §4-b) ·
selftest 69/69 · 재조성 2,014만 블록 218초 · 검수 깨끗. ★테스트 서버 view-distance 6→16
(96칸 밖 청크를 안 보내던 진짜 벽 — 접속해도 산군이 안 보였을 원인). ★시야 16 첫 대전경
판정: 「산 속에 안긴 캠퍼스」 성립 · 침봉·배후봉 기암 성립 · 전각 겹처마·열주 성립 (s8 5컷).
★남은 판단거리: 침봉 몇 기가 남쪽 접근 축선 조망을 부분 가림 — 「접근 시야 회랑」(축선 남
폭 ~30) 을 산군 제외 사각에 넣을지 (사용자) · 사람 눈 빨간펜 · 본세계 재조성 회차.

진행 (2026-08-03 오후 · 슬라이스 8~8.7 — ★★척도 전면 교정): 사용자 교정 **「블록 수부터
틀렸다 — 대계단이 20블록이다. 범위부터 틀렸다」** → 오측 원인 규명 (초판 실측이 렌더 2×2
픽셀 뭉치를 1블록으로 셈 — 확대 크롭·여장 이빨이 자). **대계단 20 이 사용자 확정 기준자.**
재실측 2판: 산문 29→57 · 본전 33→73 (총고 44·월대 5) · 통단 폭 94~118→188~240 (전장 420 ·
표고 46→170) · 벽/열주/처마 10/6/4 · 1블록 소품(여장 이빨·디딤)은 불변. 캠퍼스 전 건물 ×2 +
★**산도 척도를 탄다** (8.5): lift 160→260 · honsanR 124→280 · 골격 배율 (C 실루엣 모양
유지) · ★창룡령 능선 표고를 캠퍼스 사슬 밑에 정렬 — 실기동 증거: 본전 Δ0·장로회 Δ1·정상
Δ-5. SpireField 켜 200/430/700/1000 · Pm 265 · 프로브 1180 자동 이동. 계약 2단계 보정
(8.6~8.7 — 추정 아닌 실기동 p85 그대로): 척추 계약 3 삭제 · 성곽 로브 7 갱신 · 곁봉 2 새
어깨 재배치 (서교 Δ0 적중·전망대 깎기 -14) · 측문 64 (동단 = 산 발치 밖 성곽 벼랑 — 이미지
12 단애 문법, 한도 ±64). 검증: selftest **71/71** · jar 0 · ★최종 실기동: 산세 ~250초 ·
산군 6,358만 블록 557초 (900초 안) · 캠퍼스 열 66,988 — **검수 깨끗 · 경고 0**. ★남은 것:
새 척도 대전경 촬영·사람 눈 재검 (재실측표 핵심 값 — 사용자 눈 대조) · 본세계 재조성.

진행 (2026-08-03 저녁 · 코덱스 독립 검토 — 사용자 지시 「직접 확인 + CODEX 질문 + 개선
방향」): 사용자 재판정 「여전히 사진과 전혀 다르고 작아 보인다」. 조율자 자체 판정 + codex
CLI(0.144.5, 이미지 3장 비교) 검토가 수렴 — ★★핵심 진단: **「산을 거대하게 만들고 그 안에
건물을 놓았지만, 레퍼런스는 계단·축대·성벽·전각이 산 자체가 되도록 만들었다.」** 치명 간극
5: ①거대 평면 석대가 산을 지움 — 불규칙 단구 3~5로 쪼개고 암반·축대·돌출 바위가 교차해야
②밀도 — 광장이 너무 넓고 중간 크기 사물(작은 문루·회랑·담·소나무·바위·등롱)이 없어 크기
단계가 끊김 ③위계 — 전 건물이 같은 층고라 중심이 없음, 산문은 1.5~2배 높이·지붕 2배 두께
필요 ④입면 깊이 — 기둥/벽-1/창호-2 세 겹 + 공포층 + 지붕 재료 혼합 ⑤★**인간 단위** —
슬라이스 8의 벽 10·기둥 간격 6·문 확대는 **역효과** (사람 기준이 사라져 오히려 작아 보임).
웅장함 = 인간 단위(문 2·층고 3~4·기둥 간격 5~7)가 수십 칸 누적 + 도착 시퀀스. 검토 전문:
docs/design/hwasan_codex_review_20260803.md. ★개선 우선순위 5 (슬라이스 9 후보): 석대 해체 ·
산문 재설계 · 접근 시퀀스 · 중간 크기 충전 · 디테일 키트 표준화 — ★사용자 승인 (「네
진행합시다」).

진행 (2026-08-03 저녁 · 슬라이스 9a — 산이 건축이 되게): ①**석대 해체** — 경사 석축(batter
— 3칸 하강당 1칸 밖으로, 산몸에 닿으면 중단 = 파묻힌 단구 자동) + 암반 늑재(가장자리 1/3이
자연 암반 결로 돌출 — 「성벽은 암반에서 솟는다」) ②**디테일 키트+인간 단위** — 기단·중방
띠·이중 창 3칸 리듬·문 6→4·공포 띠(bracketRing)·기와 결 혼합(타일 60/벽돌 22/균열 18 — 면
큐브만, 윤곽선은 또렷) ③**산문 재설계** — 3층 중층 총고 38 (1.5배) · 위계 눈 신설 (본전
41>산문 38 을 selftest 가 잰다) ④**중간 충전** — 행각(광장을 두르는 개방 열주 복도)·등롱
열주 6칸 주기. 검증: selftest **77/77** · jar 0 · 실기동 재판독 **검수 깨끗 · 경고 0**.
★9b 잔여: 접근 시퀀스(기슭 언덕 대계단·소문·비석) · 단구 표고 분할(±2~6 — 평탄 계약 재설계
동반) · 전각 캔틸레버 · 여장 들쭉날쭉 · 종문<산문 층고 차등.

진행 (2026-08-03 밤 · 슬라이스 9b — 석대 해체 완성·접근 시퀀스): ①단구 표고 분할 — 로브 칸
±4 (B1~B5 · 척추 앵커·다리 패드 불변 · 소계단 기계 신설 — 대계단 21과 소계단 5가 한 문법)
②**접근 시퀀스** — 조성 6상: 산문단 남단부터 남 176칸 보행면 표(지형 추종 — 기슭 언덕을
지우지 않고 넘는다) · 20폭 대계단+26칸 참+소문 문루+비석 쌍+소나무 · 검수 ⑤(접근로 보행·
접지 — 열 3,700) ③여장 세 결(담장/총안 성첩/겹단 — 스카이라인 요철) ④위계 사다리 완성:
본전 41>산문 38>종문 30 (selftest 값 검증). 캔틸레버는 접지 계약의 새 범주가 필요해 다음
회차 감 (§10-b). 실기동 빨간펜 1회: 보행 2 = 소계단 연장선이 행각 기둥 주기와 우연히 일치 →
행각을 칸 중앙 통로에서 갈라 구조적으로 비움 (마스터플랜 회랑 개구 문법 부합). 검증: selftest
**80/80** · jar 0 · ★최종 실기동: **검수 깨끗 · 경고 0** (패드 20 · 계단 15 · 다리 3 · 열
70,684). ★남은 것: 사람 눈 재검 (특히 접근 시퀀스 — 절벽 아래서 산문까지 걸어 오르기) ·
본세계 재조성 회차.

진행 (2026-08-03 밤 · 슬라이스 10~10.5 — ★산의 완성): 사용자 연속 재판정 「사진만 봐도
이상하다」→「산부터 완성해라, 그냥 기둥이다」. ①10: 산몸 ⓪층(ridged 릴리프 — 능선·안부
네트워크) + 소문 문루 재건 + 웜톤 암질 + 침봉 멱분포 — 판정 「부분 성립, 여전히 기둥 우세」
②★10.5 구도 반전: **슬라이스 6 오독 규명** — 세장비 1:4~8은 산이 아니라 능선 위 장식
침봉의 비율이었다 (8호 주인공 = 계단진 절벽 매시프 1:0.8~1.5 · 12호 = 대암벽 하나). 광봉
켜 신설(배후봉 병풍 능선 문법의 절차 생성판 · 밀도 85% · 능선으로 잇닿은 산맥) · 침봉
62%→4% 강등 + 평지 침봉 금지 가드. 식생은 사용자 지시로 **산몸 승인 후 보류**. 검증:
selftest **87/87** (구도 비율 눈 — 광봉 ≥70%·침봉 ≤6%) · 산군 4,667만 블록 468초 · 검수
깨끗 · ★촬영+조율자 판정: **「산+관문」 성립** (s14 — 접근 컷에서 산 덩어리가 주인).
★남은 다듬기: 원경 광봉 탁상 꼭대기 (마루 요철 진폭) · ★식생 (승인 대기) · 본세계 재조성.

진행 (2026-08-03 밤 · 슬라이스 11~11.5 — 식생): ★사용자 승인 (「산몸 맞다, 확실히 괜찮아졌다.
디테일은 추후 계속 개선」) → 식생 상 신설 (산군시험 2상): 완사면 소나무 군락(55% 쌍식+발치
이끼·풀)·어깨 턱 점식·절벽 이끼 캡+30% 턱 관목·침봉 소평두 마루 소나무 — 실기동 **소나무
8,693 · 관목 7,122 · 이끼 20,177**. 탁상 마루 요철 0~7 + 침봉 소평두 거칠기 ±2. 빨간펜 1회:
접근로가 소나무 몸통을 지면으로 오독 (접지 64) → ★**실지면 정의 통일** (VEGETATION 표 —
계획 groundY 통과·조성 fillDown 갈아치움·검수 동일 · 「이끼 블록은 지면」 경계 명시) + 시간
고삐 (원경 타일 통째 건너뜀·표집 1/37·산몸 판정 경량화 — 1087초→~650초 지침 안 복귀). 검증:
selftest **89/89** · 검수 깨끗 · 촬영 판정 「초록이 회색을 깼다」 성립 (s15). ★이후: **지속
개선 트랙** (사용자 확정 — 디테일 부족은 추후 계속 개선).

진행 (2026-08-03~04 · 슬라이스 12~12.6 — 지속 개선 1회차): ①식생 밀도 이중 표집(완사면
1/13 전부) ②광봉 급벽 세로 홈 상향+계단 턱 4~7 → **성립** ③행각 지붕 두 겹. 판정에서
★**밀도가 아니라 나무 한 그루의 형태**가 진범으로 드러남 (「막대+잎뭉치」) → 9호 재실측
(폭:높이 0.7~1.1 · 잎 2~3층 · 층 사이 줄기) → **층진 우산 재구축** (소/중/대 · 대수 2×2
몸통·수관 폭 9~13·가지 팔 2~6) → 판정 성립 → 다시 「층이 1블록 평판이라 파라솔」 →
**두 켜 층**(층간 3 · 줄기 노출은 아래 켜만) + **잎 3색 그늘 배분**(아래 켜·안쪽이 어두워져
입체) + 간격 3~4. 검증: selftest 89/89 · 산군 725~735초 · 검수 깨끗 (3회차 연속). ★남은
손잡이 (다음 개선 회차): 대수 층 간격 3→2 (층이 따로 떠 보임) · 절벽 판정 경사 완화 (중경사에도
나무) · 캔틸레버 · 현판 작명 · **본세계 재조성**.

진행 (2026-08-05 · ★★목표 고정과 설계도 전환): 사용자 지시로 **목표를 1호 사진 한 장에 고정**
(`docs/design/hwasan_target_diff.md` — 차이 목록 D-01~32, 「몇 번을 지웠다」로 보고) ·
**깊이 추정 방법론** (45° 부감의 격자 역산 → 좌표 → 설계 → 같은 각도 렌더 비교 → 확장) ·
이어서 **설계도 문법 전환** (「레퍼런스를 토대로 설계도를 그리고 그걸 바탕으로 건축」):
`config/blueprints/hwasan_gate.yml` 신설 — plan(평면)+columns(y 단면 압출)+roof(코드 호출).
stage.yml 층별 ASCII 는 60×22×20층에 1,300줄이 필요해 확장했다. **도면이 좌표의 정본**이고
코드가 읽는다. 소품(깃대·석등·비석)은 PROPS 스위치로 끔 · 나무는 GREEN 스위치로 끔 ·
★**테스트 서버 리소스팩 끔** (사용자 지시 — 바닐라 그대로 보려고).

지운 차이: 계단 폭 20→7(사용자 실측) · 계단 형태(반 칸 하강·9단+참 — 지형 추종 폐기) ·
계단 가림 · **척도 전체 되돌림**(슬라이스 8 의 ×2.2 가 오류였다) · 단청 띠 · 지붕 회색·처마
내밈 3 · 깃대·석등 · 축선 비움 · 하층을 「기둥+격자 문짝」으로(도면이 잡은 코드의 반대 구현) ·
회벽 색.

★★이 구간의 계율 다섯:
1. **색은 재고 고른다** — 눈짐작으로 세 번 틀렸다 (금빛 S26 vs 꿀집 S80 · 배너 V21 vs
   로열블루 V47 · 회벽).
2. **「튀지 않게」와 「안 보이게」는 다르다** — 형태가 사라지면 그 요소는 없는 것과 같다.
3. **가리는 정도는 높이가 아니라 두께가 정한다** (축선 계약).
4. ★**팩이 덮는 재료로 색을 판단하지 마라** — 우리가 「흰 벽」이라 여긴 것은 팩 텍스처였고
   바닐라는 살구색이었다 (차이 51). 단청·적주·등롱·지붕은 팩 무영향이라 판정 유효.
5. ★★**픽셀은 섞이지만 블록은 안 섞인다** — 석영:사암 2:1 의 채도 중간대가 1% (바둑판).
   거리가 먼 두 재료의 평균을 노리지 말고 **단일 재료**로 골라라. 눈도 「평균」이 아니라
   「가짓수」를 재야 한다.

검증: selftest 128→**169** · 검수 깨끗 (연속) · 도면대로 섰다 (칸 1320·블록 3774).
★남은 것: 문짝 살 굵기 · 회벽 채도 6 부족 · 산군의 시야 회랑(사선에서 침봉이 문루를 가림) ·
나머지 구역 도면화 · GREEN/PROPS 되살리기 · 차이 목록 D-23·24·27·29·30·31·32·17.

진행 (2026-08-04 · ★산문 문루 대결 + 슬라이스 14 이식): 사용자 지시 「제작 성능 차이 확인 +
건축 알고리즘 내재화를 위해 코덱스와 병렬 건축 후 교차 평가」. 규격: docs/design/
hwasan_gate_contest.md (동일 계약·범위 80×40×60·기존 클래스 참조 금지·중립 배선
`/혼천 대결시험`). 결과: **코덱스 44 · 클로드 43 (사실상 동률)** — 클로드=재료 층위·통행
경험·구조 정직성 / 코덱스=처마 실루엣·창호 밀도·색 온기. ★**두 평가가 같은 약점(중앙부
미돌출)을 지목** — 교차 검증된 진단. 내재화 3을 캠퍼스에 이식 (슬라이스 14): ①**귀솟음
(까치발)** — 처마 네 귀가 두 켜 솟고 안쪽 점층 (곡선 없이 「들린 처마」 · 사용자 확정
「곡선 처마 불허·레퍼런스도 블록 조합」과 정확히 부합) ②적목 상향 (적목:백벽 0.33→1.03 역전)
③산문 3단 요철+차양. 검증: selftest **102/102** · 검수 깨끗 · 촬영 판정 **귀솟음 성립**
(s22 — 본전 상·하층 지붕 네 귀가 뾰족하게, 안쪽으로 점층). ★부수 확정: **리소스팩 트랙
보류** (레퍼런스가 바닐라 조합이므로 격차는 팩이 아니라 블록 기술) · BetterModel 안건 종료.
★★사고와 계율: 촬영 클라 24개 누수 → 램 120GB+스왑 전량 소진 → 세션·테스트 서버 사망
(다른 세션이 회수). **촬영 뒤 클라 회수 필수** — HANDOFF 에 계율로 박음.

진행 (2026-08-04 밤 · 슬라이스 15~16 — 산의 질과 이음매): ★사용자 교정으로 표적이 바뀌었다
(「크기는 오히려 작다 — 산과 건물의 조화·산의 퀄리티가 차이」). ★★클로드·코덱스가 독립
판정에서 **둘 다 「건물이 크다」로 빗나갔다** — 정본: `hwasan_gap_review_20260804b.md`
(계율: 같은 종류의 눈은 같은 종류로 빗나간다 · 판정이 갈리면 사용자가 정본).
**슬라이스 15**: 레퍼런스 절벽이 **그 자체로 석전 계열로 쌓여 있다**는 실측 발견 → 늑재가
`SpireField.stone` 을 그대로 쓴다 (재료 통일) · 파임+바위턱 한 식(남은 부분이 곧 선반) ·
3칸 셀 얼룩 · 절벽 덩굴 · 광봉 하나를 뭉툭하게. ★조용한 회귀 포획: 석전 상면이 심을 수
없는 땅이 돼 산 표면이 민둥이던 것 (검수가 못 잡는 종류) → 「암벽 재료는 전부 심을 수
있다」 눈. 유출 오탐(늑재 사암) 수리 — 「암벽 ∩ 스캔 = ∅」 + 반대편도 잰다.
**슬라이스 16**: 판정 「재료는 같은데 **면의 기하**가 달라 눈이 가른다」 → `faceRelief` 를
축대 top 에 물려 **기하까지 통일** (축대 거칠기 0.087→0.918) · 늑재 4~14 불규칙·60% ·
배터 칸수 돌출/홈 · 모서리 부채. 검수 화해: 배터는 패드 밖이라 스캔 밖이고 스스로 접지하며
돌출은 기존 통로 가드를 물려받는다. ★**눈의 자를 고쳤다** — 이웃 열 높이차는 경사를
거칠기로 오인하므로 물매를 뺀 잔차로 (뮤테이션 판별력 확인) · 「매끈하면 실패」 옆에
「무너져도 실패」를 나란히. 검증: selftest **112/112** · 검수 깨끗 · 판정: 축대에 세로 기둥
결이 생겨 자연 절벽과 같은 종류로 읽힌다 (s24). ★남은 잔여 인공성: **면 전체의 기울기가
일정하다** (자연 절벽은 기울기가 들쭉날쭉) — 다음 손잡이는 결이 아니라 큰 기울기의 규칙성.

진행 (2026-08-04 · 슬라이스 13a~13c — 격차 대조 2차의 구현): 사용자 지시 「사진과 얼마나
비슷한지 확인하고 개선 방향 탐색」 → 코덱스 독립 검토 + 조율자 판정 수렴 (정본:
`docs/design/hwasan_gap_review_20260804.md`). 진단: **「풍부함은 디테일 총량이 아니라
암반→축대→정원→회랑→본전이 끊김 없이 이어지는 밀도 단계에서 나온다」**. 구현: ①옹벽
**다중 선반** (개수=높이÷12 · 20+ 2단·32+ 3단 · 배터가 선반 삼킨 칸 누적 반영 — 「위만 갈리고
아래 민짜」 해소) ②선반 화단·이끼·석등 (10~30급 중간 요소) ③구조별 팔레트 분화 (축대=응회암·
점적석 거칠게 · 포장=사암 베이지 — 산 웜톤과 한 계열) ④나무 재군집 (-30% · 자리 있는 군집)
⑤본전 처마 +3·중앙 3칸 높임·월대 3구역 ⑥**원인 있는 풍화** (젖은 셀 — 아래로 갈수록 6%+3%p/칸 ·
마른 면엔 없음 — 규칙적 산점의 폐지) ⑦**정면 3단 요철** (porch — 본전 중앙 21폭 5칸 돌출·끝
후퇴 · 강당·산문도) ⑧지붕 마루 선 (치미·내림마루·합각 — 큰 지붕 한정). 검증: selftest
**98/98** · 검수 깨끗 (4회차 연속) · 판정: 옹벽·풍화·요철 성립 · 지붕 마루선은 정면 각도에선
약함 (사선 컷 필요 — 다음 손잡이). ★촬영 함정 2건 기록: 관전 모드 풀림(낙사→기본 월드
리스폰 — Pos 확인 필요) · F1은 windowactivate 없이는 안 먹고 HUD 판정은 사이드바 획 밀도로. ★남은 것: 실루엣 스크린샷 판정 (성곽으로 읽히는가) · **산의 우위** (실측표 §4 —
산:건축 2~3배, 처방 3후보 【제안】 — 산세 확정값 개정은 사용자 결정) · 정원 내장 매화의
decor 통합 (정리 회차) · 산기슭 도보길↔산문 접속 · 본세계 재조성 (시안 §6).

### B-195 · ★★ 가구_3D 의 유령 벽 — 불투명 큐브를 가구 모델로 덮으면 벽이 뚫려 보인다 (2026-08-02 신설)
- **상태**: 열림
- **분류**: 미완
- **위치**: `config/resourcepack_design.yml` 가구_3D 절 · `resourcepack/assets/minecraft/blockstates/`
  (barrel·cauldron·lectern·loom·composter·shelf 등 14종) · `tools/respack/furniture.py`
- **단계**: P2
- **의존**: —
- **닫는 조건**: 가구 블록을 벽에 붙여 놓아도 벽이 안 뚫린다 — 방법 셋 중 결정:
  ① 가구를 비폐색(non-occluding) 기반 블록으로 이사 ② BetterModel 디스플레이 엔티티로 이사
  ③ 「가구는 벽에서 한 칸 뗀다」 배치 계율 (수용) — 사용자 결정
- **검증**: 인게임 — 통을 벽에 붙여 설치 후 뚫림 없음 (실측)
- **닫힘**: —

한 줄 (B-194 체험 실측 2026-08-02가 발견): 마크의 면 컬링은 모델이 아니라 **블록 종류**로
판정한다 — 통(불투명 큐브)을 큐브 아닌 한옥 가구 모델로 덮으면, 이웃 벽면이 컬링되어
**가구 둘레의 벽이 투명하게 뚫려 보인다**. 사용자가 통 설치로 재현 확정 (쉐이더 무관).
서장 무대의 궤짝은 chest 로 대체해 회피 — 근본 수리는 이 항목.

### B-194 · ★★★ 체험형 서장 (B안) — 걷고, 행동으로 결정한다 + 리소스팩 연출 강화 (사용자 확정 2026-07-31)
- **상태**: 열림
- **분류**: 결정
- **위치**: `config/seojang.yml` · `server-mvt/src/main/java/com/honcheon/mvt/Voyage.java` ·
  `resourcepack/` · 서장 월드(honcheon_seojang)
- **단계**: P1
- **의존**: B-179
- **닫는 조건**: ① 기억의 정거장이 「바라보는 조형」이 아니라 **내려서 걷는 장면**이 된다
  ② 패(글자 클릭) 대신 **행동**(웅크림·달림·시선·상호작용)이 선택을 정한다 — 몸짓 감지
  기계(TutorialGuide.gesture) 문법 재사용 ③ 리소스팩 연출 강화(커스텀 모델·글리프 UI·소리)로
  「다른 RPG 하듯이」 체감 ④ 파일럿 1개 무대가 사람 눈을 통과한다
- **검증**: `python3 tools/antechamber_audit.py` (확장) + 신규 캐릭터 실기동 (사람 눈)
- **닫힘**: —

★사용자 확정 (2026-07-31): 「발생할 수 있는 모든 서장을 실제 플레이로 해당 상황을 직접 걷도록
(메이플 캐릭터 튜토리얼처럼) 체험하고 행동으로 결정한다」 + 「마크는 연출에 한계가 있다 —
**리소스팩을 통한 연출적 강화**(실제 그냥 다른 RPG 게임 하듯이)를 만들고 싶다」.
★이 결정은 전 세션의 뜻이었는데 **장부에 안 박혀 침묵했었다** (닫는 조건의 「겪는 서장인가」
문구만 남아 있었다). B-179 의 「계열별 기억 조형(디스플레이)」 계획을 **대체**한다 —
조형은 보는 것이 아니라 걷는 것이 됐다.
★문장(발단별 첫머리·실·에필로그)은 죽지 않는다 — **내레이션**(타자기)으로 장면 위에 흐른다.
빨간펜 회차는 그대로 유효하다.
★규모: 갈래 4벌 × 장면 3 = 무대 12종 + 행동 감지 배선. **파일럿 = 기본(재난) 벌 1장
「그날 밤」** (재난 7발단 공용 밤 무대 + 발단별 소품 연출) — 파일럿이 사람 눈을 통과한 뒤 확장.

★★진행 (2026-07-31 — 건축 방법 전환 · 사용자 지적 「사진 찍고 좌표 찍는 방식으론 원하는
느낌이 안 나온다」): **3D 공간 인식 파이프라인** 신설 — ① 층별 도면 문법
(`config/stages/*.stage.yml` — 1문자=1블록, 도면이 좌표의 정본) ② 렌더 검수
(`tools/stage_render.py` — 평면도·아이소메트릭 PNG, AI 가 조성 **전에** 보고 고친다 ·
줄 길이·범례 밖 문자·spots 자리 검증 겸함) ③ 공간덤프 대조·체감 눈금은 후속.
「그날 밤」 무대가 이 문법으로 섰다 (30×22×7층 — 마당집: 방 서북·우진각 지붕·우물·담장 틈·
대문 문루·골목·장독). 첫 렌더 검수에서 이미 3건 고침 (평판 지붕→우진각 · 장독 추가 ·
모르는 재질 보라 경보). 좌표 함수 초안(MemoryStage v0)은 폐기.
남은 조각: 도면→블록 스탬프 로더(MVT) · 공간덤프 왕복 검수 · 파티클 점등·행동 감지·연출.

★진행 2차 (2026-07-31 — 로더·시점 렌더 · 사용자 확정): 로더(StageLoader + /혼천 서장무대)
라이브 ✓ · 분위기 렌더(밤 워시+광원) ✓ · **1인칭 시점 렌더**(tools/stage_pov.py — 간이
레이캐스트: 달빛·등잔·화광, 눈높이 1.62) ✓ — 4컷(방안 2·마당·담장 틈)을 **AI 가 직접 보고
3건 자가 수리** (광원 세기·카펫·화광 차폐). 격리 설계(무대 하나·사람은 서로에게 없다) 등재 ✓.

★진행 3차 (2026-07-31~08-02 — 생명 배선 + 빨간펜 8회): SeojangStagePlay 라이브 ✓
(넋등 자리 점등·행동 감지 3종·발단별 소리·격리 veil·개인 자정·폴백 60초 ·
/혼천 서장무대 체험). 실측 빨간펜 왕복 8회 — 굵은 것:
· 1~3호: 「뭘 해야 할지 모르겠어」 → 밤눈·인과 재배열(소리가 먼저 온다 → 생각 3줄이 자리를
  가리킨다 → 마지막 물음은 타이틀) · 한 문장씩 시간차 + 생각↔자리 동기 점등
· 4~5호: 공간덤프(/혼천 서장무대 덤프 — 콘솔 가능 + tools/stage_dump_diff.py) 신설 —
  블록 어긋남 0종 실측 · 진범은 보이지 않는 고아 식구(둘) → 고아 청소 + 깨어남 시선을 식구로
· 6~8호: ★유령 벽 추적 — 청크 재전송(무효) → light 블록(무관) → **진범 = 가구_3D 오버라이드**
  (barrel 등 불투명 큐브를 큐브 아닌 모델로 덮으면 이웃 벽면 컬링 — 사용자 통 설치로 재현 확정,
  쉐이더 무관). 무대 궤짝 barrel→chest 회피 → **사용자 확인 「상자로는 주변이 괜찮았어」(닫힘)**.
  전역 수리는 B-195 (방향 결정 대기).
★남은 것: 발단별 연출 7종 완성(지금 소리 근사 4종뿐) · 실배선(항해 정거장→무대→seojang_choice) ·
  2인 동시 실측 · 리소스팩 연출 사다리(ogg·글리프 제목 카드·모델 소품).
★사용자 확정 — **디자인 강화 사다리**: ① 완성되면 AI 가 직접 확인 (필요시 별도 리뷰
에이전트와 교차 판단 — ★Codex 협업은 2026-07-17 폐지라 리뷰 에이전트가 그 자리) →
② 설치 블록의 디자인 수정 → ③ 표현 블록이 부족하면 **리소스팩 텍스처 수정·신설** →
④ 색감·형태가 그래도 안 나오면 **이미지 생성을 입힌다** (생성 경로는 그때 정한다 —
이 세션엔 이미지 생성 도구가 없다: 절차적(PIL) 텍스처까지가 자력, 일러스트급은 별도 경로).

### B-193 · ★★ 입문식 이후가 없다 — 사문이 아무것도 열지 않는다 (2026-07-31 신설)
- **상태**: 열림
- **분류**: 미완
- **위치**: `server-bot/src/main/java/com/honcheon/bot/GameListener.java` · `config/sect_life.yml` ·
  `config/faction_entry_routes.yml` · `config/skills.yml`
- **단계**: P1
- **의존**: B-191
- **닫는 조건**: 입문식이 최소 하나를 **연다** — P0: ① 육합검 무상 전수(free_grant 첫 칸 +
  MARTIAL_SKILLS 등재) ② 시트에 사문 표시. P1: ③ 문파_전용_의뢰를 favor 축→사문 축으로 +
  first_duty 월 임무 ④ 탈문 명령(PONR 에 출구가 없는 것은 버그다 — 등록부가 두 출구를 약속했다).
  P2: ⑤ 공적 통화 ⑥ 봉록. 각각 눈이 잰다
- **검증**: `python3 tools/tutorial_audit.py` (확장) + 인게임
- **닫힘**: —

한 줄 (2026-07-31 화산 종단 조사): 사문 키를 읽는 곳이 겸적 가드·재실행 방지 **둘뿐 — 둘 다
거절문이다.** 제자가 되어도: 화산 검법 못 받음(전수 기계 = 곽진/태조장권 하드코딩 하나) ·
개화는 개방 심법(현천토납법)뿐 · 시트에 사문 미표시 · 게시판 무변화(favor 가 열었지 사문이
아니다) · sect_life(계급·공적·봉록·비급각·월임무·사형제·탈문·파문) 판독 코드 0건 ·
화산 실물(산 조성 ✓·NPC 16인 ✓)은 섰는데 출행이 오프스크린이라 갈 수 없다.
★이것이 「② 입문 후 무공 튜토리얼」(사용자 2026-07-31 — 첫 게임 인지 튜토리얼과 별개)의 본체다.

### B-191 · ★★ 입문식(사문 등록)이 미배선 — 튜토리얼의 결승선이 없다 (2026-07-31 신설)
- **상태**: 진행
- **분류**: 미완
- **위치**: `server-bot/src/main/java/com/honcheon/bot/GameListener.java` · `config/faction_entry_routes.yml`
- **단계**: P1
- **의존**: —
- **닫는 조건**: 화산 심사 합격 후 **입문식 게이트**가 실제로 열리고 사문 등록(겸적 불가)이
  시트·원장에 남는다 + 눈이 잰다
- **검증**: `python3 tools/lint_config.py` + 신규/기존 눈에 입문식 검사 + 인게임 완주
- **닫힘**: —

한 줄: 심사 합격 = 태그+favor 뿐 — 「한 세력에 들어간다」가 기계적으로 일어나지 않는다.
등록부는 게이트(`입문식`·"사문 등록 — 이후 겸적 불가")를 이미 정의했는데 여는 코드가 없다.
★2026-07-31 튜토리얼 전수 조사에서 발견 — **장부 밖의 빚이었다** (침묵 6일+).

★진행 (2026-07-31 배선 완료): 합격 패에 [입문식을 올린다/아직 올리지 않는다] — 예를 미루면
패는 남는다. 올리면 시트 `사문=hwasan`(id_policy) + 원장 '입문식' + 겸적 가드(사문 있는 몸은
심사·잡역 거부). 눈 = `python3 tools/tutorial_audit.py` 결승선 검사 신설 (+selftest 3항).
닫는 조건에서 남은 것: **인게임 1회 실측** (사람 눈 관문에 묶임).

### B-192 · 심리 테스트 undecided.resolve 미배선 — 동점 아이의 성향이 영영 안 굳는다 (2026-07-31 신설)
- **상태**: 열림
- **분류**: 미완
- **위치**: `config/disposition_test.yml:60`
- **단계**: P2
- **의존**: —
- **닫는 조건**: 「행동으로 굳는다」가 배선된다 (3방+ 동점 8.25% — 12명 중 1명꼴)
- **검증**: `python3 tools/disposition_audit.py`
- **닫힘**: —

한 줄: 등록부 스스로 「★미배선 — 다음 바퀴 청구서」라 적었는데 장부에 번호가 없었다.

### B-190 · ★★★ 세계관 → 기계 — B-189 에서 정한 것 중 **아무것도 안 굴리는 것들** (2026-07-30 신설)
- **상태**: 닫힘
- **분류**: 미완
- **위치**: `config/world_clock.yml`(acts·endings) · `config/factions.yml` ·
  `config/faction_politics.yml` · `config/rumor.yml` · 봇 `WorldClockEngine`
- **단계**: P1
- **의존**: B-189
- **닫는 조건**: 아래 다섯이 **각각 배선되고 눈이 그것을 잰다**
  ① **플레이어 천마 루트** — 하오문 보고가 **어떤 기계를 타는가**(소문망? 명성? 개인 노선?) ·
     세 자리 진입(정파=입문 / 사파=값 / 마교=저쪽이 먼저 본다)이 **같은 문에서 갈리는** 배선
  ② **붙든 계보 수 → 제5막 침공 규모**의 산술 (엔딩 A/B 축에 직결)
  ③ **도려냄이 맹의 힘을 얼마나 깎는가** (제3막 딜레마 — *도려내면 맹이 약해진다*의 수치)
  ④ **제3막 집계** — 개인 노선(덮다/도려내다/이용하다) 분포의 **단위·시점·임계값**,
     그리고 그 값의 **저장 자리**
  ⑤ **세 자리의 NPC 대체 판정** — 사람이 못 채웠다는 것을 세계가 **어디서 읽는가**
- **검증**: `python3 tools/world_clock_audit.py` · `python3 tools/world_clock_audit_selftest.py` ·
  `python3 tools/lint_config.py`
  ★그리고 새 눈이 필요할 수 있다 (**「글로만 있고 기계가 없는 것」을 세는 눈** —
   있으면 이 항목이 **자기 자신을 잰다**)
- **닫힘**: 2026-07-31 — 다섯 전부 배선 + 눈. 증거: `python3 tools/world_clock_audit.py` 위반 0
  (⑥ 해소 그릇 + ④-c 천마 루트 검사 신설 — 그 눈이 곧 「글로만 있고 기계가 없는 것」을 세는
  눈이다) · `python3 tools/world_clock_audit_selftest.py` **30/30** ·
  `python3 tools/bridge_audit.py` 배선 ✅ (raid_resolved·trial_passed 발신↔수신 짝) ·
  `python3 tools/lint_config.py` 0 · 커밋 aa41c03·ef6d0a9·c9ea709·7a4c20c.
  ★남는 관측 (닫힘을 뒤집지 않는 후속): 인게임 실측은 세계막이 제2막+에 닿아야 자연 발화
  (지금 prologue) — 막 진행 후 사람 눈 · 레이드/설립전 **무대** 조성은 별도 청구서 ·
  【제안】 수치 빨간펜 대기

★★한 줄: **B-189 는 세계를 정했고, B-190 은 그것을 굴린다.**
  사흘 동안 선 것 대부분이 아직 **글**이다. 정본(`world_bible.md`)에 아름답게 적혀 있는데
  **세계일이 지나가도 아무 일이 안 일어난다.** 그 간극이 이 항목이다.

★함정: **정본이 촘촘할수록 「이미 됐다」고 착각하기 쉽다.**
  world_clock 감사가 위반 0 인 것은 **적힌 것끼리 맞는다**는 뜻이지
  **적힌 것이 굴러간다**는 뜻이 아니다.

★★진행 기록 (2026-07-31 — 등뼈 배선 회차 · 사용자 확정 3건 반영):
- **사용자 확정**: ⑴ 제5막 승패 = **인게임 레이드** (대장전 — 산술은 규모, 승패는 실전)
  ⑵ 천마 길 = 자격 심사 없음 · **값이 조건** (모든 레벨·기술 버림 + 신교의 시험 + 칭호 3단:
  그릇이 될 자 → 그릇이 된 자 → 신을 품은 자) ⑶ 노선 = **고르지 않는다, 드러난다** (퀘스트
  수행 형태가 자동으로 적는다)
- **해소 그릇 신설** (다섯의 공통 병 = 「해소 결과가 태어나는 자리가 없다」):
  world_meta `막해소:<막>.<박>` + 원장 `막해소` · 박에 `resolution` 명세 3종
  (자리_판독·노선_집계·다리_보고) — `WorldClockEngine.resolveBeat/writeResolution`
- **② 배선 ✓**: `계보:붙듦수`(0~5) → `endings.산술` 침공_규모 vs 맹_전력 → 격퇴|패배.
  레이드 통로 = world_bridge `raid_resolved`(등재+Bridge case+MVT `/혼천 레이드해소` 발신) —
  ★무대(보스 스폰·페이즈)는 별도 청구서. ★붙듦수를 **움직이는** 행위 훅은 ① 몫
- **③ 배선 ✓**: `산술.맹_전력 { 기본 4, 썩은_머릿수 1, 잃는_조건: {연대_폭: 넓다} }` 【제안】 —
  도려내면 머릿수를 잃고 연대가 넓어진다 (힘이냐 명분이냐가 두 변수의 교환)
- **④ 배선 ✓**: 단위=캐릭터 · 저장=원장 `개인_노선`(원장이 곧 상태) · 시점=bonjin_ui_gil 발화 ·
  임계값=도려내다 ≥ 0.5 【제안】 · 참여 0 → 좁다. 손 = `Quests.ACT3_NOSEON`(자릿세 셋 【제안】
  ★지어냄 — 빨간펜) + GameListener 의뢰 완수 훅 + `Db.eventsByType`
- **⑤ 배선 ✓**: 저장 = `자리:맹주|패주|천마`(player:<id>|npc:<키>) · 판정 = 맹주=maengju_seonchul
  발화 시 / 천마=chimgong_gaesi 발화 시 (비면 npc_default — npc_fallback 이 처음 읽혔다).
  ★자리를 **채우는** 기계(선출·도전·천마 루트)는 별도 — 판정과 저장이 이 항목의 몫이었다
- **엔딩 판정 배선 ✓**: `endings.wiring_status: 미배선 → 배선` — decided_at(bongin) 발화 시
  inputs(state_from)를 읽어 세계엔딩 기록 + 막엔딩 원장 + 엔딩 do 발화. 빈 그릇은 폴백이 낳는다
- **눈 ✓**: world_clock_audit **⑥ 해소 그릇** 신설 (유형·필수 칸 · state_from→실존 해소 박 ·
  bridge_kind 등재↔case · 배선 선언 정직 · 노선 손 실재 · 산술 실재) + 뮤테이션 8 —
  **selftest 28/28** · bridge_audit ✅(발신·수신 짝) · lint 0 · game_audit 0 · 봇·MVT 컴파일 ✓
- **남은 것**: ① 천마 루트 본체 (기명각 = 레벨·기술 초기화 손 + 시험 무대 + 칭호 3단 저장·표시 +
  하오문 접촉 훅) — 다음 조각. 그리고 【제안】 수치 빨간펜 · 자리를 채우는 기계 · 레이드 무대

★★진행 기록 2차 (2026-07-31 — 막별 목적·설립전 회차 · 사용자 확정 5건):
- **확정**: ⑴ 막마다 개인 목적 + 경지 창 (1막 삼류~이류 · 2막 일류~절정 · 3막 절정~초절정 ·
  5막 화경 — 4·6막은 【제안 보간】) ⑵ **경지는 막이 제한하지 않는다** (창은 권장 — 행동의 결과는
  세계 흐름보다 빨라도 보상) ⑶ **세계는 플레이어 규격으로 줄어들지 않는다** (NPC 가 더 강할 수
  있다 — 천마 대치전까지도) ⑷ **창설 이유 세 겹** — 힘·기억·★명분(부패 척결·질서) ⑸ ★★**순서
  교정: 자릿세는 맹보다 먼저 있었다** — 부패자들이 뜯고 있었고, 그걸 통제하려 맹을 세웠고,
  설립전에서 못 거른 자들이 섞여 들어와 제3막에 **같은 짓을 맹의 이름으로** 한다 (재발).
  「섞여 들어왔다」의 출처가 이것으로 생겼다. **명분만 배신당한다.**
- **배선**: world_clock.yml 전 막 `개인_목적`+`경지_창` · clock.growth_law/power_law ·
  제2막 **seollipjeon 박 신설**(world_event 설립전 — 무대는 별도 청구서) · 제3막 재발 명시 ·
  factions.yml murimmaeng.창설_이유+★명분만_배신당한다 · world_bible §5 표 2행+목적·창 표
- **인과 정리** (사용자 문답): 마교 침공의 방아쇠는 자릿세가 아니라 **시간**(초대 시한부 —
  겪은 세대가 저물고 전해 들은 세대가 셈을 시작한다). 부패의 역할은 연료(버려진 자들이
  마교로 흘러든다)·침공의 이름(청구)·맞는 쪽의 조건 — **원인이 아니다**
- 검증: world_clock_audit 0 · selftest 28/28 · lint 0 (경고 = 기존 싸우는_이유 39곳)
- **미결**: 설립전의 결과가 제3막 썩은 뿌리의 **크기**를 정하는가 (걸러낸 만큼 덜 썩는가 —
  산술 배선 후보) · 벽(절정+) 깨달음 사건의 공급 목록

★★진행 기록 3차 (2026-07-31 — ① 천마 루트 1단: 접촉·기명각·칭호):
- **배선**: 미결이었던 「보고의 기계」= **소문망** 확정 (factions.yml 보고의_기계 + 루트 기계 절
  `cheonma.플레이어_루트_기계` — 보고·접촉·기명각·시험·칭호_3단·배선_상태가 정본).
  · **접촉** = 자정 정산 `cheonmaContact` — 제3막 이후(actReached) · haomun_net 에 주체로 살아
    있는 소문(`Db.hasSubjectRumor` 신설 — 자격 심사가 아니라 존재 확인) · 미접촉 → **DM 하나**
    (공개 금지 — magyo_encroachment.display_prohibition 승계 · "마교"라는 단어 없음 · 화법
    「자네, 밥은 먹었나」). 거절=다시 안 옴 · 중단=표식 걷음(다시 올 수 있다)
  · **기명각** = 2단 확인(값의 재고지 — 전부 버린다·되돌릴 수 없다) 뒤 집행: `GrowthV3.wipe`
    (원장·화후·능력치 셋 함께 — ★backfill 부활 함정 때문에 하나라도 남기면 안 된다) + 기술·
    심법·단전·마크·**가전_무공**(성은 집이 있는 자의 것) 제거 + **경지 범인** + 원장 '기명각'
  · **칭호 1단** = 시트 '칭호'="그릇이 될 자" → mvtSheet `title` → Sheet 레코드/파서 →
    PlayerLedger.title → 명패 접두가 **경지보다 칭호 우선** (기명각 뒤 범인이라 칭호가 자리를 잇는다)
- 검증: 봇·MVT 컴파일 ✓ · world_clock/bridge/lint/game 감사 전부 0 · selftest 28/28
- **남은 것 (① 마감 조건)**: 신교의 **시험 무대**(체력 1만 돌 — Dojang.dummy 뼈대) + 칭호 2·3단
  승급(그릇이_된_자·신을_품은_자) + 3단에서 `자리:천마=player:<id>` 기입 + **①의 눈**
  (배선_상태 선언 ↔ 코드 실재 대조 + 뮤테이션). ★그리고 잠든 어긋남 의심 1건:
  `magyo_encroachment`(잠식 문법)가 B-189 마교 대개편(문턱 없음·저잣거리의 손)과 결이 맞는지
  통독 필요 — /세계 후보

### B-188 · ★★ 전 세력 무공 증축 프로그램 — 무기 파악 → 부족분 → 하나씩 (사용자 확정 2026-07-26)
- **상태**: 진행
- **분류**: 결정
- **단계**: P2
- **위치**: `config/skills.yml` (+ simbeop·ultimate_arts)
- **의존**: —
- **닫는 조건**: ① 순서상 빈 칸(사다리가 공중에서 시작하거나 끊기는 곳)이 채워지거나 「의도된
  모양」으로 명문화된다 ② tier 미기재 구식 등재 정리 ③ ~~사다리 연결 눈(선행 사슬 chain-walk —
  requires_skill 로 첫 칸까지 닿는가) 신설~~ **✓ 2026-07-26** ④ 무공 0 세력의 입장권 공백 해소
- **검증**: `python3 tools/combat_audit.py --lint-only` (사다리 눈 포함) +
  `python3 tools/combat_audit.py --selftest` (눈을 시험하는 눈 16/16 — ⑩~⑯ 이 사다리 몫)
- **닫힘**: —

★★프로그램 확정 (2026-07-26): 「**모든 세력의 스킬을 다 증축** — 세력의 사용 무기를 파악하고,
부족분을 찾고, 하나씩 채운다」. 방법 = 팽가·제갈에서 정착한 문법: 사용자 자료(정격 이름) 우선,
없으면 클리셰 【제안】+★지어냄 표기 → 빨간펜. 전 등록부 한 벌(skills·mechanics·motion·
simbeop·ultimate + 무기 계열 신설 시 weapon_power·styles·swings·basic_strike)이 한 회차다.

★세력×무기×사다리 전수 지도 (2026-07-26 2차 — 증축 목표):
| 세력 | 무기 | 상태 | 부족분 (채울 것) |
|---|---|---|---|
| ★모용 | **?** | 무공 0·심법 2 | **전부** — 자료 대기 (무기 정체성부터) |
| 소림 | 권·장(·곤?) | 권 하중·장 상만 | 장법 하·중급 · **곤법(봉) 전무** — 소림곤 후보 |
| 개방 | 봉·박투 | 봉 중·상만 | **봉법 하급** · 박투 tier 정리 |
| 당가 | 암기·독 | 독공 중만 | **독공 하·상급** · 암기 기초? |
| 무당 | 검·권 | 검 3단 ✓ | 권법 사다리(태극권 1종뿐) · 장법(면장 후보) |
| 남궁 | 검 | 검 3단 ✓ | 기초 검식(입장권 내림) · 경공/보법 0 |
| 화산 | 검 | 검 3단 ✓ | tier 미기재 4종 정리 (육합검·매화참·낙수·점점) |
| 팽가 | 도·권장퇴 | ✓ (12종) | 선법 없음(의도) — 완료급 |
| 제갈 | 검·부채·진 | ✓ (7종) | 선법 중·상급 증축 |
| 곤륜~해남 5파 | 검 | 검 3단 ✓ | 심법 1뿐(기초 심법?) · 경공 소수 — 소소 증축 |
| 녹림·하오문·혈교 | 도/단검/장 | tier·심법 없음 | 거취 문답 (사파의 결이 의도인가) |

★★기준 추가 (사용자 확정 2026-07-26): **무기별 빌드가 최소 2개 이상** — 어떤 병기를 골라도
갈 길이 둘은 있어야 한다. 현황 (타격 계열 무공의 공급원 수 · 경공/진/은신 제외):
| 판정 | 계열 | 공급원 | 채울 후보 【제안】 |
|---|---|---|---|
| ✓ | 검 10 · 맨손/권갑 7 · 도 3 · 단검 2 | — | — |
| ★1 | 암기 (당가) | +1 | 살막 (암살 세력 — 단검은 이미 있다) |
| ★1 | 봉 (개방) | +1 | **소림 곤법** (순서 ②에서 자동 해소) |
| ★1 | 활 (민간 1종) | +1 | 군문(관군 궁술) 또는 사냥꾼 계열 증축 |
| ★1 | 창 (표국 1종) | +1 | 군문 (양가창 결) |
| ★1 | 부채 (제갈) | +1 | 하오문(기녀·문사 위장) 또는 별도 결정 |
| ★0 | **겸** | +2 | 민간(농군의 낫) + 녹림 |
| ★0 | **구** | +2 | **장강수로채** (물의 갈고리 — 세력 등록 실존) + 하오문 |
| ★0 | **중병기** | +2 | 녹림(산채) + 군문(진문 대병) |
| ★0 | **부** | +2 | 녹림(산채 도끼) + 민간(나무꾼) |

진행 (2026-07-26 · ★① 모용세가 완료 — 사용자 제공 자료 · 프로그램 첫 걸음):
- **수공·지법의 명가**로 등재 — 15종 + 심법 3(청명기공 기초·구천강 상승·**건곤무적공 가주
  일인전승** — 기존 「미설계·입문 불가」 딱지가 설계로 벗겨짐 · 영의팔극공은 원천 밖이라 그대로)
  + 오의 2 (**두전성이** — 되돌림 반격 오의·가주 일인전승 · 건곤백절검해 — 원·다단 5).
- 계보: 수공 일엽수(기초·입장권)→죽엽수(하)→청죽수(상 — 강호 일절·중급 건너뜀은 남궁제황검
  문법) · 지법(신설 계열) 유성지(중)→추혼지(상 — 지풍 원거리) · 벽파권(하)→건곤무적장(상) ·
  열화각(퇴 중) · 검법 추검(하 — 찌르기 위주)→천운삼검(중)→건곤파섬검(상) · 도법 참풍도(하)→
  응익도법(중) · **응익분풍선(선 하 — 부채 2번째 공급원: 기준 ✓)** · 일엽락(신법 상 — 강호 일절).
- 미등재 후보 주석 기록 (지어내지 않음): 검법 10종(염혈검·쇄천검 등)·적룡조.
- 눈: lint·combat 린트·motion **첫 통과 전부 0** · 재배포 ✓ (MVT Done 33.0s · ERROR 0 · 봇 ✓).
- 기준 변동: 부채 ★1→**✓2** (제갈+모용) · 퇴법 공급 2 (팽가+모용) · 도 4곳 · 맨손 8곳.

★★전 세력 백지 지도 (2026-07-26 4회차 뒤 전수 재조사 · 사용자 지시 「전체적인 틀을 다 잡고
하나씩 채워나갈 예정」 — **이 표가 그 틀이다**):

**① 들어갈 수는 있는데 배울 것이 없다 — 10곳** (`faction_entry_routes.yml` 에 입문 경로가
있는데 무공 0. ★사용자 확정 순서: **여기부터 채운다** — 문이 열려 있는데 빈 방이 가장 아프다)

| 세력 | factions.yml 이 말하는 결 | 무공/심법/오의 | 이 세력이 여는 것 |
|---|---|---|---|
| 장강수로채 | 수로를 장악한 흑도 | 0 / 0 / 0 | ★**구(鉤) ★0** 해소 후보 (B-188 기준표가 이미 지목) |
| 오독교 | 남만 묘족 — 독을 **만들지 않고 기른다**(고독) | 0 / 0 / 0 | 당가와 대비축 (제조 vs 사육) · 독공 2번째 공급원 |
| 설역 밀교 | 토번 라마승 — **「호흡법이 극단으로 발달」** | 0 / 0 / 0 | ★설명이 심법 세력이라 말하는데 **심법 0** (가장 큰 모순) |
| 배화신교 | 서역 이교 — 불을 섬긴다 · 환술 | 0 / 0 / 0 | 환술 계열 자체가 세계에 없다 |
| 서역 상맹 | 비단길 상단 연합 | 0 / 0 / 0 | 상인 무력 (호위 계열) |
| 북막 한국 | 초원 유목 — **무림이 아니라 나라** | 0 / 0 / 0 | 기마·활 (활 ★1 해소 후보) |
| 동영 | 바다 건너 섬 — 지도에서 가장 멀고 실제로 가장 가깝다 | 0 / 0 / 0 | 도(刀)의 이질적 결 |
| 상단 | 상업 | 0 / 0 / 0 | 표국과 같은 과 (호위) |
| 독문 | 당가 방계 — **칠살음독경의 출처** | **0** / 1 / 0 | 심법만 있고 손이 없다 (당가 독공의 바깥짝) |
| 마교 | 금기 세력 — 세계 시간이 흐른 뒤 드러난다 | **0** / 1 / 0 | 심법만 있고 손이 없다 |

**② 입문 가능 · 배울 것 1~2종** (백지는 아니나 사다리라 부를 수 없다):
살막 1 · 혈교 1 · 표국 1 · 관군 1 · 하오문 2 (전부 심법 0 · 오의 0 — 녹림 3·민간 3 도 같은 과)

**③ 입문 경로 없이 백지**: **아미파**(★구파일방인데 백지 — factions.yml 이 「추후 확장 콘텐츠」라
못 박아 둔 유일한 명문) · 현령/관청 · 정보상

**★컨테이너 id 는 대상이 아니다** (무공을 가질 주체가 아님 — 눈도 이들을 세지 않아야 한다):
정파 계열 · 구파일방 · 오대세가 · 사파/흑도 계열 · 새외 계열 · 경제/민간 계열 · 질서/행정 계열 ·
사교/금기 계열 · 정파 협객 · **불가**(factions.yml 이 「문파가 아니라 계보다」라고 적어 뒀다)

**채운 곳 대비** (4회차 뒤): 소림 17 · 모용 15 · 당가 15 · 개방 13 · 팽가 12 ·
화산 9 · 제갈 7 · 무당 6 · 남궁 5 · 곤륜 4 · 청성·종남·점창·해남 각 3

★★이 표는 **눈이 센다** — `combat_audit.py lint_faction_coverage` (닫는 조건 ④ 의 눈).
손으로 센 지도는 낡는다. 이 눈은 factions.yml 1급 id 를 걷고(컨테이너 10종 제외 —
「불가는 문파가 아니라 계보다」를 등록부가 말해 준다) · faction_entry_routes 가 가리키는
세력과 대조해 **「문이 열렸는데 무공 0」** 을 센다. 뮤테이션 5건(⑲~㉓) · `--selftest` **23/23**.
- 눈이 손 조사를 두 곳에서 고쳤다: ① 입문 경로는 세력을 **한글 이름으로도** 가리킨다
  (`magyo_encroachment` 의 `faction: 마교`) — id 만 보던 첫 판은 **마교를 놓쳐 「9곳」**이라 했다
  (묘비 프로브 ⑳) ② 컨테이너 「사파/흑도 계열」에 **음풍검(eumpung_geom)** 이 붙어 있다 —
  「어느 문파도 아닌 무공」이면 orthodox_heroes 선례처럼 **1급 id 를 세워야** 한다 (문답 대기).
- 곁가지 발견: **개방에는 입문 경로가 없다** (구파일방인데 — 프로브 ⑲ 가 알려 줬다).
  청성·종남·점창·아미도 같다. faction_entry_routes 쪽 일이라 여기서는 기록만 한다.

★★사용자 확정 3건 (2026-07-26) — 세계의 뼈대가 정해졌다:
1. **빙공(極陰) 축 도입 안 함** — 사이트의 태양궁↔북해빙궁 대립축은 쓰지 않는다.
   우리 세계의 화공은 배화신교뿐이고, 그 짝은 만들지 않는다.
2. ★★**새외무림 입문 폐쇄 (고정)** — 6곳(설역밀교·배화신교·북막·동영·서역상맹·오독교)의
   플레이어 입문을 닫았다. **설계는 지우지 않았다**: 세 문(거리 57~128일 · 통행증 = 관의 행정 ·
   심법 opposed = 몸의 문)과 스펙트럼 6(고용/유학/사사/개종/입고/귀부)은 **세계가 새외를 어떻게
   보는지**를 적은 문장이고 입문이 닫혀도 참이다. 기계 표시 = `player_entry: false` +
   `deferred_to: NPC_기술` + `reopen_condition`(사용자 결정만). **문을 지운 게 아니라 잠갔다.**
   ★무공의 거취: 「적이나 NPC가 사용할 수도 있으니 **NPC 기술**로 미루어 둔다」(사용자) —
   그래서 새외 6곳의 무공 0 은 **구멍이 아니라 유보**다. 채움 순서에서 빠지고 npc_combat 축으로 간다.
3. **무림맹부터 진행** → 아래 신설.

★★무림맹(murimmaeng) 신설 — 「정파 계열」과 「무림맹」은 같은 것이 아니다:
- faction_politics 제1원칙이 「정파는 세력이 아니라 연합의 이름이다」라고 적어 뒀다. 맞다.
  그런데 **누가 그 이름으로 결정을 내리는가**가 비어 있었다:
  · orthodox = **분류**다 (컨테이너 — 결정하지 않는다) · 구파일방/오대세가 = **명단**이다
  · **무림맹 = 기구(機構)다** — 맹주·부맹주 2~4·**장로원(실질 권력)**, 그리고 **토벌령을 낸다**
- ★이미 세계가 이것을 필요로 했다: `authority_mandate`(관의 명령)의 **강호 쪽 짝이 없었다** —
  혈교 토벌령·무림공적 지정을 지금까지 아무도 내리지 않았다. 그 자리가 무림맹이다.
- 낼 수 있는 것: 무림공적_지정 · 토벌령 · 분쟁_중재 · **용봉지회**(무술 대회).
- ★자료가 짚은 결을 그대로 남겼다: 「부패·무능으로 그려지거나, 맹주가 조력자이거나 최종 보스이기도」
  — **무림맹은 선하다고 전제하지 않는다.** 플레이어에게 적이 될 수 있는 정파 기구다.
- 미결 문답 4건 (지어내지 않고 남겼다): 맹주 실존/공석 · 알파 시점에 이미 서 있는가 ·
  입문 가능한가(문은 아직 안 만들었다) · 무력 조직이 제 무공을 갖는가.

★★눈이 세 가지를 새로 배웠다 (`lint_faction_coverage` — 이 회차의 절반은 눈이었다):
- **「NPC 전용(입문 폐쇄)」 칸 신설** — 유보를 백지로 착각하지 않는다. `player_entry:false` 를 읽는다.
- **「무공 0 이 의도」 칸 신설** — `no_arts_by_design`. ★컨테이너와 다르다:
  컨테이너는 **주체가 아니어서** 무공이 없고, 무림맹은 **주체인데** 없다 (기구이므로).
  이 표시가 없으면 나중에 무림맹에 문이 열리는 순간 눈이 거짓 경고를 내고, 그때는 아무도
  왜 그런지 기억하지 못한다.
- ★★**판정을 정밀화했다 — 세 판을 거쳤고 세 판 다 내가 틀렸다**:
  ① 폐쇄를 하위로 전파하지 않으니 `gates[].condition.favor.faction` 이 새외 5곳을 다시
     열린 문으로 올렸다 ② 전파를 npc_only 까지 밀자 새외 절의 **대조 주석**(「★화산 5일 ·
     당가 26일」)과 trigger 참조가 언급한 **소림·무당·모용·곤륜·해남**이 「NPC 전용」으로 잡혔다
  ③ 결국 **느슨한 언급 전수 훑기 자체가 틀렸다**: 세는 것은 **루트가 제 `faction:` 으로 지목한
     세력**뿐이다 (문의 이름은 그 문에만 적혀 있다). 그리고 `routes` 절로 파고들지 않아
     파일 최상단(meta·routes)만 훑고 「입문 경로 0곳」을 낸 판도 있었다.
- ★그 교정이 **더 큰 사실**을 드러냈다: **입문 루트는 16개뿐이고, 정파는 화산·당가 둘뿐이다.**
  소림·무당·개방·아미·청성·종남·점창·해남·남궁·제갈·팽가·모용에 **입문 경로가 없다**
  (눈이 22곳을 세던 것은 느슨한 언급이 부풀린 값이었다). 새외 폐쇄 후 **입문 가능 = 9곳**:
  화산·당가·하오문·녹림·장강수로채·살막·관군·마교·혈교.
  → ★이것은 B-188 의 일이 아니다 (faction_entry_routes 축). **별도 항목이 필요하다.**
- 뮤테이션 4건 추가 (⑳-2 폐쇄를 풀면 넘어온다 · ⑳-3 유보 칸 = 새외 6곳 정확히 ·
  ⑳-4 「의도」 표시가 세력 수를 바꾼다) — `--selftest` **26/26**.
- 눈: lint 0 · combat 린트 **위반 0**(빈 방 10곳 → **2곳**: 장강수로채·마교) · motion 0 ·
  game 0 · 장부 0.

★★결(무공_색) 일괄 기입 — 자료 출처 확정 (2026-07-26 · 사용자 지시 「이 사이트의 내용을
바탕으로 기본적인 결을 정합시다」 · 출처 **haomoon.kr 무협/세력** 38편):
- ★**실태부터**: 「백지」는 무공 등재가 0인 것이고, **결은 이미 있었다** — 다만 **새외 6곳만**이었다
  (`무공_색` 필드 6개 전부 새외). 사파 5·금기 2 는 **한 줄 설명뿐**이었다. 그래서 이번 회차는
  새외를 건드리지 않고 **결이 없던 쪽에 새외와 같은 문법으로 붙였다.**
- ★★**무기 기준 ★0 둘이 자료로 확정됐다** (B-188 기준표가 지목해 둔 그 자리):
  · **구(鉤)** ← **장강수로채** — 자료: 「**쇠사슬 달린 무기**로 배를 잇고 상대를 속박」
  · **부(斧)** ← **녹림** — 자료: 「주요 무기는 **도끼와 창**」
- ★**「사파는 심법 0 이 의도인가」 문답이 닫혔다**: 녹림 자료가 「대부분 **내공보단 외공에 치중**,
  녹림왕만 내외공을 겸한다」고 말한다 → **심법 0 은 구멍이 아니라 결이다** (녹림왕이 천장).
- 결 기입 8곳: 하오문(★下五門 = 점·각·아·차·선 다섯 직업 · 개방과 **쌍벽** · 대부분 문도는 무공이
  없다 = 정보망의 값) · 녹림(칠십이채 · 율법 **청록대전** · 진짜 무림인과는 안 싸운다) ·
  장강수로채(18수채 · 거점이 배와 섬 · **물 위에서는 정파 무가보다 우월** = 경지가 아니라 땅이
  정하는 유일한 세력) · 살막(★**차륜전** — 값은 개인의 강함이 아니라 **끝나지 않는다는 것**) ·
  독문(자료에 없다 → 당가와의 **차이**로만: 같은 심법, 없는 계율) · 마교(★「마교」는 세력의 이름이
  아니라 **중원의 판단**이다 — 내부 이름은 천마신교 → 배화신교 오해 기계가 정당해진다) ·
  혈교(마교가 **빼앗는다**면 혈교는 **바친다** — 대가를 남이 치르는 유일한 결) · **낭인회(신설)**.
- ★★**낭인회 신설 — 눈이 낸 문답이 자료로 닫혔다**: `lint_faction_coverage` 가 「컨테이너
  사파/흑도 계열에 **음풍검**이 붙어 있다」를 잡고 「어느 문파도 아닌 무공이면 1급 id 를 세워라」
  했다. 자료에 그 세력이 있었다 — **낭인회**(주군 없이 떠도는 무인들의 연합 · 문파가 아니다 ·
  낭인왕 칭호는 **비소속자도 받는다** = 조직이 아니라는 증거). orthodox_heroes 의 사파 쪽 짝.
  음풍검을 `unorthodox` → `nanginhoe` 로 옮기고 tier 하급 기재 → **컨테이너 오분류 경고 0.**
- ★사이트 축과 우리 축이 어긋난 곳 (**임의로 바꾸지 않았다 — 문답 대기**):
  · 사이트의 새외는 **새외오궁(宮)** = 태양궁(극양·사막)·포달랍궁(티베트 밀교)·북해빙궁(극음·북방)·
    남만야수궁(남만 독·야수)+1 — **무공의 결로 가른 축**이다.
  · 우리 새외는 **지리·문화로 가른 축** = 설역밀교·배화신교·북막(나라)·동영(섬)·서역상맹(상단).
  · 매핑: 설역밀교 ↔ **포달랍궁 정확 일치**(라마·밀교·호흡법) · 오독교 ↔ 남만야수궁 **부분**
    (사이트엔 **야수 조련**이 더 있고 고독·묘족은 없다) · 배화신교 ↔ 태양궁 **반**(극양·화공은
    겹치나 **신앙 축은 명교**에 있다) · 북막 ↔ 북해빙궁 **불일치**(우리 북막은 나라다) ·
    동영·서역상맹은 사이트에 짝이 없다.
  · ★**빙공(極陰) 축이 우리에게 없다** — 태양궁↔북해빙궁 대립은 강력한 무공 축인데 우리 세계엔
    화공(배화신교)만 있다. 도입 여부는 사용자 결정.
- ★사이트에 있는데 우리에게 **없는 세력** (문답 대기): **무림맹**(정파의 기둥!) · **공동파**
  (구파일방인데 없다 — 우리는 해남이 그 자리) · **금의위·동창**(황궁 세력 — 우리는 관군·현령만) ·
  은자림·모산파·설산파·장백파·혈궁 · ★**정사지간(正邪之間) 계열 축**(표국·은자림이 여기 —
  우리 6계열에 이 축이 없다).
- 곁가지: 낭인회는 아직 `faction_entry_routes` 에 문이 없다 (신설 세력이라 당연 — 넣을지는 결정).
- 눈: lint 0 · combat 린트 **위반 0** · motion 0 · game 0 · 장부 0 · **selftest 23/23**.

★채움 순서 【사용자 확정 2026-07-26 — 입문 가능한 곳 먼저】:
① ~~**모용**~~ · ② ~~**소림**~~ · ③ ~~**개방**~~ · ④ ~~**당가**~~ (✓ 넷 다) →
**⑤ 장강수로채**(구 해소) → **⑥ 오독교** → **⑦ 설역 밀교** → **⑧ 마교·독문**(짝 — 심법만 있는 둘) →
**⑨ 배화신교·북막·동영·서역상맹·상단** → **⑩ 살막·혈교·표국·관군·하오문 보강** →
⑪ 무당 권장법 → ⑫ 남궁 기초·경공 → ⑬ 민간·군문 (겸·중병기·부 기준 해소) →
⑭ 곤륜~해남 소소 → ⑮ tier 미기재 일괄 정리 → ⑯ 선법 증축.
★옛 ⑦ 「사파·민간·군문 회차」는 위 ⑤~⑩ 으로 **흩어 배치**했다 — 세력별 한 회차가 이 프로그램의
단위이고, 「사파」를 한 덩어리로 묶으면 수로채·살막·하오문이 서로 다른 결인 것을 지운다.

★전수 조사 결과 (2026-07-26 — 매꿀 준비 지도. 사용자 지시 「순서상 빈 부분 찾고 매꿀 준비」):
| 세력 | 구멍 |
|---|---|
| ★모용세가 | **무공 0** (심법 2만 — 손이 없다) · 오의 0 · 가전 입장권 불가 |
| ★제갈세가 | **전부 0** (무공·심법·오의 — 완전 백지) |
| 개방 | **봉법 하급 없음** (중·상급만 — 사다리가 공중에서 시작. 박투 하급이 첫 칸인지 명문화 필요) |
| 당가 | **독공 하급·상급 없음** (중급 칠보독장뿐 — 무형지독 오의가 유일하게 **중급** 선행) |
| 소림 | 권법 상급 없음 (장법 반야장이 그 자리 — 의도로 보임 · 명문화만) |
| 화산 등 | tier 미기재 구식 등재 다수 (화산 검법 일부·경공·합격진·박투·금나술 — 사다리 눈이 못 센다) |
| 녹림·하오문 | 심법 0 · 오의 0 · tier 전무 (사파/뒷골목 결이면 의도 — 사용자 확인 필요) |
- 온전한 사다리: 검법 3단 완비 = 무당·곤륜·종남·점창·청성·해남·남궁·화산 · 팽가 도법 4단(기초 포함).
- 오의 선행 21종 전수: 전부 실존 ✓ (초기 4종만 선행 미기재 — 명문화 대상).
- 매꿈 우선순위 【제안】: ① ~~모용·~~제갈 (✓ 아래 — **모용만 남음**: 자료 대기) ② 당가 독공
  사다리 ③ 개방 봉법 하급 ④ tier 미기재 정리(일괄) ⑤ 녹림·하오문 거취 문답.
  파일럿 순서(하북팽 장원→화산) 및 B-176/오의 회차와 동행.

진행 (2026-07-26 · ★제갈세가 전량 등재 — 사용자 제공 자료):
- 자료: 심법 소천성공·대천성신공·현원전단신공 / 검법 소천성·대천성·철현·칠현무형·천지호연 /
  보법 천기미리보. 결: 별(天星)·현(絃)·와룡 — 「원천이 정형화돼 있지 않다 (작가 맘)」 →
  급 배치·수치 재량 【제안】.
- 등재 (구멍 지도의 ★제갈 「전부 0」 소멸): 심법 3 (소천성공 기초 → 대천성신공 상승 →
  현원전단신공 **가주 일인전승**) · 검법 사다리 잠룡검식(기초 ★지어냄 — 와룡의 결, 팽가
  복호와 대구)→소천성(하)→대천성(중)→철현(상 — 현음 hit 2) · 보법 천기미리보(중급 —
  iframe 7) · **오의 2** 칠현무형검(선 · 다단 7 — 일곱 줄)·천지호연검법(원 · **가주
  일인전승** — sect_life 자물쇠 등재). 입장권 → 잠룡검식 (삼류부터).
- 눈: lint 0 · combat 린트 0 · motion 0 (오의 3D 지도 「23종 전수」가 신규 2종 누락을
  잡아 즉시 배선 — 눈이 제 몫을 했다) · 재배포 ✓ (MVT Done 29.2s · ERROR 0 · 봇 JDA ✓).
- 남은 세가 구멍: **모용세가 무공 0** (심법 2는 있음) — 자료 오면 같은 문법으로.

진행 (2026-07-26 · ★제갈 확장 2 — 부채·팔진도 · 사용자 확정 2건):
- **확정 ①** 「부채로도 싸우고, 마법처럼 진법을」 · **확정 ②** 「부채는 스킬 위주」.
- **부채(扇) 무기 계열 신설** (14계열째): Weapons Series 부채(BRUSH 징발 — 활 선례: 등급은
  툴팁·PDC · 팩 모델은 팩 회차) · weapon_power **1** (맨손과 같다 — ★스킬 위주: 값은 전부
  초식·격 라이더·진에 실린다) · attacker_attribute 민첩 · 명병 지도 jegal→부채(학우선) ·
  weapon_classes registry·weapon_styles(접어벤다)·swings·basic_strike 전 등록부 등재.
- **무공 2종**: 학우선법(선법 하급 — 펼쳐 가리고 접어 벤다 · 마무리는 현음) ·
  **팔진도**(환진 2~5인 — 「마법처럼 보이는 것은 자리다」: 매화검진 문법 + 갇힌 적 도주 판정
  -2 【제안】 · party.yml 정합은 진법 회차). 제갈 총 무공 7종 + 심법 3 + 오의 2.
- 눈이 세 번 제 몫을 했다: motion_audit 이 style↔weapon_class 불일치 → 계열의 손 누락 →
  기본 초식 누락을 차례로 잡아 전 등록부가 채워졌다. lint·combat 린트·motion 전부 0 ·
  빌드 ✓ · 재배포 ✓. 후속: 선법 중·상급 증축 · 부채 팩 모델 (팩 회차).

진행 (2026-07-26 · ★★② 소림 전량 증축 + **사다리 눈 신설** — 사용자 제공 자료 · 닫는 조건 ③ 닫힘):
- **★자료 안의 발견**: 자료의 「장법」 목록에 掌(손바닥)이 아니라 **杖(지팡이)** 둘이 섞여 있었다 —
  **항마선장(降魔禪杖)·대윤회겁륜장(大輪廻劫輪杖)**. 동음이의가 가렸을 뿐, 소림 봉 무공의 정격
  이름은 자료에 이미 있었다 (장문영부도 **녹옥불장(綠玉佛杖)** — 최고 권위가 지팡이다).
  **그래서 곤법 계보를 지어내지 않았다.**
- 등재 10종 + 심법 1: 장법 사다리 **위타장**(하 — 자료가 「초보적인 장법」이라 못 박음)→**나한십팔장**
  (중)→반야장(상·기존) · 곤법 계보 sorim_gon **항마선장**(하)→**대윤회겁륜장**(상) · 지공 계보
  sorim_ji **탄지신통**(중)→**일지선**(상 — ★격공지: `cover_rule` 엄폐 **무시**, 전 무공 유일) ·
  **소림금나십팔타**(중) · **항마연환신퇴**(중) · **사자후**(음공 신설 — 전 무공 유일의 순수 판정기) ·
  **나한진**(합격진 — ★붕괴 조건이 다르다: 절반이 쓰러져야 무너진다) · 심법 **나한기공**(문파_기초).
- **딱지 벗김**: 반야장(般若掌)이 자료 실존 → 옛 ★지어냄 제거 (모용 건곤무적공 선례).
- **선행 이관**: 반야장이 소림권법(권법)을 물던 계열 어긋난 배선 → 나한십팔장(장법)으로.
  소림권법은 막다른 길이 아니다 (퇴법·사자후·나한진이 그 뒤).
- 기준 변동: **봉 ★1 → ✓2** (개방+소림 · 결까지 갈랐다 — 개방은 150° 최광각으로 훑고, 소림 선장은
  곧게 짚고 찍는다) · **지법 1 → 2** (모용+소림) · 금나·퇴 공급 +1.
- 명문화 (지어내지 않음): **곤법 중급 한 칸이 비었다** (하→상 직행 — 남궁제황검·모용청죽수 문법).
  자료에 소림 중급 곤법 이름이 없다 → 사용자 문답 대기 (후보: 소림곤법·위타곤·복마곤).
  세수진경(洗髓眞經)은 역근경 항목이 이미 담고 있음을 주석으로 명문화 (passive 이름이 「세수역근」).
- 미등재 후보 (자료에 있으나 이번에 안 건드림): 지공 11 · 수공 11 · 조법 5 · 인법 3 · 수(袖) 3 ·
  검법 5 · 도법 3 · 각법 3 · 신법 9 · 심법 다수 · 혜광심어·개정벌모세수대법 등.

- ★★**닫는 조건 ③ 닫힘 — 사다리 연결 눈(chain-walk) 신설** (`combat_audit.py lint_ladder_chain`):
  선행을 거슬러 첫 칸에 닿는가를 잰다 — 허공 참조·고리·**사다리 역행**은 위반, 공중시작·tier
  미기재는 경고 (★심각도 근거: 선행 없는 중급은 **굴러간다**. 자기모순이 아니라 설계 구멍이다).
  **눈을 시험하는 눈 7건 신설** (⑩~⑯) — `--selftest` **16/16**.
- 눈이 서자마자 잡은 것: ① 제 것 역행 2건(중급←중급) ② **오의 선행 미기재 4건**
  (매화만개·태극혜검·제왕군림·혈해만리 → 전부 배선 — 장부의 손 조사 「초기 4종」과 정확히 일치) ·
  남은 경고는 프로그램이 닫을 것들(공중시작 4 = 팽가·당가·모용 첫 칸 · tier 미기재 32 = ⑨).
- ★이 눈이 **처음엔 거짓말을 했다**: 정본 키를 헛짚어(`ultimate_arts` ≠ `legacy_arts`) 오의 0종에
  「전부 통과」를 냈다. 빈 등록부에 합격을 주는 눈은 눈이 아니다 → 실패로 세게 고치고 **묘비 프로브
  ⑮** 를 박았다. 프로브 ⑬ 도 같은 과의 함정이었다 — 여러 건을 한 줄로 묶어 내는 경고는 대상이
  늘어도 **건수가 그대로**라 건수만 보는 프로브가 조용히 통과한다. 그래서 건수가 아니라
  **없던 문자열이 생겼는가**로 잰다.
- 눈: lint 0 · combat 린트 **위반 0** · motion 0 (커버리지 150/150) · game 0 · 장부 0 ·
  combat 전체 위반 7건은 **전부 기존 B-177 v2 수치 빚** (TTK — 이번 변경분 아님).
- 실측: config 재배포 ✓ (MVT **Done 37.2s · ERROR 0**) · 봇 재기동 ✓ (토큰 72 확보 → SIGTERM →
  env 직달 · JDA Login Successful · DB postgresql) · 배포본 config 에 신규 id 실재 확인.

진행 (2026-07-26 · ★★③ 개방 전량 증축 — 사용자 제공 자료 · 구멍 지도 「개방」 줄 소멸):
- **★같은 함정이 두 번**: 자료의 **옥룡팔장(玉龍八杖)** 도 掌이 아니라 **杖**이다 (자료가 한자를
  병기해 못 박아 뒀다). 소림 항마선장·대윤회겁륜장에 이은 두 번째 — **「장」으로 끝나는 이름은
  掌인지 杖인지 먼저 본다**가 이제 이 프로그램의 계율이다.
- **정격 개명**: ★지어냄이던 `gaebang_bongbeop`「개방봉법」(중급) → **`okryong_paljang`
  옥룡팔장** (팽가 도법 개명 선례 · 전 등록부 6곳 + sect_life 5결 당주 전수 항목 동시 이관).
- **봉 사다리 완성** (구멍 「봉법 하급 없음」 소멸): **천화봉법**(하 — 신설) → 옥룡팔장(중) →
  타구봉법(상·방주 일인전승). 선행이 취권(박투)에서 봉법 안으로 정리됐다.
- 등재 10종 + 심법 3 (전부 자료 정격): 천화봉법 · **쇄비권**(하)→**파옥권**(상 · ★armor_pierce 2
  = 전 무공 최고 관통) · 취권(기존)→**취팔선권**(중) · **용음십이수**(수공 중) · **쇄심지**(지법 중) ·
  **타구진**(합격진) · **비천무영신법**(신법 상) · 취호붕격 **tier 기재**(중급 — 구멍 지도 「박투 tier 정리」) ·
  심법 **취팔선공**(문파_기초)·**백결연화신공**(상승)·**혼천강룡신공**(방주 일인전승).
- ★★**자료의 설정을 기제로 옮긴 것 3** (이 회차의 값은 여기 있다):
  ① **가난** — 천화봉법 `rearm_free`: 봉이 부러져도 **재무장이 행동을 먹지 않는다**
     (qi_manifestation `weapon_break.after_break` 「재무장 = 행동 1개」의 **유일한 예외**).
     자료 「아무 막대기나 들면 되는 봉법」 → 명병 보정을 못 받는 대신 파봉의 대가도 없다.
  ② **머릿수** — 타구진: 문턱 **삼류**(전 합격진 최저)·전개 16틱(최속)·**붕괴하지 않고 줄어든다**
     (다른 진은 1인, 나한진은 절반에서 무너진다). 대신 **슬롯 4** — 남들은 5다.
     「모이기는 쉽고 세지지는 않는다」가 떨거지의 값이다.
  ③ **비천한 마음가짐** — 백결연화신공: 무기 등급이 범철 이하일 때 발경 +1, 전낭이 서민
     생활비 이하일 때 회복 +1. **재물을 모으면 스스로 약해지는 유일한 심법** (수치는 【제안】 —
     equipment·economy 축 배선은 안 했다. 선언만).
- 맨손에 길이 둘: 취(醉 — 안 맞는 것이 방어) vs 쇄·파(碎破 — 정면으로 부순다). 술을 못 마시는
  거지도 싸울 수 있어야 한다. 지법 공급원 **3**(모용·소림·개방) · 수공 **2**(모용·개방).
- 명문화 (지어내지 않음): **개방 중급 강권 한 칸이 비었다** (쇄비권 하 → 파옥권 상 직행).
  미등재 후보: 홍무자염신공·만리추풍신법·취리건곤보 · **만천화우**(자료: 원래 개방 무공이었으나
  당문 것으로 굳었다 — ④ 당가 회차에서 거취를 묻는다).
- 눈: lint 0 · combat 린트 **위반 0** · motion 0 (커버리지 100%) · game 0 · **selftest 16/16** ·
  사다리 눈 전 항목 ✅ (개명한 id 가 허공을 가리키지 않는지도 이 눈이 봤다).
- 실측: 재배포 ✓ (MVT **Done 26.0s · ERROR 0**) · 봇 재기동 ✓ (토큰 72 → JDA Login Successful ·
  DB postgresql) · 배포본 config 에 신규 id·개명 id 실재 확인.

진행 (2026-07-26 · ★★④ 당가 전량 증축 + **편(鞭) 계열 신설 15계열째** — 사용자 제공 자료):
- **★암기는 이미 완비였다**: 당문비수술(하)→당문비접표(중)→만천화우(상)+오의 2 · 심법 3(도반삼양귀원공·
  만류귀원신공 — 자료 정격 그대로). 남은 구멍은 **독공 상급**과 **편법 전무**뿐이었다.
- ★★**「하급이 없다」는 구멍이 아니라 설계였다** — 칠보독장 등재가 이미 명문화하고 있었다:
  「독은 손이 아니라 몸으로 배운다 — 심법(칠살음독경)이 문턱이다. 그래서 이 계보에는 하급이 없다」.
  **없는 구멍을 지어낼 뻔했다.** 사다리 눈이 `requires_simbeop` 를 못 읽어 공중시작으로 세고 있었다
  → 눈을 고쳤다 (심법 문턱을 뿌리로 인정 + **그 심법이 simbeop.yml 에 실재하는지** 새로 잰다).
  공중시작 경고 4 → 3.
- **독공 사다리 완성**: 심법 문턱 → 칠보독장(중) → **적련신장(상 · 신설)** → 무형지독(오의).
  오의 선행을 **중급→상급**으로 올렸다 (구멍 지도가 지적한 「오의가 유일하게 중급을 선행으로 문다」 소멸).
- ★★**편(鞭) 무기 계열 신설 — 15계열째** (부채 14계열째에 이어): 자료가 편법 **5종**을 통째로 준다.
  등록부 한 벌 = skills weapon_classes.registry·default_by_category · combat weapon_power·
  attacker_attribute · skill_motion weapon_styles·swings·basic_strike · **Weapons.java** Series·Base·
  한자 switch·flavor switch (**switch 2곳이 exhaustive** — 계율대로 컴파일이 잡는 자리다).
  · `Base.ROD` = 낚싯대 징발 (활 BOW·부채 BRUSH 선례 — 재질 사다리 없음, 등급은 툴팁·PDC).
  · ★값의 원칙: **간격 5.0m(창과 같은 최장)인데 위력 2** — 「거리를 위력에서 뺀다」(활 3·암기 2)를
    근접에 처음 적용했다. 대가는 `min_range 1.5` — **코앞에서는 못 휘두른다**.
- 등재 10종: 편법 5 (백승연편 하 → 회타연편십삼식 중〔되받기 counter〕/ 황사만리편법 중〔★호 160°
  = **전 무공 최광각**〕→ 금룡편법 상〔선 6.0m = **병기 최장**〕/ 호연십팔편 상〔원 4.4 = 원 최대〕) ·
  독공 적련신장(상) · **삼양 계보** 비서장(하)→삼양신장(중)·삼양지(중 · 지법 **4번째 공급원**,
  ★hit_count 3 = 지법 유일 3연 명중)·삼양수(금나 중) · 비홍침 **tier 기재**.
- ★자료의 결을 옮긴 것: **호연십팔편**은 당가 상급기 중 **유일하게 on_hit 에 중독이 없다**
  (자료: 「정당한 대결에서는 절대로 독을 사용하지 않았다」 — 당가가 정파로 인정받은 얼굴).
  **삼양 계보**는 한 심법에서 장·지·수 **세 손이 갈리는 전 세력 유일한 모양**이다.
- 편은 공급원 1곳(당가) — **★1**. 두 번째 공급원은 ⑦ 사파·민간·군문 회차 (녹림·마부 후보) 【제안】.
- 명문화: 만천화우 거취 문답 보류 (자료: 원래 개방 무공 — 지금 등재는 당가 것 그대로 둔다).
- 눈: lint 0 · combat 린트 **위반 0** · motion 0 (커버리지 168/168) · game 0 · 장부 0 ·
  **selftest 18/18** (⑰ 심법 문턱 허공 · ⑱ 묘비 「없는 구멍을 지어내지 않는다」 신설).
- 실측: **jar 재빌드 + 재배포 ✓** (컴파일 오류 0 — switch 2곳이 전부였다 · MVT **Done 31.8s ·
  ERROR 0** · 룰 엔진 5종 로드) · 봇 재기동 ✓ (JDA Login Successful · DB postgresql).
  ★함정 하나: `pgrep -f "server-bot"` 가 **제 명령줄까지 잡아** 잘못된 PID 를 골라 재기동이 조용히
  실패했다 (토큰 길이 검사가 막아 줘서 죽이지는 않았다). 봇 PID 는 `ps | awk '/bin\/java -jar/'` 로 집는다.

### B-185 · RCON 에서 혼천 명령을 치면 RCON 이 통째로 죽는다 (2026-07-25 실증 2회)
- **상태**: 진행
- **분류**: 결함
- **단계**: P3
- **위치**: Paper RCON ↔ 플러그인 명령 디스패치 (근인 미규명) · `HoncheonMvt` 콘솔함
- **의존**: —
- **닫는 조건**: 근인이 밝혀지거나, 콘솔함이 관리 명령 통로의 정본으로 확정되고 그 눈이 선다
- **검증**: RCON 으로 `honcheon ...` 실행 후 RCON 생존 확인 (또는 콘솔함 왕복 실측)
- **닫힘**: —

실증 (2026-07-25 저녁 · 잔재 점검 중): `혼천`·`honcheon` 어느 이름으로도 RCON 에서 플러그인
명령은 **로그 한 줄 없이 실행되지 않고**, 그 뒤 RCON 리스너가 새 접속을 영영 안 받는다
(재기동 전까지 — 2회 재현: 18:0x·18:1x). 바닐라 명령은 정상. ★대체 통로 신설 = **콘솔함**
(`plugins/HoncheonMVT/console_inbox.txt` — 파일 한 줄 = 콘솔 명령 · 응답은 콘솔 로그 · 다리
문법). 함께: 앵커검사/재측이 로그 먼저 찍게 개정 (RCON 응답 유실 대비 — §4 조성의 계율).

### B-186 · 옛 월드 잔재 점검 (2026-07-25 저녁 — 사용자 제안 「점검 타임」)
- **상태**: 닫힘
- **분류**: 결함
- **단계**: P3
- **위치**: anchors.yml · PostgreSQL · playerdata · honcheon_seojang
- **의존**: —
- **닫는 조건**: 네 축(앵커·DB·플레이어데이터·서장 월드) 점검과 발견물 처리
- **검증**: `bash -c "grep 앵커검사 run/mvt/server-console.log"` (콘솔함 실행 흔적) + 도커 psql 매달린 참조 쿼리
- **닫힘**: 2026-07-25 — 증거: `bash -c 'grep 앵커검사 run/mvt/server-console.log'` → 재측 후 8/9 ✅ (장터=원점 보호 · 착지 보정 상시) · 매달린 참조 0 (전 표 psql 실측)
  상세: ① ★**마을 앵커 8/9 이 건축 속** (곽진 질식과 같은 뿌리 — 옛 배치 위에 v5 건축이
  덮임. Standing.landing 이 사람만 구하고 있었다) → `앵커재측`으로 7곳 재박음 · 실측 재검
  전부 ✅ ② DB 매달린 참조 0 (mvt_link 의 NULL character_id 는 사망 명부의 설계 — 오탐 1회
  기록) · 유령 주체 소문 9행은 배경음(B-180 가드) ③ 플레이어데이터 3몸 전부 정상 경로
  (Euncheaven=재생성 나루 좌표 → 물안개 leash 자연 회수) ④ 서장 월드 잔재 0 (조형 비영속 ·
  사공 1). 부산물: B-185 발견 · 콘솔함 신설 · pg_dump 백업(run/backup-db).

### B-184 · 나루 글판이 하나 모자라다 — 기동 계수 「글판 1/2」 (2026-07-25 낮 관측)
- **상태**: 닫힘
- **분류**: 결함
- **단계**: P3
- **위치**: `Antechamber` 글판 ensure · `config/antechamber.yml` text_display/stations
- **의존**: —
- **닫는 조건**: 기동 계수가 글판 2/2 로 서고, 어느 글판이 왜 안 섰는지(겹침·자리·ensure
  조기 return) 원인이 적힌다
- **검증**: 기동 로그 `나루가 섰다 … 글판 2/2` + `python3 tools/antechamber_audit.py`
- **닫힘**: 2026-07-25 — 옛 월드 잔재 (증거: `bash -c "grep 글판 run/mvt/server-console.log"` → 재생성 후 기동 전수 「글판 2/2」 · 콘솔 실측)
  상세: 5차 개정의 나루 월드 백업(20260725-125146) 후 재생성 이래 12:56·14:33·14:46·16:13
  기동 전부 2/2 — 1/2 는 전부 재생성 이전 기동(00:53·04:59·11:56·12:53). 재생성 전 월드에서
  3차 개정 조성 갱신이 옛 글판 엔티티와 어긋난 것 — 새 등록부의 조성은 처음부터 2/2 로 선다.
  재생성 전 월드에서 3차 개정 조성 갱신이 옛 글판 엔티티와 어긋난 것 — 새 등록부의 조성은
  처음부터 2/2 로 선다. 콘솔 ERROR 눈은 존속 (재발하면 즉시 짖는다).

관측 (2026-07-25): 07-25 03:41 기동은 **2/2**, 00:53·04:59·11:56 기동은 **1/2** (콘솔 ERROR
「등록부보다 적다 — 무엇인가 조용히 죽었다」 — 눈은 짖고 있다). 03:41↔04:59 사이 회차(3차
개정·세가 배선)에서 하나가 안 서게 된 것으로 추정. 도하 4차 회차는 글판을 안 만졌다 —
독립 결함으로 분리해 적는다.

### B-183 · 한 다리에 두 몸이 썼다 — 잔류 테스트 서버가 명부를 지워 접합이 막혔다 (2026-07-25 04시 실증)
- **상태**: 진행
- **분류**: 결함
- **단계**: P2
- **위치**: `WorldBridge` 다리 해석 · 테스트 서버 기동 관행 (정본 스크립트 부재)
- **의존**: —
- **닫는 조건**: 라이브가 아닌 몸(cwd ≠ run/mvt)은 공유 다리(run/bridge)에 절대 쓰지 않는다 —
  격리 가드가 실기동으로 확인되고, 가드의 눈(순수 함수 selftest)이 선다. 테스트 서버 기동의
  정본 스크립트(잔류 방지 포함)는 별도 조각
- **검증**: 테스트 서버 기동 상태에서 라이브 명부가 안 지워짐 (실기동) + 가드 selftest
- **닫힘**: —

실증 (2026-07-25 04:00 · 실사용 "자꾸 마크와 잇기가 안 되는데"): 어제 아침 kigi 촬영 회차가
남긴 **잔류 테스트 서버(run/mvt-test · 20시간)** 의 WorldBridge 가 repoRoot 탐색으로 **같은
run/bridge** 에 붙어, 빈 명부(players:{})를 5초마다 덮어썼다 — 봇은 "그 이름이 지금 강호에
없다"고 읽고 [마크와 잇기] 청을 거절. 라이브의 명부 발행은 정상이었다 (이중 작성자의 침묵 —
DB 단일 작성자 규약의 다리판 재발). 조치: ① 잔류 테스트 서버 종료 → 명부 즉시 회복 (실측)
② WorldBridge 격리 가드 — 라이브 판별은 배포 정본(live_pids.sh)과 같은 자(cwd=run/mvt),
다른 몸은 cwd/bridge 격리 (빌드 ✓ · 다음 재배포에 실림). 남은 조각: 가드 눈 + 테스트 기동
정본 스크립트 (지금은 즉석 기동 관행이 잔류를 낳는다).

### B-182 · 세가의_자제 — 오대세가 실명 루트 (사용자 확정 2026-07-25 새벽 · 설계 문답 4건)
- **상태**: 진행
- **분류**: 미완
- **단계**: P2
- **위치**: `config/player_creation.yml` (sega_promotion·families.세가의_자제·발단 3종·적서·거절) ·
  `config/seojang.yml` (세가 벌) · `config/seojang_stage.yml` · `server-bot` (승격·세가 지목)
- **의존**: 탄생=대사건의 세계반응은 소문 시스템(B-176) 회차
- **닫는 조건**: 무가 당첨의 승격 주사위로 오대세가(남궁·모용·제갈·하북팽·사천당) 자제가
  태어나고, 세가 전용 발단(비무행·그림자_시험·밀서)과 전용 서장 벌·무대를 산다. 적서·거절
  포함. 실기동으로 세가 자제 1회 관측 + 문안 빨간펜
- **검증**: `python3 tools/antechamber_audit.py` (⑧-4) + 세가 캐릭터 실기동 (사람 눈)
- **닫힘**: —

확정 (2026-07-25 설계 문답): ① **오대세가 실명 자제** (factions 정본과 연동 — 옛 등록부의
「오대세가급 직계 배제」 개정. 백지의 평등은 grants 가 지킨다: 세가도 능력치 0 — 더 큰
입장권과 더 큰 빚뿐) ② **무가 당첨 중 승격 주사위** (결은 지켜지고 무늬가 격을 올린다 ·
★재확정 2026-07-25: "20명 중 2~3명은 세가였으면" → **chance_pct 80** — P(무가)≈1/7 이라
전체 세가율 ≈11% ≈ 20명 중 2.3명. 향반 무가 잔존 ≈0.6명/20 — 실측이 어긋나면 숫자 하나만
고친다) ③ **세가 전용 발단+벌 신설** ④ **몰락무가 적서도 적는다** (등록부의
【사용자 확인 요망】 종결 — 소문 강도 0 · 시트에만).
배선 (같은 새벽): sega_promotion·families.세가의_자제(월례 40~80 【제안】·great_houses 5)·
발단 3종(전부 「명령」의 결·family_only)·적서 houses 3집·거절 확장 · 봇 rollFamily 승격+
세가 지목 주사위(시트 「세가」 칸) · 서장 세가 벌 3장+기준 서사+에필로그 5등급 · 무대
sets.세가 4장+발단 무대 3종 — 문장·조형·수치 전부 【제안】. 눈: ②-c **일반화** (family_only
발단은 전부 제 벌 — 수행·출분·세가 공통) + 뮤테이션 (103) — **눈의 시험 81/81** · 빌드·감사·
lint 0. 미결: 성씨(남궁 등)를 이름 짓기에 안내할 것인가 · LLM 컨텍스트에 세가 이름 실기 ·
탄생 소문 대사건(B-176 연계) · 세가 거절 문구의 세가판(현재 무가 문안 공용).

진행 (2026-07-25 오후 · ★미결 4건 사용자 확정·배선): ① **성씨 = 안내만** — 세가 지목
주사위를 rollFamily 승격 순간으로 앞당겨(Creation.greatHouse — 시트 조립은 선굴림 우선)
거절 문답 화면이 지목된 세가의 이름을 부르고, 성씨 안내 한 줄(great_house_surnames 등록부 —
하북팽가=팽·사천당가=당, 코드가 성을 지어내지 않는다)을 얹는다 (「남는다」 전에 표시 이름을
남궁○○ 로 — 강요 없음 【제안】) ② **붓에 세가 실명** — sceneFacts 「세가: 남궁세가 (천하가
아는 이름 — 서사에 써도 된다)」 (규칙 3 범위 안) ③ **탄생 대사건 = B-176 회차로 유보**
(사용자 확정 — 지금은 강도 5 전파로 충분) ④ **거절 문구 세가판** — prompt/accept/refuse
세가판 신설 ({house} 치환 · 문장 【제안】). 뮤테이션 (133) 재표적 — **눈의 시험 115/115** ·
감사·dispo·lint 0 · 봇 재기동 ✓. 남은 것: 세가 자제 실전 완주 (사람 눈 — 닫는 조건).

### B-181 · 출분(出奔) 서장이 재난의 뼈대를 빌려 입고 있다 (2026-07-25 대조에서 발견)
- **상태**: 열림
- **분류**: 결함
- **단계**: P2
- **위치**: `config/seojang.yml scenes` · `server-bot Seojang.branchOf` · `config/seojang_stage.yml sets`
- **의존**: —
- **닫는 조건**: 출분 3발단(담을_넘다·아버지의_검·파혼)이 전용 서장 벌(3장 — 제목·선택지가
  「저지른 아이」의 결)을 갖는다 — branchOf 가 출분을 제 벌로 보내고, 무대 sets.출분이 서고,
  감사(정거장 수·제목 대조)가 통과한다. 장면 문안은 사용자 시안 확정 뒤
- **검증**: `python3 tools/antechamber_audit.py` (⑧-4 제목 대조) + 출분 캐릭터 실기동
- **닫힘**: —

발견 (2026-07-25 · 심리테스트→서장 전체 대조): `branchOf` 는 수행_파견만 가르고 **출분
3발단은 기본(재난) 벌**로 떨어진다 — 「그날 밤」의 선택지 "식구들부터 깨운다"가 제 발로
담을 넘은 아이에게 거짓말이 된다. seojang.yml 스스로 "재난형은 닥친 것, 출분형은 저지른 것
— 그 차이가 첫 문장에 있어야 한다"고 적어 두고 산문 도입(incident_opening)만 갈랐다.
3차(발단별 무대)로 1장 무대는 갈렸으나 **뼈대(제목·선택지)와 2·3장**이 남았다.
딸린 것: 기본[1](길 위에서) 무대 맥박 "붉은 놀" = 화재 전제 — 습격·역병·출분과 어긋남
(중립 문안 빨간펜 대상).

진행 (2026-07-25 · **배선 완료 — 문안 빨간펜만 남음**): ① `seojang.yml branch_of` 신설
(등록제 — 발단→벌. 수행_파견 + 출분 3) ② **출분 전용 벌** — 뼈대 3장 (담을 넘은 밤 ·
돌아보지 않는 길 · {region}—새 이름 — 선택지 전부 「저지른 아이」의 결) + 기준 서사(scene_body)
+ 에필로그 landing 5등급·closing ("제 발로 시작되었다") — 문장 전부 【제안】 ③ 봇
`Seojang.branchOf` 등록부 구동 (없는 벌이면 옛 규약 강등 — 등록부 없는 날에도 서장은 흐른다)
④ 무대 `sets.출분` 4장 (2·3장 = 새벽 관도·미명 / 낯선 객잔·새 이름 · 1장은 발단 무대가 이김).
눈: ②-c 갈래 배정 (가출한무가 풀 ⊆ 출분 벌 — 재난 낙하 금지 · 없는 벌 금지 · 봇 등록부
구동 대조) + 뮤테이션 (101)(102) — **눈의 시험 80/80** · 감사·lint 0 · MVT 재배포 ·
봇 재빌드+재기동 (토큰 절차 7회째). 닫힘은 출분 캐릭터 실기동 + 문안 빨간펜 뒤.

### B-180 · 세계일 정산이 지워진 캐릭터의 유령을 만진다 (FK 위반 1회 — 2026-07-25 발견)
- **상태**: 열림
- **분류**: 결함
- **단계**: P4
- **위치**: `server-bot` 세계일 정산 경로 · PostgreSQL faction_standing
- **의존**: —
- **닫는 조건**: 정산이 characters 에 없는 character_id 로 faction_standing 을 만들지 않는다 —
  유령 참조의 출처(어느 표가 지워진 캐릭터를 아직 가리키나)를 찾아 정리하고, 재발 방지
  (초기화가 지우는 표 목록 대조 또는 정산의 존재 검사)
- **검증**: `run/bot/bot.log` 에 `faction_standing_character_id_fkey` 재발 0 + 출처 표 확인 쿼리
- **닫힘**: —

발견 (2026-07-25 새벽): bot.log 1회 — "세계일 정산 실패: FK 위반 (character_id=4 가
characters 에 없다)". 이전 캐릭터 삭제(초기화 또는 수동)가 남긴 유령 참조로 추정.
단발이라 즉시 수술은 유보 — 재발하면 단계 승격.

진행 (2026-07-25 오후 · ★출처 확진 + 수리): 재발 실증 — 같은 뿌리가 **탄생 소문**도 물었다
(bot.log "탄생 소문을 심지 못했다 … faction_standing_character_id_fkey" · 실은 소문은
심어졌고(rumors 탄생:14 실물 확인) factionAwareness 가 **남의 유령**에 걸려 뒤늦게 터진 것).
출처 = **rumors 는 초기화 보호 표**: 지워진 캐릭터(id=4)의 소문 ~10행이 30일 재훑기 창에
남아, factionAwareness 가 존재 검사 없이 standing 을 꽂다 FK — 그 소문만이 아니라 **부른
쪽 전체**(세계일 정산·탄생 소문)가 죽는다. 수리: 주인 잃은 소문은 배경음으로 강등
(findCharacterById 존재 검사 → continue). 유령 행 정리는 불요 (faction_standing 유령 0 —
도커 psql 실측). 봇 재기동 ✓ (PostgreSQL · 토큰 파일 무경유 절차 신설). 닫기 조건 잔여:
bot.log 재발 0 관측 (다음 세계일 정산·다음 탄생 지나고).

### B-179 · 서장 = 기억의 회랑 — 걸으며 읽는 서장 (사용자 확정 2026-07-24)
- **상태**: 열림
- **분류**: 결정
- **단계**: P1
- **위치**: `docs/design/seojang_presentation.md`
- **의존**: —
- **닫는 조건**: 서장이 「기억의 회랑」이 된다 — 발단 계열(재난/명령/출분)별 무대를 걸으며 장면마다 그 자리에서 책 페이지가 열린다 (기존 책 파이프·LLM 개인 서사·선택지 보존 + 공간·조명·사건음이 몸을 감싼다). 설계 시안 → 사용자 승인 → 무대 조성 → 배선 → 신규 캐릭터 실기동
- **검증**: 신규 캐릭터 실기동 (사람 눈 — "겪는 서장"인가) + `python3 tools/antechamber_audit.py`
- **닫힘**: —

★사용자 확정 (2026-07-24): 형식 비교는 값이 아니라 **게임 하는 입장의 와닿음**으로 —
책(읽기 벽)·시네마틱(그릇 없음)·전면 플레이월드(LLM 서사 자리 좁음) 대신 **절충: 기억의
회랑** (겪음+개인 서사 겸임 · 무대 3종 — 11종보다 작음). 다음 회차 = 설계 시안.

진행 (2026-07-25 저녁 · ★★사전 집필 전환 + 다인 격리 2결함 — 실기동 2호 회차):
- **★사용자 확정: 서장 붓(LLM 실시간 생성) 폐지** — 「모든 루트의 출력값을 생성해두고 출력 ·
  이름만 변수」. 계기 = 그림자_시험 1장: 로컬 붓이 기준 서사를 **대체**해(SYSTEM 7항 위반 —
  프롬프트에 뼈대가 실린 것은 sceneFacts 로 확인) 발단의 전제(가주의 시험)가 통째로 빠진 뜻
  모를 장이 나갔다. 배선: `seojang.yml live_brush: false`(키 없음=폐지가 기본) →
  `writeScene0` 이 Scribe 를 안 거치고 **prose 등록부를 그대로 못 박는다** (fallback=false —
  옛 폴백 경로의 정본 승격 · 새 문장 파이프 0). 모든 루트 = scene_body 갈래 4 ×
  incident_opening 발단 13 × bridge 등급 5 × 색(가문 형태·고을·적서·형제) × epilogue 4×5 —
  {name} 만 몸이 채운다. 붓 기계(LlmRenderer·Scribe)는 /혼천 대화 의 것으로 존속.
- **★결함 2 (실기동: "2명이 서장 진입 시 같은 공간 + 1번의 선택지가 2번 화면에 보임")**:
  ① 패·무대의 `onlyFor` 가 hideEntity(스폰 시점 스냅숏)라 **늦게 승선한 눈은 못 가렸다** →
  `setVisibleByDefault(false)+showEntity(주인)` (MobDisplay·SkillDisplay 와 같은 문법)
  ② 몸 자체는 아무도 안 가렸다 → `Voyage.veil/unveil` 신설 — 승선 시 같은 바다의 넋 상호
  hidePlayer + setCollidable(false)(닻이 한 점이라 겹친 몸이 밀지 않게), 하선 시 전부 복원
  (hidePlayer 는 월드를 건너 남는다 — 안 걷으면 강호에 투명 인간).
- 검증: 빌드 ✓ · antechamber_audit(+selftest)·lint 0 · 재배포 ✓ (MVT Done 29.4s · ERROR 0 ·
  봇 JDA 로그인 ✓). ★유의: 이미 그려진 장(시트에 못 박힌 붓 글)은 소급 안 됨 — 지문이
  캐릭터:장면:등급뿐이라 다음 장부터/새 판부터 등록부 문장이다. 남은 사람 눈: 2인 동시
  승선 실측(격리 체감) + 세가 1장 등록부 빨간펜 3건 (그림자_시험 전제 vs 노잣길 물목·가전
  검형 패 충돌 · 뼈대 「사당에 절을 올렸다」가 패 0번을 선점 — 문장은 사용자 몫).

진행 (2026-07-25 밤 2 · ★세가 1장 빨간펜 「1+3」 — 사용자 확정 2건):
- **확정 ①「1+3으로 진행」**: 1장 패를 발단이 가른다 + 뼈대 선점 제거. **확정 ②**: 「아직
  배운 것도 없는, 배우기 전의 이야기 — 이 서장으로 무엇을 얻는지·왜 하는지 명확해야」.
- 배선: `scenes.세가[0].choices_by_incident` 신설 (그림자_시험 = 사당 절·어둠 훑기·빈손
  셈하기 【제안】 · 밀서 = 사당 절·뒷문 길·봉인 눈에 새기기 【제안】 · 공용 폴백 = 비무행 —
  「가전 검형 다잡기」도 배우기 전의 몸에 맞게 「마당의 목검을 쥐어 본다」로, 「노잣길
  물목」은 「비무첩 이름 눈에 담기」로 개정). `Seojang.scenesOf(incident)` 가 패를 갈아
  끼운다 — **모든 소비처(붓 사실·판정·다리 명패)가 한 해석기**. 뼈대는 「사당 앞에 섰다」
  로 절 직전 정지 + 「아직 아무것도 배우지 못한 몸」 명시 + 마지막 물음(「문이 열리기 전,
  마지막으로 무엇을 하는가」)이 패에 넘긴다. 판돈 고지 = 무대 hint 개정 («지금 무엇을
  하는가가 이 길의 다음 장과 강호에서의 첫 밤을 정한다» — 선택=판정→이음새·에필로그 온도
  라는 기계의 진실 그대로 · 수치 없음).
- 눈: antechamber_audit ②-d 신설 (발단 실재·제 벌·stat 실재·수 일치·봇 판독·★세가 1장
  전용 패 실존 계약) + 뮤테이션 (139)(140) — **눈의 시험 117/117** · lint 0 · 재배포 ✓
  (MVT Done 29.0s · ERROR 0 · 봇 JDA ✓).
- 미결(사용자에게): 서장의 「얻는 것」을 기계적으로도 키울지 — 지금은 판정 등급이 다음 장
  이음새·에필로그(첫 밤)만 정한다. 선택 stat 에 영구 보정(예: 시작 능력치 씨앗)을 줄지는
  성장 v3 저울과 얽혀 별도 결정 회차 감.

진행 (2026-07-25 밤 3 · ★A안 「발단의 실」 + 세가 4발단 「견문」 — 사용자 확정 2건):
- **확정 ① A안**: 「모든 시작 캐릭터의 서장이 다 다른 느낌」 — 2·3장 뼈대에
  `{incident_thread}` 자리 신설, **14발단 × 2문장**(길 위·낯선 고을)의 실이 따라온다
  (재료 = 발단 등록부의 trace·long_hook 산문화 · 전부 【제안】). 이전엔 재난 7발단이 2장부터
  같은 글이었다 — 이제 습격의 아이는 길 위에서 말발굽을 두 번 돌아보고, 역병의 아이는
  기침 소리에 세 걸음 물러선다. 배선: `Seojang.incidentThread` + sceneBody 치환 + 겹빈줄
  여밈(`\n{3,}`→`\n\n`). 세가 3장 공용 마무리도 발단 중립으로 개정 (「명이 그러하니·맡은 일」
  → 「그리 하기로 한 길이니·오늘의 첫걸음」 — 견문에겐 명도 맡은 일도 없다).
- **확정 ② 견문 신설**: 「세가는 하나같이 왜 평범하지 않나 — 얌전히 큰 세가집 자식일 수도」
  → 세가 4발단 `견문` (kind 관례 — 명도 시험도 아닌 관례적 출행 · 넉넉한 노자 · 이름만 두고
  간다 · long_hook 「이 평범한 출행은 언제까지 평범할 것인가」). incident_pool 4종 ·
  전용 1장 패 3문장 · 첫 장 무대(열린 대문·제 손 행장·배웅의 등) 등재 — 전부 【제안】.
- 눈: ②-d required 에 견문 · **②-e 신설** (실 전수 14발단×2가닥 · 지어낸 발단 금지 ·
  4갈래 2·3장 뼈대 자리 · 봇 판독) + 뮤테이션 (141)~(143) — **눈의 시험 120/120** ·
  antechamber·lint 0 · 재배포 ✓ (MVT Done 31.2s · ERROR 0 · 봇 JDA ✓).
- 남은 사람 눈: 발단 갈아 타며 2·3장 결 체감 (특히 견문 = 평범함의 결) + 실 28문장 빨간펜.

진행 (2026-07-24 밤 · **설계 시안 섰다** — seojang_presentation.md §0): 사용자 확정 4건 —
① 회랑 = **삼도천 뱃길 자체** (건너는 동안이 곧 서장 · 도착=출도 · 명계 컨셉과 한 몸)
② 붓 대기 = 배가 느리게 저어간다 (늦으면 안개 앞 정지 + 사공 한 마디 — 침묵 금지)
③ 격리 = 멀리 실루엣만 ④ 무대 3종 = 한 물길 · 정거장 연출만 갈림 (계열별 기억 조형은
디스플레이 엔티티 — 본인에게만 보인다). 책 파이프·권위 경계·조판(SJ-001~004)은 한 줄도
안 바뀐다 — 회랑은 책이 열리는 자리와 사이의 시간만 소유. 갇힘 금지 3함정(스테일 우회·
도강 탈출·재접속=마지막 정거장 배 위) 명문화. 좌표·속도·조형 수치 전부 【제안】.
다음: 시안 승인 → SJ-101(뱃길 조성)~104(도착=출도·실기동).

진행 (2026-07-25 · **SJ-101~104 배선 완료 — 시안 승인 후 구현 회차**): ① 조성 — 원경(실루엣·
이승의 불빛)이 기슭 자리(x88~100·x92)로 이사, 정거장 넋등 문(門) 3곳(x44/60/76 · 물길 ±4 밖
±8) plan ⑤-6 ② `Voyage.java` 신설 — 승선(장벽은 열지 않는다: 의식이 몸을 옮긴다) · 저속
항해(0.5칸/초 — **붓이 느린 것이 전제**: 간격 16칸÷0.5=32초가 붓 ~22초+를 덮어 평소엔 정지
없음, 늦는 날만 정거장 앞 안개 정지 · 기다림 기계는 서책 것 재사용) · 정거장 도착=그 자리에서
책 펼침(SeojangBook.deliver → Voyage.defer — 펼침만 미루고 집필 조각은 통과) · 읽는 동안
정박 · 낙수 재승선 · 재접속 relocate(제 장면 정거장 앞) · 명단 끝(출도 클릭·봇 죽음)=기슭
직행, 닿는 순간 depart ③ 배선 — 승선 세 길(watchGate 직후·명단 지각 시계·종), onJoin 서장
미완 몸=재방문 아님(항해가 끊긴 몸), depart/onQuit/shutdown 하선, 배는 비영속(재기동이
안 되살린다 — 명단이 다시 띄운다). 눈: audit ⑧-3 항해 11종 (기하·기슭=불빛 대조·정거장
수=장면 수 교차 등록부·승선 3문·갇힘 금지) + 뮤테이션 7 신설 · selftest 다중 파일
백업(CFG·SRC·SBK·VOY — 두 개만 되돌리면 영구 감염) — **눈의 시험 69/69** · lint 0 ·
빌드 ✓ · 월드 백업(000350) 후 재생성 · 재배포. 남은 조각: 계열별 기억 조형(디스플레이 —
조형 시안 회차) · 신규 캐릭터 실기동(사람 눈 — "겪는 서장"인가 · 닫는 조건).

진행 (2026-07-25 새벽 · ★2차 개정 — **무대 그릇**, 실기동 피드백 "책을 읽는 시스템이 너무
루즈" → "글이 아닌 **몸으로 역사를 느끼는 형태**" · 확정 3건: 글=한 줄 맥박만 · 선택=물 위
세 등불 우클릭 · 연출=다층 무대): 항해 중의 서장에서 **책이 사라졌다** — 정거장에 닿으면
기억의 무대(계열별 다층 BlockDisplay 애니메이션 솟는다/기운다 + 파티클 + 사건음 — 전부
본인에게만)가 재생되고, 맥박(액션바 짧은 숨)이 방향을 짚고, 등불(Interaction+글판, 에필로그는
따뜻한 등롱 하나=출도)을 우클릭해 고른다. LLM 전문은 기슭 도착 때 **필사본**(읽기 전용 ·
진행용과 다른 표식이라 다리의 회수가 안 거둔다)으로 보존 — 개인 서사는 잃지 않는다.
정본: `config/seojang_stage.yml` (조형·문장·수치 전부 【제안】 — 기본 계열이 다층 파일럿,
수행_파견은 기본판 · 파일럿 빨간펜 뒤 증축) + `SeojangStage.java`. 강등 계약: enabled:false
= 옛 책 그릇 (침묵은 그릇이 아니다). 계열 판별 = 장면 제목 대조 (다리는 계열을 안 싣는다).
눈: audit ⑧-4 무대 9종 (제목 대조·에필로그 무대·침묵 금지·강등 문·재배달 억제·등불 다리·
격리·필사본) + 뮤테이션 5 — **눈의 시험 75/75** · lint 0 · 배포 ✓. 사람 눈 대기: 무대 파일럿
(기본 계열) 체감 — 조형·맥박 문장·등불 자리 빨간펜.

진행 (2026-07-25 04시대 · ★실기동 결함 일괄 — 항해 첫 실사용이 문 여섯을 찾았다):
① **조종권** ("배를 움직이면 파츠가 분리 · 배를 이동해 선택지 클릭") — 바닐라 보트 첫 좌석
= 조종석이라 사람이 노를 저었다 → 사공이 먼저 탄다 (조종석 봉인 · 한 배의 그림 완성)
② 등불 사거리 7→3칸 (정박한 배에서 우클릭이 닿는다) ③ **접합 20초 창** ("이었는데 종을
눌러도 이동 안 됨") — linked 거울(스냅숏 20초)을 서장 명단(2초·접합된 몸만)으로 병렬
④ **"붓이 적고 있다" 잔재** — 무대 그릇은 deliver 를 안 지나 기다림 기계가 안 걷혔다 →
settle 신설 ⑤ ★★**재기동 낙하 질식** ("재접속 하니까 땅에 끼임 그리고 죽어버림") — 나루가
지연 로드라 재기동 직후 재접속한 항해자를 바닐라가 기본 월드 스폰(광장 우물 기둥)에 떨궈
질식사. 3방어: 기동 때 나루 미리 열기(원천 소멸) + onJoin 이 나루 밖 항해자 회수 + ⑥ 리스폰
귀항 ("스폰 위치도 이상함") — 서장 미완의 몸은 죽어도 나루로 (본세계 내리는 자리는 건넌 몸의
것). 눈 6종 + 뮤테이션 (104)~(111) — 눈의 시험 상시 전수. 실측 확인: 캐릭터 「디돈」이
**비무행(세가!) 발단**으로 첫 항해 — 세가 승격·발단 무대 사슬이 실전에서 돌았다.

★★다음 회차 (2026-07-25 아침 · 사용자 실기동 총평 — "이런 걸 전부 해결해야"): 도하 개정의
남은 숙제 셋. ① **이동감 부재** — 정박+암전 도하가 「배를 타고 건넌다」로 안 읽힌다 (암전
사이에 항해의 몸이 없다). 후보: 암전 대신/전에 갑판 셸이 미끄러지는 짧은 가짜 항해 연출 ·
암전 중 물살·노 박자 강화 · 접근 자체 재고 ② **배 형태 빨간펜** — 모델(naru/barge)이 아직
어색 (곡선·비례·갑판 맞물림 — 스샷 기반 요소 수정) ③ **다음 정거장이 보인다** — 간격 16칸이라
다음 문·갑판이 훤히 보여 이질적. 후보: 정거장 간격 확대(뱃길 연장) · 정거장 사이 안개(입자
장막·시야 안개) · 정거장별 격리 공간화. **설계 문답부터** — 셋이 서로 얽힌다 (간격을 늘리면
이동감 연출도 같이 정해진다).

진행 (2026-07-25 05시대 · ★★3차 개정 — **정박 무대 + 암전 도하** · 사용자 확정 "굳이 좌석에
앉아서 갈 필요가 있을까"): **탈것 폐지.** 실기동이 좌석 편법의 값을 다 보여 줬다 (조종권 다툼·
숨긴 보트 노출(투명 플래그를 보트는 클라가 무시 — 실측)·회전 피벗 분해·30초 활강의 루즈함) —
좌석을 버리자 전부 함께 사라졌다. 새 몸: 배는 각 정거장에 **실블록으로 정박**(조성 판 ⑤-7 —
갑판 널 6×3·뱃전 난간·이물 넋등, voyage.moored 등록부)하고 사람은 갑판에 **서서** 무대를
겪고 패를 우클릭한다. 장 사이는 **암전 도하** (DARKNESS 2.5초 + 노 소리 + 다음 갑판에서
눈뜸 — transit 등록부). Voyage 전면 재작성 (보트·선체 추종·속도 전부 묘비 — git 이 판본).
추가 확정: 큰 나룻배+배 위의 선택 패 (패는 갑판 이물 위 — 글자 0.6배·줄폭 90·간격 1.3·스태거).
눈: ⑧-3 재표적 (갑판 조성·도하 연출·침묵 금지·기슭의 문 tick 판) + 탈것 눈 묘비 + 뮤테이션
6 재표적 — **눈의 시험 88/88** · 감사·lint 0 · 재배포 (판 갱신 → 첫 진입 자동 재조성).
사람 눈 대기: 도하 체감 (암전 길이·노 소리·갑판 도착감) + 갑판 위 무대·패.

진행 (2026-07-25 · ★★4차 개정 — **가짜 항해 + 안개 장막** · 도하의 남은 숙제 셋 설계 문답,
사용자 확정 3건): ① **이동감** = 가짜 항해 확정 — 사람도 배도 제자리, **세계가 흐른다**:
물살(CLOUD 결)이 뱃전 양쪽을 뒤로 흘러가고, 노 박자(row_period)가 좌우 번갈아 젓고, 안개가
동쪽에서 마중 나와 흐를수록 짙어진다. 어둠은 눈깜빡임(blink_ticks 12)으로 줄었다 — 그 사이에
몸이 다음 갑판에 옮는다 (transit 등록부 개정: flow_ticks 110 · 전부 【제안】. 묘비: 3차 단발
암전 2.5초 — 암전 사이에 항해의 몸이 없었다) ② **정거장 가림** = 안개 장막 확정 — 조성 변경 0:
정거장 사이 중간점+기슭 앞(자리는 stations_x·shore_x 의 순수 함수)에 **항해자의 눈에만** 입자
안개 기둥이 숨쉰다 (voyage.fog 등록부 신설 — 입자는 벽이 아니라 장막이다 · 눈앞의 것만 피운다)
③ **배 형태** = 아는 문제 셋 선수리 → 수리본 재확인 후 스샷 빨간펜 — 갑판 맞물림(옛 폭 1.65칸
< 갑판 3칸이라 널·난간이 뱃전 밖에 떠 있었다 → 확폭 z −3~19·scale 2.6 으로 **감싼다**) ·
곡선 각짐(이물·고물 2단 굽힘 22.5°+45°) · 비례(전장 ~7.8칸×폭 ~3.6칸 · 뱃머리가 넋등 기둥에
닿는다). 팩 세 정본 동기(소스·pack-http·릴리스 sha1 ee1d4068 — 릴리스 실물 재다운로드 대조).
눈: audit ④ 재표적(flow·blink·row 창 + fog 등록부) + ⑤ 신설(startFlow·fogCurtain 배선) +
뮤테이션 (89)(104) 재표적·(112)~(116) 신설 + ★(109) 표류 수리(「미리 열기」 조각이 자라
시험 자체가 고장 — 이 시험이 잡았다) — **눈의 시험 93/93** · antechamber_audit 위반 0.
사람 눈 대기: 도하 체감(흐름 5.5초·노 박자·눈깜빡임) + 장막 짙기 + 새 배 형태 스샷.

진행 (2026-07-25 낮 · ★★★5차 개정 — **별도 서장 월드 + 건축 나룻배 한 척** · 사용자 재설명
"서장은 나루 월드가 아닌 별도의 서장 월드 (물로 된 필드 위 배) · 건축 디자인으로 조금 큰
나룻배 (반블럭·계단 조화) · 연출로 이동감 · **1장 배에서 2장 배가 보이면 안 됨 — 한 배에
타고 있는 것처럼**" + 확정 3건: 나루 항해 장치 전부 철거 · 서장 월드 = 칠흑+달빛 ·
배 = 중선 13×5): ① **서장 월드 신설** — voyage.world 등록부(혼천_서장 · FLAT 물 층 ·
deep_ocean · 자정 고정 · 몹 0), 기동 미리 열기(1차 방어)+배 조성 ② **건축 나룻배** —
Voyage.buildBarge 결정론 조성(짙은 참나무 몸통·가문비 안칸·반블럭 뱃전 테·계단 이물/고물
곡선·넋등 장대 2 — 좌표의 순수 함수·멱등, voyage.barge 등록부) ③ **Voyage 5차 재작성** —
승선=배 위 텔레포트, 장 전환=가짜 항해(4차 승계)+눈깜빡임+**닻 재정렬만** (이동 없음 —
배는 한 척), 안개 장막→**안개 링**(배 주위 상시 · 빈 수평선 가림), 갇힘 금지·필사본·강등
그대로 ④ **나루 철거** — plan ⑤-6(정거장 문)/⑤-7(정박 갑판) 묘비, 팩 모델 셸 폐지(잔재
걷는 손만)+팩에서 naru/barge 제거(3정본 sha1 763d5f27), 월드 백업(20260725-125146) 후
재생성, 원경=순수 화폭 복귀 ⑤ **튜토리얼 침묵** (실기동 "배 위에서 우클릭 하니까 과제 »
맞는 쪽의 선택 문구" — 진범은 옛 코드가 아니라 **본토 뿌리내림(B-178)이 항해 중에도 세던
것**): TutorialGuide.silenced 중앙 게이트(bump·gesture·mirror)+사이드바 트래커 게이트 —
장부의 「트래커 소음」도 함께 닫힘 ⑥ 3방어 확장 — 서장 월드 재접속=그 자리 재승선, 소문·
혈채 서장 월드 무시. 눈: audit ⑧-3 전면 재표적(서장 월드·나룻배 치수·**묘비 부활 감시**·
승선 4문·튜토리얼 침묵 2눈) + 뮤테이션 (85)(86)(91)(92)(105)(114)(116) 재표적·(117)~(122)
신설 — **눈의 시험 99/99** · 감사·lint·tutorial_audit 0. ★함정 (첫 기동 실증): **월드 키는
ASCII 소문자만** — 「혼천_서장」이 IllegalArgumentException 으로 죽었다 (sea() null 계약이
지켜져 아무도 안 갇혔다) → honcheon_seojang 개명. 배포 ✓ (기동 오류 0 · 재기동 후 RCON
실측: 갑판 y-56 · 몸통·넋등 2·이물/고물 계단·반블럭 테 전부 실물 · time 18000 — 언로드
청크는 if block 이 **침묵**하니 forceload 후 재라). 사람 눈 대기: 신규 캐릭터 실기동
(밤바다 도하 체감·배 형태·패 자리 ahead 1.9 빨간펜 후보·안개 링 짙기).

진행 (2026-07-25 낮 · ★5차 실기동 1호 결함 2건 — "나갔다 왔는데 배에 멈춰서 2장이 다시
시작되지도 않아" · "배에 뱃사공도 없어"): ① **패가 서장 월드를 몰랐다** — SeojangStage 의
선택 패 예약(offerChoices)이 나루-검사(isAntechamber)뿐이라 서장 월드의 몸에게 패가 영영
안 걸렸다 (무대 조형·맥박은 나오고 **선택만 불가** = 갇힘. 진단 실측: 배 곁 block_display 3 ·
text_display/interaction 0 — 조형은 섰는데 패가 없다) → 나루·바다 공통 게이트. **계율: 월드를
옮기는 개정은 그 월드를 검사하는 모든 게이트를 같은 커밋에서 재라** (5차가 Voyage 는 고치고
무대의 세계 검사를 놓쳤다) ② **사공 부재** — 2차 확정(고물의 이름 없는 삿대꾼)이 5차 건축
배에서 누락 → voyage.ferryman 등록부(명패 「사공」【제안】) + ensureFerryman(빌리저 · AI 0 ·
침묵 · 한 배에 한 사공 — 기동·승선 양쪽 ensure). 부수 확인: 재생성 후 나루 글판 **2/2**
(B-184 의 1/2 는 옛 월드 잔재였을 가능성 — 다음 기동들에서 재관측). 눈: ⑧-3 패 세계 눈 +
사공 눈 2 · 뮤테이션 (123)~(125) — **눈의 시험 102/102** · 감사 0 · 재배포.

진행 (2026-07-25 낮 · ★실기동 빨간펜 2회차 — 사용자 확정 3건: "선택지만 뜨니까 무슨
내용인지 모르겠음"→**서사 글판** · "명패처럼 디자인"→**명패형+먹 테** · "뛰어내리면 못
올라옴"→**사다리+삿대**): ① 서사 글판 — 장면 전문(LLM)이 패 위 글판(TextDisplay·먹
배경·본인에게만)으로 무대와 함께 선다. 맥박은 분위기로 존치, 필사본 그대로
(seojang_stage narration_panel 등록부 【제안】) ② 패 3.0 — 판목 BlockDisplay 묘비,
글자 명패만(label_format "[ {label} ]"·0.8배·한 줄·얇은 먹 0x78) — 에필로그 등롱은
존치. ★함께 잡은 결함: lanterns ahead 1.9·spread 1.3 이 **int 파서로 1 로 잘려** 확정
간격이 세계에 안 닿고 있었다 (dbl 파서로 수리) ③ 뱃전 허리 양옆 **사다리**(waterlogged ·
barge 순수 함수) + **사공의 삿대**(rescue 등록부 — 깊이 −1.5 또는 18칸 밖 표류 = 갑판
회수 · 나루 물안개와 같은 되돌림 문법). 눈: ⑧-3 서사 글판·명패형(판목 부활 감시)·
사다리·삿대 4눈 + 뮤테이션 (126)~(129) — **눈의 시험 106/106** · 감사·lint 0 · 재배포.

진행 (2026-07-25 오후 · ★빨간펜 3회차 — "서사 글판이 너무 난잡" → **한월풍 대화 채팅 +
타자기 자동** 확정 · "리소스팩이나 UI개선으로 확 와닿게" → **기억첩 글리프 배선**):
① 전문 글판 묘비 — 배 위 판은 「장 제목 표지」로 강등, 서사는 채팅 대화 형식으로: 장식
틀(머리선·장 제목·마감선) + LLM 전문이 **문장 단위 타자기**(55틱 【제안】·max_beats 8 로
총 ~22초 상한·책장 소리)로 흐른다. 놓쳐도 스크롤에 남는다. **패는 마지막 문장 뒤에**
걸린다 (읽기 전에 안 걸린다 — lanternAt = max(맥박, 대화)) ② ★SJ-002 의 미결 배선 완결 —
기억첩 글리프 4장(E0B0 붓선·E0B1 붓점·E0B2 찍힌 인장·E0B3 빈 인장 · 2026-07-16 에 굽고
Java 배선만 남아 있었다)을 등록 용도 그대로: 붓선+붓점=대화 틀 · 빈 인장=명패(클릭 전
선택 표식) · 찍힌 인장=pick_line(선택 확정). 팩 재배포 불요 (글리프는 이미 라이브 팩에)
③ F26 이행 — config·감사 전부 \\uXXXX 이스케이프 (PUA 리터럴 0 자기검증). 눈: 대화
타자기 눈·글리프 배선 눈 + 뮤테이션 (126)(127) 재표적·(130) 신설 — **눈의 시험 107/107** ·
감사·lint 0 · 재배포. 사람 눈 대기: 대화 틀 체감(붓선 폭·타자기 속도·명패 인장).

진행 (2026-07-25 오후 · ★빨간펜 4회차 — 실기동 스샷 2건): ① **"명패가 없고"** — 패는
있었으나 긴 문장 라벨 셋이 가로 1.3칸 간격에 겹쳐 **한 줄로 뭉개져** 있었다 (스샷 실증) →
**세로 목록 4.0**: 명패가 세로 열로 쌓인다 (row_gap 0.55 【제안】 · 첫 선택이 맨 위 · 손
폭 2.6×높이 0.45 로 줄끼리 안 겹침 · 가로 spread 는 묘비) ② **"영어가 사용되어 글이
이상"** — 전문에 「길을 건넌 days」 (로컬 붓이 규칙 5(로마자 금지)를 어김 · 프롬프트만으론
부족) → **붓의 로마자 안전망** (LlmRenderer.render: hasLatin 감지 → 날 선 당부로 재집필
1회 → 그래도면 로마자만 세척 · 경고 로그). 눈: 세로 열 눈(가로 부활 감시)·안전망 눈(살아
있는 게이트 조각 겨눔 — 첫 눈은 문자열 존재만 봐서 뮤테이션에 뚫렸다, 재조준) + 뮤테이션
(131)(132) — **눈의 시험 109/109** · 감사·lint 0 · 봇 재기동(토큰 선확보 절차 6회째 ·
JDA ✓) + MVT 재배포.

진행 (2026-07-25 오후 · ★빨간펜 5회차 — "3장의 내용과 선택지가 잘 연결되는지 모르겠어요"):
실측 대조로 확진 — 3장 전문(붓)은 「문 앞·숨소리·다친 자제」의 제 갈림을 지어냈고 선택지
(등록부)는 「객잔 방·저잣거리 소문·첫 매듭」(낯선 고을 도착)의 것 — **딴 장을 살았다.**
진범: sceneFacts 가 제목·인물·직전 판정·기준 서사만 싣고 **이 장의 갈림(선택지)을 붓에게
안 알렸다** (규칙 6 「갈림 직전에 멈춰라」만 있고 무슨 갈림인지 몰랐다) → 갈림길 세 문장을
사실에 싣는다 ("서사는 이 갈림이 자연스러운 상황에서 멈춘다 — 본문에 옮겨 적거나 나열은
금지"). 눈: 갈림길 눈 + 뮤테이션 (133) — **눈의 시험 110/110** · 봇 재기동 (JDA ✓).
주의: 이미 그려진 장(지문 캐시)은 소급 안 됨 — 새로 그려지는 장부터 듣는다.

진행 (2026-07-25 오후 · ★빨간펜 6회차 — 첫 완주 직후 실기동: "바로 청하현으로 가버렸고
뭘 해야할지 잘 모르겠어" · "죽어버리고 리스폰 했는데 이상한 곳으로 이동되었어" + 로그의
곽진 질식 루프): ① **첫걸음 안내** — 출도 착지 때 트래커 읽는 법+첫 정거장(섭구)을 한 번
말한다 (tutorial.yml arrival_lines 【제안】 · 첫 정거장을 이미 뗀 몸에겐 침묵 · depart 배선)
② **리스폰 = 내리는 자리** — 침대·앵커 없는 건넌 몸의 리스폰이 바닐라 월드 스폰(아무도
고르지 않은 자리)에 떨어졌다 → destination() 한 벌(집안 앵커→destinations→Standing) 재사용
【제안】 ③ **곽진 벽 질식 루프** — 표국 마당 고정 오프셋(+3.5,1,+3.5)이 벽 속이 돼 10초마다
질식사·재소환 (로그 도배·npc_logic 예산 초과) → partnerUpkeep 이 Standing 으로 재고 세운다
(둘레 8칸에도 못 서면 안 세우고 SEVERE — 침묵 금지). 부검: 사망 원인은 반달곰(정예) —
안내 부재가 위험 지대 배회를 낳았다 (①이 그 뿌리). 눈: 세 손 눈 3종 + 뮤테이션 (134)~(136)
+ ★(120) 문턱 표류 재조정(silenced 4문 — 승선 4문과 같은 병) — **눈의 시험 113/113** ·
감사·tutorial_audit 0 · 재배포.

진행 (2026-07-25 오후 · ★빨간펜 7회차 — "수련 0.2일치가 흩어졌다 문구는 왜 뜬거지?"):
진범 = settleTraining (봇 세계일이 바뀌면 접합 몸 전원에게 오는 일일 수련 정산)이 **항해
중인 몸에게도** 와서 무대 대화 한가운데 찍혔다 (범인 천장이 낮아 하루 1.0 중 0.2 넘침 —
문구 자체는 설계된 「배분 바꿔라」의 말). 수리: **서장의 몸에는 강호의 하루가 흐르지 않는다**
— 명단·나루·서장 월드면 날을 안 굴리고 미룬다 (내린 뒤 첫 정산이 같은 몫 — 장부 손실 0 ·
자리만 옮김). 눈+뮤테이션 (137) — **눈의 시험 114/114** · 재배포. ★후속 확정 (같은 날 — "기본 배분
수치를 조정: 범인은 무공을 배우지 않았기에 오르는 것도 없음"): **범인은 갈래 수련을 안
돈다** — 하루는 통째로 기초 단련(pendTrain→화후_원장 · 삼류 관문 걸음은 그대로),
「흩어졌다」는 무공을 이고 난 뒤의 말이 됐다. 눈+뮤테이션 (138) — **눈의 시험 115/115** ·
growth_v3_backfill_selftest 0 · 재배포.

진행 (2026-07-25 새벽 · ★조립 나룻배 — 실기동 "마크 보트라 입도진에서 배를 타는 것 치곤
뱃사공도 없고 뭔가 이상함 (건축 배로 움직이게 표현은 안 될까?)"): 바닐라 보트는 **투명한
좌석**(물리·앉음새 전용)으로 물러나고, 눈에 보이는 배는 등록부(`voyage.barge` — 밑판·뱃전·
이물·고물·뱃머리 넋등·삿대·앉을 널 8부품 【제안】)의 **조립 나룻배**(BlockDisplay ·
teleport_duration 보간으로 좌석을 따라 미끄러진다). **고물에 사공이 실제로 탄다** (빌리저 ·
명패 「사공」 조용히 — 섭구는 부두의 안내인으로 남고 삼도천의 삿대꾼은 이름이 없다 【제안】).
배는 모두에게 보인다 (실루엣 확정과 부합). 눈 ⑥ 4종(등록부 실물·좌석 투명·추종·사공 몸) +
뮤테이션 (104)~(106) — **눈의 시험 84/84** · 감사 0 · 재배포.
