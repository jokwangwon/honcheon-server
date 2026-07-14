package com.honcheon.bot;

import com.honcheon.core.rules.RulesConfig;
import com.honcheon.domain.FactionService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 개인 메인스토리 (B-109) — 발단별 목표 사슬의 로더 + 마디 평가기.
 * 등록부: {@code config/personal_story.yml} (docs/design/personal_story.md 가 정본).
 *
 * <p><b>의미의 렌즈다 — 새 콘텐츠가 아니다.</b> 마디({@code beats[].done_when})의 판정은 전부
 * <b>봇이 이미 세고 있는 원장</b>(events·소지품·favor·경지·기연)만 읽는다. 이 클래스는 세계에
 * 아무것도 쓰지 않는다 — 단 하나의 예외가 <b>제 상태</b>({@code events} 의 {@code 사슬_마디} 한 건,
 * 멱등)다. 원장이 곧 상태다 (시트 중복 저장 없음).
 *
 * <p><b>마디는 레일이 아니다</b>: 표기 순서는 서사의 편의일 뿐, 평가는 독립 조건식이다.
 * 뒤 마디의 조건이 먼저 차면 앞 마디는 <b>함께 닫힌다</b> (소급 인정 — 설계 §2.2).
 *
 * <p><b>★ 두 개의 키 어긋남을 여기서 정규화한다</b> (설계 §7 청구서의 경고 그대로):
 * <ol>
 *   <li>시트의 발단은 밑줄→빈칸 치환으로 저장된다 (GameListener 탄생부) —
 *       {@link #chainKeyOf} 가 원 키로 되돌려 등록부와 조인한다.</li>
 *   <li>대화 원장의 target 은 <b>표시 이름</b>(곽진)으로 적힌다 (GameListener 대화부) —
 *       조건은 NPC <b>키</b>(gwakjin)로 쓰므로, 평가기가 npcs 등록부로 양쪽을 다 받는다.</li>
 * </ol>
 *
 * <p><b>등록부가 깨져도 봇은 죽지 않는다</b> (Rules.panelCfg 와 같은 문법) — severe 한 줄 내고
 * 사슬만 잠긴다: {@code tick}/{@code heart} 가 조용히 빈손을 돌려준다.
 *
 * <p>수치 은닉 (설계 §3): 이 클래스가 사람에게 내는 문장은 등록부의 {@code heart}/{@code epilogue_heart}
 * 그대로다 — "2/4" 류의 진행 표기를 코드가 지어 붙이지 않는다.
 */
public final class PersonalStory {

    /** 마디 닫힘이 원장에 적히는 사건 유형 — 원장이 곧 사슬의 상태다 */
    static final String BEAT_EVENT = "사슬_마디";

    private final Map<String, Object> cfg;          // personal_story.yml 통째
    private final List<String> realmLadder;         // cultivation_stages 이름 사다리
    private final Map<String, String> npcNames;     // NPC 키 → 표시 이름 (대화 원장 정규화)
    private final String fault;                     // null 이면 건강

    /** HoncheonBot·Narration 과 같은 규약 — 설정 디렉터리는 HONCHEON_CONFIG 하나로 정해진다 */
    public static PersonalStory load() {
        return new PersonalStory(Path.of(System.getenv().getOrDefault("HONCHEON_CONFIG", "config")));
    }

    @SuppressWarnings("unchecked")
    public PersonalStory(Path configDir) {
        Map<String, Object> loaded = null;
        List<String> ladder = List.of();
        Map<String, String> names = Map.of();
        String broken = null;
        try {
            loaded = RulesConfig.load(configDir.resolve("personal_story.yml"));
            if (!(loaded.get("chains") instanceof Map<?, ?> chains) || chains.isEmpty()) {
                throw new IllegalStateException("chains 절이 없거나 비었다");
            }
            List<Map<String, Object>> stages = (List<Map<String, Object>>)
                    RulesConfig.load(configDir.resolve("cultivation.yml")).get("cultivation_stages");
            ladder = stages.stream().map(s -> String.valueOf(s.get("name"))).toList();
            // 대화 원장 정규화용 — 등록부가 없어도 사슬은 산다 (키 그대로만 대조하게 된다)
            Map<String, String> byKey = new LinkedHashMap<>();
            try {
                Map<String, Object> npcs = RulesConfig.section(
                        RulesConfig.load(configDir.resolve("npcs/cheongha_npcs.yml")), "npcs");
                npcs.forEach((k, v) -> {
                    if (v instanceof Map<?, ?> npc && npc.get("name") != null) {
                        byKey.put(k, String.valueOf(npc.get("name")));
                    }
                });
            } catch (RuntimeException npcRegistryBroken) {
                System.err.println("개인 사슬: NPC 등록부를 못 읽었다 — 대화 대조는 키로만 한다: "
                        + npcRegistryBroken.getMessage());
            }
            names = Map.copyOf(byKey);
        } catch (RuntimeException e) {
            broken = e.getMessage();
            // ★ severe + 기능 무 — 봇은 죽지 않는다 (안내판 등록부와 같은 무게 배분)
            System.err.println("개인 사슬 등록부를 못 읽었다 (사슬만 잠긴다) — config/personal_story.yml: "
                    + broken);
        }
        this.cfg = loaded == null ? Map.of() : loaded;
        this.realmLadder = ladder;
        this.npcNames = names;
        this.fault = broken;
    }

    public boolean disabled() {
        return fault != null;
    }

    public String fault() {
        return fault;
    }

    // ─── 표현 (presentation) — 문장은 등록부가 진다, 코드는 지어내지 않는다 ───

    /** /혼천 정보 의 칸 이름 — presentation.sheet_field ("심중(心中)") */
    public String sheetField() {
        return text(sub(cfg, "presentation"), "sheet_field", "심중(心中)");
    }

    /** 사슬이 다 닫힌 뒤의 심중 — presentation.epilogue_heart (회향의 문장) */
    public String epilogueHeart() {
        return text(sub(cfg, "presentation"), "epilogue_heart", "");
    }

    // ─── 사슬 해석 — 등록제: 여기 없는 발단은 default 로 떨어진다 ───

    /**
     * 시트의 발단 → 사슬 키. <b>밑줄→빈칸 치환의 역치환</b>이 여기다: 시트에는
     * "가문의 몰락"(빈칸)으로 적혀 있고, 등록부 키는 "가문의_몰락"(밑줄)이다.
     * 미등록 발단은 {@code default} (안전판 — seojang incident_opening.default 와 같은 이유).
     */
    public String chainKeyOf(Object sheetIncident) {
        if (disabled()) {
            return null;
        }
        String key = sheetIncident == null ? "" : String.valueOf(sheetIncident).strip().replace(' ', '_');
        return chains().containsKey(key) ? key : "default";
    }

    /** 이 사슬의 마디 목록 (표기 순서 = 서사의 순서 — 평가는 독립이다) */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> beats(String chainKey) {
        Object chain = chains().get(chainKey);
        if (chain instanceof Map<?, ?> c && c.get("beats") instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    /** 서장 에필로그 뒤에 붙는 여운 한 줄 — chains.&lt;발단&gt;.echo (⑤ echo 배선이 읽는다) */
    public String echo(String chainKey) {
        Object chain = chains().get(chainKey);
        return chain instanceof Map<?, ?> c && c.get("echo") != null
                ? String.valueOf(c.get("echo")) : null;
    }

    // ─── 평가 — 원장을 읽고, 닫힌 마디를 원장에 적는다 (멱등) ───

    /**
     * 마디 재평가 + 전이 기록. logEvent 훅과 시트 조회가 부른다 (폴링 없음).
     *
     * <p>마디 {@code i} 는 <b>자신 뒤의 어느 마디든</b> 조건이 차면 함께 닫힌다 (소급 인정).
     * 한 번 닫힌 마디는 원장({@code 사슬_마디})이 기억한다 — favor 감쇠·소지품 상실로 조건이
     * 도로 풀려도 <b>다시 열리지 않는다</b> (사슬은 기억이지 상태 기계가 아니다).
     *
     * @return 이번에 새로 닫힌 마디 id 들 (표기 순서). 없으면 빈 목록
     */
    public List<String> tick(EventStore db, FactionService factions, long chId, int today,
                             String realm, Map<String, Object> sheet) throws Exception {
        if (disabled()) {
            return List.of();
        }
        String chainKey = chainKeyOf(sheet == null ? null : sheet.get("발단"));
        List<Map<String, Object>> beats = beats(chainKey);
        String actorId = String.valueOf(chId);
        int n = beats.size();
        boolean[] done = new boolean[n];
        for (int i = 0; i < n; i++) {
            done[i] = db.eventExists(BEAT_EVENT, actorId, beatMark(chainKey, beats.get(i)))
                    || satisfiedAll(beats.get(i).get("done_when"),
                            new Ctx(db, factions, chId, today, realm, sheet));
        }
        // 소급: 뒤가 차면 앞도 닫힌다 — 사슬을 모르고 논 시간도 사슬의 시간이었다
        List<String> newly = new ArrayList<>();
        boolean anyBehind = false;
        for (int i = n - 1; i >= 0; i--) {
            anyBehind = anyBehind || done[i];
            if (!anyBehind) {
                continue;
            }
            Map<String, Object> beat = beats.get(i);
            String mark = beatMark(chainKey, beat);
            if (!db.eventExists(BEAT_EVENT, actorId, mark)) {
                db.logEvent(BEAT_EVENT, "character", actorId, "story", mark,
                        Map.of("사슬", chainKey,
                                "마디", String.valueOf(beat.get("id")),
                                "회상", text(beat, "recall", "")));
                newly.add(String.valueOf(beat.get("id")));
            }
        }
        java.util.Collections.reverse(newly);   // 표기 순서로 (뒤에서 앞으로 적었다)
        return newly;
    }

    /**
     * 심중(心中) 한 줄 — 현재 <b>열린</b> 첫 마디의 {@code heart}. 전부 닫혔으면 회향의 문장
     * ({@code epilogue_heart}). 판정은 원장({@code 사슬_마디})만 본다 — {@link #tick} 뒤에 부르면 정확하다.
     *
     * @return 등록부의 문장 그대로 (수치·단계 표기 없음). 등록부가 잠겼으면 null
     */
    public String heart(EventStore db, long chId, Map<String, Object> sheet) throws Exception {
        if (disabled()) {
            return null;
        }
        String chainKey = chainKeyOf(sheet == null ? null : sheet.get("발단"));
        for (Map<String, Object> beat : beats(chainKey)) {
            if (!db.eventExists(BEAT_EVENT, String.valueOf(chId), beatMark(chainKey, beat))) {
                return text(beat, "heart", null);
            }
        }
        String after = epilogueHeart();
        return after.isBlank() ? null : after;
    }

    /** 멱등 표식 — events.target_id 한 칸에 사슬과 마디가 함께 적힌다 ("습격:벽") */
    private static String beatMark(String chainKey, Map<String, Object> beat) {
        return chainKey + ":" + beat.get("id");
    }

    // ─── 조건식 — done_when 어휘 (condition_vocab: event·item·item_any·favor·realm_min·fortune·any[·gate]) ───

    private record Ctx(EventStore db, FactionService factions, long chId, int today,
                       String realm, Map<String, Object> sheet) {
    }

    /** 목록 최상위는 AND (faction_entry_routes 와 같은 문법) */
    private boolean satisfiedAll(Object conds, Ctx ctx) throws Exception {
        if (!(conds instanceof List<?> list) || list.isEmpty()) {
            return false;   // 조건 없는 마디는 닫히지 않는다 — 등록부 실수가 자동 완주가 되면 안 된다
        }
        for (Object cond : list) {
            if (!(cond instanceof Map<?, ?> m) || !one(m, ctx)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private boolean one(Map<?, ?> cond, Ctx ctx) throws Exception {
        if (cond.containsKey("any")) {                          // OR 블록
            if (cond.get("any") instanceof List<?> list) {
                for (Object sub : list) {
                    if (sub instanceof Map<?, ?> m && one(m, ctx)) {
                        return true;
                    }
                }
            }
            return false;
        }
        if (cond.containsKey("gate")) {                         // 이름난 관문 — threshold_gate (any_entry)
            Object gate = cfg.get(String.valueOf(cond.get("gate")));
            return gate instanceof Map<?, ?> g && one(g, ctx);
        }
        if (cond.containsKey("event")) {
            return eventCondition(cond, ctx);
        }
        if (cond.containsKey("item")) {
            return items(ctx.sheet()).contains(String.valueOf(cond.get("item")));
        }
        if (cond.containsKey("item_any")) {
            if (cond.get("item_any") instanceof List<?> wanted) {
                List<String> mine = items(ctx.sheet());
                return wanted.stream().map(String::valueOf).anyMatch(mine::contains);
            }
            return false;
        }
        if (cond.containsKey("favor")) {
            if (!(cond.get("favor") instanceof Map<?, ?> f)) {
                return false;
            }
            int min = f.get("min") instanceof Number n ? n.intValue() : Integer.MAX_VALUE;
            String faction = String.valueOf(f.get("faction"));
            if ("any".equals(faction)) {                        // ★ 어느 세력군이든 — 정파만이 아니다
                for (FactionService.Standing s : ctx.factions().standings(ctx.chId(), ctx.today())) {
                    if (s.favor() >= min) {
                        return true;
                    }
                }
                return false;
            }
            return ctx.factions().favor(faction, ctx.chId(), ctx.today()) >= min;
        }
        if (cond.containsKey("realm_min")) {
            int mine = realmLadder.indexOf(ctx.realm());
            int floor = realmLadder.indexOf(String.valueOf(cond.get("realm_min")));
            return mine >= 0 && floor >= 0 && mine >= floor;
        }
        if (cond.containsKey("fortune")) {                      // 기연 획득 — 원장 유형 '기연', target = id
            return ctx.db().eventExists("기연", String.valueOf(ctx.chId()),
                    String.valueOf(cond.get("fortune")));
        }
        // 모르는 어휘는 거짓 — 등록부 오타가 조용한 완주가 되면 안 된다 (lint 가 짖을 자리)
        return false;
    }

    /**
     * event 조건 — 원장 누적. target 이 없으면 유형 계수(countEvents), 있으면 target 대조.
     *
     * <p>★ <b>대화 정규화</b>: 원장의 target 은 표시 이름(곽진)이고 조건은 키(gwakjin)다 —
     * 키·이름 <b>양쪽을 다 받는다</b> (원장 표기가 훗날 키로 바뀌어도 이 조건은 그대로 산다).
     * 판정 대화({@code 대화_판정})도 대화다 — 곽진에게 도적을 <b>물은</b> 것이 대화가 아니면 무엇인가.
     */
    private boolean eventCondition(Map<?, ?> cond, Ctx ctx) throws Exception {
        String type = String.valueOf(cond.get("event"));
        int need = cond.get("count") instanceof Number n ? n.intValue() : 1;
        List<String> types = "대화".equals(type) ? List.of("대화", "대화_판정") : List.of(type);
        String actorId = String.valueOf(ctx.chId());
        Object target = cond.get("target");
        if (target == null) {
            return ctx.db().countEvents("character", actorId, types) >= need;
        }
        String key = String.valueOf(target);
        java.util.Set<String> accepted = new java.util.LinkedHashSet<>(List.of(key));
        String display = npcNames.get(key);
        if (display != null) {
            accepted.add(display);
        }
        int seen = 0;
        for (String t : types) {
            for (Map<String, Object> e : ctx.db().eventsOf(t, actorId)) {
                if (accepted.contains(String.valueOf(e.get("target_id"))) && ++seen >= need) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 소지품 — 시트의 목록 (매화_목패 등 단서는 밑줄 표기 그대로 담긴다) */
    private static List<String> items(Map<String, Object> sheet) {
        if (sheet != null && sheet.get("소지품") instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> chains() {
        Object v = cfg.get("chains");
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    /** 없으면 빈 맵 — 던지지 않는다 (등록부의 빈 칸과 봇의 죽음은 같은 무게가 아니다) */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> root, String key) {
        Object v = root == null ? null : root.get(key);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    private static String text(Map<String, Object> root, String key, String fallback) {
        Object v = root == null ? null : root.get(key);
        return v == null ? fallback : String.valueOf(v).strip();
    }
}
