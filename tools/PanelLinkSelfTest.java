package com.honcheon.bot;

import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B-117 조립의 눈 — <b>접속이 명령어 타이핑이 아니라 버튼+모달인가. 그리고 그 모달이
 * 명령과 같은 파이프로 흐르는가.</b>
 *
 * <p>사용자 실측 (2026-07-14): 캐릭터 생성 직후 안내가 "여기서 {@code /혼천 접속 닉네임:…} 을 친다" —
 * "디스코드 닉네임 입력 시스템이 아닌 명령어 입력 시스템으로 되어 있다."
 *
 * <p>이 눈이 재는 것:
 * <ol>
 *   <li><b>조립</b> — 접합 모달·버튼(접합문 {@code lk:open} · 이정표 {@code lk:open})이
 *       올바른 id·등록부의 라벨을 갖는가 (JDA 객체를 <b>빌드만</b> 한다 — 디스코드 실호출 없음)</li>
 *   <li><b>파이프 동일성</b> — 모달 제출({@code onModalInteraction})과 슬래시({@code linkAccount})가
 *       <b>같은 함수</b>({@code askLink})로 흐르는가 (소스의 배선을 주석 제거 후 직접 읽는다)</li>
 *   <li><b>등록제</b> — 라벨·제목·본문이 코드가 아니라 등록부(seojang.yml · world_bridge.yml ·
 *       discord_panel.yml)에서 오는가. 이정표 본문이 실제 버튼 라벨을 가리키는가</li>
 * </ol>
 *
 * <h2>어떻게 돌리나 (저장소 루트에서 · 봇을 켜지 않는다 — 디스코드에 아무것도 보내지 않는다)</h2>
 * <pre>
 *   export JAVA_HOME=$PWD/run/jdk-21
 *   ./gradlew :server-bot:jar -q
 *   JAR=server-bot/build/libs/server-bot-0.1.0.jar
 *   $JAVA_HOME/bin/javac -d /tmp/panel-link-eye -cp "$JAR" tools/PanelLinkSelfTest.java
 *   $JAVA_HOME/bin/java -cp "$JAR:/tmp/panel-link-eye" com.honcheon.bot.PanelLinkSelfTest
 * </pre>
 */
public final class PanelLinkSelfTest {

    private static int eyes;
    private static int blind;

    private PanelLinkSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Rules rules = new Rules(Path.of("config"));

        // ══════════ ① 모달 — 조립 (JDA 객체 빌드만. 아무 데도 보내지 않는다) ══════════
        Modal m = GameListener.linkModal(rules);
        eye("모달 id 가 판독 상수와 같다 (lk:submit)",
                m.getId().equals(GameListener.LINK_MODAL_ID) && "lk:submit".equals(m.getId()));
        eye("모달 제목이 등록부(gate.discord.modal_title)에서 온다",
                !rules.gateText("modal_title", "").isBlank()
                        && m.getTitle().equals(rules.gateText("modal_title", "")));
        ActionRow row = (ActionRow) m.getComponents().get(0);
        eye("칸이 한 줄 · 한 칸이다 (마크 닉네임 하나)",
                m.getComponents().size() == 1 && row.getComponents().size() == 1);
        TextInput in = (TextInput) row.getComponents().get(0);
        eye("칸 id 가 판독 상수와 같다 (getValue 가 같은 이름을 쥔다)",
                GameListener.LINK_NICK_INPUT.equals(in.getId()));
        eye("칸 라벨이 등록부(gate.discord.modal_field)에서 온다",
                !rules.gateText("modal_field", "").isBlank()
                        && in.getLabel().equals(rules.gateText("modal_field", "")));
        eye("한 줄짜리 SHORT · 필수 · 3~16자 (마크 닉의 꼴)",
                in.getStyle() == TextInputStyle.SHORT && in.isRequired()
                        && in.getMinLength() == 3 && in.getMaxLength() == 16);

