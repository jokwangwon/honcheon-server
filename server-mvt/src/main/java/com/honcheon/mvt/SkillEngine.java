package com.honcheon.mvt;

import com.honcheon.core.rules.EquipmentEngine;
import com.honcheon.core.rules.InternalEnergyEngine;
import com.honcheon.core.rules.JudgmentEngine;
import com.honcheon.core.rules.QiManifestationEngine;
import com.honcheon.core.rules.RulesConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    /**
     * 발출(쏨)의 프레임·쿨다운·사거리 — <b>이제 코드의 폴백이 아니다</b>.
     * skill_motion.yml {@code forms.검기_참격 / 강기_포} 가 frames·cooldown_ticks·length·width 를 갖는다.
     * 아래 값은 그 등록이 지워졌을 때의 최후 폴백일 뿐이다 (등록부가 우선).
     */
    private static final Frames DEFAULT_SHOT_FRAMES = new Frames(8, 2, 12);
    private static final int DEFAULT_SHOT_COOLDOWN = 40;
    private static final double DEFAULT_SHOT_LENGTH = 8.0;
    private static final double DEFAULT_SHOT_WIDTH = 1.5;

    /** 격 없는 타격 (외공기) — 격 이름이 아니라 코스트 밴드 이름이다 */
    public static final String BARE = "외공기";
    /** 호신강기 — 격이 아니라 '형태'(두름_몸)다. 방어 격은 강기 (qi_manifestation forms.두름_몸) */
    public static final String GUARD = "호신강기";
    /** 기 발출(쏨) — 무공이 아니라 기의 운용. 모션은 forms.검기_참격 / 강기_포 가 갖는다 */
    public static final String SHOT = "__shot__";

    /**
     * 전투 창(窓) — '한 전투'의 MVT 근사. 이 시간 안에 아무 공방도 없으면 전투가 끝난 것으로 본다
     * (오의 전투당 1회 제한·흐름 누적이 여기서 초기화된다).
     * <b>config 등록 대기</b>: 제안 키 {@code combat.yml realtime.combat_window_ticks: 200}
     */
    private static final int DEFAULT_COMBAT_WINDOW = 200;
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
    private final int combatWindowTicks;
    private final int meditationFloor;                     // internal_energy.yml: recovery.meditation_floor
    private final double meditationFraction;               // internal_energy.yml: recovery.meditation_pool_fraction

    // ─── 조식(調息) — 전투 중 내력 회복 (internal_energy.yml recovery.in_combat.조식) ───
    /** 내공 1단계당 조식 회복량 — ★ '어디서부터 넘치기 시작하는가'를 정하는 수치 */
    private final double regenPerNaegong;
    /** 조식 하한 — 개화한 몸은 숨만 쉬어도 이만큼은 돈다 */
    private final int regenFloor;
    private final boolean regenOnlyIfUnspent;
    /** 두름(병기에 실은 격)의 유지비는 '태운 것'이 아니다 — 숨을 막지 않는다 */
    private final boolean regenUpkeepExempt;
    /** 호신강기(두름_몸) 전개 중에는 단전이 안 돈다 — 무한 방어를 막는 못 */
    private final boolean regenBlockedByGuard;

    // ─── 포위 — 슬롯·협공·피포위 방어·강제 태세 (combat.yml attack) ───
    private final int engageSlots;
    private final int gangPer;
    private final int gangCap;
    private final int outnumberedPer;
    private final int outnumberedCap;
    private final int forcedGuardFrom;
    private final int forcedGuardSoak;

    // 격 위력 — 【엔진 정본】 combat.yml damage.qi_power (qi_manifestation grades[].power 와 동일 값)
    private final Map<String, Integer> qiPower;
    /** 형태별 위력 — 발출형은 더 아프다 (검기_참격 3 > 검기 두름 2). forms[].power 가 격 기본값보다 우선 */
    private final Map<String, Integer> formPower;
    /** 소모 밴드의 스칼라 코스트 — internal_energy.yml cost_bands (발경 1). 범위형([1,3])은 형태가 정한다 */
    private final Map<String, Integer> bandCost;
    // 호신강기 (두름_몸) — 절대 방어는 없다. 무효화는 상쇄 소모를 낸다
    private final String guardGrade;
    private final int guardDeploy;
    private final int guardSustain;
    private final int drainLower;
    private final int drainEqual;
    private final int drainHigher;

    // ─── 방어 태세 (combat.yml attack.defender_stance_mc) — 맞는 쪽의 선택 ───
    /** 몸짓 → 태세 (isBlocking → 막기 · isSneaking → 흘리기 · isSprinting → 회피). 코드가 몸짓을 짓지 않는다 */
    private final Map<String, String> stanceGestures = new LinkedHashMap<>();
    private final String stanceDefault;
    /** 태세 연출 — 파티클·소리·글자. 팩이 없어도 셋 다 보인다 (전부 바닐라) */
    private final Map<String, StanceFx> stanceFx = new LinkedHashMap<>();

    // ─── 갑옷 (equipment.yml armor) — "갑옷은 회피를 판다" 의 반대급부 ───
    /** 갑옷의 경감이 지나가는 격 (강기 — "검강 앞 피갑은 종이") */
    private final String armorPiercedFrom;

    // ─── 표준 무인의 축 (combat_audit realm_axis 와 같은 판독) — NPC 능력치 시트의 폴백 ───
    /** 경지별 능력치 상한 — player_creation.yml attribute_cap_by_realm */
    private final Map<String, Integer> attrCapByRealm = new LinkedHashMap<>();
    /** 경지별 주력 숙련 — cultivation.yml promotion.requirements "주력 무공 숙련 N" (누적) */
    private final Map<String, Integer> realmSkill = new LinkedHashMap<>();

    // NPC — 대칭 원칙 (npc_combat.yml symmetry). 등록부는 npcs/cheongha_npcs.yml 이 정본
    private final Map<String, Npc> npcs;
    private final Map<String, Double> naegongFloor;        // cultivation.yml promotion "내공 N"
    private final int npcThinkTicks;                       // npc_combat.yml think_interval_ticks (상한)

    // 오의 — 격과는 별개의 사다리 (ultimate_arts.yml)
    private final Map<String, Ultimate> ultimates;
    private final Map<String, String> ultimateStage;       // 경지 → 개안|완성|자재|창작
    private final int ultimateFlow;                        // 발동권 — 아슬아슬한 성공 이상 공방 n회
    private final int ultimateBasePower;                   // 오의 기본 위력 (clash.loser_damage)
    private final int ultimateIframeCap;                   // skill_mechanics iframe_caps.오의 (20틱 절대 상한)
    private final String freeLimitRealm;                   // 이 경지부터 전투당 횟수 제한 해제 (자재)

    // 성능 예산 (performance.yml)
    private final int particleGlobalPerTick;
    private final int particlePerPlayerPerTick;
    private final int lodFull;
    private final int lodHalf;
    private final int cullBeyond;
    private final int duplicateWindowTicks;

    // ─── 모션 등록부 (skill_motion.yml) — 【등록제 규약】 연출도 config 가 정본이다 ───
    // 코드에는 파티클 이름도 사운드 키도 없다. 여기 담기는 것은 전부 '문자열'이다 (Bukkit 의존 0 유지).
    private final Map<String, GradeMotion> gradeMotion;
    private final Map<String, FormMotion> formMotion;
    private final Map<String, SkillMotion> skillMotion;
    private final Map<String, UltimateMotion> ultimateMotion;
    private final Map<String, EventMotion> eventMotion;
    private final Map<String, Traj> trajectories;
    private final Map<String, Style> weaponStyles;
    private final Budget budget;

    // ─── 3D 모션 등록부 (skill_motion.yml display) — 파티클 위에 얹는 층 ───
    // 【불변식 ㅁ】 디스플레이는 덧칠이다. 여기가 비어 있어도 파티클 층은 그대로 돈다.
    private final DisplayBudget displayBudget;
    private final Blend displayBlend;
    private final Map<String, DisplayModel> displayModels;
    private final Map<String, DisplayMotion> displayMotions;
    private final Map<String, String> slashByTrail;          // 히트박스 모양 → 참격선 모션
    private final Map<String, Ink> gradeInk;                 // 격 → 획의 굵기·밝기 (외공기도 있다)
    private final Map<String, Sheath> sheathByGrade;         // 격 → 날에 서리는 기 (지속)
    private final Map<String, String> displayByForm;         // 형태 → 모션 id
    private final Map<String, String> displayByUltimate;     // 오의 → 모션 id
    private final Map<String, Swing> swings;                 // 계열 → 획의 손 (굵기·길이·시간)
    private final Map<String, Body> bodyByClass;             // 계열 → 몸의 자세
    private final Map<String, Body> bodyByTrail;             // 궤적 → 몸의 자세 (궤적이 계열을 이긴다)
    private final BodyLimits bodyLimits;
    private final String throwMotion;                        // 던진 물건이 나는 모션
    private final List<String> throwClasses;
    private final Map<String, Basic> basicStrike;            // 【무공 없는 손】 계열 → 기본 히트박스
    private final int basicCooldownTicks;
    /** 판정의 눈 — 【디버그】 히트박스를 재는 자(尺). 켠 사람에게만 보인다 */
    private final Eye eye;
    private final Map<String, List<String>> artsByClass;     // 계열 → 그 계열로 나가는 무공 (원장 해석용)

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

        Map<String, Object> cu = RulesConfig.load(cfg.resolve("cultivation.yml"));
        List<Map<String, Object>> stages = (List<Map<String, Object>>) cu.get("cultivation_stages");
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
        this.meditationFraction = recovery.get("meditation_pool_fraction") instanceof Number mf
                ? mf.doubleValue() : 0.0;

        // ─── 조식 — 전투 중의 숨. config 에 없으면 0 (회복 없음). 코드가 수치를 지어내지 않는다 ───
        Map<String, Object> breath = recovery.get("in_combat") instanceof Map<?, ?> ic
                && ((Map<String, Object>) ic).get("조식") instanceof Map<?, ?> b
                ? (Map<String, Object>) b : Map.of();
        this.regenPerNaegong = breath.get("per_naegong") instanceof Number n3 ? n3.doubleValue() : 0.0;
        this.regenFloor = breath.get("floor") instanceof Number nf ? nf.intValue() : 0;
        this.regenOnlyIfUnspent = Boolean.TRUE.equals(breath.get("only_if_unspent"));
        this.regenUpkeepExempt = Boolean.TRUE.equals(breath.get("upkeep_exempt"));
        this.regenBlockedByGuard = Boolean.TRUE.equals(breath.get("blocked_by_guard"));

        // ─── 포위 — 한 사람을 에워싸면 서로 걸리적거린다 (combat.yml attack) ───
        Map<String, Object> attack = RulesConfig.section(cb, "attack");
        Map<String, Object> gang = RulesConfig.section(attack, "gang_up");
        this.gangPer = RulesConfig.intValue(gang.get("per_extra_attacker"));
        this.gangCap = RulesConfig.intValue(gang.get("max"));
        this.engageSlots = RulesConfig.intValue(gang.get("engage_slots"));
        Map<String, Object> outnumbered = RulesConfig.section(attack, "outnumbered_defense");
        this.outnumberedPer = RulesConfig.intValue(outnumbered.get("per_extra_attacker"));
        this.outnumberedCap = RulesConfig.intValue(outnumbered.get("max"));
        Map<String, Object> forced = outnumbered.get("forced_guard") instanceof Map<?, ?> fg
                ? (Map<String, Object>) fg : Map.of();
        this.forcedGuardFrom = forced.get("trigger_extra_attackers") instanceof Number n4 ? n4.intValue() : 1;
        // 경감치는 forced_guard 가 가리키는 태세(흘리기)의 defender_choice 값이 정본 — 단일 진실 원천
        String stance = String.valueOf(forced.getOrDefault("defense", "흘리기"));
        Object soak = forced.isEmpty() ? null
                : RulesConfig.section(RulesConfig.section(attack, "defender_choice"), stance)
                        .get("damage_reduction");
        this.forcedGuardSoak = soak instanceof Number n5 ? n5.intValue() : 0;

        // ─── 방어 태세 — 【맞는 쪽의 선택】 몸짓·기본값·연출을 전부 등록부에서 읽는다 ───
        //   마인크래프트는 턴제가 아니다. 맞는 순간에 메뉴를 못 띄운다 —
        //   그래서 바닐라가 **이미 가진 세 자세**(손을 세운다·몸을 낮춘다·발이 움직인다)를 읽는다.
        Map<String, Object> mc = RulesConfig.section(attack, "defender_stance_mc");
        RulesConfig.section(mc, "gestures")
                .forEach((posture, predicate) -> stanceGestures.put(String.valueOf(predicate), posture));
        this.stanceDefault = String.valueOf(mc.getOrDefault("default", "자동"));
        RulesConfig.section(mc, "vfx").forEach((name, raw) -> {
            if (raw instanceof Map<?, ?> m3) {
                Map<String, Object> f = (Map<String, Object>) m3;
                stanceFx.put(name, new StanceFx(name,
                        f.get("particle") == null ? null : String.valueOf(f.get("particle")),
                        f.get("count") instanceof Number c ? c.intValue() : 0,
                        f.get("spread") instanceof Number s2 ? s2.doubleValue() : 0.0,
                        f.get("sound") == null ? null : String.valueOf(f.get("sound")),
                        f.get("pitch") instanceof Number p2 ? p2.floatValue() : 1.0f,
                        String.valueOf(f.getOrDefault("label", name))));
            }
        });

        // ─── 갑옷 — 경감의 상한선. 프로즈("검강 앞 피갑은 종이")가 이미 그은 선이다 ───
        Map<String, Object> armorSec = RulesConfig.section(eq, "armor");
        this.armorPiercedFrom = String.valueOf(armorSec.getOrDefault("mitigation_pierced_from", "강기"));

        // ─── 표준 무인의 축 — NPC 능력치 시트가 없을 때의 폴백 (combat_audit realm_axis 와 같은 판독).
        //   ★ 도구와 엔진이 **같은 표준 무인**을 세워야 도구가 거짓말을 안 한다:
        //     능력치 = 경지 상한 −1 (상한을 찍은 자는 표준이 아니다) · 숙련 = 승급 요건의 '주력 무공 숙련 N'
        RulesConfig.section(RulesConfig.load(cfg.resolve("player_creation.yml")), "attribute_cap_by_realm")
                .forEach((realm, v) -> attrCapByRealm.put(realm, v instanceof Number n6 ? n6.intValue() : 3));
        int carried = 0;
        for (Map<String, Object> stage : stages) {
            Object promo = stage.get("promotion");
            if (promo instanceof Map<?, ?> pm && ((Map<String, Object>) pm).get("requirements") instanceof List<?> reqs) {
                for (Object req : reqs) {
                    java.util.regex.Matcher m4 = SKILL_REQ.matcher(String.valueOf(req));
                    if (m4.find()) {
                        carried = Integer.parseInt(m4.group(1));
                    }
                }
            }
            realmSkill.put(String.valueOf(stage.get("name")), carried);
        }

        Object realtime = cb.get("realtime");
        this.roundTicks = realtime instanceof Map<?, ?> m && m.get("round_ticks") instanceof Number n
                ? n.intValue() : DEFAULT_ROUND_TICKS;
        this.combatWindowTicks = realtime instanceof Map<?, ?> m2
                && m2.get("combat_window_ticks") instanceof Number n2 ? n2.intValue() : DEFAULT_COMBAT_WINDOW;

        // ─── 격 위력 (엔진 정본 = combat.yml) · 형태 위력 (발출은 더 아프다) ───
        this.qiPower = intMap((Map<String, Object>) damage.get("qi_power"));
        Map<String, Integer> powers = new LinkedHashMap<>();
        Map<String, Integer> sustains = new LinkedHashMap<>();
        RulesConfig.section(qm, "forms").forEach((category, raw) -> {
            if (raw instanceof Map<?, ?> group) {
                ((Map<String, Object>) group).forEach((form, spec) -> {
                    if (spec instanceof Map<?, ?> s && ((Map<String, Object>) s).get("power") instanceof Number p) {
                        powers.put(form, p.intValue());
                    }
                    if (spec instanceof Map<?, ?> s2
                            && ((Map<String, Object>) s2).get("sustain_per_round") instanceof Number sp) {
                        sustains.put(form, sp.intValue());
                    }
                });
            }
        });
        this.formPower = Collections.unmodifiableMap(powers);

        Map<String, Object> bands = RulesConfig.section(ie, "cost_bands");
        Map<String, Integer> costs = new LinkedHashMap<>();
        bands.forEach((band, raw) -> {
            if (raw instanceof Map<?, ?> m && ((Map<String, Object>) m).get("cost") instanceof Number n) {
                costs.put(band, n.intValue());   // 범위형([1,3])·서술형은 담기지 않는다 — 형태가 정한다
            }
        });
        this.bandCost = Collections.unmodifiableMap(costs);

        // 호신강기 — 방어 격은 forms.두름_몸.호신강기.power 가 가리키는 격 (power 3 = 강기 rank 3)
        this.guardGrade = gradeLadder.get(Math.max(0, formPower.getOrDefault(GUARD, 3) - 1));
        this.guardDeploy = qi.deployCost(GUARD);
        this.guardSustain = sustains.getOrDefault(GUARD, 2);
        Map<String, Object> onHit = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                RulesConfig.section(qm, "forms").get("두름_몸")).get(GUARD)).get("on_hit");
        this.drainLower = drain(onHit, "하위_격", 1);
        this.drainEqual = drain(onHit, "동격", 2);
        this.drainHigher = drain(onHit, "상위_격", 2);

        // ─── NPC 등록부 (대칭 원칙) ───
        Map<String, Double> floors = new LinkedHashMap<>();
        for (Map<String, Object> stage : stages) {
            Object promotion = stage.get("promotion");
            double floor = 0;
            if (promotion instanceof Map<?, ?> pm && ((Map<String, Object>) pm).get("requirements") instanceof List<?> reqs) {
                for (Object req : reqs) {
                    java.util.regex.Matcher m = NAEGONG_REQ.matcher(String.valueOf(req));
                    if (m.find()) {
                        floor = Double.parseDouble(m.group(1));
                    }
                }
            }
            floors.put(String.valueOf(stage.get("name")), floor);
        }
        // 요건에 '내공 N'이 없는 경지(일류 — 요건이 '개화'다 · 생사경 — 요건이 없다)는 등록 보충표를 읽는다.
        // ★ 여기 코드 상수(DEFAULT_FIRST_CLASS_NAEGONG)가 박혀 있었다. 이제 cultivation.yml 이 정본이다 —
        //   그리고 생사경은 내공 0 이라 **심검을 못 쓰는 생사경**이었다 (내력 풀 0). 그 빈칸도 함께 메워진다.
        RulesConfig.section(cu, "realm_naegong_floor").forEach((realm, v) -> {
            if (v instanceof Number n7 && floors.getOrDefault(realm, 0.0) <= 0) {
                floors.put(realm, n7.doubleValue());
            }
        });
        this.naegongFloor = Collections.unmodifiableMap(floors);

        Map<String, Object> nc = RulesConfig.load(cfg.resolve("npc_combat.yml"));
        Object think = nc.get("think_interval_ticks");
        this.npcThinkTicks = think instanceof List<?> l && !l.isEmpty()
                ? ((Number) l.get(l.size() - 1)).intValue() : 10;   // 매 틱 사고 금지 (npc_logic 예산)

        Map<String, Npc> registry = new LinkedHashMap<>();
        RulesConfig.section(RulesConfig.load(cfg.resolve("npcs/cheongha_npcs.yml")), "npcs")
                .forEach((id, raw) -> {
                    if (raw instanceof Map<?, ?> entry) {
                        Npc npc = parseNpc(id, (Map<String, Object>) entry);
                        if (npc != null) {
                            registry.put(id, npc);
                        }
                    }
                });
        this.npcs = Collections.unmodifiableMap(registry);

        // ─── 오의 (ultimate_arts.yml) — 격이 아니다. 별개의 사다리다 ───
        Map<String, Object> ua = RulesConfig.load(cfg.resolve("ultimate_arts.yml"));
        Map<String, String> ladderStages = new LinkedHashMap<>();
        RulesConfig.section(ua, "realm_ladder").forEach((realm, raw) -> {
            if (raw instanceof Map<?, ?> m) {
                ladderStages.put(realm, String.valueOf(((Map<String, Object>) m).get("stage")));
            }
        });
        this.ultimateStage = Collections.unmodifiableMap(ladderStages);

        Map<String, Object> casting = RulesConfig.section(ua, "casting");
        Map<String, Object> limit = (Map<String, Object>) casting.get("per_combat_limit");
        this.freeLimitRealm = limit == null ? "현경" : limit.keySet().stream()
                .filter(k -> k.contains("이상")).findFirst()
                .map(k -> k.substring(0, k.indexOf('_'))).orElse("현경");
        int flow = 3;
        Object activation = casting.get("activation_condition");
        if (activation instanceof Map<?, ?> am && ((Map<String, Object>) am).get("any_of") instanceof List<?> any) {
            for (Object condition : any) {
                java.util.regex.Matcher m = FLOW_REQ.matcher(String.valueOf(condition));
                if (m.find()) {
                    flow = Integer.parseInt(m.group(1));
                }
            }
        }
        this.ultimateFlow = flow;
        java.util.regex.Matcher basePower = POWER_NUM.matcher(
                String.valueOf(RulesConfig.section(ua, "clash").get("loser_damage")));
        this.ultimateBasePower = basePower.find() ? Integer.parseInt(basePower.group(1)) : 6;
        Object caps = mech.get("iframe_caps");
        this.ultimateIframeCap = caps instanceof Map<?, ?> cm && ((Map<String, Object>) cm).get("오의") instanceof Number cn
                ? cn.intValue() : 20;

        Map<String, Ultimate> arts = new LinkedHashMap<>();
        RulesConfig.section(ua, "legacy_arts").forEach((id, raw) -> {
            if (raw instanceof Map<?, ?> m) {
                arts.put(id, parseUltimate(id, (Map<String, Object>) m));
            }
        });
        this.ultimates = Collections.unmodifiableMap(arts);

        Map<String, Object> particles = RulesConfig.section(pf, "particles");
        this.particleGlobalPerTick = RulesConfig.intValue(particles.get("global_per_tick"));
        this.particlePerPlayerPerTick = RulesConfig.intValue(particles.get("per_player_view_per_tick"));
        Map<String, Object> lod = (Map<String, Object>) particles.get("lod");
        this.lodFull = RulesConfig.intValue(lod.get("full_distance"));
        this.lodHalf = RulesConfig.intValue(lod.get("half_distance"));
        this.cullBeyond = RulesConfig.intValue(lod.get("cull_beyond"));
        this.duplicateWindowTicks = RulesConfig.intValue(
                RulesConfig.section(pf, "skills").get("duplicate_request_window_ticks"));

        // ─── 모션 등록부 (config/skill_motion.yml) ───
        Map<String, Object> mo = RulesConfig.load(cfg.resolve("skill_motion.yml"));
        Map<String, Object> bd = RulesConfig.section(mo, "budget");
        this.budget = new Budget(
                intOr(bd.get("per_point_tick_max"), 30),
                intOr(bd.get("per_cast_max"), 160),
                intOr(bd.get("telegraph_pool"), 32),
                intOr(bd.get("trail_pool"), 24),
                intOr(bd.get("impact_pool"), 48),
                intOr(bd.get("min_impact_per_target"), 3),
                intOr(bd.get("ultimate_per_tick_max"), 48),
                intOr(bd.get("telegraph_step_ticks"), 2),
                intOr(bd.get("trail_max_points"), 12),
                intOr(bd.get("ultimate_ring_points"), 8));

        Map<String, GradeMotion> gms = new LinkedHashMap<>();
        RulesConfig.section(mo, "grades").forEach((grade, raw) -> {
            Map<String, Object> g = asMap(raw);
            Map<String, Object> sounds = asMap(g.get("sounds"));
            gms.put(grade, new GradeMotion(grade,
                    intOr(g.get("rank"), 0), intOr(g.get("brightness"), 0),
                    String.valueOf(g.getOrDefault("color", "GRAY")),
                    fx(g.get("charge")), fx(g.get("aura")), fx(g.get("impact")), fx(g.get("accent")),
                    str(asMap(g.get("trail")).get("particle")), intOr(asMap(g.get("trail")).get("per_point"), 1),
                    sfxList(sounds.get("charge")), sfxList(sounds.get("arm")), sfxList(sounds.get("impact"))));
        });
        this.gradeMotion = Collections.unmodifiableMap(gms);

        Map<String, FormMotion> fms = new LinkedHashMap<>();
        RulesConfig.section(mo, "forms").forEach((name, raw) -> {
            Map<String, Object> f = asMap(raw);
            Map<String, Object> sounds = asMap(f.get("sounds"));
            Map<String, Object> ring = asMap(f.get("ring"));
            Object fr = f.get("frames");
            fms.put(name, new FormMotion(name,
                    str(f.get("kind")), str(f.get("grade")), str(f.get("trail")),
                    dblOr(f.get("length"), DEFAULT_SHOT_LENGTH), dblOr(f.get("width"), DEFAULT_SHOT_WIDTH),
                    fr instanceof List<?> ? frames(fr) : DEFAULT_SHOT_FRAMES,
                    intOr(f.get("cooldown_ticks"), DEFAULT_SHOT_COOLDOWN),
                    fx(f.get("charge")), str(asMap(f.get("beam")).get("particle")),
                    intOr(asMap(f.get("beam")).get("per_point"), 1),
                    fx(f.get("burst")), fx(f.get("aura")),
                    str(ring.get("particle")), intOr(ring.get("points"), 0),
                    dblOr(ring.get("radius"), 0.9), intOr(ring.get("per_point"), 1),
                    dblOr(ring.get("height"), 1.0),
                    sfxList(sounds.get("charge")), sfxList(sounds.get("release")),
                    sfxList(sounds.get("deploy"))));
        });
        this.formMotion = Collections.unmodifiableMap(fms);

        Map<String, SkillMotion> sms = new LinkedHashMap<>();
        RulesConfig.section(mo, "skills").forEach((id, raw) -> {
            Map<String, Object> s = asMap(raw);
            List<Step> steps = new ArrayList<>();
            if (s.get("steps") instanceof List<?> list) {
                for (Object o : list) {
                    Map<String, Object> st = asMap(o);
                    steps.add(new Step(str(st.get("trail")), str(st.get("particle")),
                            intOr(st.get("count"), 1), Boolean.TRUE.equals(st.get("finisher")),
                            intOr(st.get("telegraph_boost"), 0), sfx(st.get("sound")),
                            // 【등록부는 소리를 줄일 수만 있다】 상한 1.0 — 키우는 것은 sound.volume 의 몫
                            (float) Math.max(0.0, Math.min(1.0, dblOr(st.get("sound_scale"), 1.0)))));
                }
            }
            sms.put(id, new SkillMotion(id, String.valueOf(s.getOrDefault("name", id)),
                    String.valueOf(s.getOrDefault("style", "무관")), List.copyOf(steps)));
        });
        this.skillMotion = Collections.unmodifiableMap(sms);

        Map<String, UltimateMotion> ums = new LinkedHashMap<>();
        RulesConfig.section(mo, "ultimates").forEach((id, raw) -> {
            Map<String, Object> u = asMap(raw);
            Map<String, Object> charge = asMap(u.get("charge"));
            Map<String, Object> sounds = asMap(u.get("sounds"));
            ums.put(id, new UltimateMotion(id, str(u.get("trail")),
                    str(charge.get("particle")), intOr(charge.get("ring_points"), 8),
                    intOr(charge.get("per_point"), 2), str(charge.get("core")),
                    intOr(charge.get("core_count"), 0),
                    fx(u.get("burst")), fx(u.get("accent")),
                    dblOr(asMap(u.get("burst")).get("spread_ratio"), 0.33),
                    sfxList(sounds.get("charge")), sfxList(sounds.get("release")),
                    sfxList(sounds.get("hit"))));
        });
        this.ultimateMotion = Collections.unmodifiableMap(ums);

        Map<String, EventMotion> ems = new LinkedHashMap<>();
        RulesConfig.section(mo, "events").forEach((name, raw) -> {
            Map<String, Object> e = asMap(raw);
            ems.put(name, new EventMotion(name, fx(e), sfxList(e.get("sounds"))));
        });
        this.eventMotion = Collections.unmodifiableMap(ems);

        Map<String, Traj> tjs = new LinkedHashMap<>();
        RulesConfig.section(mo, "trajectories").forEach((type, raw) -> {
            Map<String, Object> t = asMap(raw);
            tjs.put(type, new Traj(type, str(t.get("shape")),
                    Math.min(intOr(t.get("points"), 4), budget.trailMaxPoints()),
                    str(t.get("phase")), dblOr(t.get("radius_ratio"), 1.0),
                    dblOr(t.get("step"), 1.0), str(t.get("accent_at"))));
        });
        this.trajectories = Collections.unmodifiableMap(tjs);

        Map<String, Style> sts = new LinkedHashMap<>();
        RulesConfig.section(mo, "weapon_styles").forEach((cls, raw) -> {
            Map<String, Object> s = asMap(raw);
            sts.put(cls, new Style(cls, str(s.get("verb")), str(s.get("arc")), str(s.get("thrust")),
                    str(s.get("trail")), sfx(s.get("swing"))));
        });
        this.weaponStyles = Collections.unmodifiableMap(sts);

        // ─── 3D 모션 등록부 (skill_motion.yml display) ───
        // 예산은 performance.yml vfx_entities 와 **둘 중 작은 쪽**을 쓴다 — 등록부가 상위 예산을 못 넘는다
        Map<String, Object> dp = RulesConfig.section(mo, "display");
        Map<String, Object> db = asMap(dp.get("budget"));
        Map<String, Object> vfx = RulesConfig.section(pf, "vfx_entities");
        this.displayBudget = new DisplayBudget(
                Math.min(intOr(db.get("global_cap"), 120), intOr(vfx.get("global_cap"), 120)),
                Math.min(intOr(db.get("max_lifetime_ticks"), 60),
                        intOr(vfx.get("max_lifetime_ticks"), 60)),
                intOr(db.get("degrade_at"), 90),
                intOr(db.get("reserve_for_ultimate"), 16),
                intOr(db.get("per_cast_max"), 6),
                intOr(db.get("per_player_max"), 8),
                (float) dblOr(db.get("view_range"), 0.5),
                intOr(db.get("heartbeat_ticks"), 12),
                intOr(db.get("slash_min_ticks"), 3),
                intOr(db.get("slash_max_ticks"), 18));

        Map<String, Object> bl = asMap(dp.get("blend"));
        this.displayBlend = new Blend(
                dblOr(bl.get("trail_particle_ratio"), 1.0),
                intOr(bl.get("trail_particle_min"), 1),
                !Boolean.FALSE.equals(bl.get("keep_impact")));

        Map<String, DisplayModel> dms = new LinkedHashMap<>();
        asMap(dp.get("models")).forEach((id, raw) -> {
            Map<String, Object> m = asMap(raw);
            dms.put(id, new DisplayModel(id, str(m.get("key")),
                    String.valueOf(m.getOrDefault("base", "PAPER")),
                    String.valueOf(m.getOrDefault("fallback", "HELD")),
                    vec3(m.get("size"), 1.0f),
                    Boolean.TRUE.equals(m.get("use_held"))));
        });
        this.displayModels = Collections.unmodifiableMap(dms);

        Map<String, DisplayMotion> dmo = new LinkedHashMap<>();
        asMap(dp.get("motions")).forEach((id, raw) -> {
            Map<String, Object> m = asMap(raw);
            dmo.put(id, new DisplayMotion(id, str(m.get("kind")), str(m.get("model")),
                    Math.min(intOr(m.get("lifetime"), 10), displayBudget.maxLifetimeTicks()),
                    intOr(m.get("birth"), 0), intOr(m.get("fade"), 0),
                    Math.max(1, intOr(m.get("interpolation"), 2)),
                    (float) dblOr(m.get("spread"), 1.0),
                    (float) dblOr(m.get("spin"), 0.0), dblOr(m.get("speed"), 0.0),
                    (float) dblOr(m.get("impact_scale"), 1.0),
                    intOr(m.get("impact_ticks"), 3), intOr(m.get("stick_ticks"), 0),
                    intOr(m.get("count"), 1), dblOr(m.get("radius"), 0.85),
                    dblOr(m.get("height"), 1.0), (float) dblOr(m.get("orbit"), 0.0),
                    // burst_scale — 스칼라(세 축이 같이 자란다)도 [x,y,z](축마다 다르게)도 받는다
                    axes(m.get("burst_scale"), 1.0f),
                    // orient — [pitch, yaw, roll] 도. 없으면 [0,0,0] = 예전 그대로 (yaw 로만 선다)
                    axes(m.get("orient"), 0.0f),
                    String.valueOf(m.getOrDefault("billboard", "FIXED")),
                    intOr(idx(m.get("brightness"), 0), 15),
                    intOr(idx(m.get("brightness"), 1), 15)));
        });
        this.displayMotions = Collections.unmodifiableMap(dmo);

        Map<String, Object> bind = asMap(dp.get("bind"));
        this.displayByForm = bindMap(bind.get("forms"));
        this.displayByUltimate = bindMap(bind.get("ultimates"));
        this.slashByTrail = bindMap(dp.get("slashes"));

        // 격이 획에 스며든다 — 외공기도 획을 그린다 (그것이 이 층의 요점이다)
        Map<String, Ink> inks = new LinkedHashMap<>();
        asMap(dp.get("grade_ink")).forEach((grade, raw) -> {
            Map<String, Object> i = asMap(raw);
            inks.put(grade, new Ink(grade, (float) dblOr(i.get("thickness"), 1.0),
                    intOr(idx(i.get("brightness"), 0), 0), intOr(idx(i.get("brightness"), 1), 15),
                    intOr(i.get("hold"), 0)));
        });
        this.gradeInk = Collections.unmodifiableMap(inks);

        // 날의 기 — 격을 두르면 손에 든 병기에 겹쳐 서린다 (지속)
        Map<String, Sheath> shs = new LinkedHashMap<>();
        asMap(dp.get("sheaths")).forEach((grade, raw) -> {
            if (!(raw instanceof Map<?, ?>)) {
                return;   // null — 몸에 두른 것(호신강기)은 고리가 그 자리다
            }
            Map<String, Object> s = asMap(raw);
            shs.put(grade, new Sheath(grade, str(s.get("motion")), vec3(s.get("scale"), 1.0f),
                    intOr(idx(s.get("brightness"), 0), 15), intOr(idx(s.get("brightness"), 1), 15)));
        });
        this.sheathByGrade = Collections.unmodifiableMap(shs);

        // 계열의 손 — reach 는 **1.0 을 넘지 못한다** (히트박스보다 길게 그릴 수 없다 · 불변식 ㅂ)
        Map<String, Swing> sws = new LinkedHashMap<>();
        asMap(dp.get("swings")).forEach((cls, raw) -> {
            if (!(raw instanceof Map<?, ?>)) {
                return;   // null — 벨 것이 없는 계열 (무관·짐승)
            }
            Map<String, Object> s = asMap(raw);
            double ratio = dblOr(s.get("span_ratio"), 0.0);
            if (ratio <= 0) {
                return;   // 활 — 휘두르는 물건이 아니다
            }
            sws.put(cls, new Swing(cls, ratio,
                    Math.min(1.0, dblOr(s.get("reach"), 1.0)),   // 【정직의 못】 등록부는 줄일 수만 있다
                    (float) dblOr(s.get("thickness"), 1.0), (float) dblOr(s.get("tilt"), 0.0)));
        });
        this.swings = Collections.unmodifiableMap(sws);

        // 몸의 자세 — 되는 것만 (전진·pose). 팔다리 각도는 바닐라 프로토콜에 자리가 없다
        Map<String, Object> body = asMap(dp.get("body"));
        Map<String, Object> lim = asMap(body.get("limits"));
        this.bodyLimits = new BodyLimits(
                dblOr(lim.get("max_lunge"), 0.45),
                intOr(lim.get("max_pose_ticks"), 8),
                !Boolean.FALSE.equals(lim.get("require_ground")),
                Boolean.TRUE.equals(lim.get("yaw_kick_enabled")),
                (float) dblOr(lim.get("kick_max"), 12.0));
        this.bodyByClass = bodyMap(body.get("by_class"), bodyLimits);
        this.bodyByTrail = bodyMap(body.get("by_trail"), bodyLimits);

        Map<String, Object> thr = asMap(dp.get("throw"));
        this.throwMotion = str(thr.get("motion"));
        this.throwClasses = thr.get("classes") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : List.of();

        // 【무공 없는 손】 병기를 들고 좌클릭하면 그것만으로 궤적이 뜬다 (가장 흔한 경로)
        Map<String, Object> bs = RulesConfig.section(mo, "basic_strike");
        this.basicCooldownTicks = intOr(bs.get("cooldown_ticks"), 4);
        Map<String, Basic> bsc = new LinkedHashMap<>();
        asMap(bs.get("by_class")).forEach((cls, raw) -> {
            if (!(raw instanceof Map<?, ?>)) {
                return;   // null — 활·무관·짐승: 우리가 얹을 것이 없다
            }
            Map<String, Object> b2 = asMap(raw);
            bsc.put(cls, new Basic(cls, String.valueOf(b2.getOrDefault("trail", "호")),
                    dblOr(b2.get("range"), 3.0), dblOr(b2.get("angle"), 100.0),
                    b2.get("frames") instanceof List<?> ? frames(b2.get("frames")) : new Frames(2, 2, 4)));
        });
        this.basicStrike = Collections.unmodifiableMap(bsc);

        // 판정의 눈 — 【디버그】 등록부가 자(尺)의 촘촘함과 예산을 정한다 (코드가 정하지 않는다)
        Map<String, Object> ey = RulesConfig.section(mo, "eye");
        Map<String, Object> hb = asMap(ey.get("hitbox"));
        this.eye = new Eye(
                str(hb.get("particle")), dblOr(hb.get("step"), 0.6), dblOr(hb.get("height"), 0.3),
                intOr(hb.get("max_points"), 140),
                str(asMap(ey.get("targets")).get("particle")),
                !Boolean.FALSE.equals(ey.get("log")));

        // 계열 → 무공 (원장 해석용) — 하드코딩된 무공표를 지운 자리다
        Map<String, List<String>> abc = new LinkedHashMap<>();
        RulesConfig.section(sk, "martial_arts").forEach((id, raw) -> {
            if (!mechSkills.containsKey(id)) {
                return;   // 액션 데이터가 없는 무공은 손에 실리지 않는다
            }
            Object wc = asMap(raw).get("weapon_class");
            List<?> classes = wc instanceof List<?> l ? l : wc == null ? List.of() : List.of(wc);
            for (Object c : classes) {
                abc.computeIfAbsent(String.valueOf(c), k -> new ArrayList<>()).add(id);
            }
        });
        this.artsByClass = Collections.unmodifiableMap(abc);
    }

    /** 몸의 자세 한 줄 — 상한(limits)을 여기서 이미 물린다 (등록부가 조작감을 해치지 못한다) */
    private static Map<String, Body> bodyMap(Object raw, BodyLimits limits) {
        Map<String, Body> out = new LinkedHashMap<>();
        asMap(raw).forEach((key, v) -> {
            if (!(v instanceof Map<?, ?>)) {
                return;
            }
            Map<String, Object> b = asMap(v);
            double lunge = dblOr(b.get("lunge"), 0.0);
            lunge = Math.max(-limits.maxLunge(), Math.min(limits.maxLunge(), lunge));
            String pose = String.valueOf(b.getOrDefault("pose", "없음"));
            int poseTicks = Math.min(limits.maxPoseTicks(), intOr(b.get("pose_ticks"), 0));
            float yaw = limits.yawKickEnabled() ? (float) dblOr(b.get("yaw_kick"), 0.0) : 0.0f;
            float pitch = limits.yawKickEnabled() ? (float) dblOr(b.get("pitch_kick"), 0.0) : 0.0f;
            out.put(key, new Body(key, lunge, pose, poseTicks, yaw, pitch,
                    beats(b.get("script"), limits), beat(b.get("windup"), 0.0, limits)));
        });
        return Collections.unmodifiableMap(out);
    }

    /**
     * 자세의 줄기 — <b>획이 그려지는 동안 몸이 흐른다</b>. 마지막 beat 는 등록부가 무엇을 적었든
     * <b>원점(0,0)</b> 이다 (불변식: 회전은 순증하지 않는다 — 반동이지 조준 훼손이 아니다).
     */
    private static List<Beat> beats(Object raw, BodyLimits limits) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Beat> out = new ArrayList<>();
        for (Object o : list) {
            Beat b = beat(o, 0.0, limits);
            if (b != null) {
                out.add(b);
            }
        }
        out.sort(Comparator.comparingDouble(Beat::at));
        if (out.isEmpty()) {
            return List.of();
        }
        // 【못】 몸은 반드시 돌아온다 — 마지막 칸의 회전을 강제로 0 으로 못박는다
        Beat last = out.get(out.size() - 1);
        out.set(out.size() - 1, new Beat(1.0, last.lunge(), last.pose(), 0.0f, 0.0f, last.hand()));
        return List.copyOf(out);
    }

    private static Beat beat(Object raw, double defaultAt, BodyLimits limits) {
        if (!(raw instanceof Map<?, ?>)) {
            return null;
        }
        Map<String, Object> b = asMap(raw);
        double at = Math.max(0.0, Math.min(1.0, dblOr(b.get("at"), defaultAt)));
        double lunge = dblOr(b.get("lunge"), 0.0);
        lunge = Math.max(-limits.maxLunge(), Math.min(limits.maxLunge(), lunge));
        float kick = Math.abs(limits.kickMax());
        float yaw = limits.yawKickEnabled() ? (float) dblOr(b.get("yaw"), 0.0) : 0.0f;
        float pitch = limits.yawKickEnabled() ? (float) dblOr(b.get("pitch"), 0.0) : 0.0f;
        yaw = Math.max(-kick, Math.min(kick, yaw));       // 조작감이 등록부보다 높다 — 여기서 깎는다
        pitch = Math.max(-kick, Math.min(kick, pitch));
        String pose = b.get("pose") == null ? null : String.valueOf(b.get("pose"));
        String hand = b.get("hand") == null ? null : String.valueOf(b.get("hand"));
        return new Beat(at, lunge, pose, yaw, pitch, hand);
    }

    /** 배선표 — 값이 null(형체 없음)인 칸은 아예 담지 않는다 (없는 것과 같다) */
    private static Map<String, String> bindMap(Object raw) {
        Map<String, String> out = new LinkedHashMap<>();
        asMap(raw).forEach((k, v) -> {
            String id = str(v);
            if (id != null && !"null".equals(id)) {
                out.put(k, id);
            }
        });
        return Collections.unmodifiableMap(out);
    }

    /** [x, y, z] → float 3 (등록부의 치수·배율). Bukkit·JOML 타입은 SkillDisplay 의 몫이다 */
    private static float[] vec3(Object raw, float fallback) {
        float[] out = {fallback, fallback, fallback};
        if (raw instanceof List<?> list) {
            for (int i = 0; i < 3 && i < list.size(); i++) {
                if (list.get(i) instanceof Number n) {
                    out[i] = n.floatValue();
                }
            }
        }
        return out;
    }

    /**
     * 세 축의 값 — <b>스칼라 하나</b>({@code burst_scale: 0.5}: 세 축이 같다)도
     * <b>[x, y, z]</b>({@code burst_scale: [0.06, 0.45, 0.30]}: 축마다 다르다)도 받는다.
     * 스칼라를 그대로 두면 예전 문법이 그대로 돈다 — 등록부는 <b>필요한 칸에서만</b> 축을 가른다.
     */
    private static float[] axes(Object raw, float fallback) {
        if (raw instanceof Number n) {
            float v = n.floatValue();
            return new float[]{v, v, v};
        }
        return vec3(raw, fallback);
    }

    private static Object idx(Object raw, int i) {
        return raw instanceof List<?> list && i < list.size() ? list.get(i) : null;
    }

    // ─── 모션 등록부 판독 도우미 (전부 문자열·수치 — Bukkit 타입은 SkillListener 가 해석한다) ───

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String str(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static int intOr(Object raw, int fallback) {
        return raw instanceof Number n ? n.intValue() : fallback;
    }

    private static double dblOr(Object raw, double fallback) {
        return raw instanceof Number n ? n.doubleValue() : fallback;
    }

    private static Fx fx(Object raw) {
        Map<String, Object> m = asMap(raw);
        return new Fx(str(m.get("particle")), intOr(m.get("count"), 0),
                dblOr(m.get("spread"), 0.1), dblOr(m.get("extra"), 0.0));
    }

    private static Sfx sfx(Object raw) {
        Map<String, Object> m = asMap(raw);
        return m.get("key") == null ? null
                : new Sfx(String.valueOf(m.get("key")),
                        (float) dblOr(m.get("volume"), 0.8), (float) dblOr(m.get("pitch"), 1.0));
    }

    private static List<Sfx> sfxList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Sfx> out = new ArrayList<>();
        for (Object o : list) {
            Sfx s = sfx(o);
            if (s != null) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    // ─── config 문자열에서 수치를 캐낸다 (서술문 안의 정본 수치 — 별도 키가 생기면 이 정규식은 사라진다) ───
    private static final java.util.regex.Pattern NAEGONG_REQ = java.util.regex.Pattern.compile("내공\\s*(\\d+)");
    private static final java.util.regex.Pattern FLOW_REQ =
            java.util.regex.Pattern.compile("공방\\s*(\\d+)\\s*회");
    private static final java.util.regex.Pattern POWER_NUM = java.util.regex.Pattern.compile("위력\\s*(\\d+)");
    private static final java.util.regex.Pattern PERCENT = java.util.regex.Pattern.compile("(\\d+)\\s*%");
    private static final java.util.regex.Pattern IFRAME = java.util.regex.Pattern.compile("무적\\s*(\\d+)\\s*틱");
    /** 승급 요건의 '주력 무공 숙련 N' — 표준 무인의 손 (combat_audit realm_axis 와 같은 정규식) */
    private static final java.util.regex.Pattern SKILL_REQ =
            java.util.regex.Pattern.compile("주력 무공 숙련\\s*(\\d+)");

    @SuppressWarnings("unchecked")
    private static int drain(Map<String, Object> onHit, String key, int fallback) {
        if (onHit.get(key) instanceof Map<?, ?> m
                && ((Map<String, Object>) m).get("상쇄_소모") instanceof Number n) {
            return n.intValue();
        }
        return fallback;
    }

    /**
     * 등록부 한 줄 → 격을 쓸 수 있는 몸. 대칭 원칙(npc_combat.yml symmetry): NPC 도 같은 규칙이다.
     *
     * <p>내력의 출처 (config 그대로):
     * <ul>
     *   <li>개체에 {@code internal_energy} 가 적혀 있으면 그것이 진실이다 (백영묘 9 = round(내공 3.0 × 3))</li>
     *   <li>짐승 — "들짐승·맹수는 내력이 없다 (내공 0). 영물만 내력을 쓴다" (npc_combat.yml beasts.rules)</li>
     *   <li>사람 — 경지의 내공 하한(cultivation.yml promotion "내공 N")으로 풀을 세운다</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private Npc parseNpc(String id, Map<String, Object> e) {
        String rank = e.get("beast_rank") == null ? null : String.valueOf(e.get("beast_rank"));
        Object realmValue = e.get("realm");
        if (realmValue == null && rank == null) {
            return null;   // 전투에 서지 않는 사람 (객잔 주인·의원…) — 경지가 없으면 격도 없다
        }
        String realm = realmValue == null ? "삼류" : String.valueOf(realmValue);
        boolean beast = rank != null;
        boolean spirit = "영물".equals(rank);

        int pool;
        if (e.get("internal_energy") instanceof Number n) {
            pool = n.intValue();
        } else if (beast && !spirit) {
            pool = 0;                                        // 이빨과 발톱뿐 — 내력이 없다
        } else {
            pool = internal.pool(naegongFloor.getOrDefault(realm, 0.0));
        }

        // 격 — 개체가 선언했으면 그것, 아니면 경지 게이트가 허락하는 가장 높은 격 (사람도 짐승도 같은 사다리)
        String grade;
        if (e.get("qi_grade") != null) {
            grade = String.valueOf(e.get("qi_grade"));
        } else if (pool <= 0) {
            grade = BARE;
        } else {
            List<String> armable = armableGrades(realm);
            grade = armable.isEmpty() ? BARE : armable.get(armable.size() - 1);
        }
        if (!BARE.equals(grade) && !internal.canUse(realm, grade)) {
            grade = BARE;   // 등록값이라도 경지 게이트를 못 넘으면 못 쓴다 (게이트에 예외는 없다)
        }
        // 능력치 시트 — 등록된 것만 받는다 (미등록 축은 표준 무인으로 폴백한다. 코드가 수치를 짓지 않는다)
        Map<String, Integer> stats = new LinkedHashMap<>();
        if (e.get("stats") instanceof Map<?, ?> raw) {
            ((Map<String, Object>) raw).forEach((k, v) -> {
                if (v instanceof Number n2) {
                    stats.put(String.valueOf(k), n2.intValue());
                }
            });
        }
        // 병기 계열 — 공격 능력치를 정한다 (combat.yml attacker_attribute: 도=근력·검=민첩)
        String weaponClass = "맨손";
        if (e.get("loadout") instanceof Map<?, ?> lo && ((Map<String, Object>) lo).get("계열") != null) {
            weaponClass = String.valueOf(((Map<String, Object>) lo).get("계열"));
        } else if (beast) {
            weaponClass = "맨손";   // 이빨과 발톱 — 병장기가 없다
        }
        return new Npc(id, String.valueOf(e.getOrDefault("name", id)),
                beast ? "짐승" : "사람", rank, realm, pool, grade,
                Collections.unmodifiableMap(stats), weaponClass);
    }

    /** 전승 오의 한 줄 → 시전 가능한 한 수 (ultimate_arts.yml legacy_arts) */
    @SuppressWarnings("unchecked")
    private Ultimate parseUltimate(String id, Map<String, Object> a) {
        Map<String, Object> type = a.get("type") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        String hitbox = type.get("form") != null ? "반격"
                : String.valueOf(type.getOrDefault("hitbox", "원"));
        double range = type.get("range") instanceof Number r ? r.doubleValue()
                : type.get("length") instanceof Number l ? l.doubleValue() : 6.0;
        double width = type.get("width") instanceof Number w ? w.doubleValue() : 2.0;
        int multiHit = type.get("multi_hit") instanceof Number h ? h.intValue() : 1;
        int window = type.get("window_ticks") instanceof Number t ? t.intValue() : 0;

        String defense = String.valueOf(a.getOrDefault("defense", ""));
        java.util.regex.Matcher iframe = IFRAME.matcher(defense);
        int iframeTicks = iframe.find()
                ? Math.min(ultimateIframeCap, Integer.parseInt(iframe.group(1))) : 0;

        java.util.regex.Matcher cost = PERCENT.matcher(String.valueOf(a.getOrDefault("cost", "내력 50%")));
        double ratio = cost.find() ? Integer.parseInt(cost.group(1)) / 100.0 : 0.5;

        return new Ultimate(id, String.valueOf(a.getOrDefault("name", id)),
                String.valueOf(a.getOrDefault("faction", "")), hitbox, range, width, multiHit, window,
                frames(a.get("frames")), iframeTicks, defense.contains("슈퍼아머"), ratio,
                String.valueOf(a.getOrDefault("effect", "")),
                Boolean.TRUE.equals(a.get("bloodline_restricted")), a.get("demonic") != null);
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
     * @param gradeBonus 격 위력 (combat.yml damage.qi_power — 형태 위력이 있으면 그쪽이 우선)
     * @param ultimate   오의 (없으면 null) — 격과는 다른 사다리다
     * @param halved     개안(초절정)의 불완전 시전 — 위력 절반
     */
    public record Cast(
            String skillId, String skillName,
            String grade, int cost, int paid, boolean downcast, boolean gated,
            Frames frames, String stagger, int staggerTicks,
            String hitType, double range, double angle,
            int weaponPower, int techniquePower, int gradeBonus,
            int maxTargets, int cooldownTicks,
            Ultimate ultimate, boolean halved) {

        public boolean manifested() {
            return !BARE.equals(grade);
        }
    }

    /**
     * 격을 쓰는 NPC — 등록부(npcs/cheongha_npcs.yml)가 정본. 대칭 원칙의 자리.
     *
     * @param grade 이 몸이 두르는 격 (BARE = 외공만 — 갈호·졸개·들짐승·맹수)
     * @param pool  내력 총량 (0 = 격을 못 쓴다)
     */
    public record Npc(String id, String name, String kind, String beastRank, String realm,
                      int pool, String grade, Map<String, Integer> stats, String weaponClass) {

        public boolean manifests() {
            return !BARE.equals(grade) && pool > 0;
        }

        /** 등록된 능력치 (npcs/*.yml stats) — 없으면 fallback (경지의 표준 무인) */
        public int attr(String name, int fallback) {
            Integer v = stats.get(name);
            return v == null ? fallback : v;
        }

        public boolean isBeast() {
            return "짐승".equals(kind);
        }
    }

    /** 전승 오의 — ultimate_arts.yml legacy_arts. 격이 아니라 '도달'이다 */
    public record Ultimate(String id, String name, String faction,
                           String hitType, double range, double width, int multiHit, int counterWindow,
                           Frames frames, int iframeTicks, boolean superArmor,
                           double costRatio, String effect, boolean bloodline, boolean demonic) {

        public boolean isCounter() {
            return "반격".equals(hitType);
        }
    }

    /** 호신강기가 한 대의 타격을 맞았을 때 (qi_manifestation forms.두름_몸.호신강기.on_hit) */
    public record Guard(boolean blocked, int pierce, int drain) {
    }

    /** 한 대상에 대한 판정 결과 — 전투는 주사위를 쓴다 (조성기와 달리 난수 허용) */
    public record Strike(int roll, int margin, String tierId, String tierName, boolean hit, int damage) {
    }

    // ══════════ 모션 등록부 값 타입 (skill_motion.yml) ══════════
    // 【등록제 규약】 여기 담기는 것은 전부 문자열·수치다. Bukkit 의 Particle·Sound 로 바꾸는 것은
    // SkillListener 의 몫이다 (이 클래스의 Bukkit 의존 0 불변식을 지킨다).

    /** 파티클 한 발 — 팔레트 이름(smoke·crit·end_rod…) · 개수 · 퍼짐 · 속도 */
    public record Fx(String particle, int count, double spread, double extra) {
        public boolean present() {
            return particle != null && count > 0;
        }
    }

    /** 소리 한 발 — 바닐라 사운드 키("block.anvil.land"). 1.21 의 Sound 는 열거형이 아니다 (문자열 재생) */
    public record Sfx(String key, float volume, float pitch) {
    }

    /** 파티클 예산 — 한 지점·한 틱 상한과 세 개의 풀(응집·궤적·타격) */
    public record Budget(int perPointTickMax, int perCastMax, int telegraphPool, int trailPool,
                         int impactPool, int minImpactPerTarget, int ultimateTickMax,
                         int telegraphStepTicks, int trailMaxPoints, int ultimateRingPoints) {
    }

    /** 격의 모션 — 사다리의 한 칸. rank 가 오르면 charge·impact·aura 가 반드시 커진다 (motion_audit ③) */
    public record GradeMotion(String grade, int rank, int brightness, String color,
                              Fx charge, Fx aura, Fx impact, Fx accent,
                              String trailParticle, int trailPerPoint,
                              List<Sfx> chargeSounds, List<Sfx> armSounds, List<Sfx> impactSounds) {
    }

    /** 형태의 모션 — 두름(잔광) · 쏨(발출: 응집→광선→작렬) · 두름_몸(호신강기 고리) · 부림(어검) */
    public record FormMotion(String name, String kind, String grade, String trail,
                             double length, double width, Frames frames, int cooldownTicks,
                             Fx charge, String beamParticle, int beamPerPoint, Fx burst, Fx aura,
                             String ringParticle, int ringPoints, double ringRadius,
                             int ringPerPoint, double ringHeight,
                             List<Sfx> chargeSounds, List<Sfx> releaseSounds, List<Sfx> deploySounds) {
    }

    /**
     * 초식 한 칸의 모션 — trail 은 skill_mechanics 의 히트박스 type 과 같아야 한다 (motion_audit ②).
     *
     * @param soundScale <b>이 초식이 내는 모든 소리의 음량 배율</b> — 스윙음(초식의 {@code sound} 또는
     *                   계열의 {@code swing})·<b>응집음</b>·<b>타격음</b>({@code grades[].sounds.impact})에
     *                   함께 걸린다. 타격음은 <b>격</b>의 것이라 초식이 제 칸에서 줄일 수단이 없었다 —
     *                   이 칸이 그 자리다 (무성무색이 <b>전 무공 유일의 무음 초식</b>이라는 정체성은
     *                   이제 등록부만으로 완결된다).
     *                   <b>[0, 1] 로 물린다 — 등록부는 소리를 줄일 수만 있다</b> ({@code reach ≤ 1.0} 과 같은
     *                   문법: 키우는 것은 초식의 {@code sound.volume} 이 한다. 그래야 한 줄이 귀를 못 터뜨린다).
     *                   기본 1.0. <b>0 이면 아예 발행하지 않는다</b> — 다만 <b>타격 파티클은 그대로다</b>:
     *                   맞았다는 사실까지 지우면 그것은 화면이 판정에 대해 거짓말하는 것이다
     */
    public record Step(String trail, String particle, int count, boolean finisher,
                       int telegraphBoost, Sfx sound, float soundScale) {
    }

    public record SkillMotion(String id, String name, String style, List<Step> steps) {
        public Step step(int index) {
            return steps.isEmpty() ? null : steps.get(Math.floorMod(index, steps.size()));
        }
    }

    /** 오의의 모션 — 응집 고리(다른 어떤 모션도 고리를 쓰지 않는다) · 개시 섬광 · 작렬 */
    public record UltimateMotion(String id, String trail, String chargeParticle, int ringPoints,
                                 int ringPerPoint, String coreParticle, int coreCount,
                                 Fx burst, Fx accent, double burstSpreadRatio,
                                 List<Sfx> chargeSounds, List<Sfx> releaseSounds, List<Sfx> hitSounds) {
    }

    /** 사건의 모션 — 무기 균열·파괴·절단 · 다운캐스트 · 호신강기 무효/관통/붕괴 · 패링 … */
    public record EventMotion(String name, Fx fx, List<Sfx> sounds) {
    }

    /** 궤적 — 히트박스 type 과 1:1 (호=arc · 선=line · 원=circle · 시=shot · 돌=dash · 진=ring) */
    public record Traj(String type, String shape, int points, String phase,
                       double radiusRatio, double step, String accentAt) {
    }

    /** 무기 계열의 모션 — 검은 벤다(sweep_attack), 창은 찌른다(enchanted_hit) */
    public record Style(String weaponClass, String verb, String arc, String thrust,
                        String trail, Sfx swing) {
    }

    /** 플레이어 무공 런타임 상태 — 순수 데이터 (엔진은 이걸 읽고 쓰지 않는다; 리스너가 소유) */
    public static final class State {
        /**
         * 경지 — <b>봇의 시트에서 온다</b> ({@code world_state.json} 의 {@code sheet.<uuid>.realm}).
         *
         * <p>여기 {@code "이류"} 가 <b>박혀 있었다</b>. 주석은 <i>"MVT 는 캐릭터 시트가 없다"</i> 였고,
         * 그 한 줄 때문에 <b>모든 플레이어가 영원히 이류</b>였다 — 갓 접속한 자도, 봇에서 일류까지
         * 올라간 자도. 승급은 강호가 인정하는 것이고 강호의 장부는 봇에 있다.
         *
         * <p>초기값은 없다(null). {@link SkillListener#state(org.bukkit.entity.Player)} 가 몸에 실린
         * 원장에서 받아 세우고, 접합 전이면 등록부의 첫 단(cultivation.yml {@code cultivation_stages[0]}
         * = 범인)으로 선다. <b>코드가 경지를 지어내지 않는다.</b>
         */
        public String realm;
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
        /** 이 합이 시작될 때의 내력 — 줄어든 만큼에서 두름 유지비를 뺀 나머지가 '태운 것'이다 */
        public int energyAtRoundStart = -1;
        /**
         * 이 합에 낸 <b>두름 유지비</b> — 태운 것이 아니다 (조식을 막지 않는다).
         * 서리게 한 것과 태운 것을 가르는 장부. 조식이 정산될 때 0 으로 돌아간다.
         */
        public int upkeepThisRound;
        /** 다음 조식 정산 틱 — 한 라운드에 한 번만 돈다 */
        public long nextRegenTick = -1;
        public final Map<String, Long> cooldownUntil = new HashMap<>();
        /** 자기 무기가 자기 격을 못 견딜 때의 시전 카운터 (n회마다 손상 1) */
        public int selfStrainCount;

        // ─── 오의 (ultimate_arts) — 격과 독립된 사다리 ───
        /** 지금 몸에 실린 전승 오의 (Shift+F 로 고른다). null = 아직 아무것도 얻지 못했다 */
        public String ultimateId;
        /** 발동권 — '흐름'. 아슬아슬한 성공 이상 공방 누적. 전투가 끝나면 흩어진다 */
        public int flow;
        /** 이번 전투의 오의 시전 횟수 (화경 이하 1회) */
        public int ultimateUses;
        /** 전투 창 — 이 틱까지 아무 공방도 없으면 전투가 끝난 것 (흐름·횟수 초기화) */
        public long combatUntil = -1;
        /** 반격 오의(태극혜검)의 창 — 이 틱까지 들어온 공격 1회를 무효화하고 반사한다 */
        public long counterUntil = -1;
        /** 오의 무적 — 이 틱까지 (skill_mechanics iframe_caps.오의 = 20틱 절대 상한) */
        public long invulnerableUntil = -1;

        // ─── NPC 전용 (대칭 원칙 — 같은 State 를 쓴다) ───
        /** 격이 실린 창 — 응집(선딜)이 끝나고 이 틱까지의 타격에 격이 실린다 (발경: 두름이 없는 격) */
        public long qiHotUntil = -1;
        /** 다음 응집 시도 틱 */
        public long nextCastTick = -1;

        public boolean onCooldown(String key, long now) {
            return cooldownUntil.getOrDefault(key, -1L) > now;
        }

        public int cooldownLeft(String key, long now) {
            return (int) Math.max(0, cooldownUntil.getOrDefault(key, -1L) - now);
        }
    }

    // ══════════ 내력 · 경지 게이트 ══════════

    /** 내력 풀 — internal_energy.yml pool_curve · pool_per_year (축기 세월에 비례) */
    public int pool(double naegong) {
        return internal.pool(naegong);
    }

    /** 풀 → 내공 (역함수) — 등록부가 내력을 직접 적은 몸(npcs/*.yml)의 내공을 되찾는다 (조식이 읽는다) */
    public double naegongOf(int pool) {
        return internal.naegongOf(pool);
    }

    /** 경지의 표준 내공 — cultivation.yml 승급 요건 '내공 N' + realm_naegong_floor (보충 등록) */
    public double naegongFloor(String realm) {
        return naegongFloor.getOrDefault(realm, 0.0);
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

    /**
     * 태세 순환 목록 — 격(두름) + 호신강기(두름_몸). 몸에 두르는 것은 격이 아니라 형태다.
     * 강기를 여는 경지(화경)는 검강(공격)과 호신강기(방어) 중 하나를 고른다 — 둘은 다른 형태다.
     */
    public List<String> armableStances(String realm) {
        List<String> out = new ArrayList<>(armableGrades(realm));
        if (internal.canUse(realm, guardGrade)) {
            out.add(GUARD);
        }
        return out;
    }

    /** 태세 순환 — 외공기(null) → 발경 → 검기 → 검강 → 호신강기 → 외공기. 경지가 못 여는 것은 건너뛴다 */
    public String cycleArmed(String realm, String current) {
        List<String> armable = armableStances(realm);
        if (armable.isEmpty()) {
            return null;   // 개화 전 — 몸과 무기가 전부다
        }
        if (current == null) {
            return armable.get(0);
        }
        int idx = armable.indexOf(current);
        return idx < 0 || idx + 1 >= armable.size() ? null : armable.get(idx + 1);
    }

    /** 격 rank — 호신강기는 격이 아니라 형태지만, 상성 비교에는 그 방어 격(강기)의 rank 로 선다 */
    public int gradeRank(String grade) {
        if (grade == null || BARE.equals(grade)) {
            return 0;
        }
        return qi.gradeRank(GUARD.equals(grade) ? guardGrade : grade);
    }

    /** 격 위력 — 【엔진 정본】 combat.yml damage.qi_power. 피해 공식의 '격 위력' 항이다 */
    public int qiPower(String grade) {
        if (grade == null || BARE.equals(grade)) {
            return 0;
        }
        return qiPower.getOrDefault(GUARD.equals(grade) ? guardGrade : grade, gradeRank(grade));
    }

    /** 형태 위력 — 발출형은 1회에 몰아 쓰므로 더 아프다 (검기_참격 3 > 검기 두름 2) */
    public int formPower(String form) {
        return formPower.getOrDefault(form, 0);
    }

    /** 소모 밴드의 코스트 — internal_energy.yml cost_bands (발경 1). 범위형은 0 (형태가 정한다) */
    public int bandCost(String band) {
        return bandCost.getOrDefault(band, 0);
    }

    public String gradeGate(String grade) {
        return qi.gradeGate(GUARD.equals(grade) ? guardGrade : grade);
    }

    /** 원칙 1 — 한 격 위는 아래 격의 기 방어를 관통한다 */
    public boolean piercesGuard(String attack, String defense) {
        return gradeRank(attack) > gradeRank(defense);
    }

    // ─── 호신강기 (두름_몸) — 절대 방어는 없다 ───

    public String guardGrade() {
        return guardGrade;
    }

    /** 전개비 — 호신강기 4 (qi_manifestation forms.두름_몸.호신강기.deploy) */
    public int deployCost(String stance) {
        return GUARD.equals(stance) ? guardDeploy : sustainCost(stance);
    }

    /**
     * 원칙 4 — 기 방어는 소모품이다. 하위 격이라도 때린 만큼 상대의 내력을 깎는다.
     *
     * @return blocked = 피해 0 (하위·동격) / pierce = 관통 피해 (상위 격 — 격 위력 차) · drain = 상쇄 소모
     */
    public Guard guard(String attackGrade, String stance) {
        if (!GUARD.equals(stance)) {
            return new Guard(false, 0, 0);   // 기 방어가 없다 — 그냥 맞는다
        }
        int attack = gradeRank(attackGrade);
        int defense = gradeRank(guardGrade);
        if (attack < defense) {
            return new Guard(true, 0, drainLower);      // 무효 — 그러나 두들기면 깎인다
        }
        if (attack == defense) {
            return new Guard(true, 0, drainEqual);      // 강기 대 강기 — 먼저 마르는 쪽이 진다
        }
        return new Guard(false, Math.max(1, qiPower(attackGrade) - qiPower(guardGrade)), drainHigher);
    }

    /** 두름 유지비 — 검기_두름 1, 검강_두름·호신강기 2 (라운드당). 없는 격은 0 */
    public int sustainCost(String grade) {
        if (GUARD.equals(grade)) {
            return guardSustain;
        }
        String form = switch (grade == null ? "" : grade) {
            case "검기" -> "검기_두름";
            case "강기" -> "검강_두름";
            default -> null;
        };
        return form == null ? 0 : qi.sustainPerRound(form);
    }

    public int roundTicks() {
        return roundTicks;
    }

    /** 전투 창 — 이만큼 공방이 없으면 '그 전투'는 끝난 것 (오의 횟수·흐름 초기화) */
    public int combatWindowTicks() {
        return combatWindowTicks;
    }

    /**
     * 운기조식 1구간 회복 — max(ceil(풀 × pool_fraction × 순도), floor).
     * 전부 config (recovery.meditation_pool_fraction · meditation_floor).
     *
     * <p>구판은 {@code 내공 × 순도} 였다. 풀이 축기 세월(배증형)을 따르게 된 뒤로는 그 선형 회복이
     * <b>화경의 단전을 채우는 데 보름</b>을 요구했다. 한 구간 = 풀의 1/4 로 옮긴다:
     * <b>하루를 앉으면 누구든 만충이다</b> (하루 5구간 중 4구간 운기). 경지가 올라도 '앉아야 하는
     * 날 수'는 늘지 않는다 — 늘어나는 것은 한 번 앉아 얻는 양이다.
     */
    public int meditationRecover(double naegong, double purity) {
        int byPool = (int) Math.ceil(pool(naegong) * meditationFraction * purity);
        return Math.max(byPool, meditationFloor);
    }

    // ══════════ 조식(調息) — 전투 중의 숨 (internal_energy.yml recovery.in_combat.조식) ══════════

    /**
     * 라운드당 회복량. <b>운기조식(앉는 것)이 아니다</b> — 격을 싣지 않은 합의 호흡이다.
     *
     * <p>이것이 없던 시절, 개화 직후(내공 0.33 → 내력 풀 1)의 발경은 <b>전투당 한 번</b>이었다.
     * 전투는 5~9합인데 '개화의 보상'을 첫 합에 다 쓰고 나머지를 빈손으로 쳤다 — 형벌이었다.
     * 그리고 규칙이 비대칭이었다: NPC 만 라운드당 1씩 회복하고 있었다(하드코딩) —
     * npc_combat.yml symmetry 가 거짓이었다. 이제 둘 다 config 를 읽는다.
     */
    public int combatRegen(double naegong) {
        return Math.max((int) Math.floor(naegong * regenPerNaegong), regenFloor);
    }

    /** 【조건】 그 합에 내력을 <b>태우지</b> 않았을 때만 돈다 — 없으면 발경(코스트 1)은 공짜가 된다 */
    public boolean regenOnlyIfUnspent() {
        return regenOnlyIfUnspent;
    }

    /**
     * 【구분 ①】 <b>두름의 유지비는 '태운 것'이 아니다.</b> 날에 기를 서리게 한 채로도 숨은 쉰다.
     *
     * <p>이 한 줄이 '넘침'을 만든다: 조식(내공 × 1) − 두름 유지비 = 순수지.
     * 절정(3 − 1 = +2)부터 검기가 하루 종일 간다. 일류는 두를 격이 없다(발경은 두름이 아니다) —
     * 태울 때마다 숨이 막힌다. <b>성장의 체감은 그대로 남는다.</b>
     */
    public boolean regenUpkeepExempt() {
        return regenUpkeepExempt;
    }

    /**
     * 【구분 ②】 <b>호신강기를 두른 자는 숨을 못 고른다.</b> 몸을 통째로 기로 감싼 것이기 때문이다.
     *
     * <p>이 못이 없으면 넘치는 내력이 곧 <b>무한 방어</b>가 되고, 방어 삼문(회피·막기·흘리기)이 죽는다.
     * 격을 <b>두르는 것</b>(병기 — 숨이 돈다)과 격으로 <b>막는 것</b>(몸 — 숨이 멎는다)은 다른 일이다.
     */
    public boolean regenBlockedByGuard() {
        return regenBlockedByGuard;
    }

    // ══════════ 포위 — 다구리의 규칙 (combat.yml attack) ══════════

    /**
     * 포위 슬롯 — 한 표적을 <b>동시에</b> 칠 수 있는 손의 수. 그 밖은 대기(포위)다.
     * 다구리에도 몸이 들어갈 자리가 필요하다 — 머릿수는 '교대'가 되지 '동시타'가 되지 않는다.
     */
    public int engageSlots() {
        return engageSlots;
    }

    /** 협공 보정의 캡 — combat.yml attack.gang_up.max (화면에 찍는 숫자도 여기서 나와야 한다) */
    public int gangUpCap() {
        return gangCap;
    }

    /** 협공 보정 — 추가 공격자당 +1 (캡은 등록부가 정한다) */
    public int gangUpBonus(int attackers) {
        return Math.min(Math.max(0, attackers - 1) * gangPer, gangCap);
    }

    /** 피포위 방어 이점 — 사방에서 오는 것은 서로의 검로를 막는다 (추가 적당 +1, 캡 2) */
    public int outnumberedDefense(int attackers) {
        return Math.min(Math.max(0, attackers - 1) * outnumberedPer, outnumberedCap);
    }

    /**
     * 공격 측 순보정 = 협공 − 피포위 방어. 같은 눈금이므로 <b>0</b> 이다 (의도된 것 — combat.yml 정본).
     * 보정을 남겨두면 명중률 절벽 위에서 곱셈으로 터진다 (이류 4인의 절정 상대 명중 2.8% → 16.7%).
     */
    public int gangNetModifier(int attackers) {
        return gangUpBonus(attackers) - outnumberedDefense(attackers);
    }

    /**
     * 포위된 자의 강제 태세 경감 — 회피(몸을 빼는 것)를 잃고 받아넘긴다(흘리기 −1).
     *
     * <p>경감은 <b>협공 1인분보다 반드시 작아야 한다</b>. 막기(−3)로 잡으면 규칙이 뒤집힌다:
     * 둘이 덤비는 것이 하나보다 덜 아파진다 (총 피해 3.28 → 3.06).
     */
    public int forcedGuardSoak(int attackers) {
        return Math.max(0, attackers - 1) >= forcedGuardFrom ? forcedGuardSoak : 0;
    }

    /** 둘 이상에게 잡혔는가 — 회피(몸을 빼는 것)를 잃는 순간 (forced_guard.trigger_extra_attackers) */
    public boolean surrounded(int attackers) {
        return Math.max(0, attackers - 1) >= forcedGuardFrom;
    }

    // ══════════ 방어 태세 — 【맞는 쪽의 선택】 (combat.yml attack.defender_stance_mc) ══════════

    /**
     * 태세 연출 — <b>화면이 판정에 대해 거짓말하면 안 된다</b>.
     * 파티클·소리·글자 셋 다 바닐라다 — <b>팩이 없어도 보인다</b>.
     */
    public record StanceFx(String name, String particle, int count, double spread,
                           String sound, float pitch, String label) {

        public boolean hasParticle() {
            return particle != null && count > 0;
        }
    }

    /**
     * 이 몸짓이 말하는 태세 — {@code isBlocking}(손을 세운다) → 막기 ·
     * {@code isSneaking}(몸을 낮춘다) → 흘리기 · {@code isSprinting}(발이 움직인다) → 회피.
     *
     * <p><b>새 입력 채널을 열지 않았다.</b> 바닐라 클라이언트가 이미 가진 세 자세를 읽을 뿐이다 —
     * 그래서 <b>남의 눈에도 보인다</b> (보이는 것 = 맞는 것: 상대는 내 자세를 보고 다음 수를 고른다).
     */
    public String stanceOfGesture(String bukkitPredicate) {
        return stanceGestures.get(bukkitPredicate);
    }

    /** 아무것도 지정하지 않은 몸의 태세 — 기본은 '자동' (몸이 아는 대로) */
    public String stanceDefault() {
        return stanceDefault;
    }

    public StanceFx stanceFx(String name) {
        return stanceFx.get(name);
    }

    // ══════════ 표준 무인의 축 — NPC 능력치 폴백 (combat_audit realm_axis 와 같은 셈) ══════════

    /** 그 경지의 표준 능력치 = 상한 −1 (상한을 찍은 자는 표준이 아니다) */
    public int realmAttr(String realm) {
        return Math.max(1, attrCapByRealm.getOrDefault(realm, 3) - 1);
    }

    /** 그 경지의 표준 숙련 — 승급 요건의 '주력 무공 숙련 N' */
    public int realmSkill(String realm) {
        return realmSkill.getOrDefault(realm, 0);
    }

    /** 무공 위력 — combat.yml damage.technique_power (삼류급 1 · 절정급 2 …). 경지로 찾는다 */
    public int techniquePower(String realm) {
        return techniquePower.getOrDefault(realm + "급", 0);
    }

    // ══════════ 갑옷 — "갑옷은 회피를 판다" 의 반대급부 (equipment.yml armor) ══════════

    /** 갑옷의 경감 — 무복 0 · 피갑 1 · 철갑 2 · 내갑 1 */
    public int armorMitigation(String armor) {
        try {
            return equipment.armorMitigation(armor);
        } catch (IllegalArgumentException e) {
            return 0;   // 등록되지 않은 갑옷 — 코드가 수치를 지어내지 않는다
        }
    }

    /** 갑옷이 파는 것 — 회피 판정 (무복 0 · 피갑 −1 · 철갑 −2) */
    public int armorDodgePenalty(String armor) {
        try {
            return equipment.armorDodgePenalty(armor);
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    /**
     * 이 격 앞에서 갑옷이 종이가 되는가 — {@code equipment.yml armor.mitigation_pierced_from} (강기).
     * <b>"격 상성은 못 이긴다 — 검강 앞 피갑은 종이"</b> 가 이미 그어 둔 선이다.
     *
     * <p>★ 이것이 갑옷의 지배 전략을 스스로 막는다: 갑옷은 <b>졸개에게 강하고 고수에게 무력하다</b>.
     */
    public boolean armorPierced(String grade) {
        return grade != null && !BARE.equals(grade)
                && gradeRank(grade) >= gradeRank(armorPiercedFrom);
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

    /** 콤보 칸 수 — 단발형은 <b>1</b> 이다 (0 이면 나누기에서 터진다) */
    public int comboSize(String skillId) {
        Object combo = mechOf(skillId).get("combo");
        return combo instanceof List<?> l ? l.size() : 1;
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
        Map<String, Object> mech = mechOf(skillId);
        // 단발형(철산고·비홍침·매화참…)은 combo 가 없다 — 그 몸 전체가 한 칸이다.
        //   (하드코딩 무공표를 지우고 원장이 무공을 고르게 된 순간부터, 단발형이 이 문을 통과한다)
        List<Object> combo = mech.get("combo") instanceof List<?> l
                ? (List<Object>) l : List.of((Object) mech);
        int step = Math.floorMod(index, combo.size());
        Map<String, Object> hit = (Map<String, Object>) combo.get(step);

        // 【정본】 초식 자체는 내력을 쓰지 않는다 (cost 0). 내력을 먹는 것은 '격'이다:
        //   ① 격 라이더 — 그 초식의 특정 타에 격이 실려 태어난다 (skill_mechanics qi_rider:
        //      "개화한 자는 3타에 발경(내력 1)을 얹는다"). 경지가 못 열면 finish() 가 강등한다.
        //   ② 두름 — 두른 격이 라이더보다 높으면 타격의 격을 끌어올린다.
        //      두름(검기·강기)은 라운드 유지비로 이미 값을 냈으므로 타격마다는 0,
        //      발경은 두름이 없는 격이라 타격마다 밴드 코스트(1)를 낸다.
        int cost = hit.get("cost") instanceof Number n ? n.intValue() : 0;
        String want = cost > 0 ? "발경" : BARE;               // (하위호환) 초식이 코스트를 직접 적은 경우
        Object rider = mech.get("qi_rider");
        if (rider instanceof Map<?, ?> r) {
            Map<String, Object> qr = (Map<String, Object>) r;
            int riderStep = qr.get("step") instanceof Number s ? s.intValue() : 0;
            if (riderStep == step + 1) {
                want = String.valueOf(qr.getOrDefault("격", "발경"));
                cost = qr.get("cost") instanceof Number c ? c.intValue() : bandCost(want);
            }
        }
        if (armed != null && gradeRank(armed) > gradeRank(want)) {
            want = armed;                                     // 두름이 타격의 격을 끌어올린다
            cost = sustainCost(armed) > 0 ? 0 : bandCost(armed);
        }
        // 【고침】 쿨다운을 읽지 않던 자리 — Cast.cooldownTicks() 가 늘 0 이었다.
        //   태조장권 60 · 매화검법 90 · 흑살도법 180 이 등록부에만 있고 **한 번도 적용된 적이 없다**.
        //   쿨다운은 콤보의 **마무리 타 전용**이다 (등록부 주석: "1·2타는 쿨다운 없음 — 연타가 권법의 값").
        int cooldown = step + 1 == combo.size() && mech.get("cooldown_ticks") instanceof Number c
                ? c.intValue() : 0;
        return finish(skillId, want, cost, realm, energy, weaponClass,
                frames(hit.get("frames")),
                String.valueOf(hit.getOrDefault("stagger", "약")),
                String.valueOf(hit.getOrDefault("type", "호")),
                hit.get("range") instanceof Number r ? r.doubleValue() : 3.0,
                hit.get("angle") instanceof Number a ? a.doubleValue() : 100.0,
                cooldown);
    }

    /** 단발형 무공의 쿨다운 (콤보가 아닌 것 — 철산고 120 · 엽호궁술 30). 없으면 0 */
    public int skillCooldown(String skillId) {
        return hasActionData(skillId) && mechOf(skillId).get("cooldown_ticks") instanceof Number c
                ? c.intValue() : 0;
    }

    /**
     * 기 발출(쏨) 계획 — 검기_참격(3) / 강기_포(6). 빗나가면 소멸하는 도박.
     * 형태 코스트는 qi_manifestation.yml forms.쏨 이 정한다.
     */
    public Cast planShot(String grade, String realm, int energy, String weaponClass) {
        if (gradeRank(grade) < 2) {
            throw new IllegalArgumentException("발출할 수 없는 격: " + grade);   // 발경은 근접 유일
        }
        // 발출은 '무공'이 아니라 기의 운용 — 코스트는 강등된 격 기준으로 다시 읽는다 (COST_BY_GRADE).
        // 프레임·사거리·폭·쿨다운은 이제 모션 등록부가 정본이다 (skill_motion.yml forms.쏨).
        FormMotion form = shotForm(grade);
        return finish(SHOT, grade, -1, realm, energy, weaponClass,
                form == null ? DEFAULT_SHOT_FRAMES : form.frames(), "중",
                form == null ? "선" : form.trail(),
                form == null ? DEFAULT_SHOT_LENGTH : form.length(),
                form == null ? DEFAULT_SHOT_WIDTH : form.width(),
                form == null ? DEFAULT_SHOT_COOLDOWN : form.cooldownTicks());
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
        boolean shot = SHOT.equals(skillId);
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
        // 격 위력 — combat.yml qi_power (엔진 정본). 발출형은 형태 위력이 우선한다 (검기_참격 3 · 강기_포 5)
        int gradeBonus = shot ? shotPower(grade) : qiPower(grade);
        return new Cast(skillId, shot ? "기 발출" : skillName(skillId),
                grade, effCost, paid, downcast, gated,
                frames, stagger, staggerTicks(stagger), hitType, range, angle,
                weaponPower(weaponClass), shot ? 0 : techniquePowerOf(skillId),
                gradeBonus, maxTargets, cooldown, null, false);
    }

    /** 발출(쏨)의 위력 — qi_manifestation forms.쏨 power (검기_참격 3, 강기_포 5) */
    private int shotPower(String grade) {
        return switch (grade) {
            case "검기" -> formPower("검기_참격");
            case "강기" -> formPower("강기_포");
            default -> qiPower(grade);
        };
    }

    // ══════════ NPC — 대칭 원칙 (npc_combat.yml symmetry) ══════════

    /** 무공 카탈로그 — id → 이름 (연무장의 무공대가 목록을 그린다) */
    public Map<String, String> artNames() {
        Map<String, String> out = new LinkedHashMap<>();
        catalog.forEach((id, raw) -> {
            if (raw instanceof Map<?, ?> m) {
                Object name = m.get("name");
                out.put(id, name == null ? id : String.valueOf(name));
            }
        });
        return out;
    }

    /** 경지의 내력 풀 — 그 경지의 내공 하한으로 세운다 (연무장이 경지를 바꿀 때 쓴다) */
    public int poolOf(String realm) {
        return internal.pool(naegongFloor.getOrDefault(realm, 0.0));
    }

    /** 등록부의 그 몸 (npcs/cheongha_npcs.yml) — 없으면 null (전투에 서지 않는 NPC) */
    public Npc npc(String id) {
        return npcs.get(id);
    }

    public List<String> manifestingNpcs() {
        List<String> out = new ArrayList<>();
        npcs.forEach((id, npc) -> {
            if (npc.manifests()) {
                out.add(id);
            }
        });
        return out;
    }

    /** NPC 사고 주기 — 매 틱 사고 금지 (npc_combat.yml think_interval_ticks · performance npc_logic 예산) */
    public int npcThinkTicks() {
        return npcThinkTicks;
    }

    /**
     * NPC 타격 1회의 내력 — 발경은 타격마다 1(밴드 코스트), 두름(검기·강기)은 유지비로 이미 냈다.
     * 못 내면 다운캐스트한다 — 규칙이 대칭이어야 세계가 정직하다 (npc_combat.yml symmetry).
     */
    public int npcStrikeCost(String grade) {
        return sustainCost(grade) > 0 ? 0 : bandCost(grade);
    }

    /** 무공의 한 칸(프레임) — NPC 응집(선딜)의 길이를 이 데이터에서 빌린다 (수치를 지어내지 않는다) */
    @SuppressWarnings("unchecked")
    public Frames comboFrames(String skillId, int index) {
        List<Object> combo = (List<Object>) mechOf(skillId).get("combo");
        Map<String, Object> hit = (Map<String, Object>) combo.get(Math.floorMod(index, combo.size()));
        return frames(hit.get("frames"));
    }

    // ══════════ 오의 (ultimate_arts.yml) — 격이 아니다 ══════════

    public List<String> ultimateIds() {
        return List.copyOf(ultimates.keySet());
    }

    public Ultimate ultimate(String id) {
        return id == null ? null : ultimates.get(id);
    }

    /** 오의의 단계 — 초절정 개안 / 화경 완성 / 현경 자재 / 생사경 창작. 그 아래는 null (느끼지도 못한다) */
    public String ultimateStage(String realm) {
        return internal.canUse(realm, "오의") || internal.canUse(realm, "오의_개안")
                ? ultimateStage.get(realm) : null;
    }

    /** 개안 — 전승 오의의 불완전 시전 (위력 절반, 직후 내력 전소 + 내상). 초절정이 벽 너머를 훔쳐보는 대가 */
    public boolean isAwakening(String realm) {
        return internal.canUse(realm, "오의_개안") && !internal.canUse(realm, "오의");
    }

    /** 전투당 시전 상한 — 화경 이하 1회, 현경(자재)부터 해제 */
    public int ultimateLimit(String realm) {
        return realmIndex(realm) >= realmIndex(freeLimitRealm) ? Integer.MAX_VALUE : 1;
    }

    /** 발동권 — '흐름'. 아슬아슬한 성공 이상 공방 n회 누적 (오의는 버튼이 아니라 읽어낸 순간이다) */
    public int flowRequired() {
        return ultimateFlow;
    }

    /** 이 판정 등급이 흐름을 쌓는가 — 아슬아슬한 성공 이상 */
    public boolean isFlowTier(String tierId) {
        return "narrow_success".equals(tierId) || "success".equals(tierId)
                || "critical_success".equals(tierId);
    }

    /** 오의 코스트 — 내력 총량의 50% 이상 (오의별 명시). 시전 후 그 전투의 여력이 반토막난다 */
    public int ultimateCost(Ultimate art, int pool) {
        return Math.max(1, (int) Math.ceil(pool * art.costRatio()));
    }

    public int ultimateBasePower() {
        return ultimateBasePower;
    }

    /**
     * 오의 시전 계획. 격 사다리와 독립이다 — 두른 격이 있으면 그대로 실리고, 없어도 오의는 나간다.
     * 위력 = 무기 위력 + 무공 위력(절정급) + 오의 기본 위력 + 격 위력 + ⌊마진/2⌋ (개안이면 절반).
     */
    public Cast planUltimate(Ultimate art, String realm, int energy, int pool, String armed,
                             String weaponClass) {
        int cost = ultimateCost(art, pool);
        int paid = internal.payOrDowncast(energy, cost);
        if (paid == 0) {
            return null;   // 오의에는 다운캐스트가 없다 — 태울 것이 없으면 나가지 않는다
        }
        String grade = armed == null || GUARD.equals(armed) ? BARE : armed;
        return new Cast(art.id(), art.name(), grade, cost, paid, false, false,
                art.frames(), "다운", staggerTicks("다운"),
                art.hitType(), art.range(), art.width(),
                weaponPower(weaponClass), techniquePower.getOrDefault("절정급", 2),
                qiPower(grade) + ultimateBasePower, maxTargets, 0,
                art, isAwakening(realm));
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

    /**
     * 등록부의 첫 단 — <b>범인</b> (cultivation.yml {@code cultivation_stages[0]}).
     * 접합되지 않은 몸의 경지다: 강호의 장부에 이름이 없는 자는 무인이 아니다.
     * (하드코딩 금지 — 이름은 config 가 정한다.)
     */
    public String baseRealm() {
        return realmOrder.isEmpty() ? null : realmOrder.get(0);
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
        if (cast.halved()) {
            damage = (int) Math.ceil(damage / 2.0);           // 개안 — 불완전 시전 (위력 절반)
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

    // ══════════ 모션 등록부 (skill_motion.yml) — 연출의 단일 진실 원천 ══════════

    public Budget motionBudget() {
        return budget;
    }

    /**
     * 격의 모션. 호신강기는 격이 아니라 형태이므로 그 방어 격(강기)의 모션으로 선다 —
     * 그러나 <b>몸을 두르는 고리</b>는 형태(두름_몸)가 따로 갖는다 (실루엣이 달라야 읽힌다).
     */
    public GradeMotion motionGrade(String grade) {
        String key = grade == null ? BARE : GUARD.equals(grade) ? guardGrade : grade;
        GradeMotion m = gradeMotion.get(key);
        return m != null ? m : gradeMotion.get(BARE);
    }

    /** 형태의 모션 — 검기_두름 · 검강_두름 · 검기_참격 · 강기_포 · 호신강기 · 이기어검 */
    public FormMotion motionForm(String form) {
        return formMotion.get(form);
    }

    /** 발출(쏨)의 형태 — 격에 따라 참격/포. 프레임·사거리·쿨다운도 여기서 나온다 (코드 폴백 아님) */
    public FormMotion shotForm(String grade) {
        return switch (grade == null ? "" : grade) {
            case "검기" -> formMotion.get("검기_참격");
            case "강기" -> formMotion.get("강기_포");
            default -> null;
        };
    }

    public SkillMotion motionSkill(String skillId) {
        return skillMotion.get(skillId);
    }

    public UltimateMotion motionUltimate(String id) {
        return ultimateMotion.get(id);
    }

    /** 사건의 모션 — 등록되지 않은 이름을 부르면 null (코드가 수치를 지어내지 않는다) */
    public EventMotion motionEvent(String name) {
        return eventMotion.get(name);
    }

    /** 궤적 — 보이는 모양. skill_mechanics 의 히트박스 type 을 그대로 받는다 */
    public Traj trajectory(String type) {
        return trajectories.get(type);
    }

    /** 무기 계열의 모션 — 등록에 없는 계열은 '무관'(움직인다)으로 떨어진다 */
    public Style weaponStyle(String weaponClass) {
        Style s = weaponStyles.get(weaponClass);
        return s != null ? s : weaponStyles.get("무관");
    }

    // ══════════ 3D 모션 등록부 (skill_motion.yml display) ══════════
    // 【불변식 ㅁ】 여기가 전부 null 을 돌려줘도 무공은 보인다 — 파티클 층이 본체이기 때문이다.

    public DisplayBudget displayBudget() {
        return displayBudget;
    }

    /** 두 층의 조율 — 3D 획이 떴으면 궤적 파티클은 물러선다 (둘 다 뿌리면 지저분하고 비싸다) */
    public Blend displayBlend() {
        return displayBlend;
    }

    public DisplayModel displayModel(String id) {
        return id == null ? null : displayModels.get(id);
    }

    public DisplayMotion displayMotion(String id) {
        return id == null ? null : displayMotions.get(id);
    }

    /**
     * 참격선 — 히트박스 모양이 획을 고른다 (호=초승달 · 선/돌=곧은 획 · 원/진=고리).
     * <b>검을 그리지 않는다. 지나간 자리를 그린다.</b> 투사체(시)는 제 몸이 궤적이므로 획이 없다.
     */
    public DisplayMotion slashFor(String hitType) {
        return displayMotion(slashByTrail.get(hitType));
    }

    /** 격이 획에 스며든다 — <b>외공기도 획을 그린다</b> (격은 그것을 밝고 굵게 만들 뿐이다) */
    public Ink ink(String grade) {
        Ink i = gradeInk.get(grade == null ? BARE : grade);
        return i != null ? i : gradeInk.get(BARE);
    }

    /** 날의 기 — 격을 두르면 <b>손에 든 병기에 겹쳐</b> 서린다 (지속). 호신강기는 고리가 그 자리다 */
    public Sheath sheath(String grade) {
        return grade == null || GUARD.equals(grade) ? null : sheathByGrade.get(grade);
    }

    /** 계열의 손 — 획의 굵기·길이·시간 (검은 눕혀 긋고 · 단검은 가늘고 · 부는 굵다) */
    public Swing swing(String weaponClass) {
        return weaponClass == null ? null : swings.get(weaponClass);
    }

    /** 몸의 자세 — <b>궤적이 계열을 이긴다</b> (돌진은 어떤 병기를 들었든 몸을 던지는 것이다) */
    public Body body(String weaponClass, String hitType) {
        Body byTrail = bodyByTrail.get(hitType);
        return byTrail != null ? byTrail : bodyByClass.get(weaponClass);
    }

    public BodyLimits bodyLimits() {
        return bodyLimits;
    }

    /** 던진 물건이 나는 모션 — 이 계열의 '시' 궤적만 (활은 바닐라 화살이 이미 3D 다) */
    public DisplayMotion throwMotion(String weaponClass) {
        return throwClasses.contains(weaponClass) ? displayMotion(throwMotion) : null;
    }

    /** 【무공 없는 손】 병기를 든 것만으로 성립하는 기본 초식 — 없으면 null (활·무관·짐승) */
    public Basic basicStrike(String weaponClass) {
        return weaponClass == null ? null : basicStrike.get(weaponClass);
    }

    public int basicCooldownTicks() {
        return basicCooldownTicks;
    }

    /** 판정의 눈 — 【디버그】 히트박스의 자·목표 표시·산수 로그 (등록부가 예산을 정한다) */
    public Eye eye() {
        return eye;
    }

    /**
     * 【디버그】 판정의 눈 — <b>보이는 것이 정말 맞는 것인가</b>.
     *
     * <p>이 눈은 히트박스를 <b>다시 그리지 않는다</b>. 판정이 쓰는 그 함수에 점을 물어본다 —
     * "이 자리는 맞는 자리인가?" 그리는 코드와 맞히는 코드가 하나여야 그림이 판정에 대해 거짓말할 수 없다.
     */
    public record Eye(String hitboxParticle, double step, double height, int maxPoints,
                      String targetParticle, boolean log) {
    }

    /**
     * 이 계열로 나가는 무공들 — <b>원장이 고른다</b> (하드코딩된 무공표를 지운 자리).
     * {@code skills.yml martial_arts[].weapon_class} 정본. 액션 데이터가 없는 무공은 손에 실리지 않는다.
     */
    public List<String> artsFor(String weaponClass) {
        return artsByClass.getOrDefault(weaponClass, List.of());
    }

    /** 형태의 3D 형체 — 쏨(투사) · 두름_몸(고리) · 부림(어검). 두름(손끝 잔광)은 형체가 없다 */
    public DisplayMotion displayForForm(String form) {
        return displayMotion(displayByForm.get(form));
    }

    public DisplayMotion displayForUltimate(String ultimateId) {
        return displayMotion(displayByUltimate.get(ultimateId));
    }

    /** 3D 예산 — performance.yml vfx_entities 와 등록부 중 작은 쪽 */
    public record DisplayBudget(int globalCap, int maxLifetimeTicks, int degradeAt,
                                int reserveForUltimate, int perCastMax, int perPlayerMax,
                                float viewRange, int heartbeatTicks,
                                int slashMinTicks, int slashMaxTicks) {
    }

    /** 격이 획에 스며든다 — 굵기·밝기·머무는 틱. <b>외공기도 있다</b> (무공 없는 손도 획을 그린다) */
    public record Ink(String grade, float thickness, int blockLight, int skyLight, int hold) {
    }

    /** 날의 기 — 손에 든 병기에 <b>겹쳐</b> 서리는 형체 (복제가 아니다) */
    public record Sheath(String grade, String motion, float[] scale, int blockLight, int skyLight) {
    }

    /**
     * 몸의 자세 — <b>되는 것만</b>.
     *
     * <p>바닐라 클라이언트에서 플레이어의 <b>팔다리 각도는 서버가 줄 수 없다</b> (애니메이션은 클라이언트가
     * 돌리고, 팩으로 플레이어 지오메트리를 바꿀 수 없다). 남는 수단은 셋뿐이다:
     * 전진({@code lunge}) · 자세({@code pose} — 바닐라가 가진 자세만) · 정지(프레임이 이미 발을 묶는다).
     *
     * @param pose SWIMMING(몸을 눕힌다 — 찌르기·돌진) · SNEAKING(웅크린다 — 들어올림) ·
     *             SPIN_ATTACK(몸이 돈다 — 회전 베기) · 없음
     * @param script <b>자세의 줄기</b> — 획이 그려지는 동안 몸이 지나는 칸들 (비면 위의 한 칸만 쓴다)
     * @param windup <b>선딜이 있는 초식에만</b> 붙는 예비 동작 (기본 타격은 즉발이라 예비가 없다 —
     *               있는 척하면 "암기가 날아간 뒤에 몸이 젖혀지는" 거짓말이 된다)
     */
    public record Body(String key, double lunge, String pose, int poseTicks,
                       float yawKick, float pitchKick, List<Beat> script, Beat windup) {
        public boolean hasPose() {
            return pose != null && !"없음".equals(pose) && poseTicks > 0;
        }

        /** script 가 있으면 script 가 몸이다 — 없으면 한 칸짜리 옛 몸 (활·미등록 계열) */
        public boolean scripted() {
            return script != null && !script.isEmpty();
        }
    }

    /**
     * 자세의 한 칸 — <b>at</b> 은 스윙 시간의 비율이다 (0.0 = 판정과 같은 틱, 1.0 = 몸이 돌아온 자리).
     *
     * @param yaw   원점 기준 목표각(도). + = 오른쪽으로 튼다. 엔진은 <b>차분만</b> 더한다
     * @param pitch 원점 기준 목표각(도). + = 아래 (MC 규약)
     * @param pose  null = 유지 · "없음" = 해제 · 그 외 = 바닐라 Pose
     * @param hand  주(main) · 부(off) — <b>서버가 줄 수 있는 유일한 사지 애니메이션</b>
     */
    public record Beat(double at, double lunge, String pose, float yaw, float pitch, String hand) {
        public boolean setsPose() {
            return pose != null;
        }

        public boolean clearsPose() {
            return "없음".equals(pose);
        }
    }

    /** 자세의 상한 — 등록부가 조작감을 해치지 못하게 하는 못 */
    public record BodyLimits(double maxLunge, int maxPoseTicks, boolean requireGround,
                             boolean yawKickEnabled, float kickMax) {
    }

    /** 【무공 없는 손】 병기를 든 것만으로 성립하는 한 타 — 히트박스·프레임 (지금은 연출 전용) */
    public record Basic(String weaponClass, String trail, double range, double angle, Frames frames) {
    }

    /** 두 층의 조율 — 3D 획이 떴을 때 궤적 파티클을 깎는 비율 (타격 파티클은 건드리지 않는다) */
    public record Blend(double trailParticleRatio, int trailParticleMin, boolean keepImpact) {
        /** 3D 가 떴으면 점당 파티클을 깎는다. 0 이 되지는 않는다 — 지나간 자리는 언제나 남는다 */
        public int damp(int perPoint) {
            return Math.max(trailParticleMin, (int) Math.round(perPoint * trailParticleRatio));
        }
    }

    /**
     * 모델 한 장 — 팩과의 유일한 접점.
     *
     * @param key      item_model 컴포넌트 키 (honcheon:qi/blade_arc). 팩을 <b>받은 눈</b>에만 얹는다
     * @param base     팩이 있을 때의 바탕 아이템 (모델이 덮으므로 형체는 무의미)
     * @param fallback 팩이 없을 때의 바닐라 아이템 — 이것만으로 읽혀야 한다
     * @param size     기준 치수 [x, y, z] (m) — 팩 담당이 맞출 값이자 코드가 스케일에 곱하는 값
     * @param useHeld  실을 것이 <b>시전자의 병기 그 자체</b>다 (병기 휘두름 · 이기어검).
     *                 팩 유무와 무관하게 같은 엔티티 하나가 돈다 — <b>팩 게이트가 저절로 충족된다</b>
     */
    public record DisplayModel(String id, String key, String base, String fallback, float[] size,
                               boolean useHeld) {
    }

    /**
     * 계열의 손 — 획의 굵기·길이·시간.
     *
     * @param spanRatio 자세가 돌아오는 시간(프레임·계열 공속 중 긴 쪽)에 곱해 <b>획을 그리는 틱</b>을 낸다
     *                  — 공속이 곧 리듬이다 (단검 3틱 · 부 18틱)
     * @param reach     히트박스 사거리 대비 획의 길이. <b>1.0 을 넘지 못한다</b> — 등록부는 판정이 준
     *                  사거리를 <b>줄일 수만</b> 있다 (불변식 ㅂ · 거짓말을 구조로 막는다)
     * @param thickness 속도가 곧 두께다 — 단검 0.55 · 부 1.70
     * @param tilt      획이 누운 각(도). MC 규약: pitch 양수 = 아래 (내려베는 것일수록 크다)
     */
    public record Swing(String weaponClass, double spanRatio, double reach, float thickness,
                        float tilt) {
    }

    /**
     * 모션 한 줄 — 참격(그렸다 지워진다) · 서림(병기에 겹쳐 지속) · 투사(날아간다) · 고리 · 개화.
     *
     * @param spread     참격선이 지워질 때 살짝 <b>퍼지며</b> 사라지는 배율 (연기처럼)
     * @param stickTicks 던진 물건이 땅에 <b>꽂혀</b> 남는 틱 (빗나간 비수는 그 자리에 있다)
     * @param orient     <b>형체가 서는 각</b> [pitch, yaw, roll] (도). 시전자 기준 좌표계에서 모델을 돌린다 —
     *                   <b>+X 왼쪽 · +Y 위 · +Z 앞</b> (엔티티의 yaw 가 이미 시전자를 향해 있다).
     *                   pitch 양수 = <b>앞으로 눕는다</b> (MC 규약 · {@code swings[].tilt} 와 같은 부호):
     *                   {@code orient: [90, 0, 0]} 이면 모델의 <b>+Y(길이축)가 앞(+Z)을 향한다</b> —
     *                   그래서 '몸 앞에 선 획'이 <b>앞으로 뻗는 창</b>이 된다. 없으면 [0,0,0] (예전 그대로).
     *                   회전은 <b>크기 조절 뒤</b>에 걸린다(leftRotation) — {@code size}·{@code burst_scale} 의
     *                   축은 언제나 <b>모델의 축</b>이다 (회전해도 축이 흔들리지 않는다)
     * @param burstScale 개화가 <b>축마다</b> 자라는 비율 [x, y, z] (사거리에 곱한다). 스칼라도 받는다
     *                   (그러면 세 축이 같이 자란다 — 예전 문법). 축이 갈리면 <b>한 축만 뻗는 창</b>이 된다
     */
    public record DisplayMotion(String id, String kind, String model,
                                int lifetime, int birth, int fade, int interpolation, float spread,
                                float spin, double speed, float impactScale, int impactTicks,
                                int stickTicks, int count, double radius, double height, float orbit,
                                float[] burstScale, float[] orient,
                                String billboard, int blockLight, int skyLight) {
        public boolean isBolt() {
            return "투사".equals(kind);
        }

        public boolean isRing() {
            return "고리".equals(kind);
        }

        public boolean isBloom() {
            return "개화".equals(kind);
        }

        /** 참격선 — 검이 아니라 <b>지나간 자리</b>다 (만화의 검격) */
        public boolean isSlash() {
            return "참격".equals(kind);
        }

        /** 날의 기 — 손에 든 병기에 겹쳐 지속한다 */
        public boolean isSheath() {
            return "서림".equals(kind);
        }

        /** 등록부가 이 형체를 돌려 세웠는가 (아니면 예전 그대로 — yaw 로만 선다) */
        public boolean oriented() {
            return orient[0] != 0.0f || orient[1] != 0.0f || orient[2] != 0.0f;
        }
    }
}
