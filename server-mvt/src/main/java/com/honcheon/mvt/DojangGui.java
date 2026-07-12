package com.honcheon.mvt;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 연무장의 시험대 — <b>클릭으로 고른다</b>.
 *
 * <p>사용자 요구: "연무장에서 상호작용할 무언가를 통해 클릭으로 장비 받고 기술 등 선택하여 테스트."
 *
 * <p>명령을 외워 치는 시험은 시험이 아니라 시험 준비다. 병기 45자루(9계열 × 5등급)와 무공 수십 종을
 * 이름으로 기억할 이유가 없다 — <b>보고 고르면 된다.</b>
 *
 * <p>시험대 넷:
 * <ul>
 *   <li><b>병기대</b> — 9계열 × 5등급. 아이콘이 곧 그 병기다 (팩이 켜져 있으면 실제 텍스처가 보인다).
 *       집으면 손에 온다. 툴팁에 공격력·공속·사거리·내구가 이미 적혀 있다</li>
 *   <li><b>무공대</b> — 등록부의 무공 전부. 고르면 수련 30일이 적립된다 (숙련이 곧 위력이다)</li>
 *   <li><b>경지대</b> — 삼류~화경. 고르면 내력 풀이 그 경지의 것으로 선다</li>
 *   <li><b>적수대</b> — 허수아비(내구 12·18·22·30)와 등록부의 적(짐승·산적·무인)</li>
 * </ul>
 */
final class DojangGui implements Listener {

    private final HoncheonMvt plugin;

    DojangGui(HoncheonMvt plugin) {
        this.plugin = plugin;
    }

