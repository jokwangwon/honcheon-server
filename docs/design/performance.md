# 성능 — 계측·틱 슬라이싱·부하 시험

> 기준 문서: `docs/design/performance_and_netcode.md` (예측과 방어책 F-목록)
> 기계 판독 예산: `config/performance.yml` — **정본**
> 코드: `server-mvt/.../Metrics.java` · `server-mvt/.../TickBudget.java`
> 검산: `tools/perf_audit.py` · 계측: `scripts/perf_probe.sh`

이 문서는 **추측을 숫자로 바꾸는 절차**다. 서버는 한 번도 계측된 적이 없었다.
부채는 알고 있었지만 (조성기의 한 틱 폭탄 · 매 틱 도는 여섯), 아무도 재지 않았다.

---

## 0. 먼저 알아야 할 것 — spark 는 이미 있다

설치할 것이 없다. **Paper 1.21 이 spark 를 번들로 싣는다**, 그리고 **배경 프로파일러가 이미 돌고 있다**:

```
[22:32:28] [spark] This server bundles the spark profiler.
[22:32:33] [spark] Starting background profiler...      ← run/mvt/plugins/spark/config.json
```

즉 지난 모든 기동의 프로파일이 이미 쌓여 있었다. **아무도 안 봤을 뿐이다.**
`/spark profiler open` 한 줄이면 지금 당장 지난 랙의 스택이 나온다.

---

## 1. 계측 방식 — spark 와 자체 계측은 **다른 질문에 답한다** (그래서 둘 다 쓴다)

|  | **spark** (번들) | **`Metrics.java`** (자체) |
|---|---|---|
| 답하는 질문 | **"왜 느린가"** — 어느 메서드가 CPU 를 먹는가 | **"규약을 어겼는가"** — 어느 티커가 제 예산을 넘겼는가 |
| 방식 | 샘플링 프로파일러 (스택 트리 · 통계적) | 결정론적 계수 (`nanoTime` 실측 · 전수) |
| 예산 개념 | **없다** — spark 는 `performance.yml` 을 모른다 | **등록제** — `performance.yml` 이 정본 |
| 강점 | 범인을 찾는다. 이름도 모르는 병목을 잡는다 | 재발을 막는다. 상시 경보로 회귀를 즉시 짖는다 |
| 약점 | 상시 경보가 안 된다. 사람이 봐야 안다 | 이미 아는 티커만 잰다. 모르는 병목은 못 본다 |
| 값 | 배경 프로파일러 ~1% | 티커당 ~50ns (nanoTime 2회 + 맵 조회) |

**판단: 둘 다.** 배타적이지 않고 **상보적**이다.

* **spark 로 찾는다.** "조성이 서버를 30초 세운다"는 알지만 *그 30초의 어디가* `setType` 이고
  어디가 조명 재계산이고 어디가 청크 생성인지는 모른다. 그건 스택 트리만 안다.
* **`Metrics` 로 지킨다.** spark 는 `npc_logic 6ms` 라는 우리 규약을 모른다.
  "MobDisplay 가 오늘 7.2ms 를 먹었다"고 짖어 줄 자는 우리가 만들어야 한다.
  `performance.yml` 에 예산을 적어 놓고 재는 자를 안 두면 **그것은 예산이 아니라 주석이다**
  — 그리고 실제로 그랬다 (§4 검산 결과: 예산 항목의 절반 이상이 죽어 있었다).

> **한쪽만 골라야 한다면 spark 다.** 공짜고, 이미 켜져 있고, 아무 배선도 필요 없다.
> `Metrics` 는 배선(§5)이 필요하다. 하지만 그 배선은 티커당 한 줄이고, 그 한 줄이
> "예산이 지켜지는가"라는 질문에 **처음으로** 답하게 해 준다.

### 1.1 내가 RCON 으로 돌릴 명령 — spark

`scripts/perf_probe.sh` 가 이걸 다 감싼다. 손으로 치려면:

