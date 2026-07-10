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

    private final Map<String, List<String>> realmGates;

    @SuppressWarnings("unchecked")
    public InternalEnergyEngine(Map<String, Object> config) {
        Map<String, Object> rawGates = RulesConfig.section(config, "realm_gates");
        Map<String, List<String>> parsed = new LinkedHashMap<>();
        rawGates.forEach((realm, bands) -> parsed.put(realm, List.copyOf((List<String>) bands)));
        this.realmGates = Collections.unmodifiableMap(parsed);
    }

    /** 내력 풀 = round(내공 실수치 × 3) — 화후 규칙 (양자화 절벽 제거) */
    public int pool(double naegongReal) {
        return (int) Math.round(naegongReal * 3.0);
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
