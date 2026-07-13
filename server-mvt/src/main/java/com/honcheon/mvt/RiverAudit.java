package com.honcheon.mvt;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * 강의 눈 — <b>물이 높은 데서 낮은 데로 흐르는가.</b>
 *
 * <h2>이 눈이 한 번 헛봤다 — 그리고 그것이 이 눈을 고쳤다</h2>
 * 1차 인게임 조성에서 이 눈은 이렇게 말했다:
 * <pre>   ③ 물길 한가운데 <b>7/261</b>칸이 물이다 — 강이 끊겼다</pre>
 * <b>강은 멀쩡히 흐르고 있었다. 눈이 딴 데를 봤다.</b> 원인 둘:
 * <ol>
 *   <li><b>중심을 몰랐다</b> — 검수는 <b>구역(Zone) 한가운데</b>를 부지 중심으로 알았다. 그런데 수로채의
 *       구역 중심은 <b>뗏목</b>이다 ({@code RemoteBuilder: rc = shore + 17}) — 부지 중심에서 물 쪽으로
 *       31칸 밀려 있다. 거기에 다시 {@code axis_offset}(50칸)을 얹으니 <b>81칸 엉뚱한 자리</b>를
 *       팠다고 믿었다. 거기엔 당연히 물이 없었다.
 *       <br>→ 이제 <b>조성기가 적어 둔 하지(河誌)를 읽는다</b> ({@link RiverForge#dugPlan}).
 *       <b>강이 어디 있는지는 판 사람이 안다.</b></li>
 *   <li><b>계획을 믿었다</b> — {@code river.waterY(s)} 에 물이 있는지 물었다. 그건 <b>계획서를 보고
 *       계획서를 채점</b>하는 짓이다.
 *       <br>→ 이제 <b>거기 있는 물을 잰다.</b> 계획의 수면은 쳐다보지 않는다.</li>
 * </ol>
 * <b>검수는 조성기가 무엇을 하려 했는지 묻지 않는다. 무엇을 했는지 묻는다.</b>
 *
 * <h2>다섯 잣대</h2>
 * <ol>
 *   <li><b>있는가</b> — 등록된 물길인데 물이 없다면 그것이 가장 큰 위반이다</li>
 *   <li><b>흐르는가</b> — <b>실측</b> 수면이 하류로 갈수록 낮아지기만 하는가. 그리고 실제로 떨어지는가</li>
 *   <li><b>끊기지 않는가</b> — 상류 끝에서 하류 끝까지 물이 이어지는가</li>
 *   <li><b>새지 않는가</b> — 하상 아래가 단단한가. 물가가 수면보다 높은가</li>
 *   <li><b>배가 다니는가</b> — 물길 한가운데가 등록된 만큼 깊은가</li>
 * </ol>
 */
final class RiverAudit {

    private RiverAudit() {
    }

    private static final String OK = ChatColor.GREEN + "✓ ";
    private static final String BAD = ChatColor.RED + "✗ ";
    private static final String INFO = ChatColor.GRAY + "  ";
    private static final String HEAD = ChatColor.AQUA + "";

    /** 세계를 읽는 창 — 진짜 세계도, 시험용 가짜 세계도 이 창으로 들어온다 */
    interface Probe {

        boolean water(int x, int y, int z);

        /** 단단한가 (공기도 물도 아닌 것) */
        boolean solid(int x, int y, int z);
    }

    static Probe of(World world) {
        return new Probe() {
            @Override
            public boolean water(int x, int y, int z) {
                return world.getBlockAt(x, y, z).getType() == Material.WATER;
            }

            @Override
            public boolean solid(int x, int y, int z) {
                Material m = world.getBlockAt(x, y, z).getType();
                return !m.isAir() && m != Material.WATER;
            }
        };
    }

    // ═══════════════════════════════════════════════════════════════════
    // 부르는 법
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 이 장소의 강을 검수한다. 등록된 물길이 없으면 <b>빈 목록</b> (강 없는 곳에 강을 요구하지 않는다).
     *
     * <p><b>넘겨받은 중심을 쓰지 않는다</b> — {@link RiverForge} 가 적어 둔 <b>하지(河誌)</b> 를 읽는다.
     * 부르는 쪽이 아는 중심(구역 한가운데)은 <b>부지 중심이 아니다</b>. 그 착각이 1차 검수를 헛돌게 했다.
     */
    static List<String> audit(World world, WorldMap.Place place, int cx, int cz, int radius, int groundY) {
        if (!RiverForge.has(place)) {
            return List.of();   // 등록된 물길이 없는 곳 — 강을 요구하지 않는다
        }
        RiverPlan river = RiverForge.dugPlan(place);
        if (river == null) {
            // ★ 판 기록이 없다 — 이것도 위반이다. **짓지 않으면 위반이 없다**는 침묵을 여기서 깬다
            return List.of(
                    HEAD + "══ 강 검수 — " + place.name() + " ══",
                    BAD + "강을 판 기록이 없다 — 조성기가 이 물길을 파지 않았다 (하지가 비었다). "
                            + "물 없는 수로채는 그냥 산채다");
        }
        return check(river, of(world));
    }

    // ═══════════════════════════════════════════════════════════════════
    // 알맹이 — 세계를 모른다. Probe 만 본다 (그래야 눈을 시험한다)
    // ═══════════════════════════════════════════════════════════════════

    static List<String> check(RiverPlan river, Probe probe) {
        List<String> out = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        RiverPlan.Spec s = river.spec();

        out.add(HEAD + "══ 강 검수 — " + s.name() + " (물길 " + river.length()
                + "칸 · 폭 " + (s.halfWidth() * 2) + " · 수심 " + s.depth()
                + " · 흐름 " + face(s.ux(), s.uz()) + "쪽) ══");

        exists(out, violations, river, probe);
        flow(out, violations, river, probe);
        continuity(out, violations, river, probe);
        watertight(out, violations, river, probe);
        navigable(out, violations, river, probe);

        out.add(HEAD + "── 총평 ──");
        out.add(violations.isEmpty()
                ? OK + "위반 0건 — 강이 높은 데서 낮은 데로 흐른다"
                : BAD + "위반 " + violations.size() + "건: " + String.join(" / ", violations));
        return out;
    }

    /** 검수 결과에서 위반만 (시험이 읽는다) */
    static List<String> violations(RiverPlan river, Probe probe) {
        List<String> out = new ArrayList<>();
        for (String line : check(river, probe)) {
            String plain = ChatColor.stripColor(line);
            if (plain.startsWith("✗ ")) {
                out.add(plain.substring(2).trim());
            }
        }
        return out;
    }

    private static String face(int ux, int uz) {
        if (ux > 0) {
            return "동";
        }
        if (ux < 0) {
            return "서";
        }
        return uz > 0 ? "남" : "북";
    }

    // ─── ① 있는가 ───

    private static void exists(List<String> out, List<String> violations, RiverPlan river, Probe probe) {
        out.add(HEAD + "① 있는가 — 등록된 물길에 물이 있는가");
        int want = 0;
        int got = 0;
        for (int s = 0; s <= river.length(); s += 3) {
            int hw = river.halfWidthAt(s);
            int mid = (int) Math.round(river.centerline(s));
            for (int l = mid - hw - 2; l <= mid + hw + 2; l += 3) {
                int[] p = at(river, s, l);
                if (!river.inChannel(p[0], p[1])) {
                    continue;
                }
                want++;
                if (surfaceAt(probe, river, p[0], p[1]) != Integer.MIN_VALUE) {
                    got++;
                }
            }
        }
        int pct = want == 0 ? 0 : got * 100 / want;
        out.add(INFO + "  물길 칸 " + got + "/" + want + " (" + pct + "%)");
        if (want == 0) {
            out.add(BAD + "  물길이 비었다 — 산술이 물을 한 칸도 두지 않았다");
            violations.add("물길 없음");
        } else if (pct < 95) {
            out.add(BAD + "  강이 없다 — 등록된 물길에 물이 " + pct + "% 뿐이다. 물 없는 수로채는 그냥 산채다");
            violations.add("강 없음(" + pct + "%)");
        } else {
            out.add(OK + "  강이 있다");
        }
    }

    // ─── ② 흐르는가 — ★ 계획이 아니라 **실측** 수면을 잰다 ───

    private static void flow(List<String> out, List<String> violations, RiverPlan river, Probe probe) {
        out.add(HEAD + "② 흐르는가 — 수면이 하류로 갈수록 낮아지는가 (실측)");
        int prev = Integer.MAX_VALUE;
        int first = Integer.MIN_VALUE;
        int last = Integer.MIN_VALUE;
        int uphill = 0;
        int worstAt = -1;
        for (int s = 0; s <= river.length(); s += 2) {
            int[] p = thalweg(river, s);
            int top = surfaceAt(probe, river, p[0], p[1]);
            if (top == Integer.MIN_VALUE) {
                continue;   // 물이 없는 자리 — ①·③이 잡는다
            }
            if (first == Integer.MIN_VALUE) {
                first = top;
            }
            last = top;
            if (prev != Integer.MAX_VALUE && top > prev) {
                uphill++;
                if (worstAt < 0) {
                    worstAt = s;
                }
            }
            prev = top;
        }
        int fall = (first == Integer.MIN_VALUE) ? 0 : first - last;
        out.add(INFO + "  상류 수면 y" + first + " → 하류 수면 y" + last + " (낙차 " + fall + "칸)");
        if (uphill > 0) {
            out.add(BAD + "  물이 거꾸로 흐른다 — 수면이 하류에서 " + uphill
                    + "번 높아진다 (처음: 물길 " + worstAt + "칸 지점)");
            violations.add("역류(" + uphill + "곳)");
        } else if (fall < 1) {
            out.add(BAD + "  떨어지지 않는다 — 낙차 0칸. 이것은 강이 아니라 못이다");
            violations.add("낙차 없음");
        } else {
            out.add(OK + "  높은 데서 낮은 데로 흐른다 (" + fall + "칸 떨어진다)");
        }
    }

    // ─── ③ 끊기지 않는가 ───

    private static void continuity(List<String> out, List<String> violations, RiverPlan river, Probe probe) {
        out.add(HEAD + "③ 끊기지 않는가 — 상류 끝에서 하류 끝까지 물로 이어지는가");
        int gaps = 0;
        int firstGap = -1;
        for (int s = 0; s <= river.length(); s++) {
            int[] p = thalweg(river, s);
            if (surfaceAt(probe, river, p[0], p[1]) == Integer.MIN_VALUE) {
                gaps++;
                if (firstGap < 0) {
                    firstGap = s;
                }
            }
        }
        out.add(INFO + "  물길 한가운데 " + (river.length() + 1 - gaps) + "/"
                + (river.length() + 1) + "칸이 물이다");
        if (gaps > 0) {
            out.add(BAD + "  강이 끊겼다 — " + gaps + "칸이 물이 아니다 (처음: 물길 " + firstGap
                    + "칸 지점). 끊긴 강은 못 두 개다");
            violations.add("단절(" + gaps + "칸)");
        } else {
            out.add(OK + "  끝에서 끝까지 이어진다");
        }
    }

    // ─── ④ 새지 않는가 ───

    private static void watertight(List<String> out, List<String> violations, RiverPlan river, Probe probe) {
        out.add(HEAD + "④ 새지 않는가 — 하상 아래가 단단하고, 물가가 수면보다 높은가");
        int leakBed = 0;
        int leakBank = 0;
        for (int s = 0; s <= river.length(); s += 2) {
            int[] p = thalweg(river, s);
            int top = surfaceAt(probe, river, p[0], p[1]);
            if (top == Integer.MIN_VALUE) {
                continue;   // 물이 없다 — ③이 잡는다
            }
            // 물기둥을 따라 내려가 **하상**을 찾는다 (계획이 아니라 실측)
            int floor = river.groundY() - RiverPlan.MAX_CUT - 12;
            int y = top;
            while (y > floor && probe.water(p[0], y, p[1])) {
                y--;
            }
            // 물 밑 첫 칸부터 여섯 칸 — 한 칸이라도 비면 그것은 **새는 강**이다 (계약 ①)
            boolean holed = false;
            for (int i = 0; i < RiverPlan.SEAL_DEPTH; i++) {
                if (!probe.solid(p[0], y - i, p[1])) {
                    holed = true;
                    break;
                }
            }
            if (holed) {
                leakBed++;
            }
            // 물가 — 수면 높이에서 단단하거나(둑) 물이어야(하구) 한다. 공기면 옆으로 샌다
            int hw = river.halfWidthAt(s);
            int mid = (int) Math.round(river.centerline(s));
            for (int side = -1; side <= 1; side += 2) {
                int[] b = at(river, s, mid + side * (hw + 2));
                if (!probe.solid(b[0], top, b[1]) && !probe.water(b[0], top, b[1])) {
                    leakBank++;
                }
            }
        }
        out.add(INFO + "  하상 봉인 구멍 " + leakBed + " · 물가 구멍 " + leakBank);
        if (leakBed > 0) {
            out.add(BAD + "  강바닥이 샌다 — 하상 아래 여섯 칸이 " + leakBed + "곳에서 비었다 (계약 ①)");
            violations.add("하상 누수(" + leakBed + "곳)");
        }
        if (leakBank > 0) {
            out.add(BAD + "  물가가 낮다 — " + leakBank + "곳에서 수면 높이가 뚫려 있다. 강이 옆으로 샌다");
            violations.add("물가 누수(" + leakBank + "곳)");
        }
        if (leakBed == 0 && leakBank == 0) {
            out.add(OK + "  새지 않는다");
        }
    }

    // ─── ⑤ 배가 다니는가 ───

    private static void navigable(List<String> out, List<String> violations, RiverPlan river, Probe probe) {
        int need = Math.max(4, river.spec().depth() * 2 / 3);
        out.add(HEAD + "⑤ 배가 다니는가 — 물길 한가운데가 " + need + "칸 이상 깊은가");
        int shallow = 0;
        int total = 0;
        int min = Integer.MAX_VALUE;
        for (int s = 0; s <= river.length(); s += 2) {
            int[] p = thalweg(river, s);
            int top = surfaceAt(probe, river, p[0], p[1]);
            int d = 0;
            if (top != Integer.MIN_VALUE) {
                for (int y = top; y > top - 24 && probe.water(p[0], y, p[1]); y--) {
                    d++;
                }
            }
            total++;
            min = Math.min(min, d);
            if (d < need) {
                shallow++;
            }
        }
        int pct = total == 0 ? 0 : shallow * 100 / total;
        out.add(INFO + "  얕은 자리 " + shallow + "/" + total + " (" + pct + "%) · 최소 수심 "
                + (min == Integer.MAX_VALUE ? 0 : min) + "칸");
        if (pct > 10) {
            out.add(BAD + "  배가 못 다닌다 — 물길의 " + pct + "%가 " + need
                    + "칸보다 얕다. 뗏목이 말뚝을 박을 수 없다");
            violations.add("수심 부족(" + pct + "%)");
        } else {
            out.add(OK + "  배가 다닌다");
        }
    }

    // ─── 물길 좌표계 → 세계 좌표 ───

    private static int[] at(RiverPlan river, int s, int l) {
        RiverPlan.Spec sp = river.spec();
        int along = s - river.length() / 2;
        return new int[]{
                river.centerX() + sp.ux() * along + (-sp.uz()) * l,
                river.centerZ() + sp.uz() * along + sp.ux() * l};
    }

    private static int[] thalweg(RiverPlan river, int s) {
        return at(river, s, (int) Math.round(river.centerline(s)));
    }

    /**
     * 그 열의 <b>실측</b> 수면 y — <b>거기 있는 물</b>을 잰다.
     *
     * <p>★ 계획의 {@code waterY(s)} 언저리만 뒤지지 않는다. 그러면 계획이 틀렸을 때
     * <b>틀린 자리를 보고 "물이 없다"</b>고 말하게 된다 — 그것이 1차 검수의 병이었다.
     * 조성 지면을 기준으로 <b>팔 수 있는 범위 전부</b>를 훑는다.
     */
    private static int surfaceAt(Probe probe, RiverPlan river, int x, int z) {
        int hi = river.groundY() + 10;
        int lo = river.groundY() - RiverPlan.MAX_CUT - 12;
        for (int y = hi; y >= lo; y--) {
            if (probe.water(x, y, z)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }
}
