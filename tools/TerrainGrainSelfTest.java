package com.honcheon.mvt;

import org.bukkit.Material;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;

/**
 * <b>점묘(點描)의 눈</b> — 땅이 <b>덩어리</b>인가, <b>낱알</b>인가.
 *
 * <p>사용자 판정:
 * <blockquote>"지형을 빚을 때 <b>점처럼 찍어버려서 깔끔한 필드가 아닌 한 칸 한 칸 다른 필드</b>가
 * 되어 버리는 경우가 있어요."</blockquote>
 *
 * <h2>무엇을 재는가</h2>
 * 표본 구획(128×128)에서 <b>이웃과 다른 칸</b>을 센다. 두 축이다:
 * <ul>
 *   <li><b>인접쌍 불일치율</b> — 맞닿은 두 칸이 서로 다른 비율. 깔끔한 필드는 <b>덩어리 경계에서만</b>
 *       달라지므로 낮다. 칸 단위 독립 선택은 거의 <b>매 쌍이</b> 다르다.</li>
 *   <li><b>외톨이율</b> — 이웃 넷과 <b>모두</b> 다른 칸의 비율. 점 하나가 홀로 찍힌 자리다
 *       (모래밭에 자갈 한 점 = 정확히 이것).</li>
 * </ul>
 * 문턱은 {@code config/terrain_grain.yml eye:} 가 정한다 — 코드가 아니라 등록부다.
 *
 * <h2>★★ 일부러 어긴다</h2>
 * 눈이 <b>무엇이든 통과시키는 눈</b>이면 눈이 아니다. 그래서 이 시험은 <b>구판(칸 단위 좌표 해시)을
 * 되살려 놓고</b> 같은 자를 들이댄다 — 구판이 <b>잡히지 않으면 이 시험이 실패한다.</b>
 * (구판은 실재했던 코드다: {@code floorMod(x*7 + z*11, 3) - 1} · {@code floorMod(x*31+z*17, 29)==0} …)
 *
 * <h2>★ 그리고 결정론</h2>
 * 점묘를 없애자고 <b>난수를 들이면</b> 그것은 더 큰 병이다 (같은 좌표가 다른 땅이 된다).
 * 그래서 <b>같은 좌표를 두 번 물어 같은 답이 오는가</b>도 잰다.
 *
 * <h2>★ 그리고 「밋밋함」</h2>
 * 온 들판을 잔디 한 장으로 덮으면 점묘율은 <b>0</b>이다 — 그리고 그것은 땅이 아니다.
 * 그래서 <b>결이 실제로 있는가</b>(값이 두 가지 이상, 소수 쪽도 최소 지분)도 함께 잰다.
 *
 * <h2>어떻게 돌리나 (저장소 루트에서 · 서버를 켜지 않는다)</h2>
 * <pre>
 *   export JAVA_HOME=$PWD/run/jdk-21
 *   ./gradlew :server-mvt:jar
 *   CP="$(find ~/.gradle -path '*1.21.11-R0.1-SNAPSHOT*' -name 'paper-api-*.jar' | head -1)"
 *   CP="$CP:$(find ~/.gradle -name '*.jar' | grep -E 'adventure-key|adventure-api|examination-api|snakeyaml-2.2' \
 *             | grep -v 26.1.2 | tr '\n' ':')$(find run/mvt/libraries -name 'guava-*.jar' | head -1)"
 *   CP="$CP:server-mvt/build/libs/server-mvt-1.0.0.jar"
 *   $JAVA_HOME/bin/javac -nowarn -d /tmp/grain-eye -cp "$CP" tools/TerrainGrainSelfTest.java
 *   $JAVA_HOME/bin/java -cp "$CP:/tmp/grain-eye" com.honcheon.mvt.TerrainGrainSelfTest
 * </pre>
 */
public final class TerrainGrainSelfTest {

    /** 표본 구획 — 한 변 (칸) */
    private static final int N = 128;

    /** 표본의 원점 — 0,0 은 격자와 우연히 맞을 수 있다. 일부러 어긋난 자리를 본다 */
    private static final int OX = 1337;
    private static final int OZ = -911;

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        TerrainForge.loadGrain(Path.of("config"));   // ★ 저장소의 진짜 등록부로 시험한다
        double speckleMax = TerrainForge.speckleMax();
        double isolatedMax = TerrainForge.isolatedMax();
        System.out.println("점묘의 눈 — 표본 " + N + "×" + N + " @ (" + OX + "," + OZ + ")");
        System.out.println("등록부(terrain_grain.yml): 인접쌍 불일치 ≤ " + speckleMax
                + " · 외톨이 ≤ " + isolatedMax + "\n");

