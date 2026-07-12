package com.honcheon.bot;

import com.honcheon.core.rules.RulesConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 세력 정치 — config/faction_politics.yml 의 봇 쪽 손잡이 (단계 5).
 *
 * faction_reaction(주목·우호) 이 **세력 대 개인**이라면, 이 층은 **세력 대 세력**이다.
 *
 *   ① 파편화   '정파'는 세력이 아니라 연합의 이름이다. 평시엔 15개가 각자 논다.
 *   ② 명분     사건이 쌓는 0~30 게이지. 세력별 임계(join_threshold)를 넘은 자만 붙는다.
 *   ③ 억지     관도 무림도 선빵을 못 친다 — 선빵이 곧 상대에게 명분을 주기 때문이다.
 *
 * ★ 이 파일의 심장: **소문의 정확도가 곧 명분의 배수다** (accuracy_gate).
 *   같은 사건이라도 세력마다 **자기 조직 채널에 닿은 정확도**로 평가한다
 *   (rumor.yml network_access — 개방은 거리에서 1일, 정파망은 3일).
 *   → 망별 속도가 곧 **세력별 참전 시차**이고, 오해 밴드(30~49)는 **명분이 엉뚱한 세력에게 붙는 통로**다.
 *
 * 연합에는 테이블이 없다 — 상태가 아니라 **함수**다:
 *   참여 = f(명분, 태그, 관계표, 조직 채널 도달). 같은 세계일이면 같은 연합 (결정론, 주사위 없음).
 *
 * 수치는 전부 yml. 여기에 하드코딩된 숫자는 없다.
 */
final class Politics {

    private final Map<String, Object> cfg;

    Politics(Map<String, Object> cfg) {
        this.cfg = cfg;
    }

    // ═══ ① 등록부 — 15개 독립 세력 + 사파 5 + 관 (신규 세력 발명 금지) ═══

