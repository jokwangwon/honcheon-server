package com.honcheon.mvt;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * <b>화살은 아이템이 아니라 이펙트다</b> — 원거리 사격의 사선(射線) 판정 (B-174).
 *
 * <p>사용자 원문 (2026-07-23): <i>"활도 스킬 모션으로 공격 이펙트로 활을 쏘는것 처럼 표시
 * (모든 원거리 무기 전부 이펙트로 표현)"</i>
 *
 * <p>검기 띠의 문법(<b>화면 = 판정</b> — {@code kigiBandStrike})을 사선으로 옮긴다:
 * 바닐라 화살 엔티티를 취소하고, 등록부가 주는 이펙트 선을 긋고, <b>그 선이 곧 판정</b>이다.
 * <ul>
 * <li><b>등록부가 전부다</b>: 사거리·당김·최소거리·탄약 = {@code skill_mechanics.yml ranged.활} ·
 *   이펙트·소리 = {@code skill_motion.yml weapon_styles.활} · 위력 = {@code combat.yml weapon_power.활}</li>
 * <li><b>엄폐는 수치가 아니라 물리다</b>: 벽이 사선을 끊는다 (cover_rule 의 이펙트 번역)</li>
 * <li><b>몸짓 불변</b> (§2.6): 당김은 바닐라 활 제스처 그대로 — 힘이 사거리를 정한다</li>
 * <li><b>코앞은 못 쏜다</b>: {@code min_range} 안의 표적은 사선이 지나친다 (활대로 치는 맨손 취급 —
 *   근접 보정도 안 얹는다, {@code Weapons.applyStats})</li>
 * </ul>
 *
 * <p>몹 궁수(스켈레톤 등)는 바닐라 화살 그대로다 — 이 규약은 혼천 병기(활 계열)에만 건다.
 * ★ 미구현 잔여 (B-174): {@code ranged.활.interrupt}(당김 중 피격 시 취소+화살 소모) ·
 * 이동 표적 −1 등 cover_rule 의 수치 보정 · 암기 계열.
 */
final class RangedShot implements Listener {

    private final HoncheonMvt plugin;

    RangedShot(HoncheonMvt plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;                      // 몹 궁수는 바닐라 그대로
        }
        ItemStack bow = event.getBow();
        if (bow == null || Weapons.seriesOf(bow) != Weapons.Series.활) {
            return;                      // 혼천 활만 — 남의 활엔 손대지 않는다
        }
        SkillEngine engine = plugin.skillEngine();
        SkillEngine.Ranged spec = engine.ranged("활");
        if (spec == null) {
            return;                      // 등록부가 없으면 지어내지 않고 바닐라로 물러선다
        }
        event.setCancelled(true);

        // 탄약 — 취소된 발사는 바닐라가 소모하지 않으므로 등록부(ammo: 화살)대로 우리가 물린다
        if (player.getGameMode() != GameMode.CREATIVE) {
            ItemStack consumable = event.getConsumable();
            Material ammo = consumable != null ? consumable.getType() : Material.ARROW;
            player.getInventory().removeItem(new ItemStack(ammo, 1));
        }

        // 당김 힘 → 사거리 (등록부 range 가 상한). 구현 주의: getForce 는 화살 속도 배율이라
        // 만작에서 3.0 까지 간다 — 1 초과면 3 으로 나눠 0..1 로 눕힌다
        float raw = event.getForce();
        float force = Math.max(0.05f, Math.min(1.0f, raw > 1.0001f ? raw / 3.0f : raw));
        double range = Math.max(spec.minRange(), spec.range() * force);

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World world = player.getWorld();

        // ① 벽이 먼저 끊는다 — 엄폐의 물리
        RayTraceResult wall = world.rayTraceBlocks(eye, dir, range);
        double dist = wall != null && wall.getHitPosition() != null
                ? wall.getHitPosition().distance(eye.toVector()) : range;

        // ② 사선 위 첫 생명체 — min_range 안은 지나친다 (코앞은 활의 거리가 아니다)
        RayTraceResult hit = world.rayTraceEntities(eye, dir, dist, 0.6,
                e -> e instanceof LivingEntity && e != player
                        && e.getLocation().distance(eye) >= spec.minRange());
        LivingEntity target = null;
        double hitDist = -1.0;
        if (hit != null && hit.getHitEntity() instanceof LivingEntity le) {
            target = le;
            hitDist = hit.getHitPosition().distance(eye.toVector());
            dist = Math.min(dist, hitDist);
        }

