package com.honcheon.mvt;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>기억의 회랑 — 삼도천 도하(渡河)</b> (B-179 · ★3차 개정 2026-07-25 사용자 확정:
 * <i>"굳이 좌석에 앉아서 갈 필요가 있을까"</i> → <b>정박 무대 + 암전 도하</b>).
 *
 * <p>배는 이제 <b>각 정거장에 실블록으로 정박해 있고</b> ({@link Antechamber} plan ⑤-7),
 * 사람은 갑판에 <b>서서</b> 기억의 무대를 겪고 패를 우클릭한다. 장 사이는 <b>암전 도하</b>다 —
 * 화면이 어두워지고, 물소리와 노 소리가 지나가고, 다음 갑판에서 눈을 뜬다.
 *
 * <p>【묘비】 1~2차의 <b>탈것 항해</b>(바닐라 보트 좌석 + 디스플레이 조립 선체 + 속도 젓기) —
 * 실기동이 그 편법의 값을 다 보여 줬다: 조종권 다툼 · 숨긴 보트의 노출 · 회전 피벗 분해 ·
 * 30초 활강의 루즈함. 좌석을 버리자 전부가 함께 사라졌다. 되살릴 일이 있거든 git 의
 * 2026-07-25 이전 판을 보라.
 *
 * <p><b>★ 이 파일은 이야기를 모른다.</b> 글·선택지·판정은 봇의 것, 무대는 {@link SeojangStage},
 * 갑판은 조성 판의 것이다. 여기 있는 것은 <b>도하의 시계</b>뿐이다.
 *
 * <p><b>★ 갇힘 금지</b>: 명단이 낡으면(봇 죽음) 붙들지 않고 출도한다 · 재접속·죽음은
 * {@link Antechamber} 의 3방어가 나루로 되돌리고, 이 시계가 제 갑판으로 도하시킨다.
 */
final class Voyage {

    /** 항해 소유물의 표식 — 걷을 때 우리 것만 걷는다 (지금은 무대·패가 SeojangStage 표식을 쓴다) */
    static final NamespacedKey KEY_BOAT = new NamespacedKey("honcheon", "ipdo_voyage_boat");
    /** 정박 나룻배 겉몸(팩 모델 셸)의 표식 — ensure 가 세고, 걷을 때 우리 것만 걷는다 */
    static final NamespacedKey KEY_SHELL = new NamespacedKey("honcheon", "ipdo_barge_shell");

    private final HoncheonMvt plugin;
    private final Antechamber ante;

    private final int[] stationsX;
    private final int shoreX;
    private final int frameZ;
    private final int mooredWest;
    private final int mooredEast;
    private final int mooredHalfW;
    private final String shellModel;
    private final float shellScale;
    private final double shellY;
    private final int transitTicks;
    private final String embarkLine;
    private final String transitLine;
    private final String rowSoundKey;

    /** 도하 중인 몸 하나 */
    private static final class Rider {
        WorldBridge.SeojangScene latest;      // 다리가 내려보낸 최신 장면 (writing 포함)
        String deliveredToken;                // 이 갑판에서 이미 연 장의 토큰
        String stageSet;                      // 계열 (무대 등록부의 벌 이름 · 제목으로 판별)
        int deck = -1;                        // 지금 선 갑판 (-1 = 아직 부두)
        boolean inTransit;                    // 암전 도하 중 — 시계가 건드리지 않는다
        final java.util.List<String> transcript = new java.util.ArrayList<>();   // 필사본의 재료
    }

    private final Map<UUID, Rider> riders = new LinkedHashMap<>();
    private org.bukkit.scheduler.BukkitTask clock;

