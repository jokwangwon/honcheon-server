package com.honcheon.mvt;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.sign.Side;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.util.BoundingBox;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 청하현 조성기 (M2b) — config/regions/cheongha_hyeon.yml 의 장소를 실블록으로 세운다.
 * 철학: 맵도 컴파일한다 — 손건축이 아니라 결정론 생성 (재조성 = 같은 마을).
 * 디자인 언어: docs/design/map_design.md — 수묵 3색(목골·백벽·흑와) + 차양 채색.
 * 규모: 61x61. 담장+대문(북·남), 광장·우물(지붕)·매화나무·화단, 계단식 팔작지붕 건물 4채
 * (객잔은 대형 + 내부 탁자·화로, 의방 약재 선반, 전장 카운터, 의뢰소 게시판),
 * 노점 5개(차양 5색), 길가 등롱. 정식 맵은 M3 몫 (tools/mapgen 승격).
 */
final class CheonghaBuilder {

    private CheonghaBuilder() {
    }

    /** 플레이어 위치를 광장 중심으로 마을을 세우고 장소 앵커를 돌려준다 */
    static Map<String, Location> build(Player admin) {
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

        // 건물 4채 — cheongha_hyeon.yml places. 객잔만 대형(13x9), 나머지 9x7.
        // 도로변 입구: 북쪽 두 채는 남향, 남쪽 두 채는 북향(광장을 바라본다).
        anchors.put("청하객잔", house(world, cx - 21, cy, cz - 14, 13, 9, 4, false,
                "청하객잔", "소문은 국밥보다 빨리 식는다"));
        anchors.put("의뢰소", house(world, cx + 9, cy, cz - 13, 9, 7, 3, false,
                "의뢰소", "정파 연락망 — 일과 보수"));
        anchors.put("의방", house(world, cx - 18, cy, cz + 8, 9, 7, 3, true,
                "약재상 · 의방", "외상 장부 있음"));
        anchors.put("전장", house(world, cx + 10, cy, cz + 8, 9, 7, 3, true,
                "청하전장", "전표 = 가져온 이가 임자"));

        innInterior(world, cx, cy, cz);
        medicineInterior(world, cx, cy, cz);
        exchangeInterior(world, cx, cy, cz);
        requestOfficeInterior(world, cx, cy, cz);
        bulletinBoard(world, cx, cy, cz);
        doorPaths(world, cx, cy, cz);

        marketStalls(world, cx, cy, cz);
        placeSign(world, cx + 2, cy + 1, cz - 26, BlockFace.WEST, "북쪽 산길 →", "늑대·여우 — 도적 소문 있음");   // 독자 = 북로 위
        anchors.put("북쪽_산길", loc(world, cx, cy + 1, cz - 27));

        // NPC 5인 — cheongha_npcs.yml (이름 = 등록제 명사)
        npc(world, anchors.get("청하객잔"), 0f, "객잔 주인 한백");
        npc(world, anchors.get("의뢰소"), 0f, "의뢰소 관리인 소연");
        npc(world, anchors.get("의방"), 180f, "의원 유문");
        npc(world, anchors.get("전장"), 180f, "전장 지점주 금서방");   // F28 — 조문원은 현령이다 (등록부 정합)
        npc(world, loc(world, cx + 3, cy + 1, cz + 3), 135f, "표사 곽진");   // 우물 쪽
        return anchors;
    }

    // ─── 지형 ───

