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
 *   <li><b>연결성</b> — <b>바깥에서 걸어 들어올 수 있는가</b> (마을 밖 60칸에서 중심까지 BFS)
 *       <br>★ <b>섬은 면제된다.</b> 그 자리에 <b>④-나루</b>가 선다 (아래 {@link #CONNECTIVITY_EXEMPT})</li>
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
    private static final String EXEMPT = "§b⊖ ";       // ★ 면제 — <b>조용히 넘어가지 않는다. 소리내어 선언한다</b>

    private static final double VOID_MAX = 0.02;      // 바닥 밑 공동 2%
    private static final double WATER_MAX = 0.005;    // 이상 수역 0.5%
    private static final double CLIFF_MAX = 0.08;     // 경계 급단차 8%
    private static final int FLOAT_MAX = 40;          // 부유 블록

    // ═══════════════════════════════════════════════════════════════════════
    // ★★★ 면제 등록부 — <b>면제는 은닉이 아니다</b>
    // ═══════════════════════════════════════════════════════════════════════
    //
    //   ★ 전례를 따른다 — 팩 담당의 {@code NON_ICON} · {@code CENTER_EXEMPT} (tools/texture_audit.py):
    //       <i>"축이 시끄럽다고 파일을 숨기면 축은 조용해지고 세계가 깨진다."</i>
    //       <i>"<b>면제는 '재지 않는다'가 아니라 '다른 자로 잰다'는 뜻이다.</b>"</i>
    //
    //   ★★ 그러므로 이 표는 <b>세 가지를 동시에 한다</b>:
    //      ① 어느 지형이 ④ 를 면제받는가       ② <b>왜</b> (사람이 읽을 수 있는 이유)
    //      ③ <b>그 자리에 무엇이 서는가</b>      ← ★★★ 이것이 없으면 면제가 아니라 <b>구멍</b>이다
    //
    //   ★ 정본은 {@code config/terrain.yml audit.connectivity_exempt} 다 — 이 표는 그것의 거울이다.
    //     (코드가 등록부를 읽지 않고 <b>지형 유형</b>으로만 가른다: 어느 곳이 섬인지는
    //      {@code world_map.yml} 의 {@code terrain: 섬} 이 이미 선언했다. <b>장소 이름을 코드에 박지 않는다</b>)

    /** ④ 연결성을 면제받는 지형 → <b>그 이유</b>. ★ 검수가 이 문장을 <b>출력한다</b> */
    private static final java.util.Map<String, String> CONNECTIVITY_EXEMPT = java.util.Map.of(
            "섬", "★ 섬은 걸어 들어올 수 **없어야** 옳다 — 등록부: '바다가 가른다 · 관문이 없다'. "
                    + "**걸어서 갈 수 있는 해남파는 해남파가 아니다** (사용자 결정 2026-07-13)");

    /** 면제받은 지형에 <b>대신 서는 축</b> — ★ 면제만 받고 아무것도 안 내면 그것은 <b>구멍</b>이다 */
    private static final java.util.Map<String, String> EXEMPT_REPLACED_BY = java.util.Map.of(
            "섬", "나루");

    // ─── ④-나루 의 문턱 — config/terrain.yml audit.dock_axis 와 <b>같은 수여야 한다</b> ───
    private static final int DOCK_MIN_DEPTH = 2;      // 배가 뜨는 최소 수심
    private static final int DOCK_OPEN_RUN = 12;      // 물이 바깥으로 이어져야 하는 거리 (★ 웅덩이 배제)
    private static final int DOCK_MIN = 1;            // ★ 나루 하나면 된다 — 섬에 사방의 나루를 요구하지 않는다

    // ─── ⑥ 의 문턱 — config/terrain.yml audit.underground_* 와 <b>같은 수여야 한다</b> ───
    private static final double UNDERGROUND_VOID_MAX = 0.02;   // audit.underground_void_max — 공기만 센다
    private static final double UNDERGROUND_DUG_MAX = 0.08;    // audit.underground_dug_cave_max — 우리가 판 굴의 여유
    private static final int UNDERGROUND_MARGIN = 5;           // audit.underground_surface_margin — 지면 아래 5칸부터가 지하다

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
        // 물의 잣대 — 강가·섬·수향에 "네 방위에서 걸어 들어오라"고 요구할 수 없다.
        //   수로채는 **찾아가지 않는다. 찾아온다** (등록부의 말). 배로 오는 집에 육로를 요구하면 그건 눈이 틀린 것이다.
        boolean water = "강".equals(terrain) || "수향".equals(terrain) || "섬".equals(terrain)
                || "물가".equals(terrain);
        boolean loose = "산".equals(terrain) || "험산".equals(terrain) || "고원".equals(terrain) || water;
        return auditInner(world, name, cx, cy, cz, r, loose, dugCave, terrain);
    }

    private static List<String> auditInner(World world, String name, int cx, int cy, int cz, int r,
                                           boolean mountain, boolean dugCave,
                                           String terrain) {
        List<String> out = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        out.add(HEAD + "══ 환경 검수 — " + name + " (중심 " + cx + "," + cy + "," + cz + " · 반경 " + r + ") ══");

        hollow(out, violations, world, cx, cy, cz, r);
        water(out, violations, world, cx, cy, cz, r);
        cliffs(out, violations, world, cx, cy, cz, r, mountain);

        // ─── ④ — ★ 지형에 따라 **다른 자를 든다** (면제는 소리내어 선언한다) ───
        String exemptWhy = CONNECTIVITY_EXEMPT.get(terrain);
        if (exemptWhy == null) {
            connectivity(out, violations, world, cx, cy, cz, r, mountain);
        } else {
            // ★★ 조용히 건너뛰지 않는다 — **면제를 출력하고, 그 자리에 다른 축을 세운다**
            out.add(EXEMPT + "④ 연결성 — **면제** (지형: " + terrain + ")");
            out.add(INFO + "    " + exemptWhy);
            out.add(INFO + "    ★ 등록부: config/terrain.yml audit.connectivity_exempt." + terrain);
            String replacement = EXEMPT_REPLACED_BY.get(terrain);
            if ("나루".equals(replacement)) {
                out.add(INFO + "    ★★ **면제는 구멍이 아니다** — 그 자리에 「나루」 축이 선다 (아래 ④-나루)");
                dock(out, violations, world, cx, cy, cz, r);
            } else {
                // ★ 면제는 등록됐는데 **대신 설 축이 없다** — 그것이 곧 구멍이다. 위반으로 짖는다
                out.add(BAD + "  ★★ 면제만 있고 **대신 설 축이 없다** — 그것은 면제가 아니라 **구멍**이다");
                violations.add("면제구멍(" + terrain + ")");
            }
        }

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
        // 산은 이제 **의도적으로 벼랑**이다 (사용자: "완만하게 안 만들어도 됨. 길 하나 내면 됨").
        //   0.30 → 0.60. 다만 **없애지는 않는다**: "우리가 낸 길은 걸을 수 있어야 한다"는 여전히 참이고,
        //   그것은 ④ 연결성과 지역 검수 ① 도달성이 지킨다 (등반로 최대 단차 1칸).
        double limit = mountain ? 0.60 : CLIFF_MAX;
        int steep = 0;
        int sampled = 0;
        int worst = 0;
        // ★ B-127 — 수리는 가장자리만 다듬는다. **좌표 없는 위반은 수리할 수 없는 위반이다.**
        //   판정·문턱·표본 방식은 한 줄도 안 바뀐다 — 급단차가 난 자리만 줍는다. {단차, x, 이쪽 y, 저쪽 y, z}
        List<int[]> steepSpots = new ArrayList<>();
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
                            steepSpots.add(new int[]{jump, x, prev, y, z});
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
            // ★ 어디가 끊겼는가 — 단차 큰 순으로 최대 8곳. 상한을 두면 그 사실을 말한다 (침묵 절단 금지)
            steepSpots.sort((a, b) -> Integer.compare(b[0], a[0]));
            int show = Math.min(8, steepSpots.size());
            for (int k = 0; k < show; k++) {
                int[] s = steepSpots.get(k);
                out.add(INFO + "    · 턱 (" + s[1] + ", " + s[2] + "→" + s[3] + ", " + s[4]
                        + ") 단차 " + s[0] + "칸");
            }
            if (steepSpots.size() > show) {
                out.add(INFO + "    · 외 " + (steepSpots.size() - show) + "곳 — 단차 큰 순 8곳만 보였다");
            }
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
        // ★ B-127 — 실패 방위의 **최근접 도달점** {방위, x, y, z}. 판정은 아래 walkBlocked == null 그대로다
        List<int[]> blockedAt = new ArrayList<>();
        String[] names = {"북", "동", "남", "서"};
        int[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int i = 0; i < 4; i++) {
            int sx = cx + dirs[i][0] * (r + 24);
            int sz = cz + dirs[i][1] * (r + 24);
            tried++;
            int[] stop = walkBlocked(world, sx, sz, cx, cz, cy, r + 32);
            if (stop == null) {
                arrived++;
            } else {
                failed.add(names[i]);
                blockedAt.add(new int[]{i, stop[0], stop[1], stop[2]});
            }
        }
        out.add(INFO + "  네 방위에서 걸어 들어오기 — 성공 " + arrived + "/" + tried);
        int need = mountain ? 1 : tried;   // 산채는 한 길로 있는 것이 정상이다 (사방이 열리면 산채가 아니다)
        if (arrived < need) {
            out.add(BAD + "  어느 쪽에서도 걸어 들어올 수 없다 — 절벽·물·구멍이 길을 끊었다");
            // ★ 어디서 끊겼는가 — 방위마다 최근접 도달점과 그 앞의 벽 (통과일 때는 좌표를 내지 않는다)
            for (int[] b : blockedAt) {
                String yTxt = b[2] == Integer.MIN_VALUE ? "?" : String.valueOf(b[2]);
                out.add(INFO + "    · " + names[b[0]] + " — (" + b[1] + "," + yTxt + "," + b[3]
                        + ") 까지 오고 끊김: " + stopReason(world, b[1], b[2], b[3], cx, cz, cy));
            }
            violations.add("진입불가");
        } else if (arrived < tried) {
            out.add(OK + "  " + arrived + "방위에서 걸어 들어온다 (" + String.join("·", failed)
                    + "쪽은 벼랑 — 산의 몫이다)");
        } else {
            out.add(OK + "  네 방위 모두에서 걸어 들어온다");
        }
    }

    private static boolean walkable(World world, int sx, int sz, int gx, int gz, int cy, int limit) {
        return walkBlocked(world, sx, sz, gx, gz, cy, limit) == null;
    }

    /**
     * ④ 의 걷는 손 + <b>어디서 끊겼는지 아는 눈</b> (B-127).
     *
     * <p>구판 {@link #walkable} 과 <b>같은 BFS 를 같은 순서로</b> 걷는다 — 판정은 이 답이
     * {@code null} 인가로만 갈리므로 눈금은 한 치도 안 바뀐다. 다만 못 닿았을 때
     * <b>중심에 가장 가까이 간 발자리</b> {x, y, z} 를 돌려준다 — 수리는 거기서부터 다듬는다.
     *
     * @return 닿았으면 {@code null}. 못 닿았으면 최근접 도달점 (출발부터 설 땅이 없으면 y = {@code MIN_VALUE})
     */
    private static int[] walkBlocked(World world, int sx, int sz, int gx, int gz, int cy, int limit) {
        int sy = surfaceY(world, sx, sz, cy);
        if (sy == Integer.MIN_VALUE) {
            return new int[]{sx, Integer.MIN_VALUE, sz};
        }
        Set<Long> seen = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{sx, sy + 1, sz});
        seen.add(key(sx, sy + 1, sz));
        int visited = 0;
        int[] best = {sx, sy + 1, sz};
        int bestDist = Math.abs(sx - gx) + Math.abs(sz - gz);
        while (!queue.isEmpty() && visited < 120_000) {
            int[] cur = queue.poll();
            visited++;
            if (Math.abs(cur[0] - gx) <= 3 && Math.abs(cur[2] - gz) <= 3) {
                return null;
            }
            int dist = Math.abs(cur[0] - gx) + Math.abs(cur[2] - gz);
            if (dist < bestDist) {
                bestDist = dist;
                best = cur;
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
        return best;
    }

    /**
     * ④ 진단 — 최근접 도달점에서 <b>중심 쪽 다음 한 걸음</b>이 왜 막히는지 짚는다.
     * 판정에는 손대지 않는다 — 위반이 난 뒤에만 불려서 이유를 <b>말</b>로 바꾼다 (단차인가, 물인가).
     */
    private static String stopReason(World world, int bx, int by, int bz, int gx, int gz, int cy) {
        if (by == Integer.MIN_VALUE) {
            return "출발점에 설 땅이 없다 (허공 또는 깊은 물)";
        }
        // 남은 거리가 큰 축으로 한 걸음 — BFS 와 같은 4방위 걸음이다
        int dx = gx - bx;
        int dz = gz - bz;
        int nx = bx;
        int nz = bz;
        if (Math.abs(dx) >= Math.abs(dz)) {
            nx += Integer.signum(dx);
        } else {
            nz += Integer.signum(dz);
        }
        int stand = surfaceY(world, nx, nz, cy);
        if (stand == Integer.MIN_VALUE) {
            return "허공 — (" + nx + ",?," + nz + ") 에 설 땅이 없다";
        }
        if (world.getBlockAt(nx, stand, nz).getType() == Material.WATER
                || world.getBlockAt(nx, stand + 1, nz).getType() == Material.WATER) {
            return "물 — (" + nx + "," + stand + "," + nz + ") 이 물속이다";
        }
        int diff = stand - by;
        if (diff > 1) {
            return "단차 +" + diff + "칸 — (" + nx + "," + stand + "," + nz + ") 을 못 오른다";
        }
        if (diff < -4) {
            return "단차 " + diff + "칸 — (" + nx + "," + stand + "," + nz + ") 은 낭떠러지다";
        }
        return "통로 막힘 — (" + nx + "," + stand + "," + nz + ") 머리 위가 막혔다";
    }

    // ─── ④-나루 — ★★ ④ 의 자리에 서는 축. **배가 닿는 자리가 있는가** ───

    /**
     * <b>★★★ 나루 — 섬의 자.</b>
     *
     * <p>④ 연결성과 <b>거울</b>이다:
     * <ul>
     *   <li>④ 연결성 — "<b>뭍에서 걸어</b> 들어올 수 있는가"  ← 섬에는 <b>틀린 질문</b></li>
     *   <li>④-나루   — "<b>물에서 배로</b> 닿을 수 있는가"    ← 섬에는 <b>옳은 질문</b></li>
     * </ul>
     *
     * <p><b>★ 셋을 동시에 만족하는 자리가 하나라도 있어야 한다</b> (하나면 된다 — 섬에 사방의 나루를 요구하지 않는다):
     * <ol>
     *   <li><b>물이 있다</b> — 부지 둘레에 물이 닿는다</li>
     *   <li><b>배가 뜬다</b> — 수심 {@value #DOCK_MIN_DEPTH} 이상 · 바깥으로 {@value #DOCK_OPEN_RUN} 칸 열려 있다
     *       <br>★ <b>웅덩이는 나루가 아니다</b> — 배가 들어올 수 없으므로</li>
     *   <li><b>내려서 걷는다</b> — 그 물가에서 중심까지 <b>걸어갈 수 있다</b> (④ 와 같은 BFS 손을 쓴다)
     *       <br>★★ <b>이것이 핵심이다.</b> 물만 닿고 절벽이면 그것은 나루가 아니라 <b>벼랑</b>이다 —
     *       배는 닿는데 사람이 못 내린다. <b>닿는 것과 내리는 것은 다르다</b></li>
     * </ol>
     *
     * <p>★ <b>배는 아직 없다</b> (사용자: "배는 아직 없다. 땅이 배를 기다리며 선다 — 땅은 한 번만 서니까").
     *   그래서 이 축은 <b>땅만 잰다</b> — "배가 닿을 수 있는 땅인가". 그럼에도 <b>지금</b> 재야 한다:
     *   <b>땅은 한 번만 선다</b> (TerrainLedger · TerrainSeal). 배가 생긴 뒤엔 못 고친다.
     */
    private static void dock(List<String> out, List<String> violations,
                             World world, int cx, int cy, int cz, int r) {
        out.add(HEAD + "④-나루 — 배가 닿아 **사람이 내릴 수 있는가** (섬의 자)");
        int docks = 0;
        int sawWater = 0;      // ① 물을 만난 방위
        int sawDeep = 0;       // ② 배가 뜨는 물 (수심 + 열린 바다)
        List<String> spots = new ArrayList<>();
        for (int i = 0; i < 360; i += 6) {
            double a = Math.toRadians(i);
            for (int d = 8; d <= r + 40; d++) {
                int x = cx + (int) Math.round(Math.cos(a) * d);
                int z = cz + (int) Math.round(Math.sin(a) * d);
                int top = waterTopY(world, x, z, cy);
                if (top == Integer.MIN_VALUE) {
                    continue;                       // 아직 뭍이다 — 더 나가 본다
                }
                sawWater++;                         // ① 물가를 만났다
                // ② 배가 뜨는가 — 수심이 있고, 그 물이 **바깥으로 이어지는가** (웅덩이가 아니라 바다인가)
                if (waterDepth(world, x, top, z) >= DOCK_MIN_DEPTH && openWater(world, cx, cz, cy, a, d)) {
                    sawDeep++;
                    // ③ 내려서 걷는가 — 물가 한 칸 안쪽(뭍)에서 중심까지 **걸어간다**
                    int sx = cx + (int) Math.round(Math.cos(a) * (d - 1));
                    int sz = cz + (int) Math.round(Math.sin(a) * (d - 1));
                    if (walkable(world, sx, sz, cx, cz, cy, r + 48)) {
                        docks++;
                        if (spots.size() < 4) {
                            spots.add("(" + sx + "," + sz + ")");
                        }
                    }
                }
                break;                              // 이 방위의 **첫 물가**만 본다
            }
        }
        out.add(INFO + "  방위 60 · 물가를 만난 방위 " + sawWater + " · 배가 뜨는 물 " + sawDeep
                + " · ★ **내릴 수 있는 나루 " + docks + "**");
        if (!spots.isEmpty()) {
            out.add(INFO + "    나루: " + String.join(" ", spots));
        }
        if (docks >= DOCK_MIN) {
            out.add(OK + "  배가 닿는다 — 나루 " + docks + "곳 (★ 땅이 배를 기다리며 선다)");
            return;
        }
        // ★ 실패했으면 **어디서 끊겼는지**를 말한다 — "없다"만 말하는 눈은 눈이 아니다
        String why;
        if (sawWater == 0) {
            why = "★ **물이 없다.** 섬이라고 등록됐는데 부지 둘레에 바다가 없다 "
                    + "(반도에 앉았거나, 좌표가 뭍 한복판이다 — terrain.yml pending.섬)";
        } else if (sawDeep == 0) {
            why = "★ **배가 못 뜬다.** 물은 있으나 얕거나 갇혀 있다 (수심 < " + DOCK_MIN_DEPTH
                    + " 또는 바깥으로 " + DOCK_OPEN_RUN + "칸이 안 열린다) — **웅덩이는 나루가 아니다**";
        } else {
            why = "★★ **닿는데 못 내린다.** 배가 뜨는 물은 " + sawDeep + "방위나 있는데 "
                    + "물가에서 중심까지 **걸어갈 수가 없다** — 그것은 나루가 아니라 **벼랑**이다";
        }
        out.add(BAD + "  나루가 없다 — " + why);
        violations.add("나루없음");
    }

    /** 그 열의 수면 y — <b>맨 위의 물</b>. 물이 없으면 {@code MIN_VALUE} */
    private static int waterTopY(World world, int x, int z, int cy) {
        for (int y = cy + 12; y >= cy - 40; y--) {
            if (world.getBlockAt(x, y, z).getType() == Material.WATER) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** 수면 아래로 물이 몇 칸인가 — <b>배가 뜨려면 깊이가 있어야 한다</b> */
    private static int waterDepth(World world, int x, int top, int z) {
        int depth = 0;
        for (int y = top; y >= top - 24; y--) {
            if (world.getBlockAt(x, y, z).getType() != Material.WATER) {
                break;
            }
            depth++;
        }
        return depth;
    }

    /**
     * 그 물이 <b>바깥으로 이어지는가</b> — ★ <b>웅덩이는 나루가 아니다.</b>
     *
     * <p>배는 어디선가 <b>들어와야</b> 한다. 부지 옆의 고인 물에는 배가 못 들어온다 —
     * 그 물이 바다로 이어져 있어야 나루다. 물가에서 바깥으로 {@value #DOCK_OPEN_RUN} 칸을 훑어
     * 8할 이상이 물이면 <b>열린 물</b>로 본다.
     */
    private static boolean openWater(World world, int cx, int cz, int cy, double a, int d) {
        int wet = 0;
        for (int k = 1; k <= DOCK_OPEN_RUN; k++) {
            int x = cx + (int) Math.round(Math.cos(a) * (d + k));
            int z = cz + (int) Math.round(Math.sin(a) * (d + k));
            if (waterTopY(world, x, z, cy) != Integer.MIN_VALUE) {
                wet++;
            }
        }
        return wet * 10 >= DOCK_OPEN_RUN * 8;
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
     * ⑥ 표본 한 칸의 분류 — <b>액체는 「공동」이 아니다</b>.
     *
     * <p>★ B-114 — 구판은 물(뭍 지형)·용암을 전부 "빈 곳"으로 셌고, 그래서 3.8% 가 나왔다.
     * 그런데 cheese 봉인 전 3.81% → 후 3.84% (같은 시드) — <b>무변동</b>. 그 3.8% 는 동굴이 아니라
     * 생성기의 <b>고정 구조층</b>이었다: 1.21 의 y&lt;-54 용암 바다와 대수층(수역)은 카버가 아니라
     * 노이즈 생성기가 만든다 — honcheon_no_caves 가 동굴을 다 죽여도 <b>그대로 남는다</b>.
     * 액체를 공동으로 세는 눈은 봉인의 성패를 영원히 못 읽는다.
     *
     * <p>등록부: {@code config/terrain.yml audit.underground_liquids}.
     * (하네스가 이 함수를 직접 시험한다 — 그래서 static 이고 package-private 이다.)
     */
    enum DeepBlock { AIR, WATER, LAVA, SOLID }

    static DeepBlock classifyDeep(Material m) {
        if (m == Material.WATER || m == Material.BUBBLE_COLUMN) {
            return DeepBlock.WATER;
        }
        if (m == Material.LAVA) {
            return DeepBlock.LAVA;
        }
        // ★ Material.isAir() 를 안 쓴다 — 1.21.11 의 isAir() 는 서버 레지스트리로 위임해서
        //   서버 밖(하네스)에서 터진다. 공기 세 종을 직접 대는 것이 같은 뜻이고 **순수**하다.
        return m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR
                ? DeepBlock.AIR : DeepBlock.SOLID;
    }

    /**
     * 원장이 기억하는 <b>판 굴</b>들의 상자 — 표본 영역과 겹치는 것만.
     *
     * <p>★ B-114 후속 — 액체를 분리하고도 3.84% 가 남았고 <b>전부 공기</b>였다. 세 번의 월드 생성
     * (옛 no_caves·새 no_caves·같은 시드)에서 3.81~3.84% 불변 — 데이터팩과 무관하게 재현되는
     * 고정 부피는 하나뿐이다: <b>TerrainForge 가 결정론으로 파는 우리의 굴</b>이다.
     *
     * <p><b>대조원은 원장(terrain_built.yml)이다</b> — config {@code caves:} 는 <b>의도</b>라
     * 좌표가 없고(원형만 있다), 원장은 <b>집행된 좌표</b>(입구·방·파낸 칸수)를 기억한다.
     * 검수는 세계를 재므로 집행의 기록과 대조하는 것이 옳다. 상자는 {@code CaveSpec.zone()} 과
     * <b>같은 식</b>이다 (입구·방 ±14 · 방바닥-8 … 입구+6) — 그리는 손과 재는 눈이 같은 상자를 쓴다.
     * 등록부: {@code config/terrain.yml audit.underground_dug_caves}.
     */
    static List<Zone> dugCaveBoxes(String worldName, int cx, int cz, int r) {
        List<Zone> boxes = new ArrayList<>();
        for (String id : TerrainLedger.ids()) {
            TerrainForge.CaveSpec c = TerrainLedger.cave(TerrainLedger.land(id), worldName);
            if (c == null) {
                continue;   // 이 땅에는 굴이 없다
            }
            Zone zn = c.zone("굴:" + id);
            if (zn.x2() < cx - r || zn.x1() > cx + r || zn.z2() < cz - r || zn.z1() > cz + r) {
                continue;   // 표본 영역 밖 — 대조할 일이 없다
            }
            boxes.add(zn);
        }
        return boxes;
    }

    /**
     * ⑥ <b>기둥별 표본 상한</b> — 지하는 <b>지면 밑</b>에서만 센다.
     *
     * <p>★ B-114 5차 — 액체를 가르고 판 굴을 가르고도 3.84% 가 남았다. 진범은 <b>하늘</b>이었다:
     * 구판은 중심 y 기준 고정 대역 [cy-45, cy-5] 를 훑어, 지면이 낮은 기둥(저지·수변, 지면 62~85 vs
     * cy≈90)에서 <b>열린 하늘의 공기를 지하 공동으로</b> 셌다. 산술이 정합한다:
     * 저지 ~80기둥 × ~19칸 ≈ 1,520 ≈ 실측 1,513 — 물 0 · 무변동 · 판굴 없음을 동시에 설명한다.
     *
     * <p>상한 = min(cy, 그 기둥의 지면) − margin. 지면은 이 파일의 기존 손 {@link #surfaceY} 로 찾는다
     * ({@code getHighestBlockYAt} 은 물을 땅으로, 옛 지붕을 지면으로 읽는다 — TerrainForge 가 두 번
     * 무너진 그 버그라 쓰지 않는다). margin 등록부: {@code config/terrain.yml audit.underground_surface_margin}.
     *
     * @param standY {@link #surfaceY} 의 답 (딛는 자리 = 지면 + 1). 못 찾으면 {@code MIN_VALUE}
     * @return 이 기둥에서 표본할 최고 y. 지면을 못 찾으면 {@code MIN_VALUE} — 그 기둥은 세지 않는다
     */
    static int columnTop(int cy, int standY) {
        if (standY == Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return Math.min(cy, standY - 1) - UNDERGROUND_MARGIN;
    }

    /** 표본 칸이 판 굴의 상자 안에 있는가 — 순수 함수 (하네스가 시험한다) */
    static boolean inCaveBox(List<Zone> boxes, int x, int y, int z) {
        for (Zone zn : boxes) {
            if (x >= zn.x1() && x <= zn.x2() && y >= zn.y1() && y <= zn.y2()
                    && z >= zn.z1() && z <= zn.z2()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 지하의 빈 곳 — <b>자연 동굴은 통제할 수 없다</b>는 판단(사용자)에 따라, 세계는 <b>동굴 없이</b> 생성되고
     * 동굴이 필요하면 <b>우리가 판다</b>. 그 약속이 지켜졌는지 재는 눈이다.
     *
     * <p><b>기둥마다 그 기둥의 지면</b> 아래 {@value #UNDERGROUND_MARGIN}칸부터(중심 y−45 까지) 표본한다.
     * 카버(동굴)가 파는 것은 <b>공기</b>다 — 그래서 판정은
     * <b>공기만</b>으로 한다. 물·용암은 생성기의 구조층(대수층·용암 바다)이라 <b>분리 계수해 보고만</b> 한다
     * ({@link #classifyDeep} · B-114). 자연 동굴이 살아 있으면 공기 비율이 5~15% 나온다.
     * 데이터팩(honcheon_no_caves)이 제대로 걸렸다면 <b>1% 미만</b>이어야 한다 —
     * 우리가 판 동굴이 있다면 그만큼은 정직하게 잡힌다(그건 위반이 아니라 설계다).
     *
     * <p>구판이 강가에서 물을 빼던 예외(수로채 "자연동굴 25%" — 물은 동굴이 아니다)는 이 일반화에
     * 흡수됐다: 이제 물은 <b>어느 지형에서도</b> 공동으로 세지 않는다. (① 바닥 밑 공동은 원래부터
     * 공기만 센다 — 그 축은 건드리지 않았다.)
     *
     * <p>그리고 <b>우리가 판 굴도 자연 동굴이 아니다</b> — 원장(terrain_built.yml)이 기억하는
     * 굴 상자 안의 공기는 「판 굴」로 분리 계수한다 ({@link #dugCaveBoxes} · B-114 후속).
     * 등록 <b>안 된</b> 공기는 여전히 짖는다 — 검출력은 그대로다.
     *
     * <p>그리고 <b>하늘도 지하가 아니다</b> — 기둥별 상한을 지면에 물린다 ({@link #columnTop} · B-114 5차).
     * 지면이 낮은 기둥의 열린 하늘을 고정 대역이 지하로 세던 병을 여기서 고쳤다.
     */
    private static void underground(List<String> out, List<String> violations,
                                    World world, int cx, int cy, int cz, int r, boolean dugCave) {
        out.add(HEAD + "⑥ 지하 — 자연 동굴이 남아 있는가"
                + (dugCave ? " (우리가 판 굴이 있는 지역 — 여유 8%)" : " (동굴은 우리가 판다)"));
        List<Zone> dug = dugCaveBoxes(world.getName(), cx, cz, r);
        long air = 0;
        long dugAir = 0;
        long waterCnt = 0;
        long lavaCnt = 0;
        long total = 0;
        int blindColumns = 0;   // 지면을 못 찾은 기둥 — 세지 않되, 침묵하지 않는다
        List<String> spots = new ArrayList<>();
        for (int x = cx - r; x <= cx + r; x += 4) {
            for (int z = cz - r; z <= cz + r; z += 4) {
                // ★ 기둥별 상한 — "지하"는 **그 기둥의 지면 밑**에서만 (하늘은 지하가 아니다)
                int top = columnTop(cy, surfaceY(world, x, z, cy));
                if (top == Integer.MIN_VALUE) {
                    blindColumns++;
                    continue;
                }
                for (int y = cy - 45; y <= top; y++) {   // top ≤ cy − margin (columnTop 이 보장한다)
                    if (y < world.getMinHeight() + 5) {
                        continue;
                    }
                    total++;
                    switch (classifyDeep(world.getBlockAt(x, y, z).getType())) {
                        case AIR -> {
                            if (inCaveBox(dug, x, y, z)) {
                                dugAir++;   // 원장이 아는 굴 — 우리 것이다
                            } else {
                                air++;      // 등록 안 된 공기 — 이것만 짖는다
                                if (spots.size() < 5) {
                                    spots.add("(" + x + "," + y + "," + z + ")");
                                }
                            }
                        }
                        case WATER -> waterCnt++;
                        case LAVA -> lavaCnt++;
                        case SOLID -> { }
                    }
                }
            }
        }
        double pct = total == 0 ? 0 : (double) air / total;
        double liquidPct = total == 0 ? 0 : (double) (waterCnt + lavaCnt) / total;
        out.add(INFO + "  지하 표본 " + total + "칸 (기둥별 지면−" + UNDERGROUND_MARGIN + " 상한"
                + (blindColumns > 0 ? " · 지면 못 찾은 기둥 " + blindColumns + " 제외" : "")
                + ") · 공동(공기) " + air + "칸 (" + String.format("%.2f%%", pct * 100) + ")");
        if (!spots.isEmpty()) {
            out.add(INFO + "    자리(등록 안 된 공기 — 다음 수사는 여기서): " + String.join(" ", spots));
        }
        out.add(INFO + "    액체(분리 계수 — 동굴이 아니라 구조층이다): 물 " + waterCnt + " · 용암 " + lavaCnt
                + " (" + String.format("%.2f%%", liquidPct * 100) + ") — 판정에 넣지 않는다");
        if (!dug.isEmpty()) {
            String names = dug.stream().map(Zone::name).reduce((a, b) -> a + " " + b).orElse("");
            out.add(INFO + "    판 굴(원장 " + dug.size() + "곳 — " + names + "): 공기 " + dugAir
                    + "칸 (" + String.format("%.2f%%", total == 0 ? 0 : dugAir * 100.0 / total)
                    + ") — 설계다, 판정에 넣지 않는다");
        }
        // **우리가 판 굴은 위반이 아니다.** 산적굴 1,006칸이 "자연동굴 4%"로 잡혔다 — 그 굴은 설계다.
        //   1차 방어는 위의 원장 대조(정확한 상자)다. 이 여유는 **원장이 굴을 모르는 세계**(원장 도입
        //   이전에 판 굴)를 위한 2차 방어로 남긴다 — 등록부가 굴을 요구한 지역에만 준다.
        double limit = dugCave ? UNDERGROUND_DUG_MAX : UNDERGROUND_VOID_MAX;
        if (pct > limit) {
            out.add(BAD + "  지하 공동 " + String.format("%.1f%%", pct * 100)
                    + " — 자연 동굴이 살아 있다 (데이터팩 honcheon_no_caves 미적용?)");
            violations.add("자연동굴" + String.format("%.0f%%", pct * 100));
        } else {
            out.add(OK + "  지하가 채워져 있다 (공기 " + String.format("%.2f%%", pct * 100)
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

    /**
     * 그 열의 지표면 y (물·잎·풀·나무는 지나친다 — 딛는 자리를 찾는다).
     *
     * <p>★ package-private — <b>눈과 손이 같은 자를 쓴다</b>: ⑥(지하 공동)이 이 판정으로 지면을 잡고,
     * 치유 스윕({@code /혼천 지하정리})도 <b>같은 판정</b>으로 지면을 잡는다. 자가 둘이면
     * 눈이 짖는 곳과 손이 걷는 곳이 어긋난다 (B-114 7차).
     */
    static int surfaceY(World world, int x, int z, int cy) {
        for (int y = cy + 40; y >= cy - 40; y--) {
            Material m = world.getBlockAt(x, y, z).getType();
            if (isGround(m)) {
                return y + 1;   // 그 위가 딛는 자리
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * <b>땅인가</b> — 지면 탐지가 딛는 자리로 인정하는 재질.
     *
     * <p>★ B-114 6차 — 3.86% 의 진범은 동굴이 아니라 <b>나무</b>였다. 잎만 지나치고 <b>통나무를
     * 땅으로</b> 읽어, 숲 나무의 꼭대기가 그 기둥의 지면이 됐다 — 수관 속 공기(가지 사이 2~4칸)가
     * 전부 「지하 공동」으로 계수됐다 (실측: 공동 천장이 #leaves · 벽이 #logs — RCON 재질 검증).
     * 분포도 정합한다: 숲(중간 반경)에 많고 벌목된 마을 중심에 적고, 물 0, 팩 수리와 무관하게 불변.
     * 그래서 나무의 몸(_LOG/_WOOD)과 잎을 <b>통째로</b> 지나친다 — 나무는 땅이 아니다.
     */
    private static boolean isGround(Material m) {
        return m.isSolid() && !isTreeish(m.name());   // isSolid 는 서버 전용 — 이름 판정만 순수 분리
    }

    /** 나무의 몸인가 — 순수 이름 판정 (하네스가 시험한다 · Material API 는 레지스트리 함정) */
    static boolean isTreeish(String n) {
        return n.endsWith("_LEAVES") || n.endsWith("_LOG") || n.endsWith("_WOOD")
                || n.endsWith("MUSHROOM_BLOCK") || n.equals("MUSHROOM_STEM") || n.equals("BAMBOO");
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

    // ★ auditTown(앵커 편의 진입점)은 지웠다 — 청하현 중심의 해석은 이제 **한 곳**에만 있다:
    //   MvtCommand.resolveTerrainTarget (환경검수와 지하정리가 같은 해석기를 쓴다 — 해석기가
    //   둘이라 손이 표적을 놓친 것이 2026-07-14 실사격 미발의 뿌리였다).
}
