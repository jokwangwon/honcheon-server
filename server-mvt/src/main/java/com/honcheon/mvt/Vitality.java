package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 생명(生命) — 이 세계의 체력.
 *
 * <p><b>바닐라 하트 20 은 상한이 아니다.</b> 그것은 {@code MAX_HEALTH} 속성의 <b>기본값</b>일 뿐이고,
 * 속성 자체는 1024 까지 열려 있다. 그래서 '한도를 푸는' 데에는 아무 우회도 필요 없다 —
 * 기본값을 등록부의 내구로 세우면 그만이다. <b>진짜 벽은 표시였다</b>: 하트는 열 개마다 줄이 바뀌고,
 * 20을 넘는 순간 화면 위로 기어오른다. 그 벽은 {@link Player#setHealthScale(double)} 로 넘는다
 * (아래 {@link #apply}).
 *
 * <p><b>이 세계는 이미 체력을 갖고 있었다.</b> {@code combat.yml durability} 의 내구가 그것이고,
 * {@code HuntingGrounds} 는 이미 그 값을 몹의 {@code MAX_HEALTH} 에 싣고 있었다
 * ("짐승 16~24, 사람 16~20 — 플레이어(20)와 같은 눈금이다"). <b>플레이어만이 이 세계의 규칙 밖에
 * 서 있었다</b> — 바닐라 20 에 묶인 채로. 이 클래스는 새 축을 만들지 않는다. 빠져 있던 배선을 잇는다.
 *
 * <p>내구 = {@code round(10 + 체력×2 + 경지 보정 + 장비 보정)} — {@code combat.yml durability} 가 정본.
 * 코드에 수치는 없다 (등록제). 기본항이 그대로이므로 npcs 등록 내구·짐승은 흔들리지 않는다.
 *
 * <p><b>피해 계산은 어디서 도는가</b> — 옮기지 않았다. 지금 그대로다:
 * <ul>
 *   <li>플레이어 → 대상: {@code SkillListener} 가 피해(무기+무공+격+마진/2)를 <b>우리 엔진으로</b> 계산해
 *       {@code damage()} 로 싣는다 (바닐라 피해는 취소·대체).</li>
 *   <li>몹 → 플레이어: {@code HuntingGrounds} 가 {@code ATTACK_DAMAGE} 에 무기 위력을 실어 둔 바닐라 근접.</li>
 * </ul>
 * 둘 다 <b>이미 내구 눈금</b>(1 내구 = 1 HP)이다. 그래서 플레이어의 {@code MAX_HEALTH} 를 내구로 세우는
 * 것만으로 전부 들어맞는다 — 피해 파이프라인은 한 줄도 옮기지 않았다.
 */
public final class Vitality {

    /** 표시용 하트 눈금 — 20 = 언제나 정확히 10칸. 내구가 16이든 42든 화면은 그대로다 */
    private static final double HEART_SCALE = 20.0;

    /** 바닐라 max_health 속성의 하드 상한 (넘겨도 서버가 자른다). 우리 곡선(최대 ~44)은 한참 아래다 */
    private static final double VANILLA_ATTRIBUTE_CEILING = 1024.0;

    /** 장비 유래 내구 — 내갑·호심경을 몸에 걸친 총합 (equipment.yml vitality). 없으면 0 */
    static final NamespacedKey KEY_EQUIP = new NamespacedKey("honcheon", "vitality_equip");

    private static Vitality instance;

    // ─── 등록부 (config 판독 — 코드가 config 보다 앞서지 않는다) ───
    private final double base;                       // 10
    private final double perChe;                     // 2
    private final Map<String, Double> realmBonus = new LinkedHashMap<>();
    private final Map<String, Integer> attrCap = new LinkedHashMap<>();
    private final int equipCap;
    private final double 경상_ratio;
    private final double 중상_ratio;
    private final int 경상_penalty;
    private final int 중상_penalty;
    private final int 빈사_penalty;

    /** 부상 단계 — 이 세계는 HP 바가 아니라 <b>부상</b>으로 싸운다 (combat.yml wound_thresholds) */
    public enum Wound {
        온전("온전", 0),
        경상("경상", -1),
        중상("중상", -2),
        빈사("빈사", -3);

        private final String label;
        private final int penalty;

        Wound(String label, int penalty) {
            this.label = label;
            this.penalty = penalty;
        }

        public String label() {
            return label;
        }

        /** 판정 페널티 — judgment.yml condition 과 같은 값 (전투 밖 판정에도 지속) */
        public int penalty() {
            return penalty;
        }
    }

    private Vitality(Path cfg) {
        Map<String, Object> combat = RulesConfig.load(cfg.resolve("combat.yml"));
        Map<String, Object> dur = RulesConfig.section(combat, "durability");

        // 기본항 — formula 문자열이 정본이지만 파서를 두지 않는다 (도구도 그렇게 한다).
        // 기본항이 바뀌면 npcs 등록 내구가 전부 흔들린다 — 그때는 코드가 아니라 세계가 바뀐 것이다.
        this.base = 10.0;
        this.perChe = 2.0;

        Map<String, Object> rb = RulesConfig.section(dur, "realm_bonus");
        rb.forEach((realm, value) -> realmBonus.put(realm, num(value, 0)));

        this.equipCap = (int) num(dur.get("equipment_cap"), 0);

        Map<String, Object> wounds = RulesConfig.section(dur, "wound_thresholds");
        this.경상_ratio = ratioOf(wounds, "경상", 0.5);
        this.중상_ratio = ratioOf(wounds, "중상", 0.25);
        this.경상_penalty = (int) penaltyOf(wounds, "경상", -1);
        this.중상_penalty = (int) penaltyOf(wounds, "중상", -2);
        this.빈사_penalty = (int) penaltyOf(wounds, "빈사", -3);

        // 표준 무인의 체력 = 경지 상한 − 1 (combat_audit realm_axis 와 같은 모델 —
        // 상한을 찍은 자는 표준이 아니다). MVT 에는 캐릭터 시트가 없으므로 이것이 기본값이다.
        Map<String, Object> creation = RulesConfig.load(cfg.resolve("player_creation.yml"));
        RulesConfig.section(creation, "attribute_cap_by_realm")
                .forEach((realm, value) -> attrCap.put(realm, (int) num(value, 3)));
    }

    /** config 판독 — HoncheonMvt.onEnable 에서 Weapons.init·HuntingGrounds.init 과 같은 자리 */
    public static void init(Path cfg) {
        instance = new Vitality(cfg);
    }

    /** 배선되지 않았으면 null — HUD 는 조용히 아무것도 그리지 않는다 (연출이 판정을 멈추지 않는다) */
    public static Vitality get() {
        return instance;
    }

    // ══════════ 내구 — 등록부의 공식 (플레이어·NPC 공용) ══════════

    /**
     * 내구 = round(10 + 체력×2 + 경지 보정 + 장비 보정) — {@code combat.yml durability} 정본.
     *
     * <p><b>대칭 원칙</b>: 사람은 플레이어든 NPC든 이 한 공식을 쓴다. 짐승은 경지 보정을 받지 않는다
     * (개화한 몸만 기혈이 두터워진다) — {@code beast=true} 로 부른다.
     *
     * @param equip 장비 유래 가산 — {@code equipment_cap} 에서 잘린다
     */
    public int durability(String realm, double che, int equip, boolean beast) {
        double bonus = beast ? 0.0 : realmBonus.getOrDefault(realm, 0.0);
        int eq = Math.max(0, Math.min(equip, equipCap));
        return (int) Math.round(base + perChe * che + bonus + eq);
    }

    /** 표준 무인 — 그 경지의 체력 기본값 (능력치 상한 − 1) */
    public double defaultChe(String realm) {
        return Math.max(1, attrCap.getOrDefault(realm, 3) - 1);
    }

    /** 그 경지의 표준 내구 (체력 미지정 시 — MVT 기본) */
    public int durabilityOf(String realm, int equip) {
        return durability(realm, defaultChe(realm), equip, false);
    }

    public int equipCap() {
        return equipCap;
    }

    public double realmBonus(String realm) {
        return realmBonus.getOrDefault(realm, 0.0);
    }

    // ══════════ 몸에 세운다 ══════════

    /**
     * 등록부의 내구를 플레이어의 몸으로 만든다 — <b>경지·능력치·장비가 바뀔 때마다</b> 부른다.
     *
     * <p>두 가지를 한다:
     * <ol>
     *   <li><b>한도를 푼다</b> — {@code MAX_HEALTH} 기본값을 내구로 세운다. 20은 상한이 아니라
     *       기본값이었을 뿐이다 (속성은 1024까지 열려 있다).</li>
     *   <li><b>화면을 지킨다</b> — {@code setHealthScale(20)} 으로 <b>하트를 열 칸에 고정</b>한다.
     *       내구가 16이든 42이든 클라이언트는 언제나 하트 10개를 그린다. 줄이 늘지 않으니
     *       화면을 가릴 수 없다. <b>그리고 이것은 리소스팩 없이도 돈다</b> — 팩 게이트 불가침.</li>
     * </ol>
     *
     * <p><b>왜 비율 표시가 타협이 아닌가</b>: 부상 문턱이 <b>비율</b>이기 때문이다 (경상 50% · 중상 25%).
     * 하트를 10칸에 고정하면 경상은 <b>언제나 5칸</b>, 중상은 <b>언제나 2.5칸</b>에 선다 — 삼류의 몸이든
     * 화경의 몸이든 평생 같은 자리다. 절대 하트였다면 그 선은 성장할 때마다 움직였을 것이다.
     * 비율 계기는 비율 규칙의 <b>정확한 도구</b>다.
     *
     * <p>현재 체력은 <b>비율로 보존</b>한다 — 승급이 공짜 회복이 되어서도, 장비를 벗다 죽어서도 안 된다.
     */
    public void apply(Player player, String realm) {
        double che = defaultChe(realm);
        apply(player, realm, che);
    }

    public void apply(Player player, String realm, double che) {
        int equip = equipBonus(player);
        int target = durability(realm, che, equip, false);
        applyRaw(player, target);
    }

    /** 내구를 직접 세운다 (허수아비·시험용). 비율 보존 + 하트 10칸 고정 */
    public void applyRaw(Player player, int durability) {
        var attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) {
            return;
        }
        double clamped = Math.max(1.0, Math.min(durability, VANILLA_ATTRIBUTE_CEILING));
        double oldMax = attr.getValue();
        double ratio = oldMax <= 0 ? 1.0 : Math.max(0.0, Math.min(1.0, player.getHealth() / oldMax));

        attr.setBaseValue(clamped);
        // 비율 보존 — 승급은 회복이 아니고, 장비를 벗는 것은 죽음이 아니다
        double health = Math.max(0.5, Math.min(clamped, ratio * clamped));
        player.setHealth(health);

        // ★ 화면을 가리지 않는 이유가 이 두 줄이다 — 하트는 언제나 10칸이다
        player.setHealthScaled(true);
        player.setHealthScale(HEART_SCALE);
    }

    /**
     * <b>몸을 등록부와 맞춘다</b> — 어긋났을 때만 쓴다 (같으면 아무 일도 하지 않는다).
     *
     * <p>HUD 틱(0.2초)마다 불린다. 그래서 경지가 오르든 내갑을 입든 <b>따로 알릴 필요가 없다</b> —
     * 다음 틱에 몸이 저절로 등록부를 따라온다. 승급 훅·장비 훅을 여기저기 심지 않는 이유다
     * (훅은 빠뜨리면 조용히 틀리고, 대조는 빠뜨릴 수가 없다).
     *
     * @return 몸이 바뀌었으면 true
     */
    public boolean reconcile(Player player, String realm) {
        var attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) {
            return false;
        }
        int target = durability(realm, defaultChe(realm), equipBonus(player), false);
        double clamped = Math.max(1.0, Math.min(target, VANILLA_ATTRIBUTE_CEILING));
        if (Math.abs(attr.getBaseValue() - clamped) < 0.01 && player.isHealthScaled()) {
            return false;   // 이미 맞다 — 매 틱 쓰지 않는다
        }
        applyRaw(player, target);
        return true;
    }

    /** 장비 유래 내구 가산 (PDC) — 내갑·호심경을 걸치는 계층이 여기에 써 넣는다 */
    public int equipBonus(Player player) {
        Integer v = player.getPersistentDataContainer().get(KEY_EQUIP, PersistentDataType.INTEGER);
        return v == null ? 0 : Math.max(0, Math.min(v, equipCap));
    }

    /** 장비 가산을 세우고 몸을 갱신한다 (내갑을 입고 벗는 자리) */
    public void setEquipBonus(Player player, int bonus, String realm) {
        player.getPersistentDataContainer().set(KEY_EQUIP, PersistentDataType.INTEGER,
                Math.max(0, Math.min(bonus, equipCap)));
        apply(player, realm);
    }

    // ══════════ 부상 — 이 세계가 싸우는 방식 ══════════

    /** 지금의 부상 단계 — 비율로 잰다 (내구가 얼마든 문턱은 같은 자리에 선다) */
    public Wound wound(LivingEntity entity) {
        var attr = entity.getAttribute(Attribute.MAX_HEALTH);
        double max = attr == null ? 20.0 : attr.getValue();
        return woundOf(max <= 0 ? 0 : entity.getHealth() / max);
    }

    public Wound woundOf(double ratio) {
        if (ratio <= 0) {
            return Wound.빈사;
        }
        if (ratio < 중상_ratio) {
            return Wound.중상;
        }
        if (ratio < 경상_ratio) {
            return Wound.경상;
        }
        return Wound.온전;
    }

    /** 등록부의 페널티 (코드가 수치를 갖지 않는다 — enum 은 이름만, 값은 config) */
    public int penaltyOf(Wound wound) {
        return switch (wound) {
            case 온전 -> 0;
            case 경상 -> 경상_penalty;
            case 중상 -> 중상_penalty;
            case 빈사 -> 빈사_penalty;
        };
    }

    // ─── 판독 도우미 ───

    private static double num(Object value, double fallback) {
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    private static double ratioOf(Map<String, Object> wounds, String key, double fallback) {
        return wounds.get(key) instanceof Map<?, ?> m
                ? num(((Map<?, ?>) m).get("below_ratio"), fallback) : fallback;
    }

    private static double penaltyOf(Map<String, Object> wounds, String key, double fallback) {
        return wounds.get(key) instanceof Map<?, ?> m
                ? num(((Map<?, ?>) m).get("penalty"), fallback) : fallback;
    }
}
