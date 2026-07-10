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
    private final boolean extremeDiceEnabled;

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

        Map<String, Object> extreme = (Map<String, Object>) judgmentConfig.get("optional_extreme_dice");
        this.extremeDiceEnabled = extreme != null && Boolean.TRUE.equals(extreme.get("enabled"));
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

    /** 플레이어 간 직접 대립 결과 — 동점 = 상쇄(무승부 서사). tier 는 A측 시점 (F22) */
    public record Opposed(int margin, Tier tier, boolean draw) {
    }

    /**
     * pvp direct_opposed (F22) + 극단 주사위 배선 (F24).
     * 양측 모두 2d6 (NPC 고정 +7 미적용), 마진 = 양측 실행력 차, 동점 = 상쇄.
     * 극단 주사위(enabled 시): 자신의 쌍륙 = 등급 1단계 상승, 쌍일 = 1단계 하락 —
     * A측 시점에서 B의 극단은 부호가 뒤집힌다 (양측 등급이 거울이 되도록).
     * 쌍륙 vs 쌍륙처럼 양측이 같은 극단이면 상쇄된다. 무승부 자체는 극단으로 뒤집히지 않는다.
     */
    public Opposed directOpposed(int execA, int rollA, int execB, int rollB) {
        int margin = (execA + rollA) - (execB + rollB);
        if (margin == 0) {
            return new Opposed(0, tierOf(0), true);
        }
        Tier tier = tierOf(margin);
        if (extremeDiceEnabled) {
            int shift = 0;
            if (rollA == 12) shift++;
            if (rollA == 2) shift--;
            if (rollB == 12) shift--;
            if (rollB == 2) shift++;
            tier = shiftTier(tier, shift);
        }
        return new Opposed(margin, tier, false);
    }

    public boolean extremeDiceEnabled() {
        return extremeDiceEnabled;
    }

    /** 등급 사다리 이동 — 양수 = 상승(대성공 방향), 리스트 경계에서 고정 */
    private Tier shiftTier(Tier tier, int shift) {
        int idx = tiers.indexOf(tier) - shift;   // tiers 는 대성공 → 치명적 실패 순
        return tiers.get(Math.max(0, Math.min(tiers.size() - 1, idx)));
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
