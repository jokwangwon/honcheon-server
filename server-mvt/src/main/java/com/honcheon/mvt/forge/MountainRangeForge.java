package com.honcheon.mvt.forge;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.EnumSet;
import java.util.Set;

/**
 * 산세 기록기 — {@link RangeField}(순수 높이장)를 블록으로 옮긴다. <b>여기는 땅이다.</b>
 *
 * <p>v5 산세 생성기(기계 ① — H-12 착수 승인)의 Bukkit 면. 설계 정본은
 * {@code docs/design/terrain_forge_v5.md} (§2.6 기록기 계약).
 *
 * <h2>계약 — TerrainForge 계약의 계승 (같은 결)</h2>
 * <ul>
 *   <li><b>빈틈없이 채운다</b> — base 부터 표면까지. superflat 밑감이라 옛 지면 = base
 *       (계약 ①-b 의 결 — 묻히는 나무도, 공동도 원리적으로 없다).</li>
 *   <li><b>사람이 지은 것은 지나친다</b> — raiseMassif 문법 (TF:1116-1119).
 *       (다만 순서가 헌법이다: 땅이 먼저 서고 건축이 오른다 — 3계층.)</li>
 *   <li><b>물을 놓지 않는다</b> — 협곡은 마른 골이다. 물은 산지 수계(기계 ②)의 몫.</li>
 *   <li><b>식생을 놓지 않는다</b> — 나무·풀은 식생층(기계 ⑨)의 몫.</li>
 *   <li><b>결정론</b> — 난수 0. 높이·자재 전부 좌표의 순수 함수 → 재실행 = 같은 땅 (멱등).</li>
 * </ul>
 *
 * <h2>부르는 법 (배선은 조율자 — terrain_forge_v5.md §5)</h2>
 * <pre>
 *   RangeSpec spec = RangeSpec.hwasan(px, pz, baseY);
 *   MountainRangeForge.forge(world, spec);                       // 한 번에 (작은 시험용)
 *   // 또는 타일로 쪼개 스케줄러·TickBudget 아래서:
 *   RangeField field = new RangeField(spec);
 *   MountainRangeForge.forgeTile(world, spec, field, x0, z0, x1, z1);
 * </pre>
 * 타일 무관성(RangeField)이 보장되므로 어느 순서·크기로 쪼개도 같은 땅이 선다.
 *
 * <p>★ 실행 반경 주의: 산세 extent(경제권 444)는 장소 조성의 forge_radius 110 상위 계약
 * 밖이다 — 이 기계는 장소 조성 파이프라인(prepare)이 아니라 <b>광역 지형 단계</b>로 돈다
 * (★Q7 — 사용자 결정 대기 · H-10).
 */
public final class MountainRangeForge {

    private MountainRangeForge() {
    }

    /** 봉우리 위 허공을 비우는 여유 — raiseMassif 문법 (TF:1125) */
    private static final int CLEAR_ABOVE = 20;

    /** 표면 밑 몇 칸까지 돌인가 — 그 아래는 심성암 (raiseMassif 채움 문법, TF:1121-1123) */
    private static final int STONE_SHELL = 4;

    /** 잔디 표면 밑의 흙 두께 (sealBelow 관례의 결) */
    private static final int SOIL_DEPTH = 2;

    /**
     * 자연 자재 — <b>우리가 걷어도 되는 것</b> (TerrainForge.NATURAL 의 결 — 그 상수는
     * package-private 라 못 읽는다. 사본 최소분 — §5-5 통일 대상).
     * 이 밖의 단단한 것은 사람이 지은 것으로 보고 지나친다.
     */
    private static final Set<Material> NATURAL = EnumSet.of(
            Material.STONE, Material.DEEPSLATE, Material.TUFF, Material.GRANITE,
            Material.DIORITE, Material.ANDESITE, Material.CALCITE, Material.DIRT,
            Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.GRASS_BLOCK,
            Material.PODZOL, Material.SAND, Material.GRAVEL, Material.CLAY,
            Material.MOSS_BLOCK, Material.MUD, Material.SNOW_BLOCK, Material.BEDROCK);

