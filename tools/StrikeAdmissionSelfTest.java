package com.honcheon.mvt;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>타격 허용의 눈 (B-119)</b> — <b>누가 맞을 수 있는가의 문이 등록부 말대로 서는가.</b>
 *
 * <p>사용자 실측 (2026-07-14): <i>"npc와 동물등 안때려짐 (이거에 대한 조건이 필요할듯)"</i> —
 * 그리고 정정: <i>"마을 npc도 때려져야합니다. 몰래 죽일수도 있어야 해요."</i>
 *
 * <p>그래서 참이어야 하는 것: <b>기본은 전부 허용</b>이다 — 사냥감·가축·도적·비무상대·마을 NPC·
 * 행인·지역 사람, 전부. 문이 닫는 것은 {@code combat.yml strike_admission.immune_marks} 의
 * 표식뿐이고 그 목록은 기본이 빈 목록이다.
 *
 * <h2>★ 일부러 어긴다</h2>
 * 무엇이든 통과시키는 문은 문이 아니다 — 예외 표식을 일부러 적어 보고(그 표식만 막혀야 한다),
 * default 를 일부러 닫아 보고(전부 막혀야 한다), 절을 통째로 빼 본다(전부 허용 — 서버는 떠야 한다).
 *
 * <h2>어떻게 돌리나 (저장소 루트에서 · 서버를 켜지 않는다)</h2>
 * <pre>
 *   export JAVA_HOME=$PWD/run/jdk-21
 *   ./gradlew :server-mvt:jar
 *   CP="$(find ~/.gradle -name 'snakeyaml-2.2*.jar' | head -1):server-mvt/build/libs/server-mvt-1.0.0.jar"
 *   $JAVA_HOME/bin/javac -nowarn -d /tmp/strike-eye -cp "$CP" tools/StrikeAdmissionSelfTest.java
 *   $JAVA_HOME/bin/java -cp "$CP:/tmp/strike-eye" com.honcheon.mvt.StrikeAdmissionSelfTest
 * </pre>
 */
public final class StrikeAdmissionSelfTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        // ══════════ ① 저장소의 진짜 등록부 — config/combat.yml strike_admission 그대로 ══════════
        System.out.println("【지금 이 저장소】 config/combat.yml strike_admission 그대로");
        Map<String, Object> combat;
        try (InputStream in = Files.newInputStream(Path.of("config", "combat.yml"))) {
            combat = new Yaml().load(in);
        }
        Map<String, Object> section = (Map<String, Object>) combat.get("strike_admission");
        check("등록부에 strike_admission 절이 있다", section != null, "절 없음");
        StrikeAdmission real = StrikeAdmission.load(section);

        // 다섯 결 — 사냥감 / 가축 / NPC(마을·도적·행인·지역) / 비무 / 플레이어. 전부 허용이어야 한다.
        for (String mark : List.of(StrikeAdmission.MARK_PREY, StrikeAdmission.MARK_LIVESTOCK,
                StrikeAdmission.MARK_TOWN_NPC, StrikeAdmission.MARK_BANDIT,
                StrikeAdmission.MARK_POPULACE, StrikeAdmission.MARK_REGION,
                StrikeAdmission.MARK_SPAR, StrikeAdmission.MARK_PLAYER)) {
            check("★ " + mark + " — 맞는다 (기본: 전부 허용)", real.allowed(mark), "막혔다");
        }
        check("무명(표식 없는 몸)도 맞는다", real.allowed(null), "막혔다");
        check("허용이면 닫힌 이유가 없다", real.refusal(StrikeAdmission.MARK_PREY) == null,
                String.valueOf(real.refusal(StrikeAdmission.MARK_PREY)));

        // 등록부의 어휘와 코드의 어휘가 같은가 — marks 목록의 낱말이 전부 코드에 있어야 한다
        Object marks = section == null ? null : section.get("marks");
        check("marks 어휘가 등록돼 있다", marks instanceof List<?> l && l.size() == 8,
                String.valueOf(marks));

        // ══════════ ② 표식 환산 — 몸의 신원이 낱말이 되는 자리 (판정이 쓰는 그 함수) ══════════
        System.out.println("\n【표식 환산】 StrikeAdmission.mark — 신원 → 낱말");
        checkMark("플레이어", StrikeAdmission.mark(true, false, null, null, null, false, false),
                StrikeAdmission.MARK_PLAYER);
        checkMark("마을 NPC (PDC town_npc)", StrikeAdmission.mark(false, true, null, null, null, false, false),
                StrikeAdmission.MARK_TOWN_NPC);
        checkMark("산늑대 (짐승·들짐승)", StrikeAdmission.mark(false, false, "짐승", "들짐승", "짐승", false, false),
                StrikeAdmission.MARK_PREY);
        checkMark("닭 (짐승·가축)", StrikeAdmission.mark(false, false, "짐승", "가축", "짐승", false, false),
                StrikeAdmission.MARK_LIVESTOCK);
        checkMark("산길 도적 (사람·졸개)", StrikeAdmission.mark(false, false, "사람", null, "졸개", false, false),
                StrikeAdmission.MARK_BANDIT);
        checkMark("곽진의 비무 몸 (사람·비무상대)", StrikeAdmission.mark(false, false, "사람", null, "비무상대", false, false),
                StrikeAdmission.MARK_SPAR);
        checkMark("무명 행인 (populace)", StrikeAdmission.mark(false, false, null, null, null, true, false),
                StrikeAdmission.MARK_POPULACE);
        checkMark("지역 사람 (region_npc)", StrikeAdmission.mark(false, false, null, null, null, false, true),
                StrikeAdmission.MARK_REGION);
        checkMark("표식 없는 몸 = 무명(null)", StrikeAdmission.mark(false, false, null, null, null, false, false),
                null);

        // ══════════ ③ 일부러 어긴다 — 문이 정말 닫히는가 ══════════
        System.out.println("\n【일부러 어긴다】 예외 표식 · 닫힌 기본값 · 사라진 절");
        StrikeAdmission doctored = StrikeAdmission.load(Map.of(
                "default", "허용", "immune_marks", List.of(StrikeAdmission.MARK_TOWN_NPC)));
        check("예외 표식(마을NPC)만 막힌다", !doctored.allowed(StrikeAdmission.MARK_TOWN_NPC), "안 막혔다");
        check("예외 밖(사냥감)은 그대로 맞는다", doctored.allowed(StrikeAdmission.MARK_PREY), "막혔다");
        check("막힌 이유가 말해진다 (침묵하는 게이트는 버그로 보인다)",
                doctored.refusal(StrikeAdmission.MARK_TOWN_NPC) != null, "이유 없음");

        StrikeAdmission closed = StrikeAdmission.load(Map.of("default", "거부"));
        check("default 거부 — 전부 막힌다", !closed.allowed(StrikeAdmission.MARK_PREY)
                && !closed.allowed(null), "안 막혔다");

        StrikeAdmission missing = StrikeAdmission.load(null);
        check("절이 없으면 전부 허용 (서버는 떠야 한다 — 기본 자세가 열림)",
                missing.allowed(StrikeAdmission.MARK_PREY) && missing.allowed(null), "막혔다");

        // ══════════ 결산 ══════════
        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("✅ " + passed + "/" + passed + " — 타격 허용의 문이 등록부 말대로 선다");
            return;
        }
        System.out.println("❌ 실패 " + failures.size() + " / 통과 " + passed);
        failures.forEach(f -> System.out.println("   " + f));
        System.exit(1);
    }

    private static void checkMark(String label, String got, String want) {
        check(label + " → " + want, want == null ? got == null : want.equals(got), String.valueOf(got));
    }

    private static void check(String label, boolean ok, String got) {
        if (ok) {
            passed++;
            System.out.println("  ✓ " + label);
        } else {
            failures.add(label + " (실측: " + got + ")");
            System.out.println("  ✗ " + label + " (실측: " + got + ")");
        }
    }
}
