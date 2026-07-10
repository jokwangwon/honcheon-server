package com.honcheon.core.rules;

import java.util.Map;

/**
 * 동행(파티) 엔진 — config/party.yml 의 자바 구현.
 * 협력 판정 3형(조력/전원형/분담)과 협공. 성장 분배는 존재하지 않는다 —
 * 화후는 각자 쓴 무공에 쌓인다 (무경험치 설계의 귀결, ProgressionEngine 몫).
 */
public final class PartyEngine {

    private final int maxPartySize;
    private final int assistBonusPerHelper;
    private final int assistCap;
    private final int groupPenaltyFrom;
    private final int groupPenaltyValue;
    private final int coopBonusPerAttacker;
    private final int coopCap;
    private final int encirclementEscapePenalty;

    public PartyEngine(Map<String, Object> party) {
        Map<String, Object> formation = RulesConfig.section(party, "formation");
        Map<String, Object> tiers = RulesConfig.section(formation, "tiers");
        this.maxPartySize = RulesConfig.intValue(RulesConfig.section(tiers, "동행").get("max_size"));
        Map<String, Object> checks = RulesConfig.section(party, "cooperative_checks");
        Map<String, Object> assist = RulesConfig.section(checks, "조력");
        this.assistBonusPerHelper = RulesConfig.intValue(assist.get("bonus_per_helper"));
        this.assistCap = RulesConfig.intValue(assist.get("cap"));
        Map<String, Object> group = RulesConfig.section(RulesConfig.section(checks, "전원형"), "size_penalty");
        this.groupPenaltyFrom = RulesConfig.intValue(group.get("from"));
        this.groupPenaltyValue = RulesConfig.intValue(group.get("value"));
        Map<String, Object> combat = RulesConfig.section(party, "combat_coop");
        Map<String, Object> coop = RulesConfig.section(combat, "협공_보정");
        this.coopBonusPerAttacker = RulesConfig.intValue(coop.get("per_attacker"));
        this.coopCap = RulesConfig.intValue(coop.get("cap"));
        this.encirclementEscapePenalty =
                RulesConfig.intValue(RulesConfig.section(combat, "포위").get("target_escape_penalty"));
    }

    /** 동행 상한 — 그 이상은 세력의 영역 */
    public int maxPartySize() {
        return maxPartySize;
    }

    /** 조력 보정 — 해당 기술 1+ 보유 조력자만 센다 (필터는 호출자). 무기술 조력은 리스크만 */
    public int assistBonus(int skilledHelpers) {
        return Math.min(skilledHelpers * assistBonusPerHelper, assistCap);
    }

    /** 전원형 판정 실행력 — 최약자 기준 + 인원 페널티: 무리는 가장 서툰 발소리만큼 조용하다 */
    public int groupCheckPower(int[] memberPowers, int partySize) {
        int weakest = Integer.MAX_VALUE;
        for (int power : memberPowers) {
            weakest = Math.min(weakest, power);
        }
        return weakest + (partySize >= groupPenaltyFrom ? groupPenaltyValue : 0);
    }

    /** 협공 보정 — 같은 대상 추가 공격자당 +1 (상한 +3). 합격진은 별도 등록 무공이 상한을 올린다 */
    public int coopAttackBonus(int attackers) {
        return Math.min(Math.max(0, attackers - 1) * coopBonusPerAttacker, coopCap);
    }

    /** 포위 — 퇴로가 없으면 도주가 무겁다 */
    public int encirclementEscapePenalty() {
        return encirclementEscapePenalty;
    }
}
