package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 요청(要請) — <b>건축이 땅에게 말을 거는 유일한 문.</b>
 *
 * <p>사용자 판정:
 * <blockquote>
 * "건축에서 '이 부분은 <b>마을 내 소형 개울</b>이 있으면 좋겠다' 싶으면 <b>지형을 조금 수정하는 형태로</b>
 *  건축이 되어야 합니다." · "<b>요청이 개울 이거 하나가 아닙니다</b> — 마을에 특수한 무언가가 있으면 좋겠다 등
 *  <b>건축 디자인의 요청대로 변할 순 있어야</b> 합니다."
 * </blockquote>
 *
 * <h2>그러므로 이것은 「개울 기능」이 아니라 <b>어휘(語彙)</b>다</h2>
 * 요청을 하나씩 코딩하면 끝이 없다 (개울·온천·절벽·협곡·계단밭·바위턱…).
 * <b>원시(primitive)를 찾아야 한다.</b> 저 요청들을 관통하는 것은 셋뿐이다:
 *
 * <pre>
 *   <b>무엇을 한다</b>(연산) ×  <b>어디에</b>(모양) ×  <b>어느 높이로</b>(앵커)
 * </pre>
 *
 * <table>
 *   <tr><th>연산 5</th><td>고르기(평탄) · 깎기(파낸다) · 쌓기(돋운다) · 물(채운다) · 표층(자재만 간다)</td></tr>
 *   <tr><th>모양 3</th><td>원 · 상자 · 길(선을 따라)</td></tr>
 *   <tr><th>앵커</th><td>{@code ground} · {@code peak} · 정수 (± 오프셋)</td></tr>
 * </table>
 *
 * 이것으로 전부 적힌다 —
 * <b>"본전 뒤 절벽"</b> = {@code 깎기 · 상자 · peak-16} ·
 * <b>"연무장은 평평해야"</b> = {@code 고르기 · 원 · peak} ·
 * <b>"마을 개울"</b> = {@code 물 · 길 · ground-1} ·
 * <b>"온천이 솟는다"</b> = {@code 물 · 원 · ground-1} ·
 * <b>"산문 앞 협곡"</b> = {@code 깎기 · 길 · ground-9} ·
 * <b>"약초원 계단식 밭"</b> = {@code 고르기 · 상자} 를 y 를 달리해 세 번.
 *
 * <p><b>새 요청을 더할 때 자바를 고쳐야 한다면 그 문법이 잘못 설계된 것이다.</b>
 * 그래서 어휘는 {@code config/land_requests.yml} 에만 산다.
 *
 * <h2>문은 좁다 (어휘가 넓을수록 문이 좁아야 한다)</h2>
 * <ol>
 *   <li><b>건축은 직접 땅을 파지 않는다.</b> 등록부에 <b>요청을 적고</b>, <b>땅이 집행한다</b></li>
 *   <li>집행은 {@code prepare()} 다음, 건축 <b>앞</b>이다 — 그래서 <b>지형 원장에 적히고</b>,
 *       건물을 다시 지어도 <b>이미 거기 있으므로 아무것도 안 바뀐다</b></li>
 *   <li><b>경계가 있다</b> — 부지 안 · 크기·깊이·개수 상한. 넘으면 <b>거절하고 말한다</b>
 *       ({@code RiverForge} 가 "부지가 이미 물이다"라고 거절한 그 전례)</li>
 *   <li><b>결정론</b> — 좌표는 등록부가 준다. 난수가 없다. 같은 마을 = 같은 개울·같은 절벽</li>
 * </ol>
 */
final class LandRequest {

    private LandRequest() {
    }

    /** 연산 — <b>이 다섯이 전부다</b> */
    enum Op {
        고르기,   // LEVEL   — 목표 y 로 고른다 (깎고·채우고·봉인). 연무장·마당·계단밭
        깎기,     // CARVE   — 목표 y 위를 걷어낸다 (채우지 않는다). 절벽·협곡
        쌓기,     // RAISE   — 목표 y 까지 돋운다 (깎지 않는다). 기단·바위턱·둔덕
        물,       // FLOOD   — 파고 물을 채우고 봉인한다. 개울·못·온천
        표층      // SURFACE — 표층 자재만 간다. 밭·자갈마당·진흙
    }

    /** 모양 — 이 셋으로 어디든 가리킨다 */
    enum Shape {
        원, 상자, 길
    }

    /**
     * 한 요청.
     *
     * @param at    부지 중심 기준 (x, z). <b>세계 좌표가 아니다</b> — 부지가 옮겨 앉아도 요청은 따라간다
     * @param path  {@link Shape#길} 일 때의 꺾은선 (부지 중심 기준)
     * @param y     높이 식 ({@code ground} · {@code peak} · 정수, ± 오프셋)
     * @param depth {@link Op#물} 의 수심
     */
    record Req(String name, Op op, Shape shape, int[] at, int r, int[] half,
               List<int[]> path, int width, String y, int depth, String material) {
    }

