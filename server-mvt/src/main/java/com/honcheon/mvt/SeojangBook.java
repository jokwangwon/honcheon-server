package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 서장의 서책(序章書冊) — <b>이야기가 손에 잡히는 물건이 된다.</b>
 *
 * <p><b>★ 왜 책인가.</b> 서장은 디스코드의 임베드 + 버튼 셋이었다. 그것을 게임 안으로 옮기면서
 * 채팅창에 임베드를 흉내 내지 않았다 — <b>책은 이 세계에 원래 있던 물건이다</b> (비급·서찰·유서·족보).
 * 바닐라가 이미 지원하고, 무협의 결에 맞는다. 펼치면 이야기가 뜨고, <b>글자를 누르면 고른다.</b>
 *
 * <p><b>★★ 이 파일에는 이야기가 한 줄도 없다.</b> 글도 · 선택지도 · 판정도 전부 <b>봇</b>이 낸다
 * ({@link WorldBridge.SeojangScene} 으로 다리를 건너온다). LLM 도 판정 엔진도 시트도 봇에만 있고
 * <b>마크는 DB 를 열지 않는다</b> — 마크가 문장을 지으면 <b>정본이 둘</b>이 된다.
 * 여기 있는 것은 <b>책의 겉모습</b>뿐이고, 그것마저 {@code config/seojang.yml book} 이 정한다.
 *
 * <p><b>손이 하는 일</b>: 클릭 = {@code ClickEvent.runCommand("/혼천 서장 <토큰> <n>")}
 * (접합의 [잇는다] 와 같은 문법 — {@link LinkGate}). <b>토큰은 열쇠가 아니라 지목</b>이다:
 * 낡은 책을 눌러도, 남의 토큰을 주워 눌러도 <b>봇이 거른다</b> (지금 그 장면의 것이 아니면 버린다).
 *
 * <p><b>★ 연출(SJ-002)</b> — 실사용 근거 (2026-07-14): <i>"책이 갑자기 내려가서 무슨 상황인지
 * 인지하기 힘들었음. 그리고 오래 걸림."</i> LLM 이 다음 장을 쓰는 수십 초가 <b>아무 표정 없이</b>
 * 흘렀다. 이제 기다림은 액션바가 반복해서 말하고(집필 중이지 고장이 아니다), 도착은
 * <b>회수 → 장 제목 타이틀 → 사건음 → 펼침</b>으로 흐른다. 초도 문장도 소리도 전부
 * {@code config/seojang.yml presentation} 이 정한다 — 코드는 시계를 읽을 뿐 짓지 않는다.
 */
public final class SeojangBook {

    /** 이 아이템이 서장의 책인가 — 우클릭으로 다시 펼치는 손이 본다 */
    static final String TAG = "서장_서책";

    private static SeojangBook instance;

    private final Map<String, String> text = new LinkedHashMap<>();
    private final HoncheonMvt plugin;

    /** 이 몸에게 이미 쥐여 준 장면 (uuid → 토큰) — 2초마다 같은 책을 다시 주지 않는다 */
    private final Map<java.util.UUID, String> given = new LinkedHashMap<>();
    /** 이미 전한 사공의 말 — 같은 말을 2초마다 반복하면 그것은 도배다 */
    private final Map<java.util.UUID, String> told = new LinkedHashMap<>();
    /** 지금 붓이 들려 있는 몸들 — 액션바 반복이 이 명단을 돈다 (state=쓰는_중) */
    private final java.util.Set<java.util.UUID> writingNow = new java.util.LinkedHashSet<>();

    /** 액션바 notice 채널 이름 (B-116) — 집필 대기 조각이 사는 자리 */
    private static final String NOTICE_CHANNEL = "서장";

