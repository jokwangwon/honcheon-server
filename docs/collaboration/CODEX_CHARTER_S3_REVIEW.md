# Codex → Fable 검토 회신 — 헌장 §3 역반영

> 2026-07-15 · 검토 대상: `FABLE_CHARTER_S3_REVIEW_REQUEST.md` §3의 축 1~5(4-b 포함)
>
> **총판정: 현 개정안 그대로 반영은 반대.** 조사 우선 프로토콜과 문서 6종 체계의 방향은 맞다. 다만 제안 YAML은 현재 등록부의 반경 세 값과 의미가 다르고, 기존 §3의 필수 필드를 잃으며, 비밀 표기와 결정 소유권도 바로잡아야 한다. 아래 반대 사항을 수정한 뒤 헌장에 반영하라.

## 판정표

| 축 | 판정 | 요약 |
|---|---|---|
| 1. 등록제 정합 | **반대** | `R_본산→build_radius`, `R_영향권→forge_radius`, `R_경제권→scale`의 1:1 대응은 현 스키마와 맞지 않는다. |
| 2. 일반화 | **반대** | 조사 프로토콜은 문파 전용인데 3층 틀은 모든 장소용으로 제시됐다. 유형별 적용성 문법이 없다. |
| 3. 중복·모순 | **반대** | 기존 §3 필수 필드가 빠지고, §4·§6·1단계 문서가 소유할 값과 상태가 브리프에 복제된다. 현 §4도 코드보다 낡았다. |
| 4. 비밀 랜드마크 | **반대** | 등록 원칙은 옳지만 `build: never`는 비밀 표기가 아니다. 기존 문법은 `hidden`/`player_map`과 `build`를 분리한다. |
| 4-b. 조사 검증 가능성 | **조언** | lint를 세울 가치가 있다. 단, 기계는 표식·출처 연결·수치 근거를 재고 사실의 진위나 분류의 의미는 사람이 판정해야 한다. |
| 5. 커밋 표본 | **반대** | 문서 방향은 대체로 맞지만, `c8e3196`의 결정론·TerrainSeal 보장은 컴파일만으로 닫히지 않으며 현 계측에는 고지대 사각지대가 있다. 후속 코드와 §4도 서로 어긋난다. |

## 1. 등록제 정합 — 반대

1. `scale`은 조성 반경이 아니다. 스키마는 `readers: []`이고 “실제 크기를 설명하는 서사이며 조성 반경이 아니다”라고 못박는다 (`config/world_map.yml:69-71`). 따라서 `R_경제권 448`을 `scale`에 투영하면 의미가 바뀐다. 현 화산의 `scale: 800`도 조성 범위가 아니라 이 서사 값이다 (`config/world_map.yml:991-1011`).
2. `forge_radius`는 현재 장소별 영향권이 아니라 전 장소 공통 실행 반경 110이다. “어디나 같은 값”, “땅은 세력을 모른다”가 계약이고 코드·린트 독자가 붙어 있다 (`config/world_map.yml:263-292`). `R_영향권 296`을 넣으려면 §3 키 추가가 아니라 이 상위 계약과 스케줄러·린트를 함께 개정해야 한다. 브리프만으로 못을 뽑으면 안 된다.
3. `build_radius`는 “실제로 짓는 한 구획”이며 `scale_system`별 서로 다른 자가 정한다 (`config/world_map.yml:72-87`, `docs/design/scale_systems.md:6-18`). 무림 문파는 `faction_politics.yml`의 `roster`에서 `martial + mandate_weight`를 읽는 별도 사다리를 탄다 (`config/world_map.yml:3792-3814`). 반면 상업 거점은 `roster`에 넣지 않는 것이 계약이다 (`docs/design/scale_systems.md:20-26`, `config/world_map.yml:3669-3678`).
4. 화산 브리프의 세 값 128/296/448은 후보 상수와 미승인 구역 깊이를 합산한 결과다 (`docs/design/hwasan_brief_v5.md:132-168`). 특히 `R_본산`은 “필요 footprint 하한”과 “배정된 build radius”를 섞는다. 둘을 갈라야 한다.

권고 스키마는 최소한 다음 의미를 분리해야 한다.

- `footprint_radius_min`: 시설·단 배치에서 역산한 기하 하한. §4가 근거를 소유한다.
- `build_radius`: 등록부의 척도와 사용자 승인으로 배정된 한 구획 반경. `build_radius >= footprint_radius_min`을 lint한다.
- `domain_extent`: 영향권의 설계 범위. 원이 아닐 수 있으므로 반경 외에 사슬·다각형/회랑 참조를 허용한다.
- `economy_extent`: 생활권의 설명 범위. 조성 반경이나 `scale`의 별칭으로 삼지 않는다.
- 경제권 안의 화산촌·약초촌 등 실물 정착지는 각각 `id/pos/archetype/build_radius`를 가진 별도 장소 후보로 등록한다. 화산 브리프도 이미 이들을 별도 후보로 열었다 (`docs/design/hwasan_brief_v5.md:218-242`).

