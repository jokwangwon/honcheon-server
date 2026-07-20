package com.honcheon.mvt;

import de.slikey.effectlib.Effect;
import de.slikey.effectlib.EffectManager;
import de.slikey.effectlib.effect.ArcEffect;
import de.slikey.effectlib.effect.CircleEffect;
import de.slikey.effectlib.effect.ConeEffect;
import de.slikey.effectlib.effect.HelixEffect;
import de.slikey.effectlib.effect.SphereEffect;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * <b>기(氣)의 기하 — 궤적을 그리는 손.</b>
 *
 * <p><b>왜 남의 라이브러리인가.</b> 우리 연출 어휘는 파티클 13종 + 고정 슬롯이었고
 * <b>궤적을 생성하는 도구가 0개</b>였다. 그래서 검기를 납작한 판(ItemDisplay)으로 흉내 냈고
 * 각도 문제를 만났다. 부분 호 하나를 손으로 짜려던 참에 사용자가 짚었다 —
 * <i>"이미 검증된 시스템을 도입하면 이후 추가가 더 쉽지 않을까?"</i> 옳았다.
 * 그건 10년 굴러간 EffectLib 의 <b>기하 49종 중 하나를 다시 만드는 일</b>이었다.
 *
 * <p><b>왜 MagicSpells 가 아니라 EffectLib 인가.</b> 우리가 원한 것(기하)은 EffectLib 에 있고,
 * MagicSpells 는 <b>그것을 셰이딩해 쓸 뿐</b>이다. 그리고 MagicSpells 를 통째로 들이면
 * 스펠이 우리 감사({@code tools/motion_audit.py} · {@code tools/lint_config.py})가 <b>못 읽는
 * 자리</b>에 앉아, 감사가 실패하는 게 아니라 <b>「커버리지 100%」라고 거짓 보고</b>하게 된다.
 * EffectLib 은 리스너 없는 <b>렌더 라이브러리</b>라 그 문제가 없다.
 *
 * <p><b>★ 핵심 계약 — 파티클은 EffectLib 이 쏘지 않는다.</b>
 * {@link Effect#display} 를 가로채 우리 {@link SkillHud#emit} 으로 흘린다. 그래야
 * <b>파티클 예산(20인 군집 기준 형식 도출)·관람자별 발행·LOD</b> 가 전부 살아 있다.
 * EffectLib 에게는 <b>「어디에 찍을지」만</b> 묻고, <b>「찍어도 되는지」는 우리가 답한다.</b>
 * 이 선을 넘기면 예산 밖 파티클이 생기고, 그 순간 남의 코드를 들인 대가를 치르게 된다.
 */
final class QiGeometry {

    private final HoncheonMvt plugin;
    private final SkillHud hud;
    private EffectManager manager;

    QiGeometry(HoncheonMvt plugin, SkillHud hud) {
        this.plugin = plugin;
        this.hud = hud;
    }

    /**
     * EffectManager 는 <b>늦게</b> 만든다 — 라이브러리가 없을 수도 있는 상황에서
     * 기동 자체가 죽지 않게 한다 (연출이 없는 것과 서버가 안 뜨는 것은 다르다).
     */
    private EffectManager manager() {
        if (manager == null) {
            manager = new EffectManager(plugin);
        }
        return manager;
    }

    void dispose() {
        if (manager != null) {
            manager.dispose();
            manager = null;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  점을 받아 오는 손 — EffectLib 에게 기하만 묻는다
    // ══════════════════════════════════════════════════════════════════

    /**
     * 기하 한 판을 <b>돌려서 점만 걷는다</b>. 파티클은 여기서 안 쏜다.
     *
     * <p>EffectLib 의 스케줄러를 쓰지 않고 {@code onRun()} 을 <b>우리 틱에서 직접</b> 부른다.
     * 왜: 저쪽 스케줄러는 제 주기로 돌고 async 갈래가 있다. 우리 연출은
     * {@code SkillEngine} 의 틱 위에 서 있어야 판정과 어긋나지 않는다.
     *
     * @return 찍어야 할 자리들 (월드 좌표)
     */
    private List<Location> trace(Effect effect, Location at) {
        List<Location> points = new ArrayList<>();
        effect.setLocation(at);
        effect.particle = Particle.DUST;      // 무엇이든 상관없다 — 가로채므로 실제로 안 쓰인다
        try {
            Collector.install(effect, points::add);
            effect.onRun();
        } catch (RuntimeException | LinkageError broken) {
            // ★ 조용히 실패하지 않는다. 기하가 안 나오면 **연출이 없는 것**이고, 그것은 말해야 한다.
            plugin.getLogger().warning("[기하] " + effect.getClass().getSimpleName()
                    + " 이(가) 점을 못 냈다 — " + broken);
        } finally {
            Collector.remove(effect);
        }
        return points;
    }

    /**
     * <b>가로채기</b> — {@code display(...)} 대신 우리 소비자를 부르게 만든다.
     *
     * <p>EffectLib 의 {@code display} 는 {@code protected} 라 상속으로만 가로챌 수 있는데,
     * 기하 49종을 전부 상속하면 49개 클래스가 생긴다. 그래서 <b>수집기를 스레드에 걸어 두고</b>
     * 아래 {@link Traced} 갈래가 그것을 부르게 한다 (기하마다 얇은 껍데기 하나면 된다).
     */
    static final class Collector {
        private static final ThreadLocal<Consumer<Location>> SINK = new ThreadLocal<>();

        static void install(Effect e, Consumer<Location> sink) {
            SINK.set(sink);
        }

        static void remove(Effect e) {
            SINK.remove();
        }

        /** 껍데기들이 부른다. 수집 중이면 걷고, 아니면 {@code false} (원래 동작으로 돌려보낸다). */
        static boolean take(Location at) {
            Consumer<Location> sink = SINK.get();
            if (sink == null) {
                return false;
            }
            sink.accept(at.clone());
            return true;
        }
    }

    /** 얇은 껍데기 — 기하는 부모(EffectLib)가, 발행은 우리가. */
    static final class TracedArc extends ArcEffect implements Traced {
        TracedArc(EffectManager m) {
            super(m);
        }

        @Override
        protected void display(Particle particle, Location location) {
            if (!Collector.take(location)) {
                super.display(particle, location);
            }
        }

        @Override
        protected void display(Particle particle, Location location, Color color) {
            if (!Collector.take(location)) {
                super.display(particle, location, color);
            }
        }

        @Override
        protected void display(Particle particle, Location location, float speed, int amount) {
            if (!Collector.take(location)) {
                super.display(particle, location, speed, amount);
            }
        }
    }

    /** 표식 — 「이 기하는 발행을 우리에게 넘긴다」 */
    interface Traced { }

    // ══════════════════════════════════════════════════════════════════
    //  ★ 검기의 호 — 몸 **둘레를 도는** 부분 호
    // ══════════════════════════════════════════════════════════════════

    /**
     * <b>참격의 호를 그린다.</b> 판이 아니라 점들이므로 <b>각도에 따라 사라지지 않는다.</b>
     *
     * <p>★ 실측이 가르쳐 준 것(RAG §6): 파티클로 바꿔도 <b>등 뒤는 45px 로 그대로였다</b>
     * (우리 판이 47px). 까닭은 원시도형이 아니라 <b>배치</b>다 — 호를 몸 <b>앞</b>에 두면
     * 등 뒤에서는 몸이 가린다. 그래서 여기서는 {@code radius} 만큼 <b>몸 둘레로 돌린다.</b>
     *
     * @param center  몸의 중심 (가슴께)
     * @param facing  바라보는 방향(도) — 호의 한복판이 이쪽을 본다
     * @param radius  공전 반지름 (m) — 몸 앞이 아니라 둘레다
     * @param sweepDeg 호가 무는 각(도)
     * @param progress 0..1 — <b>한 번의 베기가 자라나는 정도</b> (3프레임의 그 자람과 같은 뜻)
     * @param particle 등록부의 파티클 이름
     * @param ink      먹빛 이름 (없으면 {@code null})
     * @return 실제로 발행된 파티클 수 (예산에 막히면 요청보다 적다)
     */
    int slashArc(Location center, float facing, double radius, double sweepDeg,
                 double progress, String particle, String ink) {
        if (center == null || center.getWorld() == null) {
            return 0;
        }
        double span = Math.toRadians(sweepDeg) * Math.max(0.0, Math.min(1.0, progress));
        double start = Math.toRadians(facing) - span / 2.0;
        int steps = Math.max(4, (int) Math.round(sweepDeg * progress / 6.0));

        int sent = 0;
        for (int i = 0; i <= steps; i++) {
            double th = start + span * i / steps;
            Location at = center.clone().add(-Math.sin(th) * radius, 0.0, Math.cos(th) * radius);
            // ★ 우리 창구로만 나간다 — 예산·관람자·LOD 가 여기서 걸린다
            sent += ink == null ? hud.emit(at, particle, 1, 0.0, 0.0)
                                : hud.emit(at, particle, ink, 1, 0.0, 0.0, false);
        }
        return sent;
    }

    /**
     * EffectLib 의 기하를 <b>그대로</b> 빌려 쓰는 길 — 호 말고 다른 모양이 필요할 때.
     *
     * <p>지금은 검기가 위 {@link #slashArc} 로 충분하지만, 몹·보스의 기술은 원·나선·원뿔이
     * 필요해진다. 그때 이 손을 쓴다 — <b>기하 49종이 이미 검증돼 있고</b>, 우리는
     * 발행만 가로채면 된다.
     */
    int trace(String shape, Location at, String particle, String ink, Consumer<Effect> tune) {
        EffectManager m = manager();
        Effect e;
        switch (shape) {
            case "호" -> e = new TracedArc(m);
            case "원" -> e = new CircleEffect(m);
            case "나선" -> e = new HelixEffect(m);
            case "뿔" -> e = new ConeEffect(m);
            case "구" -> e = new SphereEffect(m);
            default -> {
                plugin.getLogger().warning("[기하] 모르는 모양: " + shape);
                return 0;
            }
        }
        if (tune != null) {
            tune.accept(e);
        }
        int sent = 0;
        for (Location p : trace(e, at)) {
            sent += ink == null ? hud.emit(p, particle, 1, 0.0, 0.0)
                                : hud.emit(p, particle, ink, 1, 0.0, 0.0, false);
        }
        return sent;
    }
}
