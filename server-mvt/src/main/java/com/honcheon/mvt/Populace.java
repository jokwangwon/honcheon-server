package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Breedable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 청하현 인구(人口) — 무명(無名)의 행인·주민을 세계에 세운다.
 *
 * <p><b>등록제.</b> 이 클래스는 이름을 지어내지 않는다. 사람도, 자리도, 일과도 전부
 * {@code config/npcs/populace.yml} 이 정한다. 코드가 하는 일은 셋뿐이다 —
 * 등록부를 읽고(스폰), 구간에 따라 자리로 보내고(일과), 재조성 때 지운다(정리).
 *
 * <p><b>계약 NPC 와의 구별.</b> 조성기(CheonghaBuilder)의 등록 NPC 9인은 "명패 + 무적(invulnerable)"이다.
 * TownAudit ⑨ 계약 검증과 TradeListener 가 둘 다 그 조건으로 게이트한다. 행인은 <b>무적이 아니다</b> →
 * 검수의 9인에 끼지 않고, 거래 GUI 도 열리지 않는다. TownAudit 은 한 줄도 고칠 필요가 없다.
 * 추가 표식으로 PDC 키 {@code honcheon:populace}(값 = 등록부 id)를 지닌다 — 정리·인구조사의 근거.
 *
 * <p><b>결정론과 난수의 경계.</b> 스폰 자리와 인원은 등록부가 정한다(난수 없음 — 사람 수가 매번 다르면
 * 그것은 세계가 아니라 날씨다). 배회(자리 안에서 어슬렁거림)만 난수를 쓴다.
 *
 * <p><b>성능.</b> 중앙 티커 하나(2초). 플레이어 근처(활성 반경)의 자리만 몸을 세우고, 멀어지면 거둔다.
 * 동시 엔티티 상한·틱당 스폰 상한·틱당 경로탐색 상한 전부 config 예산
 * (populace.yml performance / performance.yml tick_budget.subsystem_budget_ms.npc_logic = 6ms).
 * 스폰 사유는 CUSTOM — HuntingGrounds.ALLOWED_REASONS 를 통과하므로 마을 자연 스폰 차단과 충돌하지 않는다.
 *
 * <p>배선(HoncheonMvt):
 * <pre>
 *   Populace.init(cfg);                                   // 등록부 판독 (다른 init 들 옆)
 *   this.populace = new Populace(this);
 *   getServer().getPluginManager().registerEvents(populace, this);
 *   populace.start();                                     // 중앙 티커 1개
 * </pre>
 */
public final class Populace implements Listener {

    // ══════════════ 등록부 (static — config 가 단일 진실 원천) ══════════════

    /** 자리 — 앵커 + 오프셋. 좌표를 코드에 박지 않는다 (앵커는 조성기가 세운다). */
    private record Place(String name, String anchor, int dx, int dz, int radius) {
    }

    /** 사람 — 등록부 한 줄이 세계의 한 사람이 된다. */
    private record Person(String id, String name, int age, String job, String home, String disposition,
                          Map<String, String> routine, String profession, boolean baby, String voice) {
    }

    /** 사건 반응 — 소문이 돌면 다니는 자리가 바뀐다. */
    private record Reaction(String tag, Set<String> avoid, String fallback, List<String> lines) {
    }

    // ─── 지역층 (config/npcs/regions/*.yml — 척추는 npcs/region_populace.yml) ───

    /**
     * 지역의 자리 — <b>국소 좌표(f, l)</b> 다. 청하현의 {@link Place}(절대 dx/dz)와 문법이 다르다.
     *
     * <p><b>왜 f/l 인가.</b> 원거리 원형은 <b>진입 방위로 통째로 회전한다</b>
     * ({@code RemoteBuilder.Facing} · {@code entry(spec)} 가 {@code spec.approaches()} 에서 문 낼 쪽을 고른다).
     * 지형이 남쪽을 벼랑으로 만들면 채문이 동쪽에 난다. 그때 dx/dz 로 적힌 채주는 <b>목책 밖에 선다</b>.
     * f(문 쪽) · l(좌우)로 적으면 배치가 도는 대로 사람도 돈다 — <b>집이 도는 대로 사람도 돈다.</b>
     *
     * <p>다만 <b>굴 안(origin 석실·굴입구)에는 회전이 없다</b> — 굴은 방위로 서지 않는다.
     * 그래서 그 자리만 dx/dz 를 쓴다({@code local = false}). 두 문법이 한 표에 공존하는 이유다.
     */
    private record RegionPlace(String name, String origin, int f, int l, int radius,
                               String height, boolean local) {
    }

    /** 지역의 사람 — 무명(tier 0)이 아니라 세력의 조직원(tier 2)이다. 죽으면 세력이 센다. */
    private record RegionPerson(String id, String name, int age, String job, String rank, String home,
                                Map<String, String> routine, String entity, String profession,
                                boolean nameplate, boolean armed, Map<String, List<String>> lines) {
    }

    /** 태세 규칙 한 줄 — 위에서부터 첫 일치가 이긴다 (region_populace.yml stance) */
    private record StanceRule(String faction, int min, int max, boolean wanted, String stance) {
    }

    /** 지역 하나 = 등록부 파일 하나. */
    private record Region(String id, String name, String zone, String archetype, String faction,
                          String defaultStance, List<StanceRule> stanceRules,
                          Map<String, RegionPlace> places, Map<String, Map<String, String>> routines,
                          List<RegionPerson> people,
                          int maxActive, double activateRadius, double deactivateRadius) {
    }

    /**
     * 지역의 앵커 — <b>조성이 남긴 부지의 사실</b>. 좌표를 코드에 박지 않는다:
     * 조성기가 부지를 정하고, 등록부가 그 위의 자리를 정한다.
     *
     * <p>{@code plugins/HoncheonMVT/region_anchors.yml} 이 기억한다 (anchors.yml 과 같은 규약) —
     * 재기동해도 사람이 다시 선다.
     */
    private record RegionAnchor(String world, int cx, int cz, int groundY,
                                int fx, int fz, int lx, int lz,
                                int peakX, int peakY, int peakZ,
                                int raftX, int raftZ, int deckY, int quayX, int quayZ,
                                int roomX, int roomY, int roomZ,
                                int mouthX, int mouthY, int mouthZ,
                                boolean water, boolean cave) {

        /** 국소 (f, l) → 세계 x. 원점은 자리가 고른다 (중심·봉우리·뗏목·나루) */
        int x(int ox, int f, int l) {
            return ox + fx * f + lx * l;
        }

        /** 국소 (f, l) → 세계 z */
        int z(int oz, int f, int l) {
            return oz + fz * f + lz * l;
        }
    }

    private static final Map<String, Place> PLACES = new LinkedHashMap<>();
    private static final Map<String, Map<String, String>> ROUTINES = new LinkedHashMap<>();
    private static final List<Person> PEOPLE = new ArrayList<>();
    private static final List<String> SEG_NAMES = new ArrayList<>();
    private static final List<Integer> SEG_FROM = new ArrayList<>();
    private static final Map<String, Reaction> REACTIONS = new LinkedHashMap<>();
    private static final List<String> IDLE_LINES = new ArrayList<>();

    /** 지역 등록부 — place id → 지역 (npcs/regions/*.yml. 파일 이름 순 = 판독 순) */
    private static final Map<String, Region> REGIONS = new LinkedHashMap<>();

    /** 지역층 성능 예산 (region_populace.yml performance) */
    private static int regionScanRadius = 320;
    private static int regionMaxActiveGlobal = 20;

    // 말투 — populace.yml voice_by_routine (일과 → 목소리) + npc_dialogue.yml populace.voices (목소리 → 말)
    private static final Map<String, String> VOICE_BY_ROUTINE = new LinkedHashMap<>();
    private static final Map<String, Map<String, List<String>>> VOICE_LINES = new LinkedHashMap<>();
    private static final Map<String, String> SEGMENT_MAP = new LinkedHashMap<>();
    private static String nameFormat = "[{name} · {job}]";
    private static String introFormat = "{name}이라 하오.";
    private static double introChance = 0.25;
    private static long talkCooldownMs = 1000L;

    // 성능 예산 (populace.yml performance)
    private static int tickerPeriod = 40;
    private static double activateRadius = 56;
    private static double deactivateRadius = 88;
    private static int maxActive = 24;
    private static int spawnsPerTick = 2;
    private static int movesPerTick = 6;
    private static double wanderChance = 0.25;
    private static int groundScanTries = 12;

    // 죽음의 규칙(목격·시신·단계·소문·현상금·favor)은 이 클래스가 읽지 않는다 —
    // npc_death.yml populace_layer 를 Incidents 가 읽는다. 여기는 사람을 세우는 층이다.

