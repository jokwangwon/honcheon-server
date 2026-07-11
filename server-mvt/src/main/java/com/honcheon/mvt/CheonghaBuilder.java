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
import java.util.List;
import java.util.Map;

/**
 * 청하현 조성기 (M2b·v3) — config/regions/cheongha_hyeon.yml 의 장소를 실블록으로 세운다.
 * 철학: 맵도 컴파일한다 — 손건축이 아니라 결정론 생성 (재조성 = 같은 마을).
 * 디자인 언어: docs/design/map_design.md — 수묵 3색(목골·백벽·흑와) + 차양 채색.
 * 규모(v3): 101x101 (담장 반경 r=50). 광장 15x15, 담장+대문(북·남), 우물(지붕)·매화나무·화단,
 * 2층 청하객잔 15x11 (내부 계단, 1층 주청·2층 객방 통칸), 관청류 11x9 3채(의뢰소·의방·전장),
 * 일반 민가 9채(A 마당집 7x6 · B 골목집 6x5 · C 오두막 5x5 — 앵커·NPC 없는 순수 풍경),
 * 북·남 골목길 2줄, 노점 5개(차양 5색), 길가 등롱. 정식 맵은 M3 몫 (tools/mapgen 승격).
 */
final class CheonghaBuilder {

    private CheonghaBuilder() {
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

        // 앵커 건물 4채 — cheongha_hyeon.yml places. 객잔은 2층 대형(15x11), 나머지 11x9.
        // 도로변 입구: 북쪽 두 채는 남향, 남쪽 두 채는 북향(광장을 바라본다).
        anchors.put("청하객잔", inn(world, cx, cy, cz));
        anchors.put("의뢰소", house(world, cx + 11, cy, cz - 15, 11, 9, 4, false,
                "의뢰소", "정파 연락망 — 일과 보수"));
        anchors.put("의방", house(world, cx - 22, cy, cz + 8, 11, 9, 4, true,
                "약재상 · 의방", "외상 장부 있음"));
        anchors.put("전장", house(world, cx + 12, cy, cz + 8, 11, 9, 4, true,
                "청하전장", "전표 = 가져온 이가 임자"));

        medicineInterior(world, cx, cy, cz);
        exchangeInterior(world, cx, cy, cz);
        requestOfficeInterior(world, cx, cy, cz);
        bulletinBoard(world, cx, cy, cz);
        cottages(world, cx, cy, cz);         // 일반 민가 9채 — 마을의 생기 (앵커·구역·NPC 없음)
        doorPaths(world, cx, cy, cz);
        alleys(world, cx, cy, cz);

        marketStalls(world, cx, cy, cz);
        placeSign(world, cx + 2, cy + 1, cz - 48, BlockFace.WEST, "북쪽 산길 →", "늑대·여우 — 도적 소문 있음");   // 독자 = 북로 위
        anchors.put("북쪽_산길", loc(world, cx, cy + 1, cz - 49));

        // NPC 5인 — cheongha_npcs.yml (이름 = 등록제 명사)
        npc(world, anchors.get("청하객잔"), 0f, "객잔 주인 한백");
        npc(world, anchors.get("의뢰소"), 0f, "의뢰소 관리인 소연");
        npc(world, anchors.get("의방"), 180f, "의원 유문");
        npc(world, anchors.get("전장"), 180f, "전장 지점주 금서방");   // F28 — 조문원은 현령이다 (등록부 정합)
        npc(world, loc(world, cx + 3, cy + 1, cz + 3), 135f, "표사 곽진");   // 우물 쪽

        zones(world, cx, cy, cz, zonesOut);
        return anchors;
    }

    // ─── 구역 — 입장 타이틀의 단위 (마을 전체 → 건물·장터 순으로 좁아진다) ───

