package com.honcheon.core.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 세력 반응 규칙 — config/faction_reaction.yml 의 자바 판독기.
 * 두 축: 주목(attention, 0~30 · 7단계 무관심…토벌) × 우호(favor, 0~30 · 낯섦…은인).
 *
 * <p><b>★ 이 클래스는 장부가 아니다. 규칙이다.</b>
 *
 * <p>예전에는 여기에 {@code scores}·{@code favors}·{@code reachedStage3} 맵이 있었다 — 그리고
 * 봇의 {@code faction_standing} 표에도 같은 값이 있었다. 같은 것을 세는 장부가 둘이면 언젠가
 * <b>두 세계가 갈라진다.</b> 그리고 실제로 갈라져 있었다: 이 맵들은 <b>테스트만 읽었고</b>
 * (프로덕션 호출 0), 그 사이 봇의 {@code Db.addFavor}·{@code Factions.decayedAttention} 이
 * <b>같은 산수를 다시 구현해</b> 플레이어를 상대했다. 파리티 테스트는 초록이었지만
 * 그 테스트가 지키던 것은 <b>아무도 쓰지 않는 코드</b>였다.
 *
 * <p>그래서 정본을 하나로 잘랐다 ({@link RegionStateEngine} 과 같은 처방):
 * <ul>
 *   <li><b>장부는 봇의 SQLite</b> ({@code faction_standing} 표) — 재기동을 넘어 살아남는 유일한 곳.
 *       도메인의 {@code FactionLedger} 포트가 그 문이다.</li>
 *   <li><b>규칙은 여기 하나</b> — 클램프 상한·단계 문턱·우호 등급·감쇠(하한 포함)·입력 점수표.
 *       상태를 들고 있지 않으므로 <b>갈라질 수가 없다.</b></li>
 * </ul>
 *
 * <p>부르는 쪽이 제 장부의 현재값을 넘기고, 이 클래스는 <b>다음 값만 돌려준다</b>.
 * 적는 것은 장부 주인의 일이다.
 *
 * <p><b>감쇠는 배치 잡이 아니다 — 읽는 순간 정산한다</b> ({@code decayedAttention}/{@code decayedFavor}).
 * 같은 세계일에 몇 번을 읽어도 같은 값이 나온다 (결정론). 잡이 두 번 돌아 두 번 깎이는 사고가 없다.
 */
public final class FactionReactionEngine {

    public record StageDef(int stage, String name, int minScore) {
    }

    public record FavorLevel(int level, String name) {
    }

    private final Map<String, Object> cfg;

    private final Map<String, Integer> inputs;
    private final List<StageDef> stagesDesc;
    private final int maxScore;
    private final int attentionDecayEveryDays;
    private final int attentionDecayAmount;
    private final int decayFloorAfterStage3;

    private final Map<String, Integer> favorInputs;
    private final List<FavorLevel> favorLevelsDesc;
    private final int favorMax;
    private final int favorDecayEveryDays;
    private final int favorDecayAmount;
    private final int favorFloorAfterGongsin;

    @SuppressWarnings("unchecked")
    public FactionReactionEngine(Map<String, Object> config) {
        this.cfg = config;

        Map<String, Object> rawInputs = RulesConfig.section(config, "inputs");
        Map<String, Integer> parsedInputs = new java.util.HashMap<>();
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

        List<Object> range = (List<Object>) RulesConfig.section(config, "score").get("range");
        this.maxScore = RulesConfig.intValue(range.get(1));

        Map<String, Object> decay = RulesConfig.section(config, "decay");
        this.attentionDecayEveryDays = RulesConfig.intValue(decay.get("every_days"));
        this.attentionDecayAmount = RulesConfig.intValue(decay.get("amount"));
        this.decayFloorAfterStage3 = RulesConfig.intValue(decay.get("floor_after_stage_3"));

        Map<String, Object> favor = RulesConfig.section(config, "favor");
        Map<String, Integer> parsedFavorInputs = new java.util.HashMap<>();
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
        this.favorMax = RulesConfig.intValue(((List<Object>) favor.get("range")).get(1));

        Map<String, Object> favorDecay = (Map<String, Object>) favor.get("decay");
        this.favorDecayEveryDays = RulesConfig.intValue(favorDecay.get("every_days"));
        this.favorDecayAmount = RulesConfig.intValue(favorDecay.get("amount"));
        this.favorFloorAfterGongsin = RulesConfig.intValue(favorDecay.get("floor_after_공신"));
    }