```bash
# ─── 지금 상태 (제일 먼저 이것부터) ───
spark tps                       # TPS 5s/10s/1m/5m/15m + MSPT 백분위수 (min/median/95%ile/max)
spark healthreport              # TPS·MSPT·CPU·힙·디스크 한 장 요약
spark ping                      # 접속자 핑 분포 (플레이어 있을 때)

# ─── 프로파일 (범인 찾기) ───
spark profiler open             # ★ 배경 프로파일러가 이미 켜져 있다 — 지난 랙이 여기 다 있다
spark profiler start --timeout 60                      # 60초 뜨고 자동 종료 → 웹 링크
spark profiler start --timeout 120 --thread *          # 모든 스레드 (비동기 청크 생성까지 보인다)
spark profiler start --only-ticks-over 40              # ★ 40ms 넘긴 틱만 — 스파이크 전용
spark profiler start --timeout 180 --thread * --only-ticks-over 40   # 조성 관측용
spark profiler stop             # 즉시 종료 → 링크
spark profiler cancel

# ─── 메모리 (디스플레이 엔티티 누수 의심 시) ───
spark heapsummary               # 클래스별 인스턴스 수 — ItemDisplay 가 몇 개인지 여기서 본다
spark gc                        # GC 통계

# ─── 자체 계측 ───
혼천 계측                        # 티커별 틱평균/틱최대/예산초과 틱수 (Metrics.java)
혼천 계측 초기화                  # 계기를 0으로 (시험 시작 전에)
```

> `--only-ticks-over 40` 이 핵심이다. `server_target_mspt: 40` 이 우리 목표선이므로,
> **목표선을 넘긴 틱만** 프로파일하면 평시 소음이 빠지고 스파이크의 스택만 남는다.

---

## 2. 틱 슬라이싱 — 한 틱 폭탄의 해체 (`TickBudget.java`)

### 2.1 병(病)

```
청하현 조성 = 90만 회 동기 블록 호출 · 한 틱에.
블록 하나 = 5~20µs (조명 재계산 · 청크 팔레트 · 이웃 갱신 · 패킷).
90만 × 10µs = 9초.  그 9초 동안 메인 스레드는 게임을 돌리지 않는다.
워치독이 깨어나 스레드 덤프를 뜬다 (실제로 봤다).
```

**총량은 못 깎는다.** 마을을 지으려면 블록을 놓아야 한다.
깎을 수 있는 건 **한 틱에 얼마나 하느냐**다. 조성은 수십 초가 걸려도 좋다 — **서버가 멈추지만 않으면.**

### 2.2 이미 쓴 처방과, 그것이 안 통하는 이유

`MvtCommand.SeedProbe` · `SiteProbe` 가 이미 20ms/틱 슬라이스를 쓴다.
하지만 그 둘은 **자기가 짠 루프**였다 — 후보를 하나씩 꺼내 쓰다가 예산이 다하면 손을 뗐다.

조성기는 다르다. `CheonghaBuilder.build()` 는 **7,600줄짜리 남의 함수**이고,
그 안에 재개 지점이 없다. `roads()` 중간에서 멈췄다가 다음 틱에 이어서 시작할 방법이 —
**함수를 고치지 않고서는** — 없다. 그리고 조성기 3종은 다른 작업자 소유다.

### 2.3 처방 — **스레드를 재개 지점으로 쓴다**

조성 본문을 **워커 스레드**에서 돌린다. 단, 그 스레드에 **진짜 월드를 주지 않는다.**
`org.bukkit.World` 는 **인터페이스**다 — 대역(代役)을 씌울 수 있다.

```
워커 스레드                      큐 (FIFO)                메인 스레드 (매 틱)
─────────────                  ──────────               ──────────────────
build(대역월드)                                          drain():
  setType(...)   ── 적는다 ──▶  [op][op][op]...           예산(20ms)만큼 집행
  setType(...)   ── 적는다 ──▶  [op][op]                  → 진짜 world.setType()
  getType()      ── 장벽 ────▶  [.......] 다 비우고        예산 다하면 손 뗀다
                 ◀── 값 ─────   답을 돌려준다              → 서버는 계속 돈다
```

