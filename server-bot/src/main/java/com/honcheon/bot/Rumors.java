package com.honcheon.bot;

import com.honcheon.core.rules.RulesConfig;
import com.honcheon.core.rules.RumorEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 소문망 — config/rumor.yml 의 봇 쪽 손잡이 (단계 4 B).
 *
 * 핵심 전환: 소문은 이제 '한 행'이 아니라 **망별 도달의 묶음**이다.
 *   생성 시 RumorEngine.create() 가 도달 스케줄을 계산한다 (망별 speed_days · distortion),
 *   그 도달 하나하나가 rumors 테이블의 한 행이 된다 —
 *   같은 소문이라도 망마다 born_day(도달일)와 accuracy(정확도)가 다르다.
 *   → 먼 곳에는 늦게, 그리고 다른 이야기가 되어 도착한다 (전언 게임).
 *
 * 결정론: 전파·감쇠·왜곡에 주사위가 없다. 입력은 (발원일·강도·정확도·태그)뿐 —
 *   같은 세계일이면 같은 소문판이다. 감쇠는 배치가 아니라 **읽는 순간 계산**한다
 *   (강도 = 발원강도 − (오늘 − 도달일) / decay.every_days).
 *
 * 수치는 전부 rumor.yml. 여기 있는 것은 산문(왜곡 문형)뿐이다 — Narration 과 같은 관행.
 */
final class Rumors {

    /** 소문 한 조각이 특정 망에 도달한 모습 — 플레이어가 실제로 듣는 단위 */
    record Heard(long id, String group, String truth, String subject, String network,
                 int intensity, int accuracy, int bornDay, List<String> tags) {
    }

    private final Map<String, Object> cfg;
    private final RumorEngine engine;

    Rumors(Map<String, Object> cfg) {
        this.cfg = cfg;
        this.engine = new RumorEngine(cfg);
    }

    RumorEngine engine() {
        return engine;
    }

    // ─── 등록부 조회 ───

    @SuppressWarnings("unchecked")
    private Map<String, Object> propagation() {
        return RulesConfig.section(cfg, "propagation");
    }

    /** 소문망 6종의 키 (mingan_market · inn_net · sangdan_net · haomun_net · orthodox_net · gwan_net) */
    Set<String> networks() {
        return RulesConfig.section(cfg, "networks").keySet();
    }

    String networkName(String net) {
        Object entry = RulesConfig.section(cfg, "networks").get(net);
        return entry instanceof Map<?, ?> m ? String.valueOf(m.get("name")) : net;
    }

    /** 장소 → 발원 소문망 (propagation.origin_network_by_location). 미등록 장소는 민간/장터 */
    @SuppressWarnings("unchecked")
    String originNetwork(String location) {
        Map<String, Object> byLoc = (Map<String, Object>) propagation().get("origin_network_by_location");
        Object net = byLoc.get(location);
        return net == null ? "mingan_market" : String.valueOf(net);
    }

    /** generation.intensity_by_visibility — 노출 수준이 초기 강도를 정한다 (공개_다수_목격 3 …) */
    @SuppressWarnings("unchecked")
    int intensityByVisibility(String visibility) {
        Map<String, Object> gen = RulesConfig.section(cfg, "generation");
        Map<String, Object> table = (Map<String, Object>) gen.get("intensity_by_visibility");
        return RulesConfig.intValue(table.get(visibility));
    }

    /** generation.initial_accuracy — 직접_목격 90 · 간접_전문 70 · 흔적_추론 50 */
    @SuppressWarnings("unchecked")
    int initialAccuracy(String kind) {
        Map<String, Object> gen = RulesConfig.section(cfg, "generation");
        Map<String, Object> table = (Map<String, Object>) gen.get("initial_accuracy");
        return RulesConfig.intValue(table.get(kind));
    }

    /** propagation.decay.every_days — 이 일수마다 강도 -1 (0이면 소멸) */
    @SuppressWarnings("unchecked")
    int decayEveryDays() {
        Map<String, Object> decay = (Map<String, Object>) propagation().get("decay");
        return RulesConfig.intValue(decay.get("every_days"));
    }

