package com.honcheon.mvt;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import com.honcheon.core.rules.RulesConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 팩을 <b>들고 있는 손</b> — 플레이어가 <b>실제로 접속한 주소</b>로 팩을 준다.
 *
 * <p><b>왜 이것이 필요한가.</b> {@code server.properties} 의 {@code resource-pack} 은 URL 을 <b>하나</b>만
 * 적을 수 있다. 그런데 이 기계는 주소가 여럿이다 — LAN({@code 192.168.x}) · Tailscale({@code 100.x}) · 공인 IP.
 * URL 에 LAN 주소를 박아 두면, LAN 밖에서 들어온 사람에게는 게임(25565)은 붙지만 팩(8123)은
 * <b>자기 컴퓨터에서 닿지 않는 주소</b>가 된다. 증상은 정확히 "다운로드만 실패"다.
 *
 * <p>그래서 주소를 박지 않는다. Paper 의 {@link Player#getVirtualHost()} 는 <b>그 사람이 무엇을 쳐서 들어왔는지</b>
 * 를 안다 — 그 주소로 팩을 준다. LAN 으로 들어왔으면 LAN 으로, Tailscale 로 들어왔으면 Tailscale 로.
 * <b>배급자(8123)를 더 넓은 곳에 열지 않고도</b> 각자에게 닿는 주소가 나간다.
 *
 * <p><b>sha1 은 매번 다시 잰다.</b> 팩을 새로 굽고 {@code server.properties} 의 sha1 을 안 고치면
 * 클라이언트가 조용히 거절한다 — 배급자는 200 을 주고, 파일도 멀쩡하고, 아무 로그도 안 남는다.
 * 이 실패는 눈에 안 보이므로, <b>사람이 옮겨 적는 일을 없앤다.</b>
 *
 * <p><b>팩 게이트 불가침</b> — {@code required: false}. 팩이 없어도, 팩을 거절해도 플레이는 된다.
 * 팩은 위에 얹는 것이지 들어오는 조건이 아니다.
 */
final class PackPusher implements Listener {

    private final HoncheonMvt plugin;

    private boolean enabled;
    private int port;
    private String file;
    private Path localPath;
    private String prompt;
    private boolean required;
    private String forcedHost;    // 비워 두면 접속한 주소를 따라간다 (권장)
    private String fallbackHost;  // 공인 주소로 들어온 사람에게 줄 사설 주소 (8123 은 밖으로 안 연다)
    private String absoluteUrl;   // 외부 호스팅 — 이게 있으면 주소를 추측할 일이 없다

    private String hash;          // 실물에서 잰 sha1 (사람이 옮겨 적지 않는다)
    private byte[] hashBytes;     // API 가 원하는 형태 — 같은 값이다
    private UUID packId;

    PackPusher(HoncheonMvt plugin) {
        this.plugin = plugin;
    }

    void load(Path configDir) {
        Map<String, Object> root = RulesConfig.load(configDir.resolve("resource_pack.yml"));
        Map<String, Object> s = RulesConfig.section(root, "resource_pack");
        enabled = !Boolean.FALSE.equals(s.get("enabled"));
        port = s.get("port") == null ? 8123 : RulesConfig.intValue(s.get("port"));
        file = String.valueOf(s.getOrDefault("file", "honcheon_pack.zip"));
        prompt = String.valueOf(s.getOrDefault("prompt", "혼천 리소스팩"));
        required = Boolean.TRUE.equals(s.get("required"));   // ★ 기본 false — 팩 게이트 불가침
        Object host = s.get("host");
        forcedHost = host == null || String.valueOf(host).isBlank() ? null : String.valueOf(host);
        Object direct = s.get("url");
        absoluteUrl = direct == null || String.valueOf(direct).isBlank() ? null : String.valueOf(direct);
        Object fb = s.get("fallback_host");
        fallbackHost = fb == null || String.valueOf(fb).isBlank() ? null : String.valueOf(fb);
        Object path = s.get("local_path");
        localPath = Path.of(path == null ? "../pack-http/" + file : String.valueOf(path));

        if (required) {
            // 이 계약은 config 가 뒤집을 수 없다. 뒤집으려 하면 소리내어 거절한다.
            plugin.getLogger().warning("[팩] required: true 는 팩 게이트 불가침을 어긴다 — false 로 되돌린다");
            required = false;
        }
        hash = sha1(localPath);
        hashBytes = hash == null ? null : hexBytes(hash);
        if (hash == null) {
            plugin.getLogger().warning("[팩] 실물을 못 찾았다 (" + localPath.toAbsolutePath()
                    + ") — 팩을 배급하지 않는다");
            enabled = false;
            return;
        }
        // 팩 id 는 sha1 에서 뽑는다 — 팩이 바뀌면 id 도 바뀌어 클라이언트가 캐시를 버린다
        packId = UUID.nameUUIDFromBytes(hash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (enabled) {
            plugin.getLogger().info("[팩] 배급 준비 — " + file + " · sha1 " + hash.substring(0, 12)
                    + "… · " + (absoluteUrl != null ? "외부 호스팅 " + absoluteUrl
                    : "포트 " + port + " · 주소는 " + (forcedHost == null
                    ? "접속한 주소를 따라간다" : "고정 " + forcedHost)));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled) {
            return;
        }
        Player player = event.getPlayer();
        String url;
        if (absoluteUrl != null) {
            // 외부 호스팅 — 어디서 들어왔든 같은 주소로 받는다. 추측할 것이 없다.
            url = absoluteUrl;
        } else {
            String host = resolveHost(player);
            if (host == null) {
                plugin.getLogger().warning("[팩] " + player.getName()
                        + " 의 접속 주소를 못 읽었다 — 팩을 못 보낸다");
                return;
            }
            url = "http://" + host + ":" + port + "/" + file;
        }
        try {
            player.setResourcePack(packId, url, hashBytes,
                    net.kyori.adventure.text.Component.text(prompt), required);
            plugin.getLogger().info("[팩] " + player.getName() + " 에게 보냈다 — " + url);
        } catch (RuntimeException refused) {
            plugin.getLogger().warning("[팩] " + player.getName() + " 에게 못 보냈다 — " + refused.getMessage());
        }
    }

    /**
     * <b>손잡이를 보는 눈.</b> 클라이언트가 뭐라고 답했는지 적는다.
     *
     * <p>이것이 없어서 오래 헤맸다 — 팩이 안 떠도 <b>서버 로그엔 아무것도 안 남았다.</b>
     * 배급자는 200 을 주고, 파일도 멀쩡하고, 우리는 "다운로드 실패"라는 말만 들었다.
     * 실패의 이유는 넷이고 처방이 전부 다르다:
     * <ul>
     *   <li>{@code DECLINED} — 클라이언트가 <b>받으러 오지도 않았다.</b> 대개 그 사람 설정이
     *       "서버 리소스팩: 사용 안 함" 이다. 서버가 고칠 수 있는 것이 아니다</li>
     *   <li>{@code FAILED_DOWNLOAD} — 주소에 못 닿았거나 sha1 이 안 맞다</li>
     *   <li>{@code ACCEPTED} → {@code SUCCESSFULLY_LOADED} — 정상</li>
     *   <li>{@code INVALID_URL}·{@code FAILED_RELOAD}·{@code DISCARDED} — 그대로 적는다</li>
     * </ul>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(PlayerResourcePackStatusEvent event) {
        PlayerResourcePackStatusEvent.Status status = event.getStatus();
        String who = event.getPlayer().getName();
        switch (status) {
            case SUCCESSFULLY_LOADED -> plugin.getLogger().info("[팩] " + who + " — 켜졌다");
            case ACCEPTED -> plugin.getLogger().info("[팩] " + who + " — 받는 중");
            case DECLINED -> plugin.getLogger().warning("[팩] " + who
                    + " — 거절했다 (받으러 오지도 않았다). 클라이언트 설정 '서버 리소스팩'이"
                    + " 꺼져 있을 수 있다: 멀티플레이 → 서버 편집 → 서버 리소스팩: 사용");
            case FAILED_DOWNLOAD -> plugin.getLogger().warning("[팩] " + who
                    + " — 다운로드 실패 (주소에 못 닿았거나 sha1 불일치)");
            default -> plugin.getLogger().warning("[팩] " + who + " — " + status);
        }
    }

    /**
     * <b>그 사람이 무엇을 쳐서 들어왔는가.</b>
     *
     * <p>{@code getVirtualHost()} 는 클라이언트가 서버 목록에 적은 바로 그 주소다 — LAN IP 든 Tailscale IP 든
     * 도메인이든. <b>그 사람의 컴퓨터에서 닿는 것이 증명된 유일한 주소</b>다 (그걸로 방금 들어왔으니까).
     * 그래서 우리가 짐작하지 않는다.
     *
     * <p>다만 {@code localhost} 로 들어온 사람에게 {@code localhost} 를 주는 건 옳다(같은 기계다).
     */
    private String resolveHost(Player player) {
        if (forcedHost != null) {
            return forcedHost;
        }
        InetSocketAddress virtual = player.getVirtualHost();
        if (virtual == null) {
            return fallbackHost;
        }
        String host = virtual.getHostString();
        if (host == null || host.isBlank()) {
            return fallbackHost;
        }
        host = host.toLowerCase(Locale.ROOT);
        // ★ 공인 주소로 들어왔으면 팩은 그 주소로 못 준다 — **8123 은 밖으로 열지 않기 때문이다**
        //   (공개는 25565 하나뿐이라는 규약. 배급자는 인증도 TLS 도 없는 평문 파일 서버다.)
        //   집 안에서 공유기를 한 바퀴 돌아 들어온 사람(헤어핀 NAT)이 바로 이 경우다 —
        //   게임은 공인 주소로 붙는데 팩만 죽는다. 그에게는 **사설 주소**로 준다.
        //   진짜 바깥에서 온 사람이라면 이것도 안 닿는다. 그때는 로그가 그렇게 말할 것이다 (조용히 실패하지 않는다).
        if (!isPrivate(host) && fallbackHost != null) {
            return fallbackHost;
        }
        // IPv6 리터럴은 URL 에서 대괄호를 쓴다
        return host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    /** 사설망·루프백·Tailscale(CGNAT 100.64/10)이면 배급자에 닿는다 — 8123 이 이미 듣고 있는 곳이다 */
    private static boolean isPrivate(String host) {
        if (host.equals("localhost") || host.startsWith("127.") || host.startsWith("192.168.")
                || host.startsWith("10.") || host.startsWith("[") || host.contains(":")) {
            return true;
        }
        if (host.startsWith("172.")) {
            int second = secondOctet(host);
            return second >= 16 && second <= 31;
        }
        if (host.startsWith("100.")) {   // CGNAT — Tailscale 이 여기 산다
            int second = secondOctet(host);
            return second >= 64 && second <= 127;
        }
        return !host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");   // 도메인이면 그 도메인을 믿는다
    }

    private static int secondOctet(String host) {
        String[] parts = host.split("\\.");
        try {
            return parts.length > 1 ? Integer.parseInt(parts[1]) : -1;
        } catch (NumberFormatException notNumber) {
            return -1;
        }
    }

    /** 16진 문자열을 바이트로 — API 는 바이트를 원하고 사람은 문자열을 읽는다 (같은 값이다) */
    private static byte[] hexBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** 실물의 sha1 — 사람이 옮겨 적지 않으므로 낡을 수 없다 */
    private String sha1(Path path) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(path));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (IOException | java.security.NoSuchAlgorithmException missing) {
            return null;
        }
    }
}
