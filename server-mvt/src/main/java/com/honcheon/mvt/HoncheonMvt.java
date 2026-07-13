package com.honcheon.mvt;

import com.honcheon.core.rules.EconomyEngine;
import com.honcheon.core.rules.JudgmentEngine;
import com.honcheon.core.rules.NpcLifecycleEngine;
import com.honcheon.core.rules.PartyEngine;
import com.honcheon.core.rules.ProgressionEngine;
import com.honcheon.core.rules.RulesConfig;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 혼천 MVT(최소 검증판) — 관리자 1인 로컬 테스트 서버.
 * core 룰 엔진을 인게임에 배선한다: 사냥 = 실전 화후 적립, 기세 표시, 전낭·매입가, 판정, NPC 정산.
 * config/*.yml(저장소 루트 사본)이 단일 진실 원천 — 이 플러그인은 수치를 하드코딩하지 않는다.
 */
public final class HoncheonMvt extends JavaPlugin {

    private JudgmentEngine judgment;
    private ProgressionEngine progression;
    private EconomyEngine economy;
    private NpcLifecycleEngine lifecycle;
    private PartyEngine party;
    private SkillEngine skillEngine;
    private SkillListener skills;
    private WorldMap worldMap;
    private HuntingGrounds hunting;
    private Populace populace;   // 무명(無名) — 세계에 사는 사람들 (config/npcs/populace.yml)
    private Incidents incidents;   // 사건 — 살인이 남기는 자국 (시신·목격·수사·유족의 의뢰)
    private SkillCast skillCast;   // 절기(絶技) — 삼문(承·間·虛)이 열려야 나간다
    private Dojang dojang;
    private Antechamber antechamber;   // 입도진 — 강호에 들지 않은 자를 세계에 떨구지 않는다
    private GyeonggongListener gyeonggong;   // 경공 — 몸이 땅을 딛는 법
    private DojangGui dojangGui;   // 시험대 — 클릭으로 고른다

    private final Map<UUID, PlayerLedger> ledgers = new HashMap<>();
    private final Map<String, Location> anchors = new HashMap<>();
    private final java.util.List<Zone> zones = new java.util.ArrayList<>();