        // ══════════ 지금의 땅 — 덩어리인가 ══════════
        System.out.println("【지금의 땅】 결(結) — 격자 잡음 + 보간");
        Map<String, IntBinaryOperator> now = new LinkedHashMap<>();
        now.put("지면 요철 (전이대·중턱단)", TerrainForge::undulation);
        now.put("들의 가장자리", TerrainForge::edgeDrop);
        now.put("사막 표층 자재", (x, z) -> TerrainForge.sandSkin(x, z).ordinal());
        now.put("설선의 굽이", TerrainForge::snowWobble);
        now.put("굴 바닥 자갈", (x, z) -> TerrainForge.caveGravel(x, z) ? 1 : 0);
        now.forEach((name, f) -> {
            Field field = sample(f);
            report(name, field);
            check(name + " — 인접쌍 불일치 " + pct(field.pairs) + " ≤ " + pct(speckleMax),
                    field.pairs <= speckleMax, "점묘다 — 한 칸 한 칸 다른 필드");
            check(name + " — 외톨이 " + pct(field.isolated) + " ≤ " + pct(isolatedMax),
                    field.isolated <= isolatedMax, "점이 홀로 찍혔다");
            // ★ 밋밋함도 결함이다 — 온통 한 값이면 점묘율은 0 이고, 그것은 땅이 아니다
            check(name + " — 결이 있다 (값 " + field.distinct + "종 · 소수 쪽 " + pct(field.minority) + ")",
                    field.distinct >= 2 && field.minority >= 0.02,
                    "밋밋하다 — 눈을 속이려고 땅을 지워 버린 꼴이다");
        });

        // ══════════ ★★ 일부러 어긴다 — 구판(칸 단위 좌표 해시)을 되살린다 ══════════
        System.out.println("\n【★ 일부러 어긴다】 구판 — 칸마다 독립으로 뽑던 좌표 해시");
        Map<String, IntBinaryOperator> old = new LinkedHashMap<>();
        //   실재했던 줄 그대로다 (TerrainForge 의 옛 코드)
        old.put("구판 요철  floorMod(x*7+z*11,3)-1", (x, z) -> Math.floorMod(x * 7 + z * 11, 3) - 1);
        old.put("구판 가장자리  floorMod(x*5+z*3,3)", (x, z) -> Math.floorMod(x * 5 + z * 3, 3));
        old.put("구판 사막  자갈 1/29 · 붉은모래 1/11", (x, z) ->
                Math.floorMod(x * 31 + z * 17, 29) == 0 ? Material.GRAVEL.ordinal()
                        : Math.floorMod(x * 13 + z * 7, 11) == 0 ? Material.RED_SAND.ordinal()
                        : Material.SAND.ordinal());
        old.put("구판 설선  floorMod(x*7+z*11,7)-3", (x, z) -> Math.floorMod(x * 7 + z * 11, 7) - 3);
        old.put("구판 굴 바닥  floorMod(x*13+z*7,6)==0", (x, z) ->
                Math.floorMod(x * 13 + z * 7, 6) == 0 ? 1 : 0);
        old.forEach((name, f) -> {
            Field field = sample(f);
            report(name, field);
            // ★ 여기서 통과하면 **눈이 먼 것이다**. 구판은 반드시 잡혀야 한다
            check("★ 눈이 구판을 잡는다 — " + name,
                    field.pairs > TerrainForge.speckleMax() || field.isolated > TerrainForge.isolatedMax(),
                    "눈이 점묘를 못 봤다 — 문턱이 무르거나 자가 틀렸다");
        });

