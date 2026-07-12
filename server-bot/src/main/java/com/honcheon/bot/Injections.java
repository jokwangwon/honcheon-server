package com.honcheon.bot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 동적 의뢰 주입 — 게시판은 세계 상태의 거울이다.
 *
 * A2 (faction_entry_routes.quest_injection): 루트 상태(정파 favor · 눈여겨봄 태그)를 보고
 *     세계가 문을 내민다 — 진운 경유·지명 의뢰. ★ A1(출행)의 출력이 곧 이 조각의 입력이다.
 * B3 (npc_death.quest_injection): 누가 죽었느냐에 따라 게시판이 바뀐다 —
 *     "유문이 죽은 다음 날 '외지 의원 호송'이 뜬다"가 움직이는 세계의 증거다.
 *
 * 결정론: 입력이 (세계일 · 사망 등록부 · 플레이어 상태) 뿐이다. 주사위 없음 — 같은 날 같은 게시판.
 * 등급·보수·발주자는 config 에서 읽고, 산문만 여기에 있다 (정적 POOL 과 같은 관행).
 */
final class Injections {

    static final String HWASAN = "hwasan_entry";
    /** 사망 파생 의뢰가 게시판에 머무는 창 — 사망 다음 날부터 (죽음은 즉시 게시판을 바꾼다) */
    private static final int DEATH_WINDOW_DAYS = 14;

    private Injections() {
    }

    /** 등급 → 저항값: judgment.yml static_difficulty (신규 난이도 표 없음) */
    private static int resistOf(Rules rules, String grade) {
        return switch (grade) {
            case "잔심부름" -> rules.difficulty("쉬움");
            case "호위_소탕", "표행_현상금" -> rules.difficulty("어려움");
            default -> rules.difficulty("보통");
        };
    }

    private static String realmOf(String grade) {
        return switch (grade) {   // quest_generation.yml grade_ladder rungs
            case "잔심부름" -> "범인";
            case "조사_채집" -> "삼류";
            case "호위_소탕" -> "이류";
            case "표행_현상금" -> "일류";
            default -> "절정";
        };
    }

    // ─── A2 — 루트 상태 기반 주입 (진운 지명 의뢰) ───

    /**
     * 정파 favor · 상태 태그를 보고 화산 루트의 quest_injection 규칙을 평가한다.
     * favor >= 4 → 진운 경유 의뢰 / '눈여겨봄' 태그 → 진운 지명 의뢰 / favor >= 8 → 문파 전용 의뢰.
     */
    static List<Quests.Quest> routeQuests(Rules rules, int orthodoxFavor, Set<String> tags) {
        List<Quests.Quest> out = new ArrayList<>();
        String jinun = rules.npcName("jinun");
        for (Routes.Injection inj : rules.routes.questInjections(HWASAN)) {
            boolean match = (inj.favorMin() != null && orthodoxFavor >= inj.favorMin())
                    || (inj.tag() != null && tags.contains(inj.tag()));
            if (!match) {
                continue;
            }
            switch (inj.inject()) {
                case "진운_경유_의뢰" -> out.add(new Quests.Quest("진운_경유_의뢰",
                        "정파 경유: 산길 향화객 안내", "조사_채집", realmOf("조사_채집"),
                        resistOf(rules, "조사_채집"), "조사", false,
                        "소연이 봉함된 서찰을 내민다. \"" + jinun + " 도장(道長)이 맡긴 일일세 — 정파 연락망을 "
                                + "타고 온 걸세. 자네 이름이 적혀 있진 않네만, 자네에게 주라더군.\"",
                        List.of(new Quests.Approach("서찰의 노정을 그대로 밟는다", "지혜", 2),
                                new Quests.Approach("길목의 향화객에게 먼저 말을 붙인다", "화술", 2)), "jinun"));
                case "진운_지명_의뢰" -> out.add(new Quests.Quest("진운_지명_의뢰",
                        "지명: " + jinun + "이 자네를 찾는다", "조사_채집", realmOf("조사_채집"),
                        resistOf(rules, "조사_채집"), "조사", false,
                        "게시판 한 귀퉁이에 이름이 적힌 쪽지가 붙어 있다. \"자네, 요전에 산문 앞에서 봤네만 — "
                                + "화산 아래 마을에 도적 흔적이 있다더군. 눈이 밝다니 좀 봐 주게.\" ("
                                + jinun + " 지명)",
                        List.of(new Quests.Approach("마을 어귀의 흔적부터 읽는다", "감각", 2),
                                new Quests.Approach("촌로들에게 며칠 새의 일을 묻는다", "화술", 2)), "jinun"));
                case "문파_전용_의뢰" -> out.add(new Quests.Quest("문파_전용_의뢰",
                        "화산 경유: 산문 아래 소탕", "호위_소탕", realmOf("호위_소탕"),
                        resistOf(rules, "호위_소탕"), "단거리_호위", true,
                        "정파 연락망을 통해서만 도는 일이다. \"" + jinun + "의 이름이 걸렸네 — "
                                + "일을 그르치면 그 사람이 문책을 받는다는 뜻일세.\"",
                        List.of(new Quests.Approach("길목을 막고 정면으로 친다", "근력", 2),
                                new Quests.Approach("퇴로를 먼저 끊는다", "지혜", 2)), "jinun"));
                default -> { }   // 알파 미배선 후보 — 조용히 넘긴다 (config 가 앞서 간다)
            }
        }
        return out;
    }

