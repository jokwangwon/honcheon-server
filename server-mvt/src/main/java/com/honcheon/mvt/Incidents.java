package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 사건(事件) — 무명의 죽음과 그 이후, 그리고 무명이 내는 의뢰.
 *
 * <p><b>왜 이 클래스가 있는가.</b> {@link Populace} 는 사람을 세우고 걷게 한다. 그 사람이
 * <i>죽었을 때 세계가 무엇을 하는가</i>는 다른 층의 일이다. 살인은 공짜여서도 안 되고 불가능해서도
 * 안 된다 — 대가는 확률이 아니라 <b>선택의 결과</b>다. 밤에 아무도 없는 골목에서 죽이고 시신을
 * 치우면 소문은 나지 않는다. 그러나 사람은 없어지고, 어미가 사람을 부른다.
 *
 * <p><b>등록제.</b> 강도·정확도·단계·현상금·favor 는 전부 {@code config/npc_death.yml}
 * {@code populace_layer} 가 정한다. 반경·초·틱 같은 기계값은 {@code config/npcs/populace.yml}
 * {@code incidents}. 의뢰는 목록이 아니라 규칙이다 — {@code populace.yml quests.rules} 의
 * {@code when} 이 참이 되는 순간 발주자가 관계(relations)에서 정해진다.
 *
 * <p><b>층의 경계.</b> MVT 는 봇 DB(SQLite)에 쓰지 않는다. 벌어진 일은 {@link WorldBridge}(이 저장소의
 * 유일한 다리 — config/world_bridge.yml)로 흘려보낸다. 그리고 <b>사실만</b> 보낸다:
 * 누가·어디서·목격 몇 명·밤인가·시신은 어떻게 됐나·사인은. 소문 강도·정확도·세력 점수·지역 델타 같은
 * 파생값은 봇이 npc_death.yml 로 계산한다 — 파생값을 둘이 계산하면 세계가 둘이 된다.
 * MVT 안의 수치(아래 region·bounty·favor)는 인게임 즉시 피드백을 위한 <b>거울</b>이지 장부가 아니다.
 *
 * <p>배선(HoncheonMvt):
 * <pre>
 *   Incidents.init(cfg);                                   // Populace.init(cfg) 뒤
 *   this.incidents = new Incidents(this);                  // populace 생성 뒤 (생성자가 스스로 접합한다)
 *   getServer().getPluginManager().registerEvents(incidents, this);
 *   incidents.start();
 * </pre>
 */
public final class Incidents implements Listener {

    // ══════════════ 등록부 ══════════════

    /** npc_death.yml populace_layer.stages 의 한 칸 */
    private record Stage(String name, int intensity, int accuracy, String official,
                         Map<String, Integer> region, boolean pursuit, boolean curfew,
                         int bountyMult, List<String> quests) {
    }

    /** npc_death.yml populace_layer.faction_response 의 한 줄 */
    private record FactionRule(String faction, String input, int delta, String fromStage,
                               int threshold, String contactLine) {
    }

    /** populace.yml quests.rules 의 한 줄 — 조건(when) → 의뢰 */
    private record Rule(String id, Map<String, Object> when, List<String> issuerOrder, String issuerId,
                        String grade, String rewardKey, int money, String objective, String altObjective,
                        String place, int delayDays, String offer, String accept, String decline,
                        Map<String, Object> success, Map<String, Object> failBody,
                        Map<String, Object> onExpire, Map<String, Object> irony) {
    }

    private static final Map<String, Stage> STAGES = new LinkedHashMap<>();
    private static final List<String> STAGE_ORDER = List.of("은밀", "흔적", "지목", "공개");
    private static final List<FactionRule> FACTIONS = new ArrayList<>();
    private static final Map<String, Rule> RULES = new LinkedHashMap<>();
    /** person → (관계 유형 → 상대 id). populace.yml relations 를 양방향 색인한 것 */
    private static final Map<String, Map<String, String>> KIN = new LinkedHashMap<>();

    // 기계값 (populace.yml incidents)
    private static int tickerPeriod = 20;
    private static int concealSeconds = 6;
    private static double concealRadius = 3;
    private static double concealInterrupt = 12;
    private static double discoveryRadius = 7;
    private static int autoDiscoverDays = 3;
    private static int corpseDespawnDays = 7;
    private static double witnessRadius = 16;
    private static int witnessPublicMin = 2;
    private static double nightBlind = 0.5;
    private static int curfewDays = 5;
    private static int respawnDays = 3;
    private static int bountyBase = 5000;
    private static String pursuitName = "포교";
    private static EntityType pursuitType = EntityType.VINDICATOR;
    private static Material pursuitWeapon = Material.IRON_SWORD;
    private static final Map<String, Integer> PURSUIT_COUNT = new LinkedHashMap<>();
    private static final Map<String, Integer> PURSUIT_DELAY = new LinkedHashMap<>();
    private static int pursuitGiveUpDays = 3;
    private static String pursuitSpawnPlace = "관아_앞";
    private static String witnessFleeTo = "관아_앞";
    private static double witnessSpeed = 1.3;
    private static int questExpireDays = 7;
    private static int questsPerPlayer = 3;
    private static int regionLow = 30;
    private static String regionLowRumor = "도적_소문";

    // 길잃음 (populace.yml strays)
    private static boolean straysOn = true;
    private static String strayVoice = "아이";
    private static double strayChance = 0.34;
    private static List<String> strayPlaces = List.of();

    // 문장 (전부 config — 코드가 대사를 지어내지 않는다)
    private static final Map<String, String> LINE = new LinkedHashMap<>();

    private static boolean bridgeOn = true;