        // ══════════ ② 버튼 — 문은 셋이어도 창은 하나다 ══════════
        Button gate = GameListener.gateLinkButton(rules);
        Button sign = GameListener.signpostLinkButton(rules);
        eye("접합문 버튼과 이정표 버튼이 **같은 문**이다 (lk:open)",
                GameListener.LINK_OPEN_ID.equals(gate.getId())
                        && GameListener.LINK_OPEN_ID.equals(sign.getId()));
        eye("접합문 버튼 라벨이 등록부(gate.discord.button_label)에서 온다",
                !rules.gateText("button_label", "").isBlank()
                        && gate.getLabel().equals(rules.gateText("button_label", "")));
        eye("이정표 버튼 라벨이 등록부(seojang signpost.link_label)에서 온다",
                !rules.seojang.signpost("link_label", "").isBlank()
                        && sign.getLabel().equals(rules.seojang.signpost("link_label", "")));
        eye("이정표 본문 ②가 그 라벨을 그대로 가리킨다 ([마크와 잇기] — 없는 버튼을 말하지 않는다)",
                rules.seojang.signpost("body", "").contains("[" + sign.getLabel() + "]"));
        eye("이정표 본문이 명령을 **주 경로**로 가리키지 않는다 (명령은 뒷문 한 줄뿐)",
                !rules.seojang.signpost("body", "").contains("② 여기서 `/혼천 접속"));
        eye("판의 [마크와 잇기] 라벨이 등록부(board.link_label)에 있다",
                !rules.panelBoard("link_label", "").isBlank());
        eye("판의 본문이 [마크와 잇기] 를 안내한다",
                rules.panelBoard("body", "").contains("[마크와 잇기]"));

        // ══════════ ③ 파이프 동일성 — 배선을 소스에서 직접 읽는다 (주석은 배선이 아니다) ══════════
        String src = stripComments(Files.readString(
                Path.of("server-bot/src/main/java/com/honcheon/bot/GameListener.java")));
        eye("모달 판독이 조립과 **같은 상수**로 알아본다 (LINK_MODAL_ID)",
                methodBody(src, "onModalInteraction").contains("LINK_MODAL_ID.equals(event.getModalId())"));
        eye("모달 제출 → askLink (버튼 경로의 파이프)",
                methodBody(src, "onModalInteraction").contains("askLink("));
        eye("/혼천 접속 → askLink (명령 경로의 파이프 — **같은 함수다**)",
                methodBody(src, "linkAccount").contains("askLink("));
        eye("버튼(lk · np:link)이 여는 창이 **그 모달**이다 (linkModal 하나)",
                methodBody(src, "openLinkModal").contains("linkModal(rules)")
                        && !methodBody(src, "openLinkModal").contains("Modal.create"));
        eye("안내판에 np:link 가 박혀 있다 (postPanel)",
                methodBody(src, "postPanel").contains("\"np:link\""));
        eye("생성 완료 이정표에 잇기 버튼이 실려 있다 (finishCreation)",
                methodBody(src, "finishCreation").contains("signpostLinkButton(rules)"));
        eye("np:link 의 배선 — onPanel 이 openLinkModal 로 보낸다",
                methodBody(src, "onPanel").matches("(?s).*case \"link\" -> openLinkModal\\(event\\);.*"));

        System.out.println();
        if (blind > 0) {
            System.out.println("❌ " + blind + "/" + eyes + " 이 어긋났다 — 접속의 문이 성치 않다");
            System.exit(1);
        }
        System.out.println("✅ " + eyes + "/" + eyes
                + " — 문은 셋(접합문·안내판·이정표), 창은 하나(lk:submit), 파이프도 하나(askLink)");
    }

    private static void eye(String name, boolean ok) {
        eyes++;
        if (!ok) {
            blind++;
        }
        System.out.println("  " + (ok ? "✅" : "❌") + " " + name);
    }

    /** 주석에 적힌 약속은 배선이 아니다 — link_audit 이 처음 스스로 속은 자리다 */
    private static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    /** 메서드 하나의 몸통 — 이름으로 찾아 중괄호를 세어 닫는다 (tools/panel_audit.py 와 같은 문법) */
    private static String methodBody(String text, String name) {
        Matcher m = Pattern.compile("\\bvoid\\s+" + Pattern.quote(name) + "\\s*\\(").matcher(text);
        if (!m.find()) {
            return "";
        }
        int i = text.indexOf('{', m.end());
        if (i < 0) {
            return "";
        }
        int depth = 0;
        for (int j = i; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return text.substring(i, j + 1);
            }
        }
        return text.substring(i);
    }
}
