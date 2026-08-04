package com.honcheon.mvt.contest;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.HashMap;
import java.util.Map;

/**
 * <b>화산파 산문 문루 — 대결 트랙 「클로드」</b>.
 *
 * <p>규격 정본: {@code docs/design/hwasan_gate_contest.md} · 실측 근거:
 * {@code docs/design/hwasan_block_measurements.md} §3·§3-b · 레퍼런스 {@code 화산파/} 1·2·6·7호.
 * <b>프로젝트 기존 클래스에 의존하지 않는다</b> (Bukkit API + java.* 만) — 대결 규격.
 *
 * <h2>설계 의도 — 격차 재점검 3의 처방</h2>
 * <ol>
 *   <li><b>디테일 밀도</b>: 격자창(열린 나무 뚜껑문)·공포(보 위 팔 + 받침 켜)·현판(빈 금판)·
 *       등롱(사슬에 매단 초롱)을 <b>실물로</b> 넣는다. 큰 면을 남기지 않는 것이 목표다.</li>
 *   <li><b>실루엣과 위계</b>: <b>중앙 포치가 앞으로 6칸 튀어나오고</b> 제 지붕을 이고,
 *       그 뒤로 겹처마 스커트 → 누마루(발코니) → 누각 상층 → 큰 우진각 지붕이
 *       <b>네 켜로 물러나며</b> 올라간다. 멀리서 중심이 읽히는 것은 이 <b>층서</b>다.</li>
 *   <li><b>재료 대비</b>: 석재 기단(회) → 적주(벗긴 맹그로브) + 백벽(테라코타·방해석) +
 *       흑목 격자 → 흑기와(심층암 계열 혼합). <b>네 층위가 y 로 갈린다.</b></li>
 * </ol>
 *
 * <h2>주요 치수</h2>
 * <pre>
 *   정면 61 (반폭 30) · 몸체 깊이 13 · 총고 38 (기단 상면 기준) — 비 0.62 (레퍼런스 ≈0.58)
 *   중앙 아치 11×12 · 곁 아치 5×8 두 짝 (±18) — 실측표 §3 그대로
 *   적주 간격 6 · 처마 내밈 4 · 하층 벽 12 · 상층 벽 9
 *   포치 27폭 · 앞으로 6 돌출 · 제 지붕 용마루 g+20
 *   주 지붕 처마선 g+27 (반폭 31) · 9 링 · 용마루 g+36 · 치미 g+38
 * </pre>
 *
 * <p>원점 계약: {@code (ox, oy, oz)} = <b>문루 정면 중앙의 지면 블록</b> = 기단 상면의 문지방.
 * 정면은 남(+z 바깥) · 통행 축 남북. 범위 계약(x ±40 · z −30..+10 · y −4..+56)을 지킨다.
 */
public final class GateClaude {

    private GateClaude() {
    }

    // ── 치수 상수 ────────────────────────────────────────────────────────────
    private static final int HALF = 30;          // 하층 반폭 → 정면 61
    private static final int DEPTH = 12;         // 몸체 깊이 (z: oz .. oz-12)
    private static final int WALL_H = 12;        // 하층 벽 높이 (실측 §3-b 단층 10 → 문루는 12)
    private static final int ARCH_HALF = 5;      // 중앙 아치 반폭 → 11
    private static final int SIDE_X = 18;        // 곁 아치 중심
    private static final int SIDE_HALF = 2;      // 곁 아치 반폭 → 5
    private static final int SIDE_H = 8;         // 곁 아치 높이
    private static final int COL_STEP = 6;       // 적주 간격 (실측 §3-b)
    private static final int SKIRT_OUT = 34;     // 겹처마 스커트 바깥 반폭 (내밈 4)
    private static final int UP_HALF = 27;       // 누각 상층 반폭 → 55
    private static final int UP_H = 9;           // 상층 벽 높이
    private static final int ROOF_HALF = 31;     // 주 지붕 처마 반폭 (상층에서 내밈 4)
    private static final int PORCH_HALF = 13;    // 포치 반폭 → 27
    private static final int PORCH_OUT = 6;      // 포치 돌출 (z oz+1 .. oz+6)
    private static final int PORCH_ROOF_HALF = 17;

    // ── 블록 데이터 캐시 (문자열 파싱은 한 번만) ──────────────────────────────
    private static final Map<String, BlockData> CACHE = new HashMap<>();

    private static BlockData bd(String spec) {
        return CACHE.computeIfAbsent(spec, Bukkit::createBlockData);
    }

    private static String key(Material m) {
        return m.getKey().toString();
    }

    // ── 결정론 해시 (난수 금지 — 좌표만으로 정해진다) ────────────────────────
    private static int hash(int x, int y, int z) {
        int h = x * 374761393 + y * 668265263 + z * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (h ^ (h >>> 16)) & 0x7fffffff;
    }

    // ── 재료 표 (층위가 y 로 갈린다) ─────────────────────────────────────────

    /** 기단·석재 — 거칠고 따뜻한 회색. */
    private static Material podium(int x, int y, int z) {
        int h = hash(x, y, z) % 100;
        if (h < 52) return Material.STONE_BRICKS;
        if (h < 72) return Material.ANDESITE;
        if (h < 88) return Material.TUFF;
        if (h < 95) return Material.MOSSY_STONE_BRICKS;
        return Material.COBBLESTONE;
    }