    /** 등록부 판독 — populace.yml(기계값·의뢰·관계) + npc_death.yml(규칙 수치) */
    public static void init(Path configDir) {
        STAGES.clear();
        FACTIONS.clear();
        RULES.clear();
        KIN.clear();
        LINE.clear();

        Map<String, Object> pop = tryLoad(configDir.resolve("npcs/populace.yml"));
        Map<String, Object> death = tryLoad(configDir.resolve("npc_death.yml"));

        // ─ npc_death.yml populace_layer — 규칙(강도·정확도·단계·favor·현상금 배율) ─
        Map<String, Object> layer = map(death.get("populace_layer"));
        Map<String, Object> vars = map(layer.get("variables"));
        Map<String, Object> witness = map(vars.get("witness"));
        witnessRadius = num(witness.get("radius"), (int) witnessRadius);
        witnessPublicMin = num(witness.get("public_min"), witnessPublicMin);
        nightBlind = dbl(witness.get("night_blind"), nightBlind);

        for (Map.Entry<String, Object> e : map(layer.get("stages")).entrySet()) {
            Map<String, Object> s = map(e.getValue());
            Map<String, Object> rumor = map(s.get("rumor"));
            Map<String, Integer> region = new LinkedHashMap<>();
            map(s.get("region_delta")).forEach((k, v) -> region.put(k, num(v, 0)));
            List<String> quests = new ArrayList<>();
            for (Object q : list(s.get("quests"))) {
                quests.add(String.valueOf(q));
            }
            STAGES.put(e.getKey(), new Stage(e.getKey(), num(rumor.get("intensity"), 0),
                    num(rumor.get("accuracy"), 50), str(s.get("official")), region,
                    Boolean.TRUE.equals(s.get("pursuit")), Boolean.TRUE.equals(s.get("curfew")),
                    num(s.get("bounty_mult"), 1), quests));
        }
        for (Map.Entry<String, Object> e : map(layer.get("faction_response")).entrySet()) {
            Map<String, Object> f = map(e.getValue());
            int delta = f.containsKey("favor_per_kill")
                    ? num(f.get("favor_per_kill"), 0) : num(f.get("per_kill"), 0);
            FACTIONS.add(new FactionRule(e.getKey(), str(f.get("input")), delta,
                    str(f.get("from_stage")), num(f.get("threshold"), 0), str(f.get("contact_line"))));
        }
        curfewDays = num(map(layer.get("curfew")).get("days"), curfewDays);
        respawnDays = num(map(layer.get("respawn")).get("days"), respawnDays);

        // ─ populace.yml incidents — 기계값 ─
        Map<String, Object> inc = map(pop.get("incidents"));
        tickerPeriod = num(inc.get("ticker_period_ticks"), tickerPeriod);
        Map<String, Object> conceal = map(inc.get("conceal"));
        concealSeconds = num(conceal.get("seconds"), concealSeconds);
        concealRadius = num(conceal.get("radius"), (int) concealRadius);
        concealInterrupt = num(conceal.get("interrupt_radius"), (int) concealInterrupt);
        LINE.put("conceal.progress", str(conceal.get("line_progress")));
        LINE.put("conceal.done", str(conceal.get("line_done")));
        LINE.put("conceal.fail", str(conceal.get("line_fail")));

        Map<String, Object> corpse = map(inc.get("corpse"));
        discoveryRadius = num(corpse.get("discovery_radius"), (int) discoveryRadius);
        autoDiscoverDays = num(corpse.get("auto_discover_days"), autoDiscoverDays);
        corpseDespawnDays = num(corpse.get("despawn_days"), corpseDespawnDays);
        LINE.put("corpse.name", str(corpse.get("name_format")));
        LINE.put("corpse.found", str(corpse.get("line_found")));

        Map<String, Object> wit = map(inc.get("witness"));
        witnessFleeTo = strOr(wit.get("flee_to"), witnessFleeTo);
        witnessSpeed = dbl(wit.get("speed"), witnessSpeed);
        LINE.put("witness.shout", str(wit.get("shout")));

        Map<String, Object> pursuit = map(inc.get("pursuit"));
        pursuitName = strOr(pursuit.get("name"), pursuitName);
        pursuitType = enumOr(EntityType.class, str(pursuit.get("entity")), pursuitType);
        pursuitWeapon = enumOr(Material.class, str(pursuit.get("weapon")), pursuitWeapon);
        PURSUIT_COUNT.clear();
        map(pursuit.get("count_by_stage")).forEach((k, v) -> PURSUIT_COUNT.put(k, num(v, 1)));
        PURSUIT_DELAY.clear();
        map(pursuit.get("spawn_delay_seconds")).forEach((k, v) -> PURSUIT_DELAY.put(k, num(v, 10)));
        pursuitGiveUpDays = num(pursuit.get("give_up_days"), pursuitGiveUpDays);
        pursuitSpawnPlace = strOr(pursuit.get("spawn_at"), pursuitSpawnPlace);
        LINE.put("pursuit.spawn", str(pursuit.get("line_spawn")));
        LINE.put("pursuit.reach", str(pursuit.get("line_reach")));

        Map<String, Object> bounty = map(inc.get("bounty"));
        bountyBase = num(bounty.get("base"), bountyBase);
        LINE.put("bounty.line", str(bounty.get("line")));

        Map<String, Object> reg = map(inc.get("region"));
        regionLow = num(reg.get("low"), regionLow);
        regionLowRumor = strOr(reg.get("low_rumor"), regionLowRumor);

        // ─ populace.yml relations — 관계가 없으면 의뢰가 자라지 않는다 ─
        for (Object o : list(pop.get("relations"))) {
            Map<String, Object> r = map(o);
            String person = str(r.get("person"));
            if (person == null) {
                continue;
            }
            KIN.computeIfAbsent(person, k -> new LinkedHashMap<>())
                    .put(String.valueOf(r.get("type")), String.valueOf(r.get("kin")));
        }

        // ─ populace.yml quests — 조건 → 의뢰 ─
        Map<String, Object> quests = map(pop.get("quests"));
        questExpireDays = num(quests.get("offer_expire_days"), questExpireDays);
        questsPerPlayer = num(quests.get("max_active_per_player"), questsPerPlayer);
        for (Map.Entry<String, Object> e : map(quests.get("rules")).entrySet()) {
            Map<String, Object> q = map(e.getValue());
            List<String> order = new ArrayList<>();
            for (Object t : list(q.get("issuer_order"))) {
                order.add(String.valueOf(t));
            }
            RULES.put(e.getKey(), new Rule(e.getKey(), map(q.get("when")), order, str(q.get("issuer_id")),
                    strOr(q.get("grade"), "잔심부름"), strOr(q.get("reward_key"), "잔심부름"),
                    num(q.get("money"), 100), strOr(q.get("objective"), "귀환"), str(q.get("alt_objective")),
                    str(q.get("place")), num(q.get("delay_days"), 0),
                    clean(q.get("offer")), clean(q.get("accept")), clean(q.get("decline")),
                    map(q.get("success")), map(q.get("fail_body")), map(q.get("on_expire")),
                    map(q.get("killer_irony"))));
        }

        // ─ populace.yml strays — 죽이지 않아도 아이는 사라진다 ─
        Map<String, Object> strays = map(pop.get("strays"));
        straysOn = !Boolean.FALSE.equals(strays.get("enabled")) && !strays.isEmpty();
        strayVoice = strOr(strays.get("voice"), strayVoice);
        strayChance = dbl(strays.get("chance_per_night"), strayChance);
        List<String> places = new ArrayList<>();
        for (Object p : list(strays.get("lost_places"))) {
            places.add(String.valueOf(p));
        }
        strayPlaces = places;
        LINE.put("stray.lost", str(strays.get("line_lost")));
        LINE.put("stray.found", str(strays.get("line_found")));
        LINE.put("stray.follow", str(strays.get("line_follow")));
        LINE.put("stray.radius", String.valueOf(num(strays.get("return_radius"), 6)));

        Map<String, Object> bridge = map(pop.get("bridge"));
        bridgeOn = !Boolean.FALSE.equals(bridge.get("enabled"));
    }

    // ══════════════ 런타임 상태 ══════════════

    /** 시신 — 발견될 때까지는 아무 일도 없다. 발견되는 순간 세계가 움직인다 */
    private static final class Corpse {
        final String victim;
        final Location at;
        final UUID killer;
        final int deathDay;
        UUID marker;
        int witnesses;
        boolean concealed;
        boolean discovered;
        boolean staged;           // 단계가 한 번이라도 집행됐는가 (강등 금지)
        boolean night;            // 밤에 죽었는가 — 목격·은닉의 배경 (다리가 그대로 싣는다)
        int discoverDay = -1;
        String stage = "은밀";