    // ─── 연출 등록부 (presentation) — 초·문장·소리는 전부 여기서 온다 ───
    private final String waitBar;            // 기다림 액션바 (비면 침묵 — 그러나 침묵 금지가 규약이다)
    private final int waitInterval;          // 액션바 반복 주기 (틱)
    private final Chime waitSound;           // 기다림 반복음 (선택 — key 가 비면 침묵)
    private final String titleFormat;        // 도착 타이틀 큰 줄 — {header}
    private final String subtitleFormat;     // 도착 타이틀 작은 줄 — {title}
    private final int titleIn;
    private final int titleStay;             // 청사진 §3.1: 0.8~1.2초 (등록부가 고른다)
    private final int titleOut;
    private final int openDelay;             // 타이틀 뒤 책이 펼쳐지기까지 (틱)
    /** 사건음 4채널 (open·choose·result·debut) — 청사진 §4.2. 팩 키 + 바닐라 폴백 */
    private final Map<String, Chime> chimes = new LinkedHashMap<>();

    /** 소리 한 벌 — key 는 팩의 것, fallback 은 팩이 안 켜진 눈을 위한 바닐라 강등 */
    private record Chime(String key, String fallback, float volume, float pitch) {
    }

    private SeojangBook(HoncheonMvt plugin, Path cfg) {
        this.plugin = plugin;
        Map<String, Object> root = RulesConfig.load(cfg.resolve("seojang.yml"));
        RulesConfig.section(root, "book").forEach((k, v) -> text.put(k, String.valueOf(v)));

        // ★ 연출 절이 없어도 책은 온다 (강등 계약) — 그래서 RulesConfig.section(던진다) 대신 sub(빈 표)
        Map<String, Object> pres = sub(root, "presentation");
        Map<String, Object> waiting = sub(pres, "waiting");
        waitBar = str(waiting, "actionbar", "");
        waitInterval = num(waiting, "interval_ticks", 30);
        waitSound = chimeOf(sub(waiting, "sound"));
        Map<String, Object> arrival = sub(pres, "arrival");
        titleFormat = str(arrival, "title_format", "{header}");
        subtitleFormat = str(arrival, "subtitle_format", "{title}");
        titleIn = num(arrival, "title_fade_in_ticks", 5);
        titleStay = num(arrival, "title_stay_ticks", 20);
        titleOut = num(arrival, "title_fade_out_ticks", 10);
        openDelay = num(arrival, "open_delay_ticks", 0);   // 등록이 없으면 즉시 — 옛 몸짓 그대로
        Map<String, Object> sounds = sub(pres, "sounds");
        for (String ch : sounds.keySet()) {
            chimes.put(ch, chimeOf(sub(sounds, ch)));
        }
    }

