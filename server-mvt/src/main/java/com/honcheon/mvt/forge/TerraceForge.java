package com.honcheon.mvt.forge;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.DyeColor;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 석축 테라스 기계 — <b>화산 캠퍼스의 단(段) 패드를 산비탈에 앉힌다</b> (B-146 의 처방).
 *
 * <p>설계 정본: {@code docs/design/hwasan_build_enhancement_v1.md} §2(★v2 — 20구역 마스터플랜이
 * 배치의 정본)·§3(기계). 레퍼런스({@code 화산파/} 13장)의 문법 — 건물은 절벽을 깎은 자리가
 * 아니라 <b>쌓은 석축 위</b>에 선다. 옹벽이 패드 가장자리에서 실지형까지 내려가 닿고, 대계단
 * 몸체도 전 열이 접지하므로, <b>건물이 뜰 자리가 구조적으로 없다</b> — 「본전이 떴다」(B-146 ·
 * 허공 블록 41)의 재발을 배치가 아니라 구조가 막는다.
 *
 * <p>★v2 파라메트릭: 마스터플랜은 일렬 축선이 아니라 <b>남(산문)→북(정상) 척추 + 좌우로
 * 벌어지는 구역</b>이다. 그래서 이 기계는 단을 「축선상 일렬」로 받지 않고 <b>패드 목록
 * (중심·크기·목표고)</b>과 <b>계단 링크(윗패드의 어느 면에서 아랫패드로 내려가는가)</b>로 받는다.
 * {@link #pavePad} 는 월드+패드(중심·크기·y)만 알면 되므로 곁봉 정상 패드(슬라이스 3 —
 * 19·20구역)에도 그대로 재사용된다.
 *
 * <p><b>깎기 최소 · 쌓기 우선</b> (사용자 계율 — 캠퍼스 문서 「자연 우선」): 목표고 위로 솟은
 * 지형만 깎고 나머지는 석축이 덮는다. 계획({@link #plan})이 패드마다 실지형(p85)과 목표고의
 * 어긋남을 재어 알려 준다 — 잠정 목표고는 골격 유도값 【제안】이고, 어긋남이 크면 빨간펜이
 * 수를 고친다 (코드가 조용히 지어내지 않는다).
 *
 * <p>★금지 재료 (B-195 · HANDOFF 함정): {@code BARREL}(가구_3D 오버라이드가 이웃 벽을 뚫는다) ·
 * {@code LIGHT}(컬링 누명 전과). 이 기계의 팔레트는 {@link #palette()}가 전부이고,
 * 눈({@code tools/TerraceForgeSelfTest.java})이 그 표에 금지 재료가 없는지 잰다.
 *
 * <p>계획({@link #plan}·순수 검증 {@link #validate})은 지형을 읽고, 조성({@link #pavePad}·
 * {@link #paveStair})은 블록을 얹으며, 검수({@link #audit})는 선 것을 다시 잰다 —
 * ①패드 표면 평탄 ②전 열 접지(허공 0) ③계단 보행 연속(단차 ≤1). 산세 높이장은 안 건드린다.
 */
public final class TerraceForge {

    private TerraceForge() {
    }

    /**
     * 대계단 반폭 — <b>보행 7</b> + 측석 2 = 전폭 9.
     *
     * <p>★★사용자 실측 (2026-08-04): 「레퍼런스 사진으로 보니 <b>계단의 폭이 7칸(도보길만)</b>」.
     * 이 값은 슬라이스 8 <b>이전</b> 값과 정확히 같다 — 즉 슬라이스 8 의 「대계단 20」 척도
     * 교정(9 로 올려 전폭 21)이 <b>오류였고</b>, 그때 그 앵커를 따라 전 캠퍼스가 ~2.8배로
     * 커졌다. 계단부터 되돌린다 (정본: {@code docs/design/hwasan_target_diff.md} D-19).
     *
     * <p>★계율: 이 상수는 <b>목표 사진의 실측</b>이다. 「크게 하면 웅장하다」로 올리지 마라 —
     * 웅장함은 폭이 아니라 길이·오름·양옆의 리듬에서 온다 (레퍼런스가 그렇다).
     */
    public static final int STAIR_HALF = 3;

    /** 대계단 난간(측석) 오프셋 — 계단 중심 ±4 (보행 7 바로 밖) */
    public static final int RAIL_OFF = STAIR_HALF + 1;

    /** 패드 사이 최소 낙차 — 이보다 얕으면 단이 단으로 안 읽힌다 (계단 링크의 하한) */
    public static final int MIN_STAIR_DY = 3;

    /** 패드 사이 최대 낙차 — 대계단 하나의 상한. 더 크면 계단참 패드를 끼운다 (척추가 그렇다) */
    public static final int MAX_STAIR_DY = 26;

    /** 램프가 끝난 뒤 아랫패드까지 허용하는 평탄 보도 길이 */
    public static final int MAX_WALK = 12;

    /**
     * 패드 한 변 상한 — ★H-3 의 35 를 <b>폐지</b>하고 안전핀 128(build_radius 후보)로 올렸다.
     * 근거: 사용자 지시 (2026-08-02 · 슬라이스 5) — 「이미지를 보면 블록 하나하나가 보인다,
     * 그대로 크기를 정해서 설치하라」. 마스터플랜의 단은 산 전폭을 채우는 통단(帶)이다
     * (실측표 {@code docs/design/hwasan_block_measurements.md} §2·§5).
     */
    public static final int MAX_TIER_WIDTH = 128;

    /** 패드 위 머리 공간 — 이 높이까지 걷어 하늘을 연다 (수목·바위 돌출 제거) */
    private static final int HEADROOM = 8;

    /** 지형 판독 상위 백분위 — 쌓기 우선의 눈금 (상위 15%만 깎는다) */
    private static final int PERCENTILE = 85;

    /** 잠정 목표고와 실지형(p85)의 어긋남 경고 문턱 — 넘으면 계획이 소리 낸다 */
    public static final int TERRAIN_MISMATCH_WARN = 10;

    private static final long SALT_FACE = 0x5EA_57ACL;                  // 옹벽 결
    private static final long SALT_PAVE = 0x0BAD_5EEDL ^ 0x7E44ACE5L;   // 포장 결
    private static final long SALT_RIB = 0x0C0DE_0A9L;                  // 암반 늑재 구간 (슬라이스 9)

    // ═══════════════════════════════════════════════════════════════════
    // 명세 — 패드(폴리곤)와 계단 링크
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 패드 명세 — 마스터플랜 한 구역의 테라스 자리.
     *
     * @param zone  마스터플랜 구역 번호 (§2 표 — 15 는 결번 보존 · 101/102 는 내부 계단참)
     * @param name  구역 이름 (검수·로그가 부른다)
     * @param dx    주봉 상대 중심 x (골격 좌표계 — 남 = +z)
     * @param dz    주봉 상대 중심 z
     * @param width 동서 폭 (칸) — ≤ {@value #MAX_TIER_WIDTH} (H-3)
     * @param depth 남북 깊이 (칸) — ≤ {@value #MAX_TIER_WIDTH}
     * @param h     목표 포장면 높이 (baseY 위) — 골격 유도 잠정값 【제안】
     * @param expectedLift ★의도된 Δ (h − 실지형 p85 의 기대값 · 0 = 없음) — 경고 눈은 |Δ−기대| 로
     *                     잰다. 양수 = 석탑/성곽 옹벽(단 가장자리가 벼랑으로 내려가는 자리 —
     *                     마스터플랜의 「산 전면 성곽」이 근거 · 슬라이스 5.5 에서 다리 전용 가드를
     *                     통단 계약으로 확장), 음수 = 의도된 깎기(능선 어깨가 단을 뚫는 자리).
     *                     한도 ±40 — 그 밖은 계약이 아니라 배치 오류다 ({@link #resolvePads} 가 거절).
     */
    public record PadSpec(int zone, String name, int dx, int dz, int width, int depth, int h,
                          int expectedLift) {

        /** 의도된 Δ 없는 판 (대부분의 패드) */
        public PadSpec(int zone, String name, int dx, int dz, int width, int depth, int h) {
            this(zone, name, dx, dz, width, depth, h, 0);
        }
    }

    /**
     * 계단 링크 — 윗패드의 한 면에서 아랫패드로 내려가는 계단.
     *
     * @param upperZone 윗패드 구역 번호 (h 가 더 높아야 한다)
     * @param lowerZone 아랫패드 구역 번호
     * @param side      윗패드의 어느 면에서 나가는가 — 'S'·'N'·'E'·'W'
     * @param half      보행 반폭 — 대계단 {@value #STAIR_HALF}(보행 7) 또는 ★소계단 1(보행 3 —
     *                  슬라이스 9b 단구 분할: 같은 통단 안 칸 사이를 잇는다). ★2026-08-04
     *                  사용자 실측으로 대계단이 9→3 으로 줄면서 소계단도 2→1 로 비례 축소했다
     *                  (소계단이 대계단만 해지면 위계가 뒤집힌다)
     * @param off       면 위 교차점 오프셋 (중심 기준) — 월대·행각 등 구조물을 비켜 건넌다
     */
    public record StairLink(int upperZone, int lowerZone, char side, int half, int off) {

        /** 대계단 (척추) — 중심 교차 */
        public StairLink(int upperZone, int lowerZone, char side) {
            this(upperZone, lowerZone, side, STAIR_HALF, 0);
        }

        /** 소계단 (단구 사이 · 슬라이스 9b) — 중심 교차 */
        public StairLink(int upperZone, int lowerZone, char side, int half) {
            this(upperZone, lowerZone, side, half, 0);
        }
    }

    /**
     * 운무교 명세 — <b>본산과 곁봉을 잇는 현공교</b> (마스터플랜 ⑱ · 슬라이스 3).
     * 축 정렬 직선 다리: 석교 교대(양끝 2칸 — 실지형 접지) + 목교 상판(폭 5 · 걷는 폭 3 ·
     * 난간 울타리 · 등롱) + 긴 스팬은 중간 돌교각. <b>상판 아래는 의도된 허공</b>이다 —
     * 접지 눈은 등록된 다리의 교대·교각만 재고, 상판 밑 허공은 다리이기 때문에 허용한다
     * (침묵 예외가 아니라 명세에 등록된 구간만).
     *
     * @param name    다리 이름 (검수·로그가 부른다)
     * @param alongX  참이면 동서로 지난다 (varying = x · fixed = z), 거짓이면 남북
     * @param c       고정축 좌표 (주봉 상대)
     * @param a0,a1   상판 구간 (주봉 상대 · a0 ≤ a1) — 양끝 한 칸 밖은 패드 안이어야 한다
     * @param h       상판 높이 (baseY 위) — 양끝 패드의 h 와 같아야 한다 (수평 상판)
     */
    public record BridgeSpec(String name, boolean alongX, int c, int a0, int a1, int h) {
    }

    /**
     * 상판 스팬 상한 — 이보다 길면 다리가 아니라 만용이다 (스팬을 쪼개거나 자리를 옮겨라).
     * ★척도 되돌림(2026-08-04): 160→80 — 곁봉이 제자리로 돌아와 협곡이 좁아졌다
     * (현 스팬 61·42·58). 긴 스팬을 교각으로 받치는 다주 문법 자체는 남는다.
     */
    public static final int MAX_BRIDGE_SPAN = 80;

    /** 캠퍼스 명세 — 패드 목록 + 계단 링크 + 운무교. {@link #validate} 가 순수하게 전부 잰다. */
    public record Campus(List<PadSpec> pads, List<StairLink> links, List<BridgeSpec> bridges) {

        /** 다리 없는 판 (슬라이스 1~2 호환) */
        public Campus(List<PadSpec> pads, List<StairLink> links) {
            this(pads, links, List.of());
        }
    }

    /**
     * 화산 캠퍼스 기본값 【제안】 — 마스터플랜 20구역 중 슬라이스 1 몫:
     * <b>척추 공공 단 6</b> (1 산문 → 2 외원 → 6 종문 → 9 본전 → 12 장로회 → 13 정상) +
     * <b>좌우 로브 단 7</b> (3·4·5·7·8·14·17) + 척추 낙차를 죄려고 끼운
     * <b>내부 계단참 3</b> (101·102·103 — 마스터플랜 결번 아님, 이 기계의 살림).
     *
     * <p>높이는 <b>실기동 1차 p85 판독(2026-08-02 · 캠퍼스시험 지형 어긋남 경고 9건)으로
     * 재조정한 잠정값</b>이다 【제안】 — 초판(골격 보간 유도)이 지형보다 11~29 높아 옹벽이
     * 레퍼런스의 「석축 위 단」 감(4~12칸)을 넘었다. 이제 각 패드는 p85 ±2 를 딛고,
     * 창-안이던 척추 앵커 넷(6 종문 92 · 9 본전 116 · 12 장로회 128 · 13 정상 148)은 살렸다.
     * 그 대가로 세 링크의 높낮이가 뒤집혔다 — 종문>훈련장·훈련장>생활중·산문>창고
     * (마스터플랜은 평면도라 상하는 지형이 정한다). 실지형과의 어긋남은 {@link #plan} 이
     * 계속 재어 소리 낸다.
     *
     * <p>척추 하부(외원→종문)는 낙차 34 라 계단참 병(103)을 끼워 12+22 로 갈랐다 —
     * 22 구간은 레퍼런스 1호의 긴 천계단 문법이다 (계단참 둘을 더 끼울 자리가 없다:
     * 램프가 계단참 깊이를 다 먹는다).
     *
     * <p>16(측문)·10(정원)·11(망루)은 슬라이스 2(구역 배치기), 18·19·20(운무교·곁봉)은
     * 슬라이스 3 몫 — 여기 없다.
     */
    public static Campus hwasanCampus() {
        List<PadSpec> pads = List.of(
                // ═══ 통단(帶) 7대 — 실측표 §2·§5.
                //     ★★척도 되돌림 (사용자 확정 2026-08-04): 레퍼런스 계단 도보 폭이 **7** 임이
                //     사용자 실측으로 확정됐고, 그 값은 슬라이스 8 **이전** 우리 값과 같다 —
                //     즉 슬라이스 8 의 「대계단 20」 척도 교정(×2.2)이 통째로 오류였다.
                //     평면 치수·표고 사슬을 그때 값으로 되돌린다 (h 46/58/76/96/116/128/148).
                //     ★되돌리는 것은 **척도**뿐 — 슬라이스 9~16 의 문법(단구 분할·귀솟음·포치·
                //     다중 선반·풍화·faceRelief 이음매…)은 전부 남는다.
                // ★슬라이스 9b — 단구 표고 분할: 같은 통단 안 칸을 ±4 로 갈라 원경 옹벽
                //   스카이라인이 들쭉해진다. 척추 칸(1·2·6·101·9)은 앵커 그대로 — 로브만 ±4.
                //   계약 Δ 는 h 이동분만큼 동행 (p85 는 지형이라 불변).
                // ── B1 산문단 (산문 46 · 창고 42) ──
                new PadSpec(1, "산문", -2, 178, 60, 22, 46),
                new PadSpec(17, "물자 창고", 45, 178, 34, 22, 42, 19),     // 9b: 46→42 · Δ23→19
                // ── B2 외원단 (연무장하 62 · 외원 58 · 생활하 54 · 측문 54) ──
                new PadSpec(3, "연무장 하", -40, 148, 44, 32, 62, 23),     // 9b: 58→62 · Δ19→23 (성곽 서벽)
                new PadSpec(2, "외원 광장", -2, 148, 32, 32, 58),
                new PadSpec(5, "생활 하", 32, 148, 36, 32, 54),            // 9b: 58→54 · 창 안
                new PadSpec(16, "측문", 54, 148, 8, 16, 54, 27),           // 9b: 58→54 · Δ31→27 (동벽 단애)
                // ── B3 중단 (강당 80 · 종문 76 · 훈련장 72) ──
                new PadSpec(4, "강당·무기고", -38, 114, 40, 30, 80, 17),   // 9b: 76→80 · Δ13→17
                new PadSpec(6, "종문 중정", -2, 114, 32, 30, 76, -16),     // 능선 어깨 깎기
                new PadSpec(7, "훈련장 중", 30, 114, 32, 30, 72),          // 9b: 76→72 · 창 안
                // ── B4 상단 (연무장상 100 · 중정 96 · 생활중 92) ──
                new PadSpec(14, "연무장 상", -37, 82, 40, 28, 100),        // 9b: 96→100 · 창 안
                new PadSpec(101, "중정", -2, 82, 30, 28, 96, -14),         // 능선 어깨 깎기
                new PadSpec(8, "생활 중", 29, 82, 32, 28, 92),             // 9b: 96→92 · 창 안
                // ── B5 본전단 (정원 120 · 본전 116 · 망루 112) ──
                new PadSpec(10, "장문인 정원", -36, 50, 36, 32, 120, 22),  // 9b: 116→120 · Δ18→22
                new PadSpec(9, "본전", 1, 50, 38, 32, 116),
                new PadSpec(11, "망루", 34, 50, 28, 32, 112, 24),          // 9b: 116→112 · Δ28→24
                // ── B6 장로단 h128 ──
                new PadSpec(12, "장로회", -2, 18, 44, 24, 128),
                // ── B7 정상단 h148 ──
                new PadSpec(13, "정상 암자", -2, -10, 26, 18, 148),
                // ── 곁봉 (지형 앵커 — 되돌린 골격의 어깨) ──
                new PadSpec(19, "절벽 전망대", 88, 10, 14, 12, 128, 31),
                new PadSpec(20, "부속 암자", 62, -14, 18, 14, 148, 20),
                new PadSpec(105, "서교 착지", -88, 10, 12, 12, 128, 20));
        List<StairLink> links = List.of(
                // 척추 대계단 — 도보 7 (★사용자 실측 확정 2026-08-04)
                new StairLink(2, 1, 'S'),      // 낙차 12
                new StairLink(6, 2, 'S'),      // 낙차 18
                new StairLink(101, 6, 'S'),    // 낙차 20
                new StairLink(9, 101, 'S'),    // 낙차 20
                new StairLink(12, 9, 'S'),     // 낙차 12
                new StairLink(13, 12, 'S'),    // 낙차 20
                // ★9b 소계단 (전폭 5) — 갈린 단구 사이. Δ≤1 칸(5↔16)만 여장 개구가 잇는다.
                new StairLink(1, 17, 'E', 1),
                new StairLink(3, 2, 'E', 1),
                new StairLink(2, 5, 'E', 1),
                new StairLink(4, 6, 'E', 1),
                new StairLink(6, 7, 'E', 1),
                new StairLink(14, 101, 'E', 1),
                new StairLink(101, 8, 'E', 1),
                new StairLink(10, 9, 'E', 1, -12),   // off -12 — 본전 월대(남쪽)를 비켜 건넌다
                new StairLink(9, 11, 'E', 1, -12));
        List<BridgeSpec> bridges = List.of(
                // ★척도 되돌림 — 곁봉이 제자리로 돌아와 스팬이 옛 값(61·42·58)으로
                new BridgeSpec("운무교 동일", true, 10, 20, 80, 128),    // 장로회 ↔ 전망대(19) · 스팬 61
                new BridgeSpec("운무교 동이", true, -12, 11, 52, 148),   // 정상단 ↔ 부속 암자(20) · 스팬 42
                new BridgeSpec("운무교 서", true, 10, -82, -25, 128));   // 장로회 ↔ 서교 착지(105) · 스팬 58
        return new Campus(pads, links, bridges);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 계획 — 명세를 실좌표에 앉히고 (순수) · 지형을 읽는다 (월드)
    // ═══════════════════════════════════════════════════════════════════

    /** 앉힌 패드 — 실좌표. 북 = −z. */
    public record Pad(PadSpec spec, int y, int x0, int x1, int zN, int zS) {

        int cx() {
            return x0 + spec.width() / 2;
        }

        int cz() {
            return zN + spec.depth() / 2;
        }

        boolean contains(int x, int z) {
            return x >= x0 && x <= x1 && z >= zN && z <= zS;
        }
    }

    /**
     * 앉힌 대계단 — 윗패드 면에서 한 칸 밖이 첫 디딤({@code start}), {@code dir} 로 내려간다.
     * 디딤 {@code treads} = 낙차−1, 그 뒤 보도 {@code walk} 칸이 아랫패드에 닿는다 (붙어 있으면 0).
     * 몸체 전 열은 실지형/아랫포장까지 채워 <b>접지</b>한다 — 뜬 계단은 없다.
     */
    public record StairLane(StairLink link, int startX, int startZ, int dirX, int dirZ,
                            int topY, int lowY, int treads, int walk) {

        public int length() {
            return treads + walk;
        }

        /** 보행 반폭 — 링크가 정한다 (대계단 {@value #STAIR_HALF} · 소계단 1) */
        public int half() {
            return link.half();
        }

        /** 측석(난간) 오프셋 — 보행 반폭 + 1 */
        public int rail() {
            return link.half() + 1;
        }

        /** 이 계단 몸체(폭 {@code 2·rail+1})가 그 열을 덮는가 — 여장·평탄 검수가 비켜 갈 자리 */
        boolean covers(int x, int z) {
            for (int t = 0; t <= length(); t++) {
                int cx = startX + dirX * (t - 1);
                int cz = startZ + dirZ * (t - 1);
                int off = dirZ != 0 ? x - cx : z - cz;
                boolean onCell = dirZ != 0 ? z == cz : x == cx;
                if (onCell && Math.abs(off) <= rail()) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 앉힌 운무교 — 실좌표. {@code endA} 는 a0 쪽 끝 패드, {@code endB} 는 a1 쪽 (검수·개구가 읽는다).
     */
    public record Bridge(BridgeSpec spec, boolean alongX, int c, int a0, int a1, int y,
                         Pad endA, Pad endB) {

        public int span() {
            return a1 - a0 + 1;
        }

        /** 상판·난간 폭(±2)이 그 열을 덮는가 — 여장 개구·검수가 읽는다 (패드 안 두 칸 이음 포함) */
        public boolean covers(int x, int z) {
            int v = alongX ? x : z;
            int w = alongX ? z : x;
            return v >= a0 - 2 && v <= a1 + 2 && Math.abs(w - c) <= 2;
        }

        /** 교각 자리 (a0 기준 상대 오프셋) — 조성과 검수가 같은 식을 쓴다 */
        public java.util.List<Integer> pierOffsets() {
            int n = Math.max(0, (span() - 20) / 24);
            java.util.List<Integer> out = new ArrayList<>(n);
            for (int k = 1; k <= n; k++) {
                out.add(span() * k / (n + 1));
            }
            return out;
        }
    }

    /**
     * ★접근 시퀀스 명세 (슬라이스 9b · 코덱스 개선 3 — 「웅장함은 도착하는 과정에서 나온다」):
     * 산문단 남단에서 남쪽 절벽 아래까지, 지형을 따라 오르내리는 20폭 대계단 노선.
     * {@code ys[i]} = (z0+i) 행의 보행면 y — 계획이 조성 전 지형을 읽어 정하고 (한 칸 물매 ·
     * 13칸마다 참 · 소문 마당), 조성·검수가 같은 표를 읽는다.
     *
     * @param x  축선 x (산문 중심열)
     * @param z0 첫 행 (산문단 남단 + 1)
     * @param ys 행별 보행면 y
     */
    public record Approach(int x, int z0, int[] hs) {

        public int length() {
            return hs.length;
        }

        /** 그 행에 <b>블록이 놓이는</b> y — 짝수 반단위는 풀블록, 홀수는 하단 반블록 */
        public static int blockY(int h) {
            return (h & 1) == 0 ? h / 2 - 1 : (h - 1) / 2;
        }

        /** 그 행의 상면이 <b>반 칸</b>인가 (하단 반블록) */
        public static boolean isSlab(int h) {
            return (h & 1) == 1;
        }

        /** {@code i} 번째 행에 블록이 놓이는 y (인덱스로 묻는다) */
        public int blockYAt(int i) {
            return blockY(hs[i]);
        }

        /** {@code i} 번째 행의 상면이 반 칸인가 (인덱스로 묻는다) */
        public boolean isSlabAt(int i) {
            return isSlab(hs[i]);
        }
    }

    /** 계획 — 앉힌 패드·계단·다리·접근로와 지형 어긋남 메모. */
    public record Plan(String placeId, List<Pad> pads, List<StairLane> lanes,
                       List<Bridge> bridges, Approach approach, List<String> terrainNotes) {
    }

    /** 기본 캠퍼스로 계획한다 — 지형(p85)을 읽어 어긋남을 메모에 남긴다. */
    public static Plan plan(World world, RangeSpec spec) {
        return plan(world, spec, hwasanCampus());
    }

    /** 명세를 밖에서 주는 판 — 시험·다른 산이 제 표를 들고 온다. */
    public static Plan plan(World world, RangeSpec spec, Campus campus) {
        List<Pad> pads = resolvePads(campus, spec.peakX(), spec.peakZ(), spec.baseY());
        List<StairLane> lanes = resolveLanes(campus, pads);
        List<Bridge> bridges = resolveBridges(campus, pads, lanes, spec.peakX(), spec.peakZ(), spec.baseY());
        List<String> notes = new ArrayList<>();
        for (Pad p : pads) {
            int p85 = percentileGround(world, p.x0(), p.x1(), p.zN(), p.zS(), PERCENTILE);
            int delta = p.y() - p85;
            int off = delta - p.spec().expectedLift();   // 의도된 Δ 는 계약 — 계약과의 어긋남만 잰다
            if (Math.abs(off) > TERRAIN_MISMATCH_WARN) {
                notes.add(p.spec().zone() + " " + p.spec().name() + ": 목표 y" + p.y()
                        + " vs 지형 p85 y" + p85 + " (Δ" + delta
                        + (p.spec().expectedLift() != 0 ? " · 계약 Δ" + p.spec().expectedLift() : "")
                        + ") — 잠정 높이를 빨간펜하라");
            }
        }
        return new Plan(spec.placeId(), pads, lanes, bridges,
                approachOf(world, pads), List.copyOf(notes));
    }

    /**
     * <b>초목 스위치</b> — 꺼져 있으면 나무·관목·풀·꽃·매화·덩굴을 심지 않는다 (기본 <b>꺼짐</b>).
     *
     * <p>★사용자 지시 (2026-08-05): 「<b>일단 나무 다 치우고</b> 만들어봅시다」 — 건축 자체를
     * 보기 위해서다. 되살릴 수 있어야 하므로 지우지 않고 <b>스위치</b>로 둔다: 이 한 줄을
     * {@code true} 로 바꾸면 전부 되살아난다 (산군 식생·캠퍼스 조경·접근로 소나무가 모두
     * 이 값을 읽는다).
     *
     * <p>★<b>남기는 것</b>: 이끼(MOSS_BLOCK·MOSSY_*)·지의(GLOW_LICHEN)는 「나무」가 아니라
     * <b>바위의 결</b>이다 — 암벽 표면의 일부이므로 스위치와 무관하게 남는다. 화단·밭의
     * 흙바닥(COARSE_DIRT·FARMLAND)도 지면이라 남고, 그 위에 서는 <b>초목만</b> 빠진다.
     */
    //   ★2026-08-06 <b>켠다</b> — 사용자 승인: 「산문 구조는 승인·동결. 다음은 외원/입구
    //   광장과 산문 주변 절벽·정원·난간을 함께 조성하는 것」. 구조를 먼저 세우고 장식을
    //   나중에 얹는다는 순서를 지켰고, 이제 그 「나중」이다.
    public static boolean GREEN = true;

    /**
     * <b>소품 스위치</b> — 꺼져 있으면 깃대·석등·비석을 세우지 않는다 (기본 <b>꺼짐</b>).
     *
     * <p>★사용자 지시 (2026-08-05): 「계단 주위 깃대 이런 거 <b>다 나중에</b> 세우자.
     * <b>제거 후 전체 건축부터</b> 진행」 — 전체 구조를 먼저 세우고 장식은 그 위에 얹는다.
     * {@link #GREEN} 과 같은 문법이라 이 한 줄을 {@code true} 로 바꾸면 되살아난다.
     *
     * <p>★<b>가르는 자 — 「구조인가 장식인가」</b>: 계단·난간벽·소문(문루)은 <b>구조</b>라
     * 스위치와 무관하게 남는다 (걷는 길이고 통과하는 문이다). 깃대·석등·비석은 그 위에
     * 얹히는 <b>표지</b>이므로 이 스위치를 탄다.
     */
    //   ★2026-08-06 <b>켠다</b> — 위와 같은 근거. 난간·등롱이 진입축을 강조한다는 판정.
    public static boolean PROPS = true;

    // ═══════════════════════════════════════════════════════════════════
    // 계단 문법 — 반 칸 하강 (★사용자 확정 2026-08-05)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 한 단의 디딤 깊이 — <b>2칸</b> (목표 사진 1호 실측: 디딤마다 블록 면이 두 줄 보인다).
     *
     * <p>★사용자 지시: 「너무 한 칸씩 내리막을 설치하는 게 아닌 <b>반블록을 사용해 널널히
     * 걷도록</b>」. 그래서 물매는 <b>0.5 : 2 = 1:4</b> 다 — 마크에서 반 칸은 점프 없이 걸어
     * 오르내리므로, 이 물매가 「널널히 걷는」 계단의 실체다.
     */
    public static final int STAIR_TREAD = 2;

    /** 한 주기의 단 수 — <b>아홉</b> (사용자 확정: 「위에서부터 아홉 칸 내려갔다가」) */
    public static final int STAIR_RUN = 9;

    /** 참 — <b>평지 4칸</b> (사용자 확정: 「평지 블럭이 4칸이고」) */
    public static final int STAIR_LANDING = 4;

    /**
     * 소문이 서는 참은 <b>넓다</b> (8칸) — 문루 지붕이 z±2 를 덮으므로 4칸 참에는 못 앉는다.
     * 마당이 문을 감싸야 「소문 마당」이 성립한다.
     */
    public static final int GATE_LANDING = 8;

    /** 주기 수 · 소문이 앉는 주기 (0부터) */
    public static final int APPROACH_CYCLES = 4;
    public static final int GATE_CYCLE = 2;

    /**
     * 접근로 길이 — <b>주기 구조에서 유도한다</b> (임의 상수가 아니다).
     * 주기마다 디딤 {@code 9×2=18} 행 + 참({@code 4}, 소문 주기만 {@code 8}).
     *
     * <p>★한 주기의 하강 = 9 × 반 칸 = <b>4.5 블록</b> · 네 주기 = <b>18 블록</b>.
     */
    public static final int APPROACH_LEN =
            APPROACH_CYCLES * (STAIR_RUN * STAIR_TREAD)
                    + (APPROACH_CYCLES - 1) * STAIR_LANDING + GATE_LANDING;

    /**
     * 접근로 식생 회랑 반폭 — <b>계단 중심 ±10 은 나무가 서지 않는다</b> (D-16).
     *
     * <p>★2026-08-04 사용자 지적: 목표 사진은 계단이 화면 하단을 차지하며 시선을 문루로
     * 이끄는데, 우리 것은 <b>산의 야생 숲이 계단 위까지 자라</b> 하단이 초록 덩어리였다.
     * 진범은 산군 식생의 제외 목록에 <b>접근로가 통째로 빠져 있던 것</b>이다 (패드·계단·다리만
     * 있었다 — 접근로는 그 셋 어디에도 안 든다).
     *
     * <p>값의 근거: 보행 7(±3) + 난간(±4) + 여유 — <b>비우되 넓게 밀지는 않는다.</b>
     * 목표 사진에도 계단 곁에는 매화·소나무가 있다 — 없어야 하는 것은 <b>계단을 덮는</b>
     * 나무이지 곁에 선 나무가 아니다.
     */
    public static final int APPROACH_CLEAR = 10;

    /**
     * <b>축선 시야 회랑</b> 반폭 — 계단 아래 축선에 선 눈이 문루를 볼 때 <b>그 사이에
     * 두꺼운 소품이 없어야 하는</b> 폭이다 (2026-08-05 · 사용자 지적 → 계약 교정).
     *
     * <p>옛 비석 자리 ±7 은 <b>통행</b>은 안 막았다 — 그래서 통로 겹침 눈도 조용했다. 그러나
     * 카메라(사람)가 축선 위 계단 아래에 서면 <b>가까운 비석이 멀리 있는 문루를 가린다</b>:
     * 거리 24칸의 5칸 기둥이 거리 64칸의 21칸 문루보다 화면에서 크다. <b>통행의 폭과 시야의
     * 폭은 다르다</b>.
     *
     * <p>★★계약 교정 — <b>가리는 정도는 높이가 아니라 두께가 정한다</b>:
     * <ul>
     *   <li><b>두꺼운 소품 (폭 ≥2)</b> — 비석·정자·큰 기둥. 이 회랑 <b>밖</b>에 선다.
     *       비석은 폭 2~3의 판이라 문루를 통째로 가렸다.</li>
     *   <li><b>가는 소품 (폭 1)</b> — 깃대·석등 기둥. <b>난간 위</b>(±{@link #RAIL_OFF})까지
     *       허용한다. 1칸 울타리는 원근으로도 가늘고, 목표 사진 1호에서도 깃대가 계단 양옆에
     *       촘촘히 섰는데 문루가 잘 보인다.</li>
     * </ul>
     * 처음엔 높이로 가르려 했으나(깃대 7 > 비석 5) 그것이 틀렸다 — 목표가 반례다.
     */
    public static final int AXIS_CLEAR = 13;

    /**
     * 접근로 부속의 자리 — <b>전부 {@link #APPROACH_LEN} 에서 유도한다</b>.
     *
     * <p>★2026-08-04 실증: 척도를 되돌리며 길이를 176→88 로 줄였는데 소문 몸체가 옛 인덱스
     * (102)를 그대로 물고 있어 <b>조성 중 ArrayIndexOutOfBounds</b> 로 터졌다 (순수 검증은
     * 통과했다 — 인덱스가 길이 안인지 아무도 안 쟀기 때문이다). 비석 하나는 i150 이라 범위
     * 밖으로 나가 <b>조용히 사라지고</b> 있었다. 그래서 자리를 전부 비례식으로 못 박고,
     * {@link #approachFixtureIndices()} 로 내보내 눈이 범위를 재게 한다.
     */
    /** 주기 {@code c} 의 첫 행 — 소문 주기의 참이 넓으므로 누적으로 센다 */
    public static int cycleStart(int c) {
        int i = 0;
        for (int k = 0; k < c; k++) {
            i += STAIR_RUN * STAIR_TREAD + (k == GATE_CYCLE ? GATE_LANDING : STAIR_LANDING);
        }
        return i;
    }

    // ══════════════════════════════════════════════════════════════════
    // ★★접근로 폭 전이 — 「입구는 넓고, 문 안쪽에서 기존 축으로 수렴한다」
    //   (사용자 확정 2026-08-06)
    //
    //   외부 접근 9 → 산문 전면 참 11 → 산문 통로 7 → 기존 주축 7
    //
    //   ★폭을 갑자기 줄이지 않고 <b>참에서</b> 처리한다. 참은 걸음이 멈추는 자리라
    //   폭이 바뀌어도 발이 안 걸린다. 전이 참은 주기 0 의 참(i 18~21)이다 —
    //   산문 바로 앞이다 (i=0 이 산문, i 가 클수록 아래).
    //
    //   ★계단 폭을 한 번 크게 잘못 잡은 적이 있다 (20 으로 읽고 전 캠퍼스를 ×2.2 로
    //   키웠다가 되돌렸다). 그래서 <b>기존 7 축은 안 건드리고</b> 아래쪽만 넓힌다.
    // ══════════════════════════════════════════════════════════════════

    /** 전이 참의 첫 행 — 주기 0 의 디딤이 끝나는 자리 */
    public static final int WIDEN_FROM = STAIR_RUN * STAIR_TREAD;          // 18

    /** 전이 참의 끝(배타) — 여기부터 아래는 넓은 계단 */
    public static final int WIDEN_TO = WIDEN_FROM + STAIR_LANDING;         // 22

    /**
     * 그 행의 보행 반폭 — 폭 = 2·half + 1.
     *
     * <p>i 0~17 : 3 (폭 7 — 산문 통로·기존 축과 같다)<br>
     * i 18~21 : 5 (폭 11 — 전이 참)<br>
     * i 22~   : 4 (폭 9 — 외부 접근 계단)
     */
    public static int approachHalf(int i) {
        if (i < WIDEN_FROM) {
            return STAIR_HALF;                 // 7 — 문 안쪽 축과 수렴
        }
        if (i < WIDEN_TO) {
            return STAIR_HALF + 2;             // 11 — 전이 참 (폭이 바뀌는 자리)
        }
        return STAIR_HALF + 1;                 // 9 — 입구는 넓다
    }

    /** 그 행이 참인가 — 디딤 18행이 끝난 뒤부터 주기 끝까지 */
    public static boolean isLanding(int i) {
        for (int c = 0; c < APPROACH_CYCLES; c++) {
            int s = cycleStart(c);
            int land = s + STAIR_RUN * STAIR_TREAD;
            int end = land + (c == GATE_CYCLE ? GATE_LANDING : STAIR_LANDING);
            if (i >= land && i < end) {
                return true;
            }
        }
        return false;
    }

    /** 소문 — 넓은 참(8칸)의 한가운데. 지붕이 z±2 를 덮어도 마당 안에 든다 */
    public static final int GATE_I =
            cycleStart(GATE_CYCLE) + STAIR_RUN * STAIR_TREAD + GATE_LANDING / 2 - 1;

    /** 소문 마당 — 그 주기의 참 전체 */
    public static final int GATE_YARD_N = cycleStart(GATE_CYCLE) + STAIR_RUN * STAIR_TREAD;
    public static final int GATE_YARD_S = GATE_YARD_N + GATE_LANDING - 1;

    /** 비석 두 쌍 — 아래·위 참의 첫 행 곁 */
    public static final int STELE_A = cycleStart(1) + STAIR_RUN * STAIR_TREAD;
    public static final int STELE_B = cycleStart(3) + STAIR_RUN * STAIR_TREAD;

    /** 소나무 주기 — 회랑 밖에 좌우 번갈아 (★{@link #GREEN} 이 꺼져 있으면 안 선다) */
    public static final int PINE_EVERY = Math.max(8, APPROACH_LEN / 4);

    // ══════════════════════════════════════════════════════════════════
    // ★★문전(門前) 비움 — <b>산문의 첫인상은 중앙축이 한 번에 뚫려 보여야 한다</b>
    //   (사용자 확정 2026-08-06 — 인수인계 §4-표준 ㉦ 「미해결·결정 대기」의 답)
    //
    //   ★진단이 바뀌었다: 문제는 <b>보행 폭이 아니라 문간의 시각 폭</b>이다. 통로를 7 로
    //   넓혔는데도 정면에서는 「깃대 | 적주 | 문살 | 통로 | 적주 | 등롱」으로 읽혀 다시
    //   5칸으로 압축돼 보인다 — <b>독립된 수직 구조물이 적주보다 앞에 서서</b> 문루의
    //   위계를 나눠 먹기 때문이다. 넓히는 것으로는 못 고친다. 비워야 고쳐진다.
    //
    //   ★역할을 가른다: <b>깃대는 계단의 리듬</b>을 맡고, <b>문 앞 조명은 산문 건축에
    //   붙인다</b>. 그래서 문전 구간에는 독립 수직물이 서지 않고, 낮은 석등 한 쌍만 난간에
    //   붙어 선다. 깃대의 첫 자리는 11칸 전이 참의 외곽 모서리(±8)로 내려간다.
    //
    //   ★비석(±AXIS_CLEAR)의 계약과 다른 자다: 저것은 <b>두께</b>가 시야를 가리는 문제였고
    //   (「가리는 정도는 높이가 아니라 두께가 정한다」 · 2026-08-05), 이것은 <b>문간에서만</b>
    //   가는 소품조차 위계를 나눠 먹는 문제다. 그래서 구간이 붙는다 — 문 앞에서는 가늘어도
    //   안 되고, 계단에서는 여전히 난간 위에 선다.
    // ══════════════════════════════════════════════════════════════════

    /** 문전 구간의 끝(배타) — i 0~17. 문 앞 폭 7 구간과 <b>같은 경계</b>다 */
    public static final int FORECOURT_TO = WIDEN_FROM;                 // 18

    /** 문전 구간 소품의 최대 높이 (선 자리 위로) — 낮은 석등 3칸 */
    public static final int FORECOURT_MAX_H = 3;

    /**
     * 문간 <b>시각</b> 여유 반폭 — 이 안에는 높은 소품이 서지 않는다.
     * ★보행 반폭(3)과 다른 자다: 통로가 7칸이라는 뜻이 아니라, 문간 앞에서 높은 장식물이
     * 차지하지 않아야 할 <b>시각적</b> 여유다 (사용자 규정).
     */
    public static final int FORECOURT_CLEAR = 5;

    /** 문 앞 낮은 석등이 서는 행 — <b>한 쌍만</b> (좌우 대칭 · 난간 부착) */
    public static final int FORECOURT_LANTERN_I = 2;

    /**
     * 전이 참 깃대의 축선 오프셋 — 11칸 참의 <b>외곽 모서리</b>. 그 행의 난간(±6) 밖이라
     * 보행에도, 문간 시야에도 안 든다.
     */
    public static final int LANDING_BANNER_OFF = 8;

    /**
     * ★D-21 깃대 주기 — 목표 1호 실측 <b>세로 간격 8~10</b>. 좌우 <b>쌍</b>으로 선다
     * (목표 사진에도 계단 양옆에 있다). 소나무처럼 번갈아 두면 열이 끊겨 시선을 못 이끈다.
     */
    public static final int BANNER_EVERY = 9;

    /**
     * 깃대 첫 자리 — <b>전이 참의 첫 행</b> (2026-08-06 이전: 6, 즉 문 바로 앞이었다).
     * 문전 구간을 통째로 비우므로 열은 여기서 시작한다. 주기(9)는 그대로라 계단의 리듬은
     * 안 흔들린다 — 자리만 아래로 옮겼다.
     */
    public static final int BANNER_FROM = FORECOURT_TO;

    /** ★D-22 석등 주기 — 목표 1호 실측 <b>간격 10~12</b>. 좌우 <b>번갈아</b> 선다 */
    public static final int LANTERN_EVERY = 11;

    /** 석등 첫 자리 — 깃대 행과 겹치지 않게 어긋난 위상에서 시작한다 */
    public static final int LANTERN_FROM = 2;

    /**
     * 깃대 조립 — <b>눈이 이 표로 잰다</b> (조성과 한 식. 두 번 적으면 어긋난다).
     * 기둥(가는 울타리) → 마디(배너 매다는 자리) → 가로대(울타리 팔)가 <b>아래에서 위로</b>.
     * ★배너는 마디에 걸려 <b>아래로 늘어지므로</b> 가로대보다 낮다 — 목표 1호의 형태다.
     */
    public static final int FLAG_POLE_TOP = 5;       // 기둥 꼭대기 (상대 높이)
    public static final int FLAG_BANNER_Y = 6;       // 마디·배너
    public static final int FLAG_CROSS_Y = 7;        // 가로대 — 배너보다 위

    /** 깃대 부품 — {기둥, 가로대, 배너}. 눈이 굵기·색을 이 표로 잰다 */
    public static Material[] flagpoleParts() {
        return new Material[]{Material.DARK_OAK_FENCE, Material.DARK_OAK_FENCE,
                Material.BLACK_WALL_BANNER};
    }

    /**
     * 배너 문양 — <b>눈이 이 표로 잰다</b> (조성과 한 식).
     *
     * <p>★계율 (2026-08-05): <b>「튀지 않게」와 「안 보이게」는 다르다.</b> 채도를 낮추는 것이
     * 옳을 때도 <b>형태가 사라지면 그 요소는 없는 것과 같다</b> — 단청 청록은 없애는 게 답이었지만
     * 깃대 배너는 <b>보여야 하는 표지</b>다. 무지 검정 배너는 어두운 기둥과 붙어 그림자로 읽혔고
     * 원경에서 아예 사라졌다.
     *
     * <p>★바탕을 검정으로 둔 근거 — <b>문양까지 넣은 합성 평균</b>을 목표와 맞췄다:
     * 목표 배너(문양 포함) RGB(62,61,62) V24% · 밝은 문양이 면적의 <b>14%</b>.
     * 마크 {@code CIRCLE} 패턴(면적 ≈22%) 합성 예측은 BLACK+흰원 RGB(77,79,82) V32%
     * <b>거리 30.8</b> · GRAY+흰원 94.4 · BLUE+흰원 140.6 — 검정 바탕이 여전히 최근접이다.
     * 목표 문양은 <b>가운데 원 하나</b>이고 (위아래 띠는 없다 — 위쪽 흰 것은 가로대 장식이다),
     * 그래서 패턴도 하나다.
     */
    public static DyeColor bannerBase() {
        return DyeColor.BLACK;
    }

    /**
     * 배너 문양의 <b>색</b> — 가운데 흰 원 하나 (목표 1호 실측).
     *
     * <p>★문양 <b>종류</b>({@link PatternType})와 <b>색</b>을 갈라 둔 까닭: {@code PatternType}
     * 은 서버 레지스트리를 물어야 풀리는 값이라 <b>월드 없는 눈에서는 못 읽는다</b>
     * (정적 초기화가 {@code No RegistryAccess implementation found} 로 터진다).
     * 색·개수는 평범한 enum 이라 눈이 읽을 수 있으므로, <b>계약을 그쪽에 둔다</b> —
     * 눈이 재는 것은 「문양이 있는가 · 밝은가」이고 그 둘은 색으로 판정된다.
     */
    public static java.util.List<DyeColor> bannerPatternColors() {
        return java.util.List.of(DyeColor.WHITE);
    }

    /** 배너 문양 — 가운데 흰 원 하나. 조성 때만 부른다 (레지스트리가 필요하다) */
    public static java.util.List<Pattern> bannerPatterns() {
        java.util.List<Pattern> out = new ArrayList<>();
        for (DyeColor c : bannerPatternColors()) {
            out.add(new Pattern(c, PatternType.CIRCLE));
        }
        return out;
    }

    /** 그 행에 깃대가 서는가 (좌우 쌍) */
    public static boolean isFlagpoleRow(int i) {
        return i >= BANNER_FROM && (i - BANNER_FROM) % BANNER_EVERY == 0;
    }

    /**
     * 그 행 그 쪽에 석등이 서는가 — <b>좌우 번갈아</b>. 같은 행에 깃대가 서면 양보한다
     * (한 자리에 둘을 세우면 뒤엣것이 앞엣것을 덮어 <b>조용히 하나가 사라진다</b>).
     */
    public static boolean isLanternRow(int i, boolean left) {
        if (i < FORECOURT_TO) {
            return false;            // ★문전 구간 — 높은 독립 등롱은 안 선다 (낮은 한 쌍만)
        }
        if (isFlagpoleRow(i) || i < LANTERN_FROM) {
            return false;
        }
        int k = i - LANTERN_FROM;
        return k % LANTERN_EVERY == 0 && ((k / LANTERN_EVERY) % 2 == 0) == left;
    }

    /**
     * 그 행에 <b>문 앞 낮은 석등</b>이 서는가 — 문전 구간에 <b>한 쌍만</b>, 좌우 대칭.
     * 높은 독립 등롱({@code stoneLantern})의 자리를 대신한다.
     */
    public static boolean isForecourtLanternRow(int i) {
        return i == FORECOURT_LANTERN_I && i < FORECOURT_TO;
    }

    /** 높은 등롱 높이 — 기둥 2 + 발광체 1 + 갓 1 */
    public static final int LANTERN_H = 4;

    /**
     * 그 행 그 쪽에 서는 소품의 <b>높이</b> (선 자리 위로 · 0 이면 난간 갓뿐).
     * <b>눈이 이 표로 잰다</b> — 조성과 한 식이라 어긋날 수 없다.
     */
    public static int propHeight(int i, boolean left) {
        if (!PROPS) {
            return 0;
        }
        if (isFlagpoleRow(i)) {
            return FLAG_CROSS_Y;
        }
        if (isLanternRow(i, left)) {
            return LANTERN_H;
        }
        if (isForecourtLanternRow(i)) {
            return FORECOURT_MAX_H;
        }
        return 0;
    }

    /**
     * 그 행 소품의 <b>축선 오프셋</b> — 보통은 난간 캡 위(그 행의 보행 폭 밖)지만,
     * 전이 참의 깃대만 난간 밖 ±{@link #LANDING_BANNER_OFF} 에 선다.
     */
    public static int propOff(int i) {
        if (isFlagpoleRow(i) && i < WIDEN_TO) {
            return LANDING_BANNER_OFF;
        }
        return approachHalf(i) + 1;
    }

    // ══════════════════════════════════════════════════════════════════
    // ★★★facade_projection_clearance — <b>산문 정면 투영 비움 상자</b>
    //   (사용자 승격 2026-08-06: 「화산파 전체 생성기의 공통 규칙으로 승격할 만하다」)
    //
    //   ★계율: <b>가리는 것은 재료를 안 가린다.</b>
    //   옛 이름은 tree_clearance 였고 그 이름대로 <b>나무만</b> 보고 있었다. 실측이
    //   보여 준 진범은 바위였고, 바위를 걷어내자 그 자리에 조경이 도로 심겼다.
    //   그래서 계약의 이름과 범위를 함께 넓힌다:
    //
    //     금지 — 지형(주상절리·산몸) · 산군 식생 · 캠퍼스 조경 · 높은 소품 · 깃대
    //     허용 — 낮은 석등(≤{@link #FORECOURT_MAX_H}) · 난간 · 건축에 붙는 등롱
    //
    //   ★이 상자는 <b>상위 계약</b>이다 — 02 외원을 비롯한 어느 구역도 침범할 수 없다.
    //   외원은 빈 문전을 <b>채우는</b> 공간이 아니라 그 비어 있는 중앙축의 <b>양옆</b>을
    //   구성하는 공간이다 (사용자 확정).
    //
    //   ★숫자는 <b>여기 한 곳에만</b> 산다. 생성기·조경·눈이 전부 이 이름을 부른다 —
    //   같은 값을 두 번 적으면 언젠가 갈라진다 (「신고표가 실물보다 넓으면 눈이 헛것을
    //   지킨다」의 같은 병).
    //   정본 문서: config/blueprints/hwasan_gate.yml 의 facade_projection_clearance 절
    //   (아래 세 값과 <b>같은지 눈이 대조한다</b> — 문서가 조용히 늙지 않게).
    // ══════════════════════════════════════════════════════════════════

    /** 정면 투영 반폭 — 이 안에는 <b>서는 것</b>이 없다 (수관이 처마를 감싸는 것은 무관) */
    public static final int FACADE_CLEAR_HALF = 14;

    /**
     * 정면 투영 깊이 — 문전 구간과 <b>같은 경계</b>로 잡는다.
     * ★사용자 규정은 10 이었으나 <b>실제로 가리던 줄기가 i15</b> 라 10 으로는 눈이 그것을
     * 못 잡는다 (첫 소나무 자리 = i15). 규정을 <b>바닥값</b>으로 읽고 문전 구간(18)까지
     * 올렸다 — 결정을 눈이 실제로 지키게 하려면 이 깊이여야 한다.
     */
    public static final int FACADE_CLEAR_DEPTH = FORECOURT_TO;

    /**
     * 그 상자 <b>밖에 세울 때의 축선 오프셋</b> — 상자에 닿지 않게 두 칸 물린다.
     * ★±16 을 여기저기 적지 않는다: 접근로 소나무도, 캠퍼스 조경 소나무도 이 이름을 부른다.
     * 상자(±14)와 자리(±16)가 어긋나면 소품이 <b>제 손에 조용히 지워진다</b> — 눈이 그것도 잰다.
     */
    public static final int FACADE_STANDOFF = FACADE_CLEAR_HALF + 2;

    /**
     * 접근로 소나무의 축선 오프셋 — 기본은 회랑(±{@link #APPROACH_CLEAR}) 바로 밖이지만,
     * <b>문전 구간에서는 정면 투영 밖</b>({@link #FACADE_STANDOFF})으로 더 민다.
     *
     * <p>★2026-08-06 사용자 지적: 「나무가 없다」가 아니라 <b>「나무가 건축을 가리지
     * 않는다」</b>가 계약인데, 기준 카메라(산문 정면)에서 오른쪽 소나무의 <b>줄기</b>가
     * 산문 정면과 처마를 가로질렀다. 회랑 ±12 는 <b>계단을 덮지 않는</b> 자였지 <b>문루를
     * 가리지 않는</b> 자가 아니었다 — 비석 때와 같은 종류의 어긋남이다(통행 ≠ 시야).
     * 수관이 처마를 감싸는 것은 좋다. 막아야 하는 것은 <b>줄기가 문루 앞에 수직으로 겹치는
     * 것</b>이다.
     */
    public static int pineOff(int i) {
        return i < FACADE_CLEAR_DEPTH ? FACADE_STANDOFF : APPROACH_CLEAR + 2;
    }

    /**
     * 그 행에서 <b>보행면 위로 비우는 반폭</b>. <b>눈이 이 표로 잰다</b> (조성과 한 식).
     *
     * <p>★2026-08-06 사용자 확정 — 「정면 투영을 통째로 깎는다」. 실측이 진범을 바꿨다:
     * 문 앞을 가로지르던 회백색 기둥은 우리 소나무가 아니라 <b>축선 +5 · i 4~9 에 선
     * 주상절리 돌기둥</b>이었다 (포장 y−15 → 꼭대기 y+8 · 산문 지붕 마루 y+2 보다 6칸 높다).
     * 그 꼭대기의 산군 소나무가 통로 위까지 수관을 드리웠다.
     *
     * <p>★<b>까닭</b>: 산세·산군이 먼저 서고 캠퍼스가 뒤에 깎는데, {@code clearAbove} 는
     * <b>제 보행 폭만</b> 비운다. 난간 한 칸 밖의 20칸 바위는 아무도 건드리지 않았다.
     * 「나무가 건축을 가리지 않는다」는 계약이 <b>나무만</b> 보고 있었던 것이다 —
     * 가리는 것은 재료를 안 가린다.
     *
     * <p>문전 구간에서만 정면 투영(±{@link #FACADE_CLEAR_HALF})을 표고까지 비우고,
     * 그 밖에서는 종전대로 보행면+난간만 비운다 (계단 곁의 바위·나무는 그대로 남는다).
     */
    public static int clearHalf(int i) {
        return i < FACADE_CLEAR_DEPTH ? FACADE_CLEAR_HALF : approachHalf(i) + 1;
    }

    /**
     * 그 자리가 <b>산문 정면 투영</b> 안인가 — 여기에는 줄기가 서지 않는다.
     *
     * <p>★{@link #clearHalf} 로 깎는 것만으로는 모자란다: 캠퍼스 조경은 접근로 포장
     * <b>뒤에</b> 심으므로, 깎아 놓은 자리에 도로 나무를 꽂는다 (2026-08-06 실측 —
     * 바위를 걷어내자 그 자리에 조경 소나무가 남았다). <b>비우는 손과 심는 손이 같은 표를
     * 읽어야</b> 어긋나지 않는다.
     */
    public static boolean inGateFacade(Plan plan, int x, int z) {
        Approach a = plan.approach();
        return a != null && inGateFacade(a.x(), a.z0(), x, z);
    }

    /** 같은 계약의 <b>순수</b> 꼴 — 월드 없이도 눈이 잰다 (축선·첫 행만 있으면 된다) */
    public static boolean inGateFacade(int axisX, int z0, int x, int z) {
        int i = z - z0;
        return i >= 0 && i < FACADE_CLEAR_DEPTH && Math.abs(x - axisX) <= FACADE_CLEAR_HALF;
    }

    /**
     * 그 패드에서 잰 정면 투영 상자 {x0, x1, z0, z1} — <b>산군 식생 제외</b>가 이 표를 읽는다.
     * ★식생은 캠퍼스보다 <b>먼저</b> 선다. 뒤에 깎아 없어지긴 하지만, 「먼저 심고 나중에
     * 깎는다」에 기대면 조성 순서가 한 번 바뀌는 날 조용히 되살아난다 — 아예 안 심는다.
     */
    public static int[] facadeBox(Pad gate) {
        int z0 = gate.zS() + 1;                       // approachOf 와 한 식
        return new int[]{gate.cx() - FACADE_CLEAR_HALF, gate.cx() + FACADE_CLEAR_HALF,
                z0, z0 + FACADE_CLEAR_DEPTH - 1};
    }

    /**
     * 접근로 부속이 앉는 행 인덱스 전부 — <b>눈이 이 표로 범위를 잰다</b> (같은 식이라
     * 조성과 어긋날 수 없다). 소문·비석·소나무 순.
     */
    public static int[] approachFixtureIndices() {
        int pines = (APPROACH_LEN - 15 + PINE_EVERY - 1) / PINE_EVERY;
        int banners = (APPROACH_LEN - BANNER_FROM + BANNER_EVERY - 1) / BANNER_EVERY;
        int[] out = new int[4 + Math.max(0, pines) + Math.max(0, banners)];
        out[0] = GATE_I;
        out[1] = STELE_A;
        out[2] = STELE_B;
        out[3] = FORECOURT_LANTERN_I;          // 문 앞 낮은 석등 한 쌍도 눈이 범위를 잰다
        int w = 4;
        for (int k = 0; k < Math.max(0, pines); k++) {
            out[w++] = 15 + k * PINE_EVERY;
        }
        for (int k = 0; k < Math.max(0, banners); k++) {      // ★D-21 깃대도 눈이 범위를 잰다
            out[w++] = BANNER_FROM + k * BANNER_EVERY;
        }
        return out;
    }

    /**
     * 접근로 보행면 표 — <b>반 칸 단위</b>. {@code hs[i]} = 상면 높이 × 2 이므로
     * 짝수는 풀블록 상면, 홀수는 <b>하단 반블록</b> 상면이다.
     *
     * <p>★<b>지형을 따르지 않는다</b> (2026-08-05 사용자 확정). 계단이 정본이고 지형이 계단에
     * 맞춰 깎이거나 채워진다 — 종전에는 반대였다(지형 추종). 한 주기는 「아홉 단 × 반 칸 하강
     * (디딤 2칸) + 참」이고, 네 주기가 <b>18블록</b>을 내려간다.
     */
    public static int[] approachProfile(int hs0) {
        int[] out = new int[APPROACH_LEN];
        int i = 0;
        int h = hs0;
        for (int c = 0; c < APPROACH_CYCLES; c++) {
            for (int s = 0; s < STAIR_RUN; s++) {
                for (int t = 0; t < STAIR_TREAD; t++) {
                    out[i++] = h;          // 디딤 — 한 단이 두 칸 깊다
                }
                h--;                       // 반 칸 하강 (풀블록 ↔ 하단 반블록이 갈린다)
            }
            int landing = (c == GATE_CYCLE ? GATE_LANDING : STAIR_LANDING);
            for (int t = 0; t < landing; t++) {
                out[i++] = h;              // 참 — 평지
            }
        }
        return out;
    }

    /**
     * 접근로 계획 — 산문단(1구역) 남단에서 남쪽으로, <b>조성 전 지형을 따라</b> 한 칸 물매의
     * 보행면 표를 만든다. 13칸마다 참(평탄 2칸 — 석등 쌍이 선다) · i 48~54 는 소문 마당
     * (기슭 언덕 마루쯤 — 작은 문루가 선다). 언덕은 지우지 않고 넘는다 (조율자 지시 —
     * 「기슭 언덕을 활용」).
     */
    private static Approach approachOf(World world, List<Pad> pads) {
        Pad gate = null;
        for (Pad p : pads) {
            if (p.spec().zone() == 1) {
                gate = p;
            }
        }
        if (gate == null) {
            return null;
        }
        // ★2026-08-05 — 지형을 읽지 않는다. 계단이 정본이고 지형이 따라온다
        //   (clearAbove 가 언덕을 깎고 fillDown 이 골을 채운다 — 조성이 그렇게 돌고 있었다).
        return new Approach(gate.cx(), gate.zS() + 1,
                approachProfile(2 * (gate.y() + 1)));
    }

    /**
     * 접근 시퀀스를 놓는다 (슬라이스 9b) — 절벽 아래에서 산문까지 <b>도착하는 과정</b>:
     * 도보 7 대계단(지형 추종·전 열 접지) → 참(석등 쌍) → 소문(작은 문루 — 기슭 언덕 마루) →
     * 비석·소나무 → 산문. 계단·측석·석등은 대계단 문법 그대로.
     */
    public static void paveApproach(World world, Plan plan, Tally tally) {
        Approach a = plan.approach();
        if (a == null) {
            return;
        }
        for (int i = 0; i < a.length(); i++) {
            int z = a.z0() + i;
            int by = a.blockYAt(i);
            boolean slab = a.isSlabAt(i);
            int half = approachHalf(i);        // ★폭 전이 — 입구 9 · 전이 참 11 · 문 앞 7
            for (int o = -half; o <= half; o++) {
                int x = a.x() + o;
                clearAbove(world, x, by, z, tally);
                fillDown(world, x, by - 1, z, tally);
                Block top = world.getBlockAt(x, by, z);
                if (slab) {
                    // ★반 칸 상면 — 하단 반블록. 마크에서 반 칸은 점프 없이 걸어 오르내린다
                    //   (「널널히 걷도록」의 실체). 계단 블록은 안 쓴다 — 한 칸 챌면이 되기 때문이다.
                    Slab data = (Slab) Material.STONE_BRICK_SLAB.createBlockData();
                    data.setType(Slab.Type.BOTTOM);
                    top.setBlockData(data, false);
                    tally.stairTreads++;
                } else {
                    top.setType(paveMaterial(x, z), false);
                    tally.pavement++;
                }
            }
            // ★난간은 <b>그 행의 보행 폭 바로 밖</b>에 선다. 고정 오프셋(±RAIL_OFF)이면
            //   폭이 9·11 로 넓어진 구간에서 난간이 <b>길 한가운데</b> 서 버린다
            //   (2026-08-06 폭 전이를 넣으며 생긴 회귀 — 눈이 잡는다).
            int railOff = half + 1;
            for (int side : new int[]{-railOff, railOff}) {
                int x = a.x() + side;
                clearAbove(world, x, by, z, tally);
                fillDown(world, x, by, z, tally);
                world.getBlockAt(x, by, z).setType(Material.STONE_BRICKS, false);
                boolean left = side < 0;
                if (PROPS && isFlagpoleRow(i) && i >= WIDEN_TO) {
                    // ★D-21 깃대 — 난간 캡 위, 좌우 쌍 (목표 1호의 자리). ★문전 구간과
                    //   전이 참은 제외한다 — 참의 깃대는 난간 밖 ±8 에 따로 선다.
                    flagpole(world, x, by, z, tally);
                } else if (PROPS && isLanternRow(i, left)) {
                    // ★D-22 석등 — 난간 캡 위, 좌우 번갈아 (목표 실측 간격 10~12)
                    stoneLantern(world, x, by, z, tally);
                } else if (PROPS && isForecourtLanternRow(i)) {
                    // ★문 앞 조명 — 난간에 <b>붙는</b> 낮은 석등 한 쌍 (3칸 · 좌우 대칭)
                    lowLantern(world, x, by, z, tally);
                } else {
                    world.getBlockAt(x, by + 1, z).setType(Material.STONE_BRICK_WALL, false);
                    tally.parapet++;
                }
            }
            // ★★문전 정면 투영을 비운다 — 난간 밖 ±FACADE_CLEAR_HALF 까지 (사용자 확정
            //   2026-08-06). 보행면·난간은 위 두 고리가 이미 비웠으므로 그 밖만 훑는다.
            //   ★이 한 줄이 없으면 20칸 바위가 문루 정면을 세로로 가로지른다 (실측된 진범).
            for (int o = -clearHalf(i); o <= clearHalf(i); o++) {
                if (Math.abs(o) <= railOff) {
                    continue;
                }
                clearAbove(world, a.x() + o, by, z, tally);
            }
            if (PROPS && isFlagpoleRow(i) && i < WIDEN_TO) {
                // ★깃대 첫 쌍 — 11칸 전이 참의 <b>외곽 모서리</b>(±8). 난간(±6) 밖이라
                //   보행에도 문간 시야에도 안 든다. 참 높이까지 대석을 세워 앉힌다
                //   (지형에 그냥 꽂으면 참과 높이가 갈려 기울어 보인다).
                for (int side : new int[]{-LANDING_BANNER_OFF, LANDING_BANNER_OFF}) {
                    int x = a.x() + side;
                    clearAbove(world, x, by, z, tally);
                    fillDown(world, x, by, z, tally);
                    world.getBlockAt(x, by, z).setType(Material.STONE_BRICKS, false);
                    flagpole(world, x, by, z, tally);
                }
            }
            if (i == GATE_I + 2) {
                // 소문 — 넓은 참(8칸)의 한가운데. ★마당 남끝에서 세운다: 지붕이 z±2 를 덮으므로
                //   그 행들의 조성(clearAbove)이 끝난 뒤여야 한다 (마당이 평탄이라 y 동일)
                approachGate(world, a.x(), a.blockYAt(GATE_I), a.z0() + GATE_I, tally);
            }
            if (PROPS && (i == STELE_A || i == STELE_B)) {
                // ★2026-08-05 — 비석을 <b>축선 시야 회랑</b> 밖으로 밀었다. 옛 자리(±RAIL_OFF+3
                //   = ±7)는 통행은 안 막지만, 계단 아래 축선에 선 눈에는 <b>가까운 비석이 멀리 있는
                //   문루 정면을 세로로 가로질렀다</b> (사용자 지적 — stair_1정면). 목표 사진(1호)은
                //   축선 시야가 비어 있고 소품은 좌우로 물러나 있다. AXIS_CLEAR 가 그 계약이다.
                stele(world, a.x() + AXIS_CLEAR, z, tally);
                stele(world, a.x() - AXIS_CLEAR, z, tally);
            }
            if (GREEN && i >= 15 && (i - 15) % PINE_EVERY == 0) {
                // ★계단 회랑(±APPROACH_CLEAR) 밖에 선다 — 계단을 덮지 않고 곁을 채운다 (D-16).
                //   ★문전 구간에서는 산문 <b>정면 투영</b> 밖까지 민다 (2026-08-06 · pineOff).
                int off = pineOff(i);
                approachPine(world, a.x() + (((i - 15) / PINE_EVERY) % 2 == 0 ? off : -off), z, tally);
            }
        }
    }

    /**
     * 소문 — 접근로를 걸치는 작은 문루 (★슬라이스 10-③ 재건: 「돌기둥 골대」가 아니라
     * 지붕 있는 문이다). 적주 두 쌍(2겹 · 통행을 15 로 조인다 — 시퀀스의 조임) · 석재 기단 ·
     * 다크오크 보+공포 · 팔작풍 기와 지붕(내밈 반블록·물매 계단·용마루·치미) · 빈 현판 · 등롱.
     * 디테일 키트 문법(공포·겹처마)의 접근로판.
     */
    private static void approachGate(World world, int ax, int y, int z, Tally tally) {
        // ★2026-08-04 계단 폭 7 로 줄면서 문루도 함께 좁혔다 — 기둥이 난간선(±RAIL_OFF)에
        //   앉아 보행 7 은 그대로 트인다 (옛 값 ±7·±8 은 전폭 21 계단의 것이었다)
        for (int side : new int[]{-5, -4, 4, 5}) {               // 적주 두 쌍 — 기단 위에 선다
            int x = ax + side;
            fillDown(world, x, y, z, tally);
            world.getBlockAt(x, y, z).setType(Material.STONE_BRICKS, false);
            for (int dy = 1; dy <= 7; dy++) {
                world.getBlockAt(x, y + dy, z).setType(Material.STRIPPED_MANGROVE_LOG, false);
            }
        }
        for (int o = -6; o <= 6; o++) {                          // 보 + 공포 띠
            world.getBlockAt(ax + o, y + 8, z).setType(Material.DARK_OAK_PLANKS, false);
            for (int dz : new int[]{-1, 1}) {
                world.getBlockAt(ax + o, y + 8, z + dz).setType(
                        Math.floorMod(o, 3) == 0 ? Material.DARK_OAK_PLANKS
                                : Material.DARK_OAK_SLAB, false);
            }
        }
        for (int o = -8; o <= 8; o++) {                          // 기와 지붕 — 내밈·물매·용마루
            world.getBlockAt(ax + o, y + 9, z - 2).setType(Material.DEEPSLATE_TILE_SLAB, false);
            world.getBlockAt(ax + o, y + 9, z + 2).setType(Material.DEEPSLATE_TILE_SLAB, false);
            for (int dz : new int[]{-1, 1}) {
                Stairs s = (Stairs) Material.DEEPSLATE_TILE_STAIRS.createBlockData();
                s.setFacing(dz > 0 ? BlockFace.NORTH : BlockFace.SOUTH);   // 오름이 용마루를 향한다
                world.getBlockAt(ax + o, y + 9, z + dz).setBlockData(s, false);
            }
            world.getBlockAt(ax + o, y + 9, z).setType(Material.DEEPSLATE_TILES, false);
            world.getBlockAt(ax + o, y + 10, z).setType(
                    Math.abs(o) == 8 ? Material.DEEPSLATE_TILE_WALL
                            : Material.DEEPSLATE_TILE_SLAB, false);        // 용마루 · 끝 치미
        }
        for (int o = -1; o <= 1; o++) {                          // 빈 현판 (남면 — 오는 이가 본다)
            world.getBlockAt(ax + o, y + 7, z + 1).setType(Material.DARK_OAK_PLANKS, false);
        }
        world.getBlockAt(ax + 4, y + 3, z + 1).setType(Material.LANTERN, false);
        world.getBlockAt(ax - 4, y + 3, z + 1).setType(Material.LANTERN, false);
        tally.lanterns += 2;
    }

    /**
     * ★D-22 석등 — 난간 위의 <b>네모 등롱</b> (목표 1호 실측).
     *
     * <p>돌 기둥 2 + <b>1×1 발광 고체</b> + 갓 반블록. 종전에는 {@code LANTERN}(아이템형 소형
     * 모델)이라 <b>부피가 목표와 달랐다</b> — 목표의 등롱은 한 칸을 꽉 채운 상자이고 창살 무늬가
     * 있다.
     *
     * <p>★발광체 색의 근거 — <b>목표 1호의 등롱을 픽셀로 쟀다</b>: RGB(255,199,104)·(253,178,76)
     * <b>S59~70% V96~100%</b>. 발광체는 단청 띠(벽면 장식)와 층위가 다르다 — <b>빛나는 것은
     * 밝아야 한다</b>. 그래서 여기선 채도가 높은 것이 옳고, 글로우스톤(렌더 ≈RGB(255,203,111))이
     * 실측과 거의 일치한다. 갓은 등롱을 덜 가리게 <b>반블록</b>으로 얹는다.
     */
    private static void stoneLantern(World world, int x, int by, int z, Tally tally) {
        lanternOf(world, x, by, z, LANTERN_H, tally);
    }

    /**
     * ★문 앞 낮은 석등 — 난간에 <b>붙는</b> {@link #FORECOURT_MAX_H}칸 등롱
     * (사용자 확정 2026-08-06).
     *
     * <p>{@link #stoneLantern} 과 같은 문법이되 <b>기둥 한 켜를 뺀다</b> (4칸 → 3칸).
     * 문전 구간에서 높이는 곧 위계라, 독립 등롱이 적주보다 앞에 서면 문루의 위계를 나눠 먹는다
     * — 낮추면 <b>난간의 연장</b>으로 읽혀 축을 안 자른다. 좌우 <b>대칭 한 쌍</b>만 선다
     * (번갈아 두면 문간이 한쪽으로 기운다).
     */
    private static void lowLantern(World world, int x, int by, int z, Tally tally) {
        lanternOf(world, x, by, z, FORECOURT_MAX_H, tally);
    }

    /**
     * 등롱 한 기 — <b>높이 h 를 받아</b> 기둥(h−2켜) · 발광체 · 갓으로 쌓는다.
     * ★높은 것과 낮은 것이 <b>한 식</b>이라 갈라질 수 없다 — 눈은 {@link #propHeight}
     * 로 같은 높이를 읽는다.
     */
    private static void lanternOf(World world, int x, int by, int z, int h, Tally tally) {
        for (int dy = 1; dy <= h - 2; dy++) {
            world.getBlockAt(x, by + dy, z).setType(Material.STONE_BRICKS, false);
        }
        world.getBlockAt(x, by + h - 1, z).setType(Material.GLOWSTONE, false);      // 등롱 — 1×1 발광 고체
        Slab cap = (Slab) Material.DEEPSLATE_TILE_SLAB.createBlockData();
        cap.setType(Slab.Type.BOTTOM);
        world.getBlockAt(x, by + h, z).setBlockData(cap, false);                    // 갓
        tally.lanterns++;
    }

    /**
     * ★D-21 깃대(당간) — 계단을 따라 오르며 <b>시선을 위로 이끄는</b> 장치 (목표 1호).
     *
     * <p>목표 실측: 짙은 기둥 위에 <b>붉은 가로대</b>가 얹히고 그 위로 <b>짙은 남색 배너</b>가
     * 늘어진다 (배너 RGB(34,40,50)·H223° — 처마 그늘에 잠긴 남색). 문양은 넣지 않는다 —
     * 무지 배너다 (작명·문양은 사용자 몫).
     *
     * <p>★자리 — <b>난간 캡 위</b>(±{@link #RAIL_OFF}). 목표 사진 그대로다. 축선 회랑
     * (±{@link #AXIS_CLEAR})은 <b>두꺼운</b> 소품만 막는다 — 깃대 기둥은 1칸 울타리라
     * 원근으로도 가늘게 보이고, 목표 사진에서도 깃대가 계단 양옆에 촘촘한데 문루가 잘 보인다.
     * <b>가리는 정도는 높이가 아니라 두께가 정한다</b> (2026-08-05 계약 교정).
     *
     * <p>★★조립 교정 (2026-08-05 실기동 판정 — {@code banner_2근경.png}). 첫 판은 셋이 틀렸다:
     * <ul>
     *   <li><b>가로대가 통짜 큐브</b>라 배너보다 먼저 눈에 들어왔다 (붉은 맹그로브 원목).
     *       목표의 가로대는 <b>가늘고 어둡다</b> → 울타리 팔로 갈았다 (기둥과 같은 어두운
     *       재료 · 울타리는 굵기가 블록의 1/4 이라 원경에서 선으로 읽힌다).</li>
     *   <li><b>배너가 로열블루</b>라 원경에서 파란 점으로 튀었다 → {@link Material#BLACK_BANNER}.
     *       ★색을 <b>쟀다</b>: 목표 배너 RGB(41,45,54) <b>H222 S24% V21%</b> (밝은 상위 10%조차
     *       V25% — 그늘 속 짙은 남색). 우리 BLUE 는 렌더 RGB(44,49,121) <b>S64% V47%</b> 로
     *       채도·명도가 3배였다. 바닐라 배너 바탕색과의 RGB 거리: <b>BLACK 29.0</b> ·
     *       GRAY 53.3 · BLUE 119.8 — 검정이 압도적으로 가깝다 (짙은 남색 염료는 없다).
     *       단청 꿀집 때와 같은 종류의 어긋남이라, 같은 방법(픽셀 측정)으로 골랐다.</li>
     *   <li><b>배너가 가로대 위에 얹혀</b> 있었다 → 가로대 <b>아래</b>로 늘어뜨렸다 (벽걸이
     *       배너를 마디의 옆면에 매단다 — 벽걸이는 제 자리에서 아래로 드리운다).</li>
     * </ul>
     *
     * <p>★배너를 <b>북면</b>(z-1)에 매다는 까닭: {@link #clearAbove} 가 그 행의 {@code by+1}
     * 위를 전부 지우므로, <b>아직 처리 안 된 남쪽 행</b>(z+1)에 걸면 뒤이어 조용히 지워진다.
     * 북쪽 행은 이미 지나갔다. 무지 배너라 앞뒤 모습이 같아 방향은 무해하다.
     */
    private static void flagpole(World world, int x, int by, int z, Tally tally) {
        for (int dy = 1; dy <= FLAG_POLE_TOP; dy++) {
            world.getBlockAt(x, by + dy, z).setType(Material.DARK_OAK_FENCE, false);   // 가는 기둥
        }
        // 마디 — 배너를 매다는 solid 한 칸 (어두워 기둥의 연장으로 읽힌다)
        world.getBlockAt(x, by + FLAG_BANNER_Y, z).setType(Material.DARK_OAK_LOG, false);
        // 가로대 — 울타리 팔 (가늘다 · 배너보다 위)
        for (int arm = -1; arm <= 1; arm++) {
            world.getBlockAt(x + arm, by + FLAG_CROSS_Y, z).setType(Material.DARK_OAK_FENCE, false);
        }
        // 배너 — 마디의 북면에 걸어 아래로 늘어뜨린다
        Directional banner = (Directional) Material.BLACK_WALL_BANNER.createBlockData();
        banner.setFacing(BlockFace.NORTH);
        Block bb = world.getBlockAt(x, by + FLAG_BANNER_Y, z - 1);
        bb.setBlockData(banner, false);
        // ★★흰 원 문양 — 짙은 바탕에서 <b>형태를 읽히게 하는</b> 것 (2026-08-05 판정).
        //   무지 검정은 어두운 기둥과 붙어 그림자와 구별되지 않았고, 원경에서는 배너가
        //   아예 사라졌다. 목표 1호의 배너에는 <b>가운데 흰 원</b>이 있고 그것이 표지다.
        if (bb.getState() instanceof org.bukkit.block.Banner state) {
            state.setPatterns(bannerPatterns());
            state.update(true, false);
        }
        tally.banners++;
    }

    /** 비석 — 참 곁의 돌비 (기단 + 몸 3 + 갓) */
    private static void stele(World world, int x, int z, Tally tally) {
        int g = groundY(world, x, z);
        world.getBlockAt(x, g + 1, z).setType(Material.STONE_BRICKS, false);
        for (int dy = 2; dy <= 4; dy++) {
            world.getBlockAt(x, g + dy, z).setType(Material.POLISHED_ANDESITE, false);
        }
        world.getBlockAt(x, g + 5, z).setType(Material.STONE_BRICK_WALL, false);
    }

    /**
     * 접근로 곁 소나무 — ★12.5 우산꼴 통일 (산군·캠퍼스와 한 문법): 높이 5~7 · 폭 7~9 ·
     * 층 2. 조경 층위 재료(SPRUCE_WOOD — 유출 눈 밖).
     */
    private static void approachPine(World world, int x, int z, Tally tally) {
        int g = groundY(world, x, z);
        long hh = mix(SALT_RIB ^ 0x917EL, x, 0, z);
        int h = 5 + (int) Math.floorMod(hh, 3);
        int rad = 3 + (int) Math.floorMod(hh >> 8, 2);
        for (int dy = 1; dy <= h; dy++) {
            world.getBlockAt(x, g + dy, z).setType(Material.SPRUCE_WOOD, false);
        }
        for (int t = 0; t < 2; t++) {                        // ★12.6 — 층마다 두 켜
            int tr = Math.max(1, rad - (1 - t));
            for (int k = 0; k < 2; k++) {
                int ty = g + h - t * 3 - (1 - k);
                int rr = Math.max(1, tr - k);
                for (int dx = -rr; dx <= rr; dx++) {
                    for (int dz = -rr; dz <= rr; dz++) {
                        if (Math.abs(dx) + Math.abs(dz) > rr + 1
                                || (dx == 0 && dz == 0 && t > 0 && k == 0)) {
                            continue;
                        }
                        int r = (int) Math.floorMod(mix(0x1EAF5L, x + dx, ty, z + dz), 100);
                        int dark = 22 + (k == 0 ? 20 : 0)
                                + Math.min(18, Math.max(0, rr - Math.abs(dx) - Math.abs(dz)) * 9);
                        Material leaf = r < dark ? Material.AZALEA_LEAVES
                                : (r >= 92 && k == 1 ? Material.FLOWERING_AZALEA_LEAVES
                                : Material.SPRUCE_LEAVES);
                        world.getBlockAt(x + dx, ty, z + dz).setType(leaf, false);
                    }
                }
            }
        }
        world.getBlockAt(x, g + h + 1, z).setType(Material.SPRUCE_LEAVES, false);
    }

    /**
     * 명세 → 실좌표 — <b>순수 함수</b> (눈이 이것을 잰다). 한 변 상한(H-3)·구역 번호 중복·
     * 패드 겹침을 여기서 거절한다.
     */
    public static List<Pad> resolvePads(Campus campus, int peakX, int peakZ, int baseY) {
        List<Pad> out = new ArrayList<>(campus.pads().size());
        Set<Integer> zones = new HashSet<>();
        for (PadSpec ps : campus.pads()) {
            if (ps.width() > MAX_TIER_WIDTH || ps.depth() > MAX_TIER_WIDTH) {
                throw new IllegalArgumentException("패드 한 변 상한 위반 (H-3 ≤" + MAX_TIER_WIDTH
                        + "): " + ps.name() + " " + ps.width() + "×" + ps.depth());
            }
            if (!zones.add(ps.zone())) {
                throw new IllegalArgumentException("구역 번호 중복: " + ps.zone());
            }
            if (Math.abs(ps.expectedLift()) > 40) {
                // ★한도 40→56(슬라이스 8)→64(8.7): 측문 동벽의 실측 Δ64 — B2 동단이 산 발치 밖
                //   평지 위 성곽 벼랑이다 (이미지 12 단애 문법 · 벼랑 아래 접속은 후속 도보길 몫).
                //   실측값 계약은 은폐가 아니다 — 근거 없는 추정만이 만용이다.
                throw new IllegalArgumentException("expectedLift 한도(±40) 밖: " + ps.name()
                        + " Δ" + ps.expectedLift() + " — 그건 계약이 아니라 만용이다");
            }
            int x0 = peakX + ps.dx() - ps.width() / 2;
            int zN = peakZ + ps.dz() - ps.depth() / 2;
            Pad pad = new Pad(ps, baseY + ps.h(), x0, x0 + ps.width() - 1, zN, zN + ps.depth() - 1);
            for (Pad prev : out) {
                if (pad.x0() <= prev.x1() && prev.x0() <= pad.x1()
                        && pad.zN() <= prev.zS() && prev.zN() <= pad.zS()) {
                    throw new IllegalArgumentException("패드 겹침: " + ps.name() + " ↔ " + prev.spec().name());
                }
            }
            out.add(pad);
        }
        return List.copyOf(out);
    }

    /**
     * 링크 → 실계단 — <b>순수 함수</b>. 낙차 창·담김·닿음(보도 상한)을 거절하고,
     * ★계단 몸체가 <b>남의 패드를 지나는 것</b>도 여기서 거절한다 (제 위·아래 패드는 예외 —
     * 침범은 조성이 아니라 계획이 막는다).
     */
    public static List<StairLane> resolveLanes(Campus campus, List<Pad> pads) {
        List<StairLane> lanes = new ArrayList<>(campus.links().size());
        for (StairLink link : campus.links()) {
            StairLane lane = laneOf(padOf(pads, link.upperZone()), padOf(pads, link.lowerZone()), link);
            int px = lane.dirZ() != 0 ? 1 : 0;
            int pz = lane.dirZ() != 0 ? 0 : 1;
            for (int t = 1; t <= lane.length(); t++) {
                for (int o = -lane.rail(); o <= lane.rail(); o++) {
                    int x = lane.startX() + lane.dirX() * (t - 1) + px * o;
                    int z = lane.startZ() + lane.dirZ() * (t - 1) + pz * o;
                    for (Pad p : pads) {
                        int zone = p.spec().zone();
                        if (zone != link.upperZone() && zone != link.lowerZone() && p.contains(x, z)) {
                            throw new IllegalArgumentException("계단 " + link.upperZone() + "→"
                                    + link.lowerZone() + " 이 남의 패드를 지난다: " + p.spec().name()
                                    + " (" + x + "," + z + ") — 링크나 패드 자리를 고쳐라");
                        }
                    }
                }
            }
            lanes.add(lane);
        }
        return List.copyOf(lanes);
    }

    private static Pad padOf(List<Pad> pads, int zone) {
        for (Pad p : pads) {
            if (p.spec().zone() == zone) {
                return p;
            }
        }
        throw new IllegalArgumentException("링크가 없는 구역을 부른다: " + zone);
    }

    /** 계단 하나를 앉힌다 — 순수 기하. 명세가 안 앉으면 이유를 말하고 던진다. */
    public static StairLane laneOf(Pad upper, Pad lower, StairLink link) {
        int dy = upper.y() - lower.y();
        String who = link.upperZone() + "→" + link.lowerZone();
        if (dy < MIN_STAIR_DY || dy > MAX_STAIR_DY) {
            throw new IllegalArgumentException("계단 " + who + ": 낙차 " + dy + " 이 ["
                    + MIN_STAIR_DY + "," + MAX_STAIR_DY + "] 밖 — 계단참 패드를 끼우거나 높이를 고쳐라");
        }
        int dirX = 0;
        int dirZ = 0;
        int sx;
        int sz;
        switch (link.side()) {
            case 'S' -> {
                dirZ = 1;
                sx = upper.cx() + link.off();
                sz = upper.zS() + 1;
            }
            case 'N' -> {
                dirZ = -1;
                sx = upper.cx() + link.off();
                sz = upper.zN() - 1;
            }
            case 'E' -> {
                dirX = 1;
                sx = upper.x1() + 1;
                sz = upper.cz() + link.off();
            }
            case 'W' -> {
                dirX = -1;
                sx = upper.x0() - 1;
                sz = upper.cz() + link.off();
            }
            default -> throw new IllegalArgumentException("계단 " + who + ": 면 '" + link.side()
                    + "' 은 S·N·E·W 가 아니다");
        }
        // 폭 방향 담김 — 계단 몸체(±rail)가 아랫패드 안에 들어야 닿아서 이인다
        int rail = link.half() + 1;
        if (dirZ != 0) {
            if (sx - rail < lower.x0() || sx + rail > lower.x1()) {
                throw new IllegalArgumentException("계단 " + who + ": 몸체(x" + (sx - rail) + ".."
                        + (sx + rail) + ")가 아랫패드 폭(x" + lower.x0() + ".." + lower.x1() + ") 밖");
            }
        } else {
            if (sz - rail < lower.zN() || sz + rail > lower.zS()) {
                throw new IllegalArgumentException("계단 " + who + ": 몸체(z" + (sz - rail) + ".."
                        + (sz + rail) + ")가 아랫패드 깊이(z" + lower.zN() + ".." + lower.zS() + ") 밖");
            }
        }
        int treads = dy - 1;
        int entry = -1;
        for (int t = 1; t <= treads + MAX_WALK + 1; t++) {
            if (lower.contains(sx + dirX * (t - 1), sz + dirZ * (t - 1))) {
                entry = t;
                break;
            }
        }
        if (entry < 0) {
            throw new IllegalArgumentException("계단 " + who + ": 램프+보도 " + (treads + MAX_WALK)
                    + "칸 안에 아랫패드에 닿지 않는다 — 패드를 당기거나 링크를 고쳐라");
        }
        int walk = Math.max(0, entry - treads);
        return new StairLane(link, sx, sz, dirX, dirZ, upper.y(), lower.y(), treads, walk);
    }

    /** 순수 전수 검증 — 명세만으로 앉힘 전체를 재본다 (월드 불요 · 눈과 계획이 같은 길을 쓴다). */
    public static void validate(Campus campus) {
        List<Pad> pads = resolvePads(campus, 0, 0, 0);
        resolveBridges(campus, pads, resolveLanes(campus, pads), 0, 0, 0);
    }

    /**
     * 다리 명세 → 실다리 — <b>순수 함수</b>. 여기서 거절한다: ①스팬 > {@value #MAX_BRIDGE_SPAN}
     * ②양끝 한 칸 밖이 패드가 아니거나 상판 높이와 패드 h 가 다르다 (상판은 수평이다)
     * ③상판 폭(±2)이 끝 패드의 가로 범위 밖 ④상판이 남의 패드나 계단 몸체를 지난다.
     */
    public static List<Bridge> resolveBridges(Campus campus, List<Pad> pads,
                                              List<StairLane> lanes, int peakX, int peakZ, int baseY) {
        List<Bridge> out = new ArrayList<>(campus.bridges().size());
        for (BridgeSpec bs : campus.bridges()) {
            int c = bs.c() + (bs.alongX() ? peakZ : peakX);
            int a0 = bs.a0() + (bs.alongX() ? peakX : peakZ);
            int a1 = bs.a1() + (bs.alongX() ? peakX : peakZ);
            int y = baseY + bs.h();
            int span = a1 - a0 + 1;
            if (span < 3 || span > MAX_BRIDGE_SPAN) {
                throw new IllegalArgumentException("다리 " + bs.name() + ": 스팬 " + span
                        + " 이 [3," + MAX_BRIDGE_SPAN + "] 밖");
            }
            Pad endA = padAt(pads, bs.alongX() ? a0 - 1 : c, bs.alongX() ? c : a0 - 1);
            Pad endB = padAt(pads, bs.alongX() ? a1 + 1 : c, bs.alongX() ? c : a1 + 1);
            if (endA == null || endB == null) {
                throw new IllegalArgumentException("다리 " + bs.name() + ": 끝이 패드에 닿지 않는다"
                        + " (a0-1/a1+1 이 패드 밖)");
            }
            if (endA.y() != y || endB.y() != y) {
                throw new IllegalArgumentException("다리 " + bs.name() + ": 상판 y" + y
                        + " 이 끝 패드와 다르다 (" + endA.spec().name() + " y" + endA.y() + " · "
                        + endB.spec().name() + " y" + endB.y() + ") — 상판은 수평이다");
            }
            for (Pad end : new Pad[]{endA, endB}) {
                int w0 = bs.alongX() ? end.zN() : end.x0();
                int w1 = bs.alongX() ? end.zS() : end.x1();
                if (c - 2 < w0 || c + 2 > w1) {
                    throw new IllegalArgumentException("다리 " + bs.name() + ": 상판 폭(" + (c - 2)
                            + ".." + (c + 2) + ")이 " + end.spec().name() + " 가로 범위 밖");
                }
            }
            Bridge bridge = new Bridge(bs, bs.alongX(), c, a0, a1, y, endA, endB);
            for (int t = a0; t <= a1; t++) {
                for (int o = -2; o <= 2; o++) {
                    int x = bs.alongX() ? t : c + o;
                    int z = bs.alongX() ? c + o : t;
                    for (Pad pd : pads) {
                        if (pd != endA && pd != endB && pd.contains(x, z)) {
                            throw new IllegalArgumentException("다리 " + bs.name()
                                    + " 상판이 남의 패드를 지난다: " + pd.spec().name()
                                    + " (" + x + "," + z + ")");
                        }
                    }
                    for (StairLane lane : lanes) {
                        if (lane.covers(x, z)) {
                            throw new IllegalArgumentException("다리 " + bs.name()
                                    + " 상판이 계단 몸체를 지난다: " + lane.link().upperZone() + "→"
                                    + lane.link().lowerZone() + " (" + x + "," + z + ")");
                        }
                    }
                }
            }
            out.add(bridge);
        }
        return List.copyOf(out);
    }

    private static Pad padAt(List<Pad> pads, int x, int z) {
        for (Pad p : pads) {
            if (p.contains(x, z)) {
                return p;
            }
        }
        return null;
    }

    /** 발자국 안 실지형(수목 제외)의 상위 백분위 y — 쌓기 우선의 눈금 */
    private static int percentileGround(World world, int x0, int x1, int zN, int zS, int pct) {
        int cols = (x1 - x0 + 1) * (zS - zN + 1);
        int[] gs = new int[cols];
        int k = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = zN; z <= zS; z++) {
                gs[k++] = groundY(world, x, z);
            }
        }
        java.util.Arrays.sort(gs);
        int idx = Math.min(cols - 1, (int) Math.ceil(cols * (pct / 100.0)) - 1);
        return gs[Math.max(0, idx)];
    }

    /**
     * ★식생 재료 — 「실지면」이 아니다 (슬라이스 11.5 · 접지 64건의 처방): 산군 식생(소나무
     * 몸통·잎·관목·풀·덩굴)이 지형 위에 서므로, 계획(ys 표)·조성(fillDown)·검수가 같은
     * 정의로 이것들을 <b>통과·제거 대상</b>으로 본다. groundSolid 가 나무를 「단단하다」고
     * 해도 나무 위는 길의 지면이 아니다. ★이끼 블록(MOSS_BLOCK)은 식생이 아니라 지면이다 —
     * 절벽 캡·완사면 바닥으로 깔리는 밟는 땅이다.
     */
    private static final Set<Material> VEGETATION = EnumSet.of(
            Material.SPRUCE_WOOD, Material.SPRUCE_LEAVES, Material.CHERRY_LOG,
            Material.CHERRY_LEAVES, Material.AZALEA, Material.FLOWERING_AZALEA,
            Material.AZALEA_LEAVES, Material.FLOWERING_AZALEA_LEAVES,   // ★12.6 잎 톤
            Material.FERN, Material.SHORT_GRASS, Material.VINE, Material.GLOW_LICHEN,
            Material.MOSS_CARPET);

    /** 그 재료가 식생인가 — 계획·조성·검수 공용 정의 (눈이 잰다) */
    public static boolean isVegetation(Material m) {
        return VEGETATION.contains(m);
    }

    /** 실지면 — 수목·잎·식생을 뚫고 밟는 땅을 찾는다 ({@link TrailBuilder#groundSolid} + 식생 통과) */
    private static int groundY(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z);
        int min = world.getMinHeight();
        while (y > min) {
            Material m = world.getBlockAt(x, y, z).getType();
            if (TrailBuilder.groundSolid(m) && !VEGETATION.contains(m)) {
                break;
            }
            y--;
        }
        return y;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 조성 — 패드 하나·계단 하나씩 (TickBudget 이 사이사이 예산을 물린다)
    // ═══════════════════════════════════════════════════════════════════

    /** 조성 대장 — census 가 읽는다 */
    public static final class Tally {
        public long pavement;
        public long bridgeDeck;
        public long core;
        public long wallFace;
        public long parapet;
        public long stairTreads;
        public long cut;
        public long lanterns;
        public long banners;          // ★D-21 깃대
    }

    /**
     * 패드 하나를 앉힌다 — 걷기(수목·돌출 깎기) → 채움(속은 돌, 가장자리는 석축 결) →
     * 포장(가장자리 테두리는 석전) → 여장(계단이 드나드는 자리는 비운다 · 모서리 등롱).
     * <b>월드+패드 기하만 쓴다</b> — 곁봉 패드(슬라이스 3)가 그대로 재사용한다.
     */
    public static void pavePad(World world, Plan plan, Pad pad, Tally tally) {
        for (int x = pad.x0(); x <= pad.x1(); x++) {
            for (int z = pad.zN(); z <= pad.zS(); z++) {
                int g = groundY(world, x, z);
                // 걷기 — 패드 위 하늘을 연다 (목표 위로 솟은 바위·수목 = 상위 15%의 깎기)
                int clearTop = Math.max(g + 2, pad.y() + HEADROOM);
                for (int y = pad.y() + 1; y <= clearTop; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (!b.getType().isAir()) {
                        b.setType(Material.AIR, false);
                        tally.cut++;
                    }
                }
                boolean edge = x == pad.x0() || x == pad.x1() || z == pad.zN() || z == pad.zS();
                // 채움 — 실지면에서 포장면 밑까지. 가장자리 열은 석축 결(보이는 면).
                // ★슬라이스 9: 늑재 구간은 자연 암반 결 — 「성벽은 암반에서 솟는다」 (코덱스 검토 §③)
                boolean rib = edge && ribSegment(x, z);
                for (int y = g + 1; y < pad.y(); y++) {
                    world.getBlockAt(x, y, z).setType(
                            edge ? (rib ? rockMaterial(x, y, z)
                                    : faceMaterial(x, y, z, pad.y() - y))   // ★13b — 아래일수록 젖는다
                                    : Material.STONE, false);
                    if (edge) {
                        tally.wallFace++;
                    } else {
                        tally.core++;
                    }
                }
                // 포장면 — 가장자리는 석전 테두리, 안은 박석 섞음
                world.getBlockAt(x, pad.y(), z)
                        .setType(edge ? Material.STONE_BRICKS : paveMaterial(x, z), false);
                tally.pavement++;
            }
        }
        batter(world, plan, pad, tally);
        skirt(world, plan, pad, tally);
        parapet(world, plan, pad, tally);
    }

    /**
     * ★경사 석축 + 암반 늑재 (슬라이스 9 — 「석대 해체」, 코덱스 검토 §①·③의 처방).
     *
     * <p>수직 옹벽 일색이 「자연지형을 지운 직육면체 석대」로 읽혔다 — 옹벽이 아래로 갈수록
     * 밖으로 계단져 나가고(3칸 내려갈 때 1칸 — 산처럼 발치가 넓다), 결정론 구간(늑재)마다
     * 석축 대신 <b>자연 암반 결</b>이 솟는다 (늑재는 한 칸 더 돌출). 우면(우세면)이 산몸에
     * 닿으면 그 자리부터는 <b>산이 벽이다</b> — 파묻힌 단구가 공짜로 나온다.
     *
     * <p>계약: 전 열이 실지형까지 채워져 접지 (검수 ② 는 패드 안만 재지만 이 열들도 뜨지
     * 않는다) · 남의 패드({@code onOtherPad})·계단/다리 몸체({@code laneCovered})는 비킨다 —
     * 잇닿은 통단 칸 사이·척추 계단 골에는 안 나간다.
     */
    private static void batter(World world, Plan plan, Pad pad, Tally tally) {
        for (int x = pad.x0(); x <= pad.x1(); x++) {
            batterColumn(world, plan, pad, x, pad.zN(), 0, -1, tally);
            batterColumn(world, plan, pad, x, pad.zS(), 0, 1, tally);
        }
        for (int z = pad.zN(); z <= pad.zS(); z++) {
            batterColumn(world, plan, pad, pad.x0(), z, -1, 0, tally);
            batterColumn(world, plan, pad, pad.x1(), z, 1, 0, tally);
        }
        corners(world, plan, pad, tally);
    }

    /**
     * ★16-③ <b>모서리를 깬다</b> — 직각으로 딱 떨어지는 네 귀가 인공성의 큰 몫이다.
     *
     * <p>종전 배터는 네 <b>면</b>만 나갔다 — 그래서 모서리에 대각선 빈틈(계단꼴 노치)이
     * 남아 「자로 그은 상자」로 읽혔다. 이제 대각으로도 바위가 물려 나가되, 뻗는 거리를
     * 귀마다 불규칙하게(2~5) 두고 같은 결({@link SpireField#faceRelief})을 얹어 각을 흐린다.
     *
     * <p>여장(패드 위)은 안 건드린다 — 사람이 쌓은 난간은 반듯한 것이 옳다. 흐리는 것은
     * <b>그 아래 축대</b>뿐이다 (지시 §③).
     */
    private static void corners(World world, Plan plan, Pad pad, Tally tally) {
        int[][] cs = {
                {pad.x0(), pad.zN(), -1, -1}, {pad.x1(), pad.zN(), 1, -1},
                {pad.x0(), pad.zS(), -1, 1}, {pad.x1(), pad.zS(), 1, 1},
        };
        for (int[] c : cs) {
            int ex = c[0];
            int ez = c[1];
            int dx = c[2];
            int dz = c[3];
            long h = mix(SALT_RIB ^ 0xC02E4L, ex, 0, ez);
            int reach = 2 + (int) Math.floorMod(h, 4);          // 귀마다 2~5칸 (불규칙)
            boolean rib = ribSegment(ex, ez);
            for (int k = 1; k <= reach; k++) {
                for (int a = 0; a <= k; a++) {
                    // 대각 계단꼴 — 귀에서 부채처럼 물려 나간다
                    int x = ex + dx * (k - a);
                    int z = ez + dz * a;
                    if (pad.contains(x, z) || onOtherPad(plan, pad, x, z)
                            || laneCovered(plan, x, z)) {
                        continue;
                    }
                    int base = pad.y() - 3 * k;
                    int top = SpireField.faceRelief(base, x, z);
                    int g = groundY(world, x, z);
                    if (top <= g) {
                        continue;   // 산몸에 닿았다 — 산이 벽이다
                    }
                    for (int y = g + 1; y <= top; y++) {
                        world.getBlockAt(x, y, z).setType(
                                rib ? rockMaterial(x, y, z)
                                        : faceMaterial(x, y, z, top - y + 2), false);
                        tally.wallFace++;
                    }
                }
            }
        }
    }

    private static void batterColumn(World world, Plan plan, Pad pad, int ex, int ez,
                                     int dx, int dz, Tally tally) {
        boolean rib = ribSegment(ex, ez);
        int faceH0 = pad.y() - groundY(world, ex + dx, ez + dz);
        int steps = batterSteps(ex, ez, faceH0, rib);
        // ★슬라이스 13a — 중간 선반 (거대 회색 면의 분해): 구간(~9칸)마다 해시가 선반 하나를
        //   정한다 — 그 구간에서는 배터가 k0 칸째에서 폭 2~4 로 <b>평평하게 내밀어</b> 한 면을
        //   2~3단으로 가른다. 선반 위에는 난간·식생이 앉는다 (shelfTop 이 자리를 알려 준다).
        // ★13c-① 다중 선반 — 높은 면은 선반이 <b>여러 개</b> 난다 (면 높이 ÷ 12 · 20+ 최소 2 ·
        //   32+ 최소 3). 「32+ 는 얕은 자리에」가 위쪽만 갈라 아래 20칸이 민짜로 남던 것의 처방.
        //   각 선반의 자리·폭은 여전히 불규칙 (구간 해시).
        int[] shelves = shelfPlan(ex, ez, faceH0, steps);   // 오름차순 k 목록 (빌 수 있다)
        int shelfW = shelfWidth(ex, ez);
        for (int k = 1; k <= steps; k++) {
            int x = ex + dx * k;
            int z = ez + dz * k;
            if (onOtherPad(plan, pad, x, z) || laneCovered(plan, x, z)) {
                return;
            }
            // 선반 누적 — 이 열이 몇 번째 선반 위인가 / 앞선 선반들이 몇 칸을 먹었나
            int flatten = 0;      // 지금까지 선반이 삼킨 칸수 (물매가 그만큼 늦어진다)
            int onShelf = -1;     // 이 열이 올라앉은 선반의 k (없으면 -1)
            for (int s : shelves) {
                if (k > s && k <= s + shelfW) {
                    onShelf = s;
                    break;
                }
                if (k > s + shelfW) {
                    flatten += shelfW;
                }
            }
            int top = batterTop(pad.y(), x, z, k, flatten, onShelf);
            int g = groundY(world, x, z);
            if (top <= g) {
                return;   // 산몸에 닿았다 — 여기부터는 산이 벽이다 (의도된 파묻힘)
            }
            for (int y = g + 1; y <= top; y++) {
                world.getBlockAt(x, y, z).setType(
                        rib ? rockMaterial(x, y, z)
                                : faceMaterial(x, y, z, top - y + 2), false);   // ★13b 선반 밑이 젖는다
                tally.wallFace++;
            }
            boolean shelfEdge = false;
            boolean shelfInner = false;
            for (int s : shelves) {
                if (k == s + shelfW) {
                    shelfEdge = true;
                }
                if (k == s + 1) {
                    shelfInner = true;
                }
            }
            if (shelfEdge) {
                // 선반 바깥 가장자리 — 난간 한 단 (레퍼런스의 축대 위 난간 띠)
                world.getBlockAt(x, top + 1, z).setType(Material.STONE_BRICK_WALL, false);
                tally.parapet++;
            } else if (shelfInner) {
                // ★13a-2 선반 안쪽 — 중간 스케일 채움: 화단(흙+꽃) · 이끼 · 관목 (결정론 · ~1/3)
                long sh = mix(SALT_RIB ^ 0x9A0DL, x, 0, z);
                int r = (int) Math.floorMod(sh, 100);
                if (r < 14) {
                    world.getBlockAt(x, top, z).setType(Material.COARSE_DIRT, false);
                    if (GREEN) {   // ★흙바닥은 지면이라 남고, 그 위 초목만 스위치를 탄다
                        world.getBlockAt(x, top + 1, z).setType(
                                (r & 1) == 0 ? Material.FERN : Material.SHORT_GRASS, false);
                    }
                } else if (r < 24) {
                    world.getBlockAt(x, top, z).setType(Material.MOSS_BLOCK, false);   // 이끼는 바위 결
                    if (GREEN) {
                        world.getBlockAt(x, top + 1, z).setType(Material.AZALEA, false);
                    }
                } else if (r < 30) {
                    world.getBlockAt(x, top + 1, z).setType(Material.STONE_BRICKS, false);
                    world.getBlockAt(x, top + 2, z).setType(Material.LANTERN, false);
                    tally.lanterns++;
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ★★슬라이스 16 — 이음매 닫기 (사용자 표적 「산과 건물의 조화」)
    //
    //   슬라이스 15 판정: 늑재가 산과 <b>같은 재료</b>를 쓰게 했는데도 인공 축대와 자연
    //   산면이 눈에 갈렸다. 근경 컷에서 좌측 축대(평평한 면·직선 모서리)와 우측 산면
    //   (계단진 결)이 또렷이 구분됐다 — <b>재료가 아니라 면의 기하가 달랐다.</b>
    //
    //   그래서 이번엔 <b>기하</b>를 같게 한다. 셋:
    //     ㉠ 요철  — 축대 top 에 {@link SpireField#faceRelief} 를 물린다 (산과 한 자)
    //     ㉡ 돌출  — 구간의 일부가 3~5칸 더 나간다 (튀어나온 바위 덩어리)
    //     ㉢ 홈    — 일부는 덜 나가고 낮게 물러난다 (파인 골)
    //
    //   ★검수와의 화해: 배터는 <b>패드 밖</b>이다 — 평탄 눈은 패드 안쪽 열만, 접지 눈도 패드
    //   열만 잰다. 그래서 요철이 아무리 커져도 두 눈은 흔들리지 않는다. 대신 배터 열은
    //   <b>스스로</b> 실지형까지 채워 접지하고(뜬 돌 없음), 계단·다리·접근로·남의 패드는
    //   {@code laneCovered}/{@code onOtherPad} 가드가 그대로 비킨다 — 돌출도 그 가드를
    //   물려받으므로 보행 눈도 안 흔들린다.
    // ═══════════════════════════════════════════════════════════════════

    /**
     * ★16-㉡㉢ 배터가 나가는 칸수 — 구간마다 다르다 (돌출·홈).
     *
     * <p>종전엔 면 높이만 보고 전 구간이 같은 거리로 나갔다 — 그래서 발치 선이 자로 그은
     * 듯 반듯했다. 이제 구간(~11칸) 해시가 <b>더 나가는 구간(돌출)</b>과 <b>덜 나가는
     * 구간(홈)</b>을 가른다. 순수 함수 — 조성·눈·선반 배치가 이 하나를 쓴다.
     */
    private static int batterSteps(int ex, int ez, int faceH, boolean rib) {
        int base = (rib ? 7 : 6) + (faceH >= 32 ? 6 : faceH >= 20 ? 3 : 0);
        long h = mix(SALT_RIB ^ 0x60D9EL, Math.floorDiv(ex, 11), 0, Math.floorDiv(ez, 11));
        int r = (int) Math.floorMod(h, 100);
        if (r < 20) {
            return base + 3 + (int) Math.floorMod(h >> 8, 3);   // ㉡ 돌출 — 3~5칸 더 (20%)
        }
        if (r < 42) {
            return Math.max(3, base - 2 - (int) Math.floorMod(h >> 8, 2));   // ㉢ 홈 (22%)
        }
        return base;
    }

    /**
     * ★16-㉠ 배터 한 열의 top — <b>산과 같은 자로 결을 얹는다</b>.
     *
     * <p>선반 상면은 평평해야 난간·화단이 앉으므로 결을 안 얹는다 (그 자리는 사람이 다듬은
     * 자리라는 것이 오히려 옳다). 그 밖의 면에는 {@link SpireField#faceRelief} 가 그대로
     * 물린다 — 산 표면을 파고 턱을 남긴 바로 그 식이다.
     */
    private static int batterTop(int padY, int x, int z, int k, int flatten, int onShelf) {
        if (onShelf >= 0) {
            return padY - 3 * onShelf;      // 선반 상면 — 평평하다 (난간·화단의 자리)
        }
        int base = padY - 3 * (k - flatten);
        // ★16-㉠ 흔들림 — 산의 결(faceRelief)은 7칸 셀이라 <b>축대처럼 좁은 면</b>에서는
        //   드물게 걸린다 (실측: 축대 0.087 vs 산면 0.135). 산과 같은 자를 쓰되, 열마다
        //   ±1~3 의 잔 흔들림을 더해 좁은 면에서도 결이 실제로 보이게 한다.
        long j = mix(SALT_FACE ^ 0x316E7L, x, 0, z);
        int r = (int) Math.floorMod(j, 100);
        int jitter = r < 26 ? -1 : r < 38 ? -2 : r < 44 ? -3 : r < 62 ? 1 : 0;
        return SpireField.faceRelief(base + jitter, x, z);
    }

    /**
     * 축대 면의 거칠기 — <b>물매를 뺀 잔차</b>의 평균 (16-④).
     *
     * <p>★자를 조심해서 골랐다: 이웃 열의 높이차를 그냥 재면 <b>경사를 거칠기로 오인한다</b>
     * (매끈한 배터도 한 칸마다 3씩 내려가므로 「거칠기 3」으로 나온다 — 눈이 거짓말한다).
     * 그래서 <b>결이 없었다면 있었을 높이</b>(padY − 3k)에서 얼마나 벗어났는지를 잰다 —
     * 매끈하면 정확히 0, 결이 있으면 양수다. 자연 산면도 같은 자로 잰다
     * ({@code faceRelief(h) − h}).
     *
     * @param len 표본 열 수
     */
    public static double batterRoughness(int padY, int ex, int ez, int dx, int dz, int len) {
        long sum = 0;
        int n = 0;
        for (int k = 1; k <= len; k++) {
            int x = ex + dx * k;
            int z = ez + dz * k;
            int top = batterTop(padY, x, z, k, 0, -1);
            sum += Math.abs(top - (padY - 3 * k));    // 물매를 뺀 잔차 = 결
            n++;
        }
        return n == 0 ? 0 : (double) sum / n;
    }

    /** 그 구간이 돌출인가 — 눈이 「구간마다 다르게 나간다」를 잰다 (16-㉡) */
    public static int batterStepsFor(int ex, int ez, int faceH, boolean rib) {
        return batterSteps(ex, ez, faceH, rib);
    }

    /**
     * ★선반 명세 — 구간(~9칸)마다 결정론 해시가 정한다 (13a).
     * 0 = 선반 없음 (구간의 ~45%) · 그 밖은 몇 칸째에서 선반이 나는가 (2~4).
     */
    private static int shelfDepth(int x, int z) {
        int[] p = shelfPlan(x, z, 0, 7);
        return p.length == 0 ? 0 : p[0];
    }

    /**
     * ★13c-① 선반 배치표 — 오름차순 k 목록 (빈 배열 = 선반 없음).
     * 개수 = 면 높이 ÷ 12 (20+ 최소 2 · 32+ 최소 3 · 낮은 면은 종전 확률 55%로 0~1) ·
     * 첫 자리 2~4 · 간격 2~3칸(=옹벽 6~9칸 낙차) — 자리·폭은 구간 해시로 불규칙.
     *
     * @param faceH 이 구간 옹벽 면의 높이 (0 = 모름 — 종전 단일 선반 문법)
     * @param steps 배터가 나가는 최대 칸수 (선반은 그 안에 들어야 한다)
     */
    private static int[] shelfPlan(int x, int z, int faceH, int steps) {
        long h = mix(SALT_RIB ^ 0x5EA1FL, Math.floorDiv(x, 9), 0, Math.floorDiv(z, 9));
        int r = (int) Math.floorMod(h, 100);
        int want = faceH / 12;
        if (faceH >= 32) {
            want = Math.max(want, 3);
        } else if (faceH >= 20) {
            want = Math.max(want, 2);
        } else {
            want = r < 45 ? 0 : 1;                 // 낮은 면 — 종전 확률
        }
        if (want <= 0) {
            return new int[0];
        }
        int[] out = new int[want];
        int n = 0;
        int k = 2 + (int) Math.floorMod(h >> 8, 3);   // 첫 자리 2~4
        for (int i = 0; i < want && k <= steps - 1; i++) {
            out[n++] = k;
            k += 2 + (int) Math.floorMod(h >> (12 + 4 * i), 2);   // 간격 2~3칸 (낙차 6~9)
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    /**
     * 그 구간·면 높이에서 선반이 몇 단 나는가 — 눈이 「높이 비례」 계약을 잰다 (13c).
     * ★16: 배터 칸수가 구간마다 달라졌으므로 {@link #batterSteps} 를 함께 쓴다 —
     * 자를 두 번 적으면 어긋난다 (7.5 계율).
     */
    public static int shelfCountFor(int x, int z, int faceH) {
        return shelfPlan(x, z, faceH, batterSteps(x, z, faceH, false)).length;
    }

    /** 선반 폭 2~4 (불규칙) */
    private static int shelfWidth(int x, int z) {
        return 2 + (int) Math.floorMod(
                mix(SALT_RIB ^ 0x5EA1FL, Math.floorDiv(x, 9), 1, Math.floorDiv(z, 9)) >> 4, 3);
    }

    /**
     * 그 열이 선반 상면인가 — 조경·소품(13a-2)이 자리를 묻는다. 상면 y 를 돌려준다
     * (없으면 {@link Integer#MIN_VALUE}).
     */
    public static int shelfTopAt(Pad pad, int ex, int ez, int dx, int dz, int k) {
        int shelfK = shelfDepth(ex, ez);
        int shelfW = shelfWidth(ex, ez);
        if (shelfK == 0 || k <= shelfK || k > shelfK + shelfW) {
            return Integer.MIN_VALUE;
        }
        return pad.y() - 3 * shelfK;
    }

    /**
     * 늑재 구간인가 — 자연 암반이 축대 사이로 솟는 자리 (결정론 · 난수 0).
     *
     * <p>★16-② <b>구간 길이를 불규칙하게</b> (4~14칸) + 비중 46→60%. 종전엔 7칸 자로
     * 반듯하게 갈라 「일정 간격으로 박힌 무늬」로 읽혔다 — 자연은 자를 안 쓴다. 굵은 격자
     * (23칸)가 그 안의 구간 길이를 먼저 정하고, 그 길이로 다시 가른다.
     */
    private static boolean ribSegment(int x, int z) {
        long span = mix(SALT_RIB ^ 0x11EC0L, Math.floorDiv(x, 23), 0, Math.floorDiv(z, 23));
        int len = 4 + (int) Math.floorMod(span, 11);            // 구간 길이 4~14 (불규칙)
        return Math.floorMod(mix(SALT_RIB, Math.floorDiv(x, len), 0, Math.floorDiv(z, len)),
                100) < 60;
    }

    /**
     * 자연 암반 결 — 석축이 아니라 <b>산몸이 드러난 자리</b> (늑재·돌출 바위).
     *
     * <p>★★15-② <b>산과 같은 자를 쓴다</b>: 늑재가 제 표를 따로 굴리면 산의 암질과 미묘하게
     * 달라져 「가짜 바위」로 읽힌다 — 이음매를 흐리려면 <b>같은 생성기</b>여야 한다.
     * 정본은 {@link SpireField#stone} 하나다 (산의 얼룩 문법·웜톤·석전 섞임이 그대로 온다).
     */
    private static Material rockMaterial(int x, int y, int z) {
        return SpireField.stone(x, y, z, false);
    }

    /** 패드 둘레 스커트 폭 — 가장자리에 걸린 지형 어깨를 이만큼 밀어낸다 */
    public static final int SKIRT = 2;

    /**
     * 스커트 — 패드 둘레 {@value #SKIRT}칸 띠에서 포장면 위로 솟은 지형(어깨 혹)을 걷는다.
     * 실기동 컷 4 의 병: 본전단 동편에서 지형 어깨가 여장에 바로 붙어 단을 내려다봤다.
     * 포장면 아래는 안 건드린다 — 옹벽이 딛는 비탈은 자연 그대로다.
     *
     * <p>★가드 (슬라이스 2 실기동의 병): 띠가 <b>남의 패드·계단 발자국</b>에 걸치면 그 열은
     * 건너뛴다 — 측문(16)의 스커트가 맞붙은 생활 하(5)의 가장자리 열(여장·포장·속채움)을
     * 파먹어 접지 32·평탄 6 이 났다. 남의 것은 남의 것이다.
     */
    private static void skirt(World world, Plan plan, Pad pad, Tally tally) {
        for (int x = pad.x0() - SKIRT; x <= pad.x1() + SKIRT; x++) {
            for (int z = pad.zN() - SKIRT; z <= pad.zS() + SKIRT; z++) {
                if (pad.contains(x, z) || onOtherPad(plan, pad, x, z) || laneCovered(plan, x, z)) {
                    continue;
                }
                for (int y = pad.y() + 1; y <= pad.y() + HEADROOM; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (!b.getType().isAir()) {
                        b.setType(Material.AIR, false);
                        tally.cut++;
                    }
                }
            }
        }
    }

    private static boolean onOtherPad(Plan plan, Pad self, int x, int z) {
        for (Pad p : plan.pads()) {
            if (p != self && p.contains(x, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 여장 — 네 가장자리. 계단 몸체·다리·같은 단 이웃의 개구 자리는 비운다. 모서리엔 등롱.
     * ★슬라이스 9b — 들쭉날쭉: ~5칸 구간 해시로 세 결이 섞인다 (담장 한 단 · 총안 성첩 ·
     * 겹단 성벽) — 원경 스카이라인이 자로 그은 선이기를 그친다.
     */
    private static void parapet(World world, Plan plan, Pad pad, Tally tally) {
        int y = pad.y() + 1;
        for (int x = pad.x0(); x <= pad.x1(); x++) {
            for (int z : new int[]{pad.zN(), pad.zS()}) {
                if (!laneCovered(plan, x, z) && !doorGap(plan, pad, x, z)) {
                    parapetCell(world, x, y, z, tally);
                }
            }
        }
        for (int z = pad.zN(); z <= pad.zS(); z++) {
            for (int x : new int[]{pad.x0(), pad.x1()}) {
                if (!laneCovered(plan, x, z) && !doorGap(plan, pad, x, z)) {
                    parapetCell(world, x, y, z, tally);
                }
            }
        }
        // 네 모서리 — 석전 기둥 위 등롱 (레퍼런스의 온광 점정)
        for (int cx : new int[]{pad.x0(), pad.x1()}) {
            for (int cz : new int[]{pad.zN(), pad.zS()}) {
                if (laneCovered(plan, cx, cz)) {
                    continue;
                }
                world.getBlockAt(cx, y, cz).setType(Material.STONE_BRICKS, false);
                world.getBlockAt(cx, y + 1, cz).setType(Material.LANTERN, false);
                tally.lanterns++;
            }
        }
    }

    /** 여장 한 칸 — 구간 해시(~5칸)가 결을 고른다: 담장 1단 / 총안 성첩(교대) / 겹단(2단) */
    private static void parapetCell(World world, int x, int y, int z, Tally tally) {
        int style = (int) Math.floorMod(mix(SALT_RIB ^ 0x9A57L,
                Math.floorDiv(x, 5), 0, Math.floorDiv(z, 5)), 3);
        switch (style) {
            case 1 -> {   // 총안 성첩 — 한 칸 걸러 이가 솟는다
                if (Math.floorMod(x + z, 2) == 0) {
                    world.getBlockAt(x, y, z).setType(Material.STONE_BRICKS, false);
                    world.getBlockAt(x, y + 1, z).setType(Material.STONE_BRICK_WALL, false);
                } else {
                    world.getBlockAt(x, y, z).setType(Material.STONE_BRICK_WALL, false);
                }
            }
            case 2 -> {   // 겹단 성벽 — 구간째 한 단 높다
                world.getBlockAt(x, y, z).setType(Material.STONE_BRICKS, false);
                world.getBlockAt(x, y + 1, z).setType(Material.STONE_BRICK_WALL, false);
            }
            default -> world.getBlockAt(x, y, z).setType(Material.STONE_BRICK_WALL, false);
        }
        tally.parapet++;
    }

    /**
     * ★같은 단 이웃 개구 — 통단 문법 (실측표 §5): 같은 표고의 칸이 잇닿으면 회랑 담의
     * <b>겹침 구간 중앙 7칸</b>이 문이 된다. 두 칸이 같은 중앙을 계산하므로 개구가 마주 난다.
     */
    private static boolean doorGap(Plan plan, Pad pad, int x, int z) {
        boolean xEdge = x == pad.x0() || x == pad.x1();
        int ox = xEdge ? (x == pad.x0() ? x - 1 : x + 1) : x;
        int oz = xEdge ? z : (z == pad.zN() ? z - 1 : z + 1);
        for (Pad p : plan.pads()) {
            if (p == pad || !p.contains(ox, oz) || Math.abs(p.y() - pad.y()) > 1) {
                continue;
            }
            int lo = xEdge ? Math.max(pad.zN(), p.zN()) : Math.max(pad.x0(), p.x0());
            int hi = xEdge ? Math.min(pad.zS(), p.zS()) : Math.min(pad.x1(), p.x1());
            int mid = (lo + hi) / 2;
            if (Math.abs((xEdge ? z : x) - mid) <= 3) {
                return true;
            }
        }
        return false;
    }

    private static boolean laneCovered(Plan plan, int x, int z) {
        for (StairLane lane : plan.lanes()) {
            if (lane.covers(x, z)) {
                return true;
            }
        }
        for (Bridge bridge : plan.bridges()) {
            if (bridge.covers(x, z)) {
                return true;   // 다리로 나가는 개구 — 여장·평탄 눈이 함께 비킨다
            }
        }
        Approach a = plan.approach();
        if (a != null && Math.abs(x - a.x()) <= RAIL_OFF
                && z >= a.z0() - 1 && z <= a.z0() + a.length()) {
            return true;   // ★9b — 접근로 어귀·몸체 (남단 여장이 열리고 스커트·배터가 비킨다)
        }
        return false;
    }

    /**
     * 대계단 하나를 앉힌다 — 윗패드 면에서 한 칸 계단(1:1)으로 내려가고, 램프가 끝나면
     * 아랫포장 높이의 보도가 아랫패드까지 잇는다. <b>몸체 전 열이 실지형/아랫포장까지 채워져
     * 접지한다</b> — 패드 사이가 벌어져 있어도 계단은 비탈을 딛고 선다 (뜬 계단 없음 · B-146).
     * 양옆 측석 위 여장, 네 칸마다 등롱 — 레퍼런스 1호(산길 대계단)의 열주 문법.
     */
    public static void paveStair(World world, StairLane lane, Tally tally) {
        int px = lane.dirZ() != 0 ? 1 : 0;   // 폭 방향
        int pz = lane.dirZ() != 0 ? 0 : 1;
        for (int t = 1; t <= lane.length(); t++) {
            int cx = lane.startX() + lane.dirX() * (t - 1);
            int cz = lane.startZ() + lane.dirZ() * (t - 1);
            boolean ramp = t <= lane.treads();
            int standY = ramp ? lane.topY() - t : lane.lowY();
            for (int o = -lane.half(); o <= lane.half(); o++) {
                int x = cx + px * o;
                int z = cz + pz * o;
                // ★걷기 먼저 — 노선 위로 솟은 지형(능선 혹)을 실지면까지 재서 걷어낸다.
                //   슬라이스 1 실기동이 잡은 병: 아래로는 접지하는데 위로는 안 깎아,
                //   계단이 지형 혹을 타넘었다 (보행 단차 4~8 · 상부 척추 3링크). 디딤이 정본이다.
                clearAbove(world, x, standY, z, tally);
                // 접지 채움 — 밟는 면 밑을 실지형/아랫포장까지 (뜬 계단 없음)
                fillDown(world, x, standY - 1, z, tally);
                Block top = world.getBlockAt(x, standY, z);
                if (ramp) {
                    Stairs data = (Stairs) Material.STONE_BRICK_STAIRS.createBlockData();
                    data.setFacing(ascent(lane));
                    top.setBlockData(data, false);
                    tally.stairTreads++;
                } else {
                    top.setType(Material.STONE_BRICKS, false);
                    tally.pavement++;
                }
            }
            // 측석 + 여장/등롱 — 여기도 위를 먼저 걷는다 (바위에 묻힌 난간 금지)
            for (int side : new int[]{-lane.rail(), lane.rail()}) {
                int x = cx + px * side;
                int z = cz + pz * side;
                clearAbove(world, x, standY, z, tally);
                fillDown(world, x, standY, z, tally);
                world.getBlockAt(x, standY, z).setType(Material.STONE_BRICKS, false);
                if (t % 7 == 0) {   // 석등 쌍 — 실측 6~8디딤 주기 (실측표 §1)
                    world.getBlockAt(x, standY + 1, z).setType(Material.STONE_BRICKS, false);
                    world.getBlockAt(x, standY + 2, z).setType(Material.LANTERN, false);
                    tally.lanterns++;
                } else {
                    world.getBlockAt(x, standY + 1, z).setType(Material.STONE_BRICK_WALL, false);
                    tally.parapet++;
                }
            }
        }
    }

    /**
     * 밟는 면 위를 하늘까지 연다 — 실지면({@link #groundY})이 밟는 면보다 높으면 그 위 2칸까지,
     * 아니면 머리 공간({@value #HEADROOM})까지 걷는다 (패드 걷기와 같은 결).
     */
    private static void clearAbove(World world, int x, int standY, int z, Tally tally) {
        int g = groundY(world, x, z);
        int clearTop = Math.max(g + 2, standY + HEADROOM);
        for (int y = standY + 1; y <= clearTop; y++) {
            Block b = world.getBlockAt(x, y, z);
            if (!b.getType().isAir()) {
                b.setType(Material.AIR, false);
                tally.cut++;
            }
        }
    }

    /**
     * 운무교 하나를 놓는다 — 석교 교대(양끝 2칸 · 전 열 접지) + 목교 상판(걷는 폭 3 ·
     * 가장자리 보 + 난간 울타리 · 여덟 칸마다 등롱) + 교각(돌기둥 3×3 · 전 열 접지).
     * <b>상판 아래 허공은 의도된 것</b>이다 — 그것이 현공교다 (레퍼런스 문법 · 검수는
     * 교대·교각만 잰다).
     */
    public static void paveBridge(World world, Bridge b, Tally tally) {
        java.util.List<Integer> piers = b.pierOffsets();
        for (int t = b.a0(); t <= b.a1(); t++) {
            int rel = t - b.a0();
            boolean abut = rel <= 1 || t >= b.a1() - 1;
            for (int o = -2; o <= 2; o++) {
                int x = b.alongX() ? t : b.c() + o;
                int z = b.alongX() ? b.c() + o : t;
                // 상판 위 하늘 — 벼랑 어깨가 스팬에 걸쳐 있으면 걷는다
                for (int y = b.y() + 1; y <= b.y() + 5; y++) {
                    Block blk = world.getBlockAt(x, y, z);
                    if (!blk.getType().isAir()) {
                        blk.setType(Material.AIR, false);
                        tally.cut++;
                    }
                }
                Material deck = abut ? Material.STONE_BRICKS
                        : (Math.abs(o) == 2 ? Material.SPRUCE_PLANKS : Material.DARK_OAK_PLANKS);
                world.getBlockAt(x, b.y(), z).setType(deck, false);
                tally.bridgeDeck++;
                if (abut) {
                    fillDown(world, x, b.y() - 1, z, tally);   // 교대 — 실지형까지 접지
                } else if (Math.abs(o) == 2) {
                    world.getBlockAt(x, b.y() + 1, z).setType(Material.DARK_OAK_FENCE, false);
                    tally.parapet++;
                    if (rel % 8 == 4) {
                        world.getBlockAt(x, b.y() + 2, z).setType(Material.LANTERN, false);
                        tally.lanterns++;
                    }
                }
            }
            if (piers.contains(rel)) {   // 교각 — 돌기둥 3×3, 실지형까지
                for (int to = -1; to <= 1; to++) {
                    for (int po = -1; po <= 1; po++) {
                        int x = b.alongX() ? t + to : b.c() + po;
                        int z = b.alongX() ? b.c() + po : t + to;
                        fillDown(world, x, b.y() - 1, z, tally);
                    }
                }
            }
        }
    }

    /** 오름 방향 — 계단 facing 은 오르는 쪽 (도보길과 같은 결) */
    private static BlockFace ascent(StairLane lane) {
        if (lane.dirZ() > 0) {
            return BlockFace.NORTH;
        }
        if (lane.dirZ() < 0) {
            return BlockFace.SOUTH;
        }
        return lane.dirX() > 0 ? BlockFace.WEST : BlockFace.EAST;
    }

    /**
     * (x, fromY, z) 에서 아래로, 이미 솟은 것(포장·지형)을 만날 때까지 석전으로 채운다.
     * ★식생(몸통·잎)은 지형이 아니다 — 만나면 석전으로 갈아 치우고 계속 내려간다 (11.5:
     * 잎에서 멈추면 그 밑 허공이 떠 있는 열로 남는다 — 접지 64건의 진범).
     */
    private static void fillDown(World world, int x, int fromY, int z, Tally tally) {
        int min = world.getMinHeight();
        for (int y = fromY; y > min; y--) {
            Block b = world.getBlockAt(x, y, z);
            Material m = b.getType();
            if (!m.isAir() && !VEGETATION.contains(m)) {
                return;
            }
            b.setType(Material.STONE_BRICKS, false);
            tally.core++;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 검수 — 선 것을 다시 잰다 (계획을 안 믿는다)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 검수 결과.
     *
     * @param flatViolations  패드 안쪽인데 밟는 면이 포장면이 아닌 열
     * @param floatViolations 포장면에서 내려가다 <b>허공을 만난</b> 열 (B-146 — 0 이어야 한다)
     * @param walkBreaks      계단 보행에서 단차 >1 인 걸음
     * @param checkedCols     잰 열 수
     * @param notes           위반 표본 (앞 몇 개)
     */
    public record Audit(int flatViolations, int floatViolations, int walkBreaks,
                        int checkedCols, List<String> notes) {

        public boolean clean() {
            return flatViolations == 0 && floatViolations == 0 && walkBreaks == 0;
        }
    }

    /** 조성 뒤 전수 검수 — 건물 없는 판 (슬라이스 1 호환: 발자국 예외 없음). */
    public static Audit audit(World world, Plan plan) {
        return audit(world, plan, p -> List.of());
    }

    /**
     * 조성 뒤 전수 검수 — 위반은 세고, 표본은 남긴다 (호출자가 소리친다).
     *
     * @param skipBoxes 패드 → 건물·소품 발자국 [x0,x1,zN,zS] 목록 — 평탄 눈이 그 안을
     *                  비켜 간다 (포장면 위로 솟는 것이 정상인 자리의 명세). 접지 눈은 안 비켜
     *                  간다 — 건물·소품 밑도 떠 있으면 안 된다 (B-146).
     */
    public static Audit audit(World world, Plan plan,
                              java.util.function.Function<Pad, List<int[]>> skipBoxes) {
        int flat = 0;
        int floats = 0;
        int cols = 0;
        // ★표본 쿼터 — 종류별 4건씩 (한 종류가 표본을 독식해 다른 위반의 좌표를 가리지 않게)
        List<String> flatNotes = new ArrayList<>();
        List<String> floatNotes = new ArrayList<>();
        List<String> walkNotes = new ArrayList<>();
        for (Pad pad : plan.pads()) {
            List<int[]> boxes = skipBoxes.apply(pad);
            for (int x = pad.x0(); x <= pad.x1(); x++) {
                for (int z = pad.zN(); z <= pad.zS(); z++) {
                    cols++;
                    boolean edge = x == pad.x0() || x == pad.x1() || z == pad.zN() || z == pad.zS();
                    boolean inBox = false;
                    for (int[] box : boxes) {
                        if (x >= box[0] && x <= box[1] && z >= box[2] && z <= box[3]) {
                            inBox = true;
                            break;
                        }
                    }
                    // ① 평탄 — 안쪽 열의 밟는 면은 정확히 포장면 (여장·계단 몸체·건물 자리는 예외)
                    if (!edge && !inBox && !laneCovered(plan, x, z)) {
                        int top = topSolid(world, x, pad.y() + HEADROOM, z);
                        if (top != pad.y()) {
                            flat++;
                            note(flatNotes, "평탄: " + pad.spec().name() + " (" + x + "," + z
                                    + ") 밟는 면 y" + top + " ≠ 포장 y" + pad.y());
                        }
                    }
                    // ② 접지 — 포장면에서 내려가며 허공을 만나면 그 열이 떠 있다 (B-146)
                    int min = world.getMinHeight();
                    for (int y = pad.y(); y > min; y--) {
                        Material m = world.getBlockAt(x, y, z).getType();
                        if (m.isAir()) {
                            floats++;
                            note(floatNotes, "접지: " + pad.spec().name() + " (" + x + "," + z
                                    + ") y" + y + " 허공 — 열이 떠 있다");
                            break;
                        }
                        if (TrailBuilder.groundSolid(m) && !placedMasonry(m)) {
                            break;   // 자연 지반에 닿았다 — 이 열은 접지
                        }
                    }
                }
            }
        }
        // ③ 계단 보행 — 링크마다 윗패드 두 칸 앞에서 아랫패드 두 칸 안까지 한 칸 계단 원칙
        int breaks = 0;
        for (StairLane lane : plan.lanes()) {
            int prev = Integer.MIN_VALUE;
            for (int t = -2; t <= lane.length() + 2; t++) {
                int x = lane.startX() + lane.dirX() * (t - 1);
                int z = lane.startZ() + lane.dirZ() * (t - 1);
                // ★걷는 자의 눈 — 다음 발판은 「이전 발판 +2」에서 내려 찾는다. 하늘에서 찾으면
                //   문루·처마의 지붕을 발판으로 오독한다 (2.6 실기동: 측문 문루 처마가 단차 8 로 짖음 —
                //   보행자는 그 밑을 지나간다). 머리 높이(+2)에 막힌 것은 여전히 단차로 잡힌다.
                int stand = prev == Integer.MIN_VALUE
                        ? topSolid(world, x, lane.topY() + 2, z)
                        : topSolid(world, x, prev + 2, z);
                if (prev != Integer.MIN_VALUE && Math.abs(stand - prev) > 1) {
                    breaks++;
                    note(walkNotes, "보행: 계단 " + lane.link().upperZone() + "→" + lane.link().lowerZone()
                            + " (" + x + "," + z + ") 단차 " + Math.abs(stand - prev)
                            + " (y" + prev + "→y" + stand + ")");
                }
                prev = stand;
            }
        }
        // ④ 다리 — 상판 보행(걷는 자의 눈) + 교대·교각 접지. ★상판 아래 허공은 의도된 것이라
        //   안 잰다 — 다리 명세에 등록된 구간만의 예외다 (등록 밖 허공은 여전히 ②가 잡는다).
        for (Bridge b : plan.bridges()) {
            int prev = Integer.MIN_VALUE;
            for (int t = b.a0() - 2; t <= b.a1() + 2; t++) {
                int x = b.alongX() ? t : b.c();
                int z = b.alongX() ? b.c() : t;
                int stand = prev == Integer.MIN_VALUE
                        ? topSolid(world, x, b.y() + 2, z)
                        : topSolid(world, x, prev + 2, z);
                if (prev != Integer.MIN_VALUE && Math.abs(stand - prev) > 1) {
                    breaks++;
                    note(walkNotes, "보행: 다리 " + b.spec().name() + " (" + x + "," + z + ") 단차 "
                            + Math.abs(stand - prev) + " (y" + prev + "→y" + stand + ")");
                }
                prev = stand;
            }
            java.util.List<Integer> piers = b.pierOffsets();
            for (int t = b.a0(); t <= b.a1(); t++) {
                int rel = t - b.a0();
                boolean abut = rel <= 1 || t >= b.a1() - 1;
                boolean pier = piers.contains(rel);
                if (!abut && !pier) {
                    continue;
                }
                int half = pier ? 1 : 2;
                for (int o = -half; o <= half; o++) {
                    int x = b.alongX() ? t : b.c() + o;
                    int z = b.alongX() ? b.c() + o : t;
                    cols++;
                    int min = world.getMinHeight();
                    for (int y = b.y(); y > min; y--) {
                        Material m = world.getBlockAt(x, y, z).getType();
                        if (m.isAir()) {
                            floats++;
                            note(floatNotes, "접지: 다리 " + b.spec().name() + " "
                                    + (pier ? "교각" : "교대") + " (" + x + "," + z + ") y" + y
                                    + " 허공 — 기둥이 떠 있다");
                            break;
                        }
                        if (TrailBuilder.groundSolid(m) && !placedMasonry(m)) {
                            break;
                        }
                    }
                }
            }
        }
        // ⑤ 접근로 (슬라이스 9b) — 보행(걷는 자의 눈) + 전 열 접지 (대계단 문법 재사용)
        //   ★2026-08-05 반 칸 하강: 눈도 <b>반 단위</b>로 잰다. 정수 y 로만 재면 반 칸 챌면을
        //   0 으로 세어 「매끈하다」고 거짓말한다 — 조성과 눈이 같은 정의를 써야 한다.
        Approach a = plan.approach();
        if (a != null) {
            int prevH = Integer.MIN_VALUE;
            for (int i = -2; i < a.length(); i++) {
                int z = a.z0() + i;
                int from = (prevH == Integer.MIN_VALUE ? a.blockYAt(0) : Approach.blockY(prevH)) + 2;
                int stand = topSolid(world, a.x(), from, z);
                int h = surfaceHalf(world, a.x(), stand, z);
                if (prevH != Integer.MIN_VALUE && Math.abs(h - prevH) > 2) {   // 반 단위 → 한 칸 = 2
                    breaks++;
                    note(walkNotes, "보행: 접근로 (" + a.x() + "," + z + ") 단차 "
                            + (Math.abs(h - prevH) / 2.0) + "칸 (y" + (prevH / 2.0)
                            + "→y" + (h / 2.0) + ")");
                }
                prevH = h;
            }
            for (int i = 0; i < a.length(); i++) {          // 계획한 반 칸이 실제로 반 칸인가
                int stand = topSolid(world, a.x(), a.blockYAt(i) + 2, a.z0() + i);
                int got = surfaceHalf(world, a.x(), stand, a.z0() + i);
                if (got != a.hs()[i]) {
                    breaks++;
                    note(walkNotes, "보행: 접근로 (" + a.x() + "," + (a.z0() + i)
                            + ") 상면 y" + (got / 2.0) + " ≠ 계획 y" + (a.hs()[i] / 2.0)
                            + (Approach.isSlab(a.hs()[i]) ? " (반 칸이어야 한다)" : ""));
                }
            }
            int min = world.getMinHeight();
            for (int i = 0; i < a.length(); i++) {
                for (int o = -RAIL_OFF; o <= RAIL_OFF; o++) {
                    cols++;
                    int x = a.x() + o;
                    for (int y = a.blockYAt(i); y > min; y--) {
                        Material m = world.getBlockAt(x, y, a.z0() + i).getType();
                        if (m.isAir()) {
                            floats++;
                            note(floatNotes, "접지: 접근로 (" + x + "," + (a.z0() + i) + ") y" + y
                                    + " 허공 — 열이 떠 있다");
                            break;
                        }
                        if (TrailBuilder.groundSolid(m) && !placedMasonry(m)) {
                            break;
                        }
                    }
                }
            }
        }
        List<String> notes = new ArrayList<>(flatNotes);
        notes.addAll(floatNotes);
        notes.addAll(walkNotes);
        return new Audit(flat, floats, breaks, cols, List.copyOf(notes));
    }

    private static void note(List<String> notes, String s) {
        if (notes.size() < 4) {   // 종류별 쿼터 — 셋을 합쳐 최대 12건이 표본이 된다
            notes.add(s);
        }
    }

    /**
     * 이 기계가 놓았을 수 있는 석재인가 — 접지 검수의 하강이 <b>어디서 멈출지</b>를 가른다
     * (석재를 지나 자연 지반에 닿으면 접지, 도중에 허공이면 부양). {@code STONE}·{@code TUFF}
     * 는 자연에도 있지만 상관없다 — 판정 기준은 「허공을 만나느냐」이지 「누가 놓았느냐」가
     * 아니다 (자연 돌을 더 지나 내려가도 결론은 같다 · 산세 시험 월드는 속이 꽉 찬 조성이다).
     */
    private static boolean placedMasonry(Material m) {
        return m == Material.STONE || m == Material.STONE_BRICKS
                || m == Material.CRACKED_STONE_BRICKS || m == Material.MOSSY_STONE_BRICKS
                || m == Material.ANDESITE || m == Material.POLISHED_ANDESITE
                || m == Material.TUFF;
    }

    private static int topSolid(World world, int x, int fromY, int z) {
        int y = fromY;
        int min = world.getMinHeight();
        while (y > min && world.getBlockAt(x, y, z).getType().isAir()) {
            y--;
        }
        return y;
    }

    /**
     * 그 열의 <b>실제 상면을 반 단위로</b> 읽는다 — 하단 반블록이면 홀수(반 칸), 그 밖은 짝수.
     * {@link Approach#blockY(int)} 의 짝이다: 조성이 쓴 정의와 눈이 쓰는 정의가 같아야
     * 반 칸 계단을 「매끈하다」고 오독하지 않는다 (2026-08-05).
     */
    private static int surfaceHalf(World world, int x, int blockY, int z) {
        org.bukkit.block.data.BlockData d = world.getBlockAt(x, blockY, z).getBlockData();
        if (d instanceof Slab s && s.getType() == Slab.Type.BOTTOM) {
            return 2 * blockY + 1;
        }
        return 2 * (blockY + 1);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 팔레트 — 결정론 섞음 (난수 없음 · 재실행 멱등)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 이 기계가 쓰는 재료 전부 — 눈이 금지 재료(B-195: barrel·light)를 이 표로 잰다.
     *
     * <p>★<b>신고표는 실물보다 넓으면 안 된다</b> (2026-08-05). 전에는 여기에 점적석·매끈 사암·
     * 금 간 석전·이끼 석전·조약돌·이끼 조약돌이 있었는데, 팔레트를 줄인 뒤에도 <b>표에만</b>
     * 남아 있었다. 그러면 「축대 점적석이 표에 있다」 같은 눈이 <b>실물은 안 보고 표만 보고</b>
     * 통과한다 — 폐기된 처방을 눈이 계속 지키게 된다. 눈({@code ★신고표가 실물을 덮는다})이
     * 이제 양쪽을 맞춘다.
     */
    public static Set<Material> palette() {
        return EnumSet.of(
                Material.STONE, Material.STONE_BRICKS,                    // ★암벽 몸 · 석축/포장
                Material.TUFF,                                            // ★석축 층대 띠 (구조)
                Material.POLISHED_ANDESITE,                               //   기초 켜
                Material.STONE_BRICK_STAIRS, Material.STONE_BRICK_WALL,
                Material.DARK_OAK_PLANKS, Material.DEEPSLATE_TILE_SLAB,   // ★9b — 소문 보·갓
                Material.SPRUCE_WOOD, Material.SPRUCE_LEAVES,             // ★9b — 접근로 소나무
                Material.AZALEA_LEAVES, Material.FLOWERING_AZALEA_LEAVES, // ★12.6 잎 톤
                Material.COARSE_DIRT, Material.FERN, Material.SHORT_GRASS,
                Material.MOSS_BLOCK, Material.AZALEA,                     // ★13a-2 선반 화단
                Material.GLOWSTONE,                                       // ★D-22 석등 (등롱·갓)
                Material.DARK_OAK_FENCE, Material.DARK_OAK_LOG,           // ★D-21 깃대 (기둥·가로대·마디)
                Material.BLACK_WALL_BANNER,                               //   짙은 배너 (무지 · 실측 최근접)
                Material.LANTERN, Material.AIR);
    }

    /**
     * 옹벽(축대) 결 — ★13a-3 구조별 분화: 축대는 <b>거칠고 따뜻하게</b> (기단·포장과 갈린다).
     * 층대 띠(4단마다)는 응회암, 몸은 석전 바탕에 응회암·점적석(웜톤)·균열·이끼가 섞인다 —
     * 산의 웜톤(SpireField.stone)과 같은 계열이 되게.
     */
    public static Material faceMaterial(int x, int y, int z) {
        return faceMaterial(x, y, z, Integer.MAX_VALUE);
    }

    /**
     * 옹벽 결 — ★13b-① <b>원인 있는 풍화</b>: 점적석·이끼가 고른 산점(무늬)이 아니라
     * <b>뭉치</b>로 나고, <b>아래쪽·모서리·선반 밑</b>에 몰린다 (물이 흐르고 이끼가 끼는 자리).
     * 뭉치 = 3칸 격자 셀 해시로 「젖은 셀」을 뽑고 그 안에서 2~4칸으로 자란다.
     *
     * @param below 그 열이 선반·상단에서 몇 칸 아래인가 (클수록 아래 — 젖음이 는다).
     *              {@link Integer#MAX_VALUE} = 모름 (기본 결)
     */
    /**
     * 석축 면 — <b>★두 재료</b>: 석전 한 장 + 네 켜마다 도는 응회암 층대 띠.
     * 사용자 확정 (2026-08-05) 「블록 수를 줄여 일관성을 높인다」.
     *
     * <p>★전에는 일곱이었다 (점적석·이끼 석전·이끼 조약돌·응회암·석전·안산암·금 간 석전 —
     * 「젖은 셀」과 「마른 면」으로 갈라 굴렸다). 목표 사진의 석축을 색 무리로 가르면 네 무리가
     * 나오지만 <b>색도가 넷 다 같고 밝기만 다르다</b> (54 · 93 · 131 · 182). 한 재료다.
     * 우리가 일곱으로 낸 것은 목표에 없는 잡티였다 (D-42 「석축이 헐어 보인다」).
     *
     * <p>남긴 띠는 잡티가 아니라 <b>구조</b>다 — 목표 석축에도 네 켜마다 가로 결이 돈다.
     * 「무엇을 남기는가」의 자: <b>위치가 정하면 구조, 해시가 정하면 잡티다.</b>
     */
    public static Material faceMaterial(int x, int y, int z, int below) {
        if (y % 4 == 0) {
            return Material.TUFF;       // 층대 띠 — 위치가 정한다 (구조)
        }
        return Material.STONE_BRICKS;
    }

    /**
     * 포장 결 — ★13a-3: 광장 바닥은 <b>따뜻한 베이지</b>를 섞는다 (축대의 거친 회갈과 갈린다).
     * 박석(연마 안산암) 바탕 + 매끈 사암·석전.
     */
    /**
     * 월대·마당 포장 — <b>★단일 재료</b>. 사용자 확정 (2026-08-05).
     *
     * <p>★전에는 다섯이었다 (연마 안산암 42 / 석전 20 / 매끈 사암 18 / 안산암 12 / 금 간 석전 8).
     * 사암 18%가 <b>누런 바둑판</b>으로 읽혔다 (D-43). 「베이지 — 산의 웜톤과 한 계열」이라 적어
     * 넣은 것인데, 목표의 온기는 재료가 아니라 <b>햇빛</b>이었다 (밝기와 색온도의 상관 +0.94 —
     * 같은 면 안에서도 밝은 무리일수록 따뜻하다). 웜톤을 재료로 흉내 내려던 것이 오진이었다.
     */
    public static Material paveMaterial(int x, int z) {
        return Material.STONE_BRICKS;
    }

    private static long mix(long salt, int x, int y, int z) {
        long h = salt ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL)
                ^ (z * 0x165667B19E3779F9L);
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return h;
    }
}
