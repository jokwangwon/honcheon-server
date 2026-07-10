package com.honcheon.core.rules;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 심법 엔진 — config/simbeop.yml 의 자바 구현.
 * 단전 그릇(용량·순도·결), 계열(lineage), 정화력, 도인도기 자격을 굴린다.
 */
public final class SimbeopEngine {

    public record Simbeop(String id, String name, String lineage, String grade,
                          String method, int capacity, double purity) {
    }

    private static final Set<String> PURE_LINEAGES = Set.of("도가", "불가");
    private static final int PURE_LINEAGE_BONUS = 2;
    private static final double OTHERS_PURGE_PURITY_MIN = 1.3;

    private final Map<String, Simbeop> catalog;
    private final Map<String, Integer> poisonDifficulty;

    @SuppressWarnings("unchecked")
    public SimbeopEngine(Map<String, Object> config) {
        Map<String, Object> raw = RulesConfig.section(config, "simbeop");
        Map<String, Simbeop> parsed = new LinkedHashMap<>();
        raw.forEach((id, value) -> {
            Map<String, Object> s = (Map<String, Object>) value;
            parsed.put(id, new Simbeop(id,
                    (String) s.get("name"),
                    (String) s.get("lineage"),
                    (String) s.get("grade"),
                    (String) s.get("method"),
                    RulesConfig.intValue(s.get("capacity")),
                    ((Number) s.get("purity")).doubleValue()));
        });
        this.catalog = Collections.unmodifiableMap(parsed);

        Map<String, Object> difficulty = (Map<String, Object>)
                RulesConfig.section(config, "purification").get("poison_difficulty");
        Map<String, Integer> parsedDifficulty = new LinkedHashMap<>();
        difficulty.forEach((grade, value) -> parsedDifficulty.put(grade, RulesConfig.intValue(value)));
        this.poisonDifficulty = Collections.unmodifiableMap(parsedDifficulty);
    }

    public Simbeop get(String id) {
        Simbeop simbeop = catalog.get(id);
        if (simbeop == null) {
            throw new IllegalArgumentException("정의되지 않은 심법: " + id);
        }
        return simbeop;
    }

    /** 단전 용량 — 이 심법으로 도달 가능한 내공 상한 */
    public int capacity(String simbeopId) {
        return get(simbeopId).capacity();
    }

    /**
     * 정화력 = 내공 실수치 × 순도 (+ 도가·불가 계열 +2) — 화후 규칙.
     * 판정: 정화력 + 2d6 vs 독 등급 기준치 (등급제 준용)
     */
    public int purificationPower(String simbeopId, double naegongReal) {
        Simbeop simbeop = get(simbeopId);
        double power = naegongReal * simbeop.purity();
        if (PURE_LINEAGES.contains(simbeop.lineage())) {
            power += PURE_LINEAGE_BONUS;
        }
        return (int) Math.floor(power);
    }

    /** 도인도기(타인 해독)·정순 배독 자격 — 도가·불가 계열 + 순도 1.3 이상 */
    public boolean canPurgeOthers(String simbeopId) {
        Simbeop simbeop = get(simbeopId);
        return PURE_LINEAGES.contains(simbeop.lineage())
                && simbeop.purity() >= OTHERS_PURGE_PURITY_MIN;
    }

    public int poisonDifficulty(String grade) {
        Integer difficulty = poisonDifficulty.get(grade);
        if (difficulty == null) {
            throw new IllegalArgumentException("정의되지 않은 독 등급: " + grade);
        }
        return difficulty;
    }

    public List<String> catalogIds() {
        return List.copyOf(catalog.keySet());
    }
}
