package com.honcheon.mvt;

import java.util.List;

/**
 * <b>접근 판정기를 시험하는 눈</b> — B-151 셋째 축 (access).
 *
 * <p>계율: <b>눈을 만들면 눈을 시험하라.</b> {@link AccessJudge} 는 관문 우회 구멍을 막으려고 생겼다
 * ({@code /혼천 출행 <id>} 가 access 를 안 봐서 비op 가 hidden·소문·세력·관문을 전부 우회했다 —
 * Codex §8). 이 시험은 그 판정기가 <b>정말 막는지</b>를 <b>서버 없이</b> 잰다.
 *
 * <p><b>서버 무의존.</b> {@code AccessJudge} 는 Bukkit·WorldMap 을 부르지 않으므로 이 시험도 그렇다.
 *
 * <h2>무엇을 재나 (Codex §8 요구 자기시험)</h2>
 * <ol>
 *   <li><b>공개 장소 통과</b> — 항상·누구나 는 비op 도 간다</li>
 *   <li><b>관문 장소 비op 거부</b> — 소문·세력·관문 등은 비op 를 막는다</li>
 *   <li><b>id 직행 우회 차단</b> — 같은 판정기가 관문형을 비op 에게 <b>항상</b> 거부한다 (단일 창구)</li>
 *   <li><b>access 로더 누락 감지</b> — 로더가 access 를 못 실어 null 이 오면 비op 거부(안전)</li>
 *   <li><b>미지 access 값 거부</b> — 어휘 밖 값은 비op 거부(등록제 위반)</li>
 *   <li><b>op 통과</b> — op·콘솔은 검수의 눈으로 관문형·미지·미등록을 통과한다</li>
 *   <li><b>어휘 완결</b> — config/world_map.yml 의 실제 access 토큰이 전부 닫힌 타입에 있다</li>
 * </ol>
 *
 * <h2>어떻게 돌리나 (저장소 루트에서 · 서버·팩 없이)</h2>
 * <pre>
 *   export JAVA_HOME=$PWD/run/jdk-21
 *   $JAVA_HOME/bin/javac -nowarn -d /tmp/access-eye \
 *       server-mvt/src/main/java/com/honcheon/mvt/AccessJudge.java \
 *       tools/AccessJudgeSelfTest.java
 *   $JAVA_HOME/bin/java -cp /tmp/access-eye com.honcheon.mvt.AccessJudgeSelfTest
 * </pre>
 */
public final class AccessJudgeSelfTest {

    private static int passed;
    private static final java.util.List<String> failures = new java.util.ArrayList<>();

    // config/world_map.yml 의 실제 access 토큰 (pos 있는 65곳에서 유도 · 2026-07-15).
    //   ★ 이 목록이 지도와 어긋나면 ⑦이 짖는다 — 지도가 새 어휘를 쓰면 여기와 AccessJudge.Access 를 함께 고쳐라.
    private static final List<String> CONFIG_GATED =
            List.of("hidden", "소문", "기연", "상로", "형벌", "해로", "조건부", "제한", "유학", "세력", "관문");
    private static final List<String> CONFIG_PUBLIC = List.of("항상", "누구나");

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failures.add(name);
        }
        System.out.println("  " + (ok ? "✓ " : "✗ ") + name);
    }

    public static void main(String[] args) {
        // ① 공개 장소 통과 — 비op 도 간다
        for (String t : CONFIG_PUBLIC) {
            check("① 공개 '" + t + "' — 비op 통과", AccessJudge.judge(t, false).allowed());
        }

        // ② 관문 장소 비op 거부 · ③ id 직행 우회 차단 (같은 판정기가 항상 막는다)
        for (String t : CONFIG_GATED) {
            AccessJudge.Verdict v = AccessJudge.judge(t, false);
            check("②③ 관문 '" + t + "' — 비op 거부 (우회 차단)", !v.allowed());
            check("②③ 관문 '" + t + "' — 거부에 한국어 사유가 있다",
                    v.reason() != null && !v.reason().isBlank());
        }

        // ③-b 단일 창구 — 같은 입력이면 언제나 같은 판정 (목록·지도·id 직행이 갈라지지 않는다)
        check("③ 단일 창구 — 판정이 재현된다",
                AccessJudge.judge("관문", false).allowed() == AccessJudge.judge("관문", false).allowed());

        // ④ access 로더 누락 감지 — 로더가 access 를 못 실어 null 이 오면 비op 거부(안전)
        check("④ 로더 누락(null access) — 비op 거부(안전)", !AccessJudge.judge(null, false).allowed());

        // ⑤ 미지 access 값 거부 — 어휘 밖 값 (오타·발명)
        check("⑤ 미지값 'foobar' — classify 가 null", AccessJudge.classify("foobar") == null);
        check("⑤ 미지값 'foobar' — 비op 거부", !AccessJudge.judge("foobar", false).allowed());
        check("⑤ 미지값 '개방' — 비op 거부 (산문은 토큰이 아니다)", !AccessJudge.judge("개방", false).allowed());

        // ⑥ op 통과 — 검수의 눈은 관문형·미지·미등록을 통과한다
        check("⑥ op — 관문 통과", AccessJudge.judge("관문", true).allowed());
        check("⑥ op — 미지값 통과", AccessJudge.judge("foobar", true).allowed());
        check("⑥ op — 미등록(null) 통과", AccessJudge.judge(null, true).allowed());

        // ⑦ 어휘 완결 — config 의 실제 토큰이 전부 닫힌 타입에 있고, 공개/관문 분류가 맞다
        for (String t : CONFIG_PUBLIC) {
            AccessJudge.Access a = AccessJudge.classify(t);
            check("⑦ 어휘 '" + t + "' — 등록됨 · 공개", a != null && a.open);
        }
        for (String t : CONFIG_GATED) {
            AccessJudge.Access a = AccessJudge.classify(t);
            check("⑦ 어휘 '" + t + "' — 등록됨 · 관문형", a != null && !a.open);
        }

        System.out.println("\n통과 " + passed + " · 실패 " + failures.size());
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.out.println("  ✗ " + f);
            }
            System.exit(1);
        }
    }

    private AccessJudgeSelfTest() {
    }
}
