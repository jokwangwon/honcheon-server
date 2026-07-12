package com.honcheon.mvt;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 연무장(演武場) — <b>따로 두들겨 보는 자리</b>.
 *
 * <p>사용자 판정: "서버 스킬 테스트나 몹 테스트 서버를 만드는 게 좋을까요? 이것만 따로 써 보고 평가하고 싶다.
 * 명령어로 멀티 월드처럼 이동하고, 임의로 단계를 움직이거나 능력치를 조정하고, 스킬도 쓰고,
 * <b>허수아비를 설치해 데미지 테스트</b>도 해 보고."
 *
 * <p>맞는 판단이다. 밸런스와 모션은 <b>세계 안에서는 못 읽는다</b> — 청하현에서 무공을 시험하면
 * 마을이 부서지고, 도적이 죽고, 소문이 돌고, 관이 움직인다. 시험은 세계에 자국을 남기면 안 된다.
 *
 * <p>그래서 연무장은 <b>별도 월드</b>다 (world: {@code honcheon_dojang}):
 * <ul>
 *   <li>평평한 허공 — 지형이 시험을 방해하지 않는다</li>
 *   <li>몹 자연 스폰 없음 · 항상 낮 · 날씨 없음 — 변수를 없앤다</li>
 *   <li><b>세계와 이어지지 않는다</b> — 여기서 벤 것은 소문이 되지 않는다 (WorldBridge 는 이 월드를 무시한다)</li>
 * </ul>
 *
 * <p><b>허수아비</b>는 맞아 주는 몸이다. 죽지 않고, 반격하지 않고, <b>맞은 것을 말한다</b>:
 * 누적 피해 · 최근 한 합 · 합수 · 합당 평균. 밸런스는 느낌이 아니라 숫자다.
 */
final class Dojang implements Listener {

    static final String WORLD = "honcheon_dojang";

    private static final NamespacedKey KEY_DUMMY = new NamespacedKey("honcheon", "dummy");

    private final HoncheonMvt plugin;
    /** 돌아갈 자리 — 시험이 끝나면 세계로 돌려보낸다 */
    private final Map<UUID, Location> origins = new HashMap<>();
    /** 허수아비 장부 — 누적 피해·합수 */
    private final Map<UUID, double[]> tally = new HashMap<>();   // [누적, 합수, 최근]

    // ─── 두 세계의 장부는 섞이지 않는다 (사용자 규정) ───
    //
    // "여기서 얻은 수련이나 모든 것들은 기존 월드와 분리."
    // 시험장에서 얻은 30일 수련과 신병(神兵)이 강호의 것이 되면, 그건 시험이 아니라 치트다.
    // 들어갈 때 현실의 장부·무공 상태·짐을 **떼어 두고**, 시험용 벌을 끼운다. 나올 때 되돌린다.
    // 연무장의 장부는 그것대로 남는다 — 다시 들어오면 어제 시험하던 자리에서 이어진다.

    private final Map<UUID, PlayerLedger> realLedger = new HashMap<>();
    private final Map<UUID, SkillEngine.State> realState = new HashMap<>();
    private final Map<UUID, org.bukkit.inventory.ItemStack[]> realInventory = new HashMap<>();
    private final Map<UUID, PlayerLedger> testLedger = new HashMap<>();
    private final Map<UUID, SkillEngine.State> testState = new HashMap<>();
    private final Map<UUID, org.bukkit.inventory.ItemStack[]> testInventory = new HashMap<>();

    Dojang(HoncheonMvt plugin) {
        this.plugin = plugin;
    }

    /** 연무장 월드 — 없으면 만든다 (평평한 허공 · 몹 없음 · 항상 낮) */
    World world() {
        World w = Bukkit.getWorld(WORLD);
        if (w != null) {
            return w;
        }
        w = new WorldCreator(WORLD)
                .type(WorldType.FLAT)
                .generateStructures(false)
                .generatorSettings("{\"layers\":[{\"block\":\"minecraft:stone\",\"height\":1},"
                        + "{\"block\":\"minecraft:dirt\",\"height\":2},"
                        + "{\"block\":\"minecraft:grass_block\",\"height\":1}],\"biome\":\"minecraft:plains\"}")
                .createWorld();
        if (w == null) {
            return null;
        }
        w.setGameRule(GameRule.DO_MOB_SPAWNING, false);      // 변수를 없앤다 — 시험은 시험만 남아야 한다
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        w.setGameRule(GameRule.KEEP_INVENTORY, true);
        w.setGameRule(GameRule.MOB_GRIEFING, false);
        w.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        w.setTime(6000);
        w.setStorm(false);
        return w;
    }

