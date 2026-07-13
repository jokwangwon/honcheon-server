package com.honcheon.mvt;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * 강의 눈 — <b>물이 높은 데서 낮은 데로 흐르는가.</b>
 *
 * <p>이 세계의 눈은 지금까지 <b>열일곱 번 거짓말했다.</b> 그리고 강에 대해서는 열여덟 번째를 준비하고 있었다:
 * {@code RemoteBuilder.waterStockade} 는 물이 없으면 {@code List.of()} 를 돌려주고 —
 * <b>아무것도 서지 않으면 볼 구역이 없어서 지역 검수가 "위반 0건"을 보고한다.</b>
 * <b>짓지 않으면 위반이 없다.</b> 침묵이 성공으로 읽혔다.
 *
 * <p>그래서 이 눈은 <b>없는 것을 본다</b>: 검수 ①은 "강이 있어야 할 자리에 강이 있는가"를 묻는다.
 * 나머지 넷은 있는 강이 <b>참인가</b>를 묻는다.
 *
 * <h2>다섯 가지 잣대 ({@link RiverPlan} 이 정의한 '강의 참')</h2>
 * <ol>
 *   <li><b>있는가</b> — 등록된 물길인데 물이 없다면 그것이 가장 큰 위반이다</li>
 *   <li><b>흐르는가</b> — 수면이 하류로 갈수록 <b>낮아지기만</b> 하는가. 그리고 실제로 떨어지는가</li>
 *   <li><b>끊기지 않는가</b> — 상류 끝에서 하류 끝까지 물이 이어지는가</li>
 *   <li><b>새지 않는가</b> — 하상 아래가 단단한가. 물가가 수면보다 높은가. 공중에 물이 없는가</li>
 *   <li><b>배가 다니는가</b> — 물길 한가운데가 등록된 만큼 깊은가 (수로채의 뗏목이 말뚝을 박는다)</li>
 * </ol>
 *
 * <h2>왜 {@link Probe} 인가 — <b>눈을 시험할 수 있어야 한다</b></h2>
 * 검사의 알맹이는 {@link #check} 하나이고, 그것은 {@code World} 가 아니라 {@link Probe} 를 본다.
 * 그래서 <b>서버 없이</b> 가짜 세계를 지어 놓고 눈을 시험할 수 있다 —
 * 일부러 하상을 거꾸로 놓고, 물을 한 칸 지우고, 봉인을 뚫고서 <b>눈이 정말 잡는지</b> 확인할 수 있다.
 * 시험하지 않은 눈은 <b>눈이 아니라 장식</b>이다.
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

        /** 물인가 */
        boolean water(int x, int y, int z);

        /** 단단한가 (공기도 물도 아닌 것) */
        boolean solid(int x, int y, int z);
    }

    /** 진짜 세계를 읽는 창 */
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
     * 이 장소의 강을 검수한다. 등록된 물길이 없으면 <b>빈 목록</b> (강이 없는 곳에 강을 요구하지 않는다).
     *
     * @param groundY 조성 기준 지면 — 원장이 안다 ({@code plugin.regionBase}). 이걸 틀리면 눈이 헛본다
     */
    static List<String> audit(World world, WorldMap.Place place, int cx, int cz, int radius, int groundY) {
        RiverPlan river = RiverForge.plan(place, cx, cz, radius, groundY);
        if (river == null) {
            return List.of();
        }
        return check(river, of(world));
    }

    // ═══════════════════════════════════════════════════════════════════
    // 알맹이 — 세계를 모른다. Probe 만 본다
    // ═══════════════════════════════════════════════════════════════════

    /** 검수의 알맹이. <b>가짜 세계로도 부를 수 있다</b> — 그래야 눈을 시험한다 */
    static List<String> check(RiverPlan river, Probe probe) {
        List<String> out = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        RiverPlan.Spec s = river.spec();

        out.add(HEAD + "══ 강 검수 — " + s.name() + " (물길 " + river.length()
                + "칸 · 폭 " + (s.halfWidth() * 2) + " · 수심 " + s.depth() + ") ══");

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

    // ─── ① 있는가 — 짓지 않으면 위반이 없다, 그 거짓말을 여기서 끊는다 ───

    private static void exists(List<String> out, List<String> violations, RiverPlan river, Probe probe) {
        out.add(HEAD + "① 있는가 — 등록된 물길에 물이 있는가");
        int want = 0;
        int got = 0;
        for (int s = 0; s <= river.length(); s += 3) {
            for (int l = -river.halfWidthAt(s) * 2; l <= river.halfWidthAt(s) * 2; l += 3) {
                int[] p = world(river, s, l);
                if (!river.inChannel(p[0], p[1])) {
                    continue;
                }
                want++;
                if (probe.water(p[0], river.waterY(s), p[1])) {
                    got++;
                }
            }
        }
        int pct = want == 0 ? 0 : got * 100 / want;
        out.add(INFO + "  물길 칸 " + got + "/" + want + " (" + pct + "%)");
        if (want == 0) {
            out.add(BAD + "  물길이 비었다 — 산술이 물을 한 칸도 두지 않았다 (폭·수심을 보라)");
            violations.add("물길 없음");
        } else if (pct < 95) {
            out.add(BAD + "  강이 없다 — 등록된 물길에 물이 " + pct + "% 뿐이다. "
                    + "물 없는 수로채는 그냥 산채다");
            violations.add("강 없음(" + pct + "%)");
        } else {
            out.add(OK + "  강이 있다");
        }
    }

    // ─── ② 흐르는가 — 수면은 하류로 갈수록 낮아지기만 한다 ───

    private static void flow(List<String> out, List<String> violations, RiverPlan river, Probe probe) {
        out.add(HEAD + "② 흐르는가 — 수면이 하류로 갈수록 낮아지는가");
        int prev = Integer.MAX_VALUE;
        int first = Integer.MIN_VALUE;
        int last = Integer.MIN_VALUE;
        int uphill = 0;
        int worstAt = -1;
        for (int s = 0; s <= river.length(); s += 2) {
            int[] p = thalweg(river, s);
            int top = surfaceAt(probe, river, p[0], p[1], s);
            if (top == Integer.MIN_VALUE) {
                continue;   // 물이 없는 자리 — 그건 ①·③이 잡는다
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
            if (!probe.water(p[0], river.waterY(s), p[1])) {
                gaps++;
                if (firstGap < 0) {
                    firstGap = s;
                }
            }
        }
        out.add(INFO + "  물길 한가운데 " + (river.length() + 1 - gaps) + "/" + (river.length() + 1) + "칸이 물이다");
        if (gaps > 0) {
            out.add(BAD + "  강이 끊겼다 — " + gaps + "칸이 물이 아니다 (처음: 물길 " + firstGap + "칸 지점). "
                    + "끊긴 강은 못 두 개다");
            violations.add("단절(" + gaps + "칸)");
        } else {
            out.add(OK + "  끝에서 끝까지 이어진다");
        }
    }

    // ─── ④ 새지 않는가 — 계약 ①(딛는 땅)이 강바닥에도 적용된다 ───

    private static void watertight(List<String> out, List<String> violations, RiverPlan river, Probe probe) {
        out.add(HEAD + "④ 새지 않는가 — 하상 아래가 단단하고, 물가가 수면보다 높은가");
        int leakBed = 0;
        int leakBank = 0;
        int floating = 0;
        for (int s = 0; s <= river.length(); s += 2) {
            int[] p = thalweg(river, s);
            int waterY = river.waterY(s);

            // 하상 아래 — 계약 ①. 새는 강은 강이 아니다
            int bedY = river.bedY(s, 0);
            for (int i = 1; i <= RiverPlan.SEAL_DEPTH; i++) {
                if (!probe.solid(p[0], bedY - i, p[1])) {
                    leakBed++;
                    break;
                }
            }
            // 공중의 물 — 수면 위에 물이 있으면 그것은 넘친 것이다
            if (probe.water(p[0], waterY + 1, p[1])) {
                floating++;
            }
            // 물가 — 수면 높이에서 단단해야 한다. 안 그러면 강이 옆으로 샌다
            int hw = river.halfWidthAt(s);
            for (int side = -1; side <= 1; side += 2) {
                int l = (int) Math.round(river.centerline(s)) + side * (hw + 2);
                int[] b = world(river, s, l);
                if (!probe.solid(b[0], waterY, b[1]) && !probe.water(b[0], waterY, b[1])) {
                    leakBank++;
                }
            }
        }
        out.add(INFO + "  하상 봉인 구멍 " + leakBed + " · 물가 구멍 " + leakBank
                + " · 공중의 물 " + floating);
        if (leakBed > 0) {
            out.add(BAD + "  강바닥이 샌다 — 하상 아래 여섯 칸이 " + leakBed + "곳에서 비었다 (계약 ①)");
            violations.add("하상 누수(" + leakBed + "곳)");
        }
        if (leakBank > 0) {
            out.add(BAD + "  물가가 낮다 — " + leakBank + "곳에서 수면 높이가 뚫려 있다. 강이 옆으로 샌다");
            violations.add("물가 누수(" + leakBank + "곳)");
        }
        if (floating > 0) {
            out.add(BAD + "  공중의 물 — " + floating + "곳에서 수면 위에 물이 있다");
            violations.add("공중의 물(" + floating + "곳)");
        }
        if (leakBed == 0 && leakBank == 0 && floating == 0) {
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
            int waterY = river.waterY(s);
            int d = 0;
            for (int y = waterY; y > waterY - 24; y--) {
                if (!probe.water(p[0], y, p[1])) {
                    break;
                }
                d++;
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
            out.add(BAD + "  배가 못 다닌다 — 물길의 " + pct + "%가 " + need + "칸보다 얕다. "
                    + "뗏목이 말뚝을 박을 수 없다");
            violations.add("수심 부족(" + pct + "%)");
        } else {
            out.add(OK + "  배가 다닌다");
        }
    }

    // ─── 물길 좌표계 → 세계 좌표 ───

    /** 물길 좌표 (s, l) → 세계 (x, z).  s = 하류 거리 · l = 축에서의 측방 거리 */
    private static int[] world(RiverPlan river, int s, int l) {
        RiverPlan.Spec sp = river.spec();
        int along = s - river.length() / 2;
        int vx = -sp.uz();
        int vz = sp.ux();
        return new int[]{
                river.centerX() + sp.ux() * along + vx * l,
                river.centerZ() + sp.uz() * along + vz * l};
    }

    /** 물길 <b>한가운데</b>(thalweg) 의 세계 좌표 — 가장 깊은 선 */
    private static int[] thalweg(RiverPlan river, int s) {
        return world(river, s, (int) Math.round(river.centerline(s)));
    }

    /** 그 열의 <b>실제</b> 수면 y — 세계를 읽는다 (산술이 아니라 <b>거기 있는 물</b>을 잰다) */
    private static int surfaceAt(Probe probe, RiverPlan river, int x, int z, int s) {
        for (int y = river.waterY(s) + 4; y >= river.waterY(s) - 24; y--) {
            if (probe.water(x, y, z)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }
}
