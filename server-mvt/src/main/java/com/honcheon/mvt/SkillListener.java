package com.honcheon.mvt;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 무공의 모션 — 조작을 실제 시전으로 바꾸는 층. 규칙은 SkillEngine, 연출은 여기.
 *
 * <p>조작 매핑 (docs/design/mc_action_mapping.md 1·3-B장 + skill_motion.md):
 * <table>
 *   <tr><td>좌클릭 (검·도)</td><td>기본 무공 콤보 — 육합검 1·2타(외공기 0) → 3타(발경 1)</td></tr>
 *   <tr><td>Shift + 우클릭</td><td>격 태세 순환 — 외공 → 발경 → 검기 → 강기 → 외공 (경지 게이트 통과분만)</td></tr>
 *   <tr><td>Shift + 좌클릭 (검기+ 태세)</td><td>기 발출(쏨) — 검기 참격 3 / 강기 포 6</td></tr>
 * </table>
 *
 * <p>불변식:
 * <ul>
 *   <li>수치는 전부 config — 이 파일에는 파티클 개수와 사운드 이름만 있다 (연출은 코드의 몫)</li>
 *   <li>중앙 티커 1개 (performance.yml F-P2) — 예약 타격·텔레그래프·유지비·HUD 가 한 태스크를 공유한다</li>
 *   <li>파티클은 SkillHud.emit() 을 통해서만 — 예산 초과 시 연출만 강등, 판정은 불변</li>
 * </ul>
 */
public final class SkillListener implements Listener {

    /** 손에 든 것 → 그 손에 실리는 무공. 액션 데이터(skill_mechanics.yml)가 없으면 바닐라로 흘려보낸다 */
    private static final Map<String, String> SKILL_BY_WEAPON_CLASS = Map.of(
            "검", "yukhap_geom",
            "도", "yukhap_geom",
            "맨손", "taejo_jangkwon");   // skill_mechanics.yml 에 combo 가 생기는 날 자동 점등

    private static final String CD_SHOT = "발출";

    private final HoncheonMvt plugin;
    private final SkillEngine engine;
    private final SkillHud hud;
    private final Map<UUID, SkillEngine.State> states = new HashMap<>();
    private final List<Pending> pending = new ArrayList<>();

    private long tick;
    /** 자기 피해 재진입 가드 — 엔진이 준 피해를 넣을 때 이 리스너가 다시 잡지 않도록 */
    private boolean applying;

    private record Pending(long due, Runnable action) {
    }

    public SkillListener(HoncheonMvt plugin, SkillEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
        this.hud = new SkillHud(engine);
    }

