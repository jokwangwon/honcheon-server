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
        // 비석 둘이 서로 다른 참에 선다 (겹치면 한 쌍이 사라진 것과 같다)
        check("★비석 두 쌍이 서로 다른 자리 (하나가 조용히 사라지지 않는다)",
                TerraceForge.STELE_A != TerraceForge.STELE_B
                        && TerraceForge.STELE_A % TerraceForge.LANDING_EVERY == 0
                        && TerraceForge.STELE_B % TerraceForge.LANDING_EVERY == 0,
                TerraceForge.STELE_A + "/" + TerraceForge.STELE_B);
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
        // ★10-② 웜톤 암질 — 점적석(따뜻한 갈빛)이 섞이고 금지 재료가 없다
        boolean warm = false;
        boolean bannedStone = false;
        for (int s = 0; s < 400; s++) {
            Material m = com.honcheon.mvt.forge.SpireField.stone(s * 13, (s % 40) * 3, s * 7, false);
            if (m == Material.DRIPSTONE_BLOCK) {
                warm = true;
            }
            if (m == Material.BARREL || m == Material.LIGHT) {
                bannedStone = true;
            }
        }
        check("★10-② 웜톤 — 점적석 섞임 · barrel/light 없음", warm && !bannedStone,
                warm + "/" + bannedStone);
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
        // ②-이음매 — 산의 암질에 석전 계열이 섞여야 옹벽과 경계가 흐려진다
        boolean brickInRock = false;
        for (int s = 0; s < 900; s++) {
            Material m = com.honcheon.mvt.forge.SpireField.stone(s * 7, (s % 30) * 4, s * 11, false);
            if (m == Material.STONE_BRICKS || m == Material.MOSSY_STONE_BRICKS) {
                brickInRock = true;
            }
        }
        check("★15-② 이음매 흐림 — 암벽에 석전 계열이 섞인다 (축대와 한 계열)", brickInRock, brickInRock);
        // ★15 유출 오탐의 계약 — 늑재는 「산의 것」이라 스캔 밖이어야 한다 (조경 표와 같은 문법).
        //   실기동 오탐 8건(사암)의 처방: 암벽 재료 ∩ 유출 스캔 = ∅.
        java.util.Set<Material> rockMats = com.honcheon.mvt.forge.SpireField.rockMats();
        java.util.Set<Material> leakTbl = com.honcheon.mvt.forge.HwasanCampusBuilder.leakScanMats();
        java.util.Set<Material> both = java.util.EnumSet.copyOf(rockMats);
        both.retainAll(leakTbl);
        check("★15 암벽 ∩ 유출 스캔 = ∅ (늑재를 건물로 오인하지 않는다)", both.isEmpty(), both);
        // 그러나 건물 전용 재료는 여전히 잡혀야 한다 — 「빼기」가 스캔을 무력화하면 안 된다
        check("★15 건물 전용 재료는 스캔에 남는다 (백벽·기와·유리)",
                leakTbl.contains(Material.WHITE_TERRACOTTA)
                        && leakTbl.contains(Material.DEEPSLATE_TILES)
                        && leakTbl.contains(Material.GLASS_PANE), leakTbl.size());
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
        //   침봉 몸통 유지: 마루 열에서 3칸 비켜도 몸통(≥80%)이다 — 옛 (1−d²)^1.5 는 ~65% 라 실패한다
        int mx = 0, mz = 0, mh = 0;
        for (int gx = 300; gx <= 380; gx++) {
            for (int gz = 0; gz <= 80; gz++) {
                int hh = bare.targetH(gx, gz);
                if (hh > mh) {
                    mh = hh;
                    mx = gx;
                    mz = gz;
                }
            }
        }
        check("산군: 침봉 몸통 유지 — 마루 곁 3칸이 마루의 ≥80% (§4-b 실측 78% 몸통)",
                mh > 0 && bare.targetH(mx + 3, mz) >= (int) (mh * 0.80),
                mh + " → " + bare.targetH(mx + 3, mz));
        //   배후봉 병풍 비대칭: Pm(축 동서) 남(+z) 급벽이 북 완사보다 낮다 · 능선 방향은 횡보다 높다
        check("산군: 배후봉 Pm 비대칭 (남 급벽 < 북 완사 — §4-b 0.80/1.15)",
                bare.targetH(-24, -54 + 30) < bare.targetH(-24, -54 - 30),
                bare.targetH(-24, -24) + " vs " + bare.targetH(-24, -84));
        check("산군: 배후봉 Pm 능선꼴 (장축 20칸 > 급벽횡 20칸)",
                bare.targetH(-24 + 20, -54) > bare.targetH(-24, -54 + 20),
                bare.targetH(-4, -54) + " vs " + bare.targetH(-24, -34));
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
            check("★9 재료 — 기와 혼합(벽돌·균열)과 암반 늑재(자갈돌)가 팔레트에",
                    bPal.contains(Material.DEEPSLATE_BRICKS)
                            && bPal.contains(Material.CRACKED_DEEPSLATE_TILES)
                            && palette.contains(Material.COBBLESTONE), "9 재료");
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
            check("★13a-3 팔레트 웜톤 분화 — 축대 점적석·포장 사암이 표에 있다",
                    palette.contains(Material.DRIPSTONE_BLOCK)
                            && palette.contains(Material.SMOOTH_SANDSTONE), "웜톤");
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
            // ★척도 되돌림 — 월대 3 + 층 12 + 수렴 + 치미 = 23 (치미 솟음 문법은 그대로)
            int mainTopY = com.honcheon.mvt.forge.HwasanCampusBuilder.structureTopY(main2) - main2.y();
            check("★13c-② 본전 지붕 — 치미가 한 칸 더 솟는다 (총고 23 · 척도 되돌림)",
                    mainTopY == 23, mainTopY);
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
                int h = com.honcheon.mvt.forge.SpireField.faceRelief(100, 3000 + t, 3000);
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
            check("★16-④ 축대 면에 결이 실재한다 (매끈하면 실패 — 15 판정의 병)",
                    batter >= natural * 0.5,
                    String.format("축대 %.2f vs 산면 %.2f", batter, natural));
            check("★16-④ 그러나 무너지지는 않았다 (열 평균 2칸 이내 — 폐허 방지)",
                    batter <= 2.0, String.format("%.3f", batter));

            // ★16-㉡㉢ 구간마다 다르게 나간다 — 돌출·기준·홈이 공존해야 발치 선이 반듯하지 않다
            java.util.Set<Integer> reaches = new java.util.HashSet<>();
            for (int s = 0; s < 300; s++) {
                reaches.add(TerraceForge.batterStepsFor(s * 11, 0, 24, false));
            }
            check("★16-㉡㉢ 배터 칸수가 구간마다 갈린다 (돌출·기준·홈 — 최소 3종)",
                    reaches.size() >= 3, reaches);

            // ★16 결정론 — 두 번 물어도 같은 답 (난수 0)
            check("★16 결정론 — 같은 자리는 같은 결",
                    TerraceForge.batterRoughness(120, 77, -31, 1, 0, 10)
                            == TerraceForge.batterRoughness(120, 77, -31, 1, 0, 10),
                    "동일");
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
