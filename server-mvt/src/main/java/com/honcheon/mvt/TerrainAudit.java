package com.honcheon.mvt;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 환경 검수 — <b>조성물과 자연의 이음매를 잰다</b>.
 *
 * <p>지금까지의 검수는 전부 <b>안</b>을 봤다 (길 폭·처마·소품·도달성). 그런데 사용자가 접속해서 본 것은
 * <b>바깥</b>의 파탄이었다:
 * <ul>
 *   <li>바닐라 동굴·협곡이 마을 바닥 밑에서 잘려 <b>구멍과 낭떠러지</b>가 열렸다</li>
 *   <li>강·호수가 부지에 걸려 토막 나고, <b>산 위에 웅덩이</b>가 남았다</li>
 *   <li>부지 경계에서 지형이 <b>뚝 끊겨</b> 마을이 섬처럼 떠 있다</li>
 * </ul>
 * 조성기는 자연 위에 상자를 찍어 넣었고, <b>그것을 볼 눈이 없었다.</b> 이 검수가 그 눈이다.
 *
 * <p>재는 것 (전부 객관 수치):
 * <ol>
 *   <li><b>바닥 밑 공동</b> — 조성 지면 아래 4칸이 공기인 비율 (동굴이 바닥을 먹었다)</li>
 *   <li><b>수역 파탄</b> — 공중의 물 / 갇힌 웅덩이 / 벽에 붙어 새는 물</li>
 *   <li><b>경계 절벽</b> — 부지 가장자리의 급단차 (한 칸에 3칸 이상 뛰는 자리)</li>
 *   <li><b>연결성</b> — <b>바깥에서 걸어 들어올 수 있는가</b> (마을 밖 60칸에서 중심까지 BFS)</li>
 *   <li><b>부유 블록</b> — 아무것에도 붙지 않은 블록</li>
 * </ol>
 */
final class TerrainAudit {

    private TerrainAudit() {
    }

    private static final String OK = "§a✅ ";
    private static final String WARN = "§e⚠ ";
    private static final String BAD = "§c❌ ";
    private static final String HEAD = "§6";
    private static final String INFO = "§7";

    private static final double VOID_MAX = 0.02;      // 바닥 밑 공동 2%
    private static final double WATER_MAX = 0.005;    // 이상 수역 0.5%
    private static final double CLIFF_MAX = 0.08;     // 경계 급단차 8%
    private static final int FLOAT_MAX = 40;          // 부유 블록

    /**
     * <b>사람이 깐 바닥</b> — 이 검수의 기준선.
     *
     * <p>첫 판은 자연의 동굴까지 "마을 밑의 구멍"으로 셌다(16%). 그건 거짓이다 — 산 밑에 동굴이 있는 것은
     * 세계의 자산이지 조성의 실패가 아니다. 실패는 <b>우리가 깐 길·바닥 밑이 비었을 때</b>다.
     * 자연 지면 밑의 동굴은 그대로 두고(나중에 기연·은신처가 된다), 우리 것만 묻는다.
     */
    private static boolean manMadeFloor(Material m) {
        // **길과 마당만** 센다. 판재·슬래브·지붕까지 세었더니 망루 마루와 초가지붕이 "밑이 빈 바닥"으로
        // 잡혔다(13%) — 마루 밑이 비는 건 마루라서 그렇다. 뚫리면 안 되는 것은 **사람이 걷는 땅**이다.
        return m == Material.DIRT_PATH || m == Material.POLISHED_ANDESITE || m == Material.COARSE_DIRT
                || m == Material.GRAVEL || m == Material.ANDESITE;
    }

    /**
     * @param cx 조성 중심 · @param cy 조성 지면 · @param r 부지 반경 (청하현 61 · 지역은 구역 크기)
     */
    static List<String> audit(World world, String name, int cx, int cy, int cz, int r) {
        return audit(world, name, cx, cy, cz, r, "평지");
    }

