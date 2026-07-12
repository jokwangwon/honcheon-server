package com.honcheon.bot;

import com.honcheon.core.rules.RulesConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 세력 입문 루트 — config/faction_entry_routes.yml 의 봇 쪽 손잡이.
 *
 * 제1원칙(레일 금지)의 집행자: direct_approach 는 항상 열려 있고, 조건 미달의 결과는
 * '진행 불가'가 아니라 세계의 반응(잡역 제안 · 난이도 상승 · 축객 + 낮은 문 안내)이다.
 * 수치는 전부 yml 에서 읽는다 — 여기에 하드코딩된 숫자는 없다.
 */
final class Routes {

    private final Map<String, Object> cfg;

    Routes(Map<String, Object> cfg) {
        this.cfg = cfg;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> route(String routeId) {
        return (Map<String, Object>) RulesConfig.section(cfg, "routes").get(routeId);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> directApproach(String routeId) {
        return (Map<String, Object>) route(routeId).get("direct_approach");
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> branch(String routeId, String branchKey) {
        Map<String, Object> branches = (Map<String, Object>) directApproach(routeId).get("branches");
        return (Map<String, Object>) branches.get(branchKey);
    }

    /** 즉석 심사의 벽 — hwasan_entry.direct_approach.branches.경지_있음_추천_없음.difficulty_modifier */
    int walkInDifficultyModifier(String routeId) {
        Object mod = branch(routeId, "경지_있음_추천_없음").get("difficulty_modifier");
        return mod instanceof Number n ? n.intValue() : 0;
    }

    /** 문전 잡역 favor 상한 — detail.favor_gain 문장에서 (상한 8) 을 읽는다 */
    int choreFavorCap(String routeId) {
        Map<String, Object> detail = choreDetail(routeId);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("상한\\s*(\\d+)")
                .matcher(String.valueOf(detail.get("favor_gain")));
        return m.find() ? Integer.parseInt(m.group(1)) : 8;
    }

    /** 심사 자격 자동 획득 누적 일수 — detail.auto_qualify "30일 누적 시 …" */
    int choreAutoQualifyDays(String routeId) {
        Map<String, Object> detail = choreDetail(routeId);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*일")
                .matcher(String.valueOf(detail.get("auto_qualify")));
        return m.find() ? Integer.parseInt(m.group(1)) : 30;
    }

    /** 이탈해도 남는 것 — detail.on_abandon "…재방문 시 심사 난이도 -2" */
    int watchedTagDiscount(String routeId) {
        Map<String, Object> detail = choreDetail(routeId);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("난이도\\s*-\\s*(\\d+)")
                .matcher(String.valueOf(detail.get("on_abandon")));
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** 잡역 제안이 붙이는 태그 — detail.tag (눈여겨봄) */
    String choreTag(String routeId) {
        return String.valueOf(choreDetail(routeId).get("tag"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> choreDetail(String routeId) {
        return (Map<String, Object>) branch(routeId, "조건_전무").get("detail");
    }

    /** 안면 게이트 favor 문턱 — gates[안면].condition.favor.min */
    @SuppressWarnings("unchecked")
    int gateFavorMin(String routeId, String gateId) {
        for (Object gate : (List<Object>) route(routeId).get("gates")) {
            Map<String, Object> g = (Map<String, Object>) gate;
            if (gateId.equals(g.get("id")) && g.get("condition") instanceof Map<?, ?> cond
                    && ((Map<String, Object>) cond).get("favor") instanceof Map<?, ?> favor) {
                return RulesConfig.intValue(((Map<String, Object>) favor).get("min"));
            }
        }
        return 4;
    }

    /**
     * 게이트의 소문 열쇠 — gates[gateId].condition_any 안의 rumor.intensity_min (없으면 fallback).
     * 화산 추천 게이트 열쇠 ③: '재목' 소문이 강도 2 이상이면 문파가 먼저 찾아온다.
     */
    @SuppressWarnings("unchecked")
    int gateRumorIntensity(String routeId, String gateId, int fallback) {
        for (Object gate : (List<Object>) route(routeId).get("gates")) {
            Map<String, Object> g = (Map<String, Object>) gate;
            if (!gateId.equals(g.get("id")) || !(g.get("condition_any") instanceof List<?> any)) {
                continue;
            }
            for (Object c : any) {
                Map<String, Object> cond = (Map<String, Object>) c;
                if (cond.get("rumor") instanceof Map<?, ?> rumor) {
                    Object min = ((Map<String, Object>) rumor).get("intensity_min");
                    if (min instanceof Number n) {
                        return n.intValue();
                    }
                }
            }
        }
        return fallback;
    }

    /** 단서 소지 분기의 열쇠 품목 — branches.단서_소지.condition.item_any */
    @SuppressWarnings("unchecked")
    List<String> clueItems(String routeId) {
        Map<String, Object> cond = (Map<String, Object>) branch(routeId, "단서_소지").get("condition");
        return ((List<Object>) cond.get("item_any")).stream().map(String::valueOf).toList();
    }

    /** 악명 분기 — condition_any: 하오문 favor >= n / 살인 태그 소문 강도 >= n */
    @SuppressWarnings("unchecked")
    Infamy infamy(String routeId) {
        int haomunFavor = 8;
        int rumorIntensity = 3;
        String tag = "살인";
        for (Object c : (List<Object>) branch(routeId, "악명_보유").get("condition_any")) {
            Map<String, Object> cond = (Map<String, Object>) c;
            if (cond.get("favor") instanceof Map<?, ?> favor) {
                haomunFavor = RulesConfig.intValue(((Map<String, Object>) favor).get("min"));
            }
            if (cond.get("rumor") instanceof Map<?, ?> rumor) {
                Map<String, Object> r = (Map<String, Object>) rumor;
                rumorIntensity = RulesConfig.intValue(r.get("intensity_min"));
                if (r.get("tags") instanceof List<?> tags && !tags.isEmpty()) {
                    tag = String.valueOf(tags.get(0));
                }
            }
        }
        List<String> lowDoors = ((List<Object>) branch(routeId, "악명_보유").get("still_offered"))
                .stream().map(String::valueOf).toList();
        return new Infamy(haomunFavor, rumorIntensity, tag, lowDoors);
    }

    record Infamy(int haomunFavorMin, int rumorIntensityMin, String rumorTag, List<String> lowDoors) {
    }

    /** 직행 시도 자체가 남기는 소문 — rumor_on_attempt (강도 [1,2] · orthodox_net·inn_net) */
    @SuppressWarnings("unchecked")
    Attempt rumorOnAttempt(String routeId) {
        Map<String, Object> spec = (Map<String, Object>) directApproach(routeId).get("rumor_on_attempt");
        List<Object> range = (List<Object>) spec.get("intensity");
        List<String> nets = ((List<Object>) spec.get("networks")).stream().map(String::valueOf).toList();
        return new Attempt(RulesConfig.intValue(range.get(0)), RulesConfig.intValue(range.get(1)),
                nets, String.valueOf(spec.get("text")));
    }

    record Attempt(int intensityMin, int intensityMax, List<String> networks, String text) {
    }

    /**
     * 루트 상태 → 의뢰 주입 규칙 (quest_injection).
     * when 이 favor_min · tag 둘 중 하나인 항목만 봇이 판독한다 (알파 범위).
     */
    @SuppressWarnings("unchecked")
    List<Injection> questInjections(String routeId) {
        List<Injection> out = new ArrayList<>();
        Object raw = route(routeId).get("quest_injection");
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            Map<String, Object> entry = (Map<String, Object>) item;
            Object when = entry.get("when");
            Object inject = entry.get("inject");
            if (!(when instanceof Map<?, ?> w) || !(inject instanceof String key)) {
                continue;   // 리스트형 inject·사건형 when 은 후속 (마교 루트)
            }
            Map<String, Object> cond = (Map<String, Object>) w;
            Integer favorMin = cond.get("favor_min") instanceof Number n ? n.intValue() : null;
            String tag = cond.get("tag") instanceof String t ? t : null;
            out.add(new Injection(key, favorMin, tag, String.valueOf(entry.get("channel"))));
        }
        return out;
    }

    /** inject = 의뢰 후보 키 · when = favor 문턱 또는 상태 태그 · channel = 게시 경로 */
    record Injection(String inject, Integer favorMin, String tag, String channel) {
    }

    // ═══ 관군 루트 (9번째) — ★ 입문이 아니라 임용(任用). 무림이 아닌 층위로 건너간다 ═══
    //
    // 다른 여덟 루트는 강호 **안**의 문이다. 이 문만 강호 **밖**으로 난다.
    // 그래서 이 루트의 대가는 favor 가 아니라 **눈빛**이다 — murim_gaze.
    // "관에 붙었군." 아무도 욕하지 않는다. 다만 등 뒤로 '관의 개'라는 말이 돈다.

    static final String GWANGUN = "gwangun_entry";

    /** gates[gateId].condition(.favor.min) 또는 condition_any 의 최소 favor — 포쾌 4 · 포두 8 · 무과 13 */
    @SuppressWarnings("unchecked")
    int gwangunGateFavor(String gateId, int fallback) {
        for (Object gate : (List<Object>) route(GWANGUN).get("gates")) {
            Map<String, Object> g = (Map<String, Object>) gate;
            if (!gateId.equals(g.get("id"))) {
                continue;
            }
            Integer min = favorMin(g.get("condition"));
            if (min != null) {
                return min;
            }
            if (g.get("condition_any") instanceof List<?> any) {
                for (Object c : any) {
                    Integer m = favorMin(c);
                    if (m != null) {
                        return m;
                    }
                }
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private Integer favorMin(Object condition) {
        if (condition instanceof Map<?, ?> m
                && ((Map<String, Object>) m).get("favor") instanceof Map<?, ?> favor) {
            Object min = ((Map<String, Object>) favor).get("min");
            return min instanceof Number n ? n.intValue() : null;
        }
        return null;
    }

    /**
     * ★ 강호가 관군 플레이어를 어떻게 보는가 — murim_gaze.by_gate.&lt;게이트&gt;.
     * 포쾌 등재만으로 정파 -2 / 사파 -6. 포두는 -4 / -10. 무과를 넘으면 -8 + 사파 적대 고정.
     * 이것이 이 루트의 값이다: **안전하되 외롭다.**
     */
    @SuppressWarnings("unchecked")
    Gaze gaze(String gateId) {
        Map<String, Object> gaze = (Map<String, Object>) route(GWANGUN).get("murim_gaze");
        Map<String, Object> byGate = (Map<String, Object>) gaze.get("by_gate");
        Object e = byGate.get(gateId);
        if (!(e instanceof Map<?, ?> m)) {
            return new Gaze(0, 0, null);
        }
        Map<String, Object> g = (Map<String, Object>) m;
        return new Gaze(intOr(g.get("정파_favor")), intOr(g.get("사파_favor")),
                g.get("note") == null ? null : String.valueOf(g.get("note")));
    }

    private static int intOr(Object v) {
        return v instanceof Number n ? n.intValue() : 0;   // '적대_고정' 같은 산문은 수치가 아니다
    }

    /** 정파·사파가 그를 보는 눈 (favor 델타) + 세계가 하는 말 */
    record Gaze(int orthodoxFavor, int unorthodoxFavor, String note) {
    }

    /** direct_approach.branches.&lt;분기&gt;.difficulty_modifier — 사파 소문이 도는 자의 벽 (+4) */
    Integer gwangunBranchDifficulty(String branchKey) {
        Object mod = branch(GWANGUN, branchKey).get("difficulty_modifier");
        return mod instanceof Number n ? n.intValue() : null;
    }

    /** rank_ladder.현.포쾌/포두 … — 관의 사다리 (★ 자리가 비어야 오른다. 박호가 그 자리다) */
    @SuppressWarnings("unchecked")
    Map<String, Object> gwangunLadder() {
        return (Map<String, Object>) route(GWANGUN).get("rank_ladder");
    }
}
