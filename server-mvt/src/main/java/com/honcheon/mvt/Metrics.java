package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 계측 — <b>예산을 재는 자</b>.
 *
 * <p><b>병(病).</b> {@code config/performance.yml} 에 예산이 적혀 있다 (npc_logic 6ms · 파티클 600/시야 …).
 * 그런데 <b>그 예산이 지켜지는지 재는 자가 없었다.</b> 예산은 문서였지 계기가 아니었다.
 * 매 틱 도는 것이 여섯이다 — SkillListener · SkillCast · SkillDisplay · MobDisplay · Populace · Incidents.
 * 이들이 <b>함께 돌 때</b> 무슨 일이 나는지 아무도 몰랐다. 모르면 고칠 수도 없다.
 *
 * <p><b>spark 와의 분업.</b> spark 는 <i>어느 메서드가 CPU 를 먹는지</i>를 안다 (샘플링 프로파일러 —
 * 스택 트리). 이 클래스는 <i>어느 티커가 자기 예산을 넘겼는지</i>를 안다 (등록제 예산과의 대조).
 * <b>둘은 다른 질문에 답한다.</b> spark 는 "왜 느린가"를, 계측은 "규약을 어겼는가"를 답한다.
 * 그래서 둘 다 쓴다 — spark 로 범인을 찾고, 계측으로 재발을 막는다 (경보는 상시, 프로파일은 필요할 때).
 *
 * <p><b>등록제.</b> 예산은 {@code performance.yml} 이 정본이다. 이 클래스는 수치를 하나도 모른다 —
 * {@code metrics.probes} 가 "티커 이름 → 예산 항목"을 대고, {@code tick_budget.subsystem_budget_ms} 가
 * 그 값을 댄다. yml 에 없는 이름은 <b>예산 없음</b>으로 재기만 하고 경보하지 않는다.
 *
 * <p><b>배선.</b> 티커를 감싸기만 하면 된다 — 티커의 본문은 한 줄도 안 바뀐다:
 * <pre>{@code
 *   // 이전
 *   scheduler.runTaskTimer(plugin, this::tick, 1L, 1L);
 *   // 이후
 *   scheduler.runTaskTimer(plugin, Metrics.wrap("skill_execution", this::tick), 1L, 1L);
 * }</pre>
 */
public final class Metrics {

    /** 계측 자체의 값 — nanoTime 두 번 + 맵 조회. 티커당 ~50ns. 끌 수 있게는 해 둔다. */
    private static volatile boolean enabled = true;

    /** 이름 → 예산(ns). {@code performance.yml} 이 정본 — 여기 기본값은 없다. */
    private static final Map<String, Long> BUDGET_NS = new ConcurrentHashMap<>();
    /** 이름 → 예산 항목명 (보고서 표시용). */
    private static final Map<String, String> BUDGET_KEY = new ConcurrentHashMap<>();

    private static final Map<String, Probe> PROBES = new ConcurrentHashMap<>();

    /** 예산을 넘긴 틱을 몇 틱에 한 번 콘솔에 짖을 것인가 (도배 방지). */
    private static int warnCooldownTicks = 200;
    /** 예산의 몇 배를 넘겨야 짖는가 (1.0 = 예산 초과 즉시). */
    private static double warnRatio = 1.0;
    private static boolean warnToConsole = true;

    private static Plugin plugin;
    private static long resetTick;

    private Metrics() {
    }

    // ══════════════════════════════════════════════════════════════════════
    //  등록 — performance.yml 이 정본
    // ══════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    public static void load(Path configDir) {
        BUDGET_NS.clear();
        BUDGET_KEY.clear();
        Path file = configDir.resolve("performance.yml");
        if (!Files.isRegularFile(file)) {
            return;
        }
        Map<String, Object> root = RulesConfig.load(file);

        // 서브시스템 예산 — tick_budget.subsystem_budget_ms
        Map<String, Object> sub = new LinkedHashMap<>();
        if (root.get("tick_budget") instanceof Map<?, ?> tb
                && ((Map<String, Object>) tb).get("subsystem_budget_ms") instanceof Map<?, ?> m) {
            sub.putAll((Map<String, Object>) m);
        }

