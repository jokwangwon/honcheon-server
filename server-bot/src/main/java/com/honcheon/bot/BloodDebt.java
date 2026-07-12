package com.honcheon.bot;

import com.honcheon.core.rules.RulesConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 혈채(血債) — config/faction_reaction.yml {@code blood_debt} 의 봇 쪽 손잡이.
 *
 * <p>이 세계의 모든 값은 감쇠한다. 주목은 7일에 1씩, 우호는 30일에 1씩. <b>세계는 잊는다.</b>
 * 그런데 죽은 사람은 돌아오지 않는다 — 잊히지 않는 축이 하나는 있어야 한다. 이것이 그것이다.
 *
 * <p><b>혈채는 악행 포인트가 아니다 — 무고(無辜)의 장부다.</b> 도적을 베어도, 비무에서 죽여도 0이다.
 * 서로 죽일 각오로 만난 자들 사이에는 빚이 없다. 빚은 <b>갚을 수 없는 자를 죽였을 때</b>만 생긴다.
 *
 * <p><b>장부는 둘이다.</b>
 * <pre>
 *   암혈채(hidden)  실제로 죽인 값 × 배수.        감쇠 없음 — ★ 이 세계에서 유일하다
 *   현혈채(known)   위 값 × 노출 × 정확도.        30일 -1 · floor = 공개 건수 × 2
 * </pre>
 * 완전 범죄는 가능하다 (witness 0 + 시신 은닉 → 노출 0.0 → 현혈채 0). <b>그러나 암혈채는 자란다.</b>
 * 세계를 속일 수 있어도 몸은 속일 수 없다.
 *
 * <p>이 클래스는 <b>아무 수치도 발명하지 않는다.</b> 건당 값·배수·노출·사다리는 전부
 * {@code blood_debt} 등록부에 있고, 사다리가 부르는 것들(명분·법명분·우호)의 값은
 * 각자의 등록부(faction_politics.yml · economy.yml)에 있다. 여기 있는 것은 배선뿐이다.
 * (설계: docs/design/blood_debt.md)
 */
final class BloodDebt {

    private final Map<String, Object> cfg;   // faction_reaction.yml 의 blood_debt 절

    BloodDebt(Map<String, Object> factionReactionCfg) {
        this.cfg = RulesConfig.section(factionReactionCfg, "blood_debt");
    }

    // ══════════════ 등록부 판독 ══════════════

    private Map<String, Object> engine() {
        return map(cfg.get("engine"));
    }

    /** scale: [0, 30] — 상한 (신규 통화 없음: 주목·우호·명분과 같은 눈금) */
    int max() {
        Object scale = cfg.get("scale");
        return scale instanceof List<?> l && l.size() > 1 ? RulesConfig.intValue(l.get(1)) : 30;
    }

