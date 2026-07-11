package com.honcheon.mvt;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
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
import java.util.List;

/**
 * 경락도 GUI — /혼천 정보(채팅 출력)의 화면판. 관리자 피드백: "채팅이 아닌 화면 UI로
 * 눈으로 확인하고 조작 가능하게" (cultivation.yml mc_perception 원장_조회 설계).
 * 27칸 상자 인벤토리에 수련 화후·소지금·마크·경지 문장을 아이템 이름·lore 로 표시하고,
 * 가죽 매각(BARREL)·닫기(ARROW) 버튼을 단다 — 정보는 GUI 안에서, 채팅 출력 없음.
 *
 * 배치 (27칸 = 3×9):
 *   줄1:  0~8  전부 유리판 테두리
 *   줄2:  9 │ 10 수련 │ 11 │ 12 소지금 │ 13 │ 14 마크 │ 15 │ 16 경지 문장 │ 17
 *   줄3: 18~21 │ 22 가죽 팔기 │ 23~25 │ 26 닫기
 *
 * GUI 식별은 {@link LedgerHolder}(커스텀 InventoryHolder) — 제목 문자열 비교는 리소스팩
 * 글리프·색 코드 변경에 취약하므로 금지. 클릭은 전부 취소(아이템 반출 금지) 후 버튼만 동작.
 *
 * 이벤트 등록·명령 배선은 HoncheonMvt/MvtCommand 의 몫 — 이 클래스는 배선 대상일 뿐이다.
 * (배선: registerEvents(new LedgerGui(plugin), this) + 명령에서 LedgerGui.open(plugin, player))
 */
final class LedgerGui implements Listener {

    /**
     * 제목: U+E080 경락도 배경 패널 글리프 + 라벨.
     * 주의 — 배경 글리프가 GUI 텍스처를 덮으려면 음수 공백 폰트로 커서를 되돌리는
     * 오프셋 튜닝이 필요하다 (build_resourcepack.py 쪽 작업) — 오프셋 값은 인게임에서
     * 실측으로 맞춘다. 그 전까지는 제목 앞 장식 글리프로만 보인다.
     */
    private static final String TITLE = Glyphs.GUI_LEDGER + ChatColor.DARK_GRAY + " 경락도";

    private static final int SIZE = 27;

    // ─── 슬롯 배치 ───
    private static final int SLOT_TRAINING = 10;   // 수련 현황
    private static final int SLOT_MONEY = 12;      // 소지금
    private static final int SLOT_MARKS = 14;      // 실전·사선 마크
    private static final int SLOT_CRESTS = 16;     // 경지 문장 견본 (리소스팩 확인용)
    private static final int SLOT_SELL = 22;       // 가죽 팔기 버튼
    private static final int SLOT_CLOSE = 26;      // 닫기 버튼

    /** 장터 매각 유효 반경 (블록) — MvtCommand.sell 의 15블록 규칙과 동일해야 한다 */
    private static final int MARKET_RADIUS = 15;

    private final HoncheonMvt plugin;

    LedgerGui(HoncheonMvt plugin) {
        this.plugin = plugin;
    }

    /** 자기 식별용 홀더 — 이 홀더를 가진 인벤토리만 경락도 GUI 로 취급한다 */
    private static final class LedgerHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** 경락도 GUI 를 생성해 플레이어에게 연다 — 값은 전부 원장·엔진에서 읽는다 */
    @SuppressWarnings("deprecation")   // legacy String 제목 — 기존 코드와 일관 (Adventure 미사용)
    static void open(HoncheonMvt plugin, Player player) {
        LedgerHolder holder = new LedgerHolder();
        Inventory inv = Bukkit.createInventory(holder, SIZE, TITLE);
        holder.inventory = inv;

        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int slot = 0; slot < SIZE; slot++) {
            inv.setItem(slot, pane);
        }

        PlayerLedger ledger = plugin.ledger(player.getUniqueId());
        inv.setItem(SLOT_TRAINING, trainingItem(plugin, ledger));
        inv.setItem(SLOT_MONEY, moneyItem(ledger));
        inv.setItem(SLOT_MARKS, marksItem(ledger));
        inv.setItem(SLOT_CRESTS, crestsItem());
        inv.setItem(SLOT_SELL, item(Material.BARREL, ChatColor.GOLD + "가죽 팔기", List.of(
                ChatColor.GRAY + "늑대·여우 가죽 전량 매각 (매입가 = 시세 50%)",
                ChatColor.DARK_GRAY + "장터 " + MARKET_RADIUS + "블록 내에서만 — 흥정은 상술 판정의 몫")));
        inv.setItem(SLOT_CLOSE, item(Material.ARROW, ChatColor.WHITE + "닫기", null));