    Voyage(HoncheonMvt plugin, Antechamber ante, Map<String, Object> cfg) {
        this.plugin = plugin;
        this.ante = ante;
        this.stationsX = ints(cfg.get("stations_x"), List.of(44, 60, 76)).stream()
                .mapToInt(Integer::intValue).toArray();
        this.shoreX = num(cfg.get("shore_x"), 92);
        this.frameZ = Math.max(2, num(cfg.get("frame_z"), 8));
        Map<String, Object> mo = sub(cfg, "moored");
        this.mooredWest = Math.max(1, num(mo.get("west"), 3));
        this.mooredEast = Math.max(1, num(mo.get("east"), 2));
        this.mooredHalfW = Math.max(1, num(mo.get("half_w"), 1));
        Map<String, Object> sh = sub(mo, "shell");
        this.shellModel = str(sh.get("model"), "");
        this.shellScale = sh.get("scale") instanceof Number n2 ? n2.floatValue() : 2.2f;
        this.shellY = sh.get("y") instanceof Number n3 ? n3.doubleValue() : 0.95;
        Map<String, Object> tr = sub(cfg, "transit");
        this.transitTicks = Math.max(20, num(tr.get("ticks"), 50));
        this.transitLine = str(tr.get("line"),
                "§8노 소리가 어둠을 가른다 — 물이 갈라지고, 배가 나아간다.");
        this.rowSoundKey = str(tr.get("row_sound"), "minecraft:entity.boat.paddle_water");
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

    /** 정거장 x 들 — 조성(plan ⑤-6·⑤-7)과 감사가 같은 등록부를 읽는다 */
    int[] stationsX() {
        return stationsX.clone();
    }

    /** 정거장 문(門)의 z 반폭 — 갑판 밖에 선다 */
    int frameZ() {
        return frameZ;
    }

    /** 정박 갑판의 몸 — 조성 판(⑤-7)이 읽는다 (서·동·반폭) */
    int mooredWest() {
        return mooredWest;
    }

    int mooredEast() {
        return mooredEast;
    }

    int mooredHalfW() {
        return mooredHalfW;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  승선 · 하선
    // ══════════════════════════════════════════════════════════════════════

    boolean riding(UUID body) {
        return riders.containsKey(body);
    }

    /** 승선 — 도하의 시계에 오른다. 첫 장이 오면 첫 갑판으로 암전 도하한다 */
    void embark(Player player) {
        if (riding(player.getUniqueId()) || !Antechamber.isAntechamber(player.getWorld())) {
            return;
        }
        riders.put(player.getUniqueId(), new Rider());
        if (!embarkLine.isEmpty()) {
            player.sendMessage(SeojangBook.legacy(embarkLine));
        }
        ensureClock();
    }

    /** 하선 — 무대를 걷고 시계에서 내린다. 도하 기억은 메모리뿐 — 진실은 봇의 명단이다 */
    void disembark(UUID body) {
        riders.remove(body);
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

    /** 옛 탈것 잔재(표식 있는 것) 청소 — 지난 판이 남긴 배·선체가 있으면 걷는다 */
    void sweepBoats(World w) {
        for (org.bukkit.entity.Entity e : w.getEntities()) {
            if (e.getPersistentDataContainer().has(KEY_BOAT)) {
                e.remove();
            }
        }
    }

    /**
     * 정박 나룻배의 <b>겉몸</b> — 팩 모델 셸 (사용자: "리소스팩을 수정해서라도 디자인적 개선").
     * 갑판 블록은 발판이고, 이 ItemDisplay 모델이 배의 윤곽(들린 이물·고물·뱃전)이다.
     * 허수아비 ensure 와 같은 문법: 세고, 어긋나면 걷고 다시 세운다 (많은 것도 틀린 것이다).
     */
    void ensureMooredShells(World w) {
        if (shellModel.isBlank()) {
            return;   // 등록부가 겉몸을 안 적었다 — 갑판 블록만으로 선다 (강등)
        }
        int have = 0;
        for (org.bukkit.entity.Entity e : w.getEntities()) {
            if (e.getPersistentDataContainer().has(KEY_SHELL)) {
                have++;
            }
        }
        if (have == stationsX.length) {
            return;
        }
        for (org.bukkit.entity.Entity e : w.getEntities()) {
            if (e.getPersistentDataContainer().has(KEY_SHELL)) {
                e.remove();
            }
        }
        int gy = ante.waterTop(w);
        String[] mk = shellModel.split(":", 2);
        NamespacedKey model = new NamespacedKey(mk.length == 2 ? mk[0] : "honcheon",
                mk.length == 2 ? mk[1] : mk[0]);
        for (int sx : stationsX) {
            Location at = new Location(w, ante.cx() + sx - 0.5, gy + shellY, ante.cz() + 0.5, 0f, 0f);
            w.spawn(at, org.bukkit.entity.ItemDisplay.class, e -> {
                org.bukkit.inventory.ItemStack it =
                        new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER);
                it.editMeta(m -> m.setItemModel(model));
                e.setItemStack(it);
                e.setPersistent(true);   // 세계의 가구다 — 재기동에도 남는다 (ensure 가 수를 지킨다)
                e.setBrightness(new org.bukkit.entity.Display.Brightness(12, 15));
                e.getPersistentDataContainer().set(KEY_SHELL,
                        org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
                e.setTransformation(new org.bukkit.util.Transformation(
                        new org.joml.Vector3f(), new org.joml.Quaternionf(),
                        new org.joml.Vector3f(shellScale, shellScale, shellScale),
                        new org.joml.Quaternionf()));
            });
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  책의 문 — 갑판에서 열린다
    // ══════════════════════════════════════════════════════════════════════

    /**
     * {@link SeojangBook#deliver} 가 책을 펴기 <b>전에</b> 묻는다 — <b>미룰까?</b>
     * 도하의 책은 갑판에서 열린다 (시계가 데려간다). 집필 조각은 통과 — 기다림 기계가 맡는다.
     */
    boolean defer(Player player, WorldBridge.SeojangScene scene) {
        Rider r = riders.get(player.getUniqueId());
        if (r == null) {
            // ★접합 직후의 경주 — 나루에 선 몸의 책은 승선을 기다린다 (embark 세 문이 곧 태운다).
            //   나루 밖의 몸은 옛 몸짓 그대로 즉시 편다 — 그쪽에서 붙들면 그것이 갇힘이다.
            return Antechamber.isAntechamber(player.getWorld()) && !scene.writing();
        }
        r.latest = scene;
        if (scene.writing()) {
            return false;   // 붓 소식은 미루지 않는다 — 침묵 금지 기계가 맡는다
        }
        // ★붓이 내려왔다 — 책은 안 주지만 기다림 기계는 지금 걷는다 (남은 기다림은 도하다)
        SeojangBook.get().settle(player);
        if (scene.token() != null && scene.token().equals(r.deliveredToken)) {
            return ante.stage().enabled();   // 이 갑판에서 이미 열었다 — 무대 그릇이면 책은 없다
        }
        return true;   // 갑판 도하는 시계(tick)가 맡는다 — 배달은 그 위에서 열린다
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
            if (player == null || !player.isOnline()
                    || !Antechamber.isAntechamber(player.getWorld())) {
                disembark(body);   // 떠난 몸 — 재접속하면 3방어와 명단이 다시 태운다
                continue;
            }
            Rider r = riders.get(body);
            if (r.inTransit) {
                continue;   // 어둠 속 — 도하 예약이 끝을 맺는다
            }
            // ★ 갇힘 금지 — 서장이 끝났거나 명단이 낡았다(봇 죽음): 마지막 도하 = 출도
            if (!WorldBridge.seojangHolds(body)) {
                transit(player, r, -1);
                continue;
            }
            WorldBridge.SeojangScene scene = r.latest;
            if (scene == null || scene.writing()) {
                continue;   // 붓 또는 다리를 기다린다 — 기다림 기계(사공의 말·집필 액션바)가 말한다
            }
            int idx = Math.max(0, Math.min(scene.scene(), stationsX.length - 1));
            if (r.deck != idx) {
                transit(player, r, idx);   // ★ 암전 도하 — 다음 갑판으로
            } else if (scene.token() != null && !scene.token().equals(r.deliveredToken)) {
                open(player, r, scene);    // 같은 갑판의 새 장 (에필로그가 3장 갑판에 잇따른다)
            }
        }
    }

    /**
     * <b>암전 도하</b> — 화면이 어두워지고, 노 소리가 지나가고, 다음 갑판에서 눈을 뜬다.
     * {@code toDeck < 0} 은 마지막 도하다 — 어둠이 걷히면 강호(출도)다.
     */
    private void transit(Player player, Rider r, int toDeck) {
        r.inTransit = true;
        ante.stage().clear(player.getUniqueId());
        player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS,
                transitTicks + 25, 0, false, false, false));
        if (!transitLine.isEmpty()) {
            player.sendMessage(SeojangBook.legacy(transitLine));
        }
        player.playSound(player.getLocation(), rowSoundKey, 0.8f, 0.7f);
        UUID body = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(body);
            Rider still = riders.get(body);
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
            if (!Antechamber.isAntechamber(p.getWorld())) {
                return;   // 그새 다른 손이 옮겼다 — 3방어가 다시 데려오면 시계가 잇는다
            }
            still.deck = toDeck;
            still.deliveredToken = null;   // 새 갑판 — 이 갑판의 장은 아직 안 열었다
            p.teleport(ante.deckAnchor(p.getWorld(), stationsX[toDeck]));
            p.playSound(p.getLocation(), "minecraft:ambient.underwater.exit", 0.6f, 1.1f);
            WorldBridge.SeojangScene scene = still.latest;
            if (scene != null && !scene.writing()
                    && Math.max(0, Math.min(scene.scene(), stationsX.length - 1)) == toDeck) {
                open(p, still, scene);
            }
        }, transitTicks);
    }

    /** 갑판 위에서 장이 열린다 — 무대(그릇)가 서고, 꺼져 있으면 옛 책으로 강등 */
    private void open(Player player, Rider r, WorldBridge.SeojangScene scene) {
        r.deliveredToken = scene.token();
        if (r.stageSet == null) {
            r.stageSet = ante.stage().detectSet(scene.scene(), scene.title());
        }
        r.transcript.add(transcriptOf(scene));
        World w = player.getWorld();
        int sx = stationsX[Math.max(0, Math.min(scene.scene(), stationsX.length - 1))];
        Location anchor = ante.deckAnchor(w, sx);
        if (!ante.stage().play(player, w, ante.cx() + sx + 0.5,
                ante.waterTop(w) + 1.0, anchor, scene, r.stageSet)) {
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
