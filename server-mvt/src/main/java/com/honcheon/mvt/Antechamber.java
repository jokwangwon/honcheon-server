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
import org.bukkit.entity.Villager;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
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
 * <p><b>흐름</b> — 관문은 둘뿐이다 (맞이 → 나루 · ★3차 순수 문지방). 판은 전부 <b>안내판</b>이고
 * 늘 보인다 — ★5차 개정(2026-07-24)으로 과제와 그 기계(진척 장부·감지·순차 공개)가 폐지됐다.
 * 나루는 시험하지 않는다: 사이는 갈대와 등롱과 걷기다, 그리고 그 걷기가 서장의 붓을 기다리는 시간이다.
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
 * 문은 이름이 연다(<b>글판은 안내이지 자물쇠가 아니다 — 종은 언제나 울린다</b>) · 문장·좌표·조건은 config 가 정본 ·
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

    /** 사공의 몸 표식 (3차 개정 추기 — 실사용 "나루에 섭구가 없음") — ensure 가 정확히 한 몸을 지킨다 */
    private static final NamespacedKey KEY_FERRYMAN = new NamespacedKey("honcheon", "ipdo_ferryman");
    /** 허수아비의 등급표(이름·내구) — 명패가 이것을 말한다 (HitFeedback 공통 명패도 이 이름을 쓴다) */
    static final NamespacedKey KEY_DUMMY_LABEL = new NamespacedKey("honcheon", "ipdo_dummy_label");

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
    // ★명계 개정 (2026-07-24 사용자 확정) — 저승 구간(부두 관문 서쪽)의 넋등 리듬
    private final int soulEvery;
    private final String soulUntilStation;
    // ★삼도천 화폭 (사용자 확정 2026-07-24 — tutorial_rooting.md §7 · config canvas)
    private final int lilyHash;
    private final long fixedTime;
    private final int groveZFrom;
    private final int groveSpacing;
    private final double groveThreshold;
    private final int[] groveShift;
    private final int[] groveHeight;
    private final double[] mudBand;
    private final int relicZ;
    private final int[] relicX;
    private final int relicPostHash;
    private final List<Integer> relicLanterns = new ArrayList<>();
    private final int lycorisHash;
    private final int[] ridgeX;
    private final int[] ridgeZ;
    private final int[] horizonLight;
    /** ★B-179 — 기억의 회랑 (삼도천 항해). 서장의 책이 열리는 자리와 사이의 시간 */
    private final Voyage voyage;
    /** ★B-179 2차 — 몸으로 겪는 서장 (정거장의 기억 무대 · 등불 선택) */
    private final SeojangStage stage;
    private final Hut hut;
    // 사공의 몸 (3차 개정 추기) — pos 길이 0 = 몸 없음 (등록부가 스위치)
    private final int[] ferrymanPos;
    private final String ferrymanName;
    private final List<String> ferrymanLines;
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

    private final boolean kitGive;
    private final String kitLine;
    private final String kitTakeBackLine;

    // 【묘비】 과제 기계(oneAtATime·doneLine·allDone·lessons 장부) — ★5차 개정 (2026-07-24
    //   사용자 지시 "아직 입도진에 과제가 존재 — 제거"). 나루는 시험하지 않는다: 판은 안내판
    //   (Station.panel)이고, 문은 이름이 연다. 계보는 config lessons 묘비(§7)에 있다.

    private final String plateEcho;
    private final String plateHint;
    private final int plateCooldown;
    private final List<Plate> plates = new ArrayList<>();

    private final Panels panelSpec;
    private final String arrivalPanelId;

    // ─── 장부 (사람마다) ───
    // 【묘비】 과제 진척 장부(progress·gesturesSeen·lastArmed — B-124 「메모리뿐」 설계 포함) —
    //   ★5차 개정으로 과제 자체가 폐지돼 장부도 없다. 문(cross)은 원래 과제를 안 봤다 — 그 계약만 남는다.
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

    /**
     * 재방문자 — <b>이미 건넌 몸으로 나루에 든 사람</b> (/혼천 입도 · 나루에서 로그아웃한 재접속).
     * 자동 출항은 <b>첫 건넘의 의식</b>이다 — 재방문자를 태우면 부두에 선 사공에게 말도 걸기 전에
     * 실려 간다 (실사용 2026-07-24: "우클릭 하기도 전에 종 근처로 가서 바로 청하현으로").
     * 재방문의 문은 종이다 (arrival.revisit_line 이 이미 그렇게 말한다 — "종을 울리면 언제든").
     * 메모리뿐이다 (과제 장부와 같은 계약) — 건너면(depart) 지운다.
     */
    private final Set<UUID> revisiting = new LinkedHashSet<>();
    /** B-120 — 부두를 기다린다는 말을 이미 들은 사람 (자동 출발 대기 안내는 한 번만) */
    private final Set<UUID> dockWaitSaid = new LinkedHashSet<>();
    /** 발판 연타 방지 — (사람/발판) → 다시 밟을 수 있는 틱 */
    private final Map<String, Long> plateCooldowns = new HashMap<>();
    /** 세운 글판 — 관문 id → 엔티티 (판은 관문마다 하나 · 전부 안내판이라 늘 보인다) */
    private final Map<String, UUID> panelEntities = new LinkedHashMap<>();
    /** 허수아비 장부 — 엔티티 → [누적, 합수, 최근] (Dojang 의 명패와 같은 눈금) */
    private final Map<UUID, double[]> tally = new HashMap<>();

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
                            Math.max(1, num(s.get("half"), 4)), lines(s.get("panel"))));
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
        this.lilyHash = Math.max(2, num(ma.get("lily_hash"), 21));

        // ── ★삼도천 화폭 (canvas) — 명계의 근경·중경·원경. 수치는 등록부가 정한다
        Map<String, Object> cv = RulesConfig.section(a, "canvas");
        this.fixedTime = num(cv.get("fixed_time"), 22900);
        Map<String, Object> pg = RulesConfig.section(cv, "pale_grove");
        this.groveZFrom = num(pg.get("z_from"), 6);
        this.groveSpacing = Math.max(2, num(pg.get("spacing"), 4));
        this.groveThreshold = dbl(pg.get("threshold"), 0.55);
        this.groveShift = pair(pg.get("grain_shift"), 120, 120);
        this.groveHeight = pair(pg.get("height"), 3, 6);
        this.mudBand = dpair(cv.get("mud_band"), 0.475, 0.525);
        Map<String, Object> rl = RulesConfig.section(cv, "relics");
        this.relicZ = num(rl.get("z"), -9);
        this.relicX = pair(rl.get("x"), -4, 18);
        this.relicPostHash = Math.max(2, num(rl.get("post_hash"), 3));
        if (rl.get("stone_lanterns") instanceof List<?> sll) {
            for (Object o : sll) {
                if (o instanceof Number n) {
                    relicLanterns.add(n.intValue());
                }
            }
        }
        this.lycorisHash = Math.max(2, num(cv.get("lycoris_hash"), 17));
        Map<String, Object> hor = RulesConfig.section(cv, "horizon");
        this.ridgeX = pair(hor.get("ridge_x"), 88, 100);
        this.ridgeZ = pair(hor.get("ridge_z"), -36, 36);
        this.horizonLight = pair(hor.get("light"), 92, 0);

        // ★B-179 — 기억의 회랑 (삼도천 항해 · seojang_presentation.md §0). 배·물길·정거장의 주인
        this.voyage = new Voyage(plugin, this, RulesConfig.section(a, "voyage"));
        // ★B-179 2차 — 몸으로 겪는 서장: 정거장의 기억 무대 (등록부 seojang_stage.yml)
        this.stage = new SeojangStage(plugin, configDir);

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
        // ★명계 개정 — 저승 구간(넋등)의 리듬. 경계 관문은 stations 등록부에 실존해야 한다 (감사가 잡는다)
        Map<String, Object> so = RulesConfig.section(li, "soul");
        this.soulUntilStation = str(so.get("until_station"), "");
        this.soulEvery = Math.max(2, num(so.get("post_every"), 5));

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

        // ★3차 개정 추기 — 사공의 몸 (이름·성정 정본은 npcs/cheongha_npcs.yml seopgu).
        //   pos 가 등록돼 있지 않으면 몸도 없다 — 사공이 글줄이던 시절로 되돌리는 스위치다.
        Map<String, Object> fm = RulesConfig.section(a, "ferryman");
        this.ferrymanPos = fm.get("pos") == null ? new int[0] : pair(fm.get("pos"), 24, 1);
        this.ferrymanName = str(fm.get("name"), "뱃사공 섭구");
        this.ferrymanLines = lines(fm.get("lines"));

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

        // 【묘비】 lessons 파싱 — ★5차 개정 (과제 폐지). 되살아난 lessons 절은 감사가 위반으로 잰다.

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
            // ★4차 개정 (2026-07-24 사용자 확정 — "평지 필드를 없애고 다 물로"): 맨 위 층이 **물**이다.
            //   잔디 지평선은 "나머지가 전부 물"이라는 나루의 약속을 깼다 — 이제 수평선까지 물이고,
            //   갈 길은 잔교 하나다. (먼 안개 실루엣 산은 다음 회차의 조형 — 시안 뒤 plan 에 얹는다)
            w = new WorldCreator(worldName)
                    .type(WorldType.FLAT)
                    .generateStructures(false)
                    .generatorSettings("{\"layers\":[{\"block\":\"minecraft:stone\",\"height\":1},"
                            + "{\"block\":\"minecraft:dirt\",\"height\":2},"
                            + "{\"block\":\"minecraft:water\",\"height\":5}],"
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
        // ★삼도천 — 새벽녘 고정 (사용자 확정 · canvas.fixed_time). 동쪽 하늘만 주홍으로 물들어
        //   「서=저승 청백 → 동=이승 주홍」의 축과 하늘이 같은 말을 한다 (황혼은 서쪽이 주홍 — 반대말).
        //   대낮이면 빛이 아무것도 안 가리킨다 — 어스름이어야 등롱이 길을 그린다.
        w.setTime(fixedTime);
        w.setStorm(false);
        return w;
    }

    /** 평면 월드의 지면 — <b>지어내지 않고 월드에게 묻는다</b> (y5 가 아니라 y-61 이다. 한 번 데였다).
     *  조성이 손대지 않는 먼 자리에서 재므로, 습지를 파도 이 값은 안 흔들린다. */
    private int groundY(World w) {
        return w.getHighestBlockYAt(cx + 512, cz + 512);
    }

    // ─── ★B-179 — 항해(Voyage)가 묻는 것들 (같은 등록부 · 같은 자) ───

    Voyage voyage() {
        return voyage;
    }

    SeojangStage stage() {
        return stage;
    }

    int cx() {
        return cx;
    }

    int cz() {
        return cz;
    }

    /** 수면 — 배가 뜨는 높이의 기준 (지면 질문과 같은 자) */
    int waterTop(World w) {
        return groundY(w);
    }

    /** 격자 잡음 (헌법 §2.5 — 점묘 금지): 8칸 격자점 해시값의 쌍선형 보간. 난수 없음 — 결정론 */
    private static double grain(int x, int z) {
        int g = 8;
        int gx = Math.floorDiv(x, g);
        int gz = Math.floorDiv(z, g);
        double fx = (x - gx * g) / (double) g;
        double fz = (z - gz * g) / (double) g;
        double a = knot(gx, gz) + (knot(gx + 1, gz) - knot(gx, gz)) * fx;
        double b = knot(gx, gz + 1) + (knot(gx + 1, gz + 1) - knot(gx, gz + 1)) * fx;
        return a + (b - a) * fz;
    }

    private static double knot(int gx, int gz) {
        return Math.floorMod(gx * 73856093 + gz * 19349663, 1024) / 1024.0;
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
            stage(w, "사공", this::ensureFerryman);
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
            stage(w, "사공 세우기", this::ensureFerryman);
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
     * ★명계 개정 — 저승 구간의 동쪽 끝. <b>지어내지 않는다</b>: 등록부(soul.until_station)가
     * 가리키는 관문의 서쪽 끝이다. 등록부가 모르는 이름이면 저승 구간이 없다 — 감사가 잡는다.
     */
    private int soulBoundary() {
        for (Station s : stations) {
            if (s.id().equals(soulUntilStation)) {
                return s.x() - s.half();
            }
        }
        return road.from();
    }

    /** 사공의 집과 그 처마 곁 — 고사목이 여기 서면 집을 찌른다 (한 칸 여유까지 비켜 선다) */
    private boolean nearHut(int x, int z) {
        return x >= hut.x1() - hut.eave() - 1 && x <= hut.x2() + hut.eave() + 1
                && z >= hut.z1() - hut.eave() - 1 && z <= hut.z2() + hut.eave() + 1;
    }

    /**
     * 이 x 에 등롱이 서는가, 선다면 어느 쪽인가 — <b>격자가 아니라 리듬</b>.
     * 좌·우로 번갈아 세운다 (한 줄로 세우면 그것도 격자다). 난수 없음 — 좌표 산술이다.
     *
     * <p>★명계 개정 — 리듬이 구간마다 다르다: 저승(서·넋등)은 촘촘, 이승(부두·등롱)은 성글다.
     */
    private Integer lampSide(int x) {
        if (x < road.from() || x > road.to()) {
            return null;
        }
        int n = x - road.from();
        int every = x < soulBoundary() ? soulEvery : light.postEvery();
        if (Math.floorMod(n, every) != 0) {
            return null;
        }
        if (!light.postAlternate()) {
            return light.postZ();
        }
        return Math.floorMod(n / every, 2) == 0 ? light.postZ() : -light.postZ();
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
                // 갈대 — ★4차 개정 (실사용 "늪 디자인이 너무 반복적"): 점묘 → 덩어리.
                //   한 칸 해시는 파장이 한 칸이라 균일 점묘가 된다 (헌법 §2.5 — "격자점에서 뽑고
                //   보간하라"). 격자 잡음(grain)이 군락을 가른다: 짙은 곳은 갈대가 우거지고
                //   (속은 키 2), 성긴 곳은 맨물에 수련잎만 드문드문 뜬다. 난수 없음 — 결정론.
                double n = grain(x, z);
                if (n > 0.66) {
                    out.add(new Place(cx + x, gy, cz + z, Material.SAND, null));
                    out.add(new Place(cx + x, gy + 1, cz + z, Material.SUGAR_CANE, null));
                    if (n > 0.80) {
                        out.add(new Place(cx + x, gy + 2, cz + z, Material.SUGAR_CANE, null));
                    }
                } else if (n >= mudBand[0] && n <= mudBand[1]) {
                    // ★삼도천 중경 ② — 잿빛 진흙 둔덕: grain 중간 띠가 수면 위로 반 뼘 솟는다
                    //   (띠라서 굽이치는 뻘둑이 된다 — 점묘가 아니다). 갈대 띠(>0.66)와 불가침.
                    out.add(new Place(cx + x, gy, cz + z, Material.MUD, null));
                    out.add(new Place(cx + x, gy + 1, cz + z, Material.MUD, null));
                    if (Math.floorMod(x * 3 + z * 29, lycorisHash) == 0) {
                        // ★삼도천 중경 ④ — 피안화 점정: 붉은 꽃 몇 송이, 둔덕 위에만
                        //   (팩 석산화 텍스처는 후속 — 지금은 바닐라 양귀비)
                        out.add(new Place(cx + x, gy + 2, cz + z, Material.POPPY, null));
                    }
                } else if (n < 0.30 && Math.floorMod(x * 7 + z * 13, lilyHash) == 0) {
                    // ★삼도천 — 먹빛 강에 수련잎은 최소다 (lily_hash 가 성김을 정한다)
                    out.add(new Place(cx + x, gy + 1, cz + z, Material.LILY_PAD, null));
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
            // ★명계 개정 — 서쪽(저승)은 넋등, 부두(이승)만 따뜻한 등롱. 의미의 축이 빛의 색으로 선다
            out.add(new Place(cx + x, deck + 2, cz + z,
                    x < soulBoundary() ? Material.SOUL_LANTERN : Material.LANTERN, null));
        }

        // ⑤ 화톳불 — 관문 마당에만. **관문이 곧 불빛이다** (멀리서 다음 관문이 보인다)
        for (Station s : stations) {
            if (!light.brazierStations().contains(s.id())) {
                continue;
            }
            int[] at = brazierAt(s);
            out.add(new Place(cx + at[0], deck + 1, cz + at[1], Material.CAMPFIRE, null));
        }

        // ⑤-2 ★삼도천 중경 ① — 창백한 고사목 군락 (남쪽 물가에만 · 비대칭 — 북쪽은 옛 잔교의 자리).
        //   후보는 spacing 격자, 군락은 grain 이 가른다 (§2.5 — 점묘 금지), 자리는 좌표 해시가
        //   한 칸 흔든다 (격자로 안 보이게). 잔교·사공의 집 곁은 비켜 선다. 난수 0.
        for (int x = marsh.x1(); x <= marsh.x2(); x++) {
            for (int z = groveZFrom; z <= marsh.z2(); z++) {
                if (Math.floorMod(x, groveSpacing) != 0 || Math.floorMod(z, groveSpacing) != 0) {
                    continue;
                }
                // 고사목 전용 grain 위상 — 갈대 장 그대로면 군락이 동남(이승 곁)에 몰린다.
                // 이 위상이 군락을 서남(저승 쪽)에 앉힌다 (등록부 grain_shift · 결정론)
                if (grain(x + groveShift[0], z + groveShift[1]) < groveThreshold) {
                    continue;
                }
                int tx = x + Math.floorMod(x * 31 + z * 17, 3) - 1;
                int tz = z + Math.floorMod(x * 13 + z * 41, 3) - 1;
                if (tz < groveZFrom || tz > marsh.z2() || tx < marsh.x1() || tx > marsh.x2()
                        || isDeck(tx, tz) || nearHut(tx, tz)) {
                    continue;
                }
                int h = groveHeight[0]
                        + Math.floorMod(tx * 7 + tz * 19, groveHeight[1] - groveHeight[0] + 1);
                out.add(new Place(cx + tx, gy, cz + tz, Material.MUD, null));   // 뿌리 발치
                for (int y = 1; y <= h; y++) {
                    out.add(new Place(cx + tx, gy + y, cz + tz, Material.PALE_OAK_LOG, null));
                }
                if (h >= groveHeight[0] + 2) {
                    // 죽은 가지 하나 — 키 큰 몸만, 방향은 좌표 해시
                    int dir = Math.floorMod(tx + tz, 4);
                    int ax = dir == 0 ? 1 : dir == 1 ? -1 : 0;
                    int az = dir == 2 ? 1 : dir == 3 ? -1 : 0;
                    out.add(new Place(cx + tx + ax, gy + h - 1, cz + tz + az,
                            Material.PALE_OAK_WOOD, null));
                }
            }
        }

        // ⑤-3 ★삼도천 중경 ③ — 반쯤 잠긴 옛 잔교 (북쪽 · 건너간 혼들의 흔적).
        //   말뚝은 post_hash 자리만 살아남았고, 절반은 수면과 같은 키다 (반쯤 잠겼다).
        for (int x = relicX[0]; x <= relicX[1]; x++) {
            if (Math.floorMod(x, relicPostHash) != 0) {
                continue;
            }
            out.add(new Place(cx + x, gy, cz + relicZ, Material.DARK_OAK_FENCE, null));
            if (Math.floorMod(x * 11, 2) == 0) {
                out.add(new Place(cx + x, gy + 1, cz + relicZ, Material.DARK_OAK_FENCE, null));
            }
        }
        for (int lx : relicLanterns) {
            // 석등 — 아직 불이 꺼지지 않은 넋등이 얹혀 있다 (기단은 반쯤 잠겼다)
            out.add(new Place(cx + lx, gy, cz + relicZ, Material.COBBLED_DEEPSLATE_WALL, null));
            out.add(new Place(cx + lx, gy + 1, cz + relicZ, Material.COBBLED_DEEPSLATE_WALL, null));
            out.add(new Place(cx + lx, gy + 2, cz + relicZ, Material.SOUL_LANTERN, null));
        }

        // ⑤-4 ★삼도천 원경 — **동쪽에만**: 이승의 실루엣 둔덕과 따뜻한 불빛 한 점.
        //   장벽 밖 — 눈으로만 가는 땅이다 (안개 실루엣 미결의 흡수). grain 이 높낮이를 가른다.
        for (int x = ridgeX[0]; x <= ridgeX[1]; x++) {
            for (int z = ridgeZ[0]; z <= ridgeZ[1]; z++) {
                int rh = (int) Math.round(grain(x, z) * 4) - 1;   // −1..3 — 성긴 둔덕만 물 위로 솟는다
                for (int y = 0; y <= rh; y++) {
                    out.add(new Place(cx + x, gy + y, cz + z, Material.BLACKSTONE, null));
                }
            }
        }
        int lh = Math.max(0, (int) Math.round(grain(horizonLight[0], horizonLight[1]) * 4) - 1);
        out.add(new Place(cx + horizonLight[0], gy + lh + 1, cz + horizonLight[1],
                Material.DARK_OAK_FENCE, null));
        out.add(new Place(cx + horizonLight[0], gy + lh + 2, cz + horizonLight[1],
                Material.LANTERN, null));

        // ⑤-6 ★기억의 회랑 (B-179) — 정거장의 넋등 문(門): 물길 양옆에 한 쌍씩.
        //   배가 이 사이에서 멈추고, 그 자리에서 책이 열린다. 계열별 기억 조형(디스플레이
        //   엔티티 — 본인에게만 보인다)은 후속 조형 회차의 몫이다 (시안 §0.3).
        for (int sx : voyage.stationsX()) {
            for (int side : new int[]{-1, 1}) {
                int gz2 = side * voyage.frameZ();
                for (int y = 0; y <= 2; y++) {
                    out.add(new Place(cx + sx, gy + y, cz + gz2,
                            Material.COBBLED_DEEPSLATE_WALL, null));
                }
                out.add(new Place(cx + sx, gy + 3, cz + gz2, Material.SOUL_LANTERN, null));
            }
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
        // ★★ 같은 칸을 두 번 적으면 앞 기록이 거짓이 된다 (실측 2026-07-24: 갈대가 물을,
        //   고사목이 공기를 겹쳐 써 완결성 검증이 **제 판에 속아** 94% — 매 진입마다 "반쯤
        //   섰다"며 다시 지을 뻔했다). 마지막 기록만, **마지막 자리에** 남긴다 — 얹히는 것
        //   (갈대·꽃·지붕 밑 등롱)이 늘 제 받침 뒤에 놓인다. 순수 함수 그대로 — 결정론 유지.
        LinkedHashMap<Long, Place> dedup = new LinkedHashMap<>();
        for (Place p : out) {
            long key = (((long) p.x() & 0x3FFFFFFL) << 38)
                    | (((long) p.z() & 0x3FFFFFFL) << 12) | ((long) p.y() & 0xFFFL);
            dedup.remove(key);   // 자리를 끝으로 옮긴다 (put 만 하면 첫 자리에 남아 받침을 앞지른다)
            dedup.put(key, p);
        }
        return new ArrayList<>(dedup.values());
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
        return stations.size();   // ★5차 — 판은 관문마다 하나, 전부 안내판이다 (예고 변형 소멸)
    }

    /** 관문의 글 — <b>등록부의 문장 그대로</b> (Station.panel · 맞이는 arrival). 판이 딴말을 할 수가 없다 */
    private List<String> panelText(Station s) {
        if (!s.panel().isEmpty()) {
            return s.panel();
        }
        return s.id().equals(arrivalPanelId) ? arrivalLines
                : List.of(panelSpec.titlePrefix() + s.id());
    }

    private void spawnPanels(World w) {
        int gy = groundY(w);
        for (Station s : stations) {
            boolean isGate = s.x() >= bell[0] - s.half();   // 문의 관문 — 주사 바탕
            spawnPanel(w, gy, s.id(), s, panelText(s), isGate);
        }
        // 【묘비】 순차 공개(refreshPanels 재적용·B-131 가림 경주) — ★5차: 과제가 없으니 가릴 판도
        //   없다. 안내판은 늘 보인다 — 「한 길」의 흐름은 이제 판이 아니라 물이 만든다 (길이 하나다).
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

    // 【묘비】 passed/currentStation/refreshPanels/show — 순차 공개와 예고 변형은 과제의 기계였다.
    //   ★5차 개정: 판은 두 장(맞이·나루)뿐이고 전부 안내판이라 늘 보인다. 그 시절의 계율은 남는다:
    //   「글판이 안 보이는 것과 문이 잠기는 것은 다른 것이다 — 종은 언제나 울린다」 (감사가 cross 를 계속 잰다)

    /**
     * 나루의 엔티티가 실리는 순간 글판 명부를 다시 채운다 (재기동 뒤의 빈 명부가 여기서 되살아난다).
     * ★5차 — 옛 가림 재적용(B-131 겹침 경주)은 순차 공개와 함께 걷혔다: 판은 늘 보인다.
     */
    @EventHandler
    public void onPanelsLoad(EntitiesLoadEvent event) {
        if (!isAntechamber(event.getWorld())) {
            return;
        }
        for (Entity e : event.getEntities()) {
            if (e instanceof TextDisplay && e.getPersistentDataContainer().has(KEY_PANEL)) {
                String id = e.getPersistentDataContainer().get(KEY_PANEL, PersistentDataType.STRING);
                if (id != null) {
                    panelEntities.putIfAbsent(id, e.getUniqueId());
                }
            }
        }
    }

    // 【묘비】 capable/lacks (능·requires·예고) — 「못 하는 것을 시키지 않는다」의 기계. ★5차 개정으로
    //   시키는 것 자체가 없어져 함께 걷혔다. 상속자는 본토 뿌리내림의 「막기 예고」다 (tutorial_rooting.md).

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

    /**
     * 사공의 몸 (3차 개정 추기 — 실사용 2026-07-24 "나루에 섭구가 없음").
     * 1~2차판의 사공은 글줄뿐이었다 — 집 지을 때와 같은 병("있다고 말만 하고").
     * <b>정확히 한 몸</b>이다 (ensureDummies 와 같은 계약 — 많은 것도 틀린 것이다).
     * 우클릭 대사는 TradeListener 가 잇는다 (KEY_NPC=seopgu · 나루 세계 분기 → ferrymanLines).
     */
    private void ensureFerryman(World w) {
        if (ferrymanPos.length < 2) {
            return;   // 등록부에 자리가 없으면 몸도 없다
        }
        List<Entity> standing = new ArrayList<>();
        for (Entity e : w.getEntities()) {
            if (e.getPersistentDataContainer().has(KEY_FERRYMAN, PersistentDataType.BYTE)) {
                standing.add(e);
            }
        }
        if (standing.size() == 1) {
            return;
        }
        standing.forEach(Entity::remove);
        int y = groundY(w) + road.deckY() + 1;
        Location at = new Location(w, cx + ferrymanPos[0] + 0.5, y, cz + ferrymanPos[1] + 0.5, -90f, 0f);
        w.spawn(at, Villager.class, v -> {
            v.setCustomName(ferrymanName);
            v.setCustomNameVisible(true);
            v.setAI(false);              // 배회 금지 — 사공은 부두를 지킨다
            v.setSilent(true);
            v.setPersistent(true);
            v.setRemoveWhenFarAway(false);
            // 나루는 성역이다 — B-119(살아 있는 것은 다 맞는다)는 본세계의 계약이고,
            // 문지방의 안내인은 죽지 않는다 (이행 스윕도 나루 세계는 건너뛴다 — TradeListener)
            v.setInvulnerable(true);
            v.getPersistentDataContainer().set(KEY_FERRYMAN, PersistentDataType.BYTE, (byte) 1);
            v.getPersistentDataContainer().set(CheonghaBuilder.KEY_NPC,
                    PersistentDataType.STRING, "seopgu");
        });
    }

    /** 나루 쪽 사공의 대사 — TradeListener 가 입도진 세계의 섭구 우클릭에 이 줄을 읽는다 */
    List<String> ferrymanLines() {
        return ferrymanLines;
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
            // ★ 명패는 기본 숨김 (2026-07-23 사용자: "텍스트가 너무 나와서 — 보여줄 것만") —
            //   때린 사람에게만 잠깐 열린다 (아래 타격 손). 장부 자체는 그대로 산다
            e.setCustomNameVisible(false);
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
        // ★잔교가 서기 전에는 사람을 내려놓지 않는다 (실측 2026-07-24: 월드 재생성 뒤 첫 입장이
        //   조성보다 먼저 텔레포트돼 잔교 없는 허공을 지나 심층 동굴(y-59)에 떨어졌다 —
        //   문지방이 함정이 됐다). build 의 onDone 이 유일한 문이다. 조성 중이면 잠깐 기다린다.
        if (building) {
            player.sendMessage(ChatColor.GRAY + "나루가 아직 서는 중이다 — 잠시 뒤에 내려선다.");
            Bukkit.getScheduler().runTaskLater(plugin, () -> enter(player), 40L);
            return;
        }
        build(w, false, () -> arrive(player, w, first));
    }

    /** 잔교가 선 뒤의 도착 — enter 의 몸 (build.onDone 만이 부른다) */
    private void arrive(Player player, World w, boolean first) {
        if (!player.isOnline()) {
            return;   // 조성을 기다리다 나갔다 — 다음 접속의 onJoin 이 다시 데려온다
        }
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
            revisiting.add(player.getUniqueId());   // 이미 건넌 몸 — 자동 출항 없음 (문은 종이다)
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
        // ★ 나루의 몸은 배부르다 — 이미 바닥(6)까지 닳아 들어온 몸도 달릴 수 있어야 한다.
        //   {@link #onHunger} 는 줄지 않게만 하므로, 채우는 것은 문이 한다 (들어올 때 한 번).
        player.setFoodLevel(20);
        player.setSaturation(20f);
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
                // ★B-179 — 종이 곧 승선이다: 서장이 남은 몸에게 종은 배를 띄운다 (강 위의 서장)
                if (!voyage.riding(player.getUniqueId())) {
                    voyage.embark(player);
                    return;
                }
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

    void depart(Player player, List<String> extra) {
        UUID id = player.getUniqueId();
        voyage.disembark(id);   // ★B-179 — 배는 기슭에 남지 않는다 (항해는 메모리뿐이다)
        restore(player);
        player.setGameMode(GameMode.SURVIVAL);
        player.teleport(destination(player));
        player.setFallDistance(0f);
        boarding.remove(id);
        revisiting.remove(id);
        dockWaitSaid.remove(id);
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
                if (isAntechamber(player.getWorld())) {
                    // ★B-179 — 서장이 남은 몸은 재방문이 아니라 **항해가 끊긴 몸**이다.
                    //   표식을 안 달면 watchGate 가 다시 태운다 (배는 봇의 명단이 다시 띄운다 —
                    //   제 장면의 정거장 앞에서 깨어난다. relocateIfBehind).
                    if (WorldBridge.seojangHolds(player.getUniqueId())) {
                        return;
                    }
                    // ★재방문 (2026-07-24) — 나루 안에서 재접속한 **이미 건넌 몸**은 끌고 가지
                    //   않는다: 자동 출항은 첫 건넘의 의식이고, 재방문의 문은 종이다 (사공에게
                    //   말도 걸기 전에 실려 가던 실사용 결함의 재접속 판). 표식은 메모리뿐이라
                    //   재접속마다 여기서 다시 단다. 갇힘은 없다 — 종(cross)은 언제나 울린다.
                    //   (첫 건넘 직전에 나갔다 온 드문 몸도 이 길로 온다 — 그 몸도 종이 건넌다)
                    revisiting.add(player.getUniqueId());
                    if (!revisitLine.isEmpty()) {
                        player.sendMessage(revisitLine);
                    }
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
        // 【묘비】 creditCommand/onCommand(명령 감지) — 과제 폐지(★5차)로 기입할 장부가 없다.
        //   발판의 계약은 그대로다: 대신 쳐 주되, 무엇이 쳐졌는지 그대로 보여준다 (echo = cmd 한 변수).
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
            // ★ 명패(장부 줄)는 상시 숨김 (2026-07-23 사용자 확정: 보이는 것은 공통 명패 —
            //   이름·체력바·대미지 숫자뿐. HitFeedback 이 label 로 그린다). 장부는 이름에 계속
            //   쌓인다 — 눈이 필요해지면 그때 여는 손을 단다
            dummy.setCustomName(hitName(label, durability, t));
        });
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
        boarding.remove(id);
        revisiting.remove(id);
        dockWaitSaid.remove(id);
        voyage.disembark(id);   // ★B-179 — 나간 몸의 배는 걷는다 (재접속하면 명단이 다시 띄운다)
        plateCooldowns.keySet().removeIf(k -> k.startsWith(id.toString()));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  티커
    // ══════════════════════════════════════════════════════════════════════

    void start() {
        if (ticker != null) {
            return;
        }
        // ★B-179 2차 — 등불 우클릭(선택의 몸)이 이 손으로 들어온다
        Bukkit.getPluginManager().registerEvents(stage, plugin);
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
            watchGate(player);   // ★5차 — 과제의 눈 셋(몸짓·격·경공)은 과제와 함께 걷혔다. 문만 본다
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

    // 【묘비】 watchGestures/watchArmed/watchGyeonggong — 과제의 눈 셋 (몸짓·격·경공 감지).
    //   ★5차 개정으로 과제와 함께 걷혔다. 여기 살던 계율들은 뿌리내림(B-178)이 상속했다:
    //   · 몸짓 술어는 combat.yml defender_stance_mc.gestures 가 정본 (지어내지 않는다)
    //   · 과제는 흉내가 아니라 **발동**을 본다 (경공은 riding — 달리다 뛴 몸이 아니다)
    //   · 못 하는 몸은 아예 안 본다 (requires — 「뛰어라」 함정의 상처)

    /** 강호에 이름이 올랐다 — <b>배가 뜬다</b> */
    private void watchGate(Player player) {
        UUID id = player.getUniqueId();
        if (!plugin.ledger(id).linked() || boarding.contains(id)) {
            return;
        }
        if (revisiting.contains(id)) {
            return;   // 재방문자 — 의식(자동 출항)은 첫 건넘의 것이다. 종은 언제나 울린다
        }
        boarding.add(id);
        player.sendTitle(ChatColor.GOLD + openedTitle, ChatColor.WHITE + openedSubtitle, 10, 70, 20);
        String who = WorldBridge.linkedName(id);
        openedLines.forEach(line ->
                player.sendMessage(line.replace("{name}", who == null ? player.getName() : who)));
        // ★B-179 — 서장이 남은 몸은 부두에서 기다리지 않는다: **강을 건너는 동안이 곧 서장이다.**
        //   배가 안개 물길의 정거장으로 데려가고, 장마다 그 자리에서 책이 열린다 (Voyage).
        if (WorldBridge.seojangHolds(id)) {
            if (!seojangWaitLine.isEmpty()) {
                player.sendMessage(seojangWaitLine);
            }
            voyage.embark(player);
            return;
        }
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
            Bukkit.getScheduler().runTaskTimer(plugin, task -> {
                if (!player.isOnline() || !isAntechamber(player.getWorld())
                        || !plugin.ledger(id).linked()) {
                    task.cancel();
                    return;
                }
                if (WorldBridge.seojangHolds(id)) {
                    // ★B-179 — 명단이 한 박자 늦게 실렸다 (접합 직후의 경주) — 그래도 배는 뜬다.
                    //   부두 대기가 아니라 승선이다: 서장은 강 위에서 흐른다
                    if (!voyage.riding(id)) {
                        voyage.embark(player);
                    }
                    task.cancel();
                    return;
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

    // 【묘비】 과제 장부 (complete/bump/flashCount/applicable) — ★5차 개정 (과제 폐지)으로 걷혔다.
    //   계율은 남는다: 과제 카운트는 맨 sendActionBar 가 아니라 flash 단일 창구를 탔다 (B-123 —
    //   액션바 한 줄의 주인 규약은 SkillHud/HudLine 에 살아 있다) · 「못 하는 것을 못 했다고 세지
    //   않는다」(applicable — all_done 이 영영 안 뜨던 병)는 뿌리내림 과정이 상속했다.

    // ══════════════════════════════════════════════════════════════════════
    //  종료 — 세계에 아무것도 남기지 않는다
    // ══════════════════════════════════════════════════════════════════════

    public void shutdown() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        voyage.shutdownAll();   // ★B-179 — 항해선을 걷는다 (배는 저장되지 않는다 — 명단이 다시 띄운다)
        World w = Bukkit.getWorld(worldName);
        if (w != null) {
            voyage.sweepBoats(w);   // 주인 잃은 배까지 (표식 있는 것만)
            stage.sweep(w);         // 무대·등불도 (표식 있는 것만)
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

    /** 관문 하나 — panel 은 이 관문의 <b>안내판 문장</b>이다 (비면 arrival 또는 이름만 · ★5차: 과제 아님) */
    private record Station(String id, int x, int half, List<String> panel) { }

    private record Marsh(int x1, int x2, int z1, int z2, int depth, int reedHash) { }

    private record Hut(int x1, int x2, int z1, int z2, int wallH, int eave, String line) { }

    private record Plate(String id, int x, int z, String command) { }

    /** 허수아비 한 몸 — 이름도 내구도 <b>등록부가 짓는다</b> (코드가 지어내지 않는다) */
    private record Dummy(String id, String label, int x, int z, int durability) { }

    private record Lighting(int postEvery, boolean postAlternate, int postZ,
                            Set<String> brazierStations, boolean hutLantern,
                            double darkMinPct, double darkMaxPct, double mainDarkMaxPct,
                            double lampDensityMaxPct, int mainLightSpanMin) { }

    // 【묘비】 record Lesson — 과제의 몸. ★5차 개정 (2026-07-24)으로 과제 자체가 폐지됐다 (config §7 묘비 참조).

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

    private static double[] dpair(Object raw, double a, double b) {
        if (raw instanceof List<?> list && list.size() >= 2) {
            return new double[]{
                    list.get(0) instanceof Number n1 ? n1.doubleValue() : a,
                    list.get(1) instanceof Number n2 ? n2.doubleValue() : b};
        }
        return new double[]{a, b};
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
