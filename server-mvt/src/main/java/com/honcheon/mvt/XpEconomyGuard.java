package com.honcheon.mvt;

import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 경험 경제의 절연층 — <b>레벨은 성장의 것이다.</b> 바닐라가 그 원장에 손대지 못하게 막는다.
 *
 * <p>정본: {@code docs/design/cultivation_v3_levels.md} §4-b ② (★사용자 확정 2026-07-15)
 * · {@code docs/BACKLOG.md} B-136. 바닐라 XP바 = v3 경험/레벨 (표시는 서버가 원장에서 그린다).
 * 그래서 바닐라의 소비(모루·인챈트대)와 유입(몹 오브)을 여기서 끊는다.
 *
 * <ul>
 *   <li><b>소비 차단</b> — 모루 결과를 집거나 인챈트가 성사되는 순간 바닐라가 레벨을 걷는다.
 *       걷기 <b>직전의 (레벨, 게이지)를 찍어 두고 다음 틱에 되돌린다</b> (환급).
 *       공제량을 셈하지 않는 이유: 크리에이티브 면제 같은 바닐라 내부 분기를 우리가 다
 *       모르고, 스냅샷 복원은 그 전부에 대해 무해하다 (공제가 없었으면 복원은 no-op).</li>
 *   <li><b>몹 오브 흡수(0)</b> — 정본의 낱말이 「흡수」다: 오브는 <b>그대로 뜨고 그대로
 *       빨려 든다</b> (사냥의 손맛은 남는다). 다만 원장에는 0 — v3 XP 원장 하나만 자란다.
 *       (원천별 v3 XP 적립은 별도 트랙 — 정본 §5-3 미결, 여기서 지어내지 않는다)</li>
 * </ul>
 *
 * <p>정본이 말한 것만 막는다 — 몹 죽음 외의 바닐라 유입(화로·낚시·교배·경험치 병·숫돌·
 * 블록 채굴·플레이어 죽음 오브)과 수선(Mending)의 오브 소모는 정본에 조항이 없어 <b>건드리지
 * 않는다</b> (침묵하는 실패 금지 — 모르는 것은 사용자에게 묻는다).
 *
 * <p>남는 관문 (이 층의 몫이 아니다 — B-136 본편이 걷는다): 모루·인챈트대의 <b>레벨 요구
 * 자체</b>는 바닐라가 계속 검사한다 (레벨이 모자라면 결과를 못 집는다). 상점 이관이 끝나면
 * 소비처 자체가 사라진다.
 *
 * <p>배선(조율자): {@code getServer().getPluginManager().registerEvents(new XpEconomyGuard(this), this);}
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
     * 몹 오브 흡수(0) — 오브가 몸에 닿아 빨려 드는 순간, 원장으로 가는 양만 0 으로.
     * 몹 죽음 오브만이다 — 다른 출처는 정본이 침묵하므로 여기서도 침묵하지 않고 <b>통과</b>시킨다
     * (클래스 javadoc 의 미결 목록 참조).
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onExpChange(PlayerExpChangeEvent event) {
        if (event.getSource() instanceof ExperienceOrb orb
                && orb.getSpawnReason() == ExperienceOrb.SpawnReason.ENTITY_DEATH) {
            event.setAmount(0);
        }
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