* **쓰기(void 반환)** — 큐에 순서대로 적고 **즉시 돌아간다**. 워커는 안 멈춘다.
* **읽기(값 반환)** — 큐가 비워지기를 **기다렸다가**(장벽) 메인에 왕복해 값을 받는다.

조성기의 호출 분포가 이 설계를 정당화한다 (`CheonghaBuilder` 실측):

| 호출 | 횟수 | 처리 |
|---|---|---|
| `setType` / `setBlockData` | **531 지점 · 90만 회** | 큐에 적고 즉시 반환 (핸드오프 0) |
| `getType` | 67 지점 | 장벽 + 왕복 |
| `getHighestBlockYAt` | 12 지점 | 장벽 + 왕복 |
| `getName` / `getMin·MaxHeight` | 22 지점 | **미리 떠 둔다** (왕복 없음) |
| `spawnEntity` / `setBiome` / `getState` | 5 지점 | 장벽 + 왕복 |

**뜨거운 경로(90만 회)에는 스레드 핸드오프가 한 번도 없다.** 왕복은 100번 남짓뿐이다.

### 2.4 결정론 — 무엇으로 지키는가

> 규약: 최적화가 결정론·정합성을 깨면 안 된다 (좌표 해시 · 검수 12/6/5종).

이 장치는 **순서를 한 톨도 바꾸지 않는다.**

1. **큐가 FIFO 다.** 조성기가 부른 순서 그대로 집행된다.
2. **읽기는 장벽이다.** 값을 읽는 호출은 **앞선 쓰기가 전부 집행된 뒤에야** 답을 받는다
   (read-your-writes). 즉 조성기가 보는 세계는 **동기 실행일 때와 같은 순서로 같은 상태**다.
3. **좌표 해시는 손대지 않는다.** `noise(x,z)` · `hash(x,z,n)` · `lift(dx,dz)` 는 순수 함수이고,
   워커 스레드에서 그대로 돈다. 같은 좌표 → 같은 값.
4. **한 번에 조성 하나** (`BUSY` 플래그). 두 조성이 큐를 나눠 쓰면 순서가 섞인다.

**나뉜 것은 시간뿐이다.** 산출물은 비트 단위로 같아야 한다.

> **검산 방법 (배선 후 반드시)**: 같은 좌표에 배선 전/후로 조성하고 `/혼천 검수` ·
> `/혼천 환경검수` · `/혼천 지역검수` 의 **12/6/5종 수치가 한 톨도 안 바뀌는지** 본다.
> 바뀌면 배선이 틀린 것이다 (슬라이싱 자체가 아니라).

> ⚠ **`tick_slicing.apply_physics: false` 로 내리면 이 보장이 깨진다.**
> 2~4배 빨라지지만 물이 안 흐르고 모래가 안 떨어진다 — **결과가 달라질 수 있다.**
> 결정론(순서 불변)은 유지되나 산출물이 바뀌므로, 내리려면 검수를 다시 통과시켜라.
> **기본은 `true` 다** (지금 동작과 동일).

### 2.5 API

```java
// 조성 슬라이서 — 남의 함수를 통째로 나눠 먹인다
TickBudget.build(plugin, 이름, world,
                 w -> 원래호출(w, ...),   // w = 대역 월드. 진짜 world 를 캡처하면 안 된다.
                 결과 -> { ... },          // 성공 (메인 스레드)
                 에러 -> { ... },          // 실패 (메인 스레드)
                 sender::sendMessage);     // 진행 보고 (null 허용)

// 조성 전에 땅을 비동기로 싣고, 조성이 끝날 때까지 티켓으로 붙잡는다
TickBudget.preload(plugin, world, cx, cz, 반경) → CompletableFuture<Void>

// 대역 월드를 문 Location 을 진짜 월드에 다시 묶는다 (앵커 저장 전 필수)
TickBudget.rebind(anchors, world)

// 범용 슬라이서 — 내가 소유한 루프용 (검수기의 대량 스캔 등)
TickBudget.slice(plugin, 이름, () -> { ...한 걸음...; return 더있음; }, onDone)
```

