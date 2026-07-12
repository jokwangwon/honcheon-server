package com.honcheon.mvt;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 비무(比武) — <b>죽이지 않는 싸움</b>. 무협에서 무인이 무인과 칼을 맞대는 자리는 대개 여기다.
 *
 * <p><b>왜 필요했나.</b> {@code cultivation.yml} 의 목숨_무게는 세 층을 규정한다 —
 * {@code 생사 2.0 / 실전_사냥 1.0 / 비무_대련 0.5}. 그런데 인게임엔 "때려서 죽인다" 하나뿐이었다.
 * 비무는 <b>가장 무협적이고 가장 없던 층</b>이다: 안전하지만 적게 배운다. 그래서 수련의 주된 방법이 되고,
 * 죽지 않으므로 <b>무공을 시험하는 무대</b>가 된다 (여기서 격을 두르고 초식을 걸어 본다).
 *
 * <p><b>비무의 규칙</b> (봇의 death_and_legacy 배선과 동일 — "비무는 아무리 크게 져도 중상에서 멈춘다")
 * <ul>
 *   <li><b>사망 봉인</b> — 비무 중 참가자에게 들어오는 치명타는 취소된다. 상대의 칼도, 낙사도.</li>
 *   <li><b>중상 = 패배</b> — 내구가 {@code combat.yml durability.wound_thresholds.중상.below_ratio}
 *       (25%) 아래로 떨어지는 순간 <b>피해를 취소하고</b> 그 자리에서 승패를 확정한다.</li>
 *   <li><b>항복</b> — {@code /혼천 비무 항복}. 무인은 질 줄도 알아야 한다.</li>
 *   <li><b>장외</b> — 비무 자리에서 20칸을 벗어나거나 상대와 24칸 넘게 8초를 떨어져 있으면 장외패.</li>
 *   <li><b>시간</b> — 90초. 그때까지 승부가 안 나면 남은 내구 비율로 판정한다 (동률이면 무승부).</li>
 * </ul>
 *
 * <p><b>적립</b> — 목숨_무게 {@code 비무_대련 = 0.5}. 이기면 실전 마크
 * ({@code cultivation.yml}: "실전_마크 = 비무 승리, 제압, 실전 도주 성공"). 지는 쪽도 배운다(같은 공식,
 * 마크는 없다) — 진 자가 아무것도 못 배우면 아무도 두 번째 비무를 청하지 않는다.
 */
public final class Sparring implements Listener {

    /** 비무 제한 시간 — config 등록 대기 (제안: combat.yml sparring.time_limit_seconds) */
    private static final int TIME_LIMIT_SECONDS = 90;
    /** 장외 — 비무 자리에서 이만큼 벗어나면 진다 */
    private static final double RING_RADIUS = 20;
    private static final double SEPARATION = 24;
    private static final int SEPARATION_GRACE_SECONDS = 8;
    /** 중상 — 회복까지 몸이 무겁다 (config 등록 대기: death_and_legacy 중상 회복) */
    private static final int WOUND_SECONDS = 30;
    /** 도전 수락 유효 시간 */
    private static final long CHALLENGE_TIMEOUT_MS = 30_000;

    private static final String SKILL = "비무";

    private final HoncheonMvt plugin;
    private final HuntingGrounds grounds;
    private final Map<UUID, Bout> bouts = new HashMap<>();          // 참가자 → 판
    private final Map<UUID, Challenge> challenges = new HashMap<>();  // 도전받은 자 → 도전

    Sparring(HoncheonMvt plugin, HuntingGrounds grounds) {
        this.plugin = plugin;
        this.grounds = grounds;
    }

    private record Challenge(UUID from, long expiresAtMs) {
    }

    /** 한 판 — 사람 대 사람, 또는 사람 대 무인(NPC). 무기는 진짜지만 죽음은 없다 */
    private static final class Bout {
        final UUID a;              // 도전자 (항상 플레이어)
        final UUID b;              // 상대 — 플레이어 또는 NPC 엔티티
        final boolean npcOpponent;
        final Location center;
        final long deadlineTick;
        int separatedSeconds;