    /**
     * 지형을 아는 눈 — <b>산의 잣대와 들의 잣대는 다르다</b>.
     *
     * <p>구판은 산채에게 "사방에서 걸어 들어오라"고 요구했다. 그건 틀렸다 — 산채는 산꼭대기에
     * <b>한 길로</b> 있는 것이 정상이고, 오히려 사방에서 들어올 수 있으면 그건 산채가 아니다.
     * 산에서는 진입로 하나면 되고, 급단차도 산의 몫이 있다. 들에서는 사방이 열려야 한다.
     */
    static List<String> audit(World world, String name, int cx, int cy, int cz, int r, String terrain) {
        return audit(world, name, cx, cy, cz, r, terrain, false);
    }

    /** @param dugCave 이 지역에 <b>우리가 판 굴</b>이 있는가 (있으면 지하 공동에 여유를 준다) */
    static List<String> audit(World world, String name, int cx, int cy, int cz, int r, String terrain,
                              boolean dugCave) {
        boolean mountain = "산".equals(terrain) || "험산".equals(terrain) || "고원".equals(terrain);
        return auditInner(world, name, cx, cy, cz, r, mountain, dugCave);
    }

    private static List<String> auditInner(World world, String name, int cx, int cy, int cz, int r,
                                           boolean mountain, boolean dugCave) {
        List<String> out = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        out.add(HEAD + "══ 환경 검수 — " + name + " (중심 " + cx + "," + cy + "," + cz + " · 반경 " + r + ") ══");

        hollow(out, violations, world, cx, cy, cz, r);
        water(out, violations, world, cx, cy, cz, r);
        cliffs(out, violations, world, cx, cy, cz, r, mountain);
        connectivity(out, violations, world, cx, cy, cz, r, mountain);
        floating(out, violations, world, cx, cy, cz, r);
        underground(out, violations, world, cx, cy, cz, r, dugCave);

        out.add(HEAD + "── 총평 ──");
        out.add(violations.isEmpty() ? OK + "위반 0건 — 조성물이 자연에 앉아 있다"
                : BAD + "위반 " + violations.size() + "건: " + String.join(" / ", violations));
        return out;
    }

    // ─── ① 바닥 밑 공동 — 동굴이 마을 바닥을 먹었다 ───

    private static void hollow(List<String> out, List<String> violations,
                               World world, int cx, int cy, int cz, int r) {
        out.add(HEAD + "① 바닥 밑 공동 — 마을 밑을 동굴이 먹었는가");
        int holes = 0;
        int sampled = 0;
        List<String> spots = new ArrayList<>();
        for (int x = cx - r; x <= cx + r; x += 2) {
            for (int z = cz - r; z <= cz + r; z += 2) {
                int surface = surfaceY(world, x, z, cy);
                if (surface == Integer.MIN_VALUE) {
                    continue;
                }
                if (!manMadeFloor(world.getBlockAt(x, surface - 1, z).getType())) {
                    continue;   // 자연 지면 — 그 밑의 동굴은 자연의 것이다 (세계의 자산이지 실패가 아니다)
                }
                if (surface > cy + 4 || surface < cy - 4) {
                    continue;   // **지면만** 본다. 지붕도 '사람이 깐 바닥'이라 집 안의 공기를 구멍으로 셌다(31%).
                }
                sampled++;
                int air = 0;
                for (int y = surface - 2; y >= surface - 5; y--) {
                    if (world.getBlockAt(x, y, z).getType().isAir()) {
                        air++;
                    }
                }
                if (air >= 3) {   // 우리가 깐 바닥 바로 밑 네 칸 중 셋이 허공 = 껍데기 위의 길이다
                    holes++;
                    if (spots.size() < 6) {
                        spots.add("(" + x + "," + surface + "," + z + ")");
                    }
                }
            }
        }
        double pct = sampled == 0 ? 0 : (double) holes / sampled;
        out.add(INFO + "  표본 " + sampled + "칸 · 밑이 빈 자리 " + holes + "칸 ("
                + String.format("%.1f%%", pct * 100) + ")");
        if (!spots.isEmpty()) {
            out.add(INFO + "    자리: " + String.join(" ", spots));
        }
        if (pct > VOID_MAX) {
            out.add(BAD + "  바닥 밑 공동 " + String.format("%.1f%%", pct * 100) + " > "
                    + String.format("%.0f%%", VOID_MAX * 100) + " — 마을이 껍데기 위에 서 있다");
            violations.add("바닥공동" + Math.round(pct * 100) + "%");
        } else {
            out.add(OK + "  바닥 밑이 단단하다 (" + String.format("%.1f%%", pct * 100) + ")");
        }
    }