**주의 3가지** (배선할 때):

1. 본문 람다 안에서 **`sender.sendMessage` · `plugin.getLogger()` 외의 Bukkit API 를 직접 부르지 마라.**
   본문은 워커 스레드에서 돈다. 대역 월드를 통하는 호출만 안전하다.
   메시지는 `onDone` 에서 보내라 (§5 배선 코드가 그렇게 짜여 있다).
2. **앵커는 `rebind` 해야 한다.** 조성기가 만든 `Location` 은 대역 월드를 물고 있다.
   (`Zone` 은 월드 **이름(String)** 만 담으므로 손댈 것이 없다.)
3. **`preload` 를 빼먹지 마라.** 슬라이스 조성은 수십 초 걸린다. 그 사이 청크가 언로드되면
   다음 슬라이스가 그 청크를 **동기 재생성**하며 다시 서버를 세운다 — 해체한 폭탄이 되살아난다.

---

## 3. 부하 시험 — `performance.yml` `load_test`

목표: `mspt_under: 40` · `particle_over_budget_rate_under: 0.05`
군집 20인 · 초당 시전 30 · 동시 60인.

### 3.1 플레이어 없이 되는 것 / 안 되는 것

**콘솔(RCON)만으로 서는 층:**
· 조성 (`CheonghaBuilder`·`RemoteBuilder`·`TerrainForge`) — 가장 큰 부채
· `Incidents` (1초 티커) · `HuntingGrounds` 스포너 · `WorldBridge` 큐
· `SkillListener`·`SkillCast` 의 **중앙 티커 자체** (빈 목록을 도는 값)

**플레이어가 있어야만 서는 층 — 콘솔로 못 만든다:**
· `Populace` — `activate_radius: 56` 안에 **사람이 있어야** 무명이 몸을 세운다.
  사람이 없으면 28인이 전부 잠들어 있다 → 콘솔 시험은 이 층을 **0으로 재고 지나간다**.
· `MobDisplay` — `attach_scan_range: 48` (사람 곁에서만 형체를 붙인다) + **리소스팩 게이트**.
  팩을 받은 클라이언트가 없으면 형체가 아예 안 선다.
· `SkillHud` / 파티클 LOD — **시야당** 예산(600/틱)은 '보는 눈'이 있어야 재진다.

> 즉 **`/혼천 계측` 이 콘솔에서 뱉는 숫자는 하한이다.** 진짜 값은 사람이 서 있어야 나온다.
> 시나리오 ③④⑤ 는 관리자 1인(Lindydone)이 연무장에 서서 돌린다.

### 3.2 시나리오

| # | id | 사람 | 무엇을 재는가 | 어떻게 |
|---|---|---|---|---|
| ① | `idle` | 불필요 | **무부하 기준선** — 매 틱 도는 여섯이 *가만히 있을 때* 먹는 값. 이걸 모르면 나머지 숫자가 뜻이 없다 | `scripts/perf_probe.sh baseline 60` |
| ② | `build_town` | 불필요 | **조성 폭탄** — 슬라이싱 배선 **전/후** 대조 | `scripts/perf_probe.sh build` |
| ③ | `mob_swarm` | **필요** | 몹 밀도 — 허수아비 20 + 몹 20 (`combat_cluster_size: 20`) | 아래 |
| ④ | `cast_storm` | **필요** | 무공 난사 — `casts_per_second: 30` 을 향해 | 아래 |
| ⑤ | `full` | **필요** | **합산** — 조성 없이 전부 함께. **이게 진짜 답이다** | ③+④ 동시 |

### 3.3 ③④⑤ 를 돌리는 법 (관리자 1인)

