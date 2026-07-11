package com.honcheon.mvt;

import org.bukkit.Axis;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Bisected;
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

    /** 지붕 자재 체계 — 같은 팔작 문법, 재질 3계열 (흑와 / 흑목 너와 / 산화동) */
    private enum RoofStyle {
        TILE,            // 흑와 — DEEPSLATE_TILE 계열
        SHINGLE,         // 흑목 너와 — DARK_OAK 계열
        COPPER           // 산화동 — OXIDIZED_CUT_COPPER 계열 (완전 산화라 변색 없음 = 결정론)
    }

    /**
     * 플레이어 위치를 광장 중심으로 마을을 세우고 장소 앵커를 돌려준다.
     * zonesOut 에는 입장 타이틀용 구역(마을 전체·건물 4·장터)을 채운다 — 작은 부피가 이긴다.
     */
    static Map<String, Location> build(Player admin, List<Zone> zonesOut) {
        World world = admin.getWorld();
        int cx = admin.getLocation().getBlockX();
        int cy = admin.getLocation().getBlockY() - 1;   // 발밑 = 지면
        int cz = admin.getLocation().getBlockZ();

        clearNpcs(world, cx, cy, cz);        // F29 — 재조성 시 기존 NPC 정리 (중복 스폰 방지)
        clearAndFlatten(world, cx, cy, cz);
        blendEdge(world, cx, cy, cz);        // F31 — 경계 절단면을 자연 지형으로 완사면 접합
        plazaAndWell(world, cx, cy, cz);
        roads(world, cx, cy, cz);
        townWall(world, cx, cy, cz);
        streetLanterns(world, cx, cy, cz);
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

        marketStalls(world, cx, cy, cz);
        placeSign(world, cx + 2, cy + 1, cz - 58, BlockFace.WEST, "북쪽 산길 →", "늑대·여우 — 도적 소문 있음");   // 독자 = 북로 위
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

    private static void plazaAndWell(World world, int cx, int cy, int cz) {
        for (int x = cx - 7; x <= cx + 7; x++) {   // 광장 15x15
            for (int z = cz - 7; z <= cz + 7; z++) {
                world.getBlockAt(x, cy, z).setType(Material.SMOOTH_STONE);
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
    }

    private static void roads(World world, int cx, int cy, int cz) {
        for (int d = 8; d <= 59; d++) {   // 십자로 — 북로는 북문 너머 산길(사냥터)로 이어진다
            for (int w = -1; w <= 1; w++) {
                world.getBlockAt(cx + w, cy, cz - d).setType(Material.DIRT_PATH);
                world.getBlockAt(cx + w, cy, cz + d).setType(Material.DIRT_PATH);
                world.getBlockAt(cx - d, cy, cz + w).setType(Material.DIRT_PATH);
                world.getBlockAt(cx + d, cy, cz + w).setType(Material.DIRT_PATH);
            }
        }
    }

    /** 건물 입구 ↔ 십자로 연결 골목 */
    private static void doorPaths(World world, int cx, int cy, int cz) {
        for (int z = cz - 5; z <= cz - 2; z++) {
            world.getBlockAt(cx - 21, cy, z).setType(Material.DIRT_PATH);   // 객잔 (2칸 폭 대문)
            world.getBlockAt(cx - 20, cy, z).setType(Material.DIRT_PATH);
        }
        for (int z = cz - 6; z <= cz - 2; z++) {
            world.getBlockAt(cx + 17, cy, z).setType(Material.DIRT_PATH);   // 의뢰소
        }
        for (int z = cz + 2; z <= cz + 7; z++) {
            world.getBlockAt(cx - 18, cy, z).setType(Material.DIRT_PATH);   // 의방
            world.getBlockAt(cx + 18, cy, z).setType(Material.DIRT_PATH);   // 전장
        }
        for (int x = cx + 40; x <= cx + 45; x++) {   // 표국 — 남골목(z+21) → 민가 8 동측 우회 → 표국 대문
            world.getBlockAt(x, cy, cz + 34).setType(Material.DIRT_PATH);
        }
        for (int z = cz + 22; z <= cz + 34; z++) {
            world.getBlockAt(cx + 45, cy, z).setType(Material.DIRT_PATH);
        }
    }

    /** 민가 골목길 — 북골목(z-21)·남골목(z+21) 두 줄, 남북대로와 교차한다 */
    private static void alleys(World world, int cx, int cy, int cz) {
        for (int x = cx - 45; x <= cx + 45; x++) {
            world.getBlockAt(x, cy, cz - 21).setType(Material.DIRT_PATH);
            world.getBlockAt(x, cy, cz + 21).setType(Material.DIRT_PATH);
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
        for (int x = cx - 1; x <= cx + 1; x++) {   // 남문 밖 관도 — 폭 3칸으로 넓혀 지역권 연결(간선)을 암시
            for (int z = cz + r + 1; z <= cz + r + 6; z++) {
                world.getBlockAt(x, cy, z).setType(Material.DIRT_PATH);
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

    /** 대문 — 개구부 3칸 + 목주 문루 + 흑와 처마 + 현판 "청하현" + 안팎 양면 현수 등롱 + 초소 자리 */
    private static void gate(World world, int gx, int cy, int gz, boolean north,
                             String name, String subtitle) {
        for (int x = gx - 1; x <= gx + 1; x++) {
            for (int y = cy + 1; y <= cy + 3; y++) {
                world.getBlockAt(x, y, gz).setType(Material.AIR);
            }
            world.getBlockAt(x, cy, gz).setType(Material.DIRT_PATH);
        }
        for (int side = -2; side <= 2; side += 4) {
            for (int y = cy + 1; y <= cy + 4; y++) {
                world.getBlockAt(gx + side, y, gz).setType(Material.DARK_OAK_LOG);
            }
        }
        for (int x = gx - 2; x <= gx + 2; x++) {
            world.getBlockAt(x, cy + 5, gz).setType(Material.DARK_OAK_PLANKS);   // 인방
        }
        for (int x = gx - 3; x <= gx + 3; x++) {
            world.getBlockAt(x, cy + 6, gz).setType(Material.DEEPSLATE_TILE_SLAB);   // 처마 (문루 지붕)
        }
        for (int x = gx - 2; x <= gx + 2; x++) {   // v6 ⑤ — 처마를 안팎 1칸씩 내밀어 양면 등롱을 매단다
            world.getBlockAt(x, cy + 6, gz - 1).setType(Material.DEEPSLATE_TILE_SLAB);
            world.getBlockAt(x, cy + 6, gz + 1).setType(Material.DEEPSLATE_TILE_SLAB);
        }
        hangingLantern(world, gx, cy + 4, gz);
        int in = north ? 1 : -1;   // 마을 안쪽 방향
        for (int side = -1; side <= 1; side += 2) {   // 현수 등롱 — 마을 안팎 양면 (밤에 돌아오는 낭인의 등대)
            chainLantern(world, gx + side, cy + 5, gz + 1, 1);
            chainLantern(world, gx + side, cy + 5, gz - 1, 1);
        }
        hangingSign(world, gx, cy + 5, gz + in, north ? BlockFace.SOUTH : BlockFace.NORTH,
                "청하현", subtitle);   // 대문 현판 — 등록 지명 그대로
        placeSign(world, gx + 2, cy + 1, gz + in, north ? BlockFace.SOUTH : BlockFace.NORTH, name, subtitle);   // 마을 안쪽에서 읽는다
        // 초소 자리 — 문지기의 부재도 자리로 말한다 (포두 박호의 순찰 동선, NPC 스폰은 후속)
        stair(world, gx - 3, cy + 1, gz + in * 2, Material.SPRUCE_STAIRS,
                north ? BlockFace.SOUTH : BlockFace.NORTH);
        world.getBlockAt(gx - 4, cy + 1, gz + in * 2).setType(Material.BARREL);
        world.getBlockAt(gx - 4, cy + 2, gz + in * 2).setType(Material.LANTERN);
    }

    // ─── 가로 시설 — 등롱·조경 ───

    private static void streetLanterns(World world, int cx, int cy, int cz) {
        for (int d = 10; d <= 52; d += 6) {   // 남북로 양측
            for (int side = -2; side <= 2; side += 4) {
                lanternPost(world, cx + side, cy, cz - d);
                lanternPost(world, cx + side, cy, cz + d);
            }
        }
        for (int d = 12; d <= 54; d += 7) {   // 동서로 양측 — 건물 골목(x±17..21)을 피한 간격
            for (int side = -2; side <= 2; side += 4) {
                lanternPost(world, cx - d, cy, cz + side);
                lanternPost(world, cx + d, cy, cz + side);
            }
        }
    }

    private static void lanternPost(World world, int x, int cy, int z) {
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
        placeSign(world, doorX + 1, y0 + 1, doorZ + out, doorNorth ? BlockFace.NORTH : BlockFace.SOUTH, name, subtitle);   // 입구 앞에서 읽는다
        hangingSign(world, doorX, y0 + wallH, doorZ + out,                                   // v6 ④ — 처마 밑 현판
                doorNorth ? BlockFace.NORTH : BlockFace.SOUTH, name, subtitle);
        chainLantern(world, doorX - 1, y0 + wallH, doorZ + out, 1);                          // 문 양옆 현수 등롱 쌍
        chainLantern(world, doorX + 1, y0 + wallH, doorZ + out, 1);
        world.getBlockAt(x0 + 1, y0 + 1, z0 + 1).setType(Material.LANTERN);
        world.getBlockAt(x1 - 1, y0 + 1, z1 - 1).setType(Material.LANTERN);
        return loc(world, doorX, y0 + 1, z0 + d / 2);   // 앵커 = 실내 중앙
    }

    /**
     * 건물 골조 공통 — 마루·벽(기둥·벽조직·격자창·상인방)·지붕·문 1칸. 간판·앵커 없음.
     * ws = 벽 자재 체계(WallStyle), rs = 지붕 자재 체계(RoofStyle) — v5 A안 자재 팔레트 분리.
     * paljak = true 면 팔작(상가·관아급), false 면 v5 계단 링 지붕(민가 — 지붕 격식은 위계다).
     * 관청급(w>=9)은 마루 가장자리 1칸을 흑목 귀틀로 둘러 바닥에 변화를 준다 (인테리어 규정).
     */
    private static void shell(World world, int x0, int y0, int z0, int w, int d, int wallH,
                              boolean doorNorth, WallStyle ws, RoofStyle rs, boolean paljak) {
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
        if (paljak) {   // 처마 1칸 내밀기 + 팔작(하부 링 2층 + 합각벽 + 서까래·치미)
            paljakRoof(world, x0 - 1, z0 - 1, x1 + 1, z1 + 1, y0 + wallH + 1,
                    rs, 2, Material.WHITE_TERRACOTTA);
        } else {
            hipRoof(world, x0 - 1, z0 - 1, x1 + 1, z1 + 1, y0 + wallH + 1, rs);
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
        // v6 ③ — 팔작: 하부 네 방향 링 3층 + 상부 맞배 전환 + 동서 합각벽(백벽+환기창) + 치미
        paljakRoof(world, x0 - 1, z0 - 1, x1 + 1, z1 + 1, cy + 10,
                RoofStyle.TILE, 3, Material.WHITE_TERRACOTTA);
        for (int x = x0 - 1; x <= x1 + 1; x++) {   // 층간 띠 스커트 = 1층 처마 (2층집의 허리선)
            world.getBlockAt(x, cy + 5, z0 - 1).setType(Material.DEEPSLATE_TILE_SLAB);
            world.getBlockAt(x, cy + 5, z1 + 1).setType(Material.DEEPSLATE_TILE_SLAB);
        }
        for (int z = z0; z <= z1; z++) {
            world.getBlockAt(x0 - 1, cy + 5, z).setType(Material.DEEPSLATE_TILE_SLAB);
            world.getBlockAt(x1 + 1, cy + 5, z).setType(Material.DEEPSLATE_TILE_SLAB);
        }
        for (int x = x0 - 1; x <= x1 + 1; x++) {   // 정면(남) 처마 2칸 — 깊은 처마 그늘이 향촌 건축의 얼굴
            world.getBlockAt(x, cy + 9, z1 + 2).setType(Material.DARK_OAK_SLAB);   // 서까래 받침
            stair(world, x, cy + 10, z1 + 2, Material.DEEPSLATE_TILE_STAIRS, BlockFace.NORTH);
        }
        for (int px : new int[]{cx - 25, cx - 16}) {   // 활주(活柱) 2주 — 툇마루 기둥
            for (int y = cy + 1; y <= cy + 8; y++) {
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
        placeSign(world, cx - 19, cy + 1, z1 + 1, BlockFace.SOUTH, "청하객잔", "소문은 국밥보다 빨리 식는다");
        hangingSign(world, cx - 19, cy + 9, z1 + 2, BlockFace.SOUTH,                 // v6 ④ — 정면 처마 밑 현판
                "청하객잔", "소문은 국밥보다 빨리 식는다");
        chainLantern(world, cx - 22, cy + 4, z1 + 1, 1);                             // 대문 양옆 현수 홍등 2조
        chainLantern(world, cx - 19, cy + 4, z1 + 1, 1);
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

    /** 객잔 1층 주청 — 화덕(부뚜막)·국솥, 탁자 7, 술단지 벽, 계산대(술통·화분), 현수 등롱, 입구 깔개·차림 팻말 */
    private static void innHall(World world, int cx, int cy, int cz) {
        hearth(world, cx - 26, cy, cz - 16);                                       // 화로 + 부뚜막돌
        world.getBlockAt(cx - 25, cy + 1, cz - 16).setType(Material.CAULDRON);     // 국솥
        int[][] tables = {{-25, -10}, {-25, -13}, {-23, -10}, {-23, -13},
                {-17, -10}, {-17, -13}, {-15, -14}};
        for (int[] t : tables) {
            world.getBlockAt(cx + t[0], cy + 1, cz + t[1]).setType(Material.SPRUCE_FENCE);
            world.getBlockAt(cx + t[0], cy + 2, cz + t[1]).setType(Material.SPRUCE_PRESSURE_PLATE);
        }
        world.getBlockAt(cx - 23, cy + 1, cz - 17).setType(Material.BARREL);       // 술단지 벽 (북벽)
        world.getBlockAt(cx - 22, cy + 1, cz - 17).setType(Material.BARREL);
        for (int x = cx - 21; x <= cx - 19; x++) {   // v6 ④ — 계산대 뒤 항아리 시렁 (반 블록 선반 + 술 단지 3)
            topSlab(world, x, cy + 2, cz - 17, Material.DARK_OAK_SLAB);
            world.getBlockAt(x, cy + 3, cz - 17).setType(Material.DECORATED_POT);
        }
        world.getBlockAt(cx - 24, cy + 1, cz - 10).setType(Material.DECORATED_POT);   // 상 곁 술 단지 (좌표 상수)
        world.getBlockAt(cx - 16, cy + 1, cz - 13).setType(Material.DECORATED_POT);
        world.getBlockAt(cx - 27, cy + 1, cz - 15).setType(Material.SMOKER);          // 주방 화구
        world.getBlockAt(cx - 15, cy + 1, cz - 7).setType(Material.BARREL);        // 계산대 술통
        world.getBlockAt(cx - 14, cy + 1, cz - 7).setType(Material.BARREL);
        world.getBlockAt(cx - 15, cy + 2, cz - 7).setType(Material.POTTED_BAMBOO); // 계산대 화분
        world.getBlockAt(cx - 27, cy + 1, cz - 7).setType(Material.LANTERN);
        world.getBlockAt(cx - 13, cy + 1, cz - 17).setType(Material.LANTERN);
        hangingLantern(world, cx - 24, cy + 4, cz - 12);   // 2층 바닥에 매단 홍등 자리 (한백 앵커는 비운다)
        hangingLantern(world, cx - 16, cy + 4, cz - 12);
        world.getBlockAt(cx - 21, cy + 1, cz - 7).setType(Material.RED_CARPET);    // 입구 깔개 (통행 가능)
        world.getBlockAt(cx - 20, cy + 1, cz - 7).setType(Material.RED_CARPET);
        placeSign(world, cx - 17, cy + 1, cz - 7, BlockFace.NORTH, "금일 — 국밥", "객방 있음 — 이층");   // 차림 팻말
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

    /**
     * 계단식 팔작지붕 느낌 — 층마다 한 칸씩 좁아지는 기와 스테어 링, 꼭대기는 용마루 슬래브.
     * rs = 지붕 자재 체계: TILE 흑와(DEEPSLATE_TILE) / SHINGLE 흑목 너와(DARK_OAK) / COPPER 산화동.
     */
    private static void hipRoof(World world, int x0, int z0, int x1, int z1, int yBase, RoofStyle rs) {
        Material ridge = switch (rs) {
            case TILE -> Material.DEEPSLATE_TILE_SLAB;
            case SHINGLE -> Material.DARK_OAK_SLAB;
            case COPPER -> Material.OXIDIZED_CUT_COPPER_SLAB;
        };
        for (int i = 0; x0 + i <= x1 - i && z0 + i <= z1 - i; i++) {
            int ax = x0 + i, bx = x1 - i, az = z0 + i, bz = z1 - i;
            int y = yBase + i;
            if (bx - ax <= 1 || bz - az <= 1) {
                for (int x = ax; x <= bx; x++) {
                    for (int z = az; z <= bz; z++) {
                        world.getBlockAt(x, y, z).setType(ridge);   // 용마루
                    }
                }
                return;
            }
            for (int x = ax; x <= bx; x++) {
                boolean corner = x == ax || x == bx;
                roofBlock(world, x, y, az, BlockFace.SOUTH, corner, rs);
                roofBlock(world, x, y, bz, BlockFace.NORTH, corner, rs);
            }
            for (int z = az + 1; z <= bz - 1; z++) {
                roofBlock(world, ax, y, z, BlockFace.EAST, false, rs);
                roofBlock(world, bx, y, z, BlockFace.WEST, false, rs);
            }
        }
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
            case COPPER -> Material.OXIDIZED_CUT_COPPER_SLAB;
        };
    }

    private static Material solidMat(RoofStyle rs) {
        return switch (rs) {
            case TILE -> Material.DEEPSLATE_TILES;
            case SHINGLE -> Material.DARK_OAK_PLANKS;
            case COPPER -> Material.OXIDIZED_CUT_COPPER;
        };
    }

    private static Material stairMat(RoofStyle rs) {
        return switch (rs) {
            case TILE -> Material.DEEPSLATE_TILE_STAIRS;
            case SHINGLE -> Material.DARK_OAK_STAIRS;
            case COPPER -> Material.OXIDIZED_CUT_COPPER_STAIRS;
        };
    }

    // ─── v6 ③ 지붕 문법 승격 — 팔작(헐산정) / 맞배(현산정) 분리 ───

    /**
     * 팔작지붕(헐산정) — 가이드 1.4의 마인크래프트 문법 그대로:
     * 1) 처마 첫 링 아래 dark_oak_slab 서까래 라인 (그림자 라인)
     * 2) 하부 네 방향 계단 링 hipLayers 층 (모서리는 풀 블록 추녀마루)
     * 3) 상부는 맞배로 전환 — 단변에 합각벽(gable 자재) 삼각형 + 유리판 환기창 1
     * 4) 용마루 반 블록 + 양단 풀 블록 치미
     * 좌표·층수 전부 인자 파생 상수 — 난수 없음 (재조성 = 같은 지붕).
     */
    private static void paljakRoof(World world, int x0, int z0, int x1, int z1, int yBase,
                                   RoofStyle rs, int hipLayers, Material gable) {
        rafterLine(world, x0, z0, x1, z1, yBase - 1);   // 서까래 — 처마 밑단
        int i = 0;
        for (; i < hipLayers; i++) {
            int ax = x0 + i, bx = x1 - i, az = z0 + i, bz = z1 - i;
            if (bx - ax <= 2 || bz - az <= 2) {
                break;   // 링을 더 두면 합각을 세울 몸통이 남지 않는다
            }
            int y = yBase + i;
            for (int x = ax; x <= bx; x++) {
                boolean corner = x == ax || x == bx;
                roofBlock(world, x, y, az, BlockFace.SOUTH, corner, rs);
                roofBlock(world, x, y, bz, BlockFace.NORTH, corner, rs);
            }
            for (int z = az + 1; z <= bz - 1; z++) {
                roofBlock(world, ax, y, z, BlockFace.EAST, false, rs);
                roofBlock(world, bx, y, z, BlockFace.WEST, false, rs);
            }
        }
        gableRoof(world, x0 + i, z0 + i, x1 - i, z1 - i, yBase + i, rs, gable);   // 상부 맞배 전환
    }

    /**
     * 맞배지붕(현산정) — 장변 두 방향만 계단이 오르고 단변은 벽 자재 삼각형(박공/합각).
     * 팔작의 상부 전환에도 그대로 쓰인다. gable = 박공·합각 자재 (백벽 / 목골 판자).
     * 용마루는 반 블록, 양단 1칸은 풀 블록 치미(鴟尾).
     */
    private static void gableRoof(World world, int x0, int z0, int x1, int z1, int yBase,
                                  RoofStyle rs, Material gable) {
        boolean ridgeX = (x1 - x0) >= (z1 - z0);   // 용마루는 장변을 따라 눕는다
        int ax = x0, bx = x1, az = z0, bz = z1;
        int y = yBase;
        boolean vent = true;   // 환기창은 합각 첫 단에 한 칸씩 (다락 환기창)
        while (ridgeX ? bz - az > 1 : bx - ax > 1) {
            if (ridgeX) {
                for (int x = ax; x <= bx; x++) {
                    roofBlock(world, x, y, az, BlockFace.SOUTH, false, rs);
                    roofBlock(world, x, y, bz, BlockFace.NORTH, false, rs);
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
                    roofBlock(world, ax, y, z, BlockFace.EAST, false, rs);
                    roofBlock(world, bx, y, z, BlockFace.WEST, false, rs);
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
            y++;
        }
        Material ridge = ridgeMat(rs);
        Material tip = solidMat(rs);
        for (int x = ax; x <= bx; x++) {
            for (int z = az; z <= bz; z++) {
                boolean end = ridgeX ? (x == ax || x == bx) : (z == az || z == bz);
                world.getBlockAt(x, y, z).setType(end ? tip : ridge);   // 용마루 + 양단 치미
            }
        }
    }

    /** 합각벽 한 칸 — 벽 자재(백벽·목골), 가운데 한 칸만 유리판 환기창 */
    private static void gableBlock(World world, int x, int y, int z, Material gable, boolean vent) {
        world.getBlockAt(x, y, z).setType(vent ? Material.GLASS_PANE : gable);
    }

    /** 서까래 라인 — 처마 첫 링 바로 아래 둘레에 흑목 반 블록 (깊은 처마의 그림자) */
    private static void rafterLine(World world, int x0, int z0, int x1, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            world.getBlockAt(x, y, z0).setType(Material.DARK_OAK_SLAB);
            world.getBlockAt(x, y, z1).setType(Material.DARK_OAK_SLAB);
        }
        for (int z = z0 + 1; z <= z1 - 1; z++) {
            world.getBlockAt(x0, y, z).setType(Material.DARK_OAK_SLAB);
            world.getBlockAt(x1, y, z).setType(Material.DARK_OAK_SLAB);
        }
    }

    // ─── 철산표국 (v6 ① — 등록 장소 pyoguk / 국주 진철산) ───
    //
    // 부지: 남골목(z+21) 동측 여유 부지. 돌담 마당 21x17 = x[cx+30..cx+50] · z[cz+38..cz+54].
    //   마당 대문(북 3칸 x[cx+39..cx+41]) → 마당 → 본채 기단 계단 → 본채 15x11 북향 문.
    //   본채  x[cx+31..cx+45] · z[cz+43..cz+53] — 돌 벽돌 1단 기단 위 (마을에서 유일하게 반 층 올라선 집).
    //   마구간 5x4 x[cx+31..cx+35]·z[cz+39..cz+42] / 짐수레 4x3 x[cx+45..cx+48]·z[cz+39..cz+41].
    // 좌표는 전부 상수 — 난수 없음. 기존 앵커·구역·NPC는 손대지 않고 "표국"만 추가한다.

    private static final int PY_X0 = 30;    // 마당 담 서변
    private static final int PY_X1 = 50;    // 마당 담 동변
    private static final int PY_Z0 = 38;    // 마당 담 북변 (대문)
    private static final int PY_Z1 = 54;    // 마당 담 남변
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
        for (int z = cz + 34; z <= cz + 37; z++) {   // 대문 앞 진입로 (남골목 갈래는 doorPaths 몫)
            world.getBlockAt(cx + 40, cy, z).setType(Material.DIRT_PATH);
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
        paljakRoof(world, x0 - 1, z0 - 1, x1 + 1, z1 + 1, cy + 6,
                RoofStyle.TILE, 2, Material.WHITE_TERRACOTTA);
        for (int x = cx + 36; x <= cx + 44; x++) {   // 정면 처마 2칸 — 깊은 처마가 관아급의 얼굴
            world.getBlockAt(x, cy + 5, z0 - 2).setType(Material.DARK_OAK_SLAB);   // 서까래
            stair(world, x, cy + 6, z0 - 2, Material.DEEPSLATE_TILE_STAIRS, BlockFace.SOUTH);
        }
        for (int px : new int[]{cx + 36, cx + 44}) {   // 활주(活柱) 2주
            for (int y = cy + 1; y <= cy + 4; y++) {
                world.getBlockAt(px, y, z0 - 2).setType(Material.SPRUCE_FENCE);
            }
        }
        pyogukHallInterior(world, cx, cy, cz);
    }

    /** 본채 실내 — 병장기 시렁(진철산의 오호단문창 자리)·표행 장부·표물 궤·조명 2단 */
    private static void pyogukHallInterior(World world, int cx, int cy, int cz) {
        for (int z = cz + 45; z <= cz + 46; z++) {   // 병장기 시렁 — 창걸이 계단 2 + 위 선반
            stair(world, cx + 32, cy + 2, z, Material.SPRUCE_STAIRS, BlockFace.EAST);
            world.getBlockAt(cx + 32, cy + 3, z).setType(Material.DARK_OAK_SLAB);
        }
        world.getBlockAt(cx + 43, cy + 2, cz + 45).setType(Material.LECTERN);      // 표행 장부
        bookshelf(world, cx + 44, cy + 2, cz + 45, BlockFace.WEST, 4);             // 표행 문서철
        placeWallSign(world, cx + 43, cy + 4, cz + 44, "북로 표행", "반년 대기 — 호위 구함");
        world.getBlockAt(cx + 33, cy + 2, cz + 52).setType(Material.CHEST);        // 표물 궤
        world.getBlockAt(cx + 34, cy + 2, cz + 52).setType(Material.BARREL);
        world.getBlockAt(cx + 43, cy + 2, cz + 52).setType(Material.BARREL);
        world.getBlockAt(cx + 43, cy + 3, cz + 52).setType(Material.LANTERN);      // 선반등
        world.getBlockAt(cx + 33, cy + 2, cz + 44).setType(Material.LANTERN);      // 바닥 등롱 (높이 2단)
        chainLantern(world, cx + PY_DOOR, cy + 4, cz + 44, 1);                     // 문 안쪽 현수 등롱
        world.getBlockAt(cx + 37, cy + 2, cz + 48).setType(Material.RED_CARPET);   // 문 → 국주 자리 깔개
        world.getBlockAt(cx + 37, cy + 2, cz + 47).setType(Material.RED_CARPET);
    }

    /** 마당 — 마구간 부속채(맞배 5x4) · 짐수레 자리 4x3 · 표기 장대 · 표사들 모닥불 */
    private static void pyogukYard(World world, int cx, int cy, int cz) {
        // 마구간 5x4 — 남면(마당 쪽) 개방, 맞배 부속채
        for (int x = cx + 31; x <= cx + 35; x++) {
            for (int z = cz + 39; z <= cz + 42; z++) {
                boolean wall = (z == cz + 39) || ((x == cx + 31 || x == cx + 35) && z <= cz + 41);
                if (!wall) {
                    continue;
                }
                for (int y = cy + 1; y <= cy + 3; y++) {
                    world.getBlockAt(x, y, z).setType(
                            y == cy + 3 ? Material.DARK_OAK_PLANKS : Material.LIGHT_GRAY_TERRACOTTA);
                }
            }
        }
        gableRoof(world, cx + 30, cz + 38, cx + 36, cz + 42, cy + 4,
                RoofStyle.SHINGLE, Material.DARK_OAK_PLANKS);
        world.getBlockAt(cx + 33, cy + 1, cz + 41).setType(Material.SPRUCE_FENCE);   // 칸막이 2
        world.getBlockAt(cx + 33, cy + 2, cz + 41).setType(Material.SPRUCE_FENCE);
        world.getBlockAt(cx + 32, cy + 1, cz + 40).setType(Material.HAY_BLOCK);
        world.getBlockAt(cx + 34, cy + 1, cz + 40).setType(Material.HAY_BLOCK);
        world.getBlockAt(cx + 34, cy + 1, cz + 42).setType(Material.CAULDRON);       // 물통
        world.getBlockAt(cx + 32, cy + 2, cz + 40).setType(Material.LANTERN);
        // 짐수레 자리 4x3 — 참나무 판자 데크 + 통 4 + 건초 2 (실어 둔 표물)
        for (int x = cx + 45; x <= cx + 48; x++) {
            for (int z = cz + 39; z <= cz + 41; z++) {
                world.getBlockAt(x, cy, z).setType(Material.OAK_PLANKS);
            }
        }
        world.getBlockAt(cx + 45, cy + 1, cz + 39).setType(Material.BARREL);
        world.getBlockAt(cx + 46, cy + 1, cz + 39).setType(Material.BARREL);
        world.getBlockAt(cx + 47, cy + 1, cz + 39).setType(Material.BARREL);
        world.getBlockAt(cx + 48, cy + 1, cz + 40).setType(Material.BARREL);
        world.getBlockAt(cx + 45, cy + 1, cz + 41).setType(Material.HAY_BLOCK);
        world.getBlockAt(cx + 46, cy + 1, cz + 41).setType(Material.HAY_BLOCK);
        // 표기(標旗) — 울타리 장대 3단 + 매다는 표지판, 밤엔 장대 밑 랜턴 (신용 장사의 표식)
        for (int y = cy + 1; y <= cy + 3; y++) {
            world.getBlockAt(cx + 48, y, cz + 45).setType(Material.SPRUCE_FENCE);
        }
        hangingSign(world, cx + 48, cy + 3, cz + 46, BlockFace.WEST, "철산표국", "표사 모집 — 국주 진철산");
        world.getBlockAt(cx + 47, cy + 1, cz + 45).setType(Material.LANTERN);
        // 표사들의 자리 — 모닥불 + 둘러앉는 통나무 걸상 3 (곽진이 들르는 자리)
        hearth(world, cx + 48, cy, cz + 50);
        world.getBlockAt(cx + 47, cy + 1, cz + 50).setType(Material.OAK_LOG);
        world.getBlockAt(cx + 48, cy + 1, cz + 49).setType(Material.OAK_LOG);
        world.getBlockAt(cx + 48, cy + 1, cz + 51).setType(Material.OAK_LOG);
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
     *   / 다락형 = 가로 통나무·산화동 / 작업장 = 흙벽돌 본채(흑와)+회벽목골 작업간(너와).
     *   #  유형              벽             지붕        담·마당        위치(x0,z0)   문
     *   1  작업장(대장간)    흙벽돌+회벽목골  흑와+너와   돌담 작업마당   (-44,-30)    남
     *   2  ㄱ자형            구운 벽돌       너와        목책 안마당     (-20,-30)    남
     *   3  일자형            회벽+목골       흑와        곁담 돌담       (+6,-30)     남
     *   4  다락형            가로 통나무     산화동      없음           (+25,-33)    남
     *   5  일자형            회벽+목골       흑와        곁담 목책       (+38,-20)    북
     *   6  ㄱ자형            구운 벽돌       너와        목책 안마당     (-43,+22)    북
     *   7  작업장(직조간)    흙벽돌+회벽목골  흑와+너와   돌담 작업마당   (+8,+22)     북
     *   8  다락형            가로 통나무     산화동      없음           (+34,+22)    북
     *   9  일자형            회벽+목골       흑와        없음           (-45,+12)    남
     */
    private static void cottages(World world, int cx, int cy, int cz) {
        workshopHouse(world, cx - 44, cy, cz - 30, false, true);                       // #1 대장간
        lHouse(world, cx - 20, cy, cz - 30, false);                                    // #2
        linearHouse(world, cx + 6, cy, cz - 30, false, Material.COBBLESTONE_WALL);     // #3
        loftHouse(world, cx + 25, cy, cz - 33, false);                                 // #4
        linearHouse(world, cx + 38, cy, cz - 20, true, Material.SPRUCE_FENCE);         // #5
        lHouse(world, cx - 43, cy, cz + 22, true);                                     // #6
        workshopHouse(world, cx + 8, cy, cz + 22, true, false);                        // #7 직조간
        loftHouse(world, cx + 34, cy, cz + 22, true);                                  // #8
        linearHouse(world, cx - 45, cy, cz + 12, false, null);                         // #9
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
        shell(world, x0, y0, wingZ0, 6, 7, 3, doorNorth, WallStyle.BRICK, RoofStyle.SHINGLE, false);
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
     * 다락형 9x12 — 1.5층 귀틀집: 가로 통나무 벽(벽고 4) + 산화동 지붕, 지붕 밑 다락(y+4 마루)과 사다리.
     * 필수 세트: 다락 침상 2칸+곁깔개·궤 / 아래층 화덕·작업대·술통·가마솥 ·
     * 조명 높이 변화(다락 밑 현수등 + 바닥 등롱 + 다락 등롱) · 바닥 패턴(귀틀+방석).
     */
    private static void loftHouse(World world, int x0, int y0, int z0, boolean doorNorth) {
        int w = 9, d = 12;
        shell(world, x0, y0, z0, w, d, 4, doorNorth, WallStyle.LOG, RoofStyle.COPPER, false);
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
        shell(world, x0 + 11, y0, sz0, 7, 6, 3, doorNorth, WallStyle.FRAME_GRAY, RoofStyle.SHINGLE, false);
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

    /** 의방 13x11 (cx-24..cx-12, cz+8..cz+18): 약장 벽면(2단+등롱) + 탕약 도구 + 손질상·약재 자루
     *  + 진료 평상·환자 걸상·깔개. 미세 변주 = 입구 처마 현수 등롱 쌍 (유문 앵커 cx-18,cz+13 은 비운다) */
    private static void medicineInterior(World world, int cx, int cy, int cz) {
        for (int x = cx - 22; x <= cx - 14; x++) {   // v6 ④ — 약장 9칸 2단 = 조각된 책장 (꽂힌 칸 수 상수)
            bookshelf(world, x, cy + 1, cz + 17, BlockFace.SOUTH, Math.floorMod(cx - x, 3) + 3);
            if ((cx - x) % 2 == 0) {
                bookshelf(world, x, cy + 2, cz + 17, BlockFace.SOUTH, Math.floorMod(cx - x, 4) + 2);
            } else if (x == cx - 21 || x == cx - 15) {
                world.getBlockAt(x, cy + 2, cz + 17).setType(Material.LANTERN);    // 약장 위 등롱
            }
        }
        for (int x = cx - 21; x <= cx - 19; x++) {   // 약장 최상단 선반 — 약단지 3 (감초·당귀·금창약)
            topSlab(world, x, cy + 2, cz + 16, Material.DARK_OAK_SLAB);
            world.getBlockAt(x, cy + 3, cz + 16).setType(Material.DECORATED_POT);
        }
        world.getBlockAt(cx - 23, cy + 1, cz + 14).setType(Material.BREWING_STAND); // 탕약
        world.getBlockAt(cx - 23, cy + 1, cz + 15).setType(Material.CAULDRON);
        world.getBlockAt(cx - 23, cy + 1, cz + 11).setType(Material.CRAFTING_TABLE); // 약재 손질상
        world.getBlockAt(cx - 13, cy + 1, cz + 16).setType(Material.BARREL);        // 약재 자루
        world.getBlockAt(cx - 13, cy + 1, cz + 15).setType(Material.BARREL);
        world.getBlockAt(cx - 14, cy + 1, cz + 11).setType(Material.WHITE_CARPET);  // 진료 평상
        world.getBlockAt(cx - 14, cy + 1, cz + 12).setType(Material.WHITE_CARPET);
        world.getBlockAt(cx - 13, cy + 1, cz + 10).setType(Material.SPRUCE_FENCE);  // 환자 걸상
        world.getBlockAt(cx - 13, cy + 2, cz + 10).setType(Material.SPRUCE_PRESSURE_PLATE);
        for (int x = cx - 19; x <= cx - 17; x++) {
            world.getBlockAt(x, cy + 1, cz + 16).setType(Material.BROWN_CARPET);    // 약장 앞 깔개
        }
        chainLantern(world, cx - 19, cy + 4, cz + 7, 1);   // 미세 변주 — 입구 처마 현수 등롱 쌍 (v6 사슬 현수)
        chainLantern(world, cx - 17, cy + 4, cz + 7, 1);
        for (int x = cx - 20; x <= cx - 19; x++) {   // 뒷마당 약재 건조대 — 대나무 울타리 + 널어 둔 약초
            world.getBlockAt(x, cy + 1, cz + 19).setType(Material.BAMBOO_FENCE);
            world.getBlockAt(x, cy + 2, cz + 19).setType(Material.HAY_BLOCK);
        }
        world.getBlockAt(cx - 15, cy + 1, cz + 9).setType(Material.POTTED_POPPY);   // 창가 양귀비 (단약의 암시)
    }

    /** 전장 13x11 (cx+12..cx+24, cz+8..cz+18): 카운터(+등롱) + 장부 + 금고벽 + 장부 선반·손님줄 깔개·화분.
     *  미세 변주 = 정면 2단 격자창 (금서방 앵커 cx+18,cz+13 과 문→카운터 동선은 비운다 — 깔개만) */
    private static void exchangeInterior(World world, int cx, int cy, int cz) {
        for (int x = cx + 14; x <= cx + 22; x++) {   // 카운터 — 손님(북)과 지점주(남)를 가른다
            world.getBlockAt(x, cy + 1, cz + 12).setType(Material.DARK_OAK_PLANKS);
        }
        world.getBlockAt(cx + 14, cy + 2, cz + 12).setType(Material.LANTERN);      // 카운터 등롱
        world.getBlockAt(cx + 22, cy + 2, cz + 12).setType(Material.LANTERN);
        world.getBlockAt(cx + 15, cy + 1, cz + 13).setType(Material.LECTERN);      // 장부
        world.getBlockAt(cx + 19, cy + 1, cz + 17).setType(Material.CHEST);        // 금고벽
        world.getBlockAt(cx + 20, cy + 1, cz + 17).setType(Material.BARREL);
        world.getBlockAt(cx + 21, cy + 1, cz + 17).setType(Material.BARREL);
        bookshelf(world, cx + 23, cy + 1, cz + 13, BlockFace.WEST, 3);   // v6 ④ — 전표철 (반만 채워 '끊어 준 전표')
        bookshelf(world, cx + 23, cy + 1, cz + 14, BlockFace.WEST, 2);
        for (int x = cx + 19; x <= cx + 21; x++) {   // 금고벽 앞 철창 칸막이 — 보여 주되 못 만지게
            world.getBlockAt(x, cy + 1, cz + 16).setType(Material.IRON_BARS);
            world.getBlockAt(x, cy + 2, cz + 16).setType(Material.IRON_BARS);
        }
        world.getBlockAt(cx + 19, cy + 2, cz + 17).setType(Material.BARREL);       // 금고 2단
        candles(world, cx + 16, cy + 2, cz + 12, 2, true);                         // 장부는 촛불로 본다
        world.getBlockAt(cx + 20, cy + 2, cz + 12).setType(Material.STONE_PRESSURE_PLATE);   // 천칭 접시
        world.getBlockAt(cx + 21, cy + 2, cz + 12).setType(Material.DECORATED_POT);          // 봉인된 은자 단지
        world.getBlockAt(cx + 18, cy + 1, cz + 9).setType(Material.RED_CARPET);    // 손님줄 깔개 (통행 가능)
        world.getBlockAt(cx + 18, cy + 1, cz + 10).setType(Material.RED_CARPET);
        world.getBlockAt(cx + 18, cy + 1, cz + 11).setType(Material.RED_CARPET);
        world.getBlockAt(cx + 13, cy + 1, cz + 10).setType(Material.POTTED_BAMBOO); // 실내 화분
        for (int x = cx + 13; x <= cx + 23; x++) {   // 미세 변주 — 정면 2단 창 (기존 창 격자와 같은 위상)
            if ((x + cz + 8) % 2 == 0) {
                world.getBlockAt(x, cy + 3, cz + 8).setType(Material.GLASS_PANE);
            }
        }
    }

    /** 의뢰소 13x11 (cx+11..cx+23, cz-17..cz-7): 접수 대장 + 문서 선반(+등롱)·화분 + 대기 걸상·깔개.
     *  미세 변주 = 정면 고창 2 (소연 앵커 cx+17,cz-12 와 문 안 동선은 비운다 — 깔개만) */
    private static void requestOfficeInterior(World world, int cx, int cy, int cz) {
        world.getBlockAt(cx + 14, cy + 1, cz - 12).setType(Material.LECTERN);      // 의뢰 대장
        world.getBlockAt(cx + 13, cy + 1, cz - 16).setType(Material.BARREL);
        for (int x = cx + 14; x <= cx + 19; x++) {   // v6 ④ — 문서철: 왼쪽은 꽉, 오른쪽은 비게 (일이 밀려 있다)
            bookshelf(world, x, cy + 1, cz - 16, BlockFace.SOUTH, Math.max(0, cx + 20 - x - 1));
            bookshelf(world, x, cy + 2, cz - 16, BlockFace.SOUTH, Math.max(0, cx + 18 - x));
        }
        world.getBlockAt(cx + 21, cy + 1, cz - 16).setType(Material.DECORATED_POT);   // 서류 항아리
        world.getBlockAt(cx + 22, cy + 1, cz - 15).setType(Material.POTTED_CHERRY_SAPLING);   // 매화의 복선
        world.getBlockAt(cx + 16, cy + 2, cz - 16).setType(Material.LANTERN);      // 선반 위 등롱
        world.getBlockAt(cx + 22, cy + 1, cz - 16).setType(Material.POTTED_BAMBOO); // 실내 화분
        for (int x = cx + 13; x <= cx + 21; x += 8) {   // 대기 걸상 2
            world.getBlockAt(x, cy + 1, cz - 9).setType(Material.SPRUCE_FENCE);
            world.getBlockAt(x, cy + 2, cz - 9).setType(Material.SPRUCE_PRESSURE_PLATE);
        }
        for (int x = cx + 14; x <= cx + 20; x++) {
            world.getBlockAt(x, cy + 1, cz - 9).setType(Material.LIGHT_GRAY_CARPET);   // 대기석 깔개
        }
        world.getBlockAt(cx + 14, cy + 3, cz - 7).setType(Material.GLASS_PANE);    // 미세 변주 — 정면 고창 2
        world.getBlockAt(cx + 20, cy + 3, cz - 7).setType(Material.GLASS_PANE);
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
     * v6 ⑤ 장터 잡화점 — 점포 7(x) x 9(z), 북로·광장 쪽(서향) 전면 3칸 개방 + 젖힌 덧문.
     * 좌표: x[cx+2..cx+8] · z[cz-19..cz-11] — 남북대로(x±1)와 의뢰소 처마(x+10) 사이의 유일한 여유 부지.
     * 상가 중 유일하게 서민 팔레트(회벽+스터드)와 맞배지붕을 쓴다 — 실루엣으로 격이 읽힌다.
     * 붉은 차양 노점(cx+7,cz-5)·장쇠 스폰·장터 앵커 반경 15 는 손대지 않는다 (매각 규칙 계약 불변).
     */
    private static void generalStore(World world, int cx, int cy, int cz) {
        int x0 = cx + 2, x1 = cx + 8, z0 = cz - 19, z1 = cz - 11;
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
            world.getBlockAt(x0 - 1, cy, z).setType(Material.DIRT_PATH);   // 점두 앞 흙길
        }
        rafterLine(world, x0 - 1, z0 - 1, x1 + 1, z1 + 1, cy + 3);         // 서까래 라인
        gableRoof(world, x0 - 1, z0 - 1, x1 + 1, z1 + 1, cy + 4,           // 맞배 + 박공널(목골 판자)
                RoofStyle.TILE, Material.DARK_OAK_PLANKS);
        hangingSign(world, x0 - 1, cy + 3, cz - 15, BlockFace.WEST, "장쇠네 잡화", "잡화 — 되는 대로 다 있다");
        hangingLantern(world, x0 - 1, cy + 3, cz - 17);   // 처마 밑 등롱 (밤에도 점두가 읽힌다)
        generalStoreInterior(world, cx, cy, cz, x0, x1);
    }

    /** 잡화점 실내 — 잡동사니의 밀도가 곧 인테리어 (단정한 진열은 전장의 것, 잡화점은 쌓아 두는 집) */
    private static void generalStoreInterior(World world, int cx, int cy, int cz, int x0, int x1) {
        for (int z = cz - 17; z <= cz - 13; z++) {   // 뒷벽 선반 2단 (반 블록) — 위에 잡동사니를 쌓는다
            topSlab(world, x1 - 1, cy + 1, z, Material.DARK_OAK_SLAB);
            topSlab(world, x1 - 1, cy + 3, z, Material.DARK_OAK_SLAB);
        }
        world.getBlockAt(x1 - 1, cy + 2, cz - 17).setType(Material.DECORATED_POT);   // 불규칙 진열 (좌표 상수)
        world.getBlockAt(x1 - 1, cy + 2, cz - 16).setType(Material.BARREL);
        world.getBlockAt(x1 - 1, cy + 2, cz - 14).setType(Material.DECORATED_POT);
        world.getBlockAt(x1 - 1, cy + 2, cz - 13).setType(Material.BARREL);
        world.getBlockAt(x1 - 1, cy + 4, cz - 16).setType(Material.HAY_BLOCK);
        Orientable chain = (Orientable) Material.CHAIN.createBlockData();
        chain.setAxis(Axis.Y);
        world.getBlockAt(x1 - 1, cy + 4, cz - 14).setBlockData(chain);              // 끈 대용 사슬 1칸
        world.getBlockAt(x0 + 1, cy + 1, cz - 18).setType(Material.CHEST);
        world.getBlockAt(x0 + 2, cy + 1, cz - 18).setType(Material.BARREL);
        world.getBlockAt(x0 + 1, cy + 2, cz - 18).setType(Material.LANTERN);        // 선반등 (조명 2단)
        world.getBlockAt(x0 + 1, cy + 1, cz - 12).setType(Material.LANTERN);        // 바닥 등롱
        for (int z = cz - 15; z <= cz - 14; z++) {   // 대나무 좌판 (점두 안쪽)
            world.getBlockAt(x0 + 1, cy + 1, z).setType(Material.BAMBOO_PLANKS);
        }
        world.getBlockAt(x0 + 1, cy + 2, cz - 15).setType(Material.DECORATED_POT);
        world.getBlockAt(x0 + 2, cy + 1, cz - 12).setType(Material.HAY_BLOCK);
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
        Block block = world.getBlockAt(x, y, z);
        Directional data = (Directional) Material.OAK_WALL_SIGN.createBlockData();
        data.setFacing(BlockFace.SOUTH);   // 게시판 정면(남쪽)을 향한다
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