        player.openInventory(inv);
    }

    // ─── 표시 아이템 — 이름·lore 가 정보 채널, 채팅 금지 ───

    /** 수련 현황 — 기술별 「이름 레벨 게이지(U+E010~18) N/M일」 */
    private static ItemStack trainingItem(HoncheonMvt plugin, PlayerLedger ledger) {
        List<String> lore = new ArrayList<>();
        ledger.allSkills().forEach((skill, days) -> {
            int level = ledger.levelOf(skill, plugin.progression());
            double progress = ledger.progressToNext(skill, plugin.progression());
            int cost = plugin.progression().skillLevelUpDays(level);
            // N/M일 = 현재 숙련 구간 내 진행 일수 / 구간 소요 일수 (환산표 상한이면 극성)
            String tail = cost <= 0 ? "극성"
                    : String.format("%.1f/%d일", progress * cost, cost);
            lore.add(ChatColor.AQUA + skill + ChatColor.WHITE + " 숙련 " + level
                    + " " + Glyphs.gauge(progress) + " " + tail);
        });
        if (lore.isEmpty()) {
            lore.add(ChatColor.GRAY + "아직 수련 기록이 없다 — 사냥으로 몸부터 만들라.");
        }
        lore.add(ChatColor.DARK_GRAY + String.format("오늘 적립 %.2f일", ledger.grantedToday()));
        return item(Material.EXPERIENCE_BOTTLE, ChatColor.GOLD + "수련", lore);
    }

    private static ItemStack moneyItem(PlayerLedger ledger) {
        return item(Material.GOLD_NUGGET, ChatColor.YELLOW + "소지금 " + ledger.money() + "문",
                List.of(ChatColor.GRAY + "가죽 매각·의뢰 보수로 쌓인다"));
    }

    private static ItemStack marksItem(PlayerLedger ledger) {
        return item(Material.IRON_SWORD,
                ChatColor.WHITE + "실전 마크 " + ledger.marks실전()
                        + ChatColor.GRAY + " · " + ChatColor.WHITE + "사선 마크 " + ledger.marks사선(),
                List.of(ChatColor.GRAY + "격상 상대·사선의 기억 — 돌파 심사의 근거"));
    }

    /** 경지 문장 8단 견본 (U+E020~27) — 리소스팩 적용 확인용 */
    private static ItemStack crestsItem() {
        List<String> lore = new ArrayList<>();
        for (String realm : Glyphs.crestRealms()) {
            lore.add(ChatColor.WHITE + Glyphs.realmCrest(realm) + " " + ChatColor.GRAY + realm);
        }
        lore.add(ChatColor.DARK_GRAY + "□로 보이면 리소스팩 미적용 — 팩 재적용 필요");
        return item(Material.NAME_TAG, ChatColor.GOLD + "경지 문장", lore);
    }

    @SuppressWarnings("deprecation")   // setDisplayName/setLore — legacy String API (기존 코드와 일관)
    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null) {
            meta.setLore(lore);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    // ─── 조작 — 이 GUI 일 때만 개입, 아이템 반출 금지 ───

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof LedgerHolder)) {
            return;   // 다른 인벤토리 — 불간섭
        }
        event.setCancelled(true);   // 표시용 GUI — 버튼 포함 어떤 아이템도 꺼낼 수 없다
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // rawSlot < SIZE = 상단(GUI) 클릭 — 하단(플레이어 인벤토리) 클릭은 취소만 하고 무시
        switch (event.getRawSlot()) {
            case SLOT_SELL -> sellPelts(player, event.getInventory());
            case SLOT_CLOSE -> player.closeInventory();
            default -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof LedgerHolder) {
            event.setCancelled(true);   // 드래그 반입·반출도 금지
        }
    }

    /**
     * 가죽 팔기 버튼 — MvtCommand.sell 과 동일 규칙.
     * 장터 앵커 15블록 검사 → 늑대·여우 가죽 전량, 개당 npcBuyPrice(basePrice, 상술 미판정=false)
     * = 시세 50%. 수치는 전부 EconomyEngine(config/economy.yml) 경유 — 하드코딩 없음.
     * 장터 밖이면 안내만 하고 GUI 는 열어 둔다.
     */
    @SuppressWarnings("deprecation")   // getDisplayName — legacy String API (기존 코드와 일관)
    private void sellPelts(Player player, Inventory gui) {
        Location market = plugin.anchor("장터");
        if (market != null && (player.getWorld() != market.getWorld()
                || player.getLocation().distanceSquared(market) > MARKET_RADIUS * MARKET_RADIUS)) {
            player.sendMessage(ChatColor.GRAY + "여기서는 아무도 가죽을 사지 않는다 — 장터로 가라. ("
                    + market.getBlockX() + ", " + market.getBlockZ() + " 부근, 붉은 차양 노점)");
            return;   // GUI 유지 — 닫지 않는다
        }
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            String name = item == null || !item.hasItemMeta() ? null : item.getItemMeta().getDisplayName();
            if (name == null) {
                continue;
            }
            Integer base = switch (ChatColor.stripColor(name)) {
                case "늑대 가죽" -> plugin.economy().basePrice("사냥_부산물", "늑대_가죽");
                case "여우 가죽" -> plugin.economy().basePrice("사냥_부산물", "여우_가죽");
                default -> null;
            };
            if (base != null) {
                total += plugin.economy().npcBuyPrice(base, false) * item.getAmount();
                player.getInventory().remove(item);
            }
        }
        if (total == 0) {
            player.sendMessage(ChatColor.GRAY + "팔 가죽이 없다.");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
            return;
        }
        PlayerLedger ledger = plugin.ledger(player.getUniqueId());
        ledger.earn(total);
        player.sendMessage(ChatColor.YELLOW + "매입가(시세 50%)로 " + total + "문을 받았다. (흥정은 상술 판정의 몫)");
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 1.0f, 1.0f);
        gui.setItem(SLOT_MONEY, moneyItem(ledger));   // 소지금 표시 즉시 갱신 — GUI 는 열린 채
        plugin.updateSidebar(player);                 // 사이드바도 5초 폴링을 기다리지 않는다
    }
}
