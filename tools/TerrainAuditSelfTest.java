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
 * <p>★ 7차(2026-07-14)부터 <b>손의 판정</b>도 여기서 시험한다: 조성기가 나무 위로 땅을 올려
 * 나무를 산 채로 묻었고(공기+잎+통나무가 지면 밑에), 그 수리 둘 — 계약 ①-b
 * ({@link TerrainForge#fillBelowRaised} · terrain.yml {@code forge.fill_below_raised})와
 * 치유 스윕({@code /혼천 지하정리}) — 이 같은 순수 함수({@link TerrainForge#sweepFillable} ·
 * {@link TerrainForge#sweepFill} · {@link TerrainForge#sweepShouldFill})를 쓴다 (5절).
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

        // ═══ 3.5 땅인가 — 나무는 땅이 아니다 (isGround · B-114 6차) ═══
        // 3.86% 의 진범: 통나무를 땅으로 읽어 수관 속 공기가 「지하」로 계수됐다 (천장 #leaves · 벽 #logs 실측)
        ground("돌은 나무가 아니다", "STONE", false);
        ground("잔디 블록도 나무가 아니다", "GRASS_BLOCK", false);
        ground("참나무 통나무는 나무다", "OAK_LOG", true);
        ground("자작 잎도 나무다", "BIRCH_LEAVES", true);
        ground("참나무 원목(WOOD)도 나무다", "OAK_WOOD", true);
        ground("껍질 벗긴 통나무도 나무다", "STRIPPED_OAK_LOG", true);
        ground("껍질 벗긴 원목도 나무다", "STRIPPED_BIRCH_WOOD", true);
        ground("버섯 갓도 나무다", "RED_MUSHROOM_BLOCK", true);
        ground("버섯 대도 나무다", "MUSHROOM_STEM", true);
        ground("장대나무도 나무다", "BAMBOO", true);

        // ═══ 4. 판정 — 공기만 문턱에 들어가는가 (문턱: terrain.yml audit.underground_*) ═══
        // 실측 재현: 39,401칸 중 1,513칸의 "공동"이 전부 하늘/액체/판굴로 분리되면 → 자연 공기 0% → 통과
        verdict("전부 분리됨 (실측 3.84% 재현)", 0, 0, 0, 37888, false, false);
        verdict("용암 바다+수역만 (1차 실측 재현)", 0, 250, 134, 9616, false, false);
        verdict("자연 동굴 생존 (공기 8%)", 800, 100, 50, 9050, false, true);
        verdict("판 굴 있는 지역 (공기 4% — 여유 8% 안)", 400, 0, 0, 9600, true, false);
        verdict("판 굴 지역인데 공기 9%", 900, 0, 0, 9100, true, true);
        verdict("경계값 공기 2.0% ('초과'만 위반)", 200, 300, 0, 9500, false, false);

        // ═══ 5. 채움·스윕 판정 — 올린 땅 밑은 채운다 / 묻힌 나무를 걷는다 (B-114 7차) ═══
        // 조성기가 서 있던 나무 위로 땅을 올려 지면 밑에 「공기+잎+통나무」가 묻혔다
        // (실측 단면: 옛 지면 y79 → 공기 81-82 → 잎 83-86 → 새 흙·잔디 87-89).
        // 손 둘(TerrainForge.fillBelowRaised 계약 · /혼천 지하정리 치유)이 같은 판정을 쓴다:
        //   sweepFillable — 공기·잎·통나무·버섯·초목은 치환, 돌·흙·광석·물·용암·사람 것은 보존
        //   sweepFill     — 채움 재질은 깊이의 순수 함수 (표면 밑 2칸 흙 · 그 아래 돌 = sealBelow 관례)
        //   sweepShouldFill — ★ 원장의 판 굴 상자 안은 건드리지 않는다 (inCaveBox 와 같은 자)
        // ─ 치환: 공기 (실측 단면의 81-82) ─
        fillable(Material.AIR, true);
        fillable(Material.CAVE_AIR, true);
        fillable(Material.VOID_AIR, true);
        // ─ 치환: 나무의 몸 (단면의 83-86 · 벽의 #logs) — ⑥의 isTreeish 와 같은 자 ─
        fillable(Material.OAK_LEAVES, true);
        fillable(Material.BIRCH_LEAVES, true);
        fillable(Material.OAK_LOG, true);
        fillable(Material.STRIPPED_OAK_LOG, true);
        fillable(Material.OAK_WOOD, true);
        fillable(Material.RED_MUSHROOM_BLOCK, true);
        fillable(Material.MUSHROOM_STEM, true);
        fillable(Material.BAMBOO, true);
        // ─ 치환: 묻힌 초목 (foliage — 지면 밑의 풀·꽃·눈은 땅이 아니다) ─
        fillable(Material.SHORT_GRASS, true);
        fillable(Material.RED_MUSHROOM, true);
        fillable(Material.SNOW, true);
        // ─ 보존: 땅·광석 — 채움이 도굴이 되면 안 된다 ─
        fillable(Material.STONE, false);
        fillable(Material.DEEPSLATE, false);
        fillable(Material.DIRT, false);
        fillable(Material.GRASS_BLOCK, false);
        fillable(Material.MOSS_BLOCK, false);       // _BLOCK — 초목이 아니라 NATURAL 의 땅이다
        fillable(Material.IRON_ORE, false);
        fillable(Material.DEEPSLATE_GOLD_ORE, false);
        fillable(Material.BEDROCK, false);
        // ─ 보존: 액체 — 대수층은 구조층이다 (⑥이 분리 계수하는 그 부류. 스윕이 메우면 안 된다) ─
        fillable(Material.WATER, false);
        fillable(Material.LAVA, false);
        // ─ 보존: 사람이 지은 것 — 그건 건축 계층의 것이다 ─
        fillable(Material.OAK_PLANKS, false);
        fillable(Material.COBBLESTONE, false);
        fillable(Material.STONE_BRICKS, false);
        // ─ 채움 재질 — 깊이의 순수 함수 (sealBelow 관례: 1~2칸 흙 · 3칸부터 돌) ─
        fillMat(1, Material.DIRT);
        fillMat(2, Material.DIRT);
        fillMat(3, Material.STONE);
        fillMat(12, Material.STONE);
        // ─ ★ 판 굴 상자 보존 — 상자 안은 공기여도 안 채운다 (2절의 기연굴 상자 재사용) ─
        sweep("굴 통로의 공기 — 설계다, 남긴다", Material.AIR, boxes, 110, 58, 200, false);
        sweep("굴 밖의 공기 — 묻힌 것이다, 채운다", Material.AIR, boxes, 300, 58, 400, true);
        sweep("굴 밖의 잎 — 묻힌 나무다, 채운다", Material.OAK_LEAVES, boxes, 300, 58, 400, true);
        sweep("굴 밖의 돌 — 땅이다, 남긴다", Material.STONE, boxes, 300, 58, 400, false);
        sweep("굴 상자 경계 안 모서리 — 남긴다", Material.AIR, boxes, 86, 45, 186, false);
        sweep("굴 상자 한 칸 밖 — 채운다", Material.AIR, boxes, 85, 45, 186, true);
        sweep("원장이 비면 전부 스윕 대상", Material.AIR, List.of(), 110, 58, 200, true);

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

    private static void ground(String name, String material, boolean want) {
        boolean got = TerrainAudit.isTreeish(material);
        check(got == want, String.format("[나무] %s: isTreeish(%s) = %s (want %s)", name, material, got, want));
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

    private static void fillable(Material m, boolean want) {
        boolean got = TerrainForge.sweepFillable(m);
        check(got == want, String.format("[채움?] %-22s -> %s (want %s)",
                m, got ? "치환" : "보존", want ? "치환" : "보존"));
    }

    private static void fillMat(int depth, Material want) {
        Material got = TerrainForge.sweepFill(depth);
        check(got == want, String.format("[재질] 깊이 %d -> %s (want %s)", depth, got, want));
    }

    private static void sweep(String name, Material m, List<Zone> boxes,
                              int x, int y, int z, boolean want) {
        boolean got = TerrainForge.sweepShouldFill(m, boxes, x, y, z);
        check(got == want, String.format("[스윕] %s: (%d,%d,%d) %s -> %s (want %s)",
                name, x, y, z, m, got ? "채운다" : "남긴다", want ? "채운다" : "남긴다"));
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