    /**
     * 등록부 판독. HoncheonMvt.onEnable 에서 다른 init 들과 함께 한 번 부른다.
     * 파일이 없거나 깨졌으면 인구 0 — 마을은 비지만 서버는 선다 (기동 실패로 번지지 않는다).
     */
    public static void init(Path configDir) {
        PLACES.clear();
        ROUTINES.clear();
        PEOPLE.clear();
        SEG_NAMES.clear();
        SEG_FROM.clear();
        REACTIONS.clear();
        IDLE_LINES.clear();
        VOICE_BY_ROUTINE.clear();
        VOICE_LINES.clear();
        SEGMENT_MAP.clear();

        Map<String, Object> root;
        try {
            root = RulesConfig.load(configDir.resolve("npcs/populace.yml"));
        } catch (RuntimeException e) {
            return;   // 등록부 없음 — 행인 없음
        }
        if (root == null) {
            return;
        }

        for (Object o : list(root.get("segments"))) {
            Map<String, Object> seg = asMap(o);
            SEG_NAMES.add(String.valueOf(seg.get("name")));
            SEG_FROM.add(num(seg.get("from"), 0));
        }
        for (Map.Entry<String, Object> e : asMap(root.get("places")).entrySet()) {
            Map<String, Object> p = asMap(e.getValue());
            PLACES.put(e.getKey(), new Place(e.getKey(), String.valueOf(p.get("anchor")),
                    num(p.get("dx"), 0), num(p.get("dz"), 0), num(p.get("radius"), 5)));
        }
        for (Map.Entry<String, Object> e : asMap(root.get("routines")).entrySet()) {
            Map<String, String> bySegment = new LinkedHashMap<>();
            asMap(e.getValue()).forEach((k, v) -> bySegment.put(k, String.valueOf(v)));
            ROUTINES.put(e.getKey(), bySegment);
        }
        for (Map.Entry<String, Object> e : asMap(root.get("reactions")).entrySet()) {
            Map<String, Object> r = asMap(e.getValue());
            Set<String> avoid = new LinkedHashSet<>();
            for (Object a : list(r.get("avoid"))) {
                avoid.add(String.valueOf(a));
            }
            List<String> lines = new ArrayList<>();
            for (Object l : list(r.get("lines"))) {
                lines.add(String.valueOf(l));
            }
            if ("평시".equals(e.getKey())) {
                IDLE_LINES.addAll(lines);
                continue;   // 평시는 반응이 아니라 기본 대사다
            }
            REACTIONS.put(e.getKey(), new Reaction(e.getKey(), avoid,
                    r.get("fallback") == null ? null : String.valueOf(r.get("fallback")), lines));
        }

        Map<String, Object> perf = asMap(root.get("performance"));
        tickerPeriod = num(perf.get("ticker_period_ticks"), tickerPeriod);
        activateRadius = num(perf.get("activate_radius"), (int) activateRadius);
        deactivateRadius = num(perf.get("deactivate_radius"), (int) deactivateRadius);
        maxActive = num(perf.get("max_active"), maxActive);
        spawnsPerTick = num(perf.get("spawns_per_tick"), spawnsPerTick);
        movesPerTick = num(perf.get("moves_per_tick"), movesPerTick);
        Object chance = perf.get("wander_chance");
        wanderChance = chance instanceof Number n ? n.doubleValue() : wanderChance;
        groundScanTries = num(perf.get("ground_scan_tries"), groundScanTries);

        asMap(root.get("voice_by_routine")).forEach((k, v) -> VOICE_BY_ROUTINE.put(k, String.valueOf(v)));

        for (Map.Entry<String, Object> e : asMap(root.get("people")).entrySet()) {
            Map<String, Object> p = asMap(e.getValue());
            String preset = String.valueOf(p.get("routine"));
            Map<String, String> routine = new LinkedHashMap<>(ROUTINES.getOrDefault(preset, Map.of()));
            asMap(p.get("routine_override")).forEach((k, v) -> routine.put(k, String.valueOf(v)));
            Map<String, Object> body = asMap(p.get("body"));
            PEOPLE.add(new Person(e.getKey(), String.valueOf(p.get("name")), num(p.get("age"), 0),
                    String.valueOf(p.get("job")), String.valueOf(p.get("home")),
                    String.valueOf(p.get("disposition")), routine,
                    body.get("profession") == null ? "NONE" : String.valueOf(body.get("profession")),
                    Boolean.TRUE.equals(body.get("baby")),
                    VOICE_BY_ROUTINE.getOrDefault(preset, "농민")));
        }

        loadDialogue(configDir);
        loadRegions(configDir);
    }

    /**
     * 지역 등록부 판독 — {@code config/npcs/regions/*.yml} 을 전부 읽는다.
     *
     * <p>파일 하나가 지역 하나다. 파일의 {@code region:} 이 {@code world_map.yml} 의 place id 와 같아야
     * 조성이 그 앵커를 여기에 묶는다({@link #bindRegion}). 척추(문법·태세·예산)는
     * {@code config/npcs/region_populace.yml} 이 정한다 — <b>사람은 지역 파일에, 문법은 척추에.</b>
     *
     * <p>파일이 없거나 깨졌으면 그 지역만 비고 서버는 선다 (기동 실패로 번지지 않는다).
     */
    private static void loadRegions(Path configDir) {
        Map<String, Object> spine = tryLoad(configDir.resolve("npcs/region_populace.yml"));
        Map<String, Object> perf = asMap(spine == null ? null : spine.get("performance"));
        regionScanRadius = num(perf.get("region_scan_radius"), regionScanRadius);
        regionMaxActiveGlobal = num(perf.get("max_active_global"), regionMaxActiveGlobal);
        int defActive = num(perf.get("max_active_default"), 12);
        int defOn = num(perf.get("activate_radius"), 48);
        int defOff = num(perf.get("deactivate_radius"), 76);

        java.io.File dir = configDir.resolve("npcs/regions").toFile();
        java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) {
            return;   // 지역 등록부 없음 — 원거리는 빈 무대다 (그래도 청하현은 산다)
        }
        java.util.Arrays.sort(files, java.util.Comparator.comparing(java.io.File::getName));