    private record Menu(String kind, int page) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;   // 홀더는 표식일 뿐 — 실제 인벤토리는 open() 이 만든다
        }
    }

    /** 한 쪽에 담는 칸 — 아래 한 줄은 쪽 넘김의 것이다 */
    private static final int PAGE = 45;

    // ─── 여는 손 ───

    /**
     * 병기대 — <b>45자루가 한눈에</b> (9계열 × 5등급).
     *
     * <p>사용자 요구: "모든 스킬과 장비를 한눈에 보고 클릭으로 얻고 사용할 수 있게."
     * 그래서 한 쪽에 계열 하나씩 한 줄로 편다 — <b>가로가 등급, 세로가 계열</b>이다.
     * 눈이 표를 읽듯 읽는다: 범철 검이 어디 있고 신병 창이 어디 있는지 세지 않아도 안다.
     */
    void openWeapons(Player player, int page) {
        Weapons.Series[] all = Weapons.Series.values();
        int perPage = 5;   // 한 쪽에 계열 다섯 줄 (등급 5칸씩)
        int pages = (all.length + perPage - 1) / perPage;
        int p = Math.floorMod(page, Math.max(1, pages));
        Inventory inv = Bukkit.createInventory(new Menu("병기", p), 54,
                ChatColor.DARK_GRAY + "병기대 — 가로 등급 · 세로 계열 (" + (p + 1) + "/" + pages + ")");
        for (int row = 0; row < perPage; row++) {
            int index = p * perPage + row;
            if (index >= all.length) {
                break;
            }
            Weapons.Series series = all[index];
            int col = 0;
            for (Weapons.Grade grade : Weapons.Grade.values()) {
                try {
                    inv.setItem(row * 9 + col, Weapons.make(series, grade));
                } catch (RuntimeException notMade) {
                    // 규칙이 막는 조합은 자리를 비운다 (없는 물건은 그리지 않는다)
                }
                col++;
            }
            inv.setItem(row * 9 + 8, icon(Material.PAPER, ChatColor.GOLD + series.name(),
                    List.of(ChatColor.GRAY + "사거리 " + String.format("%.1f", series.reach()) + "m",
                            ChatColor.DARK_GRAY + "← 가로가 등급이다")));
        }
        nav(inv, pages > 1);
        player.openInventory(inv);
    }

    /** 무공대 — 등록부의 무공 전부. 고르면 수련 30일 (숙련이 곧 위력이다) */
    void openArts(Player player, int page) {
        List<Map.Entry<String, String>> arts = new ArrayList<>(plugin.skillEngine().artNames().entrySet());
        int pages = Math.max(1, (arts.size() + PAGE - 1) / PAGE);
        int p = Math.floorMod(page, pages);
        Inventory inv = Bukkit.createInventory(new Menu("무공", p), 54,
                ChatColor.DARK_GRAY + "무공대 — 클릭 = 수련 +30일 (" + (p + 1) + "/" + pages + ")");
        for (int i = 0; i < PAGE; i++) {
            int index = p * PAGE + i;
            if (index >= arts.size()) {
                break;
            }
            Map.Entry<String, String> e = arts.get(index);
            double learned = plugin.ledger(player.getUniqueId()).allSkills().getOrDefault(e.getKey(), 0.0);
            inv.setItem(i, icon(learned > 0 ? Material.WRITTEN_BOOK : Material.BOOK,
                    ChatColor.WHITE + e.getValue(),
                    List.of(ChatColor.DARK_GRAY + e.getKey(),
                            ChatColor.GRAY + "누적 수련 " + String.format("%.1f", learned) + "일",
                            ChatColor.YELLOW + "클릭 — 수련 +30일")));
        }
        nav(inv, pages > 1);
        player.openInventory(inv);
    }

    /** 아래 한 줄 — 쪽 넘김 (한눈에 안 들어가면 넘겨서 본다) */
    private static void nav(Inventory inv, boolean multi) {
        if (!multi) {
            return;
        }
        inv.setItem(45, icon(Material.ARROW, ChatColor.WHITE + "◀ 이전 쪽", List.of()));
        inv.setItem(53, icon(Material.ARROW, ChatColor.WHITE + "다음 쪽 ▶", List.of()));
    }

    void openRealms(Player player) {
        Inventory inv = Bukkit.createInventory(new Menu("경지", 0), 9,
                ChatColor.DARK_GRAY + "경지대 — 고르면 내력이 그 경지의 것이 된다");
        String[] realms = {"삼류", "이류", "일류", "절정", "초절정", "화경"};
        int slot = 0;
        for (String realm : realms) {
            int pool = plugin.skillEngine().poolOf(realm);
            inv.setItem(slot++, icon(Material.AMETHYST_SHARD, ChatColor.GOLD + realm,
                    List.of(ChatColor.GRAY + "내력 풀 " + pool,
                            ChatColor.DARK_GRAY + "격: " + gradesOf(realm),
                            ChatColor.YELLOW + "클릭 — 이 경지로 선다")));
        }
        player.openInventory(inv);
    }

    void openFoes(Player player) {
        Inventory inv = Bukkit.createInventory(new Menu("적수", 0), 27,
                ChatColor.DARK_GRAY + "적수대 — 허수아비와 등록부의 적");
        int[] durabilities = {12, 18, 22, 30};
        String[] labels = {"삼류 몸(12)", "이류 몸(18)", "절정 몸(22)", "고수의 몸(30)"};
        for (int i = 0; i < durabilities.length; i++) {
            inv.setItem(i, icon(Material.HAY_BLOCK, ChatColor.WHITE + "허수아비 · " + labels[i],
                    List.of(ChatColor.GRAY + "죽지 않고 반격하지 않는다",
                            ChatColor.GRAY + "명패가 최근·누적·합수·평균·TTK 를 말한다",
                            ChatColor.YELLOW + "클릭 — 세운다")));
        }
        int slot = 9;
        for (String foeId : HuntingGrounds.foeIds()) {
            if (slot >= 27) {
                break;
            }
            inv.setItem(slot++, icon(Material.ZOMBIE_HEAD, ChatColor.WHITE + foeId,
                    List.of(ChatColor.GRAY + "등록부의 적 — 반격한다",
                            ChatColor.YELLOW + "클릭 — 부른다")));
        }
        player.openInventory(inv);
    }

    // ─── 고르는 손 ───

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);   // 시험대의 물건은 옮기는 것이 아니라 고르는 것이다
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }
        String label = ChatColor.stripColor(name(clicked) == null ? "" : name(clicked));
        if ("◀ 이전 쪽".equals(label) || "다음 쪽 ▶".equals(label)) {
            int step = label.startsWith("◀") ? -1 : 1;
            if ("병기".equals(menu.kind())) {
                openWeapons(player, menu.page() + step);
            } else if ("무공".equals(menu.kind())) {
                openArts(player, menu.page() + step);
            }
            return;
        }
        if (clicked.getType() == Material.PAPER) {
            return;   // 계열 표식 — 고르는 물건이 아니다
        }
        switch (menu.kind()) {
            case "병기" -> {
                player.getInventory().addItem(clicked.clone());
                player.sendMessage(ChatColor.GRAY + "집었다 — "
                        + (clicked.getItemMeta() != null ? clicked.getItemMeta().getDisplayName() : ""));
            }
            case "무공" -> {
                String id = plainLore(clicked, 0);
                if (id != null) {
                    plugin.dojang().grantSkill(player, id, 30.0);
                    openArts(player, menu.page());   // 누적이 늘어난 목록을 다시 그린다
                }
            }
            case "경지" -> {
                if (!label.isEmpty()) {
                    plugin.dojang().setRealm(player, label);
                    player.closeInventory();
                }
            }
            case "적수" -> {
                if (label.isEmpty()) {
                    return;
                }
                player.closeInventory();
                if (label.startsWith("허수아비")) {
                    int durability = 20;
                    int open = label.indexOf('(');
                    if (open > 0) {
                        durability = Integer.parseInt(label.substring(open + 1, label.indexOf(')')));
                    }
                    plugin.dojang().dummy(player, durability);
                } else {
                    plugin.dojang().mob(player, label);
                }
            }
            default -> { }
        }
    }

    // ─── 손 ───

    private String gradesOf(String realm) {
        List<String> armable = plugin.skillEngine().armableGrades(realm);
        return armable.isEmpty() ? "외공기" : String.join("·", armable);
    }

    private static ItemStack icon(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(new ArrayList<>(lore));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String name(ItemStack item) {
        return item.getItemMeta() == null ? null : item.getItemMeta().getDisplayName();
    }

    /** 로어 한 줄의 색을 벗겨 읽는다 (무공대는 첫 줄에 id 를 숨겨 둔다) */
    private static String plainLore(ItemStack item, int line) {
        if (item.getItemMeta() == null || item.getItemMeta().getLore() == null
                || item.getItemMeta().getLore().size() <= line) {
            return null;
        }
        return ChatColor.stripColor(item.getItemMeta().getLore().get(line));
    }
}
