package com.honcheon.mvt;

import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Candle;
import org.bukkit.block.data.type.ChiseledBookshelf;
import org.bukkit.block.data.type.HangingSign;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.block.sign.Side;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.util.BoundingBox;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 청하현 조성기 (M2b·v6) — config/regions/cheongha_hyeon.yml 의 장소를 실블록으로 세운다.
 * 철학: 맵도 컴파일한다 — 손건축이 아니라 결정론 생성 (재조성 = 같은 마을).
 * 디자인 언어: docs/design/map_design.md — 수묵 3색(목골·백벽·흑와) 기조 + 유형별 자재 팔레트.
 * 규모(v5): 121x121 (담장 반경 r=60). 광장 15x15, 담장+대문(북·남), 우물(지붕)·매화나무·화단,
 * 2층 청하객잔 17x13 (내부 계단, 1층 주청·2층 객방 통칸), 관청류 13x11 3채(의뢰소·의방·전장),
 * 일반 민가 9채, 북·남 골목길 2줄, 노점 5개(차양 5색), 길가 등롱.
 * v5(건축 개선 A안 — "색만 바뀐 변형은 변형이 아니다"): 팔레트 스왑이 아니라 형태·규모의 변주.
 * ① 풋프린트 확대 — 부지 101→121, 민가 최소 9x12급, 관청 11x9→13x11, 객잔 15x11→17x13
 * ② 민가 형태 유형 풀 4종 — 골조 자체가 다르다: 일자형 13x9 / ㄱ자형(본채 12x9+날개 6x7·안마당) /
 *    다락형 9x12(1.5층 — 지붕 밑 다락+사다리) / 작업장 병설형(본채 12x9+작업간 7x6 — 대장간·직조간)
 * ③ 자재 팔레트 분리(유형별 고정) — 벽: 회벽+목골 노출 / 점토 벽돌(BRICKS·MUD_BRICKS) / 가로 통나무,
 *    지붕: 흑와 / 흑목 너와 / 산화동, 담: 곁담 돌담·목책 / 안마당 목책 / 없음 / 돌담 작업마당
 * ④ 인테리어 필수 세트 — 전 가옥 침상·수납·작업대·조명(높이 변화)·바닥 패턴 + 용도별 소품.
 * 배치는 전부 결정론 조합표(cottages() 주석) — 난수·시각 금지. 정식 맵은 M3 몫 (tools/mapgen 승격).
 * v6(docs/design/building_style_guide.md 3.2 우선순위 5 — "지붕과 간판이 격을 말한다"):
 * ① 철산표국 신축 — 본채 15x11(돌 기단·껍질 벗긴 흑목 노출 기둥·팔작·정면 처마 2칸) + 돌담 마당 21x17
 *    (대문 3칸·표기 장대·짐수레 자리·마구간 5x4). 앵커 "표국"·구역 "철산표국"·NPC 진철산 = 추가만.
 * ② 폐사당 신축 — 담장 밖 북서 외곽(-75,-75). 평탄화 없이 지형 순응. 반파 팔작·부러진 기둥·무너진 담·
 *    냉색 조명(soul_lantern — 이 건물 전용, 마을 안 0개)·신상 없는 제단. 간판·앵커 없음(hidden).
 * ③ 지붕 문법 승격 — paljakRoof(팔작): 서까래 라인 + 네 방향 링 2~3층 + 상부 맞배 전환 + 합각벽(백벽+
 *    환기창) + 용마루·치미. 관청류 4채+객잔+표국에 적용. 민가는 v5 계단 링(맞배 계열) 유지 = 위계.
 * ④ 간판·소품 세대교체 — 현판·주기 = dark_oak_hanging_sign / 장식된 항아리(술단지·약단지·쌀독) /
 *    조각된 책장(약장·전표철·문서철 — 꽂힌 칸 수 상수) / 전장 철창·양초 / 현수 등롱 = 사슬+랜턴.
 * ⑤ 잡화점 점포화(7x9 회벽 맞배·전면 3칸 개방+젖힌 덧문) + 담장 리듬(여장/기와 갓 6칸 교대·12칸 판축
 *    이음매·대문 현판 "청하현"·양면 현수 등롱·각루 3x3 모임지붕). 붉은 차양·장쇠 스폰·반경 15 불변.
 *
 * v6.1 — 인게임 육안 지적 3건("길이 좁다 / 지붕이 어색하다 / 물건 배치가 엉성하다")의 수리.
 *   구조는 맞는데 눈이 불편했다: 규칙식으로 세운 벽·계단에 비례와 리듬이 없었다.
 * ① 길 — 폭과 질감. 대로 3→7칸(중앙 5 흙길 + 갓길 1씩 자갈/거친 흙), 골목 1→3칸(가장자리 잡초),
 *    문 앞 소로 1~2→3칸 + 대로 접속부 디딤돌, 노면 밑 1칸 거친 흙 노반(길에 단면을 준다),
 *    불규칙은 전부 좌표 해시(Math.floorMod). 광장 15x15→19x19(우물 중심 유지), 대문 개구 3→5칸.
 *    등롱 열은 ±2→±5 로 물리고 조성 순서를 건물 뒤로 미뤄 "빈 자리에만 선다"를 코드로 강제.
 * ② 지붕 — 비례 재설계. 처마를 전 방향 2칸(부속채 1칸 = 위계)으로 내밀고 그 밑에 서까래 라인 + 활주,
 *    물매는 1:1(45°)에서 2:1(2칸 전진 1칸 상승 — 같은 y 에 계단·풀블록 두 겹)로 완만하게,
 *    용마루는 반 블록 한 줄에서 풀 블록 몸통 + 덧단 + 양단 치미로. 합각은 하부 링이 다 먹고 남는
 *    만큼만 서므로 지붕 총고의 1/3 이내로 저절로 제한된다. 민가 박공널은 벽면보다 2칸 내민 그림자 선.
 * ③ 소품 — 좌표 나열이 아니라 공간 문법. 벽면 3분할(하단 가구 / 중단 선반·창 / 상단 조명·현수 —
 *    상단을 매달 대들보 2줄을 실내에 걸었다), 시선 축(문에서 정면으로 그 집의 정체 하나:
 *    객잔=계산대·술단지 시렁 / 의방=약장 / 전장=철창 금고 / 의뢰소=게시 목판·의뢰 대장 /
 *    표국=병장기 시렁 / 잡화점=잡동사니 시렁), 여백 규칙(벽 한 면 3점 이하), 방 중앙은 비운다,
 *    밀도 차등(객잔·잡화점 높음 / 의뢰소·표국 중간 / 의방·전장 낮고 정렬 — 밀도가 성격이다).
 * ④ 이격 재검산 — 처마 2칸이 새 겹침을 만들므로 민가 #2·#3·#4·#5·#9 와 표국 담·마구간·짐수레를
 *    재배치하고, 잡화점을 대로 갓길 밖(x+4..+8)으로 물렸다. 앵커 7키·구역 8종·NPC 7인 스폰 좌표 불변.
 *
 * v6.2 — 조감도 육안 지적 5건("잔디 공원 / 건물이 길을 등졌다 / 지붕이 검은 판때기 / 청록 지붕 / 처마 겹침").
 *   v6.1 이 고친 것은 '건물'이었고, 조감도가 드러낸 것은 '땅'이었다: 초원에 건물을 흩뿌린 그림.
 * ① 지면 — groundCover(): 담장 안(±59) 지표를 좌표 해시로 다진 흙 조직으로 갈아엎는다.
 *    거친 흙 62% · 흙 19% · 뿌리 흙 6% · 잔디 12.5% (남길 곳만 남긴다 — 광장 화단·매화 밑은 나중 패스가 덮는다).
 *    ※ DIRT_PATH/GRAVEL 은 '길'의 자재이므로 일반 지면에 쓰지 않는다 (검수의 길 판정을 오염시킨다).
 *    yards(): 민가 뒤뜰 텃밭 7필지(farmland + 성장 고정 작물 + 가운데 물길 1칸) + 낮은 울타리 필지 구획
 *    + 생활 흔적(장독대·장작더미·빨랫줄·닭장·퇴비통) + 우물 두레박. weeds(): 공터 거친 흙에 잡초.
 *    셋 다 reserved() (길·골목·광장·담장 발치·표국 부지) + 지면·공기 검사로 이중 방어 — 침범 0.
 * ② 앞마당 — doorPaths() 를 소로에서 '앞마당'으로 승격: 관청·객잔 문 앞 7~8칸 폭 다진 흙 + 문지방 디딤돌,
 *    민가는 골목에 면한 문턱 3칸에 디딤돌. 건물은 그대로 두고 길이 건물 앞까지 가지를 친다.
 * ③ 지붕 — 용마루를 지붕면보다 1칸 세우고(몸통 + 양단 치미 y+2 + 뿔), 처마 최외곽 링은 반 블록으로 마감해
 *    그림자 선을 만들고(eaveRim), 서까래를 한 칸 더 내밀고(deepEave), 지붕면 4칸마다 풀 블록 세로 골(기와골).
 *    물매는 2:1(0.5) 고정에서 rise(s)=(2s+2)/3 = 2:3(≈0.67) 로 — 의방 0.27 평지붕 경고를 0.5~0.8 대역으로.
 * ④ 산화구리 지붕 폐기 — RoofStyle.COPPER → MUD_TILE(흙기와, MUD_BRICK 계열). 다락형 2채의 청록 308블록이
 *    수묵 기조를 깨뜨렸다. 채색은 차양·매화·등롱에만.
 * ⑤ 처마 겹침 — 잡화점을 z[-18..-10] → z[-18..-13] 로 줄여 의뢰소 앵커 행(z-12)에서 비켜세웠다.
 *    검수의 벽 레이캐스트(공기 5칸 = 건물 밖)가 잡화점과 의뢰소를 한 건물로 합쳐 읽던 것이 겹침의 뿌리다.
 *
 * v6.3 — 조감도 2차 지적 5건("지붕 옆면을 나무 울타리로 / 물매가 다시 급하다 / 지붕이 버섯이다 /
 *   텃밭이 마을 절반이다 / 소품 과밀"). v6.2 가 땅을 고쳤고, 이번엔 **지붕의 옆면과 부피**다.
 * ① 지붕 옆면 = 목재 격자 (사용자 지시) — 심층암 덩어리로 채우던 박공·합각을 **흑목 울타리 격자 +
 *    판자 테두리**로 갈았다(gableBlock). 울타리는 서로 연결되며 살대(창살) 실루엣을 만든다 —
 *    조감도의 '검은 판때기'가 이제 결을 가진 목구조로 읽힌다. 처마 끝단(마구리)에는 지붕 반 블록
 *    바로 밑에 흑목 울타리 한 줄을 매달아(eaveFenceRim) 서까래 끝을 드러내고, 서까래 반 블록은
 *    한 칸 안쪽(벽+1)으로 물려 그늘 선으로 남긴다 — 슬래브는 그늘, 울타리는 결.
 *    큰 집엔 난간: 객잔 2층 스커트 위 흑목 난간 한 바퀴, 표국 기단 동측 툇마루 난간.
 *    팩 정합: deepslate_tiles=흑와 / bamboo_planks=죽렴 은 재텍스처, 목재 울타리는 바닐라 나무색이므로
 *    수묵 기조에 맞는 dark_oak 만 쓴다 (oak·spruce 울타리는 생활 소품·활주 전용으로 유지).
 * ② 물매 재조정 — rise(s) = (2s+2)/3 → **(s+1)/3** (세 칸 전진 한 칸 상승). 용마루 실루엣(+2)은 그대로
 *    두되 지붕면을 완만하게 눕혀 검수식 (지붕최고−지붕최저)/(폭/2) 을 0.5~0.8 대역에 넣는다:
 *    객잔 1.13→0.71 · 의뢰소 0.67→0.67 · 의방 0.33→0.67 · 전장 1.14→0.67 · 표국 1.14→0.67.
 *    (의방의 0.33 은 물매가 아니라 이웃 민가 #9 의 지붕이 검수 단면에 끼어든 값이었다 — ③에서 함께 푼다.)
 * ③ 지붕 부피 축소 — v6.2 의 deepEave(서까래를 지붕 외곽보다 한 칸 더 내밀기)를 폐기했다. 처마 2칸은
 *    유지하되 지붕 최외곽은 **벽+2** 로 고정(구 벽+3) · 용마루가 4~5칸 낮아져 덩어리가 줄었다.
 *    민가 #9 을 x-45→x-47, 잡화점 깊이 6→5 로 물려 관청 검수 단면에서 이웃 지붕을 빼냈다.
 * ④ 텃밭 7 → 4필지. 크기·작물·이랑 방향을 필지마다 다르게(7x4 밀 / 5x5 당근 / 4x7 감자 / 6x4 비트,
 *    물길 방향도 이랑과 나란히). 비운 자리는 다진 흙 마당·공터로 두고 좁은 뒷골목 3줄(backLanes)을 냈다.
 * ⑤ 소품 여백 — 벽 한 면 3점 규칙을 좌표로 강제. 의방 약장을 남벽 3 + 서벽 2 로 쪼개고(구 남벽 10),
 *    의뢰소 문서철을 북벽 3점으로 줄이고, 객잔 서벽 4→3 · 북벽 술단지 3점, 전장 남벽 4→3.
 *
 * v6.4 — 조감도 3차: **폐사당이 호수 한복판에 반쯤 잠겨 있었다** + 검수 위반 2건(야간 암흑 47.5% · 의뢰소 물매 0.30).
 * ① 【버그】 폐사당 수몰 — getHighestBlockYAt 은 **물도 최상단 블록으로 센다**. 폐사당만 평탄화 없이 지형에
 *    순응하므로, 담장 밖 (-75,-75) 가 호수면 수면 위에 기단을 얹었다. 뿌리는 "지면 판정"이었다:
 *    naturalGroundY() — 최상단에서 아래로 내려가며 **액체·수생·인공 블록을 건너뛰고 자연 지면 화이트리스트에
 *    처음 닿는 y** 를 돌려준다. 도중에 WATER/LAVA/ICE/KELP 를 만나면 그 칸은 **부적격(MIN_VALUE)**.
 *    부지 선정은 SHRINE_SITES 12곳을 **순서대로** 검사해 (담 흔적 17x21 전 칸 육지 && 높이 편차 ≤5) 인
 *    **첫 자리**에 짓는다 — 난수 0, 같은 월드면 항상 같은 자리. 다 소진하면 1번 후보를 최소 정지(整地)해
 *    쓰고 경고 로그를 남긴다. 기단 밑은 packed_mud 로 메워 공중에 뜨지 않는다.
 *    재조성 결정론: 메움(packed_mud)·마루 구멍(mud)·기단(stone_bricks)은 전부 **자연 지면 화이트리스트 밖**이라
 *    naturalGroundY 가 통과해 내려간다 → 두 번째 조성의 부지 판정이 첫 번째와 **같은 값**을 읽는다.
 *    부지 판정은 담 흔적 **+ 사방 4칸**까지 마른 땅을 요구한다 — 검수가 폐사당 **구역(Zone)을 ±4 로 넓혀**
 *    수몰을 재기 때문이다(TownAudit 0e5b97c). 구역은 우리가 실제로 고른 자리로 나가므로 냉광 4점(제단 랜턴 2·
 *    마당 랜턴·뒷마당 모닥불)은 어느 후보를 잡아도 구역 안 = **냉광 0 경고 소멸**.
 *    후보는 전부 ox∈[-88,-82] · oz∈[-86,-64] — 부지 전체가 blendEdge 스커트(±68) 밖이라 마을 조성 패스가
 *    지면을 흔들지 못하고(재조성 결정론), 앞의 여섯은 옛 좌표 (cx-75,cz-75) 를 부지 안에 품는다.
 * ② 야간 암흑 47.5% → 등롱 밀도 재설계. 대로 갓길 밖 ±5 열·간격 7 → **±4 열·간격 3**(양측),
 *    광장 12주 · 담 발치 링(±58, 간격 6) · 골목 양옆(z∓18·z∓22, 간격 6) · 뒷골목 · 앞마당 · 표국 소로.
 *    등롱은 이제 **맨땅(흙 계열)에만** 선다 — DIRT_PATH·GRAVEL·돌 포장을 지면 화이트리스트에서 빼서
 *    "노면 위에 서지 않는다"를 자재로 강제했다 (구 v6.1 가드는 흙길을 허용해 길 한복판에 설 수 있었다).
 * ③ 의뢰소 물매 0.30 — 물매는 정상(0.67)이었다. 잡화점 **용마루 뿔**(ridge 의 ax-1/bz+1 한 칸 내밈)이
 *    z=cz-13 지붕 밖으로 한 칸 더 나와 **의뢰소 앵커 z행(cz-12)** 에 얹혔고, 검수 단면이 그 뿔(y=cy+6)에서
 *    시작해 (9-6)/(15-5)=0.30 을 읽었다. 잡화점만 뿔 없는 용마루로(roofShape horns=false — 격이 가장
 *    낮은 점포의 소박한 마루) → 지붕 최남단 cz-13, 앵커 행 cz-12 는 비었다 → 단면은 제 처마(cx+9)에서
 *    시작해 (9-5)/(15-9)=0.67.
 * ④ 텃밭 축소 — 4필지의 크기를 5x4 이하로 줄이고 둘은 **빈 밭(farmland만)** 으로 비웠다. 걷어낸 자리는
 *    다진 흙 마당 + 건초 더미·퇴비통·장작더미(생활 흔적 8곳 추가). 담장 안 FARMLAND ≈ 53칸 / 지면 14161칸
 *    = 0.4% (기준 10% 이하).
 *
 * v6.5 — 조감도 4차: **지붕이 능선이 아니라 고원이다** + 잡초 줄무늬 + 휑한 마당.
 *   검수 위반 0건을 달성한 v6.4 에 새 항목(③-b 지붕 능선)을 걸었더니 **다섯 채 전부** 떨어졌다:
 *   꼭대기 평지 56~78%. 물매는 대역 안(0.67)이었는데도 지붕이 "검은 상자"로 보인 이유가 여기 있었다.
 * ① 【능선 수렴】 물매 rise(s)=(s+1)/3 은 너무 완만해서 **반폭을 다 전진해도 두 경사면이 만나지 못했다**.
 *    남은 꼭대기 평지에 용마루 몸통·등·치미·덧단·뿔을 네 켜로 얹으니 "고원 위의 모자"가 됐고,
 *    최상단 y 평면이 그 용마루 구조물로만 채워져 평지 비율 78% 가 나왔다 (실루엣을 얻으려 얹은 것이
 *    실루엣을 죽였다). v6.5 는 **지붕 높이를 폭에서 역산**한다 — roofShape 가 우진각 링 수(nHip)와
 *    수렴 단 수(nTotal)를 먼저 세고, 능선 상승 H = ceil(0.65 × run) 을 정한 뒤 rise(s)=round(s·H/nTotal)
 *    로 균일하게 오른다. 마지막 경사단은 풀 블록 한 켜(적새)로 마감하고, 그 위에 **폭 1~2칸의 용마루 선**
 *    하나만 얹는다 — 등·치미·덧단·뿔은 전부 폐기. 최상단 평면 = 용마루 5~7칸 / 그 밑 켜 = 경사면 30~38칸
 *    → 평지 13~16% (기준 20% 이하). 물매 = H/nHip = 0.67~0.71 (0.5~0.8 대역 유지).
 *    지붕 최고 높이는 v6.4 와 **같다** (덜어낸 용마루 네 켜만큼 경사면이 올라왔다) → 처마 겹침 회귀 0.
 *    덤: 뿔 폐기로 모든 지붕이 제 사각형 안에 갇혀 이웃의 검수 단면을 오염시키지 못한다 (v6.4 ③의 근절).
 * ② 【줄무늬 파괴】 잡초·잔디가 초록 세로줄로 마을을 지배했다 — 범인은 선형 해시 `x*a + z*b` 다:
 *    그 식의 등고선이 직선이므로 **반드시 줄이 선다**. noise() = 곱셈·xorshift 혼합 해시로 갈아
 *    등고선을 깨고, 잡초 밀도 22% → 14% · 잔디 12.5% → 8% 로 낮췄다 (마당은 맨땅이 지배한다).
 *    노면 점치환·광장 결·뒷골목·디딤돌의 해시도 전부 비선형으로 (사선 줄무늬는 길에도 있었다).
 * ③ 【마을 채우기】 121x121 에 건물 15채면 담장 안이 휑하다. 밭이 아니라 **살림**으로 채운다:
 *    우물 3·공동 빨래터 3·짐수레 4·노점 좌판 4·개집 4·닭장 3·장작 야적 7·건초·퇴비·돌담 모퉁이 4
 *    (=마을 살림 46곳) + 참나무·자작 24그루 + 필지 뒷마당 벽 11줄. 세 규칙을 지킨다 —
 *    (a) 지붕 자재(심층암·흑목 계단/반 블록)를 한 조각도 쓰지 않는다 (검수의 지붕 상자가 부풀면 처마 겹침 오탐),
 *    (b) 노면 자재(조약돌·돌 벽돌·자갈)를 지면(cy)에 놓지 않는다 (마당의 돌 한 장이 '길'로 세어진다),
 *    (c) 채색 금지 — 벚나무는 광장의 몫이고 마당엔 참나무·자작·짚·무명천뿐.
 *    전부 freeCell() 통과분만 = 길·골목·광장·담·건물·등롱·울타리를 한 칸도 밀어내지 않는다.
 *
 * v6.7 — 조감도 5차: **지붕이 계단 테라스 / 건물이 뚜껑 덮인 상자 / 담장 밖이 허허벌판**.
 *   v6.5 가 능선을 세우고 v6.6 이 결을 잡았지만 눈은 다시 셋을 지적했다 — 전부 '검수는 통과하는데
 *   보기 싫은' 것들이다. 자로 재서 옳은 것과 눈에 옳은 것은 다르다.
 * ① 【반 칸 단 — 테라스 파괴】 물매 0.67 은 "세 칸 전진 한 칸 상승"으로 만들어졌다: 같은 정수 y 에
 *    계단 + 풀 블록 두 겹이 평평하게 눕는다. 그 **평평한 단**이 조감도에서 동심원 층계(웨딩케이크)로
 *    읽혔다 — 눈은 rise/run 이 아니라 '단의 폭'을 본다. v6.7 은 마인크래프트 지붕의 표준 기법으로 쪼갠다:
 *    같은 y 를 두 번째로 밟는 단 위에 **반 블록 한 켜**를 얹어(halfStep) 윗면을 y+1.5 로 올린다 →
 *    한 단의 윗면이 1.0 → 1.5 → 2.0 으로 **반 칸씩** 오른다 (계단 → 풀블록+반블록 → 계단).
 *    검수 3수치는 한 톨도 안 움직인다: 물매는 처마단(단 0)과 용마루만 보고 반 블록은 그 사이 단만 올린다.
 *    능선 평지는 최상단 평면(용마루)이 아니라 **분모(그 밑 켜)만** 늘어 오히려 내려간다 (14~16% → 8~9%).
 *    마지막 경사단(적새)은 제외 — 거기 반 블록을 얹으면 용마루 켜와 같은 높이가 되어 다시 고원이다.
 *    결 규칙(v6.6 crossGrain)은 반 블록에도 그대로 적용한다 (북남 = TILE, 동서 = BRICK 회전판).
 * ② 【벽을 보이게】 처마 2칸이 벽 4칸(관청)·3칸(민가) 위에 얹히니 건물이 '뚜껑 덮인 상자'였다.
 *    처마는 향촌 건축의 얼굴이므로 줄이지 않는다 — 대신 **벽을 한 칸 올린다**: 관청류 4 → 5,
 *    민가 3 → 4 (다락형은 이미 4, 표국 본채는 기단 위 4 = 실효 5). 벽고 5 의 관청은 창을 두 켜로
 *    (아래 격자창 · 위 교창) 내어 높아진 벽이 백색 판때기가 되지 않게 한다. 처마 내밀기(2칸)·지붕 평면·
 *    부지는 불변 = 처마 겹침 회귀 0. 대들보가 한 칸 올라가므로 관청 3채의 실내 현수 등롱도 cy+3 → cy+4.
 * ③ 【담장 밖 접근부】 마을은 담에서 끝나지 않는다. 남문 밖 관도 3칸(담 밖 32칸)·북문 밖 산길 2칸
 *    (6칸마다 서쪽으로 한 칸씩 비틀린다)·길가 등롱·이정표 4·돌무더기 8·쉼터 3·나무 26·길가 풀숲.
 *    **평탄화 없이** 지형에 얹는다(outsideGroundY) — 물은 메우지 않고 참나무 널다리로 건넌다.
 *    재조성 결정론: 지면 판정이 흙길도 '지면'으로 인정하고, 돌무더기·이정표·널다리는 전부 자연 지면
 *    화이트리스트 밖이라 다음 조성이 같은 y 를 읽는다. 폐사당 부지(북서)는 통째로 비켜 간다.
 *
 * v6.8 — **안으로 들어간다**. v6.1~v6.7 이 고친 것은 전부 '밖에서 본 마을'이었다. 겉은 잡혔는데
 *   문을 열고 들어가면 소품 몇 점이 벽에 붙은 빈 방이었고, 관청 넷은 밖에서 봐도 구별이 안 됐고,
 *   기연의 무대인 폐사당은 작은 폐허였다.
 * ① 【실내 — 조명이 먼저다】 실내 광원은 지금까지 랜턴·양초였다. 그것들은 전부 검수의 PROP 집합이라
 *    "소품 벽면 ≤3" 과 경쟁했다 — 밝히면 과밀이고 규칙을 지키면 캄캄했다(실내 한복판 광원 4~6 = 몹의 집).
 *    v6.8 은 **WALL_TORCH** 로 푼다: PROP·ROOF·PATH·채색·blocking 어느 집합에도 없는 유일한 광원이다
 *    → 검수 12종 중 어느 것도 건드리지 않고 실내를 밝힌다. 배치는 밀도가 아니라 **기하**다 —
 *    내벽 안줄 간격 4 의 벽등 링(바닥+2) + 방 한가운데 열의 대들보 현수등 2. 광원 = 15 − (평면거리 + 높이차)
 *    이므로 벽등은 평면 5, 현수등은 평면 4 까지 광원 8 을 준다. 전 실내 바닥의 **최소 광원 8** 을 좌표로 보장.
 *    ㉮ 객잔 계단이 실은 올라갈 수 없었다 — 네 단(꼭대기 윗면 cy+5)이 2층 마루(걷는 면 cy+6)보다 한 칸
 *       낮아 점프해야 했다. 다섯 단으로 늘려 마루와 평평하게 잇고, 디딤마다 머리 위를 비웠다(통행고).
 *    ㉯ 2층은 통칸이 아니라 **객방 5조** — 침상 사이에 가문비 울타리 칸막이. 판벽이 아니라 울타리인 이유:
 *       빛이 통과한다(칸마다 광원을 새로 넣지 않아도 최소 8 이 유지된다). 천장고 4칸.
 *    ㉰ 다락형 민가의 다락엔 벽이 없다(벽고 4 위는 지붕면) → 벽등을 못 붙인다. 마루 등롱 4점으로 채운다.
 *    ㉱ 앉을 자리 — 객잔 술상 6조에 마주 앉는 걸상 12, 의방 진료 평상에 의원의 걸상과 맥상.
 *       걸상은 **계단 블록**이다: PROP 집합 밖이라 소품 예산을 한 점도 쓰지 않는다.
 * ② 【부속으로 정체를 말한다】 같은 문법(백벽·흑와·팔작·처마 2칸)이 격을 맞추는 대신 구별을 지웠다.
 *    실루엣을 가르는 것은 지붕이 아니라 생업의 도구다 — 의방 = 약재 건조대 2줄 + 탕약 굴뚝(연기),
 *    전장 = 돌 기단(반 칸 댓돌) + 창의 철창 + 앞뜰 낮은 담, 의뢰소 = 옥외 게시대 5칸 + 차양(앞마당 밖으로
 *    옮겨 키웠다 — 구 게시판은 작았고 하필 동선 위에 서 있었다), 객잔 = 부뚜막 굴뚝(밥 짓는 연기).
 *    자재 금기 셋: 지붕 자재 금지(벽 ±8 안의 지붕 자재는 그 집 지붕으로 세어져 처마 겹침 오탐 — 차양은
 *    SPRUCE_SLAB, 굴뚝은 BRICKS), 노면 자재를 cy 에 금지(전장 기단은 STONE_BRICK_SLAB 을 **cy+1** 에),
 *    채색 0.
 * ③ 【폐사당 — 한때 온전했던 것의 잔해】 무너진 지붕은 있는데 '무너진 것들의 역사'가 없었다.
 *    참배길 석등 2기(한 기는 넘어져 등롱이 뒹군다)·시주 비석 2(하나는 허리에서 부러졌다)·이끼 낀 참배
 *    계단 5칸(지형에 얹고 밑은 다진 진흙으로 메운다)·기울어진 문설주(층차로 쓴 기울기)·마당 잡초 26%.
 *    냉색은 폐사당 전용 유지 — 새 냉광 2점도 전부 구역 안이다 (마을 안 영혼 계열 0개 불변).
 *
 * v7.0 — **관(官)이 선다. 그리고 마을 안에 빈부가 보인다.**
 * ① 【청하현 관아 — county_office】 등록부에 이름이 있는데 실물이 없던, 청하현에서 **유일하게 무대가 빈
 *    등록 장소**다. 세력 정치(관무불가침)가 배선된 지금 "현령을 죽이면 강호가 그를 버린다"는 규칙은
 *    **현령이 앉아 있을 자리**를 요구한다. 삼문(三門, 정문 3칸 + 협문 2) · 정청(正廳 15x13, 현령의 단) ·
 *    형방(刑房 6x4) · 옥(獄 6x4, 철창) · 돌담 마당 23x25. 부지는 남서(x[-31..-9] · z[+33..+57]) —
 *    남골목에서 축선 3칸이 삼문으로 곧게 든다. 관은 무림과 다르다: 팔작·백벽·흑와의 문법은 같아도
 *    좌형(左刑) 우옥(右獄)의 **대칭**과 돌의 **위엄**이 격을 가른다.
 *    ★ NPC 2인 추가(현령 조문원 · 포두 박호) → **검수 ⑨ 가 NPC 를 7인 정수로 못 박고 있어 "NPC 9/7" 이 뜬다.**
 *    조성기는 검수기를 소유하지 않는다 — 보고서의 수정 지시서를 참조하라 (기대값 7 → 9).
 * ② 【필지 등급 — village_tiers.md P3】 등급은 마을 단위지만 **필지 단위로 ±1** 을 허용한다. 지금 당장
 *    청하현 안에서 빈부가 보이는 길이다:
 *    · 부촌 필지(중촌 +1) — 표국 · 전장 · 관아: 돌 기단 · 굽도리(stone_bricks 2단) · 전돌 바닥 · 석등.
 *      ★ 전돌은 반드시 **POLISHED_ANDESITE** 다: stone_bricks·andesite·smooth_stone·cobblestone 은 전부
 *        TownAudit.PATH 라 실내에 깔면 그 칸이 '길'로 세어져 길 폭 히스토그램과 야간 광원 표본을 오염시킨다.
 *      ★ 굽도리는 cy+1·cy+2 다 — 검수의 길 판정은 **지면(cy) 한 켜만** 읽으므로 길 표본이 늘지 않는다.
 *      ★ 석등(stone_brick_wall 2 + lantern + slab 갓)의 등롱은 PROP 이지만 전부 벽 사각형 **밖**이라
 *        propScan 이 세지 않는다.
 *    · 빈촌 필지(중촌 −1) — 북골목 민가 3채(#1 대장간 · #2 ㄱ자 · #3 일자): 흙벽(mud_bricks) · 초가 ·
 *      기운 울타리 · 규모 축소(11x9). 열병(cheongha_fever_rumor)과 물류 불안(north_road_bandits)의
 *      1차 피해자 — **가난은 서사가 아니라 자재로 말한다.**
 *      ★ 초가의 함정: TownAudit.ROOF 에 HAY_BLOCK 은 **없다**. 짚으로 지붕을 덮으면 "지붕 없음" 위반이 나고
 *        처마·물매·능선·단 길이 네 항목이 통째로 측정 불능이 된다. 그래서 초가는 "짚으로 덮은 지붕"이 아니라
 *        **"너와 지붕에 짚을 얹은 것"** 이다(thatch): 처마 최외곽 링(dark_oak_slab)과 용마루·적새
 *        (dark_oak_planks)는 손대지 않고, **경사면의 계단(dark_oak_stairs)만** 좌표 해시 25% 로 점치환한다.
 *      · 필지 예외의 상한(마을 건물 수의 30%)도 지킨다: 15채 중 3채 = 20%.
 * ③ 【규모 — "크기를 더 늘려도 되니 좀 더 여유롭게"】 중촌 규모표(village_tiers.md 2.1)대로 키웠다.
 *    부를 담는 것은 소품이 아니라 **면적**이다 (소품을 더 얹으면 검수 ④·⑨ 에 걸린다):
 *    · 객잔 17x13 → **21x15** (실내 165 → 247칸) · 관청류 3채 13x11 → **15x13** (실내 99 → 143칸)
 *    · 일자형 민가 #5·#9 13x9 → **15x11** / 빈촌 필지 #3 → 11x9 (등급이 규모로도 읽힌다)
 *    · **부지 121x121 은 유지된다** — 부지 확대(검수 SCAN_R=65 하드코딩) 없이 배치만 재검산했다:
 *      ㉮ 관청 3채는 대로 쪽으로 두 칸 나오고(벽 z∓6/±6), 앞마당을 z∓5..∓4 두 줄로 조였다. 처마(z∓4/±4)가
 *         등롱 열 위를 덮되 **지면(cy)은 한 칸도 먹지 않는다** → 길 폭·야간 표본 불변.
 *      ㉯ 객잔은 서·북으로 자랐다(x-32 · z-20). 민가 #2 의 지붕(최고 cy+9)과 객잔 지붕(최저 cy+10)이
 *         y 로 어긋나 겹치지 않는다.
 *      ㉰ 민가 #9 를 x-47 → **x-51 · z+6** 으로 물렸다. 안 그러면 그 지붕이 의방의 **지붕 스캔 상자
 *         (벽±8)** 안으로 들어와 의방의 물매 단면을 이웃 처마에서 시작하게 만든다 (v6.3 ③ 의 재발).
 *      ㉱ 다락형 #4·#8 · ㄱ자 #6 · 작업장 #7 은 **키우지 않았다** — 표국 부지·남골목·표국 진입 소로에
 *         막혀 121x121 안에서는 확대 여지가 없다 (보고서: 부지 141x141 = r70 이 필요하다).
 */
final class CheonghaBuilder {

    private CheonghaBuilder() {
    }

    /** 벽 자재 체계 — 유형별로 골조가 아니라 벽 조직 자체가 다르다 (v5 A안 ③) */
    private enum WallStyle {
        PLASTER_WHITE,   // 백벽 회벽 — 주요 건물 4채 전용 (수묵 3색 정체성)
        FRAME_GRAY,      // 회벽 + 목골 노출 — 스터드 기둥 (x+z)%3==0 열이 흑목으로 드러난다
        BRICK,           // 점토 벽돌 (구운 벽돌)
        MUD_BRICK,       // 점토 벽돌 (흙벽돌 — 투박한 살림집)
        LOG              // 가로 통나무 — 벽 진행 방향으로 눕힌 원목 (귀틀집)
    }

    /**
     * 지붕 자재 체계 — 같은 문법, 재질 3계열. v6.2 ④: 산화동(청록)을 버리고 흙기와로 갈았다.
     * 조감도에서 산화구리 지붕 2채가 청록 덩어리로 튀어 수묵 3색 기조를 깼다. 서민 지붕의 변주는
     * **저채도 안에서** 한다 — 흑와(심층암) / 흑목 너와 / 흙기와(진흙 벽돌).
     */
    private enum RoofStyle {
        TILE,            // 흑와 — DEEPSLATE_TILE 계열
        SHINGLE,         // 흑목 너와 — DARK_OAK 계열
        MUD_TILE         // 흙기와 — MUD_BRICK 계열 (v6.2 ④ — 구 COPPER 자리. 저채도 흙빛)
    }

    /**
     * 플레이어 위치를 광장 중심으로 마을을 세우고 장소 앵커를 돌려준다.
     * zonesOut 에는 입장 타이틀용 구역(마을 전체·건물 4·장터)을 채운다 — 작은 부피가 이긴다.
     */
    static Map<String, Location> build(Player admin, List<Zone> zonesOut) {
        return build(admin.getWorld(), admin.getLocation().getBlockX(),
                admin.getLocation().getBlockY() - 1,   // 발밑 = 지면
                admin.getLocation().getBlockZ(), zonesOut);
    }

    /**
     * 좌표 지정 조성 — 콘솔·자동 검증 루프의 진입점 (플레이어 없이 재조성).
     * 결정론이므로 같은 (cx,cy,cz)면 같은 마을이 선다.
     */
    static Map<String, Location> build(World world, int cx, int cy, int cz, List<Zone> zonesOut) {
        clearNpcs(world, cx, cy, cz);        // F29 — 재조성 시 기존 NPC 정리 (중복 스폰 방지)

        // v7.1 【지형 통합】 — 조성 전에 부지를 측량하고(원지형 한 벌), 그 위에서 지형 패스를 돈다.
        //   측량 → 판정(거부 사유 로그) → 정지 → 기초 봉인 → 수역 → 페더링 → 호안 → 잘린 물 청소.
        //   순서가 곧 논리다: 봉인은 정지 뒤라야 지면(cy)이 정해지고, 페더링은 봉인 뒤라야 코어 경계값이
        //   고정되며, 호안은 페더링 뒤라야 물가의 최종 지표를 안다. 잘린 물 청소는 맨 끝(모두 확정 뒤).
        // v7.2 【마을의 기복】 — 정지(整地) 전에 고도장을 먼저 빚는다. 이제 코어는 'cy 한 평면'이 아니라
        //   **완만한 기복 + 필지별 기단**이다 (노면·담장은 cy 에 못 박혀 있다 — 검수가 cy 한 켜를 읽는다).
        buildRelief(cx, cy, cz);
        Terrain terrain = surveyTerrain(world, cx, cy, cz);
        clearAndFlatten(world, terrain);
        foundationSeal(world, terrain);      // 바닥 밑 6칸 봉인 (동굴은 살려 둔다 · 심연은 돌기둥으로 받친다)
        logSite(terrain);                    // 부지 적합성 — 부적합이면 사유를 남긴다 (거부는 호출자의 몫)
        fillCoreWater(world, terrain);       // 코어 안의 물 — 웅덩이·침범한 물길을 메운다
        featherEdge(world, terrain);         // F31 후계 — 담 밖 20칸 전이대 (1-립시츠: 계단도 벽도 없다)
        shoreBank(world, terrain);           // 보존 수역의 물가 — 물이 마을로 새 들어오지 못하게 지형으로 막는다
        strayWater(world, terrain);          // 잘린 물기둥·공중의 물·물먹은 블록 제거
        groundCover(world, cx, cy, cz);      // v6.2 ① — 담장 안은 사람이 밟고 사는 땅이다 (잔디 공원 폐기)
        plazaAndWell(world, cx, cy, cz);
        roads(world, cx, cy, cz);
        townWall(world, cx, cy, cz);
        plazaTreesAndFlowerBeds(world, cx, cy, cz);

        Map<String, Location> anchors = new LinkedHashMap<>();
        anchors.put("장터", loc(world, cx, cy + 1, cz));

        // 앵커 건물 4채 — cheongha_hyeon.yml places. 객잔은 2층 대형(17x13), 나머지 13x11.
        // 도로변 입구: 북쪽 두 채는 남향, 남쪽 두 채는 북향(광장을 바라본다).
        // v6.7 ② 관청류 벽고 4 → 5. 처마(2칸)·지붕 부피는 그대로인데 벽이 한 켜만 보이면 건물은
        // '뚜껑 덮인 상자'가 된다. 벽을 한 칸 올리면 처마 그늘 밑으로 백벽이 한 켜 더 드러난다.
        anchors.put("청하객잔", inn(world, cx, cy, cz));
        // v6.9 ③ — 창호도 격이다. 같은 백벽·흑와·팔작이어도 창이 다르면 밖에서 봐도 무슨 집인지 안다:
        // 의뢰소는 정면을 게시대에 내주고 창을 측면으로 물렸고, 의방은 볕이 들게 정면을 크게 열었고,
        // 전장은 창을 작고 높게 올려 철창을 박았다.
        // v7.0 ③ — 중촌 규모표(village_tiers.md 2.1): 관청류 13x11 → **15x13**. 실내가 99 → 143칸이 되어
        //   가구를 놓고도 바닥 여백 ≥55% 가 남는다 ("크기를 늘려도 되니 여유롭게"의 구현은 면적이다).
        //   부지 검산: 의뢰소 z[-18..-6] (문 남향 · 앞마당 z-5..-4) / 의방·전장 z[+6..+18] (문 북향 · 앞마당 z+4..+5).
        //   지붕은 벽+2 → 의뢰소 z[-20..-4] · 의방·전장 z[+4..+20] — 대로 갓길(±3)·등롱 열(±4) 위를 처마가
        //   덮되 지면(cy)은 한 칸도 먹지 않는다 (길 폭·야간 표본 불변).
        anchors.put("의뢰소", house(world, cx + 11, cy, cz - 18, 15, 13, 5, false,
                "의뢰소", "정파 연락망 — 일과 보수", WindowStyle.OFFICE));
        anchors.put("의방", house(world, cx - 25, cy, cz + 6, 15, 13, 5, true,
                "약재상 · 의방", "외상 장부 있음", WindowStyle.CLINIC));
        anchors.put("전장", house(world, cx + 11, cy, cz + 6, 15, 13, 5, true,
                "청하전장", "전표 = 가져온 이가 임자", WindowStyle.VAULT));
        // v7.2 【위계를 높이로】 — 관아와 표국은 **한 켜 높은 자리**에 선다 (PLOT_PADS 와 같은 값이어야 한다).
        //   담 안이 통째로 cy+1 이므로 마당·기단·석등·담·문루가 다 함께 오른다. 담 밖 소로는 cy 라
        //   대문 앞에 한 칸 턱이 생긴다 — 그것이 곧 댓돌이고, 걸어 넘는 높이다 (1-립시츠 유지).
        anchors.put("표국", pyoguk(world, cx, cy + PAD_PYOGUK, cz));   // v6 ① — 등록 장소 pyoguk (키는 추가만, 기존 6키 불변)
        anchors.put("관아", countyOffice(world, cx, cy + PAD_OFFICE, cz));   // v7.0 ① — 등록 장소 county_office

        medicineInterior(world, cx, cy, cz);
        exchangeInterior(world, cx, cy, cz);
        requestOfficeInterior(world, cx, cy, cz);
        bulletinBoard(world, cx, cy, cz);
        cottages(world, cx, cy, cz);         // 일반 민가 9채 — 마을의 생기 (앵커·구역·NPC 없음)
        doorPaths(world, cx, cy, cz);
        alleys(world, cx, cy, cz);
        cottageDoorsteps(world, cx, cy, cz);  // v6.2 ② — 골목을 깐 뒤 문턱 3칸을 디딤돌로 (골목이 디딤돌을 덮지 않게)
        backLanes(world, cx, cy, cz);        // v6.3 ④ — 텃밭을 걷어낸 자리에 좁은 뒷골목 (마당·공터에 동선을 준다)
        yards(world, cx, cy, cz);            // v6.2 ① — 필지 울타리·뒤뜰 텃밭·생활 흔적 (울타리가 마을을 마을로 만든다)

        marketStalls(world, cx, cy, cz);
        streetLanterns(world, cx, cy, cz);   // v6.1 ① — 길을 7칸으로 넓혔으므로 등롱은 건물·길을 다 세운 뒤 빈 자리에만 선다
        villageFill(world, cx, cy, cz);      // v6.5 ③ — 우물·빨래터·수레·좌판·개집·나무·필지 벽 (휑한 마당을 살림으로 채운다)
        weeds(world, cx, cy, cz);            // v6.2 ① — 잡초는 맨 끝 (등롱·소품 자리를 뺏지 않는다)
        // v6.5 마감 — 노면 재포장. 뒤에 도는 패스(마당·필지 벽·잡초)가 길 한 칸이라도 덮으면
        // 그 자리에서 길이 끊긴다 (검수: 골목 폭 0 관측 — 골목 가장자리 한 칸이 거친 흙이 됐다).
        // 길은 마을의 뼈대다 — 맨 마지막에 다시 깔아 아무도 못 덮게 한다.
        roads(world, cx, cy, cz);
        alleys(world, cx, cy, cz);
        doorPaths(world, cx, cy, cz);
        cottageDoorsteps(world, cx, cy, cz);
        // v6.8 ② — 부속(약재 건조대·굴뚝·기단·철창·낮은 담)은 노면을 다시 깐 **뒤에** 선다.
        // 앞서 놓으면 마지막 재포장이 기단·담의 발치를 흙길로 덮어 부속이 땅에 반쯤 묻힌다.
        facades(world, cx, cy, cz);
        placeSign(world, cx + 5, cy + 1, cz - 58, BlockFace.WEST, "북쪽 산길 →", "늑대·여우 — 도적 소문 있음");   // 대로(±3) 밖 갓길
        anchors.put("북쪽_산길", loc(world, cx, cy + 1, cz - 59));

        // NPC — cheongha_npcs.yml (이름 = 등록제 명사)
        npc(world, anchors.get("청하객잔"), 0f, "객잔 주인 한백");
        npc(world, anchors.get("의뢰소"), 0f, "의뢰소 관리인 소연");
        npc(world, anchors.get("의방"), 180f, "의원 유문");
        npc(world, anchors.get("전장"), 180f, "전장 지점주 금서방");   // F28 — 조문원은 현령이다 (등록부 정합)
        npc(world, loc(world, cx + 3, cy + 1, cz + 3), 135f, "표사 곽진");   // 우물 쪽
        npc(world, loc(world, cx + 8, cy + 1, cz - 6), 90f, "장터 잡화상 장쇠");   // market_peddler — 붉은 차양 좌판 뒤, 광장(서쪽)을 본다
        npc(world, anchors.get("표국"), 0f, "표국주 진철산");   // v6 ① — 등록부 jincheolsan (본채 중앙, 대문 쪽을 본다)
        // v7.0 ① 관(官) 2인 — 등록부 county_office.linked_npcs: [jomunwon, bakho].
        //   ★ 검수 ⑨ 는 NPC 를 **7인 정수**로 못 박고 있다 (TownAudit.contracts) → 이 두 스폰으로 "NPC 9/7" 이 뜬다.
        //   조성기는 검수기를 소유하지 않는다. 보고서에 수정 지시서를 남긴다 (기대값 7 → 9).
        //   그럼에도 스폰하는 이유: 세력 정치(관무불가침)가 **현령을 죽일 수 있어야** 성립한다 —
        //   죽일 수 없는 현령은 규칙이 아니라 문서다. 앉을 자리를 지었으면 앉혀야 한다.
        // 현령은 앵커(정청 중앙)가 아니라 **단(壇) 위**에 앉는다 — 앵커는 검수의 자이고, 자리는 서사의 것이다.
        npc(world, loc(world, cx - 20, cy + PAD_OFFICE + 2, cz + 50), 180f, "현령 조문원");   // 전돌 단 위 — 삼문을 내려다본다
        npc(world, loc(world, cx - 26, cy + PAD_OFFICE + 1, cz + 39), 180f, "포두 박호");     // 형방 앞 — 마당을 본다

        approaches(world, cx, cy, cz);   // v6.7 ③ — 담장 밖 접근부 (관도·산길·이정표·돌무더기·숲. 평탄화 없음)

        zones(world, cx, cy, cz, zonesOut);
        abandonedShrine(world, cx, cy, cz, zonesOut);   // v6 ② — 담장 밖 외곽 스팟 조성 (평탄화 밖, 구역만 추가)
        // v6.9 — 마을 밖 등록 장소. 둘 다 마을 중심에서 89칸 밖 = 검수 스캔 창(≤88) 밖이므로
        // 12종 수치를 한 톨도 건드리지 않는다. 앵커는 늘리지 않는다 (검수가 앵커를 '건물'로 실측하므로
        // 야외 앵커 한 개가 "지붕없음" 위반이 된다 — 계약도 7키 그대로).
        huntingGrounds(world, cx, cy, cz, zonesOut);   // v6.9 ① 북쪽 산길 — 사냥터 (등록부 north_road)
        heuksuFerry(world, cx, cy, cz, zonesOut);      // v6.9 ② 흑수나루 (등록부 heuksu_ferry — 침몰선 비급)

        // v7.1 ④ 마감 봉인 — **맨 마지막**이어야 한다. 접근로·소품·폐사당·사냥터·나루가 자연 지형 위에
        //   놓은 바닥까지 남김없이 훑어, 그 밑 5칸의 공기를 자연 자재로 메운다 (검수 ① 바닥 밑 공동 = 0).
        //   물 위의 널다리는 밑이 물이므로 건드리지 않는다 (강을 막지 않는다).
        sealLaidFloors(world, terrain);
        return anchors;
    }

    // ─── 구역 — 입장 타이틀의 단위 (마을 전체 → 건물·장터 순으로 좁아진다) ───

    private static void zones(World world, int cx, int cy, int cz, List<Zone> out) {
        String w = world.getName();
        out.add(new Zone("청하현", "섬서의 작은 현 — 강호의 첫 걸음", w,
                cx - 60, cy - 2, cz - 60, cx + 60, cy + 19, cz + 60));
        out.add(new Zone("청하객잔", "소문은 국밥보다 빨리 식는다", w,
                cx - 32, cy - 2, cz - 20, cx - 12, cy + 19, cz - 6));
        out.add(new Zone("의뢰소", "정파 연락망 — 일과 보수", w,
                cx + 11, cy - 2, cz - 18, cx + 25, cy + 13, cz - 6));
        out.add(new Zone("의방", "약재상 — 외상 장부 있음", w,
                cx - 25, cy - 2, cz + 6, cx - 11, cy + 13, cz + 18));
        out.add(new Zone("청하전장", "전표 = 가져온 이가 임자", w,
                cx + 11, cy - 2, cz + 6, cx + 25, cy + 13, cz + 18));
        out.add(new Zone("장터", "가죽 매입 — /혼천 팔기", w,
                cx + 5, cy - 2, cz - 5, cx + 14, cy + 7, cz + 5));
        out.add(new Zone("철산표국", "표행은 신용 장사 — 한 번 깬 자와는 두 번 일하지 않는다", w,   // v6 ①
                cx + PY_X0, cy - 2, cz + PY_Z0, cx + PY_X1, cy + 14, cz + PY_Z1));
        out.add(new Zone("청하현 관아", "관은 무림이 아니다 — 여기서는 법이 이긴다", w,   // v7.0 ①
                cx + CO_X0, cy - 2, cz + CO_Z0, cx + CO_X1, cy + 16, cz + CO_Z1));
    }

    // ─── 지형 ───

    /**
     * 부지 정지(整地) — 코어(±SITE_R)의 지면을 cy 로 고르고 그 위를 비운다.
     *
     * <p>v7.1 ① 【부유 산 제거】 구 버전은 cy+1..cy+18 만 비웠다. 부지에 cy+18 보다 높은 자연 지형
     * (언덕·바위 기둥)이 걸리면 그 윗도리가 **허공에 뜬 채 남았다**. 이제 그 열의 실제 최상단까지
     * (상한 cy+80) 걷어낸다 — 검수 ⑤(부유 블록)의 구조적 방어.
     *
     * <p>지면 아래는 여기서 손대지 않는다. 그것은 foundationSeal 의 몫이다 (동굴은 세계의 자산이므로
     * '통째로 메우기'가 아니라 '얇게 봉인하기'로 푼다).
     */
    private static void clearAndFlatten(World world, Terrain t) {
        int cx = t.cx, cy = t.cy, cz = t.cz;
        for (int dx = -SITE_R; dx <= SITE_R; dx++) {
            for (int dz = -SITE_R; dz <= SITE_R; dz++) {
                int x = cx + dx, z = cz + dz;
                // v7.2 — 코어의 지표는 **cy 한 평면이 아니라 고도장(cy + lift)** 이다.
                //   담 발치(r ≥ 58)는 lift = 0 이므로 코어 경계 링은 여전히 cy 고, 페더링의 고정
                //   경계값도 그대로다 (전이대는 한 줄도 안 바뀐다 — 환경 검수 ③ 불변).
                int g = cy + lift(dx, dz);
                world.getBlockAt(x, g, z).setType(Material.GRASS_BLOCK);
                int top = Math.max(cy + 18,
                        Math.min(world.getHighestBlockYAt(x, z), Math.min(world.getMaxHeight() - 1, cy + 80)));
                for (int y = g + 1; y <= top; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (!b.getType().isAir()) {
                        b.setType(Material.AIR);
                    }
                }
                t.target[t.idx(dx, dz)] = g;   // 코어의 최종 지표 (페더링·봉인·마감이 다 이 값을 본다)
            }
        }
    }

    /** F29 — 조성 영역 내 기존 혼천 NPC(명패+무적 주민) 제거 — 재조성 = 같은 마을, NPC도 한 벌 */
    private static void clearNpcs(World world, int cx, int cy, int cz) {
        // 콘솔·자동 조성에는 근처에 플레이어가 없다 → 부지 청크가 언로드 상태이고
        // getNearbyEntities 가 빈 목록을 돌려준다. 정리가 조용히 실패해 조성마다 NPC 가 7인씩
        // 쌓였다 (F29 회귀 — 검수가 "NPC 35인" 으로 잡았다). 찾기 전에 청크를 먼저 로드한다.
        for (int chunkX = (cx - 64) >> 4; chunkX <= (cx + 64) >> 4; chunkX++) {
            for (int chunkZ = (cz - 64) >> 4; chunkZ <= (cz + 64) >> 4; chunkZ++) {
                world.getChunkAt(chunkX, chunkZ).load(true);
            }
        }
        BoundingBox box = new BoundingBox(cx - 63, cy - 8, cz - 63, cx + 64, cy + 20, cz + 64);
        int removed = 0;
        for (Entity e : world.getNearbyEntities(box)) {
            if (e instanceof Villager v && v.getCustomName() != null && v.isInvulnerable()) {
                v.remove();
                removed++;
            }
        }
        if (removed > 0) {
            org.bukkit.Bukkit.getLogger().info("[혼천/조성] 기존 NPC " + removed + "인 정리");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // v7.1 【지형 통합】 — 마을은 자연 위에 얹은 상자가 아니라 자연에 앉은 마을이어야 한다
    //   설계 근거·알고리즘 전문: docs/design/terrain_integration.md
    //
    // 인게임 관측(사용자) + 환경검수(TerrainAudit) 실측이 같은 것을 가리켰다:
    //   ① 바닥 밑 공동 8.5% — 우리가 깐 길·바닥이 바닐라 동굴·협곡 위에 **껍데기**로 깔렸다.
    //   ③ 경계 급단차 24.3% (최대 7칸) — 부지 가장자리에서 우리 지면과 자연 지면이 뚝 끊겼다.
    //   그리고 물이 걸린 부지에서는: 잘린 강, 산 위의 웅덩이, 벽에서 새는 물.
    //
    // 뿌리는 하나다 — **조성기가 지형을 읽지 않았다.** 구 clearAndFlatten 은 cy 에 잔디를 깔고
    // 위를 지웠을 뿐 아래를 보지 않았고, 구 blendEdge 는 6칸 스커트에 (natural-cy)*d/7 선형 보간을
    // 했다 — 20칸 단차면 한 칸에 3칸씩 뛴다. 그것이 '급단차'다.
    //
    // v7.1 은 조성 전에 **부지를 측량**하고(Terrain), 그 측량 위에 네 패스를 돌린다:
    //   1. foundationSeal — 기초 봉인: 지면 아래 6칸만 **자연 자재**로 단단히 채운다. 그 아래 동굴은
    //      살려 둔다 (동굴은 세계의 자산 — 기연·은신처의 무대다). 봉인 밑이 32칸까지 허공이면
    //      (협곡·거대 공동) 4칸 격자로 돌기둥을 내려 받친다 — 메우지 않고 **받친다**.
    //   2. waterWorks — 수역 삼분(三分): 갇힌 웅덩이는 메우고 · 관통 수역(강·호수·바다)은 코어 밖에서
    //      **한 칸도 건드리지 않고** · 잘린 물기둥·공중의 물은 지운다. 물가에는 호안을 세워 새지 않게 한다.
    //   3. featherEdge — 경계 페더링: 담 밖 20칸 전이대에서 우리 지면(cy)과 자연 지면을 **한 칸에 한 칸씩**
    //      (1-립시츠) 잇는다. 계단도 벽도 없다 = 어느 방위에서 걸어 들어와도 막히지 않는다(검수 ③④).
    //   4. sealLaidFloors — 마감 안전망: 조성이 끝난 뒤 **인공 바닥 블록 밑 5칸**을 다시 훑어 공기를 메운다
    //      (검수 ①의 판정식 그대로 — 소품·접근로·부속이 나중에 놓은 바닥까지 남김없이 덮는다).
    //
    // 검수 정합(중요):
    //   · 인공 바닥 판정 자재(DIRT_PATH·COARSE_DIRT·GRAVEL·*_SLAB·*_STAIRS·*_BRICKS·…)를 **전이대에는
    //     쓰지 않는다**(safeSurface). 전이대는 '자연'이어야 검수 ③이 자연-자연 단차로 보고 세지 않으며,
    //     TownAudit ①⑧(길 폭·야간 광원)의 길 표본도 오염되지 않는다 (PATH 집합에 STONE·GRAVEL이 있다).
    //   · 봉인·메움은 전부 cy 아래 = TownAudit 의 노면 판정(cy 한 켜)·지붕 상자에 한 톨도 안 닿는다.
    //   · 재조성 결정론: 봉인·메움 자재는 자연 지면 화이트리스트(돌·흙·모래) 안이므로 두 번째 측량이
    //     같은 지면 높이를 읽는다. 전이대 표고는 1-립시츠 사영의 **고정점**이라 재조성해도 안 움직인다.
    // ══════════════════════════════════════════════════════════════════════════════════════════

    private static final int SITE_R = 62;                  // 조성 코어 반경 (평탄화 = 담 r=60 + 발치 2)
    private static final int FEATHER = 20;                 // 전이대 폭 — 검수 ③의 스캔 링(49~81)을 다 덮는다
    private static final int SPAN = SITE_R + FEATHER;      // 측량 반경 82
    private static final int MAX_STEP = 1;                 // 전이대 인접 칸 최대 높이차 — 1 = 걸어 오를 수 있다
    private static final int RELAX = 40;                   // 1-립시츠 완화 스윕 수 (결정론 — 난수 0)
    private static final int SEAL_DEPTH = 6;               // 기초 봉인 두께 (검수 ①: 바닥 밑 5칸까지 단단할 것)
    private static final int BAND_SEAL = 5;                // 전이대 봉인 두께
    private static final int VOID_PROBE = 32;              // 봉인 밑 공동 탐침 — 여기까지 허공이면 '심연'
    private static final int PIER_STEP = 4;                // 심연 위 돌기둥 격자
    private static final int PIER_MAX = 64;                // 돌기둥 최대 길이
    private static final int POND_MAX = 400;               // 웅덩이 상한(칸) — 넘으면 호수 = 보존 대상
    private static final int WATER_FILL_MAX = 14;          // 코어 수역 완전 메움 한계 수심 (넘으면 봉인만)
    private static final int SEAL_SCAN = 92;               // 마감 봉인 훑기 반경 (접근부 OUT_FAR 와 같다)

    private static final byte W_NONE = 0;
    private static final byte W_POND = 1;   // 부지 안에 완전히 갇힌 작은 물 — 메운다
    private static final byte W_KEEP = 2;   // 관통 수역·큰 물 — 코어 밖에서는 한 칸도 건드리지 않는다

    private static final int[][] DIR4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * 부지 측량표 — 조성 **전**의 원지형. 모든 지형 패스가 이 한 벌의 사실 위에서 돈다
     * (블록을 다시 읽지 않는다 = 앞 패스가 뒤 패스의 판단을 오염시키지 못한다).
     */
    private static final class Terrain {
        final int cx;
        final int cy;
        final int cz;
        final int side = 2 * SPAN + 1;
        final int[] ground;      // 자연 지면 y (MIN_VALUE = 32칸 안에 지면 없음 = 심연)
        final Material[] surf;   // 자연 지면 자재 (전이대 표층·봉인 자재의 근거)
        final int[] waterTop;    // 물(액체·얼음) 최상단 y (MIN_VALUE = 물 없음)
        final byte[] wclass;     // 수역 분류
        final int[] target;      // 최종 지표 y (MIN_VALUE = 손대지 않는다 — 보존 수역·심연)

        int coreWater;      // 코어 안 물 칸
        int coreKeepWater;  // 코어를 침범한 **관통·대수역** 칸 (조성 거부 사유 ②)
        int pondCells;      // 메운 웅덩이 칸
        int caveCols;       // 봉인이 공동을 덮은 열
        int voidCols;       // 봉인 밑 32칸이 통째로 허공인 열 (협곡·심연)
        int highWater;      // 수면이 마을 바닥(cy)보다 높은 보존 수역 칸 (거부 사유 ③)
        int reliefLo = Integer.MAX_VALUE;
        int reliefHi = Integer.MIN_VALUE;

        Terrain(int cx, int cy, int cz) {
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            int n = side * side;
            ground = new int[n];
            surf = new Material[n];
            waterTop = new int[n];
            wclass = new byte[n];
            target = new int[n];
        }

        int idx(int dx, int dz) {
            return (dx + SPAN) * side + (dz + SPAN);
        }

        boolean in(int dx, int dz) {
            return Math.abs(dx) <= SPAN && Math.abs(dz) <= SPAN;
        }

        static boolean core(int dx, int dz) {
            return Math.max(Math.abs(dx), Math.abs(dz)) <= SITE_R;
        }
    }

    // ─── 측량 ───

    /**
     * 부지 측량 — ±SPAN 의 모든 열에서 (자연 지면 y · 지면 자재 · 물 최상단)을 읽는다.
     *
     * <p>지면 판정은 naturalGroundY 와 같은 원리다(자연 지면 화이트리스트에 처음 닿는 y).
     * 다만 **물을 만나도 포기하지 않는다** — 물 밑의 바닥도 알아야 웅덩이를 메울 수 있기 때문이다.
     * 물의 최상단은 따로 기록해 수역 분류에 쓴다.
     */
    private static Terrain surveyTerrain(World world, int cx, int cy, int cz) {
        Terrain t = new Terrain(cx, cy, cz);
        for (int dx = -SPAN; dx <= SPAN; dx++) {
            for (int dz = -SPAN; dz <= SPAN; dz++) {
                int x = cx + dx, z = cz + dz;
                int i = t.idx(dx, dz);
                int top = Math.min(world.getHighestBlockYAt(x, z), world.getMaxHeight() - 1);
                int floor = Math.max(world.getMinHeight(), cy - 40);
                int g = Integer.MIN_VALUE;
                int wt = Integer.MIN_VALUE;
                Material sm = Material.DIRT;
                for (int y = top; y >= floor; y--) {
                    Material m = world.getBlockAt(x, y, z).getType();
                    if (wt == Integer.MIN_VALUE && WET.contains(m)) {
                        wt = y;                       // 물기둥 최상단 = 수면
                    }
                    if (NATURAL_GROUND.contains(m)) {
                        g = y;
                        sm = m;
                        break;                        // 자연 지면 — 여기가 '땅'이다
                    }
                }
                t.ground[i] = g;
                t.surf[i] = sm;
                t.waterTop[i] = wt;
                t.target[i] = Integer.MIN_VALUE;
                if (Terrain.core(dx, dz)) {
                    if (wt != Integer.MIN_VALUE) {
                        t.coreWater++;
                    }
                    if (g != Integer.MIN_VALUE) {
                        t.reliefLo = Math.min(t.reliefLo, g);
                        t.reliefHi = Math.max(t.reliefHi, g);
                    }
                }
            }
        }
        classifyWater(t);
        return t;
    }

    /**
     * 수역 삼분 — 물 칸을 4연결 성분으로 묶어 **웅덩이**와 **관통 수역**을 가른다 (검수·설계의 갈림길).
     *   · 성분이 코어(±62) 안에 완전히 갇혀 있고 POND_MAX 이하 → 웅덩이(W_POND). 메운다.
     *   · 코어 밖으로 한 칸이라도 나가거나 POND_MAX 를 넘으면 → 강·호수·바다(W_KEEP). **보존한다.**
     * 성분이 코어 경계를 넘나들면(관통 수역이 부지를 가로지르면) 그것은 **부지 선정의 실패**이지
     * 조성기가 물길을 메워 해결할 일이 아니다 — inspectSite 가 그 사실을 수치로 고발한다.
     */
    private static void classifyWater(Terrain t) {
        int n = t.side * t.side;
        boolean[] seen = new boolean[n];
        int[] stack = new int[n];
        int[] cells = new int[n];
        for (int dx = -SPAN; dx <= SPAN; dx++) {
            for (int dz = -SPAN; dz <= SPAN; dz++) {
                int i0 = t.idx(dx, dz);
                if (seen[i0] || t.waterTop[i0] == Integer.MIN_VALUE) {
                    continue;
                }
                int sp = 0, cn = 0;
                stack[sp++] = i0;
                seen[i0] = true;
                boolean outside = false;
                while (sp > 0) {
                    int c = stack[--sp];
                    cells[cn++] = c;
                    int ux = c / t.side - SPAN, uz = c % t.side - SPAN;
                    if (!Terrain.core(ux, uz)) {
                        outside = true;   // 코어 밖으로 이어진다 = 이 물은 마을의 것이 아니다
                    }
                    for (int[] d : DIR4) {
                        int nx = ux + d[0], nz = uz + d[1];
                        if (!t.in(nx, nz)) {
                            continue;
                        }
                        int j = t.idx(nx, nz);
                        if (seen[j] || t.waterTop[j] == Integer.MIN_VALUE) {
                            continue;
                        }
                        seen[j] = true;
                        stack[sp++] = j;
                    }
                }
                byte cls = (outside || cn > POND_MAX) ? W_KEEP : W_POND;
                for (int k = 0; k < cn; k++) {
                    t.wclass[cells[k]] = cls;
                    int c = cells[k];
                    int ux = c / t.side - SPAN, uz = c % t.side - SPAN;
                    if (cls == W_POND) {
                        t.pondCells++;
                    } else if (Terrain.core(ux, uz)) {
                        t.coreKeepWater++;   // 관통 수역이 부지를 침범했다 — 거부 사유
                    }
                    if (cls == W_KEEP && t.waterTop[c] > t.cy) {
                        t.highWater++;       // 수면이 마을 바닥보다 높다 — 거부 사유
                    }
                }
            }
        }
    }

    // ─── 부지 적합성 — 언제 조성이 거부되어야 하는가 ───

    /**
     * 부지 판정 결과. {@code ok()} 가 false 면 **여기에 마을을 세우면 안 된다**.
     *
     * <p>조성기는 스스로를 거부할 수 없다(호출자가 이미 좌표를 정했다). 그래서 판정을 **밖으로 낸다** —
     * MvtCommand·RemoteBuilder 가 조성 전에 {@link #inspectSite}를 불러 게이트를 걸면 된다.
     * WorldMap.fit 이 이미 water_pct·relief 로 후보를 채점하지만, fit 은 **표면만** 본다:
     * 지하 공동(협곡)과 관통 수역의 '관통 여부'는 여기서만 드러난다.
     */
    record SiteVerdict(boolean ok, List<String> reasons, List<String> stats) {
    }

    /**
     * 부지 검사 — 조성 없이 측량만 해서 채점한다 (콘솔에서 안전하게 호출 가능).
     *
     * <p>거부 기준 (전부 객관 수치):
     * <ul>
     *   <li>① 코어 물 비율 &gt; 15% — 부지의 1/6 이상이 물이면 그것은 마을 터가 아니라 물가다.
     *       (사용자 예시 "40%가 물이면 짓지 마라" 보다 엄격하다: 15%만 돼도 강 하나가 부지를 가른다.)</li>
     *   <li>② 관통 수역(강·호수·바다)이 코어 안으로 들어옴 — 건물·길의 좌표는 상수표로 못 박혀 있어
     *       물길을 비켜 갈 수 없다. 물길을 메우는 것은 '조성'이 아니라 '파괴'다 → 다른 자리로 옮겨라.</li>
     *   <li>③ 보존 수역의 수면이 마을 바닥(cy)보다 높음 — 마을이 수면 아래다. 호안을 쌓아 막는다 해도
     *       그것은 마을이 아니라 제방 안의 웅덩이다.</li>
     *   <li>④ 코어 기복(자연 지면 최고−최저) &gt; 32 — 절벽·산비탈. 평탄화가 산을 절반 깎는다.</li>
     *   <li>⑤ 심연 비율 &gt; 5% — 부지 아래 32칸이 통째로 허공인 열(대협곡·거대 공동)이 5%를 넘으면
     *       마을은 다리 위에 선다. 돌기둥으로 받칠 수 있는 한계다.</li>
     * </ul>
     * 경고(조성은 하되 기록):
     * <ul>
     *   <li>· 코어 기복 &gt; 16 — 평탄화가 크다 (자연이 많이 상한다).</li>
     *   <li>· 동굴 관통 열 비율 — 봉인이 덮은 동굴의 양. 동굴 자체는 살아 있다 (아래 참조).</li>
     * </ul>
     */
    static SiteVerdict inspectSite(World world, int cx, int cy, int cz) {
        Terrain t = surveyTerrain(world, cx, cy, cz);
        probeUnderground(world, t);   // 봉인 없이 탐침만 (블록을 쓰지 않는다)
        return verdict(t);
    }

    /** 지하 탐침 — 봉인 대상(공동)과 심연을 **세기만** 한다 (조성 없이 판정하려고 분리했다). */
    private static void probeUnderground(World world, Terrain t) {
        t.caveCols = 0;
        t.voidCols = 0;
        for (int dx = -SITE_R; dx <= SITE_R; dx++) {
            for (int dz = -SITE_R; dz <= SITE_R; dz++) {
                int x = t.cx + dx, z = t.cz + dz;
                boolean cave = false;
                for (int d = 1; d <= SEAL_DEPTH; d++) {
                    if (!firm(world.getBlockAt(x, t.cy - d, z).getType())) {
                        cave = true;
                        break;
                    }
                }
                if (cave) {
                    t.caveCols++;
                }
                if (voidBelow(world, x, t.cy - SEAL_DEPTH, z)) {
                    t.voidCols++;
                }
            }
        }
    }

    private static SiteVerdict verdict(Terrain t) {
        int coreCells = (2 * SITE_R + 1) * (2 * SITE_R + 1);
        double waterPct = 100.0 * t.coreWater / coreCells;
        double voidPct = 100.0 * t.voidCols / coreCells;
        double cavePct = 100.0 * t.caveCols / coreCells;
        int relief = (t.reliefHi == Integer.MIN_VALUE) ? 0 : t.reliefHi - t.reliefLo;

        List<String> stats = new java.util.ArrayList<>();
        stats.add(String.format("물 %.1f%% (%d칸 · 웅덩이 %d · 관통수역 침범 %d)",
                waterPct, t.coreWater, t.pondCells, t.coreKeepWater));
        stats.add(String.format("기복 %d칸 (자연 지면 %d~%d)", relief,
                t.reliefLo == Integer.MAX_VALUE ? 0 : t.reliefLo,
                t.reliefHi == Integer.MIN_VALUE ? 0 : t.reliefHi));
        stats.add(String.format("지하 — 동굴 관통 %.1f%% (%d열) · 심연 %.1f%% (%d열)",
                cavePct, t.caveCols, voidPct, t.voidCols));
        stats.add("수면이 마을 바닥보다 높은 보존 수역 " + t.highWater + "칸");

        List<String> bad = new java.util.ArrayList<>();
        if (waterPct > 15.0) {
            bad.add(String.format("코어 물 %.1f%% > 15%% — 마을 터가 아니라 물가다", waterPct));
        }
        if (t.coreKeepWater > 0) {
            bad.add("관통 수역이 부지를 " + t.coreKeepWater + "칸 가로지른다 — 물길을 메우지 말고 부지를 옮겨라");
        }
        if (t.highWater > 0) {
            bad.add("보존 수역의 수면이 마을 바닥보다 높다 (" + t.highWater + "칸) — 마을이 수면 아래다");
        }
        if (relief > 32) {
            bad.add("코어 기복 " + relief + "칸 > 32 — 절벽·산비탈이다 (평탄화가 산을 깎는다)");
        }
        if (voidPct > 5.0) {
            bad.add(String.format("부지 밑 심연 %.1f%% > 5%% — 마을이 다리 위에 선다", voidPct));
        }
        return new SiteVerdict(bad.isEmpty(), bad, stats);
    }

    /** 조성 로그 — 부지가 부적합해도 조성기는 멈출 수 없다(호출자가 정한다). 대신 **기록으로 고발한다**. */
    private static void logSite(Terrain t) {
        SiteVerdict v = verdict(t);
        for (String s : v.stats()) {
            Bukkit.getLogger().info("[혼천/지형] " + s);
        }
        if (v.ok()) {
            Bukkit.getLogger().info("[혼천/지형] 부지 적합 — 조성한다");
            return;
        }
        for (String s : v.reasons()) {
            Bukkit.getLogger().warning("[혼천/지형] ✗ " + s);
        }
        Bukkit.getLogger().warning("[혼천/지형] 부지 부적합 — 그럼에도 조성을 강행한다 "
                + "(코어 안의 물은 메우고 호안을 세운다 = 최후 수단). 부지를 옮기는 것이 옳다.");
    }

    // ─── 1. 기초 봉인 — 바닥이 뚫리지 않게, 그러나 동굴은 살려 둔다 ───

    /** 단단한가 — 공기·액체·수초·풀은 아니다. 봉인은 이 술어가 false 인 칸만 메운다 (자연 암반은 그대로 둔다). */
    private static boolean firm(Material m) {
        return m.isSolid() && !WET.contains(m);
    }

    /** 봉인 밑이 심연인가 — VOID_PROBE 칸을 내려가도 고체가 없다 (대협곡·거대 공동·허공) */
    private static boolean voidBelow(World world, int x, int yFrom, int z) {
        int bottom = Math.max(world.getMinHeight(), yFrom - VOID_PROBE);
        for (int y = yFrom - 1; y >= bottom; y--) {
            if (firm(world.getBlockAt(x, y, z).getType())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 표토 — 봉인 위쪽 2켜의 자재. **그 자리의 자연 자재를 따른다** (회벽·판재로 지하를 채우면 지하가 우스워진다).
     * 모래밭 밑은 모래, 흙밭 밑은 흙, 그 밖은 흙 — 파 보면 그 땅의 단면이 나온다.
     */
    private static Material subsoil(Material surface) {
        return switch (surface) {
            case SAND, SANDSTONE -> Material.SAND;
            case RED_SAND, RED_SANDSTONE -> Material.RED_SAND;
            case CLAY, TERRACOTTA -> Material.CLAY;
            // ★ GRAVEL 을 자갈로 되돌리지 않는다 — 자갈은 **노면 자재**다 (TownAudit.PATH · TerrainAudit.manMadeFloor).
            //   봉인 자재가 지표(cy)에 한 켜라도 얹히면 그 칸이 '길'로 세어져 야간 광원 표본이 오염된다.
            case GRAVEL, STONE, ANDESITE, DIORITE, GRANITE, TUFF, CALCITE, DEEPSLATE,
                 SNOW_BLOCK, PODZOL, MYCELIUM, MOSS_BLOCK -> Material.DIRT;
            default -> Material.DIRT;
        };
    }

    /**
     * 기반암 — 봉인 3켜 밑부터는 암반이다. y&lt;0 은 심층암 (바닐라의 지층 규칙을 따른다).
     *
     * <p>★ STONE 이 아니라 **TUFF** 인 이유 (v7.1 회귀 수리): TownAudit.PATH 에 STONE 이 들어 있다.
     * 전이대에서 지표가 cy+3 이상으로 올라간 열은 봉인 3켜째가 정확히 **y = cy** 에 떨어지고,
     * TownAudit 의 노면 판정은 **cy 한 켜만** 읽으므로 그 돌이 '길'로 세어졌다 — 등롱이 설 수 없는
     * 지하의 돌이 길 표본에 들어와 **야간 암흑 14.1% → 18%** 의 회귀를 만들었다.
     * 응회암(TUFF)은 PATH·manMadeFloor 어느 집합에도 없고, 자연 지면 화이트리스트 안이라
     * 재조성 결정론도 그대로다. 지하를 파면 응회암층이 나온다 — 바닐라에도 있는 지층이다.
     */
    private static Material bedrock(int y) {
        return y < 0 ? Material.DEEPSLATE : Material.TUFF;
    }

    /**
     * 전이대 표층 자재 — 자연 자재를 쓰되 **검수의 '인공 바닥' 자재는 피한다**.
     *
     * <p>검수 ③(경계 급단차)은 "사람이 깐 바닥과 자연 지면이 만나는 자리"만 센다. 전이대가 인공 자재로
     * 깔리면 전이대 전체가 그 경계가 되어 자연의 잔주름까지 우리 죄로 세어진다. 또 TownAudit 은
     * STONE·GRAVEL·COBBLESTONE 을 **길 자재(PATH)** 로 보므로, 담 밖 3칸(스캔 ±65)에 돌바닥이 깔리면
     * 길 폭 히스토그램과 야간 광원 표본이 오염된다 (어두운 '길'이 늘어 야간 암흑 위반이 난다).
     *
     * <p>그래서 돌 계열은 TUFF(자연 응회암 — 두 집합 어디에도 없다), 자갈·거친 흙은 흙으로 갈아 놓는다.
     * 색은 거의 같고 검수는 조용하다.
     */
    private static Material safeSurface(Material natural) {
        return switch (natural) {
            case STONE, ANDESITE, COBBLESTONE, SMOOTH_STONE, STONE_BRICKS -> Material.TUFF;
            case GRAVEL, COARSE_DIRT -> Material.DIRT;
            case TERRACOTTA -> Material.CLAY;
            case GRASS_BLOCK, DIRT, ROOTED_DIRT, PODZOL, MYCELIUM, MOSS_BLOCK, CLAY,
                 SAND, RED_SAND, SANDSTONE, RED_SANDSTONE, DIORITE, GRANITE, TUFF,
                 DEEPSLATE, CALCITE, SNOW_BLOCK -> natural;
            default -> Material.GRASS_BLOCK;
        };
    }

    /**
     * 기초 봉인 — 코어(±62)의 지면 아래 6칸을 자연 자재로 단단히 채운다.
     *
     * <p>【왜 6칸인가】 검수 ①은 "인공 바닥 블록 **아래 2~5칸** 중 3칸 이상이 공기면 위반"으로 잰다.
     * 즉 바닥 밑 5칸까지는 단단해야 한다. 우리 바닥은 cy(노면·마당) 또는 cy+1(기단 반 칸·짚단·걸상)에
     * 앉으므로, cy-1..cy-6 을 채우면 두 경우 다 5칸 여유를 확보한다 (cy+1 기준 cy-1..cy-4 · cy 기준 cy-2..cy-5).
     *
     * <p>【왜 통째로 메우지 않는가】 동굴은 세계의 자산이다 — 기연·은신처·도적 소굴의 무대다. 마을이
     * 편하자고 지하를 다 메우면 세계가 얇아진다. 봉인은 **바닥 두께 6칸의 판** 하나일 뿐, 그 아래 공동은
     * 그대로 살아 있다 (마을 밖 어디선가 뚫고 들어오면 마을 밑에 도달한다 — 그것이 우리가 원하는 세계다).
     *
     * <p>【심연은 메우지 않고 받친다】 봉인 밑 32칸까지 고체가 없으면(대협곡·거대 공동) 봉인 판이 허공에
     * 뜬다. 그 자리는 4칸 격자로 **돌기둥**을 내려 첫 고체까지 받친다 (최대 64칸). 협곡은 협곡대로 남고,
     * 그 위를 마을이 다리처럼 건넌다 — 메우는 것보다 정직하고, 검수 ⑤(부유 블록)도 통과한다.
     *
     * <p>【자재】 위 2켜 = 그 열의 자연 표토(subsoil: 흙·모래·점토), 그 아래 = 암반(돌/심층암).
     * 전부 자연 지면 화이트리스트 안이라 **재조성 때 측량이 같은 지면 높이를 읽는다** (결정론 불변).
     */
    private static void foundationSeal(World world, Terrain t) {
        t.caveCols = 0;
        t.voidCols = 0;
        for (int dx = -SITE_R; dx <= SITE_R; dx++) {
            for (int dz = -SITE_R; dz <= SITE_R; dz++) {
                int x = t.cx + dx, z = t.cz + dz;
                // v7.2 — 봉인은 **그 열의 지표 밑**을 채운다 (cy 가 아니다). 기복이 들어왔으므로
                //   cy 밑을 채우면 솟은 자리(lift +2)의 발밑이 두 칸 빈다 = 검수 ①의 껍데기.
                int g = t.target[t.idx(dx, dz)];
                Material sub = subsoil(t.surf[t.idx(dx, dz)]);
                boolean cave = false;
                for (int d = 1; d <= SEAL_DEPTH; d++) {
                    int y = g - d;
                    Block b = world.getBlockAt(x, y, z);
                    if (firm(b.getType())) {
                        continue;                     // 자연 암반·흙 — 손대지 않는다
                    }
                    cave = true;                      // 공기·물·용암 = 동굴·수역·협곡
                    b.setType(d <= 2 ? sub : bedrock(y));
                }
                if (cave) {
                    t.caveCols++;
                }
                if (voidBelow(world, x, g - SEAL_DEPTH, z)) {
                    t.voidCols++;
                    if (Math.floorMod(x, PIER_STEP) == 0 && Math.floorMod(z, PIER_STEP) == 0) {
                        pier(world, x, g - SEAL_DEPTH, z);
                    }
                }
            }
        }
    }

    /** 돌기둥 — 심연 위의 봉인 판을 첫 고체까지 받친다 (협곡을 메우지 않는다 · 부유 블록 0) */
    private static void pier(World world, int x, int yFrom, int z) {
        int bottom = Math.max(world.getMinHeight() + 1, yFrom - PIER_MAX);
        for (int y = yFrom - 1; y >= bottom; y--) {
            Block b = world.getBlockAt(x, y, z);
            if (firm(b.getType())) {
                return;                                // 바닥에 닿았다
            }
            b.setType(bedrock(y));
        }
    }

    // ─── 2. 수역 — 메울 물과 비켜설 물을 가른다 ───

    /**
     * 수역 처리 — 세 갈래.
     * <ol>
     *   <li>【웅덩이】 코어 안에 완전히 갇힌 작은 물(≤400칸) → 바닥부터 지면까지 자연 자재로 메운다.
     *       산 위에 고인 물·마당 한복판의 물웅덩이는 조성의 실패다.</li>
     *   <li>【관통 수역】 강·호수·바다 → **코어 밖에서는 한 칸도 건드리지 않는다.** 평탄화·페더링·지면·
     *       소품·나무 패스가 전부 물 칸을 비켜 간다 (featherEdge 는 waterTop 이 있는 열을 통째로 건너뛰고,
     *       freeCell 은 지면 자재 화이트리스트로 이미 물을 거른다). 길은 널다리로 건넌다.
     *       코어 **안**으로 들어온 관통 수역은 메울 수밖에 없다 — 상수표로 못 박힌 건물·길이 물길을 비켜
     *       설 수 없기 때문이다. 그것은 조성기의 한계가 아니라 **부지 선정의 실패**이고, inspectSite 가
     *       그 사실을 거부 사유로 고발한다 (그래도 강행되면 메우고 경고를 남긴다 = 최후 수단).</li>
     *   <li>【잘린 물】 우리가 지형을 깎아 생긴 물기둥·공중의 물·벽에서 새는 물 → 지운다.
     *       흐르는 물이 벽에서 새는 것은 조성의 실패다.</li>
     * </ol>
     * 그리고 **호안(護岸)** — 보존 수역에 맞닿은 우리 땅이 수면보다 낮으면 그 열을 수면까지 자연 자재로
     * 쌓아 올린다. 물이 마을로 새 들어오는 길을 자재로 막는다 (스위치가 아니라 지형으로).
     *
     * <p>세 패스는 build() 에서 각각 다른 시점에 돈다 (한 덩이로 묶으면 순서가 틀어진다):
     * fillCoreWater 는 페더링 **전**(코어 지표를 확정해야 한다), shoreBank 는 페더링 **후**(물가의 최종
     * 지표를 알아야 한다), strayWater 는 맨 **끝**(모든 지표가 확정된 뒤에야 '지표 위의 물'을 판정할 수 있다).
     */

    /** 코어 안의 물 — 웅덩이든 관통 수역이든 **마을 바닥 아래로는 물이 없어야 한다**. 바닥부터 메운다. */
    private static void fillCoreWater(World world, Terrain t) {
        int cy = t.cy;
        for (int dx = -SITE_R; dx <= SITE_R; dx++) {
            for (int dz = -SITE_R; dz <= SITE_R; dz++) {
                int i = t.idx(dx, dz);
                if (t.waterTop[i] == Integer.MIN_VALUE) {
                    continue;
                }
                int x = t.cx + dx, z = t.cz + dz;
                int bed = t.ground[i];
                int g = t.target[i];   // v7.2 — 지표는 고도장이 정한다 (cy 평면이 아니다)
                // 수심이 너무 깊으면(호수 한복판) 전부 메우는 것은 산을 옮기는 짓이다 — 봉인(6칸)만 믿고
                // 그 아래 물은 **밀폐된 채로 남긴다** (고체 뚜껑 밑의 물은 새지 않는다). 이 부지는 어차피
                // inspectSite 가 거부한다.
                int from = (bed == Integer.MIN_VALUE || g - bed > WATER_FILL_MAX)
                        ? g - SEAL_DEPTH : bed + 1;
                for (int y = from; y <= g - 1; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (!firm(b.getType())) {
                        b.setType(y >= g - 2 ? subsoil(t.surf[i]) : bedrock(y));
                    }
                }
                world.getBlockAt(x, g, z).setType(Material.GRASS_BLOCK);   // 평탄화와 같은 지면
            }
        }
    }

    /**
     * 잘린 물·공중의 물 청소 — 보존 수역이 아닌 모든 열에서, **최종 지표 위의 물기둥**을 지운다.
     * 벽에 붙은 물·허공의 물·흐르는 물은 조성이 만든 것이므로 조성이 치운다.
     * 보존 수역(W_KEEP · 코어 밖)은 한 칸도 건드리지 않는다 — 강은 강대로 흐른다.
     */
    private static void strayWater(World world, Terrain t) {
        int cy = t.cy;
        for (int dx = -SPAN; dx <= SPAN; dx++) {
            for (int dz = -SPAN; dz <= SPAN; dz++) {
                int i = t.idx(dx, dz);
                boolean keep = t.wclass[i] == W_KEEP && !Terrain.core(dx, dz);
                if (keep) {
                    continue;                    // 강·호수·바다 — 보존
                }
                int x = t.cx + dx, z = t.cz + dz;
                // v7.2 — 코어의 지표도 target 이다 (고도장이 채워 뒀다). cy 를 쓰면 솟은 자리 위의 물을 놓친다
                int base = t.target[i] != Integer.MIN_VALUE ? t.target[i] : t.ground[i];
                if (base == Integer.MIN_VALUE) {
                    base = cy;
                }
                for (int y = base + 1; y <= base + 20; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (m == Material.WATER || m == Material.LAVA || WET.contains(m)) {
                        b.setType(Material.AIR);   // 지표 위의 물 = 잘린 물기둥·고인 물
                    } else if (b.getBlockData() instanceof org.bukkit.block.data.Waterlogged w
                            && w.isWaterlogged()) {
                        w.setWaterlogged(false);   // 물먹은 계단·울타리 = 벽에서 새는 물의 정체
                        b.setBlockData(w, false);
                    }
                }
            }
        }
    }

    /**
     * 호안 — 보존 수역에 맞닿은 우리 땅이 수면보다 낮으면 수면까지 쌓아 올린다.
     * 물은 스위치로 막는 것이 아니라 **지형으로** 막는다. (수면이 마을 바닥보다 높으면 이 호안이
     * 마을을 둘러싼 제방이 된다 — 그런 부지는 inspectSite 가 이미 거부한 부지다.)
     */
    private static void shoreBank(World world, Terrain t) {
        for (int dx = -SPAN; dx <= SPAN; dx++) {
            for (int dz = -SPAN; dz <= SPAN; dz++) {
                int i = t.idx(dx, dz);
                if (t.wclass[i] != W_KEEP || Terrain.core(dx, dz)) {
                    continue;
                }
                int wt = t.waterTop[i];
                for (int ox = -1; ox <= 1; ox++) {
                    for (int oz = -1; oz <= 1; oz++) {
                        int nx = dx + ox, nz = dz + oz;
                        if ((ox == 0 && oz == 0) || !t.in(nx, nz)) {
                            continue;
                        }
                        int j = t.idx(nx, nz);
                        if (t.waterTop[j] != Integer.MIN_VALUE) {
                            continue;                     // 물 옆의 물 — 호안이 아니다
                        }
                        int tg = Terrain.core(nx, nz) ? t.cy : t.target[j];
                        if (tg == Integer.MIN_VALUE || tg >= wt) {
                            continue;                     // 이미 수면 위 = 이미 뭍이다
                        }
                        int x = t.cx + nx, z = t.cz + nz;
                        for (int y = tg + 1; y <= wt; y++) {
                            world.getBlockAt(x, y, z).setType(
                                    y == wt ? safeSurface(t.surf[j]) : subsoil(t.surf[j]));
                        }
                        if (!Terrain.core(nx, nz)) {
                            t.target[j] = wt;             // 지표가 올라갔다 — 봉인·검수가 이 값을 쓴다
                        }
                    }
                }
            }
        }
    }

    // ─── 3. 경계 페더링 — 바깥에서 걸어 들어올 때 계단도 벽도 만나지 않는다 ───

    /**
     * 전이대(feathering) — 담 밖 20칸에 걸쳐 우리 지면(cy)과 자연 지형을 **한 칸에 한 칸씩** 잇는다.
     *
     * <p>【구 blendEdge 가 왜 실패했나】 6칸 스커트에 선형 보간(target = cy + (natural-cy)*d/7)을 썼다.
     * 자연 지형이 20칸 높으면 한 걸음에 3칸씩 뛴다 — 검수 ③이 잰 "최대 7칸 · 24.3%" 가 바로 그 계단이다.
     * 보간의 문제가 아니라 **폭의 문제**였다: 6칸으로는 산을 받아낼 수 없다.
     *
     * <p>【알고리즘 — 1-립시츠 사영】
     * <ol>
     *   <li>초안: 링 거리 d(1..20) 마다 target = clamp(자연지면, cy−d, cy+d).
     *       코어 경계(cy)에서 반경 방향으로 한 칸에 한 칸씩만 오르내리게 가둔다 (원뿔 회랑).</li>
     *   <li>완화: 40번의 결정론 스윕으로 **인접 칸 높이차 ≤ 1** 을 강제한다 (코어 링 cy 는 고정 경계값).
     *       회랑 밖으로 못 나가게 매 스윕 다시 clamp — 발산 없이 수렴하고, 재조성해도 같은 고정점이다.</li>
     *   <li>적용: 자연보다 높으면 표토로 쌓고, 낮으면 깎고, 표층은 그 열의 자연 자재(safeSurface).
     *       깎을 때는 그 위의 나무·풀도 함께 걷어낸다 (허공에 뜬 잎 = 검수 ⑤ 부유 블록).</li>
     * </ol>
     * 결과 보장: 전이대의 어떤 인접 두 칸도 높이차 1 이하 → **모든 방위에서 걸어 들어올 수 있다**(검수 ④),
     * 한 걸음 3칸 도약은 구조적으로 불가능하다(검수 ③). 대각선도 최대 2칸 (3칸 미만).
     *
     * <p>물 칸은 통째로 건너뛴다 — 강을 깎지도 메우지도 않는다. 물가의 단차는 shoreBank 가 맡는다.
     */
    private static void featherEdge(World world, Terrain t) {
        int cy = t.cy;
        // ① 초안 — 원뿔 회랑
        for (int dx = -SPAN; dx <= SPAN; dx++) {
            for (int dz = -SPAN; dz <= SPAN; dz++) {
                if (Terrain.core(dx, dz)) {
                    continue;                       // 코어는 cy 고정 (clearAndFlatten 이 넣었다)
                }
                int i = t.idx(dx, dz);
                if (t.waterTop[i] != Integer.MIN_VALUE || t.ground[i] == Integer.MIN_VALUE) {
                    t.target[i] = Integer.MIN_VALUE;   // 물·심연 — 손대지 않는다
                    continue;
                }
                int d = Math.max(Math.abs(dx), Math.abs(dz)) - SITE_R;   // 1..FEATHER
                int lo = cy - MAX_STEP * d, hi = cy + MAX_STEP * d;
                t.target[i] = Math.max(lo, Math.min(hi, t.ground[i]));
            }
        }
        // ② 완화 — 1-립시츠 (결정론: 고정 순서·고정 횟수. 난수 0)
        for (int k = 0; k < RELAX; k++) {
            for (int dx = -SPAN; dx <= SPAN; dx++) {
                for (int dz = -SPAN; dz <= SPAN; dz++) {
                    if (Terrain.core(dx, dz)) {
                        continue;
                    }
                    int i = t.idx(dx, dz);
                    if (t.target[i] == Integer.MIN_VALUE) {
                        continue;
                    }
                    int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                    for (int[] dir : DIR4) {
                        int nx = dx + dir[0], nz = dz + dir[1];
                        if (!t.in(nx, nz)) {
                            continue;
                        }
                        int nv = Terrain.core(nx, nz) ? cy : t.target[t.idx(nx, nz)];
                        if (nv == Integer.MIN_VALUE) {
                            continue;               // 물·심연 이웃은 제약이 아니다 (물가는 벼랑이어도 된다)
                        }
                        lo = Math.min(lo, nv);
                        hi = Math.max(hi, nv);
                    }
                    if (lo == Integer.MAX_VALUE) {
                        continue;
                    }
                    int v = t.target[i];
                    if (v < hi - MAX_STEP) {
                        v = hi - MAX_STEP;          // 이웃보다 너무 낮다 — 끌어올린다
                    }
                    if (v > lo + MAX_STEP) {
                        v = lo + MAX_STEP;          // 이웃보다 너무 높다 — 깎는다 (충돌 시 '깎기'가 이긴다)
                    }
                    int d = Math.max(Math.abs(dx), Math.abs(dz)) - SITE_R;
                    t.target[i] = Math.max(cy - MAX_STEP * d, Math.min(cy + MAX_STEP * d, v));
                }
            }
        }
        // ③ 적용
        for (int dx = -SPAN; dx <= SPAN; dx++) {
            for (int dz = -SPAN; dz <= SPAN; dz++) {
                if (Terrain.core(dx, dz)) {
                    continue;
                }
                int i = t.idx(dx, dz);
                int tg = t.target[i];
                if (tg == Integer.MIN_VALUE) {
                    continue;
                }
                shapeColumn(world, t.cx + dx, t.cz + dz, tg, t.surf[i], BAND_SEAL);
            }
        }
    }

    /**
     * 한 열을 목표 표고로 성형한다 — 깎고(위를 비우고) · 쌓고(밑을 채우고) · 표층을 덮고 · 봉인한다.
     * 지형 패스와 접근로가 함께 쓴다 (같은 규칙 = 같은 보장).
     */
    private static void shapeColumn(World world, int x, int z, int tg, Material natural, int seal) {
        int top = Math.min(world.getHighestBlockYAt(x, z), world.getMaxHeight() - 1);
        for (int y = Math.max(tg + 1, world.getMinHeight()); y <= Math.max(top, tg + 6); y++) {
            Block b = world.getBlockAt(x, y, z);
            if (!b.getType().isAir()) {
                b.setType(Material.AIR);           // 깎기 — 나무·풀·바위 윗도리를 함께 걷는다 (부유 블록 0)
            }
        }
        world.getBlockAt(x, tg, z).setType(safeSurface(natural));
        Material sub = subsoil(natural);
        for (int d = 1; d <= seal; d++) {          // 쌓기 + 봉인 — 밑이 비면 껍데기 바닥이 된다 (검수 ①)
            int y = tg - d;
            Block b = world.getBlockAt(x, y, z);
            if (!firm(b.getType())) {
                b.setType(d <= 2 ? sub : bedrock(y));
            }
        }
    }

    // ─── 4. 마감 봉인 — 나중에 놓인 바닥까지 남김없이 ───

    /**
     * 검수 ①의 판정식 그대로 되짚는 안전망 — **인공 바닥 블록 밑 5칸의 공기를 메운다**.
     *
     * <p>기초 봉인(코어)·전이대 봉인이 지면을 덮지만, 조성은 그 뒤로도 바닥을 놓는다: 접근로(관도·산길),
     * 담 밖 소품(짚단·통나무 걸상), 폐사당 참배 계단, 사냥터·나루의 판재. 그것들은 **자연 지형 위에**
     * 앉으므로 그 밑이 동굴이면 그대로 껍데기가 된다. 이 패스가 마지막에 한 번 더 훑는다.
     *
     * <p>지붕을 건드리지 않는 법: 열의 **지반면**(코어=cy · 전이대=target · 그 밖=자연 지면)을 먼저 정하고
     * 그 ±2 안에서만 바닥을 찾는다. 지붕은 cy+6 이상이므로 이 창에 들어오지 않는다 (검수도 같은 이유로
     * "지표가 조성 지면 ±4 안일 때만" 센다).
     *
     * <p>물 위의 널다리는 밑이 물이지 공기가 아니다 → 메우지 않는다 (강을 막지 않는다).
     */
    private static void sealLaidFloors(World world, Terrain t) {
        int cy = t.cy;
        for (int dx = -SEAL_SCAN; dx <= SEAL_SCAN; dx++) {
            for (int dz = -SEAL_SCAN; dz <= SEAL_SCAN; dz++) {
                int x = t.cx + dx, z = t.cz + dz;
                int plane;
                Material nat = Material.DIRT;
                if (t.in(dx, dz)) {
                    int i = t.idx(dx, dz);
                    nat = t.surf[i];
                    plane = t.target[i];   // v7.2 — 코어도 target (고도장) · 전이대도 target
                    if (plane == Integer.MIN_VALUE) {
                        continue;                  // 보존 수역·심연 — 바닥을 놓지 않았다
                    }
                } else {
                    plane = outsideGroundY(world, x, z);
                    if (plane == Integer.MIN_VALUE) {
                        continue;                  // 물 위 (널다리) — 밑이 공기가 아니다
                    }
                }
                // 창은 지반면 ±1 뿐이다 — 넓히면 실내 소품(cy+2 의 반 블록·계단)을 '바닥'으로 오인해
                // 그 밑의 **실내 공기**를 흙으로 메운다 (방이 흙으로 찬다). 우리 바닥은 cy·cy+1 에만 앉는다.
                for (int y = plane + 1; y >= plane - 1; y--) {
                    if (!laidFloor(world.getBlockAt(x, y, z).getType())) {
                        continue;
                    }
                    for (int d = 1; d <= 5; d++) { // 검수: 바닥 밑 2~5칸 중 3칸 이상 공기 = 위반
                        Block b = world.getBlockAt(x, y - d, z);
                        if (b.getType().isAir()) {
                            b.setType(d <= 2 ? subsoil(nat) : bedrock(y - d));
                        }
                    }
                    break;                         // 그 열의 최상단 바닥 하나면 족하다 (밑은 이제 단단하다)
                }
            }
        }
    }

    /**
     * 「사람이 깐 바닥」 판정 — TerrainAudit 과 **같은 자재 목록**을 쓴다 (검수가 세는 것을 우리가 센다).
     * 목록이 갈리면 보장이 갈린다: 검수기 쪽이 바뀌면 이 술어도 따라 바꿔라.
     */
    private static boolean laidFloor(Material m) {
        if (m == Material.DIRT_PATH || m == Material.POLISHED_ANDESITE || m == Material.COARSE_DIRT
                || m == Material.GRAVEL || m == Material.HAY_BLOCK || m == Material.STONE_BRICKS
                || m == Material.SMOOTH_STONE || m == Material.FARMLAND) {
            return true;
        }
        String n = m.name();
        return n.endsWith("_PLANKS") || n.endsWith("_BRICKS") || n.endsWith("_TILES")
                || n.endsWith("_SLAB") || n.endsWith("_STAIRS") || n.endsWith("_TERRACOTTA")
                || n.contains("COBBLESTONE");
    }

    // ─── v6.2 ① 지면 — 향촌은 사람이 밟고 사는 땅이다 ───
    //
    // 조감도에서 담장 안이 온통 초록이었다. 길만 흙이고 나머지가 잔디면 그건 마을이 아니라 초원이다.
    // groundCover 는 담장 안(±59) 지표 전체를 좌표 해시로 다진 흙 조직으로 갈아엎는다.
    // 이 패스는 광장·길·담장·건물보다 **먼저** 돈다 — 뒤에 오는 것들이 제 자리를 덮어쓴다 (충돌 0의 구조적 보장).
    // 자재 선택 규칙: DIRT_PATH·GRAVEL 은 '길'의 자재다. 일반 지면에 뿌리면 길과 마당의 구분이 사라진다.

    /**
     * v6.5 ② 【줄무늬 파괴】 좌표 해시 — 선형식 `x*a + z*b` 는 **반드시 사선/줄무늬를 만든다**.
     * 그 식의 등고선(x*a + z*b = const)이 직선이기 때문이다: 마을 전체가 같은 기울기의 줄로 덮인다.
     * 조감도에서 잡초(초록)와 잔디(초록)가 세로줄로 반복돼 마을을 지배한 것이 이 줄무늬였다.
     *
     * <p>해법은 **비선형 혼합**이다 — 곱셈으로 비트를 섞고 오른쪽 시프트로 상위 비트를 하위로 되접는다
     * (xorshift-multiply). 등고선이 직선이 아니게 되므로 어떤 방향으로도 줄이 서지 않는다.
     * 난수가 아니다: 좌표만의 순수 함수 = 같은 (x,z) 면 영원히 같은 값 (재조성 결정론 불변).
     */
    private static int noise(int x, int z) {
        int h = x * 374761393 + z * 668265263;   // 서로 소인 큰 소수 — 격자 정렬을 깬다
        h = (h ^ (h >>> 13)) * 1274126177;       // 곱셈 혼합 (비선형)
        h ^= h >>> 16;
        return h;
    }

    /** 좌표 해시 [0, n) — noise 의 결과를 구간에 접는다 */
    private static int hash(int x, int z, int n) {
        return Math.floorMod(noise(x, z), n);
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  v7.2 【마을의 기복】 — 책상 위의 마을을 땅 위로 내린다
    //
    //  사용자 피드백: "마을이 평지일 수도 있지만 조금의 높낮이 차이가 있을 수도 있다.
    //     너무 평지로써의 획일감을 주지 말고 변주를 해 달라."
    //
    //  v7.1 은 코어(±62)를 **cy 한 평면**으로 골랐다. 그래서 마을이 책상 위에 놓인 디오라마였다.
    //
    //  【기복이 설 수 없는 자리 — 검수가 그은 선】
    //    TownAudit 은 노면을 **cy 한 켜만** 읽는다 (pathGrid: getBlockAt(cx+dx, cy, cz+dz)).
    //    야간 광원도 **cy+1** 한 켜에서 잰다. 즉 길·골목·소로·광장의 노면이 cy 를 떠나는 순간
    //    그 칸은 검수의 눈에서 **사라진다** — "길 폭 0 · 골목 폭 0 · 길 없음".
    //    그러므로 <b>걷는 길은 cy 에 있어야 한다</b>. 이것은 제약이 아니라 서사이기도 하다:
    //    길은 사람이 깎고 다져 평평하게 만든 것이다 (절토·성토). 그리고 사용자도 그렇게 말했다 —
    //    <b>"대로는 특히 완만하게."</b>
    //
    //  【그래서 기복은 어디에 사는가 — 두 자리】
    //    ① <b>마당·공터의 땅</b> — 길과 길 사이, 집과 집 사이. 여기가 굽이친다 (진폭 ±2 · 파장 30~40).
    //       길은 그 굽이를 가르는 평평한 띠가 된다 (실제 향촌의 길이 그렇다).
    //    ② <b>필지의 기단</b> — 집은 제 기단 위에서 평평하되, <b>필지끼리는 높이가 다르다</b>.
    //       위계를 높이로 말한다: 관아 +1 · 표국 +1 (높은 자리) / 빈촌 민가 -1 (낮은 자리).
    //
    //  【불가침 — 1-립시츠】 어떤 인접 두 칸도 높이차 ≤ 1. 계단 없이 걷는다. 이것은 사인 합성으로
    //    "대략" 지키는 것이 아니라 <b>사영(projection)으로 강제</b>한다 (featherEdge 와 같은 손).
    //    그래야 환경 검수 ③(경계 급단차 ≤ 8%)이 구조적으로 통과한다.
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /** 기복의 진폭 — ±2칸. 3칸이면 마을이 언덕이 되고, 1칸이면 눈이 못 읽는다 */
    private static final int RELIEF_A = 2;
    /** 못끼리 어긋나지 않게 살펴보는 거리 (L1) — 진폭 2 의 두 배면 충분하다 */
    private static final int PIN_REACH = 4;

    /** 고도장 — cy 기준 상대 높이. 조성마다 build() 가 다시 빚는다 */
    private static int[] RELIEF;
    private static int RX;
    private static int RY;
    private static int RZ;

    private static int reliefIdx(int dx, int dz) {
        return (dx + SITE_R) * (2 * SITE_R + 1) + (dz + SITE_R);
    }

    /** 그 칸의 상대 고도 (cy 기준). 코어 밖이면 0 — 전이대는 지형 계층의 몫이다 */
    private static int lift(int dx, int dz) {
        if (RELIEF == null || Math.abs(dx) > SITE_R || Math.abs(dz) > SITE_R) {
            return 0;
        }
        return RELIEF[reliefIdx(dx, dz)];
    }

    /**
     * <b>그 열의 지면 y</b> — 마을 안에서 "땅이 어디 있나"는 전부 이 함수가 답한다.
     * 지면에 무언가 놓는 패스는 {@code cy} 를 직접 쓰지 않는다 (그것이 책상 위의 마을을 만들었다).
     */
    private static int gy(int x, int z) {
        return RY + lift(x - RX, z - RZ);
    }

    /**
     * 소품 자리의 지면 — 그 상자가 <b>평평할 때만</b> y 를 준다.
     * 기울어진 땅에 좌판을 놓으면 다리 하나가 뜬다 (환경 검수 ⑤ 부유 블록).
     * 살림 필지는 buildRelief 가 미리 다져 두었으므로(5x5 기단) 대개 통과한다.
     */
    private static int levelGround(int x, int z, int w, int d) {
        int g = gy(x, z);
        for (int dx = 0; dx < w; dx++) {
            for (int dz = 0; dz < d; dz++) {
                if (gy(x + dx, z + dz) != g) {
                    return Integer.MIN_VALUE;
                }
            }
        }
        return g;
    }

    /**
     * 기본 고도장 — <b>사인 셋의 합</b> (난수 0 · 좌표만의 순수 함수 = 재조성 결정론).
     *
     * <p>파장: 2π·6 ≈ 38칸(x) · 2π·5 ≈ 31칸(z) · 2π·9/√2 ≈ 40칸(대각) — 셋의 주기가 서로 어긋나
     * 등고선이 반듯한 줄이 되지 않는다 (noise() 가 배운 교훈: 규칙식은 반드시 패턴을 만든다).
     *
     * <p><b>기울기의 산수</b> — 축마다 |∂f/∂x| ≤ 1.2/6 + 0.8/9 = 0.29 < 0.5.
     * 0.5 미만이면 반올림한 두 이웃의 차가 <b>구조적으로</b> 1 이하다. 사영은 그 위의 안전망일 뿐이다.
     */
    private static double reliefBase(int dx, int dz) {
        return 1.20 * Math.sin(dx / 6.0 + 0.9)
                + 1.00 * Math.sin(dz / 5.0 + 2.3)
                + 0.80 * Math.sin((dx + dz) / 9.0 + 4.1);
    }

    /**
     * <b>평평해야 하는 자리</b> — 여기 고도는 0(=cy)에 못 박힌다.
     *
     * <p>규칙은 하나다: <b>{@code cy} 를 그대로 쓰는 패스가 한 칸이라도 쓰는 자리</b>는 전부 여기 든다.
     * 안 그러면 그 패스가 깐 노면이 굽이친 지면 <b>밑에 묻히거나 위에 뜬다</b>.
     * (대로·골목·광장·소로·뒷골목·디딤돌·담장·문루·각루, 그리고 기단을 주지 않은 건물의 필지.)
     */
    private static boolean flatZone(int dx, int dz) {
        if (reserved(dx, dz)) {
            return true;   // 광장·대로·골목·담장·표국/관아 부지·진입 소로
        }
        for (int[] l : BACK_LANES) {   // 뒷골목 (노면 = DIRT_PATH · cy)
            if (dx >= l[0] - 1 && dx <= l[1] + 1 && dz >= l[2] - 1 && dz <= l[3] + 1) {
                return true;
            }
        }
        if (dx >= -48 && dx <= -40 && dz >= 15 && dz <= 20) {
            return true;   // 민가 9 소로 (doorPath x-46..-42 · z+17..+18)
        }
        for (int[] p : FLAT_PLOTS) {
            if (dx >= p[0] && dx <= p[1] && dz >= p[2] && dz <= p[3]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 기단 없는 필지 — 지면 그대로(cy) 서는 집. {x0, x1, z0, z1}.
     *
     * <p>객잔은 <b>북골목이 그 북벽 줄(z-20..-19)을 지나므로</b> 기단을 줄 수 없다 (골목 노면은 cy 고정).
     * 관청류 3채는 광장·대로에 면해 있어 그 앞마당이 곧 대로다 — 대로가 cy 면 그 앞집도 cy 다.
     * ㄱ자형·작업장형 민가는 날개·부속간이 있어 필지 경계가 사각형이 아니다 (기단은 사각형 위에만 선다).
     */
    private static final int[][] FLAT_PLOTS = {
            {-35, -9, -23, -3},    // 청하객잔 (벽 x-32..-12 · z-20..-6 · 지붕 ±2)
            {8, 28, -21, -3},      // 의뢰소 (벽 x+11..+25 · z-18..-6)
            {-28, -8, 3, 21},      // 의방
            {8, 28, 3, 21},        // 청하전장
            {-46, -24, -32, -21},  // 민가 #1 대장간 (본채 + 작업간 — 부속이 있어 사각형이 아니다)
            {-19, -3, -33, -22},   // 민가 #2 ㄱ자형 (뒷날개)
            {-45, -29, 21, 33},    // 민가 #6 ㄱ자형
            {6, 22, 22, 33},       // 민가 #7 직조간 (본채 + 작업간)
    };

    /**
     * <b>기단이 있는 필지</b> — {x0, x1, z0, z1, 기단}. <b>위계를 높이로 말한다.</b>
     *
     * <ul>
     *   <li><b>관아 +1</b> — 관은 무림 위에 있다. 삼문에 오르는 한 켜가 그것을 말한다</li>
     *   <li><b>표국 +1</b> — 담 안이 곧 점포다. 작은 성은 한 켜 높다</li>
     *   <li><b>민가 #4·#5 +1</b> — 동편 다락집·중촌 일자집 (골목 동쪽이 높은 자리)</li>
     *   <li><b>민가 #3 -1</b> — 북골목 빈촌. 가난은 자재로도 말하고 <b>높이로도 말한다</b></li>
     *   <li><b>민가 #8·#9 -1</b> — 남쪽 저지대</li>
     * </ul>
     *
     * <p>불가침: 기단은 {-1, 0, +1} 뿐이다. 못 박힌 노면(0) 과 맞닿아도 <b>한 칸 턱</b>이면 걸어 넘는다
     * (그리고 그 턱이 곧 댓돌이다 — 스타일 가이드의 기단 문법). ±2 면 벽이 된다.
     *
     * <p>필지 안에 {@code cy} 를 그대로 쓰는 패스가 한 칸도 들어오지 않는지 좌표로 검산했다:
     * 골목(z∓19..∓21) · 문턱 디딤돌(z∓21·∓22) · 뒷골목 · 진입 소로 전부 이 상자들 밖이다.
     */
    /** 관아의 기단 — cottages·build() 가 쓰는 값과 PLOT_PADS 가 **한 값**이어야 한다 */
    private static final int PAD_OFFICE = 1;
    /** 표국의 기단 */
    private static final int PAD_PYOGUK = 1;

    private static final int[][] PLOT_PADS = {
            {-31, -9, 33, 57, PAD_OFFICE},     // 청하현 관아 (담 CO_X0..CO_X1 · CO_Z0..CO_Z1) — 한 켜 높다
            {28, 52, 36, 57, PAD_PYOGUK},      // 철산표국 (담 PY_*) — 한 켜 높다
            {8, 18, -30, -22, -1},    // 민가 #3 일자형 빈촌 11x9 — 한 켜 낮다
            {29, 37, -33, -22, 1},    // 민가 #4 다락형 9x12
            {42, 56, -18, -8, 1},     // 민가 #5 일자형 15x11
            {34, 42, 22, 33, -1},     // 민가 #8 다락형 9x12
            {-51, -37, 6, 16, -1},    // 민가 #9 일자형 15x11
    };

    /**
     * 고도장을 빚는다 — <b>사인 합성 → 못 박기 → 1-립시츠 사영</b>.
     *
     * <p>순서가 곧 보장이다: 사영이 마지막에 돌아야 못(노면 0 · 기단 ±1)과 자유 지면 사이의 이음매가
     * 한 칸씩 이어진다. 못끼리는 이미 서로 ≤1 이므로(노면 0 · 기단 ±1) 사영이 발산하지 않는다.
     */
    private static void buildRelief(int cx, int cy, int cz) {
        RX = cx;
        RY = cy;
        RZ = cz;
        int n = 2 * SITE_R + 1;
        int[] h = new int[n * n];
        boolean[] pin = new boolean[n * n];

        for (int dx = -SITE_R; dx <= SITE_R; dx++) {
            for (int dz = -SITE_R; dz <= SITE_R; dz++) {
                int i = reliefIdx(dx, dz);
                int v = (int) Math.round(reliefBase(dx, dz));
                h[i] = Math.max(-RELIEF_A, Math.min(RELIEF_A, v));
            }
        }
        // ① 기단 — **가장 먼저**. 위계는 양보하지 않는다 (관아·표국은 한 켜 높다).
        //    기단은 {-1,0,+1} 뿐이라 뒤에 올 노면 못(0)과 맞닿아도 한 칸 턱 = 댓돌이다.
        for (int[] p : PLOT_PADS) {
            pinBox(h, pin, p[0], p[1], p[2], p[3], p[4]);
        }
        // ② 노면·담장·기단 없는 필지 → 0. **이미 기단이 못 박은 칸은 건드리지 않는다**
        for (int dx = -SITE_R; dx <= SITE_R; dx++) {
            for (int dz = -SITE_R; dz <= SITE_R; dz++) {
                int i = reliefIdx(dx, dz);
                if (!pin[i] && flatZone(dx, dz)) {
                    h[i] = 0;
                    pin[i] = true;
                }
            }
        }
        // ③ 살림 필지 — 사람이 놓고 쓰는 자리는 다져져 평평하다 (5x5 기단, 그 자리의 제 높이로).
        //    노면·기단과 겹치면 통째로 포기한다 (반만 다진 필지는 소품의 다리를 띄운다).
        for (int[] g : GARDENS) {   // 밭이 **먼저** — 물길이 한 켜로 흘러야 물 댄 밭이다 (소품보다 급하다)
            pinBox(h, pin, g[0] - 1, g[1] + 1, g[2] - 1, g[3] + 1, Integer.MIN_VALUE);
        }
        for (int[] t : VILLAGE_LIFE) {
            pinPad(h, pin, t[0], t[1]);
        }
        for (int[] t : LIFE_TRACES) {
            pinPad(h, pin, t[0], t[1]);
        }
        // ④ 1-립시츠 사영 — **계단 없이 걷는다** (인접 두 칸의 높이차 ≤ 1).
        //
        //   반복 완화(min/max 클램프)를 먼저 썼는데 **진동했다**: 위 못(-1)과 아래 자유칸(+2) 사이에
        //   끼인 칸이 내리기 스윕에서 0 이 됐다가 올리기 스윕에서 다시 1 로 튀어 올랐다. 두 스윕이 싸운다.
        //
        //   답은 반복이 아니라 **구성**이다. 못들이 만드는 두 포락선을 먼저 그린다:
        //     상한 up(c)  = min over 못 p ( v(p) + L1거리(c,p) )   ← 못보다 이만큼까지만 높을 수 있다
        //     하한 low(c) = max over 못 p ( v(p) − L1거리(c,p) )   ← 못보다 이만큼까지만 낮을 수 있다
        //   둘 다 정의상 1-립시츠고, 기본장(사인 합성 + 반올림)도 기울기 0.29 < 0.5 라 1-립시츠다.
        //   **1-립시츠 함수 셋의 중앙값은 1-립시츠다** → h = clamp(기본장, low, up) 이 곧 해(解)다.
        //   반복도 진동도 없다. 재조성하면 같은 값이 나온다 (결정론).
        //
        //   포락선은 4방향 체임퍼 거리변환(전진·후진 스윕)으로 구한다.
        final int inf = 1 << 20;
        int[] up = new int[n * n];
        int[] low = new int[n * n];
        for (int i = 0; i < n * n; i++) {
            up[i] = pin[i] ? h[i] : inf;
            low[i] = pin[i] ? h[i] : -inf;
        }
        for (int sweep = 0; sweep < 2; sweep++) {
            for (int dx = -SITE_R; dx <= SITE_R; dx++) {
                for (int dz = -SITE_R; dz <= SITE_R; dz++) {
                    envelope(up, low, dx, dz, -1, 0);
                    envelope(up, low, dx, dz, 0, -1);
                }
            }
            for (int dx = SITE_R; dx >= -SITE_R; dx--) {
                for (int dz = SITE_R; dz >= -SITE_R; dz--) {
                    envelope(up, low, dx, dz, 1, 0);
                    envelope(up, low, dx, dz, 0, 1);
                }
            }
        }
        for (int i = 0; i < n * n; i++) {
            if (pin[i]) {
                continue;
            }
            h[i] = low[i] > up[i] ? up[i] : Math.max(low[i], Math.min(up[i], h[i]));
        }
        RELIEF = h;
    }

    /** 포락선 한 걸음 — 이웃에서 한 칸 멀어지면 상한은 +1, 하한은 -1 (체임퍼 거리변환) */
    private static void envelope(int[] up, int[] low, int dx, int dz, int ox, int oz) {
        int nx = dx + ox;
        int nz = dz + oz;
        if (Math.abs(nx) > SITE_R || Math.abs(nz) > SITE_R) {
            return;
        }
        int i = reliefIdx(dx, dz);
        int j = reliefIdx(nx, nz);
        up[i] = Math.min(up[i], up[j] + 1);
        low[i] = Math.max(low[i], low[j] - 1);
    }

    /** 살림 한 자리의 기단 — 소품이 딛는 칸(최대 5x4)을 그 자리의 제 높이로 다진다 */
    private static void pinPad(int[] h, boolean[] pin, int dx, int dz) {
        pinBox(h, pin, dx, dx + 4, dz, dz + 3, Integer.MIN_VALUE);
    }

    /**
     * 상자 하나를 한 높이로 못 박는다. {@code value == MIN_VALUE} 면 <b>그 상자 한복판의 높이</b>를 쓴다.
     *
     * <p>두 가지를 지킨다:
     * <ol>
     *   <li><b>겹치면 포기한다</b> — 이미 못 박힌 칸(노면·담장·다른 기단)이 하나라도 섞이면 통째로 접는다.
     *       노면을 들어 올리는 것은 검수를 깨는 짓이고, 반만 다진 필지는 소품의 다리를 띄운다.</li>
     *   <li><b>이웃 못과 한 칸 안에서 만난다</b> — 상자 둘레의 이미 못 박힌 칸을 보고 그 ±1 로 값을 조인다.
     *       못끼리 두 칸 차가 나면 사영이 그것을 못 고친다 (못은 안 움직인다) = 걸을 수 없는 턱.</li>
     * </ol>
     */
    private static void pinBox(int[] h, boolean[] pin, int x0, int x1, int z0, int z1, int value) {
        if (x0 < -SITE_R || x1 > SITE_R || z0 < -SITE_R || z1 > SITE_R) {
            return;
        }
        for (int dx = x0; dx <= x1; dx++) {
            for (int dz = z0; dz <= z1; dz++) {
                if (pin[reliefIdx(dx, dz)]) {
                    return;   // 노면·담장·다른 기단과 겹친다 — 이 상자는 포기한다
                }
            }
        }
        int v = value == Integer.MIN_VALUE ? h[reliefIdx((x0 + x1) / 2, (z0 + z1) / 2)] : value;
        // 둘레의 이미 못 박힌 이웃과 **L1 거리 안에서** 만난다: |v - v(이웃)| ≤ 거리.
        //   거리 4 까지 보면 충분하다 — 고도는 [-2,+2] 뿐이라 5칸 이상 떨어진 못과는 자동으로 어긋나지 않는다.
        //   이걸 안 하면 못끼리 두 칸 차가 나고, 사영은 못을 못 움직이므로 그 턱이 영원히 남는다.
        int lo = Integer.MIN_VALUE;
        int hi = Integer.MAX_VALUE;
        for (int dx = x0 - PIN_REACH; dx <= x1 + PIN_REACH; dx++) {
            for (int dz = z0 - PIN_REACH; dz <= z1 + PIN_REACH; dz++) {
                if (Math.abs(dx) > SITE_R || Math.abs(dz) > SITE_R) {
                    continue;
                }
                int i = reliefIdx(dx, dz);
                if (!pin[i]) {
                    continue;
                }
                int d = Math.max(0, Math.max(x0 - dx, dx - x1)) + Math.max(0, Math.max(z0 - dz, dz - z1));
                if (d == 0 || d > PIN_REACH) {
                    continue;
                }
                lo = Math.max(lo, h[i] - d);
                hi = Math.min(hi, h[i] + d);
            }
        }
        if (lo != Integer.MIN_VALUE) {
            v = Math.max(lo, Math.min(hi, v));
        }
        v = Math.max(-RELIEF_A, Math.min(RELIEF_A, v));
        for (int dx = x0; dx <= x1; dx++) {
            for (int dz = z0; dz <= z1; dz++) {
                int i = reliefIdx(dx, dz);
                h[i] = v;
                pin[i] = true;
            }
        }
    }

    /**
     * 지면 조직 — 거친 흙 66% · 흙 20% · 뿌리 흙 6% · 잔디 8% (v6.5 ②: 12.5% → 8%, 그리고 줄무늬 없이).
     * 잔디도 초록이다 — 조감도의 세로줄은 잡초만의 죄가 아니었다. 마당은 맨땅이 지배해야 한다.
     */
    private static void groundCover(World world, int cx, int cy, int cz) {
        for (int x = cx - 59; x <= cx + 59; x++) {
            for (int z = cz - 59; z <= cz + 59; z++) {
                int h = hash(x, z, 50);
                Material m;
                if (WALL_FOOT <= Math.max(Math.abs(x - cx), Math.abs(z - cz))) {
                    // v7.1 【담 발치는 자연의 땅이다】 — 여기만 다진 흙(COARSE_DIRT)을 쓰지 않는다.
                    //   TerrainAudit ③ 은 "사람이 깐 바닥 ↔ 자연 지면"의 **이음매**에서만 단차를 센다.
                    //   다진 흙은 그 검수의 '사람이 깐 바닥'이다. 담(cy+1 조약돌)과 담 발치 등롱(cy+3 랜턴)은
                    //   둘 다 검수의 눈에 **지형**으로 읽히므로(구조물 목록에 조약돌·랜턴이 없다), 그 발치를
                    //   다진 흙으로 깔면 걸음마다 "사람이 깐 바닥(cy) → 지형(cy+2~cy+4)" 의 이음매가 생기고
                    //   3~4칸 단차로 세어진다 — **경계 급단차 8.7%의 정체가 이것이었다** (지형은 이미 평평했다).
                    //   담 발치를 풀·흙(자연 자재)으로 두면 이음매 자체가 사라진다. 서사도 맞는다:
                    //   사람이 안 다니는 담 밑에는 풀이 남는다.
                    m = h < 20 ? Material.GRASS_BLOCK : h < 26 ? Material.ROOTED_DIRT : Material.DIRT;
                } else if (h < 4) {
                    m = Material.GRASS_BLOCK;      // 8% — 밟히지 않는 자리에만 풀이 남는다
                } else if (h < 7) {
                    m = Material.ROOTED_DIRT;
                } else if (h < 17) {
                    m = Material.DIRT;
                } else {
                    m = Material.COARSE_DIRT;      // 다진 흙 — 마을의 바탕색
                }
                world.getBlockAt(x, gy(x, z), z).setType(m);   // v7.2 — 지면은 고도장을 따른다
            }
        }
    }

    /** 담 발치 띠 — 이 거리부터 담장(r=60)까지는 다진 흙을 쓰지 않는다 (등롱 링 ±58 을 넉넉히 품는다) */
    private static final int WALL_FOOT = 56;

    /**
     * v7.1 【등롱 발치】 — 등롱이 선 자리와 그 사방 한 칸의 지면을 **자연 자재(흙)** 로 바꾼다.
     *
     * <p>랜턴(cy+3)은 TerrainAudit 의 구조물 목록에 없어 **지형 표면**으로 읽힌다 — 즉 등롱 한 기는
     * 검수의 눈에 "3칸 솟은 땅"이다. 그 발치가 다진 흙(=사람이 깐 바닥)이면 걸음마다 이음매가 생기고
     * 3칸 단차로 세어진다. 발치를 흙으로 두면 등롱도 그 땅도 '자연'이라 이음매가 없다.
     *
     * <p>노면(흙길·자갈·돌)은 절대 건드리지 않는다 — 맨땅(LAMP_GROUND)만 바꾼다. 길 폭 검수는 무사하다.
     */
    private static void lampApron(World world, int x, int cy, int z) {
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                Block b = world.getBlockAt(x + ox, gy(x + ox, z + oz), z + oz);
                if (LAMP_GROUND.contains(b.getType())) {
                    b.setType(Material.DIRT);
                }
            }
        }
    }

    /**
     * 손대면 안 되는 자리 — 길(대로 ±5: 갓길 + 등롱 열)·골목(z∓20 ±4)·광장(±9)·담장 발치(±58~)·표국 부지.
     * v6.2 의 새 패스(울타리·텃밭·소품·잡초)는 전부 이 술어를 통과해야 한 블록이라도 놓을 수 있다.
     */
    private static boolean reserved(int dx, int dz) {
        if (Math.abs(dx) <= 9 && Math.abs(dz) <= 9) {
            return true;                                        // 광장 19x19
        }
        if (Math.abs(dx) <= 5 || Math.abs(dz) <= 5) {
            return true;                                        // 십자대로 7칸 + 갓길 + 등롱 열(±5)
        }
        if (Math.abs(Math.abs(dz) - 20) <= 4) {
            return true;                                        // 북·남 골목 3칸 + 문턱·소로 여유
        }
        if (Math.abs(dx) >= 58 || Math.abs(dz) >= 58) {
            return true;                                        // 담장·발치 흙길
        }
        if (dx >= 26 && dx <= 54 && dz >= 33 && dz <= 59) {
            return true;                                        // 철산표국 부지(마당 담 + 처마)
        }
        if (dx >= -34 && dx <= -6 && dz >= 30 && dz <= 59) {
            return true;                                        // v7.0 ① 청하현 관아 부지 (담 + 처마 + 삼문 앞)
        }
        if (dx >= -24 && dx <= -18 && dz >= 21 && dz <= 33) {
            return true;                                        // v7.0 ① 관아 진입 소로 (남골목 → 삼문)
        }
        return dx >= 36 && dx <= 48 && dz >= 16 && dz <= 36;    // 표국 진입 소로
    }

    /** 아직 아무도 쓰지 않은 맨땅인가 — 지면이 groundCover 자재이고 그 위가 비었고 예약 구역이 아니다 */
    private static boolean freeCell(World world, int cx, int cy, int cz, int x, int z) {
        if (reserved(x - cx, z - cz)) {
            return false;
        }
        int g0 = gy(x, z);   // v7.2 — 그 열의 지면 (마당은 굽이친다)
        Material g = world.getBlockAt(x, g0, z).getType();
        boolean bare = g == Material.COARSE_DIRT || g == Material.DIRT
                || g == Material.ROOTED_DIRT || g == Material.GRASS_BLOCK;
        return bare && world.getBlockAt(x, g0 + 1, z).getType().isAir();
    }

    /**
     * v6.4 ④ 민가 뒤뜰 텃밭 — 4필지는 유지하되 **필지를 5x4 이하로 줄이고, 둘은 아예 비웠다**.
     * v6.3 은 필지 수만 줄였고 조감도에서는 여전히 초록 이랑이 마을을 지배했다 — 문제는 개수가 아니라
     * **한 필지의 크기와 작물의 채도**였다. 향촌의 마당은 밭이 아니라 '사람이 쓰는 빈 땅'이 지배한다:
     * 갈아만 두고 비워 둔 밭(crop = -1 → farmland 만)과 건초 더미·퇴비통·장작더미가 그 자리를 대신한다.
     *   {x0, x1, z0, z1, 울타리, 작물, 이랑}. 전부 상수 (난수 금지).
     *   울타리: 0 참나무 목책 / 1 대나무 목책 / 2 낮은 돌담.
     *   작물:   -1 없음(빈 밭) / 0 밀 / 1 당근 / 2 감자 / 3 비트 — 필지당 한 가지.
     *   이랑:   0 세로(물길이 한 x열) / 1 가로(물길이 한 z행).
     * 지운 자리는 다진 흙 마당·공터로 남기고 뒷골목(BACK_LANES)과 생활 흔적(LIFE_TRACES)이 채운다.
     */
    private static final int[][] GARDENS = {
            {-43, -39, -39, -36, 0, 0, 0},   // #1 대장간 뒤뜰 5x4 — 밀, 세로 이랑
            {10, 14, -40, -38, 1, -1, 1},    // #3 일자집 뒤뜰 5x3 — **빈 밭**(갈아만 둔 땅), 가로 물길
            {-27, -24, 26, 29, 2, 2, 0},     // #6 ㄱ자집 곁밭 4x4 — 감자, 세로 이랑
            {9, 13, 35, 38, 0, -1, 1},       // #7 직조간 뒤뜰 5x4 — **빈 밭**, 가로 물길
    };

    /**
     * v6.3 ④ 뒷골목 — {x0, x1, z0, z1}. 텃밭을 걷어낸 자리를 그냥 비우면 들판이 된다.
     * 좁은(2칸) 다진 흙 길을 내어 집과 집 사이에 '뒤로 도는 동선'을 만든다 — 마을은 앞면만으로 서지 않는다.
     * 대로(±5)·광장(±9)·골목(z∓20±1)·건물 지붕을 피해 잡았고, 이미 무언가 선 칸은 건너뛴다.
     */
    private static final int[][] BACK_LANES = {
            {-22, -21, -34, -22},   // 대장간 작업마당(x-24) ↔ ㄱ자집 지붕(x-19) 사이 → 북골목
            {24, 25, -36, -22},     // 일자집 지붕(x+22) ↔ 다락집 지붕(x+27) 사이 → 북골목
            {-12, -11, 22, 29},     // 의방 뒤(z+20) ↔ 남쪽 들판 (v7.0 ① — 관아 부지(z≥30) 앞에서 끊는다)
    };

    /**
     * 생활 흔적 — {x, z, 종류}. 0 장독대 / 1 장작더미 / 2 빨랫줄 / 3 닭장 / 4 퇴비통 / 5 건초 더미 (v6.4).
     * v6.4 ④: 줄인 텃밭이 비운 마당을 **밭이 아닌 것**으로 채운다 — 건초 더미·퇴비통·장작더미가
     * 초록 이랑 자리에 들어서면 조감도의 지배색이 작물(연두)에서 흙·짚(갈색)으로 돌아온다.
     */
    private static final int[][] LIFE_TRACES = {
            {-46, -28, 1}, {-46, -26, 0}, {-47, -31, 3},   // #1 대장간 서편
            {-8, -34, 0}, {-8, -32, 1},                    // #2 안마당
            {22, -28, 0}, {22, -26, 1}, {23, -33, 3},      // #3 동편
            {39, -30, 0}, {39, -27, 2}, {39, -33, 4},      // #4 동편
            {44, -8, 0}, {47, -8, 1}, {50, -9, 3},         // #5 남편
            {-45, 25, 0}, {-45, 28, 1}, {-47, 32, 3},      // #6 서편
            {6, 26, 0}, {6, 29, 1}, {24, 30, 2}, {14, 33, 4},   // #7 서·동·남편 (빨랫줄은 G4 울타리를 피해 동편으로)
            {32, 26, 0}, {32, 29, 1},                      // #8 서편
            {-31, 10, 0}, {-31, 13, 1}, {-31, 16, 2},      // #9 동편
            // v6.4 ④ — 걷어낸 텃밭 자리의 마당 살림 (건초·퇴비·장작이 밭을 대신한다)
            {-37, -39, 5}, {-34, -38, 4}, {-37, -35, 1},   // #1 뒤뜰 (구 7x4 밭의 동쪽 절반)
            {10, -35, 5}, {13, -34, 1},                    // #3 뒤뜰 (구 5x5 밭의 남쪽)
            {-27, 31, 5}, {-25, 34, 4},                    // #6 곁밭 (구 4x7 밭의 남쪽)
            {9, 33, 5}, {12, 32, 1},                       // #7 뒤뜰 (구 6x4 밭의 북쪽 — 집과 밭 사이 마당)
    };

    /** 필지 — 텃밭·울타리·생활 흔적. "울타리가 마을을 마을로 만든다" */
    private static void yards(World world, int cx, int cy, int cz) {
        for (int[] g : GARDENS) {
            farmPlot(world, cx, cy, cz, cx + g[0], cx + g[1], cz + g[2], cz + g[3], g[5], g[6]);
            plotFence(world, cx, cy, cz, cx + g[0] - 1, cx + g[1] + 1, cz + g[2] - 1, cz + g[3] + 1,
                    fenceMat(g[4]));
        }
        for (int[] t : LIFE_TRACES) {
            lifeTrace(world, cx, cy, cz, cx + t[0], cz + t[1], t[2]);
        }
    }

    /**
     * v6.3 ④ 뒷골목 — 좁은 2칸 다진 흙 길. 노면(cy)과 노반(cy-1)을 함께 깐다.
     * 골목·소로를 다 깐 뒤에 돌고, 이미 무언가 서 있는 칸(건물·담·울타리)은 건너뛴다 — 침범 0.
     */
    private static void backLanes(World world, int cx, int cy, int cz) {
        for (int[] l : BACK_LANES) {
            for (int x = cx + l[0]; x <= cx + l[1]; x++) {
                for (int z = cz + l[2]; z <= cz + l[3]; z++) {
                    if (!world.getBlockAt(x, gy(x, z) + 1, z).getType().isAir()) {
                        continue;   // 무언가 이미 섰다 — 뒷골목이 밀어내지 않는다
                    }
                    int g = gy(x, z);   // v7.2 — 뒷골목도 노면이라 고도장이 0 에 못 박아 두었다
                    int h = hash(x, z, 10);   // v6.5 ② — 선형 해시가 노면에 사선 줄을 그었다
                    world.getBlockAt(x, g, z).setType(
                            h < 2 ? Material.COARSE_DIRT : Material.DIRT_PATH);
                    world.getBlockAt(x, g - 1, z).setType(Material.COARSE_DIRT);
                }
            }
        }
    }

    private static Material fenceMat(int kind) {
        return switch (kind) {
            case 1 -> Material.BAMBOO_FENCE;
            case 2 -> Material.COBBLESTONE_WALL;
            default -> Material.OAK_FENCE;
        };
    }

    /**
     * v6.3 ④ 텃밭 한 필지 — 경작지 + **필지당 한 작물** + 이랑과 나란한 물길 1줄.
     * v6.2 는 칸마다 좌표 해시로 작물을 섞고 물길을 늘 세로로 냈다 → 어느 밭이나 같은 줄무늬였다.
     * 이제 작물(crop)과 이랑 방향(rows)이 필지 상수로 들어온다: 밭마다 색과 결이 다르다.
     * 작물은 Ageable.setAge(max) 로 성장 단계를 고정한다 — 결정론 (같은 마을이면 같은 이삭).
     *   rows 0 = 세로 이랑(물길이 가운데 한 x열) / 1 = 가로 이랑(물길이 가운데 한 z행).
     *   물길은 필지 폭의 한복판이므로 어느 경작지도 물에서 4칸 안이다 (물 댄 밭).
     */
    private static void farmPlot(World world, int cx, int cy, int cz,
                                 int x0, int x1, int z0, int z1, int crop, int rows) {
        boolean alongZ = rows == 1;              // 이랑이 가로 → 물길도 가로
        int channel = alongZ ? (z0 + z1) / 2 : (x0 + x1) / 2;
        Material seed = switch (crop) {
            case 0 -> Material.WHEAT;
            case 1 -> Material.CARROTS;
            case 2 -> Material.POTATOES;
            case 3 -> Material.BEETROOTS;
            default -> null;                     // v6.4 ④ — 빈 밭: 갈아만 두고 심지 않았다
        };
        // v7.2 — 물길은 **평평한 밭에만** 낸다. 기울어진 밭에 물을 부으면 흘러내려 '잘린 물'이 된다
        //   (환경 검수 ② 수역 파탄). 그런 필지는 마른 고랑(다진 흙)으로 둔다 — 물 못 대는 밭도 밭이다.
        boolean level = levelGround(x0, z0, x1 - x0 + 1, z1 - z0 + 1) != Integer.MIN_VALUE;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!freeCell(world, cx, cy, cz, x, z)) {
                    continue;
                }
                int g = gy(x, z);
                if (alongZ ? z == channel : x == channel) {
                    world.getBlockAt(x, g, z).setType(level ? Material.WATER : Material.COARSE_DIRT);
                    continue;
                }
                world.getBlockAt(x, g, z).setType(Material.FARMLAND);
                if (seed == null) {
                    continue;
                }
                BlockData data = seed.createBlockData();
                Ageable age = (Ageable) data;
                age.setAge(age.getMaximumAge());   // 성장 고정 — 재조성해도 같은 이삭
                world.getBlockAt(x, g + 1, z).setBlockData(age);
            }
        }
    }

    /** 필지 울타리 — 낮은 목책/돌담 한 겹. 남북 변 가운데 한 칸은 삽짝(비운다) */
    private static void plotFence(World world, int cx, int cy, int cz,
                                  int x0, int x1, int z0, int z1, Material mat) {
        int gate = (x0 + x1) / 2;
        for (int x = x0; x <= x1; x++) {
            for (int z : new int[]{z0, z1}) {
                if (x != gate && freeCell(world, cx, cy, cz, x, z)) {
                    world.getBlockAt(x, gy(x, z) + 1, z).setType(mat);   // v7.2 — 울타리는 땅을 따라 오르내린다
                }
            }
        }
        for (int z = z0 + 1; z <= z1 - 1; z++) {
            for (int x : new int[]{x0, x1}) {
                if (freeCell(world, cx, cy, cz, x, z)) {
                    world.getBlockAt(x, gy(x, z) + 1, z).setType(mat);
                }
            }
        }
    }

    /** 생활 흔적 한 무더기 — 장독대·장작더미·빨랫줄·닭장·퇴비통 (전부 freeCell 검사 통과분만) */
    private static void lifeTrace(World world, int cx, int cy, int cz, int x, int z, int kind) {
        // v7.2 【마을의 기복】 — 소품은 **평평한 자리**에만 놓는다. 기울어진 땅에 좌판·닭장·우물을 놓으면
        //   다리 하나가 뜨고(환경 검수 ⑤ 부유 블록) 우물물이 흘러내린다(검수 ② 수역 파탄).
        //   살림 필지는 buildRelief 가 미리 다져 두었다 (5x4 소품 + 사방 여유). 못 다진 자리는 비운다.
        int[] box = PROP_BOX[Math.min(kind, PROP_BOX.length - 1)];
        int g = levelGround(x, z, box[0], box[1]);
        if (g == Integer.MIN_VALUE) {
            return;
        }
        switch (kind) {
            case 0 -> {   // 장독대 — 항아리 2~3
                putProp(world, cx, cy, cz, x, z, Material.DECORATED_POT);
                putProp(world, cx, cy, cz, x, z + 1, Material.DECORATED_POT);
                putProp(world, cx, cy, cz, x + 1, z, Material.DECORATED_POT);
            }
            case 1 -> {   // 장작더미 — 눕힌 통나무 2단
                for (int dz = 0; dz <= 1; dz++) {
                    if (!freeCell(world, cx, cy, cz, x, z + dz)) {
                        continue;
                    }
                    for (int y = g + 1; y <= g + 2; y++) {
                        Orientable log = (Orientable) Material.OAK_LOG.createBlockData();
                        log.setAxis(Axis.X);
                        world.getBlockAt(x, y, z + dz).setBlockData(log);
                    }
                }
            }
            case 2 -> {   // 빨랫줄 — 장대 2 + 사슬 + 널어 둔 무명천 (수묵 안의 흰색)
                if (!freeCell(world, cx, cy, cz, x, z) || !freeCell(world, cx, cy, cz, x, z + 2)) {
                    return;
                }
                for (int dz : new int[]{0, 2}) {
                    for (int y = g + 1; y <= g + 3; y++) {
                        world.getBlockAt(x, y, z + dz).setType(Material.SPRUCE_FENCE);
                    }
                }
                Orientable chain = (Orientable) Material.IRON_CHAIN.createBlockData();
                chain.setAxis(Axis.Z);
                world.getBlockAt(x, g + 3, z + 1).setBlockData(chain);
                world.getBlockAt(x, g + 2, z + 1).setType(Material.WHITE_WOOL);   // 널어 둔 천
            }
            case 3 -> {   // 닭장 — 목책 3x3(삽짝 한 칸) + 짚
                for (int dx = 0; dx <= 2; dx++) {
                    for (int dz = 0; dz <= 2; dz++) {
                        boolean rim = dx == 0 || dx == 2 || dz == 0 || dz == 2;
                        if (!freeCell(world, cx, cy, cz, x + dx, z + dz)) {
                            continue;
                        }
                        if (rim && !(dx == 1 && dz == 0)) {
                            world.getBlockAt(x + dx, g + 1, z + dz).setType(Material.OAK_FENCE);
                        } else if (dx == 1 && dz == 1) {
                            world.getBlockAt(x + dx, g + 1, z + dz).setType(Material.HAY_BLOCK);
                        }
                    }
                }
            }
            case 4 -> {   // 퇴비통 + 통
                putProp(world, cx, cy, cz, x, z, Material.COMPOSTER);
                putProp(world, cx, cy, cz, x + 1, z, Material.BARREL);
            }
            case 5 -> {   // v6.4 ④ 건초 더미 — 2x2 짚단 1단 + 한 귀만 2단 (베어 쌓은 결)
                for (int dx = 0; dx <= 1; dx++) {
                    for (int dz = 0; dz <= 1; dz++) {
                        if (!freeCell(world, cx, cy, cz, x + dx, z + dz)) {
                            continue;
                        }
                        Orientable hay = (Orientable) Material.HAY_BLOCK.createBlockData();
                        hay.setAxis(hash(x, z, 2) == 0 ? Axis.X : Axis.Z);
                        world.getBlockAt(x + dx, g + 1, z + dz).setBlockData(hay);
                        if (dx == 0 && dz == 0) {
                            world.getBlockAt(x, g + 2, z).setBlockData(hay);
                        }
                    }
                }
            }
            case 6 -> {   // v6.5 ③ 우물 — 3x3 조약돌 담 테 + 가운데 물 + 두레박 장대 (마을 우물의 동생들)
                for (int dx = 0; dx <= 2; dx++) {
                    for (int dz = 0; dz <= 2; dz++) {
                        if (!freeCell(world, cx, cy, cz, x + dx, z + dz)) {
                            continue;
                        }
                        boolean center = dx == 1 && dz == 1;
                        if (center) {
                            world.getBlockAt(x + 1, g, z + 1).setType(Material.WATER);   // 물은 노면 자재가 아니다
                        } else {
                            world.getBlockAt(x + dx, g + 1, z + dz).setType(Material.COBBLESTONE_WALL);
                        }
                    }
                }
                if (freeCell(world, cx, cy, cz, x + 1, z + 3)) {   // 두레박 장대 + 매단 사슬
                    for (int y = g + 1; y <= g + 3; y++) {
                        world.getBlockAt(x + 1, y, z + 3).setType(Material.SPRUCE_FENCE);
                    }
                    Orientable chain = (Orientable) Material.IRON_CHAIN.createBlockData();
                    chain.setAxis(Axis.Y);
                    world.getBlockAt(x + 1, g + 3, z + 2).setBlockData(chain);
                }
            }
            case 7 -> {   // v6.5 ③ 공동 빨래터 — 물확 2칸 + 방망잇돌 + 널어 둔 무명천
                for (int dz = 0; dz <= 1; dz++) {
                    if (freeCell(world, cx, cy, cz, x, z + dz)) {
                        world.getBlockAt(x, g, z + dz).setType(Material.WATER);
                        world.getBlockAt(x - 1, g + 1, z + dz).setType(Material.COBBLESTONE_WALL);
                    }
                }
                putProp(world, cx, cy, cz, x + 1, z, Material.CAULDRON);
                putProp(world, cx, cy, cz, x + 1, z + 1, Material.BARREL);
                lifeTrace(world, cx, cy, cz, x + 2, z, 2);   // 곁의 빨랫줄 (널어 둔 천 = 흰색 = 채색 아님)
            }
            case 8 -> {   // v6.5 ③ 짐수레 — 참나무 짐칸(다락문 측판) + 끌채 2 + 실은 짚
                if (!freeCell(world, cx, cy, cz, x, z) || !freeCell(world, cx, cy, cz, x + 1, z)) {
                    return;
                }
                world.getBlockAt(x, g + 1, z).setType(Material.HAY_BLOCK);          // 실은 짐
                world.getBlockAt(x + 1, g + 1, z).setType(Material.OAK_PLANKS);     // 짐칸 바닥
                awningTrapdoor(world, x, g + 2, z, BlockFace.NORTH);                // 젖힌 측판
                awningTrapdoor(world, x + 1, g + 2, z, BlockFace.SOUTH);
                for (int dx = 2; dx <= 3; dx++) {   // 끌채 — 땅에 내려 둔 채
                    putProp(world, cx, cy, cz, x + dx, z, Material.OAK_FENCE);
                }
            }
            case 9 -> {   // v6.5 ③ 노점 좌판 — 기둥 2 + 널 상판 3 + 통 (차양은 장터의 것 = 붉은 차양 계약 불변)
                for (int dx : new int[]{0, 2}) {
                    if (freeCell(world, cx, cy, cz, x + dx, z)) {
                        world.getBlockAt(x + dx, g + 1, z).setType(Material.SPRUCE_FENCE);
                    }
                }
                for (int dx = 0; dx <= 2; dx++) {
                    if (world.getBlockAt(x + dx, g + 2, z).getType().isAir()) {
                        topSlab(world, x + dx, g + 2, z, Material.OAK_SLAB);        // 상판 (지붕 자재 아님)
                    }
                }
                putProp(world, cx, cy, cz, x + 1, z, Material.BARREL);
            }
            case 10 -> {   // v6.5 ③ 개집 — 참나무 계단 두 짝이 만드는 맞배 한 채 + 짚 + 밥그릇
                if (!freeCell(world, cx, cy, cz, x, z) || !freeCell(world, cx, cy, cz, x + 1, z)) {
                    return;
                }
                stair(world, x, g + 1, z, Material.OAK_STAIRS, BlockFace.EAST);
                stair(world, x + 1, g + 1, z, Material.OAK_STAIRS, BlockFace.WEST);
                putProp(world, cx, cy, cz, x, z + 1, Material.HAY_BLOCK);
                putProp(world, cx, cy, cz, x + 2, z, Material.CAULDRON);             // 물그릇
            }
            default -> {   // v6.5 ③ 돌담 모퉁이 — ㄱ 자로 꺾인 낮은 돌담 (필지가 여기서 꺾인다)
                for (int dx = 0; dx <= 4; dx++) {
                    putProp(world, cx, cy, cz, x + dx, z, Material.COBBLESTONE_WALL);
                }
                for (int dz = 1; dz <= 3; dz++) {
                    putProp(world, cx, cy, cz, x, z + dz, Material.COBBLESTONE_WALL);
                }
                putProp(world, cx, cy, cz, x + 2, z + 2, Material.DECORATED_POT);     // 담 안쪽 살림
            }
        }
    }

    /**
     * 소품이 딛는 칸의 크기 {가로, 세로} — 종류별. 이 상자가 평평해야 그 소품이 선다.
     * 0 장독대 / 1 장작 / 2 빨랫줄 / 3 닭장 / 4 퇴비 / 5 건초 / 6 우물 / 7 빨래터 / 8 수레 / 9 좌판
     * / 10 개집 / 11 돌담 모퉁이
     */
    private static final int[][] PROP_BOX = {
            {2, 2}, {1, 2}, {1, 3}, {3, 3}, {2, 1}, {2, 2},
            {3, 4}, {4, 3}, {4, 1}, {3, 1}, {3, 2}, {5, 4},
    };

    private static void putProp(World world, int cx, int cy, int cz, int x, int z, Material mat) {
        if (freeCell(world, cx, cy, cz, x, z)) {
            world.getBlockAt(x, gy(x, z) + 1, z).setType(mat);   // v7.2 — 소품은 제 발밑 땅 위에
        }
    }

    // ─── v6.5 ③ 마을 살림 — 121x121 에 건물 15채면 담장 안이 휑하다 ───
    //
    // 채우되 **밭으로 채우지 않는다** (텃밭은 v6.4 에서 이미 줄였다). 향촌의 빈 땅을 메우는 것은
    // 작물이 아니라 **살림의 흔적**이다: 우물·빨래터·장작 야적·수레·좌판·개집·닭장·돌담 모퉁이,
    // 그리고 나무. 여기 놓는 것은 전부 세 규칙을 지킨다 —
    //   ① 지붕 자재(심층암 계열·흑목 계단/반 블록)를 **한 조각도 쓰지 않는다**.
    //      검수의 지붕 스캔은 벽 ±8 안의 지붕 자재를 전부 그 건물의 지붕으로 읽는다 —
    //      마당에 흑목 반 블록 하나만 놓아도 관청의 지붕 상자가 부풀어 **처마 겹침 오탐**이 난다.
    //   ② 노면 자재(조약돌·돌 벽돌·안산암·자갈)를 **지면(cy)에 놓지 않는다**. 검수는 cy 의 자재로
    //      길을 판정한다 — 마당에 깐 돌 한 장이 '길'이 되어 길 폭 히스토그램을 오염시킨다.
    //      돌은 담(COBBLESTONE_WALL — 별개 자재)으로만, 그것도 cy+1 위에만 쓴다.
    //   ③ 채색 금지 (수묵 2% 예산) — 참나무·자작·가문비·짚·흰 무명천만. 벚나무는 광장의 몫이다.
    // 전부 freeCell() 를 통과한 칸에만 놓으므로 길·골목·광장·담·건물·등롱·울타리를 한 칸도 밀어내지 않는다.

    /**
     * 마을 살림 40곳 — {x, z, 종류}. 종류는 lifeTrace 확장 번호:
     *   0 장독대 / 1 장작더미 / 2 빨랫줄 / 3 닭장 / 4 퇴비통 / 5 건초 더미
     *   6 우물(마을 우물 외) / 7 공동 빨래터 / 8 짐수레 / 9 노점 좌판 / 10 개집 / 11 돌담 모퉁이
     * 좌표는 건물 지붕 상자·텃밭·뒷골목·표국 부지를 피해 잡은 상수 (난수 0).
     */
    private static final int[][] VILLAGE_LIFE = {
            // 북쪽 대공터 (담 발치 ~ 민가 뒤편 — 마을에서 가장 넓게 비어 있던 띠)
            {-50, -50, 6}, {-44, -48, 1}, {-38, -52, 10}, {-30, -46, 9}, {-24, -50, 3},
            {-14, -46, 8}, {-16, -52, 11}, {8, -48, 7}, {16, -52, 1}, {24, -46, 0},
            {32, -50, 10}, {40, -44, 8}, {48, -50, 6}, {30, -42, 3}, {44, -34, 1},
            {50, -30, 5},
            // 서쪽 띠 (객잔 서편 ~ 담 발치)
            {-52, -30, 5}, {-52, -28, 1}, {-52, -12, 9}, {-46, -10, 4},
            {-40, -14, 11}, {-34, -12, 8},
            // 동쪽 띠 (의뢰소·전장 동편)
            {30, -14, 9}, {36, -10, 10}, {33, 8, 6}, {36, 14, 1}, {44, 8, 7},
            {50, 10, 3}, {30, 26, 5}, {50, 28, 8},
            // 서쪽 중·남 띠
            {-54, 8, 5}, {-54, 12, 9}, {-56, 15, 4}, {-54, 26, 6}, {-52, 34, 1},
            // 남쪽 대공터
            {-50, 40, 9}, {-42, 46, 8}, {-34, 52, 10}, {-26, 42, 7}, {-18, 50, 1},
            {-10, 44, 3}, {-8, 36, 4}, {8, 46, 11}, {16, 52, 6}, {22, 44, 0},
            {20, 50, 1}, {-22, 38, 5},
    };

    /** 마을 나무 24그루 — {x, z, 자작나무?}. 벚나무(채색)는 광장 전용이므로 참나무·자작만. */
    private static final int[][] VILLAGE_TREES = {
            {-54, -44, 0}, {-46, -52, 1}, {-36, -44, 0}, {-28, -54, 1}, {-20, -46, 0},
            {-12, -54, 1}, {12, -44, 0}, {20, -54, 1}, {28, -46, 0}, {36, -54, 1},
            {46, -42, 0}, {54, -36, 1}, {-54, -30, 0}, {-36, -12, 1}, {-56, 30, 0},
            {-54, 44, 1}, {-44, 52, 0}, {-30, 46, 1}, {-16, 42, 0}, {12, 50, 1},
            {24, 52, 0}, {38, 10, 1}, {42, 12, 0}, {54, 12, 1},
    };

    /**
     * 뒷마당 벽 10줄 — {x0, x1, z0, z1, 자재}. 필지 경계를 촘촘히 그어 "빈 흙바닥"의 덩어리를 쪼갠다.
     * 자재: 0 돌담(COBBLESTONE_WALL — 노면 판정 자재가 아니다) / 1 참나무 목책 / 2 대나무 목책.
     * 한복판 한 칸은 삽짝으로 비운다 (담이 아니라 필지다 — 사람이 드나든다).
     */
    private static final int[][] YARD_WALLS = {
            {-50, -38, -42, -42, 0}, {-30, -18, -42, -42, 1}, {6, 20, -44, -44, 0},
            {28, 42, -44, -44, 1}, {-48, -48, -38, -26, 0}, {50, 50, -40, -28, 2},
            {-50, -38, 38, 38, 0}, {-30, -18, 40, 40, 1}, {8, 22, 42, 42, 0},
            {-51, -51, 6, 14, 2}, {30, 30, 6, 14, 0},
    };

    /** 마을 살림·나무·필지 벽 — 등롱 뒤·잡초 앞에 돈다 (등롱 자리를 뺏지 않고, 잡초에 자리를 내준다) */
    private static void villageFill(World world, int cx, int cy, int cz) {
        for (int[] w : YARD_WALLS) {
            yardWall(world, cx, cy, cz, cx + w[0], cx + w[1], cz + w[2], cz + w[3], fenceMat(w[4] == 0 ? 2 : w[4] - 1));
        }
        for (int[] t : VILLAGE_LIFE) {
            lifeTrace(world, cx, cy, cz, cx + t[0], cz + t[1], t[2]);
        }
        for (int[] t : VILLAGE_TREES) {
            villageTree(world, cx, cy, cz, cx + t[0], cz + t[1], t[2] == 1);
        }
    }

    /** 필지 벽 한 줄 — 한복판 한 칸은 삽짝 (직선이 아니라 '경계'로 읽히게) */
    private static void yardWall(World world, int cx, int cy, int cz,
                                 int x0, int x1, int z0, int z1, Material mat) {
        int gx = (x0 + x1) / 2;
        int gz = (z0 + z1) / 2;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (x == gx && z == gz) {
                    continue;   // 삽짝
                }
                if (freeCell(world, cx, cy, cz, x, z)) {
                    world.getBlockAt(x, gy(x, z) + 1, z).setType(mat);   // v7.2 — 필지 벽도 땅을 따른다
                }
            }
        }
    }

    /**
     * 마을 나무 한 그루 — 참나무/자작 (벚나무 = 채색 예산이므로 광장 밖에서는 쓰지 않는다).
     * 밑동 4단 + 잎 두 켜 + 꼭대기 한 칸. 지면은 뿌리 흙으로 갈아 나무가 흙에서 자란 것처럼 보이게 한다
     * (ROOTED_DIRT 는 등롱 지면 화이트리스트에도 있으므로 길 판정을 오염시키지 않는다).
     */
    private static void villageTree(World world, int cx, int cy, int cz, int x, int z, boolean birch) {
        if (!freeCell(world, cx, cy, cz, x, z)) {
            return;
        }
        int g = gy(x, z);
        for (int dx = -1; dx <= 1; dx++) {   // 잎이 덮을 자리가 다 비어 있어야 심는다 (허공에 뜬 잎 금지)
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = g + 1; y <= g + 6; y++) {
                    if (!world.getBlockAt(x + dx, y, z + dz).getType().isAir()) {
                        return;
                    }
                }
            }
        }
        growTree(world, x, g, z, birch);   // v7.2 — 나무는 제 자리 지면에서 자란다
    }

    /** 나무 한 그루 — 밑동 4단 + 잎 두 켜 + 꼭대기. gy = 그 자리의 지면 y (마을 안은 cy, 담 밖은 지형) */
    private static void growTree(World world, int x, int gy, int z, boolean birch) {
        Material log = birch ? Material.BIRCH_LOG : Material.OAK_LOG;
        Material lv = birch ? Material.BIRCH_LEAVES : Material.OAK_LEAVES;
        world.getBlockAt(x, gy, z).setType(Material.ROOTED_DIRT);
        for (int y = gy + 1; y <= gy + 4; y++) {
            world.getBlockAt(x, y, z).setType(log);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    persistentLeaf(world, x + dx, gy + 4, z + dz, lv);
                }
                persistentLeaf(world, x + dx, gy + 5, z + dz, lv);
            }
        }
        persistentLeaf(world, x, gy + 6, z, lv);
    }

    private static void persistentLeaf(World world, int x, int y, int z, Material mat) {
        Leaves data = (Leaves) mat.createBlockData();
        data.setPersistent(true);
        world.getBlockAt(x, y, z).setBlockData(data);
    }

    /**
     * v6.5 ② 공터 잡초 — **비선형 해시 + 밀도 14%**.
     * v6.4 는 `floorMod(x*13 + z*5, 9) <= 1` 이었다: 밀도 22% 에 등고선이 직선 → 마을을 덮은 초록 세로줄.
     * 이제 noise() 로 줄무늬를 깨고, 공터의 14%(< 20%) 에만 심는다 — 마당은 맨땅이 지배한다.
     * 마지막에 돈다: 등롱·노점·울타리·마을 살림이 이미 선 자리는 freeCell 이 아니므로 잡초가 뺏지 못한다.
     */
    private static void weeds(World world, int cx, int cy, int cz) {
        for (int x = cx - 57; x <= cx + 57; x++) {
            for (int z = cz - 57; z <= cz + 57; z++) {
                int h = hash(x, z, 100);
                if (h >= 14 || !freeCell(world, cx, cy, cz, x, z)) {
                    continue;
                }
                world.getBlockAt(x, gy(x, z) + 1, z).setType(
                        h < 10 ? Material.SHORT_GRASS : Material.FERN);
            }
        }
    }

    /**
     * 광장 19x19 (v6.1 ① — 7칸 대로에 걸맞은 비례. 우물은 그대로 중심).
     * 바닥은 매끄러운 돌 한 겹이 아니라 좌표 해시로 안산암·돌 벽돌을 섞어 결이 생기게 하고,
     * 가장자리 한 줄은 조약돌 테두리 — 흙길에서 돌바닥으로 넘어오는 경계가 눈에 보여야 한다.
     */
    private static void plazaAndWell(World world, int cx, int cy, int cz) {
        for (int x = cx - 9; x <= cx + 9; x++) {   // 광장 19x19
            for (int z = cz - 9; z <= cz + 9; z++) {
                boolean rim = Math.abs(x - cx) == 9 || Math.abs(z - cz) == 9;
                int h = hash(x, z, 12);   // v6.5 ② — 비선형 해시 (돌바닥의 결도 줄이 서면 안 된다)
                Material m = rim ? Material.COBBLESTONE
                        : h == 0 ? Material.ANDESITE
                        : h == 1 ? Material.STONE_BRICKS
                        : Material.SMOOTH_STONE;
                world.getBlockAt(x, cy, z).setType(m);
            }
        }
        // 우물 — 광장의 심장 (소문이 모이는 곳). 기둥 4주 + 흑와 지붕.
        for (int x = cx - 1; x <= cx + 1; x++) {
            for (int z = cz - 1; z <= cz + 1; z++) {
                boolean rim = x != cx || z != cz;
                world.getBlockAt(x, cy + 1, z).setType(rim ? Material.COBBLESTONE_WALL : Material.AIR);
                world.getBlockAt(x, cy, z).setType(rim ? Material.COBBLESTONE : Material.WATER);
            }
        }
        for (int dx = -2; dx <= 2; dx += 4) {
            for (int dz = -2; dz <= 2; dz += 4) {
                for (int y = cy + 1; y <= cy + 3; y++) {
                    world.getBlockAt(cx + dx, y, cz + dz).setType(Material.SPRUCE_FENCE);
                }
            }
        }
        for (int x = cx - 2; x <= cx + 2; x++) {
            for (int z = cz - 2; z <= cz + 2; z++) {
                boolean center = x == cx && z == cz;
                world.getBlockAt(x, cy + 4, z).setType(
                        center ? Material.DEEPSLATE_TILES : Material.DEEPSLATE_TILE_SLAB);
            }
        }
        // v6.2 ① — 두레박: 지붕 한복판에서 내린 사슬 2칸 + 물통 (우물은 길어 올려야 우물이다)
        for (int y = cy + 2; y <= cy + 3; y++) {
            Orientable chain = (Orientable) Material.IRON_CHAIN.createBlockData();
            chain.setAxis(Axis.Y);
            world.getBlockAt(cx, y, cz).setBlockData(chain);
        }
        world.getBlockAt(cx + 1, cy + 2, cz + 1).setType(Material.CAULDRON);   // 우물가 물통 (수마루 위)
    }

    /**
     * 십자 대로 — 폭 7칸 (v6.1 ①: 수레가 교행하는 폭). 중앙 5칸 흙길 + 양 갓길 1칸씩 자갈/거친 흙.
     * 흙길에도 좌표 해시로 자갈·거친 흙을 점치환해 직선의 딱딱함을 깬다 (난수 아님 — 상수식).
     * 광장(±9) 바로 밖 d=10 에서 시작해 담 안쪽 발치(d=59)까지. 북로는 북문 너머 산길로 이어진다.
     */
    private static void roads(World world, int cx, int cy, int cz) {
        for (int d = 10; d <= 59; d++) {
            for (int w = -3; w <= 3; w++) {
                boolean shoulder = Math.abs(w) == 3;
                roadCell(world, cx + w, cy, cz - d, shoulder);
                roadCell(world, cx + w, cy, cz + d, shoulder);
                roadCell(world, cx - d, cy, cz + w, shoulder);
                roadCell(world, cx + d, cy, cz + w, shoulder);
            }
        }
    }

    /**
     * 길 한 칸 — 노면(cy)과 노반(cy-1)을 함께 깐다. 길이 한 겹 종이가 아니라 다져진 단면이 되게.
     * shoulder(갓길) = 자갈/거친 흙 교대, 노면 = 흙길에 자갈·거친 흙 20% 점치환. 전부 좌표 해시.
     */
    private static void roadCell(World world, int x, int cy, int z, boolean shoulder) {
        int h = hash(x, z, 10);   // v6.5 ② — 점치환 해시도 비선형으로 (사선 줄무늬 제거)
        Material top;
        if (shoulder) {
            // 갓길도 노면의 일부다 — 거친 흙을 섞으면 마차가 다니는 폭이 줄어 보인다
            top = Material.GRAVEL;
        } else {
            // 노면은 흙길·자갈만 — 거친 흙을 섞으면 그건 '길'이 아니라 '땅'이다 (담장 안 지면의
            // 62%가 거친 흙이라 노면과 구별이 사라진다). 질감은 자갈 점치환으로 낸다.
            top = h < 2 ? Material.GRAVEL : Material.DIRT_PATH;
        }
        world.getBlockAt(x, cy, z).setType(top);
        world.getBlockAt(x, cy - 1, z).setType(Material.COARSE_DIRT);   // 노반 — 흙길 밑 다짐
    }

    /** 디딤돌 — 흙길에서 문지방으로 들어가는 전이. 조약돌/안산암 교대 (좌표 해시) */
    private static void steppingStone(World world, int x, int cy, int z) {
        world.getBlockAt(x, cy, z).setType(
                hash(x, z, 2) == 0 ? Material.COBBLESTONE : Material.ANDESITE);
        world.getBlockAt(x, cy - 1, z).setType(Material.COARSE_DIRT);
    }

    /**
     * 건물 입구 ↔ 대로 연결 소로 — 폭 3칸 (문이 2칸인 객잔만 4칸). 대로 접속부 한 줄은 디딤돌.
     * 대로가 x/z ±3 을 먹으므로 소로는 ±4 에서 끝난다 (대로가 소로를 삼키지 않게).
     */
    private static void doorPaths(World world, int cx, int cy, int cz) {
        // v6.2 ② — 소로가 아니라 '앞마당'이다. 문 앞 다진 흙 + 문지방 줄 디딤돌 → 대로 접속.
        // v7.0 ③ — 건물이 커지면서 벽이 대로 쪽으로 두 칸 나왔다: 앞마당은 z∓5..∓4 두 줄(디딤돌 + 흙길)로 조인다.
        //   검수 ①-b(문 앞 소로 ≥2)는 통로폭 = min(runX, runZ) 이고, 앞마당 줄(z∓4)이 대로 갓길(z∓3)과
        //   맞닿아 z 스팬이 대로와 하나로 이어지므로 runZ ≥ 9 · runX = 앞마당 폭(7~8) → 통로폭 7 이상.
        doorPath(world, cx - 26, cx - 19, cz - 5, cz - 4, cy, cz - 5);          // 객잔 (남향 2칸 대문 x-23·-22)
        doorPath(world, cx + 15, cx + 21, cz - 5, cz - 4, cy, cz - 5);          // 의뢰소 (남향 문 x+18)
        doorPath(world, cx - 21, cx - 15, cz + 4, cz + 5, cy, cz + 5);          // 의방 (북향 문 x-18)
        doorPath(world, cx + 15, cx + 21, cz + 4, cz + 5, cy, cz + 5);          // 전장 (북향 문 x+18)
        doorPath(world, cx - 46, cx - 42, cz + 17, cz + 18, cy, cz + 17);       // 민가 9 → 남골목 (v7.0 ③ — 문 x-44)
        // v7.0 ① 관아 진입 소로 — 남골목(z+21) → 삼문(z+33). 폭 3칸(축선 x-21..-19), 접속부 한 줄은 디딤돌.
        doorPath(world, cx - 21, cx - 19, cz + 22, cz + 32, cy, cz + 22);
        // 표국 — 남골목(z+19..21) → 민가 8 동측 우회(x+44..46) → 대문 앞(z+33..35) → 대문(z+36)
        for (int x = cx + 44; x <= cx + 46; x++) {
            for (int z = cz + 20; z <= cz + 35; z++) {
                world.getBlockAt(x, cy, z).setType(Material.DIRT_PATH);
                world.getBlockAt(x, cy - 1, z).setType(Material.COARSE_DIRT);
            }
        }
        for (int x = cx + 39; x <= cx + 46; x++) {   // z+34..+35 — 민가 8 남벽줄(z+33)을 먹지 않는다
            for (int z = cz + 34; z <= cz + 35; z++) {
                world.getBlockAt(x, cy, z).setType(Material.DIRT_PATH);
                world.getBlockAt(x, cy - 1, z).setType(Material.COARSE_DIRT);
            }
        }
        for (int x = cx + 39; x <= cx + 41; x++) {
            steppingStone(world, x, cy, cz + 35);   // 표국 대문 앞 디딤돌
        }
    }

    /**
     * v6.2 ② — 민가 문턱 디딤돌. 민가의 문은 이미 골목에 면해 있다(cottages 배치 규칙).
     * 골목 바깥 줄(z∓21)의 문 앞 3칸을 디딤돌로 바꿔 "이 벽에 문이 있다"를 위에서도 읽히게 한다.
     * {문 x, 골목 줄 z} — 전부 상수 (민가 조합표에서 doorX = x0 + w/2 로 유도한 값).
     */
    private static final int[][] COTTAGE_DOORSTEPS = {
            {-38, -21}, {-30, -21},   // #1 본채 · 대장간 작업간
            {-11, -22},               // #2 (v6.3 ③ — 집이 1칸 북으로 물러나 문턱도 z-22)
            {13, -21},                // #3 (v7.0 ② 빈촌 강등 11x9 — 문 x+14 → x+13)
            {33, -21},                // #4
            {49, -19},                // #5 (북향 — 골목 남쪽 줄. v7.0 ③ 15x11 로 커져 문 x+48→x+49)
            {-37, 21},                // #6
            {14, 22}, {22, 22},       // #7 본채 · 직조간 (v6.3 ③ — 집이 1칸 남으로 물러나 문턱도 z+22)
            {38, 21},                 // #8
            {-44, 19},                // #9 (v7.0 ③ 15x11 — 남벽 z+18 이 남골목에 붙어 소로가 필요 없어졌다)
    };

    private static void cottageDoorsteps(World world, int cx, int cy, int cz) {
        for (int[] d : COTTAGE_DOORSTEPS) {
            for (int dx = -1; dx <= 1; dx++) {
                steppingStone(world, cx + d[0] + dx, cy, cz + d[1]);
            }
        }
    }

    /** 소로 한 줄기 — [x0..x1] x [z0..z1] 흙길, stoneZ 줄만 디딤돌 (대로 접속부 전이) */
    private static void doorPath(World world, int x0, int x1, int z0, int z1, int cy, int stoneZ) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (z == stoneZ) {
                    steppingStone(world, x, cy, z);
                } else {
                    world.getBlockAt(x, cy, z).setType(Material.DIRT_PATH);
                    world.getBlockAt(x, cy - 1, z).setType(Material.COARSE_DIRT);
                }
            }
        }
    }

    /**
     * 민가 골목 — 폭 3칸. 북골목 z-21..z-19, 남골목 z+19..z+21 (민가 문턱 줄 z∓22 에 딱 붙는다).
     * 골목이 민가 벽줄을 먹지 않도록 중심을 z∓20 으로 잡았다 — 벽 밑에 흙길이 깔리면 문턱이 죽는다.
     * 가장자리 두 줄엔 좌표 해시로 잡초를 점치환 — 사람이 덜 밟는 자리엔 풀이 남는다.
     * 잡초는 빈 칸에만 심는다 (담·벽·문설주를 밀어내지 않게).
     */
    private static void alleys(World world, int cx, int cy, int cz) {
        for (int x = cx - 45; x <= cx + 45; x++) {
            for (int w = -1; w <= 1; w++) {
                alleyCell(world, x, cy, cz - 20 + w, w != 0);
                alleyCell(world, x, cy, cz + 20 + w, w != 0);
            }
        }
    }

    private static void alleyCell(World world, int x, int cy, int z, boolean edge) {
        int h = hash(x, z, 10);   // v6.5 ② — 점치환 해시도 비선형으로 (사선 줄무늬 제거)
        // 노면에 거친 흙 금지 — 담장 안 지면의 절반이 거친 흙이라 그걸 섞으면 길이 땅과
        // 구별되지 않고, 그 칸에서 통행 폭이 끊긴다 (검수: 골목 폭 0 — 대로에서도 같은 병이었다)
        world.getBlockAt(x, cy, z).setType(
                edge && h < 2 ? Material.GRAVEL : Material.DIRT_PATH);
        world.getBlockAt(x, cy - 1, z).setType(Material.COARSE_DIRT);
        if (edge && h == 5 && world.getBlockAt(x, cy + 1, z).getType().isAir()) {
            world.getBlockAt(x, cy + 1, z).setType(Material.SHORT_GRASS);   // 갓길 잡초
        }
    }

    // ─── 담장과 대문 — 마을의 경계 ───

    private static void townWall(World world, int cx, int cy, int cz) {
        int r = 60;
        for (int x = cx - r; x <= cx + r; x++) {
            wallColumn(world, x, cy, cz - r, x - (cx - r), true);
            wallColumn(world, x, cy, cz + r, x - (cx - r), true);
        }
        for (int z = cz - r + 1; z <= cz + r - 1; z++) {
            wallColumn(world, cx - r, cy, z, z - (cz - r), false);
            wallColumn(world, cx + r, cy, z, z - (cz - r), false);
        }
        for (int i = -r + 1; i <= r - 1; i++) {   // 담 안쪽 발치 1칸 = 흙길 (경계의 안팎을 지면으로도 가른다)
            world.getBlockAt(cx + i, cy, cz - r + 1).setType(Material.DIRT_PATH);
            world.getBlockAt(cx + i, cy, cz + r - 1).setType(Material.DIRT_PATH);
            world.getBlockAt(cx - r + 1, cy, cz + i).setType(Material.DIRT_PATH);
            world.getBlockAt(cx + r - 1, cy, cz + i).setType(Material.DIRT_PATH);
        }
        for (int dx = -r; dx <= r; dx += 2 * r) {   // 모서리 각루 3x3 — 목주 4 + 난간 + 흑와 모임지붕
            for (int dz = -r; dz <= r; dz += 2 * r) {
                cornerTower(world, cx + dx, cy, cz + dz);
            }
        }
        gate(world, cx, cy, cz - r, true, "청하현 북문", "북쪽 산길 — 나갈 때 조심");
        gate(world, cx, cy, cz + r, false, "청하현 남문", "관도 — 섬서 지역권");
        for (int x = cx - 3; x <= cx + 3; x++) {   // 남문 밖 관도 — 대로와 같은 7칸 (지역권 간선)
            for (int z = cz + r + 1; z <= cz + r + 6; z++) {
                roadCell(world, x, cy, z, Math.abs(x - cx) == 3);
            }
        }
    }

    /**
     * 담 한 칸 — 하단 조약돌 1단 + 상단 돌 벽돌 2단. v6 ⑤ 리듬 3종 (전부 좌표식 결정론):
     * ① 12칸마다 조약돌 세로 1열 = 판축(版築) 이음매  ② 최상단은 여장(담장 블록) / 기와 갓(심층암 반 블록)
     * 6칸 교대 — 갓 구간 양끝은 계단 1개씩(갓의 처마)  ③ 발치 조약돌 10% 이끼 점치환 (세월).
     */
    private static void wallColumn(World world, int x, int cy, int z, int idx, boolean alongX) {
        boolean seam = Math.floorMod(idx, 12) == 0;
        boolean mossy = Math.floorMod(idx * 7, 10) == 0;
        world.getBlockAt(x, cy + 1, z).setType(
                mossy && !seam ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE);
        world.getBlockAt(x, cy + 2, z).setType(seam ? Material.COBBLESTONE : Material.STONE_BRICKS);
        if (seam) {
            world.getBlockAt(x, cy + 3, z).setType(Material.COBBLESTONE);
            return;
        }
        if (Math.floorMod(idx / 6, 2) == 0) {
            world.getBlockAt(x, cy + 3, z).setType(Material.STONE_BRICK_WALL);   // 여장
            return;
        }
        int m = Math.floorMod(idx, 6);
        boolean cross = !alongX;   // v6.6 ① 기와 갓의 결도 담장이 뻗는 방향을 따른다 (Z 담장 = 직각 결)
        if (m == 0 || m == 5) {   // 기와 갓 구간의 양끝 = 갓의 처마
            BlockFace face = alongX
                    ? (m == 0 ? BlockFace.WEST : BlockFace.EAST)
                    : (m == 0 ? BlockFace.NORTH : BlockFace.SOUTH);
            stair(world, x, cy + 3, z, stairMat(RoofStyle.TILE, cross), face);
        } else {
            world.getBlockAt(x, cy + 3, z).setType(ridgeMat(RoofStyle.TILE, cross));   // 기와 갓
        }
    }

    /** 각루 3x3 — 목주 4 + 담장 난간 + 흑와 모임지붕(계단 1링 + 풀 블록). 실루엣이 성곽을 만든다 */
    private static void cornerTower(World world, int tx, int cy, int tz) {
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                for (int y = cy + 1; y <= cy + 5; y++) {
                    world.getBlockAt(tx + dx, y, tz + dz).setType(Material.DARK_OAK_LOG);   // 목주 4
                }
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                boolean post = dx != 0 && dz != 0;
                boolean edge = dx != 0 || dz != 0;
                if (!post) {
                    world.getBlockAt(tx + dx, cy + 4, tz + dz).setType(Material.DEEPSLATE_TILE_SLAB);   // 바닥
                    if (edge) {
                        world.getBlockAt(tx + dx, cy + 5, tz + dz).setType(Material.STONE_BRICK_WALL);  // 난간
                    }
                }
            }
        }
        for (int dx = -2; dx <= 2; dx++) {   // 모임지붕 — 계단 1링
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) != 2 && Math.abs(dz) != 2) {
                    continue;
                }
                boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                BlockFace face = Math.abs(dx) == 2
                        ? (dx < 0 ? BlockFace.EAST : BlockFace.WEST)
                        : (dz < 0 ? BlockFace.SOUTH : BlockFace.NORTH);
                // 모임지붕 — 동·서 면은 직각 결, 추녀마루(모서리) 4칸은 TILE 로 통일
                roofBlock(world, tx + dx, cy + 6, tz + dz, face, corner, RoofStyle.TILE,
                        !corner && crossGrain(face));
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.getBlockAt(tx + dx, cy + 7, tz + dz).setType(
                        dx == 0 && dz == 0 ? Material.DEEPSLATE_TILES : Material.DEEPSLATE_TILE_SLAB);
            }
        }
        hangingLantern(world, tx, cy + 6, tz);   // 각루 등롱 (지붕 밑)
    }

    /**
     * 대문 — 개구부 5칸(v6.1 ① — 7칸 대로가 3칸 문으로 조여들면 목이 막힌 것처럼 보인다) + 목주 문루
     * + 흑와 처마 + 현판 "청하현" + 안팎 양면 현수 등롱 + 초소 자리(대로 갓길 밖 x-5..-6).
     */
    private static void gate(World world, int gx, int cy, int gz, boolean north,
                             String name, String subtitle) {
        for (int x = gx - 2; x <= gx + 2; x++) {
            for (int y = cy + 1; y <= cy + 3; y++) {
                world.getBlockAt(x, y, gz).setType(Material.AIR);
            }
            roadCell(world, x, cy, gz, false);
        }
        for (int side = -3; side <= 3; side += 6) {   // 문주 — 개구 5칸 바깥
            for (int y = cy + 1; y <= cy + 4; y++) {
                world.getBlockAt(gx + side, y, gz).setType(Material.DARK_OAK_LOG);
            }
        }
        for (int x = gx - 3; x <= gx + 3; x++) {
            world.getBlockAt(x, cy + 5, gz).setType(Material.DARK_OAK_PLANKS);   // 인방
        }
        for (int dz = -2; dz <= 2; dz++) {   // 문루 지붕 — 안팎 2칸씩 내민 처마 (v6.1 ②의 원칙을 대문에도)
            int shrink = Math.abs(dz);
            for (int x = gx - 4 + shrink; x <= gx + 4 - shrink; x++) {
                if (Math.abs(dz) == 2) {
                    stair(world, x, cy + 6, gz + dz, Material.DEEPSLATE_TILE_STAIRS,
                            dz < 0 ? BlockFace.SOUTH : BlockFace.NORTH);
                } else if (dz == 0) {
                    world.getBlockAt(x, cy + 7, gz).setType(Material.DEEPSLATE_TILES);   // 용마루
                    world.getBlockAt(x, cy + 6, gz).setType(Material.DEEPSLATE_TILES);
                } else {
                    world.getBlockAt(x, cy + 6, gz + dz).setType(Material.DEEPSLATE_TILES);
                }
            }
        }
        for (int x = gx - 4; x <= gx + 4; x++) {   // 서까래 라인 — 깊은 처마의 그림자
            world.getBlockAt(x, cy + 5, gz - 2).setType(Material.DARK_OAK_SLAB);
            world.getBlockAt(x, cy + 5, gz + 2).setType(Material.DARK_OAK_SLAB);
        }
        int in = north ? 1 : -1;   // 마을 안쪽 방향
        for (int side = -2; side <= 2; side += 4) {   // 현수 등롱 — 마을 안팎 양면 (밤에 돌아오는 낭인의 등대)
            chainLantern(world, gx + side, cy + 4, gz + 2, 1);
            chainLantern(world, gx + side, cy + 4, gz - 2, 1);
        }
        hangingSign(world, gx, cy + 4, gz + in * 2, north ? BlockFace.SOUTH : BlockFace.NORTH,
                "청하현", subtitle);   // 대문 현판 — 등록 지명 그대로
        placeSign(world, gx + 5, cy + 1, gz + in, north ? BlockFace.SOUTH : BlockFace.NORTH, name, subtitle);
        // 초소 자리 — 문지기의 부재도 자리로 말한다. 대로 갓길(±3) 밖에 물려 세운다.
        stair(world, gx - 5, cy + 1, gz + in * 2, Material.SPRUCE_STAIRS,
                north ? BlockFace.SOUTH : BlockFace.NORTH);
        world.getBlockAt(gx - 6, cy + 1, gz + in * 2).setType(Material.BARREL);
        world.getBlockAt(gx - 6, cy + 2, gz + in * 2).setType(Material.LANTERN);
    }

    // ─── v6.7 ③ 담장 밖 접근부 — 마을은 담에서 끝나지 않는다 ───
    //
    // 조감도의 담장 밖은 밋밋한 초록 평지였다. 안만 다지고 밖을 비워 두면 마을이 접시 위에 얹힌
    // 모형으로 보인다. 향촌은 **길로 세상과 이어져 있다**: 남문 밖으로는 관도(官道)가 지역권으로 곧게 뻗고,
    // 북문 밖으로는 산길이 서쪽으로 비틀리며 숲으로 든다. 그 길가에 이정표·돌무더기·나무·풀숲이 선다.
    //
    // 세 규칙 —
    //   ① 【평탄화 금지】 폐사당과 같은 원칙이다. 담 밖은 clearAndFlatten(±62)·blendEdge(±68) 의 손이
    //      닿지 않는 자연 지형이다. 길은 땅을 깎지 않고 **지표 y 를 따라 얹힌다**(outsideGroundY).
    //      물을 만나면 메우지 않는다 — 수면에 참나무 널다리를 놓아 건넌다 (호수를 흙으로 메우면 그건 조경이 아니다).
    //   ② 【재조성 결정론】 outsideGroundY 는 **이미 깐 노면(흙길)도 지면으로 인정한다**. 그러지 않으면
    //      두 번째 조성이 노면 밑 흙을 지면으로 읽어 길이 한 칸씩 가라앉는다 (폐사당 부지 판정과 같은 함정).
    //      돌무더기(이끼 낀 돌 벽돌)·이정표(목책)·널다리(참나무 널)는 전부 자연 지면 화이트리스트 **밖**이라
    //      다음 조성의 지면 판정이 그대로 통과해 내려간다 → 두 번 조성해도 같은 높이, 같은 그림.
    //   ③ 【검수 불변】 노면 판정 자재(조약돌·안산암·돌 벽돌·매끈한 돌)를 **소품에 쓰지 않는다** —
    //      담 밖 ±65 는 아직 검수 스캔 안이라 마당의 돌 한 장이 '길'로 세어진다 (v6.5 ③과 같은 함정).
    //      돌무더기는 MOSSY_STONE_BRICKS + 돌담 블록(둘 다 PATH 집합 밖). 채색 0 (참나무·자작·짚·흙뿐 —
    //      벚나무는 광장의 몫), 냉색 0. 폐사당 부지(북서)는 통째로 비워 둔다.

    private static final int OUT_NEAR = 61;   // 담(r=60) 바로 밖 첫 칸
    private static final int OUT_FAR = 92;    // 접근부 끝 — 담에서 32칸

    /**
     * 담 밖 소품 — {dx, dz, 종류}. 0 이정표(관도) / 1 이정표(산길) / 2 돌무더기 / 3 길가 쉼터(걸상·짚단).
     * 좌표는 전부 상수 (난수 0). 노면 중심(관도 x=cx±1 · 산길 x=cx+off..+1)에서 3칸 이상 비켜세운다.
     */
    private static final int[][] OUTSIDE_PROPS = {
            // 남문 밖 관도
            {4, 63, 0}, {-4, 67, 2}, {5, 74, 2}, {-6, 80, 0}, {6, 87, 2}, {-5, 71, 3},
            // 북문 밖 산길 (서쪽으로 비틀린다 — 소품도 따라 물러난다)
            {-4, -63, 1}, {4, -68, 2}, {-8, -75, 2}, {2, -81, 1}, {-11, -87, 2}, {5, -73, 3},
            // 동·서 담장 밖 (문이 없는 변에도 사람이 다닌 흔적은 있다)
            {64, -12, 2}, {-64, 14, 2}, {66, 32, 2}, {-65, -22, 2}, {63, 52, 3}, {-63, 46, 2},
    };

    /** 담 밖 나무 — {dx, dz, 자작나무?}. 참나무·자작뿐 (벚나무 = 채색 예산 = 광장 전용) */
    private static final int[][] OUTSIDE_TREES = {
            {6, 64, 0}, {-7, 70, 1}, {8, 77, 0}, {-9, 83, 1}, {7, 90, 0}, {-6, 88, 1}, {10, 68, 1},
            {6, -66, 1}, {-8, -71, 0}, {9, -78, 1}, {-13, -84, 0}, {4, -89, 1}, {-14, -64, 0}, {11, -74, 0},
            {64, -30, 0}, {68, -4, 1}, {66, 20, 0}, {70, 44, 1}, {63, 62, 0}, {72, 12, 0},
            {-64, -8, 1}, {-68, 18, 0}, {-66, 40, 1}, {-63, 58, 0}, {-70, -30, 1}, {-72, 6, 0},
    };

    /** 폐사당 부지 회피 — 북서 외곽은 폐허의 몫이다 (접근부가 한 블록도 넘어가지 않는다) */
    private static boolean shrineKeepout(int dx, int dz) {
        return dx <= -66 && dz <= -46;
    }

    /**
     * 담 밖 지면 — 최상단에서 내려가며 자연 지면 **또는 이미 깔린 흙길**에 처음 닿는 y.
     * 물·용암을 만나면 MIN_VALUE (그 칸은 널다리의 몫). DIRT_PATH 를 지면으로 인정하는 것이 핵심이다:
     * 그러지 않으면 재조성 때 노면 밑 흙을 읽어 길이 조성할 때마다 한 칸씩 내려앉는다.
     */
    private static int outsideGroundY(World world, int x, int z) {
        int top = Math.min(world.getHighestBlockYAt(x, z), world.getMaxHeight() - 1);
        int floor = Math.max(world.getMinHeight(), top - 48);
        for (int y = top; y >= floor; y--) {
            Material m = world.getBlockAt(x, y, z).getType();
            if (WET.contains(m)) {
                return Integer.MIN_VALUE;
            }
            if (m == Material.DIRT_PATH || NATURAL_GROUND.contains(m)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** 널다리 놓을 높이 — 이미 놓인 참나무 널이 있으면 그 y (재조성 시 다리가 가라앉지 않는다), 없으면 수면 y */
    private static int bridgeY(World world, int x, int z) {
        int top = Math.min(world.getHighestBlockYAt(x, z), world.getMaxHeight() - 1);
        int floor = Math.max(world.getMinHeight(), top - 48);
        for (int y = top; y >= floor; y--) {
            Material m = world.getBlockAt(x, y, z).getType();
            if (m == Material.OAK_PLANKS || m == Material.WATER) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** 담 밖 접근부 — 관도·산길·이정표·돌무더기·나무·풀숲 */
    private static void approaches(World world, int cx, int cy, int cz) {
        southHighway(world, cx, cy, cz);
        northTrail(world, cx, cy, cz);
        for (int[] p : OUTSIDE_PROPS) {
            outsideProp(world, cx, cz, p[0], p[1], p[2]);
        }
        for (int[] t : OUTSIDE_TREES) {
            outsideTree(world, cx, cz, t[0], t[1], t[2] == 1);
        }
        outskirtGrove(world, cx, cz);   // 길가 나무를 먼저 심고, 남은 들녘에 성긴 숲을 흩뿌린다
    }

    /**
     * v7.1 ③ 【노선 정지(整地) — 길은 계단이 아니다】
     *
     * <p>구 접근로는 지형에 그대로 얹혔다(outsideGroundY 를 열마다 따로 읽었다). 평지에서는 통했지만
     * 언덕·둔덕을 만나면 한 걸음에 3~5칸씩 뛰는 **계단**이 됐다 — 검수 ④(연결성)의 BFS 는 한 칸 도약만
     * 허용하므로, 그런 길은 '길'이 아니다.
     *
     * <p>대신 **종단 구배(縱斷勾配)** 를 먼저 잡는다: 문 앞(d=OUT_NEAR)에서 마을 지면 cy 로 출발해,
     * 한 칸 나아갈 때마다 **최대 ±1칸**만 오르내리며 자연 지면을 따라간다. 그렇게 만든 표고선 p[d] 를
     * 노면 높이로 삼고, 그 높이에 맞춰 **깎고 · 쌓고 · 봉인한다**(shapeColumn 과 같은 규칙).
     * → 담장에서 담 밖 32칸까지 **계단 0** 이 좌표식으로 보장된다.
     *
     * <p>재조성 결정론: outsideGroundY 는 DIRT_PATH·GRAVEL 을 지면으로 인정하므로, 두 번째 조성은
     * 첫 조성이 깐 노면(=p[d])을 그대로 읽어 같은 표고선을 얻는다 (길이 조성마다 내려앉지 않는다).
     */
    private static int[] gradeProfile(World world, int cx, int cy, int cz, boolean south, int last) {
        int n = last - OUT_NEAR + 1;
        int[] p = new int[n];
        int prev = cy;                               // 성문 앞 = 마을 지면. 여기서 출발한다.
        for (int k = 0; k < n; k++) {
            int d = OUT_NEAR + k;
            int x = south ? cx : cx - ((d - OUT_NEAR) / 6);   // 산길은 6칸마다 서쪽으로 한 칸 비튼다
            int z = south ? cz + d : cz - d;
            int g = outsideGroundY(world, x, z);
            int want = (g == Integer.MIN_VALUE) ? prev : g;   // 물 = 널다리 → 높이를 유지한 채 건넌다
            prev = Math.max(prev - 1, Math.min(prev + 1, want));
            p[k] = prev;
        }
        return p;
    }

    /** 남문 밖 관도 — 폭 3칸, 곧게 (간선은 굽지 않는다). 담 밖 7칸 광폭 접속부는 townWall 이 이미 깔았다 */
    private static void southHighway(World world, int cx, int cy, int cz) {
        int[] p = gradeProfile(world, cx, cy, cz, true, OUT_FAR);
        for (int d = OUT_NEAR; d <= OUT_FAR; d++) {
            int y = p[d - OUT_NEAR];
            for (int w = -1; w <= 1; w++) {
                outsideRoadCell(world, cx + w, y, cz + d, false);
            }
            roadShoulder(world, cx - 2, y, cz + d);   // 노면과 자연 지면 사이 한 칸 완충 (측면 절개면 완화)
            roadShoulder(world, cx + 2, y, cz + d);
            roadsideBrush(world, cx, cz, cx, cz + d);
            // 길가 등롱 — 관도는 밤에도 걷는다. 측거 4 (담 밖 7칸 접속부의 자갈 갓길 ±3 을 비켜선다 —
            // 등롱은 노면에 서지 않는다) · 간격 4 → 노면 어느 칸도 맨해튼 8 안 = 광원 7 이상.
            if (Math.floorMod(d, 4) == 2) {
                roadsideLantern(world, cx - 4, cz + d);
                roadsideLantern(world, cx + 4, cz + d);
            }
        }
    }

    /** 북문 밖 산길 — 폭 2칸, 6칸마다 서쪽으로 한 칸씩 비틀린다 (좌표식 — 난수 0). 자갈·거친 흙 노면 */
    private static void northTrail(World world, int cx, int cy, int cz) {
        int[] p = gradeProfile(world, cx, cy, cz, false, OUT_FAR);
        for (int d = OUT_NEAR; d <= OUT_FAR; d++) {
            int off = -((d - OUT_NEAR) / 6);
            int y = p[d - OUT_NEAR];
            for (int w = 0; w <= 1; w++) {
                outsideRoadCell(world, cx + off + w, y, cz - d, true);
            }
            roadShoulder(world, cx + off - 1, y, cz - d);
            roadShoulder(world, cx + off + 2, y, cz - d);
            roadsideBrush(world, cx, cz, cx + off, cz - d);
            if (Math.floorMod(d, 4) == 2) {   // 산길 등롱 — 측거 3~4 · 간격 4 (노면 밖 맨땅에만 선다)
                roadsideLantern(world, cx + off - 3, cz - d);
                roadsideLantern(world, cx + off + 4, cz - d);
            }
        }
    }

    /**
     * 담 밖 노면 한 칸 — **계획 표고 y 에** 앉힌다 (지형이 아니라 노선이 높이를 정한다).
     * 뭍이면 흙길/자갈 + 밑 5칸 봉인(검수 ①), 물이면 널다리 + 널 밑 기둥(다리 밑이 공기면 그것도 껍데기다).
     * 노면 위 3칸은 비운다 — 길은 걸을 수 있어야 길이다.
     */
    private static void outsideRoadCell(World world, int x, int y, int z, boolean trail) {
        int g = outsideGroundY(world, x, z);
        if (g == Integer.MIN_VALUE) {                 // 물·심연 — 메우지 않고 건넌다
            world.getBlockAt(x, y, z).setType(Material.OAK_PLANKS);
            for (int d = 1; d <= 5; d++) {            // 교각 — 널 밑의 공기를 나무 기둥으로 채운다
                Block b = world.getBlockAt(x, y - d, z);
                if (!b.getType().isAir()) {
                    break;                            // 물·지형에 닿았다 (물은 메우지 않는다)
                }
                b.setType(Material.OAK_FENCE);
            }
            clearHeadroom(world, x, y, z);
            return;
        }
        int h = hash(x, z, 10);   // 비선형 해시 (v6.5 ② — 노면에 사선 줄무늬가 서면 안 된다)
        Material top = trail
                ? (h < 4 ? Material.GRAVEL : h < 6 ? Material.COARSE_DIRT : Material.DIRT_PATH)
                : (h < 2 ? Material.GRAVEL : Material.DIRT_PATH);
        clearHeadroom(world, x, y, z);
        world.getBlockAt(x, y, z).setType(top);
        Material nat = world.getBlockAt(x, g, z).getType();
        Material sub = subsoil(NATURAL_GROUND.contains(nat) ? nat : Material.DIRT);
        for (int d = 1; d <= 5; d++) {                // 노반 + 봉인 — 길 밑이 비면 길이 껍데기가 된다
            Block b = world.getBlockAt(x, y - d, z);
            if (!firm(b.getType())) {
                b.setType(d <= 2 ? sub : bedrock(y - d));
            }
        }
    }

    /** 노면 위를 비운다 — 절개면의 흙·풀·나무를 걷어낸다 (통행고 3칸 + 잘린 나무를 남기지 않는다) */
    private static void clearHeadroom(World world, int x, int y, int z) {
        int top = Math.min(world.getHighestBlockYAt(x, z), world.getMaxHeight() - 1);
        for (int cy2 = y + 1; cy2 <= Math.max(top, y + 3); cy2++) {
            Block b = world.getBlockAt(x, cy2, z);
            if (!b.getType().isAir()) {
                b.setType(Material.AIR);
            }
        }
    }

    /**
     * 갓길 한 칸 — 노면(y)과 자연 지면 사이의 단차를 반으로 접는다. 절개면이 1칸을 넘으면 그 한 칸만
     * 노면 높이로 끌어와(±1) 옆으로 새 계단을 만들지 않는다. 물 칸은 건드리지 않는다.
     */
    private static void roadShoulder(World world, int x, int y, int z) {
        int g = outsideGroundY(world, x, z);
        if (g == Integer.MIN_VALUE || Math.abs(g - y) <= 1) {
            return;                                   // 물이거나 이미 완만하다
        }
        int tg = g > y ? y + 1 : y - 1;               // 노면에서 한 칸만 벌린다
        Material nat = world.getBlockAt(x, g, z).getType();
        shapeColumn(world, x, z, tg, NATURAL_GROUND.contains(nat) ? nat : Material.GRASS_BLOCK, 5);
    }

    /** 길가 풀숲 — 노면 양옆 2~5칸의 16% (맨땅이 지배하되 길가엔 풀이 남는다) */
    private static void roadsideBrush(World world, int cx, int cz, int rx, int rz) {
        for (int dx = -5; dx <= 5; dx++) {
            if (Math.abs(dx) < 2) {
                continue;   // 노면·갓길은 비운다
            }
            int x = rx + dx;
            if (shrineKeepout(x - cx, rz - cz)) {
                continue;
            }
            int h = hash(x, rz, 100);
            if (h >= 16) {
                continue;
            }
            int g = outsideGroundY(world, x, rz);
            if (g == Integer.MIN_VALUE) {
                continue;
            }
            Material ground = world.getBlockAt(x, g, rz).getType();
            if (ground != Material.GRASS_BLOCK && ground != Material.DIRT) {
                continue;
            }
            if (!world.getBlockAt(x, g + 1, rz).getType().isAir()) {
                continue;
            }
            world.getBlockAt(x, g + 1, rz).setType(h < 11 ? Material.SHORT_GRASS : Material.FERN);
        }
    }

    /** 길가 등롱 — 지형 높이에 맞춰 선다. 맨땅에만(LAMP_GROUND) = 노면 위에 서지 않는다 */
    private static void roadsideLantern(World world, int x, int z) {
        int g = outsideGroundY(world, x, z);
        if (g == Integer.MIN_VALUE || !LAMP_GROUND.contains(world.getBlockAt(x, g, z).getType())) {
            return;
        }
        for (int y = g + 1; y <= g + 3; y++) {
            if (!outsideVacant(world, x, y, z)) {
                return;   // 나무·소품·지형 — 등롱이 밀어내지 않는다 (길가 풀숲만 대신한다)
            }
        }
        world.getBlockAt(x, g + 1, z).setType(Material.SPRUCE_FENCE);
        world.getBlockAt(x, g + 2, z).setType(Material.SPRUCE_FENCE);
        world.getBlockAt(x, g + 3, z).setType(Material.LANTERN);
        lampApron(world, x, g, z);   // v7.1 — 길가 등롱도 발치는 흙 (노면 옆의 이음매를 만들지 않는다)
    }

    /** 담 밖 소품 한 점 — 이정표·돌무더기·길가 쉼터 */
    private static void outsideProp(World world, int cx, int cz, int dx, int dz, int kind) {
        if (shrineKeepout(dx, dz)) {
            return;
        }
        int x = cx + dx, z = cz + dz;
        int g = outsideGroundY(world, x, z);
        if (g == Integer.MIN_VALUE) {
            return;
        }
        switch (kind) {
            case 0, 1 -> {   // 이정표 — 목책 장대 + 나무 팻말 (길에서 읽는다)
                if (!outsideVacant(world, x, g + 1, z) || !outsideVacant(world, x, g + 2, z)) {
                    return;
                }
                world.getBlockAt(x, g + 1, z).setType(Material.OAK_FENCE);
                placeSign(world, x, g + 2, z, dx < 0 ? BlockFace.EAST : BlockFace.WEST,
                        kind == 0 ? "← 청하현" : "북쪽 산길 →",
                        kind == 0 ? "관도 — 섬서 지역권" : "도적 소문 — 밤길 조심");
            }
            case 2 -> stonePile(world, x, z);
            default -> {   // 길가 쉼터 — 통나무 걸상 + 짚단 (짐을 내려놓는 자리)
                outsidePut(world, x, z, Material.OAK_LOG);
                outsidePut(world, x + 1, z, Material.HAY_BLOCK);
            }
        }
    }

    /**
     * 돌무더기(케른) — 조약돌 담 블록 4~5덩이 + 한 덩이 더 얹은 돌. 길손이 하나씩 얹고 간 것.
     * 자재는 **COBBLESTONE_WALL 하나뿐**이다: 조약돌·안산암·돌 벽돌·매끈한 돌은 전부 검수의 **길 자재**라
     * (담 밖도 ±65 스캔 안이다) 마당의 돌 한 장이 '길'로 세어진다. 담 블록은 PATH 집합 밖이고,
     * 자연 지면 화이트리스트 밖이라 재조성 시 지면 판정이 그대로 통과해 내려간다 (돌무더기가 자라지 않는다).
     */
    private static void stonePile(World world, int x, int z) {
        int[][] cells = {{0, 0}, {1, 0}, {0, 1}, {1, 1}, {-1, 0}};
        for (int i = 0; i < cells.length; i++) {
            int px = x + cells[i][0], pz = z + cells[i][1];
            int g = outsideGroundY(world, px, pz);
            if (g == Integer.MIN_VALUE || !outsideVacant(world, px, g + 1, pz)) {
                continue;
            }
            world.getBlockAt(px, g + 1, pz).setType(Material.COBBLESTONE_WALL);
            if (i == 0 && outsideVacant(world, px, g + 2, pz)) {
                world.getBlockAt(px, g + 2, pz).setType(Material.COBBLESTONE_WALL);   // 얹은 돌 한 덩이
            }
        }
    }

    /**
     * 담 밖 들녘의 성긴 숲 — 담장 밖 사방 [62, 90] 띠에 좌표 해시로 참나무·자작을 흩뿌린다.
     * 밀도 1/90 ≈ 9~10칸 간격 — 숲이 아니라 **들녘에 선 나무**다 (마을을 가리지 않고 지평을 만든다).
     * 물·절벽에서 심기가 실패하는 몫이 있으므로 검수 ⑩(채움 ≥5%)에 두 배 여유를 두고 잡았다 (평지 실측 9.7%).
     * 노면 회랑과 폐사당 부지는 건너뛰고, 잎이 덮을 자리가 다 비어 있는 칸에만 심는다 (겹친 나무 0).
     * 자작/참나무는 좌표 해시로 갈린다 — 난수가 아니라 좌표의 순수 함수 (재조성 결정론).
     */
    private static void outskirtGrove(World world, int cx, int cz) {
        for (int dx = -90; dx <= 90; dx++) {
            for (int dz = -90; dz <= 90; dz++) {
                int d = Math.max(Math.abs(dx), Math.abs(dz));
                if (d < 62 || d > 90 || onOutsideRoad(dx, dz) || shrineKeepout(dx, dz)) {
                    continue;
                }
                if (hash(cx + dx, cz + dz, 90) != 0) {
                    continue;
                }
                outsideTree(world, cx, cz, dx, dz, hash(cx + dx, cz + dz, 3) == 0);
            }
        }
    }

    /** 관도·산길 노면 회랑 — 수목이 길을 덮지 않는다 (길은 걸을 수 있어야 길이다) */
    private static boolean onOutsideRoad(int dx, int dz) {
        return Math.abs(dz) >= OUT_NEAR - 2 && dx >= -7 && dx <= 3;
    }

    /** 담 밖 소품 한 칸 — 지형 위 빈 칸에만 */
    private static void outsidePut(World world, int x, int z, Material mat) {
        int g = outsideGroundY(world, x, z);
        if (g != Integer.MIN_VALUE && outsideVacant(world, x, g + 1, z)) {
            world.getBlockAt(x, g + 1, z).setType(mat);
        }
    }

    /**
     * 담 밖 빈 칸 — 공기이거나 **길가 풀숲**(먼저 도는 패스가 심은 풀·고사리)이면 소품이 대신 선다.
     * 소품 자재(돌 벽돌·목책·통나무)는 자연 지면 화이트리스트 밖이므로, 재조성 때 이 검사가 다시 걸려
     * 두 번 쌓이지 않는다 (같은 자리에 같은 소품 한 벌 = 결정론).
     */
    private static boolean outsideVacant(World world, int x, int y, int z) {
        Material m = world.getBlockAt(x, y, z).getType();
        return m.isAir() || m == Material.SHORT_GRASS || m == Material.FERN;
    }

    /** 담 밖 나무 한 그루 — 지형 높이에서 자란다 (잎이 덮을 자리가 다 비어야 심는다) */
    private static void outsideTree(World world, int cx, int cz, int dx, int dz, boolean birch) {
        if (shrineKeepout(dx, dz)) {
            return;
        }
        int x = cx + dx, z = cz + dz;
        int g = outsideGroundY(world, x, z);
        if (g == Integer.MIN_VALUE) {
            return;
        }
        Material ground = world.getBlockAt(x, g, z).getType();
        if (ground != Material.GRASS_BLOCK && ground != Material.DIRT && ground != Material.COARSE_DIRT
                && ground != Material.ROOTED_DIRT && ground != Material.PODZOL) {
            return;
        }
        for (int px = x - 1; px <= x + 1; px++) {
            for (int pz = z - 1; pz <= z + 1; pz++) {
                for (int y = g + 1; y <= g + 6; y++) {
                    if (!world.getBlockAt(px, y, pz).getType().isAir()) {
                        return;   // 허공에 뜬 잎·겹친 나무 금지
                    }
                }
            }
        }
        growTree(world, x, g, z, birch);
    }

    // ─── 가로 시설 — 등롱·조경 ───

    // ─── v6.4 ② 등롱 — 밤에도 마을이어야 한다 ───
    //
    // 검수: 길 위 샘플의 47.5% 가 블록 광원 <7 (기준 15% 이하). 원인은 두 겹이었다:
    //   ㉮ 대로 등롱이 간격 7·측거 5 → 랜턴(y=cy+3)에서 대로 한복판(y=cy+1)까지 맨해튼 5+2+3=10 → 광원 5.
    //   ㉯ 대로 말고는 등롱이 아예 없었다 — 광장(19x19 돌바닥)·담 발치 흙길 링(4x119)·골목 2줄·뒷골목·
    //      앞마당·표국 소로가 전부 '길'로 세어지는데(검수 PATH 자재) 전부 캄캄했다.
    // v6.4 의 규칙은 **맨해튼 8 이내**: 랜턴이 cy+3, 길바닥이 cy+1 이므로 수직 2 + 수평 6 이면 광원 7 이상.
    //   대로 = 측거 4(갓길 ±3 바로 밖) · 간격 3 → 최악 4+2+1 = 7 → 광원 8.
    //   담 발치 = ±58 링 간격 6 → 발치 길(±59)까지 1+2+3 = 6 → 광원 9.
    //   골목 = 갓길 밖 z∓18 · z∓22 간격 6 → 골목 반대편 줄까지 3+2+3 = 8 → 광원 7.
    //   광장 = 12주(±4/±8 격자, 우물·매화·화단 자리를 피한다) → 어느 돌바닥도 8 이내.
    // 그리고 v6.1 의 "노면에 서지 않는다"를 자재로 못 박는다: 지면 화이트리스트에서 DIRT_PATH·GRAVEL 을 뺐다
    //   → 등롱은 **다진 흙·풀 위에만** 선다. 대로·골목·소로·뒷골목·디딤돌·광장 돌바닥에는 물리적으로 못 선다.

    /** 등롱이 설 수 있는 지면 — 맨땅뿐 (길 자재·포장은 제외 = 노면 금지 가드) */
    private static final Set<Material> LAMP_GROUND = EnumSet.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT);

    /** 광장 등롱만 서는 지면 — 광장 포장 (여기서만 돌 위 설치를 허용한다) */
    private static final Set<Material> PLAZA_GROUND = EnumSet.of(
            Material.SMOOTH_STONE, Material.ANDESITE, Material.STONE_BRICKS, Material.COBBLESTONE);

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  v7.2 【등롱은 리듬이다】 — 사용자 피드백: "조명이 너무 다닥다닥 붙어 있다.
    //     조명이 없는 부분도 존재해도 되니 적당히 유지해 달라."
    //
    //  v7.1 은 등롱을 **균등 격자**로 뿌렸다: 대로 간격 3 · 담 발치 간격 6 · 골목 간격 6 · 광장 12주.
    //  자로 재면 통과했다 (암흑 3.5%). 그러나 그것은 **안전한 마을**이지 무협의 마을이 아니다 —
    //  밤길이 무서운 것이 무협이고, 등롱이 반가운 것은 그 사이가 어둡기 때문이다.
    //
    //  【새 규칙 — 등롱은 자리에 뜻이 있어야 선다】
    //    ① 밝아야 하는 곳 = **주 동선**. 대로 · 광장 · 문 앞. 여기는 검수도 15% 로 조인다.
    //    ② 어두워도 되는 곳 = 골목 속 · 담 발치 · 뒷골목 · 마당. 여기 등롱은 **길목에만** 선다.
    //    ③ 등롱이 서는 자리의 이름: 광장 어귀 · 골목 어귀 · 각루 발치 · 문 앞 · 소로 어귀 · 우물가.
    //       "몇 칸마다"가 아니라 "무엇 옆에".
    //
    //  【개수】 등롱 기둥 시도 325 → 93 (-71%). 실제 선 등롱은 절반 이하로 떨어진다.
    //    대로 136→56 · 담 발치 76→8 · 골목 64→7 · 뒷골목 16→6 · 광장 12→8 · 소로 13→8 · 교차부 8→0
    //    처마 현수 100→36 (간격 4→11)
    //
    //  【빛의 산수 — 왜 이만큼이면 되는가】 랜턴은 광원 15, 등롱 갓은 cy+3, 길 판정면은 cy+1.
    //    수직 2 + 수평 맨해튼 ≤ 6 이면 광원 ≥ 7 (검수의 '밝음' 문턱).
    //    대로: 등롱 열이 ±4, 노면은 ±3 → 가까운 쪽 열까지 |4-|w|| ≤ 4 → 세로 여유 ≤ 2+|w|.
    //      간격 8 이면 한복판(w=0) 세 칸과 그 옆 두 칸만 어둡다 = 대로 암흑 8.9% (< 15%).
    //      → **대로는 밝고, 그 밝음 사이에 그늘이 있다.**
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * 대로 등롱 자리 — 광장 어귀(11) · 골목 어귀(18) · 그 뒤로 여덟 칸 리듬 · 대문 앞(58).
     *
     * <p>d ∈ [19,21] 은 골목 노면이라 등롱이 물리적으로 못 선다(노면 금지 가드) — 리듬이 그 자리를 비켜 간다.
     * 간격이 7·8·8·8·8·8 인 것은 균등해서가 아니라 <b>양 끝(광장·대문)이 못이고 그 사이를 나눈</b> 결과다.
     */
    private static final int[] ROAD_LAMPS = {11, 18, 26, 34, 42, 50, 58};

    /**
     * 광장 등롱 8주 (구 12주) — {dx, dz}. 우물(±2)·매화(±8,±8)·화단(±5..±6)·대로 축(|d|≤3)을 비켜선다.
     *
     * <p>안쪽 넷(±4,±4)이 **대로 축**을 밝히고, 바깥 넷(±8,±4)이 **광장 네 귀**를 밝힌다
     * (귀퉁이 (9,9) 까지 맨해튼 6 — 딱 닿는다). 구 12주의 (±4,±8) 넷은 안쪽 넷과 중복이라 걷어냈다.
     */
    private static final int[][] PLAZA_LAMPS = {
            {-4, -4}, {4, -4}, {-4, 4}, {4, 4},
            {-8, -4}, {8, -4}, {-8, 4}, {8, 4},
    };

    /**
     * 소로 등롱 — {dx, dz}. 관청·객잔 앞마당은 제 문 현수 등롱 쌍이 이미 덮는다(맨해튼 5 → 광원 10).
     * 등롱이 필요한 건 **문 등롱이 없는 소로**뿐: 민가 9 소로 · 표국 진입 소로 · 관아 진입 소로.
     * v7.2 — 13 → 8. 소로 한복판까지 맨해튼 ≤ 6 만 지키면 그 사이는 성겨도 된다.
     */
    private static final int[][] DOOR_LAMPS = {
            {-47, 17}, {-41, 17},                    // 민가 9 소로(x-46..-42) 어귀 양옆
            {47, 24}, {47, 32}, {38, 34},            // 표국 진입 소로 · 대문 앞 (구 5 → 3)
            {-24, 23}, {-18, 28}, {-24, 32},         // 관아 진입 소로 (구 6 → 3 — 지그재그로 소로를 덮는다)
    };

    /**
     * 골목 등롱 — <b>문 앞에만</b> (구: x 간격 6 의 균등 열 64기).
     *
     * <p>골목은 주 동선이 아니다. 골목의 밤은 어두워도 된다 — 다만 <b>문 앞</b>은 보여야 한다
     * (집에 돌아오는 자가 제 문을 찾는다). 민가 문턱(COTTAGE_DOORSTEPS) 곁 맨땅에 한 기씩,
     * 그것도 <b>전부가 아니라 절반만</b>. 나머지 문은 처마 밑 현수 등롱이 있거나, 아니면 어둡다.
     */
    private static final int[][] ALLEY_LAMPS = {
            {-35, -22}, {-8, -22}, {16, -22}, {36, -22},   // 북골목 — 문 넷 곁 (집 쪽 줄)
            {-40, 22}, {11, 22}, {35, 22},                 // 남골목 — 문 셋 곁
    };

    /** 가로 등롱 — 길목·문 앞·각루 발치·우물가. 건물·노점을 다 세운 뒤 빈 자리에만 선다. */
    private static void streetLanterns(World world, int cx, int cy, int cz) {
        for (int d : ROAD_LAMPS) {                   // 대로 4갈래 양측 — 측거 4, 리듬 7~8
            for (int side = -4; side <= 4; side += 8) {
                lanternPost(world, cx + side, cy, cz - d);   // 남북대로
                lanternPost(world, cx + side, cy, cz + d);
                lanternPost(world, cx - d, cy, cz + side);   // 동서대로
                lanternPost(world, cx + d, cy, cz + side);
            }
        }
        for (int i : new int[]{-54, 54}) {           // 담 발치 — **각루 발치에만** (담 밑은 어둡다)
            lanternPost(world, cx + i, cy, cz - 58);
            lanternPost(world, cx + i, cy, cz + 58);
            lanternPost(world, cx - 58, cy, cz + i);
            lanternPost(world, cx + 58, cy, cz + i);
        }
        for (int[] p : ALLEY_LAMPS) {                // 골목 — 문 앞에만
            lanternPost(world, cx + p[0], cy, cz + p[1]);
        }
        for (int[] l : BACK_LANES) {                 // 뒷골목 — **어귀에만** 한 기씩 (골목 속은 어둡다)
            lanternPost(world, cx + l[0] - 1, cy, cz + l[2]);
            lanternPost(world, cx + l[1] + 1, cy, cz + l[3]);
        }
        for (int[] p : DOOR_LAMPS) {                 // 앞마당·소로 어귀
            lanternPost(world, cx + p[0], cy, cz + p[1]);
        }
        for (int[] p : PLAZA_LAMPS) {                // 광장 8주
            plazaLantern(world, cx + p[0], cy, cz + p[1]);
        }
        for (int x = cx - 44; x <= cx + 44; x += 11) {  // 골목 처마 밑 현수 등롱 (간격 4 → 11)
            for (int dz : new int[]{-21, -19, 19, 21}) {
                eaveLantern(world, x, cy + 4, cz + dz);
            }
        }
    }

    /**
     * 처마 밑 현수 등롱 — 골목 갓길에 **땅이 없는 구간**의 답. 의방·전장의 남벽(z+18)은 남골목(z+19~21)에
     * 딱 붙어 있어 등롱 기둥을 세울 맨땅이 한 칸도 없다. 그 대신 처마(y+5) 밑 y+4 에 등롱을 매단다:
     *   노면(cy)도 통행 높이(cy+1~2)도 건드리지 않으므로 길 폭·길 판정에 영향이 0이고,
     *   위에 지붕이 있는 칸에만 걸리므로(받침 검사) 허공에 뜬 등이 생기지 않는다.
     * 향촌 골목의 실제 조명 방식이기도 하다 — 등은 처마에서 내려온다.
     */
    private static void eaveLantern(World world, int x, int y, int z) {
        if (!world.getBlockAt(x, y, z).getType().isAir()
                || world.getBlockAt(x, y + 1, z).getType().isAir()) {
            return;   // 자리가 찼거나, 매달 처마가 없다
        }
        // v7.0 — **처마에만 매단다**. 골목 등롱 열(z∓19)은 x[-48..+48] 을 훑는데, 건물이 커지며
        // 그 선이 객잔 실내(북벽 안줄)를 관통했다. 실내에도 위층 바닥이 있어 "처마"로 읽혔고,
        // 등롱 넷이 객잔 북벽에 얹혀 소품 과밀 7 을 냈다 (검수 ④).
        //   가르는 것은 자재다 — 처마는 슬래브·계단, 2층 바닥은 판재다. 판재 밑에는 등을 매달지 않는다.
        String above = world.getBlockAt(x, y + 1, z).getType().name();
        if (!above.endsWith("_SLAB") && !above.endsWith("_STAIRS")) {
            return;   // 처마가 아니라 실내 천장(=위층 바닥) — 골목의 등이 설 자리가 아니다
        }
        hangingLantern(world, x, y, z);
    }

    /**
     * 등롱 기둥 — 지면이 **맨땅**(잔디·흙·다진 흙·뿌리 흙)이고 위 3칸이 비었을 때만 선다.
     * 길 자재(DIRT_PATH·GRAVEL)·포장(조약돌·안산암)은 화이트리스트 밖 = 등롱은 노면에 서지 못한다.
     * 등롱 열(±4)·담 발치(±58)·골목 갓길(z∓18·∓22)은 전부 reserved() 이므로 울타리·텃밭·잡초가 뺏지 못한다.
     */
    private static void lanternPost(World world, int x, int cy, int z) {
        int g = gy(x, z);   // v7.2 — 등롱도 제 자리 땅 위에 선다 (마당은 굽이친다)
        if (!LAMP_GROUND.contains(world.getBlockAt(x, g, z).getType())) {
            return;
        }
        for (int y = g + 1; y <= g + 3; y++) {
            if (!world.getBlockAt(x, y, z).getType().isAir()) {
                return;   // 건물·처마·담·노점·소품 — 등롱이 밀어내지 않는다
            }
        }
        world.getBlockAt(x, g + 1, z).setType(Material.SPRUCE_FENCE);
        world.getBlockAt(x, g + 2, z).setType(Material.SPRUCE_FENCE);
        world.getBlockAt(x, g + 3, z).setType(Material.LANTERN);
        lampApron(world, x, g, z);   // v7.1 — 등롱 발치는 자연의 흙 (검수 ③의 이음매를 만들지 않는다)
    }

    /** 광장 등롱 — 돌바닥 위에만(광장 포장 화이트리스트). 광장은 노면이 아니라 '마당'이다 */
    private static void plazaLantern(World world, int x, int cy, int z) {
        if (!PLAZA_GROUND.contains(world.getBlockAt(x, cy, z).getType())) {
            return;
        }
        for (int y = cy + 1; y <= cy + 3; y++) {
            if (!world.getBlockAt(x, y, z).getType().isAir()) {
                return;
            }
        }
        world.getBlockAt(x, cy + 1, z).setType(Material.SPRUCE_FENCE);
        world.getBlockAt(x, cy + 2, z).setType(Material.SPRUCE_FENCE);
        world.getBlockAt(x, cy + 3, z).setType(Material.LANTERN);
    }

    /** 광장 네 귀의 매화나무(벚잎 = 매화 대역) + 화단 — 화산파 매화 문양의 복선 */
    private static void plazaTreesAndFlowerBeds(World world, int cx, int cy, int cz) {
        Material[] flowers = {Material.POPPY, Material.WHITE_TULIP,
                Material.OXEYE_DAISY, Material.AZURE_BLUET};
        int f = 0;
        for (int dx = -8; dx <= 8; dx += 16) {   // 광장(±7) 네 귀 대각 — 건물 처마와 간섭 없음
            for (int dz = -8; dz <= 8; dz += 16) {
                plumTree(world, cx + dx, cy, cz + dz);
            }
        }
        for (int dx = -6; dx <= 6; dx += 12) {
            for (int dz = -6; dz <= 6; dz += 12) {
                for (int x = 0; x <= 1; x++) {
                    for (int z = 0; z <= 1; z++) {
                        int bx = cx + dx - (dx > 0 ? x : -x);
                        int bz = cz + dz - (dz > 0 ? z : -z);
                        world.getBlockAt(bx, cy, bz).setType(Material.GRASS_BLOCK);
                        world.getBlockAt(bx, cy + 1, bz).setType(flowers[f % flowers.length]);
                    }
                }
                f++;
            }
        }
    }

    private static void plumTree(World world, int x, int cy, int z) {
        for (int dx = -1; dx <= 1; dx++) {   // v6.1 ① — 돌바닥 광장에 낸 나무 구덩이 (조약돌 테 + 흙)
            for (int dz = -1; dz <= 1; dz++) {
                world.getBlockAt(x + dx, cy, z + dz).setType(
                        dx == 0 && dz == 0 ? Material.DIRT : Material.COBBLESTONE);
            }
        }
        for (int y = cy + 1; y <= cy + 4; y++) {
            world.getBlockAt(x, y, z).setType(Material.CHERRY_LOG);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    leaf(world, x + dx, cy + 4, z + dz);
                }
                leaf(world, x + dx, cy + 5, z + dz);
            }
        }
        leaf(world, x, cy + 6, z);
    }

    private static void leaf(World world, int x, int y, int z) {
        Leaves data = (Leaves) Material.CHERRY_LEAVES.createBlockData();
        data.setPersistent(true);
        world.getBlockAt(x, y, z).setBlockData(data);
    }

    // ─── 건물 — 목골 백벽 흑와 (관청) + 유형별 팔레트 (민가) ───

    /**
     * 관청급 13x11 — 목골(모서리 흑목 기둥 + 상인방) · 백벽(격자창 유리) · 흑와(계단식 팔작지붕).
     * doorNorth=true 면 북향 입구(광장 남쪽 건물), false 면 남향 입구.
     */
    private static Location house(World world, int x0, int y0, int z0, int w, int d, int wallH,
                                  boolean doorNorth, String name, String subtitle, WindowStyle win) {
        shell(world, x0, y0, z0, w, d, wallH, doorNorth,
                WallStyle.PLASTER_WHITE, RoofStyle.TILE, true, win);   // 주요 건물 = 수묵 3색 + 팔작(격식)
        int x1 = x0 + w - 1;
        int z1 = z0 + d - 1;
        int doorX = x0 + w / 2;
        int doorZ = doorNorth ? z0 : z1;
        int out = doorNorth ? -1 : 1;
        placeSign(world, doorX + 2, y0 + 1, doorZ + out, doorNorth ? BlockFace.NORTH : BlockFace.SOUTH, name, subtitle);   // 3칸 소로(doorX±1) 밖에서 읽는다
        hangingSign(world, doorX, y0 + wallH, doorZ + out * 2,                               // v6.1 — 2칸 처마 끝단 밑 현판
                doorNorth ? BlockFace.NORTH : BlockFace.SOUTH, name, subtitle);
        chainLantern(world, doorX - 2, y0 + wallH, doorZ + out * 2, 1);                      // 문 양옆 현수 등롱 쌍
        chainLantern(world, doorX + 2, y0 + wallH, doorZ + out * 2, 1);
        for (int px : new int[]{doorX - 3, doorX + 3}) {   // v6.1 ② — 정면 활주 2주 (깊은 처마의 그늘에 구조를)
            for (int y = y0 + 1; y <= y0 + wallH - 1; y++) {
                world.getBlockAt(px, y, doorZ + out * 2).setType(Material.SPRUCE_FENCE);
            }
        }
        tieBeams(world, x0, y0 + wallH, z0, x1, z1);   // v6.1 ③ — 대들보 2 (상단 조명을 매다는 뼈대)
        // v6.8 ① — 대들보 현수등 2점. 벽등 링만으로는 11x9 실내 한복판이 광원 7 로 떨어진다
        // (평면거리 7 + 높이 2 = 9 → 15-9 = 6). 방 한가운데 열(x 중앙)에 걸므로 벽면 소품에 계상되지 않는다.
        hangingLantern(world, doorX, y0 + wallH - 1, z0 + 3);
        hangingLantern(world, doorX, y0 + wallH - 1, z1 - 3);
        return loc(world, doorX, y0 + 1, z0 + d / 2);   // 앵커 = 실내 중앙
    }

    /**
     * 대들보 — 상인방 높이로 실내를 가로지르는 흑목 보 2줄 (z0+3 · z1-3).
     * v6.1 ③ 벽면 3분할의 상단(조명·현수)은 매달 데가 있어야 성립한다: 이 보가 그 뼈대다.
     */
    private static void tieBeams(World world, int x0, int y, int z0, int x1, int z1) {
        for (int z : new int[]{z0 + 3, z1 - 3}) {
            for (int x = x0 + 1; x <= x1 - 1; x++) {
                world.getBlockAt(x, y, z).setType(Material.DARK_OAK_LOG);
            }
        }
    }

    /**
     * 건물 골조 공통 — 마루·벽(기둥·벽조직·격자창·상인방)·지붕·문 1칸. 간판·앵커 없음.
     * ws = 벽 자재 체계(WallStyle), rs = 지붕 자재 체계(RoofStyle) — v5 A안 자재 팔레트 분리.
     * paljak = true 면 팔작(상가·관아급), false 면 v5 계단 링 지붕(민가 — 지붕 격식은 위계다).
     * 관청급(w>=9)은 마루 가장자리 1칸을 흑목 귀틀로 둘러 바닥에 변화를 준다 (인테리어 규정).
     */
    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  v6.9 ③ 창호(窓戶) 문법 — "창은 방이 정하고, 방은 쓰임이 정한다"
    //
    //  【전(前) 규칙】  sash = (y == y0+2 || y == y0+wallH-1)  →  if (sash && (x+z) % 2 == 0) GLASS_PANE
    //    한 칸짜리 유리를 벽면 전체에 **바둑판으로 흩뿌리는** 좌표식이었다. 결과:
    //      · 모든 벽(정면·측면·뒷벽)이 똑같은 격자 — 벽면의 위계가 0
    //      · 모든 건물이 똑같은 격자 — 건물의 개성이 0 (v6.8 이 부속으로 겨우 세운 개성을 창이 지웠다)
    //      · 창이 '개구부'가 아니라 '무늬'였다 — 크기도 틀도 없으니 눈이 창으로 읽지 못한다
    //    사용자 지적: "창문 유리가 너무 일관적·반복적이라 외관을 해침". 자로 재면 통과하는데 눈이 불편한
    //    v6.7 ①·v6.5 ② 와 같은 부류다 — 규칙식은 반드시 패턴을 만든다.
    //
    //  【후(後) 규칙】  창은 **위치와 크기를 가진 개구부**다. 벽은 먼저 통짜로 서고(격자 0),
    //    그 뒤 fenestrate() 가 면마다 **창 짝(unit)**을 뚫는다:
    //      ① 단위      — 3x2 / 2x2 / 1x2 덩어리. 그 사이는 **넉넉한 벽면**으로 남는다
    //      ② 틀        — 창 좌우에 흑목 설주, 위에 흑목 인방. 틀이 있어야 창이 창으로 읽힌다
    //      ③ 면의 위계 — 정면(크고 많다) > 측면(적당) > 뒷면(통풍창 하나, 또는 없다)
    //      ④ 쓰임의 표정 — 전장=작고 높은 철창 / 의방=볕이 드는 큰 창 / 의뢰소=정면은 게시대의 몫이라
    //                     창은 측면 / 객잔=1층 넓은 창·2층 작은 객방 창 / 민가=작고 적다
    //      ⑤ 변주      — 민가 9채는 건물 좌표 해시로 창 폭·위치·측면 선택이 갈린다 (난수 0)
    //      ⑥ 실내 정합 — 약장(의방 남벽)·금고 철창(전장 남벽)·게시 목판(의뢰소 북벽) **뒤에는 창이 없다**.
    //                     창 짝의 벽 위 구간이 그 가구 구간을 피해 간다 (좌표로 강제).
    //
    //  【자재 — 창호지의 논리】 팩이 glass 를 **창호지(불투명 미색)**, glass_pane 을 **세살창(살대)** 으로
    //    재텍스처했다. 우리 세계의 창은 비치지 않는다 → 창은 채광 장치가 아니라 **외관의 요소**다
    //    (실내 채광은 v6.8 의 벽등이 이미 풀었다).
    //      SASH   = GLASS_PANE (세살창) — 살대가 있는 '열리는 창'. 창 짝의 기본.
    //      PAPER  = GLASS      (전면 창호지) — 살 없는 미색 면. 교창·봉창(작고 높은 고정창) 전용.
    //      BARRED = IRON_BARS  (철창) — 전장의 창.
    //
    //  【검수 정합 — 벽 레이캐스트 켜는 절대 뚫지 않는다】 ★ 이것이 이 문법의 유일한 하드 제약이다.
    //    TownAudit.measureBuilding 은 앵커에서 4방향으로 **probeY = 바닥 + 3** 켜를 쏘아 벽 최외곽을
    //    잡는다(wallAt). 그 좌표로 처마 내밀기(②)·물매(③)·능선(③-b)·단 평탄도(③-c)·이격/처마 겹침(⑤)·
    //    소품·바닥 여백(④)이 전부 파생된다 — **벽 박스가 흔들리면 검수 여섯 항목이 같이 흔들린다.**
    //    blocking() = isOccluding() ‖ GLASS_PANE ‖ IRON_BARS. 즉:
    //      · GLASS_PANE·IRON_BARS 는 벽으로 세어진다  → probeY 에 놓아도 벽 박스 불변 ✅
    //      · GLASS 는 isOccluding()=false 이고 blocking() 목록에도 **없다** → probeY 에 놓으면 그 방향
    //        레이캐스트가 벽을 못 찾고 null → measureBuilding 이 건물을 통째로 놓친다 ❌
    //      · AIR(뚫린 개구) 도 마찬가지 ❌
    //    그러므로 **probeY 켜의 창은 SASH/BARRED 뿐**이고, PAPER 는 probeY 를 절대 포함하지 않는다.
    //    설계로 지키고(교창=바닥+4 · 봉창=바닥+2), winMat() 이 한 번 더 막는다 (실수해도 위반이 안 난다).
    //    벽 박스가 불변이므로 v6.8 의 검수 12종 수치는 창호 교체 후에도 **한 톨도 안 움직인다**.
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /** 창호 자재 — 팩 재텍스처 기준 (glass = 창호지 / glass_pane = 세살창 / iron_bars = 철창) */
    private enum Pane {
        SASH,     // 세살창 — 창 짝의 기본. blocking() 에 든다 = probeY 에 놓아도 안전
        PAPER,    // 전면 창호지 — 교창·봉창 전용. blocking() 밖 = probeY 에 놓으면 안 된다
        BARRED    // 철창 — 전장. blocking() 에 든다
    }

    /** 창호 문법 — 건물의 쓰임이 창을 정한다 */
    private enum WindowStyle {
        CLINIC,    // 의방 — 볕이 든다: 정면 큰 창 두 짝 + 교창. 남벽(약장 뒤)엔 창이 없다
        OFFICE,    // 의뢰소 — 정면은 게시대의 몫: 정면 작게, 측면에 큰 창
        VAULT,     // 전장 — 작고 높고 철창. 뒷벽 창 0 (돈이 있는 집의 뒷벽은 막는다)
        COTTAGE,   // 민가 — 작고 적다. 건물 해시로 폭·측면이 갈린다
        LOFT,      // 다락형 민가 — 창이 하나 더 높다
        COURT,     // v7.0 ① 관아 정청 — 창 켜를 한 칸 올린다(y0+3..y0+4). 밑 두 켜(y0+1·y0+2)는 굽도리 돌의 자리
        NONE       // 부속채(날개·작업간) — 창 없음 (헛간에 창을 내지 않는다)
    }

    /** 창을 뚫어도 되는 벽면인가 — 기둥(원목)·개구(공기)·이미 뚫린 창은 건드리지 않는다 */
    private static boolean plainWall(Material m) {
        return m == Material.WHITE_TERRACOTTA || m == Material.LIGHT_GRAY_TERRACOTTA
                || m == Material.BRICKS || m == Material.MUD_BRICKS
                || m == Material.SPRUCE_LOG || m == Material.DARK_OAK_PLANKS;
    }

    /**
     * 창호 한 칸의 자재. ★ probeY 켜(= 검수의 벽 레이캐스트 켜)에는 **blocking 자재만** 나간다 —
     * PAPER(GLASS) 가 그 켜에 놓이면 벽 박스가 무너져 검수 여섯 항목이 동시에 어긋난다.
     * 설계상 그런 일이 없도록 짜 두었지만, 자재 선택에서 한 번 더 막는다 (실수 = 위반이 되지 않게).
     */
    private static Material winMat(Pane kind, int y, int probeY) {
        if (kind == Pane.BARRED) {
            return Material.IRON_BARS;
        }
        if (kind == Pane.PAPER && y != probeY) {
            return Material.GLASS;      // 창호지 — 살 없는 미색 면
        }
        return Material.GLASS_PANE;     // 세살창 (PAPER 라도 probeY 면 여기로 접힌다 = 벽 박스 보존)
    }

    /**
     * 창 짝 하나 — 벽면 [a0..a1] x [yb..yt] 를 창호로 갈고 좌우 설주·위 인방을 흑목으로 두른다.
     * face: 0=북(z0) · 1=남(z1) · 2=서(x0) · 3=동(x1). a 는 그 면을 따라 흐르는 좌표(x 또는 z).
     * 모서리 기둥과 문 개구는 침범하지 않는다 (a 범위를 벽 안쪽으로 조여서 부른다).
     */
    private static void windowUnit(World world, int x0, int y0, int z0, int x1, int z1,
                                   int face, int a0, int a1, int yb, int yt, Pane kind) {
        int probeY = y0 + 3;
        int lo = (face <= 1) ? x0 + 1 : z0 + 1;    // 모서리 기둥 안쪽
        int hi = (face <= 1) ? x1 - 1 : z1 - 1;
        if (a0 < lo || a1 > hi || a0 > a1) {
            return;   // 벽이 짧아 이 창 짝이 안 들어간다 (작은 부속채 — 창을 접는다)
        }
        for (int a = a0 - 1; a <= a1 + 1; a++) {   // 설주 + 창 + (틀 밖은 건드리지 않는다)
            for (int y = yb; y <= yt + 1; y++) {
                boolean frame = a < a0 || a > a1 || y > yt;   // 좌우 설주 · 위 인방
                Block b = blockOnFace(world, x0, z0, x1, z1, face, a, y);
                if (frame) {
                    if (plainWall(b.getType())) {
                        b.setType(Material.DARK_OAK_PLANKS);   // 틀 — 창이 창으로 읽히는 이유
                    }
                } else if (plainWall(b.getType()) || b.getType() == Material.GLASS_PANE) {
                    b.setType(winMat(kind, y, probeY));
                }
            }
        }
    }

    /** 그 면의 (a, y) 칸 */
    private static Block blockOnFace(World world, int x0, int z0, int x1, int z1,
                                     int face, int a, int y) {
        return switch (face) {
            case 0 -> world.getBlockAt(a, y, z0);
            case 1 -> world.getBlockAt(a, y, z1);
            case 2 -> world.getBlockAt(x0, y, a);
            default -> world.getBlockAt(x1, y, a);
        };
    }

    /**
     * 창호 배치 — 면의 위계(정면 > 측면 > 뒷면)와 쓰임의 표정을 좌표로 쓴다.
     * 관청급은 벽고 5 (벽 y0+1..y0+5, 인방 y0+5) → 창 켜 y0+2..y0+3(세살창) · 교창 y0+4(창호지).
     * 민가는 벽고 4 (인방 y0+4) → 창 켜 y0+2..y0+3(세살창) · 봉창 y0+2(창호지, 뒷벽 1칸).
     * probeY = y0+3 이 창 켜에 들어가므로 그 켜는 **반드시 세살창/철창** (winMat 이 강제).
     */
    private static void fenestrate(World world, int x0, int y0, int z0, int x1, int z1,
                                   int wallH, boolean doorNorth, WindowStyle st) {
        if (st == WindowStyle.NONE) {
            return;
        }
        int front = doorNorth ? 0 : 1;    // 문이 있는 면
        int back = doorNorth ? 1 : 0;
        int w = x1 - x0 + 1;
        int doorX = x0 + w / 2;
        int yb = y0 + 2, yt = y0 + 3;     // 창 켜 (2단) — probeY(y0+3) 포함 = 세살창/철창만
        int transom = y0 + wallH - 1;     // 교창 켜 — 벽고 5 면 y0+4 (probeY 아님 = 창호지 가능)
        int v = hash(x0, z0, 4);          // 건물 단위 변주 (좌표 해시 — 난수 0)

        switch (st) {
            // ── 의방: 볕이 드는 집. 정면(광장 쪽) 큰 창 두 짝 + 교창. 남벽은 약장의 벽이다 → 창 0.
            case CLINIC -> {
                windowUnit(world, x0, y0, z0, x1, z1, front, x0 + 2, x0 + 4, yb, yt, Pane.SASH);
                windowUnit(world, x0, y0, z0, x1, z1, front, x1 - 4, x1 - 2, yb, yt, Pane.SASH);
                windowUnit(world, x0, y0, z0, x1, z1, front, x0 + 2, x0 + 4, transom, transom, Pane.PAPER);
                windowUnit(world, x0, y0, z0, x1, z1, front, x1 - 4, x1 - 2, transom, transom, Pane.PAPER);
                windowUnit(world, x0, y0, z0, x1, z1, 3, z0 + 3, z0 + 5, yb, yt, Pane.SASH);   // 동측 — 아침볕
                windowUnit(world, x0, y0, z0, x1, z1, 2, z0 + 2, z0 + 3, yb, yt, Pane.SASH);   // 서측 — 약장 여벌(z0+6,7) 앞을 피한다
                // 뒷면(남벽) = 약장 3칸(x0+5..x0+7)의 벽. 창을 내지 않는다 — 약재는 볕을 싫어한다.
            }
            // ── 의뢰소: 정면은 옥외 게시대의 몫(v6.8 ②). 창은 측면으로 물러나고 정면엔 좁은 세로창 둘.
            case OFFICE -> {
                windowUnit(world, x0, y0, z0, x1, z1, front, x0 + 2, x0 + 2, yb, yt, Pane.SASH);
                windowUnit(world, x0, y0, z0, x1, z1, front, x1 - 2, x1 - 2, yb, yt, Pane.SASH);
                windowUnit(world, x0, y0, z0, x1, z1, 2, z0 + 6, z0 + 8, yb, yt, Pane.SASH);   // 서측 큰 창
                windowUnit(world, x0, y0, z0, x1, z1, 3, z0 + 3, z0 + 5, yb, yt, Pane.SASH);   // 동측 큰 창
                windowUnit(world, x0, y0, z0, x1, z1, 2, z0 + 6, z0 + 8, transom, transom, Pane.PAPER);
                windowUnit(world, x0, y0, z0, x1, z1, 3, z0 + 3, z0 + 5, transom, transom, Pane.PAPER);
                // 뒷면(북벽) = 게시 목판(x0+4..x0+8)의 벽 → 그 밖 양끝에 봉창 하나씩만
                windowUnit(world, x0, y0, z0, x1, z1, back, x0 + 2, x0 + 2, transom, transom, Pane.PAPER);
                windowUnit(world, x0, y0, z0, x1, z1, back, x1 - 2, x1 - 2, transom, transom, Pane.PAPER);
            }
            // ── 전장: 창이 작고 높고 철창이 박혀 있다. 뒷벽엔 창이 없다 (돈이 있는 집의 뒷벽은 막는다).
            case VAULT -> {
                windowUnit(world, x0, y0, z0, x1, z1, front, x0 + 3, x0 + 3, yt, transom, Pane.BARRED);
                windowUnit(world, x0, y0, z0, x1, z1, front, x1 - 3, x1 - 3, yt, transom, Pane.BARRED);
                windowUnit(world, x0, y0, z0, x1, z1, 2, z0 + 4, z0 + 4, yt, transom, Pane.BARRED);
                windowUnit(world, x0, y0, z0, x1, z1, 3, z0 + 3, z0 + 3, yt, transom, Pane.BARRED);   // 동벽 전표철(z0+6) 앞을 피한다
                // 뒷면(남벽) = 철창 금고(x0+5..x0+7). 창 0.
            }
            // ── 민가: 작고 적다. 유리는 비싸다 — 정면 한 짝, 측면 한 짝(어느 쪽인지는 집마다 다르다), 뒷벽 봉창.
            case COTTAGE, LOFT -> {
                int fw = 1 + v % 2;                                    // 정면 창 폭 2 또는 3 (집마다 다르다)
                int fa = doorX + (v < 2 ? -(3 + fw) : 3);              // 문 왼쪽이냐 오른쪽이냐도 갈린다
                fa = Math.max(x0 + 1, Math.min(fa, x1 - 1 - fw));      // 좁은 벽(다락형 9칸)에서도 창이 산다
                windowUnit(world, x0, y0, z0, x1, z1, front, fa, fa + fw, yb, yt, Pane.SASH);
                int side = (v % 2 == 0) ? 2 : 3;                       // 측면은 한쪽만 (양쪽에 다 내는 살림집은 없다)
                int sa = Math.min(z0 + 2 + (v % 3), z1 - 2);
                windowUnit(world, x0, y0, z0, x1, z1, side, sa, sa + 1, yb, yt, Pane.SASH);
                windowUnit(world, x0, y0, z0, x1, z1, back, doorX - 1 + (v % 2), doorX - 1 + (v % 2),
                        y0 + 2, y0 + 2, Pane.PAPER);                   // 봉창 — 뒷벽엔 이것 하나 (probeY 아님)
                if (st == WindowStyle.LOFT) {                          // 다락 — 반대쪽 측면에 봉창 하나 더
                    int other = side == 2 ? 3 : 2;
                    windowUnit(world, x0, y0, z0, x1, z1, other, z1 - 3, z1 - 3,
                            y0 + 2, y0 + 2, Pane.PAPER);
                }
            }
            // ── v7.0 ① 관아 정청: 관은 무림과 다르다 — 창도 위엄이다.
            //    창 켜를 y0+3..y0+4 로 **한 칸 올려** 벽 하부 두 켜를 굽도리 돌(stone_bricks 2단)에 내준다.
            //    probeY = y0+3 이 창 켜에 드므로 그 켜는 세살창(blocking) — 벽 박스 불변 (winMat 이 강제).
            //    정면(문 쪽) 큰 창 두 짝 · 측면 각 한 짝 · 뒷면(현령의 등 뒤 = 정청 병풍벽)엔 창 0.
            case COURT -> {
                int cyb = y0 + 3, cyt = y0 + 4;
                windowUnit(world, x0, y0, z0, x1, z1, front, x0 + 2, x0 + 4, cyb, cyt, Pane.SASH);
                windowUnit(world, x0, y0, z0, x1, z1, front, x1 - 4, x1 - 2, cyb, cyt, Pane.SASH);
                windowUnit(world, x0, y0, z0, x1, z1, 2, z0 + 4, z0 + 6, cyb, cyt, Pane.SASH);
                windowUnit(world, x0, y0, z0, x1, z1, 3, z0 + 4, z0 + 6, cyb, cyt, Pane.SASH);
            }
            default -> { }
        }
    }

    /**
     * v7.0 ② 굽도리(基石) — 부촌 필지의 표식. 벽 하부 두 켜를 돌 벽돌로 갈아 벽이 땅에서 '올라선' 것처럼 보이게.
     * 백벽만 갈아친다 — 기둥(원목)·창(유리/철창)·문(공기)·상인방(판재)은 건드리지 않는다.
     *
     * <p>검수 정합: STONE_BRICKS 는 PATH 집합이지만 검수의 길 판정은 **지면(cy) 한 켜만** 읽는다
     * (pathGrid). 굽도리는 cy+1·cy+2 이므로 길 폭·야간 암흑 표본에 한 칸도 보태지 않는다.
     * isOccluding = true → 벽 레이캐스트(probeY = cy+3)와도 무관하고 벽 박스도 그대로다.
     */
    private static void kerb(World world, int x0, int y0, int z0, int x1, int z1, int courses) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (x != x0 && x != x1 && z != z0 && z != z1) {
                    continue;
                }
                for (int y = y0 + 1; y <= y0 + courses; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getType() == Material.WHITE_TERRACOTTA
                            || b.getType() == Material.LIGHT_GRAY_TERRACOTTA) {
                        b.setType(Material.STONE_BRICKS);
                    }
                }
            }
        }
    }

    /**
     * v7.0 ② 전돌 바닥 — 부촌 필지의 실내. **반드시 POLISHED_ANDESITE** 다:
     * stone_bricks · smooth_stone · andesite · cobblestone 은 전부 TownAudit.PATH 라 실내에 깔면
     * 그 칸이 '길'로 세어져 길 폭 히스토그램과 야간 광원 표본을 오염시킨다 (village_tiers.md 3.5).
     * 가장자리 한 줄은 흑목 귀틀을 남겨 마루의 결을 잇는다.
     */
    private static void tiledFloor(World world, int x0, int y, int z0, int x1, int z1) {
        for (int x = x0 + 1; x <= x1 - 1; x++) {
            for (int z = z0 + 1; z <= z1 - 1; z++) {
                Material now = world.getBlockAt(x, y, z).getType();
                if (now != Material.SPRUCE_PLANKS) {
                    continue;   // 귀틀(흑목)은 남긴다
                }
                world.getBlockAt(x, y, z).setType(Material.POLISHED_ANDESITE);
            }
        }
    }

    /**
     * v7.0 ② 석등(石燈) — 부촌 필지의 조명. 돌 벽돌 담 2단 + 등롱 + 반 블록 갓.
     * 검수: STONE_BRICK_WALL·STONE_BRICK_SLAB 은 PATH 집합 밖이고(집합에 든 것은 STONE_BRICKS 풀 블록뿐),
     * 지면(cy)에는 한 칸도 놓지 않는다 → 길 판정 불변. LANTERN 은 PROP 이지만 전부 **벽 사각형 밖**이라
     * propScan 이 세지 않는다. 광원 15 (cy+3) → 평면 6 까지 길 위 광원 ≥7.
     */
    private static void stoneLamp(World world, int x, int cy, int z) {
        for (int y = cy + 1; y <= cy + 2; y++) {
            if (!world.getBlockAt(x, y, z).getType().isAir()) {
                return;
            }
            world.getBlockAt(x, y, z).setType(Material.STONE_BRICK_WALL);
        }
        world.getBlockAt(x, cy + 3, z).setType(Material.LANTERN);
        topSlab(world, x, cy + 4, z, Material.STONE_BRICK_SLAB);
    }

    /**
     * v7.0 ② 초가 점치환 — **빈촌 필지의 지붕**.
     *
     * <p>함정(village_tiers.md 6.1): TownAudit.ROOF 에 HAY_BLOCK 은 **없다**. 짚으로 지붕을 통째로 덮으면
     * 검수가 "지붕 자재를 찾지 못했다(지붕 없음)"로 잡고 처마·물매·능선·단 길이 네 항목이 통째로 측정 불능이 된다.
     * 그러므로 초가는 "짚으로 덮은 지붕"이 아니라 **"너와 지붕에 짚을 얹은 것"** 으로 짓는다:
     *   ㉮ 처마 최외곽 링 = DARK_OAK_SLAB (eaveRim 이 이미 반 블록으로 깎았다) — 손대지 않는다
     *   ㉯ 용마루·적새(capping) = DARK_OAK_PLANKS (solidMat) — 손대지 않는다
     *   ㉰ 그 사이 **경사면의 계단(DARK_OAK_STAIRS)만** 좌표 해시 25% 로 HAY_BLOCK 점치환
     * 검수가 자를 대는 두 면(처마단·용마루)이 전부 ROOF 자재로 남으므로 지붕 인식은 온전하다.
     * 덤: 짚 칸은 roofTopAt 이 건너뛰므로 단 길이 평균이 오히려 내려간다 (검수 ③-c 완화).
     * HAY_BLOCK 은 PROP 집합이지만 민가 지붕은 어느 앵커의 벽 사각형에도 들지 않아 propScan 밖이다.
     */
    private static void thatch(World world, int x0, int z0, int x1, int z1, int yBase) {
        for (int x = x0 + 1; x <= x1 - 1; x++) {
            for (int z = z0 + 1; z <= z1 - 1; z++) {
                if (hash(x, z, 4) != 0) {
                    continue;   // 25% — 좌표 해시 (난수 0)
                }
                for (int y = yBase; y <= yBase + 12; y++) {
                    if (world.getBlockAt(x, y, z).getType() == Material.DARK_OAK_STAIRS) {
                        world.getBlockAt(x, y, z).setType(Material.HAY_BLOCK);
                    }
                }
            }
        }
    }

    /** v7.0 ② 기운 울타리 — 빈촌 필지의 담. 한 칸 걸러 서고, 세 칸에 하나는 아예 넘어져 있다(빈칸) */
    private static void leaningFence(World world, int cx, int cy, int cz, int x0, int x1, int z0, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (x != x0 && x != x1 && z != z0 && z != z1) {
                    continue;
                }
                if (hash(x, z, 3) == 0) {
                    continue;   // 넘어진 칸 — 가난은 정돈되지 않는다
                }
                putProp(world, cx, cy, cz, x, z, Material.OAK_FENCE);
            }
        }
    }

    private static void shell(World world, int x0, int y0, int z0, int w, int d, int wallH,
                              boolean doorNorth, WallStyle ws, RoofStyle rs, boolean paljak) {
        shell(world, x0, y0, z0, w, d, wallH, doorNorth, ws, rs, paljak, 2, WindowStyle.COTTAGE);
    }

    private static void shell(World world, int x0, int y0, int z0, int w, int d, int wallH,
                              boolean doorNorth, WallStyle ws, RoofStyle rs, boolean paljak,
                              WindowStyle win) {
        shell(world, x0, y0, z0, w, d, wallH, doorNorth, ws, rs, paljak, 2, win);
    }

    private static void shell(World world, int x0, int y0, int z0, int w, int d, int wallH,
                              boolean doorNorth, WallStyle ws, RoofStyle rs, boolean paljak,
                              int eave, WindowStyle win) {
        int x1 = x0 + w - 1;
        int z1 = z0 + d - 1;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
                boolean rim = x == x0 + 1 || x == x1 - 1 || z == z0 + 1 || z == z1 - 1;
                world.getBlockAt(x, y0, z).setType(
                        !wall && rim && w >= 9 ? Material.DARK_OAK_PLANKS : Material.SPRUCE_PLANKS);   // 마루(+귀틀)
                for (int y = y0 + 1; y <= y0 + wallH; y++) {
                    if (corner) {
                        world.getBlockAt(x, y, z).setType(Material.DARK_OAK_LOG);        // 기둥
                    } else if (!wall) {
                        world.getBlockAt(x, y, z).setType(Material.AIR);
                    } else if (y == y0 + wallH) {
                        world.getBlockAt(x, y, z).setType(Material.DARK_OAK_PLANKS);     // 상인방(도리)
                    } else {
                        // v6.9 ③ — 벽은 먼저 **통짜로** 선다. 창은 벽면에 흩뿌리는 무늬가 아니라
                        // 뒤에 오는 fenestrate() 가 뚫는 **개구부**다 (구 (x+z)%2 격자 폐기).
                        boolean stud = ws == WallStyle.FRAME_GRAY && (x + z) % 3 == 0;   // 목골 노출 스터드
                        if (stud) {
                            world.getBlockAt(x, y, z).setType(Material.DARK_OAK_PLANKS);
                        } else {
                            wallBlock(world, x, y, z, ws, z == z0 || z == z1);           // 벽 조직
                        }
                    }
                }
            }
        }
        // v6.3 ①③ — 처마는 전 방향 eave 칸(지붕 최외곽 = 벽+eave, 최대 2). 팔작 = 관아·상가, 맞배 = 민가.
        // 합각·박공은 더 이상 백벽/판재 덩어리가 아니다: 흑목 판자 테두리 + 흑목 울타리 격자 (gableBlock).
        int ex0 = x0 - eave, ez0 = z0 - eave, ex1 = x1 + eave, ez1 = z1 + eave;
        if (paljak) {
            roofShape(world, ex0, ez0, ex1, ez1, y0 + wallH + 1, rs,
                    Material.DARK_OAK_PLANKS, 99, eave >= 2);    // 팔작 = 우진각 링 → 상부 맞배 → 능선
        } else {
            roofShape(world, ex0, ez0, ex1, ez1, y0 + wallH + 1, rs,
                    Material.DARK_OAK_PLANKS, 0, eave >= 2);     // 맞배 = 박공 널판(풍판) + 능선
        }
        if (eave >= 2) {   // 활주 — 깊은 처마 네 귀를 받치는 툇기둥
            eavePosts(world, ex0 + 1, y0, ez0 + 1, ex1 - 1, ez1 - 1, y0 + wallH);
        }
        // v6.9 ③ — 창호. 벽이 통짜로 선 **뒤**, 문을 뚫기 **전**에 창 짝을 뚫는다
        // (뒤로 미루면 창틀이 문 개구를 메운다).
        fenestrate(world, x0, y0, z0, x0 + w - 1, z0 + d - 1, wallH, doorNorth, win);
        int doorX = x0 + w / 2;
        int doorZ = doorNorth ? z0 : z1;
        world.getBlockAt(doorX, y0 + 1, doorZ).setType(Material.AIR);
        world.getBlockAt(doorX, y0 + 2, doorZ).setType(Material.AIR);
        // v6.8 ① — 실내 벽등. 골조가 서자마자 켠다 (뒤에 오는 집기 패스가 자리를 차지하기 전에).
        // y0+3 은 어느 벽고(4·5)에서도 격자창 켜(y0+2 · y0+wallH-1)가 아닌 **단단한 벽면**이다.
        roomLights(world, x0, y0 + 3, z0, x1, z1);
        wallTorch(world, doorX, y0 + 3, doorNorth ? z0 + 1 : z1 - 1,
                doorNorth ? BlockFace.SOUTH : BlockFace.NORTH);   // 문 위 등 — 동선 축을 비춘다
    }

    /** 벽 한 칸 — 자재 체계별 블록. LOG 는 벽 진행 방향으로 눕힌 원목(runsX = 동서 방향 벽) */
    private static void wallBlock(World world, int x, int y, int z, WallStyle ws, boolean runsX) {
        switch (ws) {
            case PLASTER_WHITE -> world.getBlockAt(x, y, z).setType(Material.WHITE_TERRACOTTA);
            case FRAME_GRAY -> world.getBlockAt(x, y, z).setType(Material.LIGHT_GRAY_TERRACOTTA);
            case BRICK -> world.getBlockAt(x, y, z).setType(Material.BRICKS);
            case MUD_BRICK -> world.getBlockAt(x, y, z).setType(Material.MUD_BRICKS);
            case LOG -> {
                Orientable log = (Orientable) Material.SPRUCE_LOG.createBlockData();
                log.setAxis(runsX ? Axis.X : Axis.Z);
                world.getBlockAt(x, y, z).setBlockData(log);
            }
        }
    }

    /**
     * 청하객잔 — 2층 대형 17x13 (cx-28..cx-12, cz-18..cz-6). 남향 2칸 폭 대문.
     * 1층 주청(화로·국솥·탁자 7·계산대) — 내부 계단 — 2층 객방 통칸(침상 5·난간).
     * 벽 구성: 1층 y+1..y+4 (도리 y+4) · 층간 띠/2층 바닥 y+5 · 2층 y+6..y+9 (상인방 y+9) · 지붕 y+10~ (용마루 y+17).
     */
    private static Location inn(World world, int cx, int cy, int cz) {
        // v7.0 ③ 중촌 규모표 — 객잔 17x13 → **21x15**. 벽 x[-32..-12] · z[-20..-6] · 지붕 x[-34..-10] · z[-22..-4].
        //   실내 1층 19x13 = 247칸(구 15x11 = 165칸) — 술상 여섯 조를 놓고도 바닥 여백 ≈ 70%.
        //   능선 역산: 지붕 24x18 → 우진각 링 7 · 수렴 9 · 능선 상승 5 → 용마루 cy+15, 물매 5/7 = 0.71 (0.5~0.8) ✅
        //   부지 검산: 서 x-34(담 x-60 까지 26칸 여유) · 북 z-22(민가 #2 지붕 남단 z-21 과 y 로 5칸 어긋난다:
        //   객잔 지붕 최저 cy+10, 민가 #2 지붕 최고 cy+9) · 남 z-4(동서대로 갓길 z-3 위를 처마가 덮되 지면은 안 먹는다).
        int x0 = cx - 32, z0 = cz - 20, w = 21, d = 15;
        int x1 = x0 + w - 1, z1 = z0 + d - 1;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
                boolean rim = x == x0 + 1 || x == x1 - 1 || z == z0 + 1 || z == z1 - 1;
                world.getBlockAt(x, cy, z).setType(
                        !wall && rim ? Material.DARK_OAK_PLANKS : Material.SPRUCE_PLANKS);   // 1층 마루(+귀틀)
                for (int y = cy + 1; y <= cy + 9; y++) {
                    Material m;
                    if (corner) {
                        m = Material.DARK_OAK_LOG;                       // 통기둥 2층분
                    } else if (!wall) {
                        m = (y == cy + 5) ? Material.SPRUCE_PLANKS : Material.AIR;   // 2층 바닥
                    } else if (y == cy + 4 || y == cy + 5 || y == cy + 9) {
                        m = Material.DARK_OAK_PLANKS;                    // 층도리·층간 띠·상인방
                    } else {
                        m = Material.WHITE_TERRACOTTA;                   // 백벽 (v6.9 ③ — 격자 흩뿌리기 폐기)
                    }
                    world.getBlockAt(x, y, z).setType(m);
                }
            }
        }
        // ── v6.9 ③ 객잔의 창 — 1층은 넓게(손님이 밖을 본다), 2층은 작고 높게(객방의 사생활).
        //    1층 창 켜 cy+2..cy+3 (probeY = cy+3 → 세살창만) · 2층 창 켜 cy+7..cy+8 (probeY 밖 → 창호지).
        //    정면(남, 대문 x-23..-22 양옆) 3칸 세살창 두 짝 = 마을에서 가장 큰 창 = 객잔의 얼굴.
        windowUnit(world, x0, cy, z0, x1, z1, 1, cx - 29, cx - 27, cy + 2, cy + 3, Pane.SASH);
        windowUnit(world, x0, cy, z0, x1, z1, 1, cx - 17, cx - 15, cy + 2, cy + 3, Pane.SASH);
        //    측면 — 앵커 행(z-13)은 비운다: 검수의 동·서 레이가 지나는 켜이므로 창을 내되 세살창만 가능하고,
        //    시야 축을 창으로 뚫어 두면 실내 조명(벽등)이 붙을 벽이 사라진다.
        windowUnit(world, x0, cy, z0, x1, z1, 2, cz - 16, cz - 14, cy + 2, cy + 3, Pane.SASH);
        windowUnit(world, x0, cy, z0, x1, z1, 3, cz - 17, cz - 15, cy + 2, cy + 3, Pane.SASH);
        //    뒷면(북) — 계산대·술단지 시렁(z-19 · x-27..-21)의 벽이다. 양끝 봉창 둘뿐.
        windowUnit(world, x0, cy, z0, x1, z1, 0, cx - 31, cx - 31, cy + 2, cy + 2, Pane.PAPER);
        windowUnit(world, x0, cy, z0, x1, z1, 0, cx - 13, cx - 13, cy + 2, cy + 2, Pane.PAPER);
        //    2층 객방 — 방마다 창 하나 (칸막이 울타리 사이에 정확히 한 짝씩). 작고 높다.
        for (int px : new int[]{cx - 30, cx - 25, cx - 19, cx - 14}) {
            windowUnit(world, x0, cy, z0, x1, z1, 1, px, px, cy + 7, cy + 8, Pane.PAPER);
        }
        for (int px : new int[]{cx - 30, cx - 26, cx - 22, cx - 18}) {
            windowUnit(world, x0, cy, z0, x1, z1, 0, px, px, cy + 7, cy + 8, Pane.PAPER);
        }
        windowUnit(world, x0, cy, z0, x1, z1, 2, cz - 13, cz - 13, cy + 7, cy + 8, Pane.PAPER);
        windowUnit(world, x0, cy, z0, x1, z1, 3, cz - 13, cz - 13, cy + 7, cy + 8, Pane.PAPER);
        // v6.5 ① — 처마 2칸(지붕 최외곽 = 벽+2) + 능선 수렴 (우진각 링 7 → 맞배 2 → 용마루 선 cy+15)
        roofShape(world, x0 - 2, z0 - 2, x1 + 2, z1 + 2, cy + 10,
                RoofStyle.TILE, Material.DARK_OAK_PLANKS, 99, true);
        // 층간 띠 스커트 = 1층 처마 (2층집의 허리선). 위 지붕과 같이 2칸 내밀어 두 겹 처마가 겹쳐 보이게.
        for (int x = x0 - 2; x <= x1 + 2; x++) {   // 북·남 스커트 — 결이 X 를 따른다 (TILE)
            for (int dz : new int[]{-2, -1, 1, 2}) {
                int z = dz < 0 ? z0 + dz : z1 + dz;
                world.getBlockAt(x, cy + 5, z).setType(Math.abs(dz) == 2
                        ? ridgeMat(RoofStyle.TILE, false) : solidMat(RoofStyle.TILE, false));
            }
        }
        for (int z = z0 - 1; z <= z1 + 1; z++) {   // 동·서 스커트 — 직각 결 (BRICK)
            for (int dx : new int[]{-2, -1, 1, 2}) {
                int x = dx < 0 ? x0 + dx : x1 + dx;
                world.getBlockAt(x, cy + 5, z).setType(Math.abs(dx) == 2
                        ? ridgeMat(RoofStyle.TILE, true) : solidMat(RoofStyle.TILE, true));
            }
        }
        for (int x = x0 - 2; x <= x1 + 2; x++) {   // 1층 처마 밑 서까래 라인 (남·북)
            rafter(world, x, cy + 4, z0 - 2);
            rafter(world, x, cy + 4, z1 + 2);
        }
        // v6.3 ① 2층 난간 — 층간 스커트(cy+5 반 블록) 위에 흑목 울타리 한 바퀴.
        // 2층 처마(cy+9 마구리) 와 1층 처마(cy+5) 사이에 목재 가로선이 하나 더 그어져 허리가 살아난다.
        // 지면 5칸 위라 앞마당·소로·장쇠 노점 어느 것도 침범하지 않는다 (통행 방해 0).
        balustradeRing(world, x0 - 2, z0 - 2, x1 + 2, z1 + 2, cy + 6);
        for (int px : new int[]{cx - 29, cx - 15}) {   // 활주(活柱) 2주 — 정면 툇마루 기둥 (1층 처마를 받는다)
            for (int y = cy + 1; y <= cy + 4; y++) {
                world.getBlockAt(px, y, z1 + 2).setType(Material.SPRUCE_FENCE);
            }
        }
        // 남향 대문 2칸 폭 (cx-23, cx-22)
        for (int x = cx - 23; x <= cx - 22; x++) {
            world.getBlockAt(x, cy + 1, z1).setType(Material.AIR);
            world.getBlockAt(x, cy + 2, z1).setType(Material.AIR);
        }
        placeSign(world, cx - 18, cy + 1, z1 + 1, BlockFace.SOUTH, "청하객잔", "소문은 국밥보다 빨리 식는다");   // 앞마당(x-26..-19) 밖
        hangingSign(world, cx - 23, cy + 9, z1 + 2, BlockFace.SOUTH,                 // v6 ④ — 정면 처마 밑 현판
                "청하객잔", "소문은 국밥보다 빨리 식는다");
        chainLantern(world, cx - 25, cy + 4, z1 + 1, 1);                             // 대문 양옆 현수 홍등 2조
        chainLantern(world, cx - 20, cy + 4, z1 + 1, 1);
        // 주기(酒旗) — 서쪽 처마 끝(x-34) **밖**(x-35)에 세운다. 처마 밑에 세우면 깃발이 안 보이고,
        // 동서대로(z-3..+3) 위에 세우면 길을 막는다 → x-35 · z-8 (지붕 상자·노면 어느 쪽도 침범 0).
        for (int y = cy + 1; y <= cy + 5; y++) {
            world.getBlockAt(cx - 35, y, cz - 8).setType(Material.SPRUCE_FENCE);
        }
        world.getBlockAt(cx - 35, cy + 6, cz - 8).setType(Material.DARK_OAK_PLANKS);   // 장대 끝 가로대
        world.getBlockAt(cx - 34, cy + 6, cz - 8).setType(Material.DARK_OAK_PLANKS);
        hangingSign(world, cx - 34, cy + 5, cz - 8, BlockFace.SOUTH, "청하객잔", "금일 — 국밥·술");
        world.getBlockAt(cx - 35, cy + 1, cz - 7).setType(Material.LANTERN);           // 장대 밑 등롱
        innStairs(world, cx, cy, cz);
        innHall(world, cx, cy, cz);
        innLoft(world, cx, cy, cz);
        return loc(world, cx - 22, cy + 1, cz - 13);   // 앵커 = 1층 주청 중앙 (한백)
    }

    /**
     * v6.8 ① 객잔 내부 계단 — **정말로 올라갈 수 있는가**를 좌표로 따진다.
     *
     * <p>v6.7 의 계단은 네 단(cy+1..cy+4)이었다. 2층 마루는 cy+5 블록이므로 그 윗면(= 걷는 면)은 cy+6.
     * 마지막 디딤의 윗면은 cy+5 → 마루까지 **한 칸을 점프해야** 올라갔다. 계단이 아니라 벽이었다.
     * v6.8 은 다섯 단(cy+1..cy+5)으로 늘린다: 마지막 디딤이 마루와 같은 높이(윗면 cy+6)에 놓여 **평평하게
     * 이어진다**. 각 디딤 위로는 2층 마루 켜(cy+5)까지 전부 비워 머리가 닿지 않는다(통행고 확보).
     * 난간은 개구 서쪽 줄(cx-14)에 다섯 칸 — 계단참에서 떨어지지 않는다.
     */
    private static void innStairs(World world, int cx, int cy, int cz) {
        // v7.0 ③ — 동벽 안줄이 x-13 그대로다(벽 x-12). 계단은 z-8..-12 — **앵커 행(z-13)을 비운다**:
        // 계단 받침(가문비 판재)이 그 행의 cy+3 에 서면 검수의 동쪽 레이가 계단을 벽으로 읽어 벽 박스가 줄어든다.
        for (int i = 0; i <= 4; i++) {
            int z = cz - 8 - i;
            Stairs stairs = (Stairs) Material.SPRUCE_STAIRS.createBlockData();
            stairs.setFacing(BlockFace.NORTH);   // 북쪽으로 오르는 디딤
            for (int y = cy + 1; y < cy + 1 + i; y++) {
                world.getBlockAt(cx - 13, y, z).setType(Material.SPRUCE_PLANKS);   // 계단 받침
            }
            world.getBlockAt(cx - 13, cy + 1 + i, z).setBlockData(stairs);
            for (int y = cy + 2 + i; y <= cy + 5; y++) {
                world.getBlockAt(cx - 13, y, z).setType(Material.AIR);   // 머리 위 통행고 + 2층 바닥 개구
            }
            world.getBlockAt(cx - 14, cy + 6, z).setType(Material.SPRUCE_FENCE);   // 개구 난간
        }
    }

    /**
     * 객잔 1층 주청 — v6.1 ③ 공간 문법.
     * 시선 축: 남쪽 2칸 대문(x-21..-20)으로 들어와 정면 북벽(z-17)에 **계산대 + 술단지 시렁** —
     *   이 집이 무엇을 파는 집인지 문턱에서 한 눈에 읽힌다. 나머지 소품은 전부 축을 비껴 놓는다.
     * 벽면 3분할: 하단(y+1) 가구·수납 / 중단(y+2) 선반·창 / 상단(y+3~4) 조명·현수.
     * 바닥 여백: 대문 → 계산대 통로(x-21..-20)는 깔개만 깔고 비운다. 탁자는 좌우 벽을 등진다.
     * 밀도 등급 = 높음 (객잔은 어수선한 것이 성격이다 — 정돈은 전장·의방의 몫).
     */
    private static void innHall(World world, int cx, int cy, int cz) {
        // v7.0 ③ 실내 19x13 — 벽 안줄: 서 x-31 · 동 x-13 · 북 z-19 · 남 z-7. 앵커 = (x-22, z-13).
        // ── 시선 축: 북벽 계산대 (하단 = 판재 상판 / 중단 = 시렁 / 상단 = 술 단지)
        //   술 단지는 두 자리에만 고정 = 북벽 안줄 3점(단지 2 + 술통 1). **x-22 열은 비운다** —
        //   검수의 북쪽 레이가 그 열의 cy+3 을 지나므로 단지 하나가 벽 박스를 흔들 수 있다.
        for (int x = cx - 27; x <= cx - 21; x++) {
            world.getBlockAt(x, cy + 1, cz - 18).setType(Material.DARK_OAK_PLANKS);   // 계산대 상판
            topSlab(world, x, cy + 2, cz - 19, Material.DARK_OAK_SLAB);               // 뒤 시렁 (중단)
        }
        // v7.1(1.21.11) — 단지를 **얹는다**. 블록으로 세운 단지 둘 대신, 시렁 하나에 술 셋.
        //   소품 예산: 단지 2점 → 시렁 1점 (북벽이 한 칸 넉넉해진다).
        shelf(world, cx - 26, cy + 3, cz - 19, Material.DARK_OAK_SHELF, BlockFace.SOUTH,
                new org.bukkit.inventory.ItemStack(Material.DECORATED_POT),
                new org.bukkit.inventory.ItemStack(Material.HONEY_BOTTLE),
                new org.bukkit.inventory.ItemStack(Material.GLASS_BOTTLE));
        wallTorch(world, cx - 27, cy + 2, cz - 18, BlockFace.SOUTH);   // 계산대 등 — 벽등(소품 예산 0)
        wallTorch(world, cx - 21, cy + 2, cz - 18, BlockFace.SOUTH);
        world.getBlockAt(cx - 20, cy + 1, cz - 19).setType(Material.BARREL);          // 계산대 곁 술통 1 (하단)
        // ── 서벽(x-31 안줄) 주방 — 화덕·훈연·국솥. 벽 한 면 3점 (여백 규칙)
        hearth(world, cx - 31, cy, cz - 18);
        world.getBlockAt(cx - 31, cy + 1, cz - 17).setType(Material.SMOKER);
        world.getBlockAt(cx - 31, cy + 1, cz - 16).setType(Material.CAULDRON);        // 국솥
        hangingLantern(world, cx - 30, cy + 4, cz - 17);                              // 주방 등 — 2층 바닥에 매단다
        // ── 술상 6 — 좌우 벽을 등지고 두 줄. 대문↔계산대 축(x-23..-22)은 비운다.
        //    v6.8 ① 술상마다 남·북으로 마주 앉는 걸상 한 쌍 (계단 블록 = 소품 예산 0).
        int[][] tables = {{-29, -16}, {-29, -12}, {-28, -8},
                {-17, -16}, {-17, -12}, {-16, -8}};
        for (int[] t : tables) {
            world.getBlockAt(cx + t[0], cy + 1, cz + t[1]).setType(Material.SPRUCE_FENCE);
            world.getBlockAt(cx + t[0], cy + 2, cz + t[1]).setType(Material.SPRUCE_PRESSURE_PLATE);
            stoolPair(world, cx + t[0], cy + 1, cz + t[1]);
        }
        world.getBlockAt(cx - 30, cy + 1, cz - 12).setType(Material.DECORATED_POT);   // 상 곁 술 단지 (좌표 상수)
        world.getBlockAt(cx - 16, cy + 1, cz - 14).setType(Material.DECORATED_POT);
        // ── 상단(y+4) 현수 홍등 — 2층 바닥에 매단다. 한백 앵커(x-22, z-13)는 비운다.
        hangingLantern(world, cx - 27, cy + 4, cz - 13);
        hangingLantern(world, cx - 18, cy + 4, cz - 13);
        hangingLantern(world, cx - 22, cy + 4, cz - 9);
        // ── v6.8 ① 1층 조명 — 벽등 링(cy+3) + 계단참 현수등. 19x13 은 링만으로 한복판이 광원 7 로 떨어진다.
        roomLights(world, cx - 32, cy + 3, cz - 20, cx - 12, cz - 6);
        wallTorch(world, cx - 23, cy + 3, cz - 19, BlockFace.SOUTH);
        hangingLantern(world, cx - 15, cy + 4, cz - 11);
        // ── 바닥: 대문 → 계산대 통로 깔개 (사람이 서는 자리, 통행 가능)
        for (int z = cz - 7; z >= cz - 16; z--) {
            world.getBlockAt(cx - 23, cy + 1, z).setType(Material.RED_CARPET);
            world.getBlockAt(cx - 22, cy + 1, z).setType(Material.RED_CARPET);
        }
        placeSign(world, cx - 19, cy + 1, cz - 7, BlockFace.NORTH, "금일 — 국밥", "객방 있음 — 이층");   // 차림 팻말
    }

    /**
     * 객잔 2층 객방 — v6.8 ①: 통칸이 아니라 **다섯 조의 방**이다.
     *
     * <p>2층은 계단으로 올라와도 침상 열 개가 널린 창고였다. 객방은 '칸'이 있어야 객방이다:
     * 침상 사이에 죽·목 칸막이(가문비 울타리 2단)를 세워 다섯 조로 가른다. 칸막이를 **판벽이 아니라
     * 울타리**로 세우는 데는 이유가 둘 있다 — ㉮ 향촌 객잔의 객방은 원래 발·널로 나뉜 반개방이고,
     * ㉯ 울타리는 빛을 통과시킨다 (판벽으로 막으면 칸마다 광원을 새로 넣어야 하고 그만큼 몹 스폰 위험이 는다).
     *
     * <p>천장고 — 2층 마루 cy+5, 상인방 cy+9 → 걷는 면 cy+6 위로 **4칸**(지붕 밑은 cy+10부터). 2칸 이상 확보.
     * 조명 — 벽등 링을 **바닥 높이(cy+6)** 에 건다: 2층 벽의 격자창 켜는 cy+7 이므로 cy+6·cy+8 이 단단한 벽인데,
     * cy+8 에 걸면 방 한복판이 광원 7 로 떨어진다(평면 5 + 높이 2 = 7 → 8… 실제 최악 셀은 8 → 7).
     * cy+6 은 높이차가 0 이라 평면거리 7 까지 광원 8 을 준다 — 침상 옆 촛대의 높이이기도 하다.
     */
    private static void innLoft(World world, int cx, int cy, int cz) {
        // v7.0 ③ 2층 19x13 — 객방 5조. ★ 소품(궤·통·단지·등롱)은 **벽 안줄(x-31·x-13·z-19·z-7)을 피해**
        //   방 안쪽 열에만 둔다: propScan 은 1층과 2층의 소품을 **같은 벽면 예산으로 합산**하고
        //   (y 범위 = 바닥+1 ~ 지붕최저-1), 1층 북벽이 이미 3점을 다 쓴다. 침상·깔개(양탄자)와
        //   칸막이(울타리)는 PROP 집합 밖이라 안줄에 놓아도 예산을 쓰지 않는다.
        int[][] beds = {{-30, -19}, {-29, -19}, {-26, -19}, {-25, -19}, {-22, -19}, {-21, -19},
                {-18, -19}, {-17, -19}, {-31, -12}, {-31, -11}};   // 2칸 침상 x5
        for (int[] b : beds) {
            world.getBlockAt(cx + b[0], cy + 6, cz + b[1]).setType(Material.WHITE_CARPET);
        }
        int[][] rugs = {{-29, -18}, {-25, -18}, {-21, -18}, {-17, -18}, {-30, -11}};   // 침상 곁 깔개
        for (int[] r : rugs) {
            world.getBlockAt(cx + r[0], cy + 6, cz + r[1]).setType(Material.LIGHT_GRAY_CARPET);
        }
        // v6.8 ① 객방 칸막이 — 북열 4조(x-28 · x-24 · x-20)와 서열 1조(z-13)를 가른다.
        //   판벽이 아니라 울타리인 이유: 빛이 통과한다 (칸마다 광원을 새로 넣지 않아도 최소 8 이 유지된다).
        for (int x : new int[]{cx - 28, cx - 24, cx - 20}) {
            for (int z = cz - 19; z <= cz - 17; z++) {
                world.getBlockAt(x, cy + 6, z).setType(Material.SPRUCE_FENCE);
                world.getBlockAt(x, cy + 7, z).setType(Material.SPRUCE_FENCE);
            }
        }
        for (int x = cx - 31; x <= cx - 29; x++) {
            world.getBlockAt(x, cy + 6, cz - 13).setType(Material.SPRUCE_FENCE);
            world.getBlockAt(x, cy + 7, cz - 13).setType(Material.SPRUCE_FENCE);
        }
        world.getBlockAt(cx - 16, cy + 6, cz - 15).setType(Material.CHEST);           // 짐궤 — 묵삼의 방 (v7.0: 북벽 안줄에서 물린다)
        world.getBlockAt(cx - 15, cy + 6, cz - 15).setType(Material.BARREL);          // (벽 안줄 밖 = 소품 예산 0)
        world.getBlockAt(cx - 17, cy + 6, cz - 14).setType(Material.DECORATED_POT);   // 오래 묵는 손님의 짐
        roomLights(world, cx - 32, cy + 6, cz - 20, cx - 12, cz - 6);   // v6.8 ① 2층 벽등 링 (바닥 높이)
        // 2층 복도 등롱 3 — 21칸 폭이라 벽등 링만으로는 복도 한복판이 광원 7 이다
        // (**횃불은 14, 랜턴은 15** — 이 한 칸 차이가 실내 광원 설계의 전부다). 복도 열은 벽 안줄이 아니다.
        world.getBlockAt(cx - 27, cy + 6, cz - 13).setType(Material.LANTERN);
        world.getBlockAt(cx - 22, cy + 6, cz - 13).setType(Material.LANTERN);
        world.getBlockAt(cx - 16, cy + 6, cz - 13).setType(Material.LANTERN);
        world.getBlockAt(cx - 25, cy + 6, cz - 9).setType(Material.LANTERN);
        world.getBlockAt(cx - 19, cy + 6, cz - 9).setType(Material.LANTERN);
    }

    // ─── v6.6 ① 결의 축 — 경사면 방향에 따라 자재를 바꾼다 ───
    //
    // 블록 텍스처는 방향이 고정이다. 기와의 결(가로 단 이음)은 북·남 경사면에서는 능선과 나란히 눕지만,
    // 같은 자재를 동·서 경사면에 쓰면 결이 **물매를 따라 세로로** 서서 기와가 옆으로 누운 것처럼 보인다.
    // 사용자 지적: "한 방향으로 되어 조금 이질감이 듦."
    //
    // 리소스팩이 DEEPSLATE_BRICKS(+계단·반블록)를 DEEPSLATE_TILES 의 **90도 회전판**으로 재텍스처했다.
    // 두 자재의 결이 서로 직각이므로, 조성기는 **면의 방향에 따라 자재를 고르기만 하면 된다**:
    //   결이 X 를 따라 흘러야 하는 면(북·남 경사면 — 칸이 x 로 늘어선다, 계단 facing = NORTH/SOUTH) → TILE
    //   결이 Z 를 따라 흘러야 하는 면(동·서 경사면 — 칸이 z 로 늘어선다, 계단 facing = EAST/WEST)  → BRICK
    // 용마루는 능선 방향과 결을 맞춘다 (X 능선 → TILE, Z 능선 → BRICK).
    // 추녀마루(모서리)는 두 면이 만나는 대각선이라 어느 결도 맞지 않는다 → **TILE 로 통일**(우진각 링의
    // 모서리가 이미 x 루프에서 나오므로 이 쪽이 기존 실루엣과 같다).
    //
    // TILE 계열과 BRICK 계열은 TownAudit 의 ROOF 집합에 **둘 다** 들어 있고(74~78행), 검수는 자재의
    // 소속만 볼 뿐 계열을 구분하지 않는다 → 지붕 인식·처마·물매·능선 평지 판정은 전부 불변이다.
    // 형태(계단/반블록/풀블록)도 1:1로 보존한다. 결정론: 자재 선택이 좌표가 아니라 **면의 방향**만 본다.

    /** 이 면의 결이 Z 를 따라 흘러야 하는가 — 동·서 경사면(계단이 x 축으로 오르내리는 면) */
    private static boolean crossGrain(BlockFace facing) {
        return facing == BlockFace.EAST || facing == BlockFace.WEST;
    }

    private static void roofBlock(World world, int x, int y, int z, BlockFace facing,
                                  boolean corner, RoofStyle rs) {
        roofBlock(world, x, y, z, facing, corner, rs, crossGrain(facing));
    }

    /**
     * 지붕 한 칸. cross = 직각 결 자재(회전판)를 쓸 것인가.
     * 추녀마루처럼 결이 어느 쪽도 아닌 칸은 호출자가 cross=false 로 눌러 통일한다.
     */
    private static void roofBlock(World world, int x, int y, int z, BlockFace facing,
                                  boolean corner, RoofStyle rs, boolean cross) {
        Block block = world.getBlockAt(x, y, z);
        if (corner) {
            block.setType(solidMat(rs, cross));   // 추녀마루 · 기와골 · 적새
            return;
        }
        Stairs stairs = (Stairs) stairMat(rs, cross).createBlockData();
        stairs.setFacing(facing);   // 안쪽으로 오르는 기와면
        block.setBlockData(stairs);
    }

    private static Material ridgeMat(RoofStyle rs, boolean cross) {
        return switch (rs) {
            case TILE -> cross ? Material.DEEPSLATE_BRICK_SLAB : Material.DEEPSLATE_TILE_SLAB;
            case SHINGLE -> Material.DARK_OAK_SLAB;   // 너와 = 목재, 결이 방향을 타지 않는다
            case MUD_TILE -> Material.MUD_BRICK_SLAB;
        };
    }

    private static Material solidMat(RoofStyle rs, boolean cross) {
        return switch (rs) {
            case TILE -> cross ? Material.DEEPSLATE_BRICKS : Material.DEEPSLATE_TILES;
            case SHINGLE -> Material.DARK_OAK_PLANKS;
            case MUD_TILE -> Material.MUD_BRICKS;
        };
    }

    private static Material stairMat(RoofStyle rs, boolean cross) {
        return switch (rs) {
            case TILE -> cross ? Material.DEEPSLATE_BRICK_STAIRS : Material.DEEPSLATE_TILE_STAIRS;
            case SHINGLE -> Material.DARK_OAK_STAIRS;
            case MUD_TILE -> Material.MUD_BRICK_STAIRS;
        };
    }

    // ─── v6.1 ② 지붕 재설계 — 깊은 처마 · 완만한 물매 · 도톰한 용마루 ───
    //
    // v6 의 지붕은 계단 링을 1칸 전진 1칸 상승으로 쌓아 물매가 45°였다 — 뭉툭하고 무겁다.
    // v6.1 의 물매는 "2칸 전진 1칸 상승": 한 y 에 링을 두 겹(계단 → 풀 블록) 얹고 그 다음에 한 칸 오른다.
    //   step s → y = yBase + s/2, s 가 짝수면 안쪽으로 오르는 계단(반 칸), 홀수면 풀 블록(그 y 의 평평한 단).
    //   대각 이음이라 하늘이 새지 않고, 눈으로는 완만한 곡면으로 읽힌다.
    // 처마는 벽 바깥 2칸 (전 방향). 그 밑에 흑목 반 블록 서까래 라인이 깔려 깊은 그늘이 생긴다 —
    //   무협 향촌 건축의 얼굴은 벽이 아니라 이 그늘이다.
    // 용마루는 반 블록 한 줄이 아니라 풀 블록 몸통 + 반 블록 덧단 + 양단 치미(한 칸 더 높은 풀 블록).

    /**
     * 지붕 한 채. (x0,z0)-(x1,z1) 은 **처마 끝** 사각형(벽이 아니라 처마 외곽)이므로,
     * 호출자가 벽 사각형에서 원하는 만큼(주요 건물 2칸, 부속채 1칸) 부풀려 넘긴다.
     * hipSteps = 팔작 하부 우진각 링 수 (0 이면 순수 맞배 — 민가의 위계).
     */
    private static void roofShape(World world, int x0, int z0, int x1, int z1, int yBase,
                                  RoofStyle rs, Material gable, int hipSteps) {
        roofShape(world, x0, z0, x1, z1, yBase, rs, gable, hipSteps, false);
    }

    /**
     * v6.5 ① 【능선 수렴】 — 지붕은 '고원'이 아니라 '능선'이다.
     *
     * <p>v6.4 의 지붕은 물매 rise(s)=(s+1)/3 (세 칸 전진 한 칸 상승)로 너무 완만해서, 건물 반폭을 다
     * 전진해도 두 경사면이 만나지 못했다. 남은 꼭대기 평지에 용마루 몸통·등·치미·덧단을 4켜로 얹으니
     * **넓은 검은 평지 위에 작은 모자**가 됐다 (조감도 클로즈업). 검수(③-b)의 실측:
     * 최상단 y 평면의 지붕 자재 비율이 56~78% — 용마루가 선이 아니라 면이었다.
     *
     * <p>v6.5 는 지붕 높이를 **건물 폭에서 역산**한다:
     * <pre>
     *   nHip   = 실제로 도는 우진각 링 수 (팔작 하부)          — 검수 물매의 run 이 바로 이 값이다
     *   nTotal = 경사면이 능선에서 만나기까지의 전체 단 수      — 짧은 변이 0 이 될 때까지
     *   H      = ridgeRise(nHip, nTotal) ≈ 0.65 × run          — 능선의 상승량
     *   rise(s)= round(s × H / nTotal)                          — 단마다 균일하게 오른다 (증분 ≤ 1)
     * </pre>
     * 마지막 경사단은 y = yBase+H-1 에서 **풀 블록 한 켜**로 마감하고(적새), 그 위 y = yBase+H 에
     * **폭 1~2칸의 용마루 선** 하나만 얹는다. 용마루 위에는 아무것도 없다 — 등·치미·덧단·뿔을 전부
     * 걷어냈다. 그것들이 최상단 y 평면을 '용마루 line 만'으로 채워 평지 비율을 78% 로 만든 범인이다.
     *   → 최상단 평면 = 용마루 선(5~7칸) / 그 밑 한 켜 = 경사면 30~38칸 → 평지 비율 13~16%.
     *   → 검수 물매 = H / nHip = 4/6 · 5/7 → 0.67~0.71 (0.5~0.8 대역 유지).
     *   → 지붕 최고 높이는 v6.4 와 **같다** (덜어낸 용마루 4켜만큼 경사면이 올라왔다) = 처마 겹침 회귀 0.
     *
     * <p>deepEave: 지붕 최외곽(벽+2) 한 칸 안쪽(벽+1)에 흑목 반 블록 서까래를 깔지 여부
     * (처마 2칸 집만 — 부속채·잡화점은 마구리 울타리만 = 위계).
     */
    private static void roofShape(World world, int x0, int z0, int x1, int z1, int yBase,
                                  RoofStyle rs, Material gable, int hipSteps, boolean deepEave) {
        eaveFenceRim(world, x0, z0, x1, z1, yBase - 1);   // ① 마구리 — 처마 끝에 매단 흑목 살
        if (deepEave) {
            rafterLine(world, x0 + 1, z0 + 1, x1 - 1, z1 - 1, yBase - 1);   // 서까래 그늘 (한 칸 안쪽)
        }
        int nHip = hipRingCount(x1 - x0, z1 - z0, hipSteps);
        int nTotal = Math.max(1, convergeSteps(x1 - x0, z1 - z0, nHip));
        int h = ridgeRise(nHip, nTotal);

        int ax = x0, bx = x1, az = z0, bz = z1;
        int s = 0;
        while (s < nHip) {   // 우진각(팔작 하부) 링
            int y = yBase + rise(s, h, nTotal);
            int lift = halfStep(s, h, nTotal) ? 1 : 0;
            hipRing(world, ax, az, bx, bz, y, rs, capping(s, h, nTotal));
            if (lift > 0) {
                hipRingSlab(world, ax, az, bx, bz, y + 1, rs);   // v6.7 ① — 평평한 단을 반 칸 올린다
            }
            if (ribbed(s, h, nTotal)) {
                faceRibs(world, ax, az, bx, bz, y + lift, rs);   // v6.7 ① — 남·북 경사면의 수키와 골
            }
            ax++;
            bx--;
            az++;
            bz--;
            s++;
        }
        boolean ridgeX = (bx - ax) >= (bz - az);   // 용마루는 장변을 따라 눕는다
        boolean plaster = hipSteps > 0;            // 팔작 = 합각 회벽 / 맞배 = 박공 널판(풍판)
        boolean first = true;                      // 합각 살창은 첫 단(가장 넓은 단) 한가운데
        while (ridgeX ? bz - az > 1 : bx - ax > 1) {
            int y = yBase + rise(s, h, nTotal);
            boolean solid = capping(s, h, nTotal);
            boolean half = halfStep(s, h, nTotal);   // v6.7 ① — 이 단은 반 칸만 오른다
            if (ridgeX) {
                for (int x = ax; x <= bx; x++) {
                    roofCell(world, x, y, az, BlockFace.SOUTH, rs, solid || groove(x));
                    roofCell(world, x, y, bz, BlockFace.NORTH, rs, solid || groove(x));
                    if (half) {
                        roofSlab(world, x, y + 1, az, rs, false);
                        roofSlab(world, x, y + 1, bz, rs, false);
                    }
                }
                if (ribbed(s, h, nTotal)) {
                    faceRibs(world, ax, az, bx, bz, y + (half ? 1 : 0), rs);
                }
                int mid = (az + bz) / 2;
                int run = bz - az - 1;                          // 이 단의 삼각면 폭
                for (int z = az + 1; z <= bz - 1; z++) {
                    boolean edge = z == az + 1 || z == bz - 1;   // 경사변에 닿는 칸 = 판자 테두리
                    boolean lattice = lattice(plaster, first, run, z - mid);
                    gableBlock(world, ax, y, z, gable, plaster, edge, lattice);
                    gableBlock(world, bx, y, z, gable, plaster, edge, lattice);
                }
                az++;
                bz--;
            } else {
                for (int z = az; z <= bz; z++) {
                    roofCell(world, ax, y, z, BlockFace.EAST, rs, solid || groove(z));
                    roofCell(world, bx, y, z, BlockFace.WEST, rs, solid || groove(z));
                    if (half) {
                        roofSlab(world, ax, y + 1, z, rs, true);
                        roofSlab(world, bx, y + 1, z, rs, true);
                    }
                }
                int mid = (ax + bx) / 2;
                int run = bx - ax - 1;
                for (int x = ax + 1; x <= bx - 1; x++) {
                    boolean edge = x == ax + 1 || x == bx - 1;
                    boolean lattice = lattice(plaster, first, run, x - mid);
                    gableBlock(world, x, y, az, gable, plaster, edge, lattice);
                    gableBlock(world, x, y, bz, gable, plaster, edge, lattice);
                }
                ax++;
                bx--;
            }
            first = false;
            s++;
        }
        ridgeLine(world, ax, az, bx, bz, yBase + h, rs);
        eaveRim(world, x0, z0, x1, z1, yBase, rs);   // 처마 끝단을 반 블록으로 깎아 그림자 선을 낸다
    }

    /** 실제로 도는 우진각 링 수 — roofShape 의 링 루프와 같은 조건을 미리 센다 (물매 역산의 run) */
    private static int hipRingCount(int dx, int dz, int hipSteps) {
        int s = 0;
        while (s < hipSteps && dx - 2 * s > 2 && dz - 2 * s > 2) {
            s++;
        }
        return s;
    }

    /** 경사면이 능선에서 만나기까지의 전체 단 수 — 우진각 링 + (짧은 변이 0 이 될 때까지의) 맞배 단 */
    private static int convergeSteps(int dx, int dz, int nHip) {
        int rx = dx - 2 * nHip;
        int rz = dz - 2 * nHip;
        return nHip + ((rx >= rz ? rz : rx) / 2);
    }

    /**
     * v6.5 ① 능선 상승량 — 폭에서 역산한다.
     * 검수 물매는 앵커 x 행의 단면이고, 팔작에서 그 행에 지붕이 얹히는 구간은 **우진각 링이 도는 만큼**이다
     * (맞배 단은 짧은 변만 좁히므로 앵커 행에 새 칸을 얹지 않는다) → 검수 run = nHip.
     * 따라서 H ≈ 0.65 × nHip 이면 검수 물매가 0.5~0.8 한복판에 떨어진다 (13/20 = ceil 0.65).
     * 맞배(민가·부속채)는 우진각 링이 없으므로 nTotal(= 실제 반폭)으로 같은 물매를 만든다.
     * 하한 nTotal/2 + 1 — 이보다 낮으면 rise(nTotal) == rise(nTotal-1) 이 되어 용마루가 마지막 경사단과
     * 같은 높이에 깔린다 = 다시 고원이다. 능선은 **반드시 경사면보다 한 칸 위**여야 한다.
     */
    private static int ridgeRise(int nHip, int nTotal) {
        int run = nHip > 0 ? nHip : nTotal;
        int h = (13 * run + 19) / 20;                       // ceil(0.65 × run)
        return Math.max(Math.max(h, nTotal / 2 + 1), 2);
    }

    /** 물매 — 단 s 의 상승량. round(s × H / nTotal) : 증분은 늘 0 또는 1 (H ≤ nTotal) = 하늘이 새지 않는다 */
    private static int rise(int s, int h, int n) {
        return (2 * s * h + n) / (2 * n);
    }

    /**
     * 그 단을 풀 블록으로 깔 것인가 — ① 앞 단과 같은 y (그 y 의 두 번째 단) 또는
     * ② **마지막 경사단**(용마루 바로 밑 = 적새). 용마루는 이 켜 위에 올라앉는다.
     */
    private static boolean capping(int s, int h, int n) {
        return s == n - 1 || (s > 0 && rise(s, h, n) == rise(s - 1, h, n));
    }

    /**
     * v6.7 ① 【반 칸 단】 — **평평한 3칸 단이 테라스로 읽힌다**.
     *
     * <p>v6.5·v6.6 의 지붕은 물매 0.67 을 만들려고 "세 칸 전진 한 칸 상승"을 썼다: 같은 정수 y 에
     * 계단(s) → 풀 블록(s+1) 두 겹이 나란히 눕고 그 다음 단에서 한 칸 오른다. 물매는 옳았지만
     * **그 평평한 단이 조감도에서 동심원 층계(웨딩케이크)로 읽혔다** — 눈은 rise/run 이 아니라
     * '단의 폭'을 본다.
     *
     * <p>고치는 법은 마인크래프트 지붕의 표준 기법이다: **반 블록으로 단차를 반 칸으로 쪼갠다**.
     * 같은 y 를 두 번째로 밟는 단(= capping 이 참인 반복 단)은 풀 블록 위에 **반 블록 한 켜**를 더 얹어
     * 윗면을 y+1.5 로 올린다. 그러면 한 단의 윗면이 y+1.0 → y+1.5 → y+2.0 으로 **반 칸씩** 오른다:
     * 계단 → (풀블록+반블록) → 계단. 테라스가 사라지고 물매가 눈에 매끈하게 흐른다.
     *
     * <p>검수 불변식 — 이 반 블록은 세 수치를 **한 톨도 건드리지 않는다**:
     *   ㉮ 물매 = (용마루 y − 처마 y) / nHip. 처마(단 0)와 용마루는 그대로다. 반 블록은 그 **사이** 단만
     *      올리므로 단면의 시작점·최고점이 안 움직인다 → 0.67·0.71 불변.
     *   ㉯ 능선 평지 = 최상단 y 의 지붕 칸 / (최상단 + 그 밑 켜). 반 블록의 y 는 **용마루보다 최소 한 칸 밑**
     *      (rise+1 ≤ h−1 가드)이라 최상단 평면을 늘리지 못하고, 그 밑 켜(분모)만 늘린다 → 평지 비율이 **내려간다**.
     *   ㉰ 마지막 경사단(적새, s = n−1)은 제외한다 — 거기 반 블록을 얹으면 용마루 켜와 같은 높이가 되어
     *      능선이 다시 고원이 된다.
     * 결(結) 규칙도 그대로다: 반 블록도 면의 방향으로 계열을 고른다 (북남 = TILE, 동서 = BRICK 회전판).
     */
    private static boolean halfStep(int s, int h, int n) {
        if (s <= 0 || s >= n - 1) {
            return false;                                   // 처마단·적새단은 건드리지 않는다
        }
        int y = rise(s, h, n);
        return y == rise(s - 1, h, n) && y + 1 <= h - 1;    // 반복 단이면서, 용마루 켜를 침범하지 않는다
    }

    /**
     * v6.7 ① 【수키와 골 — 층계의 나머지 절반】 반 칸 단은 **물매 방향(z)** 의 테라스를 깼다.
     * 그런데 지붕면은 **능선 방향(x)** 으로도 평평하다: 남·북 경사면의 한 단은 x 로 폭 전체(최대 21칸)가
     * 같은 높이로 눕는다 — 검수 ③-c(평균 단 길이 ≤ 3.0)가 재는 것이 바로 이 가로 평탄이다.
     *
     * <p>기와지붕의 답은 이미 있다: **수키와(볼록 기와) 줄**. v6.2 의 기와골(groove — 4칸마다 풀 블록)은
     * 자재만 바꿨을 뿐 높이가 같아 위에서 보면 여전히 판때기였다. v6.7 은 그 골을 **반 칸 융기**시킨다:
     * 골 몸통(풀 블록) 위에 반 블록 한 장 → 4칸마다 도드라진 줄이 물매를 따라 흘러내린다.
     * 검수의 가로 스캔이 4칸마다 끊기므로 평균 단 길이가 3.2~3.6 → 2.1~2.4 로 내려간다.
     *
     * <p>가드 두 개 —
     *   ㉮ 처마 끝 링(s=0)에는 골을 얹지 않는다: 처마 끝단은 반 블록 마구리(eaveRim)로 얇아야 그림자 선이 산다.
     *      덤으로 검수의 물매 단면 시작점(처마 y)이 절대 안 움직인다.
     *   ㉯ 골 마루가 **용마루보다 최소 한 칸 밑**이어야 한다 (t+1 ≤ h−1) — 아니면 능선 평면이 부풀어 고원이 된다.
     * 물매 단면(앵커 z행)은 동·서 경사면 칸만 지나므로 남·북 면의 골은 물매에 아예 닿지 않는다 (0.67·0.71 불변).
     */
    private static boolean ribbed(int s, int h, int n) {
        if (s < 1) {
            return false;                                        // 처마 끝 링 = 얇은 마구리
        }
        int t = rise(s, h, n) + (halfStep(s, h, n) ? 1 : 0);     // 이 단의 윗면 (반 칸 단이면 한 켜 위)
        return t + 1 <= h - 1;                                   // 용마루 켜를 침범하지 않는다
    }

    /** 남·북 경사면의 수키와 골 — 4칸마다(groove) 한 줄. 결은 북남 면이므로 TILE 계열 (crossGrain 규칙) */
    private static void faceRibs(World world, int ax, int az, int bx, int bz, int base, RoofStyle rs) {
        for (int x = ax; x <= bx; x++) {
            if (!groove(x)) {
                continue;
            }
            roofRib(world, x, base, az, rs);
            roofRib(world, x, base, bz, rs);
        }
    }

    /** 골 한 줄 — 몸통(풀 블록) + 그 위 반 블록. 반 칸 단이면 그 반 블록 자리를 풀 블록으로 갈아 얹는다 */
    private static void roofRib(World world, int x, int base, int z, RoofStyle rs) {
        world.getBlockAt(x, base, z).setType(solidMat(rs, false));
        world.getBlockAt(x, base + 1, z).setType(ridgeMat(rs, false));
    }

    /** v6.7 ① 반 칸 켜 한 링 — 우진각 링의 네 변 위에 반 블록을 얹는다 (모서리 = 추녀마루 계열 통일) */
    private static void hipRingSlab(World world, int ax, int az, int bx, int bz, int y, RoofStyle rs) {
        for (int x = ax; x <= bx; x++) {
            roofSlab(world, x, y, az, rs, false);
            roofSlab(world, x, y, bz, rs, false);
        }
        for (int z = az + 1; z <= bz - 1; z++) {
            roofSlab(world, ax, y, z, rs, true);
            roofSlab(world, bx, y, z, rs, true);
        }
    }

    /** 반 칸 켜 한 칸 — 하단 반 블록(윗면 = y+0.5). 빈 칸에만 놓는다 (경사면·합각을 밀어내지 않는다) */
    private static void roofSlab(World world, int x, int y, int z, RoofStyle rs, boolean cross) {
        if (world.getBlockAt(x, y, z).getType().isAir()) {
            world.getBlockAt(x, y, z).setType(ridgeMat(rs, cross));
        }
    }

    /**
     * v6.4 ⑤ 살창(欌窓) 판정 — **울타리는 면이 아니라 점·선이다** (사용자 정정).
     * v6.3 은 "지붕 옆면을 나무 울타리로"를 삼각면 **전체**를 흑목 울타리 격자로 채우는 것으로 읽었다.
     * 한옥에서 목재가 드러나는 자리는 한정돼 있다 — 서까래 끝(마구리 한 줄), 난간(한 줄), 그리고
     * 합각·박공에 난 **작은 살창 몇 칸**. 면(面)은 널판(풍판)과 회벽이 맡는다.
     *   합각(팔작) — 회벽 면 + 첫 단 한가운데 최대 3칸 살창 (좁은 합각이면 1칸).
     *   박공(맞배·민가) — 널판 면(풍판) + 꼭짓점 아래(폭 ≤3인 상단 1~2단) 한가운데 1칸 통풍구.
     */
    private static boolean lattice(boolean plaster, boolean first, int run, int off) {
        if (plaster) {
            return first && Math.abs(off) <= 1;   // 합각 중앙 살창 (최대 3칸)
        }
        return run <= 3 && off == 0;              // 박공 꼭짓점 밑 통풍구 1칸
    }

    /**
     * v6.2 ③ 기와골 — 지붕면 4칸마다 풀 블록 세로 줄. 계단만 깔린 면은 위에서 보면 매끈한 판때기지만,
     * 골이 서면 빛이 갈라져 기와지붕으로 읽힌다. 좌표 절대값 해시 = 결정론 (건물마다 골 위치가 어긋나 자연스럽다).
     */
    private static boolean groove(int coord) {
        return Math.floorMod(coord, 4) == 0;
    }

    /** 우진각 링 한 겹 — 네 변 (모서리는 풀 블록 추녀마루). solid 면 같은 y 의 두 번째 평평한 단. */
    private static void hipRing(World world, int ax, int az, int bx, int bz, int y,
                                RoofStyle rs, boolean solid) {
        for (int x = ax; x <= bx; x++) {
            roofCell(world, x, y, az, BlockFace.SOUTH, rs, solid || groove(x) || x == ax || x == bx);
            roofCell(world, x, y, bz, BlockFace.NORTH, rs, solid || groove(x) || x == ax || x == bx);
        }
        for (int z = az + 1; z <= bz - 1; z++) {
            roofCell(world, ax, y, z, BlockFace.EAST, rs, solid || groove(z));
            roofCell(world, bx, y, z, BlockFace.WEST, rs, solid || groove(z));
        }
    }

    /**
     * v6.2 ③ 처마 끝단 — 최외곽 링을 반 블록으로 마감한다. 두께가 반으로 얇아지면서 그 밑으로 그림자 선이 생기고,
     * 조감도에서 지붕 덩어리의 윤곽이 살아난다. 지붕 자재(계단·풀 블록)인 칸만 갈아친다 —
     * 합각벽·환기창·박공널은 지붕이 아니라 벽이므로 건드리지 않는다 (안 그러면 다락에 구멍이 난다).
     */
    private static void eaveRim(World world, int x0, int z0, int x1, int z1, int y, RoofStyle rs) {
        for (int x = x0; x <= x1; x++) {   // 북·남 처마 끝 = 북·남 경사면의 결 (TILE)
            rimSlab(world, x, y, z0, rs, false);
            rimSlab(world, x, y, z1, rs, false);
        }
        for (int z = z0 + 1; z <= z1 - 1; z++) {   // 동·서 처마 끝 = 직각 결 (BRICK)
            rimSlab(world, x0, y, z, rs, true);
            rimSlab(world, x1, y, z, rs, true);
        }
    }

    /** 처마 끝 한 칸을 반 블록으로 깎는다. 그 자리에 깔린 자재의 **결 계열을 그대로 이어받는다**. */
    private static void rimSlab(World world, int x, int y, int z, RoofStyle rs, boolean cross) {
        Material now = world.getBlockAt(x, y, z).getType();
        if (now == stairMat(rs, cross) || now == solidMat(rs, cross)) {
            world.getBlockAt(x, y, z).setType(ridgeMat(rs, cross));   // 반 블록(하단) — 얇은 처마 끝
        } else if (now == stairMat(rs, !cross) || now == solidMat(rs, !cross)) {
            world.getBlockAt(x, y, z).setType(ridgeMat(rs, !cross));  // 모서리(추녀마루) 계열 = 반대 결
        }
    }

    /** 지붕 한 칸 — solid 면 풀 블록, 아니면 안쪽으로 오르는 계단 (반 칸 = 완만한 물매의 절반 단) */
    private static void roofCell(World world, int x, int y, int z, BlockFace facing,
                                 RoofStyle rs, boolean solid) {
        roofBlock(world, x, y, z, facing, solid, rs);
    }

    /**
     * v6.5 ① 용마루 — **폭 1~2칸의 선**. 두 경사면이 만난 자리(마지막 적새 켜)보다 딱 한 칸 위에
     * 풀 블록 한 줄을 눕힌다. 그 위에는 아무것도 없다.
     *
     * <p>v6.4 의 용마루는 몸통(y) + 등(y+1) + 치미·덧단(y+2) + 뿔의 네 켜짜리 **구조물**이었다.
     * 그래서 지붕의 최상단 y 평면이 '용마루 선'으로만 채워졌고(그 밑 y 도 마찬가지), 검수의
     * 평지 비율 = 용마루/(용마루+뿔) = 56~78% → "고원". 실루엣을 얻으려고 얹은 것이 실루엣을 죽였다.
     *
     * <p>이제 실루엣은 **경사면이 만든다**: 능선 한 줄이 넓은 경사면(30~38칸) 위에 그어지고,
     * 최상단 평면은 그 선(5~7칸)뿐이다 → 평지 비율 13~16%. 치미·뿔이 필요하다면 그것은 지붕이
     * 아니라 조각의 몫이다 — 이 마을의 격은 지붕의 켜가 아니라 물매가 말한다.
     */
    private static void ridgeLine(World world, int ax, int az, int bx, int bz, int y, RoofStyle rs) {
        // v6.6 ① 용마루의 결은 **능선을 따라 흐른다** — X 능선이면 TILE, Z 능선이면 직각 결(BRICK).
        // (roofShape 의 ridgeX 와 같은 판정: 맞배 단은 짧은 변만 좁히므로 장단 비교가 뒤집히지 않는다)
        Material solid = solidMat(rs, (bx - ax) < (bz - az));
        for (int x = ax; x <= bx; x++) {
            for (int z = az; z <= bz; z++) {
                world.getBlockAt(x, y, z).setType(solid);
            }
        }
    }

    /**
     * v6.4 ⑤ 합각·박공 한 칸 — **사용자 정정: "지붕의 모든 면을 울타리로 바꾸라는 뜻이 아니었다.
     *   한옥 특성상 지붕의 특정 부분만 울타리로 표현하면 좋지 않을까 라는 뜻이었어."**
     * v6.3 은 삼각면 속을 통째로 흑목 울타리 격자로 채웠다 — 지붕 옆구리가 통으로 뚫려 보였다.
     * 이제 **면은 널판·회벽, 울타리는 선과 점**이다:
     *   plaster(팔작 합각) — 회벽(백벽) 면 + 경사변 접합칸은 판자 테두리 + 첫 단 한가운데 살창(lattice).
     *   !plaster(맞배 박공 — 민가·부속채·잡화점) — 흑목 널판 풍판 한 면 + 꼭짓점 밑 통풍구 1칸.
     * 나머지 목재 노출은 그대로 유지한다: 처마 마구리 한 줄(eaveFenceRim = 서까래 끝) · 난간 한 줄
     * (balustradeRing = 객잔 2층·표국 툇마루). 선과 점이면 결이고, 면이면 구멍이다.
     */
    private static void gableBlock(World world, int x, int y, int z, Material gable,
                                   boolean plaster, boolean edge, boolean lattice) {
        Material m = lattice ? Material.DARK_OAK_FENCE
                : plaster ? (edge ? gable : Material.WHITE_TERRACOTTA)
                : gable;
        world.getBlockAt(x, y, z).setType(m);
    }

    /**
     * v6.3 ① 처마 마구리 — 지붕 최외곽 링(반 블록으로 깎인 처마 끝단) **바로 밑**에 흑목 울타리 한 줄.
     * 울타리 기둥의 윗면이 그 위 반 블록에 물려 붙으므로 공중에 뜬 것처럼 보이지 않고,
     * 아래에서 올려다보면 서까래 끝이 줄지어 튀어나온 결이 된다 (슬래브는 그늘, 울타리는 결).
     * 빈 칸에만 놓는다 — 처마 0칸 변(잡화점 동벽)에서 벽 상인방을 파먹지 않게.
     */
    private static void eaveFenceRim(World world, int x0, int z0, int x1, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            eaveFence(world, x, y, z0);
            eaveFence(world, x, y, z1);
        }
        for (int z = z0 + 1; z <= z1 - 1; z++) {
            eaveFence(world, x0, y, z);
            eaveFence(world, x1, y, z);
        }
    }

    private static void eaveFence(World world, int x, int y, int z) {
        if (world.getBlockAt(x, y, z).getType().isAir()) {
            world.getBlockAt(x, y, z).setType(Material.DARK_OAK_FENCE);
        }
    }

    /**
     * 서까래 라인 — 처마 밑 흑목 반 블록 (깊은 처마의 그림자 선). v6.3 ③ 부터 지붕 외곽이 아니라
     * **한 칸 안쪽(벽+1)** 에 깔린다 — 최외곽은 울타리 마구리의 자리다.
     * 빈 칸에만 놓는다: 처마를 0칸으로 붙인 변에서 벽 상인방을 파먹지 않게.
     */
    private static void rafterLine(World world, int x0, int z0, int x1, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            rafter(world, x, y, z0);
            rafter(world, x, y, z1);
        }
        for (int z = z0 + 1; z <= z1 - 1; z++) {
            rafter(world, x0, y, z);
            rafter(world, x1, y, z);
        }
    }

    private static void rafter(World world, int x, int y, int z) {
        if (world.getBlockAt(x, y, z).getType().isAir()) {
            world.getBlockAt(x, y, z).setType(Material.DARK_OAK_SLAB);
        }
    }

    /**
     * v6.3 ① 처마 밑 난간 — 큰 집(객잔 2층·표국 본채)의 위계 표식. 흑목 울타리 한 줄.
     * 받침(반 블록 스커트·돌 기단) 위에 서므로 뜬 울타리가 아니다. 빈 칸에만 놓는다.
     */
    private static void balustradeRing(World world, int x0, int z0, int x1, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            eaveFence(world, x, y, z0);
            eaveFence(world, x, y, z1);
        }
        for (int z = z0 + 1; z <= z1 - 1; z++) {
            eaveFence(world, x0, y, z);
            eaveFence(world, x1, y, z);
        }
    }

    /** 활주(活柱) — 깊은 처마를 받치는 툇기둥. 처마 모서리 밑에 세운다 (그늘에 구조를 준다) */
    private static void eavePosts(World world, int x0, int y0, int z0, int x1, int z1, int top) {
        for (int x : new int[]{x0, x1}) {
            for (int z : new int[]{z0, z1}) {
                for (int y = y0 + 1; y <= top; y++) {
                    if (world.getBlockAt(x, y, z).getType().isAir()) {
                        world.getBlockAt(x, y, z).setType(Material.SPRUCE_FENCE);
                    }
                }
            }
        }
    }

    // ─── 철산표국 (v6 ① — 등록 장소 pyoguk / 국주 진철산) ───
    //
    // 부지: 남골목(z+21) 동측 여유 부지. 돌담 마당 21x17 = x[cx+30..cx+50] · z[cz+38..cz+54].
    //   마당 대문(북 3칸 x[cx+39..cx+41]) → 마당 → 본채 기단 계단 → 본채 15x11 북향 문.
    //   본채  x[cx+31..cx+45] · z[cz+43..cz+53] — 돌 벽돌 1단 기단 위 (마을에서 유일하게 반 층 올라선 집).
    //   마구간 5x4 x[cx+31..cx+35]·z[cz+39..cz+42] / 짐수레 4x3 x[cx+45..cx+48]·z[cz+39..cz+41].
    // 좌표는 전부 상수 — 난수 없음. 기존 앵커·구역·NPC는 손대지 않고 "표국"만 추가한다.

    // v6.1 ④ — 본채 처마가 전 방향 2칸으로 깊어지면서(x+29..x+47 · z+41..z+55) 마당 담을 뚫는다.
    // 담을 밀어 이격을 확보한다: 서 30→28, 동 50→52, 북 38→36, 남 54→57. 앵커·NPC 좌표는 불변.
    private static final int PY_X0 = 28;    // 마당 담 서변
    private static final int PY_X1 = 52;    // 마당 담 동변
    private static final int PY_Z0 = 36;    // 마당 담 북변 (대문)
    private static final int PY_Z1 = 57;    // 마당 담 남변
    private static final int PY_HX0 = 31;   // 본채 서벽
    private static final int PY_HX1 = 45;   // 본채 동벽
    private static final int PY_HZ0 = 43;   // 본채 북벽 (정면)
    private static final int PY_HZ1 = 53;   // 본채 남벽
    private static final int PY_DOOR = 38;  // 본채 문 x (마당 대문 x39~41 과 한 축)

    /** 철산표국 — 관아급 계열의 참조 구현: 돌 기단·노출 기둥·팔작·정면 처마 2칸 + 돌담 마당 */
    private static Location pyoguk(World world, int cx, int cy, int cz) {
        int x0 = cx + PY_X0, x1 = cx + PY_X1, z0 = cz + PY_Z0, z1 = cz + PY_Z1;
        // 마당 바닥 — 흙길 + 자갈 혼합 (결정론 격자: (x+z)%5==0 만 자갈)
        for (int x = x0 + 1; x <= x1 - 1; x++) {
            for (int z = z0 + 1; z <= z1 - 1; z++) {
                world.getBlockAt(x, cy, z).setType(
                        Math.floorMod(x + z, 5) == 0 ? Material.GRAVEL : Material.DIRT_PATH);
            }
        }
        // 돌담 — 조약돌 하단 + 돌 벽돌 상단 + 반 블록 갓돌 (표국은 작은 성이다)
        for (int x = x0; x <= x1; x++) {
            pyogukWallColumn(world, x, cy, z0);
            pyogukWallColumn(world, x, cy, z1);
        }
        for (int z = z0 + 1; z <= z1 - 1; z++) {
            pyogukWallColumn(world, x0, cy, z);
            pyogukWallColumn(world, x1, cy, z);
        }
        pyogukGate(world, cx, cy, cz);
        pyogukHall(world, cx, cy, cz);
        pyogukYard(world, cx, cy, cz);
        return loc(world, cx + PY_DOOR, cy + 2, cz + 48);   // 앵커 = 본채 중앙 (기단 위 = 지면보다 한 칸 높다)
    }

    private static void pyogukWallColumn(World world, int x, int cy, int z) {
        world.getBlockAt(x, cy + 1, z).setType(Material.COBBLESTONE);
        world.getBlockAt(x, cy + 2, z).setType(Material.STONE_BRICKS);
        world.getBlockAt(x, cy + 3, z).setType(Material.STONE_BRICK_SLAB);   // 갓돌
    }

    /** 마당 대문 3칸 + 인방 + 흑와 미니 처마 + 현수 등롱 쌍 — 마을 대문의 축소판 */
    private static void pyogukGate(World world, int cx, int cy, int cz) {
        int gz = cz + PY_Z0;
        for (int x = cx + 39; x <= cx + 41; x++) {   // 개구 3칸
            for (int y = cy + 1; y <= cy + 3; y++) {
                world.getBlockAt(x, y, gz).setType(Material.AIR);
            }
            world.getBlockAt(x, cy, gz).setType(Material.DIRT_PATH);
        }
        for (int side : new int[]{cx + 38, cx + 42}) {   // 문주
            for (int y = cy + 1; y <= cy + 4; y++) {
                world.getBlockAt(side, y, gz).setType(Material.DARK_OAK_LOG);
            }
        }
        for (int x = cx + 38; x <= cx + 42; x++) {
            world.getBlockAt(x, cy + 5, gz).setType(Material.DARK_OAK_PLANKS);        // 인방
        }
        for (int x = cx + 37; x <= cx + 43; x++) {
            world.getBlockAt(x, cy + 6, gz).setType(Material.DEEPSLATE_TILE_SLAB);    // 미니 처마
        }
        hangingLantern(world, cx + 39, cy + 4, gz);
        hangingLantern(world, cx + 41, cy + 4, gz);
        hangingSign(world, cx + 40, cy + 4, gz, BlockFace.NORTH, "철산표국", "표행 — 신용이 곧 물건");
        for (int x = cx + 39; x <= cx + 41; x++) {   // 대문 안쪽 3칸 소로 (남골목 갈래는 doorPaths 몫)
            for (int z = gz + 1; z <= gz + 4; z++) {
                world.getBlockAt(x, cy, z).setType(Material.DIRT_PATH);
            }
        }
    }

    /**
     * 본채 15x11 — 돌 벽돌 1단 기단 위. 4우 + 정면 보조 기둥 2주를 껍질 벗긴 흑목으로 노출(관아급 표식),
     * 백벽 + 격자창, 팔작지붕(하부 링 2층 + 합각), 정면 처마 2칸(서까래 반 블록 + 활주 울타리 2).
     */
    private static void pyogukHall(World world, int cx, int cy, int cz) {
        int x0 = cx + PY_HX0, x1 = cx + PY_HX1, z0 = cz + PY_HZ0, z1 = cz + PY_HZ1;
        for (int x = x0; x <= x1 + 1; x++) {         // 기단 — 본채 + 동측 노출 스커트 1칸
            for (int z = z0; z <= z1; z++) {
                world.getBlockAt(x, cy + 1, z).setType(Material.STONE_BRICKS);
            }
        }
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
                boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                boolean post = z == z0 && (x == cx + 35 || x == cx + 41);   // 정면 보조 기둥 2주
                boolean rim = x == x0 + 1 || x == x1 - 1 || z == z0 + 1 || z == z1 - 1;
                if (!wall) {
                    world.getBlockAt(x, cy + 1, z).setType(
                            rim ? Material.DARK_OAK_PLANKS : Material.SPRUCE_PLANKS);   // 마루 + 귀틀
                }
                for (int y = cy + 2; y <= cy + 5; y++) {
                    if (corner || post) {
                        world.getBlockAt(x, y, z).setType(Material.STRIPPED_DARK_OAK_LOG);   // 노출 기둥
                    } else if (!wall) {
                        world.getBlockAt(x, y, z).setType(Material.AIR);
                    } else if (y == cy + 5) {
                        world.getBlockAt(x, y, z).setType(Material.DARK_OAK_PLANKS);         // 상인방
                    } else {
                        world.getBlockAt(x, y, z).setType(Material.WHITE_TERRACOTTA);        // 백벽 (창은 아래에서 뚫는다)
                    }
                }
            }
        }
        // v6.9 ③ 표국의 창 — 관아급. 본채는 기단 위(바닥 cy+1)라 검수 probeY = cy+4 다.
        //   창 켜 cy+3..cy+4 → probeY 를 물므로 **세살창(blocking)** 만 쓴다. 정면은 노출 기둥
        //   (x+35 · x+41)과 문(x+38) 사이 두 짝, 측면은 큰 창 한 짝씩, 뒷벽(남)은 병장기 시렁의 벽 → 봉창 둘.
        windowUnit(world, x0, cy + 1, z0, x1, z1, 0, cx + 36, cx + 37, cy + 3, cy + 4, Pane.SASH);
        windowUnit(world, x0, cy + 1, z0, x1, z1, 0, cx + 39, cx + 40, cy + 3, cy + 4, Pane.SASH);
        windowUnit(world, x0, cy + 1, z0, x1, z1, 2, z0 + 3, z0 + 5, cy + 3, cy + 4, Pane.SASH);
        windowUnit(world, x0, cy + 1, z0, x1, z1, 3, z0 + 3, z0 + 5, cy + 3, cy + 4, Pane.SASH);
        windowUnit(world, x0, cy + 1, z0, x1, z1, 1, x0 + 2, x0 + 2, cy + 3, cy + 3, Pane.PAPER);
        windowUnit(world, x0, cy + 1, z0, x1, z1, 1, x1 - 2, x1 - 2, cy + 3, cy + 3, Pane.PAPER);
        world.getBlockAt(cx + PY_DOOR, cy + 2, z0).setType(Material.AIR);   // 북향 문 1칸
        world.getBlockAt(cx + PY_DOOR, cy + 3, z0).setType(Material.AIR);
        for (int x = cx + 37; x <= cx + 39; x++) {   // 기단 진입 계단 3칸 폭
            stair(world, x, cy + 1, z0 - 1, Material.STONE_BRICK_STAIRS, BlockFace.SOUTH);
        }
        roofShape(world, x0 - 2, z0 - 2, x1 + 2, z1 + 2, cy + 6,   // 처마 2칸 (지붕 최외곽 = 벽+2)
                RoofStyle.TILE, Material.DARK_OAK_PLANKS, 99, true);
        for (int px : new int[]{cx + 36, cx + 44}) {   // 활주(活柱) 2주 — 정면 깊은 처마를 받는다 (마당 바닥에서)
            for (int y = cy + 1; y <= cy + 5; y++) {
                world.getBlockAt(px, y, z0 - 2).setType(Material.SPRUCE_FENCE);
            }
        }
        eavePosts(world, x0 - 1, cy, z0 - 1, x1 + 1, z1 + 1, cy + 5);   // 네 귀 툇기둥 — 마당 바닥에서 처마까지
        // v6.3 ① 툇마루 난간 — 기단 동측 노출 스커트(x1+1) 위 흑목 울타리. 처마(x1+2) 밑에 든다.
        // 마당 대문 동선(x+39..41)·짐수레(x+45..48, z+37..39)·표기 장대(x+50)와 겹치지 않는다.
        for (int z = z0 + 1; z <= z1 - 1; z++) {
            eaveFence(world, x1 + 1, cy + 2, z);
        }
        tieBeams(world, x0, cy + 5, z0, x1, z1);                        // 대들보 2 (z+46 · z+50)
        // v7.0 ② 부촌 필지 승격 — 표국은 이미 돌 기단·노출 기둥을 가진 '관아급'이다. 굽도리와 전돌만 더한다.
        //   굽도리는 한 켜(cy+2)뿐이다: 벽이 cy+2..cy+5 이고 창 켜가 cy+3..cy+4 이므로 그 밑이 한 켜밖에 없다.
        //   전돌은 POLISHED_ANDESITE — stone_bricks 를 쓰면 PATH 집합이라 실내가 '길'로 세어진다.
        kerb(world, x0, cy + 1, z0, x1, z1, 1);
        tiledFloor(world, x0, cy + 1, z0, x1, z1);
        pyogukHallInterior(world, cx, cy, cz);
        // v6.8 ① — 본채는 기단 위라 바닥이 cy+2 다. 격자창 켜가 cy+3 이므로 벽등은 cy+4 (바닥+2).
        roomLights(world, x0, cy + 4, z0, x1, z1);
        hangingLantern(world, cx + PY_DOOR, cy + 4, cz + 50);           // 남쪽 대들보 현수등 (병장기 시렁 앞)
    }

    /**
     * 표국 본채 실내 (x+32..x+44 · z+44..z+52, 바닥 cy+1) — v6.1 ③ 공간 문법.
     * 시선 축: 북향 문(x+38)으로 들어와 정면 남벽(z+52)에 **병장기 시렁** — 진철산의 오호단문창 자리.
     *   표국이 무엇으로 먹고사는 집인지 문턱에서 읽힌다. 장부·표물은 축을 비껴 좌우로.
     * 벽면 3분할 — 하단 창걸이 계단 / 중단 시렁 판 / 상단 시렁 위 등.
     * 밀도 등급 = 중간 (무가는 정돈되나 살림이 있다). 국주 앵커(x+38,z+48)와 문 동선은 비운다.
     */
    private static void pyogukHallInterior(World world, int cx, int cy, int cz) {
        // 시선 축 — 남벽 병장기 시렁 5칸. v7.1(1.21.11): 중단이 **진짜 시렁**이 됐다.
        //   진철산의 오호단문창이 실제로 걸린다 — 표국이 무엇으로 먹고사는 집인지 문턱에서 읽힌다.
        //   씨앗은 좌표 해시다 (조성기 난수 금지 — 같은 마을이면 같은 병기가 걸린다).
        for (int x = cx + 36; x <= cx + 40; x++) {
            stair(world, x, cy + 2, cz + 52, Material.SPRUCE_STAIRS, BlockFace.NORTH);   // 하단 = 창걸이
            world.getBlockAt(x, cy + 3, cz + 52).setType(Material.DARK_OAK_SLAB);        // 중단 = 시렁 판
        }
        long seed = Math.floorMod(31L * cx + cz, 1_000_003L);
        shelf(world, cx + 37, cy + 3, cz + 52, Material.DARK_OAK_SHELF, BlockFace.NORTH,
                Weapons.makeSeeded(Weapons.Series.창, Weapons.Grade.정련, seed),          // 국주의 창
                Weapons.makeSeeded(Weapons.Series.도, Weapons.Grade.범철, seed + 1),      // 표사의 도
                Weapons.makeSeeded(Weapons.Series.검, Weapons.Grade.범철, seed + 2));
        shelf(world, cx + 39, cy + 3, cz + 52, Material.DARK_OAK_SHELF, BlockFace.NORTH,
                Weapons.makeSeeded(Weapons.Series.월아산, Weapons.Grade.범철, seed + 3),
                null,                                                                     // 빈 칸 = 지금 표행 나간 병기
                Weapons.makeSeeded(Weapons.Series.단검, Weapons.Grade.범철, seed + 4));
        // 상단 = 시렁 위 조명. v7.1 — 등롱 → 벽등: 시렁 둘이 남벽 안줄의 소품 예산(3점)에 들어왔다.
        //   병장기가 걸린 벽에서 밀려나야 할 것은 병기가 아니라 등이다. 벽등은 예산 0이고 빛은 같다.
        wallTorch(world, cx + 36, cy + 4, cz + 52, BlockFace.NORTH);
        wallTorch(world, cx + 40, cy + 4, cz + 52, BlockFace.NORTH);
        placeWallSign(world, cx + 38, cy + 4, cz + 52, BlockFace.NORTH,                  // 남벽에 붙는 현판
                "철산표국", "표행 — 신용이 곧 물건");
        // 서벽(x+32) — 표물 궤 3점 (여백 규칙)
        world.getBlockAt(cx + 32, cy + 2, cz + 51).setType(Material.CHEST);
        world.getBlockAt(cx + 32, cy + 2, cz + 50).setType(Material.BARREL);
        world.getBlockAt(cx + 32, cy + 2, cz + 45).setType(Material.LANTERN);            // 하단 조명
        // 동벽(x+44) — 표행 장부·문서철 2점 + 중단 등
        world.getBlockAt(cx + 44, cy + 2, cz + 46).setType(Material.LECTERN);
        bookshelf(world, cx + 44, cy + 2, cz + 45, BlockFace.WEST, 4);
        world.getBlockAt(cx + 44, cy + 3, cz + 45).setType(Material.LANTERN);
        placeWallSign(world, cx + 43, cy + 4, cz + 44, BlockFace.SOUTH,                  // 북벽(백벽 단)에 붙는다
                "북로 표행", "반년 대기 — 호위 구함");
        chainLantern(world, cx + PY_DOOR, cy + 4, cz + 46, 1);                           // 상단 — 대들보(z+46)에 매단 등롱
        for (int z = cz + 45; z <= cz + 47; z++) {   // 문 → 국주 자리 깔개 (사람이 걷는 자리)
            world.getBlockAt(cx + PY_DOOR, cy + 2, z).setType(Material.RED_CARPET);
        }
    }

    /**
     * 마당 — 마구간 부속채(맞배 6x3) · 짐수레 자리 4x3 · 표기 장대 · 표사들 모닥불.
     * v6.1 ④ — 본채 처마가 z+41 까지 나오므로 부속채·수레를 z+37..+39 로 물렸다 (처마 겹침 0).
     * 부속채 처마는 1칸 (위계 — 깊은 처마는 본채의 것이다).
     */
    private static void pyogukYard(World world, int cx, int cy, int cz) {
        // 마구간 6x3 — 남면(마당 쪽) 개방, 맞배 부속채
        for (int x = cx + 31; x <= cx + 36; x++) {
            for (int z = cz + 37; z <= cz + 39; z++) {
                boolean wall = (z == cz + 37) || ((x == cx + 31 || x == cx + 36) && z <= cz + 38);
                if (!wall) {
                    continue;
                }
                for (int y = cy + 1; y <= cy + 3; y++) {
                    world.getBlockAt(x, y, z).setType(
                            y == cy + 3 ? Material.DARK_OAK_PLANKS : Material.LIGHT_GRAY_TERRACOTTA);
                }
            }
        }
        roofShape(world, cx + 30, cz + 36, cx + 37, cz + 40, cy + 4,
                RoofStyle.SHINGLE, Material.DARK_OAK_PLANKS, 0);
        world.getBlockAt(cx + 33, cy + 1, cz + 39).setType(Material.SPRUCE_FENCE);   // 칸막이
        world.getBlockAt(cx + 33, cy + 2, cz + 39).setType(Material.SPRUCE_FENCE);
        world.getBlockAt(cx + 32, cy + 1, cz + 38).setType(Material.HAY_BLOCK);
        world.getBlockAt(cx + 35, cy + 1, cz + 38).setType(Material.HAY_BLOCK);
        world.getBlockAt(cx + 34, cy + 1, cz + 39).setType(Material.CAULDRON);       // 물통
        world.getBlockAt(cx + 32, cy + 2, cz + 38).setType(Material.LANTERN);
        // 짐수레 자리 4x3 — 참나무 판자 데크 + 통 3 + 건초 2 (실어 둔 표물)
        for (int x = cx + 45; x <= cx + 48; x++) {
            for (int z = cz + 37; z <= cz + 39; z++) {
                world.getBlockAt(x, cy, z).setType(Material.OAK_PLANKS);
            }
        }
        world.getBlockAt(cx + 45, cy + 1, cz + 37).setType(Material.BARREL);
        world.getBlockAt(cx + 46, cy + 1, cz + 37).setType(Material.BARREL);
        world.getBlockAt(cx + 48, cy + 1, cz + 38).setType(Material.BARREL);
        world.getBlockAt(cx + 45, cy + 1, cz + 39).setType(Material.HAY_BLOCK);
        world.getBlockAt(cx + 46, cy + 1, cz + 39).setType(Material.HAY_BLOCK);
        // 표기(標旗) — 울타리 장대 3단 + 매다는 표지판, 밤엔 장대 밑 랜턴 (신용 장사의 표식)
        for (int y = cy + 1; y <= cy + 3; y++) {
            world.getBlockAt(cx + 50, y, cz + 45).setType(Material.SPRUCE_FENCE);
        }
        hangingSign(world, cx + 50, cy + 3, cz + 46, BlockFace.WEST, "철산표국", "표사 모집 — 국주 진철산");
        world.getBlockAt(cx + 49, cy + 1, cz + 45).setType(Material.LANTERN);
        // v7.0 ② 부촌 필지 — 마당 석등 2기 (등롱 기둥이 아니라 돌기둥이다: 표국은 '보여 주려고 지은' 집이다)
        stoneLamp(world, cx + 29, cy, cz + 42);
        stoneLamp(world, cx + 51, cy, cz + 42);
        // 표사들의 자리 — 모닥불 + 둘러앉는 통나무 걸상 3 (곽진이 들르는 자리)
        hearth(world, cx + 50, cy, cz + 50);
        world.getBlockAt(cx + 49, cy + 1, cz + 50).setType(Material.OAK_LOG);
        world.getBlockAt(cx + 50, cy + 1, cz + 49).setType(Material.OAK_LOG);
        world.getBlockAt(cx + 50, cy + 1, cz + 51).setType(Material.OAK_LOG);
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  v7.0 ① 청하현 관아 (county_office — 현령 조문원 · 포두 박호)
    //
    //  등록부(config/regions/cheongha_hyeon.yml · config/world_map.yml)에 이름이 있는데 실물이 없던
    //  **청하현에서 유일하게 무대가 빈 등록 장소**다. 세력 정치(관무불가침)가 배선된 지금,
    //  "현령을 죽이면 강호가 그를 버린다"는 규칙은 **현령이 앉아 있을 자리**가 있어야 성립한다.
    //
    //  【관은 무림이 아니다】 표국·객잔·전장이 '무림의 격'(팔작·깊은 처마·목골)이라면, 관아는 **질서와 법**이다:
    //    ㉮ 삼문(三門) — 정문 3칸 + 협문 2. 사람은 협문으로 들고, 정문은 현령과 형(刑)이 지난다.
    //    ㉯ 정청(正廳) 15x13 — 마당의 축 정중앙. 문을 열면 곧장 현령의 단(壇)이 보인다. 축 위엔 그것뿐.
    //    ㉰ 형방(刑房) 6x4 서편 · 옥(獄) 6x4 동편 — 마당을 사이에 두고 마주 본다 (좌형 우옥의 대칭 = 위엄).
    //    ㉱ 부촌 필지(village_tiers.md 4.3) — 돌 기단·굽도리 2단·전돌 마당·석등. 관은 보여 주려고 짓는다.
    //
    //  【검수 정합 — 왜 이 좌표인가】 (전부 실좌표로 역산했다)
    //    · 마당 담 x[-31..-9] · z[+33..+57] / 정청 벽 x[-27..-13] · z[+41..+53] / 지붕 x[-29..-11] · z[+39..+55]
    //    · 담 기둥 최상단은 **STONE_BRICK_SLAB** 이다 — isOccluding()=false 라 검수의 벽 레이캐스트
    //      (probeY = cy+3)가 담을 '정청의 벽'으로 오인하지 않는다. 담을 풀 블록으로 3단 쌓으면 벽 박스가
    //      담까지 밀려 **처마 내밀기가 음수**가 된다 (표국이 같은 이유로 갓돌을 반 블록으로 쓴다).
    //    · 앵커(= 검수의 자) = 정청 실내 중앙 (cx-20, cz+47). 그 x행·z열에는 형방·옥·삼문의 지붕이
    //      한 칸도 걸리지 않는다 → 물매 단면이 제 처마(x-29)에서 시작한다: h/nHip = 5/7 = 0.71.
    //    · 처마 실측: 서 3 (형방 지붕이 x-30 까지 나온다) · 동 2 · 북 8 (삼문 처마 z+33) · 남 2 → 최소 2 ✅
    //    · 민가 #6(ㄱ자, 지붕 x[-45..-30]·z[+20..+32])은 정청 지붕 스캔 상자(벽±8 = z≥33)의 **밖**이다 —
    //      한 칸이라도 걸리면 v6.3 ③ 의 "이웃 지붕이 내 물매를 읽는" 버그가 재발한다.
    //    · 마당 바닥은 **전돌(POLISHED_ANDESITE)** 이고 축선만 흙길이다. 마당 전체를 흙길로 깔면
    //      길 표본이 500칸 늘어 야간 암흑률이 폭발한다 — 전돌은 PATH 집합 밖이라 표본을 만들지 않는다.
    //      대신 축선(3칸)만 길로 두고 석등 4기 + 삼문·정청 현수 등롱으로 맨해튼 8 안에 가둔다.
    // ══════════════════════════════════════════════════════════════════════════════════════════

    private static final int CO_X0 = -31;   // 마당 담 서변
    private static final int CO_X1 = -9;    // 마당 담 동변
    private static final int CO_Z0 = 33;    // 마당 담 북변 (삼문)
    private static final int CO_Z1 = 57;    // 마당 담 남변
    private static final int CO_HX0 = -27;  // 정청 서벽
    private static final int CO_HZ0 = 41;   // 정청 북벽 (정면)
    private static final int CO_DOOR = -20; // 정청 문 x (= 삼문 정문·진입 소로와 한 축)

    /** 청하현 관아 — 삼문·정청·형방·옥. 앵커 "관아" 를 돌려준다 (기존 7키는 불변, 추가만) */
    private static Location countyOffice(World world, int cx, int cy, int cz) {
        int x0 = cx + CO_X0, x1 = cx + CO_X1, z0 = cz + CO_Z0, z1 = cz + CO_Z1;
        // 마당 — 전돌(부촌 필지). 흙이 20% 섞여 관청 마당이 박물관 바닥이 되지 않게 (좌표 해시 = 난수 0)
        for (int x = x0 + 1; x <= x1 - 1; x++) {
            for (int z = z0 + 1; z <= z1 - 1; z++) {
                world.getBlockAt(x, cy, z).setType(
                        hash(x, z, 10) < 2 ? Material.COARSE_DIRT : Material.POLISHED_ANDESITE);
            }
        }
        for (int x = x0; x <= x1; x++) {   // 담 — 표국과 같은 문법(조약돌·돌 벽돌·반 블록 갓돌)
            coWallColumn(world, x, cy, z0);
            coWallColumn(world, x, cy, z1);
        }
        for (int z = z0 + 1; z <= z1 - 1; z++) {
            coWallColumn(world, x0, cy, z);
            coWallColumn(world, x1, cy, z);
        }
        coWings(world, cx, cy, cz);      // 형방·옥 (삼문보다 먼저 — 삼문이 제 문주를 지킨다)
        coGate(world, cx, cy, cz);       // 삼문
        coHall(world, cx, cy, cz);       // 정청
        // 축선 — 삼문(z+33) → 정청 문(z+41). 마당에서 유일한 '길'이다 (검수 ①-b 문 앞 소로의 근거)
        for (int x = cx - 21; x <= cx - 19; x++) {
            for (int z = z0; z <= cz + 40; z++) {
                world.getBlockAt(x, cy, z).setType(Material.DIRT_PATH);
                world.getBlockAt(x, cy - 1, z).setType(Material.COARSE_DIRT);
            }
        }
        // 석등 4기 (부촌 필지). 앞의 둘은 축선 한복판(z+36~+38)을 맨해튼 8 안에 가두는 자리다 —
        // 삼문 현수 등롱(z+33)과 정청 처마 등롱(z+39) 사이의 골이 여기서 메워진다 (야간 암흑 0칸).
        stoneLamp(world, cx - 23, cy, cz + 38);
        stoneLamp(world, cx - 18, cy, cz + 38);
        stoneLamp(world, cx - 30, cy, cz + 48);
        stoneLamp(world, cx - 10, cy, cz + 48);
        return loc(world, cx + CO_DOOR, cy + 1, cz + 47);   // 앵커 = 정청 실내 중앙
    }

    /** 관아 담 한 칸 — 갓돌은 반드시 반 블록 (검수의 벽 레이캐스트가 담을 벽으로 읽지 않게) */
    private static void coWallColumn(World world, int x, int cy, int z) {
        world.getBlockAt(x, cy + 1, z).setType(
                Math.floorMod(x * 5 + z * 3, 11) == 0 ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE);
        world.getBlockAt(x, cy + 2, z).setType(Material.STONE_BRICKS);
        world.getBlockAt(x, cy + 3, z).setType(Material.STONE_BRICK_SLAB);
    }

    /**
     * 삼문(三門) — 정문 3칸(x-21..-19, 축선) + 협문 2(x-27 · x-13, 정청 벽선과 한 축).
     * 문주는 흑목 통기둥, 인방 위에 흑와 미니 처마. 현판 "청하현 관아" + 현수 등롱 쌍.
     */
    private static void coGate(World world, int cx, int cy, int cz) {
        int gz = cz + CO_Z0;
        for (int x = cx - 28; x <= cx - 12; x++) {   // 문간채 벽면 (담이 아니라 건물이다)
            for (int y = cy + 1; y <= cy + 4; y++) {
                world.getBlockAt(x, y, gz).setType(Material.WHITE_TERRACOTTA);
            }
            world.getBlockAt(x, cy + 5, gz).setType(Material.DARK_OAK_PLANKS);   // 인방
        }
        for (int x : new int[]{cx - 28, cx - 26, cx - 22, cx - 18, cx - 14, cx - 12}) {
            for (int y = cy + 1; y <= cy + 4; y++) {
                world.getBlockAt(x, y, gz).setType(Material.DARK_OAK_LOG);       // 문주 6주
            }
        }
        for (int x = cx - 21; x <= cx - 19; x++) {   // 정문 개구 3칸
            for (int y = cy + 1; y <= cy + 4; y++) {
                world.getBlockAt(x, y, gz).setType(Material.AIR);
            }
        }
        for (int x : new int[]{cx - 27, cx - 13}) {  // 협문 개구 1칸씩
            for (int y = cy + 1; y <= cy + 3; y++) {
                world.getBlockAt(x, y, gz).setType(Material.AIR);
            }
            world.getBlockAt(x, cy, gz).setType(Material.DIRT_PATH);
        }
        for (int x = cx - 29; x <= cx - 11; x++) {   // 미니 처마 (흑와 반 블록, 2칸 내밈) — 관아의 얼굴
            world.getBlockAt(x, cy + 6, gz).setType(Material.DEEPSLATE_TILE_SLAB);
            world.getBlockAt(x, cy + 6, gz - 1).setType(Material.DEEPSLATE_TILE_SLAB);   // 현판이 매달릴 처마
        }
        chainLantern(world, cx - 22, cy + 4, gz, 1);
        chainLantern(world, cx - 18, cy + 4, gz, 1);
        hangingSign(world, cx - 20, cy + 5, gz - 1, BlockFace.NORTH,
                "청하현 관아", "송사·신고·수배 — 관은 무림이 아니다");
        // x-25 인 이유: x-24·x-18 은 진입 소로 등롱 열(DOOR_LAMPS)이다. 그 칸에 팻말을 붙이면
        // 뒤에 도는 streetLanterns 가 등롱을 못 세워 소로 끝이 캄캄해진다 (v6.8 게시대와 같은 함정).
        placeSign(world, cx - 25, cy + 1, gz - 1, BlockFace.NORTH,
                "현령 조문원", "포두 박호 — 관무불가침");
    }

    /**
     * 정청(正廳) 15x13 — 관아급 부촌 필지. 돌 기단(반 칸 댓돌) + 굽도리 2단 + 전돌 바닥 + 팔작·처마 2칸.
     * 시선 축: 삼문 → 마당 → 북향 문(x-20) → 정면 남벽의 **현령의 단(壇)**. 축 위엔 그것 하나뿐이다.
     * 벽면 소품 ≤3 (남 3: 문서궤·대장·현판 / 북 2 / 서 3 / 동 3) · 바닥 여백 ≈ 78%.
     */
    private static void coHall(World world, int cx, int cy, int cz) {
        int x0 = cx + CO_HX0, z0 = cz + CO_HZ0;
        int x1 = x0 + 14, z1 = z0 + 12;
        shell(world, x0, cy, z0, 15, 13, 5, true,
                WallStyle.PLASTER_WHITE, RoofStyle.TILE, true, WindowStyle.COURT);
        kerb(world, x0, cy, z0, x1, z1, 2);              // 굽도리 2단 (부촌 필지)
        tiledFloor(world, x0, cy, z0, x1, z1);           // 전돌 바닥 (polished_andesite — PATH 집합 밖)
        for (int x = x0 - 1; x <= x1 + 1; x++) {         // 돌 기단 — 반 칸 댓돌 한 바퀴
            if (x < cx - 21 || x > cx - 19) {            // 축선(진입 3칸)은 비운다 — 길 위에 댓돌을 얹지 않는다
                plinth(world, x, cy + 1, z0 - 1);
            }
            plinth(world, x, cy + 1, z1 + 1);
        }
        for (int z = z0; z <= z1; z++) {
            plinth(world, x0 - 1, cy + 1, z);
            plinth(world, x1 + 1, cy + 1, z);
        }
        tieBeams(world, x0, cy + 5, z0, x1, z1);         // 대들보 2 (z+44 · z+50)
        hangingLantern(world, cx + CO_DOOR, cy + 4, z0 + 3);
        hangingLantern(world, cx + CO_DOOR, cy + 4, z1 - 3);
        chainLantern(world, cx - 22, cy + 4, z0 - 2, 1);   // 문 양옆 처마 등롱 — 축선 끝(z+39~+40)을 밝힌다
        chainLantern(world, cx - 18, cy + 4, z0 - 2, 1);
        hangingSign(world, cx + CO_DOOR, cy + 5, z0 - 2, BlockFace.NORTH, "正廳", "청하현령 조문원");
        for (int px : new int[]{cx - 23, cx - 17}) {     // 정면 활주 2주 (깊은 처마의 그늘에 구조를)
            for (int y = cy + 1; y <= cy + 4; y++) {
                world.getBlockAt(px, y, z0 - 2).setType(Material.SPRUCE_FENCE);
            }
        }
        // ── 시선 축: 남벽 앞 현령의 단 (전돌 1단 + 교의). 방 한복판(z+44..+48)은 비운다 — 송사가 서는 자리.
        for (int x = cx - 22; x <= cx - 18; x++) {
            for (int z = cz + 50; z <= cz + 52; z++) {
                world.getBlockAt(x, cy + 1, z).setType(Material.POLISHED_ANDESITE);
            }
        }
        stair(world, cx - 20, cy + 2, cz + 51, Material.DARK_OAK_STAIRS, BlockFace.NORTH);   // 교의(交椅)
        world.getBlockAt(cx - 21, cy + 2, cz + 51).setType(Material.DARK_OAK_FENCE);         // 단 난간
        world.getBlockAt(cx - 19, cy + 2, cz + 51).setType(Material.DARK_OAK_FENCE);
        world.getBlockAt(cx - 20, cy + 2, cz + 50).setType(Material.LIGHT_GRAY_CARPET);      // 단 앞 자리
        // ── 남벽(z+52 안줄) 3점: 문서 궤 · 송사 대장 · 현판
        world.getBlockAt(cx - 25, cy + 1, cz + 52).setType(Material.CHEST);
        world.getBlockAt(cx - 15, cy + 1, cz + 52).setType(Material.LECTERN);
        placeWallSign(world, cx - 19, cy + 3, cz + 52, BlockFace.NORTH, "淸河縣", "법은 무림의 밖에 있다");
        // ── 북벽(z+42) 2점 · 서벽(x-26) 3점 · 동벽(x-14) 3점 (여백 규칙 — 벽 한 면 3점 이하)
        world.getBlockAt(cx - 25, cy + 1, cz + 42).setType(Material.BARREL);
        world.getBlockAt(cx - 15, cy + 1, cz + 42).setType(Material.DECORATED_POT);
        bookshelf(world, cx - 26, cy + 1, cz + 46, BlockFace.EAST, 5);       // 서가 — 율(律)과 장부
        bookshelf(world, cx - 26, cy + 1, cz + 47, BlockFace.EAST, 4);
        world.getBlockAt(cx - 26, cy + 2, cz + 50).setType(Material.LANTERN);
        bookshelf(world, cx - 14, cy + 1, cz + 46, BlockFace.WEST, 3);       // 수배·호적 문서철
        bookshelf(world, cx - 14, cy + 1, cz + 47, BlockFace.WEST, 2);
        world.getBlockAt(cx - 14, cy + 1, cz + 50).setType(Material.DECORATED_POT);
        for (int z = cz + 42; z <= cz + 49; z++) {   // 문 → 단 동선 (무채색 — 관아에 붉은색은 없다)
            world.getBlockAt(cx + CO_DOOR, cy + 1, z).setType(Material.LIGHT_GRAY_CARPET);
        }
    }

    /**
     * 형방(刑房, 서) · 옥(獄, 동) — 마당을 사이에 두고 마주 본다. 6x4 맞배 부속채(처마 1칸 = 위계).
     * 옥의 창은 철창이고, 옥의 문은 안에서 열리지 않는다 (철창 한 겹을 문간에 세웠다).
     */
    private static void coWings(World world, int cx, int cy, int cz) {
        // 형방 — 수배 대장·포박 도구. 문은 남향(마당을 본다)
        shell(world, cx - 29, cy, cz + 35, 6, 4, 3, false,
                WallStyle.PLASTER_WHITE, RoofStyle.TILE, false, 1, WindowStyle.NONE);
        kerb(world, cx - 29, cy, cz + 35, cx - 24, cz + 38, 1);
        world.getBlockAt(cx - 28, cy + 1, cz + 36).setType(Material.LECTERN);      // 수배 대장
        world.getBlockAt(cx - 25, cy + 1, cz + 36).setType(Material.BARREL);
        world.getBlockAt(cx - 25, cy + 2, cz + 36).setType(Material.LANTERN);
        placeSign(world, cx - 27, cy + 1, cz + 39, BlockFace.SOUTH, "형방", "포두 박호 — 수배·포박");
        // 옥 — 철창. 안에 짚 한 자리와 물그릇뿐이다
        shell(world, cx - 17, cy, cz + 35, 6, 4, 3, false,
                WallStyle.PLASTER_WHITE, RoofStyle.TILE, false, 1, WindowStyle.NONE);
        kerb(world, cx - 17, cy, cz + 35, cx - 12, cz + 38, 1);
        for (int x = cx - 16; x <= cx - 13; x++) {   // 남벽 철창 (마당에서 안이 보인다 — 그것이 형벌이다)
            world.getBlockAt(x, cy + 2, cz + 38).setType(Material.IRON_BARS);
        }
        world.getBlockAt(cx - 14, cy + 1, cz + 38).setType(Material.IRON_BARS);    // 문간 철창 한 겹
        world.getBlockAt(cx - 16, cy + 1, cz + 36).setType(Material.HAY_BLOCK);    // 짚 한 자리
        world.getBlockAt(cx - 13, cy + 1, cz + 36).setType(Material.CAULDRON);     // 물그릇
        placeSign(world, cx - 15, cy + 1, cz + 39, BlockFace.SOUTH, "옥", "들어오는 문은 하나다");
    }

    // ─── 폐사당 (v6 ② — abandoned_shrine, 담장 밖 북서 외곽 / v6.4 ① — 물 위에 짓지 않는다) ───
    //
    // 【v6.4 ① 버그】 조감도 클로즈업에서 폐사당이 **호수 한복판에 반쯤 잠긴 채** 서 있었다.
    //   폐사당만 평탄화 없이 지형에 순응하는데(폐허는 순응해야 폐허다), 지면 판정을 getHighestBlockYAt 으로
    //   했다 — 이 함수는 **물도 '최상단 블록'으로 센다**. 그래서 수면이 지면으로 읽혀 호수 위에 기단이 얹혔다.
    //
    // v6.4 의 부지 선정 — 결정론 후보 탐색 (난수 0, 같은 월드 = 같은 자리):
    //   ① naturalGroundY(x,z) — 최상단에서 내려가며 **자연 지면 화이트리스트**(풀·흙·모래·돌…)에 처음 닿는 y.
    //      가는 길에 WATER/LAVA/ICE/KELP 를 만나면 그 칸은 **부적격(MIN_VALUE)** — 물은 지면이 아니다.
    //      나뭇잎·통나무·눈·풀은 물론 **폐사당이 지난번에 놓은 인공 블록도** 전부 통과해 내려간다.
    //   ② SHRINE_SITES 후보 12곳을 **순서대로** 검사 — 담 흔적 17x21 전 칸이 육지이고 지면 높이 편차 ≤ 5
    //      (절벽 배제)인 **첫 자리**. 다 소진하면 1번 후보를 최소 정지(整地)해 쓰고 경고 로그를 남긴다.
    //   ③ baseY = 부지 지면 최고점 + 1. 기단 밑은 packed_mud 로 메우고(공중 부양 금지), **자연 지면 블록은
    //      건드리지 않는다**(ground+1 부터 메운다). packed_mud·mud·stone_bricks 는 전부 화이트리스트 밖이라
    //      재조성 시 naturalGroundY 가 이들을 통과해 **같은 자연 지면**을 읽는다 → 두 번째 조성도 같은 후보.
    //   ④ 후보 제약 — ox ∈ [-88,-82] · oz ∈ [-86,-64]:
    //      (a) 부지 전체가 담장(r=60)·완사면 스커트(r=68) 밖 (blendEdge 가 지면을 흔들지 못한다),
    //      (b) 냉광 4점이 전부 검수 창 (cx-75, cz-75) ±20 안 → "폐사당 냉광 0" 경고 해소,
    //      (c) (cx-75, cz-75) 열에 폐사당 지붕이 얹히지 않는다 — 검수는 그 열의 getHighestBlockYAt 으로
    //          냉광 y창 [base-4, base+14] 를 잡으므로, 지붕(≈base+11)이 얹히면 제단 랜턴(base+2)이 창 밑으로
    //          빠진다. **이것이 "냉광 0" 경고의 진짜 원인이었다** (물 버그와 별개의 두 번째 버그).
    //   간판·명패·앵커 없음 (hidden — 발견은 서사의 몫). 광원은 전부 영혼 계열 = 마을의 온색과 정반대.

    /** 자연 지면 — 여기 닿으면 그것이 '땅'이다. 인공 블록(기단·메움·마루·담)은 전부 이 밖 = 통과해 내려간다. */
    private static final Set<Material> NATURAL_GROUND = EnumSet.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT,
            Material.PODZOL, Material.MYCELIUM, Material.MOSS_BLOCK, Material.CLAY,
            Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.SANDSTONE, Material.RED_SANDSTONE,
            Material.STONE, Material.ANDESITE, Material.DIORITE, Material.GRANITE, Material.TUFF,
            Material.DEEPSLATE, Material.CALCITE, Material.SNOW_BLOCK, Material.TERRACOTTA);

    /** 액체·수생·빙결 — 한 칸이라도 만나면 그 열은 부지가 될 수 없다 (물 위에 절은 서지 않는다) */
    private static final Set<Material> WET = EnumSet.of(
            Material.WATER, Material.LAVA, Material.BUBBLE_COLUMN, Material.POWDER_SNOW,
            Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.FROSTED_ICE,
            Material.KELP, Material.KELP_PLANT, Material.SEAGRASS, Material.TALL_SEAGRASS,
            Material.LILY_PAD, Material.SEA_PICKLE);

    /**
     * 폐사당 부지 후보 — {ox, oz} 마을 중심 기준 폐사당 중심. **순서가 곧 우선순위**(난수 금지).
     * 1번이 원위치에 가장 가깝고, 뒤로 갈수록 북서 바깥으로 물러난다. 제약은 위 ④ 참조.
     */
    private static final int[][] SHRINE_SITES = {
            // 1~6 — ox ∈ {-82,-83}: 검수의 y창 기준 열 (cx-75, cz-75) 이 부지 안에 들어와 냉광 검출이 **보장**된다
            {-82, -75}, {-82, -82}, {-83, -68}, {-82, -85}, {-83, -74}, {-83, -80},
            // 7~12 — 더 물러난 예비지 (앞의 여섯이 전부 물·절벽일 때만)
            {-85, -70}, {-85, -78}, {-86, -84}, {-88, -74}, {-86, -65}, {-88, -82},
    };

    private static final int SH_HW = 5;    // 본전 반폭 (11x15)
    private static final int SH_HD = 7;
    private static final int SH_YW = 8;    // 담 흔적 반폭 (17x21)
    private static final int SH_YD = 10;
    private static final int SH_MAX_RELIEF = 5;   // 부지 지면 높이 편차 상한 (넘으면 절벽 — 다음 후보로)
    private static final int SH_MARGIN = 4;       // 물 회피 여유 — 검수(TownAudit)가 폐사당 구역을 ±4 로 넓혀
                                                  // 수몰을 재므로, 담 흔적 밖 4칸까지 마른 땅이어야 위반이 안 난다

    private static final int[][] SH_COBWEBS = {   // 거미줄 5곳 — 중심 기준 {dx, dz, dy}
            {-4, -6, 4}, {4, -6, 4}, {-4, 6, 4}, {3, 2, 3}, {-2, -2, 4}
    };
    private static final int[][] SH_FLOOR_HOLES = {   // 썩어 내려앉은 마루 5칸 — 중심 기준
            {-3, -4}, {-2, -4}, {2, -1}, {-1, 4}, {0, 5}
    };
    private static final int[][] SH_DEBRIS = {   // 바닥에 떨어진 기와 4곳 (서측 무너진 쪽)
            {-4, -4}, {-3, 2}, {-4, 4}, {-2, 5}
    };

    /**
     * 지면 판정 — 최상단에서 내려가며 **자연 지면**에 처음 닿는 y. 물·용암·얼음·수초를 만나면 MIN_VALUE.
     * getHighestBlockYAt 은 물을 최상단 블록으로 세므로 그것만으로는 호수를 땅으로 읽는다 (v6.3 의 버그).
     * 나뭇잎·통나무·풀·눈층과 **지난 조성의 인공 블록**은 화이트리스트 밖이므로 그냥 통과한다 → 재조성 결정론.
     */
    private static int naturalGroundY(World world, int x, int z) {
        int top = Math.min(world.getHighestBlockYAt(x, z), world.getMaxHeight() - 1);
        int floor = Math.max(world.getMinHeight(), top - 48);
        for (int y = top; y >= floor; y--) {
            Material m = world.getBlockAt(x, y, z).getType();
            if (WET.contains(m)) {
                return Integer.MIN_VALUE;   // 물·용암 — 이 열은 부지가 아니다
            }
            if (NATURAL_GROUND.contains(m)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * 부지 검사 — 두 겹으로 본다.
     *   물: 담 흔적 17x21 **+ 사방 4칸**(= 검수가 폐사당 구역을 넓혀 수몰을 재는 바로 그 상자) 전 칸.
     *       한 칸이라도 젖었으면 실격 — 호수를 4칸 옆에 두고 짓지 않는다.
     *   경사: 담 흔적 17x21 안의 지면 높이 편차. SH_MAX_RELIEF 를 넘으면 절벽이므로 다음 후보로.
     * 통과하면 기단 높이(부지 지면 최고점 + 1)를 돌려준다 — 어느 열도 기단 위로 솟지 않는다.
     */
    private static int shrineSiteBaseY(World world, int sx, int sz) {
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int x = sx - SH_YW - SH_MARGIN; x <= sx + SH_YW + SH_MARGIN; x++) {
            for (int z = sz - SH_YD - SH_MARGIN; z <= sz + SH_YD + SH_MARGIN; z++) {
                int g = naturalGroundY(world, x, z);
                if (g == Integer.MIN_VALUE) {
                    return Integer.MIN_VALUE;   // 액체 한 칸 = 부지 전체 실격 (여유 4칸까지)
                }
                if (Math.abs(x - sx) <= SH_YW && Math.abs(z - sz) <= SH_YD) {
                    lo = Math.min(lo, g);       // 경사는 부지 안만 본다 (밖의 언덕은 폐허의 배경이다)
                    hi = Math.max(hi, g);
                }
            }
        }
        return hi - lo > SH_MAX_RELIEF ? Integer.MIN_VALUE : hi + 1;
    }

    /** 폐사당 — 반파 팔작·부러진 기둥·냉색 제단. 마을 안에 영혼 계열 광원은 단 하나도 없다. */
    private static void abandonedShrine(World world, int cx, int cy, int cz, List<Zone> out) {
        int sx = cx + SHRINE_SITES[0][0];
        int sz = cz + SHRINE_SITES[0][1];
        int baseY = Integer.MIN_VALUE;
        for (int[] site : SHRINE_SITES) {   // 결정론 탐색 — 첫 번째 육지·완만한 자리
            int cand = shrineSiteBaseY(world, cx + site[0], cz + site[1]);
            if (cand != Integer.MIN_VALUE) {
                sx = cx + site[0];
                sz = cz + site[1];
                baseY = cand;
                break;
            }
        }
        if (baseY == Integer.MIN_VALUE) {   // 후보 소진 — 1번 자리를 최소 정지해서 쓴다
            baseY = shrineGrade(world, sx, sz);
            Bukkit.getLogger().warning("[혼천/조성] 폐사당 부지 후보 " + SHRINE_SITES.length
                    + "곳이 모두 물·절벽 — (" + sx + "," + sz + ") 을 최소 정지(整地)해 세운다. baseY=" + baseY);
        }
        int x0 = sx - SH_HW, x1 = sx + SH_HW, z0 = sz - SH_HD, z1 = sz + SH_HD;   // 본전 11x15
        int wx0 = sx - SH_YW, wx1 = sx + SH_YW, wz0 = sz - SH_YD, wz1 = sz + SH_YD;   // 무너진 담 17x21
        for (int x = wx0; x <= wx1; x++) {           // 부지 비우기 — 이전 조성물 제거 (재조성 = 같은 폐허)
            for (int z = wz0; z <= wz1; z++) {
                for (int y = baseY + 1; y <= baseY + 13; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR);
                }
            }
        }
        shrinePlatform(world, sx, sz, x0, x1, z0, z1, baseY);
        shrineFrame(world, sx, sz, x0, x1, z0, z1, baseY);
        shrineRoof(world, sx, sz, x0, x1, z0, z1, baseY);
        shrineAltar(world, sx, sz, baseY);
        shrineRuinYard(world, sx, sz, wx0, wx1, wz0, wz1, baseY);
        shrineRuins(world, sx, sz, baseY);   // v6.8 ③ — 석등·비석·계단·문설주·잡초 (폐허의 무대)
        out.add(new Zone("폐사당", "신상이 없는 제단 — 누군가 다녀갔다", world.getName(),
                wx0, baseY - 3, wz0, wx1, baseY + 13, wz1));
    }

    /**
     * 최소 정지(整地) — 후보를 다 소진했을 때만 돈다. 부지의 물·용암을 흙으로 메우고 지면을 고른다.
     * 기준 높이는 **부지 열들의 최상단 중앙값이 아니라 최댓값**(공중 부양 금지) — 결정론 상수식.
     */
    private static int shrineGrade(World world, int sx, int sz) {
        int rw = SH_YW + SH_MARGIN;
        int rd = SH_YD + SH_MARGIN;
        int hi = world.getMinHeight();
        for (int x = sx - rw; x <= sx + rw; x++) {
            for (int z = sz - rd; z <= sz + rd; z++) {
                hi = Math.max(hi, world.getHighestBlockYAt(x, z));
            }
        }
        int baseY = hi + 1;
        for (int x = sx - rw; x <= sx + rw; x++) {         // 검수 상자(구역 ±4)까지 물을 뺀다
            for (int z = sz - rd; z <= sz + rd; z++) {
                for (int y = baseY - 1; y > baseY - 12; y--) {   // 물·용암·공기 → 흙 (기단 밑을 메운다)
                    Material m = world.getBlockAt(x, y, z).getType();
                    if (m.isAir() || WET.contains(m)) {
                        world.getBlockAt(x, y, z).setType(Material.DIRT);
                    }
                }
            }
        }
        return baseY;
    }

    /**
     * 기단 — 돌 벽돌 1단(이끼·금간 변종 상수 치환) + 마루(썩어 내려앉은 구멍 5칸). 지형은 기단 밑만 메운다.
     * v6.4 ①: 메움 자재는 packed_mud (자연 지면 화이트리스트 밖) 이고 **자연 지면 블록 위(ground+1)부터**
     * 쌓는다 — 다음 조성의 naturalGroundY 가 이 메움을 통과해 **같은 자연 지면**을 읽어야 부지가 안 움직인다.
     */
    private static void shrinePlatform(World world, int sx, int sz,
                                       int x0, int x1, int z0, int z1, int baseY) {
        for (int x = x0 - 1; x <= x1 + 1; x++) {
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                int ground = naturalGroundY(world, x, z);
                if (ground == Integer.MIN_VALUE) {
                    ground = baseY - 1;   // 정지(整地) 경로 — 이미 메워져 있다
                }
                for (int y = ground + 1; y < baseY; y++) {
                    world.getBlockAt(x, y, z).setType(Material.PACKED_MUD);   // 평탄화가 아니라 기단 아래 메움
                }
                int dx = x - sx;
                int dz = z - sz;
                Material m = Material.STONE_BRICKS;
                if (Math.floorMod(dx * 3 + dz * 5, 10) < 2) {
                    m = Material.CRACKED_STONE_BRICKS;                  // 금 간 돌 벽돌 ~20%
                } else if (Math.floorMod(dx + dz * 2, 7) == 0) {
                    m = Material.MOSSY_STONE_BRICKS;                    // 이끼 낀 돌 벽돌 ~10%
                }
                world.getBlockAt(x, baseY, z).setType(m);
            }
        }
        for (int x = x0 + 1; x <= x1 - 1; x++) {   // 마루 — 가문비 판자
            for (int z = z0 + 1; z <= z1 - 1; z++) {
                world.getBlockAt(x, baseY, z).setType(Material.SPRUCE_PLANKS);
            }
        }
        for (int[] h : SH_FLOOR_HOLES) {   // 썩어 내려앉은 마루 — 진흙이 드러나고 잡초가 올라온다
            world.getBlockAt(sx + h[0], baseY, sz + h[1]).setType(Material.MUD);   // 화이트리스트 밖 = 결정론
        }
        world.getBlockAt(sx + SH_FLOOR_HOLES[0][0], baseY + 1, sz + SH_FLOOR_HOLES[0][1])
                .setType(Material.SHORT_GRASS);
        world.getBlockAt(sx + SH_FLOOR_HOLES[3][0], baseY + 1, sz + SH_FLOOR_HOLES[3][1])
                .setType(Material.FERN);
    }

    /**
     * 골조 — 기둥 6주 중 2주는 y+2에서 끊고 그 자리 바닥에 원목을 눕힌다(쓰러진 기둥).
     * 벽은 백벽이 아니라 회백 테라코타(빛바랜 회벽), 남벽은 절반만 세운다(뻥 뚫린 폐허의 단면).
     * 창은 없다 — 유리는 오래전에 깨졌다. 벽 구멍은 좌표식 결정론 치환.
     */
    private static void shrineFrame(World world, int sx, int sz,
                                    int x0, int x1, int z0, int z1, int baseY) {
        int zMid = (z0 + z1) / 2;
        int xMid = (x0 + x1) / 2;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
                if (!wall) {
                    continue;
                }
                if (z == z1 && x > xMid) {
                    continue;   // 남벽 절반 소실
                }
                boolean post = (x == x0 || x == x1) && (z == z0 || z == zMid || z == z1);
                boolean broken = post && ((x == x0 && z == zMid) || (x == x1 && z == z1));
                int top = post ? (broken ? baseY + 2 : baseY + 4) : baseY + 4;
                for (int y = baseY + 1; y <= top; y++) {
                    if (post) {
                        world.getBlockAt(x, y, z).setType(Material.STRIPPED_OAK_LOG);   // 빛바랜 기둥
                    } else if (Math.floorMod((x - sx) * 5 + (z - sz) * 3, 11) == 0) {
                        world.getBlockAt(x, y, z).setType(Material.AIR);                // 허물어진 벽 구멍
                    } else {
                        world.getBlockAt(x, y, z).setType(Material.LIGHT_GRAY_TERRACOTTA);
                    }
                }
            }
        }
        for (int i = 0; i < 3; i++) {   // 쓰러진 기둥 2주 — 바닥에 눕는다 (Orientable)
            Orientable log = (Orientable) Material.STRIPPED_OAK_LOG.createBlockData();
            log.setAxis(Axis.X);
            world.getBlockAt(x0 + 1 + i, baseY + 1, zMid).setBlockData(log);
            Orientable log2 = (Orientable) Material.STRIPPED_OAK_LOG.createBlockData();
            log2.setAxis(Axis.Z);
            world.getBlockAt(x1, baseY + 1, z1 - 1 - i).setBlockData(log2);
        }
        for (int[] c : SH_COBWEBS) {   // 거미줄 5곳 — 폐사당 중심 기준 상수
            world.getBlockAt(sx + c[0], baseY + c[2], sz + c[1]).setType(Material.COBWEB);
        }
    }

    /**
     * 지붕 — 팔작의 동측 절반만 온전하다. 서측 계단 링은 1층에서 끊기고, 구멍 가장자리에
     * 심층암 타일·벽돌 잔해가 흩뿌려진다(상수 좌표). 바닥에도 떨어진 기와 4곳.
     */
    private static void shrineRoof(World world, int sx, int sz,
                                  int x0, int x1, int z0, int z1, int baseY) {
        int xMid = (x0 + x1) / 2;
        int rx0 = x0 - 1, rx1 = x1 + 1, rz0 = z0 - 1, rz1 = z1 + 1;
        int yb = baseY + 5;
        for (int x = rx0; x <= rx1; x++) {   // 처마 링 1층 — 서측 일부는 이미 떨어져 나갔다
            if (Math.floorMod(x - sx, 5) != 0 || x >= xMid) {
                roofBlock(world, x, yb, rz0, BlockFace.SOUTH, x == rx0 || x == rx1, RoofStyle.TILE);
                roofBlock(world, x, yb, rz1, BlockFace.NORTH, x == rx0 || x == rx1, RoofStyle.TILE);
            }
            if (x >= xMid) {
                world.getBlockAt(x, yb - 1, rz0).setType(Material.DARK_OAK_SLAB);   // 서까래 (남은 쪽만)
                world.getBlockAt(x, yb - 1, rz1).setType(Material.DARK_OAK_SLAB);
            }
        }
        for (int z = rz0 + 1; z <= rz1 - 1; z++) {
            roofBlock(world, rx1, yb, z, BlockFace.WEST, false, RoofStyle.TILE);    // 동측 처마 = 온전
            world.getBlockAt(rx1, yb - 1, z).setType(Material.DARK_OAK_SLAB);
            if (Math.floorMod(z - sz, 4) == 0) {
                roofBlock(world, rx0, yb, z, BlockFace.EAST, false, RoofStyle.TILE);   // 서측 = 듬성듬성
            }
        }
        for (int i = 1; x1 - i >= xMid; i++) {   // 동측 지붕면만 마루까지 오른다 (동·서면 = 직각 결)
            int x = rx1 - i;
            int y = yb + i;
            for (int z = rz0 + i; z <= rz1 - i; z++) {
                boolean hip = z == rz0 + i || z == rz1 - i;   // 추녀마루 = TILE 로 통일
                roofBlock(world, x, y, z, BlockFace.WEST, hip, RoofStyle.TILE,
                        !hip && crossGrain(BlockFace.WEST));
            }
        }
        int ridgeX = xMid;
        int ridgeY = yb + (rx1 - xMid);
        for (int z = z0 + 2; z <= z1 - 4; z++) {   // 부러진 용마루 — 남쪽 끝은 무너져 없다. 능선이 Z 축 = 직각 결
            world.getBlockAt(ridgeX, ridgeY, z).setType(
                    z == z0 + 2 ? Material.DEEPSLATE_BRICKS : Material.DEEPSLATE_BRICK_SLAB);   // 치미 1단만 남음
        }
        for (int z = z0 + 2; z <= z1 - 4; z += 3) {   // 무너진 단면의 잔해 (심층암 벽돌)
            world.getBlockAt(ridgeX - 1, ridgeY - 1, z).setType(Material.DEEPSLATE_BRICKS);
        }
        for (int[] d : SH_DEBRIS) {   // 바닥에 떨어진 기와 4곳
            world.getBlockAt(sx + d[0], baseY + 1, sz + d[1]).setType(Material.DEEPSLATE_TILE_SLAB);
        }
    }

    /**
     * 무너진 담(진흙 벽돌 높이 0~2·지형 순응) + 매화 관목 + 뒷마당 영혼 모닥불.
     * v6.4 ①: 담 발치도 getHighestBlockYAt 이 아니라 naturalGroundY 로 잡는다 — 그렇지 않으면
     * ㉮ 물 위에 담이 서고, ㉯ 재조성 때 **지난번 담(진흙 벽돌) 위에 담을 또 쌓아** 담이 자란다.
     * 냉광 2점(영혼 랜턴·영혼 모닥불)도 제 지면 위에 놓는다 (공중에 뜬 등은 등이 아니다).
     */
    private static void shrineRuinYard(World world, int sx, int sz,
                                       int wx0, int wx1, int wz0, int wz1, int baseY) {
        for (int x = wx0; x <= wx1; x++) {
            for (int z = wz0; z <= wz1; z++) {
                if (x != wx0 && x != wx1 && z != wz0 && z != wz1) {
                    continue;
                }
                int h = Math.floorMod((x - sx) * 7 + (z - sz) * 11, 3);   // 높이 0~2 — 들쭉날쭉 (상수식)
                int ground = yardGroundY(world, x, z, baseY);
                for (int i = 1; i <= h; i++) {
                    world.getBlockAt(x, ground + i, z).setType(Material.MUD_BRICKS);
                }
                if (h == 2) {
                    world.getBlockAt(x, ground + 3, z).setType(Material.MUD_BRICK_SLAB);   // 무너진 갓
                }
            }
        }
        plumBush(world, sx - 7, yardGroundY(world, sx - 7, sz, baseY), sz);   // 폐허에 홀로 피는 매화
        int lz = sz - 9;
        soulLantern(world, sx + 7, yardGroundY(world, sx + 7, lz, baseY) + 1, lz, false);
        int cz2 = sz + 9;
        world.getBlockAt(sx + 7, yardGroundY(world, sx + 7, cz2, baseY) + 1, cz2)
                .setType(Material.SOUL_CAMPFIRE);   // 누군가 다녀갔다
    }

    // ─── v6.8 ③ 폐사당의 무대 — 기연이 벌어질 자리는 '작은 폐허'여선 안 된다 ───
    //
    // 지금까지 폐사당은 반파된 집 한 채 + 제단이었다. 무너진 지붕은 있는데 **무너진 것들의 역사**가 없다:
    // 여기 절이 있었다는 증거 — 참배길의 석등, 시주자의 비석, 계단, 문설주 — 가 하나도 없었다.
    // v6.8 은 폐허를 **한때 온전했던 것의 잔해**로 다시 쓴다. 냉색 조명(영혼 계열)은 폐사당 전용을 유지하고
    // (마을 안 0개 불변), 새 냉광 2점도 전부 폐사당 구역 안에 든다 → 검수 ⑦ 냉색 격리 불변.
    //
    // 자재: 돌 벽돌 계열(금간·이끼)·조각 돌 벽돌·이끼 양탄자·빛바랜 원목. 채색 0. 담장 밖 ±65 스캔 밖이라
    // 마을 검수에 잡히지 않지만, 검수 ⑩(담장 밖 채움)의 링(62~88) 안이므로 **채움률에는 보탬**이 된다.

    /** 폐허의 무대 — 석등 2기·비석 2·잡초 덮인 계단·기울어진 문설주·마당 잡초 */
    private static void shrineRuins(World world, int sx, int sz, int baseY) {
        stoneLantern(world, sx - 7, sz + 6, baseY, true);    // 참배길 석등 — 한 기는 아직 서 있다
        stoneLantern(world, sx + 5, sz + 3, baseY, false);   // 한 기는 넘어져 등롱이 풀밭에 뒹군다
                                                             // (넘어진 조각이 x+1·x+2 로 뻗으므로 담(x+8) 안에 든다)
        stele(world, sx - 4, sz + 9, baseY, true);           // 시주 비석 — 글자는 지워졌다
        stele(world, sx + 4, sz + 9, baseY, false);          // 부러진 비석 — 몸통만 남았다
        shrineSteps(world, sx, sz, baseY);
        shrineJambs(world, sx, sz, baseY);
        shrineWeeds(world, sx, sz, baseY);
    }

    /** 석등 — 기단·간주·화사석·냉광. upright=false 면 무너져 옆으로 뒹군다 */
    private static void stoneLantern(World world, int x, int z, int baseY, boolean upright) {
        int g = yardGroundY(world, x, z, baseY);
        world.getBlockAt(x, g + 1, z).setType(Material.MOSSY_STONE_BRICKS);       // 기단
        if (upright) {
            world.getBlockAt(x, g + 2, z).setType(Material.STONE_BRICK_WALL);     // 간주(竿柱)
            world.getBlockAt(x, g + 3, z).setType(Material.CHISELED_STONE_BRICKS);// 화사석
            soulLantern(world, x, g + 4, z, false);                               // 냉광 — 여긴 다르다
            world.getBlockAt(x, g + 1, z + 1).setType(Material.MOSS_CARPET);      // 발치에 낀 이끼
            return;
        }
        world.getBlockAt(x + 1, g + 1, z).setType(Material.CRACKED_STONE_BRICKS); // 넘어진 간주
        world.getBlockAt(x + 2, g + 1, z).setType(Material.CHISELED_STONE_BRICKS);// 굴러떨어진 화사석
        soulLantern(world, x + 2, g + 2, z, false);                               // 그 위에 얹힌 등
        world.getBlockAt(x + 1, g + 2, z).setType(Material.MOSS_CARPET);
    }

    /** 비석 — 조각 돌 벽돌 몸통 + 갓돌. whole=false 면 허리에서 부러져 조각이 옆에 눕는다 */
    private static void stele(World world, int x, int z, int baseY, boolean whole) {
        int g = yardGroundY(world, x, z, baseY);
        world.getBlockAt(x, g + 1, z).setType(Material.STONE_BRICKS);             // 대좌
        world.getBlockAt(x, g + 2, z).setType(Material.CHISELED_STONE_BRICKS);    // 비신
        if (whole) {
            world.getBlockAt(x, g + 3, z).setType(Material.CHISELED_STONE_BRICKS);
            world.getBlockAt(x, g + 4, z).setType(Material.STONE_BRICK_SLAB);     // 갓돌
            return;
        }
        world.getBlockAt(x + 1, g + 1, z).setType(Material.MOSSY_STONE_BRICK_SLAB);   // 부러져 나간 조각
        world.getBlockAt(x, g + 1, z + 1).setType(Material.MOSS_CARPET);
    }

    /**
     * 참배 계단 — 기단 남면(z+8)으로 오르는 다섯 칸. 평탄화 없이 지형에 얹고, 계단 밑은 다진 진흙으로 메운다
     * (공중에 뜬 계단 금지). 기단 가장자리엔 이끼 — 아무도 밟지 않은 지 오래다.
     */
    private static void shrineSteps(World world, int sx, int sz, int baseY) {
        for (int x = sx - 2; x <= sx + 2; x++) {
            int z = sz + SH_HD + 2;   // 기단 끝(z+8) 바로 밖
            int g = yardGroundY(world, x, z, baseY);
            for (int y = g + 1; y < baseY; y++) {
                world.getBlockAt(x, y, z).setType(Material.PACKED_MUD);
            }
            stair(world, x, baseY, z, Math.floorMod(x - sx, 2) == 0
                    ? Material.MOSSY_STONE_BRICK_STAIRS : Material.STONE_BRICK_STAIRS, BlockFace.NORTH);
        }
        for (int x : new int[]{sx - 2, sx + 1}) {   // 잡초 덮인 계단참
            if (world.getBlockAt(x, baseY + 1, sz + SH_HD + 1).getType().isAir()) {
                world.getBlockAt(x, baseY + 1, sz + SH_HD + 1).setType(Material.MOSS_CARPET);
            }
        }
    }

    /**
     * 문설주 — 남벽이 절반 소실된 쪽(동측)에 남은 문틀. 한 짝은 서 있고 한 짝은 기울었다:
     * 인방을 **한 칸씩 어긋나게** 얹어 수평이 무너진 문을 만든다 (기울어짐은 각도가 아니라 층차로 쓴다).
     */
    private static void shrineJambs(World world, int sx, int sz, int baseY) {
        int z = sz + SH_HD;   // 남벽 줄 (동측 절반은 벽이 없다)
        for (int y = baseY + 1; y <= baseY + 3; y++) {
            world.getBlockAt(sx + 1, y, z).setType(Material.STRIPPED_OAK_LOG);   // 성한 문설주
        }
        for (int y = baseY + 1; y <= baseY + 2; y++) {
            world.getBlockAt(sx + 4, y, z).setType(Material.STRIPPED_OAK_LOG);   // 내려앉은 문설주
        }
        Orientable lintel = (Orientable) Material.STRIPPED_OAK_LOG.createBlockData();
        lintel.setAxis(Axis.X);
        world.getBlockAt(sx + 2, baseY + 3, z).setBlockData(lintel);             // 인방 — 한 칸 어긋나 기울었다
        world.getBlockAt(sx + 3, baseY + 2, z).setBlockData(lintel);
        world.getBlockAt(sx + 2, baseY + 1, z).setType(Material.COBWEB);         // 문틀에 걸린 거미줄
    }

    /**
     * 마당 잡초 — 기단 밖 폐허 마당의 26%. 좌표 해시(비선형)라 줄무늬가 서지 않는다.
     * 이미 무언가 선 칸(담·석등·비석·매화)은 건너뛴다 — 잡초가 폐허를 밀어내지 않는다.
     */
    private static void shrineWeeds(World world, int sx, int sz, int baseY) {
        for (int dx = -SH_YW; dx <= SH_YW; dx++) {
            for (int dz = -SH_YD; dz <= SH_YD; dz++) {
                if (Math.abs(dx) <= SH_HW + 1 && Math.abs(dz) <= SH_HD + 1) {
                    continue;   // 기단 위는 마루의 몫 (썩은 구멍의 풀은 shrinePlatform 이 심었다)
                }
                int x = sx + dx;
                int z = sz + dz;
                int h = hash(x, z, 100);
                if (h >= 26) {
                    continue;
                }
                int g = yardGroundY(world, x, z, baseY);
                if (!world.getBlockAt(x, g + 1, z).getType().isAir()) {
                    continue;
                }
                Material ground = world.getBlockAt(x, g, z).getType();
                if (!NATURAL_GROUND.contains(ground)) {
                    continue;
                }
                world.getBlockAt(x, g + 1, z).setType(h < 18 ? Material.SHORT_GRASS : Material.FERN);
            }
        }
    }

    /** 마당(기단 밖) 지면 — 자연 지면. 액체·미검출이면 기단 높이로 (정지 경로에서도 뜨지 않는다) */
    private static int yardGroundY(World world, int x, int z, int baseY) {
        int g = naturalGroundY(world, x, z);
        return g == Integer.MIN_VALUE ? baseY : g;
    }

    /** 매화 관목 — 벚나무 잎(persistent) 한 그루. 폐사당 마당의 유일한 색 */
    private static void plumBush(World world, int x, int baseY, int z) {
        world.getBlockAt(x, baseY + 1, z).setType(Material.CHERRY_LOG);
        world.getBlockAt(x, baseY + 2, z).setType(Material.CHERRY_LOG);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                leaf(world, x + dx, baseY + 3, z + dz);
            }
        }
        leaf(world, x, baseY + 4, z);
    }

    /**
     * 제단 — 북벽 앞 돌 벽돌 2단 + 향로 항아리 + 꺼진 양초 3 + 좌우 영혼 랜턴 2.
     * 신상 자리는 비운다: 신상이 '없다'는 것이 이 건물의 최대 소품이다.
     * 곁에 경전 시렁(조각된 책장 — 두 칸만 남았다) + 바닥에 떨어진 책(갈색 양탄자).
     */
    private static void shrineAltar(World world, int sx, int sz, int baseY) {
        for (int x = sx - 2; x <= sx + 2; x++) {
            world.getBlockAt(x, baseY + 1, sz - 5).setType(Material.STONE_BRICKS);        // 제단 1단
            world.getBlockAt(x, baseY + 1, sz - 6).setType(Material.MOSSY_STONE_BRICKS);
            world.getBlockAt(x, baseY + 2, sz - 6).setType(Material.STONE_BRICK_SLAB);    // 제단 2단
        }
        world.getBlockAt(sx, baseY + 2, sz - 5).setType(Material.DECORATED_POT);          // 향로
        candles(world, sx - 2, baseY + 2, sz - 5, 3, false);                              // 꺼진 양초 3
        soulLantern(world, sx - 3, baseY + 2, sz - 6, false);                             // 냉색 — 여긴 다르다
        soulLantern(world, sx + 3, baseY + 2, sz - 6, false);
        // 신상 자리 (sx, baseY+3, sz-6) 는 비워 둔다 — 코드로도 비운다
        world.getBlockAt(sx, baseY + 3, sz - 6).setType(Material.AIR);
        bookshelf(world, sx - 4, baseY + 1, sz - 2, BlockFace.EAST, 2);                   // 경전 시렁 — 두 권만
        world.getBlockAt(sx - 3, baseY + 1, sz - 2).setType(Material.BROWN_CARPET);       // 떨어진 책
        world.getBlockAt(sx + 4, baseY + 1, sz - 4).setType(Material.DECORATED_POT);      // 깨진 살림 항아리
    }

    // ─── 일반 민가 — 앵커·구역·NPC 없는 순수 풍경 (마을의 생기) ───

    /**
     * 민가 9채 — 결정론 좌표·유형 조합표 (난수 금지). 북골목(z-21)·남골목(z+21)을 따라 문이 골목을 본다.
     * v5 형태 유형 풀 4종 — 골조 자체가 다르다 ("색만 바뀐 변형은 변형이 아니다"):
     *   일자형 13x9 / ㄱ자형(본채 12x9 + 뒷날개 6x7 + 안마당) / 다락형 9x12(1.5층 — 다락+사다리)
     *   / 작업장 병설형(본채 12x9 + 작업간 7x6 — 대장간 화로 또는 베틀).
     * 자재 팔레트는 유형에 고정: 일자형 = 회벽+목골 노출·흑와 / ㄱ자형 = 구운 벽돌·흑목 너와
     *   / 다락형 = 가로 통나무·흙기와(v6.2 ④) / 작업장 = 흙벽돌 본채(흑와)+회벽목골 작업간(너와).
     * v6.1 ①④ — 대로 7칸(x/z ±3)·처마 2칸을 반영한 재배치. 문턱 줄은 골목(z∓19..∓21)에 딱 붙인다.
     *   #2 -20→-17 (대로 갓길과 #1 작업마당 사이 재분배 — 이격 4), #3 +6→+8 (갓길에서 5칸),
     *   #4 +25→+29 (#3 처마와 이격 4), #5 (+38,-20)→(+42,-18) (#4 처마와 겹침 해소·골목 비켜서기),
     *   #9 (-45,+12)→(-45,+8) (#6 처마와 z 겹침 해소 — 처마끼리 같은 y 에서 만나면 안 된다).
     *   #  유형              벽             지붕        담·마당        위치(x0,z0)   문
     *   1  작업장(대장간)    흙벽돌+회벽목골  흑와+너와   돌담 작업마당   (-44,-30)    남
     *   2  ㄱ자형            구운 벽돌       너와        목책 안마당     (-17,-31)    남
     *   3  일자형            회벽+목골       흑와        곁담 돌담       (+8,-30)     남
     *   4  다락형            가로 통나무     흙기와        없음           (+29,-33)    남
     *   5  일자형            회벽+목골       흑와        곁담 목책       (+42,-18)    북
     *   6  ㄱ자형            구운 벽돌       너와        목책 안마당     (-43,+22)    북
     *   7  작업장(직조간)    흙벽돌+회벽목골  흑와+너와   돌담 작업마당   (+8,+23)     북
     *   8  다락형            가로 통나무     흙기와        없음           (+34,+22)    북
     *   9  일자형            회벽+목골       흑와        없음           (-47,+8)     남
     * v6.3 ③ — #9 을 x-45 → x-47 로 물렸다. 구 위치의 지붕 동단(x-31)이 의방 벽(x-24)에서 7칸이라
     *   검수의 지붕 스캔 박스(벽 ±8) 안에 들어와 **의방의 물매 단면을 이웃집 처마에서 시작하게** 만들었다
     *   (측정치 0.33 = 4/12 — 의방 자신의 물매가 아니었다). 새 지붕 동단은 x-33 = 벽에서 9칸 밖.
     */
    /**
     * v7.0 ② 필지 등급 — **한 마을 안에서 빈부가 보인다** (village_tiers.md 4.3).
     * 북골목 3채(#1 대장간 · #2 ㄱ자 · #3 일자)를 **빈촌 필지로 강등**한다: 흙벽(mud_bricks) · 초가(너와 +
     * 짚 점치환) · 기운 울타리 · 규모 축소. 근거는 세계관에 있다 — cheongha_fever_rumor(열병) 와
     * north_road_bandits(물류 불안)의 1차 피해자가 북쪽 골목이다. **가난은 서사가 아니라 자재로 말한다.**
     * 나머지 중촌 민가 중 일자형 둘(#5 · #9)은 중촌 규모표대로 13x9 → **15x11** 로 키운다.
     * 필지 예외의 상한(마을 건물 수의 30%)도 지킨다: 15채 중 3채 = 20%.
     */
    private static void cottages(World world, int cx, int cy, int cz) {
        // v7.2 【필지끼리는 높이가 달라도 된다】 — 기단은 PLOT_PADS 와 **같은 값**이어야 한다
        //   (고도장이 그 필지를 그 높이로 다져 놓았다. 두 표가 어긋나면 집이 땅에 묻히거나 뜬다).
        //   기단이 붙는 것은 **사각형 필지**뿐이다: ㄱ자형(#2·#6)·작업장형(#1·#7)은 날개·부속간 때문에
        //   필지가 사각형이 아니라서 0 으로 둔다 (기단은 사각형 위에만 선다).
        workshopHouse(world, cx - 44, cy, cz - 30, false, true, true);                 // #1 대장간 — 빈촌 필지
        lHouse(world, cx - 17, cy, cz - 31, false, true);                              // #2 — 빈촌 필지
        linearHouse(world, cx + 8, cy - 1, cz - 30, false, Material.OAK_FENCE, true);  // #3 빈촌 — **한 켜 낮은 자리**
        loftHouse(world, cx + 29, cy + 1, cz - 33, false);                             // #4 — 한 켜 높은 자리
        linearHouse(world, cx + 42, cy + 1, cz - 18, true, Material.SPRUCE_FENCE, false);  // #5 (15x11) — 한 켜 높다
        lHouse(world, cx - 43, cy, cz + 22, true, false);                              // #6
        workshopHouse(world, cx + 8, cy, cz + 23, true, false, false);                 // #7 직조간
        loftHouse(world, cx + 34, cy - 1, cz + 22, true);                              // #8 — 한 켜 낮은 자리
        linearHouse(world, cx - 51, cy - 1, cz + 6, false, null, false);               // #9 (15x11) — 한 켜 낮다
        poorPlots(world, cx, cy, cz);
    }

    /**
     * v7.0 ② 빈촌 필지의 마당 — {x0, x1, z0, z1}. 기운 울타리 + 깨진 살림.
     * 전부 지붕 상자 **밖**에 잡았고 freeCell() 을 통과한 칸에만 선다 — 골목(z∓16~24)·대로(±5)·
     * 담 발치를 한 칸도 밀어내지 않는다 (reserved 가 막는다).
     */
    private static final int[][] POOR_FENCES = {
            {-48, -25, -34, -34}, {-48, -48, -34, -26},   // #1 대장간 — 북·서 마당
            {-19, -5, -40, -40},                          // #2 ㄱ자 — 뒷날개 밖
            {6, 21, -34, -34}, {21, 21, -34, -26},        // #3 일자 — 북·동 마당
    };

    /** 빈촌 필지 3곳 — 기운 울타리 · 짚단 · 깨진 항아리 · 퇴비통 (밀도가 아니라 정돈도가 가난을 말한다) */
    private static void poorPlots(World world, int cx, int cy, int cz) {
        for (int[] f : POOR_FENCES) {
            leaningFence(world, cx, cy, cz, cx + f[0], cx + f[1], cz + f[2], cz + f[3]);
        }
        int[][] junk = {{-47, -30, 5}, {-45, -27, 4}, {-20, -39, 5}, {-12, -39, 0},
                {7, -33, 5}, {19, -32, 4}, {20, -27, 0}};
        for (int[] j : junk) {
            lifeTrace(world, cx, cy, cz, cx + j[0], cz + j[1], j[2]);
        }
    }

    /**
     * 일자형 13x9 — 회벽+목골 노출 벽, 흑와 지붕, 벽고 3. sideWallMat = 곁담 재질(null 이면 없음).
     * 인테리어 필수 세트: 침상 2칸+곁깔개 · 수납(술통+궤) · 작업대 · 화덕 ·
     * 조명 높이 변화(바닥 등롱 + 술통 위 선반등) · 바닥 패턴(귀틀+방석 깔개) · 소품(궤 위 화분).
     */
    private static void linearHouse(World world, int x0, int y0, int z0,
                                    boolean doorNorth, Material sideWallMat, boolean poor) {
        int w = poor ? 11 : 15, d = poor ? 9 : 11;   // v7.0 — 빈촌 11x9 / 중촌 15x11 (규모표 2.1)
        // v6.7 ② 민가 벽고 3 → 4. 처마 2칸 밑에서 벽이 두 켜(창 아래·위)로 보이면 집이 '뚜껑'이 아니라 '집'이 된다.
        shell(world, x0, y0, z0, w, d, 4, doorNorth,
                poor ? WallStyle.MUD_BRICK : WallStyle.FRAME_GRAY,
                poor ? RoofStyle.SHINGLE : RoofStyle.TILE, false,
                WindowStyle.COTTAGE);   // 민가 = 맞배 계열 유지 · 창은 작고 적다 (v6.9 ③)
        int x1 = x0 + w - 1, z1 = z0 + d - 1;
        if (poor) {
            thatch(world, x0 - 2, z0 - 2, x1 + 2, z1 + 2, y0 + 5);   // 초가 — 너와 지붕에 짚을 얹는다
        }
        int far = doorNorth ? z1 - 1 : z0 + 1;    // 문 반대편 안쪽
        int near = doorNorth ? z0 + 1 : z1 - 1;   // 문쪽 벽면 안줄
        int mid = z0 + d / 2;
        world.getBlockAt(x0 + 1, y0 + 1, far).setType(Material.WHITE_CARPET);   // 침상 2칸
        world.getBlockAt(x0 + 2, y0 + 1, far).setType(Material.WHITE_CARPET);
        world.getBlockAt(x0 + 1, y0 + 1, doorNorth ? far - 1 : far + 1).setType(Material.LIGHT_GRAY_CARPET);   // 곁깔개
        hearth(world, x1 - 1, y0, far);                                          // 화덕
        world.getBlockAt(x1 - 2, y0 + 1, far).setType(Material.DECORATED_POT);   // 쌀독 2 (v6 ④ — 살림 격차)
        world.getBlockAt(x1 - 3, y0 + 1, far).setType(Material.DECORATED_POT);
        world.getBlockAt(x1 - 1, y0 + 1, mid).setType(Material.CRAFTING_TABLE);  // 작업대
        world.getBlockAt(x1 - 1, y0 + 1, near).setType(Material.BARREL);         // 수납
        world.getBlockAt(x0 + 1, y0 + 1, near).setType(Material.CHEST);
        world.getBlockAt(x0 + 1, y0 + 2, near).setType(Material.POTTED_POPPY);   // 궤 위 화분 (소품)
        world.getBlockAt(x0 + 1, y0 + 1, mid).setType(Material.BARREL);          // 선반등 — 술통 위 등롱
        world.getBlockAt(x0 + 1, y0 + 2, mid).setType(Material.LANTERN);
        world.getBlockAt(x1 - 2, y0 + 1, near).setType(Material.LANTERN);        // 바닥 등롱 (높이 변화 짝)
        world.getBlockAt(x0 + 6, y0 + 1, mid).setType(Material.BROWN_CARPET);    // 방석 깔개 (문 열 — 통행 가능)
        world.getBlockAt(x0 + 5, y0 + 1, mid).setType(Material.BROWN_CARPET);
        if (sideWallMat != null) {   // 곁담 — 정면 좌우 낮은 담 2칸 (문·골목은 막지 않는다)
            int frontZ = doorNorth ? z0 : z1;
            for (int i = 1; i <= 2; i++) {
                world.getBlockAt(x0 - i, y0 + 1, frontZ).setType(sideWallMat);
                world.getBlockAt(x1 + i, y0 + 1, frontZ).setType(sideWallMat);
            }
        }
    }

    /**
     * ㄱ자형 — 본채 12x9 + 뒷날개 6x7(부엌·광)이 직각으로 붙어 ㄱ 골조. 구운 벽돌 벽 + 흑목 너와.
     * 날개 골조의 문 개구가 그대로 본채↔날개 통로가 된다 (공유벽 위 개구). 꺾임 안쪽 = 목책 안마당.
     * 필수 세트: 본채 침상·궤(위 선반등)·방석 / 날개 화덕·작업대·술통 2·가마솥·바닥 등롱.
     */
    private static void lHouse(World world, int x0, int y0, int z0, boolean doorNorth, boolean poor) {
        int w = 12, d = 9;
        WallStyle ws = poor ? WallStyle.MUD_BRICK : WallStyle.BRICK;   // v7.0 ② 빈촌 = 흙벽
        shell(world, x0, y0, z0, w, d, 4, doorNorth, ws, RoofStyle.SHINGLE, false,
                WindowStyle.COTTAGE);   // v6.7 ② 벽고 3 → 4
        int x1 = x0 + w - 1, z1 = z0 + d - 1;
        int wingZ0 = doorNorth ? z1 : z0 - 6;   // 날개는 문 반대편(뒤)으로 뻗는다
        shell(world, x0, y0, wingZ0, 6, 7, 4, doorNorth, ws, RoofStyle.SHINGLE, false,
                1, WindowStyle.NONE);   // 부속채 처마 1칸 (위계) · 헛간에 창은 내지 않는다
        if (poor) {
            thatch(world, x0 - 2, z0 - 2, x1 + 2, z1 + 2, y0 + 5);          // 초가 (본채)
            thatch(world, x0 - 1, wingZ0 - 1, x0 + 6, wingZ0 + 7, y0 + 5);  // 초가 (날개 — 처마 1칸)
        }
        // 본채 — 침상·수납·방석·조명
        int far = doorNorth ? z1 - 1 : z0 + 1;
        int near = doorNorth ? z0 + 1 : z1 - 1;
        world.getBlockAt(x1 - 1, y0 + 1, far).setType(Material.WHITE_CARPET);    // 침상 2칸
        world.getBlockAt(x1 - 2, y0 + 1, far).setType(Material.WHITE_CARPET);
        world.getBlockAt(x1 - 1, y0 + 1, doorNorth ? far - 1 : far + 1).setType(Material.LIGHT_GRAY_CARPET);
        world.getBlockAt(x1 - 1, y0 + 1, near).setType(Material.CHEST);          // 수납 + 선반등
        world.getBlockAt(x1 - 1, y0 + 2, near).setType(Material.LANTERN);
        world.getBlockAt(x0 + 1, y0 + 1, near).setType(Material.LANTERN);        // 바닥 등롱 (높이 변화 짝)
        world.getBlockAt(x0 + 6, y0 + 1, z0 + 4).setType(Material.BROWN_CARPET); // 방석 깔개
        // 날개 (부엌·광) — 화덕·작업대·술통·가마솥
        int wFar = doorNorth ? wingZ0 + 5 : wingZ0 + 1;    // 날개의 바깥쪽 끝 안줄
        int wIn = doorNorth ? wingZ0 + 3 : wingZ0 + 3;     // 날개 중간
        hearth(world, x0 + 1, y0, wFar);
        world.getBlockAt(x0 + 2, y0 + 1, wFar).setType(Material.DECORATED_POT);   // 쌀독 1 (v6 ④)
        world.getBlockAt(x0 + 4, y0 + 1, wFar).setType(Material.CRAFTING_TABLE);
        world.getBlockAt(x0 + 1, y0 + 1, wIn).setType(Material.BARREL);
        world.getBlockAt(x0 + 1, y0 + 1, doorNorth ? wIn - 1 : wIn + 1).setType(Material.BARREL);
        world.getBlockAt(x0 + 4, y0 + 1, wIn).setType(Material.CAULDRON);
        world.getBlockAt(x0 + 4, y0 + 1, doorNorth ? wingZ0 + 1 : wingZ0 + 5).setType(Material.LANTERN);
        // 안마당 — 꺾임 안쪽 목책 담 + 살림 (퇴비통·양귀비·술통)
        int yz0 = doorNorth ? z1 + 1 : z0 - 6;   // 마당 z 범위 시작
        int yz1 = doorNorth ? z1 + 6 : z0 - 1;
        int fenceZ = doorNorth ? z1 + 6 : z0 - 6;
        for (int x = x0 + 7; x <= x0 + 11; x++) {
            if (x != x0 + 9) {   // 마당 삽짝 (뒤쪽 출입)
                world.getBlockAt(x, y0 + 1, fenceZ).setType(Material.SPRUCE_FENCE);
            }
        }
        for (int z = yz0; z <= yz1; z++) {
            if (z != fenceZ) {
                world.getBlockAt(x0 + 11, y0 + 1, z).setType(Material.SPRUCE_FENCE);
            }
        }
        world.getBlockAt(x0 + 8, y0 + 1, doorNorth ? z1 + 2 : z0 - 2).setType(Material.COMPOSTER);
        world.getBlockAt(x0 + 9, y0 + 1, doorNorth ? z1 + 4 : z0 - 4).setType(Material.POPPY);
        world.getBlockAt(x0 + 10, y0 + 1, doorNorth ? z1 + 5 : z0 - 5).setType(Material.BARREL);
    }

    /**
     * 다락형 9x12 — 1.5층 귀틀집: 가로 통나무 벽(벽고 4) + 흙기와 지붕, 지붕 밑 다락(y+4 마루)과 사다리.
     * 필수 세트: 다락 침상 2칸+곁깔개·궤 / 아래층 화덕·작업대·술통·가마솥 ·
     * 조명 높이 변화(다락 밑 현수등 + 바닥 등롱 + 다락 등롱) · 바닥 패턴(귀틀+방석).
     */
    private static void loftHouse(World world, int x0, int y0, int z0, boolean doorNorth) {
        int w = 9, d = 12;
        shell(world, x0, y0, z0, w, d, 4, doorNorth, WallStyle.LOG, RoofStyle.MUD_TILE, false,
                WindowStyle.LOFT);   // v6.2 ④ — 청록 산화동 폐기 · 다락엔 창이 하나 더 (v6.9 ③)
        int x1 = x0 + w - 1, z1 = z0 + d - 1;
        int far = doorNorth ? z1 - 1 : z0 + 1;
        int near = doorNorth ? z0 + 1 : z1 - 1;
        // 다락 마루 (y+4) — 사다리 개구 1칸만 남긴다
        int ladderZ = far;
        for (int x = x0 + 1; x <= x1 - 1; x++) {
            for (int z = z0 + 1; z <= z1 - 1; z++) {
                if (x == x1 - 1 && z == ladderZ) {
                    continue;   // 사다리 개구
                }
                world.getBlockAt(x, y0 + 4, z).setType(Material.SPRUCE_PLANKS);
            }
        }
        for (int y = y0 + 1; y <= y0 + 4; y++) {   // 사다리 — 동벽에 붙는다 (개구 통과)
            Directional ladder = (Directional) Material.LADDER.createBlockData();
            ladder.setFacing(BlockFace.WEST);
            world.getBlockAt(x1 - 1, y, ladderZ).setBlockData(ladder);
        }
        // 아래층 — 화덕·작업대·수납·조명
        hearth(world, x0 + 1, y0, far);
        world.getBlockAt(x0 + 3, y0 + 1, far).setType(Material.DECORATED_POT);   // 쌀독 1 (v6 ④)
        world.getBlockAt(x0 + 2, y0 + 1, far).setType(Material.CAULDRON);
        world.getBlockAt(x0 + 1, y0 + 1, doorNorth ? far - 1 : far + 1).setType(Material.CRAFTING_TABLE);
        world.getBlockAt(x0 + 1, y0 + 1, near).setType(Material.BARREL);
        world.getBlockAt(x1 - 1, y0 + 1, near).setType(Material.LANTERN);        // 바닥 등롱
        hangingLantern(world, x0 + 4, y0 + 3, z0 + 5);                           // 다락 밑 현수등 (높이 변화)
        world.getBlockAt(x0 + 4, y0 + 1, z0 + 6).setType(Material.BROWN_CARPET); // 방석 깔개
        // 다락 (y+5) — 침상·궤·등롱 (용마루 아래 가운데가 높다)
        world.getBlockAt(x0 + 2, y0 + 5, z0 + 5).setType(Material.WHITE_CARPET); // 침상 2칸
        world.getBlockAt(x0 + 2, y0 + 5, z0 + 6).setType(Material.WHITE_CARPET);
        world.getBlockAt(x0 + 3, y0 + 5, z0 + 6).setType(Material.LIGHT_GRAY_CARPET);
        world.getBlockAt(x0 + 3, y0 + 5, z0 + 5).setType(Material.CHEST);
        world.getBlockAt(x0 + 4, y0 + 5, z0 + 7).setType(Material.LANTERN);      // 다락 등롱
        // v6.8 ① 다락 조명 — 다락은 **벽이 없다**(벽고 4에서 끝나고 그 위는 지붕면). 벽등을 붙일 데가 없으므로
        // 마루 등롱 4점으로 채운다: 등롱 하나로는 다락 끝(맨해튼 9)이 광원 6 이라 몹이 다락에서 스폰됐다.
        world.getBlockAt(x0 + 1, y0 + 5, z0 + 2).setType(Material.LANTERN);
        world.getBlockAt(x0 + 6, y0 + 5, z0 + 3).setType(Material.LANTERN);
        world.getBlockAt(x0 + 1, y0 + 5, z0 + 9).setType(Material.LANTERN);
        world.getBlockAt(x0 + 6, y0 + 5, z0 + 8).setType(Material.LANTERN);
    }

    /**
     * 작업장 병설형 — 본채 12x9(흙벽돌·흑와) + 작업간 7x6(회벽목골·너와, 골목 쪽 별도 문) + 돌담 작업마당.
     * smithy=true 대장간(용광로 화로·모루·대장장이 작업대), false 직조간(베틀 2·베 무더기·궤).
     * 필수 세트: 본채 침상·수납·방석·조명 2단 / 작업간 생업 작업대·수납·등롱.
     */
    private static void workshopHouse(World world, int x0, int y0, int z0,
                                      boolean doorNorth, boolean smithy, boolean poor) {
        int w = 12, d = 9;
        shell(world, x0, y0, z0, w, d, 4, doorNorth, WallStyle.MUD_BRICK,
                poor ? RoofStyle.SHINGLE : RoofStyle.TILE, false,
                WindowStyle.COTTAGE);   // v6.7 ② 벽고 3 → 4
        int x1 = x0 + w - 1, z1 = z0 + d - 1;
        if (poor) {
            thatch(world, x0 - 2, z0 - 2, x1 + 2, z1 + 2, y0 + 5);   // v7.0 ② 초가 (본채)
        }
        int far = doorNorth ? z1 - 1 : z0 + 1;
        int near = doorNorth ? z0 + 1 : z1 - 1;
        // 본채 — 살림 (침상·수납·방석·조명 높이 변화)
        world.getBlockAt(x0 + 1, y0 + 1, far).setType(Material.WHITE_CARPET);    // 침상 2칸
        world.getBlockAt(x0 + 2, y0 + 1, far).setType(Material.WHITE_CARPET);
        world.getBlockAt(x0 + 1, y0 + 1, doorNorth ? far - 1 : far + 1).setType(Material.LIGHT_GRAY_CARPET);
        hearth(world, x0 + 10, y0, far);
        world.getBlockAt(x0 + 9, y0 + 1, far).setType(Material.DECORATED_POT);   // 쌀독 2 (v6 ④)
        world.getBlockAt(x0 + 8, y0 + 1, far).setType(Material.DECORATED_POT);
        world.getBlockAt(x0 + 1, y0 + 1, near).setType(Material.CHEST);          // 수납 + 선반등
        world.getBlockAt(x0 + 1, y0 + 2, near).setType(Material.LANTERN);
        world.getBlockAt(x0 + 10, y0 + 1, near).setType(Material.LANTERN);       // 바닥 등롱
        world.getBlockAt(x0 + 6, y0 + 1, z0 + 4).setType(Material.BROWN_CARPET); // 방석 깔개
        // 작업간 — 본채 동벽에 잇대어 짓는다 (정면 정렬, 골목 쪽 별도 문)
        int sz0 = doorNorth ? z0 : z0 + 3;
        shell(world, x0 + 11, y0, sz0, 7, 6, 4, doorNorth,
                poor ? WallStyle.MUD_BRICK : WallStyle.FRAME_GRAY, RoofStyle.SHINGLE, false,
                1, WindowStyle.NONE);   // 부속채 처마 1칸 · 작업간은 벽으로 막는다
        int sz1 = sz0 + 5;
        if (poor) {
            thatch(world, x0 + 10, sz0 - 1, x0 + 18, sz1 + 1, y0 + 5);   // 초가 (작업간)
        }
        int sFar = doorNorth ? sz1 - 1 : sz0 + 1;
        int sNear = doorNorth ? sz0 + 1 : sz1 - 1;
        if (smithy) {   // 대장간 — 용광로 화로·모루·대장장이 작업대
            world.getBlockAt(x0 + 12, y0 + 1, sFar).setType(Material.BLAST_FURNACE);
            world.getBlockAt(x0 + 13, y0 + 1, sFar).setType(Material.ANVIL);
            world.getBlockAt(x0 + 16, y0 + 1, sFar).setType(Material.SMITHING_TABLE);
            world.getBlockAt(x0 + 16, y0 + 1, sNear).setType(Material.BARREL);   // 숯·철 수납 + 선반등
            world.getBlockAt(x0 + 16, y0 + 2, sNear).setType(Material.LANTERN);
            world.getBlockAt(x0 + 12, y0 + 1, sNear).setType(Material.LANTERN);
        } else {        // 직조간 — 베틀 2·베 무더기·궤
            world.getBlockAt(x0 + 12, y0 + 1, sFar).setType(Material.LOOM);
            world.getBlockAt(x0 + 13, y0 + 1, sFar).setType(Material.LOOM);
            world.getBlockAt(x0 + 16, y0 + 1, sFar).setType(Material.WHITE_WOOL);   // 베 무더기
            world.getBlockAt(x0 + 16, y0 + 1, sNear).setType(Material.CHEST);       // 실·베 궤 + 선반등
            world.getBlockAt(x0 + 16, y0 + 2, sNear).setType(Material.LANTERN);
            world.getBlockAt(x0 + 12, y0 + 1, sNear).setType(Material.LANTERN);
        }
        // 작업마당 — 작업간 동측 돌담 (삽짝은 골목 쪽)
        int frontZ = doorNorth ? sz0 : sz1;
        for (int z = sz0; z <= sz1; z++) {
            world.getBlockAt(x0 + 20, y0 + 1, z).setType(Material.COBBLESTONE_WALL);
        }
        for (int x = x0 + 18; x <= x0 + 19; x++) {
            world.getBlockAt(x, y0 + 1, sz0).setType(Material.COBBLESTONE_WALL);
            world.getBlockAt(x, y0 + 1, sz1).setType(Material.COBBLESTONE_WALL);
        }
        world.getBlockAt(x0 + 19, y0 + 1, frontZ).setType(Material.AIR);   // 삽짝
        world.getBlockAt(x0 + 18, y0 + 1, sz0 + 2).setType(Material.COMPOSTER);
        world.getBlockAt(x0 + 19, y0 + 1, sz0 + 3).setType(Material.BARREL);
    }

    /** 화덕 — 화로 + 부뚜막돌(바닥 잡석). 인테리어 공통 — 밥 짓는 연기가 지붕 위로 오른다 (생활감) */
    private static void hearth(World world, int x, int y0, int z) {
        world.getBlockAt(x, y0, z).setType(Material.COBBLESTONE);
        world.getBlockAt(x, y0 + 1, z).setType(Material.CAMPFIRE);
    }

    // ─── 내부 집기 — 건물마다 생업의 흔적 ───

    /**
     * 의방 13x11 (cx-24..cx-12, cz+8..cz+18) — v6.1 ③ 공간 문법 + v6.3 ⑤ 여백.
     * v6.2 의 약장은 남벽 한 면에 7+3 = 10칸이 몰려 "약장이 벽이 된" 집이었다 (검수 소품 과밀 10 > 3).
     * 약장을 **두 벽으로 쪼갠다**: 시선 축(남벽) 3칸 = 이 집의 정체, 서벽 2칸 = 여벌 약재.
     * 벽 한 면 3점 규칙: 남 3(약장) · 서 3(약장 2 + 약탕기) · 동 3(약탕관·약재 자루·화분) · 북 2(손질상·등).
     * 밀도 등급 = 낮음·정렬 (의방은 정돈된 집이다). 방 중앙과 유문 앵커(x-18, z+13)는 비운다.
     */
    private static void medicineInterior(World world, int cx, int cy, int cz) {
        // v7.0 ③ 15x13 — 벽 x[-25..-11] · z[+6..+18], 실내 13x11 = 143칸(구 99칸). 앵커 = (x-18, z+12).
        // 소품 수는 그대로 두고 **면적만 늘렸다** — village_tiers.md 3.1: 부를 담는 것은 소품이 아니라 여백이다.
        // ── 시선 축(x-18): 북향 문으로 들어와 정면 남벽(z+17 안줄)에 약장 3칸. 축 위엔 그것뿐.
        for (int x = cx - 19; x <= cx - 17; x++) {
            bookshelf(world, x, cy + 1, cz + 17, BlockFace.NORTH, Math.floorMod(cx - x, 3) + 3);
            world.getBlockAt(x, cy + 1, cz + 16).setType(Material.BROWN_CARPET);   // 약장 앞 깔개 (서는 자리)
        }
        // v7.1(1.21.11) — 약장 위 한 켜는 **진짜 시렁**이다. 말린 약재가 눈에 보인다.
        //   자리는 약장 바로 위(cy+2) — 소품 예산(벽면 3점)은 약장 3칸이 이미 다 쓴다. 시렁은
        //   그 위 켜라 검수의 안줄 스캔(y0..지붕)에 걸린다 → 약장 한 칸을 시렁에 내준다(3점 불변).
        world.getBlockAt(cx - 18, cy + 1, cz + 17).setType(Material.SPRUCE_PLANKS);   // 가운데 약장 → 시렁 받침
        shelf(world, cx - 18, cy + 2, cz + 17, Material.SPRUCE_SHELF, BlockFace.NORTH,
                new org.bukkit.inventory.ItemStack(Material.BROWN_MUSHROOM),
                new org.bukkit.inventory.ItemStack(Material.GLOW_BERRIES),
                new org.bukkit.inventory.ItemStack(Material.DRIED_KELP));
        // ── 서벽(x-24 안줄) 3점: 약장 여벌 2칸 + 약탕기. 축을 비껴 있다 (서벽 창은 z+8..+9 — 그 앞을 비운다).
        bookshelf(world, cx - 24, cy + 1, cz + 14, BlockFace.EAST, 4);
        bookshelf(world, cx - 24, cy + 1, cz + 13, BlockFace.EAST, 2);
        world.getBlockAt(cx - 24, cy + 1, cz + 11).setType(Material.BREWING_STAND);
        // ── 북벽(z+7 안줄) 1점: 약재 손질상(작업대 = 소품 아님) + 그 위 등
        world.getBlockAt(cx - 22, cy + 1, cz + 7).setType(Material.CRAFTING_TABLE);
        world.getBlockAt(cx - 22, cy + 2, cz + 7).setType(Material.LANTERN);
        // ── 동벽(x-12 안줄) 3점: 약탕관 + 약재 자루 + 창가 화분. 진료 평상은 그 앞 (깔개 = 소품 아님).
        world.getBlockAt(cx - 13, cy + 1, cz + 11).setType(Material.WHITE_CARPET);
        world.getBlockAt(cx - 13, cy + 1, cz + 12).setType(Material.WHITE_CARPET);
        // v6.8 ① 진료 평상의 자세 — 환자가 눕는 평상 곁에 의원이 앉는 걸상과 맥 짚는 낮은 상.
        //   계단·반 블록은 PROP 집합 밖이라 소품 벽면 예산을 한 점도 쓰지 않는다.
        stool(world, cx - 14, cy + 1, cz + 11, BlockFace.EAST);
        topSlab(world, cx - 14, cy + 1, cz + 12, Material.SPRUCE_SLAB);   // 맥상(脈床)
        world.getBlockAt(cx - 12, cy + 1, cz + 9).setType(Material.CAULDRON);
        world.getBlockAt(cx - 12, cy + 1, cz + 15).setType(Material.BARREL);
        world.getBlockAt(cx - 12, cy + 2, cz + 15).setType(Material.POTTED_POPPY);
    }

    /**
     * 전장 13x11 (cx+12..cx+24, cz+8..cz+18) — v6.1 ③ 공간 문법.
     * 시선 축: 북향 문(x+18)으로 들어와 카운터 너머 정면 남벽(z+17)에 **철창 금고** — 축의 정중앙.
     *   "보여 주되 못 만지게" — 이 집의 정체는 쇠창살이다. 축 위엔 그것 하나뿐.
     * 벽면 3분할 — 하단 금고·전표철 / 중단 카운터 위 촛불·천칭 / 상단 현수등 2.
     * 밀도 등급 = 낮음·정렬 (전장은 흐트러지면 신용이 죽는다). 손님이 서는 자리(z+9..+11)는 비운다.
     */
    private static void exchangeInterior(World world, int cx, int cy, int cz) {
        // v7.0 ③ 15x13 — 벽 x[+11..+25] · z[+6..+18], 실내 13x11. 앵커 = (x+18, z+12).
        // v7.0 ② 부촌 필지 — 굽도리 2단(cy+1·cy+2) + 전돌 바닥. 창은 이미 철창(VAULT)이고 기단은 v6.8 이 놓았다.
        //   굽도리는 창 켜(cy+3·cy+4)를 침범하지 않고, 전돌은 POLISHED_ANDESITE 라 PATH 집합 밖이다.
        kerb(world, cx + 11, cy, cz + 6, cx + 25, cz + 18, 2);
        tiledFloor(world, cx + 11, cy, cz + 6, cx + 25, cz + 18);
        for (int x = cx + 13; x <= cx + 23; x++) {   // 카운터 — 손님(북)과 지점주(남)를 가른다
            world.getBlockAt(x, cy + 1, cz + 11).setType(Material.DARK_OAK_PLANKS);
        }
        // ── 시선 축(x+18): 철창 3칸 + 그 뒤 금고 3칸. 좌우 대칭으로 정렬한다.
        for (int x = cx + 17; x <= cx + 19; x++) {
            world.getBlockAt(x, cy + 1, cz + 16).setType(Material.IRON_BARS);
            world.getBlockAt(x, cy + 2, cz + 16).setType(Material.IRON_BARS);
        }
        world.getBlockAt(cx + 17, cy + 1, cz + 17).setType(Material.BARREL);      // 금고 (하단) — 남벽 3점
        world.getBlockAt(cx + 18, cy + 1, cz + 17).setType(Material.CHEST);       // 축 정중앙 = 금궤
        world.getBlockAt(cx + 19, cy + 1, cz + 17).setType(Material.BARREL);
        // ── 중단(y+2): 카운터 위 네 점 (촛불·은자 단지·천칭·장부등). 카운터는 벽 안줄이 아니라 방 한복판이다
        //    → propScan 의 wallProps 에 한 점도 계상되지 않는다 (벽면 예산 0 소모).
        candles(world, cx + 14, cy + 2, cz + 11, 2, true);                        // 장부는 촛불로 본다
        world.getBlockAt(cx + 15, cy + 2, cz + 11).setType(Material.DECORATED_POT);         // 봉인된 은자 단지
        world.getBlockAt(cx + 21, cy + 2, cz + 11).setType(Material.STONE_PRESSURE_PLATE);   // 천칭 접시
        world.getBlockAt(cx + 23, cy + 2, cz + 11).setType(Material.LANTERN);
        world.getBlockAt(cx + 13, cy + 1, cz + 12).setType(Material.LECTERN);     // 장부 (지점주 자리 곁)
        // ── 동벽(x+24 안줄) 2점: 전표철 (여백 규칙 — 빈 벽이 있어야 있는 것이 보인다)
        bookshelf(world, cx + 24, cy + 1, cz + 13, BlockFace.WEST, 3);   // 반만 채워 '끊어 준 전표'
        bookshelf(world, cx + 24, cy + 2, cz + 13, BlockFace.WEST, 2);
        // ── 서벽(x+12 안줄) 1점: 등 (전장은 흐트러지면 신용이 죽는다 — 서벽은 비운다)
        world.getBlockAt(cx + 12, cy + 2, cz + 14).setType(Material.LANTERN);
        // ── 바닥: 손님줄 깔개 (금서방 앵커 x+18,z+12 와 문 안 동선은 비운다)
        for (int z = cz + 8; z <= cz + 10; z++) {
            world.getBlockAt(cx + 18, cy + 1, z).setType(Material.RED_CARPET);
        }
    }

    /**
     * 의뢰소 13x11 (cx+11..cx+23, cz-17..cz-7) — v6.1 ③ 공간 문법.
     * 시선 축: 남향 문(x+17)으로 들어와 정면 북벽(z-16)에 **게시판 + 독서대** — 일이 걸린 벽.
     *   문서철은 그 좌우로 물러서고, 대기 걸상은 문 쪽 벽을 등진다.
     * 벽면 3분할 — 하단 문서철·독서대 / 중단 게시 목판 / 상단 현수등.
     * 밀도 등급 = 중간 (일이 밀린 관청 — 정돈되었으나 서류가 쌓인다).
     */
    private static void requestOfficeInterior(World world, int cx, int cy, int cz) {
        // v7.0 ③ 15x13 — 벽 x[+11..+25] · z[-18..-6], 실내 13x11. 앵커 = (x+18, z-12) (구 좌표 그대로).
        // ── 시선 축(x+18): 북벽 안줄(z-17) 게시 목판(판재 — 소품 아님) + 그 앞 독서대(의뢰 대장)
        for (int x = cx + 17; x <= cx + 19; x++) {
            world.getBlockAt(x, cy + 2, cz - 17).setType(Material.DARK_OAK_PLANKS);   // 중단 = 게시 목판
        }
        placeWallSign(world, cx + 17, cy + 2, cz - 16, "의뢰 접수", "보수는 선불 없다");
        placeWallSign(world, cx + 19, cy + 2, cz - 16, "현상 수배", "청하현 관아 공동 게시");
        world.getBlockAt(cx + 18, cy + 1, cz - 17).setType(Material.LECTERN);        // 축 정중앙 = 의뢰 대장
        // ── v6.3 ⑤ 북벽 안줄은 3점까지 — 독서대 + 문서철 좌우 한 칸씩.
        //    왼쪽은 꽉(5칸 꽂힘) 오른쪽은 비게(1칸) — "일이 밀려 있다"는 서사는 두 칸으로 충분하다.
        bookshelf(world, cx + 16, cy + 1, cz - 17, BlockFace.SOUTH, 5);
        bookshelf(world, cx + 20, cy + 1, cz - 17, BlockFace.SOUTH, 1);
        // ── 서벽(x+12 안줄) 2점: 서류 궤 + 그 위 등 (서벽 큰 창 z-12..-10 앞은 비운다)
        world.getBlockAt(cx + 12, cy + 1, cz - 14).setType(Material.BARREL);
        world.getBlockAt(cx + 12, cy + 2, cz - 14).setType(Material.LANTERN);
        // ── 동벽(x+24 안줄) 2점: 화분 2 (여백)
        //    v7.0: 건물이 15칸으로 넓어지며 동벽 안줄이 게시대 등롱을 한 점 물었다 → 항아리를 뺀다.
        world.getBlockAt(cx + 24, cy + 1, cz - 16).setType(Material.POTTED_CHERRY_SAPLING);   // 매화의 복선
        world.getBlockAt(cx + 24, cy + 1, cz - 12).setType(Material.POTTED_BAMBOO);
        // ── 남벽(문 쪽 z-7 안줄): 대기 걸상 2 + 깔개 (사람은 문을 등지고 앉아 기다린다 — 전부 소품 예산 0)
        for (int x = cx + 14; x <= cx + 22; x += 8) {
            world.getBlockAt(x, cy + 1, cz - 7).setType(Material.SPRUCE_FENCE);
            world.getBlockAt(x, cy + 2, cz - 7).setType(Material.SPRUCE_PRESSURE_PLATE);
        }
        for (int x = cx + 15; x <= cx + 21; x++) {
            world.getBlockAt(x, cy + 1, cz - 7).setType(Material.LIGHT_GRAY_CARPET);   // 대기석 깔개
        }
    }

    /**
     * v6.8 ② 의뢰소의 정체 = **옥외 게시대와 차양**.
     *
     * <p>구 게시판은 3칸이었고, 하필 의뢰소 앞마당(x+14..+20)을 물고 서 있었다 — 작아서 안 보이고,
     * 서 있는 자리는 사람이 지나야 할 자리였다. v6.8 은 게시대를 **앞마당 동쪽 밖(x+21..+25)** 으로
     * 옮겨 동선을 비우고, 5칸으로 키우고, 그 위에 **가문비 반 블록 차양**을 얹어 처마 없는 옥외 시설로
     * 세운다. 차양 밑에 등롱 두 점 — 밤에도 공고를 읽는다.
     *
     * <p>자재 규칙: 차양은 SPRUCE_SLAB 이다. DARK_OAK_SLAB 은 검수의 ROOF 집합이라 의뢰소의 지붕 상자를
     * 부풀려 처마 겹침 오탐을 낳는다(게시대는 벽에서 2~5칸 — 지붕 스캔 박스 ±8 안이다). 가문비는 그 밖이다.
     */
    private static void bulletinBoard(World world, int cx, int cy, int cz) {
        // v7.0 ③ — 의뢰소가 15칸으로 넓어져 앞마당(x+15..+21)이 동쪽으로 밀렸다. 게시대도 한 칸 물린다:
        //   기둥 x+22..+26 · 차양 x+21..+27. 앞마당 동선(x+15..+21)을 한 칸도 물지 않는다.
        for (int x = cx + 22; x <= cx + 26; x++) {
            world.getBlockAt(x, cy + 1, cz - 5).setType(Material.SPRUCE_FENCE);
            world.getBlockAt(x, cy + 2, cz - 5).setType(Material.DARK_OAK_PLANKS);
            world.getBlockAt(x, cy + 3, cz - 5).setType(Material.DARK_OAK_PLANKS);
        }
        for (int x = cx + 21; x <= cx + 27; x++) {   // 차양 — 게시면보다 앞뒤로 한 칸씩 내민다
            topSlab(world, x, cy + 4, cz - 5, Material.SPRUCE_SLAB);
            topSlab(world, x, cy + 4, cz - 4, Material.SPRUCE_SLAB);
        }
        // 게시면(z-4)의 x+22 · x+25 는 **동서대로 등롱 열**이다(측거 4 · 간격 3 → cx+10,13,16,19,22,25…).
        // 그 칸에 표지판을 붙이면 뒤에 도는 streetLanterns 가 등롱을 못 세워 야간 암흑이 는다 — 비켜 붙인다.
        placeWallSign(world, cx + 23, cy + 2, cz - 4, "의뢰: 북쪽 산길", "정찰 — 보수 상담");
        placeWallSign(world, cx + 24, cy + 2, cz - 4, "구함: 상단 호위", "표국 경력 우대");
        placeWallSign(world, cx + 26, cy + 2, cz - 4, "급구: 약재", "의방 유문 앞");
        hangingLantern(world, cx + 21, cy + 3, cz - 4);   // 차양 밑 등롱 2 (밤에도 공고가 읽힌다)
        hangingLantern(world, cx + 27, cy + 3, cz - 4);
    }

    // ─── v6.8 ② 밖에서 봐도 어느 집인지 — 부속으로 정체를 말한다 ───
    //
    // 관청류 4채(의뢰소·의방·전장)와 객잔은 같은 문법으로 지어졌다: 백벽·흑와·팔작·처마 2칸.
    // 격을 맞추려고 통일한 문법이 **구별을 지웠다** — 조감도에서 넷은 크기만 다른 같은 집이다.
    // 실루엣을 가르는 것은 지붕이 아니라 **부속(附屬)** 이다. 생업의 도구가 집 밖으로 나와 서야 한다:
    //   의방  = 약재 건조대 2줄 + 탕약 굴뚝(연기가 오른다)
    //   전장  = 돌 기단 + 창의 철창 + 앞뜰 낮은 담 (작은 성 — "보여 주되 못 만지게")
    //   의뢰소 = 옥외 게시대 5칸 + 차양 (bulletinBoard — 앞마당 밖으로 옮겨 키웠다)
    //   객잔  = 2층 + 주기 + **부뚜막 굴뚝** (밥 짓는 연기)
    //   표국  = 돌담 마당 + 기단 + 표기 장대 (이미 다르다 — 참조 구현)
    //
    // 세 가지 자재 금기 (v6.5 ③ 과 같은 함정) —
    //   ① 지붕 자재(심층암 계열·흑목 계단/반 블록) 금지: 벽 ±8 안의 지붕 자재는 전부 그 건물의 지붕으로
    //      세어져 처마 겹침·능선·단 평탄도를 오염시킨다. 굴뚝은 BRICKS, 차양은 SPRUCE_SLAB.
    //   ② 노면 자재(조약돌·안산암·돌 벽돌·매끈한 돌) 를 **지면(cy)에 놓지 않는다**: 검수는 cy 의 자재로
    //      길을 판정한다. 전장 기단은 STONE_BRICK_SLAB 을 **cy+1** 에 놓는다 — 반 칸 올라선 댓돌이고,
    //      PATH 집합 밖이라 길 폭·야간 암흑 표본에 한 칸도 보태지 않는다.
    //   ③ 채색 금지 — 벽돌·돌·대나무·짚·흰 무명천뿐 (수묵 2% 예산 불변).
    // 실내 소품 예산도 건드리지 않는다: 여기 놓는 것은 전부 **벽 사각형 밖**이므로 propScan 이 세지 않는다.
    // 이 패스는 길·골목·앞마당을 마지막으로 다시 깐 **뒤에** 돈다 (노면이 부속을 덮지 않게).

    private static void facades(World world, int cx, int cy, int cz) {
        medicineFacade(world, cx, cy, cz);
        exchangeFacade(world, cx, cy, cz);
        innFacade(world, cx, cy, cz);
    }

    /**
     * 의방 — 약재 건조대 2줄(대나무 시렁 + 널어 둔 약초) + 탕약 굴뚝.
     * 서벽(x-24) 처마 끝은 x-26 이므로 건조대는 x-27·x-30, 굴뚝은 x-28 — 전부 처마 그늘 밖에서 읽힌다.
     *
     * <p>【함정 — 검수의 벽 레이캐스트】 TownAudit 은 앵커에서 **probeY = 지면+3** 높이로 사방에 레이를 쏘고
     * "마지막 시선 차단 블록"을 벽 최외곽으로 삼는다(공기 5칸 = 건물 밖). 의방 앵커는 (x-18, z+13) 이므로
     * 서쪽 레이는 **z = cz+13 · y = cy+3** 을 지난다. 여기에 약초 다발(짚단 = 불투과)을 걸면 검수가
     * 건조대를 **의방의 서벽**으로 읽어 벽 최외곽이 x-27·x-30 까지 밀리고, 처마 내밀기가 **음수**가 되어
     * "처마 없음" 위반이 난다. 그래서 짚단은 **짝수 열(z+12·+14·+16)** 에만 건다 — 레이의 z+13 은 비운다.
     * 시렁(대나무 울타리)은 cy+1·cy+2 라 probeY 를 지나지 않고, 애초에 불투과 블록도 아니다.
     */
    private static void medicineFacade(World world, int cx, int cy, int cz) {
        // v7.0 ③ — 의방이 x-25 까지 나오면서 처마 끝이 x-27 이 됐다. 건조대·굴뚝을 두 칸 서쪽으로 물린다.
        // ★ 앵커 행이 z+13 → **z+12** 로 바뀌었다. 짚단(불투과)이 그 행에 걸리면 검수의 서쪽 레이가
        //   건조대를 '의방의 서벽'으로 읽어 처마가 음수가 된다 → 짚단은 **홀수 열**에만 건다 (z+12 를 비운다).
        for (int x : new int[]{cx - 28, cx - 31}) {   // 건조대 2줄 — 대나무 시렁 2단 + 약초 다발
            for (int z = cz + 9; z <= cz + 16; z++) {
                world.getBlockAt(x, cy + 1, z).setType(Material.BAMBOO_FENCE);
                world.getBlockAt(x, cy + 2, z).setType(Material.BAMBOO_FENCE);
                if (Math.floorMod(z - cz, 2) == 1) {
                    world.getBlockAt(x, cy + 3, z).setType(Material.HAY_BLOCK);   // 널어 말리는 약초 (앵커 행 z+12 는 비운다)
                }
            }
        }
        for (int y = cy + 1; y <= cy + 6; y++) {      // 탕약 굴뚝 — 벽돌 6단
            world.getBlockAt(cx - 29, y, cz + 7).setType(Material.BRICKS);
        }
        world.getBlockAt(cx - 29, cy + 7, cz + 7).setType(Material.CAMPFIRE);     // 굴뚝 연기 (탕약을 달인다)
        world.getBlockAt(cx - 30, cy + 1, cz + 7).setType(Material.CAULDRON);     // 약탕관
        world.getBlockAt(cx - 30, cy + 1, cz + 8).setType(Material.BARREL);       // 약재 통
        Orientable log = (Orientable) Material.OAK_LOG.createBlockData();
        log.setAxis(Axis.Z);
        world.getBlockAt(cx - 31, cy + 1, cz + 7).setBlockData(log);              // 장작 한 토막
    }

    /**
     * 전장 — 돌 기단(반 칸 댓돌) + 창의 철창 + 앞뜰 낮은 담. "보여 주되 못 만지게".
     * 남변(z+19)은 남골목이므로 기단을 두르지 않는다 (골목 폭 3칸 불변).
     */
    private static void exchangeFacade(World world, int cx, int cy, int cz) {
        // v7.0 ③ — 전장이 x[+11..+25] · z[+6..+18] 로 커졌다. 기단은 벽 바깥 한 바퀴(x+10 · x+26 · z+5).
        // v7.0 ② 부촌 필지 — 기단(반 칸 댓돌)에 **석등 2기**를 더한다. 굽도리·전돌은 exchangeInterior 의 몫.
        for (int z = cz + 5; z <= cz + 19; z++) {   // 서·동 기단 — cy+1 반 칸 댓돌 (PATH 자재 아님)
            plinth(world, cx + 10, cy + 1, z);
            plinth(world, cx + 26, cy + 1, z);
        }
        for (int x = cx + 10; x <= cx + 14; x++) {  // 정면 기단 — 앞마당(x+15..+21)은 비운다
            plinth(world, x, cy + 1, cz + 5);
        }
        for (int x = cx + 22; x <= cx + 26; x++) {
            plinth(world, x, cy + 1, cz + 5);
        }
        for (int x = cx + 11; x <= cx + 25; x++) {  // 철창 — 전장의 창은 유리가 아니라 쇠살이다
            for (int z = cz + 6; z <= cz + 18; z++) {
                boolean wall = x == cx + 11 || x == cx + 25 || z == cz + 6 || z == cz + 18;
                if (!wall) {
                    continue;
                }
                for (int y = cy + 1; y <= cy + 5; y++) {
                    if (world.getBlockAt(x, y, z).getType() == Material.GLASS_PANE) {
                        world.getBlockAt(x, y, z).setType(Material.IRON_BARS);
                    }
                }
            }
        }
        stoneLamp(world, cx + 9, cy, cz + 5);      // 석등 — 처마(x+9·x+27) 끝 밑, 앞마당 동선 밖
        stoneLamp(world, cx + 27, cy, cz + 5);
    }

    /**
     * 기단 한 칸 — 빈 칸(또는 잡초)에만. 활주 울타리·등롱·소품은 밀어내지 않는다.
     * 이 패스는 weeds() 뒤에 도므로 잡초는 걷어내야 기단이 끊기지 않는다.
     */
    private static void plinth(World world, int x, int y, int z) {
        Material m = world.getBlockAt(x, y, z).getType();
        if (m.isAir() || m == Material.SHORT_GRASS || m == Material.FERN) {
            world.getBlockAt(x, y, z).setType(Material.STONE_BRICK_SLAB);
        }
    }

    /**
     * 객잔 — 부뚜막 굴뚝. 주청 서벽 화덕(x-31)의 연기가 **처마 밖**(x-35)으로 솟는다.
     * v7.0 ③ — 객잔이 x-32 까지 나오면서 처마 끝이 x-34 가 됐다. 굴뚝을 x-35 로 물린다:
     *   ㉮ 지붕 상자(x-34..) 밖 → 처마 겹침 오탐 0 (굴뚝은 BRICKS 라 ROOF 집합 밖이지만, 처마 밑에 세우면 뚫린다)
     *   ㉯ 앵커 행(z-13)을 피해 z-16 에 세운다 → 검수의 서쪽 레이가 굴뚝을 '객잔의 벽'으로 읽지 않는다
     */
    private static void innFacade(World world, int cx, int cy, int cz) {
        for (int y = cy + 1; y <= cy + 8; y++) {
            world.getBlockAt(cx - 35, y, cz - 16).setType(Material.BRICKS);
        }
        world.getBlockAt(cx - 35, cy + 9, cz - 16).setType(Material.CAMPFIRE);   // 밥 짓는 연기
        for (int dz = 0; dz <= 1; dz++) {   // 굴뚝 발치 장작더미 (cy+1·cy+2 — probeY(cy+3)를 물지 않는다)
            Orientable log = (Orientable) Material.OAK_LOG.createBlockData();
            log.setAxis(Axis.Z);
            world.getBlockAt(cx - 35, cy + 1, cz - 18 + dz).setBlockData(log);
            world.getBlockAt(cx - 35, cy + 2, cz - 18 + dz).setBlockData(log);
        }
        world.getBlockAt(cx - 36, cy + 1, cz - 18).setType(Material.BARREL);
    }

    // ─── 장터 — 노점 5개, 차양 5색 ───

    private static void marketStalls(World world, int cx, int cy, int cz) {
        stall(world, cx + 7, cy, cz - 5, 1, Material.RED_WOOL);       // 붉은 차양 — 가죽 매입 (장터 반경 15 내)
        stall(world, cx + 11, cy, cz - 5, 1, Material.YELLOW_WOOL);
        stall(world, cx + 7, cy, cz + 5, -1, Material.LIME_WOOL);
        stall(world, cx + 11, cy, cz + 5, -1, Material.LIGHT_BLUE_WOOL);
        stall(world, cx - 36, cy, cz - 5, 1, Material.ORANGE_WOOL);   // 서시(西市) 외톨이 노점 (객잔 서편)
        placeSign(world, cx + 6, cy + 1, cz - 3, BlockFace.WEST, "장터", "가죽 매입 — /혼천 팔기");   // 독자 = 광장 쪽
        // v6 ⑤ — 붉은 차양 좌판 곁 살림: 됫박(호퍼)·접시저울·널어 둔 가죽. 장쇠 스폰 (cx+8,cz-6) 은 비운다.
        world.getBlockAt(cx + 7, cy + 1, cz - 6).setType(Material.HOPPER);
        world.getBlockAt(cx + 9, cy + 1, cz - 6).setType(Material.STONE_PRESSURE_PLATE);
        world.getBlockAt(cx + 10, cy + 1, cz - 5).setType(Material.BROWN_CARPET);
        world.getBlockAt(cx + 10, cy + 1, cz - 4).setType(Material.BROWN_CARPET);
        placeSign(world, cx + 6, cy + 1, cz - 6, BlockFace.WEST,
                "가죽·부산물 삽니다", "시세보다 싸게, 정직하게");   // 등록된 disposition 문구 재사용
        generalStore(world, cx, cy, cz);
    }

    /**
     * v6.2 ⑤ 처마 겹침의 뿌리 — 검수(TownAudit)는 앵커에서 레이캐스트로 벽을 찾고 "공기 5칸 = 건물 밖"으로 끊는다.
     *   구 잡화점(z-18..-10)은 의뢰소 앵커 행(z-12)을 가로질러 서 있었고, 잡화점 동벽(x+8)과 의뢰소 서벽(x+11)
     *   사이는 공기 2칸뿐이라 **두 집이 한 채로 읽혔다** → 의뢰소의 지붕 상자가 광장 우물까지 삼켜 객잔과 교차했다.
     *   잡화점을 z[-18..-13] 로 줄여 의뢰소 앵커 행에서 비켜세운다: 레이는 이제 서쪽으로 뚫려 나가 벽을 x+11 에서 끊는다.
     *   앵커·NPC·붉은 차양·장터 반경 15 는 불변. 점포는 6칸 깊이로 줄되 점두 3칸 개방은 유지한다.
     *
     * v6 ⑤ 장터 잡화점 — 점포 5(x) x 6(z), 북로·광장 쪽(서향) 전면 3칸 개방 + 젖힌 덧문.
     * v6.1 ①④ 부지 재검산: 남북대로가 7칸(x±3)이 되고 의뢰소 처마가 x+9 까지 나오면서
     *   구 부지 x[cx+2..cx+8] 은 대로 갓길을 물고 들어간다 → x[cx+4..cx+8] · z[cz-18..cz-10] 로 물렸다.
     *   좁은 부지라 처마는 서 1칸(대로 쪽 — 점두 그늘)·동 0칸(의뢰소 처마와 맞닿는 면)·남북 1칸.
     *   처마 위계상으로도 옳다: 잡화점은 마을에서 가장 낮은 격의 점포다.
     * 붉은 차양 노점(cx+7,cz-5)·장쇠 스폰(cx+8,cz-6)·장터 앵커 반경 15 는 손대지 않는다 (매각 규칙 계약 불변).
     */
    private static void generalStore(World world, int cx, int cy, int cz) {
        int x0 = cx + 4, x1 = cx + 8, z0 = cz - 18, z1 = cz - 14;   // v6.3 ③ — 깊이 6→5
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
                boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                boolean rim = x == x0 + 1 || x == x1 - 1 || z == z0 + 1 || z == z1 - 1;
                world.getBlockAt(x, cy, z).setType(
                        !wall && rim ? Material.DARK_OAK_PLANKS : Material.SPRUCE_PLANKS);   // 마루 + 귀틀
                for (int y = cy + 1; y <= cy + 3; y++) {
                    if (corner) {
                        world.getBlockAt(x, y, z).setType(Material.DARK_OAK_LOG);
                    } else if (!wall) {
                        world.getBlockAt(x, y, z).setType(Material.AIR);
                    } else if (y == cy + 3) {
                        world.getBlockAt(x, y, z).setType(Material.DARK_OAK_PLANKS);          // 상인방
                    } else if (Math.floorMod(x + z, 3) == 0) {
                        world.getBlockAt(x, y, z).setType(Material.DARK_OAK_PLANKS);          // 목골 스터드
                    } else {
                        world.getBlockAt(x, y, z).setType(Material.LIGHT_GRAY_TERRACOTTA);    // 회벽
                    }
                }
            }
        }
        // v6.9 ③ 잡화점의 창 — 정면(서)이 이미 3칸 통째로 열린 점두다. 창을 더 낼 이유가 없다:
        //   남측 벽에 세살창 한 짝만 (벽고 2 = 창 켜 cy+2 한 켜. 이 집은 앵커가 아니라 probeY 제약이 없다).
        windowUnit(world, x0, cy, z0, x1, z1, 1, x0 + 2, x0 + 3, cy + 2, cy + 2, Pane.SASH);
        for (int z = cz - 17; z <= cz - 15; z++) {   // 전면 3칸 개방 (셔터 없는 점두) + 젖힌 덧문
            world.getBlockAt(x0, cy + 1, z).setType(Material.AIR);
            world.getBlockAt(x0, cy + 2, z).setType(Material.AIR);
            awningTrapdoor(world, x0, cy + 3, z, BlockFace.WEST);
            steppingStone(world, x0 - 1, cy, z);   // 점두 앞 디딤돌 (대로 갓길 → 문지방 전이)
        }
        // v6.5 ① — 용마루 뿔은 전 건물에서 폐기됐다 (지붕 사각형 밖으로 나가 이웃 검수 단면에 얹혔다).
        //   잡화점 지붕은 이제 구조적으로 z ≤ cz-13 안에 갇히고, 의뢰소 검수 단면은 제 처마에서 시작한다.
        roofShape(world, x0 - 1, z0 - 1, x1, z1 + 1, cy + 4,   // 맞배 + 박공. 동면 처마 0 = 의뢰소 처마와 이웃
                RoofStyle.TILE, Material.DARK_OAK_PLANKS, 0);
        hangingSign(world, x0 - 1, cy + 3, cz - 16, BlockFace.WEST, "장쇠네 잡화", "잡화 — 되는 대로 다 있다");
        hangingLantern(world, x0 - 1, cy + 3, cz - 18);   // 처마 밑 등롱 (밤에도 점두가 읽힌다)
        generalStoreInterior(world, cx, cy, cz, x0, x1);
    }

    /**
     * 잡화점 실내 (x+5..x+7 · z-17..z-15) — v6.1 ③ 벽면 3분할·시선 축. v6.3 ③ 으로 3x3 으로 줄었다.
     * 시선 축: 서쪽 점두로 들어오면 정면(동벽 안줄 x+7)에 잡동사니 시렁 = 이 집의 정체.
     * 서열 = 점두 좌판(죽렴 판재 — 리소스팩 재텍스처), 동열 = 시렁, 가운데 열(x+6)은 비운다.
     */
    private static void generalStoreInterior(World world, int cx, int cy, int cz, int x0, int x1) {
        int back = x1 - 1;   // 동벽 안줄 = 시선 축의 끝
        for (int z = cz - 17; z <= cz - 15; z++) {
            topSlab(world, back, cy + 2, z, Material.DARK_OAK_SLAB);               // 중단 = 선반 한 줄 (정렬)
            world.getBlockAt(x0 + 1, cy + 1, z).setType(Material.BAMBOO_PLANKS);   // 점두 좌판 (문을 등지고 앉는 자리)
        }
        world.getBlockAt(back, cy + 1, cz - 17).setType(Material.BARREL);          // 하단 3점 (여백 규칙)
        world.getBlockAt(back, cy + 1, cz - 16).setType(Material.CHEST);
        world.getBlockAt(back, cy + 1, cz - 15).setType(Material.DECORATED_POT);
        world.getBlockAt(back, cy + 3, cz - 17).setType(Material.LANTERN);         // 상단 = 선반 위 등
        world.getBlockAt(back, cy + 3, cz - 15).setType(Material.HAY_BLOCK);       // 상단 = 선반 위 잡동사니
        Orientable chain = (Orientable) Material.IRON_CHAIN.createBlockData();
        chain.setAxis(Axis.Y);
        world.getBlockAt(back, cy + 3, cz - 16).setBlockData(chain);               // 끈 대용 사슬 1칸
        world.getBlockAt(x0 + 1, cy + 2, cz - 16).setType(Material.DECORATED_POT); // 좌판 위 단지
    }

    /** 노점 한 채 — 기둥 2주 + 차양 3x2 + 좌판(술통). toward = 도로 쪽 z 방향(+1/-1) */
    private static void stall(World world, int x, int cy, int z, int toward, Material awning) {
        for (int y = cy + 1; y <= cy + 2; y++) {
            world.getBlockAt(x, y, z).setType(Material.SPRUCE_FENCE);
            world.getBlockAt(x + 2, y, z).setType(Material.SPRUCE_FENCE);
        }
        for (int dx = 0; dx <= 2; dx++) {
            world.getBlockAt(x + dx, cy + 3, z).setType(awning);
            world.getBlockAt(x + dx, cy + 3, z + toward).setType(awning);
        }
        world.getBlockAt(x + 1, cy + 1, z).setType(Material.BARREL);
    }

    // ─── 공용 ───

    private static void hangingLantern(World world, int x, int y, int z) {
        Lantern data = (Lantern) Material.LANTERN.createBlockData();
        data.setHanging(true);
        world.getBlockAt(x, y, z).setBlockData(data);
    }

    // ─── v6.8 ① 실내 조명 — 어두운 실내는 몹의 집이다 ───
    //
    // 지금까지 실내 광원은 '소품'이었다: 랜턴·양초는 전부 TownAudit 의 PROP 집합에 들어 있어
    // 벽면당 3점 규칙과 경쟁했다. 그래서 조명을 늘리면 과밀 위반이 나고, 규칙을 지키면 방이 캄캄했다
    // (관청 실내 한복판의 블록 광원 ≈ 4~6 → 몹이 집 안에서 스폰된다).
    //
    // 해법은 **소품이 아닌 광원**이다. WALL_TORCH 는
    //   PROP 집합에 없다   → 소품 벽면 ≤3 에 한 점도 계상되지 않는다
    //   ROOF 집합에 없다   → 처마·능선·단 평탄도에 영향 0
    //   PATH 집합에 없다   → 길 판정·야간 암흑 표본에 영향 0
    //   isColored 아니다   → 채색 2% 예산에 영향 0
    //   isOccluding 아니다 → 검수의 벽 레이캐스트(blocking)가 벽으로 오인하지 않는다
    // 즉 **검수 12종 중 어느 것도 건드리지 않고** 실내를 밝힐 수 있는 유일한 자재다.
    //
    // 배치 규칙(roomLights): 내벽 안줄(벽+1)을 따라 간격 4·양끝에서 3 안쪽. 벽등은 바닥에서 2칸 위(y0+3)
    // 이므로 광원 15 − (평면거리 + 2) ≥ 8 ⟺ 평면거리 ≤ 5. 관청급(11x9 실내)은 링만으로 한복판이
    // 7 로 떨어지므로 **대들보 현수등 2점**(x 중앙 · z0+3 / z1-3)을 더해 하한을 8 로 올린다.
    // 현수등은 벽 안줄이 아닌 방 한가운데 열에 걸리므로 wallProps 에 계상되지 않는다 (propScan 은
    // x==wx0+1 / x==wx1-1 / z==wz0+1 / z==wz1-1 인 칸만 벽면으로 센다).

    /**
     * 벽등 한 점 — 빈 칸이고 등을 붙일 벽이 단단할 때만 선다.
     * facing = 횃불이 향하는 방향(벽에서 방 안쪽). 받침 벽은 그 반대편 칸이다.
     */
    private static void wallTorch(World world, int x, int y, int z, BlockFace facing) {
        if (!world.getBlockAt(x, y, z).getType().isAir()) {
            return;   // 소품·가구가 이미 섰다 — 벽등이 밀어내지 않는다
        }
        Material support = world.getBlockAt(x - facing.getModX(), y, z - facing.getModZ()).getType();
        if (!support.isOccluding()) {
            return;   // 유리창·문 개구 — 붙일 벽이 없다
        }
        Directional data = (Directional) Material.WALL_TORCH.createBlockData();
        data.setFacing(facing);
        world.getBlockAt(x, y, z).setBlockData(data);
    }

    /** 실내 벽등 링 — 내벽 안줄, 간격 4. (x0,z0)-(x1,z1) 은 벽 사각형, y 는 벽등 높이(= 바닥+2) */
    private static void roomLights(World world, int x0, int y, int z0, int x1, int z1) {
        for (int x = x0 + 3; x <= x1 - 3; x += 4) {
            wallTorch(world, x, y, z0 + 1, BlockFace.SOUTH);
            wallTorch(world, x, y, z1 - 1, BlockFace.NORTH);
        }
        for (int z = z0 + 3; z <= z1 - 3; z += 4) {
            wallTorch(world, x0 + 1, y, z, BlockFace.EAST);
            wallTorch(world, x1 - 1, y, z, BlockFace.WEST);
        }
    }

    /**
     * 걸상 — 사람이 **머무는 자세**. 계단 블록은 PROP·ROOF·PATH 어디에도 없다 (소품 예산 0).
     * 빈 칸에만 놓는다 (탁자·깔개·동선 축을 밀어내지 않는다).
     */
    private static void stool(World world, int x, int y, int z, BlockFace facing) {
        if (!world.getBlockAt(x, y, z).getType().isAir()) {
            return;
        }
        stair(world, x, y, z, Material.SPRUCE_STAIRS, facing);
    }

    /** 탁자를 사이에 두고 남·북으로 마주 앉는 걸상 한 쌍 */
    private static void stoolPair(World world, int x, int y, int z) {
        stool(world, x, y, z - 1, BlockFace.SOUTH);
        stool(world, x, y, z + 1, BlockFace.NORTH);
    }

    /** 냉색 현수 등롱 — 폐사당 전용 (마을 안 사용 금지, 가이드 1.5) */
    private static void soulLantern(World world, int x, int y, int z, boolean hanging) {
        Lantern data = (Lantern) Material.SOUL_LANTERN.createBlockData();
        data.setHanging(hanging);
        world.getBlockAt(x, y, z).setBlockData(data);
    }

    /**
     * 현수 등롱 — 사슬 len 칸 + 랜턴(hanging). 처마·인방 아래는 사슬 현수가 우선 (등롱은 걸어야 등롱이다).
     * yTop = 사슬이 매달릴 첫 칸 (그 아래로 len-1 칸 사슬 + 랜턴).
     */
    private static void chainLantern(World world, int x, int yTop, int z, int len) {
        for (int i = 0; i < len; i++) {
            Orientable chain = (Orientable) Material.IRON_CHAIN.createBlockData();
            chain.setAxis(Axis.Y);
            world.getBlockAt(x, yTop - i, z).setBlockData(chain);
        }
        hangingLantern(world, x, yTop - len, z);
    }

    /** 현판·주기(酒旗) — 처마 밑 매다는 표지판. face = 글면이 향하는 방향(독자가 서는 쪽) */
    private static void hangingSign(World world, int x, int y, int z, BlockFace face,
                                    String line1, String line2) {
        Block block = world.getBlockAt(x, y, z);
        HangingSign data = (HangingSign) Material.DARK_OAK_HANGING_SIGN.createBlockData();
        data.setRotation(face);
        data.setAttached(false);   // 사슬로 매단 형태 (윗 블록 = 처마)
        block.setBlockData(data);
        writeSign(block, line1, line2);
    }

    /**
     * 조각된 책장 — 약장·전표철·문서철·경전 시렁. filled = 앞줄부터 꽂힌 칸 수(0~6, 상수 고정).
     * "꽂힌 책 수까지 상수" — 난수 금지 규정의 소품 판 (가이드 1.6).
     */
    private static void bookshelf(World world, int x, int y, int z, BlockFace facing, int filled) {
        ChiseledBookshelf data = (ChiseledBookshelf) Material.CHISELED_BOOKSHELF.createBlockData();
        data.setFacing(facing);
        for (int slot = 0; slot < 6; slot++) {
            data.setSlotOccupied(slot, slot < filled);
        }
        world.getBlockAt(x, y, z).setBlockData(data);
    }

    /**
     * 시렁 — 1.21.11 이 준 진짜 선반(_SHELF). 벽에 붙어 <b>물건 셋을 얹는다</b>.
     *
     * <p>이주의 값어치가 여기 있다. 지금까지 병장기 시렁은 "계단 + 반 블록"의 흉내였고, 걸린 병기는
     * 없었다 — 표국이 무엇으로 먹고사는 집인지 문턱에서 읽히지 않았다. 이제 진철산의 창이 벽에 걸린다.
     *
     * <p>자리 규칙: <b>벽 판(벽 그 자체)에 박는다</b>. 실내 안줄에 세우면 소품 예산(검수 ④, 벽면 3점)을
     * 먹는데, 시렁은 장식이 아니라 그 집의 정체다 — 예산이 시렁을 밀어내면 순서가 거꾸로다.
     * (검수의 propScan 은 벽 안쪽만 훑는다 — 벽 판은 세지 않는다.)
     *
     * @param facing 시렁이 바라보는 쪽 = 사람이 서서 보는 쪽 (실내 방향)
     * @param items  얹을 물건 최대 3 (모자라면 빈 칸 — 빈 칸도 서사다: 팔려나간 자리)
     */
    private static void shelf(World world, int x, int y, int z, Material wood,
                              BlockFace facing, org.bukkit.inventory.ItemStack... items) {
        org.bukkit.block.data.type.Shelf data =
                (org.bukkit.block.data.type.Shelf) wood.createBlockData();
        data.setFacing(facing);
        world.getBlockAt(x, y, z).setBlockData(data);
        // 물건은 **스냅샷 인벤토리**에 담고 되쓴다. 라이브 인벤토리에 곧장 넣으면 조성 중(콘솔 실행 ·
        // 청크가 막 실린 참)에는 조용히 사라진다 — 시렁이 빈 채로 선다 (Items: [] 를 검수가 봤다).
        if (world.getBlockAt(x, y, z).getState() instanceof org.bukkit.block.Shelf state) {
            for (int slot = 0; slot < Math.min(items.length, 3); slot++) {
                if (items[slot] != null) {
                    state.getSnapshotInventory().setItem(slot, items[slot]);
                }
            }
            state.update(true, false);
        }
    }

    /** 양초 — count 1~4묶음. lit=false 면 꺼진 양초 (폐사당 제단 — 아무도 불을 붙이지 않았다) */
    private static void candles(World world, int x, int y, int z, int count, boolean lit) {
        Candle data = (Candle) Material.CANDLE.createBlockData();
        data.setCandles(count);
        data.setLit(lit);
        world.getBlockAt(x, y, z).setBlockData(data);
    }

    /** 젖힌 덧문 — 점두 개구 상단에 들어 올린 다락문 (낮의 점포는 열려 있다) */
    private static void awningTrapdoor(World world, int x, int y, int z, BlockFace facing) {
        TrapDoor data = (TrapDoor) Material.SPRUCE_TRAPDOOR.createBlockData();
        data.setFacing(facing);
        data.setHalf(Bisected.Half.TOP);
        data.setOpen(true);
        world.getBlockAt(x, y, z).setBlockData(data);
    }

    /** 젖힌 반 블록 선반 — 윗 절반 슬래브(소품이 그 위에 앉는다: 항아리 시렁·약장 최상단) */
    private static void topSlab(World world, int x, int y, int z, Material mat) {
        Slab data = (Slab) mat.createBlockData();
        data.setType(Slab.Type.TOP);
        world.getBlockAt(x, y, z).setBlockData(data);
    }

    /** 계단 한 칸 — 기단 진입·걸상·갓돌 처마 등 (facing = 오르는 방향) */
    private static void stair(World world, int x, int y, int z, Material mat, BlockFace facing) {
        Stairs data = (Stairs) mat.createBlockData();
        data.setFacing(facing);
        world.getBlockAt(x, y, z).setBlockData(data);
    }

    /** 입간판 — face = 글면이 향하는 방향(독자가 서는 쪽). F30: 기본 회전이 뒤집혀 보이던 버그 */
    private static void placeSign(World world, int x, int y, int z, BlockFace face,
                                  String line1, String line2) {
        Block block = world.getBlockAt(x, y, z);
        Rotatable data = (Rotatable) Material.OAK_SIGN.createBlockData();
        data.setRotation(face);
        block.setBlockData(data);
        writeSign(block, line1, line2);
    }

    private static void placeWallSign(World world, int x, int y, int z, String line1, String line2) {
        placeWallSign(world, x, y, z, BlockFace.SOUTH, line1, line2);   // 게시판 정면(남쪽)을 향한다
    }

    /** 벽 부착 표지판 — face = 글면이 향하는 방향. 붙는 벽은 그 반대편 칸이어야 한다 (안 그러면 떨어진다) */
    private static void placeWallSign(World world, int x, int y, int z, BlockFace face,
                                      String line1, String line2) {
        Block block = world.getBlockAt(x, y, z);
        Directional data = (Directional) Material.OAK_WALL_SIGN.createBlockData();
        data.setFacing(face);
        block.setBlockData(data);
        writeSign(block, line1, line2);
    }

    private static void writeSign(Block block, String line1, String line2) {
        if (block.getState() instanceof Sign sign) {
            sign.getSide(Side.FRONT).setLine(1, line1);
            sign.getSide(Side.FRONT).setLine(2, line2);
            sign.update();
        }
    }

    /** F30 — yaw: 몸·시선 방향 (0=남, 90=서, 180=북, 270=동). AI off 라 스폰 방향이 곧 시선이다 */
    private static void npc(World world, Location at, float yaw, String name) {
        Location spawn = at.clone();
        spawn.setYaw(yaw);
        Villager v = (Villager) world.spawnEntity(spawn, EntityType.VILLAGER);
        v.setCustomName(name);
        v.setCustomNameVisible(true);
        v.setAI(false);            // MVT — 일과 스케줄 배선 전까지 제자리 (npc_lifecycle는 후속)
        v.setInvulnerable(true);
        v.setPersistent(true);
        v.setSilent(true);
    }

    private static Location loc(World world, int x, int y, int z) {
        return new Location(world, x + 0.5, y, z + 0.5);
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    //  v6.9 — 마을 밖 등록 장소. 등록부(config/regions/cheongha_hyeon.yml)에 이름이 있는데
    //  실물이 없던 두 곳을 세운다: 북쪽 산길(사냥터)·흑수나루.
    //
    //  【검수 좌표계 — 왜 새 구조물이 검수를 건드리지 않는가】
    //  TownAudit 의 스캔 창은 전부 유한하다: 볼륨(채색·냉색) ±65 · 길 격자(대로·야간 광원) ±65 ·
    //  담 밖 접근부 링 62~88 · 계약(NPC) ±65 · 냉색 격리는 **폐사당 구역 상자 ±4**.
    //  → 마을 중심에서 체비셰프 거리 **89 이상**에 지은 것은 어떤 항목의 자에도 닿지 않는다.
    //  사냥터(≥92)·흑수나루(≥100)를 그 밖에 앉힌 이유가 이것이다. 검수 12종은 v6.8 값 그대로.
    //  대신 담 밖 채움(⑩)은 **바닥 기준(≥5%)**이라 늘어나도 위반이 아니다 — 나루 이정표 한 점만
    //  링 안(d=86)에 세워 채움을 소폭 올린다.
    //
    //  【재조성 결정론 — 자재가 곧 계약이다】
    //  naturalGroundY/outsideGroundY 는 **자연 지면 화이트리스트(NATURAL_GROUND)** 에 처음 닿는 y 를
    //  돌려준다. 그러므로 지면 **위에** 얹는 것은 전부 화이트리스트 **밖** 자재여야 한다 —
    //  안 그러면 조성할 때마다 바위가 한 칸씩 자란다. STONE·ANDESITE·GRAVEL·DEEPSLATE 는 전부
    //  화이트리스트 **안**이므로 바위에 쓰지 않는다. 바위 자재는 COBBLESTONE·MOSSY_COBBLESTONE·
    //  COBBLED_DEEPSLATE·STONE_BRICKS 계열뿐 (v6.7 stonePile 이 COBBLESTONE_WALL 만 쓴 것과 같은 이유).
    //  지면 **자리**를 갈아끼우는 것(PODZOL·COARSE_DIRT)은 화이트리스트 안이어도 안전하다 — y 가 안 변한다.
    // ══════════════════════════════════════════════════════════════════════════════════════════

    // ─── v6.9 ① 북쪽 산길 — 사냥터 ───
    //
    // 등록부 north_road: "도적 증가, 상단 호위, 녹림 매복". 봇의 /혼천 사냥 · HuntListener 가 가리키는
    // 성장·경제의 기둥인데 v6.8 까지는 앵커 한 점과 평지뿐이었다. v6.7 의 산길은 담 밖 32칸(d=92)에서
    // 그냥 끊겼다 — 길이 아무 데도 닿지 않았다.
    //
    // 무대: 산길 끝에서 이어지는 분지. 타원 (반폭 32 x 반깊이 30), 중심 (cx-10, cz-122).
    //   지형에 순응한다 — 평탄화 0. 바위·개울·성긴 침엽수림을 **얹기만** 한다.
    //   ㉮ 동선 — 산길을 d=92 → d=100 까지 잇고(등롱은 d≤96 에서 끝난다) 그 끝에 사냥꾼 야영터를
    //      놓는다. 야영터부터는 등불이 없다: 1칸 폭 발자국길이 개울을 건너(널다리) 사당 터까지
    //      가다 흐지부지 끊긴다. **불이 끝나는 곳부터가 사냥터다.**
    //   ㉯ 조명 — 온색뿐(횃불·랜턴·모닥불), 그것도 산길과 야영터에만. 숲 속 블록 광원 0 =
    //      밤에는 몹이 스폰되고 낮에는 사냥터다. 냉색(영혼 계열) 0 — 그것은 폐사당의 몫이다.
    //   ㉰ 스폰 — 바닥은 풀·포드졸·거친 흙(불투명 풀 블록) + 그 위 2칸 개방 = 스폰 가능 지면.
    //      숲지붕이 하늘빛을 끊고 광원을 하나도 두지 않으므로 야간 블록 광원 0 (1.18+ 적대 몹 조건).
    //      늑대·여우는 **생물군계 게이트**가 걸린 수동 스폰이라 지형만으론 안 온다 → 분지의 생물군계를
    //      TAIGA 로 못 박고(setBiome — 결정론) 초기 개체군을 씨 뿌린다(재조성 시 먼저 걷어낸다).
    //   ㉱ 위험의 흔적 (north_road_bandits) — 버려진 수레·나무에 박힌 화살·무너진 돌담·꺼진 모닥불·
    //      낡은 사당 터. 폐사당이 아니다: 냉광 0 · 규모 5x5 · 길가 사당의 잔해다.

    private static final int HG_X = -10;      // 사냥터 중심 (마을 중심 기준)
    private static final int HG_Z = -122;
    private static final int HG_A = 32;       // 타원 반폭 (x)
    private static final int HG_B = 30;       // 타원 반깊이 (z)
    private static final int HG_TRAIL_END = 100;   // 산길 연장 끝 (담 밖 d)
    private static final int HG_LAMP_END = 96;     // 등롱이 끝나는 d — 그 너머는 어둠이 사냥터다

    /** 개울 중심선 길이 (셀 수) */
    private static final int CREEK_N = 46;

    /** 바위 무더기 — {dx, dz, 반경} 사냥터 중심 기준 (난수 0) */
    private static final int[][] HG_CRAGS = {
            {-24, -18, 3}, {-6, -24, 2}, {14, -20, 4}, {26, -8, 3}, {-28, 2, 3},
            {-16, 10, 2}, {2, 16, 3}, {20, 12, 2}, {28, 20, 3}, {-24, 22, 2},
            {8, -6, 2}, {-2, 24, 3}, {22, -26, 2}, {-14, -8, 2},
    };

    /** 사냥터 안쪽인가 — 타원 (dx-HG_X)²/32² + (dz-HG_Z)²/30² ≤ 1 (정수식) */
    private static boolean inHunt(int dx, int dz) {
        int ux = dx - HG_X;
        int uz = dz - HG_Z;
        return (long) HG_B * HG_B * ux * ux + (long) HG_A * HG_A * uz * uz
                <= (long) HG_A * HG_A * HG_B * HG_B;
    }

    /**
     * 사냥터 기준 지면 — 개울 바닥 높이의 유일한 근거. 분지 안 **고정 다섯 점**의 자연 지면 중
     * 유효한 것들의 최댓값. 이 다섯 점은 개울 회랑 밖이고, 이 위에 얹히는 것(포드졸·나무 밑동)은
     * 전부 자연 지면 화이트리스트 안의 **같은 y 자리 치환**이라 재조성에도 값이 안 움직인다.
     */
    private static int huntRefY(World world, int cx, int cz) {
        int[][] probes = {{-20, -6}, {6, 6}, {-4, -14}, {18, 4}, {-26, 14}};
        int hi = Integer.MIN_VALUE;
        for (int[] p : probes) {
            int g = naturalGroundY(world, cx + HG_X + p[0], cz + HG_Z + p[1]);
            if (g != Integer.MIN_VALUE) {
                hi = Math.max(hi, g);
            }
        }
        return hi == Integer.MIN_VALUE ? world.getHighestBlockYAt(cx + HG_X, cz + HG_Z) : hi;
    }

    /** 개울 중심선 — i 번째 셀의 마을 중심 기준 {dx, dz}. 상수식 (난수·시간 0) */
    private static int[] creekAt(int i) {
        int dx = HG_X + 16 - (2 * i) / 3 + ((i / 7) % 3) - 1;
        int dz = HG_Z - 22 + i;
        return new int[]{dx, dz};
    }

    /** 개울 바닥 y — 기준 지면에서 2칸 내려 시작해 15셀마다 한 칸씩 떨어진다 (물은 되오르지 않는다) */
    private static int creekBedY(int refY, int i) {
        return refY - 2 - i / 15;
    }

    /** 개울 회랑(수로 3 + 둑 2 = 반폭 5) 안인가 — 나무·바위·풀은 여기 못 들어온다 */
    private static boolean inCreek(int dx, int dz) {
        int i = dz - (HG_Z - 22);
        if (i < -1 || i > CREEK_N) {
            return false;
        }
        int[] c = creekAt(Math.max(0, Math.min(CREEK_N - 1, i)));
        return Math.abs(dx - c[0]) <= 5;
    }

    /** 산길 회랑 — 노면(2칸) + 갓길. 수목·바위가 길을 덮지 않는다 */
    private static boolean onHuntTrail(int dx, int dz) {
        int d = -dz;
        if (d < OUT_FAR || d > HG_TRAIL_END + 2) {
            return false;
        }
        int off = -((d - OUT_NEAR) / 6);
        return dx >= off - 3 && dx <= off + 4;
    }

    /** 무대의 부지 — 나무·바위가 침범하지 않는다 (야영터·사당 터·수레·도적 흔적) */
    private static boolean huntKeepout(int dx, int dz) {
        return (Math.abs(dx - HG_X) <= 5 && Math.abs(dz - (HG_Z + 21)) <= 5)             // 사냥꾼 야영터
                || (Math.abs(dx - (HG_X - 10)) <= 6 && Math.abs(dz - (HG_Z - 8)) <= 6)   // 낡은 사당 터
                || (Math.abs(dx - (HG_X + 7)) <= 5 && Math.abs(dz - (HG_Z + 14)) <= 4)   // 버려진 수레
                || (Math.abs(dx - (HG_X - 20)) <= 5 && Math.abs(dz - (HG_Z + 8)) <= 5);  // 도적 야영 흔적
    }

    /** 사냥터 — 조성 순서: 생물군계 → 지면 → 개울 → 바위 → 숲 → 덤불 → 흔적 → 길·야영터 → 짐승 → 구역 */
    private static void huntingGrounds(World world, int cx, int cy, int cz, List<Zone> out) {
        int refY = huntRefY(world, cx, cz);
        huntBiome(world, cx, cz, refY);
        huntFloor(world, cx, cz);
        huntCreek(world, cx, cz, refY);
        huntCrags(world, cx, cz);
        huntWoods(world, cx, cz);
        huntUndergrowth(world, cx, cz);
        huntRuinShrine(world, cx, cz);
        huntBrokenWall(world, cx, cz);
        huntCart(world, cx, cz);
        huntBanditTrace(world, cx, cz);
        huntTrailExtension(world, cx, cy, cz);   // 노면은 지형·소품 뒤에 깐다 (아무도 길을 못 덮는다)
        huntFootpath(world, cx, cz, refY);
        huntCamp(world, cx, cz);
        huntArrows(world, cx, cz);
        huntGame(world, cx, cz);

        int y0 = Math.min(cy, refY) - 14;
        int y1 = Math.max(cy, refY) + 34;
        out.add(new Zone("북쪽 산길", "늑대와 여우 — 그리고 도적 소문", world.getName(),
                cx + HG_X - HG_A - 8, y0, cz + HG_Z - HG_B - 6,
                cx + HG_X + HG_A + 8, y1, cz - OUT_NEAR - 1));
    }

    /**
     * 생물군계 = 타이가. 늑대·여우는 지형이 아니라 **생물군계**로 스폰한다 (풀 블록·광원만으론 안 온다).
     * 4x4x4 격자가 저장 단위이므로 4칸 간격이면 충분하다. 좌표만의 함수 = 결정론.
     */
    private static void huntBiome(World world, int cx, int cz, int refY) {
        for (int dx = HG_X - HG_A; dx <= HG_X + HG_A; dx += 4) {
            for (int dz = HG_Z - HG_B; dz <= HG_Z + HG_B; dz += 4) {
                if (!inHunt(dx, dz)) {
                    continue;
                }
                for (int y = refY - 12; y <= refY + 24; y += 4) {
                    world.setBiome(cx + dx, y, cz + dz, org.bukkit.block.Biome.TAIGA);
                }
            }
        }
    }

    /**
     * 사냥터 지면 — 평탄화가 아니라 **표층 치환**이다 (같은 y 자리의 블록만 갈아끼운다 = 지형 불변).
     * 포드졸 34% · 거친 흙 18% · 나머지는 원래 지면 그대로. 전부 불투명 풀 블록 = 스폰 가능 지면.
     */
    private static void huntFloor(World world, int cx, int cz) {
        for (int dx = HG_X - HG_A; dx <= HG_X + HG_A; dx++) {
            for (int dz = HG_Z - HG_B; dz <= HG_Z + HG_B; dz++) {
                if (!inHunt(dx, dz) || inCreek(dx, dz) || onHuntTrail(dx, dz)) {
                    continue;
                }
                int x = cx + dx, z = cz + dz;
                int g = outsideGroundY(world, x, z);   // 흙길도 '지면'으로 본다 = 재조성해도 안 내려앉는다
                if (g == Integer.MIN_VALUE) {
                    continue;
                }
                Material ground = world.getBlockAt(x, g, z).getType();
                if (ground != Material.GRASS_BLOCK && ground != Material.DIRT
                        && ground != Material.COARSE_DIRT && ground != Material.PODZOL
                        && ground != Material.ROOTED_DIRT) {
                    continue;   // 바위·모래·노면은 그대로 둔다 (지형에 순응 · 길을 덮지 않는다)
                }
                int h = hash(x + 4801, z - 2207, 100);
                if (h < 34) {
                    world.getBlockAt(x, g, z).setType(Material.PODZOL);
                } else if (h < 52) {
                    world.getBlockAt(x, g, z).setType(Material.COARSE_DIRT);
                }
            }
        }
    }

    /**
     * 개울 — 수로 3칸 + 둑 2칸. 바닥 높이는 지형을 **읽지 않고** huntRefY 에서 상수식으로 내려간다:
     * 수로 안의 열은 재조성 때 물이라 지면 판정이 실격(MIN_VALUE)이 되므로, 지형을 읽으면 두 번째
     * 조성이 개울을 다른 높이에 판다 (v6.4 폐사당 수몰과 같은 부류의 함정). 상수식이면 몇 번을
     * 조성해도 같은 개울이다. 15셀마다 한 칸씩 떨어지므로 단마다 작은 여울이 진다.
     */
    private static void huntCreek(World world, int cx, int cz, int refY) {
        for (int i = 0; i < CREEK_N; i++) {
            int[] c = creekAt(i);
            int bed = creekBedY(refY, i);
            int z = cz + c[1];
            for (int w = -3; w <= 3; w++) {
                int x = cx + c[0] + w;
                if (Math.abs(w) <= 1) {                       // 수로 — 파내고 물을 채운다
                    world.getBlockAt(x, bed, z).setType(Material.WATER);
                    world.getBlockAt(x, bed - 1, z).setType(Material.GRAVEL);
                    int top = Math.min(world.getHighestBlockYAt(x, z), bed + 14);
                    for (int y = bed + 1; y <= Math.max(bed + 3, top + 1); y++) {
                        world.getBlockAt(x, y, z).setType(Material.AIR);
                    }
                } else {                                      // 둑 — 물이 새지 않게 바닥까지 채운다
                    for (int y = bed; y >= bed - 10; y--) {
                        Material m = world.getBlockAt(x, y, z).getType();
                        if (!m.isAir() && m != Material.WATER) {
                            break;
                        }
                        world.getBlockAt(x, y, z).setType(hash(x, y * 31 + z, 3) == 0
                                ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE);
                    }
                }
            }
        }
        // 상·하류 마개 — 개울은 분지 안에서 시작해 분지 안에서 끝난다 (들녘으로 새지 않는다)
        creekPlug(world, cx, cz, creekAt(0), creekBedY(refY, 0), -1);
        creekPlug(world, cx, cz, creekAt(CREEK_N - 1), creekBedY(refY, CREEK_N - 1), 1);
    }

    /** 개울 끝 마개 — 수로 단면을 돌로 막는다 (물이 지형으로 흘러나가지 않게) */
    private static void creekPlug(World world, int cx, int cz, int[] c, int bed, int dir) {
        int z = cz + c[1] + dir;
        for (int w = -3; w <= 3; w++) {
            int x = cx + c[0] + w;
            for (int y = bed + 1; y >= bed - 10; y--) {
                Material m = world.getBlockAt(x, y, z).getType();
                if (!m.isAir() && m != Material.WATER) {
                    break;
                }
                world.getBlockAt(x, y, z).setType(Material.COBBLESTONE);
            }
        }
    }

    /**
     * 바위 — 무더기 14곳(상수표) + 좌표 해시로 흩뿌린 잔돌. 자재는 전부 자연 지면 화이트리스트 **밖**:
     * STONE·ANDESITE 를 쓰면 다음 조성의 지면 판정이 바위 꼭대기를 땅으로 읽어 바위가 해마다 자란다.
     */
    private static void huntCrags(World world, int cx, int cz) {
        for (int[] c : HG_CRAGS) {
            crag(world, cx, cz, c[0], c[1], c[2]);
        }
        for (int dx = HG_X - HG_A; dx <= HG_X + HG_A; dx++) {
            for (int dz = HG_Z - HG_B; dz <= HG_Z + HG_B; dz++) {
                if (!inHunt(dx, dz) || inCreek(dx, dz) || onHuntTrail(dx, dz) || huntKeepout(dx, dz)) {
                    continue;
                }
                if (hash(cx + dx - 991, cz + dz + 617, 55) != 0) {
                    continue;
                }
                huntPut(world, cx + dx, cz + dz, rockMat(cx + dx, cz + dz));
            }
        }
    }

    /**
     * 바위 한 무더기 — 반경 r 안에서 높이가 중심으로 갈수록 오르는 돌덩이 (지형에 얹는다).
     * ox·oz 는 **사냥터 중심** 기준이고 inHunt/inCreek/onHuntTrail 은 **마을 중심** 기준이다 —
     * 좌표계를 먼저 맞춘 뒤에 묻는다 (섞으면 회피가 통째로 어긋난다).
     */
    private static void crag(World world, int cx, int cz, int ox, int oz, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int d2 = dx * dx + dz * dz;
                int vdx = HG_X + ox + dx;   // 마을 중심 기준으로 환산
                int vdz = HG_Z + oz + dz;
                if (d2 > r * r || !inHunt(vdx, vdz) || inCreek(vdx, vdz)
                        || onHuntTrail(vdx, vdz) || huntKeepout(vdx, vdz)) {
                    continue;
                }
                int x = cx + vdx, z = cz + vdz;
                int g = outsideGroundY(world, x, z);
                if (g == Integer.MIN_VALUE) {
                    continue;
                }
                int h = r + 1 - (int) Math.round(Math.sqrt(d2)) + hash(x, z, 2);
                for (int k = 1; k <= h; k++) {
                    Material at = world.getBlockAt(x, g + k, z).getType();
                    if (!at.isAir() && !softPlant(at)) {
                        break;   // 나무·소품 — 바위가 밀어내지 않는다
                    }
                    world.getBlockAt(x, g + k, z).setType(rockMat(x, z + k));
                }
            }
        }
    }

    /** 바위 자재 — 조약돌·이끼 조약돌·조약 심층암 (전부 자연 지면 화이트리스트 밖 = 안 자란다) */
    private static Material rockMat(int x, int z) {
        int h = hash(x + 313, z - 77, 10);
        return h < 5 ? Material.COBBLESTONE
                : h < 8 ? Material.MOSSY_COBBLESTONE : Material.COBBLED_DEEPSLATE;
    }

    /** 성긴 침엽수림 — 가문비 우세(타이가) + 참나무·자작 섞기. 밀도 1/18 ≈ 나무 사이 4~5칸 */
    private static void huntWoods(World world, int cx, int cz) {
        for (int dx = HG_X - HG_A; dx <= HG_X + HG_A; dx++) {
            for (int dz = HG_Z - HG_B; dz <= HG_Z + HG_B; dz++) {
                if (!inHunt(dx, dz) || inCreek(dx, dz) || onHuntTrail(dx, dz) || huntKeepout(dx, dz)) {
                    continue;
                }
                int x = cx + dx, z = cz + dz;
                if (hash(x + 2311, z + 8807, 18) != 0) {
                    continue;
                }
                int g = outsideGroundY(world, x, z);
                if (g == Integer.MIN_VALUE) {
                    continue;
                }
                Material ground = world.getBlockAt(x, g, z).getType();
                if (ground != Material.GRASS_BLOCK && ground != Material.DIRT
                        && ground != Material.COARSE_DIRT && ground != Material.PODZOL
                        && ground != Material.ROOTED_DIRT) {
                    continue;
                }
                if (!huntClear(world, x, g, z)) {
                    continue;   // 잎이 덮을 자리가 다 비어야 심는다 (겹친 나무·허공의 잎 0)
                }
                int kind = hash(x - 55, z + 121, 10);
                if (kind < 7) {
                    coniferTree(world, x, g, z);
                } else {
                    growTree(world, x, g, z, kind == 9);
                }
            }
        }
    }

    /**
     * 나무 심을 자리 — 밑동 위 8칸 · 사방 2칸(잎이 덮을 범위)에 **단단한 것**이 없는가.
     * 풀·고사리·꽃·눈은 비켜 준다 — 자연 지형의 지표는 거의 다 풀로 덮여 있어서, 공기만 '빈 칸'으로
     * 치면 숲이 한 그루도 안 선다 (v6.7 outsideTree 가 들녘에서 심기를 자주 놓친 것도 같은 이유다).
     * 통나무·잎·바위·소품은 거절 — 겹친 나무와 허공에 뜬 잎이 0 인 것은 그대로다.
     */
    private static boolean huntClear(World world, int x, int g, int z) {
        for (int px = x - 2; px <= x + 2; px++) {
            for (int pz = z - 2; pz <= z + 2; pz++) {
                for (int y = g + 1; y <= g + 8; y++) {
                    Material m = world.getBlockAt(px, y, pz).getType();
                    if (!m.isAir() && !softPlant(m)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** 나무·소품이 밀어내도 되는 것 — 지표의 잔풀 (자연 지형은 거의 다 이것으로 덮여 있다) */
    private static boolean softPlant(Material m) {
        return m == Material.SHORT_GRASS || m == Material.TALL_GRASS
                || m == Material.FERN || m == Material.LARGE_FERN
                || m == Material.DEAD_BUSH || m == Material.SNOW || m == Material.MOSS_CARPET
                || m == Material.BROWN_MUSHROOM || m == Material.RED_MUSHROOM
                || m == Material.SWEET_BERRY_BUSH || m == Material.VINE || m == Material.GLOW_LICHEN
                || m == Material.POPPY || m == Material.DANDELION || m == Material.CORNFLOWER
                || m == Material.AZURE_BLUET || m == Material.OXEYE_DAISY;
    }

    /** 가문비나무 — 밑동 5~7단 + 원뿔 잎 (아래가 넓고 위로 좁아진다). 잎이 하늘빛을 끊는다 = 어둠 */
    private static void coniferTree(World world, int x, int gy, int z) {
        int h = 5 + hash(x + 71, z - 29, 3);   // 5~7
        world.getBlockAt(x, gy, z).setType(Material.PODZOL);
        for (int y = gy + 1; y <= gy + h; y++) {
            world.getBlockAt(x, y, z).setType(Material.SPRUCE_LOG);
        }
        int[] rings = {2, 2, 1, 1};   // 아래에서 위로 좁아지는 잎 반경
        for (int k = 0; k < rings.length; k++) {
            int y = gy + h - 2 + k - 1;
            int r = rings[k];
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > r + 1 || (dx == 0 && dz == 0 && y <= gy + h)) {
                        continue;
                    }
                    persistentLeaf(world, x + dx, y, z + dz, Material.SPRUCE_LEAVES);
                }
            }
        }
        persistentLeaf(world, x, gy + h + 2, z, Material.SPRUCE_LEAVES);
    }

    /** 숲 바닥 — 고사리·수풀·죽은 덤불·버섯·이끼. 절반 이상은 맨땅으로 남긴다 (스폰 자리) */
    private static void huntUndergrowth(World world, int cx, int cz) {
        for (int dx = HG_X - HG_A; dx <= HG_X + HG_A; dx++) {
            for (int dz = HG_Z - HG_B; dz <= HG_Z + HG_B; dz++) {
                if (!inHunt(dx, dz) || inCreek(dx, dz) || onHuntTrail(dx, dz)) {
                    continue;
                }
                int x = cx + dx, z = cz + dz;
                int h = hash(x - 6607, z + 4409, 100);
                if (h >= 30) {
                    continue;   // 70% 는 맨땅 — 숲 바닥은 정원이 아니다
                }
                int g = outsideGroundY(world, x, z);
                if (g == Integer.MIN_VALUE || !world.getBlockAt(x, g + 1, z).getType().isAir()) {
                    continue;
                }
                Material ground = world.getBlockAt(x, g, z).getType();
                if (!NATURAL_GROUND.contains(ground) || ground == Material.STONE) {
                    continue;
                }
                Material cover = h < 11 ? Material.FERN
                        : h < 19 ? Material.SHORT_GRASS
                        : h < 23 ? Material.DEAD_BUSH
                        : h < 26 ? Material.BROWN_MUSHROOM
                        : h < 28 ? Material.RED_MUSHROOM : Material.MOSS_CARPET;
                world.getBlockAt(x, g + 1, z).setType(cover);
            }
        }
    }

    /**
     * 낡은 사당 터 — 폐사당이 **아니다**. 길가에 있던 작은 사당의 잔해: 5x5 이끼 기단, 부러진 기둥 넷,
     * 넘어진 비석, 금간 제단. 냉광 0 (영혼 계열은 폐사당 전용 — 그 계약은 v6 부터 한 번도 안 깼다).
     * 광원 자체가 없다 = 밤이면 여기가 가장 위험한 자리다.
     */
    private static void huntRuinShrine(World world, int cx, int cz) {
        int ox = cx + HG_X - 10, oz = cz + HG_Z - 8;
        int base = Integer.MIN_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int g = outsideGroundY(world, ox + dx, oz + dz);
                if (g != Integer.MIN_VALUE) {
                    base = Math.max(base, g);
                }
            }
        }
        if (base == Integer.MIN_VALUE) {
            return;
        }
        int deck = base + 1;   // 기단 윗면 — 부지 최고점 **위**. 자연 지면 블록은 한 칸도 갈아엎지 않는다
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = ox + dx, z = oz + dz;
                int g = outsideGroundY(world, x, z);
                if (g == Integer.MIN_VALUE) {
                    continue;
                }
                for (int y = g + 1; y < deck; y++) {
                    world.getBlockAt(x, y, z).setType(Material.PACKED_MUD);   // 기단 밑 메움 (공중 부양 0)
                }
                for (int y = deck + 1; y <= deck + 4; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR);
                }
                int h = hash(x + 101, z - 43, 10);   // 이끼·금간 돌 벽돌 (상수 치환)
                world.getBlockAt(x, deck, z).setType(h < 4 ? Material.MOSSY_STONE_BRICKS
                        : h < 7 ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS);
            }
        }
        int[][] pillars = {{-2, -2, 3}, {2, -2, 1}, {-2, 2, 2}, {2, 2, 0}};   // 넷 중 하나는 뿌리만 남았다
        for (int[] pl : pillars) {
            for (int k = 1; k <= pl[2]; k++) {
                world.getBlockAt(ox + pl[0], deck + k, oz + pl[1])
                        .setType(k == pl[2] ? Material.MOSSY_STONE_BRICK_WALL : Material.STONE_BRICK_WALL);
            }
        }
        world.getBlockAt(ox, deck + 1, oz).setType(Material.CHISELED_STONE_BRICKS);   // 신상 없는 제단
        world.getBlockAt(ox, deck + 2, oz).setType(Material.STONE_BRICK_SLAB);
        huntPut(world, ox + 1, oz + 3, Material.MOSSY_STONE_BRICKS);                  // 넘어진 비석
        huntPut(world, ox + 2, oz + 3, Material.CHISELED_STONE_BRICKS);
        huntPut(world, ox + 3, oz + 3, Material.STONE_BRICK_SLAB);
        for (int[] r : new int[][]{{-3, 0}, {3, 1}, {0, -3}, {-1, 3}, {3, -2}}) {     // 굴러떨어진 조각
            huntPut(world, ox + r[0], oz + r[1], Material.MOSSY_COBBLESTONE);
        }
    }

    /** 무너진 돌담 — 22칸. 높이는 좌표 해시로 0~2 (끊긴 자리가 무너진 자리다) + 흘러내린 조약돌 */
    private static void huntBrokenWall(World world, int cx, int cz) {
        int x0 = cx + HG_X + 4, z0 = cz + HG_Z + 6;
        for (int i = 0; i < 22; i++) {
            int x = x0 + i;
            int z = z0 + (i / 6);   // 완만하게 꺾인다
            int g = outsideGroundY(world, x, z);
            if (g == Integer.MIN_VALUE) {
                continue;
            }
            int h = hash(x + 771, z - 331, 4);   // 0 = 무너져 없다
            for (int k = 1; k <= h; k++) {
                if (!outsideVacant(world, x, g + k, z)) {
                    break;
                }
                world.getBlockAt(x, g + k, z).setType(hash(x, z + k, 3) == 0
                        ? Material.MOSSY_COBBLESTONE_WALL : Material.COBBLESTONE_WALL);
            }
            if (h == 0) {
                huntPut(world, x, z + 1, Material.COBBLESTONE_SLAB);   // 흘러내린 담돌
            }
        }
    }

    /** 버려진 수레 — 짐칸이 부서졌고 바퀴 하나는 빠져 옆에 눕는다. 짐은 흩어졌다 (발자국길 옆) */
    private static void huntCart(World world, int cx, int cz) {
        int x0 = cx + HG_X + 6, z0 = cz + HG_Z + 14;
        int g = outsideGroundY(world, x0, z0);
        if (g == Integer.MIN_VALUE) {
            return;
        }
        for (int dx = 0; dx <= 2; dx++) {          // 짐칸 — 널판 (한쪽이 내려앉았다)
            for (int dz = 0; dz <= 1; dz++) {
                world.getBlockAt(x0 + dx, g + 1, z0 + dz)
                        .setType(dx == 2 ? Material.OAK_SLAB : Material.OAK_PLANKS);
            }
        }
        world.getBlockAt(x0, g + 1, z0 - 1).setType(Material.OAK_LOG);       // 끌채
        world.getBlockAt(x0 + 1, g + 2, z0).setType(Material.BARREL);        // 엎어진 짐
        world.getBlockAt(x0 + 2, g + 1, z0 + 2).setType(Material.HAY_BLOCK);
        cartWheel(world, x0 - 1, g + 1, z0, BlockFace.NORTH, false);         // 선 바퀴
        cartWheel(world, x0 + 3, g + 1, z0 + 3, BlockFace.NORTH, true);      // 빠져 나뒹구는 바퀴
        huntPut(world, x0 + 4, z0 - 1, Material.BONE_BLOCK);                 // 끌던 짐승의 뼈
        for (int[] s : new int[][]{{-2, 2}, {4, 1}, {-1, 3}}) {
            huntPut(world, x0 + s[0], z0 + s[1], Material.COBBLESTONE_SLAB);
        }
    }

    /** 수레바퀴 — 참나무 뚜껑문. 열면 판이 서고(바퀴), 닫으면 눕는다(빠진 바퀴) */
    private static void cartWheel(World world, int x, int y, int z, BlockFace facing, boolean fallen) {
        TrapDoor td = (TrapDoor) Material.OAK_TRAPDOOR.createBlockData();
        td.setFacing(facing);
        td.setOpen(!fallen);
        td.setHalf(Bisected.Half.BOTTOM);
        world.getBlockAt(x, y, z).setBlockData(td);
    }

    /** 도적의 야영 흔적 — 꺼진 모닥불(불씨가 죽었다 = 광원 0)·통나무 걸상·버린 궤짝·나무에 박힌 화살 */
    private static void huntBanditTrace(World world, int cx, int cz) {
        int x0 = cx + HG_X - 20, z0 = cz + HG_Z + 8;
        int g = outsideGroundY(world, x0, z0);
        if (g == Integer.MIN_VALUE) {
            return;
        }
        org.bukkit.block.data.Lightable fire =
                (org.bukkit.block.data.Lightable) Material.CAMPFIRE.createBlockData();
        fire.setLit(false);   // 꺼진 모닥불 — 온색이되 빛이 없다 (여기는 밤에 위험해야 한다)
        world.getBlockAt(x0, g + 1, z0).setBlockData(fire);
        for (int[] s : new int[][]{{-2, 0}, {2, 0}, {0, 2}}) {
            huntPut(world, x0 + s[0], z0 + s[1], Material.OAK_LOG);
        }
        huntPut(world, x0 + 2, z0 + 2, Material.BARREL);
        huntPut(world, x0 - 2, z0 - 2, Material.HAY_BLOCK);
        huntPut(world, x0 + 3, z0 - 2, Material.BONE_BLOCK);
        for (int[] w : new int[][]{{-3, 1}, {3, 3}, {1, -3}}) {   // 삭은 천막의 실밥
            huntPut(world, x0 + w[0], z0 + w[1], Material.COBWEB);
        }
    }

    /**
     * 나무에 박힌 화살 — 덫걸이(TRIPWIRE_HOOK)를 밑동에 붙이면 촉이 박히고 오늬가 튀어나온 화살로 읽힌다.
     * 여덟 자리 전부 밑동을 먼저 확인하고 붙인다 (허공에 뜬 화살 0).
     */
    private static void huntArrows(World world, int cx, int cz) {
        int[][] spots = {{2, -12}, {-6, -16}, {12, 4}, {-18, -2}, {6, 18}, {-12, 20}, {18, -22}, {-2, 8}};
        for (int[] s : spots) {
            arrowInTrunk(world, cx + HG_X + s[0], cz + HG_Z + s[1]);
        }
    }

    /** 반경 4 안에서 첫 밑동을 찾아 그 옆면에 화살을 박는다 */
    private static void arrowInTrunk(World world, int ox, int oz) {
        for (int r = 0; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int x = ox + dx, z = oz + dz;
                    int g = outsideGroundY(world, x, z);
                    if (g == Integer.MIN_VALUE) {
                        continue;
                    }
                    for (int y = g + 2; y <= g + 4; y++) {
                        if (!world.getBlockAt(x, y, z).getType().name().endsWith("_LOG")) {
                            continue;
                        }
                        if (!world.getBlockAt(x, y, z + 1).getType().isAir()) {
                            continue;
                        }
                        Directional d = (Directional) Material.TRIPWIRE_HOOK.createBlockData();
                        d.setFacing(BlockFace.SOUTH);   // 화살은 남쪽(마을 쪽)에서 날아와 박혔다
                        world.getBlockAt(x, y, z + 1).setBlockData(d);
                        return;
                    }
                }
            }
        }
    }

    /**
     * 산길 연장 — d=92 에서 끊긴 길을 d=100 까지 잇는다. 등롱은 d=96 에서 끝난다 (그 너머가 사냥터다).
     * v7.1: 표고선을 **북문에서부터 통째로 다시 잡아** 이어 붙인다 (구간을 따로 잡으면 이음매에서 계단이 선다).
     */
    private static void huntTrailExtension(World world, int cx, int cy, int cz) {
        int[] p = gradeProfile(world, cx, cy, cz, false, HG_TRAIL_END);
        for (int d = OUT_FAR + 1; d <= HG_TRAIL_END; d++) {
            int off = -((d - OUT_NEAR) / 6);
            for (int w = 0; w <= 1; w++) {
                outsideRoadCell(world, cx + off + w, p[d - OUT_NEAR], cz - d, true);
            }
            roadsideBrush(world, cx, cz, cx + off, cz - d);
            if (d <= HG_LAMP_END && Math.floorMod(d, 4) == 2) {
                roadsideLantern(world, cx + off - 3, cz - d);
                roadsideLantern(world, cx + off + 4, cz - d);
            }
        }
    }

    /**
     * 발자국길 — 야영터에서 개울을 건너 사당 터까지. 폭 1칸 거친 흙, 등롱 0.
     * 개울 회랑을 지나는 칸은 흙 대신 **참나무 널다리**를 개울 바닥 위에 놓는다 (물에 흙을 붓지 않는다).
     */
    private static void huntFootpath(World world, int cx, int cz, int refY) {
        int[][] legs = {
                {HG_X, HG_Z + 21, HG_X + 2, HG_Z + 8},        // 야영터 → 개울 동안 (개울과 나란히 가지 않는다)
                {HG_X + 2, HG_Z + 8, HG_X - 7, HG_Z - 5},     // 개울을 가로질러 → 사당 터
        };
        for (int[] leg : legs) {
            int steps = Math.max(Math.abs(leg[2] - leg[0]), Math.abs(leg[3] - leg[1]));
            for (int s = 0; s <= steps; s++) {
                int dx = leg[0] + (leg[2] - leg[0]) * s / steps;
                int dz = leg[1] + (leg[3] - leg[1]) * s / steps;
                footCell(world, cx, cz, dx, dz, refY);
                if (hash(cx + dx, cz + dz, 3) == 0) {   // 길이 한 칸씩 흔들린다 (자로 그은 길이 아니다)
                    footCell(world, cx, cz, dx + 1, dz, refY);
                }
            }
        }
    }

    /** 발자국길 한 칸 — 개울 위면 널다리, 뭍이면 거친 흙 */
    private static void footCell(World world, int cx, int cz, int dx, int dz, int refY) {
        int x = cx + dx, z = cz + dz;
        int i = dz - (HG_Z - 22);
        if (i >= 0 && i < CREEK_N && Math.abs(dx - creekAt(i)[0]) <= 3) {
            int bed = creekBedY(refY, i);
            world.getBlockAt(x, bed + 1, z).setType(Material.OAK_PLANKS);   // 널다리
            for (int y = bed + 2; y <= bed + 4; y++) {
                world.getBlockAt(x, y, z).setType(Material.AIR);
            }
            return;
        }
        int g = outsideGroundY(world, x, z);
        if (g == Integer.MIN_VALUE) {
            return;
        }
        world.getBlockAt(x, g, z).setType(hash(x, z, 4) == 0 ? Material.DIRT_PATH : Material.COARSE_DIRT);
        for (int y = g + 1; y <= g + 3; y++) {
            if (!world.getBlockAt(x, y, z).getType().isAir()) {
                world.getBlockAt(x, y, z).setType(Material.AIR);
            }
        }
    }

    /**
     * 사냥꾼 야영터 — 산길이 끝나는 자리. 사냥터에서 **불이 있는 유일한 곳**이다:
     * 모닥불 1(온색) · 랜턴 2 · 횃불 1. 여기서 세 칸만 벗어나면 광원 0 = 몹의 영역.
     */
    private static void huntCamp(World world, int cx, int cz) {
        int ox = cx + HG_X, oz = cz + HG_Z + 21;
        int base = Integer.MIN_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int g = outsideGroundY(world, ox + dx, oz + dz);
                if (g != Integer.MIN_VALUE) {
                    base = Math.max(base, g);
                }
            }
        }
        if (base == Integer.MIN_VALUE) {
            return;
        }
        for (int dx = -3; dx <= 3; dx++) {   // 다져진 마당 — 평탄화가 아니라 표층 치환 (제 칸의 지면 자리)
            for (int dz = -3; dz <= 3; dz++) {
                int x = ox + dx, z = oz + dz;
                int g = outsideGroundY(world, x, z);
                if (g == Integer.MIN_VALUE) {
                    continue;
                }
                world.getBlockAt(x, g, z).setType(hash(x + 17, z + 19, 3) == 0
                        ? Material.DIRT_PATH : Material.COARSE_DIRT);
                for (int y = g + 1; y <= g + 3; y++) {
                    if (!world.getBlockAt(x, y, z).getType().isAir()) {
                        world.getBlockAt(x, y, z).setType(Material.AIR);
                    }
                }
            }
        }
        campPut(world, ox, oz, Material.CAMPFIRE);   // 지펴진 모닥불 — 사냥터에서 불이 있는 유일한 자리
        for (int[] st : new int[][]{{-2, 0}, {2, 0}, {0, 2}, {0, -2}}) {   // 둘러앉는 통나무
            campPut(world, ox + st[0], oz + st[1], Material.OAK_LOG);
        }
        campPut(world, ox + 2, oz - 2, Material.BARREL);      // 가죽 무두질 통
        campPut(world, ox - 2, oz + 2, Material.HAY_BLOCK);
        // 비 가림 — 네 기둥은 **제 칸의 지면에서** 처마 높이(base+4)까지 선다. 판자 지붕만 수평이다
        // (땅은 기울어도 지붕은 수평이다 — 평탄화하지 않고 기울기를 기둥 길이로 먹는다).
        for (int[] pl : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) {
            int x = ox + pl[0], z = oz + pl[1];
            int g = outsideGroundY(world, x, z);
            if (g == Integer.MIN_VALUE) {
                continue;
            }
            for (int y = g + 1; y <= base + 3; y++) {
                world.getBlockAt(x, y, z).setType(Material.SPRUCE_FENCE);
            }
        }
        for (int dx = -3; dx <= 3; dx++) {
            world.getBlockAt(ox + dx, base + 4, oz - 3).setType(Material.SPRUCE_SLAB);
            world.getBlockAt(ox + dx, base + 4, oz + 3).setType(Material.SPRUCE_SLAB);
        }
        world.getBlockAt(ox - 3, base + 4, oz - 3).setType(Material.LANTERN);   // 기둥 위 랜턴 — 온색
        world.getBlockAt(ox + 3, base + 4, oz + 3).setType(Material.LANTERN);
        campSign(world, ox + 1, oz + 3, "북쪽 산길 사냥터", "불은 여기까지다");
        campSign(world, ox - 1, oz + 3, "늑대 · 여우 · 멧돼지", "밤에는 다른 것도 나온다");
    }

    /** 야영터 소품 — 제 칸의 지면 위 (빈 칸에만). 기울어진 땅에서도 뜨지 않는다 */
    private static void campPut(World world, int x, int z, Material mat) {
        int g = outsideGroundY(world, x, z);
        if (g != Integer.MIN_VALUE && world.getBlockAt(x, g + 1, z).getType().isAir()) {
            world.getBlockAt(x, g + 1, z).setType(mat);
        }
    }

    /** 야영터 표지 — 세우는 표지판은 밑에 받칠 블록이 있어야 한다 (제 칸의 지면 위) */
    private static void campSign(World world, int x, int z, String l1, String l2) {
        int g = outsideGroundY(world, x, z);
        if (g != Integer.MIN_VALUE && world.getBlockAt(x, g + 1, z).getType().isAir()) {
            placeSign(world, x, g + 1, z, BlockFace.SOUTH, l1, l2);
        }
    }

    /**
     * 초기 개체군 — 늑대 4 · 여우 3 · 돼지(멧돼지 대역) 4. 생물군계(타이가)를 못 박았으므로 이후는
     * 자연 스폰·번식이 돌지만, 첫 밤부터 사냥이 돌아야 성장·경제 루프가 산다.
     * 재조성 결정론: 심기 전에 분지 안의 기존 개체를 먼저 걷어낸다 (조성마다 짐승이 쌓이지 않는다).
     */
    private static void huntGame(World world, int cx, int cz) {
        int refY = huntRefY(world, cx, cz);
        BoundingBox box = new BoundingBox(cx + HG_X - HG_A, refY - 24, cz + HG_Z - HG_B,
                cx + HG_X + HG_A, refY + 32, cz + HG_Z + HG_B);
        for (Entity e : world.getNearbyEntities(box)) {
            EntityType t = e.getType();
            if (t == EntityType.WOLF || t == EntityType.FOX || t == EntityType.PIG) {
                e.remove();
            }
        }
        int[][] game = {
                {-14, -10, 0}, {-11, -8, 0}, {8, 6, 0}, {12, 9, 0},            // 늑대 — 무리 둘
                {-22, 12, 1}, {16, -16, 1}, {-4, -20, 1},                      // 여우
                {20, 2, 2}, {-18, -14, 2}, {4, 20, 2}, {24, -10, 2},           // 멧돼지 대역
        };
        for (int[] g : game) {
            int x = cx + HG_X + g[0], z = cz + HG_Z + g[1];
            int gy = outsideGroundY(world, x, z);
            if (gy == Integer.MIN_VALUE) {
                continue;
            }
            EntityType type = g[2] == 0 ? EntityType.WOLF : g[2] == 1 ? EntityType.FOX : EntityType.PIG;
            Entity e = world.spawnEntity(loc(world, x, gy + 1, z), type);
            if (e instanceof org.bukkit.entity.LivingEntity le) {
                le.setRemoveWhenFarAway(false);   // 사냥터의 짐승은 청크가 내려가도 남는다
            }
            e.setPersistent(true);
        }
    }

    /** 사냥터 소품 한 칸 — 지형 위 빈 칸에만 (잔풀은 밀어내도 된다. 나무·바위는 못 밀어낸다) */
    private static void huntPut(World world, int x, int z, Material mat) {
        int g = outsideGroundY(world, x, z);
        if (g == Integer.MIN_VALUE) {
            return;
        }
        Material at = world.getBlockAt(x, g + 1, z).getType();
        if (at.isAir() || softPlant(at)) {
            world.getBlockAt(x, g + 1, z).setType(mat);
        }
    }

    // ─── v6.9 ② 흑수나루 ───
    //
    // 등록부 heuksu_ferry: "수로 물류 거점". 그리고 등록 기연 sunken_ship_manual(침몰선 비급)의 무대다.
    // 부지는 폐사당의 SHRINE_SITES 방식을 잇는다 — **결정론 후보 목록**을 순서대로 검사해 첫 합격지에
    // 짓는다. 다만 폐사당이 '물 없는 자리'를 찾았다면 나루는 정반대로 **물과 뭍이 같이 있는 자리**를
    // 찾는다: 물 열 ≥ 15% · 뭍 열 ≥ 30% · 최대 수심 ≥ 5(침몰선이 잠길 깊이) · 뭍의 기복 ≤ 12.
    // 후보를 다 소진하면 최고점수 후보에 **선착장 못을 판다**(ferryDig) — 나루는 반드시 선다.
    //
    // 모두 마을 중심에서 100칸 이상 = 검수 스캔 창(≤88) 밖. 조명은 온색뿐(랜턴·모닥불) — 나루는
    // 괴담이 도는 곳이지 저승이 아니다. 냉색은 폐사당의 몫으로 남는다.

    /** 나루 후보 — {ox, oz}. 순서가 곧 우선순위. 북(사냥터)·북서(폐사당) 사분면은 비운다 */
    private static final int[][] FERRY_SITES = {
            {118, 12}, {12, 118}, {-118, 20}, {126, -34}, {-30, 128},
            {112, 96}, {-104, 100}, {132, 52}, {-124, -30}, {48, 140},
            {150, 8}, {-146, 60}, {8, 158}, {156, 118}, {-152, -20}, {-56, 150},
    };

    private static final int FR_R = 17;        // 부지 반폭 (35x35)
    private static final int FR_MIN_WATER = 15;    // 물 열 최소 비율 %
    private static final int FR_MIN_LAND = 30;     // 뭍 열 최소 비율 %
    private static final int FR_MIN_DEPTH = 5;     // 난파선이 잠길 최소 수심
    private static final int FR_MAX_RELIEF = 12;   // 뭍의 높이 편차 상한

    /** 그 열의 수심 — 최상단이 물이 아니면 0 (getHighestBlockYAt 은 물을 최상단으로 센다) */
    private static int waterDepth(World world, int x, int z) {
        int top = Math.min(world.getHighestBlockYAt(x, z), world.getMaxHeight() - 1);
        if (world.getBlockAt(x, top, z).getType() != Material.WATER) {
            return 0;
        }
        int d = 0;
        for (int y = top; y >= top - 40 && y > world.getMinHeight(); y--) {
            Material m = world.getBlockAt(x, y, z).getType();
            if (m == Material.WATER || m == Material.KELP || m == Material.KELP_PLANT
                    || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS) {
                d++;
            } else {
                break;
            }
        }
        return d;
    }

    /** 나루 부지 실측 — {점수, 수면y, 최대수심, 물중심x, 물중심z, 뭍중심x, 뭍중심z}. 실격이면 점수 -1 */
    private static int[] ferryProbe(World world, int sx, int sz) {
        int cells = 0, wet = 0, dry = 0, maxD = 0, surface = Integer.MIN_VALUE;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        long wxSum = 0, wzSum = 0, lxSum = 0, lzSum = 0;
        for (int x = sx - FR_R; x <= sx + FR_R; x++) {
            for (int z = sz - FR_R; z <= sz + FR_R; z++) {
                cells++;
                int d = waterDepth(world, x, z);
                if (d > 0) {
                    wet++;
                    wxSum += x;
                    wzSum += z;
                    if (d > maxD) {
                        maxD = d;
                        surface = Math.min(world.getHighestBlockYAt(x, z), world.getMaxHeight() - 1);
                    }
                    continue;
                }
                int g = naturalGroundY(world, x, z);
                if (g == Integer.MIN_VALUE) {
                    continue;   // 용암·얼음 — 물도 뭍도 아니다
                }
                dry++;
                lxSum += x;
                lzSum += z;
                lo = Math.min(lo, g);
                hi = Math.max(hi, g);
            }
        }
        if (wet == 0 || dry == 0) {
            return new int[]{-1, 0, 0, 0, 0, 0, 0};
        }
        int wPct = 100 * wet / cells;
        int lPct = 100 * dry / cells;
        int relief = hi - lo;
        int score = (wPct >= FR_MIN_WATER && lPct >= FR_MIN_LAND && maxD >= FR_MIN_DEPTH
                && relief <= FR_MAX_RELIEF) ? 1000 + Math.min(wPct, lPct) * 10 + maxD
                : Math.min(wPct, lPct) * 10 + maxD;   // 실격이어도 점수는 남긴다 (최선의 차선지)
        return new int[]{score, surface, maxD,
                (int) (wxSum / wet), (int) (wzSum / wet), (int) (lxSum / dry), (int) (lzSum / dry)};
    }

    /** 흑수나루 — 부지 선정 → 못 파기(필요 시) → 나루터·낡은 배·난파선·오두막·괴담 → 구역 */
    private static void heuksuFerry(World world, int cx, int cy, int cz, List<Zone> out) {
        int sx = cx + FERRY_SITES[0][0], sz = cz + FERRY_SITES[0][1];
        int[] best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int[] site : FERRY_SITES) {
            int[] p = ferryProbe(world, cx + site[0], cz + site[1]);
            if (p[0] >= 1000) {                    // 합격 — 첫 자리에 짓는다 (결정론)
                sx = cx + site[0];
                sz = cz + site[1];
                best = p;
                break;
            }
            if (p[0] > bestScore) {                // 차선지 기억
                bestScore = p[0];
                sx = cx + site[0];
                sz = cz + site[1];
                best = p;
            }
        }
        if (best == null || best[0] < 1000) {
            Bukkit.getLogger().warning("[혼천/조성] 흑수나루 후보 " + FERRY_SITES.length
                    + "곳에 쓸 만한 물가가 없다 — (" + sx + "," + sz + ") 에 나루 못을 판다");
            ferryDig(world, sx, sz);
            best = ferryProbe(world, sx, sz);
            if (best[0] < 0) {
                return;   // 못 파기까지 실패 — 나루를 접는다 (마을은 그대로 선다)
            }
        }
        int waterY = best[1];
        int lx = best[5], lz = best[6];   // 뭍 중심
        int wx = best[3], wz = best[4];   // 물 중심
        int[] dir = ferryBearing(lx, lz, wx, wz);   // 뭍 → 물 방향 (선착장이 뻗는 쪽)
        int[] shore = ferryShore(world, sx, sz, dir);
        if (shore == null) {
            return;
        }
        ferryPier(world, shore[0], shore[1], waterY, dir);
        ferryHut(world, shore[0], shore[1], waterY, dir);
        ferryBoats(world, shore[0], shore[1], waterY, dir);
        ferryReeds(world, sx, sz, waterY);
        ferryGhost(world, shore[0], shore[1], waterY, dir);
        int[] wreck = ferryWreck(world, sx, sz, waterY, dir);
        ferrySign(world, cx, cz, sx, sz);
        out.add(new Zone("흑수나루", "물귀신 이야기는 여기서 시작된다", world.getName(),
                sx - FR_R - 4, waterY - 26, sz - FR_R - 4,
                sx + FR_R + 4, waterY + 24, sz + FR_R + 4));
        Bukkit.getLogger().info("[혼천/조성] 흑수나루 (" + sx + "," + waterY + "," + sz
                + ") · 수심 " + best[2] + " · 침몰선 비급 궤짝 "
                + (wreck == null ? "미배치" : "(" + wreck[0] + "," + wreck[1] + "," + wreck[2] + ")"));
    }

    /** 뭍 → 물 방향을 축 하나로 접는다 (선착장은 대각으로 뻗지 않는다) */
    private static int[] ferryBearing(int lx, int lz, int wx, int wz) {
        int dx = wx - lx, dz = wz - lz;
        if (Math.abs(dx) >= Math.abs(dz)) {
            return new int[]{Integer.signum(dx) == 0 ? 1 : Integer.signum(dx), 0};
        }
        return new int[]{0, Integer.signum(dz) == 0 ? 1 : Integer.signum(dz)};
    }

    /** 물가 — 부지 중심에서 물 반대쪽으로 물러났다가 물 쪽으로 걸어와 **마지막 뭍 칸**을 찾는다 */
    private static int[] ferryShore(World world, int sx, int sz, int[] dir) {
        int px = sx - dir[0] * FR_R, pz = sz - dir[1] * FR_R;
        int lastX = Integer.MIN_VALUE, lastZ = 0;
        for (int step = 0; step <= 2 * FR_R; step++) {
            int x = px + dir[0] * step, z = pz + dir[1] * step;
            if (waterDepth(world, x, z) > 0) {
                return lastX == Integer.MIN_VALUE ? null : new int[]{lastX, lastZ};
            }
            if (naturalGroundY(world, x, z) != Integer.MIN_VALUE) {
                lastX = x;
                lastZ = z;
            }
        }
        return null;
    }

    /**
     * 나루터 — 뭍에서 물로 12칸 뻗는 폭 3 참나무 널판 잔교. 다리발(가문비 목책)은 호수 바닥까지 내리고
     * 물에 잠기는 칸은 **waterlogged** 로 놓는다 (안 그러면 다리발마다 공기 방울이 뚫린다).
     * 등롱 3주 — 온색. 나루의 밤은 마을의 밤과 같은 색이다.
     */
    private static void ferryPier(World world, int shx, int shz, int waterY, int[] dir) {
        int deck = waterY + 1;
        for (int step = -1; step <= 12; step++) {
            for (int side = -1; side <= 1; side++) {
                int x = shx + dir[0] * step - dir[1] * side;
                int z = shz + dir[1] * step + dir[0] * side;
                world.getBlockAt(x, deck, z).setType(Material.OAK_PLANKS);
                for (int y = deck + 1; y <= deck + 4; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR);
                }
                if (step >= 0 && Math.floorMod(step, 4) == 0) {   // 다리발 — 바닥까지
                    for (int y = waterY; y >= waterY - 16; y--) {
                        Material m = world.getBlockAt(x, y, z).getType();
                        if (!m.isAir() && m != Material.WATER) {
                            break;
                        }
                        putWet(world, x, y, z, Material.SPRUCE_FENCE);
                    }
                }
                if (Math.abs(side) == 1 && step >= 1 && Math.floorMod(step, 3) == 1) {
                    world.getBlockAt(x, deck + 1, z).setType(Material.OAK_FENCE);   // 난간 기둥
                }
            }
        }
        for (int step : new int[]{4, 8, 12}) {   // 등롱 — 잔교 끝쪽 난간에 매단다 (온색)
            int x = shx + dir[0] * step - dir[1];
            int z = shz + dir[1] * step + dir[0];
            world.getBlockAt(x, deck + 1, z).setType(Material.OAK_FENCE);
            world.getBlockAt(x, deck + 2, z).setType(Material.OAK_FENCE);
            hangingLantern(world, x, deck + 3, z);
        }
    }

    /** 물에 잠기는 블록 — 물칸이면 waterlogged 로 (공기 방울이 뚫리지 않는다) */
    private static void putWet(World world, int x, int y, int z, Material mat) {
        boolean submerged = world.getBlockAt(x, y, z).getType() == Material.WATER;
        BlockData data = mat.createBlockData();
        if (submerged && data instanceof org.bukkit.block.data.Waterlogged w) {
            w.setWaterlogged(true);
        }
        world.getBlockAt(x, y, z).setBlockData(data);
    }

    /** 뱃사공 오두막 — 7x6 가문비 판벽·흑와 맞배. NPC 없음 (NPC 7인 계약 불변). 모닥불·랜턴 = 온색 */
    private static void ferryHut(World world, int shx, int shz, int waterY, int[] dir) {
        // 자리 후보 넷 — 잔교 축에서 좌우·앞뒤로 물러난 뭍. 첫 번째로 **7x7 이 다 마른 땅**인 곳에 짓는다
        // (한 자리만 보고 물이면 접던 것이 v6.9 초안의 구멍이었다 — 나루에 오두막이 없는 나루가 됐다).
        int[][] cands = {{8, 5}, {8, -5}, {12, 6}, {12, -6}};
        int hx = 0, hz = 0, base = Integer.MIN_VALUE;
        for (int[] c : cands) {
            int px = shx - dir[0] * c[0] - dir[1] * c[1];
            int pz = shz - dir[1] * c[0] + dir[0] * c[1];
            int hi = Integer.MIN_VALUE;
            boolean dry = true;
            for (int dx = -3; dx <= 3 && dry; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    int g = naturalGroundY(world, px + dx, pz + dz);
                    if (g == Integer.MIN_VALUE) {
                        dry = false;
                        break;
                    }
                    hi = Math.max(hi, g);
                }
            }
            if (dry) {
                hx = px;
                hz = pz;
                base = hi;
                break;
            }
        }
        if (base == Integer.MIN_VALUE) {
            return;   // 물가가 온통 절벽·늪 — 오두막만 접는다 (잔교·난파선·제단은 그대로 산다)
        }
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int y = base + 1; y <= base + 7; y++) {
                    world.getBlockAt(hx + dx, y, hz + dz).setType(Material.AIR);
                }
                world.getBlockAt(hx + dx, base, hz + dz).setType(Material.COARSE_DIRT);
            }
        }
        for (int dx = -3; dx <= 3; dx++) {          // 벽 — 모서리는 통나무 기둥
            for (int dz = -2; dz <= 2; dz++) {
                boolean edge = Math.abs(dx) == 3 || Math.abs(dz) == 2;
                if (!edge) {
                    continue;
                }
                boolean post = Math.abs(dx) == 3 && Math.abs(dz) == 2;
                for (int k = 1; k <= 3; k++) {
                    world.getBlockAt(hx + dx, base + k, hz + dz)
                            .setType(post ? Material.SPRUCE_LOG : Material.SPRUCE_PLANKS);
                }
            }
        }
        world.getBlockAt(hx, base + 1, hz - 2).setType(Material.AIR);   // 문
        world.getBlockAt(hx, base + 2, hz - 2).setType(Material.AIR);
        for (int dx = -3; dx <= 3; dx += 6) {       // 합각(박공) — 벽 위 삼각면을 판벽으로 막는다
            for (int dz = -2; dz <= 2; dz++) {
                int top = base + 3 + (2 - Math.min(2, Math.abs(dz)));
                for (int y = base + 4; y <= top; y++) {
                    world.getBlockAt(hx + dx, y, hz + dz).setType(Material.SPRUCE_PLANKS);
                }
            }
        }
        for (int dx = -4; dx <= 4; dx++) {          // 흑와 맞배 — 처마 한 칸 내밀고 두 물매
            for (int dz = -3; dz <= 3; dz++) {
                int y = base + 4 + (2 - Math.min(2, Math.abs(dz)));
                world.getBlockAt(hx + dx, y, hz + dz).setType(Math.abs(dz) == 3
                        ? Material.DEEPSLATE_TILE_SLAB : Material.DEEPSLATE_TILES);
            }
        }
        world.getBlockAt(hx - 2, base + 1, hz + 1).setType(Material.BARREL);
        world.getBlockAt(hx + 2, base + 1, hz + 1).setType(Material.CHEST);
        wallTorch(world, hx - 2, base + 2, hz - 1, BlockFace.SOUTH);
        world.getBlockAt(hx + 4, base + 1, hz - 3).setType(Material.CAMPFIRE);   // 마당 모닥불 — 온색
        hangingLantern(world, hx, base + 3, hz - 3);
        placeSign(world, hx + 2, base + 1, hz - 3, BlockFace.SOUTH,
                "흑수나루", "밤배는 태우지 않는다");
    }

    /** 낡은 배 둘 — 잔교에 매인 나룻배 하나, 물가에 반쯤 부서져 처박힌 하나 */
    private static void ferryBoats(World world, int shx, int shz, int waterY, int[] dir) {
        int bx = shx + dir[0] * 6 - dir[1] * 3;   // 잔교 옆에 매인 배
        int bz = shz + dir[1] * 6 + dir[0] * 3;
        for (int a = -2; a <= 2; a++) {
            for (int b = -1; b <= 1; b++) {
                int x = bx + dir[0] * a - dir[1] * b;
                int z = bz + dir[1] * a + dir[0] * b;
                putWet(world, x, waterY, z, Math.abs(a) == 2
                        ? Material.SPRUCE_STAIRS : Material.SPRUCE_PLANKS);
                world.getBlockAt(x, waterY + 1, z).setType(Material.AIR);
            }
        }
        world.getBlockAt(bx, waterY + 1, bz).setType(Material.BARREL);          // 짐칸
        world.getBlockAt(bx - dir[1], waterY + 1, bz + dir[0]).setType(Material.OAK_FENCE);   // 삿대

        int wx = shx - dir[0] * 4 + dir[1] * 7;   // 물가에 처박힌 부서진 배
        int wz = shz - dir[1] * 4 - dir[0] * 7;
        int g = naturalGroundY(world, wx, wz);
        if (g == Integer.MIN_VALUE) {
            return;
        }
        for (int a = -2; a <= 2; a++) {
            int x = wx + a, z = wz;
            if (naturalGroundY(world, x, z) == Integer.MIN_VALUE) {
                continue;
            }
            world.getBlockAt(x, g + 1, z).setType(a == 1 ? Material.AIR : Material.SPRUCE_PLANKS);
            if (a != 1 && a != -2) {
                world.getBlockAt(x, g + 2, z + 1).setType(Material.SPRUCE_SLAB);   // 벌어진 뱃전
            }
        }
        world.getBlockAt(wx + 3, g + 1, wz).setType(Material.OAK_FENCE);           // 부러진 노
        world.getBlockAt(wx - 1, g + 2, wz + 1).setType(Material.COBWEB);
    }

    /**
     * 침몰선 — 등록 기연 sunken_ship_manual 의 무대. 부지에서 **가장 깊은 물**에 눕는다.
     * 선체 13x5, 뱃머리가 들리고 고물이 처박힌 기울기(상수식). 가운데 선실은 물이 찬 채로 열려 있어
     * **잠수해서 들어간다** — 그 안에 봉인된 방수 유통(궤짝)이 비급을 품는다.
     */
    private static int[] ferryWreck(World world, int sx, int sz, int waterY, int[] dir) {
        int bx = 0, bz = 0, bd = 0;
        for (int x = sx - FR_R + 3; x <= sx + FR_R - 3; x++) {   // 가장 깊은 물칸 (선체가 다 잠길 자리)
            for (int z = sz - FR_R + 3; z <= sz + FR_R - 3; z++) {
                int d = waterDepth(world, x, z);
                if (d > bd) {
                    bd = d;
                    bx = x;
                    bz = z;
                }
            }
        }
        if (bd < 4) {
            return null;
        }
        int floor = waterY - bd + 1;   // 호수 바닥 바로 위
        boolean alongX = dir[0] == 0;  // 선체는 물가와 나란히 눕는다
        for (int a = -6; a <= 6; a++) {
            int lift = Math.max(0, (a + 2) / 3);   // 뱃머리가 들린다 (상수식 기울기)
            for (int b = -2; b <= 2; b++) {
                int x = bx + (alongX ? a : b);
                int z = bz + (alongX ? b : a);
                int y = floor + lift;
                if (Math.abs(b) == 2 || Math.abs(a) == 6) {                 // 뱃전 — 갈라진 곳이 있다
                    if (hash(x, z, 7) == 0) {
                        continue;   // 부서져 뚫린 자리 (잠수해 들어가는 문)
                    }
                    world.getBlockAt(x, y, z).setType(Material.DARK_OAK_PLANKS);
                    world.getBlockAt(x, y + 1, z).setType(hash(x + 3, z, 5) == 0
                            ? Material.WATER : Material.DARK_OAK_PLANKS);
                } else if (Math.abs(a) >= 4) {                              // 바닥 — 이물·고물
                    world.getBlockAt(x, y, z).setType(Material.OAK_PLANKS);
                } else {                                                    // 선실 — 물이 찬 통칸
                    world.getBlockAt(x, y, z).setType(Material.OAK_PLANKS);
                    world.getBlockAt(x, y + 1, z).setType(Material.WATER);
                    world.getBlockAt(x, y + 2, z).setType(Material.WATER);
                }
            }
        }
        // 갈비뼈처럼 드러난 늑재
        for (int a = -5; a <= 5; a += 3) {
            int x = bx + (alongX ? a : 3);
            int z = bz + (alongX ? 3 : a);
            putWet(world, x, floor + Math.max(0, (a + 2) / 3) + 1, z, Material.DARK_OAK_LOG);
        }
        // 부러진 돛대 — 선실 바닥(floor)에서 곧게 오른다. 수면 위로 한두 칸 삐져나오면 그것이
        // 나루에서 보이는 **표식**이다 ("저기 물 밑에 배가 있다" — 괴담의 물증).
        int mastTop = Math.min(6, waterY - floor + 2);
        for (int k = 1; k <= mastTop; k++) {
            putWet(world, bx, floor + k, bz, Material.STRIPPED_SPRUCE_LOG);
        }
        putWet(world, bx, floor + mastTop + 1, bz, Material.WHITE_WOOL);   // 삭은 돛 조각
        // 비급이 봉인된 방수 유통 — 선실 안. 그 곁에 뱃사람의 유해 (잠수해 들어가면 만난다)
        int wy = floor + 1;
        int cx2 = bx + (alongX ? 2 : 0);
        int cz2 = bz + (alongX ? 0 : 2);
        world.getBlockAt(cx2, wy, cz2).setType(Material.BARREL);                          // ★ 침몰선 비급
        world.getBlockAt(bx + (alongX ? -2 : 0), wy, bz + (alongX ? 0 : -2)).setType(Material.CHEST);
        putWet(world, bx + (alongX ? 1 : 1), wy, bz + (alongX ? 1 : 1), Material.BONE_BLOCK);
        for (int a = -6; a <= 6; a += 4) {   // 선체를 삼킨 다시마
            int x = bx + (alongX ? a : -3);
            int z = bz + (alongX ? -3 : a);
            for (int y = floor; y < waterY - 1; y++) {
                if (world.getBlockAt(x, y, z).getType() == Material.WATER) {
                    world.getBlockAt(x, y, z).setType(Material.KELP_PLANT);
                }
            }
        }
        return new int[]{cx2, wy, cz2};
    }

    /** 물가 갈대·마름 — 나루의 물가는 손대지 않은 곳처럼 보여야 한다 */
    private static void ferryReeds(World world, int sx, int sz, int waterY) {
        for (int x = sx - FR_R; x <= sx + FR_R; x++) {
            for (int z = sz - FR_R; z <= sz + FR_R; z++) {
                int h = hash(x + 5501, z - 3307, 100);
                int d = waterDepth(world, x, z);
                if (d == 0) {   // 뭍 — 물가 한 칸 안쪽이면 갈대
                    if (h >= 9) {
                        continue;
                    }
                    int g = naturalGroundY(world, x, z);
                    if (g == Integer.MIN_VALUE || g != waterY
                            || !world.getBlockAt(x, g + 1, z).getType().isAir()) {
                        continue;
                    }
                    boolean nearWater = waterDepth(world, x + 1, z) > 0 || waterDepth(world, x - 1, z) > 0
                            || waterDepth(world, x, z + 1) > 0 || waterDepth(world, x, z - 1) > 0;
                    if (!nearWater) {
                        continue;
                    }
                    world.getBlockAt(x, g + 1, z).setType(Material.SUGAR_CANE);
                    if (h < 4) {
                        world.getBlockAt(x, g + 2, z).setType(Material.SUGAR_CANE);
                    }
                } else if (h < 3 && d >= 2                          // 물 — 마름 잎
                        && world.getBlockAt(x, waterY + 1, z).getType().isAir()) {
                    world.getBlockAt(x, waterY + 1, z).setType(Material.LILY_PAD);
                }
            }
        }
    }

    /** 괴담의 흔적 — 물가 제단(물귀신을 달랜다)·삭은 그물·건져 올린 뼈. 광원은 초 하나(온색) */
    private static void ferryGhost(World world, int shx, int shz, int waterY, int[] dir) {
        int gx = shx - dir[0] * 3 - dir[1] * 9;
        int gz = shz - dir[1] * 3 + dir[0] * 9;
        int g = naturalGroundY(world, gx, gz);
        if (g == Integer.MIN_VALUE) {
            return;
        }
        for (int dx = -1; dx <= 1; dx++) {          // 돌 제단 — 이끼 낀 두 단
            for (int dz = -1; dz <= 1; dz++) {
                world.getBlockAt(gx + dx, g + 1, gz + dz).setType(hash(gx + dx, gz + dz, 3) == 0
                        ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE);
            }
        }
        world.getBlockAt(gx, g + 2, gz).setType(Material.CHISELED_STONE_BRICKS);
        Candle candle = (Candle) Material.CANDLE.createBlockData();
        candle.setLit(true);
        candle.setCandles(3);
        world.getBlockAt(gx, g + 3, gz).setBlockData(candle);   // 온색 — 누군가 아직 켜 두고 간다
        placeSign(world, gx, g + 2, gz - 2, BlockFace.SOUTH, "물에 든 이를 위하여", "이름은 적지 않는다");
        for (int[] n : new int[][]{{3, 1}, {4, 2}, {2, 3}, {5, 1}}) {   // 널어 둔 그물 — 삭았다
            int x = gx + n[0], z = gz + n[1];
            int ng = naturalGroundY(world, x, z);
            if (ng == Integer.MIN_VALUE || !world.getBlockAt(x, ng + 1, z).getType().isAir()) {
                continue;
            }
            world.getBlockAt(x, ng + 1, z).setType(Material.OAK_FENCE);
            world.getBlockAt(x, ng + 2, z).setType(Material.OAK_FENCE);
            world.getBlockAt(x, ng + 3, z).setType(Material.COBWEB);
        }
        int bx = gx - 3, bz = gz + 2;
        int bg = naturalGroundY(world, bx, bz);
        if (bg != Integer.MIN_VALUE && world.getBlockAt(bx, bg + 1, bz).getType().isAir()) {
            world.getBlockAt(bx, bg + 1, bz).setType(Material.BONE_BLOCK);   // 건져 올린 것
        }
    }

    /**
     * 나루 이정표 — 남문 밖 관도 갓길(담에서 86칸 = 검수 ⑩ 링 안). 나루의 실제 방위를 읽어 쓴다.
     * 담 밖 채움(⑩)은 **바닥 기준(≥5%)** 이므로 표지 한 점이 늘어도 위반이 아니다 (오히려 오른다).
     */
    private static void ferrySign(World world, int cx, int cz, int sx, int sz) {
        int x = cx + 4, z = cz + 86;
        int g = outsideGroundY(world, x, z);
        if (g == Integer.MIN_VALUE || !outsideVacant(world, x, g + 1, z)
                || !outsideVacant(world, x, g + 2, z)) {
            return;
        }
        int dx = sx - cx, dz = sz - cz;
        String bearing = (dz > 40 ? "남" : dz < -40 ? "북" : "") + (dx > 40 ? "동" : dx < -40 ? "서" : "");
        world.getBlockAt(x, g + 1, z).setType(Material.OAK_FENCE);
        placeSign(world, x, g + 2, z, BlockFace.WEST,
                "흑수나루 " + (bearing.isEmpty() ? "→" : bearing + "쪽"), "수로 물류 — 물귀신 소문");
    }

    /**
     * 나루 못 — 후보 16곳에 물가가 하나도 없을 때만 돈다 (사막·고원 시드의 보험).
     * 부지 절반을 깊이 7 로 파고 물을 채운다. 결정론 상수식 — 같은 월드면 같은 못.
     */
    private static void ferryDig(World world, int sx, int sz) {
        int base = Integer.MIN_VALUE;
        for (int x = sx - FR_R; x <= sx + FR_R; x += 4) {
            for (int z = sz - FR_R; z <= sz + FR_R; z += 4) {
                int g = naturalGroundY(world, x, z);
                if (g != Integer.MIN_VALUE) {
                    base = Math.max(base, g);
                }
            }
        }
        if (base == Integer.MIN_VALUE) {
            return;
        }
        for (int x = sx - FR_R + 2; x <= sx + FR_R - 2; x++) {
            for (int z = sz + 1; z <= sz + FR_R - 2; z++) {   // 부지 남쪽 절반이 못이 된다
                int rim = Math.min(x - (sx - FR_R + 2), Math.min((sx + FR_R - 2) - x,
                        Math.min(z - sz, (sz + FR_R - 2) - z)));
                int depth = Math.min(7, rim);
                if (depth <= 0) {
                    continue;
                }
                for (int y = base + 8; y > base; y--) {
                    world.getBlockAt(x, y, z).setType(Material.AIR);
                }
                for (int y = base; y > base - depth; y--) {
                    world.getBlockAt(x, y, z).setType(Material.WATER);
                }
                world.getBlockAt(x, base - depth, z).setType(Material.GRAVEL);
            }
        }
    }
}
