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

    /** 액션바 notice 채널 이름 (B-116) — 시신 은닉 진행 조각이 사는 자리 */
    private static final String CONCEAL_CHANNEL = "은닉";

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
        boolean irony;            // 살해자가 유족의 의뢰를 완수했는가 (혈교가 읽는 사실)
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
    private final Map<String, String> mood = new LinkedHashMap<>();       // person → 애도
    private final Map<String, Integer> respawnAt = new LinkedHashMap<>(); // person → 부활 세계일
    private final Set<String> strayed = new LinkedHashSet<>();            // 길 잃은 아이
    private final Map<UUID, Long> concealStart = new LinkedHashMap<>();   // 은닉 중인 플레이어
    private final Map<UUID, String> concealTarget = new LinkedHashMap<>();
    private final Map<UUID, Integer> pursuitAt = new LinkedHashMap<>();   // 추격 중 → 포교 출동 세계일
    private final Set<UUID> ledgerPursued = new LinkedHashSet<>();        // 장부의 방(榜)을 보고 이미 출동했는가
    private final Set<String> contacted = new LinkedHashSet<>();          // 세력이 이미 말을 건 자 (uuid|세력)
    private int curfewUntil = -1;
    private int lastNightDay = -1;

    public Incidents(HoncheonMvt plugin) {
        this.plugin = plugin;
        this.keyCorpse = new NamespacedKey(plugin, "corpse");
        this.keyPursuer = new NamespacedKey(plugin, "pursuer");
        if (plugin.populace() != null) {
            plugin.populace().bind(this);   // 스스로 접합한다 — HoncheonMvt 는 네 줄만 알면 된다
        }
    }

    // ══════════════ 장부 판독 — 정본은 봇이다. MVT 는 읽기만 한다 ══════════════
    //
    // ★ 여기 있던 세 개의 맵(bounty · favor · region)이 이 층의 가장 큰 거짓말이었다.
    //   그것들은 인메모리였다 — 재기동하면 수배가 증발했다. 열 명을 죽이고 다시 접속하면
    //   아무도 그를 쫓지 않았다. 혈교는 "첫 피를 안다"고 말을 걸어 놓고 다음 날 그를 잊었다.
    //   그리고 더 나빴던 것: 봇도 같은 값을 제 장부에서 굴리고 있었다 (blood_debt → 현상금,
    //   faction_standing → favor, regions → 치안·경제·민심). **세계가 둘이었다.**
    //
    // 이제 정본은 하나다 — **봇의 장부**다. 재기동을 넘어 살아남는 유일한 곳이기 때문이다.
    // MVT 가 하는 일은 셋뿐이다: ① 사실을 다리로 보낸다 ② 장부를 스냅숏에서 읽는다
    // ③ 읽은 것으로 인게임 행동을 한다 (포교를 부르고, 소문을 켜고, 말을 건다).
    // 계산은 하지 않는다. 계산을 둘이 하면 세계가 둘이 된다.

    /** 지역 상태 — 봇의 regions 표 (world_bridge.yml feedback.world_state.region) */
    private static int region(String stat) {
        Integer v = WorldBridge.state().region().get(stat);
        return v == null ? 50 : v;   // 스냅숏이 아직 없다 — 세계는 기준값에 서 있다
    }

    /** 그의 목에 걸린 값 — 봇이 혈채로 계산한다 (blood_debt.engine.bounty_min 3 → 방이 붙는다) */
    private static int bountyOf(UUID player) {
        return WorldBridge.state().bounty(player);
    }

    /** 세력 우호 — 봇의 faction_standing (MVT 는 여기에 한 점도 더하지 않는다) */
    private static int favorOf(UUID player, String faction) {
        return WorldBridge.state().favor(player, faction);
    }

    /**
     * 관이 지금 그를 쫓는가.
     *
     * <p>둘 중 하나면 참이다: <b>장부</b>가 그의 목에 값을 걸었거나(현상금·법명분 — 재기동을 넘어
     * 살아남는다), 방금 이 자리에서 <b>단계가 집행됐거나</b>(포교는 사흘 쫓고 물러난다 — 관은
     * 조직이지 원한이 아니다). 앞의 것이 장부이고, 뒤의 것은 행동이다.
     */
    private boolean wanted(UUID player, int day) {
        WorldBridge.State state = WorldBridge.state();
        if (state.bounty(player) > 0 || state.wanted(player)) {
            return true;
        }
        Integer since = pursuitAt.get(player);
        return since != null && day - since <= pursuitGiveUpDays;
    }

    public void start() {
        cleanupMarkers();
        plugin.getServer().getScheduler().runTaskTimer(plugin, Metrics.wrap("incidents", this::tick), 140L, tickerPeriod);
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

        // ★ 지역 델타(민심 -3 · 치안 -3 …)는 여기서 더하지 않는다 — 봇이 더한다.
        //   MVT 는 npc_death 로 **사실**만 보냈고(목격 몇·시신은·밤인가), 봇이 그 사실을
        //   npc_death.yml populace_layer.stages 로 정산해 regions 표에 적는다.
        //   같은 델타를 양쪽이 더하면 치안이 두 번 무너진다.

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
            // 포교(捕校)는 **행동**이지 장부가 아니다 — 그래서 여기서 부른다.
            // 그러나 현상금(액수)은 장부다 — 봇이 혈채로 계산한다. 아직 방이 안 붙었으면
            // (혈채가 문턱 미만이면) 값을 지어내지 않는다. 관은 아직 그를 '찾을' 뿐이다.
            pursuitAt.put(killer.getUniqueId(), day);
            int amount = bountyOf(killer.getUniqueId());
            killer.sendMessage(amount > 0
                    ? ChatColor.RED + fmt(LINE.get("bounty.line"), Map.of("amount", String.valueOf(amount)))
                    : ChatColor.RED + "관이 당신을 찾는다.");
            killer.sendMessage(ChatColor.GRAY + "관졸에게 웅크린 채 말을 걸면 "
                    + ChatColor.WHITE + "자수" + ChatColor.GRAY + "할 수 있다.");
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> summonPursuit(killer, name), 20L * PURSUIT_DELAY.getOrDefault(name, 15));
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

    /**
     * 세력 반응 — 무명 하나로는 움직이지 않는다. 쌓여야 움직인다 (혈교는 예외다: 그들은 첫 피를 안다).
     *
     * <p><b>MVT 는 favor 를 한 점도 더하지 않는다.</b> 점수는 봇의 장부(faction_standing)에만 있고,
     * 그 값이 스냅숏으로 돌아온다. 여기서 하는 일은 <b>장부가 이미 넘긴 문턱을 사람의 말로 옮기는 것</b>뿐이다 —
     * 세력이 말을 거는 것은 사건이 아니라 <b>상태</b>이기 때문이다.
     *
     * <p>문턱: 등록부의 {@code threshold}(무명 3인) × {@code favor_per_kill}(2) = favor 6.
     * 새 수치가 아니다 — npc_death.yml populace_layer.faction_response 두 칸의 곱이다.
     */
    private void factions(Player killer, String stage) {
        int rank = STAGE_ORDER.indexOf(stage);
        for (FactionRule f : FACTIONS) {
            if (rank < STAGE_ORDER.indexOf(f.fromStage()) || f.contactLine() == null
                    || f.threshold() <= 0 || f.delta() <= 0) {
                continue;
            }
            int now = favorOf(killer.getUniqueId(), f.faction());
            if (now < f.threshold() * f.delta()) {
                continue;   // 장부가 아직 그를 그렇게 보지 않는다
            }
            if (contacted.add(killer.getUniqueId() + "|" + f.faction())) {
                killer.sendMessage(ChatColor.DARK_PURPLE + "[" + f.faction() + "] "
                        + ChatColor.LIGHT_PURPLE + f.contactLine());
            }
        }
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
        ledgerWatch(world, day);
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
        // ★ 이 치안은 이제 **봇의 표**다 (스냅숏). 예전에는 MVT 안의 사(私)장부였다 —
        //   그래서 마을을 구해도 눈금이 안 올라갔고, 마을을 망쳐도 봇은 그것을 몰랐다.
        people.rumor(regionLowRumor, region("치안") < regionLow);

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
                plugin.skills().dropNotice(id, CONCEAL_CHANNEL);
                continue;
            }
            if (!player.isSneaking() || player.getLocation().distance(corpse.at) > concealRadius) {
                concealStart.remove(id);
                concealTarget.remove(id);
                // 순간 문구는 flash, 진행 조각은 내린다 (B-116) — 맨 sendActionBar 는 곧 덮인다
                plugin.skills().dropNotice(id, CONCEAL_CHANNEL);
                plugin.skills().flash(player, ChatColor.GRAY + "손을 뗐다.");
                continue;
            }
            // 방해 — 반경 안에 산 사람이 들어오면 중단 + 목격 +1
            List<String> near = plugin.populace().aliveNear(corpse.at, concealInterrupt);
            if (!near.isEmpty()) {
                concealStart.remove(id);
                concealTarget.remove(id);
                plugin.skills().dropNotice(id, CONCEAL_CHANNEL);
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
                plugin.skills().dropNotice(id, CONCEAL_CHANNEL);
                removeMarker(corpse);
                player.sendMessage(ChatColor.DARK_GRAY + LINE.get("conceal.done"));
                applyStage(corpse, "은밀", player);
                bridgeDeath(corpse, "은닉", corpse.night, player);
                emit("시신_은닉", Map.of("victim", corpse.victim, "killer", player.getName()));
            } else {
                int pct = (int) (elapsed * 100 / Math.max(1, concealSeconds));
                // B-116: 은닉 진행은 지속 표시 — notice 채널 조각으로, 생명·격 두름과 나란히 읽힌다.
                // 수명 = 재송신 주기(tickerPeriod) + statusBar 주기(4틱) — 잠정 도출값
                plugin.skills().notice(player, CONCEAL_CHANNEL, ChatColor.DARK_RED
                        + fmt(LINE.get("conceal.progress"), Map.of("pct", String.valueOf(pct))),
                        tickerPeriod + 4);
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

    /**
     * ★ 장부 감시 — <b>재기동을 건너온 수배</b>.
     *
     * <p>이것이 이 층에서 가장 오래 비어 있던 자리다. 예전에는 수배가 MVT 의 인메모리 맵에만 있었고,
     * 서버를 껐다 켜면 <b>세계가 그를 잊었다</b>. 이제 장부는 봇에 있고(혈채 → 현상금),
     * 봇은 20초마다 그 장부를 스냅숏으로 내려보낸다. 그가 다시 접속하면 — <b>방(榜)은 그대로 붙어 있다.</b>
     *
     * <p>{@link WorldBridge.State#bounty} 를 부르는 <b>첫 자리</b>다. 여기가 비어 있었기 때문에
     * 봇이 계산한 현상금은 지금까지 아무도 읽지 않는 숫자였다.
     */
    private void ledgerWatch(World world, int day) {
        for (Player player : world.getPlayers()) {
            UUID id = player.getUniqueId();
            int amount = bountyOf(id);
            if (amount <= 0) {
                ledgerPursued.remove(id);   // 방이 내려갔다 (혈채가 감쇠했거나 관이 물러섰다)
                continue;
            }
            if (!ledgerPursued.add(id)) {
                continue;   // 이번 접속에서 이미 그를 보러 갔다
            }
            player.sendMessage(ChatColor.DARK_RED + "관아에 방(榜)이 붙어 있다. "
                    + ChatColor.RED + fmt(LINE.get("bounty.line"), Map.of("amount", String.valueOf(amount))));
            pursuitAt.put(id, day);
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> summonPursuit(player, "지목"), 20L * PURSUIT_DELAY.getOrDefault("지목", 15));
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
            if (target == null || !wanted(target.getUniqueId(), day)
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
        if (!target.isOnline() || !wanted(target.getUniqueId(), day(target.getWorld()))) {
            return;   // 그 사이에 자수했거나 관이 물러났다
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
        emit("의뢰_발생", Map.of("event", "발생", "rule", rule.id(), "outcome", "",
                "issuer", issuer, "victim", victim == null ? "" : victim, "day", day));
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
            settle(task, task.rule.onExpire(), "on_expire", null, 0);
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
        if ("관졸".equals(people.voiceOf(id)) && sneaking
                && wanted(player.getUniqueId(), day(player.getWorld()))) {
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
        // ★ 아이러니 — 살해자가 유족의 의뢰를 받아 제 시신을 '찾아 주었다'.
        //   혈교는 이것을 자격으로 읽는다 (populace.yml killer_irony.blood_favor).
        //   그 점수는 봇이 준다 — 다리에 irony=true 로 실려 간다. MVT 는 그 사실만 안다.
        task.irony = isKiller && !task.rule.irony().isEmpty();
        settle(task, outcome, outcome == task.rule.failBody() ? "fail_body" : "success",
                player, task.rule.money());

        if (task.irony) {
            player.sendMessage(ChatColor.DARK_PURPLE + clean(task.rule.irony().get("complete")));
        }
    }

    /**
     * 결말 — 보수·민심·소문·그 사람의 이후 대사. 실패도 서사다.
     *
     * <p><b>민심은 여기서 더하지 않는다.</b> 이 의뢰가 어떻게 끝났는지(rule · outcome)만 다리에 싣고,
     * 그 결말이 지역에 얼마를 얹는지는 <b>봇이 populace.yml 을 읽어</b> 제 표에 적는다.
     * 등록부는 하나이고(quests.rules.&lt;id&gt;.&lt;outcome&gt;.region), 장부도 하나여야 한다.
     */
    private void settle(Task task, Map<String, Object> outcome, String outcomeKey,
                        Player player, int money) {
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
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("event", player == null ? "만료" : "완료");
        data.put("rule", task.rule.id());
        data.put("outcome", outcomeKey);
        data.put("issuer", task.issuer);
        data.put("victim", task.victim == null ? "" : task.victim);
        if (player != null) {
            data.put("player_uuid", player.getUniqueId().toString());
            data.put("player_name", player.getName());
        }
        data.put("irony", task.irony);
        emit(player == null ? "의뢰_만료" : "의뢰_완료", data);
    }

    /**
     * 자수 — <b>포교는 물러난다.</b> 그러나 빚은 지워지지 않는다.
     *
     * <p>등록부가 두 법정을 갈라 놓았다 (그리고 그것이 이 세계에서 가장 정직한 줄 중 하나다):
     * <ul>
     *   <li><b>관의 법</b> — npc_death.yml responses.자수: 법명분 -10 · 민심 +2.
     *       법명분이 내려가면 마을 관졸이 등을 돌리지 않는다 (world_bridge feedback.wanted.gauge_min).</li>
     *   <li><b>혈채</b> — faction_reaction.yml blood_debt.atonement: <b>"혈채는 삭제되지 않는다.
     *       성격이 바뀔 뿐이다"</b> — 자수는 현혈채의 <b>감쇠를 개시</b>할 뿐이다.
     *       그래서 목에 걸린 값(현상금)은 자수 한 번으로 사라지지 않는다.</li>
     * </ul>
     *
     * <p>그 정산은 전부 <b>봇</b>이 한다 (장부는 하나다). MVT 가 하는 일은 사실을 보내고
     * (자수했다 · 벌금 얼마), 포교를 거두는 것뿐이다.
     */
    private void surrender(Player player, String guardId) {
        int amount = bountyOf(player.getUniqueId());
        pursuitAt.remove(player.getUniqueId());
        ledgerPursued.remove(player.getUniqueId());
        int fine = Math.max(0, amount / 5);   // 방이 안 붙었으면 벌금도 없다 (관은 값을 지어내지 않는다)
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
        player.sendMessage(ChatColor.GREEN + "자수 — 포교가 물러난다"
                + (fine > 0 ? " · 벌금 " + fine + "문" : "") + ". " + ChatColor.GRAY
                + "(관의 법과 유족의 원한은 다른 법정이다. 그리고 빚은 지워지지 않는다)");
        emit("자수", Map.of("player_uuid", player.getUniqueId().toString(),
                "player_name", player.getName(), "fine", fine));
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

    /** /혼천 인구 — 사건 현황. 수치는 전부 <b>봇의 장부</b>에서 읽은 것이다 (MVT 는 세지 않는다) */
    public List<String> status() {
        List<String> out = new ArrayList<>();
        out.add("§6── 사건 ──");
        out.add("§7치안 §f" + region("치안") + " §7· 경제 §f" + region("경제")
                + " §7· 민심 §f" + region("민심") + " §8(장부)"
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
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            int amount = bountyOf(p.getUniqueId());
            if (amount > 0) {
                out.add("§c수배 §f" + p.getName() + " §7— " + amount + "문 §8(봇 장부 · 재기동을 건너온다)");
            }
        }
        return out;
    }

    // ══════════════ 다리 — 봇으로 흘려보낸다 ══════════════

    /**
     * 사건 이름 → 다리의 등록 kind (config/world_bridge.yml events).
     *
     * <p>여기 <b>있는</b> 것은 장부로 간다. 여기 <b>없는</b> 것은 서버 로그에만 남는다 —
     * 그리고 그것이 옳다: {@code 무명_사망}·{@code 시신_발견}·{@code 시신_은닉}·{@code 소문_발화} 는
     * 이미 {@code npc_death} 한 줄이 다 싣고 갔고(목격·시신·밤·사인), {@code 수배}·{@code 세력_반응}·
     * {@code 지역_델타} 는 <b>파생값</b>이다 — 봇이 제 등록부로 계산한다. 파생값을 다리에 실으면
     * 세계가 그것을 두 번 믿는다.
     */
    private static final Map<String, String> KIND = Map.of(
            "자수", "surrender",
            "의뢰_발생", "populace_quest",
            "의뢰_완료", "populace_quest",
            "의뢰_만료", "populace_quest");

    /**
     * 세계의 장부로 흘려보낸다.
     *
     * <p><b>다리는 하나다.</b> 무명층은 자기 큐를 따로 파지 않는다 — {@link WorldBridge} 가 이 저장소의
     * 유일한 통로다 (config/world_bridge.yml, 등록되지 않은 kind 는 그쪽이 버린다).
     * MVT 는 <b>사실</b>만 보낸다 (누가·어디서·목격 몇·밤인가·시신은·사인은 · 무엇을 했는가).
     * 소문 강도·정확도·세력 점수·지역 델타·현상금 같은 <b>파생값은 봇이 등록부로 계산한다.</b>
     * 파생값을 둘이 계산하면 세계가 둘이 된다.
     */
    private void emit(String type, Map<String, ?> data) {
        if (!bridgeOn) {
            return;
        }
        String kind = KIND.get(type);
        if (kind == null) {
            plugin.getLogger().info("[사건] " + type + " " + data);   // 장부의 일이 아니다 (파생·중복)
            return;
        }
        WorldBridge.emit(kind, new LinkedHashMap<>(data));
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
