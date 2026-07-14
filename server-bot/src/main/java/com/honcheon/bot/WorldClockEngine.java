package com.honcheon.bot;

import com.honcheon.core.rules.RulesConfig;
import com.honcheon.domain.RegionService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 세계 시계 (B-110) — 메인스토리가 스스로 흐르는 기계.
 * 등록부: {@code config/world_clock.yml} (막 등록부 — docs/design/world_clock.md 가 정본,
 * 이야기의 정본은 docs/story_summary.md).
 *
 * <p>하는 일은 셋뿐이다 — 전부 자정 정산({@code advanceWorld})의 한 단계로:
 * <ol>
 *   <li><b>박(拍) 발화</b> — 막 진입일 기준 상대 유효일이 차면 등록된 세계 사건이 터진다.
 *       발화가 하는 일은 전부 <b>기존 기계의 입력</b>이다: 소문 심기(spread)·명분 주입(addMyeongbun)·
 *       지역 델타(RegionService.nudge). 장 개방·세계 사건(방어전 등)은 그 기계가 아직 미배선이라
 *       기록만 남긴다 (설계 §5 주의 — 시계는 무대 없이도 돈다).</li>
 *   <li><b>막 진입</b> — 유효일 E = 세계일 D + 박자보정 T(현재 막) 가 base_day 를 넘고
 *       이전 막의 종결박이 발화했으면 다음 막으로. auto 는 자정에 스스로,
 *       <b>human 은 사람의 승인({@code /막개전}) 없이는 절대 넘어가지 않는다</b> (설계 §4 —
 *       살상 PvP 개방과 맞물리므로 자동 진입 금지. 승인 전의 세계는 '개전 전야'에 머문다).</li>
 *   <li><b>전조(前兆)</b> — 전환 예정 유효일 {@code herald_lead_days}일 전부터 등록 전조 소문을
 *       심는다. 되돌림 불가(§6)의 대가다 — 세계가 먼저 수군거린다.</li>
 * </ol>
 *
 * <p><b>수치·문구는 등록부가 정본이다 — 이 코드는 지어내지 않는다.</b> 막 이름·박 일정·강도·태그·
 * 망·게이지 전부 world_clock.yml 에서 온다. 등록부에 없는 것 둘만 기존 등록 상수를 빌린다:
 * 박 소문의 정확도는 {@code rumor.yml initial_accuracy.간접_전문} (세계 개막 소문
 * seedWorldRumors 와 같은 관행 — "목격자 없는 들리는 이야기"), 명분 주입의 발원 정확도는
 * {@code 직접_목격} (정본 사건은 사실이다 — 사실적 밴드 ×1.0 이라야 등록 게이지가 등록값대로 선다).
 *
 * <p><b>상태는 전부 world_meta 키밸류다</b> (스키마 변경 없음 — 등록부 meta.state_keys):
 * <pre>
 *   세계막                현재 막 id, 또는 '관문_대기' (human gate 성숙 — §4)
 *   막진입일              현재 막에 들어선 세계일 (D 기준)
 *   박자보정:&lt;막id&gt;      막별 누적 보정(일). 감쇠 없음, ±tempo_clamp 로 clamp.
 *                         ★ 입력 배선(설계 §8 ④)은 장 엔진(P2)과 함께 — 지금은 읽기만 한다 (기본 0)
 *   박발화:&lt;막id&gt;.&lt;박key&gt;  발화한 박의 멱등 표식 (값 = 발화 세계일) — 구현 장부
 *   전조발화:&lt;막id&gt;       전조를 심은 세계일 — 소문 수명이 다하면 다시 심는다 (전조 '유지')
 * </pre>
 * 원장(events)에는 두 타입만 새로 적는다 (등록부 meta.event_types): {@code 막전환}·{@code 막관문}.
 *
 * <p><b>수치 노출 금지</b> (world_reaction_system 총원칙): 막·유효일·박자보정 숫자는 사람에게 내지
 * 않는다. Dawn 리포트에 실리는 것은 등록 막 이름 한 줄(§6 공지 문법)과 소문뿐이다.
 *
 * <p><b>등록부가 깨져도 봇은 죽지 않는다</b> (PersonalStory 와 같은 문법) — severe 한 줄 내고
 * 시계만 잠긴다. 정산 중의 실패도 그 날의 시계 걸음만 건너뛰고 소리를 남긴다 (침묵 금지).
 */
