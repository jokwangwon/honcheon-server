package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 모드팩 게이트 v0 — <b>전용 설치기로 들어와야 한다</b> (2026-07-23 사용자 확정 ·
 * docs/design/modpack_transition.md D1).
 *
 * <p>서버는 클라이언트의 모드를 직접 볼 수 없다. v0 은 <b>클라 브랜드</b>로 잰다:
 * 우리 설치기(혼천 exe)가 세우는 클라는 Fabric 이라 브랜드가 "fabric" 으로 온다 —
 * 바닐라 런처("vanilla")는 문 앞에서 한국어로 안내받고 돌아간다.
 *
 * <p>★한계를 안다: 브랜드는 위조 가능하고, 아무 Fabric 클라나 통과한다. 이것은
 * 자물쇠가 아니라 <b>문패</b>다 — 진짜 자물쇠(토큰 핸드셰이크)는 자체 클라 모드
 * (Phase 2)와 함께 온다. 그때 이 클래스가 그 채널을 듣는 귀가 된다.
 *
 * <p>촬영 하네스(kigibot 류)는 바닐라 프로토콜이라 등록부 {@code exempt} 가 지킨다.
 * 문구·대기 시간 전부 등록부(config/modpack.yml → gate) — 코드가 말을 지어내지 않는다.
 */
final class ModpackGate implements Listener {

    private final HoncheonMvt plugin;

    private boolean enforce;
    private int waitSeconds;
    private List<String> exempt = List.of();
    private String message = "";

    ModpackGate(HoncheonMvt plugin) {
        this.plugin = plugin;
    }

    void load(Path configDir) {
        Map<String, Object> root = RulesConfig.load(configDir.resolve("modpack.yml"));
        Map<String, Object> g = RulesConfig.section(root, "gate");
        enforce = Boolean.TRUE.equals(g.get("enforce"));
        waitSeconds = g.get("wait_seconds") == null ? 8
                : ((Number) g.get("wait_seconds")).intValue();
        exempt = g.get("exempt") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : List.of();
        message = String.valueOf(g.getOrDefault("message",
                "혼천은 전용 설치기로 접속합니다."));
        if (enforce) {
            plugin.getLogger().info("[모드팩 게이트] v0 브랜드 검사 켜짐 — " + waitSeconds
                    + "초 안에 fabric 브랜드가 안 오면 안내 후 퇴장 (면제 " + exempt + ")");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!enforce) {
            return;
        }
        Player p = event.getPlayer();
        if (exempt.contains(p.getName())) {
            return;
        }
        // 브랜드 패킷은 접속 직후엔 아직 안 왔을 수 있다 — 잠깐 기다렸다 잰다
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) {
                return;
            }
            String brand = p.getClientBrandName();
            boolean fabric = brand != null
                    && brand.toLowerCase(Locale.ROOT).contains("fabric");
            if (!fabric) {
                plugin.getLogger().info("[모드팩 게이트] " + p.getName() + " 브랜드="
                        + brand + " — 전용 클라가 아니다. 안내 후 내보낸다");
                p.kick(net.kyori.adventure.text.Component.text(message));
            }
        }, Math.max(1L, waitSeconds * 20L));
    }
}