```bash
# 터미널: 관측을 켠다 (계기 초기화 + spark 40ms 초과 틱만)
scripts/perf_probe.sh watch 120
```
```
# 인게임 (관측이 도는 120초 동안):
/혼천 연무장                    ← 별도 월드 (세계에 자국을 안 남긴다)
/혼천 허수아비 20               ← 맞아 주는 몸 20 (피해 계측용)
/혼천 소환 <몹> ... × 20        ← 몹 20 (MobDisplay 가 형체를 붙인다 — 3D 층이 여기서 선다)
/혼천 시험 경지 <높게> 내력 <가득>  ← 쿨다운·내력이 난사를 막지 않게
... 무공을 초당 30회를 향해 난사 ...
```
120초 뒤 터미널에 `spark tps` + `/혼천 계측` 표가 뜬다.

**합격 판정:**
* `spark tps` 의 **MSPT 95%ile < 40ms** (평균이 아니라 95%ile 을 봐라 — 랙은 꼬리에 산다)
* `/혼천 계측` 의 **초과틱** 열이 모든 티커에서 0 에 가까울 것
* 초과가 있으면 → `spark profiler open` 으로 그 틱의 스택을 본다

> **⑤(합산)를 반드시 돌려라.** ③④ 를 따로 통과해도 함께 돌 때 터질 수 있다 —
> 그게 바로 "함께 돌 때 무슨 일이 나는지 아무도 모른다"의 뜻이다.

---

## 4. 검산 — `tools/perf_audit.py` 가 찾아낸 것

```bash
python3 tools/perf_audit.py            # 표
python3 tools/perf_audit.py --json     # 기계 판독
python3 tools/perf_audit.py --strict   # 위반 있으면 exit 1 (CI)
```

### ① 등록제 위반 — **예산의 절반 이상이 죽어 있었다**

배선 전 기준, `performance.yml` 을 **여는 코드가 하나도 없었다.**
예산 항목 전부가 주석이었고, 코드는 제 수치를 따로 박아 두고 살았다.

죽은 예산 (읽는 자 없음):

| 항목 | 값 | 실태 |
|---|---|---|
| `tick_budget.server_target_mspt` | 40 | 아무도 40ms 를 목표로 재지 않았다 |
| `subsystem_budget_ms.effect_ticker` | 5 | " |
| `subsystem_budget_ms.npc_logic` | 6 | `MobDisplay`·`Populace` 가 **주석으로만** 인용 |
| `subsystem_budget_ms.world_batch_apply` | 3 | " |
| `skills.max_range_default` | 24 | **정본이 둘** — `skill_mechanics.yml` 에도 24 가 적혀 있다 |
| `skills.movement_velocity_per_tick` | 1 | 읽는 자 없음 |
| `skills.movement_min_interval_ticks` | 2 | 읽는 자 없음 |
| `netcode.counter_window_grace_ticks` | 2 | 읽는 자 없음 |
| `skills.refund_on_invalid_target` | 0.5 | 읽는 자 없음 |
| `load_test.*` (5항목) | — | 읽는 자 없음 |

**값이 다른 yml 에 또 있다** (정본이 둘 = 언젠가 갈라진다):
* `vfx_entities.global_cap: 120` ↔ `mob_models.yml budget.global_cap: 64` — **같은 이름, 다른 값, 다른 풀.**
  둘은 실제로 다른 것을 세지만(무공 VFX 풀 / 몹 형체 풀) 이름이 같아 헷갈린다.
* `skills.max_range_default: 24` ↔ `skill_mechanics.yml` — 지금은 같다. 언젠가 갈라진다.
* `Populace`·`MobDisplay`·`SkillDisplay` 의 예산은 **각자의 yml** 에서 온다
  (`populace.yml performance` · `mob_models.yml budget` · `skill_motion.yml budget`).
  `performance.yml` 은 그 위에 있다고 **주장하지만** 아무도 그렇게 읽지 않는다.

> **처방:** `Metrics.java` 와 `TickBudget.java` 가 이제 `performance.yml` 을 **실제로 연다**.
> `metrics.probes` 등록부가 "티커 이름 → 예산 항목"을 대고, `subsystem_budget_ms` 가 값을 댄다.
> 나머지 죽은 예산(F-N1/N3 · refund)은 그것을 쓰는 코드(스킬 담당 소유)가 읽어야 살아난다 —
> **내 소유가 아니라 지시서로만 남긴다.**

