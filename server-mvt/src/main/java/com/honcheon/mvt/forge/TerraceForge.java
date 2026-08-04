package com.honcheon.mvt.forge;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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
     * ★8.6: 80→160 — 산체 재척도로 협곡이 넓어졌고 (곁봉이 밀려남), 긴 스팬은 교각(24칸마다)이
     * 받친다. 실측 9호의 현공교도 다주(多柱) 문법이다.
     */
    public static final int MAX_BRIDGE_SPAN = 160;

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
                // ═══ 통단(帶) 7대 — 실측표 §2·§5 (슬라이스 8 재척도 ×2).
                //     ★사용자 기준자 (2026-08-03): 대계단 20 — 옛 실측이 절반 오측이라 평면 치수
                //     전부 ×2 (지형 앵커인 곁봉 3 은 위치 유지·×1.5). h 사슬 46/64/86/108/130/146/170
                //     — 옹벽 실측 12~24 (§1 재실측) · 전 낙차 ≤ MAX_STAIR_DY.
                //     계약 Δ 는 옛 실기동 p85 (h−Δ 로 보존) 대비 새 h 로 재산출 — 재판독 실측이
                //     오면 그 수치로 갱신하라 (발자국이 넓어져 가장자리 p85 가 다소 흐른다).
                // ★슬라이스 9b — 단구 표고 분할 (코덱스 개선 1 완성): 같은 통단 안 칸을 ±4 로
                //   갈라 원경 옹벽 스카이라인이 들쭉해진다. 척추 칸(1·2·6·101·9)은 앵커 그대로 —
                //   로브만 ±4. 계약 Δ 는 h 이동분만큼 동행 (p85 는 지형이라 불변).
                // ── B1 산문단 (산문 46 · 창고 42) ──
                new PadSpec(1, "산문", -4, 356, 120, 44, 46, 16),          // 실측 p85 -31 → Δ16
                new PadSpec(17, "물자 창고", 90, 356, 68, 44, 42, 31),     // 9b: 46→42 · Δ35→31
                // ── B2 외원단 (연무장하 68 · 외원 64 · 생활하 60 · 측문 60) ──
                new PadSpec(3, "연무장 하", -80, 296, 88, 64, 68, 40),     // 9b: 64→68 · Δ36→40 (성곽 서벽)
                new PadSpec(2, "외원 광장", -4, 296, 64, 64, 64),
                new PadSpec(5, "생활 하", 60, 296, 64, 64, 60, 13),        // 9b: 64→60 · Δ17→13
                new PadSpec(16, "측문", 100, 296, 16, 32, 60, 60),         // 9b: 64→60 · Δ64→60 (동벽 단애)
                // ── B3 중단 (강당 90 · 종문 86 · 훈련장 82) ──
                new PadSpec(4, "강당·무기고", -76, 228, 80, 60, 90, 40),   // 9b: 86→90 · Δ36→40
                new PadSpec(6, "종문 중정", -4, 228, 64, 60, 86),          // 창 안
                new PadSpec(7, "훈련장 중", 60, 228, 64, 60, 82, 10),      // 9b: 86→82 · Δ14→10
                // ── B4 상단 (연무장상 112 · 중정 108 · 생활중 104) ──
                new PadSpec(14, "연무장 상", -74, 164, 80, 56, 112, 43),   // 9b: 108→112 · Δ39→43
                new PadSpec(101, "중정", -4, 164, 60, 56, 108),            // 창 안
                new PadSpec(8, "생활 중", 58, 164, 64, 56, 104, 8),        // 9b: 108→104 · Δ12→8
                // ── B5 본전단 (정원 134 · 본전 130 · 망루 126) ──
                new PadSpec(10, "장문인 정원", -76, 100, 64, 64, 134, 36), // 9b: 130→134 · Δ32→36
                new PadSpec(9, "본전", 0, 100, 88, 64, 130),               // ★실측 Δ0 — 창룡령 정렬, 계약 불요
                new PadSpec(11, "망루", 70, 100, 52, 64, 126, 38),         // 9b: 130→126 · Δ42→38
                // ── B6 장로단 h146 ──
                new PadSpec(12, "장로회", -4, 36, 88, 48, 146),            // ★실측 Δ1 — 계약 불요
                // ── B7 정상단 h170 ──
                new PadSpec(13, "정상 암자", -4, -20, 52, 36, 170),        // ★실측 Δ-5 — 계약 불요
                // ── B1 로브 실측 반영 ──
                // (산문 17 은 아래 B1 절에서)
                // ── 곁봉 — ★8.6: 산체 재척도로 옛 어깨가 밀려남 → 새 Es·Wm 어깨로 재배치 ──
                new PadSpec(19, "절벽 전망대", 190, 20, 22, 18, 146, -14), // ★8.7 실측 Δ-14 — 새 Es 어깨가 상판보다 높다 (의도된 깎기)
                new PadSpec(20, "부속 암자", 62, -14, 26, 20, 170, 42),    // 실측 창 안 유지
                new PadSpec(105, "서교 착지", -180, 16, 18, 18, 146));     // ★8.7 실측 Δ0 — 새 Wm 어깨가 정확한 자리 (계약 불요)
        List<StairLink> links = List.of(
                // 척추 대계단 — 전폭 21 (사용자 기준자)
                new StairLink(2, 1, 'S'),      // 낙차 18
                new StairLink(6, 2, 'S'),      // 낙차 22
                new StairLink(101, 6, 'S'),    // 낙차 22
                new StairLink(9, 101, 'S'),    // 낙차 22
                new StairLink(12, 9, 'S'),     // 낙차 16
                new StairLink(13, 12, 'S'),    // 낙차 24
                // ★9b 소계단 (전폭 5) — 갈린 단구 사이. Δ≤1 칸(5↔16)만 여장 개구가 잇는다.
                new StairLink(1, 17, 'E', 1),
                new StairLink(3, 2, 'E', 1),
                new StairLink(2, 5, 'E', 1),
                new StairLink(4, 6, 'E', 1),
                new StairLink(6, 7, 'E', 1),
                new StairLink(14, 101, 'E', 1),
                new StairLink(101, 8, 'E', 1),
                new StairLink(10, 9, 'E', 1, -24),   // off -24 — 본전 월대(남쪽)를 비켜 건넌다
                new StairLink(9, 11, 'E', 1, -24));
        List<BridgeSpec> bridges = List.of(
                // ★8.6 — 곁봉 재배치로 스팬이 길어졌다 (139·123) — 교각 24칸마다 (MAX 160 안)
                new BridgeSpec("운무교 동일", true, 20, 40, 178, 146),   // 장로회(x1 39) ↔ 전망대(x0 179)
                new BridgeSpec("운무교 동이", true, -12, 22, 48, 170),   // 정상단(x1 21) ↔ 부속 암자(x0 49)
                new BridgeSpec("운무교 서", true, 20, -171, -49, 146));  // 서교 착지(x1 -172) ↔ 장로회(x0 -48)
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
     * 26칸마다 참 · 소문 마당), 조성·검수가 같은 표를 읽는다.
     *
     * @param x  축선 x (산문 중심열)
     * @param z0 첫 행 (산문단 남단 + 1)
     * @param ys 행별 보행면 y
     */
    public record Approach(int x, int z0, int[] ys) {

        public int length() {
            return ys.length;
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

    /** 접근로 길이 — 산문단 남단에서 남쪽으로 (기슭 언덕을 넘어 평지까지) */
    public static final int APPROACH_LEN = 176;

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
     * 접근로 계획 — 산문단(1구역) 남단에서 남쪽으로, <b>조성 전 지형을 따라</b> 한 칸 물매의
     * 보행면 표를 만든다. 26칸마다 참(평탄 2칸 — 석등 쌍이 선다) · i 96~108 은 소문 마당
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
        int ax = gate.cx();
        int z0 = gate.zS() + 1;
        int[] ys = new int[APPROACH_LEN];
        int prev = gate.y();
        for (int i = 0; i < APPROACH_LEN; i++) {
            boolean landing = i % 26 < 2 || (i >= 96 && i <= 108);
            int g = groundY(world, ax, z0 + i);
            int target = landing ? prev
                    : prev + Integer.compare(g, prev);   // 지형을 따르되 한 칸 물매 (걷는 자의 계약)
            ys[i] = target;
            prev = target;
        }
        return new Approach(ax, z0, ys);
    }

    /**
     * 접근 시퀀스를 놓는다 (슬라이스 9b) — 절벽 아래에서 산문까지 <b>도착하는 과정</b>:
     * 20폭 대계단(지형 추종·전 열 접지) → 참(석등 쌍) → 소문(작은 문루 — 기슭 언덕 마루) →
     * 비석·소나무 → 산문. 계단·측석·석등은 대계단 문법 그대로.
     */
    public static void paveApproach(World world, Plan plan, Tally tally) {
        Approach a = plan.approach();
        if (a == null) {
            return;
        }
        int prevY = a.ys()[0];
        for (int i = 0; i < a.length(); i++) {
            int z = a.z0() + i;
            int y = a.ys()[i];
            for (int o = -STAIR_HALF; o <= STAIR_HALF; o++) {
                int x = a.x() + o;
                clearAbove(world, x, y, z, tally);
                fillDown(world, x, y - 1, z, tally);
                Block top = world.getBlockAt(x, y, z);
                if (y != prevY) {
                    Stairs data = (Stairs) Material.STONE_BRICK_STAIRS.createBlockData();
                    data.setFacing(y < prevY ? BlockFace.NORTH : BlockFace.SOUTH);   // 오름을 향한다
                    top.setBlockData(data, false);
                    tally.stairTreads++;
                } else {
                    top.setType(paveMaterial(x, z), false);
                    tally.pavement++;
                }
            }
            for (int side : new int[]{-RAIL_OFF, RAIL_OFF}) {
                int x = a.x() + side;
                clearAbove(world, x, y, z, tally);
                fillDown(world, x, y, z, tally);
                world.getBlockAt(x, y, z).setType(Material.STONE_BRICKS, false);
                if (i % 26 < 2 || i % 13 == 6) {   // 참 석등 쌍 + 중간 등롱 리듬
                    world.getBlockAt(x, y + 1, z).setType(Material.STONE_BRICKS, false);
                    world.getBlockAt(x, y + 2, z).setType(Material.LANTERN, false);
                    tally.lanterns++;
                } else {
                    world.getBlockAt(x, y + 1, z).setType(Material.STONE_BRICK_WALL, false);
                    tally.parapet++;
                }
            }
            if (i == 104) {
                // 소문 — 기슭 언덕 마루 (중심 z0+102). ★i104 에 세운다: 지붕이 z±2 를 덮으므로
                // 그 행들의 조성(clearAbove)이 끝난 뒤여야 한다 (마당 96~108 평탄이라 y 동일)
                approachGate(world, a.x(), a.ys()[102], a.z0() + 102, tally);
            }
            if (i == 27 || i == 150) {
                stele(world, a.x() + RAIL_OFF + 3, z, tally);   // 비석 — 참 곁 (계단 곁에 선다)
                stele(world, a.x() - RAIL_OFF - 3, z, tally);
            }
            if (i % 20 == 15) {
                // ★계단 회랑(±APPROACH_CLEAR) 밖에 선다 — 계단을 덮지 않고 곁을 채운다 (D-16).
                //   옛 값 RAIL_OFF+5 는 전폭 21 계단의 것이라, 폭 7 로 줄자 계단 위에 앉았다.
                int off = APPROACH_CLEAR + 2;
                approachPine(world, a.x() + (Math.floorMod(i, 40) == 15 ? off : -off), z, tally);
            }
            prevY = y;
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
            if (Math.abs(ps.expectedLift()) > 64) {
                // ★한도 40→56(슬라이스 8)→64(8.7): 측문 동벽의 실측 Δ64 — B2 동단이 산 발치 밖
                //   평지 위 성곽 벼랑이다 (이미지 12 단애 문법 · 벼랑 아래 접속은 후속 도보길 몫).
                //   실측값 계약은 은폐가 아니다 — 근거 없는 추정만이 만용이다.
                throw new IllegalArgumentException("expectedLift 한도(±64) 밖: " + ps.name()
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
                    world.getBlockAt(x, top + 1, z).setType(
                            (r & 1) == 0 ? Material.FERN : Material.SHORT_GRASS, false);
                } else if (r < 24) {
                    world.getBlockAt(x, top, z).setType(Material.MOSS_BLOCK, false);
                    world.getBlockAt(x, top + 1, z).setType(Material.AZALEA, false);
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
        Approach a = plan.approach();
        if (a != null) {
            int prev = Integer.MIN_VALUE;
            for (int i = -2; i < a.length(); i++) {
                int z = a.z0() + i;
                int stand = prev == Integer.MIN_VALUE
                        ? topSolid(world, a.x(), a.ys()[0] + 2, z)
                        : topSolid(world, a.x(), prev + 2, z);
                if (prev != Integer.MIN_VALUE && Math.abs(stand - prev) > 1) {
                    breaks++;
                    note(walkNotes, "보행: 접근로 (" + a.x() + "," + z + ") 단차 "
                            + Math.abs(stand - prev) + " (y" + prev + "→y" + stand + ")");
                }
                prev = stand;
            }
            int min = world.getMinHeight();
            for (int i = 0; i < a.length(); i++) {
                for (int o = -RAIL_OFF; o <= RAIL_OFF; o++) {
                    cols++;
                    int x = a.x() + o;
                    for (int y = a.ys()[i]; y > min; y--) {
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

    // ═══════════════════════════════════════════════════════════════════
    // 팔레트 — 결정론 섞음 (난수 없음 · 재실행 멱등)
    // ═══════════════════════════════════════════════════════════════════

    /** 이 기계가 쓰는 재료 전부 — 눈이 금지 재료(B-195: barrel·light)를 이 표로 잰다. */
    public static Set<Material> palette() {
        return EnumSet.of(
                Material.STONE, Material.STONE_BRICKS, Material.CRACKED_STONE_BRICKS,
                Material.MOSSY_STONE_BRICKS, Material.ANDESITE, Material.POLISHED_ANDESITE,
                Material.TUFF, Material.STONE_BRICK_STAIRS, Material.STONE_BRICK_WALL,
                Material.COBBLESTONE, Material.MOSSY_COBBLESTONE,   // ★슬라이스 9 — 암반 늑재
                Material.DARK_OAK_PLANKS, Material.DEEPSLATE_TILE_SLAB,   // ★9b — 소문 보·갓
                Material.SPRUCE_WOOD, Material.SPRUCE_LEAVES,             // ★9b — 접근로 소나무
                Material.AZALEA_LEAVES, Material.FLOWERING_AZALEA_LEAVES, // ★12.6 잎 톤
                Material.DRIPSTONE_BLOCK, Material.SMOOTH_SANDSTONE,      // ★13a-3 웜톤 분화
                Material.COARSE_DIRT, Material.FERN, Material.SHORT_GRASS,
                Material.MOSS_BLOCK, Material.AZALEA,                     // ★13a-2 선반 화단
                Material.LANTERN, Material.AIR);
    }

    /**
     * 옹벽(축대) 결 — ★13a-3 구조별 분화: 축대는 <b>거칠고 따뜻하게</b> (기단·포장과 갈린다).
     * 층대 띠(4단마다)는 응회암, 몸은 석전 바탕에 응회암·점적석(웜톤)·균열·이끼가 섞인다 —
     * 산의 웜톤(SpireField.stone)과 같은 계열이 되게.
     */
    private static Material faceMaterial(int x, int y, int z) {
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
    private static Material faceMaterial(int x, int y, int z, int below) {
        if (y % 4 == 0) {
            return Material.TUFF;       // 층대 띠 — 레퍼런스 석축의 가로 결 (웜톤으로 교체)
        }
        // ① 젖은 셀 — 3칸 뭉치 격자. 아래쪽일수록 셀이 젖을 확률이 는다 (물이 흘러내린다)
        int wetBias = below == Integer.MAX_VALUE ? 12 : Math.min(34, 6 + below * 3);
        boolean wetCell = Math.floorMod(mix(SALT_FACE ^ 0x5DE7L,
                Math.floorDiv(x, 3), Math.floorDiv(y, 3), Math.floorDiv(z, 3)), 100) < wetBias;
        int r = (int) Math.floorMod(mix(SALT_FACE, x, y, z), 100);
        if (wetCell) {
            // 뭉치 안 — 젖은 결이 이어진다 (점이 아니라 얼룩)
            if (r < 46) {
                return Material.DRIPSTONE_BLOCK;
            }
            if (r < 72) {
                return Material.MOSSY_STONE_BRICKS;
            }
            if (r < 84) {
                return Material.MOSSY_COBBLESTONE;
            }
            return Material.TUFF;
        }
        // 마른 면 — 석전·응회암·안산암만 (점적석·이끼 없음: 무늬가 안 생긴다)
        if (r < 56) {
            return Material.STONE_BRICKS;
        }
        if (r < 76) {
            return Material.TUFF;
        }
        if (r < 90) {
            return Material.ANDESITE;
        }
        return Material.CRACKED_STONE_BRICKS;
    }

    /**
     * 포장 결 — ★13a-3: 광장 바닥은 <b>따뜻한 베이지</b>를 섞는다 (축대의 거친 회갈과 갈린다).
     * 박석(연마 안산암) 바탕 + 매끈 사암·석전.
     */
    private static Material paveMaterial(int x, int z) {
        int r = (int) Math.floorMod(mix(SALT_PAVE, x, 0, z), 100);
        if (r < 42) {
            return Material.POLISHED_ANDESITE;
        }
        if (r < 62) {
            return Material.STONE_BRICKS;
        }
        if (r < 80) {
            return Material.SMOOTH_SANDSTONE;   // 베이지 — 산의 웜톤과 한 계열
        }
        if (r < 92) {
            return Material.ANDESITE;
        }
        return Material.CRACKED_STONE_BRICKS;
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