    @SuppressWarnings("unchecked")
    Map<String, Object> roster() {
        return RulesConfig.section(cfg, "roster");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> entry(String faction) {
        Object e = roster().get(faction);
        return e instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /** 그 세력이 속한 연합체 (orthodox · unorthodox · authority · forbidden) */
    String coalitionOf(String faction) {
        Map<String, Object> e = entry(faction);
        return e == null ? null : String.valueOf(e.get("coalition"));
    }

    String displayName(String faction) {
        Map<String, Object> e = entry(faction);
        return e == null ? faction : String.valueOf(e.get("name"));
    }

    int martial(String faction) {
        Map<String, Object> e = entry(faction);
        return e == null ? 0 : intOr(e.get("martial"), 0);
    }

    int mandateWeight(String faction) {
        Map<String, Object> e = entry(faction);
        return e == null ? 0 : intOr(e.get("mandate_weight"), 0);
    }

    /** active: false (아미 — 확장 예약) 는 무대에 없다 */
    boolean active(String faction) {
        Map<String, Object> e = entry(faction);
        return e != null && !Boolean.FALSE.equals(e.get("active"));
    }

    /** 명분에 반응할 수 있는 세력들 — 무림(정파 15 + 사파 5). 관·금기는 연합에 붙지 않는다 */
    List<String> murim() {
        List<String> out = new ArrayList<>();
        for (String id : roster().keySet()) {
            String coalition = coalitionOf(id);
            if (active(id) && ("orthodox".equals(coalition) || "unorthodox".equals(coalition))) {
                out.add(id);
            }
        }
        return out;
    }

    // ═══ ①-b 관계표 — 같은 정파끼리도 앙숙이다 ═══

    /** relation: +3 동맹 / +2 우호 / +1 거래 / 0 중립 / -1 경쟁 / -2 원한 / -3 숙적 */
    @SuppressWarnings("unchecked")
    int relation(String a, String b) {
        if (a == null || b == null || a.equals(b)) {
            return 0;
        }
        Object raw = cfg.get("relations");
        if (!(raw instanceof List<?> list)) {
            return 0;
        }
        for (Object item : list) {
            Map<String, Object> r = (Map<String, Object>) item;
            String x = str(r.get("a"));
            String y = str(r.get("b"));
            if ((a.equals(x) && b.equals(y)) || (a.equals(y) && b.equals(x))) {
                return intOr(r.get("value"), 0);
            }
        }
        return 0;
    }

    // ═══ ② 명분 — 연합의 방아쇠 ═══

    @SuppressWarnings("unchecked")
    private Map<String, Object> myeongbun() {
        return RulesConfig.section(cfg, "myeongbun");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> inputs() {
        return (Map<String, Object>) myeongbun().get("inputs");
    }

    /** 등록된 명분 입력 사건 키 (관리자 명령의 선택지 원천 — 신규 사건 발명 금지) */
    Set<String> inputKeys() {
        return inputs().keySet();
    }

    /** myeongbun.inputs.<사건>.value — 관의 문파 토벌 10 · 무고한 학살 8 · 관의 은폐 발각 4 … */
    @SuppressWarnings("unchecked")
    int inputValue(String key) {
        Object e = inputs().get(key);
        if (!(e instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("등록되지 않은 명분 입력: " + key);
        }
        return intOr(((Map<String, Object>) m).get("value"), 0);
    }

    /** myeongbun.inputs.<사건>.tags — 무고 · 무림침해 · 존망 · 금기 · 법의배신 · 규약 · 생계 */
    @SuppressWarnings("unchecked")
    List<String> inputTags(String key) {
        Object e = inputs().get(key);
        if (e instanceof Map<?, ?> m && ((Map<String, Object>) m).get("tags") instanceof List<?> tags) {
            return tags.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /** myeongbun.drains.<수단>.value — 가해자 처형 -6 · 배상 -4 · 화해 -8 (진범_규명은 transfer) */
    @SuppressWarnings("unchecked")
    Integer drainValue(String key) {
        Map<String, Object> drains = (Map<String, Object>) myeongbun().get("drains");
        Object e = drains.get(key);
        if (e instanceof Map<?, ?> m) {
            Object v = ((Map<String, Object>) m).get("value");
            return v instanceof Number n ? n.intValue() : null;   // transfer = 수치가 아니다
        }
        return null;
    }

    Set<String> drainKeys() {
        @SuppressWarnings("unchecked")
        Map<String, Object> drains = (Map<String, Object>) myeongbun().get("drains");
        return drains.keySet();
    }

    /**
     * ★ 정확도 밴드 = 명분 배수 (myeongbun.accuracy_gate).
     * 사실적(80+) ×1.0 / 과장(50~79) ×0.7 / 오해(30~49) ×0.5 + 대상 전환 / 괴담(29-) ×0.0.
     * 밴드 이름은 rumor.yml accuracy_bands 의 것을 그대로 받는다 (Rumors.band()).
     */
    @SuppressWarnings("unchecked")
    double accuracyMultiplier(String band) {
        Map<String, Object> gate = (Map<String, Object>) myeongbun().get("accuracy_gate");
        for (Map.Entry<String, Object> e : gate.entrySet()) {
            if (e.getKey().startsWith(band) && e.getValue() instanceof Map<?, ?> m) {
                Object mult = ((Map<String, Object>) m).get("multiplier");
                return mult instanceof Number n ? n.doubleValue() : 0.0;
            }
        }
        return 0.0;   // 등록되지 않은 밴드 = 명분이 되지 못한다
    }

    /** 오해 밴드(30~49)에서는 명분이 **엉뚱한 세력에게** 붙는다 — 마교 이간의 유일한 통로 */
    @SuppressWarnings("unchecked")
    boolean targetSwap(String band) {
        Map<String, Object> gate = (Map<String, Object>) myeongbun().get("accuracy_gate");
        for (Map.Entry<String, Object> e : gate.entrySet()) {
            if (e.getKey().startsWith(band) && e.getValue() instanceof Map<?, ?> m) {
                return Boolean.TRUE.equals(((Map<String, Object>) m).get("target_swap"));
            }
        }
        return false;
    }

    /** 그 정확도로 그 사건은 명분 몇 점이 되는가 (배수 적용 — 내림) */
    int gaugeFrom(int rawGauge, String band) {
        return (int) Math.floor(rawGauge * accuracyMultiplier(band));
    }

    // ─── 감쇠 — 명분은 소진된다. 그래서 관은 버티기만 해도 이긴다 ───

    @SuppressWarnings("unchecked")
    private Map<String, Object> decay() {
        return (Map<String, Object>) myeongbun().get("decay");
    }

    int decayEveryDays() {
        return intOr(decay().get("every_days"), 7);
    }

    int decayAmount() {
        return Math.abs(intOr(decay().get("amount"), -1));
    }

    /** decay.floor — 존망 태그의 기억은 완전히 꺼지지 않는다 (4에서 멈춘다) */
    @SuppressWarnings("unchecked")
    int decayFloor(List<String> tags) {
        Map<String, Object> floor = (Map<String, Object>) decay().get("floor");
        int base = intOr(floor.get("기타"), 0);
        for (String tag : tags) {
            Object f = floor.get(tag + "_태그");
            if (f instanceof Number n) {
                base = Math.max(base, n.intValue());
            }
        }
        return base;
    }

    /** 오늘 기준 명분 정산 — 읽는 순간 계산 (배치 잡 금지. 같은 날 = 같은 값) */
    int decayed(int rawGauge, List<String> tags, int lastDay, int today) {
        int every = decayEveryDays();
        int ticks = every <= 0 ? 0 : Math.max(0, (today - lastDay) / every);
        if (ticks == 0) {
            return rawGauge;
        }
        return Math.max(decayFloor(tags), rawGauge - ticks * decayAmount());
    }

    // ─── 태그 감응 — 누가 어떤 명분에 반응하는가 ───

    @SuppressWarnings("unchecked")
    private Map<String, Object> tagTable() {
        return (Map<String, Object>) myeongbun().get("tags");
    }

    /**
     * 태그 감응 임계 보정 — 여러 태그가 걸리면 **가장 강한 것 하나**만 (중첩 금지).
     * resonates 가 명단(List)이면 그대로, 산문이면 계열로 읽는다:
     *   "전 무림 (사파 포함)"(존망) → 정파·사파 전부 / "전 정파 + 사파(존망일 때)"(무림침해)
     *   → 정파 전부, 사파는 존망 동반 시 / "정파 전반"(규약) → 정파 전부.
     */
    @SuppressWarnings("unchecked")
    int tagThresholdMod(List<String> tags, String faction) {
        boolean existential = tags.contains("존망");
        int best = 0;
        for (String tag : tags) {
            Object raw = tagTable().get(tag);
            if (!(raw instanceof Map<?, ?> m)) {
                continue;
            }
            Map<String, Object> t = (Map<String, Object>) m;
            Object resonates = t.get("resonates");
            boolean hit;
            if (resonates instanceof List<?> list) {
                hit = list.stream().map(String::valueOf).anyMatch(faction::equals);
            } else {
                String coalition = coalitionOf(faction);
                String prose = String.valueOf(resonates);
                hit = prose.contains("전 무림")
                        ? "orthodox".equals(coalition) || "unorthodox".equals(coalition)
                        : "orthodox".equals(coalition)
                                || ("unorthodox".equals(coalition) && existential
                                        && prose.contains("사파"));
            }
            if (hit) {
                best = Math.min(best, intOr(t.get("threshold_mod"), 0));
            }
        }
        return best;
    }

    // ═══ ②-b 연합 — 성립 · 크기 · 붕괴 ═══

    @SuppressWarnings("unchecked")
    private Map<String, Object> coalitionCfg() {
        return RulesConfig.section(cfg, "coalition");
    }

    /** join_threshold — 개방 8 (가장 빠르다) … 살막 26 (사실상 불참) */
    @SuppressWarnings("unchecked")
    int joinThreshold(String faction) {
        Map<String, Object> table = (Map<String, Object>) coalitionCfg().get("join_threshold");
        return intOr(table.get(faction), Integer.MAX_VALUE);   // 등재되지 않은 세력은 붙지 않는다
    }

    /** threshold_modifiers.<키> — 숫자형 보정만 (불참·범위형은 별도 규칙) */
    @SuppressWarnings("unchecked")
    int modifier(String key) {
        Map<String, Object> mods = (Map<String, Object>) coalitionCfg().get("threshold_modifiers");
        return intOr(mods.get(key), 0);
    }

    /**
     * ★ 연합 성립 — 순수 함수. 저장하지 않는다 (같은 입력이면 같은 연합).
     *
     * gaugeByFaction: 세력 → **그 세력이 자기 조직 채널에서 읽은 명분**
     *   (없으면 = 아직 그 조직에 소식이 닿지 않았다 → 평가 자체가 없다. formation.channel_gate)
     *
     * 참여 = gauge >= join_threshold + Σ(태그 감응 · 동맹/원한/경쟁 참여 · anchor).
     * 관계 보정이 참여 여부에 되먹임되므로(앙숙이 끼면 안 들어간다) 안정될 때까지 재평가한다 —
     * 순서 의존을 없애기 위해 매 라운드 전체를 다시 계산한다 (결정론).
     */
    Coalition form(Map<String, Integer> gaugeByFaction, List<String> tags, String target,
                   String regionSect) {
        List<String> candidates = new ArrayList<>();
        for (String id : murim()) {
            if (!id.equals(target) && gaugeByFaction.containsKey(id)) {
                candidates.add(id);
            }
        }
        Set<String> current = new LinkedHashSet<>();
        for (int round = 0; round < 5; round++) {   // 5회면 어떤 관계 그래프에서도 안정된다
            Set<String> next = new LinkedHashSet<>();
            for (String id : candidates) {
                if (joins(id, gaugeByFaction.get(id), tags, current, regionSect)) {
                    next.add(id);
                }
            }
            if (next.equals(current)) {
                break;
            }
            current = next;
        }
        return new Coalition(List.copyOf(current), sizeName(current.size()), leader(current),
                disputed(current), martialOf(current));
    }

    /**
     * 사건이 그 세력의 앞마당인가 — threshold_modifiers.인접_지역(-4) vs 거리_원거리(+2).
     *   · seat 이 '없음' = 본거지가 없다 = **어디에나 있다** (개방·하오문) → 언제나 인접
     *   · 사건이 난 지역권의 문파 (청하현 → 화산) → 인접
     *   · 나머지 = 원거리. 곤륜·해남·점창이 늦는 것은 무관심이 아니라 **거리**다
     */
    private boolean nearby(String id, String regionSect) {
        Map<String, Object> e = entry(id);
        Object seat = e == null ? null : e.get("seat");
        return "없음".equals(String.valueOf(seat)) || id.equals(regionSect);
    }

    /** 그 세력이 지금 이 판에 붙는가 — 임계와 보정의 계산 (숙적이 이미 끼어 있으면 불참) */
    private boolean joins(String id, int gauge, List<String> tags, Set<String> participants,
                          String regionSect) {
        boolean existential = tags.contains("존망");
        int threshold = joinThreshold(id) + tagThresholdMod(tags, id)
                + modifier(nearby(id, regionSect) ? "인접_지역" : "거리_원거리");
        boolean anchor = false;
        for (String p : participants) {
            if (p.equals(id)) {
                continue;
            }
            int rel = relation(id, p);
            if (rel <= -3 && !existential) {
                return false;   // 숙적이 낀 연합엔 들어가지 않는다 (존망 태그일 때만 예외)
            }
            if (rel >= 3) {
                threshold += modifier("동맹_이미_참여");
            } else if (rel == 2) {
                threshold += modifier("우호_참여");
            } else if (rel == -1) {
                threshold += modifier("경쟁_세력_참여");
            } else if (rel == -2) {
                threshold += modifier("원한_세력_참여");
            }
            anchor |= "sorimsa".equals(p) || "mudang".equals(p);
        }
        // anchor — 소림·무당이 나섰다는 것 자체가 명분의 보증이다 (자기 자신에겐 적용 없음)
        if (anchor && !"sorimsa".equals(id) && !"mudang".equals(id)) {
            threshold += modifier("소림_또는_무당_참여");
        }
        return gauge >= threshold;
    }

    /** size_effect — 1~2 규탄 / 3~5 소연합 / 6~9 대연합 / 10+ 무림공적_선포 */
    @SuppressWarnings("unchecked")
    String sizeName(int participants) {
        if (participants <= 0) {
            return "없음";
        }
        Map<String, Object> sizes = (Map<String, Object>) coalitionCfg().get("size_effect");
        String name = "규탄";
        for (Map.Entry<String, Object> e : sizes.entrySet()) {
            if (!e.getKey().startsWith("참여_") || !(e.getValue() instanceof Map<?, ?> m)) {
                continue;
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)")
                    .matcher(e.getKey());
            int low = matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
            if (participants >= low) {
                name = String.valueOf(((Map<String, Object>) m).get("name"));
            }
        }
        return name;
    }

    /** 관의 인식 — 무시 / 경계 / 협상 / 후퇴_검토 (연합의 크기가 곧 관의 계산이다) */
    @SuppressWarnings("unchecked")
    String gwanPerception(int participants) {
        if (participants <= 0) {
            return "무시";
        }
        Map<String, Object> sizes = (Map<String, Object>) coalitionCfg().get("size_effect");
        String perception = "무시";
        for (Map.Entry<String, Object> e : sizes.entrySet()) {
            if (!e.getKey().startsWith("참여_") || !(e.getValue() instanceof Map<?, ?> m)) {
                continue;
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)")
                    .matcher(e.getKey());
            int low = matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
            if (participants >= low) {
                Object p = ((Map<String, Object>) m).get("gwan_perception");
                perception = p == null ? perception : String.valueOf(p);
            }
        }
        return perception;
    }

    /** 맹주 — 최고 mandate_weight (동률이면 martial 이 큰 쪽). 상설 지휘부는 없다, 사안마다 새로 짜인다 */
    private String leader(Set<String> participants) {
        String best = null;
        for (String id : participants) {
            if (best == null || mandateWeight(id) > mandateWeight(best)
                    || (mandateWeight(id) == mandateWeight(best) && martial(id) > martial(best))) {
                best = id;
            }
        }
        return best;
    }

    /** 맹주 다툼 — mandate_weight 동률 + relation <= -1 인 두 세력이 함께 참여 (연합 명분 -2/주) */
    private boolean disputed(Set<String> participants) {
        List<String> list = new ArrayList<>(participants);
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                String a = list.get(i);
                String b = list.get(j);
                if (mandateWeight(a) == mandateWeight(b) && mandateWeight(a) > 0
                        && relation(a, b) <= -1) {
                    return true;
                }
            }
        }
        return false;
    }

    private int martialOf(Set<String> participants) {
        return participants.stream().mapToInt(this::martial).sum();
    }

    /** 지금 판에 선 자들 — 참여 세력·크기·맹주·무력 총합 (연합은 상태가 아니라 계산의 결과다) */
    record Coalition(List<String> participants, String size, String leader, boolean dispute,
                     int martial) {

        int count() {
            return participants.size();
        }
    }

    /** formation.naming — "참여 세력 >= 3 이면 이름이 붙는다 ('<사안> 연합' — 임시 조직이다)" */
    @SuppressWarnings("unchecked")
    int namingThreshold() {
        Map<String, Object> formation = (Map<String, Object>) coalitionCfg().get("formation");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(">=\\s*(\\d+)")
                .matcher(String.valueOf(formation.get("naming")));
        return m.find() ? Integer.parseInt(m.group(1)) : 3;
    }

    /** 이름이 붙었는가 — 규탄(1~2)은 성명서일 뿐이다. 연합은 3부터다 */
    boolean formed(int participants) {
        return participants >= namingThreshold();
    }

    /** observable 구간의 하한 (명분_8_14 → 8) — "의뢰소에 '조사' 의뢰가 붙는다"의 문턱 */
    int observableFloor(String bandKey) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^명분_(\\d+)_").matcher(bandKey);
        if (!m.find() || !RulesConfig.section(cfg, "observable").containsKey(bandKey)) {
            throw new IllegalArgumentException("등록되지 않은 관측 구간: " + bandKey);
        }
        return Integer.parseInt(m.group(1));
    }

    // ═══ ③ 법명분(法名分) — 관 측의 게이지. 대상은 개인이다 ═══

    @SuppressWarnings("unchecked")
    private Map<String, Object> mandate() {
        return RulesConfig.section(cfg, "authority_mandate");
    }

    /** authority_mandate.inputs.<키> — 포두_살해 8 · 현령_살해 14 · 관군_부대_습격 12 … */
    @SuppressWarnings("unchecked")
    int mandateInput(String key) {
        Map<String, Object> inputs = (Map<String, Object>) mandate().get("inputs");
        Object v = inputs.get(key);
        if (v == null) {
            throw new IllegalArgumentException("등록되지 않은 법명분 입력: " + key);
        }
        return intOr(v, 0);
    }

    /** authority_mandate.drains.<키> — 자수 -10 · 배상 -6 · 무림_자체_처벌 -12 (진범_규명은 전량) */
    @SuppressWarnings("unchecked")
    Integer mandateDrain(String key) {
        Map<String, Object> drains = (Map<String, Object>) mandate().get("drains");
        Object v = drains.get(key);
        return v instanceof Number n ? n.intValue() : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mandateDecay() {
        return (Map<String, Object>) mandate().get("decay");
    }

    /** 오늘 기준 법명분 정산 — 7일마다 -1. 단 15 이력이 있으면 8 아래로 내려가지 않는다 */
    int decayedMandate(int gauge, int peak, int lastDay, int today) {
        Map<String, Object> d = mandateDecay();
        int every = intOr(d.get("every_days"), 7);
        int ticks = every <= 0 ? 0 : Math.max(0, (today - lastDay) / every);
        if (ticks == 0) {
            return gauge;
        }
        int amount = Math.abs(intOr(d.get("amount"), -1));
        int floor = peak >= 15 ? intOr(d.get("floor_after_15"), 0) : 0;
        return Math.max(floor, gauge - ticks * amount);
    }

    int mandateMax() {
        @SuppressWarnings("unchecked")
        List<Object> scale = (List<Object>) mandate().get("scale");
        return scale == null ? 30 : intOr(scale.get(1), 30);
    }

    /** authority_mandate.effects — 그 게이지에서 관과 강호가 무엇을 하는가 (구간별 산문) */
    @SuppressWarnings("unchecked")
    String mandateEffect(int gauge) {
        Map<String, Object> effects = (Map<String, Object>) mandate().get("effects");
        String out = null;
        for (Map.Entry<String, Object> e : effects.entrySet()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)")
                    .matcher(e.getKey());
            if (m.find() && gauge >= Integer.parseInt(m.group(1))) {
                out = String.valueOf(e.getValue());
            }
        }
        return out;
    }

    // ═══ ③-b 강호의 절연 (murim_disavowal) — 무림이 선빵을 쳤을 때 ═══

    @SuppressWarnings("unchecked")
    private Map<String, Object> disavowal() {
        return RulesConfig.section(cfg, "murim_disavowal");
    }

    /** trigger 의 두 문턱을 읽는다: "authority_mandate >= 10 … AND 무림 명분(myeongbun) < 8" */
    private int triggerNumber(String regex, int fallback) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex)
                .matcher(String.valueOf(disavowal().get("trigger")));
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    /** 법명분이 이 값 이상이면 절연의 문턱에 선다 (10) */
    int disavowalMandateMin() {
        return triggerNumber(">=\\s*(\\d+)", 10);
    }

    /** 무림 명분이 이 값 미만이어야 절연이 성립한다 (8) — 관이 잘못한 게 있으면 얘기가 다르다 */
    int disavowalMyeongbunMax() {
        return triggerNumber("<\\s*(\\d+)", 8);
    }

    /**
     * ★ 강호의 절연 — 법명분 >= 10 AND 무림 명분 < 8.
     * 명분이 없으니 연합이 생기지 않는다. 대신 **강호가 먼저 그를 친다** —
     * 관에게 무림을 칠 구실을 주지 않으려고. 냉혹함이 아니라 생존이다.
     */
    boolean disavowed(int mandate, int murimMyeongbun) {
        return mandate >= disavowalMandateMin() && murimMyeongbun < disavowalMyeongbunMax();
    }

    @SuppressWarnings("unchecked")
    private int disavowalEffect(String key, String field, int fallback) {
        Map<String, Object> effects = (Map<String, Object>) disavowal().get("effects");
        Object e = effects.get(key);
        if (e instanceof Map<?, ?> m) {
            Object v = ((Map<String, Object>) m).get(field);
            return v instanceof Number n ? n.intValue() : fallback;
        }
        return fallback;
    }

    /** 정파 주목 +6 — 3단계(접촉·경고) 이상으로 직행 */
    int disavowalOrthodoxAttention() {
        return disavowalEffect("정파_주목", "faction_reaction.score", 6);
    }

    /** 정파 우호 -8 — ★ 공신 이력의 floor(8)조차 무너진다 (favor 규칙의 유일한 예외) */
    int disavowalOrthodoxFavor() {
        return disavowalEffect("정파_우호", "faction_reaction.favor", -8);
    }

    /** 사파 우호 -4 — 사파도 반기지 않는다. 관이 무림 전체를 훑으면 사파가 먼저 죽는다 */
    int disavowalUnorthodoxFavor() {
        return disavowalEffect("사파_우호", "faction_reaction.favor", -4);
    }

    /** 현상금 ×2 — economy.yml 고수_현상금 그대로 두고 배수만 (신규 표 없음) */
    int bountyMultiplier() {
        @SuppressWarnings("unchecked")
        Map<String, Object> effects = (Map<String, Object>) disavowal().get("effects");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("×\\s*(\\d+)")
                .matcher(String.valueOf(effects.get("현상금")));
        return m.find() ? Integer.parseInt(m.group(1)) : 2;
    }

    // ═══ ④ 관측 가능성 — 플레이어는 수치를 보지 않는다 ═══

    /** observable.명분_N_M — "산문이 닫힌다. 제자들이 하산한다. 관아가 성문을 잠근다" */
    @SuppressWarnings("unchecked")
    String observable(int gauge) {
        Map<String, Object> obs = RulesConfig.section(cfg, "observable");
        String out = null;
        for (Map.Entry<String, Object> e : obs.entrySet()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^명분_(\\d+)_(\\d+)$")
                    .matcher(e.getKey());
            if (m.matches() && gauge >= Integer.parseInt(m.group(1))) {
                out = String.valueOf(e.getValue());
            }
        }
        return gauge <= 0 ? null : out;
    }

    /** observable.법명분 — "수배 → 현상금 → 방(榜) → 어제까지 밥을 먹던 객잔이 문을 잠근다" */
    String observableMandate() {
        return String.valueOf(RulesConfig.section(cfg, "observable").get("법명분"));
    }

    /** deterrence.equilibrium.balance_point — "명분 20 · 참여 세력 6" (관이 만들지 않으려는 상태) */
    @SuppressWarnings("unchecked")
    String balancePoint() {
        Map<String, Object> deterrence = RulesConfig.section(cfg, "deterrence");
        Map<String, Object> equilibrium = (Map<String, Object>) deterrence.get("equilibrium");
        return String.valueOf(equilibrium.get("balance_point"));
    }

    // ─── 유틸 ───

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static int intOr(Object v, int fallback) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.strip().replace("+", ""));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** 진단용 — 등록된 무림 세력 수 (정파 15 + 사파 5. 아미는 예약이라 빠진다) */
    Map<String, Integer> thresholds() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String id : murim()) {
            out.put(id, joinThreshold(id));
        }
        return out;
    }
}
