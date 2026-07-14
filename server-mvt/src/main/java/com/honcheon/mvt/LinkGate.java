package com.honcheon.mvt;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * 접속의 문(門) — <b>마크 채팅에서 접합으로 가는 두 화면.</b>
 *
 * <p><b>★ 코드는 죽었다 (2026-07-13).</b> 옛 문은 코드를 냈다: 여섯 자를 클립보드에 담아 주고,
 * 사람이 그것을 디스코드로 날라 붙여넣었다. 자물쇠는 튼튼했으나 <b>사람이 코드를 날랐다</b> —
 * 외우고, 창을 바꾸고, 붙여넣고, 그러다 만료됐다. 사용자의 판정은 짧았다:
 * <i>"코드 복사는 없어져도 되고 초대 링크만 있으면 되고, 닉네임 입력하여 대조."</i>
 *
 * <p><b>그런데 닉네임만으로 이으면 아무나 남의 닉을 대고 그 캐릭터를 가져간다.</b>
 * 그래서 손을 <b>둘</b>로 나눴고, 이 파일은 그중 <b>둘째 손의 화면</b>이다:
 *
 * <pre>
 *   ① 디스코드   /혼천 접속 닉네임:&lt;마크 닉&gt;   ← 디스코드가 **서명한** 신원 (위조 불가)
 *   ② 이 화면    [잇는다] / [아니다]            ← **그 몸에 로그인한 사람** (위조 불가)  ★ 여기
 * </pre>
 *
 * <p><b>★ 왜 남의 닉을 대도 되는가.</b> 닉네임은 <b>열쇠가 아니라 수신인</b>이다. 남의 이름을 대면
 * <b>그 사람의 화면</b>에 물음이 뜰 뿐이고, 그가 [아니다] 를 누르거나 그냥 두면 2분 뒤에 죽는다.
 * 도둑이 얻는 것은 <b>남의 화면에 뜬 물음 하나</b>뿐이다. 이을 수 있는 손은 그 몸의 손뿐이다 —
 * {@link WorldBridge#linkDecision} 이 {@code player.getUniqueId()} 를 청의 {@code mc_uuid} 와 대조한다.
 *
 * <p>두 화면이 여기 있다:
 * <ul>
 *   <li>{@link #invite()} — {@code /혼천 접속} 의 답. <b>초대 링크 하나</b>와 "디스코드에서 그대의 이름을 대라"</li>
 *   <li>{@link #ask(WorldBridge.Request)} — <b>청이 왔다.</b> 그 몸의 화면에만 뜬다 (공개 채팅 금지)</li>
 * </ul>
 *
 * <p><b>문장은 여기서 짓지 않는다</b> — {@code world_bridge.yml identity.gate.mc_chat} 이 정본이다.
 * URL 도 짓지 않는다 — 초대는 봇이 스냅숏에 실어 내려보낸다 ({@link WorldBridge#discordInvite()}).
 */
public final class LinkGate {

    private LinkGate() {
    }

    /**
     * <b>{@code /혼천 접속} 의 답</b> — 이제 코드를 내지 않는다. <b>길을 알려 주는 손</b>이다.
     *
     * <p>마크가 할 수 있는 것은 <b>https URL 을 여는 것까지</b>다 (1.21.5+ 는 http/https 가 아닌 URI 를
     * 파싱조차 하지 않는다 — {@code discord://} 딥링크는 불가능하다). 그래서 거는 것은 <b>초대 링크</b>
     * 하나뿐이고, 나머지 두 걸음은 <b>말로</b> 한다. 등록부에 초대가 없으면 버튼을 띄우지 않는다.
     *
     * @param name 이 몸의 이름 — 디스코드의 닉네임 칸에 <b>그대로</b> 적어야 하는 값이다
     */
    public static Component invite(String name) {
        String url = WorldBridge.discordInvite();
        int seconds = WorldBridge.linkTtlSeconds();

        Component out = Component.text(
                        WorldBridge.gateText("headline", "강호에 이름을 올리려면 — 디스코드에서 그대의 이름을 대라"),
                        NamedTextColor.YELLOW, TextDecoration.BOLD)
                .append(Component.newline());

        if (url != null) {
            out = out.append(Component.text(WorldBridge.gateText("invite_label", "[초대 링크]"),
                            NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                            // ★ 마크의 손은 여기까지다 — URL 을 연다. 앱을 열어 명령을 대신 칠 수는 없다
                            .clickEvent(ClickEvent.openUrl(url))
                            .hoverEvent(HoverEvent.showText(Component.text(
                                    WorldBridge.gateText("invite_hover",
                                            "혼천의 디스코드로 들어오는 초대 — 아직 서버에 없다면 여기부터"),
                                    NamedTextColor.GRAY))))
                    .append(Component.newline());
        } else {
            out = out.append(Component.text(WorldBridge.gateText("no_invite",
                            "초대 링크가 아직 등록되지 않았다 — 관리자에게 물어라"), NamedTextColor.RED))
                    .append(Component.newline());
        }

        // ★ 【사용자 실사용 피드백 2026-07-14】 "채팅이 너무 깁니다 — 두 줄이면 됩니다."
        //   빈 문장은 줄 자체가 안 선다 — 등록부가 몇 줄을 쓸지 정한다 (기본은 두 줄: 링크 + 설명).
        for (String key : new String[]{"step_1", "step_2", "step_3"}) {
            String text = WorldBridge.gateText(key, "");
            if (!text.isBlank()) {
                out = out.append(line(key, text, name, seconds)).append(Component.newline());
            }
        }
        String why = WorldBridge.gateText("why", "");
        if (!why.isBlank()) {
            // ★ 왜 이 번거로움이 있는가를 **말해 준다** — 그것이 자물쇠이기 때문이다
            out = out.append(Component.text(why, NamedTextColor.DARK_GRAY));
        }

        String appNote = WorldBridge.gateText("app_note", "");
        if (url != null && !appNote.isBlank()) {
            out = out.append(Component.newline())
                    .append(Component.text(appNote, NamedTextColor.DARK_GRAY));
        }
        return out;
    }

    /**
     * ★★ <b>청(請)이 왔다 — 이 화면이 자물쇠다.</b>
     *
     * <p>디스코드의 누군가가 <b>이 몸의 이름</b>을 댔다. 그가 정말 이 몸의 주인인지는 <b>이 화면 앞의
     * 사람만</b> 안다. 그래서 묻는다 — 그리고 <b>[잇는다] 를 누르기 전까지 아무것도 이어지지 않는다.</b>
     *
     * <p>버튼은 {@code RUN_COMMAND} 다 (마크 명령이므로 마크가 대신 쳐 줄 수 있다 — 칠 것이 없다).
     * 토큰이 명령에 실리지만 <b>토큰은 열쇠가 아니다</b>: 그것을 어깨너머로 본 자가 제 화면에서 같은 명령을
     * 쳐도, {@link WorldBridge#linkDecision} 이 <b>몸을 대조</b>하므로 아무 일도 일어나지 않는다.
     */
    public static Component ask(WorldBridge.Request req) {
        long left = Math.max(1, (req.expiresAt() - System.currentTimeMillis()) / 1000);

        return Component.text("", NamedTextColor.WHITE)
                .append(Component.text(WorldBridge.gateText("ask_headline",
                                        "디스코드의 「{discord}」 가 그대라고 한다.")
                                .replace("{discord}", req.discordName()),
                        NamedTextColor.YELLOW, TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.text(WorldBridge.gateText("ask_body",
                                        "이 몸을 그 이름({character})에 잇겠는가?")
                                .replace("{character}", req.character()),
                        NamedTextColor.WHITE))
                .append(Component.newline())
                .append(Component.text(WorldBridge.gateText("accept_label", "[잇는다]"),
                                NamedTextColor.GREEN, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/혼천 수락 " + req.token()))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                WorldBridge.gateText("accept_hover", "이 몸을 그 이름에 잇는다"),
                                NamedTextColor.GRAY))))
                .append(Component.text("  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(WorldBridge.gateText("reject_label", "[아니다]"),
                                NamedTextColor.RED, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/혼천 거절 " + req.token()))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                WorldBridge.gateText("reject_hover", "청을 물린다"),
                                NamedTextColor.GRAY))))
                .append(Component.newline())
                // ★ 청하지 않은 사람에게 이 물음이 떴다면 — 그것이 곧 **도용 시도의 알림**이다
                .append(Component.text(WorldBridge.gateText("ask_warn",
                                        "그대가 청한 것이 아니라면 — [아니다] 를 누르거나 그냥 두어라 "
                                                + "({seconds}초 뒤 저절로 죽는다).")
                                .replace("{seconds}", String.valueOf(left)),
                        NamedTextColor.DARK_GRAY));
    }

    /**
     * 접합 안 한 몸에게 뜨는 <b>클릭 한 번</b> — 누르면 {@code /혼천 접속} 이 대신 실행된다.
     * (마크 명령이므로 마크가 대신 쳐 줄 수 있다. 디스코드 명령은 못 친다 — 그래서 그쪽은 버튼으로 없앴다.)
     */
    public static Component callToAction() {
        return Component.text(WorldBridge.gateText("nag_label", "[강호에 이름을 올린다]"),
                        NamedTextColor.GOLD, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(
                        WorldBridge.gateText("nag_command", "/혼천 접속")))
                .hoverEvent(HoverEvent.showText(Component.text(
                        WorldBridge.gateText("nag_hover", "누르면 디스코드로 가는 길을 알려 준다"),
                        NamedTextColor.GRAY)));
    }

    /** 이미 이어진 몸에게 — 다시 이으려면 먼저 끊어야 한다 (relink: 거부) */
    public static Component already(String linkedName) {
        return Component.text(WorldBridge.gateText("already", "이미 {name} 으(로) 이어져 있다")
                .replace("{name}", linkedName), NamedTextColor.GRAY);
    }

    /** 등록부의 한 줄 — {name}·{seconds} 만 채운다 (문장은 config 의 것이다) */
    private static Component line(String key, String fallback, String name, int seconds) {
        return Component.text(WorldBridge.gateText(key, fallback)
                .replace("{name}", name)
                .replace("{seconds}", String.valueOf(seconds)), NamedTextColor.GRAY);
    }
}
