package com.honcheon.bot;

import com.honcheon.core.rules.RulesConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 서장(序章) 등록부 — <b>글과 선택지와 저항값이 사는 곳.</b> 정본은 {@code config/seojang.yml} 이다.
 *
 * <p><b>★ 이 클래스는 이야기를 짓지 않는다.</b> 전에는 지었다 — 장면 뼈대는
 * {@code GameListener.scenes()} 안에 {@code new Scene("그날 밤", 10, …)} 로 박혀 있었고, 산문은
 * {@code Narration.INCIDENT_OPENING} 같은 자바 {@code Map.of(...)} 안에 있었다. <b>코드가 이야기를
 * 지고 있었다.</b> 등록제 위반이다. 전부 등록부로 옮기고, 여기는 <b>읽는 손</b>만 남겼다.
 *
 * <p><b>★ 누가 그리는가 — 봇이다.</b> 서장은 이제 <b>마크의 책</b>으로 흐르지만, 문장을 그리는 것도
 * 주사위를 굴리는 것도 <b>여기(봇)</b>다. LLM({@link LlmRenderer})도, 판정 엔진도, 시트도 봇에만
 * 있기 때문이다 — 마크는 DB 를 열지 않는다. <b>마크는 서책이고 봇이 저자다.</b>
 * 마크가 문장을 지으면 정본이 둘이 된다 (이 저장소가 반복해서 데인 병).
 *
 * <p><b>진행 상태는 시트에 산다</b> — {@code characters.sheet_json} 의 네 칸.
 * DB 스키마를 건드리지 않았다 (시트는 원래 자유 서식이다 — 마이그레이션도 봇 정지도 필요 없다):
 * <ul>
 *   <li>{@link #SHEET_SCENE} {@code 서장_장면} — 0..3 (3 = 에필로그). 없으면 서장 전이다</li>
 *   <li>{@link #SHEET_TIER} {@code 서장_직전등급} — 이음새의 온도</li>
 *   <li>{@link #SHEET_BODY} {@code 서장_본문} — <b>이미 그려진 글</b> (LLM 출력 또는 폴백)</li>
 *   <li>{@link #SHEET_TOKEN} {@code 서장_토큰} — 이 장면의 지목. 낡은 책의 클릭은 여기서 죽는다</li>
 * </ul>
 *
 * <p><b>왜 본문을 시트에 적어 두는가.</b> 다리는 2초마다 {@code seojang.json} 을 찍는다. 그때마다
 * LLM 을 부르면 <b>2초마다 한 번씩 새 소설이 쓰인다</b> — 사람이 읽던 문장이 눈앞에서 바뀐다.
 * 그래서 글은 <b>장면이 넘어가는 순간 한 번만</b> 그리고, 그 결과를 시트에 못 박는다.
 * 다리는 <b>적힌 것을 옮길 뿐</b>이다.
 */
final class Seojang {

    static final String SHEET_SCENE = "서장_장면";
    static final String SHEET_TIER = "서장_직전등급";
    static final String SHEET_BODY = "서장_본문";
    static final String SHEET_TOKEN = "서장_토큰";
    /** ★ 미리 쓴 글의 지문 — 지금과 다르면 그 글은 낡았다 (seojang.yml prerender.fingerprint) */
    static final String SHEET_PRINT = "서장_본문_지문";
    /** 이 글이 폴백인가 — 책의 간기(刊記)로 남는다 (llm.yml runtime.fallback_visible_to_player) */
    static final String SHEET_FALLBACK = "서장_본문_폴백";
    /**
     * ★★ <b>탄생 순간의 형제</b> — 서장의 모든 장면이 이것만 읽는다 (산 것을 읽지 않는다).
     *
     * <p>형의 서장은 동생이 나기 전에 쓰였다 — 그때는 <b>정말로 혼자였다.</b> 그 글을 소급해서
     * 고치지 않는다. 대신 형에게 <b>소식</b>이 간다 ("네 아우가 났다").
     * <b>서장은 과거고, 시트는 현재다.</b>
     */
    static final String SHEET_KIN = "서장_형제";

    /** 갈래 — 무가의 자식만 다른 서장을 산다 (재난이 아니라 명령이 발단이다) */
    static final String BRANCH_DISPATCH = "수행_파견";
    static final String BRANCH_DEFAULT = "기본";

    /** 한 장면 — 등록부의 뼈대 (판정 수치의 정본) */
    record Scene(String title, int resist, List<Choice> choices) {
    }

    record Choice(String label, String stat, int bonus) {
    }

    private final Map<String, List<Scene>> scenes = new LinkedHashMap<>();
    private final Map<String, List<String>> sceneBody = new LinkedHashMap<>();
    private final Map<String, String> incidentOpening = new LinkedHashMap<>();
    private final Map<String, String> familyColor = new LinkedHashMap<>();
    private final Map<String, String> bridgeLine = new LinkedHashMap<>();
    /** ★ 적서의 색 — 적자의 유년과 서자의 유년은 같은 글일 수 없다 (seojang.yml prose.rank_color) */
    private final Map<String, String> rankColor = new LinkedHashMap<>();
    /** ★ 가문의 형태 — 흥한 집과 기우는 집의 유년은 같지 않다 (탄생에 고정되므로 안전하다) */
    private final Map<String, String> houseStateColor = new LinkedHashMap<>();
    /** ★ 고을의 색 — 사천의 아이와 강남의 아이는 같은 유년을 살지 않았다 */
    private final Map<String, String> regionColor = new LinkedHashMap<>();
    /** ★ 형제의 색 — **탄생 순간의 형제만** 든다 (형의 서장에는 없다. 그때는 정말 혼자였다) */
    private final Map<String, String> kinColor = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> epilogueLanding = new LinkedHashMap<>();
    private final Map<String, String> epilogueClosing = new LinkedHashMap<>();
    private final Map<String, String> book = new LinkedHashMap<>();
    private final Map<String, String> signpost = new LinkedHashMap<>();
    private final Map<String, String> ferry = new LinkedHashMap<>();
    private final String debutLine;
    private final boolean prerender;

    @SuppressWarnings("unchecked")
    Seojang(Path configDir) {
        Map<String, Object> cfg = RulesConfig.load(configDir.resolve("seojang.yml"));

        Map<String, Object> sceneCfg = RulesConfig.section(cfg, "scenes");
        sceneCfg.forEach((branch, raw) -> {
            List<Scene> list = new ArrayList<>();
            for (Object o : asList(raw)) {
                Map<String, Object> s = (Map<String, Object>) o;
                List<Choice> choices = new ArrayList<>();
                for (Object c : asList(s.get("choices"))) {
                    Map<String, Object> ch = (Map<String, Object>) c;
                    choices.add(new Choice(str(ch.get("label")), str(ch.get("stat")),
                            num(ch.get("bonus"), 0)));
                }
                list.add(new Scene(str(s.get("title")), num(s.get("resist"), 10),
                        List.copyOf(choices)));
            }
            scenes.put(branch, List.copyOf(list));
        });

        Map<String, Object> prose = RulesConfig.section(cfg, "prose");
        RulesConfig.section(prose, "incident_opening").forEach((k, v) -> incidentOpening.put(k, str(v)));
        RulesConfig.section(prose, "family_color").forEach((k, v) -> familyColor.put(k, str(v)));
        RulesConfig.section(prose, "bridge").forEach((k, v) -> bridgeLine.put(k, str(v)));
        RulesConfig.section(prose, "rank_color").forEach((k, v) -> rankColor.put(k, str(v)));
        RulesConfig.section(prose, "house_state_color")
                .forEach((k, v) -> houseStateColor.put(k, str(v)));
        RulesConfig.section(prose, "region_color").forEach((k, v) -> regionColor.put(k, str(v)));
        RulesConfig.section(prose, "kin_color").forEach((k, v) -> kinColor.put(k, str(v)));
        RulesConfig.section(prose, "scene_body").forEach((branch, raw) -> {
            List<String> list = new ArrayList<>();
            asList(raw).forEach(v -> list.add(str(v)));
            sceneBody.put(branch, List.copyOf(list));
        });
        RulesConfig.section(prose, "epilogue").forEach((branch, raw) -> {
            Map<String, Object> e = (Map<String, Object>) raw;
            Map<String, String> landing = new LinkedHashMap<>();
            if (e.get("landing") instanceof Map<?, ?> m) {
                m.forEach((k, v) -> landing.put(str(k), str(v)));
            }
            epilogueLanding.put(branch, Map.copyOf(landing));
            epilogueClosing.put(branch, str(e.get("closing")));
        });
        this.debutLine = str(prose.get("debut"));

        RulesConfig.section(cfg, "book").forEach((k, v) -> book.put(k, str(v)));
        RulesConfig.section(cfg, "signpost").forEach((k, v) -> signpost.put(k, str(v)));
        RulesConfig.section(cfg, "ferry").forEach((k, v) -> ferry.put(k, str(v)));
        Object pre = RulesConfig.section(cfg, "prerender").get("enabled");
        this.prerender = !(pre instanceof Boolean b) || b;
    }

    /** 미리 쓰기를 하는가 (seojang.yml prerender.enabled) */
    boolean prerender() {
        return prerender;
    }

    /**
     * ★ 미리 쓴 글의 <b>지문</b> — {@code 캐릭터id + 장면 + 직전등급} (seojang.yml prerender.fingerprint).
     *
     * <p>책을 내려보낼 때 이 지문이 <b>지금</b>과 다르면 그 글은 <b>낡았다</b> — 버리고 다시 쓴다.
     * 이 셋이 서사가 의존하는 전부다: 캐릭터가 바뀌면 남의 이야기고, 장면이 다르면 다른 글이고,
     * <b>직전등급이 다르면 이음새({bridge})가 다르다</b> — 같은 장면도 앞 판정에 따라 달라진다.
     */
    static String fingerprint(long charId, int scene, String prevTier) {
        return charId + ":" + scene + ":" + (prevTier == null ? "-" : prevTier);
    }

    /** 나루의 사공 — 줄을 세계의 말로 (침묵 금지) */
    String ferry(String key, String fallback) {
        String v = ferry.get(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    // ─── 뼈대 ───

    /** 이 발단이 사는 갈래 — 무가의 자식(수행_파견)이냐, 재난을 겪은 아이냐 */
    static String branchOf(String incident) {
        return BRANCH_DISPATCH.equals(incident) ? BRANCH_DISPATCH : BRANCH_DEFAULT;
    }

    List<Scene> scenesOf(String incident) {
        return scenes.getOrDefault(branchOf(incident), List.of());
    }

    /** 장면 수 (에필로그는 세지 않는다 — 에필로그의 인덱스가 곧 이 값이다) */
    int sceneCount(String incident) {
        return scenesOf(incident).size();
    }

    // ─── 글 — LLM 에게 줄 기준 서사 (LLM 이 없거나 실패하면 이 글이 그대로 책이 된다) ───

    /**
     * 장면 본문. {@code prevTier} 가 null 이면 첫 장면이다 (이음새 없음).
     *
     * <p>치환은 등록부가 정한 자리에만 든다 — {@code {opening}} · {@code {family_color}} ·
     * {@code {bridge}} · {@code {name}}. <b>코드는 문장을 잇지 않는다.</b>
     */
    String sceneBody(GameListener.Character ch, int idx, String prevTier, String rank) {
        return sceneBody(ch, idx, prevTier, rank, null, null, List.of());
    }

    /** ★ 가문(형태·고을)까지 아는 판 — 흥한 집과 기우는 집, 청하의 아이와 강남의 아이는 다르다 */
    String sceneBody(GameListener.Character ch, int idx, String prevTier, String rank,
                     String houseState, String region, List<String> kinAtBirth) {
        List<String> bodies = sceneBody.getOrDefault(branchOf(ch.incident()), List.of());
        if (idx < 0 || idx >= bodies.size()) {
            return "";
        }
        // ★ 적서 — 적서가 없는 집이면 빈 문자열이 든다 (코드가 지어내지 않는다).
        //   앞뒤의 빈 줄이 남지 않도록 strip 한다 (빈 색 + \n\n = 책의 첫 장이 빈 줄로 시작한다)
        String rc = rank == null ? "" : rankColor.getOrDefault(rank,
                rankColor.getOrDefault("default", ""));
        return bodies.get(idx)
                .replace("{opening}", incidentOpening.getOrDefault(ch.incident(),
                        incidentOpening.getOrDefault("default", "")))
                .replace("{family_color}", familyColor.getOrDefault(ch.family(),
                        familyColor.getOrDefault("default", "")))
                .replace("{rank_color}", rc)
                .replace("{house_state_color}", houseState == null ? ""
                        : houseStateColor.getOrDefault(houseState,
                        houseStateColor.getOrDefault("default", "")))
                .replace("{region_color}", region == null ? ""
                        : regionColor.getOrDefault(region, regionColor.getOrDefault("default", "")))
                // ★★ **탄생 순간의** 형제 (산 것이 아니다). 없으면 빈 줄 — 없는 것을 말하지 않는다
                .replace("{kin_color}", kinAtBirth == null || kinAtBirth.isEmpty()
                        ? kinColor.getOrDefault("없음", "")
                        : kinColor.getOrDefault("있음", "")
                        .replace("{kin}", String.join(" · ", kinAtBirth)))
                .replace("{bridge}", prevTier == null ? "" : bridgeLine.getOrDefault(grade(prevTier), ""))
                .replace("{name}", ch.name())
                .strip();
    }

    /** 에필로그 — 마지막 판정의 등급이 첫 정착의 온도를 정한다. 뒤에 출도의 한 줄이 붙는다 */
    String epilogue(GameListener.Character ch, String lastTier) {
        return epilogue(ch, lastTier, null);
    }

    /** ★ 고을을 아는 판 — 「청하현의 아침」이 강남에서는 「강남의 아침」이다 */
    String epilogue(GameListener.Character ch, String lastTier, String regionName) {
        String branch = branchOf(ch.incident());
        String landing = epilogueLanding.getOrDefault(branch, Map.of())
                .getOrDefault(grade(lastTier), "");
        String closing = epilogueClosing.getOrDefault(branch, "")
                .replace("{name}", ch.name())
                .replace("{incident}", ch.incident().replace('_', ' '));
        String out = landing + closing + "\n\n" + debutLine.replace("{name}", ch.name());
        // ★ 고을 이름은 **등록부가 채운다** — 코드도 글도 「청하현」을 박아 두지 않는다
        //   (강남 상로가 서면 그 아이의 서장은 「강남의 아침」이라고 말해야 한다)
        return out.replace("{region}", regionName == null ? "" : regionName).strip();
    }

    /** 장면의 제목 — 고을 이름이 든다 (「낯선 고을 청하현」 / 「낯선 고을 강남 상로」) */
    String title(String incident, int idx, String regionName) {
        List<Scene> list = scenesOf(incident);
        if (idx < 0 || idx >= list.size()) {
            return "";
        }
        return list.get(idx).title()
                .replace("{region}", regionName == null ? "" : regionName).strip();
    }

    /**
     * judgment.yml result_tiers 의 이름 → 등록부의 5분류 키.
     *
     * <p><b>여기가 유일한 환산점이다.</b> 전에는 {@code Narration.grade} 가 자바 {@code enum} 으로
     * 같은 일을 했고, 등록부의 키와 코드의 이름이 <b>따로</b> 살았다.
     */
    static String grade(String tierName) {
        if (tierName == null) {
            return "중간";
        }
        return switch (tierName) {
            case "대성공" -> "대성공";
            case "성공" -> "성공";
            case "아슬아슬한 성공", "부분 성공" -> "중간";
            case "실패" -> "실패";
            default -> "대실패";
        };
    }

    // ─── 책과 이정표의 말 (코드가 짓지 않는다 — 없으면 fallback) ───

    String book(String key, String fallback) {
        String v = book.get(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    String signpost(String key, String fallback) {
        String v = signpost.get(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static List<?> asList(Object raw) {
        return raw instanceof List<?> l ? l : List.of();
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static int num(Object v, int fallback) {
        return v instanceof Number n ? n.intValue() : fallback;
    }
}
