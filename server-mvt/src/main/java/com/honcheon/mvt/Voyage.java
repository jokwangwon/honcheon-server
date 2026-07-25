package com.honcheon.mvt;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>기억의 회랑 — 서장 월드의 나룻배</b> (B-179 · ★5차 개정 2026-07-25 사용자 확정:
 * <i>"서장은 나루 월드가 아닌 별도의 서장 월드 · 물로 된 필드 위 건축 나룻배 · 연출로
 * 이동감 · 1장 배에서 2장 배가 보이면 안 됨 — 한 배에 타고 있는 것처럼"</i>).
 *
 * <p>서장은 <b>서장 월드</b>에서 겪는다 — 끝없는 칠흑 밤바다(달빛) 위 건축 나룻배
 * <b>한 척</b>. 승선이 몸을 배 위로 옮기고, 장이 오면 그 갑판에서 기억의 무대가 서고,
 * 장 사이는 <b>가짜 항해</b>(4차 승계 — 사람도 배도 제자리, 세계가 흐른다: 물살·좌우 노
 * 박자·마중 안개)와 <b>눈깜빡임</b>이다. 눈을 뜨면 같은 배, 다음 장이다.
 *
 * <p>【묘비】 3~4차의 나루 물길 항해 — 정거장 넋등 문 3곳 · 정박 배 3척 · 팩 모델 셸 ·
 * 중간점 안개 장막: 별도 월드 확정으로 표적이 소멸했다 (git 2026-07-25 낮 판이 마지막
 * 모습). 그 전의 탈것 항해(1~2차)의 값(조종권 다툼·보트 노출·피벗 분해)도 그 판의 묘비에
 * 있다. <b>배는 하나뿐이어야 한다</b> — 되살리는 날, 「한 척뿐」 감사와 먼저 화해하라.
 *
 * <p><b>★ 이 파일은 이야기를 모른다.</b> 글·선택지·판정은 봇의 것, 무대는 {@link SeojangStage},
 * 문지방은 {@link Antechamber} 의 것이다. 여기 있는 것은 <b>바다와 배와 도하의 시계</b>뿐이다.
 *
 * <p><b>★ 갇힘 금지</b>: 명단이 낡으면(봇 죽음) 붙들지 않고 출도한다 · 재접속은 배 위에서
 * 다시 태우고(3방어), 리스폰은 나루로 돌아와 watchGate 가 다시 태운다.
 */
final class Voyage {

    /** 항해 소유물의 표식 — 걷을 때 우리 것만 걷는다 (지금은 무대·패가 SeojangStage 표식을 쓴다) */
    static final NamespacedKey KEY_BOAT = new NamespacedKey("honcheon", "ipdo_voyage_boat");
    /** 【묘비】 팩 모델 셸(4차)의 표식 — 5차로 폐지. 남은 셸을 걷는 손만 남는다 */
    static final NamespacedKey KEY_SHELL = new NamespacedKey("honcheon", "ipdo_barge_shell");
    /** 사공의 표식 — ensure 가 세고, 걷을 때 우리 것만 걷는다 (한 배에 한 사공) */
    static final NamespacedKey KEY_FERRYMAN = new NamespacedKey("honcheon", "ipdo_ferryman");

    // ★월드 키는 ASCII 소문자만 — 한글 이름은 NamespacedKey 가 거부한다 (첫 기동 실증 2026-07-25)
    private static String seaName = "honcheon_seojang";

    private final HoncheonMvt plugin;
    private final Antechamber ante;

    private final String seaBiome;
    private final long fixedTime;
    private final int halfLen;
    private final int halfW;
    private final int flowTicks;
    private final int rowPeriod;
    private final int blinkTicks;
    private final int fogRadius;
    private final int fogHeight;
    private final int fogDensity;
    private final String ferrymanName;
    private final int ferrymanX;
    private final String embarkLine;
    private final String transitLine;
    private final String rowSoundKey;

    /** 도하 중인 몸 하나 */
    private static final class Rider {
        WorldBridge.SeojangScene latest;      // 다리가 내려보낸 최신 장면 (writing 포함)
        String deliveredToken;                // 이 배에서 이미 연 장의 토큰
        String stageSet;                      // 계열 (무대 등록부의 벌 이름 · 제목으로 판별)
        int deck = -1;                        // 지금 연 장의 번호 (-1 = 아직 첫 장 전)
        boolean inTransit;                    // 도하 중 — 시계가 건드리지 않는다
        org.bukkit.scheduler.BukkitTask fx;  // 가짜 항해의 붓 (도하 동안만 산다)
        final java.util.List<String> transcript = new java.util.ArrayList<>();   // 필사본의 재료
    }

    private final Map<UUID, Rider> riders = new LinkedHashMap<>();
    private org.bukkit.scheduler.BukkitTask clock;

    Voyage(HoncheonMvt plugin, Antechamber ante, Map<String, Object> cfg) {
        this.plugin = plugin;
        this.ante = ante;
        Map<String, Object> wo = sub(cfg, "world");
        seaName = str(wo.get("name"), "honcheon_seojang");
        this.seaBiome = str(wo.get("biome"), "minecraft:deep_ocean");
        this.fixedTime = num(wo.get("fixed_time"), 18000);
        Map<String, Object> ba = sub(cfg, "barge");
        this.halfLen = Math.max(4, num(ba.get("half_len"), 6));
        this.halfW = Math.max(2, num(ba.get("half_w"), 2));
        Map<String, Object> tr = sub(cfg, "transit");
        this.flowTicks = Math.max(40, num(tr.get("flow_ticks"), 110));
        this.rowPeriod = Math.max(6, num(tr.get("row_period"), 22));
        this.blinkTicks = Math.max(4, num(tr.get("blink_ticks"), 12));
        this.transitLine = str(tr.get("line"),
                "§8노가 물을 가른다 — 물살이 뒤로 흘러가고, 안개가 마중을 나온다.");
        this.rowSoundKey = str(tr.get("row_sound"), "minecraft:entity.boat.paddle_water");
        Map<String, Object> fg = sub(cfg, "fog");
        this.fogRadius = Math.max(6, num(fg.get("radius"), 14));
        this.fogHeight = Math.max(2, num(fg.get("height"), 5));
        this.fogDensity = Math.max(1, num(fg.get("density"), 1));
        Map<String, Object> fm = sub(cfg, "ferryman");
        this.ferrymanName = str(fm.get("name"), "§7사공");
        this.ferrymanX = num(fm.get("x"), -3);
        this.embarkLine = str(cfg.get("embark_line"), "");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    private static int num(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  서장 월드 — 물뿐이다
    // ══════════════════════════════════════════════════════════════════════

    /** 여기가 서장 월드인가 — 소문·혈채·튜토리얼이 이 월드를 무시하는 근거 */
    static boolean isSea(World world) {
        return world != null && seaName.equals(world.getName());
    }

    /**
     * 서장 월드 — 없으면 만든다 (나루 {@code world()} 와 같은 문법: FLAT + 물 층 ·
     * <b>못 만들면 null, 그리고 아무도 여기 못 가둔다</b>). 규칙은 볼 때마다 다시 세운다 —
     * level.dat 에 저장된 옛 값이 고친 코드를 이기는 함정은 나루가 먼저 밟았다.
     */
    World sea() {
        World w = Bukkit.getWorld(seaName);
        if (w != null) {
            return configureSea(w);
        }
        try {
            w = new WorldCreator(seaName)
                    .type(WorldType.FLAT)
                    .generateStructures(false)
                    .generatorSettings("{\"layers\":[{\"block\":\"minecraft:stone\",\"height\":1},"
                            + "{\"block\":\"minecraft:water\",\"height\":8}],"
                            + "\"biome\":\"" + seaBiome + "\"}")
                    .createWorld();
        } catch (Throwable t) {
            plugin.getLogger().severe("[서장] 서장 월드를 열 수 없다 — " + t);
            return null;
        }
        return w == null ? null : configureSea(w);
    }

    private World configureSea(World w) {
        w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        w.setGameRule(GameRule.KEEP_INVENTORY, true);
        w.setGameRule(GameRule.MOB_GRIEFING, false);
        w.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        w.setGameRule(GameRule.FALL_DAMAGE, false);
        w.setGameRule(GameRule.DROWNING_DAMAGE, false);   // 물에 빠져도 안 죽는다 — 젖을 뿐이다
        w.setGameRule(GameRule.FIRE_DAMAGE, false);
        // ★칠흑 + 달빛 (사용자 확정) — 자정 고정. 어둠이 수평선을 지우고, 달이 물결과
        //   넋등만 남긴다. 격리는 벽이 아니라 밤이 한다.
        w.setTime(fixedTime);
        w.setStorm(false);
        return w;
    }

    /** 바다의 수면 — 지어내지 않고 월드에게 묻는다 (배가 손대지 않는 먼 자리에서 잰다) */
    int seaTop(World w) {
        return w.getHighestBlockYAt(512, 512);
    }

    /** 배의 닻 — 갑판 중심 (동쪽, 이물을 본다). 승선·도하·무대가 전부 이 한 점에 기댄다 */
    Location bargeAnchor(World w) {
        return new Location(w, 0.5, seaTop(w) + 1.0, 0.5, -90f, 0f);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  건축 나룻배 — 한 척 (사용자 확정: 중선 13×5 · 반블럭·계단 곡선)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 나룻배가 선다 — 원점에, <b>한 척만</b>. 좌표의 순수 함수다 (같은 등록부 → 언제나
     * 같은 배 · 멱등이라 기동마다 다시 세워도 같은 자리에 같은 몸이다).
     *
     * <p>몸: 짙은 참나무 몸통(물에 앉는 테두리) + 가문비 갑판 안칸 + 반블럭 뱃전 테 +
     * 계단 이물·고물 곡선 + 이물·고물 넋등 장대. 치수는 voyage.barge 등록부 (전장 13 · 폭 5).
     */
    void buildBarge(World w) {
        int gy = seaTop(w);
        int len = halfLen - 2;    // 몸통(직선부) 반길이 — 끝 2칸은 이물·고물 곡선의 것
        // ① 몸통 테두리 (y=수면 — 물에 앉는다): 짙은 참나무
        for (int dx = -len; dx <= len; dx++) {
            set(w, dx, gy, -halfW, Material.DARK_OAK_PLANKS);
            set(w, dx, gy, halfW, Material.DARK_OAK_PLANKS);
        }
        for (int dz = -(halfW - 1); dz <= halfW - 1; dz++) {
            set(w, -(len + 1), gy, dz, Material.DARK_OAK_PLANKS);   // 고물 어깨
            set(w, len + 1, gy, dz, Material.DARK_OAK_PLANKS);      // 이물 어깨
        }
        set(w, halfLen, gy, 0, Material.DARK_OAK_PLANKS);            // 이물 끝
        set(w, -halfLen, gy, 0, Material.DARK_OAK_PLANKS);           // 고물 끝
        // ② 갑판 안칸 (걷는 면): 가문비 널
        for (int dx = -len; dx <= len; dx++) {
            for (int dz = -(halfW - 1); dz <= halfW - 1; dz++) {
                set(w, dx, gy, dz, Material.SPRUCE_PLANKS);
            }
        }
        // ③ 뱃전 테 (y+1): 낮은 반블럭 — 시야를 안 가리고 배의 윤곽을 그린다
        for (int dx = -(len - 1); dx <= len - 1; dx++) {
            set(w, dx, gy + 1, -halfW, Material.SPRUCE_SLAB);
            set(w, dx, gy + 1, halfW, Material.SPRUCE_SLAB);
        }
        // ④ 이물·고물 곡선 (y+1): 계단이 좌우에서 오르며 좁아진다
        for (int side = -1; side <= 1; side += 2) {
            stair(w, len, gy + 1, side * halfW, "east");        // 몸통 끝 — 오르기 시작
            stair(w, -len, gy + 1, side * halfW, "west");
            stair(w, len + 1, gy + 1, side * (halfW - 1), "east");   // 어깨 — 좁아지며 오른다
            stair(w, -(len + 1), gy + 1, side * (halfW - 1), "west");
        }
        stair(w, halfLen, gy + 1, 0, "east");                    // 이물 코
        stair(w, -halfLen, gy + 1, 0, "west");                   // 고물 코
        // ⑤ 넋등 장대 — 이물·고물 (어깨 중앙 · 밤바다에서 배만 빛난다)
        for (int e = -1; e <= 1; e += 2) {
            int px = e * (len + 1);
            set(w, px, gy + 1, 0, Material.SPRUCE_FENCE);
            set(w, px, gy + 2, 0, Material.SOUL_LANTERN);
        }
    }

    private static void set(World w, int x, int y, int z, Material m) {
        w.getBlockAt(x, y, z).setType(m, false);
    }

    private static void stair(World w, int x, int y, int z, String facing) {
        BlockData d = Bukkit.createBlockData("minecraft:dark_oak_stairs[facing=" + facing + "]");
        w.getBlockAt(x, y, z).setBlockData(d, false);
    }

    /**
     * <b>사공이 탄다</b> (실기동 2026-07-25 "배에 뱃사공도 없어" — 2차 확정의 승계: 고물에
     * 사공이 실제로 탄다 · 삼도천의 삿대꾼은 이름이 없다). 빌리저 한 몸 — 조용히, AI 없이,
     * 고물 갑판에 서서 동쪽(가는 방향)을 본다. ensure 문법: 세고, 어긋나면 걷고 다시 세운다.
     */
    void ensureFerryman(World w) {
        int have = 0;
        for (org.bukkit.entity.Entity e : w.getEntities()) {
            if (e.getPersistentDataContainer().has(KEY_FERRYMAN)) {
                have++;
            }
        }
        if (have == 1) {
            return;
        }
        for (org.bukkit.entity.Entity e : w.getEntities()) {
            if (e.getPersistentDataContainer().has(KEY_FERRYMAN)) {
                e.remove();   // 둘이면 겹친 것이다 — 한 배에 한 사공
            }
        }
        Location at = new Location(w, ferrymanX + 0.5, seaTop(w) + 1.0, 0.5, -90f, 0f);
        w.spawn(at, org.bukkit.entity.Villager.class, v -> {
            v.setAI(false);
            v.setSilent(true);
            v.setInvulnerable(true);
            v.setCollidable(false);
            v.setPersistent(true);
            v.setRemoveWhenFarAway(false);
            v.setCustomName(ferrymanName);
            v.setCustomNameVisible(true);
            v.getPersistentDataContainer().set(KEY_FERRYMAN,
                    org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        });
    }

    /** 【묘비】 팩 모델 셸(4차) 걷기 — 나루에 남은 셸이 있으면 걷는다 (부활 금지의 손) */
    void sweepShells(World w) {
        for (org.bukkit.entity.Entity e : w.getEntities()) {
            if (e.getPersistentDataContainer().has(KEY_SHELL)) {
                e.remove();
            }
        }
    }

    /** 옛 탈것 잔재(표식 있는 것) 청소 — 지난 판이 남긴 배·선체가 있으면 걷는다 */
    void sweepBoats(World w) {
        for (org.bukkit.entity.Entity e : w.getEntities()) {
            if (e.getPersistentDataContainer().has(KEY_BOAT)) {
                e.remove();
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  승선 · 하선
    // ══════════════════════════════════════════════════════════════════════

    boolean riding(UUID body) {
        return riders.containsKey(body);
    }

    /**
     * 승선 — 몸이 서장 월드의 배 위로 옮는다 (의식이 옮긴다 — 헤엄으로는 영영 못 가는
     * 바다다). 나루에 선 몸(세 문: watchGate·명단 시계·종)과 서장 월드에서 재접속한
     * 몸(3방어)이 이 문으로 온다. 그 밖의 세계에서는 태우지 않는다.
     */
    void embark(Player player) {
        if (riding(player.getUniqueId()) || (!Antechamber.isAntechamber(player.getWorld())
                && !isSea(player.getWorld()))) {
            return;
        }
        World sea = sea();
        if (sea == null) {
            return;   // 바다를 못 열면 붙잡지 않는다 — 사람은 원래 자리에 그대로 선다
        }
        buildBarge(sea);   // 멱등 — 배 없는 바다에 사람을 내려놓지 않는다
        ensureFerryman(sea);   // 사공 없는 배도 배가 아니다 (한 배에 한 사공)
        riders.put(player.getUniqueId(), new Rider());
        player.teleport(bargeAnchor(sea));
        player.setFallDistance(0f);
        if (!embarkLine.isEmpty()) {
            player.sendMessage(SeojangBook.legacy(embarkLine));
        }
        player.playSound(player.getLocation(), rowSoundKey, 0.7f, 0.6f);
        ensureClock();
    }

    /** 하선 — 무대를 걷고 시계에서 내린다. 도하 기억은 메모리뿐 — 진실은 봇의 명단이다 */
    void disembark(UUID body) {
        Rider r = riders.remove(body);
        if (r != null && r.fx != null) {
            r.fx.cancel();
            r.fx = null;
        }
        ante.stage().clear(body);
    }

    void shutdownAll() {
        for (UUID body : List.copyOf(riders.keySet())) {
            disembark(body);
        }
        if (clock != null) {
            clock.cancel();
            clock = null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  책의 문 — 배 위에서 열린다
    // ══════════════════════════════════════════════════════════════════════

    /**
     * {@link SeojangBook#deliver} 가 책을 펴기 <b>전에</b> 묻는다 — <b>미룰까?</b>
     * 도하의 책은 배 위에서 열린다 (시계가 데려간다). 집필 조각은 통과 — 기다림 기계가 맡는다.
     */
    boolean defer(Player player, WorldBridge.SeojangScene scene) {
        Rider r = riders.get(player.getUniqueId());
        if (r == null) {
            // ★접합 직후의 경주 — 나루(또는 바다)에 선 몸의 책은 승선을 기다린다 (embark 가
            //   곧 태운다). 그 밖의 몸은 옛 몸짓 그대로 즉시 편다 — 붙들면 그것이 갇힘이다.
            return (Antechamber.isAntechamber(player.getWorld()) || isSea(player.getWorld()))
                    && !scene.writing();
        }
        r.latest = scene;
        if (scene.writing()) {
            return false;   // 붓 소식은 미루지 않는다 — 침묵 금지 기계가 맡는다
        }
        // ★붓이 내려왔다 — 책은 안 주지만 기다림 기계는 지금 걷는다 (남은 기다림은 도하다)
        SeojangBook.get().settle(player);
        if (scene.token() != null && scene.token().equals(r.deliveredToken)) {
            return ante.stage().enabled();   // 이 장은 이미 열었다 — 무대 그릇이면 책은 없다
        }
        return true;   // 장의 펼침은 시계(tick)가 맡는다 — 배 위에서 열린다
    }

    // ══════════════════════════════════════════════════════════════════════
    //  도하의 시계
    // ══════════════════════════════════════════════════════════════════════

    private void ensureClock() {
        if (clock != null) {
            return;
        }
        clock = Bukkit.getScheduler().runTaskTimer(plugin,
                Metrics.wrap("voyage", this::tick), 10L, 10L);
    }

    private void tick() {
        if (riders.isEmpty()) {
            return;
        }
        for (UUID body : List.copyOf(riders.keySet())) {
            Player player = Bukkit.getPlayer(body);
            if (player == null || !player.isOnline() || !isSea(player.getWorld())) {
                disembark(body);   // 떠난 몸 — 재접속하면 3방어와 명단이 다시 태운다
                continue;
            }
            Rider r = riders.get(body);
            if (r.inTransit) {
                continue;   // 흐르는 물 위 — 도하 예약이 끝을 맺는다
            }
            fogRing(player);   // ★안개 링 — 빈 수평선을 안개가 감싼다 (본인에게만)
            // ★ 갇힘 금지 — 서장이 끝났거나 명단이 낡았다(봇 죽음): 마지막 도하 = 출도
            if (!WorldBridge.seojangHolds(body)) {
                transit(player, r, -1);
                continue;
            }
            WorldBridge.SeojangScene scene = r.latest;
            if (scene == null || scene.writing()) {
                continue;   // 붓 또는 다리를 기다린다 — 기다림 기계(사공의 말·집필 액션바)가 말한다
            }
            int idx = Math.max(0, scene.scene());
            if (r.deck != idx) {
                transit(player, r, idx);   // ★ 가짜 항해 도하 — 같은 배, 다음 장
            } else if (scene.token() != null && !scene.token().equals(r.deliveredToken)) {
                open(player, r, scene);    // 같은 장 번호의 새 장 (에필로그가 3장에 잇따른다)
            }
        }
    }

    /**
     * <b>가짜 항해 도하</b> (★4차 승계 · 5차 — 같은 배 위에서): 사람도 배도 제자리,
     * <b>세계가 흐른다</b> — 물살이 뱃전을 뒤로 흘러가고, 노 박자가 좌우 번갈아 거듭되고,
     * 안개가 앞에서 마중을 나온다. 흐름의 끝에 눈깜빡임(짧은 어둠) — 눈을 뜨면 <b>같은 배</b>,
     * 다음 장이다 (1장의 배에서 2장의 배가 보이면 그것이 위반이다 — 배는 하나뿐).
     * {@code toDeck < 0} 은 마지막 도하다 — 눈을 뜨면 강호(출도)다.
     */
    private void transit(Player player, Rider r, int toDeck) {
        r.inTransit = true;
        ante.stage().clear(player.getUniqueId());
        if (!transitLine.isEmpty()) {
            player.sendMessage(SeojangBook.legacy(transitLine));
        }
        UUID body = player.getUniqueId();
        startFlow(player, r);
        // 눈깜빡임 — 어둠이 감기는 데 한 숨 걸린다: 흐름 끝 반 박자 앞에 감는다
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(body);
            if (p != null && p.isOnline() && riders.containsKey(body)) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS,
                        blinkTicks + 30, 0, false, false, false));
            }
        }, Math.max(1, flowTicks - 10));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(body);
            Rider still = riders.get(body);
            if (still != null && still.fx != null) {
                still.fx.cancel();
                still.fx = null;
            }
            if (p == null || !p.isOnline() || still == null) {
                return;
            }
            still.inTransit = false;
            if (toDeck < 0) {
                // 마지막 도하 — 필사본을 품에 넣고 강호에 선다
                if (ante.stage().enabled() && ante.stage().memoirGive()
                        && !still.transcript.isEmpty()) {
                    SeojangBook.get().memoir(p, List.copyOf(still.transcript),
                            ante.stage().memoirLine(), ante.stage().memoirFullLine());
                }
                disembark(body);
                ante.depart(p, List.of());
                return;
            }
            if (!isSea(p.getWorld())) {
                return;   // 그새 다른 손이 옮겼다 — 3방어가 다시 데려오면 시계가 잇는다
            }
            still.deck = toDeck;
            still.deliveredToken = null;   // 새 장 — 아직 안 열었다
            p.teleport(bargeAnchor(p.getWorld()));   // 같은 배의 닻으로 재정렬 (어둠 속에서)
            p.playSound(p.getLocation(), "minecraft:ambient.underwater.exit", 0.6f, 1.1f);
            WorldBridge.SeojangScene scene = still.latest;
            if (scene != null && !scene.writing() && Math.max(0, scene.scene()) == toDeck) {
                open(p, still, scene);
            }
        }, flowTicks + blinkTicks);
    }

    /**
     * <b>가짜 항해의 붓</b> — 도하 동안 세계가 흐른다 (전부 본인에게만 · 난수 없음:
     * 틱 위상의 순수 무늬다). ① 물살이 뱃전 양쪽을 뒤로(서쪽) 흘러간다 ② 이물이 물을
     * 가른다 ③ 노 박자가 좌우 번갈아 젓는다 ④ 안개가 앞(동쪽)에서 마중을 나온다 —
     * 흐를수록 짙어져 눈깜빡임에 잇는다.
     */
    private void startFlow(Player player, Rider r) {
        UUID body = player.getUniqueId();
        final int[] t = {0};
        r.fx = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player p = Bukkit.getPlayer(body);
            Rider still = riders.get(body);
            if (p == null || !p.isOnline() || still == null || still.fx == null
                    || t[0] >= flowTicks || !isSea(p.getWorld())) {
                if (still != null && still.fx != null) {
                    still.fx.cancel();
                    still.fx = null;
                }
                return;
            }
            World w = p.getWorld();
            double wy = seaTop(w) + 1.0;
            Location at = p.getLocation();
            // ① 물살 — 뱃전 양쪽의 흰 결이 뒤로 흘러간다 (배는 제자리 · 세계가 흐른다)
            for (int side = -1; side <= 1; side += 2) {
                double z = at.getZ() + side * (halfW + 1.3);
                double x = at.getX() + 3.5 - ((t[0] / 2 + (side > 0 ? 0 : 3)) % 8);
                p.spawnParticle(org.bukkit.Particle.CLOUD, x, wy + 0.15, z, 0, -1.0, 0.0, 0.0, 0.3);
            }
            // ② 이물 물보라 — 배가 물을 가른다
            if (t[0] % 6 == 0) {
                p.spawnParticle(org.bukkit.Particle.SPLASH,
                        at.getX() + halfLen + 1.0, wy, at.getZ(), 6, 0.3, 0.1, 0.8, 0.0);
            }
            // ③ 노 박자 — 좌·우 번갈아 젓는다
            if (t[0] % rowPeriod == 0) {
                int side = (t[0] / rowPeriod) % 2 == 0 ? 1 : -1;
                double oz = at.getZ() + side * (halfW + 1.0);
                Location oar = new Location(w, at.getX() - 1.5, wy, oz);
                p.playSound(oar, rowSoundKey, 0.9f, side > 0 ? 0.72f : 0.65f);
                p.spawnParticle(org.bukkit.Particle.SPLASH, oar.getX(), wy, oz, 10, 0.2, 0.1, 0.2, 0.0);
            }
            // ④ 안개가 마중 나온다 — 동쪽 반원 · 흐를수록 짙게 (눈깜빡임에 잇는다)
            int breath = 1 + (4 * t[0]) / Math.max(1, flowTicks);
            for (int k = 0; k < breath; k++) {
                double ang = Math.toRadians((t[0] * 29 + k * 133) % 180 - 90);   // 동쪽 반원
                double dist = 5.0 + ((t[0] / 2 + k * 5) % 4);
                p.spawnParticle(org.bukkit.Particle.CLOUD,
                        at.getX() + Math.cos(ang) * dist, wy + 0.5 + (k % 3),
                        at.getZ() + Math.sin(ang) * dist, 2, 0.6, 0.5, 0.6, 0.01);
            }
            t[0] += 2;
        }, 0L, 2L);
    }

    /**
     * <b>안개 링</b> (★5차 — 장막의 상속자): 별도 월드라 가릴 배는 없지만, 빈 수평선이
     * 세계를 좁힌다 — 배 주위 등거리 링에 낮은 안개가 숨쉰다 (항해자의 눈에만 · 달빛 아래
     * 흰 띠). 자리는 틱 위상의 순수 무늬다 — 난수 없음.
     */
    private void fogRing(Player player) {
        World w = player.getWorld();
        double wy = seaTop(w) + 1.0;
        long beat = w.getGameTime() / 10;
        Location at = player.getLocation();
        for (int k = 0; k < 6; k++) {
            double ang = Math.toRadians((beat * 7 + k * 60) % 360);
            player.spawnParticle(org.bukkit.Particle.CLOUD,
                    at.getX() + Math.cos(ang) * fogRadius,
                    wy + fogHeight / 2.0,
                    at.getZ() + Math.sin(ang) * fogRadius,
                    fogDensity, 1.2, fogHeight / 2.0, 1.2, 0.004);
        }
    }

    /** 갑판 위에서 장이 열린다 — 무대(그릇)가 서고, 꺼져 있으면 옛 책으로 강등 */
    private void open(Player player, Rider r, WorldBridge.SeojangScene scene) {
        r.deliveredToken = scene.token();
        if (r.stageSet == null) {
            r.stageSet = ante.stage().detectSet(scene.scene(), scene.title());
        }
        r.transcript.add(transcriptOf(scene));
        World w = player.getWorld();
        Location anchor = bargeAnchor(w);
        if (!ante.stage().play(player, w, anchor.getX(), seaTop(w) + 1.0, anchor,
                scene, r.stageSet)) {
            SeojangBook.get().deliver(player, scene);   // 강등 — 무대가 꺼져 있으면 책이 온다
        }
    }

    /** 필사본 한 장의 재료 — 장 머리말 + 제목 + 전문 (책과 같은 문법 · SeojangBook.headText) */
    private String transcriptOf(WorldBridge.SeojangScene scene) {
        String head = SeojangBook.get().headText(scene) + " — " + scene.title();
        String body = scene.narration() == null ? "" : scene.narration();
        return head + "\n\n" + body;
    }
}
