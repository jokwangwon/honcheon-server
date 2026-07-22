package com.honcheon.mvt;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <b>세계는 부서지지 않는다</b> — 채굴이 없는 세상 (2026-07-23 사용자 확정 · B-161).
 *
 * <p>사용자 원문: <i>"채굴은 없는 세상 블럭 부수기 자체가 없음."</i>
 * 이 세계의 땅과 집은 조성기가 지은 저작물이다 — 플레이어의 손은 그것을 깎지 못한다.
 * 병기의 도구 성능(봉=삽·부=도끼…)을 개별로 무력화하는 대신 <b>규칙을 세계에 건다</b>:
 * 어떤 손·어떤 도구로도 블록은 깨지지 않는다. B-160(캐는 스윙의 자동 연타 합성)의
 * 뿌리도 이것으로 마른다 — 깨지지 않으면 채굴 스윙 폭풍 자체가 없다.
 *
 * <p><b>설치도 없다</b> (2026-07-23 사용자 확정 2차: <i>"블록 설치도 없습니다 RPG세상으로
 * 진행됩니다"</i>) — 이 세계에서 플레이어는 블록을 깨지도 놓지도 않는다. 몸·병기·무공·말이
 * 플레이의 전부다. <b>예외는 관리자의 손(크리에이티브)뿐이다</b> — 조성·수리는 세계의 몫이지
 * 플레이의 몫이 아니다.
 *
 * <p>약재·가죽 등 채집 경제는 사냥 부산물이라 이 규약과 부딪히지 않는다 (Goods.java —
 * "약재 — 사냥 부산물"). 코드 전수 확인: 블록 파괴에 기대는 시스템은 없었다 (2026-07-23).
 */
final class BlockCovenant implements Listener {

    /** 안내 재발화 억제(ms) — 홀드로 긁는 동안 액션바가 도배되지 않게 */
    private static final long NOTE_COOLDOWN_MS = 5000L;
    private static final Component NOTE =
            Component.text("세계는 부서지지 않는다", NamedTextColor.DARK_GRAY);

    private final Map<UUID, Long> lastNote = new HashMap<>();

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        deny(event.getPlayer(), () -> event.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        deny(event.getPlayer(), () -> event.setCancelled(true));
    }

    private void deny(Player player, Runnable cancel) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;   // 관리자의 손 — 조성·수리는 세계의 몫
        }
        cancel.run();
        long now = System.currentTimeMillis();
        Long prev = lastNote.get(player.getUniqueId());
        if (prev == null || now - prev > NOTE_COOLDOWN_MS) {
            lastNote.put(player.getUniqueId(), now);
            player.sendActionBar(NOTE);   // 조용한 취소는 침묵이다 — 왜 안 되는지 말한다
        }
    }
}
