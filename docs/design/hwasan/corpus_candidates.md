# 학습 코퍼스 후보 (E-1) — 2026-08-28 조사

> SYSTEM_REVIEW ⑥ 의 E-1. **고르는 것은 사용자다** — 코퍼스가 곧 취향의 정본이 된다.
> 파일은 `run/corpus/raw/<이름>/` 에 두고 **저장소에 커밋하지 않는다** (저작권).
> 이 문서에는 출처·판정·라이선스만 적는다.
>
> ★고지: PMC(planetminecraft)·minecraft-schematics 는 봇을 막아서(403) 상세 페이지를
> 기계로 못 읽었다. 포맷·팔레트의 **최종 판정은 내려받은 뒤 수입기가 한다** —
> 모르는 블록·모드 블록은 수입기가 세어서 크게 보고한다 (자홍 문화).

## 고르는 기준 넷 (SYSTEM_REVIEW ⑥)

1. **같은 척도** — 중형 전각 (폭 20~50칸). 초대형 재현물은 2군(지붕·공포 참고)으로만
2. **바닐라 팔레트** — Conquest·cocricot 등 모드 팔레트는 제외
3. **양식** — 중국 도관·사찰 1순위 (시각 정본이 중국풍) · 한옥 2순위 (쌓임 문법 호환) ·
   일본식은 넣으면 별도 태그 (지붕 곡선·공포가 다르다 — 분포를 오염시키면 안 된다)
4. **소수 정예 10~20채** — 분포 눈의 문턱이 된다. 한 채 한 채 사용자 눈으로 승인

## 1군 후보 — 중형 동양 목조 (본대)

