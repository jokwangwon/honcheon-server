package com.honcheon.mvt;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 청하현 규칙 린트 — 조성된 마을을 '눈'이 아니라 '자'로 검수한다.
 *
 * <p>원칙: 조성기(CheonghaBuilder)의 상수를 한 줄도 믿지 않는다. 전부 월드의 실제 블록을 읽어
 * 측정한다 — 조성기가 의도한 값이 아니라 조성기가 실제로 놓은 값이 검수 대상이다.
 * 난수 없음(결정론) — 같은 마을이면 같은 보고서.
 *
 * <p>기준 문서: docs/design/building_style_guide.md
 *
 * <pre>
 *   MvtCommand 호출 예:
 *     for (String line : TownAudit.audit(player.getWorld(), plugin.anchors(), cx, cy, cz)) {
 *         player.sendMessage(line);
 *     }
 * </pre>
 */
public final class TownAudit {

    private TownAudit() {
    }

    // ─── 기대 수치 (가이드) ───

    private static final int BOULEVARD_MIN = 7;      // 십자대로 기대 폭
    private static final int ALLEY_MIN = 3;          // 골목 기대 폭
    private static final int DOORPATH_MIN = 2;       // 문 앞 소로 기대 폭
    private static final int EAVE_MIN = 2;           // 처마 내밀기 기대 (가이드 최소 1, 관아급·객잔 정면 2 — 검수는 2로 조인다)
    private static final int PROP_MAX_PER_WALL = 3;  // 실내 벽면당 장식 상한 (초과 = 과밀)
    private static final double FLOOR_FREE_MIN = 0.55;   // 바닥 여백률 하한 (55% 미만이면 발 디딜 곳이 없다)
    private static final double INK_MAX_PCT = 2.0;   // 채색 블록 허용 비율 (수묵 3색 지배)
    private static final double DARK_MAX_PCT = 15.0; // 길 위 암흑(빛<7) 허용 비율
    private static final int SCAN_R = 65;            // 마을 스캔 반경
    private static final int SHRINE_DX = -75;        // 폐사당 중심 (마을 중심 기준)
    private static final int SHRINE_DZ = -75;
    private static final int SHRINE_R = 20;          // 냉색 허용 반경

    private static final String OK = "§a✅ ";
    private static final String WARN = "§e⚠ ";
    private static final String BAD = "§c❌ ";
    private static final String INFO = "§7";
    private static final String HEAD = "§6";

    // ─── 자재 분류 ───

    /**
     * 길로 세는 자재 — 흙길·자갈 갓길에 더해 포장(디딤돌·광장 결)까지.
     * 디딤돌 한 줄이 대로를 가로지르면 스팬이 0으로 끊겨 "대로 폭 0"이라는 오탐이 났다 (v6.2 관측).
     * 사람이 밟고 다니는 면이면 길이다.
     */
    private static final Set<Material> PATH = EnumSet.of(
            Material.DIRT_PATH, Material.GRAVEL,
            Material.COBBLESTONE, Material.ANDESITE, Material.STONE_BRICKS, Material.SMOOTH_STONE,
            Material.MOSSY_COBBLESTONE, Material.STONE);

    private static final Set<Material> ROOF = EnumSet.of(
            Material.DEEPSLATE_TILES, Material.DEEPSLATE_TILE_STAIRS, Material.DEEPSLATE_TILE_SLAB,
            Material.DEEPSLATE_BRICKS, Material.DEEPSLATE_BRICK_STAIRS, Material.DEEPSLATE_BRICK_SLAB,
            Material.DARK_OAK_STAIRS, Material.DARK_OAK_SLAB,
            Material.OXIDIZED_CUT_COPPER, Material.OXIDIZED_CUT_COPPER_STAIRS, Material.OXIDIZED_CUT_COPPER_SLAB);

    private static final Set<Material> PROP = EnumSet.of(
            Material.DECORATED_POT, Material.CHISELED_BOOKSHELF, Material.BOOKSHELF,
            Material.LANTERN, Material.SOUL_LANTERN, Material.BARREL, Material.CHEST,
            Material.LECTERN, Material.CANDLE, Material.BREWING_STAND, Material.CAULDRON,
            Material.FLOWER_POT, Material.POTTED_BAMBOO, Material.POTTED_CHERRY_SAPLING,
            Material.POTTED_POPPY, Material.POTTED_FERN, Material.LOOM, Material.SMITHING_TABLE,
            Material.ANVIL, Material.BLAST_FURNACE, Material.SMOKER, Material.COMPOSTER,
            Material.NOTE_BLOCK, Material.HAY_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            // 1.21.11 선반 — 시렁도 장식이다. 물건이 얹힌다고 예산 밖은 아니다 (벽 한 면 3점 규칙 유지).
            Material.OAK_SHELF, Material.SPRUCE_SHELF, Material.BIRCH_SHELF, Material.JUNGLE_SHELF,
            Material.ACACIA_SHELF, Material.DARK_OAK_SHELF, Material.MANGROVE_SHELF,
            Material.CHERRY_SHELF, Material.PALE_OAK_SHELF, Material.BAMBOO_SHELF,
            Material.CRIMSON_SHELF, Material.WARPED_SHELF);

    private static final Set<Material> COLD_LIGHT = EnumSet.of(Material.SOUL_LANTERN, Material.SOUL_CAMPFIRE,
            Material.SOUL_FIRE, Material.SOUL_TORCH, Material.SOUL_WALL_TORCH);

    /** 수묵 검수 대상 — 가이드는 이 색들을 "차양·매화·등롱에만" 허락한다. */
    private static boolean isColored(Material m) {
        String n = m.name();
        if (n.endsWith("_WOOL") || n.endsWith("_CARPET")) {
            return !(n.startsWith("WHITE_") || n.startsWith("LIGHT_GRAY_") || n.startsWith("GRAY_")
                    || n.startsWith("BLACK_"));
        }
        if (n.contains("COPPER")) {
            return true;
        }
        if (n.startsWith("CHERRY_")) {
            return true;
        }
        if (n.endsWith("_TERRACOTTA") || n.endsWith("_GLAZED_TERRACOTTA")) {
            return !(n.startsWith("WHITE_") || n.startsWith("LIGHT_GRAY_") || n.startsWith("GRAY_")
                    || n.startsWith("BLACK_") || n.startsWith("BROWN_"));
        }
        return false;
    }

