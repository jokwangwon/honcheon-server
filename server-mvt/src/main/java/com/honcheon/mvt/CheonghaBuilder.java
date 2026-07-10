package com.honcheon.mvt;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 청하현 조성기 (M2b) — config/regions/cheongha_hyeon.yml 의 장소를 실블록으로 세운다.
 * 철학: 맵도 컴파일한다 — 손건축이 아니라 결정론 생성 (재조성 = 같은 마을).
 * 규모: 41x41 (광장 + 우물 + 건물 4채 + 장터 노점 + 북쪽 산길 표지). 정식 맵은 M3 몫.
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

        clearAndFlatten(world, cx, cy, cz);
        plazaAndWell(world, cx, cy, cz);
        roads(world, cx, cy, cz);

        Map<String, Location> anchors = new LinkedHashMap<>();
        anchors.put("장터", loc(world, cx, cy + 1, cz));

        // 건물 4채 — cheongha_hyeon.yml places (footprint 9x7, 남향 입구)
        anchors.put("청하객잔", house(world, cx - 16, cy, cz - 3, 9, 7, "청하객잔", "소문은 국밥보다 빨리 식는다"));
        anchors.put("의뢰소", house(world, cx + 8, cy, cz - 3, 9, 7, "의뢰소", "정파 연락망 — 일과 보수"));
        anchors.put("의방", house(world, cx - 4, cy, cz + 10, 9, 7, "약재상 · 의방", "외상 장부 있음"));
        anchors.put("전장", house(world, cx - 4, cy, cz - 17, 9, 7, "청하전장", "전표 = 지참인 지불"));

        marketStalls(world, cx, cy, cz);
        northRoadSign(world, cx, cy, cz - 20);
        anchors.put("북쪽_산길", loc(world, cx, cy + 1, cz - 20));

        // NPC 5인 — cheongha_npcs.yml (이름 = 등록제 명사)
        npc(world, anchors.get("청하객잔"), "객잔 주인 한백");
        npc(world, anchors.get("의뢰소"), "의뢰소 관리인 소연");
        npc(world, anchors.get("의방"), "의원 유문");
        npc(world, anchors.get("전장"), "전장 서기 조문원");
        npc(world, loc(world, cx + 3, cy + 1, cz + 3), "표사 곽진");
        return anchors;
    }

    // ─── 지형 ───

    private static void clearAndFlatten(World world, int cx, int cy, int cz) {
        for (int x = cx - 20; x <= cx + 20; x++) {
            for (int z = cz - 24; z <= cz + 18; z++) {
                world.getBlockAt(x, cy, z).setType(Material.GRASS_BLOCK);
                for (int y = cy + 1; y <= cy + 8; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR);
                }
            }
        }
    }

    private static void plazaAndWell(World world, int cx, int cy, int cz) {
        for (int x = cx - 5; x <= cx + 5; x++) {
            for (int z = cz - 5; z <= cz + 5; z++) {
                world.getBlockAt(x, cy, z).setType(Material.SMOOTH_STONE);
            }
        }
        // 우물 — 광장의 심장 (소문이 모이는 곳)
        for (int x = cx - 1; x <= cx + 1; x++) {
            for (int z = cz - 1; z <= cz + 1; z++) {
                boolean rim = x != cx || z != cz;
                world.getBlockAt(x, cy + 1, z).setType(rim ? Material.COBBLESTONE_WALL : Material.AIR);
                world.getBlockAt(x, cy, z).setType(rim ? Material.COBBLESTONE : Material.WATER);
            }
        }
    }

    private static void roads(World world, int cx, int cy, int cz) {
        for (int d = 6; d <= 20; d++) {   // 십자로 — 북로는 산길(사냥터)로 이어진다
            for (int w = -1; w <= 1; w++) {
                world.getBlockAt(cx + w, cy, cz - d).setType(Material.DIRT_PATH);
                if (d <= 18) {
                    world.getBlockAt(cx + w, cy, cz + d).setType(Material.DIRT_PATH);
                    world.getBlockAt(cx - d, cy, cz + w).setType(Material.DIRT_PATH);
                    world.getBlockAt(cx + d, cy, cz + w).setType(Material.DIRT_PATH);
                }
            }
        }
    }

    // ─── 건물 — 목골 백벽 흑와 (한옥 느낌의 플레이스홀더) ───

    private static Location house(World world, int x0, int y0, int z0, int w, int d,
                                  String name, String subtitle) {
        int x1 = x0 + w - 1;
        int z1 = z0 + d - 1;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                world.getBlockAt(x, y0, z).setType(Material.SPRUCE_PLANKS);   // 마루
                boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
                for (int y = y0 + 1; y <= y0 + 3; y++) {
                    world.getBlockAt(x, y, z).setType(
                            corner ? Material.DARK_OAK_LOG
                                    : wall ? Material.WHITE_TERRACOTTA : Material.AIR);
                }
                world.getBlockAt(x, y0 + 4, z).setType(Material.DEEPSLATE_TILE_SLAB);   // 흑와
            }
        }
        int doorX = x0 + w / 2;
        world.getBlockAt(doorX, y0 + 1, z1).setType(Material.AIR);   // 남향 입구
        world.getBlockAt(doorX, y0 + 2, z1).setType(Material.AIR);
        world.getBlockAt(x0 + 1, y0 + 1, z0 + 1).setType(Material.LANTERN);
        placeSign(world, doorX + 1, y0 + 1, z1 + 1, name, subtitle);
        return loc(world, doorX, y0 + 1, z0 + d / 2);   // 앵커 = 실내 중앙
    }

    private static void marketStalls(World world, int cx, int cy, int cz) {
        for (int i = 0; i < 3; i++) {   // 노점 3채 — 광장 동쪽 (팔기는 여기서)
            int x = cx + 7;
            int z = cz - 3 + i * 3;
            world.getBlockAt(x, cy + 1, z).setType(Material.BARREL);
            world.getBlockAt(x + 1, cy + 1, z).setType(Material.SPRUCE_FENCE);
            world.getBlockAt(x + 1, cy + 2, z).setType(Material.RED_WOOL);   // 차양
            world.getBlockAt(x, cy + 2, z).setType(Material.RED_WOOL);
        }
        placeSign(world, cx + 6, cy + 1, cz, "장터", "가죽 매입 — /혼천 팔기");
    }

    private static void northRoadSign(World world, int x, int y, int z) {
        placeSign(world, x + 1, y + 1, z, "북쪽 산길 →", "늑대·여우 — 도적 소문 있음");
    }

    private static void placeSign(World world, int x, int y, int z, String line1, String line2) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.OAK_SIGN);
        if (block.getState() instanceof Sign sign) {
            sign.getSide(Side.FRONT).setLine(1, line1);
            sign.getSide(Side.FRONT).setLine(2, line2);
            sign.update();
        }
    }

    private static void npc(World world, Location at, String name) {
        Villager v = (Villager) world.spawnEntity(at, EntityType.VILLAGER);
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
