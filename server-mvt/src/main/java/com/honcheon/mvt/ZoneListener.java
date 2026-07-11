package com.honcheon.mvt;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 구역 입장 알림 — 채팅이 아니라 화면 중앙 타이틀 (관리자 피드백: "다른 게임의 입장 문구 방식").
 * 블록 경계를 넘을 때만 판정, 같은 구역 재알림은 쿨다운으로 차단.
 */
final class ZoneListener implements Listener {

    private static final long REENTER_COOLDOWN_MS = 30_000;   // 같은 구역 재입장 알림 쿨다운

    private final HoncheonMvt plugin;
    private final Map<UUID, String> lastZone = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> lastShown = new ConcurrentHashMap<>();

    ZoneListener(HoncheonMvt plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null
                || (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ())) {
            return;   // 블록 경계를 넘을 때만
        }
        Player player = event.getPlayer();
        Zone zone = plugin.zoneAt(event.getTo());
        String name = zone == null ? "" : zone.name();
        String before = lastZone.put(player.getUniqueId(), name);
        if (name.equals(before)) {
            return;
        }
        plugin.updateSidebar(player);   // 구역 변경 즉시 갱신 — 5초 폴링은 소지금·수련용 보조
        if (zone == null) {
            return;
        }
        Map<String, Long> shown = lastShown.computeIfAbsent(player.getUniqueId(),
                id -> new ConcurrentHashMap<>());
        long now = System.currentTimeMillis();
        Long last = shown.get(name);
        if (last != null && now - last < REENTER_COOLDOWN_MS) {
            return;
        }
        shown.put(name, now);
        // 화면 중앙 타이틀 — fadeIn 5틱 · 유지 40틱 · fadeOut 10틱
        player.sendTitle("§f" + zone.name(), "§7" + zone.subtitle(), 5, 40, 10);
    }
}
