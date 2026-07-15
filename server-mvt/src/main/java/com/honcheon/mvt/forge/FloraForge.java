package com.honcheon.mvt.forge;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Random;
import java.util.Set;

/**
 * 식생층 생성기 (기계 ⑨) — {@link RangeField}(순수 높이장·구역 판정) 위에 <b>구역별 식생</b>을
 * 심는다. 산세 기록기({@link MountainRangeForge})가 세운 맨 산(돌·흙·잔디) 위에 나무·풀·꽃을
 * 얹어 사용자가 도보로 「매화림→벚꽃, 외곽→참나무 숲」을 본다.
 *
 * <h2>결정론 (난수 금지 — 좌표 해시)</h2>
 * <ul>
 *   <li><b>난수 0.</b> 무엇을 심을지·심을지 말지는 전부 <b>좌표 해시</b>({@link #hash01})의 순수
 *       함수다 — 같은 좌표 = 같은 결정. (TerrainForge·RangeField 결정론 헌법의 계승.)</li>
 *   <li><b>나무 모양도 결정론.</b> {@code world.generateTree(loc, rng, TreeType)} 에 넘기는
 *       {@link Random} 을 <b>좌표 해시로 시드</b>한다 — 같은 좌표 = 같은 나무 모양.</li>
 *   <li><b>타일 무관성.</b> 어느 열에 나무가 서는지는 <b>지터 격자</b>(한 셀에 최대 한 그루,
 *       셀의 대표 좌표를 셀 해시로 뽑음)로 정한다 — 셀 소속·대표 좌표가 순수 함수라 어느 순서·
 *       크기로 타일을 쪼개도 같은 나무가 선다.</li>
 * </ul>
 *
 * <h2>구역 → 수종 (근거: {@code docs/design/hwasan_domain_design.md §식생 표 §100~105})</h2>
 * ★표에서 <b>유도</b>한다 — 지어내지 않는다. 표에 없는 구역은 「성기게(암석·관목·풀만)」 두거나
 * 미결로 표기한다 (terrain_forge_v5.md §3 기계 ⑨ · 아래 표 주석).
 * <pre>
 *   OUTER_PLAIN 외곽 = 참나무·자작나무·풀        → OAK/BIRCH + 잔풀·꽃          (숲 — 높음)
 *   PLUM_GROVE  매화림 = 벚꽃(매화 대체)·꽃·잔디   → CHERRY + 분홍꽃잎·잔디        (낮음 §74)
 *   VALLEY      계곡 = 대나무·이끼·양치식물        → BAMBOO + 이끼카펫·고사리       (중간)
 *   HUSAN       후산 = 오래된 소나무·고목·희귀 약초 → 큰 SPRUCE + 고사목·희귀 꽃    (중간)
 *   SUMMIT      정상 = 작은 풀·바위·거의 나무 없음  → 잔풀만 (나무 0)               (거의 없음)
 *   ENTRANCE    입구 ─ (표엔 「등산로 = 소나무·암석·관목」) → SPRUCE 성기게 + 관목  (잠정 — §미결)
 *   CLIFF/DRILL_VALLEY/HONSAN — 표에 없음 · 건축/정비 구역 → 풀·관목만, 나무 0     (성기게 · §미결)
 * </pre>
 *
 * <h2>밀도 (전부 「잠정, 승인 대기」 — §74~80 정성값에서 유도한 하드값)</h2>
 * 나무는 {@value #TREE_CELL}칸 지터 격자로 후보를 뽑고 구역별 확률로 솎는다. 잔풀·꽃은 열마다
 * 구역별 확률로 뿌린다. 하드값의 근거는 정성값(외곽 숲=높음·매화림=낮음·정상=거의 없음)뿐이므로
 * <b>전부 잠정</b>이다 (사용자 승인 시 등록부 {@code config/flora.yml} 이관 — H-14 결).
 *
 * <p>지면 존중: 나무·풀은 <b>지면 위(surfaceY+1)</b>에만, 밑이 자연 지면(흙·잔디·이끼·포졸)일
 * 때만 심는다 — 드러난 암반(돌·안산암 · 급경사·절벽)엔 안 선다. 사람이 지은 것(판자·벽돌 등)은
 * 밑감이 아니라 지나친다. 물은 놓지 않는다 (수계 ②의 몫 — 협곡은 마른 골).
 */
