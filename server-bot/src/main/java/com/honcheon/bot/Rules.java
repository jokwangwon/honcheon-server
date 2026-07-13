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
    /** 혈채 — ★ 감쇠하지 않는 유일한 축 (faction_reaction.yml blood_debt) */
    public final BloodDebt bloodDebt;
    /** 죽음과 유산 (단계 4 A) — 부상 사다리·사망 위기·상속·피의 장부의 단일 원천 */
    public final Legacy legacy;
    /** 기연 등록부 (fortune_encounters.yml) — ★ 관문 수치를 코드가 짓지 않는다 (방문 30·의뢰 15·사흘) */
    public final Fortunes fortunes;
    /**
     * 지역 상태 <b>규칙</b> (region_state.yml) — 사건 델타 + ★ 자연 회복.
     *
     * <p><b>장부가 아니다.</b> 장부는 {@code regions} 표 하나뿐이다 ({@code Db.region()}/{@code nudgeRegion}).
     * 이 엔진은 상태를 들고 있지 않으므로 두 세계로 갈라질 수가 없다 — 그것이 이 축의 요점이다.
     */
    public final com.honcheon.core.rules.RegionStateEngine regions;
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
    /** 문파 생활 — 계급 사다리·공적·문규, 그리고 ★ 문파 상태(sect_state.internal_burden) */
    private final Map<String, Object> sectLifeCfg;
    /** 심법 — ★ 은폐 가능 여부가 두 어둠의 운명을 가른다 (simbeop.yml simbeop.&lt;id&gt;.stealth_option) */
    private final Map<String, Object> simbeopCfg;
    /** 무명(無名) 등록부 (npcs/populace.yml) — 행인의 이름·관계, 그리고 무명 의뢰의 결말표 */
    private final Map<String, Object> populaceCfg;
    /** 접합의 문 (world_bridge.yml identity.gate) — 버튼·모달의 문장. 코드는 문장을 지어내지 않는다 */
    private final Map<String, Object> gateCfg;

    @SuppressWarnings("unchecked")
    public Rules(Path configDir) {
        this.judgmentCfg = RulesConfig.load(configDir.resolve("judgment.yml"));
        this.judgment = new JudgmentEngine(judgmentCfg);
        Map<String, Object> cultivationCfg = RulesConfig.load(configDir.resolve("cultivation.yml"));
        this.progression = new ProgressionEngine(
                cultivationCfg,
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
        Map<String, Object> factionReactionCfg = RulesConfig.load(
                configDir.resolve("faction_reaction.yml"));
        this.factions = new Factions(factionReactionCfg);
        this.bloodDebt = new BloodDebt(factionReactionCfg);
        this.simbeopCfg = RulesConfig.load(configDir.resolve("simbeop.yml"));
        this.fortunes = new Fortunes(
                RulesConfig.load(configDir.resolve("fortune_encounters.yml")),
                cultivationCfg, simbeopCfg);
        this.politics = new Politics(RulesConfig.load(configDir.resolve("faction_politics.yml")));
        this.legacy = new Legacy(RulesConfig.load(configDir.resolve("death_and_legacy.yml")));
        this.regionCfg = RulesConfig.load(configDir.resolve("regions/cheongha_hyeon.yml"));
        this.regions = new com.honcheon.core.rules.RegionStateEngine(
                RulesConfig.load(configDir.resolve("region_state.yml")));
        this.populaceCfg = RulesConfig.load(configDir.resolve("npcs/populace.yml"));
        this.innateQiCfg = RulesConfig.load(configDir.resolve("internal_energy.yml"));
        this.factionsCfg = RulesConfig.load(configDir.resolve("factions.yml"));
        this.sectLifeCfg = RulesConfig.load(configDir.resolve("sect_life.yml"));
        this.gateCfg = RulesConfig.section(RulesConfig.section(
                RulesConfig.load(configDir.resolve("world_bridge.yml")), "identity"), "gate");
    }

    // ─── 접합의 문 (world_bridge.yml identity.gate) ───

    /**
     * 문의 문장 하나 — {@code gate.discord.<key>}. 등록부에 없으면 fallback (코드가 지어내지 않는다).
     * 여기 오는 값은 전부 사람이 읽는 문장이다. 자물쇠는 이 표에 없다 (그것은 identity 본문이다).
     */
    public String gateText(String key, String fallback) {
        Object v = RulesConfig.section(gateCfg, "discord").get(key);
        return v == null ? fallback : String.valueOf(v).strip();
    }

    /** world_meta 키 — 접속의 문이 선 채널·길드 (등록부가 이름을 정한다) */
    public String gateMetaKey(String which, String fallback) {
        Object v = gateCfg.get(which);
        return v == null ? fallback : String.valueOf(v);
    }

    // ─── 무명(無名) 등록부 (npcs/populace.yml) — 마크의 마을이 봇의 장부에 닿는 곳 ───
    //
    // ★ 왜 봇이 이 파일을 읽는가: MVT 는 무명 의뢰가 **어떻게 끝났는지**(rule · outcome)만 다리에 싣는다.
    //   그 결말이 지역에 얼마를 얹는지(민심 ±1 · 치안 ±1 · 경제 -1)는 등록부가 정하고, 그것을 제 표에
    //   적는 것은 장부의 주인인 봇이다. 같은 표를 양쪽이 계산해 각자 더하면 — 세계가 둘이 된다.

    /** 무명의 이름 — 등록부에 있는 사람만. 없으면 null (코드는 이름을 지어내지 않는다) */
    @SuppressWarnings("unchecked")
    public String populaceName(String id) {
        Map<String, Object> people = RulesConfig.section(populaceCfg, "people");
        Object person = people.get(id);
        return person instanceof Map<?, ?> p
                ? String.valueOf(((Map<String, Object>) p).getOrDefault("name", id)) : null;
    }

    /**
     * 무명 의뢰의 결말이 지역에 얹는 값 — {@code quests.rules.<rule>.<outcome>.region}.
     * outcome ∈ {@code success · fail_body · on_expire}. 등록부에 없으면 빈 맵 (아무 일도 없다).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Integer> populaceQuestRegion(String rule, String outcome) {
        Map<String, Object> block = populaceQuestBlock(rule, outcome);
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        if (block.get("region") instanceof Map<?, ?> region) {
            ((Map<String, Object>) region).forEach((stat, value) -> {
                if (value instanceof Number n) {
                    out.put(stat, n.intValue());
                }
            });
        }
        return out;
    }

    /**
     * ★ 살해자가 유족의 의뢰를 완수했을 때 혈교가 얹는 우호 —
     * {@code quests.rules.<rule>.killer_irony.blood_favor}.
     *
     * <p>"어미가 당신이 가리킨 자리를 본다. 당신의 손을 본다. 그리고 다시 시신을 본다 —
     * 아무것도 묻지 않는다. 삯을 쥐여 준다." <b>혈교는 그것을 자격으로 읽는다.</b>
     */
    public int populaceQuestIrony(String rule) {
        Object v = populaceQuestBlock(rule, "killer_irony").get("blood_favor");
        return v instanceof Number n ? n.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> populaceQuestBlock(String rule, String outcome) {
        Map<String, Object> quests = RulesConfig.section(populaceCfg, "quests");
        Object rules = quests.get("rules");
        Object one = rules instanceof Map<?, ?> m ? ((Map<String, Object>) m).get(rule) : null;
        Object block = one instanceof Map<?, ?> r ? ((Map<String, Object>) r).get(outcome) : null;
        return block instanceof Map<?, ?> b ? (Map<String, Object>) b : Map.of();
    }

    // ─── 문파 상태 (sect_life.yml sect_state.internal_burden) — 연합의 브레이크 ───
    //
    // "문파가 제 코가 석 자면 남의 싸움에 못 낀다."
    // 이 축이 없어서 연합이 너무 쉽게 뭉쳤다 — 양(+) 보정이 통째로 빠져 있었다.

    @SuppressWarnings("unchecked")
    private Map<String, Object> internalBurdenCfg() {
        Map<String, Object> state = RulesConfig.section(sectLifeCfg, "sect_state");
        return (Map<String, Object>) state.get("internal_burden");
    }

    /** sect_state.internal_burden.scale = [0, 6] — 상한 */
    public int burdenMax() {
        @SuppressWarnings("unchecked")
        List<Object> scale = (List<Object>) internalBurdenCfg().get("scale");
        return scale == null ? 6 : RulesConfig.intValue(scale.get(1));
    }

    /** sect_state.internal_burden.decay.every_days = 30 — 사정은 느리게 풀린다 (favor 와 같은 주기) */
    @SuppressWarnings("unchecked")
    public int burdenDecayEveryDays() {
        Map<String, Object> decay = (Map<String, Object>) internalBurdenCfg().get("decay");
        return decay == null ? 30 : RulesConfig.intValue(decay.get("every_days"));
    }

    /**
     * sect_state.internal_burden.sources.&lt;키&gt;.burden — 사건이 얹는 부담.
     * 장문_교체기 3 · 내분_알력 2 · 사상자_누적 2 · 재정_궁핍 1 · 폐관_은둔 2 · **다른_전쟁_중 4**
     */
    @SuppressWarnings("unchecked")
    public int burdenSource(String key) {
        Map<String, Object> sources = (Map<String, Object>) internalBurdenCfg().get("sources");
        Object e = sources == null ? null : sources.get(key);
        if (!(e instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("등록되지 않은 문파 사정: " + key);
        }
        return RulesConfig.intValue(((Map<String, Object>) m).get("burden"));
    }

    /** 등록된 사정의 이름들 (관리자 명령의 선택지 원천 — 신규 사정 발명 금지) */
    @SuppressWarnings("unchecked")
    public java.util.Set<String> burdenSourceKeys() {
        Map<String, Object> sources = (Map<String, Object>) internalBurdenCfg().get("sources");
        return sources == null ? java.util.Set.of() : sources.keySet();
    }

    // ─── 심법 (simbeop.yml) — ★ 은폐 가능 여부 한 줄이 두 어둠의 운명을 가른다 ───
    //
    // 마교(천마무극공)는 숨을 수 있다 → 잠식한다. 혈교(혈기심공)는 못 숨는다 → 즉시 토벌.
    // 그래서 **운기조식을 목격당하는 순간** 혈교도의 은밀함은 끝난다 (blood_debt B6).

    /** 표시 이름(혈기심공)으로 심법 항목을 찾는다 — 시트에 적히는 것은 이름이다 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> simbeopByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Map<String, Object> catalog = RulesConfig.section(simbeopCfg, "simbeop");
        for (Object value : catalog.values()) {
            if (value instanceof Map<?, ?> s && name.equals(s.get("name"))) {
                return (Map<String, Object>) s;
            }
        }
        return null;
    }

    /** 마공인가 (demonic: true) — 흡성(혈교) 또는 연수(마교) */
    public boolean isMagong(String simbeopName) {
        Map<String, Object> s = simbeopByName(simbeopName);
        return s != null && Boolean.TRUE.equals(s.get("demonic"));
    }

    /**
     * ★ 운기 색을 숨길 수 있는가 (stealth_option). 마공인데 숨길 수 없으면 —
     * <b>운기조식이 곧 자백이다.</b> 혈기심공은 false 다 (그리고 그것이 혈교의 수명을 정한다).
     */
    public boolean canHideCirculation(String simbeopName) {
        Map<String, Object> s = simbeopByName(simbeopName);
        return s != null && Boolean.TRUE.equals(s.get("stealth_option"));
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

    /**
     * 표시 이름·별칭 → 세력 id (factions.yml aliases 그대로. 화산파 → hwasan).
     * 이미 id 인 것은 그대로 돌려준다. 등록부에 없으면 null — **세력을 발명하지 않는다.**
     */
    @SuppressWarnings("unchecked")
    public String factionId(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) {
            return null;
        }
        String key = nameOrId.strip();
        Object aliases = factionsCfg.get("aliases");
        if (aliases instanceof Map<?, ?> m && ((Map<String, Object>) m).get(key) != null) {
            return String.valueOf(((Map<String, Object>) m).get(key));
        }
        return key;   // id 로 온 것 — 등록 여부는 부르는 쪽이 coalitionOf 로 확인한다
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
