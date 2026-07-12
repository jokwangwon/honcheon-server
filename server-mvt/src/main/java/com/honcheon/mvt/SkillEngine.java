package com.honcheon.mvt;

import com.honcheon.core.rules.EquipmentEngine;
import com.honcheon.core.rules.InternalEnergyEngine;
import com.honcheon.core.rules.JudgmentEngine;
import com.honcheon.core.rules.QiManifestationEngine;
import com.honcheon.core.rules.RulesConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 무공 시전 규칙 — config 판독 전담. 부수효과 없음, Bukkit 의존 0 (테스트 가능).
 *
 * <p>단일 진실 원천:
 * <ul>
 *   <li>config/skills.yml — 무공 카탈로그 (등급·요구 경지)</li>
 *   <li>config/skill_mechanics.yml — 프레임·히트박스·콤보·경직</li>
 *   <li>config/qi_manifestation.yml — 격(格) 사다리·형태 코스트·무기 감당/파괴</li>
 *   <li>config/internal_energy.yml — 내력 풀·경지 게이트·다운캐스트</li>
 *   <li>config/combat.yml — 피해 공식·무기 위력·무공 위력</li>
 *   <li>config/judgment.yml — 판정 등급·비대립 난이도</li>
 *   <li>config/performance.yml — 파티클/틱 예산</li>
 * </ul>
 *
 * <p>판정·격·내력은 core 엔진(JudgmentEngine·QiManifestationEngine·InternalEnergyEngine·
 * EquipmentEngine)에 위임한다. 이 클래스는 그 넷을 무공 문법으로 묶는 배선일 뿐이다.
 */
public final class SkillEngine {

    // ─── config 미정의 폴백 (skill_motion.md 4장에 근거·제안 키를 적어 두었다) ───
    /** 실시간 '라운드' 길이 — 두름(지속형) 유지비 과금 주기. 제안 키: combat.yml realtime.round_ticks */
    private static final int DEFAULT_ROUND_TICKS = 60;
    /** 발출(쏨)의 프레임·쿨다운 — qi_manifestation.yml forms.쏨 에 frames/cooldown_ticks 가 없다 */
    private static final Frames DEFAULT_SHOT_FRAMES = new Frames(8, 2, 12);
    private static final int DEFAULT_SHOT_COOLDOWN = 40;
    /** 발출 사거리 — 선(참격) 길이. 제왕검형(선, length 8)을 기준선으로 삼는다 */
    private static final double DEFAULT_SHOT_LENGTH = 8.0;
    private static final double DEFAULT_SHOT_WIDTH = 1.5;

    /** 격 없는 타격 (외공기) — 격 이름이 아니라 코스트 밴드 이름이다 */
    public static final String BARE = "외공기";

    // ─── core 엔진 ───
    private final InternalEnergyEngine internal;
    private final QiManifestationEngine qi;
    private final JudgmentEngine judgment;
    private final EquipmentEngine equipment;

    // ─── config 단면 ───
    private final Map<String, Object> mechSkills;          // skill_mechanics.yml: skills
    private final Map<String, Integer> staggerTicks;       // skill_mechanics.yml: stagger.levels
    private final int maxTargets;
    private final Map<String, Object> catalog;             // skills.yml: martial_arts
    private final Map<String, Integer> weaponPower;        // combat.yml: damage.weapon_power
    private final Map<String, Integer> techniquePower;     // combat.yml: damage.technique_power
    private final Map<String, Integer> staticDifficulty;   // judgment.yml: static_difficulty
    private final List<String> realmOrder;                 // cultivation.yml: cultivation_stages (범인 → 생사경)
    private final int realmGapPerStage;                    // gm_modifiers.yml: realm_gap.per_stage
    private final List<String> gradeLadder;                // qi_manifestation.yml: grades (rank 오름차순)
    private final int roundTicks;
    private final int meditationFloor;                     // internal_energy.yml: recovery.meditation_floor

    // 성능 예산 (performance.yml)
    private final int particleGlobalPerTick;
    private final int particlePerPlayerPerTick;
    private final int lodFull;
    private final int lodHalf;
    private final int cullBeyond;
    private final int duplicateWindowTicks;

