package com.honcheon.mvt;

import java.util.UUID;

/**
 * B-116 의 눈 — <b>액션바 한 줄의 주인 규칙이 서 있는가.</b>
 *
 * <p>실사용 발견 (2026-07-14): "격 두름의 텍스트가 다른 텍스트와 겹쳐 있어 무슨 말인지 파악 힘듦.
 * 경공 나는 것도 동일." 기전 — 액션바는 한 줄인데 손이 여럿: 순간 문구(맨 actionBar)를
 * 4틱(0.2초)마다 도는 statusBar 가 곧바로 덮었다. 수리 — {@link HudLine} 주인 규칙:
 * <ul>
 *   <li>순간 사건은 flash 로 읽을 시간(skill_motion.yml hud.flash_read_ticks)만큼 줄을 갖는다</li>
 *   <li>지속 상태(생명·태세·격 두름·내력·경공 유지)는 compose 로 한 줄에 병기된다</li>
 * </ul>
 *
 * <h2>어떻게 돌리나 (저장소 루트에서 · 서버를 켜지 않는다 — HudLine 은 Bukkit 을 모른다)</h2>
 * <pre>
 *   export JAVA_HOME=$PWD/run/jdk-21
 *   ./gradlew :server-mvt:compileJava -q
 *   CP=server-mvt/build/classes/java/main
 *   $JAVA_HOME/bin/javac -d /tmp/hudline-eye -cp "$CP" tools/HudLineSelfTest.java
 *   $JAVA_HOME/bin/java -cp "$CP:/tmp/hudline-eye" com.honcheon.mvt.HudLineSelfTest
 * </pre>
 */
public final class HudLineSelfTest {

    private static int eyes;
    private static final String SEP = "§8 │ ";

    private HudLineSelfTest() {
    }

    public static void main(String[] args) {
        UUID id = UUID.randomUUID();
        HudLine line = new HudLine();

        // ══════════ ① 평시 — flash 가 없으면 줄은 지속 상태(합성)의 것이다 ══════════
        eye("flash 없음 → 주인 없음 (statusBar 의 차례)", line.owner(id, 0) == null);

        // ══════════ ② flash 끼어듦 — 순간 사건이 줄을 갖는다 (statusBar 가 못 덮는다) ══════════
        line.flash(id, "검기 — 기가 날에 서린다", 15);
        eye("flash 직후(틱 1) — 사건의 글자가 줄을 갖는다",
                "검기 — 기가 날에 서린다".equals(line.owner(id, 1)));
        eye("statusBar 주기(틱 4)에도 아직 사건의 글자다 — 겹침(즉시 덮임)이 없다",
                "검기 — 기가 날에 서린다".equals(line.owner(id, 4)));
        eye("만료 직전(틱 15 = until)까지 붙든다", line.owner(id, 15) != null);

        // ══════════ ③ 복귀 — 읽을 시간이 지나면 줄을 놓는다 (statusBar 가 돌아온다) ══════════
        eye("만료(틱 16) → 주인 없음 (지속 상태 합성이 돌아온다)", line.owner(id, 16) == null);
        eye("만료 뒤에는 기억도 없다 (시계가 되돌아도 안 살아난다)", line.owner(id, 1) == null);

        // ══════════ ④ 사건 위의 사건 — 마지막 사건이 이긴다 (가장 지금이니까) ══════════
        line.flash(id, "몸이 가벼워진다", 30);
        line.flash(id, "회피 │ 방어 12", 40);
        eye("나중 flash 가 먼저 flash 를 대체한다", "회피 │ 방어 12".equals(line.owner(id, 20)));
        eye("대체된 사건의 만료(30)가 아니라 새 사건의 만료(40)로 산다",
                line.owner(id, 35) != null && line.owner(id, 41) == null);

        // ══════════ ⑤ forget — 몸이 나가면 줄의 기억도 지운다 ══════════
        line.flash(id, "남은 글자", 100);
        line.forget(id);
        eye("forget 뒤 주인 없음", line.owner(id, 50) == null);

        // ══════════ ⑥ 두 몸 — 한 몸의 flash 가 다른 몸의 줄을 잡지 않는다 ══════════
        UUID other = UUID.randomUUID();
        line.flash(id, "내 판정", 100);
        eye("남의 줄은 비어 있다", line.owner(other, 50) == null
                && "내 판정".equals(line.owner(id, 50)));

        // ══════════ ⑦ 합성 — 두 상태 동시: 겹치지 않고 병기된다 (B-116 의 반대말) ══════════
        eye("격 두름 + 경공 유지 = 한 줄의 다른 자리",
                "검기(氣)§8 │ 경공 · 허공 1/2".equals(HudLine.compose(SEP, "검기(氣)", "경공 · 허공 1/2")));
        eye("null·빈 조각은 건너뛴다 (구분자가 고아가 되지 않는다)",
                "생명§8 │ 내력".equals(HudLine.compose(SEP, "생명", null, "", "내력")));
        eye("전부 비면 빈 줄", HudLine.compose(SEP, null, "").isEmpty());
        eye("한 조각이면 구분자가 없다", "외공(外功)".equals(HudLine.compose(SEP, "외공(外功)", null)));

        System.out.println("HudLineSelfTest — 눈 " + eyes + "개 전부 떠 있다 (exit 0)");
    }

    private static void eye(String what, boolean holds) {
        eyes++;
        if (!holds) {
            System.err.println("✗ " + what);
            System.exit(1);
        }
        System.out.println("✓ " + what);
    }
}
