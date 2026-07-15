package com.honcheon.mvt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>지형 관문의 눈</b> — <b>건축의 미결이 땅을 막지 않는가.</b> 그리고 <b>승인 안 된 판이 땅에 쓰지 않는가.</b>
 *
 * <p>계약(사용자가 직접 씀 · {@code docs/design/gate_and_watertown.md}):
 * <blockquote>
 *   "건축의 미결은 건축만 막을 수 있다. <b>건축의 미결이 땅을 막아서는 안 된다.</b>"<br>
 *   "강남의 실제 지형 조성은 <b>점묘 문제가 수정되고 새 TerrainForge 버전이 승인될 때까지 실행하지 않는다.</b>"
 * </blockquote>
 *
 * <h2>★★ 일부러 어긴다</h2>
 * 관문이 <b>무엇이든 통과시키면</b> 관문이 아니다. 그래서 등록부를 <b>일부러 고쳐</b> 본다:
 * 승인 판을 올려 보고(→ 그제서야 commit 이어야 한다), 없는 프로파일을 지도에 적어 보고
 * (→ 그 땅은 pending 이어야 한다), 보류를 걸어 본다.
 *
 * <h2>어떻게 돌리나 (저장소 루트에서 · 서버를 켜지 않는다)</h2>
 * <pre>
 *   export JAVA_HOME=$PWD/run/jdk-21
 *   ./gradlew :server-mvt:jar
 *   CP="$(find ~/.gradle -path '*1.21.11-R0.1-SNAPSHOT*' -name 'paper-api-*.jar' | head -1)"
 *   CP="$CP:$(find ~/.gradle -name '*.jar' | grep -E 'adventure-key|adventure-api|examination-api|snakeyaml-2.2' \
 *             | grep -v 26.1.2 | tr '\n' ':')$(find run/mvt/libraries -name 'guava-*.jar' | head -1)"
 *   CP="$CP:server-mvt/build/libs/server-mvt-1.0.0.jar"
 *   $JAVA_HOME/bin/javac -nowarn -d /tmp/gate-eye -cp "$CP" tools/TerrainGateSelfTest.java
 *   $JAVA_HOME/bin/java -cp "$CP:/tmp/gate-eye" com.honcheon.mvt.TerrainGateSelfTest
 * </pre>
 */
public final class TerrainGateSelfTest {

    private static final int R = 110;   // world_map.yml §1-b land.forge_radius

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("honcheon-gate-eye");
        System.out.println("지형 관문의 눈 — " + tmp + "\n");

        // ══════════ ① 저장소의 **진짜 등록부**로 — 지금 강남은 어떤 상태인가 ══════════
        System.out.println("【지금 이 저장소】 config/terrain_gate.yml 그대로");
        TerrainForge.load(Path.of("config"));
        TerrainGate.load(Path.of("config"), tmp.resolve("data"));
        // ★ 2026-07-14 09:xx — 사용자가 v4 를 **승인했다** (approved_forge_version: 4).
        //   그러니 이제 참이어야 하는 것은 "승인 전"이 아니라 **"승인됐으면 땅이 선다"** 이다.
        //   ★★ 승인 여부를 **눈이 스스로 정하지 않는다** — 등록부가 말하는 것을 그대로 읽고,
        //      두 갈래를 **둘 다** 시험한다 (승인 상태 · 미승인 상태).
        System.out.println("  등록부: forge_version " + TerrainGate.forgeVersion()
                + " · 승인 " + (TerrainGate.approved() ? "됨" : "안 됨"));

        WorldMap.Place gangnam = place("gangnam_sangro", "강남 상로", 12000, -4000, "평지");
        TerrainGate.Verdict g = TerrainGate.judge(gangnam, R);
        say("강남 상로", g);
        check("★ 강남은 pending 이다", "pending".equals(g.state()), g.state());
        check("★ 사유는 사용자가 못 박은 그것이다 (pointillism_fix_in_progress)",
                "pointillism_fix_in_progress".equals(g.reason()), String.valueOf(g.reason()));
        check("★★ 강남은 **월드에 쓰지 않는다** (forge_mode: preview)", !g.writes(),
                "승인 전인데 땅에 쓴다 — 땅은 한 번만 선다");

        // ══════════ ② 승인 안 된 판 — 아무 땅도 안 쓴다 ══════════
        WorldMap.Place field = place("cheongha_field", "청하 들", 500, 500, "평지");
        TerrainGate.Verdict f = TerrainGate.judge(field, R);
        say("청하 들 (보류 목록에 없다)", f);
        check("★ 승인된 판이면 땅이 선다 (ready · commit) — 화산파가 이 길로 섰다",
                f.writes(), f.state() + "/" + f.mode()
                        + " (승인=" + TerrainGate.approved() + ")");
        check("★★ 그래도 강남은 pending — **사람이 못 박았기 때문이다**",
                !TerrainGate.judge(gangnam, R).writes(),
                "보류가 승인 한 줄에 풀렸다 — 사람의 못이 코드보다 약하다");