public final class FloraForge {

    private FloraForge() {
    }

    // ─── 좌표 해시 씨앗 소금 (서로 독립인 결정을 가르는 상수 — 난수가 아니다) ───
    private static final long SALT_JX = 0x11A5_CE11L;     // 지터 격자 대표 x
    private static final long SALT_JZ = 0x22B6_DF22L;     // 지터 격자 대표 z
    private static final long SALT_TREE = 0x33C7_E033L;   // 나무를 심을 것인가 (확률 판정)
    private static final long SALT_SPECIES = 0x44D8_F144L;// 수종 선택
    private static final long SALT_SHAPE = 0x55E9_0255L;  // 나무 모양 Random 시드
    private static final long SALT_GROUND = 0x66FA_1366L; // 잔풀·꽃 뿌리기
    private static final long SALT_FLOWER = 0x770B_2477L; // 꽃 종류
    private static final long SALT_BAMBOO = 0x881C_3588L; // 대나무 높이

    /** 나무 후보 격자 한 변 (칸) — 한 셀에 최대 한 그루. 최소 이격의 자 (잠정, 승인 대기) */
    private static final int TREE_CELL = 5;

    /** 나무가 서는 최대 물매 — 이보다 가파르면(절벽·벼랑) 나무는 성기게(암벽이 드러난다). 잠정 */
    private static final double TREE_MAX_SLOPE = 0.9;

