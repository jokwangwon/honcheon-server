package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 배고픔 — <b>무협은 허기의 이야기가 아니다</b>.
 *
 * <p><b>사용자 보고 (2026-07-13): "배고픔이 너무 빨리 닳는다."</b> 맞다. 그리고 <b>우리가 그렇게 만들었다</b>:
 * 바닐라의 허기는 <b>달리기와 점프</b>로 닳는데, 이 세계는 <b>경공이 달리기·점프를 쓰고</b> 전투가 몸을
 * 굴린다. 무협을 할수록 배가 고파진다. 바닐라 규칙과 우리 문법이 정면으로 부딪친 자리다.
 *
 * <p>그래서 <b>닳는 속도를 등록부가 정한다</b> ({@code world_purity.yml → hunger}). 이 파일은
 * "무엇을 끄고 무엇을 남기는가"의 등록부다 — 자연 스폰을 끄고 동굴을 껐듯이, 허기도 여기서 다스린다.
 *
 * <p><b>없애지는 않는다</b> (등록부가 그렇게 적으면 없앨 수 있다). 굶주림은 강호의 일부다 —
 * 다만 <b>수련하다 굶어 죽는 것</b>은 이 세계의 이야기가 아니다. 그래서 {@code floor} 아래로는 안 내려간다:
 * 배는 고파지되 <b>허기가 사람을 죽이지는 않는다</b>.
 *
 * <p><b>깎는 방식</b>: 바닐라가 1을 깎으려 할 때 {@code rate} 만큼만 빚으로 적고, 빚이 1을 넘을 때
 * <b>그때 한 칸</b> 깎는다. 확률로 굴리지 않는다 — 같은 움직임이면 같은 속도로 닳는다 (결정론).
 */
final class Hunger implements Listener {

    private final HoncheonMvt plugin;

    private boolean enabled;
    private double rate;    // 바닐라의 몇 배로 닳는가 (0 = 안 닳는다)
    private int floor;      // 이 아래로는 안 내려간다 (굶어 죽지 않는다)

    /** 아직 한 칸이 안 된 허기 — 결정론을 위해 사람마다 쌓아 둔다 (난수를 굴리지 않는다) */
    private final Map<UUID, Double> debt = new HashMap<>();

    Hunger(HoncheonMvt plugin) {
        this.plugin = plugin;
    }

    void load(Path configDir) {
        Map<String, Object> root = RulesConfig.load(configDir.resolve("world_purity.yml"));
        Map<String, Object> h = RulesConfig.section(root, "hunger");
        if (h.isEmpty()) {
            enabled = false;
            plugin.getLogger().info("[허기] 등록부에 hunger 절이 없다 — 바닐라 그대로 둔다");
            return;
        }
        enabled = !Boolean.FALSE.equals(h.get("enabled"));
        rate = h.get("rate") == null ? 1.0 : ((Number) h.get("rate")).doubleValue();
        floor = h.get("floor") == null ? 0 : ((Number) h.get("floor")).intValue();
        plugin.getLogger().info("[허기] " + (enabled
                ? "바닐라의 " + rate + "배로 닳는다 · " + floor + " 아래로는 안 내려간다"
                : "★ 안 닳는다 (등록부가 껐다)"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (!enabled || !(event.getEntity() instanceof Player player)) {
            return;
        }
        int now = player.getFoodLevel();
        int want = event.getFoodLevel();
        if (want >= now) {
            return;   // 먹는 중이다 — 먹는 것은 우리가 안 건드린다
        }

        int drop = now - want;
        double owed = debt.merge(player.getUniqueId(), drop * rate, Double::sum);
        int take = (int) Math.floor(owed);
        debt.put(player.getUniqueId(), owed - take);

        int next = Math.max(floor, now - take);
        if (next == now) {
            event.setCancelled(true);   // 아직 빚이 한 칸이 안 됐다 — 이번엔 안 깎는다
            return;
        }
        event.setFoodLevel(next);
    }

    /** 나간 사람의 빚은 지운다 — 다시 들어오면 새 배다 */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        debt.remove(event.getPlayer().getUniqueId());
    }
}