    /** 중앙 티커 기동 — HoncheonMvt.onEnable 에서 1회 (효과별 개별 태스크 생성 금지, F-P2) */
    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public SkillEngine.State state(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), id -> new SkillEngine.State());
    }

    // ══════════ 중앙 티커 ══════════

    private void tick() {
        tick++;
        hud.newTick();

        if (!pending.isEmpty()) {
            List<Pending> due = new ArrayList<>();
            pending.removeIf(p -> {
                if (p.due() <= tick) {
                    due.add(p);
                    return true;
                }
                return false;
            });
            for (Pending p : due) {
                try {
                    p.action().run();
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("무공 예약 처리 실패: " + e.getMessage());
                }
            }
        }

        if (tick % 4 != 0) {
            return;   // HUD·유지비는 4틱(0.2초)마다 — 액션바 갱신 비용 절감
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            SkillEngine.State state = states.get(player.getUniqueId());
            if (state == null) {
                continue;
            }
            sustain(player, state);
            hud.energyBar(player, state);
            if (state.armed != null || engine.pool(state.naegong) > 0) {
                hud.statusBar(player, state, tick);
            }
        }
    }

    /** 두름 유지비 — 라운드마다 과금. 못 내면 기가 흩어진다 (상대가 읽을 수 있는 고갈 신호) */
    private void sustain(Player player, SkillEngine.State state) {
        if (state.armed == null) {
            return;
        }
        int cost = engine.sustainCost(state.armed);
        if (cost <= 0) {
            return;   // 발경은 유지비가 없다 — 타격 순간에만 실린다
        }
        if (tick < state.nextSustainTick) {
            // 응집은 빛으로 보인다 — 두름 유지 중 저강도 잔광 (텔레그래프, npc_combat 대칭 원칙)
            hud.emit(handLocation(player), qiParticle(state.armed), 2, 0.08, 0.0);
            return;
        }
        if (state.energy < cost) {
            dispel(player, state, "기가 흩어진다 — 내력이 다했다");
            return;
        }
        state.energy -= cost;
        state.nextSustainTick = tick + engine.roundTicks();
        hud.emit(handLocation(player), qiParticle(state.armed), 4, 0.1, 0.0);
    }

    // ══════════ 입력 → 시전 ══════════

    /** 좌클릭 = 공격. 대상을 맞힌 순간 바닐라 피해를 취소하고 무공 판정으로 대체한다 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMelee(EntityDamageByEntityEvent event) {
        if (applying || !(event.getDamager() instanceof Player player)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        String skillId = skillInHand(player);
        if (skillId == null) {
            return;   // 무공이 실리지 않는 손 — 바닐라 그대로
        }
        event.setCancelled(true);
        swing(player, skillId, target);
    }

    /** 허공 좌클릭 = 헛손질(콤보는 진행된다) / Shift+좌클릭 = 발출 / Shift+우클릭 = 격 태세 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != null && event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        Action action = event.getAction();

        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            if (!player.isSneaking()) {
                return;   // 평범한 우클릭 — 상호작용은 세계의 몫 (가드·패링은 후속 배선)
            }
            event.setCancelled(true);
            cycleArmed(player);
            return;
        }
        if (action != Action.LEFT_CLICK_AIR) {
            return;   // LEFT_CLICK_BLOCK 은 건드리지 않는다 — 채굴을 무공이 잡아먹으면 안 된다
        }
        SkillEngine.State state = state(player);
        if (player.isSneaking() && state.armed != null && engine.gradeRank(state.armed) >= 2) {
            shoot(player, state);
            return;
        }
        String skillId = skillInHand(player);
        if (skillId != null) {
            swing(player, skillId, null);
        }
    }

    // ─── 콤보 ───

    private void swing(Player player, String skillId, LivingEntity primary) {
        SkillEngine.State state = state(player);
        if (tick - state.lastCastTick < engine.duplicateWindowTicks()) {
            return;   // F-R1 — 같은 틱 중복 시전 폐기
        }
        if (tick < state.busyUntil) {
            SkillHud.actionBar(player, ChatColor.DARK_GRAY + "아직 자세가 돌아오지 않았다");
            return;   // 경직·후딜 — 연타 방지
        }
        if (tick > state.comboDeadline) {
            state.comboIndex = 0;   // 입력 유예창을 놓쳤다 — 처음부터
        }

        String weaponClass = engine.weaponClassOf(materialName(player));
        SkillEngine.Cast cast = engine.planCombo(
                skillId, state.comboIndex, state.realm, state.energy, state.armed, weaponClass);

        int shown = state.comboIndex + 1;
        int size = engine.comboSize(skillId);
        state.comboIndex = (state.comboIndex + 1) % size;
        state.comboDeadline = tick + cast.frames().total() + engine.comboWindow(skillId);
        state.busyUntil = tick + cast.frames().total();
        state.lastCastTick = tick;
        state.energy -= cast.paid();

        commit(player, state, cast, primary, shown + "타");
    }

    // ─── 발출 (쏨) ───

    private void shoot(Player player, SkillEngine.State state) {
        if (tick < state.busyUntil) {
            SkillHud.actionBar(player, ChatColor.DARK_GRAY + "아직 자세가 돌아오지 않았다");
            return;
        }
        if (state.onCooldown(CD_SHOT, tick)) {
            return;
        }
        String weaponClass = engine.weaponClassOf(materialName(player));
        SkillEngine.Cast cast = engine.planShot(state.armed, state.realm, state.energy, weaponClass);
        if (cast.downcast() || engine.gradeRank(cast.grade()) < 2) {
            // 발출만은 다운캐스트가 없다 — 프레임이 아니라 기 그 자체가 본체다 (skill_motion.md 4장)
            SkillHud.actionBar(player, ChatColor.RED + "기가 흩어진다 — 쏠 것이 없다");
            hud.emit(handLocation(player), Particle.SMOKE, 4, 0.1, 0.01);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_NODAMAGE, 0.6f, 0.7f);
            state.cooldownUntil.put(CD_SHOT, tick + 10L);
            return;
        }
        state.busyUntil = tick + cast.frames().total();
        state.lastCastTick = tick;
        state.energy -= cast.paid();
        state.cooldownUntil.put(CD_SHOT, tick + cast.cooldownTicks());
        itemCooldown(player, cast.cooldownTicks());

        commit(player, state, cast, null, "발출");
    }

    /** 선딜 텔레그래프 → 지속 프레임에 판정 → 후딜. 프레임은 다운캐스트해도 남는다 */
    private void commit(Player player, SkillEngine.State state, SkillEngine.Cast cast,
                        LivingEntity primary, String label) {
        if (cast.gated()) {
            SkillHud.actionBar(player, ChatColor.GRAY + "그 격은 아직 이 몸의 것이 아니다 ("
                    + engine.gradeGate(state.armed == null ? "발경" : state.armed) + "부터)");
        }
        if (cast.downcast()) {
            SkillHud.actionBar(player, ChatColor.DARK_GRAY + "기가 실리지 않는다 — 맨 기술");
        }

        SkillEngine.Frames f = cast.frames();
        // 시전 중 이동 제약 — 무거운 수일수록 발이 묶인다 (프레임 총량이 곧 자세의 무게다)
        if (f.total() >= 8) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                    Math.max(1, f.total()), f.total() >= 16 ? 2 : 1, false, false, false));
        }
        // 텔레그래프 — 응집은 빛으로 보인다. 선딜 2틱마다 손끝에 기가 모인다
        int rank = engine.gradeRank(cast.grade());
        if (cast.manifested() && f.startup() >= 2) {
            for (int t = 0; t < f.startup(); t += 2) {
                pending.add(new Pending(tick + t, () -> telegraph(player, cast)));
            }
            if (rank >= 2) {
                player.getWorld().playSound(player.getLocation(),
                        rank >= 3 ? Sound.BLOCK_BEACON_POWER_SELECT : Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                        0.7f, rank >= 3 ? 0.6f : 1.4f);
            }
        }
        player.swingMainHand();
        pending.add(new Pending(tick + f.startup(), () -> resolve(player, state, cast, primary, label)));
    }

    // ══════════ 판정 · 적용 ══════════

    private void resolve(Player player, SkillEngine.State state, SkillEngine.Cast cast,
                         LivingEntity primary, String label) {
        if (!player.isOnline()) {
            return;
        }
        List<LivingEntity> targets = "선".equals(cast.hitType())
                ? lineTargets(player, cast) : arcTargets(player, cast, primary);

        Location origin = swingLocation(player, cast);
        int hits = 0;
        for (LivingEntity target : targets) {
            // 실행력 = 무공 숙련 + 무기 보정 + 경지 격차(gm_modifiers realm_gap) + 상태 보정
            // MVT 근사: 능력치 시트가 없다 — 경지가 능력치의 자리를 대신한다 (skill_motion.md 4장)
            boolean hostile = target instanceof Monster;
            int mastery = plugin.ledger(player.getUniqueId())
                    .levelOf(engine.skillName(cast.skillId()), plugin.progression());
            int execBase = mastery + engine.weaponJudgmentBonus(weaponGrade(player))
                    + engine.realmGapBonus(state.realm, hostile ? "삼류" : "범인")
                    + (engine.isDepleted(state.energy) ? -2 : 0);   // 내공 고갈 = 판정 -2
            int resist = engine.difficulty(hostile ? "보통" : "쉬움");
            int roll = ThreadLocalRandom.current().nextInt(6) + 1
                    + ThreadLocalRandom.current().nextInt(6) + 1;   // 전투는 주사위를 쓴다

            SkillEngine.Strike strike = engine.strike(cast, execBase, roll, resist);
            if (!strike.hit()) {
                continue;
            }
            hits++;
            applying = true;
            try {
                target.damage(strike.damage(), player);
            } finally {
                applying = false;
            }
            stagger(target, player, cast);
            impact(target.getLocation().add(0, 1, 0), cast.grade(), strike);
        }

        if (hits == 0) {
            player.getWorld().playSound(origin, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 1.3f);
            hud.emit(origin, Particle.CLOUD, 3, 0.1, 0.01);
        } else {
            swingSound(player, origin, cast);
        }
        strain(player, state, cast);

        SkillHud.actionBar(player, SkillHud.gradeColor(cast.grade()) + label + " "
                + ChatColor.DARK_GRAY + "│ " + SkillHud.gradeLabel(cast.grade())
                + (hits > 0 ? ChatColor.WHITE + " · " + hits + "타" : ChatColor.GRAY + " · 헛손질"));
        hud.energyBar(player, state);
    }

    /** 호(arc) 히트박스 — 정면 부채꼴. max_targets 상한 (F-P3) */
    private List<LivingEntity> arcTargets(Player player, SkillEngine.Cast cast, LivingEntity primary) {
        double range = cast.range();
        Vector facing = player.getLocation().getDirection().setY(0).normalize();
        Location eye = player.getEyeLocation();
        List<LivingEntity> out = new ArrayList<>();
        if (primary != null && primary.isValid()) {
            out.add(primary);
        }
        for (org.bukkit.entity.Entity e : player.getNearbyEntities(range, range, range)) {
            if (out.size() >= cast.maxTargets()) {
                break;
            }
            if (!(e instanceof LivingEntity le) || le.equals(player) || out.contains(le) || !le.isValid()) {
                continue;
            }
            Vector to = le.getLocation().toVector().subtract(eye.toVector()).setY(0);
            if (to.lengthSquared() < 1e-6 || to.length() > range) {
                continue;
            }
            double angle = Math.toDegrees(facing.angle(to.normalize()));
            if (angle <= cast.angle() / 2.0) {
                out.add(le);
            }
        }
        return out;
    }

    /** 선(線) 히트박스 — 참격·포. 시선 방향으로 길이만큼, 폭 안의 것을 벤다 */
    private List<LivingEntity> lineTargets(Player player, SkillEngine.Cast cast) {
        double length = cast.range();
        double halfWidth = Math.max(0.5, cast.angle() / 2.0);   // 발출의 angle 칸은 폭(width)이다
        Vector dir = player.getLocation().getDirection().normalize();
        Location eye = player.getEyeLocation();
        List<LivingEntity> out = new ArrayList<>();
        for (org.bukkit.entity.Entity e : player.getNearbyEntities(length, length, length)) {
            if (out.size() >= cast.maxTargets()) {
                break;
            }
            if (!(e instanceof LivingEntity le) || le.equals(player) || !le.isValid()) {
                continue;
            }
            Vector to = le.getEyeLocation().toVector().subtract(eye.toVector());
            double along = to.dot(dir);
            if (along < 0 || along > length) {
                continue;
            }
            double perp = to.clone().subtract(dir.clone().multiply(along)).length();
            if (perp <= halfWidth) {
                out.add(le);
            }
        }
        return out;
    }

    /** 경직 — 등급별 틱 (약2·중5·강10·다운20). MC 근사: 이동 봉쇄 + 넉백 */
    private void stagger(LivingEntity target, Player from, SkillEngine.Cast cast) {
        int ticks = cast.staggerTicks();
        if (ticks <= 0) {
            return;
        }
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 4, false, false, true));
        if (ticks >= 10 && target instanceof Mob) {
            Vector push = target.getLocation().toVector()
                    .subtract(from.getLocation().toVector()).setY(0);
            if (push.lengthSquared() > 1e-6) {
                target.setVelocity(push.normalize().multiply(ticks >= 20 ? 0.8 : 0.45).setY(0.25));
            }
        }
    }

    /** 자기 무기가 자기 격을 못 견딘다 — 범철에 검기를 두르면 3회마다 손상 1 (weapon_break self_damage) */
    private void strain(Player player, SkillEngine.State state, SkillEngine.Cast cast) {
        if (!cast.manifested()) {
            return;
        }
        String grade = weaponGrade(player);
        if (!engine.selfDamages(grade, cast.grade(), 0)) {
            state.selfStrainCount = 0;
            return;
        }
        state.selfStrainCount++;
        int every = engine.selfDamageEvery();
        if (state.selfStrainCount % every != 0) {
            return;   // 아직 견딘다 — 1회째부터 '금이 간다'는 예고는 아래 손상 시점에만
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getItemMeta() instanceof Damageable dmg) {
            dmg.setDamage(dmg.getDamage() + 1);
            item.setItemMeta(dmg);
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.4f, 1.9f);
        hud.emit(handLocation(player), Particle.CRIT, 6, 0.12, 0.02);
        player.sendMessage(ChatColor.RED + "검이 운다 — " + grade + "의 몸으로 "
                + cast.grade() + "를 감당하지 못한다 (" + every + "합마다 금이 간다)");
    }

    // ══════════ 격 태세 ══════════

    private void cycleArmed(Player player) {
        SkillEngine.State state = state(player);
        List<String> armable = engine.armableGrades(state.realm);
        if (armable.isEmpty()) {
            SkillHud.actionBar(player, ChatColor.GRAY + "단전이 열리지 않았다 — 몸과 무기가 전부다");
            return;
        }
        String next = engine.cycleArmed(state.realm, state.armed);
        if (next == null) {
            dispel(player, state, "기를 거둔다");
            return;
        }
        int deploy = engine.sustainCost(next);
        if (deploy > 0 && state.energy < deploy) {
            SkillHud.actionBar(player, ChatColor.RED + "기를 두를 내력이 없다 (" + next + " 유지 " + deploy + ")");
            return;
        }
        state.energy -= deploy;
        state.armed = next;
        state.nextSustainTick = tick + engine.roundTicks();

        Location hand = handLocation(player);
        hud.emit(hand, qiParticle(next), 10, 0.15, 0.02);
        player.getWorld().playSound(player.getLocation(),
                engine.gradeRank(next) >= 3 ? Sound.BLOCK_CONDUIT_ACTIVATE : Sound.ITEM_TRIDENT_RIPTIDE_1,
                0.8f, engine.gradeRank(next) >= 3 ? 0.7f : 1.5f);
        SkillHud.actionBar(player, SkillHud.gradeColor(next) + next + " — " + gradeFlavor(next));
        hud.energyBar(player, state);
    }

    private void dispel(Player player, SkillEngine.State state, String why) {
        state.armed = null;
        state.nextSustainTick = -1;
        hud.emit(handLocation(player), Particle.SMOKE, 5, 0.12, 0.01);
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.6f);
        SkillHud.actionBar(player, ChatColor.GRAY + why);
    }

    private static String gradeFlavor(String grade) {
        return switch (grade) {
            case "발경" -> "타격에 기를 싣는다";
            case "검기" -> "기가 날에 서린다";
            case "강기" -> "기가 응집한다";
            case "어검" -> "검이 손을 떠난다";
            case "심검" -> "형(形)이 사라진다";
            default -> grade;
        };
    }

    // ══════════ 연출 ══════════

    /** 텔레그래프 — 상대가 읽을 수 있어야 한다 (npc_combat 대칭 원칙: 응집은 빛으로 보인다) */
    private void telegraph(Player player, SkillEngine.Cast cast) {
        if (!player.isOnline()) {
            return;
        }
        int n = 2 + engine.gradeRank(cast.grade());   // 격이 높을수록 밝다 (최대 7)
        hud.emit(handLocation(player), qiParticle(cast.grade()), n, 0.1, 0.01);
    }

    /** 타격 순간 — 격을 눈에 보이게. 단발 파티클 상한 24개 (시야 예산 600/틱의 4%) */
    private void impact(Location at, String grade, SkillEngine.Strike strike) {
        boolean crit = "critical_success".equals(strike.tierId());
        switch (grade) {
            case "발경" -> {   // 짧은 충격파 — 기가 몸 안에서 터진다
                hud.emit(at, Particle.SWEEP_ATTACK, 1, 0.0, 0.0);
                hud.emit(at, Particle.CRIT, 10, 0.25, 0.15);
                sound(at, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.9f, 0.9f);
                sound(at, Sound.BLOCK_ANVIL_LAND, 0.35f, 1.8f);
            }
            case "검기" -> {   // 날에 서린 기운이 살을 가른다
                hud.emit(at, Particle.ELECTRIC_SPARK, 12, 0.3, 0.05);
                hud.emit(at, Particle.END_ROD, 5, 0.2, 0.02);
                sound(at, Sound.ITEM_TRIDENT_THROW, 0.8f, 1.5f);
            }
            case "강기" -> {   // 응집 — 터지는 것이 아니라 뚫는다
                hud.emit(at, Particle.END_ROD, 16, 0.35, 0.06);
                hud.emit(at, Particle.EXPLOSION, 1, 0.0, 0.0);
                sound(at, Sound.BLOCK_CONDUIT_ACTIVATE, 0.9f, 1.2f);
            }
            case "어검", "심검" -> {
                hud.emit(at, Particle.END_ROD, 20, 0.4, 0.08);
                sound(at, Sound.BLOCK_BEACON_ACTIVATE, 0.9f, 1.4f);
            }
            default -> {       // 외공기 — 몸과 무기뿐. 다운캐스트도 여기로 온다 (빈약함이 정보다)
                hud.emit(at, Particle.CRIT, crit ? 8 : 4, 0.15, 0.05);
                sound(at, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f, 1.0f);
            }
        }
        if (crit) {
            hud.emit(at, Particle.CRIT, 6, 0.3, 0.2);
            sound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9f, 1.0f);
        }
    }

    private void swingSound(Player player, Location at, SkillEngine.Cast cast) {
        if ("선".equals(cast.hitType())) {
            // 발출의 궤적 — 선을 그린다 (상대가 피할 수 있게 눈에 남는다)
            Vector dir = player.getLocation().getDirection().normalize();
            Location step = player.getEyeLocation().clone();
            for (int i = 0; i < (int) cast.range(); i++) {
                step.add(dir);
                hud.emit(step, qiParticle(cast.grade()), 2, 0.05, 0.0);
            }
            sound(at, Sound.ITEM_TRIDENT_THROW, 1.0f, 0.8f);
            return;
        }
        sound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f, 1.1f);
    }

    private static Particle qiParticle(String grade) {
        return switch (grade) {
            case "발경" -> Particle.CRIT;
            case "검기" -> Particle.ELECTRIC_SPARK;
            case "강기", "어검", "심검" -> Particle.END_ROD;
            default -> Particle.SMOKE;
        };
    }

    private void sound(Location at, Sound sound, float volume, float pitch) {
        if (at.getWorld() != null) {
            at.getWorld().playSound(at, sound, volume, pitch);
        }
    }

    // ══════════ 관리 명령 접합 (MvtCommand 가 부른다) ══════════

    /** /혼천 경지 — MVT 는 캐릭터 시트가 없다. 경지·내공을 손으로 세운다 (검증 도구) */
    public void setRealm(Player player, String realm, double naegong) {
        SkillEngine.State state = state(player);
        state.realm = realm;
        state.naegong = naegong;
        state.energy = engine.pool(naegong);
        state.armed = null;
        hud.energyBar(player, state);
        player.sendMessage(ChatColor.GOLD + Glyphs.realmCrest(realm) + " " + realm
                + ChatColor.WHITE + " — 내공 " + String.format("%.2f", naegong)
                + " / 내력 " + state.energy
                + ChatColor.GRAY + " · 열린 격: "
                + (engine.armableGrades(realm).isEmpty() ? "없음 (외공기뿐)"
                        : String.join(" → ", engine.armableGrades(realm))));
    }

    /** /혼천 운기 — 운기조식 1구간 (전투 밖). 하한 1 + 순도 배율 (internal_energy recovery) */
    public void meditate(Player player) {
        SkillEngine.State state = state(player);
        int pool = engine.pool(state.naegong);
        if (pool <= 0) {
            player.sendMessage(ChatColor.GRAY + "단전이 비어 있다 — 돌릴 기가 없다.");
            return;
        }
        int before = state.energy;
        state.energy = Math.min(pool, state.energy + engine.meditationRecover(state.naegong, 1.0));
        hud.energyBar(player, state);
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 0.6f, 1.2f);
        hud.emit(player.getLocation().add(0, 1, 0), Particle.END_ROD, 8, 0.3, 0.01);
        player.sendMessage(ChatColor.AQUA + "한 구간을 앉았다 — 내력 " + before + " → " + state.energy
                + "/" + pool);
    }

    // ══════════ 정리 (performance.yml effects.cleanup_on) ══════════

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        SkillEngine.State state = states.get(event.getPlayer().getUniqueId());
        if (state != null) {
            state.armed = null;
            state.comboIndex = 0;
        }
    }

    // ══════════ 도우미 ══════════

    private String skillInHand(Player player) {
        String id = SKILL_BY_WEAPON_CLASS.get(engine.weaponClassOf(materialName(player)));
        return id != null && engine.hasActionData(id) ? id : null;
    }

    private static String materialName(Player player) {
        Material m = player.getInventory().getItemInMainHand().getType();
        return m == Material.AIR ? "AIR" : m.name();
    }

    private String weaponGrade(Player player) {
        return engine.weaponGradeOf(materialName(player));
    }

    private static Location handLocation(Player player) {
        return player.getEyeLocation().add(player.getLocation().getDirection().multiply(0.8)).subtract(0, 0.2, 0);
    }

    private Location swingLocation(Player player, SkillEngine.Cast cast) {
        return player.getEyeLocation()
                .add(player.getLocation().getDirection().multiply(Math.min(2.0, cast.range() / 2)));
    }

    private void itemCooldown(Player player, int ticks) {
        Material m = player.getInventory().getItemInMainHand().getType();
        if (m != Material.AIR && ticks > 0) {
            player.setCooldown(m, ticks);   // 바닐라 아이템 쿨다운 스와이프 = 스킬 쿨다운 (mc_action_mapping 2장)
        }
    }
}