    // ─── ② 수역 파탄 — 공중의 물 · 갇힌 웅덩이 · 새는 물 ───

    private static void water(List<String> out, List<String> violations,
                              World world, int cx, int cy, int cz, int r) {
        out.add(HEAD + "② 수역 — 공중의 물 · 산 위의 웅덩이");
        int floatingWater = 0;   // 밑이 허공인 물 (잘린 수역)
        int perched = 0;         // 주변보다 높이 고인 물 (산 위의 웅덩이)
        int total = 0;
        int solidTotal = 0;
        List<String> spots = new ArrayList<>();
        for (int x = cx - r; x <= cx + r; x += 2) {
            for (int z = cz - r; z <= cz + r; z += 2) {
                for (int y = cy - 8; y <= cy + 24; y++) {
                    Material m = world.getBlockAt(x, y, z).getType();
                    if (!m.isAir()) {
                        solidTotal++;
                    }
                    if (m != Material.WATER) {
                        continue;
                    }
                    total++;
                    if (world.getBlockAt(x, y - 1, z).getType().isAir()) {
                        floatingWater++;   // 물 밑이 허공 = 잘린 수역
                        if (spots.size() < 6) {
                            spots.add("공중물(" + x + "," + y + "," + z + ")");
                        }
                    } else if (y > cy + 2) {
                        perched++;          // 조성 지면보다 두 칸 넘게 높은 물 = 산 위의 웅덩이
                        if (spots.size() < 6) {
                            spots.add("고인물(" + x + "," + y + "," + z + ")");
                        }
                    }
                }
            }
        }
        double pct = solidTotal == 0 ? 0 : (double) (floatingWater + perched) / solidTotal;
        out.add(INFO + "  물 " + total + "칸 · 공중의 물 " + floatingWater + " · 높이 고인 물 " + perched);
        if (!spots.isEmpty()) {
            out.add(INFO + "    자리: " + String.join(" ", spots));
        }
        if (pct > WATER_MAX || floatingWater > 30) {
            out.add(BAD + "  수역이 잘렸다 — 공중의 물 " + floatingWater + " · 고인 물 " + perched
                    + " (물은 흘러야 물이다)");
            violations.add("수역파탄" + (floatingWater + perched));
        } else {
            out.add(OK + "  수역 온전 (공중 " + floatingWater + " · 고인 " + perched + ")");
        }
    }

    // ─── ③ 경계 절벽 — 바깥에서 걸어 들어올 수 있는 땅인가 ───

