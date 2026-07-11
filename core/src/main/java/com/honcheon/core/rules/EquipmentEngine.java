package com.honcheon.core.rules;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 장비 엔진 — config/equipment.yml 의 자바 구현.
 * 대원칙: 장비는 격차가 아니라 속성과 서사를 만든다 — 판정 보정 총합 캡이 그 집행 장치.
 * 감당 격의 원천은 qi_manifestation.yml — 본 엔진은 상향 취급(각인·애병·마병) 합산만 준다.
 */
public final class EquipmentEngine {

    /** 마병 흡수 — 적중 시 상대 원기·자기 내력의 변화 */
    public record Drain(int targetVitality, int selfInternalEnergy) { }

    private final int bonusCap;
    private final int trinketSlots;
    private final Map<String, Map<String, Object>> weapons;
    private final Map<String, Map<String, Object>> armors;
    private final Map<String, Map<String, Object>> belovedStages;
    private final Map<String, Object> demonicPower;

    @SuppressWarnings("unchecked")
    public EquipmentEngine(Map<String, Object> config) {
        Map<String, Object> caps = RulesConfig.section(config, "caps");
        this.bonusCap = ((Number) caps.get("equipment_judgment_bonus_total")).intValue();
        this.trinketSlots = ((Number) caps.get("trinket_slots")).intValue();
        this.weapons = subMaps(RulesConfig.section(config, "weapon_grades"));
        this.armors = subMaps(RulesConfig.section(config, "armor"));
        Map<String, Object> beloved = RulesConfig.section(config, "beloved_weapon");
        this.belovedStages = subMaps((Map<String, Object>) beloved.get("stages"));
        Map<String, Object> demonic = RulesConfig.section(config, "demonic_weapons");
        this.demonicPower = Collections.unmodifiableMap((Map<String, Object>) demonic.get("power"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> subMaps(Map<String, Object> raw) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (v instanceof Map<?, ?> m) {
                out.put(k, (Map<String, Object>) m);
            }
        });
        return Collections.unmodifiableMap(out);
    }

    /** 장비 유래 판정 보정 총합 캡 — 상황보정 캡 ±5 내부 계상 */
    public int cappedBonus(int requestedTotal) {
        return Math.min(bonusCap, requestedTotal);
    }

    public int trinketSlots() {
        return trinketSlots;
    }

    /** 무기 등급의 판정 보정 — 범철·정련 0, 보병·신병 1 (가치는 보정이 아니라 생존) */
    public int weaponJudgmentBonus(String grade) {
        Object v = weapon(grade).get("judgment_bonus");
        return v instanceof Number n ? n.intValue() : 0;
    }

    public int weaponPropertySlots(String grade) {
        Object v = weapon(grade).get("property_slots");
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** 방어구 — 부상 등급 1단 완화가 기본 의미 (격 상성은 못 이긴다) */
    public int armorMitigation(String name) {
        return ((Number) armor(name).get("mitigation")).intValue();
    }

    /** "갑옷은 회피를 판다" — 격 상성 원칙 2의 뒷면 */
    public int armorDodgePenalty(String name) {
        return ((Number) armor(name).get("dodge_penalty")).intValue();
    }

    /** 애병 단계별 주인 한정 판정 보정 (캡 계상) — 강탈한 남의 애병은 등급값 */
    @SuppressWarnings("unchecked")
    public int belovedOwnerBonus(String stage) {
        Map<String, Object> grants = (Map<String, Object>) beloved(stage).get("grants");
        Object v = grants.get("judgment_bonus_owner_only");
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** 애병 단계의 감당 격 상향 취급 — 통령 1 (정련→보병급), 그 외 0 */
    @SuppressWarnings("unchecked")
    public int belovedWithstandRankUp(String stage) {
        Map<String, Object> grants = (Map<String, Object>) beloved(stage).get("grants");
        Object v = grants.get("withstand_rank_up");
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** 마병의 감당 격 상향 취급 */
    public int demonicWithstandRankUp() {
        return ((Number) demonicPower.get("withstand_rank_up")).intValue();
    }

    public int demonicDamageBonus() {
        return ((Number) demonicPower.get("damage_bonus")).intValue();
    }

    /** 마병 흡수 — 적중 시 (사용 = 마공 분류 ① 타인 원기 약탈) */
    @SuppressWarnings("unchecked")
    public Drain demonicDrain() {
        Map<String, Object> drain = (Map<String, Object>) demonicPower.get("drain");
        return new Drain(((Number) drain.get("target_vitality")).intValue(),
                ((Number) drain.get("self_internal_energy")).intValue());
    }

    private Map<String, Object> weapon(String grade) {
        Map<String, Object> w = weapons.get(grade);
        if (w == null) {
            throw new IllegalArgumentException("정의되지 않은 무기 등급: " + grade);
        }
        return w;
    }

    private Map<String, Object> armor(String name) {
        Map<String, Object> a = armors.get(name);
        if (a == null) {
            throw new IllegalArgumentException("정의되지 않은 방어구: " + name);
        }
        return a;
    }

    private Map<String, Object> beloved(String stage) {
        Map<String, Object> s = belovedStages.get(stage);
        if (s == null) {
            throw new IllegalArgumentException("정의되지 않은 애병 단계: " + stage);
        }
        return s;
    }
}