    // ─── 단계 5 — 정치 주입 (강호의 판이 게시판에 비친다) ───

    /**
     * 명분·연합이 게시판에 나타나는 방식 — 플레이어는 게이지를 보지 않는다. **의뢰를 본다.**
     *
     *   명분 8~14  "문파의 사자(使者)가 오간다. 의뢰소에 '조사' 의뢰가 붙는다" (observable)
     *   연합 성립  "토벌령이 내렸다" — 연합의 토벌대에 손이 모자란다 (참여 3+)
     *
     * ★ 연합 전쟁의 실제 전투(관군 vs 무림)는 봇의 몫이 아니다 (MVT 또는 후속).
     *   봇에서 그것은 **상태·소문·의뢰**로만 나타난다 — 게시판이 전선의 유일한 창이다.
     * 등급·보수는 config (quest_generation grade_ladder · economy 의뢰_보수), 산문만 여기.
     */
    static List<Quests.Quest> politicsQuests(Rules rules, List<GameListener.Issue> issues) {
        List<Quests.Quest> out = new ArrayList<>();
        int inquiryFloor = rules.politics.observableFloor("명분_8_14");
        for (GameListener.Issue issue : issues) {
            String target = issue.row().target();
            String targetName = rules.factionName(target);
            Politics.Coalition c = issue.coalition();

            if (issue.gauge() >= inquiryFloor) {
                out.add(quest(rules, "명분_조사_" + target, "조사: " + targetName + "의 소행이라는데",
                        "조사_채집", "조사", false,
                        "게시판에 낯선 인장이 찍힌 쪽지가 붙었다 — 문파의 사자(使者)가 다녀갔다. "
                                + "\"소문이 사실인지부터 알아야 하네. 확인되지 않은 말로 사람이 죽는 걸 "
                                + "너무 많이 봤어.\"\n*(정확도가 곧 명분의 힘이다 — 증거가 명분을 두 배로 만든다)*",
                        List.of(new Quests.Approach("현장의 흔적부터 읽는다", "감각", 2),
                                new Quests.Approach("목격자를 찾아 말을 맞춰 본다", "화술", 2)), "soyeon"));
            }
            if (rules.politics.formed(c.count())) {
                String leader = rules.politics.displayName(c.leader());
                out.add(quest(rules, "토벌령_" + target, "토벌령: " + targetName + " — " + c.size(),
                        "호위_소탕", "도적_소탕_현상", true,
                        "**토벌령이 내렸다.** " + leader + "의 이름으로 무림첩이 돌았다 — "
                                + c.participants().stream().map(rules.politics::displayName)
                                        .collect(java.util.stream.Collectors.joining("·"))
                                + "이(가) 뭉쳤다.\n\"손이 모자라네. 문파 사람이 아니어도 좋아 — "
                                + "짐을 나르고, 길을 트고, 뒤를 봐 줄 사람이 필요하네.\"\n"
                                + "*(연합에 기여하면 참여 세력 **전체**에 우호가 오른다 — 연합이 곧 인맥이다)*",
                        List.of(new Quests.Approach("토벌대의 길잡이로 앞선다", "감각", 2),
                                new Quests.Approach("보급과 부상자를 맡는다", "체력", 2)), "soyeon"));
            }
        }
        return out;
    }

