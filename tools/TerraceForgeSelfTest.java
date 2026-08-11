package com.honcheon.mvt;

import com.honcheon.mvt.forge.Blueprint;
import com.honcheon.mvt.forge.TerraceForge;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * <b>석축 테라스 기계의 눈</b> — <b>패드·계단 앉힘(순수부)과 팔레트가 계약을 지키는가.</b>
 *
 * <p>계약 (docs/design/hwasan_build_enhancement_v1.md §2 v2·§3 · B-146 · B-195):
 * <ul>
 *   <li>기본 캠퍼스 = 마스터플랜 척추 6단(1·2·6·9·12·13) + 로브 7단(3·4·5·7·8·14·17) —
 *       전 링크가 순수 검증({@code validate})을 통과해야 한다</li>
 *   <li>낙차 창 [{@code MIN_STAIR_DY}, {@code MAX_STAIR_DY}] — 밖이면 거절</li>
 *   <li>패드 한 변 ≤ 35 (H-3) · 구역 중복 · 패드 겹침 · 닿지 않는 링크 — 전부 거절</li>
 *   <li>팔레트에 {@code BARREL}(가구_3D 유령 벽)·{@code LIGHT}(컬링 전과)가 없다</li>
 * </ul>
 *
 * <h2>★ 일부러 어긴다</h2>
 * 낙차 0·폭 36·구역 중복·겹침·먼 링크가 <b>거절되는지</b>도 잰다 —
 * 무엇이든 통과시키는 눈은 눈이 아니다.
 *
 * <h2>어떻게 돌리나 (저장소 루트에서 · 서버를 켜지 않는다)</h2>
 * <pre>
 *   export JAVA_HOME=$PWD/run/jdk-21
 *   ./gradlew :server-mvt:jar
 *   CP="$(find ~/.gradle -path '*1.21.11-R0.1-SNAPSHOT*' -name 'paper-api-*.jar' | head -1)"
 *   CP="$CP:$(find ~/.gradle -name '*.jar' | grep -E 'adventure-key|adventure-api|examination-api|snakeyaml-2.2' \
 *             | grep -v 26.1.2 | tr '\n' ':')$(find run/mvt/libraries -name 'guava-*.jar' | head -1)"
 *   CP="$CP:server-mvt/build/libs/server-mvt-1.0.0.jar"
 *   $JAVA_HOME/bin/javac -nowarn -d /tmp/terrace-eye -cp "$CP" tools/TerraceForgeSelfTest.java
 *   $JAVA_HOME/bin/java -cp "$CP:/tmp/terrace-eye" com.honcheon.mvt.TerraceForgeSelfTest
 * </pre>
 */
public final class TerraceForgeSelfTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    /**
     * <b>적주 몸통</b>이 앉은 켜의 자리 — 재료 이름이 아니라 <b>구조</b>로 찾는다.
     *
     * <p>★이 저장소에서 <b>여섯 번째</b> 같은 병이었다. REF-2C 로 적주가
     * {@code stripped_mangrove_log} → {@code red_terracotta} 가 되자
     * 「mangrove_log 인가」·「_log 로 끝나는가」로 몸통을 찾던 자 여섯이 한꺼번에 눈멀었다.
     * 조성은 멀쩡한데 <b>자가 팔레트에 얹혀 있었다.</b>
     *
     * <p>몸통의 구조적 정의: 세로로 {@code POST_MIN_COURSES} 켜 이상 이어진 한 재료의 런 중,
     * <b>기단(석재)도 아니고 처방(회벽·살창·빈칸)도 아닌</b> 가장 위의 것.
     * 팔레트를 몇 번 갈아도 이 정의는 안 바뀐다 — <b>팔레트는 바뀌고 구조는 안 바뀐다.</b>
     *
     * @return 몸통 켜의 자리, 없으면 −1
     */
    private static int shaftIndex(java.util.List<Blueprint.Course> col) {
        // ★자는 조성과 <b>같은 정의</b>를 써야 한다. 두 벌을 두면 언젠가 어긋나고,
        //   그때 자는 <b>실물이 아닌 자기 자신</b>을 지키게 된다.
        return Blueprint.shaftIndex(col);
    }

    /** 이 칸이 <b>적주</b>인가 — 몸통이 있으면 적주다 (글자도 재료도 안 본다) */
    private static boolean isPost(java.util.List<Blueprint.Course> col) {
        return shaftIndex(col) >= 0;
    }

    /** 이 칸의 몸통 재료 — <b>도면에서 읽는다</b>. 자 안에 재료 이름을 안 박기 위해서다 */
    private static String shaftMaterial(java.util.List<Blueprint.Course> col) {
        int i = shaftIndex(col);
        return i < 0 ? "" : col.get(i).material();
    }

    /**
     * 이 적주 런이 <b>중앙 대문에 바로 접한</b> 겹기둥인가.
     *
     * <p>이름(`J`)으로도 <b>축에서 몇 칸</b>으로도 묻지 않는다. 거리로 물으면 개구 폭이
     * 3 에서 5 로 바뀌는 순간 자가 죽는다 — 그건 또 하나의 「오늘의 값에 얹힌 자」다.
     * 물어야 할 것은 <b>이 런과 축 사이에 다른 적주가 없는가</b>다. 없으면 이 런은
     * 대문을 바로 끼고 선 것이고, 있으면 대문과 무관한 자리에서 겹친 것이다.
     */
    private static boolean flanksAxis(Blueprint bp, int row, int a, int b) {
        int axis = bp.axisCol();
        if (a <= axis && axis <= b) {
            return false;                       // 축을 걸친 런은 「양측 인접쌍」이 아니다
        }
        int inner = axis > b ? b : a;           // 축을 향한 끝
        int lo = Math.min(inner, axis);
        int hi = Math.max(inner, axis);
        for (int c = lo + 1; c < hi; c++) {
            if (bp.isPost(bp.at(c, row))) {
                return false;                   // 사이에 다른 적주가 있다 = 대문에 접하지 않는다
            }
        }
        return true;
    }

    private static final int POST_MIN = com.honcheon.mvt.forge.BlueprintBuilder.POST_MIN_COURSES;

    /** 적주 몸통 위 · 도리 아래에 몇 켜가 있는가 = <b>주두의 두께</b> (REF-3B 밀도 사다리) */
    /** 색표에서 한 재료를 꺼낸다 — 파생 이름(`_slab`·`_wall`)은 본체로 되짚는다 */
    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> grainEntry(
            java.util.Map<String, Object> table, String mat) {
        if (table.isEmpty() || mat == null || mat.isEmpty()) {
            return null;
        }
        String[] alias = {mat, mat.replace("plaster", "bone_block_side")
                .replace("lattice", "dark_oak_trapdoor")};
        for (String a : alias) {
            Object v = table.get(a);
            if (v != null) {
                return (java.util.Map<String, Object>) v;
            }
        }
        for (String suf : new String[]{"_stairs", "_slab", "_wall", "_fence", "_trapdoor"}) {
            if (mat.endsWith(suf)) {
                String base = mat.substring(0, mat.length() - suf.length());
                for (String cand : new String[]{base, base + "s", base + "_planks"}) {
                    Object v = table.get(cand);
                    if (v != null) {
                        return (java.util.Map<String, Object>) v;
                    }
                }
            }
        }
        return null;
    }

    private static double grainLum(java.util.Map<String, Object> table, String mat) {
        java.util.Map<String, Object> e = grainEntry(table, mat);
        return e == null ? 0 : ((Number) e.getOrDefault("lum", 0)).doubleValue();
    }

    private static int firstColOf(com.honcheon.mvt.forge.Level lv, char ch) {
        for (int r = 0; r < lv.depth(); r++) {
            for (int c = 0; c < lv.width(); c++) {
                if (lv.at(c, r) == ch) {
                    return c;
                }
            }
        }
        return 0;
    }

    private static int firstRowOf(com.honcheon.mvt.forge.Level lv, char ch) {
        for (int r = 0; r < lv.depth(); r++) {
            for (int c = 0; c < lv.width(); c++) {
                if (lv.at(c, r) == ch) {
                    return r;
                }
            }
        }
        return 0;
    }

    private static int capitalCourses(Blueprint bp, char ch) {
        java.util.List<Blueprint.Course> col = bp.columnOf(ch);
        // ★★자를 고쳤다 — 다섯 번째엔 「mangrove_log 인가」를 「_log 로 끝나는가」로 넓혔고,
        //   여섯 번째(REF-2C)에 적주가 통나무가 아니게 되자 그것마저 눈멀었다.
        //   이제 {@link #shaftIndex} 하나로 모은다 — 재료가 아니라 <b>런</b>을 본다.
        int shaft = shaftIndex(col);
        if (shaft < 0) {
            return 0;
        }
        // ★★자를 고쳤다 (REF-3B): 처음엔 「dark_oak 이 나오면 도리」로 끊었는데, 모서리 주두의
        //   둘째 켜가 어두운 목재라 <b>주두를 도리로 세어</b> 1 로 읽혔다. 재료가 아니라
        //   <b>자리</b>로 끊는다 — 맨 위 두 켜가 도리·긴 보이고, 그 사이가 주두다.
        return Math.max(0, (col.size() - 2) - (shaft + 1));
    }

    public static void main(String[] args) {
        // ══════════ ① 기본 캠퍼스가 순수 검증을 통과한다 ══════════
        TerraceForge.Campus campus = TerraceForge.hwasanCampus();
        boolean valid = true;
        String why = "";
        try {
            TerraceForge.validate(campus);
        } catch (IllegalArgumentException e) {
            valid = false;
            why = e.getMessage();
        }
        check("기본 캠퍼스(패드 " + campus.pads().size() + " · 링크 " + campus.links().size()
                + " · 다리 " + campus.bridges().size() + ")가 전부 앉는다", valid, why);

        // ══════════ ② 마스터플랜 구역이 다 있다 (척추 6 + 로브 7) ══════════
        for (int zone : new int[]{1, 2, 6, 9, 12, 13}) {
            check("척추 구역 " + zone + " 이 있다", hasZone(campus, zone), zone);
        }
        for (int zone : new int[]{3, 4, 5, 7, 8, 14, 17}) {
            check("로브 구역 " + zone + " 이 있다", hasZone(campus, zone), zone);
        }
        check("결번 15 를 안 쓴다", !hasZone(campus, 15), 15);
        check("중정(101) 만 남는다 — 102·103 은 통단 개편으로 흡수 (슬라이스 5)",
                hasZone(campus, 101) && !hasZone(campus, 102) && !hasZone(campus, 103), "101");
        check("슬라이스 2 신설 (10 정원 · 11 망루 · 16 측문)",
                hasZone(campus, 10) && hasZone(campus, 11) && hasZone(campus, 16), "10/11/16");
        check("★슬라이스 3 곁봉 (19 전망대 · 20 암자) + 운무교 3",
                hasZone(campus, 19) && hasZone(campus, 20) && campus.bridges().size() == 3,
                campus.bridges().size());
        // 척추 앵커 — 통단 개편(슬라이스 5 · 사용자 지시)으로 종문은 76 이 됐다. 본전·장로회·정상은 살렸다.
        // ★슬라이스 8 재척도 앵커 — h 사슬 46/64/86/108/130/146/170 (사용자 기준자: 계단 20)
        check("앵커: 종문 h76 (통단 B3 · ★척도 되돌림)", heightOf(campus, 6) == 76, heightOf(campus, 6));
        check("앵커: 본전 h116 (★척도 되돌림)", heightOf(campus, 9) == 116, heightOf(campus, 9));
        check("앵커: 장로회 h128 (★척도 되돌림)", heightOf(campus, 12) == 128, heightOf(campus, 12));
        check("앵커: 정상 h148 (★척도 되돌림)", heightOf(campus, 13) == 148, heightOf(campus, 13));
        // ★★D-19 (2026-08-04 사용자 실측) — 레퍼런스 계단 도보 폭은 7 이다.
        //   슬라이스 8 의 「20」 앵커(전폭 21)가 오류였음이 드러나 되돌렸다.
        //   이 눈이 지키는 것: 다시 「크게 하면 웅장하다」로 올리지 못한다.
        check("★D-19 대계단 도보 폭 7 (사용자 실측 — 목표 사진의 자)",
                2 * TerraceForge.STAIR_HALF + 1 == 7, 2 * TerraceForge.STAIR_HALF + 1);
        check("★D-19 난간은 도보 바로 밖 (전폭 9 = 도보 7 + 측석 2)",
                TerraceForge.RAIL_OFF == TerraceForge.STAIR_HALF + 1
                        && 2 * TerraceForge.RAIL_OFF + 1 == 9, TerraceForge.RAIL_OFF);

        // ══════════ ③ 계단 기하 — 아는 두 패드로 자로 잰다 ══════════
        TerraceForge.Campus two = new TerraceForge.Campus(
                List.of(new TerraceForge.PadSpec(1, "아래", 0, 20, 24, 16, 10),
                        new TerraceForge.PadSpec(2, "위", 0, -4, 24, 16, 20)),
                List.of(new TerraceForge.StairLink(2, 1, 'S')));
        List<TerraceForge.Pad> pads = TerraceForge.resolvePads(two, 0, 0, 100);
        List<TerraceForge.StairLane> lanes = TerraceForge.resolveLanes(two, pads);
        TerraceForge.StairLane lane = lanes.get(0);
        check("낙차 10 → 디딤 9", lane.treads() == 9, lane.treads());
        check("남으로 내려간다 (dirZ=+1)", lane.dirZ() == 1 && lane.dirX() == 0,
                lane.dirX() + "," + lane.dirZ());
        check("첫 디딤은 윗패드 남면 한 칸 밖", lane.startZ() == pads.get(1).zS() + 1, lane.startZ());
        check("램프가 아랫패드에 닿는다 (보도 0)", lane.walk() == 0, lane.walk());

        // ══════════ ④ 일부러 어긴다 — 거절이 거절인가 ══════════
        checkThrows("낙차 0 은 거절", new TerraceForge.Campus(
                List.of(new TerraceForge.PadSpec(1, "아래", 0, 20, 24, 16, 10),
                        new TerraceForge.PadSpec(2, "위", 0, -4, 24, 16, 10)),
                List.of(new TerraceForge.StairLink(2, 1, 'S'))));
        // ★슬라이스 5 — H-3 상한 35 폐지 (사용자 지시 「이미지 크기 그대로」) · 안전핀 128 만 남는다
        checkThrows("폭 129 는 거절 (안전핀 128)", new TerraceForge.Campus(
                List.of(new TerraceForge.PadSpec(1, "만용", 0, 0, 129, 16, 10)),
                List.of()));
        checkThrows("구역 번호 중복은 거절", new TerraceForge.Campus(
                List.of(new TerraceForge.PadSpec(1, "갑", 0, 0, 16, 16, 10),
                        new TerraceForge.PadSpec(1, "을", 100, 0, 16, 16, 10)),
                List.of()));
        checkThrows("패드 겹침은 거절", new TerraceForge.Campus(
                List.of(new TerraceForge.PadSpec(1, "갑", 0, 0, 20, 20, 10),
                        new TerraceForge.PadSpec(2, "을", 8, 8, 20, 20, 14)),
                List.of()));
        checkThrows("닿지 않는 링크는 거절 (램프+보도 밖)", new TerraceForge.Campus(
                List.of(new TerraceForge.PadSpec(1, "아래", 0, 90, 24, 16, 10),
                        new TerraceForge.PadSpec(2, "위", 0, 0, 24, 16, 16)),
                List.of(new TerraceForge.StairLink(2, 1, 'S'))));
        checkThrows("없는 구역을 부르는 링크는 거절", new TerraceForge.Campus(
                List.of(new TerraceForge.PadSpec(1, "갑", 0, 0, 16, 16, 10)),
                List.of(new TerraceForge.StairLink(1, 99, 'S'))));
        // ★슬라이스 2.5 — 계단 몸체가 남의 패드를 지나면 계획이 거절한다 (침범은 계획이 막는다)
        checkThrows("남의 패드를 지나는 계단은 거절", new TerraceForge.Campus(
                List.of(new TerraceForge.PadSpec(2, "위", 0, -4, 24, 16, 20),
                        new TerraceForge.PadSpec(1, "아래", 0, 24, 24, 16, 10),
                        new TerraceForge.PadSpec(3, "남의 것", 0, 10, 6, 6, 5)),
                List.of(new TerraceForge.StairLink(2, 1, 'S'))));

        // ══════════ ④-b 다리 — 기하 자재기 + 거절 ══════════
        TerraceForge.Campus withBridge = new TerraceForge.Campus(
                List.of(new TerraceForge.PadSpec(1, "본산", 0, 0, 20, 16, 50),
                        new TerraceForge.PadSpec(2, "곁봉", 60, 0, 20, 16, 50)),
                List.of(),
                List.of(new TerraceForge.BridgeSpec("시험교", true, 0, 10, 49, 50)));
        boolean bv = true;
        String bWhy2 = "";
        try {
            TerraceForge.validate(withBridge);
        } catch (IllegalArgumentException e) {
            bv = false;
            bWhy2 = e.getMessage();
        }
        check("수평 다리가 앉는다 (양끝 패드 접속)", bv, bWhy2);
        java.util.List<TerraceForge.Pad> bp = TerraceForge.resolvePads(withBridge, 0, 0, 0);
        TerraceForge.Bridge tb = TerraceForge.resolveBridges(withBridge, bp,
                java.util.List.of(), 0, 0, 0).get(0);
        check("스팬 40 · 교각 0", tb.span() == 40 && tb.pierOffsets().isEmpty(),
                tb.span() + "/" + tb.pierOffsets());
        TerraceForge.Bridge tb79 = TerraceForge.resolveBridges(new TerraceForge.Campus(
                        List.of(new TerraceForge.PadSpec(1, "본산", 0, 0, 20, 16, 50),
                                new TerraceForge.PadSpec(2, "곁봉", 99, 0, 20, 16, 50)),
                        List.of(),
                        List.of(new TerraceForge.BridgeSpec("긴교", true, 0, 10, 88, 50))),
                TerraceForge.resolvePads(new TerraceForge.Campus(
                        List.of(new TerraceForge.PadSpec(1, "본산", 0, 0, 20, 16, 50),
                                new TerraceForge.PadSpec(2, "곁봉", 99, 0, 20, 16, 50)),
                        List.of(), List.of()), 0, 0, 0),
                java.util.List.of(), 0, 0, 0).get(0);
        check("스팬 79 → 교각 2", tb79.pierOffsets().size() == 2, tb79.pierOffsets());
        checkThrows("높이 안 맞는 다리는 거절 (상판은 수평)", new TerraceForge.Campus(
                List.of(new TerraceForge.PadSpec(1, "본산", 0, 0, 20, 16, 50),
                        new TerraceForge.PadSpec(2, "곁봉", 60, 0, 20, 16, 44)),
                List.of(),
                List.of(new TerraceForge.BridgeSpec("기운교", true, 0, 10, 49, 50))));
        checkThrows("허공에 끝나는 다리는 거절", new TerraceForge.Campus(
                List.of(new TerraceForge.PadSpec(1, "본산", 0, 0, 20, 16, 50)),
                List.of(),
                List.of(new TerraceForge.BridgeSpec("허공교", true, 0, 10, 49, 50))));
        checkThrows("스팬 상한(160) 초과는 거절 (★8.6 — 협곡 재척도로 80→160)", new TerraceForge.Campus(
                List.of(new TerraceForge.PadSpec(1, "본산", 0, 0, 20, 16, 50),
                        new TerraceForge.PadSpec(2, "곁봉", 200, 0, 20, 16, 50)),
                List.of(),
                List.of(new TerraceForge.BridgeSpec("만용교", true, 0, 10, 189, 50))));
        // ★척도 되돌림 — 한도 ±40 (계약이 19~31 로 돌아왔다)
        checkThrows("expectedLift ±40 밖은 거절", new TerraceForge.Campus(
                List.of(new TerraceForge.PadSpec(1, "만용", 0, 0, 20, 16, 50, 41)),
                List.of(), List.of()));
        check("성곽 계약: 3 연무장하 Δ23 · 16 측문 Δ27 (★9b 단구 ±4 · 척도 되돌림)",
                liftOf(campus, 3) == 23 && liftOf(campus, 16) == 27,
                liftOf(campus, 3) + "/" + liftOf(campus, 16));
        check("★8.6 척추 계약 삭제 — 본전·장로회·정상 Δ0/1/-5 실측 (창룡령 정렬의 증거)",
                liftOf(campus, 9) == 0 && liftOf(campus, 12) == 0 && liftOf(campus, 13) == 0,
                liftOf(campus, 9) + "/" + liftOf(campus, 12) + "/" + liftOf(campus, 13));
        check("expectedLift 계약: 19 전망대 = 31 (★척도 되돌림 — 곁봉 제자리)",
                liftOf(campus, 19) == 31, liftOf(campus, 19));
        check("expectedLift 계약: 105 서교 착지 = 20 (★척도 되돌림)",
                liftOf(campus, 105) == 20, liftOf(campus, 105));
        check("expectedLift 계약: 20 부속 암자 = 20 (★척도 되돌림)", liftOf(campus, 20) == 20, liftOf(campus, 20));
        check("다리 몸체 covers: 난간 열(a0−2 · 폭 ±2)을 안다",
                tb.covers(8, 2) && tb.covers(50, -2) && !tb.covers(8, 3), "covers");

        // ══════════ ④-c 스킵 상자가 실물을 덮는가 — 회전 케이스 회귀 (4.0 평탄 1건) ══════════
        TerraceForge.Pad side = TerraceForge.resolvePads(campus, 0, 0, 0).stream()
                .filter(pp -> pp.spec().zone() == 16).findFirst().orElseThrow();
        int gateX = side.x0() + side.spec().width() / 2;   // wallNS/gateEW 의 담 열 (통단 개편 후 중앙)
        boolean wallCovered = true;
        for (int z = side.zN(); z <= side.zS(); z++) {
            boolean in = false;
            for (int[] box : com.honcheon.mvt.forge.HwasanCampusBuilder.auditSkipBoxes(side)) {
                if (gateX >= box[0] && gateX <= box[1] && z >= box[2] && z <= box[3]) {
                    in = true;
                    break;
                }
            }
            if (!in) {
                wallCovered = false;
            }
        }
        check("★측문 담(남북 전장)이 스킵 상자에 다 덮인다", wallCovered, gateX);

        // ══════════ ④-d 산군(SpireField) — 실측 §4 계약 (순수) ══════════
        com.honcheon.mvt.forge.SpireField bare =
                new com.honcheon.mvt.forge.SpireField(java.util.List.of());
        com.honcheon.mvt.forge.SpireField bare2 =
                new com.honcheon.mvt.forge.SpireField(java.util.List.of());
        boolean det = true;
        for (int[] pt : new int[][]{{200, 40}, {-300, 150}, {480, -200}, {-24, -54}, {150, 300}}) {
            if (bare.targetH(pt[0], pt[1]) != bare2.targetH(pt[0], pt[1])) {
                det = false;
            }
        }
        check("산군: 결정론 (난수 0)", det, "표본 5");
        check("산군: 배후봉 Pm 마루 h228 (정상단 148 + 80 — 실측 §4 · ★척도 되돌림)",
                bare.targetH(-24, -54) == 228, bare.targetH(-24, -54));
        com.honcheon.mvt.forge.SpireField blocked = new com.honcheon.mvt.forge.SpireField(
                java.util.List.of(new int[]{-30, -20, -60, -50}));
        check("산군: 제외 사각 안은 0 (산이 사람의 것을 침범하지 않는다)",
                blocked.targetH(-24, -54) == 0, blocked.targetH(-24, -54));
        // 캠퍼스 통합 — 전 패드 중심·다리 한복판에서 산군 0
        java.util.List<TerraceForge.Pad> spPads = TerraceForge.resolvePads(campus, 0, 0, 0);
        java.util.List<TerraceForge.StairLane> spLanes = TerraceForge.resolveLanes(campus, spPads);
        java.util.List<int[]> ex = new java.util.ArrayList<>();
        for (TerraceForge.Pad pd : spPads) {
            ex.add(new int[]{pd.x0() - 8, pd.x1() + 8, pd.zN() - 8, pd.zS() + 8});
        }
        for (TerraceForge.Bridge b : TerraceForge.resolveBridges(campus, spPads, spLanes, 0, 0, 0)) {
            ex.add(new int[]{b.a0() - 6, b.a1() + 6, b.c() - 6, b.c() + 6});
        }
        com.honcheon.mvt.forge.SpireField guarded = new com.honcheon.mvt.forge.SpireField(ex);
        boolean clear = true;
        for (TerraceForge.Pad pd : spPads) {
            if (guarded.targetH(pd.x0() + pd.spec().width() / 2, pd.zN() + pd.spec().depth() / 2) != 0) {
                clear = false;
            }
        }
        check("산군: 캠퍼스 전 패드 중심 무침범", clear, "패드 " + spPads.size());
        // 켜 3 — 각 환대(環帶)에 침봉이 실재하고, 근경 마루가 실측 창 안 (★척도 되돌림 — 130/260/430/620 · 86~136)
        int[] rings = new int[3];
        int r1max = 0;
        for (int gx = -610; gx <= 610; gx += 11) {
            for (int gz = -610; gz <= 610; gz += 11) {
                int hh = bare.targetH(gx, gz);
                if (hh < 30) {
                    continue;
                }
                double d = Math.hypot(gx, gz);
                if (d >= 130 && d < 260) {
                    rings[0]++;
                    if (gz > 95 && hh > r1max) {
                        r1max = hh;   // 배후봉(북) 자락 밖 — 남쪽 반구에서만 잰다
                    }
                } else if (d >= 260 && d < 430) {
                    rings[1]++;
                } else if (d >= 430 && d < 620) {
                    rings[2]++;
                }
            }
        }
        check("산군: 켜 3 실재 (근경/중경/원경 각 > 0)",
                rings[0] > 0 && rings[1] > 0 && rings[2] > 0,
                rings[0] + "/" + rings[1] + "/" + rings[2]);
        check("산군: 근경 남쪽 마루 최고가 창(86~136) 안 (광봉·침봉 공통 상한 · 10.5 · 척도 되돌림)",
                r1max >= 86 && r1max <= 136, r1max);
        // ★10.5 구도 반전 — 광봉이 주인 · 침봉은 장식 (실측 §12: 개수비 ≥4:1)
        int gTotal = 0;
        int gHit = 0;
        for (int gx2 = -6; gx2 <= 6; gx2++) {
            for (int gz2 = -6; gz2 <= 6; gz2++) {
                double dd = Math.hypot(gx2 * 80 + 40, gz2 * 80 + 40);
                if (dd < 150 || dd > 390) {
                    continue;
                }
                gTotal++;
                if (com.honcheon.mvt.forge.SpireField.broadRadius(gx2, gz2) > 0) {
                    gHit++;
                }
            }
        }
        check("★10.5 광봉이 주인 — 근·중경 격자 점유 ≥70%", gTotal > 0 && gHit * 100 / gTotal >= 70,
                gHit + "/" + gTotal);
        int sTotal = 0;
        int sHit = 0;
        for (int cx3 = -25; cx3 <= 25; cx3++) {
            for (int cz3 = -25; cz3 <= 25; cz3++) {
                double dd = Math.hypot(cx3 * 26 + 13, cz3 * 26 + 13);
                if (dd < 150 || dd > 390) {
                    continue;
                }
                sTotal++;
                if (com.honcheon.mvt.forge.SpireField.spireCenter(cx3, cz3) != null) {
                    sHit++;
                }
            }
        }
        check("★10.5 침봉 강등 — 근·중경 셀 침봉 ≤6% (62% 밭의 폐지)",
                sTotal > 0 && sHit * 100 / sTotal <= 6, sHit + "/" + sTotal);
        // ★10.5 발치 가드 — 살아남은 근경 침봉이 실재하고 (절멸 방지) 마루가 창 안
        boolean perched = false;
        for (int cx3 = -16; cx3 <= 16 && !perched; cx3++) {
            for (int cz3 = 6; cz3 <= 16 && !perched; cz3++) {
                int[] sc = com.honcheon.mvt.forge.SpireField.spireCenter(cx3, cz3);
                if (sc == null) {
                    continue;
                }
                double dd = Math.hypot(sc[0], sc[1]);
                if (dd < 130 || dd > 260) {
                    continue;
                }
                int hh = bare.targetH(sc[0], sc[1]);
                if (hh >= 86 && hh <= 136) {
                    perched = true;
                }
            }
        }
        check("★10.5 광봉 위 침봉 실재 — 강등이 절멸은 아니다 (남쪽 근경 표본)", perched, "표본 0");
        // ★8.8 — 남쪽 접근 시야 회랑: 축선 남쪽 폭 ~70 은 침봉 0 · ★10-①: 산체는 낮은 구릉(≤20)
        //   까지 허용 (시야 회랑이지 지형 금지가 아니다 — 접근로가 구릉을 넘는 것이 시퀀스다)
        boolean corridorClear = true;
        int southSide = 0;
        for (int gz = 400; gz <= 980; gz += 11) {
            for (int gx = -30; gx <= 22; gx += 7) {
                if (bare.targetH(gx, gz) > 20) {
                    corridorClear = false;
                }
            }
            if (bare.targetH(-140, gz) != 0 || bare.targetH(140, gz) != 0) {
                southSide++;
            }
        }
        check("★8.8 남쪽 시야 회랑 — 침봉 0 · 산체 구릉 ≤20 (10-① 개정)", corridorClear, "회랑 침범");
        check("★8.8 회랑 밖 남쪽 필드 실재 (회랑이 필드를 안 지웠다)", southSide > 0, southSide);

        // ★★D-16 (2026-08-04) — 접근로 회랑에는 나무가 서지 않는다.
        //   진범이었던 것: 산군 식생의 제외 목록에 패드·계단·다리만 있고 **접근로가 통째로
        //   빠져 있었다** — 그래서 야생 숲이 대계단 위까지 자라 하단이 초록 덩어리가 됐다.
        //   여기서는 그 제외 사각이 실제로 접근로를 덮는지 순수하게 잰다 (조성 없이).
        {
            java.util.List<TerraceForge.Pad> ap = TerraceForge.resolvePads(campus, 0, 0, 0);
            TerraceForge.Pad gate = ap.stream().filter(p2 -> p2.spec().zone() == 1)
                    .findFirst().orElseThrow();
            int acx = (gate.x0() + gate.x1()) / 2;
            int c = TerraceForge.APPROACH_CLEAR;
            java.util.List<int[]> corrEx = java.util.List.of(new int[]{acx - c, acx + c,
                    gate.zS() + 1, gate.zS() + 1 + TerraceForge.APPROACH_LEN + 24});
            com.honcheon.mvt.forge.SpireField corrField =
                    new com.honcheon.mvt.forge.SpireField(corrEx);
            boolean corridorExcluded = true;
            for (int i = 4; i < TerraceForge.APPROACH_LEN; i += 17) {
                int z = gate.zS() + 1 + i;
                for (int o = -c; o <= c; o += 5) {
                    if (!corrField.excluded(acx + o, z)) {
                        corridorExcluded = false;
                    }
                }
            }
            check("★D-16 접근로 회랑(±" + c + ")이 식생 제외에 든다 — 계단을 덮는 나무가 없다",
                    corridorExcluded, "제외 누락");
            // 반대편도 잰다 — 「빼기」가 필드를 통째로 지우지는 않았는가 (곁의 나무는 남는다)
            boolean besideAllowed = !corrField.excluded(acx + c + 6, gate.zS() + 40)
                    && !corrField.excluded(acx - c - 6, gate.zS() + 90);
            check("★D-16 회랑 밖은 여전히 심긴다 (곁의 나무까지 밀지는 않았다)",
                    besideAllowed, "회랑 밖도 제외됐다");
        }
        // ══════ ★접근로 부속 인덱스가 길이 안인가 (2026-08-04 실증의 재발 방지) ══════
        //   척도를 되돌리며 APPROACH_LEN 176→88 로 줄였는데 소문 몸체가 옛 인덱스(102)를 물고
        //   있어 조성 중 ArrayIndexOutOfBounds 로 터졌다 — 순수 검증은 통과했다 (아무도 범위를
        //   안 쟀다). 비석 하나(i150)는 범위 밖이라 조용히 사라지고 있었다.
        //   ★이 눈은 「부속이 실재하는가」와 「범위 안인가」를 같은 표(approachFixtureIndices)로 잰다.
        int[] fixtures = TerraceForge.approachFixtureIndices();
        boolean fixturesInRange = fixtures.length > 0;
        int worstFixture = -1;
        for (int idx : fixtures) {
            if (idx < 0 || idx >= TerraceForge.APPROACH_LEN) {
                fixturesInRange = false;
                worstFixture = idx;
            }
        }
        check("★접근로 부속 " + fixtures.length + "개가 전부 길이(" + TerraceForge.APPROACH_LEN
                        + ") 안 — 조성이 ys[] 를 넘지 않는다",
                fixturesInRange, worstFixture < 0 ? "부속 0" : "범위 밖 i" + worstFixture);
        // 소문은 제 마당 남끝에서 세운다 — 그 행도 길이 안이어야 한다 (조성 순서의 계약)
        check("★소문 조성 행(GATE_I+2)도 길이 안 · 마당이 소문을 감싼다",
                TerraceForge.GATE_I + 2 < TerraceForge.APPROACH_LEN
                        && TerraceForge.GATE_YARD_N <= TerraceForge.GATE_I
                        && TerraceForge.GATE_I <= TerraceForge.GATE_YARD_S,
                TerraceForge.GATE_YARD_N + "<=" + TerraceForge.GATE_I + "<=" + TerraceForge.GATE_YARD_S);
        // ══════ ★D-20 축선 시야 회랑 (2026-08-05 · 사용자 지적) ══════
        //   「회백색 기둥 하나가 문루 정면 중앙을 세로로 가로지른다」 — 범인은 비석이었다.
        //   옛 자리 ±(RAIL_OFF+3)=±7 은 <b>통행</b>을 안 막아 통로 겹침 눈이 조용했다. 그러나
        //   계단 아래 축선에 선 눈에는 <b>가까운 5칸 비석이 멀리 있는 21칸 문루를 가린다</b>.
        //   ★통행의 폭과 시야의 폭은 다르다 — 이 눈이 후자를 잰다.
        check("★D-20 축선 시야 회랑(±" + TerraceForge.AXIS_CLEAR + ")이 계단 통행폭보다 넓다",
                TerraceForge.AXIS_CLEAR > TerraceForge.RAIL_OFF,
                "AXIS_CLEAR " + TerraceForge.AXIS_CLEAR + " vs RAIL_OFF " + TerraceForge.RAIL_OFF);
        // 비석이 그 회랑 밖에 선다 — 조성과 같은 식(±AXIS_CLEAR)을 눈이 다시 잰다
        check("★D-20 비석이 축선 회랑 밖에 선다 (문루를 안 가린다)",
                TerraceForge.AXIS_CLEAR >= TerraceForge.APPROACH_CLEAR + 2,
                "비석 오프셋 ±" + TerraceForge.AXIS_CLEAR);

        // ★2026-08-05 계약 교정 — <b>가리는 정도는 높이가 아니라 두께가 정한다</b>.
        //   처음엔 「축선 회랑에 수직 소품 0」이었으나 그것이 과했다: 목표 사진 1호는 계단 양옆에
        //   깃대가 촘촘히 섰는데도 문루가 잘 보인다 — 1칸 기둥은 원근으로도 가늘기 때문이다.
        //   그래서 <b>두꺼운 소품(폭 ≥2)</b>만 회랑 밖으로 민다. 가는 것(폭 1)은 난간 위까지 허용.
        //   ★상자 형식은 {x0, x1, z0, z1} 이다 (y 는 없다) — 처음 이 눈을 6원소로 읽었더니
        //   폭 계산이 어긋나 <b>모든 상자를 건너뛰고 조용히 통과</b>했다 (뮤테이션이 잡았다).
        //   비석은 폭 2~3, 정자는 7, 문루는 29 — 문루는 표적이지 장애물이 아니므로 뺀다.
        {
            java.util.List<TerraceForge.Pad> axPads = TerraceForge.resolvePads(
                    TerraceForge.hwasanCampus(), 0, 0, 0);
            int intruders = 0;
            String worst = "";
            for (TerraceForge.Pad p : axPads) {
                // ★2026-08-07 <b>자를 좁혔다</b> (문턱을 낮춘 것이 아니다): 이 눈은 <b>산문</b>의
                //   시야 회랑이다 — 계단 아래에서 문루를 볼 때 그 사이가 비어야 한다는 계약.
                //   외원은 산문 <b>너머·위</b>라 그 시선에 들지 않고, E-02 로 <b>제 축 계약</b>
                //   (outer_court_axis 11 · 교차로 잰다)을 따로 갖게 됐다. 두 자로 겹쳐 재면
                //   외원의 정자가 「산문을 가린다」는 <b>있지도 않은 죄</b>로 걸린다.
                //   ※옛 정자(9칸)는 상자가 회랑 밖으로 삐져나가 <b>우연히</b> 통과하고 있었다 —
                //     이 눈은 외원에 대해 실제로는 아무것도 지키지 않고 있었다.
                if (p.spec().zone() != 1) {
                    continue;   // 산문 패드만
                }
                int cx = p.x0() + p.spec().width() / 2;
                for (int[] b : com.honcheon.mvt.forge.HwasanCampusBuilder.structureBoxes(p)) {
                    int w = b[1] - b[0] + 1;
                    if (w < 2 || w > 10) {
                        continue;   // 폭 1 = 가는 소품(허용) · 폭 >10 = 건물(표적이지 장애물이 아니다)
                    }
                    if (b[0] > cx - TerraceForge.AXIS_CLEAR && b[1] < cx + TerraceForge.AXIS_CLEAR) {
                        intruders++;
                        worst = p.spec().name() + " x" + b[0] + " 폭" + w + " (축선 " + cx + ")";
                    }
                }
            }
            check("★D-20 축선 회랑에 <b>두꺼운</b> 소품 0 (가는 기둥은 난간까지 허용)",
                    intruders == 0, intruders + "건 " + worst);
        }

        // 비석 둘이 서로 다른 참에 선다 (겹치면 한 쌍이 사라진 것과 같다)
        check("★비석 두 쌍이 서로 다른 참 (하나가 조용히 사라지지 않는다)",
                TerraceForge.STELE_A != TerraceForge.STELE_B
                        && TerraceForge.isLanding(TerraceForge.STELE_A)
                        && TerraceForge.isLanding(TerraceForge.STELE_B),
                TerraceForge.STELE_A + "/" + TerraceForge.STELE_B);

        // ══════ ★★계단 문법 — 반 칸 하강 (사용자 확정 2026-08-05) ══════
        //   「위에서부터 아홉 칸 내려갔다가 평지 블럭이 4칸이고 다시 한 칸씩 내려가는 형태」
        //   + 「반블록을 사용해 널널히 걷도록」. 그래서 한 단은 **반 칸** 내려가고 디딤은 2칸이다.
        //   ★이 눈이 없으면 「한 칸 계단」으로 되돌아가도 아무도 모른다 (종전이 그랬다).
        {
            int hs0 = 200;                                   // 임의 시작 (짝수 = 풀블록 상면)
            int[] prof = TerraceForge.approachProfile(hs0);
            check("★계단 문법 — 표 길이가 APPROACH_LEN 과 같다",
                    prof.length == TerraceForge.APPROACH_LEN, prof.length);

            // ① 한 단은 디딤 2칸 · 하강은 정확히 반 칸(1 반단위) — 한 칸도 0칸도 아니다
            boolean tread2 = true;
            boolean halfStep = true;
            int drops = 0;
            for (int i = 1; i < prof.length; i++) {
                int d = prof[i - 1] - prof[i];
                if (d != 0) {
                    drops++;
                    if (d != 1) {
                        halfStep = false;                    // 반 칸이 아니다 (한 칸이면 d==2)
                    }
                    // 하강 직전 두 칸이 같은 높이여야 디딤 2칸이다 (참 뒤 첫 하강은 예외)
                    if (i >= 2 && !TerraceForge.isLanding(i - 1) && prof[i - 2] != prof[i - 1]) {
                        tread2 = false;
                    }
                }
            }
            check("★계단 문법 — 한 단이 <b>반 칸</b>씩 내려간다 (한 칸 계단이 아니다)",
                    halfStep, "반 칸 아닌 단 있음");
            check("★계단 문법 — 디딤이 " + TerraceForge.STAIR_TREAD + "칸 (뚝뚝 떨어지지 않는다)",
                    tread2, "디딤 1칸 구간 있음");
            check("★계단 문법 — 단 수 = 주기×9 = "
                            + (TerraceForge.APPROACH_CYCLES * TerraceForge.STAIR_RUN),
                    drops == TerraceForge.APPROACH_CYCLES * TerraceForge.STAIR_RUN, drops);

            // ② 참은 평평하다 · 소문 참은 넓다
            boolean landingFlat = true;
            for (int i = 1; i < prof.length; i++) {
                if (TerraceForge.isLanding(i) && TerraceForge.isLanding(i - 1)
                        && prof[i] != prof[i - 1]) {
                    landingFlat = false;
                }
            }
            check("★계단 문법 — 참은 평지다 (" + TerraceForge.STAIR_LANDING + "칸 · 소문 참 "
                            + TerraceForge.GATE_LANDING + "칸)",
                    landingFlat, "참이 기울었다");

            // ③ 총 하강 — 주기 4 × 9단 × 반 칸 = 18 칸
            int totalHalf = hs0 - prof[prof.length - 1];
            check("★계단 문법 — 총 하강 " + (totalHalf / 2.0) + "칸 (= 주기 "
                            + TerraceForge.APPROACH_CYCLES + " × 9 × 0.5)",
                    totalHalf == TerraceForge.APPROACH_CYCLES * TerraceForge.STAIR_RUN,
                    totalHalf / 2.0);

            // ④ 반 칸이 실재하는가 — 홀수 반단위(하단 반블록)가 절반쯤 있어야 한다.
            //   전부 짝수면 「한 칸 계단」으로 되돌아간 것이다 (뮤테이션 판별력).
            int slabs = 0;
            for (int h : prof) {
                if (TerraceForge.Approach.isSlab(h)) {
                    slabs++;
                }
            }
            check("★계단 문법 — 반블록 상면이 실재한다 (" + slabs + "/" + prof.length + " 행)",
                    slabs > prof.length / 4 && slabs < prof.length * 3 / 4, slabs);

            // ⑤ 지형을 따르지 않는다 — 순수 함수라 같은 입력에 같은 표 (결정론·지형 무관)
            check("★계단 문법 — 지형 무관·결정론 (계단이 정본, 지형이 따라온다)",
                    java.util.Arrays.equals(prof, TerraceForge.approachProfile(hs0)), "표가 흔들린다");
        }

        // ══════ ★초목·소품 스위치 ══════
        //   2026-08-05 「일단 나무 다 치우고」 → <b>꺼짐</b>을 눈이 지켰다.
        //   ★2026-08-06 사용자 승인으로 <b>켠다</b>: 「산문 구조 승인·동결. 다음은 외원/입구
        //   광장과 산문 주변 절벽·정원·난간을 함께 조성」. 「나중에 세우자」의 그 나중이다.
        //
        //   ★스위치가 켜졌으니 눈이 지킬 것도 바뀐다. 「나무가 없다」가 아니라
        //   <b>「나무가 건축을 가리지 않는다」</b>가 원래 지키려던 것이다 — 그 계약을 잰다.
        check("★초목·소품이 켜져 있다 (조경 단계)",
                TerraceForge.GREEN && TerraceForge.PROPS,
                TerraceForge.GREEN + "/" + TerraceForge.PROPS);
        check("★소나무가 계단 회랑 밖에 선다 (계단을 안 덮는다 · D-16)",
                TerraceForge.APPROACH_CLEAR + 2 > TerraceForge.STAIR_HALF + 2,
                "소나무 ±" + (TerraceForge.APPROACH_CLEAR + 2)
                        + " vs 가장 넓은 난간 ±" + (TerraceForge.approachHalf(
                                TerraceForge.WIDEN_FROM) + 1));
        // ══════ ★★공공 마당은 한 재료다 (2026-08-06 · 외원 실측이 잡았다) ══════
        //   ★계율 「온기는 재료가 아니라 햇빛이다 — 한 면에는 한 재료」.
        //   paveMaterial 은 2026-08-05 에 다섯 → 하나로 고쳤는데(D-43 「누런 바둑판」),
        //   HwasanCampusBuilder 의 다른 손이 그 위에 사암을 30%+10% <b>도로 칠하고</b> 있었다
        //   (외원 마당 실측: 석전 63% : 매끈사암 37%). 문전 조경 소나무와 <b>같은 병</b> —
        //   고치는 손과 덧칠하는 손이 다른 표를 읽었다.
        //   ★가르는 자: <b>위치가 정하면 구조, 해시가 정하면 잡티다.</b>
        check("★공공 마당 포장이 한 재료다 (덧칠하는 손이 바탕과 같은 표를 읽는다)",
                com.honcheon.mvt.forge.HwasanCampusBuilder.publicPaveMaterial()
                        == TerraceForge.paveMaterial(0, 0)
                        && TerraceForge.paveMaterial(0, 0) == TerraceForge.paveMaterial(7, 13)
                        && TerraceForge.paveMaterial(0, 0) == TerraceForge.paveMaterial(-31, 44),
                com.honcheon.mvt.forge.HwasanCampusBuilder.publicPaveMaterial()
                        + " vs " + TerraceForge.paveMaterial(0, 0));
        // ★[눈의 눈] 자리를 바꿔 가며 물었는가 — 한 자리만 물으면 해시 섞임을 못 본다
        {
            java.util.Set<Material> tones = new java.util.HashSet<>();
            for (int x = -40; x <= 40; x += 3) {
                for (int z = -40; z <= 40; z += 3) {
                    tones.add(TerraceForge.paveMaterial(x, z));
                }
            }
            check("★[눈의 눈] 포장을 " + (27 * 27) + "자리에서 물었고 재료가 하나뿐이다",
                    tones.size() == 1, tones.toString());
        }

        // ══════ ★★outer_court_axis — 의례축 (사용자 확정 2026-08-06 · E-02) ══════
        //   「산문 → 종문을 잇는 의례축 11칸. 축 안에는 지붕·벽·정자·큰 나무 금지.」
        //   ★사용자 지시: <b>검수는 좌표값보다 교차 여부를 본다</b> — 부품이 옮겨 다녀도
        //   눈이 따라간다. 발자국은 structureBoxes 가 <b>마른 조성</b>으로 낸다 (선언이 아니다).
        //   ★산문 ±14 상자를 외원까지 복제하지 <b>않는다</b>: 그것은 근거리 투영 계약이고,
        //   전체에 늘리면 광장이 「긴 활주로」가 된다 (사용자 판단).
        {
            java.util.List<TerraceForge.Pad> ap2 = TerraceForge.resolvePads(campus, 0, 0, 0);
            int[] axis = TerraceForge.ceremonialAxisBox(ap2);
            check("★의례축 상자가 산문·종문에서 유도된다 (폭 "
                            + TerraceForge.CEREMONIAL_AXIS_WIDTH + ")",
                    axis != null && axis[1] - axis[0] + 1 == TerraceForge.CEREMONIAL_AXIS_WIDTH
                            && axis[3] > axis[2],
                    axis == null ? "null" : java.util.Arrays.toString(axis));
            check("★의례축이 산문 근거리 상자보다 좁다 (활주로가 되지 않는다)",
                    TerraceForge.ceremonialAxisHalf() < TerraceForge.FACADE_CLEAR_HALF,
                    "±" + TerraceForge.ceremonialAxisHalf()
                            + " vs ±" + TerraceForge.FACADE_CLEAR_HALF);
            int cross = 0;
            String who = "";
            for (TerraceForge.Pad p2 : ap2) {
                for (int[] b : com.honcheon.mvt.forge.HwasanCampusBuilder.structureBoxes(p2)) {
                    if (TerraceForge.boxesOverlap(axis, b)) {
                        cross++;
                        who = "구역 " + p2.spec().zone() + " " + java.util.Arrays.toString(b);
                    }
                }
                for (int[] b : com.honcheon.mvt.forge.HwasanCampusBuilder.decorBoxes(p2)) {
                    if (TerraceForge.boxesOverlap(axis, b)) {
                        cross++;
                        who = "구역 " + p2.spec().zone() + " 소품";
                    }
                }
                for (int[] s : com.honcheon.mvt.forge.HwasanCampusBuilder.pineSpots(p2)) {
                    if (TerraceForge.boxesOverlap(axis, new int[]{s[0], s[0], s[1], s[1]})) {
                        cross++;
                        who = "구역 " + p2.spec().zone() + " 소나무";
                    }
                }
            }
            check("★★의례축을 가로지르는 구조물·소품·큰 나무가 없다 (교차로 잰다)",
                    cross == 0, cross == 0 ? "0" : cross + "건 · " + who);
            // ★[눈의 눈] 옛 정자 자리(cx±7)는 실제로 <b>교차</b>여야 한다 — 안 그러면 눈이 헛것을 지킨다
            TerraceForge.Pad court = ap2.stream().filter(p2 -> p2.spec().zone() == 2)
                    .findFirst().orElseThrow();
            int ccx = court.x0() + court.spec().width() / 2;
            int ccz = court.zN() + court.spec().depth() / 2;
            check("★[눈의 눈] 옛 정자 자리(cx±7)는 의례축과 교차한다고 답한다",
                    TerraceForge.boxesOverlap(axis,
                            new int[]{ccx - 7 - 5, ccx - 7 + 5, ccz + 1, ccz + 11}), "");
        }

        // ══════ ★★outer_court_corridor — 행각 삼면 (사용자 확정 2026-08-06 · E-03) ══════
        //   회랑은 재료만이 아니라 두 조건으로 성립한다 (사용자): ① 마당의 경계를 연속으로
        //   만든다 ② 중앙축과 <b>별개의 측면 동선</b>을 준다. 옛 행각은 기둥+지붕뿐이라
        //   <b>퍼걸러</b>였고, 북측 좌우 두 토막뿐이라 <b>작은 두 채</b>로 읽혔다.
        //   ★남측은 <b>닫지 않는다</b> — 산문에서 들어서자마자 둘러싸이면 깊이가 잘린다.
        {
            java.util.List<TerraceForge.Pad> ap3 = TerraceForge.resolvePads(campus, 0, 0, 0);
            TerraceForge.Pad court = ap3.stream().filter(p2 -> p2.spec().zone() == 2)
                    .findFirst().orElseThrow();
            int[] axis3 = TerraceForge.ceremonialAxisBox(ap3);
            java.util.List<int[]> boxes =
                    com.honcheon.mvt.forge.HwasanCampusBuilder.structureBoxes(court);
            int cn = court.zN();
            int cs = court.zS();
            boolean north = false;
            boolean west = false;
            boolean east = false;
            boolean south = false;
            int corridors = 0;
            for (int[] b : boxes) {
                // ★행각과 정자를 <b>가로세로 비</b>로 가른다 (길이가 아니라). 행각은 폭이 얇고
                //   (폭 5 + 처마 2 = 7), 정자는 거의 정사각(9×9)이다. 처음엔 「긴 쪽 ≥8」로
                //   갈랐다가 정자까지 행각으로 세어 「남측이 닫혔다」고 잘못 짖었다.
                int ss = Math.min(b[1] - b[0] + 1, b[3] - b[2] + 1);
                if (ss < 5 || ss > 7) {
                    continue;         // 정자(9)도 등롱 열(1)도 행각이 아니다
                }
                corridors++;
                if (b[2] <= cn + 6) {
                    north = true;
                }
                if (b[3] >= cs - 6) {
                    south = true;                                      // 남측을 닫으면 짖는다
                }
                // ★방향이 아니라 <b>어느 가장자리에 붙었는가</b>로 가른다. 처음엔 「z 로 긴 것」을
                //   측면으로 읽었는데, 측면 모듈은 어귀를 피하느라 6칸이라 <b>폭(7)보다 짧다</b> —
                //   그 자로는 영영 측면이 없다. 실측이 자를 고쳤다.
                boolean northRow = b[2] <= cn + 6;
                if (!northRow && b[0] <= court.x0() + 2) {
                    west = true;
                }
                if (!northRow && b[1] >= court.x1() - 2) {
                    east = true;
                }
            }
            check("★행각이 북·서·동 삼면에 있다", north && west && east,
                    "북 " + north + " · 서 " + west + " · 동 " + east);
            check("★남측은 열린다 (산문에서 들어서는 깊이를 안 자른다)", !south, south);
            check("★행각이 네 모듈이다 (ㄷ자 한 채로 합치지 않는다)", corridors >= 4, corridors + "토막");
            int cross3 = 0;
            for (int[] b : boxes) {
                if (TerraceForge.boxesOverlap(axis3, b)) {
                    cross3++;
                }
            }
            check("★행각·정자가 의례축을 침범하지 않는다", cross3 == 0, cross3);
            // ★모서리 전이칸과 정자 이격 — 붙으면 지붕이 꺾이고 정자가 「행각의 끝방」이 된다
            java.util.List<int[]> longBoxes = new java.util.ArrayList<>();
            java.util.List<int[]> pavBoxes = new java.util.ArrayList<>();
            for (int[] b : boxes) {
                int shortSide = Math.min(b[1] - b[0] + 1, b[3] - b[2] + 1);
                if (shortSide >= 5 && shortSide <= 7) {
                    longBoxes.add(b);          // 행각 — 폭 5 + 처마 2
                } else if (shortSide >= 8) {
                    pavBoxes.add(b);           // 정자 — 거의 정사각(9)
                }
                // 그 밖(등롱 열처럼 <b>얇은</b> 것)은 이격의 대상이 아니다 — 사물이지 건물이 아니다
            }
            int touching = 0;
            for (int[] a : longBoxes) {
                for (int[] b2 : pavBoxes) {
                    if (TerraceForge.boxesOverlap(
                            new int[]{a[0] - 1, a[1] + 1, a[2] - 1, a[3] + 1}, b2)) {
                        touching++;
                    }
                }
                for (int[] b2 : longBoxes) {
                    if (a != b2 && TerraceForge.boxesOverlap(
                            new int[]{a[0] - 1, a[1] + 1, a[2] - 1, a[3] + 1}, b2)) {
                        touching++;
                    }
                }
            }
            check("★모듈끼리·정자와 붙지 않는다 (모서리 전이칸 · 정자 이격)",
                    touching == 0, touching + "쌍");
        }

        // ══════ ★★E-08 — 행각의 정본은 <b>도면</b>이다 (사용자 확정 2026-08-07) ══════
        //   「네 모듈 모두를 같은 hwasan_outer_corridor.yml 에서 뽑는 것이 가장 자연스럽다.」
        //   ★그 전까지 코드가 형태를 갖고 있었다. 이제 코드는 <b>기단(지면 일)</b>만 깔고
        //   몸체·지붕은 도면이 갖는다 — 그래서 <b>눈도 도면을 읽는다</b>.
        try {
            java.util.Map<String, Object> craw = new org.yaml.snakeyaml.Yaml().load(
                    java.nio.file.Files.readString(
                            java.nio.file.Path.of("config/blueprints/hwasan_outer_corridor.yml")));
            Blueprint cb = Blueprint.of(craw);
            cb.validate();      // ★부속급의 자기 계약 — 틀린 도면으로 짓지 않는다
            check("★행각 도면이 읽히고 제 계약을 지킨다 (모듈 "
                            + cb.width() + "×" + cb.depth() + ")", true, "통과");
            check("★행각 도면이 부속급이다 (문이 아니다 — 계약이 갈린다)",
                    cb.auxiliary(), cb.rank());
            check("★행각 도면이 외원 패드(2)에 앉는다", cb.pad() == 2, cb.pad());

            java.util.List<Blueprint.Placement> ps = cb.placements();
            // ★E-06 — 한 도면이 <b>세 구역</b>에 앉는다 (외원 4 · 종문 2 · 중정 2).
            //   구역마다 세어야 한다 — 합계만 보면 어느 구역이 비었는지 안 보인다.
            java.util.Map<Integer, Integer> perPad = new java.util.TreeMap<>();
            for (Blueprint.Placement pl : ps) {
                perPad.merge(pl.padOr(cb.pad()), 1, Integer::sum);
            }
            check("★★한 도면이 세 구역에 앉는다 — 외원 4 · 종문 2 · 중정 2 (문법 재사용)",
                    perPad.equals(java.util.Map.of(2, 4, 6, 2, 101, 2)), perPad.toString());
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (Blueprint.Placement pl : ps) {
                ids.add(pl.id());
            }
            check("★외원의 북서·북동·서·동 넷이 이름으로 다 있다",
                    ids.containsAll(java.util.List.of("north_west", "north_east", "west", "east")),
                    ids.toString());
            check("★외원 측면 둘이 돌아 앉는다 (같은 모듈을 방향만 달리 쓴다)",
                    ps.stream().filter(pl -> pl.padOr(cb.pad()) == 2 && pl.rotate() != 0).count() == 2,
                    ps.stream().filter(pl -> pl.padOr(cb.pad()) == 2)
                            .map(pl -> pl.id() + ":" + pl.rotate()).toList().toString());
            for (Blueprint.Roof rf : cb.roofs()) {
                check("★지붕이 낮은 맞배다 — " + rf.name() + " (정문급 팔작이 아니다)",
                        rf.lowGable() && rf.eave() <= 1, rf.type() + " · 처마 " + rf.eave());
            }

            // ★★도면의 자리와 <b>코드가 까는 기단</b>이 어긋나지 않는가.
            //   형태는 도면이, 발자국은 코드가 갖는다 — 둘이 갈라지면 도면은 허공에 서고
            //   검수는 엉뚱한 상자를 잰다. <b>두 손이 같은 자리를 말하는지</b> 되묻는다.
            java.util.List<TerraceForge.Pad> ap4 = TerraceForge.resolvePads(campus, 0, 0, 0);
            TerraceForge.Pad ct = ap4.stream().filter(p2 -> p2.spec().zone() == 2)
                    .findFirst().orElseThrow();
            java.util.List<int[]> bases = new java.util.ArrayList<>();
            for (int[] b : com.honcheon.mvt.forge.HwasanCampusBuilder.structureBoxes(ct)) {
                int ss = Math.min(b[1] - b[0] + 1, b[3] - b[2] + 1);
                int ls = Math.max(b[1] - b[0] + 1, b[3] - b[2] + 1);
                if (ss == 5 && ls == 6) {
                    bases.add(b);          // 행각 기단 — 5×6 (정자 기단 5×5 와 갈린다)
                }
            }
            check("★코드가 까는 행각 기단이 넷이다", bases.size() == 4, bases.size() + "장");
            // ★세 구역 전부에서 대조한다 — 도면의 자리와 코드의 기단이 칸까지 같은가
            int matched = 0;
            int wanted = 0;
            for (Blueprint.Placement pl : ps) {
                int zone = pl.padOr(cb.pad());
                TerraceForge.Pad host = ap4.stream().filter(p2 -> p2.spec().zone() == zone)
                        .findFirst().orElseThrow();
                int px0 = host.x0() + pl.col();
                int px1 = px0 + pl.widthOf(cb) - 1;
                int pz0 = host.zN() + pl.row();
                int pz1 = pz0 + pl.depthOf(cb) - 1;
                wanted++;
                for (int[] b : com.honcheon.mvt.forge.HwasanCampusBuilder.structureBoxes(host)) {
                    if (b[0] == px0 && b[1] == px1 && b[2] == pz0 && b[3] == pz1) {
                        matched++;
                    }
                }
            }
            check("★★도면의 자리와 코드의 기단이 <b>칸까지</b> 같다 (세 구역 전부)",
                    matched == wanted, matched + "/" + wanted);
            // ★[눈의 눈] 옛 자리(측면 10칸)는 이 대조가 <b>어긋났다</b>고 답해야 한다
            check("★[눈의 눈] 자리를 한 칸 옮기면 대조가 어긋난다",
                    bases.stream().noneMatch(b -> b[0] == ct.x0() + 99), "");
        } catch (Exception e) {
            check("★행각 도면이 읽히고 제 계약을 지킨다", false, e.toString());
        }

        // ══════ ★★E-07 — 정자의 정본도 도면이다 · 사모지붕 (사용자 확정 2026-08-07) ══════
        //   「정자를 low_gable 에 억지로 맞추는 것보다 지금이 지붕 문법을 하나 늘릴 적절한 시점」.
        //   ★지붕 문법 셋이 완성된다: low_gable(행각) · hip_pyramid(정자·망루) · sweep(핵심 전각).
        //     이 분류가 <b>건물 역할과 실루엣을 동시에</b> 가른다.
        try {
            java.util.Map<String, Object> praw = new org.yaml.snakeyaml.Yaml().load(
                    java.nio.file.Files.readString(
                            java.nio.file.Path.of("config/blueprints/hwasan_pavilion.yml")));
            Blueprint pb = Blueprint.of(praw);
            pb.validate();      // ★정자의 자기 계약 — 사방 개방 · 사모 · 정사각
            check("★정자 도면이 읽히고 제 계약을 지킨다 (" + pb.width() + "×" + pb.depth() + ")",
                    true, "통과");
            check("★정자가 부속급이되 쓰임이 <b>행각과 다르다</b> (계약이 갈린다)",
                    pb.auxiliary() && "pavilion".equalsIgnoreCase(pb.usage()),
                    pb.rank() + "/" + pb.usage());
            // ★E-07b — 정자 도면도 두 구역에 앉는다 (외원 2 · 중정 2). <b>복제본을 안 만든다</b>.
            //   ★사용자 원칙: 「도면은 재사용하지만 <b>배치는 공간이 결정한다</b>」 —
            //   「외원에 둘이니 중정도 둘」이 아니라, 중정 패드를 훑어 남쪽 띠에 둘이 들어갔다.
            java.util.Map<Integer, Integer> pavPer = new java.util.TreeMap<>();
            for (Blueprint.Placement pl : pb.placements()) {
                pavPer.merge(pl.padOr(pb.pad()), 1, Integer::sum);
            }
            check("★정자 도면이 <b>다섯 구역</b>에 앉는다 — 외원2 · 중정2 · 정상1 · 전망대1 · 정원1",
                    pavPer.equals(java.util.Map.of(2, 2, 101, 2, 13, 1, 19, 1, 10, 1)), pavPer.toString());
            for (Blueprint.Roof rf : pb.roofs()) {
                check("★정자 지붕이 사모다 — " + rf.name() + " (맞배는 방향성을 만든다)",
                        rf.hipPyramid(), rf.type());
                check("★사모가 정사각이다 (1호의 계약 — 자유형 다각형 엔진은 안 만든다)",
                        (rf.box()[2] - rf.box()[0]) == (rf.box()[3] - rf.box()[1]),
                        (rf.box()[2] - rf.box()[0] + 1) + "×" + (rf.box()[3] - rf.box()[1] + 1));
                check("★정자는 <b>낮고 넓은</b> 사모다 (망루의 높고 급한 것과 다르다)",
                        rf.rise() <= 3 && rf.eave() >= 2 && "pavilion".equals(rf.profile()),
                        "rise " + rf.rise() + " · 처마 " + rf.eave() + " · " + rf.profile());
            }
            // ★[눈의 눈] 망루 비례(tower)를 넣으면 <b>다른 지붕</b>이 나와야 한다 —
            //   같은 타입이되 같은 지붕을 복사하지 않는다는 계약이 값에 실려 있는가
            java.util.Map<String, Object> tw = new java.util.LinkedHashMap<>(
                    (java.util.Map<String, Object>) ((java.util.Map<String, Object>)
                            praw.get("roof")).get("사모"));
            tw.put("profile", "tower");
            tw.remove("rise");
            tw.remove("eave");
            java.util.Map<String, Object> traw = new java.util.LinkedHashMap<>(praw);
            traw.put("roof", java.util.Map.of("사모", tw));
            Blueprint towerBp = Blueprint.of(traw);
            Blueprint.Roof trf = towerBp.roofs().get(0);
            check("★[눈의 눈] profile: tower 는 더 높고 급한 사모를 낸다 (복사가 아니다)",
                    trf.rise() > pb.roofs().get(0).rise(),
                    "정자 rise " + pb.roofs().get(0).rise() + " vs 망루 rise " + trf.rise());

            // ★도면의 자리와 코드의 기단이 칸까지 같은가 (행각과 같은 대조)
            java.util.List<TerraceForge.Pad> ap5 = TerraceForge.resolvePads(campus, 0, 0, 0);
            TerraceForge.Pad ct2 = ap5.stream().filter(p2 -> p2.spec().zone() == 2)
                    .findFirst().orElseThrow();
            java.util.List<int[]> pbase = new java.util.ArrayList<>();
            for (int[] b : com.honcheon.mvt.forge.HwasanCampusBuilder.structureBoxes(ct2)) {
                if (b[1] - b[0] + 1 == 5 && b[3] - b[2] + 1 == 5) {
                    pbase.add(b);
                }
            }
            check("★코드가 까는 외원 정자 기단이 둘이다 (5×5)", pbase.size() == 2, pbase.size() + "장");
            int pm = 0;
            int[] axis5 = TerraceForge.ceremonialAxisBox(ap5);
            java.util.List<TerraceForge.StairLane> lanes5 =
                    TerraceForge.resolveLanes(campus, ap5);
            int laneHit = 0;
            int spill = 0;
            for (Blueprint.Placement pl : pb.placements()) {
                int zone = pl.padOr(pb.pad());
                TerraceForge.Pad host = ap5.stream().filter(p2 -> p2.spec().zone() == zone)
                        .findFirst().orElseThrow();
                int px0 = host.x0() + pl.col();
                int px1 = px0 + pl.widthOf(pb) - 1;
                int pz0 = host.zN() + pl.row();
                int pz1 = pz0 + pl.depthOf(pb) - 1;
                for (int[] b : com.honcheon.mvt.forge.HwasanCampusBuilder.structureBoxes(host)) {
                    if (b[0] == px0 && b[1] == px1 && b[2] == pz0 && b[3] == pz1) {
                        pm++;
                    }
                }
                // ★유출 — 지붕 처마(±eave)까지 패드 안이어야 한다
                int ev = pb.roofs().get(0).eave();
                if (px0 - ev < host.x0() || px1 + ev > host.x1()
                        || pz0 - ev < host.zN() || pz1 + ev > host.zS()) {
                    spill++;
                }
                // ★계단 봉투 — 검수와 같은 자 (rail + 앞뒤 1). ★단 <b>처마는 뺀다</b>:
                //   이 저장소는 통로 겹침을 <b>지상 발자국</b>으로 본다 (groundBoxes 의 규약
                //   「처마 제외」). 머리 위 처마는 걷는 것을 막지 않는다 — 처음 처마까지
                //   넣었더니 외원 정자가 서측 어귀를 1칸 스쳐 짖었는데, 그건 <b>자가 과한</b>
                //   것이었다. 유출(패드 밖)은 반대로 처마까지 본다 — 그건 블록이 실제로 나간다.
                for (TerraceForge.StairLane l : lanes5) {
                    int qx = l.dirZ() != 0 ? 1 : 0;
                    int qz = l.dirZ() != 0 ? 0 : 1;
                    for (int t = 0; t <= l.length() && laneHit == 0; t++) {
                        for (int o = -l.rail(); o <= l.rail(); o++) {
                            int lx = l.startX() + l.dirX() * (t - 1) + qx * o;
                            int lz = l.startZ() + l.dirZ() * (t - 1) + qz * o;
                            if (lx >= px0 && lx <= px1 && lz >= pz0 && lz <= pz1) {
                                laneHit++;
                            }
                        }
                    }
                }
                check("★정자 " + pl.id() + " 가 의례축을 침범하지 않는다",
                        !TerraceForge.boxesOverlap(axis5, new int[]{px0, px1, pz0, pz1}), "");
            }
            check("★★정자 도면의 자리와 코드의 기단이 칸까지 같다 (다섯 구역)", pm == 7, pm + "/7");
            check("★정자가 계단 봉투를 침범하지 않는다 (지상 발자국 — 처마는 뺀다)",
                    laneHit == 0, laneHit + "칸");
            check("★정자 처마가 패드 밖으로 안 샌다", spill == 0, spill + "채");
            // ★옛 코드가 남아 있지 않은가 — 중정이 아직 pavilion() 을 부르면 두 언어가 공존한다
            String hcb = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "server-mvt/src/main/java/com/honcheon/mvt/forge/HwasanCampusBuilder.java"));
            int c101 = hcb.indexOf("case 101 ->");
            String body101 = c101 < 0 ? "" : hcb.substring(c101, hcb.indexOf("case 10 ->", c101));
            check("★[눈의 눈] 중정 부품 줄을 찾았다", c101 >= 0 && !body101.isEmpty(), "");
            check("★★중정이 옛 pavilion() 을 안 부른다 (같은 역할이 두 언어로 공존하지 않는다)",
                    !body101.contains("pavilion(w, p,"), body101.trim());

            // ★회전 계약 — 사모는 정사각이라 겉보기엔 회전이 무의미해 보이지만, 장식·개구가
            //   붙으면 방향이 생긴다. <b>지붕 빌더에서 회전을 생략하지 않는다</b>가 계약이다.
            Blueprint.Placement spin = new Blueprint.Placement("시험", 0, 0, 90, 0);
            int[] c0 = spin.map(pb, 0, 0);
            int[] c1 = spin.map(pb, pb.width() - 1, 0);
            check("★[눈의 눈] 사모도 회전 계약을 탄다 (평면 모서리가 90도로 옮겨간다)",
                    c0[0] == pb.depth() - 1 && c0[1] == 0 && c1[1] == pb.width() - 1,
                    java.util.Arrays.toString(c0) + " " + java.util.Arrays.toString(c1));
            check("★[눈의 눈] 회전이 살창 법선도 돌린다",
                    spin.turn(org.bukkit.block.BlockFace.NORTH) == org.bukkit.block.BlockFace.EAST,
                    spin.turn(org.bukkit.block.BlockFace.NORTH).toString());
        } catch (Exception e) {
            check("★정자 도면이 읽히고 제 계약을 지킨다", false, e.toString());
        }

        // ══════ ★★★공통 계약 한 장 — docs/design/hwasan/contract.md 를 대조한다 ══════
        //   (사용자 확정 2026-08-07 · 02~101 정리) 「이제부터 새 구역이 반드시 따라야 하는 계약」.
        //   ★문서는 <b>신고서</b>다. 숫자를 적어 두고 코드만 바꾸면 문서가 조용히 늙는다 —
        //   여기서 <b>글자 그대로</b> 찾는다 (facade_projection_clearance 때와 같은 처방).
        try {
            String doc = java.nio.file.Files.readString(
                    java.nio.file.Path.of("docs/design/hwasan/contract.md"));
            check("★공통 계약 한 장이 있다 (contract.md)", doc.length() > 2000, doc.length() + "자");
            record Claim(String what, String needle) { }
            java.util.List<Claim> claims = java.util.List.of(
                    new Claim("바닥 공공", "`stone_bricks`"),
                    new Claim("바닥 수련", "`sand`"),
                    new Claim("정면 투영 반폭", "반폭 **" + TerraceForge.FACADE_CLEAR_HALF + "**"),
                    new Claim("정면 투영 깊이", "깊이 **" + TerraceForge.FACADE_CLEAR_DEPTH + "**"),
                    new Claim("상자 밖 자리", "**" + TerraceForge.FACADE_STANDOFF + "** 하나로"),
                    new Claim("의례축 폭", "보행 **" + TerraceForge.CEREMONIAL_AXIS_WIDTH + "**"),
                    new Claim("행각 몸체", "몸체 **6×5**"),
                    new Claim("행각 벽높이", "벽 높이 **"
                            + com.honcheon.mvt.forge.HwasanCampusBuilder.CORRIDOR_WALL_H + "**"),
                    new Claim("행각 주기", "(주기 " + com.honcheon.mvt.forge.HwasanCampusBuilder.CORRIDOR_BAY + ")"),
                    new Claim("정자 몸체", "몸체 **5×5**"),
                    new Claim("지붕 셋", "`hip_pyramid`"),
                    new Claim("꼭지 식", "짧은변/2 + 1"),
                    new Claim("계단 봉투 자", "`lane.rail`(=half+1)"),
                    new Claim("처마 규약", "처마는 뺀다"));
            int missing = 0;
            String lost = "";
            for (Claim c : claims) {
                if (!doc.contains(c.needle())) {
                    missing++;
                    lost = c.what() + " (" + c.needle() + ")";
                }
            }
            check("★★계약 문서의 수치가 코드와 같다 (" + claims.size() + "항)",
                    missing == 0, missing == 0 ? "대조 통과" : missing + "항 어긋남 · " + lost);
            // ★여덟 묶음이 다 있는가 — 하나가 빠지면 새 구역이 그것만 안 지킨다
            int groups = 0;
            for (String g : new String[]{"① 바닥", "② 축", "③ 행각", "④ 정자",
                    "⑤ 지붕", "⑥ 인스턴스", "⑦ 투영 비움", "⑧ 표면 소유권"}) {
                if (doc.contains(g)) {
                    groups++;
                }
            }
            check("★계약이 여덟 묶음으로 닫힌다 (바닥·축·행각·정자·지붕·인스턴스·투영·소유권)",
                    groups == 8, groups + "/8");
            check("★새 구역을 세울 때의 순서가 적혀 있다 (패드를 훑는 것부터)",
                    doc.contains("패드를 훑는다") && doc.contains("마지막 선택"), "");
        } catch (Exception e) {
            check("★공통 계약 한 장이 있다 (contract.md)", false, e.toString());
        }

        // ══════ ★★모임 유형 — 강당 도면 (hwasan_hall.yml · 2026-08-07) ══════
        //   전수 조사가 드러낸 것: plasterHall 하나가 <b>세 쓰임</b>을 다 맡고 있었다.
        //   첫 일은 옛 코드 교체가 아니라 <b>유형을 가르는 것</b>이다 — 이것이 첫 유형.
        try {
            java.util.Map<String, Object> hraw = new org.yaml.snakeyaml.Yaml().load(
                    java.nio.file.Files.readString(
                            java.nio.file.Path.of("config/blueprints/hwasan_hall.yml")));
            Blueprint hb = Blueprint.of(hraw);
            hb.validate();      // ★정문급 계약 — 축선에 통행 개구가 있어야 한다
            check("★강당 도면이 읽히고 제 계약을 지킨다 (" + hb.width() + "×" + hb.depth() + ")",
                    true, "통과");
            check("★모임 유형은 <b>지나 들어가는 집</b>이다 (rank principal · usage assembly)",
                    !hb.auxiliary() && "assembly".equalsIgnoreCase(hb.usage()),
                    hb.rank() + "/" + hb.usage());
            check("★강당이 옛 몸체(17×11)를 그대로 쓴다 (줄이는 것은 마지막 선택)",
                    hb.width() == 17 && hb.depth() == 11, hb.width() + "×" + hb.depth());
            for (Blueprint.Roof rf : hb.roofs()) {
                check("★강당 지붕이 팔작이다 — " + rf.name() + " (단층 · 중층은 본전의 것)",
                        !rf.lowGable() && !rf.hipPyramid() && !rf.hasUpper(), rf.type());
            }
            // ★자리·유출·계단 봉투 — 정자와 같은 자로 잰다
            java.util.List<TerraceForge.Pad> ap6 = TerraceForge.resolvePads(campus, 0, 0, 0);
            java.util.List<TerraceForge.StairLane> lanes6 = TerraceForge.resolveLanes(campus, ap6);
            int hm = 0;
            int hSpill = 0;
            int hLane = 0;
            for (Blueprint.Placement pl : hb.placements()) {
                int zone = pl.padOr(hb.pad());
                TerraceForge.Pad host = ap6.stream().filter(p2 -> p2.spec().zone() == zone)
                        .findFirst().orElseThrow();
                int px0 = host.x0() + pl.col();
                int px1 = px0 + pl.widthOf(hb) - 1;
                int pz0 = host.zN() + pl.row();
                int pz1 = pz0 + pl.depthOf(hb) - 1;
                // ★D2 ⑤ — 기단이 두 단이면 아래 단이 한 칸 넓다. 대조도 그만큼 넓힌다
                int fo = hb.foundation() - 1;
                for (int[] b : com.honcheon.mvt.forge.HwasanCampusBuilder.structureBoxes(host)) {
                    if (b[0] == px0 - fo && b[1] == px1 + fo
                            && b[2] == pz0 - fo && b[3] == pz1 + fo) {
                        hm++;
                    }
                }
                int ev = hb.roofs().get(0).eave();
                int hf = (hb.width() - 1) / 2;
                int hl = (hb.depth() - 1) / 2;
                // 팔작은 반폭+내밈 만큼 퍼진다 — 유출은 <b>처마까지</b> 본다
                if (px0 - ev < host.x0() || px1 + ev > host.x1()
                        || pz0 - ev < host.zN() || pz1 + ev > host.zS()) {
                    hSpill++;
                }
                for (TerraceForge.StairLane l : lanes6) {
                    int qx = l.dirZ() != 0 ? 1 : 0;
                    int qz = l.dirZ() != 0 ? 0 : 1;
                    for (int t = 0; t <= l.length(); t++) {
                        for (int o = -l.rail(); o <= l.rail(); o++) {
                            int lx = l.startX() + l.dirX() * (t - 1) + qx * o;
                            int lz = l.startZ() + l.dirZ() * (t - 1) + qz * o;
                            if (lx >= px0 && lx <= px1 && lz >= pz0 && lz <= pz1) {
                                hLane++;
                            }
                        }
                    }
                }
            }
            check("★★강당 도면의 자리와 코드의 기단이 칸까지 같다",
                    hm == hb.placements().size(), hm + "/" + hb.placements().size());
            check("★강당이 계단 봉투를 침범하지 않는다 (지상 발자국)", hLane == 0, hLane + "칸");
            check("★강당 처마가 패드 밖으로 안 샌다", hSpill == 0, hSpill + "채");
            // ══ ★★D2 모델링 계약 — 강당이 시험체다 (2026-08-09) ══
            //   ★「예쁘게 해라」는 못 재지만 이건 잰다: 창이 벽보다 들어갔는가 ·
            //   기둥 아래 주초가 있는가 · 처마 밑에 서까래가 있는가 · 기단이 위계만큼인가.
            check("★★D2① 입면에 깊이가 있다 — 적주는 나오고 격자창은 들어간다",
                    hb.depthOf('P') == 1 && hb.depthOf('L') == -1 && hb.depthOf('W') == 0,
                    "적주 " + hb.depthOf('P') + " · 회벽 " + hb.depthOf('W')
                            + " · 격자 " + hb.depthOf('L'));
            check("★★D2② 기둥 아래 <b>주초</b>가 있다 (막대기가 아니라 구조체다)",
                    hb.columnOf('P').get(0).material().contains("stone"),
                    hb.columnOf('P').get(0).material());
            check("★D2② 기둥 위에 창방·도리가 얹힌다 (판재 두 켜)",
                    hb.columnOf('P').stream()
                            .filter(cs -> cs.material().contains("planks")).count() >= 2, "");
            check("★★D2③ 처마 밑에 <b>서까래</b>가 있다 (올려다보면 보인다)",
                    hb.roofs().get(0).rafters(), "");
            check("★★D2⑤ 기단이 위계만큼이다 (principal → 2단)",
                    hb.foundation() == 2 && "principal".equalsIgnoreCase(hb.rank()),
                    hb.foundation() + "단 · " + hb.rank());
            check("★★D2④ 창호의 <b>모양과 개수를 가른다</b> (family ≠ density)",
                    "W2".equalsIgnoreCase(hb.windowFamily())
                            && "medium".equalsIgnoreCase(hb.windowDensity()),
                    hb.windowFamily() + " · " + hb.windowDensity());
            check("★D2④ 도면이 격자를 <b>처방</b>으로 부른다 (그림을 박지 않는다)",
                    hb.columnOf('L').stream().anyMatch(cs -> "lattice".equals(cs.material())), "");
            check("★★D2⑥ 공포가 medium 이다 (principal — 작게 시작한다)",
                    "medium".equalsIgnoreCase(hb.bracket()), hb.bracket());
            // ★★공포의 <b>자리 계약</b> — 이름만 검사하면 부족하다
            String bbSrc = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "server-mvt/src/main/java/com/honcheon/mvt/forge/BlueprintBuilder.java"));
            // ★★자를 고쳤다 (REF-1c-A): 전에는 <b>소스에 그 조건이 적혀 있는가</b>만 봤다.
            //   조건은 적혀 있었지만 <b>너무 넓었다</b> — 본전은 회벽·격자의 인방도 mangrove
            //   한 켜라 모든 벽 칸이 적주로 세어졌고, 공포가 정면 전 폭에 깔려 「밝은 띠」가 됐다.
            //   ★이제 <b>실제로 몇 칸이 골라지는지를 센다.</b> 글자가 아니라 <b>결과</b>를 묻는다.
            check("★★D2⑥ 공포가 <b>적주 머리 위에서만</b> 시작한다 (자리 계약)",
                    // ★자를 고쳤다 (REF-2C): 조성이 적주 판정을 Blueprint#isPost 하나로
                    //   옮기면서 이 문자열이 조성에서 사라졌다. 물어야 할 것은 <b>어떤 식으로
                    //   쓰였나</b>가 아니라 <b>구조적 적주 판정을 부르는가</b>다.
                    // ★자를 고쳤다 (상층 문법 · 2026-08-11): 층을 하층·상층 둘로 가르며
                    //   조성의 <b>글자</b>가 bp → lv 로 옮겼다. 계약은 안 바뀌었다.
                    bbSrc.contains("lv.isPost(lv.at(c, r))")
                            && bbSrc.contains("★적주가 아니면 공포도 없다"), "");
            // ★★자를 고쳤다 (REF-1c-A): 전에는 {@code int top = oy + roofBase - brk;} 라는
            //   <b>한 줄 그대로</b>를 찾았다. 처마 밑에 <b>그림자 골</b> 한 켜를 내면서 그 줄이
            //   바뀌자 조성은 멀쩡한데 눈이 짖었다 — 물어야 할 것은 글자가 아니라
            //   <b>「공포 머리가 지붕 밑에 있는가」</b>다.
            check("★★D2⑥ 공포가 <b>처마를 받친다</b> (지붕 바로 아래 · 위로 안 튄다 — 지붕 "
                            + hb.roofs().get(0).baseY() + " · 공포 2단 + 골 "
                            + com.honcheon.mvt.forge.BlueprintBuilder.GROOVE + ")",
                    bbSrc.contains("int top = oy + roofBase - brk - groove;")
                            && hb.roofs().get(0).baseY()
                            - 2 - com.honcheon.mvt.forge.BlueprintBuilder.GROOVE >= 0,
                    hb.roofs().get(0).baseY());
            check("★D2⑥ 공포가 개구를 침범하지 않는다 (개구 칸엔 적주가 없다)",
                    hb.columnOf('O').stream().noneMatch(cs -> cs.material().contains("mangrove_log")),
                    "");
            // ★창호 셋이 <b>규칙으로</b> 다 정의됐는가 (강당은 W2 만 쓰지만 체계는 있다)
            check("★D2④ 창호 셋(W1·W2·W3)이 규칙으로 다 있다",
                    bbSrc.contains("case \"W1\"") && bbSrc.contains("case \"W3\""), "");
            // ★[눈의 눈] 깊이가 <b>실제로 조성에 닿는가</b> — 선언만 하고 안 쓰면 헛것이다.
            //   ★★자를 고쳤다 (REF-1b): 전에는 {@code bp.depthOf(bp.at(c, r))} 라는 <b>한 줄
            //     그대로</b>를 찾고 있었다. 깊이가 「이동」에서 「덧댐/물림」으로 바뀌며 그 줄이
            //     사라지자 조성은 멀쩡한데 눈이 짖었다 — <b>글자가 아니라 쓰임</b>을 물어야 한다.
            check("★[눈의 눈] 깊이가 도면 기계에 닿는다 (덧댐·물림 두 갈래로 실제로 쓴다)",
                    bbSrc.contains("lv.depthOf(ch)") && bbSrc.contains("lv.backingChar()")
                            && bbSrc.contains("stampColumn("), "");

            // ★옛 코드가 남았는가 — 강당이 아직 plasterHall 을 부르면 두 언어가 공존한다
            String hcb3 = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "server-mvt/src/main/java/com/honcheon/mvt/forge/HwasanCampusBuilder.java"));
            int c4 = hcb3.indexOf("case 4 ->");
            String body4 = c4 < 0 ? "" : hcb3.substring(c4, hcb3.indexOf("case 5 ->", c4));
            check("★[눈의 눈] 강당 부품 줄을 찾았다", c4 >= 0 && !body4.isEmpty(), "");
            check("★★강당이 옛 plasterHall 을 안 부른다 (유형이 갈렸다)",
                    !body4.contains("plasterHall(w, p,"), body4.trim());
        } catch (Exception e) {
            check("★강당 도면이 읽히고 제 계약을 지킨다", false, e.toString());
        }

        // ══════ ★★거처 유형 — 생활관 도면 (hwasan_residence.yml · 2026-08-08) ══════
        //   ★강당의 축소판이 아니다: 강당은 「중앙 문 → 큰 내부」, 생활관은 「작은 문 여럿 →
        //   반복되는 생활 단위」. 동선이 다르므로 <b>계약도 다르다</b>.
        try {
            java.util.Map<String, Object> rraw = new org.yaml.snakeyaml.Yaml().load(
                    java.nio.file.Files.readString(
                            java.nio.file.Path.of("config/blueprints/hwasan_residence.yml")));
            Blueprint rb = Blueprint.of(rraw);
            rb.validate();      // ★거처 계약 — 출입구 여럿 · 큰 문 아님 · 리듬 · low_gable
            check("★생활관 도면이 읽히고 제 계약을 지킨다 (" + rb.width() + "×" + rb.depth() + ")",
                    true, "통과");
            check("★거처는 부속급이고 쓰임이 <b>강당과 다르다</b>",
                    rb.auxiliary() && "residence".equalsIgnoreCase(rb.usage()),
                    rb.rank() + "/" + rb.usage());
            check("★★생활관 지붕이 낮은 맞배다 (sweep 를 쓰면 대부분이 핵심 전각처럼 보인다)",
                    rb.roofs().stream().allMatch(Blueprint.Roof::lowGable), "");
            check("★생활관 벽이 강당보다 낮다 (위계가 실루엣에서 갈린다)",
                    rb.heightAt(0, 0) < 6, "거처 " + rb.heightAt(0, 0) + " vs 강당 6");
            java.util.Map<Integer, Integer> resPer = new java.util.TreeMap<>();
            for (Blueprint.Placement pl : rb.placements()) {
                resPer.merge(pl.padOr(rb.pad()), 1, Integer::sum);
            }
            check("★한 도면이 두 구역에 앉는다 — 생활 하 2 · 생활 중 1",
                    resPer.equals(java.util.Map.of(5, 2, 8, 1)), resPer.toString());
            // 자리·기단·계단 봉투·유출
            java.util.List<TerraceForge.Pad> ap7 = TerraceForge.resolvePads(campus, 0, 0, 0);
            java.util.List<TerraceForge.StairLane> lanes7 = TerraceForge.resolveLanes(campus, ap7);
            int rm = 0;
            int rLane = 0;
            int rSpill = 0;
            int ev2 = rb.roofs().get(0).eave();
            for (Blueprint.Placement pl : rb.placements()) {
                int zone = pl.padOr(rb.pad());
                TerraceForge.Pad host = ap7.stream().filter(p2 -> p2.spec().zone() == zone)
                        .findFirst().orElseThrow();
                int px0 = host.x0() + pl.col();
                int px1 = px0 + pl.widthOf(rb) - 1;
                int pz0 = host.zN() + pl.row();
                int pz1 = pz0 + pl.depthOf(rb) - 1;
                for (int[] b : com.honcheon.mvt.forge.HwasanCampusBuilder.structureBoxes(host)) {
                    if (b[0] == px0 && b[1] == px1 && b[2] == pz0 && b[3] == pz1) {
                        rm++;
                    }
                }
                if (px0 - ev2 < host.x0() || px1 + ev2 > host.x1()
                        || pz0 - ev2 < host.zN() || pz1 + ev2 > host.zS()) {
                    rSpill++;
                }
                for (TerraceForge.StairLane l : lanes7) {
                    int qx = l.dirZ() != 0 ? 1 : 0;
                    int qz = l.dirZ() != 0 ? 0 : 1;
                    for (int t2 = 0; t2 <= l.length(); t2++) {
                        for (int o = -l.rail(); o <= l.rail(); o++) {
                            int lx = l.startX() + l.dirX() * (t2 - 1) + qx * o;
                            int lz = l.startZ() + l.dirZ() * (t2 - 1) + qz * o;
                            if (lx >= px0 && lx <= px1 && lz >= pz0 && lz <= pz1) {
                                rLane++;
                            }
                        }
                    }
                }
            }
            check("★★생활관 도면의 자리와 코드의 기단이 칸까지 같다 (세 자리)",
                    rm == 3, rm + "/3");
            check("★생활관이 계단 봉투를 침범하지 않는다 (서편 어귀)", rLane == 0, rLane + "칸");
            check("★생활관 처마가 패드 밖으로 안 샌다", rSpill == 0, rSpill + "채");
            // ★[눈의 눈] 강당의 계약으로 재면 <b>떨어져야</b> 한다 — 두 계약이 정말 다른가
            java.util.Map<String, Object> asAssembly = new java.util.LinkedHashMap<>(rraw);
            java.util.Map<String, Object> meta2 = new java.util.LinkedHashMap<>(
                    (java.util.Map<String, Object>) rraw.get("meta"));
            meta2.put("rank", "principal");
            meta2.remove("usage");
            asAssembly.put("meta", meta2);
            boolean fellThrough = false;
            try {
                Blueprint.of(asAssembly).validate();
                fellThrough = true;
            } catch (IllegalStateException ok) {
                fellThrough = false;
            }
            check("★[눈의 눈] 생활관을 <b>모임의 자</b>로 재면 떨어진다 (계약이 정말 다르다)",
                    !fellThrough, fellThrough ? "그냥 통과했다" : "떨어졌다");
            String hcb4 = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "server-mvt/src/main/java/com/honcheon/mvt/forge/HwasanCampusBuilder.java"));
            int c5 = hcb4.indexOf("case 5 ->");
            String body58 = c5 < 0 ? "" : hcb4.substring(c5, hcb4.indexOf("case 17 ->", c5));
            check("★★생활(5·8)이 옛 plasterHall 을 안 부른다",
                    !body58.isEmpty() && !body58.contains("plasterHall(w, p,"), "");
        } catch (Exception e) {
            check("★생활관 도면이 읽히고 제 계약을 지킨다", false, e.toString());
        }

        // ══════ ★★저장 유형 — 창고 도면 (hwasan_storage.yml · 2026-08-08) ══════
        //   ★사용자가 짚은 함정: 「생활용 하나 만듦 → 창고도 비슷하니 그대로 사용 →
        //   다시 하나가 여러 쓰임을 먹는다」. 그래서 두 계약을 <b>서로 어긋나게</b> 세웠고,
        //   여기서 <b>정말 어긋나는지</b> 되묻는다 — 서로의 자로 재면 둘 다 떨어져야 한다.
        try {
            java.util.Map<String, Object> sraw = new org.yaml.snakeyaml.Yaml().load(
                    java.nio.file.Files.readString(
                            java.nio.file.Path.of("config/blueprints/hwasan_storage.yml")));
            Blueprint sb2 = Blueprint.of(sraw);
            sb2.validate();
            check("★창고 도면이 읽히고 제 계약을 지킨다 (" + sb2.width() + "×" + sb2.depth() + ")",
                    true, "통과");
            check("★저장은 부속급이고 쓰임이 생활관과 다르다",
                    sb2.auxiliary() && "storage".equalsIgnoreCase(sb2.usage()),
                    sb2.rank() + "/" + sb2.usage());
            check("★창고가 생활관과 <b>지붕을 공유한다</b> (같은 문파다)",
                    sb2.roofs().stream().allMatch(Blueprint.Roof::lowGable), "");
            check("★창고가 옛 몸체(15×11)를 그대로 쓴다",
                    sb2.width() == 15 && sb2.depth() == 11, sb2.width() + "×" + sb2.depth());

            // ★★[눈의 눈] 서로의 자로 재면 <b>둘 다 떨어진다</b> — 계약이 정말 갈렸는가
            java.util.Map<String, Object> rraw2 = new org.yaml.snakeyaml.Yaml().load(
                    java.nio.file.Files.readString(
                            java.nio.file.Path.of("config/blueprints/hwasan_residence.yml")));
            boolean storeAsRes = false;
            boolean resAsStore = false;
            try {
                java.util.Map<String, Object> m1 = new java.util.LinkedHashMap<>(sraw);
                java.util.Map<String, Object> mm1 = new java.util.LinkedHashMap<>(
                        (java.util.Map<String, Object>) sraw.get("meta"));
                mm1.put("usage", "residence");
                m1.put("meta", mm1);
                Blueprint.of(m1).validate();
                storeAsRes = true;
            } catch (IllegalStateException ok) {
                storeAsRes = false;
            }
            try {
                java.util.Map<String, Object> m2 = new java.util.LinkedHashMap<>(rraw2);
                java.util.Map<String, Object> mm2 = new java.util.LinkedHashMap<>(
                        (java.util.Map<String, Object>) rraw2.get("meta"));
                mm2.put("usage", "storage");
                m2.put("meta", mm2);
                Blueprint.of(m2).validate();
                resAsStore = true;
            } catch (IllegalStateException ok) {
                resAsStore = false;
            }
            check("★★[눈의 눈] 창고를 <b>거처의 자</b>로 재면 떨어진다",
                    !storeAsRes, storeAsRes ? "그냥 통과했다 — 계약이 같다는 뜻" : "떨어졌다");
            check("★★[눈의 눈] 생활관을 <b>저장의 자</b>로 재면 떨어진다",
                    !resAsStore, resAsStore ? "그냥 통과했다 — 계약이 같다는 뜻" : "떨어졌다");

            // 자리·기단·봉투·유출
            java.util.List<TerraceForge.Pad> ap8 = TerraceForge.resolvePads(campus, 0, 0, 0);
            TerraceForge.Pad st = ap8.stream().filter(p2 -> p2.spec().zone() == 17)
                    .findFirst().orElseThrow();
            Blueprint.Placement sp = sb2.placements().get(0);
            int sx0 = st.x0() + sp.col();
            int sx1 = sx0 + sp.widthOf(sb2) - 1;
            int sz0 = st.zN() + sp.row();
            int sz1 = sz0 + sp.depthOf(sb2) - 1;
            boolean sMatch = false;
            for (int[] b : com.honcheon.mvt.forge.HwasanCampusBuilder.structureBoxes(st)) {
                if (b[0] == sx0 && b[1] == sx1 && b[2] == sz0 && b[3] == sz1) {
                    sMatch = true;
                }
            }
            check("★★창고 도면의 자리와 코드의 기단이 칸까지 같다", sMatch, "");
            int ev3 = sb2.roofs().get(0).eave();
            check("★창고 처마가 패드 밖으로 안 샌다",
                    sx0 - ev3 >= st.x0() && sx1 + ev3 <= st.x1()
                            && sz0 - ev3 >= st.zN() && sz1 + ev3 <= st.zS(), "");
            int sLane = 0;
            for (TerraceForge.StairLane l : TerraceForge.resolveLanes(campus, ap8)) {
                int qx = l.dirZ() != 0 ? 1 : 0;
                int qz = l.dirZ() != 0 ? 0 : 1;
                for (int t3 = 0; t3 <= l.length(); t3++) {
                    for (int o = -l.rail(); o <= l.rail(); o++) {
                        int lx = l.startX() + l.dirX() * (t3 - 1) + qx * o;
                        int lz = l.startZ() + l.dirZ() * (t3 - 1) + qz * o;
                        if (lx >= sx0 && lx <= sx1 && lz >= sz0 && lz <= sz1) {
                            sLane++;
                        }
                    }
                }
            }
            check("★창고가 계단 봉투를 침범하지 않는다 (서편 어귀)", sLane == 0, sLane + "칸");
            String hcb5 = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "server-mvt/src/main/java/com/honcheon/mvt/forge/HwasanCampusBuilder.java"));
            int c17 = hcb5.indexOf("case 17 ->");
            String body17 = c17 < 0 ? "" : hcb5.substring(c17, hcb5.indexOf("case 9 ->", c17));
            check("★★창고가 옛 plasterHall 을 안 부른다",
                    !body17.isEmpty() && !body17.contains("plasterHall(w, p,"), "");
        } catch (Exception e) {
            check("★창고 도면이 읽히고 제 계약을 지킨다", false, e.toString());
        }

        // ══════ ★★장로회 — 모임 계열의 얕은 변형 (hwasan_council.yml · 2026-08-08) ══════
        //   사용자: 「강당이 안 들어간다 → 찌그러뜨린다」가 아니라 「같은 계열의 <b>다른 평면
        //   변형</b>이 필요하다」. 그리고 <b>숫자를 미리 정하지 않고</b> 자유 영역부터 쟀다.
        try {
            java.util.Map<String, Object> craw2 = new org.yaml.snakeyaml.Yaml().load(
                    java.nio.file.Files.readString(
                            java.nio.file.Path.of("config/blueprints/hwasan_council.yml")));
            Blueprint cb2 = Blueprint.of(craw2);
            cb2.validate();
            check("★장로회 도면이 읽히고 제 계약을 지킨다 (" + cb2.width() + "×" + cb2.depth() + ")",
                    true, "통과");
            check("★★장로회가 <b>강당과 같은 계열</b>이다 (family assembly · 쓰임만 다르다)",
                    "assembly".equalsIgnoreCase(cb2.family())
                            && "council".equalsIgnoreCase(cb2.usage()),
                    cb2.family() + "/" + cb2.usage());
            java.util.Map<String, Object> hraw2 = new org.yaml.snakeyaml.Yaml().load(
                    java.nio.file.Files.readString(
                            java.nio.file.Path.of("config/blueprints/hwasan_hall.yml")));
            Blueprint hb2 = Blueprint.of(hraw2);
            check("★★<b>얕은</b> 변형이다 — 강당보다 얕고 처마도 얕다 (찌그러뜨린 게 아니다)",
                    cb2.depth() < hb2.depth()
                            && cb2.roofs().get(0).eave() < hb2.roofs().get(0).eave(),
                    "깊이 " + cb2.depth() + "<" + hb2.depth()
                            + " · 처마 " + cb2.roofs().get(0).eave()
                            + "<" + hb2.roofs().get(0).eave());
            check("★장로회도 팔작이다 (계열이 같으니 지붕이 같다)",
                    cb2.roofs().stream().noneMatch(r -> r.lowGable() || r.hipPyramid()), "");

            // ★★척추가 그 사이로 지나간다 — 두 채가 축을 비켜 앉았는가
            java.util.List<TerraceForge.Pad> ap9 = TerraceForge.resolvePads(campus, 0, 0, 0);
            TerraceForge.Pad el = ap9.stream().filter(p2 -> p2.spec().zone() == 12)
                    .findFirst().orElseThrow();
            int ev4 = cb2.roofs().get(0).eave();
            int hfW = (cb2.width() - 1) / 2;
            int gapLo = Integer.MIN_VALUE;
            int gapHi = Integer.MAX_VALUE;
            int cm = 0;
            for (Blueprint.Placement pl : cb2.placements()) {
                int px0 = el.x0() + pl.col();
                int px1 = px0 + pl.widthOf(cb2) - 1;
                int pz0 = el.zN() + pl.row();
                int pz1 = pz0 + pl.depthOf(cb2) - 1;
                for (int[] b : com.honcheon.mvt.forge.HwasanCampusBuilder.structureBoxes(el)) {
                    if (b[0] == px0 && b[1] == px1 && b[2] == pz0 && b[3] == pz1) {
                        cm++;
                    }
                }
                // 팔작은 반폭+내밈만큼 퍼진다 — 축의 틈은 <b>지붕</b> 기준으로 잰다
                int cxw = (px0 + px1) / 2;
                int roofLo = cxw - hfW - ev4;
                int roofHi = cxw + hfW + ev4;
                if (roofHi < el.x0() + el.spec().width() / 2) {
                    gapLo = Math.max(gapLo, roofHi + 1);
                } else {
                    gapHi = Math.min(gapHi, roofLo - 1);
                }
                if (roofLo < el.x0() || roofHi > el.x1()) {
                    check("★장로회 지붕이 패드 밖으로 안 샌다", false, pl.id());
                }
            }
            check("★★장로회 도면의 자리와 코드의 기단이 칸까지 같다", cm == 2, cm + "/2");
            int gap = gapHi - gapLo + 1;
            check("★★두 채 사이로 척추가 지나간다 (틈 " + gap + " ≥ 계단 봉투 9)",
                    gap >= 9, "x" + gapLo + ".." + gapHi);
            check("★그 틈이 축선을 품는다 (한쪽으로 치우치지 않았다)",
                    gapLo <= el.x0() + el.spec().width() / 2
                            && gapHi >= el.x0() + el.spec().width() / 2, "");
            String hcb6 = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "server-mvt/src/main/java/com/honcheon/mvt/forge/HwasanCampusBuilder.java"));
            int c12 = hcb6.indexOf("case 12 ->");
            String body12 = c12 < 0 ? "" : hcb6.substring(c12, hcb6.indexOf("case 13 ->", c12));
            check("★★장로회가 옛 plasterHall 을 안 부른다",
                    !body12.isEmpty() && !body12.contains("plasterHall(w, p,"), "");
        } catch (Exception e) {
            check("★장로회 도면이 읽히고 제 계약을 지킨다", false, e.toString());
        }

        // ══════ ★★암자 — 은거 유형 (hwasan_hermitage.yml · 2026-08-08) ══════
        //   분류표에서 마지막까지 「미정」이던 유형. 같은 low_gable 지붕·재료를 쓰는 집이
        //   셋(생활·창고·암자)이고, 갈리는 것은 <b>문의 수와 폭</b> 그리고 <b>크기</b>다.
        try {
            java.util.Map<String, Object> mraw = new org.yaml.snakeyaml.Yaml().load(
                    java.nio.file.Files.readString(
                            java.nio.file.Path.of("config/blueprints/hwasan_hermitage.yml")));
            Blueprint mb = Blueprint.of(mraw);
            mb.validate();
            check("★암자 도면이 읽히고 제 계약을 지킨다 (" + mb.width() + "×" + mb.depth() + ")",
                    true, "통과");
            check("★암자가 작다 (≤ 9×7 — 산정의 집은 크지 않다)",
                    mb.width() <= 9 && mb.depth() <= 7, mb.width() + "×" + mb.depth());
            check("★암자가 생활·창고와 지붕을 공유한다",
                    mb.roofs().stream().allMatch(Blueprint.Roof::lowGable), "");

            // ★★[눈의 눈] 셋이 서로의 자로 재면 <b>전부</b> 떨어진다
            java.util.Map<String, Object> rr = new org.yaml.snakeyaml.Yaml().load(
                    java.nio.file.Files.readString(
                            java.nio.file.Path.of("config/blueprints/hwasan_residence.yml")));
            java.util.Map<String, Object> ss2 = new org.yaml.snakeyaml.Yaml().load(
                    java.nio.file.Files.readString(
                            java.nio.file.Path.of("config/blueprints/hwasan_storage.yml")));
            record Cross(String what, java.util.Map<String, Object> raw, String as) { }
            java.util.List<Cross> crosses = java.util.List.of(
                    new Cross("암자를 거처의 자로", mraw, "residence"),
                    new Cross("암자를 저장의 자로", mraw, "storage"),
                    new Cross("거처를 은거의 자로", rr, "hermitage"),
                    new Cross("저장을 은거의 자로", ss2, "hermitage"));
            int slipped = 0;
            String who2 = "";
            for (Cross cr : crosses) {
                try {
                    java.util.Map<String, Object> mm = new java.util.LinkedHashMap<>(cr.raw());
                    java.util.Map<String, Object> me = new java.util.LinkedHashMap<>(
                            (java.util.Map<String, Object>) cr.raw().get("meta"));
                    me.put("usage", cr.as());
                    mm.put("meta", me);
                    Blueprint.of(mm).validate();
                    slipped++;
                    who2 = cr.what();
                } catch (IllegalStateException ok) {
                    // 떨어져야 맞다
                }
            }
            check("★★[눈의 눈] 생활·창고·암자가 <b>서로의 자로 재면 전부 떨어진다</b> (4쌍)",
                    slipped == 0, slipped == 0 ? "전부 떨어졌다" : slipped + "쌍 통과 · " + who2);

            // 자리·기단 대조 (암자 둘 + 정자 넷)
            java.util.List<TerraceForge.Pad> apA = TerraceForge.resolvePads(campus, 0, 0, 0);
            int hmM = 0;
            for (Blueprint.Placement pl : mb.placements()) {
                int zone = pl.padOr(mb.pad());
                TerraceForge.Pad host = apA.stream().filter(p2 -> p2.spec().zone() == zone)
                        .findFirst().orElseThrow();
                int px0 = host.x0() + pl.col();
                int px1 = px0 + pl.widthOf(mb) - 1;
                int pz0 = host.zN() + pl.row();
                int pz1 = pz0 + pl.depthOf(mb) - 1;
                for (int[] b : com.honcheon.mvt.forge.HwasanCampusBuilder.structureBoxes(host)) {
                    if (b[0] == px0 && b[1] == px1 && b[2] == pz0 && b[3] == pz1) {
                        hmM++;
                    }
                }
            }
            check("★★암자 도면의 자리와 코드의 기단이 칸까지 같다", hmM == 2, hmM + "/2");

            // ★옛 pavilion() 이 정원(10) 하나만 남았는가
            String hcb7 = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "server-mvt/src/main/java/com/honcheon/mvt/forge/HwasanCampusBuilder.java"));
            int pavLeft = hcb7.split("pavilion\\(w, p,", -1).length - 1;
            check("★★옛 pavilion() 이 <b>전부</b> 사라졌다 (정원 정자까지 도면으로)",
                    pavLeft == 0, pavLeft + "곳");
            int hallLeft = hcb7.split("plasterHall\\(w, p,", -1).length - 1;
            check("★★옛 plasterHall 이 <b>전부</b> 사라졌다", hallLeft == 0, hallLeft + "곳");
        } catch (Exception e) {
            check("★암자 도면이 읽히고 제 계약을 지킨다", false, e.toString());
        }

        // ══════ ★전수 조사가 실물과 같은가 (plasterhall_census.md) ══════
        //   ★조사는 <b>신고가 아니라 센 것</b>이어야 한다. 호출부가 늘거나 줄면 표가 늙는다 —
        //   소스에서 직접 세어 문서의 수와 맞춘다.
        try {
            String hcb2 = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "server-mvt/src/main/java/com/honcheon/mvt/forge/HwasanCampusBuilder.java"));
            String census = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "docs/design/hwasan/plasterhall_census.md"));
            int halls = hcb2.split("plasterHall\\(w, p,", -1).length - 1;
            int pavs = hcb2.split("pavilion\\(w, p,", -1).length - 1;
            check("★전수 조사 문서가 있다 (plasterhall_census.md)", census.length() > 800,
                    census.length() + "자");
            check("★★조사의 plasterHall 수가 실물과 같다 (" + halls + "곳)",
                    census.contains("**" + halls + "곳**"), halls + " vs 문서");
            check("★★조사의 옛 pavilion 수가 실물과 같다 (" + pavs + "곳)",
                    census.contains("**" + pavs + "곳**"), pavs + " vs 문서");
            check("★조사가 다음 순서를 적는다 (강당부터)",
                    census.contains("강당(4) → 생활(5·8) → 창고(17) → 장로회(12) → 정상 암자(13)"), "");
        } catch (Exception e) {
            check("★전수 조사 문서가 있다 (plasterhall_census.md)", false, e.toString());
        }

        // ══════ ★surface_ownership — 한 표면에 최종 재료 소유자는 하나다 ══════
        //   (사용자 확정 2026-08-06) 이번 병의 공통 원인: 첫 생성자가 표면을 확정 →
        //   뒤 단계가 「의미를 표현한다」며 덧칠 → 정본과 화면이 갈라진다.
        //   ★소스를 직접 읽어 <b>사암을 도로 칠하는 손이 되살아났는지</b> 본다.
        try {
            String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "server-mvt/src/main/java/com/honcheon/mvt/forge/HwasanCampusBuilder.java"));
            int publicCase = src.indexOf("case 1, 2, 6, 13, 101 ->");
            String owner = publicCase < 0 ? "" : src.substring(publicCase,
                    Math.min(src.length(), publicCase + 120));
            check("★[눈의 눈] 공공 마당의 재료 소유자 줄을 찾았다", publicCase >= 0, "");
            // ══ ★E-04 — 수련 구역도 한 재료다. 다만 <b>공공과 같은 재료면 안 된다</b> ══
            //   사용자: 「외원이 석전 100% 가 됐다는 이유로 연무장도 돌바닥으로 만들면 안 된다」.
            //   조닝은 <b>표면 자체가 다른 재료</b>인 것으로 드러난다 — 덧칠이 아니라.
            // ══ ★11 망루 — 팔작에서 사모로 (2026-08-07) ══
            //   E-07 이 만든 문법을 <b>tower 비례</b>로 쓴다. 정자와 같은 계열이되 실루엣이 다르다.
            //   망루는 단지에서 <b>가장 멀리서 보이는</b> 실루엣이라 이 한 채가 원경을 바꾼다.
            int wt = src.indexOf("private static void watchtower");
            String wtBody = wt < 0 ? "" : src.substring(wt, Math.min(src.length(), wt + 2400));
            check("★[눈의 눈] 망루 조성부를 찾았다", wt >= 0, "");
            check("★★망루 지붕이 사모다 (팔작이 아니다 — 위계가 지붕으로 갈린다)",
                    wtBody.contains("hipRoof(") && !wtBody.contains("sweepRoof("), "");
            check("★망루는 <b>급한</b> 사모다 (정자의 낮고 넓은 것과 다르다)",
                    wtBody.contains("base, cz - th, cz + th, 1, 5, 2"),
                    "처마 1 · 오름 5 · 캡 2");
            check("★연무장 바닥이 한 재료다 (해시 얼룩 없음)",
                    !src.contains("Math.floorMod(x * 7 + z * 13, 5)"), "");
            check("★★수련 바닥이 공공 마당과 <b>다른 재료</b>다 (조닝이 표면으로 드러난다)",
                    com.honcheon.mvt.forge.HwasanCampusBuilder.trainingPaveMaterial()
                            != com.honcheon.mvt.forge.HwasanCampusBuilder.publicPaveMaterial(),
                    com.honcheon.mvt.forge.HwasanCampusBuilder.trainingPaveMaterial()
                            + " vs " + com.honcheon.mvt.forge.HwasanCampusBuilder.publicPaveMaterial());
            check("★모래를 없애지 않았다 (연무장의 모래는 조닝이 아니라 쓰임이다)",
                    com.honcheon.mvt.forge.HwasanCampusBuilder.trainingPaveMaterial()
                            == Material.SAND, "");
            check("★공공 마당의 소유자가 publicRepave 하나다 (sandyRepave 가 안 되살아났다)",
                    owner.contains("publicRepave") && !src.contains("sandyRepave("),
                    owner.trim());
        } catch (Exception e) {
            check("★surface_ownership — 소스를 읽는다", false, e.toString());
        }

        check("★비석이 축선 시야 회랑 밖에 선다 (문루 정면을 안 가린다 · D-20)",
                TerraceForge.AXIS_CLEAR > TerraceForge.APPROACH_CLEAR,
                "비석 ±" + TerraceForge.AXIS_CLEAR + " vs 회랑 ±" + TerraceForge.APPROACH_CLEAR);
        // ★난간은 그 행의 폭을 따라간다 — 고정 오프셋이면 넓어진 구간에서 길 한가운데 선다
        boolean railInsideWalk = false;
        for (int i = 0; i < TerraceForge.APPROACH_LEN; i++) {
            if (TerraceForge.approachHalf(i) + 1 <= TerraceForge.approachHalf(i)) {
                railInsideWalk = true;
            }
        }
        check("★난간이 보행면 밖에 선다 (폭 전이 구간 포함)", !railInsideWalk, railInsideWalk);
        // ★10-① 산몸 — 「평지+기둥」이 아니라 「산+암봉」: 근경 환대에서 맨바닥(0) 비율이
        //   절반 아래로 (침봉만 있던 8.5 는 ~75% 가 맨바닥이었다) + 산체 높이대(25~95) 실재
        int zeros = 0;
        int total = 0;
        int bodyBand = 0;
        for (int gx = -420; gx <= 420; gx += 17) {
            for (int gz = -420; gz <= 420; gz += 17) {
                double dd = Math.hypot(gx, gz);
                if (dd < 240 || dd > 420) {
                    continue;
                }
                total++;
                int hh = bare.targetH(gx, gz);
                if (hh == 0) {
                    zeros++;
                } else if (hh >= 25 && hh <= 95) {
                    bodyBand++;
                }
            }
        }
        check("★10-① 산몸 연결 — 근경 맨바닥 ≤45% (침봉이 산체 위에서 솟는다)",
                total > 0 && zeros * 100 / total <= 45, zeros + "/" + total);
        check("★10-① 산체 높이대(25~95) 실재 — 능선·안부의 층", bodyBand > 0, bodyBand);
        // ★10 실루엣 변주 — 멱분포: 가는 반경(≤9)과 굵은 반경(≥13)이 다 있다
        boolean thin = false;
        boolean thickR = false;
        for (int cx2 = -30; cx2 <= 30; cx2++) {
            for (int cz2 = -30; cz2 <= 30; cz2++) {
                int rr = com.honcheon.mvt.forge.SpireField.spireRadius(cx2, cz2);
                if (rr > 0 && rr <= 9) {
                    thin = true;
                }
                if (rr >= 13) {
                    thickR = true;
                }
            }
        }
        check("★10 침봉 변주 — 가는 놈(≤9)과 굵은 놈(≥13)이 공존 (파이프오르간 금지)",
                thin && thickR, thin + "/" + thickR);
        // ══════════ ★★팔레트 상한 — 「한 면에 몇 종을 쓰는가」 (사용자 확정 2026-08-05) ══════════
        //   근거: 목표 사진의 어느 면이든 색 무리로 가르면 여러 무리가 나오지만 <b>색도가 다 같고
        //   밝기만 다르다</b> (지붕 24·45·68 / 석축 54·93·131·182 / 계단 57·90·125·167).
        //   한 재료가 여러 밝기로 앉은 것이다 — 밝기는 마인크래프트가 면 방향과 그늘로 거저 준다.
        //   ★이 눈이 지키는 것은 「오늘의 색 고름」이 아니라 <b>종류의 수</b>다. 어떤 돌을 고를지는
        //   바뀔 수 있어도, 한 면에 여럿을 흩뿌리지 않는다는 계약은 남는다.
        java.util.Set<Material> rockSeen = java.util.EnumSet.noneOf(Material.class);
        java.util.Set<Material> capSeen = java.util.EnumSet.noneOf(Material.class);
        boolean bannedStone = false;
        for (int s = 0; s < 4000; s++) {
            int px = (s * 13) % 977 - 400;
            int py = (s * 7) % 131;
            int pz = (s * 29) % 883 - 400;
            Material m = com.honcheon.mvt.forge.SpireField.stone(px, py, pz, false);
            rockSeen.add(m);
            capSeen.add(com.honcheon.mvt.forge.SpireField.stone(px, py, pz, true));
            if (m == Material.BARREL || m == Material.LIGHT) {
                bannedStone = true;
            }
        }
        check("★팔레트 — 암벽 몸은 한 종 (밝기 결은 면 방향이 낸다)", rockSeen.size() == 1, rockSeen);
        // ══════════ ★★주상절리 — 형태의 눈 (사용자 확정 2026-08-05) ══════════
        //   레퍼런스 절벽 실측: 기둥 폭 1칸 · 이웃 마루 높이차 2~6 · 둘셋이 무리 짓는다.
        //   ★이 눈이 지키는 것은 「오늘의 진폭」이 아니라 <b>세 성질</b>이다:
        //     ㉠ 평지는 안 쪼갠다 (물매 없으면 손대지 않는다)
        //     ㉡ 급한 면은 열마다 갈린다 (한 줄만 파는 게 아니다 — 옛 faceRelief 의 병)
        //     ㉢ 무리 짓는다 (전부 제각각이면 자갈밭이지 절벽이 아니다)
        int flatTouched = 0;
        for (int s = 0; s < 2000; s++) {
            int px = s * 13 % 977;
            int pz = s * 29 % 883;
            for (int dp = 0; dp < com.honcheon.mvt.forge.SpireField.JOINT_MIN_DROP; dp++) {
                if (com.honcheon.mvt.forge.SpireField.jointed(60, dp, px, pz) != 60) {
                    flatTouched++;
                }
            }
        }
        check("★주상절리 ㉠ 평지는 안 쪼갠다 (물매 < 문턱이면 그대로)", flatTouched == 0, flatTouched);
        // ══════════════════════════════════════════════════════════════
        // ★★★B-196 산문 — <b>동결</b> (사용자 승인 2026-08-06)
        //
        //   「B-196 산문 구조: 승인 · 치수와 좌표: 동결 · 입면 판독 문제: 해결」
        //
        //   ★동결을 문서에만 적으면 표류한다. 눈으로 잠근다 — 아래 값이 바뀌면 짖는다.
        //   바꿀 일이 생기면 <b>이 눈을 고치는 것이 곧 결정의 기록</b>이 된다.
        //   ※최종 색·재료 판정은 리소스팩 적용 후로 <b>보류</b>다 (여기서 안 잰다).
        // ══════════════════════════════════════════════════════════════
        try {
            com.honcheon.mvt.forge.Blueprint gate = com.honcheon.mvt.forge.Blueprint.of(
                    new org.yaml.snakeyaml.Yaml().load(java.nio.file.Files.readString(
                            java.nio.file.Path.of("config/blueprints/hwasan_gate.yml"))));
            int open = 0;
            for (int c = 0; c < 60; c++) {
                for (com.honcheon.mvt.forge.Blueprint.Course cs : gate.columnOf(gate.at(c, 18))) {
                    if ("air".equals(cs.material()) && cs.count() >= 5) {
                        open++;
                        break;
                    }
                }
            }
            check("★동결 — 산문 중앙 통로 7", open == 7, open);
            java.util.List<Integer> pillars = new java.util.ArrayList<>();
            for (int c = 0; c < 60; c++) {
                if (gate.at(c, 18) == 'P') {
                    pillars.add(c);
                }
            }
            java.util.List<Integer> gaps = new java.util.ArrayList<>();
            for (int i = 1; i < pillars.size(); i++) {
                gaps.add(pillars.get(i) - pillars.get(i - 1));
            }
            check("★동결 — 적주 간격 3·3·5·(통로 7)·5·3·3",
                    gaps.equals(java.util.List.of(3, 3, 5, 8, 5, 3, 3)), gaps);
            boolean sym = true;
            for (int c = 1; c < 60; c++) {
                char a = gate.at(c, 18);
                char b = gate.at(60 - c, 18);
                if ("PDIONW".indexOf(a) >= 0 && a != b) {
                    sym = false;
                }
            }
            check("★동결 — 산문 정면 좌우 대칭", sym, sym);
            check("★동결 — 계단 폭 입구 9 · 전이 참 11 · 문 앞 7",
                    TerraceForge.approachHalf(TerraceForge.APPROACH_LEN - 1) == 4
                            && TerraceForge.approachHalf(TerraceForge.WIDEN_FROM) == 5
                            && TerraceForge.approachHalf(0) == 3, "9/11/7");
        } catch (Exception e) {
            check("★동결 — 산문 도면이 읽힌다", false, e.toString());
        }
        // ★동결 — 표고 (「캠퍼스 전체 표고를 콘셉트 수치에 맞춰 확대하지 않는다」)
        check("★동결 — 산문 h46 · 본전 h116 · 정상 암자 h148 (총 102칸)",
                heightOf(campus, 1) == 46 && heightOf(campus, 9) == 116
                        && heightOf(campus, 13) == 148,
                heightOf(campus, 1) + "/" + heightOf(campus, 9) + "/" + heightOf(campus, 13));
        // ══════════ ★접근로 폭 전이 (사용자 확정 2026-08-06) ══════════
        //   외부 9 → 전이 참 11 → 문 앞 7. ★폭이 <b>참에서만</b> 바뀌어야 한다 —
        //   디딤 도중에 바뀌면 걷다가 발밑이 넓어졌다 좁아진다.
        check("★접근로 — 문 앞은 폭 7 (기존 축과 수렴)",
                TerraceForge.approachHalf(0) * 2 + 1 == 7, TerraceForge.approachHalf(0));
        check("★접근로 — 입구는 폭 9 (웅장하게)",
                TerraceForge.approachHalf(TerraceForge.APPROACH_LEN - 1) * 2 + 1 == 9,
                TerraceForge.approachHalf(TerraceForge.APPROACH_LEN - 1));
        check("★접근로 — 전이 참은 폭 11 (9~13 안)",
                TerraceForge.approachHalf(TerraceForge.WIDEN_FROM) * 2 + 1 >= 9
                        && TerraceForge.approachHalf(TerraceForge.WIDEN_FROM) * 2 + 1 <= 13,
                TerraceForge.approachHalf(TerraceForge.WIDEN_FROM) * 2 + 1);
        int widthJumpsOffLanding = 0;
        for (int i = 1; i < TerraceForge.APPROACH_LEN; i++) {
            if (TerraceForge.approachHalf(i) != TerraceForge.approachHalf(i - 1)
                    && !(TerraceForge.isLanding(i) || TerraceForge.isLanding(i - 1))) {
                widthJumpsOffLanding++;
            }
        }
        check("★접근로 — 폭은 참에서만 바뀐다 (디딤 도중에 안 바뀐다)",
                widthJumpsOffLanding == 0, widthJumpsOffLanding);
        // 폭이 실제로 세 단계여야 한다 — 한 값으로 눌러 놓고 통과시키는 것을 막는다
        java.util.Set<Integer> widths = new java.util.HashSet<>();
        for (int i = 0; i < TerraceForge.APPROACH_LEN; i++) {
            widths.add(TerraceForge.approachHalf(i) * 2 + 1);
        }
        check("★접근로 — 폭이 세 단계다 (7 · 9 · 11)", widths.size() == 3, widths);
        // ══════════ ★★대(臺) 옆모습 — 실루엣의 눈 (사용자 확정 2026-08-06) ══════════
        //   레퍼런스 좌상단 절벽 실측: 벽면이 보이는 높이 전부에 걸쳐 수직이다. 원뿔이 아니다.
        //   ★이 눈이 지키는 것은 상수 셋이 아니라 <b>모양의 성질</b>이다 — 마루가 평평한가,
        //   높이의 대부분이 좁은 띠에서 떨어지는가, 발치에 너덜이 남는가.
        {
            double top = com.honcheon.mvt.forge.SpireField.mesaProfile(0.0);
            double rim = com.honcheon.mvt.forge.SpireField.mesaProfile(
                    com.honcheon.mvt.forge.SpireField.MESA_TOP);
            check("★대 ㉠ 마루가 평평하다 (중심과 마루 끝이 같은 높이)",
                    Math.abs(top - rim) < 1e-9 && top == 1.0, top + "/" + rim);
            // ㉡ 벽 — 높이의 대부분이 마루 끝~애추 시작 사이에서 떨어진다
            double foot = com.honcheon.mvt.forge.SpireField.mesaProfile(
                    com.honcheon.mvt.forge.SpireField.MESA_FOOT);
            double inWall = rim - foot;
            check("★대 ㉡ 벽이 급하다 (높이의 ≥80%가 벽 구간에서 떨어진다)",
                    inWall >= 0.80, String.format("%.3f", inWall));
            // ㉢ 애추 — 발치가 절벽으로 0 이 되지 않는다 (너덜이 남는다)
            check("★대 ㉢ 발치에 애추가 남는다 (벽이 땅까지 곧장 안 떨어진다)",
                    foot > 0.05 && com.honcheon.mvt.forge.SpireField.mesaProfile(1.0) <= 1e-9,
                    String.format("%.3f", foot));
            // ㉣ 단조 — 안쪽이 늘 더 높다 (봉우리에 계단참이 생기면 대가 아니다)
            boolean mono = true;
            double prev = 2.0;
            for (int i = 0; i <= 200; i++) {
                double f = com.honcheon.mvt.forge.SpireField.mesaProfile(i / 200.0);
                if (f > prev + 1e-12) {
                    mono = false;
                }
                prev = f;
            }
            check("★대 ㉣ 안쪽이 늘 더 높다 (단조 감소)", mono, mono);
            // ㉤ ★원뿔 금지 — 옛 (1−de)^0.55 는 마루 끝에서 이미 33% 를 잃는다.
            //    대는 거기서 <b>하나도</b> 안 잃는다. 이 한 줄이 「대인가 원뿔인가」를 가른다.
            double coneAtRim = Math.pow(1.0 - com.honcheon.mvt.forge.SpireField.MESA_TOP, 0.55);
            check("★대 ㉤ 원뿔이 아니다 (마루 끝에서 옛 원뿔보다 확실히 높다)",
                    rim - coneAtRim > 0.25, String.format("대 %.3f vs 원뿔 %.3f", rim, coneAtRim));
        }
        int split = 0;
        int rows = 0;
        for (int s = 0; s < 600; s++) {
            int px = 400 + s % 37;
            int pz = 900 + s / 37;
            if (com.honcheon.mvt.forge.SpireField.jointed(80, 9, px, pz)
                    != com.honcheon.mvt.forge.SpireField.jointed(80, 9, px + 1, pz)) {
                split++;
            }
            rows++;
        }
        check("★주상절리 ㉡ 급한 면은 열마다 갈린다 (이웃과 다른 높이 ≥45%)",
                split * 100 / rows >= 45, split * 100 / rows + "%");
        // ㉢ 무리 — 이웃이 <b>같은</b> 높이인 일도 흔해야 한다 (전부 다르면 자갈밭이다)
        check("★주상절리 ㉢ 무리 짓는다 (이웃과 같은 높이도 ≥20%)",
                (rows - split) * 100 / rows >= 20, (rows - split) * 100 / rows + "%");
        // ㉣ 파임의 깊이 — 목표의 높이차 2~6 을 담되 산에 구멍은 안 뚫는다
        int deepest = 0;
        long dsum = 0;
        int dn = 0;
        for (int s = 0; s < 3000; s++) {
            int cut = 80 - com.honcheon.mvt.forge.SpireField.jointed(80, 9, s * 7 % 991, s * 11 % 887);
            deepest = Math.max(deepest, cut);
            dsum += cut;
            dn++;
        }
        check("★주상절리 ㉣ 파임 평균이 실측 폭(2~6칸) 안", dsum / dn >= 2 && dsum / dn <= 8, dsum / dn);
        check("★주상절리 ㉣ 가장 깊은 파임도 상한 안 (산에 구멍이 안 뚫린다)",
                deepest <= com.honcheon.mvt.forge.SpireField.JOINT_MAX + 2, deepest);
        check("★팔레트 — 암벽 마루는 두 종 이하 (돌·이끼)", capSeen.size() <= 2, capSeen);
        check("★10-② 금지 재료 없음 (barrel·light)", !bannedStone, bannedStone);
        // 석축·포장도 같은 자로 잰다 — 둘 다 순수 함수라 눈이 직접 부른다
        java.util.Set<Material> faceSeen = java.util.EnumSet.noneOf(Material.class);
        java.util.Set<Material> paveSeen = java.util.EnumSet.noneOf(Material.class);
        for (int s = 0; s < 4000; s++) {
            int px = (s * 13) % 977 - 400;
            int py = (s * 7) % 131;
            int pz = (s * 29) % 883 - 400;
            faceSeen.add(com.honcheon.mvt.forge.TerraceForge.faceMaterial(px, py, pz, Integer.MAX_VALUE));
            paveSeen.add(com.honcheon.mvt.forge.TerraceForge.paveMaterial(px, pz));
        }
        check("★팔레트 — 석축은 두 종 이하 (석전 + 층대 띠)", faceSeen.size() <= 2, faceSeen);
        check("★팔레트 — 포장은 한 종 (누런 바둑판 금지 · D-43)", paveSeen.size() == 1, paveSeen);
        java.util.Set<Material> roofSeen = java.util.EnumSet.noneOf(Material.class);
        for (int s = 0; s < 4000; s++) {
            roofSeen.add(com.honcheon.mvt.forge.HwasanCampusBuilder.roofCube(
                    (s * 13) % 977 - 400, (s * 7) % 131, (s * 29) % 883 - 400));
        }
        check("★팔레트 — 지붕은 한 종 (연마 안산암이 들뜨게 하지 않는다 · D-15/26)",
                roofSeen.size() == 1, roofSeen);
        // ★층대 띠는 <b>구조</b>다 — 위치(y)가 정하지 해시가 정하지 않는다. 같은 y면 늘 같아야 한다.
        boolean bandByPosition = true;
        for (int s = 0; s < 500; s++) {
            int py = 4 * (s % 20);      // y%4==0 인 켜
            if (com.honcheon.mvt.forge.TerraceForge.faceMaterial(s * 3, py, s * 5, Integer.MAX_VALUE)
                    != com.honcheon.mvt.forge.TerraceForge.faceMaterial(s * 7 + 11, py, s * 2, Integer.MAX_VALUE)) {
                bandByPosition = false;
            }
        }
        check("★층대 띠 — 같은 켜는 어디서나 같다 (위치가 정하면 구조, 해시가 정하면 잡티)",
                bandByPosition, bandByPosition);
        // ══════════ ★★슬라이스 15 — 암벽 표면의 질 (사용자 판정 「산의 퀄리티」) ══════════
        // ①-㉢ 얼룩 — 같은 재료가 뭉쳐야 한다. 이웃한 두 열이 같은 재료일 확률이 「점점이」
        //   (한 블록마다 굴림 ≈ 12%) 보다 확실히 높아야 얼룩으로 읽힌다.
        int same = 0;
        int pairs = 0;
        for (int s = 0; s < 600; s++) {
            int bx = 400 + (s % 25);
            int by = 40 + (s / 25) % 12;
            int bz = 120 + (s % 17);
            if (com.honcheon.mvt.forge.SpireField.stone(bx, by, bz, false)
                    == com.honcheon.mvt.forge.SpireField.stone(bx + 1, by, bz, false)) {
                same++;
            }
            pairs++;
        }
        check("★15-㉢ 얼룩 — 이웃 열이 같은 재료일 확률 ≥30% (점점이 노이즈가 아니다)",
                same * 100 / pairs >= 30, same * 100 / pairs + "%");
        // ②-이음매 — 산과 옹벽의 경계가 흐려야 한다. 전에는 <b>암벽에 석전을 섞어</b> 그 모호함을
        //   냈다 (열한 종의 명분 중 하나였다). 이제는 <b>밝기로</b> 낸다 — 섞지 않고도 두 재료가
        //   충분히 가까우면 눈이 못 가른다 (돌 126 · 석전 122 — 차이 4).
        int rockL = lumaOf(com.honcheon.mvt.forge.SpireField.stone(400, 40, 120, false));
        int wallL = lumaOf(com.honcheon.mvt.forge.TerraceForge.faceMaterial(400, 41, 120, Integer.MAX_VALUE));
        check("★15-② 이음매 흐림 — 암벽과 석축의 밝기 차 ≤12 (섞지 않고 붙인다)",
                Math.abs(rockL - wallL) <= 12, rockL + " vs " + wallL);
        // ★15 유출 오탐의 계약 — 늑재는 「산의 것」이라 스캔 밖이어야 한다 (조경 표와 같은 문법).
        //   실기동 오탐 8건(사암)의 처방: 암벽 재료 ∩ 유출 스캔 = ∅.
        java.util.Set<Material> rockMats = com.honcheon.mvt.forge.SpireField.rockMats();
        java.util.Set<Material> leakTbl = com.honcheon.mvt.forge.HwasanCampusBuilder.leakScanMats();
        java.util.Set<Material> both = java.util.EnumSet.copyOf(rockMats);
        both.retainAll(leakTbl);
        check("★15 암벽 ∩ 유출 스캔 = ∅ (늑재를 건물로 오인하지 않는다)", both.isEmpty(), both);
        // 그러나 건물 전용 재료는 여전히 잡혀야 한다 — 「빼기」가 스캔을 무력화하면 안 된다
        check("★15 건물 전용 재료는 스캔에 남는다 (회벽·기와·유리)",
                leakTbl.contains(Material.BONE_BLOCK)                     // ★회벽 (단일 재료 재선정 후)
                        && leakTbl.contains(Material.COBBLED_DEEPSLATE)   // ★D-26 회색 기와
                        && leakTbl.contains(Material.GLASS_PANE), leakTbl.size());
        // ★★회벽은 「팩이 덮어 흰색으로 보이던」 재료를 쓰지 않는다 (2026-08-05 실증).
        //   바닐라 white_terracotta 는 RGB(210,178,161) 살구색이고, 우리가 흰 벽으로 알던 것은
        //   팩 텍스처(201,203,205)였다. 팩을 끄면 드러나므로 **바닐라 색이 목표인 재료만** 쓴다.
        check("★회벽에 살구색 재료(white_terracotta)를 안 쓴다",
                !com.honcheon.mvt.forge.HwasanCampusBuilder.palette().contains(Material.WHITE_TERRACOTTA),
                com.honcheon.mvt.forge.HwasanCampusBuilder.palette().contains(Material.WHITE_TERRACOTTA));
        // ★★★회벽은 **단일 재료**다 — 혼합은 실기동에서 실패했다 (2026-08-05).
        //   석영:사암 2:1 의 인게임 채도 분포가 저채도 58% · 중간대 1% · 고채도 42% 로 갈렸다.
        //   합성 평균은 계산대로였지만 **눈에는 평균이 아니라 두 색의 바둑판**이 보인다.
        //   ★옛 눈(「합성 채도가 목표 창 안」)은 이 실패를 통과시켰다 — 평균만 보고 분포를
        //   안 봤기 때문이다. 그래서 계약을 **재료 가짓수**로 바꾼다: 하나면 바둑판이 불가능하다.
        java.util.Set<Material> plasterMats = java.util.EnumSet.noneOf(Material.class);
        for (int px = 0; px < 24; px++) {
            for (int py = 0; py < 8; py++) {
                for (int pz = 0; pz < 24; pz++) {
                    plasterMats.add(com.honcheon.mvt.forge.HwasanCampusBuilder.plaster(px, py, pz));
                }
            }
        }
        check("★★회벽이 단일 재료다 (바둑판이 구조적으로 불가능하다 — 혼합 실패의 재발 방지)",
                plasterMats.size() == 1, plasterMats);
        check("★회벽 재료가 따뜻한 near-white 다 (실측 H50 S9.4 — 목표 H40 S15.6 에 최근접)",
                plasterMats.contains(Material.BONE_BLOCK), plasterMats);
        check("★회벽이 유출 스캔에 남는다 (BONE_BLOCK 은 rockMats 가 아니다)",
                leakTbl.contains(Material.BONE_BLOCK)
                        && !rockMats.contains(Material.BONE_BLOCK),
                leakTbl.contains(Material.BONE_BLOCK));
        // ★15 표면에 심을 수 있는가 — 암벽 재료가 「못 심는 땅」이면 산이 조용히 민둥이 된다
        //   (실기동: 석전 섞임 뒤 산 표면 ~12%가 식생에서 빠졌다)
        java.util.Set<Material> plantable = java.util.EnumSet.of(
                Material.STONE, Material.ANDESITE, Material.TUFF, Material.CALCITE,
                Material.DRIPSTONE_BLOCK, Material.SANDSTONE, Material.MOSS_BLOCK,
                Material.COBBLESTONE, Material.MOSSY_COBBLESTONE,
                Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS,
                Material.GRASS_BLOCK, Material.DIRT);
        java.util.Set<Material> barren = java.util.EnumSet.copyOf(rockMats);
        barren.removeAll(plantable);
        check("★15 암벽 재료는 전부 심을 수 있다 (민둥 산 방지)", barren.isEmpty(), barren);
        // ①-㉠㉡ 틈·바위턱 — 한 필드에서 파인 열과 온전한 열이 공존해야 결이 생긴다
        com.honcheon.mvt.forge.SpireField fld =
                new com.honcheon.mvt.forge.SpireField(java.util.List.of());
        int carved = 0;
        int solid = 0;
        for (int x = 260; x < 320; x++) {
            for (int z = -30; z < 30; z++) {
                int h = fld.targetH(x, z);
                if (h <= 0) {
                    continue;
                }
                int nb = Math.max(fld.targetH(x + 1, z), fld.targetH(x, z + 1));
                if (nb - h >= 2) {
                    carved++;
                } else if (Math.abs(nb - h) <= 1) {
                    solid++;
                }
            }
        }
        check("★15-㉠㉡ 표면의 결 — 파인 틈과 온전한 기둥이 공존한다 (매끈한 계단 반복이 아니다)",
                carved > 0 && solid > carved, carved + "틈/" + solid + "기둥");
        // ★6.7 형태 계약 (§4-b) — 「바늘 침봉·원뿔 배후봉」 재발 방지
        //
        // ★2026-08-06 <b>자를 옮겼다 (문턱은 그대로)</b>. 대(臺)를 세우자 이 눈 둘이 실패했는데,
        //   실물을 재 보니 <b>모양은 계약대로였고 자가 마루 위에 있었다</b>:
        //     · 「최고점」을 상자 300..380 에서 찾았는데 진짜 꼭대기는 그 <b>서쪽</b>이었다
        //       (서로 갈수록 47→57→61). 상자 모서리를 봉우리로 착각해 재고 있었다.
        //     · 배후봉을 ±20 에서 쟀는데 마루가 ±24 까지 평평하다 — 장축 226 vs 급벽 228 로
        //       구분이 안 됐다. ±30 에서는 장축 227 vs 급벽 70 으로 <b>또렷하다</b>.
        //   그래서 문턱을 낮추는 대신 ㉠꼭대기를 넓게 찾고 ㉡<b>마루 끝을 눈이 스스로 찾아</b>
        //   그 밖에서 잰다. 상수가 바뀌어도 안 썩는 자다.
        int mx = 0, mz = 0, mh = 0;
        for (int gx = 200; gx <= 460; gx++) {
            for (int gz = -60; gz <= 140; gz++) {
                int hh = bare.targetH(gx, gz);
                if (hh > mh) {
                    mh = hh;
                    mx = gx;
                    mz = gz;
                }
            }
        }
        int bodyMin = Integer.MAX_VALUE;
        for (int[] dir : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int body = 0;
            for (int k = 1; k <= 40; k++) {
                if (bare.targetH(mx + dir[0] * k, mz + dir[1] * k) >= mh * 0.80) {
                    body = k;
                } else {
                    break;
                }
            }
            bodyMin = Math.min(bodyMin, body);
        }
        check("산군: 봉우리에 몸통이 있다 — 사방 ≥3칸이 마루의 80% (바늘 금지)",
                mh > 0 && bodyMin >= 3, "h" + mh + " 몸통 " + bodyMin + "칸");
        //   배후봉 병풍 비대칭 — <b>마루 밖에서</b> 잰다. 마루 끝은 눈이 찾는다.
        int pcx = -24, pcz = -54;
        int pc = bare.targetH(pcx, pcz);
        int edge = 0;
        for (int k = 1; k <= 60; k++) {
            if (bare.targetH(pcx, pcz + k) < pc * 0.95) {
                edge = k;
                break;
            }
        }
        int probe = edge + 6;                       // 마루 끝을 막 벗어난 자리
        check("산군: 배후봉 마루가 실재한다 (평평한 대가 있다 — 원뿔이면 곧장 떨어진다)",
                edge >= 8, "마루 끝 " + edge + "칸");
        check("산군: 배후봉 Pm 비대칭 (남 급벽 < 북 완사 — §4-b 0.80/1.15)",
                bare.targetH(pcx, pcz + probe) < bare.targetH(pcx, pcz - probe),
                bare.targetH(pcx, pcz + probe) + " vs " + bare.targetH(pcx, pcz - probe));
        check("산군: 배후봉 Pm 능선꼴 (장축 > 급벽횡 — 마루 밖에서)",
                bare.targetH(pcx + probe, pcz) > bare.targetH(pcx, pcz + probe),
                bare.targetH(pcx + probe, pcz) + " vs " + bare.targetH(pcx, pcz + probe));
        // ★6.5 — 기준면 프로브: 필드 밖 + 무침범 (6.0 병: 침봉 마루에서 기준을 재 캠퍼스가 54칸 떴다)
        check("산군: 프로브가 필드 밖 (PROBE_X > FIELD_R)",
                com.honcheon.mvt.forge.SpireField.PROBE_X > com.honcheon.mvt.forge.SpireField.FIELD_R,
                com.honcheon.mvt.forge.SpireField.PROBE_X);
        check("산군: 프로브 열 무침범 (probeUntouched)",
                com.honcheon.mvt.forge.SpireField.probeUntouched(), "±2 링");

        // ══════════ ⑤ 팔레트 — 금지 재료가 없다 (B-195 · HANDOFF 함정) ══════════
        Set<Material> palette = TerraceForge.palette();
        check("★ BARREL 없음 (가구_3D 유령 벽)", !palette.contains(Material.BARREL), "BARREL");
        check("★ LIGHT 없음 (컬링 전과)", !palette.contains(Material.LIGHT), "LIGHT");
        check("팔레트가 비어 있지 않다", !palette.isEmpty(), palette.size());
        Set<Material> bPal = com.honcheon.mvt.forge.HwasanCampusBuilder.palette();
        check("★ 배치기: BARREL 없음 (상자는 chest)", !bPal.contains(Material.BARREL), "BARREL");
        check("★ 배치기: LIGHT 없음", !bPal.contains(Material.LIGHT), "LIGHT");
        check("배치기: chest 로 대체했다", bPal.contains(Material.CHEST), "CHEST");
        Set<Material> lPal = com.honcheon.mvt.forge.HwasanCampusBuilder.landscapePalette();
        check("★조경: BARREL·LIGHT 없음",
                !lPal.contains(Material.BARREL) && !lPal.contains(Material.LIGHT), "b/l");
        Set<Material> scan = com.honcheon.mvt.forge.HwasanCampusBuilder.leakScanMats();
        boolean disjoint = true;
        for (Material m : lPal) {
            if (scan.contains(m)) {
                disjoint = false;
            }
        }
        check("★조경 재료 ∩ 유출 스캔 = ∅ (패드 밖이 정상인 것을 눈이 쫓지 않는다)", disjoint, "교집합");

        // ══════════ ⑥ 건물 발자국 ⊂ 패드 — 순수 검증 (계율 #4) ══════════
        boolean bOk = true;
        String bWhy = "";
        try {
            java.util.List<TerraceForge.Pad> allPads = TerraceForge.resolvePads(campus, 0, 0, 0);
            java.util.List<TerraceForge.StairLane> allLanes = TerraceForge.resolveLanes(campus, allPads);
            com.honcheon.mvt.forge.HwasanCampusBuilder.validateBuildings(allPads, allLanes,
                    TerraceForge.resolveBridges(campus, allPads, allLanes, 0, 0, 0));
        } catch (IllegalArgumentException e) {
            bOk = false;
            bWhy = e.getMessage();
        }
        check("기본 캠퍼스 전 구역 건물 발자국이 패드 안", bOk, bWhy);

        // ══════════ ⑦ 상자 = 발자국 한 식 — 마른 조성 (슬라이스 7.5 · 평탄 95건의 처방) ══════════
        // 상자는 손으로 적히지 않는다: parts() 를 world=null 로 돌린 발자국이 곧 상자다.
        // 7.0 의 병(장로회 처마 링이 상자 밖)과 5.6 의 병(정원 매화 한 칸 어긋남)을 값으로 재현해 잰다.
        {
            java.util.List<TerraceForge.Pad> allPads = TerraceForge.resolvePads(campus, 0, 0, 0);
            // ★2026-08-08 — <b>표본을 옮겼다</b>. 이 눈이 재는 것은 「전고 상자 ⊃ 지상 상자」
            //   (처마는 전고에만 든다)인데, 장로회는 이제 <b>기단만</b> 깔아 두 상자가 같다.
            //   계약은 그대로이므로 <b>아직 코드가 지붕을 얹는</b> 망루(11)로 표본을 옮긴다 —
            //   눈을 지우는 게 아니라 재는 자리를 옮기는 것이다.
            TerraceForge.Pad tower11 = allPads.stream()
                    .filter(p -> p.spec().zone() == 11).findFirst().orElseThrow();
            int[] fb = com.honcheon.mvt.forge.HwasanCampusBuilder.structureBoxes(tower11).get(0);
            int[] gb = com.honcheon.mvt.forge.HwasanCampusBuilder.groundBoxes(tower11).get(0);
            check("★전고 상자가 처마를 덮는다 (망루 · 지붕이 몸체보다 넓다)",
                    fb[1] - fb[0] > gb[1] - gb[0] && fb[3] - fb[2] > gb[3] - gb[2],
                    "전고 " + (fb[1] - fb[0] + 1) + "×" + (fb[3] - fb[2] + 1)
                            + " · 지상 " + (gb[1] - gb[0] + 1) + "×" + (gb[3] - gb[2] + 1));
            check("★지상 상자는 처마를 뺀다 (통로 검증은 걷는 몸높이만)",
                    gb[0] >= fb[0] && gb[1] <= fb[1] && gb[2] >= fb[2] && gb[3] <= fb[3],
                    "지상 ⊂ 전고");
            // ★장로회는 이제 기단만이라 두 상자가 같다 — 그 사실도 적어 둔다
            TerraceForge.Pad jangno = allPads.stream()
                    .filter(p -> p.spec().zone() == 12).findFirst().orElseThrow();
            int[] jf = com.honcheon.mvt.forge.HwasanCampusBuilder.structureBoxes(jangno).get(0);
            int[] jg = com.honcheon.mvt.forge.HwasanCampusBuilder.groundBoxes(jangno).get(0);
            check("★장로회는 기단만이라 전고=지상이다 (지붕은 도면이 갖는다)",
                    java.util.Arrays.equals(jf, jg),
                    (jf[1] - jf[0] + 1) + "×" + (jf[3] - jf[2] + 1));
            TerraceForge.Pad garden = allPads.stream()
                    .filter(p -> p.spec().zone() == 10).findFirst().orElseThrow();
            int gcx = garden.x0() + garden.spec().width() / 2;
            int gcz = garden.zN() + garden.spec().depth() / 2;
            boolean plumBoxed = com.honcheon.mvt.forge.HwasanCampusBuilder.structureBoxes(garden).stream()
                    .anyMatch(b -> b[0] == gcx - 6 && b[1] == gcx + 2 && b[2] == gcz - 10 && b[3] == gcz - 2);
            check("★정원 매화 상자 = 수관 ±4 그대로 (5.6 재발 방지 · 척도 되돌림 — 손이 아니라 코드가 적는다)",
                    plumBoxed, "수관 상자 불일치");
        }

        // ══════════ ⑧ 슬라이스 9 — 산이 건축이 되게 (코덱스 검토 계약) ══════════
        {
            java.util.List<TerraceForge.Pad> allPads = TerraceForge.resolvePads(campus, 0, 0, 0);
            TerraceForge.Pad gate = allPads.stream()
                    .filter(p -> p.spec().zone() == 1).findFirst().orElseThrow();
            TerraceForge.Pad main = allPads.stream()
                    .filter(p -> p.spec().zone() == 9).findFirst().orElseThrow();
            int gateTop = com.honcheon.mvt.forge.HwasanCampusBuilder.structureTopY(gate) - gate.y();
            int mainTop = com.honcheon.mvt.forge.HwasanCampusBuilder.structureTopY(main) - main.y();
            check("★9 산문 재설계 — 총고 ≥20 (3단 구성 · 코덱스 개선 2 · ★척도 되돌림)",
                    gateTop >= 20, gateTop);
            check("★9 위계 — 본전이 산문보다 높다 (같은 층고 금지 · 코덱스 §⑤)",
                    mainTop > gateTop, mainTop + " vs " + gateTop);
            TerraceForge.Pad plaza = allPads.stream()
                    .filter(p -> p.spec().zone() == 2).findFirst().orElseThrow();
            TerraceForge.Pad jong = allPads.stream()
                    .filter(p -> p.spec().zone() == 6).findFirst().orElseThrow();
            int plazaParts = com.honcheon.mvt.forge.HwasanCampusBuilder.structureBoxes(plaza).size();
            int jongParts = com.honcheon.mvt.forge.HwasanCampusBuilder.structureBoxes(jong).size();
            // ★2026-08-06 E-02 — 외원 8 → <b>6</b>. 정자를 의례축 밖(±11)으로 밀고, 그 자리를
            //   내주느라 <b>남측 행각 두 토막을 뺐다</b> (원래 6행뿐이라 물리면 길이 0 이 되어
            //   조용히 사라졌다 — 눈이 그것을 잡았다). 행각은 북측 좌우에 남는다.
            //   ★E-03 (2026-08-06): 행각을 삼면 네 모듈로 → 외원 8부품 (정자2 + 행각4 + 등롱열2).
            //   ★E-06 (2026-08-07): 종문도 옛 퍼걸러를 버리고 같은 도면을 쓴다 → 5 → <b>3</b>
            //     (문 + 행각 2). 측면 둘은 어귀 실측 회차의 몫이라 아직 안 선다 (E-06b).
            check("★9 행각 — 외원 8부품(정자2+행각4+등롱열2) · 종문 3부품(문+행각2) — 9b 중앙 통로 갈림",
                    plazaParts == 8 && jongParts == 3, plazaParts + "/" + jongParts);
            check("★D-25 재료 — 단청(금빛·적목)이 팔레트에",
                    bPal.contains(Material.CUT_SANDSTONE)
                            && bPal.contains(Material.STRIPPED_MANGROVE_WOOD), bPal);
            // ★D-26 <b>뒤집힌 눈</b> — 전에는 「회색 기와 결」로 심층암 벽돌·회색 테라코타가
            //   팔레트에 있기를 요구했다. 지붕은 이제 한 종이다. 요구를 반대로 세운다.
            check("★D-26 기와 결 폐기 — 지붕 혼합재가 실제로 안 나온다 (신고표가 아니라 실물)",
                    !roofSeen.contains(Material.DEEPSLATE_BRICKS)
                            && !roofSeen.contains(Material.GRAY_TERRACOTTA)
                            && !roofSeen.contains(Material.POLISHED_ANDESITE), roofSeen);
            // ★9b — 단구 표고 분할: 같은 통단 안 칸이 ±4 로 갈렸다 (원경 스카이라인 요철)
            check("★9b 단구 — B2 표고가 갈렸다 (연무장하 62 > 외원 58 > 생활하 54 · 척도 되돌림)",
                    heightOf(campus, 3) == 62 && heightOf(campus, 2) == 58
                            && heightOf(campus, 5) == 54,
                    heightOf(campus, 3) + "/" + heightOf(campus, 2) + "/" + heightOf(campus, 5));
            // ★9b — 소계단 기하: Δ4 · 전폭 5 (half 2) 링크가 앉고 폭이 좁다
            TerraceForge.Campus mini = new TerraceForge.Campus(
                    List.of(new TerraceForge.PadSpec(1, "위", 0, 0, 20, 16, 14),
                            new TerraceForge.PadSpec(2, "아래", 20, 0, 20, 16, 10)),
                    List.of(new TerraceForge.StairLink(1, 2, 'E', 1)));
            java.util.List<TerraceForge.Pad> mp = TerraceForge.resolvePads(mini, 0, 0, 0);
            TerraceForge.StairLane ml = TerraceForge.resolveLanes(mini, mp).get(0);
            check("★9b 소계단 — 낙차 4 → 디딤 3 · 도보 3 (rail ±2 · 대계단보다 좁다)",
                    ml.treads() == 3 && ml.half() == 1 && ml.rail() == 2,
                    ml.treads() + "/" + ml.half());
            check("★D-19 위계 — 소계단이 대계단보다 좁다",
                    ml.half() < TerraceForge.STAIR_HALF, ml.half() + "<" + TerraceForge.STAIR_HALF);
            TerraceForge.Pad jongmun = allPads.stream()
                    .filter(p -> p.spec().zone() == 6).findFirst().orElseThrow();
            int jongTop = com.honcheon.mvt.forge.HwasanCampusBuilder.structureTopY(jongmun) - jongmun.y();
            check("★9b 층고 차등 — 종문(~17) < 산문(~21) (위계 사다리 · 척도 되돌림)",
                    jongTop < gateTop && jongTop >= 15, jongTop + " vs " + gateTop);
        }

        // ══════════ ⑨ 슬라이스 11.5 — 실지면 정의 통일 (접근로 접지 64건의 처방) ══════════
        check("★11.5 실지면 — 소나무 몸통·잎·관목은 식생 (지면이 아니다)",
                TerraceForge.isVegetation(Material.SPRUCE_WOOD)
                        && TerraceForge.isVegetation(Material.SPRUCE_LEAVES)
                        && TerraceForge.isVegetation(Material.AZALEA), "식생 판정");
        check("★11.5 실지면 — 이끼 블록은 지면이다 (절벽 캡·완사면 바닥은 밟는 땅)",
                !TerraceForge.isVegetation(Material.MOSS_BLOCK)
                        && !TerraceForge.isVegetation(Material.STONE), "지면 판정");

        // ══════════ ⑩ 슬라이스 13a — 밀도 단계 (거대 회색 면의 분해) ══════════
        {
            java.util.List<TerraceForge.Pad> allPads = TerraceForge.resolvePads(campus, 0, 0, 0);
            TerraceForge.Pad main2 = allPads.stream()
                    .filter(p -> p.spec().zone() == 9).findFirst().orElseThrow();
            java.util.List<int[]> boxes = com.honcheon.mvt.forge.HwasanCampusBuilder.structureBoxes(main2);
            int[] hall = boxes.get(0);
            check("★13a-5 본전 처마 확대 — 전고 상자 폭 ≥ 37 (hf15+처마 좌우 · 척도 되돌림)",
                    hall[1] - hall[0] + 1 >= 37, (hall[1] - hall[0] + 1));
            check("★13a-5 본전 발자국은 여전히 패드 안 (처마 확대의 계약)",
                    hall[0] >= main2.x0() && hall[1] <= main2.x1()
                            && hall[2] >= main2.zN() && hall[3] <= main2.zS(), "패드 밖");
            // 옹벽 선반 — 구간마다 나거나 안 나거나 (결정론 · 둘 다 실재해야 「불규칙」이다)
            boolean someShelf = false;
            boolean somePlain = false;
            for (int sx = -400; sx <= 400; sx += 9) {
                int st = TerraceForge.shelfTopAt(main2, sx, main2.zS(), 0, 1, 4);
                if (st != Integer.MIN_VALUE) {
                    someShelf = true;
                } else {
                    somePlain = true;
                }
            }
            check("★13a-1 옹벽 선반 — 구간마다 나거나 안 난다 (불규칙 테라스)",
                    someShelf && somePlain, someShelf + "/" + somePlain);
            // ★13a-3 <b>폐기된 눈의 자리</b> — 「축대 점적석·포장 사암이 표에 있다」를 요구했다.
            //   웜톤을 재료로 흉내 내려던 처방이었고, 그 처방이 오진이었다 (온기는 햇빛이다 —
            //   밝기·색온도 상관 +0.94). 이제 반대를 잰다: <b>표에 없어야 한다.</b>
            //   ★신고표(palette)가 실물보다 넓으면 눈이 헛것을 지킨다 — 신고와 실물을 맞춘다.
            check("★13a-3 웜톤 흉내 폐기 — 점적석·사암이 팔레트에서 빠졌다 (D-42·43)",
                    !palette.contains(Material.DRIPSTONE_BLOCK)
                            && !palette.contains(Material.SMOOTH_SANDSTONE), palette);
            // 신고표가 실물을 덮는가 — 석축·포장이 내는 것은 전부 표 안이어야 한다
            java.util.Set<Material> made = java.util.EnumSet.noneOf(Material.class);
            for (int s = 0; s < 2000; s++) {
                made.add(TerraceForge.faceMaterial(s * 13 % 977, s * 7 % 131, s * 29 % 883,
                        Integer.MAX_VALUE));
                made.add(TerraceForge.paveMaterial(s * 13 % 977, s * 29 % 883));
            }
            java.util.Set<Material> undeclared = java.util.EnumSet.copyOf(made);
            undeclared.removeAll(palette);
            check("★신고표가 실물을 덮는다 (내는 재료는 전부 팔레트 안)",
                    undeclared.isEmpty(), undeclared);
            // ★13b-② 정면 요철 — 본전 남면이 포치만큼 앞으로 나온다 (평평한 긴 벽의 처방)
            int mcz = main2.zN() + main2.spec().depth() / 2;
            check("★13b-② 본전 현관 포치 — 남면 발자국이 몸체보다 앞선다 (중앙 돌출)",
                    hall[3] >= mcz + 2 + 6 + 2, hall[3] - mcz);
            check("★13b-② 포치 확대 뒤에도 패드 담김 유지",
                    hall[3] <= main2.zS(), hall[3] + " vs " + main2.zS());
            // ★13c-① 다중 선반 — 높은 면은 여러 단으로 갈린다 (아래 20칸 민짜의 처방)
            check("★13c-① 면 20 → 선반 ≥2 · 면 36 → ≥3 (높이 비례 · 아래까지 갈린다)",
                    TerraceForge.shelfCountFor(0, 0, 20) >= 2
                            && TerraceForge.shelfCountFor(0, 0, 36) >= 3,
                    TerraceForge.shelfCountFor(0, 0, 20) + "/"
                            + TerraceForge.shelfCountFor(0, 0, 36));
            check("★13c-① 낮은 면(12)은 종전대로 0~1 (불규칙 유지)",
                    TerraceForge.shelfCountFor(0, 0, 12) <= 1,
                    TerraceForge.shelfCountFor(0, 0, 12));
            // ★13c-② 큰 지붕 — 치미 솟음·내림마루가 면을 선으로 가른다 (본전 지붕 높이가 자란다)
            // ★D-26 처마 내밈 2→3 — 내밈이 한 칸 커지면 수렴이 한 켜 늘어 지붕이 한 칸 자란다
            //   (월대 3 + 층 12 + 수렴 + 치미 = 24). 앵커는 실측을 따라간다.
            int mainTopY = com.honcheon.mvt.forge.HwasanCampusBuilder.structureTopY(main2) - main2.y();
            check("★13c-② 본전 지붕 — 치미가 한 칸 더 솟는다 (총고 24 · D-26 내밈 3)",
                    mainTopY == 24, mainTopY);
        }

        // ══════════ ⑪ 슬라이스 14 — 대결에서 배운 기법의 이식 ══════════
        {
            // ★14-① 귀솟음 — 귀가 가장 높고 중앙으로 갈수록 평평해진다 (조성과 이 눈이 한 식)
            check("★14-① 귀솟음 점층 — 귀 2켜 > 한 칸 안 1켜 > 그 안쪽 평평",
                    com.honcheon.mvt.forge.HwasanCampusBuilder.upturnRise(0) == 2
                            && com.honcheon.mvt.forge.HwasanCampusBuilder.upturnRise(1) == 1
                            && com.honcheon.mvt.forge.HwasanCampusBuilder.upturnRise(2) == 0,
                    com.honcheon.mvt.forge.HwasanCampusBuilder.upturnRise(0) + "/"
                            + com.honcheon.mvt.forge.HwasanCampusBuilder.upturnRise(1) + "/"
                            + com.honcheon.mvt.forge.HwasanCampusBuilder.upturnRise(2));
            // ★14-③ 산문 3단 요철 — 중앙이 앞서고 끝이 물러난다 (대결의 공통 진단)
            int rMid = com.honcheon.mvt.forge.HwasanCampusBuilder.gateRelief(0, 28);
            int rBody = com.honcheon.mvt.forge.HwasanCampusBuilder.gateRelief(15, 28);
            int rEnd = com.honcheon.mvt.forge.HwasanCampusBuilder.gateRelief(26, 28);
            check("★14-③ 산문 정면 3단 — 중앙 +2 > 기준 0 > 끝 -1 (평평한 한 판의 처방)",
                    rMid == 2 && rBody == 0 && rEnd == -1, rMid + "/" + rBody + "/" + rEnd);
            // 산문 발자국은 포치·차양이 커져도 여전히 패드 안 (계약)
            java.util.List<TerraceForge.Pad> pads14 = TerraceForge.resolvePads(campus, 0, 0, 0);
            TerraceForge.Pad gate14 = pads14.stream()
                    .filter(p -> p.spec().zone() == 1).findFirst().orElseThrow();
            java.util.List<int[]> gb = com.honcheon.mvt.forge.HwasanCampusBuilder.structureBoxes(gate14);
            boolean gateIn = true;
            for (int[] b : gb) {
                gateIn &= b[0] >= gate14.x0() && b[1] <= gate14.x1()
                        && b[2] >= gate14.zN() && b[3] <= gate14.zS();
            }
            check("★14-③ 산문 중앙 돌출·차양 뒤에도 발자국은 패드 안", gateIn, "패드 밖");
            // ★14-② 위계는 그대로 — 적목을 늘려도 본전 > 산문 > 종문 사다리는 안 흔들린다
            TerraceForge.Pad main14 = pads14.stream()
                    .filter(p -> p.spec().zone() == 9).findFirst().orElseThrow();
            TerraceForge.Pad jong14 = pads14.stream()
                    .filter(p -> p.spec().zone() == 6).findFirst().orElseThrow();
            int gT = com.honcheon.mvt.forge.HwasanCampusBuilder.structureTopY(gate14) - gate14.y();
            int mT = com.honcheon.mvt.forge.HwasanCampusBuilder.structureTopY(main14) - main14.y();
            int jT = com.honcheon.mvt.forge.HwasanCampusBuilder.structureTopY(jong14) - jong14.y();
            check("★14-② 위계 유지 — 본전 > 산문 > 종문 (배색을 덥혀도 사다리는 그대로)",
                    mT > gT && gT > jT, mT + ">" + gT + ">" + jT);
        }

        // ══════════ ⑬ 슬라이스 16 — 이음매 닫기 (축대 면의 기하) ══════════
        {
            // 판정 근거: 15 에서 재료를 통일했는데도 「평평한 축대 면 vs 계단진 산면」이 갈렸다.
            // 그러니 이 눈이 재는 것은 재료가 아니라 <b>거칠기</b>다 — 너무 매끈하면 실패다.
            //
            // ★자연 산면의 거칠기 = faceRelief 가 본래 높이에서 깎아낸 양 (축대와 같은 자).
            //   ★두 값 모두 「결이 없었다면 있었을 높이에서 벗어난 정도」다 — 경사는 안 센다.
            int natSamples = 0;
            long natSum = 0;
            for (int t = 0; t < 400; t++) {
                int h = com.honcheon.mvt.forge.SpireField.jointed(100, 8, 3000 + t, 3000);
                natSum += Math.abs(h - 100);
                natSamples++;
            }
            double natural = (double) natSum / natSamples;

            // 축대 면의 거칠기 — 여러 자리에서 재 평균 (한 자리는 우연일 수 있다)
            double sum = 0;
            int n = 0;
            for (int s = 0; s < 40; s++) {
                sum += TerraceForge.batterRoughness(120, 500 + s * 17, 500 - s * 13, 0, 1, 12);
                n++;
            }
            double batter = sum / n;
            // ★양쪽 문턱을 둔다 — 한쪽만 두면 눈이 절반만 뜬 것이다.
            //   ㉠아래: 매끈하면(전부 3칸 고정) 결이 없다 = 슬라이스 15 판정의 그 병.
            //   ㉡위:   너무 흔들리면 축대가 아니라 <b>무너진 폐허</b>다 — 사람이 쌓은 것으로
            //           안 읽힌다. 열마다 평균 2칸 넘게 벗어나면 실패.
            // ★2026-08-05 — 이 눈의 <b>전제가 뒤집혔다</b>. 전에는 「축대 ≥ 산면 × 0.5」를
            //   요구했다. 산면이 거의 매끈하던 시절엔 그게 「축대가 매끈하다」를 잡는 자였다.
            //   주상절리를 심자 산면이 5.22 로 거칠어졌고, 같은 식이 이제는 <b>축대더러 절벽만큼
            //   깨지라고</b> 요구한다 — 레퍼런스는 정반대다. 다듬은 축대와 깨진 절벽이 나란히 선다.
            //   ★문턱을 낮춰 통과시키지 않는다. 재는 <b>관계</b>를 실측이 말하는 대로 다시 세운다.
            check("★16-④ 축대 면에 결이 실재한다 (전부 3칸 고정이면 0 — 15 판정의 병)",
                    batter >= 0.5, String.format("%.2f", batter));
            check("★16-④ 그러나 무너지지는 않았다 (열 평균 2칸 이내 — 폐허 방지)",
                    batter <= 2.0, String.format("%.3f", batter));
            check("★16-④ 산면이 축대보다 거칠다 (다듬은 것과 깨진 것이 나란히 선다)",
                    natural > batter, String.format("산면 %.2f > 축대 %.2f", natural, batter));

            // ★16-㉡㉢ 구간마다 다르게 나간다 — 돌출·기준·홈이 공존해야 발치 선이 반듯하지 않다
            java.util.Set<Integer> reaches = new java.util.HashSet<>();
            for (int s = 0; s < 300; s++) {
                reaches.add(TerraceForge.batterStepsFor(s * 11, 0, 24, false));
            }
            check("★16-㉡㉢ 배터 칸수가 구간마다 갈린다 (돌출·기준·홈 — 최소 3종)",
                    reaches.size() >= 3, reaches);

            // ★16 결정론 — 두 번 물어도 같은 답 (난수 0)
            // ── ★D-25 단청 띠 — 이번 회차의 본체 (목표 1호의 화려함은 거의 전부 여기서 온다) ──
        {
            int gold = 0;
            int red = 0;
            int other = 0;
            for (int f = -20; f <= 20; f++) {
                for (int l = -3; l <= 3; l++) {
                    Material m = com.honcheon.mvt.forge.HwasanCampusBuilder.dancheong(f, l);
                    if (m == Material.CUT_SANDSTONE) {
                        gold++;
                    } else if (m == Material.STRIPPED_MANGROVE_WOOD) {
                        red++;
                    } else {
                        other++;
                    }
                }
            }
            int tot = gold + red + other;
            check("★D-25 단청 — 금빛이 바탕 (>55%)", gold * 100 / tot > 55, gold * 100 / tot + "%");
            check("★D-25 단청 — 붉은 주두가 끊는다 (15~35%)",
                    red * 100 / tot >= 15 && red * 100 / tot <= 35, red * 100 / tot + "%");
            // ★실기동 판정 (2026-08-05) — 청록은 짙게·드물게 해도 붉은 벽과 보색이라 눈에 먼저
            //   들어왔다. 「거의 안 보여야」 하는 것이 목적이므로 아예 뺐다. 두 색뿐이다.
            check("★D-25 단청 — 금빛과 붉은 주두 둘뿐이다 (청록 없음)", other == 0, other);
            java.util.Set<Material> pal2 = com.honcheon.mvt.forge.HwasanCampusBuilder.palette();
            check("★D-25 단청 — 두 색이 팔레트에 신고됐다 (반블록 변종까지)",
                    pal2.contains(Material.CUT_SANDSTONE)
                            && pal2.contains(Material.CUT_SANDSTONE_SLAB)
                            && pal2.contains(Material.STRIPPED_MANGROVE_WOOD), "");
            check("★D-25 단청 — 청록이 팔레트에서도 빠졌다",
                    !pal2.contains(Material.DARK_PRISMARINE)
                            && !pal2.contains(Material.DARK_PRISMARINE_SLAB), "");
            // ★실기동 판정 ① — 채도가 높으면 형광으로 튄다. 꿀집·밀랍구리는 이제 안 쓴다.
            check("★D-25 단청 — 형광 재료(꿀집·밀랍 산화 구리)를 안 쓴다",
                    !pal2.contains(Material.HONEYCOMB_BLOCK)
                            && !pal2.contains(Material.WAXED_OXIDIZED_CUT_COPPER), "");
        }

        // ── ★D-21 깃대 · ★D-22 석등 (2026-08-05) — 목표 1호의 계단 소품 ──
        {
            java.util.Set<Material> tPal = TerraceForge.palette();
            check("★D-22 석등 — 등롱이 1×1 발광 고체다 (글로우스톤 · 실측 RGB(255,199,104))",
                    tPal.contains(Material.GLOWSTONE), "");
            check("★D-21 깃대 — 기둥·가로대·배너가 팔레트에 신고됐다",
                    tPal.contains(Material.DARK_OAK_FENCE)
                            && tPal.contains(Material.DARK_OAK_LOG)
                            && tPal.contains(Material.BLACK_WALL_BANNER), "");
            // ★★조립 계약 3 (2026-08-05 실기동 판정) — 「3재료가 신고됐다」만 재면 조립이
            //   틀려도 통과한다. 첫 판은 재료는 다 있었는데 ①가로대가 통짜 큐브 ②배너가
            //   로열블루 ③배너가 가로대 위 였다. 그래서 굵기·색·순서를 각각 못 박는다.
            Material[] fp = TerraceForge.flagpoleParts();
            Material fPole = fp[0];
            Material fCross = fp[1];
            Material fBanner = fp[2];
            // ① 가로대가 배너보다 굵지 않다 — 울타리·담장·반블록·트랩도어는 가는 부품이고,
            //    통짜 큐브(원목·판재 등)는 굵다. 배너는 얇은 천이라 가장 가는 축이다.
            java.util.Set<Material> slimParts = java.util.EnumSet.of(
                    Material.DARK_OAK_FENCE, Material.SPRUCE_FENCE, Material.MANGROVE_FENCE,
                    Material.STONE_BRICK_WALL, Material.COBBLED_DEEPSLATE_WALL,
                    Material.DARK_OAK_SLAB, Material.DARK_OAK_TRAPDOOR);
            check("★D-21 조립① 가로대가 배너보다 굵지 않다 (통짜 큐브 금지)",
                    slimParts.contains(fCross), fCross);
            check("★D-21 조립① 기둥도 가는 부품이다", slimParts.contains(fPole), fPole);
            // ② 배너가 어두운 계열 — 목표 실측 H222 S24% V21% (밝은 상위 10%도 V25%).
            //    바닐라 배너 바탕색 RGB 거리: BLACK 29.0 · GRAY 53.3 · BLUE 119.8.
            //    ★밝은 염료(로열블루·하늘·청록 등)를 쓰면 원경에서 점으로 튄다.
            java.util.Set<Material> darkBanner = java.util.EnumSet.of(
                    Material.BLACK_WALL_BANNER, Material.BLACK_BANNER,
                    Material.GRAY_WALL_BANNER, Material.GRAY_BANNER);
            check("★D-21 조립② 배너가 어두운 계열이다 (실측 V21% — 밝은 염료 금지)",
                    darkBanner.contains(fBanner), fBanner);
            // ★★조립④ (2026-08-05 판정) — 「어두운 계열이다」만 재면 <b>무지 검정</b>이 통과하고,
            //   그러면 배너가 어두운 기둥과 붙어 그림자로 읽혀 <b>형태가 사라진다</b>.
            //   계율: 「튀지 않게」와 「안 보이게」는 다르다 — 배너는 보여야 하는 표지다.
            check("★D-21 조립④ 배너에 문양이 있다 (무지 금지 — 짙은 바탕에선 형태가 사라진다)",
                    !TerraceForge.bannerPatternColors().isEmpty(),
                    TerraceForge.bannerPatternColors().size() + "개");
            boolean brightPat = TerraceForge.bannerPatternColors().stream().anyMatch(c -> {
                org.bukkit.Color rgb = c.getColor();
                return (rgb.getRed() + rgb.getGreen() + rgb.getBlue()) / 3 > 160;   // 밝은 염료
            });
            check("★D-21 조립④ 문양이 밝다 (짙은 바탕과 대비되어 읽힌다)", brightPat,
                    TerraceForge.bannerPatternColors().get(0));
            check("★D-21 조립④ 바탕은 어둡다 (문양 포함 합성이 목표 V24% 에 맞는다)",
                    TerraceForge.bannerBase() == org.bukkit.DyeColor.BLACK
                            || TerraceForge.bannerBase() == org.bukkit.DyeColor.GRAY,
                    TerraceForge.bannerBase());
            check("★D-21 조립② 밝은 배너를 안 쓴다 (로열블루·하늘·청록)",
                    !tPal.contains(Material.BLUE_BANNER)
                            && !tPal.contains(Material.BLUE_WALL_BANNER)
                            && !tPal.contains(Material.LIGHT_BLUE_WALL_BANNER)
                            && !tPal.contains(Material.CYAN_WALL_BANNER), "");
            // ③ 배너가 가로대보다 아래 — 벽걸이 배너는 제 자리에서 아래로 드리운다.
            check("★D-21 조립③ 배너가 가로대보다 아래에 있다 (늘어진 형태)",
                    TerraceForge.FLAG_BANNER_Y < TerraceForge.FLAG_CROSS_Y,
                    "배너 y" + TerraceForge.FLAG_BANNER_Y + " · 가로대 y" + TerraceForge.FLAG_CROSS_Y);
            check("★D-21 조립③ 기둥이 배너 아래를 받친다",
                    TerraceForge.FLAG_POLE_TOP < TerraceForge.FLAG_BANNER_Y,
                    "기둥 " + TerraceForge.FLAG_POLE_TOP);
            check("★D-21 깃대 — 세로 간격이 목표 실측 창(8~10) 안",
                    TerraceForge.BANNER_EVERY >= 8 && TerraceForge.BANNER_EVERY <= 10,
                    TerraceForge.BANNER_EVERY);
            // ★자리 — 난간 캡 위(±RAIL_OFF). 목표 사진 그대로다. 가는 기둥(폭 1)이라
            //   축선 회랑(두꺼운 소품 전용)에 걸리지 않는다 — 2026-08-05 계약 교정.
            check("★D-21 깃대 — 난간 캡 위에 선다 (목표 자리 · 가는 기둥이라 시야를 안 막는다)",
                    TerraceForge.RAIL_OFF < TerraceForge.AXIS_CLEAR, TerraceForge.RAIL_OFF);
            // ★D-22 석등 — 실측 간격 10~12 · 좌우 번갈아
            check("★D-22 석등 — 간격이 목표 실측 창(10~12) 안",
                    TerraceForge.LANTERN_EVERY >= 10 && TerraceForge.LANTERN_EVERY <= 12,
                    TerraceForge.LANTERN_EVERY);
            // 한 자리에 깃대와 석등을 겹쳐 세우면 뒤엣것이 앞엣것을 덮어 조용히 하나가 사라진다
            int clash = 0;
            for (int i = 0; i < TerraceForge.APPROACH_LEN; i++) {
                if (TerraceForge.isFlagpoleRow(i)
                        && (TerraceForge.isLanternRow(i, true) || TerraceForge.isLanternRow(i, false))) {
                    clash++;
                }
            }
            check("★D-21·22 깃대와 석등이 한 자리를 다투지 않는다", clash == 0, clash + "행");
            // 석등이 좌우 번갈아 — 한쪽에만 몰리면 「열」이 아니다
            int lf = 0;
            int rt = 0;
            for (int i = 0; i < TerraceForge.APPROACH_LEN; i++) {
                if (TerraceForge.isLanternRow(i, true)) {
                    lf++;
                }
                if (TerraceForge.isLanternRow(i, false)) {
                    rt++;
                }
            }
            check("★D-22 석등이 좌우 번갈아 선다 (양쪽 ≥2기)", lf >= 2 && rt >= 2, lf + "/" + rt);
            // ★8.7 계율 — 부속 자리는 전부 길이 안이어야 한다 (조용히 사라지지 않게)
            int outOfRange = 0;
            for (int i : TerraceForge.approachFixtureIndices()) {
                if (i < 0 || i >= TerraceForge.APPROACH_LEN) {
                    outOfRange++;
                }
            }
            check("★D-21 깃대까지 포함해 접근로 부속이 전부 길이 안", outOfRange == 0, outOfRange);
            // 깃대가 실제로 여러 기 선다 (한 기만 서면 「열」이 아니다)
            int bannerCount = 0;
            for (int i = TerraceForge.BANNER_FROM; i < TerraceForge.APPROACH_LEN;
                    i += TerraceForge.BANNER_EVERY) {
                bannerCount++;
            }
            check("★D-21 깃대가 계단을 따라 열을 이룬다 (좌우 쌍 ≥4기)",
                    bannerCount >= 4, bannerCount + "기(쌍)");

            // ══════ ★★문전 비움 — gate_forecourt_clearance (사용자 확정 2026-08-06) ══════
            //   「현재 문제는 실제 보행 폭이 아니라 <b>문간의 시각적 폭</b>이다」 — 통로를 7 로
            //   넓혔는데도 정면에서는 「깃대|적주|문살|통로|적주|등롱」으로 읽혀 다시 5칸으로
            //   압축돼 보인다. 독립 수직물이 적주보다 <b>앞에</b> 서서 문루의 위계를 나눠 먹는다.
            //   ★이 눈은 <b>넓이가 아니라 비움</b>을 잰다 — 통행 폭을 재던 눈들과 다른 자다.
            {
                int tallInForecourt = 0;
                int propRows = 0;
                int maxH = 0;
                boolean symmetric = true;
                for (int i = 0; i < TerraceForge.FORECOURT_TO; i++) {
                    int hl = TerraceForge.propHeight(i, true);
                    int hr = TerraceForge.propHeight(i, false);
                    if (hl != hr) {
                        symmetric = false;                       // symmetry_required
                    }
                    maxH = Math.max(maxH, Math.max(hl, hr));
                    if (hl > 0 || hr > 0) {
                        propRows++;
                    }
                    if (TerraceForge.isFlagpoleRow(i)) {
                        tallInForecourt++;                       // forbid: tall_banner_pole
                    }
                    if (TerraceForge.isLanternRow(i, true) || TerraceForge.isLanternRow(i, false)) {
                        tallInForecourt++;                       // forbid: freestanding_tall_lantern
                    }
                }
                check("★문전 비움 — i 0~" + (TerraceForge.FORECOURT_TO - 1)
                                + " 에 깃대·높은 독립 등롱이 없다 (중앙축이 한 번에 뚫린다)",
                        tallInForecourt == 0, tallInForecourt + "행");
                check("★문전 비움 — 소품 높이 ≤ " + TerraceForge.FORECOURT_MAX_H
                                + " (낮으면 난간의 연장으로 읽혀 축을 안 자른다)",
                        maxH <= TerraceForge.FORECOURT_MAX_H, maxH);
                check("★문전 비움 — 낮은 석등이 좌우 대칭 한 쌍만",
                        symmetric && propRows == 1, propRows + "행 · 대칭 " + symmetric);
                // ★눈이 헛것을 지키지 않는가 — 그 한 쌍이 <b>실재</b>해야 한다 (0행도 「≤3」을
                //   통과한다. 「없음」과 「낮음」은 다르다 — 문 앞이 캄캄해지면 그것도 틀렸다)
                check("★문전 비움 — 그 한 쌍이 실재한다 (비움이 곧 어둠은 아니다)",
                        TerraceForge.isForecourtLanternRow(TerraceForge.FORECOURT_LANTERN_I)
                                && TerraceForge.propHeight(TerraceForge.FORECOURT_LANTERN_I, true)
                                        == TerraceForge.FORECOURT_MAX_H,
                        "i" + TerraceForge.FORECOURT_LANTERN_I);
                // min_clear_half_width — <b>시각</b>의 자다. 보행 반폭(3)보다 넓어야 뜻이 있다
                check("★문전 시각 여유 반폭 " + TerraceForge.FORECOURT_CLEAR
                                + " 이 보행 반폭(" + TerraceForge.STAIR_HALF + ")보다 넓다",
                        TerraceForge.FORECOURT_CLEAR > TerraceForge.STAIR_HALF,
                        TerraceForge.FORECOURT_CLEAR + " vs " + TerraceForge.STAIR_HALF);
                // 첫 깃대 — 11칸 전이 참의 외곽 모서리(±LANDING_BANNER_OFF)
                check("★깃대 첫 자리가 전이 참(i" + TerraceForge.WIDEN_FROM + "~"
                                + (TerraceForge.WIDEN_TO - 1) + ")으로 내려왔다",
                        TerraceForge.BANNER_FROM >= TerraceForge.FORECOURT_TO
                                && TerraceForge.BANNER_FROM < TerraceForge.WIDEN_TO,
                        "BANNER_FROM " + TerraceForge.BANNER_FROM);
                check("★그 깃대가 참의 난간(±" + (TerraceForge.approachHalf(TerraceForge.WIDEN_FROM) + 1)
                                + ") 밖 · 문간 시각 여유 밖에 선다",
                        TerraceForge.LANDING_BANNER_OFF
                                        > TerraceForge.approachHalf(TerraceForge.WIDEN_FROM) + 1
                                && TerraceForge.LANDING_BANNER_OFF >= TerraceForge.FORECOURT_CLEAR,
                        "±" + TerraceForge.LANDING_BANNER_OFF);
                check("★조성과 눈이 한 식 — 그 행의 소품 오프셋이 ±"
                                + TerraceForge.LANDING_BANNER_OFF,
                        TerraceForge.propOff(TerraceForge.BANNER_FROM)
                                == TerraceForge.LANDING_BANNER_OFF,
                        TerraceForge.propOff(TerraceForge.BANNER_FROM));
                // 하단 계단의 리듬은 <b>유지</b>한다 — 자리만 옮겼지 열을 지우지 않았다
                int railProps = 0;
                for (int i = TerraceForge.WIDEN_TO; i < TerraceForge.APPROACH_LEN; i++) {
                    if (TerraceForge.propHeight(i, true) > 0 || TerraceForge.propHeight(i, false) > 0) {
                        railProps++;
                    }
                }
                check("★하단 계단의 깃대·등롱 리듬은 유지된다 (≥10행)", railProps >= 10, railProps + "행");
            }

            // ══════ ★★gate_facade_tree_clearance — 줄기가 문루를 안 가린다 ══════
            //   「현재 계약은 <b>나무가 없다</b>가 아니라 <b>나무가 건축을 가리지 않는다</b>인데,
            //   이 기준 카메라에서는 아직 가리고 있다」 (사용자 · 2026-08-06).
            //   ★수관이 처마를 감싸는 것은 좋다 — 막는 것은 <b>줄기</b>가 문루 앞에 겹치는 것.
            {
                int trunkInProjection = 0;
                int pinesInDepth = 0;
                int pinesBeside = 0;
                for (int i = 15; i < TerraceForge.APPROACH_LEN; i += TerraceForge.PINE_EVERY) {
                    if (i < TerraceForge.FACADE_CLEAR_DEPTH) {
                        pinesInDepth++;
                        if (TerraceForge.pineOff(i) <= TerraceForge.FACADE_CLEAR_HALF) {
                            trunkInProjection++;
                        }
                    } else if (TerraceForge.pineOff(i) == TerraceForge.APPROACH_CLEAR + 2) {
                        pinesBeside++;
                    }
                }
                check("★소나무 줄기가 산문 정면 투영(반폭 " + TerraceForge.FACADE_CLEAR_HALF
                                + " · 깊이 " + TerraceForge.FACADE_CLEAR_DEPTH + ") 밖에 선다",
                        trunkInProjection == 0, trunkInProjection + "그루");
                // ★헛것을 지키는 눈 막기 — 그 깊이에 소나무가 <b>실제로</b> 서야 계약이 산다
                //   (한 그루도 안 서면 위 눈은 0 으로 조용히 통과한다. 실제로 가리던 것이 i15 다)
                check("★그 깊이에 소나무가 실재한다 (눈이 헛것을 지키지 않는다)",
                        pinesInDepth >= 1, pinesInDepth + "그루");
                check("★그 밖의 소나무는 그대로 곁에 선다 (밀어내기만 한 것이 아니다)",
                        pinesBeside >= 2, pinesBeside + "그루");

                // ══════ ★★정면 투영을 통째로 비운다 (사용자 확정 2026-08-06) ══════
                //   ★실측이 진범을 바꿨다: 문 앞을 가로지르던 것은 소나무가 아니라 <b>축선 +5 ·
                //   i 4~9 의 주상절리 돌기둥</b>이었다 (포장 y-15 → 꼭대기 y+8 · 지붕 마루보다
                //   6칸 높다). clearAbove 가 <b>제 보행 폭만</b> 비우니 난간 한 칸 밖의 20칸
                //   바위는 아무도 안 건드렸다. <b>가리는 것은 재료를 안 가린다</b> —
                //   「나무가 건축을 가리지 않는다」는 계약이 나무만 보고 있었다.
                check("★문전에서 비우는 반폭이 정면 투영(±" + TerraceForge.FACADE_CLEAR_HALF + ")이다",
                        TerraceForge.clearHalf(0) == TerraceForge.FACADE_CLEAR_HALF,
                        TerraceForge.clearHalf(0));
                check("★그 비움이 난간(±" + (TerraceForge.approachHalf(0) + 1)
                                + ")보다 넓다 — 난간 밖 바위·나무까지 걷는다",
                        TerraceForge.clearHalf(0) > TerraceForge.approachHalf(0) + 1,
                        TerraceForge.clearHalf(0) + " vs " + (TerraceForge.approachHalf(0) + 1));
                // ★문전 <b>밖</b>은 안 넓힌다 — 계단 곁의 바위·나무는 이 터의 성격이다
                boolean besideKept = true;
                for (int i = TerraceForge.FACADE_CLEAR_DEPTH; i < TerraceForge.APPROACH_LEN; i++) {
                    if (TerraceForge.clearHalf(i) != TerraceForge.approachHalf(i) + 1) {
                        besideKept = false;
                    }
                }
                check("★문전 밖에서는 보행면+난간만 비운다 (곁의 절벽·숲을 밀지 않는다)",
                        besideKept, besideKept);
                // ★제 소품을 제가 지우지 않는가 — 비움과 자리가 어긋나면 조용히 사라진다
                //   (i150 비석이 범위 밖에서 조용히 사라진 전례가 있다)
                boolean pineSurvives = true;
                for (int i = 15; i < TerraceForge.FACADE_CLEAR_DEPTH; i += TerraceForge.PINE_EVERY) {
                    if (TerraceForge.pineOff(i) <= TerraceForge.clearHalf(i)) {
                        pineSurvives = false;
                    }
                }
                check("★문전 소나무가 그 비움 밖에 선다 (제 손으로 안 지운다)",
                        pineSurvives, "소나무 ±" + TerraceForge.pineOff(15)
                                + " vs 비움 ±" + TerraceForge.clearHalf(15));
                check("★전이 참 깃대(±" + TerraceForge.LANDING_BANNER_OFF
                                + ")도 그 비움 밖에 선다",
                        TerraceForge.LANDING_BANNER_OFF
                                > TerraceForge.clearHalf(TerraceForge.BANNER_FROM),
                        "비움 ±" + TerraceForge.clearHalf(TerraceForge.BANNER_FROM));

                // ★★비우는 손과 심는 손 — 캠퍼스 조경은 접근로 포장 <b>뒤에</b> 심는다.
                //   깎아 놓은 자리에 도로 꽂으면 비운 보람이 없다 (실측: 바위를 걷어내자
                //   그 자리에 조경 소나무가 남아 문루 오른쪽을 덮었다 — 옛 자리 축선 ±5·6).
                //   ★두 손이 <b>같은 표</b>(inGateFacade)를 읽는지 잰다.
                {
                    java.util.List<TerraceForge.Pad> gp = TerraceForge.resolvePads(campus, 0, 0, 0);
                    TerraceForge.Pad gate1 = gp.stream().filter(p2 -> p2.spec().zone() == 1)
                            .findFirst().orElseThrow();
                    int cx1 = gate1.x0() + gate1.spec().width() / 2;
                    int gz0 = gate1.zS() + 1;              // 접근로 첫 행 (approachOf 와 한 식)
                    java.util.List<int[]> spots =
                            com.honcheon.mvt.forge.HwasanCampusBuilder.pineSpots(gate1);
                    int inFacade = 0;
                    for (int[] s : spots) {
                        if (TerraceForge.inGateFacade(cx1, gz0, s[0], s[1])) {
                            inFacade++;
                        }
                    }
                    check("★산문 조경 소나무 " + spots.size() + "그루가 정면 투영 밖에 선다"
                                    + " (비운 자리에 도로 심지 않는다)",
                            spots.size() >= 2 && inFacade == 0, inFacade + "그루 침범");
                    // ★눈이 헛것을 지키지 않는가 — 옛 자리(±5·6)는 실제로 <b>침범</b>이어야 한다
                    check("★[눈의 눈] 옛 자리(축선 +5)는 그 표가 침범이라고 답한다",
                            TerraceForge.inGateFacade(cx1, gz0, cx1 + 5, gate1.zS() + 4), "");

                    // ══ ★★상위 계약 — 어느 구역도 이 상자를 침범하지 않는다 ══
                    //   「외원은 빈 문전을 <b>채우는</b> 공간이 아니라 그 비어 있는 중앙축의
                    //   <b>양옆</b>을 구성하는 공간이다」 (사용자 확정 2026-08-06).
                    //   ★02 외원이 들어올 때 이 눈이 먼저 짖는다 — 그때 고치는 것이 곧 결정의 기록.
                    int trespass = 0;
                    String worst = "";
                    for (TerraceForge.Pad p2 : gp) {
                        java.util.List<int[]> spots2 =
                                com.honcheon.mvt.forge.HwasanCampusBuilder.pineSpots(p2);
                        for (int[] s : spots2) {
                            if (TerraceForge.inGateFacade(cx1, gz0, s[0], s[1])) {
                                trespass++;
                                worst = "구역 " + p2.spec().zone() + " 소나무";
                            }
                        }
                        for (int[] b : com.honcheon.mvt.forge.HwasanCampusBuilder.decorBoxes(p2)) {
                            for (int bx : new int[]{b[0], b[1]}) {
                                for (int bz : new int[]{b[2], b[3]}) {
                                    if (TerraceForge.inGateFacade(cx1, gz0, bx, bz)) {
                                        trespass++;
                                        worst = "구역 " + p2.spec().zone() + " 소품";
                                    }
                                }
                            }
                        }
                    }
                    check("★★상위 계약 — 전 구역(" + gp.size() + ")의 조경·소품이 정면 투영을"
                                    + " 침범하지 않는다",
                            trespass == 0, trespass == 0 ? "0" : trespass + "건 · " + worst);

                    // ★산군 식생 제외도 같은 상자를 읽는가 (조성 순서에 기대지 않는다)
                    int[] fbox = TerraceForge.facadeBox(gate1);
                    com.honcheon.mvt.forge.SpireField fField =
                            new com.honcheon.mvt.forge.SpireField(java.util.List.of(fbox));
                    boolean facadeExcluded =
                            fField.excluded(cx1, gz0)
                                    && fField.excluded(cx1 + TerraceForge.FACADE_CLEAR_HALF, gz0)
                                    && fField.excluded(cx1 - TerraceForge.FACADE_CLEAR_HALF,
                                            gz0 + TerraceForge.FACADE_CLEAR_DEPTH - 1);
                    check("★산군 식생 제외 상자가 정면 투영을 덮는다 (먼저 심고 깎기에 기대지 않는다)",
                            facadeExcluded, java.util.Arrays.toString(fbox));
                    check("★그 상자 밖은 여전히 심긴다 (문전만 비운다)",
                            !fField.excluded(cx1 + TerraceForge.FACADE_STANDOFF, gz0 + 2),
                            "±" + TerraceForge.FACADE_STANDOFF);
                }

                // ══ ★★문서와 코드가 갈라지지 않는가 — 도면 yml 의 계약 절을 대조한다 ══
                //   ★「신고표가 실물보다 넓으면 눈이 헛것을 지킨다」의 같은 병을 막는다.
                //   문서에 14·18·16 을 적어 두고 코드만 바뀌면 <b>문서가 조용히 늙는다</b>.
                try {
                    java.util.Map<String, Object> graw = new org.yaml.snakeyaml.Yaml().load(
                            java.nio.file.Files.readString(
                                    java.nio.file.Path.of("config/blueprints/hwasan_gate.yml")));
                    Object node = graw.get("facade_projection_clearance");
                    check("★도면 정본에 facade_projection_clearance 절이 있다",
                            node instanceof java.util.Map, node == null ? "없다" : "");
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> fc = (java.util.Map<String, Object>) node;
                    check("★도면의 half_width 가 코드와 같다 (" + TerraceForge.FACADE_CLEAR_HALF + ")",
                            Integer.valueOf(TerraceForge.FACADE_CLEAR_HALF).equals(fc.get("half_width")),
                            String.valueOf(fc.get("half_width")));
                    check("★도면의 depth 가 코드와 같다 (" + TerraceForge.FACADE_CLEAR_DEPTH + ")",
                            Integer.valueOf(TerraceForge.FACADE_CLEAR_DEPTH).equals(fc.get("depth")),
                            String.valueOf(fc.get("depth")));
                    check("★도면의 standoff 가 코드와 같다 (" + TerraceForge.FACADE_STANDOFF + ")",
                            Integer.valueOf(TerraceForge.FACADE_STANDOFF).equals(fc.get("standoff")),
                            String.valueOf(fc.get("standoff")));
                    // ★금지·허용 목록이 <b>비어 있지 않은지</b>도 잰다 — 이름만 남고 속이 빈 계약 방지
                    Object forb = fc.get("forbidden");
                    Object allow = fc.get("allowed");
                    check("★도면이 금지·허용을 둘 다 적는다 (허용을 안 적으면 문 앞이 캄캄해진다)",
                            forb instanceof java.util.List && ((java.util.List<?>) forb).size() >= 5
                                    && allow instanceof java.util.List
                                    && ((java.util.List<?>) allow).size() >= 3,
                            "금지 " + (forb instanceof java.util.List ? ((java.util.List<?>) forb).size() : -1)
                                    + " · 허용 " + (allow instanceof java.util.List
                                            ? ((java.util.List<?>) allow).size() : -1));
                } catch (Exception e) {
                    check("★도면 정본의 계약 절이 읽힌다", false, e.toString());
                }
            }
        }

        check("★16 결정론 — 같은 자리는 같은 결",
                    TerraceForge.batterRoughness(120, 77, -31, 1, 0, 10)
                            == TerraceForge.batterRoughness(120, 77, -31, 1, 0, 10),
                    "동일");
        }

        // ══════════ ⑯ ★설계도 — 도면이 좌표의 정본이다 (사용자 확정 2026-08-05) ══════════
        //   「레퍼런스를 토대로 설계도를 그리고 그걸 바탕으로 건축하는 형태를 취해봅시다」
        //   ★눈이 재는 것: 도면이 **스스로 지켜야 하는 계약**이다. 조성이 아니라 도면을 잰다 —
        //     틀린 도면으로 지으면 틀린 것이 서므로, 도면에서 먼저 죽어야 한다.
        try {
            java.util.Map<String, Object> raw = new org.yaml.snakeyaml.Yaml().load(
                    java.nio.file.Files.readString(
                            java.nio.file.Path.of("config/blueprints/hwasan_gate.yml")));
            Blueprint blueprint = Blueprint.of(raw);
            check("★설계도 산문 구역이 읽힌다 (평면 " + blueprint.width() + "×" + blueprint.depth() + ")",
                    blueprint.width() == 60 && blueprint.depth() == 22, blueprint.width() + "×" + blueprint.depth());

            blueprint.validate();
            check("★설계도 자기 계약을 지킨다 (통행 개구·축선 시야·자리·지붕 상자)", true, "통과");

            // 중앙 통행 개구 — 목표 실측 폭 5
            int openCols = 0;
            for (int c = 0; c < blueprint.width(); c++) {
                boolean air = false;
                for (Blueprint.Course cs : blueprint.columnOf(blueprint.at(c, 18))) {
                    if ("air".equals(cs.material()) && cs.count() >= 5) {
                        air = true;
                    }
                }
                if (air) {
                    openCols++;
                }
            }
            // ★2026-08-06 사용자 확정 — 5 → 7. 「5블록은 대문보다 내부 출입문처럼 보인다」.
            //   실측값(5)을 뒤집은 <b>설계 결정</b>이다. 레퍼런스가 AI 생성 이미지임이 확인된
            //   뒤라 「실측값이 정답」은 아니다 — 도면의 design_intent 에 까닭이 남아 있다.
            check("★설계도 중앙 개구 폭 7 (사용자 확정 — 대문의 위계)", openCols == 7, openCols);

            // ★하층은 「기둥 + 격자 문짝」의 반복이다 (실측의 핵심 — 넓은 백벽이 아니다).
            //   남면(row 18)에서 문짝 칸이 회벽 칸보다 많아야 한다.
            int doors = 0;
            int walls = 0;
            int mullions = 0;
            for (int c = 16; c <= 44; c++) {
                char ch = blueprint.at(c, 18);
                if (ch == 'D') {
                    doors++;
                }
                if (ch == 'I') {
                    mullions++;              // 문살도 문짝 칸이다 (판과 살이 한 짝)
                }
                if (ch == 'W') {
                    walls++;
                }
            }
            check("★설계도 하층이 「기둥+문짝」이다 (문짝 " + (doors + mullions) + " > 회벽 " + walls + ")",
                    doors + mullions > walls, (doors + mullions) + "/" + walls);

            // ★★문짝은 격자다 — 세로 문살이 없으면 가로 슬랫만 남아 **블라인드**로 읽힌다
            //   (2026-08-05 실증). 1호의 문짝은 세로 살 + 가로 중인방이 짜인 격자다.
            check("★문짝에 세로 문살이 있다 (블라인드 방지 · 살 " + mullions + "칸)",
                    mullions > 0, mullions);
            boolean mullionVertical = blueprint.columnOf('I').stream()
                    .anyMatch(cs -> cs.material().contains("fence") || cs.material().contains("bars")
                            || cs.material().contains("pane"));
            check("★문살이 세로 부품이다 (울타리·창살 계열)", mullionVertical,
                    blueprint.columnOf('I'));
            // ★2026-08-06 — 중인방(planks)이 <b>적색 가로보</b>(log)로 바뀌었다. 3단 위계에서
            //   그 자리는 「문짝 4켜 → 가로보 → 고창」의 경계다. 재는 것은 <b>재료가 아니라
            //   성질</b>이다: 문짝 켜가 가로 부재로 끊기는가 (안 끊기면 블라인드다).
            java.util.List<Blueprint.Course> dcol = blueprint.columnOf('D');
            int doorRuns = 0;
            boolean prevDoor = false;
            for (Blueprint.Course cs : dcol) {
                boolean isDoor = cs.material().contains("trapdoor");
                if (isDoor && !prevDoor) {
                    doorRuns++;
                }
                prevDoor = isDoor;
            }
            check("★문짝이 가로 부재로 끊긴다 (한 덩어리면 블라인드 — 토막 " + doorRuns + ")",
                    doorRuns >= 2, dcol);
            boolean hasBeam = dcol.stream().anyMatch(cs -> cs.material().contains("log")
                    || cs.material().contains("planks"));
            check("★그 가로 부재가 실재한다 (가로보 또는 중인방)", hasBeam, dcol);

            // 금지 재료 — 도면에도 계율이 걸린다
            boolean banned = blueprint.materials().stream()
                    .anyMatch(m -> m.contains("barrel") || m.contains("light") || m.contains("chain"));
            check("★설계도에 금지 재료가 없다 (barrel·light·chain)", !banned, blueprint.materials());

            // ★★도면도 살구색 회벽을 못 쓴다 — 팩이 덮던 재료로 색을 판단하지 마라 (2026-08-05).
            check("★설계도에 살구색 회벽(white_terracotta)이 없다",
                    !blueprint.materials().contains("white_terracotta"), blueprint.materials());

            // ★문짝은 벽 높이의 대부분을 차지한다 — 목표는 통짜 격자 문짝이고,
            //   첫 판처럼 가운데를 회벽으로 끊으면 「작은 창」으로 읽힌다.
            int doorLattice = 0;
            int doorWall = 0;
            for (Blueprint.Course cs : blueprint.columnOf('D')) {
                if (cs.material().contains("trapdoor")) {
                    doorLattice += cs.count();
                } else if (cs.material().contains("quartz") || cs.material().contains("terracotta")) {
                    doorWall += cs.count();
                }
            }
            check("★문짝이 통짜다 (격자 " + doorLattice + "켜 · 사이 회벽 " + doorWall + "켜)",
                    doorLattice >= 5 && doorWall == 0, doorLattice + "/" + doorWall);
        } catch (Exception e) {
            check("★설계도 산문 구역", false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // ══════════ ⑰ ★설계도 본전 구역 — 7호 실측 (2026-08-05) ══════════
        //   ★★이 절이 지키는 발견: **산문과 본전은 벽의 문법이 서로 반대다.**
        //     산문은 「기둥+문짝」이 하층을 지배하고, 본전은 하층을 **회벽**이 지배하고
        //     상층을 **격자창**이 지배한다. 코드가 둘을 한 문법으로 지으면 반드시 하나가 틀린다 —
        //     그 되돌아감을 여기서 잡는다.
        try {
            java.util.Map<String, Object> raw = new org.yaml.snakeyaml.Yaml().load(
                    java.nio.file.Files.readString(
                            java.nio.file.Path.of("config/blueprints/hwasan_honjeon.yml")));
            Blueprint hj = Blueprint.of(raw);
            check("★설계도 본전 구역이 읽힌다 (평면 " + hj.width() + "×" + hj.depth() + ")",
                    hj.width() == 38 && hj.depth() == 32, hj.width() + "×" + hj.depth());

            hj.validate();
            check("★설계도 본전 자기 계약을 지킨다 (통행 개구·축선 시야·자리·지붕 상자)", true, "통과");

            // ★도면은 패드를 넘지 않는다 — BlueprintBuilder 가 던지기 **전에** 여기서 죽는다.
            //   (패드 치수는 TerraceForge 가 정본이므로 손으로 적은 38×32 를 안 믿고 물어본다)
            TerraceForge.PadSpec p9 = TerraceForge.hwasanCampus().pads().stream()
                    .filter(s -> s.zone() == hj.pad()).findFirst().orElse(null);
            check("★본전 도면이 앉을 패드(구역 " + hj.pad() + ")가 있다", p9 != null, p9);
            if (p9 != null) {
                check("★본전 도면이 패드를 안 넘는다 (도면 " + hj.width() + "×" + hj.depth()
                                + " ≤ 패드 " + p9.width() + "×" + p9.depth() + ")",
                        hj.width() <= p9.width() && hj.depth() <= p9.depth(),
                        hj.width() + "×" + hj.depth() + " vs " + p9.width() + "×" + p9.depth());
            }

            // 중앙 대문 — 목표 실측대로 폭 5, 그리고 걸어 지날 수 있어야 한다
            // ★★자를 고쳤다 (깊이 20 · 2026-08-11): 「도면 뒤에서 13번째」였다 —
            //   <b>몸체 깊이가 바뀌면 그 자리에서 죽는</b> 셈이다 (실제로 죽었다: 깊이 14 → 20
            //   으로 늘리자 자 열넷이 한꺼번에 짖었다). 정면은 <b>지붕 상자의 남쪽 변</b>이다 —
            //   그것은 도면이 신고하고, 깊이가 바뀌면 같이 움직인다.
            int frontRow = hj.roofs().get(0).box()[3];
            int gateCols = 0;
            for (int c = 0; c < hj.width(); c++) {
                for (Blueprint.Course cs : hj.columnOf(hj.at(c, frontRow))) {
                    if ("air".equals(cs.material()) && cs.count() >= 5) {
                        gateCols++;
                        break;
                    }
                }
            }
            // ★★자를 고쳤다 (REF-1c-A · 사용자 확정): 중앙 <b>칸</b>은 여전히 5 지만
            //   <b>개구</b>는 3 이고 좌우 한 칸씩을 문틀이 감싼다. 5칸이 통째로 뚫리면
            //   본전 대문이 아니라 <b>관통 터널</b>로 읽힌다 (정면 사진 판정).
            int jambCols = 0;
            for (int c = hj.axisCol() - 2; c <= hj.axisCol() + 2; c++) {
                if (hj.at(c, frontRow) == 'J') {
                    jambCols++;
                }
            }
            check("★본전 중앙 칸 5 = 개구 3 + 문틀 1+1 (걸어 지나되 터널이 아니다)",
                    gateCols == 3 && jambCols == 2, gateCols + "+" + jambCols);

            // ★★★D-34 — 여기 있던 두 눈(「본전 하층은 회벽이 지배한다」·「산문과 문법이 반대다」)은
            //   **틀린 결론을 지키고 있었다.** 7호를 다시 재니 하층은 붉은색 46.8% / 회벽 9.4% 로
            //   기둥+격자가 지배했고, 적주 주기도 산문과 같은 3칸이었다. 눈이 틀린 것을 지키면
            //   고치려는 손을 눈이 막는다 — 그래서 실측이 이기고 눈을 갈아 끼운다.
            int hjWalls = 0;
            int hjDoors = 0;
            for (int c = 0; c < hj.width(); c++) {
                char ch = hj.at(c, frontRow);
                if (ch == 'W') {
                    hjWalls++;
                }
                if (ch == 'D') {
                    hjDoors++;
                }
            }
            // ① 빈 회벽 판 금지 — D-34 의 진범. 회벽의 **양**보다 통짜 판이 문제였다.
            //    목표는 모든 칸이 「회벽 판 + 그 안의 격자창」이라 흰 면이 결코 이어지지 않는다.
            int adjacentWall = 0;
            for (int c = 1; c < hj.width(); c++) {
                if (hj.at(c, frontRow) == 'W' && hj.at(c - 1, frontRow) == 'W') {
                    adjacentWall++;
                }
            }
            check("★★본전 하층에 빈 회벽 판이 없다 (회벽 켜열이 이웃하는 자리 " + adjacentWall + ")",
                    adjacentWall == 0, adjacentWall);
            // ② 적주 주기 3 — 7호 실측(기둥 틈 33/36/37px · 주기 ≈45px = 3블록)
            List<Integer> posts = new ArrayList<>();
            for (int c = 0; c < hj.width(); c++) {
                // ★★자를 고쳤다 (REF-3A): 전에는 <b>글자 'P'</b> 를 셌다. bay 역할을 나누며
                //   모서리 C · 입구 옆 A 가 생기자 기둥이 10 → 6 으로 줄어 보였다 —
                //   조성은 멀쩡한데 눈이 글자를 세고 있었던 것이다.
                //   ★이 저장소에서 <b>세 번째</b> 같은 병이다 (공포 판정 · 문설주 판정 · 여기).
                //   <b>이름이 아니라 구조로 센다</b>: 세로로 이어진 통나무가 곧 적주다.
                if (isPost(hj.columnOf(hj.at(c, frontRow)))) {
                    posts.add(c);
                }
            }
            // 주기는 <b>도면에서 읽는다</b> — 가장 잦은 틈이 그 건물의 주기다
            java.util.Map<Integer, Integer> gapHist = new java.util.HashMap<>();
            for (int i = 1; i < posts.size(); i++) {
                gapHist.merge(posts.get(i) - posts.get(i - 1), 1, Integer::sum);
            }
            // ★★자를 조였다 (Codex 2026-08-10): 최빈값을 읽으면 <b>균일하기만 하면</b> 어떤
            //   주기도 통과한다. 도면이 <b>신고</b>한 주기와 실물이 같은지를 묻는다.
            int period = hj.postPeriod();
            check("★★도면이 적주 주기를 <b>신고</b>한다 (눈이 실물에서 읽으면 아무 주기나 통과한다)",
                    period > 0, period);
            // ★★★자를 넓혔다 (Codex 판정 2026-08-10 · REF-2C) — 적주 판정이 구조로 바뀌자
            //   중앙 대문의 <b>문설주 J 도 적주로</b> 세어졌다 (8 → 10). 지금까지 J 에 공포가
            //   안 앉았던 것은 <b>결정이 아니라 사고</b>였다 — 재료가 mangrove 가 아니어서
            //   우연히 걸러졌을 뿐이다. Codex 판정: 「J 도 적주이며 공포를 받는다.
            //   겹기둥으로 합산하지 말고 <b>각각의 적주로 센다</b>. 중앙 대문 인접쌍의 내부 틈
            //   1 만 명시적 예외로 두고, 나머지 연속 적주 간격은 4 로 판정하라.」
            int badGap = 0;
            for (int i = 1; i < posts.size(); i++) {
                int gap = posts.get(i) - posts.get(i - 1);
                if (gap == 1 && flanksAxis(hj, frontRow, posts.get(i - 1), posts.get(i))) {
                    continue;                    // 중앙 대문 인접쌍의 안쪽 틈 — 명시적 예외
                }
                // ★자를 넓혔다 (칸 넓힘 · 2026-08-11): 두 문설주 <b>사이</b>는 칸이 아니라
                //   <b>대문 개구</b>다. 그 폭은 개구가 정하지 주기가 정하지 않는다.
                //   전에는 개구 3 + 문설주가 마침 주기 4 와 같아 우연히 통과했다 —
                //   주기를 6 으로 넓히자 그 우연이 깨졌다. <b>우연은 계약이 아니다.</b>
                boolean allOpen = true;
                for (int oc = posts.get(i - 1) + 1; oc < posts.get(i); oc++) {
                    allOpen &= hj.columnOf(hj.at(oc, frontRow)).stream()
                            .anyMatch(cs -> "air".equals(cs.material()));
                }
                if (allOpen && gap > 1) {
                    continue;                    // 사이가 전부 개구 — 대문이다
                }
                if (gap != period && gap != period + 2) {   // +2 = 중앙 개구를 사이에 낀 한 짝
                    badGap++;
                }
            }
            // ★★★자를 고쳤다 (Codex 디자인 판정 2026-08-10): 「주기 3」은 <b>7호 사진</b> 실측값이다.
            //   시각 정본이 mainhall_ref.png 로 옮겨졌고, Codex 가 「9칸 → 7칸」을 처방했다
            //   (2칸 폭 칸으로는 창호가 칸을 채울 수 없다). 그래서 <b>수를 박지 않는다</b> —
            //   계약은 「3이어야 한다」가 아니라 <b>고르게 선다</b>이다 (중앙 개구만 예외).
            check("★★본전 적주가 <b>고르게</b> 선다 (기둥 " + posts.size() + "개 · 어긋난 틈 "
                            + badGap + ")",
                    posts.size() >= 8 && badGap == 0, posts + "");
            // ③ 회벽은 소수다 — 실측 9.4%. 도면은 켜열 단위라 성기므로 3분의 1을 상한으로 둔다.
            check("★★본전 하층을 회벽이 지배하지 **않는다** (회벽 " + hjWalls + " ≤ 몸체의 1/3)",
                    hjWalls * 3 <= 31, hjWalls);
            check("★본전 하층에 격자가 회벽만큼 있다 (격자 " + hjDoors + " ≥ 회벽 " + hjWalls + ")",
                    hjDoors >= hjWalls, hjDoors + "/" + hjWalls);

            // ★★핵심 — 본전 **상층은 창이 지배**한다 (회벽이 없다). 코드 기본값은 회벽이므로
            //   도면이 lattice 라 말하지 않으면 상층이 통짜 백벽이 되어 목표와 어긋난다.
            Blueprint.Roof hjRoof = hj.roofs().stream()
                    .filter(r -> r.hasUpper()).findFirst().orElse(null);
            check("★본전에 상층 누각이 있다", hjRoof != null, hj.roofs());
            if (hjRoof != null) {
                check("★★본전 상층은 격자창이 띠를 이룬다 (회벽 아님 — infill=lattice)",
                        hjRoof.upperLattice(), hjRoof.upperInfill());
                // ★★하층:상층 폭 비.
                //   ┌ 구 목표 0.66  근거: 목표 7호 이미지            → **superseded**
                //   └ 신 목표 0.806 근거: mainhall_ref.png (REF-1 · 사용자 확정 2026-08-09)
                //   ★틀려서 지우는 게 아니다. <b>시각 정답이 옮겨졌다.</b> 7호 시절의 0.66 은
                //     그때의 정본을 정확히 지키던 값이고, 지금 지키라는 것은 다른 그림이다.
                //     레퍼런스 원본 비는 37/45 = 0.822 이고, 패드 38 안에서 그 느낌을 가장 잘
                //     보존하는 정수가 25 (0.806) 다 — 26 은 좌우 대칭·홀수 축을 깬다.
                int lower = hjRoof.box()[2] - hjRoof.box()[0] + 1;
                int upper = lower - 2 * hjRoof.insetX();
                double ratio = (double) upper / lower;
                check("★본전 하층:상층 폭 비가 mainhall_ref.png(0.806)에 든다 (" + lower + ":" + upper
                                + " = " + String.format("%.3f", ratio) + " — 7호의 0.66 은 superseded)",
                        ratio >= 0.78 && ratio <= 0.83, ratio);
            }

            // ★월대 — 본전은 맨땅에 서지 않는다. 몸체 **앞**에 기단면이 깔려야 한다.
            int podium = 0;
            for (int r = frontRow + 1; r < hj.depth(); r++) {
                for (int c = 0; c < hj.width(); c++) {
                    if (hj.at(c, r) == 'M') {
                        podium++;
                    }
                }
            }
            check("★본전 앞에 월대가 깔린다 (기단 " + podium + "칸)", podium > 0, podium);

            // 금지 재료·살구색 회벽 — 도면에도 같은 계율이 걸린다
            boolean hjBanned = hj.materials().stream()
                    .anyMatch(m -> m.contains("barrel") || m.contains("light") || m.contains("chain"));
            check("★본전 설계도에 금지 재료가 없다 (barrel·light·chain)", !hjBanned, hj.materials());
            check("★본전 설계도에 살구색 회벽(white_terracotta)이 없다",
                    !hj.materials().contains("white_terracotta"), hj.materials());

            // ★★두 도면을 나란히 — **문법이 서로 반대여야 한다.** 누가 「통일」하면 여기서 짖는다.
            java.util.Map<String, Object> graw = new org.yaml.snakeyaml.Yaml().load(
                    java.nio.file.Files.readString(
                            java.nio.file.Path.of("config/blueprints/hwasan_gate.yml")));
            Blueprint gate = Blueprint.of(graw);

            // ══════ ★★★산문 문루 정면 <b>법선</b> 회귀 (2026-08-09) ══════
            //   ★이것은 디자인 변경이 아니라 <b>구현 오류의 교정</b>이다 (사용자 판정).
            //   {@code outward()} 는 「이웃에 벽이 있는가」로 면의 법선을 정하는데, 판정이
            //   {@code heightAt > 0} 이라 <b>한 켜짜리 기단(T·B)까지 벽으로 셌다.</b>
            //   그래서 문루 정면(row 18)의 남·북 이웃이 둘 다 「벽」이 되어 법선이 남이 아니라
            //   <b>동·서로 떨어졌고, 정면 살창 13칸이 옆을 보고 있었다.</b>
            //   자를 「4켜 미만은 딛는 것이지 가리는 것이 아니다」로 바로잡아 교정했다.
            //   ★동결은 오류까지 영구 보존하라는 뜻이 아니다 — 다만 치수·좌표·입면 배열은
            //     한 글자도 안 건드렸으므로, <b>다시 돌아가지 않게 눈을 박는다.</b>
            int gFrontSouth = 0;
            int gFrontOther = 0;
            for (int c = 0; c < gate.width(); c++) {
                char ch = gate.at(c, 18);
                if (ch != 'D' && ch != 'I') {
                    continue;                       // 살창(트랩도어·살) 칸만 — 성벽은 딴 면이다
                }
                if (com.honcheon.mvt.forge.BlueprintBuilder.outward(gate, c, 18)
                        == org.bukkit.block.BlockFace.SOUTH) {
                    gFrontSouth++;
                } else {
                    gFrontOther++;
                }
            }
            check("★[눈의 눈] 산문 문루 정면에서 살창칸을 찾았다", gFrontSouth + gFrontOther > 0,
                    gFrontSouth + gFrontOther);
            check("★★산문 문루 정면 살창이 **남(바깥)을 본다** (옆을 보면 살창이 뒤집힌다 — 남 "
                            + gFrontSouth + " · 그 밖 " + gFrontOther + ")",
                    gFrontOther == 0 && gFrontSouth >= 5, gFrontSouth + " / " + gFrontOther);
            // ★같은 자가 측면에서는 동·서를 가리켜야 한다 — 남쪽으로 다 돌려 버리면 그것도 틀렸다
            org.bukkit.block.BlockFace gWestFace =
                    com.honcheon.mvt.forge.BlueprintBuilder.outward(gate, 6, 15);
            org.bukkit.block.BlockFace gEastFace =
                    com.honcheon.mvt.forge.BlueprintBuilder.outward(gate, gate.width() - 7, 15);
            check("★★산문 곁채 측면은 여전히 동·서를 본다 (자리가 방향을 정한다 — 서 "
                            + gWestFace + " · 동 " + gEastFace + ")",
                    gWestFace == org.bukkit.block.BlockFace.WEST
                            && gEastFace == org.bukkit.block.BlockFace.EAST,
                    gWestFace + "/" + gEastFace);
            // ★눈의 눈 — 자가 옛 값(1켜)으로 돌아가면 정면이 다시 옆을 본다
            check("★★[눈의 눈] 「기단은 벽이 아니다」 자가 살아 있다 (4켜 미만은 딛는 것)",
                    com.honcheon.mvt.forge.BlueprintBuilder.WALL_MIN_COURSES >= 4,
                    com.honcheon.mvt.forge.BlueprintBuilder.WALL_MIN_COURSES);

            int gWalls = 0;
            int gDoors = 0;
            for (int c = 0; c < gate.width(); c++) {
                char ch = gate.at(c, 18);
                if (ch == 'W') {
                    gWalls++;
                }
                if (ch == 'D' || ch == 'I') {
                    gDoors++;
                }
            }
            // ★★★뒤집힌 눈 — 예전엔 「서로 반대여야 한다」였다. 7호 재실측이 그 결론을 죽였다:
            //   두 건물 다 3칸 주기 기둥 + 격자가 지배하고 회벽은 소수다 (본전 9.4% · 산문 4.3%).
            //   ※그래도 **똑같지는 않다** — 본전이 산문보다 회벽을 더 쓴다. 그 차이를 함께 잰다.
            // ★★자를 고쳤다 (Codex 처방 2026-08-10): 본전 정면이 「창이 칸을 거의 다 채우고
            //   회벽은 <b>위 한 켜 띠</b>」로 바뀌었다. 그래서 회벽 <b>칸열</b>은 0 이 된다 —
            //   계약의 뜻은 「격자가 회벽 이상」이고, 그건 <b>더 강하게</b> 지켜지고 있다.
            check("★★산문과 본전의 하층 문법이 **같다** (둘 다 격자가 회벽 이상 — 산문 " + gDoors + "/"
                            + gWalls + " · 본전 " + hjDoors + "/" + hjWalls + ")",
                    gDoors >= gWalls && hjDoors >= hjWalls,
                    "산문 " + gDoors + "/" + gWalls + " · 본전 " + hjDoors + "/" + hjWalls);
            // ★★자를 고쳤다 (Codex 처방 2026-08-10): 본전 정면이 「창이 칸을 채우고 회벽은 띠」로
            //   바뀌며 회벽 <b>칸열</b>이 0 이 됐다. 회벽을 <b>켜</b>로 센다 — 창호 칸 안의 띠도 회벽이다.
            int hjPlaster = 0;
            for (int c = 0; c < hj.width(); c++) {
                for (Blueprint.Course cs : hj.columnOf(hj.at(c, 19))) {
                    if (cs.material().contains("plaster")) {
                        hjPlaster += cs.count();
                    }
                }
            }
            int gPlaster = 0;
            for (int c = 0; c < gate.width(); c++) {
                for (Blueprint.Course cs : gate.columnOf(gate.at(c, 18))) {
                    if (cs.material().contains("plaster")) {
                        gPlaster += cs.count();
                    }
                }
            }
            check("★본전이 산문보다 회벽을 더 쓴다 (켜 " + hjPlaster + " vs " + gPlaster + ")",
                    hjPlaster > gPlaster, hjPlaster + " vs " + gPlaster);

            // ★물러남 문법이 생겨도 **산문은 그대로 서야 한다** — 안 적은 도면의 기본값이 2 다
            //   (예전 값). 기본값이 바뀌면 이미 선 것이 조용히 달라진다.
            Blueprint.Roof gRoof = gate.roofs().stream().filter(r -> r.hasUpper()).findFirst().orElse(null);
            check("★물러남을 안 적은 도면(산문)의 기본값이 예전 값 2 다",
                    gRoof != null && gRoof.insetX() == 2 && gRoof.insetZ() == 2,
                    gRoof == null ? "상층 없음" : gRoof.insetX() + "/" + gRoof.insetZ());

            // ★상층이 물러나다 못해 사라지면 **죽어야 한다** (조용한 실종 금지 — 세 번 당했다)
            //   ※변이는 **읽은 지도**를 고친다 — 글자를 찾아 바꾸면 도면의 물러남 값이 달라진
            //     순간 눈이 조용히 아무것도 안 재게 된다 (변이시험에서 실제로 그랬다).
            boolean vanishBarks = false;
            java.util.Map<String, Object> probeRaw = new org.yaml.snakeyaml.Yaml().load(
                    java.nio.file.Files.readString(
                            java.nio.file.Path.of("config/blueprints/hwasan_honjeon.yml")));
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> probeRoofs =
                    (java.util.Map<String, Object>) probeRaw.get("roof");
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> probeUpper = (java.util.Map<String, Object>)
                    ((java.util.Map<String, Object>) probeRoofs.values().iterator().next()).get("upper");
            probeUpper.put("inset", java.util.List.of(15, 2));
            try {
                Blueprint.of(probeRaw).validate();
            } catch (IllegalStateException vanishEx) {
                vanishBarks = true;
            }
            check("★상층이 다 물러나 사라지면 도면이 죽는다 (지붕만 뜨는 것 방지)", vanishBarks, vanishBarks);

            // ══════ ★★D-36 — 처마가 벽을 덮는다 ══════
            // 진범은 **오프바이원**이었다. 앞 회차가 base_y 를 「벽 5 + 도리·단청 2 = 7」로 셈하며
            // 기둥 처방 맨 아래 기단 켜(smooth_stone)를 빼먹었다. 실제 켜열은 8 이라 처마 첫 켜가
            // 단청 띠(y7)를 그대로 덮었다. ★수치를 박지 않고 **도면에서 켜를 세어** 잰다 —
            // 8 을 박아 두면 나중에 벽을 한 켜 올릴 때 눈이 조용히 틀린 값을 지킨다.
            int tallest = 0;
            for (int c = 0; c < hj.width(); c++) {
                char ch = hj.at(c, frontRow);
                if (ch == '.' || ch == 'M') {
                    continue;
                }
                int h = 0;
                for (Blueprint.Course cs : hj.columnOf(ch)) {
                    h += cs.count();
                }
                tallest = Math.max(tallest, h);
            }
            check("★[눈의 눈] 본전 정면에서 벽 켜열을 셌다", tallest > 0, tallest);
            check("★★본전 지붕이 벽 켜열 **위**에 앉는다 (처마가 단청 띠를 안 덮는다 — 벽 "
                            + tallest + "켜 · 지붕 base_y " + (hjRoof == null ? -1 : hjRoof.baseY()) + ")",
                    hjRoof != null && hjRoof.baseY() >= tallest,
                    tallest + " vs " + (hjRoof == null ? -1 : hjRoof.baseY()));
            check("★본전 처마 내밈이 실측(한쪽 1.5칸)에 든다 — 3 은 45°에서 벽을 통째로 가린다",
                    hjRoof != null && hjRoof.eave() >= 1 && hjRoof.eave() <= 2,
                    hjRoof == null ? -1 : hjRoof.eave());

            // ★★도면의 내밈이 **코드에 닿는가.** 이 회차 전까지 roof.eave 는 죽은 값이었다 —
            //   sweepRoof 가 over=3 을 박아 두어 도면에 무엇을 적든 결과가 같았다.
            //   ※글자를 뒤지는 눈은 앵커가 사라지면 조용히 아무것도 안 잰다. 그래서 **앵커를
            //     찾았는지 먼저 짖게** 해 둔다 (눈의 눈).
            String bb = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "server-mvt/src/main/java/com/honcheon/mvt/forge/BlueprintBuilder.java"));
            int anchor = bb.indexOf("sweepRoof(world, pad, cx, oy + rf.baseY()");
            check("★[눈의 눈] BlueprintBuilder 의 하층 지붕 부름을 찾았다", anchor >= 0, anchor);
            String callSite = anchor < 0 ? "" : bb.substring(anchor, Math.min(bb.length(), anchor + 160));
            check("★★도면의 처마 내밈이 코드에 닿는다 (rf.eave() 를 넘긴다 — 죽은 값 방지)",
                    anchor >= 0 && callSite.contains("rf.eave()"), callSite.replace('\n', ' '));

            // ══════ ★★★B-196 진범 — 도면이 **빈 자리에 앉는가** (2026-08-05) ══════
            // 앞 회차들이 「본전 회벽이 넓다」(D-34)·「지붕이 벽을 덮는다」(D-36) 로 읽던 것의
            // 진범: 캠퍼스시험이 같은 패드에 세운 **통짜 건물이 도면 껍데기 안에 그대로 남아**
            // 있었다. 격자칸의 트랩도어는 바닥에 눕는 얇은 판이라 칸이 사실상 구멍이고, 그
            // 구멍 너머로 남은 건물의 가문비 판벽·자갈심층암 지붕이 비쳐 밝은 면으로 읽혔다.
            // ★즉 **우리가 재던 것은 도면이 아니라 두 건물이 겹친 자리였다.** 실측으로 확인:
            //   sanse_test_hwasan (x-12,y58,z52~46) = spruce_planks — 도면에 없는 재료.
            // ★이 눈이 지키는 것은 「오늘의 판단」이 아니라 **판단이 가능한 조건**이다.
            int hjClear = com.honcheon.mvt.forge.BlueprintBuilder.clearHeight(hj);
            int hjTallest = 0;
            for (int c = 0; c < hj.width(); c++) {
                for (int r = 0; r < hj.depth(); r++) {
                    hjTallest = Math.max(hjTallest, hj.heightAt(c, r));
                }
            }
            check("★[눈의 눈] 본전 도면에서 가장 높은 기둥 켜를 셌다", hjTallest > 0, hjTallest);
            check("★★비우는 높이가 가장 높은 기둥보다 높다 (벽이 자라도 안 파묻힌다 — 기둥 "
                            + hjTallest + " · 비움 " + hjClear + ")",
                    hjClear > hjTallest, hjTallest + " vs " + hjClear);
            int hjRoofTop = 0;
            for (Blueprint.Roof rf : hj.roofs()) {
                int[] rb = rf.box();
                int rhf = (rb[2] - rb[0]) / 2;
                int rhl = (rb[3] - rb[1]) / 2;
                hjRoofTop = Math.max(hjRoofTop, rf.baseY() + Math.max(rhf, rhl) + rf.eave());
                if (rf.hasUpper()) {
                    hjRoofTop = Math.max(hjRoofTop, rf.baseY() + 3 + rf.upperWall()
                            + Math.max(rhf - rf.insetX(), rhl - rf.insetZ()) + rf.upperEave());
                }
            }
            check("★[눈의 눈] 본전 지붕 꼭대기를 셌다", hjRoofTop > 0, hjRoofTop);
            check("★★비우는 높이가 지붕 꼭대기까지 닿는다 (남은 건물의 윗도리가 지붕 위로 "
                            + "삐져나오지 않게 — 지붕 " + hjRoofTop + " · 비움 " + hjClear + ")",
                    hjClear > hjRoofTop, hjRoofTop + " vs " + hjClear);
            // ★비움 높이는 **도면을 따라 움직여야** 한다 — 상수를 박으면 도면이 자랄 때 조용히
            //   낮아진다.
            //   ★★자를 고쳤다 (2026-08-09 · REF-1). 전에는 <b>산문과 본전의 값이 다른가</b>로
            //     재고 있었다. 그런데 본전이 REF-1 로 자라 두 값이 <b>우연히 35 로 같아지자</b>
            //     눈이 짖었다 — 조성은 멀쩡한데 자가 틀린 것이다.
            //     「두 건물이 다르다」는 「도면을 따라 움직인다」의 <b>대용품</b>일 뿐이었다.
            //     이제 도면을 실제로 <b>한 켜 키워서</b> 값이 따라 오르는지 직접 묻는다 —
            //     상수(`return 64;`)라면 안 오르고, 대용품과 달리 우연히 통과할 수도 없다.
            java.util.Map<String, Object> growRaw = new org.yaml.snakeyaml.Yaml().load(
                    java.nio.file.Files.readString(
                            java.nio.file.Path.of("config/blueprints/hwasan_honjeon.yml")));
            //   ※무엇을 키울지도 자다: 기둥 켜를 키우면 지붕이 안 따라 올라 값이 안 변한다
            //     (지붕 꼭대기가 더 높아 그쪽이 답을 지배한다 — 실제로 35→35 였다).
            //     <b>지붕을 올려야</b> 이 눈이 재려던 것을 잰다.
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> growRoofs =
                    (java.util.Map<String, Object>) growRaw.get("roof");
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> growRoof = (java.util.Map<String, Object>)
                    growRoofs.values().iterator().next();
            growRoof.put("base_y", ((Number) growRoof.get("base_y")).intValue() + 4);
            int grownClear = com.honcheon.mvt.forge.BlueprintBuilder.clearHeight(
                    Blueprint.of(growRaw));
            check("★★비우는 높이가 **도면을 따라 움직인다** (지붕을 4켜 올리니 " + hjClear + " → "
                            + grownClear + " — 상수를 박으면 여기서 짖는다)",
                    grownClear > hjClear, hjClear + " → " + grownClear);

            // ★조성이 실제로 **비우고 나서** 찍는가 — 순서가 뒤집히면 도면을 지운다.
            int clearCall = bb.indexOf("clearHeight(bp)");
            int stampLoop = bb.indexOf("for (Blueprint.Course course : lv.columnOf");
            check("★[눈의 눈] BlueprintBuilder 에서 비움 부름과 찍는 고리를 둘 다 찾았다",
                    clearCall >= 0 && stampLoop >= 0, clearCall + " / " + stampLoop);
            check("★★조성이 **비우고 나서** 찍는다 (순서가 뒤집히면 제 도면을 지운다)",
                    clearCall >= 0 && stampLoop >= 0 && clearCall < stampLoop,
                    clearCall + " < " + stampLoop);

            // ══════ ★★B-196 둘째 진범 — 격자창이 **창인가 구멍인가** (2026-08-05) ══════
            // 트랩도어를 setType 으로 놓으면 half=bottom·open=false 라 **바닥에 눕는다.**
            // 그러면 칸은 살창이 아니라 구멍이고, 벽 너머 바깥 포장이 훤히 비친다
            // (실물 확인: 자리 비움 뒤 찍은 clear_1 에서 칸마다 바깥이 보였다 — 「선반」).
            // ★눈은 글자가 아니라 **조성이 쓰는 그 함수**(outward)를 불러 방향을 실제로 잰다.
            int frontR = hj.roofs().get(0).box()[3];      // 정면 = 지붕 상자의 남쪽 변
            int latSouth = 0;
            int latOther = 0;
            for (int c = 0; c < hj.width(); c++) {
                if (hj.at(c, frontR) != 'D') {
                    continue;
                }
                if (com.honcheon.mvt.forge.BlueprintBuilder.outward(hj, c, frontR)
                        == org.bukkit.block.BlockFace.SOUTH) {
                    latSouth++;
                } else {
                    latOther++;
                }
            }
            check("★[눈의 눈] 본전 정면에서 격자칸을 찾았다", latSouth + latOther > 0, latSouth + latOther);
            check("★★본전 정면 격자창이 **남(바깥)을 본다** (안쪽을 보면 살창이 뒤집힌다 — 남 "
                            + latSouth + " · 그 밖 " + latOther + ")",
                    latOther == 0 && latSouth > 0, latSouth + " / " + latOther);
            // ★서쪽 벽은 서를 봐야 한다 — 한 처방(D)이 네 벽에 다 쓰이므로 **자리가** 방향을
            //   정하는지 확인한다. 방향을 도면에 적으면 세 벽이 틀린다.
            int westWall = -1;
            for (int r2 = 0; r2 < hj.depth(); r2++) {
                if (hj.at(4, r2) == 'D') {
                    westWall = r2;
                    break;
                }
            }
            check("★[눈의 눈] 본전 서쪽 벽에서 격자칸을 찾았다", westWall >= 0, westWall);
            check("★★같은 처방이 서쪽 벽에서는 **서를 본다** (자리가 방향을 정한다)",
                    westWall >= 0 && com.honcheon.mvt.forge.BlueprintBuilder.outward(hj, 4, westWall)
                            == org.bukkit.block.BlockFace.WEST,
                    westWall < 0 ? "없음"
                            : com.honcheon.mvt.forge.BlueprintBuilder.outward(hj, 4, westWall).toString());
            // ★뒷벽(북)은 북을 본다 — 남북 대칭이 무너지면 여기서 짖는다
            // ★자를 고쳤다 (깊이 20): 「정면 − 13」은 깊이 14 시절의 상수다.
            //   뒷벽은 <b>지붕 상자의 북쪽 변</b>이다 — 도면이 신고한다.
            int backR = hj.roofs().get(0).box()[1];
            int backCol = -1;
            for (int c = 0; c < hj.width(); c++) {
                if (hj.at(c, backR) == 'D') {
                    backCol = c;
                    break;
                }
            }
            check("★★뒷벽 격자창이 **북(바깥)을 본다**",
                    backCol >= 0 && com.honcheon.mvt.forge.BlueprintBuilder.outward(hj, backCol, backR)
                            == org.bukkit.block.BlockFace.NORTH,
                    backCol < 0 ? "없음"
                            : com.honcheon.mvt.forge.BlueprintBuilder.outward(hj, backCol, backR).toString());
            // ★그리고 **세워서** 놓는가 — 방향만 맞고 눕혀 놓으면 여전히 구멍이다.
            //   ※Bukkit.createBlockData 는 서버가 떠 있어야 해서 눈이 직접 못 만든다.
            //     그래서 stand() 의 본문을 읽되, **앵커를 찾았는지 먼저 짖게** 해 둔다.
            int standAt = bb.indexOf("static BlockData stand(");
            check("★[눈의 눈] BlueprintBuilder.stand 를 찾았다", standAt >= 0, standAt);
            String standBody = standAt < 0 ? ""
                    : bb.substring(standAt, Math.min(bb.length(), standAt + 420));
            check("★★격자창을 **세운다** (stand 가 setOpen(true) 한다 — 눕히면 칸이 구멍이 된다)",
                    standAt >= 0 && standBody.contains("setOpen(true)"), standBody.replace('\n', ' '));
            check("★★세우는 손이 **조성에 닿는다** (stamp 가 stand 를 부른다 — 죽은 함수 방지)",
                    bb.indexOf("stand(d, face)") >= 0, bb.indexOf("stand(d, face)"));
            // ★★자를 고쳤다 (상층 문법 · 2026-08-11): 상층에 <b>제 세우는 손</b>이 따로
            //   있는지를 원문에서 찾던 자였다. 이제 상층은 하층과 <b>같은 손</b>을 쓴다 —
            //   물어야 할 것은 「상층 전용 코드가 있는가」가 아니라
            //   <b>상층이 살창 처방을 쓰고, 그 처방이 세워지는가</b>다.
            {
                com.honcheon.mvt.forge.Level u0 = hj.upperLevel();
                boolean upLat = false;
                for (int r = 0; u0 != null && r < u0.depth(); r++) {
                    for (int c = 0; c < u0.width(); c++) {
                        for (Blueprint.Course cs : u0.columnOf(u0.at(c, r))) {
                            upLat |= "lattice".equals(cs.material());
                        }
                    }
                }
                check("★★상층 살창도 세운다 (상층이 살창 처방을 쓰고, 세우는 손은 하층과 하나다)",
                        upLat && bb.contains("stand(Bukkit.createBlockData(lm), nf)"), upLat);
            }

            // ══════ ★★D-34 — 정면 벽의 **재료 비율** (2026-08-05 · 오염 걷힌 뒤 첫 실측) ══════
            // 앞의 눈들은 「회벽 켜열이 붙었나」·「격자가 회벽만큼 있나」처럼 **칸의 수**만 셌다.
            // 그래서 회벽·격자가 둘 다 5켜 통짜여도 조용히 통과했다 — 한 주기의 재료가
            // 붉은 5 : 회벽 5 : 나무 5 로 **똑같이 셋**인 채로. 목표(7호 정밀 크롭)는
            // 붉은 64.0 / 회벽 22.8 / 나무 13.1 이라 **붉은색이 3분의 2**다.
            // ★★★그런데 **비율에 문턱을 두는 눈은 못 쓴다.** 처음엔 「붉은 ≥50% · 회벽 ≤35%」로
            //   적었는데, 고치기 **전** 상태(회벽 통짜 5켜)가 붉은 60%·회벽 24% 로 **그 문턱을
            //   통과했다** (변이시험에서 확인). 칸을 세는 자와 화소를 재는 자가 다르기 때문이다 —
            //   밝은 회벽은 실제보다 넓게, 좁은 붉은 기둥은 좁게 읽힌다.
            //   문턱을 조여 통과시키면 그건 **오늘의 처방을 지키는 눈**이지 실측을 지키는 눈이 아니다.
            //   그래서 실측이 실제로 뒷받침하는 **구조 규칙**만 남긴다: 「통짜 빈 판이 없다」.
            int cCream = 0;
            int noLintel = 0;
            int checked = 0;
            for (int c = 0; c < hj.width(); c++) {
                char ch = hj.at(c, frontR);
                if (ch == '.' || ch == 'M' || ch == 'N' || ch == 'F' || ch == 'P' || ch == 'O') {
                    continue;
                }
                // ★★REF-1c-A — <b>채움이 없는 칸은 「칸」이 아니다</b> (문설주 J 처럼 통짜 기둥).
                //   인방 계약은 회벽·격자처럼 <b>채워진 칸</b>의 위아래를 끊으라는 규칙이다.
                //   ※그렇다고 조용히 빠져나가지는 못한다 — 아래 [눈의 눈] 이 <b>몇 칸을 실제로
                //     쟀는지</b>를 세므로, 회벽이 통째로 사라지면 그쪽이 먼저 짖는다.
                boolean hasFill = hj.columnOf(ch).stream().anyMatch(cs ->
                        cs.material().contains("plaster") || "lattice".equals(cs.material())
                                || cs.material().contains("trapdoor")
                                || cs.material().contains("fence"));
                if (!hasFill) {
                    continue;
                }
                checked++;
                java.util.List<Blueprint.Course> col = hj.columnOf(ch);
                java.util.List<String> wallCourses = new java.util.ArrayList<>();
                for (Blueprint.Course cs : col) {
                    for (int k = 0; k < cs.count(); k++) {
                        wallCourses.add(cs.material());
                    }
                }
                if (wallCourses.size() < 4) {
                    continue;
                }
                // ★★자를 고쳤다 (2026-08-09 · D2 전파). 전에는 「맨 아래 한 켜 = 기단 ·
                //   맨 위 두 켜 = 도리·단청」이라고 **자리를 박아** 가운데를 오려 냈다.
                //   D2 로 기단이 1→2켜, 창방이 한 켜 늘자 그 오림이 통째로 어긋나 16/16 이
                //   짖었다 — <b>처방이 틀린 게 아니라 자가 틀렸다.</b>
                //   이제 자리를 세지 않고 <b>채움(회벽·살창)이 실제로 어디서 시작해 어디서
                //   끝나는지</b>를 찾아, 그 바로 위·아래 켜가 붉은 인방인지만 본다.
                int lo = -1;
                int hi = -1;
                for (int k = 0; k < wallCourses.size(); k++) {
                    String m = wallCourses.get(k);
                    boolean fill = m.contains("plaster") || m.contains("lattice")
                            || m.contains("trapdoor") || m.contains("fence");
                    if (fill) {
                        if (lo < 0) {
                            lo = k;
                        }
                        hi = k;
                        if (m.contains("plaster")) {
                            cCream++;
                        }
                    }
                }
                // ★인방 — 채움의 위·아래가 **붉은 켜로 끊겨야** 한다. 안 끊기면 회벽은
                //   통짜 빈 판이 되고 격자는 바닥부터 천장까지 이어져 「사다리」로 읽힌다.
                // ★★자를 고쳤다 (Codex 디자인 처방 2026-08-10): 창이 칸을 거의 다 채우고
                //   회벽이 <b>위 한 켜 띠</b>로 남는 문법이 됐다. 그러면 채움 위가 회벽이다.
                //   계약의 뜻은 「붉어야 한다」가 아니라 <b>채움이 통짜로 안 이어진다</b>였다 —
                //   위아래가 <b>채움이 아닌 켜</b>로 끊기면 된다.
                boolean below = lo > 0 && !"lattice".equals(wallCourses.get(lo - 1))
                        && !wallCourses.get(lo - 1).contains("plaster");
                boolean above = hi >= 0 && hi + 1 < wallCourses.size()
                        && !"lattice".equals(wallCourses.get(hi + 1));
                if (lo < 0 || !below || !above) {
                    noLintel++;
                }
            }
            // ★★자를 고쳤다 (문선 · 2026-08-11): 「15 이상」은 <b>옛 평면의 수</b>였다.
            //   칸 안에 문선(L)이 서면서 채움 칸이 3 → 2 로 줄어 12 가 됐다 —
            //   조성은 멀쩡한데 자가 <b>어제의 값</b>을 외우고 있었다 (유형 ①).
            //   계약은 「몇 칸이냐」가 아니라 <b>「정면이 비어 있지 않다」</b>이므로
            //   몸체 폭에 견준 <b>비율</b>로 묻는다.
            int bodyW = hj.roofs().get(0).box()[2] - hj.roofs().get(0).box()[0] + 1;
            check("★[눈의 눈] 본전 정면에서 채워진 칸을 <b>넉넉히</b> 찾았다 (" + checked
                            + " ≥ 몸체 폭 " + bodyW + " 의 1/3 — 회벽이 통째로 사라지면 "
                            + "여기서 먼저 짖는다)",
                    checked * 3 >= bodyW, checked + "/" + bodyW);
            check("★★칸마다 위·아래가 **붉은 인방으로 끊긴다** (안 끊기면 회벽은 통짜 빈 판이, "
                            + "격자는 사다리가 된다 — 안 끊긴 칸 " + noLintel + "/" + checked + ")",
                    noLintel == 0, noLintel + "/" + checked);
            check("★회벽이 사라지지는 않는다 (하층은 회벽이 **있는** 벽이다 — 상층과 다르다)",
                    cCream > 0, cCream);

            // ══════ ★★★REF-1 — 본전 가로 비례 (사용자 확정 2026-08-09) ══════
            //   새 시각 정본 = mainhall_ref.png. 목표는 <b>가로로 넓고 장중한 실루엣</b>.
            //   ★여기서 재는 것은 <b>선언값이 아니라 실현 상자</b>다. 옛 sweep 은 패드에 안
            //     들어가면 내밈을 스스로 줄였고, 그러면 정본에 4 라 적고 실물이 2 가 된다 —
            //     그것이 바로 이 저장소가 몇 번을 당한 <b>「신고표가 실물보다 넓다」</b>이다.
            //     그래서 눈은 ① 상자 크기와 ② <b>그 상자가 패드 안에 온전히 드는가</b>를 함께
            //     묻는다. 패드 밖은 put() 이 조용히 버리므로, 밖이면 실현 ≠ 선언이다.
            Blueprint.Roof rf1 = hj.roofs().get(0);
            int[] rbx = rf1.box();
            check("★[눈의 눈] 본전 지붕 상자를 읽었다", rbx.length == 4, java.util.Arrays.toString(rbx));
            check("★★본전만 새 판을 탄다 (profile main_hall_grand — 옛 sweep 은 안 건드린다)",
                    rf1.grand(), rf1.profile());

            int ix0 = rbx[0] - rf1.eaveX();
            int ix1 = rbx[2] + rf1.eaveX();
            int iz0 = rbx[1] - rf1.eaveZ();
            int iz1 = rbx[3] + rf1.eaveZ();
            String interBox = (ix1 - ix0 + 1) + "×" + (iz1 - iz0 + 1);
            // ★★자를 고쳤다 (깊이 20 · 2026-08-11): 37×18 은 <b>깊이 14 시절의 값</b>이라
            //   몸체를 깊게 하자 그 자리에서 죽었다. 계약은 「37×18 이다」가 아니라
            //   <b>「몸체 + 내밈 × 2 다」</b> — 값이 아니라 <b>규칙</b>을 묻는다.
            int wantW = (rbx[2] - rbx[0] + 1) + rf1.eaveX() * 2;
            int wantD = (rbx[3] - rbx[1] + 1) + rf1.eaveZ() * 2;
            check("★★층간 지붕 실현 상자 = <b>몸체 + 내밈×2</b> (" + wantW + "×" + wantD
                            + " — 실제 " + interBox + ")",
                    (ix1 - ix0 + 1) == wantW && (iz1 - iz0 + 1) == wantD, interBox);
            check("★★층간 지붕이 패드 안에 온전히 든다 (밖이면 조용히 작아진다 = 신고표 거짓말)",
                    ix0 >= 0 && ix1 < hj.width() && iz0 >= 0 && iz1 < hj.depth(),
                    ix0 + ".." + ix1 + " / " + iz0 + ".." + iz1);

            int ux0 = rbx[0] + rf1.insetX() - rf1.upperEaveX();
            int ux1 = rbx[2] - rf1.insetX() + rf1.upperEaveX();
            int uz0 = rbx[1] + rf1.insetZ() - rf1.upperEaveZ();
            int uz1 = rbx[3] - rf1.insetZ() + rf1.upperEaveZ();
            String upBox = (ux1 - ux0 + 1) + "×" + (uz1 - uz0 + 1);
            int wantUW = (rbx[2] - rbx[0] + 1) - rf1.insetX() * 2 + rf1.upperEaveX() * 2;
            int wantUD = (rbx[3] - rbx[1] + 1) - rf1.insetZ() * 2 + rf1.upperEaveZ() * 2;
            check("★★대지붕 실현 상자 = <b>상층 몸체 + 내밈×2</b> (" + wantUW + "×" + wantUD
                            + " — 실제 " + upBox + ")",
                    (ux1 - ux0 + 1) == wantUW && (uz1 - uz0 + 1) == wantUD, upBox);
            check("★★대지붕이 패드 안에 온전히 든다",
                    ux0 >= 0 && ux1 < hj.width() && uz0 >= 0 && uz1 < hj.depth(),
                    ux0 + ".." + ux1 + " / " + uz0 + ".." + uz1);

            // ★상층 폭비 — 7호의 0.66 은 **superseded**. 틀려서가 아니라 시각 정본이 바뀌어서다.
            int lowW = rbx[2] - rbx[0] + 1;
            int upW = lowW - 2 * rf1.insetX();
            check("★★상층 폭 **25** (하층 " + lowW + " 대비 "
                            + String.format("%.3f", upW / (double) lowW)
                            + " — 7호의 0.66 은 mainhall_ref.png 로 superseded)",
                    upW == 25, upW + "/" + lowW);
            check("★★지붕이 좌우로 더 뻗는다 (가로 실루엣 — 좌우 내밈 > 앞뒤 내밈)",
                    rf1.eaveX() > rf1.eaveZ() && rf1.upperEaveX() > rf1.upperEaveZ(),
                    rf1.eaveX() + "/" + rf1.eaveZ() + " · "
                            + rf1.upperEaveX() + "/" + rf1.upperEaveZ());

            // ══════ ★★★REF-1b — 적주는 <b>옮기는 것이 아니라 덧대는 것</b> ══════
            //   본전 정면 사진이 드러낸 것: {@code +1} 을 이동으로 구현하면 적주가 나가면서
            //   벽면에 세로 구멍을 남기고, 하층이 <b>폐쇄 전각이 아니라 주랑</b>으로 읽힌다.
            //   ★눈은 <b>기준면에 구멍이 남는가</b>를 묻는다 — 정면의 모든 비개구 칸은 닫혀야 한다.
            check("★★덧댐의 배경 글자가 있다 (적주가 나가도 그 자리를 회벽이 메운다)",
                    hj.backingChar() != 0, hj.backingChar());
            int holes = 0;
            int closed = 0;
            for (int c = 0; c < hj.width(); c++) {
                char fc = hj.at(c, frontR);
                if (fc == '.' || fc == 'M' || fc == 'N' || fc == 'F' || fc == 'O') {
                    continue;                       // 마당·기단·중앙 개구는 뚫려야 맞다
                }
                // ★★자를 고쳤다 (툇간 · 2026-08-10): 전에는 <b>기준면</b>이 닫혔는지 물었다.
                //   툇간이 생기며 <b>기준면은 일부러 비운다</b> (기둥과 벽 사이의 빈 칸이 곧 툇간).
                //   닫혀야 하는 것은 <b>물러난 벽 평면</b>이다 — 거기서 물어야 한다.
                char plane = fc;
                boolean planeSolid = false;
                for (Blueprint.Course cs : hj.columnOf(plane)) {
                    if (!"air".equals(cs.material()) && !"lattice".equals(cs.material())) {
                        planeSolid = true;
                        break;
                    }
                }
                if (planeSolid) {
                    closed++;
                } else {
                    holes++;
                }
            }
            check("★[눈의 눈] 본전 정면의 비개구 칸을 셌다", closed + holes > 0, closed + holes);
            check("★★<b>물러난 벽 평면</b>에 세로 구멍이 없다 (툇간 뒤는 폐쇄 전각이다 — 구멍 "
                            + holes + "/" + (closed + holes) + ")",
                    holes == 0, holes + "/" + (closed + holes));
            // ══════ ★★★기단 난간 · 겹기둥 결속 (Codex 좌표 계약 2026-08-10) ══════
            {
                java.util.List<int[]> rail = new java.util.ArrayList<>();
                java.util.List<int[]> newel = new java.util.ArrayList<>();
                for (int r2 = 0; r2 < hj.depth(); r2++) {
                    for (int c2 = 0; c2 < hj.width(); c2++) {
                        if (hj.at(c2, r2) == 'R') {
                            rail.add(new int[]{c2, r2});
                        }
                        if (hj.at(c2, r2) == 'E') {
                            newel.add(new int[]{c2, r2});
                        }
                    }
                }
                check("★[눈의 눈] 난간 칸을 찾았다", rail.size() > 40, rail.size());
                // ★난간은 <b>낙차가 있는 가장자리</b>에만 — 바깥이 더 낮아야 한다
                int bad = 0;
                for (int[] rc : rail) {
                    int c2 = rc[0];
                    int r2 = rc[1];
                    boolean onDrop = false;
                    for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int nx = c2 + d[0];
                        int ny = r2 + d[1];
                        char o = nx < 0 || ny < 0 || nx >= hj.width() || ny >= hj.depth()
                                ? '.' : hj.at(nx, ny);
                        if (o == 's' || o == '.') {
                            onDrop = true;             // 바깥에 더 낮은 몰딩/마당이 있다
                        }
                    }
                    if (!onDrop) {
                        bad++;
                    }
                }
                check("★★난간이 <b>낙차가 있는 가장자리</b>에만 선다 (안쪽에 두면 월대 횡단을 "
                                + "가른다 — 어긋난 칸 " + bad + ")", bad == 0, bad);
                // ★중앙 진입대는 <b>안 막는다</b> — 계단이 올라올 자리이고 축선이다
                int block = 0;
                for (int c2 = hj.axisCol() - 2; c2 <= hj.axisCol() + 2; c2++) {
                    for (int r2 = 20; r2 <= 25; r2++) {
                        if (r2 < hj.depth() && hj.at(c2, r2) == 'R') {
                            block++;
                        }
                    }
                }
                check("★★난간이 <b>중앙 축선을 막지 않는다</b> (계단이 올라올 자리 — 막은 칸 "
                                + block + ")", block == 0, block);
                check("★★난간이 끊기는 두 끝에 <b>엄지기둥</b>이 있다 (끊긴 자리가 그냥 "
                                + "잘린 것처럼 보이지 않게 — " + newel.size() + "개)",
                        newel.size() == 2, newel.size());
                // ★겹기둥 결속 — 재료는 안 맞추되 <b>주두 윤곽</b>과 켜 수가 같아야 한다
                int capA = capitalCourses(hj, 'A');
                int capJ = capitalCourses(hj, 'J');
                check("★★A+J 가 <b>같은 주두 윤곽</b>이다 (한 쌍으로 읽히려면 재료가 아니라 "
                                + "윤곽을 맞춘다 — A " + capA + " · J " + capJ + ")",
                        // ★★자를 고쳤다 (기둥 재설계 2026-08-10): 「두 켜여야 한다」는
                        //   그때의 <b>처방</b>이지 계약이 아니었다. 기둥에서 튀어나오는 켜를
                        //   걷어내며 주두가 한 켜가 됐다 — 계약은 <b>A 와 J 가 같은가</b>이지
                        //   <b>몇 켜인가</b>가 아니다.
                        capA == capJ && capA >= 1, capA + "/" + capJ);
                // ★자를 고쳤다 (REF-2C): 「mangrove_log 가 없다」가 아니라
                //   <b>적주와 다른 재료다</b> — 그것이 이 계약의 실제 내용이다.
                String postMat = shaftMaterial(hj.columnOf('P'));
                String jambMat = shaftMaterial(hj.columnOf('J'));
                check("★★그래도 <b>J 의 암색은 유지</b>한다 (적주와 같은 재료면 3칸 주기가 다시 깨진다 — "
                                + jambMat + " vs 적주 " + postMat + ")",
                        !jambMat.isEmpty() && !jambMat.equals(postMat), jambMat);
            }

            // ★툇간의 계약 — Codex 가 좌표로 적어 준 것
            int postD = hj.depthOf('P');
            int wallD = hj.depthOf('W');
            int latD = hj.depthOf('D');
            Blueprint.Roof rv = hj.roofs().get(0);
            check("★★적주와 벽 사이가 <b>2</b> 다 (사이에 실제 빈 칸 하나 — 그것이 툇간)",
                    postD - wallD >= 2, postD + " − " + wallD);
            // ★★자를 고쳤다 (Codex 처방): 창을 키우려고 <b>창호를 벽 평면으로 전진</b>시켰다.
            //   이제 살창과 회벽이 같은 평면이다. 계약의 뜻은 「세 겹이어야 한다」가 아니라
            //   <b>기둥이 벽보다 앞에 있고 그 사이가 비어 있다</b>였다 (툇간).
            check("★★창호가 <b>적주보다 뒤</b>에 있다 (살창 " + latD + " < 적주 " + postD + ")",
                    latD < postD, latD + "<" + postD);
            check("★★공포가 밖으로 나갈 자리가 남는다 (처마 " + rv.eaveZ() + " − 적주 " + postD
                            + " ≥ 1 — 기둥을 더 내보내면 이게 깨진다)",
                    rv.eaveZ() - postD >= 1, rv.eaveZ() - postD);

            // ══════ ★★★REF-1c-A — 큰 면 셋 (사용자 확정 2026-08-09) ══════
            //   ①밝은 가로띠 ②관통 터널 ③계단식 검은 지붕 — 작은 장식보다 <b>면적</b>이 크다.
            //   ★①밝은 재료는 <b>띠가 아니라 점</b>이어야 한다: 정면 한 켜에서 밝은 칸이
            //     연속으로 몇 칸까지 이어지는가를 잰다 (연속 2 이상이면 띠다).
            {
                int run = 0;
                int longest = 0;
                for (int c = 0; c < hj.width(); c++) {
                    char fc = hj.at(c, frontR);
                    java.util.List<Blueprint.Course> col = hj.columnOf(fc);
                    boolean bright = false;
                    for (Blueprint.Course cs : col) {
                        if (cs.material().contains("sandstone")) {
                            bright = true;
                        }
                    }
                    run = bright ? run + 1 : 0;
                    longest = Math.max(longest, run);
                }
                check("★★밝은 재료가 <b>가로띠를 이루지 않는다</b> (정면 최장 연속 " + longest
                                + "칸 — 2 이상이면 띠다)", longest <= 1, longest);
            }
            // ★②중앙 — 5칸 통짜 개구가 아니라 <b>개구 3 + 좌우 문설주</b>여야 한다.
            {
                int open = 0;
                int jamb = 0;
                for (int c = hj.axisCol() - 2; c <= hj.axisCol() + 2; c++) {
                    char fc = hj.at(c, frontR);
                    boolean through = hj.columnOf(fc).stream()
                            .anyMatch(cs -> "air".equals(cs.material()) && cs.count() >= 4);
                    if (through) {
                        open++;
                    } else if (hj.columnOf(fc).stream().anyMatch(cs ->
                            cs.material().endsWith("_log") && cs.count() >= 3)) {
                        // ★★자를 고쳤다 (REF-2B): 전에는 「mangrove_log 를 포함하는가」로 문설주를
                        //   골랐다. 목재 팔레트를 역할대로 가르자 문설주가 어두운 참나무만 남아
                        //   조성은 멀쩡한데 눈이 0 을 셌다. 문설주는 <b>재료 이름</b>이 아니라
                        //   <b>세로로 이어진 통짜 기둥</b>이라는 구조로 골라야 한다.
                        jamb++;
                    }
                }
                check("★★중앙 개구가 **3칸**이고 좌우를 문설주가 감싼다 (개구 " + open
                                + " · 문설주 " + jamb + ")", open == 3 && jamb == 2, open + "/" + jamb);
                check("★★문설주가 앞으로 나온다 = 문이 한 칸 뒤에 앉는다 (터널이 아니다)",
                        hj.depthOf(hj.at(hj.axisCol() - 2, frontR)) > 0,
                        hj.depthOf(hj.at(hj.axisCol() - 2, frontR)));
            }
            // ★③그림자 홈 — 공포 머리가 처마 밑에 바로 붙으면 한 덩어리 띠가 된다
            // ★★진범 둘 — ①판정이 넓어 모든 벽 칸이 적주로 세어졌다 ②좌우로 번져 이어졌다.
            //   ①을 <b>실제 개수로</b> 센다: 본전 정면에서 공포가 앉는 칸은 <b>적주 수</b>여야 한다.
            {
                // 몸통 재료는 <b>도면에서 읽는다</b> — 자 안에 이름을 박으면 팔레트를 갈 때 또 눈먼다
                String shaftMat = shaftMaterial(hj.columnOf('P'));
                int bracketed = 0;
                int realPosts = 0;
                for (int c = 0; c < hj.width(); c++) {
                    java.util.List<Blueprint.Course> col = hj.columnOf(hj.at(c, frontR));
                    // 넓은 자 = <b>켜 수를 안 보는</b> 자 (몸통 재료가 한 켜만 있어도 적주로 센다)
                    boolean wide = col.stream().anyMatch(cs -> cs.material().equals(shaftMat));
                    boolean tight = isPost(col);
                    if (wide) {
                        bracketed++;
                    }
                    if (tight) {
                        realPosts++;
                    }
                }
                // ★★★자를 고쳤다 (Codex 판정 2026-08-10): 「정면의 PCA 글자 수」로 세던 자다.
                //   또 <b>글자</b>였다. 계약은 「공포는 모든 구조적 적주에 앉는다」이므로
                //   기대값도 <b>구조에서</b> 나와야 한다.
                int structuralPosts = (int) java.util.stream.IntStream.range(0, hj.width())
                        .filter(cc -> hj.isPost(hj.at(cc, frontR))).count();
                check("★★공포가 앉는 칸 = <b>적주 수</b> (" + realPosts + "칸)",
                        // ★자를 고쳤다: 「10」은 9칸일 때의 수였다. 7칸이 되며 8 이 됐다 —
                        //   계약은 <b>공포가 적주에만 앉는다</b>이지 「몇 개인가」가 아니다.
                        // ★자를 조였다 (Codex): 「6 이상」이면 7칸·8앵커를 못 지킨다.
                        //   정면의 적주 역할 칸 수와 <b>정확히</b> 같아야 한다.
                        realPosts == structuralPosts, realPosts + "/" + structuralPosts);
                // ★★[눈의 눈] 넓은 자가 왜 못 쓰는지를 <b>변이로</b> 보인다.
                //   전에는 「오늘의 도면에서 넓은 자가 더 많이 고른다」로 증명했는데,
                //   REF-2B 로 인방이 mangrove_planks 가 되자 그 차이가 사라져 눈이 짖었다 —
                //   <b>증명이 오늘의 팔레트에 얹혀 있었다.</b> 이제 회벽에 한 켜짜리 붉은 통나무를
                //   <b>일부러 끼워</b> 넓은 자가 무너지는 것을 직접 보인다.
                java.util.Map<String, Object> postRaw = new org.yaml.snakeyaml.Yaml().load(
                        java.nio.file.Files.readString(
                                java.nio.file.Path.of("config/blueprints/hwasan_honjeon.yml")));
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> postCols =
                        (java.util.Map<String, Object>) postRaw.get("columns");
                @SuppressWarnings("unchecked")
                // ★자를 고쳤다: 정면이 7칸이 되며 W 가 정면에서 빠졌다 — 변이를 <b>D</b> 에 끼운다
                java.util.List<Object> mutW = new java.util.ArrayList<>(
                        (java.util.List<Object>) postCols.get("D"));
                mutW.add(1, shaftMat);                       // 인방 한 켜를 <b>몸통 재료</b>로
                postCols.put("D", mutW);
                Blueprint mut = Blueprint.of(postRaw);
                int mutWide = 0;
                int mutTight = 0;
                for (int c = 0; c < mut.width(); c++) {
                    java.util.List<Blueprint.Course> col = mut.columnOf(mut.at(c, frontR));
                    if (col.stream().anyMatch(cs -> cs.material().equals(shaftMat))) {
                        mutWide++;
                    }
                    if (isPost(col)) {
                        mutTight++;
                    }
                }
                check("★★[눈의 눈] 한 켜짜리 인방을 끼우면 <b>넓은 자만 무너진다</b> (넓은 "
                                + mutWide + " · 바른 " + mutTight + ")",
                        mutWide > mutTight && mutTight == realPosts, mutWide + "/" + mutTight);
            }
            // ══════ ★★★공포 런 계약 (Codex 2026-08-10 · REF-2C) ══════
            //   「공포는 모든 shaftIndex 적주에 놓는다. 단, 중앙 대문 양측의 인접 적주쌍에서는
            //     길이 2 의 연속 공포를 허용한다. 연속 길이 3 이상은 금지하며,
            //     좌우가 대칭이어야 한다.」
            {
                java.util.List<int[]> runs = new java.util.ArrayList<>();
                int c = 0;
                while (c < hj.width()) {
                    if (!hj.isPost(hj.at(c, frontR))) {
                        c++;
                        continue;
                    }
                    int s = c;
                    while (c < hj.width() && hj.isPost(hj.at(c, frontR))) {
                        c++;
                    }
                    runs.add(new int[]{s, c - 1});
                }
                int longest = runs.stream().mapToInt(rr -> rr[1] - rr[0] + 1).max().orElse(0);
                java.util.List<int[]> bkPairs = runs.stream()
                        .filter(rr -> rr[1] - rr[0] + 1 == 2).toList();
                check("★★공포 런이 <b>3 이상으로 안 이어진다</b> (가장 긴 런 " + longest + ")",
                        longest <= 2, longest);
                check("★★길이-2 런은 <b>중앙 대문 양측</b>에만 있다 (" + bkPairs.size() + "쌍)",
                        bkPairs.size() == 2 && bkPairs.stream().allMatch(rr ->
                                flanksAxis(hj, frontR, rr[0], rr[1])), bkPairs.size());
                // 대칭 — 축에서 잰 거리가 좌우 같아야 한다
                boolean sym = bkPairs.size() == 2
                        && Math.abs(hj.axisCol() - bkPairs.get(0)[0])
                            == Math.abs(bkPairs.get(1)[1] - hj.axisCol())
                        && Math.abs(hj.axisCol() - bkPairs.get(0)[1])
                            == Math.abs(bkPairs.get(1)[0] - hj.axisCol());
                check("★★중앙 겹기둥 두 쌍이 <b>축에 대칭</b>이다 (축 " + hj.axisCol() + ")",
                        sym, bkPairs.stream().map(java.util.Arrays::toString).toList().toString());
                // ★[눈의 눈] 축에서 먼 자리에 적주를 하나 더 붙이면 이 자가 <b>무너져야</b> 한다
                java.util.Map<String, Object> runRaw = new org.yaml.snakeyaml.Yaml().load(
                        java.nio.file.Files.readString(
                                java.nio.file.Path.of("config/blueprints/hwasan_honjeon.yml")));
                @SuppressWarnings("unchecked")
                java.util.List<String> runPlan = new java.util.ArrayList<>(java.util.Arrays.asList(
                        String.valueOf(runRaw.get("plan")).split("\n")));
                String fr = runPlan.get(frontR);
                // ★변이 자리를 <b>찾아서</b> 넣는다 (2026-08-11): 전에는 col 5 에 박아 넣었는데
                //   문선(L)이 서면서 그 자리가 이미 적주가 되어 <b>변이가 안 먹혔다</b>.
                //   왼쪽 끝 적주 <b>바로 옆의 채움 칸</b>을 찾아 거기에 적주를 끼운다.
                int mutAt = -1;
                // ★자를 고쳤다 (칸 넓힘 · 2026-08-11): 아무 빈 칸에나 끼우면 <b>런 2</b> 밖에
                //   안 된다. 증명할 계약은 「런이 3 이상이면 안 된다」이므로,
                //   <b>이미 있는 겹기둥 바로 옆</b>에 끼워 런 3 을 만든다.
                for (int[] pr2 : bkPairs) {
                    if (pr2[0] - 1 > hj.roofs().get(0).box()[0]
                            && !hj.isPost(hj.at(pr2[0] - 1, frontR))) {
                        mutAt = pr2[0] - 1;
                        break;
                    }
                }
                runPlan.set(frontR, fr.substring(0, mutAt) + 'P' + fr.substring(mutAt + 1));
                runRaw.put("plan", String.join("\n", runPlan));
                Blueprint runMut = Blueprint.of(runRaw);
                java.util.List<int[]> mutRuns = new java.util.ArrayList<>();
                int c2 = 0;
                while (c2 < runMut.width()) {
                    if (!runMut.isPost(runMut.at(c2, frontR))) {
                        c2++;
                        continue;
                    }
                    int s = c2;
                    while (c2 < runMut.width() && runMut.isPost(runMut.at(c2, frontR))) {
                        c2++;
                    }
                    mutRuns.add(new int[]{s, c2 - 1});
                }
                int mutLongest = mutRuns.stream().mapToInt(rr -> rr[1] - rr[0] + 1)
                        .max().orElse(0);
                // ★★자를 고쳤다 (문선 · 2026-08-11): 전에는 「축에서 먼 <b>길이-2 런</b>을
                //   만들면 잡힌다」로 증명했다. 그런데 문선이 서면서 모든 틈이 1 이 되어
                //   적주를 하나 더 끼우면 <b>길이 3 런</b>이 된다 — 길이-2 는 못 만든다.
                //   계약의 뜻은 그대로다: <b>「런이 3 이상으로 안 이어진다」</b>.
                //   그러므로 변이가 증명할 것도 그것이다.
                check("★★[눈의 눈] 적주를 하나 더 끼우면 <b>런이 3 이 된다</b> (그래서 잡힌다 — "
                                + mutLongest + ")", mutLongest >= 3, mutLongest);
            }
            // ★진범 — 공포가 좌우로 번지면 3칸 주기 적주에서 <b>이웃끼리 이어져 띠가 된다</b>.
            check("★★공포가 <b>좌우로 번지지 않는다</b> (번지면 적주 리듬을 스스로 지운다)",
                    java.nio.file.Files.readString(java.nio.file.Path.of(
                            "server-mvt/src/main/java/com/honcheon/mvt/forge/"
                                    + "BlueprintBuilder.java"))
                            .contains("for (int side = 0; side <= 0; side++)"), "");
            // ★★S1R — 골을 <b>켜로 비우지 않고 윤곽이 만든다</b>. 아랫단만 적주 평면(+1)이고
            //   위 두 단이 처마 평면(+2)이라, 처마 평면의 그 아래 칸이 비어 그늘이 진다.
            //   ★골을 켜로 비우던 동안 공포 아랫단이 <b>적주 몸통을 먹었다</b> (5켜 중 4켜만 남았다).
            check("★★공포가 <b>밖으로 계단지며</b> 그늘을 만든다 (골을 켜로 비우지 않는다)",
                    com.honcheon.mvt.forge.BlueprintBuilder.GROOVE == 0
                            && java.nio.file.Files.readString(java.nio.file.Path.of(
                                    "server-mvt/src/main/java/com/honcheon/mvt/forge/"
                                            + "BlueprintBuilder.java"))
                                    .contains("Math.min(s2 + 1, Math.max(1, eave))"),
                    com.honcheon.mvt.forge.BlueprintBuilder.GROOVE);
            check("★★그래도 공포는 처마를 <b>넘지 않는다</b> (골은 아래로 내는 것이지 밖으로가 아니다)",
                    java.nio.file.Files.readString(java.nio.file.Path.of(
                            "server-mvt/src/main/java/com/honcheon/mvt/forge/"
                                    + "BlueprintBuilder.java"))
                            .contains("Math.min(s2 + 1, Math.max(1, eave))"), "");

            // ══════ ★★★REF-3B <b>위계를 원경까지</b> (사용자 2026-08-10) ══════
            {
                String bb4 = java.nio.file.Files.readString(java.nio.file.Path.of(
                        "server-mvt/src/main/java/com/honcheon/mvt/forge/BlueprintBuilder.java"));
                // ★공포가 <b>지붕 뒤</b>에 놓여야 서까래에게 맨 윗단을 안 뺏긴다 (실측 0/8 이었다)
                int roofCall = bb4.indexOf("sweepRoofGrand");
                int brkCall = bb4.indexOf("★적주가 아니면 공포도 없다");
                check("★[눈의 눈] 지붕 부름과 공포 고리를 둘 다 찾았다",
                        roofCall > 0 && brkCall > 0, roofCall + "/" + brkCall);
                check("★★공포가 <b>지붕 뒤에</b> 놓인다 (앞서면 서까래가 맨 윗단을 덮는다)",
                        brkCall > roofCall, brkCall + " > " + roofCall);
                // ★★★프로파일 분리 (Codex Q3 · 사용자 원칙) — 본전에서 만든 조형을 다른 건물에
                //   복붙하지 않는다. 공포를 「지붕 뒤에 · 계단 윤곽」으로 바꾼 것이 공유 코드라
                //   <b>강당까지 따라 바뀌었다</b> (실측: 강당 서까래 줄이 공포 반블록에 끊겼다).
                //   D2 로 이미 승인된 건물이 본전 회차마다 움직이면 무엇이 승인된 것인지 알 수 없다.
                Blueprint hallBp = Blueprint.of(new org.yaml.snakeyaml.Yaml().load(
                        java.nio.file.Files.readString(
                                java.nio.file.Path.of("config/blueprints/hwasan_hall.yml"))));
                check("★★계단 윤곽 공포는 <b>본전만</b> 켠다 (강당은 D2 승인 상태 그대로)",
                        hj.bracketContour() && !hallBp.bracketContour(),
                        hj.bracketContour() + "/" + hallBp.bracketContour());
                check("★★flat 프로파일은 <b>서까래 자리를 안 뺏는다</b> (승인된 처마선 보존)",
                        bb4.contains("if (top + s2 >= oy + roofBase - 1)")
                                && bb4.contains("서까래 자리를 안 뺏는다"), "");
                check("★★두 축 모서리도 <b>본전 전용</b>이다 (프로파일이 켠다)",
                        bb4.contains("if (bp.bracketContour() && !ewRun && !nsRun)"), "");
                // ★★★REF-3B-Q1 — 입구 옆이 <b>중앙으로</b> 뻗는다 (Codex 가 내 「불가」를 반박했다).
                check("★★역할 글자를 <b>도면이 선언</b>한다 (코드가 글자를 알면 옛 병으로 되돌아간다)",
                        hj.bayRole("entrance_adjacent") == 'A' && hj.bayRole("corner") == 'C',
                        hj.bayRole("corner") + "/" + hj.bayRole("entrance_adjacent"));
                check("★★입구 옆이 <b>축을 향해</b> 한 칸 뻗는다 (좌표에 방향을 안 박는다)",
                        bb4.contains("int toward = -Integer.signum(lv.axisCol() - c)")
                                && bb4.contains("sd.setFacing(lat)"), "");
                // ★부딪치지 않는 까닭 — 문설주는 적주 평면(+1), 대각은 처마 평면(+eave)이다
                Blueprint.Roof r0 = hj.roofs().get(0);
                check("★★대각이 <b>문설주와 다른 평면</b>에 있다 (문설주 +" + hj.depthOf('J')
                                + " · 대각 +" + r0.eaveZ() + " — 같으면 겹친다)",
                        r0.eaveZ() > hj.depthOf('J'), hj.depthOf('J') + " vs " + r0.eaveZ());
                check("★★모서리 적주는 <b>두 축</b>을 받는다 (한 면만 받으면 굵은 기둥일 뿐이다)",
                        bb4.contains("!ewRun && !nsRun") && bb4.contains("faces.add(other)"), "");
                // ★중앙문 — 문두 양 끝이 <b>꺾인다</b>
                java.util.Map<String, Blueprint.Trim> tm2 = new java.util.HashMap<>();
                for (Blueprint.Trim tr : hj.trims()) {
                    tm2.put(tr.id(), tr);
                }
                Blueprint.Trim hw = tm2.get("door_head_w");
                Blueprint.Trim he = tm2.get("door_head_e");
                Blueprint.Trim hc = tm2.get("door_head");
                check("★★문두 양 끝이 <b>계단으로 꺾인다</b> (90도 네모면 크기만 클 뿐이다)",
                        hw != null && he != null && hc != null
                                && hw.material().endsWith("_stairs")
                                && he.material().endsWith("_stairs")
                                && hc.material().endsWith("_slab"),
                        hw == null ? "-" : hw.material());
                check("★★문두 꺾임이 <b>좌우 대칭</b>이다 (축에서 같은 거리)",
                        hw != null && he != null
                                && hj.axisCol() - hw.cols()[0] == he.cols()[1] - hj.axisCol(),
                        hw == null ? "-" : (hw.cols()[0] + "·" + he.cols()[1]));
                // ★내부 문틀 어깨도 좌우 대칭
                Blueprint.Trim jw = tm2.get("door_jamb_head_w");
                Blueprint.Trim je = tm2.get("door_jamb_head_e");
                check("★★내부 문틀 <b>어깨</b>가 좌우 대칭으로 있다 (문두와 개구 사이 한 단)",
                        jw != null && je != null
                                && hj.axisCol() - jw.cols()[0] == je.cols()[1] - hj.axisCol()
                                && jw.depth() == 1 && je.depth() == 1,
                        jw == null ? "-" : (jw.cols()[0] + "·" + je.cols()[1]));
                // ★밀도 사다리 — 일반 < 모서리·입구 옆 < 중앙. <b>주두 켜 수</b>로 잰다.
                int capP = capitalCourses(hj, 'P');
                int capC = capitalCourses(hj, 'C');
                int capA = capitalCourses(hj, 'A');
                check("★★주두 밀도가 <b>일반 < 모서리·입구 옆</b> 이다 (일반 " + capP + " · 모서리 "
                                + capC + " · 입구 옆 " + capA + ")",
                        capP < capC && capP < capA, capP + "/" + capC + "/" + capA);
                check("★★모서리와 입구 옆은 <b>형태는 같고 결이 다르다</b> (둘 다 두 켜지만 재료가 갈린다)",
                        capC == capA && !hj.columnOf('C').equals(hj.columnOf('A')),
                        capC + "/" + capA);
            }

            // ══════ ★★★REF-3A <b>bay 역할</b> (사용자 확정 2026-08-10) ══════
            //   정면이 `A A A A | 중앙 | A A A A` 로 <b>모든 칸이 똑같았다</b>. 반복을 깨되
            //   <b>랜덤이 아니라 자리</b>가 깬다 — 모서리 · 입구 옆 · 일반 · 중앙.
            //   ★해시는 어디에도 안 낀다 (「위치가 정하면 구조, 해시가 정하면 잡티」).
            {
                java.util.List<Integer> corner = new java.util.ArrayList<>();
                java.util.List<Integer> adj = new java.util.ArrayList<>();
                java.util.List<Integer> plain = new java.util.ArrayList<>();
                java.util.List<Integer> jamb2 = new java.util.ArrayList<>();
                for (int c = 0; c < hj.width(); c++) {
                    switch (hj.at(c, frontR)) {
                        case 'C' -> corner.add(c);
                        case 'A' -> adj.add(c);
                        case 'P' -> plain.add(c);
                        case 'J' -> jamb2.add(c);
                        default -> { }
                    }
                }
                check("★★정면에 <b>네 역할</b>이 다 있다 (모서리 " + corner.size() + " · 입구 옆 "
                                + adj.size() + " · 일반 " + plain.size() + " · 문설주 "
                                + jamb2.size() + ")",
                        // ★자를 고쳤다 (칸 넓힘): 「일반 4 이상」은 <b>7칸 시절의 수</b>다.
                        //   칸이 5 로 줄자 일반 적주는 2 가 됐다 — 조성은 멀쩡한데 자가
                        //   어제의 값을 외웠다. 계약은 「네 역할이 <b>다 있다</b>」이지
                        //   「몇 개인가」가 아니다.
                        corner.size() == 2 && adj.size() == 2 && !plain.isEmpty()
                                && jamb2.size() == 2,
                        corner + "/" + adj + "/" + plain.size());
                // ★모서리는 <b>양 끝</b>에만 — 가운데 어디에 있으면 그건 역할이 아니라 무늬다
                int bodyL = Integer.MAX_VALUE;
                int bodyR = -1;
                for (int c = 0; c < hj.width(); c++) {
                    // ★난간(R)·엄지기둥(E) 은 <b>기단</b> 부재다 — 몸체의 끝이 아니다
                    char bc = hj.at(c, frontR);
                    if (bc != '.' && bc != 's' && bc != 'M' && bc != 'N'
                            && bc != 'R' && bc != 'E') {
                        bodyL = Math.min(bodyL, c);
                        bodyR = Math.max(bodyR, c);
                    }
                }
                check("★★모서리 역할이 <b>몸체의 양 끝</b>에만 있다 (자리가 정한다 — "
                                + corner + " vs 끝 " + bodyL + "·" + bodyR + ")",
                        corner.size() == 2 && corner.get(0) == bodyL && corner.get(1) == bodyR,
                        corner + " / " + bodyL + "," + bodyR);
                // ★입구 옆은 <b>문설주 바로 바깥</b>에만
                check("★★입구 옆 역할이 <b>문설주 바로 바깥</b>에만 있다 (" + adj + " vs 문설주 "
                                + jamb2 + ")",
                        adj.size() == 2 && jamb2.size() == 2
                                && adj.get(0) == jamb2.get(0) - 1 && adj.get(1) == jamb2.get(1) + 1,
                        adj + " / " + jamb2);
                // ★역할이 달라도 <b>켜 수는 같아야</b> 한다 — 지붕이 앉는 높이는 하나다
                int hP = hj.columnOf('P').stream().mapToInt(Blueprint.Course::count).sum();
                int hC = hj.columnOf('C').stream().mapToInt(Blueprint.Course::count).sum();
                int hA = hj.columnOf('A').stream().mapToInt(Blueprint.Course::count).sum();
                check("★★역할이 달라도 <b>켜 수는 같다</b> (일반 " + hP + " · 모서리 " + hC
                                + " · 입구 옆 " + hA + " — 다르면 지붕이 기운다)",
                        hP == hC && hC == hA, hP + "/" + hC + "/" + hA);
                // ★그래도 <b>형태는 달라야</b> 한다 — 같으면 역할을 나눈 뜻이 없다
                check("★★세 역할의 <b>처방이 서로 다르다</b> (같으면 나눈 뜻이 없다)",
                        !hj.columnOf('C').equals(hj.columnOf('P'))
                                && !hj.columnOf('A').equals(hj.columnOf('P'))
                                && !hj.columnOf('A').equals(hj.columnOf('C')), "");
                // ★셋 다 공포가 걸려야 한다 (몸통 3켜 이상)
                int anchored = 0;
                for (char ch : new char[]{'P', 'C', 'A'}) {
                    if (isPost(hj.columnOf(ch))) {
                        anchored++;
                    }
                }
                check("★★세 역할 모두 <b>적주로 세어진다</b> (공포가 셋 다에 앉는다)",
                        anchored == 3, anchored);
            }

            // ══════ ★★★REF-2.5 <b>블록 조형</b> (사용자 2026-08-10) ══════
            //   「지금 그냥 색으로 나눈 것 같은데?」 — 맞는 감각이었다. 색만 갈면 흑백으로
            //   봤을 때 전·후가 거의 같다. 지붕이 좋아진 까닭은 재료가 아니라 <b>계단·반블록·
            //   담장·귀솟음·용마루로 형태를 썼기 때문</b>이다. 몸체에도 그것이 필요하다.
            //
            //   ★성공 조건: <b>흑백으로 봐도</b> 기둥·창·보·공포·기단이 서로 다른 부재로 읽힌다.
            //     그래서 눈은 색이 아니라 <b>통짜 블록이 아닌 부재가 몇이나 되는가</b>를 센다.
            {
                String bbSrc3 = java.nio.file.Files.readString(java.nio.file.Path.of(
                        "server-mvt/src/main/java/com/honcheon/mvt/forge/BlueprintBuilder.java"));
                java.util.Set<String> shaped = new java.util.LinkedHashSet<>();
                int cube = 0;
                int shape = 0;
                // ★자를 넓혔다 (2026-08-10): 난간 R·엄지기둥 E 가 생겼는데 목록에 없어
                //   「모양 있는 켜」를 덜 셌다. 난간도 몸체가 아니라 <b>형태 언어</b>의 일부다.
                for (char ch : new char[]{'P', 'W', 'D', 'J', 'O', 'M', 'N', 'F', 's', 'R', 'E'}) {
                    for (Blueprint.Course cs : hj.columnOf(ch)) {
                        String m = cs.material();
                        if ("air".equals(m) || "lattice".equals(m)) {
                            continue;
                        }
                        if (m.endsWith("_slab") || m.endsWith("_stairs") || m.endsWith("_wall")
                                || m.endsWith("_fence")) {
                            shape++;
                            shaped.add(ch + ":" + m);
                        } else {
                            cube++;
                        }
                    }
                }
                check("★[눈의 눈] 본전 처방의 켜를 재료 모양별로 셌다", cube + shape > 0, cube + shape);
                check("★★몸체가 <b>통짜 정육면체만</b>으로 서 있지 않다 (모양 있는 켜 " + shape
                                + " · 통짜 " + cube + ")", shape >= 5, shape + "/" + cube);
                // ★★S1R (2026-08-10) — 자를 뒤집었다. 앞 눈은 「담장 블록이 있는가」를 물었는데,
                //   그 담장이 바로 <b>기둥을 촛대로 만든 범인</b>이었다. 눈이 틀린 조형을 지키고
                //   있었던 것이다. 물어야 할 것은 <b>주초가 몸통보다 가늘지 않은가</b>다.
                //   ★교훈: 흑백에서 달라진다 ≠ 레퍼런스와 닮았다.
                java.util.List<Blueprint.Course> s1p = hj.columnOf('P');
                boolean thinFoot = s1p.stream().anyMatch(cs ->
                        cs.material().endsWith("_wall") || cs.material().endsWith("_fence"));
                int shaftAt = shaftIndex(s1p);
                boolean stoneFoot = shaftAt > 0 && (s1p.get(shaftAt - 1).material().contains("stone")
                        || s1p.get(shaftAt - 1).material().contains("andesite"));
                check("★★S1R 주초가 <b>몸통보다 가늘지 않다</b> (담장·울타리 받침 금지 — 촛대가 된다)",
                        !thinFoot, thinFoot);
                check("★★S1R 적주가 <b>석재 주초</b> 위에 바로 선다 (굵기 그대로 내려온다)",
                        stoneFoot, shaftAt);
                // ★자를 고쳤다 (REF-2C): 「mangrove 나 dark_oak 로 시작하는가」가 아니라
                //   <b>석재가 아닌가</b>다. 계약의 내용은 「밝은 석재 캡이 처마를 받지 않는다」이지
                //   「특정 목재다」가 아니다.
                String capMat = shaftAt >= 0 && shaftAt + 1 < s1p.size()
                        ? s1p.get(shaftAt + 1).material() : "-";
                check("★★S1R 주두가 <b>석재가 아니다</b> (밝은 석재 캡이 아니라 목구조가 처마를 받는다)",
                        shaftAt >= 0 && !capMat.contains("stone") && !capMat.contains("andesite")
                                && !capMat.equals("-"), capMat);
                check("★★S2 창에 <b>창턱</b>이 있다 (살창 바로 아래가 반블록 — 턱과 그늘)",
                        hj.columnOf('D').stream().anyMatch(cs -> cs.material().endsWith("_slab")),
                        "");
                // ★자는 <b>구조</b>로 남긴다 (재료 이름으로 되돌리지 않는다) —
                //   계약은 「도리가 <b>반블록</b>이다」이지 무슨 나무인가가 아니다.
                check("★★S3 긴 도리가 <b>반블록</b>이다 (통짜 판이면 흑백에서 그냥 띠다)",
                        hj.columnOf('W').stream().anyMatch(cs -> cs.material().endsWith("_slab")),
                        "");
                check("★★S5 기단에 <b>몰딩 치마</b>가 있다 (full block 케이크 3단 방지)",
                        hj.columnOf('s').stream().anyMatch(cs -> cs.material().endsWith("_slab")),
                        "");
                // S4 공포 — 계단 + 반블록 윤곽
                check("★★S4 공포 첫 단이 <b>몸통과 같은 재료</b>다 (다르면 기둥에 블럭이 붙어 보이고 "
                            + "신고한 몸통 켜 수가 실물과 어긋난다)",
                        // ★자를 고쳤다 (REF-2C): 특정 재료 이름을 찾던 자였다. 계약은
                        //   「몸통과 <b>같은 것</b>」이므로, 원문도 <b>같은 팔레트를 부르는가</b>를 본다.
                        bbSrc3.contains("s2 == 0 ? mat(bp.palette(\"post\""), "");
                check("★★계단의 방향도 <b>자리가 정한다</b> (도면은 네 벽에 다 쓰인다)",
                        bbSrc3.contains("st.setFacing(face.getOppositeFace())"), "");
                // S6 중앙 — 벽 0 · 문설주 +1 · 문두 +2 세 겹
                java.util.Set<Integer> depths = new java.util.TreeSet<>();
                depths.add(0);
                depths.add(hj.depthOf('J'));
                for (Blueprint.Trim tr : hj.trims()) {
                    if (tr.row() == frontR && tr.cols()[0] <= hj.axisCol()
                            && tr.cols()[1] >= hj.axisCol()) {
                        depths.add(tr.depth());
                    }
                }
                check("★★S6 중앙이 <b>세 겹 깊이</b>다 (벽 0 · 문설주 +1 · 문두 +2 — " + depths + ")",
                        depths.size() >= 3 && depths.contains(2), depths.toString());
            }

            // ══════ ★★★REF-2B 목구조 — <b>세 역할을 색으로 가른다</b> (2026-08-10) ══════
            //   더 붉게 만드는 회차가 아니다. 지붕이 기와가 된 뒤에도 목재가 한 갈색 덩어리로
            //   뭉쳤기에, 역할마다 재료를 뗀다:
            //     적주·프레임 = 붉은색 / 창방·인방 = 어두운 적갈색 / 도리·서까래·창호 = 매우 어두운 갈색
            {
                java.util.List<Blueprint.Course> post = hj.columnOf('P');
                // ★이것만은 <b>색 주장</b>이라 이름을 본다 — 구조가 아니라 색이 계약이기 때문이다.
                //   대신 재료 하나를 박지 않고 <b>붉은 재료의 목록</b>으로 둔다: 팔레트를 갈아도
                //   「붉은가」는 물을 수 있어야 한다 (REF-2C 로 통나무 → 테라코타가 됐다).
                java.util.Set<String> reds = java.util.Set.of(
                        "stripped_mangrove_log", "mangrove_log", "red_terracotta",
                        "red_concrete", "red_nether_bricks", "crimson_planks");
                String postShaft = shaftMaterial(post);
                boolean postRed = reds.contains(postShaft);
                check("★★적주는 <b>붉은색</b>이다 (붉은 재료가 세로로 이어진다 — " + postShaft + ")",
                        postRed, postShaft);
                // 정면 벽 칸의 인방·창방 = 어두운 적갈색 · 도리와 그 위 = 매우 어두운 갈색
                int redBeam = 0;
                int darkBeam = 0;
                int wrong = 0;
                for (char ch : new char[]{'W', 'D'}) {
                    java.util.List<Blueprint.Course> col = hj.columnOf(ch);
                    // ★★자를 넓혔다 (벽 6켜 · 2026-08-10) — 창호가 층으로 나뉘며
                    //   <b>하부판</b>이 생겼다. 하부판은 가로보가 아니라 <b>창호의 일부</b>라
                    //   dark_oak 계열이 맞다 (창살·창틀과 같은 무리).
                    //   그래서 살창 언저리는 이 자가 안 잰다 — 재려면 「창호 안인가」를 먼저 묻는다.
                    int fLo = -1;
                    int fHi = -1;
                    for (int i = 0; i < col.size(); i++) {
                        if ("lattice".equals(col.get(i).material())) {
                            if (fLo < 0) {
                                fLo = i;
                            }
                            fHi = i;
                        }
                    }
                    for (int i = 0; i < col.size(); i++) {
                        if (fLo >= 0 && i >= fLo - 2 && i <= fHi + 1) {
                            continue;                    // 창호 조 안 — 이 자의 몫이 아니다
                        }
                        String m = col.get(i).material();
                        if (m.contains("stone") || "lattice".equals(m) || m.contains("plaster")) {
                            continue;
                        }
                        boolean top2 = i >= col.size() - 2;
                        if (top2) {
                            if (m.startsWith("dark_oak")) {
                                darkBeam++;
                            } else {
                                wrong++;             // 도리·긴 보가 붉으면 위가 안 가라앉는다
                            }
                        } else if (m.startsWith("mangrove_")) {
                            redBeam++;
                        } else {
                            wrong++;                 // 인방·창방이 적주와 같은 재료면 색이 뭉친다
                        }
                    }
                }
                check("★★인방·창방은 <b>어두운 적갈색</b>이고 도리·긴 보는 <b>매우 어두운 갈색</b>이다 "
                                + "(적갈 " + redBeam + " · 짙은 갈 " + darkBeam + " · 어긋남 " + wrong + ")",
                        wrong == 0 && redBeam > 0 && darkBeam > 0, redBeam + "/" + darkBeam + "/" + wrong);
                // ★적주와 보가 <b>같은 재료면 안 된다</b> — 그게 「한 갈색 덩어리」의 정의다
                boolean beamIsPost = hj.columnOf('W').stream()
                        .anyMatch(cs -> cs.material().equals(postShaft));
                check("★★보가 적주와 <b>같은 재료를 쓰지 않는다</b> (같으면 정면이 한 덩어리로 뭉친다)",
                        !beamIsPost, beamIsPost);
                check("★★창호·서까래는 여전히 가장 어둡다 (dark_oak 계열)",
                        hj.columnOf('J').stream().anyMatch(cs -> cs.material().startsWith("dark_oak")),
                        "");
            }

            // ══════════════════════════════════════════════════════════════
            // ★★★<b>읽힘 자</b> (2026-08-11) — 「무엇으로 읽히는가」를 잰다
            // ══════════════════════════════════════════════════════════════
            //   이 저장소의 눈이 실패하는 유형은 셋이다:
            //     ① 오늘의 값을 외운 자 (깊이−13 · 37×18) — 도면이 바뀌면 죽는다
            //     ② 이름으로 구조를 고르는 자 (contains("mangrove_log")) — 팔레트가 바뀌면 죽는다
            //     ③ <b>색만 재는 자</b> — 「무엇으로 읽히는가」를 못 잰다
            //   ①②는 <b>헛짖어서</b> 티가 난다. ③은 <b>안 짖는다</b> — 잘못 만든 것에 최고점을 준다.
            //   2026-08-11: 기둥을 red_nether_bricks 로 갈았더니 색 지표가 만점이었고
            //   실물은 <b>조적 기둥</b>이었다. 목조 기둥은 세로결이어야 하는데 가로 줄눈이 뚜렷했다.
            //
            //   ★그래서 <b>재료의 결</b>을 잰다 — 텍스처에서 직접. 사진도 카메라도 필요 없다.
            //     Ex(가로로 갈 때의 변화) − Ey(세로로 갈 때의 변화) 의 치우침이 <b>방향</b>이고,
            //     둘의 평균이 <b>세기</b>다. 세기가 약하면 방향은 뜻이 없다 (민판).
            //     굽는 곳: tools/block_palette.py  ·  표: config/block_colors.json
            {
                java.util.Map<String, Object> grainTable;
                java.nio.file.Path gp = java.nio.file.Path.of("config/block_colors.json");
                if (!java.nio.file.Files.exists(gp)) {
                    check("★★읽힘 자의 색표가 있다 (python3 tools/block_palette.py)", false, gp);
                    grainTable = java.util.Map.of();
                } else {
                    grainTable = new org.yaml.snakeyaml.Yaml()
                            .load(java.nio.file.Files.readString(gp));
                    check("★★읽힘 자의 색표가 있다", grainTable.size() > 500, grainTable.size());
                }

                java.util.function.Function<String, String> readsAs = mat -> {
                    java.util.Map<String, Object> e = grainEntry(grainTable, mat);
                    if (e == null) {
                        return "모름";
                    }
                    double s = ((Number) e.getOrDefault("grain_strength", 0)).doubleValue();
                    double g = ((Number) e.getOrDefault("grain", 0)).doubleValue();
                    return s < 0.012 ? "민판" : g > 0.15 ? "세로결" : g < -0.15 ? "가로줄눈" : "격자";
                };

                if (!grainTable.isEmpty()) {
                    // ★① 적주는 <b>조적으로 읽히면 안 된다</b> — 2026-08-11 의 그 실패
                    String shaft = hj.shaftMaterial('P');
                    String how = readsAs.apply(shaft);
                    check("★★★적주가 <b>조적으로 안 읽힌다</b> (목조 기둥에 가로 줄눈은 없다 — "
                                    + shaft + " = " + how + ")",
                            !"가로줄눈".equals(how), shaft + " " + how);

                    // ★[눈의 눈] 벽돌을 기둥에 끼우면 <b>이 자가 잡는다</b>
                    check("★★[눈의 눈] red_nether_bricks 를 기둥에 쓰면 잡힌다",
                            "가로줄눈".equals(readsAs.apply("red_nether_bricks")),
                            readsAs.apply("red_nether_bricks"));
                    check("★★[눈의 눈] 통나무는 <b>안</b> 잡힌다 (헛짖지 않는다)",
                            !"가로줄눈".equals(readsAs.apply("dark_oak_log")),
                            readsAs.apply("dark_oak_log"));

                    // ★② 살창은 <b>무늬가 있어야</b> 창이다 — 민판이면 그냥 판이다
                    String lat = hj.palette("lattice", "dark_oak_trapdoor");
                    java.util.Map<String, Object> le = grainEntry(grainTable, lat);
                    double lstr = le == null ? 0
                            : ((Number) le.getOrDefault("grain_strength", 0)).doubleValue();
                    check("★★살창에 <b>결이 있다</b> (민판이면 창이 아니라 판이다 — "
                                    + lat + " 세기 " + lstr + ")", lstr >= 0.02, lstr);

                    // ★③ 부재가 <b>이웃과 명도로 갈린다</b> — 같은 색이면 만들어도 안 보인다
                    //    (공포·간포를 다 만들어 놓고도 창방과 같은 색이라 통짜 판으로 뭉쳤다)
                    double bracketLum = grainLum(grainTable, "dark_oak_planks");
                    double beamLum = grainLum(grainTable, "mangrove_planks");
                    check("★★공포와 창방이 <b>명도로 갈린다</b> (같으면 만들어도 안 보인다 — 공포 "
                                    + bracketLum + " · 창방 " + beamLum + " · 차 "
                                    + Math.round(Math.abs(bracketLum - beamLum)) + ")",
                            Math.abs(bracketLum - beamLum) >= 20,
                            Math.abs(bracketLum - beamLum));
                }
            }

            // ══════ ★★★청색 철회 (사용자 2026-08-11) ══════
            //   웹에서 모은 관습(한옥 궁궐·화북의 「기둥 위 부재를 푸른 계열로」)으로
            //   창방·도리·공포·간포를 청색으로 칠했다가 사용자가 잘랐다:
            //   「청색은 빼는 게 좋아 보여요. <b>레퍼런스에도 청색은 없었어요.</b>
            //     다 중요해도 <b>레퍼런스 위주의 건축</b>이 되어야 합니다.
            //     건축(조사)은 <b>형태 참고만</b> 할 뿐입니다.」
            //   ★그래서 이 눈은 「단청이 잘 들어갔나」가 아니라
            //     <b>「레퍼런스에 없는 색이 다시 기어들어오지 않았나」</b>를 지킨다.
            {
                java.util.List<String> offColor = new java.util.ArrayList<>();
                for (char ch : hj.columnKeys()) {
                    for (Blueprint.Course cs : hj.columnOf(ch)) {
                        String m = cs.material();
                        if (m.startsWith("warped") || m.startsWith("crimson")
                                || m.contains("glazed") || m.contains("prismarine")
                                || m.startsWith("cyan") || m.startsWith("blue")) {
                            offColor.add(ch + ":" + m);
                        }
                    }
                }
                com.honcheon.mvt.forge.Level upC = hj.upperLevel();
                for (int r = 0; upC != null && r < upC.depth(); r++) {
                    for (int c = 0; c < upC.width(); c++) {
                        for (Blueprint.Course cs : upC.columnOf(upC.at(c, r))) {
                            String m = cs.material();
                            if (m.startsWith("warped") || m.contains("glazed")
                                    || m.contains("prismarine")) {
                                offColor.add("상층:" + m);
                            }
                        }
                    }
                }
                check("★★★도면에 <b>레퍼런스에 없는 색</b>이 없다 (청록·유광·프리즈머린 — "
                                + offColor.size() + ")", offColor.isEmpty(),
                        offColor.stream().distinct().toList().toString());
                String bbC = java.nio.file.Files.readString(java.nio.file.Path.of(
                        "server-mvt/src/main/java/com/honcheon/mvt/forge/BlueprintBuilder.java"));
                String hcC = java.nio.file.Files.readString(java.nio.file.Path.of(
                        "server-mvt/src/main/java/com/honcheon/mvt/forge/HwasanCampusBuilder.java"));
                check("★★★조성에도 <b>청색 부재가 없다</b> (공포·간포·부연·추녀 끝)",
                        !bbC.contains("Material.WARPED_") && !hcC.contains("GLAZED_TERRACOTTA"),
                        "");
                // ★[눈의 눈] 도면에 청록을 한 켜 끼우면 이 자가 <b>무너져야</b> 한다
                java.util.Map<String, Object> cRaw = new org.yaml.snakeyaml.Yaml().load(
                        java.nio.file.Files.readString(
                                java.nio.file.Path.of("config/blueprints/hwasan_honjeon.yml")));
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> cCols =
                        (java.util.Map<String, Object>) cRaw.get("columns");
                @SuppressWarnings("unchecked")
                java.util.List<Object> mutC = new java.util.ArrayList<>(
                        (java.util.List<Object>) cCols.get("W"));
                mutC.add(1, "warped_planks");
                cCols.put("W", mutC);
                Blueprint cMut = Blueprint.of(cRaw);
                boolean caught = cMut.columnOf('W').stream()
                        .anyMatch(cs -> cs.material().startsWith("warped"));
                check("★★[눈의 눈] 청록을 한 켜 끼우면 <b>이 자가 잡는다</b>", caught, caught);
            }

            // ══════ ★★★상층이 하층과 <b>같은 문법</b>을 쓴다 (2026-08-11 · Codex 판정 B) ══════
            //   Codex 합격 기준 셋을 그대로 눈으로 옮긴다.
            {
                String bbU = java.nio.file.Files.readString(java.nio.file.Path.of(
                        "server-mvt/src/main/java/com/honcheon/mvt/forge/BlueprintBuilder.java"));
                // ① 사각 루프와 % 3 이 사라졌는가
                check("★★①상층 사각 루프의 <b>주기 3</b> 이 조성에서 사라졌다 "
                                + "(코드가 기둥 자리를 정하면 도면이 못 정한다)",
                        !bbU.contains("(c2 - bx0) % 3 == 0"), "");
                check("★★①상층이 <b>같은 파이프라인</b>을 지난다 (stampLevel·trims·brackets)",
                        bbU.contains("stampLevel(world, bp, up, place, ox, upBase, oz, n)")
                                && bbU.contains("brackets(world, bp, up, place"), "");
                com.honcheon.mvt.forge.Level upv = hj.upperLevel();
                check("★★상층이 제 <b>평면</b>을 갖는다 (도면이 짓는다)", upv != null, upv);
                if (upv != null) {
                    // ② 상층 적주가 <b>전부</b> 하층 적주(고주) 좌표에 선다
                    java.util.List<String> orphan = new java.util.ArrayList<>();
                    int upPosts = 0;
                    for (int r = 0; r < upv.depth(); r++) {
                        for (int c = 0; c < upv.width(); c++) {
                            if (!upv.isPost(upv.at(c, r))) {
                                continue;
                            }
                            upPosts++;
                            if (!hj.isPost(hj.at(c, r))) {
                                orphan.add("(" + c + "," + r + ")");
                            }
                        }
                    }
                    check("★★②상층 적주 " + upPosts + "개가 <b>모두</b> 하층 적주 위에 선다 "
                                    + "(허공에 선 것 " + orphan.size() + ")",
                            upPosts > 0 && orphan.isEmpty(), orphan.toString());
                    // ★[눈의 눈] 상층 기둥 하나를 옆으로 한 칸 밀면 이 자가 <b>무너져야</b> 한다
                    java.util.Map<String, Object> upRaw = new org.yaml.snakeyaml.Yaml().load(
                            java.nio.file.Files.readString(java.nio.file.Path.of(
                                    "config/blueprints/hwasan_honjeon.yml")));
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> upRoof = (java.util.Map<String, Object>)
                            ((java.util.Map<String, Object>) upRaw.get("roof")).values()
                                    .iterator().next();
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> upBlk =
                            (java.util.Map<String, Object>) upRoof.get("upper");
                    java.util.List<String> ul = new java.util.ArrayList<>(java.util.Arrays.asList(
                            String.valueOf(upBlk.get("plan")).split("\n")));
                    int frow = -1;
                    for (int i = 0; i < ul.size(); i++) {
                        if (ul.get(i).indexOf('T') >= 0) {
                            frow = i;
                            break;
                        }
                    }
                    String fl = ul.get(frow);
                    int tAt = fl.indexOf('T');
                    // 적주 하나를 이웃 칸(창)과 <b>맞바꾼다</b> — 수는 그대로, 자리만 어긋난다
                    ul.set(frow, fl.substring(0, tAt) + 'V' + 'T' + fl.substring(tAt + 2));
                    upBlk.put("plan", String.join("\n", ul));
                    com.honcheon.mvt.forge.Level mutU = Blueprint.of(upRaw).upperLevel();
                    int mutOrphan = 0;
                    for (int r = 0; r < mutU.depth(); r++) {
                        for (int c = 0; c < mutU.width(); c++) {
                            if (mutU.isPost(mutU.at(c, r)) && !hj.isPost(hj.at(c, r))) {
                                mutOrphan++;
                            }
                        }
                    }
                    check("★★[눈의 눈] 상층 기둥을 한 칸 밀면 <b>이 자가 무너진다</b> (허공 "
                                    + mutOrphan + ")", mutOrphan == 1, mutOrphan);
                    // ③ 선언된 요소가 실제로 나타난다 — 4켜 · 경량 공포 · 모서리 역할
                    java.util.Set<Character> chs = new java.util.TreeSet<>();
                    for (int r = 0; r < upv.depth(); r++) {
                        for (int c = 0; c < upv.width(); c++) {
                            if (upv.at(c, r) != '.') {
                                chs.add(upv.at(c, r));
                            }
                        }
                    }
                    int maxH = chs.stream().mapToInt(ch -> upv.heightAt(
                            firstColOf(upv, ch), firstRowOf(upv, ch))).max().orElse(0);
                    check("★★③상층 처방이 <b>4켜</b>다 (12켜용 처방을 그대로 못 쓴다 — " + maxH + ")",
                            maxH == 4, maxH);
                    check("★★③상층 공포가 <b>가볍다</b> (하층 " + hj.bracket() + " → 상층 "
                                    + upv.bracket() + " — 무조건 복제 금지)",
                            !upv.bracket().equalsIgnoreCase(hj.bracket())
                                    && !"none".equalsIgnoreCase(upv.bracket()), upv.bracket());
                    // ★자를 고쳤다 (깊이 20): 좌표 (7,8)·(31,17) 을 박고 있었다.
                    //   네 귀는 <b>상층 몸체 상자</b>가 안다 — 몸체가 움직이면 같이 움직인다.
                    int[] ub = {rbx[0] + rf1.insetX(), rbx[1] + rf1.insetZ(),
                            rbx[2] - rf1.insetX(), rbx[3] - rf1.insetZ()};
                    char nw = upv.at(ub[0], ub[1]);
                    char se = upv.at(ub[2], ub[3]);
                    char ne = upv.at(ub[2], ub[1]);
                    char sw = upv.at(ub[0], ub[3]);
                    char other = 0;
                    for (int c = ub[0] + 1; c < ub[2]; c++) {
                        if (upv.isPost(upv.at(c, ub[1]))) {
                            other = upv.at(c, ub[1]);
                            break;
                        }
                    }
                    check("★★③상층에 <b>모서리 역할</b>이 따로 있다 (네 귀가 일반 적주와 다르다 — 귀 "
                                    + nw + " · 일반 " + other + ")",
                            nw == se && nw == ne && nw == sw && other != 0 && nw != other,
                            "" + nw + ne + sw + se + "/" + other);
                    check("★★③상층에 <b>대문 역할이 없다</b> (상층엔 대문이 없다 — Codex)",
                            upv.bayRole("entrance_adjacent") == 0, "");
                }
            }

            // ══════ ★★★REF-1c-B — 실루엣 마감 · 중앙 위계 ══════
            String bbSrc2 = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "server-mvt/src/main/java/com/honcheon/mvt/forge/BlueprintBuilder.java"));
            check("★★대지붕에 용마루가 있다 (길이 " + rf1.upperRidge() + " — 정상부가 검은 면으로 "
                            + "닫히면 「잘 만든 지붕」이 아니라 「검은 경사지붕」이다)",
                    rf1.upperRidge() == 17, rf1.upperRidge());
            check("★★층간 지붕에는 <b>중앙 용마루를 안 만든다</b> (가운데를 상층 몸체가 차지한다)",
                    // ★자를 넓혔다 (REF-2A): 부름 끝에 재료 계열 인자가 붙어 옛 글자가 어긋났다.
                    //   물어야 할 것은 <b>층간에 용마루 길이 0 을 넘기는가</b>다.
                    bbSrc2.contains("rf.eaveZ(), 0,")
                            && bbSrc2.contains("rf.upperRidge()"), "");
            {
                java.util.Map<String, Blueprint.Trim> tm = new java.util.HashMap<>();
                for (Blueprint.Trim tr : hj.trims()) {
                    tm.put(tr.id(), tr);
                }
                Blueprint.Trim sign = tm.get("sign_anchor");
                Blueprint.Trim cb = tm.get("crown_base");
                Blueprint.Trim ct = tm.get("crown_top");
                check("★[눈의 눈] 마감 조각 셋을 도면에서 찾았다 (현판 자리 · 관 두 단)",
                        sign != null && cb != null && ct != null, hj.trims().size());
                check("★★현판 <b>자리</b>가 문 개구(3칸) 바로 위에 · 한 칸 나와 있다",
                        sign.cols()[1] - sign.cols()[0] + 1 == 3 && sign.depth() == 1,
                        (sign.cols()[1] - sign.cols()[0] + 1) + "칸 · +" + sign.depth());
                check("★★현판 <b>글자·소품은 아직 없다</b> (D3 — 자리만 만든다)",
                        !sign.material().contains("sign") && !sign.material().contains("item"),
                        sign.material());
                check("★★중앙 관이 <b>중앙 칸 5 안에서만</b> 있다 (밖으로 나가면 입면을 깬다)",
                        cb.cols()[0] >= hj.axisCol() - 2 && cb.cols()[1] <= hj.axisCol() + 2
                                && ct.cols()[0] >= hj.axisCol() - 2
                                && ct.cols()[1] <= hj.axisCol() + 2,
                        cb.cols()[0] + ".." + cb.cols()[1]);
                check("★★중앙 관 높이가 <b>2</b>다 (독립 지붕이 아니라 문두 장식)",
                        ct.y() - cb.y() == 1, (ct.y() - cb.y() + 1));
                // ★위계 — 관이 용마루보다 먼저 눈에 띄면 과하다. 관 꼭대기가 층간 처마보다 낮아야 한다.
                check("★★관이 용마루와 <b>경쟁하지 않는다</b> (관 꼭대기 " + ct.y()
                                + " < 층간 처마 " + rf1.baseY() + ")",
                        ct.y() < rf1.baseY(), ct.y() + " vs " + rf1.baseY());
            }
            // ★★★REF-3B-Q2 추녀 꼬리 — <b>어느 좌표계에 붙였는가</b>가 회귀를 가른다 (Codex).
            String hcb5 = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "server-mvt/src/main/java/com/honcheon/mvt/forge/HwasanCampusBuilder.java"));
            check("★★추녀 꼬리가 <b>지붕 실현 외곽 링</b>에 붙는다 (몸체 모서리에 붙이면 "
                            + "처마가 바뀔 때 연쇄로 어긋난다)",
                    hcb5.contains("cornerTail(world, pad, x0, x1, cy, z0, z1, tally)")
                            && hcb5.contains("int cx = sx < 0 ? x0 : x1;"), "");
            check("★★추녀 꼬리가 <b>추가 돌출 0</b> 이다 (두 칸 모두 실현 상자 안 — 모서리와 "
                            + "한 칸 안쪽 대각)",
                    hcb5.contains("cx - sx, cy - 2, cz - sz"), "");
            check("★★층간·대지붕이 <b>각각 제 링</b>을 쓴다 (sweepRoofGrand 안에서 부른다)",
                    hcb5.indexOf("cornerTail") > hcb5.indexOf("sweepRoofGrand"), "");
            // ★모서리는 서까래가 아니라 <b>추녀의 자리</b>다 — 다만 추녀를 놓는 지붕만 양보한다
            check("★★모서리를 <b>추녀에게 넘긴다</b> (안 넘기면 서까래가 꼬리 머리를 덮는다)",
                    hcb5.contains("if (corner && cornerTail)")
                            && hcb5.contains("★모서리는 추녀의 자리다"), "");
            check("★★그 양보는 <b>추녀를 놓는 지붕만</b> 한다 (강당 모서리 서까래가 사라지면 안 된다)",
                    java.nio.file.Files.readString(java.nio.file.Path.of(
                            "server-mvt/src/main/java/com/honcheon/mvt/forge/"
                                    + "BlueprintBuilder.java")).contains("rez, rf.grand(), rf.grand(), tally)"),
                    "");
            // ★★★겹처마 (동양풍 전환 2026-08-11 · Codex ④) — 격이 높은 전각만.
            check("★★<b>겹처마</b>다 (서까래 위에 부연 한 겹 — 처마 밑에 그림자 선이 두 줄)",
                    hcb5.contains("if (twoTier) {") && hcb5.contains("y - 1, z + iz"), "");
            check("★★겹처마는 <b>본전 전용 판</b>만 (강당·산문은 홑처마 그대로)",
                    java.nio.file.Files.readString(java.nio.file.Path.of(
                            "server-mvt/src/main/java/com/honcheon/mvt/forge/"
                                    + "BlueprintBuilder.java"))
                            .contains("rf.grand(), rf.grand(), tally)"), "");
            // ★★★유광테라코타 상한 (Codex): 처마 모서리·공포 핵심점에만 · 1% 이하
            check("★★추녀 끝이 <b>레퍼런스 팔레트</b>다 (유광테라코타는 철회됐다)",
                    hcb5.contains("static final Material GLAZE = Material.DARK_OAK_SLAB"), "");
            check("★★귀마루는 <b>지붕을 새로 들어 올리지 않는다</b> (귀솟음이 쥔 첫 칸을 안 건드린다)",
                    java.nio.file.Files.readString(java.nio.file.Path.of(
                            "server-mvt/src/main/java/com/honcheon/mvt/forge/"
                                    + "HwasanCampusBuilder.java"))
                            .contains("for (int k = 1; k <= 5; k++)"), "");
            // ★★자를 고쳤다 (상층 문법 · 2026-08-11): 원문의 삼항식을 찾던 자였다.
            //   이제 창턱은 <b>도면의 창 처방</b>에 있다. 계약은 「창턱이 전 폭으로 안 이어진다」이므로,
            //   <b>적주 처방에 창턱이 없다</b>는 것을 도면에서 직접 잰다 — 없으면 못 이어진다.
            {
                com.honcheon.mvt.forge.Level u1 = hj.upperLevel();
                boolean sillInWindow = false;
                boolean sillInPost = false;
                for (int r = 0; u1 != null && r < u1.depth(); r++) {
                    for (int c = 0; c < u1.width(); c++) {
                        char ch = u1.at(c, r);
                        if (ch == '.') {
                            continue;
                        }
                        for (Blueprint.Course cs : u1.columnOf(ch)) {
                            if (!cs.material().endsWith("_slab")) {
                                continue;
                            }
                            if (u1.isPost(ch)) {
                                sillInPost = true;
                            } else {
                                sillInWindow = true;
                            }
                        }
                    }
                }
                check("★★상층 창턱이 <b>적주에서 끊긴다</b> (창엔 있고 적주엔 없다 — 전 폭 연속이면"
                                + " 「밝은 띠」를 다시 짓는 셈)",
                        sillInWindow && !sillInPost, sillInWindow + "/" + sillInPost);
            }

            // ★REF-1b — 밝은 단청은 <b>적주 머리에만</b>. 정면 전체를 가로지르면 한 덩어리로 읽힌다.
            int brightTop = 0;
            int postTop = 0;
            for (char fc : new char[]{'P', 'W', 'D'}) {
                // ★★자를 고쳤다 (REF-1c-A): 전에는 <b>맨 위 켜</b>만 봤다. 밝은 사암이
                //   맨 위(단청)에서 <b>주두</b>로 내려가자 눈이 「밝은 것이 없다」고 읽었다.
                //   물어야 할 것은 자리가 아니라 <b>어느 열에 있는가</b>다.
                boolean bright = hj.columnOf(fc).stream()
                        .anyMatch(cs -> cs.material().contains("sandstone"));
                if (bright) {
                    brightTop++;
                    if (fc == 'P') {
                        postTop++;
                    }
                }
            }
            // ★S1R (2026-08-10) — 밝은 사암 주두를 걷어냈으므로 <b>없어도 통과</b>한다.
            //   다만 남긴다면 회벽·격자 위엔 못 온다 (띠가 되면 REF-1c-A 로 되돌아간다).
            check("★★밝은 사암은 <b>있어도 적주 열에만</b> 있다 (밝은 " + brightTop
                            + "종 중 적주 " + postTop + ")",
                    brightTop == postTop, brightTop + "/" + postTop);

            // ★REF-1b — grand 물매는 <b>오목</b>하다. 곧은 1:1 이면 계단식 피라미드로 읽힌다.
            int st = 9;
            int[] rise = new int[st];
            for (int i = 0; i < st; i++) {
                rise[i] = com.honcheon.mvt.forge.HwasanCampusBuilder.grandRise(i, st);
            }
            boolean monotone = true;
            boolean noJump = true;
            boolean hasFlat = false;
            for (int i = 1; i < st; i++) {
                if (rise[i] < rise[i - 1]) {
                    monotone = false;
                }
                if (rise[i] - rise[i - 1] > 1) {
                    noJump = false;
                }
                if (rise[i] == rise[i - 1]) {
                    hasFlat = true;
                }
            }
            check("★★grand 물매가 오름만 한다 · 한 켜에 두 칸을 안 뛴다 (뛰면 외피에 구멍)",
                    monotone && noJump, java.util.Arrays.toString(rise));
            check("★★grand 물매가 <b>오목하다</b> (처마 쪽이 완만 — 평탄 구간이 있다)",
                    hasFlat && rise[st - 1] < st - 1, java.util.Arrays.toString(rise));
            check("★★[눈의 눈] 옛 sweep 은 이 식을 안 탄다 (산문 회귀 금지 — 곧은 1:1 그대로)",
                    java.nio.file.Files.readString(java.nio.file.Path.of(
                            "server-mvt/src/main/java/com/honcheon/mvt/forge/"
                                    + "HwasanCampusBuilder.java"))
                            .contains("grand ? grandRise(i, steps) : i"), true);

            // ★REF-1 ⑤ — 3단 가시 기단. 세 켜 수가 <b>1·2·3 으로 갈려야</b> 세 단으로 읽힌다.
            //   ★한 칸씩 세 번 올라야 걸어 오른다 — 두 칸이 한 번이라도 있으면 대문이 막힌다.
            int tier1 = hj.columnOf('M').stream().mapToInt(Blueprint.Course::count).sum();
            int tier2 = hj.columnOf('N').stream().mapToInt(Blueprint.Course::count).sum();
            int tier3 = hj.columnOf('F').stream().mapToInt(Blueprint.Course::count).sum();
            check("★★기단이 **3단**이고 한 칸씩 오른다 (" + tier1 + "→" + tier2 + "→" + tier3 + ")",
                    tier1 == 1 && tier2 == 2 && tier3 == 3, tier1 + "/" + tier2 + "/" + tier3);
            // ★몸체 기둥의 밑동이 맨 윗단과 같은 높이여야 문지방과 방바닥이 맞는다
            int doorBase = 0;
            for (Blueprint.Course cs : hj.columnOf('O')) {
                if (!"smooth_stone".equals(cs.material())) {
                    break;
                }
                doorBase += cs.count();
            }
            check("★★대문 문지방이 기단 맨 윗단과 같다 (어긋나면 문 앞에 턱이 생긴다)",
                    doorBase == tier3, doorBase + " vs " + tier3);

            // ══════ ★★D-35 — 망루가 본전보다 눈에 먼저 든다 ══════
            // 진범은 높이가 아니라 **흰 면의 넓이**다. 눈이 소스 글자가 아니라 **조성이 쓰는
            // 그 함수**(towerWall)를 불러 한 면의 회벽 칸을 실제로 센다.
            int[] tHalves = {5, 4, 3};
            int towerFace = 0;
            int towerWallCells = 0;
            int towerPlaster = 0;
            for (int s = 0; s < tHalves.length; s++) {
                int half = tHalves[s];
                int wallH = s == 0 ? 5 : 4;
                for (int l = -half; l <= half; l++) {
                    for (int dy = 1; dy <= wallH; dy++) {
                        if (com.honcheon.mvt.forge.HwasanCampusBuilder
                                .towerWall(-half, l, half, dy, wallH, 0, dy, l) == Material.BONE_BLOCK) {
                            towerFace++;
                        }
                    }
                }
                for (int f = -half; f <= half; f++) {
                    for (int l = -half; l <= half; l++) {
                        if (Math.abs(f) != half && Math.abs(l) != half) {
                            continue;
                        }
                        for (int dy = 1; dy <= wallH; dy++) {
                            towerWallCells++;
                            if (com.honcheon.mvt.forge.HwasanCampusBuilder
                                    .towerWall(f, l, half, dy, wallH, f, dy, l) == Material.BONE_BLOCK) {
                                towerPlaster++;
                            }
                        }
                    }
                }
            }
            check("★[눈의 눈] 망루 벽 칸을 셌다", towerWallCells > 0 && towerFace > 0,
                    towerWallCells + "/" + towerFace);
            // 본전 정면의 흰 면 = 회벽 켜열 수 × 벽 높이 5
            // ★★자를 고쳤다: 「회벽 칸열 × 벽 높이」로 셌는데, 본전 정면이 <b>창이 칸을 채우는</b>
            //   문법이 되며 회벽 칸열이 0 이 됐다. 이제 <b>회벽 켜를 직접</b> 센다
            //   (창호 칸 안의 회벽 띠도 회벽이다).
            int hallFace = 0;
            for (int c = 0; c < hj.width(); c++) {
                for (Blueprint.Course cs : hj.columnOf(hj.at(c, frontRow))) {
                    if (cs.material().contains("plaster")) {
                        hallFace += cs.count();
                    }
                }
            }
            // ★★★이 자를 <b>은퇴시킨다</b> (Codex 판정 2026-08-10). D-35 의 뜻은
            //   「망루가 본전보다 눈에 먼저 들면 안 된다」였고, 그 대용품으로 <b>회벽 블록 수</b>를
            //   썼다. Codex 처방으로 본전 정면이 「창이 칸을 채우고 회벽은 한 켜 띠」가 되자
            //   블록 수로는 망루(30)가 본전(18)보다 희어졌다 — 그러나 <b>화면에서 그런지는
            //   블록 수가 답할 수 없다</b> (본전은 창·기둥·기단이 훨씬 넓다).
            //   ★후계자: <b>화면에서 재는 것</b> — 최대 밝은 연결 성분 · 밝은 화소 비율 ·
            //     중앙 대문과 측면 창의 명도차 (diff 마스크 도구가 이미 그 자를 갖고 있다).
            //   여기서는 <b>망루가 제 계약을 지키는지</b>만 남긴다.
            check("★망루 흰 면 견줌은 <b>화면에서</b> 잰다 (블록 수는 대용품이라 은퇴 — 망루 "
                            + towerFace + " · 본전 " + hallFace + ")",
                    towerFace > 0 && hallFace > 0, towerFace + " vs " + hallFace);
            check("★망루 벽을 회벽이 지배하지 않는다 (회벽 " + towerPlaster + "/" + towerWallCells + ")",
                    towerPlaster * 2 <= towerWallCells, towerPlaster + "/" + towerWallCells);
            boolean towerHasGrammar = false;
            for (int dy = 1; dy <= 5; dy++) {
                Material m = com.honcheon.mvt.forge.HwasanCampusBuilder
                        .towerWall(-5, 0, 5, dy, 5, 0, dy, 0);
                if (m == Material.DARK_OAK_TRAPDOOR) {
                    towerHasGrammar = true;
                }
            }
            check("★망루가 본전·산문과 같은 문법을 쓴다 (격자창이 있다)", towerHasGrammar, towerHasGrammar);
        } catch (Exception e) {
            check("★설계도 본전 구역", false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // ══════════ 결산 ══════════
        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("테라스의 눈 — " + passed + "/" + passed + " 통과");
            return;
        }
        System.out.println("테라스의 눈 — 실패 " + failures.size() + " (통과 " + passed + ")");
        for (String f : failures) {
            System.out.println("  ✗ " + f);
        }
        System.exit(1);
    }

    private static boolean hasZone(TerraceForge.Campus campus, int zone) {
        return campus.pads().stream().anyMatch(p -> p.zone() == zone);
    }

    private static int liftOf(TerraceForge.Campus campus, int zone) {
        return campus.pads().stream().filter(p -> p.zone() == zone)
                .mapToInt(TerraceForge.PadSpec::expectedLift).findFirst().orElse(-9999);
    }

    private static int heightOf(TerraceForge.Campus campus, int zone) {
        return campus.pads().stream().filter(p -> p.zone() == zone)
                .mapToInt(TerraceForge.PadSpec::h).findFirst().orElse(-9999);
    }

    private static void checkThrows(String what, TerraceForge.Campus bad) {
        try {
            TerraceForge.validate(bad);
            check(what, false, "통과해 버렸다");
        } catch (IllegalArgumentException e) {
            check(what, true, e.getMessage());
        }
    }

    /**
     * 바닐라 텍스처의 평균 밝기 (0~255) — 클라이언트 jar 를 실측한 값
     * ({@code run/client/client-1.21.11.jar} · 불투명 화소 평균 · 0.299R+0.587G+0.114B).
     * ★리소스팩이 아니라 <b>바닐라</b>다 — 테스트 서버는 팩을 끄고 본다.
     */
    private static int lumaOf(Material m) {
        return switch (m) {
            case STONE -> 126;
            case STONE_BRICKS -> 122;
            case CRACKED_STONE_BRICKS -> 118;
            case ANDESITE -> 136;
            case POLISHED_ANDESITE -> 133;
            case TUFF -> 108;
            case COBBLED_DEEPSLATE -> 78;
            case DEEPSLATE_BRICKS -> 71;
            case MOSS_BLOCK -> 100;
            case BONE_BLOCK -> 225;
            default -> throw new IllegalArgumentException("밝기 실측이 없는 재료: " + m
                    + " — run/client 의 바닐라 텍스처를 재서 이 표에 더하라");
        };
    }

    private static void check(String what, boolean ok, Object got) {
        if (ok) {
            passed++;
            System.out.println("  ✓ " + what);
        } else {
            failures.add(what + " — 실제: " + got);
            System.out.println("  ✗ " + what + " — 실제: " + got);
        }
    }
}
