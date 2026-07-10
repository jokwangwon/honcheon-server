package com.honcheon.core.rules;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 지역 상태 엔진 — config/region_state.yml 의 자바 구현.
 * 치안/경제/민심(0~100)에 사건 결과를 반영한다. '지역 누적 시간'의 실체.
 */
public final class RegionStateEngine {

    private final Map<String, Map<String, Integer>> eventDeltas;
    private final int recoveryToward;
    private final int recoveryAmount;
    private final Map<String, Integer> state = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public RegionStateEngine(Map<String, Object> config, Map<String, Integer> initialState) {
        Map<String, Object> rawDeltas = RulesConfig.section(config, "event_deltas");
        Map<String, Map<String, Integer>> parsed = new HashMap<>();
        rawDeltas.forEach((event, deltas) -> {
            Map<String, Integer> statDeltas = new HashMap<>();
            ((Map<String, Object>) deltas).forEach((stat, value) ->
                    statDeltas.put(stat, RulesConfig.intValue(value)));
            parsed.put(event, Map.copyOf(statDeltas));
        });
        this.eventDeltas = Map.copyOf(parsed);

        Map<String, Object> recovery = RulesConfig.section(config, "recovery");
        this.recoveryToward = RulesConfig.intValue(recovery.get("toward"));
        this.recoveryAmount = RulesConfig.intValue(recovery.get("amount"));

        this.state.putAll(initialState);
    }

    /** 사건 결과 반영 — 변경된 상태 스냅샷 반환 */
    public Map<String, Integer> applyEvent(String eventKey) {
        Map<String, Integer> deltas = eventDeltas.get(eventKey);
        if (deltas == null) {
            throw new IllegalArgumentException("정의되지 않은 지역 사건: " + eventKey);
        }
        deltas.forEach((stat, delta) ->
                state.merge(stat, delta, (current, d) -> Math.max(0, Math.min(100, current + d))));
        return snapshot();
    }

    /** 자연 회복 1회분 — 기준값(50)을 향해 1씩 */
    public void recoveryStep() {
        state.replaceAll((stat, value) -> {
            if (value < recoveryToward) {
                return Math.min(recoveryToward, value + recoveryAmount);
            }
            if (value > recoveryToward) {
                return Math.max(recoveryToward, value - recoveryAmount);
            }
            return value;
        });
    }

    public int get(String stat) {
        return state.getOrDefault(stat, recoveryToward);
    }

    public Map<String, Integer> snapshot() {
        return Map.copyOf(state);
    }
}