    /** 여기가 연무장인가 — 세계 다리·사냥·소문이 이 월드를 무시하는 근거 */
    static boolean isDojang(World world) {
        return world != null && WORLD.equals(world.getName());
    }

    // ─── 이동 ───

    void enter(Player player) {
        World w = world();
        if (w == null) {
            player.sendMessage(ChatColor.RED + "연무장을 열 수 없다.");
            return;
        }
        UUID id = player.getUniqueId();
        if (!isDojang(player.getWorld())) {
            origins.put(id, player.getLocation());   // 돌아갈 자리를 기억한다
            // 현실의 장부·무공·짐을 떼어 둔다 — 시험은 세계에 자국을 남기지 않는다
            realLedger.put(id, plugin.swapLedger(id,
                    testLedger.computeIfAbsent(id, k -> new PlayerLedger())));
            SkillEngine.State mine = plugin.skills().state(player);   // 세계의 나 (경지는 봇의 시트)
            SkillEngine.State test = testState.computeIfAbsent(id, k -> new SkillEngine.State());
            if (test.realm == null) {
                // 시험대에는 **제 경지로** 들어선다 — 그 뒤에 /혼천 시험 경지 로 갈아 낀다.
                // (경지 기본값이 코드에 없어졌다: 그 자리에 "이류"가 박혀 있었다)
                test.realm = mine.realm;
                test.naegong = mine.naegong;
                test.energy = mine.energy;
            }
            realState.put(id, plugin.skills().swapState(id, test));
            realInventory.put(id, player.getInventory().getContents().clone());
            player.getInventory().setContents(testInventory.computeIfAbsent(id,
                    k -> new org.bukkit.inventory.ItemStack[player.getInventory().getSize()]));
        }
        // 평면 월드의 지면은 y5 가 아니다 — 층이 월드 최저(-64)부터 쌓이므로 지표는 y-61 언저리다.
        // 구판은 y5 에 떨어뜨렸고 사람이 **낙사**했다. 지면은 월드에게 묻는다.
        int groundY = w.getHighestBlockYAt(0, 0);
        Location at = new Location(w, 0.5, groundY + 1.0, 0.5, 0f, 0f);
        pad(w, groundY);   // 딛는 자리 — 첫 걸음이 허공이면 시험이고 뭐고 없다
        player.teleport(at);
        player.setFallDistance(0f);
        player.setGameMode(org.bukkit.GameMode.CREATIVE);   // 시험은 죽음의 자리가 아니다
        player.sendMessage(ChatColor.GOLD + "── 연무장 ──");
        player.sendMessage(ChatColor.GRAY + "여기서 벤 것은 소문이 되지 않는다. 마음껏 두들겨라.");
        player.sendMessage(ChatColor.GRAY + "/혼천 시험 경지 <삼류|이류|일류|절정|초절정|화경> · "
                + "/혼천 시험 내력 <값> · /혼천 시험 무공 <id>");
        player.sendMessage(ChatColor.GRAY + "/혼천 허수아비 [내구] · /혼천 시험 몹 <id> · /혼천 귀환");
    }

    /** 연무장 바닥 — 32×32 돌바닥과 표식. 허공에 떨구지 않는다 */
    private void pad(World w, int groundY) {
        for (int x = -16; x <= 16; x++) {
            for (int z = -16; z <= 16; z++) {
                w.getBlockAt(x, groundY, z).setType(Math.floorMod(x + z, 8) == 0
                        ? Material.ANDESITE : Material.POLISHED_ANDESITE);
                for (int y = groundY + 1; y <= groundY + 4; y++) {
                    w.getBlockAt(x, y, z).setType(Material.AIR);
                }
            }
        }
        for (int i = -16; i <= 16; i += 8) {   // 열 자(尺) — 거리를 눈으로 잰다 (사거리 시험)
            w.getBlockAt(i, groundY, 0).setType(Material.DARK_OAK_PLANKS);
            w.getBlockAt(0, groundY, i).setType(Material.DARK_OAK_PLANKS);
        }
        // 시험대 넷 — **클릭으로 고른다** (명령을 외워 치는 시험은 시험 준비다)
        stand(w, -5, groundY, -5, Material.SMITHING_TABLE, "병기대", "9계열 × 5등급 — 집으면 손에 온다");
        stand(w, 5, groundY, -5, Material.LECTERN, "무공대", "등록부의 무공 — 고르면 수련 30일");
        stand(w, -5, groundY, 5, Material.ENCHANTING_TABLE, "경지대", "삼류~화경 — 내력 풀이 그 경지의 것으로");
        stand(w, 5, groundY, 5, Material.TARGET, "적수대", "허수아비 · 등록부의 적");
    }

