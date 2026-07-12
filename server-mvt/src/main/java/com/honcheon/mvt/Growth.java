package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 성장(成長) — <b>이 세계의 캐릭터 시트</b>.
 *
 * <p><b>왜 이 클래스가 필요했나.</b> {@code SkillListener} 에 이런 주석이 있었다:
 * <i>"MVT 근사: 능력치 시트가 없다 — 경지가 능력치의 자리를 대신한다"</i>.
 * 그리고 {@link Vitality#defaultChe(String)} 는 체력을 <b>경지에서 유도</b>했다 (캡 −1).
 * 즉 이 게임에서 <b>플레이어의 몸은 전부 경지의 함수</b>였고, 같은 경지의 두 사람은 <b>완전히 같은 사람</b>이었다.
 * 사용자가 말한 <i>"기술 위력만 올린다든가 몸이 단단한 쪽으로 키우고 싶다든가 하는 선택지가 없다"</i> 는
 * 체감이 아니라 <b>구조</b>였다 — 고를 자리가 코드에 없었다.
 *
 * <p><b>새 축을 만들지 않았다.</b> 이 클래스가 하는 일은 이미 있던 셋을 잇는 것뿐이다:
 * <ol>
 *   <li>하루 5구간 ({@code time.yml day_segments})</li>
 *   <li>삼중 사다리 — 능력치 / 무공 숙련 / 내공 ({@code stats_and_progression.md})</li>
 *   <li>환산표 — 능력치 +1 = 1년 · 숙련 90~2880일 · 내공 n→n+1 = n년</li>
 * </ol>
 * 잇는 방식이 <b>수련 배분</b>이다 ({@code training.yml curriculum}): 하루의 구간을 어느 사다리에 넣는가.
 * <b>포인트를 뿌리는 게 아니라 시간을 쓴다 — 무엇을 반복했는가가 곧 그 사람이 된다.</b>
 *
 * <p><b>1구간 = 0.2일치는 고른 값이 아니다.</b> 수련 하루 = 1일치({@code cultivation.yml daily_cap})이고
 * 하루 = 5구간({@code time.yml})이므로, 그 나눗셈이다. 한 과목에 5구간을 다 넣으면 1.0일치/일 —
 * {@code training.yml} 의 <i>"능력치 +1 = 집중 단련 1년"</i> 과 정확히 맞물린다.
 *
 * <p><b>지배 전략을 막는 것은 밸런스 수치가 아니라 천장과 시간이다.</b> 전투 능력치 5축을 일류 캡(6)까지
 * 채우려면 15~20년이 든다. 일류 도달은 1년이 채 안 된다. <b>그 시간은 없다</b> — 무엇을 천장까지
 * 올릴지가 곧 빌드다. 그리고 천장을 여는 것은 경지다 ({@code attribute_cap_by_realm}).
 *
 * <p>수치는 하나도 갖지 않는다 (등록제 — 전부 config 판독). 검산: {@code tools/growth_audit.py}.
 */
public final class Growth {

    private static Growth instance;

    // ─── 등록부 (config 판독 — 코드가 config 보다 앞서지 않는다) ───

    /** 하루 구간 수 — time.yml segments_per_day 가 정본 */
    private final int segmentsPerDay;
    /** 1구간의 값 (일치) — training.yml curriculum.days_per_segment */
    private final double daysPerSegment;
    /** 능력치 +1 에 드는 일치 — training.yml attribute_progression.cost ("1년") */
    private final double attrCostDays;
    /** 과목 등록부 — 이름 → 정의 (training.yml curriculum.subjects) */
    private final Map<String, Subject> subjects = new LinkedHashMap<>();
    /** 경지별 능력치 상한 — player_creation.yml attribute_cap_by_realm */
    private final Map<String, Integer> attrCap = new LinkedHashMap<>();
    /** 병기 계열 → 공격 판정 능력치 — combat.yml attack.attacker_attribute */
    private final Map<String, String> weaponAttr = new LinkedHashMap<>();
    private final String weaponAttrDefault;
    /** 방어 태세 → (능력치, 경감, 판정 비용) — combat.yml attack.defender_choice */
    private final Map<String, Stance> stances = new LinkedHashMap<>();
    /** 포위 시 잃는 태세 · 감사 바닥 — combat.yml attack.outnumbered_defense.forced_guard */
    private final List<String> forcedLoses = new ArrayList<>();
    private final String forcedFloor;
    /** 심법 id → 결(結) (태세 → 판정 가산) — simbeop.yml <id>.gyeol */
    private final Map<String, Map<String, Integer>> gyeol = new LinkedHashMap<>();
    /** 심법 id → 단전 용량 (내공 상한) */
    private final Map<String, Double> capacity = new LinkedHashMap<>();

    /**
     * 수련 과목 — 구간이 흘러 들어가는 사다리 하나.
     *
     * @param ledgers 쌓이는 원장 (능력치 이름들, 또는 "무공_숙련")
     * @param split   여러 원장에 나눠 질 때의 비율 (외공 = 근력 0.5 / 체력 0.5 — 두 축을 함께 지므로 느리다)
     * @param requires 게이트 (내공 = "심법" — 개화하지 않은 몸은 이 과목에 구간을 넣을 수 없다)
     */
    public record Subject(String name, List<String> ledgers, List<Double> split, String requires) {

        public boolean isSkill() {
            return ledgers.contains("무공_숙련");
        }

        public boolean isNaegong() {
            return ledgers.contains("내공");
        }
    }

    /**
     * 방어 태세 — 세 방어는 세 능력치다 (그것이 빌드의 뒷면이다).
     *
     * @param skill      판정에 서는 '기술' 항 — 회피는 <b>경공</b>, 막기·흘리기는 <b>병기 기술</b>
     *                   ({@code combat.yml defender_choice.<태세>.check})
     * @param weaponSafe 접촉이 없는가 — 회피·흘리기는 무기가 상하지 않는다
     *                   ({@code qi_manifestation.yml weapon_break.trigger} 가 이미 그렇게 적어 뒀다)
     */
    public record Stance(String name, String attribute, String skill, int soak, int penalty,
                         boolean weaponSafe) {

        /** 이 태세의 '기술' 항이 경공인가 (회피) — 아니면 병기 기술이다 */
        public boolean usesGyeonggong() {
            return "경공".equals(skill);
        }
    }

    @SuppressWarnings("unchecked")
    private Growth(Path cfg) {
        Map<String, Object> training = RulesConfig.load(cfg.resolve("training.yml"));
        Map<String, Object> cur = RulesConfig.section(training, "curriculum");
        Map<String, Object> time = RulesConfig.load(cfg.resolve("time.yml"));

        // 하루 5구간 — 정본은 time.yml (training 의 값은 대조용 사본이다)
        this.segmentsPerDay = (int) num(time.get("segments_per_day"),
                num(cur.get("segments_per_day"), 5));
        this.daysPerSegment = num(cur.get("days_per_segment"), 0.2);

        // 능력치 +1 = 1년 (환산표) — 문자열을 일수로 (ProgressionEngine.durationToDays 와 같은 셈)
        Object cost = RulesConfig.section(training, "attribute_progression").get("cost");
        this.attrCostDays = String.valueOf(cost).contains("년") ? 360.0 : 30.0;

        Map<String, Object> subs = RulesConfig.section(cur, "subjects");
        subs.forEach((name, raw) -> {
            if (!(raw instanceof Map)) {
                return;
            }
            Map<String, Object> s = (Map<String, Object>) raw;
            List<String> ledgers = new ArrayList<>();
            Object led = s.get("ledger");
            if (led instanceof List<?> list) {
                list.forEach(v -> ledgers.add(String.valueOf(v)));
            }
            List<Double> split = new ArrayList<>();
            if (s.get("split") instanceof List<?> list) {
                list.forEach(v -> split.add(num(v, 1.0)));
            }
            while (split.size() < ledgers.size()) {
                split.add(1.0 / Math.max(1, ledgers.size()));   // 미지정이면 균등
            }
            Object req = s.get("requires");
            subjects.put(name, new Subject(name, ledgers, split, req == null ? null : String.valueOf(req)));
        });

        RulesConfig.section(RulesConfig.load(cfg.resolve("player_creation.yml")), "attribute_cap_by_realm")
                .forEach((realm, v) -> attrCap.put(realm, (int) num(v, 3)));

        Map<String, Object> combat = RulesConfig.load(cfg.resolve("combat.yml"));
        Map<String, Object> attack = RulesConfig.section(combat, "attack");

        // ★ 병기가 능력치를 정한다 — 이 한 줄이 '외공 지배 전략'을 푼 매듭이다 (growth_audit 이 잡아냈다)
        Map<String, Object> aa = RulesConfig.section(attack, "attacker_attribute");
        aa.forEach((attr, raw) -> {
            if (raw instanceof List<?> list) {
                list.forEach(w -> weaponAttr.put(String.valueOf(w), attr));
            }
        });
        this.weaponAttrDefault = String.valueOf(aa.getOrDefault("default", "근력"));

        RulesConfig.section(attack, "defender_choice").forEach((name, raw) -> {
            if (raw instanceof Map) {
                Map<String, Object> d = (Map<String, Object>) raw;
                stances.put(name, new Stance(name,
                        String.valueOf(d.getOrDefault("attribute", "민첩")),
                        String.valueOf(d.getOrDefault("skill", "병기 기술")),
                        (int) num(d.get("damage_reduction"), 0),
                        (int) num(d.get("penalty"), 0),
                        Boolean.TRUE.equals(d.get("weapon_safe"))));
            }
        });

        Map<String, Object> fg = RulesConfig.section(
                RulesConfig.section(attack, "outnumbered_defense"), "forced_guard");
        if (fg.get("loses") instanceof List<?> list) {
            list.forEach(v -> forcedLoses.add(String.valueOf(v)));
        }
        this.forcedFloor = String.valueOf(fg.getOrDefault("audit_floor", "흘리기"));

        RulesConfig.section(RulesConfig.load(cfg.resolve("simbeop.yml")), "simbeop")
                .forEach((id, raw) -> {
                    if (!(raw instanceof Map)) {
                        return;
                    }
                    Map<String, Object> s = (Map<String, Object>) raw;
                    capacity.put(id, num(s.get("capacity"), 4));
                    Map<String, Integer> g = new LinkedHashMap<>();
                    if (s.get("gyeol") instanceof Map<?, ?> gm) {
                        gm.forEach((k, v) -> g.put(String.valueOf(k).replace("_판정", ""),
                                (int) num(v, 0)));
                    }
                    gyeol.put(id, g);
                });
    }

    /** config 판독 — HoncheonMvt.onEnable 에서 Vitality.init 과 같은 자리 */
    public static void init(Path cfg) {
        instance = new Growth(cfg);
    }

    /** 배선되지 않았으면 null — 호출자는 조용히 예전 동작으로 돌아간다 (연출이 판정을 멈추지 않는다) */
    public static Growth get() {
        return instance;
    }

    // ══════════ 등록부 조회 ══════════

    public int segmentsPerDay() {
        return segmentsPerDay;
    }

    public Map<String, Subject> subjects() {
        return subjects;
    }

    public int cap(String realm) {
        return attrCap.getOrDefault(realm, 3);
    }

    /** 병기 계열 → 공격 판정 능력치 (도=근력 · 검=민첩 · 활/암기=감각) */
    public String attackAttribute(String weaponClass) {
        return weaponAttr.getOrDefault(weaponClass, weaponAttrDefault);
    }

    public Stance stance(String name) {
        return stances.get(name);
    }

    /** 등록된 태세 이름들 — 회피 · 막기 · 흘리기 (코드가 목록을 짓지 않는다) */
    public java.util.Set<String> stanceNames() {
        return stances.keySet();
    }

    /** 이 태세의 경감 — 등록부의 damage_reduction (없는 태세면 0) */
    public int soak(String stanceName) {
        Stance st = stances.get(stanceName);
        return st == null ? 0 : st.soak();
    }

    /** 이 태세가 무기를 태우는가 — 막기만 접촉이 있다 (weapon_break.trigger) */
    public boolean clashes(String stanceName) {
        Stance st = stances.get(stanceName);
        return st != null && !st.weaponSafe();
    }

    /** 포위된 자가 못 고르는 태세 (회피 — 몸을 뺄 자리가 없다) */
    public boolean lostWhenSurrounded(String stance) {
        return forcedLoses.contains(stance);
    }

    public String forcedFloor() {
        return forcedFloor;
    }

    // ══════════ 수련 배분 — 하루를 쓴다 ══════════

    /**
     * <b>하루치 수련을 배분표대로 원장에 붓는다.</b>
     *
     * <p>{@code days} = 그날 수련에 쓴 일치 (5구간 전부면 1.0). 배분표(과목 → 구간)대로 나뉘고,
     * <b>캡에 닿은 과목은 더 받지 않는다</b> — 그 몫은 버려진다 (플레이어가 배분을 바꿔야 한다.
     * 몰빵을 멈추는 것은 페널티가 아니라 <b>천장</b>이다).
     *
     * @return 캡에 막혀 버려진 일치 (0 이면 전부 들어갔다 — HUD 가 "몸이 더는 받지 않는다"를 띄울 근거)
     */
    public double train(PlayerLedger ledger, String realm, double days) {
        Map<String, Integer> alloc = ledger.curriculum();
        if (alloc.isEmpty() || days <= 0) {
            return 0;
        }
        double wasted = 0;
        for (Map.Entry<String, Integer> e : alloc.entrySet()) {
            Subject sub = subjects.get(e.getKey());
            if (sub == null || e.getValue() <= 0) {
                continue;
            }
            // 구간 몫 — 5구간 전부면 그 과목이 하루를 통째로 먹는다 (몰빵)
            wasted += pour(ledger, realm, sub, days * (e.getValue() / (double) segmentsPerDay));
        }
        return wasted;
    }

    /** 한 과목에 붓는다 — 캡을 넘기지 않는다 (넘친 몫은 돌려준다 = 버려진 시간) */
    private double pour(PlayerLedger ledger, String realm, Subject sub, double days) {
        if (days <= 0) {
            return 0;
        }
        if (sub.isSkill()) {
            // 숙련에는 캡이 없다 — 대신 비용이 급증한다 (0→1 은 3개월, 5→6 은 8년). 비용이 곧 천장이다.
            // 원장은 '일치'가 정본이고 레벨은 환산표로 파생된다 (PlayerLedger.levelOf).
            ledger.grant(ledger.primaryArt(), days);
            return 0;
        }
        if (sub.isNaegong()) {
            if (ledger.simbeop() == null) {
                return days;    // ★ 심법 게이트 — 개화하지 않은 몸은 축기할 수 없다 (구간이 통째로 샌다)
            }
            // 천장은 둘 중 낮은 쪽 — 경지 캡 ∧ 단전 용량 (현천토납법 4 · 자하신공 9)
            double room = Math.min(cap(realm), capacity.getOrDefault(ledger.simbeop(), 4.0));
            double cur = ledger.naegong();
            if (cur >= room) {
                return days;
            }
            // 내공은 유일한 배증형 사다리 — n→n+1 = n년 (simbeop.yml dantian.accumulation_cost).
            // 늦게 시작하면 영원히 못 따라간다. 그것이 '일찍 심어야 늦게 거둔다'의 수치다.
            double perPoint = Math.max(1.0, Math.floor(cur)) * 360.0;
            ledger.grantNaegong(Math.min(room - cur, days / perPoint));
            return 0;
        }
        // 능력치 과목 — 외공은 근력·체력 두 축을 split 으로 나눠 진다 (그래서 정수부가 절반 속도다)
        double wasted = 0;
        int cap = cap(realm);
        for (int i = 0; i < sub.ledgers().size(); i++) {
            String attr = sub.ledgers().get(i);
            double share = days * sub.split().get(i);
            double cur = ledger.attr(attr);
            if (cur >= cap) {
                wasted += share;                    // 천장 — 배분을 바꾸지 않으면 그 시간은 버려진다
                continue;
            }
            double room = (cap - cur) * attrCostDays;          // 천장까지 남은 일치
            ledger.grantAttr(attr, Math.min(room, share) / attrCostDays);
            wasted += Math.max(0, share - room);
        }
        return wasted;
    }

    // ══════════ 파생치 — 전부 등록부의 공식 ══════════

    /** 체력 실수치 — {@link Vitality#apply(org.bukkit.entity.Player, String, double)} 의 입력 */
    public double che(PlayerLedger ledger) {
        return ledger.attr("체력");
    }

    /**
     * 공격 판정의 능력치 항 — {@code combat.yml attack.attacker} 의 '능력치'.
     *
     * <p><b>지금 엔진은 이 항을 통째로 빼고 돌고 있다</b> ({@code SkillListener} execBase).
     * 배선하면 그 자리에 들어간다. 판정은 <b>정수부</b>만 본다 (화후 규칙 — 3.9와 4.0은 세계가 다르다).
     */
    public int attackBonus(PlayerLedger ledger, String weaponClass) {
        return (int) ledger.attr(attackAttribute(weaponClass));
    }

    /**
     * <b>방어 판정치</b> = 능력치 + 기술 + 심법의 결 − 판정 비용 + 갑옷.
     *
     * <p>{@code combat.yml defender_choice} 의 세 {@code check} 문장이 여기서 처음으로 참이 된다:
     * <ul>
     *   <li><b>회피</b> = 민첩 + <b>경공</b> + 갑옷(dodge_penalty) — 경공 항은
     *       {@link Gyeonggong#gyeonggongMastery} 가 댄다 (경공 담당이 깔아 둔 이음매).</li>
     *   <li><b>막기</b> = 근력 + <b>병기 기술</b></li>
     *   <li><b>흘리기</b> = 감각 + <b>병기 기술</b> − 2</li>
     * </ul>
     *
     * <p><b>경지 게이트가 없다.</b> 삼류의 회피는 낮지만 존재한다 — 게이트를 두면 삼류가 제 목숨을
     * 영영 못 본다. 방어를 사는 것은 내력이 아니라 <b>몸</b>이다.
     *
     * @param weaponSkill 병기 기술 (주무공 숙련) — 막기·흘리기의 기술 항
     * @param gyeonggong  경공 숙련 — 회피의 기술 항 (보법을 안 배웠으면 0)
     * @param armorDodge  갑옷의 회피 페널티 (equipment.yml dodge_penalty — 무복 0 · 피갑 −1 · 철갑 −2).
     *                    <b>회피에만 든다</b> — 갑옷은 회피를 판다
     * @param surrounded  둘 이상에게 잡혔는가 — 그러면 흘리기의 −2 를 물지 않는다 (forced_guard.waives)
     */
    public int defenseScore(PlayerLedger ledger, String stanceName, int weaponSkill, int gyeonggong,
                            int armorDodge, boolean surrounded) {
        Stance st = stances.get(stanceName);
        if (st == null) {
            return weaponSkill;
        }
        // 고른 자가 무는 대가는 고른 자만 문다 — 강제당한 자는 물지 않는다 (forced_guard.waives 판정_비용)
        int pen = (surrounded && forcedFloor.equals(stanceName)) ? 0 : st.penalty();
        int gy = ledger.simbeop() == null ? 0
                : gyeol.getOrDefault(ledger.simbeop(), Map.of()).getOrDefault(stanceName, 0);
        int skill = st.usesGyeonggong() ? gyeonggong : weaponSkill;
        int armor = st.usesGyeonggong() ? armorDodge : 0;   // 갑옷이 파는 것은 회피뿐이다
        return (int) ledger.attr(st.attribute()) + skill + gy + pen + armor;
    }

    /** 옛 서명 — 기술 항을 가르지 않던 시절 (호출자가 남아 있으면 조용히 근사한다) */
    public int defenseScore(PlayerLedger ledger, String stanceName, int mastery, boolean surrounded) {
        return defenseScore(ledger, stanceName, mastery, mastery, 0, surrounded);
    }

    /**
     * <b>이 몸이 고를 방어</b> — <b>기대 피해가 가장 작은</b> 태세. 포위되면 회피를 못 고른다.
     *
     * <p>★ 판정치만 비교하면 안 된다: 회피는 경감이 0 이고 막기는 −3 이다. 그래서 <b>판정치 + 경감</b>을
     * 함께 잰다 — 그것이 {@code growth_audit.exchange} 가 쓰는 것과 같은 산술이다 (도구와 엔진이 같은
     * 셈을 해야 도구가 거짓말을 안 한다).
     *
     * <p>★ 이것이 빌드가 <b>눈에 보이는</b> 자리다: 민첩을 키운 자는 회피가 뜨고, 근력을 키운 자는
     * 막기가 뜨고, 감각을 키운 자는 흘리기가 뜬다. 그리고 <b>포위당하는 순간 회피 빌드는 태세를 잃는다</b> —
     * 화면이 그 사실을 말해 준다 ("몸을 뺄 자리가 없다").
     */
    public String bestStance(PlayerLedger ledger, int weaponSkill, int gyeonggong, int armorDodge,
                             boolean surrounded) {
        String best = forcedFloor;
        int bestScore = Integer.MIN_VALUE;
        for (String name : stances.keySet()) {
            if (surrounded && lostWhenSurrounded(name)) {
                continue;               // 회피 — 뺄 자리가 없으면 못 고른다
            }
            int score = defenseScore(ledger, name, weaponSkill, gyeonggong, armorDodge, surrounded)
                    + stances.get(name).soak();      // 경감도 방어의 값이다 (판정만 보면 회피가 늘 이긴다)
            if (score > bestScore) {
                bestScore = score;
                best = name;
            }
        }
        return best;
    }

    /** 그 경지에서 이 능력치가 천장에 닿았는가 — HUD 가 "더는 받지 않는다"를 그릴 근거 */
    public boolean atCap(PlayerLedger ledger, String attr, String realm) {
        return ledger.attr(attr) >= cap(realm);
    }

    /**
     * 성장이 <b>보이는</b> 한 줄 — 경락도 GUI·연무장이 그대로 쓴다.
     * 예: {@code 근력 5.5 ▓▓▓▓▓░ (캡 6) · 체력 5.5 → 내구 23}
     */
    public List<String> sheet(PlayerLedger ledger, String realm) {
        List<String> out = new ArrayList<>();
        int cap = cap(realm);
        for (String attr : List.of("근력", "민첩", "체력", "내공", "감각")) {
            double v = ledger.attr(attr);
            boolean capped = v >= cap;
            out.add(String.format("%s %.1f/%d%s", attr, v, cap, capped ? " (천장)" : ""));
        }
        return out;
    }

    private static double num(Object v, double fallback) {
        return v instanceof Number n ? n.doubleValue() : fallback;
    }
}
