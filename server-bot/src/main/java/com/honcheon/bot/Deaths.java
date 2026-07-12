package com.honcheon.bot;

import com.honcheon.core.rules.RulesConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NPC 사망 연쇄 — config/npc_death.yml 의 봇 쪽 손잡이.
 *
 * 원칙(설계 문서): 죽음은 막다른 길이 아니다 — 기능·서사·루트는 이어진다.
 * 단 더 비싸게(price_mult) · 더 어둡게(장물 매입가) · 더 늦게(outsider_days).
 * 상태 기계는 결정론이다: (사망일, 오늘, 후계 등록부) → 서비스 상태. 주사위 없음.
 */
final class Deaths {

    private final Map<String, Object> cfg;

    Deaths(Map<String, Object> cfg) {
        this.cfg = cfg;
    }

    // ─── 서비스 공백 (service_gap) ───

    @SuppressWarnings("unchecked")
    private Map<String, Object> facilities() {
        return (Map<String, Object>) RulesConfig.section(cfg, "service_gap").get("facilities");
    }

    /** NPC 가 지키는 시설 키 (없으면 null — 루트·서사 NPC 는 서비스 공백이 없다) */
    @SuppressWarnings("unchecked")
    String facilityOf(String npcKey) {
        for (Map.Entry<String, Object> e : facilities().entrySet()) {
            Map<String, Object> f = (Map<String, Object>) e.getValue();
            Object npc = f.get("npc");
            if (npcKey.equals(npc) || (npc instanceof List<?> list && list.contains(npcKey))) {
                return e.getKey();
            }
        }
        return null;
    }