    /** 시험대 한 자리 — 블록 + 그 위 명패 */
    private void stand(World w, int x, int y, int z, Material block, String title, String note) {
        w.getBlockAt(x, y + 1, z).setType(block);
        org.bukkit.block.Block sign = w.getBlockAt(x, y + 2, z);
        sign.setType(Material.OAK_SIGN);
        if (sign.getState() instanceof org.bukkit.block.Sign s) {
            s.getSide(org.bukkit.block.sign.Side.FRONT).setLine(0, ChatColor.GOLD + title);
            s.getSide(org.bukkit.block.sign.Side.FRONT).setLine(1, ChatColor.GRAY + "우클릭");
            s.getSide(org.bukkit.block.sign.Side.FRONT).setLine(2, ChatColor.DARK_GRAY + note.substring(0,
                    Math.min(15, note.length())));
            s.update();
        }
    }

    /** 시험대를 우클릭하면 그 자리의 목록이 열린다 */
    @EventHandler
    public void onInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || !isDojang(event.getPlayer().getWorld())
                || event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Material m = event.getClickedBlock().getType();
        DojangGui gui = plugin.dojangGui();
        Player p = event.getPlayer();
        switch (m) {
            case SMITHING_TABLE -> {
                event.setCancelled(true);
                gui.openWeapons(p, 0);
            }
            case LECTERN -> {
                event.setCancelled(true);
                gui.openArts(p, 0);
            }
            case ENCHANTING_TABLE -> {
                event.setCancelled(true);
                gui.openRealms(p);
            }
            case TARGET -> {
                event.setCancelled(true);
                gui.openFoes(p);
            }
            default -> { }
        }
    }

    void leave(Player player) {
        UUID id = player.getUniqueId();
        if (isDojang(player.getWorld())) {
            // 시험의 장부는 시험장에 두고 간다 (다시 들어오면 그 자리에서 이어진다)
            testInventory.put(id, player.getInventory().getContents().clone());
            testLedger.put(id, plugin.swapLedger(id, realLedger.remove(id)));
            testState.put(id, plugin.skills().swapState(id, realState.remove(id)));
            org.bukkit.inventory.ItemStack[] back = realInventory.remove(id);
            player.getInventory().setContents(back != null ? back
                    : new org.bukkit.inventory.ItemStack[player.getInventory().getSize()]);
        }
        player.setGameMode(org.bukkit.GameMode.SURVIVAL);
        Location back2 = origins.remove(id);
        Location back = back2;
        if (back == null || back.getWorld() == null) {
            Location market = plugin.anchor("장터");
            back = market != null ? market : Bukkit.getWorlds().get(0).getSpawnLocation();
        }
        player.teleport(back);
        player.sendMessage(ChatColor.GRAY + "연무장을 나선다 — 시험의 수련·병기는 시험장에 두고 간다.");
    }

    // ─── 능력치 조정 ───

    void setRealm(Player player, String realm) {
        SkillEngine.State state = plugin.skills().state(player);
        state.realm = realm;
        state.energy = plugin.skillEngine().poolOf(realm);
        player.sendMessage(ChatColor.GOLD + "경지 " + realm
                + ChatColor.GRAY + " · 내력 풀 " + state.energy);
    }

    void setEnergy(Player player, int energy) {
        SkillEngine.State state = plugin.skills().state(player);
        state.energy = Math.max(0, energy);
        player.sendMessage(ChatColor.GOLD + "내력 " + state.energy);
    }

    void grantSkill(Player player, String skillId, double days) {
        plugin.ledger(player.getUniqueId()).grant(skillId, days);
        player.sendMessage(ChatColor.GOLD + skillId + ChatColor.GRAY + " 수련 +"
                + String.format("%.1f", days) + "일 (누적 "
                + String.format("%.1f", plugin.ledger(player.getUniqueId()).allSkills()
                        .getOrDefault(skillId, 0.0)) + "일)");
    }

    // ─── 허수아비 ───

    /**
     * 허수아비 — 맞아 주는 몸. 죽지 않고, 반격하지 않고, <b>맞은 것을 말한다</b>.
     *
     * <p>몸은 좀비다 (인간형이라 격·무기 판정이 사람 상대와 같게 돈다 — 짚단을 때리면
     * 사람을 때린 것과 다른 숫자가 나온다). AI 를 끄고, 죽지 않게 하고, 명패로 장부를 보여 준다.
     */
    void dummy(Player player, int durability) {
        World w = player.getWorld();
        Location at = player.getLocation().add(player.getLocation().getDirection().multiply(4));
        at.setY(player.getLocation().getY());
        Zombie z = w.spawn(at, Zombie.class, e -> {
            e.setAI(false);
            e.setSilent(true);
            e.setCollidable(true);
            e.setRemoveWhenFarAway(false);
            e.setShouldBurnInDay(false);
            e.setAdult();
            e.getPersistentDataContainer().set(KEY_DUMMY, PersistentDataType.INTEGER, durability);
            if (e.getAttribute(Attribute.MAX_HEALTH) != null) {
                e.getAttribute(Attribute.MAX_HEALTH).setBaseValue(2048);   // 죽지 않는다 (장부를 위해 산다)
            }
            e.setHealth(2048);
            e.setCustomNameVisible(true);
            e.setCustomName(ChatColor.GRAY + "허수아비 · 내구 " + durability);
        });
        tally.put(z.getUniqueId(), new double[]{0, 0, 0});
        player.sendMessage(ChatColor.GOLD + "허수아비 (내구 " + durability + ") — 때려라. "
                + ChatColor.GRAY + "명패가 누적·합수·평균을 말한다");
    }

    /** 몹 시험 — 등록부의 적을 그대로 부른다 (짐승·산적·무인) */
    void mob(Player player, String foeId) {
        Location at = player.getLocation().add(player.getLocation().getDirection().multiply(5));
        LivingEntity spawned = plugin.hunting().spawnById(foeId, at);
        if (spawned == null) {
            player.sendMessage(ChatColor.RED + "등록부에 없는 적: " + foeId);
            return;
        }
        player.sendMessage(ChatColor.GOLD + spawned.getCustomName() + ChatColor.GRAY + " 을(를) 불렀다");
    }

    void clear(Player player) {
        int removed = 0;
        for (org.bukkit.entity.Entity e : player.getWorld().getEntities()) {
            if (e instanceof LivingEntity && !(e instanceof Player)) {
                e.remove();
                removed++;
            }
        }
        tally.clear();
        player.sendMessage(ChatColor.GRAY + "연무장을 치웠다 — " + removed + "체");
    }

    // ─── 계측 — 허수아비는 맞은 것을 말한다 ───

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        double[] t = tally.get(target.getUniqueId());
        if (t == null) {
            return;
        }
        // 다음 틱에 읽는다 — 우리 무공 리스너가 피해를 고쳐 쓴 **뒤**의 값이 진실이다
        Bukkit.getScheduler().runTask(plugin, () -> {
            double dealt = event.getFinalDamage();
            t[0] += dealt;
            t[1] += 1;
            t[2] = dealt;
            target.setHealth(Math.min(2048,
                    target.getAttribute(Attribute.MAX_HEALTH).getValue()));   // 죽지 않는다
            int durability = target.getPersistentDataContainer()
                    .getOrDefault(KEY_DUMMY, PersistentDataType.INTEGER, 20);
            double avg = t[1] == 0 ? 0 : t[0] / t[1];
            int ttk = avg <= 0 ? 0 : (int) Math.ceil(durability / avg);
            target.setCustomName(ChatColor.GRAY + "허수아비 · "
                    + ChatColor.WHITE + String.format("최근 %.1f", t[2])
                    + ChatColor.GRAY + " · 누적 " + String.format("%.0f", t[0])
                    + " · " + (int) t[1] + "합 · 평균 " + String.format("%.2f", avg)
                    + ChatColor.YELLOW + " → 내구 " + durability + " 상대 TTK " + ttk + "합");
        });
    }

    /**
     * 연무장의 허수아비·몹은 세계의 장부에 오르지 않는다 (소문·명분·혈채 없음).
     *
     * <p><b>입도진(나루)도 같다</b> — 강호에 들지 않은 자가 나루의 허수아비를 백 번 베어도
     * 그것은 강호의 일이 아니다. 세 곳({@code SkillCast}·{@code SkillListener}·{@code HuntingGrounds})이
     * 이미 이 한 문장을 부르고 있으므로, <b>여기 한 줄이 세 곳을 다 막는다</b>.
     */
    static boolean suppressWorldEvents(World world) {
        return isDojang(world) || Antechamber.isAntechamber(world);
    }
}
