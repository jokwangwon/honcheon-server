package com.honcheon.mvt;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Stairs;

import java.util.ArrayList;
import java.util.List;

/**
 * 원거리 지역 조성기 — <b>등록부의 좌표에 실제로 집이 서게 한다</b>.
 *
 * <p>세계 지도(world_map.yml)에는 33곳이 좌표를 갖고 있는데, 지금까지 <b>선 것은 청하현과 산길 도적뿐</b>이고
 * 나머지는 {@code build: later} 였다. 등록만 되고 서지 않은 곳은 여행의 목적지가 아니라 문서의 줄이다.
 *
 * <p>실지리 1:1(1블록 = 1m)이라 33곳을 손으로 지을 수는 없다. 그래서 <b>원형(archetype)</b>을 짓는다:
 * 등록부가 세력·등급·건축을 적어 두었으니, 조성기는 그것을 읽어 원형을 고른다.
 * <ul>
 *   <li><b>산채(寨)</b> — 녹림. 목책과 통나무 막사. "도적의 집은 언제든 버릴 수 있어야 한다"(등록부의 말)</li>
 * </ul>
 * (문파 산문·석성 총채·성시 관문은 다음 원형이다 — 원형이 늘 때마다 지도의 한 켜가 실물이 된다.)
 *
 * <p><b>결정론</b> — 조성기에 난수는 없다. 흔들림은 전부 좌표 해시({@code Math.floorMod})다.
 * 같은 자리에 두 번 지으면 같은 산채가 선다.
 *
 * <p><b>지형</b> — 산에 짓는다. 평지를 깎지 않고 <b>단(段)</b>을 놓는다: 부지의 지면 중앙값을 바닥으로 잡고,
 * 낮은 데는 통나무 기둥으로 받치고 높은 데는 깎는다. 산채가 산에 붙어 있어야 산채다.
 */
final class RemoteBuilder {

    private RemoteBuilder() {
    }

    /** 산채 반지름 — 목책 둘레 (등록부 scale 200 은 '산 하나'의 규모다. 채(寨) 자체는 이만하면 산다) */
    private static final int R = 22;

    /**
     * 자연이 놓은 자재 — <b>사람이 지은 것과 가르는 선</b>.
     *
     * <p>이 선이 없어서 두 번 속았다: 재조성이 옛 지붕(기와)을 자연으로 알고 남겨 두었고,
     * 그 지붕 때문에 "지면이 이미 높다"고 읽어 <b>산을 세우지 않았다</b>(사막 위 도관이 두 번 섰다).
     * 자연은 이 목록뿐이고, 나머지는 전부 사람의 것이다.
     */
    private static final java.util.Set<Material> NATURAL = java.util.EnumSet.of(
            Material.STONE, Material.DEEPSLATE, Material.TUFF, Material.GRANITE, Material.DIORITE,
            Material.ANDESITE, Material.CALCITE, Material.DIRT, Material.COARSE_DIRT,
            Material.ROOTED_DIRT, Material.GRASS_BLOCK, Material.PODZOL, Material.MYCELIUM,
            Material.SAND, Material.RED_SAND, Material.SANDSTONE, Material.RED_SANDSTONE,
            Material.GRAVEL, Material.CLAY, Material.TERRACOTTA, Material.SNOW_BLOCK,
            Material.PACKED_ICE, Material.ICE, Material.WATER, Material.LAVA, Material.BEDROCK,
            Material.MOSS_BLOCK, Material.MUD);

    /**
     * 기초 봉인 — <b>바닥 밑이 비면 그것은 껍데기다</b>.
     *
     * <p>인게임에서 드러난 파탄: 우리가 깐 길·마당이 바닐라 동굴 위에 얇게 덮여 있었다(환경 검수 13%).
     * 발밑을 딛으면 그 아래가 허공이고, 어딘가는 뚫려 있고, 협곡이 잘려 열렸다.
     * 조성 지면 아래 <b>여섯 칸</b>은 반드시 단단해야 한다 — 그 아래 동굴은 살려 둔다(세계의 자산이다).
     * 채우는 자재는 자연을 따른다: 얕은 데는 흙, 깊은 데는 돌.
     */
    private static void sealBelow(World world, int x, int y, int z) {
        for (int i = 1; i <= 6; i++) {
            org.bukkit.block.Block b = world.getBlockAt(x, y - i, z);
            if (b.getType().isAir() || b.isLiquid()) {
                b.setType(i <= 2 ? Material.DIRT : Material.STONE);
            }
        }
    }

    /**
     * 경계 페더링 — <b>부지가 자연으로 스며들게 한다</b>.
     *
     * <p>단(段)을 놓으면 그 가장자리에서 지형이 뚝 끊긴다. 산채는 경계 급단차 52.7%(최대 20칸)였고,
     * <b>북쪽에서는 아예 걸어 들어올 수 없었다</b> — 우리가 산비탈에 벼랑을 냈기 때문이다.
     *
     * <p>페더링은 안(조성 지면)에서 밖(자연 지면)으로 높이를 <b>여러 칸에 걸쳐</b> 잇는다:
     * 낮은 데는 메우고 높은 데는 깎되, 거리에 비례해 자연 쪽으로 수렴한다. 좌표 해시로 흔들어
     * 전이대가 자로 그은 것처럼 보이지 않게 한다.
     */
    private static void feather(World world, int cx, int cy, int cz, int rInner, int rOuter) {
        for (int x = cx - rOuter; x <= cx + rOuter; x++) {
            for (int z = cz - rOuter; z <= cz + rOuter; z++) {
                double d = dist(x - cx, z - cz);
                if (d < rInner || d > rOuter) {
                    continue;
                }
                int natural = naturalGround(world, x, z, cy + 50);
                double t = (d - rInner) / (double) (rOuter - rInner);   // 0 = 부지, 1 = 자연
                int jitter = Math.floorMod(x * 7 + z * 11, 3) - 1;
                int target = (int) Math.round(cy * (1 - t) + natural * t) + jitter;
                if (Math.abs(target - natural) <= 1) {
                    continue;   // 이미 자연과 같다 — 손대지 않는다
                }
                // 위를 비운다(깎기) — **여섯 칸으로는 모자랐다**: 비탈을 자른 자리에 20칸짜리 절개 벼랑이
                //   그대로 서 있었고(환경 검수: 경계 급단차 41%), 우리는 그것을 못 봤다.
                //   전이대의 높이까지 깎아야 전이대다.
                for (int y = target + 1; y <= target + 24; y++) {
                    org.bukkit.block.Block b = world.getBlockAt(x, y, z);
                    if (!b.getType().isAir() && (NATURAL.contains(b.getType())
                            || b.getType().name().endsWith("_LEAVES") || b.getType().name().endsWith("_LOG"))) {
                        b.setType(Material.AIR);
                    }
                }
                org.bukkit.block.Block top = world.getBlockAt(x, target, z);
                if (top.getType().isAir() || top.isLiquid()) {
                    top.setType(Material.GRASS_BLOCK);
                }
                sealBelow(world, x, target, z);   // 메운 자리 밑도 단단해야 한다
            }
        }
    }

    /** 자연 지면 — 사람이 지은 것을 <b>세지 않는다</b>. 없으면 최저값 */
    private static int naturalGround(World world, int x, int z, int from) {
        for (int y = from; y >= from - 80; y--) {
            Material m = world.getBlockAt(x, y, z).getType();
            if (NATURAL.contains(m) || (m.isSolid() && m.name().endsWith("_ORE"))) {
                return y;
            }
        }
        return from - 80;
    }