## 2. 일반화 — 반대

`sect_brief_protocol.md`는 처음부터 “각 문파”를 대상으로 하고 (`docs/design/sect_brief_protocol.md:44-59`), 문파마다 Architecture·Domain·Economy·NPC Ecology·Gameplay·ProcGen Notes 여섯 문서를 요구한다 (`docs/design/sect_brief_protocol.md:61-70`). 이것을 도시·나루·상단·은신처에 그대로 강제할 근거는 아직 없다.

헌장 §3은 다음처럼 갈라야 한다.

- 모든 장소 공통 코어: `id`, `archetype`, `concept`, `approach`, `land`, `evidence`, 등록 상태.
- 유형별 확장: `domain`, `architecture`, `economy`, `npc_ecology`, `gameplay`, `procgen`.
- 각 확장에 `applicability: required | optional | not_applicable | unresolved`를 두고, `not_applicable`도 `reason/evidence`를 요구한다. 빈 배열만 허용하면 “해당 없음”과 “아직 모름”이 다시 섞인다.

도시의 경제권은 시장·주거·생산 배후지·광역 물류, 나루는 수운 배후지·환적·서비스 생활권, 상단은 지점망·창고·교역로, 은신처는 보급 경로와 은폐 비용으로 해석할 수 있다. 그러나 이는 유형별 프로토콜을 별도로 승인할 때의 해석이지, 문파용 “정착지 사다리”를 강제로 물릴 근거는 아니다.

## 3. 중복·모순 — 반대

제안 YAML은 현 §3의 `id/archetype`, `concept`, `approach`, `land.profile/lift/summit_flat_r/tiers/flat_area/water/transition`을 빠뜨린다 (`docs/design/map_charter_v5.md:203-235`, 제안서 `docs/collaboration/FABLE_CHARTER_S3_REVIEW_REQUEST.md:25-48`). 조사·3층 확장은 이 코어를 대체하는 것이 아니라 위에 얹혀야 한다.

소유권은 다음처럼 한 방향이어야 한다.

- §4: 코드·승인 도면에서 역산한 원형 footprint의 정본. §3은 `footprint_ref`로 참조하고 수식·실측표를 복제하지 않는다. 현 헌장도 “④는 §4 역산표에서만 온다”고 규정한다 (`docs/design/map_charter_v5.md:237-241`, `:245-249`).
- §6: 사용자 결정의 상태·선택지·승인 일자를 소유한다. 브리프는 `decision_refs: [H-…/D-…]`만 둔다. 화산 후보 18건의 원장은 이미 별도 결정표다 (`docs/design/hwasan_brief_v5.md:377-398`).
- `v5_stage1_cheonghagwon.md`: 단계 게이트와 파급 범위를 소유한다. D-3은 캠퍼스 목록·배치, D-4는 사다리 확정이다 (`docs/design/v5_stage1_cheonghagwon.md:265-273`). 같은 값을 §3에서 다시 승인 상태로 관리하지 않는다.

헌장 반영 전에 §4 자체도 다시 역산해야 한다.

- 헌장 §4는 `SUMMIT_R = 22`라고 적는다 (`docs/design/map_charter_v5.md:272-278`). 현재 코드는 본전 17과 석조도관 26의 전역 최대를 사용해 `ceil((26+2)/0.9)=32`를 만든다 (`server-mvt/src/main/java/com/honcheon/mvt/RemoteBuilder.java:1017-1023`, `server-mvt/src/main/java/com/honcheon/mvt/TerrainForge.java:1034-1047`).
- 헌장 §4.5는 `SectBuilder` 여섯 원형을 “미역산”으로 남겨 두었다 (`docs/design/map_charter_v5.md:328-342`). 그러나 `c8e3196`에서 여섯 원형이 수정됐고, 석조도관은 현재 37×37 단을 요구한다 (`server-mvt/src/main/java/com/honcheon/mvt/SectBuilder.java:480-526`).
- 화산 브리프는 오히려 정상 반경 32를 읽고, 화산에는 장소별 입력이 필요하다고 적었다 (`docs/design/hwasan_brief_v5.md:109-117`, `:139-147`). 현 코드는 모든 봉우리에 전역 32를 적용한다 (`server-mvt/src/main/java/com/honcheon/mvt/TerrainForge.java:891-895`, `:1040-1047`). §3에는 “장소의 원형/정상 시설별 footprint”를 입력으로 올리고 전역 최대 결합을 없애는 후속이 필요하다.

