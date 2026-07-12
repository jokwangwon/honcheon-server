package com.honcheon.bot;

import com.honcheon.core.rules.EconomyEngine;
import com.honcheon.core.rules.InternalEnergyEngine;
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
    public final InternalEnergyEngine energy;
    public final Map<String, Object> dispositionTest;
    public final Map<String, Object> playerCreation;
    /** 세력 입문 루트 — 직행(direct_approach)·게이트·의뢰 주입의 단일 원천 */
    public final Routes routes;
    /** NPC 사망 연쇄 — 서비스 공백·후계·소문·의뢰 주입의 단일 원천 */
    public final Deaths deaths;
    /** 소문망 (단계 4 B) — 전파(망별 속도·왜곡)·감쇠·세력 인지의 단일 원천 */
    public final Rumors rumors;
    /** 세력 반응 (단계 4 C) — 주목·우호 2축, 반응 사다리·감쇠의 단일 원천 */
    public final Factions factions;
    /** 세력 정치 (단계 5) — 명분·연합·관무불가침의 단일 원천 (세력 대 세력) */
    public final Politics politics;
    /** 죽음과 유산 (단계 4 A) — 부상 사다리·사망 위기·상속·피의 장부의 단일 원천 */
    public final Legacy legacy;
    private final Map<String, Object> judgmentCfg;
    private final Map<String, Object> economyCfg;
    private final Map<String, Object> llmCfg;
    private final Map<String, Object> npcsCfg;
    private final Map<String, Object> rumorCfg;
    private final Map<String, Object> timeCfg;
    private final Map<String, Object> questCfg;
    private final Map<String, Object> regionCfg;
    private final Map<String, Object> innateQiCfg;
    private final Map<String, Object> factionsCfg;

    @SuppressWarnings("unchecked")
    public Rules(Path configDir) {
        this.judgmentCfg = RulesConfig.load(configDir.resolve("judgment.yml"));
        this.judgment = new JudgmentEngine(judgmentCfg);
        this.progression = new ProgressionEngine(
                RulesConfig.load(configDir.resolve("cultivation.yml")),
                RulesConfig.load(configDir.resolve("training.yml")));
        this.economyCfg = RulesConfig.load(configDir.resolve("economy.yml"));
        this.economy = new EconomyEngine(economyCfg);
        this.energy = new InternalEnergyEngine(RulesConfig.load(configDir.resolve("internal_energy.yml")));
        this.dispositionTest = RulesConfig.load(configDir.resolve("disposition_test.yml"));
        this.playerCreation = RulesConfig.load(configDir.resolve("player_creation.yml"));
        this.llmCfg = RulesConfig.load(configDir.resolve("llm.yml"));
        this.npcsCfg = RulesConfig.load(configDir.resolve("npcs/cheongha_npcs.yml"));
        this.rumorCfg = RulesConfig.load(configDir.resolve("rumor.yml"));
        this.timeCfg = RulesConfig.load(configDir.resolve("time.yml"));
        this.questCfg = RulesConfig.load(configDir.resolve("quest_generation.yml"));
        this.routes = new Routes(RulesConfig.load(configDir.resolve("faction_entry_routes.yml")));
        this.deaths = new Deaths(RulesConfig.load(configDir.resolve("npc_death.yml")));
        this.rumors = new Rumors(rumorCfg);
        this.factions = new Factions(RulesConfig.load(configDir.resolve("faction_reaction.yml")));
        this.politics = new Politics(RulesConfig.load(configDir.resolve("faction_politics.yml")));
        this.legacy = new Legacy(RulesConfig.load(configDir.resolve("death_and_legacy.yml")));
        this.regionCfg = RulesConfig.load(configDir.resolve("regions/cheongha_hyeon.yml"));
        this.innateQiCfg = RulesConfig.load(configDir.resolve("internal_energy.yml"));
        this.factionsCfg = RulesConfig.load(configDir.resolve("factions.yml"));
    }

    /** judgment.yml formula.npc_fixed_bonus — NPC는 주사위 대신 고정값 (+7) */
    public int npcFixedBonus() {
        return RulesConfig.intValue(RulesConfig.section(judgmentCfg, "formula").get("npc_fixed_bonus"));
    }

    /** judgment.yml formula.situation_modifier_cap — 상황 보정 합계 절대값 상한 (±5) */
    public int situationCap() {
        return RulesConfig.intValue(
                RulesConfig.section(judgmentCfg, "formula").get("situation_modifier_cap"));
    }

    /** judgment.yml situation_modifiers.condition — 경상 -1 · 중상 -2 · 빈사 -3 (전투 밖 판정에도 지속) */
    @SuppressWarnings("unchecked")
    public int conditionModifier(String wound) {
        if (wound == null || wound.isBlank()) {
            return 0;
        }
        Map<String, Object> mods = RulesConfig.section(judgmentCfg, "situation_modifiers");
        Map<String, Object> condition = (Map<String, Object>) mods.get("condition");
        Object value = condition.get(wound);
        return value instanceof Number n ? n.intValue() : 0;
    }

    /** internal_energy.yml innate_qi.burn_uses.회생.cost_years — 빈사 사망 위기 자동 통과 1회의 값 */
    @SuppressWarnings("unchecked")
    public int revivalCostYears() {
        Map<String, Object> innate = RulesConfig.section(innateQiCfg, "innate_qi");
        Map<String, Object> burns = (Map<String, Object>) innate.get("burn_uses");
        Map<String, Object> revival = (Map<String, Object>) burns.get("회생");
        return RulesConfig.intValue(revival.get("cost_years"));
    }

    /** internal_energy.yml innate_qi.total_at_birth — 수명 100년 (전원 균등, 재능과 무관) */
    public int innateQiTotal() {
        return RulesConfig.intValue(RulesConfig.section(innateQiCfg, "innate_qi").get("total_at_birth"));
    }

    /**
     * 청하현 등록 사건 (regions/cheongha_hyeon.yml incidents) — 세계 개막 소문의 원천.
     * 신규 사건 발명 없음: 이미 등록된 3건(사파 연락책·북로 도적·열병)이 첫날부터 돌고 있어야 한다.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> incidentsRegistry() {
        Object incidents = regionCfg.get("incidents");
        return incidents instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** 등록 NPC 의 장소 키 (cheongha_inn · market · request_office …) — 그가 어느 소문망에 사는가 */
    public String npcLocation(String key) {
        Map<String, Object> npc = npcByKey(key);
        return npc == null ? null : String.valueOf(npc.get("location"));
    }

    /** 등록 NPC 의 소속 세력 (mingan · haomun · orthodox_heroes · sangdan · gwan_gun …) */
    public String npcFaction(String key) {
        Map<String, Object> npc = npcByKey(key);
        return npc == null ? null : String.valueOf(npc.get("faction"));
    }

    /**
     * 세력 id → 표시 이름 — factions.yml aliases 를 뒤집어 읽는다 (haomun → 하오문).
     * 별칭표가 곧 등록부다: 여기에 없는 id 는 그대로 보여 준다 (신규 세력 발명 금지).
     */
    @SuppressWarnings("unchecked")
    public String factionName(String id) {
        Object aliases = factionsCfg.get("aliases");
        if (aliases instanceof Map<?, ?> m) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                if (id.equals(String.valueOf(e.getValue()))) {
                    return e.getKey();
                }
            }
        }
        return id;
    }

    /** quest_generation.yml grade_ladder — 등급 사다리 (낮은 것부터). 등급 상한 집행의 원천 */
    @SuppressWarnings("unchecked")
    public List<String> gradeLadder() {
        Map<String, Object> ladder = RulesConfig.section(questCfg, "grade_ladder");
        List<Object> rungs = (List<Object>) ladder.get("rungs");
        return rungs.stream().map(r -> String.valueOf(((Map<String, Object>) r).get("grade"))).toList();
    }

    /** rumor.yml generation.initial_accuracy — 직접_목격 90 · 간접_전문 70 · 흔적_추론 50 */
    @SuppressWarnings("unchecked")
    public int initialAccuracy(String kind) {
        Map<String, Object> gen = RulesConfig.section(rumorCfg, "generation");
        Map<String, Object> table = (Map<String, Object>) gen.get("initial_accuracy");
        return RulesConfig.intValue(table.get(kind));
    }

    /** judgment.yml static_difficulty — 난이도 기준치 (쉬움 10 · 보통 12 · 어려움 14 …) */
    public int difficulty(String band) {
        Map<String, Object> table = RulesConfig.section(judgmentCfg, "static_difficulty");
        return RulesConfig.intValue(table.get(band));
    }

    /** economy.yml trading.black_market.rate — 장물 매입가 (장쇠 사후 마삼의 좌판) */
    @SuppressWarnings("unchecked")
    public double blackMarketRate() {
        Map<String, Object> trading = RulesConfig.section(economyCfg, "trading");
        Map<String, Object> black = (Map<String, Object>) trading.get("black_market");
        return ((Number) black.get("rate")).doubleValue();
    }

    /** economy.yml price_table 하위 표의 값 (범위면 하한) — 노자 산출의 원천 */
    @SuppressWarnings("unchecked")
    public int price(String category, String item) {
        Map<String, Object> table = RulesConfig.section(economyCfg, "price_table");
        Object value = RulesConfig.section(table, category).get(item);
        if (value instanceof List<?> range) {
            return ((Number) range.get(0)).intValue();
        }
        return ((Number) value).intValue();
    }

    /**
     * 오프스크린 여정의 하루치 노자 — 봉놋방 1박 + 국밥 2끼 (economy.yml 생활 표에서 유도).
     * 신규 수치 발명 없음: 기존 생활 물가의 합이 곧 '길 위의 하루'다.
     */
    public int dailyTravelCost() {
        return price("생활", "봉놋방_1박") + price("생활", "국밥") * 2;
    }

    /** time.yml action_costs.지역권_이동 = "3~7일 (multi_day)" → [3, 7] */
    public List<Integer> regionTravelDays() {
        Map<String, Object> costs = RulesConfig.section(timeCfg, "action_costs");
        String raw = String.valueOf(costs.get("지역권_이동"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*~\\s*(\\d+)").matcher(raw);
        if (!m.find()) {
            throw new IllegalStateException("time.yml 지역권_이동 형식을 읽을 수 없다: " + raw);
        }
        return List.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
    }

    /** rumor.yml propagation.origin_network_by_location — 장소별 발원 소문망 */
    @SuppressWarnings("unchecked")
    public String originNetwork(String location) {
        Map<String, Object> prop = RulesConfig.section(rumorCfg, "propagation");
        Map<String, Object> byLoc = (Map<String, Object>) prop.get("origin_network_by_location");
        Object net = byLoc.get(location);
        return net == null ? "mingan_market" : String.valueOf(net);
    }

    /** 등록 NPC 키 → 표시 이름 (등록제 명사 — 발명 금지) */
    public String npcName(String key) {
        Map<String, Object> npc = npcByKey(key);
        return npc == null ? key : String.valueOf(npc.get("name"));
    }

    public String npcRole(String key) {
        Map<String, Object> npc = npcByKey(key);
        return npc == null ? "" : String.valueOf(npc.get("role"));
    }

    public int npcTier(String key) {
        Map<String, Object> npc = npcByKey(key);
        return npc == null ? 1 : RulesConfig.intValue(npc.get("tier"));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> npcByKey(String key) {
        Object npc = RulesConfig.section(npcsCfg, "npcs").get(key);
        return npc instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /** 표시 이름 → 등록 키 (대화 명령의 상대 옵션은 이름으로 온다) */
    public String npcKeyByName(String name) {
        for (Map.Entry<String, Object> e : RulesConfig.section(npcsCfg, "npcs").entrySet()) {
            if (e.getValue() instanceof Map<?, ?> npc && name.equals(npc.get("name"))) {
                return e.getKey();
            }
        }
        return null;
    }

    /** 기 운용 게이트 — realm_gates에 없는 경지(범인)는 게이트 없음 = 불가 */
    public boolean canUseQi(String realm, String costBand) {
        try {
            return energy.canUse(realm, costBand);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 등록 NPC 항목을 표시 이름으로 찾는다 (등록제 명사 — 대화 페르소나의 원천) */
    @SuppressWarnings("unchecked")
    public Map<String, Object> npcByName(String name) {
        Map<String, Object> registry = RulesConfig.section(npcsCfg, "npcs");
        for (Object value : registry.values()) {
            if (value instanceof Map<?, ?> npc && name.equals(npc.get("name"))) {
                return (Map<String, Object>) npc;
            }
        }
        return null;
    }

    /** llm.yml roles.turn_renderer.model — 세대 교체는 config 만 갱신하면 된다 */
    @SuppressWarnings("unchecked")
    public String turnRendererModel() {
        Map<String, Object> roles = RulesConfig.section(llmCfg, "roles");
        Map<String, Object> renderer = (Map<String, Object>) roles.get("turn_renderer");
        return (String) renderer.get("model");
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

    /** 의뢰 보수 — economy.yml price_table.의뢰_보수: 고정값 또는 [min, max] 범위 */
    @SuppressWarnings("unchecked")
    public int questReward(String key, Random dice) {
        Map<String, Object> table = RulesConfig.section(economyCfg, "price_table");
        Map<String, Object> rewards = (Map<String, Object>) table.get("의뢰_보수");
        Object value = rewards.get(key);
        if (value instanceof List<?> range) {
            int min = ((Number) range.get(0)).intValue();
            int max = ((Number) range.get(1)).intValue();
            return min + dice.nextInt(max - min + 1);
        }
        return ((Number) value).intValue();
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