    /**
     * 지역 하나를 짓는다. 원형은 세력이 고른다.
     *
     * @return 등록할 구역 (없으면 빈 목록 — 원형이 없는 세력은 아직 못 짓는다)
     */
    static List<Zone> build(World world, WorldMap.Place place, int cx, int cy, int cz) {
        if ("noklim".equals(place.faction())) {
            return stockade(world, place, cx, cy, cz);
        }
        if (SECTS.contains(place.faction())) {
            return sect(world, place, cx, cy, cz);
        }
        return List.of();
    }

    /** 문파 원형을 쓰는 세력 — 구파일방·오대세가는 <b>같은 문법</b>을 쓴다 (산문 → 계단 → 문전 → 본전) */
    private static final java.util.Set<String> SECTS = java.util.Set.of(
            "hwasan", "gupailbang", "jongnam", "sorimsa", "mudang", "gonryun",
            "jeomchang", "cheongseong", "ami", "haenam", "gaebang");

    /** 원형을 가진 세력인가 — 명령이 미리 물어 "아직 못 짓는다"고 말할 수 있게 */
    static boolean canBuild(WorldMap.Place place) {
        return "noklim".equals(place.faction()) || SECTS.contains(place.faction());
    }

    // ══════════════════════════════════════════════════════════════════
    //  산채(寨) — 목책 · 채문 · 망루 · 통나무 막사 · 마당
    // ══════════════════════════════════════════════════════════════════

    /**
     * 산채의 문법 — 청하현의 문법과 다른 점이 곧 도적의 성격이다.
     *
     * <ul>
     *   <li><b>담이 아니라 목책</b> — 돌을 쌓지 않는다. 통나무를 박고 끝을 뾰족하게 깎는다(계단 블록).
     *       버리고 떠날 집에 석공을 부르지 않는다.</li>
     *   <li><b>기와가 없다</b> — 지붕은 짚(건초)이다. 청하현의 수묵 규칙은 여기서도 산다: 채색은 깃발뿐.</li>
     *   <li><b>마당이 중심</b> — 관아는 정청이 중심이고, 산채는 <b>모닥불</b>이 중심이다.
     *       나눠 먹는 자리가 그 집의 정체다.</li>
     *   <li><b>망루 둘</b> — 도적은 지키는 자가 아니라 <b>보는 자</b>다. 관군이 오는 것을 먼저 봐야 산다.</li>
     * </ul>
     */
    private static List<Zone> stockade(World world, WorldMap.Place place, int cx, int cy, int cz) {
        clearAbove(world, cx, cy, cz);
        terrace(world, cx, cy, cz);

        palisade(world, cx, cy, cz);
        gate(world, cx, cy, cz);

        // v2 — 조감이 잡아낸 것: 막사 넷이 마당을 다 먹어 모닥불이 묻혔고, 두목 막사가 채문 앞을 막았다.
        //   산채의 중심은 **마당**이다 (나눠 먹는 자리). 집은 벽을 등지고 물러서고, 가운데를 비운다.
        //   두목 막사는 **문 맞은편 북쪽** — 마당 건너로 들어오는 자를 본다 (문 앞을 막는 게 아니라).
        barrack(world, cx - 19, cy, cz - 4, 9, 7, BlockFace.EAST);    // 서 막사
        barrack(world, cx + 11, cy, cz - 4, 9, 7, BlockFace.WEST);    // 동 막사
        chiefHall(world, cx - 6, cy, cz - 19);                        // 두목 막사 (북) — 13x9
        watchtower(world, cx - 15, cy, cz - 13);
        watchtower(world, cx + 14, cy, cz - 13);

        yard(world, cx, cy, cz);
        feather(world, cx, cy, cz, R + 1, R + 22);   // 목책 밖 22칸 — 산으로 스며든다 (경사가 완만해야 스민다)
        return List.of(new Zone(place.name(), "녹림 — 목책과 통나무", world.getName(),
                cx - R - 2, cy - 4, cz - R - 2, cx + R + 2, cy + 14, cz + R + 2));
    }

    /**
     * 부지를 비운다 — 나무·풀·눈, <b>그리고 지난 조성이 남긴 것</b>.
     *
     * <p>재조성은 덮어쓰기다. 나무만 걷고 집을 안 걷으면 옛 지붕이 새 마당 위에 떠 있는다
     * (조감이 그것을 잡았다 — 채문 앞에 유령 지붕이 남아 있었다). 담장 안(반경 R)은 전부 걷는다.
     * 산은 그대로 둔다 — 걷는 것은 <b>지면 위</b>뿐이다.
     */
    private static void clearAbove(World world, int cx, int cy, int cz) {
        for (int x = cx - R - 2; x <= cx + R + 2; x++) {
            for (int z = cz - R - 2; z <= cz + R + 2; z++) {
                boolean inside = dist(x - cx, z - cz) <= R + 1;
                for (int y = cy + 1; y <= cy + 20; y++) {
                    Material m = world.getBlockAt(x, y, z).getType();
                    if (m.isAir()) {
                        continue;
                    }
                    boolean natural = m.name().endsWith("_LOG") || m.name().endsWith("_LEAVES")
                            || m == Material.SNOW || m == Material.GRASS_BLOCK
                            || m.name().endsWith("_GRASS") || m.name().endsWith("_FERN")
                            || m == Material.DEAD_BUSH || m.name().endsWith("_FLOWER");
                    if (inside || natural) {
                        world.getBlockAt(x, y, z).setType(Material.AIR);   // 담장 안은 전부, 밖은 초목만
                    }
                }
            }
        }
    }

    /**
     * 단(段) — 산비탈에 앉히는 평평한 바닥.
     *
     * <p>깎아 평지로 만들면 산채가 아니라 마을이 된다. 그래서 <b>바닥만 고르고 밑은 받친다</b>:
     * 낮은 자리는 통나무 기둥으로 받쳐 마루가 뜨고(고상식), 높은 자리는 그만큼 깎는다.
     * 산채가 비탈에 걸터앉은 것이 눈에 보여야 한다.
     */
    private static void terrace(World world, int cx, int cy, int cz) {
        for (int x = cx - R; x <= cx + R; x++) {
            for (int z = cz - R; z <= cz + R; z++) {
                if (dist(x - cx, z - cz) > R) {
                    continue;
                }
                for (int y = cy + 1; y <= cy + 6; y++) {   // 바닥 위를 비운다 (깎기)
                    world.getBlockAt(x, y, z).setType(Material.AIR);
                }
                Material floor = Math.floorMod(x * 7 + z * 3, 11) == 0
                        ? Material.COARSE_DIRT : Material.DIRT_PATH;   // 다져진 흙 마당
                world.getBlockAt(x, cy, z).setType(floor);
                sealBelow(world, x, cy, z);   // 밑이 비면 껍데기다 (환경 검수: 바닥 공동 13%)
            }
        }
    }