    // ─── B3 — 사망 파생 주입 (누가 죽었느냐가 게시판을 바꾼다) ───

    /**
     * 사망 등록부 → 게시판 후보. NPC 1인당 최대 2건 (npc_types.quest_count 상한).
     * 유형은 config 가 정한다: cause_modifier.<사인>.quests + 시신 은닉(실종_수색) + 서비스 공백(공백_메우기).
     */
    static List<Quests.Quest> deathQuests(Rules rules, Map<String, Map<String, Object>> dead, int today) {
        List<Quests.Quest> out = new ArrayList<>();
        Set<String> deadKeys = dead.keySet();
        for (Map.Entry<String, Map<String, Object>> e : dead.entrySet()) {
            String npcKey = e.getKey();
            Map<String, Object> state = e.getValue();
            int deathDay = ((Number) state.getOrDefault("사망일", 0)).intValue();
            int elapsed = today - deathDay;
            if (elapsed < 1 || elapsed > DEATH_WINDOW_DAYS) {
                continue;   // 죽은 다음 날 뜨고, 보름이면 세계는 다음 일로 넘어간다
            }
            String cause = String.valueOf(state.getOrDefault("사인", "사건_피살"));
            String body = String.valueOf(state.getOrDefault("시신", "즉시_발견"));
            int witness = ((Number) state.getOrDefault("목격", 1)).intValue();
            boolean covert = witness == 0 && "은닉".equals(body);   // 아무도 그가 죽은 줄 모른다

            List<String> types = new ArrayList<>();
            if (covert) {
                // 완전 은밀 — 세계는 '죽음'을 모른다. 발각은 느릴 뿐이다 (missing_person)
                if (elapsed < rules.deaths.missingPersonDays()) {
                    continue;   // 아직 아무도 그가 없어진 줄 모른다 — 게시판은 조용하다
                }
                types.add("실종_수색");
            } else {
                types.addAll(rules.deaths.questTypesByCause(cause));
                if ("은닉".equals(body)) {
                    types.remove("시신_수습");   // 수습할 시신이 없다 — 수색이 대신한다
                    if (elapsed >= rules.deaths.missingPersonDays()) {
                        types.add(0, "실종_수색");
                    }
                }
            }
            // 서비스 공백 — 문이 닫혔거나(공백·폐업) 대행이 이어지는 동안 외지인을 데려와야 한다
            // (quest_injection.공백_메우기.trigger: "폐업 또는 대행 지속") — 후계자가 이으면 뜨지 않는다
            Deaths.Gap gap = rules.deaths.serviceState(npcKey, elapsed, deadKeys);
            boolean vacancy = rules.deaths.facilityOf(npcKey) != null
                    && !gap.normal() && !"후계_초기".equals(gap.state());
            if (vacancy) {
                types.add(Math.min(1, types.size()), "공백_메우기");   // 가장 즉물적인 대가 — 앞자리에
            }
            int taken = 0;
            for (String type : new LinkedHashSet<>(types)) {
                if (taken >= 2) {   // npc_types.quest_count 상한 — NPC 1인당 최대 2건
                    break;
                }
                Quests.Quest q = deathQuest(rules, type, npcKey, covert);
                if (q != null && out.stream().noneMatch(x -> x.key().equals(q.key()))) {
                    out.add(q);
                    taken++;
                }
            }
        }
        return out;
    }

