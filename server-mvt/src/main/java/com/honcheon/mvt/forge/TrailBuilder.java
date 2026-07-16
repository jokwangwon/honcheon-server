package com.honcheon.mvt.forge;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Stairs;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 도보길 조성기 (기계 ③ 조성분 — 천계단·잔도) — 완성된 <b>통짜 험산</b>({@link RangeField}) 위에
 * 실제 <b>걸을 수 있는 계단길</b>을 짓는다. 산세 높이장은 건드리지 않는다(읽기만) — 이 조성기는
 * 그 위에 <b>블록만</b> 놓는다.
 *
 * <h2>왜 이 조성기가 따로 서는가 (2026-07-16 사용자 확정)</h2>
 * <p>「도보 길이 너무 매끄러워 보인다 — 일단 산을 다 완성 후 도보길을 내는 형태로 진행.」
 * carve(보행 자리 평탄화 후처리)가 폐기되어({@code terrain_forge_v5.md §8 C-18}) {@code reliefAt} 은
 * 이제 crag·골격 그대로의 통짜 험산이다. {@link TrailForge} 는 그 위에 놓일 「예정 중심선」
 * (노선 폴리라인·경로계수 1.5·구역당 굽이 ≥2)만 <b>계획</b>하고, 물매-보행성(≤1:1)은 이 조성기가
 * <b>실제 노반을 깎고 채워</b> 맞춘다 (물매-보행성 이관 — {@code §3.1 · §8.2 C-18}).
 *
 * <h2>계단길 조성 원리</h2>
 * <ul>
 *   <li><b>노선 계승</b> — 중심선은 {@link TrailForge#generate}(산기슭 r264 → 본산 문 r124)를
 *       그대로 딛는다. 문에서 <b>정상(최고봉 Pm)</b>까지는 골격 <b>관문척추</b> 능선
 *       ({@code RangeSpec.skelRidges "관문척추"} — 관문→Pm 설계 척추)을 이어 붙여 끊김 없이 오른다.</li>
 *   <li><b>한 칸 계단 (걸어 오름)</b> — 노반(딛는 면) y 는 산기슭에서 정상까지 <b>한 칸에 최대 ±1</b>
 *       만 오르내린다({@link #MAX_STEP}). 오르는 칸은 <b>석계단</b>({@link Material#STONE_BRICK_STAIRS})을
 *       놓아 점프 없이 걸어 오르고, 평탄한 칸은 평탄 노반({@link Material#STONE_BRICKS})을 깐다.
 *       이 「한 칸 원칙」이 곧 <b>걸을 수 있음의 보증</b>이다.</li>
 *   <li><b>산을 깎아 낸 길</b> — 노반 위 바위는 걷어내 머리 공간을 낸다({@link #HEADROOM}칸). 노반이
 *       험산의 실지면보다 낮으면 위 바위를 깎고(잔도·천계단의 「산을 깎아 만든」 느낌), 높으면
 *       아래를 기단({@code Stone/Andesite/Tuff})으로 받쳐 뜨지 않게 한다.</li>
 *   <li><b>물매 여밈</b> — 노반의 목표 등고는 험산 실지면(crag 포함)이 아니라 <b>골격 표면</b>
 *       ({@code reliefLayers(x,z,warp=true,crag=false)} — 잔결 없는 매끈한 몸)을 딛는다. 그래서
 *       계단이 crag 잡음을 쫓아 들쭉날쭉하지 않고 산의 진짜 사면을 따라 규칙적으로 오른다.
 *       crag 요철은 노반 둘레에서 깎이거나 채워진다.</li>
 * </ul>
 *
 * <h2>재료 — 팔레트 유도 (지어낸 재료 없음)</h2>
 * <p>{@code hwasan_campus_architecture.md} 기단 팔레트 {@code Stone · Andesite · Tuff · Stone Brick}
 * 과 장식 팔레트 {@code Lantern} 에서만 뽑는다:
 * <ul>
 *   <li>석계단(오름) = {@code STONE_BRICK_STAIRS} · 평탄 노반 = {@code STONE_BRICKS} (Stone Brick 계단·판)</li>
 *   <li>기단(노반 받침·등롱 기둥) = {@code STONE / ANDESITE / TUFF} (결정론 해시로 섞어 결을 준다)</li>
 *   <li>조명 = {@code LANTERN} (깎아 낸 회랑이 어둡지 않게 — 갓길에 등롱, {@value #LANTERN_EVERY}칸 간격)</li>
 * </ul>
 *
 * <h2>결정론</h2>
 * <p>노선(TrailForge)·정상(spec)·노반 등고(reliefLayers)·재료 해시 전부 좌표의 순수 함수 → 같은
 * 사양이면 같은 계단길. {@link #plan}은 Bukkit 을 모른다(순수 계획) — 블록 쓰기는 {@link #paveNode}
 * 가 노드 하나씩 (조율자가 TickBudget 아래 굴린다).
 *
 * <h2>미결 (승인 대기)</h2>
 * <ul>
 *   <li>잔도(절벽 개방 회랑) — 지금은 급면을 <b>깎아 낸 회랑/터널</b>로 지난다. 절벽에 한쪽 트인
 *       널길(잔도)·현공교는 후속.</li>
 *   <li>난간·쉼터·전망대 — v1 은 걸을 수 있는 계단길이 핵심이라 최소 장식(등롱만). 후속.</li>
 *   <li>물매-보행성 실검증 — 인게임 도보로 「점프 없이 오르는가」 최종 확인 (조율자 절차).</li>
 * </ul>
 */
public final class TrailBuilder {

    private TrailBuilder() {
    }

    // ─── 노반 형상 (전부 「잠정·승인 대기」 — 팔레트·한 칸 원칙에서 유도) ───────────────
    /** 노반 반폭 (칸) — 폭 = 2·{@value}+1 = 3칸 (브리프 「폭 2~3칸」) */
    public static final int HALF = 1;
    /** 노반 위 머리 공간 (칸) — 이만큼 위 바위를 걷어낸다 (플레이어 키 2 + 여유) */
    public static final int HEADROOM = 3;
    /** 한 칸(중심선 한 셀) 당 노반 최대 오르내림 — <b>걸어 오름의 보증</b> (석계단 한 단) */
    public static final int MAX_STEP = 1;
    /** 중심선 조밀화 간격 (칸) — 폴리라인을 이 간격으로 표본해 이어지는 셀 사슬을 만든다 */
    private static final double DENSIFY_STEP = 0.5;
    /** 등롱 간격 (노드 수) — 깎아 낸 회랑 조명 */
    private static final int LANTERN_EVERY = 9;

    /** 노반 받침·등롱 기둥이 자연 지면을 뚫지 않게 걷어도 되는 것 (MountainRangeForge.NATURAL 계승) */
    private static final Set<Material> NATURAL = EnumSet.of(
            Material.STONE, Material.DEEPSLATE, Material.TUFF, Material.GRANITE,
            Material.DIORITE, Material.ANDESITE, Material.CALCITE, Material.DIRT,
            Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.GRASS_BLOCK,
            Material.PODZOL, Material.SAND, Material.GRAVEL, Material.CLAY,
            Material.MOSS_BLOCK, Material.MUD, Material.SNOW_BLOCK, Material.BEDROCK);

    /** 초목 — 걷어도 되는 것 (MountainRangeForge.foliage 계승) */
    private static boolean foliage(Material m) {
        String n = m.name();
        return n.endsWith("_LOG") || n.endsWith("_LEAVES") || n.endsWith("_GRASS")
                || n.endsWith("_FERN") || n.endsWith("_FLOWER") || n.endsWith("_SAPLING")
                || n.endsWith("_PETALS") || m == Material.BAMBOO || m == Material.SNOW
                || m == Material.DEAD_BUSH || m == Material.VINE || m == Material.MOSS_CARPET
                || m == Material.LARGE_FERN;
    }

    private static final long SALT_BASE = 0x7A11_BA5EL;   // 기단 결

    // ═══════════════════════════════════════════════════════════════════
    // 계획 — 순수 함수 (Bukkit 무의존 · RangeField 만 읽는다)
    // ═══════════════════════════════════════════════════════════════════

    /** 계단길 한 노드 — 중심선 한 셀 + 노반 등고 + 형상. */
    public record Node(int x, int z, int standY, boolean ascending,
                       BlockFace stairFacing, int perpX, int perpZ, int lanternSide) {
    }

    /**
     * 도보길 계획 — 노드 사슬 + 트레일헤드(산기슭)·정상 좌표. 걸을 수 있음의 보증은
     * {@code |standY[i]-standY[i-1]| ≤ }{@value #MAX_STEP} (연속 노드 노반 단차 한 칸).
     */
    public record Plan(List<Node> nodes,
                       int footX, int footY, int footZ,
                       int summitX, int summitY, int summitZ,
                       int ascendSteps, int flatSteps, int highestColX, int highestColZ, int highestColY) {
    }

    /**
     * 노선(TrailForge) + 관문척추 능선을 이어 <b>산기슭→정상</b> 계단길을 계획한다. 순수 함수.
     */
    public static Plan plan(RangeSpec spec, RangeField field) {
        // ① 중심선 = 노선 폴리라인(산기슭→본산 문) + 관문척추(문→정상 Pm) 이어 붙이기.
        TrailForge.Result route = TrailForge.generate(spec, field::reliefAt);
        List<double[]> poly = new ArrayList<>();
        double[] rxs = route.xs(), rzs = route.zs();
        for (int i = 0; i < rxs.length; i++) {
            poly.add(new double[]{rxs[i], rzs[i]});
        }
        // 문에서 정상까지 — 설계 척추(관문척추: 관문→Pm)를 그대로 딛는다 (spec 에서 · 지어낸 노선 아님)
        RangeSpec.Ridge spine = findRidge(spec, "관문척추");
        RangeSpec.Peak pm = findPeak(spec, "Pm");
        if (spine != null) {
            for (int i = 0; i < spine.xs().length; i++) {
                poly.add(new double[]{spec.peakX() + spine.xs()[i], spec.peakZ() + spine.zs()[i]});
            }
        } else if (pm != null) {   // 폴백 — 척추가 없으면 최고봉으로 곧장
            poly.add(new double[]{spec.peakX() + pm.px(), spec.peakZ() + pm.pz()});
        }

        // ② 조밀화 → 이어지는(4-연결) 셀 사슬. 대각 도약은 L-모서리 셀을 끼워 틈을 없앤다.
        List<int[]> spineCells = new ArrayList<>();
        int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
        for (int seg = 0; seg + 1 < poly.size(); seg++) {
            double ax = poly.get(seg)[0], az = poly.get(seg)[1];
            double bx = poly.get(seg + 1)[0], bz = poly.get(seg + 1)[1];
            double len = Math.hypot(bx - ax, bz - az);
            int steps = Math.max(1, (int) Math.ceil(len / DENSIFY_STEP));
            for (int s = 0; s <= steps; s++) {
                double t = s / (double) steps;
                int cx = (int) Math.round(ax + (bx - ax) * t);
                int cz = (int) Math.round(az + (bz - az) * t);
                if (cx == lastX && cz == lastZ) {
                    continue;
                }
                if (lastX != Integer.MIN_VALUE && cx != lastX && cz != lastZ) {
                    spineCells.add(new int[]{lastX, cz});   // L-모서리 — 4-연결 보장
                }
                spineCells.add(new int[]{cx, cz});
                lastX = cx;
                lastZ = cz;
            }
        }

        // ③ 노반 등고 — 골격 표면(crag 없는 매끈한 몸)을 목표로, 한 칸에 최대 ±1 만 (걸어 오름).
        List<Node> nodes = new ArrayList<>(spineCells.size());
        int prevStand = smoothGround(spec, field, spineCells.get(0)[0], spineCells.get(0)[1]);
        int ascend = 0, flat = 0;
        for (int i = 0; i < spineCells.size(); i++) {
            int[] c = spineCells.get(i);
            int desired = smoothGround(spec, field, c[0], c[1]);
            int stand;
            boolean up;
            if (i == 0) {
                stand = desired;
                up = false;
            } else {
                int delta = Math.max(-MAX_STEP, Math.min(MAX_STEP, desired - prevStand));
                stand = prevStand + delta;
                up = delta > 0;
            }
            if (up) {
                ascend++;
            } else {
                flat++;
            }
            // 진행 방향 (이웃 차분) → 계단 방위·노반 횡폭 방향
            int[] a = spineCells.get(Math.max(0, i - 1));
            int[] b = spineCells.get(Math.min(spineCells.size() - 1, i + 1));
            BlockFace travel = cardinal(b[0] - a[0], b[1] - a[1]);
            int[] perp = perpOf(travel);
            // 오르는 칸의 석계단은 <b>내리막(왔던) 쪽</b>이 낮게 서야 걸어 오른다 → facing = travel 반대
            BlockFace stairFacing = up ? travel.getOppositeFace() : null;
            int lantSide = (i > 0 && i % LANTERN_EVERY == 0) ? (((i / LANTERN_EVERY) % 2 == 0) ? +1 : -1) : 0;
            nodes.add(new Node(c[0], c[1], stand, up, stairFacing, perp[0], perp[1], lantSide));
            prevStand = stand;
        }

        Node foot = nodes.get(0);
        Node top = nodes.get(nodes.size() - 1);
        // 보고용 — 실제 최고 열 (골격 최고봉 근방 국소 최고점 탐색 · 경로와 무관한 참고값)
        int[] hi = highestColumn(spec, field);
        return new Plan(nodes, foot.x(), foot.standY(), foot.z(),
                top.x(), top.standY(), top.z(), ascend, flat, hi[0], hi[2], hi[1]);
    }

    /** 골격 표면 등고 (절대 y) — crag 잔결 없는 매끈한 몸 (reliefLayers warp=true·crag=false). */
    private static int smoothGround(RangeSpec spec, RangeField field, int x, int z) {
        double h = field.reliefLayers(x, z, true, false);
        return spec.baseY() + (int) Math.round(Math.max(0.0, h));
    }

    /** 참고용 최고 열 — 본산권(r≤honsanR)에서 실지면 최고 열 {x, y, z} (경로 아님 · 보고만). */
    private static int[] highestColumn(RangeSpec spec, RangeField field) {
        int best = Integer.MIN_VALUE, bx = spec.peakX(), bz = spec.peakZ();
        int r = spec.honsanR();
        for (int x = spec.peakX() - r; x <= spec.peakX() + r; x += 2) {
            for (int z = spec.peakZ() - r; z <= spec.peakZ() + r; z += 2) {
                if (Math.hypot(x - spec.peakX(), z - spec.peakZ()) > r) {
                    continue;
                }
                int y = field.surfaceY(x, z);
                if (y > best) {
                    best = y;
                    bx = x;
                    bz = z;
                }
            }
        }
        return new int[]{bx, best, bz};
    }

    private static RangeSpec.Ridge findRidge(RangeSpec spec, String id) {
        for (RangeSpec.Ridge r : spec.skelRidges()) {
            if (r.id().equals(id)) {
                return r;
            }
        }
        return null;
    }

    private static RangeSpec.Peak findPeak(RangeSpec spec, String id) {
        for (RangeSpec.Peak p : spec.skelPeaks()) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        return null;
    }

    /** 방향 벡터 → 우세 축의 사방위 (계단 방위·횡폭 축). */
    private static BlockFace cardinal(double dx, double dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return dz >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /** 진행 사방위에 수직인 횡폭 단위 (E/W → z 축, N/S → x 축). */
    private static int[] perpOf(BlockFace travel) {
        return (travel == BlockFace.EAST || travel == BlockFace.WEST)
                ? new int[]{0, 1} : new int[]{1, 0};
    }

    // ═══════════════════════════════════════════════════════════════════
    // 조성 — 노드 하나를 땅에 놓는다 (조율자가 TickBudget 아래 노드마다 부른다)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 노드 하나의 노반 단면(폭 3)을 놓는다: 노반(계단/평탄) · 머리 공간 깎기 · 아래 기단 받침 ·
     * (간격이면) 갓길 등롱. {@code tally} 에 census 를 누적한다. 사람이 지은 것·이미 놓인 노반은
     * 지나친다(멱등 — 재실행 안전). 물을 놓지 않는다.
     */
    public static void paveNode(World world, RangeSpec spec, Node n, Tally tally) {
        // 노드 소구역 청크 선로드 (콘솔 조성은 언로드 상태일 수 있다)
        int pad = HALF + 2;
        ensureChunks(world, n.x() - pad, n.z() - pad, n.x() + pad, n.z() + pad);

        int s = n.standY();
        for (int l = -HALF; l <= HALF; l++) {
            int cx = n.x() + n.perpX() * l;
            int cz = n.z() + n.perpZ() * l;
            int ground = world.getHighestBlockYAt(cx, cz);   // 험산 실지면(놓인 블록)

            // (1) 머리 공간 — 노반 위 바위·초목을 걷어낸다 (노반·계단·등롱은 NATURAL 밖이라 안 걷힌다)
            for (int y = s + 1; y <= s + HEADROOM; y++) {
                Block b = world.getBlockAt(cx, y, cz);
                Material m = b.getType();
                if (!m.isAir() && (NATURAL.contains(m) || foliage(m))) {
                    b.setType(Material.AIR, false);
                    tally.carve();
                }
            }
            // (2) 노반 — 오르는 칸은 석계단, 아니면 평탄 노반
            Block tread = world.getBlockAt(cx, s, cz);
            if (n.ascending() && n.stairFacing() != null) {
                Stairs data = (Stairs) Material.STONE_BRICK_STAIRS.createBlockData();
                data.setFacing(n.stairFacing());
                data.setHalf(Bisected.Half.BOTTOM);
                tread.setBlockData(data, false);
                tally.stair();
            } else {
                tread.setType(Material.STONE_BRICKS, false);
                tally.flat();
            }
            // (3) 기단 받침 — 노반이 실지면보다 높으면(뜨면) 아래를 채운다 (잔도 밑동)
            for (int y = s - 1; y > ground; y--) {
                Block b = world.getBlockAt(cx, y, cz);
                if (b.getType().isAir()) {
                    b.setType(baseMaterial(cx, y, cz), false);
                    tally.fill();
                }
            }
        }

        // (4) 갓길 등롱 — 깎아 낸 회랑이 어둡지 않게
        if (n.lanternSide() != 0) {
            int lx = n.x() + n.perpX() * (HALF + 1) * n.lanternSide();
            int lz = n.z() + n.perpZ() * (HALF + 1) * n.lanternSide();
            int ground = world.getHighestBlockYAt(lx, lz);
            for (int y = s - 1; y > ground; y--) {            // 기둥 받침
                Block b = world.getBlockAt(lx, y, lz);
                if (b.getType().isAir()) {
                    b.setType(baseMaterial(lx, y, lz), false);
                }
            }
            world.getBlockAt(lx, s, lz).setType(Material.STONE_BRICKS, false);   // 기둥 갓
            Block cap = world.getBlockAt(lx, s + 1, lz);
            Material capM = cap.getType();
            if (capM.isAir() || NATURAL.contains(capM) || foliage(capM)) {
                cap.setType(Material.LANTERN, false);
                tally.lantern();
            }
        }
    }

    /** 기단 재료 — Stone/Andesite/Tuff (기단 팔레트 · 결정론 해시로 섞는다). */
    private static Material baseMaterial(int x, int y, int z) {
        double r = h01(x, y, z, SALT_BASE);
        return r < 0.55 ? Material.STONE : r < 0.82 ? Material.ANDESITE : Material.TUFF;
    }

    private static void ensureChunks(World world, int minX, int minZ, int maxX, int maxZ) {
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    world.getChunkAt(cx, cz).load(true);
                }
            }
        }
    }

    /** 좌표+소금 → [0,1) 결정론 값 (splitmix64 계열 · 난수 아님). */
    private static double h01(int x, int y, int z, long salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0x165667B19E3779F9L
                ^ z * 0xC2B2AE3D27D4EB4FL ^ salt * 0xD6E8FEB86659FD93L;
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) / (double) (1L << 53);
    }

    // ═══════════════════════════════════════════════════════════════════
    // census 누적기 — 여러 노드에 걸쳐 센다 (완료 보고용)
    // ═══════════════════════════════════════════════════════════════════
    public static final class Tally {
        private long stairs;
        private long flats;
        private long carved;
        private long filled;
        private long lanterns;

        void stair() {
            stairs++;
        }

        void flat() {
            flats++;
        }

        void carve() {
            carved++;
        }

        void fill() {
            filled++;
        }

        void lantern() {
            lanterns++;
        }

        public long stairs() {
            return stairs;
        }

        public long flats() {
            return flats;
        }

        public long carved() {
            return carved;
        }

        public long filled() {
            return filled;
        }

        public long lanterns() {
            return lanterns;
        }
    }
}