        Map<String, Object> metrics = root.get("metrics") instanceof Map<?, ?> mm
                ? (Map<String, Object>) mm : Map.of();
        Object on = metrics.get("enabled");
        enabled = !(on instanceof Boolean b) || b;
        warnCooldownTicks = (int) num(metrics.get("warn_cooldown_ticks"), warnCooldownTicks);
        warnRatio = num(metrics.get("warn_ratio"), warnRatio);
        Object w = metrics.get("warn_to_console");
        warnToConsole = !(w instanceof Boolean wb) || wb;

        // 계기 등록부 — metrics.probes: { 티커이름: 예산항목 }
        if (metrics.get("probes") instanceof Map<?, ?> pm) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) pm).entrySet()) {
                String key = String.valueOf(e.getValue());
                Object ms = sub.get(key);
                if (ms instanceof Number n) {
                    BUDGET_NS.put(e.getKey(), (long) (n.doubleValue() * 1_000_000.0));
                    BUDGET_KEY.put(e.getKey(), key);
                }
            }
        }
    }

    private static double num(Object o, double fallback) {
        return o instanceof Number n ? n.doubleValue() : fallback;
    }

    /** 계기의 쓸기 — 티커가 <b>안 돈 틱</b>도 마감해 준다 (안 그러면 마지막 틱의 초과를 놓친다). */
    public static void start(Plugin p) {
        plugin = p;
        resetTick = tick();
        p.getServer().getScheduler().runTaskTimer(p, () -> {
            long now = tick();
            for (Probe probe : PROBES.values()) {
                synchronized (probe) {
                    if (probe.lastTick >= 0 && probe.lastTick != now) {
                        roll(probe);
                        probe.lastTick = -1;
                    }
                }
            }
        }, 1L, 1L);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  재기
    // ══════════════════════════════════════════════════════════════════════

    /** 티커를 감싼다 — 배선의 유일한 형태. 본문은 한 줄도 안 바뀐다. */
    public static Runnable wrap(String name, Runnable body) {
        return () -> timed(name, body);
    }

    /** 이름 붙은 구간을 잰다. */
    public static void timed(String name, Runnable body) {
        if (!enabled) {
            body.run();
            return;
        }
        long t0 = System.nanoTime();
        try {
            body.run();
        } finally {
            record(name, System.nanoTime() - t0);
        }
    }

    /** 값을 돌려주는 구간을 잰다. */
    public static <T> T timed(String name, Supplier<T> body) {
        if (!enabled) {
            return body.get();
        }
        long t0 = System.nanoTime();
        try {
            return body.get();
        } finally {
            record(name, System.nanoTime() - t0);
        }
    }

    /**
     * 이미 잰 시간을 적는다 (직접 nanoTime 을 쓴 자리 · TickBudget 이 부른다).
     * <p>같은 틱에 여러 번 불려도 좋다 — 틱 합계로 묶인다 (이벤트 핸들러가 그렇다).
     */
    public static void record(String name, long nanos) {
        if (!enabled) {
            return;
        }
        Probe p = PROBES.computeIfAbsent(name, Probe::new);
        long now = tick();
        synchronized (p) {
            if (p.lastTick != now) {
                if (p.lastTick >= 0) {
                    roll(p);
                }
                p.lastTick = now;
                p.tickNs = 0L;
                p.tickCalls = 0;
                p.ticks++;
            }
            p.tickNs += nanos;
            p.tickCalls++;
            p.calls++;
            p.totalNs += nanos;
            if (nanos > p.maxCallNs) {
                p.maxCallNs = nanos;
            }
        }
    }

    /** 한 틱을 마감한다 — 예산과 대조하는 유일한 자리. */
    private static void roll(Probe p) {
        if (p.tickNs > p.maxTickNs) {
            p.maxTickNs = p.tickNs;
            p.maxTickCalls = p.tickCalls;
        }
        Long budget = BUDGET_NS.get(p.name);
        if (budget == null || budget <= 0) {
            return;   // 예산 없음 — 재기만 한다 (등록제: yml 에 없으면 경보도 없다)
        }
        if (p.tickNs <= budget * warnRatio) {
            return;
        }
        p.overTicks++;
        if (warnToConsole && plugin != null && p.lastTick - p.lastWarnTick >= warnCooldownTicks) {
            p.lastWarnTick = p.lastTick;
            plugin.getLogger().warning(String.format(
                    "[계측] %s 예산 초과 — %.1fms / %.1fms (%s · 호출 %d회 · 누적 초과 %d틱)",
                    p.name, p.tickNs / 1e6, budget / 1e6,
                    BUDGET_KEY.getOrDefault(p.name, "?"), p.tickCalls, p.overTicks));
        }
    }

    private static long tick() {
        try {
            return Bukkit.getCurrentTick();
        } catch (Throwable t) {
            return System.nanoTime() / 50_000_000L;   // Paper 밖(테스트) — 대략의 틱
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  보고 — /혼천 계측
    // ══════════════════════════════════════════════════════════════════════

    /** 표를 줄 목록으로 (색 코드는 호출부가 얹는다 — 콘솔에서도 읽혀야 하므로 여기선 안 넣는다). */
    public static List<String> report() {
        List<String> out = new ArrayList<>();
        long span = Math.max(1L, tick() - resetTick);

        double[] tps = Bukkit.getTPS();
        out.add(String.format("계측 — 최근 %d틱 (%.0f초) · 계기 %s",
                span, span / 20.0, enabled ? "켜짐" : "꺼짐"));
        out.add(String.format("TPS %.2f / %.2f / %.2f   ·   평균 MSPT %.2fms   ·   목표 %s",
                tps[0], tps[1], tps[2], Bukkit.getAverageTickTime(),
                budgetLabel()));

        if (PROBES.isEmpty()) {
            out.add("계기가 하나도 안 붙었다 — Metrics.wrap 배선이 없다.");
            return out;
        }
        out.add(String.format("%-22s %7s %8s %8s %7s %7s", "티커", "예산", "틱평균", "틱최대", "초과틱", "호출/틱"));

        List<Probe> sorted = new ArrayList<>(PROBES.values());
        sorted.sort((a, b) -> Long.compare(b.maxTickNs, a.maxTickNs));
        for (Probe p : sorted) {
            synchronized (p) {
                Long budget = BUDGET_NS.get(p.name);
                double avgTick = p.ticks == 0 ? 0 : p.totalNs / (double) p.ticks / 1e6;
                out.add(String.format("%-22s %7s %8s %8s %7s %7s",
                        p.name,
                        budget == null ? "-" : String.format("%.0fms", budget / 1e6),
                        String.format("%.2fms", avgTick),
                        String.format("%.2fms", p.maxTickNs / 1e6),
                        budget == null ? "-" : String.valueOf(p.overTicks),
                        p.ticks == 0 ? "0" : String.format("%.1f", p.calls / (double) p.ticks)));
            }
        }
        out.add("틱평균 = 그 티커가 실제로 돈 틱들의 평균 (안 도는 틱은 안 센다).");
        return out;
    }

    private static String budgetLabel() {
        return BUDGET_NS.isEmpty() ? "performance.yml 미등록"
                : BUDGET_NS.size() + "개 항목 등록";
    }

    /** 계기를 0 으로 — 부하 시험은 이걸 부르고 시작한다. */
    public static void reset() {
        PROBES.clear();
        resetTick = tick();
    }

    public static void enabled(boolean on) {
        enabled = on;
    }

    public static boolean enabled() {
        return enabled;
    }

    /** 부하 시험의 합격 판정용 — performance.yml load_test.pass_criteria 와 대조한다. */
    public static double averageMspt() {
        return Bukkit.getAverageTickTime();
    }

    private static final class Probe {
        final String name;
        long calls;
        long totalNs;
        long maxCallNs;
        long ticks;             // 이 티커가 실제로 돈 틱 수
        long lastTick = -1;
        long tickNs;            // 지금 틱의 누적
        int tickCalls;
        long maxTickNs;         // 틱 합계의 최대 — 스파이크는 여기서 보인다
        int maxTickCalls;
        long overTicks;         // 예산을 넘긴 틱 수
        long lastWarnTick = Long.MIN_VALUE / 2;

        Probe(String name) {
            this.name = name;
        }
    }
}