    /** 시선을 막는 블록 = 벽 후보 (유리판·철창 포함 — 창도 벽면이다). */
    private static boolean blocking(Material m) {
        if (m.isAir()) {
            return false;
        }
        if (m == Material.GLASS_PANE || m == Material.IRON_BARS) {
            return true;
        }
        return m.isOccluding();
    }

    private static boolean isSign(Material m) {
        return m.name().endsWith("_SIGN");
    }

    // ─── 건물 측정 결과 ───

    private static final class Bld {
        final String name;
        int wx0, wx1, wz0, wz1;      // 벽 최외곽 (실측)
        int rx0, rx1, rz0, rz1;      // 지붕 최외곽 (실측)
        int groundY;
        int roofMinY = Integer.MAX_VALUE;
        int roofMaxY = Integer.MIN_VALUE;
        boolean roofFound;
        int[] eave = new int[4];     // 서·동·북·남 처마 내밀기
        double slope = -1;           // 물매 (rise/run)
        int[] wallProps = new int[4];
        /** 과밀 진단 — 벽면별 소품의 자재·좌표 (루프의 눈: 어느 블록이 넘쳤는지 말한다) */
        final java.util.List<String>[] wallDetail = new java.util.List[]{
                new java.util.ArrayList<String>(), new java.util.ArrayList<String>(),
                new java.util.ArrayList<String>(), new java.util.ArrayList<String>()};
        double floorFree = -1;
        int propTotal;

        Bld(String name) {
            this.name = name;
        }
    }

    // ─── 진입점 ───

    /** 구역 정보 없이 (하위호환) — 폐사당은 기본 좌표로 추정한다 */
    public static List<String> audit(World world, Map<String, Location> anchors, int cx, int cy, int cz) {
        return audit(world, anchors, List.of(), cx, cy, cz);
    }

    public static List<String> audit(World world, Map<String, Location> anchors, List<Zone> zones,
                                     int cx, int cy, int cz) {
        List<String> out = new ArrayList<>();
        out.add(HEAD + "══ 청하현 규칙 린트 (building_style_guide.md 대조) ══");
        out.add(INFO + "중심 (" + cx + ", " + cy + ", " + cz + ") · 스캔 반경 " + SCAN_R);

        List<String> violations = new ArrayList<>();

        boolean[][] path = pathGrid(world, cx, cy, cz);
        WORLD_PROBE = world;
        PROBE_Y = cy;
        roadWidths(out, violations, path, cx, cz);

        List<Bld> blds = new ArrayList<>();
        if (anchors != null) {
            for (Map.Entry<String, Location> e : anchors.entrySet()) {
                if (e.getValue() == null || "북쪽_산길".equals(e.getKey()) || "장터".equals(e.getKey())) {
                    continue;   // 앵커이되 건물이 아니다 (좌표 표식)
                }
                Bld b = measureBuilding(world, e.getKey(), e.getValue());
                if (b != null) {
                    blds.add(b);
                }
            }
        }
        eavesAndRoofs(out, violations, blds);
        roofPlateau(out, violations, world, blds);
        roofTerracing(out, violations, world, blds);
        props(out, violations, world, blds);
        spacing(out, violations, blds);
        doorPathWidths(out, violations, path, blds, cx, cz);

        Volume vol = scanVolume(world, cx, cy, cz);
        inkWash(out, violations, vol);
        coldLight(out, violations, world, vol, cx, cy, cz, zones);
        nightLight(out, violations, world, path, cx, cy, cz);
        outsideTown(out, violations, world, cx, cy, cz);
        contracts(out, violations, world, anchors, cx, cy, cz);

        out.add(HEAD + "── 총평 ──");
        if (violations.isEmpty()) {
            out.add(OK + "위반 0건 — 가이드 준수.");
        } else {
            out.add(BAD + "위반 " + violations.size() + "건: " + String.join(" / ", violations));
        }
        return out;
    }

    // ─── ① 길 폭 ───

    private static boolean[][] pathGrid(World world, int cx, int cy, int cz) {
        boolean[][] g = new boolean[2 * SCAN_R + 1][2 * SCAN_R + 1];
        for (int dx = -SCAN_R; dx <= SCAN_R; dx++) {
            for (int dz = -SCAN_R; dz <= SCAN_R; dz++) {
                Material m = world.getBlockAt(cx + dx, cy, cz + dz).getType();
                g[dx + SCAN_R][dz + SCAN_R] = PATH.contains(m);
            }
        }
        return g;
    }

    /** (dx,dz)를 지나는 x축 방향 연속 스팬 길이. */
    private static int runX(boolean[][] g, int dx, int dz) {
        int ix = dx + SCAN_R, iz = dz + SCAN_R;
        if (!in(ix) || !in(iz) || !g[ix][iz]) {
            return 0;
        }
        int n = 1;
        for (int i = ix - 1; in(i) && g[i][iz]; i--) {
            n++;
        }
        for (int i = ix + 1; in(i) && g[i][iz]; i++) {
            n++;
        }
        return n;
    }

    private static int runZ(boolean[][] g, int dx, int dz) {
        int ix = dx + SCAN_R, iz = dz + SCAN_R;
        if (!in(ix) || !in(iz) || !g[ix][iz]) {
            return 0;
        }
        int n = 1;
        for (int i = iz - 1; in(i) && g[ix][i]; i--) {
            n++;
        }
        for (int i = iz + 1; in(i) && g[ix][i]; i++) {
            n++;
        }
        return n;
    }

    private static boolean in(int i) {
        return i >= 0 && i <= 2 * SCAN_R;
    }

