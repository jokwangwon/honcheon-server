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
 *   <li>바깥 지속 표시(비무·서장·은닉)는 notice 채널 조각 — 합성 한 줄의 다른 자리다 (★Codex R6~R8)</li>
 *   <li>지속 상태(생명·태세·격 두름·내력·경공 유지)는 compose 로 한 줄에 병기된다</li>
 * </ul>
 *
 * <p>⑨ 는 <b>정적 감사</b>다 — server-mvt 소스를 훑어 중재기 밖의 맨
 * {@code sendActionBar}·{@code ChatMessageType.ACTION_BAR} 손을 찾는다 (허용 = SkillHud 하나).
 * 눈을 시험하는 눈: 위반을 심은 표본 문자열이 잡히는지 먼저 잰다.
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

        // ══════════ ⑧ notice — 바깥 지속 표시는 채널 조각이다 (전역 소유권 · ★Codex R6~R8) ══════════
        HudLine bus = new HudLine();
        UUID body = UUID.randomUUID();
        bus.notice(body, "비무", "비무 30초", 24);
        bus.notice(body, "은닉", "은닉 40%", 24);
        eye("두 채널 = 두 조각, 등재 순서대로 (마지막-승자 덮어쓰기가 없다)",
                java.util.List.of("비무 30초", "은닉 40%").equals(bus.notices(body, 10)));
        bus.notice(body, "비무", "비무 29초", 44);
        eye("같은 채널 재송신은 글자만 갈고 자리를 지킨다",
                java.util.List.of("비무 29초", "은닉 40%").equals(bus.notices(body, 10)));
        eye("만료된 채널은 조각에서 빠진다 (TTL) — 남은 채널은 산다",
                java.util.List.of("비무 29초").equals(bus.notices(body, 30)));
        bus.dropNotice(body, "비무");
        eye("dropNotice — 끝난 판의 카운트다운은 만료를 기다리지 않는다", bus.notices(body, 31).isEmpty());
        bus.notice(body, "서장", "먹을 가는 소리…", 100);
        bus.flash(body, "회피 │ 방어 12", 50);
        eye("flash 는 notice 위다 (우선순위 계약) — 줄의 주인은 순간 사건",
                "회피 │ 방어 12".equals(bus.owner(body, 40)));
        eye("flash 만료가 notice 를 지우지 않는다 — 조각은 제 수명대로 산다",
                bus.owner(body, 51) == null
                        && java.util.List.of("먹을 가는 소리…").equals(bus.notices(body, 60)));
        bus.forget(body);
        eye("forget 은 조각의 기억도 지운다 (나간 몸)", bus.notices(body, 60).isEmpty());

        // ══════════ ⑨ 정적 감사 — 중재기 밖의 맨 액션바 손이 남아 있는가 ══════════
        // 눈을 시험하는 눈 — 위반 표본이 안 잡히면 이 감사는 장님이다
        eye("[자기시험] 심은 위반(sendActionBar)이 잡힌다",
                stray("HuntListener.java", "  player.sendActionBar(x);\n").size() == 1);
        eye("[자기시험] 심은 위반(ChatMessageType.ACTION_BAR)이 잡힌다",
                stray("Foo.java", "spigot().sendMessage(ChatMessageType.ACTION_BAR, tc);\n").size() == 1);
        eye("[자기시험] 주석 속 언급은 위반이 아니다",
                stray("Bar.java", "// 맨 sendActionBar 는 덮인다 (B-116)\n"
                        + "/* ChatMessageType.ACTION_BAR 도 */\n").isEmpty());
        eye("[자기시험] SkillHud(유일한 문)는 허용이다",
                stray("SkillHud.java", "player.spigot().sendMessage(ChatMessageType.ACTION_BAR, m);\n")
                        .isEmpty());
        java.nio.file.Path root = java.nio.file.Path.of(
                args.length > 0 ? args[0] : "server-mvt/src/main/java");
        if (!java.nio.file.Files.isDirectory(root)) {
            System.err.println("✗ 감사할 소스가 안 보인다: " + root.toAbsolutePath()
                    + " (저장소 루트에서 돌리거나 경로를 인자로)");
            System.exit(1);
        }
        java.util.List<String> strays = new java.util.ArrayList<>();
        try (var walk = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path p : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                strays.addAll(stray(p.getFileName().toString(), java.nio.file.Files.readString(p)));
            }
        } catch (java.io.IOException e) {
            System.err.println("✗ 감사 실패: " + e);
            System.exit(1);
        }
        strays.forEach(s -> System.err.println("  잔존 손: " + s));
        eye("중재기 밖의 맨 액션바 손 0곳 (문은 SkillHud.actionBar 하나)", strays.isEmpty());

        System.out.println("HudLineSelfTest — 눈 " + eyes + "개 전부 떠 있다 (exit 0)");
    }

    /**
     * 파일 하나에서 중재기 밖의 맨 액션바 호출을 찾는다 — 주석(//·블록)은 걷어 내고 잰다.
     * SkillHud 는 유일하게 허용된 문이다 (HudLine 이 중재한 글자만 거기서 나간다).
     */
    private static java.util.List<String> stray(String fileName, String source) {
        if ("SkillHud.java".equals(fileName)) {
            return java.util.List.of();
        }
        // 블록 주석은 줄 수를 지키며 지운다 — 신고 줄번호가 어긋나면 사람이 못 찾는다
        String bare = java.util.regex.Pattern.compile("(?s)/\\*.*?\\*/")
                .matcher(source).replaceAll(m -> m.group().replaceAll("[^\n]", ""));
        java.util.List<String> hits = new java.util.ArrayList<>();
        String[] lines = bare.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String code = lines[i].replaceFirst("//.*$", "");
            if (code.contains("sendActionBar") || code.contains("ChatMessageType.ACTION_BAR")) {
                hits.add(fileName + ":" + (i + 1) + "  " + code.strip());
            }
        }
        return hits;
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
