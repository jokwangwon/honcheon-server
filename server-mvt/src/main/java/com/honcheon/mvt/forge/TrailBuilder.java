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

    // ─── 노반 형상 (폭·회랑은 ★사용자 확정 2026-07-16 「기존 길들 +2·판 공간 +2」 — 인게임 인상 확인만 남음) ───
    /**
     * 노반 반폭 (칸) — 폭 = 2·{@value}+1 = <b>5칸</b>. ★사용자 확정 (2026-07-16 수치 재조정):
     * "도보길 수치 재조정 — 기존 길들 +2" → 옛 폭 3(HALF 1)에서 +2 = 폭 5. 노반·계단·다리 상판 전부.
     */
    public static final int HALF = 2;
    /**
     * 노반 위 머리 공간 (칸) — 이만큼 위 바위를 걷어낸다. ★사용자 확정 (2026-07-16 수치 재조정):
     * "땅을 파서 생기는 공간에도 +2를 하여 확장감을 부여" → 옛 3에서 +2 = <b>5칸</b> 회랑/터널.
     * 마루가 이보다 두꺼우면 천장 회랑/터널이 된다 (깊은 마루 절삭 MAX_HEAD_CUT 은 철회된 채 유지).
     */
    public static final int HEADROOM = 5;
    /** 한 칸(중심선 한 셀) 당 노반 최대 오르내림 — <b>걸어 오름의 보증</b> (석계단 한 단) */
    public static final int MAX_STEP = 1;
    /** 중심선 조밀화 간격 (칸) — 폴리라인을 이 간격으로 표본해 이어지는 셀 사슬을 만든다 */
    private static final double DENSIFY_STEP = 0.5;
    // ─── ★목책풍 난간 (사용자 확정 양식 2026-07-16: "가문비 울타리 + 굵힌 모서리 기둥 + 기둥 위 등롱"
    //     — 소박한 산길 인상. 문턱·간격 수치는 전부 잠정·승인 대기) ───────────────────────────
    /** 난간 기둥 간격 (노드) — 잠정 (지시 6~8 의 중앙값) */
    private static final int POST_EVERY = 7;
    /**
     * 기둥 재료 — ★A안 확정 (사용자 2026-07-16): {@code SPRUCE_LOG} 법수는 "더 안 이뻐 보인다"
     * (비례 4배 점프 · 수피 재질 이질 · 스카이라인 돌기 · 연결 어색) → 폐지. 기둥 = <b>울타리 2단
     * 겹침</b> — 두께 4px 유지·색 동일·난간보다 딱 한 단 높은 가는 표주 (바닐라가 수직 울타리를
     * 기둥 모양으로 잇는다). 등롱은 그 꼭대기 위.
     */
    private static final Material POST_MATERIAL = Material.SPRUCE_FENCE;
    /** 기둥 높이 (칸 — 갓돌 위 울타리 켜 수) = 난간(1) + 1 = 2단. 등롱은 그 위 (잠정) */
    private static final int POST_HEIGHT = 2;
    /** 몇 번째 기둥마다 등롱을 얹나 — 옛 9노드 독립 등롱을 기둥 위로 이관·정렬 (잠정) */
    private static final int LANTERN_POST_EVERY = 2;
    /** 낭떠러지 판정 낙차 (칸) — 갓돌 자리 지형이 노반보다 이만큼 깊으면 그 쪽에 난간 (잠정) */
    private static final int CLIFF_DROP = 4;

    // ─── ★굽이 참(계단참·landing) — 사용자 추가 (2026-07-16: "회전 급경사에서의 난간과 올라가는
    //     길 디자인 개선"). 문턱 전부 잠정·승인 대기 ───────────────────────────────────────
    /** 참 반폭 (노드) — 급경사 굽이 정점 앞뒤 이만큼을 평탄 참으로 (잠정) */
    private static final int LANDING_HALF = 1;
    /** 급경사 판정 창 반폭 (노드) · 창 안 최소 오름 수 — 굽이 정점 주변 오름 밀도 (잠정) */
    private static final int STEEP_WIN = 4;
    private static final int STEEP_MIN_UPS = 4;
    /** 쉼단 — 연속 오름이 이 길이에 달하면 평탄 1노드를 끼운다 (계단 벽 방지 · 잠정) */
    private static final int REST_RUN = 6;

    /**
     * 다리(잔도·현공교) 문턱 (칸) — 노반이 실지면보다 이만큼 이상 <b>뜨면</b> 통짜 기단 대신 <b>다리</b>
     * (상판 + 드문 기둥, 밑은 허공)로 짓는다. 그 미만(1~2칸)은 기단 받침 유지 (다리로 하기 애매).
     * ★잠정·승인 대기 — 華山 잔도·현공교 문법 유도. (사용자 보강 2026-07-16 「뜬 구간은 다리 형태로」)
     */
    public static final int BRIDGE_MIN_FLOAT = 3;
    /** 다리 지지 기둥 간격 (노드) — <b>드물어야</b> 상판 밑이 비어 다리로 읽힌다 (잠정) */
    private static final int PIER_EVERY = 5;
    /**
     * 다리 상판 두께 (칸) — ★사용자 확정 (2026-07-16 실측: "다리 구간이 블럭 하나의 굵기라 부실해
     * 보입니다 — 3칸 정도의 굵기로"). 걷는 면 y 는 불변, 그 아래로 {@value}−1 칸을 더 채워 상판이
     * 육중한 덩어리가 된다 (폭 5 발자국 전체 · 다리 판정 노드만).
     */
    public static final int DECK_THICKNESS = 3;
    /** 다리 기둥 단면 — 2×2 (상판 3칸 두께에 1×1 기둥은 가늘다 — ★잠정·승인 대기) */
    private static final int PIER_SIZE = 2;
    /** 다리 난간을 세우는가 — 팔레트 목재({@code Spruce}) 울타리 (선택 · 기본 켬 · 상판 3칸 보행로는 트임) */
    private static final boolean RAILING = true;

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
    private static final long SALT_DECK = 0x0DEC_0DECL;   // 다리 상판 belly 결

    /** 우리가 놓는 시설 자재 — 지면 측정이 이것을 지나쳐야 재실행에도 다리/기단 판정이 안 흔들린다 */
    private static final Set<Material> TRAIL_MADE = EnumSet.of(
            Material.STONE_BRICKS, Material.STONE_BRICK_STAIRS,
            Material.SPRUCE_FENCE, Material.LANTERN);

    /** 지면이 아닌 지표 장식 — FloraForge 가 심는 꽃(이름이 _FLOWER 로 안 끝나 foliage 밖) */
    private static final Set<Material> GROUND_DECOR = EnumSet.of(
            Material.POPPY, Material.DANDELION, Material.OXEYE_DAISY, Material.CORNFLOWER,
            Material.AZURE_BLUET, Material.ALLIUM, Material.LILY_OF_THE_VALLEY, Material.BLUE_ORCHID);

    /**
     * 지면 술어 — 이 블록을 「지형 지면」으로 볼 것인가. 순수(레지스트리 무의존 — 자기시험이 서버
     * 없이 잰다): 공기·초목(나무/잎/대나무/풀)·꽃 장식·<b>우리 시설</b>(상판·계단·난간·등롱)은 지면이
     * 아니다. 나머지(돌·흙·기단 STONE/ANDESITE/TUFF 포함)가 지면이다.
     */
    static boolean groundSolid(Material m) {
        return m != Material.AIR && m != Material.CAVE_AIR && m != Material.VOID_AIR
                && !foliage(m) && !TRAIL_MADE.contains(m) && !GROUND_DECOR.contains(m);
    }

    /**
     * 지형 지면 y — <b>나무·식생·우리 시설을 지나쳐</b> 잰다. ★2026-07-16 census 전멸 수리:
     * 옛 판은 {@code getHighestBlockYAt} 를 그대로 썼는데 그것은 <b>맨 위 블록</b>(식생시험이 심은
     * 나무·대나무 꼭대기 · 재실행이면 우리 상판·난간·등롱)을 돌려준다 → 지면이 노반 위로 읽혀
     * 다리 판정 false·기단 채움 0회전(census 전멸). 이제 위에서 내려가며 {@link #groundSolid} 가
     * 참인 첫 블록을 지면으로 삼는다 — 식생 위·재실행에도 안정.
     */
    private static int terrainGroundY(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z);
        int min = world.getMinHeight();
        while (y > min && !groundSolid(world.getBlockAt(x, y, z).getType())) {
            y--;
        }
        return y;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 계획 — 순수 함수 (Bukkit 무의존 · RangeField 만 읽는다)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 노반 열(column) 하나 — 계획이 확정한 <b>그 열의 유일한 노반</b>. ★2026-07-16 실측 수리의 핵:
     * 옛 조성은 노드마다 폭 3 행을 제 standY 로 포장해, 굽이·대각 조깅에서 <b>겹치는 열</b>에 다른 y 의
     * 노반·계단이 <b>포개졌다</b>(수직 겹침 → 걷는 표면 +2). 이제 한 열은 한 번만 포장된다.
     *
     * @param stair null 이면 평탄 노반({@code STONE_BRICKS}), 아니면 그 방위로 오르는 석계단
     *              (걷는 이가 낮은 면으로 접근 — facing = 오름 방향)
     */
    public record Col(int x, int z, int treadY, BlockFace stair) {
    }

    /**
     * 계단길 한 노드 — 중심선 한 셀 + 형상 + <b>이 노드가 소유한 노반 열들</b>({@code cols} —
     * 겹침 해상 뒤라 서로소·전체가 폭 2·{@value #HALF}+1(+굽이 참 확폭) 노반을 이룬다).
     *
     * <ul>
     *   <li>{@code pier} = 다리 기둥 자리 · {@code bridge} = 다리 판정 (<b>계획이 정한다</b> — 지면 =
     *       {@code field.surfaceY}, 순수·결정론. 월드 읽기 판정은 식생·재실행·자기 상판에 흔들렸다).</li>
     *   <li>{@code curbNeg/curbPos} = 그 쪽 갓돌 자리가 비어 있나 (다른 노반 열과 안 겹칠 때만 참).</li>
     *   <li>{@code railNeg/railPos} = <b>목책 난간 차등</b>: 다리 = 양옆 · 절벽/급경사 = 낭떠러지 쪽만 ·
     *       완만·지면 붙음 = 없음 (구간별 표정 — 사용자 확정 양식·잠정 문턱).</li>
     *   <li>{@code postNeg/postPos} = 기둥(★A안 — <b>울타리 2단</b> 겹침 · SPRUCE_LOG 법수 폐지) ·
     *       {@code postLantern} = 그 기둥 위 등롱 (옛 독립 등롱을 기둥 위로 이관).</li>
     *   <li>{@code widenSide} = 굽이 참 확폭 쪽 (0 = 없음) — 그 쪽 발자국이 +1 열 넓다 (갓돌도 밀림).</li>
     * </ul>
     */
    public record Node(int x, int z, int standY, boolean ascending, int perpX, int perpZ,
                       boolean pier, boolean bridge, boolean curbNeg, boolean curbPos,
                       boolean railNeg, boolean railPos, boolean postNeg, boolean postPos,
                       boolean postLantern, int widenSide, List<Col> cols) {
        /** 걷는 표면 y (딛는 노반 위 = 발 높이) — 걸을 수 있음(연속 셀 단차 ≤1)의 자. */
        public int walkTop() {
            return standY + 1;
        }

        /** 그 쪽 갓돌(난간·기둥) 열의 횡 오프셋 — 확폭 쪽은 한 칸 더 바깥. */
        public int curbOffset(int side) {
            return HALF + 1 + (widenSide == side ? 1 : 0);
        }
    }

    /**
     * 도보길 계획 — 노드 사슬 + 트레일헤드(산기슭)·정상 좌표. 걸을 수 있음의 보증은
     * <b>포장 전체(폭 5 발자국)</b>에서 인접 열 노반 단차 ≤{@value #MAX_STEP} (열 해상 완화가 강제 ·
     * 자기시험 ①②가 실물 모형으로 잰다).
     */
    public record Plan(List<Node> nodes,
                       int footX, int footY, int footZ,
                       int summitX, int summitY, int summitZ,
                       int ascendSteps, int flatSteps, int highestColX, int highestColZ, int highestColY,
                       List<Integer> steepCorners) {
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
        //    굽이 정점(폴리라인 내부 꼭짓점 = 헤어핀 모서리)의 노드 인덱스를 함께 기록한다 (굽이 참·법수).
        List<int[]> spineCells = new ArrayList<>();
        java.util.LinkedHashSet<Integer> cornerIdx = new java.util.LinkedHashSet<>();
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
                    if (seg > 0 && s == 0) {
                        cornerIdx.add(spineCells.size() - 1);   // 정점 셀 = 직전 셀 (중복 제거됨)
                    }
                    continue;
                }
                if (lastX != Integer.MIN_VALUE && cx != lastX && cz != lastZ) {
                    spineCells.add(new int[]{lastX, cz});   // L-모서리 — 4-연결 보장
                }
                spineCells.add(new int[]{cx, cz});
                lastX = cx;
                lastZ = cz;
                if (seg > 0 && s == 0) {
                    cornerIdx.add(spineCells.size() - 1);       // 굽이 정점 노드
                }
            }
        }

        // 진행 방향·횡폭 축 (참 확폭·열 배정·계단 방위에 쓴다)
        int m = spineCells.size();
        BlockFace[] travels = new BlockFace[m];
        int[][] perps = new int[m][];
        for (int i = 0; i < m; i++) {
            int[] a = spineCells.get(Math.max(0, i - 1));
            int[] b = spineCells.get(Math.min(m - 1, i + 1));
            travels[i] = cardinal(b[0] - a[0], b[1] - a[1]);
            perps[i] = perpOf(travels[i]);
        }

        // ③ 노반 등고 — 골격 표면(crag 없는 매끈한 몸)을 목표로, 한 칸에 최대 ±1 만 (걸어 오름).
        int[] desired = new int[m];
        for (int i = 0; i < m; i++) {
            desired[i] = smoothGround(spec, field, spineCells.get(i)[0], spineCells.get(i)[1]);
        }
        // 1차 사슬 — 급경사(오름 밀도) 판정의 밑감
        int[] pass1 = new int[m];
        pass1[0] = desired[0];
        for (int i = 1; i < m; i++) {
            pass1[i] = pass1[i - 1] + Math.max(-MAX_STEP, Math.min(MAX_STEP, desired[i] - pass1[i - 1]));
        }
        // ★굽이 참(landing) — 사용자 추가 "회전 급경사 개선": 급경사(정점 주변 ±STEEP_WIN 오름 ≥
        //   STEEP_MIN_UPS) 굽이 정점 앞뒤 LANDING_HALF 노드를 평탄 참으로. 확폭 쪽(widen)은 굽이 바깥.
        boolean[] landing = new boolean[m];
        int[] widen = new int[m];                       // 0 = 확폭 없음 · ±1 = perp 그 쪽 +1 열
        java.util.LinkedHashSet<Integer> steepCorners = new java.util.LinkedHashSet<>();
        for (int j : cornerIdx) {
            int ups = 0;
            for (int k = Math.max(1, j - STEEP_WIN); k <= Math.min(m - 1, j + STEEP_WIN); k++) {
                if (pass1[k] > pass1[k - 1]) {
                    ups++;
                }
            }
            if (ups < STEEP_MIN_UPS) {
                continue;                               // 완만한 굽이 — 참 불요
            }
            steepCorners.add(j);
            // 굽이 바깥 쪽 = -(들어오는 방향 + 나가는 방향) 을 perp 축에 사영한 부호
            int[] pin = spineCells.get(Math.max(0, j - 3));
            int[] pout = spineCells.get(Math.min(m - 1, j + 3));
            int[] pj = spineCells.get(j);
            double ux = pj[0] - pin[0], uz = pj[1] - pin[1];
            double vx = pout[0] - pj[0], vz = pout[1] - pj[1];
            double outX = -(ux + vx), outZ = -(uz + vz);
            for (int k = Math.max(0, j - LANDING_HALF); k <= Math.min(m - 1, j + LANDING_HALF); k++) {
                landing[k] = true;
                double proj = outX * perps[k][0] + outZ * perps[k][1];
                widen[k] = proj > 1e-9 ? +1 : proj < -1e-9 ? -1 : 0;
            }
        }
        // 2차 사슬 — 참(delta 0 강제) + 쉼단(연속 오름 REST_RUN 이면 평탄 1노드). 오름 총량은 사슬이
        //   뒤에서 따라잡는다 (경로 여유 — 자기시험이 정상 도달을 잰다 · 불가면 그대로 두고 보고).
        int[] stand = new int[m];
        stand[0] = desired[0];
        int upRun = 0;
        for (int i = 1; i < m; i++) {
            int delta = Math.max(-MAX_STEP, Math.min(MAX_STEP, desired[i] - stand[i - 1]));
            if (landing[i]) {
                delta = 0;                              // 굽이 참 — 평탄 (돌음참)
            } else if (delta > 0 && upRun >= REST_RUN) {
                delta = 0;                              // 쉼단 — 계단 벽 방지
            }
            upRun = delta > 0 ? upRun + 1 : 0;
            stand[i] = stand[i - 1] + delta;
        }
        // 고립 마루/골 여밈 — 한 셀만 이웃보다 ±1 튀는 자리는 눕힌다 (어색한 한 단 오르내림 방지).
        //   ★유지 판단: 2칸 벽의 실원인은 아래 ④(열 겹침)였으나, 이 여밈은 계획 자체의 한 칸 돌기
        //   (올랐다 바로 내림)를 없애는 독립 개선이라 남긴다.
        smoothBumps(stand);

        // ④ ★포장 해상(解像) — 한 열(column)에 노반 하나 (2026-07-16 사용자 실측 「계단 2개 위아래」 수리).
        //   실원인: 폭 3 행이 굽이·대각 조깅에서 서로 겹치는데, 옛 paveNode 는 노드마다 같은 열을 제
        //   standY 로 다시 포장했다 — 계단·노반은 NATURAL 밖(멱등 보호)이라 안 걷혀, 뒤 노드의 계단이
        //   앞 노드의 노반 **위에 얹혔다** (B+S 수직 겹침 → 걷는 표면 +2 · 등롱은 뒤 노반에 파묻힘).
        //   수리: 계획이 열마다 노반 y 를 하나로 확정한다 —
        //     (a) 주인 배정: |l| 최소(중심 우선) · 동률이면 먼저 온 노드 (결정론)
        //     (b) 이웃 완화: 인접 열 단차 >1 이면 높은 쪽을 낮춘다 (값은 내려가기만 → 수렴 · 결정론)
        //     (c) 계단 표기: 낮은 이웃에서 오르는 열 — 중심선(주 보행선)은 실제 걸음 방향을 우선
        // (a) 열 → {주인 노드, |l|} — 중심(|l|=0) 우선 · 동률 먼저 온 노드 (LinkedHashMap = 결정 순서).
        //     ★굽이 참 확폭: landing 노드는 widen 쪽으로 +1 열 (폭 5→6, 바깥 모서리만 — 잠정).
        java.util.LinkedHashMap<Long, int[]> owner = new java.util.LinkedHashMap<>();
        for (int i = 0; i < m; i++) {
            int[] c = spineCells.get(i);
            int lo = -HALF - (widen[i] < 0 ? 1 : 0);
            int hi = HALF + (widen[i] > 0 ? 1 : 0);
            for (int l = lo; l <= hi; l++) {
                long key = colKey(c[0] + perps[i][0] * l, c[1] + perps[i][1] * l);
                int[] cur = owner.get(key);
                if (cur == null || Math.abs(l) < cur[1]) {
                    owner.put(key, new int[]{i, Math.abs(l)});
                }
            }
        }
        // (b) 노반 y 완화 — 발자국 전체에서 인접 열 단차 ≤1 (높은 쪽을 낮춘다)
        java.util.LinkedHashMap<Long, Integer> tread = new java.util.LinkedHashMap<>();
        for (var e : owner.entrySet()) {
            tread.put(e.getKey(), stand[e.getValue()[0]]);
        }
        relaxTreads(tread);
        // ★굽이 참 평탄 보존 — 완화가 참 열 하나를 낮추면 참 안에 +1 오름(계단)이 되살아난다.
        //   참 중심 열들을 참 최저로 눕히고 재완화 (값은 내려가기만 → 수렴 · 결정론).
        boolean zoneChanged = true;
        while (zoneChanged) {
            zoneChanged = false;
            for (int j : steepCorners) {
                int lo = Integer.MAX_VALUE;
                for (int k = Math.max(0, j - LANDING_HALF); k <= Math.min(m - 1, j + LANDING_HALF); k++) {
                    lo = Math.min(lo, tread.get(colKey(spineCells.get(k)[0], spineCells.get(k)[1])));
                }
                for (int k = Math.max(0, j - LANDING_HALF); k <= Math.min(m - 1, j + LANDING_HALF); k++) {
                    long key = colKey(spineCells.get(k)[0], spineCells.get(k)[1]);
                    if (tread.get(key) != lo) {
                        tread.put(key, lo);
                        zoneChanged = true;
                    }
                }
            }
            if (zoneChanged) {
                relaxTreads(tread);
            }
        }
        // (c) 계단 표기 — 중심선 먼저 (실제 걸음 방향 = 이전 중심→현 중심 · facing = 오름 방향)
        java.util.LinkedHashMap<Long, BlockFace> stairAt = new java.util.LinkedHashMap<>();
        for (int i = 1; i < m; i++) {
            int[] c = spineCells.get(i), p = spineCells.get(i - 1);
            Integer t = tread.get(colKey(c[0], c[1])), tp = tread.get(colKey(p[0], p[1]));
            if (t != null && tp != null && t == tp + 1) {
                stairAt.put(colKey(c[0], c[1]), cardinal(c[0] - p[0], c[1] - p[1]));
            }
        }
        //     곁 열 — 주인 진행축 뒤가 한 칸 낮으면 그 방위, 아니면 아무 낮은 4-이웃 (모든 +1 오름이 계단)
        for (var e : tread.entrySet()) {
            long key = e.getKey();
            if (stairAt.containsKey(key)) {
                continue;
            }
            int x = keyX(key), z = keyZ(key);
            BlockFace tv = travels[owner.get(key)[0]];
            Integer behind = tread.get(colKey(x - tv.getModX(), z - tv.getModZ()));
            if (behind != null && e.getValue() == behind + 1) {
                stairAt.put(key, tv);
                continue;
            }
            for (int[] d : NEIGHBORS4) {
                Integer tn = tread.get(colKey(x + d[0], z + d[1]));
                if (tn != null && e.getValue() == tn + 1) {
                    // facing = 오름 방향 = 낮은 이웃(그쪽에서 걸어온다) → 이 열. d 는 열→이웃이므로 반대.
                    stairAt.put(key, cardinal(-d[0], -d[1]));
                    break;
                }
            }
        }
        // (d) 노드 세우기 — 소유 열 배분 + ★목책 난간 차등·기둥(법수)·기둥 위 등롱 (전부 계획 소유 · 순수)
        List<List<Col>> ownedCols = new ArrayList<>(m);
        for (int i = 0; i < m; i++) {
            ownedCols.add(new ArrayList<>(4));
        }
        for (var e : owner.entrySet()) {
            int x = keyX(e.getKey()), z = keyZ(e.getKey());
            ownedCols.get(e.getValue()[0]).add(
                    new Col(x, z, tread.get(e.getKey()), stairAt.get(e.getKey())));
        }
        // d-1: 중심 노반 · 다리 판정 (지면 = field.surfaceY — 순수·결정론) · 갓돌 가용·자리·낙차
        int[] centerT = new int[m];
        boolean[] bridgeAt = new boolean[m];
        boolean[] curbAvail = new boolean[2 * m];       // [2i]=neg [2i+1]=pos
        int[] curbX = new int[2 * m], curbZ = new int[2 * m], curbDrop = new int[2 * m];
        for (int i = 0; i < m; i++) {
            int[] c = spineCells.get(i);
            centerT[i] = tread.get(colKey(c[0], c[1]));
            bridgeAt[i] = centerT[i] - field.surfaceY(c[0], c[1]) - 1 >= BRIDGE_MIN_FLOAT;
            for (int side : new int[]{-1, 1}) {
                int off = HALF + 1 + (widen[i] == side ? 1 : 0);   // 확폭 쪽은 갓돌이 한 칸 더 바깥
                int k = 2 * i + (side < 0 ? 0 : 1);
                curbX[k] = c[0] + perps[i][0] * off * side;
                curbZ[k] = c[1] + perps[i][1] * off * side;
                curbAvail[k] = !tread.containsKey(colKey(curbX[k], curbZ[k]));
                curbDrop[k] = centerT[i] - field.surfaceY(curbX[k], curbZ[k]);
            }
        }
        // d-2: 난간 차등 — 다리 = 양옆 · 절벽/급경사 = 낭떠러지 쪽(낙차 ≥ CLIFF_DROP)만 ·
        //      굽이 참 = 바깥쪽 연속(모서리 감싸기) · 완만 지면 = 없음. (사용자 확정 양식 · 문턱 잠정)
        boolean[] railN = new boolean[m], railP = new boolean[m];
        for (int i = 0; i < m; i++) {
            railN[i] = curbAvail[2 * i] && (bridgeAt[i] || curbDrop[2 * i] >= CLIFF_DROP
                    || (landing[i] && widen[i] == -1));
            railP[i] = curbAvail[2 * i + 1] && (bridgeAt[i] || curbDrop[2 * i + 1] >= CLIFF_DROP
                    || (landing[i] && widen[i] == +1));
        }
        // d-3: 기둥(★A안 울타리 2단) — 간격(POST_EVERY) ∪ 급경사 굽이 정점(필수·바깥) ∪ 다리 양끝(양쪽).
        //      매몰 방지: 갓돌 자리 지형이 노반 위(surfaceY > centerT)면 그 쪽엔 안 세운다.
        //      등롱은 기둥 위 — LANTERN_POST_EVERY 기둥마다 (옛 독립 등롱 이관·정렬).
        boolean[] postN = new boolean[m], postP = new boolean[m], postLant = new boolean[m];
        int postOrdinal = 0;
        for (int i = 0; i < m; i++) {
            boolean interval = i > 0 && i % POST_EVERY == 0;
            boolean corner = steepCorners.contains(i);
            boolean bridgeEnd = bridgeAt[i]
                    && ((i == 0 || !bridgeAt[i - 1]) || (i == m - 1 || !bridgeAt[i + 1]));
            if (!interval && !corner && !bridgeEnd) {
                continue;
            }
            boolean canN = curbAvail[2 * i] && curbDrop[2 * i] >= 0;         // 매몰 아님 (지형 ≤ 노반)
            boolean canP = curbAvail[2 * i + 1] && curbDrop[2 * i + 1] >= 0;
            if (bridgeEnd) {
                postN[i] = canN;                        // 다리 양끝 — 양쪽 법수
                postP[i] = canP;
            } else if (corner) {
                int ws = widen[i] != 0 ? widen[i]       // 굽이 — 바깥쪽 법수 필수 (난간이 기둥에 연결)
                        : (curbDrop[2 * i] >= curbDrop[2 * i + 1] ? -1 : +1);
                postN[i] = ws < 0 && canN;
                postP[i] = ws > 0 && canP;
                if (!postN[i] && !postP[i]) {           // 바깥이 막혔으면 반대쪽이라도
                    postN[i] = canN;
                    postP[i] = !canN && canP;
                }
            } else {                                    // 간격 — 난간 있는 쪽 우선, 없으면 낙차 깊은 쪽
                if (railN[i] && canN) {
                    postN[i] = true;
                } else if (railP[i] && canP) {
                    postP[i] = true;
                } else if (canN && (!canP || curbDrop[2 * i] >= curbDrop[2 * i + 1])) {
                    postN[i] = true;
                } else if (canP) {
                    postP[i] = true;
                }
            }
            if (postN[i] || postP[i]) {
                postOrdinal++;
                postLant[i] = postOrdinal % LANTERN_POST_EVERY == 1;   // 첫 기둥부터 격번 등롱
            }
        }
        List<Node> nodes = new ArrayList<>(m);
        int ascend = 0, flat = 0;
        for (int i = 0; i < m; i++) {
            int[] c = spineCells.get(i);
            boolean up = i > 0 && centerT[i] > centerT[i - 1];
            if (up) {
                ascend++;
            } else {
                flat++;
            }
            boolean pier = i > 0 && i % PIER_EVERY == 0;
            nodes.add(new Node(c[0], c[1], centerT[i], up, perps[i][0], perps[i][1],
                    pier, bridgeAt[i], curbAvail[2 * i], curbAvail[2 * i + 1],
                    railN[i], railP[i], postN[i], postP[i], postLant[i], widen[i],
                    List.copyOf(ownedCols.get(i))));
        }

        Node foot = nodes.get(0);
        Node top = nodes.get(nodes.size() - 1);
        // 보고용 — 실제 최고 열 (골격 최고봉 근방 국소 최고점 탐색 · 경로와 무관한 참고값)
        int[] hi = highestColumn(spec, field);
        return new Plan(nodes, foot.x(), foot.standY(), foot.z(),
                top.x(), top.standY(), top.z(), ascend, flat, hi[0], hi[2], hi[1],
                List.copyOf(steepCorners));
    }

    /** 이웃 완화 — 발자국 전체에서 인접 열 단차 >1 이면 높은 쪽을 낮춘다 (내려가기만 → 수렴 · 결정론). */
    private static void relaxTreads(java.util.LinkedHashMap<Long, Integer> tread) {
        boolean relaxing = true;
        while (relaxing) {
            relaxing = false;
            for (var e : tread.entrySet()) {
                int x = keyX(e.getKey()), z = keyZ(e.getKey());
                int t = e.getValue();
                for (int[] d : NEIGHBORS4) {
                    Integer tn = tread.get(colKey(x + d[0], z + d[1]));
                    if (tn != null && t > tn + 1) {
                        t = tn + 1;
                        relaxing = true;
                    }
                }
                if (t != e.getValue()) {
                    e.setValue(t);
                }
            }
        }
    }

    /**
     * 고립 마루/골 여밈 — 한 셀만 이웃보다 정확히 ±1 튀는 자리를 이웃 높이로 눕힌다. 반복해서
     * 없어질 때까지 (연속 단차 ≤1 은 보존 — 눕혀도 이웃이 같아진다). 걸어 오름의 어색한 한 칸 턱 제거.
     */
    private static void smoothBumps(int[] stand) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 1; i < stand.length - 1; i++) {
                if (stand[i] == stand[i - 1] + 1 && stand[i] == stand[i + 1] + 1) {
                    stand[i] = stand[i - 1];           // 고립 마루 → 눕힌다
                    changed = true;
                } else if (stand[i] == stand[i - 1] - 1 && stand[i] == stand[i + 1] - 1) {
                    stand[i] = stand[i - 1];           // 고립 골 → 채운다
                    changed = true;
                }
            }
        }
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

    /** 4-이웃 (완화·계단 표기·자기시험 공용 — 결정 순서: 북·동·남·서) */
    private static final int[][] NEIGHBORS4 = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    /** 열 키 — (x,z) 를 하나의 long 으로 (상위 32 = x · 하위 32 = z). */
    private static long colKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int keyX(long key) {
        return (int) (key >> 32);
    }

    private static int keyZ(long key) {
        return (int) key;
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
     * 노드 하나의 노반 단면(폭 5 — 사용자 확정 +2 · 굽이 참은 바깥 +1 확폭)을 놓는다: 노반(계단/평탄) ·
     * 머리 공간 깎기 · 받침(낮게 뜨면 기단, 높게 뜨면 <b>다리</b>) · <b>목책 난간</b>(차등 — 계획이
     * 정했다) · <b>법수 기둥 + 기둥 위 등롱</b>. {@code tally} 에 census 를 누적한다. 사람이 지은 것·
     * 이미 놓인 노반은 지나친다(멱등 — 재실행 안전 · 서 있는 것은 센다). 물은 안 놓는다.
     *
     * <p><b>다리(잔도·현공교)</b>: 판정은 계획이 정했다({@code n.bridge()} — 지면 = surfaceY 순수 모형).
     * 다리 노드는 아래를 통짜로 안 채우고 <b>{@value #DECK_THICKNESS}칸 두께 상판 덩어리</b>(걷는 면 y
     * 불변 · 아래 belly 2켜 — ★사용자 확정 "3칸 굵기")만 남긴 뒤, {@value #PIER_EVERY}노드마다 양옆
     * <b>{@value #PIER_SIZE}×{@value #PIER_SIZE} 기둥</b>(잠정)을 바닥까지 내린다 → 상판 밑이 대부분
     * 허공이라 다리로 읽힌다. 5칸 보행로 양옆에 갓돌(상판 연장) + {@code Spruce} 울타리 난간을 세워
     * 걸으며 안 떨어진다 (보행로는 트여 폭 5 유지).
     */
    public static void paveNode(World world, RangeSpec spec, Node n, Tally tally) {
        // 노드 소구역 청크 선로드 (콘솔 조성은 언로드 상태일 수 있다 · 확폭+갓돌+2×2 기둥 여유)
        int pad = HALF + 4;
        ensureChunks(world, n.x() - pad, n.z() - pad, n.x() + pad, n.z() + pad);

        int s = n.standY();
        // ★다리 판정 — 계획이 정했다 (n.bridge() — 지면 = field.surfaceY · 순수·결정론).
        //   월드 읽기 판정은 식생·재실행·자기 상판(3칸 두께 belly 가 STONE/ANDESITE/TUFF 라
        //   지형과 구분 불가)에 흔들린다 — 계획 판정은 언제 다시 돌아도 같다.
        boolean bridge = n.bridge();
        if (bridge) {
            tally.bridge();
        }

        // ★한 열 한 번 — 이 노드가 소유한 열만 포장한다 (겹치는 열은 그 주인이 이미/나중에 포장 —
        //   수직 겹침이 원리적으로 불가). 열마다 계획이 확정한 treadY·계단 방위를 그대로 쓴다.
        for (Col c : n.cols()) {
            int cx = c.x(), cz = c.z();
            int t = c.treadY();
            int ground = terrainGroundY(world, cx, cz);      // 험산 실지면 (나무·우리 시설 제외)

            // (1) 머리 공간 — 노반 위 바위·초목을 HEADROOM 만 걷어낸다. ★깊은 마루 절삭(10칸) 철회
            //     (사용자: "위의 공간은 되돌리는 게 좋을 것 — 너무 붕 떠 보임") → 두꺼운 마루는 천장
            //     회랑/터널로 지난다. 노반·계단·등롱은 NATURAL 밖이라 안 걷힌다 (멱등).
            for (int y = t + 1; y <= t + HEADROOM; y++) {
                Block b = world.getBlockAt(cx, y, cz);
                Material mm = b.getType();
                if (!mm.isAir() && (NATURAL.contains(mm) || foliage(mm))) {
                    b.setType(Material.AIR, false);
                    tally.carve();
                }
            }
            // (2) 노반(상판) — 낮은 이웃에서 오르는 열은 석계단(facing=오름 방향), 아니면 평탄 노반
            Block tread = world.getBlockAt(cx, t, cz);
            if (c.stair() != null) {
                Stairs data = (Stairs) Material.STONE_BRICK_STAIRS.createBlockData();
                data.setFacing(c.stair());
                data.setHalf(Bisected.Half.BOTTOM);
                tread.setBlockData(data, false);
                tally.stair();
            } else {
                tread.setType(Material.STONE_BRICKS, false);
                tally.flat();
            }
            // (3) 받침 — 다리면 ★3칸 두께 상판 덩어리(걷는 면 y 불변 · 아래로 2칸 더 — 사용자 확정
            //     "블럭 하나의 굵기라 부실해 — 3칸 굵기로") 아래는 허공. 낮게 뜨면(1~2칸) 기단 통짜.
            if (bridge) {
                for (int y = t - 1; y >= t - (DECK_THICKNESS - 1); y--) {
                    Block b = world.getBlockAt(cx, y, cz);
                    Material bm = b.getType();
                    if (bm.isAir() || NATURAL.contains(bm) || foliage(bm)) {
                        b.setType(deckMaterial(cx, y, cz), false);
                        tally.fill();
                    }
                }
            } else {
                for (int y = t - 1; y > ground; y--) {
                    Block b = world.getBlockAt(cx, y, cz);
                    if (b.getType().isAir()) {
                        b.setType(baseMaterial(cx, y, cz), false);
                        tally.fill();
                    }
                }
            }
        }

        // (3b) ★목책 갓돌·난간 (차등 — 계획이 정했다: 다리 양옆 · 절벽 낭떠러지 쪽 · 굽이 참 바깥).
        //      난간은 갓돌 위 — 부유 조각 없음. 재실행이면 서 있는 난간도 센다 (census 침묵 0 방지).
        for (int side : new int[]{-1, 1}) {
            boolean rail = side < 0 ? n.railNeg() : n.railPos();
            boolean post = side < 0 ? n.postNeg() : n.postPos();
            if (!rail && !post) {
                continue;
            }
            int off = n.curbOffset(side);
            int ex = n.x() + n.perpX() * off * side;
            int ez = n.z() + n.perpZ() * off * side;
            // 갓돌 — 난간·기둥의 받침 (상판 연장 · Stone Brick)
            Block curb = world.getBlockAt(ex, s, ez);
            Material cm = curb.getType();
            if (cm.isAir() || NATURAL.contains(cm) || foliage(cm)) {
                curb.setType(Material.STONE_BRICKS, false);
            }
            // 난간 — 갓돌 위 울타리 (팔레트 목재 Spruce · 울타리는 이웃 울타리/기둥에 자동 연결)
            if (rail && RAILING) {
                Block railB = world.getBlockAt(ex, s + 1, ez);
                Material rm = railB.getType();
                if (rm.isAir() || NATURAL.contains(rm) || foliage(rm)) {
                    railB.setType(Material.SPRUCE_FENCE, false);
                    tally.rail();
                } else if (rm == Material.SPRUCE_FENCE) {
                    tally.rail();
                }
            }
            // ★기둥 — 울타리 2단 겹침 (A안 — 사용자 확정: SPRUCE_LOG 법수 폐지 · "더 안 이뻐 보인다").
            //   아래 단은 난간과 연속(같은 fence — 바닐라가 수직으로 기둥 모양으로 잇는다), 위 단이
            //   난간보다 딱 한 단 높은 가는 표주. 등롱은 그 꼭대기 위 (fence 에 자연스럽게 얹힌다).
            //   옛 SPRUCE_LOG 는 foliage(_LOG)라 재실행 때 fence 로 자연 승격. 서 있는 것도 센다.
            if (post) {
                boolean stood = false;
                for (int y = s + 1; y <= s + POST_HEIGHT; y++) {
                    Block b = world.getBlockAt(ex, y, ez);
                    Material bm = b.getType();
                    if (bm.isAir() || NATURAL.contains(bm) || foliage(bm)) {
                        b.setType(POST_MATERIAL, false);
                        stood = true;
                    } else if (bm == POST_MATERIAL) {
                        stood = true;
                    }
                }
                if (stood) {
                    tally.post();
                }
                if (n.postLantern()) {
                    Block cap = world.getBlockAt(ex, s + POST_HEIGHT + 1, ez);
                    Material capM = cap.getType();
                    if (capM.isAir() || NATURAL.contains(capM) || foliage(capM)) {
                        cap.setType(Material.LANTERN, false);
                        tally.lantern();
                    } else if (capM == Material.LANTERN) {
                        tally.lantern();
                    }
                }
            }
        }

        // (3c) ★다리 지지 기둥 — 드문 노드에서만 바닥까지 (드물어야 다리로 읽힌다). 단면 2×2 (상판
        //      3칸 두께에 1×1 은 가늘다 — 잠정): 진행축 2 × 갓돌→안쪽 2. 공기만 채워 belly·지형 무접촉.
        if (bridge && n.pier()) {
            int tx = n.perpZ(), tz = n.perpX();              // 진행축 단위 (perp 에 수직)
            for (int side : new int[]{-1, 1}) {
                if (side < 0 ? !n.curbNeg() : !n.curbPos()) {
                    continue;
                }
                int off = n.curbOffset(side);
                int ex = n.x() + n.perpX() * off * side;
                int ez = n.z() + n.perpZ() * off * side;
                boolean legged = false;
                for (int a = 0; a < PIER_SIZE; a++) {
                    for (int bIn = 0; bIn < PIER_SIZE; bIn++) {
                        int px = ex + a * tx - bIn * n.perpX() * side;   // 갓돌에서 안쪽(상판 밑)으로
                        int pz = ez + a * tz - bIn * n.perpZ() * side;
                        int pg = terrainGroundY(world, px, pz);
                        for (int y = s - 1; y > pg; y--) {
                            Block b = world.getBlockAt(px, y, pz);
                            if (b.getType().isAir()) {
                                b.setType(baseMaterial(px, y, pz), false);
                                legged = true;
                            }
                        }
                    }
                }
                if (legged) {
                    tally.pier();
                }
            }
        }
    }

    /** 기단 재료 — Stone/Andesite/Tuff (기단 팔레트 · 결정론 해시로 섞는다). */
    private static Material baseMaterial(int x, int y, int z) {
        double r = h01(x, y, z, SALT_BASE);
        return r < 0.55 ? Material.STONE : r < 0.82 ? Material.ANDESITE : Material.TUFF;
    }

    /**
     * 다리 상판 belly 재료 — 걷는 면 아래 {@value #DECK_THICKNESS}−1 켜. Stone Brick 이 골재
     * (STONE/ANDESITE/TUFF)를 물고 있는 육중한 석축 결 (기단 팔레트 안 배합 · 결정론 해시).
     */
    private static Material deckMaterial(int x, int y, int z) {
        double r = h01(x, y, z, SALT_DECK);
        return r < 0.45 ? Material.STONE_BRICKS : baseMaterial(x, y, z);
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
        private long bridges;
        private long piers;
        private long rails;
        private long posts;

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

        void bridge() {
            bridges++;
        }

        void pier() {
            piers++;
        }

        void rail() {
            rails++;
        }

        void post() {
            posts++;
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

        /** 다리 상판 노드 수 (뜬 구간을 통짜로 안 채우고 다리로 지은 노드). */
        public long bridges() {
            return bridges;
        }

        /** 다리 지지 기둥 다리(leg) 수. */
        public long piers() {
            return piers;
        }

        /** 난간(울타리) 칸 수. */
        public long rails() {
            return rails;
        }

        /** 난간 기둥(울타리 2단 — A안) 수. */
        public long posts() {
            return posts;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 자기 시험 — ★실물 기준 (2026-07-16 재작성 · 사용자 실측 「계단 2개 위아래」 뒤).
    //   옛 시험은 plan 의 노드 standY 만 쟀다 — 실물(폭 3 포장)이 열 겹침으로 어긋났는데 통과했다.
    //   이제 paveNode 가 실제로 놓는 것의 순수 모형 = Plan 의 열 목록(cols — 같은 데이터가 곧 배치)을
    //   그대로 잰다. 서버 없이 돈다 (paper-api 클래스패스 필요).
    //   조율자: java -cp <classes>:<paper-api.jar 등> com.honcheon.mvt.forge.TrailBuilder
    //   검사: ① 수직 겹침 0 — 같은 열에 노반 둘(계단 위아래) 금지 (열 유일성)
    //         ② 발자국 전체 인접 열 노반 단차 ≤1 (2칸 턱 감지 — 폭 5 전체)
    //         ③ 중심선 걸음: 연속 셀 4-연결 · 오름 +1 칸은 반드시 걸음 방향 계단 (점프 없이)
    //         ④ 등롱 매몰 0 — 등롱 자리가 노반 열과 안 겹침
    //         ⑤ 회귀 표본 x=-5·z=194~206 (사용자 실측 단면 — B+S 겹침 재현 여부)
    //         ⑥ 눈을 시험하는 눈 (심은 겹침·2칸 턱을 검출기가 잡는가)
    // ═══════════════════════════════════════════════════════════════════

    /** 실물 포장 모형 — Plan 의 모든 열을 한 지도로 (paveNode 가 놓는 노반 그대로). 겹침은 여기서 드러난다 */
    private static java.util.LinkedHashMap<Long, Col> pavementOf(Plan p, int[] dupOut) {
        java.util.LinkedHashMap<Long, Col> map = new java.util.LinkedHashMap<>();
        int dup = 0;
        for (Node n : p.nodes()) {
            for (Col c : n.cols()) {
                if (map.put(colKey(c.x(), c.z()), c) != null) {
                    dup++;                       // 같은 열이 두 번 — 수직 겹침의 씨앗
                }
            }
        }
        dupOut[0] = dup;
        return map;
    }

    /** 인접 열 최대 단차 {최대 단차, x, z} — 발자국 전체 (①②의 눈) */
    private static int[] worstSeam(java.util.Map<Long, Col> pave) {
        int max = 0, mx = 0, mz = 0;
        for (Col c : pave.values()) {
            for (int[] d : NEIGHBORS4) {
                Col nb = pave.get(colKey(c.x() + d[0], c.z() + d[1]));
                if (nb != null && Math.abs(nb.treadY() - c.treadY()) > max) {
                    max = Math.abs(nb.treadY() - c.treadY());
                    mx = c.x();
                    mz = c.z();
                }
            }
        }
        return new int[]{max, mx, mz};
    }

    public static void main(String[] args) {
        RangeSpec spec = RangeSpec.hwasan(0, 0, 63);
        RangeField field = new RangeField(spec);
        Plan plan = plan(spec, field);
        boolean ok = true;

        System.out.printf("도보길 계획: 노드 %d · 트레일헤드(%d,%d,%d) → 정상 Pm(%d,%d,%d) · 오름 %d · 계단 %d 평탄 %d%n",
                plan.nodes().size(), plan.footX(), plan.footY(), plan.footZ(),
                plan.summitX(), plan.summitY(), plan.summitZ(),
                plan.summitY() - plan.footY(), plan.ascendSteps(), plan.flatSteps());

        // ① 수직 겹침 (열 유일성) — paveNode 는 cols 만 놓으므로 지도의 중복 = 실물의 포개짐
        int[] dup = new int[1];
        java.util.LinkedHashMap<Long, Col> pave = pavementOf(plan, dup);
        long stairCols = pave.values().stream().filter(c -> c.stair() != null).count();
        System.out.printf("실물 포장: 열 %d (계단 %d · 평탄 %d) · 중복 열 %d%n",
                pave.size(), stairCols, pave.size() - stairCols, dup[0]);
        if (dup[0] > 0) {
            System.out.println("FAIL 겹침: 같은 열이 두 번 포장된다 — 계단이 노반 위에 얹힌다 (실측 재현)");
            ok = false;
        }

        // ② 발자국 전체 인접 열 단차 ≤1 (2칸 턱 — 폭 5 어디로 걸어도)
        int[] seam = worstSeam(pave);
        System.out.printf("발자국 연속성: 인접 열 최대 단차 %d @ (%d,%d) (≤%d)%n",
                seam[0], seam[1], seam[2], MAX_STEP);
        if (seam[0] > MAX_STEP) {
            System.out.println("FAIL 2칸 턱: 발자국 안 인접 열 단차 > 1 — 못 걷는 면이 있다");
            ok = false;
        }

        // ③ 중심선 걸음 — 4-연결 · 오름 +1 은 걸음 방향 계단 (점프 없이 · facing 낮은 면이 걸어오는 쪽)
        List<Node> ns = plan.nodes();
        for (int i = 1; i < ns.size(); i++) {
            Node a = ns.get(i - 1), b = ns.get(i);
            int dist = Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z());
            if (dist > 1) {
                System.out.printf("FAIL 코너: 노드 %d→%d 셀거리 %d — 4-연결 틈%n", i - 1, i, dist);
                ok = false;
            }
            Col cb = pave.get(colKey(b.x(), b.z()));
            Col ca = pave.get(colKey(a.x(), a.z()));
            if (ca == null || cb == null) {
                System.out.printf("FAIL 중심선: 노드 %d 중심 열이 포장에 없다%n", i);
                ok = false;
                continue;
            }
            int rise = cb.treadY() - ca.treadY();
            if (Math.abs(rise) > MAX_STEP) {
                System.out.printf("FAIL 중심선 2칸 턱: 노드 %d→%d 노반 %d→%d%n", i - 1, i, ca.treadY(), cb.treadY());
                ok = false;
            }
            if (rise == 1) {
                BlockFace step = cardinal(b.x() - a.x(), b.z() - a.z());
                if (cb.stair() == null) {
                    System.out.printf("FAIL 계단 결락: 노드 %d (%d,%d) +1 오름인데 평탄 노반 — 점프 필요%n",
                            i, b.x(), b.z());
                    ok = false;
                } else if (cb.stair() != step) {
                    System.out.printf("FAIL 계단 방위: 노드 %d (%d,%d) 걸음 %s 인데 facing %s — 등이 막는다%n",
                            i, b.x(), b.z(), step, cb.stair());
                    ok = false;
                }
            }
        }

        // ③b 계단 방위 전수 — 모든 계단 열: facing 반대쪽(걸어오는 낮은 면) 이웃이 정확히 한 칸 낮아야
        //     (facing 이 뒤집히면 계단 등이 걸어오는 이를 막는다 — 곁 열 포함 전수)
        for (Col c : pave.values()) {
            if (c.stair() == null) {
                continue;
            }
            Col low = pave.get(colKey(c.x() - c.stair().getModX(), c.z() - c.stair().getModZ()));
            if (low == null || low.treadY() != c.treadY() - 1) {
                System.out.printf("FAIL 계단 방위 전수: (%d,%d) facing %s 인데 그 반대쪽이 한 칸 낮지 않다%n",
                        c.x(), c.z(), c.stair());
                ok = false;
            }
        }

        // ④ 목책 배선 전수 — 난간·기둥·등롱이 노반 열과 안 겹치고(매몰 0), 난간 밑엔 갓돌이 선다
        //    (부유 0 — paveNode 가 rail/post 자리마다 갓돌을 먼저 놓는다), 등롱은 기둥 위에만.
        for (Node n : ns) {
            for (int side : new int[]{-1, 1}) {
                boolean rail = side < 0 ? n.railNeg() : n.railPos();
                boolean post = side < 0 ? n.postNeg() : n.postPos();
                if (!rail && !post) {
                    continue;
                }
                long ck = colKey(n.x() + n.perpX() * n.curbOffset(side) * side,
                        n.z() + n.perpZ() * n.curbOffset(side) * side);
                if (pave.containsKey(ck)) {
                    System.out.printf("FAIL 목책 매몰: (%d,%d) 난간/기둥 자리가 노반 열 — 길을 막는다%n",
                            keyX(ck), keyZ(ck));
                    ok = false;
                }
            }
            if (n.postLantern() && !n.postNeg() && !n.postPos()) {
                System.out.printf("FAIL 등롱: 노드 (%d,%d) 등롱이 기둥 없이 떠 있다%n", n.x(), n.z());
                ok = false;
            }
        }

        // ⑤ 회귀 표본 — 사용자 실측 단면 x=-5 · z=194~206 (B+S 수직 겹침·+2 턱이 재현되는가)
        System.out.println("회귀 표본 (x=-5 · z=194~206 · 실물 포장 열):");
        Integer prevT = null;
        for (int z = 206; z >= 194; z--) {
            Col c = pave.get(colKey(-5, z));
            if (c == null) {
                System.out.printf("  (x=-5,z=%d) — 포장 밖%n", z);
                prevT = null;
                continue;
            }
            System.out.printf("  (x=-5,z=%d) 노반y=%d %s%n", z, c.treadY(),
                    c.stair() != null ? "계단(" + c.stair() + ")" : "평탄");
            if (prevT != null && Math.abs(c.treadY() - prevT) > MAX_STEP) {
                System.out.printf("FAIL 회귀: x=-5 z=%d 단차 %d — 실측 +2 턱 재현%n", z, c.treadY() - prevT);
                ok = false;
            }
            prevT = c.treadY();
        }

        // ⑦ ★census 하한 눈 (2026-07-16 「전부 0」 침묵 퇴행 뒤) — 깨끗한 월드 모형(지면 = field.surfaceY)
        //    에서 계획이 낳아야 할 등롱·다리·절삭·기단이 최소 1 은 되는가. 조성 경로가 끊기면(등롱 게이트
        //    전량 필터·다리 판정 사멸·절삭 미연결) 여기서 기계가 문다 — census 전멸은 다시 조용히 못 온다.
        int expLanterns = 0, expBridges = 0, planBridges = 0, expPosts = 0, expRailSides = 0, oneSidedRails = 0;
        long expCarveCols = 0, expFillCols = 0;
        for (Node n : ns) {
            if (n.postLantern()) {
                expLanterns++;
            }
            if (n.postNeg() || n.postPos()) {
                expPosts++;
            }
            if (n.railNeg()) {
                expRailSides++;
            }
            if (n.railPos()) {
                expRailSides++;
            }
            if (n.railNeg() ^ n.railPos()) {
                oneSidedRails++;                 // 절벽 한쪽 난간 (차등의 증거)
            }
            if (n.standY() - field.surfaceY(n.x(), n.z()) - 1 >= BRIDGE_MIN_FLOAT) {
                expBridges++;                    // 독립 재계산 (지면 = surfaceY 모형)
            }
            if (n.bridge()) {
                planBridges++;                   // 계획이 박은 판정 (조성이 그대로 쓴다)
            }
            for (Col c : n.cols()) {
                int g = field.surfaceY(c.x(), c.z());
                if (g >= c.treadY() + 1) {
                    expCarveCols++;              // 노반 위 바위 → 절삭이 일할 열
                }
                if (c.treadY() - 1 > g) {
                    expFillCols++;               // 뜬 노반 → 기단/다리가 일할 열
                }
            }
        }
        System.out.printf("census 하한(깨끗한 월드 모형): 등롱 %d · 기둥 노드 %d · 난간 변 %d(한쪽만 %d) · "
                        + "다리 노드 %d(계획 %d) · 절삭 열 %d · 뜬 열 %d%n",
                expLanterns, expPosts, expRailSides, oneSidedRails, expBridges, planBridges,
                expCarveCols, expFillCols);
        if (expPosts < 1) {
            System.out.println("FAIL census: 기둥(법수) 0 — 간격/모서리/다리 양끝 배치가 전멸");
            ok = false;
        }
        if (expRailSides < 1) {
            System.out.println("FAIL census: 난간 0 — 차등 판정이 전량을 걸렀다");
            ok = false;
        }
        // 절벽 한쪽 난간(차등) 검증 — 난간 선 비-다리 변은 낙차 ≥ CLIFF_DROP 또는 굽이 참 바깥이어야
        for (Node n : ns) {
            if (n.bridge()) {
                continue;
            }
            for (int side : new int[]{-1, 1}) {
                if (!(side < 0 ? n.railNeg() : n.railPos())) {
                    continue;
                }
                int ex = n.x() + n.perpX() * n.curbOffset(side) * side;
                int ez = n.z() + n.perpZ() * n.curbOffset(side) * side;
                boolean cliff = n.standY() - field.surfaceY(ex, ez) >= CLIFF_DROP;
                boolean landingOuter = n.widenSide() == side;
                if (!cliff && !landingOuter) {
                    System.out.printf("FAIL 난간 차등: (%d,%d) %s 쪽 난간인데 낭떠러지도 굽이 바깥도 아니다%n",
                            n.x(), n.z(), side < 0 ? "neg" : "pos");
                    ok = false;
                }
            }
        }

        // ⑧ ★굽이 참·법수 표본 (사용자 추가 「회전 급경사 개선」 — 문턱 잠정: STEEP_WIN ±4 오름 ≥4)
        //    참의 뜻: 참 안(정점±LANDING_HALF) 노반이 전부 한 등고(평탄 돌음참) + 정점 셀은 계단 아님.
        //    참 첫 셀의 계단은 「참으로 올라서는 마지막 단」이라 허용 (걷는 표면은 참 전체가 평평하다).
        int cornerNoPost = 0, cornerNoOuterRail = 0, cornerStairs = 0;
        for (int j : plan.steepCorners()) {
            int zoneT = Integer.MIN_VALUE;
            for (int k = Math.max(0, j - 1); k <= Math.min(ns.size() - 1, j + 1); k++) {
                Col cc = pave.get(colKey(ns.get(k).x(), ns.get(k).z()));
                if (cc == null) {
                    continue;
                }
                if (zoneT == Integer.MIN_VALUE) {
                    zoneT = cc.treadY();
                } else if (cc.treadY() != zoneT) {
                    cornerStairs++;                 // 참 안 등고가 갈렸다 — 평탄 참 아님
                }
            }
            Col vertex = pave.get(colKey(ns.get(j).x(), ns.get(j).z()));
            if (vertex != null && vertex.stair() != null) {
                cornerStairs++;                     // 정점 셀에 계단 — 모서리에서 꺾이며 오른다 (금지)
            }
            Node cn = ns.get(j);
            if (!cn.postNeg() && !cn.postPos()) {
                cornerNoPost++;                 // 모서리 법수 없음 (양쪽 다 매몰/점유면 불가)
            }
            boolean outerRail = false;
            for (int k = Math.max(0, j - 1); k <= Math.min(ns.size() - 1, j + 1); k++) {
                Node nk = ns.get(k);
                if (nk.widenSide() < 0 ? nk.railNeg() : nk.widenSide() > 0 && nk.railPos()) {
                    outerRail = true;
                }
            }
            if (!outerRail) {
                cornerNoOuterRail++;
            }
        }
        System.out.printf("굽이 참: 급경사 굽이 %d곳 · 참 평탄 위반 %d (0 이어야) · 법수 없는 굽이 %d · "
                        + "바깥 난간 없는 굽이 %d%n",
                plan.steepCorners().size(), cornerStairs, cornerNoPost, cornerNoOuterRail);
        if (cornerStairs > 0) {
            System.out.println("FAIL 굽이 참: 참 안 등고가 갈렸거나 정점 셀에 계단 — 평탄 돌음참이 아니다");
            ok = false;
        }
        if (!plan.steepCorners().isEmpty() && cornerNoPost > plan.steepCorners().size() / 2) {
            System.out.println("FAIL 굽이 법수: 급경사 굽이 과반에 모서리 기둥이 없다");
            ok = false;
        }
        if (!plan.steepCorners().isEmpty() && cornerNoOuterRail > plan.steepCorners().size() / 2) {
            System.out.println("FAIL 굽이 난간: 급경사 굽이 과반에 바깥 난간이 없다 — 모서리를 안 감싼다");
            ok = false;
        }
        // 쉼단 — 연속 오름이 REST_RUN 을 넘지 않는가 (계단 벽 방지)
        int maxRun = 0, run = 0;
        for (int i = 1; i < ns.size(); i++) {
            run = ns.get(i).standY() > ns.get(i - 1).standY() ? run + 1 : 0;
            maxRun = Math.max(maxRun, run);
        }
        System.out.printf("쉼단: 최장 연속 오름 %d (상한 %d)%n", maxRun, REST_RUN);
        if (maxRun > REST_RUN) {
            System.out.printf("FAIL 쉼단: 연속 오름 %d > %d — 계단 벽%n", maxRun, REST_RUN);
            ok = false;
        }
        // 정상 도달 보존 — 참·쉼단이 오름을 지연시켜도 사슬이 정상까지 따라잡았는가
        Node last = ns.get(ns.size() - 1);
        int summitDesired = smoothGround(spec, field, last.x(), last.z());
        System.out.printf("정상 도달: 계획 %d vs 골격 지면 %d (차 %d)%n",
                last.standY(), summitDesired, last.standY() - summitDesired);
        if (Math.abs(last.standY() - summitDesired) > 2) {
            System.out.println("FAIL 정상 도달: 참·쉼단 지연을 사슬이 못 따라잡았다 — 오름 재배분 재검토");
            ok = false;
        }
        if (planBridges != expBridges) {
            System.out.printf("FAIL 다리 판정: 계획 플래그 %d ≠ 지면 모형 재계산 %d — 판정이 어긋났다%n",
                    planBridges, expBridges);
            ok = false;
        }
        // ★상판 두께 계약 (사용자 확정 3칸): 걷는 면 y 불변 + 아래 belly ≥ 2켜. belly 는 제 열의
        //   treadY 아래에만 놓이고 열은 유일(①)하므로 다른 노반을 묻지 못한다 — 두께 상수만 물면 된다.
        if (DECK_THICKNESS != 3) {
            System.out.println("FAIL 상판 두께: DECK_THICKNESS " + DECK_THICKNESS + " ≠ 3 (사용자 확정값)");
            ok = false;
        }
        if (planBridges > 0 && DECK_THICKNESS - 1 < 2) {
            System.out.println("FAIL 상판 두께: 다리가 있는데 belly 켜 " + (DECK_THICKNESS - 1) + " < 2 — 얇은 상판 회귀");
            ok = false;
        }
        // ★기둥 A안 계약 (사용자 확정): 기둥 = 울타리 2단 (SPRUCE_LOG 폐지 — 로그 0). 재료가 울타리라야
        //   난간과 수직 연속(기둥 모양)·지면 술어(TRAIL_MADE)가 지나친다. 높이 2단 = 난간 + 1.
        if (POST_MATERIAL != Material.SPRUCE_FENCE) {
            System.out.println("FAIL 기둥 A안: POST_MATERIAL " + POST_MATERIAL
                    + " ≠ SPRUCE_FENCE (울타리 2단 확정 — 로그 회귀 금지)");
            ok = false;
        }
        if (!TRAIL_MADE.contains(POST_MATERIAL)) {
            System.out.println("FAIL 기둥 A안: 기둥 재료가 TRAIL_MADE 밖 — 지면 술어가 기둥을 지면으로 본다");
            ok = false;
        }
        if (POST_HEIGHT != 2) {
            System.out.println("FAIL 기둥 A안: POST_HEIGHT " + POST_HEIGHT + " ≠ 2 (난간보다 딱 한 단 높음)");
            ok = false;
        }
        if (expLanterns < 1) {
            System.out.println("FAIL census: 등롱 0 — 게이트가 전량을 걸렀다 (간격 배치 사멸)");
            ok = false;
        }
        if (expFillCols >= 1 && expBridges < 1) {
            System.out.println("FAIL census: 뜬 구간이 있는데 다리 노드 0 — 다리 판정 경로가 끊겼다");
            ok = false;
        }
        if (expCarveCols < 1) {
            System.out.println("FAIL census: 절삭 열 0 — 통짜 험산 위인데 깎을 바위가 없다? (절삭 경로 확인)");
            ok = false;
        }
        if (expFillCols < 1) {
            System.out.println("FAIL census: 뜬 열 0 — 험산 위인데 기단/다리가 일할 자리가 없다? (판정 확인)");
            ok = false;
        }

        // ⑥ 눈을 시험하는 눈 — 심은 결함을 검출기가 잡는가
        //   (a) 겹침 눈: 같은 열 두 번 (계단이 노반 위에) → dup 검출기가 잡아야
        java.util.LinkedHashMap<Long, Col> fake = new java.util.LinkedHashMap<>();
        int fakeDup = 0;
        for (Col c : new Col[]{new Col(0, 0, 100, null), new Col(0, 0, 101, BlockFace.NORTH)}) {
            if (fake.put(colKey(c.x(), c.z()), c) != null) {
                fakeDup++;
            }
        }
        if (fakeDup == 0) {
            System.out.println("FAIL 자기검증: 심은 수직 겹침(같은 열 노반+계단)을 못 잡는다 — 눈이 죽었다");
            ok = false;
        }
        //   (b) 단차 눈: 인접 열 3칸 턱 → worstSeam 이 잡아야
        java.util.LinkedHashMap<Long, Col> fake2 = new java.util.LinkedHashMap<>();
        fake2.put(colKey(0, 0), new Col(0, 0, 100, null));
        fake2.put(colKey(1, 0), new Col(1, 0, 103, null));
        if (worstSeam(fake2)[0] <= MAX_STEP) {
            System.out.println("FAIL 자기검증: 심은 3칸 턱을 못 잡는다 — 눈이 죽었다");
            ok = false;
        }
        //   (c2) 지면 술어 눈: 나뭇잎·대나무·꽃·우리 시설(상판·난간·등롱)은 지면이 아니고, 돌·기단은 지면
        //        (census 전멸의 원인 — getHighestBlockYAt 이 나무 꼭대기·자기 상판을 지면으로 읽었다)
        Material[] notGround = {Material.OAK_LEAVES, Material.SPRUCE_LOG, Material.BAMBOO,
                Material.STONE_BRICKS, Material.STONE_BRICK_STAIRS, Material.SPRUCE_FENCE,
                Material.LANTERN, Material.POPPY, Material.AIR};
        for (Material mm : notGround) {
            if (groundSolid(mm)) {
                System.out.println("FAIL 자기검증: 지면 술어가 " + mm + " 를 지면으로 본다 — 다리/기단 판정이 다시 죽는다");
                ok = false;
            }
        }
        for (Material mm : new Material[]{Material.STONE, Material.ANDESITE, Material.TUFF,
                Material.GRASS_BLOCK, Material.DEEPSLATE}) {
            if (!groundSolid(mm)) {
                System.out.println("FAIL 자기검증: 지면 술어가 " + mm + " 를 지면으로 안 본다 — 눈이 죽었다");
                ok = false;
            }
        }
        //   (c) 계단 방위 눈: 뒤집힌 facing(낮은 면이 facing 쪽) → ③b 술어가 잡아야
        java.util.LinkedHashMap<Long, Col> fake3 = new java.util.LinkedHashMap<>();
        fake3.put(colKey(0, 0), new Col(0, 0, 100, null));                       // 낮은 열 (서쪽)
        Col inverted = new Col(1, 0, 101, BlockFace.WEST);                        // ★facing 이 낮은 쪽을 가리킴 (뒤집힘)
        fake3.put(colKey(1, 0), inverted);
        Col lowOpp = fake3.get(colKey(inverted.x() - inverted.stair().getModX(),
                inverted.z() - inverted.stair().getModZ()));
        if (lowOpp != null && lowOpp.treadY() == inverted.treadY() - 1) {
            System.out.println("FAIL 자기검증: 심은 뒤집힌 계단 방위를 못 잡는다 — 눈이 죽었다");
            ok = false;
        }

        System.out.println((ok ? "PASS" : "FAIL") + " — TrailBuilder 자기 시험 (실물 포장 기준 · 폭 "
                + (2 * HALF + 1) + "칸 · 머리 공간 " + HEADROOM + "칸 · 다리 문턱 " + BRIDGE_MIN_FLOAT
                + "칸 — 폭·회랑은 사용자 확정 +2)");
        if (!ok) {
            System.exit(1);
        }
    }
}