    private static World WORLD_PROBE;   // 진단 출력용 (측정에는 쓰지 않는다)
    private static int PROBE_Y;

    private static void roadWidths(List<String> out, List<String> violations, boolean[][] g, int cx, int cz) {
        out.add(HEAD + "① 길 폭 (실측 DIRT_PATH/GRAVEL 스팬)");

        int[] sample = {-50, -40, -30, -15, 15, 30, 40, 50};
        int nsMin = Integer.MAX_VALUE, ewMin = Integer.MAX_VALUE;
        List<String> ns = new ArrayList<>(), ew = new ArrayList<>();
        for (int d : sample) {
            int w = runX(g, 0, d);          // 남북대로: z=d 에서 x방향 폭
            ns.add(w + "");
            nsMin = Math.min(nsMin, w);
            int w2 = runZ(g, d, 0);         // 동서대로: x=d 에서 z방향 폭
            ew.add(w2 + "");
            ewMin = Math.min(ewMin, w2);
        }
        out.add(INFO + "  남북대로 폭 " + String.join("/", ns) + " (최소 " + nsMin + ")");
        // 폭 0 = 그 행에 길 자재가 아예 없다 — 무엇이 길을 덮었는지 이름을 대라 (오탐/실버그 판별)
        for (int d : sample) {
            if (runX(g, 0, d) == 0) {
                out.add(WARN + "  진단: 남북대로 z=" + (cz + d) + " 중심선이 길이 아니다 — "
                        + WORLD_PROBE.getBlockAt(cx, PROBE_Y, cz + d).getType());
            }
        }
        out.add(INFO + "  동서대로 폭 " + String.join("/", ew) + " (최소 " + ewMin + ")");
        int boulevard = Math.min(nsMin, ewMin);
        if (boulevard >= BOULEVARD_MIN) {
            out.add(OK + "십자대로 최소 폭 " + boulevard + " ≥ " + BOULEVARD_MIN);
        } else {
            out.add(BAD + "십자대로 최소 폭 " + boulevard + " < 기대 " + BOULEVARD_MIN
                    + " — 대로가 좁다 (한 줄짜리 길은 대로가 아니다)");
            violations.add("대로폭 " + boulevard + "/" + BOULEVARD_MIN);
        }

        int alleyMin = Integer.MAX_VALUE;
        List<String> al = new ArrayList<>();
        for (int dz : new int[]{-21, 21}) {
            for (int dx : new int[]{-40, -20, 20, 40}) {
                int w = runZ(g, dx, dz);
                al.add(w + "");
                alleyMin = Math.min(alleyMin, w);
            }
        }
        out.add(INFO + "  골목(z±21) 폭 " + String.join("/", al) + " (최소 " + alleyMin + ")");
        for (int dz : new int[]{-21, 21}) {
            for (int x : new int[]{-40, -20, 0, 20, 40}) {
                if (runZ(g, x, dz) == 0) {
                    out.add(WARN + "  진단: 골목 (" + (cx + x) + "," + (cz + dz) + ") 이 길이 아니다 — "
                            + WORLD_PROBE.getBlockAt(cx + x, PROBE_Y, cz + dz).getType());
                }
            }
        }
        if (alleyMin >= ALLEY_MIN) {
            out.add(OK + "골목 최소 폭 " + alleyMin + " ≥ " + ALLEY_MIN);
        } else {
            out.add(BAD + "골목 최소 폭 " + alleyMin + " < 기대 " + ALLEY_MIN);
            violations.add("골목폭 " + alleyMin + "/" + ALLEY_MIN);
        }

        // 히스토그램 — 모든 길 칸의 '통로 폭'(x폭·z폭 중 작은 값)
        TreeMap<Integer, Integer> hist = new TreeMap<>();
        int cells = 0;
        for (int dx = -SCAN_R; dx <= SCAN_R; dx++) {
            for (int dz = -SCAN_R; dz <= SCAN_R; dz++) {
                if (!g[dx + SCAN_R][dz + SCAN_R]) {
                    continue;
                }
                cells++;
                int w = Math.min(runX(g, dx, dz), runZ(g, dx, dz));
                hist.merge(Math.min(w, 12), 1, Integer::sum);
            }
        }
        out.add(INFO + "  길 칸 " + cells + "개 · 통로폭 히스토그램:");
        for (Map.Entry<Integer, Integer> e : hist.entrySet()) {
            int bars = Math.max(1, e.getValue() * 30 / Math.max(1, cells));
            out.add(INFO + String.format("    폭 %2d칸 │%s %d (%.1f%%)", e.getKey(),
                    "█".repeat(bars), e.getValue(), 100.0 * e.getValue() / cells));
        }
    }

    // ─── ②③ 건물 실측 (벽·지붕) ───

