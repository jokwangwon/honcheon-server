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
    }

    // ═══════════════════════════════════════════════════════════════════
    // 구역 배치 — 패드 하나 = 한 걸음 (TickBudget 이 사이를 문다)
    // ═══════════════════════════════════════════════════════════════════

    /** 구역 하나의 건물을 앉힌다 — 계단참(101~103)은 소품 없음. 남향(문은 +z 쪽). */
    public static void buildZone(World world, TerraceForge.Plan plan, TerraceForge.Pad pad, Tally tally) {
        int cx = pad.x0() + pad.spec().width() / 2;
        int cz = pad.zN() + pad.spec().depth() / 2;
        switch (pad.spec().zone()) {
            case 1 -> {                                          // 산문 — 큰 문루 (레퍼런스 1호)
                sandyRepave(world, pad, tally);
                gate(world, pad, cx, cz, 5, tally);
            }
            case 2 -> {                                          // 외원 — 좌우 대칭 정자 · 중앙은 여백 (원작 11호)
                sandyRepave(world, pad, tally);
                pavilion(world, pad, cx - 9, cz + 4, 2, tally);   // ★생활 하 계단 띠(z −6~+? — sz 144)의 남쪽으로
                pavilion(world, pad, cx + 9, cz + 4, 2, tally);
            }
            case 6 -> {                                          // 종문 — 둘째 문루 (작다)
                sandyRepave(world, pad, tally);
                gate(world, pad, cx, cz, 4, tally);
            }
            case 16 -> {                                         // 측문 — 남북 담에 낸 동향 작은 문
                // ★통행이 동서다 (창고 램프 → 동쪽 바깥) — 담은 남북으로 서고 문은 동을 본다.
                //   문루 x 는 램프(13칸) 동쪽 평지: cx+3.
                gateEW(world, pad, cx + 3, cz, 2, tally);
                wallNS(world, plan, pad, cx + 3, cz, tally);
            }
            case 3 -> yard(world, pad, cx, cz, true, 4, 8, tally);    // 연무장 하 — 목인 줄은 남쪽 (외원·강당 계단 회랑 회피)
            case 14 -> yard(world, pad, cx, cz, true, 3, -2, tally);  // 연무장 상
            case 7 -> yard(world, pad, cx, cz, false, 3, -2, tally);  // 훈련장 중 — 박석 마당
            case 4 -> {                                          // 강당·무기고 — 큰 회벽 홀 + 병기 시렁
                plasterHall(world, pad, cx, cz, 5, 3, true, false, tally);
                rack(world, pad, cx - 3, pad.y(), cz + 1, 3, tally);
            }
            case 5 -> {                                          // 생활 하 — 숙소·식당 두 채
                plasterHall(world, pad, cx - 6, cz, 3, 2, true, false, tally);
                plasterHall(world, pad, cx + 6, cz, 3, 2, true, false, tally);
            }
            case 8 -> plasterHall(world, pad, cx, cz, 4, 2, true, false, tally);   // 생활 중
            case 17 -> {                                         // 물자 창고 — 창 없는 홀 + 상자 (barrel 금지 → chest)
                plasterHall(world, pad, cx, cz, 4, 3, false, false, tally);
                chests(world, pad, cx, pad.y(), cz, tally);
            }
            case 9 -> mainHall(world, pad, cx, cz, tally);       // 본전 — 적벽 2층 중루 (핵심 전각)
            case 12 -> plasterHall(world, pad, cx, cz - 1, 5, 2, true, false, tally);   // 장로회 — 중형 홀 + 남쪽 마당
            case 13 -> {                                         // 정상 — 소형 사당 + 정자 (건축 최소화)
                sandyRepave(world, pad, tally);
                plasterHall(world, pad, cx - 3, cz, 2, 2, false, false, tally);
                pavilion(world, pad, cx + 5, cz, 1, tally);
            }
            case 19 -> pavilion(world, pad, cx + 3, cz, 2, tally);   // 절벽 전망대 — 정자 하나뿐 (벼랑 끝 · 절제)
            case 20 -> {                                         // 부속 암자 — 소형 암자 + 빈 마당 (레퍼런스 9호)
                plasterHall(world, pad, cx + 2, cz, 3, 2, true, false, tally);
            }
            case 10 -> garden(world, pad, cx, cz, tally);        // 장문인 정원 — 연못·정자·매화
            case 11 -> watchtower(world, pad, cx, cz + 5, tally);   // 망루 — 탑은 계단 회랑(북편) 남쪽에 (2.5 보행 단차의 수리)
            default -> {
                // 계단참 101·102·103 — 소품 없음 (지나는 자리)
            }
        }
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
        return switch (pad.spec().zone()) {
            case 1 -> List.of(new Decor('M', cx - 8, cz + 5), new Decor('M', cx - 8, cz - 5));
            case 2 -> List.of(new Decor('M', cx - 13, cz - 8), new Decor('M', cx + 8, cz - 8));
            case 6 -> List.of(new Decor('M', cx - 10, cz + 6), new Decor('M', cx + 8, cz + 6));
            case 9 -> List.of(new Decor('M', cx - 14, cz + 9), new Decor('M', cx + 14, cz + 9));
            case 12 -> List.of(new Decor('M', cx - 8, cz + 4));
            case 13 -> List.of(new Decor('M', cx + 6, cz - 5));
            case 19 -> List.of(new Decor('M', cx - 4, cz - 3));
            case 20 -> List.of(new Decor('M', cx - 5, cz + 4));
            case 3 -> List.of(new Decor('B', cx - 14, pad.zN() + 2), new Decor('B', cx - 5, pad.zN() + 2),
                    new Decor('F', pad.x0() + 3, pad.zN() + 4), new Decor('F', pad.x1() - 4, pad.zN() + 4));
            case 14 -> List.of(new Decor('B', cx - 5, pad.zS() - 2), new Decor('B', cx + 5, pad.zS() - 2),
                    new Decor('F', pad.x0() + 3, pad.zN() + 3));
            case 7 -> List.of(new Decor('B', cx - 4, pad.zS() - 2), new Decor('B', cx + 4, pad.zS() - 2));
            case 17 -> List.of(new Decor('C', cx + 6, cz - 2));
            case 5 -> List.of(new Decor('L', cx, cz + 8));
            case 8 -> List.of(new Decor('L', cx - 6, cz + 4), new Decor('P', cx + 5, cz + 3));
            default -> List.of();
        };
    }

    /** 소품 발자국 상자 — {@link #decors} 에서 유도 (조성과 검증이 같은 자를 쓴다) */
    static List<int[]> decorBoxes(TerraceForge.Pad pad) {
        java.util.ArrayList<int[]> out = new java.util.ArrayList<>();
        for (Decor d : decors(pad)) {
            out.add(switch (d.kind()) {
                case 'M' -> new int[]{d.x() - 2, d.x() + 2, d.z() - 2, d.z() + 2};
                case 'C' -> new int[]{d.x(), d.x() + 2, d.z(), d.z() + 1};
                case 'L' -> new int[]{d.x() - 2, d.x() + 2, d.z(), d.z()};
                case 'P' -> new int[]{d.x(), d.x() + 4, d.z(), d.z() + 3};
                default -> new int[]{d.x(), d.x(), d.z(), d.z()};
            });
        }
        return out;
    }

    /** 검수 평탄 눈이 비켜 갈 상자 전부 — 구조물 + 소품 (포장면 위로 솟는 것들의 명세) */
    public static List<int[]> auditSkipBoxes(TerraceForge.Pad pad) {
        java.util.ArrayList<int[]> out = new java.util.ArrayList<>(structureBoxes(pad));
        out.addAll(decorBoxes(pad));
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
            for (int[] sb : auditSkipBoxes(pad)) {
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
                        for (int o = -TerraceForge.RAIL_OFF; o <= TerraceForge.RAIL_OFF; o++) {
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
     * 구역별 <b>실구조물</b> 발자국(빈틈 없이 좁게 — 처마 포함) — 계단 통로 겹침 검증용.
     * {@link #buildingBox} 는 검수 스킵용의 느슨한 상자라 여기 못 쓴다 (마당 전체를 덮는다).
     * 측문(16)의 담은 뺀다 — 담은 계단 통로를 스스로 비킨다 ({@code sideWalls} 의 laneCrosses 가드).
     */
    static List<int[]> structureBoxes(TerraceForge.Pad pad) {
        int cx = pad.x0() + pad.spec().width() / 2;
        int cz = pad.zN() + pad.spec().depth() / 2;
        return switch (pad.spec().zone()) {
            case 1 -> List.of(new int[]{cx - 7, cx + 7, cz - 1, cz + 1});
            case 2 -> List.of(new int[]{cx - 12, cx - 6, cz + 1, cz + 7},
                    new int[]{cx + 6, cx + 12, cz + 1, cz + 7});
            case 6 -> List.of(new int[]{cx - 6, cx + 6, cz - 1, cz + 1});
            // ★16 은 회전 케이스 — 담이 남북 「전장」을 달린다. 4.0 실기동: 문루 범위(z±4)만 상자에
            //   넣었다가 담 끝 칸(z169)이 평탄 위반으로 잡혔다. 담+문루 = x 세 열의 z 전장 띠 하나.
            case 16 -> List.of(new int[]{cx + 2, cx + 4, pad.zN(), pad.zS()});
            case 3 -> List.of(new int[]{cx - 11, cx + 6, cz + 8, cz + 8},    // 목인 4 (팔 포함 · 남쪽 줄)
                    new int[]{cx - 12, cx - 6, pad.zN() + 2, pad.zN() + 2}); // 시렁 (북서)
            case 14, 7 -> List.of(new int[]{cx - 6, cx + 6, cz - 2, cz - 2},
                    new int[]{cx - 3, cx + 3, pad.zS() - 2, pad.zS() - 2});
            case 4 -> List.of(new int[]{cx - 6, cx + 6, cz - 4, cz + 4});
            case 5 -> List.of(new int[]{cx - 10, cx - 2, cz - 3, cz + 3},
                    new int[]{cx + 2, cx + 10, cz - 3, cz + 3});
            case 8 -> List.of(new int[]{cx - 5, cx + 5, cz - 3, cz + 3});
            case 17 -> List.of(new int[]{cx - 5, cx + 5, cz - 4, cz + 4});
            case 9 -> List.of(new int[]{cx - 12, cx + 12, cz - 9, cz + 11});
            case 12 -> List.of(new int[]{cx - 6, cx + 6, cz - 5, cz + 3});
            case 13 -> List.of(new int[]{cx - 6, cx, cz - 3, cz + 3},
                    new int[]{cx + 3, cx + 7, cz - 2, cz + 2});
            case 10 -> List.of(new int[]{cx - 6, cx - 3, cz - 2, cz + 1},    // 연못
                    new int[]{cx - 3, cx + 1, cz + 3, cz + 7},               // 정자
                    new int[]{cx - 4, cx, cz - 8, cz - 4});                  // 매화
            case 11 -> List.of(new int[]{cx - 4, cx + 4, cz + 1, cz + 9});
            case 19 -> List.of(new int[]{cx, cx + 6, cz - 3, cz + 3});       // 정자 (동편 벼랑 쪽 — 서편은 다리 이음)
            case 20 -> List.of(new int[]{cx - 2, cx + 6, cz - 3, cz + 3});   // 암자 (동편 — 서편은 다리 이음)
            default -> List.of();
        };
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
                        if (BUILDING_MATS.contains(m)) {
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

    /** 동향 문루 — {@link #gate} 의 90°판: 기둥이 남북으로 벌고 통행이 동서다 */
    private static void gateEW(World world, TerraceForge.Pad pad, int gx, int cz, int half, Tally tally) {
        int y = pad.y();
        for (int l : new int[]{-half, -half + 1, half - 1, half}) {
            for (int dy = 1; dy <= 6; dy++) {
                put(world, pad, gx, y + dy, cz + l, Material.STONE_BRICKS, tally);
            }
        }
        for (int l = -half - 1; l <= half + 1; l++) {
            put(world, pad, gx, y + 7, cz + l, Material.POLISHED_ANDESITE, tally);
            put(world, pad, gx, y + 8, cz + l, Material.DEEPSLATE_TILES, tally);
        }
        for (int l = -half - 2; l <= half + 2; l++) {
            put(world, pad, gx - 1, y + 8, cz + l, Material.DEEPSLATE_TILE_SLAB, tally);
            put(world, pad, gx + 1, y + 8, cz + l, Material.DEEPSLATE_TILE_SLAB, tally);
        }
        put(world, pad, gx, y + 6, cz, Material.DARK_OAK_PLANKS, tally);   // 빈 현판
        put(world, pad, gx + 1, y + 1, cz - half, Material.LANTERN, tally);
        put(world, pad, gx + 1, y + 1, cz + half, Material.LANTERN, tally);
        tally.gates++;
    }

    /** 측문의 남북 담 — 문루 위아래로 패드 끝까지. 계단 통로는 담이 비킨다 */
    private static void wallNS(World world, TerraceForge.Plan plan, TerraceForge.Pad pad,
                               int gx, int cz, Tally tally) {
        int y = pad.y();
        for (int z = pad.zN(); z <= pad.zS(); z++) {
            if (Math.abs(z - cz) <= 4 || laneCrosses(plan, gx, z)) {
                continue;   // 문루 몫(처마 포함) · 계단 통로는 담이 비킨다
            }
            for (int dy = 1; dy <= 3; dy++) {
                put(world, pad, gx, y + dy, z, Material.STONE_BRICKS, tally);
            }
            put(world, pad, gx, y + 4, z, Material.DEEPSLATE_TILE_SLAB, tally);
        }
    }

    /**
     * 회벽 집 — {@code SectBuilder.plasterHall} 문법: 백벽 · 침엽 모서리 기둥 · 흑기와 모임지붕 ·
     * 가늘고 긴 세로 창 · 남문.
     */
    private static void plasterHall(World world, TerraceForge.Pad pad, int cx, int cz,
                                    int hf, int hl, boolean windows, boolean red, Tally tally) {
        int y = pad.y();
        int wallH = 4;
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
                    Material m = corner ? (red ? Material.MANGROVE_LOG : Material.SPRUCE_LOG)
                            : (red && dy == wallH ? Material.RED_TERRACOTTA : Material.WHITE_TERRACOTTA);
                    put(world, pad, x, y + dy, z, m, tally);
                }
                if (windows && !corner && Math.floorMod(f * 3 + l * 5, 4) == 0) {
                    put(world, pad, x, y + 2, z, Material.GLASS_PANE, tally);
                    put(world, pad, x, y + 3, z, Material.GLASS_PANE, tally);
                }
            }
        }
        // 남문 — 한 칸 폭, 두 칸 높이
        for (int dy = 1; dy <= 2; dy++) {
            put(world, pad, cx, y + dy, cz + hl, Material.AIR, tally);
        }
        hipRoof(world, pad, cx, y + wallH, cz, hf, hl, tally);
        put(world, pad, cx, y + wallH, cz + hl + 1, Material.LANTERN, tally);
        tally.halls++;
    }

    /** 흑기와 모임지붕 — {@code SectBuilder.tiledRoof} 문법 (능선으로 수렴) */
    private static void hipRoof(World world, TerraceForge.Pad pad, int cx, int cy, int cz,
                                int hf, int hl, Tally tally) {
        for (int i = 0; i <= Math.min(hf, hl); i++) {
            int y = cy + 1 + i;
            for (int f = -hf - 1 + i; f <= hf + 1 - i; f++) {
                put(world, pad, cx + f, y, cz - hl - 1 + i, Material.DEEPSLATE_TILES, tally);
                put(world, pad, cx + f, y, cz + hl + 1 - i, Material.DEEPSLATE_TILES, tally);
            }
            for (int l = -hl + i; l <= hl - i; l++) {
                put(world, pad, cx - hf - 1 + i, y, cz + l, Material.DEEPSLATE_TILES, tally);
                put(world, pad, cx + hf + 1 - i, y, cz + l, Material.DEEPSLATE_TILES, tally);
            }
        }
    }

    /**
     * 본전 — <b>캠퍼스에서 가장 큰 몸</b>: 월대({@code SectBuilder.podiumHall} 문법 — 돌기단이
     * 들어올린다) 위 2층 중루. ★핵심 전각의 적: 적목 기둥 + 상단 적 띠 (조닝 3색의 「적」).
     */
    private static void mainHall(World world, TerraceForge.Pad pad, int cx, int cz, Tally tally) {
        int y = pad.y();
        int hf = 8;
        int hl = 5;
        // 월대 — 두 켜 돌기단 + 남쪽 오름 계단 (오르는 집이다)
        for (int f = -hf - 3; f <= hf + 3; f++) {
            for (int l = -hl - 3; l <= hl + 3; l++) {
                put(world, pad, cx + f, y + 1, cz + l, Material.STONE_BRICKS, tally);
                put(world, pad, cx + f, y + 2, cz + l, Material.STONE_BRICKS, tally);
            }
        }
        for (int i = 0; i < 2; i++) {
            for (int l = -2; l <= 2; l++) {
                put(world, pad, cx + l, y + 2 - i, cz + hl + 4 + i, Material.STONE_BRICKS, tally);
            }
        }
        int base = y + 2;
        // 1층 — 백벽 + 적목 모서리, 상단 적 띠, 남면 삼문 (불문 삼문이 아니라 정전의 격)
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
                for (int dy = 1; dy <= 5; dy++) {
                    Material m = corner ? Material.MANGROVE_LOG
                            : (dy == 5 ? Material.RED_TERRACOTTA : Material.WHITE_TERRACOTTA);
                    put(world, pad, x, base + dy, z, m, tally);
                }
            }
        }
        for (int d : new int[]{-4, 0, 4}) {
            for (int l = d - 1; l <= d + 1; l++) {
                for (int dy = 1; dy <= 3; dy++) {
                    put(world, pad, cx + l, base + dy, cz + hl, Material.AIR, tally);
                }
            }
        }
        // 1층 처마 — 한 바퀴 흑기와
        for (int f = -hf - 1; f <= hf + 1; f++) {
            put(world, pad, cx + f, base + 6, cz - hl - 1, Material.DEEPSLATE_TILES, tally);
            put(world, pad, cx + f, base + 6, cz + hl + 1, Material.DEEPSLATE_TILES, tally);
        }
        for (int l = -hl - 1; l <= hl + 1; l++) {
            put(world, pad, cx - hf - 1, base + 6, cz + l, Material.DEEPSLATE_TILES, tally);
            put(world, pad, cx + hf + 1, base + 6, cz + l, Material.DEEPSLATE_TILES, tally);
        }
        // 2층 — 두 칸 안으로 들인 중루
        int hf2 = hf - 2;
        int hl2 = hl - 1;
        for (int f = -hf2; f <= hf2; f++) {
            for (int l = -hl2; l <= hl2; l++) {
                int x = cx + f;
                int z = cz + l;
                boolean edge = Math.abs(f) == hf2 || Math.abs(l) == hl2;
                put(world, pad, x, base + 6, z, Material.SPRUCE_PLANKS, tally);
                if (!edge) {
                    continue;
                }
                boolean corner = Math.abs(f) == hf2 && Math.abs(l) == hl2;
                for (int dy = 7; dy <= 10; dy++) {
                    Material m = corner ? Material.MANGROVE_LOG
                            : (dy == 10 ? Material.RED_TERRACOTTA : Material.WHITE_TERRACOTTA);
                    put(world, pad, x, base + dy, z, m, tally);
                }
                if (!corner && Math.floorMod(f * 3 + l * 5, 4) == 0) {
                    put(world, pad, x, base + 8, z, Material.GLASS_PANE, tally);
                }
            }
        }
        hipRoof(world, pad, cx, base + 10, cz, hf2, hl2, tally);
        put(world, pad, cx - hf, base + 5, cz + hl + 1, Material.LANTERN, tally);
        put(world, pad, cx + hf, base + 5, cz + hl + 1, Material.LANTERN, tally);
        // 현판 자리 — 빈 판 (글자는 사용자 몫)
        put(world, pad, cx, base + 4, cz + hl, Material.DARK_OAK_PLANKS, tally);
        tally.halls++;
    }

    /** 정자 — 침엽 네 기둥 + 흑기와 모임지붕, 사방이 열려 있다 (레퍼런스 6호의 대칭 소품) */
    private static void pavilion(World world, TerraceForge.Pad pad, int cx, int cz, int half, Tally tally) {
        int y = pad.y();
        for (int f : new int[]{-half, half}) {
            for (int l : new int[]{-half, half}) {
                for (int dy = 1; dy <= 3; dy++) {
                    put(world, pad, cx + f, y + dy, cz + l, Material.SPRUCE_LOG, tally);
                }
            }
        }
        hipRoof(world, pad, cx, y + 3, cz, half, half, tally);
        put(world, pad, cx, y + 3, cz, Material.LANTERN, tally);
        tally.pavilions++;
    }

    /**
     * 마당 — 연무장(모래) 또는 훈련장(박석). 목인({@code SectBuilder.woodenDummy} 문법)과
     * 병기 시렁을 세운다 (레퍼런스 3호의 모래 마당·목인·병기걸이).
     */
    private static void yard(World world, TerraceForge.Pad pad, int cx, int cz,
                             boolean sand, int dummies, int dummyDz, Tally tally) {
        int y = pad.y();
        if (sand) {
            // 모래 마당 — 안쪽을 모래로 재포장 (테두리 3칸은 박석 그대로)
            for (int x = pad.x0() + 3; x <= pad.x1() - 3; x++) {
                for (int z = pad.zN() + 3; z <= pad.zS() - 3; z++) {
                    put(world, pad, x, y, z,
                            Math.floorMod(x * 7 + z * 13, 5) == 0 ? Material.SMOOTH_SANDSTONE
                                    : Material.SAND, tally);
                }
            }
        }
        // 목인 — 마당 안 결정론 자리 (동서로 벌린다 · 줄의 남북 자리는 구역이 정한다 — 계단 통로 회피)
        for (int i = 0; i < dummies; i++) {
            int dx = (i - dummies / 2) * 5;
            woodenDummy(world, pad, cx + dx, y, cz + dummyDz, tally);
        }
        // 병기 시렁 【제안 — 전용 부품이 없어 울타리+판 반블록 조합으로 세웠다】
        //   연무장 하(목인 4)는 남쪽 줄을 목인에 내주고 시렁은 북서로 (계단 회랑 둘을 다 비킨다)
        if (dummies >= 4) {
            rack(world, pad, cx - 12, y, pad.zN() + 2, 3, tally);
        } else {
            rack(world, pad, cx - 3, y, pad.zS() - 2, 3, tally);
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
     * 장문인 정원 — 연못(포장면 높이 물) · 소정자 · 매화 한 그루 (사용자 표: Cherry = 매화 대체).
     * ★동편 띠(본전에서 내려오는 계단 몸체 x cx+2.., z cz−3..+3)는 비워 둔다 — 소품은 서·남·북으로.
     */
    private static void garden(World world, TerraceForge.Pad pad, int cx, int cz, Tally tally) {
        int y = pad.y();
        for (int dx = -6; dx <= -3; dx++) {   // 연못 — 서쪽 (밑은 돌 속)
            for (int dz = -2; dz <= 1; dz++) {
                put(world, pad, cx + dx, y, cz + dz, Material.WATER, tally);
            }
        }
        pavilion(world, pad, cx - 1, cz + 5, 1, tally);   // 남서 모서리 소정자
        // 매화 — 벚 원목 + 벚잎, 북서 (군락이 아니라 한 그루가 점을 찍는다)
        int tx = cx - 2;
        int tz = cz - 6;
        for (int dy = 1; dy <= 3; dy++) {
            put(world, pad, tx, y + dy, tz, Material.CHERRY_LOG, tally);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 3; dy <= 5; dy++) {
                    if (Math.abs(dx) + Math.abs(dz) + (dy - 3) <= 3 && (dx != 0 || dz != 0 || dy > 3)) {
                        put(world, pad, tx + dx, y + dy, tz + dz, Material.CHERRY_LEAVES, tally);
                    }
                }
            }
        }
    }

    /** 망루 — 3층 석전 탑 (레퍼런스 9호의 절벽 망루 · {@code SectBuilder.pagoda} 문법의 축소) */
    private static void watchtower(World world, TerraceForge.Pad pad, int cx, int cz, Tally tally) {
        int y = pad.y();
        int[] halves = {3, 2, 1};
        int base = y;
        for (int s = 0; s < halves.length; s++) {
            int half = halves[s];
            int wallH = s == 0 ? 4 : 3;
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
            if (s == 0) {   // 1층 남문
                for (int dy = 1; dy <= 2; dy++) {
                    put(world, pad, cx, base + dy, cz + half, Material.AIR, tally);
                }
            }
            // 층 처마 — 한 바퀴 반블록
            for (int f = -half - 1; f <= half + 1; f++) {
                put(world, pad, cx + f, base + wallH + 1, cz - half - 1, Material.DEEPSLATE_TILE_SLAB, tally);
                put(world, pad, cx + f, base + wallH + 1, cz + half + 1, Material.DEEPSLATE_TILE_SLAB, tally);
            }
            for (int l = -half; l <= half; l++) {
                put(world, pad, cx - half - 1, base + wallH + 1, cz + l, Material.DEEPSLATE_TILE_SLAB, tally);
                put(world, pad, cx + half + 1, base + wallH + 1, cz + l, Material.DEEPSLATE_TILE_SLAB, tally);
            }
            put(world, pad, cx - half, base + 1, cz + half + 1, Material.LANTERN, tally);
            base += wallH + 1;
        }
        hipRoof(world, pad, cx, base, cz, 1, 1, tally);
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
                case 'C' -> {                                    // 상자 더미 — barrel 금지 → chest+반블록
                    for (int i = 0; i <= 2; i++) {
                        put(world, pad, d.x() + i, y + 1, d.z(), Material.CHEST, tally);
                        put(world, pad, d.x() + i, y + 1, d.z() + 1,
                                i == 1 ? Material.CHEST : Material.DARK_OAK_SLAB, tally);
                    }
                    put(world, pad, d.x() + 1, y + 2, d.z(), Material.DARK_OAK_SLAB, tally);
                    tally.props++;
                }
                case 'L' -> {                                    // 빨래줄 — 기둥 둘 + 실
                    for (int px : new int[]{d.x() - 2, d.x() + 2}) {
                        put(world, pad, px, y + 1, d.z(), Material.SPRUCE_FENCE, tally);
                        put(world, pad, px, y + 2, d.z(), Material.SPRUCE_FENCE, tally);
                    }
                    for (int px = d.x() - 1; px <= d.x() + 1; px++) {
                        put(world, pad, px, y + 2, d.z(), Material.TRIPWIRE, tally);
                    }
                    tally.props++;
                }
                case 'P' -> {                                    // 밭 — 물 한 칸 + 경작지 + 밀
                    for (int fx = d.x(); fx <= d.x() + 4; fx++) {
                        for (int fz = d.z(); fz <= d.z() + 3; fz++) {
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
        for (int dy = 1; dy <= 3; dy++) {
            put(world, pad, tx, y + dy, tz, Material.CHERRY_LOG, tally);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 3; dy <= 5; dy++) {
                    if (Math.abs(dx) + Math.abs(dz) + (dy - 3) <= 3 && (dx != 0 || dz != 0 || dy > 3)) {
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
            case 1 -> List.of(new int[]{cx - 9, pad.zS() + 5}, new int[]{cx + 7, pad.zS() + 6});
            case 2 -> List.of(new int[]{pad.x0() - 4, cz + 8}, new int[]{pad.x1() + 4, cz + 9});
            case 9 -> List.of(new int[]{pad.x1() + 4, pad.zN() - 3}, new int[]{pad.x0() - 4, pad.zN() - 4});
            case 13 -> List.of(new int[]{pad.x0() - 4, cz - 2});
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
        int h = 3 + (int) Math.floorMod(hash(0x917E, x, 0, z), 3);   // 3~5
        for (int dy = 1; dy <= h; dy++) {
            world.getBlockAt(x, g + dy, z).setType(Material.SPRUCE_WOOD, false);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(dx) + Math.abs(dz) <= 1) {
                    world.getBlockAt(x + dx, g + h, z + dz).setType(Material.SPRUCE_LEAVES, false);
                    world.getBlockAt(x + dx, g + h - 1, z + dz).setType(Material.SPRUCE_LEAVES, false);
                }
            }
        }
        world.getBlockAt(x, g + h + 1, z).setType(Material.SPRUCE_LEAVES, false);
        tally.pines++;
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
                Material.CHERRY_LOG, Material.CHERRY_LEAVES,
                Material.RED_BANNER, Material.WHITE_BANNER, Material.CAMPFIRE,
                Material.TRIPWIRE, Material.FARMLAND, Material.WHEAT);
    }

    /** 유출 눈이 찾는 건물 재료 표 — 눈이 조경 표와 겹치지 않는지 잰다 */
    public static Set<Material> leakScanMats() {
        return EnumSet.copyOf(BUILDING_MATS);
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
        world.getBlockAt(x, y, z).setType(m, false);
        tally.blocks++;
    }

    /** 배치기의 재료 전부 — 눈이 금지 재료(B-195: barrel·light)를 이 표로 잰다. */
    public static Set<Material> palette() {
        return EnumSet.of(
                Material.WHITE_TERRACOTTA, Material.RED_TERRACOTTA,
                Material.SPRUCE_LOG, Material.SPRUCE_PLANKS, Material.SPRUCE_FENCE,
                Material.DARK_OAK_PLANKS, Material.DARK_OAK_FENCE, Material.DARK_OAK_SLAB,
                Material.STRIPPED_DARK_OAK_LOG, Material.MANGROVE_LOG,
                Material.DEEPSLATE_TILES, Material.DEEPSLATE_TILE_SLAB,
                Material.STONE_BRICKS, Material.POLISHED_ANDESITE, Material.GLASS_PANE,
                Material.SAND, Material.SMOOTH_SANDSTONE, Material.SANDSTONE,
                Material.CHERRY_LOG, Material.CHERRY_LEAVES, Material.WATER,
                Material.CHEST, Material.LANTERN, Material.AIR);
    }

    /** 패드 밖 유출 검수가 찾는 건물 재료 — 테라스 제 것(석전 벽·등롱)은 뺀다 */
    private static final Set<Material> BUILDING_MATS = EnumSet.of(
            Material.WHITE_TERRACOTTA, Material.RED_TERRACOTTA,
            Material.SPRUCE_LOG, Material.SPRUCE_PLANKS, Material.SPRUCE_FENCE,
            Material.DARK_OAK_PLANKS, Material.DARK_OAK_FENCE, Material.DARK_OAK_SLAB,
            Material.STRIPPED_DARK_OAK_LOG, Material.MANGROVE_LOG,
            Material.DEEPSLATE_TILES, Material.DEEPSLATE_TILE_SLAB, Material.GLASS_PANE,
            Material.SAND, Material.SMOOTH_SANDSTONE, Material.SANDSTONE, Material.CHEST);
}
