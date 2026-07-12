package com.honcheon.mvt;

import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 지역 검수 — <b>원거리 조성물의 자동 검산</b>.
 *
 * <p>청하현에는 검수 12종이 있는데 산채·문파에는 아무 눈도 없었다. 눈으로만 본 조성은 검산이 아니다 —
 * 조감은 "보기에 이상하다"까지만 말하고, <b>"오를 수 없다"는 말은 못 한다.</b>
 *
 * <p>지역이 마을과 다르게 물어야 하는 것들:
 * <ol>
 *   <li><b>도달성(到達性)</b> — 이 검수의 핵심. 문(산문·채문)에서 안(본전·마당)까지 <b>사람이 걸어갈 수
 *       있는가</b>. 계단 한 칸이 어긋나도 문파는 오를 수 없는 산이 된다. 걸음의 규칙(한 칸 오르기·
 *       두 칸 머리 공간·낙차 4칸 이하)으로 BFS 를 돌려 실제로 걸어 본다.</li>
 *   <li><b>경사</b> — 계단이 한 번에 두 칸 이상 오르면 사람은 못 오른다 (점프로도 1칸이다).</li>
 *   <li><b>구조 계약</b> — 그 원형이 반드시 가져야 할 것 (산채: 목책·채문·모닥불·망루 / 문파: 산문·본전·연무장).</li>
 *   <li><b>야간 광원</b> — 길 위가 어두우면 밤에 오르다 죽는다.</li>
 *   <li><b>수묵</b> — 채색 비율 (청하현과 같은 잣대. 세계는 하나다).</li>
 *   <li><b>허공</b> — 떠 있는 블록 (지형을 빚다 보면 생긴다).</li>
 * </ol>
 */
final class RegionAudit {

    private RegionAudit() {
    }

    private static final String OK = "§a✅ ";
    private static final String WARN = "§e⚠ ";
    private static final String BAD = "§c❌ ";
    private static final String HEAD = "§6";
    private static final String INFO = "§7";

    /** 수묵 상한 — 청하현과 같은 잣대 (채색은 깃발·매화·등롱에만) */
    private static final double COLOR_MAX = 0.02;
    /**
     * 야간 암흑 상한 — <b>0.25 → 0.45</b>.
     *
     * <p>사용자 피드백("조명이 없는 부분도 존재해도 된다")을 지역에도 적용한다. 더구나 산채는
     * <b>도적의 소굴</b>이다 — 등불로 환한 산채는 산채가 아니다. 마당과 채문만 밝으면 된다.
     */
    private static final double DARK_MAX = 0.45;

    static List<String> audit(World world, WorldMap.Place place, Zone zone) {
        List<String> out = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        out.add(HEAD + "══ 지역 검수 — " + place.name() + " (" + place.id() + ") ══");
        out.add(INFO + "  구역 [" + zone.x1() + ".." + zone.x2() + "] × [" + zone.z1() + ".." + zone.z2()
                + "] · y " + zone.y1() + ".." + zone.y2());

        boolean sect = !"noklim".equals(place.faction());
        reachability(out, violations, world, zone, sect);
        contract(out, violations, world, zone, place);
        floating(out, violations, world, zone);
        light(out, violations, world, zone);
        ink(out, violations, world, zone);

        out.add(HEAD + "── 총평 ──");
        out.add(violations.isEmpty() ? OK + "위반 0건 — 지역이 규칙을 지킨다"
                : BAD + "위반 " + violations.size() + "건: " + String.join(" / ", violations));
        return out;
    }

    // ─── ① 도달성 — 문에서 안까지 걸어갈 수 있는가 ───