    /**
     * faction_awareness.network_access — 세력의 '조직 채널'.
     * 조직원 개인이 현장에서 들은 것은 기억일 뿐이고, 이 채널을 거쳐야 세력 반응 점수가 된다
     * (망별 speed_days 가 곧 '세력이 조직적으로 반응하기까지의 시차'다).
     */
    @SuppressWarnings("unchecked")
    Map<String, List<String>> factionNetworks() {
        Map<String, Object> awareness = RulesConfig.section(cfg, "faction_awareness");
        Map<String, Object> access = (Map<String, Object>) awareness.get("network_access");
        Map<String, List<String>> out = new LinkedHashMap<>();
        access.forEach((faction, nets) -> out.put(faction,
                ((List<Object>) nets).stream().map(String::valueOf).toList()));
        return out;
    }

    /** 이 망을 조직 채널로 쓰는 세력들 (한 망을 여럿이 공유한다 — 녹림은 하오문 망에 얹혀 있다) */
    List<String> factionsListening(String network) {
        List<String> out = new ArrayList<>();
        factionNetworks().forEach((faction, nets) -> {
            if (nets.contains(network)) {
                out.add(faction);
            }
        });
        return out;
    }

    /** accuracy_bands — 사실적(80+) / 과장(50+) / 오해(30+) / 괴담(0+) */
    String band(int accuracy) {
        return engine.accuracyBand(accuracy);
    }

    /**
     * myeongbun_link.intensity_on_coalition — 연합이 성립하면 그 자체가 소문이 된다.
     * 연합_성립 = 강도 4 (지역권) · 무림공적_선포 = 강도 5 (천하).
     * ★ 정치는 수치로 보이지 않는다. 플레이어는 이 소문으로만 그것을 안다.
     */
    @SuppressWarnings("unchecked")
    int coalitionIntensity(String key) {
        Map<String, Object> link = RulesConfig.section(cfg, "myeongbun_link");
        Map<String, Object> table = (Map<String, Object>) link.get("intensity_on_coalition");
        Object v = table.get(key);
        return v instanceof Number n ? n.intValue() : 3;
    }

    /** 이 세력이 조직 채널로 쓰는 망들 (faction_awareness.network_access — 없으면 빈 목록) */
    List<String> networksOf(String faction) {
        return factionNetworks().getOrDefault(faction, List.of());
    }

    /**
     * 정치층의 채널 해석 — 개별 키가 없는 문파는 **계열 연락망**으로 듣는다
     * (faction_awareness.coalition_channel: 곤륜·종남·팽가… → 정파망 / 사파 → 하오문 망).
     * '못 듣는다'가 아니라 '늦게, 더 뒤틀려 듣는다' — 그것이 원거리 세력의 참전 시차다.
     */
    @SuppressWarnings("unchecked")
    List<String> politicalNetworksOf(String faction, String coalition) {
        List<String> own = networksOf(faction);
        if (!own.isEmpty()) {
            return own;
        }
        Map<String, Object> awareness = RulesConfig.section(cfg, "faction_awareness");
        Object fallback = ((Map<String, Object>) awareness.get("coalition_channel")).get(coalition);
        return fallback instanceof List<?> list ? list.stream().map(String::valueOf).toList()
                : List.of();
    }

    // ─── 전파 — 도달 스케줄 계산 (persist 는 Db.spreadRumor) ───

    /**
     * 발원망 + 관심 일치 망들의 도달 스케줄.
     * 강도 1 이하는 발원망 하나뿐 (propagation.reach_by_intensity: "발생 장소의 기본 망 1개"),
     * 강도 2 이상이면 관심(interests)이 겹치는 망으로 건너간다 — speed_days 만큼 늦게,
     * distortion 만큼 부정확하게 (cross_network.accuracy_rule).
     */
    Map<String, RumorEngine.Arrival> arrivals(String group, int day, String originNet,
                                              int intensity, int accuracy, Set<String> tags) {
        RumorEngine.Rumor rumor = engine.create(group, day, originNet, intensity, accuracy,
                new LinkedHashSet<>(tags), null);
        return rumor.arrivals();
    }

