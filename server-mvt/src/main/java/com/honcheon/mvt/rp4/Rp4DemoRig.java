package com.honcheon.mvt.rp4;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * RP-4 데모의 무대 배우 — 몸(LivingEntity) 없이 서는 호랑이 형체.
 *
 * <p><b>왜 몸이 없나.</b> 비교 데모는 같은 자리·같은 대본으로 5동작을 재현해야 한다
 * (rp4_pilot.md §5.1). 살아 있는 몸의 AI 는 대본을 따르지 않는다 — 그래서 무대에서는
 * 가상 커서({@link #cursor})가 몸 노릇을 하고, 대본({@link Rp4DemoStage})이 그 커서를 움직인다.
 * <b>운영 편입용이 아니다</b>: 판정·사냥은 처음부터 없다 (때릴 몸이 없다 — 불변식 ㄴ 을
 * 위반할 여지 자체가 없다). 운영의 형체 층은 여전히 MobDisplay 하나다.
 *
 * <p><b>변환 수식은 MobDisplay v2 를 그대로 재현한다</b> (follow → apply → onDeath 의 수학).
 * 그것이 이 데모의 존재 이유다: A안이 무대에 올리는 것은 <b>현행 절차식 모션의 실제 실력</b>이지
 * 새로 지은 흉내가 아니다. 수식이 갈라지면 비교가 거짓말이 된다.
 *
 * <p><b>유령 규약 승계</b> — 조각마다 {@link #KEY_DEMO} 표를 달고 {@code setPersistent(false)}.
 * 플러그인이 죽어도 다음 {@code Rp4Demo.register} 가 걷는다 (MobDisplay.start 와 같은 문법).
 */
final class Rp4DemoRig {

    /** 유령 표식 — MobDisplay.KEY_RIG("mob_rig") 와 같은 문법, 다른 이름 (서로의 청소가 간섭하지 않는다) */
    static final NamespacedKey KEY_DEMO = new NamespacedKey("honcheon", "rp4_demo");

    private final Plugin plugin;
    private final Rp4Registry.Model model;
    private final Rp4Registry.Follow follow;
    private final UUID viewerId;

    private final List<ItemDisplay> parts = new ArrayList<>(7);
    private List<Rp4Registry.Part> specs = List.of();

    /** 가상 몸 — 무대가 이것을 움직인다 (yaw 포함). 렌더는 이 커서를 본체 위치로 먹는다 */
    Location cursor;
    /** 머리가 보는 곳 — 무대가 관전자의 눈을 넣는다 (head_yaw_max 상시 표정의 시연) */
    Location focus;

    private Location last;
    private double phase;     // 걷기 위상 — 이동거리로 돈다 (시간이 아니다. MobDisplay 와 같다)
    private double speed;     // 직전 틱의 이동거리 (m/틱)
    private long leanUntil;   // 공격 기울기·앞발 들기가 끝나는 틱
    private long hurtUntil;   // 피격 움찔이 끝나는 틱
    private boolean dead;

    Rp4DemoRig(Plugin plugin, Rp4Registry.Model model, Rp4Registry.Follow follow, UUID viewerId) {
        this.plugin = plugin;
        this.model = model;
        this.follow = follow;
        this.viewerId = viewerId;
    }

    // ══════════════ 세우기 · 걷어내기 ══════════════

    /**
     * 관절 등급(parts) 전부를 세운다. 한 조각도 못 뜨면 {@code false} — 무대는 그 배우를 버린다.
     * {@code lowTier} 면 병합 실루엣(lod_parts)으로 선다 — 강등 사다리 등급의 육안 비교용.
     */
    boolean spawn(Location at, boolean lowTier) {
        cursor = at.clone();
        last = null;
        List<Rp4Registry.Part> spec = lowTier && !model.lodParts().isEmpty()
                ? model.lodParts() : model.parts();
        List<Rp4Registry.Part> mounted = new ArrayList<>(spec.size());
        for (Rp4Registry.Part part : spec) {
            ItemDisplay d = spawnPart(at, part);
            if (d != null) {
                parts.add(d);
                mounted.add(part);
            }
        }
        specs = List.copyOf(mounted);
        if (parts.isEmpty()) {
            return false;
        }
        show();
        return true;
    }

    private ItemDisplay spawnPart(Location at, Rp4Registry.Part part) {
        ItemStack stack = new ItemStack(part.base());
        ItemMeta meta = stack.getItemMeta();
        NamespacedKey key = NamespacedKey.fromString(part.key());
        if (meta == null || key == null || at.getWorld() == null) {
            return null;
        }
        meta.setItemModel(key);   // 팩과의 유일한 접점 — MobDisplay 와 같은 키를 부른다 (같은 팩, 같은 모델)
        stack.setItemMeta(meta);

        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(stack);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setViewRange(model.viewRange());
            d.setTeleportDuration(follow.teleportDuration());
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(follow.interpolation());
            d.setPersistent(false);
            d.setVisibleByDefault(false);   // 관전자에게만 보인다 — 팩 없는 눈에 보라 큐브를 들이밀지 않는다
            d.getPersistentDataContainer().set(KEY_DEMO, PersistentDataType.BYTE, (byte) 1);
            d.setTransformation(new Transformation(
                    new Vector3f(part.offset()), new Quaternionf(),
                    new Vector3f(part.size()), new Quaternionf()));
        });
    }

    /** 관전자에게만 보여 준다 — 데모의 관중은 명령을 친 한 사람이다 (팩 수락은 그 사람의 몫) */
    private void show() {
        Player viewer = plugin.getServer().getPlayer(viewerId);
        if (viewer == null) {
            return;
        }
        for (ItemDisplay d : parts) {
            viewer.showEntity(plugin, d);
        }
    }

    void despawn() {
        for (ItemDisplay d : parts) {
            if (d.isValid()) {
                d.remove();
            }
        }
        parts.clear();
        specs = List.of();
    }

    // ══════════════ 대본 사건 — 무대가 부른다 ══════════════

    /** 공격 — attack_lean_deg 기울기 + 앞다리 들기(attack_raise_deg). MobDisplay.onStrike 의 가해 쪽 */
    void attack(long tick) {
        leanUntil = tick + model.motion().attackLeanTicks();
    }

    /** 피격 — hurt_recoil_deg 움찔. MobDisplay.onStrike 의 피해 쪽 */
    void hurt(long tick) {
        hurtUntil = tick + model.motion().hurtTicks();
    }

    /**
     * 사망 — 옆으로 넘어지며 가라앉는다. MobDisplay.onDeath 의 변환 그대로.
     * 걷어내는 시점은 무대의 몫이다 (death_ticks + 4 — 주검 여유도 같은 눈금).
     */
    void die() {
        dead = true;
        Rp4Registry.Motion m = model.motion();
        Quaternionf topple = new Quaternionf().rotateZ((float) Math.toRadians(m.deathTopple()));
        for (int i = 0; i < parts.size(); i++) {
            ItemDisplay d = parts.get(i);
            if (!d.isValid()) {
                continue;
            }
            Rp4Registry.Part part = specs.get(i);
            Vector3f t = new Vector3f(part.offset());
            topple.transform(t);
            t.y -= m.deathSink();
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(m.deathTicks());
            d.setTransformation(new Transformation(t, new Quaternionf(topple),
                    new Vector3f(part.size()), new Quaternionf()));
        }
    }

    boolean dead() {
        return dead;
    }

    int partCount() {
        return parts.size();
    }

    // ══════════════ 렌더 — MobDisplay.follow / apply 의 수식 재현 ══════════════

    /**
     * 한 틱의 자세 — 무대가 커서를 움직인 <b>뒤에</b> 부른다.
     *
     * @return 이번 틱의 teleport 횟수 (계측 — 패킷량의 프록시. rp4_pilot.md §11)
     */
    int render(long tick) {
        if (dead || parts.isEmpty() || cursor == null) {
            return 0;
        }
        Location at = cursor;
        Rp4Registry.Motion m = model.motion();

        double moved = 0;
        if (last != null && last.getWorld() == at.getWorld()) {
            moved = last.distance(at);
        }
        speed = moved;
        phase += moved * m.walkBobRate();   // 위상은 이동거리로 돈다 — 멈추면 다리도 멈춘다
        double yawDelta = last == null ? 999 : Math.abs(wrap(at.getYaw() - last.getYaw()));
        boolean move = moved > follow.moveEpsilon() || yawDelta > follow.yawEpsilon();

        float lean = 0;
        if (leanUntil > tick && m.attackLean() != 0) {
            lean += m.attackLean();
        }
        if (hurtUntil > tick && m.hurtRecoil() != 0) {
            lean -= m.hurtRecoil();
        }
        if (m.chargeLean() != 0 && m.chargeSpeedFull() > 0) {
            lean += (float) (m.chargeLean() * Math.min(1.0, speed / m.chargeSpeedFull()));
        }
        float roll = (float) (m.walkRoll() * Math.sin(phase));
        float bob = (float) (m.walkBob() * Math.abs(Math.sin(phase)));
        if (m.idleBreath() > 0 && m.idleRate() > 0) {
            double calm = Math.max(0.0, 1.0 - speed / Math.max(1e-6, follow.moveEpsilon() * 4));
            bob += (float) (m.idleBreath() * calm * Math.sin(tick * m.idleRate()));
        }

        Quaternionf pose = new Quaternionf()
                .rotateX((float) Math.toRadians(lean))
                .rotateZ((float) Math.toRadians(roll));

        Location stand = at.clone();
        stand.setPitch(0);

        int teleports = 0;
        for (int i = 0; i < parts.size(); i++) {
            ItemDisplay d = parts.get(i);
            if (!d.isValid()) {
                continue;
            }
            if (move) {
                d.teleport(stand);
                teleports++;
            }
            apply(d, specs.get(i), pose, bob, tick);
        }
        last = at.clone();
        return teleports;
    }

    private void apply(ItemDisplay d, Rp4Registry.Part part, Quaternionf pose, float bob, long tick) {
        Vector3f t = new Vector3f(part.offset());
        pose.transform(t);
        t.y += bob;

        Quaternionf q = new Quaternionf(pose);
        switch (part.role()) {
            case "head" -> {
                float[] look = look(part);
                q.rotateY((float) Math.toRadians(look[0]));
                q.rotateX((float) Math.toRadians(look[1]));
            }
            case "tail" -> q.rotateY((float) Math.toRadians(
                    part.swayDeg() * Math.sin(phase + tick * model.motion().idleRate())));
            case "leg" -> {
                if (leanUntil > tick && part.attackRaiseDeg() != 0) {
                    q.rotateX((float) Math.toRadians(-part.attackRaiseDeg()));
                } else {
                    q.rotateX((float) Math.toRadians(
                            part.swayDeg() * Math.sin(phase + Math.toRadians(part.phaseDeg()))));
                }
            }
            default -> {
                // body — 몸통의 자세 그대로
            }
        }
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(follow.interpolation());
        d.setTransformation(new Transformation(t, q, new Vector3f(part.size()), new Quaternionf()));
    }

    /** 머리가 focus 를 보는 각 — MobDisplay.look 의 수식, 표적 대신 무대의 focus */
    private float[] look(Rp4Registry.Part part) {
        if (focus == null || part.headYawMax() <= 0 || focus.getWorld() != cursor.getWorld()) {
            return new float[]{0, 0};
        }
        double dx = focus.getX() - cursor.getX();
        double dz = focus.getZ() - cursor.getZ();
        double dy = focus.getY() - (cursor.getY() + part.offset().y);
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < 0.1) {
            return new float[]{0, 0};
        }
        float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float yaw = clamp(wrap(want - cursor.getYaw()), -part.headYawMax(), part.headYawMax());
        float pitch = clamp((float) Math.toDegrees(-Math.atan2(dy, flat)),
                -part.headPitchMax(), part.headPitchMax());
        return new float[]{-yaw, pitch};
    }

    private static float wrap(float deg) {
        float d = deg % 360f;
        if (d > 180f) {
            d -= 360f;
        }
        if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
