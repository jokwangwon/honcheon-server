package com.honcheon.mvt;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 경공의 <b>몸</b> — {@link Gyeonggong}(등록부·산술)을 마인크래프트의 살에 붙인다.
 *
 * <p><b>여기 있는 것은 전부 실제로 굴러가는 것뿐이다</b> (안 되는 것을 등록하면 그것이 거짓말이다):
 * <ul>
 *   <li><b>답설무흔</b> — 달리며 점프하면 몸이 가벼워진다. 걷기 속도·도약이 오르고 <b>발밑에 자국이 남는다</b></li>
 *   <li><b>벽 딛기</b> — 벽에 붙어 차고 오른다. <b>경지 천장 ∧ 수직 보법(제운종·답운종)</b> 둘 다 있어야 한다</li>
 *   <li><b>수상비</b> — 물 위를 <b>달린다</b> (초절정+). 서면 빠진다</li>
 *   <li><b>낙법</b> — 경지가 높이를 산다. ★ <b>내력이 끊긴 몸은 돌처럼 떨어진다</b></li>
 *   <li><b>물러남·파고듦</b> — 회피가 성공하면 몸이 실제로 물러나고, 선공을 잡으면 먼저 붙는다
 *       ({@link #retreat} · {@link #closeIn} — 전투 쪽 배선의 이음매)</li>
 * </ul>
 *
 * <p><b>쿨다운은 하나도 없다.</b> 이 몸을 막는 것은 시계가 아니라 <b>내력·경지·갑옷</b>이다.
 *
 * <p>중앙 티커 1개 ({@code performance.yml effects.central_ticker}) — 그리고 스스로를 잰다
 * ({@code Metrics.wrap("gyeonggong", …)} → {@code performance.yml metrics.probes.gyeonggong}).
 * <b>티커를 만들었으면 재는 자도 만든다.</b>
 */
public final class GyeonggongListener implements Listener {

    /** 한 사람의 경공 — 켜져 있는 동안만 산다 (꺼지면 지운다: 지도에 죽은 몸을 남기지 않는다) */
    private static final class Ride {
        Gyeonggong.Profile profile;
        long lastUpkeep;
        long lastFootprint;
        long lastKick;
        long refreshAt;
        int idleTicks;
        double climbed;        // 이번 체공에 벽으로 오른 높이 (땅에 닿으면 0)
        float savedWalk;
    }

    private final HoncheonMvt plugin;
    private final Map<UUID, Ride> riding = new HashMap<>();
    /** 고갈의 여운 — 이 틱까지는 다시 못 뜬다 (몸이 무겁다) */
    private final Map<UUID, Long> depletedUntil = new HashMap<>();
    private long tick;

    public GyeonggongListener(HoncheonMvt plugin) {
        this.plugin = plugin;
    }

    /** 중앙 티커 1개 — 켜져 있는 사람만 돈다 (아무도 안 날면 0원) */
    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin,
                Metrics.wrap("gyeonggong", this::tick), 1L, 1L);
    }

    /** 서버가 내려갈 때 몸을 되돌린다 — 경공을 켠 채 로그아웃한 자가 영원히 빨라지면 안 된다 */
    public void shutdown() {
        plugin.getServer().getOnlinePlayers().forEach(p -> stop(p, null));
    }

    // ══════════════════════════════════════════════════════════════════
    //  티커 — 유지비 · 벽 · 물 · 자국
    // ══════════════════════════════════════════════════════════════════

    private void tick() {
        // ★ 시계는 **아무도 안 날 때도** 간다. 여기서 일찍 빠지면 고갈의 여운(depletedUntil)이
        //   영원히 안 끝난다 — 고갈 직후엔 riding 이 비어 있으니까. 한 번 떨어진 자가 다시 못 뜬다.
        tick++;
        Gyeonggong gg = Gyeonggong.get();
        if (gg == null || riding.isEmpty()) {
            return;
        }
        for (UUID id : riding.keySet().toArray(new UUID[0])) {
            Player player = plugin.getServer().getPlayer(id);
            Ride ride = riding.get(id);
            if (player == null || !player.isOnline() || ride == null) {
                riding.remove(id);
                continue;
            }
            step(gg, player, ride);
        }
    }

    private void step(Gyeonggong gg, Player player, Ride ride) {
        SkillEngine.State state = plugin.skills().state(player);

        if (tick >= ride.refreshAt) {                 // 경지·수련·갑옷이 바뀌었을 수 있다 (1초마다 다시 읽는다)
            ride.profile = profileOf(gg, player, state);
            ride.refreshAt = tick + gg.upkeepInterval();
            if (!ride.profile.open()) {
                stop(player, ride.profile.blockedBy());
                return;
            }
            applyWalk(gg, player, ride.profile);
        }
        Gyeonggong.Profile p = ride.profile;

        Vector vel = player.getVelocity();
        double horizontal = Math.hypot(vel.getX(), vel.getZ());
        boolean onWater = onWaterSurface(player) && p.waterRun();

        // ─── 유지비 — 달리는 동안 단전이 돈다. 물 위는 두 배다 (물은 딛을 것이 없다) ───
        if (tick - ride.lastUpkeep >= p.upkeepInterval()) {
            int cost = gg.upkeep(onWater);
            if (state.energy < cost) {
                deplete(gg, player, state);           // ★ 여기가 그 자리다 — 내력이 끊기면 떨어진다
                return;
            }
            state.energy -= cost;
            ride.lastUpkeep = tick;
        }

        // ─── 멈춰 서면 기가 흩어진다 (경공은 '달리는 몸'이다) ───
        if (horizontal < 0.05 && player.isOnGround()) {
            if (++ride.idleTicks > gg.idleOffTicks()) {
                stop(player, "off");
                return;
            }
        } else {
            ride.idleTicks = 0;
        }

        if (player.isOnGround()) {
            ride.climbed = 0;                          // 땅을 밟으면 체공이 끝난다
        }

        // ─── 수상비 — 물 위를 **달린다**. 서면 빠진다 (water.min_speed) ───
        if (onWater && horizontal >= gg.water("min_speed", 0.10)) {
            player.setVelocity(new Vector(vel.getX(), gg.water("lift", 0.10), vel.getZ()));
            player.setFallDistance(0);
            if (tick % 2 == 0) {
                spray(gg, player, "water_step");
            }
        }

        // ─── 벽 딛기 — 경지 천장 ∧ 수직 보법. 둘 중 하나라도 없으면 벽은 벽이다 ───
        if (p.canWallClimb() && !player.isOnGround() && vel.getY() < 0.1
                && tick - ride.lastKick >= gg.wall("min_interval_ticks", 6)
                && ride.climbed + gg.wall("kick_gain_m", 1.25) <= p.wallClimb()
                && facingWall(gg, player)) {
            int cost = gg.wallKickCost();
            if (state.energy < cost) {
                deplete(gg, player, state);            // 벽을 차려다 기가 끊긴다 — 그대로 떨어진다
                return;
            }
            state.energy -= cost;
            ride.lastKick = tick;
            ride.climbed += gg.wall("kick_gain_m", 1.25);
            Vector into = player.getLocation().getDirection().setY(0).normalize()
                    .multiply(gg.wall("hold_horizontal", 0.12));
            player.setVelocity(new Vector(into.getX(), gg.wall("kick_velocity", 0.42), into.getZ()));
            player.setFallDistance(0);
            spray(gg, player, "wall_kick");
        }

        // ─── 답설무흔 — 발밑에 자국이 남는다 (팩이 없어도 보인다) ───
        Map<String, Object> foot = gg.vfx("footprint");
        int interval = (int) num(foot.get("interval_ticks"), 2);
        if (player.isOnGround() && horizontal > 0.05 && tick - ride.lastFootprint >= interval) {
            ride.lastFootprint = tick;
            footprint(gg, player, foot);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  켜짐 — 달리며 점프 (mc_action_mapping.md:24)
    // ══════════════════════════════════════════════════════════════════

    @EventHandler(ignoreCancelled = true)
    public void onJump(PlayerJumpEvent event) {
        Gyeonggong gg = Gyeonggong.get();
        if (gg == null || !event.getPlayer().isSprinting()) {
            return;   // 달리며 점프 — 걷다가 뛰는 것은 그냥 점프다
        }
        Player player = event.getPlayer();
        if (depletedUntil.getOrDefault(player.getUniqueId(), -1L) > tick) {
            SkillHud.actionBar(player, ChatColor.DARK_GRAY + gg.message("depleted"));
            return;   // 고갈의 여운 — 몸이 무겁다
        }
        SkillEngine.State state = plugin.skills().state(player);
        Gyeonggong.Profile p = profileOf(gg, player, state);
        if (!p.open()) {
            Ride existing = riding.remove(player.getUniqueId());
            if (existing != null) {
                restore(player, existing);
            }
            SkillHud.actionBar(player, ChatColor.DARK_GRAY + gg.message(p.blockedBy()));
            return;   // 개화 전 · 철갑 — 이 몸은 땅을 딛을 뿐이다
        }

        int cost = gg.leapCost();
        if (state.energy < cost) {
            deplete(gg, player, state);
            return;
        }
        state.energy -= cost;

        boolean fresh = !riding.containsKey(player.getUniqueId());
        Ride ride = riding.computeIfAbsent(player.getUniqueId(), id -> new Ride());
        ride.profile = p;
        ride.refreshAt = tick + gg.upkeepInterval();
        ride.lastUpkeep = tick;
        ride.idleTicks = 0;
        if (fresh) {
            ride.savedWalk = player.getWalkSpeed();
            applyWalk(gg, player, p);
            SkillHud.actionBar(player, ChatColor.AQUA + gg.message("on"));
        }

        // 도약 — 다음 틱에 실어야 바닐라 점프 속도를 덮어쓴다
        double y = gg.baseJumpVelocity() * (1 + p.jump());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && riding.containsKey(player.getUniqueId())) {
                Vector v = player.getVelocity();
                player.setVelocity(new Vector(v.getX(), y, v.getZ()));
            }
        });
        spray(gg, player, "leap");
    }

    /** 웅크리면 몸을 내린다 — 켜는 것은 달리며 점프, 끄는 것은 웅크림 (Shift 탭은 다른 담당이 쓴다: 누르는 순간만 본다) */
    @EventHandler(ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking() && riding.containsKey(event.getPlayer().getUniqueId())
                && event.getPlayer().isOnGround()) {
            stop(event.getPlayer(), "off");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  낙법 — 경지가 높이를 산다. 그리고 기가 끊긴 몸은 돌처럼 떨어진다
    // ══════════════════════════════════════════════════════════════════

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onFall(EntityDamageEvent event) {
        Gyeonggong gg = Gyeonggong.get();
        if (gg == null || event.getCause() != EntityDamageEvent.DamageCause.FALL
                || !(event.getEntity() instanceof Player player)) {
            return;
        }
        SkillEngine.State state = plugin.skills().state(player);
        Gyeonggong.Profile p = profileOf(gg, player, state);
        double fell = player.getFallDistance();

        // ★ 낙법은 **경공을 켜지 않아도** 듣는다 (몸이 아는 것이다). 다만 **기가 도는 몸에만** 듣는다.
        double grace = gg.fallGrace(p, state.energy);
        double over = fell - grace - gg.vanillaSafeFall();

        if (over <= 0) {
            event.setCancelled(true);
            player.setFallDistance(0);
            spray(gg, player, "fall_soft");
            SkillHud.actionBar(player, ChatColor.AQUA + gg.message("fall_soft"));
            return;
        }
        if (!p.open() || state.energy <= 0) {
            // ★★ 이 게임의 극적인 자리 — 내력이 없으면 낙법이 없다 (grace × 0)
            if (p.open()) {
                SkillHud.actionBar(player, ChatColor.RED + gg.message("fall_hard"));
            }
            return;   // 바닐라 피해 그대로 (경지도 신법도 여기서는 아무것도 못 산다)
        }

        // 남은 높이를 내력으로 산다 — 내력 1 = meters_per_qi m. 있는 만큼만 태운다
        int need = gg.softenCost(over);
        int paid = Math.min(need, state.energy);
        state.energy -= paid;
        double bought = paid * gg.metersPerQi();
        double left = Math.max(0, over - bought);

        if (left <= 0) {
            event.setCancelled(true);
            player.setFallDistance(0);
            spray(gg, player, "fall_soft");
            SkillHud.actionBar(player, ChatColor.AQUA + gg.message("fall_soft")
                    + ChatColor.DARK_AQUA + " (내력 −" + paid + ")");
            return;
        }
        event.setDamage(left);   // 못 산 높이만 아프다 — 내력이 모자라면 그만큼 몸이 먹는다
        SkillHud.actionBar(player, ChatColor.RED + gg.message("fall_hard")
                + ChatColor.DARK_GRAY + " (내력 −" + paid + ")");
    }

    // ══════════════════════════════════════════════════════════════════
    //  전투와의 이음 — 회피가 성공하면 몸이 **실제로 물러나야** 한다
    // ══════════════════════════════════════════════════════════════════

    /**
     * <b>이음매.</b> 누군가 회피를 배선해 피해를 취소하는 순간 ({@code event.setCancelled(true)}),
     * 이 핸들러가 그 몸을 <b>실제로 뒤로 뺀다</b>. 회피가 없으면 아무 일도 일어나지 않는다 —
     * 즉 이 코드는 <b>지금은 조용하고, 회피가 서는 날 저절로 산다.</b>
     *
     * <p>{@code combat.yml defender_choice.회피.note}: <i>"몸을 빼는 것에는 격이 없다."</i>
     * 격은 없다. 그러나 <b>경신은 든다</b> — 발에 기를 싣지 않으면 몸은 그 자리에 선 채로 안 맞는 척만 한다.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDodged(EntityDamageByEntityEvent event) {
        if (!event.isCancelled() || !(event.getEntity() instanceof Player player)) {
            return;
        }
        retreat(plugin, player, event.getDamager().getLocation());
    }

    /**
     * <b>몸을 뺀다</b> — 회피 성공의 물리적 형태. 보법이 거리를 정한다
     * (매화보 5 · 제운종 8 · 취팔선보 4 · 답운종 10 — {@code skill_mechanics.yml distance}).
     * 취팔선보는 <b>비틀거린다</b> (±30° 무작위 편향 — 취보의 값이자 대가).
     *
     * @return 물러났는가 (내력이 없으면 false — <b>기 없는 몸은 못 빠진다</b>)
     */
    public static boolean retreat(HoncheonMvt plugin, Player player, Location from) {
        return step(plugin, player, from, true, "retreat");
    }

    /**
     * <b>먼저 붙는다</b> — 선공(initiative)의 물리적 형태
     * ({@code combat.yml initiative.frontal} — 민첩+감각을 이긴 자가 먼저 움직인다).
     */
    public static boolean closeIn(HoncheonMvt plugin, Player player, LivingEntity target) {
        return step(plugin, player, target.getLocation(), false, "retreat");
    }

    private static boolean step(HoncheonMvt plugin, Player player, Location anchor,
                                boolean away, String vfxKey) {
        Gyeonggong gg = Gyeonggong.get();
        if (gg == null || anchor == null || !anchor.getWorld().equals(player.getWorld())) {
            return false;
        }
        SkillEngine.State state = plugin.skills().state(player);
        Gyeonggong.Profile p = profileOf(gg, plugin, player, state);
        int cost = away ? gg.retreatCost() : gg.closeInCost();
        if (!p.open() || state.energy < cost) {
            return false;   // 개화 전·철갑·고갈 — 몸은 그 자리에 선다
        }
        state.energy -= cost;

        Vector dir = player.getLocation().toVector().subtract(anchor.toVector()).setY(0);
        if (dir.lengthSquared() < 1.0e-4) {
            dir = player.getLocation().getDirection().setY(0);
        }
        dir = dir.normalize();
        if (!away) {
            dir = dir.multiply(-1);   // 파고든다
        }
        if (p.dodgeBiasDeg() > 0) {   // 취팔선보 — 어디로 빠질지는 취한 발이 정한다
            double rad = Math.toRadians((Math.random() * 2 - 1) * p.dodgeBiasDeg());
            double x = dir.getX() * Math.cos(rad) - dir.getZ() * Math.sin(rad);
            double z = dir.getX() * Math.sin(rad) + dir.getZ() * Math.cos(rad);
            dir = new Vector(x, 0, z).normalize();
        }
        double speed = Math.min(gg.dash("max_velocity", 1.2),
                p.dash() * gg.dash("velocity_per_meter", 0.09));
        player.setVelocity(dir.multiply(speed).setY(gg.dash("upward", 0.18)));
        player.setFallDistance(0);
        spray(gg, player, vfxKey);
        return true;
    }

    // ══════════════════════════════════════════════════════════════════
    //  꺼짐 — 그리고 고갈
    // ══════════════════════════════════════════════════════════════════

    /** ★ 내력이 끊긴다 — 보이고, 들리고, 그리고 <b>떨어진다</b> (낙법이 안 듣는다) */
    private void deplete(Gyeonggong gg, Player player, SkillEngine.State state) {
        depletedUntil.put(player.getUniqueId(), tick + gg.depletedWindow());
        state.energy = 0;
        spray(gg, player, "depleted");
        SkillHud.actionBar(player, ChatColor.RED + gg.message("depleted"));
        stop(player, null);
    }

    private void stop(Player player, String reason) {
        Ride ride = riding.remove(player.getUniqueId());
        if (ride == null) {
            return;
        }
        restore(player, ride);
        Gyeonggong gg = Gyeonggong.get();
        if (gg != null && "off".equals(reason)) {
            SkillHud.actionBar(player, ChatColor.DARK_GRAY + gg.message("off"));
        } else if (gg != null && reason != null) {
            SkillHud.actionBar(player, ChatColor.DARK_GRAY + gg.message(reason));
        }
    }

    private void restore(Player player, Ride ride) {
        player.setWalkSpeed(ride.savedWalk > 0 ? ride.savedWalk : 0.2f);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stop(event.getPlayer(), null);
        depletedUntil.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        stop(event.getPlayer(), null);
    }

    // ══════════════════════════════════════════════════════════════════
    //  몸 읽기 — 경지 · 민첩 · 심법 · 보법 · 갑옷
    // ══════════════════════════════════════════════════════════════════

    /** 이 몸을 통째로 읽는다 — 경지(천장) · 민첩(성장) · 심법(연비) · 보법(발) · 갑옷(무게) */
    private static Gyeonggong.Profile profileOf(Gyeonggong gg, HoncheonMvt plugin,
                                                Player player, SkillEngine.State state) {
        PlayerLedger ledger = plugin.ledger(player.getUniqueId());
        Gyeonggong.Bobeop bobeop = gg.bobeopOf(ledger);
        return gg.profile(state.realm, ledger.attr("민첩"), ledger.simbeop(), bobeop,
                gg.masteryOf(ledger, bobeop, plugin.progression()),
                gg.armorOf(chestplate(player)));
    }

    private Gyeonggong.Profile profileOf(Gyeonggong gg, Player player, SkillEngine.State state) {
        return profileOf(gg, plugin, player, state);
    }

    private static String chestplate(Player player) {
        ItemStack chest = player.getInventory().getChestplate();
        return chest == null ? null : chest.getType().name();
    }

    private void applyWalk(Gyeonggong gg, Player player, Gyeonggong.Profile p) {
        double v = gg.baseWalkSpeed() * (1 + p.speed());
        player.setWalkSpeed((float) Math.min(gg.walkSpeedCap(), v));
    }

    private boolean onWaterSurface(Player player) {
        Block feet = player.getLocation().getBlock();
        return feet.getType() == Material.WATER
                || feet.getRelative(0, -1, 0).getType() == Material.WATER;
    }

    private boolean facingWall(Gyeonggong gg, Player player) {
        Vector dir = player.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1.0e-4) {
            return false;
        }
        Location probe = player.getLocation().add(dir.normalize().multiply(gg.wall("reach", 0.55)));
        return probe.getBlock().getType().isSolid()
                || probe.add(0, 1, 0).getBlock().getType().isSolid();
    }

    // ══════════════════════════════════════════════════════════════════
    //  파티클 — 팩이 없어도 경공은 보인다 (팩 게이트 불가침)
    // ══════════════════════════════════════════════════════════════════

    /** 답설무흔 — 밟은 것이 무엇인지에 따라 자국이 다르다 (눈에는 눈이 인다) */
    private void footprint(Gyeonggong gg, Player player, Map<String, Object> foot) {
        Location at = player.getLocation();
        Material under = at.clone().add(0, -0.2, 0).getBlock().getType();
        String name = String.valueOf(foot.getOrDefault("particle", "CLOUD"));
        if (under == Material.SNOW || under == Material.SNOW_BLOCK || under == Material.POWDER_SNOW) {
            name = String.valueOf(foot.getOrDefault("on_snow", name));
        } else if (under == Material.WATER) {
            name = String.valueOf(foot.getOrDefault("on_water", name));
        }
        draw(player, name, at, (int) num(foot.get("count"), 1), 0.08);
    }

    private static void spray(Gyeonggong gg, Player player, String key) {
        Map<String, Object> v = gg.vfx(key);
        if (v.isEmpty()) {
            return;
        }
        draw(player, String.valueOf(v.getOrDefault("particle", "CLOUD")),
                player.getLocation(), (int) num(v.get("count"), 4), 0.2);
        Object sound = v.get("sound");
        if (sound != null) {
            player.getWorld().playSound(player.getLocation(), String.valueOf(sound),
                    (float) num(v.get("volume"), 0.5), (float) num(v.get("pitch"), 1.0));
        }
    }

    /** LOD — {@code performance.yml particles.lod.cull_beyond} 밖에서는 그리지 않는다 (예산이 정본) */
    private static void draw(Player player, String particle, Location at, int count, double spread) {
        Particle p;
        try {
            p = Particle.valueOf(particle);
        } catch (IllegalArgumentException e) {
            p = Particle.CLOUD;   // 등록부의 오타가 세계를 멈추지 않는다 (연출은 판정을 막지 못한다)
        }
        Gyeonggong gg = Gyeonggong.get();
        double cull = gg == null ? 32 : gg.cullBeyond();
        for (Player viewer : at.getWorld().getPlayers()) {
            if (viewer.getLocation().distanceSquared(at) <= cull * cull) {
                viewer.spawnParticle(p, at.getX(), at.getY() + 0.1, at.getZ(), count,
                        spread, 0.02, spread, 0.0);
            }
        }
    }

    private static double num(Object v, double fallback) {
        return v instanceof Number n ? n.doubleValue() : fallback;
    }
}
