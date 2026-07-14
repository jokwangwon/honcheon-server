# 조성의 관문 — 게이트 분리 · 강남 · 수향

> **출처: 사용자가 직접 결정했다 (2026-07-14).** 추측이 아니다.
> 이 문서는 `world_map.yml`·`TerrainForge`·`MvtCommand`의 **정본**이다.

## ★ 최종 원칙 (두 문장)

> **땅은 건축의 허락을 받고 서지 않는다. 건축이 미결이면 땅 위에 집만 없을 뿐이다.**
>
> **강남의 물골목은 건축물이 판 도랑이 아니다. 건축물이 들어오기 전부터 그 땅을 이루고 있던 수향이다.**

---

# ① 게이트 순서 — 땅은 빚고, 집만 거절한다

**건축 가능 여부를 먼저 확인하고, 건축이 미결이면 지형 조성까지 중단하는 방식은 폐기한다.**

```
1. 지형 게이트 검사
2. 지형 조성
3. 지형 조성 영수증 기록
4. 건축 게이트 검사
5. 통과하면 건축
6. 실패하면 땅만 남기고 건축 거절 사유 출력
```

> **건축의 미결은 건축만 막을 수 있다. 건축의 미결이 땅을 막아서는 안 된다.**

## 지형 게이트 (이것만 검사한다)
- 좌표가 확정되어 있는가
- 지형 종류/프로파일이 확정되어 있는가
- 지형 조성에 필요한 입력이 존재하는가
- **해당 지형 프로파일의 구현이 완료되었는가**
- 이미 조성된 땅이 아닌가
- **현재 TerrainForge 버전이 조성 승인 상태인가**

## 건축 게이트 (지형이 선 뒤에)
- 건축 원형이 확정되어 있는가
- 규모 체계가 확정되어 있는가
- `build_radius`를 산출할 수 있는가
- 원형에 필요한 배치 조건이 충족되는가

## 정상으로 허용되는 상태 (실패가 아니다)
```yaml
terrain_state: forged
architecture_state: blocked
block_reason: architecture_unresolved
```
**땅은 정상적으로 섰으며, 집만 아직 설 수 없다는 뜻이다.**

## ★ 중요한 제한 — 「미결 16곳을 즉시 다 빚는다」가 아니다
각 장소는 **건축 게이트와 무관하게 지형 게이트만 통과하면** 땅을 갖는다.
**지형 자체가 미결이거나 지형 프로파일 구현이 안 끝났으면 그 땅은 계속 대기한다:**
```yaml
terrain_state: pending
pending_reason: terrain_profile_unresolved        # 또는
pending_reason: terrain_forge_version_not_approved
```

## ★ 강남은 지금 빚지 않는다
게이트 구조는 **지금 고치되**, 강남의 실제 조성은 **점묘 문제가 수정되고 새 TerrainForge 버전이 승인될 때까지 실행하지 않는다.**
```yaml
terrain_state: pending
pending_reason: pointillism_fix_in_progress
```
명령이 호출되어도 **월드에 쓰지 않는다** — 검사 결과와 예정 변형만 출력한다:
```yaml
forge_mode: preview     # 점묘 수정 뒤 → forge_mode: commit
```

## 일회성 조성 영수증 (땅은 한 번만 선다)
```yaml
terrain_receipt:
  profile: 수향
  forge_radius: 110
  forge_version: <approved_version>
  world_seed: <seed>
  location_key: <machine_key>
  input_hash: <deterministic_hash>
  state: committed
```
**이미 `committed` 영수증이 있는 장소는 일반 명령으로 다시 조성하지 않는다.**

---

# ② 강남의 상업 등급 — `trade_city`

```yaml
scale_system: commercial
commercial_class: trade_city
build_radius: 64
```

## 판정 근거 (등록부에 이미 있는 것)
- **`route_centrality: 2`** — 두 교역로가 강남을 통과한다
- `tier: rich` · `scale: 2000` · 플레이 대상 장소 · 물길 중심 정착지

**`route_centrality: 2`는 「한 지역의 시장」인 `market_town`보다 「여러 교역로가 만나는 상업도시」인 `trade_city`의 정의에 더 직접 부합한다.** `market_town: 48`은 강남을 지나치게 작게 읽는다.

**`grand_trade_hub: 80`으로 올리지 않는 이유** — 아래를 입증할 근거가 **없다**:
대륙 전체의 교역 통제 · 여러 권역의 대표 시장 집중 · 중원 전역의 물류 중심 · 국제적 대상 집결지

## 기존 필드의 처리
```
금지: build_radius = scale 변환값
```
**`scale: 2000`은 상업 반경으로 직접 환산하지 않는다** — 서사적 크기의 참고 근거일 뿐이다.

`tier: rich`가 **경제적 부유함**을 뜻하는 필드임이 확인되면 새 스키마로 정규화한다: `wealth_tier: rich`.
**이는 창작이 아니라 기존 필드의 의미를 옮기는 작업이다.** 다만 `tier`가 다른 의미로 쓰인 필드라면 **자동 변환하지 말고 원래 값을 유지한다.**