    /** 백벽 — 회벽. 흰색 일색이 아니라 결이 섞인다. */
    private static Material plaster(int x, int y, int z) {
        int h = hash(x, y, z) % 100;
        if (h < 62) return Material.WHITE_TERRACOTTA;
        if (h < 86) return Material.CALCITE;
        return Material.SMOOTH_QUARTZ;
    }

    /** 기와 — 흑기와. 면 큐브만 섞고 윤곽(계단·반블록)은 또렷하게 둔다. */
    private static Material tile(int x, int y, int z) {
        int h = hash(x, y, z) % 100;
        if (h < 56) return Material.DEEPSLATE_TILES;
        if (h < 78) return Material.DEEPSLATE_BRICKS;
        if (h < 91) return Material.CRACKED_DEEPSLATE_TILES;
        return Material.BLACKSTONE;
    }

    /** 기와 계단 — 두 종만 섞는다 (윤곽선을 흐리지 않으려고). */
    private static Material tileStair(int x, int y, int z) {
        return (hash(x, y, z) % 100) < 74 ? Material.DEEPSLATE_TILE_STAIRS : Material.DEEPSLATE_BRICK_STAIRS;
    }

    // ── 원시 조작 ────────────────────────────────────────────────────────────
    private static void set(World w, int x, int y, int z, Material m) {
        w.getBlockAt(x, y, z).setType(m, false);
    }

    private static void set(World w, int x, int y, int z, BlockData d) {
        w.getBlockAt(x, y, z).setBlockData(d, false);
    }