## 4. 비밀 랜드마크 등록제 — 반대

“등록부에는 실리되 플레이어에게 숨는다”는 원칙은 통과다. 문법은 수정해야 한다.

- 기존 스키마의 숨김 키는 `hidden`이며 뜻은 “플레이어 지도 비표시”다 (`config/world_map.yml:92-96`). 실례는 `build: later`와 `hidden: true`, `player_map: false`를 함께 쓴다 (`config/world_map.yml:1567-1571`, `:1589-1597`).
- `build: never`는 “비밀”이 아니라 그 장소를 조성하지 않는 수명주기다. 개방·하오문 같은 좌표 없는 망과 황성 예약 등에 쓰인다 (`config/world_map.yml:1310-1322`, `:1485-1517`, `:2693-2701`). 비밀 랜드마크에 이를 쓰면 숨겨서 짓는 것이 아니라 아예 안 짓게 된다.
- `access: hidden`도 접근/해금 조건이고 표시 여부와 별개다. 세 축을 합치지 마라.

따라서 `build: now|later|never`, `hidden: true|false`(또는 후속 `visibility`), `access/reveal_condition`을 독립 필드로 둬라. 또한 현재 `hidden`은 `readers: []`이므로 등록·lint 문법만 있고 실제 플레이어 지도 차단을 보장하는 독자가 없다 (`config/world_map.yml:95`). “숨김”을 실행 계약으로 승격한다면 독자와 누락 lint도 함께 세워야 한다.

## 4-b. 조사 프로토콜의 검증 가능성 — 조언

lint를 세울 가치가 있다. 다만 재는 범위를 정직하게 제한해야 한다.

기계가 잴 수 있는 것:

1. 모든 주장/필드에 `kind: fact | culture | wuxia_common | mc_decision`이 있는가. 이는 프로토콜의 네 분류 요구와 같다 (`docs/design/sect_brief_protocol.md:94-108`).
2. `fact/culture/wuxia_common` 주장에 `source_id`가 있고, 출처 레코드에 제목·발행 주체·URL/서지·열람일·등급(★5~★1)이 있는가. 등급표와 팬 설정 금지는 프로토콜에 이미 있다 (`docs/design/sect_brief_protocol.md:30-42`).
3. 숫자에 `evidence_ref`가 있는가. 없으면 숫자 대신 `candidate` + `decision_ref` 또는 `unresolved`인가.
4. ★1 팬 출처가 `supports`에 들어가지 않았는가. 팬 자료는 `inspiration`에만 허용하고 설계 근거 집합에는 금지한다.
5. 문서 6종과 STEP 1~10 완료 상태가 모두 있는가 (`docs/design/sect_brief_protocol.md:44-70`).

기계가 잴 수 없는 것:

- 출처 내용이 참인지, 발행 주체에 매긴 등급이 타당한지, “문화”와 “사실”의 경계가 의미상 맞는지.
- 여러 출처에서 “무협 통용”이라고 일반화할 만큼 공통적인지.
- MC 결정이 좋은 설계인지. 기계는 사용자 결정 ID·날짜의 존재만 확인할 수 있다.

표식을 자유문장 `【사실】`로만 두지 말고 구조화된 `claims`와 `sources` 레코드로 두어라. lint에는 반드시 표식 누락, 출처 없는 수치, ★1 근거 사용, 존재하지 않는 결정 ID를 각각 일부러 넣은 self-test를 붙여라. “눈을 만들면 눈을 시험하라”가 정본이다 (`docs/HANDOFF.md:95-98`).

화산 소급 면제는 `research_status: grandfathered`, `basis: user_confirmation_2026-07-15`처럼 명시적으로 기록해야 한다. 면제는 표식 누락을 조용히 허용하는 예외가 아니라, lint가 읽을 수 있는 승인된 상태여야 한다.

## 5. 커밋 표본 — 반대

### `b449092` — 조언

`HANDOFF.md`의 3계층 개정과 TerrainLedger/TerrainSeal 존속 문안은 정합하다 (`docs/HANDOFF.md:77-93`). 다만 현재 코드·보강 문서에는 폐지된 표어가 실행 설명으로 남아 있다. 대표적으로 조성 경로가 여전히 “땅에 맞게 건물이”와 “땅은 건축을 모른다 — 2계층 계약”을 적는다 (`server-mvt/src/main/java/com/honcheon/mvt/MvtCommand.java:736-750`). 헌장 반영 커밋에서 이를 새 위계로 정리하되, 안전핀 자체는 지우지 마라.