        // ══════════ 결정론 — 같은 좌표는 언제나 같은 땅 ══════════
        System.out.println("\n【결정론】 난수를 다시 들이지 않았는가");
        boolean same = true;
        for (int i = 0; i < 5000 && same; i++) {
            int x = OX + i * 7;
            int z = OZ - i * 13;
            same = TerrainForge.undulation(x, z) == TerrainForge.undulation(x, z)
                    && TerrainForge.sandSkin(x, z) == TerrainForge.sandSkin(x, z)
                    && TerrainForge.grain2(x, z, 13) == TerrainForge.grain2(x, z, 13)
                    && TerrainForge.snowWobble(x, z) == TerrainForge.snowWobble(x, z)
                    && TerrainForge.caveGravel(x, z) == TerrainForge.caveGravel(x, z);
        }
        check("★ 같은 좌표 = 같은 답 (5000점)", same, "난수가 섞였다 — 조성기의 금기다");
        // 좌표를 옮기면 값이 달라야 한다 (상수 함수가 아니다 = 결정론을 밋밋함으로 때우지 않았다)
        check("★ 다른 좌표 = 다른 땅 (상수 함수가 아니다)",
                TerrainForge.grain2(OX, OZ, 13) != TerrainForge.grain2(OX + 40, OZ + 40, 13), "");

        // ══════════ cell=1 이면 옛 병으로 돌아간다 — 등록부의 뜻을 확인한다 ══════════
        System.out.println("\n【★ 등록부를 어긴다】 덩어리 크기를 1칸으로 (= 칸 단위 해시)");
        Field cell1 = sample((x, z) -> (int) Math.round(TerrainForge.grain(x, z, 1) * 3));
        report("cell=1 (덩어리가 한 칸)", cell1);
        check("★ 덩어리 크기 1 은 점묘가 된다 — 눈이 그것을 안다",
                cell1.pairs > TerrainForge.speckleMax(),
                "cell=1 인데도 통과했다 — 자가 무디다");

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("✔ 눈 " + passed + "개 — 전부 통과. 땅은 덩어리다");
        } else {
            System.out.println("✖ 실패 " + failures.size() + " / 통과 " + passed);
            failures.forEach(f -> System.out.println("  ✖ " + f));
            System.exit(1);
        }
    }

    // ══════════ 자(尺) ══════════

    /** 한 표본 구획의 성적 */
    private record Field(double pairs, double isolated, int distinct, double minority) {
    }

    /**
     * 표본을 뜬다 — <b>인접쌍 불일치율</b>과 <b>외톨이율</b>, 그리고 <b>결의 다양성</b>.
     *
     * <p>인접쌍: 오른쪽·아래 두 방향만 센다 (같은 쌍을 두 번 세지 않는다).
     * 외톨이: 사방 넷과 모두 다른 칸.
     */
    private static Field sample(IntBinaryOperator f) {
        int[][] v = new int[N][N];
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                v[i][j] = f.applyAsInt(OX + i, OZ + j);
            }
        }
        long diff = 0;
        long pairs = 0;
        long alone = 0;
        Map<Integer, Integer> hist = new LinkedHashMap<>();
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                hist.merge(v[i][j], 1, Integer::sum);
                if (i + 1 < N) {
                    pairs++;
                    if (v[i][j] != v[i + 1][j]) {
                        diff++;
                    }
                }
                if (j + 1 < N) {
                    pairs++;
                    if (v[i][j] != v[i][j + 1]) {
                        diff++;
                    }
                }
                if (i > 0 && j > 0 && i + 1 < N && j + 1 < N
                        && v[i][j] != v[i - 1][j] && v[i][j] != v[i + 1][j]
                        && v[i][j] != v[i][j - 1] && v[i][j] != v[i][j + 1]) {
                    alone++;
                }
            }
        }
        int total = N * N;
        int biggest = hist.values().stream().mapToInt(Integer::intValue).max().orElse(total);
        double minority = 1.0 - biggest / (double) total;   // 소수 쪽의 지분 (0 = 온통 한 값)
        return new Field(diff / (double) pairs, alone / (double) total, hist.size(), minority);
    }

    private static void report(String name, Field f) {
        System.out.printf("  %-38s 인접쌍 %6s · 외톨이 %6s · 값 %d종%n",
                name, pct(f.pairs), pct(f.isolated), f.distinct);
    }

    private static String pct(double v) {
        return String.format("%.1f%%", v * 100);
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("    ✔ " + what);
        } else {
            failures.add(what + (detail.isBlank() ? "" : " — " + detail));
            System.out.println("    ✖ " + what + (detail.isBlank() ? "" : " — " + detail));
        }
    }
}