        for (java.io.File file : files) {
            Map<String, Object> root = tryLoad(file.toPath());
            if (root == null || root.get("region") == null) {
                continue;
            }
            String id = String.valueOf(root.get("region"));

            Map<String, Object> rp = asMap(root.get("performance"));
            Map<String, RegionPlace> places = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : asMap(root.get("places")).entrySet()) {
                Map<String, Object> p = asMap(e.getValue());
                // ★ 두 문법의 공존점: f/l 이 있으면 회전하는 국소 좌표, 없으면 절대 dx/dz (굴 안).
                boolean local = p.get("f") != null || p.get("l") != null;
                places.put(e.getKey(), new RegionPlace(e.getKey(),
                        String.valueOf(p.getOrDefault("origin", "중심")),
                        num(p.get(local ? "f" : "dx"), 0), num(p.get(local ? "l" : "dz"), 0),
                        num(p.get("radius"), 4),
                        String.valueOf(p.getOrDefault("높이", "지표")), local));
            }
            Map<String, Map<String, String>> routines = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : asMap(root.get("routines")).entrySet()) {
                Map<String, String> bySeg = new LinkedHashMap<>();
                asMap(e.getValue()).forEach((k, v) -> bySeg.put(k, String.valueOf(v)));
                routines.put(e.getKey(), bySeg);
            }

            Map<String, Object> stance = asMap(root.get("stance"));
            List<StanceRule> rules = new ArrayList<>();
            for (Object o : list(stance.get("rules"))) {
                Map<String, Object> r = asMap(o);
                Map<String, Object> when = asMap(r.get("when"));
                String to = String.valueOf(r.get("stance"));
                if (Boolean.TRUE.equals(when.get("wanted"))) {
                    rules.add(new StanceRule(null, 0, Integer.MAX_VALUE, true, to));
                    continue;
                }
                Map<String, Object> fav = asMap(when.get("favor"));
                Map<String, Object> favMax = asMap(when.get("favor_max"));
                if (!fav.isEmpty()) {
                    rules.add(new StanceRule(String.valueOf(fav.get("faction")),
                            num(fav.get("min"), 0), Integer.MAX_VALUE, false, to));
                } else if (!favMax.isEmpty()) {
                    rules.add(new StanceRule(String.valueOf(favMax.get("faction")),
                            Integer.MIN_VALUE, num(favMax.get("max"), 0), false, to));
                }
            }

            List<RegionPerson> people = new ArrayList<>();
            for (Map.Entry<String, Object> e : asMap(root.get("people")).entrySet()) {
                Map<String, Object> p = asMap(e.getValue());
                String preset = String.valueOf(p.get("routine"));
                Map<String, String> routine = new LinkedHashMap<>(routines.getOrDefault(preset, Map.of()));
                Map<String, Object> body = asMap(p.get("body"));
                Map<String, List<String>> lines = new LinkedHashMap<>();
                asMap(p.get("lines")).forEach((k, v) -> {
                    List<String> out = new ArrayList<>();
                    for (Object l : list(v)) {
                        out.add(String.valueOf(l));
                    }
                    lines.put(k, out);
                });
                people.add(new RegionPerson(e.getKey(), String.valueOf(p.get("name")),
                        num(p.get("age"), 0), String.valueOf(p.get("job")),
                        String.valueOf(p.get("rank")), String.valueOf(p.get("home")), routine,
                        String.valueOf(body.getOrDefault("entity", "VILLAGER")),
                        body.get("profession") == null ? "NONE" : String.valueOf(body.get("profession")),
                        !Boolean.FALSE.equals(p.get("nameplate")),
                        p.get("combat") != null,   // 병기를 든 자만 적대할 수 있다 (stance.armed_only)
                        lines));
            }

            REGIONS.put(id, new Region(id, String.valueOf(root.get("name")),
                    String.valueOf(root.getOrDefault("zone", root.get("name"))),
                    String.valueOf(root.get("archetype")), String.valueOf(root.get("faction")),
                    String.valueOf(asMap(root.get("stance")).getOrDefault("default", "중립")), rules,
                    places, routines, people,
                    num(rp.get("max_active"), defActive),
                    num(rp.get("activate_radius"), defOn),
                    num(rp.get("deactivate_radius"), defOff)));
        }
    }

    /** 등록부 한 장 — 없거나 깨졌으면 null (그 지역만 빈다) */
    private static Map<String, Object> tryLoad(Path path) {
        try {
            return RulesConfig.load(path);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 무명의 말 — config/npc_dialogue.yml populace.
     * 대사 원문은 대화 등록부가 단일 진실 원천이다 (여기서 지어내지 않는다).
     */
    private static void loadDialogue(Path configDir) {
        Map<String, Object> root;
        try {
            root = RulesConfig.load(configDir.resolve("npc_dialogue.yml"));
        } catch (RuntimeException e) {
            return;
        }
        Map<String, Object> pop = asMap(root == null ? null : root.get("populace"));
        if (pop.isEmpty()) {
            return;
        }
        if (pop.get("cooldown_ms") instanceof Number n) {
            talkCooldownMs = n.longValue();
        }
        if (pop.get("name_format") != null) {
            nameFormat = String.valueOf(pop.get("name_format"));
        }
        if (pop.get("intro_format") != null) {
            introFormat = String.valueOf(pop.get("intro_format"));
        }
        if (pop.get("intro_chance") instanceof Number n) {
            introChance = n.doubleValue();
        }
        asMap(pop.get("segment_map")).forEach((k, v) -> SEGMENT_MAP.put(k, String.valueOf(v)));
        for (Map.Entry<String, Object> e : asMap(pop.get("voices")).entrySet()) {
            Map<String, List<String>> byKey = new LinkedHashMap<>();
            asMap(e.getValue()).forEach((k, v) -> {
                List<String> lines = new ArrayList<>();
                for (Object l : list(v)) {
                    lines.add(String.valueOf(l));
                }
                byKey.put(k, lines);
            });
            VOICE_LINES.put(e.getKey(), byKey);
        }
    }

    // ─── YAML 판독 도우미 (등록부가 비어도 터지지 않는다) ───

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    private static int num(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    // ══════════════ 런타임 ══════════════

    /** 행인 표식 — 값은 등록부 id. 계약 NPC 는 이 키가 없다 (구별의 두 번째 근거) */
    private final NamespacedKey keyId;

    /**
     * 지역 사람 표식 — 값은 {@code <지역id>/<등록id>}.
     *
     * <p><b>무명(honcheon:populace)과 다른 키인 것이 요점이다.</b> 무명의 죽음은 민심을 깎고 유족이
     * 의뢰를 낸다({@link Incidents}). 지역 사람의 죽음은 <b>세력이 센다</b>
     * (faction_reaction.yml 조직원_사망 +4) — 연쇄가 다르므로 표식이 다르다.
     * HuntingGrounds 는 {@code honcheon:foe_id} 로만 게이트하므로 이 키와도 겹치지 않는다
     * (같은 도적이 두 번 서지 않는다 — region_populace.yml §겹침 금지).
     */
    private final NamespacedKey keyRegion;

    private final HoncheonMvt plugin;
    private final Map<String, UUID> bodies = new LinkedHashMap<>();          // 등록 id → 몸
    private final Map<UUID, Long> lastTalk = new ConcurrentHashMap<>();      // 우클릭 쿨다운
    private final Set<String> activeRumors = new LinkedHashSet<>();          // 켜진 사건 반응
    private final Set<String> dead = new LinkedHashSet<>();                  // 죽은 사람 — 자리가 빈다
    private final Map<String, String> pinned = new LinkedHashMap<>();        // 사건이 붙잡아 둔 사람 (목격자·길잃은 아이)
    private final Map<String, UUID> following = new LinkedHashMap<>();       // 플레이어를 따라가는 사람

    /** 지역 앵커 — 조성이 채우고 region_anchors.yml 이 기억한다 (place id → 부지의 사실) */
    private final Map<String, RegionAnchor> regionAnchors = new LinkedHashMap<>();
    /** 지역 사람의 몸 — {@code <지역id>/<등록id>} → 몸 */
    private final Map<String, UUID> regionBodies = new LinkedHashMap<>();
    /** 죽은 지역 사람 — 자리가 빈다 (respawn_days 는 봇의 장부가 센다) */
    private final Set<String> regionDead = new LinkedHashSet<>();
    private int regionCursor;

    private Incidents incidents;   // 사건층 — 죽음·의뢰는 그쪽 소관 (생성자가 스스로 접합한다)
    private boolean curfew;
    private String segment = "";
    private int cursor;   // 배회 라운드로빈 커서 (틱당 경로탐색 예산을 나눠 쓴다)

    public Populace(HoncheonMvt plugin) {
        this.plugin = plugin;
        this.keyId = new NamespacedKey(plugin, "populace");
        this.keyRegion = new NamespacedKey(plugin, "region_npc");
    }

    // ─── 사건층과의 접합 (Incidents 가 스스로 붙는다 — HoncheonMvt 는 몰라도 된다) ───

    void bind(Incidents incidents) {
        this.incidents = incidents;
    }

    /** 지금 구간 (새벽·아침·낮·해질녘·밤) */
    public String segment() {
        return segment;
    }

    String nameOf(String id) {
        Person p = person(id);
        return p == null ? id : p.name();
    }

    String jobOf(String id) {
        Person p = person(id);
        return p == null ? "행인" : p.job();
    }

    String voiceOf(String id) {
        Person p = person(id);
        return p == null ? null : p.voice();
    }

    boolean knows(String id) {
        return person(id) != null;
    }

    boolean isDead(String id) {
        return dead.contains(id);
    }

    /** 등록 id 중 이 말투를 쓰는 사람들 (길잃음 후보 — 아이) */
    List<String> idsWithVoice(String voice) {
        List<String> out = new ArrayList<>();
        for (Person p : PEOPLE) {
            if (p.voice().equals(voice)) {
                out.add(p.id());
            }
        }
        return out;
    }

    /** 이 반경 안에 살아 있는 무명들 (목격·발견 판정의 눈) */
    List<String> aliveNear(Location at, double radius) {
        List<String> out = new ArrayList<>();
        if (at.getWorld() == null) {
            return out;
        }
        for (Map.Entry<String, UUID> e : bodies.entrySet()) {
            Entity body = plugin.getServer().getEntity(e.getValue());
            if (body == null || body.isDead() || body.getWorld() != at.getWorld()) {
                continue;
            }
            if (body.getLocation().distance(at) <= radius) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    Mob bodyOf(String id) {
        UUID uuid = bodies.get(id);
        Entity body = uuid == null ? null : plugin.getServer().getEntity(uuid);
        return body instanceof Mob mob ? mob : null;
    }

    /** 등록된 자리 이름 — 사건층이 '어디서 났는가'를 되짚을 때 (다리의 place_map 키) */
    Set<String> placeNames() {
        return PLACES.keySet();
    }

    /** 자리의 중심 좌표 (사건층이 목표·출동 지점을 잡을 때) */
    Location placeCenter(String placeName) {
        Location market = plugin.anchor("장터");
        Place place = place(placeName);
        if (market == null || market.getWorld() == null || place == null) {
            return null;
        }
        return center(market.getWorld(), place);
    }

    /** 죽었다 — 자리가 빈다. 등록부는 남지만 몸은 서지 않는다 */
    void markDead(String id) {
        dead.add(id);
        bodies.remove(id);
        pinned.remove(id);
        following.remove(id);
    }

    /** 자리를 메운다 — 무명은 대체된다. 그것이 무명의 정의다 (npc_death.populace_layer.respawn) */
    void revive(String id) {
        dead.remove(id);
    }

    /** 사건이 사람을 붙잡아 둔다 (목격자는 관아로, 길 잃은 아이는 산길 어귀에) */
    void pin(String id, String place) {
        if (place == null) {
            pinned.remove(id);
        } else {
            pinned.put(id, place);
        }
    }

    void follow(String id, UUID player) {
        following.put(id, player);
        pinned.remove(id);
    }

    void unfollow(String id) {
        following.remove(id);
    }

    /** 통행금지 — 밤에 사람이 다니지 않는다 (npc_death.populace_layer.curfew) */
    void curfew(boolean active) {
        this.curfew = active;
    }

    /** 중앙 티커 하나 — 개체별 태스크를 만들지 않는다 (performance.yml effects.central_ticker 규약) */
    public void start() {
        despawnAll();   // 재조성·재기동 잔류 개체 정리 (중복 방지)
        loadRegionAnchors();   // 조성이 남긴 부지의 사실 — 없으면 원거리는 빈 무대다
        plugin.getServer().getScheduler().runTaskTimer(plugin, Metrics.wrap("populace", this::tick), 120L, tickerPeriod);
        plugin.getLogger().info("청하현 인구 " + PEOPLE.size() + "인 등록 (무명 tier 0 — 자리 "
                + PLACES.size() + "곳, 일과 " + ROUTINES.size() + "종)");
        if (!REGIONS.isEmpty()) {
            plugin.getLogger().info("지역 인구 " + regionsRegistered() + "인 등록 (세력 tier 2 — 지역 "
                    + REGIONS.size() + "곳, 조성된 곳 " + regionAnchors.size() + ")");
        }
    }

    /** 사건 반응 스위치 — 소문이 돌면 사람들이 다니는 자리가 바뀐다 (도적 소문 → 나무꾼이 산길을 피한다) */
    public void rumor(String tag, boolean active) {
        if (active) {
            activeRumors.add(tag);
        } else {
            activeRumors.remove(tag);
        }
    }

    /** 켜진 소문 (표시·디버그용) */
    public Set<String> rumors() {
        return java.util.Collections.unmodifiableSet(activeRumors);
    }

    /** 인구 조사 — 등록 인원·현재 활성 인원·구간 */
    public List<String> census() {
        List<String> out = new ArrayList<>();
        out.add("§6── 청하현 인구 (무명 tier 0) ──");
        out.add("§7등록 §f" + PEOPLE.size() + "인 §7· 활성 §f" + bodies.size() + "인 §7· 구간 §f"
                + (segment.isEmpty() ? "?" : segment)
                + (activeRumors.isEmpty() ? "" : " §7· 소문 §c" + String.join(", ", activeRumors)));
        for (Person p : PEOPLE) {
            boolean up = bodies.containsKey(p.id());
            if (dead.contains(p.id())) {
                out.add("§4✖ §7" + p.name() + " §8(" + p.job() + ") — 죽었다");
                continue;
            }
            out.add((up ? "§a● " : "§8○ ") + "§f" + p.name() + " §7(" + p.age() + ", " + p.job() + ") — "
                    + (up ? "§7" + station(p) : "§8쉬는 중")
                    + (incidents != null && incidents.hasOffer(p.id()) ? " §e[의뢰]" : ""));
        }
        out.addAll(regionCensus());   // 원거리 지역 — 문파·산채·성문·수로채·은신처
        if (incidents != null) {
            out.addAll(incidents.status());
        }
        return out;
    }

    /** 전원 회수 — 재조성(/혼천 조성) 전에 부르면 중복 스폰이 없다. 반환값 = 지운 수 */
    public int despawnAll() {
        int removed = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity e : world.getEntities()) {
                PersistentDataContainer pdc = e.getPersistentDataContainer();
                if (pdc.has(keyId, PersistentDataType.STRING)
                        || pdc.has(keyRegion, PersistentDataType.STRING)) {
                    e.remove();
                    removed++;
                }
            }
        }
        bodies.clear();
        regionBodies.clear();
        return removed;
    }

    // ─── 중앙 티커 ───

    private void tick() {
        Location market = plugin.anchor("장터");
        World world = market == null ? null : market.getWorld();

        // 구간(새벽·아침·낮·해질녘·밤)은 청하현이 없어도 흐른다 — 지역의 하루도 같은 시계 위에 있다
        World clock = world;
        if (clock == null) {
            List<World> worlds = plugin.getServer().getWorlds();
            clock = worlds.isEmpty() ? null : worlds.get(0);
        }
        if (clock == null) {
            return;
        }
        segment = segmentAt(clock.getTime());

        // ① 청하현 무명층 — 마을이 없으면 사람도 없다 (앵커가 없으니까)
        if (world != null && !PEOPLE.isEmpty()) {
            List<Player> players = new ArrayList<>(world.getPlayers());
            prune(players);
            if (!players.isEmpty()) {
                populate(world, players);
            }
            walk(world);
        }

        // ② 지역층 — 같은 티커를 나눠 쓴다 (performance.yml effects.central_ticker: 개체별 태스크 금지).
        //    조성 안 된 지역은 앵커가 없어 한 줄도 돌지 않는다
        tickRegions();
    }

    /** 죽었거나·사라졌거나·사람에게서 멀어진 몸을 거둔다 (등록부는 남는다 — 사람은 내일 또 나온다) */
    private void prune(List<Player> players) {
        List<String> gone = new ArrayList<>();
        for (Map.Entry<String, UUID> e : bodies.entrySet()) {
            Entity body = plugin.getServer().getEntity(e.getValue());
            if (body == null || body.isDead()) {
                gone.add(e.getKey());
                continue;
            }
            Person person = person(e.getKey());
            if (person != null && station(person) == null) {
                body.remove();   // 통행금지 — 문을 잠그고 들어갔다
                gone.add(e.getKey());
                continue;
            }
            if (nearest(players, body.getLocation()) > deactivateRadius) {
                body.remove();   // 아무도 안 보는 마을은 비어 있다 (엔티티 예산)
                gone.add(e.getKey());
            }
        }
        gone.forEach(bodies::remove);
    }

    /** 등록부 순서대로 세운다 — 결정론. 누가 서는지는 난수가 아니라 등록부와 거리가 정한다 */
    private void populate(World world, List<Player> players) {
        int spawned = 0;
        for (Person person : PEOPLE) {
            if (bodies.size() >= maxActive || spawned >= spawnsPerTick) {
                return;
            }
            if (bodies.containsKey(person.id()) || dead.contains(person.id())) {
                continue;   // 죽은 사람은 서지 않는다 — 빈 자리가 남는다
            }
            Place place = place(station(person));
            if (place == null) {
                continue;
            }
            Location center = center(world, place);
            if (center == null || nearest(players, center) > activateRadius) {
                continue;   // 사람 눈이 닿지 않는 자리 — 세우지 않는다
            }
            Location at = ground(world, center, place.radius());
            if (at != null && spawn(person, at)) {
                spawned++;
            }
        }
    }

    /** 배회·귀환 — 틱당 moves_per_tick 명만 (경로탐색 예산). 라운드로빈이라 모두 차례가 온다 */
    private void walk(World world) {
        if (bodies.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>(bodies.keySet());
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < Math.min(movesPerTick, ids.size()); i++) {
            String id = ids.get(Math.floorMod(cursor++, ids.size()));
            Person person = person(id);
            Entity body = plugin.getServer().getEntity(bodies.get(id));
            if (person == null || !(body instanceof Mob mob) || mob.isDead()) {
                continue;
            }
            UUID lead = following.get(id);
            if (lead != null) {
                Player owner = plugin.getServer().getPlayer(lead);
                if (owner != null && owner.getWorld() == mob.getWorld()
                        && mob.getLocation().distance(owner.getLocation()) > 2.5) {
                    mob.getPathfinder().moveTo(owner.getLocation(), 1.1);   // 아이가 옷자락을 붙잡고 따라온다
                }
                continue;
            }
            Place place = place(station(person));
            if (place == null) {
                continue;
            }
            Location center = center(world, place);
            if (center == null || center.getWorld() != mob.getWorld()) {
                continue;
            }
            double d = mob.getLocation().distance(center);
            if (d > place.radius()) {
                mob.getPathfinder().moveTo(center, 1.0);   // 구간이 바뀌면 걸어서 간다 (텔레포트 금지 — npc_lifecycle)
            } else if (rng.nextDouble() < wanderChance) {
                Location spot = center.clone().add(
                        rng.nextDouble(-place.radius(), place.radius()), 0,
                        rng.nextDouble(-place.radius(), place.radius()));
                spot.setY(world.getHighestBlockYAt(spot) + 1);
                mob.getPathfinder().moveTo(spot, 0.8);
            }
        }
    }

    // ─── 몸을 세운다 ───

    @SuppressWarnings("deprecation")   // setCustomName — CheonghaBuilder.npc 과 같은 규약 (명패 문자열)
    private boolean spawn(Person person, Location at) {
        World world = at.getWorld();
        if (world == null) {
            return false;
        }
        Entity spawned = world.spawnEntity(at, EntityType.VILLAGER);   // SpawnReason.CUSTOM — HuntingGrounds 통과
        if (!(spawned instanceof Villager villager)) {
            spawned.remove();
            return false;
        }
        villager.setCustomName(ChatColor.GRAY + person.name());   // 회색 명패 — 계약 NPC(흰 명패)와 눈으로도 다르다
        villager.setCustomNameVisible(false);                     // 이름은 다가가 물어야 안다 (무명)
        villager.setInvulnerable(false);   // ★ 계약 NPC 와의 구별점 — TownAudit ⑨ 는 무적만 센다. 이들은 죽는다
        villager.setPersistent(false);     // 디스크에 남기지 않는다 (재기동 시 중복 방지 — 등록부가 다시 세운다)
        villager.setRemoveWhenFarAway(true);
        villager.setSilent(true);
        villager.setCanPickupItems(false);
        if (villager instanceof Ageable ageable) {
            if (person.baby()) {
                ageable.setBaby();
            }
            ageable.setAgeLock(true);
        }
        if (villager instanceof Breedable breedable) {
            breedable.setBreed(false);   // 인구는 등록부가 정한다 — 번식으로 늘지 않는다
        }
        profession(villager, person.profession());

        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        pdc.set(keyId, PersistentDataType.STRING, person.id());
        bodies.put(person.id(), villager.getUniqueId());
        return true;
    }

    /** 옷차림 — 직업 문자열이 바닐라 직업과 안 맞으면 그냥 평민(NONE)으로 선다 */
    private static void profession(Villager villager, String name) {
        try {
            org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.minecraft(name.toLowerCase(java.util.Locale.ROOT));
            Villager.Profession profession = org.bukkit.Registry.VILLAGER_PROFESSION.get(key);
            if (profession != null) {
                villager.setProfession(profession);
            }
        } catch (RuntimeException ignored) {
            // 등록되지 않은 직업 이름 — 평민으로 둔다 (등록부 오타가 서버를 세우지 못하게)
        }
    }

    // ─── 자리 계산 ───

    /**
     * 지금 이 사람이 있어야 할 자리 이름 — 사건(붙잡힘) &gt; 통행금지 &gt; 소문 &gt; 일과.
     * null 이면 이 사람은 지금 세계에 서지 않는다 (집 안에 있다).
     */
    private String station(Person person) {
        String held = pinned.get(person.id());
        if (held != null) {
            return held;   // 목격자는 관아로 달리고, 길 잃은 아이는 그 자리에 있다
        }
        String name = person.routine().get(segment);
        if (name == null && !SEG_NAMES.isEmpty()) {
            name = person.routine().get(SEG_NAMES.get(0));
        }
        // 통행금지 — 밤에는 관졸만 남는다. 해질녘부터는 집으로 (npc_death.populace_layer.curfew)
        if (curfew && !"관졸".equals(person.voice())) {
            if ("밤".equals(segment) || "새벽".equals(segment)) {
                return null;   // 문을 잠갔다. 빈 거리가 곧 형벌이다
            }
            if ("해질녘".equals(segment)) {
                return person.home();
            }
        }
        for (String tag : activeRumors) {
            Reaction reaction = REACTIONS.get(tag);
            if (reaction != null && reaction.avoid().contains(name) && reaction.fallback() != null) {
                return reaction.fallback();   // 도적 소문이 돌면 나무꾼은 산길로 가지 않는다
            }
        }
        return name;
    }

    private static Place place(String name) {
        return name == null ? null : PLACES.get(name);
    }

    private Person person(String id) {
        for (Person p : PEOPLE) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        return null;
    }

    /** 자리의 중심 — 앵커 + 오프셋 (좌표는 코드가 아니라 조성기와 등록부의 합작이다) */
    private Location center(World world, Place place) {
        Location anchor = plugin.anchor(place.anchor());
        if (anchor == null || anchor.getWorld() != world) {
            return null;
        }
        int x = anchor.getBlockX() + place.dx();
        int z = anchor.getBlockZ() + place.dz();
        return new Location(world, x + 0.5, world.getHighestBlockYAt(x, z) + 1, z + 0.5);
    }

    /** 자리 안에서 설 수 있는 땅 — 결정론이 아니어도 되는 유일한 스폰 요소는 '어느 칸이냐' 뿐이다 */
    private Location ground(World world, Location center, int radius) {
        if (standable(world, center)) {
            return center;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < groundScanTries; i++) {
            int x = center.getBlockX() + rng.nextInt(-radius, radius + 1);
            int z = center.getBlockZ() + rng.nextInt(-radius, radius + 1);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;   // 청크를 억지로 올리지 않는다 (성능)
            }
            Location at = new Location(world, x + 0.5, world.getHighestBlockYAt(x, z) + 1, z + 0.5);
            if (standable(world, at)) {
                return at;
            }
        }
        return null;
    }

    private static boolean standable(World world, Location at) {
        Material floor = world.getBlockAt(at.getBlockX(), at.getBlockY() - 1, at.getBlockZ()).getType();
        if (!floor.isSolid() || floor == Material.WATER || floor == Material.LAVA) {
            return false;
        }
        return world.getBlockAt(at).getType().isAir()
                && world.getBlockAt(at.getBlockX(), at.getBlockY() + 1, at.getBlockZ()).getType().isAir();
    }

    private static double nearest(List<Player> players, Location at) {
        double best = Double.MAX_VALUE;
        for (Player player : players) {
            if (player.getWorld() == at.getWorld()) {
                best = Math.min(best, player.getLocation().distance(at));
            }
        }
        return best;
    }

    /** 지금 구간 — config/time.yml 의 5구간을 월드 시각에 얹는다 (populace.yml segments) */
    private String segmentAt(long worldTime) {
        if (SEG_NAMES.isEmpty()) {
            return "낮";
        }
        String current = SEG_NAMES.get(SEG_NAMES.size() - 1);   // 감기는 구간(밤/새벽)이 기본
        int bestFrom = -1;
        for (int i = 0; i < SEG_NAMES.size(); i++) {
            int from = SEG_FROM.get(i);
            if (worldTime >= from && from > bestFrom) {
                bestFrom = from;
                current = SEG_NAMES.get(i);
            }
        }
        return current;
    }

    // ══════════════ 사건 — 행인은 배경이 아니라 세계의 증거다 ══════════════

    /**
     * 무명의 죽음. npc_death.yml 의 '배경'(death_weight 1) 아래 층 —
     * 계절 이벤트 예산을 먹지 않고, 후계도 복수자도 없다. 그러나 소문은 난다.
     * 강도는 killer_response.variables.witness 를 실제로 세어 정한다 (백주 장터냐, 아무도 없는 골목이냐).
     * 복수자가 없는 죽음의 대가는 npc_death.revenge.civil_debt — 지역의 냉기(민심 부채)다.
     */
    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        PersistentDataContainer pdc = event.getEntity().getPersistentDataContainer();
        String regionKey = pdc.get(keyRegion, PersistentDataType.STRING);
        if (regionKey != null) {
            onRegionDeath(event, regionKey);
            return;
        }
        String id = pdc.get(keyId, PersistentDataType.STRING);
        if (id == null) {
            return;
        }
        bodies.remove(id);
        event.getDrops().clear();      // 행인은 전리품이 아니다
        event.setDroppedExp(0);

        Person person = person(id);
        if (person == null) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        plugin.getLogger().info("[무명 사망] " + person.name() + " (" + person.job() + ")"
                + (killer == null ? " · 사인 미상" : " · 살해 " + killer.getName())
                + " · 복수자 없음 (civil_debt — 민심 자연회복 제외)");

        // 목격·시신·증거의 3변수와 그 연쇄는 사건층의 몫이다 (npc_death.yml populace_layer)
        if (incidents != null) {
            incidents.onDeath(id, event.getEntity(), killer);
        } else {
            markDead(id);   // 사건층이 없으면 자리만 빈다 (등록부 death 절이 최소 기본값)
        }
    }

    /**
     * 지역 사람의 죽음 — <b>민심이 아니라 세력이 센다</b>.
     *
     * <p>무명이 죽으면 유족이 의뢰를 내고 민심이 식는다({@link Incidents}). 조직원이 죽으면
     * <b>세력이 장부를 편다</b>: faction_reaction.yml {@code 조직원_사망_또는_중상_확인} +4 (주목 축),
     * 그리고 그 세력의 favor 가 배신_적대로 떨어진다. 졸개를 죽이면 산채가 시끄러워지고,
     * <b>채주를 죽이면 산채가 바뀐다</b> (region_populace.yml death).
     *
     * <p>MVT 는 <b>사실만</b> 보낸다 ({@code registry: region} · 누가·어디서·목격 몇·밤인가).
     * 파생값(주목 +4 · favor -4 · 소문 강도)은 <b>봇이 계산한다</b> — 둘이 계산하면 세계가 둘이 된다.
     */
    private void onRegionDeath(EntityDeathEvent event, String key) {
        regionBodies.remove(key);
        event.getDrops().clear();      // 지역 사람도 전리품이 아니다 (노획은 상자에서 난다)
        event.setDroppedExp(0);

        int slash = key.indexOf('/');
        Region region = slash < 0 ? null : REGIONS.get(key.substring(0, slash));
        RegionPerson person = region == null ? null : regionPerson(region, key.substring(slash + 1));
        if (region == null || person == null) {
            return;
        }
        regionDead.add(key);   // 자리가 빈다 — 산채는 사람을 다시 모은다 (respawn_days 는 봇이 센다)

        Location at = event.getEntity().getLocation();
        int witnesses = 0;
        for (UUID uuid : regionBodies.values()) {   // 목격 — 같은 지역의 살아 있는 눈만 센다
            Entity other = plugin.getServer().getEntity(uuid);
            if (other != null && !other.isDead() && other.getWorld() == at.getWorld()
                    && other.getLocation().distance(at) <= 24) {
                witnesses++;
            }
        }
        boolean night = "밤".equals(segment) || "새벽".equals(segment);
        Player killer = event.getEntity().getKiller();

        plugin.getLogger().info("[지역 사망] " + region.name() + " — " + person.name()
                + " (" + person.job() + " · " + person.rank() + ")"
                + (killer == null ? " · 사인 미상" : " · 살해 " + killer.getName())
                + " · 목격 " + witnesses + " · 세력 " + region.faction());

        WorldBridge.npcDeath("region", person.id(), person.name(), person.job(), region.id(),
                witnesses, night, "즉시_발견",
                killer == null ? "사고" : "플레이어_살해",
                killer == null ? null : killer.getUniqueId(),
                killer == null ? null : killer.getName());
    }

    /**
     * 우클릭 — 행인은 소문을 옮긴다. 거래 GUI 는 열리지 않는다 (행인은 상인이 아니다).
     * TradeListener 는 "명패 + 무적"만 다루므로 이 핸들러와 겹치지 않는다.
     */
    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Mob mob)) {
            return;
        }
        PersistentDataContainer pdc = mob.getPersistentDataContainer();

        // ─ 지역 사람 — 말은 태세가 고른다 (같은 산채가 어떤 자에겐 소굴이고 어떤 자에겐 집이다) ─
        String regionKey = pdc.get(keyRegion, PersistentDataType.STRING);
        if (regionKey != null) {
            event.setCancelled(true);
            Player who = event.getPlayer();
            long at = System.currentTimeMillis();
            Long prev = lastTalk.get(who.getUniqueId());
            if (prev != null && at - prev < talkCooldownMs) {
                return;
            }
            lastTalk.put(who.getUniqueId(), at);
            int slash = regionKey.indexOf('/');
            Region region = slash < 0 ? null : REGIONS.get(regionKey.substring(0, slash));
            RegionPerson person = region == null ? null
                    : regionPerson(region, regionKey.substring(slash + 1));
            if (region == null || person == null) {
                return;
            }
            // 명패 없는 자(은신처)는 이름도 생업도 대지 않는다 — 세계에는 그를 부를 이름이 없다
            String said = sayRegion(region, person, who);
            who.sendMessage(person.nameplate()
                    ? ChatColor.GRAY + label(person.name(), person.job()) + ChatColor.WHITE + " " + said
                    : ChatColor.DARK_GRAY + said);
            who.playSound(who.getLocation(), Sound.ENTITY_VILLAGER_AMBIENT, 0.6f, 1.0f);
            return;
        }

        if (!(mob instanceof Villager villager)) {
            return;
        }
        String id = villager.getPersistentDataContainer().get(keyId, PersistentDataType.STRING);
        if (id == null) {
            return;   // 계약 NPC 거나 야생 주민 — 남의 몫이다
        }
        event.setCancelled(true);   // 바닐라 거래 GUI 차단

        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Long last = lastTalk.get(player.getUniqueId());
        if (last != null && now - last < talkCooldownMs) {
            return;
        }
        lastTalk.put(player.getUniqueId(), now);

        Person person = person(id);
        if (person == null) {
            return;
        }
        // ① 사건층이 먼저 본다 — 의뢰·자수·길 잃은 아이는 잡담보다 앞선다
        if (incidents != null && incidents.onTalk(player, id, player.isSneaking())) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_AMBIENT, 0.7f, 0.9f);
            return;
        }
        // ② 잡담 — 무명도 이름이 있다. 이름을 대고, 생업을 말하고, 오늘의 세계를 말한다
        player.sendMessage(ChatColor.GRAY + label(person.name(), person.job())
                + ChatColor.WHITE + " " + say(person));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_AMBIENT, 0.6f, 1.1f);
    }

    /** 명패 — 무명도 지역 사람도 같은 서식을 쓴다 (npc_dialogue.yml populace.name_format) */
    private String label(String name, String job) {
        return nameFormat.replace("{name}", name).replace("{job}", job);
    }

    /**
     * 무명의 한 마디. 고르는 순서는 등록부가 정한다 (npc_dialogue.yml populace):
     * 애도(혈육을 잃었다) &gt; 공포(미해결 살인) &gt; 소문(사건 반응) &gt; 구간(아침·낮·밤).
     * 아이와 노인과 관졸의 말투가 다른 것은 코드가 아니라 voice_by_routine 이 정한 것이다.
     */
    private String say(Person person) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Map<String, List<String>> voice = VOICE_LINES.getOrDefault(person.voice(), Map.of());
        String mood = incidents == null ? null : incidents.moodOf(person.id());

        List<String> lines = new ArrayList<>();
        if (mood != null) {
            lines.addAll(voice.getOrDefault(mood, List.of()));
        }
        if (lines.isEmpty()) {
            // 사건 반응(populace.yml reactions.lines)이 있으면 그 얘기부터 한다 — 행인은 소문의 다리다
            for (String tag : activeRumors) {
                Reaction reaction = REACTIONS.get(tag);
                if (reaction != null) {
                    lines.addAll(reaction.lines());
                }
            }
            if (!lines.isEmpty()) {
                lines.addAll(voice.getOrDefault("소문", List.of()));
            }
        }
        if (lines.isEmpty()) {
            if (rng.nextDouble() < introChance) {
                return introFormat.replace("{name}", person.name())
                        .replace("{age}", String.valueOf(person.age()))
                        .replace("{job}", person.job())
                        .replace("{home}", person.home().replace('_', ' '));
            }
            lines.addAll(voice.getOrDefault(SEGMENT_MAP.getOrDefault(segment, "낮"), List.of()));
        }
        if (lines.isEmpty()) {
            lines.addAll(IDLE_LINES);
        }
        return lines.isEmpty() ? "…" : lines.get(rng.nextInt(lines.size()));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  지역층 — 문파에 제자가 살고, 산채에 도적이 산다
    // ══════════════════════════════════════════════════════════════════════

    /**
     * <b>조성이 끝나는 자리에서 부지의 사실을 받아 적는다.</b> 지역 인구의 유일한 앵커 원천이다.
     *
     * <p>청하현은 조성기가 앵커 8키(장터·관아…)를 등록한다. 지역에는 그런 키가 없다 —
     * {@code RemoteBuilder} 는 {@link Zone} 하나만 남기고, 그 상자는 <b>중심 대칭이라 방위를 모른다</b>.
     * 그러나 조성 순간의 {@link TerrainForge.SiteSpec} 은 전부 알고 있다: 부지 중심·지면·<b>진입 방위</b>·
     * 봉우리·물가, 그리고 {@link TerrainForge.CaveSpec} 이 굴을 안다. 그 사실을 여기서 받아 적고
     * {@code region_anchors.yml} 에 남긴다 (재기동해도 사람이 다시 선다).
     *
     * <p><b>조성이 안 됐으면 사람도 없다.</b> 앵커가 없는 지역은 등록부만 남고 몸은 서지 않는다 —
     * 자리가 없는데 사람을 세우면 그 사람은 허공에 선다.
     *
     * <p><b>배선 (MvtCommand.finishRegion 의 메인 스레드 콜백 — {@code plugin.setZones(all)} 바로 뒤):</b>
     * <pre>
     *   plugin.populace().bindRegion(place, r.spec(), r.cave());
     * </pre>
     *
     * @param place 등록부의 지역 (world_map.yml). id 가 regions/&lt;id&gt;.yml 의 {@code region:} 과 같아야 묶인다
     * @param spec  지형 계층이 낸 부지 사양 (조성이 쓴 바로 그것 — 다시 재지 않는다)
     * @param cave  굴 (은신처 원형만. 없으면 null)
     */
    public void bindRegion(WorldMap.Place place, TerrainForge.SiteSpec spec, TerrainForge.CaveSpec cave) {
        Region region = place == null ? null : REGIONS.get(place.id());
        if (region == null || spec == null) {
            return;   // 등록부에 없는 지역 — 집은 섰으나 사람은 등재되지 않았다
        }
        World world = plugin.getServer().getWorld(spec.world());
        if (world == null) {
            return;
        }

        // ─ 진입 방위 = 배치가 돈 방위. RemoteBuilder.entry / waterStockade 의 거울이다 ─
        //   수로채만 물 쪽으로 돈다 (배가 나가는 방향) — 나머지는 걸어 들어오는 쪽으로 돈다.
        boolean water = "수로채".equals(region.archetype());
        BlockFace face = water
                ? (spec.waterSides().isEmpty() ? BlockFace.SOUTH : spec.waterSides().get(0))
                : entryFace(spec);
        int[] rot = rotation(face);
        int fx = rot[0];
        int fz = rot[1];
        int lx = rot[2];
        int lz = rot[3];

        int cx = spec.cx();
        int cz = spec.cz();

        // ─ 수로채 — 뗏목(물 위)과 나루(뭍의 유일한 자리) ─
        //   RemoteBuilder.waterStockade 의 국소 상수를 그대로 따른다: 본채 뗏목 중심 = shore+17,
        //   나루 = shore-7, 갑판 = 수면+1. 집이 거기 있으므로 사람도 거기 선다.
        int raftX = cx;
        int raftZ = cz;
        int quayX = cx;
        int quayZ = cz;
        int deckY = spec.groundY();
        if (water) {
            int shore = shoreline(spec, fx, fz, lx, lz);
            deckY = waterLevel(world, spec, fx, fz) + 1;
            int rc = shore + 17;
            raftX = cx + fx * rc;
            raftZ = cz + fz * rc;
            quayX = cx + fx * (shore - 7);
            quayZ = cz + fz * (shore - 7);
        }

        RegionAnchor anchor = new RegionAnchor(world.getName(), cx, cz, spec.groundY(),
                fx, fz, lx, lz,
                spec.peakX(), spec.peakY(), spec.peakZ(),
                raftX, raftZ, deckY, quayX, quayZ,
                cave == null ? cx : cave.roomX(), cave == null ? spec.groundY() : cave.roomY(),
                cave == null ? cz : cave.roomZ(),
                cave == null ? cx : cave.mouthX(), cave == null ? spec.groundY() : cave.mouthY(),
                cave == null ? cz : cave.mouthZ(),
                water, cave != null);

        despawnRegion(region.id());
        regionAnchors.put(place.id(), anchor);
        region.people().forEach(p -> regionDead.remove(region.id() + "/" + p.id()));
        saveRegionAnchors();
        plugin.getLogger().info("[지역인구] " + region.name() + " — 앵커 (" + cx + ", " + spec.groundY()
                + ", " + cz + ") · 진입 " + face + " · " + region.people().size() + "인 등록 ("
                + region.archetype() + ")");
    }

    /** 문 낼 방위 — RemoteBuilder.entry 의 거울 (선호 남·동·서·북. 아무 데도 안 열렸으면 남) */
    private static BlockFace entryFace(TerrainForge.SiteSpec spec) {
        for (BlockFace f : new BlockFace[]{BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH}) {
            if (spec.approaches().contains(f)) {
                return f;
            }
        }
        return BlockFace.SOUTH;
    }

    /** 국소 축 (f = 문 쪽 · l = 좌우) → 세계 축. RemoteBuilder.Facing 의 표와 같은 값이다 */
    private static int[] rotation(BlockFace face) {
        return switch (face) {
            case NORTH -> new int[]{0, -1, -1, 0};
            case EAST -> new int[]{1, 0, 0, -1};
            case WEST -> new int[]{-1, 0, 0, 1};
            default -> new int[]{0, 1, 1, 0};   // 남 — 옛 상수표의 방위
        };
    }

    /** 물가 — 뭍이 끝나고 물이 시작되는 국소 f (RemoteBuilder.shoreline 의 거울) */
    private static int shoreline(TerrainForge.SiteSpec spec, int fx, int fz, int lx, int lz) {
        int cx = spec.cx();
        int cz = spec.cz();
        for (int f = -spec.radius() + 1; f < spec.radius() - 1; f++) {
            boolean here = spec.wet(cx + fx * f, cz + fz * f);
            boolean next = spec.wet(cx + fx * (f + 1), cz + fz * (f + 1));
            if (!here && next) {
                return f;
            }
        }
        return 0;
    }

    /** 수면 — 뗏목 마루는 여기서 한 칸 위다 (RemoteBuilder.waterLevel 의 거울) */
    private static int waterLevel(World world, TerrainForge.SiteSpec spec, int fx, int fz) {
        int cx = spec.cx();
        int cz = spec.cz();
        int cy = spec.groundY();
        for (int f = 0; f <= spec.radius(); f++) {
            int x = cx + fx * f;
            int z = cz + fz * f;
            if (!spec.wet(x, z)) {
                continue;
            }
            for (int y = cy + 6; y >= cy - 16; y--) {
                if (world.getBlockAt(x, y, z).getType() == Material.WATER) {
                    return y;
                }
            }
        }
        return cy;
    }

    // ─── 앵커의 기억 (plugins/HoncheonMVT/region_anchors.yml — anchors.yml 과 같은 규약) ───

    private void saveRegionAnchors() {
        org.bukkit.configuration.file.YamlConfiguration yml =
                new org.bukkit.configuration.file.YamlConfiguration();
        regionAnchors.forEach((id, a) -> {
            yml.set(id + ".world", a.world());
            yml.set(id + ".cx", a.cx());
            yml.set(id + ".cz", a.cz());
            yml.set(id + ".groundY", a.groundY());
            yml.set(id + ".fx", a.fx());
            yml.set(id + ".fz", a.fz());
            yml.set(id + ".lx", a.lx());
            yml.set(id + ".lz", a.lz());
            yml.set(id + ".peakX", a.peakX());
            yml.set(id + ".peakY", a.peakY());
            yml.set(id + ".peakZ", a.peakZ());
            yml.set(id + ".raftX", a.raftX());
            yml.set(id + ".raftZ", a.raftZ());
            yml.set(id + ".deckY", a.deckY());
            yml.set(id + ".quayX", a.quayX());
            yml.set(id + ".quayZ", a.quayZ());
            yml.set(id + ".roomX", a.roomX());
            yml.set(id + ".roomY", a.roomY());
            yml.set(id + ".roomZ", a.roomZ());
            yml.set(id + ".mouthX", a.mouthX());
            yml.set(id + ".mouthY", a.mouthY());
            yml.set(id + ".mouthZ", a.mouthZ());
            yml.set(id + ".water", a.water());
            yml.set(id + ".cave", a.cave());
        });
        try {
            yml.save(new java.io.File(plugin.getDataFolder(), "region_anchors.yml"));
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("[지역인구] 앵커를 남기지 못했다: " + e.getMessage());
        }
    }

    private void loadRegionAnchors() {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "region_anchors.yml");
        if (!file.isFile()) {
            return;   // 아직 아무 지역도 조성되지 않았다 — 원거리는 빈 무대다
        }
        org.bukkit.configuration.file.YamlConfiguration yml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        for (String id : yml.getKeys(false)) {
            if (!REGIONS.containsKey(id)) {
                continue;   // 등록부에서 사라진 지역 — 앵커만 남았다면 무시한다
            }
            regionAnchors.put(id, new RegionAnchor(yml.getString(id + ".world", ""),
                    yml.getInt(id + ".cx"), yml.getInt(id + ".cz"), yml.getInt(id + ".groundY"),
                    yml.getInt(id + ".fx"), yml.getInt(id + ".fz"),
                    yml.getInt(id + ".lx"), yml.getInt(id + ".lz"),
                    yml.getInt(id + ".peakX"), yml.getInt(id + ".peakY"), yml.getInt(id + ".peakZ"),
                    yml.getInt(id + ".raftX"), yml.getInt(id + ".raftZ"), yml.getInt(id + ".deckY"),
                    yml.getInt(id + ".quayX"), yml.getInt(id + ".quayZ"),
                    yml.getInt(id + ".roomX"), yml.getInt(id + ".roomY"), yml.getInt(id + ".roomZ"),
                    yml.getInt(id + ".mouthX"), yml.getInt(id + ".mouthY"), yml.getInt(id + ".mouthZ"),
                    yml.getBoolean(id + ".water"), yml.getBoolean(id + ".cave")));
        }
        if (!regionAnchors.isEmpty()) {
            plugin.getLogger().info("[지역인구] 앵커 " + regionAnchors.size() + "곳 로드 — 지역 "
                    + regionAnchors.keySet());
        }
    }

    // ─── 지역의 티커 (청하현의 중앙 티커 하나를 나눠 쓴다 — 개체별 태스크 금지) ───

    /**
     * 사람이 안 보는 지역은 존재하지 않는다 — 등록부만 남는다. 그리고 등록부는 공짜다.
     *
     * <p>지역은 서로 20km 넘게 떨어져 있으므로(world_map) 한 플레이어는 한 번에 한 지역에만 있다.
     * 부지 중심에서 {@code region_scan_radius} 밖이면 그 지역은 <b>한 줄도 돌지 않는다</b>.
     */
    private void tickRegions() {
        if (REGIONS.isEmpty() || regionAnchors.isEmpty()) {
            return;
        }
        for (Map.Entry<String, RegionAnchor> e : regionAnchors.entrySet()) {
            Region region = REGIONS.get(e.getKey());
            RegionAnchor anchor = e.getValue();
            World world = plugin.getServer().getWorld(anchor.world());
            if (region == null || world == null) {
                continue;
            }
            Location center = new Location(world, anchor.cx(), anchor.groundY(), anchor.cz());
            List<Player> near = new ArrayList<>();
            for (Player p : world.getPlayers()) {
                if (p.getLocation().distance(center) <= regionScanRadius) {
                    near.add(p);
                }
            }
            pruneRegion(region, anchor, near);
            if (!near.isEmpty()) {
                populateRegion(region, anchor, world, near);
                walkRegion(region, anchor, world);
            }
        }
    }

    private void pruneRegion(Region region, RegionAnchor anchor, List<Player> players) {
        List<String> gone = new ArrayList<>();
        for (Map.Entry<String, UUID> e : regionBodies.entrySet()) {
            if (!e.getKey().startsWith(region.id() + "/")) {
                continue;
            }
            Entity body = plugin.getServer().getEntity(e.getValue());
            if (body == null || body.isDead()) {
                gone.add(e.getKey());
                continue;
            }
            if (players.isEmpty() || nearest(players, body.getLocation()) > region.deactivateRadius()) {
                body.remove();   // 아무도 안 보는 산채는 비어 있다 (엔티티 예산)
                gone.add(e.getKey());
            }
        }
        gone.forEach(regionBodies::remove);
    }

    /** 등록부 순서대로 세운다 — 결정론. 누가 서는지는 난수가 아니라 등록부와 거리가 정한다 */
    private void populateRegion(Region region, RegionAnchor anchor, World world, List<Player> players) {
        int spawned = 0;
        int mine = 0;
        for (String key : regionBodies.keySet()) {
            if (key.startsWith(region.id() + "/")) {
                mine++;
            }
        }
        for (RegionPerson person : region.people()) {
            String key = region.id() + "/" + person.id();
            if (mine >= region.maxActive() || regionBodies.size() >= regionMaxActiveGlobal
                    || spawned >= spawnsPerTick) {
                return;
            }
            if (regionBodies.containsKey(key) || regionDead.contains(key)) {
                continue;
            }
            RegionPlace place = region.places().get(station(person));
            if (place == null) {
                continue;
            }
            Location center = regionCenter(world, anchor, place);
            if (center == null || nearest(players, center) > region.activateRadius()) {
                continue;   // 사람 눈이 닿지 않는 자리 — 세우지 않는다
            }
            Location at = regionGround(world, anchor, place, center);
            if (at != null && spawnRegion(region, person, key, at)) {
                spawned++;
                mine++;
            }
        }
    }

    /** 지금 이 사람이 있어야 할 자리 — 지역에는 통행금지도 소문 회피도 없다 (일과가 전부다) */
    private String station(RegionPerson person) {
        String name = person.routine().get(segment);
        if (name == null && !SEG_NAMES.isEmpty()) {
            name = person.routine().get(SEG_NAMES.get(0));
        }
        return name == null ? person.home() : name;
    }

    /**
     * 자리의 중심 — <b>원점 + 국소 좌표를 진입 방위로 돌린 것</b>.
     *
     * <p>원점은 원형마다 축의 못이 다르다 (region_populace.yml §좌표계):
     * 중심(부지) · 봉우리(도관의 축은 봉우리를 지난다) · 뗏목/나루(수로채) · 석실/굴입구(은신처).
     * 굴 안에는 회전이 없다 — 그 자리는 dx/dz 로 적히고 돌지 않는다.
     */
    private Location regionCenter(World world, RegionAnchor anchor, RegionPlace place) {
        int ox;
        int oz;
        switch (place.origin()) {
            case "봉우리" -> {
                ox = anchor.peakX();
                oz = anchor.peakZ();
            }
            case "뗏목" -> {
                ox = anchor.raftX();
                oz = anchor.raftZ();
            }
            case "나루" -> {
                ox = anchor.quayX();
                oz = anchor.quayZ();
            }
            case "석실" -> {
                ox = anchor.roomX();
                oz = anchor.roomZ();
            }
            case "굴입구" -> {
                ox = anchor.mouthX();
                oz = anchor.mouthZ();
            }
            default -> {
                ox = anchor.cx();
                oz = anchor.cz();
            }
        }
        // 굴 안(석실·굴입구)은 돌지 않는다 — 굴은 방위로 서지 않는다. 나머지는 배치와 함께 돈다.
        boolean rotate = place.local() && !"석실".equals(place.origin()) && !"굴입구".equals(place.origin());
        int x = rotate ? anchor.x(ox, place.f(), place.l()) : ox + place.f();
        int z = rotate ? anchor.z(oz, place.f(), place.l()) : oz + place.l();
        return new Location(world, x + 0.5, regionY(world, anchor, place, x, z), z + 0.5);
    }

    /** 어느 높이에 서는가 — 지표(기본) · 갑판(뗏목 마루) · 굴(산 아래) */
    private double regionY(World world, RegionAnchor anchor, RegionPlace place, int x, int z) {
        return switch (place.height()) {
            // 뗏목 마루. 지표를 쓰면 사람이 강바닥에 선다
            case "갑판" -> anchor.deckY();
            // 굴 — 앵커의 y 근방. 지표를 쓰면 사람이 산 위에 선다 (굴은 산 아래에 있다)
            case "굴" -> anchor.roomY();
            default -> world.getHighestBlockYAt(x, z) + 1;
        };
    }

    /** 자리 안에서 설 수 있는 땅. 갑판·굴은 높이가 고정이라 평면으로만 흩는다 */
    private Location regionGround(World world, RegionAnchor anchor, RegionPlace place, Location center) {
        boolean flat = "갑판".equals(place.height()) || "굴".equals(place.height());
        if (standable(world, center)) {
            return center;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < groundScanTries; i++) {
            int x = center.getBlockX() + rng.nextInt(-place.radius(), place.radius() + 1);
            int z = center.getBlockZ() + rng.nextInt(-place.radius(), place.radius() + 1);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            if (flat) {
                // 굴은 위아래로도 조금 찾는다 (방바닥이 고르지 않다). 갑판은 한 켜뿐이다
                int span = "굴".equals(place.height()) ? 3 : 0;
                for (int dy = 0; dy <= span; dy++) {
                    for (int s = -1; s <= 1; s += 2) {
                        Location at = new Location(world, x + 0.5, center.getY() + dy * s, z + 0.5);
                        if (standable(world, at)) {
                            return at;
                        }
                        if (dy == 0) {
                            break;
                        }
                    }
                }
                continue;
            }
            Location at = new Location(world, x + 0.5, world.getHighestBlockYAt(x, z) + 1, z + 0.5);
            if (standable(world, at)) {
                return at;
            }
        }
        return null;
    }

    /**
     * 몸을 세운다 — 지역 사람은 <b>병기를 든 몸</b>일 수 있다.
     *
     * <p>{@code body.entity: ZOMBIE} = 병기가 손에 보이고 근접 AI 가 도는 사람 (HuntingGrounds 와 같은 징발 근거).
     * {@code VILLAGER} = 싸우지 않는 사람 (살림·포로·상인) — 적대 태세에서도 물러선다.
     */
    @SuppressWarnings("deprecation")   // setCustomName — CheonghaBuilder.npc 과 같은 규약 (명패 문자열)
    private boolean spawnRegion(Region region, RegionPerson person, String key, Location at) {
        World world = at.getWorld();
        if (world == null) {
            return false;
        }
        EntityType type;
        try {
            type = EntityType.valueOf(person.entity());
        } catch (IllegalArgumentException e) {
            type = EntityType.VILLAGER;   // 등록부 오타가 서버를 세우지 못하게
        }
        Entity spawned = world.spawnEntity(at, type);   // SpawnReason.CUSTOM — HuntingGrounds 통과
        if (!(spawned instanceof Mob mob)) {
            spawned.remove();
            return false;
        }
        // ★ 은신처는 명패를 걸지 않는다 (nameplate: false) — 등록제는 "이름을 적으라"고 했지
        //   "이름을 보이라"고 하지 않았다. 그 셋의 이름은 등록부에만 있다.
        if (person.nameplate()) {
            mob.setCustomName(ChatColor.GRAY + person.name());
        }
        mob.setCustomNameVisible(false);   // 이름은 다가가 물어야 안다
        mob.setInvulnerable(false);   // 죽는다 — 그리고 그 죽음은 세력이 센다 (faction_reaction)
        mob.setPersistent(false);     // 디스크에 남기지 않는다 (재기동 시 중복 방지 — 등록부가 다시 세운다)
        mob.setRemoveWhenFarAway(true);
        mob.setCanPickupItems(false);
        if (mob instanceof Ageable ageable) {
            ageable.setAgeLock(true);
        }
        if (mob instanceof Breedable breedable) {
            breedable.setBreed(false);
        }
        if (mob instanceof Villager villager) {
            villager.setSilent(true);
            profession(villager, person.profession());
        }
        // 좀비 몸은 낮에 타지 않는다 (산채의 도적은 해가 뜨면 사라지는 것이 아니다)
        if (mob instanceof org.bukkit.entity.Zombie zombie) {
            zombie.setShouldBurnInDay(false);
            zombie.setAdult();
        }
        // 낯선 자에게 달려드는 것은 태세가 정한다 — 여기서는 아무도 먼저 치지 않는다
        mob.setTarget(null);

        mob.getPersistentDataContainer().set(keyRegion, PersistentDataType.STRING, key);
        regionBodies.put(key, mob.getUniqueId());
        return true;
    }

    /** 배회·귀환 — 틱당 moves_per_tick 명만 (경로탐색 예산). 라운드로빈이라 모두 차례가 온다 */
    private void walkRegion(Region region, RegionAnchor anchor, World world) {
        List<String> ids = new ArrayList<>();
        for (String key : regionBodies.keySet()) {
            if (key.startsWith(region.id() + "/")) {
                ids.add(key);
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < Math.min(movesPerTick, ids.size()); i++) {
            String key = ids.get(Math.floorMod(regionCursor++, ids.size()));
            RegionPerson person = regionPerson(region, key.substring(key.indexOf('/') + 1));
            Entity body = plugin.getServer().getEntity(regionBodies.get(key));
            if (person == null || !(body instanceof Mob mob) || mob.isDead()) {
                continue;
            }
            RegionPlace place = region.places().get(station(person));
            if (place == null) {
                continue;
            }
            Location center = regionCenter(world, anchor, place);
            if (center == null || center.getWorld() != mob.getWorld()) {
                continue;
            }
            double d = mob.getLocation().distance(center);
            if (d > place.radius()) {
                mob.getPathfinder().moveTo(center, 1.0);   // 구간이 바뀌면 걸어서 간다 (텔레포트 금지)
            } else if (rng.nextDouble() < wanderChance) {
                Location spot = center.clone().add(
                        rng.nextDouble(-place.radius(), place.radius()), 0,
                        rng.nextDouble(-place.radius(), place.radius()));
                // 갑판·굴은 높이가 고정이다 — 지표로 흩으면 사람이 강바닥이나 산 위로 걸어간다
                if (!"갑판".equals(place.height()) && !"굴".equals(place.height())) {
                    spot.setY(world.getHighestBlockYAt(spot) + 1);
                }
                mob.getPathfinder().moveTo(spot, 0.8);
            }
        }
    }

    private static RegionPerson regionPerson(Region region, String id) {
        for (RegionPerson p : region.people()) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        return null;
    }

    // ─── 태세 — favor 가 태세를 정하고, 태세가 세계를 정한다 ───

    /**
     * 같은 산채가 어떤 자에겐 소굴이고 어떤 자에겐 집이다.
     *
     * <p>규칙은 위에서부터 <b>첫 일치가 이긴다</b>. 어느 것도 안 맞으면 등록부의 default.
     * favor·수배의 출처는 <b>봇의 장부</b>다 ({@link WorldBridge.State}) — 다리가 아직 스냅숏을
     * 안 줬으면 favor 는 전부 0 이다 → 낯선 자 → 산채는 적대, 도관은 중립.
     * <b>그것이 정확히 옳다</b> (아무 이력도 없는 낭인이 산채에 걸어 들어가면 도적이 달려든다).
     */
    private String stanceOf(Region region, Player player) {
        WorldBridge.State state = WorldBridge.state();
        for (StanceRule rule : region.stanceRules()) {
            if (rule.wanted()) {
                if (state.wanted(player.getUniqueId())) {
                    return rule.stance();
                }
                continue;
            }
            int favor = state.favor(player.getUniqueId(), rule.faction());
            if (favor >= rule.min() && favor <= rule.max()) {
                return rule.stance();
            }
        }
        return region.defaultStance();
    }

    /**
     * <b>태세가 손을 정한다.</b> 좀비의 몸은 바닐라 AI 를 타고 오므로, 두지 않으면
     * <b>화산파 제자가 오르는 사람마다 검을 뽑는다</b> (문파의 무기는 칼이 아니라 문이다).
     *
     * <p>그래서 표적 잡기를 태세로 거른다:
     * <ul>
     *   <li><b>적대</b> — 병기를 든 자만 표적을 잡는다 (region_populace.yml stance.armed_only)</li>
     *   <li>살림·포로·상인({@code combat} 절이 없는 사람) — 적대 태세에서도 싸우지 않는다. 물러선다</li>
     *   <li>경계·중립·우호 — 아무도 먼저 치지 않는다. 말이 찰 뿐이다</li>
     *   <li>지역 사람끼리 — 싸우지 않는다 (같은 채의 사람이다)</li>
     * </ul>
     */
    @EventHandler
    public void onRegionTarget(org.bukkit.event.entity.EntityTargetLivingEntityEvent event) {
        String key = event.getEntity().getPersistentDataContainer()
                .get(keyRegion, PersistentDataType.STRING);
        if (key == null) {
            return;   // 무명이거나 사냥터의 적 — 남의 몫이다
        }
        if (!(event.getTarget() instanceof Player player)) {
            event.setCancelled(true);   // 같은 채의 사람에게 칼을 겨누지 않는다
            return;
        }
        int slash = key.indexOf('/');
        Region region = slash < 0 ? null : REGIONS.get(key.substring(0, slash));
        RegionPerson person = region == null ? null : regionPerson(region, key.substring(slash + 1));
        if (region == null || person == null) {
            return;
        }
        if (!person.armed() || !"적대".equals(stanceOf(region, player))) {
            event.setCancelled(true);
        }
    }

    /** 지역 사람의 한 마디 — 태세가 고른다 (등록부 lines.<태세>). 무명의 말과 달리 세력의 말이다 */
    private String sayRegion(Region region, RegionPerson person, Player player) {
        List<String> lines = person.lines().getOrDefault(stanceOf(region, player), List.of());
        if (lines.isEmpty()) {
            return "…";
        }
        return lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
    }

    // ─── 정리·조사 ───

    /** 한 지역의 몸을 전부 거둔다 (재조성 = 덮어쓰기) */
    private int despawnRegion(String regionId) {
        int removed = 0;
        List<String> gone = new ArrayList<>();
        for (Map.Entry<String, UUID> e : regionBodies.entrySet()) {
            if (!e.getKey().startsWith(regionId + "/")) {
                continue;
            }
            Entity body = plugin.getServer().getEntity(e.getValue());
            if (body != null) {
                body.remove();
                removed++;
            }
            gone.add(e.getKey());
        }
        gone.forEach(regionBodies::remove);
        return removed;
    }

    /** 지역 인구 조사 (/혼천 인구 의 아래 켜) */
    public List<String> regionCensus() {
        List<String> out = new ArrayList<>();
        if (REGIONS.isEmpty()) {
            return out;
        }
        out.add("§6── 지역 인구 (세력 tier 2) ──");
        for (Region region : REGIONS.values()) {
            boolean built = regionAnchors.containsKey(region.id());
            int up = 0;
            for (String key : regionBodies.keySet()) {
                if (key.startsWith(region.id() + "/")) {
                    up++;
                }
            }
            out.add((built ? "§a● " : "§8○ ") + "§f" + region.name() + " §7(" + region.archetype()
                    + ") — 등록 §f" + region.people().size() + "인"
                    + (built ? " §7· 활성 §f" + up + "인" : " §8· 미조성 (앵커 없음 — 사람도 없다)"));
        }
        return out;
    }

    /** 지역 등록 인원 (배선·검수용) */
    public static int regionsRegistered() {
        int n = 0;
        for (Region r : REGIONS.values()) {
            n += r.people().size();
        }
        return n;
    }

    /** 등록 인원 (배선·검수용) */
    public static int registered() {
        return PEOPLE.size();
    }

    /** 활성 인원 (성능 관측용) */
    public int active() {
        return bodies.size();
    }

    /** 새 몸을 붙일 때 참조하는 이름 목록 — 디버그용 (등록부 순서 유지) */
    public static List<String> names() {
        List<String> out = new ArrayList<>();
        for (Person p : PEOPLE) {
            out.add(p.name());
        }
        return out;
    }

    /** 미사용 방지 — 등록부 성정을 봇/대화 층이 읽을 때의 창구 */
    public static Map<String, String> dispositions() {
        Map<String, String> out = new HashMap<>();
        for (Person p : PEOPLE) {
            out.put(p.name(), p.disposition());
        }
        return out;
    }

    /** 거처 — 등록부 home (봇·서사층 조회용) */
    public static Map<String, String> homes() {
        Map<String, String> out = new HashMap<>();
        for (Person p : PEOPLE) {
            out.put(p.name(), p.home());
        }
        return out;
    }
}
