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
import java.util.Objects;

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
    /** 타격의 순간 — 멈춤·밀림·흔들림 (combat.yml impact). 맞는 쪽의 몸이 말하는 자리 */
    private final Impact impact;
    /** 타격 허용 — 누가 맞을 수 있는가의 문 (combat.yml strike_admission · B-119). 기본: 전부 허용 */
    private final StrikeAdmission admission;
    /** 전투 판정 v2 — 공방(攻防) (combat.yml combat_v2 · B-177). enabled: false 면 v1 그대로 */
    private final CombatV2 combatV2;
    /** 성장 v3 — 경지별 자격 레벨 (cultivation.yml levels.qualifying_level). 몹 레벨 유도의 표 */
    private final Map<String, Integer> qualifyingLevel = new LinkedHashMap<>();
    /** 성장 v3 — 처치 XP 등급 계수 (levels.xp_sources.combat.grade_coefficient). 비면 XP 미배선 */
    private final Map<String, Double> xpGradeCoef = new LinkedHashMap<>();
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
    private final Map<String, Ranged> rangedSpecs;         // skill_mechanics.yml: ranged (활·암기)
    private final Map<String, RangedFx> rangedFx;          // skill_motion.yml: ranged_fx (사선의 그림)
    private final Budget budget;
    /** HUD flash 읽을 시간 (skill_motion.yml hud.flash_read_ticks) — 순간 사건이 액션바 줄을 갖는 틱 수 (B-116) */
    private final int hudFlashTicks;
    /** 색의 등록부 (inks) — 코드는 이름을 옮길 뿐 색을 고르지 않는다 */
    private final Map<String, InkColor> inks;
    /** 오의의 화려함 — default 가 21종을 덮고, by_id 가 정체를 가진 것만 덮어쓴다 */
    private final UltFlourish ultFlourishDefault;
    private final Map<String, UltFlourish> ultFlourish;
    /** 무기 오라 — 손에 든 병기 둘레를 도는 기운 (weapon_aura). 없으면 null (등록 안 되면 조용히 꺼진다) */
    private final WeaponAura weaponAura;
    /** 병기 전시대 — 든 병기를 땅에 크게 세운다 (weapon_stand). 없으면 null */
    private final WeaponStand weaponStand;
    /** 떨어뜨린 병기 자동 확대 (dropped_display). 없으면 null */
    private final DroppedDisplay droppedDisplay;
    /** 전용 검기 평타 — 초록 초승달 검기 (kigi_slash). 없으면 null (기존 무협 참격이 돈다) */
    private final KigiSlash kigiSlashConfig;
    private HeavySlash heavySlashConfig;

    /**
     * ★ 검기의 <b>인게임 오버라이드</b> — {@code /혼천 검기 <키> <값>} 이 갈아끼운 값. null 이면 등록부 원본.
     *
     * <p>각도·크기는 <b>눈으로 봐야</b> 정해진다. config 를 고쳐 재기동하면 왕복이 1분이라 값 하나를
     * 못 찾는다. 그래서 <b>메모리에만</b> 사는 사본을 둔다 — 재기동하면 등록부 값으로 돌아간다
     * (config 파일은 <b>절대 안 쓴다</b>: 주석이 정본의 절반이라 프로그램이 쓰면 그 절반이 죽는다).
     * 확정값은 {@code /혼천 검기 보기} 가 뱉는 붙여넣기용 줄로 사람이 config 에 못 박는다.
     */
    private KigiSlash kigiSlashOverride;

    // ─── 3D 모션 등록부 (skill_motion.yml display) — 파티클 위에 얹는 층 ───
    // 【불변식 ㅁ】 디스플레이는 덧칠이다. 여기가 비어 있어도 파티클 층은 그대로 돈다.
    private final DisplayBudget displayBudget;
    private final Blend displayBlend;
    private final Map<String, DisplayModel> displayModels;
    private final Map<String, DisplayMotion> displayMotions;
    private final Map<String, StrokeOrigin> strokeOrigins;   // 모션 → 획이 서는 자리 (앞·높이·옆)
    private final StrokeLimits strokeLimits;                 // 몸 안 금지 · 사거리 대비 상한
    private final SwingArcs swingArcs;                       // ★ 스윙 넷 — 획이 도는 각 (찌르기 → 베기)
    private final SlashEye slashEye;                         // 【눈】 이것은 참격인가 찌르기인가
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
    /** 헛박자 신호 — 박자 미도래로 획이 안 나간 스윙의 소리 (basic_strike.whiff · 없으면 null) */
    private final Sfx basicWhiff;
    /** 타격 피드백 — 맞았다는 그림 (hit_feedback) */
    private final HitFx hitFx;
    /** 판정의 눈 — 【디버그】 히트박스를 재는 자(尺). 켠 사람에게만 보인다 */
    private final Eye eye;
    /** party.yml — 무공은 아군을 베지 않는다 (friendly_fire.스킬 = 면제) */
    private final boolean spareAllies;
    /** party.yml — <b>오의의 광역만은 예외다</b> ("매화만개 앞에서는 아군도 물러선다") */
    private final boolean ultimateSweepsAllies;
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
        Map<String, Object> pt = RulesConfig.load(cfg.resolve("party.yml"));

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

        // ─── 성장 v3 XP (cultivation.yml levels — B-135 단계 4 · 사용자 확정 2026-07-24) ───
        Map<String, Object> v3Levels = RulesConfig.section(cu, "levels");
        RulesConfig.section(v3Levels, "qualifying_level")
                .forEach((realm, v) -> qualifyingLevel.put(realm, v instanceof Number n7 ? n7.intValue() : 0));
        RulesConfig.section(RulesConfig.section(RulesConfig.section(v3Levels, "xp_sources"), "combat"),
                        "grade_coefficient")
                .forEach((g, v) -> xpGradeCoef.put(g, v instanceof Number n8 ? n8.doubleValue() : 0.0));

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

        // ─── 타격의 순간 (combat.yml impact) — 등록부가 없으면 통째로 꺼진다 (옛 동작 보존) ───
        this.impact = loadImpact(RulesConfig.section(cb, "impact"));

        // ─── 타격 허용 (combat.yml strike_admission · B-119) — 절이 없으면 전부 허용 (기본 자세가 열림) ───
        this.admission = StrikeAdmission.load(RulesConfig.section(cb, "strike_admission"));

        // ─── 전투 판정 v2 (combat.yml combat_v2 · B-177) — 절이 없거나 enabled: false 면 v1 그대로 ───
        this.combatV2 = CombatV2.load(RulesConfig.section(cb, "combat_v2"));
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
                intOr(bd.get("ultimate_ring_points"), 8),
                intOr(bd.get("crit_reserve"), 4));

        // ─── HUD — 액션바 한 줄의 주인 (B-116): 순간 사건이 줄을 갖는 읽을 시간 ───
        this.hudFlashTicks = intOr(asMap(mo.get("hud")).get("flash_read_ticks"), 15);

        // ─── 먹빛 (inks) — 색의 등록부. 코드는 이름을 옮길 뿐 색을 고르지 않는다 ───
        Map<String, InkColor> inkMap = new LinkedHashMap<>();
        RulesConfig.section(mo, "inks").forEach((name, raw) -> {
            Map<String, Object> i = asMap(raw);
            int[] rgb = rgb(i.get("rgb"));
            if (rgb != null) {
                inkMap.put(name, new InkColor(name, rgb[0], rgb[1], rgb[2],
                        (float) dblOr(i.get("size"), 1.0)));
            }
        });
        this.inks = Collections.unmodifiableMap(inkMap);

        Map<String, GradeMotion> gms = new LinkedHashMap<>();
        RulesConfig.section(mo, "grades").forEach((grade, raw) -> {
            Map<String, Object> g = asMap(raw);
            Map<String, Object> sounds = asMap(g.get("sounds"));
            gms.put(grade, new GradeMotion(grade,
                    intOr(g.get("rank"), 0), intOr(g.get("brightness"), 0),
                    String.valueOf(g.getOrDefault("color", "GRAY")),
                    fx(g.get("charge")), fx(g.get("aura")), fx(g.get("impact")), fx(g.get("accent")),
                    str(asMap(g.get("trail")).get("particle")), intOr(asMap(g.get("trail")).get("per_point"), 1),
                    fx(g.get("echo")), fx(g.get("haze")), fx(g.get("burst")),
                    sfxList(sounds.get("charge")), sfxList(sounds.get("arm")), sfxList(sounds.get("impact"))));
        });
        this.gradeMotion = Collections.unmodifiableMap(gms);

        // ─── 오의의 화려함 — default 한 줄이 21종을 덮고, by_id 가 정체를 가진 것만 덮어쓴다 ───
        Map<String, Object> uf = RulesConfig.section(mo, "ultimate_flourish");
        this.ultFlourishDefault = ultFlourish("default", asMap(uf.get("default")));
        Map<String, UltFlourish> ufs = new LinkedHashMap<>();
        asMap(uf.get("by_id")).forEach((id, raw) -> ufs.put(id, ultFlourish(id, asMap(raw))));
        this.ultFlourish = Collections.unmodifiableMap(ufs);

        // ─── 무기 오라 (weapon_aura) — 손에 든 병기 둘레를 도는 기운. 미등록이면 null (조용히 꺼진다) ───
        this.weaponAura = weaponAura(RulesConfig.section(mo, "weapon_aura"));
        this.weaponStand = weaponStand(RulesConfig.section(mo, "weapon_stand"));
        this.droppedDisplay = droppedDisplay(RulesConfig.section(mo, "dropped_display"));
        this.kigiSlashConfig = kigiSlash(RulesConfig.section(mo, "kigi_slash"));
        this.heavySlashConfig = heavySlash(RulesConfig.section(mo, "heavy_slash"));

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

        // ─── 원거리 등록부 (skill_mechanics.yml ranged — 활·암기. 사거리를 위력으로 바꾼다) ───
        Map<String, Ranged> rgs = new LinkedHashMap<>();
        RulesConfig.section(mech, "ranged").forEach((cls, raw) -> {
            if (!(raw instanceof Map)) {
                return;   // note 등 서술 키는 계열이 아니다
            }
            Map<String, Object> r = asMap(raw);
            rgs.put(cls, new Ranged(cls, dblOr(r.get("range"), 24.0),
                    intOr(r.get("draw_ticks"), 20), dblOr(r.get("min_range"), 0.0),
                    str(r.get("ammo"))));
        });
        this.rangedSpecs = Collections.unmodifiableMap(rgs);

        // ─── 원거리 사선의 그림 (skill_motion.yml ranged_fx — 시안 축. 없으면 즉발·weapon_styles) ───
        Map<String, RangedFx> rfx = new LinkedHashMap<>();
        RulesConfig.section(mo, "ranged_fx").forEach((cls, raw) -> {
            if (!(raw instanceof Map)) {
                return;
            }
            Map<String, Object> r = asMap(raw);
            rfx.put(cls, new RangedFx(str(r.get("mode")),
                    str(r.get("core_ink")), str(r.get("rim_ink")),
                    dblOr(r.get("step"), 0.6), dblOr(r.get("speed_mpt"), 12.0),
                    dblOr(r.get("tail_m"), 3.0), (float) dblOr(r.get("size"), 0.5)));
        });
        this.rangedFx = Collections.unmodifiableMap(rfx);

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
                    Boolean.TRUE.equals(m.get("use_held")),
                    str(m.get("anchor"))));     // 등록부가 고정점을 청구한다 (코드가 지어내지 않는다)
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

        // ─── 획이 서는 자리 — **코드가 자리를 지어내지 않는다** (등록제 규약) ───
        Map<String, Object> so = asMap(dp.get("stroke_origin"));
        Map<String, Object> sl = asMap(so.get("limits"));
        this.strokeLimits = new StrokeLimits(
                dblOr(sl.get("body_radius"), 0.30),
                dblOr(sl.get("clearance"), 0.10),
                dblOr(sl.get("max_height"), 1.80),
                dblOr(sl.get("forward_max_ratio"), 0.45));
        Map<String, StrokeOrigin> sos = new LinkedHashMap<>();
        so.forEach((id, raw) -> {
            if ("limits".equals(id)) {
                return;
            }
            Map<String, Object> m = asMap(raw);
            sos.put(id, new StrokeOrigin(id,
                    dblOr(m.get("forward"), 0.0),
                    dblOr(m.get("height"), 1.35),
                    dblOr(m.get("lateral"), 0.0),
                    Boolean.TRUE.equals(m.get("centered"))));
        });
        this.strokeOrigins = Collections.unmodifiableMap(sos);

        // ─── ★ 스윙 넷 — **획이 도는 각** (코드가 각을 지어내지 않는다) ───
        Map<String, Object> sa = asMap(dp.get("swing_arcs"));
        Map<String, Object> sal = asMap(sa.get("limits"));
        Map<String, SwingArc> arcs = new LinkedHashMap<>();
        asMap(sa.get("strokes")).forEach((id, raw) -> {
            Map<String, Object> a = asMap(raw);
            arcs.put(id, new SwingArc(id,
                    pair(a.get("yaw")), pair(a.get("pitch")), pair(a.get("roll")),
                    pair(a.get("rise")),
                    Math.max(0.0, Math.min(1.0, dblOr(a.get("fan"), 1.0))),
                    dblOr(a.get("bow"), 0.0)));
        });
        List<String> cyc = new ArrayList<>();
        if (sa.get("cycle") instanceof List<?> l) {
            for (Object o : l) {
                if (o != null && arcs.containsKey(String.valueOf(o))) {
                    cyc.add(String.valueOf(o));
                }
            }
        }
        List<String> heavyCls = new ArrayList<>();
        if (sa.get("heavy_classes") instanceof List<?> l) {
            l.forEach(o -> heavyCls.add(String.valueOf(o)));
        }
        this.swingArcs = new SwingArcs(
                !Boolean.FALSE.equals(sal.get("enabled")),
                dblOr(sal.get("max_arc_deg"), 150.0),
                intOr(sal.get("reset_ticks"), 24),
                dblOr(sal.get("lunge_to_meters"), 2.5),
                Collections.unmodifiableList(cyc),
                str(sa.get("heavy")),
                Collections.unmodifiableList(heavyCls),
                Collections.unmodifiableMap(arcs),
                new SwingTuning(1.0, 1.0, 1.0, 1.0).from(asMap(sa.get("tuning"))));

        Map<String, Object> se = asMap(dp.get("slash_eye"));
        List<String> exempt = new ArrayList<>();
        if (se.get("exempt_trails") instanceof List<?> l) {
            l.forEach(o -> exempt.add(String.valueOf(o)));
        }
        this.slashEye = new SlashEye(
                dblOr(se.get("min_arc_deg"), 60.0),
                dblOr(se.get("max_lunge_m"), 0.30),
                dblOr(se.get("max_lunge_m_heavy"), dblOr(se.get("max_lunge_m"), 0.40)),
                dblOr(se.get("min_ratio"), 150.0),
                Collections.unmodifiableList(exempt));

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
        this.basicWhiff = sfx(bs.get("whiff"));
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

        // 【타격 피드백】 맞았다는 그림 — 판정의 결과 표시 (hit_feedback · 2026-07-23 사용자 승인)
        Map<String, Object> hf = RulesConfig.section(mo, "hit_feedback");
        Map<String, Object> hfNum = asMap(hf.get("damage_number"));
        Map<String, Object> hfBar = asMap(hf.get("target_bar"));
        Map<String, Object> hfKill = asMap(hf.get("kill_burst"));
        this.hitFx = new HitFx(
                Boolean.TRUE.equals(hf.get("enabled")),
                hfNum.get("enabled") == null || Boolean.TRUE.equals(hfNum.get("enabled")),
                Math.max(1, intOr(hfNum.get("ticks"), 18)), dblOr(hfNum.get("rise"), 0.8),
                dblOr(hfNum.get("scale"), 1.4), dblOr(hfNum.get("scatter"), 0.45),
                Math.max(1, intOr(hfNum.get("max_alive"), 24)),
                hfBar.get("enabled") == null || Boolean.TRUE.equals(hfBar.get("enabled")),
                Math.max(1, intOr(hfBar.get("seconds"), 6)), Math.max(1, intOr(hfBar.get("width"), 10)),
                dblOr(hfBar.get("height"), 0.5), Math.max(1, intOr(hfBar.get("max_alive"), 8)),
                hfKill.get("enabled") == null || Boolean.TRUE.equals(hfKill.get("enabled")),
                str(hfKill.get("particle")), Math.max(0, intOr(hfKill.get("count"), 14)),
                dblOr(hfKill.get("spread"), 0.35));

        // ─── 손이 가려야 할 것 — 【등록부가 이미 답을 갖고 있었다】 (party.yml mc.friendly_fire) ───
        //   "friendly_fire: { 스킬: 면제, 오의_광역: 예외 }" — 무공은 아군을 베지 않는다.
        //   **다만 오의의 광역은 예외다** ("매화만개 앞에서는 아군도 물러선다").
        //   코드가 이 규칙을 지어내지 않는다 — 등록부에서 읽어 온다. 등록부가 말을 바꾸면 코드가 따라간다.
        Map<String, Object> ff = asMap(RulesConfig.section(pt, "mc").get("friendly_fire"));
        this.spareAllies = "면제".equals(str(ff.get("스킬")));
        this.ultimateSweepsAllies = "예외".equals(str(ff.get("오의_광역")));

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

    /** [시작, 끝] 두 칸 — 스윙의 각·높이가 쓰는 문법. 없으면 [0, 0] (안 도는 획 = 옛 그림) */
    private static double[] pair(Object raw) {
        return new double[]{dblOr(idx(raw, 0), 0.0), dblOr(idx(raw, 1), 0.0)};
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
                dblOr(m.get("spread"), 0.1), dblOr(m.get("extra"), 0.0), str(m.get("ink")));
    }

    /**
     * 무기 오라 등록부 한 벌 — {@code weapon_aura} 절. 절이 없으면 null (조용히 꺼진다).
     * 계열/명병 색과 등급 사다리를 문자열 그대로 담는다 (코드는 색·파티클을 고르지 않는다).
     */
    private static WeaponAura weaponAura(Map<String, Object> m) {
        if (m == null || m.isEmpty()) {
            return null;
        }
        Map<String, String> seriesInk = new LinkedHashMap<>();
        asMap(m.get("series")).forEach((s, raw) -> {
            String ink = str(asMap(raw).get("ink"));
            if (ink != null) {
                seriesInk.put(s, ink);
            }
        });
        Map<String, String> myeongInk = new LinkedHashMap<>();
        asMap(m.get("myeong")).forEach((s, raw) -> {
            String ink = str(asMap(raw).get("ink"));
            if (ink != null) {
                myeongInk.put(s, ink);
            }
        });
        Map<String, WeaponAuraGrade> grades = new LinkedHashMap<>();
        asMap(m.get("grades")).forEach((g, raw) -> {
            Map<String, Object> gm = asMap(raw);
            grades.put(g, new WeaponAuraGrade(intOr(gm.get("shards"), 0),
                    intOr(gm.get("sparks"), 0), intOr(gm.get("spark_every"), 0),
                    str(gm.get("ink"))));
        });
        return new WeaponAura(
                m.get("enabled") == null || Boolean.TRUE.equals(m.get("enabled")),
                intOr(m.get("interval_ticks"), 3), dblOr(m.get("orbit_speed"), 0.22),
                dblOr(m.get("radius"), 0.30), dblOr(m.get("helix"), 0.12),
                m.get("dropped") == null || Boolean.TRUE.equals(m.get("dropped")),
                dblOr(m.get("dropped_rise"), 0.25),
                m.get("include_displays") == null || Boolean.TRUE.equals(m.get("include_displays")),
                m.get("held") == null || Boolean.TRUE.equals(m.get("held")),
                intOr(m.get("held_interval_ticks"), 1),
                dblOr(m.get("held_forward"), 0.45), dblOr(m.get("held_right"), 0.32),
                dblOr(m.get("held_down"), 0.30),
                str(m.get("shard_particle")), str(m.get("spark_particle")),
                (float) dblOr(m.get("shard_size"), 0.7), (float) dblOr(m.get("spark_size"), 0.6),
                dblOr(m.get("shard_spread"), 0.02), dblOr(m.get("spark_spread"), 0.03),
                dblOr(m.get("spark_radius_mul"), 1.3),
                Collections.unmodifiableMap(seriesInk), Collections.unmodifiableMap(myeongInk),
                Collections.unmodifiableMap(grades));
    }

    /** 병기 전시대 등록부 — {@code weapon_stand} 절. 없으면 null (전시대 명령이 조용히 거절한다) */
    private static WeaponStand weaponStand(Map<String, Object> m) {
        if (m == null || m.isEmpty()) {
            return null;
        }
        return new WeaponStand(
                m.get("enabled") == null || Boolean.TRUE.equals(m.get("enabled")),
                dblOr(m.get("scale"), 3.5), dblOr(m.get("rot_x"), 0.0),
                dblOr(m.get("rot_y"), 0.0), dblOr(m.get("rot_z"), 135.0),
                dblOr(m.get("rise"), 1.4), dblOr(m.get("retrieve_radius"), 4.0),
                dblOr(m.get("aura_scale"), 2.8), dblOr(m.get("aura_center_rise"), 1.5));
    }

    /** 떨어뜨린 병기 자동 확대 등록부 — {@code dropped_display} 절. 없으면 null (드롭 확대 안 함) */
    private static DroppedDisplay droppedDisplay(Map<String, Object> m) {
        if (m == null || m.isEmpty()) {
            return null;
        }
        return new DroppedDisplay(
                m.get("enabled") == null || Boolean.TRUE.equals(m.get("enabled")),
                dblOr(m.get("scale"), 2.5), dblOr(m.get("rot_x"), 0.0),
                dblOr(m.get("rot_y"), 0.0), dblOr(m.get("rot_z"), 90.0),
                dblOr(m.get("rise"), 0.9), dblOr(m.get("pickup_radius"), 1.6),
                intOr(m.get("lifetime_seconds"), 300),
                dblOr(m.get("aura_scale"), 2.2), dblOr(m.get("aura_center_rise"), 1.0));
    }

    /**
     * ★ 부(斧)의 횡참(橫斬) — {@code heavy_slash} 절 (2026-07-21 사용자 확정: "충격은 빼고 횡참으로").
     * 검의 띠와 같은 밴드 문법이되 <b>짧고 굵고 둔중하다</b> — 검은 베고 지나가고, 부는 부수고 멈춘다.
     * 판정도 같은 문법: 궤적에 닿으면 딜 (빗나감/명중 분기 없음).
     */
    private static HeavySlash heavySlash(Map<String, Object> m) {
        if (m == null || m.isEmpty()) {
            return null;
        }
        List<String> classes = new ArrayList<>();
        if (m.get("apply_to_classes") instanceof List<?> cl) {
            cl.forEach(o -> classes.add(String.valueOf(o)));
        }
        return new HeavySlash(
                m.get("enabled") == null || Boolean.TRUE.equals(m.get("enabled")),
                Collections.unmodifiableList(classes),
                String.valueOf(m.getOrDefault("ink", "청회")), str(m.get("ink_alt")),
                str(m.get("template")),
                dblOr(m.get("radius"), 1.5), dblOr(m.get("sweep_deg"), 70.0),
                dblOr(m.get("tilt_deg"), 20.0), dblOr(m.get("center_height"), 1.0),
                dblOr(m.get("band_width"), 0.55), Math.max(1, intOr(m.get("band_rows"), 4)),
                dblOr(m.get("band_jitter"), 0.14), Math.max(1, intOr(m.get("sweep_ticks"), 3)),
                dblOr(m.get("step_deg"), 1.2),
                // ★ 몸이 먼저, 궤적이 나중 (2026-07-22 사용자) — 선딜 뒤에 띠·판정이 함께 나간다
                Math.max(0, intOr(m.get("trail_delay_ticks"), 3)),
                dblOr(m.get("width_self_mul"), 0.8), dblOr(m.get("width_others_mul"), 1.25),
                dblOr(m.get("height_others_mul"), 2.0),
                m.get("hit") == null || Boolean.TRUE.equals(m.get("hit")),
                dblOr(m.get("hit_reach"), 0.9),
                m.get("replace_stroke") == null || Boolean.TRUE.equals(m.get("replace_stroke")));
    }

    /** 전용 검기 평타 등록부 — {@code kigi_slash} 절. 없으면 null (조용히 꺼진다 — 기존 무협 참격이 돈다) */
    private static KigiSlash kigiSlash(Map<String, Object> m) {
        if (m == null || m.isEmpty()) {
            return null;
        }
        List<String> trails = new ArrayList<>();
        if (m.get("apply_to_trails") instanceof List<?> l) {
            l.forEach(o -> trails.add(String.valueOf(o)));
        }
        // ★ 원형(archetype) 축 — 있으면 trail 축을 대신한다 (도끼가 검압을 받지 않게 · 검토 P0)
        List<String> classes = new ArrayList<>();
        if (m.get("apply_to_classes") instanceof List<?> cl) {
            cl.forEach(o -> classes.add(String.valueOf(o)));
        }
        // ★ 단계 모델 — 스윙 중 갈아끼울 순서 (등록 이름. 비면 교체가 꺼지고 model 하나로 고정)
        List<String> frameModels = new ArrayList<>();
        if (m.get("frame_models") instanceof List<?> fl) {
            fl.forEach(o -> frameModels.add(String.valueOf(o)));
        }
        // ★ B 방향 세트 (검압 올려베기) — 있으면 스윙 방향(dirSign)에 따라 A/B 를 번갈아 쓴다
        List<String> frameModelsB = new ArrayList<>();
        if (m.get("frame_models_b") instanceof List<?> fb) {
            fb.forEach(o -> frameModelsB.add(String.valueOf(o)));
        }
        Map<String, Object> sp = asMap(m.get("spark"));
        KigiSpark spark = new KigiSpark(
                String.valueOf(sp.getOrDefault("particle", "end_rod")),
                intOr(sp.get("count"), 7), dblOr(sp.get("spread"), 0.9),
                dblOr(sp.get("speed"), 0.02),
                sp.get("along_arc") == null || Boolean.TRUE.equals(sp.get("along_arc")));
        return new KigiSlash(
                m.get("enabled") == null || Boolean.TRUE.equals(m.get("enabled")),
                // ★ 매체 축 (재설계 v2 · 사용자 확정): 일반 무기는 particle.
                //   plate·model3d 는 지우지 않는다 — 보스·특수 무기의 교체 지점이다.
                String.valueOf(m.getOrDefault("medium", "particle")),
                str(m.get("model")), Collections.unmodifiableList(trails),
                Collections.unmodifiableList(classes),
                Collections.unmodifiableList(frameModels),
                Collections.unmodifiableList(frameModelsB),
                Math.max(1, intOr(m.get("frame_ticks"), 3)),
                // ★ 파티클 띠 (docs/design/kigi_particle_v2.md — 야마토 실측 대역)
                dblOr(m.get("band_width"), 0.6), Math.max(1, intOr(m.get("band_rows"), 4)),
                dblOr(m.get("band_jitter"), 0.10),
                Math.max(1, intOr(m.get("band_sweep_ticks"), 4)),
                Math.max(0, intOr(m.get("accent_count"), 12)),
                // ★ 시점별 굵기 (2026-07-22 사용자): 시전자(1인칭) 얇게 · 관전자(3인칭) 넓게
                dblOr(m.get("width_self_mul"), 0.8), dblOr(m.get("width_others_mul"), 1.25),
                // ★ 세로 퍼짐 (2026-07-22 재질문 답: "3인칭 넓게 = 세로 높이") — 관전자에게만
                dblOr(m.get("height_others_mul"), 2.2),
                // ★ 띠 = 판정 (2026-07-21 사용자 확정) — 닿으면 딜. 빗나감/명중 분기 없음
                m.get("band_hit") == null || Boolean.TRUE.equals(m.get("band_hit")),
                dblOr(m.get("band_hit_reach"), 0.9),
                Boolean.TRUE.equals(m.get("replace_stroke")),
                dblOr(m.get("scale"), 2.0), dblOr(m.get("center_height"), 1.0),
                dblOr(m.get("forward"), 0.1), dblOr(m.get("orbit_radius"), 1.1),
                dblOr(m.get("sweep_deg"), 140.0),
                dblOr(m.get("tilt_deg"), 35.0), dblOr(m.get("roll_deg"), 0.0),
                dblOr(m.get("blade_pitch_deg"), 90.0),
                dblOr(m.get("yaw_deg"), 0.0),   // ★베기면 고정 (2026-07-23): 90 = 판 길이축이 전진축 — 정면·후면 얇고 측면이 그림 전체

                Math.max(1, intOr(m.get("draw_ticks"), 8)),
                Math.max(0, intOr(m.get("fade_ticks"), 5)),
                String.valueOf(m.getOrDefault("billboard", "FIXED")),
                m.get("alternate") == null || Boolean.TRUE.equals(m.get("alternate")),
                Math.max(0, Math.min(15, intOr(m.get("brightness"), 15))),
                spark,
                m.get("calm_held_aura") == null || Boolean.TRUE.equals(m.get("calm_held_aura")),
                // ★ 몸 둘레를 도는 호 (EffectLib 기하) — 이름이 없으면 안 그린다 (옛 동작 그대로)
                str(m.get("geom_particle")), str(m.get("geom_ink")), str(m.get("geom_ink_alt")),
                // ★ 시트 템플릿 (있으면 밴드 대신 그림을 그대로 놓는다 — tools/sheet_to_template.py)
                str(m.get("geom_template")), str(m.get("geom_template_fps")),
                // ★ 호의 각은 **판의 sweep_deg 와 다른 것**이다. 재사용했다가 한 점으로 접혔다
                //   (sweep_deg 는 0 으로 맞춰져 있다 — 프레임이 흩어지지 않게 한 값이다).
                dblOr(m.get("geom_sweep_deg"), 150.0),
                // ★ 점 간격(도) — 작을수록 촘촘하다. 6도는 티끌로 읽혔다 (사용자 평가 · 실측)
                dblOr(m.get("geom_step_deg"), 1.5),
                // ★ 판(ItemDisplay)을 세우는가. false 면 **점만** 쓴다 (층을 갈라 재기 위한 문 · 가역)
                m.get("plate") == null || Boolean.TRUE.equals(m.get("plate")),
                // ★ 판이 시전자를 따라 움직이는가 (2026-07-23 사용자: "지나간 자리 허공에 남는다") — 기본은 옛 동작(붙박이 · 가역)
                Boolean.TRUE.equals(m.get("follow")),
                // ★ 3D 리본 (BetterModel) — 이름이 있으면 판·점보다 먼저 시도한다. 없으면 옛 동작 (가역)
                str(m.get("model3d")), str(m.get("model3d_anim")),
                // ★ 리본의 배치 — 재적재로 쓸어 맞춘다 (재기동 없이). up=올림(m) · yaw/pitch=회전(도)
                dblOr(m.get("model3d_up"), 0.0),
                dblOr(m.get("model3d_yaw"), 0.0),
                dblOr(m.get("model3d_pitch"), 0.0));
    }

    /** {@code rgb: [r, g, b]} — 세 칸이 아니면 null (등록부가 색을 반만 적었으면 색이 아니다) */
    private static int[] rgb(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() < 3) {
            return null;
        }
        int[] v = new int[3];
        for (int i = 0; i < 3; i++) {
            v[i] = list.get(i) instanceof Number n ? Math.max(0, Math.min(255, n.intValue())) : 0;
        }
        return v;
    }

    /** 오의의 화려함 한 벌 — 등록되지 않은 칸은 {@code present() == false} 로 조용히 비어 있다 */
    private static UltFlourish ultFlourish(String id, Map<String, Object> m) {
        Map<String, Object> ly = asMap(m.get("layers"));
        Map<String, Object> mi = asMap(m.get("mist"));
        return new UltFlourish(id,
                fx(m.get("bloom")),
                new Layers(str(ly.get("particle")), str(ly.get("ink")),
                        intOr(ly.get("arcs"), 0), intOr(ly.get("points"), 0),
                        intOr(ly.get("per_point"), 0), dblOr(ly.get("spread"), 0.1),
                        dblOr(ly.get("extra"), 0.0), dblOr(ly.get("radius_ratio"), 0.55)),
                new Mist(str(mi.get("particle")), intOr(mi.get("count"), 0),
                        dblOr(mi.get("spread"), 0.5), dblOr(mi.get("extra"), 0.0),
                        intOr(mi.get("ticks"), 0), dblOr(mi.get("height"), 0.4)),
                fx(m.get("hit")));
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
        // 몹 레벨 (성장 v3 XP — 등록값 우선, 없으면 상당 경지의 자격 레벨로 유도. 코드가 짓지 않는다)
        int level = e.get("level") instanceof Number ln ? ln.intValue() : 0;
        // XP 등급 (성장 v3 — 잡졸/정예/두목 · levels.xp_sources.combat.grade_coefficient 의 키만
        //   유효하다. lint_config 가 잰다). 미등록이면 null — killXp 가 잡졸 계수로 읽는다
        String xpGrade = e.get("xp_grade") == null ? null : String.valueOf(e.get("xp_grade"));
        return new Npc(id, String.valueOf(e.getOrDefault("name", id)),
                beast ? "짐승" : "사람", rank, realm, pool, grade,
                Collections.unmodifiableMap(stats), weaponClass, level, xpGrade);
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
                      int pool, String grade, Map<String, Integer> stats, String weaponClass,
                      int level, String xpGrade) {

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
    /**
     * 타격 피드백 등록부 (hit_feedback) — 대미지 숫자 · 표적 HP띠 · 처치 흩어짐.
     * <b>판정의 결과 표시일 뿐이다</b> — 판정·피해는 한 획도 안 바꾼다.
     */
    public record HitFx(boolean enabled,
                        boolean numberEnabled, int numberTicks, double numberRise,
                        double numberScale, double numberScatter, int numberMaxAlive,
                        boolean barEnabled, int barSeconds, int barWidth,
                        double barHeight, int barMaxAlive,
                        boolean killEnabled, String killParticle, int killCount, double killSpread) {
    }

    public record Strike(int roll, int margin, String tierId, String tierName, boolean hit, int damage) {
    }

    // ══════════ 모션 등록부 값 타입 (skill_motion.yml) ══════════
    // 【등록제 규약】 여기 담기는 것은 전부 문자열·수치다. Bukkit 의 Particle·Sound 로 바꾸는 것은
    // SkillListener 의 몫이다 (이 클래스의 Bukkit 의존 0 불변식을 지킨다).

    /**
     * 파티클 한 발 — 팔레트 이름(smoke·crit·end_rod·dust…) · 개수 · 퍼짐 · 속도 · <b>먹빛</b>.
     *
     * @param ink 등록부 {@code inks} 의 이름(회백·청회·청록·옥·청백·먹·혈). {@code dust} 파티클만 쓴다 —
     *            <b>코드가 색을 지어내지 않는다</b>: 이름을 받아 {@link InkColor} 로 옮길 뿐이다
     *            (SkillHud 가 그 자리다). 색이 없으면 {@code null} 이고, 그러면 바닐라 색 그대로다
     */
    public record Fx(String particle, int count, double spread, double extra, String ink) {
        public boolean present() {
            return particle != null && count > 0;
        }
    }

    /**
     * 먹빛 한 칸 — {@code inks} 등록부의 색 (config/skill_motion.yml).
     *
     * <p><b>사용자 지시(2026-07)</b>: 기본=먹·회백 / 중급=청회 / 상급=청록·옥·백.
     * <b>금지=형광 핑크·네온 보라·과도한 노랑</b>. 그 금지는 {@code inks_forbidden} 이 적고
     * {@code motion_audit.py} 축 ⑦ 이 잰다 — 코드는 색을 <b>고르지 않고 옮긴다</b>.
     */
    public record InkColor(String name, int r, int g, int b, float size) {
    }

    /** 소리 한 발 — 바닐라 사운드 키("block.anvil.land"). 1.21 의 Sound 는 열거형이 아니다 (문자열 재생) */
    public record Sfx(String key, float volume, float pitch) {
    }

    /**
     * 파티클 예산 — 한 지점·한 틱 상한과 세 개의 풀(응집·궤적·타격).
     *
     * @param critReserve 대성공(급소)이 <b>타격점 위에 겹칠 자리</b> — 미리 비워 둔다.
     *                    이것이 없으면 폭발이 자리를 다 먹고 대성공이 <b>조용히 잘린다</b>
     */
    public record Budget(int perPointTickMax, int perCastMax, int telegraphPool, int trailPool,
                         int impactPool, int minImpactPerTarget, int ultimateTickMax,
                         int telegraphStepTicks, int trailMaxPoints, int ultimateRingPoints,
                         int critReserve) {
    }

    /**
     * 격의 모션 — 사다리의 한 칸. rank 가 오르면 charge·impact·aura 가 반드시 커진다 (motion_audit ③).
     *
     * @param echo  <b>잔상</b> — 획이 지나간 자리 (회백 → 청회 → 청록 → 옥 → 청백)
     * @param haze  <b>먼지 · 바람결 · 먹번짐</b> — 초급의 "발광 거의 없음"을 지키는 자
     * @param burst <b>타격 순간의 작은 폭발형 입자</b> — 먹점(impact)에서 <b>갈라 온</b> 몫이다
     *              (예산은 한 톨도 안 늘었다 — skill_motion.yml budget 절의 표)
     */
    public record GradeMotion(String grade, int rank, int brightness, String color,
                              Fx charge, Fx aura, Fx impact, Fx accent,
                              String trailParticle, int trailPerPoint,
                              Fx echo, Fx haze, Fx burst,
                              List<Sfx> chargeSounds, List<Sfx> armSounds, List<Sfx> impactSounds) {
    }

    /**
     * 무기 오라 — 손에 든 병기 둘레를 도는 기운 (weapon_aura). 순수 VFX (판정을 모른다).
     * 계열/명병은 색(ink)만 정하고, 등급이 밀도(shards·sparks)를 정한다 (사다리는 등급이다).
     */
    public record WeaponAura(boolean enabled, int intervalTicks, double orbitSpeed, double radius,
                             double helix,
                             boolean dropped, double droppedRise, boolean includeDisplays,
                             boolean held, int heldIntervalTicks,
                             double heldForward, double heldRight, double heldDown,
                             String shardParticle, String sparkParticle,
                             float shardSize, float sparkSize,
                             double shardSpread, double sparkSpread, double sparkRadiusMul,
                             Map<String, String> seriesInk, Map<String, String> myeongInk,
                             Map<String, WeaponAuraGrade> grades) {
        /** 이 등급의 밀도 — 미등록 등급은 null (오라 없음으로 처리) */
        public WeaponAuraGrade grade(String g) {
            return g == null ? null : grades.get(g);
        }

        /** 결정 조각의 색 — 명병 문파색이 등록돼 있으면 그것, 아니면 계열색. 둘 다 없으면 null */
        public String inkFor(String series, String sect) {
            if (sect != null && myeongInk.containsKey(sect)) {
                return myeongInk.get(sect);
            }
            return series == null ? null : seriesInk.get(series);
        }
    }

    /** 무기 오라의 등급 한 칸 — 밀도(결정·반짝이)와 색 덮어쓰기(마병 혈) */
    public record WeaponAuraGrade(int shards, int sparks, int sparkEvery, String inkOverride) {
    }

    /** 병기 전시대 — 든 병기를 땅에 크게 세운 ItemDisplay (weapon_stand). 미등록이면 null */
    public record WeaponStand(boolean enabled, double scale, double rotX, double rotY, double rotZ,
                              double rise, double retrieveRadius, double auraScale,
                              double auraCenterRise) {
    }

    /** 떨어뜨린 병기 자동 확대 — 작은 드롭을 큰 ItemDisplay 로 교체 (dropped_display). 미등록이면 null */
    public record DroppedDisplay(boolean enabled, double scale, double rotX, double rotY, double rotZ,
                                 double rise, double pickupRadius, int lifetimeSeconds,
                                 double auraScale, double auraCenterRise) {
    }

    /**
     * 전용 검기(劍氣) 평타 — 크고 선명한 초록 초승달 검기 ({@code kigi_slash}). 미등록이면 null.
     *
     * <p>기존 무협 참격(작은 획)을 대체한다. 모든 시각 파라미터는 등록부가 준다 — 코드는 읽어서
     * 동작할 뿐이다 (조율자가 재빌드 없이 값만 바꿔 인게임에서 반복 조율한다).
     *
     * @param model         item_model 키 (kigi/arc1 — display.models 의 어떤 모델의 key). 소환 시 1단계
     * @param frameModels   ★ <b>단계 모델</b> — 스윙 중 이 순서로 갈아끼운다 (display.models 의 <b>등록
     *                      이름</b>. key 가 아니다). 마인크래프트의 .mcmeta 텍스처 애니는 <b>전역 시계</b>로
     *                      돌아 소환 순간의 프레임이 매번 제각각이었다 — 그래서 단계를 텍스처가 아니라
     *                      <b>모델</b>로 가르고 코드가 갈아끼운다 ({@code SkillDisplay.advanceFrames}).
     *                      비었거나 1개면 교체가 꺼진다 (가역)
     * @param frameTicks    단계당 틱 — 이 간격으로 다음 단계로 넘어간다 (3단계 × 3 = draw_ticks 9 와 정합)
     * @param applyToTrails 이 basic trail 무기에만 검기를 씌운다 (호 = 검·도·부·겸·봉 sweep)
     * @param replaceStroke true 면 이 무기의 기존 무협 참격 아크·궤적 파티클을 억제한다
     * @param scale         초승달 크기(대략 m) — 크게, 3인칭에서 잘 보이게
     * @param centerHeight  발바닥에서 <b>공전 중심</b> 높이(m) — 몸의 중심
     * @param forward       공전 중심을 앞으로 미는 값(m). 0 이면 몸 한복판이 중심이다
     * @param orbitRadius   ★ <b>공전 반경</b>(m) — 몸 중심에서 초승달까지. 크레센트는 이 반경으로
     *                      몸 주위를 <b>돈다</b> (제자리 자전이 아니다 — 레퍼런스가 그렇다)
     * @param sweepDeg      ★ <b>공전 호각</b> — 몸 주위를 도는 총 각(시작→끝을 클라이언트가 보간)
     * @param tiltDeg       ★ <b>공전면의 기울기</b> — 수평 공전면을 앞축(前) 둘레로 눕힌다 (내려베는 대각)
     * @param rollDeg       정적 롤 오프셋
     * @param bladePitchDeg 날의 눕힘 — 접선축 기준. 90 이면 판이 누워 볼록한 바깥이 정면을 본다
     *                      (0 이면 판이 세로로 서서 ∩ 의 배가 하늘을 본다 — 사용자가 잡은 그 버그)
     * @param drawTicks     휩쓰는(그리는) 시간 — 시작각→끝각 보간 틱
     * @param fadeTicks     사라지는 시간(꼬리부터 수축)
     * @param alternate     true 면 스윙마다 좌↔우 방향을 번갈아
     * @param brightness    발광(block_light = sky_light = 이 값)
     * @param calmHeldAura  true 면 이 무기를 들었을 때 weapon_aura held 방출을 억제 (가역)
     * @param geomParticle  ★ 몸 둘레 호에 쓸 파티클 이름. <b>비우면 안 그린다</b> — 판만으로 간다.
     *                      (판이 못 푸는 등 뒤 각도를 점으로 메운다. 실측: 파티클로 바꿔도 배치를
     *                       안 바꾸면 뒤는 45px 로 그대로다 — 그래서 공전 반경 위에 찍는다)
     * @param geomInk       그 호의 먹빛 이름 (없으면 {@code null} — 파티클 기본색)
     * @param geomSweepDeg  그 호가 무는 각(도). ★ {@code sweepDeg}(판의 공전 보간)와 <b>다른 값</b>이다 —
     *                      한 번 재사용했다가 {@code sweep_deg: 0} 탓에 호가 한 점으로 접혔다 (실측)
     */
    public record KigiSlash(boolean enabled, String medium, String model, List<String> applyToTrails,
                            List<String> applyToClasses,
                            List<String> frameModels, List<String> frameModelsB, int frameTicks,
                            double bandWidth, int bandRows, double bandJitter,
                            int bandSweepTicks, int accentCount,
                            double widthSelfMul, double widthOthersMul, double heightOthersMul,
                            boolean bandHit, double bandHitReach,
                            boolean replaceStroke, double scale, double centerHeight, double forward,
                            double orbitRadius, double sweepDeg, double tiltDeg, double rollDeg,
                            double bladePitchDeg, double yawDeg,
                            int drawTicks, int fadeTicks, String billboard, boolean alternate,
                            int brightness, KigiSpark spark, boolean calmHeldAura,
                            String geomParticle, String geomInk, String geomInkAlt,
                            String geomTemplate, String geomTemplateFps, double geomSweepDeg,
                            double geomStepDeg, boolean plate, boolean follow,
                            String model3d, String model3dAnim,
                            double model3dUp, double model3dYaw, double model3dPitch) {
        /** 이 무기의 basic trail 이 검기를 받는가 (apply_to_trails 에 등록됐는가) */
        public boolean appliesToTrail(String trail) {
            return trail != null && applyToTrails.contains(trail);
        }

        /**
         * ★ 이 무기가 검기를 받는가 — <b>원형(archetype) 축이 trail 축보다 우선한다</b> (2026-07-21 ·
         * 외부 검토 P0). 호 궤적은 검·도뿐 아니라 부(도끼)·봉도 쓰므로, trail 만 보면 도끼가
         * 검압 초승달을 받아 무기군 원형(vfx_primitives.md)이 런타임에서 무너진다.
         * {@code apply_to_classes} 가 있으면 무기 분류로만 판단하고, 없으면 옛 trail 규약(가역).
         */
        public boolean appliesTo(String weaponClass, String trail) {
            if (!applyToClasses.isEmpty()) {
                return weaponClass != null && applyToClasses.contains(weaponClass);
            }
            return appliesToTrail(trail);
        }
    }

    /** 검기의 흰 별 반짝이 — 아크 궤적을 따라 성기게 터진다 (kigi_slash.spark) */
    public record KigiSpark(String particle, int count, double spread, double speed,
                            boolean alongArc) {
    }

    /** 부의 횡참 — 짧고 굵은 밴드. 판정은 궤적에 닿으면 딜 (heavy_slash 절) */
    public record HeavySlash(boolean enabled, List<String> applyToClasses, String ink, String inkAlt,
                             String template,
                             double radius, double sweepDeg, double tiltDeg, double centerHeight,
                             double bandWidth, int bandRows, double bandJitter, int sweepTicks,
                             double stepDeg, int trailDelayTicks,
                             double widthSelfMul, double widthOthersMul, double heightOthersMul,
                             boolean hit, double hitReach, boolean replaceStroke) {
        public boolean appliesTo(String weaponClass) {
            return weaponClass != null && applyToClasses.contains(weaponClass);
        }
    }

    /**
     * 오의의 화려함 — 사용자가 이름을 댄 셋 (다층 궤적 · 운무 · 청백색 폭발감) + 타격.
     *
     * @param layers 다층 궤적 — {@code arcs} 겹의 호가 개시 다음 틱부터 차례로 벌어진다
     * @param mist   운무 — {@code ticks} 틱 동안 몸 둘레에 낮게 깔린다
     */
    public record UltFlourish(String id, Fx bloom, Layers layers, Mist mist, Fx hit) {
    }

    /** 다층 궤적 한 벌 — 겹(arcs) × 점(points) × 점당(per_point). 한 틱에 한 겹씩 벌어진다 */
    public record Layers(String particle, String ink, int arcs, int points, int perPoint,
                         double spread, double extra, double radiusRatio) {
        public boolean present() {
            return particle != null && arcs > 0 && points > 0 && perPoint > 0;
        }
    }

    /** 운무 — 개화가 사는 동안 낮게 깔린다 */
    public record Mist(String particle, int count, double spread, double extra, int ticks,
                       double height) {
        public boolean present() {
            return particle != null && count > 0 && ticks > 0;
        }
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

    /** 원거리 등록부 한 줄 — skill_mechanics.yml ranged.<계열> (사거리·당김·최소거리·탄약) */
    public record Ranged(String weaponClass, double range, int drawTicks, double minRange,
                         String ammo) {
    }

    /**
     * 원거리 사선의 그림 — skill_motion.yml ranged_fx.<계열> (시안 축 · 2026-07-23 B-174).
     * mode: 즉발(현행 — weapon_styles trail 한 겹) · 먹줄(심+테 두 겹 즉발) ·
     * 주행(트레이서가 speed_mpt m/틱으로 날아가고 판정도 도달 틱에 맞춘다 — 화면=판정).
     */
    public record RangedFx(String mode, String coreInk, String rimInk,
                           double step, double speedMpt, double tailM, float size) {
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

    // ══════════ 성장 v3 XP (B-135 단계 4 — cultivation.yml levels.xp_sources) ══════════

    /** XP 배선 여부 — 등급 계수 등록부가 비면 XP 를 내지 않는다 (config 가 스위치다) */
    public boolean xpEnabled() {
        return !xpGradeCoef.isEmpty();
    }

    /** 몹 레벨 — 등록값(npcs/*.yml level) 우선, 없으면 상당 경지의 자격 레벨 (표에 없으면 1) */
    public int mobLevel(Npc npc, String fallbackRealm) {
        if (npc != null && npc.level() > 0) {
            return npc.level();
        }
        String realm = npc != null ? npc.realm() : fallbackRealm;
        return Math.max(1, qualifyingLevel.getOrDefault(realm, 1));
    }

    /**
     * 처치 XP = 몹 레벨 × 등급 계수 — 클래식 고정값 (사용자 확정 2026-07-24):
     * 같은 몹은 언제나 같은 XP. 상황 배수·반복 감쇠·일일 상한 없음 — 사다리 감속이 pacing 이다.
     */
    public int killXp(int mobLevel, String mobGrade) {
        double coef = xpGradeCoef.getOrDefault(mobGrade, xpGradeCoef.getOrDefault("잡졸", 1.0));
        return Math.max(0, (int) Math.round(mobLevel * coef));
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

    // ══════════ 전투 판정 v2 — 공방(攻防) (combat.yml combat_v2 · B-177) ══════════

    /**
     * 전투 v2 등록부 — 명중 = 획 히트박스(판정 없음) · 피해 = max(1, 공격력 − 방어력) × 크리배수.
     * <b>enabled: false 면 어느 판정길도 이 값을 읽지 않는다</b> (v1 그대로 — 단계적 전환 스위치).
     * 수치는 전부 combat.yml 이 정본이다 — 코드는 수치를 지어내지 않는다.
     */
    public record CombatV2(boolean enabled,
                           boolean defenseFromArmor, double defensePerBody,
                           boolean defenseStanceSoak, boolean defenseNpcRealmBase,
                           String bodyAttribute, String senseAttribute, String wisdomAttribute,
                           double chanceBase, double chancePerSense, double chancePerWisdom,
                           double chanceCap, Map<String, Double> chanceByWeapon,
                           double damageBase, double damagePerSense, double damagePerWisdom,
                           Map<String, Double> damageAmpByWeapon) {

        static CombatV2 load(Map<String, Object> sec) {
            Map<String, Object> def = RulesConfig.section(sec, "defense");
            Map<String, Object> crit = RulesConfig.section(sec, "crit");
            return new CombatV2(Boolean.TRUE.equals(sec.get("enabled")),
                    Boolean.TRUE.equals(def.get("from_armor")),
                    num(def.get("per_body")),
                    Boolean.TRUE.equals(def.get("stance_soak")),
                    Boolean.TRUE.equals(def.get("npc_realm_base")),
                    String.valueOf(def.getOrDefault("body_attribute", "체력")),
                    String.valueOf(crit.getOrDefault("sense_attribute", "감각")),
                    String.valueOf(crit.getOrDefault("wisdom_attribute", "지혜")),
                    num(crit.get("chance_base")), num(crit.get("chance_per_sense")),
                    num(crit.get("chance_per_wisdom")), num(crit.get("chance_cap")),
                    doubleMap(crit.get("chance_by_weapon")),
                    num(crit.get("damage_base")), num(crit.get("damage_per_sense")),
                    num(crit.get("damage_per_wisdom")),
                    doubleMap(crit.get("damage_amp_by_weapon")));
        }

        private static double num(Object v) {
            return v instanceof Number n ? n.doubleValue() : 0.0;
        }

        private static Map<String, Double> doubleMap(Object raw) {
            Map<String, Double> out = new LinkedHashMap<>();
            if (raw instanceof Map<?, ?> m) {
                m.forEach((k, v) -> out.put(String.valueOf(k), num(v)));
            }
            return Collections.unmodifiableMap(out);
        }
    }

    public boolean combatV2Enabled() {
        return combatV2.enabled();
    }

    /** 등급 이름 — 판정 등록부(judgment.yml tiers)의 것. v2 의 눈이 재사용한다 (코드는 이름을 짓지 않는다) */
    public String tierName(String tierId) {
        JudgmentEngine.Tier tier = judgment.tierById(tierId);
        return tier == null ? tierId : tier.name();
    }

    public CombatV2 combatV2() {
        return combatV2;
    }

    /**
     * v2 크리 확률 — 기본 0 + 감각(주축) + 지혜(급소를 안다) + 무기별, 상한 chance_cap.
     * 굴림은 호출자가 한다 (액션 RNG — 2d6 아님). 장비 가산은 equipment.yml crit 슬롯 등재 뒤에 붙는다.
     */
    public double critChance(int sense, int wisdom, String weaponClass) {
        double c = combatV2.chanceBase()
                + combatV2.chancePerSense() * sense
                + combatV2.chancePerWisdom() * wisdom
                + combatV2.chanceByWeapon().getOrDefault(weaponClass, 0.0);
        return Math.min(combatV2.chanceCap(), Math.max(0.0, c));
    }

    /** v2 크리 배수 — 기본 1.2 + 감각 + 지혜 + 무기 증강 (장비 증강은 equipment.yml 등재 뒤) */
    public double critMultiplier(int sense, int wisdom, String weaponClass) {
        return combatV2.damageBase()
                + combatV2.damagePerSense() * sense
                + combatV2.damagePerWisdom() * wisdom
                + combatV2.damageAmpByWeapon().getOrDefault(weaponClass, 0.0);
    }

    /**
     * v2 타격 — <b>명중은 기하가 이미 정했다</b> (band_hit). 판정 없음, 언제나 타격.
     * 공격력 = 무기 위력 + 기술 숙련 + 능력치(병기 축) + 격 보정 — 4항 (사용자 2026-07-24:
     * 무공 위력표·경지 격차·내공 고갈 보정은 v2 에 없다). 하한 1 → 크리배수 → 개안 절반.
     * 크리 여부·배수는 호출자가 굴려 온다 (엔진은 난수를 만들지 않는다: 테스트 가능성).
     * 등급 문법(성공/대성공)은 판정 등록부의 것을 재사용한다 — 급소 연출(critReserve)이 같은 문을 지난다.
     */
    public Strike strikeV2(Cast cast, int mastery, int attrBonus, int defense,
                           boolean crit, double critMult) {
        int attackPower = cast.weaponPower() + mastery + attrBonus + cast.gradeBonus();
        double dmg = Math.max(1, attackPower - defense) * (crit ? critMult : 1.0);
        if (cast.halved()) {
            dmg = Math.ceil(dmg / 2.0);                       // 개안 — 불완전 시전 (위력 절반)
        }
        String tierId = crit ? "critical_success" : "success";
        JudgmentEngine.Tier tier = judgment.tierById(tierId);
        return new Strike(0, attackPower - defense, tierId,
                tier == null ? tierId : tier.name(),   // 이름은 등록부의 것 — 코드는 짓지 않는다
                true, Math.max(1, (int) Math.round(dmg)));
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
     * 순간 사건(판정·격 태세 전환·경공)의 한 줄이 statusBar 에 덮이지 않고 머무는 시간 —
     * 등록부({@code skill_motion.yml hud.flash_read_ticks})가 정본이다 (B-116: 0.2초는 못 읽는다).
     */
    public int hudFlashTicks() {
        return hudFlashTicks;
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

    /** 격의 사다리 — rank 순 (사다리를 나란히 보여 주는 자리: {@code /혼천 사다리}) */
    public List<GradeMotion> gradeLadder() {
        List<GradeMotion> out = new ArrayList<>(gradeMotion.values());
        out.sort(java.util.Comparator.comparingInt(GradeMotion::rank));
        return out;
    }

    /**
     * 먹빛 — 등록부의 이름({@code 회백·청회·청록·옥·청백·먹·혈})을 색으로.
     * <b>등록되지 않은 이름은 null</b> — 그러면 바닐라 색이 그대로 나간다 (조용한 색 발명 금지).
     */
    public InkColor inkColor(String name) {
        return name == null ? null : inks.get(name);
    }

    /** 등록된 먹빛 전부 — 눈({@code /혼천 사다리})과 감사가 나란히 세운다 */
    public java.util.Collection<InkColor> inkColors() {
        return inks.values();
    }

    /** 무기 오라 등록부 — 미등록이면 null (SkillListener 는 이때 오라를 뿌리지 않는다) */
    public WeaponAura weaponAura() {
        return weaponAura;
    }

    /** 병기 전시대 등록부 — 미등록이면 null (전시대 명령이 거절한다) */
    public WeaponStand weaponStand() {
        return weaponStand;
    }

    /** 떨어뜨린 병기 자동 확대 등록부 — 미등록이면 null (드롭 확대 안 함) */
    public DroppedDisplay droppedDisplay() {
        return droppedDisplay;
    }

    /**
     * 전용 검기 평타 등록부 — 미등록이면 null (SkillListener 는 이때 기존 무협 참격을 그대로 그린다).
     * <b>인게임 오버라이드가 있으면 그것이 이긴다</b> ({@code /혼천 검기}).
     */
    public KigiSlash kigiSlash() {
        return kigiSlashOverride != null ? kigiSlashOverride : kigiSlashConfig;
    }

    /** 부의 횡참 등록부 — 없으면 null (조용히 꺼진다) */
    public HeavySlash heavySlash() {
        return heavySlashConfig;
    }

    /** 등록부가 적어 둔 원본 — 오버라이드와 무관하다 (되돌릴 자리이자 대조군) */
    public KigiSlash kigiSlashConfig() {
        return kigiSlashConfig;
    }

    /** 지금 화면에 도는 값이 사람이 민 값인가 (오버라이드가 걸렸는가) */
    public boolean kigiSlashOverridden() {
        return kigiSlashOverride != null;
    }

    /** 검기 값을 <b>메모리에서만</b> 갈아끼운다 — 다음 스윙부터 반영. config 파일은 안 건드린다 */
    public void setKigiSlash(KigiSlash cfg) {
        this.kigiSlashOverride = cfg;
    }

    /** 등록부(config 파일) 값으로 되돌린다 — 인게임에서 민 것은 전부 버린다 */
    public void resetKigiSlash() {
        this.kigiSlashOverride = null;
    }

    // ══════════ 핫 리로드의 눈 — 무엇이 실렸고 무엇이 달라졌나 ══════════

    /**
     * <b>모션 등록부 인구조사</b> — 지금 메모리에 실린 등록부의 크기.
     *
     * <p>{@code /혼천 모션 재적재} 가 리로드 전후로 이것을 재서 <b>사람에게 요약을 보여 준다</b>.
     * "재적재했다"는 말은 증거가 아니다 — <b>숫자가 달라진 것</b>이 증거다.
     */
    public java.util.Map<String, Integer> motionCensus() {
        java.util.Map<String, Integer> census = new LinkedHashMap<>();
        census.put("모션", skillMotion.size());
        census.put("격모션", gradeMotion.size());
        census.put("형태모션", formMotion.size());
        census.put("오의모션", ultimateMotion.size());
        census.put("사건모션", eventMotion.size());
        census.put("궤적", trajectories.size());
        census.put("3D모델", displayModels.size());
        census.put("3D모션", displayMotions.size());
        census.put("먹빛", inks.size());
        return census;
    }

    /**
     * 두 검기 등록부의 <b>다른 칸만</b> 골라 낸다 ({@code 키: 옛값 → 새값}).
     *
     * <p>레코드 성분을 반사로 훑는다 — {@link KigiSlash} 에 칸이 늘어도 이 눈이 따라온다
     * (손으로 적은 목록은 반드시 언젠가 뒤처진다).
     */
    public static List<String> diffKigiSlash(KigiSlash before, KigiSlash after) {
        List<String> out = new ArrayList<>();
        if (before == null || after == null) {
            if (before != after) {
                out.add("kigi_slash: " + (before == null ? "없음 → 생김" : "있음 → 사라짐"));
            }
            return out;
        }
        for (java.lang.reflect.RecordComponent rc : KigiSlash.class.getRecordComponents()) {
            try {
                Object a = rc.getAccessor().invoke(before);
                Object b = rc.getAccessor().invoke(after);
                if (!Objects.equals(a, b)) {
                    out.add(rc.getName() + ": " + a + " → " + b);
                }
            } catch (ReflectiveOperationException unreadable) {
                out.add(rc.getName() + ": 읽지 못했다 (" + unreadable.getMessage() + ")");
            }
        }
        return out;
    }

    /**
     * item_model {@code key} 를 가진 모델의 등록부 이름(id)을 돌려준다 — 없으면 null.
     * 검기 평타는 등록부 이름이 아니라 <b>키</b>(honcheon:kigi/arc)로 모델을 가리키므로 이 역참조가 필요하다.
     */
    public String displayModelNameByKey(String key) {
        if (key == null) {
            return null;
        }
        for (DisplayModel m : displayModels.values()) {
            if (key.equals(m.key())) {
                return m.id();
            }
        }
        return null;
    }

    /** 오의의 화려함 — by_id 가 있으면 그것, 없으면 default (등록되지 않은 오의는 default 를 쓴다) */
    public UltFlourish ultimateFlourish(String id) {
        UltFlourish f = ultFlourish.get(id);
        return f != null ? f : ultFlourishDefault;
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

    /** 원거리 등록부 — 없는 계열은 null (지어내지 않는다: null 이면 호출자가 물러선다) */
    public Ranged ranged(String weaponClass) {
        return rangedSpecs.get(weaponClass);
    }

    /** 사선의 그림 — 없으면 null (즉발·weapon_styles 폴백) */
    public RangedFx rangedFx(String weaponClass) {
        return rangedFx.get(weaponClass);
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

    /**
     * 등록부가 아는 <b>모든</b> 형체 — 등록 순서 그대로 (LinkedHashMap).
     *
     * <p>{@code /혼천 획시험} 이 이것을 한 줄로 세운다. <b>코드가 목록을 짓지 않는다</b> —
     * 시험대에 서는 것은 등록부가 부르는 것과 정확히 같은 집합이어야 하고, 그래야 시험이 거짓말을 안 한다.
     */
    public java.util.Collection<DisplayModel> displayModels() {
        return displayModels.values();
    }

    public DisplayMotion displayMotion(String id) {
        return id == null ? null : displayMotions.get(id);
    }

    /** 참격선 모션 전수 — {@code /혼천 획위치} 가 이것을 나열한다 (코드가 목록을 짓지 않는다) */
    public java.util.List<DisplayMotion> slashMotions() {
        return displayMotions.values().stream().filter(DisplayMotion::isSlash).toList();
    }

    /**
     * <b>획이 서는 자리</b> — 등록부({@code display.stroke_origin})가 정한다.
     *
     * <p>등록되지 않은 모션은 {@code default} 칸으로 선다. {@code default} 조차 없으면
     * <b>몸 밖으로 밀어낸 최소값</b>을 돌려준다 — 등록부가 비어도 획이 <b>몸 안에서는 안 나온다</b>
     * (폴백이 병을 되살리지 않게).
     */
    public StrokeOrigin strokeOrigin(String motionId) {
        StrokeOrigin o = motionId == null ? null : strokeOrigins.get(motionId);
        if (o != null) {
            return o;
        }
        StrokeOrigin def = strokeOrigins.get("default");
        return def != null ? def
                : new StrokeOrigin("default", strokeLimits.bodyRadius() + strokeLimits.clearance(),
                        1.35, 0.0, false);
    }

    public Map<String, StrokeOrigin> strokeOrigins() {
        return strokeOrigins;
    }

    public StrokeLimits strokeLimits() {
        return strokeLimits;
    }

    /** ★ 스윙 넷 — 획이 도는 각 (등록부가 쥔다. 코드가 각을 지어내지 않는다) */
    public SwingArcs swingArcs() {
        return swingArcs;
    }

    public SlashEye slashEye() {
        return slashEye;
    }

    /**
     * <b>이 계열의 몸이 앞으로 가는 거리</b> (m) — script 의 양수 lunge 합 × {@code lunge_to_meters}.
     *
     * <p>{@code lunge} 는 <b>틱당 속도</b>(블록)다. 지면 마찰(≈0.6/틱)이 먹으면 총 이동 ≈ 2.5 × v
     * (환산 상수는 등록부가 쥔다 — 코드가 지어내지 않는다). 음수 beat(되돌아옴)는 <b>안 센다</b>:
     * 이 눈이 재는 것은 "앞으로 얼마나 <b>나갔나</b>"이지 순 변위가 아니다.
     */
    public double lungeMeters(String weaponClass) {
        Body b = bodyByClass.get(weaponClass);
        if (b == null) {
            return 0.0;
        }
        double sum = 0.0;
        if (b.scripted()) {
            for (Beat beat : b.script()) {
                sum += Math.max(0.0, beat.lunge());
            }
        } else {
            sum = Math.max(0.0, b.lunge());
        }
        return sum * swingArcs.lungeToMeters();
    }

    /**
     * <b>【눈】 이 계열의 기본 타격은 참격인가 찌르기인가.</b> 어긋나면 사유를, 아니면 null.
     *
     * <p>기동 때 한 번 짖는다(아래 {@link #slashEyeReport}). 정적으로도 같은 축이 선다 —
     * {@code tools/motion_audit.py} 축 ⑬ 가 <b>같은 세 수</b>를 잰다 (두 개의 진실을 만들지 않는다).
     */
    public String slashFault(String weaponClass) {
        Basic basic = basicStrike.get(weaponClass);
        if (basic == null || slashEye.exemptTrails().contains(basic.trail())) {
            return null;   // 활·무관·짐승 / 찌르는 것은 찌른다 (면제는 등록부가 소리내어 청구했다)
        }
        String id = swingArcs.strokeAt(weaponClass, 0);
        SwingArc arc = swingArcs.stroke(id);
        double deg = arc == null ? 0.0 : arc.arcDeg(swingArcs.tuning().arc());
        double m = lungeMeters(weaponClass);
        // 무거운 손은 몸을 싣는다 (도끼는 들이받는 것이 아니다) — 상한이 갈린다.
        // 상한을 한 칸으로 두면 그 칸이 **옛 검의 전진과 같아져** 눈이 아무것도 못 잡았다 (눈의 시험 ⑤)
        double cap = swingArcs.heavyClasses().contains(weaponClass)
                ? slashEye.maxLungeHeavyM() : slashEye.maxLungeM();
        double ratio = m <= 1.0e-6 ? Double.POSITIVE_INFINITY : deg / m;
        List<String> bad = new ArrayList<>();
        if (deg < slashEye.minArcDeg()) {
            bad.add(String.format("호각 %.0f도 < %.0f도 (쓸지 않았다 — 자라기만 한다)",
                    deg, slashEye.minArcDeg()));
        }
        if (m > cap) {
            bad.add(String.format("전진 %.2fm > %.2fm (베는 것이 아니라 들이받는다)", m, cap));
        }
        if (ratio < slashEye.minRatio()) {
            bad.add(String.format("참격비 %.0f도/m < %.0f (찌르기 쪽이다)", ratio, slashEye.minRatio()));
        }
        return bad.isEmpty() ? null : String.join(" · ", bad);
    }

    /** 기동 때의 한 줄 — 계열마다 호각·전진·참격비. <b>안 짖으면 없느니만 못한 눈이다</b> */
    public List<String> slashEyeReport() {
        List<String> out = new ArrayList<>();
        for (String cls : basicStrike.keySet()) {
            Basic basic = basicStrike.get(cls);
            String id = swingArcs.strokeAt(cls, 0);
            SwingArc arc = swingArcs.stroke(id);
            double deg = arc == null ? 0.0 : arc.arcDeg(swingArcs.tuning().arc());
            double m = lungeMeters(cls);
            double ratio = m <= 1.0e-6 ? Double.POSITIVE_INFINITY : deg / m;
            String fault = slashFault(cls);
            boolean exempt = slashEye.exemptTrails().contains(basic.trail());
            out.add(String.format("%-4s %s  호각 %3.0f도 · 전진 %.2fm · 참격비 %s%s",
                    cls, basic.trail(),
                    // ★ (long) 캐스트가 여기 있었다 — %f 에 long 을 먹이면 IllegalFormatConversionException 이
                    //   터지고, 이 눈이 onEnable 에서 불리므로 **플러그인 전체가 안 켜졌다** (팩·나루·HUD 전부 죽었다).
                    //   눈 하나가 세계를 죽였다. 서식 문자와 인자의 짝은 컴파일러가 안 봐 준다.
                    deg, m, Double.isInfinite(ratio) ? "∞" : String.format("%.0f", ratio),
                    exempt ? "  [면제 — 찌르는 것은 찌른다]"
                            : fault == null ? "  ✔ 참격" : "  ✖ " + fault));
        }
        return out;
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

    /** 헛박자 신호 소리 (basic_strike.whiff) — 등록부에 없으면 null (구판대로 조용히 버린다) */
    public Sfx basicWhiff() {
        return basicWhiff;
    }

    /** 타격 피드백 등록부 (hit_feedback) — 없으면 enabled=false 로 온다 (옛 동작 그대로) */
    public HitFx hitFx() {
        return hitFx;
    }

    public int basicCooldownTicks() {
        return basicCooldownTicks;
    }

    // ══════════ 타격의 순간 — 멈춤 · 밀림 · 흔들림 (combat.yml impact) ══════════

    /** 맞는 쪽의 몸이 말하는 규칙 한 벌. 등록부가 없으면 {@code enabled=false} — 옛 동작 그대로 */
    public Impact impact() {
        return impact;
    }

    /** 타격 허용의 문 (combat.yml strike_admission · B-119) — 누가 맞을 수 있는가. 기본: 전부 허용 */
    public StrikeAdmission admission() {
        return admission;
    }

    @SuppressWarnings("unchecked")
    private static Impact loadImpact(Map<String, Object> im) {
        if (im.isEmpty() || !Boolean.TRUE.equals(im.getOrDefault("enabled", Boolean.TRUE))) {
            return Impact.OFF;
        }
        Map<String, Object> hs = RulesConfig.section(im, "hitstop");
        Map<String, Integer> byGrade = new LinkedHashMap<>();
        if (hs.get("by_grade") instanceof Map<?, ?> bg) {
            ((Map<String, Object>) bg).forEach((k, v) -> byGrade.put(k, RulesConfig.intValue(v)));
        }
        Map<String, Object> kb = RulesConfig.section(im, "knockback");
        Map<String, Object> sh = RulesConfig.section(im, "shake");
        Map<String, Object> bt = RulesConfig.section(im, "basic_startup");
        return new Impact(true,
                !hs.isEmpty() && Boolean.TRUE.equals(hs.getOrDefault("enabled", Boolean.TRUE)),
                Collections.unmodifiableMap(byGrade),
                Boolean.TRUE.equals(hs.get("freeze_target")),
                Boolean.TRUE.equals(hs.get("attacker_hold")),
                intOr(hs.get("max_ticks"), 8),
                !kb.isEmpty() && Boolean.TRUE.equals(kb.getOrDefault("enabled", Boolean.TRUE)),
                dblOr(kb.get("base"), 0.0), dblOr(kb.get("per_qi"), 0.0),
                dblOr(kb.get("per_damage"), 0.0), dblOr(kb.get("lift"), 0.0),
                dblOr(kb.get("max"), 1.0), Boolean.TRUE.equals(kb.get("applies_to_players")),
                !sh.isEmpty() && Boolean.TRUE.equals(sh.getOrDefault("enabled", Boolean.TRUE)),
                dblOr(sh.get("base_degrees"), 0.0), dblOr(sh.get("per_qi"), 0.0),
                dblOr(sh.get("per_damage"), 0.0), dblOr(sh.get("max_degrees"), 0.0),
                intOr(sh.get("ticks"), 3),
                !bt.isEmpty() && Boolean.TRUE.equals(bt.getOrDefault("enabled", Boolean.TRUE)),
                dblOr(bt.get("reach_grace"), 0.0), Boolean.TRUE.equals(bt.get("fx")));
    }

    /**
     * <b>타격의 순간</b> — 맞는 쪽의 몸이 말하는 세 축(멈춤 · 밀림 · 흔들림) + 무공 없는 손의 시간 구조.
     *
     * <p>수치는 한 칸도 코드에 없다 ({@code combat.yml impact}). 등록부가 통째로 없으면 {@link #OFF} —
     * 그러면 이 세계는 <b>이 패스 이전의 동작으로 정확히 돌아간다</b> (되돌릴 수 있는 변경이라는 뜻이다).
     */
    public record Impact(
            boolean enabled,
            boolean hitstopEnabled, Map<String, Integer> hitstopByGrade,
            boolean freezeTarget, boolean attackerHold, int hitstopMax,
            boolean knockEnabled, double knockBase, double knockPerQi, double knockPerDamage,
            double knockLift, double knockMax, boolean knockPlayers,
            boolean shakeEnabled, double shakeBase, double shakePerQi, double shakePerDamage,
            double shakeMax, int shakeTicks,
            boolean basicStartup, double reachGrace, boolean basicFx) {

        static final Impact OFF = new Impact(false, false, Map.of(), false, false, 0,
                false, 0, 0, 0, 0, 0, false, false, 0, 0, 0, 0, 0, false, 0, false);

        /** 이 격에 맞으면 몇 틱이 얼어붙는가. 등록되지 않은 격은 0 (얼지 않는다 — 지어내지 않는다) */
        public int hitstopTicks(String grade) {
            if (!enabled || !hitstopEnabled) {
                return 0;
            }
            return Math.min(hitstopMax, hitstopByGrade.getOrDefault(grade, 0));
        }

        /** 밀림의 크기 — 격과 피해가 함께 민다. {@code max} 가 못이다 (연출이 위치를 훔치지 못하게) */
        public double knockback(int qiPower, double damage) {
            if (!enabled || !knockEnabled) {
                return 0.0;
            }
            return Math.min(knockMax, knockBase + knockPerQi * qiPower + knockPerDamage * damage);
        }

        /** 흔들림의 각(도) — 맞은 쪽의 시야. {@code max_degrees} 가 pitch 벽(±90)에서 멀리 떨어뜨린다 */
        public double shakeDegrees(int qiPower, double damage) {
            if (!enabled || !shakeEnabled) {
                return 0.0;
            }
            return Math.min(shakeMax, shakeBase + shakePerQi * qiPower + shakePerDamage * damage);
        }
    }

    /** 판정의 눈 — 【디버그】 히트박스의 자·목표 표시·산수 로그 (등록부가 예산을 정한다) */
    public Eye eye() {
        return eye;
    }

    /** party.yml {@code mc.friendly_fire.스킬 = 면제} — 무공의 <b>휩쓸림</b>은 아군을 베지 않는다 */
    public boolean spareAllies() {
        return spareAllies;
    }

    /** party.yml {@code mc.friendly_fire.오의_광역 = 예외} — <b>오의의 광역은 아군도 담는다</b> */
    public boolean ultimateSweepsAllies() {
        return ultimateSweepsAllies;
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
    /**
     * @param anchor 성장·소멸의 <b>고정점</b>. {@code "head"} = 머리(+X 끝)를 붙박고 꼬리가 자란다/지워진다.
     *               null = 원점(=모델의 기하 중심)에서 대칭으로 자란다 (고리·판·덩이).
     *               <p><b>모델은 중심에 서고, 고정점은 코드가 옮긴다</b> — 모델에 오프셋을 박으면
     *               그 편향을 모든 모션이 물려받는다 (2026-07: 획이 시전자 옆구리에서 나오던 병).
     */
    public record DisplayModel(String id, String key, String base, String fallback, float[] size,
                               boolean useHeld, String anchor) {
        /** 머리 고정인가 — 참격선이 그렇다 (그리면 머리에서 자라고, 지우면 꼬리부터 사라진다) */
        public boolean headAnchored() {
            return "head".equals(anchor);
        }
    }

    /**
     * <b>획이 서는 자리</b> — 시전자의 <b>발</b>을 원점으로, 그 몸을 기준으로 잰다.
     *
     * <p><b>왜 등록부인가</b>: 예전엔 코드가 획을 <b>시전자의 눈</b>에 세웠다
     * ({@code eyeLocation − 0.25}, 앞으로 미는 값 없음). 그래서 획이 몸을 관통했고, 1인칭에서는
     * 카메라가 획의 안쪽에 있어 <b>제 검의 궤적이 제 눈에 안 보였다</b>. 자리는 눈으로 맞출 값이므로
     * 코드가 아니라 등록부가 쥔다 ({@code /혼천 획위치} 가 인게임에서 밀고 당긴다).
     *
     * @param forward  시선(수평)으로 앞으로 미는 거리 (m) — 몸을 뚫지 않게. <b>1인칭의 값이 여기 있다</b>
     * @param height   발바닥에서 잰 <b>절대</b> 높이 (m) — 눈(1.62)이 아니라 어깨(1.35)가 팔이 지나는 자리다
     * @param lateral  <b>오른쪽</b>으로 미는 거리 (m) — 바닐라 플레이어는 오른손잡이다 (음수 = 왼쪽)
     * @param centered <b>몸에 겹치는 것이 옳다</b>는 선언 (고리). 몸 안 검사에서 면제된다 —
     *                 면제는 <b>등록부가 소리내어 청구</b>해야 한다 (코드가 예외를 지어내지 않는다)
     */
    public record StrokeOrigin(String motion, double forward, double height, double lateral,
                               boolean centered) {
    }

    /**
     * 자리의 못 — <b>어떤 값을 적어도 획은 몸 밖에 선다.</b>
     *
     * @param bodyRadius      【실측】 플레이어 히트박스 폭 0.6m ÷ 2 — 이 안은 몸이다
     * @param clearance       몸 밖으로 더 밀어내는 여유
     * @param maxHeight       【실측】 플레이어의 키 1.8m — 발~키 사이가 '몸의 높이'다
     * @param forwardMaxRatio 앞으로 미는 거리 ≤ 획 길이 × 이 값 (너무 밀면 <b>허공에 뜬 판자</b>다)
     */
    public record StrokeLimits(double bodyRadius, double clearance, double maxHeight,
                               double forwardMaxRatio) {
        /** 몸 밖의 첫 자리 — 이 아래로는 어떤 등록값도 내려가지 못한다 */
        public double minForward() {
            return bodyRadius + clearance;
        }
    }

    // ══════════ ★ 스윙 넷 — 획이 **도는** 각 (찌르기 → 베기) ══════════

    /**
     * <b>한 획의 스윙</b> — 시작 각에서 끝 각으로 <b>쓸고 지나간다</b>.
     *
     * <p><b>【왜 이것이 생겼나】</b> 예전 참격선은 각을 한 번 세우고 {@code scale.x} 만 키웠다 —
     * 즉 <b>제자리에서 길어지는 판자</b>였다 (각이동 0도). 사람은 그것을 <b>찌르기</b>라고 읽는다.
     * ItemDisplay 의 Transformation 은 <b>회전도 보간된다</b>(client slerp) — 시작 각과 끝 각만 주면
     * 클라이언트가 그 사이를 <b>호로 이어 준다</b>. 엔티티는 하나 그대로다 (예산 0 증가).
     *
     * <p><b>도는 것은 검이지 카메라가 아니다</b> — {@code setRotation}(사람의 시야)은 손대지 않았다.
     *
     * @param yaw   [시작, 끝] (도) 좌우로 쓴다. <b>히트박스 부채꼴 밖으로 못 나간다</b> (불변식 ㅂ —
     *              {@code SkillDisplay} 가 깎고 소리내어 짖는다)
     * @param pitch [시작, 끝] (도) 위아래로 쓴다. <b>자유다</b> — 호 히트박스는 높이를 안 본다
     * @param roll  [시작, 끝] (도) 정면에서 <b>시계바늘처럼</b> 도는 각 — 참격의 인상은 대부분 여기서 나온다
     * @param rise  [시작, 끝] (m) 획이 오르내리는 높이 (올려베기 −→+ · 내려베기 +→−)
     * @param fan   파티클이 훑는 부채꼴의 몫 (0~1). <b>1 을 넘지 못한다</b>
     * @param bow   파티클 궤적의 활(m) — 중간이 부푸는 정도. 직선이 아니라 <b>호</b>로 보이게 하는 값
     */
    public record SwingArc(String id, double[] yaw, double[] pitch, double[] roll, double[] rise,
                           double fan, double bow) {
        /** 이 획이 <b>실제로 돌아간 각</b> (도) — 세 축의 합이 아니라 합성 회전의 각이다 */
        public double arcDeg(double scale) {
            org.joml.Quaternionf a = quat(0, scale);
            org.joml.Quaternionf b = quat(1, scale);
            return Math.toDegrees(a.difference(b, new org.joml.Quaternionf()).angle());
        }

        /** @param end 0 = 시작 각 · 1 = 끝 각 */
        public org.joml.Quaternionf quat(int end, double scale) {
            return new org.joml.Quaternionf()
                    .rotateY((float) Math.toRadians(yaw[end] * scale))
                    .rotateX((float) Math.toRadians(pitch[end] * scale))
                    .rotateZ((float) Math.toRadians(roll[end] * scale));
        }
    }

    /** 인게임에서 미는 손잡이 ({@code /혼천 스윙}) — <b>이런 값은 눈으로 봐야 정해진다</b> */
    public record SwingTuning(double arc, double rise, double bow, double lunge) {
        SwingTuning from(Map<String, Object> t) {
            return new SwingTuning(dblOr(t.get("arc_scale"), 1.0), dblOr(t.get("rise_scale"), 1.0),
                    dblOr(t.get("bow_scale"), 1.0), dblOr(t.get("lunge_scale"), 1.0));
        }
    }

    /**
     * 스윙 등록부 — 넷과 그 리듬.
     *
     * <p><b>【함정 ②】 cycle 은 콤보가 아니다.</b> 입력 창도 버퍼도 없고, 우클릭·웅크림·달림의 뜻을
     * 하나도 안 바꾼다. 연타하면 <b>획의 방향만</b> 번갈아 바뀐다 — 그림의 리듬이지 입력의 문법이 아니다.
     *
     * @param heavyClasses 내려베는 계열 — 이 손의 1타는 {@code heavy} 획으로 시작한다.
     *                     ('강공격' 입력 수단이 이 서버에 <b>없다</b> — 지어내지 않았다)
     */
    public record SwingArcs(boolean enabled, double maxArcDeg, int resetTicks, double lungeToMeters,
                            List<String> cycle, String heavy, List<String> heavyClasses,
                            Map<String, SwingArc> strokes, SwingTuning tuning) {
        /** 이 계열의 {@code n} 번째 타가 쓰는 획 — <b>그림만의 순번이다</b> */
        public String strokeAt(String weaponClass, int n) {
            if (!enabled || cycle.isEmpty()) {
                return null;
            }
            if (heavy != null && heavyClasses.contains(weaponClass) && Math.floorMod(n, 3) == 0) {
                return heavy;   // 내려베는 손은 1타를 위에서 아래로 시작한다 (swings.tilt 45~70 의 몸)
            }
            return cycle.get(Math.floorMod(n, cycle.size()));
        }

        public SwingArc stroke(String id) {
            return id == null ? null : strokes.get(id);
        }
    }

    /**
     * <b>【눈】 이것은 참격인가 찌르기인가.</b>
     *
     * <p>찌르기 = 전진이 크고 각이 작다 · 참격 = 각이 크고 전진이 작다. 두 수를 재고 그 비를 본다.
     * <b>호(弧) 궤적을 청구한 계열만</b> 잰다 — 창·암기는 원래 찌르고 던진다 (면제는 등록부가 청구한다).
     */
    public record SlashEye(double minArcDeg, double maxLungeM, double maxLungeHeavyM,
                           double minRatio, List<String> exemptTrails) {
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
