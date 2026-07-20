package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;

import org.bukkit.configuration.file.YamlConfiguration;
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
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;

import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
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
    /**
     * 허수아비가 바라는 체력. <b>이 숫자를 그대로 {@code setHealth} 에 넣지 않는다.</b>
     *
     * <p>Paper 의 {@code MAX_HEALTH} 특성에는 <b>제 범위(0~1024)</b>가 있다. 기준값에 2048 을 적으면
     * 기준값은 2048 로 들어가지만 <b>실효값은 1024 로 깎이고</b>, 그 뒤 {@code setHealth(2048)} 이
     * "0..1024 사이여야 한다"며 터진다. 2026-07-13 오전에 그 예외 하나가 <b>대기실 조성 전체</b>를 죽였다.
     *
     * <p>그래서 체력은 <b>특성에게 물어서</b> 넣는다 ({@code attr.getValue()} — 이미 깎인 값이다).
     * 상한이 몇이든 이 코드는 안 터진다. <b>같은 병이 다시 날 수가 없다.</b>
     */
    /**
     * 발판의 몸 — <b>한 곳에만 적는다.</b> {@link #plan} 이 까는 것과 {@link #countPlates} 가 세는 것이
     * 같은 물건이어야 한다 (두 곳에 적으면 언젠가 갈라지고, 그러면 "깔았다"고 세면서 안 깐다).
     */
    private static final Material PLATE = Material.POLISHED_BLACKSTONE_PRESSURE_PLATE;

    private static final double DUMMY_HEALTH = 1024.0;

    private static final NamespacedKey KEY_DUMMY = new NamespacedKey("honcheon", "ipdo_dummy");
    /** 허수아비의 등급표(이름·내구) — 명패가 이것을 말한다 */
    private static final NamespacedKey KEY_DUMMY_LABEL = new NamespacedKey("honcheon", "ipdo_dummy_label");

    private static String worldName = "honcheon_ipdo";

    private final HoncheonMvt plugin;

    // ─── 등록부 (config/antechamber.yml 이 정본) ───
    private final String displayName;
    private final int cx;
    private final int cz;
    private final Difficulty difficulty;
    private final boolean damagePlayers;
    /** 조성 완결성 — 판을 이 간격으로 훑어 세계에게 묻는다 (결정론: 난수 표본이 아니다) */
    private final int verifySample;
    /** 표본이 이보다 덜 맞으면 **반쯤 선 것이다** — 다시 짓는다 */
    private final int verifyMinPct;

    private final Road road;
    private final int[] spawn;
    private final List<Station> stations = new ArrayList<>();
    private final Marsh marsh;
    private final boolean barrierOn;
    private final int barrierMargin;
    private final int barrierHeight;
    private final boolean barrierCap;
    private final Lighting light;
    private final Hut hut;
    private final int[] bell;
    private final int[] boat;
    private final boolean mooring;

    private final List<Dummy> dummies = new ArrayList<>();
    private final String dummyIdle;
    private final String dummyHit;

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
    /** B-120 — 자동 출발이 태우는 관문(마당)의 id. 빈 값이면 어디서든 태운다 (옛 동작) */
    private final String autoCrossFrom;
    /** B-120 — 자동 출발이 사람을 기다리기로 한 순간의 한 줄 (침묵 금지) */
    private final String dockWaitLine;
    private final List<String> crossedLines;
    private final List<String> destinations;
    private final String seojangWaitLine;
    private final String seojangWritingLine;
    private final String seojangReadingLine;

    private final String arrivalTitle;
    private final String arrivalSubtitle;
    private final List<String> arrivalLines;
    private final String revisitLine;
    /** B-124 — 건넌 몸이 다시 섰을 때, 과제 장부가 백지인 이유를 말하는 한 줄 (침묵 금지) */
    private final String revisitLedgerLine;

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
    /**
     * 과제 장부 — ★ <b>메모리뿐이다. 설계다</b> (B-124): 나루는 시험장이 아니라 문지방이고,
     * 문({@link #cross})은 과제를 보지 않으므로 장부를 세계에 남길 이유가 없다.
     * 나감({@link #onQuit})·재기동마다 백지가 된다 — 이미 건넌 몸이 다시 서면 관문이 도로
     * 열려 있는 이유가 이것이고, 그 결은 {@link #enter} 의 재방문 한 줄
     * ({@code arrival.revisit_ledger_line})이 말한다 (침묵 금지 — 다시 하는 것은 자유다).
     */
    private final Map<UUID, Map<String, Integer>> progress = new HashMap<>();
    private final Map<UUID, Set<String>> gesturesSeen = new HashMap<>();
    private final Map<UUID, String> lastArmed = new HashMap<>();
    /**
     * ★ <b>맡아 둔 짐</b> — 나루의 꾸러미를 쥐여 주는 동안 <b>사람의 진짜 짐</b>을 여기 둔다.
     *
     * <p><b>이것은 디스크에 적힌다</b> ({@link #STOW_FILE}). 예전에는 이 맵이 <b>메모리에만</b> 있었고,
     * 옆의 주석은 <i>"서버가 죽어도 사람의 짐은 안 죽는다"</i> 라고 적혀 있었다 — <b>사실이 아니었다.</b>
     * 실측(2026-07-20): 짐을 지닌 채 나루에 든 사람이 <b>평범한 재기동</b> 하나로 전부 잃었다.
     * ({@code onDisable} 은 나루 월드의 {@code getPlayers()} 를 도는데, 종료 시점엔 이미 아무도 없다.
     *  그리고 그 뒤 {@code stowed.clear()} 가 증거까지 지웠다.)
     *
     * <p>연무장 금고가 이 위험 때문에 원자적 디스크 저장을 갖췄다. 나루는 <b>같은 책임</b>을 지면서
     * 아무 보호가 없었다. 그래서 같은 손을 쓴다 — <b>맡는 순간 적고, 돌려주는 순간 지운다.</b>
     */
    private final Map<UUID, ItemStack[]> stowed = new HashMap<>();

    /** 맡아 둔 짐이 적히는 곳 — 연무장의 {@code dojang.yml} 과 같은 역할이다. */
    static final String STOW_FILE = "ipdo_stow.yml";
    private final Set<UUID> boarding = new LinkedHashSet<>();
    /** B-120 — 부두를 기다린다는 말을 이미 들은 사람 (자동 출발 대기 안내는 한 번만) */
    private final Set<UUID> dockWaitSaid = new LinkedHashSet<>();
    /** 발판 연타 방지 — (사람/발판) → 다시 밟을 수 있는 틱 */
    private final Map<String, Long> plateCooldowns = new HashMap<>();
    /** 세운 글판 — 관문 id(+변형) → 엔티티 */
    private final Map<String, UUID> panelEntities = new LinkedHashMap<>();
    /** 허수아비 장부 — 엔티티 → [누적, 합수, 최근] (Dojang 의 명패와 같은 눈금) */
    private final Map<UUID, double[]> tally = new HashMap<>();
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
        // ★ 난이도는 등록부가 정한다 — 그리고 PEACEFUL 은 **거절한다.**
        //   평화는 몬스터(=허수아비의 몸)를 매 틱 조용히 지운다. 그것이 오늘의 병이었다.
        Difficulty want;
        try {
            want = Difficulty.valueOf(str(a.get("difficulty"), "EASY").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            want = Difficulty.EASY;
            plugin.getLogger().warning("[입도진] 등록부의 난이도를 모른다: " + a.get("difficulty")
                    + " — EASY 로 선다");
        }
        if (want == Difficulty.PEACEFUL) {
            want = Difficulty.EASY;
            plugin.getLogger().severe("[입도진] 난이도 PEACEFUL 은 허수아비(좀비)를 조용히 지운다 "
                    + "— EASY 로 올린다. 사람은 damage_players 가 지킨다");
        }
        this.difficulty = want;
        this.damagePlayers = a.get("damage_players") instanceof Boolean dp && dp;

        // ── 조성이 **끝났는가** — 반쯤 선 것을 "서 있다"고 하지 않는다 (config: build)
        Map<String, Object> bld = RulesConfig.section(a, "build");
        this.verifySample = Math.max(1, num(bld.get("verify_sample"), 61));
        this.verifyMinPct = Math.min(100, Math.max(0, num(bld.get("verify_min_pct"), 97)));

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

        Map<String, Object> ba = RulesConfig.section(a, "barrier");
        this.barrierOn = !Boolean.FALSE.equals(ba.get("enabled"));
        this.barrierMargin = Math.max(0, num(ba.get("margin"), 1));
        this.barrierHeight = Math.max(4, num(ba.get("height"), 24));
        this.barrierCap = !Boolean.FALSE.equals(ba.get("cap"));
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
        this.dummyIdle = str(du.get("idle"), "§7{label} §8· 내구 {durability}");
        this.dummyHit = str(du.get("hit"), "§7{label} §f최근 {last} §7· 누적 {total} · {hits}합 "
                + "· 평균 {avg}§e → 내구 {durability} 상대 TTK {ttk}합");
        if (du.get("list") instanceof List<?> ds) {
            for (Object o : ds) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> dm = (Map<String, Object>) m;
                    int[] pos = pair(dm.get("pos"), 0, 0);
                    Dummy dummy = new Dummy(str(dm.get("id"), ""), str(dm.get("label"), "허수아비"),
                            pos[0], pos[1], Math.max(1, num(dm.get("durability"), 20)));
                    if (!dummy.id().isEmpty()) {
                        dummies.add(dummy);
                    }
                }
            }
        }

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
        // ★ B-120 — 배는 부두에서 뜬다. 값은 stations 의 id (등록제 — 모르는 이름이면 붙들지 않는다)
        this.autoCrossFrom = str(opened.get("auto_cross_from"), "");
        this.dockWaitLine = str(opened.get("dock_wait_line"), "");
        this.crossedLines = lines(RulesConfig.section(gate, "crossed").get("lines"));
        this.destinations = lines(gate.get("destinations"));
        // ★ B-118 — 서장이 남은 몸은 배가 기다린다. 문구는 등록부의 것이다 (침묵 금지 — 기본값은 대타)
        Map<String, Object> seojang = RulesConfig.section(gate, "seojang");
        this.seojangWaitLine = str(seojang.get("wait_line"),
                "§8사공이 삿대를 세운다. §7\"붓이 네 서장을 짓고 있다 — 이야기가 먼저다.\"");
        this.seojangWritingLine = str(seojang.get("writing_line"),
                "§7붓이 서장을 짓고 있다 — 책이 오면 품에서 저절로 펼쳐진다. 이야기가 끝나야 배가 뜬다.");
        this.seojangReadingLine = str(seojang.get("reading_line"),
                "§7서장이 아직 끝나지 않았다 — 책의 마지막 장에서 §f[강호로 나선다]§7 를 누르라.");

        Map<String, Object> arrival = RulesConfig.section(a, "arrival");
        this.arrivalTitle = str(arrival.get("title"), displayName);
        this.arrivalSubtitle = str(arrival.get("subtitle"), "");
        this.arrivalLines = lines(arrival.get("lines"));
        this.revisitLine = str(arrival.get("revisit_line"), "");
        this.revisitLedgerLine = str(arrival.get("revisit_ledger_line"), "");

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
                        str(l.get("requires"), ""),
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
            return configure(w);   // ★ 이미 열려 있어도 매번 다시 세운다 (아래 주석을 보라)
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
        return configure(w);
    }

    /**
     * 나루의 규칙 — <b>월드를 열 때만이 아니라 볼 때마다 다시 세운다.</b>
     *
     * <p>왜 매번인가: 이 값들은 {@code level.dat} 에 <b>저장된다</b>. 한 번 잘못 적은 값은
     * 서버를 껐다 켜도 그대로 살아 있고, {@code world()} 가 "이미 열려 있으니 그대로 쓴다"고
     * 돌려주는 순간 <b>고친 코드가 세계에 닿지 못한다</b>. (오늘 PEACEFUL 이 정확히 그랬다 —
     * 코드를 고쳐도 이미 만들어진 나루는 영영 평화였을 것이다.)
     *
     * <p>★ <b>난이도는 PEACEFUL 이 아니다.</b> 평화는 몬스터를 매 틱 지운다 — 허수아비의 몸이
     * 좀비이므로 평화는 곧 <b>허수아비의 부재</b>다. 그리고 그것을 아무도 말해 주지 않는다
     * (예외도 로그도 없다. 그저 없다). "나루에서는 죽지 않는다"는 약속은 난이도가 아니라
     * {@link #onPlayerDamage} 가 지킨다 — <b>약속을 지키느라 허수아비를 죽이지 않는다.</b>
     */
    private World configure(World w) {
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
        if (w.getDifficulty() != difficulty) {
            plugin.getLogger().info("[입도진] 난이도 " + w.getDifficulty() + " → " + difficulty
                    + " (평화는 허수아비를 지운다)");
            w.setDifficulty(difficulty);
        }
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
        // ★ +3 — 사용자 보고: "시작 스폰 위치가 살짝 아래임, 2칸만 위로."
        //   널 위에 서는 것이 아니라 **널 속에 반쯤 묻혀** 있었다.
        return new Location(w, cx + spawn[0] + 0.5, groundY(w) + road.deckY() + 3.0,
                cz + spawn[1] + 0.5, -90f, 0f);
    }

    /**
     * <b>★★ 나루가 정말로 서 있는가 — 반쯤 선 것을 "서 있다"고 하면 안 된다.</b>
     *
     * <p><b>옛 코드는 블록 하나를 봤다:</b>
     * <pre>return w.getBlockAt(bell...).getType() == Material.BELL;</pre>
     *
     * <p>그리고 오늘 크래시가 나루를 <b>반쯤 지어 놓고</b> 죽였다. 다음 기동에서 조성기는
     * <i>"이미 서 있다"</i> 며 건너뛰었다 — <b>종 하나가 놓였다는 것은 종 하나가 놓였다는 뜻이지
     * 나루가 섰다는 뜻이 아니다.</b> 수만 칸짜리 판을 한 칸으로 판단했다. <b>한 칸은 표본이 아니다.</b>
     *
     * <p>이제 <b>세계에게 묻는다</b>: {@link #plan} 을 {@code verify_sample} 간격으로 훑어,
     * 세계의 블록이 판과 <b>실제로 같은지</b> 센다. 점수가 {@code verify_min_pct} 에 못 미치면
     * 그것은 반쯤 선 나루이고, {@link #build} 가 처음부터 다시 짓는다.
     *
     * <p>★ <b>결정론</b> — 난수 표본이 아니다. plan 은 등록부에서 매번 같은 순서로 나오고, 표본은 그
     * 인덱스를 고정 간격으로 집는다. 같은 세계는 언제나 같은 점수를 받는다.
     *
     * <p>★ 100% 를 요구하지 않는 이유: 사람이 널판 하나 부순 것으로 나루를 다시 짓지 않는다.
     * 크래시는 표본을 <b>통째로</b> 무너뜨린다 — 두 사건은 점수가 다르다.
     *
     * @return 완결성 백분율 (0..100). 판이 비면 0
     */
    private int completeness(World w) {
        List<Place> plan = plan(groundY(w));
        if (plan.isEmpty()) {
            return 0;
        }
        int seen = 0;
        int match = 0;
        for (int i = 0; i < plan.size(); i += verifySample) {
            Place p = plan.get(i);
            seen++;
            if (w.getBlockAt(p.x(), p.y(), p.z()).getType() == p.m()) {
                match++;
            }
        }
        return seen == 0 ? 0 : (int) Math.round(100.0 * match / seen);
    }

    /**
     * <b>★★ 세계에 실제로 깔린 발판 — 등록부의 개수가 아니라 <i>깔린</i> 개수.</b>
     *
     * <p><b>2026-07-13 · 사용자: "발판 밟아도 메시지가 안 뜬다."</b> 재 보니 나루에 압력판이
     * <b>하나도 없었다.</b> 그런데 조성 로그는 <b>"발판 6"</b> 이라 찍고 있었다 — 그것은
     * {@code plates.size()}, 즉 <b>등록부의 개수</b>였다. 글판·허수아비는 세계에게 물어 세는데
     * (<i>N/M</i> 꼴) <b>발판만 안 물었다.</b> 오늘 세 번째로 잡는 같은 병이다:
     * <b>침묵이(그리고 등록부가) 성공으로 읽힌다.</b>
     */
    private int countPlates(World w) {
        int y = groundY(w) + road.deckY() + 1;
        int n = 0;
        for (Plate p : plates) {
            if (w.getBlockAt(cx + p.x(), y, cz + p.z()).getType() == PLATE) {
                n++;
            }
        }
        return n;
    }

    /** 종이 서 있는가 — <b>종은 문이다.</b> 없으면 나루가 아니라 갇힌 섬이다 */
    private boolean bellStands(World w) {
        return w.getBlockAt(cx + bell[0], groundY(w) + road.deckY() + 1, cz + bell[1])
                .getType() == Material.BELL;
    }

    /**
     * <b>★★ 표본이 못 보는 것 — 「이정표」는 전수 검사한다.</b>
     *
     * <p><b>오늘의 병</b>: {@link #completeness} 는 조성 판을 {@code verify_sample} 간격으로 훑는다.
     * 그런데 그 판은 <b>늪(물·자갈·허공)이 99% 를 차지한다</b> — 발판 6칸은 4만 칸 중 6칸,
     * 즉 <b>0.015%</b> 다. 표본이 그것을 집을 확률은 거의 0 이다.
     *
     * <p>그래서 <b>발판이 하나도 없는데 완결도는 97% 였다.</b> 문턱을 넘었으니 조성기는
     * <i>"이미 서 있다"</i> 며 건너뛰었고, 발판은 <b>영영 안 깔렸다.</b>
     * <b>표본은 부피를 재지 의미를 재지 않는다.</b>
     *
     * <p>이정표(발판·종)는 <b>드물고, 없으면 튜토리얼이 통째로 죽는다.</b> 그러므로 세지 않고
     * <b>전수 검사</b>한다 — 하나라도 없으면 나루는 <b>안 선 것이다.</b>
     */
    private boolean landmarksStand(World w) {
        return countPlates(w) == plates.size() && bellStands(w);
    }


    // ══════════════════════════════════════════════════════════════════════
    //  조성 — 틱 슬라이싱
    // ══════════════════════════════════════════════════════════════════════

    void build(World w, boolean force, Runnable onDone) {
        if (building) {
            return;
        }
        // ★★ 반쯤 선 나루를 "서 있다"고 하지 않는다 — 세계에게 물어 점수를 받는다.
        //   두 눈으로 본다: **부피**(표본)와 **이정표**(전수). 하나만 봐서 오늘 발판을 놓쳤다.
        int score = force ? -1 : completeness(w);
        boolean marks = !force && landmarksStand(w);
        if (score >= verifyMinPct && marks) {
            stage(w, "글판", this::ensurePanels);
            stage(w, "허수아비", this::ensureDummies);
            // ★ **침묵을 없앤다.** 이 갈래(이미 서 있다)는 여태 아무 말도 안 했다 —
            //   그래서 로그에 입도진이 한 줄도 없었고, 그 침묵이 "잘 지어졌다"로 읽혔다.
            census(w, "나루는 이미 서 있다 (완결 " + score + "% ≥ " + verifyMinPct + "%)");
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        if (score >= 0) {
            // ★ 이 줄이 없어서 반쯤 선 나루가 조용히 살아남았다. 이제 **소리를 내고 다시 짓는다**
            plugin.getLogger().warning("[입도진] 나루가 **반쯤 서 있다** — 완결 " + score
                    + "% (문턱 " + verifyMinPct + "% · 표본 1/" + verifySample + ")"
                    + " · 발판 " + countPlates(w) + "/" + plates.size()
                    + " · 종 " + (bellStands(w) ? "섬" : "★ 없음")
                    + ". 처음부터 다시 짓는다");
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
            // ★★ 하나가 죽어도 나머지는 선다.
            //
            //   과거의 병: 허수아비 하나가 던진 예외가 **onDone 전체**를 끊었다. onDone 은
            //   TickBudget.slice 의 try 블록 **안에서** 불린다 — 그래서 예외는 "[틱예산] 입도진 중단"
            //   한 줄로 삼켜졌고, 그 뒤의 글판도 발판도 완성 로그도 **전부 안 나왔다.**
            //   이제 각 단계는 제 울타리 안에서 죽는다. 죽으면 소리를 낸다. 나머지는 선다.
            stage(w, "글판 걷기", this::clearPanels);
            stage(w, "허수아비 걷기", this::clearDummies);
            stage(w, "글판 세우기", this::spawnPanels);
            stage(w, "허수아비 세우기", this::ensureDummies);
            census(w, "나루가 섰다 — 블록 " + plan.size());
            if (onDone != null) {
                onDone.run();
            }
        });
    }

    /** 조성의 한 단위 — <b>제 울타리 안에서 죽는다.</b> 하나가 터져도 나머지는 선다 */
    private void stage(World w, String what, java.util.function.Consumer<World> unit) {
        try {
            unit.accept(w);
        } catch (Throwable t) {
            plugin.getLogger().severe("[입도진] " + what + " 실패 — " + t
                    + " (나머지는 계속 세운다)");
            t.printStackTrace();
        }
    }

    /**
     * <b>세어서 말한다 — 침묵이 성공으로 읽히면 안 된다.</b>
     *
     * <p>여태 조성 로그는 {@code dummySpots.size()} 를 찍었다. 그것은 <b>등록부의 개수</b>이지
     * <b>선 것의 개수</b>가 아니다. 허수아비가 태어나자마자 지워져도 로그는 "허수아비 3"이라 말했다.
     * <b>로그가 거짓말을 한 것이다.</b> 이제 세계에게 묻는다 — 지금 실제로 몇이 서 있는가.
     */
    private void census(World w, String head) {
        int liveDummies = countDummies(w);
        int livePanels = countPanels(w);
        // ★ 블록도 센다 — 글판·허수아비만 세던 시절, **반쯤 선 잔교**는 아무도 안 세고 있었다
        int score = completeness(w);
        // ★★ 발판도 **세계에게 묻는다.** 여태 이 자리는 plates.size() — **등록부의 개수**를 찍었다.
        //   세계에 0개가 깔려 있어도 로그는 "발판 6" 이라 말했고, 사용자는 밟을 것이 없었다.
        int livePlates = countPlates(w);
        String line = "[입도진] " + head
                + " · 완결 " + score + "%"
                + " · 관문 " + stations.size()
                + " · 글판 " + livePanels + "/" + expectedPanels()
                + " · 발판 " + livePlates + "/" + plates.size()
                + " · 종 " + (bellStands(w) ? "섬" : "★ 없음")
                + " · 허수아비 " + liveDummies + "/" + dummies.size()
                + " · 난이도 " + w.getDifficulty();
        if (liveDummies < dummies.size() || livePanels < expectedPanels()
                || livePlates < plates.size() || !bellStands(w)
                || score < verifyMinPct) {
            plugin.getLogger().severe(line + "  ← ★ 등록부보다 적다. 무엇인가 조용히 죽었다");
        } else {
            plugin.getLogger().info(line);
        }
        // ★ 그리고 **한 번 더 본다.** 오늘의 병은 "세울 때는 있었는데 다음 틱에 사라진" 것이었다
        //   (평화 난이도가 몬스터를 지웠다). 세운 직후의 개수만 세면 그 병을 영영 못 본다.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            int after = countDummies(w);
            if (after < dummies.size()) {
                plugin.getLogger().severe("[입도진] ★ 허수아비가 세운 뒤에 사라졌다 — "
                        + after + "/" + dummies.size() + " (난이도 " + w.getDifficulty()
                        + " · PEACEFUL 이면 좀비는 매 틱 지워진다)");
            }
        }, 40L);
    }

    private int countDummies(World w) {
        int n = 0;
        for (Entity e : w.getEntities()) {
            if (e.isValid() && e.getPersistentDataContainer().has(KEY_DUMMY)) {
                n++;
            }
        }
        return n;
    }

    private int countPanels(World w) {
        int n = 0;
        for (Entity e : w.getEntities()) {
            if (e instanceof TextDisplay && e.isValid()
                    && e.getPersistentDataContainer().has(KEY_PANEL)) {
                n++;
            }
        }
        return n;
    }

    /**
     * <b>/혼천 입도 재조성</b> — 나루를 다시 세운다 (재접속 없이).
     * 땅을 다시 깔고, 글판을 다시 걸고, <b>허수아비를 다시 세운다.</b>
     */
    public void rebuild(Player player) {
        World w = world();
        if (w == null) {
            player.sendMessage(ChatColor.RED + displayName + "을(를) 열 수 없다.");
            return;
        }
        player.sendMessage(ChatColor.GRAY + "나루를 다시 세운다 — 잠시 걸린다.");
        build(w, true, () -> {
            if (!player.isOnline()) {
                return;
            }
            // ★ 사람이 **즉시 확인**할 수 있게 — 세계에게 물어서 말한다 (등록부의 개수가 아니다)
            player.sendMessage(ChatColor.GOLD + "나루가 다시 섰다 "
                    + ChatColor.GRAY + "— 허수아비 " + countDummies(w) + "/" + dummies.size()
                    + " · 글판 " + countPanels(w) + "/" + expectedPanels()
                    + " · 발판 " + countPlates(w) + "/" + plates.size()
                    + " · 종 " + (bellStands(w) ? "섬" : ChatColor.RED + "없음"));
            if (isAntechamber(player.getWorld())) {
                refreshPanels(player);
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

        barrier(out, gy, deck);

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
            out.add(new Place(cx + p.x(), deck + 1, cz + p.z(), PLATE, null));
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
            if (l != null && l.gated()) {
                n++;   // 판이 둘 — 할 수 있는 몸 / 없는 몸(예고). 사람마다 하나만 보인다
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
            if (l != null && l.gated()) {
                spawnPanel(w, gy, s.id() + "_없음", s, panelText(s, true), false);
            }
        }
        // ★★ 갓 뿌린 글판은 기본값이 「보임」이다 — 그 자리에 선 사람에게 즉시 가림을 다시 건다.
        //   B-131 회귀: onPanelsLoad(EntitiesLoadEvent)는 청크 **적재**만 잡고 spawn()은 못 잡는다.
        //   런타임 재건축(완결 미달 → 다시 짓기)이 이 갈래로 새 판을 뿌리면, 여기서 안 가리면
        //   present 플레이어는 how·예고 두 장을 겹쳐 본다. 이 재적용은 멱등이다 (show 는 미적재 무시).
        for (Player p : w.getPlayers()) {
            refreshPanels(p);
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
        if (lacks(player, l)) {
            return true;   // ★ 못 하는 것 때문에 길이 막히지 않는다. 그냥 지나간다 (판은 예고로 바뀐다)
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
        int current = oneAtATime ? currentStation(player) : stations.size() - 1;
        for (int i = 0; i < stations.size(); i++) {
            Station s = stations.get(i);
            Lesson l = lessons.get(s.lesson());
            boolean reached = i <= current;
            boolean gated = l != null && l.gated();
            boolean lacking = lacks(player, l);
            // 판이 둘이면 **하나만** 보인다: 할 수 있는 몸에게는 how, 없는 몸에게는 unavailable(예고)
            show(player, s.id(), reached && (!gated || !lacking));
            if (gated) {
                show(player, s.id() + "_없음", reached && lacking);
            }
        }
        shownThrough.put(player.getUniqueId(), current);
    }

    private void show(Player player, String panelId, boolean visible) {
        UUID id = panelEntities.get(panelId);
        if (id == null || !(Bukkit.getEntity(id) instanceof TextDisplay d)) {
            return;   // 아직 안 실린 글판 — {@link #onPanelsLoad} 가 실리는 순간 다시 가린다
        }
        if (visible) {
            player.showEntity(plugin, d);
        } else {
            player.hideEntity(plugin, d);
        }
    }

    /**
     * ★ <b>숨김이 적재보다 먼저 달리면 두 장이 다 보인다</b> (사용자 실측 2026-07-15 — 격·경공
     * 관문의 본문 판과 예고 판이 겹쳐 보였다).
     *
     * <p>관문 글판은 한 자리에 <b>두 장</b>(how + unavailable)이고 {@link #refreshPanels} 가
     * 사람마다 한 장을 가린다. 그런데 그 가림은 <b>엔티티가 실려 있어야</b> 걸린다 —
     * 진입 직후의 refresh 는 엔티티 청크 비동기 적재보다 빠를 수 있고, {@link #show} 는
     * 못 찾으면 조용히 지나갔다. 기본값은 「보임」이므로 <b>침묵의 값이 곧 겹침</b>이었다.
     *
     * <p>그래서: 나루의 엔티티가 실리는 순간 글판 명부를 다시 채우고(재기동 뒤의 빈 명부도
     * 여기서 되살아난다), 그 세계에 서 있는 사람들의 가림을 재적용한다. 이 둘은 멱등이다.
     */
    @EventHandler
    public void onPanelsLoad(EntitiesLoadEvent event) {
        if (!isAntechamber(event.getWorld())) {
            return;
        }
        boolean panels = false;
        for (Entity e : event.getEntities()) {
            if (e instanceof TextDisplay && e.getPersistentDataContainer().has(KEY_PANEL)) {
                String id = e.getPersistentDataContainer().get(KEY_PANEL, PersistentDataType.STRING);
                if (id != null) {
                    panelEntities.putIfAbsent(id, e.getUniqueId());
                    panels = true;
                }
            }
        }
        if (!panels) {
            return;
        }
        for (Player p : event.getWorld().getPlayers()) {
            refreshPanels(p);
        }
    }

    /**
     * <b>★★ 이 몸이 이 조작을 할 수 있는가</b> — <b>못 하는 것을 시키지 않는다.</b>
     *
     * <p>능(能)의 이름은 <b>등록부</b>가 적는다 ({@code lessons.list[].requires}). 코드는 그 이름의
     * <b>술어</b>만 갖는다 — 그리고 그 술어는 <b>지어내지 않고 제 주인에게 묻는다</b>:
     *
     * <table>
     *   <tr><td>{@code 두를_격}</td><td>{@link SkillEngine#armableGrades}(경지) 가 비지 않았는가</td></tr>
     *   <tr><td>{@code 허공_딛기}</td><td>{@link Gyeonggong#ceiling}(경지).airJumps() &gt; 0 인가
     *       ({@code gyeonggong.yml realm_ceiling} — <b>개화 전은 0 이다</b>)</td></tr>
     * </table>
     *
     * <p><b>모르는 이름은 "못 한다"로 답한다.</b> 없는 조작을 가르치는 것보다 안 가르치는 것이 낫고,
     * 감사({@code antechamber_audit.py} ③)가 그 이름을 잡아 준다.
     */
    private boolean capable(Player player, Lesson l) {
        if (!l.gated()) {
            return true;
        }
        try {
            String realm = plugin.skills().state(player).realm;
            if (realm == null) {
                return false;
            }
            return switch (l.requires()) {
                case "두를_격" -> !plugin.skillEngine().armableGrades(realm).isEmpty();
                case "허공_딛기" -> {
                    Gyeonggong gg = Gyeonggong.get();
                    yield gg != null && gg.open(realm) && gg.ceiling(realm).airJumps() > 0;
                }
                default -> false;   // 등록부가 지어낸 이름 — 코드는 흉내내지 않는다 (감사가 잡는다)
            };
        } catch (Throwable t) {
            return false;   // 모르면 "못 한다" 쪽으로 — 없는 조작을 가르치는 것보다 안 가르치는 게 낫다
        }
    }

    /** 이 사람에게 이 관문의 판이 <b>예고(unavailable)</b>로 떠야 하는가 */
    private boolean lacks(Player player, Lesson l) {
        return l != null && l.gated() && !capable(player, l);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  허수아비
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 이 몸이 허수아비인가 — <b>계기(計器)는 대립하지 않는다</b>는 판정 예외의 신원.
     * 등록부(antechamber.yml)가 못박았다: "안 죽고, 안 움직이고, 맞은 것을 말한다."
     * {@link SkillListener} 가 대립 판정에 앞서 이것을 묻는다 (B-132).
     */
    static boolean dummy(Entity e) {
        return e.getPersistentDataContainer().has(KEY_DUMMY);
    }

    private void clearDummies(World w) {
        for (Entity e : w.getEntities()) {
            if (e.getPersistentDataContainer().has(KEY_DUMMY)) {
                tally.remove(e.getUniqueId());
                e.remove();
            }
        }
    }

    /**
     * 허수아비를 세운다 — <b>하나가 죽어도 나머지는 선다.</b>
     *
     * <p>과거 병: 허수아비 하나가 던진 예외가 대기실 조성 <b>전체</b>를 죽였다 (허수아비도 글판도
     * 발판도 안 지어졌다). 이제 <b>한 몸이 제 울타리 안에서 죽는다</b> — 그리고 <b>죽으면 소리를 낸다.</b>
     */
    private void ensureDummies(World w) {
        // ★★ {@code >=} 가 아니라 {@code !=} 다. 옛 코드는 "등록부보다 많으면 됐다"고 넘어갔다 —
        //   그래서 재조성이 허수아비를 **쌓았다**: 등록부 6인데 세계에 24 가 서 있었다
        //   (조성이 네 번 돌 때마다 6씩 늘고, 늘어난 뒤로는 `24 >= 6` 이라 **영영 안 치웠다**).
        //   많은 것도 틀린 것이다. 겹쳐 선 허수아비는 히트박스가 겹쳐 타격 계측을 망친다.
        if (countDummies(w) == dummies.size()) {
            return;
        }
        clearDummies(w);
        int y = groundY(w) + road.deckY() + 1;
        int stood = 0;
        for (Dummy d : dummies) {
            try {
                spawnDummy(w, d, y);
                stood++;
            } catch (Throwable t) {
                // ★ 조용히 죽지 않는다. 이 몸 하나만 못 섰다고 말하고, 다음 몸을 세운다
                plugin.getLogger().severe("[입도진] 허수아비 '" + d.id() + "' 를 못 세웠다 — " + t);
                t.printStackTrace();
            }
        }
        if (stood < dummies.size()) {
            plugin.getLogger().severe("[입도진] 허수아비 " + stood + "/" + dummies.size()
                    + " 만 섰다 — 나머지는 위 예외를 보라");
        }
    }

    private void spawnDummy(World w, Dummy d, int y) {
        Location at = new Location(w, cx + d.x() + 0.5, y, cz + d.z() + 0.5, 90f, 0f);
        w.spawn(at, Zombie.class, e -> {
            e.setAI(false);              // 반격하지 않는다 (때리지도, 걷지도 않는다)
            e.setSilent(true);
            e.setCollidable(true);
            e.setRemoveWhenFarAway(false);
            e.setShouldBurnInDay(false);
            e.setAdult();
            e.setPersistent(true);
            e.getPersistentDataContainer().set(KEY_DUMMY, PersistentDataType.INTEGER,
                    d.durability());
            e.getPersistentDataContainer().set(KEY_DUMMY_LABEL, PersistentDataType.STRING,
                    d.label());
            // ★★ 체력은 **특성에게 물어서** 넣는다 — 숫자를 손으로 넣지 않는다.
            //   MAX_HEALTH 특성에는 제 범위(…1024)가 있어서, 기준값에 2048 을 적으면
            //   실효값은 1024 로 깎이는데 setHealth(2048) 은 "0..1024 여야 한다"며 터진다.
            //   그 예외 하나가 오늘 아침 대기실 조성 전체를 죽였다 (로그 09:00·09:43·09:47).
            //   getValue() 는 **이미 깎인 값**이다 — 상한이 몇이든 이 줄은 안 터진다.
            var attr = e.getAttribute(Attribute.MAX_HEALTH);
            if (attr != null) {
                attr.setBaseValue(DUMMY_HEALTH);
                e.setHealth(attr.getValue());
            }
            e.setInvulnerable(false);   // 맞는 것은 보여야 한다 (다만 죽지 않는다 — 리스너가 되돌린다)
            e.setCustomNameVisible(true);
            e.setCustomName(idleName(d.label(), d.durability()));
        });
    }

    private String idleName(String label, int durability) {
        return dummyIdle.replace("{label}", label)
                .replace("{durability}", String.valueOf(durability));
    }

    /**
     * 맞은 것을 말한다 — <b>명패가 장부다</b> (최근 · 누적 · 합수 · 평균 · TTK).
     * 눈금은 {@link Dojang} 의 명패와 같다. 서식은 등록부({@code dummies.hit})가 정한다.
     */
    private String hitName(String label, int durability, double[] t) {
        double avg = t[1] == 0 ? 0 : t[0] / t[1];
        int ttk = avg <= 0 ? 0 : (int) Math.ceil(durability / avg);
        return dummyHit.replace("{label}", label)
                .replace("{durability}", String.valueOf(durability))
                .replace("{last}", String.format("%.1f", t[2]))
                .replace("{total}", String.format("%.0f", t[0]))
                .replace("{hits}", String.valueOf((int) t[1]))
                .replace("{avg}", String.format("%.2f", avg))
                .replace("{ttk}", String.valueOf(ttk));
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
            // ★ 이미 맡긴 것이 있으면 **아무것도 파괴하지 않고 물러선다.**
            //
            //   여기서 두 번 틀렸다 (실측 2026-07-20):
            //   ① 그냥 덮어쓰면 → 앞서 맡긴 진짜 짐이 사라진다.
            //   ② "덮어쓰지 않는다"고 거르기만 하면 → 바로 아래 `clear()` 가 **지금 든 것**을 없앴다
            //      (신병 겸·고대잔해·황금사과가 그렇게 사라졌다). 거르는 것과 버리는 것은 다르다.
            //   ③ 앞의 것을 먼저 restore 해도 → `setContents` 가 **덮어쓰기**라 지금 든 것이 죽는다.
            //
            //   두 벌의 인벤토리를 한 몸에 넣을 방법은 없다. 그러니 **손대지 않는다.**
            //   꾸러미를 못 쥐여 주는 것은 불편이고, 짐을 지우는 것은 손실이다 — 불편을 고른다.
            if (stowed.containsKey(player.getUniqueId())) {
                plugin.getLogger().severe("[나루/짐] ★ " + player.getName()
                        + " 은(는) 이미 맡긴 짐이 있다 (" + STOW_FILE + ") — 앞뒤가 맞지 않는다. "
                        + "짐에 손대지 않고 꾸러미도 쥐여 주지 않는다. 관리자가 봐야 한다.");
                player.sendMessage(ChatColor.RED + "★ 맡긴 짐이 이미 있다 — 짐은 그대로 둔다. "
                        + "관리자에게 말하라.");
            } else {
                stowed.put(player.getUniqueId(), player.getInventory().getContents().clone());
                saveStow();   // ★ **맡는 순간** 적는다 — 여기서 죽어도 짐은 디스크에 있다
                player.getInventory().clear();
                player.getInventory().addItem(new ItemStack(Material.WOODEN_SWORD));
                player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
            }
        }
        player.setGameMode(GameMode.ADVENTURE);   // 손은 쓴다. 잔교는 못 부순다
        player.teleport(spawnAt(w));
        player.setFallDistance(0f);
        if (plugin.ledger(player.getUniqueId()).linked()) {
            player.sendMessage(revisitLine);
            // ★ B-124 — 과제 장부는 메모리뿐이라(progress 필드 주석) 건넌 몸에게도 관문이 도로
            //   열려 있다. 그 결을 한 줄로 말한다: 다시 하는 것은 자유고, 종은 과제를 묻지 않는다.
            if (!revisitLedgerLine.isEmpty()) {
                player.sendMessage(revisitLedgerLine);
            }
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
        // ★ 나루의 몸은 배부르다 — 이미 바닥(6)까지 닳아 들어온 몸도 달릴 수 있어야 한다.
        //   {@link #onHunger} 는 줄지 않게만 하므로, 채우는 것은 문이 한다 (들어올 때 한 번).
        player.setFoodLevel(20);
        player.setSaturation(20f);
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
            // ★ B-118 — 서장이 남았으면 종도 말린다. 판정은 「책이 지금 손에 있나」(토큰)가 아니라
            //   **「서장이 끝났나」**(다리의 서장 명단 — WorldBridge.seojangHolds)다: 새 몸은 붓(LLM)이
            //   서장을 짓는 수십 초 동안 토큰이 없어서, 토큰만 보면 문이 경주에서 이겨 서사를 통째로
            //   건너뛴다. 다리가 죽어 명단이 낡으면 붙들지 않는다 (아래 bridge_down 과 같은 원칙).
            if (WorldBridge.seojangHolds(player.getUniqueId())) {
                boolean bookInHand = SeojangBook.get() != null
                        && SeojangBook.get().tokenOf(player.getUniqueId()) != null;
                player.sendMessage(bookInHand ? seojangReadingLine : seojangWritingLine);
                return;
            }
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
        player.teleport(destination(player));
        player.setFallDistance(0f);
        boarding.remove(id);
        dockWaitSaid.remove(id);
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
        saveStow();   // ★ 돌려준 것은 그 자리에서 지운다 (두 번 돌려주면 짐이 복제된다)
        if (!kitTakeBackLine.isEmpty()) {
            player.sendMessage(kitTakeBackLine);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ★ 맡아 둔 짐의 디스크 (연무장 금고와 같은 손 — 원자적으로 쓴다)
    // ══════════════════════════════════════════════════════════════════════

    private File stowFile() {
        return new File(plugin.getDataFolder(), STOW_FILE);
    }

    /** <b>맡는 순간·돌려주는 순간</b> 굽는다. 여기서 죽어도 짐은 디스크에 있다. */
    private void saveStow() {
        File f = stowFile();
        if (stowed.isEmpty() && !f.isFile()) {
            return;
        }
        try {
            YamlConfiguration yml = new YamlConfiguration();
            stowed.forEach((id, items) -> yml.set(id.toString(),
                    Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items))));
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            yml.save(tmp);
            try {
                Files.move(tmp.toPath(), f.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException noAtomic) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().severe("[나루/짐] ★★ 맡은 짐을 못 구웠다 — 지금 서버가 죽으면 "
                    + "나루에 있는 사람이 짐을 잃는다: " + e);
        }
    }

    /**
     * 기동 — 맡아 둔 짐을 연다. <b>남아 있으면 짖는다</b> (관리자가 알아야 한다).
     *
     * <p>사람은 여기서 안 꺼낸다 — 접속할 때 꺼낸다. 오프라인인 몸은 만질 수 없다.
     * <b>깨진 파일은 덮지 않는다</b> — 옆으로 치우고 짖는다. 사람의 짐이 걸려 있다.
     */
    void loadStow() {
        stowed.clear();
        File f = stowFile();
        if (!f.isFile()) {
            return;
        }
        YamlConfiguration yml = new YamlConfiguration();
        try {
            yml.load(f);
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException broken) {
            File keep = new File(plugin.getDataFolder(),
                    STOW_FILE + ".broken-" + System.currentTimeMillis());
            boolean moved = f.renameTo(keep);
            plugin.getLogger().severe("[나루/짐] ★★ 맡은 짐을 읽을 수 없다: " + broken);
            plugin.getLogger().severe("[나루/짐] " + (moved
                    ? "깨진 것을 " + keep.getName() + " 로 옮겼다 — 손으로 살릴 수 있다."
                    : "★ 옮기지도 못했다 — 파일을 건드리지 마라."));
            return;
        }
        for (String key : yml.getKeys(false)) {
            try {
                stowed.put(UUID.fromString(key), ItemStack.deserializeItemsFromBytes(
                        Base64.getDecoder().decode(yml.getString(key, ""))));
            } catch (RuntimeException bad) {
                // ★ 한 사람이 깨졌다고 나머지를 버리지 않는다. 그 사람만 짖고 넘어간다.
                plugin.getLogger().severe("[나루/짐] ★ " + key + " 의 짐을 못 읽는다 — "
                        + STOW_FILE + " 에 원본은 남아 있다: " + bad);
            }
        }
        if (!stowed.isEmpty()) {
            plugin.getLogger().warning("[나루] ★ 맡은 짐이 " + stowed.size()
                    + "인 남아 있다 (나루에 있는 채로 서버가 내려갔다). 접속하면 돌려준다.");
        }
    }

    /**
     * <b>내리는 자리 — 등록부의 순서대로, 그러나 <u>재고 나서</u> 쓴다.</b>
     *
     * <p>★ <b>여기가 오늘의 병이 살던 자리다.</b> 옛 주석은 이렇게 적혀 있었다:
     * <i>"여기서 null 을 돌려주는 경로는 없다 — 그것이 '절대 갇히지 않는다'의 마지막 보루다."</i>
     * 그 보루가 사람을 <b>우물에 가뒀다</b>. {@code 장터} 앵커는 마을 <b>원점 표식</b>이고, 원점에는
     * 광장 <b>우물</b>이 서 있다 ({@code CheonghaBuilder.plazaAndWell}). 아무도 <b>"거기 설 수 있는가"</b>를
     * 재지 않았으므로, 이 함수는 <b>성공적으로</b> 사람을 우물 바닥에 내려놓았다.
     *
     * <p><b>고친 규칙</b> (사용자: <i>"모르는 장소를 작은 장소로 간주하는 것도 하나의 창작이다"</i>):
     * <ol>
     *   <li><b>1차</b> — 등록부 순서대로. 단 <b>{@link Standing#measure(Location) 설 수 있는 앵커}만</b> 쓴다.
     *       못 서는 앵커는 <b>건너뛴다</b> (다음 후보로).</li>
     *   <li><b>2차</b> — 다 못 서면, 그 앵커들 <b>근처에서 설 자리를 실제로 찾는다</b>. 그리고
     *       <b>짖는다</b> (SEVERE) — 관리자가 알아야 한다. <b>사람에게도 말한다</b>.</li>
     *   <li><b>3차</b> — 등록부가 통째로 죽었으면 세계 스폰 <b>근처를</b> 뒤진다.
     *       <b>스폰 좌표 그 자체를 믿지 않는다</b> — 그것을 믿은 것이 오늘의 창작이었다.</li>
     *   <li><b>4차</b> — 그마저 실패. 그때만 스폰에 떨군다. <b>조용히는 아니다</b> (SEVERE + 사람에게 고지).</li>
     * </ol>
     */
    private Location destination(Player who) {
        List<String> notes = new ArrayList<>();
        List<Location> fallbacks = new ArrayList<>();

        // ═══ ★★ 집안이 자리를 정한다 — **전원이 같은 곳에 내리지 않는다** (2026-07-13) ═══
        //
        // 사용자: *"**모든 사람이 똑같은 위치에서 똑같이 소환되는 것도 아니고**",
        //          "**마인크래프트에서도 신분이 적용**되어야 합니다."*
        //
        // 【전에는】 destinations(= [흑수나루, 장터])의 첫 유효 앵커로 **전원이** 갔다.
        //   그리고 player_creation.yml 의 start_location 11군데는 **읽는 코드가 0줄**이었다.
        //
        // 【이제는】 봇이 시트에 **start_anchor**(집안이 정한 앵커 이름)를 실어 내려보낸다
        //   (player_creation.yml mvt_start.by_family — 근거는 각 집안의 world_link·grants).
        //   무가의 자식은 **전장**(월례 전표가 오는 곳)에, 객잔집 자식은 **청하객잔**에,
        //   의원집 자식은 **의방**에 선다.
        //
        // ★ 앵커 그 자체를 믿지 않는다 — 장터 앵커는 **우물 한가운데**다.
        //   Standing 이 재고, 못 서면 아래의 옛 길(destinations)로 조용히 떨어진다.
        WorldBridge.Sheet sheet = WorldBridge.state().sheet(who.getUniqueId());
        String home = sheet == null ? null : sheet.startAnchor();
        if (home != null && !home.isBlank()) {
            Location at = plugin.anchor(home);
            if (at == null || at.getWorld() == null || isAntechamber(at.getWorld())) {
                notes.add("집안의 자리 「" + home + "」 — 세계에 그 앵커가 없다 (조성 전인가)");
            } else {
                Standing.Verdict v = Standing.measure(at);
                if (v.ok()) {
                    return at;
                }
                Location spot = Standing.landing(at);
                if (spot != null) {
                    return spot;   // 앵커 곁에 내린다 (우물에 빠뜨리지 않는다)
                }
                notes.add("집안의 자리 「" + home + "」 에 설 곳이 없다 — " + v.why());
            }
        }

        for (String name : destinations) {
            Location at = plugin.anchor(name);
            if (at == null || at.getWorld() == null || isAntechamber(at.getWorld())) {
                notes.add("등록부는 「" + name + "」 를 부르는데 세계에 그 앵커가 없다");
                continue;
            }
            Standing.Verdict v = Standing.measure(at);
            if (v.ok()) {
                return at;   // 재 보고 통과했다 — 여기 내린다
            }
            notes.add("「" + name + "」 " + Standing.describe(at) + " — " + v.why());
            fallbacks.add(at);
        }

        for (Location at : fallbacks) {
            Location spot = Standing.landing(at);
            if (spot != null) {
                bark(who, notes, "앵커 곁에서 설 자리를 찾았다", spot);
                return spot;
            }
        }

        for (World w : Bukkit.getWorlds()) {
            if (isAntechamber(w) || Dojang.isDojang(w)) {
                continue;
            }
            Location spot = Standing.landing(w.getSpawnLocation(), 32);
            if (spot != null) {
                notes.add("등록부의 앵커를 하나도 못 썼다 — 세계 스폰 근처를 뒤졌다");
                bark(who, notes, "세계 스폰 곁에서 설 자리를 찾았다", spot);
                return spot;
            }
        }

        Location last = Bukkit.getWorlds().get(0).getSpawnLocation();
        notes.add("세계 스폰 근처 32칸에도 설 자리가 없다 — 세계가 통째로 이상하다");
        bark(who, notes, "설 자리를 못 찾았다 · 세계 스폰에 떨군다 (갇힐 수 있다)", last);
        return last;
    }

    /**
     * <b>짖는다.</b> 내리는 자리가 등록부대로가 아니었으면 <b>관리자에게 SEVERE 로</b>, 그리고
     * <b>사람에게도</b> 왜 여기 내렸는지 말한다. ★ 조용한 기본값을 금한다.
     */
    private void bark(Player who, List<String> notes, String what, Location spot) {
        java.util.logging.Logger log = plugin.getLogger();
        log.severe("[나루/착지] ★ 등록부의 앵커에 사람을 내릴 수 없었다 — " + what
                + " " + Standing.describe(spot));
        for (String n : notes) {
            log.severe("[나루/착지]   · " + n);
        }
        log.severe("[나루/착지]   → 고치려면: /혼천 앵커검사 (무엇이 썩었는지) · /혼천 앵커재측 (고친다)");
        if (who != null) {
            who.sendMessage(ChatColor.RED + "── 사공이 뱃머리를 돌렸다 ──");
            for (String n : notes) {
                who.sendMessage(ChatColor.GRAY + "  " + n);
            }
            who.sendMessage(ChatColor.YELLOW + "그래서 " + Standing.describe(spot)
                    + ChatColor.GRAY + " 에 내렸다 — " + what + ".");
        }
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
            // ★ 안전망 — 맡긴 짐이 있는데 **나루 밖에** 서 있다면 지금 돌려준다.
            //   나루를 벗어나는 정상 경로는 restore() 하나지만, 리붓·강제 이동·다른 손으로
            //   밖에 나가 있을 수 있다. 그때 짐이 디스크에만 남아 영영 안 돌아오면 그것도 손실이다.
            //   (틱을 미뤄서 부른다 — 접속 그 순간의 인벤토리 조작은 클라에 안 붙는 일이 있다.)
            if (stowed.containsKey(player.getUniqueId()) && !isAntechamber(player.getWorld())) {
                plugin.getLogger().warning("[나루/짐] " + player.getName()
                        + " 이(가) 나루 밖에 있는데 맡긴 짐이 남아 있다 — 지금 돌려준다");
                restore(player);
            }
            if (plugin.ledger(player.getUniqueId()).linked()) {
                if (isAntechamber(player.getWorld())
                        && !WorldBridge.seojangHolds(player.getUniqueId())
                        && atDock(player)) {
                    // 없는 사이에 이름이 올랐다 — 문은 이미 열려 있었다.
                    // ★ B-118 — 단, 서장이 남은 몸은 여기서도 끌고 가지 않는다 (서장 도중 나갔다
                    //   돌아온 몸): watchGate(5틱)가 배를 세워 두고, 책은 다리가 다시 배달하며,
                    //   서장이 끝나면 그 시계가 반드시 건넨다.
                    // ★ B-120 — 마당(부두 밖)에서 나갔다 돌아온 몸도 즉시 끌고 가지 않는다 (atDock):
                    //   재접속 순간의 텔레포트가 걸어온 길의 글판을 통째로 지웠다. watchGate 의
                    //   시계가 부두에 서는 순간 반드시 건넨다 — 갇힘은 없다.
                    depart(player, List.of());
                }
                return;   // 강호에 든 자는 나루를 다시 안 거친다
            }
            enter(player);
        }, 2L);
    }

    /**
     * ★ <b>죽어서 돌아오는 자리도 「내리는 자리」다.</b>
     *
     * <p>침대가 없으면 바닐라는 <b>월드 스폰</b>에 떨군다 — 그리고 이 세계의 월드 스폰은 마을 원점,
     * 곧 <b>광장 우물</b>의 기둥이다. 도강만 고치고 여기를 두면 <b>죽을 때마다 우물에 빠진다</b>.
     *
     * <p><b>침대·리스폰 앵커는 건드리지 않는다</b> ({@code isBedSpawn}/{@code isAnchorSpawn}) — 그건
     * 사람이 <b>제 손으로 고른 자리</b>다. 제가 담을 쌓고 그 안에서 자는 것은 그 사람의 자유다.
     * 우리가 고치는 것은 <b>아무도 고르지 않은 자리</b>, 곧 월드 스폰뿐이다.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        if (event.isBedSpawn() || event.isAnchorSpawn()) {
            return;   // 사람이 고른 자리 — 남의 집에 손대지 않는다
        }
        Location at = event.getRespawnLocation();
        if (at == null || at.getWorld() == null
                || isAntechamber(at.getWorld()) || Dojang.isDojang(at.getWorld())) {
            return;
        }
        Standing.Verdict v = Standing.measure(at);
        if (v.ok()) {
            return;
        }
        Location spot = Standing.landing(at, 32);
        if (spot == null) {
            plugin.getLogger().severe("[부활] ★ 월드 스폰 " + Standing.describe(at) + " — " + v.why()
                    + " · 둘레 32칸에도 설 자리가 없다. 사람이 갇힌다 — /혼천 앵커검사");
            return;
        }
        event.setRespawnLocation(spot);
        plugin.getLogger().severe("[부활] ★ 월드 스폰 " + Standing.describe(at) + " 에 사람을 세울 수 없다 — "
                + v.why() + " · " + Standing.describe(spot) + " 로 옮겨 내렸다.");
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(ChatColor.YELLOW + "세계의 스폰 자리에는 설 수 없다 ("
                        + ChatColor.GRAY + v.why() + ChatColor.YELLOW + ") — "
                        + Standing.describe(spot) + " 에 내렸다.");
            }
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

    /**
     * 과제: 손 — 허수아비를 <b>실제로 쳤을 때만</b>. 그리고 <b>명패가 맞은 것을 말한다.</b>
     *
     * <p>피해는 <b>다음 틱에</b> 읽는다 — 우리 무공 리스너가 피해를 고쳐 쓴 <b>뒤</b>의 값이 진실이다
     * ({@link Dojang#onDamage} 와 같은 이유·같은 눈금).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)
                || !isAntechamber(player.getWorld())
                || !(event.getEntity() instanceof LivingEntity dummy)
                || !dummy.getPersistentDataContainer().has(KEY_DUMMY)) {
            return;
        }
        double[] t = tally.computeIfAbsent(dummy.getUniqueId(), k -> new double[]{0, 0, 0});
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!dummy.isValid()) {
                return;
            }
            double dealt = event.getFinalDamage();
            t[0] += dealt;
            t[1] += 1;
            t[2] = dealt;
            var attr = dummy.getAttribute(Attribute.MAX_HEALTH);
            if (attr != null) {
                dummy.setHealth(attr.getValue());   // 죽지 않는다 (장부를 위해 산다)
            }
            int durability = dummy.getPersistentDataContainer()
                    .getOrDefault(KEY_DUMMY, PersistentDataType.INTEGER, 20);
            String label = dummy.getPersistentDataContainer()
                    .getOrDefault(KEY_DUMMY_LABEL, PersistentDataType.STRING, "허수아비");
            dummy.setCustomName(hitName(label, durability, t));
        });
        bump(player, "손");
    }

    /**
     * <b>나루에서 사람은 죽지 않는다</b> — 그리고 그 약속을 <b>난이도가 아니라 이 손이</b> 지킨다.
     *
     * <p>여태는 난이도 PEACEFUL 이 지켰다. 그런데 평화는 <b>몬스터를 지운다</b> — 허수아비의 몸이
     * 좀비이므로, 그 약속이 곧 <b>때릴 상대의 부재</b>였다. 사용자가 본 것이 그것이다:
     * <i>"인증 전까지 때릴 상대가 없습니다."</i>
     *
     * <p>이제 나루는 EASY 다 (허수아비가 산다). 대신 <b>사람에게 오는 모든 피해를 여기서 끊는다</b> —
     * 굶주림도, 선인장도, 있지도 않은 몹도. 약속은 그대로이고, 허수아비만 살아났다.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (damagePlayers || !(event.getEntity() instanceof Player p)
                || !isAntechamber(p.getWorld())) {
            return;
        }
        event.setCancelled(true);
    }

    /**
     * <b>나루에서는 배가 고프지 않다</b> — "죽지 않는다"({@link #onPlayerDamage})와 같은 결.
     *
     * <p>사용자 실측 (2026-07-15): <i>"배가 고프면 달림 과제를 깰 수가 없네요."</i> 기전:
     * 전역 허기 규칙(world_purity.yml hunger)의 {@code floor: 6}은 굶어 죽지 않게 하는
     * 바닥인데, <b>바닐라 달리기는 허기 6 초과라야 된다</b> — 나루의 새 몸이 6까지 닳으면
     * 회피(달림) 과제가 정확히 그 바닥에 막힌다. 배우는 자리의 계기는 몸이 아니라 손이어야
     * 한다 — 나루에서는 허기가 줄지 않는다 (먹는 것은 막지 않는다. 강호에 나가면 배는
     * 강호의 규칙대로 고파진다).
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player p) || !isAntechamber(p.getWorld())) {
            return;
        }
        if (event.getFoodLevel() < p.getFoodLevel()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        // ★★ **접속이 끊길 때는 맡은 짐에 손대지 않는다** (실측 2026-07-20).
        //   예전에는 여기서 돌려주고 기록을 지웠다. 그런데 **종료 중의 quit** 에서는
        //   이 `setContents` 가 디스크의 playerdata 까지 못 간다 — 사람은 이미 떠나는 중이다.
        //   그래서 짐은 인벤토리에도, 기록에도 없게 됐다. **그것이 짐을 잃던 자리다.**
        //   (측정: 정상 재기동 한 번에 신병·에메랄드·황금사과가 통째로 사라졌다.)
        //
        //   이제 기록은 **나루에 있는 동안 그대로 둔다.** 끊겼다 다시 들어와도 그 사람은
        //   여전히 나루에 있고 손에는 꾸러미가 있다 — 진짜 짐은 디스크에 그대로다.
        //   돌려주는 자리는 하나뿐이다: **나루를 실제로 벗어날 때** ({@link #restore}).
        progress.remove(id);
        gesturesSeen.remove(id);
        lastArmed.remove(id);
        boarding.remove(id);
        dockWaitSaid.remove(id);
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

    /**
     * 과제: 격 — Shift+우클릭으로 두름이 바뀌는 순간.
     *
     * <p>★ B-124 (실사용: <i>"캐릭터 초기화를 하지 않으면 설명대로 해도 안 깨지는 건가?"</i>) —
     * <b>못 하는 몸은 아예 안 본다</b> ({@code requires: 두를_격}). 접합 전의 몸은 범인이고
     * ({@code player_creation.yml starting_realm}), 범인의 {@code armableGrades} 는 비어 있어
     * SkillListener 가 순환 자체를 거절한다("단전이 열리지 않았다"). 그 몸에게 이 과제는
     * "영영 안 깨지는" 것이 아니라 <b>없는 것</b>이다: {@link #applicable} 에서 빠져
     * all_done 을 막지 않고, 판은 예고({@code unavailable})로 바뀌어 이유를 말하며,
     * {@link #passed} 가 관문을 지나간 것으로 쳐 길도 안 막힌다. 접합으로 경지가 서면
     * {@link #watchGate} 가 판을 다시 세우고 — 그때부터 이 눈이 뜬다.
     */
    private void watchArmed(Player player) {
        Lesson l = lessons.get("격");
        if (l == null || complete(player, l) || !capable(player, l)) {
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

    /**
     * 과제: 경공 — {@code gyeonggong.yml activate}: <b>"공중에서 점프 키 한 번 더"</b> (더블 점프).
     *
     * <p>구판은 {@code isSprinting() && !isOnGround()} 를 봤다 — 그것은 <b>달리며 점프</b>의 눈이었고,
     * 발동이 손가락으로 옮겨간 지금은 <b>그냥 달리다 뛴 몸</b>까지 통과시킨다. 그래서 이제
     * <b>경공이 실제로 켜졌는가</b>를 그 주인({@link GyeonggongListener#riding})에게 직접 묻는다 —
     * 과제는 <b>흉내</b>가 아니라 <b>발동</b>을 봐야 한다.
     *
     * <p>★ 그리고 <b>못 하는 몸은 아예 안 본다</b> ({@code requires: 허공_딛기}). 나루에 서는 몸은
     * <b>범인</b>이고 ({@code player_creation.yml starting_realm}), {@code gyeonggong.yml realm_ceiling}
     * 이 범인·삼류·이류의 {@code air_jumps} 를 <b>0</b> 으로 적어 뒀다 — <b>개화 전에는 안 켜진다.</b>
     */
    private void watchGyeonggong(Player player) {
        Lesson l = lessons.get("경공");
        if (l == null || complete(player, l) || !capable(player, l)) {
            return;
        }
        GyeonggongListener gg = plugin.gyeonggong();
        if (gg != null && gg.riding(player) && !player.isOnGround()) {
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
            // ★ 【실사용 2026-07-14】 접합 6초 뒤 자동 출발이 **서장을 읽는 사람을 책째로 끌고 갔다** —
            //   장면 도중 청하현으로 이동돼 서사가 끊겼다. 서장의 문은 서장이 닫는다(에필로그 [강호로
            //   나선다]) — 그러므로 **서장이 끝날 때까지 기다린다.**
            //
            // ★ B-118 【실사용 2026-07-14 · 부계정】 그 판정이 토큰(이미 배달된 책)이었을 때는 여전히
            //   경주에서 졌다 — 새 몸은 붓(LLM)이 서장을 짓는 수십 초 동안 토큰이 없다. 그래서 이제
            //   **다리의 서장 명단**(WorldBridge.seojangHolds — 쓰는_중 포함)을 본다: 접합 즉시 실리고,
            //   에필로그 [강호로 나선다] 가 눌려야 사라진다. 갇힘은 없다 — 끝나면 반드시 건너고,
            //   다리가 죽어 명단이 낡아도 붙들지 않는다 (gate.bridge_down 과 같은 원칙).
            if (WorldBridge.seojangHolds(id) && !seojangWaitLine.isEmpty()) {
                player.sendMessage(seojangWaitLine);   // 배는 붓을 기다린다 — 침묵 금지
            }
            Bukkit.getScheduler().runTaskTimer(plugin, task -> {
                if (!player.isOnline() || !isAntechamber(player.getWorld())
                        || !plugin.ledger(id).linked()) {
                    task.cancel();
                    return;
                }
                if (WorldBridge.seojangHolds(id)) {
                    return;   // 서장이 남았다 — 붓이 먼저다
                }
                // ★ B-120 【실사용 2026-07-14 · 부계정】 — **배는 부두에서 뜬다.**
                //   접합 6초 뒤 자동 출발이 마당에서 몸짓(태세 셋)을 배우던 사람을 끌고 갔다 —
                //   세계가 바뀌는 순간 걸어온 길의 글판이 전부 사라진다 (글판은 이 세계의 것이다).
                //   그래서 부두 마당(auto_cross_from 관문) 밖에 선 몸은 태우지 않고 기다린다.
                //   문은 잠기지 않는다: 종(cross)은 어디서든 울리고, 부두에 서면 배는 반드시 뜬다.
                if (!atDock(player)) {
                    if (dockWaitSaid.add(id) && !dockWaitLine.isEmpty()) {
                        player.sendMessage(dockWaitLine);   // 침묵 금지 — 왜 안 뜨는지 한 번 말한다
                    }
                    return;
                }
                task.cancel();
                depart(player, List.of());
            }, autoCrossSeconds * 20L, 40L);
        }
    }

    /**
     * B-120 — 이 몸이 <b>부두 마당</b>({@code gate.opened.auto_cross_from} 관문)에 서 있는가.
     * 자동 출발은 여기 선 몸만 태운다 — 서쪽 마당에서 배우는 사람을 끌고 가지 않는다.
     *
     * <p><b>갇힘 금지가 먼저다:</b> 등록부가 비었거나 stations 에 없는 이름을 불렀으면
     * <b>어디서든 태운다</b>(옛 동작) — 오탈자 하나가 사람을 나루에 가두면 안 된다.
     * 관문 x 는 등록부 좌표(마을 중심 기준)이므로 사람의 세계 x 에서 {@code cx} 를 빼고 잰다.
     */
    private boolean atDock(Player player) {
        if (autoCrossFrom.isEmpty()) {
            return true;
        }
        for (Station s : stations) {
            if (s.id().equals(autoCrossFrom)) {
                return player.getLocation().getX() - cx >= s.x() - s.half();
            }
        }
        return true;   // 등록부가 모르는 이름 — 붙들지 않는다 (갇힘 금지)
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
            flashCount(player, l, now + 1);
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

    /**
     * 과제 카운트 한 줄 — ★ <b>B-123: 맨 {@code sendActionBar} 를 버리고 B-116 의 flash
     * 단일 창구({@link SkillListener#flash})를 탄다.</b> 입도진이 마지막 남은 맨 액션바 손이었다.
     *
     * <p><b>겹침의 기전</b> (실사용 2026-07-14): 격 순환 한 사건이 판정 flash("검기 — …")와
     * 이 카운트를 같은 액션바 줄에 세웠다 — Shift 는 웅크림이기도 해서 태세 과제("태세 … n/3")가
     * 같은 순간에 셈을 했다. 맨 sendActionBar 는 다음 statusBar 틱(≤0.2초)에 덮여 겹쳐 읽히고,
     * flash 로 바로 쏘면 "마지막이 이김" 규칙이 카운트로 판정을 지운다 — 어느 쪽이든 한쪽이 안 읽힌다.
     *
     * <p><b>순서로 푼다 (합성이 아니라)</b>: 카운트는 읽을 시간
     * ({@code skill_motion.yml hud.flash_read_ticks}) 하나 <b>뒤에</b> 줄을 받는다 —
     * 같은 사건의 판정 flash 가 제 시간을 다 읽히고, 그 다음 카운트가 같은 시간만큼 읽힌다.
     * 합성은 못 한다: 판정의 글자는 SkillListener 의 것이고 이 손은 그 글자를 모른다 —
     * 지어서 병기하면 화면이 세계에 대해 거짓말할 수 있다. 눈금은 그 flash 의 것을 그대로 쓴다
     * (하드코딩 금지 — {@code engine.hudFlashTicks()}, B-116 과 같은 정본).
     * 기다리는 사이 과제가 이미 닫혔으면(연타) 낡은 카운트는 그리지 않는다 — done 줄이 이미 말했다.
     */
    private void flashCount(Player player, Lesson l, int n) {
        String text = ChatColor.GRAY + l.title() + "  "
                + ChatColor.WHITE + n + ChatColor.GRAY + "/" + l.count();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            SkillListener skills = plugin.skills();
            if (skills != null && player.isOnline()
                    && isAntechamber(player.getWorld()) && !complete(player, l)) {
                skills.flash(player, text);
            }
        }, plugin.skillEngine().hudFlashTicks());
    }

    /**
     * 이 사람이 <b>실제로 할 수 있는</b> 과제만. <b>못 하는 것을 못 했다고 세지 않는다.</b>
     *
     * <p>★ 이것이 없어서 <b>'몸이 알았다'(all_done)가 영영 안 떴다</b>: 경공 과제에 {@code requires} 가
     * 없던 시절, 나루에 오는 모든 몸(범인 — {@code air_jumps} 0)이 그 과제를 <b>영원히</b> 못 닫았고,
     * {@code allMatch(complete)} 는 언제나 거짓이었다. <b>아무도 다 끝낼 수 없는 튜토리얼이었다.</b>
     */
    private List<Lesson> applicable(Player player) {
        List<Lesson> out = new ArrayList<>();
        for (Lesson l : lessons.values()) {
            if (lacks(player, l)) {
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
            // ★ 여기서도 **돌려주지 않는다** — quit 과 같은 이유다: 종료 중의 setContents 는
            //   playerdata 까지 못 갈 수 있고, 그 사이 기록을 지우면 짐은 어디에도 없다.
            //   기록만 디스크에 맞추고 떠난다. 다음 기동의 loadStow() 가 그대로 되살린다.
            clearPanels(w);
        }
        // ★ **지우기 전에 굽는다.** 여기가 짐을 잃던 자리다 (실측 2026-07-20):
        //   위 반복은 나루 월드의 getPlayers() 를 도는데 **종료 시점엔 이미 아무도 없다.**
        //   그래서 아무도 못 돌려받고, 곧바로 stowed.clear() 가 증거까지 지웠다.
        //   이제 디스크가 진실을 쥔다 — 맵을 비워도 다음 기동이 loadStow() 로 되살린다.
        saveStow();
        stowed.clear();   // 메모리만 비운다 (파일은 위에서 이미 맞췄다 — 다시 굽지 않는다)
        tally.clear();
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

    /** 허수아비 한 몸 — 이름도 내구도 <b>등록부가 짓는다</b> (코드가 지어내지 않는다) */
    private record Dummy(String id, String label, int x, int z, int durability) { }

    private record Lighting(int postEvery, boolean postAlternate, int postZ,
                            Set<String> brazierStations, boolean hutLantern,
                            double darkMinPct, double darkMaxPct, double mainDarkMaxPct,
                            double lampDensityMaxPct, int mainLightSpanMin) { }

    /**
     * 과제 하나. {@code requires} 는 <b>이 조작을 할 수 있는 몸</b>의 이름이다 (빈 문자열 = 누구나).
     *
     * <p>등록부가 능(能)의 이름을 적고, 코드가 그 이름의 <b>술어</b>를 갖는다
     * ({@link #capable}). 등록부에 없는 이름을 코드가 지어내지 않고, 코드에 없는 이름을 등록부가
     * 적으면 {@code tools/antechamber_audit.py} 가 잡는다.
     */
    private record Lesson(String id, String title, String how, String detect, int count, String done,
                          Set<String> gestures, String command, boolean needsArgs,
                          String requires, String unavailable) {

        /** 이 과제가 <b>경지에 따라 없을 수도 있는</b> 것인가 (판이 둘 — 할 수 있는 몸 / 없는 몸) */
        boolean gated() {
            return !requires.isEmpty();
        }
    }

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

    /**
     * <b>세계의 없음</b> — 늪 밖으로는 나갈 수 없다.
     *
     * <p>사용자 보고: <i>"외부로 나갈수도 있으니 투명블록으로 늪을 제외한 곳 다 막아서 평지로 못나가게."</i>
     *
     * <p>물안개(mist)는 <b>되돌림</b>이었다 — 나간 사람을 데려온다. 그러나 <b>되돌리기 전에 평지가 보인다.</b>
     * 보이는 순간 그곳은 "갈 수 있는 곳"이 되고, <b>길이 하나라는 약속이 깨진다.</b>
     * 습지가 길을 만드는 것은 "물이라서"가 아니라 <b>물 너머에 아무것도 없어서</b>다.
     *
     * <p>{@code BARRIER} 는 보이지도 않고 나갈 수도 없다. 세계의 <b>끝</b>이 아니라 세계의 <b>없음</b>이다.
     * 높이는 경공으로도 못 넘는다 (전 경지 도약 최대 3.2m). 천장도 덮는다 — 창의를 막는 것이 아니라
     * <b>길을 지키는 것</b>이다. 나루는 놀이터가 아니라 <b>문지방</b>이다.
     */
    private void barrier(List<Place> out, int gy, int deck) {
        if (!barrierOn) {
            return;
        }
        int x1 = marsh.x1() - barrierMargin;
        int x2 = marsh.x2() + barrierMargin;
        int z1 = marsh.z1() - barrierMargin;
        int z2 = marsh.z2() + barrierMargin;
        int base = gy - 1;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
                if (edge) {
                    for (int y = base; y <= deck + barrierHeight; y++) {
                        out.add(new Place(cx + x, y, cz + z, Material.BARRIER, null));
                    }
                } else if (barrierCap) {
                    out.add(new Place(cx + x, deck + barrierHeight, cz + z, Material.BARRIER, null));
                }
            }
        }
    }

}