    @SuppressWarnings("unchecked")
    public SkillEngine(Path cfg) {
        Map<String, Object> ie = RulesConfig.load(cfg.resolve("internal_energy.yml"));
        Map<String, Object> qm = RulesConfig.load(cfg.resolve("qi_manifestation.yml"));
        Map<String, Object> jd = RulesConfig.load(cfg.resolve("judgment.yml"));
        Map<String, Object> eq = RulesConfig.load(cfg.resolve("equipment.yml"));
        Map<String, Object> mech = RulesConfig.load(cfg.resolve("skill_mechanics.yml"));
        Map<String, Object> sk = RulesConfig.load(cfg.resolve("skills.yml"));
        Map<String, Object> cb = RulesConfig.load(cfg.resolve("combat.yml"));
        Map<String, Object> pf = RulesConfig.load(cfg.resolve("performance.yml"));

        this.internal = new InternalEnergyEngine(ie);
        this.qi = new QiManifestationEngine(qm);
        this.judgment = new JudgmentEngine(jd);
        this.equipment = new EquipmentEngine(eq);

        this.mechSkills = Collections.unmodifiableMap(RulesConfig.section(mech, "skills"));
        Map<String, Object> stagger = RulesConfig.section(mech, "stagger");
        Map<String, Integer> levels = new LinkedHashMap<>();
        ((Map<String, Object>) stagger.get("levels"))
                .forEach((k, v) -> levels.put(k, RulesConfig.intValue(v)));
        this.staggerTicks = Collections.unmodifiableMap(levels);
        this.maxTargets = RulesConfig.intValue(
                RulesConfig.section(mech, "global_rules").get("max_targets_default"));

        this.catalog = Collections.unmodifiableMap(RulesConfig.section(sk, "martial_arts"));

        Map<String, Object> damage = RulesConfig.section(cb, "damage");
        this.weaponPower = intMap((Map<String, Object>) damage.get("weapon_power"));
        this.techniquePower = intMap((Map<String, Object>) damage.get("technique_power"));
        this.staticDifficulty = intMap(RulesConfig.section(jd, "static_difficulty"));

        List<Map<String, Object>> stages = (List<Map<String, Object>>)
                RulesConfig.load(cfg.resolve("cultivation.yml")).get("cultivation_stages");
        List<String> realms = new ArrayList<>();
        stages.forEach(s -> realms.add(String.valueOf(s.get("name"))));
        this.realmOrder = List.copyOf(realms);
        this.realmGapPerStage = RulesConfig.intValue(RulesConfig
                .section(RulesConfig.load(cfg.resolve("gm_modifiers.yml")), "realm_gap").get("per_stage"));

        Map<String, Object> grades = RulesConfig.section(qm, "grades");
        List<String> ladder = new ArrayList<>(grades.keySet());
        ladder.sort((a, b) -> Integer.compare(qi.gradeRank(a), qi.gradeRank(b)));
        this.gradeLadder = List.copyOf(ladder);

        Map<String, Object> recovery = (Map<String, Object>) RulesConfig
                .section(ie, "internal_energy").get("recovery");
        this.meditationFloor = RulesConfig.intValue(recovery.get("meditation_floor"));

        Object realtime = cb.get("realtime");
        this.roundTicks = realtime instanceof Map<?, ?> m && m.get("round_ticks") instanceof Number n
                ? n.intValue() : DEFAULT_ROUND_TICKS;

        Map<String, Object> particles = RulesConfig.section(pf, "particles");
        this.particleGlobalPerTick = RulesConfig.intValue(particles.get("global_per_tick"));
        this.particlePerPlayerPerTick = RulesConfig.intValue(particles.get("per_player_view_per_tick"));
        Map<String, Object> lod = (Map<String, Object>) particles.get("lod");
        this.lodFull = RulesConfig.intValue(lod.get("full_distance"));
        this.lodHalf = RulesConfig.intValue(lod.get("half_distance"));
        this.cullBeyond = RulesConfig.intValue(lod.get("cull_beyond"));
        this.duplicateWindowTicks = RulesConfig.intValue(
                RulesConfig.section(pf, "skills").get("duplicate_request_window_ticks"));
    }