| # | 이름 | 출처 | 판정 (조사 시점) | 비고 |
|---|---|---|---|---|
| C-01 | ★**한옥마을 (3개월·평지)** — Arynos(구 GOLDENS) | [한마포 map/4094655](https://www.koreaminecraft.net/map/4094655) | **월드 zip 43MB 첨부 확인** · **최소 1.21.11** — 우리 버전과 일치 · All Rights Reserved (개인 사용 가, 재배포 불가) | 마을 = 중형 전각 다수. 첫손 후보. 월드라 수입은 상자 지정 필요 (아래 §수입) |
| C-02 | **Xianyougong Temple** (옛 북경 도관 재현) | [PMC](https://www.planetminecraft.com/project/xianyougong-temple/) | 본전 schematic 첨부 (검색 스니펫 기준) | **도관** — 양식 1순위 |
| C-03 | **Chinese Temple [Download]** | [PMC](https://www.planetminecraft.com/project/chinese-temple-download/) | .litematic · 바닐라 표기 | |
| C-04 | **Templo Taoista from Deimos** | [PMC](https://www.planetminecraft.com/project/templo-taoista-from-deimos-only-pictures/) | 다운로드 표기 · RPG 서버용 | 도관 |
| C-05 | **Asian Sanctum** (full interior · survival-friendly) | [PMC](https://www.planetminecraft.com/project/asian-sanctum-download/) | survival-friendly = 바닐라 유력 | 내부까지 있음 |
| C-06 | **Hanok — traditional korean house** — Uwanami (2022) | [PMC](https://www.planetminecraft.com/project/hanok-traditional-korean-house/) | schematic 첨부 · 다운로드 635 | 단채 한옥 |
| C-07 | **Korean Hanok** | [PMC](https://www.planetminecraft.com/project/korean-hanok-6080124/) | 미확인 | |
| C-08 | **Hanok (Wonmo)** | [minecraft-schematics #13227](https://www.minecraft-schematics.com/schematic/13227/) | "huge" 표기 — 척도 확인 필요 | 옛 사이트라 .schematic(수치 ID) 포맷 위험 |

## 2군 후보 — 대형 재현 (지붕 곡선·공포 참고 · 척도 태그 분리)

| # | 이름 | 출처 | 비고 |
|---|---|---|---|
| C-11 | 경복궁 근정전·경회루 (한옥마을 EP.1) | [한마포 quality/1474666](https://www.koreaminecraft.net/quality/1474666) | 진행 중 프로젝트 — 배포 여부 미확인 |
| C-12 | 북경 자금성 1:1 (중문 커뮤니티) | [minecraftzw 중식 태그](https://www.minecraftzw.com/tag/chinese-map) | 다운로드 경로·버전 미확인 · 참고 수준 |

## 제외 기록 (다시 「발견」하지 않게)

- **Hanok [Conquest Reforged]** (urara · PMC) — 모드 팔레트. 기준 2 탈락
- 4399 등 **중국 모바일(베드락) 존치** — 자바 포맷 아님
- **Chinese Workshop 등 건축 모드** — 블록 자체가 모드. 우리 제약 밖

## 더 캐는 곳 (사용자 브라우즈용 — 봇은 403)

- PMC 태그+schematic 필터: [chinese](https://www.planetminecraft.com/projects/tag/chinese/?share=schematic) · [asian](https://www.planetminecraft.com/projects/tag/asian/?share=schematic) · [pagoda](https://www.planetminecraft.com/projects/tag/pagoda/?share=schematic) · [korea](https://www.planetminecraft.com/projects/tag/korea/?share=schematic)
- [minecraft-schematics theme/asian](https://www.minecraft-schematics.com/theme/asian/)
- [한마포 맵 게시판](https://www.koreaminecraft.net/map)

## §수입 — 받은 것을 어디에 어떻게

```
run/corpus/raw/<이름>/          내려받은 원본 (커밋 금지 — .gitignore 대상)
python3 tools/schem_import.py run/corpus/raw/<이름>/파일.schem
  → run/corpus/tsv/<이름>.tsv   (덤프와 같은 정규형 — stack_mine 이 바로 먹는다)
```

- 지원: Sponge `.schem` (v1·v2·v3) · `.litematic`
- **월드는 `tools/world_import.py`** — `scan` 이 건축 청크를 무리 지어 상자 좌표를 주고,
  `box` 가 상자를 TSV 로 자른다 (★음수 좌표는 `--` 뒤에)
- 옛 `.schematic`(1.12 이전 수치 ID)은 **지원 밖** — 수입기가 그렇게 말하고 죽는다

## §C-01 수입 기록 (2026-08-28)

사용자가 월드 zip 을 받아 왔다 → `run/corpus/raw/hanok_village/` 에 풀었다.
**MC 26.2 (DataVersion 4903)** 저장 — 우리(1.21.11)보다 새 블록이 있다 (shelf·pale_oak·
bush 등 — 색표·형태 사전이 모르는 것은 자홍으로 보고된다. 조용히 안 넘어간다).

- `scan`: 건축 청크 2,538 · 본 무리 **(-432,-624)~(639,527)** — 지름 ~1km 성곽 도시
- 마을 지도: `run/preview/hanok_village_map.png` (최상단 블록 색 · 128칸 격자 눈금)
- 지면 y≈-60 (평지) · 건물 대역 y −60~−20 → 상자는 y −64~−12 로 잘랐다
- 첫 상자 셋 (전부 **바닐라 팔레트 확인**):
  `hanok_hallA` (-48,-210)~(40,-88) 저택 블록 · `hanok_palace` (178,-52)~(310,102)
  **궁궐형 담장 구역 — 중층 정전 + 회랑, 코퍼스감 최상** · `hanok_swB` (-102,238)~(-2,338)
  **사찰 구역 (쌍탑)**
- ★확인된 다음 과제: `stack_mine` 이 이 코퍼스에서 **몸통 0** 을 낸다 — 경계(base·plate)가
  우리 사전 재료로 박혀 있어서다. **경계를 코퍼스에서 스스로 찾는 눈**이 필요하다
  (지붕 재료 바로 밑의 전면 수평 켜 = plate 후보 — E-2 확장)
