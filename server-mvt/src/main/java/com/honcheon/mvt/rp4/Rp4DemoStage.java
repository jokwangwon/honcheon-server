package com.honcheon.mvt.rp4;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * RP-4 A안 비교 무대 — rp4_pilot.md §5.1 의 대본을 그대로 돌린다.
 *
 * <p><b>무대 대본</b> (등재된 목록 — 새로 정하지 않는다):
 * 대기(10초) → 걷기(왕복) → 공격 → 피격 → 사망. <b>동작 5종 전부.</b>
 * 눈금은 전부 등록부에서 온다 (idle 10초 = §5.1 · 동작 틱 = mob_models.yml motion 절).
 *
 * <p><b>부하 무대</b> — 호랑이 군집 (performance.yml {@code load_test.combat_cluster_size} = 20).
 * 각자 제 원을 돌며 걷는다 (걷기 추종이 이 층의 주 비용이다 — 그것을 잰다).
 *
 * <p><b>무대 손잡이</b> (등록 눈금이 아니다 — 대본의 연출 매개변수. rp4_pilot.md §8.4 표):
 * 막간 {@link #ACT_GAP}, 왕복 거리 {@link #WALK_DIST}, 보행 속도(charge_speed_full 의 절반 —
 * '전력 질주에서 기울기 최대'라는 등록 눈금에서 유도), 군집 간격 {@link #GRID}(몸길이 2.2m + 여유),
 * 군집 자동 종료 {@link #CLUSTER_TTL}.
 *
 * <p><b>계측</b> — 중앙 티커 1개(F-P2 승계)의 nanoTime 과 teleport 횟수를 세서
 * §5.2 예산 실측표의 A안 칸(형체 수·틱 비용·갱신량 프록시)을 채운다. budget_ms 1 이 기준선이다.
 */
final class Rp4DemoStage {

    // ── 무대 손잡이 — 등록 눈금이 아니다 (연출 매개변수. 바꾸려면 여기와 §8.4 표를 함께 고친다) ──
    /** 대기 막 길이 — §5.1 대본의 "대기(10초)" */
    private static final int IDLE_TICKS = 200;
    /** 막간 — 동작과 동작 사이 2초 (관측·녹화가 끊어 읽을 수 있게) */
    private static final int ACT_GAP = 40;
    /** 걷기 왕복 편도 거리(m) */
    private static final double WALK_DIST = 8.0;
    /** 군집 배우 간격(m) — 몸길이 2.2m(등록부 size)보다 넉넉히 */
    private static final double GRID = 3.0;
    /** 군집 자동 종료(틱) — 60초. 잊힌 부하 무대를 세계에 남기지 않는다 */
    private static final int CLUSTER_TTL = 1200;

    private final Plugin plugin;
    private final Rp4Registry.Sheet sheet;

    private BukkitTask task;
    private long tick;

    // ── 5동작 무대 ──
    private Rp4DemoRig actor;
    private Player viewer;
    private int act;            // 0 대기 · 1 걷기(나감) · 2 걷기(돌아옴) · 3 막간 · 4 공격 · 5 피격 · 6 사망 대기
    private long actStart;
    private double traveled;
    private double walkSpeed;
    private long despawnAt;

    // ── 부하 무대 ──
    private final List<Rp4DemoRig> herd = new ArrayList<>();
    private final List<Location> centers = new ArrayList<>();
    private java.util.UUID herdViewer;
    private double herdAngle;
    private long herdUntil;

    // ── 계측 ──
    private long ticksMeasured;
    private long totalNanos;
    private long maxNanos;
    private long teleports;

    Rp4DemoStage(Plugin plugin, Rp4Registry.Sheet sheet) {
        this.plugin = plugin;
        this.sheet = sheet;
    }

    // ══════════════ 명령이 부르는 문 ══════════════

    /** 5동작 무대 — 관전자 앞에 한 마리를 세우고 대본을 돌린다 */
    String startStage(Player p, boolean lowTier) {
        if (actor != null || !herd.isEmpty()) {
            return "무대나 군집이 이미 돌고 있다 — /rp4demo 정리 로 걷고 다시 (계측이 섞이면 실측표가 거짓말한다).";
        }
        // 배우는 관전자 앞 4m, 관전자를 등지고 선다 (걷기가 옆모습으로 보이게 yaw 90° 꺾는다)
        Location at = p.getLocation().clone();
        at.add(at.getDirection().setY(0).normalize().multiply(4.0));
        at.setYaw(p.getLocation().getYaw() + 90f);
        at.setPitch(0);

        Rp4DemoRig rig = new Rp4DemoRig(plugin, sheet.model(), sheet.follow(), p.getUniqueId());
        if (!rig.spawn(at, lowTier)) {
            return "형체를 한 조각도 못 세웠다 — 등록부(parts)와 팩 키를 확인하라.";
        }
        actor = rig;
        viewer = p;
        walkSpeed = sheet.model().motion().chargeSpeedFull() * 0.5;   // 질주 눈금의 절반을 보행으로 (§8.4)
        act = 0;
        actStart = tick;
        traveled = 0;
        resetMetrics();
        ensureTicker();
        return "무대 개막 — " + sheet.model().name() + " ("
                + (lowTier ? "병합 실루엣 lod_parts" : "관절 " + rig.partCount() + "파트")
                + ") · 대본: 대기 10초 → 걷기 왕복 → 공격 → 피격 → 사망";
    }

    /** 부하 무대 — n 마리가 각자 제 원을 돌며 걷는다 (기본 n = load_test.combat_cluster_size) */
    String startCluster(Player p, int n) {
        if (actor != null || !herd.isEmpty()) {
            return "무대나 군집이 이미 돌고 있다 — /rp4demo 정리 로 걷고 다시 (계측이 섞이면 실측표가 거짓말한다).";
        }
        int count = n > 0 ? n : sheet.clusterSize();
        Location base = p.getLocation().clone();
        base.setPitch(0);
        int cols = (int) Math.ceil(Math.sqrt(count));
        int stood = 0;
        for (int i = 0; i < count; i++) {
            Location center = base.clone().add(
                    (i % cols) * (GRID + WALK_DIST), 0, (i / cols) * (GRID + WALK_DIST));
            Rp4DemoRig rig = new Rp4DemoRig(plugin, sheet.model(), sheet.follow(), p.getUniqueId());
            Location start = circle(center, 0);
            if (rig.spawn(start, false)) {
                herd.add(rig);
                centers.add(center);
                stood++;
            }
        }
        if (stood == 0) {
            return "군집을 한 마리도 못 세웠다 — 등록부와 팩 키를 확인하라.";
        }
        herdViewer = p.getUniqueId();
        herdAngle = 0;
        herdUntil = tick + CLUSTER_TTL;
        resetMetrics();
        ensureTicker();
        return "부하 무대 — " + stood + "마리 × " + sheet.model().parts().size()
                + "파트 = 형체 " + (stood * sheet.model().parts().size())
                + "개 (60초 뒤 자동 종료 · /rp4demo 통계 로 실측)";
    }

    /** 걷어내기 — 무대·군집 전부 (본체가 없으니 되돌릴 투명도 없다) */
    String clear() {
        int n = (actor != null ? 1 : 0) + herd.size();
        if (actor != null) {
            actor.despawn();
            actor = null;
            viewer = null;
        }
        for (Rp4DemoRig rig : herd) {
            rig.despawn();
        }
        herd.clear();
        centers.clear();
        herdViewer = null;
        stopIfIdle();
        return "걷었다 — 배우 " + n + "마리.";
    }

    /** 실측표 — §5.2 의 A안 칸: 형체 수 · 틱 비용 · 갱신량 프록시 */
    String stats() {
        int rigs = (actor != null ? 1 : 0) + herd.size();
        int liveParts = actor != null ? actor.partCount() : 0;
        for (Rp4DemoRig rig : herd) {
            liveParts += rig.partCount();
        }
        if (ticksMeasured == 0) {
            return "잰 틱이 없다 — 무대나 군집을 먼저 돌려라.";
        }
        return String.format(Locale.ROOT,
                "[RP-4·A안 실측] 배우 %d마리 · 파트 %d개 · %d틱 관측 · 틱 평균 %.3fms · 최대 %.3fms"
                        + " · teleport %.1f회/틱 (기준선 budget_ms 1 — mob_models.yml budget 절)",
                rigs, liveParts, ticksMeasured,
                totalNanos / (double) ticksMeasured / 1_000_000.0,
                maxNanos / 1_000_000.0,
                teleports / (double) ticksMeasured);
    }

    // ══════════════ 중앙 티커 1개 — F-P2 승계 ══════════════

    private void ensureTicker() {
        if (task == null || task.isCancelled()) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }
    }

    private void stopIfIdle() {
        if (actor == null && herd.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        tick++;
        long t0 = System.nanoTime();
        if (actor != null) {
            stageTick();
        }
        if (!herd.isEmpty()) {
            herdTick();
        }
        long dt = System.nanoTime() - t0;
        ticksMeasured++;
        totalNanos += dt;
        maxNanos = Math.max(maxNanos, dt);
    }

    // ── 5동작 대본 ──

    private void stageTick() {
        Rp4DemoRig rig = actor;
        if (viewer != null && viewer.isOnline()) {
            rig.focus = viewer.getEyeLocation();   // 머리 조준(상시 표정) — 관전자를 본다
        }
        long at = tick - actStart;
        switch (act) {
            case 0 -> {   // 대기 — 숨(idle_breath)·꼬리 표류가 보여야 한다
                if (at >= IDLE_TICKS) {
                    say("걷기 — 왕복 " + (int) WALK_DIST + "m (대각 보행·이동거리 위상)");
                    next(1);
                }
            }
            case 1 -> {   // 걷기 나감
                advance(rig);
                if (traveled >= WALK_DIST) {
                    rig.cursor.setYaw(rig.cursor.getYaw() + 180f);
                    traveled = 0;
                    next(2);
                }
            }
            case 2 -> {   // 걷기 돌아옴
                advance(rig);
                if (traveled >= WALK_DIST) {
                    traveled = 0;
                    say("공격 — attack_lean " + sheet.model().motion().attackLean()
                            + "° + 앞발 들기");
                    next(3);
                }
            }
            case 3 -> {   // 막간 뒤 공격
                if (at >= ACT_GAP) {
                    rig.attack(tick);
                    next(4);
                }
            }
            case 4 -> {   // 공격이 끝나면 막간 뒤 피격
                if (at >= sheet.model().motion().attackLeanTicks() + ACT_GAP) {
                    say("피격 — hurt_recoil " + sheet.model().motion().hurtRecoil() + "° 움찔");
                    rig.hurt(tick);
                    next(5);
                }
            }
            case 5 -> {   // 피격이 끝나면 막간 뒤 사망
                if (at >= sheet.model().motion().hurtTicks() + ACT_GAP) {
                    say("사망 — 옆으로 " + sheet.model().motion().deathTopple() + "° 쓰러진다");
                    rig.die();
                    despawnAt = tick + sheet.model().motion().deathTicks() + 4;   // 주검 여유 +4 (MobDisplay 와 같다)
                    next(6);
                }
            }
            case 6 -> {   // 주검 — 쓰러진 형체가 잠시 남는다
                if (tick >= despawnAt) {
                    rig.despawn();
                    actor = null;
                    say("무대 폐막 — 5동작 전부. " + stats());
                    viewer = null;
                    stopIfIdle();
                    return;
                }
            }
            default -> {
            }
        }
        if (actor != null && !actor.dead()) {
            teleports += actor.render(tick);
        }
    }

    private void next(int nextAct) {
        act = nextAct;
        actStart = tick;
    }

    /** 커서를 yaw 방향으로 한 걸음 — 무대는 평지를 전제한다 (Y 는 개막 높이 그대로) */
    private void advance(Rp4DemoRig rig) {
        double yaw = Math.toRadians(rig.cursor.getYaw());
        rig.cursor.add(-Math.sin(yaw) * walkSpeed, 0, Math.cos(yaw) * walkSpeed);
        traveled += walkSpeed;
    }

    private void say(String line) {
        if (viewer != null && viewer.isOnline()) {
            viewer.sendMessage("[RP-4] " + line);
        }
    }

    // ── 부하 무대 — 각자 제 원을 돈다 ──

    private void herdTick() {
        if (tick >= herdUntil) {
            String report = stats();
            Player p = herdViewer == null ? null : plugin.getServer().getPlayer(herdViewer);
            for (Rp4DemoRig rig : herd) {
                rig.despawn();
            }
            herd.clear();
            centers.clear();
            if (p != null && p.isOnline()) {
                p.sendMessage("[RP-4] 부하 무대 자동 종료 — " + report);
            } else {
                plugin.getLogger().info("[RP-4] 부하 무대 자동 종료 — " + report);
            }
            stopIfIdle();
            return;
        }
        double speed = sheet.model().motion().chargeSpeedFull() * 0.5;
        double radius = WALK_DIST / 2.0;
        herdAngle += speed / radius;   // 호 길이 = 보행 속도 — 배우마다 같은 각속도
        for (int i = 0; i < herd.size(); i++) {
            Rp4DemoRig rig = herd.get(i);
            Location next = circle(centers.get(i), herdAngle);
            rig.cursor.setX(next.getX());
            rig.cursor.setZ(next.getZ());
            rig.cursor.setYaw(next.getYaw());
            teleports += rig.render(tick);
        }
    }

    /** 반지름 WALK_DIST/2 원 위의 점 — yaw 는 접선 방향 (걷는 방향과 몸의 방향이 같다) */
    private Location circle(Location center, double angle) {
        double radius = WALK_DIST / 2.0;
        Location at = center.clone().add(radius * Math.cos(angle), 0, radius * Math.sin(angle));
        at.setYaw((float) Math.toDegrees(angle));   // 접선: dir = (-sin a, cos a) ⇔ yaw = a
        at.setPitch(0);
        return at;
    }

    private void resetMetrics() {
        ticksMeasured = 0;
        totalNanos = 0;
        maxNanos = 0;
        teleports = 0;
    }
}