        Bout(UUID a, UUID b, boolean npcOpponent, Location center, long deadlineTick) {
            this.a = a;
            this.b = b;
            this.npcOpponent = npcOpponent;
            this.center = center;
            this.deadlineTick = deadlineTick;
        }

        UUID other(UUID id) {
            return id.equals(a) ? b : a;
        }
    }

    // ══════════════ 도전과 수락 ══════════════

    /** 비무 상대(곽진) 우클릭 — 무인은 청하면 받는다 */
    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        if (!"비무상대".equals(HuntingGrounds.tag(entity, HuntingGrounds.KEY_ROLE))) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (bouts.containsKey(player.getUniqueId())) {
            return;
        }
        if (!(entity instanceof LivingEntity partner)) {
            return;
        }
        begin(player, partner.getUniqueId(), true, partner.getLocation());
        HuntingGrounds.Foe foe = HuntingGrounds.foeOf(entity);
        String name = foe == null ? "무인" : foe.name();
        player.sendMessage(ChatColor.AQUA + name + ": \"한 수 배웁시다.\" "
                + ChatColor.GRAY + "— 목검이 아니어도 죽지는 않는다. 중상에서 멈춘다.");
        if (entity instanceof Mob mob) {
            mob.setTarget(player);
        }
    }

    /** /혼천 비무 &lt;플레이어&gt; — 사람과 사람의 비무 (죽지 않는 PvP) */
    public void challenge(Player from, Player to) {
        if (bouts.containsKey(from.getUniqueId()) || bouts.containsKey(to.getUniqueId())) {
            from.sendMessage(ChatColor.GRAY + "이미 겨루고 있는 사람이다.");
            return;
        }
        challenges.put(to.getUniqueId(),
                new Challenge(from.getUniqueId(), System.currentTimeMillis() + CHALLENGE_TIMEOUT_MS));
        from.sendMessage(ChatColor.GOLD + to.getName() + "에게 비무를 청했다.");
        to.sendMessage(ChatColor.GOLD + from.getName() + "이(가) 비무를 청한다 — "
                + ChatColor.WHITE + "/혼천 비무 수락" + ChatColor.GRAY + " (30초)");
    }

    /** /혼천 비무 수락 */
    public void accept(Player player) {
        Challenge challenge = challenges.remove(player.getUniqueId());
        if (challenge == null || challenge.expiresAtMs() < System.currentTimeMillis()) {
            player.sendMessage(ChatColor.GRAY + "청해 온 비무가 없다.");
            return;
        }
        Player from = plugin.getServer().getPlayer(challenge.from());
        if (from == null) {
            player.sendMessage(ChatColor.GRAY + "상대가 자리를 떴다.");
            return;
        }
        Location center = from.getLocation().toVector().midpoint(
                player.getLocation().toVector()).toLocation(player.getWorld());
        begin(from, player.getUniqueId(), false, center);
        announce(from, player, ChatColor.GOLD + "── 비무 ──");
        announce(from, player, ChatColor.WHITE + "죽이는 싸움이 아니다. 중상이면 진다 — 항복은 /혼천 비무 항복");
    }

    private void begin(Player challenger, UUID opponent, boolean npc, Location center) {
        long deadline = plugin.getServer().getWorlds().get(0).getFullTime() + TIME_LIMIT_SECONDS * 20L;
        Bout bout = new Bout(challenger.getUniqueId(), opponent, npc, center, deadline);
        bouts.put(challenger.getUniqueId(), bout);
        bouts.put(opponent, bout);
        challenger.playSound(challenger.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1.0f, 0.7f);
    }

    /** /혼천 비무 항복 — 무인은 질 줄도 알아야 한다 */
    public void yield(Player player) {
        Bout bout = bouts.get(player.getUniqueId());
        if (bout == null) {
            player.sendMessage(ChatColor.GRAY + "겨루는 중이 아니다.");
            return;
        }
        finish(bout, bout.other(player.getUniqueId()), player.getUniqueId(), "항복");
    }

    public boolean isSparring(Entity entity) {
        return entity != null && bouts.containsKey(entity.getUniqueId());
    }

    // ══════════════ 죽지 않는 보장 ══════════════

    /**
     * 비무의 칼 — 피해는 들어가되 <b>죽음은 들어오지 않는다</b>.
     * 중상 문턱(내구 25%)을 넘길 피해는 취소되고, 그 자리에서 승패가 확정된다.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Bout bout = bouts.get(event.getEntity().getUniqueId());
        if (bout == null || !(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        UUID attacker = damagerId(event.getDamager());
        if (attacker == null || !attacker.equals(bout.other(victim.getUniqueId()))) {
            return;   // 제3자의 칼은 비무가 아니다 (진짜 피해 — 산적이 끼어들면 그건 실전이다)
        }
        double floor = floorHealth(victim);
        if (victim.getHealth() - event.getFinalDamage() <= floor) {
            event.setCancelled(true);
            victim.setHealth(Math.max(floor, 0.5));
            finish(bout, attacker, victim.getUniqueId(), "중상");
        }
    }

    /** 환경 피해로도 죽지 않는다 — 비무 중의 낙사는 비무가 아니다 (판을 깨지 말 것) */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnyDamage(EntityDamageEvent event) {
        Bout bout = bouts.get(event.getEntity().getUniqueId());
        if (bout == null || !(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        if (victim.getHealth() - event.getFinalDamage() <= 0) {
            event.setCancelled(true);
            victim.setHealth(Math.max(floorHealth(victim), 0.5));
            finish(bout, bout.other(victim.getUniqueId()), victim.getUniqueId(), "중상");
        }
    }

    private static double floorHealth(LivingEntity entity) {
        var max = entity.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = max == null ? 20 : max.getValue();
        return maxHealth * HuntingGrounds.중상비율();   // combat.yml — 중상은 config 가 정한다
    }

    private static UUID damagerId(Entity damager) {
        if (damager instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Entity shooter) {
            return shooter.getUniqueId();
        }
        return damager.getUniqueId();
    }

    // ══════════════ 판정 — 중앙 티커(HuntingGrounds)가 1초마다 부른다 ══════════════

    void tick() {
        if (bouts.isEmpty()) {
            return;
        }
        long now = plugin.getServer().getWorlds().get(0).getFullTime();
        for (Bout bout : List.copyOf(new java.util.HashSet<>(bouts.values()))) {
            Player a = plugin.getServer().getPlayer(bout.a);
            Entity b = bout.npcOpponent ? plugin.getServer().getEntity(bout.b)
                    : plugin.getServer().getPlayer(bout.b);
            if (a == null || b == null || b.isDead()) {
                abort(bout);
                continue;
            }
            // 장외 — 자리를 벗어나면 진다 (도망은 비무가 아니다)
            if (a.getWorld() != b.getWorld()) {
                finish(bout, bout.b, bout.a, "장외");
                continue;
            }
            if (bout.center.getWorld() == a.getWorld()
                    && a.getLocation().distance(bout.center) > RING_RADIUS) {
                finish(bout, bout.b, bout.a, "장외");
                continue;
            }
            if (!bout.npcOpponent && bout.center.getWorld() == b.getWorld()
                    && b.getLocation().distance(bout.center) > RING_RADIUS) {
                finish(bout, bout.a, bout.b, "장외");
                continue;
            }
            if (a.getLocation().distance(b.getLocation()) > SEPARATION) {
                if (++bout.separatedSeconds >= SEPARATION_GRACE_SECONDS) {
                    finish(bout, bout.b, bout.a, "장외");
                    continue;
                }
            } else {
                bout.separatedSeconds = 0;
            }
            // NPC 상대는 도전자를 계속 본다 (표적이 풀리면 판이 죽는다)
            if (b instanceof Mob mob && mob.getTarget() == null) {
                mob.setTarget(a);
            }
            long left = (bout.deadlineTick - now) / 20;
            if (left <= 0) {
                judgeByHealth(bout, a, b);
                continue;
            }
            actionBar(a, ChatColor.AQUA + "비무 " + ChatColor.WHITE + left + "초"
                    + ChatColor.GRAY + " — 중상이면 끝난다 (항복: /혼천 비무 항복)");
        }
    }

    /** 시간 초과 — 남은 내구 비율로 판정한다 (판정승도 승리다) */
    private void judgeByHealth(Bout bout, Player a, Entity b) {
        double ratioA = ratio(a);
        double ratioB = b instanceof LivingEntity le ? ratio(le) : 1.0;
        if (Math.abs(ratioA - ratioB) < 0.05) {
            announceAll(bout, ChatColor.GRAY + "비무 시간이 다했다 — 무승부. 서로 포권한다.");
            grant(a, b, "무승부");
            clear(bout);
            return;
        }
        boolean aWins = ratioA > ratioB;
        finish(bout, aWins ? bout.a : bout.b, aWins ? bout.b : bout.a, "판정");
    }

    private static double ratio(LivingEntity entity) {
        var max = entity.getAttribute(Attribute.MAX_HEALTH);
        return entity.getHealth() / (max == null ? 20 : max.getValue());
    }

    // ══════════════ 종결 — 승패·중상·적립 ══════════════

    private void finish(Bout bout, UUID winnerId, UUID loserId, String how) {
        Entity winner = resolve(winnerId, bout);
        Entity loser = resolve(loserId, bout);
        String winnerName = displayName(winner);
        String loserName = displayName(loser);

        announceAll(bout, ChatColor.GOLD + "── 비무 종료 (" + how + ") ── "
                + ChatColor.WHITE + winnerName + ChatColor.GRAY + " 승 · "
                + ChatColor.WHITE + loserName + ChatColor.GRAY + " 패");

        if (loser instanceof LivingEntity wounded) {
            wound(wounded);
        }
        if (winner instanceof Player w) {
            plugin.ledger(w.getUniqueId()).mark실전();   // cultivation.yml: 실전_마크 = 비무 승리
            w.playSound(w.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
        }
        grant(winner, loser, how);
        grant(loser, winner, how);   // 지는 쪽도 배운다 (마크는 없다)
        clear(bout);
    }

    /** 중상 — 죽지는 않았으나 몸이 성치 않다 (판을 끝낸 뒤에도 값은 남는다) */
    private void wound(LivingEntity loser) {
        loser.setHealth(Math.max(floorHealth(loser), 0.5));
        loser.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, WOUND_SECONDS * 20, 0, true, true));
        loser.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, WOUND_SECONDS * 20, 0, true, true));
        if (loser instanceof Player player) {
            player.sendMessage(ChatColor.RED + "중상 — 숨이 가쁘다. 한동안 몸이 말을 듣지 않는다.");
        }
    }

    /**
     * 화후 적립 — 목숨_무게 <b>비무_대련 0.5</b>. 실전보다 적게 배운다. 그러나 안전하다.
     * 격차 배율(상대_격차)은 상대의 경지가 정한다 — 고수에게 배우는 것이 가장 크다.
     */
    private void grant(Entity learner, Entity opponent, String how) {
        if (!(learner instanceof Player player)) {
            return;   // NPC 의 성장은 npc_lifecycle 의 몫
        }
        String theirRealm = opponent instanceof Player other
                ? HuntingGrounds.playerRealm(plugin, other)
                : realmOf(opponent);
        String gap = HuntingGrounds.gapBetween(HuntingGrounds.playerRealm(plugin, player), theirRealm);

        PlayerLedger ledger = plugin.ledger(player.getUniqueId());
        ledger.rollDay(player.getWorld().getFullTime() / 24000L);
        String key = "비무:" + (opponent == null ? "?" : displayName(opponent));
        double accrual = plugin.progression().combatAccrualDays(gap, "비무_대련", ledger.repetitionOf(key));
        double granted = plugin.progression().cappedGrant(ledger.grantedToday(), accrual);
        ledger.countRepetition(key);
        if (granted <= 0) {
            actionBar(player, ChatColor.GRAY + (accrual <= 0
                    ? "배울 것이 없는 상대였다" : "오늘은 몸이 벅차다"));
            return;
        }
        int before = ledger.levelOf(SKILL, plugin.progression());
        ledger.grant(SKILL, granted);
        int after = ledger.levelOf(SKILL, plugin.progression());
        actionBar(player, ChatColor.AQUA + String.format("겨루며 배운다 (+%.2f일 · 비무 ×0.5)", granted));
        if (after > before) {
            player.sendTitle(ChatColor.GOLD + "손속에 요령이 붙는다",
                    ChatColor.YELLOW + "비무 숙련 " + before + " → " + after, 10, 40, 20);
        }
    }

    private static String realmOf(Entity entity) {
        HuntingGrounds.Foe foe = HuntingGrounds.foeOf(entity);
        return foe == null ? "삼류" : foe.realm();
    }

    /** NPC 상대는 판이 끝나면 자리로 돌아가 몸을 추스른다 (죽지 않는다 — 다시 청할 수 있어야 한다) */
    private void clear(Bout bout) {
        bouts.remove(bout.a);
        bouts.remove(bout.b);
        Entity npc = bout.npcOpponent ? plugin.getServer().getEntity(bout.b) : null;
        if (npc instanceof LivingEntity partner) {
            var max = partner.getAttribute(Attribute.MAX_HEALTH);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!partner.isDead()) {
                    partner.setHealth(max == null ? 20 : max.getValue());   // 다음 사람을 기다린다
                }
            }, WOUND_SECONDS * 20L);
        }
        if (npc instanceof Mob mob) {
            mob.setTarget(null);
        }
    }

    private void abort(Bout bout) {
        bouts.remove(bout.a);
        bouts.remove(bout.b);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Bout bout = bouts.get(event.getPlayer().getUniqueId());
        if (bout != null) {
            finish(bout, bout.other(event.getPlayer().getUniqueId()), event.getPlayer().getUniqueId(), "기권");
        }
        challenges.remove(event.getPlayer().getUniqueId());
    }

    /** 비무 상대가 (다른 손에) 죽으면 판은 없던 일이 된다 */
    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        Bout bout = bouts.get(event.getEntity().getUniqueId());
        if (bout != null) {
            abort(bout);
        }
    }

    // ══════════════ 잔심부름 ══════════════

    private Entity resolve(UUID id, Bout bout) {
        Player player = plugin.getServer().getPlayer(id);
        return player != null ? player : plugin.getServer().getEntity(id);
    }

    private static String displayName(Entity entity) {
        if (entity == null) {
            return "?";
        }
        if (entity instanceof Player player) {
            return player.getName();
        }
        HuntingGrounds.Foe foe = HuntingGrounds.foeOf(entity);
        return foe != null ? foe.name()
                : ChatColor.stripColor(entity.getCustomName() == null
                        ? entity.getType().name() : entity.getCustomName());
    }

    private void announceAll(Bout bout, String message) {
        List<Player> players = new ArrayList<>();
        Player a = plugin.getServer().getPlayer(bout.a);
        if (a != null) {
            players.add(a);
        }
        if (!bout.npcOpponent) {
            Player b = plugin.getServer().getPlayer(bout.b);
            if (b != null) {
                players.add(b);
            }
        }
        players.forEach(p -> p.sendMessage(message));
    }

    private void announce(Player a, Player b, String message) {
        a.sendMessage(message);
        b.sendMessage(message);
    }

    @SuppressWarnings("deprecation")
    private static void actionBar(Player player, String message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
    }
}
