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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 병기 제작소 — 무협 무기를 하나의 문법으로 찍어낸다.
 *
 * <p>계약(정본):
 * <ul>
 *   <li>config/resourcepack_design.yml {@code item_channels.무기} — 베이스 아이템(등급) × 모델 키(계열)</li>
 *   <li>config/equipment.yml {@code weapon_grades} — 감당 격·판정 보정 (툴팁의 원천)</li>
 *   <li>config/combat.yml {@code damage.weapon_power} — 계열별 무기 위력 (= 공격력의 원천)</li>
 *   <li>config/economy.yml {@code price_table.장비} — 가격 (상점의 원천)</li>
 * </ul>
 *
 * <p>세 가지 규율:
 * <ol>
 *   <li><b>등급은 공격력이 아니다</b> — 재질 티어(돌·철·다이아·금·네더라이트)가 딸고 오는 바닐라 공격력·공속을
 *       전부 덮어쓴다. 공격력·공속·사거리는 <b>계열</b>이 정하고, 등급이 파는 것은 <b>생존</b>(내구)과
 *       <b>감당 격</b>뿐이다 (equipment.yml: "가치는 보정이 아니라 생존").</li>
 *   <li><b>PDC가 진실</b> — 등급·계열을 재질로 근사하지 않는다. {@link #seriesOf}·{@link #gradeOf}·
 *       {@link #weaponClassOf} 가 판정층의 유일한 입력이다.</li>
 *   <li><b>팩 게이트</b> — 팩에 구워지지 않은 계열(부·겸·월아산·구)에는 {@code item_model} 을 붙이지 않는다.
 *       없는 모델 키를 가리키면 보라-검정 체크가 된다. 텍스처가 구워지면 {@link Series#modelId} 만 채우면 켜진다.</li>
 * </ol>
 *
 * <p>툴팁은 UI다 — 표시된 공격력·공속·사거리는 실제 attribute modifier 와 <b>같은 상수에서 계산</b>된다
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

    public static final String STATE_UNIDENTIFIED = "unidentified";
    public static final String STATE_REVEALED = "revealed";

    private static NamespacedKey key(String value) {
        // 플러그인 이름이 아니라 고정 네임스페이스 — 판정층이 리터럴로 재구성할 수 있어야 한다
        return new NamespacedKey("honcheon", value);
    }

    // ══════════ 등급 ══════════

    /** 등급 = 베이스 아이템(재질) + 색 + 내구. 순서 = 사다리 (equipment.yml weapon_grades) */
    public enum Grade {
        범철("beomcheol", ChatColor.GRAY, 250),
        정련("jeongryeon", ChatColor.WHITE, 800),
        보병("bobyeong", ChatColor.AQUA, 2000),
        신병("sinbyeong", ChatColor.GOLD, 0),       // 0 = 불괴 (세계 등록제 유일물)
        마병("mabyeong", ChatColor.DARK_RED, 0);    // 0 = 불괴 (혈교 잔재 — 부러지지 않는다)

        /** 모델 키 접미사 (팩 파일명) */
        public final String slug;
        /** semantic_colors.장비_품질 — 색 단독 금지(colorblind_rule)이므로 등급명을 글자로도 쓴다 */
        public final ChatColor color;
        /** 바닐라 max_damage 덮어쓰기. 0 = Unbreakable (금검 내구 32 같은 재질 사고를 막는다) */
        public final int durability;

        Grade(String slug, ChatColor color, int durability) {
            this.slug = slug;
            this.color = color;
            this.durability = durability;
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
     * 팩 담당이 {@code honcheon:weapon/<modelId>_<등급slug>} 를 구우면 modelId 만 채워 점등한다.
     *
     * <p>공속: 플레이어 기본 공격속도 4.0 에 더해지는 값(ADD_NUMBER). 표시 공속 = 4.0 + speedMod.
     * 바닐라 대역(도끼 0.8~1.0 / 검 1.6 / 괭이 ~4.0) 안에 있다.
     * 사거리: 기본 상호작용 거리 3.0 에 더해지는 값. 0 이면 바닐라 그대로.
     */
    public enum Series {
        //     한글  모델키      베이스 종류     위력키    공속mod  사거리mod
        검("sword", Base.SWORD, "검", -2.4, 0.0),      // 표준 1.6/s — 중용의 병기
        도("dao", Base.SWORD, "도", -2.6, 0.0),        // 1.4/s — 한날·무겁다
        창("spear", Base.SWORD, "창", -2.9, 2.0),      // 1.1/s — 느리다. 대신 간격 5.0m
        권갑("gauntlet", Base.SWORD, "맨손", -2.0, 0.0), // 2.0/s — 맨손에 가깝다 (위력은 무공이 만든다)
        단검("dagger", Base.SWORD, "단검", -1.8, 0.0),  // 2.2/s — 가장 빠르다 (속검)
        // ─── 바닐라 도구 징발 (18반 병기) — 텍스처 미구움: modelId=null (팩 게이트) ───
        부(null, Base.AXE, "중병기", -3.1, 0.0),        // 0.9/s — 가장 느리고 한 방이 가장 무겁다 (방패 파괴)
        겸(null, Base.HOE, "단검", -2.1, 0.0),          // 1.9/s — 걸어 채는 날. 가볍고 빠르다
        월아산(null, Base.SHOVEL, "봉", -3.0, 1.0),      // 1.0/s — 승려의 장병기. 간격 4.0m
        구(null, Base.PICKAXE, "검", -2.5, 0.0);        // 1.5/s — 걸고 당긴다. 중간

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

        /** 표시 공격속도 (초당 타수) — 툴팁과 attribute 가 같은 식을 쓴다 */
        public double attackSpeed() {
            return BASE_ATTACK_SPEED + speedMod;
        }

        /** 표시 사거리 (m) */
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

    /** 판정 보정 — equipment.yml weapon_grades.<등급>.judgment_bonus (범철·정련 0 / 보병·신병 +1) */
    public static int judgmentBonus(Grade grade) {
        if (grade == Grade.마병) {
            return 0;   // 마병이 파는 것은 보정이 아니라 damage_bonus + 흡수 (그리고 침식)
        }
        Object v = gradeSpec(grade).get("judgment_bonus");
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** 무기 위력 — combat.yml damage.weapon_power[계열 위력키] */
    public static int power(Series series) {
        ensureLoaded();
        return weaponPower.getOrDefault(series.powerKey, weaponPower.getOrDefault("맨손", 1));
    }

    /** 표시 공격력 = 플레이어 기본(1.0) + 무기 위력. attribute modifier 와 같은 식 (툴팁 무결성) */
    public static double attackDamage(Series series) {
        return BASE_ATTACK_DAMAGE + power(series);
    }

    // ══════════ 제작 ══════════

    /** 병기 1점 — 계열·등급 한글명 (예: make("검", "정련")) */
    public static ItemStack make(String series, String grade) {
        return make(Series.of(series), Grade.of(grade), List.of());
    }

    public static ItemStack make(Series series, Grade grade) {
        return make(series, grade, List.of());
    }

    /**
     * 병기 1점.
     *
     * @param properties equipment.yml special_properties 의 키 (파사·한철·경명·명공각인·음양쌍인·탈혼).
     *                   등급별 property_slots 를 넘겨도 막지 않는다 — 지급 주체가 지킨다 (MVT)
     */
    public static ItemStack make(Series series, Grade grade, List<String> properties) {
        ensureLoaded();
        if (grade == Grade.마병 && series != DEMONIC_SERIES) {
            throw new IllegalArgumentException(
                    "마병은 도(刀)에만 존재한다 — 혈음도 (팩에 " + series.name() + "_mabyeong 텍스처가 없다)");
        }
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
        if (demonic) {
            pdc.set(KEY_STATE, PersistentDataType.STRING, STATE_UNIDENTIFIED);
        }

        applyStats(meta, series, grade);
        applyTooltip(meta, series, grade, properties, demonic ? STATE_UNIDENTIFIED : null);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * 바닐라 수치 정리 — 재질 티어가 딸고 오는 공격력·공속·내구를 전부 덮어쓴다.
     *
     * <p>1.20.5+ 규칙: attribute_modifiers 컴포넌트를 세우면 그 아이템의 <b>기본 modifier 는 전부 대체</b>된다.
     * 따라서 공격력·공속을 우리가 명시하지 않으면 공속이 4.0(맨손)으로 튄다 — 둘 다 반드시 쓴다.
     */
    private static void applyStats(ItemMeta meta, Series series, Grade grade) {
        meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(
                key("weapon_damage"), power(series),
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(
                key("weapon_speed"), series.speedMod,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        if (series.reachMod != 0.0) {
            // 창·월아산 — 간격이 곧 병기의 성격 (사거리를 파는 대신 공속을 준다)
            meta.addAttributeModifier(Attribute.ENTITY_INTERACTION_RANGE, new AttributeModifier(
                    key("weapon_reach"), series.reachMod,
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        }

        // 내구 = 등급이 파는 것 ("가치는 보정이 아니라 생존"). 재질 내구(금검 32!)는 버린다
        if (grade.durability > 0) {
            ((Damageable) meta).setMaxDamage(grade.durability);
        } else {
            meta.setUnbreakable(true);   // 신병·마병 — 세계 등록제 유일물은 삽질로 부러지지 않는다
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        }
        meta.setEnchantable(0);   // 강호의 병기는 마법부여로 강해지지 않는다 (판정이 전부다)
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);   // 바닐라 수치 줄 차단 — 툴팁은 우리가 그린다
        meta.setMaxStackSize(1);
    }

    // ══════════ 툴팁 — 규칙이 위, 서사가 아래 ══════════

    private static final String RULE = ChatColor.DARK_GRAY + "─────────────";

    @SuppressWarnings("deprecation")
    private static void applyTooltip(ItemMeta meta, Series series, Grade grade,
                                     List<String> properties, String state) {
        // 미감정 마병은 거짓말을 한다 — 감정 전엔 평범한 정련 도 (texture_layer_design: "정체 불명")
        boolean lie = STATE_UNIDENTIFIED.equals(state);
        Grade shown = lie ? Grade.정련 : grade;

        meta.setDisplayName(shown.color + shown.name() + hanja(shown) + " " + series.name()
                + hanja(series));

        List<String> lore = new ArrayList<>();
        lore.add(RULE);
        lore.add(ChatColor.GRAY + "공격력 " + ChatColor.WHITE + fmt(attackDamage(series))
                + ChatColor.DARK_GRAY + " · " + ChatColor.GRAY + "공속 "
                + ChatColor.WHITE + fmt(series.attackSpeed())
                + ChatColor.GRAY + " (초당 " + fmt(series.attackSpeed()) + "회)");
        if (series.reachMod != 0.0) {
            lore.add(ChatColor.GRAY + "사거리 " + ChatColor.WHITE + fmt(series.reach()) + "m"
                    + ChatColor.DARK_GRAY + " (기본 " + fmt(BASE_REACH) + "m)");
        }
        lore.add(ChatColor.GRAY + "감당 격: " + shown.color + withstands(shown) + "까지");
        int bonus = judgmentBonus(shown);
        lore.add(ChatColor.GRAY + "판정 보정 " + ChatColor.WHITE + (bonus >= 0 ? "+" : "") + bonus);

        if (!properties.isEmpty() && !lie) {
            lore.add(RULE);
            for (String property : properties) {
                lore.add(ChatColor.LIGHT_PURPLE + "◆ " + property + ChatColor.GRAY
                        + " — " + propertyEffect(property));
            }
        }

        lore.add(RULE);
        for (String line : flavor(series, shown)) {
            lore.add(ChatColor.WHITE + line);
        }
        lore.add(ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + warning(shown));
        if (!lie && grade == Grade.마병) {
            lore.add(ChatColor.DARK_RED + "" + ChatColor.ITALIC + "혈향 — 적중마다 상대 원기를 마신다. 그리고 너를 침식한다.");
        }
        // 애병(愛兵) 자리 예약 — 이력 축(동행 년수·생사 격돌)이 서면 여기에 단계 lore 를 얹는다.
        // equipment.yml beloved_weapon.stages: 손에_익다(+1 주인 한정) / 명병(명명·파괴 유예) / 통령(감당 격 +1)
        // MVT엔 이력 축이 없다 — PDC 키만 예약해 둔다 (honcheon:weapon_beloved_stage).

        meta.setLore(lore);
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

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
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
        applyTooltip(meta, seriesOf(item), Grade.마병, List.of(), STATE_REVEALED);
        item.setItemMeta(meta);
        return item;
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

    /** 감당 등급 문자열 — SkillEngine.weaponGradeOf 의 정식 대체 (없으면 null) */
    public static String gradeNameOf(ItemStack item) {
        return tag(item, KEY_GRADE);
    }

    /** combat.yml weapon_power 키 — SkillEngine.weaponClassOf 의 정식 대체 (없으면 null) */
    public static String weaponClassOf(ItemStack item) {
        return tag(item, KEY_CLASS);
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

    // ══════════ 전리품 — 남발 금지 ══════════

    /** 전리품 후보 계열 — 무장 인간형이 떨어뜨릴 법한 것만 (짐승은 병기를 지니지 않는다) */
    private static final Series[] LOOT_SERIES = {Series.검, Series.도, Series.단검};

    /**
     * 전리품 굴림 — 무장 인간형(산적 대역)만, 범철만, 드물게.
     * 짐승(늑대·여우)은 절대 병기를 떨구지 않는다 — 세계가 거짓말을 하면 안 된다.
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
        ItemStack loot = make(series, Grade.범철);
        // 전리품은 새것이 아니다 — 절반쯤 상한 채로 나온다 (주인이 쓰던 것)
        ItemMeta meta = loot.getItemMeta();
        ((Damageable) meta).setDamage(Grade.범철.durability / 2);
        loot.setItemMeta(meta);
        return loot;
    }

    private Weapons() {
    }
}
