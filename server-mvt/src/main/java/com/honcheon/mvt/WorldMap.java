package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Biome;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 세계 지도 — {@code config/world_map.yml} 의 판독기.
 *
 * <p>이 클래스가 푸는 문제는 셋이다.
 * <ol>
 *   <li><b>좌표</b> — 등록된 지역·본산·산채·사냥터가 <b>제 자리</b>에 선다. 조성한 자리가 아니라
 *       <b>등록된 자리</b>다 (기존 {@code /혼천 조성}은 친 자리에 마을을 세웠다 — 물가에 서면 마을이 물가로 이사했다).</li>
 *   <li><b>지형 적합성</b> — 그 좌표의 <b>실제 지형</b>이 요구를 만족하는가. 흑수나루는 물가여야 하고
 *       청하현은 평지여야 한다. 안 맞으면 <b>결정론적으로</b> 인근을 뒤진다
 *       ({@link CheonghaBuilder} 의 {@code SHRINE_SITES} 방식 계승 — 난수 없음).</li>
 *   <li><b>시드 탐색</b> — 후보 시드를 여러 개 실측해 <b>등록 좌표들의 지형이 가장 잘 맞는 시드</b>를 찾는다.
 *       완벽히 맞출 수는 없으므로 <b>점수</b>로 보고한다.</li>
 * </ol>
 *
 * <p><b>결정론</b>: 좌표·부지 선정에 난수가 없다. 유일한 입력 난수는 <b>월드 시드</b>이고,
 * 시드는 입력이지 난수가 아니다 — 같은 시드면 언제나 같은 세계다.
 *
 * <p><b>축척</b>: 1블록 = 1미터. 실지리 1:1. 하루 도보 = 40,000블록.
 * 원거리는 걷지 않는다 — 여정(오프스크린)이 건넌다. 그래서 좌표가 ±194만이어도 게임은 죽지 않는다.
 */
final class WorldMap {

    /** 지형 적합 판정선 — 이 점수 이상이면 "그 자리에 지어도 된다" */
    static final int PASS_SCORE = 70;

    private final Map<String, Object> root;
    private final int originX;
    private final int originZ;
    private final int sampleRadius;
    private final int sampleStep;
    private final int blocksPerDay;
    private final int forgeRadius;
    private final int preloadMargin;
    private final int probeMargin;
    private final Map<String, Place> places = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> terrainTypes = new LinkedHashMap<>();
    private final List<int[]> bearings = new ArrayList<>();
    private final List<Integer> ringSteps = new ArrayList<>();

    // ─── 지면 판정용 블록 집합 (CheonghaBuilder.naturalGroundY 규약 계승) ───

    /** 젖은 열 — 이 블록을 만나면 그 열은 '물'이다 */
    private static final Set<Material> WET = Set.of(
            Material.WATER, Material.LAVA, Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE,
            Material.FROSTED_ICE, Material.KELP, Material.KELP_PLANT, Material.SEAGRASS,
            Material.TALL_SEAGRASS, Material.BUBBLE_COLUMN);