    private static Map<String, Integer> intMap(Map<String, Object> raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (v instanceof Number n) {
                out.put(k, n.intValue());
            }
        });
        return Collections.unmodifiableMap(out);
    }

    // ══════════ 값 타입 ══════════

    /** 프레임 데이터 [선딜, 지속, 후딜] — 다운캐스트해도 프레임은 유지된다 (internal_energy downcast.keeps) */
    public record Frames(int startup, int active, int recovery) {
        public int total() {
            return startup + active + recovery;
        }
    }

    /**
     * 시전 계획 — 규칙이 계산해 낸 '이번 한 수'의 전부. 연출·적용은 SkillListener 의 몫.
     *
     * @param grade      실제 발현 격 (BARE = 외공기 — 격 없음)
     * @param cost       규칙상 코스트
     * @param paid       실제 차감 (0 이고 cost>0 이면 다운캐스트)
     * @param downcast   내력이 모자라 '맨 기술'로 나갔는가 (위력·속성 상실, 프레임 유지)
     * @param gated      경지 게이트에 막혀 격이 강등됐는가 (요청 격 → 실제 격)
     */
    public record Cast(
            String skillId, String skillName,
            String grade, int cost, int paid, boolean downcast, boolean gated,
            Frames frames, String stagger, int staggerTicks,
            String hitType, double range, double angle,
            int weaponPower, int techniquePower, int gradeBonus,
            int maxTargets, int cooldownTicks) {

        public boolean manifested() {
            return !BARE.equals(grade);
        }
    }

    /** 한 대상에 대한 판정 결과 — 전투는 주사위를 쓴다 (조성기와 달리 난수 허용) */
    public record Strike(int roll, int margin, String tierId, String tierName, boolean hit, int damage) {
    }

    /** 플레이어 무공 런타임 상태 — 순수 데이터 (엔진은 이걸 읽고 쓰지 않는다; 리스너가 소유) */
    public static final class State {
        /** 경지 — MVT 는 캐릭터 시트가 없다. /혼천 경지 로 세운다 (기본: 개화 전) */
        public String realm = "이류";
        /** 내공 실수치 — 내력 풀 = round(내공 × 3) */
        public double naegong = 0.0;
        public int energy = 0;
        /** 격 태세 (두름) — null = 외공기. Shift+우클릭으로 순환 */
        public String armed;
        public int comboIndex;
        public long comboDeadline = -1;
        /** 경직·후딜이 끝나는 틱 — 그 전 입력은 무시 (연타 방지) */
        public long busyUntil = -1;
        public long lastCastTick = -1;
        public long nextSustainTick = -1;
        public final Map<String, Long> cooldownUntil = new HashMap<>();
        /** 자기 무기가 자기 격을 못 견딜 때의 시전 카운터 (n회마다 손상 1) */
        public int selfStrainCount;

        public boolean onCooldown(String key, long now) {
            return cooldownUntil.getOrDefault(key, -1L) > now;
        }

        public int cooldownLeft(String key, long now) {
            return (int) Math.max(0, cooldownUntil.getOrDefault(key, -1L) - now);
        }
    }

    // ══════════ 내력 · 경지 게이트 ══════════

    /** 내력 풀 = round(내공 × 3) — 화후 규칙 */
    public int pool(double naegong) {
        return internal.pool(naegong);
    }

    public boolean canUse(String realm, String band) {
        return internal.canUse(realm, band);
    }

    public boolean isDepleted(int energy) {
        return internal.isDepleted(energy);
    }

    /** 이 경지가 두를 수 있는 격 — 격 태세 순환 목록 (외공기 = null 자리, 여기엔 넣지 않는다) */
    public List<String> armableGrades(String realm) {
        List<String> out = new ArrayList<>();
        for (String grade : gradeLadder) {
            if (internal.canUse(realm, grade)) {
                out.add(grade);
            }
        }
        return out;
    }

    /** 격 태세 순환 — 외공기(null) → 발경 → 검기 → … → 외공기. 경지가 못 여는 격은 건너뛴다 */
    public String cycleArmed(String realm, String current) {
        List<String> armable = armableGrades(realm);
        if (armable.isEmpty()) {
            return null;   // 개화 전 — 몸과 무기가 전부다
        }
        if (current == null) {
            return armable.get(0);
        }
        int idx = armable.indexOf(current);
        return idx < 0 || idx + 1 >= armable.size() ? null : armable.get(idx + 1);
    }

    public int gradeRank(String grade) {
        return BARE.equals(grade) ? 0 : qi.gradeRank(grade);
    }

    public String gradeGate(String grade) {
        return qi.gradeGate(grade);
    }

    /** 원칙 1 — 한 격 위는 아래 격의 기 방어를 관통한다 */
    public boolean piercesGuard(String attack, String defense) {
        return gradeRank(attack) > gradeRank(defense);
    }

    /** 두름 유지비 — 검기_두름 1, 검강_두름 2 (라운드당). 없는 격은 0 */
    public int sustainCost(String grade) {
        String form = switch (grade) {
            case "검기" -> "검기_두름";
            case "강기" -> "검강_두름";
            default -> null;
        };
        return form == null ? 0 : qi.sustainPerRound(form);
    }

    public int roundTicks() {
        return roundTicks;
    }

    /**
     * 운기조식 1구간 회복 — max(내공 × 순도, floor). 하한은 config (meditation_floor).
     * 무방비의 값을 한다: 개화 직후에도 한 구간이면 발경 한 번은 돌아온다.
     */
    public int meditationRecover(double naegong, double purity) {
        return (int) Math.max(Math.round(naegong * purity), meditationFloor);
    }

    // ══════════ 무기 ══════════

    /**
     * 무기 감당 등급 — MVT 근사: 바닐라 재질을 등급 사다리에 얹는다 (equipment.yml weapon_grades).
     * 나무·돌·철·금 = 범철 / 다이아 = 정련 / 네더라이트 = 보병. (신병은 세계 등록제 — 재질로 못 준다)
     */
    /**
     * 무기 등급 — 정식 PDC 태그가 있으면 그것이 진실이다.
     * 재질 근사(legacy)는 바닐라 무기용 폴백일 뿐 — 도구를 병기화하면서 근사가 거짓말을 하기
     * 시작했다 (곡괭이(구) → "맨손", 도끼(부) → "도", 창·권갑·단검이 전부 "검"으로 뭉갠다).
     */
    public String weaponGradeOf(org.bukkit.inventory.ItemStack hand, String materialName) {
        String tagged = Weapons.gradeNameOf(hand);
        return tagged != null ? tagged : legacyGradeOf(materialName);
    }

    /** 무기 계열 — 정식 태그 우선, 없으면 재질 근사 (바닐라 무기 하위호환) */
    public String weaponClassOf(org.bukkit.inventory.ItemStack hand, String materialName) {
        String tagged = Weapons.weaponClassOf(hand);
        return tagged != null ? tagged : legacyClassOf(materialName);
    }

    private String legacyGradeOf(String materialName) {
        String m = materialName.toUpperCase(Locale.ROOT);
        if (m.startsWith("NETHERITE_")) {
            return "보병";
        }
        if (m.startsWith("DIAMOND_")) {
            return "정련";
        }
        return "범철";
    }

    /** 무기 계열 — combat.yml damage.weapon_power 의 키로 환산 */
    private String legacyClassOf(String materialName) {
        String m = materialName == null ? "" : materialName.toUpperCase(Locale.ROOT);
        if (m.endsWith("_SWORD")) {
            return "검";
        }
        if (m.endsWith("_AXE")) {
            return "도";               // 도법 계열의 자리 — 날붙이 한손
        }
        if (m.endsWith("_SHOVEL") || m.endsWith("_HOE")) {
            return "봉";
        }
        if (m.equals("TRIDENT")) {
            return "창";
        }
        return "맨손";
    }

    public int weaponPower(String weaponClass) {
        return weaponPower.getOrDefault(weaponClass, weaponPower.getOrDefault("맨손", 1));
    }

    public int weaponJudgmentBonus(String weaponGrade) {
        return equipment.weaponJudgmentBonus(weaponGrade);
    }

    /** 자기 무기가 자기 발현 격을 못 견디는가 — 범철에 검기 두름 (n회 시전마다 손상 1) */
    public boolean selfDamages(String weaponGrade, String grade, int withstandRankUp) {
        return !BARE.equals(grade) && qi.selfDamages(weaponGrade, grade, withstandRankUp);
    }

    public int selfDamageEvery() {
        return qi.selfDamageEvery();
    }

    /** 무기 격돌 — NONE / CRACK(누적 파괴) / SEVER(즉시 절단) */
    public QiManifestationEngine.Clash clash(String weaponGrade, String incomingGrade, int rankUp) {
        return qi.clash(weaponGrade, incomingGrade, rankUp);
    }

    public int breaksAt() {
        return qi.breaksAt();
    }

    // ══════════ 무공 카탈로그 · 콤보 ══════════

    @SuppressWarnings("unchecked")
    private Map<String, Object> catalogOf(String skillId) {
        Object v = catalog.get(skillId);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    public String skillName(String skillId) {
        Object n = catalogOf(skillId).get("name");
        return n == null ? skillId : String.valueOf(n);
    }

    /** 무공 등급 → 무공 위력 (combat.yml technique_power). 카탈로그 등급명과 표 키의 이음쇠 */
    public int techniquePowerOf(String skillId) {
        String grade = String.valueOf(catalogOf(skillId).getOrDefault("grade", "입문"));
        String key = switch (grade) {
            case "입문" -> "입문";
            case "삼류" -> "삼류급";
            case "이류", "일류" -> "이류급";        // 일류급 칸이 표에 없다 — 아래로 내린다 (보수적)
            case "절정", "초절정", "화경" -> "절정급";
            default -> "입문";
        };
        return techniquePower.getOrDefault(key, 0);
    }

    /** 무공 시전에 요구되는 경지 (skills.yml required_realm) */
    public String requiredRealm(String skillId) {
        return String.valueOf(catalogOf(skillId).getOrDefault("required_realm", "범인"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mechOf(String skillId) {
        Object v = mechSkills.get(skillId);
        if (!(v instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("액션 데이터가 없는 무공: " + skillId + " (skill_mechanics.yml)");
        }
        return (Map<String, Object>) m;
    }

    public boolean hasActionData(String skillId) {
        return mechSkills.containsKey(skillId);
    }

    @SuppressWarnings("unchecked")
    public int comboSize(String skillId) {
        Object combo = mechOf(skillId).get("combo");
        return combo instanceof List<?> l ? l.size() : 0;
    }

    public int comboWindow(String skillId) {
        Object w = mechOf(skillId).get("combo_input_window");
        return w instanceof Number n ? n.intValue() : 0;
    }

    private static Frames frames(Object raw) {
        List<?> f = (List<?>) raw;
        return new Frames(((Number) f.get(0)).intValue(),
                ((Number) f.get(1)).intValue(), ((Number) f.get(2)).intValue());
    }

    public int staggerTicks(String level) {
        return staggerTicks.getOrDefault(level, 0);
    }

    // ══════════ 시전 계획 ══════════

    /**
     * 콤보 1타 계획. 좌클릭 체인의 심장.
     *
     * <p>육합검 1·2타 = 외공기(cost 0) → 3타 = cost 1 = 발경. 코스트는 config 가 정한다.
     * 격 태세(두름)가 켜져 있으면 그 격이 타격의 격이 된다 (검기 두름 = 1타부터 검기).
     * 경지가 못 여는 격은 강등(gated), 내력이 모자라면 다운캐스트(맨 기술 — 프레임만 남는다).
     */
    @SuppressWarnings("unchecked")
    public Cast planCombo(String skillId, int index, String realm, int energy,
                          String armed, String weaponClass) {
        List<Object> combo = (List<Object>) mechOf(skillId).get("combo");
        Map<String, Object> hit = (Map<String, Object>) combo.get(Math.floorMod(index, combo.size()));

        int cost = hit.get("cost") instanceof Number n ? n.intValue() : 0;
        String want = cost > 0 ? "발경" : BARE;               // 코스트가 있으면 그 자체가 발경이다
        if (armed != null && gradeRank(armed) > gradeRank(want)) {
            want = armed;                                     // 두름이 타격의 격을 끌어올린다
        }
        return finish(skillId, want, cost, realm, energy, weaponClass,
                frames(hit.get("frames")),
                String.valueOf(hit.getOrDefault("stagger", "약")),
                String.valueOf(hit.getOrDefault("type", "호")),
                hit.get("range") instanceof Number r ? r.doubleValue() : 3.0,
                hit.get("angle") instanceof Number a ? a.doubleValue() : 100.0,
                0);
    }

    /**
     * 기 발출(쏨) 계획 — 검기_참격(3) / 강기_포(6). 빗나가면 소멸하는 도박.
     * 형태 코스트는 qi_manifestation.yml forms.쏨 이 정한다.
     */
    public Cast planShot(String grade, String realm, int energy, String weaponClass) {
        if (gradeRank(grade) < 2) {
            throw new IllegalArgumentException("발출할 수 없는 격: " + grade);   // 발경은 근접 유일
        }
        // 발출은 '무공'이 아니라 기의 운용 — 코스트는 강등된 격 기준으로 다시 읽는다 (COST_BY_GRADE)
        return finish("__shot__", grade, -1, realm, energy, weaponClass,
                DEFAULT_SHOT_FRAMES, "중", "선", DEFAULT_SHOT_LENGTH, DEFAULT_SHOT_WIDTH,
                DEFAULT_SHOT_COOLDOWN);
    }

    /** 발출(쏨) 1회 소모 — qi_manifestation.yml forms.쏨 */
    private int shotCost(String grade) {
        return switch (grade) {
            case "검기" -> qi.oneShotCost("검기_참격");
            case "강기" -> qi.oneShotCost("강기_포");
            default -> 0;   // 발경 이하는 쏠 것이 없다 → 다운캐스트 경로로 흘러간다
        };
    }

    /**
     * 경지 게이트 강등 → 내력 지불/다운캐스트 → 위력 조립. 두 계획이 공유하는 마지막 마디.
     *
     * @param cost -1 이면 발출 — 강등이 끝난 격으로 코스트를 다시 읽는다 (검강 포 6 → 검기 참격 3)
     */
    private Cast finish(String skillId, String wantGrade, int cost, String realm, int energy,
                        String weaponClass, Frames frames, String stagger, String hitType,
                        double range, double angle, int cooldown) {
        String grade = wantGrade;
        boolean gated = false;
        while (!BARE.equals(grade) && !internal.canUse(realm, grade)) {
            gated = true;
            int rank = gradeRank(grade) - 1;
            grade = rank <= 0 ? BARE : gradeLadder.get(rank - 1);
        }
        int effCost = BARE.equals(grade) ? 0 : (cost < 0 ? shotCost(grade) : cost);
        int paid = internal.payOrDowncast(energy, effCost);
        boolean downcast = effCost > 0 && paid == 0;
        if (downcast) {
            grade = BARE;                                     // 맨 기술 — 위력·속성 상실, 프레임 유지
        }
        int gradeBonus = gradeRank(grade);                    // 격 사다리 = 위력 보정 (rank 그대로)
        return new Cast(skillId, "__shot__".equals(skillId) ? "기 발출" : skillName(skillId),
                grade, effCost, paid, downcast, gated,
                frames, stagger, staggerTicks(stagger), hitType, range, angle,
                weaponPower(weaponClass),
                "__shot__".equals(skillId) ? 0 : techniquePowerOf(skillId),
                gradeBonus, maxTargets, cooldown);
    }

    // ══════════ 판정 (주사위) ══════════

    /** 비대립 저항값 — MVT 근사: 몹은 고정 난이도 (judgment.yml static_difficulty) */
    public int difficulty(String band) {
        return staticDifficulty.getOrDefault(band, staticDifficulty.getOrDefault("보통", 12));
    }

    public int realmIndex(String realm) {
        int idx = realmOrder.indexOf(realm);
        return idx < 0 ? 0 : idx;
    }

    /** 경지 격차 보정 — gm_modifiers.yml realm_gap (전투에만 적용, 단계당 ±2) */
    public int realmGapBonus(String mine, String theirs) {
        return realmGapPerStage * (realmIndex(mine) - realmIndex(theirs));
    }

    public List<String> realmOrder() {
        return realmOrder;
    }

    /**
     * 한 대상 타격 판정 — combat.yml attack/damage 를 JudgmentEngine 에 얹는다.
     * 피해 = 무기 위력 + 무공 위력 + floor(마진/2) + 격 보정. 대성공 = 급소 (부상 단계 +1 근사).
     *
     * @param roll2d6 2d6 — 호출자가 굴린다 (엔진은 난수를 만들지 않는다: 테스트 가능성)
     */
    public Strike strike(Cast cast, int execBase, int roll2d6, int resist) {
        int margin = execBase + roll2d6 - resist;
        JudgmentEngine.Tier tier = judgment.tierOf(margin);
        boolean hit = !"failure".equals(tier.id()) && !"critical_failure".equals(tier.id());
        if (!hit) {
            return new Strike(roll2d6, margin, tier.id(), tier.name(), false, 0);
        }
        int damage = cast.weaponPower() + cast.techniquePower()
                + Math.floorDiv(margin, 2) + cast.gradeBonus();
        if ("critical_success".equals(tier.id())) {
            damage += 2;                                      // 급소 — 부상 단계 즉시 +1 의 MVT 근사
        }
        return new Strike(roll2d6, margin, tier.id(), tier.name(), true, Math.max(1, damage));
    }

    // ══════════ 성능 예산 (performance.yml) ══════════

    public int particleGlobalPerTick() {
        return particleGlobalPerTick;
    }

    public int particlePerPlayerPerTick() {
        return particlePerPlayerPerTick;
    }

    public int lodFull() {
        return lodFull;
    }

    public int lodHalf() {
        return lodHalf;
    }

    public int cullBeyond() {
        return cullBeyond;
    }

    /** F-R1 — 같은 틱 중복 시전 폐기 창 */
    public int duplicateWindowTicks() {
        return duplicateWindowTicks;
    }
}
