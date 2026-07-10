package com.honcheon.bot;

import com.honcheon.core.rules.EconomyEngine;
import com.honcheon.core.rules.JudgmentEngine;
import com.honcheon.core.rules.ProgressionEngine;
import com.honcheon.core.rules.RulesConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * config/*.yml 로더 + 룰 엔진 묶음 — 단일 진실 원천의 봇 쪽 손잡이.
 */
public final class Rules {

    public final JudgmentEngine judgment;
    public final ProgressionEngine progression;
    public final EconomyEngine economy;
    public final Map<String, Object> dispositionTest;
    public final Map<String, Object> playerCreation;
    private final Map<String, Object> economyCfg;

    @SuppressWarnings("unchecked")
    public Rules(Path configDir) {
        Map<String, Object> judgmentCfg = RulesConfig.load(configDir.resolve("judgment.yml"));
        this.judgment = new JudgmentEngine(judgmentCfg);
        this.progression = new ProgressionEngine(
                RulesConfig.load(configDir.resolve("cultivation.yml")),
                RulesConfig.load(configDir.resolve("training.yml")));
        this.economyCfg = RulesConfig.load(configDir.resolve("economy.yml"));
        this.economy = new EconomyEngine(economyCfg);
        this.dispositionTest = RulesConfig.load(configDir.resolve("disposition_test.yml"));
        this.playerCreation = RulesConfig.load(configDir.resolve("player_creation.yml"));
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> questions() {
        return (List<Map<String, Object>>) dispositionTest.get("questions");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> families() {
        Map<String, Object> lifepath = RulesConfig.section(playerCreation, "age_and_lifepath");
        return (Map<String, Object>) lifepath.get("families");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> incidents() {
        Map<String, Object> lifepath = RulesConfig.section(playerCreation, "age_and_lifepath");
        return (Map<String, Object>) lifepath.get("inciting_incidents");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> ageBrackets() {
        Map<String, Object> lifepath = RulesConfig.section(playerCreation, "age_and_lifepath");
        return (Map<String, Object>) lifepath.get("age_brackets");
    }

    /**
     * 시작 자금 — economy.yml starting_money: 연령대 범위 × 집안 배율.
     * 몰락_무가의_자식은 배율 항목이 없어 1.0 — 가보는 서사 자산이지 전낭이 아니다.
     */
    @SuppressWarnings("unchecked")
    public int startingMoney(String bracket, String family, Random dice) {
        Map<String, Object> sm = (Map<String, Object>) economyCfg.get("starting_money");
        List<Number> range = (List<Number>) sm.get(bracket);
        int min = range.get(0).intValue();
        int max = range.get(1).intValue();
        int base = min + dice.nextInt(max - min + 1);
        Map<String, Object> multipliers = (Map<String, Object>) sm.get("family_multiplier");
        double mult = multipliers.containsKey(family)
                ? ((Number) multipliers.get(family)).doubleValue() : 1.0;
        return Math.max(1, (int) Math.round(base * mult));
    }

    @SuppressWarnings("unchecked")
    public List<Integer> presetStats(String disposition) {
        Map<String, Object> presets = RulesConfig.section(playerCreation, "disposition_presets");
        Map<String, Object> preset = (Map<String, Object>) presets.get(disposition);
        if (preset == null) {
            preset = (Map<String, Object>) presets.get("협의형");
        }
        return (List<Integer>) preset.get("stats");
    }
}
