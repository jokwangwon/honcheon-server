package com.honcheon.mvt;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;

import java.util.List;

/**
 * <b>⑥의 눈을 시험하는 눈</b> — 환경 검수 ⑥(지하 공동)의 표본 판정을 서버 없이 시험한다 (B-114).
 *
 * <h2>왜 있는가</h2>
 * ⑥ 은 세 번 거짓말했고, 세 번 다 <b>분류의 병</b>이었다:
 * <ol>
 *   <li><b>액체를 공동으로 셌다</b> — 용암 바다(y&lt;-54)·대수층은 카버가 아니라 노이즈 생성기의
 *       구조층이다. 증거: cheese 봉인 전 3.81% → 후 3.84% (같은 시드) — 무변동.</li>
 *   <li><b>우리가 판 굴을 자연 동굴로 셌다</b> — 원장(terrain_built.yml)이 좌표를 기억하는데
 *       눈이 원장을 안 읽었다.</li>
 *   <li><b>하늘을 지하로 셌다</b> — 고정 대역 [cy-45, cy-5] 가 지면 낮은 기둥(저지·수변)의
 *       열린 하늘을 공동으로 셌다. 산술: 저지 ~80기둥 × ~19칸 ≈ 1,520 ≈ 실측 1,513.</li>
 * </ol>
 * 그래서 판정이 순수 함수로 분리됐고({@link TerrainAudit#classifyDeep} ·
 * {@link TerrainAudit#inCaveBox} · {@link TerrainAudit#columnTop}), 이 시험이 그 함수들을
 * 경계값까지 물어 본다. <b>"위반 0건"은 이 시험이 통과할 때만 믿어라.</b>
 *
 * <p>등록부: {@code config/terrain.yml audit.underground_*} (문턱 0.02/0.08 · margin 5 · 액체/판굴 분리).
 * 이 시험의 수와 등록부의 수가 어긋나면 <b>등록부가 정본이다</b> — 시험을 고쳐라.
 *
 * <h2>어떻게 돌리나 (저장소 루트에서 · 서버를 켜지 않는다)</h2>
 * <pre>
 *   export JAVA_HOME=$PWD/run/jdk-21
 *   ./gradlew :server-mvt:jar
 *   CP="$(find ~/.gradle -path '*1.21.11-R0.1-SNAPSHOT*' -name 'paper-api-*.jar' | head -1)"
 *   CP="$CP:$(find ~/.gradle -name '*.jar' | grep -E 'adventure-key|adventure-api|examination-api|snakeyaml-2.2' \
 *             | grep -v 26.1.2 | tr '\n' ':')$(find run/mvt/libraries -name 'guava-*.jar' | head -1)"
 *   CP="$CP:server-mvt/build/libs/server-mvt-1.0.0.jar"
 *   $JAVA_HOME/bin/javac -nowarn -d /tmp/audit-eye -cp "$CP" tools/TerrainAuditSelfTest.java
 *   $JAVA_HOME/bin/java -cp "$CP:/tmp/audit-eye" com.honcheon.mvt.TerrainAuditSelfTest
 * </pre>
 * (Bukkit Material 은 컴파일타임 상수 enum 이고, ⑥ 의 판정 함수는 {@code Material.isAir()} 같은
 * 레지스트리 위임을 일부러 안 쓴다 — 그래서 서버 밖에서 돈다.)
 */
public final class TerrainAuditSelfTest {

