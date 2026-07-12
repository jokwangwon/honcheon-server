package com.honcheon.mvt;

import com.honcheon.core.rules.ProgressionEngine;
import com.honcheon.core.rules.RulesConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 경공(輕功) — <b>무협의 몸이 땅을 딛는 법</b>. 등록부와 산술만 갖는다 (몸·틱·파티클은
 * {@link GyeonggongListener} 가 진다 — {@code Growth} ↔ {@code SkillListener} 와 같은 분업).
 *
 * <p><b>왜 이 클래스가 필요했나.</b> {@code gap_audit.md §3-①} 이 이렇게 적었다:
 * <i>"경공은 설계·config 에 풍부하게 있다 … 그런데 자바 프로덕션 코드에 '경공'이 0건이다."</i>
 * 그래서 절정 고수도 삼류도 <b>같은 속도로 걷고 같은 높이에서 죽었다.</b> 그리고 성장 축의
 * 신법(발)은 <b>회피 판정만</b> 샀다 — 구간을 부어도 <b>몸이 가벼워지지 않았다.</b> 축의 절반이 비어 있었다.
 *
 * <p><b>세 계약</b> (전부 등록부에서 온다 — 이 클래스는 수치를 하나도 갖지 않는다):
 * <ol>
 *   <li><b>경지가 천장을 정한다.</b> {@code internal_energy.yml realm_gates} 가 '경신'을 <b>일류</b>에
 *       열었다 — 삼류·이류는 내력이 없다(풀 0). <b>그들은 바닐라처럼 걷는다.</b> 그것이 개화의 보상이다.</li>
 *   <li><b>신법(민첩)이 그 천장 안에서 자란다.</b> {@code gyeonggong.yml growth.per_agility} —
 *       민첩 <b>실수치</b>를 쓴다 (화후 규칙: 판정은 정수, <b>자원은 실수</b>. 몸은 자원이다).</li>
 *   <li><b>내력을 태운다. 그리고 끊기면 떨어진다</b> — 낙법({@link #fallGrace})은
 *       <b>기가 도는 몸에만</b> 듣는다 ({@code qi.depleted.grace_multiplier = 0}).</li>
 * </ol>
 *
 * <p><b>쿨다운은 하나도 없다.</b> 이 프로젝트는 시계로 막지 않는다 — 막는 것은 <b>단전·경지·갑옷</b>이다.
 *
 * <p>보법은 지어내지 않았다: {@code skills.yml} 의 {@code category: 경공} 넷(매화보·제운종·취팔선보·답운종)을
 * 그대로 읽고, 거리·수직·변칙은 {@code skill_mechanics.yml} 에서 읽는다. 검산: {@code tools/gyeonggong_audit.py}.
 */
public final class Gyeonggong {

    private static Gyeonggong instance;

    /** 경지 천장 — 다섯 값 전부 '여기까지'다 (수련이 그 안에서 자란다) */
    public record Ceiling(double speed, double jump, double fallGrace, double wallClimb, boolean waterRun) {

        static final Ceiling ZERO = new Ceiling(0, 0, 0, 0, false);
    }

    /** 문파 보법 — skills.yml(카탈로그) + skill_mechanics.yml(거리·수직·변칙) 이 정본 */
    public record Bobeop(String id, String name, String faction, double distance,
                         boolean vertical, boolean irregular) {
    }

    /** 심법의 결 — simbeop.yml qi_profile.연비 / passive 의 기계 키 (gyeonggong.yml simbeop_effects) */
    public record SimEffect(int discount, double distanceMult, boolean fallImmune) {

        static final SimEffect NONE = new SimEffect(0, 1.0, false);
    }

    /**
     * <b>이 몸의 경공</b> — 경지(천장) × 신법(성장) × 심법(연비) × 보법(발) × 갑옷(무게)의 결과.
     *
     * @param upkeepInterval 유지비 1 을 무는 주기(틱). 심법 할인이 <b>이것을 늘린다</b> (곤륜 = 3배)
     */
    public record Profile(boolean open, double speed, double jump, double fallGrace,
                          double wallClimb, boolean waterRun, double dash,
                          int upkeepInterval, boolean fallImmune, double dodgeBiasDeg,
                          String blockedBy) {

        /** 경공이 아예 안 열리는 몸 (개화 전 · 철갑) — 유지비를 물 일이 없다 */
        static Profile closed(String why) {
            return new Profile(false, 0, 0, 0, 0, false, 0, Integer.MAX_VALUE, false, 0, why);
        }

        public boolean canWallClimb() {
            return open && wallClimb > 0;
        }
    }

    // ─── 등록부 (전부 config 판독) ───

    private final String band;                       // 경신 — 이 모드가 무는 격
    private final Map<String, List<String>> realmGates = new LinkedHashMap<>();
    private final Map<String, Ceiling> ceilings = new LinkedHashMap<>();
    private final Map<String, Double> perAgility = new LinkedHashMap<>();
    private final Map<String, Bobeop> bobeops = new LinkedHashMap<>();
    private final Map<String, SimEffect> simEffects = new LinkedHashMap<>();

    private final int upkeepCost;
    private final int upkeepInterval;
    private final int waterUpkeepMult;
    private final int leapCost;
    private final int wallKickCost;
    private final int retreatCost;
    private final int closeInCost;
    private final int softenCost;
    private final double metersPerQi;
    private final int costFloor;
    private final double depletedGrace;
    private final int depletedWindow;
    private final int idleOffTicks;
    private final int bandLow;
    private final int bandHigh;

    // 보법 없는 몸 / 숙련 보너스
    private final double baseDash;
    private final double masteryBonusM;
    private final double irregularBias;

    // 갑옷 — equipment.yml 이 정본, 바닐라 흉갑 매핑만 여기
    private final Map<String, String> armorByChest = new LinkedHashMap<>();
    private final String armorNone;
    private final Set<String> armorBlocking = new LinkedHashSet<>();
    private final Map<String, Integer> armorDodgePenalty = new LinkedHashMap<>();
    private final double dodgePenaltyScale;

    // 마인크래프트의 상수 · 물리값 — 코드가 지어내지 않는다 (gyeonggong.yml mc · wall · water · dash)
    private final Map<String, Double> mc = new LinkedHashMap<>();
    private final Map<String, Double> wall = new LinkedHashMap<>();
    private final Map<String, Double> water = new LinkedHashMap<>();
    private final Map<String, Double> dash = new LinkedHashMap<>();

    private final Map<String, Object> vfx;
    private final Map<String, Object> messages;
    private final double cullBeyond;

    @SuppressWarnings("unchecked")
    private Gyeonggong(Path cfg) {
        Map<String, Object> root = RulesConfig.load(cfg.resolve("gyeonggong.yml"));
        Map<String, Object> mode = RulesConfig.section(root, "mode");
        this.band = String.valueOf(mode.getOrDefault("band", "경신"));
        this.idleOffTicks = (int) num(mode.get("idle_off_ticks"), 40);

        // ── 경지 게이트 — 【정본은 internal_energy.yml】. 경공은 '경신'이 열린 경지부터다
        Map<String, Object> ie = RulesConfig.load(cfg.resolve("internal_energy.yml"));
        RulesConfig.section(ie, "realm_gates").forEach((realm, raw) -> {
            List<String> bands = new ArrayList<>();
            if (raw instanceof List<?> list) {
                list.forEach(v -> bands.add(String.valueOf(v)));
            }
            realmGates.put(realm, bands);
        });
        Object costBand = RulesConfig.section(ie, "cost_bands").get(band);
        int lo = 1;
        int hi = 2;
        if (costBand instanceof Map<?, ?> cb && ((Map<String, Object>) cb).get("cost") instanceof List<?> l
                && l.size() == 2) {
            lo = (int) num(l.get(0), 1);
            hi = (int) num(l.get(1), 2);
        }
        this.bandLow = lo;
        this.bandHigh = hi;

        // ── 경지 천장
        RulesConfig.section(root, "realm_ceiling").forEach((realm, raw) -> {
            if (raw instanceof Map<?, ?> m) {
                Map<String, Object> c = (Map<String, Object>) m;
                ceilings.put(realm, new Ceiling(
                        num(c.get("speed_bonus"), 0), num(c.get("jump_bonus"), 0),
                        num(c.get("fall_grace_m"), 0), num(c.get("wall_climb_m"), 0),
                        Boolean.TRUE.equals(c.get("water_run"))));
            }
        });

        // ── 신법의 성장
        RulesConfig.section(RulesConfig.section(root, "growth"), "per_agility")
                .forEach((k, v) -> perAgility.put(k, num(v, 0)));

        // ── 내력
        Map<String, Object> qi = RulesConfig.section(root, "qi");
        Map<String, Object> up = RulesConfig.section(qi, "upkeep");
        this.upkeepCost = (int) num(up.get("cost"), 1);
        this.upkeepInterval = (int) num(up.get("interval_ticks"), 20);
        this.waterUpkeepMult = (int) num(qi.get("water_upkeep_multiplier"), 2);
        this.leapCost = (int) num(qi.get("leap"), 1);
        this.wallKickCost = (int) num(qi.get("wall_kick"), 1);
        this.retreatCost = (int) num(qi.get("retreat"), 1);
        this.closeInCost = (int) num(qi.get("close_in"), 1);
        Map<String, Object> soft = RulesConfig.section(qi, "soften");
        this.softenCost = (int) num(soft.get("cost"), 1);
        this.metersPerQi = num(soft.get("meters_per_qi"), 4);
        this.costFloor = (int) num(qi.get("floor"), 1);
        Map<String, Object> dep = RulesConfig.section(qi, "depleted");
        this.depletedGrace = num(dep.get("grace_multiplier"), 0);
        this.depletedWindow = (int) num(dep.get("window_ticks"), 60);

        // ── 심법의 결
        RulesConfig.section(root, "simbeop_effects").forEach((id, raw) -> {
            if (raw instanceof Map<?, ?> m) {
                Map<String, Object> s = (Map<String, Object>) m;
                simEffects.put(id, new SimEffect(
                        (int) num(s.get("경신_할인"), 0),
                        num(s.get("이동_거리_배율"), 1.0),
                        Boolean.TRUE.equals(s.get("낙하_무효"))));
            }
        });

        // ── 보법 — skills.yml(카탈로그) × skill_mechanics.yml(거리·수직·변칙). 여기서 지어내지 않는다
        Map<String, Object> bo = RulesConfig.section(root, "bobeop");
        this.baseDash = num(RulesConfig.section(bo, "none").get("distance_m"), 3);
        this.masteryBonusM = num(bo.get("mastery_bonus_m"), 0.25);
        this.irregularBias = num(RulesConfig
                .section(RulesConfig.section(bo, "grants"), "irregular").get("dodge_bias_deg"), 30);

        Map<String, Object> mech = RulesConfig.section(
                RulesConfig.load(cfg.resolve("skill_mechanics.yml")), "skills");
        RulesConfig.section(RulesConfig.load(cfg.resolve("skills.yml")), "martial_arts")
                .forEach((id, raw) -> {
                    if (!(raw instanceof Map)) {
                        return;
                    }
                    Map<String, Object> s = (Map<String, Object>) raw;
                    if (!"경공".equals(String.valueOf(s.get("category")))) {
                        return;   // 보법의 정의는 카탈로그가 한다 — 코드가 목록을 갖지 않는다
                    }
                    Map<String, Object> m = mech.get(id) instanceof Map<?, ?> mm
                            ? (Map<String, Object>) mm : Map.of();
                    bobeops.put(id, new Bobeop(id,
                            String.valueOf(s.getOrDefault("name", id)),
                            String.valueOf(s.getOrDefault("faction", "")),
                            num(m.get("distance"), baseDash),
                            Boolean.TRUE.equals(m.get("vertical")),
                            m.get("irregular") != null));
                });

        // ── 갑옷 — equipment.yml armor 가 정본 (restrictions · dodge_penalty)
        Map<String, Object> gate = RulesConfig.section(root, "armor_gate");
        RulesConfig.section(gate, "by_chestplate").forEach((mat, v) -> armorByChest.put(mat, String.valueOf(v)));
        this.armorNone = String.valueOf(gate.getOrDefault("none", "무복"));
        this.dodgePenaltyScale = num(gate.get("dodge_penalty_scale"), 0.15);
        String blockedBy = String.valueOf(gate.getOrDefault("blocked_by", "경공_불가"));
        RulesConfig.section(RulesConfig.load(cfg.resolve("equipment.yml")), "armor")
                .forEach((name, raw) -> {
                    if (!(raw instanceof Map)) {
                        return;
                    }
                    Map<String, Object> a = (Map<String, Object>) raw;
                    armorDodgePenalty.put(name, (int) num(a.get("dodge_penalty"), 0));
                    if (a.get("restrictions") instanceof List<?> r
                            && r.stream().anyMatch(v -> blockedBy.equals(String.valueOf(v)))) {
                        armorBlocking.add(name);   // 철갑 — "경공_불가"
                    }
                });

        // ── 바닐라 상수 · 물리값 (전부 등록부에서)
        RulesConfig.section(root, "mc").forEach((k, v) -> mc.put(k, num(v, 0)));
        RulesConfig.section(root, "wall").forEach((k, v) -> wall.put(k, num(v, 0)));
        RulesConfig.section(root, "water").forEach((k, v) -> water.put(k, num(v, 0)));
        RulesConfig.section(root, "dash").forEach((k, v) -> dash.put(k, num(v, 0)));

        this.vfx = RulesConfig.section(root, "vfx");
        this.messages = RulesConfig.section(root, "messages");
        this.cullBeyond = num(RulesConfig.section(RulesConfig.section(
                RulesConfig.load(cfg.resolve("performance.yml")), "particles"), "lod").get("cull_beyond"), 32);
    }

    /** config 판독 — {@code HoncheonMvt.onEnable} 에서 {@code Growth.init} 바로 뒤 */
    public static void init(Path cfg) {
        instance = new Gyeonggong(cfg);
    }

    /** 배선되지 않았으면 null — 호출자는 조용히 예전 동작으로 돌아간다 (경공이 없던 세계로) */
    public static Gyeonggong get() {
        return instance;
    }

    // ══════════ ① 경지 — 천장을 여는 것은 승급이다 ══════════

    /** 이 경지에 '경신'이 열렸는가 — internal_energy.yml realm_gates (삼류·이류는 false) */
    public boolean open(String realm) {
        return realmGates.getOrDefault(realm, List.of()).contains(band);
    }

    public Ceiling ceiling(String realm) {
        return ceilings.getOrDefault(realm, Ceiling.ZERO);
    }

    // ══════════ ② 몸 — 경지 × 신법 × 심법 × 보법 × 갑옷 ══════════

    /**
     * <b>이 몸이 실제로 할 수 있는 것.</b> 전부 {@code min(경지 천장, 신법 성장) × 갑옷 무게}다.
     *
     * @param agility  민첩 <b>실수치</b> (PlayerLedger.attr("민첩"))
     * @param simbeop  익힌 심법 id (null = 개화 전 — 결이 없다)
     * @param bobeop   익힌 보법 (null = 보법 없는 몸 — 벽을 못 딛는다)
     * @param mastery  그 보법의 숙련 (없으면 0)
     * @param armor    갑옷 계열 (equipment.yml armor 의 키 — 철갑이면 경공이 안 열린다)
     */
    public Profile profile(String realm, double agility, String simbeop,
                           Bobeop bobeop, int mastery, String armor) {
        if (!open(realm)) {
            return Profile.closed("no_realm");        // ★ 개화하지 않은 몸 — 삼류가 지붕을 날면 안 된다
        }
        if (armorBlocking.contains(armor)) {
            return Profile.closed("armor_blocked");   // ★ 철갑 — equipment.yml restrictions: [경공_불가]
        }
        Ceiling c = ceiling(realm);
        SimEffect se = simEffects.getOrDefault(simbeop == null ? "" : simbeop, SimEffect.NONE);

        // 갑옷의 무게 — dodge_penalty(피갑 −1)가 몸을 무겁게 한다 ("갑옷은 회피를 판다"의 경공 쪽 절반)
        double weight = 1.0 + armorDodgePenalty.getOrDefault(armor, 0) * dodgePenaltyScale;
        weight = Math.max(0.1, weight);

        double speed = grow("speed_bonus", agility, c.speed()) * weight;
        double jump = grow("jump_bonus", agility, c.jump()) * weight;
        double grace = grow("fall_grace_m", agility, c.fallGrace()) * weight;

        // ★ 벽은 아무나 못 딛는다 — 경지 천장(절정+) ∧ 수직 보법(제운종·답운종). 둘 다 있어야 벽이 길이 된다
        double wall = (bobeop != null && bobeop.vertical())
                ? grow("wall_climb_m", agility, c.wallClimb()) * weight : 0;

        double dist = (bobeop == null ? baseDash : bobeop.distance() + mastery * masteryBonusM)
                * se.distanceMult() + perAgility.getOrDefault("dash_m", 0.0) * agility;

        // ★ 할인은 '얼마를 내는가'가 아니라 '얼마나 오래 버티는가'를 산다 (공짜로 나는 몸은 없다)
        int interval = upkeepInterval * (1 + Math.max(0, se.discount()));

        return new Profile(true, speed, jump, grace, wall, c.waterRun(), dist,
                interval, se.fallImmune(),
                bobeop != null && bobeop.irregular() ? irregularBias : 0, null);
    }

    /** 천장 안에서 자란다 — {@code min(천장, 민첩 × 눈금)} */
    private double grow(String key, double agility, double ceilingValue) {
        return Math.min(ceilingValue, perAgility.getOrDefault(key, 0.0) * agility);
    }

    // ══════════ ③ 내력 — 태우고, 끊기면 떨어진다 ══════════

    /** 유지비 — 물 위는 두 배다 (물은 딛을 것이 없다). 밴드 [1,2] 안 */
    public int upkeep(boolean onWater) {
        return clampBand(upkeepCost * (onWater ? waterUpkeepMult : 1));
    }

    public int upkeepInterval() {
        return upkeepInterval;
    }

    public int leapCost() {
        return clampBand(leapCost);
    }

    public int wallKickCost() {
        return clampBand(wallKickCost);
    }

    public int retreatCost() {
        return clampBand(retreatCost);
    }

    public int closeInCost() {
        return clampBand(closeInCost);
    }

    /** 할인은 단발 코스트를 0 으로 만들지 못한다 (qi.floor) — 그리고 밴드 상단을 넘지도 못한다 */
    private int clampBand(int cost) {
        return Math.max(Math.max(costFloor, bandLow), Math.min(bandHigh, cost));
    }

    /**
     * <b>낙법(落法)</b> — 경지가 사는 높이. ★ <b>기가 도는 몸에만 듣는다.</b>
     *
     * @param energy 지금 내력. 0 이면 {@code depleted.grace_multiplier}(= 0) 이 곱해진다 —
     *               <b>돌처럼 떨어진다.</b> 그 순간이 이 게임의 극적인 자리다
     */
    public double fallGrace(Profile p, int energy) {
        if (!p.open()) {
            return 0;
        }
        if (p.fallImmune() && energy > 0) {
            return Double.MAX_VALUE / 4;   // 답운(곤륜현천공) — 다만 고갈된 몸은 곤륜도 떨어진다
        }
        return energy > 0 ? p.fallGrace() : p.fallGrace() * depletedGrace;
    }

    /** 천장 위로 떨어진 높이를 내력으로 산다 — 내력 {@code soften.cost} = {@code meters_per_qi} m */
    public int softenCost(double metersOverGrace) {
        return metersOverGrace <= 0 ? 0
                : (int) Math.ceil(metersOverGrace / metersPerQi) * softenCost;
    }

    /** 내력 한 점이 사는 높이 (m) */
    public double metersPerQi() {
        return metersPerQi;
    }

    public int depletedWindow() {
        return depletedWindow;
    }

    public int idleOffTicks() {
        return idleOffTicks;
    }

    public double cullBeyond() {
        return cullBeyond;
    }

    // ══════════ 마인크래프트의 상수 — 우리가 고른 값이 아니다 (gyeonggong.yml mc) ══════════

    public double baseWalkSpeed() {
        return mc.getOrDefault("base_walk_speed", 0.2);
    }

    public double baseJumpVelocity() {
        return mc.getOrDefault("base_jump_velocity", 0.42);
    }

    public double vanillaSafeFall() {
        return mc.getOrDefault("vanilla_safe_fall_m", 3.0);
    }

    public double walkSpeedCap() {
        return mc.getOrDefault("walk_speed_hard_cap", 0.6);
    }

    /** 벽 딛기 / 수상비 / 물러남의 물리값 — 전부 등록부 (gyeonggong.yml wall · water · dash) */
    public double wall(String key, double fallback) {
        return wall.getOrDefault(key, fallback);
    }

    public double water(String key, double fallback) {
        return water.getOrDefault(key, fallback);
    }

    public double dash(String key, double fallback) {
        return dash.getOrDefault(key, fallback);
    }

    /** 도달 높이 ≈ 1.25 × (1 + jump)² 블록 — 감사가 재는 것과 같은 셈 (지붕은 뛰어서 못 오른다) */
    public double jumpHeight(double jumpBonus) {
        double h = mc.getOrDefault("jump_height_m", 1.25);
        return h * (1 + jumpBonus) * (1 + jumpBonus);
    }

    // ══════════ ④ 보법 — 이 몸이 아는 발 ══════════

    public Map<String, Bobeop> bobeops() {
        return bobeops;
    }

    /**
     * 이 원장이 아는 보법 중 <b>가장 멀리 가는 것</b> (제운종 8 · 답운종 10 …).
     * 원장의 키가 id 든 이름이든 받는다 — 어느 쪽으로 적립됐는지 원장이 통일돼 있지 않다.
     */
    public Bobeop bobeopOf(PlayerLedger ledger) {
        Bobeop best = null;
        for (Bobeop b : bobeops.values()) {
            if (ledger.daysOf(b.id()) > 0 || ledger.daysOf(b.name()) > 0) {
                if (best == null || b.distance() > best.distance()) {
                    best = b;
                }
            }
        }
        return best;
    }

    /**
     * <b>회피 판정의 '경공'</b> — {@code combat.yml defender_choice.회피.check = "민첩 + 경공"}.
     * 지금까지 이 항은 <b>세계에 존재하지 않았다</b> (회피 분기 자체가 없었다 — gap_audit §3-①).
     * 회피를 배선하는 자가 이것을 더하면 그 문장이 처음으로 참이 된다.
     */
    public int gyeonggongMastery(PlayerLedger ledger, ProgressionEngine progression) {
        Bobeop b = bobeopOf(ledger);
        if (b == null) {
            return 0;
        }
        return Math.max(ledger.levelOf(b.id(), progression), ledger.levelOf(b.name(), progression));
    }

    public int masteryOf(PlayerLedger ledger, Bobeop b, ProgressionEngine progression) {
        return b == null ? 0
                : Math.max(ledger.levelOf(b.id(), progression), ledger.levelOf(b.name(), progression));
    }

    // ══════════ ⑤ 갑옷 · 연출 · 말 ══════════

    /** 바닐라 흉갑 → equipment.yml armor 계열 (MVT 에는 방어구 착용 체계가 없다 — 그것을 바닐라로 읽는다) */
    public String armorOf(String chestplateMaterial) {
        return chestplateMaterial == null ? armorNone
                : armorByChest.getOrDefault(chestplateMaterial, armorNone);
    }

    public boolean blocksGyeonggong(String armor) {
        return armorBlocking.contains(armor);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> vfx(String key) {
        return vfx.get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    public String message(String key) {
        return String.valueOf(messages.getOrDefault(key, ""));
    }

    private static double num(Object v, double fallback) {
        return v instanceof Number n ? n.doubleValue() : fallback;
    }
}
