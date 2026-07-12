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

    /**
     * 지역 하나를 짓는다. 원형은 세력이 고르고, <b>땅은 지형 계층이 준다</b>.
     *
     * @param spec 부지 사양 — {@link TerrainForge#prepare} 가 이미 땅을 빚고 봉인하고 전이대를 놓았다
     * @return 등록할 구역 (없으면 빈 목록 — 원형이 없는 세력은 아직 못 짓는다)
     */
    static List<Zone> build(World world, WorldMap.Place place, TerrainForge.SiteSpec spec) {
        if ("noklim".equals(place.faction())) {
            return stockade(world, place, spec);
        }
        if (SECTS.contains(place.faction())) {
            return sect(world, place, spec);
        }
        return List.of();
    }

    /** 부지 반경 — 산채는 좁고(목책 R=22), 문파는 두 켜를 이어야 하므로 넓다. MvtCommand 와 같은 값이다 */
    static int siteRadius(WorldMap.Place place) {
        return "noklim".equals(place.faction()) ? 24 : 44;
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
        return "noklim".equals(place.faction()) || SECTS.contains(place.faction());
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

        // 문 밖 길 — 걸어 올라오는 자의 마지막 스무 칸. 표고는 전이대의 것을 따른다.
        int prev = cy;
        for (int f = R + 1; f <= R + 20; f++) {
            int y = spec.inside(fw.x(cx, f, 0), fw.z(cz, f, 0))
                    ? spec.groundAt(fw.x(cx, f, 0), fw.z(cz, f, 0))
                    : surfaceProbe(world, fw.x(cx, f, 0), fw.z(cz, f, 0), prev);
            y = Math.max(prev - 1, Math.min(prev + 1, y));   // 한 걸음 ±1 — 길에 계단은 없다
            for (int l = -1; l <= 1; l++) {
                TerrainForge.terrace(world, spec, fw.x(cx, f, l), fw.z(cz, f, l), y, 0, 0,
                        Material.DIRT_PATH);   // 땅은 요청한다 — 깎기·축대·봉인은 지형 계층의 손
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

        int rad = spec.radius();
        int courtF = rad - 8;                 // 문전 마당 중심 (부지 중심 기준 전방) — 반경 안에 든다
        int gateF = courtF + 6;               // 산문 — 문전 마당의 앞턱 (단 위에 선다)
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
            //   간격 2 → 6: 등롱은 리듬이지 도배가 아니다 (사용자 피드백).
            if (Math.floorMod(i, 6) == 0) {
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

    // ─── 손 ───

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
            out.add("원형 없음 — '" + place.faction() + "' 의 건축 원형이 아직 없다 (지금은 산채·도관뿐)");
        }
        return out;
    }
}
