package com.honcheon.mvt;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Stairs;

import java.util.ArrayList;
import java.util.List;

/**
 * 원거리 지역 조성기 — <b>건축 계층</b>. 등록부의 좌표에 실제로 집이 서게 한다.
 *
 * <p>세계 지도(world_map.yml)에는 33곳이 좌표를 갖고 있는데, 지금까지 <b>선 것은 청하현과 산길 도적뿐</b>이고
 * 나머지는 {@code build: later} 였다. 등록만 되고 서지 않은 곳은 여행의 목적지가 아니라 문서의 줄이다.
 *
 * <p>실지리 1:1(1블록 = 1m)이라 33곳을 손으로 지을 수는 없다. 그래서 <b>원형(archetype)</b>을 짓는다:
 * 등록부가 세력·등급·건축을 적어 두었으니, 조성기는 그것을 읽어 원형을 고른다.
 * <ul>
 *   <li><b>산채(寨)</b> — 녹림. 목책과 통나무 막사. "도적의 집은 언제든 버릴 수 있어야 한다"(등록부의 말)</li>
 *   <li><b>도관(道觀)</b> — 구파일방·오대세가. 산문 → 천 계단 → 문전 → 본전</li>
 * </ul>
 *
 * <hr>
 *
 * <h2>v2 — 【지형 계층 분리】 이 파일은 이제 땅을 만지지 않는다</h2>
 *
 * <p>사고가 났다: 산채 자리에 봉우리가 서서 사방이 <b>33칸 벼랑</b>이 되고 걸어 들어갈 수 없게 됐다.
 * 원인은 하나다 — <b>두 계층이 산을 두 번 만졌다.</b> {@link TerrainForge} 가 주문대로 봉우리를 세웠는데,
 * RemoteBuilder 가 제 {@code shapeTerrain}·{@code raiseMassif}·{@code terrace}·{@code feather} 로
 * 그 위에 또 산을 세우고 또 단을 깎고 또 전이대를 놓았다. 땅이 두 번 빚어지면 그것은 지형이 아니라 사고다.
 *
 * <p>계약(docs/design/terrain_layer.md)은 이렇다:
 * <pre>
 *   지도(world_map.yml) ──주문──▶ 지형 계층(TerrainForge) ──SiteSpec──▶ 건축 계층(RemoteBuilder)
 * </pre>
 * 그래서 이 파일에서 <b>지형 함수를 전부 걷어냈다</b>:
 * {@code shapeTerrain · raiseMassif · levelField · terrace · sealBelow · feather · naturalGround · NATURAL}.
 *
 * <p>이제 땅에 대한 질문은 전부 {@link TerrainForge.SiteSpec} 이 답한다:
 * <ul>
 *   <li>{@code spec.groundY()} — 조성 기준 지면 (그 아래 6칸은 이미 단단하다)</li>
 *   <li>{@code spec.groundAt(x,z)} · {@code spec.canBuild(x,z)} — 열마다의 지면과 건축 가능 여부</li>
 *   <li>{@code spec.approaches()} — <b>걸어 들어올 수 있는 방위</b>. 채문·산문은 여기로 낸다</li>
 *   <li>{@code spec.peakX()/peakZ()/peakY()} · {@code spec.twoTier()} — 봉우리와 두 켜</li>
 * </ul>
 * 평평한 바닥이 필요하면 <b>직접 깎지 않고 요청한다</b> —
 * {@link TerrainForge#terrace} · {@link TerrainForge#terraceRound}. 흙일은 땅의 일이다.
 *
 * <p><b>결정론</b> — 조성기에 난수는 없다. 흔들림은 전부 좌표 해시({@code Math.floorMod})다.
 * 같은 자리에 두 번 지으면 같은 산채가 선다.
 */
final class RemoteBuilder {

    private RemoteBuilder() {
    }

    /** 산채 반지름 — 목책 둘레 (지형 계층이 준 부지 반경 24 안에 든다) */
    private static final int R = 22;

    // ══════════════════════════════════════════════════════════════════
    //  진입 방향 — 문은 걸어 들어올 수 있는 쪽에 낸다
    // ══════════════════════════════════════════════════════════════════

    /**
     * <b>배치의 방위</b> — 국소 좌표(전방 f = 문 쪽 · 측방 l)를 세계 좌표로 옮긴다.
     *
     * <p>지금까지 채문·산문은 <b>남쪽 고정</b>이었다. 지형이 남쪽을 벼랑으로 만들면 문이 벼랑에 붙었고,
     * 그 산채는 들어갈 수 없는 집이었다 (환경 검수 ④ 사방 진입 — "북쪽에서는 아예 걸어 들어올 수 없다").
     * 이제 {@code spec.approaches()} 가 고른 방위로 배치 전체를 돌린다.
     *
     * <p>원형의 좌표표는 전부 <b>국소 좌표</b>다: {@code f} 가 커지면 문 쪽(밖), {@code l} 은 좌우.
     * 남쪽 진입이면 (f, l) = (+z, +x) 라 옛 상수표와 정확히 같다 — 회귀 없이 회전만 얻는다.
     */
    private record Facing(BlockFace face, int fx, int fz, int lx, int lz) {

        static Facing of(BlockFace f) {
            return switch (f) {
                case NORTH -> new Facing(f, 0, -1, -1, 0);
                case EAST -> new Facing(f, 1, 0, 0, -1);
                case WEST -> new Facing(f, -1, 0, 0, 1);
                default -> new Facing(BlockFace.SOUTH, 0, 1, 1, 0);   // 남 — 옛 상수표의 방위
            };
        }

        int x(int cx, int f, int l) {
            return cx + fx * f + lx * l;
        }

        int z(int cz, int f, int l) {
            return cz + fz * f + lz * l;
        }

        /** 전방(밖)을 보는 면 */
        BlockFace out() {
            return face;
        }

        /** 후방(안)을 보는 면 */
        BlockFace in() {
            return face.getOppositeFace();
        }

        /** 측방 +l 을 보는 면 */
        BlockFace side(int sign) {
            int dx = lx * sign;
            int dz = lz * sign;
            if (dx > 0) {
                return BlockFace.EAST;
            }
            if (dx < 0) {
                return BlockFace.WEST;
            }
            return dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
        }

        /** 동·서 진입이면 가로세로가 바뀐다 (직사각형 원형의 폭·깊이를 맞바꾼다) */
        boolean swapped() {
            return fx != 0;
        }
    }

    /**
     * 문을 낼 방위 — <b>걸어 들어올 수 있는 쪽</b>.
     *
     * <p>선호 순서는 남·동·서·북(옛 배치의 회귀를 막는다: 남이 열려 있으면 옛 마을과 같은 그림이 선다).
     * 어느 방위도 안 열렸으면(사방이 벼랑·물) 남으로 낸다 — 그 부지는 지형 계층이 이미 고발했다.
     */
    private static Facing entry(TerrainForge.SiteSpec spec) {
        BlockFace[] pref = {BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH};
        for (BlockFace f : pref) {
            if (spec.approaches().contains(f)) {
                return Facing.of(f);
            }
        }
        return Facing.of(BlockFace.SOUTH);
    }

    // ══════════════════════════════════════════════════════════════════
    //  원형(archetype) — 등록부가 고르고, 검수가 그 이름으로 계약을 묻는다
    // ══════════════════════════════════════════════════════════════════

    /**
     * 지역 원형 다섯. <b>등록부(world_map.yml)의 세력·구역·architecture 가 고른다</b> — 우리가 발명하지 않는다.
     *
     * <ul>
     *   <li>{@link #산채} — 녹림. 목책·통나무 (버리고 떠날 집)</li>
     *   <li>{@link #도관} — 구파·세가. 산문 → 천 계단 → 본전 (오르는 길이 시험이다)</li>
     *   <li>{@link #은신처} — 마·혈. <b>동굴이 본체다.</b> 지상은 위장, 살림은 굴 안</li>
     *   <li>{@link #성문} — 성시(장안·낙양·개봉). 실지리 1:1 이라 도시는 못 짓는다 — <b>관문 한 구획</b>만 짓는다</li>
     *   <li>{@link #수로채} — 장강수로채. <b>뭍에 집이 없다</b> (등록부의 말) — 뗏목과 말뚝이 전부다</li>
     * </ul>
     */
    enum Archetype {
        산채, 도관, 은신처, 성문, 수로채
    }

    /**
     * 이 자리에 어떤 원형이 서는가 — <b>등록부를 읽는다</b>.
     *
     * <p>세력만으로는 갈리지 않는다: {@code gwan_gun} 에는 성시(장안)도 있고 역참·군진·황성도 있다.
     * 그래서 <b>구역(section)</b> 을 같이 본다 — {@code places} 의 관(官)은 성시고,
     * {@code places_official} 의 관은 역참·군진이다(그 원형은 아직 없다).
     *
     * <p>마·혈은 <b>굴이 있어야</b> 은신처다 ({@code TerrainForge.caveKind} 가 답한다).
     * 마교 본교("초원의 흑성")·낙양 잠복지에는 굴이 없다 — 그 원형은 아직 없고, 있는 척하지 않는다.
     */
    static Archetype archetype(WorldMap.Place place) {
        String faction = place.faction() == null ? "" : place.faction();
        if ("noklim".equals(faction)) {
            return Archetype.산채;
        }
        if (SECTS.contains(faction)) {
            return Archetype.도관;
        }
        if (("magyo".equals(faction) || "hyeolgyo".equals(faction))
                && TerrainForge.caveKind(place) != null) {
            return Archetype.은신처;   // 굴이 본체다 — 굴이 없으면 은신처가 아니다
        }
        if ("gwan_gun".equals(faction) && "places".equals(place.section())) {
            return Archetype.성문;     // 5.2 현성·도시 — 성시의 관문
        }
        if ("jangang_suroche".equals(faction)) {
            return Archetype.수로채;
        }
        return null;
    }

    /**
     * 지역 하나를 짓는다. 원형은 등록부가 고르고, <b>땅은 지형 계층이 준다</b>.
     *
     * @param spec 부지 사양 — {@link TerrainForge#prepare} 가 이미 땅을 빚고 봉인하고 전이대를 놓았다
     * @return 등록할 구역 (없으면 빈 목록 — 원형이 없는 세력은 아직 못 짓는다)
     * @deprecated 굴이 있는 지역(마·혈)은 {@link #build(World, WorldMap.Place, TerrainForge.SiteSpec,
     *             TerrainForge.CaveSpec)} 를 써라 — 굴 안의 살림은 굴의 좌표를 알아야 놓는다
     */
    @Deprecated
    static List<Zone> build(World world, WorldMap.Place place, TerrainForge.SiteSpec spec) {
        return build(world, place, spec, null);
    }

    /**
     * 지역 하나를 짓는다 — <b>굴까지 받는다</b>.
     *
     * @param cave 지형 계층이 판 굴 ({@link TerrainForge#digCave}). 굴이 없는 지역이면 null.
     *             <b>은신처 원형은 이것이 있어야 안을 채운다</b> — 제단·거처는 굴 안에 있고,
     *             그 좌표는 {@link TerrainForge.CaveSpec} 만이 안다 (입구는 부지 밖 24칸까지 나갈 수 있다)
     */
    static List<Zone> build(World world, WorldMap.Place place, TerrainForge.SiteSpec spec,
                            TerrainForge.CaveSpec cave) {
        Archetype kind = archetype(place);
        if (kind == null) {
            return List.of();
        }
        List<Zone> zones = switch (kind) {
            case 산채 -> stockade(world, place, spec);
            case 도관 -> sect(world, place, spec);
            case 은신처 -> hideout(world, place, spec, cave);
            case 성문 -> cityGate(world, place, spec);
            case 수로채 -> waterStockade(world, place, spec);
        };
        zones.forEach(zone -> sweepOrphans(world, zone, kind));   // 처마에서 떨어져 나온 기와를 거둔다
        return zones;
    }

    /**
     * <b>떨어져 나온 것을 거둔다.</b>
     *
     * <p>검수가 화산파에서 "허공 블록 41개"를 세었다. 처음엔 지형이 남긴 부스러기인 줄 알고
     * 지형을 쓸었는데 <b>0개가 쓸렸다.</b> 그래서 검수에게 <b>"몇 개냐"가 아니라 "무엇이냐"</b>를 묻게 했더니
     * 이렇게 답했다 — <b>기와 37장</b>, 나뭇잎 3, 통나무 1. 지형이 아니라 <b>지붕</b>이었다.
     * (수를 세는 눈은 어디를 팔지 알려주지 않는다. 이름을 대는 눈이 알려준다.)
     *
     * <p>처음엔 <b>"여섯 면이 허공인 블록은 어떤 건축에도 없다"</b> 고 적고 자재를 가리지 않았다.
     * <b>그것이 틀렸다.</b> 쓸개에게 이름을 대게 했더니 이렇게 답했다 —
     * {@code {LANTERN=3, SPRUCE_PLANKS=5, STONE=1, TORCH=2}}. <b>망루의 불을 먹고 있었다.</b>
     * (검수가 곧바로 짖었다: "망루의 불 없음 — TORCH 0개 &lt; 2".)
     *
     * <p>그러므로 <b>불은 건드리지 않는다.</b> 등롱이 허공에 걸린 것은 <b>지으려던 것</b>이다 —
     * 사슬이 빠졌을지언정 그 자리에 불이 있어야 한다는 뜻이고, 그것을 지우면 <b>밤에 사람이 죽는다.</b>
     * 마찬가지로 <b>구조 계약({@link #contract})의 자재</b>도 건드리지 않는다 —
     * 그 집이 그 집이려면 있어야 하는 것을 쓸개가 치우면, 쓸개가 곧 파괴다.
     *
     * <p>다만 <b>떠 있다는 사실은 감추지 않는다.</b> 남겨 둔 것은 로그가 말한다 — 건축의 흠이지 쓸개의 일이 아니다.
     *
     * <p>건축이 <b>끝난 뒤</b> 돈다. 짓는 도중에 쓸면 아직 이웃이 놓이지 않은 것을 거둔다.
     */
    private static void sweepOrphans(World world, Zone zone, Archetype kind) {
        int swept = 0;
        // 무엇을 거뒀는지 이름을 댄다 — **거둔 것이 지으려던 것이면 그건 쓸개가 아니라 파괴다.**
        java.util.Map<Material, Integer> what = new java.util.TreeMap<>();
        java.util.Map<Material, Integer> kept = new java.util.TreeMap<>();   // 떠 있으나 지우지 않은 것
        for (int x = zone.x1(); x <= zone.x2(); x++) {
            for (int z = zone.z1(); z <= zone.z2(); z++) {
                for (int y = zone.y1(); y <= zone.y2(); y++) {
                    org.bukkit.block.Block block = world.getBlockAt(x, y, z);
                    if (block.getType().isAir()) {
                        continue;
                    }
                    if (protectedFrom(block.getType(), kind)) {
                        if (isOrphan(world, x, y, z)) {
                            kept.merge(block.getType(), 1, Integer::sum);   // 떠 있다. 그러나 지우지 않는다
                        }
                        continue;
                    }
                    if (isOrphan(world, x, y, z)) {
                        what.merge(block.getType(), 1, Integer::sum);
                        block.setType(Material.AIR, false);   // 이웃 갱신 불필요 — 이웃이 없다
                        swept++;
                    }
                }
            }
        }
        if (swept > 0) {
            org.bukkit.Bukkit.getLogger().info("[건축] " + zone.name() + " — 흘린 블록 " + swept + "개를 거뒀다: "
                    + what);
        }
        if (!kept.isEmpty()) {
            // 감추지 않는다 — 이것은 건축의 흠이다 (사슬 빠진 등롱 따위). 쓸개가 치울 일이 아니다.
            org.bukkit.Bukkit.getLogger().info("[건축] " + zone.name() + " — 떠 있으나 남겨 둔 것: " + kept
                    + " (불과 계약 자재는 건드리지 않는다)");
        }
    }

    /** 쓸개가 손대면 안 되는 것 — <b>불</b>과 <b>그 집을 그 집으로 만드는 자재</b> */
    private static boolean protectedFrom(Material m, Archetype kind) {
        if (m.createBlockData().getLightEmission() > 0) {
            return true;   // 불. 지우면 밤에 사람이 죽는다
        }
        for (Need need : contract(kind)) {
            if (need.material() == m) {
                return true;   // 검수가 세는 것 — 쓸개가 치우면 쓸개가 곧 파괴다
            }
        }
        return false;
    }

    /** 여섯 면이 전부 허공인가 */
    private static boolean isOrphan(World world, int x, int y, int z) {
        return world.getBlockAt(x, y - 1, z).getType().isAir()
                && world.getBlockAt(x, y + 1, z).getType().isAir()
                && world.getBlockAt(x + 1, y, z).getType().isAir()
                && world.getBlockAt(x - 1, y, z).getType().isAir()
                && world.getBlockAt(x, y, z + 1).getType().isAir()
                && world.getBlockAt(x, y, z - 1).getType().isAir();
    }