        Corpse(String victim, Location at, UUID killer, int deathDay) {
            this.victim = victim;
            this.at = at;
            this.killer = killer;
            this.deathDay = deathDay;
        }
    }

    /** 굴러가는 의뢰 한 건 */
    private static final class Task {
        final Rule rule;
        final String issuer;
        final String victim;      // null 가능 (소문·통행금지에서 자란 의뢰)
        final int bornDay;
        UUID player;              // 수주자 (null = 아직 제안 전)
        boolean offered;
        boolean done;             // 목표 달성
        boolean closed;
        long offeredAt;

        Task(Rule rule, String issuer, String victim, int bornDay) {
            this.rule = rule;
            this.issuer = issuer;
            this.victim = victim;
            this.bornDay = bornDay;
        }

        String key() {
            return rule.id() + ":" + (victim == null ? issuer : victim);
        }
    }

    private final HoncheonMvt plugin;
    private final NamespacedKey keyCorpse;
    private final NamespacedKey keyPursuer;

    private final Map<String, Corpse> corpses = new LinkedHashMap<>();
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final Map<UUID, Integer> bounty = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Integer>> favor = new LinkedHashMap<>();
    private final Map<UUID, Integer> kills = new LinkedHashMap<>();
    private final Map<String, String> mood = new LinkedHashMap<>();       // person → 애도
    private final Map<String, Integer> respawnAt = new LinkedHashMap<>(); // person → 부활 세계일
    private final Set<String> strayed = new LinkedHashSet<>();            // 길 잃은 아이
    private final Map<UUID, Long> concealStart = new LinkedHashMap<>();   // 은닉 중인 플레이어
    private final Map<UUID, String> concealTarget = new LinkedHashMap<>();
    private final Map<UUID, Integer> pursuitAt = new LinkedHashMap<>();   // 수배자 → 포교 출동 세계일
    private final Map<UUID, Integer> wantedSince = new LinkedHashMap<>();
    private final Map<String, Integer> region = new LinkedHashMap<>();
    private final Map<UUID, Long> confirm = new LinkedHashMap<>();        // 재우클릭 확인 창
    private int curfewUntil = -1;
    private int lastNightDay = -1;

    public Incidents(HoncheonMvt plugin) {
        this.plugin = plugin;
        this.keyCorpse = new NamespacedKey(plugin, "corpse");
        this.keyPursuer = new NamespacedKey(plugin, "pursuer");
        region.put("치안", 50);
        region.put("경제", 50);
        region.put("민심", 50);
        if (plugin.populace() != null) {
            plugin.populace().bind(this);   // 스스로 접합한다 — HoncheonMvt 는 네 줄만 알면 된다
        }
    }