    /** 심을 수 있는 밑감 — 자연 지면만. 드러난 암반(STONE/안산암)·사람이 지은 것은 제외 */
    private static final Set<Material> PLANTABLE = EnumSet.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT,
            Material.ROOTED_DIRT, Material.PODZOL, Material.MOSS_BLOCK);

    /** 구역별 나무 확률 (후보 격자점 하나가 나무가 될 확률) — 전부 잠정, 승인 대기 (§74~80 정성값 유도) */
    private static double treeProbability(RangeZone zone) {
        return switch (zone) {
            case OUTER_PLAIN -> 0.55;   // 외곽 = 참나무 숲 (높음)
            case PLUM_GROVE -> 0.30;    // 매화림 = 벚꽃 (낮음 — §74 매화림 밀도 낮음)
            case VALLEY -> 0.35;        // 계곡 = 대나무 군락 (중간)
            case HUSAN -> 0.35;         // 후산 = 오래된 소나무 (중간 — 거대한 자연)
            case ENTRANCE -> 0.15;      // 입구 = 소나무 성기게 (잠정 — 「등산로」 유도 · 상업 거리 존중)
            // 표에 없거나 「거의 나무 없음」 — 나무 0 (성기게 · 건축/정비 구역 · 정상 성역)
            case SUMMIT, CLIFF, DRILL_VALLEY, HONSAN, OUTSIDE -> 0.0;
        };
    }

    // ═══════════════════════════════════════════════════════════════════
    // 타일 하나에 식생을 심는다 — [minX..maxX] × [minZ..maxZ]
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 타일 하나에 식생을 심는다. 타일 무관성: 같은 사양이면 어떤 분할로 불러도 같은 식생이다.
     * 결과 개수는 {@link Tally} 에 누적한다 (여러 타일에 걸쳐 census 를 모은다).
     */
    public static void plantTile(World world, RangeSpec spec, RangeField field,
                                 int minX, int minZ, int maxX, int maxZ, Tally tally) {
        preload(world, minX, minZ, maxX, maxZ);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                RangeZone zone = field.zoneAt(x, z);
                if (zone == RangeZone.OUTSIDE) {
                    continue;   // 경제권 밖 — 회랑·야생의 몫
                }
                // ① 나무 — 이 열이 제 격자 셀의 대표(지터) 좌표일 때만 후보다 (셀당 최대 한 그루)
                if (isTreeSite(x, z)) {
                    plantTree(world, spec, field, x, z, zone, tally);
                }
                // ② 잔풀·꽃·이끼 — 열마다 (나무와 독립)
                plantGroundCover(world, x, z, zone, tally);
            }
        }
    }

    /** 이 열이 제 {@value #TREE_CELL}칸 격자 셀의 대표(지터) 좌표인가 — 순수 함수 (타일 무관) */
    private static boolean isTreeSite(int x, int z) {
        int cellX = Math.floorDiv(x, TREE_CELL);
        int cellZ = Math.floorDiv(z, TREE_CELL);
        int jx = (int) (hash01(cellX, cellZ, SALT_JX) * TREE_CELL);
        int jz = (int) (hash01(cellX, cellZ, SALT_JZ) * TREE_CELL);
        return x == cellX * TREE_CELL + jx && z == cellZ * TREE_CELL + jz;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 나무 — 구역 → 수종 (식생 표 유도). 결정론: 확률·수종·모양 전부 좌표 해시
    // ═══════════════════════════════════════════════════════════════════

    private static void plantTree(World world, RangeSpec spec, RangeField field,
                                  int x, int z, RangeZone zone, Tally tally) {
        double prob = treeProbability(zone);
        if (prob <= 0.0 || hash01(x, z, SALT_TREE) >= prob) {
            return;
        }
        if (field.slopeAt(x, z) > TREE_MAX_SLOPE) {
            return;   // 급경사·절벽 — 나무는 성기게 (암벽이 드러난다)
        }
        int groundY = world.getHighestBlockYAt(x, z);
        Block below = world.getBlockAt(x, groundY, z);
        if (!PLANTABLE.contains(below.getType())) {
            return;   // 드러난 암반·사람이 지은 것·이미 초목 — 지나친다
        }
        if (!world.getBlockAt(x, groundY + 1, z).getType().isAir()) {
            return;   // 이미 무언가 서 있다 (이웃 나무 수관 등)
        }
        double pick = hash01(x, z, SALT_SPECIES);
        switch (zone) {
            case VALLEY -> {
                bambooStalk(world, x, groundY, z, tally);   // 계곡 = 대나무 (나무가 아니다)
                return;
            }
            case HUSAN -> {
                if (pick < 0.20) {                          // 후산 = 고목(고사목) 20%
                    deadSnag(world, x, groundY, z, tally);
                    return;
                }
                grow(world, x, groundY, z, TreeType.TALL_REDWOOD, zone, tally);  // 오래된 소나무
            }
            case PLUM_GROVE -> grow(world, x, groundY, z, TreeType.CHERRY, zone, tally);  // 벚꽃(매화 대체)
            case ENTRANCE -> grow(world, x, groundY, z, TreeType.REDWOOD, zone, tally);   // 소나무 (잠정)
            case OUTER_PLAIN -> grow(world, x, groundY, z,                                 // 참나무 70 / 자작 30
                    pick < 0.70 ? TreeType.TREE : TreeType.BIRCH, zone, tally);
            default -> { /* 나무 없음 */ }
        }
    }

    /** {@code generateTree} 에 좌표 시드 Random 을 넘긴다 — 같은 좌표 = 같은 나무 모양 (난수 아님) */
    private static void grow(World world, int x, int groundY, int z,
                             TreeType type, RangeZone zone, Tally tally) {
        Random rng = new Random(hashLong(x, z, SALT_SHAPE));
        Location loc = new Location(world, x, groundY + 1, z);
        if (world.generateTree(loc, rng, type)) {
            tally.tree(zone);
        }
    }

    /** 대나무 줄기 — 3~6단 (계곡 식생표). 높이는 좌표 해시 (난수 아님) */
    private static void bambooStalk(World world, int x, int groundY, int z, Tally tally) {
        int h = 3 + (int) (hash01(x, z, SALT_BAMBOO) * 4);   // 3~6
        for (int y = groundY + 1; y <= groundY + h; y++) {
            world.getBlockAt(x, y, z).setType(Material.BAMBOO, false);
        }
        tally.addBamboo();
    }

    /** 고사목(古木) — 잎 없는 죽은 소나무 밑동 3~5단 (후산 식생표: 고목) */
    private static void deadSnag(World world, int x, int groundY, int z, Tally tally) {
        int h = 3 + (int) (hash01(x, z, SALT_BAMBOO) * 3);   // 3~5
        for (int y = groundY + 1; y <= groundY + h; y++) {
            world.getBlockAt(x, y, z).setType(Material.STRIPPED_SPRUCE_LOG, false);
        }
        tally.tree(RangeZone.HUSAN);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 잔풀·꽃·이끼·양치 — 구역별 지표 (열마다 · 좌표 해시)
    // ═══════════════════════════════════════════════════════════════════

    private static void plantGroundCover(World world, int x, int z, RangeZone zone, Tally tally) {
        int groundY = world.getHighestBlockYAt(x, z);
        Block below = world.getBlockAt(x, groundY, z);
        if (!PLANTABLE.contains(below.getType())) {
            return;
        }
        Block target = world.getBlockAt(x, groundY + 1, z);
        if (!target.getType().isAir()) {
            return;
        }
        double r = hash01(x, z, SALT_GROUND);
        // (구역별 확률·수종 — 전부 잠정, 승인 대기. 식생 표에서 유도)
        switch (zone) {
            case OUTER_PLAIN -> {                          // 외곽 = 풀 (+ 들꽃)
                if (r < 0.05) {
                    placeFlower(target, x, z, tally);
                } else if (r < 0.35) {
                    placeGrass(world, target, x, z, groundY, tally);
                }
            }
            case PLUM_GROVE -> {                           // 매화림 = 꽃·잔디 (분홍 꽃잎)
                if (r < 0.15) {
                    place(target, Material.PINK_PETALS, tally);
                } else if (r < 0.40) {
                    placeGrass(world, target, x, z, groundY, tally);
                }
            }
            case VALLEY -> {                               // 계곡 = 이끼·양치식물
                if (r < 0.20) {
                    place(target, Material.MOSS_CARPET, tally);
                } else if (r < 0.35) {
                    place(target, Material.FERN, tally);
                } else if (r < 0.40) {
                    placeDouble(world, x, groundY + 1, z, Material.LARGE_FERN, tally);
                }
            }
            case HUSAN -> {                                // 후산 = 양치·잔풀 (+ 희귀 약초 approx)
                if (r < 0.02) {
                    place(target, rareHerb(x, z), tally);
                } else if (r < 0.10) {
                    place(target, Material.FERN, tally);
                } else if (r < 0.25) {
                    place(target, Material.SHORT_GRASS, tally);
                }
            }
            case SUMMIT -> {                               // 정상 = 작은 풀만 (거의 없음)
                if (r < 0.06) {
                    place(target, Material.SHORT_GRASS, tally);
                }
            }
            case ENTRANCE -> {                             // 입구 = 관목·풀 (잠정 — 「등산로」 유도)
                if (r < 0.02) {
                    place(target, Material.DEAD_BUSH, tally);
                } else if (r < 0.14) {
                    place(target, Material.SHORT_GRASS, tally);
                }
            }
            case CLIFF -> {                                // 절벽 = 마른 관목만 (성기게)
                if (r < 0.04) {
                    place(target, Material.DEAD_BUSH, tally);
                }
            }
            case DRILL_VALLEY -> {                         // 연무 계곡 = 열린 수련장 (풀만 성기게)
                if (r < 0.08) {
                    place(target, Material.SHORT_GRASS, tally);
                }
            }
            case HONSAN -> {                               // 본산 = 건축 매우 높음 (풀만 아주 성기게)
                if (r < 0.05) {
                    place(target, Material.SHORT_GRASS, tally);
                }
            }
            default -> { /* OUTSIDE 등 — 없음 */ }
        }
    }

    /** 잔풀 — 대개 SHORT_GRASS, 가끔 TALL_GRASS(두 켜) */
    private static void placeGrass(World world, Block target, int x, int z, int groundY, Tally tally) {
        if (hash01(x, z, SALT_FLOWER) < 0.15) {
            placeDouble(world, x, groundY + 1, z, Material.TALL_GRASS, tally);
        } else {
            place(target, Material.SHORT_GRASS, tally);
        }
    }

    /** 들꽃 — 좌표 해시로 종류를 고른다 (난수 아님) */
    private static void placeFlower(Block target, int x, int z, Tally tally) {
        Material[] flowers = {Material.POPPY, Material.DANDELION, Material.OXEYE_DAISY,
                Material.CORNFLOWER, Material.AZURE_BLUET, Material.ALLIUM};
        place(target, flowers[(int) (hash01(x, z, SALT_FLOWER) * flowers.length)], tally);
    }

    /** 희귀 약초 — 바닐라에 「약초」 블록이 없어 희귀 꽃으로 근사한다 (★미결 — 아이템은 후속) */
    private static Material rareHerb(int x, int z) {
        Material[] herbs = {Material.LILY_OF_THE_VALLEY, Material.BLUE_ORCHID, Material.ALLIUM};
        return herbs[(int) (hash01(x, z, SALT_FLOWER) * herbs.length)];
    }

    private static void place(Block target, Material m, Tally tally) {
        target.setType(m, false);
        tally.addGround();
    }

    /** 두 켜 식물(TALL_GRASS·LARGE_FERN) — 아래·위 절반을 함께 놓는다. 위가 비었을 때만 */
    private static void placeDouble(World world, int x, int y, int z, Material m, Tally tally) {
        if (!world.getBlockAt(x, y + 1, z).getType().isAir()) {
            return;
        }
        Bisected lower = (Bisected) m.createBlockData();
        lower.setHalf(Bisected.Half.BOTTOM);
        Bisected upper = (Bisected) m.createBlockData();
        upper.setHalf(Bisected.Half.TOP);
        world.getBlockAt(x, y, z).setBlockData(lower, false);
        world.getBlockAt(x, y + 1, z).setBlockData(upper, false);
        tally.addGround();
    }

    // ═══════════════════════════════════════════════════════════════════
    // 청크 선로드 — 콘솔 조성은 부지가 언로드 상태일 수 있다 (MountainRangeForge.preload 문법)
    // ═══════════════════════════════════════════════════════════════════
    private static void preload(World world, int minX, int minZ, int maxX, int maxZ) {
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                world.getChunkAt(cx, cz).load(true);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 결정론 좌표 해시 — splitmix64 계열 섞기 (RangeField.lattice 결). 난수 씨앗이 없다
    // ═══════════════════════════════════════════════════════════════════

    /** 좌표+소금을 섞은 64비트 — {@code generateTree} Random 시드로도 쓴다 */
    static long hashLong(int x, int z, long salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return h;
    }

    /** 좌표+소금 → [0,1) 결정론 값 (난수 아님 — 같은 좌표 = 같은 값) */
    static double hash01(int x, int z, long salt) {
        return (hashLong(x, z, salt) >>> 11) / (double) (1L << 53);
    }

    // ═══════════════════════════════════════════════════════════════════
    // census 누적기 — 여러 타일에 걸쳐 심은 것을 센다 (완료 보고용)
    // ═══════════════════════════════════════════════════════════════════
    public static final class Tally {
        private final EnumMap<RangeZone, Long> treesByZone = new EnumMap<>(RangeZone.class);
        private long trees;
        private long bamboo;
        private long ground;

        void tree(RangeZone zone) {
            trees++;
            treesByZone.merge(zone, 1L, Long::sum);
        }

        void addBamboo() {
            bamboo++;
            treesByZone.merge(RangeZone.VALLEY, 1L, Long::sum);
        }

        void addGround() {
            ground++;
        }

        public long trees() {
            return trees;
        }

        public long bamboo() {
            return bamboo;
        }

        public long ground() {
            return ground;
        }

        /** 구역별 나무·대나무 수 — 완료 census 한 줄 (심긴 구역만, 많은 순) */
        public String byZone() {
            StringBuilder sb = new StringBuilder();
            treesByZone.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .forEach(en -> {
                        if (sb.length() > 0) {
                            sb.append(" · ");
                        }
                        sb.append(tag(en.getKey())).append(' ').append(en.getValue());
                    });
            return sb.length() == 0 ? "(없음)" : sb.toString();
        }

        private static String tag(RangeZone z) {
            return switch (z) {
                case PLUM_GROVE -> "매화림";
                case VALLEY -> "계곡";
                case HUSAN -> "후산";
                case ENTRANCE -> "입구";
                case OUTER_PLAIN -> "외곽";
                case SUMMIT -> "정상";
                case CLIFF -> "절벽";
                case DRILL_VALLEY -> "연무";
                case HONSAN -> "본산";
                default -> z.name();
            };
        }
    }
}
