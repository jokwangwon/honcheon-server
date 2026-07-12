package com.honcheon.bot;

import com.honcheon.core.rules.FactionReactionEngine;
import com.honcheon.core.rules.RulesConfig;

import java.util.List;
import java.util.Map;

/**
 * 세력 반응 — config/faction_reaction.yml 의 봇 쪽 손잡이 (단계 4 C).
 *
 * 두 축은 독립이다:
 *   주목(attention) "얼마나 신경 쓰는가" — 0~30, 7단계 (무관심 … 토벌)
 *   우호(favor)     "어느 편으로 보는가" — 0~30, 5단 (낯섦·안면·신용·공신·은인)
 * 주목 높고 우호 높음 = "유능하고 위험한 자" → 회유·영입 대상.
 * 주목 높고 우호 낮음 = 경고·협박·습격.
 *
 * 점수 계산은 core FactionReactionEngine (단계·등급 판정, 파리티 테스트 보유) 을 쓰고,
 * 여기서는 **입력 점수표·감쇠 규칙·단계 행동 지침**을 config 에서 읽어 봇에 물린다.
 * 상태는 엔진의 메모리가 아니라 faction_standing 테이블에 산다 (봇 재기동 생존).
 */
final class Factions {

    private final Map<String, Object> cfg;
    private final FactionReactionEngine engine;

    Factions(Map<String, Object> cfg) {
        this.cfg = cfg;
        this.engine = new FactionReactionEngine(cfg);
    }

    // ─── 주목(위협) 축 ───

    /** inputs.<키> — 소문_도달_관심일치 1 · 조직원_사망_또는_중상_확인 4 · 큰_공적_또는_구명 -4 … */
    int attentionInput(String key) {
        Object points = RulesConfig.section(cfg, "inputs").get(key);
        if (points == null) {
            throw new IllegalArgumentException("정의되지 않은 세력 반응 입력: " + key);
        }
        return RulesConfig.intValue(points);
    }

    /** 소문 도달 1건의 주목 점수 — 정확도 70 이상이면 고정확도(2), 미만이면 관심일치(1) */
    int rumorInput(int accuracy) {
        return accuracy >= 70 ? attentionInput("소문_도달_고정확도")
                : attentionInput("소문_도달_관심일치");
    }

    int scoreMax() {
        @SuppressWarnings("unchecked")
        List<Object> range = (List<Object>) RulesConfig.section(cfg, "score").get("range");
        return RulesConfig.intValue(range.get(1));
    }

    FactionReactionEngine.StageDef stageOf(int attention) {
        return engine.stageOf(attention);
    }

    /** stage_actions.<단계> — 세력이 그 단계에서 하는 일 (반드시 서사로 표현한다) */
    String stageAction(int stage) {
        Object action = RulesConfig.section(cfg, "stage_actions").get(stage);
        return action == null ? null : String.valueOf(action);
    }

    // ─── 감쇠 — 세계는 잊는다 (단, 기억 태그는 남는다) ───

    int attentionDecayEveryDays() {
        return RulesConfig.intValue(RulesConfig.section(cfg, "decay").get("every_days"));
    }

    /** decay.amount — 음수 (-1) */
    int attentionDecayAmount() {
        return RulesConfig.intValue(RulesConfig.section(cfg, "decay").get("amount"));
    }

    /** decay.floor_after_stage_3 — 접촉(3단계)까지 갔던 상대는 2단계 아래로 잊히지 않는다 */
    int attentionFloorAfterStage3() {
        return RulesConfig.intValue(RulesConfig.section(cfg, "decay").get("floor_after_stage_3"));
    }

    /**
     * 오늘 기준 주목 정산 — 마지막 갱신일로부터 경과분을 한 번에 깎는다 (읽는 순간 정산).
     * peakStage 3 이상 이력이 있으면 하한(4) 아래로 내려가지 않는다.
     * interplay: 우호 '공신'(13) 이상이면 감쇠 가속 (-2) — 믿는 자는 덜 감시한다.
     */
    int decayedAttention(int attention, int peakStage, int favor, int lastDay, int today) {
        int every = attentionDecayEveryDays();
        int ticks = every <= 0 ? 0 : Math.max(0, (today - lastDay) / every);
        if (ticks == 0) {
            return attention;
        }
        int perTick = Math.abs(attentionDecayAmount()) + (favor >= favorLevelMin("공신") ? 1 : 0);
        int floor = peakStage >= 3 ? attentionFloorAfterStage3() : 0;
        return Math.max(floor, attention - ticks * perTick);
    }

    // ─── 우호 축 (F3) ───

    /** favor.inputs.<키> — 공적_대 4 · 공적_소 2 · 협조 1 · 배신_적대 -4 */
    @SuppressWarnings("unchecked")
    int favorInput(String key) {
        Map<String, Object> favor = RulesConfig.section(cfg, "favor");
        Object points = ((Map<String, Object>) favor.get("inputs")).get(key);
        if (points == null) {
            throw new IllegalArgumentException("정의되지 않은 우호 입력: " + key);
        }
        return RulesConfig.intValue(points);
    }

    @SuppressWarnings("unchecked")
    int favorMax() {
        Map<String, Object> favor = RulesConfig.section(cfg, "favor");
        return RulesConfig.intValue(((List<Object>) favor.get("range")).get(1));
    }

    FactionReactionEngine.FavorLevel favorLevelOf(int favor) {
        return engine.favorLevelOf(favor);
    }

    /** favor.thresholds 에서 이름으로 문턱값 (안면 4 · 신용 8 · 공신 13 · 은인 19) */
    @SuppressWarnings("unchecked")
    int favorLevelMin(String levelName) {
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
    List<String> favorBenefits(int favor) {
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> favorDecay() {
        Map<String, Object> favor = RulesConfig.section(cfg, "favor");
        return (Map<String, Object>) favor.get("decay");
    }

    int favorDecayEveryDays() {
        return RulesConfig.intValue(favorDecay().get("every_days"));
    }

    /** favor.decay.floor_after_공신 — 공신(13) 도달 이력은 장부에 남는다 (신용 8 밑으로 안 잊힌다) */
    int favorFloorAfterGongsin() {
        return RulesConfig.intValue(favorDecay().get("floor_after_공신"));
    }

    /** 오늘 기준 우호 정산 — 은혜는 위협보다 천천히 잊힌다 (30일마다 -1) */
    int decayedFavor(int favor, int peakFavor, int lastDay, int today) {
        int every = favorDecayEveryDays();
        int ticks = every <= 0 ? 0 : Math.max(0, (today - lastDay) / every);
        if (ticks == 0) {
            return favor;
        }
        int amount = Math.abs(RulesConfig.intValue(favorDecay().get("amount")));
        int floor = peakFavor >= favorLevelMin("공신") ? favorFloorAfterGongsin() : 0;
        return Math.max(floor, favor - ticks * amount);
    }

    /**
     * 우호 등급 → 접근 가능한 의뢰 등급 상한 (thresholds[].benefits 의 문자열이 원천).
     * "하급 의뢰 접근"(안면) → 조사_채집 / "중급 의뢰"(신용) → 호위_소탕 / "상급 의뢰"(공신) → 표행_현상금.
     * 낯섦(0)은 잔심부름까지 — 세력 경유 의뢰는 이름을 아는 자에게만 간다.
     */
    String questGradeCap(int favor) {
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
}