    public void start() {
        cleanupMarkers();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 140L, tickerPeriod);
        plugin.getLogger().info("사건 엔진 기동 — 단계 " + STAGES.size() + " · 의뢰 규칙 " + RULES.size()
                + " · 관계 " + KIN.size() + "인 · 세력 반응 " + FACTIONS.size());
    }

    // ══════════════ 죽음 ══════════════

    /**
     * 무명이 죽었다. Populace.onDeath 가 부른다.
     * 여기서 세 변수를 실제로 잰다 — 목격(반경 안의 산 사람)·시신(치웠는가)·증거(얼굴을 봤는가).
     */
    void onDeath(String id, LivingEntity body, Player killer) {
        Populace people = plugin.populace();
        Location at = body.getLocation().clone();
        int day = day(at.getWorld());

        boolean night = "밤".equals(people.segment()) || "새벽".equals(people.segment());
        List<String> saw = new ArrayList<>();
        for (String other : people.aliveNear(at, witnessRadius)) {
            if (other.equals(id)) {
                continue;
            }
            if (night && ThreadLocalRandom.current().nextDouble() >= nightBlind) {
                continue;   // 어두우면 덜 본다 (npc_death.populace_layer.variables.witness.night_blind)
            }
            saw.add(other);
        }
        for (Player p : at.getWorld().getPlayers()) {
            if (killer != null && p.equals(killer)) {
                continue;
            }
            if (p.getLocation().distance(at) <= witnessRadius) {
                saw.add("player:" + p.getName());
            }
        }

        Corpse corpse = new Corpse(id, at, killer == null ? null : killer.getUniqueId(), day);
        corpse.witnesses = saw.size();
        corpse.night = night;
        corpses.put(id, corpse);
        people.markDead(id);
        respawnAt.put(id, day + respawnDays);
        strayed.remove(id);

        spawnCorpseMarker(corpse, people.nameOf(id));

        // 목격자는 관아로 달린다 — 걸어서 (텔레포트 없음)
        for (String w : saw) {
            if (w.startsWith("player:")) {
                continue;
            }
            Mob mob = people.bodyOf(w);
            Location office = people.placeCenter(witnessFleeTo);
            if (mob != null && office != null) {
                mob.getPathfinder().moveTo(office, witnessSpeed);
                people.pin(w, witnessFleeTo);
                shout(mob.getLocation(), people.nameOf(w), LINE.get("witness.shout"));
            }
        }

        if (corpse.witnesses >= witnessPublicMin) {
            corpse.discovered = true;
            corpse.discoverDay = day;
            applyStage(corpse, "공개", killer);
        } else if (corpse.witnesses >= 1) {
            corpse.discovered = true;
            corpse.discoverDay = day;
            applyStage(corpse, "지목", killer);
        } else if (killer != null) {
            killer.sendMessage(ChatColor.DARK_GRAY + "아무도 보지 않았다. "
                    + ChatColor.GRAY + "시신 위에서 웅크리면 치울 수 있다 (" + concealSeconds + "초).");
        }
        emit("무명_사망", Map.of("victim", id, "witness", corpse.witnesses, "night", night,
                "killer", killer == null ? "" : killer.getName(), "day", day));
        if (corpse.discovered) {
            bridgeDeath(corpse, "즉시_발견", night, killer);   // 본 사람이 있다 — 시신은 이미 발견된 것이다
        }
        // 아무도 못 봤다면 다리는 아직 잠잠하다. 시신의 처지가 정해질 때(은닉/발견) 그때 실린다 —
        // 완전 범죄는 없다. 느린 발각만 있다.
    }

    private void spawnCorpseMarker(Corpse corpse, String name) {
        World world = corpse.at.getWorld();
        if (world == null) {
            return;
        }
        ArmorStand stand = world.spawn(corpse.at.clone().add(0, -0.4, 0), ArmorStand.class, s -> {
            s.setCustomName(ChatColor.DARK_RED + fmt(LINE.get("corpse.name"), Map.of("name", name)));
            s.setCustomNameVisible(true);
            s.setInvulnerable(true);
            s.setGravity(false);
            s.setBasePlate(false);
            s.setArms(true);
            s.setPersistent(false);
            s.getPersistentDataContainer().set(keyCorpse, PersistentDataType.STRING, corpse.victim);
        });
        corpse.marker = stand.getUniqueId();
    }

    /** 단계 집행 — 소문·관·현상금·지역 델타·세력·통행금지·의뢰. 전부 등록부가 정한다 */
    private void applyStage(Corpse corpse, String name, Player killer) {
        Stage stage = STAGES.get(name);
        if (stage == null) {
            return;
        }
        if (corpse.staged && STAGE_ORDER.indexOf(name) <= STAGE_ORDER.indexOf(corpse.stage)) {
            return;   // 세계는 한 사건을 두 번 정산하지 않는다 (강등도 없다 — 드러난 것은 덮이지 않는다)
        }
        corpse.stage = name;
        corpse.staged = true;
        Populace people = plugin.populace();
        String victim = people.nameOf(corpse.victim);
        int day = day(corpse.at.getWorld());

        stage.region().forEach((k, v) -> region.merge(k, v, Integer::sum));
        if (!stage.region().isEmpty()) {
            emit("지역_델타", new LinkedHashMap<>(stage.region()));
        }

        if (stage.intensity() > 0) {
            broadcast(ChatColor.DARK_RED + "[소문 강도 " + stage.intensity() + "] " + ChatColor.GRAY
                    + rumorText(name, victim, people.jobOf(corpse.victim), killer));
            people.rumor("살인_소문", true);
            emit("소문_발화", Map.of("subject", "무명_살해", "victim", corpse.victim,
                    "intensity", stage.intensity(), "accuracy", stage.accuracy(),
                    "network", "mingan_market", "day", day));
        }
        if (stage.curfew()) {
            curfewUntil = Math.max(curfewUntil, day + curfewDays);
            people.curfew(true);
            broadcast(ChatColor.DARK_GRAY + "해가 지면 아무도 나오지 않는다. 청하현에 통행금지가 내렸다.");
        }
        if (killer != null && stage.pursuit()) {
            int amount = bountyBase * Math.max(1, stage.bountyMult());
            bounty.merge(killer.getUniqueId(), amount, Integer::sum);
            wantedSince.putIfAbsent(killer.getUniqueId(), day);
            pursuitAt.put(killer.getUniqueId(), day);
            killer.sendMessage(ChatColor.RED + fmt(LINE.get("bounty.line"),
                    Map.of("amount", String.valueOf(bounty.get(killer.getUniqueId())))));
            killer.sendMessage(ChatColor.GRAY + "관졸에게 웅크린 채 말을 걸면 "
                    + ChatColor.WHITE + "자수" + ChatColor.GRAY + "할 수 있다 (수배 소멸 · 민심 +2).");
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> summonPursuit(killer, name), 20L * PURSUIT_DELAY.getOrDefault(name, 15));
            emit("수배", Map.of("killer", killer.getName(), "bounty", bounty.get(killer.getUniqueId()),
                    "stage", name, "day", day));
        }
        if (killer != null) {
            factions(killer, name);
        }
    }

    private String rumorText(String stage, String victim, String job, Player killer) {
        return switch (stage) {
            case "공개" -> victim + "이(가) 백주에 죽었다. 사람들이 " + (killer == null ? "그 무인" : killer.getName())
                    + "의 얼굴을 보았다.";
            case "지목" -> victim + "이(가) 죽었다. 누군가 보았다고 한다.";
            default -> "골목에서 " + job + " 하나가 죽어 있었다. 누가 그랬는지는 아무도 모른다.";
        };
    }

    /** 세력 반응 — 무명 하나로는 움직이지 않는다. 쌓여야 움직인다 (혈교는 예외다: 그들은 첫 피를 안다) */
    private void factions(Player killer, String stage) {
        int done = kills.merge(killer.getUniqueId(), 1, Integer::sum);
        int rank = STAGE_ORDER.indexOf(stage);
        for (FactionRule f : FACTIONS) {
            if (rank < STAGE_ORDER.indexOf(f.fromStage())) {
                continue;
            }
            Map<String, Integer> mine = favor.computeIfAbsent(killer.getUniqueId(), k -> new LinkedHashMap<>());
            int now = mine.merge(f.faction(), f.delta(), Integer::sum);
            emit("세력_반응", Map.of("faction", f.faction(), "input", f.input() == null ? "" : f.input(),
                    "delta", f.delta(), "total", now, "killer", killer.getName()));
            if (f.threshold() > 0 && done == f.threshold() && f.contactLine() != null) {
                killer.sendMessage(ChatColor.DARK_PURPLE + "[" + f.faction() + "] "
                        + ChatColor.LIGHT_PURPLE + f.contactLine());
            }
        }
        killer.sendMessage(ChatColor.DARK_GRAY + "(무명 " + done + "인 — 혈교 favor "
                + favor.getOrDefault(killer.getUniqueId(), Map.of()).getOrDefault("혈교", 0)
                + " · 민심 " + region.get("민심") + ")");
    }

    // ══════════════ 티커 ══════════════

    private void tick() {
        Populace people = plugin.populace();
        Location market = plugin.anchor("장터");
        if (market == null || market.getWorld() == null) {
            return;
        }
        World world = market.getWorld();
        int day = day(world);

        conceal(world);
        discover(world, day);
        pursue(world, day);
        strays(world, day);
        respawn(day);
        if (curfewUntil >= 0 && day > curfewUntil) {
            curfewUntil = -1;
            people.curfew(false);
            people.rumor("살인_소문", false);
            broadcast(ChatColor.GRAY + "장이 다시 선다. 사람들이 밤에도 문을 연다.");
        }
        // 치안이 무너지면 산길 소문이 돈다 — region_state.yml threshold_effects.치안_저하.
        // 살인이 쌓이면 치안이 무너지고, 치안이 무너지면 의뢰가 저절로 자란다 (quest_generation.sources.region_state)
        people.rumor(regionLowRumor, region.get("치안") < regionLow);

        grow(world, day);
        objectives(world);
        expire(day);
    }

    /** 은닉 — 시신 위에서 웅크린 채 버틴다. 누가 오면 손이 멈춘다 (그리고 그가 목격자가 된다) */
    private void conceal(World world) {
        for (UUID id : new ArrayList<>(concealStart.keySet())) {
            Player player = plugin.getServer().getPlayer(id);
            String victim = concealTarget.get(id);
            Corpse corpse = victim == null ? null : corpses.get(victim);
            if (player == null || corpse == null || corpse.discovered || corpse.concealed) {
                concealStart.remove(id);
                concealTarget.remove(id);
                continue;
            }
            if (!player.isSneaking() || player.getLocation().distance(corpse.at) > concealRadius) {
                concealStart.remove(id);
                concealTarget.remove(id);
                player.sendActionBar(ChatColor.GRAY + "손을 뗐다.");
                continue;
            }
            // 방해 — 반경 안에 산 사람이 들어오면 중단 + 목격 +1
            List<String> near = plugin.populace().aliveNear(corpse.at, concealInterrupt);
            if (!near.isEmpty()) {
                concealStart.remove(id);
                concealTarget.remove(id);
                corpse.witnesses++;
                corpse.discovered = true;
                corpse.discoverDay = day(world);
                player.sendMessage(ChatColor.RED + LINE.get("conceal.fail"));
                applyStage(corpse, "지목", player);
                bridgeDeath(corpse, "즉시_발견", corpse.night, player);
                continue;
            }
            long elapsed = (System.currentTimeMillis() - concealStart.get(id)) / 1000L;
            if (elapsed >= concealSeconds) {
                corpse.concealed = true;
                concealStart.remove(id);
                concealTarget.remove(id);
                removeMarker(corpse);
                player.sendMessage(ChatColor.DARK_GRAY + LINE.get("conceal.done"));
                applyStage(corpse, "은밀", player);
                bridgeDeath(corpse, "은닉", corpse.night, player);
                emit("시신_은닉", Map.of("victim", corpse.victim, "killer", player.getName()));
            } else {
                int pct = (int) (elapsed * 100 / Math.max(1, concealSeconds));
                player.sendActionBar(ChatColor.DARK_RED
                        + fmt(LINE.get("conceal.progress"), Map.of("pct", String.valueOf(pct))));
            }
        }
    }

    /** 발견 — 산 사람이 시신 곁을 지나거나, 아무도 못 봐도 관이 결국 찾는다 */
    private void discover(World world, int day) {
        for (Corpse corpse : new ArrayList<>(corpses.values())) {
            if (corpse.concealed || corpse.discovered) {
                if (corpse.discovered && corpse.discoverDay >= 0 && day - corpse.discoverDay >= corpseDespawnDays) {
                    removeMarker(corpse);
                }
                continue;
            }
            String finder = null;
            List<String> near = plugin.populace().aliveNear(corpse.at, discoveryRadius);
            if (!near.isEmpty()) {
                finder = plugin.populace().nameOf(near.get(0));
            } else {
                for (Player p : world.getPlayers()) {
                    if (p.getUniqueId().equals(corpse.killer)) {
                        continue;   // 살해자가 제 시신을 '발견'하지는 못한다
                    }
                    if (p.getLocation().distance(corpse.at) <= discoveryRadius) {
                        finder = p.getName();
                        break;
                    }
                }
            }
            if (finder == null && day - corpse.deathDay >= autoDiscoverDays) {
                finder = "관졸";
            }
            if (finder == null) {
                continue;
            }
            corpse.discovered = true;
            corpse.discoverDay = day;
            Player killer = corpse.killer == null ? null : plugin.getServer().getPlayer(corpse.killer);
            broadcast(ChatColor.DARK_RED + fmt(LINE.get("corpse.found"),
                    Map.of("finder", finder, "name", plugin.populace().nameOf(corpse.victim))));
            emit("시신_발견", Map.of("victim", corpse.victim, "finder", finder, "day", day));
            bridgeDeath(corpse, "유기_지연발견", corpse.night, killer);
            applyStage(corpse, "흔적", killer);
        }
    }

    /** 포교(捕校) — 관의 손. 사흘 쫓고 물러난다 (관은 조직이지 원한이 아니다) */
    private void pursue(World world, int day) {
        for (Entity e : world.getEntities()) {
            if (!e.getPersistentDataContainer().has(keyPursuer, PersistentDataType.STRING)) {
                continue;
            }
            String owner = e.getPersistentDataContainer().get(keyPursuer, PersistentDataType.STRING);
            Player target = owner == null ? null : plugin.getServer().getPlayer(UUID.fromString(owner));
            if (target == null || !bounty.containsKey(target.getUniqueId())
                    || day - pursuitAt.getOrDefault(target.getUniqueId(), day) > pursuitGiveUpDays) {
                e.remove();
                continue;
            }
            if (e instanceof Mob mob) {
                mob.setTarget(target);
                if (mob.getLocation().distance(target.getLocation()) < 3.5
                        && ThreadLocalRandom.current().nextInt(6) == 0) {
                    target.sendMessage(ChatColor.RED + "[" + pursuitName + "] "
                            + ChatColor.WHITE + LINE.get("pursuit.reach"));
                }
            }
        }
    }

    private void summonPursuit(Player target, String stage) {
        if (!bounty.containsKey(target.getUniqueId())) {
            return;
        }
        Location at = plugin.populace().placeCenter(pursuitSpawnPlace);
        if (at == null || at.getWorld() == null) {
            at = target.getLocation().clone().add(12, 0, 12);
        }
        int count = PURSUIT_COUNT.getOrDefault(stage, 1);
        for (int i = 0; i < count; i++) {
            Entity spawned = at.getWorld().spawnEntity(at, pursuitType);
            if (!(spawned instanceof Mob mob)) {
                spawned.remove();
                continue;
            }
            mob.setCustomName(ChatColor.RED + pursuitName);
            mob.setCustomNameVisible(true);
            mob.setPersistent(false);
            mob.setRemoveWhenFarAway(false);
            mob.getPersistentDataContainer().set(keyPursuer, PersistentDataType.STRING,
                    target.getUniqueId().toString());
            if (mob.getEquipment() != null) {
                mob.getEquipment().setItemInMainHand(new ItemStack(pursuitWeapon));
                mob.getEquipment().setItemInMainHandDropChance(0f);
            }
            mob.setTarget(target);
        }
        target.sendMessage(ChatColor.RED + LINE.get("pursuit.spawn"));
        target.playSound(target.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.6f);
    }

    /** 길잃음 — 흉흉한 밤에 아이 하나가 집에 못 들어간다 */
    private void strays(World world, int day) {
        if (!straysOn || strayPlaces.isEmpty()) {
            return;
        }
        Populace people = plugin.populace();
        if (!"밤".equals(people.segment()) || day == lastNightDay) {
            return;
        }
        lastNightDay = day;
        boolean uneasy = curfewUntil >= day || !people.rumors().isEmpty();
        if (!uneasy || !strayed.isEmpty() || ThreadLocalRandom.current().nextDouble() >= strayChance) {
            return;
        }
        List<String> children = new ArrayList<>();
        for (String id : people.idsWithVoice(strayVoice)) {
            if (!people.isDead(id) && kinOf(id) != null) {
                children.add(id);   // 찾아 줄 사람이 있는 아이만 (관계가 없으면 의뢰가 자라지 않는다)
            }
        }
        if (children.isEmpty()) {
            return;
        }
        String child = children.get(ThreadLocalRandom.current().nextInt(children.size()));
        String place = strayPlaces.get(ThreadLocalRandom.current().nextInt(strayPlaces.size()));
        strayed.add(child);
        people.pin(child, place);
        broadcast(ChatColor.GRAY + LINE.get("stray.lost"));
        plugin.getLogger().info("[길잃음] " + people.nameOf(child) + " → " + place);
    }

    private void respawn(int day) {
        for (Map.Entry<String, Integer> e : new ArrayList<>(respawnAt.entrySet())) {
            if (day < e.getValue()) {
                continue;
            }
            // 유족의 의뢰가 아직 살아 있으면 자리를 메우지 않는다 (npc_death.populace_layer.respawn.exception)
            boolean pending = tasks.values().stream()
                    .anyMatch(t -> !t.closed && e.getKey().equals(t.victim));
            if (pending) {
                continue;
            }
            respawnAt.remove(e.getKey());
            corpses.remove(e.getKey());
            plugin.populace().revive(e.getKey());
        }
    }

    // ══════════════ 의뢰 — 세계의 상태가 의뢰를 낳는다 ══════════════

    /** 조건(when) 스캔 → 발주자 결정(관계) → 대기 중인 의뢰 생성 */
    private void grow(World world, int day) {
        Populace people = plugin.populace();
        for (Rule rule : RULES.values()) {
            Map<String, Object> when = rule.when();
            if (when.containsKey("rumor")) {
                if (people.rumors().contains(String.valueOf(when.get("rumor")))) {
                    open(rule, rule.issuerId(), null, day);
                }
                continue;
            }
            if (when.containsKey("curfew")) {
                if (curfewUntil >= day) {
                    open(rule, rule.issuerId(), null, day);
                }
                continue;
            }
            String state = str(when.get("subject_state"));
            String voice = str(when.get("subject_voice"));
            if (state == null) {
                continue;
            }
            for (String victim : subjects(state)) {
                if (voice != null && !voice.equals(people.voiceOf(victim))) {
                    continue;
                }
                if (day - deathOrLostDay(victim, day) < rule.delayDays()) {
                    continue;   // 하루는 기다린다 — "곧 오겠지요"
                }
                String issuer = rule.issuerId() != null ? rule.issuerId() : issuerFor(rule, victim);
                if (issuer == null || people.isDead(issuer)) {
                    continue;   // 아무도 그를 찾지 않는다 (civil_debt — 지역의 냉기만 남는다)
                }
                open(rule, issuer, victim, day);
            }
        }
    }

    /** 조건이 가리키는 사람들 — 실종(시신 미발견·길잃음) / 사망_확인(시신 발견) */
    private List<String> subjects(String state) {
        List<String> out = new ArrayList<>();
        if ("실종".equals(state)) {
            out.addAll(strayed);
            for (Corpse c : corpses.values()) {
                if (!c.discovered) {
                    out.add(c.victim);
                }
            }
        } else if ("사망_확인".equals(state)) {
            for (Corpse c : corpses.values()) {
                if (c.discovered) {
                    out.add(c.victim);
                }
            }
        }
        return out;
    }

    private int deathOrLostDay(String victim, int today) {
        Corpse c = corpses.get(victim);
        return c == null ? today - 1 : c.deathDay;   // 길잃음은 그날 밤 바로 (어미는 밤을 새운다)
    }

    /** 발주자 — 관계에서만 찾는다. 신규 인물 발명 금지 */
    private String issuerFor(Rule rule, String victim) {
        Map<String, String> kin = KIN.getOrDefault(victim, Map.of());
        for (String type : rule.issuerOrder()) {
            String who = kin.get(type);
            if (who != null && !plugin.populace().isDead(who) && plugin.populace().knows(who)) {
                return who;
            }
        }
        return null;
    }

    private String kinOf(String person) {
        Map<String, String> kin = KIN.get(person);
        return kin == null || kin.isEmpty() ? null : kin.values().iterator().next();
    }

    private void open(Rule rule, String issuer, String victim, int day) {
        if (issuer == null) {
            return;
        }
        Task task = new Task(rule, issuer, victim, day);
        if (tasks.containsKey(task.key())) {
            return;
        }
        tasks.put(task.key(), task);
        emit("의뢰_발생", Map.of("rule", rule.id(), "issuer", issuer,
                "victim", victim == null ? "" : victim, "day", day));
        plugin.getLogger().info("[무명 의뢰] " + rule.id() + " — 발주 "
                + plugin.populace().nameOf(issuer)
                + (victim == null ? "" : " (대상 " + plugin.populace().nameOf(victim) + ")"));
    }

    /** 목표 판정 — 시신_확인 / 장소_방문 / 생존_귀환 */
    private void objectives(World world) {
        for (Task task : tasks.values()) {
            if (task.closed || task.done || task.player == null) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(task.player);
            if (player == null) {
                continue;
            }
            if (task.victim != null && strayed.contains(task.victim)) {
                // 생존_귀환 — 아이를 발주자 곁으로 데려온다
                Mob child = plugin.populace().bodyOf(task.victim);
                Mob mom = plugin.populace().bodyOf(task.issuer);
                double back = Double.parseDouble(LINE.getOrDefault("stray.radius", "6"));
                if (child != null && mom != null
                        && child.getLocation().distance(mom.getLocation()) <= back) {
                    task.done = true;
                    player.sendMessage(ChatColor.GREEN + "아이를 데려왔다. " + ChatColor.GRAY
                            + plugin.populace().nameOf(task.issuer) + "에게 말을 걸어라.");
                }
                continue;
            }
            Location target = objectiveLocation(task);
            if (target == null || target.getWorld() != player.getWorld()) {
                continue;
            }
            if (player.getLocation().distance(target) <= discoveryRadius) {
                task.done = true;
                player.sendMessage(ChatColor.YELLOW + "[의뢰] " + ChatColor.WHITE
                        + ("시신_확인".equals(task.rule.objective()) ? "찾았다. 여기 누워 있다."
                        : "둘러보았다.") + ChatColor.GRAY + " — "
                        + plugin.populace().nameOf(task.issuer) + "에게 돌아가라.");
            }
        }
    }

    private Location objectiveLocation(Task task) {
        if ("장소_방문".equals(task.rule.objective())) {
            return plugin.populace().placeCenter(task.rule.place());
        }
        Corpse corpse = task.victim == null ? null : corpses.get(task.victim);
        return corpse == null ? null : corpse.at;
    }

    private void expire(int day) {
        for (Task task : tasks.values()) {
            if (task.closed || day - task.bornDay < questExpireDays) {
                continue;
            }
            task.closed = true;
            settle(task, task.rule.onExpire(), null, 0);
        }
    }

    // ══════════════ 말 걸기 — Populace 가 넘긴다 ══════════════

    /**
     * 무명 우클릭. 사건층이 먼저 본다 — 의뢰가 걸려 있으면 그것부터 말한다.
     * 반환 true = 사건층이 처리했다 (Populace 는 잡담을 출력하지 않는다).
     */
    boolean onTalk(Player player, String id, boolean sneaking) {
        Populace people = plugin.populace();

        // ① 길 잃은 아이를 찾았다 — 아이가 따라온다
        if (strayed.contains(id)) {
            Mob child = people.bodyOf(id);
            if (child != null) {
                people.follow(id, player.getUniqueId());
                player.sendMessage(ChatColor.GRAY + "[" + people.nameOf(id) + "] " + ChatColor.WHITE
                        + fmt(LINE.get("stray.found"), Map.of("name", people.nameOf(id))));
                player.sendMessage(ChatColor.DARK_GRAY
                        + fmt(LINE.get("stray.follow"), Map.of("name", people.nameOf(id))));
            }
            return true;
        }

        // ② 자수 — 관졸에게 웅크린 채 말을 건다 (npc_death.responses.자수)
        if ("관졸".equals(people.voiceOf(id)) && sneaking && bounty.containsKey(player.getUniqueId())) {
            surrender(player, id);
            return true;
        }

        // ③ 의뢰 — 이 사람이 발주자인가
        Task task = tasks.values().stream()
                .filter(t -> !t.closed && t.issuer.equals(id))
                .findFirst().orElse(null);
        if (task == null) {
            return false;
        }
        String name = people.nameOf(id);
        Map<String, String> subs = Map.of("issuer", name, "victim",
                task.victim == null ? "그 아이" : people.nameOf(task.victim));

        if (task.player == null) {
            if (!task.offered) {
                task.offered = true;
                task.offeredAt = System.currentTimeMillis();
                boolean killer = task.victim != null && corpses.containsKey(task.victim)
                        && player.getUniqueId().equals(corpses.get(task.victim).killer);
                String suffix = killer ? str(task.rule.irony().get("offer_suffix")) : null;
                player.sendMessage(ChatColor.YELLOW + "[" + name + "] " + ChatColor.WHITE
                        + fmt(task.rule.offer(), subs) + (suffix == null ? "" : " " + suffix));
                player.sendMessage(ChatColor.GRAY + "— 한 번 더 말을 걸면 "
                        + ChatColor.GREEN + "수락" + ChatColor.GRAY + ", 웅크린 채 말을 걸면 "
                        + ChatColor.RED + "사양" + ChatColor.GRAY + "이다. (보수 "
                        + task.rule.money() + "문 · " + task.rule.grade() + ")");
                return true;
            }
            if (sneaking) {
                task.offered = false;
                player.sendMessage(ChatColor.GRAY + "[" + name + "] " + ChatColor.WHITE
                        + fmt(task.rule.decline(), subs));
                return true;
            }
            long active = tasks.values().stream()
                    .filter(t -> player.getUniqueId().equals(t.player) && !t.closed).count();
            if (active >= questsPerPlayer) {
                player.sendMessage(ChatColor.RED + "손이 모자란다 — 이미 " + active + "건을 맡았다.");
                return true;
            }
            task.player = player.getUniqueId();
            player.sendMessage(ChatColor.GREEN + "[" + name + "] " + ChatColor.WHITE
                    + fmt(task.rule.accept(), subs));
            String hint = "장소_방문".equals(task.rule.objective())
                    ? task.rule.place() + " 쪽으로 가라."
                    : (task.victim != null && strayed.contains(task.victim)
                    ? "아이를 찾아 데려와라." : "시신을 찾아라.");
            player.sendMessage(ChatColor.GRAY + "[의뢰 수락] " + hint);
            return true;
        }

        if (!player.getUniqueId().equals(task.player)) {
            return false;   // 남이 맡은 의뢰 — 잡담이나 하라
        }
        if (!task.done) {
            player.sendMessage(ChatColor.GRAY + "[" + name + "] " + ChatColor.WHITE + "…아직이오?");
            return true;
        }
        complete(task, player, subs);
        return true;
    }

    private void complete(Task task, Player player, Map<String, String> subs) {
        task.closed = true;
        Corpse corpse = task.victim == null ? null : corpses.get(task.victim);
        boolean alive = task.victim != null && strayed.contains(task.victim);
        boolean isKiller = corpse != null && player.getUniqueId().equals(corpse.killer);

        Map<String, Object> outcome;
        if (alive || corpse == null) {
            outcome = task.rule.success();          // 살아 돌아왔다 / 다녀왔다
        } else {
            outcome = task.rule.failBody().isEmpty() ? task.rule.success() : task.rule.failBody();
        }
        if (alive) {
            strayed.remove(task.victim);
            plugin.populace().unfollow(task.victim);
            plugin.populace().pin(task.victim, null);
        }
        settle(task, outcome, player, task.rule.money());

        if (isKiller && !task.rule.irony().isEmpty()) {
            player.sendMessage(ChatColor.DARK_PURPLE + clean(task.rule.irony().get("complete")));
            int blood = num(task.rule.irony().get("blood_favor"), 0);
            if (blood != 0) {
                favor.computeIfAbsent(player.getUniqueId(), k -> new LinkedHashMap<>())
                        .merge("혈교", blood, Integer::sum);
                emit("세력_반응", Map.of("faction", "혈교", "input", "무명_유족_기만",
                        "delta", blood, "killer", player.getName()));
            }
        }
    }

    /** 결말 — 보수·민심·소문·그 사람의 이후 대사. 실패도 서사다 */
    private void settle(Task task, Map<String, Object> outcome, Player player, int money) {
        if (outcome == null || outcome.isEmpty()) {
            return;
        }
        Populace people = plugin.populace();
        Map<String, String> subs = Map.of("issuer", people.nameOf(task.issuer),
                "victim", task.victim == null ? "그 아이" : people.nameOf(task.victim));
        String text = clean(outcome.get("text"));
        if (text != null) {
            broadcast(ChatColor.GRAY + fmt(text, subs));
        }
        map(outcome.get("region")).forEach((k, v) -> region.merge(k, num(v, 0), Integer::sum));
        if (!map(outcome.get("region")).isEmpty()) {
            Map<String, Object> delta = new LinkedHashMap<>(map(outcome.get("region")));
            emit("지역_델타", delta);
        }
        String newMood = str(outcome.get("mood"));
        if (newMood != null) {
            mood.put(task.issuer, newMood);   // 이 사람의 말이 영구히 바뀐다
        }
        String rumorTag = str(outcome.get("rumor"));
        if (rumorTag != null) {
            people.rumor(rumorTag, true);
        }
        if (player != null && money > 0) {
            double mult = dbl(outcome.get("money_mult"), 1.0);
            int paid = (int) Math.round(money * mult);
            plugin.ledger(player.getUniqueId()).earn(paid);
            player.sendMessage(ChatColor.GOLD + "보수 " + paid + "문 " + ChatColor.GRAY
                    + "(" + task.rule.rewardKey() + " · " + task.rule.grade() + ")");
        }
        emit(player == null ? "의뢰_만료" : "의뢰_완료", Map.of("rule", task.rule.id(),
                "issuer", task.issuer, "victim", task.victim == null ? "" : task.victim,
                "player", player == null ? "" : player.getName()));
    }

    /** 자수 — 수배는 소멸한다. 민심은 오른다. 그러나 죽은 사람은 돌아오지 않는다 */
    private void surrender(Player player, String guardId) {
        Integer amount = bounty.remove(player.getUniqueId());
        pursuitAt.remove(player.getUniqueId());
        wantedSince.remove(player.getUniqueId());
        region.merge("민심", 2, Integer::sum);
        int fine = Math.max(0, amount == null ? 0 : amount / 5);
        plugin.ledger(player.getUniqueId()).earn(-fine);
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getPersistentDataContainer().has(keyPursuer, PersistentDataType.STRING)) {
                    e.remove();
                }
            }
        }
        player.sendMessage(ChatColor.GRAY + "[" + plugin.populace().nameOf(guardId) + "] "
                + ChatColor.WHITE + "…따라오시오. 포두 나리께 고하겠소.");
        player.sendMessage(ChatColor.GREEN + "자수 — 수배 소멸 · 벌금 " + fine + "문 · 민심 +2. "
                + ChatColor.GRAY + "(관의 법과 유족의 원한은 다른 법정이다)");
        emit("자수", Map.of("player", player.getName(), "fine", fine,
                "authority_mandate", -10));
    }

    // ══════════════ 이벤트 ══════════════

    /** 시신 우클릭 — 웅크리면 치운다(은닉), 아니면 살펴본다(발견) */
    @EventHandler
    public void onCorpse(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        String victim = event.getRightClicked().getPersistentDataContainer()
                .get(keyCorpse, PersistentDataType.STRING);
        if (victim == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Corpse corpse = corpses.get(victim);
        if (corpse == null || corpse.concealed) {
            return;
        }
        if (player.isSneaking() && !corpse.discovered) {
            concealStart.putIfAbsent(player.getUniqueId(), System.currentTimeMillis());
            concealTarget.put(player.getUniqueId(), victim);
            player.sendMessage(ChatColor.DARK_GRAY + "웅크린 채 " + concealSeconds + "초를 버텨야 한다.");
            return;
        }
        player.sendMessage(ChatColor.GRAY + plugin.populace().nameOf(victim) + " — "
                + plugin.populace().jobOf(victim) + ". 목이 꺾여 있다. 죽은 지 "
                + (day(corpse.at.getWorld()) - corpse.deathDay) + "일.");
    }

    /** 포교는 마을 사람을 치지 않는다 — 관은 범인을 잡으러 온 것이지 마을을 부수러 온 것이 아니다 */
    @EventHandler
    public void onPursuerSwing(EntityDamageByEntityEvent event) {
        if (!event.getDamager().getPersistentDataContainer().has(keyPursuer, PersistentDataType.STRING)) {
            return;
        }
        if (!(event.getEntity() instanceof Player)) {
            event.setCancelled(true);
        }
    }

    // ══════════════ 조회 ══════════════

    /** 이 사람의 태세 — 애도(가족을 잃었다) / 공포(미해결 살인) / null */
    String moodOf(String id) {
        String m = mood.get(id);
        if (m != null) {
            return m;
        }
        return curfewUntil >= 0 ? "공포" : null;
    }

    /** 이 사람이 지금 의뢰를 걸고 있는가 (명패에 표를 단다) */
    boolean hasOffer(String id) {
        return tasks.values().stream().anyMatch(t -> !t.closed && t.issuer.equals(id));
    }

    public boolean curfew() {
        return curfewUntil >= 0;
    }

    /** /혼천 인구 — 사건 현황 */
    public List<String> status() {
        List<String> out = new ArrayList<>();
        out.add("§6── 사건 ──");
        out.add("§7치안 §f" + region.get("치안") + " §7· 경제 §f" + region.get("경제")
                + " §7· 민심 §f" + region.get("민심")
                + (curfew() ? " §c· 통행금지" : ""));
        for (Corpse c : corpses.values()) {
            out.add("§8· §f" + plugin.populace().nameOf(c.victim) + " §7— " + c.stage
                    + (c.concealed ? " (은닉)" : c.discovered ? " (발견)" : " (미발견)")
                    + " §7목격 " + c.witnesses);
        }
        for (Task t : tasks.values()) {
            out.add((t.closed ? "§8○ " : "§e● ") + "§f" + t.rule.id() + " §7— 발주 "
                    + plugin.populace().nameOf(t.issuer)
                    + (t.player == null ? " §8(미수주)" : " §a(수주)") + (t.done ? " §a목표달성" : ""));
        }
        bounty.forEach((id, amount) -> {
            Player p = plugin.getServer().getPlayer(id);
            out.add("§c수배 §f" + (p == null ? id : p.getName()) + " §7— " + amount + "문");
        });
        return out;
    }

    // ══════════════ 다리 — 봇으로 흘려보낸다 ══════════════

    /**
     * 세계의 장부로 흘려보낸다.
     *
     * <p><b>다리는 하나다.</b> 무명층은 자기 큐를 따로 파지 않는다 — {@link WorldBridge} 가 이 저장소의
     * 유일한 통로다 (config/world_bridge.yml, 등록되지 않은 kind 는 그쪽이 버린다).
     * 죽음은 등록 kind {@code npc_death} 로 나간다: MVT 는 <b>사실</b>만 보내고
     * (누가·어디서·목격 몇·밤인가·시신은·사인은), 소문 강도·정확도·세력 점수·지역 델타 같은
     * <b>파생값은 봇이 npc_death.yml 로 계산한다.</b> 파생값을 둘이 계산하면 세계가 둘이 된다.
     *
     * <p>여기 남은 것들(수배·자수·의뢰)은 아직 world_bridge.yml events 에 등록되지 않았다 →
     * 로그로만 남긴다 (다리 담당자가 kind 를 등록하면 그때 emit 으로 승격한다).
     */
    private void emit(String type, Map<String, ?> data) {
        if (!bridgeOn) {
            return;
        }
        plugin.getLogger().info("[사건] " + type + " " + data);
    }

    /** 죽음만은 등록 kind 로 장부에 실린다 — 파생은 봇이 한다 (npc_death.yml 이 그쪽에도 있다) */
    private void bridgeDeath(Corpse corpse, String body, boolean night, Player killer) {
        if (!bridgeOn) {
            return;
        }
        Populace people = plugin.populace();
        WorldBridge.npcDeath("populace", corpse.victim, people.nameOf(corpse.victim),
                people.jobOf(corpse.victim), placeKey(corpse.at), corpse.witnesses, night, body,
                killer == null ? "사고" : "플레이어_살해",
                killer == null ? null : killer.getUniqueId(),
                killer == null ? null : killer.getName());
    }

    /** 자리 이름 — 소문의 발원망은 '어디서 났는가'가 정한다 (world_bridge.yml place_map) */
    private String placeKey(Location at) {
        Populace people = plugin.populace();
        String best = null;
        double bestDist = Double.MAX_VALUE;
        for (String name : people.placeNames()) {
            Location center = people.placeCenter(name);
            if (center == null || center.getWorld() != at.getWorld()) {
                continue;
            }
            double d = center.distance(at);
            if (d < bestDist) {
                bestDist = d;
                best = name;
            }
        }
        return best == null ? "장터_광장" : best;
    }

    // ─── 잡동사니 ───

    private void cleanupMarkers() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (e.getPersistentDataContainer().has(keyCorpse, PersistentDataType.STRING)
                        || e.getPersistentDataContainer().has(keyPursuer, PersistentDataType.STRING)) {
                    e.remove();
                }
            }
        }
    }

    private void removeMarker(Corpse corpse) {
        if (corpse.marker == null) {
            return;
        }
        Entity e = plugin.getServer().getEntity(corpse.marker);
        if (e != null) {
            e.remove();
        }
        corpse.marker = null;
    }

    private void shout(Location at, String who, String line) {
        if (line == null || at.getWorld() == null) {
            return;
        }
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getLocation().distance(at) <= 48) {
                p.sendMessage(ChatColor.RED + "[" + who + "] " + ChatColor.WHITE + line);
            }
        }
    }

    private void broadcast(String message) {
        Location market = plugin.anchor("장터");
        if (market == null || market.getWorld() == null) {
            return;
        }
        for (Player p : market.getWorld().getPlayers()) {
            p.sendMessage(message);
        }
    }

    private static int day(World world) {
        return world == null ? 0 : (int) (world.getFullTime() / 24000L);
    }

    private static String fmt(String template, Map<String, String> subs) {
        if (template == null) {
            return "";
        }
        String out = template;
        for (Map.Entry<String, String> e : subs.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    private static String clean(Object v) {
        return v == null ? null : String.valueOf(v).replaceAll("\\s+", " ").strip();
    }

    private static Map<String, Object> tryLoad(Path file) {
        try {
            Map<String, Object> root = RulesConfig.load(file);
            return root == null ? Map.of() : root;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    private static List<?> list(Object v) {
        return v instanceof List<?> l ? l : List.of();
    }

    private static int num(Object v, int fallback) {
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private static double dbl(Object v, double fallback) {
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String strOr(Object v, String fallback) {
        return v == null ? fallback : String.valueOf(v);
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