    /** 부지 반경 — 산채는 좁고(목책 R=22), 나머지는 넓다. <b>MvtCommand 의 forgeRadius 와 같은 값이다</b> */
    static int siteRadius(WorldMap.Place place) {
        return "noklim".equals(place.faction()) ? 24 : 64;
    }

    /**
     * 구조 계약 — <b>그 집이 그 집이려면 반드시 있어야 하는 것</b>.
     *
     * <p>지역 검수(RegionAudit ②)가 이 표를 읽는다. 지금까지 검수는 "산채냐 아니냐"로만 갈렸고,
     * 그래서 <b>마교 은신처에 매화를 요구했다</b>. 원형이 다섯이면 계약도 다섯이다 —
     * 무엇이 그 집을 그 집으로 만드는가는 <b>짓는 자가 안다</b>. 그래서 표가 여기 있다.
     */
    record Need(Material material, int min, String what) {
    }

    static List<Need> contract(Archetype kind) {
        if (kind == null) {
            return List.of();
        }
        return switch (kind) {
            case 산채 -> List.of(
                    new Need(Material.SPRUCE_LOG, 200, "목책·막사(통나무)"),
                    new Need(Material.CAMPFIRE, 1, "모닥불(마당의 중심)"),
                    new Need(Material.HAY_BLOCK, 10, "초가·짚"),
                    new Need(Material.TORCH, 2, "망루의 불"));
            case 도관 -> List.of(
                    new Need(Material.DEEPSLATE_TILES, 40, "검은 기와(본전)"),
                    new Need(Material.WHITE_TERRACOTTA, 40, "회벽(본전)"),
                    new Need(Material.POLISHED_ANDESITE, 400, "돌바닥(계단·단)"),
                    new Need(Material.CHERRY_LEAVES, 20, "매화"),
                    new Need(Material.LANTERN, 4, "석등(오르는 길의 불)"));
            // 은신처 — **없는 것으로 정의된다**. 기와도 회벽도 매화도 없고, 등롱은 한 기도 없다.
            //   있는 것은 굴 안의 제단(심층암 벽돌)과 촛불, 그리고 쟁여 둔 궤뿐이다.
            case 은신처 -> List.of(
                    new Need(Material.DEEPSLATE_BRICKS, 18, "석실의 제단"),
                    new Need(Material.CANDLE, 2, "촛불(굴 안의 유일한 불)"),
                    new Need(Material.CHEST, 1, "쟁여 둔 궤"),
                    new Need(Material.COBWEB, 4, "거미줄(사람이 사는 흔적을 지운다)"));
            // 성문 — **규모가 위계다**. 청하현의 담(조약돌 3켜)과 성시의 성벽은 다른 물건이다
            case 성문 -> List.of(
                    new Need(Material.STONE_BRICKS, 1500, "성벽(거대한 돌)"),
                    new Need(Material.STONE_BRICK_WALL, 40, "여장(女墻)"),
                    new Need(Material.DEEPSLATE_TILES, 40, "성루의 기와"),
                    new Need(Material.DIRT_PATH, 150, "관도(성문으로 드는 길)"),
                    new Need(Material.LANTERN, 8, "관도의 등롱"));
            // 수로채 — 뭍에 집이 없다. 뗏목(판자)과 말뚝(통나무)이 이 집의 전부다
            case 수로채 -> List.of(
                    new Need(Material.SPRUCE_PLANKS, 300, "뗏목 마루"),
                    new Need(Material.SPRUCE_LOG, 60, "물에 박은 말뚝"),
                    new Need(Material.BARREL, 4, "통행세 궤짝"),
                    new Need(Material.LANTERN, 4, "물 위의 불"));
        };
    }

    /**
     * <b>구(舊) 진입점 — 호출자가 아직 SiteSpec 을 넘기지 않을 때의 다리.</b>
     *
     * <p>지형 계층({@link TerrainForge#prepare})은 이미 이 호출 **앞에서** 돌았다. 그러니 여기서는
     * 땅을 다시 빚지 않고 <b>재기만</b> 한다({@link TerrainForge#survey} — 블록을 한 칸도 건드리지 않는다).
     *
     * @deprecated {@code build(world, place, spec)} 을 쓰라 — 지형 계층이 낸 사양을 그대로 넘기면
     *             측량이 한 번으로 끝난다. 이 다리는 MvtCommand 가 배선을 바꾸면 지운다.
     */
    @Deprecated
    static List<Zone> build(World world, WorldMap.Place place, int cx, int cy, int cz) {
        return build(world, place,
                TerrainForge.survey(world, place, cx, cy, cz, siteRadius(place)));
    }

    /** 문파 원형을 쓰는 세력 — 구파일방·오대세가는 <b>같은 문법</b>을 쓴다 (산문 → 계단 → 문전 → 본전) */
    private static final java.util.Set<String> SECTS = java.util.Set.of(
            "hwasan", "gupailbang", "jongnam", "sorimsa", "mudang", "gonryun",
            "jeomchang", "cheongseong", "ami", "haenam", "gaebang");

    /** 원형을 가진 세력인가 — 명령이 미리 물어 "아직 못 짓는다"고 말할 수 있게 */
    static boolean canBuild(WorldMap.Place place) {
        return archetype(place) != null;
    }

    // ══════════════════════════════════════════════════════════════════
    //  산채(寨) — 목책 · 채문 · 망루 · 통나무 막사 · 마당
    // ══════════════════════════════════════════════════════════════════

    /**
     * 산채의 문법 — 청하현의 문법과 다른 점이 곧 도적의 성격이다.
     *
     * <ul>
     *   <li><b>담이 아니라 목책</b> — 돌을 쌓지 않는다. 통나무를 박고 끝을 뾰족하게 깎는다(계단 블록).
     *       버리고 떠날 집에 석공을 부르지 않는다.</li>
     *   <li><b>기와가 없다</b> — 지붕은 짚(건초)이다. 청하현의 수묵 규칙은 여기서도 산다: 채색은 깃발뿐.</li>
     *   <li><b>마당이 중심</b> — 관아는 정청이 중심이고, 산채는 <b>모닥불</b>이 중심이다.</li>
     *   <li><b>망루 둘</b> — 도적은 지키는 자가 아니라 <b>보는 자</b>다.</li>
     * </ul>
     *
     * <p>v2 — 땅은 만지지 않는다. 마당 한 켜가 필요하면 {@link TerrainForge#terraceRound} 에 <b>요청</b>한다.
     */
    private static List<Zone> stockade(World world, WorldMap.Place place, TerrainForge.SiteSpec spec) {
        int cx = spec.cx();
        int cz = spec.cz();
        int cy = spec.groundY();
        Facing fw = entry(spec);

        // 【단】 — 목책 안 한 켜. 깎고·받치고·봉인하는 것은 전부 지형 계층의 손이다.
        TerrainForge.terraceRound(world, spec, cx, cz, cy, R, Material.DIRT_PATH);
        speckleYard(world, cx, cy, cz);   // 마당 결 — 다져진 흙에 거친 흙을 점치환 (바닥 마감 = 건축의 일)

        palisade(world, cx, cy, cz, fw);
        stockadeGate(world, spec, cx, cy, cz, fw);

        // 조감이 잡아낸 것: 막사 넷이 마당을 다 먹어 모닥불이 묻혔고, 두목 막사가 채문 앞을 막았다.
        //   산채의 중심은 **마당**이다 (나눠 먹는 자리). 집은 벽을 등지고 물러서고, 가운데를 비운다.
        //   두목 막사는 **문 맞은편**(f = -19) — 마당 건너로 들어오는 자를 본다.
        barrackLocal(world, cx, cy, cz, fw, -4, -19, 7, 9, +1);    // 좌(측방 -) 막사 — 문은 마당(+l)을 본다
        barrackLocal(world, cx, cy, cz, fw, -4, 11, 7, 9, -1);     // 우(측방 +) 막사 — 문은 마당(-l)을 본다
        chiefHall(world, cx, cy, cz, fw);                          // 두목 막사 (문 맞은편) — 13x9
        watchtower(world, cx, cy, cz, fw, -13, -15);
        watchtower(world, cx, cy, cz, fw, -13, 14);

        yard(world, cx, cy, cz, fw);
        return List.of(new Zone(place.name(), "녹림 — 목책과 통나무", world.getName(),
                cx - R - 2, cy - 4, cz - R - 2, cx + R + 2, cy + 14, cz + R + 2));
    }

    /** 마당 결 — 다진 흙에 거친 흙 한 점씩 (바닥의 마감은 건축의 일이다 · 높이는 건드리지 않는다) */
    private static void speckleYard(World world, int cx, int cy, int cz) {
        for (int x = cx - R; x <= cx + R; x++) {
            for (int z = cz - R; z <= cz + R; z++) {
                if (dist(x - cx, z - cz) > R || Math.floorMod(x * 7 + z * 3, 11) != 0) {
                    continue;
                }
                if (world.getBlockAt(x, cy, z).getType() == Material.DIRT_PATH) {
                    world.getBlockAt(x, cy, z).setType(Material.COARSE_DIRT);
                }
            }
        }
    }

    /** 목책 — 통나무 기둥 5단 + 뾰족한 끝(계단) + 안쪽 순찰 마루 */
    private static void palisade(World world, int cx, int cy, int cz, Facing fw) {
        for (int f = -R - 2; f <= R + 2; f++) {
            for (int l = -R - 2; l <= R + 2; l++) {
                double d = dist(f, l);
                double r = ringR(cx, cz, f, l);
                if (d < r - 0.6 || d > r + 0.6) {
                    continue;
                }
                if (isGateSpan(f, l)) {
                    continue;   // 채문 자리 — 목책이 열린다
                }
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                for (int y = cy + 1; y <= cy + 5; y++) {
                    world.getBlockAt(x, y, z).setType(Material.SPRUCE_LOG);
                }
                int ix = fw.x(cx, f + inward(f), l + inward(l));
                int iz = fw.z(cz, f + inward(f), l + inward(l));
                if (world.getBlockAt(ix, cy + 4, iz).getType().isAir()) {
                    world.getBlockAt(ix, cy + 4, iz).setType(Material.SPRUCE_PLANKS);   // 순찰 마루
                }
                // 끝을 깎는다 — 뾰족한 말뚝. 계단의 코가 밖을 본다
                BlockFace out = Math.abs(f) > Math.abs(l)
                        ? (f > 0 ? fw.out() : fw.in())
                        : fw.side(l > 0 ? 1 : -1);
                stair(world, x, cy + 6, z, Material.SPRUCE_STAIRS, out, true);
            }
        }
    }

    /**
     * 채문(寨門) — <b>진입 방향</b>에 낸다. 통나무 문루 + 문 앞 산길.
     *
     * <p>문 밖 20칸의 길은 지형 계층에 <b>단을 요청</b>해서 낸다 (직접 깎지 않는다). 전이대 위를 지나므로
     * 그 자리는 이미 1-립시츠(한 걸음 ≤ 1칸)다 — 길은 그 표고를 그대로 따라간다.
     */
    private static void stockadeGate(World world, TerrainForge.SiteSpec spec,
                                     int cx, int cy, int cz, Facing fw) {
        for (int l = -2; l <= 2; l++) {   // 문루 상단 — 통나무 들보
            world.getBlockAt(fw.x(cx, R, l), cy + 5, fw.z(cz, R, l)).setType(Material.SPRUCE_LOG);
            world.getBlockAt(fw.x(cx, R, l), cy + 6, fw.z(cz, R, l)).setType(Material.SPRUCE_SLAB);
        }
        for (int l : new int[]{-3, 3}) {   // 문설주
            for (int y = cy + 1; y <= cy + 4; y++) {
                world.getBlockAt(fw.x(cx, R, l), y, fw.z(cz, R, l)).setType(Material.SPRUCE_LOG);
            }
        }
        for (int l = -2; l <= 2; l++) {   // 문짝 자리는 비운다 — 도적은 문을 잠그지 않는다
            for (int y = cy + 1; y <= cy + 4; y++) {
                world.getBlockAt(fw.x(cx, R, l), y, fw.z(cz, R, l)).setType(Material.AIR);
            }
        }
        wallTorch(world, fw.x(cx, R - 1, -3), cy + 3, fw.z(cz, R - 1, -3), fw.in());
        wallTorch(world, fw.x(cx, R - 1, 3), cy + 3, fw.z(cz, R - 1, 3), fw.in());

        // 문 밖 길 — 걸어 올라오는 자의 마지막 스무 칸
        approachPath(world, spec, cx, cz, fw, R + 1, R + 20, cy, 1, Material.DIRT_PATH);
    }

    /**
     * <b>문 밖 진입로</b> — 문 앞 표고에서 출발해 <b>한 걸음에 ±1칸</b>만 오르내리며 자연으로 나간다.
     * 한 칸짜리 단을 지형 계층에 요청한다 (깎기·축대·봉인이 함께 온다).
     *
     * <p><b>★ 여기에 지붕을 얹지 마라.</b> 지역 검수 ①(도달성)의 BFS 는 구역 <b>남쪽 가장자리 열의
     * 가장 높은 설 자리</b>에서 출발한다({@code RegionAudit.standY} 는 {@code zone.y2()} 에서 아래로 훑는다).
     * 그 열에 문루·산문 지붕이 있으면 <b>사람이 지붕 위에서 출발해 갇힌다</b> —
     * 화산파가 그렇게 못 올랐다 (걸어 닿은 칸 24 · 오른 최고 y78 = 산문 기와 위). 계단은 멀쩡했다.
     * 그러니 <b>가장자리 두 칸은 반드시 맨 진입로</b>여야 한다.
     */
    private static void approachPath(World world, TerrainForge.SiteSpec spec, int cx, int cz, Facing fw,
                                     int fFrom, int fTo, int y0, int half, Material floor) {
        int prev = y0;
        for (int f = fFrom; f <= fTo; f++) {
            int ax = fw.x(cx, f, 0);
            int az = fw.z(cz, f, 0);
            int y = spec.inside(ax, az) ? spec.groundAt(ax, az) : surfaceProbe(world, ax, az, prev);
            y = Math.max(prev - 1, Math.min(prev + 1, y));   // 한 걸음 ±1 — 길에 계단은 없다
            for (int l = -half; l <= half; l++) {
                TerrainForge.terrace(world, spec, fw.x(cx, f, l), fw.z(cz, f, l), y, 0, 0, floor);
            }
            prev = y;
        }
    }

