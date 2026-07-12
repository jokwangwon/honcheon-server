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
        this.judgment = new JudgmentEngine(RulesConfig.load(cfg.resolve("judgment.yml")));
        this.progression = new ProgressionEngine(
                RulesConfig.load(cfg.resolve("cultivation.yml")), RulesConfig.load(cfg.resolve("training.yml")));
        this.economy = new EconomyEngine(RulesConfig.load(cfg.resolve("economy.yml")));
        this.lifecycle = new NpcLifecycleEngine(
                RulesConfig.load(cfg.resolve("npc_lifecycle.yml")), RulesConfig.load(cfg.resolve("judgment.yml")));
        this.party = new PartyEngine(RulesConfig.load(cfg.resolve("party.yml")));
        this.skillEngine = new SkillEngine(cfg);
        this.worldMap = WorldMap.load(cfg);   // 세계 지도 — world_map.yml (없으면 null)
        Weapons.init(cfg);   // 병기 제작소 — equipment.yml·combat.yml 판독
        HuntingGrounds.init(cfg);   // 적 등록부 — 짐승·사람 스탯 (npcs·npc_combat·combat)
        Populace.init(cfg);   // 인구 등록부 — 행인·주민 28인 (npcs/populace.yml)
        this.skills = new SkillListener(this, skillEngine);
        this.hunting = new HuntingGrounds(this);
        this.populace = new Populace(this);

        getServer().getPluginManager().registerEvents(new HuntListener(this), this);
        getServer().getPluginManager().registerEvents(new ZoneListener(this), this);
        getServer().getPluginManager().registerEvents(new TradeListener(this), this);
        getServer().getPluginManager().registerEvents(new LedgerGui(this), this);
        getServer().getPluginManager().registerEvents(new WeaponShop(this), this);
        getServer().getPluginManager().registerEvents(skills, this);
        getServer().getPluginManager().registerEvents(hunting, this);
        getServer().getPluginManager().registerEvents(populace, this);
        getServer().getPluginManager().registerEvents(hunting.sparring(), this);
        skills.start();   // 중앙 티커 1개 (performance.yml F-P2)
        hunting.start();  // 중앙 티커 — 구역 스포너·전의·비무 판정
        populace.start();   // 중앙 티커 — 행인의 일과·배회 (마을이 비어 있으면 세계가 아니다)
        getCommand("honcheon").setExecutor(new MvtCommand(this));
        loadAnchors();
        loadZones();
        // 정보 패널 (사이드바) — 5초 주기 갱신: 위치·소지금·오늘 수련
        getServer().getScheduler().runTaskTimer(this,
                () -> getServer().getOnlinePlayers().forEach(this::updateSidebar), 100L, 100L);
        // 등록부가 누구를 무인으로 세웠는지 기동 때 말한다 — config 에 적었는데 코드가 안 읽으면
        // 그 NPC 는 조용히 맨손으로 선다(곽진이 그랬다: realm 이 없어 '전투에 서지 않는 사람'이었다).
        java.util.List<String> fighters = skillEngine.manifestingNpcs();
        getLogger().info("격을 쓰는 NPC " + fighters.size() + "인: "
                + (fighters.isEmpty() ? "없음 — 등록부에 realm 이 없다" : String.join(", ", fighters)));
        getLogger().info("혼천 MVT 기동 — 룰 엔진 5종 로드 완료 (/혼천 도움말)"
                + (anchors.isEmpty() ? " — 청하현 미조성 (/혼천 조성)" : " — 청하현 앵커 " + anchors.size() + "곳"));
    }

    public PlayerLedger ledger(UUID playerId) {
        return ledgers.computeIfAbsent(playerId, id -> new PlayerLedger());
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
        PlayerLedger ledger = ledger(player.getUniqueId());
        obj.getScore("§7위치: §f" + (zone == null ? "야외" : zone.name())).setScore(3);
        obj.getScore("§e소지금 " + ledger.money() + "문").setScore(2);
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

    public SkillListener skills() {
        return skills;
    }

    /** 세계 지도 — 등록 좌표·지형 적합성·여정 일수 (world_map.yml 이 없으면 null) */
    public WorldMap worldMap() {
        return worldMap;
    }

    /** 사냥터·산적·비무 — 적의 세 층 */
    public HuntingGrounds hunting() {
        return hunting;
    }

    /** 무명(無名) — 행인·주민. 세계가 사람이 사는 곳으로 보이려면 이들이 있어야 한다 */
    public Populace populace() {
        return populace;
    }
}