        // ③ 사선의 그림 + 시위 소리 — 그림은 ranged_fx(시안 축), 소리·폴백은 weapon_styles.활
        SkillEngine.Style style = engine.weaponStyle("활");
        SkillEngine.RangedFx fx = engine.rangedFx("활");
        if (style.swing() != null) {
            world.playSound(eye, style.swing().key(), style.swing().volume(), style.swing().pitch());
        }
        String mode = fx == null || fx.mode() == null ? "즉발" : fx.mode();
        switch (mode) {
            case "먹줄" -> drawInkLine(eye, dir, dist, fx);
            case "주행" -> { drawTracer(eye, dir, dist, fx, target, hitDist, bow, player); return; }
            default -> drawInstant(eye, dir, dist, style);
        }
        if (target != null) {
            // basicMelee 재진입 — 태세·기 방어·계기 판정이 전부 기존 한 경로로 잰다 (판정 일원화)
            target.damage(Math.max(1.0, Weapons.attackDamageOf(bow)), player);
        }
    }

    /** 즉발 한 겹 — v1 현행 (weapon_styles.활.trail 그대로) */
    private void drawInstant(Location eye, Vector dir, double dist, SkillEngine.Style style) {
        SkillHud hud = plugin.skills().hud();
        String particle = style.trail() == null || style.trail().isBlank()
                ? "enchanted_hit" : style.trail();
        for (double d = 1.2; d <= dist; d += 0.6) {
            hud.emit(eye.clone().add(dir.clone().multiply(d)), particle, 1, 0.02, 0.0);
        }
    }

    /** 먹줄 — 심(core)+테(rim) 두 겹 즉발. 검기의 먹빛 문법을 사선에 잇는다 */
    private void drawInkLine(Location eye, Vector dir, double dist, SkillEngine.RangedFx fx) {
        SkillHud hud = plugin.skills().hud();
        String core = fx.coreInk() == null ? "청백" : fx.coreInk();
        String rim = fx.rimInk() == null ? "먹" : fx.rimInk();
        // 사선에 수직인 테 방향 — 수평 우측 (연직 사선에서도 죽지 않게 월드 Y 와의 외적)
        Vector side = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 1.0e-6) {
            side = new Vector(1, 0, 0);
        }
        side.normalize().multiply(0.09);
        for (double d = 1.2; d <= dist; d += fx.step()) {
            Location at = eye.clone().add(dir.clone().multiply(d));
            hud.emitSized(at, "dust", core, fx.size(), 1, 0.01, 0.0);
            hud.emitSized(at.clone().add(side), "dust", rim, fx.size() * 0.7f, 1, 0.02, 0.0);
            hud.emitSized(at.clone().subtract(side), "dust", rim, fx.size() * 0.7f, 1, 0.02, 0.0);
        }
    }

    /**
     * 주행 — 트레이서가 틱마다 {@code speed_mpt} m 씩 난다. <b>판정도 도달 틱에 맞춘다</b>
     * (화면 = 판정: 그림이 늦게 닿으면 피해도 늦게 닿는다 — 즉발 판정에 주행 그림만 씌우면 거짓말이 된다).
     */
    private void drawTracer(Location eye, Vector dir, double dist, SkillEngine.RangedFx fx,
                            LivingEntity target, double hitDist, ItemStack bow, Player shooter) {
        SkillHud hud = plugin.skills().hud();
        String core = fx.coreInk() == null ? "청백" : fx.coreInk();
        String rim = fx.rimInk() == null ? "청회" : fx.rimInk();
        final double total = dist;
        final double hitAt = target != null ? hitDist : -1.0;
        new org.bukkit.scheduler.BukkitRunnable() {
            double head = 1.2;
            boolean struck = false;

            @Override
            public void run() {
                double next = Math.min(total, head + fx.speedMpt());
                for (double d = head; d <= next; d += fx.step()) {
                    Location at = eye.clone().add(dir.clone().multiply(d));
                    hud.emitSized(at, "dust", core, fx.size(), 1, 0.01, 0.0);
                }
                // 꼬리 — 머리 뒤 tail_m 만큼 성긴 잔상
                for (double d = Math.max(1.2, next - fx.tailM()); d < next; d += fx.step() * 2.0) {
                    hud.emitSized(eye.clone().add(dir.clone().multiply(d)),
                            "dust", rim, fx.size() * 0.6f, 1, 0.04, 0.0);
                }
                if (!struck && hitAt > 0 && next >= hitAt) {
                    struck = true;
                    if (target.isValid() && shooter.isOnline()) {
                        target.damage(Math.max(1.0, Weapons.attackDamageOf(bow)), shooter);
                    }
                }
                head = next;
                if (head >= total) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
}
