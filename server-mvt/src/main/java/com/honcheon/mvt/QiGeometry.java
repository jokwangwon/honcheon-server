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
     * @param from     0..1 — <b>이번에 그을 구간의 시작</b>
     * @param to       0..1 — 그 구간의 끝. ★ 매 틱 <b>지나간 자리만</b> 새로 긋는다 —
     *                 호 전체를 매번 다시 뿌리면 낭비이고, 예산에 막혀 오히려 성겨진다.
     *                 파티클은 제 수명 동안 남으므로 그은 자국이 <b>쌓여</b> 궤적이 된다.
     * @param tiltDeg  궤도면의 기울기 — 판(kigiPose)의 tilt_deg 와 같은 값을 줘야 둘이 같은 평면에 선다
     * @param stepDeg  점 간격(도). 작을수록 촘촘하다 (실측: 6도는 티끌로 읽혔다)
     * @param particle 등록부의 파티클 이름
     * @param ink      먹빛 이름 (없으면 {@code null})
     * @return 실제로 발행된 파티클 수 (예산에 막히면 요청보다 적다)
     */
    int slashArc(Location center, float facing, double radius, double sweepDeg,
                 double from, double to, double tiltDeg, double stepDeg,
                 String particle, String ink) {
        if (center == null || center.getWorld() == null || to <= from) {
            return 0;
        }
        double full = Math.toRadians(sweepDeg);
        double base = Math.toRadians(facing) - full / 2.0;
        double a0 = base + full * Math.max(0.0, Math.min(1.0, from));
        double a1 = base + full * Math.max(0.0, Math.min(1.0, to));
        int steps = Math.max(1, (int) Math.ceil(Math.toDegrees(a1 - a0) / Math.max(0.2, stepDeg)));
        double tilt = Math.toRadians(tiltDeg);

        int sent = 0;
        for (int i = 0; i <= steps; i++) {
            double th = a0 + (a1 - a0) * i / steps;
            // 수평 궤도를 시선축 둘레로 눕힌다 — 판(kigiPose)의 tilt_deg 와 **같은 평면**에 놓기 위해서다.
            //   (둘이 다른 평면에 있으면 점과 판이 서로 딴 데서 논다)
            double x = -Math.sin(th) * radius;
            double z = Math.cos(th) * radius;
            double y = -x * Math.sin(tilt);
            Location at = center.clone().add(x * Math.cos(tilt), y, z);
            // ★ 우리 창구로만 나간다 — 예산·관람자·LOD 가 여기서 걸린다
            sent += ink == null ? hud.emit(at, particle, 1, 0.0, 0.0)
                                : hud.emit(at, particle, ink, 1, 0.0, 0.0, false);
        }
        return sent;
    }

    /**
     * ★ <b>검기의 띠(밴드)</b> — 단선 호가 아니라 <b>폭·두께·단면색을 가진 리본</b>이다
     * (검기 재설계 v2 · docs/design/kigi_particle_v2.md · 야마토 레퍼런스 실측 2026-07-21).
     *
     * <p>단면 = 명암 샌드위치의 파티클 번역 (등록부 색만):
     * 바깥(날) <b>청백 1줄</b> → 몸 <b>본색(먹빛 인자) 여러 줄</b> → 안쪽 <b>먹 소량</b>(밝은 하늘 가독).
     * 실루엣은 검압을 계승한다 — 꼬리(스윕 시작) 가늘고 머리(진행 끝)로 갈수록 폭·밀도가 는다.
     *
     * <p>이전 「티끌」 실패(단선·저밀도)와의 차이가 이 폭×줄×지터다. 발행은 전부
     * {@code hud.emit} 창구로 — 예산·관람자·LOD 게이트가 그대로 산다.
     *
     * @param width  띠 폭(m) — 날(바깥 반경)에서 안쪽으로 이만큼 (실측 대역 0.5~0.8)
     * @param rows   몸 줄 수 (청백 날·먹 안감은 별도로 얹는다)
     * @param jitter 두께 지터(m) — 궤도면에 수직으로 ±이만큼 흩어 그레인 입체감을 만든다
     * @param inkBody 몸의 먹빛 (격 사다리 축 — 검기 청회 → 강기 청록 …)
     * @return 발행된 파티클 수
     */
    int slashBand(Location center, float facing, double radius, double sweepDeg,
                  double from, double to, double tiltDeg, double stepDeg,
                  double width, int rows, double jitter,
                  String particle, String inkBody) {
        if (center == null || center.getWorld() == null || to <= from) {
            return 0;
        }
        double full = Math.toRadians(sweepDeg);
        double base = Math.toRadians(facing) - full / 2.0;
        double a0 = base + full * Math.max(0.0, Math.min(1.0, from));
        double a1 = base + full * Math.max(0.0, Math.min(1.0, to));
        int steps = Math.max(1, (int) Math.ceil(Math.toDegrees(a1 - a0) / Math.max(0.2, stepDeg)));
        double tilt = Math.toRadians(tiltDeg);
        java.util.Random rnd = java.util.concurrent.ThreadLocalRandom.current();

        // ★ 시선 기준 좌표계 (v2b 실측 교훈: 월드축 기울임은 시선과 무관하게 「머리 위 화환」을 만든다).
        //   호는 몸 **앞** 반구를 가로지르고, 기울임은 시선축 둘레의 대각(왼위→오른아래)이다 — 베기면.
        double f = Math.toRadians(facing);
        double fwdX = -Math.sin(f), fwdZ = Math.cos(f);            // 시선 앞
        double rgtX = -Math.cos(f), rgtZ = Math.sin(f);            // 시전자의 오른쪽
        double half = full / 2.0;
        int sent = 0;
        for (int i = 0; i <= steps; i++) {
            double u = (double) i / steps;
            double phase = from + (to - from) * u;                 // 0=꼬리 … 1=머리
            double phi = -half + full * phase;                     // 시선 기준 호각
            // 검압 실루엣 — 꼬리 12% → 머리 100% (판 세대에서 확정한 폭 프로필의 요지)
            double taper = phase < 0.2 ? 0.12 + 0.88 * (phase / 0.2) * 0.25
                         : Math.min(1.0, 0.34 + 0.66 * Math.pow((phase - 0.2) / 0.6, 0.85));
            double w = width * taper;
            for (int k = -1; k <= rows; k++) {
                // k=-1: 날(청백·바깥) · 0..rows-1: 몸(본색) · rows: 먹 안감(소량)
                boolean edge = k < 0;
                boolean dark = k >= rows;
                if (dark && (i % 3 != 0 || phase < 0.25)) {
                    continue;                                       // 먹은 소량 — 셋에 하나
                }
                if (!edge && !dark && rnd.nextDouble() > 0.80 + 0.20 * taper) {
                    continue;                                       // 꼬리 쪽 몸은 성기게 (밀도 테이퍼)
                }
                double fr = edge ? 0.0 : dark ? 1.0 : (k + 1.0) / (rows + 1.0);
                double r = radius - w * fr;
                double side = Math.sin(phi) * r * Math.cos(tilt);
                double up = -Math.sin(phi) * r * Math.sin(tilt);   // 왼쪽 위 → 오른쪽 아래 대각
                double fwd = Math.cos(phi) * r;
                Location at = center.clone().add(
                        fwdX * fwd + rgtX * side, up, fwdZ * fwd + rgtZ * side);
                double jt = edge ? jitter * 0.5 : jitter;           // 날도 약간은 흩어진다 (딱딱한 선 금지)
                at.add((rnd.nextDouble() - 0.5) * jt, (rnd.nextDouble() - 0.5) * jt * 2.0,
                       (rnd.nextDouble() - 0.5) * jt);
                String ink = edge ? "청백" : dark ? "먹" : inkBody;
                // ★ 잘게 (2026-07-21 사용자: "밀도가 굵다·뭉툭하다") — 등록 크기 1.0 정사각이 아니라
                //   가는 알갱이. spread 는 발행 시 무작위 오프셋 — 「선 긋기」가 아니라 자연 확산.
                float sz = edge ? 0.50f : dark ? 0.60f : 0.55f;
                sent += hud.emitSized(at, particle, ink, sz, 1, 0.05, 0.0);
            }
            // 담묵 헤일로 — 획 둘레의 옅은 번짐 (둘에 하나 · 크게 흩고 · 성기게) — 퍼지는 느낌의 몸통
            if (i % 2 == 0 && phase > 0.15) {
                double r2 = radius - w * 0.5;
                double side2 = Math.sin(phi) * r2 * Math.cos(tilt);
                double up2 = -Math.sin(phi) * r2 * Math.sin(tilt);
                double fwd2 = Math.cos(phi) * r2;
                Location at2 = center.clone().add(
                        fwdX * fwd2 + rgtX * side2, up2, fwdZ * fwd2 + rgtZ * side2);
                at2.add((rnd.nextDouble() - 0.5) * jitter * 3.5,
                        (rnd.nextDouble() - 0.5) * jitter * 4.5,
                        (rnd.nextDouble() - 0.5) * jitter * 3.5);
                sent += hud.emitSized(at2, particle, inkBody, 0.45f, 1, 0.12, 0.0);
            }
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