### ② 매 틱 도는 것 — 티커 9개 · `@EventHandler` 44개

| 티커 | 주기 | 빈도 | 위치 |
|---|---|---|---|
| `SkillCast.tick` — 절기의 삼문(承·間·虛) | **1틱** | 20Hz | `SkillCast.java:190` |
| `SkillListener.tick` — 무공 중앙 티커 + 조식·HUD | **1틱** | 20Hz | `SkillListener.java:98` |
| `MobDisplay.tick` — 3D 몹 형체 추종 | **1틱** (`follow.interval_ticks`) | 20Hz | `MobDisplay.java:330` |
| `HuntingGrounds.tick` — 구역 스포너·전의·비무 | 20틱 | 1Hz | `HuntingGrounds.java:540` |
| `Incidents.tick` — 사건 엔진 | 20틱 | 1Hz | `Incidents.java:350` |
| `Populace.tick` — 무명 28인 일과·배회 | 40틱 | 0.5Hz | `Populace.java:402` |
| 사이드바 갱신 | 100틱 | 0.2Hz | `HoncheonMvt.java:111` |

**매 틱(20Hz) 도는 것이 셋이다** — `SkillCast` · `SkillListener` · `MobDisplay`.
`SkillDisplay` 는 `SkillListener` 안에서 돈다. 이 셋이 `skill_execution 8ms` + `vfx_display 3ms`
안에 들어가는지가 핵심 질문이고, **§5 배선 후 `/혼천 계측` 이 처음으로 답한다.**

리스너가 가장 많은 곳: `SkillListener`(7) · `HuntingGrounds`(6) · `SkillCast`(6) · `MobDisplay`(5).

### ③ 한 틱 폭탄 — 정적으로 잡힌 것

**잡힌다 (상한이 상수라서 셀 수 있다):**

| 자리 | 추정 블록 쓰기 | 정지 시간 (10µs/블록) |
|---|---|---|
| `CheonghaBuilder.build()` (롤업) | **≥ 296,853회** (못 푼 루프 31겹) | **≥ 3.0초** |
| `CheonghaBuilder.sealLaidFloors()` | 171,125회 (반경 92 × 깊이 6 이상) | 1.7초 |
| `CheonghaBuilder.foundationSeal()` | 93,751회 | 0.9초 |
| `CheonghaBuilder.fillCoreWater()` | ≥ 15,625회 | 0.2초 |

> 롤업 296k 는 **하한**이다 (루프 31겹의 경계를 못 풀었다). 실제 90만은 이 위에 있다.

**안 잡힌다 — 정적 검사의 한계:**
`RemoteBuilder.cityWall` · `gateTower` · `rampartStair` · `TerrainForge.carveOasis` · `carveRamp` ·
`CheonghaBuilder.inn` · `pyogukHall` · `shoreBank` … 는 **루프 경계가 런타임 값**이다
(반경·높이가 인자로 온다 — 봉우리 r=60×h=40, 사구 r=40 은 **호출자가 정한다**).

> **"정적 검사로 잡을 수 있는가?"에 대한 정직한 답:**
> **절반만.** 상한이 상수면 센다. 반경이 인자로 오면 **못 센다** —
> 도구는 그것을 `[상한 미상]` 으로 따로 세운다. 그건 "작다"는 뜻이 아니라
> **"코드만 봐선 모른다"**는 뜻이고, 그래서 더 나쁘다.
> **여기서 정적 검사는 끝나고 계측이 시작된다.** 그게 이 문서가 존재하는 이유다.

---

## 5. 배선 — 내가 넣을 코드 (조성기·티커는 한 줄도 안 고친다)

### 5.1 기동 — `HoncheonMvt.onEnable()`

```java
// (기존) Path cfg = configDir.toPath();  바로 뒤
Metrics.load(cfg);      // 예산 등록부 (performance.yml metrics.probes)
TickBudget.load(cfg);   // 틱 슬라이싱 예산 (performance.yml tick_slicing)
Metrics.start(this);    // 계기 쓸기 (안 돈 틱도 마감한다)
```