    /**
     * 걸어서 도달 가능한가 — <b>사람의 걸음으로 BFS 를 돈다</b>.
     *
     * <p>규칙은 마인크래프트의 몸이 정한다: 발밑이 단단하고, 머리 위 두 칸이 비어 있고,
     * 한 칸까지는 오르고(점프), 네 칸까지는 내려선다(낙사 안 함). 계단 블록·반 블록은 오르는 칸으로 센다.
     *
     * <p>출발은 <b>문 앞</b>(구역 남쪽 가장자리 중앙 = 사람이 걸어오는 자리), 목표는 <b>구역의 가장 높은
     * 사람의 자리</b>(본전 단·마당). 문파는 정상에 닿아야 하고, 산채는 마당에 닿아야 한다.
     */
    private static void reachability(List<String> out, List<String> violations,
                                     World world, Zone zone, boolean sect) {
        out.add(HEAD + "① 도달성 — 문에서 안까지 걸어갈 수 있는가");
        // 출발은 **남쪽에서 걸어오는 자리**다. 한 점만 보면 그 점이 못이거나 벼랑이면 검사가 죽는다
        //   (실제로 "문앞없음"으로 죽었다 — 하필 그 열이 물웅덩이였다). 남쪽 가장자리를 훑어 첫 설 자리를 잡는다.
        int startX = (zone.x1() + zone.x2()) / 2;
        int startZ = Integer.MIN_VALUE;
        int startY = Integer.MIN_VALUE;
        outer:
        for (int dz = 2; dz <= 24; dz++) {
            for (int dx = 0; dx <= 12; dx++) {
                for (int sx : new int[]{startX - dx, startX + dx}) {
                    int sz = zone.z2() - dz;
                    int sy = groundStandY(world, sx, sz, zone);   // 지붕이 아니라 땅에서 출발한다
                    if (sy != Integer.MIN_VALUE) {
                        startX = sx;
                        startZ = sz;
                        startY = sy;
                        break outer;
                    }
                }
            }
        }
        if (startZ == Integer.MIN_VALUE) {
            out.add(BAD + "  남쪽 어디에도 설 자리가 없다 — 걸어올 수 없는 지역이다");
            violations.add("문앞없음");
            return;
        }

        int goalX = (zone.x1() + zone.x2()) / 2;
        int goalZ = sect ? (zone.z1() + zone.z2()) / 2 - 10 : (zone.x1() + zone.x2()) / 2 * 0;
        goalZ = sect ? goalZ : (zone.z1() + zone.z2()) / 2;   // 산채는 마당(중앙), 문파는 본전 앞
        int goalY = standY(world, goalX, goalZ, zone);
        if (goalY == Integer.MIN_VALUE) {
            out.add(BAD + "  목적지에 설 자리가 없다 (" + goalX + ", " + goalZ + ")");
            violations.add("목적지없음");
            return;
        }

        Set<Long> seen = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{startX, startY, startZ});
        seen.add(key(startX, startY, startZ));
        int visited = 0;
        int highest = startY;
        boolean arrived = false;
        while (!queue.isEmpty() && visited < 240_000) {
            int[] cur = queue.poll();
            visited++;
            highest = Math.max(highest, cur[1]);
            if (Math.abs(cur[0] - goalX) <= 2 && Math.abs(cur[2] - goalZ) <= 2) {
                arrived = true;
                break;
            }
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = cur[0] + d[0];
                int nz = cur[2] + d[1];
                if (nx < zone.x1() || nx > zone.x2() || nz < zone.z1() || nz > zone.z2()) {
                    continue;
                }
                for (int dy = 1; dy >= -4; dy--) {   // 한 칸 오르고, 네 칸까지 내려선다
                    int ny = cur[1] + dy;
                    if (!canStand(world, nx, ny, nz)) {
                        continue;
                    }
                    if (seen.add(key(nx, ny, nz))) {
                        queue.add(new int[]{nx, ny, nz});
                    }
                    break;   // 그 열에서 첫 설 자리 하나만 (아래로 파고들지 않는다)
                }
            }
        }
        out.add(INFO + "  출발 문 앞 (" + startX + ", " + startY + ", " + startZ + ")"
                + " → 목표 (" + goalX + ", " + goalY + ", " + goalZ + ")");
        out.add(INFO + "  걸어 닿은 칸 " + visited + " · 오른 최고 높이 y" + highest
                + " (목표 y" + goalY + ")");
        if (arrived) {
            out.add(OK + "  문에서 안까지 걸어갈 수 있다 (오른 높이 " + (highest - startY) + "켜)");
        } else {
            out.add(BAD + "  **걸어서 닿지 못한다** — 계단이 끊겼거나 문이 막혔다 "
                    + "(최고 y" + highest + " · 목표 y" + goalY + ")");
            violations.add("도달불가");
        }
    }

    /** 설 수 있는 자리 — 발밑 단단 + 머리 위 두 칸 빔 */
    private static boolean canStand(World world, int x, int y, int z) {
        Material floor = world.getBlockAt(x, y - 1, z).getType();
        if (!floor.isSolid()) {
            return false;
        }
        return passable(world.getBlockAt(x, y, z).getType())
                && passable(world.getBlockAt(x, y + 1, z).getType());
    }

    private static boolean passable(Material m) {
        return m.isAir() || !m.isSolid();
    }

    /** 그 열에서 설 수 있는 가장 높은 자리 (구역 안) */
    private static int standY(World world, int x, int z, Zone zone) {
        for (int y = zone.y2(); y >= zone.y1(); y--) {
            if (canStand(world, x, y, z)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * <b>땅</b> 위의 설 자리 — 지붕·인방은 땅이 아니다.
     *
     * <p>도달성 검사가 화산파를 "오를 수 없다"고 했다(24칸만 걷고 갇힘). 계단은 멀쩡했다 —
     * <b>사람이 산문 기와 위에서 태어났을 뿐이다.</b> 구역 남쪽 가장자리 열에 하필 패방이 서 있었고,
     * 그 열의 "가장 높은 설 자리"가 지붕이었다. 걷는 검사가 지붕에서 출발하면 무엇을 재든 거짓이다.
     */
    private static int groundStandY(World world, int x, int z, Zone zone) {
        for (int y = zone.y2(); y >= zone.y1(); y--) {
            if (!canStand(world, x, y, z)) {
                continue;
            }
            Material floor = world.getBlockAt(x, y - 1, z).getType();
            String n = floor.name();
            boolean roof = n.endsWith("_TILES") || n.endsWith("_SLAB") || n.endsWith("_STAIRS")
                    || n.endsWith("_PLANKS") || n.endsWith("_LOG") || floor == Material.HAY_BLOCK
                    || n.endsWith("_TERRACOTTA") || n.endsWith("_BRICKS") || n.endsWith("_FENCE");
            if (!roof) {
                return y;   // 흙·돌·노면 — 사람이 걷는 땅이다
            }
        }
        return Integer.MIN_VALUE;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (y & 0xFFF) << 30) | (z & 0x3FFFFFF);
    }

    // ─── ② 구조 계약 — 그 원형이 반드시 가져야 할 것 ───

    /**
     * 구조 계약 — <b>원형마다 다르다.</b>
     *
     * <p>구판은 `sect = !noklim` 한 줄로 갈라 **마교 은신처에 매화 20장을 요구했다.** 은신처는
     * 매화를 심지 않는다 — 그 집은 <b>없는 것으로 자기를 말하는</b> 집이다(담도 등불도 깃발도 없다).
     * 계약표는 그 집을 지은 자(건축 계층)가 갖는다. 검수는 그 표를 읽을 뿐이다.
     */
    private static void contract(List<String> out, List<String> violations,
                                 World world, Zone zone, WorldMap.Place place) {
        out.add(HEAD + "② 구조 계약 — 그 집이 그 집이려면 (" + RemoteBuilder.archetype(place) + ")");
        java.util.Map<Material, Integer> census = census(world, zone);
        for (RemoteBuilder.Need n : RemoteBuilder.contract(RemoteBuilder.archetype(place))) {
            need(out, violations, census, n.material(), n.min(), n.what());
        }
    }

    private static void need(List<String> out, List<String> violations,
                             java.util.Map<Material, Integer> census, Material m, int min, String what) {
        int n = census.getOrDefault(m, 0);
        if (n >= min) {
            out.add(OK + "  " + what + " — " + m.name() + " " + n + "개 (≥" + min + ")");
        } else {
            out.add(BAD + "  " + what + " 없음 — " + m.name() + " " + n + "개 < " + min);
            violations.add(what + "없음");
        }
    }

    private static java.util.Map<Material, Integer> census(World world, Zone zone) {
        java.util.Map<Material, Integer> out = new java.util.EnumMap<>(Material.class);
        for (int x = zone.x1(); x <= zone.x2(); x++) {
            for (int z = zone.z1(); z <= zone.z2(); z++) {
                for (int y = zone.y1(); y <= zone.y2(); y++) {
                    Material m = world.getBlockAt(x, y, z).getType();
                    if (!m.isAir()) {
                        out.merge(m, 1, Integer::sum);
                    }
                }
            }
        }
        return out;
    }

    // ─── ③ 허공 — 떠 있는 블록 (지형을 빚다 보면 생긴다) ───

    private static void floating(List<String> out, List<String> violations, World world, Zone zone) {
        out.add(HEAD + "③ 허공 — 떠 있는 블록");
        int floating = 0;
        int sampled = 0;
        for (int x = zone.x1() + 1; x <= zone.x2() - 1; x += 2) {
            for (int z = zone.z1() + 1; z <= zone.z2() - 1; z += 2) {
                for (int y = zone.y1() + 1; y <= zone.y2() - 1; y++) {
                    Material m = world.getBlockAt(x, y, z).getType();
                    if (m.isAir() || !m.isSolid()) {
                        continue;
                    }
                    sampled++;
                    boolean supported = false;
                    for (int[] d : new int[][]{{0, -1, 0}, {0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}}) {
                        Material n = world.getBlockAt(x + d[0], y + d[1], z + d[2]).getType();
                        if (!n.isAir()) {
                            supported = true;
                            break;
                        }
                    }
                    if (!supported) {
                        floating++;
                    }
                }
            }
        }
        double pct = sampled == 0 ? 0 : 100.0 * floating / sampled;
        out.add(INFO + "  표본 " + sampled + "칸 · 외톨이 블록 " + floating + "개 ("
                + String.format("%.2f%%", pct) + ")");
        if (floating > 20) {
            out.add(WARN + "  떠 있는 블록 " + floating + "개 — 지형 빚기가 남긴 부스러기");
            violations.add("허공블록" + floating);
        } else {
            out.add(OK + "  허공 블록 " + floating + "개 — 깨끗하다");
        }
    }

    // ─── ④ 야간 광원 ───

    private static void light(List<String> out, List<String> violations, World world, Zone zone) {
        out.add(HEAD + "④ 야간 광원 — 밤에 오를 수 있는가");
        int dark = 0;
        int sampled = 0;
        List<String> darkSpots = new ArrayList<>();
        for (int x = zone.x1(); x <= zone.x2(); x += 3) {
            for (int z = zone.z1(); z <= zone.z2(); z += 3) {
                int y = standY(world, x, z, zone);
                if (y == Integer.MIN_VALUE) {
                    continue;
                }
                Material floor = world.getBlockAt(x, y - 1, z).getType();
                // 길은 **사람이 깐 것**뿐이다: 다듬은 안산암(포장)·흙길·다진 흙.
                //   구판은 그냥 ANDESITE 도 길로 셌는데, 그건 **산비탈의 반점**이다 — 산의 40%가 길로 잡혀
                //   "암흑 47%"가 나왔고, 나는 계단에 등을 세 사이클이나 더 꽂았다. 어두운 곳은 계단이 아니었다.
                //   산비탈의 어둠은 산의 것이다. 등불로 산을 밝힐 이유가 없다.
                if (floor != Material.POLISHED_ANDESITE && floor != Material.DIRT_PATH
                        && floor != Material.COARSE_DIRT) {
                    continue;
                }
                sampled++;
                if (world.getBlockAt(x, y, z).getLightFromBlocks() < 7) {
                    dark++;
                    if (darkSpots.size() < 40) {
                        darkSpots.add("(" + x + "," + y + "," + z + ")" + floor.name().charAt(0));
                    }
                }
            }
        }
        double pct = sampled == 0 ? 0 : (double) dark / sampled;
        out.add(INFO + "  길 표본 " + sampled + "칸 · 암흑 " + dark + "칸 ("
                + String.format("%.0f%%", pct * 100) + ")");
        if (!darkSpots.isEmpty()) {
            // **어디가** 어두운지 말한다. "암흑 47%"만 외치면 등을 엉뚱한 데 세우게 된다 —
            // 실제로 세 사이클을 계단에 등을 더 꽂으며 헛돌았다 (어두운 곳은 계단이 아니었다).
            out.add(INFO + "    어두운 자리 (앞 8): " + String.join(" ", darkSpots.subList(0,
                    Math.min(8, darkSpots.size()))));
        }
        if (sampled == 0) {
            out.add(WARN + "  길이 없다 — 잴 것이 없다");
        } else if (pct > DARK_MAX) {
            out.add(BAD + "  길 위 암흑 " + String.format("%.0f%%", pct * 100) + " > "
                    + (int) (DARK_MAX * 100) + "% — 밤에 못 다닌다");
            violations.add("암흑" + Math.round(pct * 100) + "%");
        } else {
            out.add(OK + "  길 위 암흑 " + String.format("%.0f%%", pct * 100) + " ≤ "
                    + (int) (DARK_MAX * 100) + "%");
        }
    }

    // ─── ⑤ 수묵 — 채색 비율 (청하현과 같은 잣대. 세계는 하나다) ───

    private static void ink(List<String> out, List<String> violations, World world, Zone zone) {
        out.add(HEAD + "⑤ 수묵 — 채색 비율 (깃발·매화·등롱에만)");
        long colored = 0;
        long solid = 0;
        for (int x = zone.x1(); x <= zone.x2(); x += 2) {
            for (int z = zone.z1(); z <= zone.z2(); z += 2) {
                for (int y = zone.y1(); y <= zone.y2(); y++) {
                    Material m = world.getBlockAt(x, y, z).getType();
                    if (m.isAir()) {
                        continue;
                    }
                    solid++;
                    String n = m.name();
                    if (n.endsWith("_WOOL") || n.endsWith("_CARPET") || n.endsWith("_BANNER")
                            || n.startsWith("CHERRY_") || n.endsWith("_CONCRETE")) {
                        colored++;
                    }
                }
            }
        }
        double pct = solid == 0 ? 0 : (double) colored / solid;
        out.add(INFO + "  채색 " + colored + " / 비공기 " + solid + " = "
                + String.format("%.2f%%", pct * 100));
        if (pct > COLOR_MAX) {
            out.add(BAD + "  채색 " + String.format("%.2f%%", pct * 100) + " > "
                    + String.format("%.0f%%", COLOR_MAX * 100) + " — 수묵이 깨진다");
            violations.add("채색과다");
        } else {
            out.add(OK + "  수묵 유지 (" + String.format("%.2f%%", pct * 100) + " ≤ 2%)");
        }
    }
}
