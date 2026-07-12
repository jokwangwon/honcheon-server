package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Hoglin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 적(敵)의 세 층 — 사람·비무·짐승. 그리고 그 셋이 서 있을 자리(구역)와 서지 못할 자리(마을).
 *
 * <p><b>왜 이 파일이 생겼나.</b> v6.9 까지 인게임의 적은 전부 <b>바닐라 스폰</b>이었다. 향촌 안에 좀비가
 * 서고, 사냥터의 짐승은 사냥하면 고갈됐으며, {@code config/npcs/cheongha_npcs.yml} 에 등록된 짐승 스탯
 * (격·내구·무기·전의)은 어떤 엔티티에도 실리지 않았다. 무엇보다 — <b>무협의 적은 짐승이 아니라 사람이다.</b>
 * 산적과 싸우고, 무인과 겨룬다(비무). {@code cultivation.yml} 의 목숨_무게 3층
 * ({@code 생사 2.0 / 실전_사냥 1.0 / 비무_대련 0.5})이 이미 그 셋을 말하고 있었는데 인게임엔 "때려잡기"
 * 하나뿐이었다.
 *
 * <p><b>세 층의 배선</b>
 * <ul>
 *   <li><b>사람</b> — 산적(북쪽 산길 도적 무리·녹림). {@link Weapons#make} 로 <b>진짜 병기를 들려</b>
 *       스폰하고, 죽으면 그 병기를 떨군다. 두목이 쓰러지면 졸개의 전의가 무너진다(npc_combat morale).</li>
 *   <li><b>비무</b> — {@link Sparring}. 죽지 않는 싸움. 중상(내구 25%)에서 멈춘다. 적립 ×0.5.</li>
 *   <li><b>짐승</b> — 생계·재료·초심자의 수련. 주역이 아니다. 구역 정원제로 재생한다(고갈 없음).</li>
 * </ul>
 *
 * <p><b>단일 진실 원천</b> — 스탯은 전부 config 판독이다:
 * {@code npcs/cheongha_npcs.yml}(개체: 내구·전의·자연 무기·드롭·경지) ·
 * {@code combat.yml}(무기 위력·무공 위력·내구 공식·부상 문턱) · {@code npc_combat.yml}(전의 가중치) ·
 * {@code cultivation.yml}(경지 사다리·짐승 격) · {@code resourcepack_design.yml}(기세 색).
 * 코드에 남은 수치는 <b>config 등록 대기</b> 주석이 붙은 것뿐이다(개체군 정원·주기·바닐라 몹 매핑·무장).
 *
 * <p><b>성능</b> — 중앙 티커 1개(1초). 스폰 census 는 10초, 전의·표적 스윕은 2초, 그것도 <b>플레이어가
 * 가까이 있을 때만</b> 돈다 (performance.yml npc_logic 6ms 예산).
 */
public final class HuntingGrounds implements Listener {

    // ══════════════ PDC 태그 — 재질 추측을 대체한다 (Weapons 와 같은 문법) ══════════════

    /** 개체 id — npcs/cheongha_npcs.yml 의 키 (san_neukdae · north_road_bandit …) */
    public static final NamespacedKey KEY_ID = key("foe_id");
    /** 층 — 짐승 | 사람 */
    public static final NamespacedKey KEY_KIND = key("foe_kind");
    /** 짐승의 격 — 들짐승 | 맹수 | 영물 (사람은 없음) */
    public static final NamespacedKey KEY_RANK = key("beast_rank");
    /** 목격한 최고 격 (0=없음 · 1=발경 · 2=검기 · 3=강기 · 4=어검/심검) — 한 번 본 것은 그 전투 내내 남는다 */
    public static final NamespacedKey KEY_SEEN_QI = key("foe_seen_qi");
    /** 경지 — 삼류 | 이류 | 일류 | 절정 (짐승도 '상당치'를 갖는다) */
    public static final NamespacedKey KEY_REALM = key("foe_realm");
    /** 배역 — 졸개 | 두목 | 비무상대 */
    public static final NamespacedKey KEY_ROLE = key("foe_role");
    /** 고향 구역 — 정원 census 의 단위 */
    public static final NamespacedKey KEY_ZONE = key("foe_zone");
    /** 전의 게이지 시작값·붕괴 문턱 (npc_combat.yml morale_profile) */
    public static final NamespacedKey KEY_MORALE = key("foe_morale");
    public static final NamespacedKey KEY_MORALE_BREAK = key("foe_morale_break");
    /** 도주 중 — 이 틱까지 (0 = 아님) */
    public static final NamespacedKey KEY_FLEEING = key("foe_fleeing_until");
    /** 영물·비무상대의 수동 타격 쿨다운 (바닐라 근접 AI 가 없는 몸을 징발했을 때) */
    public static final NamespacedKey KEY_SWING = key("foe_next_swing");

    private static NamespacedKey key(String value) {
        return new NamespacedKey("honcheon", value);
    }

    // ══════════════ config 등록 대기 — 아래 셋만이 코드에 남은 수치다 ══════════════

    /**
     * 바닐라 몹 매핑 — <b>config 등록 대기</b>: {@code npcs/cheongha_npcs.yml} 개체에 {@code mc_entity} 키.
     * 등록되면 이 표는 폴백으로만 남는다({@link #entityTypeOf}).
     *
     * <p>징발 근거 (docs/design/mob_spawning.md §매핑):
     * <ul>
     *   <li>산늑대 = WOLF — 무리·타이가 원주민. 길들이기·번식은 봉인한다(우리 짐승은 애완이 아니다).</li>
     *   <li>멧돼지 = HOGLIN — 바닐라에서 <b>돌진하는 멧돼지형 적대 몹</b>은 이것뿐이다(PIG 는 가축이고
     *       AI 가 없다). 좀비화 면역을 켜서 오버월드 좀글린 변이를 막는다.</li>
     *   <li>호랑이 = RAVAGER — 바닐라에 호랑이가 없다. 후보 중 <b>큰 몸집·적대·근접 AI·돌진(포효 넉백)</b>을
     *       모두 갖춘 유일한 네발짐승이다. OCELOT/CAT 은 공격 AI 자체가 없고(때리지 못한다), POLAR_BEAR 는
     *       반달곰이 이미 쓴다(같은 모습 두 짐승 = 못 읽는다). 팩 게이트: 텍스처 없으면 약탈수 모습이지만
     *       <b>이름(붉은 명패 「호랑이」)·크기·행동(단독·영역·기습)</b>으로 읽힌다.</li>
     *   <li>반달곰 = POLAR_BEAR — 지시대로. 근접 AI 보유, 덩치(super_armor)와 맞는다.</li>
     *   <li>백영묘 = CAT(WHITE) — 영물은 <b>고양이여야</b> 한다(白影猫). 대신 CAT 은 공격 AI 가 없으므로
     *       근접 타격을 <b>중앙 티커가 수동으로</b> 준다({@link #spiritSwing}). 발광(검기)으로 격을 표시.</li>
     *   <li>사람(산적·무인) = ZOMBIE — 인간형이면서 <b>손에 든 병기가 보이고 근접 AI 가 도는</b> 몸.
     *       PIGLIN 은 Brain 기반이라 setTarget 이 뇌에 덮이고 금 줍기·후퇴 행동이 붙는다. VILLAGER 는
     *       공격 AI 가 아예 없다. ZOMBIE 는 낮 연소를 끄고({@code setShouldBurnInDay(false)}) 신음을
     *       죽이고({@code setSilent}) 마을 사람 표적을 봉인하면({@link #onTarget}) — 남는 것은
     *       <b>병기를 든 사람의 실루엣</b>이다. 팩 게이트: 좀비 텍스처 교체가 예정된 유일한 채널.</li>
     * </ul>
     */
    private static final Map<String, EntityType> DEFAULT_ENTITY = Map.of(
            "san_neukdae", EntityType.WOLF,
            "metdwaeji", EntityType.HOGLIN,
            "horangi", EntityType.RAVAGER,
            "bandal_gom", EntityType.POLAR_BEAR,
            "baegyeongmyo", EntityType.CAT,
            "north_road_bandit", EntityType.ZOMBIE,
            "galho", EntityType.ZOMBIE,
            "gwakjin", EntityType.ZOMBIE);

    /**
     * 사람의 무장 — <b>config 등록 대기</b>: {@code npcs/cheongha_npcs.yml} 개체에 {@code loadout: {계열, 등급}}.
     * 졸개는 범철 도, 두목은 정련 검(도법 3 — 갈호), 비무 상대는 범철 검(목검 계열 미등록).
     */
    private static final Map<String, String[]> DEFAULT_LOADOUT = Map.of(
            "north_road_bandit", new String[]{"도", "범철"},
            "galho", new String[]{"검", "정련"},
            "gwakjin", new String[]{"검", "범철"});

    /**
     * 구역별 개체군 — <b>config 등록 대기</b>: {@code config/hunting_grounds.yml}
     * (제안 YAML: docs/design/mob_spawning.md §config 제안). {@code {id, 낮 정원, 밤 정원}}.
     *
     * <p>낮은 들짐승과 도적, 밤은 맹수. 영물(baegyeongmyo)은 <b>정원 0</b> — 등록제다
     * ({@code cultivation.yml beast_ranks.영물.policy: 양산 금지}). 관리자 소환으로만 선다.
     */
    private static final Map<String, List<Quota>> POPULATIONS = Map.of(
            "북쪽 산길", List.of(
                    new Quota("san_neukdae", 4, 6),
                    new Quota("metdwaeji", 3, 2),
                    new Quota("horangi", 0, 1),
                    new Quota("bandal_gom", 0, 1),
                    new Quota("north_road_bandit", 3, 4),
                    new Quota("galho", 1, 1)));

    /** 마을 구역 이름 — config 등록 대기 (zones.yml 은 이름만 갖는다. '마을인가'는 아직 데이터가 아니다) */
    private static final String TOWN_ZONE = "청하현";
    /** 담장 밖 여유 — 마을 무스폰은 구역 상자 + 이 만큼 (스폰이 담 밑에 붙는 것을 막는다) */
    private static final int TOWN_MARGIN = 8;

    /** 스폰 조건 — config 등록 대기 (hunting_grounds.yml spawn) */
    private static final int SPAWN_CYCLE_TICKS = 200;    // 재생 주기 10초 — 한 구역에 최대 1마리/주기
    private static final int MORALE_CYCLE_TICKS = 40;    // 전의·표적 스윕 2초
    private static final int BOSS_RESPAWN_TICKS = 6000;  // 두목은 5분 뒤에야 다시 선다 (양산 금지)
    private static final int NEAR_PLAYER = 96;           // 구역에 사람이 이 안에 없으면 아무것도 돌지 않는다
    private static final int SPAWN_MIN_DIST = 20;        // 눈앞에 튀어나오지 않는다
    private static final int SPAWN_MAX_DIST = 64;
    private static final int SPAWN_MAX_LIGHT = 7;        // 블록 광원 — 불이 끝나는 곳부터가 사냥터다 (v6.9 ㉯)
    private static final int ZONE_ENTITY_CAP = 18;       // 구역당 태그 개체 상한 (performance.yml)
    private static final int SPIRIT_REACH = 3;           // 영물 수동 타격 사거리
    private static final int SPIRIT_SWING_TICKS = 20;

    /** 우리가 부른 것 — 통과. 나머지 이유(NATURAL·SPAWNER·PATROL·RAID…)는 규칙이 심판한다 */
    private static final Set<CreatureSpawnEvent.SpawnReason> ALLOWED_REASONS = EnumSet.of(
            CreatureSpawnEvent.SpawnReason.CUSTOM,
            CreatureSpawnEvent.SpawnReason.COMMAND,
            CreatureSpawnEvent.SpawnReason.SPAWNER_EGG,
            CreatureSpawnEvent.SpawnReason.BREEDING,
            CreatureSpawnEvent.SpawnReason.EGG,
            CreatureSpawnEvent.SpawnReason.DISPENSE_EGG,
            CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM,
            CreatureSpawnEvent.SpawnReason.BUILD_SNOWMAN,
            CreatureSpawnEvent.SpawnReason.CURED);

    /** 마을이 품는 가축 — 마당의 닭은 마을 살림이다 (자연 스폰은 아니고, 번식·알로만 는다) */
    private static final Set<EntityType> LIVESTOCK = EnumSet.of(
            EntityType.CHICKEN, EntityType.COW, EntityType.SHEEP, EntityType.PIG, EntityType.HORSE,
            EntityType.DONKEY, EntityType.CAT, EntityType.VILLAGER, EntityType.WANDERING_TRADER);

    // ══════════════ 등록부 (config 판독) ══════════════

    /** 개체 정의 — npcs/cheongha_npcs.yml + combat.yml 을 합쳐 '인게임에 실을 수 있는 형태'로 */
    public record Foe(String id, String name, String kind, String rank, String realm, String role,
                      int durability, int attack, int moraleStart, int moraleBreak,
                      List<String> drops, EntityType type, String[] loadout) {
        public boolean isBeast() {
            return "짐승".equals(kind);
        }
    }

    private record Quota(String id, int day, int night) {
        int target(boolean night) {
            return night ? this.night : this.day;
        }
    }

    private static final Map<String, Foe> FOES = new LinkedHashMap<>();
    private static Map<String, Integer> weaponPower = Map.of();
    private static Map<String, Integer> techniquePower = Map.of();
    private static Map<String, Object> moraleWeights = Map.of();
    private static List<String> realmLadder = List.of("범인", "삼류", "이류", "일류", "절정", "초절정", "화경");
    private static double 중상_ratio = 0.25;   // combat.yml durability.wound_thresholds.중상.below_ratio

    // ─── 포위 (combat.yml attack) — 다구리에도 몸이 들어갈 자리가 필요하다 ───
    private static int engageSlots = 3;        // gang_up.engage_slots — 동시에 칠 수 있는 손
    private static int forcedGuardFrom = 1;    // outnumbered_defense.forced_guard.trigger_extra_attackers
    private static int forcedGuardSoak;        // 강제 태세(흘리기)의 피해 경감 — defender_choice 가 정본
    /** 근접 교전 거리 — 이 안에 든 몸만 슬롯을 차지한다 (활·암기는 슬롯 밖에서 쏜다) */
    private static final double ENGAGE_REACH = 4.0;

    private final HoncheonMvt plugin;
    private final Sparring sparring;
    private final Map<String, Long> lastSpawnTick = new HashMap<>();
    private long cycle;

    public HuntingGrounds(HoncheonMvt plugin) {
        this.plugin = plugin;
        this.sparring = new Sparring(plugin, this);
    }

    public Sparring sparring() {
        return sparring;
    }

    /** config 판독 — 코드가 config 보다 앞서지 않는다 (Weapons.init 과 같은 자리에서 부른다) */
    @SuppressWarnings("unchecked")
    public static void init(Path cfg) {
        Map<String, Object> combat = RulesConfig.load(cfg.resolve("combat.yml"));
        Map<String, Object> damage = RulesConfig.section(combat, "damage");
        weaponPower = (Map<String, Integer>) damage.get("weapon_power");
        techniquePower = (Map<String, Integer>) damage.get("technique_power");
        Map<String, Object> dur = RulesConfig.section(combat, "durability");
        Object 중상 = ((Map<String, Object>) dur.get("wound_thresholds")).get("중상");
        if (중상 instanceof Map<?, ?> m && m.get("below_ratio") instanceof Number n) {
            중상_ratio = n.doubleValue();
        }
        // ─── 포위 — 한 사람을 에워싸면 서로 걸리적거린다 (combat.yml attack) ───
        Map<String, Object> attack = RulesConfig.section(combat, "attack");
        Map<String, Object> gang = RulesConfig.section(attack, "gang_up");
        if (gang.get("engage_slots") instanceof Number n) {
            engageSlots = n.intValue();
        }
        Map<String, Object> outnumbered = RulesConfig.section(attack, "outnumbered_defense");
        if (outnumbered.get("forced_guard") instanceof Map<?, ?> raw) {
            Map<String, Object> forced = (Map<String, Object>) raw;
            if (forced.get("trigger_extra_attackers") instanceof Number t) {
                forcedGuardFrom = t.intValue();
            }
            // 경감치는 forced_guard 가 가리키는 태세(흘리기)의 defender_choice 값이 정본 — 단일 진실 원천
            String stance = String.valueOf(forced.getOrDefault("defense", "흘리기"));
            Object soak = RulesConfig.section(RulesConfig.section(attack, "defender_choice"), stance)
                    .get("damage_reduction");
            forcedGuardSoak = soak instanceof Number s ? s.intValue() : 0;
        }

        Map<String, Object> npcCombat = RulesConfig.load(cfg.resolve("npc_combat.yml"));
        moraleWeights = (Map<String, Object>) RulesConfig.section(npcCombat, "morale").get("weights");

        Object stages = RulesConfig.load(cfg.resolve("cultivation.yml")).get("cultivation_stages");
        if (stages instanceof List<?> list) {
            List<String> ladder = new ArrayList<>();
            for (Object row : list) {
                if (row instanceof Map<?, ?> m && m.get("name") != null) {
                    ladder.add(String.valueOf(m.get("name")));
                }
            }
            if (!ladder.isEmpty()) {
                realmLadder = List.copyOf(ladder);
            }
        }

        FOES.clear();
        Map<String, Object> npcs = RulesConfig.section(
                RulesConfig.load(cfg.resolve("npcs/cheongha_npcs.yml")), "npcs");
        npcs.forEach((id, raw) -> {
            if (!(raw instanceof Map<?, ?> entry)) {
                return;
            }
            Foe foe = parse(id, (Map<String, Object>) entry);
            if (foe != null) {
                FOES.put(id, foe);
            }
        });
    }

    /** 등록부의 한 줄 → 인게임 개체 정의. 전투에 세울 수 없는 NPC(객잔 주인 등)는 null */
    @SuppressWarnings("unchecked")
    private static Foe parse(String id, Map<String, Object> e) {
        EntityType type = entityTypeOf(id, e);
        if (type == null) {
            return null;   // 몸을 징발하지 않은 NPC — 전투에 서지 않는다
        }
        String name = String.valueOf(e.getOrDefault("name", id));
        String rank = e.get("beast_rank") == null ? null : String.valueOf(e.get("beast_rank"));
        String realm = e.get("realm") == null ? "삼류" : String.valueOf(e.get("realm"));
        boolean beast = rank != null;
        Map<String, Object> stats = e.get("stats") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        // 내구 — 짐승은 등록값, 사람은 config 공식 round(10 + 체력 × 2) (combat.yml durability.formula)
        int durability = e.get("durability") instanceof Number n ? n.intValue()
                : (int) Math.round(10 + 2.0 * num(stats.get("체력"), 3));

        // 피해 — 짐승: 자연 무기 위력(이빨=맨손 1, 엄니·발톱=단검 3, 앞발=봉 3, 영물 발톱=검 4)
        //        사람: 무기 위력 + 무공 위력 (격 위력은 격을 두른 라운드에만 — 아래 arm() 주석 참조)
        int attack;
        if (beast) {
            Map<String, Object> nw = e.get("natural_weapon") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            attack = weaponPower.getOrDefault(String.valueOf(nw.getOrDefault("weapon_power_as", "맨손")), 1);
        } else {
            String[] loadout = loadoutOf(id, e);
            attack = weaponPower.getOrDefault(loadout[0], 1)
                    + techniquePower.getOrDefault(realm + "급", 0);
        }

        // 전의 — 개체 등록값 우선, 없으면 격의 기본 프로필 (npc_combat.yml morale_profile)
        int start = 5;
        int breakAt = 3;
        if (e.get("morale") instanceof Map<?, ?> m) {
            start = (int) num(((Map<String, Object>) m).get("start"), start);
            breakAt = (int) num(((Map<String, Object>) m).get("도주_문턱"), breakAt);
        } else if (!beast) {
            start = "이류".equals(realm) || "일류".equals(realm) ? 9 : 5;   // 두목 9 / 졸개 5 (morale.gauge)
            breakAt = start >= 9 ? 2 : 3;
        }

        List<String> drops = e.get("drops") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : List.of();
        String role = beast ? "짐승"
                : "gwakjin".equals(id) ? "비무상대"
                : "galho".equals(id) ? "두목" : "졸개";

        return new Foe(id, name, beast ? "짐승" : "사람", rank, realm, role,
                durability, attack, start, breakAt, drops, type, loadoutOf(id, e));
    }

    /**
     * 손에 든 병기 — <b>등록부가 이긴다</b> ({@code loadout: {계열, 등급}}).
     *
     * <p>DEFAULT_LOADOUT 은 등록 전까지의 임시 자리였다. config 에 적어 두고 코드가 안 읽으면
     * 등록부는 거짓말이 된다 — 적힌 대로 서지 않는 세계는 등록제가 아니다.
     */
    @SuppressWarnings("unchecked")
    private static String[] loadoutOf(String id, Map<String, Object> entry) {
        if (entry.get("loadout") instanceof Map<?, ?> m) {
            Map<String, Object> load = (Map<String, Object>) m;
            return new String[]{
                    String.valueOf(load.getOrDefault("계열", "맨손")),
                    String.valueOf(load.getOrDefault("등급", "범철"))};
        }
        return DEFAULT_LOADOUT.getOrDefault(id, new String[]{"맨손", "범철"});
    }

    private static EntityType entityTypeOf(String id, Map<String, Object> entry) {
        Object registered = entry.get("mc_entity");   // config 등록 대기 — 등록되면 이쪽이 이긴다
        if (registered != null) {
            try {
                return EntityType.valueOf(String.valueOf(registered).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
        return DEFAULT_ENTITY.get(id);
    }

    private static double num(Object value, double fallback) {
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    public static Foe definition(String id) {
        return FOES.get(id);
    }

    public static List<String> registry() {
        return List.copyOf(FOES.keySet());
    }

    // ══════════════ ① 마을은 무(無)스폰 ══════════════

    /**
     * 자연 스폰 심판. 향촌에 좀비가 서지 않고, 사냥터의 짐승은 <b>우리가</b> 심는다.
     *
     * <ul>
     *   <li>우리가 부른 것(CUSTOM·COMMAND·번식·알)은 통과 — 조성기의 NPC(Villager)와 이 파일의 짐승·산적.</li>
     *   <li><b>적대 몹은 세계 전역에서 취소</b> — 무협 세계에 좀비·크리퍼·스켈레톤은 없다.
     *       (우리 멧돼지(HOGLIN)도 Enemy 지만 CUSTOM 이라 위에서 이미 통과했다.)</li>
     *   <li><b>마을</b>(구역 「청하현」 + 담장 밖 8칸) — 적대·야생 가릴 것 없이 자연 스폰 전부 취소.
     *       가축은 번식·알로만 는다(마당의 닭은 남는다).</li>
     *   <li><b>사냥터</b> — 바닐라가 씨를 뿌리지 못하게 막는다. 정원(定員)이 census 로 관리되려면
     *       그 땅의 짐승은 전부 우리 것이어야 한다.</li>
     * </ul>
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (ALLOWED_REASONS.contains(event.getSpawnReason())) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity instanceof Enemy) {
            event.setCancelled(true);   // 세계 무스폰 — 적은 사람과 짐승이지 언데드가 아니다
            return;
        }
        Location at = event.getLocation();
        if (inTown(at)) {
            event.setCancelled(true);   // 가축이라도 마을에 저절로 솟지는 않는다 (번식·알은 위에서 통과)
            return;
        }
        if (huntZoneAt(at) != null && !LIVESTOCK.contains(entity.getType())) {
            event.setCancelled(true);   // 사냥터의 개체군은 정원제다 — 바닐라가 끼어들면 census 가 깨진다
        }
    }

    /** 마을 = 구역 「청하현」 상자 + 여유. 구역이 없으면 장터 앵커 반경 80 (조성 전 폴백) */
    private boolean inTown(Location at) {
        for (Zone zone : plugin.zones()) {
            if (!TOWN_ZONE.equals(zone.name())) {
                continue;
            }
            return at.getWorld() != null && at.getWorld().getName().equals(zone.world())
                    && at.getBlockX() >= zone.x1() - TOWN_MARGIN && at.getBlockX() <= zone.x2() + TOWN_MARGIN
                    && at.getBlockZ() >= zone.z1() - TOWN_MARGIN && at.getBlockZ() <= zone.z2() + TOWN_MARGIN;
        }
        Location market = plugin.anchor("장터");
        return market != null && market.getWorld() == at.getWorld()
                && market.distanceSquared(at) < 80 * 80;
    }

    /** 사냥터 구역 — 개체군 표(POPULATIONS)에 등록된 이름의 구역 */
    private Zone huntZoneAt(Location at) {
        for (Zone zone : plugin.zones()) {
            if (POPULATIONS.containsKey(zone.name()) && zone.contains(at)) {
                return zone;
            }
        }
        return null;
    }

    // ══════════════ ② 구역 스포너 — 중앙 티커 하나 ══════════════

    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 100L, 20L);
    }

    private void tick() {
        cycle += 20;
        sparring.tick();                                  // 비무 판정 (장외·시간초과) — 티커 공유
        if (cycle % MORALE_CYCLE_TICKS == 0) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                sweep(player);
            }
        }
        if (cycle % SPAWN_CYCLE_TICKS == 0) {
            for (Zone zone : plugin.zones()) {
                if (POPULATIONS.containsKey(zone.name())) {
                    repopulate(zone);
                }
            }
            partnerUpkeep();   // 비무 상대(곽진) — 표국 마당에 한 사람은 늘 서 있다
        }
    }

    /** 정원 채우기 — 사냥해서 줄면 시간이 지나 다시 찬다. 한 주기에 한 마리 (고갈되지 않되 무한하지도 않게) */
    private void repopulate(Zone zone) {
        World world = plugin.getServer().getWorld(zone.world());
        if (world == null) {
            return;
        }
        BoundingBox box = new BoundingBox(zone.x1(), zone.y1(), zone.z1(),
                zone.x2() + 1, zone.y2() + 1, zone.z2() + 1);
        Player near = nearestPlayer(world, box.getCenter().toLocation(world), NEAR_PLAYER);
        if (near == null) {
            return;   // 사람이 없으면 산도 조용하다 (성능)
        }
        Map<String, Integer> census = new HashMap<>();
        int total = 0;
        for (Entity e : world.getNearbyEntities(box)) {
            String id = tag(e, KEY_ID);
            if (id != null && zone.name().equals(tag(e, KEY_ZONE)) && !e.isDead()) {
                census.merge(id, 1, Integer::sum);
                total++;
            }
        }
        if (total >= ZONE_ENTITY_CAP) {
            return;
        }
        boolean night = isNight(world);
        for (Quota quota : POPULATIONS.get(zone.name())) {
            Foe foe = FOES.get(quota.id());
            if (foe == null || census.getOrDefault(quota.id(), 0) >= quota.target(night)) {
                continue;
            }
            String cooldownKey = zone.name() + "/" + quota.id();
            long wait = "두목".equals(foe.role()) ? BOSS_RESPAWN_TICKS : 0;
            if (cycle - lastSpawnTick.getOrDefault(cooldownKey, -wait) < wait) {
                continue;
            }
            Location at = pickSpawn(world, zone, near, foe);
            if (at != null) {
                spawn(foe, at, zone.name());
                lastSpawnTick.put(cooldownKey, cycle);
                return;   // 한 주기에 한 마리 — 짐승은 쏟아지지 않는다
            }
        }
    }

    /** 스폰 자리 — 구역 안, 지면 위, 어두운 곳, 사람에게서 20~64칸. 마을 쪽으로는 새지 않는다 */
    private Location pickSpawn(World world, Zone zone, Player near, Foe foe) {
        if (zone.x2() - 3 <= zone.x1() + 4 || zone.z2() - 3 <= zone.z1() + 4) {
            return null;   // 짐승이 설 수 없는 좁은 구역 (건물 구역 등)
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 16; attempt++) {
            int x = rng.nextInt(zone.x1() + 4, zone.x2() - 3);
            int z = rng.nextInt(zone.z1() + 4, zone.z2() - 3);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;   // 청크를 강제로 올리지 않는다 (성능)
            }
            int y = world.getHighestBlockYAt(x, z);
            if (y < zone.y1() || y > zone.y2()) {
                continue;
            }
            Location at = new Location(world, x + 0.5, y + 1, z + 0.5);
            if (inTown(at) || !standable(world, x, y, z)) {
                continue;
            }
            double distance = at.distance(near.getLocation());
            if (distance < SPAWN_MIN_DIST || distance > SPAWN_MAX_DIST) {
                continue;
            }
            // 광원 — 짐승은 어둠에서 온다 (야영터·산길 등롱 밑엔 서지 않는다). 사람(산적)은 불을 쬔다
            if (foe.isBeast() && world.getBlockAt(x, y + 1, z).getLightFromBlocks() > SPAWN_MAX_LIGHT) {
                continue;
            }
            return at;
        }
        return null;
    }

    private static boolean standable(World world, int x, int y, int z) {
        Material ground = world.getBlockAt(x, y, z).getType();
        if (!ground.isSolid() || ground == Material.WATER || ground == Material.LAVA) {
            return false;
        }
        return world.getBlockAt(x, y + 1, z).getType().isAir()
                && world.getBlockAt(x, y + 2, z).getType().isAir();
    }

    private static boolean isNight(World world) {
        long time = world.getTime();
        return time >= 13000 && time < 23000;
    }

    private Player nearestPlayer(World world, Location at, double range) {
        Player best = null;
        double bestDistance = range * range;
        for (Player player : world.getPlayers()) {
            double d = player.getLocation().distanceSquared(at);
            if (d < bestDistance) {
                bestDistance = d;
                best = player;
            }
        }
        return best;
    }

    // ══════════════ ③ 스탯을 몸에 입힌다 ══════════════

    /**
     * 등록부의 한 줄이 인게임의 한 마리가 되는 자리.
     * 내구 → 최대 체력 · 무기 위력(+무공 위력) → 공격력 · 격 → 명패 색 · 전의 → PDC.
     */
    /** 등록부 id 로 부른다 — 연무장의 몹 시험 (없으면 null) */
    public LivingEntity spawnById(String foeId, Location at) {
        Foe foe = FOES.get(foeId);
        return foe == null ? null : spawn(foe, at, null);
    }

    public LivingEntity spawn(Foe foe, Location at, String zoneName) {
        World world = at.getWorld();
        if (world == null) {
            return null;
        }
        Entity spawned = world.spawnEntity(at, foe.type());   // SpawnReason.CUSTOM — 우리 것은 통과한다
        if (!(spawned instanceof LivingEntity entity)) {
            spawned.remove();
            return null;
        }

        // 내구 → 체력 (combat.yml durability). 짐승 16~24, 사람 16~20 — 플레이어(20)와 같은 눈금이다
        setAttr(entity, Attribute.MAX_HEALTH, foe.durability());
        entity.setHealth(foe.durability());
        // 무기 위력 → 공격력 (combat.yml damage.formula 의 첫 항)
        setAttr(entity, Attribute.ATTACK_DAMAGE, foe.attack());
        setAttr(entity, Attribute.FOLLOW_RANGE, 32);

        entity.setCustomName(plateColor(foe) + foe.name());
        entity.setCustomNameVisible(true);
        entity.setPersistent(true);
        entity.setRemoveWhenFarAway(false);   // 사냥터의 짐승은 청크가 내려가도 남는다 (census 의 전제)
        entity.setCanPickupItems(false);

        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(KEY_ID, PersistentDataType.STRING, foe.id());
        pdc.set(KEY_KIND, PersistentDataType.STRING, foe.kind());
        pdc.set(KEY_REALM, PersistentDataType.STRING, foe.realm());
        pdc.set(KEY_ROLE, PersistentDataType.STRING, foe.role());
        pdc.set(KEY_MORALE, PersistentDataType.INTEGER, foe.moraleStart());
        pdc.set(KEY_MORALE_BREAK, PersistentDataType.INTEGER, foe.moraleBreak());
        if (foe.rank() != null) {
            pdc.set(KEY_RANK, PersistentDataType.STRING, foe.rank());
        }
        if (zoneName != null) {
            pdc.set(KEY_ZONE, PersistentDataType.STRING, zoneName);
        }

        shape(entity, foe);
        return entity;
    }

    /** 징발한 몸을 그 짐승·사람으로 만든다 — 바닐라 기본 행동의 봉인과 개방 */
    private void shape(LivingEntity entity, Foe foe) {
        switch (entity) {
            case Wolf wolf -> {
                wolf.setAngry(true);        // 산늑대는 애완이 아니다 (길들이기는 onTame 이 막는다)
                wolf.setCollarColor(org.bukkit.DyeColor.GRAY);
            }
            case Hoglin hoglin -> {
                hoglin.setImmuneToZombification(true);   // 오버월드 좀글린 변이 봉인
                hoglin.setIsAbleToBeHunted(false);
            }
            case Cat cat -> {                            // 백영묘 — 영물
                cat.setCatType(Cat.Type.WHITE);
                cat.setTamed(false);
                cat.setGlowing(true);                    // 발톱에 서린 검기 (qi_manifestation 검기_두름)
                cat.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 1, true, false));
            }
            case Zombie zombie -> {                      // 사람 — 산적·무인
                zombie.setBaby(false);
                zombie.setShouldBurnInDay(false);        // 낮에도 산길에 선다
                zombie.setSilent(true);                  // 신음을 죽인다 — 사람은 신음하지 않는다
                arm(zombie, foe);
            }
            default -> {
                // RAVAGER(호랑이)·POLAR_BEAR(반달곰) — 바닐라 근접 AI 를 그대로 쓴다
            }
        }
    }

    /**
     * 무장 — <b>진짜 병기를 들린다</b>({@link Weapons#make}). 산적이 범철 도를 들고, 두목은 정련 검을 든다.
     * 죽으면 그 병기가 떨어진다({@link #onDeath}) — 전리품 계통은 이미 서 있다(PDC·재련·각인 그대로).
     *
     * <p>격(格)의 대칭 — npc_combat.yml symmetry 는 "NPC 도 같은 규칙"이라 말한다. 지금 배선된 것은
     * <b>무공 위력까지</b>다(공격력 = 무기 위력 + 무공 위력). 격 위력(발경 +1 · 검기 +2)은 내력 지불이
     * 붙는 라운드 개념이라 {@link SkillEngine} 이 플레이어에게만 배선돼 있다 — NPC 격 시전은 SkillEngine
     * 공개 API 가 필요하다(보고서의 배선 diff §NPC 격). 그전까지 일류+ 무인은 외공기로 싸운다.
     */
    private void arm(Zombie zombie, Foe foe) {
        EntityEquipment gear = zombie.getEquipment();
        if (gear == null || foe.loadout() == null) {
            return;
        }
        gear.setItemInMainHand(Weapons.make(foe.loadout()[0], foe.loadout()[1]));
        gear.setItemInMainHandDropChance(0f);   // 전리품은 onDeath 가 준다 (확률은 규칙이 정한다)
        gear.setHelmet(new ItemStack(Material.LEATHER_HELMET));   // 낮 연소 이중 방어 + 실루엣
        gear.setHelmetDropChance(0f);
        if ("두목".equals(foe.role())) {
            gear.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
            gear.setChestplateDropChance(0f);
        }
    }

    private static void setAttr(LivingEntity entity, Attribute attribute, double value) {
        var instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    /**
     * 명패 색 — resourcepack_design.yml semantic_colors.기세 4단(회/백/황/적)을 <b>절대 격</b>에 건다.
     * (플레이어별 상대 격차는 HuntListener 의 액션바가 실시간으로 말한다 — 명패는 개체당 하나뿐이라
     *  '이 몸이 어느 층인가'를 말하는 편이 읽힌다.)
     * 회 = 가축·무해 / 백 = 삼류(들짐승·졸개) / 황 = 이류(두목·무인) / 적 = 일류+(맹수·고수) · 영물은 진홍.
     */
    private static String plateColor(Foe foe) {
        if ("영물".equals(foe.rank())) {
            return "" + org.bukkit.ChatColor.DARK_RED + org.bukkit.ChatColor.BOLD;
        }
        return switch (foe.realm()) {
            case "삼류" -> "" + org.bukkit.ChatColor.WHITE;
            case "이류" -> "" + org.bukkit.ChatColor.YELLOW;
            case "일류", "절정", "초절정", "화경" -> "" + org.bukkit.ChatColor.RED;
            default -> "" + org.bukkit.ChatColor.GRAY;
        };
    }

    // ══════════════ 전의(戰意) — 격 차이를 보면 하위 짐승은 도망간다 ══════════════

    /** 플레이어 주변 스윕 — 표적·전의·도주·영물의 수동 타격. 사람 곁에서만 돈다 (npc_logic 예산) */
    private void sweep(Player player) {
        for (Entity entity : player.getNearbyEntities(24, 12, 24)) {
            if (!(entity instanceof Mob mob) || tag(mob, KEY_ID) == null) {
                continue;
            }
            Foe foe = FOES.get(tag(mob, KEY_ID));
            if (foe == null || sparring.isSparring(mob)) {
                continue;   // 비무 중인 몸은 Sparring 이 조종한다
            }
            long fleeingUntil = pdcLong(mob, KEY_FLEEING);
            if (fleeingUntil > cycle) {
                flee(mob, player);
                continue;
            }
            int morale = morale(mob, foe, player);
            if (morale <= foe.moraleBreak()) {
                breakMorale(mob, foe, player);
                continue;
            }
            engage(mob, foe, player);
        }
    }

    /** 전의 게이지 — npc_combat.yml morale.weights 를 그대로 판독해 매 스윕 재계산 (누적 아님) */
    private int morale(Mob mob, Foe foe, Player player) {
        int gauge = foe.moraleStart();

        // ① 내구 비율
        double max = mob.getAttribute(Attribute.MAX_HEALTH) == null ? foe.durability()
                : mob.getAttribute(Attribute.MAX_HEALTH).getValue();
        double ratio = mob.getHealth() / Math.max(1, max);
        if (ratio <= 0.25) {
            gauge += weight("내구_비율", "25%_이하", -4);
            if ("맹수".equals(foe.rank())) {
                gauge += 3;   // 부상 입은 맹수가 가장 위험하다 (npc_combat morale_profile.맹수)
            }
        } else if (ratio <= 0.5) {
            gauge += weight("내구_비율", "50%_이하", -2);
        }

        // ② 아군 수 — 시야 내 같은 편 (짐승은 같은 종, 사람은 같은 무리)
        int allies = 0;
        boolean bossAlive = false;
        for (Entity near : mob.getNearbyEntities(16, 8, 16)) {
            String id = tag(near, KEY_ID);
            if (id == null || near.isDead()) {
                continue;
            }
            Foe other = FOES.get(id);
            if (other == null || !other.kind().equals(foe.kind())) {
                continue;
            }
            allies++;
            if ("두목".equals(other.role())) {
                bossAlive = true;
            }
        }
        int enemies = 1;   // 이 스윕의 기준은 눈앞의 플레이어
        if (allies == 0) {
            gauge += weight("아군_수", "혼자_남음", -3);
        } else if (allies + 1 > enemies) {
            gauge += weight("아군_수", "수적_우세", 2);
        }
        if ("들짐승".equals(foe.rank()) && allies == 0) {
            gauge -= 3;   // 무리가 흩어지면 곧 도망친다 — 아군_수 가중치 2배 (morale_profile.들짐승)
        }

        // ③ 상대 위세 — 경지 격차 (-1 / 2경지, 상한 -4) + **격 목격** (SkillListener.impact 가 심는다)
        int gap = realmIndex(playerRealm(plugin, player)) - realmIndex(foe.realm());
        if (gap > 0) {
            gauge -= Math.min(4, gap / 2);
        }
        int seenQi = mob.getPersistentDataContainer()
                .getOrDefault(KEY_SEEN_QI, PersistentDataType.INTEGER, 0);
        if (seenQi > 0) {
            gauge += qiWitnessWeight(QI_LADDER[seenQi - 1]);
        }

        // ④ 두목 생사 — 두목이 쓰러지면 졸개가 무너진다
        if ("졸개".equals(foe.role()) && !bossAlive) {
            gauge += weight("두목_생사", "두목_사망", -5);
        }
        return gauge;
    }

    /** 격 목격 가중치 — morale.weights.상대_위세.격_목격.<격> (발경 0 · 검기 -2 · 강기 -5 · 어검_심검 -8) */
    private static int qiWitnessWeight(String grade) {
        if (moraleWeights.get("상대_위세") instanceof Map<?, ?> prestige
                && prestige.get("격_목격") instanceof Map<?, ?> seen
                && seen.get(grade) instanceof Number n) {
            return n.intValue();
        }
        return switch (grade) {   // config 가 비면 사다리의 뜻만은 지킨다
            case "검기" -> -2;
            case "강기" -> -5;
            case "어검_심검" -> -8;
            default -> 0;         // 발경 — 개화한 자는 흔하다
        };
    }

    private static int weight(String group, String key, int fallback) {
        if (moraleWeights.get(group) instanceof Map<?, ?> m && m.get(key) instanceof Number n) {
            return n.intValue();
        }
        return fallback;
    }

    /** 붕괴 — 짐승은 도망치고, 사람은 도주 선언을 하고, 영물은 '떠난다' (붕괴하지 않는다. 사라진다) */
    private void breakMorale(Mob mob, Foe foe, Player player) {
        if ("영물".equals(foe.rank())) {
            mob.getWorld().spawnParticle(Particle.END_ROD, mob.getLocation().add(0, 0.6, 0), 40, 0.4, 0.6, 0.4, 0.02);
            mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 1.0f, 0.6f);
            player.sendMessage(org.bukkit.ChatColor.GRAY + "흰 그림자가 바위 뒤로 사라졌다 — 산이 데려갔다.");
            mob.remove();
            return;
        }
        mob.getPersistentDataContainer().set(KEY_FLEEING, PersistentDataType.LONG, cycle + 200);
        mob.setTarget(null);
        if ("사람".equals(foe.kind())) {
            player.sendMessage(org.bukkit.ChatColor.GRAY + foe.name() + ": \"살려… 살려주시오!\" — 등을 보이고 달아난다.");
        }
        flee(mob, player);
    }

    private void flee(Mob mob, Player player) {
        mob.setTarget(null);
        Vector away = mob.getLocation().toVector().subtract(player.getLocation().toVector());
        if (away.lengthSquared() < 0.01) {
            away = new Vector(1, 0, 0);
        }
        mob.setVelocity(away.normalize().multiply(0.45).setY(0.12));
        mob.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 1, true, false));
    }

    /** 교전 — 표적을 잡는다. 영물은 먼저 치지 않는다 (영역을 밟은 자에게만 온다) */
    private void engage(Mob mob, Foe foe, Player player) {
        double distance = mob.getLocation().distance(player.getLocation());
        boolean territorial = "영물".equals(foe.rank());
        double leash = territorial ? 8 : 24;
        if (distance <= leash && player.getGameMode() == org.bukkit.GameMode.SURVIVAL) {
            if (mob.getTarget() == null) {
                mob.setTarget(player);
            }
            if (mob instanceof Wolf wolf && !wolf.isAngry()) {
                wolf.setAngry(true);
            }
        }
        if (territorial && mob.getTarget() != null) {
            spiritSwing(mob, foe, player, distance);
        }
    }

    /** 영물의 발톱 — CAT 에는 근접 공격 AI 가 없다. 티커가 대신 친다 (덮치기 + 검기) */
    private void spiritSwing(Mob mob, Foe foe, Player player, double distance) {
        if (distance > SPIRIT_REACH || pdcLong(mob, KEY_SWING) > cycle) {
            if (distance > SPIRIT_REACH && distance < 12) {
                Vector to = player.getLocation().toVector().subtract(mob.getLocation().toVector());
                mob.setVelocity(to.normalize().multiply(0.55).setY(0.25));   // 덮치기
            }
            return;
        }
        mob.getPersistentDataContainer().set(KEY_SWING, PersistentDataType.LONG, cycle + SPIRIT_SWING_TICKS);
        player.damage(foe.attack(), mob);
        mob.getWorld().spawnParticle(Particle.SWEEP_ATTACK, player.getLocation().add(0, 1, 0), 3, 0.3, 0.3, 0.3, 0);
    }

    // ══════════════ 포위(包圍) — 다구리에도 몸이 들어갈 자리가 필요하다 ══════════════

    /**
     * 한 표적을 지금 붙잡고 있는 몸들 — 가까운 순. 앞의 {@link #engageSlots} 명만 <b>손이 들어간다</b>.
     *
     * <p>정렬은 거리 → UUID 로 안정적이다 (같은 거리에서 슬롯이 매 틱 뒤바뀌면 규칙이 아니라 난수다).
     */
    private List<Mob> engagedOn(Player player) {
        List<Mob> foes = new ArrayList<>();
        for (Entity entity : player.getNearbyEntities(ENGAGE_REACH, ENGAGE_REACH, ENGAGE_REACH)) {
            if (entity instanceof Mob mob && mob.isValid() && tag(mob, KEY_ID) != null
                    && !sparring.isSparring(mob) && player.equals(mob.getTarget())) {
                foes.add(mob);
            }
        }
        Location at = player.getLocation();
        foes.sort(java.util.Comparator
                .comparingDouble((Mob m) -> m.getLocation().distanceSquared(at))
                .thenComparing(m -> m.getUniqueId()));
        return foes;
    }

    /**
     * 포위의 규칙 — {@code combat.yml attack.gang_up.engage_slots} · {@code outnumbered_defense} 의 배선.
     *
     * <p>이 배선이 없던 시절, 등록부는 거짓말이었다: config 는 "동시에 칠 수 있는 손은 셋"이라 적어 두고
     * 코드는 <b>여섯이든 열이든 전부 때리게</b> 두었다. 도적 5인 매복이 이류 무인을 1.1합에 눕혔다.
     *
     * <p>둘로 나뉜다:
     * <ul>
     *   <li><b>슬롯</b> — 앞의 3인만 손이 들어간다. 4인째의 타격은 취소된다 (그 자리에 몸이 없다).
     *       머릿수는 '교대'가 되지 '동시타'가 되지 않는다 — 대신 도주를 막는다(party.yml 포위 -2).</li>
     *   <li><b>강제 태세</b> — 포위된 자는 회피(몸을 빼는 것)를 잃는다. 남는 것은 받아넘기기(흘리기 −1).
     *       경감은 협공 1인분보다 작다 — 그래야 '둘이 덤비는 것이 하나보다 덜 아픈' 뒤집힘이 안 난다.</li>
     * </ul>
     *
     * <p>판정 순보정(협공 +2 − 피포위 방어 +2 = 0)은 여기서 계산할 것이 없다 — 상쇄가 정본이다.
     * 그래서 고수는 산다: 명중률 절벽이 머릿수로 무너지지 않는다.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onSurround(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player) || !(event.getDamager() instanceof Mob mob)) {
            return;
        }
        if (tag(mob, KEY_ID) == null || sparring.isSparring(mob)) {
            return;   // 등록부의 몸이 아니거나 비무 중 — 여기의 규칙이 아니다
        }
        List<Mob> foes = engagedOn(player);
        if (!foes.contains(mob)) {
            foes.add(mob);   // 방금 친 몸은 붙잡고 있는 몸이다 (표적 스윕과의 한 틱 어긋남 보정)
        }
        if (foes.size() <= 1) {
            return;   // 포위가 아니다 — 일대일에는 이 규칙이 없다
        }
        if (foes.indexOf(mob) >= engageSlots) {
            event.setCancelled(true);   // 대기 — 앞선 자가 무력화·후퇴해야 슬롯이 열린다
            return;
        }
        int soak = Math.max(0, foes.size() - 1) >= forcedGuardFrom ? forcedGuardSoak : 0;
        if (soak > 0) {
            event.setDamage(Math.max(0.0, event.getDamage() - soak));
        }
    }

    // ══════════════ 바닐라 행동의 봉인 ══════════════

    /** 우리 짐승은 애완이 아니다 — 뼈로 늑대를 길들이지 못하고, 고양이는 따라오지 않는다 */
    @EventHandler
    public void onTame(EntityTameEvent event) {
        if (tag(event.getEntity(), KEY_ID) != null) {
            event.setCancelled(true);
        }
    }

    /** 사냥터의 개체군은 정원제다 — 번식으로 늘지 않는다 (그러면 정원이 의미를 잃는다) */
    @EventHandler
    public void onBreed(EntityBreedEvent event) {
        if (tag(event.getEntity(), KEY_ID) != null || tag(event.getMother(), KEY_ID) != null
                || tag(event.getFather(), KEY_ID) != null) {
            event.setCancelled(true);
        }
    }

    /** 산적은 마을 사람을 물지 않는다 — 좀비의 본능(주민·골렘 표적)을 봉인한다. 비무 상대는 도전자만 본다 */
    @EventHandler
    public void onTarget(EntityTargetLivingEntityEvent event) {
        String id = tag(event.getEntity(), KEY_ID);
        if (id == null) {
            return;
        }
        if (event.getTarget() != null && !(event.getTarget() instanceof Player)) {
            event.setCancelled(true);
            return;
        }
        if ("비무상대".equals(tag(event.getEntity(), KEY_ROLE))
                && !sparring.isSparring(event.getEntity())) {
            event.setCancelled(true);   // 비무 밖에서는 사람을 치지 않는다
        }
    }

    /**
     * 전리품 — 바닐라 드롭(썩은 살점·경험치 구슬)을 걷어내고 등록부의 것을 준다.
     * <b>경험치 구슬 0</b>: 성장은 화후로만 온다 (HuntListener — 마인크래프트의 문법을 빌리지 않는다).
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        String id = tag(event.getEntity(), KEY_ID);
        if (id == null) {
            return;
        }
        Foe foe = FOES.get(id);
        event.getDrops().clear();
        event.setDroppedExp(0);
        if (foe == null) {
            return;
        }
        for (String drop : foe.drops()) {
            ItemStack item = dropItem(drop);
            if (item != null) {
                event.getDrops().add(item);
            }
        }
        if (foe.loadout() != null && !"비무상대".equals(foe.role())) {
            // 병기 전리품 — 졸개는 반, 두목은 확실히 (config 등록 대기: hunting_grounds.yml loot.weapon_chance)
            double chance = "두목".equals(foe.role()) ? 1.0 : 0.5;
            if (ThreadLocalRandom.current().nextDouble() < chance) {
                event.getDrops().add(Weapons.make(foe.loadout()[0], foe.loadout()[1]));
            }
        }

        // 세계 다리 — **벤 자의 이름이 강호에 돈다** (도적 토벌 → 치안·소문·세력 주목).
        // 지금까지 마크에서 벤 도적은 봇의 장부에 한 줄도 남지 않았다: 세계가 둘로 쪼개져 있었다.
        org.bukkit.entity.Player slayer = event.getEntity().getKiller();
        if (slayer != null && !"비무상대".equals(foe.role())
                && !Dojang.suppressWorldEvents(event.getEntity().getWorld())) {   // 연무장의 일은 강호의 일이 아니다
            org.bukkit.Location at = event.getEntity().getLocation();
            int seen = (int) at.getWorld().getNearbyEntities(at, 24, 12, 24).stream()
                    .filter(e -> (e instanceof org.bukkit.entity.Player p && !p.equals(slayer))
                            || e instanceof org.bukkit.entity.Villager).count();
            if (foe.isBeast()) {
                WorldBridge.beastSlain(foe.id(), foe.name(), foe.realm(), "north_road", seen,
                        slayer.getUniqueId(), slayer.getName());
            } else {
                WorldBridge.banditSlain(foe.id(), foe.name(), foe.role(), foe.realm(), "north_road", seen,
                        slayer.getUniqueId(), slayer.getName());
            }
        }
    }

    /**
     * 부산물 한 점 — economy.yml price_table.사냥_부산물 · Goods 채널과 이름이 일치해야 팔린다.
     * 미등록 부산물(멧돼지_가죽·곰_가죽·엄니·호골·백영묘_가죽)은 <b>config·Goods 등록 대기</b> — 지금은 떨구지 않는다
     * (없는 이름의 물건을 만들면 장터에서 팔리지 않고 인벤토리만 더럽힌다).
     */
    private static ItemStack dropItem(String drop) {
        return switch (drop) {
            case "늑대_가죽" -> Goods.pelt("늑대");
            case "호피" -> Goods.pelt("호랑이");
            case "웅담" -> Goods.ungdam();
            case "늑대_고기", "멧돼지_고기", "고기" -> new ItemStack(Material.PORKCHOP);
            default -> null;   // 등록 대기
        };
    }

    // ══════════════ 비무 상대 — 마을에 한 사람은 늘 서 있다 ══════════════

    /** 곽진(상단 호위무사·등록 NPC) — 표국 마당의 비무 상대. 죽지 않는다(비무는 살상이 아니다) */
    private void partnerUpkeep() {
        Foe partner = FOES.get("gwakjin");
        Location yard = plugin.anchor("표국");
        if (partner == null || yard == null || yard.getWorld() == null) {
            return;
        }
        if (!yard.getWorld().isChunkLoaded(yard.getBlockX() >> 4, yard.getBlockZ() >> 4)) {
            return;
        }
        for (Entity e : yard.getWorld().getNearbyEntities(yard, 24, 12, 24)) {
            if ("gwakjin".equals(tag(e, KEY_ID)) && !e.isDead()) {
                return;   // 이미 서 있다
            }
        }
        Location at = yard.clone().add(3.5, 1, 3.5);
        LivingEntity spawned = spawn(partner, at, null);
        if (spawned != null) {
            spawned.setCustomName(org.bukkit.ChatColor.AQUA + partner.name()
                    + org.bukkit.ChatColor.GRAY + " (비무 — 우클릭)");
        }
    }

    // ══════════════ 판독 — HuntListener·Sparring 이 재질이 아니라 태그로 묻는다 ══════════════

    /** 격의 사다리 — npc_combat.yml morale.weights.상대_위세.격_목격 의 키 순서 그대로 */
    private static final String[] QI_LADDER = {"발경", "검기", "강기", "어검_심검"};

    /**
     * 격 목격 — <b>검강을 보고도 안 도망가는 졸개는 없다</b> (npc_combat morale 상대_위세.격_목격).
     *
     * <p>지금까지 전의는 내구·머릿수·두목만 봤다. 격은 <b>수치로 적혀 있었지만 아무도 보지 못했다</b> —
     * 강기(-5)를 눈앞에서 터뜨려도 산적의 전의는 꿈쩍하지 않았다. 이제 본다.
     *
     * <p>"한 번 본 것은 그 전투 내내 유지된다" — 그래서 최고 격만 PDC 에 굳힌다 (매 라운드 재계산되는
     * 다른 입력과 달리, 이것만은 기억이다).
     */
    public static void witnessQi(org.bukkit.Location at, String grade) {
        int rank = 0;
        for (int i = 0; i < QI_LADDER.length; i++) {
            if (QI_LADDER[i].equals(grade) || ("어검_심검".equals(QI_LADDER[i])
                    && ("어검".equals(grade) || "심검".equals(grade)))) {
                rank = i + 1;
            }
        }
        if (rank == 0 || at.getWorld() == null) {
            return;   // 외공기 — 볼 것이 없다
        }
        for (Entity near : at.getWorld().getNearbyEntities(at, 24, 12, 24)) {
            if (tag(near, KEY_ID) == null || near.isDead()) {
                continue;
            }
            org.bukkit.persistence.PersistentDataContainer pdc = near.getPersistentDataContainer();
            int seen = pdc.getOrDefault(KEY_SEEN_QI, PersistentDataType.INTEGER, 0);
            if (rank > seen) {
                pdc.set(KEY_SEEN_QI, PersistentDataType.INTEGER, rank);
            }
        }
    }

    public static String tag(Entity entity, NamespacedKey key) {
        return entity == null ? null
                : entity.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private static long pdcLong(Entity entity, NamespacedKey key) {
        Long value = entity.getPersistentDataContainer().get(key, PersistentDataType.LONG);
        return value == null ? 0 : value;
    }

    /** 이 몸이 우리 등록부의 것인가 — 재질 추측 금지 */
    public static Foe foeOf(Entity entity) {
        String id = tag(entity, KEY_ID);
        return id == null ? null : FOES.get(id);
    }

    /** 화후 배율의 첫 항 — 상대 격차 (cultivation.yml combat_hwahu.multipliers.상대_격차) */
    public static String gapOf(HoncheonMvt plugin, Player player, Entity entity) {
        Foe foe = foeOf(entity);
        if (foe == null) {
            return null;
        }
        return gapBetween(playerRealm(plugin, player), foe.realm());
    }

    /** 격차 4단 — 2경지 이상 아래 = 압도적 하수 (적립 0). 경지 사다리는 cultivation.yml 이 정본 */
    public static String gapBetween(String mine, String theirs) {
        int gap = realmIndex(theirs) - realmIndex(mine);
        if (gap >= 1) {
            return "상수";
        }
        if (gap == 0) {
            return "동수";
        }
        return gap == -1 ? "하수" : "압도적_하수";
    }

    public static int realmIndex(String realm) {
        int index = realmLadder.indexOf(realm);
        return index < 0 ? 0 : index;
    }

    /** 플레이어 경지 — MVT 는 캐릭터 시트가 없다 (/혼천 경지 가 세운다. SkillEngine.State 가 진실) */
    static String playerRealm(HoncheonMvt plugin, Player player) {
        return plugin.skills().state(player).realm;
    }

    /** 비무·전투의 중상 문턱 (combat.yml durability.wound_thresholds.중상.below_ratio) */
    public static double 중상비율() {
        return 중상_ratio;
    }

    // ══════════════ 관리자 소환 — 영물은 등록제다 (/혼천 소환) ══════════════

    /** 관리자 소환 — 정원 밖의 개체(영물·특정 두목)를 그 자리에 세운다. 스폰은 결정론이 아니어도 된다 */
    public LivingEntity summon(String id, Location at) {
        Foe foe = FOES.get(id);
        return foe == null ? null : spawn(foe, at, null);
    }

    /** 구역 개체군 현황 — /혼천 사냥터 (검증용) */
    public List<String> census() {
        List<String> lines = new ArrayList<>();
        for (Zone zone : plugin.zones()) {
            if (!POPULATIONS.containsKey(zone.name())) {
                continue;
            }
            World world = plugin.getServer().getWorld(zone.world());
            if (world == null) {
                continue;
            }
            boolean night = isNight(world);
            Map<String, Integer> census = new HashMap<>();
            for (Entity e : world.getNearbyEntities(new BoundingBox(zone.x1(), zone.y1(), zone.z1(),
                    zone.x2() + 1, zone.y2() + 1, zone.z2() + 1))) {
                String id = tag(e, KEY_ID);
                if (id != null && !e.isDead()) {
                    census.merge(id, 1, Integer::sum);
                }
            }
            lines.add(org.bukkit.ChatColor.GOLD + "── " + zone.name() + " ("
                    + (night ? "밤 — 맹수가 나온다" : "낮 — 들짐승과 도적") + ") ──");
            for (Quota quota : POPULATIONS.get(zone.name())) {
                Foe foe = FOES.get(quota.id());
                lines.add(org.bukkit.ChatColor.WHITE + (foe == null ? quota.id() : foe.name())
                        + org.bukkit.ChatColor.GRAY + "  " + census.getOrDefault(quota.id(), 0)
                        + " / 정원 " + quota.target(night));
            }
        }
        if (lines.isEmpty()) {
            lines.add(org.bukkit.ChatColor.GRAY + "등록된 사냥터가 없다 — 먼저 /혼천 조성");
        }
        return lines;
    }
}