    private static void cliffs(List<String> out, List<String> violations,
                               World world, int cx, int cy, int cz, int r, boolean mountain) {
        out.add(HEAD + "③ 경계 — 부지 가장자리가 절벽인가" + (mountain ? " (산의 잣대)" : ""));
        double limit = mountain ? 0.30 : CLIFF_MAX;   // 산은 원래 가파르다 — 벼랑을 금할 수는 없다
        int steep = 0;
        int sampled = 0;
        int worst = 0;
        for (int i = 0; i < 360; i += 3) {
            double a = Math.toRadians(i);
            int prev = Integer.MIN_VALUE;
            boolean prevMade = false;
            for (int d = r - 12; d <= r + 20; d++) {   // 담장 안쪽부터 바깥 20칸까지 훑는다
                int x = cx + (int) Math.round(Math.cos(a) * d);
                int z = cz + (int) Math.round(Math.sin(a) * d);
                int y = terrainSurfaceY(world, x, z, cy);
                if (y == Integer.MIN_VALUE) {
                    continue;
                }
                if (prev != Integer.MIN_VALUE) {
                    // **이음매만** 센다: 한쪽이 사람이 깐 것이고 다른 쪽이 자연일 때의 단차.
                    //   자연 대 자연의 단차는 산이다 — 산을 두고 "경계가 절벽"이라 외치면 눈이 거짓말한다.
                    boolean here = manMadeFloor(world.getBlockAt(x, y - 1, z).getType());
                    if (here != prevMade) {
                        int jump = Math.abs(y - prev);
                        sampled++;
                        if (jump >= 3) {   // 한 칸 걸음에 세 칸 이상 = 사람이 못 넘는다
                            steep++;
                            worst = Math.max(worst, jump);
                        }
                    }
                    prevMade = here;
                } else {
                    prevMade = manMadeFloor(world.getBlockAt(x, y - 1, z).getType());
                }
                prev = y;
            }
        }
        double pct = sampled == 0 ? 0 : (double) steep / sampled;
        out.add(INFO + "  경계 표본 " + sampled + "걸음 · 급단차(3칸+) " + steep + "회 ("
                + String.format("%.1f%%", pct * 100) + ") · 최대 " + worst + "칸");
        if (pct > limit) {
            out.add(BAD + "  경계 급단차 " + String.format("%.1f%%", pct * 100) + " > "
                    + String.format("%.0f%%", limit * 100) + " — 조성물이 섬처럼 끊겼다");
            violations.add("경계절벽" + Math.round(pct * 100) + "%");
        } else {
            out.add(OK + "  경계가 자연으로 이어진다 (" + String.format("%.1f%%", pct * 100) + ")");
        }
    }

    // ─── ④ 연결성 — 바깥에서 걸어 들어올 수 있는가 ───

    /**
     * 마을 밖 <b>먼 곳</b>에서 중심까지 걸어 본다. 지형 검수의 결론은 이것이다:
     * 마을이 아무리 예뻐도 <b>바깥에서 걸어 들어올 수 없으면 그것은 섬</b>이다.
     */
    private static void connectivity(List<String> out, List<String> violations,
                                     World world, int cx, int cy, int cz, int r, boolean mountain) {
        out.add(HEAD + "④ 연결성 — 바깥에서 걸어 들어올 수 있는가"
                + (mountain ? " (산 — 길 하나면 된다)" : ""));
        int arrived = 0;
        int tried = 0;
        List<String> failed = new ArrayList<>();
        String[] names = {"북", "동", "남", "서"};
        int[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int i = 0; i < 4; i++) {
            int sx = cx + dirs[i][0] * (r + 24);
            int sz = cz + dirs[i][1] * (r + 24);
            tried++;
            if (walkable(world, sx, sz, cx, cz, cy, r + 32)) {
                arrived++;
            } else {
                failed.add(names[i]);
            }
        }
        out.add(INFO + "  네 방위에서 걸어 들어오기 — 성공 " + arrived + "/" + tried);
        int need = mountain ? 1 : tried;   // 산채는 한 길로 있는 것이 정상이다 (사방이 열리면 산채가 아니다)
        if (arrived < need) {
            out.add(BAD + "  어느 쪽에서도 걸어 들어올 수 없다 — 절벽·물·구멍이 길을 끊었다");
            violations.add("진입불가");
        } else if (arrived < tried) {
            out.add(OK + "  " + arrived + "방위에서 걸어 들어온다 (" + String.join("·", failed)
                    + "쪽은 벼랑 — 산의 몫이다)");
        } else {
            out.add(OK + "  네 방위 모두에서 걸어 들어온다");
        }
    }