    // ═══════════════════════════════════════════════════════════════════
    // 등록부
    // ═══════════════════════════════════════════════════════════════════

    private static final Map<String, List<Req>> registry = new LinkedHashMap<>();
    private static Map<String, Object> limits = Map.of();

    /** 왜 요청을 거절했는가 — <b>조용히 넘어가지 않는다</b> */
    static final List<String> refusals = new ArrayList<>();

    @SuppressWarnings("unchecked")
    static void load(Path configDir) {
        registry.clear();
        limits = Map.of();
        Path file = configDir.resolve("land_requests.yml");
        if (!Files.isRegularFile(file)) {
            return;
        }
        Map<String, Object> root = RulesConfig.load(file);
        if (root.get("limits") instanceof Map<?, ?> lm) {
            limits = (Map<String, Object>) lm;
        }
        if (!(root.get("requests") instanceof Map<?, ?> reqs)) {
            return;
        }
        for (Map.Entry<?, ?> e : reqs.entrySet()) {
            if (!(e.getValue() instanceof List<?> list)) {
                continue;
            }
            List<Req> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Req r = parse((Map<String, Object>) m);
                    if (r != null) {
                        out.add(r);
                    }
                }
            }
            registry.put(String.valueOf(e.getKey()), out);
        }
    }

    @SuppressWarnings("unchecked")
    private static Req parse(Map<String, Object> m) {
        Op op;
        Shape shape;
        try {
            op = Op.valueOf(String.valueOf(m.get("op")).trim());
            shape = Shape.valueOf(String.valueOf(m.get("shape")).trim());
        } catch (IllegalArgumentException | NullPointerException ex) {
            refusals.add("모르는 연산·모양: " + m.get("op") + "/" + m.get("shape")
                    + " — 어휘는 다섯 연산(고르기·깎기·쌓기·물·표층) × 세 모양(원·상자·길)뿐이다");
            return null;
        }
        List<int[]> path = new ArrayList<>();
        if (m.get("path") instanceof List<?> pl) {
            for (Object p : pl) {
                if (p instanceof List<?> pair && pair.size() >= 2) {
                    path.add(new int[]{((Number) pair.get(0)).intValue(), ((Number) pair.get(1)).intValue()});
                }
            }
        }
        return new Req(String.valueOf(m.getOrDefault("name", "(이름 없는 요청)")), op, shape,
                pair(m.get("at")), num(m, "r", 0), pair(m.get("half")),
                path, num(m, "width", 3),
                m.get("y") == null ? "ground" : String.valueOf(m.get("y")),
                num(m, "depth", 2),
                m.get("material") == null ? null : String.valueOf(m.get("material")));
    }

    private static int[] pair(Object o) {
        if (o instanceof List<?> l && l.size() >= 2) {
            return new int[]{((Number) l.get(0)).intValue(), ((Number) l.get(1)).intValue()};
        }
        return new int[]{0, 0};
    }

    private static int num(Map<String, Object> m, String k, int fallback) {
        return m.get(k) instanceof Number n ? n.intValue() : fallback;
    }

    private static int limit(String k, int fallback) {
        return limits.get(k) instanceof Number n ? n.intValue() : fallback;
    }

    static List<Req> of(String placeId) {
        return registry.getOrDefault(placeId, List.of());
    }

    // ═══════════════════════════════════════════════════════════════════
    // 집행 — <b>땅이 한다</b>
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 등록된 요청을 집행한다. <b>{@code prepare()} 다음, 건축 앞</b>이다.
     *
     * <p>넘으면 <b>거절하고 말한다</b> — 조용히 잘라 넣지 않는다 (그러면 등록부가 거짓이 된다).
     *
     * @return 집행된 요청 수
     */
    static int apply(World world, TerrainForge.SiteSpec spec) {
        refusals.clear();
        List<Req> reqs = of(spec.placeId());
        if (reqs.isEmpty()) {
            return 0;
        }
        int maxCount = limit("max_requests", 12);
        if (reqs.size() > maxCount) {
            refusals.add(spec.name() + " — 요청이 " + reqs.size() + "개다 (상한 " + maxCount
                    + "). 땅은 건축의 도화지가 아니다");
            return 0;
        }
        int done = 0;
        for (Req r : reqs) {
            String no = check(r, spec);
            if (no != null) {
                refusals.add(spec.name() + " / " + r.name() + " — " + no);
                continue;
            }
            execute(world, spec, r);
            done++;
        }
        return done;
    }

    /** <b>경계</b> — 넘으면 거절한다. 요청은 부지 안이고, 산을 가르지 않는다 */
    private static String check(Req r, TerrainForge.SiteSpec spec) {
        int maxR = limit("max_radius", 16);
        int maxW = limit("max_width", 6);
        int maxDepth = limit("max_depth", 12);
        int maxLift = limit("max_lift", 10);
        int maxLen = limit("max_path", 120);

        if (r.op() == Op.물 && r.depth() > maxDepth) {
            return "수심 " + r.depth() + " (상한 " + maxDepth + ") — 마을의 개울이지 강이 아니다. "
                    + "강이 필요하면 rivers.yml 에 등록하라";
        }
        if (r.shape() == Shape.원 && r.r() > maxR) {
            return "반경 " + r.r() + " (상한 " + maxR + ")";
        }
        if (r.shape() == Shape.상자 && (r.half()[0] > maxR || r.half()[1] > maxR)) {
            return "크기 " + r.half()[0] + "x" + r.half()[1] + " (상한 " + maxR + ")";
        }
        if (r.shape() == Shape.길) {
            if (r.width() > maxW) {
                return "폭 " + r.width() + " (상한 " + maxW + ")";
            }
            int len = 0;
            for (int i = 1; i < r.path().size(); i++) {
                len += Math.abs(r.path().get(i)[0] - r.path().get(i - 1)[0])
                        + Math.abs(r.path().get(i)[1] - r.path().get(i - 1)[1]);
            }
            if (len > maxLen) {
                return "길이 " + len + " (상한 " + maxLen + ") — 마을 안이지 산을 가르는 것이 아니다";
            }
            if (r.path().size() < 2) {
                return "길인데 꺾은선(path)이 없다";
            }
        }
        // ★ 부지 안인가 — 요청은 부지 밖으로 나가지 않는다 (전이대를 부수면 마을이 섬이 된다)
        int reach = r.shape() == Shape.원 ? r.r()
                : r.shape() == Shape.상자 ? Math.max(r.half()[0], r.half()[1]) : r.width();
        for (int[] p : points(r)) {
            if (Math.hypot(p[0], p[1]) + reach > spec.radius() - 8) {
                return "부지 밖으로 나간다 (" + p[0] + "," + p[1] + ") — 요청은 부지 안이다";
            }
        }
        int y = anchor(r.y(), spec);
        if (r.op() == Op.물 && y > spec.groundY() + 3) {
            // ★ TerrainAudit ②가 이것을 '산 위의 웅덩이'로 센다. 검수를 깨는 요청은 거절한다
            return "수면 y" + y + " 이 조성 지면 +3 보다 높다 — 환경 검수 ②가 '산 위의 웅덩이'로 센다";
        }
        if (r.op() == Op.쌓기 && y - spec.groundY() > maxLift) {
            return "돋움 " + (y - spec.groundY()) + "칸 (상한 " + maxLift + ") — 산을 세우려면 terrain.yml 이다";
        }
        return null;
    }

    /** 요청이 가리키는 자리들 (부지 중심 기준) */
    private static List<int[]> points(Req r) {
        if (r.shape() == Shape.길) {
            return r.path();
        }
        return List.of(r.at());
    }

    /** 높이 식 — {@code ground} · {@code peak} · 정수, ± 오프셋 */
    static int anchor(String expr, TerrainForge.SiteSpec spec) {
        String s = expr.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        int sign = 0;
        int off = 0;
        int cut = Math.max(s.lastIndexOf('+'), s.lastIndexOf('-'));
        if (cut > 0) {
            sign = s.charAt(cut) == '+' ? 1 : -1;
            try {
                off = Integer.parseInt(s.substring(cut + 1));
            } catch (NumberFormatException ignored) {
                off = 0;
            }
            s = s.substring(0, cut);
        }
        int base = switch (s) {
            case "peak", "정상" -> spec.peakY();
            case "ground", "지면", "" -> spec.groundY();
            default -> {
                try {
                    yield Integer.parseInt(s);
                } catch (NumberFormatException ignored) {
                    yield spec.groundY();
                }
            }
        };
        return base + sign * off;
    }

    // ─── 집행 ───

    private static void execute(World world, TerrainForge.SiteSpec spec, Req r) {
        int y = anchor(r.y(), spec);
        Material mat = material(r.material());
        for (int[] c : cells(r, spec)) {
            int x = spec.cx() + c[0];
            int z = spec.cz() + c[1];
            switch (r.op()) {
                case 고르기 -> level(world, x, y, z, mat);
                case 깎기 -> carve(world, x, y, z);
                case 쌓기 -> raise(world, x, y, z, mat);
                case 물 -> flood(world, x, y, z, r.depth());
                case 표층 -> surface(world, x, y, z, mat);
            }
        }
    }

    /** 요청이 덮는 칸 (부지 중심 기준). <b>결정론</b> — 좌표의 순수 함수다 */
    private static List<int[]> cells(Req r, TerrainForge.SiteSpec spec) {
        List<int[]> out = new ArrayList<>();
        switch (r.shape()) {
            case 원 -> {
                for (int dx = -r.r(); dx <= r.r(); dx++) {
                    for (int dz = -r.r(); dz <= r.r(); dz++) {
                        if (Math.hypot(dx, dz) <= r.r()) {
                            out.add(new int[]{r.at()[0] + dx, r.at()[1] + dz});
                        }
                    }
                }
            }
            case 상자 -> {
                for (int dx = -r.half()[0]; dx <= r.half()[0]; dx++) {
                    for (int dz = -r.half()[1]; dz <= r.half()[1]; dz++) {
                        out.add(new int[]{r.at()[0] + dx, r.at()[1] + dz});
                    }
                }
            }
            case 길 -> {
                int hw = Math.max(0, r.width() / 2);
                for (int i = 1; i < r.path().size(); i++) {
                    int[] a = r.path().get(i - 1);
                    int[] b = r.path().get(i);
                    int steps = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]));
                    for (int t = 0; t <= steps; t++) {
                        int px = a[0] + (b[0] - a[0]) * t / Math.max(1, steps);
                        int pz = a[1] + (b[1] - a[1]) * t / Math.max(1, steps);
                        for (int dx = -hw; dx <= hw; dx++) {
                            for (int dz = -hw; dz <= hw; dz++) {
                                if (Math.hypot(dx, dz) <= hw + 0.35) {
                                    out.add(new int[]{px + dx, pz + dz});
                                }
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    // ─── 원시 다섯 ───

    private static void level(World world, int x, int y, int z, Material mat) {
        clearAbove(world, x, y, z);
        fillTo(world, x, y, z, mat == null ? Material.DIRT : mat);
        TerrainForge.sealBelow(world, x, y, z);
    }

    private static void carve(World world, int x, int y, int z) {
        clearAbove(world, x, y, z);   // 채우지 않는다 — 벼랑은 벼랑이다
    }

    private static void raise(World world, int x, int y, int z, Material mat) {
        fillTo(world, x, y, z, mat == null ? Material.STONE : mat);
        TerrainForge.sealBelow(world, x, y, z);
    }

    /** 물 — 파고·채우고·봉인한다. <b>새는 개울은 개울이 아니다</b> */
    private static void flood(World world, int x, int y, int z, int depth) {
        int bed = y - Math.max(1, depth);
        clearAbove(world, x, y, z);
        world.getBlockAt(x, bed, z).setType(Material.GRAVEL);
        TerrainForge.sealBelow(world, x, bed, z);
        for (int i = bed + 1; i <= y; i++) {
            Block b = world.getBlockAt(x, i, z);
            if (b.getType() != Material.WATER) {
                b.setType(Material.WATER);
            }
        }
    }

    private static void surface(World world, int x, int y, int z, Material mat) {
        if (mat == null) {
            return;
        }
        for (int i = y + 3; i >= y - 3; i--) {
            Block b = world.getBlockAt(x, i, z);
            if (!b.getType().isAir() && b.getType() != Material.WATER) {
                b.setType(mat);
                return;
            }
        }
    }

    private static void clearAbove(World world, int x, int y, int z) {
        for (int i = y + 1; i <= y + 40; i++) {
            Block b = world.getBlockAt(x, i, z);
            if (!b.getType().isAir()) {
                b.setType(Material.AIR);
            }
        }
    }

    private static void fillTo(World world, int x, int y, int z, Material mat) {
        for (int i = y; i > y - 20; i--) {
            Block b = world.getBlockAt(x, i, z);
            if (b.getType().isAir() || b.isLiquid()) {
                b.setType(i == y ? mat : Material.STONE);
            } else if (i <= y - 3) {
                break;
            }
        }
        world.getBlockAt(x, y, z).setType(mat);
    }

    /** 등록부의 자재 이름 → 블록. <b>모르면 안 바꾼다</b> (코드가 자재를 지어내지 않는다) */
    private static Material material(String name) {
        if (name == null) {
            return null;
        }
        return switch (name.trim()) {
            case "다진흙" -> Material.PACKED_MUD;
            case "자갈" -> Material.GRAVEL;
            case "흙" -> Material.DIRT;
            case "거친흙" -> Material.COARSE_DIRT;
            case "진흙" -> Material.MUD;
            case "잔디" -> Material.GRASS_BLOCK;
            case "돌" -> Material.STONE;
            case "석재" -> Material.STONE_BRICKS;
            case "모래" -> Material.SAND;
            case "밭흙" -> Material.FARMLAND;
            case "포석" -> Material.DIRT_PATH;
            default -> null;
        };
    }
}
