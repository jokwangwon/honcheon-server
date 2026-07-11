package com.honcheon.mvt;

import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC 우클릭 상호작용 — 관리자 피드백: "명령(/혼천 팔기)만으로 팔리는 게 비직관적".
 * 장쇠(장터 잡화상) 우클릭 = 가죽 전량 매각. 규칙은 MvtCommand.sell 과 동일 —
 * basePrice("사냥_부산물", …) × npcBuyPrice(시세 50%), 수치는 전부 EconomyEngine(config/economy.yml).
 * 나머지 등록 NPC 5인(한백·소연·유문·금서방·곽진)은 역할 인사 한 줄 (cheongha_npcs.yml disposition 톤).
 * 명패 있는 무적 주민(= 조성기 등록 NPC)만 다룬다 — 야생 주민은 바닐라 그대로.
 * 이벤트 등록은 HoncheonMvt 의 몫 — 이 클래스는 배선 대상일 뿐이다.
 */
final class TradeListener implements Listener {

    private static final long COOLDOWN_MS = 1_000;   // 플레이어당 상호작용 쿨다운 (스팸 방지)

    /** 매입 상인 명패 — CheonghaBuilder 스폰 명패와 일치해야 한다 */
    private static final String PEDDLER_NAMEPLATE = "장터 잡화상 장쇠";

    /** 등록 NPC 인사 — 명패(조성기 스폰명) → 대사. disposition 톤 반영, 신규 명사 없음 */
    private static final Map<String, String> GREETINGS = Map.of(
            "객잔 주인 한백", ChatColor.GOLD + "[한백] " + ChatColor.WHITE
                    + "밥이든 방이든 값은 선불일세 — 낯선 손은 특히. 소문은 술이 들어가야 나오는 법이지.",
            "의뢰소 관리인 소연", ChatColor.GOLD + "[소연] " + ChatColor.WHITE
                    + "의뢰를 맡으려면 게시판부터 보고 오게. 말보다 확실한 태도를 보여야 일을 맡기지.",
            "의원 유문", ChatColor.GOLD + "[유문] " + ChatColor.WHITE
                    + "다친 데가 있으면 앉게. 없으면 비켜주게 — 환자가 밀렸네. 약값이 급하면 외상도 받네.",
            "전장 지점주 금서방", ChatColor.GOLD + "[금서방] " + ChatColor.WHITE
                    + "어서 오십시오. 전표는 신용이 확인된 분께만 끊어 드립니다 — 장부는 거짓말을 하지 않지요.",
            "표사 곽진", ChatColor.GOLD + "[곽진] " + ChatColor.WHITE
                    + "말은 됐네. 어깨너머로 배울 수 있는 건 없어 — 배울 생각이면 /혼천 사사 로 정식으로 청하게.");

    private final HoncheonMvt plugin;
    private final Map<UUID, Long> lastInteract = new ConcurrentHashMap<>();

    TradeListener(HoncheonMvt plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    @SuppressWarnings("deprecation")
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;   // 우클릭은 손마다 한 번씩 발화 — 메인핸드만 (중복 방어)
        }
        if (!(event.getRightClicked() instanceof Villager villager)) {
            return;
        }
        String nameplate = villager.getCustomName();
        if (nameplate == null || !villager.isInvulnerable()) {
            return;   // 야생 주민 — 불간섭 (등록 NPC = 명패 + 무적, CheonghaBuilder 스폰 규약)
        }
        event.setCancelled(true);   // 바닐라 주민 거래 GUI 차단 — 등록 NPC 는 대사로 말한다

        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Long last = lastInteract.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) {
            return;   // 쿨다운 중 — GUI 차단은 유지, 대사만 침묵
        }
        lastInteract.put(player.getUniqueId(), now);

        String plain = ChatColor.stripColor(nameplate);
        if (PEDDLER_NAMEPLATE.equals(plain)) {
            sellPelts(player);
            return;
        }
        String greeting = GREETINGS.get(plain);
        if (greeting != null) {
            player.sendMessage(greeting);
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_AMBIENT, 0.8f, 1.0f);
        }
    }

    /**
     * 장쇠 매입 — MvtCommand.sell 과 동일 규칙 (장터 앵커 거리 검사는 불필요: 장쇠 자체가 장터다).
     * 늑대 가죽·여우 가죽 전량, 개당 npcBuyPrice(basePrice, 상술 미판정=false) = 시세 50%.
     */
    @SuppressWarnings("deprecation")
    private void sellPelts(Player player) {
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
            player.sendMessage(ChatColor.GOLD + "[장쇠] " + ChatColor.GRAY + "빈손이면 구경만 하게.");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
            return;
        }
        plugin.ledger(player.getUniqueId()).earn(total);
        player.sendMessage(ChatColor.GOLD + "[장쇠] " + ChatColor.WHITE + "좋은 물건이군 — "
                + ChatColor.YELLOW + total + "문" + ChatColor.WHITE + " 쳐주지.");
        player.sendMessage(ChatColor.GRAY + "(매입가 = 시세 50% — 흥정은 상술 판정의 몫)");
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 1.0f, 1.0f);
        plugin.updateSidebar(player);   // 소지금 즉시 반영 — 5초 폴링을 기다리지 않는다
    }
}
