package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 병기 제작소 — 무협 무기를 하나의 문법으로 찍어낸다.
 *
 * <p>계약(정본):
 * <ul>
 *   <li>config/resourcepack_design.yml {@code item_channels.무기} — 베이스 아이템(등급) × 모델 키(계열)</li>
 *   <li>config/equipment.yml {@code weapon_grades} — 감당 격·판정 보정·property_slots (툴팁의 원천)</li>
 *   <li>config/equipment.yml {@code special_properties} — 각인 카탈로그 (등록된 것만 새긴다)</li>
 *   <li>config/equipment.yml {@code beloved_weapon} — 애병 3단계 (이력이 파는 유일한 판정 보정)</li>
 *   <li>config/combat.yml {@code damage.weapon_power} — 계열별 무기 위력 (= 공격력의 원천)</li>
 *   <li>config/economy.yml {@code price_table.장비} — 가격 (상점의 원천)</li>
 * </ul>
 *
 * <h2>병기의 다섯 축</h2>
 * <pre>
 *   계열(系)   검·도·창…      공격력·공속·사거리의 골격      — 고를 때 정해진다
 *   등급(級)   범철…신병      감당 격 + 내구 + 슬롯 수       — 재질이 판다
 *   치우침(偏) 경·예·균·후·중  공격력 ↔ 공속 (합 보존)        — 개체차 · 제작 시 확정
 *   품(品)     조잡…명공      공격력·내구 미세 보정          — 개체차 · 재련으로 오른다
 *   이력(歷)   격돌·동행      애병 — 주인 한정 판정          — 강화의 최고봉 · 살 수 없다
 * </pre>
 *
 * <h2>규율</h2>
 * <ol>
 *   <li><b>등급은 공격력이 아니다</b> — 재질 티어(돌·철·다이아·금·네더라이트)가 딸고 오는 바닐라 공격력·공속을
 *       전부 덮어쓴다. 공격력·공속·사거리는 <b>계열</b>이 정하고, 등급이 파는 것은 <b>생존</b>(내구)과
 *       <b>감당 격</b>뿐이다 (equipment.yml: "가치는 보정이 아니라 생존").</li>
 *   <li><b>PDC가 진실</b> — 등급·계열·치우침·품·이력을 재질로 근사하지 않는다. 한 번 벼려진 병기의 값은
 *       아이템에 영구히 새겨지고, 같은 입력으로 다시 만들면 같은 값이 나온다 (제작은 순함수다).</li>
 *   <li><b>팩 게이트</b> — 팩에 구워지지 않은 계열(부·겸·월아산·구)에는 {@code item_model} 을 붙이지 않는다.</li>
 *   <li><b>치우침은 합을 보존한다</b> — {@link Bias} 는 공격력과 공속을 <b>서로의 역수</b>로 민다
 *       (곱 = 초당 피해가 불변). 한쪽으로 치우친 병기는 다른 쪽을 정확히 그만큼 잃는다 — 상위 호환이 없다.
 *       <b>사거리는 계열이 정한다</b> — 치우침도 품도 재련도 사거리를 건드리지 않는다 (사용자 규정).</li>
 *   <li><b>품·강화는 등급을 침범하지 않는다</b> — {@link Craft} 는 공격력·내구에만 좁은 폭으로 붙고
 *       <b>감당 격과 판정에는 손대지 않는다</b>. 명공(名工)은 equipment.yml 의 {@code property_slots} 를
 *       한 칸 먹는 각인이라 슬롯이 없는 <b>범철에는 원리적으로 나오지 않는다</b> — 아무리 재련해도
 *       범철은 정련이 되지 않는다 (감당 격은 재질의 것이다).</li>
 *   <li><b>무기가 파는 유일한 판정 보정은 이력</b> — combat.yml 이 등급 judgment_bonus 를 전부 0으로 내렸다.
 *       품도 강화도 판정을 주지 않는다. +1 은 오직 애병 '손에_익다'가, 그것도 <b>주인의 손에서만</b> 준다
 *       (equipment.yml beloved_weapon.principle — 약탈 메타 차단).</li>
 * </ol>
 *
 * <p>툴팁은 UI다 — 표시된 공격력·공속·사거리는 실제 attribute modifier 와 <b>같은 값에서</b> 나온다:
 * 표시값을 먼저 소수 1자리로 반올림하고 <b>그 반올림된 값에서 modifier 를 역산</b>한다
 * (툴팁이 거짓말하지 않는 유일한 방법). 바닐라 attribute 줄은 {@link ItemFlag#HIDE_ATTRIBUTES} 로 끈다.
 */
public final class Weapons {

    // ══════════ PDC 태그 규약 — 판정층(SkillEngine)이 읽는 키 ══════════

    /** 계열 — 값: 검·도·창·권갑·단검·부·겸·월아산·구 */
    public static final NamespacedKey KEY_SERIES = key("weapon_series");
    /** 등급 — 값: 범철·정련·보병·신병·마병 */
    public static final NamespacedKey KEY_GRADE = key("weapon_grade");
    /** 무기 계열 → combat.yml damage.weapon_power 의 키 (맨손·단검·검·도·창·봉·중병기) */
    public static final NamespacedKey KEY_CLASS = key("weapon_class");
    /** 마병 감정 상태 — 값: unidentified | revealed (미감정 마병은 툴팁이 거짓말한다) */
    public static final NamespacedKey KEY_STATE = key("weapon_state");

    /** 치우침 — 값: 경·예·균·후·중 (제작 시 확정 · 불변) */
    public static final NamespacedKey KEY_BIAS = key("weapon_bias");
    /** 품 — 값: 조잡·보통·정품·명공 (재련으로 오른다) */
    public static final NamespacedKey KEY_CRAFT = key("weapon_craft");
    /** 각인 목록 — 쉼표 구분 (equipment.yml special_properties 의 키만) */
    public static final NamespacedKey KEY_PROPS = key("weapon_properties");
    /** 실효 위력 — 치우침·품이 반영된 실수 위력. 판정층이 재계산 없이 읽는 창구 */
    public static final NamespacedKey KEY_POWER = key("weapon_power_effective");
    /** 재련 횟수 — 이력 표시용 (성공만 센다) */
    public static final NamespacedKey KEY_REFORGE = key("weapon_reforge_count");

    // ─── 이력(歷) 축 — 애병 ───
    /** 주인 UUID — 처음 피를 본 손이 주인이 된다. 모든 애병 보정은 이 손에서만 */
    public static final NamespacedKey KEY_OWNER = key("weapon_owner");
    /** 생사 격돌 횟수 — beloved_weapon.history_inputs.생사_격돌_생존 */
    public static final NamespacedKey KEY_CLASH = key("weapon_clash_count");
    /** 동행 일수 — 게임 내 날짜 축이 서면 채운다 (지금은 0) */
    public static final NamespacedKey KEY_BOND_DAYS = key("weapon_bond_days");
    /** 애병 단계 — 값: 손에_익다 | 명병 | 통령 (없으면 0단) */
    public static final NamespacedKey KEY_BELOVED = key("weapon_beloved_stage");

    public static final String STATE_UNIDENTIFIED = "unidentified";
    public static final String STATE_REVEALED = "revealed";

    private static NamespacedKey key(String value) {
        // 플러그인 이름이 아니라 고정 네임스페이스 — 판정층이 리터럴로 재구성할 수 있어야 한다
        return new NamespacedKey("honcheon", value);
    }

    // ══════════ 등급 ══════════

    /** 등급 = 베이스 아이템(재질) + 색 + 내구 + 각인 슬롯. 순서 = 사다리 (equipment.yml weapon_grades) */
    public enum Grade {
        //        슬러그          색                내구   슬롯
        범철("beomcheol", ChatColor.GRAY, 250, 0),
        정련("jeongryeon", ChatColor.WHITE, 800, 1),
        보병("bobyeong", ChatColor.AQUA, 2000, 1),
        신병("sinbyeong", ChatColor.GOLD, 0, 2),      // 0 = 불괴 (세계 등록제 유일물)
        마병("mabyeong", ChatColor.DARK_RED, 0, 0);   // 0 = 불괴 (혈교 잔재 — 부러지지 않는다)

        /** 모델 키 접미사 (팩 파일명) */
        public final String slug;
        /** semantic_colors.장비_품질 — 색 단독 금지(colorblind_rule)이므로 등급명을 글자로도 쓴다 */
        public final ChatColor color;
        /** 바닐라 max_damage 덮어쓰기. 0 = Unbreakable (금검 내구 32 같은 재질 사고를 막는다) */
        public final int durability;
        /**
         * 각인 슬롯 수 — equipment.yml weapon_grades.<등급>.property_slots 의 사본.
         * 범철에 이 키가 <b>없다</b>는 사실이 이 시스템의 주춧돌이다:
         * 명공각인이 슬롯을 먹는 이상, <b>범철은 명공이 될 수 없다</b> → 재련한 범철이 정련을 이길 길이 없다.
         */
        public final int slots;

        Grade(String slug, ChatColor color, int durability, int slots) {
            this.slug = slug;
            this.color = color;
            this.durability = durability;
            this.slots = slots;
        }

        public static Grade of(String korean) {
            for (Grade g : values()) {
                if (g.name().equals(korean)) {
                    return g;
                }
            }
            throw new IllegalArgumentException("없는 등급: " + korean + " (범철·정련·보병·신병·마병)");
        }
    }

    // ══════════ 계열 ══════════

    /**
     * 계열 = 실루엣(모델 키) + 손맛(공속·사거리) + 위력(combat.yml weapon_power 키).
     *
     * <p>{@code modelId == null} = 팩에 텍스처가 없다 → item_model 미부착(바닐라 텍스처로 굴린다).
     *
     * <p>공속: 플레이어 기본 공격속도 4.0 에 더해지는 값(ADD_NUMBER). 표시 공속 = 4.0 + speedMod.
     * 사거리: 기본 상호작용 거리 3.0 에 더해지는 값. <b>계열만이 사거리를 정한다</b> — 개체차 축이 못 건드린다.
     */
    public enum Series {
        //     한글  모델키      베이스 종류     위력키    공속mod  사거리mod
        검("sword", Base.SWORD, "검", -2.4, 0.0),      // 표준 1.6/s — 중용의 병기
        도("dao", Base.SWORD, "도", -2.6, 0.0),        // 1.4/s — 한날·무겁다
        창("spear", Base.SWORD, "창", -2.9, 2.0),      // 1.1/s — 느리다. 대신 간격 5.0m
        권갑("gauntlet", Base.SWORD, "맨손", -2.0, 0.0), // 2.0/s — 맨손에 가깝다 (위력은 무공이 만든다)
        단검("dagger", Base.SWORD, "단검", -1.8, 0.0),  // 2.2/s — 가장 빠르다 (속검)
        // ─── 바닐라 도구 징발 (18반 병기) — 팩 6차에서 텍스처 16장이 구워져 점등됐다 ───
        부("bu", Base.AXE, "중병기", -3.1, 0.0),        // 0.9/s — 가장 느리고 한 방이 가장 무겁다 (방패 파괴)
        겸("gyeom", Base.HOE, "단검", -2.1, 0.0),          // 1.9/s — 걸어 채는 날. 가볍고 빠르다
        월아산("wolasan", Base.SHOVEL, "봉", -3.0, 1.0),      // 1.0/s — 승려의 장병기. 간격 4.0m
        구("gu", Base.PICKAXE, "검", -2.5, 0.0);        // 1.5/s — 걸고 당긴다. 중간

        /** 팩 모델 키의 계열 부분. null = 팩 미구움 → item_model 부착 금지 */
        public final String modelId;
        final Base base;
        /** combat.yml damage.weapon_power 의 키 — 공격력의 원천 */
        public final String powerKey;
        final double speedMod;
        final double reachMod;

        Series(String modelId, Base base, String powerKey, double speedMod, double reachMod) {
            this.modelId = modelId;
            this.base = base;
            this.powerKey = powerKey;
            this.speedMod = speedMod;
            this.reachMod = reachMod;
        }

        public static Series of(String korean) {
            for (Series s : values()) {
                if (s.name().equals(korean)) {
                    return s;
                }
            }
            throw new IllegalArgumentException("없는 계열: " + korean
                    + " (검·도·창·권갑·단검·부·겸·월아산·구)");
        }

        /** 계열 표준 공속 (치우침 없음) */
        public double baseAttackSpeed() {
            return BASE_ATTACK_SPEED + speedMod;
        }

        /**
         * 계열 표준 공속 — 기존 호출자(SkillListener 의 무공 후딜 계산)를 위한 이름.
         * 개체의 실제 공속은 치우침이 민다: {@link Weapons#attackSpeed(Series, Bias)} 를 쓰라.
         */
        public double attackSpeed() {
            return baseAttackSpeed();
        }

        /** 사거리 (m) — 계열 고정. 개체차·강화가 없다 */
        public double reach() {
            return BASE_REACH + reachMod;
        }
    }

    /** 베이스 아이템 종류 — 등급(재질) × 종류 = Material */
    private enum Base {
        SWORD(Material.STONE_SWORD, Material.IRON_SWORD, Material.DIAMOND_SWORD,
                Material.GOLDEN_SWORD, Material.NETHERITE_SWORD),
        AXE(Material.STONE_AXE, Material.IRON_AXE, Material.DIAMOND_AXE,
                Material.GOLDEN_AXE, Material.NETHERITE_AXE),
        HOE(Material.STONE_HOE, Material.IRON_HOE, Material.DIAMOND_HOE,
                Material.GOLDEN_HOE, Material.NETHERITE_HOE),
        SHOVEL(Material.STONE_SHOVEL, Material.IRON_SHOVEL, Material.DIAMOND_SHOVEL,
                Material.GOLDEN_SHOVEL, Material.NETHERITE_SHOVEL),
        PICKAXE(Material.STONE_PICKAXE, Material.IRON_PICKAXE, Material.DIAMOND_PICKAXE,
                Material.GOLDEN_PICKAXE, Material.NETHERITE_PICKAXE);

        private final Material[] byGrade;   // 범철·정련·보병·신병·마병 순

        Base(Material... byGrade) {
            this.byGrade = byGrade;
        }

        Material of(Grade grade) {
            return byGrade[grade.ordinal()];
        }
    }

    // ══════════ ① 치우침(偏) — 같은 계열 안의 변주 ══════════

    /**
     * 치우침 = 그 쇠가 어디에 무게를 두었는가. <b>같은 범철 검이라도 가벼운 검과 무거운 검이 있다.</b>
     *
     * <p><b>합의 보존</b>: 공격력 배율 = {@code STEP^n}, 공속 배율 = {@code STEP^-n} — 정확히 서로의 역수다.
     * 곱(= 초당 피해)이 불변이므로 <b>어떤 치우침도 다른 치우침의 상위 호환이 아니다</b>.
     * 무거운 검은 한 합이 무겁고, 가벼운 검은 합이 잦다. 둘 중 무엇이 나은지는 <b>상대</b>가 정한다:
     * 방어구 감쇄(armor.mitigation)는 <b>한 합마다</b> 깎으므로 중(重)이 유리하고,
     * 기(氣) 축적·경직 누적은 <b>타수</b>를 세므로 경(輕)이 유리하다.
     *
     * <p><b>사거리는 없다</b> — 사용자 규정: "무기의 특성상 사거리는 계열이 정해진다".
     */
    public enum Bias {
        경("輕", "가볍다", -2),
        예("銳", "날렵하다", -1),
        균("均", null, 0),          // 부제 없음 — 치우침 없는 것이 표준이다
        후("厚", "묵직하다", 1),
        중("重", "무겁다", 2);

        /** 한 칸의 폭. 1.08 = 8% — 계열 차(검 4 vs 단검 3 = 33%)보다 확실히 작다: 치우침이 계열을 못 넘는다 */
        private static final double STEP = 1.08;

        public final String hanja;
        /** 툴팁 제목의 부제 (균은 null) */
        public final String subtitle;
        /** -2(가장 가볍다) … +2(가장 무겁다) */
        public final int notch;

        Bias(String hanja, String subtitle, int notch) {
            this.hanja = hanja;
            this.subtitle = subtitle;
            this.notch = notch;
        }

        public double damageMul() {
            return Math.pow(STEP, notch);
        }

        /** 공격력 배율의 정확한 역수 — 여기가 "합이 보존된다"의 전부다 */
        public double speedMul() {
            return Math.pow(STEP, -notch);
        }

        public static Bias of(String korean) {
            for (Bias b : values()) {
                if (b.name().equals(korean)) {
                    return b;
                }
            }
            throw new IllegalArgumentException("없는 치우침: " + korean + " (경·예·균·후·중)");
        }

        /** 굴림 — 종 모양. 극단(경·중)은 드물다 (합 12) */
        static Bias roll(Random random) {
            int r = random.nextInt(12);
            if (r < 1) return 경;
            if (r < 4) return 예;
            if (r < 8) return 균;
            if (r < 11) return 후;
            return 중;
        }
    }

    // ══════════ ② 품(品) — 같은 등급 안의 만듦새 ══════════

    /**
     * 품 = 누가 벼렸는가. <b>같은 범철이라도 잘 벼린 것과 조잡한 것이 있다.</b>
     *
     * <p>등급이 파는 것(감당 격·판정)은 <b>건드리지 않는다</b>. 품이 만지는 것은 공격력(±8%)과 내구(±30%)뿐이다.
     * 폭이 좁은 이유: 등급 사다리(내구 250→800→2000)를 품이 넘으면 등급이 죽는다.
     * <b>명공 범철(250×1.30 = 325) &lt; 조잡 정련(800×0.80 = 640)</b> — 어떤 재련도 한 급 위를 못 넘는다.
     *
     * <p><b>명공은 각인이다</b> — equipment.yml special_properties.명공각인 을 그대로 쓴다 (중복 정의 금지).
     * 각인은 {@code property_slots} 를 한 칸 먹으므로 <b>슬롯이 0인 범철은 명공이 될 수 없다</b>.
     * 이것이 "재련한 범철이 정련을 이긴다"를 규칙 차원에서 불가능하게 만든다.
     */
    public enum Craft {
        조잡("粗製", 0.94, 0.80, "급히 두들긴 쇠 — 날에 결이 남았다."),
        보통("常", 1.00, 1.00, "장인의 평범한 하루가 낳은 물건."),
        정품("精品", 1.04, 1.15, "잘 벼려진 날 — 손이 가볍다."),
        명공("名工", 1.08, 1.30, "명공의 각인 — 이 쇠는 백 년을 간다.");

        public final String hanja;
        final double damageMul;
        final double durabilityMul;
        public final String flavor;

        Craft(String hanja, double damageMul, double durabilityMul, String flavor) {
            this.hanja = hanja;
            this.damageMul = damageMul;
            this.durabilityMul = durabilityMul;
            this.flavor = flavor;
        }

        /** 명공은 각인이다 → 슬롯을 한 칸 먹는다 (equipment.yml special_properties.명공각인) */
        public boolean isInscription() {
            return this == 명공;
        }

        /** 그 등급이 닿을 수 있는 품의 천장 — 슬롯 없는 범철은 정품에서 멈춘다 */
        public static Craft ceiling(Grade grade) {
            return grade.slots >= 1 ? 명공 : 정품;
        }

        public Craft next() {
            return this == 명공 ? null : values()[ordinal() + 1];
        }

        public static Craft of(String korean) {
            for (Craft c : values()) {
                if (c.name().equals(korean)) {
                    return c;
                }
            }
            throw new IllegalArgumentException("없는 품: " + korean + " (조잡·보통·정품·명공)");
        }

        /** 굴림 — 등급이 높을수록 만듦새가 좋다 (신병을 조잡하게 벼리는 대장장이는 없다) */
        static Craft roll(Grade grade, Random random) {
            int r = random.nextInt(10);
            return switch (grade) {
                case 범철 -> r < 3 ? 조잡 : (r < 9 ? 보통 : 정품);       // 명공 불가 (슬롯 0)
                case 정련 -> r < 5 ? 보통 : (r < 9 ? 정품 : 명공);
                case 보병 -> r < 2 ? 보통 : (r < 7 ? 정품 : 명공);
                case 신병 -> r < 1 ? 정품 : 명공;                        // 신병은 명공이 기본이다
                case 마병 -> 보통;                                       // 사람이 벼린 것이 아니다 — 품이 없다
            };
        }
    }

    // ══════════ ⑤ 이력(歷) — 애병(愛兵) ══════════

    /**
     * 애병 단계 — equipment.yml beloved_weapon.stages 의 사본.
     *
     * <p><b>개체차와 직교한다</b>: 치우침·품은 <b>"이 쇠가 무엇인가"</b>(제작 시 확정, 불변),
     * 애병은 <b>"이 쇠와 사람이 서로를 얼마나 아는가"</b>(이력, 성장). 두 축은 서로를 침범하지 않는다 —
     * 애병이 치우침을 바꾸지 않는 이유: 검이 사람에게 맞춰 <b>모양을 바꾸는</b> 것은 강호의 물리가 아니다.
     * 바뀌는 것은 사람의 손이다. 그래서 애병의 보정은 전부 <b>주인 한정</b>이다 (강탈해도 따라오지 않는다).
     *
     * <p>단 하나의 예외를 통령(通靈)에 예약한다 — 십 년을 함께하고 주인이 화경에 이르면
     * 검이 주인 쪽으로 <b>치우침 한 칸</b> 움직인다 ({@code KEY_BIAS_SHIFT}). 합 보존은 그대로이므로
     * 이것은 <b>상향이 아니라 적합</b>이다. 이력 축(년수·화경)이 MVT에 없으므로 지금은 자리만 예약한다.
     */
    public enum Beloved {
        손에_익다("주인의 손에서만 판정 +1"),
        명병("세계 유일 명명 · 파괴 판정 1회/전투 유예"),
        통령("감당 격 +1 취급 · 이기어검 궁합");

        public final String grants;

        Beloved(String grants) {
            this.grants = grants;
        }

        public String display() {
            return name().replace('_', ' ');
        }

        public static Beloved of(String stored) {
            for (Beloved b : values()) {
                if (b.name().equals(stored)) {
                    return b;
                }
            }
            return null;
        }
    }

    /**
     * '손에_익다' 승급 문턱 — equipment.yml beloved_weapon.stages.손에_익다.requires:
     * "동행 1년+ <b>또는</b> 격돌 30회". MVT엔 게임 내 년수 축이 없으므로 <b>격돌 30회</b>만 센다.
     * (config 등록 대기: beloved_weapon.stages.손에_익다.clash_threshold: 30 — 지금은 문장 안에만 있다)
     */
    public static final int CLASH_FOR_FIRST_STAGE = 30;

    /** 명병·통령은 사건(생사 고비·명성·화경)을 요구한다 — MVT에 그 축이 없다. 자리만 예약 */
    public static final NamespacedKey KEY_BIAS_SHIFT = key("weapon_bias_shift");

    // ══════════ 바닐라 기준값 (플레이어 기본 attribute) ══════════

    private static final double BASE_ATTACK_DAMAGE = 1.0;   // 맨주먹 = 1
    private static final double BASE_ATTACK_SPEED = 4.0;
    private static final double BASE_REACH = 3.0;

    /** 마병은 도(道)에만 존재한다 — 팩에 sword_mabyeong 은 없다 (혈음도 1점) */
    private static final Series DEMONIC_SERIES = Series.도;

    // ══════════ config 로드 (지연 — 배선 없이도 /혼천 병기 가 산다) ══════════

    private static Map<String, Object> equipment;   // equipment.yml
    private static Map<String, Integer> weaponPower;   // combat.yml damage.weapon_power

    /** 명시 초기화 — HoncheonMvt.onEnable 에서 불러주면 지연 로드를 건너뛴다 (선택) */
    @SuppressWarnings("unchecked")
    public static synchronized void init(Path configDir) {
        equipment = RulesConfig.load(configDir.resolve("equipment.yml"));
        Map<String, Object> combat = RulesConfig.load(configDir.resolve("combat.yml"));
        Map<String, Object> damage = RulesConfig.section(combat, "damage");
        Map<String, Integer> power = new LinkedHashMap<>();
        ((Map<String, Object>) damage.get("weapon_power"))
                .forEach((k, v) -> power.put(k, RulesConfig.intValue(v)));
        weaponPower = Map.copyOf(power);
    }

    /** 지연 로드 — 플러그인 데이터 폴더의 config/ 에서 (scripts/run_mvt_server.sh 가 동기화한다) */
    private static synchronized void ensureLoaded() {
        if (equipment != null) {
            return;
        }
        Plugin plugin = Bukkit.getPluginManager().getPlugin("HoncheonMVT");
        if (plugin == null) {
            throw new IllegalStateException("HoncheonMVT 플러그인을 찾을 수 없다 — Weapons.init(configDir) 로 초기화하라");
        }
        init(plugin.getDataFolder().toPath().resolve("config"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> gradeSpec(Grade grade) {
        ensureLoaded();
        Map<String, Object> grades = RulesConfig.section(equipment, "weapon_grades");
        Object spec = grades.get(grade.name());
        return spec instanceof Map ? (Map<String, Object>) spec : Map.of();
    }

    /** 감당 격 — equipment.yml weapon_grades.<등급>.withstands (마병은 등급표에 없다 → 도의 상향) */
    public static String withstands(Grade grade) {
        if (grade == Grade.마병) {
            return "강기";   // demonic_weapons.power.withstand_rank_up: 1 (정련 기준 1단 상향)
        }
        Object v = gradeSpec(grade).get("withstands");
        return v == null ? "?" : String.valueOf(v);
    }

    /**
     * 판정 보정 — equipment.yml weapon_grades.<등급>.judgment_bonus.
     * 2026-07 전투 정합 패스 이후 <b>전 등급 0</b>이다. 품도 강화도 여기에 손대지 않는다 —
     * 무기가 파는 유일한 판정 보정은 애병(이력)뿐이다 ({@link #belovedJudgmentBonus}).
     */
    public static int judgmentBonus(Grade grade) {
        if (grade == Grade.마병) {
            return 0;   // 마병이 파는 것은 보정이 아니라 damage_bonus + 흡수 (그리고 침식)
        }
        Object v = gradeSpec(grade).get("judgment_bonus");
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** 등급 표준 무기 위력 — combat.yml damage.weapon_power[계열 위력키] (개체차 이전의 골격) */
    public static int power(Series series) {
        ensureLoaded();
        return weaponPower.getOrDefault(series.powerKey, weaponPower.getOrDefault("맨손", 1));
    }

    // ══════════ 수치 — 툴팁과 attribute 의 유일한 원천 ══════════

    /**
     * 표시값을 먼저 굳힌다 — modifier 는 이 굳은 값에서 역산된다 (툴팁 무결성).
     *
     * <p><b>소수 2자리인 이유</b>: 1자리로 자르면 합 보존이 깨진다. 부(斧)의 공속은 0.9 대역이라
     * 0.1 의 반올림이 11% 오차다 — 실제로 1자리에서는 <b>예(銳) 부가 경(輕) 부를 공격력·공속 모두에서
     * 이기는</b> 지배 조합이 12건 생겼다. 2자리에서 0건이 된다 (같은 품 안 DPS 편차 0.61% = 반올림 오차뿐).
     */
    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * 표시 공격력 = (기본 1.0 + 계열 위력) × 치우침 × 품.
     *
     * <p>배율이 <b>표시 총합</b>에 걸린다 (위력 부분에만 걸지 않는다). 위력에만 걸면 맨주먹 몫 1.0 이
     * 배율 밖에 남아 가벼운 병기가 <b>공짜 DPS</b>를 얻는다 — 검산: 그 형태에서 같은 품 안 DPS 편차가
     * 11.5% 로 벌어졌다. 총합에 걸면 {@code 공격력 × 공속} 이 치우침에 대해 <b>정확히 불변</b>이다.
     */
    public static double attackDamage(Series series, Bias bias, Craft craft) {
        return round2((BASE_ATTACK_DAMAGE + power(series)) * bias.damageMul() * craft.damageMul);
    }

    /** 표시 공속 = 계열 표준 공속 × 치우침(공격력 배율의 <b>정확한 역수</b>). 품은 공속을 건드리지 않는다 */
    public static double attackSpeed(Series series, Bias bias) {
        return round2(series.baseAttackSpeed() * bias.speedMul());
    }

    /** 실효 위력 (판정층용) = 표시 공격력 − 맨주먹 몫. 치우침·품이 반영된 실수 위력 */
    public static double effectivePower(Series series, Bias bias, Craft craft) {
        return round2(attackDamage(series, bias, craft) - BASE_ATTACK_DAMAGE);
    }

    /** 내구 = 등급(사다리) × 품(미세). 0 = 불괴 */
    public static int durability(Grade grade, Craft craft) {
        if (grade.durability == 0) {
            return 0;
        }
        return (int) Math.round(grade.durability * craft.durabilityMul);
    }

    /** 하위 호환 — 치우침·품 없는 표준 개체 */
    public static double attackDamage(Series series) {
        return attackDamage(series, Bias.균, Craft.보통);
    }

    // ══════════ 제작 ══════════

    /** 병기 1점 — 계열·등급 한글명 (예: make("검", "정련")). 표준 개체(균·보통) */
    public static ItemStack make(String series, String grade) {
        return make(Series.of(series), Grade.of(grade), List.of());
    }

    /**
     * 표준 개체 (균·보통) — 개체차 없는 <b>제식(制式) 병기</b>.
     *
     * <p>기존 호출자(WeaponShop 진열·지급)가 이 서명을 쓴다. 여기서 굴리면 <b>진열된 개체와 산 개체가
     * 달라진다</b> (WeaponShop 은 진열용·지급용으로 make 를 두 번 부른다) — 그래서 이 문은 결정론이다.
     * 개체를 골라 사는 병기점(진열 개체 고정)은 {@link #makeSeeded} 로 짓는다.
     */
    public static ItemStack make(Series series, Grade grade) {
        return make(series, grade, List.of());
    }

    /**
     * 표준 개체 + 각인.
     *
     * @param properties equipment.yml special_properties 의 키 (파사·한철·경명·명공각인·음양쌍인·탈혼)
     */
    public static ItemStack make(Series series, Grade grade, List<String> properties) {
        return make(series, grade, Bias.균, Craft.보통, properties);
    }

    /**
     * 병기 1점 — 개체차까지 명시. <b>순함수</b>: 같은 입력 → 같은 아이템 (PDC 영속 검증의 근거).
     *
     * @throws IllegalArgumentException 명공을 슬롯 없는 등급(범철)에 요구하거나, 각인이 슬롯을 넘길 때
     */
    public static ItemStack make(Series series, Grade grade, Bias bias, Craft craft,
                                 List<String> properties) {
        ensureLoaded();
        if (grade == Grade.마병 && series != DEMONIC_SERIES) {
            throw new IllegalArgumentException(
                    "마병은 도(刀)에만 존재한다 — 혈음도 (팩에 " + series.name() + "_mabyeong 텍스처가 없다)");
        }
        if (grade == Grade.마병) {
            // 사람이 벼린 것이 아니다 — 혈교의 의식이 응결시킨 것. 품도 치우침도 없다 (등록제 유일물)
            bias = Bias.균;
            craft = Craft.보통;
        }
        List<String> props = normalizeProperties(grade, craft, properties);

        ItemStack item = new ItemStack(series.base.of(grade));
        ItemMeta meta = item.getItemMeta();

        // ─── 모델 (팩과의 유일한 접점) ───
        if (series.modelId != null) {
            meta.setItemModel(new NamespacedKey("honcheon", "weapon/" + series.modelId + "_" + grade.slug));
        }
        // 마병 = 상태 변주 (custom_model_data.strings) — 미감정은 평범한 도로 보인다
        boolean demonic = grade == Grade.마병;
        if (demonic) {
            var cmd = meta.getCustomModelDataComponent();
            cmd.setStrings(List.of(STATE_UNIDENTIFIED));
            meta.setCustomModelDataComponent(cmd);
        }

        // ─── PDC — 판정층의 진실 (재질 근사를 대체한다) ───
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(KEY_SERIES, PersistentDataType.STRING, series.name());
        pdc.set(KEY_GRADE, PersistentDataType.STRING, grade.name());
        pdc.set(KEY_CLASS, PersistentDataType.STRING, series.powerKey);
        pdc.set(KEY_BIAS, PersistentDataType.STRING, bias.name());
        pdc.set(KEY_CRAFT, PersistentDataType.STRING, craft.name());
        pdc.set(KEY_PROPS, PersistentDataType.STRING, String.join(",", props));
        pdc.set(KEY_POWER, PersistentDataType.DOUBLE, effectivePower(series, bias, craft));
        if (demonic) {
            pdc.set(KEY_STATE, PersistentDataType.STRING, STATE_UNIDENTIFIED);
        }

        applyStats(meta, series, grade, bias, craft);
        applyTooltip(meta, series, grade, bias, craft, props,
                demonic ? STATE_UNIDENTIFIED : null, 0, null, 0);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * 각인 정리 — 등록된 것만, 슬롯 안에서만. 명공 품은 명공각인을 <b>스스로 하나 소모한다</b>
     * (중복 정의 금지: 각인의 효과는 equipment.yml 이 정의한다 — 여기선 슬롯만 센다).
     */
    private static List<String> normalizeProperties(Grade grade, Craft craft, List<String> requested) {
        List<String> props = new ArrayList<>();
        if (craft.isInscription()) {
            props.add(INSCRIPTION_MASTERWORK);
        }
        for (String p : requested) {
            if (!props.contains(p)) {
                props.add(p);
            }
        }
        if (craft.isInscription() && grade.slots < 1) {
            throw new IllegalArgumentException(
                    "명공(名工)은 각인이다 — " + grade.name() + "에는 각인 슬롯이 없다 (property_slots 0). "
                            + "아무리 벼려도 범철은 정련이 되지 않는다: 감당 격은 재질의 것이다");
        }
        if (props.size() > grade.slots) {
            throw new IllegalArgumentException(grade.name() + "의 각인 슬롯은 " + grade.slots
                    + "칸이다 — 요구 " + props.size() + "칸 " + props);
        }
        return List.copyOf(props);
    }

    /** equipment.yml special_properties 의 키 — 명공 품이 소모하는 각인 (중복 정의 금지) */
    public static final String INSCRIPTION_MASTERWORK = "명공각인";

    /**
     * 개체 굴림 — <b>결정론적</b> 씨앗. 같은 씨앗 → 같은 병기.
     *
     * <p>병기점의 무협적 감각은 <b>"진열대에서 병기를 고른다"</b>이다 — 사는 순간 굴리면
     * 고르는 행위가 사라지고, 진열된 검과 산 검이 다르면 세계가 거짓말을 한다.
     * 그래서 굴림은 <b>진열 시점</b>에 한 번, 씨앗으로 고정한다 (예: 씨앗 = 그날 날짜 × 진열칸).
     * 진열대는 하루 동안 같은 병기를 걸어두고, 다음 날 새 물건이 들어온다.
     */
    public static ItemStack makeSeeded(Series series, Grade grade, long seed) {
        Random random = new Random(seed);
        return make(series, grade, Bias.roll(random), Craft.roll(grade, random), List.of());
    }

    /** 굴림 — 전리품·기연용 (씨앗 없음). 만들어진 뒤엔 PDC 에 굳는다 */
    public static ItemStack makeRolled(Series series, Grade grade) {
        return makeSeeded(series, grade, ThreadLocalRandom.current().nextLong());
    }

    /**
     * 바닐라 수치 정리 — 재질 티어가 딸고 오는 공격력·공속·내구를 전부 덮어쓴다.
     *
     * <p>1.20.5+ 규칙: attribute_modifiers 컴포넌트를 세우면 그 아이템의 <b>기본 modifier 는 전부 대체</b>된다.
     * 따라서 공격력·공속을 우리가 명시하지 않으면 공속이 4.0(맨손)으로 튄다 — 둘 다 반드시 쓴다.
     *
     * <p>modifier 는 <b>툴팁이 표시할 값에서 역산</b>한다 — 표시 3.6 이면 modifier 는 2.6 이다.
     * 반올림을 표시 쪽에서만 하면 툴팁이 거짓말을 하게 된다.
     */
    private static void applyStats(ItemMeta meta, Series series, Grade grade, Bias bias, Craft craft) {
        meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(
                key("weapon_damage"), attackDamage(series, bias, craft) - BASE_ATTACK_DAMAGE,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(
                key("weapon_speed"), attackSpeed(series, bias) - BASE_ATTACK_SPEED,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        if (series.reachMod != 0.0) {
            // 창·월아산 — 간격이 곧 병기의 성격. 계열 고정: 치우침·품·강화가 못 건드린다
            meta.addAttributeModifier(Attribute.ENTITY_INTERACTION_RANGE, new AttributeModifier(
                    key("weapon_reach"), series.reachMod,
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        }

        // 내구 = 등급이 파는 것 ("가치는 보정이 아니라 생존") × 품(만듦새). 재질 내구(금검 32!)는 버린다
        int durability = durability(grade, craft);
        if (durability > 0) {
            ((Damageable) meta).setMaxDamage(durability);
        } else {
            meta.setUnbreakable(true);   // 신병·마병 — 세계 등록제 유일물은 삽질로 부러지지 않는다
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        }
        // 강호의 병기는 마법부여로 강해지지 않는다 (판정이 전부다).
        // 1.21.11 — setEnchantable(0) 은 이제 던진다("Enchantability must be positive"): 0 은 컴포넌트가
        // 담을 수 없는 값이다. 끄는 법은 값을 0 으로 두는 게 아니라 **컴포넌트를 지우는 것**이다.
        // minecraft:enchantable 이 없는 아이템은 마법부여대가 받지 않는다 (1.21.2+ 규칙).
        meta.setEnchantable(null);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);   // 바닐라 수치 줄 차단 — 툴팁은 우리가 그린다
        meta.setMaxStackSize(1);
    }

    // ══════════ 툴팁 — 규칙이 위, 이력이 가운데, 서사가 아래 ══════════

    private static final String RULE = ChatColor.DARK_GRAY + "─────────────";

    @SuppressWarnings("deprecation")
    private static void applyTooltip(ItemMeta meta, Series series, Grade grade, Bias bias, Craft craft,
                                     List<String> properties, String state,
                                     int reforged, Beloved beloved, int clashes) {
        // 미감정 마병은 거짓말을 한다 — 감정 전엔 평범한 정련 도 (texture_layer_design: "정체 불명")
        boolean lie = STATE_UNIDENTIFIED.equals(state);
        Grade shown = lie ? Grade.정련 : grade;

        // 제목: 등급(漢) 계열(漢) — 부제(치우침). 색 단독 금지 → 등급·치우침 모두 글자로 두 번 말한다
        String title = shown.color + shown.name() + hanja(shown) + " " + series.name() + hanja(series);
        if (bias.subtitle != null) {
            title += ChatColor.DARK_GRAY + " — " + ChatColor.GRAY + bias.subtitle
                    + ChatColor.DARK_GRAY + "(" + bias.hanja + ")";
        }
        meta.setDisplayName(title);

        List<String> lore = new ArrayList<>();
        lore.add(RULE);

        // ─── 수치 (attribute 와 같은 값) ───
        double damage = attackDamage(series, bias, craft);
        double speed = attackSpeed(series, bias);
        lore.add(ChatColor.GRAY + "공격력 " + ChatColor.WHITE + stat(damage)
                + ChatColor.DARK_GRAY + " · " + ChatColor.GRAY + "공속 "
                + ChatColor.WHITE + stat(speed)
                + ChatColor.GRAY + " (초당 " + stat(speed) + "회)");
        lore.add(ChatColor.GRAY + "사거리 " + ChatColor.WHITE + fmt(series.reach()) + "m"
                + ChatColor.DARK_GRAY + " (" + series.name() + " 계열 고정)");

        // ─── 등급이 파는 것 ───
        int durability = durability(shown, lie ? Craft.보통 : craft);
        lore.add(ChatColor.GRAY + "감당 격: " + shown.color + withstands(shown) + "까지"
                + ChatColor.DARK_GRAY + " · " + ChatColor.GRAY + "내구 " + ChatColor.WHITE
                + (durability == 0 ? "불괴(不壞)" : String.valueOf(durability)));

        // ─── 품 ───
        if (!lie) {
            lore.add(ChatColor.GRAY + "품(品): " + craftColor(craft) + craft.name()
                    + ChatColor.DARK_GRAY + "(" + craft.hanja + ")"
                    + (reforged > 0 ? ChatColor.DARK_GRAY + " · 재련 " + reforged + "회" : ""));
        }

        // ─── 판정 (등급은 0 — 애병만이 준다) ───
        int bonus = judgmentBonus(shown) + (beloved == Beloved.손에_익다 || beloved == Beloved.명병
                || beloved == Beloved.통령 ? 1 : 0);
        if (bonus != 0) {
            lore.add(ChatColor.GRAY + "판정 보정 " + ChatColor.WHITE + "+" + bonus
                    + ChatColor.DARK_GRAY + " (주인의 손에서만)");
        }

        // ─── 각인 ───
        if (!properties.isEmpty() && !lie) {
            lore.add(RULE);
            for (String property : properties) {
                lore.add(ChatColor.LIGHT_PURPLE + "◆ " + property + ChatColor.GRAY
                        + " — " + propertyEffect(property));
            }
            lore.add(ChatColor.DARK_GRAY + "각인 " + properties.size() + "/" + shown.slots + "칸");
        }

        // ─── 이력(歷) — 검을 쓸수록 자란다 ───
        if (!lie && (clashes > 0 || beloved != null)) {
            lore.add(RULE);
            if (beloved != null) {
                lore.add(ChatColor.GOLD + "애병(愛兵) · " + beloved.display()
                        + ChatColor.GRAY + " — " + beloved.grants);
            }
            lore.add(ChatColor.GRAY + "생사 격돌 " + ChatColor.WHITE + clashes + "회"
                    + (beloved == null
                    ? ChatColor.DARK_GRAY + " (손에 익기까지 " + Math.max(0, CLASH_FOR_FIRST_STAGE - clashes) + "회)"
                    : ""));
        }

        // ─── 서사 ───
        lore.add(RULE);
        for (String line : flavor(series, shown)) {
            lore.add(ChatColor.WHITE + line);
        }
        if (!lie) {
            lore.add(ChatColor.WHITE + "\"" + craft.flavor + "\"");
        }
        lore.add(ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + warning(shown));
        if (!lie && grade == Grade.마병) {
            lore.add(ChatColor.DARK_RED + "" + ChatColor.ITALIC + "혈향 — 적중마다 상대 원기를 마신다. 그리고 너를 침식한다.");
        }

        meta.setLore(lore);
    }

    /** 품의 색 — 등급 색과 겹치지 않게. 색 단독 금지이므로 한자를 항상 함께 쓴다 */
    private static ChatColor craftColor(Craft craft) {
        return switch (craft) {
            case 조잡 -> ChatColor.DARK_GRAY;
            case 보통 -> ChatColor.GRAY;
            case 정품 -> ChatColor.GREEN;
            case 명공 -> ChatColor.LIGHT_PURPLE;
        };
    }

    /** 한자 병기 — 색 단독 금지(colorblind_rule)의 연장: 등급·계열을 글자가 두 번 말한다 */
    private static String hanja(Grade grade) {
        return ChatColor.DARK_GRAY + "(" + switch (grade) {
            case 범철 -> "凡鐵";
            case 정련 -> "精鍊";
            case 보병 -> "寶兵";
            case 신병 -> "神兵";
            case 마병 -> "魔兵";
        } + ")" + grade.color;
    }

    private static String hanja(Series series) {
        return switch (series) {
            case 검 -> "";
            case 도 -> "";
            case 창 -> "";
            case 권갑 -> "";
            case 단검 -> "";
            case 부 -> ChatColor.DARK_GRAY + "(斧)";
            case 겸 -> ChatColor.DARK_GRAY + "(鎌)";
            case 월아산 -> ChatColor.DARK_GRAY + "(月牙鏟)";
            case 구 -> ChatColor.DARK_GRAY + "(鉤)";
        };
    }

    /** 서사 한 줄 — 등급(무엇인가) + 계열(어떻게 싸우는가) */
    private static List<String> flavor(Series series, Grade grade) {
        String byGrade = switch (grade) {
            case 범철 -> "아무 대장간에서나 나온다. 무림인의 첫 병기.";
            case 정련 -> "명공의 손을 거친 병기. 검기를 받아낸다.";
            case 보병 -> "문파의 신물. 소지 자체가 소문이 된다.";
            case 신병 -> "세계에 몇 자루 없다. 주인이 바뀌면 강호가 술렁인다.";
            case 마병 -> "혈교 인신공양의 잔재. 응결된 원기가 스스로 갈증을 갖는다.";
        };
        String bySeries = switch (series) {
            case 검 -> "곧은 양날 — 중용의 병기. 빠르지도 느리지도 않다.";
            case 도 -> "한쪽 날의 무게 — 검보다 느리고, 한 합이 무겁다.";
            case 창 -> "간격이 곧 무기다 — 닿기 전에 닿는다.";
            case 권갑 -> "날이 없다 — 위력은 병기가 아니라 무공이 만든다.";
            case 단검 -> "짧고 가볍다 — 가장 빠른 손. 대신 한 합이 가볍다.";
            case 부 -> "가장 느리고 가장 무겁다 — 병기를 부수는 병기.";
            case 겸 -> "걸어 채는 날 — 가볍고 빠르다.";
            case 월아산 -> "승려의 장병기 — 길고 느리며 간격이 있다.";
            case 구 -> "걸고 당긴다 — 상대의 병기를 얽는 손.";
        };
        return List.of(byGrade, bySeries);
    }

    /** 그 등급이 무엇을 못 견디는가 — 경고 (qi_manifestation.yml weapon_break) */
    private static String warning(Grade grade) {
        return switch (grade) {
            case 범철 -> "발경까지만 견딘다 — 검기를 두르면 3합마다 병기가 상한다.";
            case 정련 -> "검기까지 견딘다 — 강기 앞에서는 3합이 한계다.";
            case 보병 -> "강기까지 견딘다 — 심검 앞에서는 한 합에 끊긴다.";
            case 신병 -> "심검을 받아낸다 — 이 병기를 끊을 것은 세상에 거의 없다.";
            case 마병 -> "강기를 견딘다 — 그러나 견디는 값은 네 원기로 치른다.";
        };
    }

    private static String propertyEffect(String property) {
        ensureLoaded();
        Map<String, Object> table = RulesConfig.section(equipment, "special_properties");
        Object spec = table.get(property);
        if (spec instanceof Map<?, ?> m && m.get("effect") != null) {
            return String.valueOf(m.get("effect"));
        }
        return "?";
    }

    /** 사거리·기타 — 소수 1자리 */
    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /**
     * 공격력·공속 — 소수 2자리, 단 끝의 0 은 지운다 (5.00 → "5", 1.60 → "1.6", 4.29 → "4.29").
     * attribute 에 실린 값과 <b>같은 반올림</b>을 통과한 값만 그린다 — 툴팁은 거짓말하지 않는다.
     */
    private static String stat(double value) {
        String s = String.format(Locale.ROOT, "%.2f", value);
        if (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);
        }
        return s;
    }

    // ══════════ 강화 ① 재련(再鍊) — 명공의 손을 빌린다 ══════════

    /**
     * 재련 결과. 실패에도 등급이 있다 — 무협에서 쇠를 다시 불에 넣는 것은 도박이다.
     */
    public enum Reforge {
        /** 품이 한 단계 올랐다 */
        성공,
        /** 굴림에 졌다 — 병기가 상했다 (내구가 깎인다). 품은 그대로 */
        손상,
        /** 대실패(뱀눈) — 쇠가 갈라졌다. 병기가 사라진다 */
        파손,
        /** 재련이 성립하지 않는다 (천장 도달·슬롯 없음·마병) */
        불가
    }

    public record ReforgeResult(Reforge outcome, ItemStack item, String message) { }

    /**
     * 재련 난도 — 야철수의 2d6 + 솜씨가 넘어야 하는 수.
     *
     * <p><b>첫 단계(조잡 → 보통)는 굴리지 않는다</b> — 초보가 첫 검을 잃으면 게임을 그만둔다.
     * 조잡을 보통으로 되돌리는 것은 벼리기가 아니라 <b>손질</b>이다.
     *
     * <p>config 등록 대기 — equipment.yml {@code reforge.difficulty} 로 옮긴다.
     */
    private static int reforgeDifficulty(Craft target) {
        return switch (target) {
            case 보통 -> 0;    // 무조건 성공 (손질)
            case 정품 -> 8;    // 2d6 ≥ 8 - 솜씨 … 표준 야철수(솜씨 +2)면 58% 언저리
            case 명공 -> 12;   // 명공의 각인은 명공만이 새긴다 — 야철수(+2)로는 2d6=10+ 가 필요하다
            default -> 99;
        };
    }

    /**
     * 재련 삯 (문) — config 등록 대기: economy.yml {@code price_table.용역.재련_<품>}.
     * 등급이 오를수록 값이 오른다 (보병을 명공에게 맡기는 값은 서민의 몇 해치다).
     */
    public static int reforgeFee(Grade grade, Craft target) {
        int base = switch (target) {
            case 보통 -> 20;
            case 정품 -> 150;
            case 명공 -> 1200;
            default -> 0;
        };
        int gradeMul = switch (grade) {
            case 범철 -> 1;
            case 정련 -> 3;
            case 보병 -> 10;
            case 신병, 마병 -> 30;
        };
        return base * gradeMul;
    }

    /**
     * 재련 — 야철수(治鐵手)에게 병기를 맡긴다. 품이 한 단계 오른다.
     *
     * <p><b>천장</b>: {@link Craft#ceiling} — 슬롯 없는 범철은 정품에서 멈춘다.
     * 아무리 벼려도 <b>범철은 정련이 되지 않는다</b>: 감당 격과 내구 사다리는 <b>재질</b>의 것이고,
     * 재련이 파는 것은 <b>만듦새</b>뿐이다. (명공 범철 250×1.30=325 &lt; 조잡 정련 800×0.80=640)
     *
     * <p><b>치우침은 바뀌지 않는다</b> — 쇠를 다시 벼려도 그 검의 성격은 그대로다.
     * 무게 배분은 만들 때 정해지는 것이지 손질로 옮겨지는 것이 아니다.
     *
     * @param smithSkill 야철수의 솜씨 보정 (청하현 야철수 = +2 · config 등록 대기)
     * @param random     굴림 (테스트는 고정 씨앗으로)
     */
    public static ReforgeResult reforge(ItemStack item, int smithSkill, Random random) {
        Grade grade = gradeOf(item);
        if (grade == null) {
            return new ReforgeResult(Reforge.불가, item, "혼천의 병기가 아니다 — 야철수가 손도 대지 않는다.");
        }
        if (grade == Grade.마병) {
            return new ReforgeResult(Reforge.불가, item,
                    "마병은 사람이 벼린 것이 아니다 — 불에 넣으면 쇠가 비명을 지른다.");
        }
        Craft craft = craftOf(item);
        Craft target = craft.next();
        Craft ceiling = Craft.ceiling(grade);
        if (target == null || craft.ordinal() >= ceiling.ordinal()) {
            return new ReforgeResult(Reforge.불가, item,
                    grade.name() + "은(는) " + ceiling.name() + "이 천장이다 — "
                            + (grade.slots < 1
                            ? "각인 슬롯이 없는 쇠에는 명공의 각인이 앉지 못한다. 더 벼려도 감당 격은 오르지 않는다."
                            : "이 이상은 사람의 손이 아니라 세월의 몫이다 (애병)."));
        }

        // 명공은 각인이다 — 앉을 칸이 없으면 재련이 성립하지 않는다 (파사가 그 칸을 쓰고 있다면)
        if (target.isInscription() && propertiesOf(item).size() >= grade.slots) {
            return new ReforgeResult(Reforge.불가, item,
                    "명공의 각인이 앉을 칸이 없다 — " + propertiesOf(item) + "이(가) "
                            + grade.name() + "의 " + grade.slots + "칸을 이미 쓰고 있다. "
                            + "각인을 지우지 않는 한 이 병기는 정품이 천장이다.");
        }

        int difficulty = reforgeDifficulty(target);
        if (difficulty > 0) {
            int d1 = random.nextInt(6) + 1;
            int d2 = random.nextInt(6) + 1;
            int roll = d1 + d2;
            if (roll == 2) {   // 뱀눈 — 무협의 도박에는 바닥이 있다
                return new ReforgeResult(Reforge.파손, null,
                        "쇠가 갈라졌다. 야철수가 말없이 조각을 쓸어 담는다. (2d6 = 2)");
            }
            if (roll + smithSkill < difficulty) {
                ItemStack damaged = item.clone();
                ItemMeta meta = damaged.getItemMeta();
                int max = durability(grade, craft);
                if (max > 0 && meta instanceof Damageable dmg) {
                    // 상했다 — 최대 내구의 30%만큼 깎인다 (부러지진 않는다)
                    dmg.setDamage(Math.min(max - 1, dmg.getDamage() + (int) Math.round(max * 0.30)));
                    damaged.setItemMeta(meta);
                }
                return new ReforgeResult(Reforge.손상, damaged,
                        "불이 모자랐다. 날에 금이 갔다 — 품은 그대로다. (2d6+" + smithSkill + " = "
                                + (roll + smithSkill) + " < " + difficulty + ")");
            }
        }

        ItemStack reforged = rebuild(item, target, null);
        ItemMeta meta = reforged.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(KEY_REFORGE, PersistentDataType.INTEGER, reforgeCountOf(item) + 1);
        reforged.setItemMeta(meta);
        // 이력이 늘었으니 툴팁을 다시 그린다
        reforged = refresh(reforged);
        return new ReforgeResult(Reforge.성공, reforged,
                "쇠가 울었다 — " + craft.name() + " → " + target.name() + "("+ target.hanja + ")"
                        + (target.isInscription() ? ". 야철수가 각인을 새긴다." : "."));
    }

    // ══════════ 강화 ② 각인(刻印) — 속성을 새긴다 ══════════

    /**
     * 각인 — equipment.yml special_properties 에 <b>등록된 것만</b>, 등급의 슬롯 <b>안에서만</b>.
     *
     * <p>명공 품이 이미 {@link #INSCRIPTION_MASTERWORK} 로 한 칸을 먹고 있음에 유의 —
     * 그것이 명공의 <b>기회비용</b>이다: 명공 정련(슬롯 1)은 파사를 새길 칸이 없다.
     * 이 슬롯 경제가 "명공이 정품의 상위 호환"이 되는 것을 막는다
     * (마공 상대라면 <b>정품 + 파사</b>가 명공보다 낫다).
     *
     * @return 각인된 병기, 또는 실패 사유 (item == null)
     */
    public static ReforgeResult inscribe(ItemStack item, String property) {
        Grade grade = gradeOf(item);
        if (grade == null) {
            return new ReforgeResult(Reforge.불가, item, "혼천의 병기가 아니다.");
        }
        ensureLoaded();
        Map<String, Object> table = RulesConfig.section(equipment, "special_properties");
        if (!table.containsKey(property)) {
            return new ReforgeResult(Reforge.불가, item,
                    "등록되지 않은 각인이다: " + property + " (등록부: " + table.keySet() + ")");
        }
        if (property.equals(INSCRIPTION_MASTERWORK)) {
            return new ReforgeResult(Reforge.불가, item,
                    "명공각인은 새기는 것이 아니라 벼려지는 것이다 — 재련으로 명공에 이르러라.");
        }
        List<String> props = new ArrayList<>(propertiesOf(item));
        if (props.contains(property)) {
            return new ReforgeResult(Reforge.불가, item, "이미 새겨져 있다: " + property);
        }
        if (props.size() >= grade.slots) {
            return new ReforgeResult(Reforge.불가, item,
                    grade.name() + "의 각인 슬롯은 " + grade.slots + "칸 — 이미 " + props + " 로 찼다"
                            + (props.contains(INSCRIPTION_MASTERWORK)
                            ? " (명공각인이 한 칸을 쓰고 있다 — 명공의 값이다)" : ""));
        }
        props.add(property);
        return new ReforgeResult(Reforge.성공, rebuild(item, craftOf(item), props),
                property + "을(를) 새겼다 — " + propertyEffect(property));
    }

    // ══════════ 강화 ③ 애병(愛兵) — 시간과 피가 벼린다 ══════════

    /**
     * 생사 격돌 1회를 검에 새긴다 — <b>강화의 최고봉은 사는 것이 아니라 함께 산 시간</b>이다.
     *
     * <p>세는 것: {@code beloved_weapon.history_inputs.생사_격돌_생존} — 죽고 살 만한 싸움을
     * <b>살아서</b> 끝낸 횟수. 잡몹 학살로 채워지면 애병이 값싸진다 → <b>호출자가 게이트한다</b>
     * (권장: 적대 인간형 처치 또는 자신이 피를 흘린 전투의 종료 시 1회. 몬스터 방목 사냥은 세지 않는다).
     *
     * <p>주인은 <b>처음 격돌을 함께한 손</b>이 된다. 다른 손에서는 보정이 나오지 않고 카운터도 늘지 않는다
     * (equipment.yml beloved_weapon.principle — 약탈 메타 차단).
     *
     * @return 갱신된 병기 (승급했으면 툴팁이 자란다). 혼천 병기가 아니면 원본
     */
    public static ItemStack recordClash(ItemStack item, UUID wielder) {
        if (!isWeapon(item)) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String owner = pdc.get(KEY_OWNER, PersistentDataType.STRING);
        if (owner == null) {
            owner = wielder.toString();
            pdc.set(KEY_OWNER, PersistentDataType.STRING, owner);   // 처음 피를 본 손이 주인이다
        } else if (!owner.equals(wielder.toString())) {
            return item;   // 남의 애병 — 이력이 쌓이지 않는다 (강탈해도 검은 옛 주인의 것)
        }
        int clashes = pdc.getOrDefault(KEY_CLASH, PersistentDataType.INTEGER, 0) + 1;
        pdc.set(KEY_CLASH, PersistentDataType.INTEGER, clashes);

        if (clashes >= CLASH_FOR_FIRST_STAGE && pdc.get(KEY_BELOVED, PersistentDataType.STRING) == null) {
            pdc.set(KEY_BELOVED, PersistentDataType.STRING, Beloved.손에_익다.name());
        }
        // 명병·통령: 생사 고비 3회 + 명성 사건 / 동행 10년 + 주인 화경 — MVT에 그 축이 없다 (자리만 예약)
        item.setItemMeta(meta);
        return refresh(item);
    }

    /**
     * 애병 판정 보정 — <b>주인의 손에서만</b>. 무기가 파는 유일한 판정 보정이다
     * (combat.yml 이 등급 judgment_bonus 를 전부 0 으로 내렸다 — 그 자리를 이력이 가진다).
     *
     * <p>판정층 배선 대기: SkillEngine 의 판정 합산에 이 값을 더하면 된다 (파일 소유가 달라 미배선).
     */
    public static int belovedJudgmentBonus(ItemStack item, UUID wielder) {
        Beloved stage = belovedOf(item);
        if (stage == null) {
            return 0;
        }
        String owner = tag(item, KEY_OWNER);
        if (owner == null || !owner.equals(wielder.toString())) {
            return 0;   // 강탈한 남의 애병 = 등급값
        }
        return 1;   // 손에_익다.grants.judgment_bonus_owner_only: 1 (명병·통령도 이 +1 을 유지)
    }

    // ══════════ 재구축 — 개체차·이력을 보존한 채 툴팁·attribute 를 다시 그린다 ══════════

    /**
     * 같은 병기를 <b>새 품·새 각인</b>으로 다시 벼린다. 계열·등급·치우침·이력(주인·격돌·애병)은 그대로 따라온다.
     * PDC 영속의 증명: 같은 아이템을 다시 만들면 같은 값이 나온다.
     */
    private static ItemStack rebuild(ItemStack source, Craft craft, List<String> properties) {
        Series series = seriesOf(source);
        Grade grade = gradeOf(source);
        Bias bias = biasOf(source);
        List<String> props = properties == null ? propertiesOf(source) : properties;
        // 명공각인은 품이 소유한다 — 품이 명공이 아니면 각인 목록에서 뺀다 (중복 정의 금지)
        List<String> carried = new ArrayList<>(props);
        carried.remove(INSCRIPTION_MASTERWORK);

        ItemStack rebuilt = make(series, grade, bias, craft, carried);
        ItemMeta from = source.getItemMeta();
        ItemMeta to = rebuilt.getItemMeta();
        PersistentDataContainer src = from.getPersistentDataContainer();
        PersistentDataContainer dst = to.getPersistentDataContainer();
        copy(src, dst, KEY_OWNER, PersistentDataType.STRING);
        copy(src, dst, KEY_BELOVED, PersistentDataType.STRING);
        copy(src, dst, KEY_STATE, PersistentDataType.STRING);
        copy(src, dst, KEY_CLASH, PersistentDataType.INTEGER);
        copy(src, dst, KEY_BOND_DAYS, PersistentDataType.INTEGER);
        copy(src, dst, KEY_REFORGE, PersistentDataType.INTEGER);
        // 상한 재계산 — 품이 오르면 최대 내구가 오른다. 이미 닳은 만큼은 그대로 가져간다 (새것이 되지 않는다)
        if (from instanceof Damageable was && to instanceof Damageable now && grade.durability > 0) {
            now.setDamage(Math.min(was.getDamage(), durability(grade, craft) - 1));
        }
        rebuilt.setItemMeta(to);
        return refresh(rebuilt);
    }

    private static <T> void copy(PersistentDataContainer src, PersistentDataContainer dst,
                                 NamespacedKey k, PersistentDataType<T, T> type) {
        T v = src.get(k, type);
        if (v != null) {
            dst.set(k, type, v);
        }
    }

    /** 툴팁만 다시 그린다 — PDC 가 바뀌었을 때 (이력 증가·감정·재련) */
    public static ItemStack refresh(ItemStack item) {
        if (!isWeapon(item)) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        applyTooltip(meta, seriesOf(item), gradeOf(item), biasOf(item), craftOf(item),
                propertiesOf(item), stateOf(item),
                pdc.getOrDefault(KEY_REFORGE, PersistentDataType.INTEGER, 0),
                belovedOf(item),
                pdc.getOrDefault(KEY_CLASH, PersistentDataType.INTEGER, 0));
        item.setItemMeta(meta);
        return item;
    }

    // ══════════ 감정 — 마병의 가면을 벗긴다 ══════════

    /** 미감정 마병 → 발현. 도가·불가 심법·신의만이 혈향을 읽는다 (equipment.yml identification) */
    public static ItemStack reveal(ItemStack item) {
        if (gradeOf(item) != Grade.마병) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(STATE_REVEALED));
        meta.setCustomModelDataComponent(cmd);
        meta.getPersistentDataContainer().set(KEY_STATE, PersistentDataType.STRING, STATE_REVEALED);
        item.setItemMeta(meta);
        return refresh(item);
    }

    // ══════════ 판독 — 판정층의 입구 (재질 근사를 대체한다) ══════════

    private static String tag(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .get(key, PersistentDataType.STRING);
    }

    /** 혼천 병기인가 — PDC 태그가 있는 것만 (바닐라 철검은 바닐라다) */
    public static boolean isWeapon(ItemStack item) {
        return tag(item, KEY_SERIES) != null;
    }

    /** 계열 — 혼천 병기가 아니면 null */
    public static Series seriesOf(ItemStack item) {
        String value = tag(item, KEY_SERIES);
        return value == null ? null : Series.of(value);
    }

    /** 등급 — 혼천 병기가 아니면 null (SkillEngine 은 이때 "범철"로 근사하지 말고 맨손 취급) */
    public static Grade gradeOf(ItemStack item) {
        String value = tag(item, KEY_GRADE);
        return value == null ? null : Grade.of(value);
    }

    /** 치우침 — 태그가 없는 구(舊) 병기는 균(均)으로 읽는다 (하위 호환) */
    public static Bias biasOf(ItemStack item) {
        String value = tag(item, KEY_BIAS);
        return value == null ? Bias.균 : Bias.of(value);
    }

    /** 품 — 태그가 없는 구(舊) 병기는 보통으로 읽는다 (하위 호환) */
    public static Craft craftOf(ItemStack item) {
        String value = tag(item, KEY_CRAFT);
        return value == null ? Craft.보통 : Craft.of(value);
    }

    /** 각인 목록 — 없으면 빈 목록 */
    public static List<String> propertiesOf(ItemStack item) {
        String value = tag(item, KEY_PROPS);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(","));
    }

    /** 애병 단계 — 0단이면 null */
    public static Beloved belovedOf(ItemStack item) {
        String value = tag(item, KEY_BELOVED);
        return value == null ? null : Beloved.of(value);
    }

    /** 생사 격돌 횟수 */
    public static int clashesOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 0;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .getOrDefault(KEY_CLASH, PersistentDataType.INTEGER, 0);
    }

    /** 재련 성공 횟수 */
    public static int reforgeCountOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 0;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .getOrDefault(KEY_REFORGE, PersistentDataType.INTEGER, 0);
    }

    /** 주인 UUID 문자열 — 아직 피를 보지 않은 병기는 null */
    public static String ownerOf(ItemStack item) {
        return tag(item, KEY_OWNER);
    }

    /** 감당 등급 문자열 — SkillEngine.weaponGradeOf 의 정식 대체 (없으면 null) */
    public static String gradeNameOf(ItemStack item) {
        return tag(item, KEY_GRADE);
    }

    /** combat.yml weapon_power 키 — SkillEngine.weaponClassOf 의 정식 대체 (없으면 null) */
    public static String weaponClassOf(ItemStack item) {
        return tag(item, KEY_CLASS);
    }

    /**
     * 실효 위력 (치우침·품 반영) — 판정층이 계열 표준 위력 대신 읽어야 할 값.
     * 혼천 병기가 아니면 -1 (호출자가 맨손으로 처리한다).
     */
    public static double effectivePowerOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return -1;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .getOrDefault(KEY_POWER, PersistentDataType.DOUBLE, -1.0);
    }

    /** 마병 감정 상태 — unidentified | revealed | null(마병 아님) */
    public static String stateOf(ItemStack item) {
        return tag(item, KEY_STATE);
    }

    // ══════════ 가격 — economy.yml price_table.장비 (등록된 것만 판다) ══════════

    /** 시장 가격 (문). 등록되지 않은 품목(비매·미등록)은 -1 — 발명하지 않는다 */
    public static int price(com.honcheon.core.rules.EconomyEngine economy,
                            Series series, Grade grade, int economyIndex) {
        try {
            int base = economy.basePrice("장비", series.name() + "_" + grade.name());
            return economy.adjustedPrice(base, economyIndex);
        } catch (RuntimeException notListed) {
            return -1;   // economy.yml 미등록 — 상점 진열 대상이 아니다 (등록제)
        }
    }

    /**
     * 개체 가격 — 품이 값을 매긴다 (치우침은 값을 바꾸지 않는다: 취향이지 우열이 아니다).
     * config 등록 대기: economy.yml {@code price_table.장비.품_배율}.
     */
    public static int price(com.honcheon.core.rules.EconomyEngine economy,
                            Series series, Grade grade, Craft craft, int economyIndex) {
        int base = price(economy, series, grade, economyIndex);
        if (base < 0) {
            return -1;
        }
        double mul = switch (craft) {
            case 조잡 -> 0.6;
            case 보통 -> 1.0;
            case 정품 -> 1.8;
            case 명공 -> 5.0;
        };
        return (int) Math.round(base * mul);
    }

    // ══════════ 전리품 — 남발 금지 ══════════

    /** 전리품 후보 계열 — 무장 인간형이 떨어뜨릴 법한 것만 (짐승은 병기를 지니지 않는다) */
    private static final Series[] LOOT_SERIES = {Series.검, Series.도, Series.단검};

    /**
     * 전리품 굴림 — 무장 인간형(산적 대역)만, 범철만, 드물게. <b>개체차는 굴린다</b>
     * (주인이 쓰던 검이다 — 제식일 이유가 없다).
     *
     * @return 병기 1점 또는 null (대부분 null)
     */
    public static ItemStack rollLoot(EntityType type) {
        double chance = switch (type) {
            case PILLAGER, VINDICATOR -> 0.02;   // 무장한 인간형 — 녹림 대역
            case ZOMBIE, HUSK -> 0.005;          // 굶주린 시체가 쥐고 있던 것
            default -> 0.0;                      // 짐승·주민 — 병기 없음
        };
        if (chance <= 0.0 || ThreadLocalRandom.current().nextDouble() >= chance) {
            return null;
        }
        Series series = LOOT_SERIES[ThreadLocalRandom.current().nextInt(LOOT_SERIES.length)];
        ItemStack loot = makeRolled(series, Grade.범철);
        // 전리품은 새것이 아니다 — 절반쯤 상한 채로 나온다 (주인이 쓰던 것)
        ItemMeta meta = loot.getItemMeta();
        ((Damageable) meta).setDamage(durability(Grade.범철, craftOf(loot)) / 2);
        loot.setItemMeta(meta);
        return loot;
    }

    /** 진단용 — 계열 × 치우침 × 품 대조표 한 줄 (툴팁 ↔ attribute 대조의 근거) */
    public static String describe(Series series, Bias bias, Craft craft, Grade grade) {
        return String.join(" · ", Arrays.asList(
                series.name() + "/" + bias.name() + "/" + craft.name() + "/" + grade.name(),
                "공격력 " + stat(attackDamage(series, bias, craft)),
                "공속 " + stat(attackSpeed(series, bias)),
                "사거리 " + fmt(series.reach()) + "m",
                "내구 " + durability(grade, craft),
                "DPS " + fmt(attackDamage(series, bias, craft) * attackSpeed(series, bias))));
    }

    private Weapons() {
    }
}
