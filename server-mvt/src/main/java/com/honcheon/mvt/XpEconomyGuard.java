package com.honcheon.mvt;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 경험 경제의 절연층 — <b>레벨은 성장의 것이다.</b> 바닐라가 그 원장에 손대지 못하게 막는다.
 *
 * <p>정본: {@code docs/design/cultivation_v3_levels.md} §4-b ② (★사용자 확정 2026-07-15)
 * · {@code docs/BACKLOG.md} B-136. 바닐라 XP바 = v3 경험/레벨 (표시는 서버가 원장에서 그린다).
 * 그래서 바닐라의 소비(모루·인챈트대)와 유입(<b>전 원천</b>)을 여기서 끊는다.
 *
 * <ul>
 *   <li><b>소비 차단</b> — 모루 결과를 집거나 인챈트가 성사되는 순간 바닐라가 레벨을 걷는다.
 *       걷기 <b>직전의 (레벨, 게이지)를 찍어 두고 다음 틱에 되돌린다</b> (환급).
 *       공제량을 셈하지 않는 이유: 크리에이티브 면제 같은 바닐라 내부 분기를 우리가 다
 *       모르고, 스냅샷 복원은 그 전부에 대해 무해하다 (공제가 없었으면 복원은 no-op).</li>
 *   <li><b>유입 전면 흡수(0)</b> — ★사용자 확정 2026-07-15: "경험은 v3 XP 하나만."
 *       몹 오브만이 아니라 화로 제련·낚시·경험치 병·교배·숫돌·채굴·플레이어 죽음 오브까지
 *       <b>원천 무관</b>이다. 오브는 <b>그대로 뜨고 그대로 빨려 든다</b> (사냥의 손맛은 남는다) —
 *       다만 원장에는 0. (원천별 v3 XP 적립은 별도 트랙 — 정본 §5-3 미결, 여기서 지어내지 않는다)</li>
 *   <li><b>수선(Mending) 취소</b> — 오브가 장비를 공짜로 수리하는 길도 XP 경제다.
 *       수리는 상점에서 돈으로 (B-136). 취소된 오브의 XP는 그대로 흡수 규칙(0)으로 떨어진다.</li>
 * </ul>
 *
 * <p>남는 관문 (이 층의 몫이 아니다 — B-136 본편이 걷는다): 모루·인챈트대의 <b>레벨 요구
 * 자체</b>는 바닐라가 계속 검사한다 (레벨이 모자라면 결과를 못 집는다). 상점 이관이 끝나면
 * 소비처 자체가 사라진다.
 *
 * <p>배선(조율자): {@code getServer().getPluginManager().registerEvents(new XpEconomyGuard(this), this);}
 * — 그리고 <b>기동 로그 한 줄</b>로 절연이 도는 것을 말한다 (침묵 금지).
 */
final class XpEconomyGuard implements Listener {

    private final HoncheonMvt plugin;

    XpEconomyGuard(HoncheonMvt plugin) {
        this.plugin = plugin;
    }

    /** 인챈트대 — 성사(MONITOR·비취소)만 본다. 바닐라의 공제는 이 이벤트 <b>뒤</b>에 온다 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        restoreNextTick(event.getEnchanter());
    }

    /** 모루 — 결과 슬롯을 집는 순간 바닐라가 repairCost 레벨을 걷는다 (Shift·숫자키 집기 포함) */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnvilTake(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getType() != InventoryType.ANVIL) {
            return;
        }
        if (event.getSlotType() != InventoryType.SlotType.RESULT) {
            return;
        }
        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType().isAir()) {
            return;   // 빈 결과 — 바닐라도 걷지 않는다. 헛 스케줄을 만들지 않는다
        }
        if (event.getWhoClicked() instanceof Player player) {
            restoreNextTick(player);
        }
    }

    /**
     * 유입 전면 흡수(0) — 바닐라 XP가 몸에 닿는 순간, 원장으로 가는 양을 <b>원천 무관</b> 0 으로.
     * 옛날엔 몹 죽음 오브만 걸렀다 — ★사용자 확정(2026-07-15)으로 전부다: "경험은 v3 XP 하나만"
     * (cultivation_v3_levels.md §4-b).
     *
     * <p><b>전제</b>: 앞으로 생길 v3 XP 지급 경로는 이 이벤트를 <b>타지 않는다</b> —
     * v3 경험은 원장(PlayerLedger)에 직접 적히고, 바닐라 XP 파이프({@code giveExp}·오브)를
     * 쓰지 않는다. 그 전제가 깨지면 이 한 줄이 v3 지급을 삼킨다 — 그때는 여기가 아니라
     * 지급 경로를 고쳐라.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onExpChange(PlayerExpChangeEvent event) {
        event.setAmount(0);
    }

    /**
     * 수선(Mending) 취소 — 오브로 하는 공짜 수리 차단. <b>수리는 상점에서 돈으로</b> (B-136).
     * 취소하면 그 오브의 XP는 수리에 안 쓰이고 통째로 {@link #onExpChange} 로 떨어진다 —
     * 거기서 0 이 되므로 어느 길로도 새지 않는다.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMend(PlayerItemMendEvent event) {
        event.setCancelled(true);
    }

    /**
     * 공제 직전의 바를 다음 틱에 되돌린다.
     *
     * <p>알려진 틈 둘 — 창이 1틱이라 실측상 무시한다:
     * 같은 틱의 다른 XP 변동이 복원에 덮일 수 있다 (몹 오브는 이미 0 이라 겹칠 것이 드물다).
     * 그 틱에 접속이 끊기면 복원을 건너뛴다 (죽은 몸에 쓰면 저장에 안 남는다 — 거짓 복원 금지).
     */
    private void restoreNextTick(Player player) {
        int level = player.getLevel();
        float exp = player.getExp();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            player.setLevel(level);
            player.setExp(exp);
        });
    }
}