    /** inputs.&lt;분류&gt; — 무고_배경 1 · 무고_기능 2 · 무고_서사 3 · 무장_상대 0 · 관인 0 · 비무_사고 0 */
    double baseOf(String category) {
        if (category == null || "없음".equals(category)) {
            return 0;
        }
        Object v = map(cfg.get("inputs")).get(category);
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    /** multipliers — 의식_살인 ×2.0 · 무력한_자 ×1.5 · 항복자_살해 ×1.5 (곱해진다) */
    double multiplierOf(List<String> flags) {
        double m = 1.0;
        if (flags == null) {
            return m;
        }
        Map<String, Object> table = map(cfg.get("multipliers"));
        for (String flag : flags) {
            Object v = table.get(flag);
            if (v instanceof Number n) {
                m *= n.doubleValue();
            }
        }
        return m;
    }

    // ─── B2. 대상 분류 — "누구를 죽였는가" (engine.classification) ───

    /**
     * 죽은 자를 여섯 칸 중 하나로 떨어뜨린다. <b>여기가 이 축의 정직함이 사는 곳이다</b> —
     * 무장한 상대와 관인은 0이다 (전자는 빚이 아니고, 후자는 법명분으로 간다. 중복 금지).
     *
     * @param registry populace | cheongha_npcs
     * @param faction  등록 NPC 의 소속 (gwan_gun → 관인)
     * @param roleOrJob 역할·생업 (armed_roles 낱말이 들어 있으면 무장_상대)
     * @param tier     등록 NPC 의 tier (1·2·3 → 배경·기능·서사)
     * @param cause    사인 (노환_병사·사고 → 없음. 비무_사고 → 0)
     */
    String classify(String registry, String faction, String roleOrJob, int tier, String cause) {
        Map<String, Object> c = map(engine().get("classification"));
        Object byCause = map(c.get("by_cause")).get(cause);
        if (byCause != null) {
            return String.valueOf(byCause);   // 노환·사고·비무 — 내가 만든 빚이 아니다
        }
        for (Object word : list(c.get("armed_roles"))) {
            if (roleOrJob != null && roleOrJob.contains(String.valueOf(word))) {
                return "무장_상대";            // ★ 서로 죽일 각오로 만난 자는 빚이 아니다
            }
        }
        Object byFaction = faction == null ? null : map(c.get("by_faction")).get(faction);
        if (byFaction != null) {
            return String.valueOf(byFaction);  // ★ 관인 — 법명분으로 간다
        }
        if (!"cheongha_npcs".equals(registry)) {
            return String.valueOf(c.getOrDefault("populace_default", "무고_배경"));
        }
        Object byTier = map(c.get("by_tier")).get(String.valueOf(tier));
        if (byTier == null) {
            byTier = map(c.get("by_tier")).get(tier);   // YAML 키가 정수로 읽힌 경우
        }
        return byTier == null ? "무고_배경" : String.valueOf(byTier);
    }

    /** 다리의 이 kind 는 애초에 혈채가 아니다 (bandit_slain · beast_slain · sparring) */
    boolean zeroKind(String kind) {
        for (Object k : list(map(engine().get("classification")).get("zero_kinds"))) {
            if (String.valueOf(k).equals(kind)) {
                return true;
            }
        }
        return false;
    }

    // ─── B1. 노출·정확도 — 현혈채의 두 배수 ───

    /** 목격·시신 → 노출 단계 (은밀 · 흔적 · 지목 · 공개). npc_death.yml killer_response.variables 문법 */
    String exposureStage(int witnesses, String body) {
        Map<String, Object> rules = map(engine().get("exposure_rules"));
        if (witnesses >= 2 && rules.containsKey("공개")) {
            return "공개";
        }
        if (witnesses >= 1 && rules.containsKey("지목")) {
            return "지목";
        }
        // witness 0 — 시신을 감췄으면 아무 일도 없었던 것이 된다 (세계에게는)
        boolean hidden = false;
        for (Object b : list(map(rules.get("은밀")).get("body"))) {
            if (String.valueOf(b).equals(body)) {
                hidden = true;
            }
        }
        return hidden ? "은밀" : "흔적";
    }

    /** exposure.&lt;단계&gt; — 은밀 0.0 · 흔적 0.5 · 지목 1.0 · 공개 1.5 */
    double exposureMult(String stage) {
        Object v = map(cfg.get("exposure")).get(stage);
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    /** engine.accuracy_multipliers — 사실적 1.0 · 과장 0.7 · 오해 0.5 · 괴담 0.5 (rumor.yml 밴드 이름) */
    double accuracyMult(String band) {
        Object v = map(engine().get("accuracy_multipliers")).get(band);
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    /** engine.magong_witness.exposure_floor — 마공 운기를 목격당한 몸에는 '은밀'이 없다 (1.0) */
    double magongExposureFloor() {
        Object v = map(engine().get("magong_witness")).get("exposure_floor");
        return v instanceof Number n ? n.doubleValue() : 1.0;
    }

    Map<String, Object> magongWitness() {
        return map(engine().get("magong_witness"));
    }

    // ─── 감쇠 — 현혈채만 (암혈채는 ★ 감쇠하지 않는다) ───

    private Map<String, Object> knownDecay() {
        return map(map(map(cfg.get("ledgers")).get("현혈채")).get("decay"));
    }

    int knownDecayEveryDays() {
        Object v = knownDecay().get("every_days");
        return v instanceof Number n ? Math.max(1, n.intValue()) : 30;
    }

    private int knownDecayAmount() {
        Object v = knownDecay().get("amount");
        return v instanceof Number n ? Math.abs(n.intValue()) : 1;
    }

    /**
     * 오늘 기준 현혈채 — 읽는 순간 정산 (주목·우호·명분과 같은 문법. 같은 날이면 같은 값).
     * <b>하한은 공개(witness 2) 건수 × 2</b> — 백주에 죽인 것은 잊히지 않는다.
     */
    int decayedKnown(double knownRaw, int publicCount, int lastDay, int today) {
        int every = knownDecayEveryDays();
        int ticks = Math.max(0, (today - lastDay) / every);
        int raw = Math.min(max(), (int) Math.floor(knownRaw));   // scale [0, 30] — 신규 통화 없음
        int floor = Math.min(publicCount * 2, raw);   // 백주에 죽인 것은 잊히지 않는다 (하한 ≤ 원값)
        return Math.max(0, Math.max(floor, raw - ticks * knownDecayAmount()));
    }

    // ─── 사다리 — 살인 1건과 10건은 다르다 ───

    /** ladder 의 한 칸 (stage · min · name · 그 칸의 소문 강도) */
    record Rung(int stage, int min, String name, int rumorIntensity) {
    }

    List<Rung> ladder() {
        List<Rung> out = new ArrayList<>();
        for (Object raw : list(cfg.get("ladder"))) {
            Map<String, Object> r = map(raw);
            Object intensity = map(r.get("rumor")).get("intensity");
            out.add(new Rung(RulesConfig.intValue(r.get("stage")), RulesConfig.intValue(r.get("min")),
                    String.valueOf(r.get("name")),
                    intensity instanceof Number n ? n.intValue() : 0));
        }
        return out;
    }

    /** 지금 그는 어느 칸에 있는가 (현혈채가 읽는 값이다 — 세계는 암혈채를 모른다) */
    Rung rungOf(int known) {
        Rung best = new Rung(0, 0, "없음", 0);
        for (Rung r : ladder()) {
            if (known >= r.min()) {
                best = r;
            }
        }
        return best;
    }

    // ─── engine — 사다리의 각 칸이 부르는 것 ───

    int bountyMin() {
        return RulesConfig.intValue(engine().getOrDefault("bounty_min", 3));
    }

    /** economy.yml price_table.의뢰_보수 의 키 (고수_현상금 = 50000). 금액은 저기가 정본이다 */
    String bountyRef() {
        return String.valueOf(engine().getOrDefault("bounty_ref", "고수_현상금"));
    }

    /** ★ 게시판이 닫힌다 — 세계에서 일이 사라진다 (혈채 15+. 남은 수입은 약탈뿐이다) */
    int boardBlockMin() {
        return RulesConfig.intValue(engine().getOrDefault("board_block_min", 15));
    }

    /** engine.faction — { min, faction, score, favor } */
    record FactionStep(int min, String faction, int score, int favor) {
    }

    List<FactionStep> factionSteps() {
        List<FactionStep> out = new ArrayList<>();
        for (Object raw : list(engine().get("faction"))) {
            Map<String, Object> r = map(raw);
            out.add(new FactionStep(RulesConfig.intValue(r.get("min")),
                    String.valueOf(r.get("faction")), RulesConfig.intValue(r.get("score")),
                    RulesConfig.intValue(r.get("favor"))));
        }
        return out;
    }

    /** engine.myeongbun / engine.mandate — { min, input, victims } / { min, input, value } */
    record Step(int min, String input, List<String> victims, int value) {
    }

    List<Step> myeongbunSteps() {
        List<Step> out = new ArrayList<>();
        for (Object raw : list(engine().get("myeongbun"))) {
            Map<String, Object> r = map(raw);
            List<String> victims = new ArrayList<>();
            for (Object v : list(r.get("victims"))) {
                victims.add(String.valueOf(v));
            }
            out.add(new Step(RulesConfig.intValue(r.get("min")), String.valueOf(r.get("input")),
                    victims, 0));
        }
        return out;
    }

    List<Step> mandateSteps() {
        List<Step> out = new ArrayList<>();
        for (Object raw : list(engine().get("mandate"))) {
            Map<String, Object> r = map(raw);
            out.add(new Step(RulesConfig.intValue(r.get("min")), String.valueOf(r.get("input")),
                    List.of(), RulesConfig.intValue(r.get("value"))));
        }
        return out;
    }

    // ─── 한 건의 계산 — 이 함수가 이 축의 전부다 ───

    /**
     * 한 사람이 죽었다. 그것은 얼마인가.
     *
     * <pre>
     *   base   = inputs.&lt;분류&gt;          (무고 1·2·3 / 무장 0 / 관인 0)
     *   암혈채 += base × 배수             ← ★ 노출과 무관하다. 몸은 다 안다
     *   현혈채 += base × 배수 × 노출 × 정확도
     * </pre>
     */
    record Charge(String category, double base, double multiplier, String exposure,
                  double exposureMult, String band, double accuracyMult,
                  double hidden, double known, boolean publicKill) {

        boolean any() {
            return hidden > 0 || known > 0;
        }
    }

    Charge charge(String category, List<String> flags, int witnesses, String body, int accuracy,
                  String band, double exposureFloor) {
        double base = baseOf(category);
        double mult = multiplierOf(flags);
        String stage = exposureStage(witnesses, body);
        double exposure = Math.max(exposureMult(stage), exposureFloor);   // ★ B6 — 마공은 숨지 못한다
        double acc = accuracyMult(band);
        double hidden = base * mult;                       // ★ 감쇠하지 않는 값
        double known = hidden * exposure * acc;
        return new Charge(category, base, mult, stage, exposure, band, acc, hidden, known,
                witnesses >= 2 && known > 0);
    }

    // ─── 판독 도우미 ───

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }
}
