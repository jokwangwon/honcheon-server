package com.honcheon.mvt;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * 접속의 문(門) — <b>마크 채팅에서 디스코드로 가는 유일한 길.</b>
 *
 * <p><b>무엇이 벽이었나.</b> 접합의 방향(마크가 코드를 내고 디스코드가 확정한다)은 옳다 —
 * 그것을 뒤집으면 코드 한 줄이 캐릭터의 열쇠가 된다. 그런데 사람이 치른 값은 규약과 무관한 셋이었다:
 * <b>여섯 자를 외우고 · 창을 바꿔 디스코드를 찾아가고 · 명령 문법을 다시 친다.</b>
 * 벽은 자물쇠가 아니라 <b>손놀림</b>에 있었다. 그래서 손놀림만 깎는다.
 *
 * <pre>
 *   [혼천 접속]  클릭 → 디스코드 접속 채널이 열린다   (ClickEvent OPEN_URL)
 *   [코드 복사]  클릭 → 코드가 클립보드로            (ClickEvent COPY_TO_CLIPBOARD, 1.15+)
 * </pre>
 *
 * <p><b>★ 마크가 못 하는 것 — 정직하게.</b> 마크는 디스코드 앱을 열어 <b>명령을 대신 쳐 줄 수 없다.</b>
 * 클라이언트가 클릭으로 여는 것은 URL 뿐이고(그것도 확인 창을 한 번 거친다), 허용 스킴은 <b>http/https 뿐</b>이다 —
 * {@code discord://} 딥링크는 바닐라 클라이언트가 거부한다. 그래서 여는 것은
 * {@code https://discord.com/channels/<길드>/<채널>} 이다: 브라우저가 뜨고, 데스크톱에 앱이 깔려 있으면
 * 브라우저가 앱으로 넘긴다. <b>거기까지가 마크의 손이 닿는 끝이다.</b> 그 다음 한 걸음(명령을 없애는 것)은
 * 디스코드 쪽에서 했다 — 접속 채널에 <b>버튼</b>이 상시로 붙어 있고, 누르면 코드 한 칸짜리 창이 뜬다.
 * <b>붙여넣기 한 번이면 끝이다.</b>
 *
 * <p><b>자물쇠는 그대로다.</b> 클립보드에 담기는 것도, 창에 붙여넣는 것도 <b>코드</b>이고, 그 코드는
 * <b>그 몸의 주인 화면에만</b> 떴다. 버튼에는 코드가 들어 있지 않다 — 누가 눌러도 좋다. 확정에 필요한 것은
 * 여전히 둘이다: <b>코드 + 디스코드가 서명한 신원</b>. TTL·1회성·1:1·감사 로그는 한 칸도 건드리지 않았다.
 *
 * <p><b>문장도 URL 도 여기서 짓지 않는다</b> — 문장은 {@code world_bridge.yml identity.gate.mc_chat},
 * URL 은 봇이 제가 붙어 있는 채널로 만들어 스냅숏에 실어 보낸다 ({@link WorldBridge#discordUrl()}).
 */
public final class LinkGate {

    private LinkGate() {
    }

    /**
     * 코드를 받은 그 사람에게 보여 줄 문 — <b>본인에게만</b> 보낸다 (공개 채팅 금지: 코드가 곧 값이다).
     *
     * <pre>
     *   // MvtCommand.link — 코드를 받은 직후
     *   player.sendMessage(LinkGate.gate(code));
     * </pre>
     *
     * @param code {@link WorldBridge#requestLink} 가 낸 일회용 코드
     * @return 클릭 가능한 한 덩이 (문이 안 섰으면 [코드 복사]만 — 없는 URL 을 걸지 않는다)
     */
    public static Component gate(String code) {
        String url = WorldBridge.discordUrl();
        int minutes = Math.max(1, WorldBridge.linkTtlSeconds() / 60);

        Component out = Component.text(
                        WorldBridge.gateText("headline", "접합 코드가 나왔다 — 강호에 이름을 올릴 차례다"),
                        NamedTextColor.YELLOW)
                .append(Component.newline());

        if (url != null) {
            out = out.append(Component.text(WorldBridge.gateText("open_label", "[혼천 접속]"),
                            NamedTextColor.AQUA, TextDecoration.BOLD)
                    // ★ 클릭 = URL 열기. 마크가 할 수 있는 것은 여기까지다 (앱을 열어 명령을 치지는 못한다)
                    .clickEvent(ClickEvent.openUrl(url))
                    .hoverEvent(HoverEvent.showText(Component.text(
                            WorldBridge.gateText("open_hover", "디스코드의 접속 채널을 연다"),
                            NamedTextColor.GRAY))));
            out = out.append(Component.text("  ", NamedTextColor.DARK_GRAY));
        } else {
            out = out.append(Component.text(
                            WorldBridge.gateText("no_url", "접속 채널이 아직 등록되지 않았다"),
                            NamedTextColor.RED))
                    .append(Component.newline());
        }

        out = out.append(Component.text(WorldBridge.gateText("copy_label", "[코드 복사]"),
                        NamedTextColor.GREEN, TextDecoration.BOLD)
                // 클립보드 (1.15+) — 외울 것이 없어진다. 디스코드에서는 붙여넣기 한 번
                .clickEvent(ClickEvent.copyToClipboard(code))
                .hoverEvent(HoverEvent.showText(Component.text(
                        WorldBridge.gateText("copy_hover", "코드를 클립보드에 담는다") + "\n" + code,
                        NamedTextColor.GRAY))));

        return out.append(Component.newline())
                .append(Component.text(tail(code, minutes), NamedTextColor.GRAY));
    }

    /**
     * 접합 안 한 몸에게 뜨는 <b>클릭 한 번</b> — 누르면 {@code /혼천 접속} 이 대신 실행된다.
     *
     * <p>여기는 <b>마크 명령</b>이므로 마크가 대신 쳐 줄 수 있다 (RUN_COMMAND). 디스코드 명령은 못 친다 —
     * 그래서 그쪽은 버튼으로 없앴다. 첫 접속의 잔소리({@code SkillListener.nag})에 한 줄 붙이면 된다:
     *
     * <pre>
     *   on.unlinkedLines().forEach(player::sendMessage);
     *   player.sendMessage(LinkGate.callToAction());   // ← 이 한 줄
     * </pre>
     */
    public static Component callToAction() {
        return Component.text(WorldBridge.gateText("nag_label", "[강호에 이름을 올린다]"),
                        NamedTextColor.GOLD, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(
                        WorldBridge.gateText("nag_command", "/혼천 접속")))
                .hoverEvent(HoverEvent.showText(Component.text(
                        WorldBridge.gateText("nag_hover", "누르면 접합 코드가 나온다"),
                        NamedTextColor.GRAY)));
    }

    /** 이미 이어진 몸에게 — 다시 이으려면 먼저 끊어야 한다 (relink: 거부) */
    public static Component already(String linkedName) {
        return Component.text(WorldBridge.gateText("already", "이미 {name} 으(로) 이어져 있다")
                .replace("{name}", linkedName), NamedTextColor.GRAY);
    }

    /** 등록부의 꼬리말 — {code}·{minutes} 만 채운다 (문장은 config 의 것이다) */
    private static String tail(String code, int minutes) {
        return WorldBridge.gateText("tail", "코드 {code} · 유효 {minutes}분")
                .replace("{code}", code)
                .replace("{minutes}", String.valueOf(minutes));
    }
}