    private TerrainAuditSelfTest() {
    }

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        // ═══ 1. 분류 — 공기/물/용암/고체 (classifyDeep) ═══
        // 공기 — 카버(동굴)가 파는 것. 판정에 들어가는 유일한 부류
        classify(Material.AIR, TerrainAudit.DeepBlock.AIR);
        classify(Material.CAVE_AIR, TerrainAudit.DeepBlock.AIR);     // 카버가 실제로 남기는 블록
        classify(Material.VOID_AIR, TerrainAudit.DeepBlock.AIR);
        // 물 — 대수층·시드 원점 수역. 분리 계수 (판정 제외)
        classify(Material.WATER, TerrainAudit.DeepBlock.WATER);
        classify(Material.BUBBLE_COLUMN, TerrainAudit.DeepBlock.WATER);   // 마그마/소울샌드 기둥 — 물의 일부다
        // 용암 — y<-54 용암 바다·용암 대수층. 분리 계수 (판정 제외)
        classify(Material.LAVA, TerrainAudit.DeepBlock.LAVA);
        // 고체 — 지하의 정상 상태 (비고체·비공기도 구판부터 공동이 아니었다 — 동일 유지)
        classify(Material.STONE, TerrainAudit.DeepBlock.SOLID);
        classify(Material.DEEPSLATE, TerrainAudit.DeepBlock.SOLID);
        classify(Material.TUFF, TerrainAudit.DeepBlock.SOLID);
        classify(Material.GRAVEL, TerrainAudit.DeepBlock.SOLID);
        classify(Material.DIRT, TerrainAudit.DeepBlock.SOLID);
        classify(Material.BEDROCK, TerrainAudit.DeepBlock.SOLID);
        classify(Material.MAGMA_BLOCK, TerrainAudit.DeepBlock.SOLID);    // 용암 바다 가장자리 — 블록이지 액체가 아니다
        classify(Material.OBSIDIAN, TerrainAudit.DeepBlock.SOLID);
        classify(Material.GLOW_LICHEN, TerrainAudit.DeepBlock.SOLID);
        classify(Material.POINTED_DRIPSTONE, TerrainAudit.DeepBlock.SOLID);
        classify(Material.SEAGRASS, TerrainAudit.DeepBlock.SOLID);

        // ═══ 2. 판 굴 상자 — 원장의 CaveSpec.zone() 과 같은 식인가 (inCaveBox) ═══
        // 기연굴: 입구 (100,64,200) → 방 (122,53,201). zone() 식:
        //   x1=min-14=86 · x2=max+14=136 · z1=186 · z2=215 · y1=방바닥-8=45 · y2=입구+6=70
        TerrainForge.CaveSpec spec = new TerrainForge.CaveSpec(
                "test_cave", TerrainForge.CaveKind.기연굴, "w", 100, 64, 200, BlockFace.EAST,
                122, 53, 201, 900);
        Zone zn = spec.zone("굴:test_cave");
        box("상자 식 x1", zn.x1(), 86);
        box("상자 식 x2", zn.x2(), 136);
        box("상자 식 z1", zn.z1(), 186);
        box("상자 식 z2", zn.z2(), 215);
        box("상자 식 y1(방바닥-8)", zn.y1(), 45);
        box("상자 식 y2(입구+6)", zn.y2(), 70);
        List<Zone> boxes = List.of(zn);
        in(boxes, 110, 58, 200, true);     // 통로 한복판 — 판 굴
        in(boxes, 122, 53, 201, true);     // 방 — 판 굴
        in(boxes, 86, 45, 186, true);      // 상자 모서리 (경계 포함)
        in(boxes, 85, 58, 200, false);     // x 한 칸 밖 — 자연으로 센다
        in(boxes, 110, 44, 200, false);    // 방바닥-9 (y 밑) — 자연으로 센다
        in(boxes, 110, 71, 200, false);    // 입구+7 (y 위) — 자연으로 센다
        in(boxes, 300, 58, 400, false);    // 먼 곳의 공기 — 여전히 짖는다 (검출력 유지)
        in(List.of(), 110, 58, 200, false); // 원장이 비면 아무것도 안 뺀다

        // ═══ 3. 기둥별 상한 — 하늘은 지하가 아니다 (columnTop · B-114 5차) ═══
        // columnTop(cy, standY) = min(cy, standY-1) - margin(5).  standY = surfaceY 의 답(지면+1)
        // cy=90 · 대역 바닥 cy-45=45 (청하현 실측 좌표계)
        top("지면=cy (보통 기둥, 지면 90)", 90, 91, 85);        // 구판과 동일: cy-5
        top("지면이 cy 위 (봉우리, 지면 130)", 90, 131, 85);    // cy 로 캡 — 산속을 5칸만 째지 않는다
        top("지면이 대역 안 (저지, 지면 62)", 90, 63, 57);      // ★ 하늘 58..85 가 표본에서 빠진다
        top("지면이 대역 안 (수변, 지면 85)", 90, 86, 80);
        top("지면이 대역 밑 (깊은 골, 지면 39)", 90, 40, 34);   // top(34) < 바닥(45) → 기둥 기여 0
        top("지면 못 찾음", 90, Integer.MIN_VALUE, Integer.MIN_VALUE);
        // 기둥 기여 칸수 = max(0, top - (cy-45) + 1) — 5차 가설의 산술 재현
        cells("보통 기둥 기여", 90, 85, 41);                    // 구판 41칸 그대로
        cells("저지(지면62) 기여", 90, 57, 13);                 // 41-13=28칸의 하늘이 빠졌다
        cells("깊은 골 기여", 90, 34, 0);                       // 대역 전체가 지면 위 — 0