    /** 시설을 지키는 NPC 키 (복수면 첫째) */
    @SuppressWarnings("unchecked")
    String npcOfFacility(String facility) {
        Map<String, Object> f = (Map<String, Object>) facilities().get(facility);
        if (f == null) {
            return null;
        }
        Object npc = f.get("npc");
        return npc instanceof List<?> list ? String.valueOf(list.get(0)) : String.valueOf(npc);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stateEffect(String state) {
        Map<String, Object> effects = (Map<String, Object>) RulesConfig.section(cfg, "service_gap")
                .get("state_effects");
        return (Map<String, Object>) effects.get(state);
    }

    /** succession.npcs.<key> — 등록부에 적힌 '그의 죽음 이후' (없으면 null) */
    @SuppressWarnings("unchecked")
    Map<String, Object> succession(String npcKey) {
        Map<String, Object> npcs = (Map<String, Object>) RulesConfig.section(cfg, "succession").get("npcs");
        Object entry = npcs.get(npcKey);
        return entry instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    // ─── 정치 귀결 (단계 5) — 관을 죽이면 법명분이 오른다 (faction_politics 접합) ───

    /**
     * succession.npcs.<key>.political.authority_mandate — 포두(박호) +8 · 현령(조문원) +14.
     * 수치의 원천은 faction_politics.yml authority_mandate.inputs 이고,
     * 이 키는 "그 NPC 를 죽이는 것이 어느 입력에 해당하는가"의 등록부다 (null = 관이 아니다).
     */
    @SuppressWarnings("unchecked")
    Integer authorityMandateOf(String npcKey) {
        Map<String, Object> s = succession(npcKey);
        if (s != null && s.get("political") instanceof Map<?, ?> m) {
            Object v = ((Map<String, Object>) m).get("authority_mandate");
            return v instanceof Number n ? n.intValue() : null;
        }
        return null;
    }

    /** escalation_murim — 무림 측 반응 ("강호가 먼저 그를 친다"). 관 살해의 진짜 대가는 이쪽이다 */
    String escalationMurim(String npcKey) {
        Map<String, Object> s = succession(npcKey);
        Object e = s == null ? null : s.get("escalation_murim");
        return e == null ? null : String.valueOf(e).replaceAll("\\s+", " ").strip();
    }

    /** responses.<대응>.political.authority_mandate — 자수 -10 · 배상 -6 (법명분 drains) */
    @SuppressWarnings("unchecked")
    Integer responseMandate(String response) {
        Map<String, Object> responses = RulesConfig.section(cfg, "responses");
        Object r = responses.get(response);
        if (r instanceof Map<?, ?> m && ((Map<String, Object>) m).get("political") instanceof Map<?, ?> p) {
            Object v = ((Map<String, Object>) p).get("authority_mandate");
            return v instanceof Number n ? n.intValue() : null;
        }
        return null;
    }

    /** revenge.faction_proxy.<세력> — 유족이 없을 때 복수의 형태 (orthodox = 토벌_명분) */
    @SuppressWarnings("unchecked")
    String factionProxy(String faction) {
        Map<String, Object> revenge = RulesConfig.section(cfg, "revenge");
        Map<String, Object> proxy = (Map<String, Object>) revenge.get("faction_proxy");
        Object v = proxy.get(faction);
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 서비스 상태 — 결정론 상태 기계.
     * 정상(생존) → 공백(아무도 없다) → 대행/후계_초기(더 비싸고 더 서툴다) → 정상(외지인 부임).
     */
    Gap serviceState(String npcKey, int elapsedDays, Set<String> dead) {
        if (!dead.contains(npcKey)) {
            return new Gap("정상", npcKey, Map.of());
        }
        Map<String, Object> s = succession(npcKey);
        if (s == null) {
            return new Gap("폐업", null, Map.of());
        }
        Integer outsiderDays = intOrNull(s.get("outsider_days"));

        String heir = str(s.get("heir"));
        if (heir != null && !dead.contains(heir)) {
            int resume = intOr(s.get("heir_resume_days"), 3);
            if (elapsedDays < resume) {
                return new Gap("공백", null, Map.of());
            }
            int early = intOr(stateEffect("후계_초기").get("duration_days"), 30);
            String state = elapsedDays < resume + early ? "후계_초기" : "정상";
            return new Gap(state, heir, penalty(s.get("heir_penalty")));
        }
        if (heir != null) {
            // 후계자도 죽었다 — heir_absent_fallback (진철산 → 곽진 부재 시 폐업)
            String fallback = str(s.get("heir_absent_fallback"));
            if ("폐업".equals(fallback)) {
                return closureOrOutsider(elapsedDays, outsiderDays);
            }
        }

        String standin = str(s.get("standin"));
        if (standin != null && !dead.contains(standin)) {
            if (outsiderDays != null && elapsedDays >= outsiderDays) {
                return new Gap("정상", null, Map.of());   // 외지인 부임 — favor 0, 전임자의 관계는 상속되지 않는다
            }
            int days = intOr(s.get("standin_days"), 1);
            if (elapsedDays < days) {
                return new Gap("공백", null, Map.of());
            }
            return new Gap("대행", standin, penalty(s.get("standin_penalty")));
        }
        // 차선 대행 (standin_secondary) — 첫 대행자마저 죽었을 때
        String secondary = str(s.get("standin_secondary"));
        if (standin != null && secondary != null && !dead.contains(secondary)) {
            int days = intOr(s.get("standin_days"), 1);
            return elapsedDays < days ? new Gap("공백", null, Map.of())
                    : new Gap("대행", secondary, penalty(s.get("standin_penalty")));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> factionStandin = s.get("faction_standin") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : null;
        if (factionStandin != null) {
            int days = intOr(factionStandin.get("days"), 7);
            if (elapsedDays < days) {
                return new Gap("공백", null, Map.of());   // 조직도 사람을 보내는 데 시간이 걸린다
            }
            String arrival = str(factionStandin.get("arrival"));
            return new Gap("대행", null, Map.of("부임", arrival == null
                    ? String.valueOf(factionStandin.get("faction")) : arrival.replace('_', ' ')));
        }
        return closureOrOutsider(elapsedDays, outsiderDays);
    }

    private Gap closureOrOutsider(int elapsedDays, Integer outsiderDays) {
        if (outsiderDays != null && elapsedDays >= outsiderDays) {
            return new Gap("정상", null, Map.of());
        }
        return new Gap("폐업", null, Map.of());
    }

    /**
     * 서비스 상태 한 칸 — state 는 service_gap.states, actor 는 대행자·후계자 NPC 키,
     * penalty 는 등록부의 대가 표(가격·매입가·보수·의뢰_등급_상한 …).
     */
    record Gap(String state, String actorKey, Map<String, Object> penalty) {

        boolean open() {
            return !"공백".equals(state) && !"폐업".equals(state);
        }

        boolean normal() {
            return "정상".equals(state);
        }

        /** 의뢰 등급 상한 (소연 사후 백석 대행: 호위_소탕까지) */
        String gradeCap() {
            return str(penalty.get("의뢰_등급_상한"));
        }

        /** 보수 배율 (0.9 — 대행 창구는 값을 깎는다) */
        double rewardMult() {
            Object v = penalty.get("보수");
            return v instanceof Number n ? n.doubleValue() : 1.0;
        }

        /** 매입가 배율 (0.3 — 마삼의 좌판은 장물 시세다) */
        Double buyRate() {
            Object v = penalty.get("매입가");
            return v instanceof Number n ? n.doubleValue() : null;
        }

        String arrival() {
            return str(penalty.get("부임"));
        }
    }

    // ─── 살해자 반응 — 목격·시신의 3변수 (killer_response.rumor_matrix) ───

    /** 소문 규격 (강도 0 = 소문 없음 — 은밀형의 보상) */
    record RumorSpec(int intensity, int accuracy, int delayDays, String target) {
    }

    @SuppressWarnings("unchecked")
    RumorSpec rumorFor(int witness, String body, String cause) {
        List<Object> matrix = (List<Object>) ((Map<String, Object>) RulesConfig.section(cfg, "killer_response"))
                .get("rumor_matrix");
        for (Object row : matrix) {
            Map<String, Object> r = (Map<String, Object>) row;
            if (intOr(r.get("witness"), -1) != witness) {
                continue;
            }
            String rowBody = str(r.get("body"));
            if (rowBody != null && !rowBody.equals(body)) {
                continue;
            }
            int intensity = Math.max(0, intOr(r.get("intensity"), 0) + causeRumorModifier(cause));
            int delay = firstInt(r.get("delay_days"), 0);
            return new RumorSpec(intensity, intOr(r.get("accuracy"), 50), delay, str(r.get("target")));
        }
        return new RumorSpec(0, 0, 0, null);
    }

    /** cause_modifier.<사인>.rumor — 노환·병사는 조용하다 (-1) */
    @SuppressWarnings("unchecked")
    private int causeRumorModifier(String cause) {
        Map<String, Object> mods = (Map<String, Object>) RulesConfig.section(cfg, "npc_types")
                .get("cause_modifier");
        Object entry = mods.get(cause);
        if (entry instanceof Map<?, ?> m) {
            Object rumor = ((Map<String, Object>) m).get("rumor");
            return rumor instanceof Number n ? n.intValue() : 0;
        }
        return 0;
    }

    /** 사인이 부르는 의뢰 유형 (cause_modifier.<사인>.quests) */
    @SuppressWarnings("unchecked")
    List<String> questTypesByCause(String cause) {
        Map<String, Object> mods = (Map<String, Object>) RulesConfig.section(cfg, "npc_types")
                .get("cause_modifier");
        Object entry = mods.get(cause);
        if (entry instanceof Map<?, ?> m && ((Map<String, Object>) m).get("quests") instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /** 시신 은닉 → 실종 발화 시점 (missing_person.trigger "3일 연속 일과 이탈") */
    @SuppressWarnings("unchecked")
    int missingPersonDays() {
        Map<String, Object> mp = (Map<String, Object>) RulesConfig.section(cfg, "killer_response")
                .get("missing_person");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*일")
                .matcher(String.valueOf(mp.get("trigger")));
        return m.find() ? Integer.parseInt(m.group(1)) : 3;
    }

    // ─── 죽음이 낳는 의뢰 (quest_injection.types) ───

    @SuppressWarnings("unchecked")
    Map<String, Object> questType(String type) {
        Map<String, Object> types = (Map<String, Object>) RulesConfig.section(cfg, "quest_injection")
                .get("types");
        Object t = types.get(type);
        return t instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** 의뢰 등급 — quest_injection.types.<type>.grade (리스트면 첫째) */
    String questGrade(String type, String fallback) {
        Object grade = questType(type).get("grade");
        if (grade instanceof List<?> list && !list.isEmpty()) {
            return String.valueOf(list.get(0));
        }
        return grade == null ? fallback : String.valueOf(grade);
    }

    /** 보수 키 — economy.yml 의뢰_보수 표의 키 (신규 표 없음). 맵형(보수: 낮음)이면 fallback */
    String questReward(String type, String fallback) {
        Object reward = questType(type).get("reward");
        return reward instanceof String s ? s : fallback;
    }

    /** 발주자 — issuer (리스트면 첫째, NPC 키가 아닌 것도 있다: 유족·관) */
    String questIssuer(String type) {
        Object issuer = questType(type).get("issuer");
        if (issuer instanceof List<?> list && !list.isEmpty()) {
            return String.valueOf(list.get(0));
        }
        return issuer == null ? null : String.valueOf(issuer);
    }

    // ─── 유틸 ───

    private static Map<String, Object> penalty(Object raw) {
        return raw instanceof Map<?, ?> m ? new LinkedHashMap<>(cast(m)) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static int intOr(Object v, int fallback) {
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private static Integer intOrNull(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    private static int firstInt(Object v, int fallback) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Number n) {
            return n.intValue();
        }
        return fallback;
    }

    /** 진단용 — 등록된 시설 목록 */
    List<String> allFacilities() {
        return new ArrayList<>(facilities().keySet());
    }
}
