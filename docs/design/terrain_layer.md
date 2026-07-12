# 지형 계층과 건축 계층 (2026-07-12)

사용자 판정:
> "자연동굴 제거 및 필요하면 그때 동굴의 형태로 생성하기 (통제가 힘들기 때문)"
> "별개의 시스템이 필요하다 — 1. 지형 생성 에이전트(지도에 맞게 지형을 만드는) 2. 건물·마을 생성 에이전트
>  (지형에 맞게 건축·기반을 올리는). 두 개로 역할 분담하고 **지도를 통해 관리**되게."

## 왜 갈라야 하는가
지금까지 조성기 하나가 **땅도 빚고 집도 지었다.** 그래서 두 일이 서로를 밟았다:
* 집을 지으려고 땅을 깎다가 바닐라 동굴을 열었다 (마을이 껍데기 위에 섰다)
* 땅을 빚다가 수역을 토막 냈다 (산 위의 웅덩이)
* 부지 경계에서 지형이 뚝 끊겼다 (마을이 섬이 됐다)
땅과 집은 관심사가 다르다. **땅은 자연과 협상하고, 집은 사람과 협상한다.**

## 계약 (지도가 관리한다)
`config/world_map.yml` 의 등록 항목이 **주문서**다: 좌표·지형(평지/산/험산/강/분지)·규모·세력·등급.

```
지도(world_map.yml)  ──주문──▶  지형 계층(TerrainForge)  ──부지 사양(SiteSpec)──▶  건축 계층(Builder)
```

### 지형 계층이 보장하는 것 (SiteSpec)
1. **딛는 땅** — 조성 지면 아래 6칸은 단단하다 (자연 동굴을 뚫고 짓지 않는다)
2. **자연 동굴 없음** — 세계는 `worldgen/honcheon_no_caves` 데이터팩으로 생성된다.
   지하 공동 **10.08% → 1.06%** (환경 검수 ⑥으로 실측). 동굴이 필요하면 **우리가 판다**
   (기연 동굴·마교 은신처·산적 굴 — 등록부가 요구할 때, 형태를 우리가 정해서).
3. **경계가 자연으로 이어진다** — 전이대(페더링). 바깥에서 걸어 들어올 수 있다.
4. **수역 정직** — 물 위에 짓지 않는다. 물가면 물가라고 말하고(나루·다리), 웅덩이는 남기지 않는다.
5. **지형이 주문과 맞다** — 험산을 주문했는데 사막이면 봉우리를 세운다(실지리 1:1 좌표는 못 옮긴다).

### 건축 계층이 지키는 것
* **땅을 건드리지 않는다.** 부지 사양이 준 지면 위에만 세운다.
* 지형이 준 진입 방향·수변·경사에 맞춰 배치한다 (산채는 한 길, 성시는 사방).
* 자재·소품·창호·수묵 규약은 전부 건축 계층의 것이다 (마을 검수 12종).

## 검산 (눈이 계약을 지킨다)
| 검수 | 무엇을 | 누구의 책임 |
|---|---|---|
| 환경 검수 (TerrainAudit) ①~⑥ | 바닥 공동·수역·경계·연결성·부유·**지하 동굴** | 지형 계층 |
| 마을 검수 (TownAudit) 12종 | 길·처마·물매·소품·수묵·야간·계약 | 건축 계층 |
| 지역 검수 (RegionAudit) 5종 | 도달성·구조 계약·허공·광원·수묵 | 둘 다 (도달성은 지형, 나머지는 건축) |

---

# TerrainForge — 지형 계층의 단일 창구 (2026-07-12 구현)

`server-mvt/src/main/java/com/honcheon/mvt/TerrainForge.java`

**건축 계층은 이 문으로만 땅과 이야기한다.** 땅을 직접 재지 않는다 —
`getHighestBlockYAt` 은 **물을 땅으로 읽고**(폐사당이 호수에 섰다) **옛 지붕을 지면으로 읽는다**
(사막 위 도관이 두 번 섰다). 그 두 버그가 이 계층이 존재하는 이유다.

## 1. 부지 사양 (SiteSpec) — 땅의 답장

| 필드 | 뜻 | 건축 계층이 쓰는 법 |
|---|---|---|
| `groundY` | **조성 기준 지면** | 이 y 위에 세운다. **그 아래 6칸은 이미 단단하다** |
| `peakX/peakZ/peakY` | 봉우리의 자리와 정상 | `twoTier()` 가 참이면 두 켜를 쓴다 (문파: 문전 `groundY` → 계단 → 본전 `peakY`) |
| `buildable[]` | **건축 가능 마스크** | `canBuild(x,z)` — 물·급경사(3칸 초과)·보호구역(동굴 입구)이 빠져 있다 |
| `surface[]` | 각 열의 지면 y | `groundAt(x,z)` — 비탈에 집을 앉힐 때 |
| `approaches` | **진입 방향** | 길이 어디서 오는가. 산은 하나여도 정상, 들은 사방 |
| `waterfront` / `waterSides` | **수변 여부와 물가 방향** | 참이면 **나루·다리·선착장**을 놓는다 (그것은 집의 일이다) |
| `slope` / `relief` | 경사 등급 0~3 / 고저차 | 배치의 문법이 갈린다 (`slopeName()`: 평탄·완만·비탈·험) |
| `terrain` | 주문된 지형 | `mountain()` — 산의 문법인가 |

