package com.honcheon.core.rules;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 내공 엔진 — config/internal_energy.yml 의 자바 구현.
 * 삼원 구조 중 후천진기(내력) 계층: 내력 풀(화후 규칙), 기 운용 경지 게이트, 다운캐스트.
 */
public final class InternalEnergyEngine {

    /** 축기 세월 곡선의 등록 이름 — internal_energy.yml {@code pool_curve} */
    private static final String CURVE_YEARS = "누적_축기_년수";

    private final Map<String, List<String>> realmGates;
    /** 내력 풀 배율 — internal_energy.yml {@code pool_per_year} (축기 1년치 = 내력 n). 코드가 짓지 않는다 */
    private final double poolPerYear;
    /** 풀 곡선 — {@code 누적_축기_년수} 면 x(x+1)/2, 아니면 선형(구판 호환) */
    private final boolean poolByYears;

    @SuppressWarnings("unchecked")
    public InternalEnergyEngine(Map<String, Object> config) {
        Map<String, Object> rawGates = RulesConfig.section(config, "realm_gates");
        Map<String, List<String>> parsed = new LinkedHashMap<>();
        rawGates.forEach((realm, bands) -> parsed.put(realm, List.copyOf((List<String>) bands)));
        this.realmGates = Collections.unmodifiableMap(parsed);

        Map<String, Object> inner = RulesConfig.section(config, "internal_energy");
        this.poolPerYear = inner.get("pool_per_year") instanceof Number n ? n.doubleValue() : 3.0;
        this.poolByYears = CURVE_YEARS.equals(String.valueOf(inner.get("pool_curve")));
    }

    /**
     * 내력 풀 = round(축기_세월(내공) × pool_per_year) — internal_energy.yml 정본.
     *
     * <p>축기_세월(x) = x(x+1)/2 년. cultivation.yml {@code accumulation_cost}("내공 n→n+1 = n년")의
     * 누적이고, simbeop.yml {@code magong.baseline.table} 이 이미 그 표를 적어 두었다
     * (내공 1=1년 · 3=6년 · 5=15년 · 7=28년 · 9=45년). <b>단전에 쌓이는 것은 단계가 아니라 세월이다.</b>
     *
     * <p>화후 규칙(실수 적립)은 그대로 — 양자화 절벽은 없다. 개화 직후(0.33)의 풀 1,
     * 일류(1.0)의 풀 3 은 구판과 같은 값이다. 넘치기 시작하는 것은 그 위다 (절정 18 · 화경 84).
     */
    public int pool(double naegongReal) {
        if (naegongReal <= 0) {
            return 0;
        }
        double years = poolByYears ? naegongReal * (naegongReal + 1.0) / 2.0 : naegongReal;
        return (int) Math.round(years * poolPerYear);
    }

    /**
     * 풀 → 내공 (역함수) — 등록부가 내력을 직접 적은 몸(npcs/*.yml {@code internal_energy})의
     * 내공을 되찾는다. 조식(내공에 비례)이 그 값을 필요로 한다.
     */
    public double naegongOf(int pool) {
        if (pool <= 0 || poolPerYear <= 0) {
            return 0.0;
        }
        double years = pool / poolPerYear;
        return poolByYears ? (Math.sqrt(1.0 + 8.0 * years) - 1.0) / 2.0 : years;
    }

    /** 기 운용 경지 게이트 — 알아도 경지가 낮으면 시전이 잠긴다 */
    public boolean canUse(String realm, String costBand) {
        List<String> allowed = realmGates.get(realm);
        if (allowed == null) {
            throw new IllegalArgumentException("정의되지 않은 경지: " + realm);
        }
        return allowed.contains(costBand);
    }

    /**
     * 다운캐스트 — 내력 부족 시 시전 불가가 아니라 '맨 기술'로 나간다.
     * @return 실제 소모량 (부족하면 0 — 맨 기술, 위력·속성 상실은 호출자가 처리)
     */
    public int payOrDowncast(int currentEnergy, int cost) {
        return currentEnergy >= cost ? cost : 0;
    }

    public boolean isDepleted(int currentEnergy) {
        return currentEnergy <= 0;
    }
}
