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
                if (p.spec().zone() != 1 && p.spec().zone() != 2) {
                    continue;   // 축선이 지나는 패드 (산문·외원)
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
            TerraceForge.Pad jangno = allPads.stream()
                    .filter(p -> p.spec().zone() == 12).findFirst().orElseThrow();
            java.util.List<int[]> full = com.honcheon.mvt.forge.HwasanCampusBuilder.structureBoxes(jangno);
            java.util.List<int[]> ground = com.honcheon.mvt.forge.HwasanCampusBuilder.groundBoxes(jangno);
            int[] fb = full.get(0);
            int[] gb = ground.get(0);
            check("★장로회 전고 상자가 처마를 덮는다 (17×7 홀 + 처마 → 21×11 · 척도 되돌림)",
                    fb[1] - fb[0] == 20 && fb[3] - fb[2] == 10,
                    (fb[1] - fb[0] + 1) + "×" + (fb[3] - fb[2] + 1));
            check("★장로회 지상 상자는 처마를 뺀다 (벽 19×9 — 통로 검증은 걷는 몸높이만)",
                    gb[1] - gb[0] == 18 && gb[3] - gb[2] == 8,
                    (gb[1] - gb[0] + 1) + "×" + (gb[3] - gb[2] + 1));
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
            check("★9 행각 — 외원 8부품(정자2+행각4+등롱열2) · 종문 5부품(문+행각4) — 9b 중앙 통로 갈림",
                    plazaParts == 8 && jongParts == 5, plazaParts + "/" + jongParts);
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
                    if (i < TerraceForge.TREE_CLEAR_DEPTH) {
                        pinesInDepth++;
                        if (TerraceForge.pineOff(i) <= TerraceForge.TREE_CLEAR_HALF) {
                            trunkInProjection++;
                        }
                    } else if (TerraceForge.pineOff(i) == TerraceForge.APPROACH_CLEAR + 2) {
                        pinesBeside++;
                    }
                }
                check("★소나무 줄기가 산문 정면 투영(반폭 " + TerraceForge.TREE_CLEAR_HALF
                                + " · 깊이 " + TerraceForge.TREE_CLEAR_DEPTH + ") 밖에 선다",
                        trunkInProjection == 0, trunkInProjection + "그루");
                // ★헛것을 지키는 눈 막기 — 그 깊이에 소나무가 <b>실제로</b> 서야 계약이 산다
                //   (한 그루도 안 서면 위 눈은 0 으로 조용히 통과한다. 실제로 가리던 것이 i15 다)
                check("★그 깊이에 소나무가 실재한다 (눈이 헛것을 지키지 않는다)",
                        pinesInDepth >= 1, pinesInDepth + "그루");
                check("★그 밖의 소나무는 그대로 곁에 선다 (밀어내기만 한 것이 아니다)",
                        pinesBeside >= 2, pinesBeside + "그루");
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
            int frontRow = hj.depth() - 13;          // 정면 벽 (row 19) — 도면 뒤에서 13번째
            int gateCols = 0;
            for (int c = 0; c < hj.width(); c++) {
                for (Blueprint.Course cs : hj.columnOf(hj.at(c, frontRow))) {
                    if ("air".equals(cs.material()) && cs.count() >= 5) {
                        gateCols++;
                        break;
                    }
                }
            }
            check("★본전 중앙 대문 폭 5 (걸어 지나는 개구)", gateCols == 5, gateCols);

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
                if (hj.at(c, frontRow) == 'P') {
                    posts.add(c);
                }
            }
            int badGap = 0;
            for (int i = 1; i < posts.size(); i++) {
                int gap = posts.get(i) - posts.get(i - 1);
                if (gap != 3 && gap != 6) {       // 6 = 대문(폭 5)을 사이에 낀 한 짝
                    badGap++;
                }
            }
            check("★★본전 적주가 3칸 주기다 (기둥 " + posts.size() + "개 · 어긋난 틈 " + badGap + ")",
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
                // ★★하층:상층 폭 비 — 7호 실측 38:25 = **0.66**. 코드에 물러남이 2 로 박혀
                //   있던 동안 이 값은 0.87 이었다 (상층이 하층만큼 넓은 다른 건물). 이제
                //   도면이 물러남을 정하므로, 그 값이 실측 비를 내는지 여기서 잰다.
                int lower = hjRoof.box()[2] - hjRoof.box()[0] + 1;
                int upper = lower - 2 * hjRoof.insetX();
                double ratio = (double) upper / lower;
                check("★본전 하층:상층 폭 비가 실측(0.66)에 든다 (" + lower + ":" + upper
                                + " = " + String.format("%.2f", ratio) + ")",
                        ratio >= 0.60 && ratio <= 0.75, ratio);
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
            check("★★산문과 본전의 하층 문법이 **같다** (둘 다 격자가 회벽 이상 — 산문 " + gDoors + "/"
                            + gWalls + " · 본전 " + hjDoors + "/" + hjWalls + ")",
                    gDoors >= gWalls && hjDoors >= hjWalls,
                    "산문 " + gDoors + "/" + gWalls + " · 본전 " + hjDoors + "/" + hjWalls);
            check("★본전이 산문보다 회벽을 더 쓴다 (실측 9.4% vs 4.3%)",
                    hjWalls > gWalls, hjWalls + " vs " + gWalls);

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
            //   낮아진다. 산문(다른 치수)에 대고 값이 **달라지는지** 재어 상수 박기를 잡는다.
            //   ※이 눈이 없으면 clearHeight 가 `return 64;` 여도 위 두 눈은 통과한다.
            int gateClear = com.honcheon.mvt.forge.BlueprintBuilder.clearHeight(gate);
            check("★★비우는 높이가 **도면을 따라 움직인다** (산문 " + gateClear + " ≠ 본전 "
                            + hjClear + " — 상수를 박으면 여기서 짖는다)",
                    gateClear != hjClear, gateClear + " vs " + hjClear);

            // ★조성이 실제로 **비우고 나서** 찍는가 — 순서가 뒤집히면 도면을 지운다.
            int clearCall = bb.indexOf("clearHeight(bp)");
            int stampLoop = bb.indexOf("for (Blueprint.Course course : bp.columnOf");
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
            int frontR = hj.depth() - 13;                 // 정면 벽 (row 19)
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
            int backR = frontR - 13;
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
            check("★★상층 살창도 세운다 (하층만 고치면 위층이 구멍으로 남는다)",
                    bb.indexOf("stand(Bukkit.createBlockData(fill), uf)") >= 0,
                    bb.indexOf("stand(Bukkit.createBlockData(fill), uf)"));

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
                if (ch == '.' || ch == 'M' || ch == 'P' || ch == 'O') {
                    continue;
                }
                checked++;
                java.util.List<Blueprint.Course> col = hj.columnOf(ch);
                // 벽 켜 = 기단(맨 아래 한 켜)과 도리·단청(맨 위 두 켜)을 뺀 가운데
                java.util.List<String> wallCourses = new java.util.ArrayList<>();
                for (Blueprint.Course cs : col) {
                    for (int k = 0; k < cs.count(); k++) {
                        wallCourses.add(cs.material());
                    }
                }
                if (wallCourses.size() < 4) {
                    continue;
                }
                java.util.List<String> mid = wallCourses.subList(1, wallCourses.size() - 2);
                for (String m : mid) {
                    if (m.contains("plaster")) {
                        cCream++;
                    }
                }
                // ★인방 — 칸의 벽 켜는 **위·아래가 붉은 켜로 끊겨야** 한다. 안 끊기면 회벽은
                //   통짜 빈 판이 되고 격자는 바닥부터 천장까지 이어져 「사다리」로 읽힌다.
                if (!mid.get(0).contains("mangrove") || !mid.get(mid.size() - 1).contains("mangrove")) {
                    noLintel++;
                }
            }
            check("★[눈의 눈] 본전 정면에서 기둥 아닌 칸을 찾았다", checked > 0, checked);
            check("★★칸마다 위·아래가 **붉은 인방으로 끊긴다** (안 끊기면 회벽은 통짜 빈 판이, "
                            + "격자는 사다리가 된다 — 안 끊긴 칸 " + noLintel + "/" + checked + ")",
                    noLintel == 0, noLintel + "/" + checked);
            check("★회벽이 사라지지는 않는다 (하층은 회벽이 **있는** 벽이다 — 상층과 다르다)",
                    cCream > 0, cCream);

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
            int hallFace = hjWalls * 5;
            check("★★망루 한 면의 흰 면이 본전 정면보다 좁다 (망루 " + towerFace
                            + " ≤ 본전 " + hallFace + ") — 위계는 면적이 정한다",
                    towerFace <= hallFace, towerFace + " vs " + hallFace);
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