    // ─── 왜곡 — 같은 소문이 어떻게 들리는가 (전언 게임) ───

    /**
     * 정확도 구간(accuracy_bands)에 따라 같은 사실이 다른 이야기가 된다.
     * 수치(구간 문턱·style)는 rumor.yml, 문형만 여기 — 결정론(주사위 없음).
     *   사실적  사건이 거의 그대로 전달
     *   과장    규모·실력·잔혹함이 부풀려짐
     *   오해    범인·동기·대상이 뒤바뀜 (주체가 '이름 모를 자'로 미끄러진다)
     *   괴담    원형을 알아볼 수 없는 흉흉한 이야기
     */
    String tell(Heard heard) {
        String truth = heard.truth();
        String who = heard.subject() == null || heard.subject().isBlank() ? "누군가" : heard.subject();
        return switch (band(heard.accuracy())) {
            case "사실적" -> truth;
            case "과장" -> truth + " …그런데 들을수록 이야기가 커지네. "
                    + exaggeration(heard.tags()) + " 뭐, 반은 걸러 들어야지.";
            case "오해" -> misheard(truth, who, heard.tags());
            default -> ghostStory(heard.tags());
        };
    }

    /** 과장 — 규모·실력·잔혹함이 부풀려진다 (태그가 무엇을 부풀릴지 정한다) */
    private String exaggeration(List<String> tags) {
        if (tags.contains("살인") || tags.contains("괴사")) {
            return "핏자국이 골목 끝까지 이어졌다는 사람도 있고, 열 명이 당했다는 사람도 있어.";
        }
        if (tags.contains("무재") || tags.contains("무인")) {
            return "일수에 셋을 눕혔다던가, 검이 보이지도 않았다던가.";
        }
        if (tags.contains("도적") || tags.contains("치안")) {
            return "떼로 몰려다닌다더군 — 서른은 된다는 소리도 있어.";
        }
        if (tags.contains("질병")) {
            return "한 골목이 통째로 앓아누웠다는 말까지 도네.";
        }
        return "이야기가 붙고 붙어서, 이제는 누구 말이 원래 것인지도 모르겠네.";
    }

    /** 오해 — 범인·동기·대상이 뒤바뀐다. 주체가 미끄러지는 것이 이 구간의 본질이다 */
    private String misheard(String truth, String who, List<String> tags) {
        String slipped = truth.replace(who, "이름 모를 낭인");
        String twist = tags.contains("살인") || tags.contains("폭력")
                ? " 아니, 오히려 그쪽이 당했다는 말도 있어 — 누가 누굴 벴는지 아무도 모르네."
                : tags.contains("사파") || tags.contains("조직원")
                        ? " 관에서 꾸민 일이라는 말도 돌고, 상단 쪽 다툼이라는 말도 돌고."
                        : " 그런데 사람마다 말이 달라. 누구 이야기인지부터 어긋나 있어.";
        return slipped + twist;
    }

    /** 괴담 — 원형을 알아볼 수 없다. 여기서는 사실이 남지 않는다 (거리의 대가) */
    private String ghostStory(List<String> tags) {
        if (tags.contains("살인") || tags.contains("괴사")) {
            return "…밤마다 곡소리가 난다더군. 사람 짓이 아니라는 말까지 나와. "
                    + "누가 죽었는지도, 정말 죽긴 했는지도 이젠 아무도 모르네.";
        }
        if (tags.contains("질병")) {
            return "…역병이 아니라 저주라는 소리가 돌아. 폐사당 쪽에서 시작됐다던가. 흉흉해.";
        }
        return "…뭔가 있긴 있었다는데, 이젠 말하는 사람마다 딴소리야. "
                + "흉흉한 이야기만 남고 알맹이는 사라졌네.";
    }
}
