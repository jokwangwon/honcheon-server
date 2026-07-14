package com.honcheon.mvt;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * <b>말하는 창구 — 조성은 반드시 소리를 낸다.</b>
 *
 * <h2>겪은 일 (2026-07-14 09:46)</h2>
 * 관리자가 <b>RCON 콘솔</b>에서 {@code /혼천 지형조성 hwasan} 을 쳤다. 화면에는 <i>"부지를 찾는다"</i>
 * 한 줄이 뜨고 <b>그 뒤로 12분간 아무 말도 없었다.</b> 완료도, 실패도, 진행도 없었다 —
 * 사람은 <b>조성이 죽은 줄 알았다.</b>
 *
 * <p><b>죽지 않았다. 먹통이었다.</b> 조성은 729초 뒤 멀쩡히 끝났다(로그가 증명한다).
 * 진짜 병은 <b>말이 갈 곳이 없었다</b>는 것이다:
 * <pre>
 *   TickBudget.build(..., log = sender::sendMessage)   ← 20k 연산마다 진행을 보고한다
 *   TickBudget.build(..., err = sender.sendMessage(...))  ← 실패도 여기로 간다
 * </pre>
 * ★★ <b>RCON 의 {@code sender} 는 명령이 반환되는 순간 죽는다</b> (소켓이 닫힌다 —
 * 로그의 {@code RCON Client shutting down} 이 매 명령 직후에 찍힌다). 그러니 그 뒤로 보낸 말은
 * <b>전부 허공으로 갔다.</b> 진행도, <b>실패도</b>. 조성이 정말 터졌어도 <b>아무도 몰랐을 것이다.</b>
 *
 * <h2>규칙 — 하나뿐이다</h2>
 * <blockquote><b>조성의 말은 언제나 로그에 남는다.</b> 사람에게 보내는 것은 <b>덤</b>이다.</blockquote>
 *
 * 로그는 아무도 안 죽는다 — 콘솔이든 RCON 이든 플레이어든, 서버가 사는 한 남는다.
 * 그래서 이 창구는 <b>먼저 로그에 적고</b>, 그 다음에 (살아 있으면) 사람에게 보낸다.
 *
 * <p><b>눈</b>: {@code tools/silence_audit.py} — 조성 경로에서 이 창구를 <b>거치지 않는 말</b>을 잡는다.
 * ★ <b>침묵은 그 자체로 결함이다.</b>
 */
final class Announce {

    private Announce() {
    }

    /** 진행 보고를 로그에 적는 최소 간격 (ms) — 27.6M 연산이면 보고가 1380줄이다. 콘솔을 익사시키지 않는다 */
    private static final long LOG_EVERY_MS = 15_000L;

    private static long lastProgressLog;

    /** 한 마디 — <b>로그에 적고</b>, 사람이 살아 있으면 사람에게도 */
    static void say(Plugin plugin, CommandSender sender, String message) {
        plugin.getLogger().info(plain(message));
        tell(sender, message);
    }

    /** 경고 — 조용히 넘어가지 않는다 */
    static void warn(Plugin plugin, CommandSender sender, String message) {
        plugin.getLogger().warning(plain(message));
        tell(sender, ChatColor.RED + message);
    }

    /** ★ 실패 — <b>이것이 허공으로 가면 사람은 조성이 도는 줄 안다</b> */
    static void fail(Plugin plugin, CommandSender sender, String message) {
        plugin.getLogger().severe(plain(message));
        tell(sender, ChatColor.RED + message);
    }

    /**
     * 진행 — 사람에게는 매번, 로그에는 {@value #LOG_EVERY_MS}ms 마다.
     *
     * <p>★ <b>긴 조성은 살아 있다고 말해야 한다.</b> 화산파는 729초를 말없이 돌았고, 그 침묵이
     * 곧 "죽었다"는 오해였다.
     */
    static void progress(Plugin plugin, CommandSender sender, String message) {
        tell(sender, message);
        long now = System.currentTimeMillis();
        if (now - lastProgressLog >= LOG_EVERY_MS) {
            lastProgressLog = now;
            plugin.getLogger().info("[조성·진행] " + plain(message));
        }
    }

    /**
     * 사람에게 — <b>죽은 sender 에게 보내도 예외는 안 난다</b>(그냥 사라진다). 그래서 로그가 정본이다.
     * 플레이어가 로그아웃했으면 보내지 않는다.
     */
    private static void tell(CommandSender sender, String message) {
        if (sender == null) {
            return;
        }
        if (sender instanceof Player p && !p.isOnline()) {
            return;
        }
        try {
            sender.sendMessage(message);
        } catch (RuntimeException dead) {
            // RCON 이 이미 끊겼다 — 로그에는 이미 적었다. 그것으로 충분하다
        }
    }

    private static String plain(String message) {
        String s = ChatColor.stripColor(message);
        return s == null ? message : s;
    }
}