final class WorldClockEngine {

    /** 소문을 심는 손 — GameListener.spread 의 모양 그대로 (전파·세력 인지 배선을 재사용한다) */
    interface Planter {
        int plant(String group, String truth, String subject, Long subjectId, List<String> tags,
                  int intensity, int accuracy, String originNet, int day) throws Exception;
    }

    /** {@code /막개전} 의 대답 — ok 가 아니면 아무것도 바뀌지 않았다는 뜻이다 */
    record Approval(boolean ok, String body) {
    }

    /** 박 — 막 진입일 기준 상대 유효일 {@code at} 에 발화하는 세계 사건 (do 는 기존 기계의 입력) */
    private record Beat(String key, int at, Map<String, Object> does, String resolve) {
    }

    private record Act(String id, String name, int order, int baseDay, String gate,
                       String requiresBeat, List<Beat> beats,
                       List<Map<String, Object>> heralds, Map<String, Object> rulesShift) {
    }

    static final String KEY_ACT = "세계막";
    static final String KEY_ENTRY = "막진입일";
    static final String KEY_TEMPO = "박자보정:";
    static final String KEY_BEAT = "박발화:";
    static final String KEY_HERALD = "전조발화:";
    /** human gate 가 성숙해 승인을 기다리는 상태 — 세계는 '개전 전야'에 머문다 (설계 §4) */
    static final String WAITING = "관문_대기";
    static final String EVENT_TRANSITION = "막전환";
    static final String EVENT_GATE = "막관문";
    /** 원장의 행위자 — 세계 시계가 적은 것임을 감사가 알아보게 */
    static final String ACTOR = "세계시계";

    private final Rules rules;
    private final GameStore db;
    private final RegionService regions;
    private final Planter planter;

    private final List<Act> acts = new ArrayList<>();          // order 순
    private final Map<String, Act> byId = new LinkedHashMap<>();
    private int tempoClamp;
    private int heraldLeadDays;
    private final String fault;                                // null 이면 건강

    /** HoncheonBot·PersonalStory 와 같은 규약 — 설정 디렉터리는 HONCHEON_CONFIG 하나로 정해진다 */
    WorldClockEngine(Rules rules, GameStore db, RegionService regions, Planter planter) {
        this(Path.of(System.getenv().getOrDefault("HONCHEON_CONFIG", "config")),
                rules, db, regions, planter);
    }

    @SuppressWarnings("unchecked")
    WorldClockEngine(Path configDir, Rules rules, GameStore db, RegionService regions,
                     Planter planter) {
        this.rules = rules;
        this.db = db;
        this.regions = regions;
        this.planter = planter;
        String broken = null;
        try {
            Map<String, Object> cfg = RulesConfig.load(configDir.resolve("world_clock.yml"));
            Map<String, Object> clock = RulesConfig.section(cfg, "clock");
            this.tempoClamp = RulesConfig.intValue(clock.get("tempo_clamp"));
            this.heraldLeadDays = RulesConfig.intValue(clock.get("herald_lead_days"));
            parseActs(RulesConfig.section(cfg, "acts"));
        } catch (Exception e) {
            broken = e.getMessage() == null ? e.toString() : e.getMessage();
            System.err.println("세계 시계: 등록부(config/world_clock.yml)를 못 세웠다 — 시계가 잠긴다: "
                    + broken);
            acts.clear();
            byId.clear();
        }
        this.fault = broken;
    }

