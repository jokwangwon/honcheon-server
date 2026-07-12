package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 첫 접속의 목소리 — <b>아무도 말을 걸지 않았다.</b>
 *
 * <p>MVT 의 {@code PlayerJoinEvent} 처리기는 둘뿐이었다: 리소스팩(PackPusher)과 몹 형체(MobDisplay).
 * 사람이 처음 들어오면 <b>아무 말도 듣지 못하고</b> 빈 세계에 섰다. 그리고 {@code player_creation.yml}
 * 의 {@code initial_goal} 절은 <b>아무도 읽지 않는 유령 절</b>이었다 — config 에 적혀 있는데 코드에 손이 없었다.
 *
 * <p>더 나쁜 것: {@code /혼천 수련} 을 치지 않으면 {@code curriculum} 이 비고, 빈 curriculum 은
 * {@link Growth#train} 에서 즉시 {@code return 0} 이다. <b>아무것도 자라지 않는다.</b> 그리고 그 사실을
 * 본인이 알 방법이 없었다 — 화면에는 아무 일도 안 일어나니까.
 *
 * <p>이 클래스는 문장을 <b>지어내지 않는다</b>. 전부 {@code player_creation.yml mvt_onboarding} 이 정한다
 * (등록제 — config 가 단일 진실 원천).
 */
public final class Onboarding {

    private static Onboarding instance;

    private final String unlinkedTitle;
    private final String unlinkedSubtitle;
    private final List<String> unlinkedLines;
    private final long nagMillis;

    private final String goalPrefix;
    private final List<String> goals;
    private final List<String> linkedLines;

    private final Map<String, Integer> defaultCurriculum;

    /** 잔소리 억제 — 접합 안 한 몸에게 몇 분에 한 번만 말한다 (매 틱 떠들면 그건 벽이다) */
    private final Map<UUID, Long> lastNag = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    private Onboarding(Path cfg) {
        Map<String, Object> creation = RulesConfig.load(cfg.resolve("player_creation.yml"));
        Map<String, Object> on = RulesConfig.section(creation, "mvt_onboarding");

        Map<String, Object> unlinked = RulesConfig.section(on, "unlinked");
        this.unlinkedTitle = String.valueOf(unlinked.getOrDefault("title", "너는 아직 강호에 없다"));
        this.unlinkedSubtitle = String.valueOf(unlinked.getOrDefault("subtitle", "/혼천 접속"));
        this.unlinkedLines = lines(unlinked.get("lines"));
        this.nagMillis = num(unlinked.get("nag_seconds"), 180) * 1000L;

        Map<String, Object> linked = RulesConfig.section(on, "linked");
        this.goalPrefix = String.valueOf(linked.getOrDefault("goal_prefix", "첫 걸음"));
        this.linkedLines = lines(linked.get("lines"));

        // ★ 목표는 initial_goal 절이 정본이다 — goal_source 가 그 자리를 가리킨다 (지어내지 않는다)
        String source = String.valueOf(linked.getOrDefault("goal_source", "initial_goal.examples"));
        this.goals = lines(resolve(creation, source));

        Map<String, Integer> curriculum = new LinkedHashMap<>();
        RulesConfig.section(on, "default_curriculum")
                .forEach((subject, v) -> curriculum.put(subject, num(v, 0)));
        this.defaultCurriculum = Map.copyOf(curriculum);
    }

    public static void init(Path configDir) {
        instance = new Onboarding(configDir);
    }

    public static Onboarding get() {
        return instance;
    }

    /** 접합 전 — 강호에 이름이 없는 몸에게 (nag_seconds 마다 한 번) */
    public boolean shouldNag(UUID player, long now) {
        Long last = lastNag.get(player);
        if (last != null && now - last < nagMillis) {
            return false;
        }
        lastNag.put(player, now);
        return true;
    }

    public void forget(UUID player) {
        lastNag.remove(player);
    }

    public String unlinkedTitle() {
        return unlinkedTitle;
    }

    public String unlinkedSubtitle() {
        return unlinkedSubtitle;
    }

    public List<String> unlinkedLines() {
        return unlinkedLines;
    }

    public String goalPrefix() {
        return goalPrefix;
    }

    public List<String> linkedLines() {
        return linkedLines;
    }

    /**
     * 이 사람의 첫 목표 — {@code initial_goal.examples} 중 하나.
     * 몸마다 고정(uuid 해시)이다: 접속할 때마다 목표가 바뀌면 그것은 목표가 아니다.
     */
    public String goalFor(UUID player) {
        if (goals.isEmpty()) {
            return null;
        }
        return goals.get(Math.floorMod(player.hashCode(), goals.size()));
    }

    /**
     * 첫 배분 — <b>명령을 모르는 자의 몸도 자라야 한다.</b>
     * 빈 curriculum 은 {@code Growth.train} 이 즉시 0 을 돌려준다. 그 침묵이 이 게임의 첫 함정이었다.
     */
    public Map<String, Integer> defaultCurriculum() {
        return defaultCurriculum;
    }

    private static Object resolve(Map<String, Object> root, String dotted) {
        Object at = root;
        for (String part : dotted.split("\\.")) {
            if (!(at instanceof Map<?, ?> m)) {
                return null;
            }
            at = m.get(part);
        }
        return at;
    }

    private static List<String> lines(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            list.forEach(v -> out.add(String.valueOf(v)));
        }
        return List.copyOf(out);
    }

    private static int num(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }
}