    private static void zones(World world, int cx, int cy, int cz, List<Zone> out) {
        String w = world.getName();
        out.add(new Zone("청하현", "섬서의 작은 현 — 강호의 첫 걸음", w,
                cx - 50, cy - 2, cz - 50, cx + 50, cy + 19, cz + 50));
        out.add(new Zone("청하객잔", "소문은 국밥보다 빨리 식는다", w,
                cx - 25, cy - 2, cz - 16, cx - 11, cy + 19, cz - 6));
        out.add(new Zone("의뢰소", "정파 연락망 — 일과 보수", w,
                cx + 11, cy - 2, cz - 15, cx + 21, cy + 13, cz - 7));
        out.add(new Zone("의방", "약재상 — 외상 장부 있음", w,
                cx - 22, cy - 2, cz + 8, cx - 12, cy + 13, cz + 16));
        out.add(new Zone("청하전장", "전표 = 가져온 이가 임자", w,
                cx + 12, cy - 2, cz + 8, cx + 22, cy + 13, cz + 16));
        out.add(new Zone("장터", "가죽 매입 — /혼천 팔기", w,
                cx + 5, cy - 2, cz - 7, cx + 15, cy + 7, cz + 7));
    }

    // ─── 지형 ───

    private static void clearAndFlatten(World world, int cx, int cy, int cz) {
        for (int x = cx - 52; x <= cx + 52; x++) {
            for (int z = cz - 52; z <= cz + 52; z++) {
                world.getBlockAt(x, cy, z).setType(Material.GRASS_BLOCK);
                for (int y = cy + 1; y <= cy + 18; y++) {   // 객잔 용마루 cy+16 여유
                    world.getBlockAt(x, y, z).setType(Material.AIR);
                }
            }
        }
    }

    /** F29 — 조성 영역 내 기존 혼천 NPC(명패+무적 주민) 제거 — 재조성 = 같은 마을, NPC도 한 벌 */
    private static void clearNpcs(World world, int cx, int cy, int cz) {
        BoundingBox box = new BoundingBox(cx - 53, cy - 8, cz - 53, cx + 54, cy + 20, cz + 54);
        for (Entity e : world.getNearbyEntities(box)) {
            if (e instanceof Villager v && v.getCustomName() != null && v.isInvulnerable()) {
                v.remove();
            }
        }
    }

    /** F31 — 평탄화 경계의 수직 절단면을 6칸 완사면으로 자연 지형에 접합 */
    private static void blendEdge(World world, int cx, int cy, int cz) {
        int r = 52;
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
        for (int d = 8; d <= 49; d++) {   // 십자로 — 북로는 북문 너머 산길(사냥터)로 이어진다
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
            world.getBlockAt(cx - 18, cy, z).setType(Material.DIRT_PATH);   // 객잔 (2칸 폭 대문)
            world.getBlockAt(cx - 17, cy, z).setType(Material.DIRT_PATH);
        }
        for (int z = cz - 6; z <= cz - 2; z++) {
            world.getBlockAt(cx + 16, cy, z).setType(Material.DIRT_PATH);   // 의뢰소
        }
        for (int z = cz + 2; z <= cz + 7; z++) {
            world.getBlockAt(cx - 17, cy, z).setType(Material.DIRT_PATH);   // 의방
            world.getBlockAt(cx + 17, cy, z).setType(Material.DIRT_PATH);   // 전장
        }
    }

    /** 민가 골목길 — 북골목(z-21)·남골목(z+21) 두 줄, 남북대로와 교차한다 */
    private static void alleys(World world, int cx, int cy, int cz) {
        for (int x = cx - 38; x <= cx + 38; x++) {
            world.getBlockAt(x, cy, cz - 21).setType(Material.DIRT_PATH);
            world.getBlockAt(x, cy, cz + 21).setType(Material.DIRT_PATH);
        }
    }

    // ─── 담장과 대문 — 마을의 경계 ───

