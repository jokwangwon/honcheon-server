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
        if (!isDojang(player.getWorld())) {
            origins.put(player.getUniqueId(), player.getLocation());   // 돌아갈 자리를 기억한다
        }
        Location at = new Location(w, 0.5, 5, 0.5, 0f, 0f);
        player.teleport(at);
        player.sendMessage(ChatColor.GOLD + "── 연무장 ──");
        player.sendMessage(ChatColor.GRAY + "여기서 벤 것은 소문이 되지 않는다. 마음껏 두들겨라.");
        player.sendMessage(ChatColor.GRAY + "/혼천 시험 경지 <삼류|이류|일류|절정|초절정|화경> · "
                + "/혼천 시험 내력 <값> · /혼천 시험 무공 <id>");
        player.sendMessage(ChatColor.GRAY + "/혼천 허수아비 [내구] · /혼천 시험 몹 <id> · /혼천 귀환");
    }

    void leave(Player player) {
        Location back = origins.remove(player.getUniqueId());
        if (back == null || back.getWorld() == null) {
            Location market = plugin.anchor("장터");
            back = market != null ? market : Bukkit.getWorlds().get(0).getSpawnLocation();
        }
        player.teleport(back);
        player.sendMessage(ChatColor.GRAY + "연무장을 나선다.");
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

    /** 연무장의 허수아비·몹은 세계의 장부에 오르지 않는다 (소문·명분·혈채 없음) */
    static boolean suppressWorldEvents(World world) {
        return isDojang(world);
    }
}