    /** 앵커에서 4방향 레이캐스트로 벽 최외곽을, 그 위에서 지붕 최외곽을 실측한다. */
    private static Bld measureBuilding(World world, String name, Location anchor) {
        int ax = anchor.getBlockX(), az = anchor.getBlockZ();
        int ay = anchor.getBlockY();
        int groundY = ay - 1;
        // 지면 보정 — 앵커 밑 첫 비공기
        for (int y = ay; y > ay - 4; y--) {
            if (!world.getBlockAt(ax, y - 1, az).getType().isAir()) {
                groundY = y - 1;
                break;
            }
        }
        int probeY = groundY + 3;   // 가구(2단)보다 위, 지붕보다 아래 = 순수 벽면

        Integer wx0 = wallAt(world, ax, probeY, az, -1, 0);
        Integer wx1 = wallAt(world, ax, probeY, az, 1, 0);
        Integer wz0 = wallAt(world, ax, probeY, az, 0, -1);
        Integer wz1 = wallAt(world, ax, probeY, az, 0, 1);
        if (wx0 == null || wx1 == null || wz0 == null || wz1 == null) {
            return null;   // 벽이 안 잡히면 건물이 아니다 (야외 앵커)
        }
        Bld b = new Bld(name);
        b.groundY = groundY;
        b.wx0 = wx0;
        b.wx1 = wx1;
        b.wz0 = wz0;
        b.wz1 = wz1;

        // 지붕 최외곽 — 벽 박스 ±8, y = 지면+3 ~ 지면+22
        b.rx0 = wx0;
        b.rx1 = wx1;
        b.rz0 = wz0;
        b.rz1 = wz1;
        int[][] top = new int[wx1 - wx0 + 17][wz1 - wz0 + 17];
        for (int[] row : top) {
            Arrays.fill(row, Integer.MIN_VALUE);
        }
        for (int x = wx0 - 8; x <= wx1 + 8; x++) {
            for (int z = wz0 - 8; z <= wz1 + 8; z++) {
                for (int y = groundY + 22; y >= groundY + 3; y--) {
                    if (ROOF.contains(world.getBlockAt(x, y, z).getType())) {
                        b.roofFound = true;
                        top[x - wx0 + 8][z - wz0 + 8] = y;
                        b.rx0 = Math.min(b.rx0, x);
                        b.rx1 = Math.max(b.rx1, x);
                        b.rz0 = Math.min(b.rz0, z);
                        b.rz1 = Math.max(b.rz1, z);
                        b.roofMinY = Math.min(b.roofMinY, y);
                        b.roofMaxY = Math.max(b.roofMaxY, y);
                        break;
                    }
                }
            }
        }
        b.eave[0] = b.wx0 - b.rx0;   // 서
        b.eave[1] = b.rx1 - b.wx1;   // 동
        b.eave[2] = b.wz0 - b.rz0;   // 북
        b.eave[3] = b.rz1 - b.wz1;   // 남

        // 물매 — 앵커를 지나는 x 단면의 최고점까지 rise/run
        if (b.roofFound) {
            int zi = az - wz0 + 8;
            if (zi >= 0 && zi < top[0].length) {
                int peakX = -1, peakY = Integer.MIN_VALUE, edgeX = -1, edgeY = 0;
                for (int x = b.rx0; x <= b.rx1; x++) {
                    int h = top[x - wx0 + 8][zi];
                    if (h == Integer.MIN_VALUE) {
                        continue;
                    }
                    if (edgeX < 0) {
                        edgeX = x;
                        edgeY = h;
                    }
                    if (h > peakY) {
                        peakY = h;
                        peakX = x;
                    }
                }
                if (peakX > edgeX) {
                    b.slope = (double) (peakY - edgeY) / (peakX - edgeX);
                }
            }
        }
        return b;
    }

    /** 앵커에서 (sx,sz) 방향으로 나가며 마지막 벽면 좌표를 찾는다 (공기 5칸 연속 = 건물 밖). */
    private static Integer wallAt(World world, int ax, int y, int az, int sx, int sz) {
        int last = Integer.MIN_VALUE;
        int gap = 0;
        for (int d = 1; d <= 30; d++) {
            int x = ax + sx * d, z = az + sz * d;
            if (blocking(world.getBlockAt(x, y, z).getType())) {
                last = sx != 0 ? x : z;
                gap = 0;
            } else if (last != Integer.MIN_VALUE && ++gap >= 5) {
                break;
            }
        }
        return last == Integer.MIN_VALUE ? null : last;
    }

    /**
     * 지붕 상단의 평지(고원) 비율 — 두 경사면은 용마루 '선'에서 만나야 한다.
     * 물매가 완만해도 꼭대기가 넓은 평지면 지붕이 아니라 '검은 상자'로 보인다 (v6.4 조감 관측).
     */
    private static void roofPlateau(List<String> out, List<String> violations, World world, List<Bld> blds) {
        out.add(HEAD + "③-b 지붕 능선 (꼭대기 평지 비율 — 20% 초과 = 평지붕)");
        for (Bld b : blds) {
            if (!b.roofFound) {
                continue;
            }
            int top = b.roofMaxY;
            int flat = 0, area = 0;
            for (int x = b.rx0; x <= b.rx1; x++) {
                for (int z = b.rz0; z <= b.rz1; z++) {
                    Material m = world.getBlockAt(x, top, z).getType();
                    Material below = world.getBlockAt(x, top - 1, z).getType();
                    if (ROOF.contains(below) || ROOF.contains(m)) {
                        area++;
                        if (ROOF.contains(m)) {
                            flat++;   // 최상단 y에 지붕 자재가 깔려 있다 = 그 칸은 평지
                        }
                    }
                }
            }
            double pct = area == 0 ? 0 : 100.0 * flat / area;
            if (pct > 20.0) {
                out.add(BAD + String.format("  %s — 꼭대기 평지 %.0f%% (%d/%d칸) — 용마루가 선이 아니라 고원이다",
                        b.name, pct, flat, area));
                violations.add("평지붕 " + b.name);
            } else {
                out.add(OK + String.format("  %s — 꼭대기 평지 %.0f%% (능선 수렴)", b.name, pct));
            }
        }
    }

    /**
     * 지붕 단 평탄도 — 같은 높이로 이어지는 칸이 길면 '층계(웨딩케이크)'로 보인다.
     * 물매를 완만하게 하려고 평평한 단을 3칸씩 두면 수치(rise/run)는 통과하지만 눈에는 테라스다.
     * 반블록으로 반 칸씩 쪼개면 이 값이 내려간다.
     */
    private static void roofTerracing(List<String> out, List<String> violations, World world, List<Bld> blds) {
        out.add(HEAD + "③-c 지붕 단 평탄도 (같은 높이 연속 — 3.0 초과 = 층계로 보인다)");
        for (Bld b : blds) {
            if (!b.roofFound) {
                continue;
            }
            long runs = 0, cells = 0;
            for (int z = b.rz0; z <= b.rz1; z++) {
                int prev = Integer.MIN_VALUE, run = 0;
                for (int x = b.rx0; x <= b.rx1; x++) {
                    int top = roofTopAt(world, x, z, b);
                    if (top == Integer.MIN_VALUE) {
                        prev = Integer.MIN_VALUE;
                        continue;
                    }
                    if (top == prev) {
                        run++;
                    } else {
                        if (run > 0) {
                            runs += run;
                            cells++;
                        }
                        run = 1;
                        prev = top;
                    }
                }
                if (run > 0) {
                    runs += run;
                    cells++;
                }
            }
            double avg = cells == 0 ? 0 : (double) runs / cells;
            if (avg > 3.0) {
                out.add(BAD + String.format("  %s — 평균 단 길이 %.1f칸 (층계로 보인다 — 반블록으로 쪼개라)",
                        b.name, avg));
                violations.add("지붕층계 " + b.name);
            } else {
                out.add(OK + String.format("  %s — 평균 단 길이 %.1f칸", b.name, avg));
            }
        }
    }