    // ─── 주목(위협) 축 ───

    /** inputs.&lt;키&gt; — 소문_도달_관심일치 1 · 조직원_사망_또는_중상_확인 4 · 큰_공적_또는_구명 -4 … */
    public int attentionInput(String key) {
        Integer points = inputs.get(key);
        if (points == null) {
            throw new IllegalArgumentException("정의되지 않은 세력 반응 입력: " + key);
        }
        return points;
    }

    /** 소문 도달 1건의 주목 점수 — 정확도 70 이상이면 고정확도(2), 미만이면 관심일치(1) */
    public int rumorInput(int accuracy) {
        return accuracy >= 70 ? attentionInput("소문_도달_고정확도")
                : attentionInput("소문_도달_관심일치");
    }

    /**
     * 주목 가산 한 걸음 — <b>부르는 쪽의 장부값</b>에 얹는다. [0, scoreMax] 클램프.
     * 상태를 기억하지 않으므로 봇의 표와 갈라질 수가 없다.
     */
    public int nextAttention(int current, int delta) {
        return Math.max(0, Math.min(maxScore, current + delta));
    }

    public StageDef stageOf(int score) {
        for (StageDef stage : stagesDesc) {
            if (score >= stage.minScore()) {
                return stage;
            }
        }
        return stagesDesc.get(stagesDesc.size() - 1);
    }

    /** stage_actions.&lt;단계&gt; — 세력이 그 단계에서 하는 일 (반드시 서사로 표현한다) */
    public String stageAction(int stage) {
        Object action = RulesConfig.section(cfg, "stage_actions").get(stage);
        return action == null ? null : String.valueOf(action);
    }

    // ─── 감쇠 — 세계는 잊는다 (단, 이력의 하한은 남는다) ───

    /**
     * 오늘 기준 주목 정산 — 마지막 갱신일로부터 경과분을 한 번에 깎는다 (읽는 순간 정산).
     *
     * <p>{@code peakStage} 3 이상 이력이 있으면 하한(4) 아래로 내려가지 않는다 — 그 하한이
     * 예전 {@code reachedStage3} 집합이 하던 일이다. 다만 그 집합은 <b>봇이 재기동하면 사라졌고</b>,
     * {@code peak_stage} 열은 살아남는다. 그래서 장부가 이겼다.
     *
     * <p>interplay: 우호 '공신'(13) 이상이면 감쇠 가속 (-2) — 믿는 자는 덜 감시한다.
     */
    public int decayedAttention(int attention, int peakStage, int favor, int lastDay, int today) {
        int every = attentionDecayEveryDays;
        int ticks = every <= 0 ? 0 : Math.max(0, (today - lastDay) / every);
        if (ticks == 0) {
            return attention;
        }
        int perTick = Math.abs(attentionDecayAmount) + (favor >= favorLevelMin("공신") ? 1 : 0);
        int floor = peakStage >= 3 ? decayFloorAfterStage3 : 0;
        return Math.max(floor, attention - ticks * perTick);
    }

    // ─── 우호 축 (F3) ───

    /** favor.inputs.&lt;키&gt; — 공적_대 4 · 공적_소 2 · 협조 1 · 배신_적대 -4 */
    public int favorInput(String key) {
        Integer points = favorInputs.get(key);
        if (points == null) {
            throw new IllegalArgumentException("정의되지 않은 우호 입력: " + key);
        }
        return points;
    }

    /** favor.range 상한 (30) */
    public int favorMax() {
        return favorMax;
    }

    /**
     * 우호 가산 한 걸음 — [0, min(cap, favorMax)] 클램프.
     *
     * <p>{@code cap} 은 <b>부르는 쪽이 아는 상한</b>이다 (입문 루트의 잔심부름 상한 등 —
     * faction_entry_routes.yml 이 정한다). 상한이 따로 없으면 {@link #favorMax()} 를 넘긴다.
     */
    public int nextFavor(int current, int delta, int cap) {
        return Math.max(0, Math.min(Math.min(cap, favorMax), current + delta));
    }

    public FavorLevel favorLevelOf(int favor) {
        for (FavorLevel level : favorLevelsDesc) {
            if (favor >= level.level()) {
                return level;
            }
        }
        return favorLevelsDesc.get(favorLevelsDesc.size() - 1);
    }