        // ═══ 4. 판정 — 공기만 문턱에 들어가는가 (문턱: terrain.yml audit.underground_*) ═══
        // 실측 재현: 39,401칸 중 1,513칸의 "공동"이 전부 하늘/액체/판굴로 분리되면 → 자연 공기 0% → 통과
        verdict("전부 분리됨 (실측 3.84% 재현)", 0, 0, 0, 37888, false, false);
        verdict("용암 바다+수역만 (1차 실측 재현)", 0, 250, 134, 9616, false, false);
        verdict("자연 동굴 생존 (공기 8%)", 800, 100, 50, 9050, false, true);
        verdict("판 굴 있는 지역 (공기 4% — 여유 8% 안)", 400, 0, 0, 9600, true, false);
        verdict("판 굴 지역인데 공기 9%", 900, 0, 0, 9100, true, true);
        verdict("경계값 공기 2.0% ('초과'만 위반)", 200, 300, 0, 9500, false, false);

        System.out.println("── 총평 ──");
        System.out.println(pass + "/" + (pass + fail) + " PASS" + (fail == 0 ? "" : " · ❌ FAIL " + fail));
        if (fail != 0) {
            System.exit(1);
        }
    }

    // ─── 손 ───

    private static void classify(Material m, TerrainAudit.DeepBlock want) {
        TerrainAudit.DeepBlock got = TerrainAudit.classifyDeep(m);
        check(got == want, String.format("[분류] %-22s -> %-6s (want %s)", m, got, want));
    }

    private static void box(String name, int got, int want) {
        check(got == want, String.format("[상자] %s = %d (want %d)", name, got, want));
    }

    private static void in(List<Zone> boxes, int x, int y, int z, boolean want) {
        boolean got = TerrainAudit.inCaveBox(boxes, x, y, z);
        check(got == want, String.format("[판굴?] (%d,%d,%d) -> %s (want %s)",
                x, y, z, got ? "판굴" : "자연", want ? "판굴" : "자연"));
    }

    private static void top(String name, int cy, int standY, int want) {
        int got = TerrainAudit.columnTop(cy, standY);
        check(got == want, String.format("[상한] %s: columnTop(%d, %s) = %s (want %s)",
                name, cy, str(standY), str(got), str(want)));
    }

    private static void cells(String name, int cy, int top, int want) {
        int got = Math.max(0, top - (cy - 45) + 1);
        check(got == want, String.format("[기여] %s = %d칸 (want %d)", name, got, want));
    }

    /** ⑥ 의 판정식 재현 — 공기만 문턱과 비교한다 (0.02 / dugCave 0.08 = terrain.yml audit.underground_*) */
    private static void verdict(String name, long air, long water, long lava, long solid,
                                boolean dugCave, boolean wantViolation) {
        long total = air + water + lava + solid;
        double pct = total == 0 ? 0 : (double) air / total;
        double limit = dugCave ? 0.08 : 0.02;
        boolean violation = pct > limit;
        check(violation == wantViolation, String.format(
                "[판정] %s — 공기 %.2f%% / 문턱 %.0f%% → %s (want %s)",
                name, pct * 100, limit * 100,
                violation ? "위반" : "통과", wantViolation ? "위반" : "통과"));
    }

    private static String str(int y) {
        return y == Integer.MIN_VALUE ? "못찾음" : String.valueOf(y);
    }

    private static void check(boolean ok, String line) {
        System.out.println((ok ? "PASS   " : "FAIL   ") + line);
        if (ok) {
            pass++;
        } else {
            fail++;
        }
    }
}