    private static void townWall(World world, int cx, int cy, int cz) {
        int r = 50;
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
        for (int d = 10; d <= 46; d += 6) {   // 남북로 양측
            for (int side = -2; side <= 2; side += 4) {
                lanternPost(world, cx + side, cy, cz - d);
                lanternPost(world, cx + side, cy, cz + d);
            }
        }
        for (int d = 12; d <= 40; d += 7) {   // 동서로 양측 — 건물 골목(x±16..18)을 피한 간격
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
        for (int dx = -8; dx <= 8; dx += 16) {   // 광장(±7) 네 귀 대각 — 건물 처마(±10)와 간섭 없음
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

    // ─── 건물 — 목골 백벽 흑와 (한옥 느낌의 플레이스홀더) ───

    /**
     * 목골(모서리 흑목 기둥 + 상인방) · 백벽(격자창 유리) · 흑와(계단식 팔작지붕).
     * doorNorth=true 면 북향 입구(광장 남쪽 건물), false 면 남향 입구.
     */
    private static Location house(World world, int x0, int y0, int z0, int w, int d, int wallH,
                                  boolean doorNorth, String name, String subtitle) {
        shell(world, x0, y0, z0, w, d, wallH, doorNorth);
        int x1 = x0 + w - 1;
        int z1 = z0 + d - 1;
        int doorX = x0 + w / 2;
        int doorZ = doorNorth ? z0 : z1;
        int out = doorNorth ? -1 : 1;
        placeSign(world, doorX + 1, y0 + 1, doorZ + out, doorNorth ? BlockFace.NORTH : BlockFace.SOUTH, name, subtitle);   // 입구 앞에서 읽는다
        world.getBlockAt(x0 + 1, y0 + 1, z0 + 1).setType(Material.LANTERN);
        world.getBlockAt(x1 - 1, y0 + 1, z1 - 1).setType(Material.LANTERN);
        return loc(world, doorX, y0 + 1, z0 + d / 2);   // 앵커 = 실내 중앙
    }

    /** 건물 골조 공통 — 마루·벽(기둥·백벽·격자창·상인방)·팔작지붕·문 1칸. 간판·앵커 없음 */
    private static void shell(World world, int x0, int y0, int z0, int w, int d, int wallH,
                              boolean doorNorth) {
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
    }

    /**
     * 청하객잔 — 2층 대형 15x11 (cx-25..cx-11, cz-16..cz-6). 남향 2칸 폭 대문.
     * 1층 주청(화로·국솥·탁자 6·계산대) — 내부 계단 — 2층 객방 통칸(침상 4·난간).
     * 벽 구성: 1층 y+1..y+4 (도리 y+4) · 층간 띠/2층 바닥 y+5 · 2층 y+6..y+9 (상인방 y+9) · 지붕 y+10~.
     */
    private static Location inn(World world, int cx, int cy, int cz) {
        int x0 = cx - 25, z0 = cz - 16, w = 15, d = 11;
        int x1 = x0 + w - 1, z1 = z0 + d - 1;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                world.getBlockAt(x, cy, z).setType(Material.SPRUCE_PLANKS);   // 1층 마루
                boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
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
        hipRoof(world, x0 - 1, z0 - 1, x1 + 1, z1 + 1, cy + 10);   // 용마루 cy+16
        // 남향 대문 2칸 폭 (cx-18, cx-17)
        for (int x = cx - 18; x <= cx - 17; x++) {
            world.getBlockAt(x, cy + 1, z1).setType(Material.AIR);
            world.getBlockAt(x, cy + 2, z1).setType(Material.AIR);
        }
        placeSign(world, cx - 16, cy + 1, z1 + 1, BlockFace.SOUTH, "청하객잔", "소문은 국밥보다 빨리 식는다");
        innStairs(world, cx, cy, cz);
        innHall(world, cx, cy, cz);
        innLoft(world, cx, cy, cz);
        return loc(world, cx - 18, cy + 1, cz - 11);   // 앵커 = 1층 주청 중앙 (한백)
    }

    /** 객잔 내부 계단 — 동벽(x=cx-12)을 따라 북쪽으로 오른다. 2층 바닥 개구 + 난간 */
    private static void innStairs(World world, int cx, int cy, int cz) {
        for (int i = 0; i < 4; i++) {
            int z = cz - 8 - i;
            Block step = world.getBlockAt(cx - 12, cy + 1 + i, z);
            Stairs stairs = (Stairs) Material.SPRUCE_STAIRS.createBlockData();
            stairs.setFacing(BlockFace.NORTH);   // 북쪽으로 오르는 디딤
            step.setBlockData(stairs);
            for (int y = cy + 1; y <= cy + i; y++) {
                world.getBlockAt(cx - 12, y, z).setType(Material.SPRUCE_PLANKS);   // 계단 받침
            }
            world.getBlockAt(cx - 12, cy + 5, z).setType(Material.AIR);            // 2층 바닥 개구
            world.getBlockAt(cx - 13, cy + 6, z).setType(Material.SPRUCE_FENCE);   // 개구 난간
        }
    }

    /** 객잔 1층 주청 — 화로·국솥, 탁자 6, 계산대 술통 */
    private static void innHall(World world, int cx, int cy, int cz) {
        world.getBlockAt(cx - 23, cy + 1, cz - 14).setType(Material.CAMPFIRE);     // 화로
        world.getBlockAt(cx - 22, cy + 1, cz - 14).setType(Material.CAULDRON);     // 국솥
        int[][] tables = {{-21, -9}, {-21, -12}, {-16, -9}, {-16, -12}, {-14, -13}, {-20, -14}};
        for (int[] t : tables) {
            world.getBlockAt(cx + t[0], cy + 1, cz + t[1]).setType(Material.SPRUCE_FENCE);
            world.getBlockAt(cx + t[0], cy + 2, cz + t[1]).setType(Material.SPRUCE_PRESSURE_PLATE);
        }
        world.getBlockAt(cx - 14, cy + 1, cz - 7).setType(Material.BARREL);        // 계산대 술통
        world.getBlockAt(cx - 13, cy + 1, cz - 7).setType(Material.BARREL);
        world.getBlockAt(cx - 24, cy + 1, cz - 15).setType(Material.LANTERN);
        world.getBlockAt(cx - 12, cy + 1, cz - 7).setType(Material.LANTERN);
    }

    /** 객잔 2층 객방 통칸 — 침상(백색 깔개) 4, 짐 술통, 바닥 등롱 */
    private static void innLoft(World world, int cx, int cy, int cz) {
        int[][] beds = {{-24, -15}, {-23, -15}, {-20, -15}, {-19, -15},
                {-16, -15}, {-15, -15}, {-24, -9}, {-24, -8}};   // 2칸 침상 x4
        for (int[] b : beds) {
            world.getBlockAt(cx + b[0], cy + 6, cz + b[1]).setType(Material.WHITE_CARPET);
        }
        world.getBlockAt(cx - 13, cy + 6, cz - 15).setType(Material.BARREL);
        world.getBlockAt(cx - 18, cy + 6, cz - 11).setType(Material.LANTERN);
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

    // ─── 일반 민가 — 앵커·구역·NPC 없는 순수 풍경 (마을의 생기) ───

    /**
     * 민가 9채 — 결정론 좌표표 (난수 금지). 북골목(z-21)·남골목(z+21)을 따라 문이 골목을 본다.
     * 3종 변형: A 마당집 7x6(동측 울타리 마당) · B 골목집 6x5(실내 화분) · C 오두막 5x5(침상뿐).
     */
    private static void cottages(World world, int cx, int cy, int cz) {
        yardHouse(world, cx - 36, cy, cz - 27, false);            // 북골목 북측 — 남향
        cottage(world, cx - 24, cy, cz - 26, 6, 5, false, 1);
        cottage(world, cx + 8, cy, cz - 26, 5, 5, false, 2);
        yardHouse(world, cx + 26, cy, cz - 27, false);
        cottage(world, cx + 35, cy, cz - 20, 6, 5, true, 1);      // 북골목 남측 — 북향
        yardHouse(world, cx - 35, cy, cz + 22, true);             // 남골목 남측 — 북향
        cottage(world, cx + 9, cy, cz + 22, 5, 5, true, 2);
        cottage(world, cx + 27, cy, cz + 22, 6, 5, true, 1);
        cottage(world, cx - 8, cy, cz + 16, 5, 5, false, 2);      // 남골목 북측 — 남향
    }

    /** A형 마당집 — 7x6 골조 + 동측 3칸 울타리 마당(퇴비통·양귀비) */
    private static void yardHouse(World world, int x0, int y0, int z0, boolean doorNorth) {
        cottage(world, x0, y0, z0, 7, 6, doorNorth, 0);
        int x1 = x0 + 6, z1 = z0 + 5;
        for (int z = z0; z <= z1; z++) {
            world.getBlockAt(x1 + 3, y0 + 1, z).setType(Material.SPRUCE_FENCE);
        }
        for (int x = x1 + 1; x <= x1 + 3; x++) {
            world.getBlockAt(x, y0 + 1, z0).setType(Material.SPRUCE_FENCE);
            world.getBlockAt(x, y0 + 1, z1).setType(Material.SPRUCE_FENCE);
        }
        world.getBlockAt(x1 + 1, y0 + 1, doorNorth ? z0 : z1).setType(Material.AIR);   // 마당 입구 = 문쪽
        world.getBlockAt(x1 + 2, y0 + 1, z0 + 2).setType(Material.COMPOSTER);
        world.getBlockAt(x1 + 2, y0 + 1, z0 + 3).setType(Material.POPPY);
    }

    /** 민가 골조 — 목골 백벽 흑와 문법의 소형판(벽고 3). variant: 0 살림상 1 화분·술통 2 침상 */
    private static void cottage(World world, int x0, int y0, int z0, int w, int d,
                                boolean doorNorth, int variant) {
        shell(world, x0, y0, z0, w, d, 3, doorNorth);
        int x1 = x0 + w - 1, z1 = z0 + d - 1;
        int farZ = doorNorth ? z1 - 1 : z0 + 1;   // 문 반대편 안쪽
        world.getBlockAt(x0 + 1, y0 + 1, doorNorth ? z0 + 1 : z1 - 1).setType(Material.LANTERN);
        switch (variant) {
            case 0 -> world.getBlockAt(x1 - 1, y0 + 1, farZ).setType(Material.CRAFTING_TABLE);
            case 1 -> {
                world.getBlockAt(x1 - 1, y0 + 1, farZ).setType(Material.BARREL);
                world.getBlockAt(x0 + 1, y0 + 1, farZ).setType(Material.POTTED_POPPY);
            }
            default -> {
                world.getBlockAt(x1 - 1, y0 + 1, farZ).setType(Material.WHITE_CARPET);   // 침상
                world.getBlockAt(x1 - 2, y0 + 1, farZ).setType(Material.WHITE_CARPET);
            }
        }
    }

    // ─── 내부 집기 — 건물마다 생업의 흔적 ───

    /** 의방 11x9 (cx-22..cx-12, cz+8..cz+16): 약장 벽면 + 탕약 도구 + 진료 평상 */
    private static void medicineInterior(World world, int cx, int cy, int cz) {
        for (int x = cx - 21; x <= cx - 14; x++) {
            world.getBlockAt(x, cy + 1, cz + 15).setType(Material.BOOKSHELF);      // 약장
            if ((x - cx) % 2 == 0) {
                world.getBlockAt(x, cy + 2, cz + 15).setType(Material.BOOKSHELF);
            }
        }
        world.getBlockAt(cx - 21, cy + 1, cz + 12).setType(Material.BREWING_STAND); // 탕약
        world.getBlockAt(cx - 21, cy + 1, cz + 13).setType(Material.CAULDRON);
        world.getBlockAt(cx - 14, cy + 1, cz + 10).setType(Material.WHITE_CARPET);  // 진료 평상
        world.getBlockAt(cx - 14, cy + 1, cz + 11).setType(Material.WHITE_CARPET);
    }

    /** 전장 11x9 (cx+12..cx+22, cz+8..cz+16): 실내를 가로막는 카운터 + 장부 + 금고벽 */
    private static void exchangeInterior(World world, int cx, int cy, int cz) {
        for (int x = cx + 14; x <= cx + 20; x++) {   // 카운터 — 손님(북)과 지점주(남)를 가른다
            world.getBlockAt(x, cy + 1, cz + 11).setType(Material.DARK_OAK_PLANKS);
        }
        world.getBlockAt(cx + 15, cy + 1, cz + 12).setType(Material.LECTERN);      // 장부
        world.getBlockAt(cx + 18, cy + 1, cz + 15).setType(Material.CHEST);        // 금고벽
        world.getBlockAt(cx + 19, cy + 1, cz + 15).setType(Material.BARREL);
        world.getBlockAt(cx + 20, cy + 1, cz + 15).setType(Material.BARREL);
    }

    /** 의뢰소 11x9 (cx+11..cx+21, cz-15..cz-7): 접수 대장 + 대기 걸상 */
    private static void requestOfficeInterior(World world, int cx, int cy, int cz) {
        world.getBlockAt(cx + 14, cy + 1, cz - 11).setType(Material.LECTERN);      // 의뢰 대장
        world.getBlockAt(cx + 13, cy + 1, cz - 14).setType(Material.BARREL);
        for (int x = cx + 13; x <= cx + 19; x += 6) {   // 대기 걸상 2
            world.getBlockAt(x, cy + 1, cz - 9).setType(Material.SPRUCE_FENCE);
            world.getBlockAt(x, cy + 2, cz - 9).setType(Material.SPRUCE_PRESSURE_PLATE);
        }
    }

    /** 의뢰소 앞 게시판 — 간판 여러 장 (사건 소문과 맞물리는 의뢰 공고) */
    private static void bulletinBoard(World world, int cx, int cy, int cz) {
        for (int x = cx + 18; x <= cx + 20; x++) {
            world.getBlockAt(x, cy + 1, cz - 5).setType(Material.SPRUCE_FENCE);
            world.getBlockAt(x, cy + 2, cz - 5).setType(Material.DARK_OAK_PLANKS);
            world.getBlockAt(x, cy + 3, cz - 5).setType(Material.DARK_OAK_PLANKS);
        }
        placeWallSign(world, cx + 18, cy + 2, cz - 4, "의뢰: 북쪽 산길", "정찰 — 보수 상담");
        placeWallSign(world, cx + 19, cy + 2, cz - 4, "구함: 상단 호위", "표국 경력 우대");
        placeWallSign(world, cx + 20, cy + 2, cz - 4, "급구: 약재", "의방 유문 앞");
    }

    // ─── 장터 — 노점 5개, 차양 5색 ───

    private static void marketStalls(World world, int cx, int cy, int cz) {
        stall(world, cx + 7, cy, cz - 5, 1, Material.RED_WOOL);       // 붉은 차양 — 가죽 매입 (장터 반경 15 내)
        stall(world, cx + 11, cy, cz - 5, 1, Material.YELLOW_WOOL);
        stall(world, cx + 7, cy, cz + 5, -1, Material.LIME_WOOL);
        stall(world, cx + 11, cy, cz + 5, -1, Material.LIGHT_BLUE_WOOL);
        stall(world, cx - 14, cy, cz - 5, 1, Material.ORANGE_WOOL);   // 서시(西市) 외톨이 노점
        placeSign(world, cx + 6, cy + 1, cz - 3, BlockFace.WEST, "장터", "가죽 매입 — /혼천 팔기");   // 독자 = 광장 쪽
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
