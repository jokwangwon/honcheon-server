package com.honcheon.mvt;

import org.bukkit.Axis;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        clearAndFlatten(world, cx, cy, cz);
        blendEdge(world, cx, cy, cz);        // F31 — 경계 절단면을 자연 지형으로 완사면 접합
        groundCover(world, cx, cy, cz);      // v6.2 ① — 담장 안은 사람이 밟고 사는 땅이다 (잔디 공원 폐기)
        plazaAndWell(world, cx, cy, cz);
        roads(world, cx, cy, cz);
        townWall(world, cx, cy, cz);
        plazaTreesAndFlowerBeds(world, cx, cy, cz);

        Map<String, Location> anchors = new LinkedHashMap<>();
        anchors.put("장터", loc(world, cx, cy + 1, cz));

        // 앵커 건물 4채 — cheongha_hyeon.yml places. 객잔은 2층 대형(17x13), 나머지 13x11.
        // 도로변 입구: 북쪽 두 채는 남향, 남쪽 두 채는 북향(광장을 바라본다).
        anchors.put("청하객잔", inn(world, cx, cy, cz));
        anchors.put("의뢰소", house(world, cx + 11, cy, cz - 17, 13, 11, 4, false,
                "의뢰소", "정파 연락망 — 일과 보수"));
        anchors.put("의방", house(world, cx - 24, cy, cz + 8, 13, 11, 4, true,
                "약재상 · 의방", "외상 장부 있음"));
        anchors.put("전장", house(world, cx + 12, cy, cz + 8, 13, 11, 4, true,
                "청하전장", "전표 = 가져온 이가 임자"));
        anchors.put("표국", pyoguk(world, cx, cy, cz));   // v6 ① — 등록 장소 pyoguk (키는 추가만, 기존 6키 불변)

        medicineInterior(world, cx, cy, cz);
        exchangeInterior(world, cx, cy, cz);
        requestOfficeInterior(world, cx, cy, cz);
        bulletinBoard(world, cx, cy, cz);
        cottages(world, cx, cy, cz);         // 일반 민가 9채 — 마을의 생기 (앵커·구역·NPC 없음)
        doorPaths(world, cx, cy, cz);
        alleys(world, cx, cy, cz);
        cottageDoorsteps(world, cx, cy, cz);  // v6.2 ② — 골목을 깐 뒤 문턱 3칸을 디딤돌로 (골목이 디딤돌을 덮지 않게)
        yards(world, cx, cy, cz);            // v6.2 ① — 필지 울타리·뒤뜰 텃밭·생활 흔적 (울타리가 마을을 마을로 만든다)

        marketStalls(world, cx, cy, cz);
        streetLanterns(world, cx, cy, cz);   // v6.1 ① — 길을 7칸으로 넓혔으므로 등롱은 건물·길을 다 세운 뒤 빈 자리에만 선다
        weeds(world, cx, cy, cz);            // v6.2 ① — 잡초는 맨 끝 (등롱·소품 자리를 뺏지 않는다)
        placeSign(world, cx + 5, cy + 1, cz - 58, BlockFace.WEST, "북쪽 산길 →", "늑대·여우 — 도적 소문 있음");   // 대로(±3) 밖 갓길
        anchors.put("북쪽_산길", loc(world, cx, cy + 1, cz - 59));

        // NPC 6인 — cheongha_npcs.yml (이름 = 등록제 명사)
        npc(world, anchors.get("청하객잔"), 0f, "객잔 주인 한백");
        npc(world, anchors.get("의뢰소"), 0f, "의뢰소 관리인 소연");
        npc(world, anchors.get("의방"), 180f, "의원 유문");
        npc(world, anchors.get("전장"), 180f, "전장 지점주 금서방");   // F28 — 조문원은 현령이다 (등록부 정합)
        npc(world, loc(world, cx + 3, cy + 1, cz + 3), 135f, "표사 곽진");   // 우물 쪽
        npc(world, loc(world, cx + 8, cy + 1, cz - 6), 90f, "장터 잡화상 장쇠");   // market_peddler — 붉은 차양 좌판 뒤, 광장(서쪽)을 본다
        npc(world, anchors.get("표국"), 0f, "표국주 진철산");   // v6 ① — 등록부 jincheolsan (본채 중앙, 대문 쪽을 본다)

        zones(world, cx, cy, cz, zonesOut);
        abandonedShrine(world, cx, cy, cz, zonesOut);   // v6 ② — 담장 밖 외곽 스팟 조성 (평탄화 밖, 구역만 추가)
        return anchors;
    }

    // ─── 구역 — 입장 타이틀의 단위 (마을 전체 → 건물·장터 순으로 좁아진다) ───

    private static void zones(World world, int cx, int cy, int cz, List<Zone> out) {
        String w = world.getName();
        out.add(new Zone("청하현", "섬서의 작은 현 — 강호의 첫 걸음", w,
                cx - 60, cy - 2, cz - 60, cx + 60, cy + 19, cz + 60));
        out.add(new Zone("청하객잔", "소문은 국밥보다 빨리 식는다", w,
                cx - 28, cy - 2, cz - 18, cx - 12, cy + 19, cz - 6));
        out.add(new Zone("의뢰소", "정파 연락망 — 일과 보수", w,
                cx + 11, cy - 2, cz - 17, cx + 23, cy + 13, cz - 7));
        out.add(new Zone("의방", "약재상 — 외상 장부 있음", w,
                cx - 24, cy - 2, cz + 8, cx - 12, cy + 13, cz + 18));
        out.add(new Zone("청하전장", "전표 = 가져온 이가 임자", w,
                cx + 12, cy - 2, cz + 8, cx + 24, cy + 13, cz + 18));
        out.add(new Zone("장터", "가죽 매입 — /혼천 팔기", w,
                cx + 5, cy - 2, cz - 7, cx + 15, cy + 7, cz + 7));
        out.add(new Zone("철산표국", "표행은 신용 장사 — 한 번 깬 자와는 두 번 일하지 않는다", w,   // v6 ①
                cx + PY_X0, cy - 2, cz + PY_Z0, cx + PY_X1, cy + 14, cz + PY_Z1));
    }

    // ─── 지형 ───

    private static void clearAndFlatten(World world, int cx, int cy, int cz) {
        for (int x = cx - 62; x <= cx + 62; x++) {
            for (int z = cz - 62; z <= cz + 62; z++) {
                world.getBlockAt(x, cy, z).setType(Material.GRASS_BLOCK);
                for (int y = cy + 1; y <= cy + 18; y++) {   // 객잔 용마루 cy+17 여유
                    world.getBlockAt(x, y, z).setType(Material.AIR);
                }
            }
        }
    }

    /** F29 — 조성 영역 내 기존 혼천 NPC(명패+무적 주민) 제거 — 재조성 = 같은 마을, NPC도 한 벌 */
    private static void clearNpcs(World world, int cx, int cy, int cz) {
        BoundingBox box = new BoundingBox(cx - 63, cy - 8, cz - 63, cx + 64, cy + 20, cz + 64);
        for (Entity e : world.getNearbyEntities(box)) {
            if (e instanceof Villager v && v.getCustomName() != null && v.isInvulnerable()) {
                v.remove();
            }
        }
    }

    /** F31 — 평탄화 경계의 수직 절단면을 6칸 완사면으로 자연 지형에 접합 */
    private static void blendEdge(World world, int cx, int cy, int cz) {
        int r = 62;
        int skirt = 6;
        for (int x = cx - r - skirt; x <= cx + r + skirt; x++) {
            for (int z = cz - r - skirt; z <= cz + r + skirt; z++) {
                int d = Math.max(Math.abs(x - cx), Math.abs(z - cz)) - r;
                if (d <= 0 || d > skirt) {
                    continue;
                }
                int natural = world.getHighestBlockYAt(x, z);
                int target = cy + (natural - cy) * d / (skirt + 1);   // 안쪽 = 마을 높이, 밖 = 자연
                if (natural > target) {
                    for (int y = target + 1; y <= natural; y++) {
                        world.getBlockAt(x, y, z).setType(Material.AIR);
                    }
                } else {
                    for (int y = natural; y < target; y++) {
                        world.getBlockAt(x, y, z).setType(Material.DIRT);
                    }
                }
                world.getBlockAt(x, target, z).setType(Material.GRASS_BLOCK);
            }
        }
    }

    // ─── v6.2 ① 지면 — 향촌은 사람이 밟고 사는 땅이다 ───
    //
    // 조감도에서 담장 안이 온통 초록이었다. 길만 흙이고 나머지가 잔디면 그건 마을이 아니라 초원이다.
    // groundCover 는 담장 안(±59) 지표 전체를 좌표 해시로 다진 흙 조직으로 갈아엎는다.
    // 이 패스는 광장·길·담장·건물보다 **먼저** 돈다 — 뒤에 오는 것들이 제 자리를 덮어쓴다 (충돌 0의 구조적 보장).
    // 자재 선택 규칙: DIRT_PATH·GRAVEL 은 '길'의 자재다. 일반 지면에 뿌리면 길과 마당의 구분이 사라진다.

    /** 지면 조직 — 거친 흙 62% · 흙 19% · 뿌리 흙 6% · 잔디 12.5% (잔디는 남길 곳만 남긴다) */
    private static void groundCover(World world, int cx, int cy, int cz) {
        for (int x = cx - 59; x <= cx + 59; x++) {
            for (int z = cz - 59; z <= cz + 59; z++) {
                int h = Math.floorMod(x * 7 + z * 11, 16);
                Material m;
                if (h < 2) {
                    m = Material.GRASS_BLOCK;      // 12.5% — 밟히지 않는 자리에만 풀이 남는다
                } else if (h == 2) {
                    m = Material.ROOTED_DIRT;
                } else if (h <= 5) {
                    m = Material.DIRT;
                } else {
                    m = Material.COARSE_DIRT;      // 다진 흙 — 마을의 바탕색
                }
                world.getBlockAt(x, cy, z).setType(m);
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
        return dx >= 36 && dx <= 48 && dz >= 16 && dz <= 36;    // 표국 진입 소로
    }

    /** 아직 아무도 쓰지 않은 맨땅인가 — 지면이 groundCover 자재이고 그 위가 비었고 예약 구역이 아니다 */
    private static boolean freeCell(World world, int cx, int cy, int cz, int x, int z) {
        if (reserved(x - cx, z - cz)) {
            return false;
        }
        Material g = world.getBlockAt(x, cy, z).getType();
        boolean bare = g == Material.COARSE_DIRT || g == Material.DIRT
                || g == Material.ROOTED_DIRT || g == Material.GRASS_BLOCK;
        return bare && world.getBlockAt(x, cy + 1, z).getType().isAir();
    }

    /**
     * 민가 뒤뜰 텃밭 7필지 — {x0, x1, z0, z1, 울타리 종류}. 전부 상수 (난수 금지).
     * 울타리: 0 = 참나무 목책 / 1 = 대나무 목책 / 2 = 낮은 돌담. 민가 유형에 따라 고정.
     * 좌표는 민가 처마(벽±3)와 골목·대로 밖으로 잡았고, 그래도 어긋나면 freeCell 이 알아서 건너뛴다.
     */
    private static final int[][] GARDENS = {
            {-43, -35, -39, -35, 0},   // #1 대장간 뒤뜰 (북서 들판) — 울타리 줄이 #1 서까래(-33) 밖
            {-24, -20, -36, -32, 2},   // #2 ㄱ자집 곁밭
            {9, 17, -39, -35, 1},      // #3 일자집 뒤뜰 — 울타리 줄이 #3 서까래(-33) 밖
            {29, 36, -42, -38, 0},     // #4 다락집 뒤뜰 — 울타리 줄이 #4 서까래(-36) 밖
            {-56, -50, 9, 15, 2},      // #9 서편 밭 (담장 발치 안쪽)
            {-27, -23, 25, 29, 1},     // #6 ㄱ자집 곁밭
            {8, 16, 35, 39, 0},        // #7 직조간 뒤뜰
    };

    /** 생활 흔적 — {x, z, 종류}. 0 장독대 / 1 장작더미 / 2 빨랫줄 / 3 닭장 / 4 퇴비통 */
    private static final int[][] LIFE_TRACES = {
            {-46, -28, 1}, {-46, -26, 0}, {-47, -31, 3},   // #1 대장간 서편
            {-8, -34, 0}, {-8, -32, 1},                    // #2 안마당
            {22, -28, 0}, {22, -26, 1}, {23, -33, 3},      // #3 동편
            {39, -30, 0}, {39, -27, 2}, {39, -33, 4},      // #4 동편
            {44, -8, 0}, {47, -8, 1}, {50, -9, 3},         // #5 남편
            {-45, 25, 0}, {-45, 28, 1}, {-47, 32, 3},      // #6 서편
            {6, 26, 0}, {6, 29, 1}, {10, 32, 2}, {14, 33, 4},   // #7 서·남편
            {32, 26, 0}, {32, 29, 1},                      // #8 서편
            {-31, 10, 0}, {-31, 13, 1}, {-31, 16, 2},      // #9 동편
    };

    /** 필지 — 텃밭·울타리·생활 흔적. "울타리가 마을을 마을로 만든다" */
    private static void yards(World world, int cx, int cy, int cz) {
        for (int[] g : GARDENS) {
            farmPlot(world, cx, cy, cz, cx + g[0], cx + g[1], cz + g[2], cz + g[3]);
            plotFence(world, cx, cy, cz, cx + g[0] - 1, cx + g[1] + 1, cz + g[2] - 1, cz + g[3] + 1,
                    fenceMat(g[4]));
        }
        for (int[] t : LIFE_TRACES) {
            lifeTrace(world, cx, cy, cz, cx + t[0], cz + t[1], t[2]);
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
     * 텃밭 한 필지 — 경작지 + 작물(밀·당근·비트, 좌표 해시로 배분) + 가운데 세로 물길 1칸.
     * 작물은 Ageable.setAge(max) 로 성장 단계를 고정한다 — 결정론 (같은 마을이면 같은 밭).
     */
    private static void farmPlot(World world, int cx, int cy, int cz, int x0, int x1, int z0, int z1) {
        int wx = (x0 + x1) / 2;   // 물길 — 밭 가운데를 세로로 가른다 (사방 4칸 이내 = 물 댄 논밭)
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!freeCell(world, cx, cy, cz, x, z)) {
                    continue;
                }
                if (x == wx) {
                    world.getBlockAt(x, cy, z).setType(Material.WATER);
                    continue;
                }
                world.getBlockAt(x, cy, z).setType(Material.FARMLAND);
                Material crop = switch (Math.floorMod(x * 3 + z * 5, 3)) {
                    case 0 -> Material.WHEAT;
                    case 1 -> Material.CARROTS;
                    default -> Material.BEETROOTS;
                };
                BlockData data = crop.createBlockData();
                Ageable age = (Ageable) data;
                age.setAge(age.getMaximumAge());   // 성장 고정 — 재조성해도 같은 이삭
                world.getBlockAt(x, cy + 1, z).setBlockData(age);
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
                    world.getBlockAt(x, cy + 1, z).setType(mat);
                }
            }
        }
        for (int z = z0 + 1; z <= z1 - 1; z++) {
            for (int x : new int[]{x0, x1}) {
                if (freeCell(world, cx, cy, cz, x, z)) {
                    world.getBlockAt(x, cy + 1, z).setType(mat);
                }
            }
        }
    }

    /** 생활 흔적 한 무더기 — 장독대·장작더미·빨랫줄·닭장·퇴비통 (전부 freeCell 검사 통과분만) */
    private static void lifeTrace(World world, int cx, int cy, int cz, int x, int z, int kind) {
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
                    for (int y = cy + 1; y <= cy + 2; y++) {
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
                    for (int y = cy + 1; y <= cy + 3; y++) {
                        world.getBlockAt(x, y, z + dz).setType(Material.SPRUCE_FENCE);
                    }
                }
                Orientable chain = (Orientable) Material.CHAIN.createBlockData();
                chain.setAxis(Axis.Z);
                world.getBlockAt(x, cy + 3, z + 1).setBlockData(chain);
                world.getBlockAt(x, cy + 2, z + 1).setType(Material.WHITE_WOOL);   // 널어 둔 천
            }
            case 3 -> {   // 닭장 — 목책 3x3(삽짝 한 칸) + 짚
                for (int dx = 0; dx <= 2; dx++) {
                    for (int dz = 0; dz <= 2; dz++) {
                        boolean rim = dx == 0 || dx == 2 || dz == 0 || dz == 2;
                        if (!freeCell(world, cx, cy, cz, x + dx, z + dz)) {
                            continue;
                        }
                        if (rim && !(dx == 1 && dz == 0)) {
                            world.getBlockAt(x + dx, cy + 1, z + dz).setType(Material.OAK_FENCE);
                        } else if (dx == 1 && dz == 1) {
                            world.getBlockAt(x + dx, cy + 1, z + dz).setType(Material.HAY_BLOCK);
                        }
                    }
                }
            }
            default -> {   // 퇴비통 + 통
                putProp(world, cx, cy, cz, x, z, Material.COMPOSTER);
                putProp(world, cx, cy, cz, x + 1, z, Material.BARREL);
            }
        }
    }

    private static void putProp(World world, int cx, int cy, int cz, int x, int z, Material mat) {
        if (freeCell(world, cx, cy, cz, x, z)) {
            world.getBlockAt(x, cy + 1, z).setType(mat);
        }
    }

    /**
     * 공터 잡초 — 다진 흙 위에 좌표 해시로 잡풀·고사리. 마지막에 돈다:
     * 등롱·노점·울타리가 이미 선 자리는 freeCell 이 아니므로 잡초가 그 자리를 뺏지 못한다.
     */
    private static void weeds(World world, int cx, int cy, int cz) {
        for (int x = cx - 57; x <= cx + 57; x++) {
            for (int z = cz - 57; z <= cz + 57; z++) {
                int h = Math.floorMod(x * 13 + z * 5, 9);
                if (h > 1 || !freeCell(world, cx, cy, cz, x, z)) {
                    continue;
                }
                world.getBlockAt(x, cy + 1, z).setType(
                        h == 0 ? Material.SHORT_GRASS : Material.FERN);
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
                int h = Math.floorMod((x - cx) * 7 + (z - cz) * 11, 12);
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
            Orientable chain = (Orientable) Material.CHAIN.createBlockData();
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
        int h = Math.floorMod(x * 7 + z * 11, 10);
        Material top;
        if (shoulder) {
            top = h < 6 ? Material.GRAVEL : Material.COARSE_DIRT;
        } else {
            top = h == 0 ? Material.GRAVEL : h == 1 ? Material.COARSE_DIRT : Material.DIRT_PATH;
        }
        world.getBlockAt(x, cy, z).setType(top);
        world.getBlockAt(x, cy - 1, z).setType(Material.COARSE_DIRT);   // 노반 — 흙길 밑 다짐
    }

    /** 디딤돌 — 흙길에서 문지방으로 들어가는 전이. 조약돌/안산암 교대 (좌표 해시) */
    private static void steppingStone(World world, int x, int cy, int z) {
        world.getBlockAt(x, cy, z).setType(
                Math.floorMod(x * 3 + z * 5, 2) == 0 ? Material.COBBLESTONE : Material.ANDESITE);
        world.getBlockAt(x, cy - 1, z).setType(Material.COARSE_DIRT);
    }

    /**
     * 건물 입구 ↔ 대로 연결 소로 — 폭 3칸 (문이 2칸인 객잔만 4칸). 대로 접속부 한 줄은 디딤돌.
     * 대로가 x/z ±3 을 먹으므로 소로는 ±4 에서 끝난다 (대로가 소로를 삼키지 않게).
     */
    private static void doorPaths(World world, int cx, int cy, int cz) {
        // v6.2 ② — 소로가 아니라 '앞마당'이다. 문 앞 7~8칸 폭 다진 흙 + 문지방 줄 디딤돌 → 대로 접속.
        doorPath(world, cx - 24, cx - 17, cz - 5, cz - 4, cy, cz - 5);          // 객잔 (남향 2칸 대문)
        doorPath(world, cx + 14, cx + 20, cz - 6, cz - 4, cy, cz - 6);          // 의뢰소 (남향 문)
        doorPath(world, cx - 21, cx - 15, cz + 4, cz + 7, cy, cz + 7);          // 의방 (북향 문)
        doorPath(world, cx + 15, cx + 21, cz + 4, cz + 7, cy, cz + 7);          // 전장 (북향 문)
        doorPath(world, cx - 41, cx - 37, cz + 17, cz + 18, cy, cz + 17);       // 민가 9 → 남골목
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
            {-11, -21},               // #2
            {14, -21},                // #3
            {33, -21},                // #4
            {48, -19},                // #5 (북향 — 골목 남쪽 줄)
            {-37, 21},                // #6
            {14, 21}, {22, 21},       // #7 본채 · 직조간
            {38, 21},                 // #8
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
        int h = Math.floorMod(x * 7 + z * 11, 10);
        world.getBlockAt(x, cy, z).setType(
                edge && h < 2 ? Material.COARSE_DIRT : Material.DIRT_PATH);
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
        if (m == 0 || m == 5) {   // 기와 갓 구간의 양끝 = 갓의 처마
            BlockFace face = alongX
                    ? (m == 0 ? BlockFace.WEST : BlockFace.EAST)
                    : (m == 0 ? BlockFace.NORTH : BlockFace.SOUTH);
            stair(world, x, cy + 3, z, Material.DEEPSLATE_TILE_STAIRS, face);
        } else {
            world.getBlockAt(x, cy + 3, z).setType(Material.DEEPSLATE_TILE_SLAB);   // 기와 갓
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
                roofBlock(world, tx + dx, cy + 6, tz + dz, face, corner, RoofStyle.TILE);
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

    // ─── 가로 시설 — 등롱·조경 ───

    /**
     * 가로 등롱 — 대로 갓길(±3) 밖 ±5 열. 골목 줄(z∓20±1)과 겹치지 않게 간격 7 (d=12,19,26,...).
     * v6.1 ①: 길을 7칸으로 넓히면서 등롱 열이 노면 위로 올라오는 사고가 나므로,
     * 조성 순서를 건물·노점 뒤로 미루고 "빈 자리에만 선다"는 규칙을 코드로 강제한다.
     */
    private static void streetLanterns(World world, int cx, int cy, int cz) {
        for (int d = 12; d <= 54; d += 7) {
            for (int side = -5; side <= 5; side += 10) {
                lanternPost(world, cx + side, cy, cz - d);   // 남북대로 양측
                lanternPost(world, cx + side, cy, cz + d);
                lanternPost(world, cx - d, cy, cz + side);   // 동서대로 양측
                lanternPost(world, cx + d, cy, cz + side);
            }
        }
    }

    /**
     * 등롱 기둥 — 지면이 길·맨땅이고 위 3칸이 비었을 때만 선다 (건물·처마·담을 뚫지 않는다).
     * v6.2 ①: groundCover 가 흙·뿌리 흙을 깔면서 지면 판정을 넓히지 않으면 등롱이 통째로 사라진다
     * (→ 야간 암흑 위반). 등롱 열(±5)은 reserved 이므로 울타리·텃밭·잡초가 그 자리를 뺏지 못한다.
     */
    private static void lanternPost(World world, int x, int cy, int z) {
        Material ground = world.getBlockAt(x, cy, z).getType();
        boolean open = ground == Material.GRASS_BLOCK || ground == Material.DIRT_PATH
                || ground == Material.GRAVEL || ground == Material.COARSE_DIRT
                || ground == Material.DIRT || ground == Material.ROOTED_DIRT;
        for (int y = cy + 1; y <= cy + 3; y++) {
            open &= world.getBlockAt(x, y, z).getType().isAir();
        }
        if (!open) {
            return;
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
                                  boolean doorNorth, String name, String subtitle) {
        shell(world, x0, y0, z0, w, d, wallH, doorNorth,
                WallStyle.PLASTER_WHITE, RoofStyle.TILE, true);   // 주요 건물 = 수묵 3색 + 팔작(격식)
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
    private static void shell(World world, int x0, int y0, int z0, int w, int d, int wallH,
                              boolean doorNorth, WallStyle ws, RoofStyle rs, boolean paljak) {
        shell(world, x0, y0, z0, w, d, wallH, doorNorth, ws, rs, paljak, 2);
    }

    private static void shell(World world, int x0, int y0, int z0, int w, int d, int wallH,
                              boolean doorNorth, WallStyle ws, RoofStyle rs, boolean paljak, int eave) {
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
                        boolean stud = ws == WallStyle.FRAME_GRAY && (x + z) % 3 == 0;   // 목골 노출 스터드
                        if (stud) {
                            world.getBlockAt(x, y, z).setType(Material.DARK_OAK_PLANKS);
                        } else if (y == y0 + 2 && (x + z) % 2 == 0) {
                            world.getBlockAt(x, y, z).setType(Material.GLASS_PANE);      // 격자창
                        } else {
                            wallBlock(world, x, y, z, ws, z == z0 || z == z1);           // 벽 조직
                        }
                    }
                }
            }
        }
        // v6.1 ② — 처마를 전 방향 eave 칸 내밀고, 물매는 2:1 (roofShape). 팔작 = 관아·상가, 맞배 = 민가.
        int ex0 = x0 - eave, ez0 = z0 - eave, ex1 = x1 + eave, ez1 = z1 + eave;
        if (paljak) {
            roofShape(world, ex0, ez0, ex1, ez1, y0 + wallH + 1, rs,
                    Material.WHITE_TERRACOTTA, 99, true, eave >= 2);   // 합각벽 = 백벽 (하부 링이 다 먹고 남는 만큼만)
        } else {
            roofShape(world, ex0, ez0, ex1, ez1, y0 + wallH + 1, rs,
                    Material.DARK_OAK_PLANKS, 0, false, eave >= 2);    // 박공널 = 흑목 1열, 벽면보다 eave 칸 내민 그림자 선
        }
        if (eave >= 2) {   // 활주 — 깊은 처마 네 귀를 받치는 툇기둥
            eavePosts(world, ex0 + 1, y0, ez0 + 1, ex1 - 1, ez1 - 1, y0 + wallH);
        }
        int doorX = x0 + w / 2;
        int doorZ = doorNorth ? z0 : z1;
        world.getBlockAt(doorX, y0 + 1, doorZ).setType(Material.AIR);
        world.getBlockAt(doorX, y0 + 2, doorZ).setType(Material.AIR);
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
        int x0 = cx - 28, z0 = cz - 18, w = 17, d = 13;
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
                    } else if ((y == cy + 2 || y == cy + 7) && (x + z) % 2 == 0) {
                        m = Material.GLASS_PANE;                         // 1·2층 격자창
                    } else {
                        m = Material.WHITE_TERRACOTTA;                   // 백벽
                    }
                    world.getBlockAt(x, y, z).setType(m);
                }
            }
        }
        // v6.1 ② — 처마 전 방향 2칸 + 물매 2:1 + 도톰한 용마루 (용마루 cy+14, 덧단 cy+15)
        roofShape(world, x0 - 2, z0 - 2, x1 + 2, z1 + 2, cy + 10,
                RoofStyle.TILE, Material.WHITE_TERRACOTTA, 99, true, true);
        // 층간 띠 스커트 = 1층 처마 (2층집의 허리선). 위 지붕과 같이 2칸 내밀어 두 겹 처마가 겹쳐 보이게.
        for (int x = x0 - 2; x <= x1 + 2; x++) {
            for (int dz : new int[]{-2, -1, 1, 2}) {
                int z = dz < 0 ? z0 + dz : z1 + dz;
                world.getBlockAt(x, cy + 5, z).setType(
                        Math.abs(dz) == 2 ? Material.DEEPSLATE_TILE_SLAB : Material.DEEPSLATE_TILES);
            }
        }
        for (int z = z0 - 1; z <= z1 + 1; z++) {
            for (int dx : new int[]{-2, -1, 1, 2}) {
                int x = dx < 0 ? x0 + dx : x1 + dx;
                world.getBlockAt(x, cy + 5, z).setType(
                        Math.abs(dx) == 2 ? Material.DEEPSLATE_TILE_SLAB : Material.DEEPSLATE_TILES);
            }
        }
        for (int x = x0 - 2; x <= x1 + 2; x++) {   // 1층 처마 밑 서까래 라인 (남·북)
            rafter(world, x, cy + 4, z0 - 2);
            rafter(world, x, cy + 4, z1 + 2);
        }
        for (int px : new int[]{cx - 25, cx - 16}) {   // 활주(活柱) 2주 — 정면 툇마루 기둥 (1층 처마를 받는다)
            for (int y = cy + 1; y <= cy + 4; y++) {
                world.getBlockAt(px, y, z1 + 2).setType(Material.SPRUCE_FENCE);
            }
        }
        // 남향 대문 2칸 폭 (cx-21, cx-20)
        for (int x = cx - 21; x <= cx - 20; x++) {
            world.getBlockAt(x, cy + 1, z1).setType(Material.AIR);
            world.getBlockAt(x, cy + 2, z1).setType(Material.AIR);
        }
        for (int x : new int[]{cx - 25, cx - 24, cx - 17, cx - 16}) {   // 미세 변주 — 대문 양옆 연속 창 (주청 개방감)
            world.getBlockAt(x, cy + 2, z1).setType(Material.GLASS_PANE);
        }
        placeSign(world, cx - 18, cy + 1, z1 + 1, BlockFace.SOUTH, "청하객잔", "소문은 국밥보다 빨리 식는다");   // 소로(x-22..-19) 밖
        hangingSign(world, cx - 21, cy + 9, z1 + 2, BlockFace.SOUTH,                 // v6 ④ — 정면 처마 밑 현판
                "청하객잔", "소문은 국밥보다 빨리 식는다");
        chainLantern(world, cx - 23, cy + 4, z1 + 1, 1);                             // 대문 양옆 현수 홍등 2조
        chainLantern(world, cx - 18, cy + 4, z1 + 1, 1);
        for (int y = cy + 1; y <= cy + 5; y++) {                                     // 주기(酒旗) 장대
            world.getBlockAt(cx - 27, y, cz - 3).setType(Material.SPRUCE_FENCE);
        }
        world.getBlockAt(cx - 27, cy + 6, cz - 3).setType(Material.DARK_OAK_PLANKS);   // 장대 끝 가로대
        world.getBlockAt(cx - 26, cy + 6, cz - 3).setType(Material.DARK_OAK_PLANKS);
        hangingSign(world, cx - 26, cy + 5, cz - 3, BlockFace.SOUTH, "청하객잔", "금일 — 국밥·술");
        world.getBlockAt(cx - 27, cy + 1, cz - 2).setType(Material.LANTERN);           // 장대 밑 등롱
        innStairs(world, cx, cy, cz);
        innHall(world, cx, cy, cz);
        innLoft(world, cx, cy, cz);
        return loc(world, cx - 20, cy + 1, cz - 12);   // 앵커 = 1층 주청 중앙 (한백)
    }

    /** 객잔 내부 계단 — 동벽(x=cx-13)을 따라 북쪽으로 오른다. 2층 바닥 개구 + 난간 */
    private static void innStairs(World world, int cx, int cy, int cz) {
        for (int i = 0; i < 4; i++) {
            int z = cz - 8 - i;
            Block step = world.getBlockAt(cx - 13, cy + 1 + i, z);
            Stairs stairs = (Stairs) Material.SPRUCE_STAIRS.createBlockData();
            stairs.setFacing(BlockFace.NORTH);   // 북쪽으로 오르는 디딤
            step.setBlockData(stairs);
            for (int y = cy + 1; y <= cy + i; y++) {
                world.getBlockAt(cx - 13, y, z).setType(Material.SPRUCE_PLANKS);   // 계단 받침
            }
            world.getBlockAt(cx - 13, cy + 5, z).setType(Material.AIR);            // 2층 바닥 개구
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
        // ── 시선 축: 북벽 계산대 (하단 = 판재 상판 / 중단 = 시렁 / 상단 = 술 단지)
        for (int x = cx - 23; x <= cx - 18; x++) {
            world.getBlockAt(x, cy + 1, cz - 16).setType(Material.DARK_OAK_PLANKS);   // 계산대 상판
            topSlab(world, x, cy + 2, cz - 17, Material.DARK_OAK_SLAB);               // 뒤 시렁 (중단)
            world.getBlockAt(x, cy + 3, cz - 17).setType(                             // 시렁 위 술 단지 (상단)
                    Math.floorMod(x, 2) == 0 ? Material.DECORATED_POT : Material.AIR);
        }
        world.getBlockAt(cx - 23, cy + 2, cz - 16).setType(Material.LANTERN);         // 계산대 양 끝 등 (중단)
        world.getBlockAt(cx - 18, cy + 2, cz - 16).setType(Material.LANTERN);
        world.getBlockAt(cx - 19, cy + 1, cz - 17).setType(Material.BARREL);          // 계산대 곁 술통 1 (하단)
        // ── 서벽(x-27) 주방 — 화덕·국솥·화구. 벽 한 면 3점 (여백 규칙)
        hearth(world, cx - 27, cy, cz - 16);
        world.getBlockAt(cx - 27, cy + 1, cz - 15).setType(Material.SMOKER);
        world.getBlockAt(cx - 27, cy + 1, cz - 14).setType(Material.CAULDRON);        // 국솥
        world.getBlockAt(cx - 27, cy + 2, cz - 15).setType(Material.LANTERN);         // 주방 등 (중단)
        // ── 탁자 6 — 좌우 벽을 등지고 두 줄. 대문↔계산대 축(x-21..-20)은 비운다.
        int[][] tables = {{-25, -14}, {-25, -11}, {-24, -8},
                {-16, -14}, {-16, -11}, {-15, -8}};
        for (int[] t : tables) {
            world.getBlockAt(cx + t[0], cy + 1, cz + t[1]).setType(Material.SPRUCE_FENCE);
            world.getBlockAt(cx + t[0], cy + 2, cz + t[1]).setType(Material.SPRUCE_PRESSURE_PLATE);
        }
        world.getBlockAt(cx - 26, cy + 1, cz - 11).setType(Material.DECORATED_POT);   // 상 곁 술 단지 (좌표 상수)
        world.getBlockAt(cx - 15, cy + 1, cz - 12).setType(Material.DECORATED_POT);
        // ── 상단(y+4) 현수 홍등 — 2층 바닥에 매단다. 한백 앵커(x-20,z-12)는 비운다.
        hangingLantern(world, cx - 24, cy + 4, cz - 12);
        hangingLantern(world, cx - 17, cy + 4, cz - 12);
        hangingLantern(world, cx - 20, cy + 4, cz - 9);
        // ── 바닥: 대문 → 계산대 통로 깔개 (사람이 서는 자리, 통행 가능)
        for (int z = cz - 7; z >= cz - 14; z--) {
            world.getBlockAt(cx - 21, cy + 1, z).setType(Material.RED_CARPET);
            world.getBlockAt(cx - 20, cy + 1, z).setType(Material.RED_CARPET);
        }
        placeSign(world, cx - 18, cy + 1, cz - 7, BlockFace.NORTH, "금일 — 국밥", "객방 있음 — 이층");   // 차림 팻말
    }

    /** 객잔 2층 객방 통칸 — 침상(백색 깔개) 5 + 곁깔개, 짐 술통·궤, 등롱 2 */
    private static void innLoft(World world, int cx, int cy, int cz) {
        int[][] beds = {{-27, -17}, {-26, -17}, {-23, -17}, {-22, -17}, {-19, -17}, {-18, -17},
                {-27, -10}, {-27, -9}, {-27, -14}, {-27, -13}};   // 2칸 침상 x5
        for (int[] b : beds) {
            world.getBlockAt(cx + b[0], cy + 6, cz + b[1]).setType(Material.WHITE_CARPET);
        }
        int[][] rugs = {{-26, -16}, {-22, -16}, {-18, -16}, {-26, -9}, {-26, -13}};   // 침상 곁 깔개 (바닥 변화)
        for (int[] r : rugs) {
            world.getBlockAt(cx + r[0], cy + 6, cz + r[1]).setType(Material.LIGHT_GRAY_CARPET);
        }
        world.getBlockAt(cx - 15, cy + 6, cz - 17).setType(Material.CHEST);        // 짐궤 — 묵삼의 방
        world.getBlockAt(cx - 14, cy + 6, cz - 17).setType(Material.BARREL);
        world.getBlockAt(cx - 16, cy + 6, cz - 17).setType(Material.DECORATED_POT);   // 오래 묵는 손님의 짐
        world.getBlockAt(cx - 24, cy + 6, cz - 7).setType(Material.LANTERN);
        world.getBlockAt(cx - 16, cy + 6, cz - 7).setType(Material.LANTERN);
    }

    private static void roofBlock(World world, int x, int y, int z, BlockFace facing,
                                  boolean corner, RoofStyle rs) {
        Block block = world.getBlockAt(x, y, z);
        if (corner) {
            block.setType(solidMat(rs));   // 추녀마루
            return;
        }
        Stairs stairs = (Stairs) stairMat(rs).createBlockData();
        stairs.setFacing(facing);   // 안쪽으로 오르는 기와면
        block.setBlockData(stairs);
    }

    private static Material ridgeMat(RoofStyle rs) {
        return switch (rs) {
            case TILE -> Material.DEEPSLATE_TILE_SLAB;
            case SHINGLE -> Material.DARK_OAK_SLAB;
            case MUD_TILE -> Material.MUD_BRICK_SLAB;
        };
    }

    private static Material solidMat(RoofStyle rs) {
        return switch (rs) {
            case TILE -> Material.DEEPSLATE_TILES;
            case SHINGLE -> Material.DARK_OAK_PLANKS;
            case MUD_TILE -> Material.MUD_BRICKS;
        };
    }

    private static Material stairMat(RoofStyle rs) {
        return switch (rs) {
            case TILE -> Material.DEEPSLATE_TILE_STAIRS;
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
     * thickRidge = 큰 집(객잔·표국·관아)의 도톰한 용마루.
     */
    private static void roofShape(World world, int x0, int z0, int x1, int z1, int yBase,
                                  RoofStyle rs, Material gable, int hipSteps, boolean thickRidge) {
        roofShape(world, x0, z0, x1, z1, yBase, rs, gable, hipSteps, thickRidge, false);
    }

    /**
     * v6.2 ③ — deepEave: 서까래를 지붕 외곽보다 한 칸 더 내밀어 처마 그늘을 진하게 한다
     * (관아·객잔·표국 = 처마 2칸짜리 집에만. 부속채·잡화점은 위계상 얕은 처마 그대로).
     */
    private static void roofShape(World world, int x0, int z0, int x1, int z1, int yBase,
                                  RoofStyle rs, Material gable, int hipSteps, boolean thickRidge,
                                  boolean deepEave) {
        rafterLine(world, x0, z0, x1, z1, yBase - 1);   // 서까래 — 처마 밑단의 그림자 선
        if (deepEave) {
            rafterLine(world, x0 - 1, z0 - 1, x1 + 1, z1 + 1, yBase - 1);   // 한 칸 더 내민 서까래
        }
        int ax = x0, bx = x1, az = z0, bz = z1;
        int s = 0;
        while (s < hipSteps && bx - ax > 2 && bz - az > 2) {   // 우진각(팔작 하부) 링
            hipRing(world, ax, az, bx, bz, yBase + rise(s), rs, flat(s));
            ax++;
            bx--;
            az++;
            bz--;
            s++;
        }
        boolean ridgeX = (bx - ax) >= (bz - az);   // 용마루는 장변을 따라 눕는다
        boolean vent = true;                       // 환기창은 합각·박공 첫 단에 한 칸
        while (ridgeX ? bz - az > 1 : bx - ax > 1) {
            int y = yBase + rise(s);
            boolean solid = flat(s);
            if (ridgeX) {
                for (int x = ax; x <= bx; x++) {
                    roofCell(world, x, y, az, BlockFace.SOUTH, rs, solid || groove(x));
                    roofCell(world, x, y, bz, BlockFace.NORTH, rs, solid || groove(x));
                }
                int mid = (az + bz) / 2;
                for (int z = az + 1; z <= bz - 1; z++) {
                    gableBlock(world, ax, y, z, gable, vent && z == mid);
                    gableBlock(world, bx, y, z, gable, vent && z == mid);
                }
                az++;
                bz--;
            } else {
                for (int z = az; z <= bz; z++) {
                    roofCell(world, ax, y, z, BlockFace.EAST, rs, solid || groove(z));
                    roofCell(world, bx, y, z, BlockFace.WEST, rs, solid || groove(z));
                }
                int mid = (ax + bx) / 2;
                for (int x = ax + 1; x <= bx - 1; x++) {
                    gableBlock(world, x, y, az, gable, vent && x == mid);
                    gableBlock(world, x, y, bz, gable, vent && x == mid);
                }
                ax++;
                bx--;
            }
            vent = false;
            s++;
        }
        ridge(world, ax, az, bx, bz, yBase + rise(s), rs, ridgeX, thickRidge);
        eaveRim(world, x0, z0, x1, z1, yBase, rs);   // v6.2 ③ — 처마 끝단을 반 블록으로 깎아 그림자 선을 낸다
    }

    /**
     * v6.2 ③ 물매 — rise(s) = (2s+2)/3 → 세 칸 전진에 두 칸 상승 (≈0.67).
     * v6.1 의 2:1(0.5) 은 계산상 완만했으나 실측 물매가 0.27까지 떨어져(의방) "비가 안 흐르는" 판때기가 됐다.
     * 0.5~0.8 대역이 향촌 기와의 자리다: 급하지 않으면서 지붕면이 하늘을 향해 서 있는 각.
     */
    private static int rise(int s) {
        return (2 * s + 2) / 3;
    }

    /** 그 단이 앞 단과 같은 높이인가 (= 그 y 의 두 번째 평평한 단 → 풀 블록) */
    private static boolean flat(int s) {
        return s > 0 && rise(s) == rise(s - 1);
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
        for (int x = x0; x <= x1; x++) {
            rimSlab(world, x, y, z0, rs);
            rimSlab(world, x, y, z1, rs);
        }
        for (int z = z0 + 1; z <= z1 - 1; z++) {
            rimSlab(world, x0, y, z, rs);
            rimSlab(world, x1, y, z, rs);
        }
    }

    private static void rimSlab(World world, int x, int y, int z, RoofStyle rs) {
        Material now = world.getBlockAt(x, y, z).getType();
        if (now == stairMat(rs) || now == solidMat(rs)) {
            world.getBlockAt(x, y, z).setType(ridgeMat(rs));   // 반 블록(하단) — 얇은 처마 끝
        }
    }

    /** 지붕 한 칸 — solid 면 풀 블록, 아니면 안쪽으로 오르는 계단 (반 칸 = 완만한 물매의 절반 단) */
    private static void roofCell(World world, int x, int y, int z, BlockFace facing,
                                 RoofStyle rs, boolean solid) {
        roofBlock(world, x, y, z, facing, solid, rs);
    }

    /**
     * 용마루 — v6.2 ③. v6.1 의 용마루는 지붕면과 같은 높이의 반 블록 줄이라 조감도에서 **선이 안 보였다**.
     * 이제 세 켜다: 몸통(y, 풀 블록) → 마루 등(y+1, 풀 블록 = 주변 지붕면보다 확실히 1칸 높다)
     * → 양단 치미(y+2, 풀 블록) + 마루 밖으로 한 칸 내민 뿔(y+1). thick(객잔·표국·관아)는 y+2 에 반 블록 덧단.
     * 위에서 내려다보면 검은 판때기 한복판에 마루 선 한 줄이 그어지고, 그 양 끝이 뿔처럼 들린다.
     */
    private static void ridge(World world, int ax, int az, int bx, int bz, int y,
                             RoofStyle rs, boolean ridgeX, boolean thick) {
        Material solid = solidMat(rs);
        for (int x = ax; x <= bx; x++) {
            for (int z = az; z <= bz; z++) {
                world.getBlockAt(x, y, z).setType(solid);   // 몸통
            }
        }
        int mx = (ax + bx) / 2;
        int mz = (az + bz) / 2;
        if (ridgeX) {
            for (int x = ax; x <= bx; x++) {
                world.getBlockAt(x, y + 1, mz).setType(solid);              // 마루 등 — 1칸 세운다
                if (thick) {
                    topSlab(world, x, y + 2, mz, ridgeMat(rs));             // 덧단 (격이 높은 집)
                }
            }
            for (int x : new int[]{ax, bx}) {
                world.getBlockAt(x, y + 2, mz).setType(solid);              // 치미 — 양단을 확실히 세운다
            }
            world.getBlockAt(ax - 1, y + 1, mz).setType(solid);             // 들린 양 끝 (치미의 뿔)
            world.getBlockAt(bx + 1, y + 1, mz).setType(solid);
        } else {
            for (int z = az; z <= bz; z++) {
                world.getBlockAt(mx, y + 1, z).setType(solid);
                if (thick) {
                    topSlab(world, mx, y + 2, z, ridgeMat(rs));
                }
            }
            for (int z : new int[]{az, bz}) {
                world.getBlockAt(mx, y + 2, z).setType(solid);
            }
            world.getBlockAt(mx, y + 1, az - 1).setType(solid);
            world.getBlockAt(mx, y + 1, bz + 1).setType(solid);
        }
    }

    /** 합각·박공 한 칸 — 벽 자재(백벽 / 흑목 박공널), 가운데 한 칸만 유리판 환기창 */
    private static void gableBlock(World world, int x, int y, int z, Material gable, boolean vent) {
        world.getBlockAt(x, y, z).setType(vent ? Material.GLASS_PANE : gable);
    }

    /**
     * 서까래 라인 — 처마 첫 링 바로 아래 둘레에 흑목 반 블록 (깊은 처마의 그림자).
     * 빈 칸에만 놓는다: 처마를 0칸으로 붙인 변(잡화점 동벽 등)에서 벽 상인방을 파먹지 않게.
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
                    } else if (y == cy + 3 && Math.floorMod(x + z, 2) == 0) {
                        world.getBlockAt(x, y, z).setType(Material.GLASS_PANE);              // 격자창
                    } else {
                        world.getBlockAt(x, y, z).setType(Material.WHITE_TERRACOTTA);        // 백벽
                    }
                }
            }
        }
        world.getBlockAt(cx + PY_DOOR, cy + 2, z0).setType(Material.AIR);   // 북향 문 1칸
        world.getBlockAt(cx + PY_DOOR, cy + 3, z0).setType(Material.AIR);
        for (int x = cx + 37; x <= cx + 39; x++) {   // 기단 진입 계단 3칸 폭
            stair(world, x, cy + 1, z0 - 1, Material.STONE_BRICK_STAIRS, BlockFace.SOUTH);
        }
        roofShape(world, x0 - 2, z0 - 2, x1 + 2, z1 + 2, cy + 6,   // v6.2 ③ — 처마 2칸 + 한 칸 더 내민 서까래
                RoofStyle.TILE, Material.WHITE_TERRACOTTA, 99, true, true);
        for (int px : new int[]{cx + 36, cx + 44}) {   // 활주(活柱) 2주 — 정면 깊은 처마를 받는다 (마당 바닥에서)
            for (int y = cy + 1; y <= cy + 5; y++) {
                world.getBlockAt(px, y, z0 - 2).setType(Material.SPRUCE_FENCE);
            }
        }
        eavePosts(world, x0 - 1, cy, z0 - 1, x1 + 1, z1 + 1, cy + 5);   // 네 귀 툇기둥 — 마당 바닥에서 처마까지
        tieBeams(world, x0, cy + 5, z0, x1, z1);                        // 대들보 2 (z+46 · z+50)
        pyogukHallInterior(world, cx, cy, cz);
    }

    /**
     * 표국 본채 실내 (x+32..x+44 · z+44..z+52, 바닥 cy+1) — v6.1 ③ 공간 문법.
     * 시선 축: 북향 문(x+38)으로 들어와 정면 남벽(z+52)에 **병장기 시렁** — 진철산의 오호단문창 자리.
     *   표국이 무엇으로 먹고사는 집인지 문턱에서 읽힌다. 장부·표물은 축을 비껴 좌우로.
     * 벽면 3분할 — 하단 창걸이 계단 / 중단 시렁 판 / 상단 시렁 위 등.
     * 밀도 등급 = 중간 (무가는 정돈되나 살림이 있다). 국주 앵커(x+38,z+48)와 문 동선은 비운다.
     */
    private static void pyogukHallInterior(World world, int cx, int cy, int cz) {
        for (int x = cx + 36; x <= cx + 40; x++) {   // 시선 축 — 남벽 앞 병장기 시렁 5칸
            stair(world, x, cy + 2, cz + 52, Material.SPRUCE_STAIRS, BlockFace.NORTH);   // 하단 = 창걸이
            world.getBlockAt(x, cy + 3, cz + 52).setType(Material.DARK_OAK_SLAB);        // 중단 = 시렁 판
        }
        world.getBlockAt(cx + 36, cy + 4, cz + 52).setType(Material.LANTERN);            // 상단 = 시렁 위 등 2
        world.getBlockAt(cx + 40, cy + 4, cz + 52).setType(Material.LANTERN);
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
                RoofStyle.SHINGLE, Material.DARK_OAK_PLANKS, 0, false);
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
        // 표사들의 자리 — 모닥불 + 둘러앉는 통나무 걸상 3 (곽진이 들르는 자리)
        hearth(world, cx + 50, cy, cz + 50);
        world.getBlockAt(cx + 49, cy + 1, cz + 50).setType(Material.OAK_LOG);
        world.getBlockAt(cx + 50, cy + 1, cz + 49).setType(Material.OAK_LOG);
        world.getBlockAt(cx + 50, cy + 1, cz + 51).setType(Material.OAK_LOG);
    }

    // ─── 폐사당 (v6 ② — abandoned_shrine, 담장 밖 북서 외곽) ───
    //
    // 마을 중심에서 (-75,-75) — 담장(r=60)·완사면(r=68) 밖. 평탄화하지 않는다: 폐허는 지형에 순응해야 폐허답다.
    //   본전 11x15 = x[cx-80..cx-70] · z[cz-82..cz-68], 무너진 담 흔적 17x21 = x[cx-83..cx-67]·z[cz-85..cz-65].
    //   기준 높이는 부지 밖 한 점(cx-75, cz-90 — 우리가 절대 건드리지 않는 좌표)의 지표에서 뽑는다 →
    //   재조성해도 같은 baseY (getHighestBlockYAt 이 제 건물을 다시 읽는 비결정론을 차단).
    //   간판·명패·앵커 없음 (hidden — 발견은 서사의 몫). 광원은 전부 영혼 계열 = 마을의 온색과 정반대.

    private static final int[][] SH_COBWEBS = {   // 거미줄 5곳 — 천장 모서리·부러진 기둥 틈 (좌표 상수)
            {-79, -81, 4}, {-71, -81, 4}, {-79, -69, 4}, {-72, -73, 3}, {-77, -77, 4}
    };
    private static final int[][] SH_FLOOR_HOLES = {   // 썩어 내려앉은 마루 5칸
            {-78, -79}, {-77, -79}, {-73, -76}, {-76, -71}, {-75, -70}
    };
    private static final int[][] SH_DEBRIS = {   // 바닥에 떨어진 기와 4곳 (서측 무너진 쪽)
            {-79, -79}, {-78, -73}, {-79, -71}, {-77, -70}
    };

    /** 폐사당 — 반파 팔작·부러진 기둥·냉색 제단. 마을 안에 영혼 계열 광원은 단 하나도 없다. */
    private static void abandonedShrine(World world, int cx, int cy, int cz, List<Zone> out) {
        int x0 = cx - 80, x1 = cx - 70, z0 = cz - 82, z1 = cz - 68;   // 본전 11x15
        int wx0 = cx - 83, wx1 = cx - 67, wz0 = cz - 85, wz1 = cz - 65;   // 무너진 담 17x21
        int baseY = world.getHighestBlockYAt(cx - 75, cz - 90);   // 부지 밖 기준점 = 재조성 결정론
        for (int x = wx0; x <= wx1; x++) {           // 부지 비우기 — 이전 조성물 제거 (재조성 = 같은 폐허)
            for (int z = wz0; z <= wz1; z++) {
                for (int y = baseY + 1; y <= baseY + 13; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR);
                }
            }
        }
        shrinePlatform(world, cx, cy, cz, x0, x1, z0, z1, baseY);
        shrineFrame(world, cx, cz, x0, x1, z0, z1, baseY);
        shrineRoof(world, cx, cz, x0, x1, z0, z1, baseY);
        shrineAltar(world, cx, cz, baseY);
        shrineRuinYard(world, cx, cz, wx0, wx1, wz0, wz1, baseY);
        out.add(new Zone("폐사당", "신상이 없는 제단 — 누군가 다녀갔다", world.getName(),
                wx0, baseY - 3, wz0, wx1, baseY + 13, wz1));
    }

    /** 기단 — 돌 벽돌 1단(이끼·금간 변종 상수 치환) + 마루(썩어 내려앉은 구멍 5칸). 지형은 기단 밑만 메운다. */
    private static void shrinePlatform(World world, int cx, int cy, int cz,
                                       int x0, int x1, int z0, int z1, int baseY) {
        for (int x = x0 - 1; x <= x1 + 1; x++) {
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                int ground = world.getHighestBlockYAt(x, z);
                for (int y = ground; y < baseY; y++) {
                    world.getBlockAt(x, y, z).setType(Material.DIRT);   // 평탄화가 아니라 기단 아래 메움
                }
                int dx = x - cx;
                int dz = z - cz;
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
        for (int[] h : SH_FLOOR_HOLES) {   // 썩어 내려앉은 마루 — 흙이 드러나고 잡초가 올라온다
            world.getBlockAt(cx + h[0], baseY, cz + h[1]).setType(Material.COARSE_DIRT);
        }
        world.getBlockAt(cx + SH_FLOOR_HOLES[0][0], baseY + 1, cz + SH_FLOOR_HOLES[0][1])
                .setType(Material.SHORT_GRASS);
        world.getBlockAt(cx + SH_FLOOR_HOLES[3][0], baseY + 1, cz + SH_FLOOR_HOLES[3][1])
                .setType(Material.FERN);
    }

    /**
     * 골조 — 기둥 6주 중 2주는 y+2에서 끊고 그 자리 바닥에 원목을 눕힌다(쓰러진 기둥).
     * 벽은 백벽이 아니라 회백 테라코타(빛바랜 회벽), 남벽은 절반만 세운다(뻥 뚫린 폐허의 단면).
     * 창은 없다 — 유리는 오래전에 깨졌다. 벽 구멍은 좌표식 결정론 치환.
     */
    private static void shrineFrame(World world, int cx, int cz,
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
                    } else if (Math.floorMod((x - cx) * 5 + (z - cz) * 3, 11) == 0) {
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
        for (int[] c : SH_COBWEBS) {   // 거미줄 5곳 — 좌표 상수
            world.getBlockAt(cx + c[0], baseY + c[2], cz + c[1]).setType(Material.COBWEB);
        }
    }

    /**
     * 지붕 — 팔작의 동측 절반만 온전하다. 서측 계단 링은 1층에서 끊기고, 구멍 가장자리에
     * 심층암 타일·벽돌 잔해가 흩뿌려진다(상수 좌표). 바닥에도 떨어진 기와 4곳.
     */
    private static void shrineRoof(World world, int cx, int cz,
                                  int x0, int x1, int z0, int z1, int baseY) {
        int xMid = (x0 + x1) / 2;
        int rx0 = x0 - 1, rx1 = x1 + 1, rz0 = z0 - 1, rz1 = z1 + 1;
        int yb = baseY + 5;
        for (int x = rx0; x <= rx1; x++) {   // 처마 링 1층 — 서측 일부는 이미 떨어져 나갔다
            if (Math.floorMod(x - cx, 5) != 0 || x >= xMid) {
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
            if (Math.floorMod(z - cz, 4) == 0) {
                roofBlock(world, rx0, yb, z, BlockFace.EAST, false, RoofStyle.TILE);   // 서측 = 듬성듬성
            }
        }
        for (int i = 1; x1 - i >= xMid; i++) {   // 동측 지붕면만 마루까지 오른다
            int x = rx1 - i;
            int y = yb + i;
            for (int z = rz0 + i; z <= rz1 - i; z++) {
                roofBlock(world, x, y, z, BlockFace.WEST, z == rz0 + i || z == rz1 - i, RoofStyle.TILE);
            }
        }
        int ridgeX = xMid;
        int ridgeY = yb + (rx1 - xMid);
        for (int z = z0 + 2; z <= z1 - 4; z++) {   // 부러진 용마루 — 남쪽 끝은 무너져 없다
            world.getBlockAt(ridgeX, ridgeY, z).setType(
                    z == z0 + 2 ? Material.DEEPSLATE_TILES : Material.DEEPSLATE_TILE_SLAB);   // 치미 1단만 남음
        }
        for (int z = z0 + 2; z <= z1 - 4; z += 3) {   // 무너진 단면의 잔해 (심층암 벽돌)
            world.getBlockAt(ridgeX - 1, ridgeY - 1, z).setType(Material.DEEPSLATE_BRICKS);
        }
        for (int[] d : SH_DEBRIS) {   // 바닥에 떨어진 기와 4곳
            world.getBlockAt(cx + d[0], baseY + 1, cz + d[1]).setType(Material.DEEPSLATE_TILE_SLAB);
        }
    }

    /** 무너진 담(진흙 벽돌 높이 0~2·지형 순응) + 매화 관목 + 뒷마당 영혼 모닥불 */
    private static void shrineRuinYard(World world, int cx, int cz,
                                       int wx0, int wx1, int wz0, int wz1, int baseY) {
        for (int x = wx0; x <= wx1; x++) {
            for (int z = wz0; z <= wz1; z++) {
                if (x != wx0 && x != wx1 && z != wz0 && z != wz1) {
                    continue;
                }
                int h = Math.floorMod((x - cx) * 7 + (z - cz) * 11, 3);   // 높이 0~2 — 들쭉날쭉 (상수식)
                int ground = world.getHighestBlockYAt(x, z);
                for (int i = 1; i <= h; i++) {
                    world.getBlockAt(x, ground + i, z).setType(Material.MUD_BRICKS);
                }
                if (h == 2) {
                    world.getBlockAt(x, ground + 3, z).setType(Material.MUD_BRICK_SLAB);   // 무너진 갓
                }
            }
        }
        plumBush(world, cx - 82, baseY, cz - 75);          // 폐허에 홀로 피는 매화 (화산파 복선)
        soulLantern(world, cx - 68, baseY + 1, cz - 84, false);
        world.getBlockAt(cx - 68, baseY + 1, cz - 66).setType(Material.SOUL_CAMPFIRE);   // 누군가 다녀갔다
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
    private static void shrineAltar(World world, int cx, int cz, int baseY) {
        for (int x = cx - 77; x <= cx - 73; x++) {
            world.getBlockAt(x, baseY + 1, cz - 80).setType(Material.STONE_BRICKS);        // 제단 1단
            world.getBlockAt(x, baseY + 1, cz - 81).setType(Material.MOSSY_STONE_BRICKS);
            world.getBlockAt(x, baseY + 2, cz - 81).setType(Material.STONE_BRICK_SLAB);    // 제단 2단
        }
        world.getBlockAt(cx - 75, baseY + 2, cz - 80).setType(Material.DECORATED_POT);      // 향로
        candles(world, cx - 77, baseY + 2, cz - 80, 3, false);                              // 꺼진 양초 3
        soulLantern(world, cx - 78, baseY + 2, cz - 81, false);                             // 냉색 — 여긴 다르다
        soulLantern(world, cx - 72, baseY + 2, cz - 81, false);
        // 신상 자리 (cx-75, baseY+3, cz-81) 는 비워 둔다 — 코드로도 비운다
        world.getBlockAt(cx - 75, baseY + 3, cz - 81).setType(Material.AIR);
        bookshelf(world, cx - 79, baseY + 1, cz - 77, BlockFace.EAST, 2);                   // 경전 시렁 — 두 권만
        world.getBlockAt(cx - 78, baseY + 1, cz - 77).setType(Material.BROWN_CARPET);       // 떨어진 책
        world.getBlockAt(cx - 71, baseY + 1, cz - 79).setType(Material.DECORATED_POT);      // 깨진 살림 항아리
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
     *   2  ㄱ자형            구운 벽돌       너와        목책 안마당     (-17,-30)    남
     *   3  일자형            회벽+목골       흑와        곁담 돌담       (+8,-30)     남
     *   4  다락형            가로 통나무     흙기와        없음           (+29,-33)    남
     *   5  일자형            회벽+목골       흑와        곁담 목책       (+42,-18)    북
     *   6  ㄱ자형            구운 벽돌       너와        목책 안마당     (-43,+22)    북
     *   7  작업장(직조간)    흙벽돌+회벽목골  흑와+너와   돌담 작업마당   (+8,+22)     북
     *   8  다락형            가로 통나무     흙기와        없음           (+34,+22)    북
     *   9  일자형            회벽+목골       흑와        없음           (-45,+8)     남
     */
    private static void cottages(World world, int cx, int cy, int cz) {
        workshopHouse(world, cx - 44, cy, cz - 30, false, true);                       // #1 대장간
        lHouse(world, cx - 17, cy, cz - 30, false);                                    // #2
        linearHouse(world, cx + 8, cy, cz - 30, false, Material.COBBLESTONE_WALL);     // #3
        loftHouse(world, cx + 29, cy, cz - 33, false);                                 // #4
        linearHouse(world, cx + 42, cy, cz - 18, true, Material.SPRUCE_FENCE);         // #5
        lHouse(world, cx - 43, cy, cz + 22, true);                                     // #6
        workshopHouse(world, cx + 8, cy, cz + 22, true, false);                        // #7 직조간
        loftHouse(world, cx + 34, cy, cz + 22, true);                                  // #8
        linearHouse(world, cx - 45, cy, cz + 8, false, null);                          // #9
    }

    /**
     * 일자형 13x9 — 회벽+목골 노출 벽, 흑와 지붕, 벽고 3. sideWallMat = 곁담 재질(null 이면 없음).
     * 인테리어 필수 세트: 침상 2칸+곁깔개 · 수납(술통+궤) · 작업대 · 화덕 ·
     * 조명 높이 변화(바닥 등롱 + 술통 위 선반등) · 바닥 패턴(귀틀+방석 깔개) · 소품(궤 위 화분).
     */
    private static void linearHouse(World world, int x0, int y0, int z0,
                                    boolean doorNorth, Material sideWallMat) {
        int w = 13, d = 9;
        shell(world, x0, y0, z0, w, d, 3, doorNorth, WallStyle.FRAME_GRAY, RoofStyle.TILE, false);   // 민가 = 맞배 계열 유지
        int x1 = x0 + w - 1, z1 = z0 + d - 1;
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
    private static void lHouse(World world, int x0, int y0, int z0, boolean doorNorth) {
        int w = 12, d = 9;
        shell(world, x0, y0, z0, w, d, 3, doorNorth, WallStyle.BRICK, RoofStyle.SHINGLE, false);
        int x1 = x0 + w - 1, z1 = z0 + d - 1;
        int wingZ0 = doorNorth ? z1 : z0 - 6;   // 날개는 문 반대편(뒤)으로 뻗는다
        shell(world, x0, y0, wingZ0, 6, 7, 3, doorNorth, WallStyle.BRICK, RoofStyle.SHINGLE, false, 1);   // 부속채 처마 1칸 (위계)
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
        shell(world, x0, y0, z0, w, d, 4, doorNorth, WallStyle.LOG, RoofStyle.MUD_TILE, false);   // v6.2 ④ — 청록 산화동 폐기
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
    }

    /**
     * 작업장 병설형 — 본채 12x9(흙벽돌·흑와) + 작업간 7x6(회벽목골·너와, 골목 쪽 별도 문) + 돌담 작업마당.
     * smithy=true 대장간(용광로 화로·모루·대장장이 작업대), false 직조간(베틀 2·베 무더기·궤).
     * 필수 세트: 본채 침상·수납·방석·조명 2단 / 작업간 생업 작업대·수납·등롱.
     */
    private static void workshopHouse(World world, int x0, int y0, int z0,
                                      boolean doorNorth, boolean smithy) {
        int w = 12, d = 9;
        shell(world, x0, y0, z0, w, d, 3, doorNorth, WallStyle.MUD_BRICK, RoofStyle.TILE, false);
        int x1 = x0 + w - 1, z1 = z0 + d - 1;
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
        shell(world, x0 + 11, y0, sz0, 7, 6, 3, doorNorth, WallStyle.FRAME_GRAY, RoofStyle.SHINGLE, false, 1);   // 부속채 처마 1칸
        int sz1 = sz0 + 5;
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
     * 의방 13x11 (cx-24..cx-12, cz+8..cz+18) — v6.1 ③ 공간 문법.
     * 시선 축: 북향 문(x-18)으로 들어와 정면 남벽(z+17)에 **약장** 한 벌. 그것만 축 위에 있다.
     * 벽면 3분할 — 하단 약장 서랍 / 중단 약장 윗칸 (성긴 2칸) / 상단 매단 등롱 2.
     * 밀도 등급 = 낮음·정렬 (의방은 정돈된 집이다). 벽 한 면 3점을 넘기지 않고, 방 중앙은 비운다.
     */
    private static void medicineInterior(World world, int cx, int cy, int cz) {
        for (int x = cx - 21; x <= cx - 15; x++) {   // 남벽 = 약장 7칸 (꽂힌 칸 수 상수 — 난수 없음)
            bookshelf(world, x, cy + 1, cz + 17, BlockFace.NORTH, Math.floorMod(cx - x, 3) + 3);   // 하단
            if (x == cx - 20 || x == cx - 18 || x == cx - 16) {                                    // 중단 = 성기게
                bookshelf(world, x, cy + 2, cz + 17, BlockFace.NORTH, Math.floorMod(cx - x, 4) + 2);
            }
        }
        hangingLantern(world, cx - 21, cy + 3, cz + 15);   // 상단 = 대들보(z+15)에 매단 등롱 2 — 약장을 비춘다
        hangingLantern(world, cx - 15, cy + 3, cz + 15);
        for (int x = cx - 19; x <= cx - 17; x++) {
            world.getBlockAt(x, cy + 1, cz + 16).setType(Material.BROWN_CARPET);   // 약장 앞 깔개 (서는 자리)
        }
        // 서벽(x-23) — 탕약 채비 3점 (하단 2 · 중단 1). 축을 비껴 있다.
        world.getBlockAt(cx - 23, cy + 1, cz + 15).setType(Material.BREWING_STAND);
        world.getBlockAt(cx - 23, cy + 1, cz + 14).setType(Material.CAULDRON);
        world.getBlockAt(cx - 23, cy + 1, cz + 11).setType(Material.CRAFTING_TABLE);   // 약재 손질상
        world.getBlockAt(cx - 23, cy + 2, cz + 11).setType(Material.LANTERN);          // 중단 조명
        // 동벽(x-13) — 진료 평상 + 약재 자루 (하단 2점). 환자는 문 쪽을 등지고 눕는다.
        world.getBlockAt(cx - 14, cy + 1, cz + 11).setType(Material.WHITE_CARPET);
        world.getBlockAt(cx - 14, cy + 1, cz + 12).setType(Material.WHITE_CARPET);
        world.getBlockAt(cx - 13, cy + 1, cz + 10).setType(Material.SPRUCE_FENCE);     // 환자 걸상
        world.getBlockAt(cx - 13, cy + 2, cz + 10).setType(Material.SPRUCE_PRESSURE_PLATE);
        world.getBlockAt(cx - 13, cy + 1, cz + 15).setType(Material.BARREL);           // 약재 자루 1 (여백)
        world.getBlockAt(cx - 13, cy + 2, cz + 15).setType(Material.POTTED_POPPY);     // 중단 — 창가 양귀비
        // 처마 밑 현수 등롱 쌍 (2칸 처마 끝단 — house() 의 것과 겹치지 않는 안쪽 줄)
        chainLantern(world, cx - 20, cy + 4, cz + 7, 1);
        chainLantern(world, cx - 16, cy + 4, cz + 7, 1);
        for (int z = cz + 14; z <= cz + 15; z++) {   // 곁마당 약재 건조대 — 대나무 울타리 + 널어 둔 약초
            world.getBlockAt(cx - 25, cy + 1, z).setType(Material.BAMBOO_FENCE);   // 남골목(z+19..21)을 피해 서측으로
            world.getBlockAt(cx - 25, cy + 2, z).setType(Material.HAY_BLOCK);
        }
    }

    /**
     * 전장 13x11 (cx+12..cx+24, cz+8..cz+18) — v6.1 ③ 공간 문법.
     * 시선 축: 북향 문(x+18)으로 들어와 카운터 너머 정면 남벽(z+17)에 **철창 금고** — 축의 정중앙.
     *   "보여 주되 못 만지게" — 이 집의 정체는 쇠창살이다. 축 위엔 그것 하나뿐.
     * 벽면 3분할 — 하단 금고·전표철 / 중단 카운터 위 촛불·천칭 / 상단 현수등 2.
     * 밀도 등급 = 낮음·정렬 (전장은 흐트러지면 신용이 죽는다). 손님이 서는 자리(z+9..+11)는 비운다.
     */
    private static void exchangeInterior(World world, int cx, int cy, int cz) {
        for (int x = cx + 14; x <= cx + 22; x++) {   // 카운터 — 손님(북)과 지점주(남)를 가른다
            world.getBlockAt(x, cy + 1, cz + 12).setType(Material.DARK_OAK_PLANKS);
        }
        // ── 시선 축(x+18): 철창 3칸 + 그 뒤 금고 3칸. 좌우 대칭으로 정렬한다.
        for (int x = cx + 17; x <= cx + 19; x++) {
            world.getBlockAt(x, cy + 1, cz + 16).setType(Material.IRON_BARS);
            world.getBlockAt(x, cy + 2, cz + 16).setType(Material.IRON_BARS);
        }
        world.getBlockAt(cx + 17, cy + 1, cz + 17).setType(Material.BARREL);      // 금고 (하단)
        world.getBlockAt(cx + 18, cy + 1, cz + 17).setType(Material.CHEST);       // 축 정중앙 = 금궤
        world.getBlockAt(cx + 19, cy + 1, cz + 17).setType(Material.BARREL);
        world.getBlockAt(cx + 18, cy + 2, cz + 17).setType(Material.DECORATED_POT);   // 봉인된 은자 단지 (중단)
        hangingLantern(world, cx + 18, cy + 3, cz + 15);                              // 상단 — 대들보(z+15)에 매단 등
        // ── 중단(y+2): 카운터 위 세 점만 (촛불·천칭·장부등)
        candles(world, cx + 15, cy + 2, cz + 12, 2, true);                        // 장부는 촛불로 본다
        world.getBlockAt(cx + 20, cy + 2, cz + 12).setType(Material.STONE_PRESSURE_PLATE);   // 천칭 접시
        world.getBlockAt(cx + 22, cy + 2, cz + 12).setType(Material.LANTERN);
        world.getBlockAt(cx + 14, cy + 1, cz + 13).setType(Material.LECTERN);     // 장부 (지점주 자리 곁)
        // ── 동벽(x+23): 전표철 2칸만 (여백 규칙 — 빈 벽이 있어야 있는 것이 보인다)
        bookshelf(world, cx + 23, cy + 1, cz + 14, BlockFace.WEST, 3);   // 반만 채워 '끊어 준 전표'
        bookshelf(world, cx + 23, cy + 2, cz + 14, BlockFace.WEST, 2);
        // ── 바닥: 손님줄 깔개 (금서방 앵커 x+18,z+13 과 문 안 동선은 비운다)
        for (int z = cz + 9; z <= cz + 11; z++) {
            world.getBlockAt(cx + 18, cy + 1, z).setType(Material.RED_CARPET);
        }
        hangingLantern(world, cx + 18, cy + 3, cz + 11);   // 상단 — 대들보(z+11)에 매단 손님줄 등
    }

    /**
     * 의뢰소 13x11 (cx+11..cx+23, cz-17..cz-7) — v6.1 ③ 공간 문법.
     * 시선 축: 남향 문(x+17)으로 들어와 정면 북벽(z-16)에 **게시판 + 독서대** — 일이 걸린 벽.
     *   문서철은 그 좌우로 물러서고, 대기 걸상은 문 쪽 벽을 등진다.
     * 벽면 3분할 — 하단 문서철·독서대 / 중단 게시 목판 / 상단 현수등.
     * 밀도 등급 = 중간 (일이 밀린 관청 — 정돈되었으나 서류가 쌓인다).
     */
    private static void requestOfficeInterior(World world, int cx, int cy, int cz) {
        // ── 시선 축(x+17): 북벽 게시 목판 + 그 앞 독서대(의뢰 대장)
        for (int x = cx + 16; x <= cx + 18; x++) {
            world.getBlockAt(x, cy + 2, cz - 16).setType(Material.DARK_OAK_PLANKS);   // 중단 = 게시 목판
        }
        placeWallSign(world, cx + 16, cy + 2, cz - 15, "의뢰 접수", "보수는 선불 없다");
        placeWallSign(world, cx + 18, cy + 2, cz - 15, "현상 수배", "청하현 관아 공동 게시");
        world.getBlockAt(cx + 17, cy + 1, cz - 16).setType(Material.LECTERN);        // 축 정중앙 = 의뢰 대장
        hangingLantern(world, cx + 17, cy + 3, cz - 14);                             // 상단 — 대들보(z-14)에 매단 등
        // ── 북벽 좌우: 문서철 (왼쪽은 꽉, 오른쪽은 비게 — 일이 밀려 있다). 축은 비껴 있다.
        for (int x = cx + 13; x <= cx + 15; x++) {
            bookshelf(world, x, cy + 1, cz - 16, BlockFace.SOUTH, Math.max(0, cx + 16 - x));
        }
        for (int x = cx + 19; x <= cx + 21; x++) {
            bookshelf(world, x, cy + 1, cz - 16, BlockFace.SOUTH, Math.max(0, cx + 22 - x - 1));
        }
        world.getBlockAt(cx + 22, cy + 1, cz - 16).setType(Material.DECORATED_POT);  // 서류 항아리
        world.getBlockAt(cx + 12, cy + 1, cz - 16).setType(Material.BARREL);
        world.getBlockAt(cx + 12, cy + 2, cz - 16).setType(Material.LANTERN);        // 중단 조명
        // ── 동벽(x+22): 화분 2점만 (여백)
        world.getBlockAt(cx + 22, cy + 1, cz - 14).setType(Material.POTTED_CHERRY_SAPLING);   // 매화의 복선
        world.getBlockAt(cx + 22, cy + 1, cz - 12).setType(Material.POTTED_BAMBOO);
        // ── 남벽(문 쪽): 대기 걸상 2 + 깔개 (사람은 문을 등지고 앉아 기다린다)
        for (int x = cx + 13; x <= cx + 21; x += 8) {
            world.getBlockAt(x, cy + 1, cz - 9).setType(Material.SPRUCE_FENCE);
            world.getBlockAt(x, cy + 2, cz - 9).setType(Material.SPRUCE_PRESSURE_PLATE);
        }
        for (int x = cx + 14; x <= cx + 20; x++) {
            world.getBlockAt(x, cy + 1, cz - 9).setType(Material.LIGHT_GRAY_CARPET);   // 대기석 깔개
        }
    }

    /** 의뢰소 앞 게시판 — 간판 여러 장 (사건 소문과 맞물리는 의뢰 공고) */
    private static void bulletinBoard(World world, int cx, int cy, int cz) {
        for (int x = cx + 19; x <= cx + 21; x++) {
            world.getBlockAt(x, cy + 1, cz - 5).setType(Material.SPRUCE_FENCE);
            world.getBlockAt(x, cy + 2, cz - 5).setType(Material.DARK_OAK_PLANKS);
            world.getBlockAt(x, cy + 3, cz - 5).setType(Material.DARK_OAK_PLANKS);
        }
        placeWallSign(world, cx + 19, cy + 2, cz - 4, "의뢰: 북쪽 산길", "정찰 — 보수 상담");
        placeWallSign(world, cx + 20, cy + 2, cz - 4, "구함: 상단 호위", "표국 경력 우대");
        placeWallSign(world, cx + 21, cy + 2, cz - 4, "급구: 약재", "의방 유문 앞");
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
        int x0 = cx + 4, x1 = cx + 8, z0 = cz - 18, z1 = cz - 13;
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
                    } else if (y == cy + 2 && Math.floorMod(x + z, 2) == 0) {
                        world.getBlockAt(x, y, z).setType(Material.GLASS_PANE);               // 격자창
                    } else {
                        world.getBlockAt(x, y, z).setType(Material.LIGHT_GRAY_TERRACOTTA);    // 회벽
                    }
                }
            }
        }
        for (int z = cz - 16; z <= cz - 14; z++) {   // 전면 3칸 개방 (셔터 없는 점두) + 젖힌 덧문
            world.getBlockAt(x0, cy + 1, z).setType(Material.AIR);
            world.getBlockAt(x0, cy + 2, z).setType(Material.AIR);
            awningTrapdoor(world, x0, cy + 3, z, BlockFace.WEST);
            steppingStone(world, x0 - 1, cy, z);   // 점두 앞 디딤돌 (대로 갓길 → 문지방 전이)
        }
        roofShape(world, x0 - 1, z0 - 1, x1, z1 + 1, cy + 4,   // 맞배 + 박공널. 동면 처마 0 = 의뢰소 처마와 이웃
                RoofStyle.TILE, Material.DARK_OAK_PLANKS, 0, false);
        hangingSign(world, x0 - 1, cy + 3, cz - 15, BlockFace.WEST, "장쇠네 잡화", "잡화 — 되는 대로 다 있다");
        hangingLantern(world, x0 - 1, cy + 3, cz - 17);   // 처마 밑 등롱 (밤에도 점두가 읽힌다)
        generalStoreInterior(world, cx, cy, cz, x0, x1);
    }

    /**
     * 잡화점 실내 (x+5..x+7 · z-17..z-11) — v6.1 ③ 벽면 3분할·시선 축.
     * 시선 축: 서쪽 점두로 들어오면 정면(동벽 x+7)에 좌판·잡동사니 시렁 = 이 집의 정체.
     * 밀도 등급 = 높음 (장터·객잔은 어수선한 것이 성격이다). 방 중앙(x+6)은 비운다.
     */
    private static void generalStoreInterior(World world, int cx, int cy, int cz, int x0, int x1) {
        int back = x1 - 1;   // 동벽 안줄 = 시선 축의 끝
        for (int z = cz - 17; z <= cz - 14; z++) {   // 하단(y+1) 수납 · 중단(y+2) 선반 · 상단(y+3) 매단 것
            topSlab(world, back, cy + 2, z, Material.DARK_OAK_SLAB);   // 중단 = 선반 한 줄 (정렬)
        }
        world.getBlockAt(back, cy + 1, cz - 17).setType(Material.BARREL);          // 하단 3점만 (여백 규칙)
        world.getBlockAt(back, cy + 1, cz - 15).setType(Material.DECORATED_POT);
        world.getBlockAt(back, cy + 1, cz - 14).setType(Material.BARREL);
        world.getBlockAt(back, cy + 3, cz - 17).setType(Material.DECORATED_POT);   // 상단 = 선반 위 잡동사니
        world.getBlockAt(back, cy + 3, cz - 14).setType(Material.HAY_BLOCK);
        Orientable chain = (Orientable) Material.CHAIN.createBlockData();
        chain.setAxis(Axis.Y);
        world.getBlockAt(back, cy + 3, cz - 15).setBlockData(chain);               // 끈 대용 사슬 1칸
        world.getBlockAt(back, cy + 3, cz - 16).setType(Material.LANTERN);         // 상단 — 선반 위 등 (3분할 완성)
        world.getBlockAt(x0 + 1, cy + 1, cz - 17).setType(Material.CHEST);         // 북벽 — 2점
        world.getBlockAt(x0 + 2, cy + 1, cz - 17).setType(Material.BARREL);
        world.getBlockAt(x0 + 1, cy + 2, cz - 17).setType(Material.LANTERN);       // 중단 조명
        for (int z = cz - 16; z <= cz - 14; z++) {   // 점두 안쪽 대나무 좌판 (문을 등지고 앉는 자리)
            world.getBlockAt(x0 + 1, cy + 1, z).setType(Material.BAMBOO_PLANKS);
        }
        world.getBlockAt(x0 + 1, cy + 2, cz - 15).setType(Material.DECORATED_POT);
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
            Orientable chain = (Orientable) Material.CHAIN.createBlockData();
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
}
