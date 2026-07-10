package com.honcheon.core.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 판정 엔진 — config/judgment.yml 의 자바 구현.
 * 스펙: docs/design/judgment_system.md, 참조 구현: tools/simulate_judgment.py
 *
 * 실행력 = 능력치 + 기술 + 상황 보정 + 2d6 / 저항값 = NPC측 + 7 / 마진 = 실행력 - 저항값
 */
public final class JudgmentEngine {

    public record Tier(String id, String name, Integer minMargin) {
    }

    public static final int NPC_FIXED_BONUS = 7;

    private final List<Tier> tiers;
    private final int autoSuccessMargin;
    private final int autoFailMargin;

    @SuppressWarnings("unchecked")
    public JudgmentEngine(Map<String, Object> judgmentConfig) {
        List<Map<String, Object>> rawTiers = (List<Map<String, Object>>) judgmentConfig.get("result_tiers");
        List<Tier> parsed = new ArrayList<>();
        for (Map<String, Object> raw : rawTiers) {
            Object min = raw.get("min_margin");
            parsed.add(new Tier((String) raw.get("id"), (String) raw.get("name"),
                    min == null ? null : RulesConfig.intValue(min)));
        }
        this.tiers = List.copyOf(parsed);

        Map<String, Object> auto = RulesConfig.section(judgmentConfig, "auto_resolution");
        this.autoSuccessMargin = RulesConfig.intValue(auto.get("auto_success_expected_margin"));
        this.autoFailMargin = RulesConfig.intValue(auto.get("auto_fail_expected_margin"));
    }

    public Tier tierOf(int margin) {
        for (Tier tier : tiers) {
            if (tier.minMargin() != null && margin >= tier.minMargin()) {
                return tier;
            }
        }
        return tiers.get(tiers.size() - 1); // 치명적 실패
    }

    public Tier resolve(int execBase, int roll2d6, int resist) {
        return tierOf(execBase + roll2d6 - resist);
    }

    /** 기대 마진 = 실행력 기본치 + 7(주사위 기대값) - 저항값 */
    public int expectedMargin(int execBase, int resist) {
        return execBase + NPC_FIXED_BONUS - resist;
    }

    public boolean isAutoSuccess(int execBase, int resist) {
        return expectedMargin(execBase, resist) >= autoSuccessMargin;
    }

    public boolean isAutoFail(int execBase, int resist) {
        return expectedMargin(execBase, resist) <= autoFailMargin;
    }

    /** 2d6 전수 열거(36가지) 등급 분포 — 파이썬 시뮬레이터와 동일 계산 */
    public Map<String, Double> distribution(int execBase, int resist) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int a = 1; a <= 6; a++) {
            for (int b = 1; b <= 6; b++) {
                counts.merge(resolve(execBase, a + b, resist).id(), 1, Integer::sum);
            }
        }
        Map<String, Double> dist = new LinkedHashMap<>();
        counts.forEach((id, count) -> dist.put(id, count / 36.0));
        return dist;
    }
}
