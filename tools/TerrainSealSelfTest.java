package com.honcheon.mvt;

import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>봉인의 눈을 시험하는 눈</b> — B-150: 고지대 사각지대가 정말 없어졌는가.
 *
 * <p>계율: <b>눈을 만들면 눈을 시험하라.</b> 이 시험은 가짜 세계 위에서
 * <b>일부러 고지대 흙일을 저지르고</b> 새 봉인({@link TerrainSeal})이 잡는지 잰다.
 * 그리고 <b>구판의 눈(기준면 창)을 되살려</b> 같은 흙일을 들이댄다 —
 * 구판이 <b>못 보는 것이 재현되지 않으면 이 시험이 실패한다</b> (사각지대가 실재했다는 증명).
 *
 * <h2>무엇을 어기고 무엇을 재나</h2>
 * <ol>
 *   <li><b>무변</b> — 지면 위에만 짓는다 → 위반 0 · 지문 동일 (멱등의 자)</li>
 *   <li><b>★ 고지대 깎기</b> — 정상 단(y140, 기준면 y100)의 지면을 판다 → 새 눈은 잡고,
 *       <b>구판 창(기준면 8칸)은 무변</b>이어야 한다 (사각지대 재현)</li>
 *   <li><b>★ 고지대 돋움</b> — 정상 단 지면 밑 빈 속을 몰래 채운다 → 잡는다</li>
 *   <li><b>승인된 단</b> — terrace 를 흉내 낸다 (세계 + spec.surface 를 <b>함께</b> 고친다)
 *       → 위반이 아니라 단(壇)으로 읽는다 (허용 마스크 = 지도)</li>
 *   <li><b>밑 빈 단</b> — 단은 승인됐지만 계약 ①(밑 6칸 단단)을 어긴다 → 잡는다</li>
 *   <li><b>같은 높이의 단</b> — 높이 무변 + buildable(false→true) 곁눈 → 위반이 아니다</li>
 *   <li><b>젖은 열</b> — 물↔공기 출렁임(B-139 물리 잡음)은 안 물고, 호수 바닥을 파면 문다</li>
 *   <li><b>부지 이동</b> — 원장이 안 막은 재빚기 → 잡는다 (구판 계약 존속)</li>
 * </ol>
 *
 * <h2>어떻게 돌리나 (저장소 루트에서 · 서버를 켜지 않는다)</h2>
 * <pre>
 *   export JAVA_HOME=$PWD/run/jdk-21
 *   ./gradlew :server-mvt:jar
 *   CP="$(find ~/.gradle -path '*1.21.11-R0.1-SNAPSHOT*' -name 'paper-api-*.jar' | head -1)"
 *   CP="$CP:server-mvt/build/libs/server-mvt-1.0.0.jar"
 *   $JAVA_HOME/bin/javac -nowarn -d /tmp/seal-eye -cp "$CP" tools/TerrainSealSelfTest.java
 *   $JAVA_HOME/bin/java -cp "$CP:/tmp/seal-eye" com.honcheon.mvt.TerrainSealSelfTest
 * </pre>
 * 실패가 하나라도 있으면 exit 1.
 */
