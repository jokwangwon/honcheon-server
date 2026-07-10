package com.honcheon.core.rules;

import java.util.List;
import java.util.Map;

/**
 * NPC 생애 엔진 — config/npc_lifecycle.yml 의 자바 구현.
 * 두 개의 시계: 일과(하루 5구간 — 활동별 판정 컨텍스트)와 생애(계절 1회 — 사다리 정산).
 * NPC 판정 문법 그대로: 능력치 + 기술 + 상황 + 7 (주사위 없음 — 입력이 세계 상태라 결과는 살아 있다).
 */
public final class NpcLifecycleEngine {

    private final Map<String, Object> ladders;
    private final Map<String, Object> activityModifiers;
    private final int nightIntrusionAlertnessDelta;
    private final int helpMin;
    private final int helpMax;
    private final int npcFixedBonus;

    @SuppressWarnings("unchecked")
    public NpcLifecycleEngine(Map<String, Object> lifecycle, Map<String, Object> judgment) {
        Map<String, Object> life = RulesConfig.section(lifecycle, "life_simulation");
        this.ladders = RulesConfig.section(life, "ladders");
        Map<String, Object> schedule = RulesConfig.section(lifecycle, "schedule");
        this.activityModifiers = RulesConfig.section(schedule, "activity_context_modifiers");
        Map<String, Object> night = RulesConfig.section(schedule, "night_intrusion");
        this.nightIntrusionAlertnessDelta = RulesConfig.intValue(night.get("alertness_delta"));
        Map<String, Object> hand = RulesConfig.section(life, "player_hand");
        List<Number> bonus = (List<Number>) RulesConfig.section(hand, "help").get("bonus");
        this.helpMin = bonus.get(0).intValue();
        this.helpMax = bonus.get(1).intValue();
        Map<String, Object> formula = RulesConfig.section(judgment, "formula");
        this.npcFixedBonus = RulesConfig.intValue(formula.get("npc_fixed_bonus"));
    }

    // ─── 생애 시계 ───

    /** 사다리 다음 칸의 난이도 — 구조화된 사다리(상인의_길 등)만. 무인의_길은 cultivation 관문 위임 */
    public int rungDifficulty(String ladder, String rungName) {
        for (Map<String, Object> rung : rungs(ladder)) {
            if (rungName.equals(rung.get("name"))) {
                return RulesConfig.intValue(rung.get("difficulty"));
            }
        }
        throw new IllegalArgumentException(ladder + "에 없는 칸: " + rungName);
    }

    /** 현재 칸의 다음 칸 이름 — 정점이면 null (그 위는 세계급 등록 이벤트의 영역) */
    public String nextRung(String ladder, String currentRung) {
        List<Map<String, Object>> list = rungs(ladder);
        for (int i = 0; i < list.size() - 1; i++) {
            if (currentRung.equals(list.get(i).get("name"))) {
                return (String) list.get(i + 1).get("name");
            }
        }
        return null;
    }

    /** NPC 생애 판정력 = 능력치 + 기술 + 상황(자산·경제·favor·개입) + 7 — 주사위 없음 */
    public int lifeCheckPower(int attribute, int skill, int situation) {
        return attribute + skill + situation + npcFixedBonus;
    }

    /** 계절 정산 마진 — 0 이상이면 상승 궤적 적립, 음수 누적은 하락 판정으로 */
    public int seasonMargin(int lifeCheckPower, int nextRungDifficulty) {
        return lifeCheckPower - nextRungDifficulty;
    }

    /** 플레이어 개입 보정 클램프 — 은혜도 원한도 ±3을 넘지 않는다 (기록은 무제한, 계절 반영은 캡) */
    public int clampPlayerHand(int recordedBonus) {
        return Math.max(-helpMax, Math.min(helpMax, recordedBonus));
    }

    public int helpBonusMin() {
        return helpMin;
    }

    // ─── 일과 시계 ───

    /** 활동별 판정 컨텍스트 보정 — 만남의 '때'를 고르는 것이 전략이 된다 (환경 보정 내 계상) */
    @SuppressWarnings("unchecked")
    public int activityModifier(String activity, String judgmentType) {
        Object mods = activityModifiers.get(activity);
        if (!(mods instanceof Map)) {
            return 0;
        }
        Object value = ((Map<String, Object>) mods).get(judgmentType);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    /** 밤 침소의 경계 변화 — 싸게 들어가면 비싸게 나온다 (발각 시 적대·소문은 호출자 처리) */
    public int nightIntrusionAlertnessDelta() {
        return nightIntrusionAlertnessDelta;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rungs(String ladder) {
        Object entry = ladders.get(ladder);
        if (!(entry instanceof Map)) {
            throw new IllegalArgumentException("구조화된 사다리가 아닙니다 (위임 사다리): " + ladder);
        }
        return (List<Map<String, Object>>) ((Map<String, Object>) entry).get("rungs");
    }
}