## 최종 판정
```yaml
commercial_class: trade_city
commercial_radius: 64
classification_basis: [route_centrality_2, existing_rich_tier, narrative_scale_2000]
classification_confidence: sufficient
```
> 강남은 광역 대교역 중심까지는 아니지만, 단순한 지역 시장을 넘어선 **복수 교역로의 수향 상업도시**다.

---

# ③ 물골목 — TerrainForge에 새 「수향」 프로파일

**`land_requests.yml`을 통해 건축이 요청하는 시설로 만들지 않는다.**

```yaml
terrain_profile: 수향
terrain_profile_key: water_town
```

> **강남에 물골목이 있는 것은 건물이 물을 요청했기 때문이 아니다.
> 강남의 땅 자체가 수향이기 때문에 건물들이 물골목 사이에 서는 것이다.**

## 책임 분리

### TerrainForge 「수향」의 책임
- 고을을 관통하는 **주 물길**
- 주 물길에서 갈라지는 **물골목망**
- 물길 사이에 형성되는 **육지 구획**
- **수면 높이와 둑 높이**
- 물이 **고립된 웅덩이가 되지 않게** 하는 연결성
- 기존 월드 지형과 수로망의 **전이**
- 건축이 설 수 있는 **육지와 수변의 기본 형태**

### `land_requests.yml`의 책임 — **국소 수정만**
특정 건물 앞의 작은 나루 · 창고에 접한 하역 공간 · 객잔 뒤편의 짧은 수로 가지 · 다리 교대가 놓일 자리 · 물가 계단 · 마당과 물길 사이의 작은 절개

> **`land_requests.yml`은 수향을 만들지 않는다.** 이미 존재하는 수향 위에서 **건축과 물의 접점을 다듬는다.**

## ★ 폭 6 상한을 주 수로망에 적용하지 않는다
```
금지: 수향의 모든 물길 = land_request(width <= 6)
```
폭 6 상한은 **건축의 국소 요청**을 위한 제한이다. **고을 전체를 관통하는 물골목망을 이 제한에 억지로 넣지 않는다.**

「수향」 프로파일은 **자체 수로 폭 계약**을 갖는다. **구체적 폭이 아직 검산되지 않았으면 숫자를 먼저 고정하지 않는다.** 대신 이 구분부터 구현한다:
```yaml
waterway_classes: [trunk, canal, alley]
```
- **`trunk`** — 등록부의 기존 주요 물길
- **`canal`** — 주요 구역을 연결하는 지선
- **`alley`** — 건물과 생활 공간 사이로 들어가는 좁은 물골목

**각 등급의 실제 폭은 점묘 수정 후 눈 시험과 배 통행 시험으로 확정한다.**

## 수로망 생성 원칙
**등록부에 이미 있는 물길 한 줄을 「주 물길의 씨앗」으로 쓴다.** 그 뒤 수향 프로파일이 **결정론적으로** 지선을 만든다.
```
기존 물길 → 주 물길 → 구역 연결 수로 → 물골목
```
**모든 물길은 하나의 연결된 수계에 속해야 한다.**

### 금지
- 주 물길과 **연결되지 않은 장식용 웅덩이**
- **배가 들어갔다가 나올 수 없는** 막힌 수로
- **수면만 있고 내려설 둑이 없는** 수로
- 건축 부지를 모두 잘라 **고립시키는** 무계획한 물길
- **한 칸마다 서로 다른 무작위 필드로 생기는 점묘 수면**

## ★ 수향 검수 (최소한 이 여섯)
1. **물이 실제로 있는가** — 바닥 장식·색상 변화가 아니라 **실제 수면**
2. **물이 연결되어 있는가** — 고립된 웅덩이는 물골목이 아니다
3. **배가 뜰 수 있는가** — 수로 등급에 맞는 이동 가능성
4. **배에서 내릴 수 있는가** — 나루·물가 계단·낮은 부두·완만한 둑. **물이 건물 곁을 지나가도 내릴 수 없으면 생활 수향이 아니다**
5. **육지끼리 이동할 수 있는가** — 다리는 건축 단계여도 좋으나, **지형이 다리를 놓을 수 없는 수변·절벽만 만들면 안 된다**
6. **점묘가 없는가** — **같은 수로 구간은 연속된 필드와 곡선**을 가져야 하며, **변화는 구역 단위와 전이 구간에서** 일어난다

## 강남의 최종 조성 순서
```
1. 점묘 문제 수정
2. 「수향」 프로파일 구현
3. 미리보기 조성
4. 물 연결성 시험
5. 배 통행 시험
6. 하선 가능성 시험
7. 육지 구획 검산
8. 눈 시험
9. TerrainForge 버전 승인
10. 실제 지형 조성
11. 강남 건축 게이트 검사
12. trade_city 반경 64로 건축 조성
```

---

# 최종 결정 요약
```yaml
gate_contract:
  terrain_before_architecture: true
  architecture_unresolved_blocks_terrain: false
  terrain_unresolved_blocks_terrain: true

gangnam:
  scale_system: commercial
  commercial_class: trade_city
  build_radius: 64
  terrain_profile: 수향
  terrain_state: pending
  pending_reason: pointillism_fix_in_progress

terrain_profile_contract:
  water_town_owned_by: TerrainForge
  land_requests_role: local_adjustments_only
```