    @SuppressWarnings("unchecked")
    private void parseActs(Map<String, Object> raw) {
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            Map<String, Object> a = (Map<String, Object>) e.getValue();
            Map<String, Object> entry = (Map<String, Object>) a.get("entry");
            String gate = String.valueOf(entry.get("gate"));
            if (!"auto".equals(gate) && !"human".equals(gate)) {
                throw new IllegalStateException("막 " + e.getKey() + " — 등록되지 않은 gate: " + gate);
            }
            if ("human".equals(gate)) {
                Map<String, Object> approval = (Map<String, Object>) entry.get("approval");
                String command = approval == null ? null : String.valueOf(approval.get("command"));
                if (!"/막개전".equals(command)) {
                    throw new IllegalStateException("막 " + e.getKey()
                            + " — human gate 인데 approval.command 가 /막개전 이 아니다: " + command);
                }
            }
            List<Beat> beats = new ArrayList<>();
            for (Map<String, Object> b : (List<Map<String, Object>>) a.getOrDefault("beats", List.of())) {
                Map<String, Object> does = (Map<String, Object>) b.getOrDefault("do", Map.of());
                for (String doKey : does.keySet()) {
                    if (!List.of("rumor", "chapter_open", "myeongbun", "region_delta", "world_event")
                            .contains(doKey)) {
                        throw new IllegalStateException("막 " + e.getKey() + " 박 " + b.get("key")
                                + " — 등록되지 않은 do 유형: " + doKey);
                    }
                }
                Map<String, Object> rumor = (Map<String, Object>) does.get("rumor");
                if (rumor != null && !rules.rumors.networks().contains(String.valueOf(rumor.get("망")))) {
                    throw new IllegalStateException("막 " + e.getKey() + " 박 " + b.get("key")
                            + " — rumor.yml 에 없는 망: " + rumor.get("망"));
                }
                beats.add(new Beat(String.valueOf(b.get("key")), RulesConfig.intValue(b.get("at")),
                        does, String.valueOf(b.getOrDefault("resolve", ""))));
            }
            List<Map<String, Object>> heralds = new ArrayList<>();
            for (Map<String, Object> h : (List<Map<String, Object>>) a.getOrDefault("heralds", List.of())) {
                Map<String, Object> rumor = (Map<String, Object>) h.get("rumor");
                if (rumor == null || !rules.rumors.networks().contains(String.valueOf(rumor.get("망")))) {
                    throw new IllegalStateException("막 " + e.getKey() + " — 전조가 rumor 가 아니거나 망이 미등록");
                }
                heralds.add(rumor);
            }
            Act act = new Act(e.getKey(), String.valueOf(a.get("name")),
                    RulesConfig.intValue(a.get("order")),
                    RulesConfig.intValue(entry.getOrDefault("base_day", 0)), gate,
                    entry.get("requires_beat") == null ? null : String.valueOf(entry.get("requires_beat")),
                    beats, heralds,
                    (Map<String, Object>) a.getOrDefault("rules_shift", Map.of()));
            byId.put(act.id(), act);
        }
        // order 순으로 눕히고 사슬을 검산한다 — 등록부가 스스로 모순이면 시계를 세우지 않는다
        byId.values().stream().sorted(java.util.Comparator.comparingInt(Act::order)).forEach(acts::add);
        if (acts.isEmpty()) {
            throw new IllegalStateException("acts 절이 비었다 — 막 없는 세계 시계는 시계가 아니다");
        }
        for (int i = 0; i < acts.size(); i++) {
            Act act = acts.get(i);
            if (act.order() != i) {
                throw new IllegalStateException("막 order 가 연속이 아니다: " + act.id() + " = " + act.order());
            }
            if (i == 0) {
                continue;
            }
            String req = act.requiresBeat();
            if (req == null) {
                throw new IllegalStateException("첫 막이 아닌데 requires_beat 이 없다: " + act.id());
            }
            String prevId = acts.get(i - 1).id();
            if (!req.startsWith(prevId + ".")
                    || acts.get(i - 1).beats().stream()
                            .noneMatch(b -> req.equals(prevId + "." + b.key()))) {
                throw new IllegalStateException("막 " + act.id() + " — requires_beat 이 직전 막("
                        + prevId + ")의 박을 가리키지 않는다: " + req);
            }
        }
    }

    /** 시계가 잠겨 있으면 그 이유 — null 이면 건강 (감사·시험용) */
    String fault() {
        return fault;
    }

    // ─── 자정 정산의 한 걸음 — advanceWorld 가 부른다 ───

    /**
     * 세계 시계 한 걸음: 박 발화 → 전조 → 막 진입 판정. 반환은 Dawn 리포트에 얹을 문장들
     * (빈 문자열 = 오늘 시계가 겉으로 한 일 없음). 실패는 삼키지 않는다 — 리포트와 stderr 에 남기되,
     * 정산의 나머지(빈사 마감 등)를 길동무로 죽이지는 않는다.
     */
    String tick(int day) {
        if (fault != null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        try {
            step(day, out);
        } catch (Exception e) {
            System.err.println("세계 시계 정산 실패 (세계일 " + day + "): " + e);
            out.append("🕰️ 세계 시계가 오늘 걸음을 건너뛰었다 — ").append(e.getMessage()).append('\n');
        }
        return out.toString();
    }

    private void step(int day, StringBuilder out) throws Exception {
        String cur = meta(KEY_ACT).orElse(null);
        if (cur == null) {
            // 개벽 — 등록부의 첫 막으로 들어선다 (프롤로그: base_day 1, gate auto, 조건 없음)
            enter(acts.get(0), day, "개벽", 0, out);
            cur = acts.get(0).id();
        }
        if (WAITING.equals(cur)) {
            // 개전 전야 — 박은 멈추고 전조 소문만 유지된다 (설계 §4). 승인은 /막개전 뿐이다.
            Act pending = pendingAct();
            if (pending == null) {
                // 침묵 금지 — 장부(원장 막관문)와 등록부가 어긋났다. 세계는 전야에 머문다
                throw new IllegalStateException("관문_대기 인데 원장의 '막관문' 기록이 없거나 등록부 밖의 막을 가리킨다");
            }
            maintainHeralds(pending, day, out);
            return;
        }
        Act act = byId.get(cur);
        if (act == null) {
            throw new IllegalStateException("world_meta 세계막 '" + cur + "' 이 등록부에 없다");
        }
        // 가감으로 하루에 여러 문턱을 넘을 수 있다 — 막 수만큼만 돈다 (교착·폭주 둘 다 불능)
        for (int guard = 0; guard <= acts.size(); guard++) {
            fireDueBeats(act, day, out);
            if (act.order() + 1 >= acts.size()) {
                return;                                        // 마지막 막 — 시즌 마감은 사람의 일이다
            }
            Act next = acts.get(act.order() + 1);
            boolean ripeBeat = beatFired(act.id(), stripActPrefix(next.requiresBeat(), act.id()));
            int effective = day + tempo(act.id());             // 유효일 E = D + T(현재 막)
            if (ripeBeat && effective >= next.baseDay() - heraldLeadDays) {
                maintainHeralds(next, day, out);               // 전조 — 세계가 먼저 수군거린다 (§6)
            }
            if (!ripeBeat || effective < next.baseDay()) {
                return;                                        // 아직이다 — 기본 시계만 흐른다
            }
            if ("human".equals(next.gate())) {
                waitAtGate(act, next, day, out);               // 사람의 승인 없이는 넘어가지 않는다 (§4)
                return;
            }
            enter(next, day, act.id(), tempo(act.id()), out);
            act = next;
        }
    }

    /** requires_beat "막id.박key" 에서 박key 만 — 등록부 검산이 접두를 이미 보증했다 */
    private static String stripActPrefix(String requiresBeat, String actId) {
        return requiresBeat.substring(actId.length() + 1);
    }

    // ─── 박 발화 — 등록된 세계 사건이 기존 기계로 흘러든다 ───

    private void fireDueBeats(Act act, int day, StringBuilder out) throws Exception {
        int entryDay = meta(KEY_ENTRY).map(Integer::parseInt).orElse(day);
        int elapsed = (day - entryDay) + tempo(act.id());      // 막 진입일 기준 상대 유효일
        for (Beat beat : act.beats()) {
            if (beat.at() > elapsed || beatFired(act.id(), beat.key())) {
                continue;
            }
            db.setMeta(KEY_BEAT + act.id() + "." + beat.key(), String.valueOf(day));
            fire(act, beat, day, out);
        }
    }

    @SuppressWarnings("unchecked")
    private void fire(Act act, Beat beat, int day, StringBuilder out) throws Exception {
        String rumorGroup = null;
        Map<String, Object> rumor = (Map<String, Object>) beat.does().get("rumor");
        if (rumor != null) {
            rumorGroup = ACTOR + ":" + act.id() + "." + beat.key();
            plantRumor(rumorGroup, rumor, day, out);
        }
        Map<String, Object> myeongbun = (Map<String, Object>) beat.does().get("myeongbun");
        if (myeongbun != null) {
            injectMyeongbun(myeongbun, rumorGroup, day, out);
        }
        Map<String, Object> delta = (Map<String, Object>) beat.does().get("region_delta");
        if (delta != null) {
            nudgeRegion(delta, out);
        }
        Object chapter = beat.does().get("chapter_open");
        if (chapter != null) {
            // 장 엔진 미배선 (chapter_events.yml unwired) — 시계는 무대 없이 돈다 (설계 §5 주의).
            // 조용히 넘기지 않는다: 콘솔에 남겨 배선 빚을 소리나게 한다.
            System.out.println("세계 시계 — 박 " + act.id() + "." + beat.key() + " 의 장 개방 '"
                    + chapter + "' 은 장 엔진 미배선으로 발화하지 못했다 (B-110 설계 §5)");
        }
        Object worldEvent = beat.does().get("world_event");
        if (worldEvent != null) {
            // 무대(마크 전투 이벤트)는 별도 청구서 (설계 §8 ⑤) — 결말은 정본(resolve)이 정한다
            System.out.println("세계 시계 — 세계 사건 '" + worldEvent + "' (" + act.id() + "."
                    + beat.key() + ") — 무대 미배선. 정본 결말: " + beat.resolve());
            out.append("⚔️ ").append(beat.resolve()).append('\n');
        }
    }

    /**
     * 박·전조의 소문 — 등록부의 강도·태그·망 그대로, 문안은 문안키에서 편다.
     * ★ 등록부에 문안 산문이 아직 없다 — 키(밑줄→빈칸)가 곧 문장이다. 산문 등재는 사람의 몫이다.
     */
    @SuppressWarnings("unchecked")
    private void plantRumor(String group, Map<String, Object> rumor, int day, StringBuilder out)
            throws Exception {
        List<String> tags = ((List<Object>) rumor.getOrDefault("태그", List.of()))
                .stream().map(String::valueOf).toList();
        String truth = String.valueOf(rumor.get("문안키")).replace('_', ' ');
        planter.plant(group, truth, null, null, tags, RulesConfig.intValue(rumor.get("강도")),
                rules.initialAccuracy("간접_전문"), String.valueOf(rumor.get("망")), day);
        out.append("🗣️ 흉흉한 말이 돈다 — “").append(truth).append("”\n");
    }

    /** 명분 주입 — adminMyeongbun 과 같은 장부(addMyeongbun)로. 발원 소문이 참전 시차를 만든다 */
    @SuppressWarnings("unchecked")
    private void injectMyeongbun(Map<String, Object> myeongbun, String rumorGroup, int day,
                                 StringBuilder out) throws Exception {
        String issue = String.valueOf(myeongbun.get("issue"));
        String target = myeongbun.containsKey("target") ? String.valueOf(myeongbun.get("target"))
                : issue.substring(issue.lastIndexOf(':') + 1);   // 등록 사안 키의 문법: 사건:대상
        if (rules.politics.coalitionOf(target) == null) {
            throw new IllegalStateException("명분 대상이 등록부에 없다: " + target);
        }
        List<String> tags = ((List<Object>) myeongbun.getOrDefault("tags", List.of()))
                .stream().map(String::valueOf).toList();
        List<String> victims = ((List<Object>) myeongbun.getOrDefault("victims_add", List.of()))
                .stream().map(String::valueOf).toList();
        int gauge = myeongbun.containsKey("gauge") ? RulesConfig.intValue(myeongbun.get("gauge")) : 0;
        db.addMyeongbun(issue, target, victims, tags, gauge,
                rules.initialAccuracy("직접_목격"),   // 정본 사건은 사실이다 — 사실적 밴드 ×1.0
                rumorGroup, null, day, rules.politics.gaugeMax(), rules.politics);
        db.logEvent("명분", "world", ACTOR, "faction", issue,
                Map.of("사안", issue, "대상", target, "가산", gauge, "태그", tags, "피해", victims));
        out.append("🏛️ 강호에 명분이 선다 — ").append(rules.factionName(target))
                .append("을(를) 두고 말이 무거워진다.\n");
    }

    /** 지역 델타 — 등록 지역(첫 고을)만. 다지역 전파는 설계 미결(§9)이므로 다른 지역은 소리내고 넘긴다 */
    private void nudgeRegion(Map<String, Object> delta, StringBuilder out) throws Exception {
        String region = String.valueOf(delta.getOrDefault("region", WorldStore.PRIMARY_REGION));
        if (!WorldStore.PRIMARY_REGION.equals(region)) {
            System.err.println("세계 시계 — 미등록 지역 델타를 건너뛴다 (다지역은 설계 미결 §9): " + region);
            return;
        }
        Map<String, Integer> deltas = new LinkedHashMap<>();
        delta.forEach((k, v) -> {
            if (!"region".equals(k)) {
                deltas.put(k, RulesConfig.intValue(v));
            }
        });
        regions.nudge(deltas);
        out.append("🏞️ 고을의 공기가 달라졌다 — 거리의 표정이 굳는다.\n");
    }

    // ─── 전조·관문·전환 ───

    /**
     * 전조 소문 — 전환 예정 유효일 {@code herald_lead_days}일 전부터, 그리고 '개전 전야' 동안 유지.
     * 유지 = 심은 소문의 수명(강도 × 감쇠 주기)이 다하면 다시 심는다 — 전야의 세계가 조용해지지 않게.
     */
    private void maintainHeralds(Act next, int day, StringBuilder out) throws Exception {
        if (next.heralds().isEmpty()) {
            return;
        }
        Optional<Integer> last = meta(KEY_HERALD + next.id()).map(Integer::parseInt);
        int minIntensity = next.heralds().stream()
                .mapToInt(h -> RulesConfig.intValue(h.get("강도"))).min().orElse(1);
        int lifetime = Math.max(1, minIntensity * rules.rumors.decayEveryDays());
        if (last.isPresent() && day - last.get() < lifetime) {
            return;
        }
        db.setMeta(KEY_HERALD + next.id(), String.valueOf(day));
        for (Map<String, Object> herald : next.heralds()) {
            plantRumor(ACTOR + ":전조:" + next.id() + ":" + day, herald, day, out);
        }
    }

    /**
     * human gate 성숙 — 세계막을 '관문_대기'로, 원장에 '막관문'을, 관리자에게 소리를 (설계 §4 ②).
     * Dawn(공개 채널)에는 넣지 않는다 — 세계에는 전조 소문만 들린다. 관리자 채널 등록부가 아직 없어
     * 소리는 콘솔에 남는다 (보고 사항).
     */
    private void waitAtGate(Act cur, Act next, int day, StringBuilder out) throws Exception {
        db.setMeta(KEY_ACT, WAITING);
        db.logEvent(EVENT_GATE, "world", ACTOR, "act", next.id(),
                Map.of("day", day, "대기막", next.id(), "직전막", cur.id(),
                        "박자보정", tempo(cur.id())));
        System.out.println("세계 시계 — 막관문 대기: " + next.id() + " (직전막 " + cur.id()
                + ", 세계일 " + day + "). 승인은 /막개전 (MANAGE_SERVER) 뿐이다 — 그때까지 개전 전야다");
        maintainHeralds(next, day, out);
    }

    /** 막 전환 — 불가역 (설계 §6). 롤백 명령은 만들지 않는다. 원장·콘솔·Dawn 에 소리를 낸다 */
    private void enter(Act next, int day, String prevId, int prevTempo, StringBuilder out)
            throws Exception {
        db.setMeta(KEY_ACT, next.id());
        db.setMeta(KEY_ENTRY, String.valueOf(day));
        db.logEvent(EVENT_TRANSITION, "world", ACTOR, "act", next.id(),
                Map.of("day", day, "이전막", prevId, "새막", next.id(), "박자보정", prevTempo));
        System.out.println("세계 시계 — 막전환: " + prevId + " → " + next.id() + " (세계일 " + day + ")");
        out.append("🕰️ **").append(next.name()).append("** — 세상이 한 굽이를 넘었다.\n");
    }

    /** '관문_대기'가 기다리는 막 — 원장의 마지막 '막관문' 기록이 가리킨다 (원장이 곧 상태다) */
    private Act pendingAct() throws Exception {
        List<Map<String, Object>> gates = db.eventsOf(EVENT_GATE, ACTOR);
        if (gates.isEmpty()) {
            return null;
        }
        return byId.get(String.valueOf(gates.get(gates.size() - 1).get("target_id")));
    }

    // ─── /막개전 — 사람의 승인 (설계 §4 ③④) ───

    /**
     * 관문을 연다 — '관문_대기'일 때만. 승인 즉시 전환하고 상대 유효일 0의 박을 발화한다
     * ("승인 시점의 자정 정산 <b>또는 즉시</b>" — 설계 §4 ④의 즉시 쪽. 기다릴 이유가 없다).
     */
    Approval approve(int day) throws Exception {
        if (fault != null) {
            return new Approval(false, "세계 시계가 잠겨 있다 — 등록부(config/world_clock.yml)부터: " + fault);
        }
        String cur = meta(KEY_ACT).orElse(null);
        if (!WAITING.equals(cur)) {
            return new Approval(false, "열 관문이 없다 — 관문은 조건(유효일·종결박)이 차면 스스로 대기 상태가 되고, "
                    + "그때에만 이 명령이 연다. 세계는 제 시계로 가고 있다"
                    + (cur == null ? " (아직 개막 전 — 다음 정산에 첫 막이 선다)" : "") + ".");
        }
        Act pending = pendingAct();
        if (pending == null) {
            return new Approval(false, "관문_대기 인데 원장의 '막관문' 기록이 없거나 등록부 밖의 막을 "
                    + "가리킨다 — 장부가 어긋났다. events 원장과 world_clock.yml 을 대조하라 "
                    + "(이 명령은 아무것도 바꾸지 않았다).");
        }
        List<Map<String, Object>> gates = db.eventsOf(EVENT_GATE, ACTOR);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) gates.get(gates.size() - 1).get("data");
        String prevId = String.valueOf(data.getOrDefault("직전막", WAITING));
        StringBuilder out = new StringBuilder();
        enter(pending, day, prevId, tempo(prevId), out);
        fireDueBeats(pending, day, out);
        if (!pending.rulesShift().isEmpty()) {
            out.append("⚖️ 규칙 전환 — ").append(pending.rulesShift())
                    .append(" *(등록부 선언 — 실제 개방 배선은 별도 청구서: B-111 접경)*\n");
        }
        out.append("\n되돌림은 없다 (설계 §6) — 세계는 앞으로만 흐른다.");
        return new Approval(true, out.toString());
    }

    // ─── 낮은 손들 ───

    private Optional<String> meta(String key) throws Exception {
        return db.getMeta(key);
    }

    private boolean beatFired(String actId, String beatKey) throws Exception {
        return meta(KEY_BEAT + actId + "." + beatKey).isPresent();
    }

    /** 막의 박자보정 T — 감쇠 없음, ±tempo_clamp clamp. 입력 배선(§8 ④)은 장 배선(P2)과 함께 */
    private int tempo(String actId) throws Exception {
        int t = meta(KEY_TEMPO + actId).map(Integer::parseInt).orElse(0);
        return Math.max(-tempoClamp, Math.min(tempoClamp, t));
    }
}
