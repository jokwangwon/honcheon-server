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

    /** 좌석(바닐라 보트)을 선체 속으로 가라앉히는 깊이 — 부품·패의 기준면은 이만큼 되올린다 */
    private static final double SEAT_SINK = 0.35;

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
    // ★조립 나룻배 (2026-07-25 실기동 "마크 보트라 … 뱃사공도 없고") — 등록부 voyage.barge
    private final int bargeLerp;
    private final boolean bargeFerryman;
    private final String bargeFerrymanName;
    private final double[] ferrymanAt;
    private final List<BargePart> bargeParts = new java.util.ArrayList<>();

    /** 나룻배 부품 하나 — 좌석(투명 보트) 중심 기준 상대 자리. 회전은 도(度) — yaw(Y)·pitch(X)·roll(Z:
     *  동진하는 배의 **앞들림**이 roll 이다. 이물 +14, 고물 -12 같은 값이 곡선을 흉내 낸다) */
    private record BargePart(double[] at, org.bukkit.Material block, float[] scale,
                             float yaw, float pitch, float roll) { }

    /** 물 위에 선 부품 하나 — 엔티티와 그 상대 자리 (배를 따라 미끄러진다) */
    private record Placed(UUID id, double[] at) { }

    /** 항해 중인 몸 하나 */
    private static final class Rider {
        UUID boat;                            // 좌석 — 투명한 바닐라 보트 (물리와 앉음새만 맡는다)
        WorldBridge.SeojangScene latest;      // 다리가 내려보낸 최신 장면 (writing 포함)
        String deliveredToken;                // 정거장에서 이미 연 장의 토큰 (두 번 열지 않는다)
        String stageSet;                      // ★2차 — 계열 (무대 등록부의 벌 이름 · 제목으로 판별)
        final java.util.List<String> transcript = new java.util.ArrayList<>();   // 필사본의 재료
        final java.util.List<Placed> barge = new java.util.ArrayList<>();   // 조립 나룻배 부품들
        UUID ferryman;                        // 배 위의 사공 (고물)
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
        // ── 조립 나룻배 등록부 (voyage.barge)
        @SuppressWarnings("unchecked")
        Map<String, Object> bg = cfg.get("barge") instanceof Map
                ? (Map<String, Object>) cfg.get("barge") : Map.of();
        this.bargeLerp = Math.max(0, num(bg.get("teleport_duration"), 2));
        this.bargeFerryman = !(bg.get("ferryman") instanceof Boolean fb) || fb;
        this.bargeFerrymanName = bg.get("ferryman_name") == null ? "사공"
                : String.valueOf(bg.get("ferryman_name"));
        this.ferrymanAt = dtriple(bg.get("ferryman_at"), -1.3, 0.0, -0.35);
        if (bg.get("parts") instanceof List<?> pl) {
            for (Object o : pl) {
                if (!(o instanceof Map<?, ?> pm)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) pm;
                org.bukkit.Material m;
                try {
                    m = org.bukkit.Material.valueOf(String.valueOf(p.get("block")));
                } catch (IllegalArgumentException e) {
                    continue;   // 등록부가 모르는 블록 — 그 부품만 비운다
                }
                bargeParts.add(new BargePart(dtriple(p.get("at"), 0, 0, 0), m,
                        ftriple(p.get("scale")),
                        p.get("yaw") instanceof Number n1 ? n1.floatValue() : 0f,
                        p.get("pitch") instanceof Number n2 ? n2.floatValue() : 0f,
                        p.get("roll") instanceof Number n3 ? n3.floatValue() : 0f));
            }
        }
    }

    private static double[] dtriple(Object o, double a, double b, double c) {
        double[] out = new double[]{a, b, c};
        if (o instanceof List<?> l) {
            for (int i = 0; i < Math.min(3, l.size()); i++) {
                if (l.get(i) instanceof Number n) {
                    out[i] = n.doubleValue();
                }
            }
        }
        return out;
    }

    private static float[] ftriple(Object o) {
        float[] out = new float[]{1f, 1f, 1f};
        if (o instanceof List<?> l) {
            for (int i = 0; i < Math.min(3, l.size()); i++) {
                if (l.get(i) instanceof Number n) {
                    out[i] = n.floatValue();
                }
            }
        }
        return out;
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

    /** 하선 — 배와 무대를 걷는다 (출도·퇴장·종료 공통). 항해 기억은 메모리뿐 — 진실은 봇의 명단이다 */
    void disembark(UUID body) {
        Rider r = riders.remove(body);
        if (r != null) {
            if (r.boat != null && Bukkit.getEntity(r.boat) instanceof Boat b) {
                b.remove();
            }
            clearBarge(r);
        }
        ante.stage().clear(body);
    }

    /** 필사본 한 장의 재료 — 장 머리말 + 제목 + 전문 (책과 같은 문법 · SeojangBook.headText) */
    private String transcriptOf(WorldBridge.SeojangScene scene) {
        String head = SeojangBook.get().headText(scene) + " — " + scene.title();
        String body = scene.narration() == null ? "" : scene.narration();
        return head + "\n\n" + body;
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
        clearBarge(r);
        double y = ante.waterTop(w) + 1.0;
        Location at = new Location(w, ante.cx() + atX + 0.5, y,
                ante.cz() + laneOf(player.getUniqueId()) + 0.5, -90f, 0f);
        // ★좌석 감춤 (실기동 스샷 2026-07-25 — 투명 플래그를 보트는 클라가 무시한다):
        //   중력을 끄고 선체 속으로 가라앉힌다 — 밑판·뱃전이 바닐라 보트를 삼킨다
        if (!bargeParts.isEmpty()) {
            at = at.clone().subtract(0, SEAT_SINK, 0);
        }
        Boat boat = (Boat) w.spawnEntity(at, EntityType.DARK_OAK_BOAT);
        boat.setPersistent(false);   // 항해는 메모리뿐 — 재기동이 배를 되살리지 않는다 (명단이 다시 띄운다)
        boat.setInvulnerable(true);
        // ★조립 나룻배 (실기동: "마크 보트라 … 이상함") — 바닐라 보트는 **숨은 좌석**일 뿐이다.
        //   눈에 보이는 배는 등록부(voyage.barge)의 조립 선체이고, 사공이 함께 올라탄다
        boat.setInvisible(!bargeParts.isEmpty());
        boat.setGravity(bargeParts.isEmpty());   // 부력이 좌석을 도로 띄우지 않게 (가라앉힌 채 유지)
        boat.getPersistentDataContainer().set(KEY_BOAT, PersistentDataType.BYTE, (byte) 1);
        r.boat = boat.getUniqueId();
        spawnBarge(w, r, at);
        // ★★ 조종권 (실사용 2026-07-25 "배를 움직이면 파츠가 분리 · 내가 배를 이동해서
        //   선택지를 클릭해야 함") — 보트의 첫 좌석이 곧 조종석이다. **사공이 먼저 탄다**:
        //   사람은 둘째 좌석이라 노를 못 젓고, 배는 코드(steer)만 몬다. 파츠 분리도 이것이
        //   병인이었다 — 사람이 저어 낸 속도를 선체 추종이 못 따라간 것.
        if (r.ferryman != null && Bukkit.getEntity(r.ferryman) instanceof org.bukkit.entity.Villager v) {
            boat.addPassenger(v);
        }
        player.teleport(at);
        boat.addPassenger(player);
    }

    /** 부품·패의 기준면 — 가라앉힌 좌석을 도로 올린 자리 (선체는 수면의 것이다) */
    private Location deck(Location seat) {
        return seat.clone().add(0, SEAT_SINK, 0);
    }

    /** 조립 나룻배 — 등록부의 부품들이 좌석을 따라 미끄러진다. 배는 모두에게 보인다 (실루엣 확정) */
    private void spawnBarge(World w, Rider r, Location sunkSeat) {
        Location seat = deck(sunkSeat);
        for (BargePart p : bargeParts) {
            Location at = seat.clone().add(p.at()[0], p.at()[1], p.at()[2]);
            org.bukkit.entity.BlockDisplay d = w.spawn(at, org.bukkit.entity.BlockDisplay.class, e -> {
                e.setBlock(p.block().createBlockData());
                e.setPersistent(false);
                e.setTeleportDuration(bargeLerp);
                e.setBrightness(new org.bukkit.entity.Display.Brightness(12, 15));
                e.getPersistentDataContainer().set(KEY_BOAT, PersistentDataType.BYTE, (byte) 1);
                // 회전 — 도(度)를 라디안으로.
                // ★피벗 (실기동 스샷 2026-07-25 "배가 분해되어 보임") — 디스플레이 회전은 **모서리
                //   원점** 기준이라, 중심 보정(-s/2)을 회전 **밖**에 두면 긴 판의 끝이 크게 튕긴다.
                //   보정 벡터를 회전에 태워(R × -s/2) 부품이 제 발치 중심으로 돌게 한다.
                org.joml.Quaternionf rot = new org.joml.Quaternionf().rotationYXZ(
                        (float) Math.toRadians(p.yaw()),
                        (float) Math.toRadians(p.pitch()),
                        (float) Math.toRadians(p.roll()));
                org.joml.Vector3f half =
                        new org.joml.Vector3f(-p.scale()[0] / 2f, 0f, -p.scale()[2] / 2f);
                rot.transform(half);
                e.setTransformation(new org.bukkit.util.Transformation(
                        half,
                        rot,
                        new org.joml.Vector3f(p.scale()[0], p.scale()[1], p.scale()[2]),
                        new org.joml.Quaternionf()));
            });
            r.barge.add(new Placed(d.getUniqueId(), p.at()));
        }
        if (bargeFerryman) {
            // 사공의 몸 — 보트의 **첫 좌석**에 앉는다 (spawnBoat 가 태운다: 첫 좌석 = 조종석 봉인)
            Location at = seat.clone().add(ferrymanAt[0], ferrymanAt[1], ferrymanAt[2]);
            at.setYaw(-90f);
            org.bukkit.entity.Villager v = w.spawn(at, org.bukkit.entity.Villager.class, e -> {
                e.setCustomName(bargeFerrymanName);
                e.setCustomNameVisible(false);   // 명패는 조용히 — 우클릭하면 보인다 (사공은 말이 없다)
                e.setAI(false);
                e.setSilent(true);
                e.setInvulnerable(true);
                e.setPersistent(false);
                e.getPersistentDataContainer().set(KEY_BOAT, PersistentDataType.BYTE, (byte) 1);
            });
            r.ferryman = v.getUniqueId();
        }
    }

    private void clearBarge(Rider r) {
        for (Placed p : r.barge) {
            Entity e = Bukkit.getEntity(p.id());
            if (e != null) {
                e.remove();
            }
        }
        r.barge.clear();
        if (r.ferryman != null && Bukkit.getEntity(r.ferryman) != null) {
            Bukkit.getEntity(r.ferryman).remove();
        }
        r.ferryman = null;
    }

    /** 배가 나아간 만큼 선체와 사공이 따라 미끄러진다 (teleport_duration 이 보간한다) */
    private void followBarge(Rider r, Boat boat) {
        // ★뱃머리 고정 — 보트 yaw 는 물살·충돌로 흐른다. 선체는 세계축 정렬이라 배가 돌면
        //   좌석(사람·사공)만 도는 그림이 된다 — 동진(-90)으로 매 틱 붙든다
        boat.setRotation(-90f, 0f);
        Location seat = deck(boat.getLocation());
        for (Placed p : r.barge) {
            if (Bukkit.getEntity(p.id()) instanceof org.bukkit.entity.BlockDisplay d) {
                d.teleport(seat.clone().add(p.at()[0], p.at()[1], p.at()[2]));
            }
        }
        // 사공은 보트의 첫 좌석에 타 있다 — 따로 옮길 것이 없다 (좌석이 데려간다)
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
            // ★실기동 (2026-07-25 "이으니까 바로 책을 받고 읽기 시작") — 접합 직후의 경주:
            //   다리(2초)가 승선(watchGate 5틱·명단 전파)보다 먼저 배달하면 책이 **부두에서**
            //   열렸다. 나루에 선 몸의 책은 승선을 기다린다 — 배가 정거장으로 데려간다
            //   (watchGate 세 문이 곧 태운다 — 굶는 몸은 없다). 나루 밖의 몸은 옛 몸짓
            //   그대로 즉시 편다 — 그쪽에서 붙들면 그것이 갇힘이다. 붓 소식(writing)은
            //   어디서든 통과 — 기다림 기계는 자리를 안 가린다.
            return Antechamber.isAntechamber(player.getWorld()) && !scene.writing();
        }
        r.latest = scene;
        if (scene.writing()) {
            return false;   // 붓 소식은 미루지 않는다 — 침묵 금지 기계가 맡는다
        }
        // ★붓이 내려왔다 (실사용: 무대 그릇에서 "적고 있다" 액션바가 안 걷혔다) — 책은 안 주지만
        //   기다림 기계는 지금 걷는다. 남은 기다림은 붓이 아니라 **항해**다 (배가 말한다)
        SeojangBook.get().settle(player);
        if (scene.token() != null && scene.token().equals(r.deliveredToken)) {
            // 이미 정거장에서 연 장 — 무대 그릇이면 책을 아예 안 준다 (2초 재배달이 책을 몰래
            // 쥐여 주면 그릇이 둘이 된다). 강등(책 그릇)일 때만 통과 — SeojangBook.given 이 소거한다
            return ante.stage().enabled();
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
            followBarge(r, boat);   // 선체와 사공이 좌석을 따라 미끄러진다
        }
    }

    /** 한 틱의 젓기 — 표적을 고르고, 그리로 아주 천천히 민다 (멈춤도 젓기의 한 값이다: 0) */
    private void steer(Player player, Rider r, Boat boat) {
        double x = boat.getLocation().getX() - ante.cx();
        // ★ 갇힘 금지 — 서장이 끝났거나 명단이 낡았다(봇 죽음): 곧장 기슭으로. 닿으면 출도다
        if (!WorldBridge.seojangHolds(player.getUniqueId())) {
            if (x >= shoreX) {
                UUID body = player.getUniqueId();
                // ★2차 — 필사본: 강을 건너며 겪은 기억의 전문이 품에 남는다 (개인 서사는 잃지 않는다)
                if (ante.stage().enabled() && ante.stage().memoirGive()
                        && !r.transcript.isEmpty()) {
                    SeojangBook.get().memoir(player, List.copyOf(r.transcript),
                            ante.stage().memoirLine(), ante.stage().memoirFullLine());
                }
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
            push(boat, 0);   // ★ 정거장 — 배가 멈추고, 그 자리에서 기억이 재생된다
            r.deliveredToken = r.latest.token();
            if (r.stageSet == null) {
                r.stageSet = ante.stage().detectSet(r.latest.scene(), r.latest.title());
            }
            r.transcript.add(transcriptOf(r.latest));
            // ★2차 (사용자: "글이 아닌 몸으로") — 책 대신 무대. 꺼져 있으면 옛 책 그릇으로 강등
            if (!ante.stage().play(player, player.getWorld(),
                    ante.cx() + stationX + 0.5, ante.waterTop(player.getWorld()) + 1.0,
                    deck(boat.getLocation()), r.latest, r.stageSet)) {
                SeojangBook.get().deliver(player, r.latest);
            }
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
