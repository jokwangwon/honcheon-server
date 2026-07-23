package com.honcheon.mvt;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 타격 피드백 — <b>맞았다는 그림</b> (등록부 {@code hit_feedback} · 2026-07-23 사용자 승인).
 *
 * <p>한월 실측(작업물/레퍼런스/한월_20260723)의 결론: 우리 전투에서 모자란 것은 휘두르는
 * 그림(획)이 아니라 <b>맞았다는 그림</b>이었다. 세 겹을 얹는다:
 * ① 대미지 숫자 — 실린 피해를 그 자리에 띄운다 (떠오르며 사라진다)
 * ② 표적 HP띠 — 타격 후 잠깐 머리 위에 판정의 잔고를 보인다
 * ③ 처치 흩어짐 — 몸이 스러질 때 한 번 터진다
 *
 * <p><b>판정은 한 획도 안 바꾼다.</b> 배선은 MONITOR({@link SkillListener})라 이미 실린
 * 피해(getFinalDamage)를 그대로 읽어 보일 뿐이다 — 화면=판정.
 *
 * <p>수명은 {@link #tick} 이 걷는다 (중앙 티커 하나를 같이 쓴다 — F-P2 태스크는 하나다).
 * 예산: 숫자 {@code max_alive} 초과 시 가장 오래된 것부터 거둔다 (조용한 누적 금지).
 */
final class HitFeedback {

    private final HoncheonMvt plugin;
    private SkillEngine engine;

    /** 살아 있는 대미지 숫자 (오래된 것이 앞) */
    private record Popup(TextDisplay d, long dieAt) {
    }

    private static final class Bar {
        TextDisplay d;
        long dieAt;
    }

    private final ArrayDeque<Popup> numbers = new ArrayDeque<>();
    private final Map<UUID, Bar> bars = new HashMap<>();
    private long tick;

    HitFeedback(HoncheonMvt plugin, SkillEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
    }

    /** 핫 리로드 (/혼천 모션 재적재) — 다음 타격부터 새 등록부를 읽는다 */
    void rebind(SkillEngine engine) {
        this.engine = engine;
    }

    /** 중앙 티커에서 매 틱 — 숫자 소멸 · 띠 추종/만료 */
    void tick(long now) {
        this.tick = now;
        while (!numbers.isEmpty()
                && (now >= numbers.peekFirst().dieAt() || !numbers.peekFirst().d().isValid())) {
            Popup p = numbers.pollFirst();
            if (p.d().isValid()) {
                p.d().remove();
            }
        }
        for (Iterator<Map.Entry<UUID, Bar>> it = bars.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Bar> e = it.next();
            Bar b = e.getValue();
            Entity host = plugin.getServer().getEntity(e.getKey());
            if (!b.d.isValid() || host == null || !host.isValid() || host.isDead() || now >= b.dieAt) {
                if (b.d.isValid()) {
                    b.d.remove();
                }
                it.remove();
                continue;
            }
            b.d.teleport(barAnchor(host));
        }
    }

    /**
     * 타격이 실렸다 — MONITOR 에서 부른다 (피해는 이미 확정).
     *
     * <p>띠의 잔고는 <b>다음 틱</b>에 읽는다: 이벤트 시점의 health 는 아직 이 타격이 안 빠진
     * 값이라, 그대로 그리면 띠가 한 대씩 늦게 말한다 (그것이 곧 거짓말이다).
     */
    void onDamaged(LivingEntity target, Player attacker, double damage) {
        SkillEngine.HitFx c = engine.hitFx();
        if (c == null || !c.enabled() || damage <= 0 || target instanceof Player) {
            return;
        }
        if (c.numberEnabled()) {
            spawnNumber(target, damage, c);
        }
        if (c.barEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> upsertBar(target, c));
        }
    }

    /** 처치 — 몸이 흩어진다 + 띠를 거둔다 */
    void onKilled(LivingEntity target) {
        Bar b = bars.remove(target.getUniqueId());
        if (b != null && b.d.isValid()) {
            b.d.remove();
        }
        SkillEngine.HitFx c = engine.hitFx();
        if (c == null || !c.enabled() || !c.killEnabled() || c.killCount() <= 0
                || target instanceof Player || target.getKiller() == null) {
            return;
        }
        Particle p;
        try {
            p = Particle.valueOf(String.valueOf(c.killParticle()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            p = Particle.CLOUD;   // 등록부 오타 — 흰 퍼프로 물러선다 (조용한 0 은 아니다)
        }
        Location at = target.getLocation().add(0, target.getHeight() * 0.5, 0);
        target.getWorld().spawnParticle(p, at, c.killCount(),
                c.killSpread(), c.killSpread(), c.killSpread(), 0.02);
    }

    /** 플러그인 종료 — 그림을 남기지 않는다 */
    void clearAll() {
        numbers.forEach(pu -> {
            if (pu.d().isValid()) {
                pu.d().remove();
            }
        });
        numbers.clear();
        bars.values().forEach(b -> {
            if (b.d.isValid()) {
                b.d.remove();
            }
        });
        bars.clear();
    }

    // ══════════ 발행 ══════════

    private void spawnNumber(LivingEntity target, double damage, SkillEngine.HitFx c) {
        while (numbers.size() >= c.numberMaxAlive()) {
            Popup old = numbers.pollFirst();   // 예산 — 가장 오래된 것부터 거둔다
            if (old.d().isValid()) {
                old.d().remove();
            }
        }
        Location at = target.getLocation().add(0, target.getHeight() * 0.75, 0);
        TextDisplay d = target.getWorld().spawn(at, TextDisplay.class, e -> {
            e.setText("§f§l" + Math.round(damage));   // 흰 종이빛 + 그림자 — 수묵 (발광 없음)
            e.setShadowed(true);
            e.setBillboard(Display.Billboard.CENTER);
            e.setDefaultBackground(false);
            e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            e.setPersistent(false);
            e.getPersistentDataContainer().set(SkillDisplay.KEY_VFX, PersistentDataType.BYTE, (byte) 1);
            float s = (float) c.numberScale();
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(s, s, s), new Quaternionf()));
        });
        // 떠오름 — 자리 보간 (클라가 잇는다). 크기는 그대로, 자리만 rise 만큼 오른다
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(c.numberTicks());
        float s = (float) c.numberScale();
        d.setTransformation(new Transformation(new Vector3f(0, (float) c.numberRise(), 0),
                new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
        numbers.addLast(new Popup(d, tick + c.numberTicks()));
    }

    private void upsertBar(LivingEntity target, SkillEngine.HitFx c) {
        if (!target.isValid() || target.isDead()) {
            return;   // 그 한 대가 마지막이었다 — 띠 대신 onKilled 가 말한다
        }
        Bar b = bars.get(target.getUniqueId());
        if (b == null) {
            if (bars.size() >= c.barMaxAlive()) {
                return;   // 동시 표적 상한 — 새 띠를 조용히 접는 것이 아니라 상한이 등록부에 있다
            }
            b = new Bar();
            b.d = target.getWorld().spawn(barAnchor(target), TextDisplay.class, e -> {
                e.setShadowed(true);
                e.setBillboard(Display.Billboard.CENTER);
                e.setDefaultBackground(false);
                e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                e.setAlignment(TextDisplay.TextAlignment.CENTER);
                e.setPersistent(false);
                e.getPersistentDataContainer().set(SkillDisplay.KEY_VFX, PersistentDataType.BYTE, (byte) 1);
            });
            bars.put(target.getUniqueId(), b);
        }
        double max = Math.max(1.0, target.getAttribute(
                org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
        double hp = Math.max(0.0, target.getHealth());
        int width = c.barWidth();
        int filled = (int) Math.ceil(width * Math.min(1.0, hp / max));
        StringBuilder bar = new StringBuilder("§c");
        for (int i = 0; i < filled; i++) {
            bar.append('█');
        }
        bar.append("§8");
        for (int i = filled; i < width; i++) {
            bar.append('█');
        }
        String name = target.getCustomName();
        b.d.setText((name == null || name.isBlank() ? "" : "§f" + name + "\n")
                + bar + " §f" + Math.round(hp) + "§7/" + Math.round(max));
        b.dieAt = tick + c.barSeconds() * 20L;
    }

    /** 띠의 자리 — 머리 위 여백만큼 (이름표와 겹치면 등록부 height 를 올린다) */
    private Location barAnchor(Entity host) {
        return host.getLocation().add(0, host.getHeight() + engine.hitFx().barHeight(), 0);
    }
}
