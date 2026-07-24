package com.honcheon.mvt;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>기억의 회랑 — 삼도천 항해</b> (B-179 · 사용자 확정 2026-07-24 · seojang_presentation.md §0).
 *
 * <p><b>강을 건너는 동안이 곧 서장이다.</b> 종을 울린(또는 접합한) 몸이 배에 오르고, 배가 안개
 * 물길을 <b>느리게</b> 동진한다. 기억의 정거장에 닿으면 배가 멈추고 그 장의 책이 열린다 —
 * 선택하면 다시 저어간다. 동쪽 기슭(이승의 불빛)에 닿는 순간이 곧 출도다.
 *
 * <p><b>★ 이 파일은 이야기를 모른다.</b> 글·선택지·판정·진행은 전부 봇의 것이고
 * ({@link WorldBridge.SeojangScene}), 책의 겉모습은 {@link SeojangBook} 의 것이다. 여기 있는 것은
 * <b>배와 물길</b>뿐이다 — 책이 열리는 <b>자리</b>와 사이의 <b>시간</b>만 소유한다.
 *
 * <p><b>★ 붓이 느린 것이 전제다</b> (실측: 서장 1건 ~22.4초 · GPU 하나). 항해 속도는 그 대기를
 * 덮도록 등록부가 정한다 (정거장 간격 ÷ speed ≥ 붓의 시간) — 평소에는 정지 없이 항해가 대기를
 * 흡수하고, 그래도 늦는 날만 정거장 앞 안개에서 멈춘다 (침묵 금지는 서책의 기다림 기계 —
 * 집필 액션바·사공의 말 — 가 그대로 맡는다).
 *
 * <p><b>★ 갇힘 금지가 먼저다</b>: 서장 명단이 낡으면(봇 죽음) 붙들지 않고 곧장 기슭으로 저어
 * 출도한다 · 재접속한 몸은 제 장면의 정거장 앞에서 다시 배에 오른다 · 물에 빠지면 배가 도로
 * 태운다 (나루 무피해 규약) · 장벽은 열지 않는다 — 승선이 몸을 옮긴다 (헤엄으로는 못 가는 물길).
 */
final class Voyage {

    /** 이 배가 항해의 배인가 — 기동·재조성 때 떠돌이 배를 걷는 표식 */
    static final NamespacedKey KEY_BOAT = new NamespacedKey("honcheon", "ipdo_voyage_boat");

    private final HoncheonMvt plugin;
    private final Antechamber ante;

    private final int startX;
    private final int[] stationsX;
    private final int shoreX;
    private final int[] lanesZ;
    private final double speedPerTick;   // 등록부는 칸/초 — 시계는 틱으로 산다
    private final int stallMargin;
    private final int frameZ;
    private final String embarkLine;

    /** 항해 중인 몸 하나 */
    private static final class Rider {
        UUID boat;                            // 배 엔티티
        WorldBridge.SeojangScene latest;      // 다리가 내려보낸 최신 장면 (writing 포함)
        String deliveredToken;                // 정거장에서 이미 편 책의 토큰 (두 번 열지 않는다)
    }

    private final Map<UUID, Rider> riders = new LinkedHashMap<>();
    private org.bukkit.scheduler.BukkitTask clock;

    Voyage(HoncheonMvt plugin, Antechamber ante, Map<String, Object> cfg) {
        this.plugin = plugin;
        this.ante = ante;
        this.startX = num(cfg.get("start_x"), 36);
        List<Integer> st = ints(cfg.get("stations_x"), List.of(44, 60, 76));
        this.stationsX = st.stream().mapToInt(Integer::intValue).toArray();
        this.shoreX = num(cfg.get("shore_x"), 92);
        List<Integer> ln = ints(cfg.get("lanes_z"), List.of(-4, 0, 4));
        this.lanesZ = ln.stream().mapToInt(Integer::intValue).toArray();
        double bps = cfg.get("speed_bps") instanceof Number n ? n.doubleValue() : 0.5;
        this.speedPerTick = Math.max(0.0, bps) / 20.0;
        this.stallMargin = Math.max(1, num(cfg.get("stall_margin"), 6));
        this.frameZ = Math.max(2, num(cfg.get("frame_z"), 8));
        this.embarkLine = cfg.get("embark_line") == null ? "" : String.valueOf(cfg.get("embark_line"));
    }

    /** 정거장 x 들 — 조성(plan ⑤-6)과 감사가 같은 등록부를 읽는다 */
    int[] stationsX() {
        return stationsX.clone();
    }

    /** 정거장 문(門)의 z 반폭 — 물길(lanes) 밖에 선다 */
    int frameZ() {
        return frameZ;
    }