부수 질문: `wet(x,z)` · `inside(x,z)` · `buildableCount()` · `reserve(x,z,r)` · `summary()`

## 2. 부르는 법

```java
// ① 땅을 빚고 사양을 받는다 (이것이 전부다)
TerrainForge.SiteSpec site = TerrainForge.prepare(world, place, cx, cy, cz, radius);

// ② 단(段)이 필요하면 **요청한다** — 건축 계층은 땅을 만지지 않는다
TerrainForge.terraceRound(world, site, cx, cz, site.groundY(), 22, Material.DIRT_PATH);   // 산채 마당
TerrainForge.terrace(world, site, cx, cz - 12, site.peakY(), 13, 10, Material.POLISHED_ANDESITE);  // 본전 단
//    → 위를 깎고 · 바닥을 깔고 · 축대로 받치고 · **아래 6칸을 봉인하고** · 마스크를 갱신한다

// ③ 이제 짓는다 — 마스크가 허락한 자리에만
if (site.canBuild(x, z)) { world.getBlockAt(x, site.groundAt(x, z) + 1, z).setType(...); }

// ④ 굴이 필요하면 (등록부가 요구할 때)
TerrainForge.CaveKind kind = TerrainForge.caveKind(place);
if (kind != null) { TerrainForge.CaveSpec cave = TerrainForge.digCave(world, site, kind); }
```

`prepare()` 안의 순서가 곧 계약이다:
`preload → clearSurface(초목·지난 조성) → shape(계약④) → tidyWater(계약③) → feather(계약②) → survey`
(계약①은 `sealBelow` — `terrace`/`feather`/`levelField` 가 지날 때마다 지킨다.)

## 3. 계약을 어떻게 지키는가

1. **딛는 땅** — `sealBelow(x,y,z)`: 지면 아래 6칸 중 공기·**액체**를 흙(2)·돌(4)로 채운다.
   대수층은 동굴을 껐어도 남는다 — 물 위의 길도 껍데기다.
2. **경계가 이어진다** — `feather()`: 부지 밖 22칸에 걸쳐 조성 지면 → 자연 지면으로 선형 수렴.
   위로 **24칸**을 깎는다(6칸으로는 20칸짜리 절개 벼랑이 그대로 남았다). 좌표 해시로 ±1 흔든다.
   **젖은 열은 건너뛴다** — 강을 메우면 강이 아니다.
3. **물 위에 짓지 않는다** — 젖은 열(`WET_COLUMN`)은 마스크에서 빠진다.
   `tidyWater()`: 밑이 허공인 물(**잘린 물기둥**)과 기준면 +3 보다 높은 물(**산 위의 웅덩이**)을 없앤다.
   기준면 아래의 물 = 강·호수 → **건드리지 않는다**. 대신 `waterfront`/`waterSides` 로 **말한다**.
4. **지형이 주문과 맞는다** — `shape()`:
   험산·산·설산 → 없으면 `raiseMassif`(정상 +36, 능선을 방위 해시로 흔들어 등고선을 깬다) ·
   고원 → 넓은 대지(+22) · 평지·평야·분지·초원·폐허 → `levelField`(발치를 메운다, 물은 남긴다) ·
   강·수향·섬·밀림 → **빚지 않는다**(물과 숲은 자연이 준 대로가 옳다).
   **이미 맞는 땅은 건드리지 않는다** — 자연이 준 것이 언제나 더 낫다.

## 4. 이관 (RemoteBuilder → TerrainForge)

`RemoteBuilder` 의 지형 코드는 **전부 TerrainForge 로 옮겨졌다**:

| RemoteBuilder (구) | TerrainForge (신) |
|---|---|
| `sealBelow` | `sealBelow` (액체도 채운다) |
| `feather` | `feather` (젖은 열 보호 추가) |
| `naturalGround` / `NATURAL` | `naturalGround`(물이면 WET) · `naturalTop`(물을 지나침) · `NATURAL`/`SOLID_NATURAL` |
| `shapeTerrain` | `shape` — **peakY 를 돌려준다** (건축이 두 켜를 안다) |
| `raiseMassif` · `levelField` | 같음 (물 보호 추가) |
| `terrace` · `pad` | `terraceRound` · `terrace` — **SiteSpec 을 갱신한다** |
| `clearAbove` · `clearSect` | `clearSurface` — 광석은 남긴다(지하 도굴 방지) |
| `preload` | `preload` |

건축 전담 작업자는 위 목록을 RemoteBuilder 에서 **지우고** `SiteSpec` 만 쓰면 된다.
