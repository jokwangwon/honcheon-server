package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;

import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 입도진(入道津) — <b>강호에 들기 전에 서는 자리</b>.
 *
 * <p>사용자 판정: <i>"시작부터 필드에 떨구지 말고, 접속 전 대기실로 첫 소환을 당하여, 강호에 들어오면
 * 세계로 들어오게끔."</i>
 *
 * <p><b>왜 나루인가.</b> 지어낸 자리가 아니다 — {@code world_map.yml} 이 이미 답을 적어 뒀다:
 * 흑수나루(pos [118, 12], terrain 물가)는 청하현의 물길이고, <i>"청하현이 왜 나루를 갖는가의 답"</i>이다.
 * 나루는 이 세계가 이미 가진 <b>문지방</b>이다. 강호에 든다는 것은 강을 건너는 것이고, 건네주는 것은 사공이다.
 * 그리고 <b>사공은 이름 없는 자를 태우지 않는다</b> — 나룻배는 장부에 적고 건넨다.
 * 그것이 이 세계의 접합(接合)이 무엇인지에 대한 정확한 은유다. <b>문은 배다.</b>
 *
 * <p><b>불변식</b>:
 * <ul>
 *   <li><b>떨구지 않는다</b> — 첫 접속은 필드가 아니라 별도 월드({@code honcheon_ipdo})다.
 *       낙사·굶주림·몹이 없다. 평면 월드의 지면은 y5 가 아니라 <b>y-61</b> 이므로 좌표는
 *       지어내지 않고 월드에게 묻는다 ({@code getHighestBlockYAt}).</li>
 *   <li><b>가두지 않는다</b> — 문(종)은 언제나 거기 있고, 열리는 조건은 하나다: <b>강호에 이름이 올랐는가</b>.
 *       그리고 <b>봇이 꺼져 있으면(worldDay ≤ 0) 사공은 그냥 건넨다</b> ({@code gate.bridge_down}).
 *       다리가 죽었다고 사람이 나루에 갇히면 그것은 초대가 아니라 사고다.</li>
 *   <li><b>과제는 문을 잠그지 않는다</b> ({@code lessons.gating: false}) — 과제 하나가 깨진 날 사람이 갇힌다.</li>
 *   <li><b>읽는 것이 아니라 해 보는 것</b> — 표지판에 적어 두고 끝내지 않는다. 허수아비를 세우고 치게 하고,
 *       실제 조작(EntityDamage · 몸짓 술어 · 명령)을 <b>실제로 봤을 때만</b> 과제가 닫힌다.</li>
 *   <li><b>못 하는 것을 가르치지 않는다</b> — 범인은 격을 두를 수 없다
 *       ({@link SkillEngine#armableGrades}가 빈다). 그 사람에게 "격을 둘러라"라고 적힌 판은 <b>뜨지 않는다</b>.</li>
 *   <li><b>문장·좌표·조건은 {@code config/antechamber.yml} 이 정본</b> — 코드가 지어내지 않는다.</li>
 *   <li><b>난수 없음</b> — 무늬는 좌표 해시({@code Math.floorMod}). 조성은 {@link TickBudget#slice} 로 나눠 먹인다.</li>
 * </ul>
 *
 * <p><b>글은 표지판이 아니다.</b> 표지판은 글자가 작고 코앞까지 가야 읽히고 네 줄에 묶인다.
 * 안내는 {@link TextDisplay} 로 띄운다 — {@code billboard CENTER}(어디서 보든 정면) ·
 * {@code scale}(크기) · 먹빛 배경(바닐라 반투명 검정 상자를 쓰지 않는다. 이 세계는 수묵이다).
 * 상주 엔티티이므로 <b>반드시 걷는다</b> — 재조성·종료 시 {@link #clearPanels} 가 쓸어낸다
 * (이 프로젝트는 "보이지 않는 호랑이가 세계에 남는" 병을 이미 겪었다).
 */
public final class Antechamber implements Listener {

    /** 이 세계의 상주물 표식 — 재조성·종료 때 이것만 보고 걷는다 (두 겹으로 겹치지 않는다) */
    private static final NamespacedKey KEY_PANEL = new NamespacedKey("honcheon", "ipdo_panel");
    private static final NamespacedKey KEY_DUMMY = new NamespacedKey("honcheon", "ipdo_dummy");

    private static String worldName = "honcheon_ipdo";

    private final HoncheonMvt plugin;

    // ─── 등록부 (config/antechamber.yml 이 정본) ───
    private final String displayName;
    private final int cx;
    private final int cz;
    private final int[] yardX;
    private final int[] yardZ;
    private final int wallHeight;
    private final int[] dockX;
    private final int[] dockZ;
    private final int[] riverX;
    private final int[] riverZ;
    private final int riverDepth;
    private final int[] bell;
    private final int[] boat;
    private final int lanternEvery;
    private final List<int[]> dummySpots;
    private final int dummyDurability;
    private final String dummyName;

    private final int leash;
    private final String mistLine;

    private final boolean bridgeDownAllows;
    private final List<String> bridgeDownLines;
    private final String refuseTitle;
    private final String refuseSubtitle;
    private final List<String> refuseLines;
    private final String openedTitle;
    private final String openedSubtitle;
    private final List<String> openedLines;
    private final int autoCrossSeconds;
    private final List<String> crossedLines;
    private final List<String> destinations;

    private final String arrivalTitle;
    private final String arrivalSubtitle;
    private final List<String> arrivalLines;
    private final String revisitLine;

    private final boolean kitGive;
    private final String kitLine;
    private final String kitTakeBackLine;

    private final boolean lessonsGate;
    private final String doneLine;
    private final String allDoneTitle;
    private final String allDoneSubtitle;
    private final List<String> allDoneLines;
    private final Map<String, Lesson> lessons = new LinkedHashMap<>();

    private final Panels panelSpec;
    private final List<Panel> panelList = new ArrayList<>();

    // ─── 장부 (사람마다) ───
    /** 과제 진척 — 사람 → (과제 id → 횟수) */
    private final Map<UUID, Map<String, Integer>> progress = new HashMap<>();
    /** 방어 몸짓 — 본 것들 (isBlocking·isSneaking·isSprinting) */
    private final Map<UUID, Set<String>> gesturesSeen = new HashMap<>();
    /** 격 태세 — 직전에 두르고 있던 것 (바뀌는 순간을 본다) */
    private final Map<UUID, String> lastArmed = new HashMap<>();
    /** 나루에 두고 온 짐 — 나루의 목검·방패를 강호로 가져가지 않는다 (연무장의 규약) */
    private final Map<UUID, ItemStack[]> stowed = new HashMap<>();
    /** 배가 뜬 것을 이미 알린 몸 (자동 도강 예약 중복 방지) */
    private final Set<UUID> boarding = new LinkedHashSet<>();
    /** 세운 글판 — id → 엔티티 (사람마다 보이고 안 보이고를 여기서 가른다) */
    private final Map<String, UUID> panelEntities = new LinkedHashMap<>();

    private org.bukkit.scheduler.BukkitTask ticker;
    private boolean building;

    // ══════════════════════════════════════════════════════════════════════
    //  등록부 읽기
    // ══════════════════════════════════════════════════════════════════════

    Antechamber(HoncheonMvt plugin, Path configDir) {
        this.plugin = plugin;
        Map<String, Object> root = RulesConfig.load(configDir.resolve("antechamber.yml"));
        Map<String, Object> a = RulesConfig.section(root, "antechamber");

        worldName = str(a.get("world"), "honcheon_ipdo");
        this.displayName = str(a.get("display_name"), "입도진");
        List<Integer> center = ints(a.get("center"));
        this.cx = center.size() > 0 ? center.get(0) : 0;
        this.cz = center.size() > 1 ? center.get(1) : 0;

        Map<String, Object> layout = RulesConfig.section(a, "layout");
        Map<String, Object> yard = RulesConfig.section(layout, "yard");
        this.yardX = pair(yard.get("x"), -14, 19);
        this.yardZ = pair(yard.get("z"), -14, 14);
        this.wallHeight = num(RulesConfig.section(layout, "wall").get("height"), 3);
        Map<String, Object> dock = RulesConfig.section(layout, "dock");
        this.dockX = pair(dock.get("x"), 15, 24);
        this.dockZ = pair(dock.get("z"), -2, 2);
        Map<String, Object> river = RulesConfig.section(layout, "river");
        this.riverX = pair(river.get("x"), 20, 52);
        this.riverZ = pair(river.get("z"), -32, 32);
        this.riverDepth = Math.max(1, num(river.get("depth"), 3));
        this.bell = pair(layout.get("bell"), 24, 0);
        this.boat = pair(layout.get("boat"), 27, 0);
        this.lanternEvery = Math.max(2, num(layout.get("lantern_every"), 6));
        this.dummySpots = new ArrayList<>();
        if (layout.get("dummies") instanceof List<?> ds) {
            ds.forEach(d -> dummySpots.add(pair(d, 0, 0)));
        }
        this.dummyDurability = num(layout.get("dummy_durability"), 20);
        this.dummyName = str(layout.get("dummy_name"), "§7허수아비");

        Map<String, Object> mist = RulesConfig.section(a, "mist");
        this.leash = Math.max(24, num(mist.get("leash_blocks"), 96));
        this.mistLine = str(mist.get("line"), "");

        Map<String, Object> gate = RulesConfig.section(a, "gate");
        Map<String, Object> down = RulesConfig.section(gate, "bridge_down");
        this.bridgeDownAllows = !(down.get("allow_passage") instanceof Boolean b) || b;
        this.bridgeDownLines = lines(down.get("lines"));
        Map<String, Object> refuse = RulesConfig.section(gate, "refuse");
        this.refuseTitle = str(refuse.get("title"), "");
        this.refuseSubtitle = str(refuse.get("subtitle"), "");
        this.refuseLines = lines(refuse.get("lines"));
        Map<String, Object> opened = RulesConfig.section(gate, "opened");
        this.openedTitle = str(opened.get("title"), "");
        this.openedSubtitle = str(opened.get("subtitle"), "");
        this.openedLines = lines(opened.get("lines"));
        this.autoCrossSeconds = num(opened.get("auto_cross_seconds"), 0);
        this.crossedLines = lines(RulesConfig.section(gate, "crossed").get("lines"));
        this.destinations = lines(gate.get("destinations"));

        Map<String, Object> arrival = RulesConfig.section(a, "arrival");
        this.arrivalTitle = str(arrival.get("title"), displayName);
        this.arrivalSubtitle = str(arrival.get("subtitle"), "");
        this.arrivalLines = lines(arrival.get("lines"));
        this.revisitLine = str(arrival.get("revisit_line"), "");

        Map<String, Object> kit = RulesConfig.section(a, "kit");
        this.kitGive = !(kit.get("give") instanceof Boolean g) || g;
        this.kitLine = str(kit.get("line"), "");
        this.kitTakeBackLine = str(kit.get("take_back_line"), "");

        Map<String, Object> les = RulesConfig.section(a, "lessons");
        this.lessonsGate = les.get("gating") instanceof Boolean lg && lg;
        this.doneLine = str(les.get("done_line"), "§a✔ §f{title}");
        Map<String, Object> allDone = RulesConfig.section(les, "all_done");
        this.allDoneTitle = str(allDone.get("title"), "");
        this.allDoneSubtitle = str(allDone.get("subtitle"), "");
        this.allDoneLines = lines(allDone.get("lines"));
        if (les.get("list") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> l = (Map<String, Object>) m;
                Lesson lesson = new Lesson(
                        str(l.get("id"), ""),
                        str(l.get("title"), ""),
                        str(l.get("how"), ""),
                        str(l.get("detect"), ""),
                        Math.max(1, num(l.get("count"), 1)),
                        str(l.get("done"), ""),
                        new LinkedHashSet<>(lines(l.get("gestures"))),
                        str(l.get("command"), ""),
                        l.get("needs_args") instanceof Boolean na && na,
                        l.get("requires_armable_grade") instanceof Boolean ra && ra,
                        str(l.get("unavailable"), ""));
                if (!lesson.id().isEmpty()) {
                    lessons.put(lesson.id(), lesson);
                }
            }
        }

        Map<String, Object> td = RulesConfig.section(a, "text_display");
        this.panelSpec = new Panels(
                num(td.get("max_panels"), 16),
                (float) dbl(td.get("scale"), 1.6),
                (float) dbl(td.get("view_range"), 2.0),
                num(td.get("line_width"), 240),
                dbl(td.get("y_offset"), 2.4),
                str(td.get("billboard"), "CENTER"),
                str(td.get("alignment"), "CENTER"),
                td.get("see_through") instanceof Boolean st && st,
                !(td.get("shadowed") instanceof Boolean sh) || sh,
                argb(td.get("background_argb"), 0xC8140F0C),
                argb(td.get("accent_argb"), 0xC8241A12),
                num(RulesConfig.section(td, "brightness").get("block"), 12),
                num(RulesConfig.section(td, "brightness").get("sky"), 15),
                str(td.get("title_prefix"), "§6§l"));

        if (a.get("panels") instanceof List<?> ps) {
            for (Object o : ps) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) m;
                int[] pos = pair(p.get("pos"), 0, 0);
                String fromLesson = str(p.get("from_lesson"), "");
                String onlyIf = str(p.get("only_if"), "");
                List<String> text;
                if (!fromLesson.isEmpty()) {
                    Lesson l = lessons.get(fromLesson);
                    if (l == null) {
                        plugin.getLogger().warning("[입도진] 없는 과제를 가리키는 글판: " + fromLesson);
                        continue;
                    }
                    // ★ 판의 문장은 과제의 문장 그대로다 — 판과 과제가 다른 말을 하는 일이 불가능해진다
                    text = List.of(panelSpec.titlePrefix() + l.title(),
                            "not_armable".equals(onlyIf) ? l.unavailable() : l.how());
                } else {
                    text = lines(p.get("lines"));
                }
                panelList.add(new Panel(str(p.get("id"), ""), pos[0], pos[1], text,
                        p.get("accent") instanceof Boolean ac && ac, onlyIf));
            }
        }
        if (panelList.size() > panelSpec.maxPanels()) {
            // 상주 엔티티의 상한 — 조용히 넘기지 않는다. 넘치는 것은 세우지 않고, 그렇다고 말한다
            plugin.getLogger().warning("[입도진] 글판이 상한을 넘는다 — "
                    + panelList.size() + " > " + panelSpec.maxPanels() + " (넘는 것은 세우지 않는다)");
            while (panelList.size() > panelSpec.maxPanels()) {
                panelList.remove(panelList.size() - 1);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  월드
    // ══════════════════════════════════════════════════════════════════════

    /** 여기가 나루인가 — 세계 다리·사냥·소문이 이 월드를 무시하는 근거 (Dojang.isDojang 의 대응물) */
    public static boolean isAntechamber(World world) {
        return world != null && worldName.equals(world.getName());
    }

    public static String worldName() {
        return worldName;
    }

    /**
     * 나루 월드 — 없으면 만든다. <b>못 만들면 null 을 준다 (그리고 아무도 여기 못 가둔다)</b>:
     * 부르는 쪽은 null 을 보면 <b>아무것도 하지 않는다</b> — 사람은 원래 있던 자리에 그대로 선다.
     */
    World world() {
        World w = Bukkit.getWorld(worldName);
        if (w != null) {
            return w;
        }
        try {
            w = new WorldCreator(worldName)
                    .type(WorldType.FLAT)
                    .generateStructures(false)
                    .generatorSettings("{\"layers\":[{\"block\":\"minecraft:stone\",\"height\":1},"
                            + "{\"block\":\"minecraft:dirt\",\"height\":2},"
                            + "{\"block\":\"minecraft:grass_block\",\"height\":1}],"
                            + "\"biome\":\"minecraft:plains\"}")
                    .createWorld();
        } catch (Throwable t) {
            plugin.getLogger().severe("[입도진] 나루를 열 수 없다 — " + t);
            return null;
        }
        if (w == null) {
            return null;
        }
        // 나루에서는 죽지 않는다. 강호에 들지 않은 자가 대기실에서 죽으면 그것은 초대가 아니다
        w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        w.setGameRule(GameRule.KEEP_INVENTORY, true);
        w.setGameRule(GameRule.MOB_GRIEFING, false);
        w.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        w.setGameRule(GameRule.FALL_DAMAGE, false);
        w.setGameRule(GameRule.DROWNING_DAMAGE, false);
        w.setGameRule(GameRule.FIRE_DAMAGE, false);
        w.setGameRule(GameRule.NATURAL_REGENERATION, true);
        w.setDifficulty(Difficulty.PEACEFUL);
        w.setTime(1000);          // 아침 — 나루는 언제나 아침이다
        w.setStorm(false);
        return w;
    }

    /** 평면 월드의 지면 — <b>지어내지 않고 월드에게 묻는다</b> (y5 가 아니라 y-61 이다. 한 번 데였다).
     *  조성이 손대지 않는 먼 자리에서 재므로, 우리가 강을 파도 이 값은 안 흔들린다. */
    private int groundY(World w) {
        return w.getHighestBlockYAt(cx + 512, cz + 512);
    }

    /** 딛는 자리 — 마당의 중심 */
    Location spawnAt(World w) {
        return new Location(w, cx + 0.5, groundY(w) + 1.0, cz + 0.5, 90f, 0f);   // 동쪽(나루)을 본다
    }

    private boolean built(World w) {
        return w.getBlockAt(cx + bell[0], groundY(w) + 1, cz + bell[1]).getType() == Material.BELL;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  조성 — 틱 슬라이싱 (한 틱에 다 지으면 서버가 선다)
    // ══════════════════════════════════════════════════════════════════════

    /** 나루를 짓는다. 이미 서 있으면 아무것도 안 한다. {@code force} 면 다시 짓는다 (글판은 걷고 다시 세운다). */
    void build(World w, boolean force, Runnable onDone) {
        if (building) {
            return;
        }
        if (built(w) && !force) {
            ensurePanels(w);
            ensureDummies(w);
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        building = true;
        final int gy = groundY(w);
        final List<Place> plan = plan(gy);
        final int[] i = {0};
        TickBudget.slice(plugin, "입도진", () -> {
            if (i[0] >= plan.size()) {
                return false;
            }
            Place p = plan.get(i[0]++);
            Block b = w.getBlockAt(p.x(), p.y(), p.z());
            if (b.getType() != p.m()) {
                b.setType(p.m(), false);   // 물리 없이 — 물이 흘러 마당을 덮으면 안 된다
            }
            return true;
        }, () -> {
            building = false;
            clearPanels(w);       // ★ 다시 지으면 글이 두 겹으로 겹치면 안 된다
            clearDummies(w);
            spawnPanels(w);
            ensureDummies(w);
            plugin.getLogger().info("[입도진] 나루가 섰다 — 블록 " + plan.size()
                    + " · 글판 " + panelEntities.size() + " · 허수아비 " + dummySpots.size());
            if (onDone != null) {
                onDone.run();
            }
        });
    }

    /** 조성 판 — 난수 없음. 무늬는 좌표 해시(Math.floorMod)다 (프로젝트 규약) */
    private List<Place> plan(int gy) {
        List<Place> out = new ArrayList<>();
        // ① 강 — 흑수(黑水). 저편은 물안개다
        for (int x = riverX[0]; x <= riverX[1]; x++) {
            for (int z = riverZ[0]; z <= riverZ[1]; z++) {
                for (int d = 0; d < riverDepth; d++) {
                    out.add(new Place(cx + x, gy - d, cz + z, Material.WATER));
                }
                out.add(new Place(cx + x, gy - riverDepth, cz + z, Material.GRAVEL));
            }
        }
        // ② 마당 — 객잔 뒷마당. 무늬는 해시다
        for (int x = yardX[0]; x <= yardX[1]; x++) {
            for (int z = yardZ[0]; z <= yardZ[1]; z++) {
                boolean path = Math.floorMod(x + z, 7) == 0;
                out.add(new Place(cx + x, gy, cz + z,
                        path ? Material.COBBLESTONE : Material.PACKED_MUD));
                for (int y = gy + 1; y <= gy + 5; y++) {   // 머리 위를 비운다 (허공에 갇히지 않게)
                    out.add(new Place(cx + x, y, cz + z, Material.AIR));
                }
            }
        }
        // ③ 담 — 동쪽(나루 쪽)만 열려 있다. 갇힘이 아니라 **뒷마당**이다
        for (int x = yardX[0]; x <= yardX[1]; x++) {
            for (int y = gy + 1; y <= gy + wallHeight; y++) {
                out.add(new Place(cx + x, y, cz + yardZ[0], wallBlock(x, y)));
                out.add(new Place(cx + x, y, cz + yardZ[1], wallBlock(x, y)));
            }
        }
        for (int z = yardZ[0]; z <= yardZ[1]; z++) {
            for (int y = gy + 1; y <= gy + wallHeight; y++) {
                out.add(new Place(cx + yardX[0], y, cz + z, wallBlock(z, y)));
            }
        }
        // ④ 부두 — 마당에서 물로. 널판이 물 위를 건넌다
        for (int x = dockX[0]; x <= dockX[1]; x++) {
            for (int z = dockZ[0]; z <= dockZ[1]; z++) {
                out.add(new Place(cx + x, gy, cz + z, Material.DARK_OAK_PLANKS));
                for (int y = gy + 1; y <= gy + 4; y++) {
                    out.add(new Place(cx + x, y, cz + z, Material.AIR));
                }
            }
            if (Math.floorMod(x, 3) == 0) {   // 말뚝 — 물에 박힌 기둥
                for (int d = 1; d <= riverDepth; d++) {
                    out.add(new Place(cx + x, gy - d, cz + dockZ[0], Material.DARK_OAK_FENCE));
                    out.add(new Place(cx + x, gy - d, cz + dockZ[1], Material.DARK_OAK_FENCE));
                }
            }
        }
        // ⑤ 종 — ★ 이것이 문이다
        out.add(new Place(cx + bell[0], gy + 1, cz + bell[1], Material.BELL));
        // ⑥ 나룻배 — 매여 있다 (사공이 오면 뜬다). 물 위의 널판 한 조각
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                out.add(new Place(cx + boat[0] + x, gy, cz + boat[1] + z,
                        Math.abs(x) + Math.abs(z) == 2 ? Material.WATER : Material.SPRUCE_PLANKS));
            }
        }
        // ⑦ 등롱 — 결정론 간격
        for (int x = yardX[0]; x <= yardX[1]; x++) {
            for (int z = yardZ[0]; z <= yardZ[1]; z++) {
                if (Math.floorMod(x - yardX[0], lanternEvery) == 0
                        && Math.floorMod(z - yardZ[0], lanternEvery) == 0
                        && !(x == 0 && z == 0)) {
                    out.add(new Place(cx + x, gy + 1, cz + z, Material.LANTERN));
                }
            }
        }
        return out;
    }

    private Material wallBlock(int a, int y) {
        return Math.floorMod(a + y, 5) == 0 ? Material.MUD_BRICKS : Material.MUD_BRICK_WALL;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  글판 — TextDisplay (표지판이 아니다)
    // ══════════════════════════════════════════════════════════════════════

    /** 이 세계에 남은 우리 글판을 <b>전부</b> 걷는다 — 재조성 때 글이 겹치는 것을 원천 차단한다 */
    void clearPanels(World w) {
        if (w == null) {
            return;
        }
        for (Entity e : w.getEntities()) {
            if (e instanceof TextDisplay && e.getPersistentDataContainer().has(KEY_PANEL)) {
                e.remove();
            }
        }
        panelEntities.clear();
    }

    private void ensurePanels(World w) {
        // 이미 서 있는 우리 판을 주워 담는다 (재기동 뒤에도 사람마다 보이고 안 보이고를 다시 가를 수 있게)
        panelEntities.clear();
        for (Entity e : w.getEntities()) {
            if (e instanceof TextDisplay && e.getPersistentDataContainer().has(KEY_PANEL)) {
                String id = e.getPersistentDataContainer()
                        .get(KEY_PANEL, PersistentDataType.STRING);
                if (id != null && panelEntities.putIfAbsent(id, e.getUniqueId()) != null) {
                    e.remove();   // 같은 id 가 둘이면 그것은 겹친 것이다 — 하나만 남긴다
                }
            }
        }
        if (panelEntities.size() != panelList.size()) {
            clearPanels(w);
            spawnPanels(w);
        }
    }

    private void spawnPanels(World w) {
        int gy = groundY(w);
        for (Panel p : panelList) {
            Location at = new Location(w, cx + p.x() + 0.5,
                    gy + 1 + panelSpec.yOffset(), cz + p.z() + 0.5);
            TextDisplay d = w.spawn(at, TextDisplay.class, e -> {
                e.setText(String.join("\n", p.lines()));
                e.setBillboard(billboard(panelSpec.billboard()));
                e.setAlignment(alignment(panelSpec.alignment()));
                e.setLineWidth(panelSpec.lineWidth());
                e.setViewRange(panelSpec.viewRange());
                e.setSeeThrough(panelSpec.seeThrough());
                e.setShadowed(panelSpec.shadowed());
                e.setDefaultBackground(false);   // ★ 바닐라 반투명 검정 상자를 쓰지 않는다 (수묵)
                e.setBackgroundColor(Color.fromARGB(
                        p.accent() ? panelSpec.accentArgb() : panelSpec.backgroundArgb()));
                e.setBrightness(new Display.Brightness(panelSpec.blockLight(), panelSpec.skyLight()));
                e.setTransformation(new Transformation(
                        new Vector3f(), new AxisAngle4f(),
                        new Vector3f(panelSpec.scale(), panelSpec.scale(), panelSpec.scale()),
                        new AxisAngle4f()));
                e.setPersistent(true);
                e.getPersistentDataContainer().set(KEY_PANEL, PersistentDataType.STRING, p.id());
            });
            panelEntities.put(p.id(), d.getUniqueId());
        }
    }

    /**
     * <b>사람마다 다르게 보인다.</b> 범인에게 "Shift+우클릭으로 격을 둘러라"라고 적힌 판이 보이면
     * <b>그것이 거짓말이다</b> — {@link SkillEngine#armableGrades}가 비면 그 조작은 존재하지 않는다.
     * 그 사람에게는 "아직 두를 격이 없다" 판이 대신 뜬다 (같은 자리 · 다른 사람 · 겹치지 않는다).
     */
    void refreshPanels(Player player) {
        boolean armable = armable(player);
        for (Panel p : panelList) {
            UUID id = panelEntities.get(p.id());
            if (id == null || !(Bukkit.getEntity(id) instanceof TextDisplay d)) {
                continue;
            }
            boolean show = switch (p.onlyIf()) {
                case "armable" -> armable;
                case "not_armable" -> !armable;
                default -> true;
            };
            if (show) {
                player.showEntity(plugin, d);
            } else {
                player.hideEntity(plugin, d);
            }
        }
    }

    private boolean armable(Player player) {
        try {
            String realm = plugin.skills().state(player).realm;
            return realm != null && !plugin.skillEngine().armableGrades(realm).isEmpty();
        } catch (Throwable t) {
            return false;   // 모르면 "못 한다" 쪽으로 — 없는 조작을 가르치는 것보다 안 가르치는 게 낫다
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  허수아비 — 맞아 주는 몸 (읽는 것이 아니라 치는 것이다)
    // ══════════════════════════════════════════════════════════════════════

    private void clearDummies(World w) {
        for (Entity e : w.getEntities()) {
            if (e.getPersistentDataContainer().has(KEY_DUMMY)) {
                e.remove();
            }
        }
    }

    private void ensureDummies(World w) {
        int alive = 0;
        for (Entity e : w.getEntities()) {
            if (e.getPersistentDataContainer().has(KEY_DUMMY) && e.isValid()) {
                alive++;
            }
        }
        if (alive >= dummySpots.size()) {
            return;
        }
        clearDummies(w);
        int gy = groundY(w);
        for (int[] spot : dummySpots) {
            Location at = new Location(w, cx + spot[0] + 0.5, gy + 1.0, cz + spot[1] + 0.5);
            w.spawn(at, Zombie.class, e -> {
                e.setAI(false);
                e.setSilent(true);
                e.setCollidable(true);
                e.setRemoveWhenFarAway(false);
                e.setShouldBurnInDay(false);
                e.setAdult();
                e.setPersistent(true);
                e.getPersistentDataContainer()
                        .set(KEY_DUMMY, PersistentDataType.INTEGER, dummyDurability);
                if (e.getAttribute(Attribute.MAX_HEALTH) != null) {
                    e.getAttribute(Attribute.MAX_HEALTH).setBaseValue(2048);   // 죽지 않는다
                }
                e.setHealth(2048);
                e.setCustomNameVisible(true);
                e.setCustomName(dummyName);
            });
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  들어오기 / 건너기 — ★ 문
    // ══════════════════════════════════════════════════════════════════════

    /** 나루로. {@code /혼천 입도} 와 첫 접속이 부른다. 월드를 못 열면 <b>아무것도 하지 않는다</b> (never trap). */
    public void enter(Player player) {
        World w = world();
        if (w == null) {
            player.sendMessage(ChatColor.RED + displayName + "을(를) 열 수 없다 — 강호로 바로 간다.");
            return;   // ★ 못 열면 사람을 원래 있던 자리에 그대로 둔다. 절대 붙잡지 않는다
        }
        boolean first = !isAntechamber(player.getWorld());
        build(w, false, null);
        if (first && kitGive) {
            stowed.put(player.getUniqueId(), player.getInventory().getContents().clone());
            player.getInventory().clear();
            player.getInventory().addItem(new ItemStack(Material.WOODEN_SWORD));
            player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        }
        player.setGameMode(GameMode.ADVENTURE);   // 손은 쓴다. 마당은 못 부순다
        player.teleport(spawnAt(w));
        player.setFallDistance(0f);
        boolean known = plugin.ledger(player.getUniqueId()).linked();
        if (known) {
            player.sendMessage(revisitLine);
        } else {
            player.sendTitle(ChatColor.GOLD + arrivalTitle, ChatColor.GRAY + arrivalSubtitle, 10, 70, 20);
            arrivalLines.forEach(player::sendMessage);
            if (kitGive && !kitLine.isEmpty()) {
                player.sendMessage(kitLine);
            }
        }
        refreshPanels(player);
    }

    /**
     * <b>강을 건넌다 — 문.</b> 열리는 조건은 하나다: <b>강호에 이름이 올랐는가</b>({@link PlayerLedger#linked}).
     *
     * <p>그리고 <b>봇이 꺼져 있으면 사공은 그냥 건넨다</b> — {@link WorldBridge#worldDay()} ≤ 0 은
     * 스냅숏이 없다는 뜻이고, 그것은 접합이 <b>원리적으로 불가능</b>하다는 뜻이다. 그때 문을 잠그면
     * 사람은 영원히 나루에 갇힌다. 대기실은 초대이지 감옥이 아니다 ({@code gate.bridge_down}).
     */
    public void cross(Player player) {
        if (!isAntechamber(player.getWorld())) {
            player.sendMessage(ChatColor.GRAY + "여기는 나루가 아니다.");
            return;
        }
        if (plugin.ledger(player.getUniqueId()).linked()) {
            depart(player, List.of());
            return;
        }
        if (bridgeDownAllows && plugin.worldDay() <= 0) {
            depart(player, bridgeDownLines);   // ★ 다리가 죽었다 — 갇히지 않는다
            return;
        }
        player.sendTitle(ChatColor.RED + refuseTitle, ChatColor.YELLOW + refuseSubtitle, 10, 60, 20);
        refuseLines.forEach(player::sendMessage);
    }

    /** 배가 뭍에 닿는다 — 짐을 돌려주고, 강호로 내린다 */
    private void depart(Player player, List<String> extra) {
        UUID id = player.getUniqueId();
        restore(player);
        player.setGameMode(GameMode.SURVIVAL);
        player.teleport(destination());
        player.setFallDistance(0f);
        boarding.remove(id);
        crossedLines.forEach(player::sendMessage);
        extra.forEach(player::sendMessage);
    }

    /** 나루의 짐은 나루에 둔다 (연무장의 규약) */
    private void restore(Player player) {
        ItemStack[] back = stowed.remove(player.getUniqueId());
        if (back == null) {
            return;
        }
        player.getInventory().setContents(back);
        if (!kitTakeBackLine.isEmpty()) {
            player.sendMessage(kitTakeBackLine);
        }
    }

    /**
     * 내리는 자리 — 등록부의 순서대로. <b>하나도 못 찾으면 세계의 스폰</b>.
     * 여기서 null 을 돌려주는 경로는 없다. 그것이 "절대 갇히지 않는다"의 마지막 보루다.
     */
    private Location destination() {
        for (String name : destinations) {
            Location at = plugin.anchor(name);
            if (at != null && at.getWorld() != null && !isAntechamber(at.getWorld())) {
                return at;
            }
        }
        for (World w : Bukkit.getWorlds()) {
            if (!isAntechamber(w) && !Dojang.isDojang(w)) {
                return w.getSpawnLocation();
            }
        }
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  사건 — 첫 소환 · 종 · 허수아비 · 명령
    // ══════════════════════════════════════════════════════════════════════

    /**
     * <b>첫 소환.</b> 강호에 들지 않은 자는 필드에 떨어지지 않는다 — 나루에 선다.
     *
     * <p>이미 강호에 든 자는 <b>나루를 다시 안 거친다</b> (세계로 바로). 다만 접합이 <b>오프라인에서</b>
     * 확정됐다면(디스코드에서 확정하고 로그아웃했다면) 나루에 서 있을 것이다 — 그때는 문이 이미 열려 있으므로
     * 그대로 건넨다.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // SkillListener.onJoin 이 시트를 싣고 상태를 세운 뒤에 판단한다 (같은 틱의 뒤)
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            boolean linked = plugin.ledger(player.getUniqueId()).linked();
            if (linked) {
                if (isAntechamber(player.getWorld())) {
                    depart(player, List.of());   // 없는 사이에 이름이 올랐다 — 문은 이미 열려 있었다
                }
                return;   // 강호에 든 자는 나루를 다시 안 거친다
            }
            enter(player);
        }, 2L);
    }

    /** 종 — 사공을 부른다. ★ 이것이 문이다 (= {@code /혼천 도강}) */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || !isAntechamber(event.getPlayer().getWorld())
                || event.getClickedBlock().getType() != Material.BELL) {
            return;
        }
        event.setCancelled(true);
        cross(event.getPlayer());
    }

    /** 과제 ①: 손 — 허수아비를 <b>실제로 쳤을 때만</b> 닫힌다 (표지판을 읽은 것으로는 안 닫힌다) */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)
                || !isAntechamber(player.getWorld())
                || !(event.getEntity() instanceof LivingEntity dummy)
                || !dummy.getPersistentDataContainer().has(KEY_DUMMY)) {
            return;
        }
        // 허수아비는 죽지 않는다 — 장부를 위해 산다
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (dummy.isValid() && dummy.getAttribute(Attribute.MAX_HEALTH) != null) {
                dummy.setHealth(dummy.getAttribute(Attribute.MAX_HEALTH).getValue());
            }
        });
        bump(player, "손");
    }

    /** 과제 ⑤⑥: 명령 — <b>실제로 친</b> 것만 (도움말만 띄운 것은 배운 것이 아니다) */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!isAntechamber(player.getWorld())) {
            return;
        }
        String[] parts = event.getMessage().trim().split("\\s+");
        if (parts.length < 2 || !parts[0].toLowerCase(Locale.ROOT).startsWith("/혼천")) {
            return;
        }
        for (Lesson l : lessons.values()) {
            if (!"명령".equals(l.detect()) || !l.command().equals(parts[1])) {
                continue;
            }
            if (l.needsArgs() && parts.length < 3) {
                continue;   // `/혼천 수련` 만 친 것 = 도움말. 배분을 실제로 해야 닫힌다
            }
            bump(player, l.id());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        // ★ 나루에서 나가면 짐을 즉시 돌려준다 — 서버가 죽어도 사람의 짐은 안 죽는다
        if (stowed.containsKey(id)) {
            player.getInventory().setContents(stowed.remove(id));
        }
        progress.remove(id);
        gesturesSeen.remove(id);
        lastArmed.remove(id);
        boarding.remove(id);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  티커 — 몸짓·경공·물안개·문이 열리는 순간
    // ══════════════════════════════════════════════════════════════════════

    void start() {
        if (ticker != null) {
            return;
        }
        ticker = Bukkit.getScheduler().runTaskTimer(plugin,
                Metrics.wrap("antechamber", this::tick), 40L, 5L);
    }

    private void tick() {
        World w = Bukkit.getWorld(worldName);
        if (w == null) {
            return;
        }
        for (Player player : w.getPlayers()) {
            leash(player);
            watchGestures(player);
            watchArmed(player);
            watchGyeonggong(player);
            watchGate(player);
        }
    }

    /** 물안개 — 벽이 아니라 되돌림이다. 죽이지 않고, 막지도 않고, 데려온다 (청크를 무한히 만들지 않는다) */
    private void leash(Player player) {
        Location at = player.getLocation();
        double dx = at.getX() - (cx + 0.5);
        double dz = at.getZ() - (cz + 0.5);
        if (dx * dx + dz * dz <= (double) leash * leash && at.getY() > groundY(player.getWorld()) - 8) {
            return;
        }
        player.teleport(spawnAt(player.getWorld()));
        player.setFallDistance(0f);
        if (!mistLine.isEmpty()) {
            player.sendMessage(mistLine);
        }
    }

    /**
     * 과제 ③: 방어 태세 — <b>몸짓이 곧 선택이다.</b>
     *
     * <p>보는 술어는 {@code combat.yml defender_stance_mc.gestures} 의 값 그대로다
     * (막기=isBlocking · 흘리기=isSneaking · 회피=isSprinting). 이 목록은 {@code antechamber.yml} 의
     * 과제에 적혀 있고, {@code tools/antechamber_audit.py} 가 두 등록부를 대조한다 —
     * <b>화면이 세계에 대해 거짓말하면 잡힌다.</b>
     */
    private void watchGestures(Player player) {
        Lesson l = lessons.get("태세");
        if (l == null || !"방어_몸짓".equals(l.detect()) || complete(player, l)) {
            return;
        }
        Set<String> seen = gesturesSeen.computeIfAbsent(player.getUniqueId(), k -> new LinkedHashSet<>());
        int before = seen.size();
        for (String g : l.gestures()) {
            boolean now = switch (g) {
                case "isBlocking" -> player.isBlocking();
                case "isSneaking" -> player.isSneaking();
                case "isSprinting" -> player.isSprinting();
                default -> false;   // 등록부에 없는 술어는 코드가 지어내지 않는다 (감사가 잡는다)
            };
            if (now) {
                seen.add(g);
            }
        }
        for (int i = before; i < seen.size(); i++) {
            bump(player, l.id());
        }
    }

    /** 과제 ②: 격 — Shift+우클릭으로 두름이 바뀌는 순간 (SkillListener 가 state.armed 를 돌린다) */
    private void watchArmed(Player player) {
        Lesson l = lessons.get("격");
        if (l == null || complete(player, l) || (l.requiresArmable() && !armable(player))) {
            return;
        }
        String armed;
        try {
            armed = plugin.skills().state(player).armed;
        } catch (Throwable t) {
            return;
        }
        String was = lastArmed.get(player.getUniqueId());
        lastArmed.put(player.getUniqueId(), armed == null ? "" : armed);
        if (armed != null && !armed.isEmpty() && was != null && !armed.equals(was)) {
            bump(player, l.id());
        }
    }

    /** 과제 ④: 경공 — {@code gyeonggong.yml activate}: "달리며 점프". 발이 이미 움직일 때만 몸이 뜬다 */
    private void watchGyeonggong(Player player) {
        Lesson l = lessons.get("경공");
        if (l == null || complete(player, l)) {
            return;
        }
        if (player.isSprinting() && !player.isOnGround() && player.getFallDistance() <= 0.1f) {
            bump(player, l.id());
        }
    }

    /** 강호에 이름이 올랐다 — <b>배가 뜬다</b>. 문이 열리는 순간을 사람이 알아야 한다 */
    private void watchGate(Player player) {
        UUID id = player.getUniqueId();
        if (!plugin.ledger(id).linked() || boarding.contains(id)) {
            return;
        }
        boarding.add(id);
        refreshPanels(player);   // 시트가 내려왔다 — 이제 격을 두를 수 있을지도 모른다
        player.sendTitle(ChatColor.GOLD + openedTitle, ChatColor.WHITE + openedSubtitle, 10, 70, 20);
        String who = WorldBridge.linkedName(id);
        openedLines.forEach(line ->
                player.sendMessage(line.replace("{name}", who == null ? player.getName() : who)));
        if (autoCrossSeconds > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && isAntechamber(player.getWorld())
                        && plugin.ledger(id).linked()) {
                    depart(player, List.of());
                }
            }, autoCrossSeconds * 20L);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  과제 장부
    // ══════════════════════════════════════════════════════════════════════

    private boolean complete(Player player, Lesson l) {
        return progress.getOrDefault(player.getUniqueId(), Map.of())
                .getOrDefault(l.id(), 0) >= l.count();
    }

    /** 한 번 했다. 다 채우면 <b>말해 준다</b> — 침묵은 이 게임의 첫 함정이었다 */
    private void bump(Player player, String lessonId) {
        Lesson l = lessons.get(lessonId);
        if (l == null) {
            return;
        }
        Map<String, Integer> mine = progress.computeIfAbsent(player.getUniqueId(),
                k -> new LinkedHashMap<>());
        int now = mine.getOrDefault(lessonId, 0);
        if (now >= l.count()) {
            return;
        }
        mine.put(lessonId, now + 1);
        if (now + 1 < l.count()) {
            player.sendActionBar(ChatColor.GRAY + l.title() + "  "
                    + ChatColor.WHITE + (now + 1) + ChatColor.GRAY + "/" + l.count());
            return;
        }
        player.sendMessage(doneLine.replace("{title}", l.title()).replace("{done}", l.done()));
        if (applicable(player).stream().allMatch(x -> complete(player, x))) {
            player.sendTitle(ChatColor.GOLD + allDoneTitle, ChatColor.GRAY + allDoneSubtitle, 10, 70, 20);
            allDoneLines.forEach(player::sendMessage);
        }
    }

    /** 이 사람이 <b>실제로 할 수 있는</b> 과제만. 못 하는 것을 못 했다고 세지 않는다 */
    private List<Lesson> applicable(Player player) {
        List<Lesson> out = new ArrayList<>();
        boolean armable = armable(player);
        for (Lesson l : lessons.values()) {
            if (l.requiresArmable() && !armable) {
                continue;
            }
            out.add(l);
        }
        return out;
    }

    /** 과제가 문을 잠그는가 — 등록부가 정한다 (기본 false. 과제 하나가 깨진 날 사람이 갇히면 안 된다) */
    public boolean lessonsGate() {
        return lessonsGate;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  종료 — 세계에 아무것도 남기지 않는다
    // ══════════════════════════════════════════════════════════════════════

    /** 플러그인이 내려간다 — 글판을 걷고, 나루에 남은 사람의 짐을 돌려준다 */
    public void shutdown() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        World w = Bukkit.getWorld(worldName);
        if (w != null) {
            for (Player player : w.getPlayers()) {
                if (stowed.containsKey(player.getUniqueId())) {
                    player.getInventory().setContents(stowed.remove(player.getUniqueId()));
                }
            }
            clearPanels(w);
        }
        stowed.clear();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  잔뼈
    // ══════════════════════════════════════════════════════════════════════

    private record Place(int x, int y, int z, Material m) { }

    private record Lesson(String id, String title, String how, String detect, int count, String done,
                          Set<String> gestures, String command, boolean needsArgs,
                          boolean requiresArmable, String unavailable) { }

    private record Panel(String id, int x, int z, List<String> lines, boolean accent, String onlyIf) { }

    private record Panels(int maxPanels, float scale, float viewRange, int lineWidth, double yOffset,
                          String billboard, String alignment, boolean seeThrough, boolean shadowed,
                          int backgroundArgb, int accentArgb, int blockLight, int skyLight,
                          String titlePrefix) { }

    private static Display.Billboard billboard(String name) {
        try {
            return Display.Billboard.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Display.Billboard.CENTER;
        }
    }

    private static TextDisplay.TextAlignment alignment(String name) {
        try {
            return TextDisplay.TextAlignment.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return TextDisplay.TextAlignment.CENTER;
        }
    }

    private static int argb(Object raw, int fallback) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            String t = s.trim().replaceFirst("^(0x|0X|#)", "");
            try {
                return (int) Long.parseLong(t, 16);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String str(Object raw, String fallback) {
        return raw == null ? fallback : String.valueOf(raw);
    }

    private static int num(Object raw, int fallback) {
        return raw instanceof Number n ? n.intValue() : fallback;
    }

    private static double dbl(Object raw, double fallback) {
        return raw instanceof Number n ? n.doubleValue() : fallback;
    }

    private static List<String> lines(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            list.forEach(v -> out.add(String.valueOf(v)));
        }
        return out;
    }

    private static List<Integer> ints(Object raw) {
        List<Integer> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            list.forEach(v -> out.add(v instanceof Number n ? n.intValue() : 0));
        }
        return out;
    }

    private static int[] pair(Object raw, int a, int b) {
        List<Integer> v = ints(raw);
        return new int[]{v.size() > 0 ? v.get(0) : a, v.size() > 1 ? v.get(1) : b};
    }
}
