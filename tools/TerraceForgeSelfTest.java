package com.honcheon.mvt;

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
        check("앵커: 종문 h76 (통단 B3)", heightOf(campus, 6) == 76, heightOf(campus, 6));
        check("앵커: 본전 h116", heightOf(campus, 9) == 116, heightOf(campus, 9));
        check("앵커: 장로회 h128", heightOf(campus, 12) == 128, heightOf(campus, 12));
        check("앵커: 정상 h148", heightOf(campus, 13) == 148, heightOf(campus, 13));

        // ══════════ ③ 계단 기하 — 아는 두 패드로 자로 잰다 ══════════
        TerraceForge.Campus two = new TerraceForge.Campus(
                List.of(new TerraceForge.PadSpec(1, "아래", 0, 20, 20, 16, 10),
                        new TerraceForge.PadSpec(2, "위", 0, -4, 20, 16, 20)),
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
                List.of(new TerraceForge.PadSpec(1, "아래", 0, 20, 20, 16, 10),
                        new TerraceForge.PadSpec(2, "위", 0, -4, 20, 16, 10)),
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
                List.of(new TerraceForge.PadSpec(1, "아래", 0, 90, 20, 16, 10),
                        new TerraceForge.PadSpec(2, "위", 0, 0, 20, 16, 16)),
                List.of(new TerraceForge.StairLink(2, 1, 'S'))));
        checkThrows("없는 구역을 부르는 링크는 거절", new TerraceForge.Campus(
                List.of(new TerraceForge.PadSpec(1, "갑", 0, 0, 16, 16, 10)),
                List.of(new TerraceForge.StairLink(1, 99, 'S'))));
        // ★슬라이스 2.5 — 계단 몸체가 남의 패드를 지나면 계획이 거절한다 (침범은 계획이 막는다)
        checkThrows("남의 패드를 지나는 계단은 거절", new TerraceForge.Campus(
                List.of(new TerraceForge.PadSpec(2, "위", 0, -4, 20, 16, 20),
                        new TerraceForge.PadSpec(1, "아래", 0, 24, 20, 16, 10),
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
        checkThrows("스팬 상한(80) 초과는 거절", new TerraceForge.Campus(
                List.of(new TerraceForge.PadSpec(1, "본산", 0, 0, 20, 16, 50),
                        new TerraceForge.PadSpec(2, "곁봉", 120, 0, 20, 16, 50)),
                List.of(),
                List.of(new TerraceForge.BridgeSpec("만용교", true, 0, 10, 109, 50))));
        // ★5.5 — expectedLift 는 통단(성곽 옹벽·의도된 깎기) 계약으로 확장됐다 · 한도 ±40 만 지킨다
        checkThrows("expectedLift ±40 밖은 거절", new TerraceForge.Campus(
                List.of(new TerraceForge.PadSpec(1, "만용", 0, 0, 20, 16, 50, 41)),
                List.of(), List.of()));
        check("성곽 계약: 3 연무장하 Δ19 · 6 종문 Δ-16 (깎기)",
                liftOf(campus, 3) == 19 && liftOf(campus, 6) == -16,
                liftOf(campus, 3) + "/" + liftOf(campus, 6));
        check("expectedLift 계약: 19 전망대 = 31 (석탑 위 전각)",
                liftOf(campus, 19) == 31, liftOf(campus, 19));
        check("expectedLift 계약: 105 서교 착지 = 20", liftOf(campus, 105) == 20, liftOf(campus, 105));
        check("expectedLift 계약: 20 부속 암자 = 20", liftOf(campus, 20) == 20, liftOf(campus, 20));
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
        check("산군: 배후봉 Pm 마루 h228 (정상단 148 + 80 — 실측 §4)",
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
        // 켜 3 — 각 환대(環帶)에 침봉이 실재하고, 근경 마루가 실측 창(110~170) 안
        int[] rings = new int[3];
        int r1max = 0;
        for (int gx = -600; gx <= 600; gx += 13) {
            for (int gz = -600; gz <= 600; gz += 13) {
                int hh = bare.targetH(gx, gz);
                if (hh < 50) {
                    continue;
                }
                double d = Math.hypot(gx, gz);
                if (d >= 130 && d < 260) {
                    rings[0]++;
                    if (d >= 140 && hh > r1max) {
                        r1max = hh;   // 배후봉 자락 밖에서만 잰다
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
        check("산군: 근경 침봉 마루가 실측 창(110~170) 안", r1max >= 110 && r1max <= 170, r1max);
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
            TerraceForge.Pad jangno = allPads.stream()
                    .filter(p -> p.spec().zone() == 12).findFirst().orElseThrow();
            java.util.List<int[]> full = com.honcheon.mvt.forge.HwasanCampusBuilder.structureBoxes(jangno);
            java.util.List<int[]> ground = com.honcheon.mvt.forge.HwasanCampusBuilder.groundBoxes(jangno);
            int[] fb = full.get(0);
            int[] gb = ground.get(0);
            check("★장로회 전고 상자가 처마를 덮는다 (17×7 홀 + 처마 → 21×11)",
                    fb[1] - fb[0] == 20 && fb[3] - fb[2] == 10,
                    (fb[1] - fb[0] + 1) + "×" + (fb[3] - fb[2] + 1));
            check("★장로회 지상 상자는 처마를 뺀다 (벽 17×7 — 통로 검증은 걷는 몸높이만)",
                    gb[1] - gb[0] == 16 && gb[3] - gb[2] == 6,
                    (gb[1] - gb[0] + 1) + "×" + (gb[3] - gb[2] + 1));
            TerraceForge.Pad garden = allPads.stream()
                    .filter(p -> p.spec().zone() == 10).findFirst().orElseThrow();
            int gcx = garden.x0() + garden.spec().width() / 2;
            int gcz = garden.zN() + garden.spec().depth() / 2;
            boolean plumBoxed = com.honcheon.mvt.forge.HwasanCampusBuilder.structureBoxes(garden).stream()
                    .anyMatch(b -> b[0] == gcx - 4 && b[1] == gcx && b[2] == gcz - 8 && b[3] == gcz - 4);
            check("★정원 매화 상자 = 수관 ±2 그대로 (5.6 재발 방지 — 손이 아니라 코드가 적는다)",
                    plumBoxed, "수관 상자 불일치");
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