    /** 초목 — 걷어도 되는 것 (땅이 아니다). foliage 문법의 사본 최소분 */
    private static boolean foliage(Material m) {
        String n = m.name();
        return n.endsWith("_LOG") || n.endsWith("_LEAVES") || n.endsWith("_GRASS")
                || n.endsWith("_FERN") || n.endsWith("_FLOWER") || n.endsWith("_SAPLING")
                || m == Material.SNOW || m == Material.DEAD_BUSH || m == Material.VINE;
    }

    /**
     * 산세 전체를 빚는다 — 경제권(444) 정사각 범위. <b>동기 실행이다</b> —
     * 큰 산은 {@link #forgeTile} 로 쪼개 스케줄러 아래서 돌리는 것이 옳다 (배선 지점).
     */
    public static void forge(World world, RangeSpec spec) {
        RangeField field = new RangeField(spec);
        int r = spec.economyR();
        forgeTile(world, spec, field,
                spec.peakX() - r, spec.peakZ() - r, spec.peakX() + r, spec.peakZ() + r);
    }

    /**
     * 타일 하나를 빚는다 — [minX..maxX] × [minZ..maxZ].
     * 타일 무관성: 같은 사양이면 어떤 분할로 불러도 이음매 없이 같은 땅이다.
     */
    public static void forgeTile(World world, RangeSpec spec, RangeField field,
                                 int minX, int minZ, int maxX, int maxZ) {
        preload(world, minX, minZ, maxX, maxZ);
        int base = spec.baseY();
        int ceiling = base + spec.lift() + CLEAR_ABOVE;
        // ★주상절리를 쪼개려면 이웃 높이를 알아야 한다. 열마다 딱 한 번만 재 둔다 —
        //   이웃마다 surfaceY 를 다시 부르면 다섯 곱이 되어 조성이 몇 배로 길어진다.
        int w = maxX - minX + 3;
        int d = maxZ - minZ + 3;
        int[][] bare = new int[w][d];
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < d; j++) {
                bare[i][j] = field.surfaceY(minX - 1 + i, minZ - 1 + j);
            }
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                RangeZone zone = field.zoneAt(x, z);
                if (zone == RangeZone.OUTSIDE) {
                    continue;   // 회랑·야생 — 노선 조성기의 몫
                }
                // ★주상절리 — 급한 면을 1칸 폭 기둥으로 쪼갠다 ({@link SpireField#jointed} 와
                //   같은 함수 · 산군과 본산의 결이 갈라지면 이음매가 드러난다).
                int i = x - minX + 1;
                int j = z - minZ + 1;
                int h0 = bare[i][j];
                int low = Math.min(Math.min(bare[i - 1][j], bare[i + 1][j]),
                        Math.min(bare[i][j - 1], bare[i][j + 1]));
                int target = base + SpireField.jointed(h0 - base, h0 - low, x, z);
                clearAbove(world, x, z, target, ceiling);
                if (target > base) {
                    fillColumn(world, field, x, z, base, target, zone);
                } else {
                    dressFlat(world, x, z, base, target, zone);
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 열(column) 하나 — 깎고, 채우고, 표층을 입힌다
    // ═══════════════════════════════════════════════════════════════════

    /** 표면 위 허공을 비운다 — 자연 자재·초목만. 사람이 지은 것은 지나친다 */
    private static void clearAbove(World world, int x, int z, int target, int ceiling) {
        for (int y = target + 1; y <= ceiling; y++) {
            Block b = world.getBlockAt(x, y, z);
            Material m = b.getType();
            if (!m.isAir() && (NATURAL.contains(m) || foliage(m))) {
                b.setType(Material.AIR, false);
            }
        }
    }

    /** 산몸 — base+1 부터 표면까지 빈틈없이 (superflat 이라 그 아래는 이미 단단하다) */
    private static void fillColumn(World world, RangeField field, int x, int z,
                                   int base, int target, RangeZone zone) {
        Material skin = skin(field, x, z, target - base, zone);
        boolean soil = skin == Material.GRASS_BLOCK;
        for (int y = target; y > base; y--) {
            Block b = world.getBlockAt(x, y, z);
            Material here = b.getType();
            if (here.isSolid() && !NATURAL.contains(here) && !foliage(here)) {
                continue;   // 사람이 지은 것 — 건축 계층의 것이다 (TF:1116-1119 문법)
            }
            Material put;
            if (y == target) {
                put = skin;
            } else if (soil && y > target - SOIL_DEPTH) {
                put = Material.DIRT;
            } else if (y > target - STONE_SHELL) {
                put = Material.STONE;
            } else {
                put = Material.DEEPSLATE;
            }
            b.setType(put, false);
        }
    }

    /**
     * 평지 켜 — 입구·외곽 평원. 요철이 base 아래로 내려간 열(±1 의 −쪽)은 깎고,
     * 표면을 잔디로 여민다. superflat 원표면은 최대한 그대로 둔다.
     */
    private static void dressFlat(World world, int x, int z, int base, int target, RangeZone zone) {
        if (target < base) {
            for (int y = target + 1; y <= base; y++) {
                Block b = world.getBlockAt(x, y, z);
                Material m = b.getType();
                if (!m.isAir() && (NATURAL.contains(m) || foliage(m))) {
                    b.setType(Material.AIR, false);
                }
            }
            Block top = world.getBlockAt(x, target, z);
            if (NATURAL.contains(top.getType()) || top.getType().isAir()) {
                top.setType(Material.GRASS_BLOCK, false);
            }
        }
        // target == base: superflat 그대로 — 손대지 않는다 (멱등의 가장 싼 형태)
    }

    /**
     * 표층 자재 — 구역 + 물매 + 결의 함수 (rockSkin 문법 계승, TF:1197-1212).
     * 식생은 여기 없다 — 흙·잔디는 식생층(⑨)이 심을 밑감일 뿐이다.
     */
    private static Material skin(RangeField field, int x, int z, int height, RangeZone zone) {
        double slope = field.slopeAt(x, z);
        double patch = Math.sin(x / 9.0) * Math.sin(z / 7.0) + Math.sin((x + z) / 19.0) * 0.7;
        if (slope > 1.5) {
            return patch > 0.2 ? Material.STONE : Material.DEEPSLATE;   // 절벽 — 암반이 드러난다
        }
        switch (zone) {
            case SUMMIT -> {
                return patch > 0.6 ? Material.ANDESITE : Material.STONE;   // 성역 — 풀·바위 (식생표)
            }
            case CLIFF -> {
                return patch > 0.5 ? Material.ANDESITE : Material.STONE;
            }
            case PLUM_GROVE, ENTRANCE, OUTER_PLAIN -> {
                return slope < 0.6 ? Material.GRASS_BLOCK : Material.STONE;
            }
            case VALLEY -> {
                // 계곡 — 이끼·바위의 결 (식생표: 대나무·이끼·양치는 ⑨의 몫, 여기는 밑감)
                if (patch > 0.55) {
                    return Material.MOSS_BLOCK;
                }
                return slope < 0.6 ? Material.GRASS_BLOCK : Material.STONE;
            }
            default -> {
                // 본산·후산·연무 계곡 — 산의 살갗: 완만하면 풀, 험하면 바위·너덜
                if (slope < 0.45 && patch < 0.3) {
                    return Material.GRASS_BLOCK;
                }
                if (patch > 0.85) {
                    return Material.ANDESITE;
                }
                if (patch < -0.9) {
                    return Material.TUFF;
                }
                return Material.STONE;
            }
        }
    }

    /** 조성 전 청크 선로드 — 콘솔 조성은 부지가 언로드 상태다 (TF.preload 문법) */
    private static void preload(World world, int minX, int minZ, int maxX, int maxZ) {
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                world.getChunkAt(cx, cz).load(true);
            }
        }
    }
}
