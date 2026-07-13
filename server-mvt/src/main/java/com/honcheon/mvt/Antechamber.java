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
 * <p><b>왜 나루인가.</b> 지어낸 자리가 아니다 — {@code world_map.yml} 이 이미 답을 적어 뒀다:
 * 흑수나루(pos [118, 12], terrain 물가)는 청하현의 물길이고, <i>"청하현이 왜 나루를 갖는가의 답"</i>이다.
 * 강호에 든다는 것은 강을 건너는 것이고, <b>사공은 이름 없는 자를 태우지 않는다</b>. <b>문은 배다.</b>
 *
 * <h2>2차 개정 — 왜 습지(濕地)인가</h2>
 *
 * <p>사용자가 1차판에 실제로 들어가 보고 말했다: <i>"필드가 너무 디자인이 없음 · 조명도 너무 균일 ·
 * <b>어떻게 어느 방향으로 무엇을 언제 봐야 하는지 흐름이 없음</b> · 한마디로 너무 난잡함."</i>
 *
 * <p>맞는 말이었다. 1차판은 <b>마당</b>이었다. 마당은 사방이 열려 있고, 사방이 열려 있으면
 * <b>아무 방향도 가리키지 않는다.</b> 과제 여섯이 동시에 존재했다 — 그것은 안내가 아니라 여섯 개의 선택지였다.
 *
 * <p>그래서 땅을 갈아엎었다. 나루는 이제 <b>갈대 습지</b>이고 <b>마른 땅은 잔교(棧橋) 하나뿐이다.</b>
 * 길이 하나인 이유는 표지판이 시켜서가 아니라 <b>나머지가 전부 물이기 때문이다.</b>
 * 벽을 하나도 세우지 않았는데 길이 하나다 — 그것이 <b>가두지 않으면서 이끄는 법</b>이다.
 * (물에 빠져도 죽지 않는다: 수심 1 · 익사 꺼짐. 기어오르면 된다. <b>막지 않는다. 다만 젖을 뿐이다.</b>)
 *
 * <p><b>흐름</b> — 관문은 길을 따라 <b>하나씩</b> 나온다 (맞이 → 손 → 격 → 태세 → 경공 → 장부 → 나루).
 * 그리고 <b>한 번에 하나만 보인다</b>: 다음 관문의 글판은 앞 관문이 닫히기 전까지 뜨지 않는다
 * ({@code lessons.one_at_a_time}). 지금 무엇을 해야 하는지가 화면에 하나뿐이면 흐름은 저절로 생긴다.
 *
 * <p><b>빛</b> — 격자는 아무것도 안 가리킨다. 등롱이 <b>길을 따라</b> 늘어서면 그것이 곧 화살표다.
 * 그리고 <b>어두운 곳이 있어야 밝은 곳이 길로 읽힌다</b> — 습지는 어둡다. 눈금은
 * {@link TownAudit} 의 야간 3축 그대로다 (암흑 하한 12% · 주 동선 15% · 광원 밀도 6%).
 *
 * <p><b>발판</b> — {@code /혼천 수련 외공 2} 를 손으로 치게 하는 것이 진입 장벽이다.
 * 발판을 밟으면 명령이 <b>대신 쳐진다</b>. 다만 <b>무엇이 쳐졌는지 그대로 보여준다</b> —
 * 강호에서는 혼자 쳐야 하니까. 화면에 뜨는 글자와 실행되는 글자는 <b>같은 변수 하나</b>다.
 *
 * <p><b>불변식</b>: 떨구지 않는다 · 가두지 않는다(봇이 꺼지면 사공이 그냥 건넨다) ·
 * 과제는 문을 잠그지 않는다(<b>글판이 안 보이는 것과 문이 잠기는 것은 다른 것이다 — 종은 언제나 울린다</b>) ·
 * 못 하는 것을 가르치지 않는다(범인에게 격 관문은 없는 것으로 친다) · 문장·좌표·조건은 config 가 정본 ·
 * 난수 없음 · 조성은 {@link TickBudget#slice} 로 나눠 먹인다.
 */
public final class Antechamber implements Listener {

    private static final NamespacedKey KEY_PANEL = new NamespacedKey("honcheon", "ipdo_panel");
    private static final NamespacedKey KEY_DUMMY = new NamespacedKey("honcheon", "ipdo_dummy");

    private static String worldName = "honcheon_ipdo";

    private final HoncheonMvt plugin;

    // ─── 등록부 (config/antechamber.yml 이 정본) ───
    private final String displayName;
    private final int cx;
    private final int cz;

    private final Road road;
    private final int[] spawn;
    private final List<Station> stations = new ArrayList<>();
    private final Marsh marsh;
    private final Lighting light;
    private final Hut hut;
    private final int[] bell;
    private final int[] boat;
    private final boolean mooring;

    private final List<int[]> dummySpots = new ArrayList<>();
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

    private final boolean oneAtATime;
    private final String doneLine;
    private final String nextLine;
    private final String allDoneTitle;
    private final String allDoneSubtitle;
    private final List<String> allDoneLines;
    private final Map<String, Lesson> lessons = new LinkedHashMap<>();

    private final String plateEcho;
    private final String plateHint;
    private final int plateCooldown;
    private final List<Plate> plates = new ArrayList<>();

    private final Panels panelSpec;
    private final String arrivalPanelId;

    // ─── 장부 (사람마다) ───
    private final Map<UUID, Map<String, Integer>> progress = new HashMap<>();
    private final Map<UUID, Set<String>> gesturesSeen = new HashMap<>();
    private final Map<UUID, String> lastArmed = new HashMap<>();
    private final Map<UUID, ItemStack[]> stowed = new HashMap<>();
    private final Set<UUID> boarding = new LinkedHashSet<>();
    /** 발판 연타 방지 — (사람/발판) → 다시 밟을 수 있는 틱 */
    private final Map<String, Long> plateCooldowns = new HashMap<>();
    /** 세운 글판 — 관문 id(+변형) → 엔티티 */
    private final Map<String, UUID> panelEntities = new LinkedHashMap<>();
    /** 이 사람에게 지금 열려 있는 관문 번호 */
    private final Map<UUID, Integer> shownThrough = new HashMap<>();

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
        int[] center = pair(a.get("center"), 0, 0);
        this.cx = center[0];
        this.cz = center[1];

        // ── 길 (하나뿐이다)
        Map<String, Object> r = RulesConfig.section(a, "road");
        List<Gap> gaps = new ArrayList<>();
        if (r.get("gaps") instanceof List<?> gl) {
            for (Object o : gl) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> g = (Map<String, Object>) m;
                    int[] bz = pair(g.get("bypass_z"), 2, 3);
                    gaps.add(new Gap(num(g.get("from"), 0), num(g.get("to"), 0), bz[0], bz[1]));
                }
            }
        }
        this.road = new Road(num(r.get("z"), 0), Math.max(1, num(r.get("half_width"), 1)),
                num(r.get("from"), -30), num(r.get("to"), 26),
                Math.max(1, num(r.get("deck_y"), 1)), gaps);
        this.spawn = pair(a.get("spawn"), road.from(), road.z());

        if (a.get("stations") instanceof List<?> sl) {
            for (Object o : sl) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> s = (Map<String, Object>) m;
                    stations.add(new Station(str(s.get("id"), ""), num(s.get("x"), 0),
                            Math.max(1, num(s.get("half"), 4)), str(s.get("lesson"), "")));
                }
            }
        }

        Map<String, Object> ma = RulesConfig.section(a, "marsh");
        int[] mx = pair(ma.get("x"), -38, 34);
        int[] mz = pair(ma.get("z"), -22, 22);
        this.marsh = new Marsh(mx[0], mx[1], mz[0], mz[1],
                Math.max(1, num(ma.get("depth"), 1)), Math.max(2, num(ma.get("reed_hash"), 11)));

        Map<String, Object> li = RulesConfig.section(a, "lighting");
        this.light = new Lighting(
                Math.max(2, num(li.get("post_every"), 9)),
                !(li.get("post_alternate") instanceof Boolean pa) || pa,
                num(li.get("post_z"), 2),
                new LinkedHashSet<>(lines(li.get("brazier_stations"))),
                !(li.get("hut_lantern") instanceof Boolean hl) || hl,
                dbl(li.get("dark_min_pct"), 12), dbl(li.get("dark_max_pct"), 40),
                dbl(li.get("main_dark_max_pct"), 15), dbl(li.get("lamp_density_max_pct"), 6),
                num(li.get("main_light_span_min"), 3));

        Map<String, Object> h = RulesConfig.section(a, "hut");
        int[] hx = pair(h.get("x"), 19, 24);
        int[] hz = pair(h.get("z"), 5, 10);
        this.hut = new Hut(hx[0], hx[1], hz[0], hz[1],
                Math.max(2, num(h.get("wall_h"), 3)), Math.max(0, num(h.get("eave"), 2)),
                str(h.get("line"), ""));

        Map<String, Object> d = RulesConfig.section(a, "dock");
        this.bell = pair(d.get("bell"), 26, 0);
        this.boat = pair(d.get("boat"), 29, 0);
        this.mooring = !(d.get("mooring") instanceof Boolean mo) || mo;

        Map<String, Object> du = RulesConfig.section(a, "dummies");
        if (du.get("spots") instanceof List<?> ds) {
            ds.forEach(s -> dummySpots.add(pair(s, 0, 0)));
        }
        this.dummyDurability = num(du.get("durability"), 20);
        this.dummyName = str(du.get("name"), "§7허수아비");

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

        Map<String, Object> pl = RulesConfig.section(a, "plates");
        this.plateEcho = str(pl.get("echo"), "§8발판 — §f/{command}");
        this.plateHint = str(pl.get("hint"), "");
        this.plateCooldown = Math.max(1, num(pl.get("cooldown_ticks"), 40));
        if (pl.get("list") instanceof List<?> plist) {
            for (Object o : plist) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> p = (Map<String, Object>) m;
                    int[] pos = pair(p.get("pos"), 0, 0);
                    plates.add(new Plate(str(p.get("id"), ""), pos[0], pos[1],
                            str(p.get("command"), "")));
                }
            }
        }

        Map<String, Object> les = RulesConfig.section(a, "lessons");
        this.oneAtATime = !(les.get("one_at_a_time") instanceof Boolean oa) || oa;
        this.doneLine = str(les.get("done_line"), "§a✔ §f{title}");
        this.nextLine = str(les.get("next_line"), "");
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
                        str(l.get("id"), ""), str(l.get("title"), ""), str(l.get("how"), ""),
                        str(l.get("detect"), ""), Math.max(1, num(l.get("count"), 1)),
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
                num(td.get("max_panels"), 16), (float) dbl(td.get("scale"), 1.6),
                (float) dbl(td.get("view_range"), 2.0), num(td.get("line_width"), 240),
                dbl(td.get("y_offset"), 2.6), str(td.get("billboard"), "CENTER"),
                str(td.get("alignment"), "CENTER"),
                td.get("see_through") instanceof Boolean st && st,
                !(td.get("shadowed") instanceof Boolean sh) || sh,
                argb(td.get("background_argb"), 0xC8140F0C),
                argb(td.get("accent_argb"), 0xC8241A12),
                num(RulesConfig.section(td, "brightness").get("block"), 12),
                num(RulesConfig.section(td, "brightness").get("sky"), 15),
                str(td.get("title_prefix"), "§6§l"));
        this.arrivalPanelId = str(td.get("arrival_id"), "맞이");

        if (expectedPanels() > panelSpec.maxPanels()) {
            plugin.getLogger().warning("[입도진] 글판이 상한을 넘는다 — "
                    + expectedPanels() + " > " + panelSpec.maxPanels());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  월드
    // ══════════════════════════════════════════════════════════════════════

    /** 여기가 나루인가 — 소문·혈채가 이 월드를 무시하는 근거 ({@code Dojang.suppressWorldEvents} 가 부른다) */
    public static boolean isAntechamber(World world) {
        return world != null && worldName.equals(world.getName());
    }

    public static String worldName() {
        return worldName;
    }

    /**
     * 나루 월드 — 없으면 만든다. <b>못 만들면 null (그리고 아무도 여기 못 가둔다)</b>:
     * 부르는 쪽은 null 을 보면 아무것도 하지 않는다 — 사람은 원래 있던 자리에 그대로 선다.
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
                            + "\"biome\":\"minecraft:swamp\"}")
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
        w.setGameRule(GameRule.DROWNING_DAMAGE, false);   // ★ 물에 빠져도 안 죽는다 (막지 않는다. 젖을 뿐이다)
        w.setGameRule(GameRule.FIRE_DAMAGE, false);       // 화톳불 옆을 지나도 안 탄다
        w.setGameRule(GameRule.NATURAL_REGENERATION, true);
        w.setDifficulty(Difficulty.PEACEFUL);
        w.setTime(15000);   // ★ 초저녁 — 등롱이 길을 그린다. 대낮이면 빛이 아무것도 안 가리킨다
        w.setStorm(false);
        return w;
    }

    /** 평면 월드의 지면 — <b>지어내지 않고 월드에게 묻는다</b> (y5 가 아니라 y-61 이다. 한 번 데였다).
     *  조성이 손대지 않는 먼 자리에서 재므로, 습지를 파도 이 값은 안 흔들린다. */
    private int groundY(World w) {
        return w.getHighestBlockYAt(cx + 512, cz + 512);
    }

    /**
     * 딛는 자리 — 길의 서쪽 끝. <b>동쪽을 본다.</b>
     *
     * <p>yaw <b>−90 = 동</b>이다 (0=남 · 90=서 · 180=북). 1차판은 여기에 {@code 90f} 를 적어 두고
     * 주석에 "동쪽(나루)을 본다"고 써 놨다 — <b>사람을 정반대로 돌려세우고 있었다.</b>
     * "어느 방향을 봐야 하는지 모르겠다"는 말이 나온 데에는 이 한 글자도 있었다.
     */
    Location spawnAt(World w) {
        return new Location(w, cx + spawn[0] + 0.5, groundY(w) + road.deckY() + 1.0,
                cz + spawn[1] + 0.5, -90f, 0f);
    }

    private boolean built(World w) {
        return w.getBlockAt(cx + bell[0], groundY(w) + road.deckY() + 1, cz + bell[1])
                .getType() == Material.BELL;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  조성 — 틱 슬라이싱
    // ══════════════════════════════════════════════════════════════════════

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
        final List<Place> plan = plan(groundY(w));
        final int[] i = {0};
        TickBudget.slice(plugin, "입도진", () -> {
            if (i[0] >= plan.size()) {
                return false;
            }
            Place p = plan.get(i[0]++);
            Block b = w.getBlockAt(p.x(), p.y(), p.z());
            b.setType(p.m(), false);   // 물리 없이 — 물이 흘러 잔교를 덮으면 안 된다
            if (p.data() != null) {
                try {
                    b.setBlockData(Bukkit.createBlockData(p.data()), false);
                } catch (IllegalArgumentException ignored) {
                    // 블록 상태가 안 맞으면 기본 상태로 둔다 (조성이 멈추지 않는다)
                }
            }
            return true;
        }, () -> {
            building = false;
            clearPanels(w);       // ★ 다시 지으면 글이 두 겹으로 겹치면 안 된다
            clearDummies(w);
            spawnPanels(w);
            ensureDummies(w);
            plugin.getLogger().info("[입도진] 나루가 섰다 — 블록 " + plan.size()
                    + " · 관문 " + stations.size() + " · 글판 " + panelEntities.size()
                    + " · 발판 " + plates.size() + " · 허수아비 " + dummySpots.size());
            if (onDone != null) {
                onDone.run();
            }
        });
    }

    // ─── 마른 땅이 어디인가 — ★ '길이 하나'의 정의 ───

    /**
     * 이 칸이 마른 땅(잔교·마당·우회로·집·등롱 브래킷)인가. <b>나머지는 전부 물이다.</b>
     *
     * <p>★ <b>끊긴 자리가 가장 먼저다.</b> 그러지 않으면 경공 관문의 마당(x 4~12)이 그 구멍을
     * <b>도로 메워 버린다</b> — 잔교는 안 끊기고, 뛸 일도 없고, 과제는 영영 안 닫힌다.
     * (조성 판을 읽다가 잡았다. 화면에는 멀쩡한 다리로 보였을 것이다.)
     */
    private boolean isDeck(int x, int z) {
        if (inGap(x, z)) {
            return false;
        }
        return onRoad(x, z) || onStation(x, z) || onBypass(x, z) || onHut(x, z) || onLampBracket(x, z);
    }

    /** 잔교가 끊긴 자리 — 우회 널판만 빼고 전부 물이다 */
    private boolean inGap(int x, int z) {
        for (Gap g : road.gaps()) {
            if (x >= g.from() && x <= g.to() && !onBypass(x, z)) {
                return true;
            }
        }
        return false;
    }

    private boolean onRoad(int x, int z) {
        return x >= road.from() && x <= road.to()
                && Math.abs(z - road.z()) <= road.halfWidth();
    }

    private boolean onStation(int x, int z) {
        for (Station s : stations) {
            if (Math.abs(x - s.x()) <= s.half() && Math.abs(z - road.z()) <= s.half()) {
                return true;
            }
        }
        return false;
    }

    /** 우회로 — 끊긴 자리 옆 널판. <b>뛰기 싫은 사람이 돌아가는 길</b> (없으면 그것은 함정이다) */
    private boolean onBypass(int x, int z) {
        for (Gap g : road.gaps()) {
            if (x >= g.from() && x <= g.to()
                    && z >= Math.min(g.bz1(), g.bz2()) && z <= Math.max(g.bz1(), g.bz2())) {
                return true;
            }
        }
        return false;
    }

    private boolean onHut(int x, int z) {
        return x >= hut.x1() && x <= hut.x2() && z >= hut.z1() && z <= hut.z2();
    }

    /** 집에 잇닿은 칸 — 여기에 난간을 세우면 문이 막힌다 */
    private boolean abutsHut(int x, int z) {
        return onHut(x, z + 1) || onHut(x, z - 1);
    }

    private boolean onLampBracket(int x, int z) {
        Integer side = lampSide(x);
        return side != null && z == road.z() + side;
    }

    /**
     * 이 x 에 등롱이 서는가, 선다면 어느 쪽인가 — <b>격자가 아니라 리듬</b>.
     * 좌·우로 번갈아 세운다 (한 줄로 세우면 그것도 격자다). 난수 없음 — 좌표 산술이다.
     */
    private Integer lampSide(int x) {
        if (x < road.from() || x > road.to()) {
            return null;
        }
        int n = x - road.from();
        if (Math.floorMod(n, light.postEvery()) != 0) {
            return null;
        }
        if (!light.postAlternate()) {
            return light.postZ();
        }
        return Math.floorMod(n / light.postEvery(), 2) == 0 ? light.postZ() : -light.postZ();
    }

    /** 화톳불 자리 — 관문 마당의 한 귀퉁이. <b>길 위가 아니다</b> (불이 길을 막으면 안 된다) */
    private int[] brazierAt(Station s) {
        return new int[]{s.x() + s.half() - 1, road.z() - (s.half() - 1)};
    }

    // ─── 조성 판 (난수 없음 — 무늬는 좌표 해시) ───

    private List<Place> plan(int gy) {
        List<Place> out = new ArrayList<>();
        int deck = gy + road.deckY();

        // ① 습지 — 잔교 밖은 전부 물이다. ★ **이것이 '길이 하나'의 진짜 이유다** (벽을 안 세웠다)
        for (int x = marsh.x1(); x <= marsh.x2(); x++) {
            for (int z = marsh.z1(); z <= marsh.z2(); z++) {
                boolean d = isDeck(x, z);
                out.add(new Place(cx + x, gy, cz + z, Material.WATER, null));
                out.add(new Place(cx + x, gy - marsh.depth(), cz + z, Material.GRAVEL, null));
                if (d) {
                    continue;
                }
                for (int y = gy + 1; y <= gy + 7; y++) {   // 잔교 밖 머리 위를 비운다
                    out.add(new Place(cx + x, y, cz + z, Material.AIR, null));
                }
                // 갈대 — 해시로 돋는다 (난수 아님). 습지가 습지처럼 보여야 한다
                if (Math.floorMod(x * 31 + z * 17, marsh.reedHash()) == 0) {
                    out.add(new Place(cx + x, gy, cz + z, Material.SAND, null));
                    out.add(new Place(cx + x, gy + 1, cz + z, Material.SUGAR_CANE, null));
                    if (Math.floorMod(x + z, 3) == 0) {
                        out.add(new Place(cx + x, gy + 2, cz + z, Material.SUGAR_CANE, null));
                    }
                }
            }
        }

        // ② 잔교 — 마른 땅. 말뚝 위에 얹힌다 (수로채의 어휘)
        for (int x = marsh.x1(); x <= marsh.x2(); x++) {
            for (int z = marsh.z1(); z <= marsh.z2(); z++) {
                if (!isDeck(x, z)) {
                    continue;
                }
                boolean yard = onStation(x, z) || onHut(x, z);
                out.add(new Place(cx + x, deck, cz + z,
                        yard ? Material.SPRUCE_PLANKS : Material.DARK_OAK_PLANKS, null));
                for (int y = deck + 1; y <= gy + 7; y++) {
                    out.add(new Place(cx + x, y, cz + z, Material.AIR, null));
                }
                // 말뚝 — 세 칸에 하나 (촘촘하면 그것도 격자다)
                if (Math.floorMod(x, 3) == 0 && Math.floorMod(z, 3) == 0) {
                    for (int y = gy; y < deck; y++) {
                        out.add(new Place(cx + x, y, cz + z, Material.DARK_OAK_FENCE, null));
                    }
                }
            }
        }

        // ③ 난간 — 마당의 물 쪽 가장자리에만.
        //    ★ 집을 등지는 쪽은 난간을 세우지 않는다 — 안 그러면 **난간이 사공의 집 문을 막는다**
        //      (집을 지어 놓고 못 들어가게 하는 것만큼 난잡한 것이 없다).
        for (Station s : stations) {
            for (int x = s.x() - s.half(); x <= s.x() + s.half(); x++) {
                for (int side : new int[]{-s.half(), s.half()}) {
                    int z = road.z() + side;
                    if (onHut(x, z) || onLampBracket(x, z) || abutsHut(x, z) || inGap(x, z)) {
                        continue;
                    }
                    out.add(new Place(cx + x, deck + 1, cz + z, Material.DARK_OAK_FENCE, null));
                }
            }
        }

        // ④ 등롱 — ★ **길을 따라** 늘어선다. 이것이 화살표다 (6칸 격자로 도배하던 1차판을 지웠다)
        for (int x = road.from(); x <= road.to(); x++) {
            Integer side = lampSide(x);
            if (side == null) {
                continue;
            }
            int z = road.z() + side;
            out.add(new Place(cx + x, deck + 1, cz + z, Material.DARK_OAK_FENCE, null));
            out.add(new Place(cx + x, deck + 2, cz + z, Material.LANTERN, null));
        }

        // ⑤ 화톳불 — 관문 마당에만. **관문이 곧 불빛이다** (멀리서 다음 관문이 보인다)
        for (Station s : stations) {
            if (!light.brazierStations().contains(s.id())) {
                continue;
            }
            int[] at = brazierAt(s);
            out.add(new Place(cx + at[0], deck + 1, cz + at[1], Material.CAMPFIRE, null));
        }

        planHut(out, deck);

        // ⑦ 발판 — 밟으면 명령이 대신 쳐진다
        for (Plate p : plates) {
            out.add(new Place(cx + p.x(), deck + 1, cz + p.z(),
                    Material.POLISHED_BLACKSTONE_PRESSURE_PLATE, null));
        }

        // ⑧ 종 — ★ 길의 끝. 문이다
        out.add(new Place(cx + bell[0], deck + 1, cz + bell[1], Material.BELL,
                "minecraft:bell[attachment=floor,facing=north]"));

        // ⑨ 나룻배 — 매여 있다 (사공이 오면 뜬다)
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                out.add(new Place(cx + boat[0] + x, deck, cz + boat[1] + z,
                        Math.abs(x) + Math.abs(z) == 2 ? Material.WATER : Material.SPRUCE_PLANKS, null));
            }
        }
        out.add(new Place(cx + boat[0], deck + 1, cz + boat[1] - 1, Material.DARK_OAK_FENCE, null));
        if (mooring) {   // 닻줄 — 부두에서 배까지
            for (int x = bell[0] + 1; x < boat[0]; x++) {
                // 1.21.11 에서 CHAIN 은 IRON_CHAIN 으로 개명됐다 (구리 사슬 계열이 들어오면서).
                // 등록부(Material)에게 물어서 알았다 — 기억에 있는 이름을 쓰면 컴파일이 막아 준다
                out.add(new Place(cx + x, deck + 1, cz + boat[1], Material.IRON_CHAIN,
                        "minecraft:iron_chain[axis=x]"));
            }
        }
        return out;
    }

    /**
     * 사공의 집 — <b>나루가 나루처럼 보여야 한다.</b>
     *
     * <p>1차판에는 <b>기능만 있고 집이 없었다</b> — 사공이 있다고 말만 하고 사공의 집이 없었다.
     * 건축 어휘는 이 세계에 이미 있다 ({@code CheonghaBuilder}: 기단·처마·판재 /
     * {@code RemoteBuilder}: 수로채 = 물 위의 채). <b>나루는 수로채의 형제다</b> — 말뚝 위에 얹힌 채.
     * 조성기를 복제하지 않고 <b>어휘만 배웠다</b>: 기단 · 흙벽+판재 · <b>처마 내밀기 2</b>
     * ({@code TownAudit.EAVE_MIN} 과 같은 눈금) · 초가 지붕 · <b>켜진 불</b>.
     */
    private void planHut(List<Place> out, int deck) {
        int midX = (hut.x1() + hut.x2()) / 2;
        int midZ = (hut.z1() + hut.z2()) / 2;
        for (int y = deck + 1; y <= deck + hut.wallH(); y++) {
            for (int x = hut.x1(); x <= hut.x2(); x++) {
                for (int z = hut.z1(); z <= hut.z2(); z++) {
                    boolean edge = x == hut.x1() || x == hut.x2() || z == hut.z1() || z == hut.z2();
                    // 문 — 잔교를 향해 (남쪽 벽 두 칸). 닫힌 집은 초대가 아니다
                    boolean door = z == hut.z1() && y <= deck + 2 && x >= midX && x <= midX + 1;
                    if (!edge || door) {
                        out.add(new Place(cx + x, y, cz + z, Material.AIR, null));
                        continue;
                    }
                    boolean window = y == deck + 2 && Math.floorMod(x + z, 3) == 0;
                    out.add(new Place(cx + x, y, cz + z,
                            window ? Material.DARK_OAK_FENCE
                                    : y == deck + 1 ? Material.MUD_BRICKS : Material.SPRUCE_PLANKS,
                            null));
                }
            }
        }
        // 처마 — 벽 밖으로 eave 만큼 내민다. 초가(짚)
        int ry = deck + hut.wallH() + 1;
        for (int x = hut.x1() - hut.eave(); x <= hut.x2() + hut.eave(); x++) {
            for (int z = hut.z1() - hut.eave(); z <= hut.z2() + hut.eave(); z++) {
                out.add(new Place(cx + x, ry, cz + z, Material.HAY_BLOCK, null));
            }
            out.add(new Place(cx + x, ry + 1, cz + midZ, Material.SPRUCE_SLAB, null));   // 용마루
        }
        // ★ 불이 켜져 있다 — 매단 등롱 (사공은 없지만 집은 살아 있다)
        if (light.hutLantern()) {
            out.add(new Place(cx + midX, deck + hut.wallH(), cz + midZ, Material.LANTERN,
                    "minecraft:lantern[hanging=true]"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  글판 — 관문마다 하나. ★ 한 번에 하나만 보인다
    // ══════════════════════════════════════════════════════════════════════

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
        panelEntities.clear();
        for (Entity e : w.getEntities()) {
            if (e instanceof TextDisplay && e.getPersistentDataContainer().has(KEY_PANEL)) {
                String id = e.getPersistentDataContainer().get(KEY_PANEL, PersistentDataType.STRING);
                if (id != null && panelEntities.putIfAbsent(id, e.getUniqueId()) != null) {
                    e.remove();   // 같은 id 가 둘이면 겹친 것이다
                }
            }
        }
        if (panelEntities.size() != expectedPanels()) {
            clearPanels(w);
            spawnPanels(w);
        }
    }

    private int expectedPanels() {
        int n = 0;
        for (Station s : stations) {
            n++;
            Lesson l = lessons.get(s.lesson());
            if (l != null && l.requiresArmable()) {
                n++;   // 격은 판이 둘 — 두를 수 있는 몸 / 없는 몸 (사람마다 하나만 보인다)
            }
        }
        return n;
    }

    /** 관문의 글 — <b>과제의 문장 그대로</b>. 판과 관문이 딴말을 할 수가 없다 */
    private List<String> panelText(Station s, boolean unavailableVariant) {
        Lesson l = lessons.get(s.lesson());
        if (l == null) {
            return s.id().equals(arrivalPanelId) ? arrivalLines
                    : List.of(panelSpec.titlePrefix() + s.id());
        }
        return List.of(panelSpec.titlePrefix() + l.title(),
                unavailableVariant ? l.unavailable() : l.how());
    }

    private void spawnPanels(World w) {
        int gy = groundY(w);
        for (Station s : stations) {
            Lesson l = lessons.get(s.lesson());
            boolean isGate = s.x() >= bell[0] - s.half();   // 문의 관문 — 주사 바탕
            spawnPanel(w, gy, s.id(), s, panelText(s, false), isGate);
            if (l != null && l.requiresArmable()) {
                spawnPanel(w, gy, s.id() + "_없음", s, panelText(s, true), false);
            }
        }
    }

    private void spawnPanel(World w, int gy, String id, Station s, List<String> text, boolean accent) {
        Location at = new Location(w, cx + s.x() + 0.5,
                gy + road.deckY() + 1 + panelSpec.yOffset(), cz + road.z() + 0.5);
        TextDisplay d = w.spawn(at, TextDisplay.class, e -> {
            e.setText(String.join("\n", text));
            e.setBillboard(billboard(panelSpec.billboard()));
            e.setAlignment(alignment(panelSpec.alignment()));
            e.setLineWidth(panelSpec.lineWidth());
            e.setViewRange(panelSpec.viewRange());
            e.setSeeThrough(panelSpec.seeThrough());
            e.setShadowed(panelSpec.shadowed());
            e.setDefaultBackground(false);   // ★ 바닐라 반투명 검정 상자를 쓰지 않는다 (수묵)
            e.setBackgroundColor(Color.fromARGB(
                    accent ? panelSpec.accentArgb() : panelSpec.backgroundArgb()));
            e.setBrightness(new Display.Brightness(panelSpec.blockLight(), panelSpec.skyLight()));
            e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(panelSpec.scale(), panelSpec.scale(), panelSpec.scale()),
                    new AxisAngle4f()));
            e.setPersistent(true);
            e.getPersistentDataContainer().set(KEY_PANEL, PersistentDataType.STRING, id);
        });
        panelEntities.put(id, d.getUniqueId());
    }

    /**
     * 이 관문이 이 사람에게 <b>넘어간 것</b>인가 — 과제가 닫혔거나, 과제가 없거나(맞이),
     * <b>못 하는 조작이라 아예 없는 것으로 치는</b> 경우(범인의 격).
     */
    private boolean passed(Player player, Station s) {
        Lesson l = lessons.get(s.lesson());
        if (l == null) {
            return true;   // 맞이 — 아무것도 요구하지 않는다 (첫 화면이 시험이면 그것은 초대가 아니다)
        }
        if (l.requiresArmable() && !armable(player)) {
            return true;   // ★ 못 하는 것 때문에 길이 막히지 않는다. 그냥 지나간다
        }
        return complete(player, l);
    }

    /** 지금 이 사람이 서 있는 관문의 번호 — 앞의 것이 다 닫혀야 다음이 열린다 */
    private int currentStation(Player player) {
        for (int i = 0; i < stations.size(); i++) {
            if (!passed(player, stations.get(i))) {
                return i;
            }
        }
        return stations.size() - 1;   // 다 지났다 — 나루까지 열려 있다
    }

    /**
     * <b>★ 한 번에 하나만.</b> 다음 관문의 글판은 앞 관문이 닫히기 전까지 <b>뜨지 않는다</b>.
     * 지금 무엇을 해야 하는지가 화면에 하나뿐이면, 흐름은 저절로 생긴다
     * (과제 여섯이 동시에 보이면 그것은 안내가 아니라 <b>여섯 개의 선택지</b>다 — 1차판의 병).
     *
     * <p>지나온 관문의 판은 <b>남긴다</b> (걸어온 길이 보여야 길이다). 앞의 판만 감춘다.
     *
     * <p>★ <b>이것은 문을 잠그는 것이 아니다.</b> 판이 안 보여도 <b>종은 울린다</b>
     * ({@code lessons.gating: false}). 글판은 안내이지 자물쇠가 아니다.
     */
    void refreshPanels(Player player) {
        boolean armable = armable(player);
        int current = oneAtATime ? currentStation(player) : stations.size() - 1;
        for (int i = 0; i < stations.size(); i++) {
            Station s = stations.get(i);
            Lesson l = lessons.get(s.lesson());
            boolean reached = i <= current;
            boolean armGated = l != null && l.requiresArmable();
            show(player, s.id(), reached && (!armGated || armable));
            if (armGated) {
                show(player, s.id() + "_없음", reached && !armable);
            }
        }
        shownThrough.put(player.getUniqueId(), current);
    }

    private void show(Player player, String panelId, boolean visible) {
        UUID id = panelEntities.get(panelId);
        if (id == null || !(Bukkit.getEntity(id) instanceof TextDisplay d)) {
            return;
        }
        if (visible) {
            player.showEntity(plugin, d);
        } else {
            player.hideEntity(plugin, d);
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
    //  허수아비
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
        int y = groundY(w) + road.deckY() + 1;
        for (int[] spot : dummySpots) {
            Location at = new Location(w, cx + spot[0] + 0.5, y, cz + spot[1] + 0.5);
            w.spawn(at, Zombie.class, e -> {
                e.setAI(false);
                e.setSilent(true);
                e.setCollidable(true);
                e.setRemoveWhenFarAway(false);
                e.setShouldBurnInDay(false);
                e.setAdult();
                e.setPersistent(true);
                e.getPersistentDataContainer().set(KEY_DUMMY, PersistentDataType.INTEGER,
                        dummyDurability);
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
        player.setGameMode(GameMode.ADVENTURE);   // 손은 쓴다. 잔교는 못 부순다
        player.teleport(spawnAt(w));
        player.setFallDistance(0f);
        if (plugin.ledger(player.getUniqueId()).linked()) {
            player.sendMessage(revisitLine);
        } else {
            player.sendTitle(ChatColor.GOLD + arrivalTitle, ChatColor.GRAY + arrivalSubtitle, 10, 70, 20);
            arrivalLines.forEach(player::sendMessage);
            if (kitGive && !kitLine.isEmpty()) {
                player.sendMessage(kitLine);
            }
            if (!hut.line().isEmpty()) {
                player.sendMessage(hut.line());
            }
        }
        refreshPanels(player);
    }

    /**
     * <b>강을 건넌다 — 문.</b> 조건은 하나다: <b>강호에 이름이 올랐는가</b>({@link PlayerLedger#linked}).
     * 그리고 <b>봇이 꺼져 있으면 사공은 그냥 건넨다</b> ({@link WorldBridge#worldDay()} ≤ 0 = 접합이
     * 원리적으로 불가능하다는 뜻이다. 그때 문을 잠그면 사람은 영원히 나루에 갇힌다).
     *
     * <p>★ <b>과제와 무관하다.</b> 글판이 하나도 안 열렸어도 종은 울린다.
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

    private void depart(Player player, List<String> extra) {
        UUID id = player.getUniqueId();
        restore(player);
        player.setGameMode(GameMode.SURVIVAL);
        player.teleport(destination());
        player.setFallDistance(0f);
        boarding.remove(id);
        shownThrough.remove(id);
        crossedLines.forEach(player::sendMessage);
        extra.forEach(player::sendMessage);
    }

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
     * 내리는 자리 — 등록부의 순서대로. <b>하나도 못 찾으면 세계의 스폰.</b>
     * 여기서 null 을 돌려주는 경로는 없다 — 그것이 "절대 갇히지 않는다"의 마지막 보루다.
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
    //  사건
    // ══════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (plugin.ledger(player.getUniqueId()).linked()) {
                if (isAntechamber(player.getWorld())) {
                    depart(player, List.of());   // 없는 사이에 이름이 올랐다 — 문은 이미 열려 있었다
                }
                return;   // 강호에 든 자는 나루를 다시 안 거친다
            }
            enter(player);
        }, 2L);
    }

    /**
     * 종 — 사공을 부른다 (★ 문) · <b>발판 — 명령이 대신 쳐진다</b>.
     * 발판은 {@code Action.PHYSICAL} 이다 — <b>밟는 것</b>이지 누르는 것이 아니다.
     */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Block b = event.getClickedBlock();
        if (b == null || !isAntechamber(player.getWorld())) {
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && b.getType() == Material.BELL) {
            event.setCancelled(true);
            cross(player);
            return;
        }
        if (event.getAction() != Action.PHYSICAL) {
            return;
        }
        for (Plate p : plates) {
            if (b.getX() == cx + p.x() && b.getZ() == cz + p.z()) {
                stepPlate(player, p);
                return;
            }
        }
    }

    /**
     * <b>발판을 밟았다 — 명령이 대신 쳐진다.</b> (사용자 제안: <i>"발판을 밟아 자동으로 명령어가 쳐지게"</i>)
     *
     * <p>{@code /혼천 수련 외공 2} 를 손으로 치게 하는 것이 <b>진입 장벽</b>이다. 무협 서버에 처음 온 사람에게
     * 명령어 문법을 외우게 하지 않는다.
     *
     * <p>★ 화면에 뜨는 글자와 실제로 실행되는 글자는 <b>같은 변수 하나</b>({@code cmd})다. 둘을 따로 쓰면
     * 그 순간 화면이 세계에 대해 거짓말할 수 있다 — 이 프로젝트가 가장 싫어하는 것이다
     * ({@code /혼천 협공} 이 "캡 +3"이라 찍는데 config 는 2였던 적이 있다).
     *
     * <p>그리고 <b>가르치는 것을 잊지 않는다</b>: 강호에서는 이것을 손으로 쳐야 한다 ({@code plates.hint}).
     */
    private void stepPlate(Player player, Plate plate) {
        String key = player.getUniqueId() + "/" + plate.id();
        long now = player.getWorld().getFullTime();
        Long until = plateCooldowns.get(key);
        if (until != null && now < until) {
            return;   // 발판 위에서 발을 구르는 것은 한 번이다
        }
        plateCooldowns.put(key, now + plateCooldown);

        final String cmd = plate.command();   // ★ 단 하나의 진실 — 보여주는 것과 치는 것이 같은 변수다
        player.sendMessage(plateEcho.replace("{command}", cmd));
        if (!plateHint.isEmpty()) {
            player.sendMessage(plateHint);
        }
        player.performCommand(cmd);
        // performCommand 는 PlayerCommandPreprocessEvent 를 안 태운다 — 과제는 여기서 직접 닫는다.
        // 손으로 친 것과 발판으로 친 것이 **같은 함수**를 지나야 둘이 어긋나지 않는다
        String[] parts = cmd.trim().split("\\s+");
        if (parts.length >= 2) {
            creditCommand(player, parts[1], parts.length - 2);
        }
    }

    /** 과제: 명령 — <b>손으로 친 것</b> */
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
        creditCommand(player, parts[1], parts.length - 2);
    }

    /**
     * 명령 하나를 과제에 <b>기입한다</b> — 손으로 쳤든 발판으로 쳤든 <b>같은 문</b>을 지난다.
     * (두 경로가 각자 셈을 하면 언젠가 둘이 어긋난다.)
     */
    private void creditCommand(Player player, String sub, int extraArgs) {
        for (Lesson l : lessons.values()) {
            if (!"명령".equals(l.detect()) || !l.command().equals(sub)) {
                continue;
            }
            if (l.needsArgs() && extraArgs < 1) {
                continue;   // `/혼천 수련` 만 친 것 = 도움말. 배분을 실제로 해야 닫힌다
            }
            bump(player, l.id());
        }
    }

    /** 과제: 손 — 허수아비를 <b>실제로 쳤을 때만</b> */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)
                || !isAntechamber(player.getWorld())
                || !(event.getEntity() instanceof LivingEntity dummy)
                || !dummy.getPersistentDataContainer().has(KEY_DUMMY)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (dummy.isValid() && dummy.getAttribute(Attribute.MAX_HEALTH) != null) {
                dummy.setHealth(dummy.getAttribute(Attribute.MAX_HEALTH).getValue());
            }
        });
        bump(player, "손");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (stowed.containsKey(id)) {   // 서버가 죽어도 사람의 짐은 안 죽는다
            player.getInventory().setContents(stowed.remove(id));
        }
        progress.remove(id);
        gesturesSeen.remove(id);
        lastArmed.remove(id);
        boarding.remove(id);
        shownThrough.remove(id);
        plateCooldowns.keySet().removeIf(k -> k.startsWith(id.toString()));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  티커
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

    /** 물안개 — 벽이 아니라 되돌림이다. 죽이지 않고, 막지도 않고, 데려온다 */
    private void leash(Player player) {
        Location at = player.getLocation();
        double dx = at.getX() - (cx + 0.5);
        double dz = at.getZ() - (cz + 0.5);
        if (dx * dx + dz * dz <= (double) leash * leash
                && at.getY() > groundY(player.getWorld()) - 8) {
            return;
        }
        player.teleport(spawnAt(player.getWorld()));
        player.setFallDistance(0f);
        if (!mistLine.isEmpty()) {
            player.sendMessage(mistLine);
        }
    }

    /**
     * 과제: 방어 태세 — <b>몸짓이 곧 선택이다.</b> 보는 술어는
     * {@code combat.yml defender_stance_mc.gestures} 의 값 그대로다 (막기=isBlocking · 흘리기=isSneaking ·
     * 회피=isSprinting). {@code tools/antechamber_audit.py} 가 두 등록부를 대조한다.
     */
    private void watchGestures(Player player) {
        Lesson l = lessons.get("태세");
        if (l == null || !"방어_몸짓".equals(l.detect()) || complete(player, l)) {
            return;
        }
        Set<String> seen = gesturesSeen.computeIfAbsent(player.getUniqueId(),
                k -> new LinkedHashSet<>());
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

    /** 과제: 격 — Shift+우클릭으로 두름이 바뀌는 순간 */
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

    /** 과제: 경공 — {@code gyeonggong.yml activate}: "달리며 점프" */
    private void watchGyeonggong(Player player) {
        Lesson l = lessons.get("경공");
        if (l == null || complete(player, l)) {
            return;
        }
        if (player.isSprinting() && !player.isOnGround() && player.getFallDistance() <= 0.1f) {
            bump(player, l.id());
        }
    }

    /** 강호에 이름이 올랐다 — <b>배가 뜬다</b> */
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

        int before = shownThrough.getOrDefault(player.getUniqueId(), 0);
        refreshPanels(player);   // ★ 다음 관문의 글판이 여기서 비로소 뜬다
        boolean advanced = currentStation(player) > before;

        if (applicable(player).stream().allMatch(x -> complete(player, x))) {
            player.sendTitle(ChatColor.GOLD + allDoneTitle, ChatColor.GRAY + allDoneSubtitle, 10, 70, 20);
            allDoneLines.forEach(player::sendMessage);
        } else if (advanced && !nextLine.isEmpty()) {
            player.sendMessage(nextLine);
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

    // ══════════════════════════════════════════════════════════════════════
    //  종료 — 세계에 아무것도 남기지 않는다
    // ══════════════════════════════════════════════════════════════════════

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

    private record Place(int x, int y, int z, Material m, String data) { }

    private record Gap(int from, int to, int bz1, int bz2) { }

    private record Road(int z, int halfWidth, int from, int to, int deckY, List<Gap> gaps) { }

    private record Station(String id, int x, int half, String lesson) { }

    private record Marsh(int x1, int x2, int z1, int z2, int depth, int reedHash) { }

    private record Hut(int x1, int x2, int z1, int z2, int wallH, int eave, String line) { }

    private record Plate(String id, int x, int z, String command) { }

    private record Lighting(int postEvery, boolean postAlternate, int postZ,
                            Set<String> brazierStations, boolean hutLantern,
                            double darkMinPct, double darkMaxPct, double mainDarkMaxPct,
                            double lampDensityMaxPct, int mainLightSpanMin) { }

    private record Lesson(String id, String title, String how, String detect, int count, String done,
                          Set<String> gestures, String command, boolean needsArgs,
                          boolean requiresArmable, String unavailable) { }

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
            try {
                return (int) Long.parseLong(s.trim().replaceFirst("^(0x|0X|#)", ""), 16);
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

    private static int[] pair(Object raw, int a, int b) {
        if (raw instanceof List<?> list && list.size() >= 2) {
            return new int[]{
                    list.get(0) instanceof Number n1 ? n1.intValue() : a,
                    list.get(1) instanceof Number n2 ? n2.intValue() : b};
        }
        return new int[]{a, b};
    }
}
