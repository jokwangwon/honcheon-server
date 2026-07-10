package com.honcheon.core.rules;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 성장 엔진 — config/cultivation.yml(실전의 경험치화 v2.1)·config/training.yml(수련 환산표)의 자바 구현.
 * 원칙: 경험치·레벨 없음 — 모든 성장은 화후(실수 적립, '수련 일치' 단위) 원장 하나로 쌓인다.
 * 수련은 매일 조금씩, 실전은 한 번에 크게 — 적립 속도가 다를 뿐 장부는 같다.
 */
public final class ProgressionEngine {

    private static final Pattern DURATION = Pattern.compile("(\\d+)(년|개월)");

    private final double baseGrantDays;
    private final double repetitionDecayRate;
    private final double dailyCapDays;
    private final Map<String, Object> gapMultipliers;
    private final Map<String, Object> stakesMultipliers;
    private final Map<String, Object> skillCosts;

    public ProgressionEngine(Map<String, Object> cultivation, Map<String, Object> training) {
        Map<String, Object> combat = RulesConfig.section(cultivation, "combat_hwahu");
        this.baseGrantDays = ((Number) combat.get("base_grant_days")).doubleValue();
        this.repetitionDecayRate = ((Number) combat.get("repetition_decay_rate")).doubleValue();
        this.dailyCapDays = ((Number) combat.get("daily_cap_days")).doubleValue();
        Map<String, Object> multipliers = RulesConfig.section(combat, "multipliers");
        this.gapMultipliers = RulesConfig.section(multipliers, "상대_격차");
        this.stakesMultipliers = RulesConfig.section(multipliers, "목숨_무게");
        this.skillCosts = RulesConfig.section(training, "skill_progression_cost");
    }

    /**
     * 실전 적립(수련 일치) = 기본 × 상대 격차 × 목숨 무게 × 반복 감쇠.
     * 배움의 양은 상대가 정한다 — 압도적 하수는 0 (늑대 백 마리째에는 늑대에게 배울 게 없다).
     */
    public double combatAccrualDays(String gapTier, String stakesTier, int repetition) {
        double gap = multiplier(gapMultipliers, gapTier, "상대_격차");
        double stakes = multiplier(stakesMultipliers, stakesTier, "목숨_무게");
        return baseGrantDays * gap * stakes * Math.pow(1.0 - repetitionDecayRate, repetition);
    }

    /** 연속식 적립의 일일 상한 — 실전은 수련을 대체하지 않고 압축한다 (단발 보정은 별도 상한) */
    public double cappedGrant(double alreadyGrantedToday, double grant) {
        return Math.max(0.0, Math.min(grant, dailyCapDays - alreadyGrantedToday));
    }

    /** 숙련 n→n+1 필요 수련일 (효율 1.0) — 환산표에 없으면 -1: 수련만으로 도달 불가 구간 */
    public int skillLevelUpDays(int fromLevel) {
        Object cost = skillCosts.get(fromLevel + "_to_" + (fromLevel + 1));
        return cost == null ? -1 : durationToDays((String) cost);
    }

    /** 수련 적립(일치) = 실경과일 × 효율 곱연산 (방식 × 나이 × 스승 우위 …) — training.yml */
    public double trainingAccrualDays(double elapsedDays, double... efficiencyFactors) {
        double days = elapsedDays;
        for (double factor : efficiencyFactors) {
            days *= factor;
        }
        return days;
    }

    /** 능력치 +1 = 집중 단련 1년 — training.yml attribute_progression */
    public int attributePointDays() {
        return durationToDays("1년");
    }

    /** 화후 돌파 — 정수부가 오르는 날 ("오늘따라 손이 가볍다"). 판정은 정수, 자원은 실수 */
    public static boolean breakthrough(double before, double gainedProgress) {
        return Math.floor(before + gainedProgress) > Math.floor(before);
    }

    /** 적립 일수 → 진행도 (다음 단계 필요 일수 대비 비율) */
    public static double progressRatio(double grantedDays, double requiredDays) {
        return grantedDays / requiredDays;
    }

    /** 한국어 기간 → 일: 보름 15 / 개월 30 / 년 360 (게임 달력 환산) */
    public static int durationToDays(String duration) {
        if (duration.contains("보름")) {
            return 15;
        }
        Matcher m = DURATION.matcher(duration);
        int days = 0;
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            days += "년".equals(m.group(2)) ? n * 360 : n * 30;
        }
        if (days == 0) {
            throw new IllegalArgumentException("해석할 수 없는 기간: " + duration);
        }
        return days;
    }

    private static double multiplier(Map<String, Object> table, String key, String tableName) {
        Object value = table.get(key);
        if (value == null) {
            throw new IllegalArgumentException(tableName + "에 없는 등급: " + key);
        }
        return ((Number) value).doubleValue();
    }
}