    /** 목책 — 통나무 기둥 3단 + 뾰족한 끝(계단). 12칸마다 버팀목이 비스듬히 선다 */
    private static void palisade(World world, int cx, int cy, int cz) {
        for (int x = cx - R - 2; x <= cx + R + 2; x++) {
            for (int z = cz - R - 2; z <= cz + R + 2; z++) {
                double d = dist(x - cx, z - cz);
                double r = ringR(cx, cz, x - cx, z - cz);
                if (d < r - 0.6 || d > r + 0.6) {
                    continue;
                }
                if (isGateSpan(x - cx, z - cz)) {
                    continue;   // 채문 자리 — 목책이 열린다
                }
                // v3 — 목책이 낮아 조감에서 '흙 테두리'로 읽혔다. 사람 키 두 배(5단)로 세운다:
                //   넘을 수 없어야 담이다. 안쪽에는 순찰 마루 — 도적은 담 위에서 산길을 본다.
                for (int y = cy + 1; y <= cy + 5; y++) {
                    world.getBlockAt(x, y, z).setType(Material.SPRUCE_LOG);
                }
                int ix = x + inward(x - cx);
                int iz = z + inward(z - cz);
                if (world.getBlockAt(ix, cy + 4, iz).getType().isAir()) {
                    world.getBlockAt(ix, cy + 4, iz).setType(Material.SPRUCE_PLANKS);   // 순찰 마루
                }
                // 끝을 깎는다 — 뾰족한 말뚝. 방향은 바깥쪽 (계단의 코가 밖을 본다)
                BlockFace out = Math.abs(x - cx) > Math.abs(z - cz)
                        ? (x > cx ? BlockFace.EAST : BlockFace.WEST)
                        : (z > cz ? BlockFace.SOUTH : BlockFace.NORTH);
                stair(world, x, cy + 6, z, Material.SPRUCE_STAIRS, out, true);   // 뾰족한 말뚝 끝
            }
        }
    }

    /** 채문(寨門) — 남쪽. 통나무 문루 + 해골 장식은 없다(허세가 아니라 실용) + 문 앞 횃불 */
    private static void gate(World world, int cx, int cy, int cz) {
        int gz = cz + R;
        for (int x = cx - 2; x <= cx + 2; x++) {   // 문루 상단 — 통나무 들보
            world.getBlockAt(x, cy + 5, gz).setType(Material.SPRUCE_LOG);
            world.getBlockAt(x, cy + 6, gz).setType(Material.SPRUCE_SLAB);
        }
        for (int y = cy + 1; y <= cy + 4; y++) {   // 문설주
            world.getBlockAt(cx - 3, y, gz).setType(Material.SPRUCE_LOG);
            world.getBlockAt(cx + 3, y, gz).setType(Material.SPRUCE_LOG);
        }
        for (int x = cx - 2; x <= cx + 2; x++) {   // 문짝 자리는 비운다 — 도적은 문을 잠그지 않는다
            for (int y = cy + 1; y <= cy + 4; y++) {
                world.getBlockAt(x, y, gz).setType(Material.AIR);
            }
        }
        wallTorch(world, cx - 3, cy + 3, gz - 1, BlockFace.SOUTH);
        wallTorch(world, cx + 3, cy + 3, gz - 1, BlockFace.SOUTH);
        // 문 밖 길 — 산길이 여기로 온다 (걸어 올라오는 자의 마지막 스무 칸)
        for (int z = gz + 1; z <= gz + 20; z++) {
            for (int x = cx - 1; x <= cx + 1; x++) {
                int y = groundAt(world, x, z, cy);
                world.getBlockAt(x, y, z).setType(Material.DIRT_PATH);
            }
        }
    }

    /** 망루 — 5칸 통나무 기둥 + 사다리 + 상단 널마루 + 횃불. 도적은 지키는 자가 아니라 보는 자다 */
    private static void watchtower(World world, int x0, int cy, int z0) {
        for (int y = cy + 1; y <= cy + 7; y++) {
            world.getBlockAt(x0, y, z0).setType(Material.SPRUCE_LOG);
            world.getBlockAt(x0 + 1, y, z0).setType(Material.SPRUCE_LOG);
            world.getBlockAt(x0, y, z0 + 1).setType(Material.SPRUCE_LOG);
            world.getBlockAt(x0 + 1, y, z0 + 1).setType(Material.SPRUCE_LOG);
        }
        for (int x = x0 - 1; x <= x0 + 2; x++) {   // 상단 마루 4x4
            for (int z = z0 - 1; z <= z0 + 2; z++) {
                world.getBlockAt(x, cy + 8, z).setType(Material.SPRUCE_PLANKS);
                if (x == x0 - 1 || x == x0 + 2 || z == z0 - 1 || z == z0 + 2) {
                    world.getBlockAt(x, cy + 9, z).setType(Material.SPRUCE_FENCE);   // 난간
                }
            }
        }
        world.getBlockAt(x0, cy + 8, z0).setType(Material.AIR);   // 오르는 구멍
        for (int y = cy + 1; y <= cy + 8; y++) {                  // 사다리
            ladder(world, x0 - 1, y, z0, BlockFace.EAST);
        }
        world.getBlockAt(x0, cy + 10, z0).setType(Material.TORCH);   // 망루 불 — 밤의 산채는 이걸로 읽힌다
    }

