package com.honcheon.core.rules;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 기 발현 엔진 — config/qi_manifestation.yml 의 자바 구현.
 * 격(格) 사다리·형태(形) 코스트·격 상성 3원칙·무기 파괴(감당 격).
 * 상성 한 줄: "격은 막기를 부수고, 회피는 격을 속인다" — 회피 판정은 이 엔진의 관할 밖이다.
 */
public final class QiManifestationEngine {

    /** 격돌 결과 — 감당_이상 / 1격_초과(누적 — breaks_at 회째 파괴) / 2격_초과(즉시 절단) */
    public enum Clash { NONE, CRACK, SEVER }

    private final Map<String, Integer> gradeRanks;
    private final Map<String, String> gradeGates;
    private final Map<String, Integer> weaponWithstands;   // 무기 등급 → 감당 격 rank
    private final Map<String, Map<String, Object>> forms;  // 발현명 → 수치 (두름/쏨/두름_몸/부림 평탄화)
    private final int breaksAt;
    private final int selfDamageEvery;

    @SuppressWarnings("unchecked")
    public QiManifestationEngine(Map<String, Object> config) {
        Map<String, Object> grades = RulesConfig.section(config, "grades");
        Map<String, Integer> ranks = new LinkedHashMap<>();
        Map<String, String> gates = new LinkedHashMap<>();
        grades.forEach((name, raw) -> {
            Map<String, Object> g = (Map<String, Object>) raw;
            ranks.put(name, ((Number) g.get("rank")).intValue());
            gates.put(name, String.valueOf(g.get("gate")));
        });
        this.gradeRanks = Collections.unmodifiableMap(ranks);
        this.gradeGates = Collections.unmodifiableMap(gates);

        Map<String, Object> weaponGrades = RulesConfig.section(config, "weapon_grades");
        Map<String, Integer> withstands = new LinkedHashMap<>();
        weaponGrades.forEach((name, raw) -> {
            String grade = String.valueOf(((Map<String, Object>) raw).get("withstands"));
            withstands.put(name, ranks.get(grade));
        });
        this.weaponWithstands = Collections.unmodifiableMap(withstands);

        Map<String, Object> rawForms = RulesConfig.section(config, "forms");
        Map<String, Map<String, Object>> flat = new LinkedHashMap<>();
        rawForms.forEach((category, raw) -> ((Map<String, Object>) raw).forEach((name, spec) -> {
            if (spec instanceof Map<?, ?> m) {
                flat.put(name, (Map<String, Object>) m);
            }
        }));
        this.forms = Collections.unmodifiableMap(flat);

        Map<String, Object> wb = RulesConfig.section(config, "weapon_break");
        Map<String, Object> rule = (Map<String, Object>) wb.get("rule");
        this.breaksAt = ((Number) ((Map<String, Object>) rule.get("1격_초과")).get("breaks_at")).intValue();
        this.selfDamageEvery = ((Number) wb.get("self_damage_every")).intValue();
    }

    public int gradeRank(String grade) {
        Integer rank = gradeRanks.get(grade);
        if (rank == null) {
            throw new IllegalArgumentException("정의되지 않은 격: " + grade);
        }
        return rank;
    }

    /** 격의 경지 게이트 — 시전 가능 최저 경지 (internal_energy realm_gates와 정합) */
    public String gradeGate(String grade) {
        return gradeGates.get(grade);
    }

    /** 원칙 1 — 한 격 위의 기운은 아래 격의 '기 방어'를 관통·파괴한다. (원칙 2: 회피는 관할 밖) */
    public boolean piercesGuard(String attackGrade, String defenseGrade) {
        return gradeRank(attackGrade) > gradeRank(defenseGrade);
    }

    /**
     * 무기 격돌 — 원칙 3: 무기가 격을 견뎌야 한다.
     * @param withstandRankUp 감당 격 상향 취급 합계 (명공각인·애병 통령·마병 — equipment)
     */
    public Clash clash(String weaponGrade, String incomingGrade, int withstandRankUp) {
        Integer withstand = weaponWithstands.get(weaponGrade);
        if (withstand == null) {
            throw new IllegalArgumentException("정의되지 않은 무기 등급: " + weaponGrade);
        }
        int incoming = gradeRank(incomingGrade);
        if (incoming <= 1) {
            return Clash.NONE;   // 발경 예외 — 기가 몸·무기 안에 머문다
        }
        int over = incoming - (withstand + withstandRankUp);
        if (over <= 0) {
            return Clash.NONE;
        }
        return over == 1 ? Clash.CRACK : Clash.SEVER;
    }

    /** CRACK 누적 파괴 문턱 — 격돌 n회째 파괴 (1회째부터 '금이 간다' 예고) */
    public int breaksAt() {
        return breaksAt;
    }

    /** 자기 무기가 자기 발현 격을 못 견디는가 (범철에 검기 두름 — n회 시전마다 손상 1) */
    public boolean selfDamages(String weaponGrade, String manifestGrade, int withstandRankUp) {
        return gradeRank(manifestGrade) > weaponWithstands.get(weaponGrade) + withstandRankUp;
    }

    public int selfDamageEvery() {
        return selfDamageEvery;
    }

    /** 발출형(쏨) 1회 소모 — 검기_참격 3, 강기_포 6 */
    public int oneShotCost(String formName) {
        return formInt(formName, "cost");
    }

    /** 전개·시전 비용 — 호신강기 deploy 2, 이기어검 cast 4 */
    public int deployCost(String formName) {
        Map<String, Object> form = form(formName);
        Object v = form.containsKey("deploy") ? form.get("deploy") : form.get("cast");
        if (!(v instanceof Number n)) {
            throw new IllegalArgumentException("전개 비용이 없는 발현: " + formName);
        }
        return n.intValue();
    }

    /** 라운드당 유지비 — 검기_두름 1, 검강_두름·호신강기·이기어검 2 */
    public int sustainPerRound(String formName) {
        return formInt(formName, "sustain_per_round");
    }

    /** 어검사의 회피 페널티 — 마음이 검에 가 있다 */
    @SuppressWarnings("unchecked")
    public int eogeomCasterDodgePenalty() {
        Map<String, Object> penalty = (Map<String, Object>) form("이기어검").get("caster_penalty");
        return ((Number) penalty.get("회피")).intValue();
    }

    private Map<String, Object> form(String name) {
        Map<String, Object> form = forms.get(name);
        if (form == null) {
            throw new IllegalArgumentException("정의되지 않은 발현: " + name);
        }
        return form;
    }

    private int formInt(String name, String key) {
        Object v = form(name).get(key);
        if (!(v instanceof Number n)) {
            throw new IllegalArgumentException(name + " 에 " + key + " 수치가 없다");
        }
        return n.intValue();
    }
}
