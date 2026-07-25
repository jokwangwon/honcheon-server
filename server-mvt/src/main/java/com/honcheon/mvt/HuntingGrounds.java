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

import java.nio.file.Files;
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
     * 구역별 개체군 — <b>등록부: {@code config/hunting_grounds.yml} grounds</b> ({@link #loadGrounds}).
     * {@code {id, 낮 정원, 밤 정원}}.
     *
     * <p>낮은 들짐승과 도적, 밤은 맹수. 영물(baegyeongmyo)은 <b>정원 0</b> — 등록제다
     * ({@code cultivation.yml beast_ranks.영물.policy: 양산 금지}). 관리자 소환으로만 선다.
     *
     * <p>아래 값은 <b>폴백</b>이다 — 등록부가 유실돼도 산은 서야 한다.
     */
    private static Map<String, List<Quota>> populations = Map.of(
            "북쪽 산길", List.of(
                    new Quota("san_neukdae", 4, 6),
                    new Quota("metdwaeji", 3, 2),
                    new Quota("horangi", 0, 1),
                    new Quota("bandal_gom", 0, 1),
                    new Quota("north_road_bandit", 3, 4),
                    new Quota("galho", 1, 1)));

    /** 마을 구역 이름 — hunting_grounds.yml town.zone (아래는 폴백) */
    private static String townZone = "청하현";
    /** 담장 밖 여유 — 마을 무스폰은 구역 상자 + 이 만큼 (스폰이 담 밑에 붙는 것을 막는다) */
    private static int townMargin = 8;

    /** 스폰 조건 — 등록부: hunting_grounds.yml spawn (아래는 전부 폴백값) */
    private static int spawnCycleTicks = 200;    // 재생 주기 10초 — 한 구역에 최대 1마리/주기
    private static int moraleCycleTicks = 40;    // 전의·표적 스윕 2초
    private static int nearPlayer = 96;          // 구역에 사람이 이 안에 없으면 아무것도 돌지 않는다
    private static int spawnMinDist = 20;        // 눈앞에 튀어나오지 않는다
    private static int spawnMaxDist = 64;
    private static int spawnMaxLight = 7;        // 블록 광원 — 불이 끝나는 곳부터가 사냥터다 (v6.9 ㉯)
    private static int zoneEntityCap = 18;       // 구역당 태그 개체 상한 (performance.yml)
    /** 병기 전리품 확률 — hunting_grounds.yml loot.weapon_drop_chance (역할별. 폴백: 졸개 0.5 · 두목 1.0) */
    private static Map<String, Double> weaponDropChance = Map.of("졸개", 0.5, "두목", 1.0, "default", 0.5);
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

    /**
     * <b>야생 스폰 허용 목록</b> — 등록부 {@code config/world_purity.yml} 의 {@code wild_spawn.allow}.
     *
     * <p>이것은 <b>금지 목록이 아니라 허용 목록</b>이다. 여기 없는 것은 자연 스폰이 전부 취소된다.
     *
     * <p>근거 — 무협의 산야에 젖소가 뛰노는가? 아니다.
     * 소·양·돼지·닭은 <b>가축(家畜)</b>이다. 사람이 기르는 것이지 산에 저절로 나는 것이 아니다.
     * 그러나 가축을 세계에서 지우자는 것이 아니다 — 가축은 <b>마을의 살림</b>이다.
     * 청하현 마당의 닭과 외양간의 소는 옳다. 그건 조성기가 {@code CUSTOM} 으로 <b>놓는</b> 것이고
     * ({@link #ALLOWED_REASONS} 가 이미 통과시킨다) 번식·알로도 는다.
     * 취소되는 것은 <b>야생에 저절로 솟는 것</b>뿐이다.
     *
     * <p>산야의 짐승(산늑대·멧돼지·호랑이·반달곰)은 이 파일이 정원제로 <b>심는다</b> — 바닐라가 뿌리는 게 아니라.
     *
     * <p>등록부가 없으면(config 유실) 이 폴백으로 돈다 — 서버는 떠야 한다.
     */
    private static Set<EntityType> wildAllow = EnumSet.noneOf(EntityType.class);

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

    /**
     * 치안 구간 — <b>등록부: {@code hunting_grounds.yml} security.bands</b>. 코드가 짓지 않는다.
     * {@code min}/{@code max} 는 포함. 위에서부터 첫 일치.
     */
    private record Band(String id, int min, int max, int day, int night) {
        boolean holds(int value) {
            return value >= min && value <= max;
        }

        int delta(boolean night) {
            return night ? this.night : this.day;
        }
    }

    /**
     * 마을 가축 정원 — <b>등록부: {@code hunting_grounds.yml} livestock</b>.
     *
     * <p><b>왜 사냥터 엔진이 가축을 놓는가.</b> 자연 스폰이 0 이 된 뒤
     * ({@code world_purity.yml wild_spawn.allow: []}) 가축은 <b>아무도 놓지 않으면 세계에 0마리</b>다.
     * 등록부는 오래전부터 "가축은 조성기가 CUSTOM 으로 놓는다"고 적어 두었는데 — <b>아무도 안 놓고 있었다.</b>
     *
     * <p>그리고 조성기의 일도 아니다: 조성기는 건물을 <b>한 번</b> 짓지만 가축은 <b>죽는다</b>(잡아먹고,
     * 늑대가 물고, 떨어진다). 한 번 놓고 끝나면 사흘 뒤 마당은 다시 빈다.
     * 가축은 <b>건축이 아니라 정원(定員)</b>이다 — 그리고 정원을 지키는 기계는 이미 여기 있다.
     */
    private static String livestockZone = "청하현";
    private static String livestockAnchor = "장터";
    private static int livestockRadius = 28;
    private static int livestockCycleTicks = 600;   // 가축은 급하지 않다 (30초)
    private static List<Quota> livestock = List.of();

    /** 가축이 설 땅 — 마당·밭·길. <b>지붕과 물 위에는 안 선다</b> (최고 블록이 지붕일 수 있다) */
    private static final Set<Material> YARD_GROUND = EnumSet.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT,
            Material.PODZOL, Material.FARMLAND, Material.DIRT_PATH, Material.MOSS_BLOCK,
            Material.SAND, Material.GRAVEL);

    // ══════════════ 승계(承繼) — 두목은 되살아나지 않는다. 다른 사람이 잇는다 ══════════════

    /**
     * <b>두목의 시계는 세계일이다 — 실시간이 아니었던 것이 병의 뿌리였다.</b>
     *
     * <p>{@code boss_respawn_seconds: 300} 은 <b>하루 288번</b>이다. 세계는 갈호가 하나라고 믿는데
     * MVT 는 "도적 두목이 제거되었다"를 5분마다 봇에 보고했다 — 같은 사람을 288번 죽였다고.
     * 다리가 나른 것은 사실이 아니라 <b>거짓</b>이었다. 치안이 50분이면 100 에 닿았다.
     *
     * <p>이제 <b>갈호는 되살아나지 않는다.</b> 죽으면 끝이다. 대신 며칠 뒤 <b>도백(채주)이 새 사람을
     * 산길로 내려보낸다</b> — {@code nokrim_sochae.yml relations} 가 갈호를 도백의 사람이라 적었다.
     * 그 새 사람은 <b>다른 사람</b>이고, 그를 죽이는 것은 <b>다른 사실</b>이다.
     * 수치를 깎은 것이 아니라 <b>사실을 바로잡은 것</b>이다.
     *
     * <p>수치는 전부 등록부에서 왔다 ({@code hunting_grounds.yml succession}):
     * 30 세계일 = {@code region_populace.yml death.boss_respawn_days} ·
     * 후계자 곰치 = {@code nokrim_sochae.yml people.sochae_budu} (부두목 · <b>갈호와 같은 이류</b>).
     */
    private static int successionDays = 30;
    private static String successionRoster = "npcs/regions/nokrim_sochae.yml";
    /** 두목 → 후계 사슬 (순서대로). 사슬이 다하면 <b>산길에 두목이 없다</b> — 녹림이 그 길을 포기한 것이다 */
    private static Map<String, List<String>> heirs = Map.of();

    /** 소탕 — 「부분 소탕」은 시체 하나가 아니라 <b>무리가 비었다</b>는 사실이다 */
    private static int clearanceCooldownDays = 5;
    private static String clearanceRole = "졸개";

    // ─── 재기동을 넘어 사는 상태 (plugins/HoncheonMVT/hunting_state.yml) ───
    //   ★ 메모리에만 두면 **서버를 껐다 켤 때마다 갈호가 되살아난다.** 그러면 고친 것이 아니다.
    //     원장(ledgers.yml)·구역(zones.yml)이 이미 그 전례다.
    /** 죽은 두목 → 죽은 세계일. <b>여기 든 이름은 다시 서지 않는다.</b> */
    private final Map<String, Integer> bossSlainDay = new LinkedHashMap<>();
    /** 구역 → 마지막으로 도적이 비었던 세계일 (부분 소탕의 쿨다운) */
    private final Map<String, Integer> lastClearDay = new LinkedHashMap<>();
    /** 이미 「승계」를 세계에 알린 두목 (같은 승계를 두 번 보고하지 않는다) */
    private final Set<String> announcedHeirs = new java.util.LinkedHashSet<>();

    /** 치안의 이음매 — hunting_grounds.yml security (아래는 전부 폴백). 구간이 비면 치안은 산길을 안 움직인다 */
    private static String securityStat = "치안";
    private static int securityFallback = 50;
    private static String securityRole = "졸개";
    private static List<Band> securityBands = List.of();

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
    private final MobDisplay mobDisplay;
    private long cycle;

    public HuntingGrounds(HoncheonMvt plugin) {
        this.plugin = plugin;
        this.sparring = new Sparring(plugin, this);
        this.mobDisplay = new MobDisplay(plugin);
    }

    public Sparring sparring() {
        return sparring;
    }

    /**
     * 몹의 3D 형체 — 본체를 감추고 커스텀 모델을 태우는 층 ({@link MobDisplay}).
     *
     * <p>이 층이 통째로 실패해도 사냥터는 돈다 — 그 몸이 바닐라 모습(호랑이=라바저)으로 설 뿐이다.
     * 배선: {@code HoncheonMvt} 가 이 객체를 리스너로 등록하고, 정지 시 {@code clearAll()} 을 부른다.
     */
    public MobDisplay mobDisplay() {
        return mobDisplay;
    }

    /**
     * 야생 스폰 허용 목록 판독 — {@code config/world_purity.yml} 의 {@code wild_spawn.allow}.
     *
     * <p>등록제다: <b>코드가 아니라 데이터가 정본</b>이다. 이 목록을 늘리고 줄이는 것은 yml 을 고치는 일이지
     * 자바를 고치는 일이 아니다. 검산은 {@code tools/world_purity_audit.py}.
     *
     * <p>등록부가 없거나 깨졌으면 <b>폴백으로 돈다</b> — 순도 하나 때문에 서버가 죽으면 안 된다.
     * 알 수 없는 EntityType 이름은 건너뛴다(바닐라 버전이 올라 몹 이름이 바뀌어도 서버는 뜬다).
     */
    @SuppressWarnings("unchecked")
    private static void loadWildAllow(Path cfg) {
        Path file = cfg.resolve("world_purity.yml");
        if (!Files.isRegularFile(file)) {
            return;   // 폴백 유지 — 등록부가 없어도 젖소는 여전히 산에 안 뜬다
        }
        try {
            Map<String, Object> purity = RulesConfig.section(RulesConfig.load(file), "world_purity");
            Object allow = RulesConfig.section(purity, "wild_spawn").get("allow");
            // ★ **빈 목록은 정당한 값이다** — "아무것도 자연 스폰하지 않는다"는 뜻이다.
            //   예전 코드는 `names.isEmpty()` 를 「등록부가 없다」로 읽고 **폴백을 살렸다.**
            //   그 시절엔 폴백에 이리·박쥐·물고기가 들어 있었으니 —
            //   등록부를 통째로 비워도 세계엔 여전히 이리가 뛰었을 것이다. **등록제가 아니었다.**
            //   (이 버그를 audit 의 「폴백 표류」 검사가 잡았다.)
            //   이제 목록이 **있기만 하면** 그 내용이 정본이다. 비어 있으면 비어 있는 대로 정본이다.
            if (!(allow instanceof List<?> names)) {
                return;   // 키 자체가 없다 — 그때만 폴백
            }
            Set<EntityType> parsed = EnumSet.noneOf(EntityType.class);
            for (Object name : names) {
                try {
                    parsed.add(EntityType.valueOf(String.valueOf(name).toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // 등록부에 오타가 있거나 바닐라에서 사라진 몹 — 조용히 건너뛴다
                    // (오타는 tools/world_purity_audit.py §④-a 가 잡는다 — 여기서 죽지는 않는다)
                }
            }
            wildAllow = parsed;
        } catch (RuntimeException ignored) {
            // 등록부가 깨졌다 — 폴백으로 돈다
        }
    }

    /** config 판독 — 코드가 config 보다 앞서지 않는다 (Weapons.init 과 같은 자리에서 부른다) */
    @SuppressWarnings("unchecked")
    public static void init(Path cfg) {
        loadWildAllow(cfg);
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

        MobDisplay.init(cfg);   // 몹 형체 등록부 — config/mob_models.yml (없어도 돈다: 바닐라 몸이 그대로 선다)

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

        loadGrounds(cfg);   // 개체군·스폰·전리품 — config/hunting_grounds.yml
    }

    /**
     * 사냥터 등록부 — {@code config/hunting_grounds.yml}.
     *
     * <p>구역별 정원(낮·밤)·재생 주기·마을 무스폰·병기 전리품 확률이 여기서 온다.
     * <b>등록부가 없으면 폴백으로 돈다</b> — 산이 비는 것보다는 코드가 심은 산이 낫다 (서버는 떠야 한다).
     * 다만 정원을 코드가 '짓지는' 않는다: 폴백은 등록부의 사본이지 다른 정본이 아니다.
     */
    @SuppressWarnings("unchecked")
    private static void loadGrounds(Path cfg) {
        Path file = cfg.resolve("hunting_grounds.yml");
        if (!java.nio.file.Files.isRegularFile(file)) {
            return;   // 폴백 유지
        }
        Map<String, Object> root = RulesConfig.load(file);

        if (root.get("spawn") instanceof Map<?, ?> raw) {
            Map<String, Object> s = (Map<String, Object>) raw;
            spawnCycleTicks = ticks(s.get("cycle_seconds"), spawnCycleTicks);
            moraleCycleTicks = ticks(s.get("morale_cycle_seconds"), moraleCycleTicks);
            nearPlayer = intOr(s.get("player_near"), nearPlayer);
            spawnMinDist = intOr(s.get("min_distance"), spawnMinDist);
            spawnMaxDist = intOr(s.get("max_distance"), spawnMaxDist);
            spawnMaxLight = intOr(s.get("max_block_light"), spawnMaxLight);
            zoneEntityCap = intOr(s.get("zone_entity_cap"), zoneEntityCap);
        }
        if (root.get("succession") instanceof Map<?, ?> raw) {
            Map<String, Object> s = (Map<String, Object>) raw;
            successionDays = intOr(s.get("days"), successionDays);
            successionRoster = String.valueOf(s.getOrDefault("roster", successionRoster));
            Map<String, List<String>> chain = new LinkedHashMap<>();
            if (s.get("heirs") instanceof Map<?, ?> h) {
                ((Map<String, Object>) h).forEach((boss, list) -> {
                    if (list instanceof List<?> ids) {
                        chain.put(boss, ids.stream().map(String::valueOf).toList());
                    }
                });
            }
            heirs = Map.copyOf(chain);
            loadHeirRoster(cfg.resolve(successionRoster));   // 후계자의 정의는 저 파일 하나뿐이다
        }
        if (root.get("clearance") instanceof Map<?, ?> raw) {
            Map<String, Object> c = (Map<String, Object>) raw;
            clearanceCooldownDays = intOr(c.get("cooldown_days"), clearanceCooldownDays);
            clearanceRole = String.valueOf(c.getOrDefault("role", clearanceRole));
        }
        if (root.get("town") instanceof Map<?, ?> raw) {
            Map<String, Object> t = (Map<String, Object>) raw;
            townZone = String.valueOf(t.getOrDefault("zone", townZone));
            townMargin = intOr(t.get("margin"), townMargin);
        }
        if (root.get("grounds") instanceof Map<?, ?> raw) {
            Map<String, List<Quota>> parsed = new LinkedHashMap<>();
            ((Map<String, Object>) raw).forEach((key, value) -> {
                if (!(value instanceof Map<?, ?> g)) {
                    return;
                }
                Map<String, Object> ground = (Map<String, Object>) g;
                String zone = String.valueOf(ground.get("zone"));
                List<Quota> quotas = new ArrayList<>();
                if (ground.get("population") instanceof Map<?, ?> pop) {
                    ((Map<String, Object>) pop).forEach((id, q) -> {
                        if (q instanceof Map<?, ?> qm) {
                            Map<String, Object> quota = (Map<String, Object>) qm;
                            quotas.add(new Quota(id, intOr(quota.get("day"), 0),
                                    intOr(quota.get("night"), 0)));
                        }
                    });
                }
                if (!quotas.isEmpty()) {
                    parsed.put(zone, List.copyOf(quotas));
                }
            });
            if (!parsed.isEmpty()) {
                populations = Map.copyOf(parsed);
            }
        }
        if (root.get("livestock") instanceof Map<?, ?> raw) {
            Map<String, Object> l = (Map<String, Object>) raw;
            livestockZone = String.valueOf(l.getOrDefault("zone", livestockZone));
            livestockAnchor = String.valueOf(l.getOrDefault("anchor", livestockAnchor));
            livestockRadius = intOr(l.get("radius"), livestockRadius);
            livestockCycleTicks = ticks(l.get("cycle_seconds"), livestockCycleTicks);
            List<Quota> herd = new ArrayList<>();
            if (l.get("population") instanceof Map<?, ?> pop) {
                ((Map<String, Object>) pop).forEach((id, q) -> {
                    if (q instanceof Map<?, ?> qm) {
                        Map<String, Object> quota = (Map<String, Object>) qm;
                        herd.add(new Quota(id, intOr(quota.get("day"), 0), intOr(quota.get("night"), 0)));
                    }
                });
            }
            livestock = List.copyOf(herd);
        }
        if (root.get("security") instanceof Map<?, ?> raw) {
            Map<String, Object> s = (Map<String, Object>) raw;
            securityStat = String.valueOf(s.getOrDefault("stat", securityStat));
            securityFallback = intOr(s.get("fallback"), securityFallback);
            securityRole = String.valueOf(s.getOrDefault("applies_to", securityRole));
            List<Band> bands = new ArrayList<>();
            if (s.get("bands") instanceof List<?> list) {
                for (Object row : list) {
                    if (!(row instanceof Map<?, ?> b)) {
                        continue;
                    }
                    Map<String, Object> band = (Map<String, Object>) b;
                    bands.add(new Band(String.valueOf(band.getOrDefault("id", "?")),
                            intOr(band.get("min"), Integer.MIN_VALUE),
                            intOr(band.get("max"), Integer.MAX_VALUE),
                            intOr(band.get("day"), 0), intOr(band.get("night"), 0)));
                }
            }
            securityBands = List.copyOf(bands);
        }
        if (root.get("loot") instanceof Map<?, ?> raw
                && ((Map<String, Object>) raw).get("weapon_drop_chance") instanceof Map<?, ?> chance) {
            Map<String, Double> parsed = new LinkedHashMap<>();
            ((Map<String, Object>) chance).forEach((role, value) -> {
                if (value instanceof Number n) {
                    parsed.put(role, n.doubleValue());
                }
            });
            if (!parsed.isEmpty()) {
                weaponDropChance = Map.copyOf(parsed);
            }
        }
    }

    /**
     * 후계자의 정의를 <b>지역 인구 등록부에서 읽는다</b> ({@code npcs/regions/nokrim_sochae.yml}).
     *
     * <p><b>왜 베끼지 않는가.</b> 곰치(부두목)는 이미 저기에 온전히 적혀 있다 —
     * 이름·나이·경지·몸·무장·전의·대사까지. 그를 {@code cheongha_npcs.yml} 에 <b>다시 적으면</b>
     * 같은 사람이 두 등록부에 살게 되고, 그 둘은 언젠가 갈라진다. 그것이 두 번째 정본이다.
     * <b>정의는 한 곳에만 있다.</b> 우리는 읽는다.
     *
     * <p>저 파일의 스키마는 우리 것과 다르다 ({@code body: {entity, durability}} ·
     * {@code combat: {loadout, morale}}). 그래서 여기서 <b>옮겨 담는다</b> — 발명이 아니라 번역이다.
     * 산길의 두목 자리를 잇는 것이므로 배역은 {@code 두목}이다.
     */
    @SuppressWarnings("unchecked")
    private static void loadHeirRoster(Path file) {
        if (!Files.isRegularFile(file) || heirs.isEmpty()) {
            return;   // 후계 사슬이 없으면 명부도 필요 없다
        }
        Set<String> wanted = new java.util.LinkedHashSet<>();
        heirs.values().forEach(wanted::addAll);
        try {
            Map<String, Object> people = RulesConfig.section(RulesConfig.load(file), "people");
            people.forEach((id, raw) -> {
                if (!wanted.contains(id) || !(raw instanceof Map<?, ?> m)) {
                    return;
                }
                Map<String, Object> p = (Map<String, Object>) m;
                Map<String, Object> body = p.get("body") instanceof Map<?, ?> b
                        ? (Map<String, Object>) b : Map.of();
                Map<String, Object> combat = p.get("combat") instanceof Map<?, ?> c
                        ? (Map<String, Object>) c : Map.of();

                EntityType type;
                try {
                    type = EntityType.valueOf(
                            String.valueOf(body.getOrDefault("entity", "ZOMBIE")).toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException unknown) {
                    return;   // 몸을 못 얻으면 서지 못한다 (audit 이 잡는다)
                }
                String realm = String.valueOf(p.getOrDefault("realm", "이류"));
                String[] loadout = combat.get("loadout") instanceof Map<?, ?> l
                        ? new String[]{String.valueOf(((Map<String, Object>) l).getOrDefault("계열", "도")),
                                       String.valueOf(((Map<String, Object>) l).getOrDefault("등급", "범철"))}
                        : new String[]{"도", "범철"};
                int start = 9;
                int breakAt = 2;
                if (combat.get("morale") instanceof Map<?, ?> mo) {
                    Map<String, Object> morale = (Map<String, Object>) mo;
                    start = (int) num(morale.get("start"), start);
                    breakAt = (int) num(morale.get("break"), breakAt);
                }
                int attack = weaponPower.getOrDefault(loadout[0], 1)
                        + techniquePower.getOrDefault(realm + "급", 0);
                FOES.put(id, new Foe(id, String.valueOf(p.getOrDefault("name", id)), "사람", null,
                        realm, "두목",   // 산길의 두목 자리를 잇는다 — 그것이 그의 새 배역이다
                        (int) num(body.get("durability"), 22), attack, start, breakAt,
                        List.of(), type, loadout));
            });
        } catch (RuntimeException ignored) {
            // 지역 등록부가 깨졌다 — 후계는 서지 않는다. 서버는 뜬다 (audit 이 고발한다)
        }
    }

    private static int ticks(Object seconds, int fallback) {
        return seconds instanceof Number n ? Math.max(1, (int) Math.round(n.doubleValue() * 20)) : fallback;
    }

    private static int intOr(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
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
        // 내구 — 짐승은 등록값, 사람은 config 공식 (combat.yml durability — **경지 보정 포함**).
        // 【대칭】 플레이어와 같은 공식이어야 한다. 안 그러면 절정 플레이어(26)가 절정 NPC(22)보다 두껍다.
        int durability = e.get("durability") instanceof Number n ? n.intValue()
                : Vitality.get() == null
                    ? (int) Math.round(10 + 2.0 * num(stats.get("체력"), 3))
                    : Vitality.get().durability(realm, num(stats.get("체력"), 3), 0, beast);

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
     *   <li>우리가 부른 것(CUSTOM·COMMAND·번식·알)은 통과 — 조성기의 NPC(Villager)와 이 파일의 짐승·산적.
     *       <b>마을의 가축은 여기로 들어온다</b>: 조성기가 놓고, 번식·알로 는다.</li>
     *   <li><b>적대 몹은 세계 전역에서 취소</b> — 무협 세계에 좀비·크리퍼·스켈레톤은 없다.
     *       (우리 멧돼지(HOGLIN)도 Enemy 지만 CUSTOM 이라 위에서 이미 통과했다.)</li>
     *   <li><b>허용 목록에 없는 것은 세계 전역에서 취소</b> ({@link #wildAllow} — world_purity.yml).
     *       젖소·양·돼지·닭·말·주민·떠돌이 상인·철골렘·염소·판다… 야생에 <b>저절로</b> 솟지 않는다.
     *       금지 목록이 아니라 허용 목록인 것이 핵심이다 — 바닐라가 몹을 새로 추가해도 새지 않는다.</li>
     *   <li><b>마을</b>(구역 「청하현」 + 담장 밖 8칸) — 자연 스폰 전부 취소. 이리도 마당엔 안 선다.</li>
     *   <li><b>사냥터</b> — 자연 스폰 전부 취소. 정원(定員)이 census 로 관리되려면
     *       그 땅의 짐승은 <b>전부 우리 것</b>이어야 한다 — 바닐라가 뿌린 이리가 섞이면 정원이 깨진다.</li>
     *   <li>그 밖의 산야 — 허용 목록의 것만 통과. 이리·여우·산토끼·박쥐와 물고기.</li>
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
        if (!wildAllow.contains(entity.getType())) {
            event.setCancelled(true);   // 가축·주민·마스코트 — 야생에 저절로 솟지 않는다 (마을이 기른다)
            return;
        }
        Location at = event.getLocation();
        if (inTown(at) || huntZoneAt(at) != null) {
            event.setCancelled(true);   // 마을과 사냥터의 개체군은 전부 우리 것이다
        }
    }

    /** 마을 = 구역 「청하현」 상자 + 여유. 구역이 없으면 장터 앵커 반경 80 (조성 전 폴백) */
    private boolean inTown(Location at) {
        for (Zone zone : plugin.zones()) {
            if (!townZone.equals(zone.name())) {
                continue;
            }
            return at.getWorld() != null && at.getWorld().getName().equals(zone.world())
                    && at.getBlockX() >= zone.x1() - townMargin && at.getBlockX() <= zone.x2() + townMargin
                    && at.getBlockZ() >= zone.z1() - townMargin && at.getBlockZ() <= zone.z2() + townMargin;
        }
        Location market = plugin.anchor("장터");
        return market != null && market.getWorld() == at.getWorld()
                && market.distanceSquared(at) < 80 * 80;
    }

    /** 사냥터 구역 — 개체군 표(populations)에 등록된 이름의 구역 */
    private Zone huntZoneAt(Location at) {
        for (Zone zone : plugin.zones()) {
            if (populations.containsKey(zone.name()) && zone.contains(at)) {
                return zone;
            }
        }
        return null;
    }

    // ══════════════ ② 구역 스포너 — 중앙 티커 하나 ══════════════

    public void start() {
        loadState();   // ★ 죽은 두목은 재기동해도 죽어 있다 (안 그러면 껐다 켤 때마다 갈호가 산다)
        plugin.getServer().getScheduler().runTaskTimer(plugin, Metrics.wrap("hunting", this::tick), 100L, 20L);
        mobDisplay.start();   // 형체 층 — 유령 청소 + 중앙 티커 1개 (1틱 추종)
    }

    private void tick() {
        cycle += 20;
        sparring.tick();                                  // 비무 판정 (장외·시간초과) — 티커 공유
        if (cycle % moraleCycleTicks == 0) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                sweep(player);
            }
        }
        if (cycle % spawnCycleTicks == 0) {
            for (Zone zone : plugin.zones()) {
                if (populations.containsKey(zone.name())) {
                    repopulate(zone);
                }
            }
            partnerUpkeep();   // 비무 상대(곽진) — 표국 마당에 한 사람은 늘 서 있다
        }
        if (livestockCycleTicks > 0 && cycle % livestockCycleTicks == 0) {
            repopulateLivestock();   // 마당의 닭 — 세계가 텅 비지 않는 두 축 중 하나
        }
    }

    /**
     * 마을 가축 정원 — 마당에 닭이 있고 외양간에 소가 있다.
     *
     * <p>사냥터의 {@link #repopulate} 와 같은 기계다: census 로 세고, 모자란 만큼, 한 주기에 한 마리.
     * 다른 것은 <b>자리의 규칙</b>뿐이다 —
     * <ul>
     *   <li>사냥터는 <b>어둠</b>에서 나고 사람에게서 20칸 떨어져야 하지만, 가축은 <b>마당</b>에 선다.
     *       광원도 거리도 안 본다 (등롱 밑의 닭은 옳다).</li>
     *   <li>사냥터는 마을을 <b>피하고</b>({@code inTown → continue}), 가축은 마을 <b>안에서만</b> 난다.
     *       그래서 {@link #pickSpawn} 을 쓸 수 없다 — 그 함수는 원리적으로 마을에 못 놓는다.</li>
     *   <li>땅을 가린다({@link #YARD_GROUND}) — 지붕 위의 소와 물 위의 닭을 막는다.
     *       {@code getHighestBlockYAt} 은 <b>지붕도 최고점으로 돌려주기 때문이다.</b></li>
     * </ul>
     */
    private void repopulateLivestock() {
        if (livestock.isEmpty()) {
            return;
        }
        Location yard = plugin.anchor(livestockAnchor);
        if (yard == null || yard.getWorld() == null) {
            return;   // 마당이 아직 없다 (조성 전) — 가축도 없다
        }
        World world = yard.getWorld();
        if (!world.isChunkLoaded(yard.getBlockX() >> 4, yard.getBlockZ() >> 4)) {
            return;   // 청크를 강제로 올리지 않는다 (성능)
        }
        Map<String, Integer> census = new HashMap<>();
        for (Entity e : world.getNearbyEntities(yard, livestockRadius, 24, livestockRadius)) {
            String id = tag(e, KEY_ID);
            if (id != null && !e.isDead()) {
                census.merge(id, 1, Integer::sum);
            }
        }
        boolean night = isNight(world);
        for (Quota quota : livestock) {
            Foe foe = FOES.get(quota.id());
            if (foe == null || census.getOrDefault(quota.id(), 0) >= quota.target(night)) {
                continue;
            }
            Location at = pickYardSpot(world, yard);
            if (at != null) {
                spawn(foe, at, livestockZone);   // SpawnReason.CUSTOM — 마을 무스폰을 통과한다
                return;   // 한 주기에 한 마리
            }
        }
    }

    /** 마당의 자리 — 앵커 둘레, 하늘이 보이는 흙·풀·길 위. 담장 밖으로는 안 샌다 */
    private Location pickYardSpot(World world, Location yard) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 16; attempt++) {
            int x = yard.getBlockX() + rng.nextInt(-livestockRadius, livestockRadius + 1);
            int z = yard.getBlockZ() + rng.nextInt(-livestockRadius, livestockRadius + 1);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            int y = world.getHighestBlockYAt(x, z);
            if (!YARD_GROUND.contains(world.getBlockAt(x, y, z).getType())
                    || !standable(world, x, y, z)) {
                continue;   // 지붕·물·돌바닥 — 가축의 자리가 아니다
            }
            Location at = new Location(world, x + 0.5, y + 1, z + 0.5);
            if (inTown(at)) {
                return at;
            }
        }
        return null;
    }

    /** 정원 채우기 — 사냥해서 줄면 시간이 지나 다시 찬다. 한 주기에 한 마리 (고갈되지 않되 무한하지도 않게) */
    private void repopulate(Zone zone) {
        World world = plugin.getServer().getWorld(zone.world());
        if (world == null) {
            return;
        }
        BoundingBox box = new BoundingBox(zone.x1(), zone.y1(), zone.z1(),
                zone.x2() + 1, zone.y2() + 1, zone.z2() + 1);
        Player near = nearestPlayer(world, box.getCenter().toLocation(world), nearPlayer);
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
        if (total >= zoneEntityCap) {
            return;
        }
        boolean night = isNight(world);
        for (Quota quota : populations.get(zone.name())) {
            Foe foe = FOES.get(quota.id());
            if (foe == null) {
                continue;
            }
            // ★ 두목 자리는 **사람이 아니라 자리**다. 등록부의 galho 는 「그 자리의 첫 사람」일 뿐이고,
            //   그가 죽으면 그 자리를 **다른 사람**이 잇는다 (승계). 아무도 없으면 그 자리는 빈다.
            String standing = quota.id();
            if ("두목".equals(foe.role())) {
                standing = currentBoss(quota.id());
                if (standing == null) {
                    continue;   // 산길에 두목이 없다 — 잡은 값이다. 그 길은 조용하다
                }
                foe = FOES.get(standing);
                if (foe == null) {
                    continue;   // 후계자의 정의를 못 읽었다 (audit 이 고발한다)
                }
            }
            if (census.getOrDefault(standing, 0) >= quotaFor(foe, quota, night)) {
                continue;
            }
            Location at = pickSpawn(world, zone, near, foe);
            if (at != null) {
                spawn(foe, at, zone.name());
                // 새 두목이 처음 섰다 — **그것도 사건이다.** 산채가 다시 머리를 얻었다
                if (!standing.equals(quota.id()) && announcedHeirs.add(standing)) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("zone", zone.name());
                    data.put("predecessor", quota.id());
                    data.put("successor", standing);
                    data.put("successor_name", foe.name());
                    data.put("world_day", worldDay());
                    WorldBridge.emit("bandit_boss_succeeded", data);
                    saveState();
                    plugin.getLogger().info("승계 — 「" + zone.name() + "」의 두목 자리를 "
                            + foe.name() + "(" + standing + ") 가 이었다");
                }
                return;   // 한 주기에 한 마리 — 짐승은 쏟아지지 않는다
            }
        }
    }

    // ══════════════ 승계 — 세계일의 시계, 재기동을 넘어 사는 기억 ══════════════

    /** 세계의 시계 — 봇의 자정 스케줄러가 정본이다 ("실제 하루 = 세계 1일"). 마크의 20분 하루가 아니다 */
    private static int worldDay() {
        return WorldBridge.state().day();
    }

    /**
     * <b>지금 이 산길의 두목은 누구인가.</b>
     *
     * <ul>
     *   <li>아직 안 죽었으면 — 그 사람 (갈호).</li>
     *   <li>죽었고 <b>승계일이 안 찼으면 — 아무도 없다.</b> 잡으면 그 산길은 <b>한동안 조용해진다.</b>
     *       그것이 두목을 벤 값이다.</li>
     *   <li>승계일이 찼으면 — <b>다음 사람</b> (곰치). 그는 <b>다른 사람</b>이다.</li>
     *   <li>사슬이 다했으면 — <b>영영 없다.</b> 녹림이 그 산길을 포기했다.</li>
     * </ul>
     *
     * <p>죽은 자는 {@link #bossSlainDay} 에 있고, 그 표는 <b>디스크에 굽힌다</b> — 재기동해도 죽어 있다.
     */
    private String currentBoss(String rootBossId) {
        if (!bossSlainDay.containsKey(rootBossId)) {
            return rootBossId;   // 살아 있다
        }
        int today = worldDay();
        String previous = rootBossId;
        for (String heir : heirs.getOrDefault(rootBossId, List.of())) {
            Integer slain = bossSlainDay.get(previous);
            if (slain == null) {
                return previous;
            }
            if (today - slain < successionDays) {
                return null;   // 산채가 아직 사람을 못 보냈다 — 산길에 두목이 없다
            }
            if (!bossSlainDay.containsKey(heir)) {
                return heir;   // 그가 지금의 두목이다
            }
            previous = heir;
        }
        return null;   // 사슬이 다했다
    }

    /** 두목이 죽었다 — <b>그 이름은 다시 서지 않는다.</b> 세계일을 적고 디스크에 굽는다 */
    private void bossSlain(String foeId) {
        bossSlainDay.put(foeId, worldDay());
        saveState();
    }

    /** 상태를 굽는다 — 재기동을 넘어 살아야 두목이 진짜로 죽은 것이다 */
    private void saveState() {
        org.bukkit.configuration.file.YamlConfiguration yml =
                new org.bukkit.configuration.file.YamlConfiguration();
        bossSlainDay.forEach((id, day) -> yml.set("boss_slain_day." + id, day));
        lastClearDay.forEach((zone, day) -> yml.set("last_clear_day." + zone, day));
        yml.set("announced_heirs", new ArrayList<>(announcedHeirs));
        try {
            java.io.File file = new java.io.File(plugin.getDataFolder(), "hunting_state.yml");
            file.getParentFile().mkdirs();
            yml.save(file);
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("사냥터 상태 저장 실패 — 재기동하면 두목이 되살아난다: " + e.getMessage());
        }
    }

    private void loadState() {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "hunting_state.yml");
        if (!file.isFile()) {
            return;
        }
        org.bukkit.configuration.file.YamlConfiguration yml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        if (yml.getConfigurationSection("boss_slain_day") != null) {
            for (String id : yml.getConfigurationSection("boss_slain_day").getKeys(false)) {
                bossSlainDay.put(id, yml.getInt("boss_slain_day." + id));
            }
        }
        if (yml.getConfigurationSection("last_clear_day") != null) {
            for (String zone : yml.getConfigurationSection("last_clear_day").getKeys(false)) {
                lastClearDay.put(zone, yml.getInt("last_clear_day." + zone));
            }
        }
        announcedHeirs.addAll(yml.getStringList("announced_heirs"));
        if (!bossSlainDay.isEmpty()) {
            plugin.getLogger().info("사냥터 기억 — 죽은 두목 " + bossSlainDay.size()
                    + "인 (되살아나지 않는다): " + bossSlainDay.keySet());
        }
    }

    /**
     * <b>부분 소탕</b> — 그 산길의 도적이 <b>실제로 비었을 때</b> 한 번.
     *
     * <p>병: 졸개를 벨 때마다 다리가 {@code 도적_부분_소탕}(치안 +2)을 보냈다. 10초에 한 마리가
     * 되살아나니 <b>4분이면 치안이 100</b> 이었다. 농장이었다.
     *
     * <p>등록부의 <b>이름이 이미 답을 말하고 있었다 — 「부분 소탕」</b>. 소탕은 <b>시체 하나</b>가 아니라
     * <b>무리가 비었다</b>는 사실이다. 졸개는 계속 되살아난다(산길에 도적이 없으면 산길이 아니다).
     * 우리가 고친 것은 되살아남이 아니라 <b>보고</b>다.
     */
    private void checkClearance(Zone zone, Entity dying) {
        if (zone == null) {
            return;
        }
        World world = plugin.getServer().getWorld(zone.world());
        if (world == null) {
            return;
        }
        BoundingBox box = new BoundingBox(zone.x1(), zone.y1(), zone.z1(),
                zone.x2() + 1, zone.y2() + 1, zone.z2() + 1);
        for (Entity e : world.getNearbyEntities(box)) {
            if (e.equals(dying) || e.isDead()) {
                continue;   // 지금 쓰러지는 자는 이미 없는 자다
            }
            Foe foe = foeOf(e);
            if (foe != null && clearanceRole.equals(foe.role())
                    && zone.name().equals(tag(e, KEY_ZONE))) {
                return;   // 아직 남았다 — 소탕이 아니다
            }
        }
        int today = worldDay();
        Integer last = lastClearDay.get(zone.name());
        if (last != null && today - last < clearanceCooldownDays) {
            return;   // 산채가 아직 사람을 못 모았다 — 그 길을 「비웠다」고 두 번 말할 수 없다
        }
        lastClearDay.put(zone.name(), today);
        saveState();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("zone", zone.name());
        data.put("world_day", today);
        WorldBridge.emit("bandit_camp_cleared", data);
        // ⚠ 이 kind 가 world_bridge.yml 에 아직 없다 — 등록 전까지 다리가 **버린다**(경고 로그).
        //   서버는 안 죽는다. 등록 코드는 보고서에 넘겼다.
    }

    // ══════════════ 치안(治安) — 정원을 움직이는 유일한 힘 ══════════════

    /**
     * <b>치안이 곧 도적의 정원이다.</b> 등록부 {@code region_state.yml threshold_effects} 의 배선.
     *
     * <p><b>왜 이것이 생겼나.</b> {@code region_state.yml} 은 오래전부터 이렇게 <b>약속</b>했다 —
     * <i>「치안 &lt; 30 → 도적/절도 사건 자동 발생률 증가 · 야간 이동에 위험 조우 판정 추가」</i>.
     * 그런데 <b>아무도 그 줄을 읽지 않았다.</b> 청하현의 치안이 12로 무너져도 북쪽 산길의 도적은
     * 정확히 3명이었다. 등록부가 거짓말을 하고 있었던 것이다.
     *
     * <p>이제 정원이 그 줄을 읽는다. 치안이 무너지면 산길에 도적이 늘고(밤에 두 배),
     * 치안이 넘치면 관군이 순찰해 도적이 밀려난다 ({@code hunting_grounds.yml security.bands}).
     *
     * <p><b>세 가지를 지킨다.</b>
     * <ul>
     *   <li><b>배역</b> — {@code security.applies_to}(졸개)에만 듣는다. 호랑이는 관군을 두려워하지 않고,
     *       두목은 하나뿐이다({@code cultivation.yml} 양산 금지). 치안이 갈호를 다섯으로 만들지 않는다.</li>
     *   <li><b>등록제</b> — <b>정원 0 인 자리는 움직이지 않는다.</b> "이 산길엔 낮에 도적이 없다"고
     *       등록했으면 치안이 무너져도 없다. 없던 것을 치안이 <b>만들지는</b> 않는다.</li>
     *   <li><b>결정론</b> — 난수가 없다. 치안 값 → 첫 일치 구간 → 가감. 같은 치안이면 같은 산길이다.</li>
     * </ul>
     *
     * <p>정본은 <b>봇의 장부</b>다({@link WorldBridge#state}). MVT 는 읽기만 한다 —
     * 계산을 둘이 하면 세계가 둘이 된다 ({@code Incidents} 와 같은 규약).
     */
    private static int quotaFor(Foe foe, Quota quota, boolean night) {
        int target = quota.target(night);
        if (target <= 0 || !securityRole.equals(foe.role())) {
            return target;
        }
        return Math.max(0, target + securityDelta(night));
    }

    /** 치안 → 정원 가감. 등록부의 첫 일치 구간 (없으면 0 — 치안은 산길을 안 움직인다) */
    private static int securityDelta(boolean night) {
        int value = security();
        for (Band band : securityBands) {
            if (band.holds(value)) {
                return band.delta(night);
            }
        }
        return 0;
    }

    /** 지역 치안 — 봇의 regions 표 (스냅숏). 아직 없으면 등록부의 기준값에 선다 */
    private static int security() {
        Integer value = WorldBridge.state().region().get(securityStat);
        return value == null ? securityFallback : value;
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
            if (distance < spawnMinDist || distance > spawnMaxDist) {
                continue;
            }
            // 광원 — 짐승은 어둠에서 온다 (야영터·산길 등롱 밑엔 서지 않는다). 사람(산적)은 불을 쬔다
            if (foe.isBeast() && world.getBlockAt(x, y + 1, z).getLightFromBlocks() > spawnMaxLight) {
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
    /** 등록부의 적 id 전부 — 연무장의 적수대가 목록을 그린다 */
    public static java.util.List<String> foeIds() {
        return new java.util.ArrayList<>(FOES.keySet());
    }

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
        // 형체 — 바닐라 몸을 감추고 커스텀 3D 모델을 태운다 (config/mob_models.yml).
        // 등록되지 않았거나 · 팩 없는 눈이 곁에 있거나 · 예산이 없으면 **아무 일도 일어나지 않는다** —
        // 그 몸은 바닐라 모습(호랑이=라바저)으로 선다. 형체는 덧칠이다 (MobDisplay 불변식 ㄱ).
        mobDisplay.attach(entity, foe.id());
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
            if ("가축".equals(foe.rank())) {
                continue;   // 마당의 닭은 그냥 마당에 있다 — 전의도 도주도 표적도 없다
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
        }
        // ★ 【이관】 강제 태세의 경감(−1)은 여기서 빠졌다 — 이제 **방어 태세 층**이 낸다
        //   (SkillListener.npcStrike → chooseStance/guardline). 이유:
        //     이 자리는 포위된 자가 **무엇으로 받는지 모른다**. 등록부는 흘리기(−1)를 바닥으로 주되
        //     막기(−3, 무기가 격을 먹는다)도 허용한다 (forced_guard.also_allowed).
        //     여기서 −1 을 일괄로 빼면 태세를 고른 자와 안 고른 자가 같아지고,
        //     태세 층이 또 빼면 **경감이 두 번** 든다 (둘이 덤비는 것이 하나보다 덜 아파진다 —
        //     등록부가 명시적으로 금지한 뒤집힘이다).
        //   forcedGuardFrom·forcedGuardSoak 필드는 남는다: 이 클래스의 사냥 시뮬(`/혼천 사냥검수`)이 쓴다.
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
        // ★ 맨손은 떨굴 병기가 없다 (B-144) — 폴백 무장(:729)과 짐승의 이빨이 다 "맨손"인데,
        //   Weapons.Series 에는 맨손이 없어 make 가 던졌고 **onDeath 나머지(다리 사건)까지 죽었다**
        if (foe.loadout() != null && !"비무상대".equals(foe.role())
                && !"맨손".equals(foe.loadout()[0])) {
            // 병기 전리품 — 역할별 확률은 등록부가 준다 (hunting_grounds.yml loot.weapon_drop_chance)
            double chance = weaponDropChance.getOrDefault(String.valueOf(foe.role()),
                    weaponDropChance.getOrDefault("default", 0.5));
            if (ThreadLocalRandom.current().nextDouble() < chance) {
                event.getDrops().add(Weapons.make(foe.loadout()[0], foe.loadout()[1]));
            }
        }

        // 세계 다리 — **벤 자의 이름이 강호에 돈다** (도적 토벌 → 치안·소문·세력 주목).
        // 지금까지 마크에서 벤 도적은 봇의 장부에 한 줄도 남지 않았다: 세계가 둘로 쪼개져 있었다.
        org.bukkit.entity.Player slayer = event.getEntity().getKiller();
        boolean realWorld = !Dojang.suppressWorldEvents(event.getEntity().getWorld());

        // ★ 두목이 죽었다 — **그 이름은 다시 서지 않는다.** 세계일을 적고 디스크에 굽는다.
        //   (연무장의 허수아비는 세계의 일이 아니다 — 거기서 죽인 갈호는 죽은 것이 아니다)
        if (realWorld && "두목".equals(foe.role())) {
            bossSlain(foe.id());
            plugin.getLogger().info("두목 사망 — " + foe.name() + "(" + foe.id()
                    + ") · 세계일 " + worldDay() + ". 되살아나지 않는다 (승계 "
                    + successionDays + "일)");
        }
        // ★ 「부분 소탕」 — 무리가 실제로 비었을 때 한 번 (시체 하나마다가 아니라)
        if (realWorld && clearanceRole.equals(foe.role())) {
            checkClearance(huntZoneAt(event.getEntity().getLocation()), event.getEntity());
        }

        if (slayer != null && !"비무상대".equals(foe.role()) && realWorld) {
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
            // ─── 마을 가축 (npcs 등록부 dak·so·yang·dwaeji) ───
            //   가축의 살림은 부산물 채널(Goods)이 아니라 **바닐라 식재료**다 — 마을에서 먹는 것이지
            //   장터에 내다 파는 사냥 전리품이 아니다 (economy.yml price_table.사냥_부산물 밖).
            case "닭고기" -> new ItemStack(Material.CHICKEN);
            case "달걀" -> new ItemStack(Material.EGG);
            case "소고기" -> new ItemStack(Material.BEEF);
            case "소_가죽" -> new ItemStack(Material.LEATHER);
            case "양고기" -> new ItemStack(Material.MUTTON);
            case "양털" -> new ItemStack(Material.WHITE_WOOL);
            case "돼지고기" -> new ItemStack(Material.PORKCHOP);
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
        // ★설 자리를 재고 세운다 (실기동 2026-07-25 — 표국 마당 고정 오프셋이 벽 속이 돼
        //   곽진이 10초마다 질식사·재소환을 돌았다: 로그 도배 + npc_logic 예산 초과.
        //   벽 속에 세우느니 안 세우고 로그가 말한다 — 침묵 금지)
        Standing.Verdict v = Standing.measure(at);
        if (!v.ok()) {
            Location fixed = Standing.landing(at, 8);
            if (fixed == null) {
                plugin.getLogger().severe("[비무] 곽진의 설 자리가 없다 — 표국 마당 "
                        + Standing.describe(at) + " (" + v.why() + ") · 둘레 8칸에도 못 선다");
                return;
            }
            at = fixed;
        }
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
            if (!populations.containsKey(zone.name())) {
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
            for (Quota quota : populations.get(zone.name())) {
                Foe foe = FOES.get(quota.id());
                if (foe == null) {
                    // 등록부가 심으라 한 것에 몸이 없다 — 이 자리는 **영원히 빈다.** 숨기지 않는다
                    lines.add(org.bukkit.ChatColor.RED + quota.id()
                            + org.bukkit.ChatColor.GRAY + "  ✘ 미등록 — NPC 등록부에 없다 (안 난다)");
                    continue;
                }
                // ★ 두목 자리 — 사람이 아니라 **자리**다. 누가 앉아 있는지(또는 비었는지) 말한다
                if ("두목".equals(foe.role())) {
                    String standing = currentBoss(quota.id());
                    if (standing == null) {
                        Integer slain = bossSlainDay.get(quota.id());
                        boolean exhausted = heirs.getOrDefault(quota.id(), List.of()).stream()
                                .allMatch(bossSlainDay::containsKey);
                        String why = exhausted && slain != null
                                ? "사슬이 다했다 — 녹림이 이 산길을 포기했다"
                                : "승계 대기 — 세계일 " + (slain == null ? "?"
                                    : (slain + successionDays - worldDay())) + "일 남음";
                        lines.add(org.bukkit.ChatColor.DARK_GRAY + "두목 자리  "
                                + org.bukkit.ChatColor.GRAY + "비어 있다 (" + why + ")");
                        continue;
                    }
                    Foe seated = FOES.get(standing);
                    String tail = standing.equals(quota.id()) ? ""
                            : org.bukkit.ChatColor.YELLOW + " (승계 — " + quota.id() + " 의 자리를 이었다)";
                    lines.add(org.bukkit.ChatColor.WHITE
                            + (seated == null ? standing : seated.name())
                            + org.bukkit.ChatColor.GRAY + "  " + census.getOrDefault(standing, 0)
                            + " / 정원 " + quota.target(night) + tail);
                    continue;
                }
                // 등록 정원과 **실효 정원**을 함께 보인다 — 치안이 움직였으면 그 자리에서 보여야 한다.
                // (실효만 보이면 "왜 4명이지?" 를 알 수 없고, 등록만 보이면 census 가 거짓말이 된다)
                int listed = quota.target(night);
                int effective = quotaFor(foe, quota, night);
                String shift = effective == listed ? ""
                        : org.bukkit.ChatColor.YELLOW + " (등록 " + listed + " · 치안 "
                          + security() + (effective > listed ? " ↑" : " ↓") + ")";
                lines.add(org.bukkit.ChatColor.WHITE + foe.name()
                        + org.bukkit.ChatColor.GRAY + "  " + census.getOrDefault(quota.id(), 0)
                        + " / 정원 " + effective + shift);
            }
        }
        // ── 마을 가축 — 세계가 텅 비지 않는 두 축 중 하나. 여기서도 보여야 한다 ──
        Location yard = plugin.anchor(livestockAnchor);
        if (!livestock.isEmpty() && yard != null && yard.getWorld() != null) {
            Map<String, Integer> herd = new HashMap<>();
            for (Entity e : yard.getWorld().getNearbyEntities(yard, livestockRadius, 24, livestockRadius)) {
                String id = tag(e, KEY_ID);
                if (id != null && !e.isDead()) {
                    herd.merge(id, 1, Integer::sum);
                }
            }
            boolean night = isNight(yard.getWorld());
            lines.add(org.bukkit.ChatColor.GOLD + "── " + livestockZone + " · 가축 (「"
                    + livestockAnchor + "」 둘레 " + livestockRadius + "칸) ──");
            for (Quota quota : livestock) {
                Foe foe = FOES.get(quota.id());
                lines.add(org.bukkit.ChatColor.WHITE + (foe == null ? quota.id() : foe.name())
                        + org.bukkit.ChatColor.GRAY + "  " + herd.getOrDefault(quota.id(), 0)
                        + " / 정원 " + quota.target(night));
            }
        }

        if (lines.isEmpty()) {
            lines.add(org.bukkit.ChatColor.GRAY + "등록된 사냥터가 없다 — 먼저 /혼천 조성");
        }
        return lines;
    }
}
