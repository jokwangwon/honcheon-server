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
                  String particle, String inkBody, int mirror) {
        return slashBand(center, facing, radius, sweepDeg, from, to, tiltDeg, stepDeg,
                width, rows, jitter, particle, inkBody, mirror, null);
    }

    /**
     * 관중을 가르는 띠 — 시전자(1인칭)와 관전자(3인칭)에게 <b>다른 굵기</b>의 같은 획을 보인다
     * (2026-07-22 사용자: "1인칭은 얇게, 3인칭은 넓게 — 검·도끼 둘 다"). 판정은 이 갈래와 무관하다.
     */
    int slashBand(Location center, float facing, double radius, double sweepDeg,
                  double from, double to, double tiltDeg, double stepDeg,
                  double width, int rows, double jitter,
                  String particle, String inkBody, int mirror,
                  java.util.function.Predicate<org.bukkit.entity.Player> audience) {
        return slashBand(center, facing, radius, sweepDeg, from, to, tiltDeg, stepDeg,
                width, rows, jitter, particle, inkBody, mirror, audience, 1.0);
    }

    /**
     * heightMul — 세로(위아래) 퍼짐 배율 (2026-07-22 사용자 재질문 답: "3인칭 넓게 = 세로 높이").
     * 관전자 갈래에만 키워 초승달 띠가 상하로 두툼한 면적을 덮게 한다. 판정 무관.
     */
    int slashBand(Location center, float facing, double radius, double sweepDeg,
                  double from, double to, double tiltDeg, double stepDeg,
                  double width, int rows, double jitter,
                  String particle, String inkBody, int mirror,
                  java.util.function.Predicate<org.bukkit.entity.Player> audience,
                  double heightMul) {
        return slashBand(center, facing, radius, sweepDeg, from, to, tiltDeg, stepDeg,
                width, rows, jitter, particle, inkBody, mirror, audience, heightMul,
                null, false, false);
    }

    /**
     * ★ v5 — 디자이너 컨셉 시트 반영 (2026-07-22 · 작업물/검기/2db88914….jpg):
     * 초승달 배 프로필(양끝 가늘고 중앙 최대) · 밀도 3계층(날 100%/몸 ~60%/안쪽 잔향 ~20%) ·
     * 먹방울 튀김(미숙한 내력의 흔적) · 1인칭 중앙 비움(clearNear) · 3인칭 외곽 먹선(darkOutline) ·
     * echoPass=true 면 번짐 단계(스윕 뒤) — 성긴 먹·본색이 뒤로 흩어진다 (100→350ms 구간).
     *
     * @param clearNear   null 아니면 이 지점 1.4m 안은 발행하지 않는다 — 1인칭 크로스헤어 비움
     * @param darkOutline 날 바깥에 성긴 먹 윤곽선 — 관전자(3인칭) 실루엣 강조
     * @param echoPass    번짐 패스 — 밀도 1/3 · 지터 2.5배 · 먹/본색만 (밝은 날 없음)
     */
    int slashBand(Location center, float facing, double radius, double sweepDeg,
                  double from, double to, double tiltDeg, double stepDeg,
                  double width, int rows, double jitter,
                  String particle, String inkBody, int mirror,
                  java.util.function.Predicate<org.bukkit.entity.Player> audience,
                  double heightMul, Location clearNear, boolean darkOutline, boolean echoPass) {
        return slashBand(center, facing, radius, sweepDeg, from, to, tiltDeg, stepDeg,
                width, rows, jitter, particle, inkBody, mirror, audience, heightMul,
                clearNear, darkOutline, echoPass, null);
    }

    /** inkAlt — 몸색 혼합 (디자이너 시트: 몸통 옥/청록 · 2026-07-22 "색감 동일하게"). null 이면 단색 */
    int slashBand(Location center, float facing, double radius, double sweepDeg,
                  double from, double to, double tiltDeg, double stepDeg,
                  double width, int rows, double jitter,
                  String particle, String inkBody, int mirror,
                  java.util.function.Predicate<org.bukkit.entity.Player> audience,
                  double heightMul, Location clearNear, boolean darkOutline, boolean echoPass,
                  String inkAlt) {
        if (center == null || center.getWorld() == null || to <= from) {
            return 0;
        }
        double full = Math.toRadians(sweepDeg);
        int steps = Math.max(1, (int) Math.ceil(sweepDeg * (to - from) / Math.max(0.2, stepDeg)));
        double tilt = Math.toRadians(tiltDeg);
        java.util.Random rnd = java.util.concurrent.ThreadLocalRandom.current();

        double f = Math.toRadians(facing);
        double fwdX = -Math.sin(f), fwdZ = Math.cos(f);
        double rgtX = -Math.cos(f), rgtZ = Math.sin(f);
        double half = full / 2.0;
        double mir = mirror < 0 ? -1.0 : 1.0;
        double clearSq = 1.4 * 1.4;
        int sent = 0;
        for (int i = 0; i <= steps; i++) {
            double u = (double) i / steps;
            double phase = from + (to - from) * u;
            double phi = -half + full * phase;
            // ★ 초승달 배 프로필 — 양끝(뿔) 가늘고 중앙(배)이 최대 (디자이너: 중반부가 가장 묵직)
            double d0 = Math.abs(phase - 0.55) / 0.55;
            double taper = Math.max(0.14, 1.0 - d0 * d0 * 0.95);
            double w = width * taper;
            // 끝자락 비백(飛白) — 뿔 근처는 지터가 커져 드문드문 흩어진다
            double horn = phase < 0.12 || phase > 0.88 ? 1.8 : 1.0;
            for (int k = -1; k <= rows; k++) {
                boolean edge = k < 0;
                boolean dark = k >= rows;
                if (echoPass && edge) {
                    continue;                               // 번짐 단계엔 밝은 날이 없다 (날 먼저 죽는다)
                }
                // ★ 밀도 3계층 (가이드): 날 100% · 몸 ~55%×배부름 · 안쪽 잔향 ~20%
                double keep = edge ? 1.0 : dark ? 0.20 : 0.55 * (0.5 + 0.5 * taper);
                if (echoPass) {
                    keep *= 0.35;                           // 번짐 — 전체가 성겨진다
                }
                if (!edge && rnd.nextDouble() > keep) {
                    continue;
                }
                double fr = edge ? 0.0 : dark ? 1.0 : (k + 1.0) / (rows + 1.0);
                double r = radius - w * fr;
                double side = Math.sin(phi) * r * Math.cos(tilt);
                double up = -Math.sin(phi) * r * Math.sin(tilt) * mir;
                double fwd = Math.cos(phi) * r;
                Location at = center.clone().add(
                        fwdX * fwd + rgtX * side, up, fwdZ * fwd + rgtZ * side);
                double jt = (edge ? jitter * 0.5 : jitter) * horn * (echoPass ? 2.5 : 1.0);
                at.add((rnd.nextDouble() - 0.5) * jt,
                       (rnd.nextDouble() - 0.5) * jt * 2.0 * heightMul,
                       (rnd.nextDouble() - 0.5) * jt);
                if (clearNear != null && at.distanceSquared(clearNear) < clearSq) {
                    continue;                               // 1인칭 크로스헤어 앞은 비운다
                }
                // inkAlt 있음 = 시트 평가판: 날 청백 · 몸 본색+alt 40% 혼합 · 잔향 먹/청회 교대 · 점묘 미세
                // inkAlt 없음 = 기존 v5 그대로 (기존 설정은 바꾸지 않는다 — 2026-07-22 사용자)
                boolean sheet = inkAlt != null;
                String ink = edge ? "청백"
                        : dark ? (sheet && rnd.nextBoolean() ? "청회" : "먹")
                        : (sheet && rnd.nextDouble() < 0.4 ? inkAlt : inkBody);
                float sz = edge ? (sheet ? 0.40f : 0.45f)
                        : dark ? (sheet ? 0.50f : 0.60f)
                        : (sheet ? 0.45f : 0.55f);
                sent += hud.emitSized(at, particle, ink, sz, 1, 0.05, 0.0, audience);
            }
            // ★ 외곽 먹선 (관전자 실루엣 강조 — 가이드 TPS 배치) — 날 바깥에 성긴 먹
            if (darkOutline && !echoPass && i % 2 == 0) {
                double r2 = radius + width * 0.12;
                double side = Math.sin(phi) * r2 * Math.cos(tilt);
                double up = -Math.sin(phi) * r2 * Math.sin(tilt) * mir;
                double fwd = Math.cos(phi) * r2;
                Location at = center.clone().add(
                        fwdX * fwd + rgtX * side, up, fwdZ * fwd + rgtZ * side);
                at.add((rnd.nextDouble() - 0.5) * jitter * 0.6,
                       (rnd.nextDouble() - 0.5) * jitter * heightMul,
                       (rnd.nextDouble() - 0.5) * jitter * 0.6);
                sent += hud.emitSized(at, particle, "먹", 0.55f, 1, 0.04, 0.0, audience);
            }
        }
        // ★ 먹방울 튀김 — 미숙한 내력의 흔적 (스윕 본 패스에서 구간당 최대 1방울)
        if (!echoPass && rnd.nextDouble() < 0.6) {
            double phase = from + (to - from) * rnd.nextDouble();
            double phi = -half + full * phase;
            double r = radius - width * (1.3 + rnd.nextDouble() * 1.2);   // 날 뒤(안쪽)로 튄다
            double side = Math.sin(phi) * r * Math.cos(tilt);
            double up = -Math.sin(phi) * r * Math.sin(tilt) * mir;
            double fwd = Math.cos(phi) * r;
            Location at = center.clone().add(
                    fwdX * fwd + rgtX * side, up + (rnd.nextDouble() - 0.5) * 0.3,
                    fwdZ * fwd + rgtZ * side);
            if (clearNear == null || at.distanceSquared(clearNear) >= clearSq) {
                sent += hud.emitSized(at, particle, "먹", 0.7f, 1, 0.06, 0.0, audience);
            }
        }
        return sent;
    }

    /** 빈짐 1단(+1틱) 색 내림 — 시트 「빈짐 350ms」: 밝은 날이 먼저 죽고 몸이 어두워진다. */
    private static final java.util.Map<String, String> DIM1 = java.util.Map.of(
            "청백", "옥", "옥", "청록", "청록", "청회", "청회", "먹", "먹", "먹");
    /** 빈짐 2단(+3틱) — 소멸(700ms)로 가는 길: 거의 다 청회·먹으로 가라앉는다. */
    private static final java.util.Map<String, String> DIM2 = java.util.Map.of(
            "청백", "청회", "옥", "청회", "청록", "먹", "청회", "먹", "먹", "먹");

    /**
     * ★ <b>템플릿 발행</b> — 컨셉 시트의 그림을 베기면에 그대로 놓는다 (QiTemplate 참조).
     *
     * <p>두 좌표계:
     * <ul>
     * <li><b>베기면 데칼</b> (billboard=false · 관전자): 그림 세로(y) = 베기 진행축
     *   (기울기 tilt 만큼 옆으로 누운 세로축), 그림 가로(x) = 앞뒤축 — 그림 오른쪽(초승달 열린 쪽)이
     *   시전자 쪽을 본다. 측면 관전자가 시트의 TPS 그림을 정면으로 본다.</li>
     * <li><b>빌보드</b> (billboard=true · 1인칭): 눈앞 고정 거리에 화면 평행으로 —
     *   시전자가 시트의 FPS 그림 그대로를 본다.</li>
     * </ul>
     *
     * @param halfH  그림 세로 반높이(m) — 초승달 전체 높이는 2×halfH
     * @param f0,f1  공개 구간 [f0,f1) — 점 목록(y 정렬)의 비율. 스윕 틱마다 새 구간만 긋는다
     * @param stage  0=본 스윕 · 1=빈짐(+1틱, 35% 색내림) · 2=빈짐(+3틱, 15% 색내림 2단)
     * @param rotDeg 그림 회전(도) — 횡참(부)은 90 으로 눕힌다
     */
    int emitTemplate(Location center, float facing, QiTemplate tpl, boolean billboard,
                     double halfH, double tiltDeg, int mirror, double f0, double f1,
                     int stage, double rotDeg,
                     java.util.function.Predicate<org.bukkit.entity.Player> audience) {
        if (center == null || center.getWorld() == null || tpl == null || tpl.pts.isEmpty()) {
            return 0;
        }
        java.util.Random rnd = java.util.concurrent.ThreadLocalRandom.current();
        double f = Math.toRadians(facing);
        double fwdX = -Math.sin(f), fwdZ = Math.cos(f);
        double rgtX = -Math.cos(f), rgtZ = Math.sin(f);
        double tilt = Math.toRadians(tiltDeg);
        double mir = mirror < 0 ? -1.0 : 1.0;
        double halfW = halfH / Math.max(0.3, tpl.aspect);
        double rot = Math.toRadians(rotDeg);
        double cr = Math.cos(rot), sr = Math.sin(rot);
        int n = tpl.pts.size();
        int i0 = Math.max(0, (int) Math.floor(f0 * n));
        int i1 = Math.min(n, (int) Math.floor(f1 * n));
        double keep = stage == 0 ? 1.0 : stage == 1 ? 0.35 : 0.15;
        double jbase = stage == 0 ? 0.03 : stage == 1 ? 0.28 : 0.5;   // r3: 지터가 윤곽을 흐렸다
        int sent = 0;
        for (int i = i0; i < i1; i++) {
            if (stage > 0 && rnd.nextDouble() > keep) {
                continue;
            }
            QiTemplate.Pt p = tpl.pts.get(i);
            double tx = p.x() * cr - p.y() * sr;
            double ty = p.x() * sr + p.y() * cr;
            double ox, oy, oz;
            if (billboard) {
                // 1인칭 — 화면 평행: 그림 x → 오른쪽(거울로 좌/우 교대), 그림 y(아래) → 화면 아래
                ox = rgtX * (tx * halfW * mir) + fwdX * 0.0;
                oy = -ty * halfH;
                oz = rgtZ * (tx * halfW * mir) + fwdZ * 0.0;
            } else {
                // 관전자 — 베기면 데칼: 그림 y → 기울어진 세로축, 그림 x → 앞뒤축(열린 쪽이 시전자 쪽)
                // ★ 앞 밀기 halfW·0.5 — 몸 중심에 놓으면 초승달이 몸에 겹친다 (r1 실측: 배치 가이드는
                //   초승달이 몸 **앞을 감싼다**)
                double vx = rgtX * Math.sin(tilt) * mir, vy = -Math.cos(tilt),
                        vz = rgtZ * Math.sin(tilt) * mir;
                double fx2 = -tx * halfW + halfW * 0.5;
                ox = fwdX * fx2 + vx * (ty * halfH);
                oy = vy * (ty * halfH);
                oz = fwdZ * fx2 + vz * (ty * halfH);
            }
            Location at = center.clone().add(
                    ox + (rnd.nextDouble() - 0.5) * jbase,
                    oy + (rnd.nextDouble() - 0.5) * jbase,
                    oz + (rnd.nextDouble() - 0.5) * jbase);
            String ink = p.ink();
            float sz = p.size();
            if (stage == 1) {
                ink = DIM1.getOrDefault(ink, ink);
                sz += 0.04f;
            } else if (stage == 2) {
                ink = DIM2.getOrDefault(ink, ink);
                sz += 0.07f;
            }
            // ★ 날 발광 (r3→r5): 청백을 절반 end_rod 로 바꿨더니 dust 림이 **끊겨** 읽혔다.
            //   그래서 청백 dust 는 전부 두고, 30% 에 end_rod 를 **덧얹는다** — 연속 림 + 광채.
            // 점당 2발 · 5cm 군집 (r8): 한 점을 살짝 어긋난 쌍으로 — 시트의 드라이브러시 뭉침.
            //   수는 예산 상향(평가 서버)이 받친다. end_rod 는 12%만 (30% 는 흰 줄무늬로 번졌다)
            //   청백(날)만 군집 2.5cm — 림이 몸으로 번지지 않게 (r9: 날 13%로 흐려졌다)
            double cluster = "청백".equals(ink) ? 0.025 : 0.05;
            sent += hud.emitSized(at, "dust", ink, sz, stage == 0 ? 2 : 1, cluster, 0.0, audience);
            if (stage == 0 && "청백".equals(ink) && rnd.nextDouble() < 0.12) {
                sent += hud.emitSized(at, "end_rod", null, sz, 1, 0.0, 0.0, audience);
            }
        }
        return sent;
    }

    /**
     * ★ <b>Impact 원형</b> (도끼·둔기 · vfx_primitives.md 원형 2호) — 호가 아니라 <b>충격</b>이다.
     *
     * <p>피격점에 짧고 넓은 <b>압력면</b>(눌린 원반)이 눌렸다 터지고, <b>불완전 충격 고리</b>가
     * 바깥으로 번진다. Slash 와 같은 재질 문법(등록부 색·명암 샌드위치)이되 첨두는 가장자리가
     * 아니라 <b>중심</b>이다 (헌장 — 원형별로 갈리는 것).
     *
     * @param tick  0=압축(작고 짙게) · 1=피크(면 완성+중심 청백) · 2..=고리 확장(결락 25%)
     */
    int impactBurst(Location at, double faceRadius, double ringMax, int tick, int ticks, String ink) {
        if (at == null || at.getWorld() == null) {
            return 0;
        }
        java.util.Random rnd = java.util.concurrent.ThreadLocalRandom.current();
        int sent = 0;
        if (tick <= 1) {
            double R = faceRadius * (tick == 0 ? 0.55 : 1.0);
            int rings = tick == 0 ? 3 : 4;
            for (int q = 0; q < rings; q++) {
                double rr = R * (q + 1) / rings;
                int n = Math.max(6, (int) (rr * 28));
                boolean rim = q == rings - 1;
                for (int i = 0; i < n; i++) {
                    double a = Math.PI * 2 * i / n + rnd.nextDouble() * 0.25;
                    Location pt = at.clone().add(Math.cos(a) * rr,
                            rnd.nextDouble() * 0.14, Math.sin(a) * rr);
                    sent += hud.emitSized(pt, "dust", rim ? "먹" : ink,
                            rim ? 0.6f : 0.55f, 1, 0.06, 0.0);
                }
            }
            if (tick == 1) {
                for (int i = 0; i < 10; i++) {          // 중심 청백 초점 — Impact 의 첨두
                    Location pt = at.clone().add((rnd.nextDouble() - 0.5) * 0.3,
                            rnd.nextDouble() * 0.25, (rnd.nextDouble() - 0.5) * 0.3);
                    sent += hud.emitSized(pt, "dust", "청백", 0.7f, 1, 0.05, 0.0);
                }
            }
        } else {
            double u = (tick - 1.0) / Math.max(1, ticks - 2);
            double rr = faceRadius + (ringMax - faceRadius) * u;
            int n = Math.max(10, (int) (rr * 30));
            for (int i = 0; i < n; i++) {
                if (rnd.nextDouble() < 0.28) {
                    continue;                            // 불완전 고리 — 결락이 곧 통제된 비대칭
                }
                double a = Math.PI * 2 * i / n;
                Location pt = at.clone().add(Math.cos(a) * rr,
                        0.06 + rnd.nextDouble() * 0.18, Math.sin(a) * rr);
                sent += hud.emitSized(pt, "dust", ink, 0.5f, 1, 0.10, 0.0);
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