### 5.2 티커 — `Metrics.wrap` 한 겹 (본문은 안 바뀐다)

```java
// SkillListener.start()
- plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
+ plugin.getServer().getScheduler().runTaskTimer(plugin,
+         Metrics.wrap("skill_execution", this::tick), 1L, 1L);

// SkillCast.start()
+         Metrics.wrap("skill_cast", this::tick), 1L, 1L);

// MobDisplay.start()
+         Metrics.wrap("mob_display", this::tick), 40L, budget.followInterval());

// Populace.start()
+         Metrics.wrap("populace", this::tick), 120L, tickerPeriod);

// Incidents.start()
+         Metrics.wrap("incidents", this::tick), 140L, tickerPeriod);

// HuntingGrounds.start()
+         Metrics.wrap("hunting", this::tick), 100L, 20L);

// HoncheonMvt — 사이드바
+         Metrics.wrap("sidebar", () -> getServer().getOnlinePlayers().forEach(this::updateSidebar)),
          100L, 100L);
```
이름은 `performance.yml` `metrics.probes` 의 키와 **정확히** 같아야 한다 (등록제).
`SkillDisplay` 는 `SkillListener.tick` 안에서 도므로 별도 계기가 필요하면
`SkillDisplay.tick()` 을 `Metrics.timed("skill_display", () -> ...)` 로 감싼다.

### 5.3 `/혼천 계측` — `MvtCommand`

```java
// switch 에 추가
case "계측" -> metrics(sender, args);
```
```java
private boolean metrics(CommandSender sender, String[] args) {
    if (sender instanceof Player p && !p.isOp()) {
        return true;
    }
    if (args.length >= 2 && args[1].equals("초기화")) {
        Metrics.reset();
        sender.sendMessage(ChatColor.GRAY + "계기를 0으로 놓았다.");
        return true;
    }
    if (args.length >= 2 && (args[1].equals("켜기") || args[1].equals("끄기"))) {
        Metrics.enabled(args[1].equals("켜기"));
        sender.sendMessage(ChatColor.GRAY + "계기 " + args[1]);
        return true;
    }
    for (String line : Metrics.report()) {
        sender.sendMessage(ChatColor.GRAY + line);
    }
    return true;
}
```

### 5.4 ★ 조성 — `MvtCommand.buildTown()` (가장 큰 부채)

```java
// ── 이전 (한 틱 폭탄) ──
anchors = CheonghaBuilder.build(world, bx, by, bz, zones);
plugin.setAnchors(anchors);
plugin.setZones(zones);
sender.sendMessage(ChatColor.GOLD + "청하현이 섰다 …");
return true;

// ── 이후 (틱을 나눠 먹는다 — CheonghaBuilder 는 한 줄도 안 고친다) ──
if (TickBudget.busy()) {
    sender.sendMessage(ChatColor.RED + "이미 조성이 돌고 있다.");
    return true;
}
TickBudget.preload(plugin, world, bx, bz, 96).thenRun(() ->
    org.bukkit.Bukkit.getScheduler().runTask(plugin, () ->
        TickBudget.build(plugin, "조성:청하현", world,
            w -> CheonghaBuilder.build(w, bx, by, bz, zones),   // w = 대역 월드
            built -> {                                          // 메인 스레드
                TickBudget.rebind(built, world);                // ★ 앵커를 진짜 월드에 다시 묶는다
                plugin.setAnchors(built);
                plugin.setZones(zones);
                sender.sendMessage(ChatColor.GOLD + "청하현이 섰다 — 장소 " + built.size()
                        + "곳 · 구역 " + zones.size() + "곳 (입장 타이틀)");
            },
            err -> sender.sendMessage(ChatColor.RED + "조성 실패: " + err),
            sender::sendMessage)));
return true;
```
플레이어 조성(`CheonghaBuilder.build(player, zones)`)은 좌표를 먼저 풀어서 같은 형태로:
```java
Location at = player.getLocation();
int bx = at.getBlockX(), by = at.getBlockY() - 1, bz = at.getBlockZ();
// … 위와 동일 …
```
> `build(Player, List<Zone>)` 오버로드는 **쓰지 마라** — 그 안에서 `admin.getWorld()` 로
> **진짜 월드**를 집어 온다 (대역을 우회한다). 좌표 오버로드만 대역을 받는다.

