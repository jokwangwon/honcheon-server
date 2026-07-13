package com.honcheon.mvt;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import com.honcheon.core.rules.RulesConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 강 — <b>지형 계층이 만드는 물</b>. ({@code config/rivers.yml} 이 주문서다)
 *
 * <p>지금까지 이 세계에 강은 <b>없었다</b>. 지도는 일곱 곳에 물을 주문했는데
 * ({@code terrain: 강} 둘 · {@code 수향} 셋 · {@code 물가} 둘) 지형 계층의 윤곽표는 이렇게 적고 있었다:
 *
 * <pre>   그대로 — 강·수향·섬·밀림. 물과 숲은 자연이 준 대로가 옳다</pre>
 *
 * <p>그래서 <b>장강수로채는 바닐라가 물을 놓아 줬기를 바라며 섰다.</b> 부지 판정이 요구하는
 * {@code water_pct: [40,90] · max_depth_min: 6} 은 <b>바다도 호수도 똑같이 만족한다</b> —
 * 흐르지 않는 물은 강이 아니다. 그리고 물이 아예 없으면 {@code RemoteBuilder.waterStockade} 는
 * {@code List.of()} 를 돌려준다 — <b>아무것도 서지 않는다.</b> 아무것도 서지 않으면 지역 검수는
 * <b>볼 구역이 없어서 위반 0건</b>을 보고한다. <b>짓지 않으면 위반이 없다</b> — 눈이 그렇게 거짓말했다.
 *
 * <h2>계층의 경계를 넘지 않는다</h2>
 * 여기는 <b>땅</b>이다. 강은 땅이 자연과 하는 협상이다 (계약 ③: 물 위에 지을 자리를 주지 않는다).
 * 나루·뗏목·사슬관문은 <b>집</b>이고, 그것은 {@code RemoteBuilder} 의 것이다 —
 * 이 클래스는 블록 하나도 건축하지 않는다. <b>물과 둔치까지가 우리 몫이다.</b>
 *
 * <h2>부르는 법 — {@code TerrainForge.prepare()} <u>다음</u>이다</h2>
 * <pre>
 *   TerrainForge.SiteSpec spec = TerrainForge.prepare(w, place, x, y, z, r);
 *   spec = RiverForge.carve(w, place, spec);     // ★ 이 한 줄. 강이 없으면 spec 을 그대로 돌려준다
 *   ... RemoteBuilder.build(w, place, spec, cave);
 * </pre>
 * <b>순서가 계약이다.</b> {@code prepare()} 의 {@code tidyWater} 는 '공중의 물·산 위의 웅덩이'를 치우는데,
 * 그 앞에서 강을 파면 <b>우리가 판 강을 그것이 지운다.</b> 그리고 {@code feather} 가 지나간 뒤라야
 * 골짜기가 <b>이미 이어진 지형</b>을 가른다.
 *
 * <h2>결정론</h2>
 * 난수가 없다. 굽이는 저주파 사인 둘이다 ({@link RiverPlan}). 같은 좌표 = 같은 강.
 */
final class RiverForge {

    private RiverForge() {
    }

