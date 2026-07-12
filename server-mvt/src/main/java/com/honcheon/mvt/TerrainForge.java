package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 지형 계층 — <b>땅의 단일 창구</b>. 건축 계층은 이 문으로만 땅과 이야기한다.
 *
 * <p>사용자 판정: "지형 생성 에이전트 / 건물·마을 생성 에이전트 — 두 개로 역할을 나누고 지도로 관리한다."
 * <b>땅과 집을 가른다.</b> 여기는 땅이다. 집은 {@code RemoteBuilder}·{@code CheonghaBuilder} 의 것이다.
 *
 * <pre>
 *   지도(world_map.yml) ──주문──▶ TerrainForge ──{@link SiteSpec}──▶ 건축 계층
 * </pre>
 *
 * <h2>계약 넷 (docs/design/terrain_layer.md)</h2>
 * <ol>
 *   <li><b>딛는 땅</b> — 조성 지면 아래 {@value #SEAL_DEPTH}칸은 단단하다 ({@link #sealBelow})</li>
 *   <li><b>경계가 자연으로 이어진다</b> — 전이대(페더링). 바깥에서 걸어 들어올 수 있다 ({@link #feather})</li>
 *   <li><b>물 위에 지을 자리를 주지 않는다</b> — 젖은 열은 마스크에서 뺀다. 잘린 물기둥은 치운다
 *       ({@link #tidyWater} · {@link SiteSpec#buildable})</li>
 *   <li><b>지형이 주문과 맞는다</b> — 험산인데 사막이면 봉우리를 세운다 ({@link #shape}).
 *       실지리 1:1 좌표는 못 옮긴다 — 그래서 <b>땅을 주문에 맞춘다</b></li>
 * </ol>
 *
 * <h2>동굴</h2>
 * 자연 동굴은 데이터팩({@code worldgen/honcheon_no_caves})으로 껐다 — <b>통제할 수 없기 때문이다</b>.
 * 동굴이 필요하면 <b>우리가 판다</b> ({@link #digCave}). 등록부가 요구하는 자리에, 우리가 정한 형태로.
 *
 * <h2>결정론</h2>
 * 난수가 없다. 모든 흔들림은 좌표 해시({@code Math.floorMod})다. 같은 좌표 = 같은 땅 = 같은 동굴.
 */
final class TerrainForge {

    private TerrainForge() {
    }

    // ═══════════════════════════════════════════════════════════════════
    // 계약 상수
    // ═══════════════════════════════════════════════════════════════════

    /** ① 조성 지면 아래 이만큼은 단단하다 (껍데기 위의 마을을 두 번 짓지 않는다) */
    static final int SEAL_DEPTH = 6;

    /** ② 경계 전이대의 폭 — 부지 밖 이만큼에 걸쳐 자연 지면으로 수렴한다 */
    static final int FEATHER_WIDTH = 22;

    /** 건축 가능 최대 경사 — 이웃 칸과의 높이차가 이보다 크면 집을 못 앉힌다 */
    private static final int BUILD_MAX_STEP = 3;

    /** 젖은 열 표시 — 그 열은 땅이 아니라 물이다 */
    private static final int WET_COLUMN = Integer.MIN_VALUE;

    // ═══════════════════════════════════════════════════════════════════
    // 자재의 선 — 자연이 놓은 것과 사람이 지은 것
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 자연이 놓은 자재 — <b>사람이 지은 것과 가르는 선</b>.
     *
     * <p>이 선이 없어서 두 번 속았다: 재조성이 옛 지붕(기와)을 자연으로 알고 남겨 두었고,
     * 그 지붕 때문에 "지면이 이미 높다"고 읽어 <b>산을 세우지 않았다</b>(사막 위 도관이 두 번 섰다).
     */
    static final Set<Material> NATURAL = EnumSet.of(
            Material.STONE, Material.DEEPSLATE, Material.TUFF, Material.GRANITE, Material.DIORITE,
            Material.ANDESITE, Material.CALCITE, Material.DIRT, Material.COARSE_DIRT,
            Material.ROOTED_DIRT, Material.GRASS_BLOCK, Material.PODZOL, Material.MYCELIUM,
            Material.SAND, Material.RED_SAND, Material.SANDSTONE, Material.RED_SANDSTONE,
            Material.GRAVEL, Material.CLAY, Material.TERRACOTTA, Material.SNOW_BLOCK,
            Material.PACKED_ICE, Material.ICE, Material.WATER, Material.LAVA, Material.BEDROCK,
            Material.MOSS_BLOCK, Material.MUD);

    /** 딛을 수 있는 자연 지면 — 물·얼음은 여기 없다 (물을 땅으로 읽으면 마을이 호수에 선다) */
    private static final Set<Material> SOLID_NATURAL = EnumSet.of(
            Material.STONE, Material.DEEPSLATE, Material.TUFF, Material.GRANITE, Material.DIORITE,
            Material.ANDESITE, Material.CALCITE, Material.DIRT, Material.COARSE_DIRT,
            Material.ROOTED_DIRT, Material.GRASS_BLOCK, Material.PODZOL, Material.MYCELIUM,
            Material.SAND, Material.RED_SAND, Material.SANDSTONE, Material.RED_SANDSTONE,
            Material.GRAVEL, Material.CLAY, Material.TERRACOTTA, Material.SNOW_BLOCK,
            Material.MOSS_BLOCK, Material.MUD, Material.BEDROCK);

    /** 젖은 것 — 이 블록을 만나면 그 열은 '물'이다 (WorldMap.WET 규약 계승) */
    private static final Set<Material> WET = EnumSet.of(
            Material.WATER, Material.LAVA, Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE,
            Material.FROSTED_ICE, Material.KELP, Material.KELP_PLANT, Material.SEAGRASS,
            Material.TALL_SEAGRASS, Material.BUBBLE_COLUMN);

    /** 초목 — 걷어도 되는 것 (땅이 아니다) */
    private static boolean foliage(Material m) {
        String n = m.name();
        return n.endsWith("_LOG") || n.endsWith("_LEAVES") || n.endsWith("_GRASS")
                || n.endsWith("_FERN") || n.endsWith("_FLOWER") || n.endsWith("_SAPLING")
                || n.endsWith("_MUSHROOM") || m == Material.SNOW || m == Material.DEAD_BUSH
                || m == Material.VINE || m == Material.SUGAR_CANE || m == Material.CACTUS
                || m == Material.BAMBOO || m == Material.MOSS_CARPET || m == Material.LILY_PAD;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 부지 사양 (SiteSpec) — 지형 계층이 건축 계층에게 주는 유일한 산출물
    // ═══════════════════════════════════════════════════════════════════

    /**
     * <b>부지 사양</b> — "여기에 이렇게 지어라"는 땅의 답장.
     *
     * <p>건축 계층은 이 record 만 보고 짓는다. 땅을 직접 재지 않는다 —
     * {@code getHighestBlockYAt} 은 물을 땅으로 읽고 옛 지붕을 지면으로 읽는다(그 버그로 두 번 무너졌다).
     * <b>땅에 대한 질문은 전부 여기서 답한다.</b>
     *
     * @param placeId    등록부 id (world_map.yml)
     * @param name       사람이 부르는 이름
     * @param terrain    주문된 지형 (평지·산·험산·고원·분지·강·수향·폐허…)
     * @param cx         부지 중심 x (등록 좌표에서 밀렸을 수 있다 — 이미 반영된 값)
     * @param cz         부지 중심 z
     * @param radius     부지 반경 — 마스크가 덮는 범위
     * @param groundY    <b>조성 기준 지면</b>. 건축 계층은 이 y 위에 세운다 (밑은 이미 단단하다)
     * @param peakX      봉우리 중심 x — 산을 세웠거나 자연 봉우리가 있을 때. 아니면 cx
     * @param peakZ      봉우리 중심 z
     * @param peakY      <b>봉우리 정상 y</b>. 두 켜를 쓰는 원형(문파: 문전→계단→본전)이 위 켜로 삼을 높이.
     *                   산이 아니면 groundY 와 같다 — <b>같으면 켜가 하나라는 뜻이다</b>
     * @param surface    각 열의 지면 y (부지 좌표계 (2r+1)²). {@link #groundAt} 로 읽는다
     * @param buildable  <b>건축 가능 마스크</b> — 물·급경사·보호구역(동굴 입구)을 뺀 땅
     * @param approaches <b>진입 방향</b> — 바깥에서 걸어 들어올 수 있는 방위.
     *                   비면 안 된다(계약 ②). 산은 하나여도 정상이고, 들은 사방이 열린다
     * @param waterfront <b>수변인가</b> — 참이면 건축 계층은 나루·다리·선착장을 놓는다
     * @param waterSides 물이 있는 방위 (수변일 때만 채워진다) — 나루가 어느 쪽을 보는가
     * @param slope      경사 등급 0(평탄) · 1(완만) · 2(비탈) · 3(험). 배치의 문법이 여기서 갈린다
     * @param relief     부지 안 고저차(칸) — slope 의 근거
     */
    record SiteSpec(String placeId, String name, String terrain, String world,
                    int cx, int cz, int radius,
                    int groundY, int peakX, int peakZ, int peakY,
                    int[] surface, boolean[] buildable,
                    List<BlockFace> approaches,
                    boolean waterfront, List<BlockFace> waterSides,
                    int slope, int relief) {

        /** 부지 좌표계 인덱스 — 밖이면 -1 */
        private int idx(int x, int z) {
            int dx = x - cx + radius;
            int dz = z - cz + radius;
            int w = radius * 2 + 1;
            if (dx < 0 || dz < 0 || dx >= w || dz >= w) {
                return -1;
            }
            return dz * w + dx;
        }

        boolean inside(int x, int z) {
            return idx(x, z) >= 0;
        }

        /** <b>여기에 지어도 되는가</b> — 건축 계층이 블록 하나를 놓기 전에 묻는 질문 */
        boolean canBuild(int x, int z) {
            int i = idx(x, z);
            return i >= 0 && buildable[i];
        }

        /**
         * <b>그 열의 지면 y</b> — 물이거나 부지 밖이면 {@link #groundY}(기준면)로 답한다.
         * 건축 계층은 이 값을 믿어도 된다: 그 아래 {@value #SEAL_DEPTH}칸은 단단하다.
         */
        int groundAt(int x, int z) {
            int i = idx(x, z);
            if (i < 0 || surface[i] == WET_COLUMN) {
                return groundY;
            }
            return surface[i];
        }

        /** 젖은 열인가 — 물 위다 (건축 금지) */
        boolean wet(int x, int z) {
            int i = idx(x, z);
            return i >= 0 && surface[i] == WET_COLUMN;
        }

        /** 산의 문법인가 — 산채·도관은 한 길로 있고, 성시는 사방이 열린다 */
        boolean mountain() {
            return "산".equals(terrain) || "험산".equals(terrain) || "고원".equals(terrain)
                    || "설산".equals(terrain);
        }

        /** 두 켜를 쓸 수 있는가 — 봉우리가 기준면보다 충분히 높다 (문파: 문전 ↔ 본전) */
        boolean twoTier() {
            return peakY - groundY >= 12;
        }

        /** 건축 가능한 칸 수 — 부지가 얼마나 쓸 만한가 */
        int buildableCount() {
            int n = 0;
            for (boolean b : buildable) {
                if (b) {
                    n++;
                }
            }
            return n;
        }

        /** 보호구역 지정 — 동굴 입구·나루터처럼 <b>건축이 덮으면 안 되는 자리</b> */
        void reserve(int x, int z, int r) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int i = idx(x + dx, z + dz);
                    if (i >= 0) {
                        buildable[i] = false;
                    }
                }
            }
        }

        /** 경사 등급의 이름 (보고용) */
        String slopeName() {
            return switch (slope) {
                case 0 -> "평탄";
                case 1 -> "완만";
                case 2 -> "비탈";
                default -> "험";
            };
        }

        /** 한 줄 요약 — 검수·로그가 읽는다 */
        String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(name).append(" (").append(cx).append(',').append(cz).append(") 지면 y").append(groundY);
            if (twoTier()) {
                sb.append(" · 정상 y").append(peakY);
            }
            sb.append(" · 경사 ").append(slopeName()).append('(').append(relief).append("칸)");
            sb.append(" · 건축가능 ").append(buildableCount()).append('/')
                    .append((radius * 2 + 1) * (radius * 2 + 1)).append("칸");
            sb.append(" · 진입 ");
            sb.append(approaches.isEmpty() ? "없음(!)" : faces(approaches));
            if (waterfront) {
                sb.append(" · 수변 ").append(faces(waterSides));
            }
            return sb.toString();
        }

        private static String faces(List<BlockFace> list) {
            List<String> out = new ArrayList<>();
            for (BlockFace f : list) {
                out.add(switch (f) {
                    case NORTH -> "북";
                    case EAST -> "동";
                    case SOUTH -> "남";
                    case WEST -> "서";
                    default -> f.name();
                });
            }
            return String.join("·", out);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 창구 — prepare()
    // ═══════════════════════════════════════════════════════════════════

    /**
     * <b>땅을 빚고 부지 사양을 낸다</b> — 건축 계층이 부르는 단 하나의 함수.
     *
     * <p>순서가 곧 계약이다:
     * <ol>
     *   <li>{@link #preload} 땅을 싣는다 (원거리는 아직 존재하지 않는 땅이다)</li>
     *   <li>{@link #clearSurface} 부지 위를 걷는다 (초목 · <b>지난 조성이 남긴 것</b>)</li>
     *   <li>{@link #shape} 계약 ④ — 지형을 주문에 맞춘다 (험산인데 사막이면 봉우리를 세운다)</li>
     *   <li>{@link #tidyWater} 계약 ③ — 잘린 물기둥·공중의 물을 치운다</li>
     *   <li>{@link #feather} 계약 ② — 경계를 자연으로 잇는다</li>
     *   <li>{@link #survey} 실측 — 마스크·진입·수변·경사를 잰다 (계약 ①은 {@link #terrace} 가 지킨다)</li>
     * </ol>
     *
     * @param cy 조성 기준 지면 (MvtCommand 가 원장에서 꺼내 준다 — 다시 재면 지난번 세운 산 위에 또 쌓는다)
     * @param radius 부지 반경 (산채 24 · 문파 44 · 마을 61)
     */
    static SiteSpec prepare(World world, WorldMap.Place place, int cx, int cy, int cz, int radius) {
        int[] peak = new int[]{cx, cz, cy, 0};
        int reach = radius + FEATHER_WIDTH + 8;
        preload(world, cx, cz, reach);

        clearSurface(world, cx, cy, cz, radius);
        peak = shape(world, place, cx, cy, cz, radius);
        int massifR = peak[3];
        if (massifR > 0) {
            preload(world, peak[0], peak[1], massifR + 40);   // 산의 발치까지 땅이 실려 있어야 한다
        }
        tidyWater(world, cx, cy, cz, radius + FEATHER_WIDTH);

        // 경계 전이대 — **산을 세웠으면 산의 발치만 편다** (그 위는 벼랑이어야 산이다).
        //   벼랑은 부지가 아니라 **봉우리의 발치**에 선다.
        BlockFace trail = null;
        if (massifR > 0) {
            feather(world, peak[0], peak[1], massifR - 4, massifR + 18, peak[2]);
            // ★ 등반로 — **오르는 길은 하나면 된다** (사용자의 판정).
            //   구판은 산 전체를 완만하게 만들어 사방에서 걷게 했다 → 흙무더기가 됐다.
            //   이제 산은 깎아지르고, 길 하나가 그것을 감아 오른다.
            trail = carveTrail(world, peak[0], peak[1], peak[2], massifR, cy, SUMMIT_R);
        } else {
            feather(world, cx, cz, radius, radius + FEATHER_WIDTH, cy);
            // 진입로 보장 (계약 ②) — 산이 아닌 곳은 여전히 사방에서 걸어 들어온다
            ensureApproach(world, place, cx, cy, cz, radius);
        }

        // 표층 (계약 ⑤) — **눈과 모래는 우리가 얹는다** (바이옴이 주던 것을 우리가 준다).
        //   맨 마지막에 돈다: 봉우리·사구·전이대·램프가 전부 놓인 뒤에 그 위를 덮어야
        //   산의 발치도 걸어 오르는 길도 같은 표층을 입는다 (길만 잔디인 설산은 우습다).
        //   ★ 봉우리를 세웠으면 **산의 발치까지** 덮는다 — 부지 반경만 덮으면 정상만 희고 산은 검다.
        Surface s = surface(place);
        if (s != Surface.자연) {
            int overlayR = massifR > 0 ? massifR + 30 : radius + FEATHER_WIDTH;
            int ox = massifR > 0 ? peak[0] : cx;
            int oz = massifR > 0 ? peak[1] : cz;
            applySurface(world, ox, oz, overlayR, s, snowline(place), Math.max(cy, peak[2]));
        }

        // 오아시스 (W3) — 사막의 예외. **모래 한가운데 진짜 물**이 있어야 그 서사가 지형에서 읽힌다.
        //   표층(모래) 다음에 판다 — 오아시스는 모래를 덮는 것이지 모래에 덮이는 것이 아니다
        if (hasOasis(place)) {
            carveOasis(world, cx, cy, cz, radius);
        }

        SiteSpec spec = survey(world, place, cx, cy, cz, radius, peak[0], peak[1], peak[2]);
        // 등반로가 있으면 그것이 진입이다 — 직선 광선 검사(approaches)는 굽이치는 산길을 못 본다.
        //   길은 나선이라 어느 직선도 그 길만 따라가지 않는다. **길이 있다는 사실은 우리가 안다.**
        if (trail != null && !spec.approaches().contains(trail)) {
            spec.approaches().add(trail);
        }
        return spec;
    }

    /**
     * 진입로 보장 — <b>최소 한 방위에서는 걸어 오를 수 있어야 한다</b> (환경 검수 ④).
     *
     * <p>산채는 진입 <b>0방위</b>였다. 봉우리를 세운 탓도 있지만, 근본은 <b>지형 계층이 길을 보장하지 않았다</b>는
     * 것이다. 우리는 땅을 빚었으면 <b>그 땅에 닿는 길</b>까지 책임져야 한다.
     *
     * <p>이미 걸어 들어올 수 있으면 아무것도 하지 않는다 (자연이 준 길이 언제나 낫다).
     * 막혔으면 <b>남쪽</b>에 램프를 낸다 — 등록부가 그렇게 적고 있다:
     * 산채는 "산길이 채문(남)으로 온다", 도관은 "산문 → 천 계단"(남에서 오른다).
     */
    private static BlockFace ensureApproach(World world, WorldMap.Place place,
                                            int cx, int cy, int cz, int radius) {
        if (!approaches(world, cx, cy, cz, radius).isEmpty()) {
            return null;   // 자연이 이미 길을 줬다
        }
        int centerY = naturalTop(world, cx, cz, cy + 80, cy - 40);
        carveRamp(world, cx, cz, centerY, radius, BlockFace.SOUTH, cy);
        if (!approaches(world, cx, cy, cz, radius).isEmpty()) {
            return BlockFace.SOUTH;
        }
        // 남쪽이 물이거나 절벽이면 나머지 방위를 순서대로 (결정론 — 북·동·서)
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.WEST}) {
            carveRamp(world, cx, cz, centerY, radius, f, cy);
            if (!approaches(world, cx, cy, cz, radius).isEmpty()) {
                return f;
            }
        }
        return null;   // 네 방위 다 냈는데도 못 걸어온다 — 검수가 잡을 것이다 (숨기지 않는다)
    }

    /** 램프의 반폭 — 5칸 폭 (요구: 3칸 이상. 사람 둘이 비켜 간다) */
    private static final int RAMP_HALF = 2;

    /**
     * 램프 — <b>걸어 오르는 길</b>. 계단이 아니라 비탈이다 (계단·석등은 건축 계층의 것이다).
     *
     * <p>규칙은 하나: <b>인접 칸 높이차 ≤ 1</b>. 그래서 길이를 높이차에 맞춰 늘린다 —
     * 36켜를 올라야 하면 최소 36칸을 걷는다. 짧은 길로 높이 오르려 하면 그것은 길이 아니라 벼랑이다.
     */
    private static void carveRamp(World world, int cx, int cz, int centerY, int radius,
                                  BlockFace dir, int yHint) {
        int dx = dir.getModX();
        int dz = dir.getModZ();
        // 바깥 착지점 — 자연 지면에 닿는 곳. 높이차만큼 길이를 늘려 물매를 1:1 아래로 눕힌다
        int len = radius + 40;
        for (int pass = 0; pass < 2; pass++) {
            int outer = naturalTop(world, cx + dx * len, cz + dz * len, yHint + 80, yHint - 60);
            len = Math.max(len, Math.abs(centerY - outer) + 20);
        }
        int outerY = naturalTop(world, cx + dx * len, cz + dz * len, yHint + 80, yHint - 60);

        for (int i = 0; i <= len; i++) {
            int d = len - i;                                    // 바깥(len) → 중심(0)
            int y = (int) Math.round(outerY + (centerY - outerY) * (double) i / len);
            for (int w = -RAMP_HALF; w <= RAMP_HALF; w++) {
                int x = cx + dx * d + (dx == 0 ? w : 0);
                int z = cz + dz * d + (dz == 0 ? w : 0);
                for (int yy = y + 1; yy <= y + 6; yy++) {       // 머리 위를 비운다 (사람이 선다)
                    Block b = world.getBlockAt(x, yy, z);
                    Material m = b.getType();
                    if (!m.isAir() && (NATURAL.contains(m) || foliage(m))) {
                        b.setType(Material.AIR);
                    }
                }
                Block top = world.getBlockAt(x, y, z);          // 딛는 자리를 놓는다
                if (top.getType().isAir() || top.isLiquid()) {
                    top.setType(grassOrStone(world, x, y, z));
                }
                sealBelow(world, x, y, z);                      // 계약 ① — 길 밑도 단단하다
            }
        }
    }

    /** 지형을 빚지 않고 <b>재기만</b> 한다 — 검수·조회용 (땅을 건드리지 않는다) */
    static SiteSpec survey(World world, WorldMap.Place place, int cx, int cy, int cz, int radius) {
        return survey(world, place, cx, cy, cz, radius, cx, cz, naturalTop(world, cx, cz, cy + 60, cy));
    }

    // ═══════════════════════════════════════════════════════════════════
    // 계약 ① — 딛는 땅 (기초 봉인)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 기초 봉인 — <b>바닥 밑이 비면 그것은 껍데기다</b>.
     *
     * <p>인게임에서 드러난 파탄: 우리가 깐 길·마당이 바닐라 동굴 위에 얇게 덮여 있었다(환경 검수 13%).
     * 발밑을 딛으면 그 아래가 허공이고, 어딘가는 뚫려 있고, 협곡이 잘려 열렸다.
     * 조성 지면 아래 <b>여섯 칸</b>은 반드시 단단해야 한다.
     *
     * <p>물도 채운다 — 대수층(aquifer)은 동굴을 껐어도 남는다. 물 위의 길도 껍데기다.
     */
    static void sealBelow(World world, int x, int y, int z) {
        for (int i = 1; i <= SEAL_DEPTH; i++) {
            Block b = world.getBlockAt(x, y - i, z);
            if (b.getType().isAir() || b.isLiquid()) {
                b.setType(i <= 2 ? Material.DIRT : Material.STONE);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 계약 ② — 경계가 자연으로 이어진다 (페더링)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 경계 페더링 — <b>부지가 자연으로 스며들게 한다</b>.
     *
     * <p>단(段)을 놓으면 그 가장자리에서 지형이 뚝 끊긴다. 산채는 경계 급단차 52.7%(최대 20칸)였고,
     * <b>북쪽에서는 아예 걸어 들어올 수 없었다</b> — 우리가 산비탈에 벼랑을 냈기 때문이다.
     *
     * <p><b>v2 — 안쪽 높이를 상수로 잡으면 안 된다.</b> 구판은 안쪽 기준을 조성 지면 {@code cy} 로 못박았다.
     * 그런데 봉우리를 세우면 <b>경계에서의 실제 지면은 cy 가 아니라 산비탈(cy+30 언저리)</b>이다.
     * 전이대가 그 비탈을 cy 까지 깎아 내려 <b>33칸짜리 절개 벼랑</b>을 냈고, 산채는 사방이 막혔다
     * (경계 급단차 32.3% · 진입 0방위 — 도적이 못 드나드는 산채가 됐다).
     *
     * <p>고친 것: 안쪽 높이를 <b>그 방위의 실제 지형에서 읽는다</b>(rInner 에서 표본). 그러면 전이대는
     * "부지 → 자연"이 아니라 <b>"지형 → 지형"</b>을 잇는다. 봉우리를 세워도 그 발치가 자연으로 흘러내린다.
     *
     * <p><b>물은 건드리지 않는다</b> (계약 ③) — 젖은 열은 건너뛴다. 강을 메우면 강이 아니다.
     *
     * @param yHint 표본을 시작할 높이 (그 언저리의 지형을 찾는다)
     */
    static void feather(World world, int cx, int cz, int rInner, int rOuter, int yHint) {
        for (int x = cx - rOuter; x <= cx + rOuter; x++) {
            for (int z = cz - rOuter; z <= cz + rOuter; z++) {
                double d = dist(x - cx, z - cz);
                if (d < rInner || d > rOuter || d < 1) {
                    continue;
                }
                int natural = naturalGround(world, x, z, yHint + 70);
                if (natural == WET_COLUMN) {
                    continue;   // 강·호수 — 메우지 않는다 (물은 흘러야 물이다)
                }
                // 그 방위의 **안쪽 지형**과 **바깥쪽 지형** — 상수가 아니라 땅에게 묻는다
                double ux = (x - cx) / d;
                double uz = (z - cz) / d;
                int innerY = rayHeight(world, cx, cz, ux, uz, rInner, yHint);
                int outerY = rayHeight(world, cx, cz, ux, uz, rOuter, yHint);
                double t = (d - rInner) / (double) Math.max(1, rOuter - rInner);   // 0 = 안, 1 = 바깥
                int jitter = Math.floorMod(x * 7 + z * 11, 3) - 1;
                int target = (int) Math.round(innerY * (1 - t) + outerY * t) + jitter;
                if (Math.abs(target - natural) <= 1) {
                    continue;   // 이미 자연과 같다 — 손대지 않는다
                }
                // 위를 비운다(깎기) — **여섯 칸으로는 모자랐다**: 비탈을 자른 자리에 20칸짜리 절개 벼랑이
                //   그대로 서 있었다(환경 검수: 경계 급단차 41%). 전이대의 높이까지 깎아야 전이대다.
                for (int y = target + 1; y <= target + 24; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (!m.isAir() && (NATURAL.contains(m) || foliage(m))) {
                        b.setType(Material.AIR);
                    }
                }
                Block top = world.getBlockAt(x, target, z);
                if (top.getType().isAir() || top.isLiquid()) {
                    top.setType(grassOrStone(world, x, target, z));
                }
                sealBelow(world, x, target, z);   // 메운 자리 밑도 단단해야 한다
            }
        }
    }

    /**
     * 그 방위 그 거리의 지형 높이 — <b>땅에게 묻는다</b>. 한 열은 거짓말을 하므로(바위 하나·구덩이 하나)
     * 세 점을 표본해 가운데 값을 쓴다.
     */
    private static int rayHeight(World world, int cx, int cz, double ux, double uz, int d, int yHint) {
        int[] s = new int[3];
        for (int i = 0; i < 3; i++) {
            int dd = d + (i - 1) * 2;
            int x = cx + (int) Math.round(ux * dd);
            int z = cz + (int) Math.round(uz * dd);
            s[i] = naturalTop(world, x, z, yHint + 70, yHint - 70);
        }
        java.util.Arrays.sort(s);
        return s[1];   // 가운데 값
    }

    /** 메우는 자재는 자연을 따른다 — 사막에 잔디를 깔면 그것도 상처다 */
    private static Material grassOrStone(World world, int x, int y, int z) {
        for (int dx = -3; dx <= 3; dx += 3) {
            for (int dz = -3; dz <= 3; dz += 3) {
                int g = naturalGround(world, x + dx, z + dz, y + 8);
                if (g == WET_COLUMN) {
                    continue;
                }
                Material m = world.getBlockAt(x + dx, g, z + dz).getType();
                if (m == Material.SAND || m == Material.RED_SAND || m == Material.SANDSTONE
                        || m == Material.SNOW_BLOCK || m == Material.PODZOL || m == Material.GRASS_BLOCK
                        || m == Material.COARSE_DIRT) {
                    return m;
                }
            }
        }
        return Material.GRASS_BLOCK;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 계약 ③ — 수역 정직
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 수역 정리 — <b>물은 흘러야 물이다</b>.
     *
     * <p>땅을 빚으면 물이 토막 난다: 산을 세우면 강물이 산 위로 딸려 올라가 <b>산 위의 웅덩이</b>가 되고,
     * 비탈을 깎으면 물기둥이 잘려 <b>공중의 물</b>이 남는다 (환경 검수 ②가 그것을 센다).
     *
     * <p>규칙은 둘뿐이다:
     * <ul>
     *   <li><b>공중의 물</b> (밑이 허공인 물) — 없앤다. 잘린 물기둥은 물이 아니다</li>
     *   <li><b>고인 물</b> (조성 지면 +3 보다 높은 물) — 없앤다. 강·호수는 지면 아래에 있다.
     *       그보다 높은 물은 우리가 땅을 빚다가 딸려 올린 것이다</li>
     * </ul>
     * <b>강은 건드리지 않는다</b> — 기준면 아래의 물은 세계의 것이다. 마을이 물가면 물가라고 말할 뿐이다
     * ({@link SiteSpec#waterfront}) — 나루·다리는 건축 계층이 놓는다.
     */
    static int tidyWater(World world, int cx, int cy, int cz, int radius) {
        int cleaned = 0;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                if (dist(x - cx, z - cz) > radius) {
                    continue;
                }
                for (int y = cy + 40; y >= cy - 10; y--) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getType() != Material.WATER) {
                        continue;
                    }
                    boolean airBelow = world.getBlockAt(x, y - 1, z).getType().isAir();
                    boolean perched = y > cy + 3;
                    if (airBelow || perched) {
                        b.setType(Material.AIR);   // 잘린 물기둥 · 산 위의 웅덩이
                        cleaned++;
                    }
                }
            }
        }
        return cleaned;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 계약 ④ — 지형이 주문과 맞는다
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 조성 윤곽(profile) — <b>같은 "산"이라도 서는 집이 다르면 빚는 땅이 다르다</b>.
     *
     * <p>인게임이 가르쳐 준 것: 녹림 소채에 {@code terrain: 산} 이 적혀 있다고 <b>봉우리(+36)</b>를 세웠더니
     * 사방이 33칸 벼랑이 되어 <b>진입 0방위</b>가 됐다. 도적이 못 드나드는 산채는 산채가 아니다.
     *
     * <p><b>산채는 봉우리가 아니다.</b> 산채는 산 중턱의 완만한 단(段)에 목책을 두른 집이고,
     * 등록부도 그렇게 적고 있다 ("목책과 통나무 막사. 도적의 집은 언제든 버릴 수 있어야 한다" · tier: poor).
     * 봉우리는 <b>도관의 것</b>이다 — "산문 → 천 계단 → 본전"을 적어 둔 집만이 봉우리를 요구한다.
     *
     * <p><b>지도가 관리한다</b> — 세력과 architecture 가 윤곽을 고른다.
     */
    enum Profile {
        /** 봉우리 — 문파의 산. 오르는 길이 시험이다 (+36, 천 계단) */
        봉우리,
        /** 중턱단 — 산채·마을. <b>산을 세우지 않는다.</b> 있는 산의 중턱을 필요한 만큼만 고른다 */
        중턱단,
        /** 들 — 평지·분지·폐허. 발치를 메워 고른다 */
        들,
        /** 사막 — 서역. <b>사막은 평평한 게 아니라 물결친다</b> (사구). 봉우리처럼 세우지 않는다 */
        사막,
        /** 그대로 — 강·수향·섬·밀림. 물과 숲은 자연이 준 대로가 옳다 */
        그대로
    }

    /**
     * 표층 덧입힘(surface overlay) — <b>눈과 모래는 블록이지 바이옴이 아니다</b>.
     *
     * <p>사용자가 "사막 필드도 얼음 지역도 필요없다"고 했고, 순도 담당이 바이옴 12종을 껐다. 옳다 —
     * <b>무작위로 흩뿌려지는 사막·빙원은 통제 불가능</b>하기 때문이다. 그런데 끄는 순간
     * <b>이 세계에서 모래와 눈의 유일한 출처가 사라졌다</b>: 곤륜(설산)은 회색 돌산이 됐고,
     * 가욕관(사막)은 잔디밭이 됐다. <b>둘 다 이미 등록된 중원의 장소다.</b>
     *
     * <p>원칙은 이미 정해져 있다 — <b>자연 동굴을 끄고 굴을 우리가 판 것과 정확히 같다</b>:
     * <pre>
     *   끈다   — 통제 불가능한 무작위 배치는.
     *   만든다 — 등록 좌표의 의도된 지형은.
     * </pre>
     * 지금까지 앞의 절반만 했다. 이것이 뒤의 절반이다.
     *
     * <p><b>덧입힘은 윤곽(Profile)이 아니다</b> — 어느 윤곽 위에도 얹힌다
     * (봉우리 + 설원 = 곤륜 · 고원 + 설원 = 토번 · 들 + 사막 = 가욕관).
     */
    enum Surface {
        /** 자연 — 땅이 준 표층 그대로 (중원의 기본) */
        자연,
        /** 사막 — 모래·사암·자갈의 결. 표층 SAND/RED_SAND, 그 아래 SANDSTONE */
        사막,
        /** 설원 — <b>설선(雪線) 위에만</b> 눈이 얹힌다. 고도가 눈을 정한다 */
        설원
    }

    /** 봉우리를 요구하는 세력 — 구파일방·오대세가. <b>산문에서 본전까지 오르는 집</b>들이다 */
    private static final Set<String> PEAK_FACTIONS = Set.of(
            "hwasan", "gupailbang", "jongnam", "sorimsa", "mudang", "gonryun",
            "jeomchang", "cheongseong", "ami", "haenam", "gaebang");

    /**
     * 이 자리를 어떤 윤곽으로 빚는가 — <b>등록부(terrain.yml)가 먼저, 없으면 지도에서 읽어 낸다</b>.
     *
     * <p>읽는 순서: ① terrain.yml 의 명시 지정 → ② 세력(녹림은 중턱단 · 구파·세가는 봉우리)
     * → ③ architecture 서술("산문"·"본전"·"계단"이 적혀 있으면 봉우리다) → ④ 지형.
     */
    static Profile profile(WorldMap.Place place) {
        Profile registered = shapeRegistry.get(place.id());
        if (registered != null) {
            return registered;
        }
        String terrain = place.terrain() == null ? "" : place.terrain();
        String faction = place.faction() == null ? "" : place.faction().toLowerCase(Locale.ROOT);
        String arch = place.note() == null ? "" : place.note();
        boolean mountainous = "산".equals(terrain) || "험산".equals(terrain)
                || "설산".equals(terrain) || "고원".equals(terrain);

        if (!mountainous) {
            return switch (terrain) {
                case "평지", "평야", "분지", "초원", "폐허", "구릉" -> Profile.들;
                // ★ 사막이 switch 에 **없어서** default(그대로) 로 떨어졌다 — 지형 계층이 사막을 빚지 않았다.
                //   바닐라 사막 바이옴이 살아 있을 때는 티가 안 났고, 그것이 꺼지자 가욕관이 잔디밭이 됐다.
                case "사막" -> Profile.사막;
                default -> Profile.그대로;   // 강·수향·섬·밀림 — 빚지 않는다 (★ 섬은 자연에 맡긴다)
            };
        }
        if ("noklim".equals(faction)) {
            return Profile.중턱단;   // ★ 산채는 봉우리가 아니다 — 중턱에 걸터앉는다
        }
        if (PEAK_FACTIONS.contains(faction)) {
            return Profile.봉우리;   // 도관 — 오르는 길이 시험이다
        }
        // 세력이 없거나 모르는 세력이면 **등록부의 말**을 듣는다 (지도가 관리한다)
        if (arch.contains("산문") || arch.contains("본전") || arch.contains("계단")
                || arch.contains("도관") || arch.contains("석성")) {
            return Profile.봉우리;
        }
        return Profile.중턱단;   // 모르면 산을 세우지 않는다 — **덜 하는 쪽이 안전하다**
    }

    /**
     * 이 자리의 표층 — <b>등록부가 먼저, 없으면 지형이 말한다</b>.
     *
     * <p>{@code 설산 → 설원} · {@code 사막 → 사막}. 그 밖은 자연이다.
     * 고원+눈(토번)처럼 지형만으로 못 읽는 것은 {@code config/terrain.yml} 의 {@code surfacing:} 이 지정한다.
     */
    static Surface surface(WorldMap.Place place) {
        Surface registered = surfaceRegistry.get(place.id());
        if (registered != null) {
            return registered;
        }
        String terrain = place.terrain() == null ? "" : place.terrain();
        return switch (terrain) {
            case "사막" -> Surface.사막;
            case "설산" -> Surface.설원;
            default -> Surface.자연;
        };
    }

    /**
     * 설선(雪線) — <b>이 높이 위에만 눈이 얹힌다</b>.
     *
     * <p>지역마다 다르다 (곤륜은 낮고 화산은 높다 — 위도와 기후가 다르므로).
     * 등록부가 정한다. 없으면 {@value #SNOWLINE_DEFAULT}.
     *
     * <p><b>눈을 산 전체에 덮지 않는 이유</b>: 설산의 그림은 흰 산이 아니라
     * <b>돌과 눈이 갈라지는 선</b>이다. 발치는 검고 정상은 희다 — 그 대비가 설산이다.
     */
    static int snowline(WorldMap.Place place) {
        Integer registered = snowlineRegistry.get(place.id());
        return registered == null ? SNOWLINE_DEFAULT : registered;
    }

    /** 설선의 기본값 — 곤륜 y120 · 토번 y110 언저리 (등록부가 장소마다 고쳐 준다) */
    static final int SNOWLINE_DEFAULT = 120;

    /** 오아시스를 파는 자리인가 — <b>모래 한가운데 진짜 물이 있어야</b> 그 서사가 지형에서 읽힌다 */
    static boolean hasOasis(WorldMap.Place place) {
        return oasisRegistry.contains(place.id());
    }

    /**
     * 지형 빚기 — <b>기본 세계를 쓰되, 없으면 만든다</b>.
     *
     * <p>등록부는 장소마다 지형을 요구한다. 바닐라 생성은 실지리를 모른다 — 화산파의 좌표에 <b>사막</b>을 놓았다.
     * 좌표를 옮기면 지도(실지리 1:1)가 무너지고, 그냥 지으면 사막 한복판의 도관이 된다.
     * <b>세 번째 답: 땅을 주문에 맞춘다.</b> 다만 <b>주문이 봉우리를 요구할 때만</b> 봉우리를 세운다.
     *
     * <p>이미 맞는 땅은 건드리지 않는다 — 자연이 준 것이 언제나 더 낫다. 빚는 것은 <b>모자랄 때뿐</b>이다.
     *
     * @return {peakX, peakZ, peakY, massifRadius} — massifRadius 0 이면 산을 세우지 않았다
     */
    private static int[] shape(World world, WorldMap.Place place, int cx, int cy, int cz, int radius) {
        Profile p = profile(place);
        int natural = naturalTop(world, cx, cz, cy + 70, cy - 40);
        switch (p) {
            case 봉우리 -> {
                // 봉우리의 중심은 부지 중심에서 **북으로 8칸** — 남쪽(산문)으로 비탈이 흘러내린다
                int px = cx;
                int pz = cz - 8;
                boolean plateau = "고원".equals(place.terrain());
                int top = cy + lift(place, plateau);
                if (natural >= top - 2) {
                    return new int[]{px, pz, natural, 0};   // 자연이 이미 산을 줬다 — 손대지 않는다
                }
                // ★★ v3 — **완만함을 버렸다.** 사용자의 판정: "사람이 굳이 오를 정도로 완만하게 산을
                //   안 만들어도 됨. 올라갈 수 있는 특정 길을 만들어서 그 길로 올라갈 수 있게."
                //
                //   구판은 발치 반경을 높이의 2.4배로 잡아 물매를 1:2.4 로 눕혔다 (사방에서 걸어 오르게
                //   하려고). 그 결과가 **거대한 흙무더기**다 — 검수(경계 급단차 5.4%)는 통과하는데
                //   조감에는 산이 아니었고, 산이 화면을 다 먹어 문파 건물이 보이지도 않았다.
                //   **검수가 통과한다고 좋은 산이 아니다.**
                //
                //   이제 반경은 높이의 **0.9배**다 (물매 1:0.9 — 깎아지른다). 산은 좁고 높다.
                //   사방은 벼랑이어도 좋다 — 오르는 길은 **하나면 된다** ({@link #carveTrail}).
                int height = top - cy;
                // ★★ 산의 크기는 **부지 반경과 무관하다**. 구판은 {@code max(radius + 24, ...)} 였다 —
                //   부지를 키우면 산이 같이 뚱뚱해져 물매가 눕는다. 그것이 흙무더기의 또 다른 뿌리였다.
                //   산은 **제 높이가 정한다**: 반경 = 높이×0.9 (깎아지른다).
                //   부지가 산보다 넓으면 그 바깥은 그냥 들이다 — 산문과 문전이 거기 선다 (그게 옳다).
                int massifR = plateau
                        ? Math.max(SUMMIT_R + 40, (int) (height * 2.0))   // 대지는 넓고 낮다 (여전히)
                        : Math.max(SUMMIT_R + 28, (int) (height * 0.9));  // 봉우리는 좁고 높다
                raiseMassif(world, px, top, pz, massifR, plateau ? 30 : SUMMIT_R, cy);
                return new int[]{px, pz, top, massifR};
            }
            case 중턱단 -> {
                // ★ **산을 세우지 않는다.** 있는 산의 중턱을 필요한 만큼만 고른다 (기존 지형을 살린다).
                //   부지(목책 안)만 다듬고, 그 바깥은 산이 산으로 남는다 — 산채는 산에 붙어 있어야 산채다.
                benchTerrace(world, cx, cy, cz, radius);
                return new int[]{cx, cz, cy, 0};
            }
            case 들 -> {
                levelField(world, cx, cy, cz, Math.max(radius + 8, 40));
                return new int[]{cx, cz, cy, 0};
            }
            case 사막 -> {
                duneField(world, cx, cy, cz, Math.max(radius + 8, 40));
                return new int[]{cx, cz, cy, 0};
            }
            default -> {
                // 강·수향·섬·밀림 — 빚지 않는다
                return new int[]{cx, cz, Math.max(natural, cy), 0};
            }
        }
    }

    /**
     * 사구(砂丘) — <b>사막은 평평한 게 아니라 물결친다</b>.
     *
     * <p>구판은 사막을 몰랐다(switch 에 없어 {@code 그대로} 로 떨어졌다). 그래서 바닐라 사막 바이옴에
     * 얹혀 살았고, 그것이 꺼지자 <b>가욕관이 잔디밭이 됐다</b>.
     *
     * <p>봉우리처럼 <b>세우지 않는다</b>. 저주파 사인 넷을 겹쳐 완만한 물결을 만든다
     * (진폭 ~18 · 파장 60~120 · 물매 0.3/칸 — 벼랑이 생기지 않으므로 어디서든 걸어 들어온다).
     * 난수는 없다: 좌표의 순수 함수다 (같은 자리면 같은 사구).
     *
     * <p><b>부지 한가운데는 평평하다</b> — 0.45R 안쪽은 조성 지면. 사구는 그 바깥에서 일어난다.
     * 물결 위에 관문을 세울 수는 없다 (집이 설 땅은 평평해야 한다).
     */
    private static void duneField(World world, int cx, int cy, int cz, int radius) {
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                double d = dist(x - cx, z - cz);
                if (d > radius) {
                    continue;
                }
                if (naturalGround(world, x, z, cy + 70) == WET_COLUMN) {
                    continue;   // 사막의 물 — 오아시스다. 메우지 않는다
                }
                double flat = radius * 0.45;
                double amp = d <= flat ? 0 : Math.min(1.0, (d - flat) / (radius - flat));
                int target = cy + (int) Math.round(amp * dune(x, z));
                for (int y = target + 1; y <= target + 16; y++) {   // 깎기
                    Block b = world.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (!m.isAir() && (NATURAL.contains(m) || foliage(m))) {
                        b.setType(Material.AIR);
                    }
                }
                for (int y = target; y >= target - SEAL_DEPTH; y--) {   // 메우기
                    Block b = world.getBlockAt(x, y, z);
                    if (!b.getType().isAir() && !b.isLiquid()) {
                        break;
                    }
                    b.setType(y == target ? Material.SAND : Material.SANDSTONE, false);
                }
                sealBelow(world, x, target, z);
            }
        }
    }

    /** 사구의 높이 — 좌표의 순수 함수. 저주파 넷을 겹쳐 결을 만든다 (등고가 반듯해지지 않는다) */
    private static double dune(int x, int z) {
        return Math.sin(x / 47.0) * 6 + Math.sin(z / 61.0) * 5
                + Math.sin((x + z) / 89.0) * 4 + Math.sin((x - z) / 113.0) * 3;
    }

    /**
     * 중턱단 — <b>산에 걸터앉는다</b> (산채·산마을).
     *
     * <p>봉우리를 세우지도, 산을 밀어 평지로 만들지도 않는다. 부지 안만 조성 지면으로 고르되
     * <b>가장자리는 자연 쪽으로 풀어 준다</b>(바깥으로 갈수록 원지형을 따라간다). 그래야
     * 목책 밖이 여전히 산이고, 산길이 채문으로 올라온다.
     */
    private static void benchTerrace(World world, int cx, int cy, int cz, int radius) {
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                double d = dist(x - cx, z - cz);
                if (d > radius) {
                    continue;
                }
                int natural = naturalGround(world, x, z, cy + 70);
                if (natural == WET_COLUMN) {
                    continue;   // 산속의 못 — 메우지 않는다
                }
                // 안쪽(0.6R 까지)은 조성 지면, 바깥은 원지형으로 <b>선형 수렴</b>한다.
                //   부지 안에서 이미 전이가 시작되므로 목책 밖에 벼랑이 생기지 않는다.
                double inner = radius * 0.6;
                double t = d <= inner ? 0 : (d - inner) / (radius - inner);
                int jitter = Math.floorMod(x * 7 + z * 11, 3) - 1;
                int target = (int) Math.round(cy * (1 - t) + natural * t) + (t > 0 ? jitter : 0);
                for (int y = target + 1; y <= target + 18; y++) {   // 깎기
                    Block b = world.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (!m.isAir() && (NATURAL.contains(m) || foliage(m))) {
                        b.setType(Material.AIR);
                    }
                }
                Block top = world.getBlockAt(x, target, z);   // 메우기
                if (top.getType().isAir() || top.isLiquid()) {
                    top.setType(grassOrStone(world, x, target, z));
                }
                sealBelow(world, x, target, z);
            }
        }
    }

    /**
     * 산의 들이 — <b>험산은 높다</b>.
     *
     * <p>사용자: "산의 높이는 더 높여도 될 거 같음". 구판은 36켜였다 — 그건 언덕이지 험산이 아니다.
     * 이제 <b>72켜</b>다 (등록부가 장소마다 고친다: {@code terrain.yml lift:}).
     */
    private static final int LIFT_MOUNTAIN = 72;

    /** 산(험산이 아닌) — 그 절반 */
    private static final int LIFT_HILL = 44;

    /** 대지의 들이 — 고원은 오르는 산이 아니라 <b>올라선 들</b>이다 */
    private static final int LIFT_PLATEAU = 24;

    /** 정상 평탄지의 반경 — 본전(15×13)과 연무장이 앉을 만큼. <b>그 밖은 벼랑이다</b> */
    private static final int SUMMIT_R = 22;

    /** 이 산의 높이 — 등록부가 먼저({@code terrain.yml lift:}), 없으면 지형이 정한다 */
    private static int lift(WorldMap.Place place, boolean plateau) {
        Integer registered = liftRegistry.get(place.id());
        if (registered != null) {
            return registered;
        }
        if (plateau) {
            return LIFT_PLATEAU;
        }
        String terrain = place.terrain() == null ? "" : place.terrain();
        return "험산".equals(terrain) || "설산".equals(terrain) ? LIFT_MOUNTAIN : LIFT_HILL;
    }

    /**
     * 봉우리 v3 — <b>산이 산으로 보이게</b>.
     *
     * <p>사용자가 조감을 보고 말했다: "산을 이상하게 만듦 — <b>점이 찍힌 느낌</b>이고
     * <b>제대로 채워지지 않음</b>. 사람이 굳이 오를 정도로 완만하게 산을 안 만들어도 됨.
     * <b>올라갈 수 있는 특정 길</b>을 만들어서 그 길로 올라갈 수 있게 해도 됨.
     * 산의 높이는 더 높여도 될 거 같음."
     *
     * <p><b>검수가 통과한다고 좋은 산이 아니다.</b> 경계 급단차 5.4% 를 통과한 그 산은
     * 조감에서 <b>거대한 흙무더기</b>였다. 세 가지 병을 각각 고쳤다:
     *
     * <ol>
     *   <li><b>점무늬</b> — 구판의 요철은 {@code floorMod(x*11+z*7,7)-3} 이었다. <b>파장이 한 칸</b>이라
     *       이웃끼리 아무 상관이 없다 — 바위가 <b>낱알로 흩어졌다</b>. 그 위에 2칸 계단 양자화
     *       ({@code y - floorMod(y+phase,2)})가 얼룩을 얹었다. <b>둘 다 버렸다.</b>
     *       이제 요철은 파장 11~23칸의 저주파 결이다 —
     *       <b>바위는 덩어리로 있지 낱알로 흩어지지 않는다.</b></li>
     *   <li><b>구멍</b> ("제대로 채워지지 않음") — 구판의 채움 고리는 단단한 블록을 만나면 {@code break}
     *       했다. 비탈 속에 공기 주머니가 있으면 그 위만 덮고 <b>아래를 비운 채</b> 지나갔다.
     *       환경 검수 ⑤(부유 블록)는 그것을 못 본다 — <b>검수가 못 보는 결함</b>이었다.
     *       이제 <b>자연 지면까지 전부 채운다. break 가 없다.</b></li>
     *   <li><b>흙무더기</b> — 물매를 1:2.4 로 눕혔더니(사방에서 걷게 하려고) 산이 무더기가 됐다.
     *       이제 <b>1:0.9</b> 다. 오목한 비탈({@code t^0.85})이라 <b>정상 부근이 깎아지른다</b>.
     *       사방이 벼랑이어도 좋다 — <b>오르는 길은 하나면 된다</b> ({@link #carveTrail}).</li>
     * </ol>
     *
     * @param baseY 산이 서는 들의 높이 — <b>여기까지 채운다</b>
     */
    private static void raiseMassif(World world, int cx, int topY, int cz,
                                    int radius, int flatR, int baseY) {
        long seed = Math.floorMod(31L * cx + 17L * cz, 1_000_003L);
        double phase = (seed % 628) / 100.0;
        int height = topY - baseY;

        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                double d = dist(x - cx, z - cz);
                if (d > radius) {
                    continue;
                }
                double t = slopeT(x, z, cx, cz, d, radius, flatR);
                int y = (int) Math.round(topY - Math.pow(t, 0.85) * height
                        + crag(x, z, phase) * cragFactor(t));
                int ground = naturalTop(world, x, z, topY + 40, baseY - 60);
                if (y <= ground) {
                    continue;   // 발치가 자연 지면에 닿았다 — 그 아래는 산의 몫이다 (파내지 않는다)
                }
                Material skin = rockSkin(x, z, t,
                        slopeAt(x, z, cx, cz, radius, flatR, height, phase));
                // ── 채운다 — **자연 지면까지 전부**. break 하지 않는다 (그것이 구멍의 원인이었다) ──
                for (int yy = y; yy > ground; yy--) {
                    Block b = world.getBlockAt(x, yy, z);
                    Material here = b.getType();
                    if (here.isSolid() && !NATURAL.contains(here)) {
                        continue;   // 사람이 지은 것 — 지나친다 (그건 건축 계층의 것이다)
                    }
                    b.setType(yy == y ? skin
                            : yy > y - 4 ? Material.STONE
                            : Material.DEEPSLATE, false);
                }
                for (int yy = y + 1; yy <= topY + 20; yy++) {   // 봉우리 위 허공을 비운다
                    Block b = world.getBlockAt(x, yy, z);
                    if (!b.getType().isAir()) {
                        b.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    /**
     * 정상(0) ~ 발치(1) — <b>능선 왜곡을 먹인</b> 유효 거리.
     * 등고가 완전한 원이면 그것은 산이 아니라 <b>기단</b>이다. 방위마다 반경이 다르다 (산줄기 3·5·7).
     */
    private static double slopeT(int x, int z, int cx, int cz, double d, int radius, int flatR) {
        double ang = Math.atan2(z - cz, x - cx);
        double ridge = Math.sin(ang * 3 + 0.7) * 0.10 + Math.sin(ang * 5 - 1.3) * 0.06
                + Math.sin(ang * 7 + 2.1) * 0.035;
        double rEff = radius * (1 + ridge);
        double fEff = flatR * (1 + ridge * 0.5);
        if (d <= fEff) {
            return 0;
        }
        return Math.max(0, Math.min(1, (d - fEff) / Math.max(1, rEff - fEff)));
    }

    /**
     * 바위의 결 — <b>파장 11~23칸</b>. 구판은 파장이 <b>한 칸</b>이라 점무늬가 됐다.
     * 진폭 ~7에 파장이 그 두세 배이므로 <b>바위 하나가 3~6칸 덩어리</b>로 읽힌다.
     */
    private static double crag(int x, int z, double phase) {
        return Math.sin(x / 13.0 + phase) * Math.cos(z / 11.0 - phase) * 3.5
                + Math.sin((x + z) / 23.0 + phase) * 2.5
                + Math.sin((x - z) / 17.0) * 1.5;
    }

    /**
     * 바위 결의 세기 — <b>정상은 매끈하고 중턱이 가장 험하다</b>.
     *
     * <p>구판(v3 초안)은 계수가 정상에서 1.0 이었다 — 그래서 <b>봉우리 꼭대기가 ±7칸으로 울퉁불퉁했고</b>,
     * 본전이 앉을 자리가 없었다({@code peakY} 가 거짓말을 했다). 정상은 <b>단(壇)</b>이어야 한다.
     * 발치도 눅여야 전이대가 자연으로 스민다.
     */
    private static double cragFactor(double t) {
        double ramp = Math.max(0, Math.min(1, (t - 0.05) / 0.25));   // 정상 부근은 0 (매끈한 단)
        return ramp * (0.5 + 0.5 * (1 - t));                          // 중턱이 가장 험하다
    }

    /** 그 자리의 물매(칸/칸) — 자재를 고르는 데 쓴다 (절벽은 암반, 발치는 너덜) */
    private static double slopeAt(int x, int z, int cx, int cz,
                                  int radius, int flatR, int height, double phase) {
        double a = surfH(x + 2, z, cx, cz, radius, flatR, height, phase);
        double b = surfH(x - 2, z, cx, cz, radius, flatR, height, phase);
        double c = surfH(x, z + 2, cx, cz, radius, flatR, height, phase);
        double e = surfH(x, z - 2, cx, cz, radius, flatR, height, phase);
        return Math.max(Math.abs(a - b), Math.abs(c - e)) / 4.0;
    }

    private static double surfH(int x, int z, int cx, int cz,
                                int radius, int flatR, int height, double phase) {
        double d = dist(x - cx, z - cz);
        double t = slopeT(x, z, cx, cz, d, radius, flatR);
        return -Math.pow(t, 0.85) * height + crag(x, z, phase) * cragFactor(t);
    }

    /**
     * 바위의 결(자재) — <b>능선·절벽·너덜·암반을 가른다</b>.
     *
     * <p>구판은 STONE/ANDESITE 를 좌표 해시로 점점이 섞었다 — 그것은 결이 아니라 <b>노이즈</b>다.
     * 자재는 <b>지형이 정해야</b> 한다: 깎아지른 데는 암반이 드러나고, 발치에는 너덜(스크리)이 쌓이고,
     * 능선에는 굳은 바위가 솟는다. 그리고 <b>덩어리로</b> 나온다 (파장 9~19칸의 패치).
     */
    private static Material rockSkin(int x, int z, double t, double slope) {
        double patch = Math.sin(x / 9.0) * Math.sin(z / 7.0) + Math.sin((x + z) / 19.0) * 0.7;
        if (slope > 1.5) {
            return patch > 0.2 ? Material.STONE : Material.DEEPSLATE;   // 절벽 — 암반이 드러난다
        }
        if (t > 0.82 && patch > 0.1) {
            return Material.GRAVEL;      // 너덜(스크리) — 발치에 부스러기가 쌓인다
        }
        if (patch > 0.85) {
            return Material.ANDESITE;    // 암반 노출 — 덩어리로
        }
        if (patch < -0.9) {
            return Material.TUFF;        // 응회암 — 결의 변주
        }
        return Material.STONE;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 등반로 — **오르는 길은 하나면 된다**
    // ═══════════════════════════════════════════════════════════════════

    /** 등반로의 반폭 — 3칸 폭의 좁은 산길 (램프의 5칸과 다르다. 이건 길이지 도로가 아니다) */
    private static final int TRAIL_HALF = 1;

    /**
     * 등반로 — <b>사용자의 판정 그대로</b>.
     *
     * <blockquote>"사람이 굳이 오를 정도로 완만하게 산을 안 만들어도 됨 —
     * <b>올라갈 수 있는 특정 길을 만들어서 그 길로 올라갈 수 있게</b> 해도 됨"</blockquote>
     *
     * <p>구판은 <b>산 전체</b>를 완만하게 만들어 사방에서 걷게 했다. 그래서 흙무더기가 됐다.
     * 이제 산은 깎아지르고, <b>길 하나</b>가 그것을 감아 오른다.
     *
     * <p>모양: 발치에서 정상까지 <b>굽이치며 감아 오르는 나선</b>(switchback). 한 칸에 한 칸 넘게
     * 오르지 않는다(걸어 오른다). 길이는 높이에 맞춰 늘어난다 — 72켜를 오르려면 그만큼 감는다.
     * 자재는 놓지 않는다: 바위를 깎아 낸 <b>턱</b>일 뿐이다 (계단·석등·포석은 건축 계층의 것이다).
     *
     * @return 길이 시작되는 방위 — 부지 사양의 진입 방향이 된다
     */
    private static BlockFace carveTrail(World world, int px, int pz, int peakY,
                                        int massifR, int baseY, int flatR) {
        long seed = Math.floorMod(31L * px + 17L * pz, 1_000_003L);
        // 길머리는 **남쪽**에서 든다 (등록부: 산문은 남쪽에 선다). 좌표 해시로 조금 흔든다
        double a0 = Math.PI / 2 + (Math.floorMod(seed, 40) - 20) / 100.0;
        int rise = peakY - baseY;
        double turns = 1.15;                                  // 한 바퀴 조금 더 — 굽이가 보인다
        int steps = Math.max(rise * 2, 180);                  // 물매 ≤ 0.5/칸 — 넉넉히 걷는다

        int footX = px + (int) Math.round(Math.cos(a0) * (massifR - 2));
        int footZ = pz + (int) Math.round(Math.sin(a0) * (massifR - 2));
        int footY = naturalTop(world, footX, footZ, peakY + 20, baseY - 40);

        int prevY = footY;
        for (int i = 0; i <= steps; i++) {
            double s = i / (double) steps;                    // 0 = 발치, 1 = 정상
            double r = (massifR - 2) * (1 - s) + (flatR - 4) * s;
            double ang = a0 + s * turns * 2 * Math.PI;
            int x = px + (int) Math.round(Math.cos(ang) * r);
            int z = pz + (int) Math.round(Math.sin(ang) * r);
            int y = (int) Math.round(footY + (peakY - footY) * s);
            if (y - prevY > 1) {
                y = prevY + 1;      // **한 칸에 한 칸** — 걸어 오른다 (도달성 검사가 이것을 잰다)
            }
            prevY = y;

            for (int dx = -TRAIL_HALF - 1; dx <= TRAIL_HALF + 1; dx++) {
                for (int dz = -TRAIL_HALF - 1; dz <= TRAIL_HALF + 1; dz++) {
                    if (dist(dx, dz) > TRAIL_HALF + 0.6) {
                        continue;
                    }
                    for (int yy = y + 1; yy <= y + 5; yy++) {   // 머리 위를 깎는다 (바위를 판다)
                        Block b = world.getBlockAt(x + dx, yy, z + dz);
                        Material m = b.getType();
                        if (!m.isAir() && (NATURAL.contains(m) || foliage(m))) {
                            b.setType(Material.AIR, false);
                        }
                    }
                    Block floor = world.getBlockAt(x + dx, y, z + dz);   // 딛는 턱
                    if (floor.getType().isAir() || floor.isLiquid()) {
                        floor.setType(Material.STONE, false);
                    }
                    sealBelow(world, x + dx, y, z + dz);                 // 계약 ① — 길 밑도 단단하다
                }
            }
        }
        // 길머리를 자연 지면으로 잇는다 — 산 밖에서 길머리까지 걸어올 수 있어야 한다
        BlockFace face = Math.abs(Math.cos(a0)) > Math.abs(Math.sin(a0))
                ? (Math.cos(a0) > 0 ? BlockFace.EAST : BlockFace.WEST)
                : (Math.sin(a0) > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
        return face;
    }

    /**
     * 들 — 평지를 요구하는데 땅이 울퉁불퉁하면 고른다. 벼랑을 깎는 게 아니라 <b>발치를 메운다</b>:
     * 낮은 데를 흙으로 채우고 높은 데만 깎는다 (파낸 자국은 사람이 산 흔적이 아니라 상처다).
     *
     * <p>물은 건드리지 않는다 — 젖은 열은 그대로 둔다 (강가의 마을은 강가에 있어야 한다).
     */
    private static void levelField(World world, int cx, int topY, int cz, int radius) {
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                double d = dist(x - cx, z - cz);
                if (d > radius) {
                    continue;
                }
                if (naturalGround(world, x, z, topY + 40) == WET_COLUMN) {
                    continue;   // 강·호수 — 들을 고른다고 물을 메우지 않는다
                }
                int edge = Math.floorMod(x * 5 + z * 3, 3);      // 가장자리는 자연으로 풀어 준다
                int target = topY - (d > radius - 6 ? edge : 0);
                for (int y = target + 1; y <= target + 10; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (!m.isAir() && (NATURAL.contains(m) || foliage(m))) {
                        b.setType(Material.AIR);
                    }
                }
                for (int y = target; y >= target - SEAL_DEPTH; y--) {
                    Block b = world.getBlockAt(x, y, z);
                    if (!b.getType().isAir() && !b.isLiquid()) {
                        break;
                    }
                    b.setType(y == target ? grassOrStone(world, x, target, z) : Material.DIRT);
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 표층 덧입힘 — 눈과 모래 (W1·W2·W4)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 표층을 입힌다 — <b>바이옴이 주던 것을 우리가 준다</b>.
     *
     * <p>맨 마지막에 돈다: 봉우리·사구·전이대·램프가 <b>전부 놓인 뒤</b>에 그 위를 덮는다.
     * 그래야 산의 발치도, 걸어 오르는 길도 같은 표층을 입는다 (길만 잔디인 설산은 우습다).
     *
     * <p><b>설원</b> — 설선 위에만 눈이 얹힌다. 높이 올라갈수록 두꺼워지고, 아주 높은 데는 얼음이 섞인다.
     * 설선 아래는 <b>돌과 흙 그대로 둔다</b> — 설산의 그림은 흰 산이 아니라 <b>돌과 눈이 갈라지는 선</b>이다.
     * 물이 있으면 표면을 얼린다.
     *
     * <p><b>사막</b> — 표층은 모래, 그 아래 다섯 칸은 사암. 자갈이 드문드문 섞인다
     * (사막은 모래만이 아니다 — 자갈의 결이 있어야 사막으로 읽힌다). <b>식생은 남기지 않는다.</b>
     *
     * @param radius 덮는 반경 — <b>봉우리를 세웠으면 산의 발치까지</b> 덮어야 한다
     */
    static void applySurface(World world, int cx, int cz, int radius, Surface s, int snowline, int yHint) {
        if (s == Surface.자연) {
            return;   // 중원 — 자연이 준 표층이 옳다
        }
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                if (dist(x - cx, z - cz) > radius) {
                    continue;
                }
                int g = naturalGround(world, x, z, yHint + 90);
                if (g == WET_COLUMN) {
                    if (s == Surface.설원) {
                        freezeTop(world, x, z, yHint);   // 얼어붙은 못 — 설원의 물은 얼음이다
                    }
                    continue;   // 물 — 모래로 메우지 않는다 (오아시스·강은 물이다)
                }
                if (s == Surface.사막) {
                    sandColumn(world, x, g, z);
                } else {
                    snowColumn(world, x, g, z, snowline);
                }
            }
        }
    }

    /** 모래 기둥 — 표층 모래(붉은 모래가 드문드문) · 그 아래 사암 · 자갈의 결 */
    private static void sandColumn(World world, int x, int g, int z) {
        Block top = world.getBlockAt(x, g, z);
        if (!SOLID_NATURAL.contains(top.getType())) {
            return;   // 사람이 지은 것 — 덮지 않는다 (건축 계층의 바닥을 모래로 만들지 않는다)
        }
        // 자갈의 결 — 30칸에 하나꼴. 사막은 모래만이 아니다
        Material surface = Math.floorMod(x * 31 + z * 17, 29) == 0 ? Material.GRAVEL
                : Math.floorMod(x * 13 + z * 7, 11) == 0 ? Material.RED_SAND : Material.SAND;
        top.setType(surface, false);   // 물리 갱신 없이 — 모래가 우수수 떨어지면 안 된다
        for (int i = 1; i <= 5; i++) {
            Block b = world.getBlockAt(x, g - i, z);
            if (SOLID_NATURAL.contains(b.getType())) {
                b.setType(Material.SANDSTONE, false);
            }
        }
    }

    /**
     * 눈 기둥 — <b>설선 위에만</b>. 높이 올라갈수록 두꺼워진다.
     *
     * <p>설선 바로 위는 눈이 얇게 앉고(돌이 비친다), 한참 위는 두껍고, 아주 높은 데는 얼음이 섞인다.
     * 좌표 해시로 설선 자체를 ±3 흔든다 — <b>자로 그은 설선은 설선이 아니다</b>.
     */
    private static void snowColumn(World world, int x, int g, int z, int snowline) {
        int wobble = Math.floorMod(x * 7 + z * 11, 7) - 3;   // 설선의 들쭉날쭉 (±3)
        if (g < snowline + wobble) {
            return;   // 설선 아래 — 돌과 흙 그대로. **돌과 눈이 갈라지는 선이 설산이다**
        }
        Block top = world.getBlockAt(x, g, z);
        if (!SOLID_NATURAL.contains(top.getType())) {
            return;   // 사람이 지은 것 — 덮지 않는다
        }
        top.setType(Material.SNOW_BLOCK, false);
        int depth = Math.min(3, (g - snowline) / 12 + 1);   // 높을수록 두껍다
        for (int i = 1; i <= depth; i++) {
            Block b = world.getBlockAt(x, g - i, z);
            if (!SOLID_NATURAL.contains(b.getType())) {
                break;
            }
            // 아주 높은 데는 만년빙 — 다만 **표층에는 얼음을 쓰지 않는다**
            //   (ICE/PACKED_ICE 는 '젖은 열'로 읽혀 건축 마스크에서 빠진다. 정상에 집을 못 짓게 된다)
            boolean glacier = g >= snowline + 30 && i >= 2 && Math.floorMod(x * 5 + z * 3, 4) == 0;
            b.setType(glacier ? Material.PACKED_ICE : Material.SNOW_BLOCK, false);
        }
    }

    /** 언 물 — 설원의 못은 얼음이다. 표면 한 켜만 (밑은 물로 남는다 — 물은 흘러야 물이다) */
    private static void freezeTop(World world, int x, int z, int yHint) {
        for (int y = yHint + 40; y >= yHint - 20; y--) {
            Block b = world.getBlockAt(x, y, z);
            if (b.getType() == Material.WATER) {
                b.setType(Material.ICE, false);
                return;
            }
            if (!b.getType().isAir() && b.getType() != Material.SNOW) {
                return;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 오아시스 (W3) — 모래 한가운데 진짜 물
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 오아시스 — <b>서역 설계의 심장</b>.
     *
     * <p>"오아시스를 놓치면 죽는다"(routes.sangro_silk.survival)가 <b>지형에서 읽혀야</b> 한다.
     * 그러려면 모래 한가운데 <b>진짜 물</b>이 있어야 한다.
     *
     * <p>지금까지 {@code TerrainForge} 는 물을 <b>만들지 않았다</b> ({@link #tidyWater} 는 정리만 한다).
     * 오아시스가 그 첫 예외다 — 그리고 <b>바다는 여전히 만들지 않는다</b>(섬은 자연에 맡긴다. 해남파의 선례).
     * 못 하나는 만들 수 있고 바다는 못 만든다 — 그 선을 여기서 긋는다.
     *
     * <p><b>경계는 급격하다.</b> 오아시스는 그라데이션이 아니다 —
     * <b>물이 닿는 데까지가 초록이고 그 밖은 모래다.</b> 그 대비가 오아시스다.
     *
     * <p>수면은 조성 지면 <b>아래</b>(cy-1)에 둔다. 그래야 환경 검수 ②가 이 물을
     * "산 위의 웅덩이"(고인 물 = 지면 +3 초과)로 세지 않는다 — <b>오아시스는 웅덩이가 아니라 못이다.</b>
     */
    static void carveOasis(World world, int cx, int cy, int cz, int radius) {
        int pond = Math.max(14, radius / 2);      // 못 — 부지의 절반
        int green = pond + 12;                    // 녹지 띠 — 물이 닿는 데까지

        for (int x = cx - green; x <= cx + green; x++) {
            for (int z = cz - green; z <= cz + green; z++) {
                double d = dist(x - cx, z - cz);
                if (d > green) {
                    continue;
                }
                if (d <= pond) {
                    // 못 — 그릇 모양. 가운데가 깊고 가장자리가 얕다 (수직 벽의 못은 우물이다)
                    int depth = 1 + (int) Math.round(5 * (1 - d / (double) pond));
                    int floorY = cy - 1 - depth;
                    for (int y = cy + 14; y >= floorY + 1; y--) {   // 파낸다
                        Block b = world.getBlockAt(x, y, z);
                        Material m = b.getType();
                        if (!m.isAir() && (NATURAL.contains(m) || foliage(m))) {
                            b.setType(Material.AIR, false);
                        }
                    }
                    world.getBlockAt(x, floorY, z).setType(Material.SAND, false);   // 못 바닥
                    sealBelow(world, x, floorY, z);                                  // 계약 ① — 못 밑도 단단하다
                    for (int y = floorY + 1; y <= cy - 1; y++) {                     // 물을 채운다 (수면 = cy-1)
                        world.getBlockAt(x, y, z).setType(Material.WATER, false);
                    }
                } else {
                    // 녹지 — 물가의 풀. **경계는 급격하다** (여기까지가 초록이고 그 밖은 모래다)
                    int g = naturalGround(world, x, z, cy + 40);
                    if (g == WET_COLUMN) {
                        continue;
                    }
                    Block top = world.getBlockAt(x, g, z);
                    if (SOLID_NATURAL.contains(top.getType())) {
                        top.setType(Material.GRASS_BLOCK, false);
                    }
                    for (int i = 1; i <= 3; i++) {
                        Block b = world.getBlockAt(x, g - i, z);
                        if (b.getType() == Material.SANDSTONE || b.getType() == Material.SAND) {
                            b.setType(Material.DIRT, false);   // 풀 밑은 흙이다
                        }
                    }
                }
            }
        }
        // 대추야자 대용 — 아카시아. 못을 둘러 여섯 그루 (좌표 해시 — 같은 오아시스면 같은 나무)
        for (int i = 0; i < 6; i++) {
            double a = Math.PI * i / 3.0 + Math.floorMod(cx * 7L + cz * 13L, 6) * 0.17;
            int tx = cx + (int) Math.round(Math.cos(a) * (pond + 5));
            int tz = cz + (int) Math.round(Math.sin(a) * (pond + 5));
            palm(world, tx, cz == tz && cx == tx ? cy : naturalGround(world, tx, tz, cy + 40), tz, cy);
        }
    }

    /** 종려(대용: 아카시아) — 줄기 다섯에 잎 한 겹. 사막의 나무는 성글다 */
    private static void palm(World world, int x, int g, int z, int cy) {
        if (g == WET_COLUMN || Math.abs(g - cy) > 8) {
            return;
        }
        int h = 5 + Math.floorMod(x * 3 + z * 5, 3);
        for (int i = 1; i <= h; i++) {
            world.getBlockAt(x, g + i, z).setType(Material.ACACIA_LOG, false);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > 2 || (dx == 0 && dz == 0)) {
                    continue;
                }
                Block b = world.getBlockAt(x + dx, g + h, z + dz);
                if (b.getType().isAir()) {
                    b.setType(Material.ACACIA_LEAVES, false);
                }
            }
        }
        Block crown = world.getBlockAt(x, g + h + 1, z);
        if (crown.getType().isAir()) {
            crown.setType(Material.ACACIA_LEAVES, false);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 부지를 비운다 — 초목 · 지난 조성이 남긴 것
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 부지 위를 걷는다 — 나무·풀·눈, <b>그리고 지난 조성이 남긴 것</b>.
     *
     * <p>재조성은 덮어쓰기다. 나무만 걷고 집을 안 걷으면 옛 지붕이 새 마당 위에 떠 있는다
     * (조감이 그것을 잡았다 — 채문 앞에 유령 지붕이 남아 있었다).
     * <b>산은 그대로 둔다</b> — 걷는 것은 지면 위의 <b>사람이 지은 것과 초목</b>뿐이다.
     */
    static void clearSurface(World world, int cx, int cy, int cz, int radius) {
        for (int x = cx - radius - 2; x <= cx + radius + 2; x++) {
            for (int z = cz - radius - 2; z <= cz + radius + 2; z++) {
                if (dist(x - cx, z - cz) > radius + 2) {
                    continue;
                }
                // **지면 위만** 걷는다. 아래로 내려가면 광석(자연이지만 NATURAL 목록에 없다)을 파내
                //   지하에 구멍을 낸다 — 그것은 조성이 아니라 도굴이다.
                // 훑는 천장은 **실제 지표**로 묶는다 — 부지 반경이 100을 넘으면서 (봉우리 산문이 들에 서므로)
                //   고정 천장(cy+60)으로 훑으면 빈 하늘을 수백만 칸 읽는다. 메인 스레드가 그만큼 멈춘다.
                int ceiling = Math.min(cy + 60, world.getHighestBlockYAt(x, z) + 1);
                for (int y = ceiling; y >= cy + 1; y--) {
                    Block b = world.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (m.isAir() || NATURAL.contains(m) || m.name().endsWith("_ORE")) {
                        continue;   // 자연 지면 · 광석 · 물 — 남긴다 (물은 tidyWater 가 본다)
                    }
                    b.setType(Material.AIR);   // 지난 조성이 남긴 것 · 초목
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 단(段) — 건축 계층이 요청하는 평평한 바닥
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 단(段) — <b>건축 계층은 땅을 만지지 않는다. 대신 여기에 요청한다.</b>
     *
     * <p>집을 앉히려면 평평한 바닥이 있어야 하고, 비탈에서는 축대가 받쳐야 한다. 그 흙일은 땅의 일이다.
     * 건축 계층은 "여기 이만한 단을 달라"고 말하고, 이 함수가 판다:
     * <ul>
     *   <li>위를 비운다 (깎기)</li>
     *   <li>바닥을 깐다 (자재는 건축이 고른다 — 산채는 다진 흙, 도관은 마감 안산암)</li>
     *   <li>밑을 받친다 (축대) — 허공이면 돌로 채워 올린다</li>
     *   <li>{@link #sealBelow} — <b>계약 ①</b>. 단 아래 여섯 칸은 단단하다</li>
     *   <li>{@link SiteSpec} 의 지면·마스크를 갱신한다 — 이제 건축 계층은 여기에 지을 수 있다</li>
     * </ul>
     */
    static void terrace(World world, SiteSpec spec, int cx, int cz, int y,
                        int halfW, int halfD, Material floor) {
        for (int x = cx - halfW; x <= cx + halfW; x++) {
            for (int z = cz - halfD; z <= cz + halfD; z++) {
                for (int yy = y + 1; yy <= y + 14; yy++) {
                    Block b = world.getBlockAt(x, yy, z);
                    if (!b.getType().isAir()) {
                        b.setType(Material.AIR);
                    }
                }
                world.getBlockAt(x, y, z).setType(floor);
                boolean edge = x == cx - halfW || x == cx + halfW || z == cz - halfD || z == cz + halfD;
                for (int yy = y - 1; yy >= y - 16; yy--) {   // 축대 — 허공·물이면 돌로 받친다
                    Block b = world.getBlockAt(x, yy, z);
                    if (!b.getType().isAir() && !b.isLiquid()) {
                        break;
                    }
                    b.setType(edge ? Material.STONE_BRICKS : Material.COBBLESTONE);
                }
                sealBelow(world, x, y, z);
                int i = spec.idx(x, z);
                if (i >= 0) {
                    spec.surface()[i] = y;
                    spec.buildable()[i] = true;
                }
            }
        }
    }

    /** 둥근 단 — 산채·마당처럼 담이 둥근 부지 */
    static void terraceRound(World world, SiteSpec spec, int cx, int cz, int y, int r, Material floor) {
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                if (dist(x - cx, z - cz) > r) {
                    continue;
                }
                for (int yy = y + 1; yy <= y + 8; yy++) {
                    Block b = world.getBlockAt(x, yy, z);
                    if (!b.getType().isAir()) {
                        b.setType(Material.AIR);
                    }
                }
                world.getBlockAt(x, y, z).setType(floor);
                sealBelow(world, x, y, z);
                int i = spec.idx(x, z);
                if (i >= 0) {
                    spec.surface()[i] = y;
                    spec.buildable()[i] = true;
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 실측 — 마스크 · 진입 · 수변 · 경사
    // ═══════════════════════════════════════════════════════════════════

    private static SiteSpec survey(World world, WorldMap.Place place, int cx, int cy, int cz,
                                   int radius, int peakX, int peakZ, int peakY) {
        int w = radius * 2 + 1;
        int[] surface = new int[w * w];
        boolean[] buildable = new boolean[w * w];

        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int x = cx + dx;
                int z = cz + dz;
                int i = (dz + radius) * w + (dx + radius);
                int g = naturalGround(world, x, z, cy + 70);
                surface[i] = g;
                if (g != WET_COLUMN) {
                    lo = Math.min(lo, g);
                    hi = Math.max(hi, g);
                }
            }
        }

        // 건축 가능 마스크 — **물 위에 지을 자리를 주지 않는다**(계약 ③) + 급경사를 뺀다
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int i = (dz + radius) * w + (dx + radius);
                if (surface[i] == WET_COLUMN) {
                    continue;   // 물 — 건축 금지. 나루는 건축 계층이 물가에 놓는다
                }
                if (dist(dx, dz) > radius) {
                    continue;   // 부지 밖 (마스크는 원이다)
                }
                boolean steep = false;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = dx + d[0];
                    int nz = dz + d[1];
                    if (Math.abs(nx) > radius || Math.abs(nz) > radius) {
                        continue;
                    }
                    int ni = (nz + radius) * w + (nx + radius);
                    if (surface[ni] == WET_COLUMN) {
                        continue;   // 물가는 급경사가 아니다 — 다만 그 칸이 못 지을 뿐이다
                    }
                    if (Math.abs(surface[ni] - surface[i]) > BUILD_MAX_STEP) {
                        steep = true;
                        break;
                    }
                }
                buildable[i] = !steep;
            }
        }

        int relief = (lo == Integer.MAX_VALUE) ? 0 : hi - lo;
        int slope = relief < 6 ? 0 : relief < 14 ? 1 : relief < 28 ? 2 : 3;

        List<BlockFace> approaches = approaches(world, cx, cy, cz, radius);
        List<BlockFace> waterSides = waterSides(world, cx, cy, cz, radius);
        boolean waterfront = !waterSides.isEmpty();

        return new SiteSpec(place.id(), place.name(), place.terrain(), world.getName(),
                cx, cz, radius, cy, peakX, peakZ, peakY,
                surface, buildable, approaches, waterfront, waterSides, slope, relief);
    }

    /**
     * 진입 방향 — <b>바깥에서 걸어 들어올 수 있는가</b> (계약 ②의 검산).
     *
     * <p>네 방위에서 부지 밖 {@code radius + 24} 칸부터 중심까지 한 칸씩 걸어 본다.
     * 한 걸음에 세 칸 넘게 뛰어야 하는 자리(사람은 못 넘는다)나 물을 건너야 하는 자리가 있으면 그 방위는 막혔다.
     *
     * <p>산은 한 방위만 열려도 정상이다 (산채는 한 길로 있는 것이 산채다). 들은 사방이 열려야 한다 —
     * 그 판정은 건축 계층·검수의 몫이고, 여기서는 <b>사실만</b> 돌려준다.
     */
    private static List<BlockFace> approaches(World world, int cx, int cy, int cz, int radius) {
        List<BlockFace> out = new ArrayList<>();
        BlockFace[] faces = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
        int[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int f = 0; f < 4; f++) {
            boolean ok = true;
            int prev = Integer.MIN_VALUE;
            for (int d = radius + 24; d >= 0; d--) {
                int x = cx + dirs[f][0] * d;
                int z = cz + dirs[f][1] * d;
                int g = naturalGround(world, x, z, cy + 70);
                if (g == WET_COLUMN) {
                    ok = false;   // 물을 건너야 한다 — 걸어 들어올 수 없다 (나루가 필요하다)
                    break;
                }
                if (prev != Integer.MIN_VALUE && Math.abs(g - prev) > BUILD_MAX_STEP) {
                    ok = false;   // 벼랑
                    break;
                }
                prev = g;
            }
            if (ok) {
                out.add(faces[f]);
            }
        }
        return out;
    }

    /**
     * 물가 방향 — <b>마을이 물가면 물가라고 말한다</b>.
     *
     * <p>부지 둘레(반경 ~ 반경+20)의 젖은 열을 방위별로 센다. 한 방위에 40칸 넘게 물이 있으면
     * 그쪽이 물가다. 건축 계층은 이 값으로 <b>나루·다리·선착장</b>을 놓는다 (그것은 집의 일이다).
     */
    private static List<BlockFace> waterSides(World world, int cx, int cy, int cz, int radius) {
        int[] count = new int[4];   // 북 동 남 서
        for (int x = cx - radius - 20; x <= cx + radius + 20; x += 2) {
            for (int z = cz - radius - 20; z <= cz + radius + 20; z += 2) {
                double d = dist(x - cx, z - cz);
                if (d > radius + 20) {
                    continue;
                }
                if (naturalGround(world, x, z, cy + 70) != WET_COLUMN) {
                    continue;
                }
                int dx = x - cx;
                int dz = z - cz;
                if (Math.abs(dx) > Math.abs(dz)) {
                    count[dx > 0 ? 1 : 3]++;
                } else {
                    count[dz > 0 ? 2 : 0]++;
                }
            }
        }
        List<BlockFace> out = new ArrayList<>();
        BlockFace[] faces = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
        for (int i = 0; i < 4; i++) {
            if (count[i] >= 40) {
                out.add(faces[i]);
            }
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 동굴 — 자연 동굴을 껐으니 우리가 판다
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 동굴 원형 — <b>어둠이 동굴의 값이다</b>.
     *
     * <p>자연 동굴은 통제할 수 없어서 껐다(사용자 판정). 그런데 등록부는 동굴을 요구한다:
     * 마교 전초는 "동굴과 석실 — 사람이 사는 흔적을 지운다"고 적혀 있고, 기연은 폐사당 지하에 앉고,
     * 녹림은 굴에 짐을 쟁인다. <b>그래서 우리가 판다.</b> 원형이 넷이다.
     *
     * <p>공통 규약:
     * <ul>
     *   <li><b>입구는 비탈에 뚫린다</b> — 평지에 구멍이 나 있으면 그건 함정이지 동굴이 아니다.
     *       부지 둘레에서 <b>바깥으로 내려가는 비탈</b>을 찾아 그 살을 파고 들어간다</li>
     *   <li><b>광원이 없다</b> — 횃불 하나 놓지 않는다. 어둠을 들고 들어가는 것이 동굴의 값이다</li>
     *   <li><b>물이 새지 않는다</b> — 판 자리의 껍질에 물·용암이 닿으면 돌로 막는다 (대수층은 남아 있다)</li>
     *   <li><b>결정론</b> — 입구 자리·통로의 굽이·방의 요철이 전부 좌표 해시다. 두 번 파도 같은 굴이다</li>
     * </ul>
     */
    enum CaveKind {
        /** 은신처 — 마교 전초. 입구가 좁고 통로가 길다. 안쪽에 석실 둘 (사람이 사는 흔적을 지운다) */
        은신처(2, 44, 18, 7, 4, 7, true),
        /** 기연굴 — 폐사당 지하·옛 문파 터. 짧은 통로 + 방 하나. 안쪽 끝에 무언가 있다 */
        기연굴(1, 24, 11, 5, 3, 5, false),
        /** 산적굴 — 녹림. 입구가 넓다(짐을 들여야 한다). 짧고 굵다 */
        산적굴(2, 18, 7, 8, 4, 8, false),
        /** 제단굴 — 혈교 폐사. 깊이 내려간다. 방이 넓고 천장이 높다 */
        제단굴(2, 30, 22, 9, 5, 9, false);

        final int passHalf;      // 통로 반폭 (1 = 3칸 · 2 = 5칸)
        final int length;        // 통로 길이
        final int depth;         // 입구에서 방 바닥까지 내려가는 깊이
        final int roomX;
        final int roomY;
        final int roomZ;
        final boolean sideRoom;  // 곁방(석실)이 있는가

        CaveKind(int passHalf, int length, int depth, int roomX, int roomY, int roomZ, boolean sideRoom) {
            this.passHalf = passHalf;
            this.length = length;
            this.depth = depth;
            this.roomX = roomX;
            this.roomY = roomY;
            this.roomZ = roomZ;
            this.sideRoom = sideRoom;
        }
    }

    /**
     * 판 동굴의 사양 — 건축 계층이 <b>안에 무엇을 놓을지</b> 정할 때 읽는다.
     * (제단·궤·짚자리는 집의 일이다. 굴은 땅의 일이다.)
     *
     * @param carved 파낸 칸 수 — 환경 검수 ⑥ (지하 공동 2% 문턱)과 대조할 근거
     */
    record CaveSpec(String placeId, CaveKind kind, String world,
                    int mouthX, int mouthY, int mouthZ, BlockFace into,
                    int roomX, int roomY, int roomZ, int carved) {

        /** 굴 전체를 감싸는 구역 (Zone 등록용) */
        Zone zone(String name) {
            int x1 = Math.min(mouthX, roomX) - 14;
            int z1 = Math.min(mouthZ, roomZ) - 14;
            int x2 = Math.max(mouthX, roomX) + 14;
            int z2 = Math.max(mouthZ, roomZ) + 14;
            return new Zone(name, caveSubtitle(kind), world,
                    x1, roomY - 8, z1, x2, mouthY + 6, z2);
        }

        private static String caveSubtitle(CaveKind kind) {
            return switch (kind) {
                case 은신처 -> "동굴과 석실 — 사람이 사는 흔적을 지운다";
                case 기연굴 -> "굴 — 어둠 안쪽에 무언가 있다";
                case 산적굴 -> "굴 — 짐을 쟁이는 자리";
                case 제단굴 -> "지하 제단 — 내려갈수록 조용하다";
            };
        }
    }

    /**
     * <b>동굴을 판다.</b> 부지 사양이 이미 있는 자리에 (건축 계층이 짓기 전에 부르라 — 입구를 보호구역으로 잡는다).
     *
     * <p>순서: 비탈 찾기 → 입구 → 통로(굽이치며 내려간다) → 방 → 곁방 → 껍질 봉인 → 바닥 깔기.
     *
     * @param spec 부지 사양 — 입구 자리를 여기서 찾고, 찾은 자리를 {@link SiteSpec#reserve 보호구역}으로 잡는다
     */
    static CaveSpec digCave(World world, SiteSpec spec, CaveKind kind) {
        int[] mouth = findSlopeMouth(world, spec);
        BlockFace into = face(mouth[3]);
        CaveSpec cave = dig(world, spec.placeId(), kind, mouth[0], mouth[1], mouth[2], into);
        spec.reserve(cave.mouthX(), cave.mouthZ(), 4);   // 입구를 집으로 덮지 않는다
        return cave;
    }

    /** 부지 사양 없이 좌표만으로 판다 (검증·시험용) */
    static CaveSpec digCave(World world, String placeId, CaveKind kind,
                            int mouthX, int mouthY, int mouthZ, BlockFace into) {
        return dig(world, placeId, kind, mouthX, mouthY, mouthZ, into);
    }

    private static CaveSpec dig(World world, String placeId, CaveKind kind,
                                int mx, int my, int mz, BlockFace into) {
        long seed = Math.floorMod(31L * mx + 17L * mz + kind.ordinal(), 1_000_003L);
        Set<Long> carved = new HashSet<>();

        // ─ 통로 — 굽이치며 내려간다. 굽이는 좌표 해시다 (같은 자리면 같은 굽이) ─
        double dirX = into.getModX();
        double dirZ = into.getModZ();
        double px = mx;
        double pz = mz;
        double py = my;
        double descent = kind.depth / (double) kind.length;   // 한 걸음에 내려가는 켜 (≤1 — 걸어 내려간다)
        int lastX = mx;
        int lastY = my;
        int lastZ = mz;

        for (int i = 0; i <= kind.length; i++) {
            // 굽이 — 8칸마다 방위를 ±22.5° 튼다. 자로 그은 굴은 굴이 아니라 갱도다
            if (i > 0 && i % 8 == 0) {
                double turn = (Math.floorMod(seed + i * 37L, 5) - 2) * 0.30;   // -0.6 … +0.6 rad
                double nx = dirX * Math.cos(turn) - dirZ * Math.sin(turn);
                double nz = dirX * Math.sin(turn) + dirZ * Math.cos(turn);
                dirX = nx;
                dirZ = nz;
            }
            px += dirX;
            pz += dirZ;
            py -= descent;
            lastX = (int) Math.round(px);
            lastY = (int) Math.round(py);
            lastZ = (int) Math.round(pz);
            // 입구 쪽 여덟 칸은 조금 넓다 — 비탈의 살을 무너뜨린 것처럼 (자연스러운 아가리)
            int half = kind.passHalf + (i < 3 ? 1 : 0);
            int head = 3 + Math.floorMod(seed + lastX * 7L + lastZ * 13L, 2);   // 천장 높이 3~4
            carveBox(world, carved, lastX, lastY, lastZ, half, head, seed);
        }

        // ─ 방 — 통로의 끝. 바닥은 평평하고 천장은 울퉁불퉁하다 ─
        carveRoom(world, carved, lastX, lastY, lastZ, kind.roomX, kind.roomY, kind.roomZ, seed);

        // ─ 곁방(석실) — 은신처만. 방에서 옆으로 빠지는 짧은 굴 + 작은 방 ─
        if (kind.sideRoom) {
            int sx = lastX + (int) Math.round(-dirZ * 12);   // 방의 옆구리 (진행 방향의 직각)
            int sz = lastZ + (int) Math.round(dirX * 12);
            for (int i = 1; i <= 12; i++) {
                int tx = lastX + (int) Math.round(-dirZ * i);
                int tz = lastZ + (int) Math.round(dirX * i);
                carveBox(world, carved, tx, lastY, tz, 1, 3, seed + 91);
            }
            carveRoom(world, carved, sx, lastY, sz, 5, 4, 5, seed + 173);
        }

        // ─ 껍질 봉인 — 판 자리에 물·용암이 닿으면 막는다. 새는 굴은 굴이 아니라 우물이다 ─
        seal(world, carved);
        // ─ 바닥 — 돌과 자갈. 좌표 해시로 섞는다 (자로 잰 바닥은 바닥이 아니다) ─
        floor(world, carved);

        return new CaveSpec(placeId, kind, world.getName(), mx, my, mz, into,
                lastX, lastY, lastZ, carved.size());
    }

    /** 통로 한 마디 — 바닥 y 에서 head 칸 높이. 벽은 좌표 해시로 울퉁불퉁하다 */
    private static void carveBox(World world, Set<Long> carved, int x, int y, int z,
                                 int half, int head, long seed) {
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                if (dist(dx, dz) > half + 0.5) {
                    continue;
                }
                // 벽의 요철 — 가장자리는 좌표 해시로 절반쯤 남긴다 (매끈한 통로는 갱도다)
                boolean edge = dist(dx, dz) > half - 0.4;
                if (edge && Math.floorMod(seed + (x + dx) * 31L + (z + dz) * 17L, 3) == 0) {
                    continue;
                }
                for (int dy = 0; dy < head; dy++) {
                    cut(world, carved, x + dx, y + dy, z + dz);
                }
            }
        }
    }

    /** 방 — 타원체. 바닥은 평평하고(딛는다) 천장은 흔들린다 */
    private static void carveRoom(World world, Set<Long> carved, int cx, int cy, int cz,
                                  int rx, int ry, int rz, long seed) {
        for (int dx = -rx; dx <= rx; dx++) {
            for (int dz = -rz; dz <= rz; dz++) {
                for (int dy = 0; dy <= ry; dy++) {
                    double e = (dx * dx) / (double) (rx * rx)
                            + (dz * dz) / (double) (rz * rz)
                            + (dy * dy) / (double) (ry * ry);
                    // 천장의 요철 — 방위·높이의 해시로 껍질을 흔든다 (동심 타원은 자연이 아니다)
                    double wobble = 1.0 + (Math.floorMod(seed + (cx + dx) * 11L + (cz + dz) * 7L
                            + dy * 3L, 5) - 2) * 0.05;
                    if (e <= wobble) {
                        cut(world, carved, cx + dx, cy + dy, cz + dz);
                    }
                }
            }
        }
    }

    private static void cut(World world, Set<Long> carved, int x, int y, int z) {
        if (y <= world.getMinHeight() + 6) {
            return;   // 기반암은 파지 않는다
        }
        Block b = world.getBlockAt(x, y, z);
        if (b.getType() != Material.BEDROCK) {
            b.setType(Material.AIR);
        }
        carved.add(key(x, y, z));
    }

    /**
     * 껍질 봉인 — 판 자리에 <b>물·용암이 닿으면 돌로 막는다</b>.
     *
     * <p>자연 동굴은 껐지만 <b>대수층(aquifer)은 남아 있다</b>. 지하 18칸을 파고 들어가면 물주머니를 건드릴 수
     * 있고, 그러면 굴이 잠긴다. 굴의 껍질(파낸 칸의 이웃) 중 액체인 것을 전부 돌로 바꾼다 —
     * 굴은 마른 채로 있어야 굴이다.
     */
    private static void seal(World world, Set<Long> carved) {
        int[][] n6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        List<long[]> shell = new ArrayList<>();
        for (long k : carved) {
            int x = kx(k);
            int y = ky(k);
            int z = kz(k);
            for (int[] d : n6) {
                long nk = key(x + d[0], y + d[1], z + d[2]);
                if (!carved.contains(nk)) {
                    shell.add(new long[]{x + d[0], y + d[1], z + d[2]});
                }
            }
        }
        for (long[] s : shell) {
            Block b = world.getBlockAt((int) s[0], (int) s[1], (int) s[2]);
            if (b.isLiquid()) {
                b.setType(Material.STONE);
            }
        }
    }

    /** 바닥 — 파낸 칸 중 <b>밑이 허공인 것</b>을 돌·자갈로 받친다. 굴에도 딛는 땅이 있어야 한다 */
    private static void floor(World world, Set<Long> carved) {
        for (long k : carved) {
            int x = kx(k);
            int y = ky(k);
            int z = kz(k);
            if (carved.contains(key(x, y - 1, z))) {
                continue;   // 그 밑도 굴이다 — 바닥이 아니다
            }
            Block below = world.getBlockAt(x, y - 1, z);
            if (below.getType().isAir() || below.isLiquid()) {
                below.setType(Material.STONE);
                continue;
            }
            // 딛는 자리 — 돌 사이에 자갈을 섞는다 (사람이 다닌 굴의 바닥은 부슬부슬하다)
            if (Math.floorMod(x * 13 + z * 7, 6) == 0) {
                below.setType(Material.GRAVEL);
            }
        }
    }

    /**
     * 입구 자리 — <b>바깥으로 내려가는 비탈</b>을 찾는다.
     *
     * <p>동굴 입구는 벼랑이나 산비탈의 살이 터진 자리에 있다. 평지 한복판의 구멍은 함정이지 동굴이 아니다.
     * 부지 둘레를 방위각 순서로(해시 오프셋으로 시작점을 흔들어) 훑으며,
     * <b>바깥 5칸이 4켜 이상 내려가는</b> 마른 열을 찾는다. 첫 번째 자리를 쓴다 — 결정론이다.
     *
     * <p>비탈이 하나도 없으면(들판) 마지막 답: 가장 가파른 자리를 쓴다.
     *
     * @return {x, y, z, 방위index} — 방위는 <b>파고 들어가는 쪽</b>(비탈의 안쪽)이다
     */
    private static int[] findSlopeMouth(World world, SiteSpec spec) {
        int cx = spec.cx();
        int cz = spec.cz();
        int r = spec.radius();
        long h = Math.floorMod(31L * cx + 17L * cz, 360);
        int[] best = null;
        int bestDrop = 0;
        for (int a = 0; a < 360; a += 3) {
            double ang = Math.toRadians((a + h) % 360);
            int dirIdx = quadrant(ang);
            for (int d = r - 4; d <= r + 24; d++) {
                int x = cx + (int) Math.round(Math.cos(ang) * d);
                int z = cz + (int) Math.round(Math.sin(ang) * d);
                int gIn = naturalTop(world, x, z, spec.groundY() + 70, spec.groundY() - 40);
                if (gIn == WET_COLUMN || naturalGround(world, x, z, spec.groundY() + 70) == WET_COLUMN) {
                    continue;   // 물가에 굴 입구를 내면 굴이 잠긴다
                }
                int ox = cx + (int) Math.round(Math.cos(ang) * (d + 5));
                int oz = cz + (int) Math.round(Math.sin(ang) * (d + 5));
                int gOut = naturalTop(world, ox, oz, spec.groundY() + 70, spec.groundY() - 40);
                int drop = gIn - gOut;   // 바깥으로 내려간다 = 비탈이다
                if (drop >= 4) {
                    // 안쪽(산 쪽)으로 판다 — 비탈의 살을 파고 들어가야 위에 지붕(바위)이 생긴다
                    return new int[]{x, gIn + 1, z, opposite(dirIdx)};
                }
                if (drop > bestDrop) {
                    bestDrop = drop;
                    best = new int[]{x, gIn + 1, z, opposite(dirIdx)};
                }
            }
        }
        if (best != null) {
            return best;
        }
        // 들판 — 비탈이 없다. 부지 밖 북쪽 가장자리에 <b>바위 틈</b>처럼 낸다 (땅이 갈라진 자리)
        int x = cx;
        int z = cz - (r + 8);
        int g = naturalTop(world, x, z, spec.groundY() + 70, spec.groundY() - 40);
        return new int[]{x, g, z, 0};   // 북 → 남으로 판다 (아래로 내려간다)
    }

    private static int quadrant(double ang) {
        double cos = Math.cos(ang);
        double sin = Math.sin(ang);
        if (Math.abs(cos) > Math.abs(sin)) {
            return cos > 0 ? 1 : 3;   // 동 : 서
        }
        return sin > 0 ? 2 : 0;       // 남 : 북
    }

    private static int opposite(int dirIdx) {
        return (dirIdx + 2) % 4;
    }

    private static BlockFace face(int dirIdx) {
        return switch (dirIdx) {
            case 1 -> BlockFace.EAST;
            case 2 -> BlockFace.SOUTH;
            case 3 -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    // ═══════════════════════════════════════════════════════════════════
    // 동굴 등록부 — 어디에 파는가 (config/terrain.yml)
    // ═══════════════════════════════════════════════════════════════════

    private static Map<String, CaveKind> caveRegistry = new LinkedHashMap<>();

    /** 조성 윤곽 등록부 — <b>지도가 관리한다</b>. 코드의 추론보다 이 표가 우선이다 */
    private static Map<String, Profile> shapeRegistry = new LinkedHashMap<>();

    /** 표층 등록부 — 눈·모래를 어디에 얹는가 (고원+눈=토번처럼 지형만으로 못 읽는 것) */
    private static Map<String, Surface> surfaceRegistry = new LinkedHashMap<>();

    /** 설선 등록부 — 장소마다 다르다 (곤륜은 낮고 화산은 높다) */
    private static Map<String, Integer> snowlineRegistry = new LinkedHashMap<>();

    /** 오아시스 등록부 — 모래 한가운데 물을 파는 자리 */
    private static Set<String> oasisRegistry = new java.util.LinkedHashSet<>();

    /** 산 높이 등록부 — 장소마다 다르다 (험산 72 · 산 44 · 고원 24 가 기본) */
    private static Map<String, Integer> liftRegistry = new LinkedHashMap<>();

    /** {@code config/terrain.yml} 판독 — 없어도 돈다 (그때는 등록부에서 <b>읽어 낸다</b>) */
    @SuppressWarnings("unchecked")
    static void load(Path configDir) {
        caveRegistry = new LinkedHashMap<>();
        shapeRegistry = new LinkedHashMap<>();
        surfaceRegistry = new LinkedHashMap<>();
        snowlineRegistry = new LinkedHashMap<>();
        oasisRegistry = new java.util.LinkedHashSet<>();
        Path file = configDir.resolve("terrain.yml");
        if (!Files.isRegularFile(file)) {
            return;
        }
        Map<String, Object> root = RulesConfig.load(file);

        // 표층 — surfacing: { <id>: 사막|설원 }  (없으면 지형이 말한다: 설산 → 설원 · 사막 → 사막)
        if (root.get("surfacing") instanceof Map<?, ?> surfacing) {
            for (Map.Entry<?, ?> e : surfacing.entrySet()) {
                Object v = e.getValue();
                String name = v instanceof Map
                        ? String.valueOf(((Map<String, Object>) v).get("surface"))
                        : String.valueOf(v);
                try {
                    surfaceRegistry.put(String.valueOf(e.getKey()), Surface.valueOf(name.trim()));
                } catch (IllegalArgumentException ignored) {
                    // 모르는 표층 — 조용히 넘긴다
                }
                if (v instanceof Map && ((Map<String, Object>) v).get("snowline") instanceof Number n) {
                    snowlineRegistry.put(String.valueOf(e.getKey()), n.intValue());
                }
            }
        }
        // 설선 — snowline: { <id>: 120 }  (surfacing 안에 적어도 되고, 따로 적어도 된다)
        if (root.get("snowline") instanceof Map<?, ?> snow) {
            for (Map.Entry<?, ?> e : snow.entrySet()) {
                if (e.getValue() instanceof Number n) {
                    snowlineRegistry.put(String.valueOf(e.getKey()), n.intValue());
                }
            }
        }
        // 오아시스 — oasis: [id, id]
        if (root.get("oasis") instanceof List<?> list) {
            for (Object o : list) {
                oasisRegistry.add(String.valueOf(o));
            }
        }
        // 산 높이 — lift: { <id>: 72 }  (★ 조감을 보고 장소마다 조절한다. 코드를 안 고쳐도 된다)
        if (root.get("lift") instanceof Map<?, ?> lifts) {
            for (Map.Entry<?, ?> e : lifts.entrySet()) {
                if (e.getValue() instanceof Number n) {
                    liftRegistry.put(String.valueOf(e.getKey()), n.intValue());
                }
            }
        }

        if (root.get("caves") instanceof Map<?, ?> caves) {
            for (Map.Entry<?, ?> e : caves.entrySet()) {
                if (!(e.getValue() instanceof Map)) {
                    continue;
                }
                Object kind = ((Map<String, Object>) e.getValue()).get("archetype");
                if (kind == null) {
                    continue;
                }
                try {
                    caveRegistry.put(String.valueOf(e.getKey()), CaveKind.valueOf(String.valueOf(kind).trim()));
                } catch (IllegalArgumentException ignored) {
                    // 모르는 원형 — 조용히 넘긴다 (등록부가 앞서 갈 수 있다)
                }
            }
        }
        if (root.get("shaping") instanceof Map<?, ?> shaping) {
            for (Map.Entry<?, ?> e : shaping.entrySet()) {
                Object v = e.getValue();
                String name = v instanceof Map
                        ? String.valueOf(((Map<String, Object>) v).get("profile"))
                        : String.valueOf(v);
                try {
                    shapeRegistry.put(String.valueOf(e.getKey()), Profile.valueOf(name.trim()));
                } catch (IllegalArgumentException ignored) {
                    // 모르는 윤곽 — 조용히 넘긴다
                }
            }
        }
    }

    /**
     * 이 자리에 동굴을 파는가 — 등록부(terrain.yml)가 먼저, 없으면 <b>지도에서 읽어 낸다</b>.
     *
     * <p>지도는 이미 말하고 있다: 마교 전초의 architecture 는 "동굴과 석실"이고, 녹림은 굴에 짐을 쟁이고,
     * 폐허(폐사당·옛 문파 터)는 기연이 앉는 자리다. <b>등록부를 다시 쓰지 않고 읽는다.</b>
     *
     * @return 팔 동굴의 원형. 없으면 null (여기엔 굴이 없다)
     */
    static CaveKind caveKind(WorldMap.Place place) {
        CaveKind registered = caveRegistry.get(place.id());
        if (registered != null) {
            return registered;
        }
        String note = place.note() == null ? "" : place.note();
        String faction = place.faction() == null ? "" : place.faction().toLowerCase(Locale.ROOT);
        String terrain = place.terrain() == null ? "" : place.terrain();
        if ("hyeolgyo".equals(faction) && "폐허".equals(terrain)) {
            return CaveKind.제단굴;                  // 혈교 옛 제단 — 내려가는 굴
        }
        if ("magyo".equals(faction) || "hyeolgyo".equals(faction)) {
            if (note.contains("동굴") || note.contains("석실") || "험산".equals(terrain)
                    || "밀림".equals(terrain)) {
                return CaveKind.은신처;              // 마교 전초 — "동굴과 석실"
            }
        }
        if ("noklim".equals(faction) && !"nokrim_sangil".equals(place.id())) {
            return CaveKind.산적굴;                  // 산채의 굴 — 짐을 쟁인다
        }
        if ("폐허".equals(terrain)) {
            return CaveKind.기연굴;                  // 폐사당·옛 문파 터 — 기연이 앉는 자리
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 손
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 자연 지면 — 사람이 지은 것도, 초목도 세지 않는다.
     * <b>물을 만나면 그 열은 젖었다</b>({@link #WET_COLUMN}) — 물을 땅으로 읽으면 마을이 호수에 선다.
     */
    private static int naturalGround(World world, int x, int z, int from) {
        int top = Math.min(from, world.getMaxHeight() - 1);
        int floor = Math.max(world.getMinHeight(), top - 120);
        for (int y = top; y >= floor; y--) {
            Material m = world.getBlockAt(x, y, z).getType();
            if (WET.contains(m)) {
                return WET_COLUMN;
            }
            if (SOLID_NATURAL.contains(m)) {
                return y;
            }
        }
        return floor;
    }

    /** 자연 지면 — <b>물을 지나쳐서</b> 잰다 (산을 세울 때는 호수 바닥도 땅이다) */
    private static int naturalTop(World world, int x, int z, int from, int floorHint) {
        int top = Math.min(from, world.getMaxHeight() - 1);
        int floor = Math.max(world.getMinHeight(), Math.min(floorHint - 40, top - 120));
        for (int y = top; y >= floor; y--) {
            Material m = world.getBlockAt(x, y, z).getType();
            if (SOLID_NATURAL.contains(m)) {
                return y;
            }
        }
        return floor;
    }

    private static double dist(int dx, int dz) {
        return Math.sqrt((double) dx * dx + (double) dz * dz);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (y & 0xFFF) << 30) | (z & 0x3FFFFFF);
    }

    private static int kx(long k) {
        int v = (int) ((k >> 42) & 0x3FFFFF);
        return v >= 0x200000 ? v - 0x400000 : v;
    }

    private static int ky(long k) {
        int v = (int) ((k >> 30) & 0xFFF);
        return v >= 0x800 ? v - 0x1000 : v;
    }

    private static int kz(long k) {
        int v = (int) (k & 0x3FFFFFF);
        return v >= 0x2000000 ? v - 0x4000000 : v;
    }

    /** 조성 전 청크 선로드 — 콘솔 조성은 부지가 언로드 상태다 (F29 의 교훈) */
    static void preload(World world, int cx, int cz, int radius) {
        for (int chunkX = (cx - radius) >> 4; chunkX <= (cx + radius) >> 4; chunkX++) {
            for (int chunkZ = (cz - radius) >> 4; chunkZ <= (cz + radius) >> 4; chunkZ++) {
                world.getChunkAt(chunkX, chunkZ).load(true);
            }
        }
    }
}
