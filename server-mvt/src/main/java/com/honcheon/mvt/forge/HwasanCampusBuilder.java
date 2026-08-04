package com.honcheon.mvt.forge;

import org.bukkit.Material;
import org.bukkit.World;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 화산 캠퍼스 구역 배치기 — <b>테라스 패드 위에 마스터플랜 구역의 건물을 앉힌다</b> (슬라이스 2).
 *
 * <p>설계 정본: {@code docs/design/hwasan_build_enhancement_v1.md} §2(20구역 마스터플랜)·§3.
 * 부품 문법은 {@code SectBuilder}(도관 원형 — plasterHall·tiledRoof·mountainGate·pagoda)의 것을
 * 잇는다 — 그 부품들은 private + SiteSpec(프로덕션 지형 계약) 결합이라 직접 부르지 못하고,
 * <b>같은 치수·재료·실루엣을 패드 좌표계로 옮겨 왔다</b> (백벽 회반죽 · 침엽 기둥 · 흑기와
 * 모임지붕 · 세로 창). 재료는 사용자 블록 표(hwasan_campus_architecture.md — White Terracotta ·
 * Spruce/Dark Oak · Deepslate Tile)를 따른다.
 *
 * <p>★조닝 3색 (시안 §2 — 마스터플랜의 색이 재료가 된다):
 * <ul>
 *   <li><b>핵심 전각(적)</b> — 본전(9)만 적벽: 적목 기둥({@code MANGROVE_LOG})·적 띠
 *       ({@code RED_TERRACOTTA}) 【제안 — 사용자 블록 표에 적색 계열이 없어 골랐다. 빨간펜 대상】</li>
 *   <li><b>수련 공간(청→절제)</b> — 회벽·흑기와, 장식 없음</li>
 *   <li><b>공공 공간(모래빛)</b> — 바닥에 모래빛 박석({@code SANDSTONE} 계열)을 섞는다</li>
 * </ul>
 *
 * <p>★계율: 건물은 <b>패드 위에만</b> 선다 — 모든 블록은 {@link #put} 을 지나며, 패드 밖이면
 * 그 자리에서 던진다 (B-146 의 연장: 패드가 접지를 보증하니 패드 안이면 뜰 수 없다).
 * 순수 검증 {@link #validateBuildings} 가 조성 전에 발자국을 재고, 검수
 * {@link #auditBuildings} 가 조성 뒤 패드 밖 유출을 세계에서 다시 잰다.
 * 현판 글자·이름 없음 (작명은 사용자 몫 — 현판 자리는 빈 판으로 둔다).
 * {@code BARREL}(B-195)·{@code LIGHT} 금지 — 상자는 {@code CHEST}.
 */
public final class HwasanCampusBuilder {

    private HwasanCampusBuilder() {
    }

    /** 조성 대장 — census 가 읽는다 */
    public static final class Tally {
        public long blocks;
        public int halls;
        public int gates;
        public int pavilions;
        public int dummies;
        public int racks;
        public int towers;
        public int plums;      // 매화
        public int pines;      // 절벽 소나무
        public long vines;     // 덩굴·이끼·지의
        public int props;      // 깃발·화로·상자·빨래줄·밭
        public int cloisters;  // 행각 (슬라이스 9)
        Print print;           // ★마른 조성(dry run) — null 이 아니면 put 이 쓰지 않고 발자국만 적는다
    }

    /**
     * 마른 조성 채집기 — <b>부품 코드 자체가 제 발자국의 정본이다</b> (슬라이스 7.5).
     *
     * <p>7.0 실기동의 평탄 95건 = 겹처마가 벽 밖 2칸 내밀었는데 손으로 적은 상자가 안 자랐다.
     * 5.6(정원 매화)·7.0 둘 다 같은 병: <b>상자와 실물이 별도 손으로 적히면 어긋난다.</b>
     * 처방: 상자를 손으로 적지 않는다 — {@code world=null}·{@code tally.print} 로 부품 함수를
     * 그대로 돌리면 {@link #put}/{@link #putRoofStair} 깔때기가 블록 대신 발자국을 적는다.
     * 조성과 상자가 <b>같은 코드</b>를 지나므로 어긋날 자리가 없다.
     *
     * <p>층위 둘: <b>전고</b>(처마 포함 — 평탄 검수 스킵·패드 담김)와 <b>지상</b>(y ≤ 포장+{@value
     * #GROUND_TOP} — 통로·다리 어귀 겹침). 처마가 계단 머리 위를 덮는 것은 「걷는 자의 눈」이
     * 이미 허용하므로 통로 검증은 지상만 잰다.
     */
    static final class Print {
        static final int GROUND_TOP = 4;   // 포장 위 이 높이까지가 「지상」 — 벽·기둥·목인은 걸리고 처마(≥벽고 5)는 빠진다
        private final int groundY;
        private int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE,
                z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;           // 전고
        private int gx0 = Integer.MAX_VALUE, gx1 = Integer.MIN_VALUE,
                gz0 = Integer.MAX_VALUE, gz1 = Integer.MIN_VALUE;         // 지상
        private int yTop = Integer.MIN_VALUE;                             // 최고점 (위계 눈 — 슬라이스 9)
        Print(int padY) {
            this.groundY = padY + GROUND_TOP;
        }
        void take(int x, int y, int z) {
            x0 = Math.min(x0, x); x1 = Math.max(x1, x);
            z0 = Math.min(z0, z); z1 = Math.max(z1, z);
            yTop = Math.max(yTop, y);
            if (y <= groundY) {
                gx0 = Math.min(gx0, x); gx1 = Math.max(gx1, x);
                gz0 = Math.min(gz0, z); gz1 = Math.max(gz1, z);
            }
        }
        int[] full() {
            return x0 > x1 ? null : new int[]{x0, x1, z0, z1};
        }
        int[] ground() {
            return gx0 > gx1 ? null : new int[]{gx0, gx1, gz0, gz1};
        }
    }

    /** 구역 부품 하나 — 실물 조성과 발자국 채집(마른 조성)이 같은 코드를 지난다 */
    @FunctionalInterface
    interface Part {
        void build(World world, TerraceForge.Pad pad, Tally tally);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 구역 배치 — 패드 하나 = 한 걸음 (TickBudget 이 사이를 문다)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 구역 하나의 건물을 앉힌다 — 계단참(102·103)은 소품 없음. 남향(문은 +z 쪽).
     *
     * <p>★구조는 두 층이다 (슬라이스 7.5): <b>지면 일</b>(재포장·모래 마당·담 — 포장면 높이라
     * 상자가 필요 없거나[재포장] 스스로 통로를 비킨다[담])은 여기서 직접, <b>구조물</b>은
     * {@link #parts} 목록으로 — 그 목록을 마른 조성으로 돌린 것이 곧 검수 상자다.
     */
    public static void buildZone(World world, TerraceForge.Plan plan, TerraceForge.Pad pad, Tally tally) {
        int cx = pad.x0() + pad.spec().width() / 2;
        int cz = pad.zN() + pad.spec().depth() / 2;
        switch (pad.spec().zone()) {                              // 지면 일 — 부품이 아니다
            case 1, 2, 6, 13, 101 -> sandyRepave(world, pad, tally);   // 공공 = 모래빛
            case 3, 14 -> sandField(world, pad, tally);                // 연무장 = 모래 마당 (테두리 4 박석)
            case 16 -> wallNS(world, plan, pad, cx, cz, tally);        // 담은 laneCrosses 로 스스로 비킨다
            default -> {
            }
        }
        for (Part part : parts(pad)) {
            part.build(world, pad, tally);
        }
    }

    /**
     * 구역별 구조물 부품 목록 — <b>이 표가 조성·검수 상자·통로 검증의 공동 정본이다</b>.
     * 조성은 {@link #buildZone} 이 이 목록을 실행하고, 상자는 {@link #structureBoxes} 가
     * 같은 목록을 마른 조성으로 돌려 얻는다 — 좌표·처마가 어긋날 자리가 없다 (7.5 계율).
     */
    static List<Part> parts(TerraceForge.Pad pad) {
        int cx = pad.x0() + pad.spec().width() / 2;
        int cz = pad.zN() + pad.spec().depth() / 2;
        int y = pad.y();
        int zS = pad.zS();
        // ★슬라이스 8 재척도: 사용자 기준자 (대계단 20) — 자리·크기 전부 ×2 (실측표 개정판)
        return switch (pad.spec().zone()) {
            case 1 -> List.of((w, p, t) -> gateGrand(w, p, cx, cz + 2, 28, t),   // 산문 57 — 처마가 램프 줄을 비킨다
                    (w, p, t) -> lanternRow(w, p, cx - 12, p.zN() + p.spec().depth() / 2 + 12, p.zS() - 4, t),   // ★9 — 접근 축선 등롱 열
                    (w, p, t) -> lanternRow(w, p, cx + 12, p.zN() + p.spec().depth() / 2 + 12, p.zS() - 4, t));
            case 2 -> List.of((w, p, t) -> pavilion(w, p, cx - 12, cz + 12, 6, t),  // 외원 — 대칭 정자 · 중앙 여백 (원작 11호)
                    (w, p, t) -> pavilion(w, p, cx + 12, cz + 12, 6, t),
                    // ★9b — 행각은 칸 중앙 통로(cz±5)에서 갈린다: 소계단 착지의 보행 연장선이
                    //   기둥 열을 밟지 않는다 (실기동 보행 2건의 처방 — 어귀는 비워 두는 것)
                    (w, p, t) -> cloister(w, p, p.x0() + 4, p.zN() + 4, cz - 6, t),
                    (w, p, t) -> cloister(w, p, p.x0() + 4, cz + 6, p.zS() - 4, t),
                    (w, p, t) -> cloister(w, p, p.x1() - 8, p.zN() + 4, cz - 6, t),
                    (w, p, t) -> cloister(w, p, p.x1() - 8, cz + 6, p.zS() - 4, t),
                    (w, p, t) -> lanternRow(w, p, cx - 12, p.zN() + 6, cz - 6, t),
                    (w, p, t) -> lanternRow(w, p, cx + 12, p.zN() + 6, cz - 6, t));
            case 6 -> List.of((w, p, t) -> gateGrand(w, p, cx, cz + 14, 20, t),   // 종문 41 (9b — 중층 마감 ~30 · 산문<)
                    (w, p, t) -> cloister(w, p, p.x0() + 4, p.zN() + 4, cz - 6, t),
                    (w, p, t) -> cloister(w, p, p.x0() + 4, cz + 6, p.zS() - 4, t),
                    (w, p, t) -> cloister(w, p, p.x1() - 8, p.zN() + 4, cz - 6, t),
                    (w, p, t) -> cloister(w, p, p.x1() - 8, cz + 6, p.zS() - 4, t));
            case 16 -> List.of((w, p, t) -> gateEW(w, p, cx, cz, 5, t));          // 동향 문루 (담은 지면 일)
            case 3 -> List.of((w, p, t) -> dummyRow(w, p, cx, cz - 4, 6, t),      // 연무장 하 — 목인 6
                    (w, p, t) -> rack(w, p, cx - 6, y, zS - 5, 6, t));
            case 14 -> List.of((w, p, t) -> dummyRow(w, p, cx, cz - 4, 5, t),     // 연무장 상
                    (w, p, t) -> rack(w, p, cx - 6, y, zS - 5, 6, t));
            case 7 -> List.of((w, p, t) -> dummyRow(w, p, cx, cz - 4, 4, t),      // 훈련장 중 (박석 마당)
                    (w, p, t) -> rack(w, p, cx - 6, y, zS - 5, 6, t));
            case 4 -> List.of((w, p, t) -> plasterHall(w, p, cx, cz + 4, 16, 10, true, false, t),   // 강당 33×21
                    (w, p, t) -> rack(w, p, cx - 6, y, cz + 4, 6, t));
            case 5 -> List.of((w, p, t) -> plasterHall(w, p, cx - 16, cz + 4, 11, 8, true, false, t),   // 생활 두 채 23×17 (★8.6 — 패드 동편 8 축소 몫)
                    (w, p, t) -> plasterHall(w, p, cx + 16, cz + 4, 11, 8, true, false, t));
            case 8 -> List.of((w, p, t) -> plasterHall(w, p, cx, cz, 8, 5, true, false, t));
            case 17 -> List.of((w, p, t) -> plasterHall(w, p, cx, cz + 4, 14, 10, false, false, t),  // 창고 29×21
                    (w, p, t) -> chests(w, p, cx, y, cz + 4, t));
            case 9 -> List.of((w, p, t) -> mainHall(w, p, cx, cz + 4, t));        // 본전 — 73폭 2층 중루
            case 12 -> List.of((w, p, t) -> plasterHall(w, p, cx, cz + 12, 16, 6, true, false, t));  // 장로회 33×13
            case 13 -> List.of((w, p, t) -> plasterHall(w, p, cx - 10, cz + 4, 8, 6, false, false, t),  // 정상 사당+정자
                    (w, p, t) -> pavilion(w, p, cx + 10, cz + 4, 6, t));
            case 19 -> List.of((w, p, t) -> pavilion(w, p, cx + 1, cz, 4, t));    // 절벽 전망대 (처마가 패드 안)
            case 20 -> List.of((w, p, t) -> plasterHall(w, p, cx + 1, cz, 7, 5, true, false, t));  // 부속 암자 (처마가 다리 어귀를 비킨다)
            case 101 -> List.of((w, p, t) -> pavilion(w, p, cx - 12, cz + 14, 5, t),   // 중정 정자 한 쌍 (★9 — 행각 몫으로 안쪽)
                    (w, p, t) -> pavilion(w, p, cx + 12, cz + 14, 5, t),
                    (w, p, t) -> cloister(w, p, p.x0() + 4, p.zN() + 4, cz - 6, t),   // 9b — 중앙 통로 개구
                    (w, p, t) -> cloister(w, p, p.x0() + 4, cz + 6, p.zS() - 4, t),
                    (w, p, t) -> cloister(w, p, p.x1() - 8, p.zN() + 4, cz - 6, t),
                    (w, p, t) -> cloister(w, p, p.x1() - 8, cz + 6, p.zS() - 4, t));
            case 10 -> List.of((w, p, t) -> gardenPond(w, p, cx, cz, t),          // 정원 — 연못·정자·매화 세 부품
                    (w, p, t) -> pavilion(w, p, cx + 12, cz + 12, 5, t),
                    (w, p, t) -> gardenPlum(w, p, cx - 4, cz - 12, t));
            case 11 -> List.of((w, p, t) -> watchtower(w, p, cx, cz + 12, t));     // 망루 22/18/14
            default -> List.of();                                                 // 계단참 102·103 — 지나는 자리
        };
    }

    // ═══════════════════════════════════════════════════════════════════
    // 순수 검증 + 세계 검수 — 발자국은 패드 안 (계율 #4)
    // ═══════════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════════
    // 조경 (슬라이스 4) — 소품 목록이 정본: 조성·발자국 상자·검증이 같은 표를 읽는다
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 조경 소품 하나 — 종류와 자리 (절대 좌표).
     * 'M' 매화(관 ±2) · 'B' 홍기(무지 — 문양·글자는 사용자 몫) · 'W' 백기 ·
     * 'F' 화로 · 'C' 상자 더미(3×2) · 'L' 빨래줄(기둥 ±2 + 실) · 'P' 밭(5×4)
     */
    record Decor(char kind, int x, int z) {
    }

    /**
     * 구역별 조경 소품 【제안 · 빨간펜 대상】 — 원작 11호의 여백이 기준: 단마다 1~3점.
     * 자리는 통로·구조물 눈이 검증한다 ({@link #validateBuildings} 가 이 표의 상자를 잰다).
     */
    static List<Decor> decors(TerraceForge.Pad pad) {
        int cx = pad.x0() + pad.spec().width() / 2;
        int cz = pad.zN() + pad.spec().depth() / 2;
        // ★슬라이스 8 재척도 ×2 (매화 수관도 커졌다 — 실측 12~16)
        return switch (pad.spec().zone()) {
            case 1 -> List.of(new Decor('M', cx - 36, cz + 12), new Decor('M', cx + 36, cz + 12));
            case 2 -> List.of(new Decor('M', cx - 26, cz - 12), new Decor('M', cx + 26, cz - 12));
            case 6 -> List.of(new Decor('M', cx - 24, cz - 16), new Decor('M', cx + 24, cz - 16));
            case 9 -> List.of(new Decor('M', cx - 32, cz - 20), new Decor('M', cx + 32, cz - 20));
            case 12 -> List.of(new Decor('M', cx + 16, cz + 8), new Decor('M', cx + 28, cz + 8));   // 동편 마당 (홀은 서편)
            case 20 -> List.of(new Decor('M', cx - 4, cz + 5));   // 다리 어귀(서편 x≤50)를 비킨다
            case 10 -> List.of(new Decor('M', cx + 4, cz - 20));
            case 3 -> List.of(new Decor('B', cx - 12, pad.zN() + 5), new Decor('B', cx + 12, pad.zN() + 5),
                    new Decor('F', pad.x0() + 7, pad.zN() + 7), new Decor('F', pad.x1() - 7, pad.zN() + 7));
            case 14 -> List.of(new Decor('B', cx - 10, pad.zS() - 5), new Decor('B', cx + 10, pad.zS() - 5),
                    new Decor('F', pad.x0() + 7, pad.zN() + 7));
            case 7 -> List.of(new Decor('B', cx - 10, pad.zS() - 5), new Decor('B', cx + 10, pad.zS() - 5));
            case 17 -> List.of(new Decor('C', cx + 20, cz + 4));
            case 5 -> List.of(new Decor('L', cx, cz + 20));
            case 8 -> List.of(new Decor('L', cx - 18, cz + 18), new Decor('P', cx + 12, cz + 16));
            default -> List.of();
        };
    }

    /** 소품 발자국 상자 — {@link #decors} 에서 유도 (조성과 검증이 같은 자를 쓴다) */
    static List<int[]> decorBoxes(TerraceForge.Pad pad) {
        java.util.ArrayList<int[]> out = new java.util.ArrayList<>();
        for (Decor d : decors(pad)) {
            out.add(switch (d.kind()) {
                case 'M' -> new int[]{d.x() - 4, d.x() + 4, d.z() - 4, d.z() + 4};   // 수관 ±4 (실측 12~16 재척도)
                case 'C' -> new int[]{d.x(), d.x() + 3, d.z(), d.z() + 2};
                case 'L' -> new int[]{d.x() - 4, d.x() + 4, d.z(), d.z()};
                case 'P' -> new int[]{d.x(), d.x() + 9, d.z(), d.z() + 7};
                default -> new int[]{d.x(), d.x(), d.z(), d.z()};
            });
        }
        return out;
    }

    /** 검수 평탄 눈이 비켜 갈 상자 전부 — 구조물(전고·마른 조성) + 소품 + 측문 담 띠 */
    public static List<int[]> auditSkipBoxes(TerraceForge.Pad pad) {
        java.util.ArrayList<int[]> out = new java.util.ArrayList<>(structureBoxes(pad));
        out.addAll(decorBoxes(pad));
        if (pad.spec().zone() == 16) {
            // 담 띠 — wallNS 가 x=cx 한 열에 세운다 (같은 식 · 부품이 아니라 여기서 얹는다)
            int cx = pad.x0() + pad.spec().width() / 2;
            out.add(new int[]{cx, cx, pad.zN(), pad.zS()});
        }
        return out;
    }

    /**
     * 조성 전 순수 검증 — ①구역별 최대 발자국(처마 포함)이 패드 안에 드는가
     * ②★구조물(문루·홀·정자·탑·목인·시렁·연못·나무)이 <b>계단 몸체(통로)와 겹치지 않는가</b>.
     * ②는 2.5 실기동의 보행 단차 5건(망루가 착지선을 막고, 강당 처마가 보행선에 걸림)의 처방 —
     * <b>검수가 걷는 선은 조성이 다진 선과 같아야 하고, 그 선 위에 아무것도 세우지 않는다.</b>
     * 눈({@code tools/TerraceForgeSelfTest.java})과 계획이 같은 자를 쓴다.
     */
    public static void validateBuildings(List<TerraceForge.Pad> pads, List<TerraceForge.StairLane> lanes,
                                         List<TerraceForge.Bridge> bridges) {
        for (TerraceForge.Pad pad : pads) {
            for (int[] box : auditSkipBoxes(pad)) {
                if (box[0] < pad.x0() || box[1] > pad.x1() || box[2] < pad.zN() || box[3] > pad.zS()) {
                    throw new IllegalArgumentException("건물·소품 발자국이 패드 밖: " + pad.spec().zone()
                            + " " + pad.spec().name() + " — 발자국 x" + box[0] + ".." + box[1] + " z"
                            + box[2] + ".." + box[3] + " vs 패드 x" + pad.x0() + ".." + pad.x1()
                            + " z" + pad.zN() + ".." + pad.zS());
                }
            }
            // ★통로·다리 어귀 겹침은 <b>지상 발자국</b>으로 잰다 (7.5) — 처마가 계단 머리 위를
            //   덮는 것은 「걷는 자의 눈」이 허용하는 정상 통행이다 (문루 아래를 걷는다).
            java.util.ArrayList<int[]> groundLevel = new java.util.ArrayList<>(groundBoxes(pad));
            groundLevel.addAll(decorBoxes(pad));
            for (int[] sb : groundLevel) {
                // ★다리 회랑 (걷는 폭 ±2 · 패드 안 두 칸 이음 포함) — 구조물이 다리 어귀를 막으면 거절
                for (TerraceForge.Bridge b : bridges) {
                    for (int t = b.a0() - 2; t <= b.a1() + 2; t++) {
                        for (int o = -2; o <= 2; o++) {
                            int x = b.alongX() ? t : b.c() + o;
                            int z = b.alongX() ? b.c() + o : t;
                            if (x >= sb[0] && x <= sb[1] && z >= sb[2] && z <= sb[3]) {
                                throw new IllegalArgumentException("구조물이 다리 어귀를 막는다: "
                                        + pad.spec().zone() + " " + pad.spec().name() + " ∩ 다리 "
                                        + b.spec().name() + " (" + x + "," + z + ")");
                            }
                        }
                    }
                }
                for (TerraceForge.StairLane lane : lanes) {
                    int px = lane.dirZ() != 0 ? 1 : 0;
                    int pz = lane.dirZ() != 0 ? 0 : 1;
                    for (int t = 0; t <= lane.length(); t++) {
                        for (int o = -lane.rail(); o <= lane.rail(); o++) {
                            int x = lane.startX() + lane.dirX() * (t - 1) + px * o;
                            int z = lane.startZ() + lane.dirZ() * (t - 1) + pz * o;
                            if (x >= sb[0] && x <= sb[1] && z >= sb[2] && z <= sb[3]) {
                                throw new IllegalArgumentException("구조물이 계단 통로를 막는다: "
                                        + pad.spec().zone() + " " + pad.spec().name() + " 발자국 x"
                                        + sb[0] + ".." + sb[1] + " z" + sb[2] + ".." + sb[3]
                                        + " ∩ 계단 " + lane.link().upperZone() + "→"
                                        + lane.link().lowerZone() + " (" + x + "," + z + ")");
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 구역별 <b>실구조물</b> 발자국(<b>전고</b> — 처마 포함) — 평탄 스킵·패드 담김 검증용.
     *
     * <p>★손으로 적지 않는다 (슬라이스 7.5): {@link #parts} 목록을 <b>마른 조성</b>으로 돌려
     * 부품 코드가 스스로 보고한 발자국을 쓴다. 5.6(정원 매화 한 칸)·7.0(처마 링 95건)이
     * 전부 「상자와 실물이 별도 손」의 병이었다 — 이제 같은 코드가 둘 다를 만든다.
     * 측문(16)의 담은 부품이 아니다 — 담은 계단 통로를 스스로 비킨다 ({@code wallNS} 의
     * laneCrosses 가드) · 평탄 스킵 몫은 {@link #auditSkipBoxes} 가 담 띠로 얹는다.
     */
    public static List<int[]> structureBoxes(TerraceForge.Pad pad) {
        return dryRunBoxes(pad, false);
    }

    /** 구역별 실구조물 <b>지상</b> 발자국(y ≤ 포장+{@value Print#GROUND_TOP}) — 통로·다리 어귀 겹침 검증용 (처마 제외) */
    public static List<int[]> groundBoxes(TerraceForge.Pad pad) {
        return dryRunBoxes(pad, true);
    }

    private static List<int[]> dryRunBoxes(TerraceForge.Pad pad, boolean ground) {
        java.util.ArrayList<int[]> out = new java.util.ArrayList<>();
        for (Part part : parts(pad)) {
            Tally dry = new Tally();
            dry.print = new Print(pad.y());
            part.build(null, pad, dry);          // world 는 안 닿는다 — put 깔때기가 발자국만 적는다
            int[] box = ground ? dry.print.ground() : dry.print.full();
            if (box != null) {
                out.add(box);
            }
        }
        return out;
    }

    /**
     * 구역 구조물의 최고점 y (마른 조성) — <b>위계의 눈</b>이 읽는다 (슬라이스 9 · 코덱스 §⑤:
     * 전 건물이 같은 층고면 중심이 없다 — 본전 > 산문 > 홀 층고 차등을 값으로 잰다).
     */
    public static int structureTopY(TerraceForge.Pad pad) {
        int top = Integer.MIN_VALUE;
        for (Part part : parts(pad)) {
            Tally dry = new Tally();
            dry.print = new Print(pad.y());
            part.build(null, pad, dry);
            top = Math.max(top, dry.print.yTop);
        }
        return top;
    }

    /**
     * 조성 뒤 세계 검수 — 패드 밖 스커트 띠(1~{@code SKIRT+1}칸)에서 건물 재료가 나오면
     * 유출이다. put 가드가 있으니 0 이어야 하지만, <b>선 것을 다시 잰다 (계획을 안 믿는다)</b>.
     */
    public static List<String> auditBuildings(World world, TerraceForge.Plan plan) {
        java.util.ArrayList<String> leaks = new java.util.ArrayList<>();
        for (TerraceForge.Pad pad : plan.pads()) {
            for (int x = pad.x0() - 3; x <= pad.x1() + 3 && leaks.size() < 8; x++) {
                for (int z = pad.zN() - 3; z <= pad.zS() + 3; z++) {
                    if (pad.contains(x, z) || onAnyPad(plan, x, z) || onBridge(plan, x, z)) {
                        continue;   // ★다리 몸체(상판·난간·교대)는 다리의 것 — 명세 기하로 판정 (3.0 유출 8건의 수리)
                    }
                    for (int y = pad.y() + 1; y <= pad.y() + 16; y++) {
                        Material m = world.getBlockAt(x, y, z).getType();
                        if (LEAK_SCAN.contains(m)) {
                            leaks.add("유출: " + pad.spec().name() + " 곁 (" + x + "," + y + "," + z
                                    + ") " + m);
                            break;
                        }
                    }
                }
            }
        }
        return leaks;
    }

    /** 그 열이 등록된 다리 몸체(폭 ±2 · 패드 안 이음 포함)인가 — 유출 눈이 다리를 안다 */
    private static boolean onBridge(TerraceForge.Plan plan, int x, int z) {
        for (TerraceForge.Bridge b : plan.bridges()) {
            if (b.covers(x, z)) {
                return true;
            }
        }
        return false;
    }

    private static boolean onAnyPad(TerraceForge.Plan plan, int x, int z) {
        for (TerraceForge.Pad p : plan.pads()) {
            if (p.contains(x, z)) {
                return true;
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 부품 — SectBuilder 문법의 패드판 (전부 put 가드를 지난다)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 문루 — {@code SectBuilder.mountainGate} 문법: 돌기둥 두 쌍 · 처마 · 현판 자리(빈 판).
     * 동서로 벌리고 남북으로 지난다.
     */
    private static void gate(World world, TerraceForge.Pad pad, int cx, int cz, int half, Tally tally) {
        int y = pad.y();
        for (int l : new int[]{-half, -half + 1, half - 1, half}) {
            for (int dy = 1; dy <= 6; dy++) {
                put(world, pad, cx + l, y + dy, cz, Material.STONE_BRICKS, tally);
            }
        }
        for (int l = -half - 1; l <= half + 1; l++) {
            put(world, pad, cx + l, y + 7, cz, Material.POLISHED_ANDESITE, tally);
            put(world, pad, cx + l, y + 8, cz, Material.DEEPSLATE_TILES, tally);
        }
        for (int l = -half - 2; l <= half + 2; l++) {
            put(world, pad, cx + l, y + 8, cz - 1, Material.DEEPSLATE_TILE_SLAB, tally);
            put(world, pad, cx + l, y + 8, cz + 1, Material.DEEPSLATE_TILE_SLAB, tally);
        }
        // 현판 자리 — 빈 판 (글자는 사용자 몫)
        put(world, pad, cx, y + 6, cz, Material.DARK_OAK_PLANKS, tally);
        put(world, pad, cx - half, y + 1, cz + 1, Material.LANTERN, tally);
        put(world, pad, cx + half, y + 1, cz + 1, Material.LANTERN, tally);
        tally.gates++;
    }

    /**
     * 큰 문루 — <b>실측 1호의 산문</b>: 전면 2·half+1 · 깊이 5 · 중앙 아치 5×6 · (half≥12 이면)
     * 곁 아치 3×4 두 짝 · ★적주+백벽+흑기와 (이미지가 정본 — 문루도 붉다 · 실측표 §3) ·
     * 상층 백벽 · 빈 현판.
     */
    private static void gateGrand(World world, TerraceForge.Pad pad, int gx, int gz, int half, Tally tally) {
        // ★슬라이스 8 재척도 ×2: 깊이 9 · 중앙 아치 11×12 · 곁 아치 5×8 (±18) · 하층 벽 12 · 상층 8
        int y = pad.y();
        for (int f = -half; f <= half; f++) {
            int af = Math.abs(f);
            // ★14-③ 정면 3단 요철 — 중앙 17폭이 <b>2칸 앞으로 나온다</b>. 대결의 두 평가가
            //   똑같이 지목한 약점이 「중앙부 미돌출」이었다 (본전엔 13b 에 포치가 섰는데
            //   산문엔 없었다). 끝 구간은 종전대로 한 칸 물러난다 → 앞선다/기준/물러난다 세 켜.
            int relief = gateRelief(af, half);          // +2 앞선다 / 0 기준 / -1 물러난다
            int jut = Math.max(relief, 0);
            int recess = Math.max(-relief, 0);
            for (int d = -4 + recess; d <= 4 - recess + jut; d++) {
                boolean sideArch = half >= 24 && Math.abs(af - 18) <= 2;   // 곁 아치 5 폭
                boolean arch = af <= 5 || sideArch;                        // 중앙 아치 11 폭
                for (int dy = 1; dy <= 12; dy++) {
                    int x = gx + f;
                    int z = gz + d;
                    if (arch && (!sideArch ? dy <= 12 : dy <= 8)) {
                        put(world, pad, x, y + dy, z, Material.AIR, tally);   // 아치 11×12 / 5×8
                        continue;
                    }
                    // ★14-② 적주를 굵게 — 한 칸 → <b>두 칸</b>. 대결에서 코덱스 문루가 훨씬
                    //   따뜻했고, 회백 과다가 우리 격차 ③의 뿌리였다 (적목 비중 상향·백벽 절제).
                    boolean pillarF = af >= half - 1 || af == 6 || af == 7
                            || (half >= 24 && Math.abs(af - 18) >= 3 && Math.abs(af - 18) <= 4);
                    // 남·북 겉면 — 입면 층위는 겉면에만. ★돌출부는 <b>제 앞면</b>이 겉면이고
                    //   뒷면(북)은 종전 자리 그대로다 — 두 쪽을 따로 잡지 않으면 돌출한 중앙의
                    //   북면이 민 백벽으로 남는다 (요철을 넣다가 뒷면을 잃는 함정).
                    boolean shell = d == 4 - recess + jut || d == -4 + recess;
                    Material m = pillarF ? Material.STRIPPED_MANGROVE_LOG   // 적주(벗긴 맹그로브 — 껍질면은 흑갈로 읽힌다)
                            : shell && dy == 1 ? Material.POLISHED_ANDESITE                     // 기단 밝은 선
                            : shell && dy == 2 ? Material.STRIPPED_MANGROVE_LOG                 // ★14-② 하인방 적목 띠
                            : shell && dy == 7 ? Material.DARK_OAK_PLANKS                       // 중방 — 두 층 리듬
                            : shell && dy >= 10 ? Material.RED_TERRACOTTA                       // ★14-② 상단 적 띠 (11→10 확대)
                            : shell && Math.floorMod(f, 3) == 1
                                    && (dy == 4 || dy == 5 || dy == 8 || dy == 9)
                                    ? Material.GLASS_PANE                                       // 창 리듬 3칸
                            : Material.WHITE_TERRACOTTA;
                    put(world, pad, x, y + dy, z, m, tally);
                }
            }
        }
        // ★14-③ 포치 차양 — 돌출한 중앙이 제 처마를 인다 (돌출이 지붕 없이 벽만이면 요철이 안 읽힌다)
        for (int f = -9; f <= 9; f++) {
            for (int d = 5; d <= 7; d++) {
                put(world, pad, gx + f, y + 11, gz + d,
                        d == 7 || Math.abs(f) == 9 ? Material.DEEPSLATE_TILE_SLAB
                                : roofCube(gx + f, y + 11, gz + d), tally);
            }
        }
        eaveUpturn(world, pad, gx, y + 11, gz + 6, 9, 1, tally);   // 차양 귀도 들린다
        // ★슬라이스 9 — 산문 재설계 (코덱스 개선 2: 높이 1.5~2배·지붕 두께 2배·위계의 정점).
        //   3단 구성: 하층(1..12 아치 몸) → 겹처마 스커트 → 중층(13..22) → 스커트 → 상층(23..30)
        //   → 팔작. 총고 ~38 (구 ~26 의 1.5배) — 본전(~41)보다는 낮다 (위계: 본전 > 산문).
        int mh = half - 4;   // 중층 반폭
        for (int f = -mh; f <= mh; f++) {
            for (int d = -3; d <= 3; d++) {
                boolean edge = Math.abs(f) == mh || Math.abs(d) == 3;
                if (!edge) {
                    continue;
                }
                // ★14-② 적주 두 칸 (한 칸 → 두 칸 · 적목 비중 상향)
                boolean pillar = Math.abs(f) >= mh - 1
                        || Math.floorMod(f + mh, 6) <= 1;
                for (int dy = 13; dy <= 22; dy++) {
                    Material m = pillar ? Material.STRIPPED_MANGROVE_LOG
                            : (dy == 13 ? Material.STRIPPED_MANGROVE_LOG                 // ★14-② 하인방 적목
                            : dy == 17 ? Material.DARK_OAK_PLANKS                        // 중방
                            : dy >= 20 ? Material.RED_TERRACOTTA : Material.WHITE_TERRACOTTA);
                    put(world, pad, gx + f, y + dy, gz + d, m, tally);
                }
                if (!pillar && Math.abs(d) == 3 && Math.floorMod(f, 3) == 1) {   // 창 리듬 3칸
                    for (int wy : new int[]{15, 16, 19, 20}) {
                        put(world, pad, gx + f, y + wy, gz + d, Material.GLASS_PANE, tally);
                    }
                }
            }
        }
        bracketRing(world, pad, gx, y + 21, gz, mh + 1, 4, tally);   // 공포 — 중층 처마 밑
        eaveRing(world, pad, gx, y + 22, gz, mh, 3, tally);
        // ★9b — 층고 차등 (위계): 산문(half≥24)만 상층을 얹어 ~38, 종문은 중층 팔작 마감 ~30
        if (half >= 24) {
            int th = half - 8;   // 상층 반폭
            for (int f = -th; f <= th; f++) {
                for (int d = -2; d <= 2; d++) {
                    put(world, pad, gx + f, y + 22, gz + d, Material.SPRUCE_PLANKS, tally);   // 상층 마루
                    boolean edge = Math.abs(f) == th || Math.abs(d) == 2;
                    if (!edge) {
                        continue;
                    }
                    boolean pillar = Math.abs(f) >= th - 1 || Math.floorMod(f + th, 6) <= 1;   // ★14-② 두 칸
                    for (int dy = 23; dy <= 30; dy++) {
                        Material m = pillar ? Material.STRIPPED_MANGROVE_LOG
                                : (dy == 23 ? Material.STRIPPED_MANGROVE_LOG              // ★14-② 하인방 적목
                                : dy >= 28 ? Material.RED_TERRACOTTA : Material.WHITE_TERRACOTTA);
                        put(world, pad, gx + f, y + dy, gz + d, m, tally);
                    }
                    if (!pillar && Math.abs(d) == 2 && Math.floorMod(f, 3) == 1) {
                        for (int wy : new int[]{25, 26, 27}) {
                            put(world, pad, gx + f, y + wy, gz + d, Material.GLASS_PANE, tally);
                        }
                    }
                }
            }
            bracketRing(world, pad, gx, y + 29, gz, th + 1, 3, tally);   // 공포 — 상층 처마 밑
            sweepRoof(world, pad, gx, y + 30, gz, th, 2, tally);
        } else {
            sweepRoof(world, pad, gx, y + 22, gz, mh, 3, tally);
        }
        // 겹처마 — 하층 스커트 (실측표 §3-b)
        eaveRing(world, pad, gx, y + 12, gz, half, 4, tally);
        for (int pf = -1; pf <= 1; pf++) {                        // 빈 현판 (남·북면 — 글자는 사용자 몫)
            put(world, pad, gx + pf, y + 16, gz + 3, Material.DARK_OAK_PLANKS, tally);
            put(world, pad, gx + pf, y + 16, gz - 3, Material.DARK_OAK_PLANKS, tally);
        }
        put(world, pad, gx - 6, y + 1, gz + 5, Material.LANTERN, tally);
        put(world, pad, gx + 6, y + 1, gz + 5, Material.LANTERN, tally);
        tally.gates++;
    }

    /** 동향 문루 — {@link #gate} 의 90°판: 기둥이 남북으로 벌고 통행이 동서다 */
    private static void gateEW(World world, TerraceForge.Pad pad, int gx, int cz, int half, Tally tally) {
        // ★재척도 ×2 — 기둥 2겹·벽 12·통행 개구 그대로 (동서 통행)
        int y = pad.y();
        for (int l : new int[]{-half, -half + 1, half - 1, half}) {
            for (int dy = 1; dy <= 12; dy++) {
                put(world, pad, gx, y + dy, cz + l, Material.STONE_BRICKS, tally);
            }
        }
        for (int l = -half - 1; l <= half + 1; l++) {
            put(world, pad, gx, y + 13, cz + l, Material.POLISHED_ANDESITE, tally);
        }
        sweepRoof(world, pad, gx, y + 13, cz, 2, half, tally);   // 회전 팔작 — 용마루가 남북
        put(world, pad, gx, y + 11, cz, Material.DARK_OAK_PLANKS, tally);   // 빈 현판
        put(world, pad, gx + 1, y + 1, cz - half, Material.LANTERN, tally);
        put(world, pad, gx + 1, y + 1, cz + half, Material.LANTERN, tally);
        tally.gates++;
    }

    /** 측문의 남북 담 — 문루 위아래로 패드 끝까지. 계단 통로는 담이 비킨다 */
    private static void wallNS(World world, TerraceForge.Plan plan, TerraceForge.Pad pad,
                               int gx, int cz, Tally tally) {
        int y = pad.y();
        for (int z = pad.zN(); z <= pad.zS(); z++) {
            if (Math.abs(z - cz) <= 10 || laneCrosses(plan, gx, z)) {
                continue;   // 문루 몫(처마 포함 · 재척도 half 5+처마 4+1) · 계단 통로는 담이 비킨다
            }
            for (int dy = 1; dy <= 5; dy++) {
                put(world, pad, gx, y + dy, z, Material.STONE_BRICKS, tally);
            }
            put(world, pad, gx, y + 6, z, Material.DEEPSLATE_TILE_SLAB, tally);
        }
    }

    /**
     * 회벽 집 — {@code SectBuilder.plasterHall} 문법: 백벽 · 침엽 모서리 기둥 · 흑기와 모임지붕 ·
     * 가늘고 긴 세로 창 · 남문.
     */
    private static void plasterHall(World world, TerraceForge.Pad pad, int cx, int cz,
                                    int hf, int hl, boolean windows, boolean red, Tally tally) {
        int y = pad.y();
        int wallH = 10;   // 실측 단층 벽 10~12 (슬라이스 8 재척도)
        for (int f = -hf; f <= hf; f++) {
            for (int l = -hl; l <= hl; l++) {
                int x = cx + f;
                int z = cz + l;
                put(world, pad, x, y, z, Material.SPRUCE_PLANKS, tally);   // 마루
                boolean edge = Math.abs(f) == hf || Math.abs(l) == hl;
                if (!edge) {
                    continue;
                }
                boolean corner = Math.abs(f) == hf && Math.abs(l) == hl;
                for (int dy = 1; dy <= wallH; dy++) {
                    // ★슬라이스 9 — 입면 층위 (코덱스 §②): 기단(밝은 수평선 1단) · 중방(다크오크
                    //   띠 dy5 — 벽 10 이 두 층 리듬으로 읽힌다 · 인간 단위 층고 3~4 의 복원) · 상단 띠
                    //   ★14-② 백벽 절제 — 하인방(dy2) 적목 띠 + 상단 적 띠를 두 켜로. 조닝 계율은
                    //   유지한다: 본전(red)만 강하게 붉고, 다른 건물은 「따뜻해지되 본전보다 덜」.
                    Material m = corner ? (red ? Material.MANGROVE_LOG : Material.SPRUCE_LOG)
                            : (dy == 1 ? Material.POLISHED_ANDESITE
                            : dy == 2 ? Material.STRIPPED_MANGROVE_LOG
                            : dy == 5 ? Material.DARK_OAK_PLANKS
                            : (red && dy >= wallH - 1 ? Material.RED_TERRACOTTA
                            : dy == wallH ? Material.DARK_OAK_PLANKS : Material.WHITE_TERRACOTTA));
                    put(world, pad, x, y + dy, z, m, tally);
                }
                // ★이중 창 리듬 — 3칸마다 (인간 단위 칸이 누적된다 · 코덱스 §⑤)
                if (windows && !corner) {
                    int along = Math.abs(l) == hl ? f : l;
                    if (Math.floorMod(along, 3) == 1) {
                        for (int wy : new int[]{3, 4, 7, 8}) {
                            if (wy < wallH) {
                                put(world, pad, x, y + wy, z, Material.GLASS_PANE, tally);
                            }
                        }
                    }
                }
            }
        }
        // 전면 — 적주 열주 + 처마 보 + 격자창 (실측표 §3-b · 재척도)
        int doorHalf = hf >= 12 ? 2 : 1;
        colonnade(world, pad, cx, y, cz, hf, hl, wallH, doorHalf, tally);
        for (int dx = -doorHalf; dx <= doorHalf; dx++) {   // 남문 — ★인간 단위 4 (코덱스 §⑤: 문이 크면 사람 기준이 사라진다)
            for (int dy = 1; dy <= 4; dy++) {
                put(world, pad, cx + dx, y + dy, cz + hl, Material.AIR, tally);
            }
        }
        bracketRing(world, pad, cx, y + wallH - 1, cz, hf + 1, hl + 1, tally);   // 공포층 — 처마 밑 (슬라이스 9)
        sweepRoof(world, pad, cx, y + wallH, cz, hf, hl, tally);
        // ★13b-② 큰 홀은 중앙 현관이 앞으로 — 긴 벽의 요철. 단, 포치는 패드 안에서만
        //   (남면 여유가 모자라면 세우지 않는다 — 눈이 계획 단계에서 잡아 준 계약)
        if (hf >= 14 && cz + hl + 5 <= pad.zS() - 1) {
            porch(world, pad, cx, y, cz + hl, 4, 3, wallH, tally);
        }
        put(world, pad, cx, y + wallH, cz + hl + 1, Material.LANTERN, tally);
        tally.halls++;
    }

    /**
     * 팔작/우진각 근사 지붕 — <b>실측표 §3-b 의 공용 지붕 문법</b> (슬라이스 7 · 전 건물 공통):
     * 처마가 벽선 밖 2칸 내밀고(반블록 끝), 모서리는 1단 들리며, 계단 블록이 물매를 만들고,
     * 용마루는 큐브+반블록, 마루 끝은 담장 블록(치미)이 솟는다.
     */
    private static void sweepRoof(World world, TerraceForge.Pad pad, int cx, int cy, int cz,
                                  int hf, int hl, Tally tally) {
        int over = 4;   // 처마 내밈 실측 3~5 (슬라이스 8 재척도)
        boolean big = Math.max(hf, hl) >= 12;   // ★13c-② 큰 지붕만 — 치미 솟음·내림마루·합각
        for (int i = 0; ; i++) {
            int hF = hf + over - i;
            int hL = hl + over - i;
            int y = cy + i;
            if (hF <= 0 || hL <= 0) {
                // 용마루 — 긴 축으로 수렴 · ★13c-② 끝은 치미가 한 칸 더 솟는다 (선으로 갈리는
                //   지붕: 큰 면이 평평히 읽히던 것의 처방 · 레퍼런스 1·4호)
                boolean alongF = hF > 0;
                int len = Math.max(alongF ? hF : hL, 0);
                for (int k = -len; k <= len; k++) {
                    int x = alongF ? cx + k : cx;
                    int z = alongF ? cz : cz + k;
                    put(world, pad, x, y, z, Material.DEEPSLATE_TILES, tally);
                    boolean tip = Math.abs(k) == len;
                    put(world, pad, x, y + 1, z,
                            tip ? Material.DEEPSLATE_TILE_WALL : Material.DEEPSLATE_TILE_SLAB, tally);
                    if (tip && big) {
                        put(world, pad, x, y + 2, z, Material.DEEPSLATE_TILE_WALL, tally);   // 치미 솟음
                    }
                }
                return;
            }
            for (int f = -hF; f <= hF; f++) {
                for (int l = -hL; l <= hL; l++) {
                    int x = cx + f;
                    int z = cz + l;
                    boolean eF = Math.abs(f) == hF;
                    boolean eL = Math.abs(l) == hL;
                    if (i == 0) {                          // 처마 끝 — 반블록 (귀솟음은 아래에서 한 벌)
                        put(world, pad, x, y, z, eF || eL ? Material.DEEPSLATE_TILE_SLAB
                                : roofCube(x, y, z), tally);
                    } else if (eF && eL) {
                        // ★13c-② 내림마루 — 모서리에서 처마로 내려오는 마루 선 (면을 가른다)
                        put(world, pad, x, y, z, Material.DEEPSLATE_TILES, tally);
                        if (big) {
                            put(world, pad, x, y + 1, z, Material.DEEPSLATE_TILE_SLAB, tally);
                        }
                    } else if (eL) {
                        putRoofStair(world, pad, x, y, z,
                                l > 0 ? org.bukkit.block.BlockFace.NORTH
                                        : org.bukkit.block.BlockFace.SOUTH, tally);
                    } else if (eF) {
                        putRoofStair(world, pad, x, y, z,
                                f > 0 ? org.bukkit.block.BlockFace.WEST
                                        : org.bukkit.block.BlockFace.EAST, tally);
                    } else {
                        put(world, pad, x, y, z, roofCube(x, y, z), tally);
                    }
                }
            }
            if (i == 0) {   // ★14-① 귀솟음 — 처마 끝 네 귀가 들린다 (대결에서 배운 기법)
                eaveUpturn(world, pad, cx, y, cz, hF, hL, tally);
            }
            // ★13c-② 합각(측면 삼각 벽) — 짧은 축 끝면을 백벽으로 막아 지붕 옆이 「면」이 된다
            //   (레퍼런스 1·4호: 팔작의 측면 삼각). 큰 지붕만·용마루 쪽 두 켜.
            if (big && i >= 1 && hF > 0 && hL > 0 && hF != hL) {
                boolean gableAlongF = hF < hL;          // 짧은 축이 합각면
                int gh = gableAlongF ? hF : hL;
                for (int k = -gh; k <= gh; k++) {
                    for (int s : new int[]{-1, 1}) {
                        int gx = gableAlongF ? cx + k : cx + s * (hF);
                        int gz = gableAlongF ? cz + s * (hL) : cz + k;
                        put(world, pad, gx, y - 1, gz,
                                Math.abs(k) == gh ? Material.DARK_OAK_PLANKS
                                        : Material.WHITE_TERRACOTTA, tally);
                    }
                }
            }
        }
    }

    /**
     * ★14-① <b>귀솟음(까치발)</b> — 처마 끝이 <b>모서리에서 가장 크게 들리고 중앙으로 갈수록
     * 평평해진다</b>. 산문 문루 대결에서 코덱스에게 배운 기법이다: 곡선 블록이 없어도
     * 계단·반블록을 귀에서 층지게 쌓으면 <b>「들린 처마」가 읽힌다</b> — 우리 지붕이 평평해
     * 보이던 까닭이 이 한 켜의 부재였다 (곡선 처마 불허 판정과도 맞물린다: 블록만으로 되는 일).
     *
     * <p>정본: {@code docs/design/hwasan_gate_contest.md} 「내재화할 기법 3」 ①.
     * 귀 = 두 켜(몸+꺾임) · 한 칸 안 = 한 켜 · 그 안쪽 = 평평.
     */
    private static void eaveUpturn(World world, TerraceForge.Pad pad, int cx, int y, int cz,
                                   int hf, int hl, Tally tally) {
        for (int sf : new int[]{-1, 1}) {
            for (int sl : new int[]{-1, 1}) {
                int gx = cx + sf * hf;
                int gz = cz + sl * hl;
                for (int d = 0; d <= 1; d++) {
                    int rise = upturnRise(d);
                    if (rise == 0) {
                        continue;
                    }
                    if (d == 0) {
                        put(world, pad, gx, y + 1, gz, Material.DEEPSLATE_TILES, tally);       // 귀 — 솟음의 몸
                        put(world, pad, gx, y + rise, gz, Material.DEEPSLATE_TILE_WALL, tally); // 귀 끝 — 하늘로 꺾인다
                        continue;
                    }
                    if (hf > d) {   // 점층 — 중앙으로 갈수록 낮아진다 (귀솟음의 결)
                        put(world, pad, gx - sf * d, y + rise, gz, Material.DEEPSLATE_TILE_SLAB, tally);
                    }
                    if (hl > d) {
                        put(world, pad, gx, y + rise, gz - sl * d, Material.DEEPSLATE_TILE_SLAB, tally);
                    }
                }
            }
        }
    }

    /**
     * ★14-① 귀솟음의 <b>점층 규칙</b> — 귀에서 {@code d} 칸 안쪽이 몇 켜 들리는가.
     * 조성과 눈이 <b>이 한 식에서 갈라져 나온다</b> (7.5 계율: 손으로 두 번 적으면 어긋난다).
     * 귀(0)=2켜 · 한 칸 안(1)=1켜 · 그 안쪽=평평 — 「모서리에서 가장 크게, 중앙으로 갈수록 평평」.
     */
    public static int upturnRise(int d) {
        return d == 0 ? 2 : d == 1 ? 1 : 0;
    }

    /**
     * ★14-③ 산문 정면의 <b>3단 요철 규칙</b> — 중앙에서 {@code af}(=|f|) 칸 떨어진 자리가
     * 앞으로 몇 칸 나오는가. 중앙 17폭이 2칸 앞서고, 그 밖은 기준, 끝 구간은 물러난다(음수).
     * 대결의 두 평가가 똑같이 지목한 「중앙부 미돌출」의 처방 — 조성·눈 공동 정본.
     */
    public static int gateRelief(int af, int half) {
        return af <= 8 ? 2 : af > half - 6 ? -1 : 0;
    }

    /** 겹처마의 하단 스커트 — 몸체 둘레 한 바퀴: 안쪽 계단 켜 + 바깥 반블록 끝(모서리 들림) */
    private static void eaveRing(World world, TerraceForge.Pad pad, int cx, int y, int cz,
                                 int hf, int hl, Tally tally) {
        // ★재척도: 스커트 내밈 2→4 — 안쪽 3켜 계단(몸체 쪽이 높다) + 바깥 반블록 끝(모서리 들림)
        for (int f = -(hf + 4); f <= hf + 4; f++) {
            for (int l = -(hl + 4); l <= hl + 4; l++) {
                int aF = Math.abs(f);
                int aL = Math.abs(l);
                if (aF <= hf && aL <= hl) {
                    continue;   // 몸체 안 — 스커트 아님
                }
                int ring = Math.max(aF - hf, aL - hl);   // 1..4 (몸체 밖 몇 칸째인가)
                int x = cx + f;
                int z = cz + l;
                int ry = y + (3 - Math.min(ring, 3));    // 안쪽이 높다 — 1켜=+2 · 2켜=+1 · 3켜=+0
                if (ring == 4) {
                    put(world, pad, x, ry, z, Material.DEEPSLATE_TILE_SLAB, tally);
                } else if (aF > hf && aL > hl) {
                    put(world, pad, x, ry, z, Material.DEEPSLATE_TILES, tally);   // 모서리 대각
                } else if (aL > hl && aL - hl >= aF - hf) {
                    putRoofStair(world, pad, x, ry, z,
                            l > 0 ? org.bukkit.block.BlockFace.NORTH
                                    : org.bukkit.block.BlockFace.SOUTH, tally);
                } else {
                    putRoofStair(world, pad, x, ry, z,
                            f > 0 ? org.bukkit.block.BlockFace.WEST
                                    : org.bukkit.block.BlockFace.EAST, tally);
                }
            }
        }
        eaveUpturn(world, pad, cx, y, cz, hf + 4, hl + 4, tally);   // ★14-① 스커트 귀도 들린다
    }

    /** 지붕 계단 한 장 — 오름이 용마루를 향한다 (facing = 오름 방향 · 도보길 결) */
    private static void putRoofStair(World world, TerraceForge.Pad pad, int x, int y, int z,
                                     org.bukkit.block.BlockFace ascent, Tally tally) {
        if (!pad.contains(x, z)) {
            throw new IllegalStateException("건물 블록이 패드 밖: " + pad.spec().name()
                    + " (" + x + "," + y + "," + z + ") 지붕 계단");
        }
        if (tally.print != null) {   // ★마른 조성 — BlockData 를 만들기 전에 빠진다 (서버 없이 돈다)
            tally.print.take(x, y, z);
            return;
        }
        // ★슬라이스 9 — 기와 결 혼합 (코덱스 §④: 「검은 단일 덩어리」의 처방 · 결정론)
        Material mat = Math.floorMod(hash(0x5EA9L, x, y, z), 100) < 72
                ? Material.DEEPSLATE_TILE_STAIRS : Material.DEEPSLATE_BRICK_STAIRS;
        org.bukkit.block.data.type.Stairs data =
                (org.bukkit.block.data.type.Stairs) mat.createBlockData();
        data.setFacing(ascent);
        world.getBlockAt(x, y, z).setBlockData(data, false);
        tally.blocks++;
    }

    /**
     * 기와 면 결 — 심층암 타일 60 / 벽돌 22 / 균열 타일 13 / 균열 벽돌 5 (코덱스 §④ 배합 ·
     * 결정론). 처마 끝 반블록·용마루는 <b>안 섞는다</b> — 윤곽선은 또렷해야 한다.
     */
    private static Material roofCube(int x, int y, int z) {
        int r = Math.floorMod((int) hash(0x5EA9L ^ 0xF00FL, x, y, z), 100);
        if (r < 60) {
            return Material.DEEPSLATE_TILES;
        }
        if (r < 82) {
            return Material.DEEPSLATE_BRICKS;
        }
        if (r < 95) {
            return Material.CRACKED_DEEPSLATE_TILES;
        }
        return Material.CRACKED_DEEPSLATE_BRICKS;
    }

    /**
     * 공포층 — 처마 밑 받침 띠 (코덱스 §② — 입면 깊이 세 겹의 가운데 층 · 실측표 §3-b).
     * 벽선 한 칸 밖 y 에 다크오크 반블록이 돌고, 세 칸마다 통판(주두)이 박힌다.
     */
    private static void bracketRing(World world, TerraceForge.Pad pad, int cx, int y, int cz,
                                    int hf, int hl, Tally tally) {
        for (int f = -hf; f <= hf; f++) {
            for (int l : new int[]{-hl, hl}) {
                put(world, pad, cx + f, y, cz + l,
                        Math.floorMod(f, 3) == 0 ? Material.DARK_OAK_PLANKS
                                : Material.DARK_OAK_SLAB, tally);
            }
        }
        for (int l = -hl + 1; l <= hl - 1; l++) {
            for (int f : new int[]{-hf, hf}) {
                put(world, pad, cx + f, y, cz + l,
                        Math.floorMod(l, 3) == 0 ? Material.DARK_OAK_PLANKS
                                : Material.DARK_OAK_SLAB, tally);
            }
        }
    }

    /**
     * ★13b-② 현관 포치 — <b>정면 3단 요철</b>의 중심 (조율자 판정 「평평한 긴 벽」의 처방).
     * 레퍼런스의 전각은 중앙이 앞으로 나오고(자체 지붕을 인 현관) 좌우 끝이 물러난다.
     * 중앙 폭 2·ph+1 이 앞으로 {@code out} 칸 내밀고, 적주 열과 맞배 지붕을 인다.
     *
     * @param ph  포치 반폭 (중앙 폭 = 2·ph+1)
     * @param out 앞으로 내미는 칸수 (3~5)
     */
    private static void porch(World world, TerraceForge.Pad pad, int cx, int y, int cz,
                              int ph, int out, int wallH, Tally tally) {
        for (int f = -ph; f <= ph; f++) {
            for (int d = 1; d <= out; d++) {
                boolean side = Math.abs(f) == ph;
                if (side || d == out) {                 // 옆벽·앞벽 (문간은 비운다)
                    for (int dy = 1; dy <= wallH - 2; dy++) {
                        boolean doorway = d == out && Math.abs(f) <= 1 && dy <= 4;
                        if (doorway) {
                            continue;
                        }
                        boolean pillar = side && (d == 1 || d == out);
                        put(world, pad, cx + f, y + dy, cz + d,
                                pillar ? Material.STRIPPED_MANGROVE_LOG
                                        : (dy == 1 ? Material.POLISHED_ANDESITE
                                        : dy == 5 ? Material.DARK_OAK_PLANKS
                                        : Material.WHITE_TERRACOTTA), tally);
                    }
                }
            }
        }
        bracketRing(world, pad, cx, y + wallH - 3, cz + out / 2 + 1, ph + 1, out / 2 + 1, tally);
        sweepRoof(world, pad, cx, y + wallH - 2, cz + out / 2 + 1, ph, out / 2, tally);
    }

    /** 전면 열주 — 적주 간격 3 이 처마 보(다크오크)를 받친다 · 기둥 사이 격자창 (실측표 §3-b) */
    private static void colonnade(World world, TerraceForge.Pad pad, int cx, int y, int cz,
                                  int hf, int hl, int wallH, int doorHalf, Tally tally) {
        int z = cz + hl;
        for (int f = -hf; f <= hf; f++) {
            // ★14-② 적주를 굵게 — 간격 6 은 실측(사람 단위)이라 지키되 <b>기둥 자체를 두 칸</b>으로
            //   (대결: 코덱스 문루의 적목 비중이 우리보다 높아 훨씬 따뜻했다 · 격차 ③의 처방)
            boolean pillar = Math.floorMod(f + hf, 6) <= 1;
            if (pillar) {
                for (int dy = 1; dy < wallH; dy++) {
                    put(world, pad, cx + f, y + dy, z, Material.STRIPPED_MANGROVE_LOG, tally);
                }
            } else if (Math.abs(f) > doorHalf) {
                for (int wy = 3; wy <= Math.min(6, wallH - 2); wy++) {
                    put(world, pad, cx + f, y + wy, z, Material.GLASS_PANE, tally);
                }
            }
            put(world, pad, cx + f, y + wallH, z, Material.DARK_OAK_PLANKS, tally);   // 처마 보
        }
    }

    /**
     * 본전 — <b>캠퍼스에서 가장 큰 몸</b>: 월대({@code SectBuilder.podiumHall} 문법 — 돌기단이
     * 들어올린다) 위 2층 중루. ★핵심 전각의 적: 적목 기둥 + 상단 적 띠 (조닝 3색의 「적」).
     */
    private static void mainHall(World world, TerraceForge.Pad pad, int cx, int cz, Tally tally) {
        // ★슬라이스 8 재척도 ×2: 전면 73 + 처마 81 · 깊이 27 · 월대 5 · 총고 ~44 (실측표 개정판)
        int y = pad.y();
        int hf = 36;
        int hl = 13;
        // 월대 — 높이 5 (3켜의 재척도) + 남계단 폭 15 · ★상단 테는 밝은 수평선 (기단 — 코덱스 §④)
        for (int f = -hf - 6; f <= hf + 6; f++) {
            for (int l = -hl - 6; l <= hl + 6; l++) {
                for (int k = 1; k <= 5; k++) {
                    boolean rim = k == 5 && (Math.abs(f) == hf + 6 || Math.abs(l) == hl + 6);
                    put(world, pad, cx + f, y + k, cz + l,
                            rim ? Material.POLISHED_ANDESITE : Material.STONE_BRICKS, tally);
                }
            }
        }
        for (int i = 0; i < 5; i++) {
            for (int l = -7; l <= 7; l++) {
                put(world, pad, cx + l, y + 5 - i, cz + hl + 7 + i, Material.STONE_BRICKS, tally);
            }
        }
        // ★13a-5 월대 3구역 — 중앙 계단 곁을 화단과 난간으로 가른다 (빈 스케일 메우기)
        for (int side : new int[]{-1, 1}) {
            for (int l = 10; l <= 22; l++) {
                int px = cx + side * l;
                put(world, pad, px, y + 5, cz + hl + 4, Material.MOSS_BLOCK, tally);
                put(world, pad, px, y + 6, cz + hl + 4,
                        Math.floorMod(l, 3) == 0 ? Material.AZALEA : Material.SHORT_GRASS, tally);
                put(world, pad, px, y + 6, cz + hl + 6, Material.STONE_BRICK_WALL, tally);   // 난간 띠
            }
            put(world, pad, cx + side * 9, y + 6, cz + hl + 5, Material.STONE_BRICKS, tally);
            put(world, pad, cx + side * 9, y + 7, cz + hl + 5, Material.LANTERN, tally);
        }
        int base = y + 5;
        // 1층 — 백벽 + 적목 모서리 + 상단 적 띠 · 남면 삼문 (높이 6)
        for (int f = -hf; f <= hf; f++) {
            for (int l = -hl; l <= hl; l++) {
                int x = cx + f;
                int z = cz + l;
                put(world, pad, x, base, z, Material.SPRUCE_PLANKS, tally);
                boolean edge = Math.abs(f) == hf || Math.abs(l) == hl;
                if (!edge) {
                    continue;
                }
                boolean corner = Math.abs(f) == hf && Math.abs(l) == hl;
                for (int dy = 1; dy <= 12; dy++) {
                    // ★슬라이스 9 — 기단·중방·창 리듬 (입면 층위 · plasterHall 과 같은 키트)
                    Material m = corner ? Material.MANGROVE_LOG
                            : (dy == 1 ? Material.POLISHED_ANDESITE
                            : dy == 6 ? Material.DARK_OAK_PLANKS
                            : dy >= 11 ? Material.RED_TERRACOTTA : Material.WHITE_TERRACOTTA);
                    put(world, pad, x, base + dy, z, m, tally);
                }
                int along = Math.abs(l) == hl ? f : l;
                if (!corner && Math.floorMod(along, 3) == 1) {
                    for (int wy : new int[]{3, 4, 8, 9}) {
                        put(world, pad, x, base + wy, z, Material.GLASS_PANE, tally);
                    }
                }
            }
        }
        for (int d : new int[]{-8, 0, 8}) {
            for (int l = d - 1; l <= d + 1; l++) {
                for (int dy = 1; dy <= 6; dy++) {
                    put(world, pad, cx + l, base + dy, cz + hl, Material.AIR, tally);
                }
            }
        }
        // 퇴칸 회랑 — 월대 위 전면 열주(간격 4)가 처마 보를 받친다 (실측표 §3-b).
        // ★13b-② 정면 3단 요철: 중앙(|f|≤10)은 포치가 앞으로 나오고, 끝(|f|>hf-8)은 두 칸
        //   물러나며, 그 사이가 기준선 — 60칸 곧은 벽이 세 켜로 갈린다.
        for (int f = -hf; f <= hf; f += 4) {
            int zOff = Math.abs(f) > hf - 8 ? 0 : 2;   // 끝은 후퇴 (열주가 뒤로)
            for (int dy = 1; dy <= 11; dy++) {
                put(world, pad, cx + f, base + dy, cz + hl + zOff, Material.STRIPPED_MANGROVE_LOG, tally);
            }
        }
        for (int f = -hf; f <= hf; f++) {
            int zOff = Math.abs(f) > hf - 8 ? 0 : 2;
            put(world, pad, cx + f, base + 12, cz + hl + zOff, Material.DARK_OAK_PLANKS, tally);
        }
        porch(world, pad, cx, base, cz + hl, 10, 5, 13, tally);   // 중앙 현관 — 21폭이 5칸 내민다
        // 겹처마 하단 — 몸체+회랑을 덮는 스커트 (+공포 띠 — 슬라이스 9).
        // ★13a-5: 처마를 좌우로 더 내밀어(hf+3) 하층 지붕이 <b>행랑처럼 수평으로</b> 뻗는다
        //   (레퍼런스 4·7호의 수평 실루엣 — 처마 2~4 추가 돌출).
        bracketRing(world, pad, cx, base + 11, cz, hf + 3, hl + 3, tally);
        eaveRing(world, pad, cx, base + 12, cz, hf + 3, hl + 2, tally);
        // 2층 — 들인 중루 + 상단 팔작
        int hf2 = hf - 6;
        int hl2 = hl - 4;
        for (int f = -hf2; f <= hf2; f++) {
            for (int l = -hl2; l <= hl2; l++) {
                int x = cx + f;
                int z = cz + l;
                boolean edge = Math.abs(f) == hf2 || Math.abs(l) == hl2;
                put(world, pad, x, base + 12, z, Material.SPRUCE_PLANKS, tally);
                if (!edge) {
                    continue;
                }
                boolean corner = Math.abs(f) == hf2 && Math.abs(l) == hl2;
                for (int dy = 13; dy <= 22; dy++) {
                    Material m = corner ? Material.MANGROVE_LOG
                            : (dy >= 21 ? Material.RED_TERRACOTTA : Material.WHITE_TERRACOTTA);
                    put(world, pad, x, base + dy, z, m, tally);
                }
                if (!corner && Math.floorMod(f * 3 + l * 5, 4) == 0) {
                    for (int wy = 16; wy <= 18; wy++) {
                        put(world, pad, x, base + wy, z, Material.GLASS_PANE, tally);
                    }
                }
            }
        }
        bracketRing(world, pad, cx, base + 21, cz, hf2 + 1, hl2 + 1, tally);   // 공포 — 상층 처마 밑
        sweepRoof(world, pad, cx, base + 22, cz, hf2, hl2, tally);
        // ★13a-5 중앙 3칸 높임 — 좌우가 낮고 가운데가 솟는 위계 (레퍼런스 7호의 정면 리듬)
        for (int f = -4; f <= 4; f++) {
            for (int l = -3; l <= 3; l++) {
                if (Math.abs(f) + Math.abs(l) > 5) {
                    continue;
                }
                boolean edge = Math.abs(f) == 4 || Math.abs(l) == 3;
                put(world, pad, cx + f, base + 24, cz + l,
                        edge ? Material.DEEPSLATE_TILE_SLAB : roofCube(cx + f, base + 24, cz + l), tally);
            }
        }
        for (int f = -3; f <= 3; f++) {
            put(world, pad, cx + f, base + 25, cz,
                    Math.abs(f) == 3 ? Material.DEEPSLATE_TILE_WALL
                            : Material.DEEPSLATE_TILE_SLAB, tally);   // 중앙 용마루 + 치미
        }
        put(world, pad, cx - hf, base + 9, cz + hl + 3, Material.LANTERN, tally);
        put(world, pad, cx + hf, base + 9, cz + hl + 3, Material.LANTERN, tally);
        put(world, pad, cx, base + 8, cz + hl, Material.DARK_OAK_PLANKS, tally);   // 빈 현판
        tally.halls++;
    }

    /**
     * 행각(行閣) — <b>광장을 두르는 개방 열주 복도</b> (슬라이스 9 · 코덱스 개선 1·4:
     * 빈 광장 해체 + 침봉과 전각 사이의 중간 크기). 남북으로 길게, 기둥 간격 4(인간 단위),
     * 맞배 반블록 지붕. 양쪽이 열려 있어 통행을 막지 않는다 — 이웃 칸 개구·계단 어귀와
     * 부딪치지 않게 배치는 {@link #parts} 가 정한다.
     */
    private static void cloister(World world, TerraceForge.Pad pad, int x0, int z0, int z1, Tally tally) {
        int y = pad.y();
        int x1 = x0 + 4;   // 폭 5
        for (int z = z0; z <= z1; z++) {
            if (Math.floorMod(z - z0, 4) == 0) {
                for (int x : new int[]{x0, x1}) {
                    for (int dy = 1; dy <= 4; dy++) {
                        put(world, pad, x, y + dy, z, Material.SPRUCE_LOG, tally);
                    }
                }
            }
            // 맞배 지붕 — ★12-③ 두 겹 (원거리에서 「선」으로 읽히던 것의 처방): 처마 내밈
            //   반블록(y+4) → 가장자리 반블록 + 물매 계단(y+5) → 중심 통기와 + 용마루 반블록(y+6)
            put(world, pad, x0 - 1, y + 4, z, Material.DEEPSLATE_TILE_SLAB, tally);
            put(world, pad, x1 + 1, y + 4, z, Material.DEEPSLATE_TILE_SLAB, tally);
            put(world, pad, x0, y + 5, z, Material.DEEPSLATE_TILE_SLAB, tally);
            put(world, pad, x1, y + 5, z, Material.DEEPSLATE_TILE_SLAB, tally);
            putRoofStair(world, pad, x0 + 1, y + 5, z, org.bukkit.block.BlockFace.EAST, tally);
            putRoofStair(world, pad, x1 - 1, y + 5, z, org.bukkit.block.BlockFace.WEST, tally);
            put(world, pad, x0 + 2, y + 5, z, roofCube(x0 + 2, y + 5, z), tally);
            put(world, pad, x0 + 2, y + 6, z, Material.DEEPSLATE_TILE_SLAB, tally);
            if (Math.floorMod(z - z0, 8) == 4) {
                put(world, pad, x0 + 2, y + 3, z, Material.LANTERN, tally);   // 복도 등롱
            }
        }
        tally.cloisters++;
    }

    /**
     * 등롱 열주 — 광장 축선을 따라 반복되는 인간 크기 사물 (슬라이스 9 · 코덱스 §⑤:
     * 웅장함은 인간 단위의 <b>누적</b>에서 나온다). 석전 기둥 2 + 등롱, 6칸 주기.
     */
    private static void lanternRow(World world, TerraceForge.Pad pad, int x, int z0, int z1, Tally tally) {
        int y = pad.y();
        for (int z = z0; z <= z1; z += 6) {
            put(world, pad, x, y + 1, z, Material.STONE_BRICKS, tally);
            put(world, pad, x, y + 2, z, Material.STONE_BRICKS, tally);
            put(world, pad, x, y + 3, z, Material.LANTERN, tally);
        }
    }

    /** 정자 — 침엽 네 기둥 + 흑기와 모임지붕, 사방이 열려 있다 (레퍼런스 6호의 대칭 소품) */
    private static void pavilion(World world, TerraceForge.Pad pad, int cx, int cz, int half, Tally tally) {
        // ★재척도 — 기둥 높이 8 · 13×13 (half 6)
        int y = pad.y();
        for (int f : new int[]{-half, half}) {
            for (int l : new int[]{-half, half}) {
                for (int dy = 1; dy <= 8; dy++) {
                    put(world, pad, cx + f, y + dy, cz + l, Material.SPRUCE_LOG, tally);
                }
            }
        }
        sweepRoof(world, pad, cx, y + 8, cz, half, half, tally);
        put(world, pad, cx, y + 6, cz, Material.LANTERN, tally);
        tally.pavilions++;
    }

    /** 모래 마당 — 안쪽을 모래로 재포장 (테두리 4칸은 박석 그대로 · 지면 일이라 부품이 아니다) */
    private static void sandField(World world, TerraceForge.Pad pad, Tally tally) {
        int y = pad.y();
        for (int x = pad.x0() + 8; x <= pad.x1() - 8; x++) {
            for (int z = pad.zN() + 8; z <= pad.zS() - 8; z++) {
                put(world, pad, x, y, z,
                        Math.floorMod(x * 7 + z * 13, 5) == 0 ? Material.SMOOTH_SANDSTONE
                                : Material.SAND, tally);
            }
        }
    }

    /** 목인 줄 — 동서로 5칸씩 벌린다 (줄의 남북 자리는 구역이 정한다 — 계단 통로 회피) */
    private static void dummyRow(World world, TerraceForge.Pad pad, int cx, int cz, int dummies, Tally tally) {
        for (int i = 0; i < dummies; i++) {
            int dx = (i - dummies / 2) * 8;   // 재척도 — 목인 간격 8
            woodenDummy(world, pad, cx + dx, pad.y(), cz, tally);
        }
    }

    /** 목인 — 벗긴 침엽 몸통 + 울타리 팔 ({@code SectBuilder.woodenDummy} 문법) */
    private static void woodenDummy(World world, TerraceForge.Pad pad, int x, int y, int z, Tally tally) {
        for (int dy = 1; dy <= 3; dy++) {
            put(world, pad, x, y + dy, z, Material.STRIPPED_DARK_OAK_LOG, tally);
        }
        put(world, pad, x + 1, y + 2, z, Material.DARK_OAK_FENCE, tally);
        put(world, pad, x - 1, y + 2, z, Material.DARK_OAK_FENCE, tally);
        tally.dummies++;
    }

    /** 병기 시렁 【제안】 — 울타리 기둥 + 판 반블록 가로대 (전용 부품 없음 · 빨간펜 대상) */
    private static void rack(World world, TerraceForge.Pad pad, int x0, int y, int z, int len, Tally tally) {
        for (int i = 0; i <= len * 2; i++) {
            put(world, pad, x0 + i, y + 1, z, Material.SPRUCE_FENCE, tally);
            put(world, pad, x0 + i, y + 2, z, Material.DARK_OAK_SLAB, tally);
        }
        tally.racks++;
    }

    /** 창고 속 상자 — ★barrel 금지 (B-195) → chest */
    private static void chests(World world, TerraceForge.Pad pad, int cx, int y, int cz, Tally tally) {
        put(world, pad, cx - 2, y + 1, cz - 1, Material.CHEST, tally);
        put(world, pad, cx - 1, y + 1, cz - 1, Material.CHEST, tally);
        put(world, pad, cx + 1, y + 1, cz - 1, Material.CHEST, tally);
        put(world, pad, cx + 2, y + 1, cz - 1, Material.CHEST, tally);
    }

    /**
     * 정원 연못 — 포장면 높이 물 (밑은 돌 속). 정원(10)의 세 부품 중 하나 —
     * ★동편 띠(본전에서 내려오는 계단 몸체)는 비워 둔다 — 소품은 서·남·북으로.
     */
    private static void gardenPond(World world, TerraceForge.Pad pad, int cx, int cz, Tally tally) {
        int y = pad.y();
        for (int dx = -12; dx <= -5; dx++) {   // 재척도 — 연못 8×8
            for (int dz = -4; dz <= 3; dz++) {
                put(world, pad, cx + dx, y, cz + dz, Material.WATER, tally);
            }
        }
    }

    /** 정원 매화 — 벚 원목+벚잎 한 그루 (사용자 표: Cherry = 매화 대체 · 군락이 아니라 점) */
    private static void gardenPlum(World world, TerraceForge.Pad pad, int tx, int tz, Tally tally) {
        // ★재척도 — 수관 ±4 (지름 9) · 수고 ~10 (실측 12~16 의 정원 소형)
        int y = pad.y();
        for (int dy = 1; dy <= 6; dy++) {
            put(world, pad, tx, y + dy, tz, Material.CHERRY_LOG, tally);
        }
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = 6; dy <= 10; dy++) {
                    if (Math.abs(dx) + Math.abs(dz) + (dy - 6) <= 6 && (dx != 0 || dz != 0 || dy > 6)) {
                        put(world, pad, tx + dx, y + dy, tz + dz, Material.CHERRY_LEAVES, tally);
                    }
                }
            }
        }
    }

    /** 망루 — 3층 석전 탑 (레퍼런스 9호의 절벽 망루 · {@code SectBuilder.pagoda} 문법의 축소) */
    private static void watchtower(World world, TerraceForge.Pad pad, int cx, int cz, Tally tally) {
        int y = pad.y();
        int[] halves = {11, 9, 7};   // 층폭 23/19/15 (실측표 개정 · 9호 — 슬라이스 8 재척도)
        int base = y;
        for (int s = 0; s < halves.length; s++) {
            int half = halves[s];
            int wallH = s == 0 ? 8 : 7;
            for (int f = -half; f <= half; f++) {
                for (int l = -half; l <= half; l++) {
                    int x = cx + f;
                    int z = cz + l;
                    put(world, pad, x, base, z, Material.SPRUCE_PLANKS, tally);
                    boolean edge = Math.abs(f) == half || Math.abs(l) == half;
                    if (!edge) {
                        continue;
                    }
                    boolean corner = Math.abs(f) == half && Math.abs(l) == half;
                    for (int dy = 1; dy <= wallH; dy++) {
                        put(world, pad, x, base + dy, z,
                                corner ? Material.SPRUCE_LOG : Material.WHITE_TERRACOTTA, tally);
                    }
                }
            }
            if (s == 0) {
                for (int dy = 1; dy <= 4; dy++) {
                    put(world, pad, cx, base + dy, cz + half, Material.AIR, tally);
                }
            }
            eaveRing(world, pad, cx, base + wallH, cz, half, half, tally);   // 층 겹처마
            put(world, pad, cx - half, base + 1, cz + half + 1, Material.LANTERN, tally);
            base += wallH + 1;
        }
        sweepRoof(world, pad, cx, base, cz, halves[2], halves[2], tally);
        tally.towers++;
    }

    /** 공공 구역 바닥 — 모래빛 박석을 섞는다 (조닝 「공공=모래빛」 · 테두리는 석전 그대로) */
    private static void sandyRepave(World world, TerraceForge.Pad pad, Tally tally) {
        for (int x = pad.x0() + 1; x <= pad.x1() - 1; x++) {
            for (int z = pad.zN() + 1; z <= pad.zS() - 1; z++) {
                int r = Math.floorMod(x * 11 + z * 17, 10);
                if (r < 3) {
                    put(world, pad, x, pad.y(), z, Material.SMOOTH_SANDSTONE, tally);
                } else if (r < 4) {
                    put(world, pad, x, pad.y(), z, Material.SANDSTONE, tally);
                }
            }
        }
    }

    /**
     * 조경 상(相) — 소품 표({@link #decors})를 앉히고, 옹벽 면에 덩굴·지의를 점점이 찍고,
     * 패드 밖 벼랑 턱에 소나무를 심는다 (슬라이스 4). 전부 결정론 — 난수 0.
     */
    public static void decorate(World world, TerraceForge.Plan plan, TerraceForge.Pad pad, Tally tally) {
        int y = pad.y();
        for (Decor d : decors(pad)) {
            switch (d.kind()) {
                case 'M' -> {
                    plum(world, pad, d.x(), y, d.z(), tally);
                    tally.plums++;
                }
                case 'B' -> {
                    put(world, pad, d.x(), y + 1, d.z(), Material.RED_BANNER, tally);
                    tally.props++;
                }
                case 'W' -> {
                    put(world, pad, d.x(), y + 1, d.z(), Material.WHITE_BANNER, tally);
                    tally.props++;
                }
                case 'F' -> {
                    put(world, pad, d.x(), y + 1, d.z(), Material.CAMPFIRE, tally);
                    tally.props++;
                }
                case 'C' -> {                                    // 상자 더미 — barrel 금지 → chest+반블록 (재척도 4×3)
                    for (int i = 0; i <= 3; i++) {
                        put(world, pad, d.x() + i, y + 1, d.z(), Material.CHEST, tally);
                        put(world, pad, d.x() + i, y + 1, d.z() + 1,
                                i == 1 || i == 2 ? Material.CHEST : Material.DARK_OAK_SLAB, tally);
                        put(world, pad, d.x() + i, y + 1, d.z() + 2, Material.DARK_OAK_SLAB, tally);
                    }
                    put(world, pad, d.x() + 1, y + 2, d.z(), Material.DARK_OAK_SLAB, tally);
                    put(world, pad, d.x() + 2, y + 2, d.z(), Material.DARK_OAK_SLAB, tally);
                    tally.props++;
                }
                case 'L' -> {                                    // 빨래줄 — 기둥 둘 + 실 (재척도 ±4)
                    for (int px : new int[]{d.x() - 4, d.x() + 4}) {
                        put(world, pad, px, y + 1, d.z(), Material.SPRUCE_FENCE, tally);
                        put(world, pad, px, y + 2, d.z(), Material.SPRUCE_FENCE, tally);
                        put(world, pad, px, y + 3, d.z(), Material.SPRUCE_FENCE, tally);
                    }
                    for (int px = d.x() - 3; px <= d.x() + 3; px++) {
                        put(world, pad, px, y + 3, d.z(), Material.TRIPWIRE, tally);
                    }
                    tally.props++;
                }
                case 'P' -> {                                    // 밭 — 물 한 칸 + 경작지 + 밀 (재척도 10×8)
                    for (int fx = d.x(); fx <= d.x() + 9; fx++) {
                        for (int fz = d.z(); fz <= d.z() + 7; fz++) {
                            if (fx == d.x() && fz == d.z()) {
                                put(world, pad, fx, y, fz, Material.WATER, tally);
                                continue;
                            }
                            put(world, pad, fx, y, fz, Material.FARMLAND, tally);
                            put(world, pad, fx, y + 1, fz, Material.WHEAT, tally);
                        }
                    }
                    tally.props++;
                }
                default -> {
                }
            }
        }
        wallGreen(world, plan, pad, tally);
        for (int[] spot : pineSpots(pad)) {
            pine(world, plan, pad, spot[0], spot[1], tally);
        }
    }

    /** 매화 한 그루 — 벚 원목 + 벚잎 관 (정원 것과 같은 문법 · 군락 금지, 점을 찍는다) */
    private static void plum(World world, TerraceForge.Pad pad, int tx, int y, int tz, Tally tally) {
        // ★재척도 — 수관 ±4 · 수고 ~10 (실측 12~16 · decorBoxes 'M' ±4 와 한 자)
        for (int dy = 1; dy <= 6; dy++) {
            put(world, pad, tx, y + dy, tz, Material.CHERRY_LOG, tally);
        }
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = 6; dy <= 10; dy++) {
                    if (Math.abs(dx) + Math.abs(dz) + (dy - 6) <= 6 && (dx != 0 || dz != 0 || dy > 6)) {
                        put(world, pad, tx + dx, y + dy, tz + dz, Material.CHERRY_LEAVES, tally);
                    }
                }
            }
        }
    }

    /**
     * 옹벽 덩굴·이끼·지의 — 둘레 옹벽 면 바깥 공기 열에 결정론 해시로 점점이 (밀도 ~7% —
     * 자연 70 비율: 뒤덮지 않는다). 재료는 조경 몫이라 유출 눈 대상 밖 ({@link #landscapePalette}).
     */
    private static void wallGreen(World world, TerraceForge.Plan plan, TerraceForge.Pad pad, Tally tally) {
        int[][] sides = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
        for (int[] n : sides) {
            int fx0 = n[0] == 0 ? pad.x0() : (n[0] < 0 ? pad.x0() : pad.x1());
            int fx1 = n[0] == 0 ? pad.x1() : (n[0] < 0 ? pad.x0() : pad.x1());
            int fz0 = n[1] == 0 ? pad.zN() : (n[1] < 0 ? pad.zN() : pad.zS());
            int fz1 = n[1] == 0 ? pad.zS() : (n[1] < 0 ? pad.zN() : pad.zS());
            for (int wx = fx0; wx <= fx1; wx++) {
                for (int wz = fz0; wz <= fz1; wz++) {
                    int ox = wx + n[0];
                    int oz = wz + n[1];
                    if (onAnyPad(plan, ox, oz)) {
                        continue;
                    }
                    for (int yy = pad.y() - 1; yy >= pad.y() - 12; yy--) {
                        Material face = world.getBlockAt(wx, yy, wz).getType();
                        if (face.isAir() || !world.getBlockAt(ox, yy, oz).getType().isAir()) {
                            break;   // 옹벽이 끝났거나 지형에 닿았다
                        }
                        int r = (int) Math.floorMod(hash(0x62EE7L, wx, yy, wz), 100);
                        if (r < 5) {
                            org.bukkit.block.data.MultipleFacing v =
                                    (org.bukkit.block.data.MultipleFacing) Material.VINE.createBlockData();
                            v.setFace(faceOf(-n[0], -n[1]), true);
                            world.getBlockAt(ox, yy, oz).setBlockData(v, false);
                            tally.vines++;
                        } else if (r < 7) {
                            org.bukkit.block.data.MultipleFacing g =
                                    (org.bukkit.block.data.MultipleFacing) Material.GLOW_LICHEN.createBlockData();
                            g.setFace(faceOf(-n[0], -n[1]), true);
                            world.getBlockAt(ox, yy, oz).setBlockData(g, false);
                            tally.vines++;
                        }
                    }
                }
            }
        }
    }

    private static org.bukkit.block.BlockFace faceOf(int dx, int dz) {
        if (dx < 0) {
            return org.bukkit.block.BlockFace.WEST;
        }
        if (dx > 0) {
            return org.bukkit.block.BlockFace.EAST;
        }
        return dz < 0 ? org.bukkit.block.BlockFace.NORTH : org.bukkit.block.BlockFace.SOUTH;
    }

    /**
     * 벼랑 턱 소나무 자리 【제안】 — 남쪽 접근 시야·다리에서 보이는 벼랑 위주.
     * 패드 밖 지형이라 조성 때 실지형·통로를 재고 안 맞으면 조용히 접는다 (자연은 강요하지 않는다).
     */
    private static List<int[]> pineSpots(TerraceForge.Pad pad) {
        int cx = pad.x0() + pad.spec().width() / 2;
        int cz = pad.zN() + pad.spec().depth() / 2;
        return switch (pad.spec().zone()) {
            case 1 -> List.of(new int[]{cx - 12, pad.zS() + 5}, new int[]{cx + 10, pad.zS() + 6});
            case 3 -> List.of(new int[]{pad.x0() - 4, cz}, new int[]{pad.x0() - 5, cz + 9});
            case 16 -> List.of(new int[]{pad.x1() + 4, cz - 3});
            case 4 -> List.of(new int[]{pad.x0() - 4, cz + 2});
            case 10 -> List.of(new int[]{pad.x0() - 4, cz - 2});
            case 11 -> List.of(new int[]{pad.x1() + 4, cz + 2});
            case 13 -> List.of(new int[]{pad.x0() - 4, cz - 2}, new int[]{pad.x1() + 4, cz});
            case 19 -> List.of(new int[]{pad.x1() + 3, cz + 3}, new int[]{cx + 2, pad.zS() + 4});
            case 20 -> List.of(new int[]{pad.x1() + 3, cz - 2}, new int[]{cx - 2, pad.zN() - 4});
            case 105 -> List.of(new int[]{pad.x0() - 3, cz + 2}, new int[]{cx - 2, pad.zS() + 4});
            default -> List.of();
        };
    }

    /**
     * 소나무 하나 — 벗긴 몸통이 아니라 껍질 통나무({@code SPRUCE_WOOD} — 건물 재료
     * {@code SPRUCE_LOG} 와 갈라 유출 눈이 조경을 오인하지 않는다) + 잎. 자리가 패드·통로·다리
     * 위면 접는다.
     */
    private static void pine(World world, TerraceForge.Plan plan, TerraceForge.Pad pad,
                             int x, int z, Tally tally) {
        if (onAnyPad(plan, x, z) || laneCrosses(plan, x, z) || onBridge(plan, x, z)) {
            return;
        }
        int g = groundTop(world, x, z, pad.y() + 24);
        if (g <= world.getMinHeight()) {
            return;
        }
        // ★12.5 — 산군 식생과 같은 우산꼴 문법 (조율자 판정: 「깃발 꽂은 막대」 금지).
        //   벼랑 턱이라 산의 대수보다는 작게: 높이 5~7 · 폭 7~9 · 층 2 (폭:높이 ≈ 1.2)
        long hh = hash(0x917E, x, 0, z);
        int h = 5 + (int) Math.floorMod(hh, 3);
        int rad = 3 + (int) Math.floorMod(hh >> 8, 2);
        for (int dy = 1; dy <= h; dy++) {
            world.getBlockAt(x, g + dy, z).setType(Material.SPRUCE_WOOD, false);
        }
        // ★12.6 — 층마다 두 켜 (얇은 파라솔 금지) + 잎 톤 혼합 (그늘·볕)
        for (int t = 0; t < 2; t++) {
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
                        world.getBlockAt(x + dx, ty, z + dz)
                                .setType(leafTone(x + dx, ty, z + dz,
                                        rr - Math.abs(dx) - Math.abs(dz), k), false);
                    }
                }
            }
        }
        world.getBlockAt(x, g + h + 1, z).setType(Material.SPRUCE_LEAVES, false);
        tally.pines++;
    }

    /** ★12.6 잎 톤 — 그늘(진달래 잎)·기본(spruce)·볕 반점 (산군 문법과 한 결) */
    private static Material leafTone(int x, int y, int z, int edge, int k) {
        int r = (int) Math.floorMod(hash(0x1EAF5L, x, y, z), 100);
        int dark = 22 + (k == 0 ? 20 : 0) + Math.min(18, Math.max(0, edge) * 9);
        if (r < dark) {
            return Material.AZALEA_LEAVES;
        }
        if (r >= 92 && k == 1) {
            return Material.FLOWERING_AZALEA_LEAVES;
        }
        return Material.SPRUCE_LEAVES;
    }

    private static int groundTop(World world, int x, int z, int from) {
        int yy = from;
        int min = world.getMinHeight();
        while (yy > min && world.getBlockAt(x, yy, z).getType().isAir()) {
            yy--;
        }
        return yy;
    }

    private static long hash(long salt, int x, int y, int z) {
        long h = salt ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL)
                ^ (z * 0x165667B19E3779F9L);
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return h;
    }

    /** 조경 재료 — 유출 눈 대상 밖 (패드 밖이 정상인 것들 · 눈이 이 표와 건물 표의 불교집합을 잰다) */
    public static Set<Material> landscapePalette() {
        return EnumSet.of(
                Material.VINE, Material.GLOW_LICHEN, Material.MOSS_BLOCK,
                Material.SPRUCE_WOOD, Material.SPRUCE_LEAVES,
                Material.AZALEA_LEAVES, Material.FLOWERING_AZALEA_LEAVES,   // ★12.6 잎 톤
                Material.CHERRY_LOG, Material.CHERRY_LEAVES,
                Material.RED_BANNER, Material.WHITE_BANNER, Material.CAMPFIRE,
                Material.TRIPWIRE, Material.FARMLAND, Material.WHEAT);
    }

    /**
     * 유출 눈이 찾는 건물 재료 표 — 눈이 조경 표·<b>암벽 표</b>와 겹치지 않는지 잰다.
     *
     * <p>★15 수리: 늑재(자연 암반 흉내)가 {@link SpireField#stone} 을 쓰게 되면서 웜톤 사암이
     * 패드 밖에 섰고, 유출 눈이 그것을 「건물이 패드를 넘었다」로 잡았다 (오탐 8건). 산의 것은
     * 스캔 밖이다 — <b>건물 전용 재료만 남긴다</b> (백벽·적목·기와·유리는 그대로 잡힌다).
     */
    public static Set<Material> leakScanMats() {
        EnumSet<Material> out = EnumSet.copyOf(BUILDING_MATS);
        out.removeAll(SpireField.rockMats());
        return out;
    }

    /** 그 열을 계단 몸체가 지나는가 — 담·소품이 통로를 막지 않게 (같은 forge 라 lane.covers 를 읽는다) */
    private static boolean laneCrosses(TerraceForge.Plan plan, int x, int z) {
        for (TerraceForge.StairLane lane : plan.lanes()) {
            if (lane.covers(x, z)) {
                return true;
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 가드 + 팔레트
    // ═══════════════════════════════════════════════════════════════════

    /** 모든 블록이 지나는 문 — 패드 밖이면 던진다 (건물은 패드 위에만 · 계율 #4) */
    private static void put(World world, TerraceForge.Pad pad, int x, int y, int z,
                            Material m, Tally tally) {
        if (!pad.contains(x, z)) {
            throw new IllegalStateException("건물 블록이 패드 밖: " + pad.spec().name()
                    + " (" + x + "," + y + "," + z + ") " + m);
        }
        if (tally.print != null) {   // ★마른 조성 — 발자국만 적는다 (상자와 실물이 한 식)
            tally.print.take(x, y, z);
            return;
        }
        world.getBlockAt(x, y, z).setType(m, false);
        tally.blocks++;
    }

    /** 배치기의 재료 전부 — 눈이 금지 재료(B-195: barrel·light)를 이 표로 잰다. */
    public static Set<Material> palette() {
        return EnumSet.of(
                Material.WHITE_TERRACOTTA, Material.RED_TERRACOTTA,
                Material.SPRUCE_LOG, Material.SPRUCE_PLANKS, Material.SPRUCE_FENCE,
                Material.DARK_OAK_PLANKS, Material.DARK_OAK_FENCE, Material.DARK_OAK_SLAB,
                Material.STRIPPED_DARK_OAK_LOG, Material.MANGROVE_LOG, Material.STRIPPED_MANGROVE_LOG,
                Material.DEEPSLATE_TILES, Material.DEEPSLATE_TILE_SLAB, Material.DEEPSLATE_TILE_STAIRS,
                Material.DEEPSLATE_TILE_WALL, Material.STONE_BRICKS, Material.POLISHED_ANDESITE, Material.GLASS_PANE,
                Material.DEEPSLATE_BRICKS, Material.CRACKED_DEEPSLATE_TILES,          // ★9 — 기와 결 혼합
                Material.CRACKED_DEEPSLATE_BRICKS, Material.DEEPSLATE_BRICK_STAIRS,
                Material.SAND, Material.SMOOTH_SANDSTONE, Material.SANDSTONE,
                Material.CHERRY_LOG, Material.CHERRY_LEAVES, Material.WATER,
                Material.CHEST, Material.LANTERN, Material.AIR);
    }

    /** 패드 밖 유출 검수가 찾는 건물 재료 — 테라스 제 것(석전 벽·등롱)은 뺀다 */
    private static final Set<Material> BUILDING_MATS = EnumSet.of(
            Material.WHITE_TERRACOTTA, Material.RED_TERRACOTTA,
            Material.SPRUCE_LOG, Material.SPRUCE_PLANKS, Material.SPRUCE_FENCE,
            Material.DARK_OAK_PLANKS, Material.DARK_OAK_FENCE, Material.DARK_OAK_SLAB,
            Material.STRIPPED_DARK_OAK_LOG, Material.MANGROVE_LOG, Material.STRIPPED_MANGROVE_LOG,
            Material.DEEPSLATE_TILES, Material.DEEPSLATE_TILE_SLAB, Material.DEEPSLATE_TILE_STAIRS,
            Material.DEEPSLATE_TILE_WALL, Material.GLASS_PANE,
            Material.DEEPSLATE_BRICKS, Material.CRACKED_DEEPSLATE_TILES,          // ★9 — 기와 결 혼합
            Material.CRACKED_DEEPSLATE_BRICKS, Material.DEEPSLATE_BRICK_STAIRS,
            Material.SAND, Material.SMOOTH_SANDSTONE, Material.SANDSTONE, Material.CHEST);

    /**
     * ★15 유출 스캔이 실제로 쓰는 표 — {@link #BUILDING_MATS} 에서 <b>암벽 재료를 뺀</b> 것.
     * 늑재가 산의 재료로 패드 밖에 서므로, 빼지 않으면 산을 건물로 오인한다 (실기동 오탐 8건).
     * ★선언 자리 주의: {@code BUILDING_MATS} <b>뒤</b>여야 한다 (정적 초기화는 선언 순서다).
     */
    private static final Set<Material> LEAK_SCAN = leakScanMats();
}