    @Override
    public void onEnable() {
        File configDir = new File(getDataFolder(), "config");
        if (!configDir.isDirectory()) {
            getLogger().severe("config 디렉터리가 없습니다: " + configDir);
            getLogger().severe("scripts/run_mvt_server.sh 로 기동하세요 (저장소 config/ 를 동기화합니다).");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Path cfg = configDir.toPath();
        Metrics.load(cfg);      // 계기 등록부 — performance.yml metrics.probes (예산이 정본이다)
        TickBudget.load(cfg);   // 틱 슬라이싱 예산 — 조성의 한 틱 폭탄을 나눠 먹인다
        Metrics.start(this);    // 계기 쓸기 (안 돈 틱도 마감한다)
        this.judgment = new JudgmentEngine(RulesConfig.load(cfg.resolve("judgment.yml")));
        this.progression = new ProgressionEngine(
                RulesConfig.load(cfg.resolve("cultivation.yml")), RulesConfig.load(cfg.resolve("training.yml")));
        this.economy = new EconomyEngine(RulesConfig.load(cfg.resolve("economy.yml")));
        this.lifecycle = new NpcLifecycleEngine(
                RulesConfig.load(cfg.resolve("npc_lifecycle.yml")), RulesConfig.load(cfg.resolve("judgment.yml")));
        this.party = new PartyEngine(RulesConfig.load(cfg.resolve("party.yml")));
        this.skillEngine = new SkillEngine(cfg);
        this.worldMap = WorldMap.load(cfg);   // 세계 지도 — world_map.yml (없으면 null)
        TerrainForge.load(cfg);   // 지형 계층 — 동굴 등록부 (config/terrain.yml · 없어도 돈다)
        Weapons.init(cfg);   // 병기 제작소 — equipment.yml·combat.yml 판독
        Vitality.init(cfg);   // 생명 — 내구 등록부. **HuntingGrounds 보다 먼저** (FOES 를 init 때 파싱한다)
        MapAudit.load(cfg);   // 지도의 눈 — ★ 안 지어진 60곳은 검수를 통과하는 게 아니라 **검수를 안 받는다**
        RiverForge.load(cfg); // 강 — 물길 등록부 (config/rivers.yml · 없어도 돈다).
                              // ★ 이 세계엔 **강이 없었다.** 지도가 물을 일곱 곳 주문했는데 지형 계층이 안 빚었다.
        Gyeonggong.init(cfg); // 경공 — 경지 천장·신법 성장·경신 소모·문파 보법 (config/gyeonggong.yml).
                              // ★ 이것이 없으면 절정 고수도 삼류도 **바닐라처럼 똑같이 걷는다**
        Growth.init(cfg);     // 성장 축 — training.yml curriculum · combat.yml attacker_attribute.
                              // ★ 이것이 없으면 **같은 경지의 두 사람이 코드 수준에서 완전히 같은 사람**이 된다
        HuntingGrounds.init(cfg);   // 적 등록부 — 짐승·사람 스탯 (npcs·npc_combat·combat)
        Populace.init(cfg);   // 인구 등록부 — 행인·주민 28인 (npcs/populace.yml)
        WorldBridge.init(cfg, getLogger());   // 세계 다리 — 마크의 사건이 봇의 장부로 간다 (world_bridge.yml)
        Incidents.init(cfg);   // 사건 등록부 — 살해 연쇄·시신·수사·무명의 의뢰 (npc_death.populace_layer)
        this.skills = new SkillListener(this, skillEngine);
        this.hunting = new HuntingGrounds(this);
        this.populace = new Populace(this);
        this.incidents = new Incidents(this);   // 순서 중요 — 생성자가 populace 에 스스로 접합한다
        this.skillCast = new SkillCast(this, skillEngine, skills, cfg);
        this.dojang = new Dojang(this);
        this.antechamber = new Antechamber(this, cfg);
        this.dojangGui = new DojangGui(this);
        this.gyeonggong = new GyeonggongListener(this);   // 몸이 땅을 딛는 법
        Onboarding.init(cfg);   // 첫 접속의 목소리 — player_creation.yml mvt_onboarding
                                // ★ 이것이 없으면 아무것도 모르는 사람이 **아무 말도 못 듣고** 빈 세계에 선다
        // 되먹임 — 봇의 소문판이 마을 사람의 발길을 바꾸고, ★ 봇의 시트가 사람의 몸에 실린다
        // (워커 스레드 → 메인 스레드로 태워 준다)
        WorldBridge.onState(state -> getServer().getScheduler().runTask(this, () -> {
            for (String tag : WorldBridge.reactionTags()) {
                populace.rumor(tag, state.reactions().contains(tag));
            }
            skills.syncAllSheets();   // ★ 경지·능력치·심법·내공 — 정본은 봇이다
        }));
        WorldBridge.start();

        PackPusher packPusher = new PackPusher(this);
        packPusher.load(cfg);                    // 팩은 **접속한 주소**로 나간다 (박힌 URL 은 LAN 밖에서 죽는다)
        getServer().getPluginManager().registerEvents(packPusher, this);
        getServer().getPluginManager().registerEvents(antechamber, this);
        getServer().getPluginManager().registerEvents(gyeonggong, this);
        getServer().getPluginManager().registerEvents(new HuntListener(this), this);
        getServer().getPluginManager().registerEvents(new ZoneListener(this), this);
        getServer().getPluginManager().registerEvents(new TradeListener(this), this);
        getServer().getPluginManager().registerEvents(new LedgerGui(this), this);
        getServer().getPluginManager().registerEvents(new WeaponShop(this), this);
        getServer().getPluginManager().registerEvents(skills, this);
        getServer().getPluginManager().registerEvents(skillCast, this);
        getServer().getPluginManager().registerEvents(hunting, this);
        getServer().getPluginManager().registerEvents(hunting.mobDisplay(), this);   // 3D 몹 형체
        getServer().getPluginManager().registerEvents(populace, this);
        getServer().getPluginManager().registerEvents(incidents, this);
        getServer().getPluginManager().registerEvents(dojang, this);
        getServer().getPluginManager().registerEvents(dojangGui, this);
        getServer().getPluginManager().registerEvents(hunting.sparring(), this);
        skills.start();   // 중앙 티커 1개 (performance.yml F-P2)
        gyeonggong.start();   // 켜져 있는 사람만 돈다 (Metrics 가 잰다 — probes.gyeonggong)
        antechamber.start();   // 몸짓·경공·물안개·문이 열리는 순간을 본다
        skillCast.start();   // 절기의 삼문 — 창(窓)·간격·허를 매 틱 읽는다
        hunting.start();  // 중앙 티커 — 구역 스포너·전의·비무 판정
        populace.start();   // 중앙 티커 — 행인의 일과·배회 (마을이 비어 있으면 세계가 아니다)
        incidents.start();   // 중앙 티커 — 시신 발견·은닉·포교의 추적·무명의 의뢰
        getCommand("honcheon").setExecutor(new MvtCommand(this));
        loadAnchors();
        loadZones();
        loadRegionBases();
        loadLedgers();   // ★ 원장 — 재기동을 넘어 살아남는다 (여태 순수 HashMap 이었다)
        // 원장 굽기 — 주기 저장. 크래시는 예고하지 않는다 (onDisable 이 안 불리는 죽음이 있다)
        getServer().getScheduler().runTaskTimer(this, this::saveLedgers, 6000L, 6000L);   // 5분
        // 정보 패널 (사이드바) — 5초 주기 갱신: 위치·소지금·오늘 수련
        getServer().getScheduler().runTaskTimer(this,
                Metrics.wrap("sidebar", () -> getServer().getOnlinePlayers().forEach(this::updateSidebar)),
                100L, 100L);
        // 등록부가 누구를 무인으로 세웠는지 기동 때 말한다 — config 에 적었는데 코드가 안 읽으면
        // 그 NPC 는 조용히 맨손으로 선다(곽진이 그랬다: realm 이 없어 '전투에 서지 않는 사람'이었다).
        java.util.List<String> fighters = skillEngine.manifestingNpcs();
        getLogger().info("격을 쓰는 NPC " + fighters.size() + "인: "
                + (fighters.isEmpty() ? "없음 — 등록부에 realm 이 없다" : String.join(", ", fighters)));
        getLogger().info("혼천 MVT 기동 — 룰 엔진 5종 로드 완료 (/혼천 도움말)"
                + (anchors.isEmpty() ? " — 청하현 미조성 (/혼천 조성)" : " — 청하현 앵커 " + anchors.size() + "곳"));
    }

    @Override
    public void onDisable() {
        saveLedgers();        // ★ 원장을 먼저 굽는다 — 재기동 = 능력치·내공·숙련·소지금 전멸이었다
        WorldBridge.stop();   // 큐에 남은 사건을 마저 쓴다 — 하나도 버리지 않는다
        if (skills != null) {
            skills.shutdown();   // 무공의 3D 형체를 세계에 남기지 않는다
        }
        if (antechamber != null) {
            // 글판을 걷는다 — 안 걷으면 다시 지을 때 **글이 두 겹으로 겹친다**
            antechamber.shutdown();
        }
        if (gyeonggong != null) {
            // 걷기 속도를 되돌린다 — 경공을 켠 채 로그아웃한 자가 **영영 빨라지면 안 된다**
            gyeonggong.shutdown();
        }
        if (hunting != null) {
            // 형체를 걷고 **투명한 본체를 되돌린다** — 없으면 보이지 않는 호랑이가 세계에 남는다
            hunting.mobDisplay().clearAll();
        }
    }

    /**
     * 장부를 갈아 끼운다 — <b>연무장의 수련은 세계로 새지 않는다</b> (사용자 규정).
     *
     * <p>연무장에 들어갈 때 현실의 장부를 떼어 두고 시험용 장부를 끼운다. 나올 때 되돌린다.
     * 시험장에서 얻은 30일 수련이 강호의 경지가 되면, 그건 시험이 아니라 치트다.
     */
    public PlayerLedger swapLedger(UUID playerId, PlayerLedger replacement) {
        PlayerLedger previous = ledgers.get(playerId);
        if (replacement == null) {
            ledgers.remove(playerId);
        } else {
            ledgers.put(playerId, replacement);
        }
        return previous;
    }

    public PlayerLedger ledger(UUID playerId) {
        return ledgers.computeIfAbsent(playerId, id -> new PlayerLedger());
    }

    // ─── 원장 영속 — **재기동을 넘어 살아남는다** ───
    //
    // 여기 저장·적재 코드가 **없었다**. `ledgers` 는 순수 HashMap 이었고 (`new HashMap<>()`),
    // 재기동 한 번이면 능력치·내공·숙련·소지금·수련 배분이 **전부 소멸**했다. 로그아웃도 마찬가지였다.
    //
    // 문법은 이 저장소의 것을 그대로 쓴다 — anchors.yml · zones.yml · regions.yml 과 같은
    // YamlConfiguration (플러그인 데이터 폴더). 새 형식을 발명하지 않는다.
    //
    // ★ 이 파일은 **거울이지 원본이 아니다.** 시트의 정본은 봇(SQLite)이고, 여기 적힌 경지·능력치는
    //   봇이 꺼져 있을 때 마지막으로 본 상(像)이다 — 봇이 켜지면 스냅숏이 절대값으로 덮어쓴다.
    //   진짜로 여기서만 사는 값은 **몸의 것**뿐이다: 수련 배분 · 일일 카운터 · 아직 못 올린 미결 증분.

    private static final String LEDGER_FILE = "ledgers.yml";

    /** 한 사람의 원장을 굽는다 — 로그아웃 (SkillListener.onQuit) */
    public void saveLedger(UUID playerId) {
        PlayerLedger led = ledgers.get(playerId);
        if (led == null) {
            return;
        }
        File file = new File(getDataFolder(), LEDGER_FILE);
        YamlConfiguration yml = file.isFile()
                ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        led.save(yml.createSection(playerId.toString()));
        try {
            yml.save(file);
        } catch (IOException e) {
            getLogger().severe("원장 저장 실패 — 이 사람의 수련이 사라진다: " + e.getMessage());
        }
    }

    /** 전원의 원장을 굽는다 — 5분 주기 · 종료 */
    public void saveLedgers() {
        if (ledgers.isEmpty()) {
            return;
        }
        YamlConfiguration yml = new YamlConfiguration();
        ledgers.forEach((id, led) -> led.save(yml.createSection(id.toString())));
        try {
            yml.save(new File(getDataFolder(), LEDGER_FILE));
        } catch (IOException e) {
            getLogger().severe("원장 저장 실패 — 재기동하면 전부 사라진다: " + e.getMessage());
        }
    }

    private void loadLedgers() {
        File file = new File(getDataFolder(), LEDGER_FILE);
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String key : yml.getKeys(false)) {
            var section = yml.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            try {
                ledgers.put(UUID.fromString(key), PlayerLedger.load(section));
            } catch (IllegalArgumentException notUuid) {
                getLogger().warning("원장의 키가 uuid 가 아니다 (건너뛴다): " + key);
            }
        }
        getLogger().info("원장 " + ledgers.size() + "인 적재 — 재기동을 넘어 살아남았다");
    }