        // ══════════ ③ ★ 일부러 어긴다 — 승인을 도로 내린다 (미승인 갈래) ══════════
        System.out.println("\n【★ 일부러 어긴다】 approved_forge_version 을 내린다 (= 미승인 판)");
        Path cfg = tmp.resolve("config");
        Files.createDirectories(cfg);
        Files.writeString(cfg.resolve("terrain_gate.yml"),
                Files.readString(Path.of("config/terrain_gate.yml"), StandardCharsets.UTF_8)
                        .replace("approved_forge_version: " + TerrainGate.forgeVersion(),
                                "approved_forge_version: 0"),
                StandardCharsets.UTF_8);
        TerrainGate.load(cfg, tmp.resolve("data"));
        check("미승인이 됐다", !TerrainGate.approved(), "");
        TerrainGate.Verdict f2 = TerrainGate.judge(field, R);
        say("청하 들 (미승인)", f2);
        check("★★ 승인 안 된 판으로는 **어느 땅도** 월드에 안 쓴다", !f2.writes(),
                "승인 안 된 판으로 땅을 빚었다 — 땅은 한 번만 선다");
        check("★ 사유를 말한다 (terrain_forge_version_not_approved)",
                "terrain_forge_version_not_approved".equals(f2.reason()), String.valueOf(f2.reason()));
        check("★★ 그리고 **조용히 넘어가지 않는다** (사람에게 할 말이 있다)",
                !f2.notes().isEmpty(), "막았는데 아무 말도 없다 — 그것이 09:46 의 병이다");

        // ══════════ ④ 없는 프로파일 — 「수향」은 아직 구현되지 않았다 ══════════
        System.out.println("\n【★ 일부러 어긴다】 지도가 「수향」을 요구한다 (아직 구현 안 됨)");
        Files.writeString(cfg.resolve("terrain.yml"),
                "shaping:\n  suhyang_test: 수향\n", StandardCharsets.UTF_8);
        Files.writeString(cfg.resolve("terrain_grain.yml"),
                Files.readString(Path.of("config/terrain_grain.yml"), StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        // 승인은 저장소의 것으로 되돌린다 — 여기서 재는 것은 **프로파일 미구현**이지 승인이 아니다
        Files.writeString(cfg.resolve("terrain_gate.yml"),
                Files.readString(Path.of("config/terrain_gate.yml"), StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        TerrainForge.load(cfg);
        TerrainGate.load(cfg, tmp.resolve("data"));
        WorldMap.Place water = place("suhyang_test", "물골목 마을", 9000, 900, "평지");
        TerrainGate.Verdict w = TerrainGate.judge(water, R);
        say("물골목 마을", w);
        check("★★ 안 만든 프로파일을 요구하면 그 땅은 **서지 않는다**",
                !w.writes(), "구현도 안 된 「수향」인데 땅을 빚었다 — 들판이 서고 아무도 모른다");
        check("★ 사유를 말한다 (terrain_profile_unresolved)",
                "terrain_profile_unresolved".equals(w.reason()), String.valueOf(w.reason()));
        check("★ 요구된 이름을 잃지 않았다 (조용히 '들'로 바꾸지 않는다)",
                "수향".equals(TerrainForge.requestedProfile(water)),
                TerrainForge.requestedProfile(water));

        // ══════════ ⑤ 이미 선 땅 — 다시 빚지 않는다 ══════════
        System.out.println("\n【땅은 한 번만 선다】");
        Path data = tmp.resolve("data");
        Files.createDirectories(data);
        Files.writeString(cfg.resolve("terrain.yml"), "shaping: {}\n", StandardCharsets.UTF_8);
        TerrainForge.load(cfg);
        Files.writeString(data.resolve("terrain_receipts.yml"),
                "cheongha_field:\n  profile: \"들\"\n  forge_version: 4\n  state: \"committed\"\n",
                StandardCharsets.UTF_8);
        TerrainGate.load(cfg, data);
        TerrainGate.Verdict done = TerrainGate.judge(field, R);
        say("청하 들 (영수증 있음)", done);
        check("★★ 영수증이 있으면 다시 안 빚는다 (committed)",
                "committed".equals(done.state()) && !done.writes(),
                "이미 선 땅을 다시 빚었다 — 산 위에 산을 쌓는다");

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("✔ 눈 " + passed + "개 — 전부 통과. 땅은 건축의 허락을 받지 않고, "
                    + "승인 없는 판은 땅에 쓰지 않는다");
        } else {
            System.out.println("✖ 실패 " + failures.size() + " / 통과 " + passed);
            failures.forEach(x -> System.out.println("  ✖ " + x));
            System.exit(1);
        }
    }

    /** 지도의 한 줄 — 건축 쪽 칸은 <b>일부러 미결로 둔다</b> (그것이 땅을 막으면 안 된다는 것이 계약이다) */
    private static WorldMap.Place place(String id, String name, int x, int z, String terrain) {
        return new WorldMap.Place(id, name, x, z, terrain, List.of(), "later", false, 3, "강남",
                null, "rich", "물길의 상업 고을",
                null,                 // archetype — 미결 (건축 게이트가 막을 것이다)
                null, "unresolved",   // build_radius — 미결
                "commercial_class 미정",
                false,                // hidden — 표시 축 (B-151). 땅의 게이트와는 무관하다
                null);                // access — 해금 축 (B-151). 땅의 게이트와는 무관하다 (미등록)
    }

    private static void say(String who, TerrainGate.Verdict v) {
        System.out.println("  " + who + " → terrain_state: " + v.state()
                + (v.reason() == null ? "" : " · pending_reason: " + v.reason())
                + " · forge_mode: " + v.mode());
        v.notes().forEach(n -> System.out.println("      " + n));
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("    ✔ " + what);
        } else {
            failures.add(what + (detail == null || detail.isBlank() ? "" : " — " + detail));
            System.out.println("    ✖ " + what + (detail == null || detail.isBlank() ? "" : " — " + detail));
        }
    }
}
