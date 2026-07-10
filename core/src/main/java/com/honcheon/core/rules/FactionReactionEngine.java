package com.honcheon.core.rules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 세력 반응 엔진 — config/faction_reaction.yml 의 자바 구현.
 * 두 축: 주목(score, 위협/관심 0~6단계) × 우호(favor, 낯섦~은인).
 * 점수는 세력 × 대상별로 관리한다 ("미상의 낭인" 가상 대상 포함).
 */
public final class FactionReactionEngine {

    public record StageDef(int stage, String name, int minScore) {
    }

    public record FavorLevel(int level, String name) {
    }

    private final Map<String, Integer> inputs;
    private final List<StageDef> stagesDesc;
    private final int maxScore;
    private final int decayFloorAfterStage3;

    private final Map<String, Integer> favorInputs;
    private final List<FavorLevel> favorLevelsDesc;
    private final int favorMax;

    private final Map<String, Integer> scores = new HashMap<>();
    private final Set<String> reachedStage3 = new HashSet<>();
    private final Map<String, Integer> favors = new HashMap<>();

    @SuppressWarnings("unchecked")
    public FactionReactionEngine(Map<String, Object> config) {
        Map<String, Object> rawInputs = RulesConfig.section(config, "inputs");
        Map<String, Integer> parsedInputs = new HashMap<>();
        rawInputs.forEach((key, value) -> parsedInputs.put(key, RulesConfig.intValue(value)));
        this.inputs = Map.copyOf(parsedInputs);

        List<Map<String, Object>> rawStages = (List<Map<String, Object>>) config.get("stage_thresholds");
        List<StageDef> stages = new ArrayList<>();
        for (Map<String, Object> raw : rawStages) {
            stages.add(new StageDef(RulesConfig.intValue(raw.get("stage")),
                    (String) raw.get("name"), RulesConfig.intValue(raw.get("min_score"))));
        }
        stages.sort((a, b) -> Integer.compare(b.minScore(), a.minScore()));
        this.stagesDesc = List.copyOf(stages);

        List<Integer> range = (List<Integer>) RulesConfig.section(config, "score").get("range");
        this.maxScore = range.get(1);
        this.decayFloorAfterStage3 = RulesConfig.intValue(
                RulesConfig.section(config, "decay").get("floor_after_stage_3"));

        Map<String, Object> favor = RulesConfig.section(config, "favor");
        Map<String, Integer> parsedFavorInputs = new HashMap<>();
        ((Map<String, Object>) favor.get("inputs"))
                .forEach((key, value) -> parsedFavorInputs.put(key, RulesConfig.intValue(value)));
        this.favorInputs = Map.copyOf(parsedFavorInputs);

        List<Map<String, Object>> rawLevels = (List<Map<String, Object>>) favor.get("thresholds");
        List<FavorLevel> levels = new ArrayList<>();
        for (Map<String, Object> raw : rawLevels) {
            levels.add(new FavorLevel(RulesConfig.intValue(raw.get("level")), (String) raw.get("name")));
        }
        levels.sort((a, b) -> Integer.compare(b.level(), a.level()));
        this.favorLevelsDesc = List.copyOf(levels);
        this.favorMax = ((List<Integer>) favor.get("range")).get(1);
    }

    private static String key(String faction, String target) {
        return faction + "|" + target;
    }

    // ── 주목(위협) 축 ──

    public int apply(String faction, String target, String inputKey) {
        Integer points = inputs.get(inputKey);
        if (points == null) {
            throw new IllegalArgumentException("정의되지 않은 세력 반응 입력: " + inputKey);
        }
        return applyPoints(faction, target, points);
    }

    public int applyPoints(String faction, String target, int points) {
        String key = key(faction, target);
        int updated = Math.max(0, Math.min(maxScore, scores.getOrDefault(key, 0) + points));
        scores.put(key, updated);
        if (stageOf(updated).stage() >= 3) {
            reachedStage3.add(key);
        }
        return updated;
    }

    public int score(String faction, String target) {
        return scores.getOrDefault(key(faction, target), 0);
    }

    public StageDef stageOf(int score) {
        for (StageDef stage : stagesDesc) {
            if (score >= stage.minScore()) {
                return stage;
            }
        }
        return stagesDesc.get(stagesDesc.size() - 1);
    }

    public int stage(String faction, String target) {
        return stageOf(score(faction, target)).stage();
    }

    /** 주기 감쇠 1회분 — 3단계(접촉) 도달 이력이 있으면 하한 적용 */
    public void decayTick() {
        scores.replaceAll((key, score) -> {
            int floor = reachedStage3.contains(key) ? decayFloorAfterStage3 : 0;
            return Math.max(floor, score - 1);
        });
    }

    // ── 우호 축 (F3) ──

    public int applyFavor(String faction, String target, String inputKey) {
        Integer points = favorInputs.get(inputKey);
        if (points == null) {
            throw new IllegalArgumentException("정의되지 않은 우호 입력: " + inputKey);
        }
        String key = key(faction, target);
        int updated = Math.max(0, Math.min(favorMax, favors.getOrDefault(key, 0) + points));
        favors.put(key, updated);
        return updated;
    }

    public int favor(String faction, String target) {
        return favors.getOrDefault(key(faction, target), 0);
    }

    public FavorLevel favorLevelOf(int favor) {
        for (FavorLevel level : favorLevelsDesc) {
            if (favor >= level.level()) {
                return level;
            }
        }
        return favorLevelsDesc.get(favorLevelsDesc.size() - 1);
    }

    public String favorLevelName(String faction, String target) {
        return favorLevelOf(favor(faction, target)).name();
    }

    /** 정체 확인 시 가상 대상("미상의 낭인")의 두 축 점수를 실제 대상으로 병합한다 */
    public void mergeTarget(String faction, String unknownTarget, String realTarget) {
        String from = key(faction, unknownTarget);
        Integer pending = scores.remove(from);
        if (pending != null) {
            applyPoints(faction, realTarget, pending);
        }
        if (reachedStage3.remove(from)) {
            reachedStage3.add(key(faction, realTarget));
        }
        Integer pendingFavor = favors.remove(from);
        if (pendingFavor != null) {
            String to = key(faction, realTarget);
            favors.put(to, Math.min(favorMax, favors.getOrDefault(to, 0) + pendingFavor));
        }
    }
}
