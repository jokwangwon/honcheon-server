package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;

import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>서장 무대(序章舞臺) — 몸으로 겪는 회랑</b> (사용자 확정 2026-07-25: <i>"글이 아닌 몸으로
 * 역사를 느끼는 형태"</i> · B-179 2차 개정 · 등록부 {@code config/seojang_stage.yml}).
 *
 * <p>항해 중의 서장은 이제 책이 아니다: 정거장에 닿으면 <b>기억의 무대</b>가 안개 속에서
 * 재생된다 — 다층 조형(BlockDisplay 애니메이션) + 파티클 + 사건음, 전부 <b>본인에게만</b>
 * 보인다. 글은 <b>한 줄 맥박</b>(액션바)만 방향을 짚고, 선택은 <b>물 위 세 등불 우클릭</b>이다.
 * LLM 이 지은 전문은 도착 때 필사본으로 남는다 — 개인 서사는 잃지 않는다.
 *
 * <p><b>★ 이 파일은 이야기를 모른다.</b> 판정·글·선택지·진행은 전부 봇의 것이고, 무대의
 * 조형·문장·소리는 전부 등록부의 것이다. 코드는 등록부를 무대에 세울 뿐이다.
 *
 * <p><b>★ 강등 계약</b>: {@code enabled: false} 면 {@link Voyage} 가 옛 책 그릇으로 되돌린다 —
 * 무대가 없어도 서장은 흐른다.
 */
final class SeojangStage implements Listener {

    /** 무대 조형·등불의 표식 — 걷을 때 우리 것만 걷는다 */
    static final NamespacedKey KEY_STAGE = new NamespacedKey("honcheon", "ipdo_stage");
    private static final NamespacedKey KEY_OWNER = new NamespacedKey("honcheon", "ipdo_stage_owner");
    private static final NamespacedKey KEY_TOKEN = new NamespacedKey("honcheon", "ipdo_stage_token");
    private static final NamespacedKey KEY_CHOICE = new NamespacedKey("honcheon", "ipdo_stage_choice");

    private record Layer(int[] at, Material block, float[] scale, float yaw, String anim) { }

    private record Chime(String key, float volume, float pitch) { }

    private record Sprinkle(String type, int count, double spread) { }

    private record SceneSpec(String title, List<String> pulse, Chime sound, Sprinkle particle,
                             List<Layer> layers) { }

    private final HoncheonMvt plugin;
    private final boolean enabled;
    private final int beatInterval;
    private final int beatRead;
    private final int choicesDelay;
    private final double lanternAhead;
    private final double lanternSpread;
    private final double lanternHeight;
    private final String labelFormat;
    private final float labelScale;
    private final int labelLineWidth;
    private final int labelBgArgb;
    private final boolean dlgEnabled;
    private final String dlgHeadFormat;
    private final String dlgTitleFormat;
    private final String dlgBodyFormat;
    private final String dlgFoot;
    private final int dlgInterval;
    private final int dlgMaxBeats;
    private final Chime dlgSound;
    private final boolean npEnabled;
    private final double npAhead;
    private final double npHeight;
    private final float npScale;
    private final int npLineWidth;
    private final int npBgArgb;
    private final String npTitleFormat;
    private final String hint;
    private final String pickLine;
    private final String debutLabel;
    private final boolean memoirGive;
    private final String memoirLine;
    private final String memoirFullLine;
    private final SceneSpec neutral;
    private final Map<String, List<SceneSpec>> sets = new LinkedHashMap<>();
    /** ★3차 — 발단별 첫 장 무대 (모든 경우의 수 사전 제작 · 키 = player_creation inciting_incidents) */
    private final Map<String, SceneSpec> incidents = new LinkedHashMap<>();

    /** 이 몸 앞에 서 있는 무대·등불 (본인에게만 보이는 것들) */
    private final Map<UUID, List<UUID>> standing = new LinkedHashMap<>();
    /** 이 몸의 무대 시계들 (맥박·등불 예약) — 걷을 때 함께 걷는다 */
    private final Map<UUID, List<org.bukkit.scheduler.BukkitTask>> clocks = new LinkedHashMap<>();

    SeojangStage(HoncheonMvt plugin, Path configDir) {
        this.plugin = plugin;
        Map<String, Object> root = RulesConfig.load(configDir.resolve("seojang_stage.yml"));
        Map<String, Object> st = RulesConfig.section(root, "stage");
        this.enabled = !(st.get("enabled") instanceof Boolean e) || e;
        Map<String, Object> pu = RulesConfig.section(st, "pulse");
        this.beatInterval = num(pu.get("beat_interval_ticks"), 60);
        this.beatRead = num(pu.get("beat_read_ticks"), 70);
        this.choicesDelay = num(pu.get("choices_delay_ticks"), 40);
        Map<String, Object> la = RulesConfig.section(st, "lanterns");
        // ★소수 등록값은 소수로 읽는다 — int 파서가 ahead 1.9·spread 1.3 을 1로 잘라
        //   확정 간격이 세계에 닿지 않았다 (2026-07-25 명패형 회차에 잡음)
        this.lanternAhead = dbl(la.get("ahead"), 1.9);
        this.lanternSpread = dbl(la.get("spread"), 1.3);
        this.lanternHeight = dbl(la.get("height"), 1.05);
        // ★3.0 명패형 + 먹 테 (사용자 확정 「명패처럼 디자인」) — 판목 몸은 걷혔다 (묘비)
        this.labelFormat = str(la.get("label_format"), "§f[ {label} ]");
        this.labelScale = (float) dbl(la.get("label_scale"), 0.8);
        this.labelLineWidth = num(la.get("label_line_width"), 200);
        this.labelBgArgb = argb(la.get("label_bg_argb"), 0x78140F0C);
        Map<String, Object> dg = RulesConfig.section(st, "dialogue");
        this.dlgEnabled = !(dg.get("enabled") instanceof Boolean db) || db;
        this.dlgHeadFormat = str(dg.get("head_format"), "§8── §6{header} §8──");
        this.dlgTitleFormat = str(dg.get("title_format"), " §f『{title}』");
        this.dlgBodyFormat = str(dg.get("body_format"), " §7{line}");
        this.dlgFoot = str(dg.get("foot"), "");
        this.dlgInterval = Math.max(20, num(dg.get("sentence_interval_ticks"), 55));
        this.dlgMaxBeats = Math.max(1, num(dg.get("max_beats"), 8));
        Map<String, Object> ds = RulesConfig.section(dg, "sound");
        this.dlgSound = ds.isEmpty() ? null : new Chime(str(ds.get("key"), ""),
                (float) dbl(ds.get("volume"), 0.35), (float) dbl(ds.get("pitch"), 1.4));
        Map<String, Object> np = RulesConfig.section(st, "narration_panel");
        this.npEnabled = !(np.get("enabled") instanceof Boolean b) || b;
        this.npAhead = dbl(np.get("ahead"), 1.9);
        this.npHeight = dbl(np.get("height"), 2.6);
        this.npScale = (float) dbl(np.get("scale"), 0.7);
        this.npLineWidth = num(np.get("line_width"), 170);
        this.npBgArgb = argb(np.get("background_argb"), 0xC8140F0C);
        this.npTitleFormat = str(np.get("title_format"), "§6§l{header} §7— §f{title}");
        this.hint = str(la.get("hint"), "");
        this.pickLine = str(la.get("pick_line"), "");
        this.debutLabel = str(la.get("debut_label"), "강호로 나선다");
        Map<String, Object> me = RulesConfig.section(st, "memoir");
        this.memoirGive = !(me.get("give") instanceof Boolean g) || g;
        this.memoirLine = str(me.get("line"), "");
        this.memoirFullLine = str(me.get("full_line"), "");
        this.neutral = sceneOf(RulesConfig.section(st, "neutral"), "");
        Object incRaw = st.get("incidents");
        if (incRaw instanceof Map<?, ?> im) {
            for (Map.Entry<?, ?> en : im.entrySet()) {
                if (en.getValue() instanceof Map<?, ?> sm) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> spec = (Map<String, Object>) sm;
                    incidents.put(String.valueOf(en.getKey()),
                            sceneOf(spec, String.valueOf(en.getKey())));
                }
            }
        }
        Object setsRaw = st.get("sets");
        if (setsRaw instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> en : m.entrySet()) {
                List<SceneSpec> list = new ArrayList<>();
                if (en.getValue() instanceof List<?> sl) {
                    for (Object o : sl) {
                        if (o instanceof Map<?, ?> sm) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> spec = (Map<String, Object>) sm;
                            list.add(sceneOf(spec, str(spec.get("title"), "")));
                        }
                    }
                }
                sets.put(String.valueOf(en.getKey()), list);
            }
        }
    }

    private static SceneSpec sceneOf(Map<String, Object> m, String title) {
        List<String> pulse = new ArrayList<>();
        if (m.get("pulse") instanceof List<?> pl) {
            pl.forEach(v -> pulse.add(String.valueOf(v)));
        }
        Map<String, Object> so = sub(m, "sound");
        Chime sound = so.isEmpty() ? null : new Chime(str(so.get("key"), ""),
                dec(so.get("volume"), 1.0), dec(so.get("pitch"), 1.0));
        Map<String, Object> pa = sub(m, "particle");
        Sprinkle particle = pa.isEmpty() ? null : new Sprinkle(str(pa.get("type"), ""),
                num(pa.get("count"), 6), (double) dec(pa.get("spread"), 2.0));
        List<Layer> layers = new ArrayList<>();
        if (m.get("layers") instanceof List<?> ll) {
            for (Object o : ll) {
                if (!(o instanceof Map<?, ?> lm)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> l = (Map<String, Object>) lm;
                int[] at = triple(l.get("at"));
                Material block;
                try {
                    block = Material.valueOf(str(l.get("block"), "STONE"));
                } catch (IllegalArgumentException e) {
                    continue;   // 등록부가 모르는 블록 — 그 층만 비운다 (감사가 잡는다)
                }
                float[] scale = fscale(l.get("scale"));
                layers.add(new Layer(at, block, scale,
                        (float) dec(l.get("yaw"), 0.0), str(l.get("anim"), "멎는다")));
            }
        }
        return new SceneSpec(title, pulse, sound, particle, layers);
    }

    // ─── 등록부 판독 잔뼈 ───

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> root, String key) {
        Object v = root.get(key);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    private static String str(Object v, String def) {
        return v == null ? def : String.valueOf(v);
    }

    /**
     * 전문을 <b>문장 숨</b>으로 가른다 — 타자기 리듬의 단위 (문장 부호 뒤에서 가르고,
     * 문장이 많으면 {@code maxBeats} 로 고르게 합쳐 총 시간을 막는다 — 리듬은 살고
     * 밤은 길어지지 않는다).
     */
    private static List<String> sentenceBeats(String text, int maxBeats) {
        String flat = text.replace("\r", "").replaceAll("\\n+", " ").trim();
        List<String> sents = new ArrayList<>();
        for (String s : flat.split("(?<=[.!?…”』」)])\\s+")) {
            if (!s.isBlank()) {
                sents.add(s.trim());
            }
        }
        if (sents.isEmpty()) {
            return List.of(flat);
        }
        if (sents.size() <= maxBeats) {
            return sents;
        }
        List<String> out = new ArrayList<>();
        int per = (int) Math.ceil(sents.size() / (double) maxBeats);
        for (int i = 0; i < sents.size(); i += per) {
            out.add(String.join(" ", sents.subList(i, Math.min(sents.size(), i + per))));
        }
        return out;
    }

    private static double dbl(Object v, double def) {
        return v instanceof Number n ? n.doubleValue() : def;
    }

    /** ARGB 등록값 — "0xC8140F0C" 문자열 (나루 안내판과 같은 문법) */
    private static int argb(Object v, int def) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? def : (int) Long.decode(String.valueOf(v).trim()).longValue();
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int num(Object v, int def) {
        return v instanceof Number n ? n.intValue() : def;
    }

    private static float dec(Object v, double def) {
        return v instanceof Number n ? n.floatValue() : (float) def;
    }

    private static int[] triple(Object v) {
        int[] out = new int[]{0, 0, 0};
        if (v instanceof List<?> l) {
            for (int i = 0; i < Math.min(3, l.size()); i++) {
                if (l.get(i) instanceof Number n) {
                    out[i] = n.intValue();
                }
            }
        }
        return out;
    }

    private static float[] fscale(Object v) {
        float[] out = new float[]{1f, 1f, 1f};
        if (v instanceof List<?> l) {
            for (int i = 0; i < Math.min(3, l.size()); i++) {
                if (l.get(i) instanceof Number n) {
                    out[i] = n.floatValue();
                }
            }
        }
        return out;
    }

    boolean enabled() {
        return enabled;
    }

    boolean memoirGive() {
        return memoirGive;
    }

    String memoirLine() {
        return memoirLine;
    }

    String memoirFullLine() {
        return memoirFullLine;
    }

    /**
     * 계열 판별 — <b>장면 제목으로 등록부를 되짚는다</b> (다리는 계열을 안 싣는다. 제목이 정본이고,
     * {@code {region}} 류 자리채움은 앞부분만 대조한다). 못 찾으면 null = 중립 무대.
     */
    String detectSet(int sceneIdx, String title) {
        if (title == null) {
            return null;
        }
        for (Map.Entry<String, List<SceneSpec>> en : sets.entrySet()) {
            List<SceneSpec> list = en.getValue();
            if (list.isEmpty()) {
                continue;
            }
            SceneSpec spec = list.get(Math.min(sceneIdx, list.size() - 1));
            if (titleMatches(spec.title(), title)) {
                return en.getKey();
            }
        }
        return null;
    }

    private static boolean titleMatches(String registry, String actual) {
        int brace = registry.indexOf('{');
        if (brace < 0) {
            return registry.equals(actual);
        }
        String prefix = registry.substring(0, brace);
        return !prefix.isBlank() && actual.startsWith(prefix);
    }

    /**
     * <b>무대를 재생한다</b> — 정거장에 닿은 순간 {@link Voyage} 가 부른다.
     * 꺼져 있으면 false (강등 — 부르는 쪽이 책을 편다).
     */
    boolean play(Player player, World w, double baseX, double baseY, Location boat,
                 WorldBridge.SeojangScene scene, String setName) {
        if (!enabled) {
            return false;
        }
        clear(player.getUniqueId());
        SceneSpec spec = resolve(setName, scene);

        // 도착 — 장 제목 타이틀 + 사건음 (책 도착과 같은 문법 · 같은 등록부)
        SeojangBook book = SeojangBook.get();
        player.sendTitle("§6§l" + book.headText(scene), "§7" + scene.title(), 10, 50, 15);
        book.chime(player, "open");
        if (spec.sound() != null && !spec.sound().key().isBlank()) {
            player.playSound(new Location(w, baseX, baseY, player.getLocation().getZ()),
                    spec.sound().key(), spec.sound().volume(), spec.sound().pitch());
        }

        // 조형 — 다층 무대 (본인에게만 보인다)
        List<UUID> mine = standing.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        for (Layer l : spec.layers()) {
            mine.add(spawnLayer(player, w, baseX, baseY, l).getUniqueId());
        }

        // ★배 위 글판 = 제목 표지 (2차 빨간펜 "서사 글판이 너무 난잡" — 전문 글판은 묘비.
        //   전문은 아래 dialogue 채팅의 것, 이 판은 지금 몇 장인지의 자리 표지다)
        if (npEnabled) {
            String head = npTitleFormat.replace("{header}", book.headText(scene))
                    .replace("{title}", scene.title());
            Location at = boat.clone().add(npAhead, npHeight, 0);
            TextDisplay panel = w.spawn(at, TextDisplay.class, e -> {
                e.setText(head);
                e.setBillboard(Display.Billboard.CENTER);
                e.setLineWidth(npLineWidth);
                e.setSeeThrough(false);
                e.setShadowed(true);
                e.setDefaultBackground(false);
                e.setBackgroundColor(org.bukkit.Color.fromARGB(npBgArgb));
                e.setPersistent(false);
                e.getPersistentDataContainer().set(KEY_STAGE, PersistentDataType.STRING,
                        player.getUniqueId().toString());
                e.setBrightness(new Display.Brightness(13, 15));
                e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                        new Vector3f(npScale, npScale, npScale), new AxisAngle4f()));
            });
            onlyFor(player, panel);
            mine.add(panel.getUniqueId());
        }

        // 파티클 — 무대가 걷힐 때까지 숨을 쉰다
        List<org.bukkit.scheduler.BukkitTask> myClocks =
                clocks.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        if (spec.particle() != null && !spec.particle().type().isBlank()) {
            Particle type;
            try {
                type = Particle.valueOf(spec.particle().type());
            } catch (IllegalArgumentException e) {
                type = null;   // 등록부가 모르는 입자 — 침묵보다 무대 없는 숨이 낫다 (감사가 잡는다)
            }
            if (type != null) {
                Particle fType = type;
                myClocks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                    if (player.isOnline()) {
                        player.spawnParticle(fType, baseX + 3, baseY + 1.5,
                                player.getLocation().getZ() - 8,
                                spec.particle().count(),
                                spec.particle().spread(), 1.0, spec.particle().spread(), 0.01);
                    }
                }, 10L, 25L));
            }
        }

        // 맥박 — 한 줄씩, 숨 간격으로 (글은 방향만 짚는다 — 전문은 대화·필사본의 것)
        List<String> pulse = spec.pulse();
        for (int i = 0; i < pulse.size(); i++) {
            String line = pulse.get(i);
            myClocks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    plugin.skills().notice(player, "서장", line, beatRead);
                }
            }, (long) beatInterval * (i + 1)));
        }

        // ★★서사 = 한월풍 대화 채팅 (2차 빨간펜 "서사 글판이 너무 난잡" — 사용자 확정:
        //   디자인 강화한 대화 글 + 타자기 자동): 장식 틀(붓선 E0B0·붓점 E0B1 — SJ-002 가
        //   굽고 배선만 미결이던 기억첩 글리프)이 서고, 전문이 문장 단위로 흐른다.
        //   놓쳐도 스크롤에 남는다. 패는 마지막 문장 뒤에 걸린다.
        long dlgTicks = 0;
        if (dlgEnabled && scene.narration() != null && !scene.narration().isBlank()) {
            List<String> beats = sentenceBeats(scene.narration(), dlgMaxBeats);
            player.sendMessage(SeojangBook.legacy(
                    dlgHeadFormat.replace("{header}", book.headText(scene))));
            player.sendMessage(SeojangBook.legacy(
                    dlgTitleFormat.replace("{title}", scene.title())));
            for (int i = 0; i < beats.size(); i++) {
                String line = dlgBodyFormat.replace("{line}", beats.get(i));
                boolean last = i == beats.size() - 1;
                myClocks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    player.sendMessage(SeojangBook.legacy(line));
                    if (dlgSound != null && !dlgSound.key().isBlank()) {
                        player.playSound(player.getLocation(), dlgSound.key(),
                                dlgSound.volume(), dlgSound.pitch());
                    }
                    if (last && !dlgFoot.isEmpty()) {
                        player.sendMessage(SeojangBook.legacy(dlgFoot));
                    }
                }, (long) dlgInterval * (i + 1)));
            }
            dlgTicks = (long) dlgInterval * (beats.size() + 1);
        }

        // 선택 패 — 마지막 숨·마지막 문장이 지나면 이물 난간에 걸린다 (읽기 전에 안 걸린다)
        long lanternAt = Math.max((long) beatInterval * (pulse.size() + 1), dlgTicks)
                + choicesDelay;
        Location dock = boat.clone();   // 정박 좌표 — 선택 동안 배는 매여 있다
        myClocks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // ★5차 — 무대는 서장 월드의 배 위에서 선다 (실기동 2026-07-25 "2장이 다시 시작되지도
            //   않아": 나루-검사만 있어서 서장 월드의 몸에게 패가 영영 안 걸렸다 — 선택 불가 = 갇힘)
            if (player.isOnline() && (Antechamber.isAntechamber(player.getWorld())
                    || Voyage.isSea(player.getWorld()))) {
                offerChoices(player, w, dock, scene);
            }
        }, lanternAt));
        return true;
    }

    /**
     * 무대 고르기 — ★3차 (사용자 확정 2026-07-25 "모든 경우의 수로 다 만드는 건"):
     * <b>첫 장은 발단이 가른다</b> (역병의 그날 밤과 화재의 그날 밤은 다른 기억이다).
     * 발단 무대가 등록부에 없으면 계열 무대로, 그것도 없으면 중립(안개의 고동)으로 강등 —
     * 어느 계단에서도 침묵은 없다. 뒷장(길 위·낯선 고을·에필로그)은 계열 공유다.
     */
    private SceneSpec resolve(String setName, WorldBridge.SeojangScene scene) {
        if (scene.scene() == 0 && !scene.last() && scene.incident() != null) {
            SceneSpec byIncident = incidents.get(scene.incident());
            if (byIncident != null) {
                return byIncident;
            }
        }
        List<SceneSpec> list = setName == null ? null : sets.get(setName);
        return list == null || list.isEmpty() ? neutral
                : list.get(Math.min(scene.scene(), list.size() - 1));
    }

    /** 조형 한 층 — 물속에서 솟거나(솟는다), 기울며 자리 잡거나(기운다), 그저 선다(멎는다) */
    private BlockDisplay spawnLayer(Player owner, World w, double baseX, double baseY, Layer l) {
        Location at = new Location(w, baseX + l.at()[0] + 0.5, baseY + l.at()[1],
                owner.getLocation().getZ() + l.at()[2]);
        Vector3f scale = new Vector3f(l.scale()[0], l.scale()[1], l.scale()[2]);
        Vector3f center = new Vector3f(-l.scale()[0] / 2f, 0f, -l.scale()[2] / 2f);
        AxisAngle4f rest = new AxisAngle4f((float) Math.toRadians(l.yaw()), 0f, 1f, 0f);
        BlockDisplay d = w.spawn(at, BlockDisplay.class, e -> {
            e.setBlock(l.block().createBlockData());
            e.setPersistent(false);
            e.getPersistentDataContainer().set(KEY_STAGE, PersistentDataType.STRING,
                    owner.getUniqueId().toString());
            e.setBrightness(new Display.Brightness(11, 15));
            switch (l.anim()) {
                case "솟는다" -> e.setTransformation(new Transformation(
                        new Vector3f(center.x, -2.5f, center.z), new AxisAngle4f(rest),
                        new Vector3f(scale), new AxisAngle4f()));
                case "기운다" -> e.setTransformation(new Transformation(
                        new Vector3f(center), new AxisAngle4f(0f, 0f, 1f, 0f),
                        new Vector3f(scale), new AxisAngle4f()));
                default -> e.setTransformation(new Transformation(
                        new Vector3f(center), new AxisAngle4f(rest),
                        new Vector3f(scale), new AxisAngle4f()));
            }
        });
        onlyFor(owner, d);
        if (!"멎는다".equals(l.anim())) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (d.isValid()) {
                    d.setInterpolationDelay(0);
                    d.setInterpolationDuration(50);
                    AxisAngle4f tilt = "기운다".equals(l.anim())
                            ? new AxisAngle4f((float) Math.toRadians(l.yaw()), 0.12f, 1f, 0.1f)
                            : rest;
                    d.setTransformation(new Transformation(
                            new Vector3f(center), tilt, new Vector3f(scale), new AxisAngle4f()));
                }
            }, 3L);
        }
        return d;
    }

    /**
     * 선택 패(牌木) — ★2.0 (사용자 확정: "큰 나룻배에 선택지가 올라가 있고 그걸 누른다").
     * 정박한 배의 <b>이물 난간</b>에 패 셋이 걸린다 — 좌석에서 시선만 돌려 우클릭한다
     * (이동 0 · 조준 실패 없음). 에필로그는 따뜻한 등롱 하나 — 출도의 등이다.
     */
    private void offerChoices(Player player, World w, Location boat,
                              WorldBridge.SeojangScene scene) {
        List<String> labels = scene.last() ? List.of(debutLabel) : scene.choices();
        if (labels.isEmpty()) {
            return;   // 고를 것이 없는 장 — 다음 배달이 흐름을 잇는다
        }
        List<UUID> mine = standing.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        double z0 = boat.getZ() - (labels.size() - 1) * lanternSpread / 2.0;
        for (int i = 0; i < labels.size(); i++) {
            String lab = labels.get(i);
            double x = boat.getX() + lanternAhead;
            double z = z0 + i * lanternSpread;
            Location at = new Location(w, x, boat.getY() + lanternHeight, z);
            // ★3.0 명패형 (사용자 확정 「명패처럼 디자인」 — 명패형 + 먹 테): 판목 몸은
            //   걷혔다 【묘비: 세로 판목 BlockDisplay + ◆ 접두 0.6배 세로 조판】. 에필로그의
            //   따뜻한 등롱 하나(=출도)만 몸을 가진다 (2차 확정 그대로).
            if (scene.last()) {
                Vector3f pScale = new Vector3f(0.45f, 0.45f, 0.45f);
                BlockDisplay body = w.spawn(at, BlockDisplay.class, e -> {
                    e.setBlock(Material.LANTERN.createBlockData());
                    e.setPersistent(false);
                    e.getPersistentDataContainer().set(KEY_STAGE, PersistentDataType.STRING,
                            player.getUniqueId().toString());
                    e.setBrightness(new Display.Brightness(15, 15));
                    e.setTransformation(new Transformation(
                            new Vector3f(-pScale.x / 2f, 0f, -pScale.z / 2f),
                            new AxisAngle4f(), pScale, new AxisAngle4f()));
                });
                onlyFor(player, body);
                mine.add(body.getUniqueId());
            }
            TextDisplay label = w.spawn(at.clone().add(0, 0.55, 0), TextDisplay.class, e -> {
                e.setText(labelFormat.replace("{label}", lab));
                e.setBillboard(Display.Billboard.CENTER);
                e.setLineWidth(labelLineWidth);   // 한 줄 — 명패는 줄을 안 바꾼다
                e.setSeeThrough(false);
                e.setShadowed(true);
                e.setDefaultBackground(false);
                e.setBackgroundColor(org.bukkit.Color.fromARGB(labelBgArgb));
                e.setPersistent(false);
                e.getPersistentDataContainer().set(KEY_STAGE, PersistentDataType.STRING,
                        player.getUniqueId().toString());
                e.setBrightness(new Display.Brightness(13, 15));
                e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                        new Vector3f(labelScale, labelScale, labelScale), new AxisAngle4f()));
            });
            int idx = scene.last() ? -1 : i;
            Interaction hand = w.spawn(at.clone().add(0, 0.2, 0), Interaction.class, e -> {
                e.setInteractionWidth(1.0f);   // 명패 하나의 손 — spread 1.3 이라 이웃과 안 겹친다
                e.setInteractionHeight(0.8f);
                e.setPersistent(false);
                var pdc = e.getPersistentDataContainer();
                pdc.set(KEY_STAGE, PersistentDataType.STRING, player.getUniqueId().toString());
                pdc.set(KEY_OWNER, PersistentDataType.STRING, player.getUniqueId().toString());
                pdc.set(KEY_TOKEN, PersistentDataType.STRING,
                        scene.token() == null ? "" : scene.token());
                pdc.set(KEY_CHOICE, PersistentDataType.INTEGER, idx);
            });
            onlyFor(player, label);
            onlyFor(player, hand);
            mine.add(label.getUniqueId());
            mine.add(hand.getUniqueId());
        }
        if (!hint.isEmpty()) {
            player.sendMessage(SeojangBook.legacy(hint));
        }
    }

    /** 본인에게만 보인다 — 삼도천의 격리 확정(멀리 실루엣만)과 계열 무대의 전제 */
    private void onlyFor(Player owner, Entity e) {
        for (Player p : e.getWorld().getPlayers()) {
            if (!p.getUniqueId().equals(owner.getUniqueId())) {
                p.hideEntity(plugin, e);
            }
        }
    }

    /** <b>등불을 우클릭했다</b> — 선택은 몸의 행위다. 번호 하나를 다리에 얹을 뿐, 판정은 봇의 것 */
    @EventHandler
    public void onPick(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction hand)) {
            return;
        }
        var pdc = hand.getPersistentDataContainer();
        String owner = pdc.get(KEY_OWNER, PersistentDataType.STRING);
        if (owner == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.getUniqueId().toString().equals(owner)) {
            return;   // 남의 등불 — 그 사람 눈에만 보이는 기억이다 (히트박스만 스친 것)
        }
        String token = pdc.get(KEY_TOKEN, PersistentDataType.STRING);
        Integer choice = pdc.get(KEY_CHOICE, PersistentDataType.INTEGER);
        if (token == null || token.isBlank() || choice == null) {
            return;
        }
        clear(player.getUniqueId());   // 등불·무대를 걷는다 — 연타 잠금이자 장면의 끝
        if (!pickLine.isEmpty()) {
            player.sendMessage(SeojangBook.legacy(pickLine));
        }
        SeojangBook.get().chime(player, "choose");
        WorldBridge.seojangChoice(player.getUniqueId(), player.getName(), token, choice);
    }

    /** 이 몸의 무대·등불·시계를 걷는다 (선택·다음 장·하선·퇴장 공통) */
    void clear(UUID body) {
        List<UUID> mine = standing.remove(body);
        if (mine != null) {
            for (UUID id : mine) {
                Entity e = Bukkit.getEntity(id);
                if (e != null) {
                    e.remove();
                }
            }
        }
        List<org.bukkit.scheduler.BukkitTask> myClocks = clocks.remove(body);
        if (myClocks != null) {
            myClocks.forEach(org.bukkit.scheduler.BukkitTask::cancel);
        }
    }

    /** 종료·재조성 — 주인 잃은 무대까지 걷는다 (표식 있는 것만) */
    void sweep(World w) {
        for (Entity e : w.getEntities()) {
            if (e.getPersistentDataContainer().has(KEY_STAGE)) {
                e.remove();
            }
        }
        standing.clear();
        clocks.values().forEach(list -> list.forEach(org.bukkit.scheduler.BukkitTask::cancel));
        clocks.clear();
    }
}