    private static Quests.Quest deathQuest(Rules rules, String type, String npcKey, boolean covert) {
        String name = rules.npcName(npcKey);
        String role = rules.npcRole(npcKey);
        String key = type + "_" + npcKey;
        String issuer = issuerNpc(rules, type);
        return switch (type) {
            case "실종_수색" -> quest(rules, key, "수색: " + name + "이 보이지 않는다",
                    rules.deaths.questGrade(type, "조사_채집"), rules.deaths.questReward(type, "조사"), false,
                    name + "(" + role + ")이 며칠째 자리를 비웠다. 문은 잠겨 있고, 아무도 그를 본 사람이 없다. "
                            + "\"찾아 주게 — 살아 있든, 아니든.\"",
                    List.of(new Quests.Approach("마지막으로 본 자를 캔다", "화술", 2),
                            new Quests.Approach("인적 드문 물가와 산기슭을 뒤진다", "감각", 2)), issuer);
            case "시신_수습" -> quest(rules, key, "수습: " + name + "의 시신",
                    rules.deaths.questGrade(type, "잔심부름"), rules.deaths.questReward(type, "잔심부름"), false,
                    name + "이 죽었다. 시신이 아직 길가에 있다 — 험한 곳이라 아무도 선뜻 나서지 않는다. "
                            + "\"사람 대접은 해서 보내야지.\"",
                    List.of(new Quests.Approach("수레를 빌려 조심히 옮긴다", "체력", 2),
                            new Quests.Approach("현장을 흐트러뜨리지 않고 수습한다", "지혜", 2)), issuer);
            case "하수인_추적" -> quest(rules, key, "추적: " + name + "을 벤 자",
                    rules.deaths.questGrade(type, "호위_소탕"),
                    rules.deaths.questReward(type, "도적_소탕_현상"), true,
                    "포두 " + rules.npcName("bakho") + "가 방을 붙였다. \"" + name + "을 죽인 손을 찾는다. "
                            + "칼자국이 말을 하겠지 — 발이 빠른 자를 구한다.\"",
                    List.of(new Quests.Approach("칼자국과 발자국을 읽는다", "감각", 2),
                            new Quests.Approach("저잣거리의 입을 산다", "화술", 2)), issuer);
            case "애도_장례" -> quest(rules, key, "장례: " + name + "의 상(喪)",
                    rules.deaths.questGrade(type, "잔심부름"), rules.deaths.questReward(type, "잔심부름"), false,
                    "성외 사당의 " + rules.npcName("hyegak") + "이 상(喪)을 치른다. 상여를 멜 손이 모자란다 — "
                            + "가장 싼 의뢰이자, 유족을 만나는 자리다.",
                    List.of(new Quests.Approach("상여를 멘다", "체력", 2),
                            new Quests.Approach("문상객을 맞고 부의를 적는다", "지혜", 2)), issuer);
            case "공백_메우기" -> quest(rules, key, "호송: 외지 " + outsiderRole(role) + " 부임",
                    rules.deaths.questGrade(type, "잔심부름"), rules.deaths.questReward(type, "잔심부름"), false,
                    (covert ? name + "이 사라진 뒤로 그 문은 닫혀 있다."   // 세계는 죽음을 모른다 — 부재만 안다
                            : name + "이 죽은 뒤로 그 문은 닫혀 있다.")
                            + " 현령 " + rules.npcName("jomunwon")
                            + "이 외지에서 사람을 불렀다 — 길이 험하니 데려올 손이 필요하다. "
                            + "(완수하면 부임이 앞당겨진다)",
                    List.of(new Quests.Approach("관도를 따라 마중 나간다", "체력", 2),
                            new Quests.Approach("표국에 동행을 붙여 안전히 데려온다", "화술", 2)), issuer);
            default -> null;   // 유산_분쟁_중재·후계_옹립·복수_청부 — 후속 (config 가 앞서 간다)
        };
    }

    /** issuer 가 등록 NPC 키면 그대로, '유족'·'관' 같은 비인물이면 null (발주자 사망 필터의 대상 아님) */
    private static String issuerNpc(Rules rules, String type) {
        String issuer = rules.deaths.questIssuer(type);
        return issuer != null && rules.npcByKey(issuer) != null ? issuer : null;
    }

    /** "청하현 의원" → "의원" · "청하객잔 주인" → "객잔 주인" (외지인 호송 의뢰 제목 — 등록부 role 에서) */
    private static String outsiderRole(String role) {
        String head = role.split("[(—/]")[0].strip();
        return head.replaceFirst("^청하현\\s*", "").replaceFirst("^청하", "").strip();
    }

    private static Quests.Quest quest(Rules rules, String key, String name, String grade, String rewardKey,
                                      boolean combatMark, String brief, List<Quests.Approach> approaches,
                                      String issuer) {
        return new Quests.Quest(key, name, grade, realmOf(grade), resistOf(rules, grade),
                rewardKey, combatMark, brief, approaches, issuer);
    }
}
