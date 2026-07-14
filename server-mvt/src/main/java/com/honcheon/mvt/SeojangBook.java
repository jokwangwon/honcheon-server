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

    private SeojangBook(HoncheonMvt plugin, Path cfg) {
        this.plugin = plugin;
        Map<String, Object> root = RulesConfig.load(cfg.resolve("seojang.yml"));
        RulesConfig.section(root, "book").forEach((k, v) -> text.put(k, String.valueOf(v)));
    }

    public static void init(HoncheonMvt plugin, Path configDir) {
        instance = new SeojangBook(plugin, configDir);
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
        // ★ 붓이 아직 들려 있다 — 책을 주지 않는다. 대신 **사공이 말한다** (침묵 금지)
        if (scene.writing()) {
            String line = scene.ferry();
            if (line != null && !line.equals(told.get(player.getUniqueId()))) {
                told.put(player.getUniqueId(), line);
                player.sendMessage(legacy(line));
            }
            return;
        }
        // 같은 장면의 책을 두 번 주지 않는다 (다리는 2초마다 같은 것을 내려보낸다)
        if (scene.token() != null && scene.token().equals(given.get(player.getUniqueId()))) {
            return;
        }
        given.put(player.getUniqueId(), scene.token());
        told.remove(player.getUniqueId());

        ItemStack book = build(scene);
        // 옛 장의 책은 회수한다 — 품에 두 권이 있으면 어느 것이 지금인지 알 수 없다
        removeOld(player);
        player.getInventory().addItem(book);
        player.sendMessage(legacy(say("given", "§6품 안에 서책 한 권이 들어 있다.")));
        player.sendMessage(legacy(say("reopen", "§7손에 든 서책을 §f우클릭§7 하면 다시 펼쳐진다.")));
        player.openBook(book);   // ★ 저절로 펼쳐진다 — 사람은 아무것도 찾지 않아도 된다
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
    }

    public void forget(java.util.UUID body) {
        given.remove(body);
        told.remove(body);
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

        String headText = scene.last()
                ? say("epilogue_header", "서장의 끝")
                : say("header", "제 {scene} 장")
                .replace("{scene}", String.valueOf(scene.scene() + 1))
                .replace("{total}", String.valueOf(scene.total()));
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