    /** 자연 지면으로 치는 것 — {@code TerrainForge.SOLID_NATURAL} 과 같은 약속 (그것은 private 이다) */
    private static final Set<Material> SOLID_NATURAL = EnumSet.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT,
            Material.PODZOL, Material.MYCELIUM, Material.MUD, Material.MUDDY_MANGROVE_ROOTS,
            Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.CLAY,
            Material.STONE, Material.ANDESITE, Material.DIORITE, Material.GRANITE, Material.TUFF,
            Material.DEEPSLATE, Material.CALCITE, Material.SANDSTONE, Material.RED_SANDSTONE,
            Material.TERRACOTTA, Material.SNOW_BLOCK,
            Material.WHITE_TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.YELLOW_TERRACOTTA,
            Material.BROWN_TERRACOTTA, Material.LIGHT_GRAY_TERRACOTTA, Material.RED_TERRACOTTA);

    /** 젖은 것 — {@code TerrainForge.WET} 과 같은 약속 */
    private static final Set<Material> WET = EnumSet.of(
            Material.WATER, Material.LAVA, Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE,
            Material.FROSTED_ICE, Material.KELP, Material.KELP_PLANT, Material.SEAGRASS,
            Material.TALL_SEAGRASS, Material.BUBBLE_COLUMN);

    // ═══════════════════════════════════════════════════════════════════
    // 등록부 (config/rivers.yml)
    // ═══════════════════════════════════════════════════════════════════

    private static Map<String, RiverPlan.Spec> registry = new LinkedHashMap<>();

    /** 등록된 물길이 하나라도 있는가 — 없으면 이 계층은 잠자코 있는다 */
    static boolean loaded() {
        return !registry.isEmpty();
    }

    /** {@code config/rivers.yml} 판독. 없으면 강이 없다 (지금까지와 같다 — 세계는 그래도 돈다) */
    @SuppressWarnings("unchecked")
    static void load(Path configDir) {
        registry = new LinkedHashMap<>();
        Path file = configDir.resolve("rivers.yml");
        if (!Files.isRegularFile(file)) {
            return;
        }
        Map<String, Object> root = RulesConfig.load(file);
        Map<String, Object> def = root.get("defaults") instanceof Map
                ? (Map<String, Object>) root.get("defaults") : Map.of();
        if (!(root.get("rivers") instanceof Map<?, ?> rivers)) {
            return;
        }
        for (Map.Entry<?, ?> e : rivers.entrySet()) {
            if (!(e.getValue() instanceof Map)) {
                continue;
            }
            String id = String.valueOf(e.getKey());
            Map<String, Object> m = (Map<String, Object>) e.getValue();

            // flow: [들어오는 쪽, 나가는 쪽] — 물은 뒤엣것을 향해 흐른다
            int[] u = {1, 0};
            if (m.get("flow") instanceof List<?> flow && flow.size() >= 2) {
                u = bearing(String.valueOf(flow.get(1)));
            }
            registry.put(id, new RiverPlan.Spec(
                    id,
                    String.valueOf(m.getOrDefault("name", id)),
                    u[0], u[1],
                    Math.max(2, num(m, def, "width", 36) / 2),      // 반폭
                    num(m, def, "depth", 8),
                    Math.max(1, num(m, def, "gradient", 70)),
                    0,                                              // axis_offset 은 반경을 알아야 정해진다 (아래 plan())
                    num(m, def, "surface_below_ground", 2),
                    Math.max(1, num(m, def, "valley", 26)),
                    Math.max(0, num(m, def, "margin", 20)),
                    num(m, def, "meander_amp", 16),
                    Math.max(8, num(m, def, "meander_len", 170)),
                    str(m, def, "bank_material", "자갈")));
            // axis_offset 은 비율이라 별도 표에 담는다 (반경은 조성 때 정해진다)
            offsets.put(id, dbl(m, def, "axis_offset", 0.45));
        }
        // 하지(河誌) — 조성기가 판 강의 자리. 플러그인 자료방에 둔다 (config 가 아니다 — 이것은 기록이다)
        ledgerFile = configDir.getParent() == null
                ? null : configDir.getParent().resolve("rivers_built.yml");
        loadLedger();
    }

    /** 방위 이름 (로그용) */
    private static String faceName(int ux, int uz) {
        if (ux > 0) {
            return "동";
        }
        if (ux < 0) {
            return "서";
        }
        return uz > 0 ? "남" : "북";
    }

    private static final Map<String, Double> offsets = new LinkedHashMap<>();

    private static int num(Map<String, Object> m, Map<String, Object> def, String k, int fallback) {
        Object v = m.get(k) != null ? m.get(k) : def.get(k);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private static double dbl(Map<String, Object> m, Map<String, Object> def, String k, double fallback) {
        Object v = m.get(k) != null ? m.get(k) : def.get(k);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    private static String str(Map<String, Object> m, Map<String, Object> def, String k, String fallback) {
        Object v = m.get(k) != null ? m.get(k) : def.get(k);
        return v == null ? fallback : String.valueOf(v);
    }

    /** 방위 이름 → 단위벡터. 축은 지도 그대로다 (x = 동(+)/서(-), z = 남(+)/북(-)) */
    private static int[] bearing(String name) {
        return switch (name.trim()) {
            case "동" -> new int[]{1, 0};
            case "서" -> new int[]{-1, 0};
            case "남" -> new int[]{0, 1};
            case "북" -> new int[]{0, -1};
            default -> new int[]{1, 0};
        };
    }

    /** 이 장소에 등록된 물길이 있는가 */
    static boolean has(WorldMap.Place place) {
        return place != null && registry.containsKey(place.id());
    }

    /**
     * 조성에 쓸 물길 산술. 없으면 null.
     *
     * <p>{@code axis_offset} 은 <b>반경의 배수</b>로 등록된다 — 부지 반경은 조성 때 정해지므로
     * 여기서 비로소 칸으로 환산한다.
     */
    /** 등록부가 적은 그대로의 Spec (아직 땅에게 묻기 전) */
    private static RiverPlan.Spec registered(WorldMap.Place place, int radius) {
        RiverPlan.Spec s = place == null ? null : registry.get(place.id());
        if (s == null) {
            return null;
        }
        int off = (int) Math.round(offsets.getOrDefault(place.id(), 0.45) * radius);
        return new RiverPlan.Spec(s.placeId(), s.name(), s.ux(), s.uz(),
                s.halfWidth(), s.depth(), s.gradient(), off, s.surfaceBelowGround(),
                s.valley(), s.margin(), s.meanderAmp(), s.meanderLen(), s.bankMaterial());
    }

    // ═══════════════════════════════════════════════════════════════════
    // 하지(河誌) — ★ 조성기가 **어디를 팠는지 적어 둔다**
    // ═══════════════════════════════════════════════════════════════════
    //
    // 왜 필요한가 — 인게임 1차 조성이 가르쳐 준 것:
    //   검수는 **구역(Zone) 한가운데**를 부지 중심으로 알고 강을 찾았다. 그런데 수로채의 구역 중심은
    //   **뗏목**이다 (RemoteBuilder: rc = shore + 17) — 부지 중심에서 물 쪽으로 31칸 밀려 있다.
    //   검수는 그 밀린 중심에 다시 axis_offset(50칸)을 얹어 **엉뚱한 자리**를 팠다고 믿었고,
    //   거기엔 당연히 물이 없었다:
    //       ③ 물길 한가운데 7/261칸이 물이다   ← 강은 멀쩡히 흐르고 있었다. **눈이 딴 데를 봤다**
    //
    //   ★ 정본이 둘이었다. 조성기가 판 강과 검수가 상상한 강이 서로 달랐다.
    //   그래서 **조성기가 적는다. 검수는 그 기록을 읽는다.** 강이 어디 있는지는 판 사람이 안다.

    private static Path ledgerFile;
    private static final Map<String, int[]> dug = new LinkedHashMap<>();   // id → {x, z, radius, groundY, ux, uz, offset}

    /** 검수가 읽는다 — 조성기가 실제로 판 강. 없으면 null */
    static RiverPlan dugPlan(WorldMap.Place place) {
        RiverPlan.Spec base = place == null ? null : registry.get(place.id());
        int[] d = place == null ? null : dug.get(place.id());
        if (base == null || d == null) {
            return null;
        }
        RiverPlan.Spec actual = new RiverPlan.Spec(base.placeId(), base.name(), d[4], d[5],
                base.halfWidth(), base.depth(), base.gradient(), d[6], base.surfaceBelowGround(),
                base.valley(), base.margin(), base.meanderAmp(), base.meanderLen(), base.bankMaterial());
        return new RiverPlan(actual, d[0], d[1], d[2], d[3]);   // 기하만 — 검수는 **거기 있는 물을 잰다**
    }

    private static void remember(String id, RiverPlan river, int radius, int groundY) {
        RiverPlan.Spec s = river.spec();
        dug.put(id, new int[]{river.centerX(), river.centerZ(), radius, groundY,
                s.ux(), s.uz(), s.axisOffset()});
        if (ledgerFile == null) {
            return;
        }
        try {
            List<String> lines = new java.util.ArrayList<>();
            lines.add("# 하지(河誌) — 조성기가 판 강의 자리. **검수가 이것을 읽는다** (손으로 고치지 말 것)");
            for (Map.Entry<String, int[]> e : dug.entrySet()) {
                int[] v = e.getValue();
                lines.add(e.getKey() + ": [" + v[0] + ", " + v[1] + ", " + v[2] + ", " + v[3]
                        + ", " + v[4] + ", " + v[5] + ", " + v[6] + "]");
            }
            Files.write(ledgerFile, lines);
        } catch (java.io.IOException ignored) {
            // 적지 못해도 조성은 끝났다 — 다만 검수가 눈을 감을 뿐이다 (그것은 거짓말이 아니라 침묵이다)
        }
    }

    @SuppressWarnings("unchecked")
    private static void loadLedger() {
        dug.clear();
        if (ledgerFile == null || !Files.isRegularFile(ledgerFile)) {
            return;
        }
        Map<String, Object> root = RulesConfig.load(ledgerFile);
        for (Map.Entry<String, Object> e : root.entrySet()) {
            if (e.getValue() instanceof List<?> v && v.size() >= 7) {
                int[] a = new int[7];
                for (int i = 0; i < 7; i++) {
                    a[i] = ((Number) v.get(i)).intValue();
                }
                dug.put(e.getKey(), a);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 조성
    // ═══════════════════════════════════════════════════════════════════

    /** 왜 강을 파지 못했는가 — 조성기가 <b>말하게</b> 한다 (조용히 넘어가면 그게 거짓말이다) */
    static String lastRefusal;

    /**
     * <b>강을 판다.</b> 등록된 물길이 없으면 {@code spec} 을 그대로 돌려준다 (아무 일도 하지 않는다).
     *
     * <p>돌려주는 {@link TerrainForge.SiteSpec} 은 <b>새로 잰 것</b>이다 —
     * 물길이 지나간 열은 이제 '젖은 열'이고, 건축 마스크에서 빠져야 하고, 수변이 참이어야 한다.
     * 땅을 바꿔 놓고 옛 사양을 그대로 넘기면 <b>건축 계층이 강 위에 집을 짓는다.</b>
     *
     * @return 갱신된 부지 사양 (강을 못 팠으면 원래 것)
     */
    static TerrainForge.SiteSpec carve(World world, WorldMap.Place place, TerrainForge.SiteSpec spec) {
        lastRefusal = null;
        RiverPlan.Spec asRegistered = registered(place, spec.radius());
        if (asRegistered == null) {
            return spec;   // 등록되지 않은 곳 — 강을 발명하지 않는다
        }

        // ★ 땅에게 묻는다 — 어느 쪽이 내리막인가. 등록부는 지리를 알고, 땅은 중력을 안다.
        //   등록된 방향이 오르막이면 축을 뒤집는다 (물은 지리를 모른다).
        RiverPlan.Ground ground = (x, z) -> naturalGround(world, x, z, spec.groundY() + 80);
        RiverPlan.Spec asLandWants = RiverPlan.faceDownhill(
                asRegistered, spec.cx(), spec.cz(), spec.radius(), spec.groundY(), ground);
        if (asLandWants.ux() != asRegistered.ux() || asLandWants.uz() != asRegistered.uz()) {
            org.bukkit.Bukkit.getLogger().info("[지형/강] " + asLandWants.name()
                    + " — 등록된 방향이 오르막이다. 땅을 따라 축을 뒤집었다 ("
                    + faceName(asRegistered.ux(), asRegistered.uz()) + " → "
                    + faceName(asLandWants.ux(), asLandWants.uz()) + " 쪽으로 흐른다)");
        }
        RiverPlan river = new RiverPlan(asLandWants, spec.cx(), spec.cz(),
                spec.radius(), spec.groundY(), ground);   // ★ 땅이 수면을 정한다

        // ─── 거절 ① 이미 물이다 ────────────────────────────────────────────
        //   부지가 바다·큰 호수 한복판이면 강을 팔 수 없다. 우리 수면(지면-2)보다 높은 자연 수면이
        //   곁에 있으면, 우리가 판 골짜기로 **그 물이 쏟아진다** (강이 아니라 배수구가 된다).
        //   ★ 조용히 넘어가지 않는다 — 등록부(world_map.yml terrain_types)가 뭍을 요구하도록 고쳐야 한다.
        int intruder = highestNaturalWater(world, river, spec);
        if (intruder > river.waterY(river.length() / 2)) {
            lastRefusal = place.name() + " — 부지가 이미 물이다 (자연 수면 y" + intruder
                    + " > 계획 수면 y" + river.waterY(river.length() / 2) + "). 강을 팔 수 없다: "
                    + "판 골짜기로 그 물이 쏟아진다. world_map.yml terrain_types 가 **뭍**을 요구해야 한다";
            return spec;
        }

        Material bed = bedMaterial(river.spec().bankMaterial());
        int r = river.reachRadius();

        for (int x = spec.cx() - r; x <= spec.cx() + r; x++) {
            for (int z = spec.cz() - r; z <= spec.cz() + r; z++) {
                if (!river.inReach(x, z)) {
                    continue;
                }
                int natural = naturalGround(world, x, z, spec.groundY() + 80);
                if (river.inChannel(x, z)) {
                    channelColumn(world, river, x, z, natural, bed);
                } else {
                    bankColumn(world, river, x, z, natural, bed);
                }
            }
        }

        // ★ 판 자리를 **적는다** — 검수가 이것을 읽는다 (구역 중심으로 강을 찾으면 딴 데를 본다)
        remember(place.id(), river, spec.radius(), spec.groundY());

        // ─── 땅이 바뀌었으니 **다시 잰다** ────────────────────────────────────
        //   surface[]·buildable[]·waterfront 를 손으로 고치지 않는다 — 지형 계층의 자[尺]로 다시 잰다.
        //   정본은 하나여야 한다 (물 판정 규약을 두 벌 두면 언젠가 갈라진다).
        TerrainForge.SiteSpec fresh = TerrainForge.survey(
                world, place, spec.cx(), spec.groundY(), spec.cz(), spec.radius());

        // ★ 수변 방위만은 **우리가 안다.** TerrainForge.waterSides() 는 사분면으로 물을 세는데,
        //   강은 부지를 **관통하므로** 상·하류 두 방위에서도 물이 잡힌다 (동·남·서가 전부 수변이 된다).
        //   그 표는 호수·바다(한쪽에 있는 물)를 전제로 만들어졌다 — 강에게는 틀린 자다.
        //   RemoteBuilder.waterStockade 는 waterSides().get(0) 을 보고 **나루를 어느 쪽에 놓을지** 정한다.
        //   강이 아는 답은 하나다: **물은 흐름의 옆에 있다** (axis_offset 이 가리키는 쪽).
        BlockFace bankFace = bankFace(river);

        // 봉우리는 원래 사양의 것을 지킨다 (다시 재면 naturalTop 이 강가의 언덕을 정상으로 읽는다)
        return new TerrainForge.SiteSpec(
                fresh.placeId(), fresh.name(), fresh.terrain(), fresh.world(),
                fresh.cx(), fresh.cz(), fresh.radius(), fresh.groundY(),
                spec.peakX(), spec.peakZ(), spec.peakY(),
                fresh.surface(), fresh.buildable(), fresh.approaches(),
                true, List.of(bankFace),
                fresh.slope(), fresh.relief());
    }

    /** 물이 있는 쪽 — 흐름의 오른쪽({@code v = (-uz, ux)}) 또는 왼쪽 (axis_offset 의 부호) */
    static BlockFace bankFace(RiverPlan river) {
        int vx = -river.spec().uz();
        int vz = river.spec().ux();
        if (river.spec().axisOffset() < 0) {
            vx = -vx;
            vz = -vz;
        }
        if (vx > 0) {
            return BlockFace.EAST;
        }
        if (vx < 0) {
            return BlockFace.WEST;
        }
        return vz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    // ─── 물 ────────────────────────────────────────────────────────────

    /**
     * 물길 한 열 — 하상을 놓고, 물을 채우고, <b>하늘을 연다</b>.
     *
     * <p>물 위에 천장이 있으면 그것은 강이 아니라 <b>굴</b>이다. 그리고 하상 아래가 비면
     * 그것은 강이 아니라 <b>새는 통</b>이다 (계약 ①).
     */
    private static void channelColumn(World world, RiverPlan river, int x, int z,
                                      int natural, Material bed) {
        int s = river.downstream(x, z);
        double d = river.distanceFromCenterline(x, z);
        int waterY = river.waterY(s);
        int bedY = river.bedY(s, d);

        // 하상 — 물이 딛는 바닥
        world.getBlockAt(x, bedY, z).setType(bed);
        TerrainForge.sealBelow(world, x, bedY, z);   // 계약 ① — 새는 강은 강이 아니다

        // 물 — 원천 블록으로 채운다 (흐르는 물을 놓으면 마르거나 넘친다)
        for (int y = bedY + 1; y <= waterY; y++) {
            Block b = world.getBlockAt(x, y, z);
            if (b.getType() != Material.WATER) {
                b.setType(Material.WATER);
            }
        }

        // ─── 하늘 — 수면 위를 비운다 ───────────────────────────────────────
        //   ★ 구판은 여기를 **clearanceTop(수면+8)까지만** 걷었다. 평지에서는 그게 하늘이었다.
        //   그런데 실제 부지는 기복 32칸이었다 — 물길이 언덕을 지나는 자리에서는 지면이 수면보다
        //   20~30칸 높았고, 그 **덮개를 안 걷었다.** 강이 굴 속으로 들어간 것이다.
        //   **덮은 흙 전부를 걷는다** — 자연 지면까지. 강 위에 천장이 있으면 그건 강이 아니라 굴이다.
        int top = Math.max(natural == RiverPlan.WET ? waterY : natural, river.clearanceTop(s));
        for (int y = waterY + 1; y <= top; y++) {
            Block b = world.getBlockAt(x, y, z);
            if (!b.getType().isAir()) {
                b.setType(Material.AIR);
            }
        }
    }

    // ─── 둔치 ──────────────────────────────────────────────────────────

    /**
     * 둔치 한 열 — 물가에서 <b>자연 지형으로 수렴한다</b>.
     *
     * <p>이게 없으면 강가가 수직 절벽이 된다 (그건 강이 아니라 수로다). 그리고 주변 땅이 수면보다
     * 낮으면 <b>둑을 쌓는다</b> — 강은 스스로 둑을 쌓는다. 안 쌓으면 옆으로 샌다.
     *
     * <p><b>자연이 이미 물이면 손대지 않는다</b> — 거기가 이 강의 하구다.
     */
    private static void bankColumn(World world, RiverPlan river, int x, int z, int natural, Material bed) {
        int target = river.bankTargetY(x, z, natural);
        if (target == RiverPlan.WET) {
            return;   // 바닐라의 물 — 강이 여기서 그것을 만난다. 메우면 하구가 막힌다
        }
        int s = river.downstream(x, z);
        double d = river.distanceFromCenterline(x, z);
        int hw = river.halfWidthAt(s);

        // 깎는다 — 목표 위의 것은 걷어낸다 (물 위에 얹힌 언덕이 강가 절벽이 된다)
        int from = Math.max(natural == RiverPlan.WET ? target : natural, river.clearanceTop(s));
        for (int y = from; y > target; y--) {
            Block b = world.getBlockAt(x, y, z);
            if (!b.getType().isAir()) {
                b.setType(Material.AIR);
            }
        }
        // 메운다 — 목표 아래가 비었으면 채운다 (둑)
        for (int y = target; y > target - RiverPlan.SEAL_DEPTH; y--) {
            Block b = world.getBlockAt(x, y, z);
            if (b.getType().isAir() || b.isLiquid()) {
                b.setType(y == target ? Material.DIRT : Material.STONE);
            }
        }
        // 표층 — 물가 두 칸은 자갈·모래·진흙이다 (풀이 물에 잠기면 그건 물가가 아니다)
        Material top = d <= hw + 2 ? bed : Material.GRASS_BLOCK;
        Block t = world.getBlockAt(x, target, z);
        if (SOLID_NATURAL.contains(t.getType()) || t.getType() == Material.DIRT) {
            t.setType(top);
        }
        TerrainForge.sealBelow(world, x, target, z);   // 계약 ①
    }

    private static Material bedMaterial(String name) {
        return switch (name.trim()) {
            case "모래" -> Material.SAND;
            case "진흙" -> Material.MUD;
            default -> Material.GRAVEL;
        };
    }

    // ─── 땅 읽기 ───────────────────────────────────────────────────────

    /**
     * 그 열의 자연 지면 y. 물이면 {@link RiverPlan#WET}.
     *
     * <p>{@code TerrainForge.naturalGround} 와 <b>같은 규약</b>이다 (그것은 private 이다).
     * {@code getHighestBlockYAt} 을 쓰면 <b>물을 땅으로 읽는다</b> — 그 버그로 폐사당이 호수에 섰다.
     */
    private static int naturalGround(World world, int x, int z, int from) {
        int top = Math.min(from, world.getMaxHeight() - 1);
        int floor = Math.max(world.getMinHeight(), top - 120);
        for (int y = top; y >= floor; y--) {
            Material m = world.getBlockAt(x, y, z).getType();
            if (WET.contains(m)) {
                return RiverPlan.WET;
            }
            if (SOLID_NATURAL.contains(m)) {
                return y;
            }
        }
        return floor;
    }

    /**
     * 사정권 안 <b>자연 수면</b>의 최고 y — 바다·큰 호수가 우리 골짜기보다 높으면 강을 팔 수 없다.
     *
     * <p>표본만 뜬다 (4칸 격자) — 이 질문은 "물이 있냐"가 아니라 "<b>높은 물이 있냐</b>"다.
     */
    private static int highestNaturalWater(World world, RiverPlan river, TerrainForge.SiteSpec spec) {
        int r = river.reachRadius();
        int best = Integer.MIN_VALUE;
        for (int x = spec.cx() - r; x <= spec.cx() + r; x += 4) {
            for (int z = spec.cz() - r; z <= spec.cz() + r; z += 4) {
                if (!river.inReach(x, z)) {
                    continue;
                }
                for (int y = spec.groundY() + 8; y >= spec.groundY() - 24; y--) {
                    if (world.getBlockAt(x, y, z).getType() == Material.WATER) {
                        best = Math.max(best, y);
                        break;
                    }
                }
            }
        }
        return best;
    }
}