    /** favor.thresholds 에서 이름으로 문턱값 (안면 4 · 신용 8 · 공신 13 · 은인 19) */
    @SuppressWarnings("unchecked")
    public int favorLevelMin(String levelName) {
        Map<String, Object> favor = RulesConfig.section(cfg, "favor");
        for (Object raw : (List<Object>) favor.get("thresholds")) {
            Map<String, Object> level = (Map<String, Object>) raw;
            if (levelName.equals(level.get("name"))) {
                return RulesConfig.intValue(level.get("level"));
            }
        }
        throw new IllegalArgumentException("등록되지 않은 우호 등급: " + levelName);
    }

    /** favor.thresholds[].benefits — 그 등급이 여는 문 ("중급 의뢰", "추천장" …) */
    @SuppressWarnings("unchecked")
    public List<String> favorBenefits(int favor) {
        Map<String, Object> section = RulesConfig.section(cfg, "favor");
        List<String> benefits = List.of();
        for (Object raw : (List<Object>) section.get("thresholds")) {
            Map<String, Object> level = (Map<String, Object>) raw;
            if (favor >= RulesConfig.intValue(level.get("level"))
                    && level.get("benefits") instanceof List<?> list) {
                benefits = list.stream().map(String::valueOf).toList();
            }
        }
        return benefits;
    }

    /** 오늘 기준 우호 정산 — 은혜는 위협보다 천천히 잊힌다 (30일마다 -1) */
    public int decayedFavor(int favor, int peakFavor, int lastDay, int today) {
        int every = favorDecayEveryDays;
        int ticks = every <= 0 ? 0 : Math.max(0, (today - lastDay) / every);
        if (ticks == 0) {
            return favor;
        }
        int amount = Math.abs(favorDecayAmount);
        int floor = peakFavor >= favorLevelMin("공신") ? favorFloorAfterGongsin : 0;
        return Math.max(floor, favor - ticks * amount);
    }

    /**
     * 우호 등급 → 접근 가능한 의뢰 등급 상한 (thresholds[].benefits 의 문자열이 원천).
     * "하급 의뢰 접근"(안면) → 조사_채집 / "중급 의뢰"(신용) → 호위_소탕 / "상급 의뢰"(공신) → 표행_현상금.
     * 낯섦(0)은 잔심부름까지 — 세력 경유 의뢰는 이름을 아는 자에게만 간다.
     */
    public String questGradeCap(int favor) {
        List<String> benefits = favorBenefits(favor);
        if (benefits.stream().anyMatch(b -> b.contains("상급 의뢰"))) {
            return "표행_현상금";
        }
        if (benefits.stream().anyMatch(b -> b.contains("중급 의뢰"))) {
            return "호위_소탕";
        }
        if (benefits.stream().anyMatch(b -> b.contains("하급 의뢰"))) {
            return "조사_채집";
        }
        return "잔심부름";
    }

    // ─── 정체 확인 — 가상 대상("미상의 낭인")의 두 축을 실제 대상으로 옮긴다 ───

    /** 병합 결과 — 두 축의 다음 값 (부르는 쪽이 제 장부에 적는다) */
    public record Merged(int attention, int favor, boolean carriesStage3Floor) {
    }

    /**
     * ★ <b>규칙은 있고 기능은 없다.</b> "미상의 낭인"으로 쌓인 주목·우호를 정체가 밝혀진 순간
     * 실제 인물에게 옮기는 규칙이다 — 그런데 <b>봇에 정체 확인 경로가 아직 없다</b>.
     * 그래서 이 메서드는 프로덕션 호출자가 없다. 그것은 죽은 쌍둥이가 아니라 <b>미착수 기능</b>이다
     * (규칙을 지우면 설계가 사라진다 — 그래서 남긴다. 청구서는 보고서에 있다).
     *
     * @param carried 가상 대상에 쌓인 값 (주목·우호), {@code carriedPeakStage} 는 그 이력
     * @param target  실제 대상의 현재 값
     */
    public Merged mergeIdentity(int carriedAttention, int carriedFavor, int carriedPeakStage,
                        int targetAttention, int targetFavor) {
        return new Merged(nextAttention(targetAttention, carriedAttention),
                Math.min(favorMax, targetFavor + carriedFavor),
                carriedPeakStage >= 3);
    }
}