    /** 통나무 막사 — 널벽·짚지붕·2층 침상. 살림은 얇다 (tier: poor) */
    private static void barrack(World world, int x0, int cy, int z0, int w, int d, BlockFace door) {
        int x1 = x0 + w - 1;
        int z1 = z0 + d - 1;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                world.getBlockAt(x, cy, z).setType(Material.SPRUCE_PLANKS);   // 마루 (땅에서 뜬다)
                boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                if (!edge) {
                    continue;
                }
                for (int y = cy + 1; y <= cy + 3; y++) {
                    boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                    world.getBlockAt(x, y, z).setType(corner ? Material.SPRUCE_LOG : Material.SPRUCE_PLANKS);
                }
            }
        }
        // 지붕 — **초가**. 청하현 빈촌이 쓴 어휘 그대로다(자재는 세계의 언어다):
        //   짚으로 통째 덮으면 노란 덩어리가 되고 수묵이 깨진다. 초가는 "짚으로 덮은 지붕"이 아니라
        //   **너와 지붕에 짚을 얹은 것**이다 — 경사면의 계단만 좌표 해시로 점치환한다.
        //   도적의 초가는 더 성글다: 청하현 25% 에 비해 40% (덜 여미고 산다).
        int peak = Math.min(w, d) / 2;
        for (int i = 0; i <= peak; i++) {
            int y = cy + 3 + i;
            for (int x = x0 - 1 + i; x <= x1 + 1 - i; x++) {
                thatchStair(world, x, y, z0 - 1 + i, BlockFace.NORTH);
                thatchStair(world, x, y, z1 + 1 - i, BlockFace.SOUTH);
            }
            for (int z = z0 + i; z <= z1 - i; z++) {
                thatchStair(world, x0 - 1 + i, y, z, BlockFace.WEST);
                thatchStair(world, x1 + 1 - i, y, z, BlockFace.EAST);
            }
        }
        for (int x = x0 - 1 + peak; x <= x1 + 1 - peak; x++) {   // 용마루 — 짚 이엉을 눌러 얹은 마루대
            put(world, x, cy + 4 + peak, (z0 + z1) / 2, Material.SPRUCE_SLAB);
        }
        // 문 — 마당 쪽. 그리고 안에는 잠자리(짚)와 궤 하나. 그게 전부다
        int dx = door == BlockFace.EAST ? x1 : door == BlockFace.WEST ? x0 : (x0 + x1) / 2;
        int dz = door == BlockFace.SOUTH ? z1 : door == BlockFace.NORTH ? z0 : (z0 + z1) / 2;
        world.getBlockAt(dx, cy + 1, dz).setType(Material.AIR);
        world.getBlockAt(dx, cy + 2, dz).setType(Material.AIR);
        for (int x = x0 + 1; x <= x1 - 1; x += 2) {
            world.getBlockAt(x, cy + 1, z0 + 1).setType(Material.HAY_BLOCK);   // 짚 잠자리
        }
        world.getBlockAt(x1 - 1, cy + 1, z1 - 1).setType(Material.BARREL);
        lantern(world, (x0 + x1) / 2, cy + 3, (z0 + z1) / 2);   // 대들보 등 하나
    }

    /**
     * 두목 막사 — 큰 막사 + <b>노획 병기 시렁</b>(1.21.11 선반).
     *
     * <p>도적이 무엇으로 먹고사는지는 벽에 걸려 있다: 훔친 병기. 그중엔 표국의 것도 있다.
     * 등급은 씨앗(좌표 해시)이 정한다 — 같은 산채면 같은 노획물이다.
     */
    private static void chiefHall(World world, int x0, int cy, int z0) {
        barrack(world, x0, cy, z0, 13, 9, BlockFace.SOUTH);
        long seed = Math.floorMod(31L * x0 + z0, 1_000_003L);
        int bx = x0 + 1;
        int bz = z0;   // 북벽 안쪽 — 들어오면 정면에 걸린다
        shelf(world, bx + 3, cy + 2, bz, BlockFace.SOUTH,
                Weapons.makeSeeded(Weapons.Series.도, Weapons.Grade.범철, seed),
                Weapons.makeSeeded(Weapons.Series.검, Weapons.Grade.정련, seed + 1),   // 표사에게서 뺏은 것
                Weapons.makeSeeded(Weapons.Series.창, Weapons.Grade.범철, seed + 2));
        shelf(world, bx + 8, cy + 2, bz, BlockFace.SOUTH,
                Weapons.makeSeeded(Weapons.Series.부, Weapons.Grade.범철, seed + 3),
                null,                                                                   // 팔아넘긴 자리
                Weapons.makeSeeded(Weapons.Series.단검, Weapons.Grade.범철, seed + 4));
        world.getBlockAt(x0 + 6, cy + 1, z0 + 4).setType(Material.CHEST);       // 노획 궤
        world.getBlockAt(x0 + 7, cy + 1, z0 + 4).setType(Material.BARREL);
        lantern(world, x0 + 6, cy + 3, z0 + 2);
    }

    /** 마당 — 모닥불이 중심이다. 나눠 먹는 자리가 이 집의 정체다 */
    private static void yard(World world, int cx, int cy, int cz) {
        // 채문 → 마당 동선 — 들어오는 자가 곧장 모닥불을 본다 (아무도 그 앞을 막지 않는다)
        for (int z = cz + 6; z <= cz + R - 1; z++) {
            for (int x = cx - 2; x <= cx + 2; x++) {
                for (int y = cy + 1; y <= cy + 4; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR);
                }
                world.getBlockAt(x, cy, z).setType(Material.DIRT_PATH);
            }
        }
        world.getBlockAt(cx, cy + 1, cz).setType(Material.CAMPFIRE);
        for (int i = 0; i < 8; i++) {   // 모닥불 둘레 통나무 걸상 (앉는 자리 = 사람이 산다는 증거)
            double a = Math.PI * i / 4.0;
            int x = cx + (int) Math.round(Math.cos(a) * 3);
            int z = cz + (int) Math.round(Math.sin(a) * 3);
            if (world.getBlockAt(x, cy + 1, z).getType().isAir()) {
                world.getBlockAt(x, cy + 1, z).setType(Material.SPRUCE_SLAB);
            }
        }
        // 고기 걸이 · 술통 · 노획물 수레 — 산채의 살림 (얇게)
        for (int x = cx + 5; x <= cx + 7; x++) {
            world.getBlockAt(x, cy + 4, cz - 2).setType(Material.SPRUCE_FENCE);
            world.getBlockAt(x, cy + 3, cz - 2).setType(Material.IRON_CHAIN);
        }
        world.getBlockAt(cx + 6, cy + 2, cz - 2).setType(Material.HAY_BLOCK);   // 걸린 것 (말린 고기 대용)
        world.getBlockAt(cx - 6, cy + 1, cz - 2).setType(Material.BARREL);
        world.getBlockAt(cx - 7, cy + 1, cz - 2).setType(Material.BARREL);
        world.getBlockAt(cx - 6, cy + 1, cz + 2).setType(Material.CAULDRON);    // 국솥
        // 마당 살림 — 훈련 말뚝 셋 · 장작더미 · 짐수레. 조감의 마당이 휑했다:
        //   사람이 사는 마당에는 **하던 일의 흔적**이 남는다 (도적은 훈련하고, 패고, 실어 나른다).
        for (int i = 0; i < 3; i++) {
            int px = cx - 9 + i * 3;
            int pz = cz + 8;
            world.getBlockAt(px, cy + 1, pz).setType(Material.SPRUCE_FENCE);   // 훈련 말뚝
            world.getBlockAt(px, cy + 2, pz).setType(Material.SPRUCE_FENCE);
            stair(world, px, cy + 3, pz, Material.SPRUCE_STAIRS, BlockFace.SOUTH, false);
        }
        for (int x = cx + 6; x <= cx + 8; x++) {   // 장작더미 — 패다 만 것
            for (int z = cz + 6; z <= cz + 7; z++) {
                world.getBlockAt(x, cy + 1, z).setType(Material.SPRUCE_LOG);
                if (Math.floorMod(x * 5 + z * 3, 3) == 0) {
                    world.getBlockAt(x, cy + 2, z).setType(Material.SPRUCE_LOG);
                }
            }
        }
        world.getBlockAt(cx + 9, cy + 1, cz + 7).setType(Material.SPRUCE_TRAPDOOR);   // 도끼 박힌 그루터기 대용
        for (int x = cx - 10; x <= cx - 8; x++) {   // 짐수레 — 노획물을 싣는다
            world.getBlockAt(x, cy + 1, cz - 8).setType(Material.SPRUCE_SLAB);
            world.getBlockAt(x, cy + 2, cz - 8).setType(Math.floorMod(x, 2) == 0
                    ? Material.BARREL : Material.CHEST);
        }
        // 마당의 불 — 지역 검수가 "길 위 암흑 84%"라고 했다. 모닥불 하나로는 채가 안 밝다.
        //   도적도 밤에 걸어 다닌다 (망보러 나가고, 오줌 누러 나온다).
        for (int dx = -14; dx <= 14; dx += 7) {
            for (int dz = -14; dz <= 14; dz += 7) {
                if (Math.abs(dx) + Math.abs(dz) < 5) {
                    continue;   // 모닥불 자리는 비운다
                }
                lanternPost(world, cx + dx, cy, cz + dz);
            }
        }
        // 채기(寨旗) — 채색은 여기뿐이다 (수묵 규칙: 깃발·불빛에만 색을 허락한다)
        for (int y = cy + 1; y <= cy + 5; y++) {
            world.getBlockAt(cx + 9, y, cz + 9).setType(Material.SPRUCE_FENCE);
        }
        world.getBlockAt(cx + 9, cy + 6, cz + 9).setType(Material.GREEN_WOOL);   // 녹림(綠林) — 푸른 숲
    }

    // ══════════════════════════════════════════════════════════════════
    //  도관(道觀) — 산문 · 천 계단 · 문전 · 본전 · 연무장
    // ══════════════════════════════════════════════════════════════════

    /**
     * 문파의 문법 — <b>오르는 길이 시험이다</b> (등록부: "산문 → 계단 → 문전. 잡역은 문전에서 시작한다").
     *
     * <p>산채는 평평한 한 켜였다. 문파는 <b>두 켜</b>다: 아래 문전(門前)과 위 본전(本殿).
     * 그 사이를 계단이 잇는다 — 걸어 올라가는 동안 사람이 무엇을 보게 되는가가 이 건축의 전부다.
     * 문전에서 올려다보면 본전이 하늘에 걸려 있고, 본전에서 내려다보면 세상이 발밑에 있다.
     *
     * <p>자재는 청하현 관아의 어휘다 — <b>회벽(white_terracotta)과 검은 기와(deepslate_tiles)</b>.
     * 도적의 통나무와 다른 것이 곧 위계다. 채색은 <b>매화</b>뿐 (등록부가 cherry_grove 를 적어 두었다).
     */
    private static List<Zone> sect(World world, WorldMap.Place place, int cx, int cy, int cz) {
        // v3 — 조감이 잡은 것: "천 계단"이 **아홉 칸**이었다. 본전 단을 지면 +9 로 잡았으니
        //   봉우리랄 것도 없는 둔덕이 섰고, 산문·계단은 그 둔덕의 발치에서 뭉개졌다.
        //   오르는 길이 시험이라면 **올라야 한다**: 본전은 지면에서 36켜 위, 계단은 45칸을 오른다.
        // 발치 높이를 **한 열에서** 재다가 물웅덩이 바닥(y53)을 지면으로 읽었다 — 계단이 못에서 시작해
        // 정상에 닿지 못했다(지역 검수의 도달성 검사가 잡았다: 오른 최고 y73 · 목표 y92).
        // 지면은 지도가 이미 계산해 뒀다(site.groundY) — 그 값을 쓴다. 한 열은 거짓말을 한다.
        int lower = cy;                 // 문전 — 산의 발치
        int upper = cy + 36;            // 본전 — 서른여섯 켜 위 (오르는 길이 시험이다)

        clearSect(world, cx, lower, cz);
        // 화산파를 세웠더니 **사막**에 섰다 — 시드 8888 의 그 일대(4km)에 험산이 없다.
        // 실지리 1:1 좌표는 못 옮긴다(지도의 뼈대다). 그러면 답은 하나 — **없으면 세운다.**
        shapeTerrain(world, place, cx, upper, cz);
        pad(world, cx, upper, cz - 12, 27, 21, Material.POLISHED_ANDESITE);   // 본전 단 (봉우리 정상)
        pad(world, cx, lower, cz + FOOT_Z, 17, 13, Material.POLISHED_ANDESITE);   // 문전 마당 (발치)

        mountainGate(world, cx, lower, cz + FOOT_Z + 7);              // 산문(山門) — 패방. 여기서부터 문파의 땅
        thousandSteps(world, cx, lower, cz + FOOT_Z - 7, upper, cz - 1);   // 계단 — 발치에서 정상으로
        mainHall(world, cx - 7, upper, cz - 10, 15, 13);               // 본전 15x13 — 회벽·검은 기와
        trainingGround(world, cx, upper, cz + 4);                      // 연무장 — 오르면 먼저 이것이 보인다
        // 정상 마당·문전의 석등 — 계단만 밝혀서는 밤에 못 다닌다 (지역 검수: 길 위 암흑 67%)
        for (int dx = -12; dx <= 12; dx += 6) {
            for (int dz = -10; dz <= 8; dz += 6) {
                if (Math.abs(dx) <= 8 && dz >= -11 && dz <= 3) {
                    continue;   // 본전 자리 — 등이 벽 속에 박힌다 (밝은 벽은 밝은 길이 아니다)
                }
                lanternPost(world, cx + dx, upper, cz + dz);
            }
        }
        for (int dx = -7; dx <= 7; dx += 4) {
            for (int dz = -5; dz <= 5; dz += 5) {
                lanternPost(world, cx + dx, lower, cz + FOOT_Z + dz);
            }
        }
        plumTrees(world, cx, upper, cz);                               // 매화 — 채색은 여기뿐이다
        feather(world, cx, lower, cz + FOOT_Z, 10, 22);                // 문전 마당 밖 — 들로 스며든다

        return List.of(new Zone(place.name(), sectSubtitle(place), world.getName(),
                cx - 40, lower - 8, cz - 44, cx + 40, upper + 18, cz + FOOT_Z + 14));
    }

    /**
     * 산문·문전이 서는 자리 — 봉우리 중심(cz-8)에서 남쪽으로 이만큼.
     * <b>산의 발치 바깥이어야 한다</b>: 봉우리 반경 60 + 중심 오프셋 8 = 68. 46 은 산 속이었고,
     * 그래서 문전이 산에 파묻혀 계단이 시작할 자리를 잃었다.
     */
    private static final int FOOT_Z = 74;

    private static String sectSubtitle(WorldMap.Place place) {
        return "rich".equals(place.tier()) ? "도관 — 산문에서 본전까지 천 계단"
                : "산문 — 오르는 길이 시험이다";
    }

    /**
     * 지형 빚기 — <b>기본 세계를 쓰되, 없으면 만든다</b> (사용자 판정: "없으면 산을 생성하든 필드를 생성하든").
     *
     * <p>등록부는 장소마다 지형을 요구한다(험산·산·평지·고원·분지·강). 바닐라 생성은 실지리를 모른다 —
     * 화산파의 좌표에 사막을 놓았다. 좌표를 옮기면 지도(실지리 1:1)가 무너지고, 그냥 지으면
     * 사막 한복판의 도관이 된다. <b>세 번째 답: 땅을 요구에 맞춘다.</b>
     *
     * <p>이미 맞는 땅은 건드리지 않는다 — 자연이 준 것이 언제나 더 낫다. 빚는 것은 <b>모자랄 때뿐</b>이다.
     */
    private static void shapeTerrain(World world, WorldMap.Place place, int cx, int topY, int cz) {
        String terrain = place.terrain() == null ? "" : place.terrain();
        // 지면은 **자연 자재로만** 잰다. getHighestBlockYAt 은 지난 조성이 남긴 기와를 지면으로 세어
        // "이미 충분히 높다"고 거짓말했다 — 그래서 산이 서지 않았다 (조감이 사막 위 도관을 두 번 보여줬다).
        int natural = naturalGround(world, cx, cz, topY + 40);
        switch (terrain) {
            case "험산", "산" -> {
                if (natural < topY - 2) {           // 이미 봉우리 위면 그대로 (자연이 준 산)
                    // 봉우리의 중심은 **본전**이지 마당이 아니다. 남쪽(문전·계단)으로 비탈이 흘러내려야
                    // 계단이 비탈을 타고 오른다 — 산을 마당 한가운데 세웠더니 문전이 파묻혀 분화구가 됐다.
                    raiseMassif(world, cx, topY, cz - 8, 60, 18);
                }
            }
            case "고원" -> {
                if (natural < topY - 2) {
                    raiseMassif(world, cx, topY, cz - 8, 66, 26);   // 대지(臺地) — 같은 손, 정상이 넓다
                }
            }
            case "평지", "분지" -> levelField(world, cx, topY, cz);
            default -> {
                // 강·폐허·밀림 — 아직 빚지 않는다. 자연이 준 대로 앉힌다 (원형이 늘 때 함께 온다)
            }
        }
    }

    /**
     * 들 — 평지를 요구하는데 땅이 울퉁불퉁하면 고른다. 벼랑을 깎는 게 아니라 <b>발치를 메운다</b>:
     * 낮은 데를 흙으로 채우고 높은 데만 깎는다 (파낸 자국은 사람이 산 흔적이 아니라 상처다).
     */
    private static void levelField(World world, int cx, int topY, int cz) {
        int radius = 40;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                double d = dist(x - cx, z - cz);
                if (d > radius) {
                    continue;
                }
                int edge = Math.floorMod(x * 5 + z * 3, 3);      // 가장자리는 자연으로 풀어 준다
                int target = topY - (d > radius - 6 ? edge : 0);
                for (int y = target + 1; y <= target + 8; y++) {
                    if (!world.getBlockAt(x, y, z).getType().isAir()) {
                        world.getBlockAt(x, y, z).setType(Material.AIR);
                    }
                }
                for (int y = target; y >= target - 6; y--) {
                    if (!world.getBlockAt(x, y, z).getType().isAir()
                            && !world.getBlockAt(x, y, z).isLiquid()) {
                        break;
                    }
                    world.getBlockAt(x, y, z).setType(y == target ? Material.GRASS_BLOCK : Material.DIRT);
                }
            }
        }
    }

    /**
     * 봉우리 — <b>땅이 요구를 못 맞추면 땅을 빚는다</b>.
     *
     * <p>등록부는 화산파에 험산(jagged_peaks)을 요구하는데, 시드가 그 자리에 사막을 놓았다.
     * 좌표를 옮기면(실지리 1:1) 지도가 무너지고, 그냥 지으면 사막 한복판의 도관이 된다.
     * 그래서 <b>산을 세운다</b> — 본전 단을 받치는 암반과 그 아래로 흘러내리는 비탈.
     *
     * <p>모양의 규칙: 완만한 원뿔이 아니라 <b>층진 벼랑</b>이다 (험산은 깎아지른 것이 성격이다).
     * 높이는 방위·거리의 좌표 해시로 흔들려 등고선이 반듯해지지 않는다.
     */
    private static void raiseMassif(World world, int cx, int topY, int cz, int radius, int flatR) {
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                double d = dist(x - cx, z - cz);
                if (d > radius) {
                    continue;
                }
                // 정상 평탄(flatR) → 비탈 → 발치.
                //   v2 — 구판은 **탁상(메사)** 이 됐다: 옆이 수직 벼랑이고 등고가 반듯한 원이라 산이 아니라
                //   기단으로 읽혔다. 봉우리는 ① 발치로 갈수록 완만하고(비탈이 오목한 게 아니라 볼록하다),
                //   ② 능선이 방위마다 다르고(좌표 해시 ±5), ③ 발치가 자연 지면에 스며든다.
                double t = Math.max(0, (d - flatR) / (double) (radius - flatR));   // 0 = 정상, 1 = 발치
                int crest = topY + 1;
                // v3 — 조감이 **등고선**을 보여줬다: 동심원 층이 나이테처럼 뚜렷해 산이 아니라 지형도가 됐다.
                //   원인은 ① 높이가 거리 d 의 함수뿐이라 등고가 완전한 원이고, ② 두 칸 단이 그 원을 그렸다.
                //   답: 능선을 **방위의 함수**로 흔들어 등고를 깨고(골과 날), 단은 자리마다 어긋나게 놓는다.
                double ang = Math.atan2(z - cz, x - cx);
                double lobes = Math.sin(ang * 3 + 0.7) * 5 + Math.sin(ang * 5 - 1.3) * 3;   // 산줄기 셋·다섯
                int ridge = Math.floorMod(x * 11 + z * 7, 7) - 3;           // 바위의 요철
                double dEff = d - lobes * (1 - t) - lobes * 0.4;            // 골과 날 — 등고가 원이 아니다
                double tEff = Math.max(0, Math.min(1, (dEff - flatR) / (double) (radius - flatR)));
                int drop = (int) Math.round(Math.pow(tEff, 1.4) * 48);      // 볼록한 비탈 (아래로 갈수록 완만)
                int y = crest - drop + (int) Math.round(ridge * (1 - tEff) * 0.8);
                int stepPhase = Math.floorMod(x * 3 + z * 5, 3);            // 단의 위상을 자리마다 어긋낸다
                y = y - Math.floorMod(y + stepPhase, 2);
                int ground = naturalGround(world, x, z, topY + 40);
                if (y <= ground) {
                    continue;   // 발치가 자연 지면에 닿았다 — 그 아래는 산의 몫이다 (파내지 않는다)
                }
                for (int yy = y; yy >= y - 40; yy--) {
                    Material here = world.getBlockAt(x, yy, z).getType();
                    if (!here.isAir() && !here.isSolid()) {
                        world.getBlockAt(x, yy, z).setType(Material.STONE);
                        continue;
                    }
                    if (here.isAir()) {
                        world.getBlockAt(x, yy, z).setType(
                                Math.floorMod(x * 3 + yy * 5 + z, 9) == 0 ? Material.ANDESITE
                                        : yy > y - 3 ? Material.STONE : Material.DEEPSLATE);
                    } else {
                        break;   // 자연 지면에 닿았다 — 그 아래는 산의 몫이다
                    }
                }
                for (int yy = y + 1; yy <= topY + 14; yy++) {   // 봉우리 위 허공을 비운다
                    if (!world.getBlockAt(x, yy, z).getType().isAir()) {
                        world.getBlockAt(x, yy, z).setType(Material.AIR);
                    }
                }
            }
        }
    }

    /** 부지를 비운다 — 두 켜 모두. 산은 남기고 그 위의 것만 걷는다 */
    private static void clearSect(World world, int cx, int cy, int cz) {
        for (int x = cx - 24; x <= cx + 24; x++) {
            for (int z = cz - 30; z <= cz + FOOT_Z + 12; z++) {
                for (int y = cy + 1; y <= cy + 50; y++) {
                    Material m = world.getBlockAt(x, y, z).getType();
                    if (m.isAir()) {
                        continue;
                    }
                    boolean natural = NATURAL.contains(m) || m.name().endsWith("_ORE");
                    if (!natural) {
                        world.getBlockAt(x, y, z).setType(Material.AIR);   // 지난 조성·초목은 걷는다
                    }
                }
            }
        }
    }

    /** 단(段) — 돌로 다진 평평한 바닥 + 그 밑을 받치는 축대 (험산에 집을 앉히는 법) */
    private static void pad(World world, int cx, int y, int cz, int w, int d, Material floor) {
        for (int x = cx - w / 2; x <= cx + w / 2; x++) {
            for (int z = cz - d / 2; z <= cz + d / 2; z++) {
                world.getBlockAt(x, y, z).setType(floor);
                for (int yy = y + 1; yy <= y + 12; yy++) {
                    world.getBlockAt(x, yy, z).setType(Material.AIR);
                }
                boolean edge = x == cx - w / 2 || x == cx + w / 2 || z == cz - d / 2 || z == cz + d / 2;
                for (int yy = y - 1; yy >= y - 14; yy--) {   // 축대 — 허공이면 돌로 받친다
                    if (!world.getBlockAt(x, yy, z).getType().isAir()
                            && !world.getBlockAt(x, yy, z).isLiquid()) {
                        break;
                    }
                    world.getBlockAt(x, yy, z).setType(edge ? Material.STONE_BRICKS : Material.COBBLESTONE);
                }
                sealBelow(world, x, y, z);   // 축대가 닿은 뒤에도 그 밑 여섯 칸은 단단해야 한다
            }
        }
    }

    /** 산문(山門) — 패방(牌坊). 돌기둥 넷 + 현판 + 검은 기와. 여기서부터 문파의 땅이다 */
    private static void mountainGate(World world, int cx, int cy, int cz) {
        for (int dx : new int[]{-4, -3, 3, 4}) {
            for (int y = cy + 1; y <= cy + 5; y++) {
                world.getBlockAt(cx + dx, y, cz).setType(Material.STONE_BRICKS);
            }
        }
        for (int x = cx - 5; x <= cx + 5; x++) {   // 들보 + 기와 처마
            world.getBlockAt(x, cy + 6, cz).setType(Material.POLISHED_ANDESITE);
            world.getBlockAt(x, cy + 7, cz).setType(Material.DEEPSLATE_TILES);
        }
        for (int x = cx - 6; x <= cx + 6; x++) {
            stair(world, x, cy + 7, cz - 1, Material.DEEPSLATE_TILE_STAIRS, BlockFace.NORTH, false);
            stair(world, x, cy + 7, cz + 1, Material.DEEPSLATE_TILE_STAIRS, BlockFace.SOUTH, false);
        }
        world.getBlockAt(cx, cy + 5, cz).setType(Material.DARK_OAK_PLANKS);   // 현판 자리
        lanternPost(world, cx - 6, cy, cz + 1);
        lanternPost(world, cx + 6, cy, cz + 1);
    }

    /** 천 계단 — 문전에서 본전으로. 한 켜씩 올라가며 폭이 좁아진다 (오를수록 좁아지는 길) */
    private static void thousandSteps(World world, int cx, int yLow, int zLow, int yHigh, int zHigh) {
        int rise = yHigh - yLow;
        int run = zLow - zHigh;
        for (int i = 0; i <= run; i++) {
            int z = zLow - i;
            int y = yLow + (int) Math.round((double) rise * i / run);
            int half = Math.max(2, 5 - i / 5);   // 폭 11 → 5 (오를수록 좁아진다)
            for (int x = cx - half; x <= cx + half; x++) {
                world.getBlockAt(x, y, z).setType(Material.POLISHED_ANDESITE);
                for (int yy = y + 1; yy <= y + 6; yy++) {
                    world.getBlockAt(x, yy, z).setType(Material.AIR);
                }
                for (int yy = y - 1; yy >= y - 12; yy--) {
                    if (!world.getBlockAt(x, yy, z).getType().isAir()) {
                        break;
                    }
                    world.getBlockAt(x, yy, z).setType(Material.COBBLESTONE);
                }
            }
            // 석등은 **길 안쪽 가장자리**에 세운다. 길 밖(바위 쪽)에 세우면 등이 바위에 박혀 빛이 길로
            //   못 나온다 — 검수는 세 사이클 내리 "암흑 47%"라고 했고, 조감으로는 등이 서 있는 것처럼 보였다.
            //   눈에 보이는 등과 밝은 길은 다른 것이다.
            if (Math.floorMod(i, 2) == 0) {
                for (int dx : new int[]{-half, half}) {
                    world.getBlockAt(cx + dx, y + 1, z).setType(Material.COBBLESTONE_WALL);
                    world.getBlockAt(cx + dx, y + 2, z).setType(Material.LANTERN);
                }
            }
        }
    }

    /** 본전(本殿) — 회벽·검은 기와. 청하현 관아의 어휘다 (도적의 통나무와 다른 것이 위계다) */
    private static void mainHall(World world, int x0, int cy, int z0, int w, int d) {
        int x1 = x0 + w - 1;
        int z1 = z0 + d - 1;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                world.getBlockAt(x, cy, z).setType(Material.POLISHED_ANDESITE);
                boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                if (!edge) {
                    continue;
                }
                boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                for (int y = cy + 1; y <= cy + 4; y++) {
                    world.getBlockAt(x, y, z).setType(corner ? Material.DARK_OAK_LOG
                            : Material.WHITE_TERRACOTTA);   // 회벽 · 기둥은 목재
                }
            }
        }
        // 문 — 남향 (계단이 남에서 온다). 삼문(三門)이 아니라 하나 (문파의 본전은 겸손하다)
        for (int x = (x0 + x1) / 2 - 1; x <= (x0 + x1) / 2 + 1; x++) {
            world.getBlockAt(x, cy + 1, z1).setType(Material.AIR);
            world.getBlockAt(x, cy + 2, z1).setType(Material.AIR);
            world.getBlockAt(x, cy + 3, z1).setType(Material.AIR);
        }
        // 지붕 — 검은 기와, 능선 수렴 (청하현 검수가 요구하는 물매)
        int peak = Math.min(w, d) / 2;
        for (int i = 0; i <= peak; i++) {
            int y = cy + 5 + i;
            for (int x = x0 - 1 + i; x <= x1 + 1 - i; x++) {
                put(world, x, y, z0 - 1 + i, Material.DEEPSLATE_TILES);
                put(world, x, y, z1 + 1 - i, Material.DEEPSLATE_TILES);
            }
            for (int z = z0 + i; z <= z1 - i; z++) {
                put(world, x0 - 1 + i, y, z, Material.DEEPSLATE_TILES);
                put(world, x1 + 1 - i, y, z, Material.DEEPSLATE_TILES);
            }
        }
        // 실내 — 제단(향로)과 시렁. 본전에 걸린 것은 노획물이 아니라 **전승의 병기**다
        int mx = (x0 + x1) / 2;
        int mz = (z0 + z1) / 2;
        world.getBlockAt(mx, cy + 1, z0 + 1).setType(Material.CAULDRON);        // 향로
        candlesAt(world, mx - 1, cy + 1, z0 + 1);
        candlesAt(world, mx + 1, cy + 1, z0 + 1);
        long seed = Math.floorMod(31L * x0 + z0, 1_000_003L);
        shelf(world, mx - 3, cy + 2, z0, BlockFace.SOUTH,
                Weapons.makeSeeded(Weapons.Series.검, Weapons.Grade.보병, seed),
                null,
                Weapons.makeSeeded(Weapons.Series.검, Weapons.Grade.정련, seed + 1));
        lantern(world, mx, cy + 4, mz);
    }

    /** 연무장 — 돌바닥 + 목인장 셋. 오르면 먼저 이것이 보인다 (문파는 무엇을 하는 집인가) */
    private static void trainingGround(World world, int cx, int cy, int cz) {
        for (int x = cx - 8; x <= cx + 8; x++) {
            for (int z = cz - 4; z <= cz + 4; z++) {
                world.getBlockAt(x, cy, z).setType(Math.floorMod(x + z, 7) == 0
                        ? Material.ANDESITE : Material.POLISHED_ANDESITE);
            }
        }
        for (int i = -1; i <= 1; i++) {   // 목인장(木人樁) — 팔이 셋 달린 나무 사람
            int px = cx + i * 6;
            world.getBlockAt(px, cy + 1, cz).setType(Material.STRIPPED_DARK_OAK_LOG);
            world.getBlockAt(px, cy + 2, cz).setType(Material.STRIPPED_DARK_OAK_LOG);
            world.getBlockAt(px, cy + 3, cz).setType(Material.STRIPPED_DARK_OAK_LOG);
            world.getBlockAt(px - 1, cy + 2, cz).setType(Material.DARK_OAK_FENCE);   // 팔
            world.getBlockAt(px + 1, cy + 2, cz).setType(Material.DARK_OAK_FENCE);
            world.getBlockAt(px, cy + 2, cz + 1).setType(Material.DARK_OAK_FENCE);
        }
    }

    /** 매화 — 채색은 여기뿐이다 (등록부가 cherry_grove 를 적어 두었다). 본전 마당 모서리에 셋 */
    private static void plumTrees(World world, int cx, int cy, int cz) {
        int[][] spots = {{-11, -6}, {11, -6}, {-11, 6}};
        for (int[] s : spots) {
            int x = cx + s[0];
            int z = cz + s[1];
            for (int y = cy + 1; y <= cy + 4; y++) {
                world.getBlockAt(x, y, z).setType(Material.CHERRY_LOG);
            }
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > 3) {
                        continue;
                    }
                    put(world, x + dx, cy + 5, z + dz, Material.CHERRY_LEAVES);
                    if (Math.abs(dx) + Math.abs(dz) <= 1) {
                        put(world, x + dx, cy + 6, z + dz, Material.CHERRY_LEAVES);
                    }
                }
            }
        }
    }

    /** 석등 — 돌기둥 + 등롱 (계단·산문의 불빛) */
    private static void lanternPost(World world, int x, int cy, int z) {
        world.getBlockAt(x, cy + 1, z).setType(Material.COBBLESTONE_WALL);
        world.getBlockAt(x, cy + 2, z).setType(Material.COBBLESTONE_WALL);
        world.getBlockAt(x, cy + 3, z).setType(Material.LANTERN);
    }

    private static void candlesAt(World world, int x, int y, int z) {
        org.bukkit.block.data.type.Candle data =
                (org.bukkit.block.data.type.Candle) Material.CANDLE.createBlockData();
        data.setCandles(2);
        data.setLit(true);
        world.getBlockAt(x, y, z).setBlockData(data);
    }

    // ─── 손 ───

    private static double dist(int dx, int dz) {
        return Math.sqrt((double) dx * dx + (double) dz * dz);
    }

    /**
     * 그 방위의 목책 반경 — <b>완전한 원은 도면이다. 도적에겐 도면이 없다.</b>
     *
     * <p>조감이 잡은 것: 반듯한 원이 산비탈에 찍혀 인공 절개지처럼 보였다. 목책은 지형과 나무 사이를
     * 비집고 서는 것이라 들쭉날쭉해야 한다. 방위각을 8등분해 좌표 해시로 ±2칸 흔든다 (결정론 유지).
     */
    private static double ringR(int cx, int cz, int dx, int dz) {
        int sector = (int) Math.floor((Math.atan2(dz, dx) + Math.PI) / (Math.PI / 6));   // 12방위
        return R - 1 + Math.floorMod(cx * 13 + cz * 7 + sector * 31, 4);   // R-1 .. R+2
    }

    private static int inward(int d) {
        return d > 0 ? -1 : d < 0 ? 1 : 0;
    }

    /** 채문 스팬 — 남쪽 정면 7칸 (문설주 포함) */
    private static boolean isGateSpan(int dx, int dz) {
        return dz > R - 2 && Math.abs(dx) <= 3;
    }

    /** 초가 한 칸 — 계단(너와) 위에 좌표 해시 40% 로 짚을 얹는다. 결정론: 같은 자리 = 같은 이엉 */
    private static void thatchStair(World world, int x, int y, int z, BlockFace facing) {
        if (!world.getBlockAt(x, y, z).getType().isAir()) {
            return;
        }
        boolean straw = Math.floorMod(x * 31 + z * 17 + y * 7, 10) < 4;
        if (straw) {
            world.getBlockAt(x, y, z).setType(Material.HAY_BLOCK);
        } else {
            stair(world, x, y, z, Material.SPRUCE_STAIRS, facing, false);
        }
    }

    private static void put(World world, int x, int y, int z, Material m) {
        if (world.getBlockAt(x, y, z).getType().isAir()) {
            world.getBlockAt(x, y, z).setType(m);
        }
    }

    /** 지면 — 그 열의 첫 단단한 블록 (산비탈이라 열마다 다르다) */
    private static int groundAt(World world, int x, int z, int cy) {
        for (int y = cy + 8; y >= cy - 8; y--) {
            if (!world.getBlockAt(x, y, z).getType().isAir()
                    && !world.getBlockAt(x, y, z).isLiquid()) {
                return y;
            }
        }
        return cy;
    }

    private static void stair(World world, int x, int y, int z, Material m, BlockFace facing, boolean top) {
        Stairs data = (Stairs) m.createBlockData();
        data.setFacing(facing);
        data.setHalf(top ? org.bukkit.block.data.Bisected.Half.TOP
                : org.bukkit.block.data.Bisected.Half.BOTTOM);
        world.getBlockAt(x, y, z).setBlockData(data);
    }

    private static void wallTorch(World world, int x, int y, int z, BlockFace facing) {
        Directional data = (Directional) Material.WALL_TORCH.createBlockData();
        data.setFacing(facing);
        world.getBlockAt(x, y, z).setBlockData(data);
    }

    private static void ladder(World world, int x, int y, int z, BlockFace facing) {
        Directional data = (Directional) Material.LADDER.createBlockData();
        data.setFacing(facing);
        world.getBlockAt(x, y, z).setBlockData(data);
    }

    private static void lantern(World world, int x, int y, int z) {
        org.bukkit.block.data.type.Lantern data =
                (org.bukkit.block.data.type.Lantern) Material.LANTERN.createBlockData();
        data.setHanging(true);
        world.getBlockAt(x, y, z).setBlockData(data);
    }

    private static void shelf(World world, int x, int y, int z, BlockFace facing,
                              org.bukkit.inventory.ItemStack... items) {
        org.bukkit.block.data.type.Shelf data =
                (org.bukkit.block.data.type.Shelf) Material.SPRUCE_SHELF.createBlockData();
        data.setFacing(facing);
        world.getBlockAt(x, y, z).setBlockData(data);
        if (world.getBlockAt(x, y, z).getState() instanceof org.bukkit.block.Shelf state) {
            for (int slot = 0; slot < Math.min(items.length, 3); slot++) {
                if (items[slot] != null) {
                    state.getSnapshotInventory().setItem(slot, items[slot]);
                }
            }
            state.update(true, false);
        }
    }

    /** 조성 전 청크 선로드 — 콘솔 조성은 부지가 언로드 상태다 (F29 의 교훈) */
    static void preload(World world, int cx, int cz, int radius) {
        for (int chunkX = (cx - radius) >> 4; chunkX <= (cx + radius) >> 4; chunkX++) {
            for (int chunkZ = (cz - radius) >> 4; chunkZ <= (cz + radius) >> 4; chunkZ++) {
                world.getChunkAt(chunkX, chunkZ).load(true);
            }
        }
    }

    /** 앵커 — 채문 앞 (여행의 도착점) */
    static Location anchor(World world, int cx, int cy, int cz) {
        return new Location(world, cx + 0.5, cy + 1, cz + R + 2.5);
    }

    static List<String> unbuildableReasons(WorldMap.Place place) {
        List<String> out = new ArrayList<>();
        if (place.faction() == null) {
            out.add("세력 미등록 — 원형을 고를 수 없다");
        } else if (!canBuild(place)) {
            out.add("원형 없음 — '" + place.faction() + "' 의 건축 원형이 아직 없다 (지금은 산채뿐)");
        }
        return out;
    }
}
