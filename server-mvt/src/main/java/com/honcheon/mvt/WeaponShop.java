package com.honcheon.mvt;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 장쇠의 병기 좌판 — 범철 무기를 파는 유일한 상시 경로 (장터).
 *
 * <p>등록제: {@code config/economy.yml price_table.장비} 에 <b>가격이 등록된 품목만</b> 진열한다.
 * 가격을 발명하지 않는다 — 미등록 계열(도·권갑·부·겸·월아산·구)은 진열되지 않으며,
 * economy.yml 에 {@code 도_범철} 등이 추가되는 순간 코드 변경 없이 자동으로 좌판에 오른다.
 *
 * <p>결제는 전낭(PlayerLedger.money) — 아이템 화폐는 없다 (economy.yml mc.ledger_based:
 * 복제·인플레 원천 차단). 매입(가죽)은 TradeListener 의 몫, 매도(병기)는 이쪽이다.
 *
 * <p>GUI 식별은 커스텀 InventoryHolder (LedgerGui 와 같은 문법 — 제목 문자열 비교 금지).
 * 이벤트 등록은 HoncheonMvt 의 몫: {@code registerEvents(new WeaponShop(plugin), this)}.
 */
final class WeaponShop implements Listener {

    private static final String TITLE = ChatColor.DARK_GRAY + "장쇠의 병기 좌판";
    private static final int SIZE = 27;

    /** 지역 경제 지표 — MVT 는 청하현 기본값 고정 (물가 배율 1.0 대역) */
    private static final int ECONOMY_INDEX = 50;

    private final HoncheonMvt plugin;

    WeaponShop(HoncheonMvt plugin) {
        this.plugin = plugin;
    }

    private static final class ShopHolder implements InventoryHolder {
        private Inventory inventory;
        /** 슬롯 → 판매 품목 (계열·가격) */
        private final Map<Integer, Offer> offers = new LinkedHashMap<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record Offer(Weapons.Series series, Weapons.Grade grade, int price) { }

    /** 좌판을 연다 — 장쇠 우클릭(TradeListener) 또는 /혼천 병기상 */
    static void open(HoncheonMvt plugin, Player player) {
        ShopHolder holder = new ShopHolder();
        Inventory inv = Bukkit.createInventory(holder, SIZE, TITLE);
        holder.inventory = inv;

        int slot = 10;
        for (Weapons.Series series : Weapons.Series.values()) {
            int price = Weapons.price(plugin.economy(), series, Weapons.Grade.범철, ECONOMY_INDEX);
            if (price < 0) {
                continue;   // economy.yml 미등록 — 장쇠는 값을 모르는 물건을 팔지 않는다
            }
            ItemStack display = Weapons.make(series, Weapons.Grade.범철);
            ItemMeta meta = display.getItemMeta();
            List<String> lore = new ArrayList<>(meta.getLore());
            lore.add(ChatColor.DARK_GRAY + "─────────────");
            lore.add(ChatColor.YELLOW + "값 " + price + "문" + ChatColor.GRAY + " — 클릭해 산다");
            meta.setLore(lore);
            display.setItemMeta(meta);

            holder.offers.put(slot, new Offer(series, Weapons.Grade.범철, price));
            inv.setItem(slot, display);
            slot++;
        }

        ItemStack purse = new ItemStack(Material.PAPER);
        ItemMeta purseMeta = purse.getItemMeta();
        purseMeta.setDisplayName(ChatColor.YELLOW + "전낭 — "
                + plugin.ledger(player.getUniqueId()).money() + "문");
        purseMeta.setLore(List.of(
                ChatColor.GRAY + "장쇠는 범철만 취급한다.",
                ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + "정련 이상은 명공·표국·문파의 몫 — 돈으로 사는 물건이 아니다."));
        purse.setItemMeta(purseMeta);
        inv.setItem(22, purse);

        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_BARREL_OPEN, 0.8f, 1.0f);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShopHolder holder)) {
            return;
        }
        event.setCancelled(true);   // 진열품은 집어갈 수 없다 — 사는 것만 된다
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Offer offer = holder.offers.get(event.getRawSlot());
        if (offer == null) {
            return;
        }
        PlayerLedger ledger = plugin.ledger(player.getUniqueId());
        if (ledger.money() < offer.price()) {
            player.sendMessage(ChatColor.GOLD + "[장쇠] " + ChatColor.GRAY
                    + "돈이 모자라네 — " + offer.price() + "문일세. (지금 " + ledger.money() + "문)");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
            return;
        }
        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage(ChatColor.GOLD + "[장쇠] " + ChatColor.GRAY + "손이 가득 찼네. 짐부터 덜게.");
            return;
        }
        ledger.earn(-offer.price());   // PlayerLedger 에 spend() 가 없다 — 음수 적립이 곧 지출 (MVT)
        player.getInventory().addItem(Weapons.make(offer.series(), offer.grade()));
        player.sendMessage(ChatColor.GOLD + "[장쇠] " + ChatColor.WHITE + offer.grade().color
                + offer.grade().name() + " " + offer.series().name() + ChatColor.WHITE
                + " — " + ChatColor.YELLOW + offer.price() + "문" + ChatColor.WHITE + "일세. 잘 쓰게.");
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 1.0f, 1.0f);
        plugin.updateSidebar(player);
        open(plugin, player);   // 전낭 표시 갱신
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ShopHolder) {
            event.setCancelled(true);
        }
    }
}