public final class TerrainSealSelfTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    // ── 가짜 세계 — 열쇠 (x,y,z) → 's'(단단) · 'w'(물) · 없음(공기) ──
    private static final class Fake implements TerrainSeal.Probe {

        private final Map<Long, Character> cells = new HashMap<>();

        private static long key(int x, int y, int z) {
            return ((long) (x & 0xFFFFF) << 40) | ((long) (y & 0xFFFFF) << 20) | (z & 0xFFFFF);
        }

        void put(int x, int y, int z, char kind) {
            cells.put(key(x, y, z), kind);
        }

        void air(int x, int y, int z) {
            cells.remove(key(x, y, z));
        }

        @Override
        public boolean solid(int x, int y, int z) {
            Character c = cells.get(key(x, y, z));
            return c != null && c == 's';
        }

        @Override
        public boolean water(int x, int y, int z) {
            Character c = cells.get(key(x, y, z));
            return c != null && c == 'w';
        }
    }

    /** 시험 부지 — 기준면 y100 들판 + 남서쪽 정상 단(y140) + 남동쪽 호수(젖은 열) */
    private record Site(Fake world, TerrainForge.SiteSpec spec) {
    }

    private static final int R = 6;
    private static final int GY = 100;
    private static final int PEAK_Y = 140;

    private static Site site() {
        Fake world = new Fake();
        int w = 2 * R + 1;
        int[] surface = new int[w * w];
        boolean[] buildable = new boolean[w * w];
        for (int dz = -R; dz <= R; dz++) {
            for (int dx = -R; dx <= R; dx++) {
                int i = (dz + R) * w + (dx + R);
                if (dx >= 4 && dz >= 4) {
                    // 호수 — 젖은 열: 수면 y100..97, 바닥 y96부터 단단
                    surface[i] = Integer.MIN_VALUE;   // WET_COLUMN 규약 (TerrainForge)
                    buildable[i] = false;
                    for (int y = GY; y >= 97; y--) {
                        world.put(dx, y, dz, 'w');
                    }
                    for (int y = 96; y >= 85; y--) {
                        world.put(dx, y, dz, 's');
                    }
                } else if (dx <= -3 && dz <= -3) {
                    // 정상 단 — 실지면 y140 (기준면보다 40칸 높다: B-150 의 무대)
                    surface[i] = PEAK_Y;
                    buildable[i] = true;
                    for (int y = PEAK_Y; y >= 85; y--) {
                        world.put(dx, y, dz, 's');
                    }
                } else {
                    // 들판 — 실지면 = 기준면
                    surface[i] = GY;
                    buildable[i] = true;
                    for (int y = GY; y >= 85; y--) {
                        world.put(dx, y, dz, 's');
                    }
                }
            }
        }
        TerrainForge.SiteSpec spec = new TerrainForge.SiteSpec(
                "seal_test", "봉인시험", "산", "world",
                0, 0, R, GY, -5, -5, PEAK_Y,
                surface, buildable,
                List.of(BlockFace.NORTH), false, List.of(), 2, PEAK_Y - GY);
        return new Site(world, spec);
    }

    /** ★ 구판의 눈 — 기준면 아래 8칸만 (사각지대 재현용으로 되살렸다) */
    private static int oldEye(TerrainSeal.Probe p, int x, int groundY, int z) {
        int bits = 0;
        for (int i = 0; i < TerrainSeal.DEPTH; i++) {
            int y = groundY - i;
            int v = p.water(x, y, z) ? 2 : p.solid(x, y, z) ? 1 : 0;
            bits |= v << (i * 2);
        }
        return bits;
    }

    private static int idx(int dx, int dz) {
        return (dz + R) * (2 * R + 1) + (dx + R);
    }

    /** terrace 흉내 — 세계와 spec 을 **함께** 고친다 (지형 계층의 손) */
    private static void terrace(Site s, int x, int z, int y) {
        for (int yy = y + 1; yy <= y + 14; yy++) {
            s.world().air(x, yy, z);
        }
        for (int yy = y; yy >= y - TerrainForge.SEAL_DEPTH; yy--) {
            s.world().put(x, yy, z, 's');
        }
        s.spec().surface()[idx(x, z)] = y;
        s.spec().buildable()[idx(x, z)] = true;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failures.add(name);
        }
        System.out.println("  " + (ok ? "✓ " : "✗ ") + name);
    }

    private static boolean says(List<String> lines, String needle) {
        return lines.stream().anyMatch(l -> l.contains(needle));
    }

    public static void main(String[] args) {
        System.out.println("봉인의 눈 자기 시험 — 부지 반경 " + R + " · 기준면 y" + GY
                + " · 정상 단 y" + PEAK_Y + " (+40)\n");

        // ══ 1. 무변 — 지면 위에만 지으면 위반 0 · 지문 동일 ══
        {
            Site s = site();
            TerrainSeal.Seal b = TerrainSeal.seal(s.world(), s.spec());
            for (int y = GY + 1; y <= GY + 5; y++) {
                s.world().put(0, y, 0, 's');          // 들판 위의 벽
            }
            for (int y = PEAK_Y + 1; y <= PEAK_Y + 5; y++) {
                s.world().put(-5, y, -5, 's');        // ★ 정상 단 위의 벽 — 건축의 하늘
            }
            TerrainSeal.Seal a = TerrainSeal.seal(s.world(), s.spec());
            check("무변: 지면 위 건축은 위반 0", TerrainSeal.violations(b, a).isEmpty());
            check("무변: 지문이 같다 (멱등의 자)",
                    TerrainSeal.fingerprint(b) == TerrainSeal.fingerprint(a));
        }

        // ══ 2. ★ 고지대 깎기 — 구판은 못 보고, 새 눈은 잡아야 한다 ══
        {
            Site s = site();
            TerrainSeal.Seal b = TerrainSeal.seal(s.world(), s.spec());
            int oldBefore = oldEye(s.world(), -5, GY, -5);
            s.world().air(-5, PEAK_Y, -5);            // 정상 단의 지면 두 칸을 판다
            s.world().air(-5, PEAK_Y - 1, -5);
            int oldAfter = oldEye(s.world(), -5, GY, -5);
            TerrainSeal.Seal a = TerrainSeal.seal(s.world(), s.spec());
            List<String> v = TerrainSeal.violations(b, a);
            check("고지대 깎기: ★ 구판(기준면 창)은 무변 — 사각지대가 실재했다", oldBefore == oldAfter);
            check("고지대 깎기: 새 눈이 잡는다", says(v, "땅을 바꿨다"));
            check("고지대 깎기: 방향이 「판 것」이다", says(v, "판 것 1"));
            check("고지대 깎기: 지문이 달라진다",
                    TerrainSeal.fingerprint(b) != TerrainSeal.fingerprint(a));
        }

        // ══ 3. ★ 고지대 돋움 — 단 밑 빈 속을 몰래 채운다 ══
        {
            Site s = site();
            s.world().air(-5, PEAK_Y - 3, -5);        // 지면 밑의 빈 속 (봉인 전부터 있던 굴)
            TerrainSeal.Seal b = TerrainSeal.seal(s.world(), s.spec());
            s.world().put(-5, PEAK_Y - 3, -5, 's');   // 건축이 몰래 채웠다
            TerrainSeal.Seal a = TerrainSeal.seal(s.world(), s.spec());
            List<String> v = TerrainSeal.violations(b, a);
            check("고지대 돋움: 새 눈이 잡는다", says(v, "땅을 바꿨다"));
            check("고지대 돋움: 방향이 「돋운 것」이다", says(v, "돋운 것 1"));
        }

        // ══ 4. 승인된 단 — terrace 채널은 위반이 아니다 (허용 마스크 = 지도) ══
        {
            Site s = site();
            TerrainSeal.Seal b = TerrainSeal.seal(s.world(), s.spec());
            for (int dx = -6; dx <= -4; dx++) {
                for (int dz = -6; dz <= -4; dz++) {
                    terrace(s, dx, dz, PEAK_Y - 2);   // 정상을 두 칸 깎아 3×3 단을 앉힌다
                }
            }
            TerrainSeal.Seal a = TerrainSeal.seal(s.world(), s.spec());
            List<String> report = TerrainSeal.compare(b, a);
            check("승인 단: 위반 0 (spec 이 함께 움직였다)", TerrainSeal.violations(b, a).isEmpty());
            check("승인 단: 단(壇) 9열로 읽는다", says(report, "단(壇) 9열"));
        }

        // ══ 5. 밑 빈 단 — 단은 승인됐지만 계약 ①을 어겼다 ══
        {
            Site s = site();
            TerrainSeal.Seal b = TerrainSeal.seal(s.world(), s.spec());
            terrace(s, -5, -5, PEAK_Y - 2);
            s.world().air(-5, PEAK_Y - 5, -5);        // 단 밑 3칸 자리가 비었다 (축대 부실)
            TerrainSeal.Seal a = TerrainSeal.seal(s.world(), s.spec());
            check("밑 빈 단: 계약 ① 위반으로 잡는다",
                    says(TerrainSeal.violations(b, a), "단 밑이 비었다"));
        }

        // ══ 6. 같은 높이의 단 — buildable(false→true) 곁눈이 알아본다 ══
        {
            Site s = site();
            s.spec().buildable()[idx(2, 2)] = false;  // 아직 단이 안 선 자리
            s.world().air(2, GY - 3, 2);              // 지면 밑 빈 속
            TerrainSeal.Seal b = TerrainSeal.seal(s.world(), s.spec());
            terrace(s, 2, 2, GY);                     // 같은 높이의 단 — 속만 채운다
            TerrainSeal.Seal a = TerrainSeal.seal(s.world(), s.spec());
            check("같은 높이 단: 위반이 아니다", TerrainSeal.violations(b, a).isEmpty());
            check("같은 높이 단: 단(壇)으로 읽는다",
                    says(TerrainSeal.compare(b, a), "단(壇) 1열"));
        }

        // ══ 7. 젖은 열 — 물의 출렁임은 안 물고 (B-139), 바닥을 파면 문다 ══
        {
            Site s = site();
            TerrainSeal.Seal b = TerrainSeal.seal(s.world(), s.spec());
            s.world().air(5, 98, 5);                  // 물 한 칸이 빠졌다 — 물리의 몫
            TerrainSeal.Seal a1 = TerrainSeal.seal(s.world(), s.spec());
            check("젖은 열: 물↔공기 출렁임은 위반이 아니다 (B-139 잡음 절연)",
                    TerrainSeal.violations(b, a1).isEmpty());
            s.world().air(5, 96, 5);                  // 호수 바닥(뼈)을 팠다
            TerrainSeal.Seal a2 = TerrainSeal.seal(s.world(), s.spec());
            check("젖은 열: 바닥(뼈)을 파면 문다",
                    says(TerrainSeal.violations(b, a2), "땅을 바꿨다"));
        }

        // ══ 8. 부지 이동 — 원장이 안 막은 재빚기 (구판 계약 존속) ══
        {
            Site s = site();
            TerrainSeal.Seal b = TerrainSeal.seal(s.world(), s.spec());
            TerrainForge.SiteSpec moved = new TerrainForge.SiteSpec(
                    "seal_test", "봉인시험", "산", "world",
                    3, 0, R, GY, -2, -5, PEAK_Y,
                    s.spec().surface(), s.spec().buildable(),
                    List.of(BlockFace.NORTH), false, List.of(), 2, PEAK_Y - GY);
            TerrainSeal.Seal a = TerrainSeal.seal(s.world(), moved);
            check("부지 이동: 잡는다", says(TerrainSeal.violations(b, a), "부지가 옮겨 앉았다"));
        }

        // ══ 9. 결정론 — 같은 세계를 두 번 뜨면 같은 지문 ══
        {
            Site s = site();
            long f1 = TerrainSeal.fingerprint(TerrainSeal.seal(s.world(), s.spec()));
            long f2 = TerrainSeal.fingerprint(TerrainSeal.seal(s.world(), s.spec()));
            check("결정론: 지문이 재현된다", f1 == f2);
        }

        System.out.println("\n통과 " + passed + " · 실패 " + failures.size());
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.out.println("  ✗ " + f);
            }
            System.exit(1);
        }
    }

    private TerrainSealSelfTest() {
    }
}