    /** 망루 — 통나무 기둥 + 사다리 + 상단 널마루 + 횃불. 도적은 지키는 자가 아니라 보는 자다 */
    private static void watchtower(World world, int cx, int cy, int cz, Facing fw, int f0, int l0) {
        int x0 = fw.x(cx, f0, l0);
        int z0 = fw.z(cz, f0, l0);
        for (int y = cy + 1; y <= cy + 7; y++) {
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    world.getBlockAt(x0 + dx, y, z0 + dz).setType(Material.SPRUCE_LOG);
                }
            }
        }
        for (int x = x0 - 1; x <= x0 + 2; x++) {   // 상단 마루 4x4 + 난간
            for (int z = z0 - 1; z <= z0 + 2; z++) {
                world.getBlockAt(x, cy + 8, z).setType(Material.SPRUCE_PLANKS);
                if (x == x0 - 1 || x == x0 + 2 || z == z0 - 1 || z == z0 + 2) {
                    world.getBlockAt(x, cy + 9, z).setType(Material.SPRUCE_FENCE);
                }
            }
        }
        world.getBlockAt(x0, cy + 8, z0).setType(Material.AIR);   // 오르는 구멍
        for (int y = cy + 1; y <= cy + 8; y++) {
            ladder(world, x0 - 1, y, z0, BlockFace.EAST);
        }
        world.getBlockAt(x0, cy + 10, z0).setType(Material.TORCH);   // 망루 불 — 밤의 산채는 이걸로 읽힌다
    }

    /** 국소 좌표로 놓는 막사 — (f0,l0) 이 국소 원점, 깊이 fd(전후) x 폭 lw(좌우). 문은 측방으로 난다 */
    private static void barrackLocal(World world, int cx, int cy, int cz, Facing fw,
                                     int f0, int l0, int fd, int lw, int doorSide) {
        int[] box = localBox(cx, cz, fw, f0, f0 + fd - 1, l0, l0 + lw - 1);
        boolean swap = fw.swapped();
        barrack(world, box[0], cy, box[1], swap ? fd : lw, swap ? lw : fd, fw.side(doorSide));
    }

    /** 국소 사각형 → 세계 사각형의 최소 모서리 {x0, z0} */
    private static int[] localBox(int cx, int cz, Facing fw, int f0, int f1, int l0, int l1) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (int f : new int[]{f0, f1}) {
            for (int l : new int[]{l0, l1}) {
                minX = Math.min(minX, fw.x(cx, f, l));
                minZ = Math.min(minZ, fw.z(cz, f, l));
            }
        }
        return new int[]{minX, minZ};
    }

    /** 통나무 막사 — 널벽·초가지붕·짚 잠자리. 살림은 얇다 (tier: poor) */
    private static void barrack(World world, int x0, int cy, int z0, int w, int d, BlockFace door) {
        int x1 = x0 + w - 1;
        int z1 = z0 + d - 1;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                world.getBlockAt(x, cy, z).setType(Material.SPRUCE_PLANKS);   // 마루 (땅에서 뜬다)
                boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                if (!edge) {
                    continue;
                }
                for (int y = cy + 1; y <= cy + 3; y++) {
                    boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                    world.getBlockAt(x, y, z).setType(corner ? Material.SPRUCE_LOG : Material.SPRUCE_PLANKS);
                }
            }
        }
        // 지붕 — **초가**. 짚으로 통째 덮으면 노란 덩어리가 되고 수묵이 깨진다:
        //   초가는 "짚으로 덮은 지붕"이 아니라 **너와 지붕에 짚을 얹은 것**이다.
        int peak = Math.min(w, d) / 2;
        for (int i = 0; i <= peak; i++) {
            int y = cy + 3 + i;
            for (int x = x0 - 1 + i; x <= x1 + 1 - i; x++) {
                thatchStair(world, x, y, z0 - 1 + i, BlockFace.NORTH);
                thatchStair(world, x, y, z1 + 1 - i, BlockFace.SOUTH);
            }
            for (int z = z0 + i; z <= z1 - i; z++) {
                thatchStair(world, x0 - 1 + i, y, z, BlockFace.WEST);
                thatchStair(world, x1 + 1 - i, y, z, BlockFace.EAST);
            }
        }
        for (int x = x0 - 1 + peak; x <= x1 + 1 - peak; x++) {   // 용마루
            put(world, x, cy + 4 + peak, (z0 + z1) / 2, Material.SPRUCE_SLAB);
        }
        int dx = door == BlockFace.EAST ? x1 : door == BlockFace.WEST ? x0 : (x0 + x1) / 2;
        int dz = door == BlockFace.SOUTH ? z1 : door == BlockFace.NORTH ? z0 : (z0 + z1) / 2;
        world.getBlockAt(dx, cy + 1, dz).setType(Material.AIR);
        world.getBlockAt(dx, cy + 2, dz).setType(Material.AIR);
        for (int x = x0 + 1; x <= x1 - 1; x += 2) {
            world.getBlockAt(x, cy + 1, z0 + 1).setType(Material.HAY_BLOCK);   // 짚 잠자리
        }
        world.getBlockAt(x1 - 1, cy + 1, z1 - 1).setType(Material.BARREL);
        lantern(world, (x0 + x1) / 2, cy + 3, (z0 + z1) / 2);   // 대들보 등 하나
    }

    /**
     * 두목 막사 — 큰 막사 + <b>노획 병기 시렁</b>(1.21.11 선반).
     *
     * <p>도적이 무엇으로 먹고사는지는 벽에 걸려 있다: 훔친 병기. 그중엔 표국의 것도 있다.
     */
    private static void chiefHall(World world, int cx, int cy, int cz, Facing fw) {
        boolean swap = fw.swapped();
        int[] box = localBox(cx, cz, fw, -19, -11, -6, 6);   // 깊이 9(전후) x 폭 13(좌우)
        int x0 = box[0];
        int z0 = box[1];
        barrack(world, x0, cy, z0, swap ? 9 : 13, swap ? 13 : 9, fw.out());   // 문은 마당(문 쪽)을 본다
        long seed = Math.floorMod(31L * x0 + z0, 1_000_003L);
        // 노획 병기 시렁 — **뒷벽**(문 맞은편, 국소 f = -19)에 건다. 들어오면 정면에 걸린다.
        //   시렁은 벽에 박히는 블록이므로 벽줄 그 자체에 놓고, 방 안(fw.out())을 보게 한다.
        shelf(world, fw.x(cx, -19, -3), cy + 2, fw.z(cz, -19, -3), fw.out(),
                Weapons.makeSeeded(Weapons.Series.도, Weapons.Grade.범철, seed),
                Weapons.makeSeeded(Weapons.Series.검, Weapons.Grade.정련, seed + 1),   // 표사에게서 뺏은 것
                Weapons.makeSeeded(Weapons.Series.창, Weapons.Grade.범철, seed + 2));
        shelf(world, fw.x(cx, -19, 2), cy + 2, fw.z(cz, -19, 2), fw.out(),
                Weapons.makeSeeded(Weapons.Series.부, Weapons.Grade.범철, seed + 3),
                null,                                                                   // 팔아넘긴 자리
                Weapons.makeSeeded(Weapons.Series.단검, Weapons.Grade.범철, seed + 4));
        world.getBlockAt(fw.x(cx, -15, 0), cy + 1, fw.z(cz, -15, 0)).setType(Material.CHEST);   // 노획 궤
        world.getBlockAt(fw.x(cx, -15, 1), cy + 1, fw.z(cz, -15, 1)).setType(Material.BARREL);
        lantern(world, fw.x(cx, -17, 0), cy + 3, fw.z(cz, -17, 0));
    }

    /**
     * 마당 — <b>모닥불이 중심</b>이다. 나눠 먹는 자리가 이 집의 정체다.
     * 좌표는 전부 국소(f = 문 쪽, l = 좌우) — 배치가 돌아가도 마당의 그림은 같다.
     */
    private static void yard(World world, int cx, int cy, int cz, Facing fw) {
        // 채문 → 마당 동선 — 들어오는 자가 곧장 모닥불을 본다 (아무도 그 앞을 막지 않는다)
        for (int f = 6; f <= R - 1; f++) {
            for (int l = -2; l <= 2; l++) {
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                for (int y = cy + 1; y <= cy + 4; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR);
                }
                world.getBlockAt(x, cy, z).setType(Material.DIRT_PATH);
            }
        }
        world.getBlockAt(cx, cy + 1, cz).setType(Material.CAMPFIRE);
        for (int i = 0; i < 8; i++) {   // 모닥불 둘레 통나무 걸상 (앉는 자리 = 사람이 산다는 증거)
            double a = Math.PI * i / 4.0;
            int x = cx + (int) Math.round(Math.cos(a) * 3);
            int z = cz + (int) Math.round(Math.sin(a) * 3);
            if (world.getBlockAt(x, cy + 1, z).getType().isAir()) {
                world.getBlockAt(x, cy + 1, z).setType(Material.SPRUCE_SLAB);
            }
        }
        // 고기 걸이 · 술통 · 국솥 — 산채의 살림 (얇게)
        for (int l = 5; l <= 7; l++) {
            world.getBlockAt(fw.x(cx, -2, l), cy + 4, fw.z(cz, -2, l)).setType(Material.SPRUCE_FENCE);
            world.getBlockAt(fw.x(cx, -2, l), cy + 3, fw.z(cz, -2, l)).setType(Material.IRON_CHAIN);
        }
        world.getBlockAt(fw.x(cx, -2, 6), cy + 2, fw.z(cz, -2, 6)).setType(Material.HAY_BLOCK);
        world.getBlockAt(fw.x(cx, -2, -6), cy + 1, fw.z(cz, -2, -6)).setType(Material.BARREL);
        world.getBlockAt(fw.x(cx, -2, -7), cy + 1, fw.z(cz, -2, -7)).setType(Material.BARREL);
        world.getBlockAt(fw.x(cx, 2, -6), cy + 1, fw.z(cz, 2, -6)).setType(Material.CAULDRON);   // 국솥
        // 훈련 말뚝 셋 · 장작더미 · 짐수레 — 사람이 사는 마당에는 하던 일의 흔적이 남는다
        for (int i = 0; i < 3; i++) {
            int l = -9 + i * 3;
            int x = fw.x(cx, 8, l);
            int z = fw.z(cz, 8, l);
            world.getBlockAt(x, cy + 1, z).setType(Material.SPRUCE_FENCE);
            world.getBlockAt(x, cy + 2, z).setType(Material.SPRUCE_FENCE);
            stair(world, x, cy + 3, z, Material.SPRUCE_STAIRS, fw.out(), false);
        }
        for (int l = 6; l <= 8; l++) {   // 장작더미 — 패다 만 것
            for (int f = 6; f <= 7; f++) {
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                world.getBlockAt(x, cy + 1, z).setType(Material.SPRUCE_LOG);
                if (Math.floorMod(x * 5 + z * 3, 3) == 0) {
                    world.getBlockAt(x, cy + 2, z).setType(Material.SPRUCE_LOG);
                }
            }
        }
        world.getBlockAt(fw.x(cx, 7, 9), cy + 1, fw.z(cz, 7, 9)).setType(Material.SPRUCE_TRAPDOOR);
        for (int l = -10; l <= -8; l++) {   // 짐수레 — 노획물을 싣는다
            int x = fw.x(cx, -8, l);
            int z = fw.z(cz, -8, l);
            world.getBlockAt(x, cy + 1, z).setType(Material.SPRUCE_SLAB);
            world.getBlockAt(x, cy + 2, z).setType(Math.floorMod(x, 2) == 0
                    ? Material.BARREL : Material.CHEST);
        }
        // 마당의 불 — 모닥불 하나로는 채가 안 밝다 (도적도 밤에 걸어 다닌다).
        //   다만 등롱은 **리듬**이다: 격자로 도배하지 않고 동선(문 → 마당 → 막사)에만 세운다.
        int[][] posts = {{16, -5}, {16, 5}, {9, -12}, {9, 12}, {-6, -12}, {-6, 12}, {-14, 0}};
        for (int[] p : posts) {
            lanternPost(world, fw.x(cx, p[0], p[1]), cy, fw.z(cz, p[0], p[1]));
        }
        // 채기(寨旗) — 채색은 여기뿐이다 (수묵 규칙: 깃발·불빛에만 색을 허락한다)
        int fx = fw.x(cx, 9, 9);
        int fz = fw.z(cz, 9, 9);
        for (int y = cy + 1; y <= cy + 5; y++) {
            world.getBlockAt(fx, y, fz).setType(Material.SPRUCE_FENCE);
        }
        world.getBlockAt(fx, cy + 6, fz).setType(Material.GREEN_WOOL);   // 녹림(綠林) — 푸른 숲
    }

    // ══════════════════════════════════════════════════════════════════
    //  도관(道觀) — 산문 · 천 계단 · 문전 · 본전 · 연무장
    // ══════════════════════════════════════════════════════════════════

    /**
     * 문파의 문법 — <b>오르는 길이 시험이다</b> (등록부: "산문 → 계단 → 문전. 잡역은 문전에서 시작한다").
     *
     * <p>산채는 평평한 한 켜였다. 문파는 <b>두 켜</b>다: 아래 문전(門前)과 위 본전(本殿).
     * 그 사이를 계단이 잇는다 — 걸어 올라가는 동안 사람이 무엇을 보게 되는가가 이 건축의 전부다.
     *
     * <p>v2 — 켜의 높이를 <b>더 이상 우리가 정하지 않는다</b>:
     * <ul>
     *   <li>아래 켜 = {@code spec.groundY()} · 위 켜 = {@code spec.peakY()} (지형이 봉우리를 세웠다)</li>
     *   <li>축은 <b>봉우리를 지난다</b> — 봉우리가 부지 중심에서 밀려 있어도 계단이 정상에 닿는다
     *       (옛 버그: 계단이 정상에 못 닿아 지역 검수의 도달성이 떨어졌다)</li>
     *   <li>계단이 <b>한 칸에 한 칸 넘게 오르지 않도록</b> 위 켜를 낮춘다 — 못 오르는 계단은 벽이다.
     *       부지 반경이 좁으면 본전이 그만큼 내려앉는다 (터가 허락하는 만큼만 오른다)</li>
     *   <li>{@code twoTier()} 가 거짓이면(평지·들) 켜는 하나다 — 산문·마당·본전이 한 평면에 선다</li>
     * </ul>
     */
    private static List<Zone> sect(World world, WorldMap.Place place, TerrainForge.SiteSpec spec) {
        Facing fw = entry(spec);
        int cx = spec.cx();
        int cz = spec.cz();
        int lower = spec.groundY();

        // 축의 원점은 **봉우리**다 (부지 중심이 아니다 — 지형이 봉우리를 북으로 8칸 밀어 두었다).
        int px = spec.peakX();
        int pz = spec.peakZ();
        // 봉우리가 축 위에서 얼마나 뒤(-f)에 있는가 — 문전·산문은 부지 중심 기준으로 잡아야 반경 안에 든다
        int peakF = (px - cx) * fw.fx() + (pz - cz) * fw.fz();

        // 【배치의 못 — 구역 가장자리 두 칸은 비워 둔다】
        //   지역 검수의 도달성 BFS 는 구역 남쪽 가장자리(z2-2)의 **가장 높은 설 자리**에서 출발한다.
        //   구 배치는 산문(gateF = rad-2)을 하필 그 열에 세워 사람이 **기와 위에서 출발**했고,
        //   지붕 24칸을 돌다 갇혔다 (검수: 걸어 닿은 칸 24 · 오른 최고 y78 = 산문 기와). 계단은 멀쩡했다.
        //   이제 마당·산문을 안으로 물리고, 가장자리에는 **맨 진입로**만 깐다.
        int rad = spec.radius();
        int courtF = rad - 14;                // 문전 마당 중심 — 가장자리에서 열넷 안쪽
        int gateF = courtF + 6;               // 산문 — 문전 마당의 앞턱 (rad-8: 가장자리 두 칸에서 물러섰다)
        int stepFrom = peakF + UPPER_HALF_D + 1;   // 계단 시작 (본전 단의 앞턱)
        int stepTo = courtF - 6;                   // 계단 끝 (문전 마당의 뒷턱)
        int run = stepTo - stepFrom;

        // 오를 수 있는 만큼만 오른다 — 한 칸에 한 칸이 한계고, 그 위에 여유 넷을 둔다.
        //   못 오르는 계단은 계단이 아니라 벽이다. 터가 좁으면 본전이 그만큼 내려앉는다.
        boolean climb = spec.twoTier() && run >= 8;
        int rise = climb ? Math.max(0, Math.min(spec.peakY() - lower, run - 4)) : 0;
        int upper = lower + rise;

        // 【단】 두 켜 — 깎기·축대·봉인은 전부 지형 계층의 손이다 (우리는 요청만 한다)
        TerrainForge.terrace(world, spec, px, pz, upper,
                fw.swapped() ? UPPER_HALF_D : UPPER_HALF_W,
                fw.swapped() ? UPPER_HALF_W : UPPER_HALF_D, Material.POLISHED_ANDESITE);
        TerrainForge.terrace(world, spec, fw.x(cx, courtF, 0), fw.z(cz, courtF, 0), lower,
                fw.swapped() ? 6 : 8, fw.swapped() ? 8 : 6, Material.POLISHED_ANDESITE);

        // 산문 앞 진입로 — 마당 앞턱(gateF+1)에서 구역 밖까지. **지붕 없는 맨 길**이라
        //   검수의 걸음이 여기서 출발해 문전으로 걸어 들어온다 (도달성 ①의 첫 걸음).
        approachPath(world, spec, cx, cz, fw, gateF + 1, rad + 16, lower, 2, Material.POLISHED_ANDESITE);
        mountainGate(world, cx, lower, cz, fw, gateF);                     // 산문(山門) — 패방
        if (upper > lower) {
            thousandSteps(world, spec, cx, cz, fw, stepFrom, stepTo, upper, lower);
        }
        mainHall(world, px, pz, upper, fw);                                // 본전 — 회벽·검은 기와
        trainingGround(world, px, upper, pz, fw);                          // 연무장 — 오르면 먼저 보인다
        sectLanterns(world, cx, cz, fw, px, pz, upper, lower, courtF);
        plumTrees(world, px, upper, pz, fw);                               // 매화 — 채색은 여기뿐이다

        int lo = Math.min(lower, upper) - 8;
        int hi = Math.max(lower, upper) + 18;
        return List.of(new Zone(place.name(), sectSubtitle(place), world.getName(),
                cx - rad, lo, cz - rad, cx + rad, hi, cz + rad));
    }

    /** 본전 단 — 봉우리 정상의 마당 (폭 27 x 깊이 21) */
    private static final int UPPER_HALF_W = 13;
    private static final int UPPER_HALF_D = 10;

    private static String sectSubtitle(WorldMap.Place place) {
        return "rich".equals(place.tier()) ? "도관 — 산문에서 본전까지 천 계단"
                : "산문 — 오르는 길이 시험이다";
    }

    /** 산문(山門) — 패방(牌坊). 돌기둥 넷 + 현판 + 검은 기와. 여기서부터 문파의 땅이다 */
    private static void mountainGate(World world, int cx, int cy, int cz, Facing fw, int gf) {
        for (int l : new int[]{-4, -3, 3, 4}) {
            for (int y = cy + 1; y <= cy + 5; y++) {
                world.getBlockAt(fw.x(cx, gf, l), y, fw.z(cz, gf, l)).setType(Material.STONE_BRICKS);
            }
        }
        for (int l = -5; l <= 5; l++) {   // 들보 + 기와 처마
            world.getBlockAt(fw.x(cx, gf, l), cy + 6, fw.z(cz, gf, l)).setType(Material.POLISHED_ANDESITE);
            world.getBlockAt(fw.x(cx, gf, l), cy + 7, fw.z(cz, gf, l)).setType(Material.DEEPSLATE_TILES);
        }
        for (int l = -6; l <= 6; l++) {
            stair(world, fw.x(cx, gf - 1, l), cy + 7, fw.z(cz, gf - 1, l),
                    Material.DEEPSLATE_TILE_STAIRS, fw.in(), false);
            stair(world, fw.x(cx, gf + 1, l), cy + 7, fw.z(cz, gf + 1, l),
                    Material.DEEPSLATE_TILE_STAIRS, fw.out(), false);
        }
        world.getBlockAt(fw.x(cx, gf, 0), cy + 5, fw.z(cz, gf, 0)).setType(Material.DARK_OAK_PLANKS);   // 현판
    }

    /**
     * 천 계단 — 문전에서 본전으로. 한 켜씩 올라가며 폭이 좁아진다 (오를수록 좁아지는 길).
     *
     * <p>한 단은 <b>한 칸 이하</b>로만 오른다 — 오를 수 없는 계단은 계단이 아니라 벽이다.
     * 단마다 지형 계층에 한 칸짜리 단을 요청한다(깎기·축대·봉인이 함께 온다).
     */
    private static void thousandSteps(World world, TerrainForge.SiteSpec spec, int cx, int cz,
                                      Facing fw, int fFrom, int fTo, int yHigh, int yLow) {
        int run = fTo - fFrom;
        for (int i = 0; i <= run; i++) {
            int f = fTo - i;                                        // 문전(먼 쪽)에서 본전(가까운 쪽)으로
            int y = yLow + (int) Math.round((double) (yHigh - yLow) * i / run);
            int half = Math.max(2, 5 - i / 5);                      // 폭 11 → 5 (오를수록 좁아진다)
            for (int l = -half; l <= half; l++) {
                TerrainForge.terrace(world, spec, fw.x(cx, f, l), fw.z(cz, f, l), y, 0, 0,
                        Material.POLISHED_ANDESITE);
            }
            // 석등은 **길 안쪽 가장자리**에 세운다. 길 밖(바위 쪽)에 세우면 등이 바위에 박혀 빛이 길로
            //   못 나온다 — 눈에 보이는 등과 밝은 길은 다른 것이다.
            //   간격 2 → 4: 등롱은 리듬이지 도배가 아니다 (사용자 피드백). 다만 **오르는 길은 밝아야 한다** —
            //   등롱(y+2)에서 계단 한복판까지 측거 half(≤5) + 세로 ≤2 + 높이 1 = 8 → 광원 7 (밝음의 문턱).
            if (Math.floorMod(i, 4) == 0) {
                for (int l : new int[]{-half, half}) {
                    world.getBlockAt(fw.x(cx, f, l), y + 1, fw.z(cz, f, l)).setType(Material.COBBLESTONE_WALL);
                    world.getBlockAt(fw.x(cx, f, l), y + 2, fw.z(cz, f, l)).setType(Material.LANTERN);
                }
            }
        }
    }

    /** 본전(本殿) — 회벽·검은 기와. 청하현 관아의 어휘다 (도적의 통나무와 다른 것이 위계다) */
    private static void mainHall(World world, int px, int pz, int cy, Facing fw) {
        boolean swap = fw.swapped();
        int[] box = localBox(px, pz, fw, -10, 2, -7, 7);   // 깊이 13(전후) x 폭 15(좌우)
        int x0 = box[0];
        int z0 = box[1];
        int w = swap ? 13 : 15;
        int d = swap ? 15 : 13;
        int x1 = x0 + w - 1;
        int z1 = z0 + d - 1;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                world.getBlockAt(x, cy, z).setType(Material.POLISHED_ANDESITE);
                boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                if (!edge) {
                    continue;
                }
                boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                for (int y = cy + 1; y <= cy + 4; y++) {
                    world.getBlockAt(x, y, z).setType(corner ? Material.DARK_OAK_LOG
                            : Material.WHITE_TERRACOTTA);   // 회벽 · 기둥은 목재
                }
            }
        }
        // 문 — 계단이 오는 쪽. 삼문(三門)이 아니라 하나 (문파의 본전은 겸손하다)
        for (int i = -1; i <= 1; i++) {
            int dx = fw.out() == BlockFace.EAST || fw.out() == BlockFace.WEST
                    ? (fw.out() == BlockFace.EAST ? x1 : x0) : (x0 + x1) / 2 + i;
            int dz = fw.out() == BlockFace.SOUTH || fw.out() == BlockFace.NORTH
                    ? (fw.out() == BlockFace.SOUTH ? z1 : z0) : (z0 + z1) / 2 + i;
            for (int y = cy + 1; y <= cy + 3; y++) {
                world.getBlockAt(dx, y, dz).setType(Material.AIR);
            }
        }
        // 지붕 — 검은 기와, 능선 수렴 (청하현 검수가 요구하는 물매)
        int peak = Math.min(w, d) / 2;
        for (int i = 0; i <= peak; i++) {
            int y = cy + 5 + i;
            for (int x = x0 - 1 + i; x <= x1 + 1 - i; x++) {
                put(world, x, y, z0 - 1 + i, Material.DEEPSLATE_TILES);
                put(world, x, y, z1 + 1 - i, Material.DEEPSLATE_TILES);
            }
            for (int z = z0 + i; z <= z1 - i; z++) {
                put(world, x0 - 1 + i, y, z, Material.DEEPSLATE_TILES);
                put(world, x1 + 1 - i, y, z, Material.DEEPSLATE_TILES);
            }
        }
        // 실내 — 제단(향로)과 시렁. 본전에 걸린 것은 노획물이 아니라 **전승의 병기**다
        int mx = (x0 + x1) / 2;
        int mz = (z0 + z1) / 2;
        world.getBlockAt(mx, cy + 1, z0 + 1).setType(Material.CAULDRON);        // 향로
        candlesAt(world, mx - 1, cy + 1, z0 + 1);
        candlesAt(world, mx + 1, cy + 1, z0 + 1);
        long seed = Math.floorMod(31L * x0 + z0, 1_000_003L);
        shelf(world, mx - 3, cy + 2, z0 + 1, BlockFace.SOUTH,
                Weapons.makeSeeded(Weapons.Series.검, Weapons.Grade.보병, seed),
                null,
                Weapons.makeSeeded(Weapons.Series.검, Weapons.Grade.정련, seed + 1));
        lantern(world, mx, cy + 4, mz);
    }

    /** 연무장 — 돌바닥 + 목인장 셋. 오르면 먼저 이것이 보인다 (문파는 무엇을 하는 집인가) */
    private static void trainingGround(World world, int px, int cy, int pz, Facing fw) {
        for (int f = 4; f <= 10; f++) {
            for (int l = -8; l <= 8; l++) {
                int x = fw.x(px, f, l);
                int z = fw.z(pz, f, l);
                world.getBlockAt(x, cy, z).setType(Math.floorMod(x + z, 7) == 0
                        ? Material.ANDESITE : Material.POLISHED_ANDESITE);
            }
        }
        for (int i = -1; i <= 1; i++) {   // 목인장(木人樁) — 팔이 셋 달린 나무 사람
            int x = fw.x(px, 7, i * 6);
            int z = fw.z(pz, 7, i * 6);
            for (int y = cy + 1; y <= cy + 3; y++) {
                world.getBlockAt(x, y, z).setType(Material.STRIPPED_DARK_OAK_LOG);
            }
            world.getBlockAt(fw.x(px, 7, i * 6 - 1), cy + 2, fw.z(pz, 7, i * 6 - 1))
                    .setType(Material.DARK_OAK_FENCE);   // 팔
            world.getBlockAt(fw.x(px, 7, i * 6 + 1), cy + 2, fw.z(pz, 7, i * 6 + 1))
                    .setType(Material.DARK_OAK_FENCE);
            world.getBlockAt(fw.x(px, 8, i * 6), cy + 2, fw.z(pz, 8, i * 6))
                    .setType(Material.DARK_OAK_FENCE);
        }
    }

    /**
     * 문파의 불 — 정상 마당·문전. 계단만 밝혀서는 밤에 못 다닌다.
     * 다만 <b>격자로 도배하지 않는다</b> — 등은 동선(산문 · 마당 네 귀 · 본전 앞)에만 선다.
     */
    private static void sectLanterns(World world, int cx, int cz, Facing fw,
                                     int px, int pz, int upper, int lower, int courtF) {
        int[][] top = {{5, -10}, {5, 10}, {-5, -12}, {-5, 12}, {10, -6}, {10, 6}};   // 정상 마당 (본전 벽을 피한다)
        for (int[] p : top) {
            lanternPost(world, fw.x(px, p[0], p[1]), upper, fw.z(pz, p[0], p[1]));
        }
        int[][] court = {{-5, -6}, {-5, 6}, {5, -6}, {5, 6}};              // 문전 마당 네 귀
        for (int[] p : court) {
            lanternPost(world, fw.x(cx, courtF + p[0], p[1]), lower, fw.z(cz, courtF + p[0], p[1]));
        }
    }

    /** 매화 — 채색은 여기뿐이다 (등록부가 cherry_grove 를 적어 두었다). 본전 마당 모서리에 셋 */
    private static void plumTrees(World world, int px, int cy, int pz, Facing fw) {
        int[][] spots = {{-6, -11}, {-6, 11}, {6, -11}};   // 국소 (f, l)
        for (int[] s : spots) {
            int x = fw.x(px, s[0], s[1]);
            int z = fw.z(pz, s[0], s[1]);
            for (int y = cy + 1; y <= cy + 4; y++) {
                world.getBlockAt(x, y, z).setType(Material.CHERRY_LOG);
            }
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > 3) {
                        continue;
                    }
                    put(world, x + dx, cy + 5, z + dz, Material.CHERRY_LEAVES);
                    if (Math.abs(dx) + Math.abs(dz) <= 1) {
                        put(world, x + dx, cy + 6, z + dz, Material.CHERRY_LEAVES);
                    }
                }
            }
        }
    }

    /** 석등 — 돌기둥 + 등롱. 이미 뭔가 선 자리에는 서지 않는다 (등이 벽에 박히면 빛이 길로 안 나온다) */
    private static void lanternPost(World world, int x, int cy, int z) {
        for (int y = cy + 1; y <= cy + 3; y++) {
            if (!world.getBlockAt(x, y, z).getType().isAir()) {
                return;
            }
        }
        world.getBlockAt(x, cy + 1, z).setType(Material.COBBLESTONE_WALL);
        world.getBlockAt(x, cy + 2, z).setType(Material.COBBLESTONE_WALL);
        world.getBlockAt(x, cy + 3, z).setType(Material.LANTERN);
    }

    private static void candlesAt(World world, int x, int y, int z) {
        org.bukkit.block.data.type.Candle data =
                (org.bukkit.block.data.type.Candle) Material.CANDLE.createBlockData();
        data.setCandles(2);
        data.setLit(true);
        world.getBlockAt(x, y, z).setBlockData(data);
    }

    // ══════════════════════════════════════════════════════════════════
    //  은신처(隱身處) — 마교 전초 · 혈교 은거지. <b>동굴이 본체다</b>
    // ══════════════════════════════════════════════════════════════════

    /**
     * 은신처의 문법 — <b>이 집은 없는 것으로 자기를 말한다</b>.
     *
     * <p>등록부(magyo_jinryeong)가 architecture 에 적어 둔 한 줄이 이 원형의 전부다:
     * <b>"동굴과 석실 — 사람이 사는 흔적을 지운다"</b>. 그래서 다른 원형이 가진 것을 <b>전부 안 가진다</b>:
     * <ul>
     *   <li><b>담이 없다</b> — 목책도 성벽도 없다. 둘러싸면 거기 뭔가 있다는 뜻이다</li>
     *   <li><b>등롱이 없다</b> — 지상에 광원을 한 기도 세우지 않는다. 밤에 산을 보면 <b>아무것도 없다</b></li>
     *   <li><b>깃발이 없다</b> — 세력의 표식이 있으면 그것은 은신처가 아니라 본거지다</li>
     *   <li><b>기와도 회벽도 없다</b> — 격을 말하는 자재는 격을 드러낸다</li>
     * </ul>
     *
     * <p>대신 둘을 가진다:
     * <ol>
     *   <li><b>지상 — 위장</b>. 등록부의 지형이 위장을 고른다:
     *       폐허 → <b>무너진 사당</b> · 산 → <b>바위 틈</b> · 그 밖(밀림·들) → <b>나무꾼 오두막</b>.
     *       셋 다 "여기 왜 사람 손이 닿았나"에 <b>거짓 답</b>을 준다 — 그것이 위장이다</li>
     *   <li><b>굴 안 — 살림</b>. 제단·거처·궤. <b>광원은 촛불뿐</b>이다
     *       (동굴 원형의 계약: "어둠이 동굴의 값이다" — 빛을 들고 들어가는 것이 사건이다)</li>
     * </ol>
     *
     * <p><b>굴이 없으면 짓지 않는다.</b> 지상부는 굴 입구를 감추는 물건이고, 감출 입구가 없으면
     * 그것은 그냥 오두막이다. 굴의 좌표는 {@link TerrainForge.CaveSpec} 만이 안다 —
     * 입구는 부지 <b>밖 24칸</b>까지 나갈 수 있어서 부지 중심으로는 찾을 수 없다.
     */
    private static List<Zone> hideout(World world, WorldMap.Place place, TerrainForge.SiteSpec spec,
                                      TerrainForge.CaveSpec cave) {
        if (cave == null) {
            return List.of();   // 굴이 본체다 — 굴 없이는 이 원형이 성립하지 않는다
        }
        int cx = spec.cx();
        int cz = spec.cz();
        int cy = spec.groundY();
        int mx = cave.mouthX();
        int my = cave.mouthY();
        int mz = cave.mouthZ();
        // 입구의 국소 좌표계 — f 가 커지면 <b>굴 밖</b>(비탈 아래). into() 는 파고 들어간 방위다
        Facing mf = Facing.of(cave.into().getOppositeFace());
        long seed = Math.floorMod(31L * mx + 17L * mz, 1_000_003L);

        String terrain = place.terrain() == null ? "" : place.terrain();
        if ("폐허".equals(terrain)) {
            ruinedShrine(world, spec, mx, my, mz, mf);        // 폐사 — 신상이 없는 제단이 위장이다
        } else if (spec.mountain()) {
            rockCleft(world, spec, mx, my, mz, mf);           // 바위 틈 — 손을 가장 적게 댄 위장
        } else {
            woodcutterHut(world, spec, mx, my, mz, mf);       // 나무꾼 오두막 — 살아 있는 위장
        }
        caveDwelling(world, cave, seed);                      // 굴 안의 살림

        // 구역 — 굴 입구는 부지 밖 24칸까지 나간다. 구역이 그것을 덮어야 검수의 눈이 굴을 본다.
        //   중심은 여전히 부지 중심이다 (검수의 도달성 BFS 가 중심을 목표로 잡는다 — 거기가 걸을 수 있는 땅이다)
        int half = spec.radius() + 30;
        int deep = Math.min(cave.roomY(), my) - 10;
        return List.of(new Zone(place.name(), "동굴과 석실 — 사람이 사는 흔적을 지운다", world.getName(),
                cx - half, deep, cz - half, cx + half, cy + 24, cz + half));
    }

    /**
     * 위장 ① <b>바위 틈</b> — 산의 위장은 <b>손을 대지 않는 것</b>이다.
     *
     * <p>비탈에 굴을 파면 살이 터진다. 그 터진 살을 <b>그대로 둔 것처럼</b> 만든다:
     * 입구 좌우에 무너져 내린 바위, 그 위에 걸린 처마 바위, 입구를 가로질러 쓰러진 나무 한 그루.
     * 사람의 손이라고는 <b>바위를 치우지 않은 것</b>뿐이다 (치우면 길이 되고, 길은 사람을 부른다).
     */
    private static void rockCleft(World world, TerrainForge.SiteSpec spec,
                                  int mx, int my, int mz, Facing mf) {
        for (int side : new int[]{-1, 1}) {   // 입구 양옆으로 무너진 돌무더기
            for (int f = -1; f <= 4; f++) {
                for (int l = 2; l <= 5; l++) {
                    int x = mf.x(mx, f, l * side);
                    int z = mf.z(mz, f, l * side);
                    int g = groundOf(world, spec, x, z, my);
                    int h = Math.floorMod(x * 31 + z * 17, 4);   // 0~3켜 — 자로 잰 무더기는 무더기가 아니다
                    for (int y = g + 1; y <= g + h; y++) {
                        put(world, x, y, z, rubble(x, y, z));
                    }
                }
            }
        }
        for (int l = -3; l <= 3; l++) {   // 처마 바위 — 위에서 보면 그늘뿐이다 (하늘에서 굴이 안 보인다)
            put(world, mf.x(mx, -1, l), my + 4, mf.z(mz, -1, l), Material.STONE);
            put(world, mf.x(mx, 0, l), my + 4, mf.z(mz, 0, l), Material.COBBLESTONE);
            put(world, mf.x(mx, 1, l), my + 5, mf.z(mz, 1, l), Material.STONE);
        }
        for (int l = -4; l <= 4; l++) {   // 쓰러진 나무 — 입구 앞을 가로지른다 (넘어가야 보인다)
            logAxis(world, mf.x(mx, 5, l), groundOf(world, spec, mf.x(mx, 5, l), mf.z(mz, 5, l), my) + 1,
                    mf.z(mz, 5, l), Material.SPRUCE_LOG, mf.swapped()
                            ? org.bukkit.Axis.Z : org.bukkit.Axis.X);
        }
    }

    /**
     * 위장 ② <b>나무꾼 오두막</b> — 밀림·들의 위장은 <b>살아 있어야</b> 한다.
     *
     * <p>산에서는 안 치우는 것이 위장이지만, 사람이 다니는 땅에서는 <b>아무것도 없는 자리</b>가 오히려 눈에 띈다.
     * 그래서 여기엔 사는 사람을 만든다: 통나무 오두막 한 채, 패다 만 장작, 모닥불, 그루터기.
     * <b>장작더미가 굴 입구를 가린다</b> — 나무꾼이 장작을 쌓아 둔 자리가 하필 거기다.
     */
    private static void woodcutterHut(World world, TerrainForge.SiteSpec spec,
                                      int mx, int my, int mz, Facing mf) {
        int hx = mf.x(mx, 11, 3);
        int hz = mf.z(mz, 11, 3);
        int hy = groundOf(world, spec, hx, hz, my);
        // 마당 — 단은 땅의 일이다 (깎기·축대·봉인이 함께 온다).
        //   바닥은 **자갈**이다. 다진 흙(노면)을 깔면 그것은 <b>길</b>이고, 길은 사람을 부른다 —
        //   은신처에는 길이 없다 (검수 ④의 노면 표본에도 잡히지 않는다. 잴 길이 없는 것이 이 집의 답이다).
        TerrainForge.terrace(world, spec, hx, hz, hy, 6, 6, Material.GRAVEL);
        boolean swap = mf.swapped();
        int hw = swap ? 5 : 7;
        int hd = swap ? 7 : 5;
        barrack(world, hx - hw / 2, hy, hz - hd / 2, hw, hd, mf.out());   // 오두막 — 살림은 얇다

        // 장작더미 — **입구와 오두막 사이**에 쌓는다. 나무꾼의 살림이 곧 굴의 가림막이다
        for (int f = 4; f <= 7; f++) {
            for (int l = -3; l <= 1; l++) {
                int x = mf.x(mx, f, l);
                int z = mf.z(mz, f, l);
                int g = groundOf(world, spec, x, z, my);
                int h = 1 + Math.floorMod(x * 5 + z * 3, 3);   // 1~3켜
                for (int y = g + 1; y <= g + h; y++) {
                    logAxis(world, x, y, z, Material.SPRUCE_LOG,
                            swap ? org.bukkit.Axis.Z : org.bukkit.Axis.X);
                }
            }
        }
        // 마당의 불 — 위장은 밤에도 위장이어야 한다 (불이 꺼진 나무꾼 집은 빈 집이고, 빈 집은 들여다본다)
        int fx = mf.x(mx, 12, -3);
        int fz = mf.z(mz, 12, -3);
        world.getBlockAt(fx, hy, fz).setType(Material.COBBLESTONE);
        world.getBlockAt(fx, hy + 1, fz).setType(Material.CAMPFIRE);
        // 그루터기와 도끼 자국 — 팬 나무는 저쪽이고, 판 굴은 이쪽이다
        int sx = mf.x(mx, 10, -5);
        int sz = mf.z(mz, 10, -5);
        world.getBlockAt(sx, hy + 1, sz).setType(Material.SPRUCE_LOG);
        world.getBlockAt(mf.x(mx, 9, -5), hy + 1, mf.z(mz, 9, -5)).setType(Material.SPRUCE_SLAB);
    }

    /**
     * 위장 ③ <b>무너진 사당</b> — 폐허의 위장은 <b>이미 있던 거짓말</b>이다.
     *
     * <p>등록부는 이 자리를 이미 폐허로 적어 두었다(hyeolgyo_jedan: "폐허·금기 장소에 남는다").
     * 그러니 사당을 새로 무너뜨릴 것도 없다 — <b>무너진 채로 세우면</b> 굴은 그 뒤뜰의 갈라진 틈이 된다.
     * 청하현 폐사당의 어휘를 그대로 쓴다(금 간 돌벽돌·이끼·거미줄·냉색). <b>세계는 하나다.</b>
     */
    private static void ruinedShrine(World world, TerrainForge.SiteSpec spec,
                                     int mx, int my, int mz, Facing mf) {
        int sx = mf.x(mx, 11, 0);
        int sz = mf.z(mz, 11, 0);
        int sy = groundOf(world, spec, sx, sz, my);
        boolean swap = mf.swapped();
        int halfW = swap ? 4 : 5;
        int halfD = swap ? 5 : 4;
        TerrainForge.terrace(world, spec, sx, sz, sy, halfW, halfD, Material.STONE_BRICKS);   // 기단

        // 잔벽 — **반만 선다**. 남은 벽이 무너진 벽을 말한다
        for (int f = -4; f <= 4; f++) {
            for (int l = -5; l <= 5; l++) {
                boolean edge = Math.abs(f) == 4 || Math.abs(l) == 5;
                if (!edge) {
                    continue;
                }
                int x = mf.x(sx, f, l);
                int z = mf.z(sz, f, l);
                int h = Math.floorMod(x * 7 + z * 13, 5);   // 0~4켜 — 들쭉날쭉한 것이 폐허다
                for (int y = sy + 1; y <= sy + h; y++) {
                    put(world, x, y, z, weathered(x, y, z));
                }
            }
        }
        // 쓰러진 기둥 둘 — 세운 것이 아니라 누운 것
        for (int l : new int[]{-3, 3}) {
            for (int f = -2; f <= 1; f++) {
                logAxis(world, mf.x(sx, f, l), sy + 1, mf.z(sz, f, l), Material.STRIPPED_OAK_LOG,
                        swap ? org.bukkit.Axis.Z : org.bukkit.Axis.X);
            }
        }
        // 제단 — **신상이 없다**. 없는 것이 최대의 소품이다 (청하현 폐사당의 규약 계승)
        for (int l = -2; l <= 2; l++) {
            put(world, mf.x(sx, -3, l), sy + 1, mf.z(sz, -3, l), Material.MOSSY_STONE_BRICKS);
            put(world, mf.x(sx, -3, l), sy + 2, mf.z(sz, -3, l), Material.CRACKED_STONE_BRICKS);
        }
        world.getBlockAt(mf.x(sx, -3, 0), sy + 3, mf.z(sz, -3, 0)).setType(Material.CAULDRON);   // 향로
        soulLantern(world, mf.x(sx, -3, -2), sy + 3, mf.z(sz, -3, -2));   // 냉색 — 폐사의 유일한 불
        for (int i = 0; i < 6; i++) {   // 거미줄 — 좌표 상수 (난수 금지)
            int[][] webs = {{-3, -4}, {-1, 4}, {2, -4}, {3, 3}, {0, -5}, {-4, 1}};
            put(world, mf.x(sx, webs[i][0], webs[i][1]), sy + 3, mf.z(sz, webs[i][0], webs[i][1]),
                    Material.COBWEB);
        }
        // 무너진 담 — 사당 뒤뜰에서 굴 입구까지 이어진다. 굴은 <b>담 뒤의 갈라진 틈</b>으로 보인다
        for (int f = 4; f <= 9; f++) {
            for (int l : new int[]{-4, 4}) {
                int x = mf.x(mx, f, l);
                int z = mf.z(mz, f, l);
                int g = groundOf(world, spec, x, z, my);
                int h = Math.floorMod(x * 11 + z * 5, 3);   // 0~2켜 — 담은 이미 무너졌다
                for (int y = g + 1; y <= g + h; y++) {
                    put(world, x, y, z, Material.MUD_BRICKS);
                }
            }
        }
    }

    /**
     * <b>굴 안의 살림</b> — 땅은 굴을 팠고(빈 돌), 사람은 그 안에 <b>산다</b>.
     *
     * <p>동굴 원형의 계약(docs/design/cave_archetypes.md): <b>"제단·궤·짚자리·시렁은 집의 일이다."</b>
     * 그 계약의 이쪽 절반이 여기다. 그리고 계약의 다른 한 줄을 지킨다 —
     * <b>"광원이 없다. 어둠이 동굴의 값이다."</b> 그래서 등롱도 횃불도 놓지 않는다.
     * 촛불 몇 자루가 전부고, 그것은 <b>제단에만</b> 있다. 통로는 <b>완전한 어둠</b>이다 —
     * 그 어둠을 걸어 들어오는 것이 이 집을 만나는 방식이다.
     *
     * <p>방의 <b>깊은 쪽</b>(입구 반대편)이 제단이고, 곁방(석실)이 거처다.
     * 곁방이 없는 원형(제단굴)이면 거처는 방의 <b>얕은 쪽</b>에 눕는다.
     */
    private static void caveDwelling(World world, TerrainForge.CaveSpec cave, long seed) {
        int rx = cave.roomX();
        int ry = cave.roomY();   // 방의 첫 공기 켜 = 사람이 딛는 높이 (그 아래가 바닥이다)
        int rz = cave.roomZ();
        int dx = rx - cave.mouthX();
        int dz = rz - cave.mouthZ();
        // 방의 깊은 쪽 — 온 길의 반대. 들어서면 <b>정면에</b> 제단이 있다
        BlockFace deep = Math.abs(dx) >= Math.abs(dz)
                ? (dx > 0 ? BlockFace.EAST : BlockFace.WEST)
                : (dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
        Facing df = Facing.of(deep);
        int reach = Math.max(2, cave.kind().roomX - 3);
        int ax = df.x(rx, reach, 0);
        int az = df.z(rz, reach, 0);

        // ─ 제단 — 심층암 벽돌. 굴의 돌(화강암·심층암)과 같은 계열이되 **자른 돌**이다.
        //   자연이 놓은 돌과 사람이 자른 돌의 차이 — 그 한 겹이 여기가 사람의 자리라는 증거다
        for (int f = -1; f <= 1; f++) {
            for (int l = -2; l <= 2; l++) {
                putOn(world, df.x(ax, f, l), ry, df.z(az, f, l), Material.DEEPSLATE_BRICKS);
            }
        }
        for (int l = -1; l <= 1; l++) {
            put(world, df.x(ax, 1, l), ry + 1, df.z(az, 1, l), Material.DEEPSLATE_BRICKS);
            put(world, df.x(ax, 1, l), ry + 2, df.z(az, 1, l), Material.DEEPSLATE_BRICKS);   // 감실(龕室)
        }
        put(world, df.x(ax, 0, 0), ry + 1, df.z(az, 0, 0), Material.CAULDRON);               // 향로
        candlesAt(world, df.x(ax, 0, -2), ry + 1, df.z(az, 0, -2));                          // 촛불 — 굴의 유일한 불
        candlesAt(world, df.x(ax, 0, 2), ry + 1, df.z(az, 0, 2));
        candlesAt(world, df.x(ax, 1, 0), ry + 3, df.z(az, 1, 0));                            // 감실 위 한 자루
        // 시렁 — 걸린 것은 <b>마병</b>이다. 산채의 시렁엔 노획물이, 본전의 시렁엔 전승이,
        //   여기엔 **부러지지 않는 병기**가 걸린다 (Weapons.Grade.마병 — "혈교 잔재. 부러지지 않는다")
        shelf(world, df.x(ax, 1, -2), ry + 1, df.z(az, 1, -2), deep.getOppositeFace(),
                Weapons.makeSeeded(Weapons.Series.도, Weapons.Grade.마병, seed),
                null,
                Weapons.makeSeeded(Weapons.Series.단검, Weapons.Grade.정련, seed + 1));
        // ★ 두 번째 시렁 — 예전엔 여기에 **검+마병**을 걸었고, 그것이 조성 전체를 죽였다
        //   (`Weapons.make`: "마병은 도(刀)에만 존재한다"). 바로 윗줄 주석이 그 규칙을 적어 두고도
        //   다음 줄에서 어겼다 — **주석은 규칙을 지키지 않는다. 코드가 지킨다.**
        //   (그래서 마교 진령은 조성될 때마다 예외로 죽었고, 다섯 지역 중 유일하게 서지 못했다.)
        //
        //   대신 노획한 검을 건다. 마교는 남의 것을 빼앗아 쓴다 — 걸린 검이 정파의 것이라는 사실 자체가
        //   이 굴이 무엇을 했는지 말한다. 마병(도)은 첫 시렁에 이미 걸려 있다.
        shelf(world, df.x(ax, 1, 2), ry + 1, df.z(az, 1, 2), deep.getOppositeFace(),
                Weapons.makeSeeded(Weapons.Series.검, Weapons.Grade.정련, seed + 2),   // 빼앗은 것
                null,
                null);
        putOn(world, df.x(ax, -2, -3), ry, df.z(az, -2, -3), Material.DECORATED_POT);
        putOn(world, df.x(ax, -2, 3), ry, df.z(az, -2, 3), Material.DECORATED_POT);

        // ─ 거처 — 곁방(석실)이 있으면 거기, 없으면 방의 얕은 쪽
        int[] cell = sideRoom(world, cave);
        int qx = cell != null ? cell[0] : df.x(rx, -reach, 0);
        int qy = cell != null ? cell[1] : ry;
        int qz = cell != null ? cell[2] : df.z(rz, -reach, 0);
        for (int i = -1; i <= 1; i++) {   // 짚자리 셋 — 살림은 얇다 (tier: none)
            putOn(world, qx + i, qy, qz - 1, Material.HAY_BLOCK);
        }
        putOn(world, qx - 2, qy, qz + 1, Material.CHEST);
        putOn(world, qx + 2, qy, qz + 1, Material.BARREL);
        putOn(world, qx, qy, qz + 2, Material.BARREL);
        putOn(world, qx + 1, qy, qz + 2, Material.CHISELED_BOOKSHELF);   // 경전 시렁
        candlesAt(world, qx - 1, qy, qz + 1);
        // 거미줄 — 사람이 사는 흔적을 지운 자리에는 거미가 산다
        int[][] webs = {{-3, -3}, {3, -3}, {-3, 3}, {3, 3}, {0, -4}};
        for (int[] w : webs) {
            put(world, qx + w[0], qy + 2, qz + w[1], Material.COBWEB);
        }
    }

    /**
     * 곁방(석실)의 자리 — <b>굴에게 묻는다</b>.
     *
     * <p>{@link TerrainForge.CaveSpec} 은 방의 중심만 준다. 곁방은 방에서 <b>옆으로</b> 열두 칸 빠지는데,
     * 그 방위는 통로가 굽어 온 방향에 달렸고 우리는 그 굽이를 모른다. 그래서 <b>파낸 자리를 읽는다</b>:
     * 방 둘레 10~16칸의 공기 칸 중 <b>온 길(입구 쪽)이 아닌 것</b>들의 무게중심이 곧 곁방이다.
     * (블록을 읽을 뿐 한 칸도 놓지 않는다 — 땅을 만지는 것이 아니다.)
     */
    private static int[] sideRoom(World world, TerrainForge.CaveSpec cave) {
        if (!cave.kind().sideRoom) {
            return null;   // 곁방이 없는 원형 (기연굴·산적굴·제단굴)
        }
        int rx = cave.roomX();
        int ry = cave.roomY();
        int rz = cave.roomZ();
        int mdx = cave.mouthX() - rx;   // 방 → 입구 (온 길)
        int mdz = cave.mouthZ() - rz;
        long n = 0;
        long sx = 0;
        long sz = 0;
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                double d = dist(dx, dz);
                if (d < 10 || d > 16) {
                    continue;
                }
                if (dx * mdx + dz * mdz > 0) {
                    continue;   // 온 길 쪽 — 그건 통로지 곁방이 아니다
                }
                if (!world.getBlockAt(rx + dx, ry + 1, rz + dz).getType().isAir()) {
                    continue;
                }
                n++;
                sx += dx;
                sz += dz;
            }
        }
        if (n < 16) {
            return null;   // 곁방을 못 찾았다 — 거처는 방 안에 눕는다
        }
        return new int[]{rx + (int) (sx / n), ry, rz + (int) (sz / n)};
    }

    // ══════════════════════════════════════════════════════════════════
    //  성문(城門) — 성시의 관문. <b>규모가 위계다</b>
    // ══════════════════════════════════════════════════════════════════

    /** 성벽의 들이 — 청하현의 담이 <b>3켜</b>다. 열둘은 그 넉 배다. 담과 성벽은 다른 물건이다 */
    private static final int WALL_H = 12;

    /** 성벽의 두께 — 다섯 칸. 그 위를 사람이 걷는다 (마도·여장·성루가 그 위에 산다) */
    private static final int WALL_T = 2;

    /** 성벽의 중심선 (국소 f) — 음수는 성 안. 부지 중심(f=0)은 <b>성문 앞</b>이다 */
    private static final int WALL_F = -8;

    /**
     * 성시의 문법 — <b>실지리 1:1 에서 큰 도시를 어떻게 읽히게 하는가</b>.
     *
     * <p>장안의 등록 규모는 {@code scale: 3000} 이다. 반경 3km 짜리 도시를 블록으로 지을 수는 없다 —
     * 지어도 아무도 다 못 걷고, 짓는 동안 서버가 죽는다. 그러나 <b>도시를 못 짓는 것과
     * 도시가 없는 것은 다르다.</b>
     *
     * <p>그래서 <b>무대(stage) 한 구획</b>만 짓는다 — <b>관문</b>. 그리고 규모를 <b>비교</b>로 말한다:
     * <table>
     *   <tr><th></th><th>청하현 (현성)</th><th>장안 (성도)</th></tr>
     *   <tr><td>담/성벽</td><td>조약돌·돌벽돌 <b>3켜</b> · 두께 1</td><td>돌벽돌 <b>12켜</b> · 두께 5</td></tr>
     *   <tr><td>문</td><td>개구 3칸 · 문루 6~7</td><td>개구 <b>7칸</b> · 성루 지붕 y+20</td></tr>
     *   <tr><td>길</td><td>흙길 3칸</td><td>관도 <b>7칸</b> · 석등 열</td></tr>
     * </table>
     * 사람의 눈은 절대 크기를 모른다 — <b>아는 것과 견주어서만</b> 안다. 청하현을 걸어 본 자가
     * 이 성벽 앞에 서면, 성벽이 열두 켜라는 사실이 <b>그를 대신해 "여기는 성도다"라고 말한다.</b>
     *
     * <p>짓는 것은 넷뿐이다: <b>성벽 일부 · 성문과 성루 · 관도가 드는 자리 · 성문 앞 저잣거리 한 골목.</b>
     * 성 안은 <b>짓지 않는다</b> — 성벽 너머로 보이지 않는 것이 도시의 나머지다.
     * (그것이 정직하다. 문을 지나 들어가면 아무것도 없는 도시를 짓느니, 문 앞에서 멈추는 편이 낫다.)
     */
    private static List<Zone> cityGate(World world, WorldMap.Place place, TerrainForge.SiteSpec spec) {
        int cx = spec.cx();
        int cz = spec.cz();
        int cy = spec.groundY();
        Facing fw = entry(spec);
        int rad = spec.radius();
        boolean swap = fw.swapped();

        // 【단】 성문 앞 광장 — 돌 포장. 관이 깐 바닥은 흙이 아니다
        TerrainForge.terrace(world, spec, fw.x(cx, 6, 0), fw.z(cz, 6, 0),
                cy, swap ? 8 : 12, swap ? 12 : 8, Material.POLISHED_ANDESITE);

        cityWall(world, spec, cx, cy, cz, fw, rad);
        cityGateway(world, spec, cx, cy, cz, fw);
        gateTower(world, cx, cy, cz, fw);
        rampartStair(world, spec, cx, cy, cz, fw);
        // 관도 — 성문으로 <b>드는</b> 길. 폭 7 (청하현 관도가 3칸이다)
        approachPath(world, spec, cx, cz, fw, 15, rad + 12, cy, 3, Material.DIRT_PATH);
        marketLane(world, spec, cx, cy, cz, fw);
        cityLanterns(world, cx, cy, cz, fw, rad);

        return List.of(new Zone(place.name(), "성문 — 담과 성벽은 다른 물건이다", world.getName(),
                cx - rad, cy - 8, cz - rad, cx + rad, cy + 26, cz + rad));
    }

    /**
     * 성벽 — <b>거대한 돌</b>. 자재가 위계를 말한다 (통나무 = 도적 / 회벽·기와 = 문파·관 / <b>성벽 = 돌</b>).
     *
     * <p>결이 있어야 돌이 돌로 보인다: 발치 두 켜는 <b>잡석</b>(조약돌 — 기초는 다듬지 않는다),
     * 열두 칸마다 <b>판축 이음매</b>(조각된 돌벽돌 세로 줄 — 판을 대고 흙을 다져 올린 자국),
     * 좌표 해시로 <b>풍화</b>(금 간 돌벽돌). 성벽은 한 해에 서지 않았다.
     *
     * <p>꼭대기에는 <b>여장</b>과 <b>총안</b>(쏘는 구멍 — 세 칸마다 하나). 그리고 <b>치(雉)</b> —
     * 성벽에서 밖으로 튀어나온 돌출부. 치가 있어야 성벽 밑에 붙은 적을 옆에서 쏜다.
     * 청하현의 담에는 치가 없다. <b>그 부재가 현(縣)과 성(城)의 차이다.</b>
     */
    private static void cityWall(World world, TerrainForge.SiteSpec spec,
                                 int cx, int cy, int cz, Facing fw, int rad) {
        int top = cy + WALL_H;
        for (int l = -(rad - 2); l <= rad - 2; l++) {
            for (int f = WALL_F - WALL_T; f <= WALL_F + WALL_T; f++) {
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                int base = groundOf(world, spec, x, z, cy);
                for (int y = Math.min(base, cy); y <= top; y++) {
                    world.getBlockAt(x, y, z).setType(masonry(x, y, z, cy, l));
                }
            }
            // 여장(女墻)과 총안(銃眼) — 안팎 두 줄. 세 칸마다 한 칸을 비운다 (그 구멍으로 쏜다)
            if (Math.floorMod(l, 3) != 0) {
                for (int f : new int[]{WALL_F - WALL_T, WALL_F + WALL_T}) {
                    world.getBlockAt(fw.x(cx, f, l), top + 1, fw.z(cz, f, l))
                            .setType(Material.STONE_BRICK_WALL);
                }
            }
        }
        // 치(雉) — 성벽에서 밖으로 튀어나온 돌출부. 24칸마다 (성벽이 곧기만 하면 그건 담이다)
        for (int lc = -48; lc <= 48; lc += 24) {
            if (Math.abs(lc) < 12 || Math.abs(lc) > rad - 8) {
                continue;   // 성문 자리와 부지 밖은 건너뛴다
            }
            for (int l = lc - 3; l <= lc + 3; l++) {
                for (int f = WALL_F + WALL_T + 1; f <= WALL_F + WALL_T + 2; f++) {
                    int x = fw.x(cx, f, l);
                    int z = fw.z(cz, f, l);
                    int base = groundOf(world, spec, x, z, cy);
                    for (int y = Math.min(base, cy); y <= top; y++) {
                        world.getBlockAt(x, y, z).setType(masonry(x, y, z, cy, l));
                    }
                    if (Math.floorMod(l, 2) == 0) {
                        world.getBlockAt(x, top + 1, z).setType(Material.STONE_BRICK_WALL);
                    }
                }
            }
            // 치 위의 기와 갓 — 관의 지붕은 기와다
            for (int l = lc - 3; l <= lc + 3; l++) {
                world.getBlockAt(fw.x(cx, WALL_F + WALL_T + 2, l), top + 2,
                        fw.z(cz, WALL_F + WALL_T + 2, l)).setType(Material.DEEPSLATE_TILE_SLAB);
            }
        }
    }

    /** 성벽의 돌 — 잡석 기초 · 판축 이음매 · 풍화. 결정론(좌표 해시)이다 */
    private static Material masonry(int x, int y, int z, int cy, int l) {
        if (y <= cy + 1) {
            return Material.COBBLESTONE;                    // 잡석 기초 — 다듬지 않는다
        }
        if (Math.floorMod(l, 12) == 0) {
            return Material.CHISELED_STONE_BRICKS;          // 판축 이음매 — 세로 한 줄
        }
        if (Math.floorMod(x * 7 + y * 13 + z * 3, 11) == 0) {
            return Material.CRACKED_STONE_BRICKS;           // 풍화 — 성벽은 한 해에 서지 않았다
        }
        return Material.STONE_BRICKS;
    }

    /** 성문 — 개구 7칸 x 높이 8. 문짝은 없다 (열려 있어야 성문이다 — 닫힌 문은 벽이다) */
    private static void cityGateway(World world, TerrainForge.SiteSpec spec,
                                    int cx, int cy, int cz, Facing fw) {
        for (int f = WALL_F - WALL_T; f <= WALL_F + WALL_T; f++) {
            for (int l = -3; l <= 3; l++) {
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                world.getBlockAt(x, cy, z).setType(Material.STONE_BRICKS);   // 문 밑도 돌이다
                TerrainForge.sealBelow(world, x, cy, z);
                for (int y = cy + 1; y <= cy + 8; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR);
                }
            }
            // 홍예(虹霓) — 아치. 문의 어깨를 계단으로 접는다 (모난 구멍은 문이 아니라 뚫린 자리다)
            for (int s : new int[]{-1, 1}) {
                stair(world, fw.x(cx, f, 3 * s), cy + 8, fw.z(cz, f, 3 * s),
                        Material.STONE_BRICK_STAIRS, fw.side(-s), true);
                stair(world, fw.x(cx, f, 2 * s), cy + 9, fw.z(cz, f, 2 * s),
                        Material.STONE_BRICK_STAIRS, fw.side(-s), true);
            }
        }
        // 현판 — 성문 밖 얼굴. 이름은 등록부의 것이다 (장안·낙양 — 새 이름을 만들지 않는다)
        for (int l = -1; l <= 1; l++) {
            world.getBlockAt(fw.x(cx, WALL_F + WALL_T, l), cy + 10, fw.z(cz, WALL_F + WALL_T, l))
                    .setType(Material.DARK_OAK_PLANKS);
        }
        for (int l : new int[]{-2, 2}) {   // 현판 양옆 현수 등롱 (사슬 + 랜턴)
            int x = fw.x(cx, WALL_F + WALL_T, l);
            int z = fw.z(cz, WALL_F + WALL_T, l);
            world.getBlockAt(x, cy + 10, z).setType(Material.IRON_CHAIN);
            lantern(world, x, cy + 9, z);
        }
    }

    /**
     * 성루(城樓) — 성벽 위의 집. <b>관의 어휘</b>다 (회벽 · 검은 기와 · 노출 기둥 —
     * 청하현 표국·관아의 문법 그대로. 세계는 하나고, 관은 어디서나 같은 집을 짓는다).
     *
     * <p>다만 <b>서는 자리가 다르다</b> — 표국은 땅에 서고 성루는 <b>열두 켜 위에</b> 선다.
     * 같은 집도 그 높이에 있으면 다른 말을 한다.
     */
    private static void gateTower(World world, int cx, int cy, int cz, Facing fw) {
        int floor = cy + WALL_H;      // 성벽 꼭대기 = 성루의 마루
        for (int f = WALL_F - WALL_T - 1; f <= WALL_F + WALL_T + 1; f++) {
            for (int l = -7; l <= 7; l++) {
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                put(world, x, floor, z, Material.STONE_BRICKS);   // 처마가 성벽 밖으로 한 칸 나온다
                boolean edge = Math.abs(f - WALL_F) == WALL_T + 1 || Math.abs(l) == 7;
                boolean corner = Math.abs(f - WALL_F) == WALL_T + 1 && Math.abs(l) == 7;
                if (!edge) {
                    continue;
                }
                for (int y = floor + 1; y <= floor + 4; y++) {
                    world.getBlockAt(x, y, z).setType(corner ? Material.DARK_OAK_LOG
                            : Material.WHITE_TERRACOTTA);   // 회벽 · 노출 기둥
                }
                if (Math.floorMod(x + z, 2) == 0) {
                    world.getBlockAt(x, floor + 2, z).setType(Material.GLASS_PANE);   // 격자창
                }
            }
        }
        // 문 — 마도(馬道)가 오는 쪽 (성 안)
        for (int l = -1; l <= 1; l++) {
            world.getBlockAt(fw.x(cx, WALL_F - WALL_T - 1, l), floor + 1,
                    fw.z(cz, WALL_F - WALL_T - 1, l)).setType(Material.AIR);
            world.getBlockAt(fw.x(cx, WALL_F - WALL_T - 1, l), floor + 2,
                    fw.z(cz, WALL_F - WALL_T - 1, l)).setType(Material.AIR);
        }
        // 지붕 — 검은 기와. 능선으로 수렴한다 (본전과 같은 물매 — 관과 문파는 같은 지붕을 인다)
        for (int i = 0; i <= 3; i++) {
            int y = floor + 5 + i;
            for (int l = -8 + i; l <= 8 - i; l++) {
                put(world, fw.x(cx, WALL_F - WALL_T - 2 + i, l), y,
                        fw.z(cz, WALL_F - WALL_T - 2 + i, l), Material.DEEPSLATE_TILES);
                put(world, fw.x(cx, WALL_F + WALL_T + 2 - i, l), y,
                        fw.z(cz, WALL_F + WALL_T + 2 - i, l), Material.DEEPSLATE_TILES);
            }
            for (int f = WALL_F - WALL_T - 2 + i; f <= WALL_F + WALL_T + 2 - i; f++) {
                put(world, fw.x(cx, f, -8 + i), y, fw.z(cz, f, -8 + i), Material.DEEPSLATE_TILES);
                put(world, fw.x(cx, f, 8 - i), y, fw.z(cz, f, 8 - i), Material.DEEPSLATE_TILES);
            }
        }
        for (int l = -5; l <= 5; l++) {   // 용마루
            put(world, fw.x(cx, WALL_F, l), floor + 9, fw.z(cz, WALL_F, l),
                    Material.DEEPSLATE_TILE_SLAB);
        }
        lantern(world, fw.x(cx, WALL_F, 0), floor + 4, fw.z(cz, WALL_F, 0));   // 성루의 불 — 밤의 성문
        lantern(world, fw.x(cx, WALL_F, -5), floor + 4, fw.z(cz, WALL_F, -5));
        lantern(world, fw.x(cx, WALL_F, 5), floor + 4, fw.z(cz, WALL_F, 5));
    }

    /**
     * 마도(馬道) — 성벽 위로 오르는 비탈. <b>성벽은 오르는 것이다</b> (못 오르는 성벽은 담이다).
     * 한 칸에 한 켜씩만 오른다 — 그것이 사람의 걸음이다 (지역 검수 ①의 잣대).
     */
    private static void rampartStair(World world, TerrainForge.SiteSpec spec,
                                     int cx, int cy, int cz, Facing fw) {
        for (int i = 0; i <= WALL_H; i++) {
            int l = 9 + i;
            for (int f = WALL_F - WALL_T - 3; f <= WALL_F - WALL_T - 1; f++) {
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                int base = groundOf(world, spec, x, z, cy);
                for (int y = Math.min(base, cy); y <= cy + i; y++) {
                    world.getBlockAt(x, y, z).setType(y <= cy + 1 ? Material.COBBLESTONE
                            : Material.STONE_BRICKS);
                }
                for (int y = cy + i + 1; y <= cy + i + 4; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR);   // 머리 위를 비운다
                }
            }
        }
    }

    /**
     * 성문 앞 저잣거리 — <b>한 골목</b>. 성문 밖에 장이 서는 것은 세금 때문이다
     * (성 안으로 물건을 들이면 문세를 문다 — 그래서 파는 자도 사는 자도 <b>문 앞</b>에서 만난다).
     *
     * <p>여기가 이 무대에서 <b>유일하게 색이 있는 자리</b>다 (수묵 규약: 채색은 차양·등롱·매화에만).
     */
    private static void marketLane(World world, TerrainForge.SiteSpec spec,
                                   int cx, int cy, int cz, Facing fw) {
        Material[] awnings = {Material.RED_WOOL, Material.LIGHT_GRAY_WOOL, Material.BROWN_WOOL};
        int i = 0;
        for (int f : new int[]{18, 24, 30}) {
            for (int side : new int[]{-1, 1}) {
                stall(world, cx, cy, cz, fw, f, 6 * side, side, awnings[i % awnings.length]);
                i++;
            }
        }
        // 점포 두 채 — 회벽·맞배. 관청류(팔작)가 아니라 상가다 (지붕 문법이 격을 가른다)
        shopHouse(world, spec, cx, cy, cz, fw, 26, -13);
        shopHouse(world, spec, cx, cy, cz, fw, 26, 13);
    }

    /** 노점 — 대나무 좌판 + 차양 + 통. 장사치는 관도를 본다 */
    private static void stall(World world, int cx, int cy, int cz, Facing fw,
                              int f0, int l0, int side, Material awning) {
        for (int f = f0; f <= f0 + 1; f++) {
            for (int l = l0 - side; l != l0 + 2 * side; l += side) {
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                world.getBlockAt(x, cy + 1, z).setType(Material.BAMBOO_PLANKS);   // 좌판
                world.getBlockAt(x, cy + 4, z).setType(awning);                   // 차양
            }
        }
        int px = fw.x(cx, f0, l0 + 2 * side);
        int pz = fw.z(cz, f0, l0 + 2 * side);
        for (int y = cy + 1; y <= cy + 3; y++) {
            world.getBlockAt(px, y, pz).setType(Material.SCAFFOLDING);   // 차양 골조 (대나무 질감)
        }
        world.getBlockAt(fw.x(cx, f0 + 1, l0), cy + 2, fw.z(cz, f0 + 1, l0)).setType(Material.BARREL);
        world.getBlockAt(fw.x(cx, f0, l0), cy + 2, fw.z(cz, f0, l0)).setType(Material.DECORATED_POT);
    }

    /** 점포 — 회벽 · 맞배 흑와 9x7. 성문 밖의 장사는 관의 격을 못 쓴다 (팔작은 관의 것이다) */
    private static void shopHouse(World world, TerrainForge.SiteSpec spec,
                                  int cx, int cy, int cz, Facing fw, int f0, int l0) {
        boolean swap = fw.swapped();
        int[] box = localBox(cx, cz, fw, f0 - 3, f0 + 3, l0 - 4, l0 + 4);
        int x0 = box[0];
        int z0 = box[1];
        int w = swap ? 7 : 9;
        int d = swap ? 9 : 7;
        // 점포의 마당은 **자갈**이다 — 노면(다진 흙)을 깔면 검수가 그것을 '길'로 세고,
        //   길로 세면 밤에 밝아야 한다. 저잣거리의 길은 관도 하나면 족하다 (5부 §5.1 등롱의 리듬)
        TerrainForge.terrace(world, spec, x0 + w / 2, z0 + d / 2, cy, w / 2 + 1, d / 2 + 1,
                Material.GRAVEL);
        int x1 = x0 + w - 1;
        int z1 = z0 + d - 1;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                world.getBlockAt(x, cy, z).setType(Material.SPRUCE_PLANKS);
                boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                if (!edge) {
                    continue;
                }
                boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                for (int y = cy + 1; y <= cy + 3; y++) {
                    world.getBlockAt(x, y, z).setType(corner ? Material.DARK_OAK_LOG
                            : Material.WHITE_TERRACOTTA);
                }
                if (Math.floorMod(x + z, 2) == 0) {
                    world.getBlockAt(x, cy + 2, z).setType(Material.GLASS_PANE);
                }
            }
        }
        int peak = Math.min(w, d) / 2;
        for (int i = 0; i <= peak; i++) {   // 맞배 — 두 방향만 계단이 오른다
            int y = cy + 3 + i;
            for (int x = x0 - 1 + i; x <= x1 + 1 - i; x++) {
                put(world, x, y, z0 - 1 + i, Material.DEEPSLATE_TILES);
                put(world, x, y, z1 + 1 - i, Material.DEEPSLATE_TILES);
            }
            for (int z = z0 + i; z <= z1 - i; z++) {
                put(world, x0 - 1 + i, y, z, Material.DEEPSLATE_TILES);
                put(world, x1 + 1 - i, y, z, Material.DEEPSLATE_TILES);
            }
        }
        int dx = fw.x(cx, f0 - 3, l0);   // 문은 관도를 본다
        int dz = fw.z(cz, f0 - 3, l0);
        world.getBlockAt(dx, cy + 1, dz).setType(Material.AIR);
        world.getBlockAt(dx, cy + 2, dz).setType(Material.AIR);
        world.getBlockAt(x0 + 1, cy + 1, z0 + 1).setType(Material.BARREL);
        world.getBlockAt(x1 - 1, cy + 1, z1 - 1).setType(Material.CHEST);
        lantern(world, (x0 + x1) / 2, cy + 3, (z0 + z1) / 2);
    }

    /**
     * 성문의 불 — <b>관이 지키는 길은 밝다</b> (도적의 산채가 어두운 것과 정반대의 말이다).
     * 광장은 격자로, 관도는 <b>여섯 칸 리듬</b>으로. 등롱은 노면 밖(±4)에 서서 길 한복판까지 빛을 던진다.
     */
    private static void cityLanterns(World world, int cx, int cy, int cz, Facing fw, int rad) {
        for (int f : new int[]{0, 6, 12}) {           // 광장 — 열둘
            for (int l : new int[]{-9, -3, 3, 9}) {
                lanternPost(world, fw.x(cx, f, l), cy, fw.z(cz, f, l));
            }
        }
        for (int f = 18; f <= rad + 8; f += 6) {      // 관도 — 여섯 칸 리듬 (노면 폭 7의 양옆)
            for (int l : new int[]{-4, 4}) {
                lanternPost(world, fw.x(cx, f, l), cy, fw.z(cz, f, l));
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  수로채(水路寨) — 장강수로채. <b>뭍에 집이 없다</b>
    // ══════════════════════════════════════════════════════════════════

    /**
     * 수로채의 문법 — 등록부의 한 줄이 이 원형을 다 적어 두었다:
     * <b>"물 위의 채 — 뗏목과 배를 엮어 만든 마을. 뭍에 집이 없다"</b>.
     *
     * <p>그 <b>"뭍에 집이 없다"</b> 가 이 원형의 전부다. 산채는 땅을 두르고, 문파는 산을 오르고,
     * 성시는 땅에 벽을 세운다. 수로채는 <b>땅을 쓰지 않는다</b> — 그것이 이 세력이 왜 강(江)인가의 답이다:
     * <ul>
     *   <li><b>뭍에는 나루뿐</b> — 물으면 "고기 잡는 나루"라고 답할 만한 것. 집은 한 채도 없다</li>
     *   <li><b>물 위에 뗏목</b> — 마루는 물에서 한 칸 뜬다. 말뚝이 강바닥까지 박혀 그것을 받친다</li>
     *   <li><b>쇠사슬 관문</b> — 강을 가로지른다. <b>통행세를 걷는다</b>는 말이 형태가 된 것
     *       (routes.sulo_jangang: "이 물길의 통행세를 그들이 걷는다")</li>
     *   <li><b>땅을 만지지 않는다</b> — 물을 메우지 않고, 물을 걷어내지 않는다. <b>물 위에 얹을 뿐이다</b>
     *       (지형 계층의 계약 ③: 강은 세계의 것이다)</li>
     * </ul>
     *
     * <p>물이 없으면 <b>짓지 않는다</b>. 물 없는 수로채는 그냥 산채고, 우리에겐 이미 산채가 있다.
     */
    private static List<Zone> waterStockade(World world, WorldMap.Place place,
                                            TerrainForge.SiteSpec spec) {
        if (!spec.waterfront()) {
            return List.of();   // 물이 이 집의 땅이다 — 없으면 세울 수 없다
        }
        int cx = spec.cx();
        int cz = spec.cz();
        int cy = spec.groundY();
        Facing fw = Facing.of(spec.waterSides().get(0));   // f = 물 쪽 (배가 나가는 방향)
        int waterY = waterLevel(world, spec, fw);
        int deck = waterY + 1;                             // 뗏목의 마루 — 물에서 한 칸
        int shore = shoreline(spec, fw);                   // 물가의 국소 f

        quay(world, spec, cx, cy, cz, fw, shore, deck);            // 뭍 — 나루뿐이다
        int rc = shore + 17;                                       // 본채 뗏목의 중심 (물 위)
        raft(world, cx, cz, fw, deck, shore + 6, shore + 28, 11);  // 본채 뗏목
        raft(world, cx, cz, fw, deck, shore + 1, shore + 5, 2);    // 잔교 — 나루와 본채를 잇는다
        slipway(world, cx, cz, fw, deck, shore + 29);              // 선창 — 물에서 채로 오르는 계단
        raftHuts(world, cx, cz, fw, deck, rc);
        chainGate(world, spec, cx, cz, fw, waterY, deck, shore + 33);
        boats(world, cx, cz, fw, deck, rc);

        // 구역의 중심은 **뗏목**이다 (부지 중심이 아니다 — 거기는 물일 수 있고, 물은 목적지가 아니다).
        //   검수의 걸음이 목표로 삼는 자리가 사람이 서는 자리여야 한다.
        int rcx = fw.x(cx, rc, 0);
        int rcz = fw.z(cz, rc, 0);
        int half = spec.radius() - 8;
        return List.of(new Zone(place.name(), "물 위의 채 — 뭍에 집이 없다", world.getName(),
                rcx - half, waterY - 12, rcz - half, rcx + half, cy + 22, rcz + half));
    }

    /**
     * 선창(船艙) — <b>물에서 채로 오르는 계단</b>. 강바닥에서 한 칸에 한 켜씩 뗏목 마루까지.
     *
     * <p>왜 필요한가: 이 집에 오는 길은 둘이다 — 뭍에서 나루를 거쳐 오거나, <b>물에서 기어오르거나</b>.
     * 물에서 오르는 길이 없으면 뗏목은 물 위의 <b>섬</b>이고, 검수의 걸음이 강바닥에서 멈춘다
     * (지역 검수 ① 도달성 · 환경 검수 ④ 연결성 — 둘 다 물에서 출발할 수 있다).
     *
     * <p><b>물을 걷어내지 않는다</b>: 돌을 물속에 <b>놓을</b> 뿐이다. 물 위를 비우면 그것은 물이 아니라
     * 구덩이가 되고(공중의 물 — 검수 ②), 강을 판 자리는 강이 아니다.
     */
    private static void slipway(World world, int cx, int cz, Facing fw, int deck, int f0) {
        for (int i = 1; i <= 10; i++) {
            int y = deck - i;
            for (int l = -2; l <= 2; l++) {
                int x = fw.x(cx, f0 + i - 1, l);
                int z = fw.z(cz, f0 + i - 1, l);
                org.bukkit.block.Block b = world.getBlockAt(x, y, z);
                if (!b.getType().isAir() && !b.isLiquid()) {
                    continue;   // 강바닥에 닿았다 — 계단은 여기서 끝난다
                }
                b.setType(Material.COBBLESTONE);
                for (int yy = y - 1; yy >= y - 6; yy--) {   // 밑을 받친다 (물속의 축대)
                    org.bukkit.block.Block u = world.getBlockAt(x, yy, z);
                    if (!u.getType().isAir() && !u.isLiquid()) {
                        break;
                    }
                    u.setType(Material.COBBLESTONE);
                }
            }
        }
    }

    /** 물의 높이 — <b>물에게 묻는다</b>. 젖은 열의 가장 높은 물 한 칸이 강의 낯이다 */
    private static int waterLevel(World world, TerrainForge.SiteSpec spec, Facing fw) {
        int cx = spec.cx();
        int cz = spec.cz();
        int cy = spec.groundY();
        for (int f = 0; f <= spec.radius(); f++) {
            int x = fw.x(cx, f, 0);
            int z = fw.z(cz, f, 0);
            if (!spec.wet(x, z)) {
                continue;
            }
            for (int y = cy + 6; y >= cy - 16; y--) {
                if (world.getBlockAt(x, y, z).getType() == Material.WATER) {
                    return y;
                }
            }
        }
        return cy;
    }

    /** 물가 — 뭍이 끝나고 물이 시작되는 국소 f (배가 뭍에 닿는 자리) */
    private static int shoreline(TerrainForge.SiteSpec spec, Facing fw) {
        int cx = spec.cx();
        int cz = spec.cz();
        for (int f = -spec.radius() + 1; f < spec.radius() - 1; f++) {
            boolean here = spec.wet(fw.x(cx, f, 0), fw.z(cz, f, 0));
            boolean next = spec.wet(fw.x(cx, f + 1, 0), fw.z(cz, f + 1, 0));
            if (!here && next) {
                return f;
            }
        }
        return 0;
    }

    /**
     * 나루 — <b>뭍에 있는 유일한 것</b>. 집이 아니라 <b>자리</b>다 (통·계선주·등롱 둘).
     *
     * <p>그리고 <b>석축 물매</b> — 뭍에서 뗏목까지 한 칸에 한 켜씩 내려간다.
     * 배를 대는 나루에 계단이 없으면 짐을 못 내린다 (그리고 걸어 들어올 수도 없다 — 검수 ①).
     */
    private static void quay(World world, TerrainForge.SiteSpec spec,
                             int cx, int cy, int cz, Facing fw, int shore, int deck) {
        int qx = fw.x(cx, shore - 7, 0);
        int qz = fw.z(cz, shore - 7, 0);
        TerrainForge.terrace(world, spec, qx, qz, cy, 3, 3,
                Material.COARSE_DIRT);   // 나루 마당 — 뭍에서 유일하게 사람이 깐 바닥
        // 석축 물매 — 마당(cy)에서 뗏목(deck)까지 <b>한 칸에 한 켜</b>. 계단이 아니라 비탈이다
        //   (짐을 지고 오르내리는 자리에 계단을 놓으면 짐을 못 진다).
        int run = Math.max(3, cy - deck) + 2;
        for (int i = 0; i <= run; i++) {
            int f = shore + 1 - run + i;
            int y = Math.max(deck, cy - i);
            for (int l = -2; l <= 2; l++) {
                TerrainForge.terrace(world, spec, fw.x(cx, f, l), fw.z(cz, f, l), y, 0, 0,
                        Material.COBBLESTONE);
            }
        }
        world.getBlockAt(fw.x(cx, shore - 8, -2), cy + 1, fw.z(cz, shore - 8, -2))
                .setType(Material.BARREL);
        world.getBlockAt(fw.x(cx, shore - 8, 2), cy + 1, fw.z(cz, shore - 8, 2))
                .setType(Material.BARREL);
        // 나루의 불 둘 — 뭍에서 유일한 광원. 마당 한복판까지 맨해튼 ≤ 6 이라 마당이 통째로 밝다
        lanternPost(world, fw.x(cx, shore - 7, -3), cy, fw.z(cz, shore - 7, -3));
        lanternPost(world, fw.x(cx, shore - 7, 3), cy, fw.z(cz, shore - 7, 3));
    }

    /**
     * 뗏목 — 물 위의 마루. <b>물을 걷어내지 않는다</b>: 마루는 물 위 한 칸(deck = 수면+1)에 얹고,
     * 말뚝을 <b>강바닥까지</b> 박아 받친다 (말뚝은 물을 밀어낼 뿐 허공을 만들지 않는다 — 검수 ②).
     */
    private static void raft(World world, int cx, int cz, Facing fw,
                             int deck, int fFrom, int fTo, int half) {
        for (int f = fFrom; f <= fTo; f++) {
            for (int l = -half; l <= half; l++) {
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                world.getBlockAt(x, deck, z).setType(Math.floorMod(x * 3 + z * 7, 9) == 0
                        ? Material.SPRUCE_SLAB : Material.SPRUCE_PLANKS);   // 결 — 자로 잰 뗏목은 배가 아니다
                // 말뚝 — 네 칸마다. 강바닥까지 박는다 (엮은 뗏목은 흐르고, 박은 채는 남는다)
                boolean pile = Math.floorMod(f - fFrom, 4) == 0 && Math.floorMod(l + half, 4) == 0;
                if (pile) {
                    drivePile(world, x, z, deck);
                }
            }
        }
        if (half < 4) {
            return;   // 잔교(다리)에는 난간을 두르지 않는다 — 끝을 막으면 그것은 다리가 아니라 벽이다
        }
        for (int l = -half; l <= half; l += 2) {   // 난간 — 물에 빠지지 않게 (사람이 사는 자리다)
            world.getBlockAt(fw.x(cx, fTo, l), deck + 1, fw.z(cz, fTo, l)).setType(Material.SPRUCE_FENCE);
        }
    }

    /** 말뚝 — 수면 아래 강바닥까지 통나무를 박는다. 물을 <b>대신</b>할 뿐 허공을 만들지 않는다 */
    private static void drivePile(World world, int x, int z, int deck) {
        for (int y = deck - 1; y >= deck - 24; y--) {
            org.bukkit.block.Block b = world.getBlockAt(x, y, z);
            if (!b.getType().isAir() && !b.isLiquid()) {
                break;   // 강바닥에 닿았다
            }
            b.setType(Material.SPRUCE_LOG);
        }
    }

    /** 뗏목 위의 살림 — 오두막 셋 · 망루 · 모닥불 · 채기. 기와는 없다 (수적은 관이 아니다) */
    private static void raftHuts(World world, int cx, int cz, Facing fw, int deck, int rc) {
        boolean swap = fw.swapped();
        for (int[] p : new int[][]{{rc - 6, -7}, {rc - 6, 7}, {rc + 5, -7}}) {
            int[] box = localBox(cx, cz, fw, p[0] - 2, p[0] + 2, p[1] - 2, p[1] + 2);
            barrack(world, box[0], deck, box[1], 5, 5, fw.side(p[1] > 0 ? -1 : 1));
        }
        // 모닥불 — 물 위의 불은 <b>돌 위에</b> 놓는다 (뗏목에 그냥 불을 놓으면 그것은 화재다)
        int fx = fw.x(cx, rc, 0);
        int fz = fw.z(cz, rc, 0);
        world.getBlockAt(fx, deck, fz).setType(Material.COBBLESTONE);
        world.getBlockAt(fx, deck + 1, fz).setType(Material.CAMPFIRE);
        for (int i = 0; i < 4; i++) {
            double a = Math.PI * i / 2.0 + Math.PI / 4;
            put(world, fx + (int) Math.round(Math.cos(a) * 2), deck + 1,
                    fz + (int) Math.round(Math.sin(a) * 2), Material.SPRUCE_SLAB);   // 둘러앉는 자리
        }
        // 망루 — 강을 본다. 수로채가 보는 것은 산길이 아니라 <b>배</b>다
        int[] tower = localBox(cx, cz, fw, rc + 8, rc + 9, -1, 0);
        watchtowerAt(world, tower[0], deck, tower[1]);
        // 통행세 궤 — 걷은 것이 쌓이는 자리 (이 세력이 무엇으로 먹고사는가)
        for (int i = -2; i <= 2; i++) {
            int x = fw.x(cx, rc + 2, i);
            int z = fw.z(cz, rc + 2, i);
            world.getBlockAt(x, deck + 1, z).setType(Math.floorMod(i, 2) == 0
                    ? Material.BARREL : Material.CHEST);
        }
        // 그물 — 울타리에 걸린다 (여기 사는 자들은 어부이기도 하다. 그것이 위장이자 살림이다)
        for (int l = 4; l <= 8; l++) {
            world.getBlockAt(fw.x(cx, rc - 2, l), deck + 2, fw.z(cz, rc - 2, l))
                    .setType(Material.SPRUCE_FENCE);
            world.getBlockAt(fw.x(cx, rc - 2, l), deck + 1, fw.z(cz, rc - 2, l))
                    .setType(Material.IRON_CHAIN);
        }
        // 채기(寨旗) — 채색은 여기뿐이다. 물빛이다
        int gx = fw.x(cx, rc - 4, 4);
        int gz = fw.z(cz, rc - 4, 4);
        for (int y = deck + 1; y <= deck + 5; y++) {
            world.getBlockAt(gx, y, gz).setType(Material.SPRUCE_FENCE);
        }
        world.getBlockAt(gx, deck + 6, gz).setType(Material.CYAN_WOOL);
        // 물 위의 불 — 밤의 강에서 <b>이것</b>이 수로채다 (배는 이 불을 보고 온다, 또는 피한다)
        for (int[] p : new int[][]{{rc - 8, 0}, {rc, -9}, {rc, 9}, {rc + 7, 4}}) {
            int x = fw.x(cx, p[0], p[1]);
            int z = fw.z(cz, p[0], p[1]);
            if (world.getBlockAt(x, deck, z).getType().isAir()) {
                continue;
            }
            world.getBlockAt(x, deck + 1, z).setType(Material.SPRUCE_FENCE);
            world.getBlockAt(x, deck + 2, z).setType(Material.SPRUCE_FENCE);
            lantern(world, x, deck + 3, z);
        }
    }

    /** 망루 — 통나무 네 주 + 마루 + 불. 산채의 것과 같은 물건이다 (도적의 눈은 어디서나 같다) */
    private static void watchtowerAt(World world, int x0, int cy, int z0) {
        for (int y = cy + 1; y <= cy + 6; y++) {
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    world.getBlockAt(x0 + dx, y, z0 + dz).setType(Material.SPRUCE_LOG);
                }
            }
        }
        for (int x = x0 - 1; x <= x0 + 2; x++) {
            for (int z = z0 - 1; z <= z0 + 2; z++) {
                world.getBlockAt(x, cy + 7, z).setType(Material.SPRUCE_PLANKS);
                if (x == x0 - 1 || x == x0 + 2 || z == z0 - 1 || z == z0 + 2) {
                    world.getBlockAt(x, cy + 8, z).setType(Material.SPRUCE_FENCE);
                }
            }
        }
        world.getBlockAt(x0, cy + 7, z0).setType(Material.AIR);
        for (int y = cy + 1; y <= cy + 7; y++) {
            ladder(world, x0 - 1, y, z0, BlockFace.EAST);
        }
        world.getBlockAt(x0, cy + 9, z0).setType(Material.TORCH);
    }

    /**
     * 쇠사슬 관문 — 강을 가로지르는 사슬. <b>이 세력이 왜 세력인가</b>가 여기 있다.
     *
     * <p>등록부(routes.sulo_jangang): "이 물길의 통행세를 그들이 걷는다. 배는 걷는 것보다 빠르다 —
     * 그래서 상단은 물을 쓰고, <b>그래서 수로채에게 뜯긴다</b>."
     * 그 한 문장이 블록이 되면 이것이다: <b>강 위에 걸린 사슬 한 줄.</b> 배는 여기서 멈춘다.
     */
    private static void chainGate(World world, TerrainForge.SiteSpec spec,
                                  int cx, int cz, Facing fw, int waterY, int deck, int f) {
        for (int l : new int[]{-13, 13}) {   // 두 말뚝 — 사슬은 어딘가에 매여야 사슬이다
            int x = fw.x(cx, f, l);
            int z = fw.z(cz, f, l);
            drivePile(world, x, z, waterY + 1);
            for (int y = deck; y <= deck + 4; y++) {
                world.getBlockAt(x, y, z).setType(Material.SPRUCE_LOG);
            }
            lantern(world, x, deck + 5, z);   // 관문의 불 — 밤에도 사슬이 보여야 한다
        }
        for (int l = -12; l <= 12; l++) {   // 사슬 — 강을 가로지른다
            world.getBlockAt(fw.x(cx, f, l), deck + 4, fw.z(cz, f, l)).setType(Material.IRON_CHAIN);
        }
    }

    /** 정박한 배 둘 — 판자와 계단으로 뜬 선체. 뗏목 옆구리에 매여 있다 (수적은 배로 먹고산다) */
    private static void boats(World world, int cx, int cz, Facing fw, int deck, int rc) {
        for (int side : new int[]{-1, 1}) {
            int l0 = 12 * side;
            for (int f = rc - 4; f <= rc + 3; f++) {
                int x = fw.x(cx, f, l0);
                int z = fw.z(cz, f, l0);
                world.getBlockAt(x, deck, z).setType(Material.SPRUCE_PLANKS);   // 갑판
                boolean end = f == rc - 4 || f == rc + 3;
                world.getBlockAt(fw.x(cx, f, l0 + side), deck, fw.z(cz, f, l0 + side))
                        .setType(end ? Material.SPRUCE_PLANKS : Material.SPRUCE_SLAB);
                if (end) {
                    stair(world, x, deck + 1, z, Material.SPRUCE_STAIRS,
                            f == rc - 4 ? fw.in() : fw.out(), false);   // 이물과 고물
                }
            }
            // 삿대와 차양 — 배는 세워 두는 물건이 아니라 <b>쓰는</b> 물건이다
            world.getBlockAt(fw.x(cx, rc, l0), deck + 1, fw.z(cz, rc, l0)).setType(Material.SPRUCE_FENCE);
            world.getBlockAt(fw.x(cx, rc, l0), deck + 2, fw.z(cz, rc, l0)).setType(Material.SPRUCE_FENCE);
            world.getBlockAt(fw.x(cx, rc - 1, l0), deck + 1, fw.z(cz, rc - 1, l0)).setType(Material.BARREL);
        }
    }

    // ─── 손 ───

    /**
     * <b>그 열의 땅 높이</b> — 부지 안이면 사양이 답하고, 밖이면 세계를 읽는다.
     * (굴 입구는 부지 밖 24칸까지 나간다 — 그래서 이 물음이 필요하다. 읽을 뿐 놓지 않는다.)
     */
    private static int groundOf(World world, TerrainForge.SiteSpec spec, int x, int z, int hint) {
        if (spec.inside(x, z) && !spec.wet(x, z)) {
            return spec.groundAt(x, z);
        }
        return surfaceProbe(world, x, z, hint);
    }

    /** 무너진 돌 — 굴을 파다 터진 살. 자로 잰 돌무더기는 돌무더기가 아니다 (좌표 해시) */
    private static Material rubble(int x, int y, int z) {
        return switch (Math.floorMod(x * 7 + y * 3 + z * 11, 5)) {
            case 0 -> Material.COBBLESTONE;
            case 1 -> Material.MOSSY_COBBLESTONE;
            case 2 -> Material.ANDESITE;
            case 3 -> Material.GRAVEL;
            default -> Material.STONE;
        };
    }

    /** 풍화한 돌 — 폐허의 자재 (청하현 폐사당의 어휘. 세계는 하나다) */
    private static Material weathered(int x, int y, int z) {
        return switch (Math.floorMod(x * 5 + y * 7 + z * 3, 4)) {
            case 0 -> Material.CRACKED_STONE_BRICKS;
            case 1 -> Material.MOSSY_STONE_BRICKS;
            case 2 -> Material.STONE_BRICKS;
            default -> Material.MUD_BRICKS;
        };
    }

    /** 누운 통나무 — 결이 눕는다 (선 나무와 쓰러진 나무는 다른 물건이다) */
    private static void logAxis(World world, int x, int y, int z, Material m, org.bukkit.Axis axis) {
        if (!world.getBlockAt(x, y, z).getType().isAir()) {
            return;
        }
        org.bukkit.block.data.Orientable data = (org.bukkit.block.data.Orientable) m.createBlockData();
        data.setAxis(axis);
        world.getBlockAt(x, y, z).setBlockData(data);
    }

    /** 냉색 — 폐사의 불. 마을의 온색과 정반대다 (금기의 조명 온도) */
    private static void soulLantern(World world, int x, int y, int z) {
        if (!world.getBlockAt(x, y, z).getType().isAir()) {
            return;
        }
        world.getBlockAt(x, y, z).setType(Material.SOUL_LANTERN);
    }

    /** 딛는 자리에만 놓는다 — 발밑이 단단하고 그 자리가 비었을 때만 (굴 안의 요철에 물건이 뜨지 않게) */
    private static void putOn(World world, int x, int y, int z, Material m) {
        if (!world.getBlockAt(x, y, z).getType().isAir()) {
            return;
        }
        if (!world.getBlockAt(x, y - 1, z).getType().isSolid()) {
            return;
        }
        world.getBlockAt(x, y, z).setType(m);
    }

    private static double dist(int dx, int dz) {
        return Math.sqrt((double) dx * dx + (double) dz * dz);
    }

    /**
     * <b>지면 읽기</b> — 부지 <b>밖</b>의 한 열이 얼마나 높은가 (문 밖 산길이 지나는 자리).
     *
     * <p>이것은 지형을 <b>빚는</b> 것이 아니라 <b>읽는</b> 것이다 — 블록 하나 놓지 않는다.
     * 부지 안이라면 물어볼 것도 없다: {@code spec.groundAt()} 이 답한다 (땅은 이미 지형 계층의 것이다).
     */
    private static int surfaceProbe(World world, int x, int z, int from) {
        for (int y = from + 12; y >= from - 12; y--) {
            Material m = world.getBlockAt(x, y, z).getType();
            if (!m.isAir() && !world.getBlockAt(x, y, z).isLiquid()) {
                return y;
            }
        }
        return from;
    }

    /**
     * 그 방위의 목책 반경 — <b>완전한 원은 도면이다. 도적에겐 도면이 없다.</b>
     * 방위각을 12등분해 좌표 해시로 흔든다 (결정론 유지).
     */
    private static double ringR(int cx, int cz, int f, int l) {
        int sector = (int) Math.floor((Math.atan2(l, f) + Math.PI) / (Math.PI / 6));
        return R - 1 + Math.floorMod(cx * 13 + cz * 7 + sector * 31, 4);   // R-1 .. R+2
    }

    private static int inward(int d) {
        return d > 0 ? -1 : d < 0 ? 1 : 0;
    }

    /** 채문 스팬 — 진입 방향 정면 7칸 (문설주 포함). 국소 좌표라 방위가 돌아도 같다 */
    private static boolean isGateSpan(int f, int l) {
        return f > R - 2 && Math.abs(l) <= 3;
    }

    /** 초가 한 칸 — 계단(너와) 위에 좌표 해시 40% 로 짚을 얹는다. 결정론: 같은 자리 = 같은 이엉 */
    private static void thatchStair(World world, int x, int y, int z, BlockFace facing) {
        if (!world.getBlockAt(x, y, z).getType().isAir()) {
            return;
        }
        boolean straw = Math.floorMod(x * 31 + z * 17 + y * 7, 10) < 4;
        if (straw) {
            world.getBlockAt(x, y, z).setType(Material.HAY_BLOCK);
        } else {
            stair(world, x, y, z, Material.SPRUCE_STAIRS, facing, false);
        }
    }

    private static void put(World world, int x, int y, int z, Material m) {
        if (world.getBlockAt(x, y, z).getType().isAir()) {
            world.getBlockAt(x, y, z).setType(m);
        }
    }

    private static void stair(World world, int x, int y, int z, Material m, BlockFace facing, boolean top) {
        Stairs data = (Stairs) m.createBlockData();
        data.setFacing(facing);
        data.setHalf(top ? org.bukkit.block.data.Bisected.Half.TOP
                : org.bukkit.block.data.Bisected.Half.BOTTOM);
        world.getBlockAt(x, y, z).setBlockData(data);
    }

    private static void wallTorch(World world, int x, int y, int z, BlockFace facing) {
        Directional data = (Directional) Material.WALL_TORCH.createBlockData();
        data.setFacing(facing);
        world.getBlockAt(x, y, z).setBlockData(data);
    }

    private static void ladder(World world, int x, int y, int z, BlockFace facing) {
        Directional data = (Directional) Material.LADDER.createBlockData();
        data.setFacing(facing);
        world.getBlockAt(x, y, z).setBlockData(data);
    }

    private static void lantern(World world, int x, int y, int z) {
        org.bukkit.block.data.type.Lantern data =
                (org.bukkit.block.data.type.Lantern) Material.LANTERN.createBlockData();
        data.setHanging(true);
        world.getBlockAt(x, y, z).setBlockData(data);
    }

    private static void shelf(World world, int x, int y, int z, BlockFace facing,
                              org.bukkit.inventory.ItemStack... items) {
        org.bukkit.block.data.type.Shelf data =
                (org.bukkit.block.data.type.Shelf) Material.SPRUCE_SHELF.createBlockData();
        data.setFacing(facing);
        world.getBlockAt(x, y, z).setBlockData(data);
        if (world.getBlockAt(x, y, z).getState() instanceof org.bukkit.block.Shelf state) {
            for (int slot = 0; slot < Math.min(items.length, 3); slot++) {
                if (items[slot] != null) {
                    state.getSnapshotInventory().setItem(slot, items[slot]);
                }
            }
            state.update(true, false);
        }
    }

    static List<String> unbuildableReasons(WorldMap.Place place) {
        List<String> out = new ArrayList<>();
        if (place.faction() == null) {
            out.add("세력 미등록 — 원형을 고를 수 없다");
        } else if (!canBuild(place)) {
            String faction = place.faction();
            if (("magyo".equals(faction) || "hyeolgyo".equals(faction))) {
                // 굴이 없는 마·혈 — 마교 본교("초원의 흑성")·낙양 잠복지(도시 뒷골목).
                //   은신처 원형은 **굴이 본체다**. 굴이 없으면 그것은 다른 집이고, 그 원형은 아직 없다.
                out.add("원형 없음 — 굴이 없는 " + faction + " 거점이다 (등록부: \""
                        + place.note() + "\"). 은신처 원형은 굴이 본체다");
            } else {
                out.add("원형 없음 — '" + faction + "' 의 건축 원형이 아직 없다 "
                        + "(지금은 산채·도관·은신처·성문·수로채)");
            }
        }
        return out;
    }
}