    private static boolean walkable(World world, int sx, int sz, int gx, int gz, int cy, int limit) {
        int sy = surfaceY(world, sx, sz, cy);
        if (sy == Integer.MIN_VALUE) {
            return false;
        }
        Set<Long> seen = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{sx, sy + 1, sz});
        seen.add(key(sx, sy + 1, sz));
        int visited = 0;
        while (!queue.isEmpty() && visited < 120_000) {
            int[] cur = queue.poll();
            visited++;
            if (Math.abs(cur[0] - gx) <= 3 && Math.abs(cur[2] - gz) <= 3) {
                return true;
            }
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = cur[0] + d[0];
                int nz = cur[2] + d[1];
                if (Math.abs(nx - gx) > limit || Math.abs(nz - gz) > limit) {
                    continue;
                }
                for (int dy = 1; dy >= -4; dy--) {
                    int ny = cur[1] + dy;
                    if (!canStand(world, nx, ny, nz)) {
                        continue;
                    }
                    if (seen.add(key(nx, ny, nz))) {
                        queue.add(new int[]{nx, ny, nz});
                    }
                    break;
                }
            }
        }
        return false;
    }

    // ─── ⑤ 부유 블록 ───

    private static void floating(List<String> out, List<String> violations,
                                 World world, int cx, int cy, int cz, int r) {
        out.add(HEAD + "⑤ 부유 블록 — 아무것에도 붙지 않은 것");
        int loose = 0;
        List<String> spots = new ArrayList<>();
        for (int x = cx - r; x <= cx + r; x += 3) {
            for (int z = cz - r; z <= cz + r; z += 3) {
                for (int y = cy - 4; y <= cy + 26; y++) {
                    Material m = world.getBlockAt(x, y, z).getType();
                    if (m.isAir() || !m.isSolid()) {
                        continue;
                    }
                    boolean supported = false;
                    for (int[] d : new int[][]{{0, -1, 0}, {0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}}) {
                        if (!world.getBlockAt(x + d[0], y + d[1], z + d[2]).getType().isAir()) {
                            supported = true;
                            break;
                        }
                    }
                    if (!supported) {
                        loose++;
                        if (spots.size() < 5) {
                            spots.add("(" + x + "," + y + "," + z + ")");
                        }
                    }
                }
            }
        }
        out.add(INFO + "  외톨이 블록 " + loose + "개" + (spots.isEmpty() ? "" : " · " + String.join(" ", spots)));
        if (loose > FLOAT_MAX) {
            out.add(BAD + "  부유 블록 " + loose + "개 > " + FLOAT_MAX);
            violations.add("부유블록" + loose);
        } else {
            out.add(OK + "  부유 블록 " + loose + "개 — 허용 범위");
        }
    }

    // ─── ⑥ 지하 공동 — 자연 동굴이 있는가 (있으면 안 된다) ───

    /**
     * 지하의 빈 곳 — <b>자연 동굴은 통제할 수 없다</b>는 판단(사용자)에 따라, 세계는 <b>동굴 없이</b> 생성되고
     * 동굴이 필요하면 <b>우리가 판다</b>. 그 약속이 지켜졌는지 재는 눈이다.
     *
     * <p>지면 아래 5~45칸을 표본해 공기(와 물)의 비율을 센다. 자연 동굴이 살아 있으면 이 값이 5~15% 나온다.
     * 데이터팩(honcheon_no_caves)이 제대로 걸렸다면 <b>1% 미만</b>이어야 한다 —
     * 우리가 판 동굴이 있다면 그만큼은 정직하게 잡힌다(그건 위반이 아니라 설계다).
     */
    private static void underground(List<String> out, List<String> violations,
                                    World world, int cx, int cy, int cz, int r, boolean dugCave) {
        out.add(HEAD + "⑥ 지하 — 자연 동굴이 남아 있는가"
                + (dugCave ? " (우리가 판 굴이 있는 지역 — 여유 8%)" : " (동굴은 우리가 판다)"));
        long air = 0;
        long total = 0;
        for (int x = cx - r; x <= cx + r; x += 4) {
            for (int z = cz - r; z <= cz + r; z += 4) {
                for (int y = cy - 45; y <= cy - 5; y++) {
                    if (y < world.getMinHeight() + 5) {
                        continue;
                    }
                    Material m = world.getBlockAt(x, y, z).getType();
                    total++;
                    if (m.isAir() || m == Material.WATER || m == Material.LAVA) {
                        air++;
                    }
                }
            }
        }
        double pct = total == 0 ? 0 : (double) air / total;
        out.add(INFO + "  지하 표본 " + total + "칸 · 빈 곳 " + air + "칸 ("
                + String.format("%.2f%%", pct * 100) + ")");
        // **우리가 판 굴은 위반이 아니다.** 산적굴 1,006칸이 "자연동굴 4%"로 잡혔다 — 그 굴은 설계다.
        //   등록부가 굴을 요구한 지역에는 그만큼의 여유를 준다 (원형 최대 제단굴 ~2,100칸 = 약 1.2%).
        double limit = dugCave ? 0.08 : 0.02;
        if (pct > limit) {
            out.add(BAD + "  지하 공동 " + String.format("%.1f%%", pct * 100)
                    + " — 자연 동굴이 살아 있다 (데이터팩 honcheon_no_caves 미적용?)");
            violations.add("자연동굴" + String.format("%.0f%%", pct * 100));
        } else {
            out.add(OK + "  지하가 채워져 있다 (" + String.format("%.2f%%", pct * 100)
                    + ") — 동굴은 우리가 판다");
        }
    }

    // ─── 손 ───

    /**
     * <b>지형</b>의 표면 — 사람이 세운 것(담·목책·집·계단)을 지나쳐 땅을 찾는다.
     *
     * <p>경계 검사가 목책과 담을 "절벽"으로 셌다(48%). 담은 지형이 아니다 — 담이 높다고
     * 마을이 섬인 것은 아니다. 이 검사가 묻는 것은 <b>땅이 이어지는가</b>이지 벽이 있는가가 아니다.
     */
    private static int terrainSurfaceY(World world, int x, int z, int cy) {
        for (int y = cy + 40; y >= cy - 40; y--) {
            Material m = world.getBlockAt(x, y, z).getType();
            if (!m.isSolid()) {
                continue;
            }
            String n = m.name();
            boolean structure = n.endsWith("_LOG") || n.endsWith("_PLANKS") || n.endsWith("_STAIRS")
                    || n.endsWith("_SLAB") || n.endsWith("_FENCE") || n.endsWith("_WALL")
                    || n.endsWith("_TILES") || n.endsWith("_BRICKS") || n.endsWith("_TERRACOTTA")
                    || n.endsWith("_LEAVES") || m == Material.HAY_BLOCK || m == Material.GLASS_PANE
                    || n.endsWith("_DOOR") || n.endsWith("_SHELF") || m == Material.BOOKSHELF;
            if (structure) {
                continue;   // 사람이 세운 것 — 지형이 아니다
            }
            return y + 1;
        }
        return Integer.MIN_VALUE;
    }

    /** 그 열의 지표면 y (물·잎·풀은 지나친다 — 딛는 자리를 찾는다) */
    private static int surfaceY(World world, int x, int z, int cy) {
        for (int y = cy + 40; y >= cy - 40; y--) {
            Material m = world.getBlockAt(x, y, z).getType();
            if (m.isSolid() && !m.name().endsWith("_LEAVES")) {
                return y + 1;   // 그 위가 딛는 자리
            }
        }
        return Integer.MIN_VALUE;
    }

    private static boolean canStand(World world, int x, int y, int z) {
        Material floor = world.getBlockAt(x, y - 1, z).getType();
        if (!floor.isSolid()) {
            return false;
        }
        return !world.getBlockAt(x, y, z).getType().isSolid()
                && !world.getBlockAt(x, y + 1, z).getType().isSolid();
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (y & 0xFFF) << 30) | (z & 0x3FFFFFF);
    }

    /** 앵커에서 중심을 잡는 편의 진입점 (청하현) */
    static List<String> auditTown(World world, Location center, int radius) {
        return audit(world, "청하현", center.getBlockX(), center.getBlockY() - 1,
                center.getBlockZ(), radius);
    }
}
