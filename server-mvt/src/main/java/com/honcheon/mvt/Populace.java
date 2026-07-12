package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
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

    private static final Map<String, Place> PLACES = new LinkedHashMap<>();
    private static final Map<String, Map<String, String>> ROUTINES = new LinkedHashMap<>();
    private static final List<Person> PEOPLE = new ArrayList<>();
    private static final List<String> SEG_NAMES = new ArrayList<>();
    private static final List<Integer> SEG_FROM = new ArrayList<>();
    private static final Map<String, Reaction> REACTIONS = new LinkedHashMap<>();
    private static final List<String> IDLE_LINES = new ArrayList<>();

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

    private final HoncheonMvt plugin;
    private final Map<String, UUID> bodies = new LinkedHashMap<>();          // 등록 id → 몸
    private final Map<UUID, Long> lastTalk = new ConcurrentHashMap<>();      // 우클릭 쿨다운
    private final Set<String> activeRumors = new LinkedHashSet<>();          // 켜진 사건 반응
    private final Set<String> dead = new LinkedHashSet<>();                  // 죽은 사람 — 자리가 빈다
    private final Map<String, String> pinned = new LinkedHashMap<>();        // 사건이 붙잡아 둔 사람 (목격자·길잃은 아이)
    private final Map<String, UUID> following = new LinkedHashMap<>();       // 플레이어를 따라가는 사람
    private Incidents incidents;   // 사건층 — 죽음·의뢰는 그쪽 소관 (생성자가 스스로 접합한다)
    private boolean curfew;
    private String segment = "";
    private int cursor;   // 배회 라운드로빈 커서 (틱당 경로탐색 예산을 나눠 쓴다)

    public Populace(HoncheonMvt plugin) {
        this.plugin = plugin;
        this.keyId = new NamespacedKey(plugin, "populace");
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
        plugin.getServer().getScheduler().runTaskTimer(plugin, Metrics.wrap("populace", this::tick), 120L, tickerPeriod);
        plugin.getLogger().info("청하현 인구 " + PEOPLE.size() + "인 등록 (무명 tier 0 — 자리 "
                + PLACES.size() + "곳, 일과 " + ROUTINES.size() + "종)");
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
                if (e.getPersistentDataContainer().has(keyId, PersistentDataType.STRING)) {
                    e.remove();
                    removed++;
                }
            }
        }
        bodies.clear();
        return removed;
    }

    // ─── 중앙 티커 ───

    private void tick() {
        if (PEOPLE.isEmpty()) {
            return;
        }
        Location market = plugin.anchor("장터");
        if (market == null || market.getWorld() == null) {
            return;   // 미조성 — 마을이 없으면 사람도 없다
        }
        World world = market.getWorld();
        segment = segmentAt(world.getTime());

        List<Player> players = new ArrayList<>(world.getPlayers());
        prune(players);
        if (!players.isEmpty()) {
            populate(world, players);
        }
        walk(world);
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
     * 우클릭 — 행인은 소문을 옮긴다. 거래 GUI 는 열리지 않는다 (행인은 상인이 아니다).
     * TradeListener 는 "명패 + 무적"만 다루므로 이 핸들러와 겹치지 않는다.
     */
    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || !(event.getRightClicked() instanceof Villager villager)) {
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
        player.sendMessage(ChatColor.GRAY + label(person) + ChatColor.WHITE + " " + say(person));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_AMBIENT, 0.6f, 1.1f);
    }

    private String label(Person person) {
        return nameFormat.replace("{name}", person.name()).replace("{job}", person.job());
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
