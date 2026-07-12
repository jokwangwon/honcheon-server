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
    /**
     * 일류 무인의 내공 하한 — cultivation.yml 은 절정(3)·초절정(5)·화경(7)·현경(9)만 수치로 적는다.
     * 일류는 '개화'가 요건이라 수치가 없다 (simbeop.yml: 0→1 = 1년 = 개화 직후 첫 단계).
     * <b>config 등록 대기</b>: 제안 키 {@code cultivation.yml 일류.promotion.requirements 에 "내공 1"}
     */
    private static final double DEFAULT_FIRST_CLASS_NAEGONG = 1.0;

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

    // ─── 조식(調息) — 전투 중 내력 회복 (internal_energy.yml recovery.in_combat.조식) ───
    private final int combatRegen;
    private final boolean regenOnlyIfUnspent;

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

        // ─── 조식 — 전투 중의 숨. config 에 없으면 0 (회복 없음). 코드가 수치를 지어내지 않는다 ───
        Map<String, Object> breath = recovery.get("in_combat") instanceof Map<?, ?> ic
                && ((Map<String, Object>) ic).get("조식") instanceof Map<?, ?> b
                ? (Map<String, Object>) b : Map.of();
        this.combatRegen = breath.get("per_round") instanceof Number n3 ? n3.intValue() : 0;
        this.regenOnlyIfUnspent = Boolean.TRUE.equals(breath.get("only_if_unspent"));

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
        floors.computeIfPresent("일류", (r, v) -> v > 0 ? v : DEFAULT_FIRST_CLASS_NAEGONG);
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
                            intOr(st.get("telegraph_boost"), 0), sfx(st.get("sound"))));
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
        return new Npc(id, String.valueOf(e.getOrDefault("name", id)),
                beast ? "짐승" : "사람", rank, realm, pool, grade);
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
                      int pool, String grade) {

        public boolean manifests() {
            return !BARE.equals(grade) && pool > 0;
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

    /** 초식 한 칸의 모션 — trail 은 skill_mechanics 의 히트박스 type 과 같아야 한다 (motion_audit ②) */
    public record Step(String trail, String particle, int count, boolean finisher,
                       int telegraphBoost, Sfx sound) {
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
        /** 이 합이 시작될 때의 내력 — 합이 끝날 때 줄어 있으면 '쓴 것'이다 (조식이 돌지 않는다) */
        public int energyAtRoundStart = -1;
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
     * 운기조식 1구간 회복 — max(내공 × 순도, floor). 하한은 config (meditation_floor).
     * 무방비의 값을 한다: 개화 직후에도 한 구간이면 발경 한 번은 돌아온다.
     */
    public int meditationRecover(double naegong, double purity) {
        return (int) Math.max(Math.round(naegong * purity), meditationFloor);
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
    public int combatRegen() {
        return combatRegen;
    }

    /** 【조건】 그 합에 내력을 한 점도 쓰지 않았을 때만 돈다. 이 조건이 규칙의 전부다 — 없으면 발경은 공짜다 */
    public boolean regenOnlyIfUnspent() {
        return regenOnlyIfUnspent;
    }

    // ══════════ 포위 — 다구리의 규칙 (combat.yml attack) ══════════

    /**
     * 포위 슬롯 — 한 표적을 <b>동시에</b> 칠 수 있는 손의 수. 그 밖은 대기(포위)다.
     * 다구리에도 몸이 들어갈 자리가 필요하다 — 머릿수는 '교대'가 되지 '동시타'가 되지 않는다.
     */
    public int engageSlots() {
        return engageSlots;
    }

    /** 협공 보정 — 추가 공격자당 +1 (캡 2) */
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
        Map<String, Object> mech = mechOf(skillId);
        List<Object> combo = (List<Object>) mech.get("combo");
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
}