    private static int num(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    private static List<Integer> ints(Object o, List<Integer> def) {
        if (o instanceof List<?> list && !list.isEmpty()) {
            List<Integer> out = new java.util.ArrayList<>();
            for (Object v : list) {
                if (v instanceof Number n) {
                    out.add(n.intValue());
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        return def;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  승선 · 하선
    // ══════════════════════════════════════════════════════════════════════

    boolean riding(UUID body) {
        return riders.containsKey(body);
    }

    /**
     * 승선 — 몸을 장벽 너머 배 위로 옮긴다 (문이 아니라 <b>의식</b>이다).
     * 부르는 쪽이 linked·seojangHolds 를 이미 확인했다 — 여기서는 배만 띄운다.
     */
    void embark(Player player) {
        if (riding(player.getUniqueId())) {
            return;
        }
        World w = player.getWorld();
        if (!Antechamber.isAntechamber(w)) {
            return;
        }
        Rider r = new Rider();
        riders.put(player.getUniqueId(), r);
        spawnBoat(w, player, r, startX);
        if (!embarkLine.isEmpty()) {
            player.sendMessage(SeojangBook.legacy(embarkLine));
        }
        ensureClock();
    }

    /** 하선 — 배를 걷는다 (출도·퇴장·종료 공통). 항해 기억은 메모리뿐이다 — 진실은 봇의 명단이다 */
    void disembark(UUID body) {
        Rider r = riders.remove(body);
        if (r != null && r.boat != null && Bukkit.getEntity(r.boat) instanceof Boat b) {
            b.remove();
        }
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

    /** 기동·재조성 때 — 주인 잃은 항해선을 걷는다 (표식이 있는 것만) */
    void sweepBoats(World w) {
        for (Entity e : w.getEntities()) {
            if (e.getPersistentDataContainer().has(KEY_BOAT)) {
                e.remove();
            }
        }
    }

    private int laneOf(UUID body) {
        return lanesZ[Math.floorMod(body.hashCode(), lanesZ.length)];
    }

    private void spawnBoat(World w, Player player, Rider r, int atX) {
        if (r.boat != null && Bukkit.getEntity(r.boat) instanceof Boat old) {
            old.remove();
        }
        double y = ante.waterTop(w) + 1.0;
        Location at = new Location(w, ante.cx() + atX + 0.5, y,
                ante.cz() + laneOf(player.getUniqueId()) + 0.5, -90f, 0f);
        Boat boat = (Boat) w.spawnEntity(at, EntityType.DARK_OAK_BOAT);
        boat.setPersistent(false);   // 항해는 메모리뿐 — 재기동이 배를 되살리지 않는다 (명단이 다시 띄운다)
        boat.setInvulnerable(true);
        boat.getPersistentDataContainer().set(KEY_BOAT, PersistentDataType.BYTE, (byte) 1);
        r.boat = boat.getUniqueId();
        player.teleport(at);
        boat.addPassenger(player);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  책의 문 — 정거장에서 열린다
    // ══════════════════════════════════════════════════════════════════════

    /**
     * {@link SeojangBook#deliver} 가 책을 펴기 <b>전에</b> 묻는다 — <b>미룰까?</b>
     *
     * <p>항해 중의 책은 정거장에서 열린다 (배가 그 자리로 데려간다). 미루는 것은 <b>펼침</b>뿐이다:
     * 집필 중(writing) 조각은 통과시켜 기다림 기계(액션바·사공의 말)가 그대로 돌게 한다.
     */
    boolean defer(Player player, WorldBridge.SeojangScene scene) {
        Rider r = riders.get(player.getUniqueId());
        if (r == null) {
            return false;   // 항해가 아니다 — 옛 몸짓 그대로 (부두 없이도 서장은 흐른다)
        }
        r.latest = scene;
        if (scene.writing()) {
            return false;   // 붓 소식은 미루지 않는다 — 침묵 금지 기계가 맡는다
        }
        if (scene.token() != null && scene.token().equals(r.deliveredToken)) {
            return false;   // 이미 정거장에서 폈다 — 중복 배달의 소거는 SeojangBook.given 이 맡는다
        }
        // 몸이 뒤처져 있으면(재접속) 배를 제 장면의 정거장 앞으로 옮긴다 — 80초 재항해는 벌이다
        relocateIfBehind(player, r, scene.scene());
        return !atStation(r, scene.scene());   // 정거장 전이면 미룬다 — 도착 틱이 다시 편다
    }

    private int stationIdx(int scene) {
        return Math.max(0, Math.min(scene, stationsX.length - 1));
    }

    private boolean atStation(Rider r, int scene) {
        if (!(Bukkit.getEntity(r.boat) instanceof Boat b)) {
            return false;
        }
        double dx = (ante.cx() + stationsX[stationIdx(scene)] + 0.5) - b.getLocation().getX();
        return Math.abs(dx) < 0.8;
    }

    private void relocateIfBehind(Player player, Rider r, int scene) {
        if (!(Bukkit.getEntity(r.boat) instanceof Boat b)) {
            return;
        }
        int target = stationsX[stationIdx(scene)];
        double x = b.getLocation().getX() - ante.cx();
        // 정거장 하나 넘게 뒤처졌다 = 재접속의 몸 — 제 기억 앞으로 (마지막 정거장의 배 위)
        if (target - x > (stationsX.length > 1 ? stationsX[1] - stationsX[0] : 16) + stallMargin) {
            spawnBoat(player.getWorld(), player, r, target - stallMargin);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  시계 — 배는 항상 저어가고 있다
    // ══════════════════════════════════════════════════════════════════════

    private void ensureClock() {
        if (clock != null) {
            return;
        }
        clock = Bukkit.getScheduler().runTaskTimer(plugin,
                Metrics.wrap("voyage", this::tick), 1L, 1L);
    }

    private void tick() {
        if (riders.isEmpty()) {
            return;
        }
        for (UUID body : List.copyOf(riders.keySet())) {
            Player player = Bukkit.getPlayer(body);
            if (player == null || !player.isOnline()
                    || !Antechamber.isAntechamber(player.getWorld())) {
                disembark(body);   // 떠난 몸 — 재접속하면 명단(봇)이 다시 태운다
                continue;
            }
            Rider r = riders.get(body);
            if (!(Bukkit.getEntity(r.boat) instanceof Boat boat) || !boat.isValid()) {
                spawnBoat(player.getWorld(), player, r,
                        r.latest == null ? startX : stationsX[stationIdx(r.latest.scene())]);
                continue;
            }
            // 물에 빠진 몸 — 배가 도로 태운다 (막지 않는다. 젖을 뿐이다)
            if (!boat.getPassengers().contains(player)) {
                player.teleport(boat.getLocation());
                boat.addPassenger(player);
            }
            steer(player, r, boat);
        }
    }

    /** 한 틱의 젓기 — 표적을 고르고, 그리로 아주 천천히 민다 (멈춤도 젓기의 한 값이다: 0) */
    private void steer(Player player, Rider r, Boat boat) {
        double x = boat.getLocation().getX() - ante.cx();
        // ★ 갇힘 금지 — 서장이 끝났거나 명단이 낡았다(봇 죽음): 곧장 기슭으로. 닿으면 출도다
        if (!WorldBridge.seojangHolds(player.getUniqueId())) {
            if (x >= shoreX) {
                UUID body = player.getUniqueId();
                disembark(body);
                ante.depart(player, List.of());
                return;
            }
            push(boat, shoreX - x);
            return;
        }
        if (r.latest == null) {
            // 첫 소식 전 — 첫 정거장 앞까지는 저어가도 된다 (다리는 2초 안에 온다)
            push(boat, capAt(stationsX[0] - stallMargin, x));
            return;
        }
        int idx = stationIdx(r.latest.scene());
        double stationX = stationsX[idx];
        if (r.latest.writing()) {
            // 붓이 아직 들려 있다 — 정거장 앞 안개까지가 이 배의 몫이다 (넘겨 짚지 않는다)
            push(boat, capAt(stationX - stallMargin, x));
            return;
        }
        if (r.latest.token() != null && r.latest.token().equals(r.deliveredToken)) {
            push(boat, 0);   // 읽는 중 — 배는 정거장에 매여 있다
            return;
        }
        double dx = stationX + 0.5 - (boat.getLocation().getX() - ante.cx());
        if (Math.abs(dx) < 0.8) {
            push(boat, 0);   // ★ 정거장 — 배가 멈추고, 그 자리에서 책이 열린다
            r.deliveredToken = r.latest.token();
            SeojangBook.get().deliver(player, r.latest);
            return;
        }
        push(boat, dx);
    }

    /** 남은 거리를 표적으로 — 지나치지 않게 깎는다 */
    private double capAt(double targetX, double x) {
        return Math.max(0, targetX - x);
    }

    /** 동쪽으로 민다 — 등록부의 속도로. dx ≤ 0 이면 선다 (배는 서쪽으로 되젓지 않는다) */
    private void push(Boat boat, double dx) {
        if (dx <= 0.01) {
            boat.setVelocity(new Vector(0, boat.getVelocity().getY(), 0));
            return;
        }
        double step = Math.min(speedPerTick, dx / 20.0 + speedPerTick * 0.5);
        boat.setVelocity(new Vector(step, boat.getVelocity().getY(), 0));
    }
}