### 5.5 지역 조성 — `MvtCommand.finishRegion()`

`TerrainForge.prepare` → `digCave` → `RemoteBuilder.build` 셋을 **한 세션**으로 묶는다
(셋 다 `world` 를 받으므로 대역 하나로 덮인다):

```java
private record RegionResult(TerrainForge.SiteSpec spec, TerrainForge.CaveSpec cave,
                            java.util.List<Zone> built) { }

private void finishRegion(CommandSender sender, World world, WorldMap.Place place, WorldMap.Site site) {
    Integer remembered = plugin.regionBase(place.id());
    int baseY = remembered == null ? site.groundY() : remembered;
    if (remembered == null) {
        plugin.setRegionBase(place.id(), baseY);
    }
    int forgeRadius = "noklim".equals(place.faction()) ? 24 : 110;

    TickBudget.build(plugin, "조성:" + place.id(), world,
        w -> {                                       // ★ 워커 스레드 — Bukkit API 직접 호출 금지
            TerrainForge.SiteSpec spec =
                    TerrainForge.prepare(w, place, site.x(), baseY, site.z(), forgeRadius);
            TerrainForge.CaveKind kind = TerrainForge.caveKind(place);
            TerrainForge.CaveSpec cave = kind == null ? null : TerrainForge.digCave(w, spec, kind);
            return new RegionResult(spec, cave, RemoteBuilder.build(w, place, spec, cave));
        },
        r -> {                                       // ★ 메인 스레드 — 여기서 말한다
            plugin.getLogger().info("[지형] " + r.spec().summary());
            if (r.cave() != null) {
                sender.sendMessage(ChatColor.GRAY + "굴 입구: /tp " + r.cave().mouthX() + " "
                        + r.cave().mouthY() + " " + r.cave().mouthZ());
            }
            if (r.built().isEmpty()) {
                sender.sendMessage(ChatColor.RED + "원형이 없어 아무것도 서지 않았다.");
                return;
            }
            … 기존 구역 병합 코드 그대로 …
        },
        err -> sender.sendMessage(ChatColor.RED + "조성 실패: " + err),
        sender::sendMessage);
}
```
> `preloadThenBuild` 의 기존 비동기 청크 로드는 **그대로 두되**, 반경을 `TickBudget.preload`
> 로 갈아 끼우면 **티켓까지 잡아 준다** (수십 초 조성 중 언로드 방어).
> 기존 `preloadThenBuild` 는 티켓을 안 잡는다 — **이게 슬라이싱과 함께 쓰면 새 버그가 된다.**

### 5.6 `plugin.yml` — 새 하위명령

`/혼천 계측` 은 기존 `honcheon` 명령의 하위이므로 `plugin.yml` 변경이 없다.
`/혼천 도움말` 목록에 한 줄 추가만 하면 된다.

---

## 6. 순서 — 무엇부터 할 것인가

```
1. 아무것도 안 고치고 먼저 잰다        scripts/perf_probe.sh baseline 60
   (spark 는 이미 켜져 있다 — 지난 랙의 프로파일이 이미 있다: spark profiler open)
2. 조성 폭탄을 관측한다 (배선 전)      scripts/perf_probe.sh build      ← 여기서 서버가 멈추는 걸 본다
3. §5.1~5.3 배선 (계측)  → 다시 ①    이제 티커별 숫자가 나온다
4. §5.4~5.5 배선 (슬라이싱) → 다시 ②  이제 안 멈춘다
5. 검수 12/6/5종 재실행               결정론이 안 깨졌음을 증명한다 ← 생략 금지
6. 부하 시험 ⑤ (합산)                 진짜 답
```

**1번을 건너뛰지 마라.** 기준선 없이 개선을 말할 수 없다.
