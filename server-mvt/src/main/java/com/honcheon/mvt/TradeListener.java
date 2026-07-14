package com.honcheon.mvt;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC 우클릭 상호작용 — 관리자 피드백: "명령(/혼천 팔기)만으로 팔리는 게 비직관적".
 * 장쇠(장터 잡화상) 우클릭 = 가죽 전량 매각. 규칙은 MvtCommand.sell 과 동일 —
 * basePrice("사냥_부산물", …) × npcBuyPrice(시세 50%), 수치는 전부 EconomyEngine(config/economy.yml).
 * 나머지 등록 NPC 5인(한백·소연·유문·금서방·곽진)은 역할 인사 한 줄 (cheongha_npcs.yml disposition 톤).
 * ★ B-121 — 등록부에 대사가 있는 NPC(npcs.&lt;id&gt;.greeting_lines — 첫 손님: 뱃사공 섭구)는
 *   그 줄을 글자 그대로 말한다. 문구는 등록부가 정본 — 코드의 GREETINGS 는 이행기 잔존이다.
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

    /**
     * 등록부의 대사 — {@code npcs/cheongha_npcs.yml npcs.<id>.greeting_lines} (B-121).
     * 위 GREETINGS(코드에 박힌 다섯 줄)보다 <b>등록부가 먼저다</b> — 문구는 등록부가 정본이고,
     * 등록부에 대사가 있는 NPC 는 이 표가 말한다. 키 = PDC 표식(honcheon:town_npc)의 등록부 id.
     */
    private final Map<String, List<String>> registryLines = new java.util.HashMap<>();

    /** 등록부의 이름 — 대사 머리의 [이름] (명패의 역할 접두어가 아니라 등록부 name 으로 부른다) */
    private final Map<String, String> registryNames = new java.util.HashMap<>();

    TradeListener(HoncheonMvt plugin) {
        this.plugin = plugin;
        // 등록부 판독 — 다른 소비자(SkillEngine·HuntingGrounds)와 같은 길: RulesConfig.load 한 번.
        Map<String, Object> npcs = com.honcheon.core.rules.RulesConfig.section(
                com.honcheon.core.rules.RulesConfig.load(
                        new java.io.File(plugin.getDataFolder(), "config").toPath()
                                .resolve("npcs/cheongha_npcs.yml")), "npcs");
        for (Map.Entry<String, Object> e : npcs.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> body)) {
                continue;
            }
            Object lines = body.get("greeting_lines");
            if (!(lines instanceof List<?> list) || list.isEmpty()) {
                continue;   // 대사가 등록되지 않은 NPC — GREETINGS(이행기 잔존) 또는 침묵
            }
            List<String> said = new java.util.ArrayList<>();
            for (Object line : list) {
                if (line instanceof String s && !s.isBlank()) {
                    said.add(s);
                }
            }
            if (!said.isEmpty()) {
                registryLines.put(e.getKey(), List.copyOf(said));
                Object name = body.get("name");
                registryNames.put(e.getKey(), name instanceof String s ? s : e.getKey());
            }
        }
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
        if (nameplate == null || !townNpc(villager)) {
            return;   // 야생 주민 — 불간섭 (등록 NPC = PDC 표식, CheonghaBuilder 스폰 규약 · B-119)
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
        // 등록부의 대사가 먼저다 (B-121) — PDC 표식의 등록부 id 로 npcs/cheongha_npcs.yml 을 부른다
        String id = villager.getPersistentDataContainer()
                .get(CheonghaBuilder.KEY_NPC, PersistentDataType.STRING);
        List<String> lines = id == null ? null : registryLines.get(id);
        if (lines != null) {
            String name = registryNames.getOrDefault(id, plain);
            for (String line : lines) {
                player.sendMessage(ChatColor.GOLD + "[" + name + "] " + ChatColor.WHITE + line);
            }
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_AMBIENT, 0.8f, 1.0f);
            return;
        }
        String greeting = GREETINGS.get(plain);
        if (greeting != null) {
            player.sendMessage(greeting);
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_AMBIENT, 0.8f, 1.0f);
        }
    }

    /**
     * 계약 NPC 인가 — <b>표식(PDC honcheon:town_npc)이 정본</b>이다 (B-119).
     * 명패+무적은 옛 규약의 잔존 몸 — {@link #onEntitiesLoad} 이행 스윕이 곧 표식으로 바꾼다.
     */
    private static boolean townNpc(Villager v) {
        return v.getPersistentDataContainer().has(CheonghaBuilder.KEY_NPC, PersistentDataType.STRING)
                || (v.getCustomName() != null && v.isInvulnerable());
    }

    /**
     * <b>규약 이행 스윕 (B-119)</b> — 옛 규약(명패+무적)으로 서 있는 몸을 새 규약(표식·가격 가능)으로.
     *
     * <p>사용자 실측 <i>"npc 안때려짐"</i>의 기전이 바로 이 무적이었다 — 무적의 몸에는 피해 이벤트
     * 자체가 서지 않는다. 조성기는 이제 표식으로 낳지만({@link CheonghaBuilder#KEY_NPC}), <b>이미 선
     * 몸</b>은 재조성 전까지 무적인 채다. 청크가 올라오는 순간마다 여기서 이행한다 — 서버를 껐다
     * 켜도, 멀리 있던 몸도, 눈앞에 서는 순간에는 맞는 몸이다.
     *
     * <p>나루(Antechamber)는 남의 트랙이라 손대지 않는다 — 다만 그 세계에 명패+무적 주민은 없다
     * (허수아비는 좀비·무적 아님, 시체 표지는 ArmorStand). 그래도 세계 게이트를 세워 둔다.
     */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (Antechamber.isAntechamber(event.getWorld())) {
            return;
        }
        for (Entity e : event.getEntities()) {
            if (e instanceof Villager v && v.getCustomName() != null && v.isInvulnerable()) {
                String plain = ChatColor.stripColor(v.getCustomName());
                v.getPersistentDataContainer().set(CheonghaBuilder.KEY_NPC, PersistentDataType.STRING,
                        CheonghaBuilder.NPC_IDS.getOrDefault(plain, plain));
                v.setInvulnerable(false);
            }
        }
    }

    /**
     * <b>계약 NPC 의 죽음 — 사실이 다리를 건넌다</b> (B-119: <i>"몰래 죽일수도 있어야 해요"</i>의 씨앗).
     *
     * <p>등록 그대로다: {@code world_bridge.yml events.npc_death} · registry {@code cheongha_npcs}
     * (region_event 배선: 등록_npc_사망). MVT 는 <b>사실만</b> 나른다 — 누가·어디서·목격 몇·밤인가.
     * 목격의 뒤(수배·소문·업보)는 봇의 몫이고, 그 자리는 후계·재조성 전까지 빈다 — 죽음의 값이다.
     *
     * <p>목격 반경 24 는 {@link Populace} 지역 사망의 눈금 그대로 (새 수치를 짓지 않는다).
     */
    @EventHandler
    public void onNpcDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Villager v)) {
            return;
        }
        String id = v.getPersistentDataContainer().get(CheonghaBuilder.KEY_NPC, PersistentDataType.STRING);
        if (id == null) {
            return;   // 계약 NPC 가 아니다 — 무명·지역 사람은 Populace 가 나른다
        }
        event.getDrops().clear();   // 사람은 전리품이 아니다 (Populace 규약 그대로)
        event.setDroppedExp(0);

        String plain = v.getCustomName() == null ? id : ChatColor.stripColor(v.getCustomName());
        int cut = plain.lastIndexOf(' ');
        String job = cut > 0 ? plain.substring(0, cut) : "";   // 명패 = 「생업 이름」 (조성기 규약)
        String name = cut > 0 ? plain.substring(cut + 1) : plain;

        Player killer = v.getKiller();
        int witnesses = 0;
        for (Entity e : v.getNearbyEntities(24, 12, 24)) {   // 살아 있는 눈만 — 죽은 자는 증언하지 않는다
            if ((e instanceof Player p && !p.equals(killer))
                    || (e instanceof Villager other && !other.isDead())) {
                witnesses++;
            }
        }
        String seg = plugin.populace() == null ? "" : plugin.populace().segment();
        boolean night = "밤".equals(seg) || "새벽".equals(seg);

        plugin.getLogger().info("[계약 NPC 사망] " + plain
                + (killer == null ? " · 사인 미상" : " · 살해 " + killer.getName())
                + " · 목격 " + witnesses + (night ? " · 밤" : ""));
        WorldBridge.npcDeath("cheongha_npcs", id, name, job, placeKey(v.getLocation()),
                witnesses, night, "즉시_발견",
                killer == null ? "사고" : "플레이어_살해",
                killer == null ? null : killer.getUniqueId(),
                killer == null ? null : killer.getName());
    }

    /** 자리 이름 — 소문의 발원망은 '어디서 났는가'가 정한다 ({@link Incidents} placeKey 와 같은 셈) */
    private String placeKey(Location at) {
        Populace people = plugin.populace();
        if (people == null) {
            return "장터_광장";
        }
        String best = null;
        double bestDist = Double.MAX_VALUE;
        for (String name : people.placeNames()) {
            Location center = people.placeCenter(name);
            if (center == null || center.getWorld() != at.getWorld()) {
                continue;
            }
            double d = center.distance(at);
            if (d < bestDist) {
                bestDist = d;
                best = name;
            }
        }
        return best == null ? "장터_광장" : best;
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