    /** 자연 지면 — 여기 처음 닿는 y 가 그 열의 지면이다 */
    private static final Set<Material> NATURAL_GROUND = Set.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT,
            Material.PODZOL, Material.MYCELIUM, Material.MUD, Material.MUDDY_MANGROVE_ROOTS,
            Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.CLAY,
            Material.STONE, Material.ANDESITE, Material.DIORITE, Material.GRANITE, Material.TUFF,
            Material.DEEPSLATE, Material.CALCITE, Material.SANDSTONE, Material.RED_SANDSTONE,
            Material.TERRACOTTA, Material.SNOW_BLOCK, Material.POWDER_SNOW,
            Material.WHITE_TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.YELLOW_TERRACOTTA,
            Material.BROWN_TERRACOTTA, Material.LIGHT_GRAY_TERRACOTTA, Material.RED_TERRACOTTA);

    // ═══════════════════════════════════════════════════════════════════
    // 등록 장소
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 등록된 한 장소. {@code pos} 는 <b>청하현 원점 기준 오프셋</b>이고, origin 이 (0,0)이므로
     * 사실상 월드 좌표와 같다 (world_map.yml scale.origin_world).
     *
     * @param stageLocal true = 청하현 무대(1:1 도보권) 안의 좌표. CheonghaBuilder 가 이미 짓는다
     * @param archetype   <b>★ 무엇을 짓는가 — 지도가 말한다</b> (world_map.yml §16). {@code "pending"} 이면
     *                    <b>사람이 아직 안 정했다</b>. null 이면 지도가 침묵한 것이다.
     *                    <b>코드는 이것을 추측하지 않는다</b> — 예전엔 {@code faction+section} 으로 골라냈고,
     *                    그래서 소림·무당·곤륜·해남이 전부 <b>화산의 도관</b>으로 섰다 (매화 20장까지 똑같이).
     * @param buildRadius <b>★ 부지 반경 — 지도가 말한다</b> (world_map.yml §17 influence:
     *                    {@code martial + mandate_weight} 의 함수다). null 이면 미등록·pending.
     *                    <b>전성기 값이고 변하지 않는다</b> — 땅은 한 번만 서기 때문이다 (TerrainLedger).
     *                    예전엔 {@code noklim ? 24 : 64} 였다 — <b>코드가 마을 크기를 정하고 있었다</b>.
     * @param pendingWhy  pending 인 필드의 사유. <b>침묵과 미결은 다르다</b> — 조성이 거절할 때 이 말을 그대로 전한다
     * @param note        {@code architecture} <b>산문(散文)</b>. ★ <b>기계는 이것을 읽지 않는다</b> —
     *                    사람과 봇이 읽는다. (한때 TerrainForge 가 여기서 '동굴'·'산문' 낱말을 찾았다.
     *                    글을 고치면 굴이 사라졌다 — 그것은 등록부가 아니다)
     * @param hidden      <b>★ 표시 축 (B-151)</b> — {@code hidden: true} 또는 {@code player_map: false} 면
     *                    <b>플레이어 지도에 없다</b> (§5.8: 금기는 '존재를 모른다'가 설계다).
     *                    ★ 세 축은 다른 것이다: {@code build}(조성 수명주기) · {@code hidden/player_map}(표시) ·
     *                    {@code access}(해금) — <b>{@code build: never} 는 비밀 표기가 아니다.</b>
     *                    독자: {@code MvtCommand.showMap}(지도 렌더 차단) · {@code MvtCommand.travel}(목록 차단)
     */
    record Place(String id, String name, int x, int z, String terrain, List<String> biomes,
                 String build, boolean stageLocal, int days, String section,
                 String faction, String tier, String note,
                 String archetype, Integer buildRadius, String buildRadiusMark, String pendingWhy,
                 boolean hidden) {

        boolean buildableNow() {
            return "now".equals(build);
        }

        /**
         * 반경이 <b>수가 아닌 말</b>로 적혀 있는가 — {@code "unresolved"} · {@code "pending"} · null(침묵).
         *
         * <p><b>★ 셋은 다른 것이다</b>: {@code pending} 은 "알고 있고 아직 못 정했다"이고,
         * {@code unresolved} 는 <b>"근거가 없어서 모른다 — 그리고 모른다고 적었다"</b>이며,
         * null 은 <b>침묵</b>이다. 조성이 거절할 때 <b>셋을 갈라서 말한다</b>.
         */
        boolean radiusUnresolved() {
            return "unresolved".equals(buildRadiusMark);
        }

        /** 지도가 "아직 못 정했다"고 적었는가 — <b>침묵(null)이 아니라 미결이다</b> */
        boolean archetypePending() {
            return "pending".equals(archetype);
        }
    }

    /** 지형 실측 결과 — 점수와 그 근거 */
    record Fit(int score, int waterPct, int relief, int meanY, int maxDepth, int biomeHitPct, String verdict) {

        boolean pass() {
            return score >= PASS_SCORE;
        }
    }

    /** 부지 결정 — 등록 좌표에서 얼마나 밀렸는가까지 돌려준다 */
    record Site(int x, int z, int groundY, int shift, Fit fit) {
    }

    // ═══════════════════════════════════════════════════════════════════
    // 로드
    // ═══════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private WorldMap(Map<String, Object> root) {
        this.root = root;
        Map<String, Object> scale = RulesConfig.section(root, "scale");
        List<Number> origin = (List<Number>) scale.get("origin_world");
        this.originX = origin.get(0).intValue();
        this.originZ = origin.get(1).intValue();

        Map<String, Object> travel = RulesConfig.section(root, "travel");
        this.blocksPerDay = ((Number) travel.get("blocks_per_walking_day")).intValue();

        Map<String, Object> sampling = RulesConfig.section(root, "terrain_sampling");
        this.sampleRadius = ((Number) sampling.get("sample_radius")).intValue();
        this.sampleStep = ((Number) sampling.get("sample_step")).intValue();

        // ★★★ 땅의 반경 — <b>어디나 같은 값</b> (world_map.yml §1-b land).
        //   예전엔 MvtCommand 가 `noklim ? 24 : 110` 이라 적었다 — **땅이 세력을 알고 있었다.**
        //   사용자: *"땅은 세력을 모른다. 산은 문파가 흥하든 망하든 그 자리에 그만큼 있다."*
        Map<String, Object> land = RulesConfig.section(root, "land");
        this.forgeRadius = mustBeNumber(land, "forge_radius");
        this.preloadMargin = mustBeNumber(land, "preload_margin");
        this.probeMargin = mustBeNumber(land, "probe_margin");

        for (Map.Entry<String, Object> e : RulesConfig.section(root, "terrain_types").entrySet()) {
            terrainTypes.put(e.getKey(), (Map<String, Object>) e.getValue());
        }

        Map<String, Object> ring = RulesConfig.section(root, "search_ring");
        for (Object b : (List<Object>) ring.get("bearings")) {
            List<Number> pair = (List<Number>) b;
            bearings.add(new int[]{pair.get(0).intValue(), pair.get(1).intValue()});
        }
        for (Object s : (List<Object>) ring.get("steps")) {
            ringSteps.add(((Number) s).intValue());
        }

        // 장소는 네 섹션에 흩어져 있다 — 문법이 같으므로 한 표로 합친다
        readSection("places", "places");
        readSection("places_official", "관");
        readSection("hunting_grounds", "사냥터");
        readSection("ruins", "폐허");
        readSection("resources", "산지");
    }

    @SuppressWarnings("unchecked")
    private void readSection(String key, String label) {
        Object raw = root.get(key);
        if (!(raw instanceof Map)) {
            return;
        }
        for (Map.Entry<String, Object> e : ((Map<String, Object>) raw).entrySet()) {
            if (!(e.getValue() instanceof Map)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) e.getValue();
            Object pos = m.get("pos");
            if (!(pos instanceof List) || ((List<?>) pos).size() < 2) {
                continue;   // 망(網)·무거점 — 개방·하오문·살막은 좌표를 갖지 않는다. 그것이 설계다
            }
            List<Number> p = (List<Number>) pos;
            Object travel = m.get("travel");
            int days = 0;
            if (travel instanceof Map && ((Map<String, Object>) travel).get("days") instanceof Number n) {
                days = n.intValue();
            }
            List<String> biomes = m.get("biomes") instanceof List
                    ? (List<String>) m.get("biomes") : List.of();
            places.put(e.getKey(), new Place(
                    e.getKey(),
                    String.valueOf(m.getOrDefault("name", e.getKey())),
                    p.get(0).intValue(), p.get(1).intValue(),
                    m.get("terrain") == null ? null : String.valueOf(m.get("terrain")),
                    biomes,
                    String.valueOf(m.getOrDefault("build", "later")),
                    Boolean.TRUE.equals(m.get("stage_local")),
                    days, label,
                    // 등급(tier)이 살림의 두께를 정한다. ★ 세력은 더 이상 **원형을 고르지 않는다** —
                    //   원형은 아래 archetype 이 말한다 (§16). 세력은 소속일 뿐이다.
                    m.get("faction") == null ? null : String.valueOf(m.get("faction")),
                    String.valueOf(m.getOrDefault("tier", "poor")),
                    m.get("architecture") == null ? "" : String.valueOf(m.get("architecture")),
                    // ★★ 등록제의 접합 — **지도가 무엇을·얼마나 크게 짓는지 말한다** (§16 wiring W-A·W-B)
                    m.get("archetype") == null ? null : String.valueOf(m.get("archetype")).trim(),
                    radius(m.get("build_radius")),
                    radiusMark(m.get("build_radius")),
                    m.get("pending_why") == null ? null : String.valueOf(m.get("pending_why")),
                    // ★ B-151 — 표시 축. hidden: true / player_map: false 둘 다 같은 말이다
                    //   (§5.8 의 문법이 둘을 나란히 적으므로 어느 쪽이든 숨긴다).
                    //   ★ 불리언만 읽는다 — "true" 같은 문자열은 숨기지 못한다 (map_lint [표시타입] 이 짖는다)
                    Boolean.TRUE.equals(m.get("hidden")) || Boolean.FALSE.equals(m.get("player_map"))));
        }
    }

    /**
     * {@code build_radius} 판독 — <b>숫자만 받는다</b>.
     *
     * <p>{@code pending} 은 <b>수가 아니다.</b> "아직 못 정했다"는 말이고, 그러면 <b>null 이 옳다</b> —
     * 여기서 아무 수나 넣으면 그 순간 <b>코드가 마을 크기를 정한 것</b>이 되고, 그것이 이 배선이 고치는 병이다.
     * 조성은 {@link RemoteBuilder#unbuildableReasons} 에서 <b>거절하고 그렇게 말한다</b>.
     */
    private static Integer radius(Object raw) {
        return raw instanceof Number n ? n.intValue() : null;
    }

    /**
     * 수가 아닌 반경의 <b>이름</b> — {@code "unresolved"} · {@code "pending"} · null(침묵).
     *
     * <p>★★★ <b>여기서 아무 수도 넣지 않는다.</b> 사용자 (2026-07-13):
     * <i>"{@code commercial_class} 가 없으면 outpost / 잘못되면 radius 40 — <b>이 구현을 금지한다.</b>
     * <b>모르는 장소를 작은 장소로 간주하는 것도 하나의 창작이기 때문이다.</b>"</i>
     */
    private static String radiusMark(Object raw) {
        return raw instanceof Number || raw == null ? null : String.valueOf(raw).trim();
    }

    /**
     * 등록부의 수를 <b>받아 적는다 — 없으면 지어내지 않고 죽는다</b>.
     *
     * <p>여기서 조용히 기본값을 넣으면 그 순간 <b>코드가 다시 땅의 크기를 정한 것</b>이고,
     * 그것이 이 배선이 고친 병이다. 등록부가 말하지 않으면 <b>서버가 뜨지 않는 것이 옳다</b>.
     */
    private static int mustBeNumber(Map<String, Object> section, String key) {
        Object v = section.get(key);
        if (!(v instanceof Number n)) {
            throw new IllegalArgumentException(
                    "world_map.yml land." + key + " 가 없다(또는 수가 아니다) — "
                    + "★ 코드는 땅의 크기를 지어내지 않는다. 등록부에 적어라 (§1-b land)");
        }
        return n.intValue();
    }

    // ═══════════════════════════════════════════════════════════════════
    // 땅의 반경 — ★★ **어디나 같은 값.** 세력을 묻지 않는다 (§1-b land)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 지형 계층이 빚는 반경. <b>★ 어디나 같다</b> — 도적의 산이라고 작게 서지 않는다.
     *
     * <p><b>예전엔 {@code noklim ? 24 : 110} 이었다</b> — 세력이 땅의 크기를 정했다.
     * 사용자: <i>"땅은 세력을 모른다. 산은 문파가 흥하든 망하든 그 자리에 그만큼 있다."</i>
     *
     * <p><b>2계층 계약</b>: 집({@code build_radius})은 이 반경 <b>안에</b> 있어야 한다.
     * 지금 최대 집이 80 이고 땅이 110 이다 — {@code map_lint.two_layers} 가 그 여유를 매번 잰다.
     */
    int forgeRadius() {
        return forgeRadius;
    }

    /** 조성 직전 청크 선적 여유 — <b>땅의 크기가 아니라 스케줄러의 수</b>다 (§1-b land.preload_margin) */
    int preloadMargin() {
        return preloadMargin;
    }

    /** 부지 탐색 단계의 선적 여유 (§1-b land.probe_margin). ★ 여기에도 세력이 박혀 있었다 — 지웠다 */
    int probeMargin() {
        return probeMargin;
    }

    /** {@code config/world_map.yml} 판독. 파일이 없으면 null (세계 지도 없이도 MVT 는 돈다) */
    static WorldMap load(Path configDir) {
        Path file = configDir.resolve("world_map.yml");
        if (!java.nio.file.Files.isRegularFile(file)) {
            return null;
        }
        return new WorldMap(RulesConfig.load(file));
    }

    // ═══════════════════════════════════════════════════════════════════
    // 좌표
    // ═══════════════════════════════════════════════════════════════════

    /** 등록된 지역 전부 — 출행 명령이 목록을 편다 */
    Map<String, Place> places() {
        return java.util.Collections.unmodifiableMap(places);
    }

    Place place(String id) {
        return places.get(id);
    }

    /** 전 등록 장소 (좌표 있는 것만 — 망·무거점 제외) */
    List<Place> all() {
        return List.copyOf(places.values());
    }

    /** 청하현 무대 밖의 원거리 장소 — 여정의 목적지들 */
    List<Place> remote() {
        return places.values().stream().filter(p -> !p.stageLocal() && !"cheongha_hyeon".equals(p.id())).toList();
    }

    int worldX(Place p) {
        return originX + p.x();
    }

    int worldZ(Place p) {
        return originZ + p.z();
    }

    /** 청하현에서의 직선 거리(블록) */
    int distance(Place p) {
        return (int) Math.hypot(p.x(), p.z());
    }

    /**
     * 여정 일수. 등록값이 있으면 그것을 쓴다 (world_map.yml 이 원천이다).
     * 없으면 직선거리 ÷ 하루 도보로 하한을 준다.
     */
    int travelDays(Place p) {
        return p.days() > 0 ? p.days() : Math.max(1, Math.round((float) distance(p) / blocksPerDay));
    }

    int blocksPerDay() {
        return blocksPerDay;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 지형 실측 — 그 좌표가 요구 지형을 만족하는가
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 지면 판정 — 최상단에서 내려가며 <b>자연 지면</b>에 처음 닿는 y.
     * 물·용암을 만나면 {@link Integer#MIN_VALUE} (그 열은 '젖었다').
     *
     * <p>{@code getHighestBlockYAt} 은 <b>물도 최상단 블록으로 센다</b> — 그것만 쓰면 호수를 땅으로 읽는다.
     * (CheonghaBuilder v6.3 이 실제로 그 버그로 폐사당을 물에 지었다.)
     */
    private static int naturalGroundY(World world, int x, int z) {
        int top = Math.min(world.getHighestBlockYAt(x, z), world.getMaxHeight() - 1);
        int floor = Math.max(world.getMinHeight(), top - 64);
        for (int y = top; y >= floor; y--) {
            Material m = world.getBlockAt(x, y, z).getType();
            if (WET.contains(m)) {
                return Integer.MIN_VALUE;
            }
            if (NATURAL_GROUND.contains(m)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** 그 열의 수심 — 최상단이 물이 아니면 0 */
    private static int waterDepth(World world, int x, int z) {
        int top = Math.min(world.getHighestBlockYAt(x, z), world.getMaxHeight() - 1);
        if (world.getBlockAt(x, top, z).getType() != Material.WATER) {
            return 0;
        }
        int d = 0;
        for (int y = top; y > world.getMinHeight() && y >= top - 48; y--) {
            Material m = world.getBlockAt(x, y, z).getType();
            if (m == Material.WATER || m == Material.KELP || m == Material.KELP_PLANT
                    || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS) {
                d++;
            } else {
                break;
            }
        }
        return d;
    }

    /**
     * 지형 실측 — 중심 (cx, cz) 반경 sampleRadius 를 sampleStep 격자로 훑는다.
     * 점수는 0~100. 요구 지형의 조건마다 어긋난 만큼 깎는다 — <b>이분법이 아니라 점수다</b>
     * (완벽한 자리는 없다. 어느 자리가 <i>덜 나쁜가</i>를 재는 것이 이 함수의 일이다).
     */
    Fit fit(World world, int cx, int cz, String terrain, List<String> preferBiomes) {
        int total = 0;
        int wet = 0;
        int maxDepth = 0;
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        long sumY = 0;
        int dry = 0;
        int biomeHit = 0;
        int edgeSum = 0;
        int edgeCount = 0;
        int centerY = 0;
        int centerCount = 0;

        for (int dx = -sampleRadius; dx <= sampleRadius; dx += sampleStep) {
            for (int dz = -sampleRadius; dz <= sampleRadius; dz += sampleStep) {
                int x = cx + dx;
                int z = cz + dz;
                total++;
                int g = naturalGroundY(world, x, z);
                if (g == Integer.MIN_VALUE) {
                    wet++;
                    maxDepth = Math.max(maxDepth, waterDepth(world, x, z));
                    continue;
                }
                dry++;
                lo = Math.min(lo, g);
                hi = Math.max(hi, g);
                sumY += g;
                boolean edge = Math.abs(dx) >= sampleRadius - sampleStep
                        || Math.abs(dz) >= sampleRadius - sampleStep;
                if (edge) {
                    edgeSum += g;
                    edgeCount++;
                } else if (Math.abs(dx) <= sampleStep && Math.abs(dz) <= sampleStep) {
                    centerY += g;
                    centerCount++;
                }
                if (!preferBiomes.isEmpty() && matchesBiome(world, x, g, z, preferBiomes)) {
                    biomeHit++;
                }
            }
        }

        int waterPct = total == 0 ? 0 : wet * 100 / total;
        int landPct = 100 - waterPct;
        int relief = dry == 0 ? 0 : hi - lo;
        int meanY = dry == 0 ? 0 : (int) (sumY / dry);
        int biomePct = total == 0 || preferBiomes.isEmpty() ? 0 : biomeHit * 100 / total;
        boolean basin = centerCount > 0 && edgeCount > 0
                && (edgeSum / edgeCount) - (centerY / centerCount) >= 15;

        Map<String, Object> spec = terrain == null ? null : terrainTypes.get(terrain);
        if (spec == null) {
            return new Fit(100, waterPct, relief, meanY, maxDepth, biomePct, "지형 요구 없음");
        }

        int score = 100;
        StringBuilder why = new StringBuilder();
        score -= penaltyRange(spec, "water_pct", waterPct, why, "물");
        score -= penaltyRange(spec, "relief", relief, why, "기복");
        score -= penaltyRange(spec, "mean_y", meanY, why, "고도");
        score -= penaltyMin(spec, "land_pct_min", landPct, why, "뭍");
        score -= penaltyMin(spec, "max_depth_min", maxDepth, why, "수심");
        if (Boolean.TRUE.equals(spec.get("basin")) && !basin) {
            score -= 25;
            why.append("분지 아님 ");
        }
        // 바이옴은 가점이다 — 감점이 아니다. 지형이 맞으면 바이옴이 좀 달라도 지을 수 있다
        score = Math.min(100, score + biomePct / 8);
        score = Math.max(0, score);
        return new Fit(score, waterPct, relief, meanY, maxDepth, biomePct,
                why.isEmpty() ? "적합" : why.toString().trim());
    }

    /** [min, max] 범위를 벗어난 만큼 감점 (최대 40) */
    @SuppressWarnings("unchecked")
    private static int penaltyRange(Map<String, Object> spec, String key, int value,
                                    StringBuilder why, String label) {
        Object raw = spec.get(key);
        if (!(raw instanceof List)) {
            return 0;
        }
        List<Number> range = (List<Number>) raw;
        int min = range.get(0).intValue();
        int max = range.get(1).intValue();
        if (value >= min && value <= max) {
            return 0;
        }
        int off = value < min ? min - value : value - max;
        int span = Math.max(1, max - min);
        int penalty = Math.min(40, 10 + off * 30 / span);
        why.append(label).append(' ').append(value).append("(요구 ").append(min).append('~').append(max).append(") ");
        return penalty;
    }

    /** 최소값 미달분만큼 감점 (최대 40) */
    private static int penaltyMin(Map<String, Object> spec, String key, int value,
                                  StringBuilder why, String label) {
        Object raw = spec.get(key);
        if (!(raw instanceof Number)) {
            return 0;
        }
        int min = ((Number) raw).intValue();
        if (value >= min) {
            return 0;
        }
        int penalty = Math.min(40, 10 + (min - value) * 30 / Math.max(1, min));
        why.append(label).append(' ').append(value).append("(최소 ").append(min).append(") ");
        return penalty;
    }

    private static boolean matchesBiome(World world, int x, int y, int z, List<String> prefer) {
        Biome biome = world.getBiome(x, y, z);
        String key = biome.getKey().getKey().toLowerCase(Locale.ROOT);
        for (String want : prefer) {
            if (key.equals(want.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 부지 결정 — 결정론 인근 탐색
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 등록 좌표의 지형이 요구를 만족하면 그대로. 아니면 <b>상수 후보표 순서대로</b> 인근을 뒤진다.
     * 링(반경) × 8방위를 정해진 순서로 돌고, 첫 합격지를 채택한다. 전부 실격이면 최고점 자리.
     *
     * <p><b>난수 없음.</b> 같은 시드·같은 등록 좌표면 언제나 같은 부지가 나온다.
     * (CheonghaBuilder 의 SHRINE_SITES / FERRY_SITES 가 쓰는 바로 그 방식이다.)
     *
     * <p>최대 1,024블록까지만 민다 — 여정 일수는 그 정도로는 안 바뀐다.
     * <b>좌표는 흔들려도 시간표는 안 흔들린다.</b>
     */
    Site resolve(World world, Place p) {
        int bx = worldX(p);
        int bz = worldZ(p);
        Fit best = fit(world, bx, bz, p.terrain(), p.biomes());
        int bestX = bx;
        int bestZ = bz;
        int bestShift = 0;
        if (best.pass()) {
            return new Site(bx, bz, groundY(world, bx, bz), 0, best);
        }
        for (int step : ringSteps) {
            for (int[] b : bearings) {
                int x = bx + b[0] * step;
                int z = bz + b[1] * step;
                Fit f = fit(world, x, z, p.terrain(), p.biomes());
                if (f.pass()) {
                    return new Site(x, z, groundY(world, x, z), step, f);
                }
                if (f.score() > best.score()) {
                    best = f;
                    bestX = x;
                    bestZ = z;
                    bestShift = step;
                }
            }
        }
        Bukkit.getLogger().warning("[혼천/지도] " + p.name() + " 후보 전부 실격 — 최고점 "
                + best.score() + "점 자리에 앉힌다 (" + bestX + ", " + bestZ + "). 사유: " + best.verdict());
        return new Site(bestX, bestZ, groundY(world, bestX, bestZ), bestShift, best);
    }

    // ─── 시드 검사 지원 — 한 틱에 다 하지 않기 위한 조각들 ───
    //
    // scoreSeed 는 한 번 부르면 후보 수백 곳을 훑고, 후보마다 지형을 표본하면서 **청크를 동기 생성**한다.
    // 5개 시드를 검사했더니 4,380청크를 한 틱 안에서 만들다가 워치독이 서버를 죽였다.
    //   → 후보를 하나씩 꺼내 쓰도록 쪼갠다. 부르는 쪽(MvtCommand)이 틱을 나눠 먹는다.

    /**
     * 한 지역의 후보 좌표 — 등록 좌표가 먼저, 그다음 링(가까운 고리부터). {x, z, shift}
     *
     * <p><b>원거리는 더 멀리 찾는다.</b> 화산파를 세웠더니 <b>사막</b>에 섰다 — 등록 좌표가 요구하는
     * 험산(jagged_peaks)이 시드 8888 의 그 자리엔 없고, 링(≤1024칸)을 다 돌아도 없어서 최고점 자리에
     * 앉혔기 때문이다. 실지리 1:1 + 바닐라 생성이라 33곳의 지형을 시드 하나로 다 맞출 수는 없다.
     *
     * <p>답은 <b>더 멀리 옮기는 것</b>이다. 화산은 "그 근처 어딘가의 험한 봉우리"에 있으면 된다 —
     * 4,096칸(4km)은 하루 도보(40km)의 10분의 1이고, 여정 일수(반올림)를 흔들지 않는다.
     * 무대 안(stage_local: 청하현·산길)은 그대로 둔다 — 거기서 1km 씩 밀리면 마을이 사라진다.
     */
    List<int[]> probeCandidates(Place p) {
        List<int[]> out = new ArrayList<>();
        out.add(new int[]{worldX(p), worldZ(p), 0});
        List<Integer> steps = new ArrayList<>(ringSteps);
        if (!p.stageLocal()) {
            for (int far : new int[]{1536, 2048, 3072, 4096}) {
                if (!steps.contains(far)) {
                    steps.add(far);
                }
            }
        }
        for (int step : steps) {
            for (int[] b : bearings) {
                out.add(new int[]{worldX(p) + b[0] * step, worldZ(p) + b[1] * step, step});
            }
        }
        return out;
    }

    /** 후보 한 곳의 지형 적합성 (청크 생성이 여기서 일어난다 — 한 번에 하나씩 부르라) */
    Fit fitAt(World world, Place p, int x, int z) {
        return fit(world, x, z, p.terrain(), p.biomes());
    }

    /** 검사용 임시 월드 — 쓰고 지운다 */
    World createProbeWorld(long seed) {
        return new WorldCreator("seedprobe_" + Long.toUnsignedString(seed))
                .seed(seed).environment(World.Environment.NORMAL).createWorld();
    }

    /** 검사용 임시 월드 폐기 — 언로드 + 폴더 삭제 */
    void disposeProbeWorld(World probe) {
        if (probe != null) {
            Bukkit.unloadWorld(probe, false);
            deleteRecursively(probe.getWorldFolder());
        }
    }

    /** 조성 기준 지면 y — 부지 중심의 자연 지면 (물이면 해수면으로 폴백) */
    static int groundY(World world, int x, int z) {
        int g = naturalGroundY(world, x, z);
        return g == Integer.MIN_VALUE ? world.getSeaLevel() : g;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 시드 탐색 — 등록 좌표들의 지형이 가장 잘 맞는 시드를 찾는다
    // ═══════════════════════════════════════════════════════════════════

    /** 한 시드의 채점 결과 */
    record SeedReport(long seed, int score, List<String> lines) {
    }

    /**
     * 시드 채점 대상 — <b>지금 조성하는 것</b>이 기준이다.
     * 청하현(평지) + 무대 안 스팟(폐사당=마른 땅 / 사냥터=산 / 흑수나루=물가).
     *
     * <p>이 넷이 과거에 실제로 깨졌던 자리다: 물가·저지대에 서서 조성했더니
     * 폐사당 후보 12곳이 전부 물이었다. 시드는 그 사고를 <b>미리</b> 막는 도구다.
     */
    List<Place> seedProbeTargets(boolean includeRemote) {
        List<Place> out = new ArrayList<>();
        for (Place p : places.values()) {
            if (p.terrain() == null) {
                continue;
            }
            if (p.buildableNow() || p.stageLocal()) {
                out.add(p);
            } else if (includeRemote && travelDays(p) <= 6) {
                out.add(p);   // --원거리: 청하현에서 엿새 안 — 다음에 지을 것들
            }
        }
        return out;
    }

    /**
     * 시드 하나를 채점한다. 임시 월드를 만들어 등록 좌표의 지형을 실측하고 지운다.
     *
     * <p>비용: 장소당 약 36청크 생성. 기본 4곳이면 ~150청크 — 몇 초.
     * {@code includeRemote} 면 10곳 안팎 — 수십 초.
     */
    SeedReport scoreSeed(long seed, boolean includeRemote) {
        String name = "seedprobe_" + Long.toUnsignedString(seed);
        World probe = new WorldCreator(name).seed(seed).environment(World.Environment.NORMAL).createWorld();
        List<String> lines = new ArrayList<>();
        int sum = 0;
        int count = 0;
        try {
            for (Place p : seedProbeTargets(includeRemote)) {
                Site site = resolve(probe, p);
                sum += site.fit().score();
                count++;
                lines.add(String.format("  %-14s %3d점 %s%s", p.name(), site.fit().score(),
                        site.fit().pass() ? "적합" : "부적합 — " + site.fit().verdict(),
                        site.shift() == 0 ? "" : " (인근 " + site.shift() + "칸 이동)"));
            }
        } finally {
            if (probe != null) {
                Bukkit.unloadWorld(probe, false);
                deleteRecursively(probe.getWorldFolder());
            }
        }
        return new SeedReport(seed, count == 0 ? 0 : sum / count, lines);
    }

    private static void deleteRecursively(java.io.File file) {
        java.io.File[] kids = file.listFiles();
        if (kids != null) {
            for (java.io.File kid : kids) {
                deleteRecursively(kid);
            }
        }
        if (!file.delete()) {
            file.deleteOnExit();
        }
    }
}
