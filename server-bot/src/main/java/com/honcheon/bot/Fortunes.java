package com.honcheon.bot;

import com.honcheon.core.rules.RulesConfig;

import java.util.List;
import java.util.Map;

/**
 * 기연(奇緣) 등록부 — config/fortune_encounters.yml 의 봇 쪽 손잡이.
 *
 * <p>등록제: 여기 없는 기연은 존재하지 않는다. 그리고 <b>관문 수치를 코드가 짓지 않는다</b> —
 * 방문 30회·의뢰 15건·사흘 연속은 전부 config 가 준다.
 *
 * <p>★ 이 클래스가 생기기 전, 봇은 관문을 자바 상수(3회·2건)로 갖고 있었고 등록부는 30회·15건이라
 * 적고 있었다. 정본이 둘이면 코드가 이긴다 — 그리고 config 는 장식이 된다. 그 자리를 이 클래스가 막는다.
 */
public final class Fortunes {

    /** 기연 관문 — 조건 충족은 '자격'이지 합격이 아니다 (합격은 trial 이 정한다) */
    public record Gate(String place, int visits, String deedEvent, int deeds, String maxRealm) {
    }

    private final Map<String, Object> cfg;
    private final Map<String, Object> simbeopCfg;
    private final List<String> realmLadder;

    @SuppressWarnings("unchecked")
    public Fortunes(Map<String, Object> cfg, Map<String, Object> cultivationCfg,
                    Map<String, Object> simbeopCfg) {
        this.cfg = cfg;
        this.simbeopCfg = simbeopCfg;
        List<Map<String, Object>> stages =
                (List<Map<String, Object>>) cultivationCfg.get("cultivation_stages");
        this.realmLadder = stages.stream().map(s -> String.valueOf(s.get("name"))).toList();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> encounter(String id) {
        Object e = RulesConfig.section(cfg, "encounters").get(id);
        if (!(e instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("등록되지 않은 기연: " + id);
        }
        return (Map<String, Object>) m;
    }

    /** 관문 — gate 절이 없는 기연은 아직 무대가 없다 (배선 금지 표식) */
    @SuppressWarnings("unchecked")
    public Gate gate(String id) {
        Object g = encounter(id).get("gate");
        if (!(g instanceof Map<?, ?> m)) {
            throw new IllegalStateException("기연 '" + id + "' 에 gate 절이 없다 — 아직 무대가 없는 기연이다");
        }
        Map<String, Object> gate = (Map<String, Object>) m;
        return new Gate(
                String.valueOf(gate.get("place")),
                RulesConfig.intValue(gate.get("visits")),
                String.valueOf(gate.get("deed_event")),
                RulesConfig.intValue(gate.get("deeds")),
                String.valueOf(gate.get("max_realm")));
    }

    /** 경지 상한 — 사다리에서 max_realm 이하인가 (일류가 된 자는 이 문을 지난다) */
    public boolean realmAllowed(String id, String realm) {
        int limit = realmLadder.indexOf(gate(id).maxRealm());
        int mine = realmLadder.indexOf(realm);
        return mine >= 0 && limit >= 0 && mine <= limit;
    }

    /** trial.streak_days — 사흘 '연속' (선택_시험: 시험임을 알려주지 않는다) */
    @SuppressWarnings("unchecked")
    public int trialStreakDays(String id) {
        Object t = encounter(id).get("trial");
        Map<String, Object> trial = (Map<String, Object>) t;
        return RulesConfig.intValue(trial.get("streak_days"));
    }

    /** grants.simbeop (id) → 표시 이름 (simbeop.yml 등록부가 준다 — 이름을 코드가 짓지 않는다) */
    public String grantedSimbeopName(String id) {
        String simbeopId = String.valueOf(grants(id).get("simbeop"));
        Object s = RulesConfig.section(simbeopCfg, "simbeop").get(simbeopId);
        if (!(s instanceof Map<?, ?> m)) {
            throw new IllegalStateException("기연 '" + id + "' 가 미등록 심법을 준다: " + simbeopId);
        }
        return String.valueOf(m.get("name"));
    }

    /** grants.tie — 인연 태그 */
    public String grantedTie(String id) {
        return String.valueOf(grants(id).get("tie"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> grants(String id) {
        Object g = encounter(id).get("grants");
        if (!(g instanceof Map<?, ?> m)) {
            throw new IllegalStateException("기연 '" + id + "' 에 grants 절이 없다");
        }
        return (Map<String, Object>) m;
    }

    /** 1회성 — 세계 원장의 소모 키. 획득 즉시 세계에서 사라진다 (공유 세계 선착순) */
    public String metaKey(String id) {
        return "기연:" + id;
    }
}