    private static void clearAndFlatten(World world, int cx, int cy, int cz) {
        for (int x = cx - 30; x <= cx + 30; x++) {
            for (int z = cz - 30; z <= cz + 30; z++) {
                world.getBlockAt(x, cy, z).setType(Material.GRASS_BLOCK);
                for (int y = cy + 1; y <= cy + 12; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR);
                }
            }
        }
    }

    /** F29 — 조성 영역 내 기존 혼천 NPC(명패+무적 주민) 제거 — 재조성 = 같은 마을, NPC도 한 벌 */
    private static void clearNpcs(World world, int cx, int cy, int cz) {
        BoundingBox box = new BoundingBox(cx - 31, cy - 8, cz - 31, cx + 32, cy + 16, cz + 32);
        for (Entity e : world.getNearbyEntities(box)) {
            if (e instanceof Villager v && v.getCustomName() != null && v.isInvulnerable()) {
                v.remove();
            }
        }
    }

    /** F31 — 평탄화 경계의 수직 절단면을 6칸 완사면으로 자연 지형에 접합 */
    private static void blendEdge(World world, int cx, int cy, int cz) {
        int r = 30;
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
        for (int x = cx - 6; x <= cx + 6; x++) {
            for (int z = cz - 6; z <= cz + 6; z++) {
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
        for (int d = 7; d <= 27; d++) {   // 십자로 — 북로는 북문 너머 산길(사냥터)로 이어진다
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
            world.getBlockAt(cx - 15, cy, z).setType(Material.DIRT_PATH);   // 객잔
        }
        for (int z = cz - 6; z <= cz - 2; z++) {
            world.getBlockAt(cx + 13, cy, z).setType(Material.DIRT_PATH);   // 의뢰소
        }
        for (int z = cz + 2; z <= cz + 7; z++) {
            world.getBlockAt(cx - 14, cy, z).setType(Material.DIRT_PATH);   // 의방
            world.getBlockAt(cx + 14, cy, z).setType(Material.DIRT_PATH);   // 전장
        }
    }

    // ─── 담장과 대문 — 마을의 경계 ───

    private static void townWall(World world, int cx, int cy, int cz) {
        int r = 28;
        for (int x = cx - r; x <= cx + r; x++) {
            wallColumn(world, x, cy, cz - r);
            wallColumn(world, x, cy, cz + r);
        }
        for (int z = cz - r + 1; z <= cz + r - 1; z++) {
            wallColumn(world, cx - r, cy, z);
            wallColumn(world, cx + r, cy, z);
        }
        // 모서리 각루 — 목주 + 등롱
        for (int dx = -r; dx <= r; dx += 2 * r) {
            for (int dz = -r; dz <= r; dz += 2 * r) {
                for (int y = cy + 1; y <= cy + 4; y++) {
                    world.getBlockAt(cx + dx, y, cz + dz).setType(Material.DARK_OAK_LOG);
                }
                world.getBlockAt(cx + dx, cy + 5, cz + dz).setType(Material.LANTERN);
            }
        }
        gate(world, cx, cy, cz - r, true, "청하현 북문", "북쪽 산길 — 나갈 때 조심");
        gate(world, cx, cy, cz + r, false, "청하현 남문", "관도 — 섬서 지역권");
    }

    private static void wallColumn(World world, int x, int cy, int z) {
        world.getBlockAt(x, cy + 1, z).setType(Material.COBBLESTONE);
        world.getBlockAt(x, cy + 2, z).setType(Material.STONE_BRICKS);
        world.getBlockAt(x, cy + 3, z).setType(Material.STONE_BRICK_WALL);
    }

    /** 대문 — 개구부 3칸 + 목주 문루 + 흑와 처마 + 현판(간판) */
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
            world.getBlockAt(x, cy + 6, gz).setType(Material.DEEPSLATE_TILE_SLAB);   // 처마
        }
        hangingLantern(world, gx, cy + 4, gz);
        int in = north ? 1 : -1;   // 간판은 마을 안쪽
        placeSign(world, gx + 2, cy + 1, gz + in, north ? BlockFace.SOUTH : BlockFace.NORTH, name, subtitle);   // 마을 안쪽에서 읽는다
    }

    // ─── 가로 시설 — 등롱·조경 ───

    private static void streetLanterns(World world, int cx, int cy, int cz) {
        for (int d = 8; d <= 20; d += 6) {   // 남북로 양측
            for (int side = -2; side <= 2; side += 4) {
                lanternPost(world, cx + side, cy, cz - d);
                lanternPost(world, cx + side, cy, cz + d);
            }
        }
        for (int d = 9; d <= 23; d += 7) {   // 동서로 양측
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
        for (int dx = -7; dx <= 7; dx += 14) {   // 광장 네 귀 대각 — 건물 처마(±8)와 간섭 없음
            for (int dz = -7; dz <= 7; dz += 14) {
                plumTree(world, cx + dx, cy, cz + dz);
            }
        }
        for (int dx = -5; dx <= 5; dx += 10) {
            for (int dz = -5; dz <= 5; dz += 10) {
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

    // ─── 건물 — 목골 백벽 흑와 (한옥 느낌의 플레이스홀더) ───

    /**
     * 목골(모서리 흑목 기둥 + 상인방) · 백벽(격자창 유리) · 흑와(계단식 팔작지붕).
     * doorNorth=true 면 북향 입구(광장 남쪽 건물), false 면 남향 입구.
     */
    private static Location house(World world, int x0, int y0, int z0, int w, int d, int wallH,
                                  boolean doorNorth, String name, String subtitle) {
        int x1 = x0 + w - 1;
        int z1 = z0 + d - 1;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                world.getBlockAt(x, y0, z).setType(Material.SPRUCE_PLANKS);   // 마루
                boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
                for (int y = y0 + 1; y <= y0 + wallH; y++) {
                    Material m;
                    if (corner) {
                        m = Material.DARK_OAK_LOG;                       // 기둥
                    } else if (!wall) {
                        m = Material.AIR;
                    } else if (y == y0 + wallH) {
                        m = Material.DARK_OAK_PLANKS;                    // 상인방(도리)
                    } else if (y == y0 + 2 && (x + z) % 2 == 0) {
                        m = Material.GLASS_PANE;                         // 격자창
                    } else {
                        m = Material.WHITE_TERRACOTTA;                   // 백벽
                    }
                    world.getBlockAt(x, y, z).setType(m);
                }
            }
        }
        hipRoof(world, x0 - 1, z0 - 1, x1 + 1, z1 + 1, y0 + wallH + 1);   // 처마 1칸 내밀기
        int doorX = x0 + w / 2;
        int doorZ = doorNorth ? z0 : z1;
        world.getBlockAt(doorX, y0 + 1, doorZ).setType(Material.AIR);
        world.getBlockAt(doorX, y0 + 2, doorZ).setType(Material.AIR);
        int out = doorNorth ? -1 : 1;
        placeSign(world, doorX + 1, y0 + 1, doorZ + out, doorNorth ? BlockFace.NORTH : BlockFace.SOUTH, name, subtitle);   // 입구 앞에서 읽는다
        world.getBlockAt(x0 + 1, y0 + 1, z0 + 1).setType(Material.LANTERN);
        world.getBlockAt(x1 - 1, y0 + 1, z1 - 1).setType(Material.LANTERN);
        return loc(world, doorX, y0 + 1, z0 + d / 2);   // 앵커 = 실내 중앙
    }

    /** 계단식 팔작지붕 느낌 — 층마다 한 칸씩 좁아지는 흑와 스테어 링, 꼭대기는 용마루 슬래브 */
    private static void hipRoof(World world, int x0, int z0, int x1, int z1, int yBase) {
        for (int i = 0; x0 + i <= x1 - i && z0 + i <= z1 - i; i++) {
            int ax = x0 + i, bx = x1 - i, az = z0 + i, bz = z1 - i;
            int y = yBase + i;
            if (bx - ax <= 1 || bz - az <= 1) {
                for (int x = ax; x <= bx; x++) {
                    for (int z = az; z <= bz; z++) {
                        world.getBlockAt(x, y, z).setType(Material.DEEPSLATE_TILE_SLAB);   // 용마루
                    }
                }
                return;
            }
            for (int x = ax; x <= bx; x++) {
                boolean corner = x == ax || x == bx;
                roofBlock(world, x, y, az, BlockFace.SOUTH, corner);
                roofBlock(world, x, y, bz, BlockFace.NORTH, corner);
            }
            for (int z = az + 1; z <= bz - 1; z++) {
                roofBlock(world, ax, y, z, BlockFace.EAST, false);
                roofBlock(world, bx, y, z, BlockFace.WEST, false);
            }
        }
    }

    private static void roofBlock(World world, int x, int y, int z, BlockFace facing, boolean corner) {
        Block block = world.getBlockAt(x, y, z);
        if (corner) {
            block.setType(Material.DEEPSLATE_TILES);   // 추녀마루
            return;
        }
        Stairs stairs = (Stairs) Material.DEEPSLATE_TILE_STAIRS.createBlockData();
        stairs.setFacing(facing);   // 안쪽으로 오르는 기와면
        block.setBlockData(stairs);
    }

    // ─── 내부 집기 — 건물마다 생업의 흔적 ───

    /** 청하객잔 13x9 (cx-21..cx-9, cz-14..cz-6): 화로 + 탁자 4 + 계산대 */
    private static void innInterior(World world, int cx, int cy, int cz) {
        world.getBlockAt(cx - 15, cy + 1, cz - 12).setType(Material.CAMPFIRE);     // 화로
        world.getBlockAt(cx - 16, cy + 1, cz - 12).setType(Material.CAULDRON);     // 국솥
        for (int dx = -19; dx <= -11; dx += 8) {
            for (int dz = -12; dz <= -8; dz += 4) {
                world.getBlockAt(cx + dx, cy + 1, cz + dz).setType(Material.SPRUCE_FENCE);
                world.getBlockAt(cx + dx, cy + 2, cz + dz).setType(Material.SPRUCE_PRESSURE_PLATE);
            }
        }
        world.getBlockAt(cx - 11, cy + 1, cz - 13).setType(Material.BARREL);       // 계산대 술통
        world.getBlockAt(cx - 12, cy + 1, cz - 13).setType(Material.BARREL);
    }

    /** 의방 9x7 (cx-18..cx-10, cz+8..cz+14): 약재 선반 + 탕약 도구 */
    private static void medicineInterior(World world, int cx, int cy, int cz) {
        for (int x = cx - 17; x <= cx - 11; x++) {
            world.getBlockAt(x, cy + 1, cz + 13).setType(Material.BOOKSHELF);      // 약장
            if ((x - cx) % 2 == 0) {
                world.getBlockAt(x, cy + 2, cz + 13).setType(Material.BOOKSHELF);
            }
        }
        world.getBlockAt(cx - 16, cy + 1, cz + 9).setType(Material.BREWING_STAND); // 탕약
        world.getBlockAt(cx - 12, cy + 1, cz + 9).setType(Material.CAULDRON);
    }

    /** 전장 9x7 (cx+10..cx+18, cz+8..cz+14): 카운터 + 금고·장부 */
    private static void exchangeInterior(World world, int cx, int cy, int cz) {
        for (int x = cx + 11; x <= cx + 15; x++) {   // 실내 중앙(앵커) 앞을 가로막는 카운터
            world.getBlockAt(x, cy + 1, cz + 12).setType(Material.DARK_OAK_PLANKS);
        }
        world.getBlockAt(cx + 13, cy + 1, cz + 13).setType(Material.LECTERN);      // 장부
        world.getBlockAt(cx + 11, cy + 1, cz + 13).setType(Material.BARREL);       // 금고
        world.getBlockAt(cx + 12, cy + 1, cz + 13).setType(Material.CHEST);
    }

    /** 의뢰소 9x7 (cx+9..cx+17, cz-13..cz-7): 접수대 */
    private static void requestOfficeInterior(World world, int cx, int cy, int cz) {
        world.getBlockAt(cx + 13, cy + 1, cz - 11).setType(Material.LECTERN);      // 의뢰 대장
        world.getBlockAt(cx + 11, cy + 1, cz - 12).setType(Material.BARREL);       // (cx+10,cz-12)는 실내 등롱
    }

    /** 의뢰소 앞 게시판 — 간판 여러 장 (사건 소문과 맞물리는 의뢰 공고) */
    private static void bulletinBoard(World world, int cx, int cy, int cz) {
        for (int x = cx + 9; x <= cx + 11; x++) {
            world.getBlockAt(x, cy + 1, cz - 5).setType(Material.SPRUCE_FENCE);
            world.getBlockAt(x, cy + 2, cz - 5).setType(Material.DARK_OAK_PLANKS);
            world.getBlockAt(x, cy + 3, cz - 5).setType(Material.DARK_OAK_PLANKS);
        }
        placeWallSign(world, cx + 9, cy + 2, cz - 4, "의뢰: 북쪽 산길", "정찰 — 보수 상담");
        placeWallSign(world, cx + 10, cy + 2, cz - 4, "구함: 상단 호위", "표국 경력 우대");
        placeWallSign(world, cx + 11, cy + 2, cz - 4, "급구: 약재", "의방 유문 앞");
    }

    // ─── 장터 — 노점 5개, 차양 5색 ───

    private static void marketStalls(World world, int cx, int cy, int cz) {
        stall(world, cx + 6, cy, cz - 4, 1, Material.RED_WOOL);       // 붉은 차양 — 가죽 매입
        stall(world, cx + 10, cy, cz - 4, 1, Material.YELLOW_WOOL);
        stall(world, cx + 6, cy, cz + 4, -1, Material.LIME_WOOL);
        stall(world, cx + 10, cy, cz + 4, -1, Material.LIGHT_BLUE_WOOL);
        stall(world, cx - 12, cy, cz - 4, 1, Material.ORANGE_WOOL);   // 서시(西市) 외톨이 노점
        placeSign(world, cx + 5, cy + 1, cz - 2, BlockFace.WEST, "장터", "가죽 매입 — /혼천 팔기");   // 독자 = 광장 쪽
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
