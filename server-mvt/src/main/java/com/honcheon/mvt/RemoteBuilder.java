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
     * 지역 하나를 짓는다. 원형은 세력이 고른다.
     *
     * @return 등록할 구역 (없으면 빈 목록 — 원형이 없는 세력은 아직 못 짓는다)
     */
    static List<Zone> build(World world, WorldMap.Place place, int cx, int cy, int cz) {
        if ("noklim".equals(place.faction())) {
            return stockade(world, place, cx, cy, cz);
        }
        return List.of();
    }

    /** 원형을 가진 세력인가 — 명령이 미리 물어 "아직 못 짓는다"고 말할 수 있게 */
    static boolean canBuild(WorldMap.Place place) {
        return "noklim".equals(place.faction());
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
                for (int y = cy - 1; y >= cy - 6; y--) {   // 밑을 받친다 (허공이면 기둥)
                    if (!world.getBlockAt(x, y, z).getType().isAir()
                            && !world.getBlockAt(x, y, z).isLiquid()) {
                        break;
                    }
                    world.getBlockAt(x, y, z).setType(Material.SPRUCE_LOG);
                }
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
        // 채기(寨旗) — 채색은 여기뿐이다 (수묵 규칙: 깃발·불빛에만 색을 허락한다)
        for (int y = cy + 1; y <= cy + 5; y++) {
            world.getBlockAt(cx + 9, y, cz + 9).setType(Material.SPRUCE_FENCE);
        }
        world.getBlockAt(cx + 9, cy + 6, cz + 9).setType(Material.GREEN_WOOL);   // 녹림(綠林) — 푸른 숲
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