    // ─── 등록부 판독 — 없는 절은 빈 표다 (연출이 빠져도 이야기는 흐른다) ───

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> root, String key) {
        Object v = root.get(key);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static int num(Map<String, Object> m, String key, int def) {
        return m.get(key) instanceof Number n ? n.intValue() : def;
    }

    private static float dec(Map<String, Object> m, String key, double def) {
        return m.get(key) instanceof Number n ? n.floatValue() : (float) def;
    }

    private static Chime chimeOf(Map<String, Object> m) {
        return new Chime(str(m, "key", ""), str(m, "fallback", ""),
                dec(m, "volume", 1.0), dec(m, "pitch", 1.0));
    }

    public static void init(HoncheonMvt plugin, Path configDir) {
        instance = new SeojangBook(plugin, configDir);
        instance.start();
    }

    /** 기다림의 시계 — 붓이 들려 있는 몸에게만 돈다 (명단이 비면 한 사람도 건드리지 않는다) */
    private void start() {
        if (waitInterval <= 0) {
            return;   // 등록부가 0 을 적었다 = 반복 연출을 껐다 (기존 채팅 한 줄만 남는다)
        }
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickWaiting,
                waitInterval, waitInterval);
    }

    private void tickWaiting() {
        if (writingNow.isEmpty()) {
            return;
        }
        var it = writingNow.iterator();
        while (it.hasNext()) {
            Player p = plugin.getServer().getPlayer(it.next());
            if (p == null || !p.isOnline()) {
                it.remove();   // 나간 몸 — 명단이 유령을 들고 있지 않는다 (조각은 quit 의 forget 이 지운다)
                continue;
            }
            if (!waitBar.isBlank()) {
                // B-116: 집필 대기는 지속 표시 — notice 채널 조각으로, 생명·격 두름과 나란히 읽힌다.
                // 수명 = 재송신 주기(waitInterval) + statusBar 주기(4틱) — 잠정 도출값
                // waitBar 는 § 코드 문자열 그대로 — 액션바의 문(SkillHud.actionBar)도 § 코드를 그린다
                plugin.skills().notice(p, NOTICE_CHANNEL, waitBar, waitInterval + 4);
            }
            play(p, waitSound);
        }
    }

    public static SeojangBook get() {
        return instance;
    }

    private String say(String key, String fallback) {
        String v = text.get(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    /**
     * <b>책이 왔다</b> — 다리가 내려보낸 장면 하나를 그 몸의 손에 쥐여 주고 <b>펼친다</b>.
     *
     * <p>메인 스레드에서 불려야 한다 (Bukkit API 는 그렇게 산다 — {@link HoncheonMvt} 가 넘겨 준다).
     */
    public void deliver(Player player, WorldBridge.SeojangScene scene) {
        // ★B-179 (기억의 회랑) — 항해 중의 책은 **정거장에서** 열린다: 배가 그 자리로 데려간다.
        //   미루는 것은 펼침뿐이다 — 집필 중 조각은 통과시켜 기다림 기계가 그대로 돈다.
        //   항해가 아니면(부두 없는 세계·옛 경로) 옛 몸짓 그대로 즉시 편다.
        Antechamber ante = plugin.antechamber();
        if (ante != null && ante.voyage().defer(player, scene)) {
            return;
        }
        // ★ 붓이 아직 들려 있다 — 책을 주지 않는다. 대신 **사공이 말한다** (침묵 금지)
        if (scene.writing()) {
            // 기다림 명단에 올린다 — 이제부터 액션바가 반복해서 말한다 ("집필 중"이지 고장이 아니다).
            // 실사용 근거 (2026-07-14): 이 공백이 아무 표정 없이 흘러 "무슨 상황인지 인지하기 힘들었음"
            writingNow.add(player.getUniqueId());
            String line = scene.ferry();
            if (line != null && !line.equals(told.get(player.getUniqueId()))) {
                told.put(player.getUniqueId(), line);
                player.sendMessage(legacy(line));
            }
            return;
        }
        // 같은 장면의 책을 두 번 주지 않는다 (다리는 2초마다 같은 것을 내려보낸다)
        if (scene.token() != null && scene.token().equals(given.get(player.getUniqueId()))) {
            writingNow.remove(player.getUniqueId());   // 붓은 내려놨다 — 액션바도 그친다
            plugin.skills().dropNotice(player.getUniqueId(), NOTICE_CHANNEL);
            return;
        }
        given.put(player.getUniqueId(), scene.token());
        told.remove(player.getUniqueId());
        writingNow.remove(player.getUniqueId());
        plugin.skills().dropNotice(player.getUniqueId(), NOTICE_CHANNEL);

        ItemStack book = build(scene);
        // 옛 장의 책은 회수한다 — 품에 두 권이 있으면 어느 것이 지금인지 알 수 없다
        removeOld(player);
        player.getInventory().addItem(book);
        player.sendMessage(legacy(say("given", "§6품 안에 서책 한 권이 들어 있다.")));
        player.sendMessage(legacy(say("reopen", "§7손에 든 서책을 §f우클릭§7 하면 다시 펼쳐진다.")));

        // ─── 도착 연출 (청사진 §3.1): 회수 → 장 제목 타이틀 → 사건음 → 펼침 ───
        // 타이틀은 바닐라 화면이다 — 팩이 없어도 같은 정보가 보인다 (강등 계약).
        String header = headText(scene);
        player.sendTitle(
                titleFormat.replace("{header}", header).replace("{title}", scene.title()),
                subtitleFormat.replace("{header}", header).replace("{title}", scene.title()),
                titleIn, titleStay, titleOut);
        chime(player, "open");
        if (openDelay <= 0) {
            player.openBook(book);   // 연출이 꺼져 있다 — 옛 몸짓 그대로 즉시 펼친다
            return;
        }
        java.util.UUID body = player.getUniqueId();
        String token = scene.token();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player still = plugin.getServer().getPlayer(body);
            // 그새 나갔거나 · 더 새 장이 왔으면(토큰이 다르다) 이 책은 펼치지 않는다 — 새 배달이 제 책을 연다
            if (still != null && still.isOnline()
                    && token != null && token.equals(given.get(body))) {
                still.openBook(book);   // ★ 저절로 펼쳐진다 — 사람은 아무것도 찾지 않아도 된다
            }
        }, openDelay);
    }

    /** 타이틀의 큰 줄에 쓰는 머리말 — 책의 첫 쪽과 같은 등록부(book.header)에서 온다 (정본은 하나).
     *  ★B-179 2차 — 무대(SeojangStage)·필사본(Voyage)도 같은 문장을 쓴다 (그래서 문이 열렸다) */
    String headText(WorldBridge.SeojangScene scene) {
        return scene.last()
                ? say("epilogue_header", "서장의 끝")
                : say("header", "제 {scene} 장")
                .replace("{scene}", String.valueOf(scene.scene() + 1))
                .replace("{total}", String.valueOf(scene.total()));
    }

    /**
     * ★ <b>사건음</b> — 채널 이름(open·choose·result·debut)으로 낸다 (등록부: presentation.sounds).
     *
     * <p><b>강등 계약</b>: 팩이 켜진 눈({@code SUCCESSFULLY_LOADED})에는 팩의 키
     * ({@code honcheon:seojang.*})를, 아니면 바닐라 폴백 키를 낸다 — 어느 쪽이든 <b>같은 사건이 들린다.</b>
     * 팩 안에서도 전용 .ogg 가 없으면 sounds.json 이 같은 바닐라 소리로 재지향한다 (자산 누락 = 침묵 아님).
     *
     * <p>choose(SJ-003)·result(SJ-003)·debut(SJ-004)의 발성 자리는 이 문을 부르면 된다.
     * 등급별 음높이 변주(§4.2)는 부르는 쪽이 등록부의 pitch 위에 얹는다 — 여기는 표를 읽을 뿐이다.
     */
    public void chime(Player player, String channel) {
        Chime c = chimes.get(channel);
        if (c == null) {
            return;   // 등록부에 없는 채널 — 코드가 소리를 지어내지 않는다
        }
        boolean packed = player.getResourcePackStatus()
                == org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED;
        String key = packed && !c.key().isBlank() ? c.key() : c.fallback();
        play(player, new Chime(key, "", c.volume(), c.pitch()));
    }

    private static void play(Player player, Chime c) {
        if (c == null || c.key() == null || c.key().isBlank()) {
            return;   // 비워 둔 소리 = 침묵을 골랐다 (waiting.sound 의 기본)
        }
        player.playSound(player.getLocation(), c.key(), c.volume(), c.pitch());
    }

    /**
     * ★무대 그릇 (실사용 2026-07-25 "2장 출력 다 되었는데도 '붓이 다음 장을 적고 있다'가 존재") —
     * 무대 길에서는 책을 안 주므로 {@link #deliver} 의 걷기 코드가 안 돈다. 붓이 내려온 순간
     * (non-writing 장면 도착) 이 손이 기다림 기계만 걷는다 — 화면이 세계에 대해 거짓말하지 않는다.
     */
    public void settle(Player player) {
        writingNow.remove(player.getUniqueId());
        told.remove(player.getUniqueId());
        plugin.skills().dropNotice(player.getUniqueId(), NOTICE_CHANNEL);
    }

    /** 그 몸의 서장 책을 회수한다 (출도했거나 다음 장이 왔다) */
    public void removeOld(Player player) {
        ItemStack[] items = player.getInventory().getContents();
        for (int i = 0; i < items.length; i++) {
            ItemStack it = items[i];
            if (it != null && it.getType() == Material.WRITTEN_BOOK && isSeojang(it)) {
                player.getInventory().setItem(i, null);
            }
        }
    }

    /** 출도했다 — 책을 거두고 기억을 지운다 (다시 서장에 들면 새 책이 온다) */
    public void close(Player player) {
        if (given.remove(player.getUniqueId()) != null) {
            removeOld(player);
        }
        told.remove(player.getUniqueId());
        writingNow.remove(player.getUniqueId());   // 이야기가 끝났다 — 기다림의 액션바도 그친다
    }

    public void forget(java.util.UUID body) {
        given.remove(body);
        told.remove(body);
        writingNow.remove(body);
    }

    /** ★B-179 2차 — 필사본 표식: 진행용 서책(TAG)과 다르다 — close() 가 거두지 않고, 클릭도 없다 */
    static final String MEMOIR_TAG = "서장_필사본";

    /**
     * <b>서장 필사본</b> — 강을 건너며 겪은 기억의 <b>전문</b>이 품에 남는다 (읽기 전용 · 선택 없음).
     *
     * <p>무대 그릇(★2차: "글이 아닌 몸으로")에서 글은 한 줄 맥박로만 흘렀다 — LLM 이 지은
     * 개인 서사는 여기 보존된다. 진행용 서책과 표식이 달라 다리의 회수(close)가 거두지 않는다.
     */
    public void memoir(Player player, List<String> sceneTexts, String givenLine, String fullLine) {
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        item.editMeta(BookMeta.class, meta -> {
            meta.title(Component.text(say("memoir_title", "서장 필사본")));
            meta.author(Component.text(say("author", "혼천")));
            meta.displayName(Component.text(say("memoir_title", "서장 필사본"),
                    NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            meta.getPersistentDataContainer().set(Goods.KEY_GOODS,
                    PersistentDataType.STRING, MEMOIR_TAG);
            List<Component> pages = new ArrayList<>();
            for (String text : sceneTexts) {
                for (String chunk : BookLayout.paginate(text, 0)) {
                    pages.add(Component.text(chunk, NamedTextColor.BLACK));
                }
            }
            if (pages.isEmpty()) {
                pages.add(Component.text(say("memoir_empty", "…기억은 안개 속에 남았다."),
                        NamedTextColor.DARK_GRAY));
            }
            meta.pages(pages);
        });
        var leftover = player.getInventory().addItem(item);
        if (leftover.isEmpty()) {
            if (givenLine != null && !givenLine.isEmpty()) {
                player.sendMessage(legacy(givenLine));
            }
        } else if (fullLine != null && !fullLine.isEmpty()) {
            player.sendMessage(legacy(fullLine));   // 지급 강행 금지 (SJ-004) — 다만 침묵하지 않는다
        }
    }

    static boolean isSeojang(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return TAG.equals(item.getItemMeta().getPersistentDataContainer()
                .get(Goods.KEY_GOODS, PersistentDataType.STRING));
    }

    /** 지금 이 몸이 든 책의 토큰 (우클릭으로 다시 펼칠 때 쓴다) */
    public String tokenOf(java.util.UUID body) {
        return given.get(body);
    }

    /** 붓이 다음 장을 적고 있다 (등록부의 말 — 코드가 짓지 않는다) */
    public String waiting() {
        return say("waiting", "§8… 붓이 다음 장을 적고 있다.");
    }

    /** 낡은 책을 눌렀다 — 다음 장이 이미 왔다 */
    public String stale() {
        return say("stale", "§8그 장은 이미 넘어갔다 — 새 서책이 품에 있다.");
    }

    // ─── 책을 짓는다 — ★ **글은 봇의 것이다. 여기서는 종이에 앉힐 뿐이다** ───

    private ItemStack build(WorldBridge.SeojangScene scene) {
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        item.editMeta(BookMeta.class, meta -> {
            meta.title(Component.text(say("title", "서장(序章)")));
            meta.author(Component.text(say("author", "혼천")));
            meta.displayName(Component.text(say("title", "서장(序章)") + " — " + scene.character(),
                    NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            meta.getPersistentDataContainer().set(Goods.KEY_GOODS, PersistentDataType.STRING, TAG);
            meta.pages(pages(scene));
        });
        return item;
    }

    /**
     * 낱장 — 머리말 + 서사(나눠 담는다) + <b>선택지 장</b>.
     *
     * <p>마지막 장의 선택지가 <b>클릭 가능한 줄</b>이다. 채팅 명령을 외우게 하지 않는다 —
     * 사용자의 뜻: <i>"선택지는 책 안의 클릭 가능한 줄로."</i>
     */
    private List<Component> pages(WorldBridge.SeojangScene scene) {
        List<Component> out = new ArrayList<>();

        String headText = headText(scene);   // 도착 타이틀과 같은 문장 — 정본은 하나 (book.header)
        Component header = Component.text(headText, NamedTextColor.DARK_GRAY, TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.text(scene.title(), NamedTextColor.BLACK, TextDecoration.BOLD))
                .append(Component.newline()).append(Component.newline());

        // 머리말이 먹는 줄 수 = 장 번호 줄 + 제목 줄(길면 화면이 여러 줄로 꺾는다) + 빈 줄 하나.
        // 둘 다 굵은 글씨라 폭이 1px 넓다 — 조판기가 같은 자로 잰다 (BookLayout.advance)
        int headerLines = BookLayout.lines(headText, true)
                + BookLayout.lines(scene.title(), true) + 1;

        List<String> chunks = BookLayout.paginate(
                scene.narration() == null ? "" : scene.narration(), headerLines);
        for (int i = 0; i < chunks.size(); i++) {
            Component page = i == 0 ? header : Component.empty();
            out.add(page.append(Component.text(chunks.get(i), NamedTextColor.BLACK)));
        }
        if (out.isEmpty()) {
            out.add(header);
        }

        // ─── 마지막 장 — 고르는 자리 ───
        Component pick = Component.empty();
        if (scene.last()) {
            // ★ 에필로그의 단 하나의 길 = 출도. **막다른 곳이 없다** — 사람의 손이 서장을 닫는다
            pick = pick.append(clickable(say("debut_label", "▸ 강호로 나선다"),
                    say("debut_hover", "서장을 닫고 강호에 선다"),
                    scene.token(), -1, NamedTextColor.DARK_RED));
        } else {
            for (int i = 0; i < scene.choices().size(); i++) {
                pick = pick.append(clickable(
                                say("choice_format", "▸ {label}").replace("{label}", scene.choices().get(i)),
                                say("choice_hover", "이 길을 고른다 — 되돌릴 수 없다"),
                                scene.token(), i, NamedTextColor.DARK_BLUE))
                        .append(Component.newline()).append(Component.newline());
            }
        }
        // ★ 간기(刊記) — 이 글이 붓이 아니라 옛 필사본에서 왔다면, **그 사실이 남는다** (침묵 금지)
        if (scene.fallback()) {
            pick = pick.append(Component.newline())
                    .append(legacy(say("fallback_mark", "§8(붓이 더디어 옛 필사본이 왔다)")));
        }
        out.add(pick);
        return out;
    }

    /** 누를 수 있는 한 줄 — 접합의 [잇는다] 와 같은 문법 (RUN_COMMAND. 사람은 아무것도 외우지 않는다) */
    private Component clickable(String label, String hover, String token, int n, NamedTextColor color) {
        return Component.text(label, color, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/혼천 서장 " + token + " " + n))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.DARK_GRAY)));
    }

    /** §-코드가 섞인 등록부의 줄 → Component (PUA 금지 규약: 색은 § 로만) */
    static Component legacy(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s);
    }
}