    private static void box(World w, int x0, int y0, int z0, int x1, int y1, int z1, Material m) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    set(w, x, y, z, m);
                }
            }
        }
    }

    /** 세운 통나무 (적주·기둥). */
    private static void postY(World w, int x, int y0, int y1, int z, Material log) {
        BlockData d = bd(key(log) + "[axis=y]");
        for (int y = y0; y <= y1; y++) set(w, x, y, z, d);
    }

    /** 누운 보 (x 축). */
    private static void beamX(World w, int x0, int x1, int y, int z, Material log) {
        BlockData d = bd(key(log) + "[axis=x]");
        for (int x = x0; x <= x1; x++) set(w, x, y, z, d);
    }

    /** 누운 보 (z 축). */
    private static void beamZ(World w, int x, int y, int z0, int z1, Material log) {
        BlockData d = bd(key(log) + "[axis=z]");
        for (int z = z0; z <= z1; z++) set(w, x, y, z, d);
    }

    private static void stair(World w, int x, int y, int z, Material m, String facing, boolean top, String shape) {
        set(w, x, y, z, bd(key(m) + "[facing=" + facing + ",half=" + (top ? "top" : "bottom")
                + ",shape=" + shape + ",waterlogged=false]"));
    }

    private static void slab(World w, int x, int y, int z, Material m, boolean top) {
        set(w, x, y, z, bd(key(m) + "[type=" + (top ? "top" : "bottom") + ",waterlogged=false]"));
    }

    /** 격자창 한 칸 — 열린 나무 뚜껑문이 벽면에 서서 살창이 된다. */
    private static void lattice(World w, int x, int y, int z, String facing, Material trap) {
        set(w, x, y, z, bd(key(trap) + "[facing=" + facing + ",half=bottom,open=true,powered=false,waterlogged=false]"));
    }

    /** 사슬에 매단 등롱 — 처마 밑 리듬. */
    private static void lantern(World w, int x, int yTop, int z, int chain) {
        BlockData c = bd("minecraft:iron_bars[north=false,south=false,east=false,west=false,waterlogged=false]");
        for (int i = 0; i < chain; i++) set(w, x, yTop - i, z, c);
        set(w, x, yTop - chain, z, bd("minecraft:lantern[hanging=true,waterlogged=false]"));
    }

    /** 기단을 땅까지 내려 채운다 — 뜬 블록 금지 (구조적 정직성). */
    private static void fillDown(World w, int x, int yFrom, int z, int floor) {
        for (int y = yFrom; y >= floor; y--) {
            // 땅을 만나면 멈춘다 — 그 아래는 이미 산이다 (레지스트리 없이 도는 단순 비교)
            if (y != yFrom && w.getBlockAt(x, y, z).getType() != Material.AIR) break;
            set(w, x, y, z, podium(x, y, z));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  본체
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * 문루를 세운다.
     *
     * @param w  월드
     * @param ox 정면 중앙 x
     * @param oy 정면 중앙 지면 y (기단 상면 = 문지방)
     * @param oz 정면 z (남쪽이 바깥)
     */
    public static void build(World w, int ox, int oy, int oz) {
        podiumAndSteps(w, ox, oy, oz);
        lowerStory(w, ox, oy, oz);
        passages(w, ox, oy, oz);
        porch(w, ox, oy, oz);
        skirtEave(w, ox, oy, oz);
        balcony(w, ox, oy, oz);
        upperStory(w, ox, oy, oz);
        mainRoof(w, ox, oy, oz);
        groundLanterns(w, ox, oy, oz);
    }

    // ── ① 기단 — 밝고 매끈한 수평선 ──────────────────────────────────────────
    private static void podiumAndSteps(World w, int ox, int oy, int oz) {
        int x0 = ox - HALF - 4, x1 = ox + HALF + 4;
        int z0 = oz - DEPTH - 4, z1 = oz + PORCH_OUT + 1;   // 앞 계약 z ≤ oz+10 을 계단 몫으로 남긴다
        int floor = oy - 4;

        // 몸통: 상면 아래 세 켜 + 땅까지 접지
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = oy; y >= oy - 3; y--) set(w, x, y, z, podium(x, y, z));
                fillDown(w, x, oy - 4, z, floor);
            }
        }
        // 상면은 매끈하게 — 기단은 건물보다 밝다 (재료 층위 ①)
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int h = hash(x, oy, z) % 100;
                set(w, x, oy, z, h < 68 ? Material.POLISHED_ANDESITE
                        : h < 88 ? Material.SMOOTH_STONE : Material.STONE_BRICKS);
            }
        }
        // 가장자리 몰딩 — 기단 테두리에 그림자 선 하나. ★문 앞은 비운다 (문지방을 막지 않는다)
        for (int x = x0; x <= x1; x++) {
            if (Math.abs(x - ox) > ARCH_HALF + 1) {
                slab(w, x, oy + 1, z1, Material.STONE_BRICK_SLAB, false);
            }
            slab(w, x, oy + 1, z0, Material.STONE_BRICK_SLAB, false);
        }
        for (int z = z0; z <= z1; z++) {
            slab(w, x1, oy + 1, z, Material.STONE_BRICK_SLAB, false);
            slab(w, x0, oy + 1, z, Material.STONE_BRICK_SLAB, false);
        }
        // 정면 진입 계단 — 대계단(20폭)이 닿는 자리
        for (int x = ox - 10; x <= ox + 10; x++) {
            for (int k = 0; k < 3; k++) {
                int z = z1 + 1 + k;
                stair(w, x, oy - k, z, Material.STONE_BRICK_STAIRS, "south", false, "straight");
                fillDown(w, x, oy - k - 1, z, oy - 4);
            }
        }
        // 계단 곁 소맷돌
        for (int side = -1; side <= 1; side += 2) {
            int x = ox + side * 11;
            for (int k = 0; k < 3; k++) {
                set(w, x, oy - k + 1, z1 + 1 + k, Material.POLISHED_ANDESITE);
                fillDown(w, x, oy - k, z1 + 1 + k, oy - 4);
            }
        }
    }

    // ── ② 하층 — 아치 셋 · 적주 · 백벽 · 격자창 ──────────────────────────────
    private static void lowerStory(World w, int ox, int oy, int oz) {
        int yTop = oy + WALL_H;                    // g+12
        int zBack = oz - DEPTH;

        // 벽체: 앞·뒤 두 켜 두께 (깊이감 — 창이 파고들 자리를 남긴다)
        for (int x = ox - HALF; x <= ox + HALF; x++) {
            for (int y = oy + 1; y <= yTop; y++) {
                for (int z = zBack; z <= oz; z++) {
                    boolean shell = (z == oz || z == zBack || x == ox - HALF || x == ox + HALF);
                    if (!shell) continue;
                    set(w, x, y, z, plaster(x, y, z));
                }
            }
        }
        // 벽 속을 채워 두께를 준다 (뚫린 껍데기 금지)
        box(w, ox - HALF, oy + 1, oz - 1, ox + HALF, yTop, oz - 1, Material.STONE_BRICKS);
        box(w, ox - HALF, oy + 1, zBack + 1, ox + HALF, yTop, zBack + 1, Material.STONE_BRICKS);

        // 적주 열주 — 간격 6, 벽면에 박혀 보를 받는다 (구조적 정직성)
        for (int x = ox - HALF; x <= ox + HALF; x += COL_STEP) {
            postY(w, x, oy + 1, yTop, oz, Material.STRIPPED_MANGROVE_LOG);
            postY(w, x, oy + 1, yTop, zBack, Material.STRIPPED_MANGROVE_LOG);
        }
        // 모서리 귀기둥은 두 겹으로 굵게
        for (int side = -1; side <= 1; side += 2) {
            int x = ox + side * HALF;
            postY(w, x, oy + 1, yTop, oz, Material.MANGROVE_LOG);
            postY(w, x, oy + 1, yTop, zBack, Material.MANGROVE_LOG);
            for (int z = zBack; z <= oz; z++) {
                if (z == oz || z == zBack) continue;
                postY(w, x, oy + 1, yTop, z, Material.STRIPPED_MANGROVE_LOG);
            }
        }

        // 중앙 아치 11×12 — 앞뒤로 관통
        carveArch(w, ox, oy, oz, zBack, ARCH_HALF, WALL_H);
        // 곁 아치 5×8 두 짝 (±18)
        carveArch(w, ox - SIDE_X, oy, oz, zBack, SIDE_HALF, SIDE_H);
        carveArch(w, ox + SIDE_X, oy, oz, zBack, SIDE_HALF, SIDE_H);

        // 격자창 — 아치 사이 백벽에 (디테일 밀도 ①)
        int[] winX = {ox - 25, ox - 12, ox + 12, ox + 25};
        for (int wx : winX) {
            latticeWindow(w, wx, oy + 4, oz, "south", 2, 4);
            latticeWindow(w, wx, oy + 4, zBack, "north", 2, 4);
        }
        // 측면에도 하나씩 — 옆에서 봐도 큰 면이 안 남는다
        for (int side = -1; side <= 1; side += 2) {
            int x = ox + side * HALF;
            for (int zc : new int[]{oz - 4, oz - 8}) {
                for (int dy = 0; dy < 4; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        lattice(w, x, oy + 5 + dy, zc + dz, side < 0 ? "west" : "east",
                                Material.DARK_OAK_TRAPDOOR);
                    }
                }
                for (int dy = -1; dy <= 4; dy++) {
                    set(w, x, oy + 4 + dy, zc - 2, Material.STRIPPED_DARK_OAK_LOG);
                    set(w, x, oy + 4 + dy, zc + 2, Material.STRIPPED_DARK_OAK_LOG);
                }
            }
        }

        // 보 + 공포 — 처마를 무엇이 받치는가 (구조적 정직성)
        bracketBand(w, ox, oy + WALL_H + 1, oz, zBack, HALF);
    }

    /** 아치 하나를 앞뒤로 뚫고 어깨를 계단으로 둥글린다. */
    private static void carveArch(World w, int cx, int oy, int oz, int zBack, int half, int h) {
        for (int x = cx - half; x <= cx + half; x++) {
            for (int y = oy + 1; y <= oy + h; y++) {
                for (int z = zBack; z <= oz; z++) set(w, x, y, z, Material.AIR);
            }
        }
        // 문틀: 적주 한 쌍이 개구를 물고 선다
        for (int side = -1; side <= 1; side += 2) {
            int x = cx + side * (half + 1);
            postY(w, x, oy + 1, oy + h + 1, oz, Material.STRIPPED_MANGROVE_LOG);
            postY(w, x, oy + 1, oy + h + 1, zBack, Material.STRIPPED_MANGROVE_LOG);
        }
        // 인방(상인방) — 개구 위를 가로지르는 보
        beamX(w, cx - half - 1, cx + half + 1, oy + h + 1, oz, Material.MANGROVE_LOG);
        beamX(w, cx - half - 1, cx + half + 1, oy + h + 1, zBack, Material.MANGROVE_LOG);
        // 어깨 — 개구 맨 위 양끝을 계단으로 접어 아치로 읽히게
        for (int z : new int[]{oz, zBack}) {
            stair(w, cx - half, oy + h, z, Material.DEEPSLATE_TILE_STAIRS, "east", true, "straight");
            stair(w, cx + half, oy + h, z, Material.DEEPSLATE_TILE_STAIRS, "west", true, "straight");
        }
    }

    /** 격자창 하나 — 나무 테두리 + 살창 + 덧문 두 짝. */
    private static void latticeWindow(World w, int cx, int y0, int z, String facing, int half, int h) {
        Material trap = ((cx & 1) == 0) ? Material.DARK_OAK_TRAPDOOR : Material.MANGROVE_TRAPDOOR;
        for (int x = cx - half; x <= cx + half; x++) {
            for (int y = y0; y < y0 + h; y++) {
                set(w, x, y, z, Material.AIR);
                set(w, x, y, z + ("south".equals(facing) ? -1 : 1), Material.POLISHED_DEEPSLATE); // 안쪽 그늘
                lattice(w, x, y, z, facing, trap);
            }
        }
        // 테두리
        for (int y = y0 - 1; y <= y0 + h; y++) {
            set(w, cx - half - 1, y, z, Material.STRIPPED_DARK_OAK_LOG);
            set(w, cx + half + 1, y, z, Material.STRIPPED_DARK_OAK_LOG);
        }
        beamX(w, cx - half - 1, cx + half + 1, y0 - 1, z, Material.DARK_OAK_LOG);
        beamX(w, cx - half - 1, cx + half + 1, y0 + h, z, Material.DARK_OAK_LOG);
        // 차양 — 창 위에 반블록 한 켜 (그늘 선)
        for (int x = cx - half - 1; x <= cx + half + 1; x++) {
            slab(w, x, y0 + h + 1, z + ("south".equals(facing) ? 1 : -1), Material.DARK_OAK_SLAB, true);
        }
    }

    /** 공포 띠 — 보 → 팔 → 받침 켜. 처마가 허공에서 시작하지 않는다. */
    private static void bracketBand(World w, int ox, int y, int oz, int zBack, int half) {
        // 보: 네 면을 두른다
        beamX(w, ox - half, ox + half, y, oz, Material.DARK_OAK_LOG);
        beamX(w, ox - half, ox + half, y, zBack, Material.DARK_OAK_LOG);
        beamZ(w, ox - half, y, zBack, oz, Material.DARK_OAK_LOG);
        beamZ(w, ox + half, y, zBack, oz, Material.DARK_OAK_LOG);

        // 팔(첨차): 3칸마다 밖으로 두 칸 뻗는다
        for (int x = ox - half; x <= ox + half; x += 3) {
            for (int d = 1; d <= 2; d++) {
                set(w, x, y, oz + d, Material.DARK_OAK_WOOD);
                set(w, x, y, zBack - d, Material.DARK_OAK_WOOD);
            }
        }
        for (int z = zBack; z <= oz; z += 3) {
            for (int d = 1; d <= 2; d++) {
                set(w, ox - half - d, y, z, Material.DARK_OAK_WOOD);
                set(w, ox + half + d, y, z, Material.DARK_OAK_WOOD);
            }
        }
        // 받침 켜: 팔 위에 반블록 한 바퀴 (그림자 선)
        for (int x = ox - half - 3; x <= ox + half + 3; x++) {
            slab(w, x, y + 1, oz + 3, Material.DARK_OAK_SLAB, true);
            slab(w, x, y + 1, zBack - 3, Material.DARK_OAK_SLAB, true);
        }
        for (int z = zBack - 3; z <= oz + 3; z++) {
            slab(w, ox - half - 3, y + 1, z, Material.DARK_OAK_SLAB, true);
            slab(w, ox + half + 3, y + 1, z, Material.DARK_OAK_SLAB, true);
        }
    }

    // ── ③ 통로 — 문을 지나가는 경험 ──────────────────────────────────────────
    private static void passages(World w, int ox, int oy, int oz) {
        int zBack = oz - DEPTH;
        // 바닥: 통로만 다른 결 (걸으면 발밑이 바뀐다)
        for (int x = ox - ARCH_HALF; x <= ox + ARCH_HALF; x++) {
            for (int z = zBack - 2; z <= oz + PORCH_OUT + 2; z++) {
                int h = hash(x, oy, z) % 100;
                set(w, x, oy, z, h < 55 ? Material.STONE_BRICKS
                        : h < 80 ? Material.POLISHED_ANDESITE : Material.ANDESITE);
            }
        }
        // 천장 우물반자: 3칸마다 보가 지난다
        for (int z = zBack; z <= oz; z++) {
            boolean beam = ((z - zBack) % 3 == 0);
            for (int x = ox - ARCH_HALF; x <= ox + ARCH_HALF; x++) {
                set(w, x, oy + WALL_H, z, beam ? Material.DARK_OAK_WOOD : Material.POLISHED_DEEPSLATE);
            }
        }
        // 통로 벽 감실 + 등롱 — 어두운 터널에 온광 리듬 (그늘·빛 대비).
        // ★감실은 문설주(개구 밖) 안에 판다 — 통행 폭을 한 칸도 안 먹는다
        for (int z = zBack + 2; z <= oz - 2; z += 4) {
            for (int side = -1; side <= 1; side += 2) {
                int x = ox + side * (ARCH_HALF + 1);
                set(w, x, oy + 5, z, bd("minecraft:lantern[hanging=false,waterlogged=false]"));
            }
        }
        // 곁 통로 천장
        for (int cx : new int[]{ox - SIDE_X, ox + SIDE_X}) {
            for (int z = zBack; z <= oz; z++) {
                for (int x = cx - SIDE_HALF; x <= cx + SIDE_HALF; x++) {
                    set(w, x, oy + SIDE_H, z, Material.DARK_OAK_WOOD);
                }
            }
            // 곁 통로 등롱은 문설주 쪽에 붙인다 — 가운데를 비워 머리가 안 닿게
            for (int side = -1; side <= 1; side += 2) {
                int x = cx + side * SIDE_HALF;
                set(w, x, oy + SIDE_H - 2, oz - 3, bd("minecraft:lantern[hanging=false,waterlogged=false]"));
                set(w, x, oy + SIDE_H - 2, zBack + 3, bd("minecraft:lantern[hanging=false,waterlogged=false]"));
            }
        }
    }

    // ── ④ 중앙 포치 — 앞으로 6칸 나와 중심을 만든다 (위계 ②) ─────────────────
    private static void porch(World w, int ox, int oy, int oz) {
        int zF = oz + PORCH_OUT;                  // 포치 앞면
        int yTop = oy + WALL_H + 1;               // g+13

        // 네 귀 + 사이 적주: 기둥이 보를 받고 보가 지붕을 받는다
        for (int x = ox - PORCH_HALF; x <= ox + PORCH_HALF; x += COL_STEP) {
            if (Math.abs(x - ox) <= ARCH_HALF) continue;   // 통로는 비운다
            postY(w, x, oy + 1, yTop - 1, zF, Material.STRIPPED_MANGROVE_LOG);
            postY(w, x, oy + 1, yTop - 1, oz + 1, Material.STRIPPED_MANGROVE_LOG);
        }
        for (int side = -1; side <= 1; side += 2) {
            int x = ox + side * PORCH_HALF;
            postY(w, x, oy + 1, yTop - 1, zF, Material.MANGROVE_LOG);
            // 옆벽: 백벽 + 격자창 — 포치 옆구리에도 정보량을 준다
            for (int z = oz + 1; z <= zF; z++) {
                for (int y = oy + 1; y < yTop - 1; y++) set(w, x, y, z, plaster(x, y, z));
            }
            for (int dy = 0; dy < 4; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    lattice(w, x, oy + 5 + dy, oz + 3 + dz, side < 0 ? "west" : "east",
                            Material.MANGROVE_TRAPDOOR);
                }
            }
        }
        // 포치 앞면 벽 — 아치 양옆만 (통로는 열려 있다)
        for (int x = ox - PORCH_HALF; x <= ox + PORCH_HALF; x++) {
            if (Math.abs(x - ox) <= ARCH_HALF + 1) continue;
            for (int y = oy + 1; y < yTop - 1; y++) {
                if ((x - ox) % COL_STEP == 0) continue;
                set(w, x, y, zF, plaster(x, y, zF));
            }
        }
        // 포치 앞면 격자창 두 짝
        latticeWindow(w, ox - 9, oy + 5, zF, "south", 1, 4);
        latticeWindow(w, ox + 9, oy + 5, zF, "south", 1, 4);

        // 인방 + 공포
        beamX(w, ox - PORCH_HALF, ox + PORCH_HALF, yTop - 1, zF, Material.MANGROVE_LOG);
        beamX(w, ox - PORCH_HALF, ox + PORCH_HALF, yTop, zF, Material.DARK_OAK_LOG);
        beamZ(w, ox - PORCH_HALF, yTop, oz, zF, Material.DARK_OAK_LOG);
        beamZ(w, ox + PORCH_HALF, yTop, oz, zF, Material.DARK_OAK_LOG);
        for (int x = ox - PORCH_HALF; x <= ox + PORCH_HALF; x += 3) {
            for (int d = 1; d <= 2; d++) set(w, x, yTop, zF + d, Material.DARK_OAK_WOOD);
        }

        // ★ 현판 — 빈 금판, 아치 바로 위 중앙 (글자 없음)
        int plaqueY = oy + WALL_H - 2;
        for (int x = ox - 2; x <= ox + 2; x++) {
            set(w, x, plaqueY, zF + 1, Material.GOLD_BLOCK);
            set(w, x, plaqueY + 1, zF + 1, Material.GOLD_BLOCK);
        }
        for (int y = plaqueY - 1; y <= plaqueY + 2; y++) {
            set(w, ox - 3, y, zF + 1, Material.STRIPPED_DARK_OAK_LOG);
            set(w, ox + 3, y, zF + 1, Material.STRIPPED_DARK_OAK_LOG);
        }
        beamX(w, ox - 3, ox + 3, plaqueY - 1, zF + 1, Material.DARK_OAK_LOG);
        beamX(w, ox - 3, ox + 3, plaqueY + 2, zF + 1, Material.DARK_OAK_LOG);
        // 현판 곁 등롱 한 쌍
        lantern(w, ox - 5, plaqueY + 1, zF + 1, 1);
        lantern(w, ox + 5, plaqueY + 1, zF + 1, 1);

        // 포치 지붕 — 제 몫의 우진각 (겹처마 층서의 맨 앞 켜)
        hipRoof(w, ox - PORCH_ROOF_HALF, ox + PORCH_ROOF_HALF, oz - 1, zF + 3, oy + WALL_H + 3, 6, true);
        // 처마 밑 등롱 — 포치 앞 리듬
        for (int x : new int[]{ox - 11, ox - 7, ox + 7, ox + 11}) {
            lantern(w, x, oy + WALL_H + 2, zF + 2, 1);
        }
    }

    // ── ⑤ 겹처마 스커트 — 하층과 상층 사이의 켜 ──────────────────────────────
    private static void skirtEave(World w, int ox, int oy, int oz) {
        int zBack = oz - DEPTH;
        int yBase = oy + WALL_H + 3;              // g+15 — 공포 받침 위
        for (int r = 0; r < 3; r++) {
            int half = SKIRT_OUT - r;
            int z0 = zBack - 4 + r, z1 = oz + 4 - r;
            int y = yBase + r;
            ringStairs(w, ox - half, ox + half, z0, z1, y, ox, oz, true);
        }
        // 스커트 밑을 채워 하늘이 안 비치게
        for (int r = 0; r < 3; r++) {
            int half = SKIRT_OUT - r;
            int z0 = zBack - 4 + r, z1 = oz + 4 - r;
            int y = yBase + r;
            for (int x = ox - half + 1; x <= ox + half - 1; x++) {
                for (int z = z0 + 1; z <= z1 - 1; z++) {
                    if (Math.abs(x - ox) <= HALF - 1 && z < oz && z > zBack) continue;
                    if (skipForPorch(ox, oz, x, z)) continue;
                    set(w, x, y, z, tile(x, y, z));
                }
            }
        }
    }

    /** 포치가 선 자리에는 스커트를 두지 않는다 (두 지붕이 겹치면 지저분하다). */
    private static boolean skipForPorch(int ox, int oz, int x, int z) {
        return z > oz - 2 && Math.abs(x - ox) <= PORCH_ROOF_HALF + 1;
    }

    private static void ringStairs(World w, int x0, int x1, int z0, int z1, int y,
                                   int ox, int oz, boolean porchSkip) {
        for (int x = x0; x <= x1; x++) {
            // 포치가 선 자리(정면 중앙)에는 스커트를 두지 않는다 — 두 지붕이 겹치면 지저분하다
            if (!porchSkip || !skipForPorch(ox, oz, x, z1)) {
                stair(w, x, y, z1, tileStair(x, y, z1), "south", false, "straight");
            }
            stair(w, x, y, z0, tileStair(x, y, z0), "north", false, "straight");
        }
        for (int z = z0; z <= z1; z++) {
            if (porchSkip && skipForPorch(ox, oz, x1, z)) continue;
            stair(w, x1, y, z, tileStair(x1, y, z), "east", false, "straight");
            stair(w, x0, y, z, tileStair(x0, y, z), "west", false, "straight");
        }
        // 네 귀 — 바깥으로 한 칸 더, 한 칸 위 (처마 끝 들림)
        upturn(w, x0, y, z0, "west", "north");
        upturn(w, x1, y, z0, "east", "north");
        if (!porchSkip || !skipForPorch(ox, oz, x0, z1)) {
            upturn(w, x0, y, z1, "west", "south");
            upturn(w, x1, y, z1, "east", "south");
        }
    }

    /** 처마 끝 들림 — 귀에서 한 칸 밖·한 칸 위로 뻗는 날개. */
    private static void upturn(World w, int x, int y, int z, String ew, String ns) {
        int dx = "west".equals(ew) ? -1 : 1;
        int dz = "north".equals(ns) ? -1 : 1;
        stair(w, x + dx, y, z, tileStair(x + dx, y, z), ew, false, "straight");
        stair(w, x, y, z + dz, tileStair(x, y, z + dz), ns, false, "straight");
        slab(w, x + dx, y + 1, z + dz, Material.DEEPSLATE_TILE_SLAB, false);   // 날개 끝이 한 칸 솟는다
    }

    // ── ⑥ 누마루 — 상층 앞의 발코니 (사람 크기의 켜) ─────────────────────────
    private static void balcony(World w, int ox, int oy, int oz) {
        int y = oy + WALL_H + 6;                  // g+18 — 스커트 위
        int zBack = oz - DEPTH;
        for (int x = ox - SKIRT_OUT + 3; x <= ox + SKIRT_OUT - 3; x++) {
            for (int z = zBack - 1; z <= oz + 1; z++) {
                boolean outside = Math.abs(x - ox) > UP_HALF || z > oz - 1 || z < zBack + 1;
                if (!outside) continue;
                if (skipForPorch(ox, oz, x, z)) continue;
                set(w, x, y, z, (hash(x, y, z) % 100) < 70 ? Material.POLISHED_ANDESITE : Material.STONE_BRICKS);
            }
        }
        // 난간 — 담장 + 등롱 기둥
        int half = SKIRT_OUT - 3;
        for (int x = ox - half; x <= ox + half; x++) {
            for (int z : new int[]{zBack - 1, oz + 1}) {
                if (skipForPorch(ox, oz, x, z)) continue;
                boolean post = ((x - ox) % 6 == 0);
                set(w, x, y + 1, z, post ? Material.STRIPPED_MANGROVE_LOG : Material.MANGROVE_FENCE);
                if (post) {
                    set(w, x, y + 2, z, bd("minecraft:lantern[hanging=false,waterlogged=false]"));
                }
            }
        }
        for (int z = zBack - 1; z <= oz + 1; z++) {
            for (int x : new int[]{ox - half, ox + half}) {
                boolean post = ((z - oz) % 6 == 0);
                set(w, x, y + 1, z, post ? Material.STRIPPED_MANGROVE_LOG : Material.MANGROVE_FENCE);
                if (post) set(w, x, y + 2, z, bd("minecraft:lantern[hanging=false,waterlogged=false]"));
            }
        }
    }

    // ── ⑦ 상층 누각 — 적주 리듬 + 격자창 ─────────────────────────────────────
    private static void upperStory(World w, int ox, int oy, int oz) {
        int y0 = oy + WALL_H + 6;                 // g+18 (누마루 바닥)
        int y1 = y0 + UP_H;                       // g+27
        int zF = oz - 1, zB = oz - DEPTH + 1;

        // 벽 + 기둥
        for (int x = ox - UP_HALF; x <= ox + UP_HALF; x++) {
            for (int y = y0 + 1; y <= y1; y++) {
                for (int z = zB; z <= zF; z++) {
                    boolean shell = (z == zF || z == zB || x == ox - UP_HALF || x == ox + UP_HALF);
                    if (!shell) continue;
                    set(w, x, y, z, plaster(x, y, z));
                }
            }
        }
        box(w, ox - UP_HALF, y0 + 1, zF - 1, ox + UP_HALF, y1, zF - 1, Material.STONE_BRICKS);
        for (int x = ox - UP_HALF; x <= ox + UP_HALF; x += COL_STEP) {
            postY(w, x, y0 + 1, y1, zF, Material.STRIPPED_MANGROVE_LOG);
            postY(w, x, y0 + 1, y1, zB, Material.STRIPPED_MANGROVE_LOG);
        }
        for (int side = -1; side <= 1; side += 2) {
            int x = ox + side * UP_HALF;
            postY(w, x, y0 + 1, y1, zF, Material.MANGROVE_LOG);
            postY(w, x, y0 + 1, y1, zB, Material.MANGROVE_LOG);
            for (int z = zB; z <= zF; z += 4) postY(w, x, y0 + 1, y1, z, Material.STRIPPED_MANGROVE_LOG);
        }
        // 중방 — 벽을 두 켜로 나누는 수평선
        beamX(w, ox - UP_HALF, ox + UP_HALF, y0 + 5, zF, Material.DARK_OAK_LOG);
        beamX(w, ox - UP_HALF, ox + UP_HALF, y0 + 5, zB, Material.DARK_OAK_LOG);

        // 격자창 — 기둥 사이마다 (상층은 창이 촘촘한 것이 누각의 결)
        for (int x = ox - UP_HALF + 3; x <= ox + UP_HALF - 3; x += COL_STEP) {
            latticeWindow(w, x, y0 + 2, zF, "south", 1, 3);
            latticeWindow(w, x, y0 + 7, zF, "south", 1, 2);
            latticeWindow(w, x, y0 + 2, zB, "north", 1, 3);
        }
        // 보 + 공포
        bracketBand(w, ox, y1 + 1, zF, zB, UP_HALF);
        // 처마 밑 등롱 — 6칸 리듬
        for (int x = ox - UP_HALF + 3; x <= ox + UP_HALF - 3; x += COL_STEP) {
            lantern(w, x, y1, zF + 3, 1);
        }
    }

    // ── ⑧ 주 지붕 — 우진각 · 용마루 · 치미 ───────────────────────────────────
    private static void mainRoof(World w, int ox, int oy, int oz) {
        int yEave = oy + WALL_H + 15;             // g+27
        int zF = oz + 3, zB = oz - DEPTH + 1 - 4; // 상층에서 4 내밈
        hipRoof(w, ox - ROOF_HALF, ox + ROOF_HALF, zB, zF, yEave, 9, false);
    }

    /**
     * 우진각 지붕 한 채 — 링을 하나씩 올리고, 남은 마루를 용마루로 덮고, 끝에 치미를 세운다.
     *
     * @param rings 링 수 (깊이의 절반쯤)
     * @param small 작은 지붕이면 치미를 낮게 (과밀 방지)
     */
    private static void hipRoof(World w, int x0, int x1, int z0, int z1, int yEave, int rings, boolean small) {
        int ax0 = x0, ax1 = x1, az0 = z0, az1 = z1;
        int y = yEave;
        for (int r = 0; r < rings; r++) {
            if (ax0 > ax1 || az0 > az1) break;
            // 처마 켜: 바깥을 향한 계단
            for (int x = ax0; x <= ax1; x++) {
                stair(w, x, y, az1, tileStair(x, y, az1), "south", false, "straight");
                stair(w, x, y, az0, tileStair(x, y, az0), "north", false, "straight");
            }
            for (int z = az0; z <= az1; z++) {
                stair(w, ax1, y, z, tileStair(ax1, y, z), "east", false, "straight");
                stair(w, ax0, y, z, tileStair(ax0, y, z), "west", false, "straight");
            }
            // 안쪽 한 켜는 통 블록으로 채워 물이 안 새게 (밑에서 봐도 뚫리지 않는다)
            for (int x = ax0 + 1; x <= ax1 - 1; x++) {
                set(w, x, y, az1 - 1, tile(x, y, az1 - 1));
                set(w, x, y, az0 + 1, tile(x, y, az0 + 1));
            }
            for (int z = az0 + 1; z <= az1 - 1; z++) {
                set(w, ax1 - 1, y, z, tile(ax1 - 1, y, z));
                set(w, ax0 + 1, y, z, tile(ax0 + 1, y, z));
            }
            if (r == 0) {
                upturn(w, ax0, y, az0, "west", "north");
                upturn(w, ax0, y, az1, "west", "south");
                upturn(w, ax1, y, az0, "east", "north");
                upturn(w, ax1, y, az1, "east", "south");
            }
            ax0++; ax1--; az0++; az1--; y++;
        }
        // 용마루 — 남은 마루를 다듬돌로 덮고 반블록을 얹는다
        for (int x = ax0 - 1; x <= ax1 + 1; x++) {
            for (int z = az0 - 1; z <= az1 + 1; z++) {
                set(w, x, y - 1, z, Material.POLISHED_DEEPSLATE);
            }
        }
        for (int x = ax0 - 1; x <= ax1 + 1; x++) {
            for (int z = az0 - 1; z <= az1 + 1; z++) {
                slab(w, x, y, z, Material.POLISHED_DEEPSLATE_SLAB, false);
            }
        }
        // 치미 — 용마루 양 끝이 솟는다 (실루엣의 마침표)
        int rz = (az0 + az1) / 2;
        int up = small ? 2 : 3;
        for (int side = 0; side < 2; side++) {
            int x = (side == 0) ? ax0 - 1 : ax1 + 1;
            for (int k = 0; k < up; k++) {
                set(w, x, y + k, rz, Material.POLISHED_DEEPSLATE);
                if (k == up - 1) {
                    set(w, x, y + k + 1, rz, bd("minecraft:polished_deepslate_wall"
                            + "[up=true,north=none,south=none,east=none,west=none,waterlogged=false]"));
                }
            }
            // 내림마루 — 치미에서 처마로 흘러내리는 선
            int dx = (side == 0) ? 1 : -1;
            for (int k = 1; k <= 3; k++) {
                slab(w, x + dx * k, y - k, rz, Material.POLISHED_DEEPSLATE_SLAB, true);
            }
        }
    }

    // ── ⑨ 마당 등롱 — 문 앞의 사람 크기 사물 ─────────────────────────────────
    private static void groundLanterns(World w, int ox, int oy, int oz) {
        // 포치 옆구리를 끼고 선다 (포치는 ±13 — 그 밖에 두어야 문 앞이 안 막힌다)
        for (int side = -1; side <= 1; side += 2) {
            for (int k = 0; k < 2; k++) {
                int x = ox + side * (16 + k * 6);
                int z = oz + PORCH_OUT + 1 - k * 4;
                set(w, x, oy + 1, z, Material.STONE_BRICKS);
                set(w, x, oy + 2, z, Material.POLISHED_ANDESITE);
                set(w, x, oy + 3, z, bd("minecraft:cobblestone_wall"
                        + "[up=true,north=none,south=none,east=none,west=none,waterlogged=false]"));
                set(w, x, oy + 4, z, bd("minecraft:lantern[hanging=false,waterlogged=false]"));
                slab(w, x, oy + 5, z, Material.STONE_BRICK_SLAB, false);
            }
        }
    }
}