    /** 지금 세계일 — 봇이 굴린다 (현실 하루 = 세계 1일). 0 = 봇이 꺼져 있다 */
    public long worldDay() {
        return WorldBridge.worldDay();
    }

    // ─── 청하현 장소 앵커 — 조성이 세우고 파일이 기억한다 (재기동 생존) ───

    public Location anchor(String name) {
        return anchors.get(name);
    }

    /** 전 앵커 — 검수·조감(TownAudit·TownRender)의 입력 */
    public Map<String, Location> anchors() {
        return java.util.Collections.unmodifiableMap(anchors);
    }

    public void setAnchors(Map<String, Location> built) {
        anchors.clear();
        anchors.putAll(built);
        YamlConfiguration yml = new YamlConfiguration();
        anchors.forEach(yml::set);   // Location = ConfigurationSerializable
        try {
            yml.save(new File(getDataFolder(), "anchors.yml"));
        } catch (IOException e) {
            getLogger().warning("앵커 저장 실패: " + e.getMessage());
        }
    }

    private void loadAnchors() {
        File file = new File(getDataFolder(), "anchors.yml");
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String key : yml.getKeys(false)) {
            Location at = yml.getLocation(key);
            if (at != null) {
                anchors.put(key, at);
            }
        }
    }

    /** 정보 패널 — 우측 사이드바 (관리자 피드백: 표지판 외 상시 정보 채널) */
    public void updateSidebar(org.bukkit.entity.Player player) {
        org.bukkit.scoreboard.Scoreboard board =
                getServer().getScoreboardManager().getNewScoreboard();
        org.bukkit.scoreboard.Objective obj = board.registerNewObjective(
                "honcheon", org.bukkit.scoreboard.Criteria.DUMMY, "§6§l혼 천");
        obj.setDisplaySlot(org.bukkit.scoreboard.DisplaySlot.SIDEBAR);
        Zone zone = zoneAt(player.getLocation());
        boolean dojang = Dojang.isDojang(player.getWorld());
        PlayerLedger ledger = ledger(player.getUniqueId());
        // ★ 접합 여부와 경지를 **상시** 보여 준다 — 미접합자가 "조용히 반쪽 세계에서 노는" 것이
        //   이 게임의 가장 나쁜 상태였다. 화면이 늘 말해 준다: 너는 강호에 있는가, 없는가.
        if (ledger.linked()) {
            String name = WorldBridge.linkedName(player.getUniqueId());
            obj.getScore("§6" + ledger.realm(skillEngine.baseRealm())
                    + " §7" + (name == null ? "" : name)).setScore(5);
        } else {
            obj.getScore("§c강호에 없다 §7/혼천 접속").setScore(5);
        }
        obj.getScore("§7위치: §f" + (dojang ? "연무장 §8(시험)" : zone == null ? "야외" : zone.name()))
                .setScore(4);
        obj.getScore("§e소지금 " + ledger.money() + "문").setScore(3);
        // 수련 배분이 비면 **아무것도 자라지 않는다** (Growth.train 이 즉시 0 을 돌려준다).
        // 그 침묵이 첫 함정이었다 — 이제 화면이 그것을 말한다.
        obj.getScore(ledger.curriculum().isEmpty()
                ? "§c수련 없음 §7/혼천 수련"
                : "§7수련 §f" + ledger.curriculum().entrySet().stream()
                        .map(e -> e.getKey() + e.getValue())
                        .collect(java.util.stream.Collectors.joining(" "))).setScore(2);
        obj.getScore(String.format("§f오늘 수련 +%.1f일", ledger.grantedToday())).setScore(1);
        player.setScoreboard(board);
    }

    // ─── 구역 (입장 타이틀) — 조성이 만들고 zones.yml 이 기억한다 ───

    /** 전 구역 — 검수(TownAudit)가 폐사당 같은 앵커 없는 장소를 찾는 입력 */
    public java.util.List<Zone> zones() {
        return java.util.Collections.unmodifiableList(zones);
    }

    /** 위치가 속한 구역 — 중첩 시 부피가 작은 쪽 (건물 > 마을) */
    public Zone zoneAt(Location at) {
        Zone best = null;
        for (Zone zone : zones) {
            if (zone.contains(at) && (best == null || zone.volume() < best.volume())) {
                best = zone;
            }
        }
        return best;
    }

    public void setZones(java.util.List<Zone> built) {
        zones.clear();
        zones.addAll(built);
        YamlConfiguration yml = new YamlConfiguration();
        int i = 0;
        for (Zone z : zones) {
            String k = "zone" + i++;
            yml.set(k + ".name", z.name());
            yml.set(k + ".subtitle", z.subtitle());
            yml.set(k + ".world", z.world());
            yml.set(k + ".box", java.util.List.of(z.x1(), z.y1(), z.z1(), z.x2(), z.y2(), z.z2()));
        }
        try {
            yml.save(new File(getDataFolder(), "zones.yml"));
        } catch (java.io.IOException e) {
            getLogger().warning("구역 저장 실패: " + e.getMessage());
        }
    }

    private void loadZones() {
        File file = new File(getDataFolder(), "zones.yml");
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String key : yml.getKeys(false)) {
            java.util.List<Integer> box = yml.getIntegerList(key + ".box");
            if (box.size() == 6) {
                zones.add(new Zone(yml.getString(key + ".name", "?"),
                        yml.getString(key + ".subtitle", ""),
                        yml.getString(key + ".world", "world"),
                        box.get(0), box.get(1), box.get(2), box.get(3), box.get(4), box.get(5)));
            }
        }
        if (!zones.isEmpty()) {
            getLogger().info("구역 " + zones.size() + "곳 로드 (입장 타이틀)");
        }
    }

    // ─── 지역 원장(元帳) — 지역의 **첫 조성 때 잰 자연 지면** ───
    //
    // 재조성이 산 위에 산을 또 쌓고 있었다: 지면을 재는데 **지난번에 세운 산**을 읽었고,
    // 그 위에 다시 36켜를 얹었다 (조성할 때마다 화산파가 하늘로 자랐다).
    // 자연이 놓은 돌과 우리가 놓은 돌은 자재로는 구별되지 않는다 — 그러니 **기억해 둔다.**
    // 첫 조성이 잰 값이 그 지역의 지면이다. 그 뒤로는 몇 번을 다시 지어도 같은 자리에 선다.

    /**
     * 지역 원장 — <b>첫 조성이 정한 자리를 기억한다.</b>
     *
     * <p>★ 전에는 <b>지면 높이만</b> 적었다. 그래서 재조성할 때마다 <b>부지를 다시 찾았고</b> —
     * 지난번에 우리가 판 강이 땅을 바꿔 놓았으므로 <b>탐색이 다른 답을 냈다.</b>
     * 장강수로채가 재조성 한 번에 128칸을 옮겨 앉았고, 하지(河誌)는 옛 자리를 가리켰다.
     * <b>우리가 만든 것이 다음 탐색을 흔든다.</b>
     *
     * <p>주석은 <i>"몇 번을 다시 지어도 같은 자리에 선다"</i> 고 적혀 있었다 — <b>주석이 거짓말이었다.</b>
     * 좌표를 안 적으면 같은 자리에 안 선다. 이제 <b>x·y·z 를 함께</b> 적는다.
     */
    private final Map<String, int[]> regionBase = new HashMap<>();

    public Integer regionBase(String placeId) {
        int[] at = regionBase.get(placeId);
        return at == null ? null : at[1];
    }

    /** 기억된 자리 (x, y, z) — 없으면 null */
    public int[] regionSite(String placeId) {
        return regionBase.get(placeId);
    }

    public void setRegionBase(String placeId, int x, int groundY, int z) {
        regionBase.put(placeId, new int[]{x, groundY, z});
        YamlConfiguration yml = new YamlConfiguration();
        regionBase.forEach((id, at) -> {
            yml.set(id + ".x", at[0]);
            yml.set(id + ".y", at[1]);
            yml.set(id + ".z", at[2]);
        });
        try {
            yml.save(new File(getDataFolder(), "regions.yml"));
        } catch (IOException e) {
            getLogger().warning("지역 원장 저장 실패: " + e.getMessage());
        }
    }

    private void loadRegionBases() {
        File file = new File(getDataFolder(), "regions.yml");
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String key : yml.getKeys(false)) {
            if (yml.isConfigurationSection(key)) {
                regionBase.put(key, new int[]{
                        yml.getInt(key + ".x"), yml.getInt(key + ".y"), yml.getInt(key + ".z")});
            } else {
                // 옛 형식(지면 높이만) — 좌표를 모른다. 다시 지으면 자리가 흔들릴 수 있으므로 **버린다.**
                //   조용히 y 만 받아 두면 그것이 곧 "기억한 척"이다.
                getLogger().warning("[지역 원장] " + key + " — 옛 형식(높이만)이라 버린다. "
                        + "다시 조성하면 자리를 새로 정하고, 그 뒤로는 그 자리에 선다");
            }
        }
    }

    public JudgmentEngine judgment() {
        return judgment;
    }

    public ProgressionEngine progression() {
        return progression;
    }

    public EconomyEngine economy() {
        return economy;
    }

    public NpcLifecycleEngine lifecycle() {
        return lifecycle;
    }

    public PartyEngine party() {
        return party;
    }

    public SkillEngine skillEngine() {
        return skillEngine;
    }

    public Antechamber antechamber() {
        return antechamber;
    }

    public SkillListener skills() {
        return skills;
    }

    /** 세계 지도 — 등록 좌표·지형 적합성·여정 일수 (world_map.yml 이 없으면 null) */
    /** 원장이 아는 곳 — 한 번이라도 조성된 지역 */
    public java.util.Set<String> regionBaseIds() {
        return regionBase.keySet();
    }

    public WorldMap worldMap() {
        return worldMap;
    }

    /** 사냥터·산적·비무 — 적의 세 층 */
    public HuntingGrounds hunting() {
        return hunting;
    }

    /** 연무장 — 시험은 세계에 자국을 남기면 안 된다 */
    public Dojang dojang() {
        return dojang;
    }

    /** 연무장의 시험대 — 클릭으로 병기·무공·경지·적수를 고른다 */
    public DojangGui dojangGui() {
        return dojangGui;
    }

    /** 무명(無名) — 행인·주민. 세계가 사람이 사는 곳으로 보이려면 이들이 있어야 한다 */
    public Populace populace() {
        return populace;
    }
}