    /** 그 (x,z) 열에서 지붕 자재의 최상단 y (없으면 MIN_VALUE) */
    private static int roofTopAt(World world, int x, int z, Bld b) {
        for (int y = b.roofMaxY; y >= b.roofMinY; y--) {
            if (ROOF.contains(world.getBlockAt(x, y, z).getType())) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** 담장 밖 접근부 — 마을만 정성 들이고 바깥이 허허벌판이면 마을이 무대장치로 보인다 */
    private static void outsideTown(List<String> out, List<String> violations, World world,
                                    int cx, int cy, int cz) {
        out.add(HEAD + "⑩ 담장 밖 접근부 (허허벌판 방지)");
        int r0 = 62, r1 = 88;    // 담장 밖 링
        int structures = 0, path = 0, sampled = 0;
        for (int x = cx - r1; x <= cx + r1; x += 2) {
            for (int z = cz - r1; z <= cz + r1; z += 2) {
                int d = Math.max(Math.abs(x - cx), Math.abs(z - cz));
                if (d < r0 || d > r1) {
                    continue;
                }
                sampled++;
                int y = world.getHighestBlockYAt(x, z);
                Material m = world.getBlockAt(x, y - 1, z).getType();
                Material top = world.getBlockAt(x, y, z).getType();
                if (PATH.contains(m) || PATH.contains(top)) {
                    path++;
                } else if (!top.isAir() && (top.name().endsWith("_LOG") || top.name().endsWith("_LEAVES")
                        || PROP.contains(top) || top.name().endsWith("_WALL") || top.name().endsWith("_SIGN")
                        || top.name().endsWith("_FENCE") || top == Material.COBBLESTONE)) {
                    structures++;
                }
            }
        }
        double pct = sampled == 0 ? 0 : 100.0 * (structures + path) / sampled;
        out.add(INFO + String.format("  샘플 %d칸 · 길 %d · 구조물·수목 %d → 채움 %.1f%%",
                sampled, path, structures, pct));
        if (pct < 5.0) {
            out.add(BAD + String.format("담장 밖 채움 %.1f%% < 5%% — 허허벌판 (관도·산길·이정표·수목이 없다)", pct));
            violations.add("담장밖 " + String.format("%.1f%%", pct));
        } else {
            out.add(OK + String.format("담장 밖 접근부 %.1f%%", pct));
        }
    }

    private static void eavesAndRoofs(List<String> out, List<String> violations, List<Bld> blds) {
        out.add(HEAD + "② 처마 내밀기 (벽 최외곽 → 지붕 최외곽, 기대 ≥" + EAVE_MIN + ")");
        for (Bld b : blds) {
            if (!b.roofFound) {
                out.add(BAD + "  " + b.name + " — 지붕 자재를 찾지 못했다 (지붕 없음?)");
                violations.add(b.name + " 지붕없음");
                continue;
            }
            int min = Math.min(Math.min(b.eave[0], b.eave[1]), Math.min(b.eave[2], b.eave[3]));
            String detail = String.format("서%d 동%d 북%d 남%d (벽 %dx%d)", b.eave[0], b.eave[1], b.eave[2], b.eave[3],
                    b.wx1 - b.wx0 + 1, b.wz1 - b.wz0 + 1);
            if (min >= EAVE_MIN) {
                out.add(OK + "  " + b.name + " — " + detail);
            } else if (min >= 1) {
                out.add(WARN + "  " + b.name + " — " + detail + " · 최소 " + min + " < " + EAVE_MIN
                        + " (얕은 처마 — 그늘이 안 진다)");
                violations.add(b.name + " 처마" + min);
            } else {
                out.add(BAD + "  " + b.name + " — " + detail + " · 처마 없음(" + min + ")");
                violations.add(b.name + " 처마" + min);
            }
        }

        out.add(HEAD + "③ 지붕 물매 (rise/run — 1.0 = 1칸/1칸 급경사)");
        for (Bld b : blds) {
            if (b.slope < 0) {
                out.add(INFO + "  " + b.name + " — 단면 미검출");
                continue;
            }
            String s = String.format("%.2f (높이 %d~%d, 폭 %d)", b.slope, b.roofMinY, b.roofMaxY, b.rx1 - b.rx0 + 1);
            if (b.slope >= 1.0) {
                out.add(WARN + "  " + b.name + " — 물매 " + s + " · 급경사(계단 1:1) — 향촌 지붕은 완만해야 한다");
                violations.add(b.name + " 급경사");
            } else if (b.slope < 0.35) {
                out.add(WARN + "  " + b.name + " — 물매 " + s + " · 너무 평평(비가 안 흐른다)");
                violations.add(b.name + " 평지붕");
            } else {
                out.add(OK + "  " + b.name + " — 물매 " + s);
            }
        }
    }

    // ─── ④ 소품 밀도 ───

    private static void props(List<String> out, List<String> violations, World world, List<Bld> blds) {
        out.add(HEAD + "④ 소품 밀도 (실내 벽면당 장식 — >" + PROP_MAX_PER_WALL + " 과밀) · 바닥 여백률");
        for (Bld b : blds) {
            propScan(world, b);
            String faces = String.format("서%d 동%d 북%d 남%d", b.wallProps[0], b.wallProps[1],
                    b.wallProps[2], b.wallProps[3]);
            int max = Math.max(Math.max(b.wallProps[0], b.wallProps[1]),
                    Math.max(b.wallProps[2], b.wallProps[3]));
            String line = "  " + b.name + " — 벽면 " + faces + " · 총 " + b.propTotal
                    + String.format(" · 바닥 여백 %.0f%%", b.floorFree * 100);
            if (max > PROP_MAX_PER_WALL) {
                out.add(WARN + line + " · 벽면 최대 " + max + " > " + PROP_MAX_PER_WALL + " (과밀)");
                violations.add(b.name + " 소품과밀" + max);
                String[] faceName = {"서", "동", "북", "남"};
                for (int f = 0; f < 4; f++) {
                    if (b.wallProps[f] > PROP_MAX_PER_WALL) {
                        out.add(INFO + "    " + faceName[f] + "벽 내역 — "
                                + String.join(" ", b.wallDetail[f]));
                    }
                }
            } else if (b.propTotal == 0) {
                out.add(BAD + line + " · 소품 0 (빈 집)");
                violations.add(b.name + " 소품0");
            } else {
                out.add(OK + line);
            }
            if (b.floorFree >= 0 && b.floorFree < FLOOR_FREE_MIN) {
                out.add(WARN + "    바닥 여백률 " + String.format("%.0f%%", b.floorFree * 100)
                        + " < " + (int) (FLOOR_FREE_MIN * 100) + "% — 발 디딜 곳이 없다");
                violations.add(b.name + " 바닥막힘");
            }
        }
    }

    /** 건물 실내를 스캔해 벽면별 장식 수·바닥 여백률을 채운다. */
    private static void propScan(World world, Bld b) {
        int y0 = b.groundY + 1;
        int y1 = b.roofFound ? Math.max(b.roofMinY - 1, y0 + 3) : y0 + 4;
        int floorCells = 0, freeCells = 0;
        for (int x = b.wx0 + 1; x <= b.wx1 - 1; x++) {
            for (int z = b.wz0 + 1; z <= b.wz1 - 1; z++) {
                floorCells++;
                if (world.getBlockAt(x, y0, z).getType().isAir()) {
                    freeCells++;
                }
                for (int y = y0; y <= y1; y++) {
                    Material m = world.getBlockAt(x, y, z).getType();
                    if (!PROP.contains(m) && !isSign(m)) {
                        continue;
                    }
                    b.propTotal++;
                    String at = m.name() + "(" + x + "," + y + "," + z + ")";
                    if (x == b.wx0 + 1) {
                        b.wallProps[0]++;
                        b.wallDetail[0].add(at);
                    }
                    if (x == b.wx1 - 1) {
                        b.wallProps[1]++;
                        b.wallDetail[1].add(at);
                    }
                    if (z == b.wz0 + 1) {
                        b.wallProps[2]++;
                        b.wallDetail[2].add(at);
                    }
                    if (z == b.wz1 - 1) {
                        b.wallProps[3]++;
                        b.wallDetail[3].add(at);
                    }
                }
            }
        }
        b.floorFree = floorCells == 0 ? -1 : (double) freeCells / floorCells;
    }

    // ─── ⑤ 건물 간 이격 ───

    private static void spacing(List<String> out, List<String> violations, List<Bld> blds) {
        out.add(HEAD + "⑤ 건물 간 이격 · 처마 겹침");
        if (blds.size() < 2) {
            out.add(INFO + "  비교할 건물이 2채 미만");
            return;
        }
        int worst = Integer.MAX_VALUE;
        String worstPair = "";
        for (int i = 0; i < blds.size(); i++) {
            for (int j = i + 1; j < blds.size(); j++) {
                Bld a = blds.get(i), b = blds.get(j);
                int gap = gap(a.wx0, a.wx1, b.wx0, b.wx1, a.wz0, a.wz1, b.wz0, b.wz1);
                if (gap < worst) {
                    worst = gap;
                    worstPair = a.name + "↔" + b.name;
                }
                boolean roofOverlap = a.roofFound && b.roofFound
                        && a.rx0 <= b.rx1 && b.rx0 <= a.rx1 && a.rz0 <= b.rz1 && b.rz0 <= a.rz1;
                if (roofOverlap) {
                    out.add(BAD + "  " + a.name + " ↔ " + b.name + " 처마 겹침 (지붕 박스 교차)");
                    violations.add("처마겹침 " + a.name + "/" + b.name);
                } else if (gap <= 1) {
                    out.add(WARN + "  " + a.name + " ↔ " + b.name + " 이격 " + gap + "칸 (너무 붙었다)");
                    violations.add("이격 " + a.name + "/" + b.name + "=" + gap);
                }
            }
        }
        out.add((worst >= 2 ? OK : WARN) + "  최소 이격 " + worst + "칸 (" + worstPair + ")");
    }

    private static int gap(int ax0, int ax1, int bx0, int bx1, int az0, int az1, int bz0, int bz1) {
        int gx = Math.max(0, Math.max(bx0 - ax1 - 1, ax0 - bx1 - 1));
        int gz = Math.max(0, Math.max(bz0 - az1 - 1, az0 - bz1 - 1));
        return Math.max(gx, gz);
    }

    // ─── 문 앞 소로 ───

    private static void doorPathWidths(List<String> out, List<String> violations, boolean[][] g,
                                       List<Bld> blds, int cx, int cz) {
        out.add(HEAD + "① -b 문 앞 소로 폭 (기대 ≥" + DOORPATH_MIN + ")");
        for (Bld b : blds) {
            int best = 0;
            // 건물 4변 바깥 1~3칸의 길 칸 중 최대 통로폭
            for (int d = 1; d <= 3; d++) {
                for (int x = b.wx0; x <= b.wx1; x++) {
                    best = Math.max(best, corridor(g, x - cx, b.wz0 - d - cz));
                    best = Math.max(best, corridor(g, x - cx, b.wz1 + d - cz));
                }
                for (int z = b.wz0; z <= b.wz1; z++) {
                    best = Math.max(best, corridor(g, b.wx0 - d - cx, z - cz));
                    best = Math.max(best, corridor(g, b.wx1 + d - cx, z - cz));
                }
            }
            if (best == 0) {
                out.add(BAD + "  " + b.name + " — 문 앞에 길이 없다 (풀밭에 선 집)");
                violations.add(b.name + " 소로없음");
            } else if (best < DOORPATH_MIN) {
                out.add(WARN + "  " + b.name + " — 소로 폭 " + best + " < " + DOORPATH_MIN);
                violations.add(b.name + " 소로" + best);
            } else {
                out.add(OK + "  " + b.name + " — 소로 폭 " + best);
            }
        }
    }

    private static int corridor(boolean[][] g, int dx, int dz) {
        if (Math.abs(dx) > SCAN_R || Math.abs(dz) > SCAN_R) {
            return 0;
        }
        return Math.min(runX(g, dx, dz), runZ(g, dx, dz));
    }

    // ─── 볼륨 스캔 (⑥⑦) ───

    private static final class Volume {
        long solid;
        long colored;
        final Map<Material, Integer> coloredBy = new LinkedHashMap<>();
        final List<int[]> coldOutside = new ArrayList<>();
        int coldInsideShrine;
    }

    private static Volume scanVolume(World world, int cx, int cy, int cz) {
        Volume v = new Volume();
        for (int x = cx - SCAN_R; x <= cx + SCAN_R; x++) {
            for (int z = cz - SCAN_R; z <= cz + SCAN_R; z++) {
                for (int y = cy - 1; y <= cy + 20; y++) {
                    Material m = world.getBlockAt(x, y, z).getType();
                    if (m.isAir()) {
                        continue;
                    }
                    v.solid++;
                    if (isColored(m)) {
                        v.colored++;
                        v.coloredBy.merge(m, 1, Integer::sum);
                    }
                    if (COLD_LIGHT.contains(m)) {
                        int sx = cx + SHRINE_DX, sz = cz + SHRINE_DZ;
                        if (Math.max(Math.abs(x - sx), Math.abs(z - sz)) > SHRINE_R) {
                            v.coldOutside.add(new int[]{x, y, z});
                        } else {
                            v.coldInsideShrine++;
                        }
                    }
                }
            }
        }
        return v;
    }

    private static void inkWash(List<String> out, List<String> violations, Volume v) {
        out.add(HEAD + "⑥ 수묵 검수 (채색 블록 비율 — 차양·매화·등롱에만 허락)");
        double pct = v.solid == 0 ? 0 : 100.0 * v.colored / v.solid;
        out.add(INFO + String.format("  채색 %d / 비공기 %d = %.2f%%", v.colored, v.solid, pct));
        List<Map.Entry<Material, Integer>> top = new ArrayList<>(v.coloredBy.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder(INFO + "  내역: ");
        for (int i = 0; i < Math.min(6, top.size()); i++) {
            sb.append(top.get(i).getKey().name()).append("×").append(top.get(i).getValue()).append("  ");
        }
        if (!top.isEmpty()) {
            out.add(sb.toString());
        }
        if (pct <= INK_MAX_PCT) {
            out.add(OK + String.format("수묵 3색 지배 유지 (%.2f%% ≤ %.1f%%)", pct, INK_MAX_PCT));
        } else {
            out.add(BAD + String.format("채색 과다 %.2f%% > %.1f%% — 화면이 시끄럽다", pct, INK_MAX_PCT));
            violations.add(String.format("채색 %.1f%%", pct));
        }
    }

    private static void coldLight(List<String> out, List<String> violations, World world, Volume v,
                                  int cx, int cy, int cz, List<Zone> zones) {
        out.add(HEAD + "⑦ 냉색 격리 (영혼 계열 = 폐사당 전용)");
        // 폐사당은 마을 스캔(±65) 밖이고 부지가 옮겨질 수 있다 — 좌표를 짐작하지 말고
        // 구역(Zone)이 말하는 실제 경계를 읽는다 (구역이 없으면 옛 기본 좌표로 폴백)
        Zone shrine = zones.stream().filter(z -> z.name().contains("폐사당")).findFirst().orElse(null);
        int x1, z1, x2, z2, y1, y2;
        if (shrine != null) {
            x1 = Math.min(shrine.x1(), shrine.x2()) - 4;
            x2 = Math.max(shrine.x1(), shrine.x2()) + 4;
            z1 = Math.min(shrine.z1(), shrine.z2()) - 4;
            z2 = Math.max(shrine.z1(), shrine.z2()) + 4;
            y1 = Math.min(shrine.y1(), shrine.y2()) - 4;
            y2 = Math.max(shrine.y1(), shrine.y2()) + 4;
        } else {
            int sx = cx + SHRINE_DX, sz = cz + SHRINE_DZ;
            int baseY = world.getHighestBlockYAt(sx, sz);
            x1 = sx - SHRINE_R; x2 = sx + SHRINE_R;
            z1 = sz - SHRINE_R; z2 = sz + SHRINE_R;
            y1 = baseY - 8; y2 = baseY + 16;
        }
        int shrineCold = v.coldInsideShrine;
        int water = 0, solid = 0;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                for (int y = y1; y <= y2; y++) {
                    Material m = world.getBlockAt(x, y, z).getType();
                    if (COLD_LIGHT.contains(m)) {
                        shrineCold++;
                    }
                    if (m == Material.WATER) {
                        water++;
                    } else if (!m.isAir()) {
                        solid++;
                    }
                }
            }
        }
        out.add(INFO + "  폐사당 " + (shrine == null ? "(구역 없음 — 기본 좌표)" : "구역 [" + x1 + ".." + x2
                + ", " + z1 + ".." + z2 + "]") + " 냉광 " + shrineCold
                + "개 · 마을 안 냉광 " + v.coldOutside.size() + "개");
        // 수몰 검사 — 폐허는 지형에 순응하되 호수 위에 서면 안 된다 (v6.3 관측 버그)
        if (water > 0) {
            double wpct = 100.0 * water / Math.max(1, water + solid);
            if (wpct > 10.0) {
                out.add(BAD + String.format("폐사당 부지에 물 %d블록 (%.0f%%) — 수몰됐다 (육지 부지 탐색 필요)",
                        water, wpct));
                violations.add("폐사당 수몰");
            } else {
                out.add(INFO + "  부지 내 물 " + water + "블록 (연못·습지 인접 — 허용)");
            }
        }
        if (v.coldOutside.isEmpty()) {
            out.add(OK + "마을 안 영혼 계열 광원 0개 — 온색/냉색 분리 유지");
        } else {
            out.add(BAD + "마을 안 영혼 계열 " + v.coldOutside.size() + "개 — 첫 좌표 "
                    + Arrays.toString(v.coldOutside.get(0)));
            violations.add("냉색누출 " + v.coldOutside.size());
        }
        if (shrineCold == 0) {
            out.add(WARN + "폐사당 냉광 0 — 폐사당이 조성되지 않았거나 위치가 다르다");
            violations.add("폐사당 냉광0");
        }
    }

    // ─── ⑧ 야간 광원 커버리지 ───

    private static void nightLight(List<String> out, List<String> violations, World world, boolean[][] g,
                                   int cx, int cy, int cz) {
        out.add(HEAD + "⑧ 야간 광원 커버리지 (길 위 블록 광원 <7 = 암흑)");
        int samples = 0, dark = 0, sum = 0;
        for (int dx = -SCAN_R; dx <= SCAN_R; dx += 2) {
            for (int dz = -SCAN_R; dz <= SCAN_R; dz += 2) {
                if (!g[dx + SCAN_R][dz + SCAN_R]) {
                    continue;
                }
                Block above = world.getBlockAt(cx + dx, cy + 1, cz + dz);
                int light = above.getLightFromBlocks();
                samples++;
                sum += light;
                if (light < 7) {
                    dark++;
                }
            }
        }
        if (samples == 0) {
            out.add(BAD + "  길 샘플 0 — 길이 없다");
            violations.add("길없음");
            return;
        }
        double pct = 100.0 * dark / samples;
        out.add(INFO + String.format("  샘플 %d칸 · 평균 광원 %.1f · 암흑 %d칸 (%.1f%%)",
                samples, (double) sum / samples, dark, pct));
        if (pct <= DARK_MAX_PCT) {
            out.add(OK + String.format("암흑 구간 %.1f%% ≤ %.0f%%", pct, DARK_MAX_PCT));
        } else {
            out.add(BAD + String.format("암흑 구간 %.1f%% > %.0f%% — 등롱 리듬이 성기다", pct, DARK_MAX_PCT));
            violations.add(String.format("암흑 %.0f%%", pct));
        }
    }

    // ─── ⑨ 계약 검증 ───

    private static final String[] ANCHOR_KEYS = {
            "장터", "청하객잔", "의뢰소", "의방", "전장", "표국", "관아", "북쪽_산길"};

    private static void contracts(List<String> out, List<String> violations, World world,
                                  Map<String, Location> anchors, int cx, int cy, int cz) {
        out.add(HEAD + "⑨ 계약 검증 (앵커 8 · NPC 9 · 붉은 차양)");
        List<String> missing = new ArrayList<>();
        for (String k : ANCHOR_KEYS) {
            if (anchors == null || !anchors.containsKey(k) || anchors.get(k) == null) {
                missing.add(k);
            }
        }
        if (missing.isEmpty()) {
            out.add(OK + "앵커 " + ANCHOR_KEYS.length + "키 존재 (" + String.join(", ", ANCHOR_KEYS) + ")");
        } else {
            out.add(BAD + "앵커 누락: " + String.join(", ", missing));
            violations.add("앵커누락 " + missing.size());
        }

        BoundingBox box = new BoundingBox(cx - SCAN_R, cy - 8, cz - SCAN_R, cx + SCAN_R, cy + 24, cz + SCAN_R);
        List<String> npcs = new ArrayList<>();
        for (Entity e : world.getNearbyEntities(box)) {
            if (e instanceof Villager v && v.getCustomName() != null && v.isInvulnerable()) {
                npcs.add(v.getCustomName());
            }
        }
        npcs.sort(String::compareTo);
        if (npcs.size() == 9) {
            out.add(OK + "NPC 9인 스폰: " + String.join(", ", npcs));
        } else {
            out.add(BAD + "NPC " + npcs.size() + "인 (기대 9): " + String.join(", ", npcs));
            violations.add("NPC " + npcs.size() + "/9");
        }

        Location market = anchors == null ? null : anchors.get("장터");
        if (market == null) {
            out.add(BAD + "장터 앵커 없음 — 붉은 차양 검증 불가");
            violations.add("장터앵커없음");
            return;
        }
        int mx = market.getBlockX(), my = market.getBlockY(), mz = market.getBlockZ();
        int red = 0;
        int nearest = Integer.MAX_VALUE;
        for (int x = mx - 15; x <= mx + 15; x++) {
            for (int z = mz - 15; z <= mz + 15; z++) {
                for (int y = my - 2; y <= my + 8; y++) {
                    if (world.getBlockAt(x, y, z).getType() == Material.RED_WOOL) {
                        red++;
                        nearest = Math.min(nearest, Math.max(Math.abs(x - mx), Math.abs(z - mz)));
                    }
                }
            }
        }
        if (red > 0) {
            out.add(OK + "붉은 차양(RED_WOOL) " + red + "칸 · 장터 앵커에서 최근접 " + nearest + "칸 (반경 15 내)");
        } else {
            out.add(BAD + "장터 반경 15 내 RED_WOOL 0 — 매각 규칙 정합 깨짐");
            violations.add("붉은차양0");
        }
    }
}