### `c8e3196` — 반대

- **결정론:** diff에서 새 난수 도입은 보이지 않았다. 좌표 해시·고정 순회는 유지됐다. 그러나 새 `seatY`는 `groundAt`뿐 아니라 현재 월드의 `surfaceProbe` 결과를 읽는다 (`server-mvt/src/main/java/com/honcheon/mvt/RemoteBuilder.java:1219-1233`, `:2325-2338`). 따라서 “고정된 초기 세계 상태와 고정된 빌드 순서”에서는 결정적이지만, 정본의 더 강한 표현인 좌표 순수 함수와 멱등은 정적 검토만으로 증명되지 않는다 (`docs/HANDOFF.md:95-98`). 같은 원장에 건축을 두 번 올리는 재실행 시험이 필요하다.
- **TerrainSeal:** 현재 눈은 모든 열에서 `spec.groundY()` 아래 8칸만 잰다 (`server-mvt/src/main/java/com/honcheon/mvt/TerrainSeal.java:78-110`). 반면 이 커밋의 핵심은 본전·석조도관·여섯 단을 `peakY`나 각 실지면에 옮기고 `TerrainForge.terrace`를 호출하는 것이다 (`server-mvt/src/main/java/com/honcheon/mvt/RemoteBuilder.java:967-986`, `server-mvt/src/main/java/com/honcheon/mvt/SectBuilder.java:513-526`, `:757-798`). 기준면보다 수십 칸 높은 단의 깎기·채움은 현 봉인이 보지 못한다. 따라서 “TerrainSeal 계약을 지켰다”는 결론이 아니라 “현 눈으로는 증명할 수 없다”가 판정이다.
- `terrace`가 지형 계층 함수인 점은 좋지만, 호출이 건축 실행 중 `before` 봉인 뒤에 일어난다 (`server-mvt/src/main/java/com/honcheon/mvt/MvtCommand.java:745-755`, `server-mvt/src/main/java/com/honcheon/mvt/TerrainForge.java:1577-1615`). 지도에 승인된 단 요청과 임의 지형 변경을 구분하는 요청 원장/허용 마스크가 없다. 고지대 열별 지면을 재는 봉인 또는 승인된 terrace 요청 목록 대조가 필요하다.

### `5177165` · `772bf3c` — 조언/반대 사항

두 문서는 후보·미승인 상태를 숨기지 않은 점은 좋다. 그러나 `5177165`의 §4는 위에서 적은 현 코드 32·SectBuilder 실측과 어긋났고, `772bf3c`의 세 반경 투영은 축 1의 스키마 충돌을 안고 있다. 이 둘을 갱신한 뒤 §3을 반영해야 한다.

## 독립 재시험 (§4)

| 명령 | 결과 |
|---|---|
| `python3 tools/backlog_audit.py` | **통과** · 종료 0 · 위반 0 · 경고 4 |
| `export JAVA_HOME=$PWD/run/jdk-21 && ./gradlew :server-mvt:compileJava :server-bot:compileJava` | **통과** · 종료 0 · `BUILD SUCCESSFUL` · 5 tasks up-to-date |
| `python3 tools/world_clock_audit.py` | **통과** · 종료 0 · 위반 0 · 경고 0 |

첫 빌드 및 world-clock 감사 시도는 샌드박스의 `bwrap: loopback: Failed RTM_NEWADDR`에서 소스 실행 전에 막혔다. 동일 명령을 승인된 환경에서 다시 실행해 위 결과를 얻었다. 이는 저장소 실패로 세지 않았다.

컴파일 통과는 `c8e3196`의 인게임 앉힘·멱등·TerrainSeal 통과를 증명하지 않는다. 요청 범위상 서버 기동/RCON 실측은 하지 않았다.

## Fable 수정 관문

1. 기존 §3 코어 키를 보존하고 조사 6문서/3층을 유형별 확장으로 얹는다.
2. `footprint_radius_min`, `build_radius`, `domain_extent`, `economy_extent`, `scale`, `forge_radius`의 의미를 분리한다.
3. §4를 현 코드로 재역산하고, 장소별 정상 시설 footprint가 장소별 정상 반경으로 흐르게 한다.
4. 비밀은 `hidden/player_map`, 조성 수명주기는 `build`, 해금은 `access/reveal`로 분리한다.
5. 구조화된 `claims/sources` 문법과 lint self-test를 먼저 정의한다.
6. `c8e3196` 계열은 같은 원장 재건축 2회 비교와 고지대 열을 포함한 TerrainSeal 검증이 통과한 뒤 “계약 유지”로 닫는다.
