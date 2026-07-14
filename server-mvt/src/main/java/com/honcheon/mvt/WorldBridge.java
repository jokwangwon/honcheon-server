package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 세계 다리(世界橋) — MVT 쪽 발신부. <b>몸에서 벌어진 일을 장부로 보낸다.</b>
 *
 * <p>이 프로젝트는 몸이 둘이다. 마크(여기)에서 사람이 죽고 도적이 베이고 검기가 터지는데,
 * 봇(장부)은 그것을 하나도 모른다. 이 클래스가 그 사이의 유일한 통로다.
 *
 * <p><b>왜 파일인가.</b> 두 프로세스는 별도 JVM 이고 봇은 항상 떠 있지 않다. 소켓이면 봇이 꺼진 동안의
 * 사건이 증발한다. 봇의 SQLite 에 직접 쓰면 두 프로세스가 한 파일을 놓고 다투고, 잠금 대기가
 * <b>메인 스레드에서 일어나면 그것이 곧 TPS 붕괴다</b>. 그래서 append-only JSONL 이다 —
 * 한 줄이 한 사건이고, 쓰는 쪽은 절대 막히지 않고, 봇은 켜질 때 밀린 것을 전부 따라잡는다.
 * (판단 근거·탈락 후보는 docs/design/world_bridge.md 2장)
 *
 * <p><b>Bukkit 을 모른다.</b> 이 클래스에는 org.bukkit import 가 하나도 없다 — 일부러 그렇다.
 * ① 헤드리스로 검증할 수 있고 ② 메인 스레드를 건드리지 않는다(쓰기는 전용 데몬 스레드).
 * 되먹임을 세계에 적용하는 것(=메인 스레드 진입)은 {@link #onState} 를 등록하는 <b>배선 쪽의 몫</b>이다:
 *
 * <pre>
 *   // HoncheonMvt.onEnable — 다른 init 들 옆
 *   WorldBridge.init(cfg, getLogger());
 *   WorldBridge.onState(state -&gt; getServer().getScheduler().runTask(this, () -&gt; {
 *       for (String tag : WorldBridge.reactionTags()) {
 *           populace.rumor(tag, state.reactions().contains(tag));   // 도적 소문 → 나무꾼이 산길을 피한다
 *       }
 *   }));
 *   WorldBridge.start();
 *   // onDisable: WorldBridge.stop();
 * </pre>
 *
 * <p><b>등록제.</b> kind 도 페이로드도 {@code config/world_bridge.yml} 이 정한다. 여기 없는 사건은
 * 세계에 존재하지 않는다 — 코드가 새 이벤트를 발명하지 못하게 하는 것이 이 다리의 첫 규칙이다.
 */
public final class WorldBridge {

    private WorldBridge() {
    }

    // ══════════════ 등록부 (config/world_bridge.yml) ══════════════

    private static final Set<String> KINDS = new LinkedHashSet<>();
    private static final Map<String, List<String>> REACTION_MAP = new LinkedHashMap<>();
    private static Path bridgeDir;
    private static Path outboxDir;
    private static Path snapshotFile;
    private static int pollSeconds = 20;
    private static Logger log = Logger.getLogger("WorldBridge");

    private static long dedupeMillis = 600_000L;   // events.qi_manifested.effects.dedupe_seconds

    private static final Map<String, Long> RECENT = new LinkedHashMap<>();   // 중복 발신 차단 (fresh)
    private static final ConcurrentLinkedQueue<String> QUEUE = new ConcurrentLinkedQueue<>();
    private static final CopyOnWriteArrayList<Consumer<State>> LISTENERS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static volatile State current = State.empty();
    private static volatile long snapshotStamp;
    private static Thread worker;

    /**
     * 등록부 판독 + 다리 자리 확보. HoncheonMvt.onEnable 에서 다른 init 들과 함께 한 번 부른다.
     * 등록부가 없거나 깨졌으면 <b>다리는 서지 않는다</b> (조용히) — 서버는 그대로 뜬다.
     * 세계의 절반이 안 붙는 것이 서버가 안 뜨는 것보다 낫다.
     *
     * @param configDir 플러그인 데이터 폴더의 config (HoncheonMvt 가 이미 쥐고 있는 그것)
     */
    @SuppressWarnings("unchecked")
    public static void init(Path configDir, Logger logger) {
        log = logger == null ? Logger.getLogger("WorldBridge") : logger;
        KINDS.clear();
        REACTION_MAP.clear();

        Map<String, Object> root;
        try {
            root = RulesConfig.load(configDir.resolve("world_bridge.yml"));
        } catch (RuntimeException e) {
            log.warning("세계 다리 등록부 없음 (world_bridge.yml) — 다리를 놓지 않는다: " + e.getMessage());
            return;
        }
        if (root == null) {
            return;
        }
        Map<String, Object> transport = map(root.get("transport"));
        Map<String, Object> events = map(root.get("events"));
        KINDS.addAll(events.keySet());
        pollSeconds = Math.max(2, num(transport.get("snapshot_seconds"), pollSeconds));
        RECENT.clear();
        dedupeMillis = 1000L * num(map(map(events.get("qi_manifested")).get("effects"))
                .get("dedupe_seconds"), 600);

        // 신원 접합 — 청의 수명 (안내 문구용. 진짜 만료 판정은 봇의 장부가 한다 — 정본은 하나다)
        Map<String, Object> identity = map(root.get("identity"));
        linkTtlSeconds = Math.max(15, num(identity.get("ttl_seconds"), linkTtlSeconds));
        // 접합의 문 — 채팅에 뜰 문장 (identity.gate.mc_chat). 코드는 문장을 지어내지 않는다
        GATE.clear();
        map(map(identity.get("gate")).get("mc_chat"))
                .forEach((k, v) -> GATE.put(k, String.valueOf(v)));

        Map<String, Object> feedback = map(root.get("feedback"));
        map(feedback.get("reaction_map")).forEach((tag, tags) -> {
            List<String> want = new ArrayList<>();
            for (Object t : list(tags)) {
                want.add(String.valueOf(t));
            }
            REACTION_MAP.put(tag, want);
        });

        Path root0 = repoRoot(configDir);
        bridgeDir = root0.resolve(String.valueOf(transport.getOrDefault("dir", "run/bridge")));
        outboxDir = bridgeDir.resolve(String.valueOf(transport.getOrDefault("outbox", "mvt")));
        snapshotFile = bridgeDir.resolve(String.valueOf(
                transport.getOrDefault("snapshot", "world_state.json")));
        // ★ 접합의 두 파일 — 청(봇 → 여기)과 명부(여기 → 봇). 스냅숏(20초)과 별도다:
        //   사람이 화면 앞에서 **기다리는** 값이라 20초는 설계가 아니라 사고다.
        linkRequestsFile = bridgeDir.resolve(String.valueOf(
                transport.getOrDefault("link_requests", "link_requests.json")));
        rosterFile = bridgeDir.resolve(String.valueOf(
                transport.getOrDefault("roster", "roster.json")));
        linkPollSeconds = Math.max(1, num(transport.get("link_poll_seconds"), linkPollSeconds));
        rosterSeconds = Math.max(1, num(transport.get("roster_seconds"), rosterSeconds));
        // ★ 서장의 책 — 접합과 **같은 이유로 같은 시계**다: 사람이 책을 펴 놓고 글자를 누르며 기다린다
        seojangFile = bridgeDir.resolve(String.valueOf(
                transport.getOrDefault("seojang", "seojang.json")));
        seojangPollSeconds = Math.max(1, num(transport.get("seojang_poll_seconds"), seojangPollSeconds));
        // ★ B-118 — 서장 책의 심장 소리가 이보다 낡으면 봇이 죽은 것이다 (그때는 문이 사람을 붙들지 않는다)
        seojangStaleSeconds = Math.max(seojangPollSeconds,
                num(transport.get("seojang_stale_seconds"), seojangStaleSeconds));
        try {
            Files.createDirectories(outboxDir);
        } catch (IOException e) {
            log.warning("다리 디렉터리를 만들 수 없다: " + outboxDir + " — " + e.getMessage());
            bridgeDir = null;
            return;
        }
        log.info("세계 다리 — 발신 " + outboxDir + " · 수신(되먹임) " + snapshotFile
                + " · 청 " + linkRequestsFile + " (" + linkPollSeconds + "초) · 명부 " + rosterFile
                + " (" + rosterSeconds + "초) · 서장 " + seojangFile + " (" + seojangPollSeconds + "초)"
                + " · 등록 이벤트 " + KINDS.size() + "종 " + KINDS);
    }

    // ══════════════ 서장(序章) — ★ **마크는 서책이다. 저자는 봇이다** ══════════════
    //
    // 【★ 여기에는 문장이 한 줄도 없다】 글도, 선택지도, 판정도 **전부 봇이 낸다.**
    //   LLM(로컬 qwen3 또는 Claude)도 · 판정 엔진(2d6·성별 보정)도 · 시트도 봇에만 있고,
    //   **마크는 DB 를 열지 않는다** (단일 작성자 규약). 마크가 문장을 지으면 **정본이 둘**이 된다 —
    //   이 저장소가 반복해서 데인 바로 그 병이다.
    //
    //   그래서 이 절이 하는 일은 둘뿐이다:
    //     ① 봇이 내려보낸 **현재 장면 한 장**을 읽는다 (seojang.json)
    //     ② 그 손이 누른 **선택지 번호**를 되돌린다 (seojang_choice)
    //
    // 【★ 나루의 사공 — 「배는 한 명씩 탄다」】 봇의 붓은 하나다 (GPU 도 하나다: 4건 동시 = 89.5초).
    //   글이 아직 안 왔으면 state 가 "쓰는_중" 이고, 그때 마크는 **책을 주지 않고** 사공의 말만 한다.
    //   ★ 침묵 금지 — 앞에 몇 사람인지 봇이 `ferry` 에 실어 보낸다.

    private static Path seojangFile;
    private static int seojangPollSeconds = 2;
    private static volatile long seojangStamp;
    private static final CopyOnWriteArrayList<Consumer<List<SeojangScene>>> SEOJANG_LISTENERS =
            new CopyOnWriteArrayList<>();

    // ─── ★ B-118 — 서장의 **상태** (책이 아니라 명단이다) ───
    //
    // 【실사용 2026-07-14 · 부계정】 접합 직후 자동 출도가 서장 없이 사람을 청하현으로 보냈다.
    //   대기소의 게이트가 SeojangBook 토큰(**이미 배달된 책**)만 봤는데, 새 몸은 붓(LLM)이
    //   서장을 짓는 수십 초 동안 토큰이 없다 — 게이트가 경주에서 이겨 버렸다.
    //
    // 【정본】 봇은 서장이 끝나지 않은 접합된 몸을 **전부** seojang.json 에 싣는다 — 글이 아직
    //   안 그려진 몸도 state=쓰는_중 으로 실린다 (GameListener.seojangEntries). 그리고 에필로그의
    //   [강호로 나선다] 가 눌리는 순간 status 가 "강호"가 되어 명단에서 **사라진다**. 그러므로
    //   「이 명단에 있다 = 서장이 아직 끝나지 않았다」 — 이것이 게이트가 봐야 할 판정이다.
    //
    // 【무한 대기 함정 금지】 봇은 명단이 비어도 seojang.json 을 계속 다시 굽는다 (poll 주기) —
    //   `at` 이 심장 소리다. 그 소리가 seojang_stale_seconds 보다 낡으면 봇이 죽은 것이고,
    //   죽은 다리가 사람을 대기소에 가둬서는 안 된다 (gate.bridge_down 과 같은 원칙).

    private static int seojangStaleSeconds = 60;
    private static volatile long seojangPublishedAt;
    private static volatile Set<String> seojangBodies = Set.of();

    /**
     * 봇이 내려보낸 <b>서장의 한 장.</b> 마크는 이것을 <b>책으로 그릴 뿐</b>이다.
     *
     * @param writing   붓이 아직 들려 있다 (글이 안 왔다) — 책을 주지 않는다. 사공의 말만 한다
     * @param ferry     사공의 말 (줄이 밀렸을 때만. 없으면 null) — <b>침묵 금지</b>
     * @param fallback  이 글이 <b>옛 필사본</b>(폴백)인가 — 책의 간기 한 줄로 남는다
     * @param token     이 장면의 지목. 낡은 책의 클릭은 봇이 여기서 거른다 (열쇠가 아니다)
     * @param last      에필로그인가 — 선택지 대신 [강호로 나선다] 가 붙는다
     */
    public record SeojangScene(UUID body, String character, int scene, int total, String title,
                               String narration, List<String> choices, String token,
                               boolean writing, boolean last, boolean fallback, String ferry) {
    }

    /** 책이 왔다 — 그리는 손을 등록한다 (워커 스레드에서 불린다. 받는 쪽이 메인으로 넘겨야 한다) */
    public static void onSeojang(Consumer<List<SeojangScene>> listener) {
        SEOJANG_LISTENERS.add(listener);
    }

    /**
     * ★ <b>그 손이 책의 글자를 눌렀다</b> — 서장의 유일한 진행 신호.
     *
     * <p>마크는 <b>아무것도 판정하지 않는다.</b> 번호 하나를 다리에 얹을 뿐이고, 옳고 그름은
     * 봇이 정한다 (토큰이 지금 그 장면인가 · 그 몸이 그 캐릭터인가 · 등록부에 있는 선택지인가).
     *
     * @param choice 선택지 번호 (0부터). <b>-1 = 출도</b> ([강호로 나선다])
     */
    public static void seojangChoice(UUID player, String name, String token, int choice) {
        if (bridgeDir == null || !KINDS.contains("seojang_choice")) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("player_uuid", player.toString());
        data.put("player_name", name);
        data.put("token", token);
        data.put("choice", choice);
        emit("seojang_choice", data);
        flush();   // ★ 사람이 책을 덮고 다음 장을 기다린다 — 워커의 다음 바퀴(0.5초)도 아깝다
    }

    /** 봇이 내려보낸 책을 읽는다 (파일이 바뀌었을 때만) */
    private static void readSeojang() {
        if (seojangFile == null || !Files.isRegularFile(seojangFile)) {
            return;
        }
        try {
            long stamp = Files.getLastModifiedTime(seojangFile).toMillis();
            if (stamp == seojangStamp) {
                return;
            }
            seojangStamp = stamp;
            ingestSeojang(Files.readString(seojangFile, StandardCharsets.UTF_8), stamp);
        } catch (IOException | RuntimeException e) {
            log.warning("서장의 책을 읽지 못했다: " + e.getMessage());
        }
    }

    /**
     * 서장 책 한 장을 들인다 — <b>파일과 스레드를 걷어 낸 자리</b> ({@code ingest} 와 같은 무늬).
     * 눈({@code SeojangGateSelfTest})이 이 문으로 들어온다.
     *
     * @param mtime 파일의 시각 — 봇이 {@code at} 을 안 실었을 때의 대타 (심장 소리의 정본은 봇의 {@code at})
     */
    static void ingestSeojang(String jsonText, long mtime) {
        try {
            Map<String, Object> root = map(Json.parse(jsonText));
            // ★ 깨진 책은 **버린다** — scenes 조차 없는 것은 빈 명단이 아니라 깨진 파일이다.
            //   (빈 명단도 봇은 "scenes": [] 를 싣는다.) 여기서 빈 명단으로 읽으면 서장 중인
            //   몸을 게이트가 놓아 버린다 — 눈(SeojangGateSelfTest)이 이것을 잡았다.
            if (!(root.get("scenes") instanceof List<?>)) {
                log.warning("서장의 책이 깨졌다 — 버린다 (마지막 성한 명단이 남는다)");
                return;
            }
            // ★ B-118 — 심장 소리. 명단이 비어도 봇은 이 파일을 계속 굽는다 (poll 주기) —
            //   이 시각이 낡으면 봇이 죽은 것이고, 그때 게이트는 사람을 붙들지 않는다.
            long at = millis(root.get("at"));
            List<SeojangScene> out = new ArrayList<>();
            Set<String> pending = new LinkedHashSet<>();
            for (Object o : list(root.get("scenes"))) {
                Map<String, Object> s = map(o);
                String uuid = String.valueOf(s.getOrDefault("mc_uuid", "")).strip();
                if (uuid.isEmpty()) {
                    continue;
                }
                List<String> choices = new ArrayList<>();
                for (Object c : list(s.get("choices"))) {
                    choices.add(String.valueOf(map(c).getOrDefault("label", "")));
                }
                Object narration = s.get("narration");
                try {
                    out.add(new SeojangScene(UUID.fromString(uuid),
                            String.valueOf(s.getOrDefault("character", "")),
                            num(s.get("scene"), 0), num(s.get("total"), 0),
                            String.valueOf(s.getOrDefault("title", "")),
                            narration == null ? null : String.valueOf(narration),
                            List.copyOf(choices),
                            s.get("token") == null ? null : String.valueOf(s.get("token")),
                            "쓰는_중".equals(String.valueOf(s.get("state"))),
                            Boolean.TRUE.equals(s.get("final")),
                            Boolean.TRUE.equals(s.get("fallback")),
                            s.get("ferry") == null ? null : String.valueOf(s.get("ferry"))));
                    pending.add(uuid);   // ★ B-118 — 명단에 있다 = 서장이 아직 끝나지 않았다 (쓰는_중 포함)
                } catch (IllegalArgumentException e) {
                    log.warning("서장 — 몸의 이름이 아니다: " + uuid);
                }
            }
            seojangPublishedAt = at > 0 ? at : mtime;
            seojangBodies = Set.copyOf(pending);
            for (Consumer<List<SeojangScene>> l : SEOJANG_LISTENERS) {
                l.accept(out);
            }
        } catch (RuntimeException e) {
            log.warning("서장의 책을 읽지 못했다: " + e.getMessage());
        }
    }

    /**
     * ★ B-118 — <b>이 몸의 서장이 아직 끝나지 않았는가.</b> 대기소({@link Antechamber})의 게이트가
     * 보는 눈이다. 판정은 「책이 지금 손에 있나」(토큰)가 아니라 <b>「서장이 끝났나」</b>(봇의 서장
     * 명단)다 — 붓(LLM)이 글을 짓는 동안에도 몸은 명단에 실려 있으므로 경주가 없다.
     *
     * <p>다리가 죽어 심장 소리({@code at})가 {@code seojang_stale_seconds} 보다 낡으면 <b>붙들지
     * 않는다</b> — 죽은 다리는 사람을 가두지 못한다 (antechamber.yml gate.bridge_down 과 같은 원칙).
     */
    public static boolean seojangHolds(UUID body) {
        return body != null && seojangHolds(seojangBodies.contains(body.toString()),
                seojangPublishedAt, System.currentTimeMillis(), seojangStaleSeconds * 1000L);
    }

    /** 순수 판정 — 하네스({@code SeojangGateSelfTest})가 이 문으로 들어온다 (그리는 코드와 같은 눈) */
    static boolean seojangHolds(boolean listed, long publishedAt, long now, long staleMillis) {
        return listed && publishedAt > 0 && now - publishedAt <= staleMillis;
    }

    /**
     * 저장소 루트 찾기 — 봇과 <b>같은 자리</b>를 봐야 다리가 이어진다.
     * 우선순위: 환경변수 HONCHEON_BRIDGE(그 자체가 다리 폴더의 부모) → config 폴더에서 거슬러 올라
     * config/ 와 .git 를 함께 지닌 디렉터리(저장소 루트) → 그래도 없으면 데이터 폴더 자신.
     * (MVT 서버 cwd 는 run/mvt 이고 데이터 폴더는 run/mvt/plugins/HoncheonMVT — 봇은 루트에서 돈다.)
     */
    private static Path repoRoot(Path configDir) {
        String env = System.getenv("HONCHEON_HOME");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        Path p = configDir.toAbsolutePath();
        for (int i = 0; i < 8 && p != null; i++) {
            if (Files.isDirectory(p.resolve("config")) && Files.isDirectory(p.resolve(".git"))) {
                return p;
            }
            p = p.getParent();
        }
        return configDir.toAbsolutePath().getParent() == null
                ? configDir.toAbsolutePath() : configDir.toAbsolutePath().getParent();
    }

    // ══════════════ 발신 — 한 줄이 한 사건 ══════════════

    /**
     * 사건 하나를 장부로 보낸다. <b>절대 막히지 않는다</b> — 큐에 넣고 즉시 돌아온다
     * (디스크 쓰기는 데몬 스레드가 한다). 메인 스레드에서 불러도 안전하다.
     *
     * <p>등록되지 않은 kind 는 버린다 (경고 1회) — config 가 단일 진실 원천이라는 말의 집행이다.
     */
    public static void emit(String kind, Map<String, Object> data) {
        if (bridgeDir == null) {
            return;   // 다리가 서지 않았다 — 세계는 반쪽으로 돈다 (서버는 죽지 않는다)
        }
        if (!KINDS.contains(kind)) {
            log.warning("미등록 이벤트: " + kind + " — config/world_bridge.yml events 에 없다 (버린다)");
            return;
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", UUID.randomUUID().toString());
        envelope.put("kind", kind);
        envelope.put("at", System.currentTimeMillis());
        envelope.put("source", "mvt");
        envelope.put("data", data == null ? Map.of() : data);
        QUEUE.add(json(envelope));
        if (worker == null) {
            flush();   // 티커가 없는 환경(검증·헤드리스) — 그 자리에서 쓴다
        }
    }

    /**
     * 같은 사건을 방금 실었는가 — 중복 발신 차단 (events.&lt;kind&gt;.effects.dedupe_seconds).
     * 다리에 실리지 않은 것은 장부에도 없다. 그러니 거르는 것은 여기가 마지막 자리다.
     */
    private static synchronized boolean fresh(String key) {
        long now = System.currentTimeMillis();
        Long last = RECENT.get(key);
        if (last != null && now - last < dedupeMillis) {
            return false;
        }
        if (RECENT.size() > 256) {
            RECENT.entrySet().removeIf(e -> now - e.getValue() > dedupeMillis);
        }
        RECENT.put(key, now);
        return true;
    }

    // ─── 사건별 손잡이 (부르는 쪽이 키 이름을 외우지 않게) ───

    /**
     * 사람이 죽었다. registry 는 "populace"(무명) 또는 "cheongha_npcs"(등록 NPC) —
     * 연쇄의 깊이가 다르다 (무명에게는 후계도 복수자도 없다. 남는 것은 민심의 냉기뿐).
     */
    public static void npcDeath(String registry, String npcId, String npcName, String job,
                                String place, int witnesses, boolean night, String body,
                                String cause, UUID killerUuid, String killerName) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("registry", registry);
        data.put("npc_id", npcId);
        data.put("npc_name", npcName);
        data.put("job", job);
        data.put("place", place);
        data.put("witnesses", witnesses);
        data.put("night", night);
        data.put("body", body);
        data.put("cause", cause(cause));
        if (killerUuid != null) {
            data.put("killer_uuid", killerUuid.toString());
        }
        if (killerName != null) {
            data.put("killer_name", killerName);
        }
        emit("npc_death", data);
    }

    /**
     * 사인(死因)을 등록부의 낱말로 되돌린다 — 봇은 npc_death.yml 의 어휘만 안다
     * ({@code 노환_병사 · 사고 · 사건_피살 · 자멸_심마}). 부르는 쪽이 "플레이어_살해"라고 말해도
     * 장부에는 <b>사건_피살</b>로 적힌다. 그러지 않으면 사인 보정(cause_modifier)이 조용히 0이 되고,
     * 아무도 그것을 모른다 — <b>등록부에 없는 낱말은 세계에 존재하지 않는다.</b>
     */
    private static String cause(String cause) {
        if (cause == null || cause.isBlank()) {
            return "사건_피살";
        }
        return switch (cause) {
            case "플레이어_살해", "피살", "살해" -> "사건_피살";
            default -> cause;
        };
    }

    /** 도적을 베었다 — 명분 있는 죽음. 길이 그만큼 안전해진다 (region 치안 +) */
    public static void banditSlain(String foeId, String foeName, String role, String realm,
                                   String place, int witnesses, UUID killerUuid, String killerName) {
        emit("bandit_slain", slain(foeId, foeName, realm, place, witnesses, killerUuid, killerName,
                "role", role));
    }

    /** 짐승을 잡았다 — 생계와 수련. 늑대 한 마리는 소문이 되지 못한다 (영물만 사람들이 말한다) */
    public static void beastSlain(String foeId, String foeName, String realm, String place,
                                  int witnesses, UUID killerUuid, String killerName) {
        emit("beast_slain", slain(foeId, foeName, realm, place, witnesses, killerUuid, killerName));
    }

    private static Map<String, Object> slain(String foeId, String foeName, String realm, String place,
                                             int witnesses, UUID killerUuid, String killerName,
                                             String... extra) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("foe_id", foeId);
        data.put("foe_name", foeName);
        data.put("realm", realm);
        data.put("place", place);
        data.put("witnesses", witnesses);
        if (killerUuid != null) {
            data.put("killer_uuid", killerUuid.toString());
        }
        if (killerName != null) {
            data.put("killer_name", killerName);
        }
        for (int i = 0; i + 1 < extra.length; i += 2) {
            data.put(extra[i], extra[i + 1]);
        }
        return data;
    }

    /**
     * 격이 터졌다 — <b>소문의 씨앗</b>. "그자가 검기를 뿜었다더라."
     *
     * <p>한 전투에서 검기를 열 번 뿜어도 <b>소문은 하나다</b>. 매 타격을 다리에 실으면 장부가 검기로
     * 덮인다 — 같은 자의 같은 격은 dedupe_seconds(기본 10분) 안에 한 번만 발신한다 (등록부가 정한다).
     * 아무도 없는 산속의 검기는 애초에 소문이 되지 않는다 (min_witnesses — 그것은 봇이 거른다).
     */
    public static void qiManifested(String grade, String place, int witnesses,
                                    UUID playerUuid, String playerName) {
        if (!fresh("qi:" + playerUuid + ":" + grade)) {
            return;   // 방금 같은 격을 실었다 — 한 합 한 합이 다 소문이 될 수는 없다
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("grade", grade);
        data.put("place", place);
        data.put("witnesses", witnesses);
        if (playerUuid != null) {
            data.put("player_uuid", playerUuid.toString());
        }
        data.put("player_name", playerName);
        emit("qi_manifested", data);
    }

    // ═══════════ 신원 접합 — ★ **디스코드가 청하고, 이 몸이 수락한다** ═══════════
    //
    // 【코드는 죽었다】 옛 길은 마크가 6자를 내고 사람이 그것을 날라 디스코드에 붙여넣는 것이었다.
    //   사용자의 판정: "코드 복사는 없어져도 된다." 그래서 requestLink 는 **삭제됐다** (이 자리에 있었다).
    //
    // 【남은 것은 두 손】 ① 디스코드가 청한다 (그쪽이 신원을 서명한다)
    //                    ② **이 화면의 손이 [잇는다] 를 누른다** ← 여기가 이 파일의 몫이다
    //
    // 【★ 이 파일이 지키는 자물쇠 — 단 한 줄이다】 {@link #linkDecision} 은 **요청에 적힌 그 몸(uuid)** 만
    //   수락할 수 있게 한다. 토큰이 새어도, 남이 그 명령을 쳐도, 몸이 다르면 **아무 일도 일어나지 않는다.**
    //   (그리고 봇이 다리 건너에서 **한 번 더** 같은 대조를 한다 — Bridge.linkConfirm. 다리는 파일이므로.)

    /** 청의 수명 (초) — 안내 문구용. 진짜 만료 판정은 **봇의 장부**가 한다 (정본은 하나다) */
    private static int linkTtlSeconds = 120;

    /** 지금 이 서버에 내려온 청들 (token → 청). 봇이 link_requests.json 으로 내려보낸 것 */
    private static final Map<String, Request> REQUESTS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<Consumer<Request>> ASK_LISTENERS =
            new CopyOnWriteArrayList<>();
    /** 이미 화면에 띄운 청 — 2초마다 같은 물음을 다시 던지지 않는다 */
    private static final Set<String> ASKED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static Path linkRequestsFile;
    private static Path rosterFile;
    private static int linkPollSeconds = 2;
    private static int rosterSeconds = 5;
    private static volatile long requestsStamp;
    private static volatile Map<String, String> roster = Map.of();   // mc_name → uuid (주 스레드가 채운다)
    private static volatile long rosterWritten;

    /** 디스코드에서 온 청 하나 — <b>아직 아무것도 이어지지 않았다</b> */
    public record Request(String token, UUID body, String mcName, String discordName,
                          String character, long expiresAt) {
    }

    public static int linkTtlSeconds() {
        return linkTtlSeconds;
    }

    /** 이 몸에게 지금 살아 있는 청 (없으면 null) — 화면에 다시 띄우거나 초기화할 때 본다 */
    public static Request pendingFor(UUID player) {
        long now = System.currentTimeMillis();
        for (Request r : REQUESTS.values()) {
            if (r.body().equals(player) && r.expiresAt() > now) {
                return r;
            }
        }
        return null;
    }

    /**
     * 청이 내려왔다 — <b>그 몸의 화면에 물음을 띄우는 손</b>을 등록한다 (배선은 HoncheonMvt 가 한다).
     * 워커 스레드에서 불리므로 <b>받는 쪽이 메인 스레드로 넘겨야 한다</b> (Bukkit API 는 그렇게 산다).
     */
    public static void onLinkRequest(Consumer<Request> listener) {
        ASK_LISTENERS.add(listener);
    }

    /**
     * ★★ <b>이 몸이 답한다 — 접합의 유일한 결속 신호.</b>
     *
     * <p><b>자물쇠는 여기 한 줄이다:</b> {@code req.body().equals(player)}. 청은 <b>그 몸</b>에게 간 것이고,
     * <b>그 몸만</b> 답할 수 있다. 토큰을 어깨너머로 본 자가 제 화면에서 같은 명령을 쳐도 — 몸이 다르므로
     * 아무 일도 일어나지 않는다. 토큰은 <b>열쇠가 아니라 지목</b>이다.
     *
     * @param player 이 명령을 실제로 친 몸 (Bukkit 이 서명한다 — 위조 불가)
     * @return 사람에게 보여 줄 결과 (GONE · NOT_YOURS · EXPIRED · OK)
     */
    public static Decision linkDecision(UUID player, String token, boolean accept, String name) {
        Request req = token == null ? null : REQUESTS.get(token.strip().toUpperCase(Locale.ROOT));
        if (req == null) {
            return Decision.GONE;
        }
        if (!req.body().equals(player)) {
            // ★ 남의 청 — 여기서 죽는다. (봇도 다리 건너에서 같은 대조를 한 번 더 한다)
            log.warning("접합 — 남의 청에 답하려 했다: 청은 " + req.mcName() + " 에게 갔는데 "
                    + name + " 이(가) 눌렀다");
            return Decision.NOT_YOURS;
        }
        if (req.expiresAt() <= System.currentTimeMillis()) {
            REQUESTS.remove(req.token());
            return Decision.EXPIRED;
        }
        REQUESTS.remove(req.token());
        ASKED.remove(req.token());
        emitDecision(req.token(), player, name, accept ? "수락" : "거절");
        return accept ? Decision.ACCEPTED : Decision.REJECTED;
    }

    public enum Decision { GONE, NOT_YOURS, EXPIRED, ACCEPTED, REJECTED }

    /**
     * <b>초기화</b> — {@code /혼천 접속} 을 다시 불렀다 (발판이든 손이든).
     *
     * <p>사용자의 말: <i>"발판 밟을 때마다 코드 초기화를 시켜야 할 듯."</i> 코드는 없어졌지만 원리는 남는다 —
     * <b>다시 부르면 낡은 청은 죽는다.</b> 사람이 발판을 다시 밟는 것은 "처음부터 다시 하겠다"는 뜻이고,
     * 그때 낡은 청이 살아 있으면 ① 죽은 줄 알았던 창을 나중에 실수로 수락하거나 ② 두 청이 경쟁한다.
     *
     * <p>토큰을 안 싣는다 — <b>몸으로</b> 지운다. 그래야 이 서버가 아직 못 본 청(방금 앉은 것)까지 쓸린다.
     */
    public static void linkReset(UUID player, String name) {
        REQUESTS.values().removeIf(r -> {
            if (r.body().equals(player)) {
                ASKED.remove(r.token());
                return true;
            }
            return false;
        });
        emitDecision("", player, name, "취소");
    }

    private static void emitDecision(String token, UUID player, String name, String decision) {
        if (bridgeDir == null || !KINDS.contains("link_confirm")) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("mc_uuid", player.toString());
        data.put("mc_name", name);
        data.put("decision", decision);
        emit("link_confirm", data);
        flush();   // ★ 사람이 화면 앞에서 기다린다 — 워커의 다음 바퀴(0.5초)를 기다리지 않는다
    }

    /**
     * <b>명부(名簿)</b> — 지금 이 서버에 누가 있는가. 주 스레드가 채우고, 워커가 파일로 찍는다.
     *
     * <p>봇은 이것으로 <b>단 하나</b>를 판정한다: "그 이름이 지금 강호에 있는가." 없으면 청 자체가 서지 않는다
     * (물어볼 화면이 없으니까). 좌표도 시트도 담지 않는다 — <b>접속 여부만</b>이다.
     */
    public static void setRoster(Map<String, String> nameToUuid) {
        roster = Map.copyOf(nameToUuid);
    }

    // ─── 접속의 문 — 마크가 클릭에 걸 것들 (등록부 + 스냅숏) ───

    /** 채팅 문장 등록부 (identity.gate.mc_chat) — 없으면 fallback. 코드가 세계의 말을 짓지 않는다 */
    private static final Map<String, String> GATE = new LinkedHashMap<>();

    /** 접속 채널의 URL — <b>봇이 내려보낸 것</b> (스냅숏 discord.url). 문이 안 섰으면 null */
    private static volatile String discordUrl;

    /**
     * <b>초대 링크</b> — 봇이 내려보낸 것 (스냅숏 {@code discord.invite} ← {@code identity.gate.invite_url}).
     * 관리자가 등록부에 넣지 않았으면 <b>null</b> — 그때 마크는 초대 버튼을 띄우지 않는다.
     */
    private static volatile String discordInvite;

    public static String gateText(String key, String fallback) {
        String v = GATE.get(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    /**
     * <b>디스코드 접속 채널의 주소.</b> 스냅숏이 실어 온다 — 여기서 짓지 않는다.
     *
     * <p>null 이면 문이 아직 안 섰다는 뜻이다 (디스코드에서 관리자가 {@code /혼천 접합문} 을 쳐야 한다).
     * 그때 마크는 <b>[코드 복사]만</b> 띄운다 — 없는 문을 가리키는 클릭은 없는 것만 못하다.
     */
    public static String discordUrl() {
        return discordUrl;
    }

    /**
     * <b>혼천 디스코드로 들어오는 초대.</b> 스냅숏이 실어 온다 — 여기서 짓지 않는다.
     *
     * <p><b>왜 채널 URL 로 부족한가:</b> 이 디스코드는 <b>공개가 아니다.</b> {@link #discordUrl()} 은
     * <b>이미 서버 안에 있는 사람</b>에게만 쓸모가 있다 — 밖에 있는 사람이 그것을 눌러 봐야 아무 데도 못 간다.
     *
     * <p>null 이면 관리자가 아직 {@code config/world_bridge.yml identity.gate.invite_url} 을 채우지
     * 않았다는 뜻이다 (봇이 뜰 때 콘솔에 소리내어 경고한다). 그때 마크는 초대 버튼을 <b>띄우지 않는다</b>.
     */
    public static String discordInvite() {
        return discordInvite;
    }

    /** 지금 이 몸은 누구인가 — 접합됐으면 장부의 이름, 아니면 null (되먹임 스냅숏이 정본이다) */
    public static String linkedName(UUID player) {
        return current.linkedName(player);
    }

    /**
     * <b>몸에서 쌓인 것을 장부로 올린다</b> — {@code cultivation_logged}.
     *
     * <p>이것이 <b>정본의 방향</b>이다. 시트를 적는 손은 <b>봇 하나뿐</b>이고, 마크는 계산기다:
     * 규칙({@link Growth})은 마크에 한 벌만 있고, 그 결과인 <b>증분</b>만 다리를 건넌다. 봇은 그것을
     * 시트에 더하고 경지 캡으로 조인 뒤({@code player_creation.yml attribute_cap_by_realm})
     * {@code promoteIfDue} 를 굴리고, 확정된 시트를 스냅숏으로 되내려보낸다.
     *
     * <p>왜 반대가 아닌가 — 봇이 규칙을 다시 구현하면 <b>규칙의 정본이 둘</b>이 되고, 마크가 시트를
     * 쓰면 <b>장부의 정본이 둘</b>이 된다. 이 프로젝트가 반복해서 데인 병이 그것이다.
     * 그래서 <b>규칙은 마크에 하나 · 장부는 봇에 하나</b>로 잘라 두었다.
     *
     * <p>유실·중복 걱정은 없다: 큐는 append-only 디스크이고(봇이 꺼져 있으면 쌓였다가 켤 때 따라잡는다)
     * 봇의 {@code bridge_inbox}(PK=event_id)가 재생을 무해하게 만든다 — <b>정확히 한 번</b> 적용된다.
     */
    public static void cultivationLogged(UUID player, String name, String realm,
                                         PlayerLedger.Pending pending) {
        if (player == null || pending == null || !KINDS.contains("cultivation_logged")) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("player_uuid", player.toString());
        data.put("player_name", name);
        data.put("realm", realm);
        if (!pending.attrs().isEmpty()) {
            data.put("attr_days", new LinkedHashMap<>(pending.attrs()));
        }
        if (!pending.skills().isEmpty()) {
            data.put("skill_days", new LinkedHashMap<>(pending.skills()));
        }
        if (pending.naegong() > 0) {
            data.put("naegong", pending.naegong());
        }
        if (pending.trainDays() > 0) {
            data.put("train_days", pending.trainDays());
        }
        if (pending.marks실전() > 0) {
            data.put("marks_실전", pending.marks실전());
        }
        if (pending.marks사선() > 0) {
            data.put("marks_사선", pending.marks사선());
        }
        emit("cultivation_logged", data);
    }

    /** 비무 — 죽이지 않는 싸움. 장터에서 하면 이야깃거리가 된다 */
    public static void sparring(UUID winnerUuid, String winnerName, UUID loserUuid, String loserName,
                                String reason, String place, int witnesses) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (winnerUuid != null) {
            data.put("winner_uuid", winnerUuid.toString());
        }
        data.put("winner_name", winnerName);
        if (loserUuid != null) {
            data.put("loser_uuid", loserUuid.toString());
        }
        data.put("loser_name", loserName);
        data.put("reason", reason);
        data.put("place", place);
        data.put("witnesses", witnesses);
        emit("sparring", data);
    }

    // ══════════════ 되먹임 — 장부가 몸에게 (스냅숏: 최신이 이긴다) ══════════════

    /**
     * 지금 세계의 상태 — 봇이 20초마다 갈아 끼운다.
     * 사실(사건)은 큐로 흐르고 <b>상태는 스냅숏으로 흐른다</b>: 어제의 소문판을 다시 트는 것은
     * 세계가 아니라 녹음이다. 그래서 되먹임에는 재생도, 유실 걱정도 없다.
     *
     * @param day       봇의 세계일 (장부의 시간 — 마크의 낮밤과 별개)
     * @param tags      살아 있는 소문 태그 (도적·치안·질병 …)
     * @param reactions populace.yml reactions 키로 옮긴 것 (도적_소문 …) — Populace.rumor 의 입력
     * @param region    치안·경제·민심
     * @param wanted    mc_uuid → 법명분 게이지 (관졸이 적대할 값)
     * @param favor     mc_uuid → (세력 → 우호)
     */
    // ══════════════ ★★ 묘비(墓碑) — 지운 사람은 되살아나지 않는다 ══════════════
    //
    // 【겪은 일 · 2026-07-14 01:46】 사용자가 디스코드에서 「전부」 초기화를 쳤다. 봇은 제 장부를 지웠고
    //   (mvt_link·characters 에서 사라진 것을 확인했다), 마크는 playerdata 를 지웠고 ledgers.yml 의
    //   그 절(節)도 지웠다. 로그는 "지웠다"고 적었고 — **실제로 지워져 있었다.**
    //
    //   그런데 3분 뒤 재접속하자 그 사람은 **나루가 아니라 청하현에 떨어졌고**, ledgers.yml 에는
    //   `linked: true` 인 그의 절이 **되살아나 있었다** (money 228 · 흥정 90 — 지우기 전 그대로).
    //
    // 【범인】 **이 스냅숏 파일이다.** `run/bridge/world_state.json` 은 봇이 굽는 캐시인데,
    //   그때 그 파일은 **01:43 에 구워진 것**이었다 (초기화보다 3분 **먼저**). 그 안에는 지워진 사람의
    //   `sheet` 와 `links` 가 그대로 살아 있었다. 재접속 → `SkillListener.syncSheet` 가 그 낡은 시트를
    //   읽어 `applySheet` → **linked = true** → 5분 타이머(`saveLedgers`)가 그것을 파일에 구웠다.
    //   `Antechamber.onJoin`(MONITOR) 은 그 되살아난 `linked()` 를 보고 "강호에 든 자"로 판정했다.
    //
    //   ★ **마크가 지운 것을, 마크가 캐시에서 되살렸다.** 봇은 죄가 없다 (봇의 장부는 깨끗했다).
    //
    // 【고침 — 시간을 본다】 스냅숏에는 `generated_at` 이 실려 있다. 초기화는 여기에 **묘비**를 세운다:
    //   *"이 몸은 T 에 지워졌다."* 그 뒤로는 **T 보다 낡은 스냅숏의 시트·접합은 없는 것으로 읽는다.**
    //   봇이 새 스냅숏을 구우면(= generated_at > T) 묘비는 스스로 물러난다 — 그때는 **봇이 정본이다.**
    //
    //   ★ 이것은 영구 추방이 아니다. 같은 몸이 새 캐릭터로 다시 접합하면 새 스냅숏이 그것을 싣고,
    //     묘비는 그 순간 걷힌다 ({@link #sweepGraves}). **정본은 언제나 봇이다.**

    /** mc_uuid → 지워진 시각(ms). 이보다 낡은 스냅숏은 이 몸에 대해 <b>거짓말</b>이다 */
    private static final Map<String, Long> GRAVES = new java.util.concurrent.ConcurrentHashMap<>();

    /** 지금 읽고 있는 스냅숏이 <b>언제 구워졌는가</b> (봇의 generated_at · 없으면 파일 시각) */
    private static volatile long snapshotGeneratedAt;

    /** 묘비를 적어 두는 곳 — 재기동을 넘어 살아남는다 (봇이 새로 굽기 전에 서버가 내려갈 수 있다) */
    private static Path gravesFile;

    /**
     * 묘비 파일을 정하고 <b>읽어 들인다</b> — {@link HoncheonMvt} 가 데이터 폴더를 알려 준다.
     * 파일이 없으면 없는 대로 (묘비가 없는 것은 정상이다).
     */
    public static void gravesStore(Path file) {
        gravesFile = file;
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            map(Json.parse(Files.readString(file, StandardCharsets.UTF_8))).forEach((who, at) -> {
                long ms = millis(at);
                if (ms > 0) {
                    GRAVES.put(who, ms);
                }
            });
            log.info("묘비 " + GRAVES.size() + "기 — 지운 몸은 낡은 스냅숏으로 되살아나지 않는다");
        } catch (IOException | RuntimeException e) {
            log.warning("묘비를 못 읽었다 (지운 몸이 낡은 스냅숏으로 되살아날 수 있다): " + e.getMessage());
        }
    }

    /**
     * <b>묘비를 세운다</b> — 초기화가 이 몸을 지웠다 ({@link Reset#wipe}).
     *
     * <p>이 순간부터, <b>{@code at} 보다 낡은 스냅숏</b>이 이 몸에 대해 무슨 말을 하든 마크는 듣지 않는다.
     * 되살아나는 길(낡은 캐시 → syncSheet → linked → saveLedgers)이 여기서 끊긴다.
     */
    public static void forget(UUID player, long at) {
        if (player == null) {
            return;
        }
        GRAVES.put(player.toString(), at);
        // ★ 지금 손에 든 스냅숏이 낡았으면, 그 안의 이 몸은 **이미 죽은 말**이다. 그 사실을 즉시 적용한다
        //   (다음 스냅숏을 기다리지 않는다 — 그 사이에 재접속하면 그때 되살아난다).
        saveGraves();
        log.info("묘비 — " + player + " 는 " + at + " 에 지워졌다. 그보다 낡은 스냅숏은 믿지 않는다");
    }

    /**
     * 이 몸은 <b>지금 읽고 있는 스냅숏보다 나중에 지워졌는가</b> — 그렇다면 그 스냅숏은 거짓말이다.
     *
     * <p>봇이 새 스냅숏을 구우면 {@code snapshotGeneratedAt > 묘비} 가 되어 <b>저절로 false</b> 가 된다.
     */
    public static boolean forgotten(UUID player) {
        if (player == null) {
            return false;
        }
        Long at = GRAVES.get(player.toString());
        return at != null && snapshotGeneratedAt <= at;
    }

    /** 새 스냅숏이 왔다 — 그보다 낡은 묘비는 할 일을 다했다 (봇이 정본이다) */
    private static void sweepGraves() {
        if (GRAVES.isEmpty()) {
            return;
        }
        long gen = snapshotGeneratedAt;
        if (GRAVES.values().removeIf(at -> at < gen)) {
            saveGraves();
        }
    }

    private static void saveGraves() {
        if (gravesFile == null) {
            return;
        }
        try {
            Files.createDirectories(gravesFile.getParent());
            Files.writeString(gravesFile, json(new LinkedHashMap<String, Object>(GRAVES)),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warning("묘비를 못 적었다 (재기동하면 지운 몸이 되살아날 수 있다): " + e.getMessage());
        }
    }

    /** 눈이 쓰는 문 — 스냅숏이 구워진 시각 */
    static long snapshotGeneratedAt() {
        return snapshotGeneratedAt;
    }

    /** ★ 눈이 <b>일부러 어길</b> 때 쓰는 문 — 묘비를 걷어 낸다 (그러면 죽은 자가 걸어 나온다) */
    static void razeGraves() {
        GRAVES.clear();
    }

    private static long millis(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    public record State(int day, Set<String> tags, Set<String> reactions, Map<String, Integer> region,
                        Map<String, Integer> wanted, Map<String, Map<String, Integer>> favor,
                        Map<String, String> links, Map<String, Integer> bounty, int wantedMin,
                        Map<String, Sheet> sheets) {

        static State empty() {
            return new State(0, Set.of(), Set.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                    Map.of(), 8, Map.of());
        }

        /**
         * 이 몸에 실린 시트 — 봇의 확정값. 접합되지 않았으면 null (강호에 없는 사람이다).
         *
         * <p>★ <b>묘비를 먼저 본다.</b> 지운 뒤에 구워진 스냅숏이 아니면, 그 안의 시트는 <b>유령</b>이다.
         */
        public Sheet sheet(UUID player) {
            if (player == null || forgotten(player)) {
                return null;
            }
            return sheets.get(player.toString());
        }

        /** 접합된 몸인가 — 그의 장부 이름 (아니면 null. 이름 없는 자의 일은 세계의 배경음이다) */
        public String linkedName(UUID player) {
            if (player == null || forgotten(player)) {
                return null;   // ★ 지운 사람이다 — 낡은 스냅숏이 뭐라 하든
            }
            return links.get(player.toString());
        }

        public boolean linked(UUID player) {
            return linkedName(player) != null;
        }

        /**
         * 그의 목에 걸린 값 — 0 이면 아직 방이 안 붙었다 (혈채 3+ 에서 관이 붙인다).
         * ★ 혈채 수치는 여기 없다 (blood_debt.visibility: 내부). 내려오는 것은 <b>세계의 반응</b>뿐이다.
         */
        public int bounty(UUID player) {
            Integer v = player == null ? null : bounty.get(player.toString());
            return v == null ? 0 : v;
        }

        /** 관에 쫓기는가 — 마을 관졸이 적대할 문턱 (world_bridge.yml feedback.wanted.gauge_min) */
        public boolean wanted(UUID player) {
            return mandate(player) >= wantedMin;
        }

        public int mandate(UUID player) {
            Integer g = wanted.get(player.toString());
            return g == null ? 0 : g;
        }

        /** 세력 우호 — 산문의 문이 열리고 닫힌다 (없으면 0 = 무관심) */
        public int favor(UUID player, String faction) {
            Map<String, Integer> mine = favor.get(player.toString());
            Integer v = mine == null ? null : mine.get(faction);
            return v == null ? 0 : v;
        }
    }

    /**
     * 봇이 내려보낸 시트 한 장 — {@code world_state.json} 의 {@code sheet.<mc_uuid>} 블록.
     *
     * <p><b>이것이 캐릭터다.</b> 마크에는 캐릭터 시트가 없었다 — {@code SkillEngine.State.realm} 은
     * {@code "이류"} 로 <b>박혀 있었고</b>(<i>"MVT 는 캐릭터 시트가 없다"</i>) 능력치는 전부 0.0 이었다.
     * 그래서 갓 접속한 플레이어는 {@code Growth.attackBonus} = +0 인데 같은 경지의 산적은
     * {@code realmAttr} 로 표준치를 받았다 — <b>플레이어가 제 급의 적보다 구조적으로 약했다.</b>
     *
     * <p>음수·null 은 <b>"봇이 모르는 값"</b>이다 (덮어쓰지 않는다). {@code simbeop} 만은 null 도
     * 진실이다 — 개화 전이면 개화 전인 것이고, 그것이 내공 과목의 게이트다.
     */
    /**
     * 봇이 내려보낸 시트 한 장.
     *
     * <p>★ <b>house · startAnchor · kin 은 2026-07-13 에 붙었다</b> — 그전까지 마크는
     * <b>내가 누구의 자식인지 몰랐다</b> (realm·attrs·naegong 만 왔다). 그래서 무가의 자식과
     * 객잔집 자식이 <b>같은 자리에 내려 같은 대접을 받았다.</b>
     *
     * @param house       집안 (무가의_자식 · 객잔집_자식 …). null = 옛 캐릭터
     * @param startAnchor ★ <b>이 집안이 설 자리</b> — 마크의 <b>앵커 이름</b>(한글). 좌표는 마크가 안다
     * @param leftHouse   세가를 <b>거절했는가</b> (집을 나온 아이 — 검도 전표도 없다)
     * @param birthRank   ★ <b>적서</b> — 적자/서자. 같은 집인데 세상이 아는 무게가 다르다 (null = 없는 집)
     * @param houseName   ★ <b>가문의 이름</b> — 「청하현 이가(李家)」. 성은 무가 계열에만 붙는다
     * @param houseRegion ★ 가문이 사는 고을 — <b>시작 위치가 여기서 나온다</b> (지역 × 집안)
     * @param houseState  ★ 가문의 형태 (흥·쇠·멸) — <b>탄생에 고정</b>. 변하지 않는다
     * @param kin         ★ 같은 집에 태어난 아이들 — <b>태어난 순서대로</b> (형·누나·동생)
     */
    public record Sheet(String realm, Map<String, Double> attrs, double naegong, String simbeop,
                        String primaryArt, Map<String, Double> skillDays,
                        int marks실전, int marks사선, int money,
                        String house, String gender, String startAnchor, boolean leftHouse,
                        String birthRank, String houseName, String houseRegion, String houseState,
                        List<Kin> kin) {
    }

    /** 피붙이 하나 — <b>형·누나·동생</b> (문파의 사형·사저와 **다른 어휘**다. 축이 다르다: 태어난 순) */
    public record Kin(String name, boolean elder, String title) {
    }

    /** 지금 읽힌 세계 상태 (아직 스냅숏이 없으면 빈 상태 — 세계는 조용하다) */
    public static State state() {
        return current;
    }

    /**
     * <b>세계일 — 하나뿐인 달력.</b> 봇이 굴리고(자정 스케줄러: 현실 하루 = 세계 1일) 마크가 읽는다.
     *
     * <p>0 이면 <b>스냅숏이 아직 없다</b> = 봇이 꺼져 있거나 다리가 안 섰다. 그때 장부는 <b>움직이지 않는다</b> —
     * 장부는 봇의 것이므로, 봇이 죽어 있는 동안 화후가 쌓이면 그것은 장부가 아니라 위조다.
     *
     * <p>마크의 하루(현실 20분)를 여기 쓰지 말 것. {@code Growth.attrCostDays}(능력치 +1 = 360일)와
     * 봇의 승급 요건은 <b>같은 {@code days} 단위</b>를 쓴다 — 두 시계를 섞으면 72배가 벌어진다.
     * 그 대조를 {@code tools/clock_audit.py} 가 지킨다.
     */
    public static long worldDay() {
        return current.day();
    }

    /**
     * 상태가 바뀔 때마다 부른다 — <b>워커 스레드에서</b> 부른다.
     * 세계를 건드릴 거라면 배선 쪽에서 스케줄러로 메인 스레드에 태워라 (클래스 주석의 배선 예시).
     */
    public static void onState(Consumer<State> listener) {
        LISTENERS.add(listener);
    }

    /** populace.yml reactions 의 키들 (도적_소문·열병_소문) — 켜고 끌 스위치의 전체 목록 */
    public static Set<String> reactionTags() {
        return java.util.Collections.unmodifiableSet(REACTION_MAP.keySet());
    }

    // ══════════════ 워커 — 쓰기 + 스냅숏 읽기 (데몬 스레드 1개) ══════════════

    public static void start() {
        if (bridgeDir == null || !RUNNING.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(() -> {
            long lastPoll = 0;
            long lastAsk = 0;
            long lastBook = 0;
            while (RUNNING.get()) {
                try {
                    flush();
                    long now = System.currentTimeMillis();
                    if (now - lastPoll >= pollSeconds * 1000L) {
                        lastPoll = now;
                        readSnapshot();
                    }
                    // ★ 접합은 사람이 **기다리는** 값이다 — 스냅숏(20초)과 다른 시계로 돈다 (2초)
                    if (now - lastAsk >= linkPollSeconds * 1000L) {
                        lastAsk = now;
                        readLinkRequests();
                    }
                    // ★ 서장도 사람이 **책을 펴 놓고** 기다리는 값이다 — 같은 시계 (2초)
                    if (now - lastBook >= seojangPollSeconds * 1000L) {
                        lastBook = now;
                        readSeojang();
                    }
                    if (now - rosterWritten >= rosterSeconds * 1000L) {
                        writeRoster(now);
                    }
                    Thread.sleep(500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException e) {
                    log.warning("세계 다리 워커: " + e.getMessage());
                }
            }
        }, "honcheon-bridge");
        worker.setDaemon(true);
        worker.start();
    }

    /** 남은 것을 마저 쓰고 멈춘다 — onDisable 에서 부른다 (사건은 하나도 버리지 않는다) */
    public static void stop() {
        RUNNING.set(false);
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        flush();
    }

    /** 큐를 디스크로 — 조각 파일은 날짜별. 한 번의 write 로 줄 단위 원자성을 얻는다 (O_APPEND) */
    private static synchronized void flush() {
        if (bridgeDir == null || QUEUE.isEmpty()) {
            return;
        }
        StringBuilder batch = new StringBuilder();
        for (String line = QUEUE.poll(); line != null; line = QUEUE.poll()) {
            batch.append(line).append('\n');
        }
        if (batch.isEmpty()) {
            return;
        }
        Path segment = outboxDir.resolve(LocalDate.now(ZoneId.of("Asia/Seoul"))
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + ".jsonl");
        try {
            Files.writeString(segment, batch.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.severe("다리 발신 실패 — 사건이 장부에 닿지 못한다: " + e.getMessage());
        }
    }

    /**
     * <b>명부를 찍는다</b> — 지금 이 서버에 누가 있는가 (mc_name → uuid) + 찍은 시각.
     *
     * <p>봇은 이 파일로 <b>단 하나</b>를 판정한다: "그 이름이 지금 강호에 있는가."
     * {@code at} 이 낡으면 봇은 <b>마크가 꺼졌다</b>고 읽는다 (그래서 사람에게 다른 말을 해 준다) —
     * 그러므로 사람이 없어도 <b>계속 찍어야 한다.</b> 빈 명부와 낡은 명부는 다른 사실이다.
     *
     * <p>목록은 {@link #setRoster} 가 주 스레드에서 채운다 (Bukkit API 는 그 스레드에서만 산다).
     */
    private static void writeRoster(long now) {
        if (bridgeDir == null || rosterFile == null) {
            return;
        }
        rosterWritten = now;
        Map<String, Object> players = new LinkedHashMap<>();
        roster.forEach((name, uuid) -> players.put(name,
                new LinkedHashMap<>(Map.of("uuid", uuid, "name", name))));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("at", now);
        root.put("players", players);
        try {
            Files.createDirectories(bridgeDir);
            Path tmp = rosterFile.resolveSibling(rosterFile.getFileName() + ".tmp");
            Files.writeString(tmp, json(root), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, rosterFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warning("명부를 찍지 못했다 — 봇이 '마크가 꺼졌다'고 읽는다: " + e.getMessage());
        }
    }

    /**
     * <b>청을 읽는다</b> — 봇이 내려보낸 수락 요청 (link_requests.json).
     *
     * <p>새로 온 청은 <b>한 번만</b> 화면에 띄운다 ({@code ASKED} — 2초마다 같은 물음을 던지면 그것은 도배다).
     * 봇이 지운 청(수락·거절·만료·초기화)은 여기서도 사라진다 — <b>죽은 청의 [잇는다] 는 아무것도 하지 않는다</b>
     * (그리고 눌러도 봇이 다시 거른다. 자물쇠는 두 겹이다).
     */
    private static void readLinkRequests() {
        if (linkRequestsFile == null || !Files.isRegularFile(linkRequestsFile)) {
            return;
        }
        try {
            long stamp = Files.getLastModifiedTime(linkRequestsFile).toMillis();
            if (stamp == requestsStamp) {
                return;
            }
            requestsStamp = stamp;
            Map<String, Object> root = map(Json.parse(
                    Files.readString(linkRequestsFile, StandardCharsets.UTF_8)));
            Set<String> live = new LinkedHashSet<>();
            List<Request> fresh = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (Object o : list(root.get("requests"))) {
                Map<String, Object> r = map(o);
                String token = String.valueOf(r.getOrDefault("token", "")).strip();
                String uuid = String.valueOf(r.getOrDefault("mc_uuid", "")).strip();
                if (token.isEmpty() || uuid.isEmpty()) {
                    continue;
                }
                long expires = r.get("expires_at") instanceof Number n ? n.longValue() : 0L;
                if (expires <= now) {
                    continue;   // 죽은 청은 화면에 뜨지 않는다
                }
                UUID body;
                try {
                    body = UUID.fromString(uuid);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                Request req = new Request(token, body,
                        String.valueOf(r.getOrDefault("mc_name", "?")),
                        String.valueOf(r.getOrDefault("discord_name", "?")),
                        String.valueOf(r.getOrDefault("character", "?")), expires);
                live.add(token);
                if (REQUESTS.put(token, req) == null && ASKED.add(token)) {
                    fresh.add(req);   // 처음 보는 청 — 이 몸의 화면에 물음을 띄운다
                }
            }
            REQUESTS.keySet().retainAll(live);   // 봇이 지운 것은 여기서도 죽는다
            ASKED.retainAll(live);
            for (Request req : fresh) {
                for (Consumer<Request> listener : ASK_LISTENERS) {
                    try {
                        listener.accept(req);
                    } catch (RuntimeException e) {
                        log.warning("접합 청 전달 실패: " + e.getMessage());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warning("접합 청 판독 실패: " + e.getMessage());
        }
    }

    /** 스냅숏 판독 — 안 바뀌었으면 아무것도 하지 않는다 (mtime 비교) */
    private static void readSnapshot() {
        try {
            if (!Files.isRegularFile(snapshotFile)) {
                return;
            }
            long stamp = Files.getLastModifiedTime(snapshotFile).toMillis();
            if (stamp == snapshotStamp) {
                return;
            }
            snapshotStamp = stamp;
            ingest(Files.readString(snapshotFile, StandardCharsets.UTF_8), stamp);
        } catch (IOException | RuntimeException e) {
            log.warning("세계 상태 판독 실패: " + e.getMessage());
        }
    }

    /**
     * 스냅숏 한 장을 들인다 — <b>파일과 스레드를 걷어 낸 자리</b>. 눈({@code ResetTombstoneSelfTest})이
     * 이 문으로 들어온다: 실제 코드가 실제 JSON 을 읽는 그 길을 시험해야 시험이다.
     *
     * @param mtime 파일의 시각 — 봇이 {@code generated_at} 을 안 실었을 때의 대타
     */
    static void ingest(String jsonText, long mtime) {
        try {
            Map<String, Object> root = map(Json.parse(jsonText));
            // ★★ 스냅숏이 **언제 구워졌는가** — 이것이 없어서 지운 사람이 되살아났다 (§묘비).
            //   봇이 안 실어 주면 파일의 시각을 쓴다 (그것도 없으면 0 — 그때는 묘비가 계속 지킨다).
            long gen = millis(root.get("generated_at"));
            snapshotGeneratedAt = gen > 0 ? gen : mtime;

            Set<String> tags = new LinkedHashSet<>();
            for (Object t : list(root.get("rumor_tags"))) {
                tags.add(String.valueOf(t));
            }
            Set<String> reactions = new LinkedHashSet<>();
            for (Object t : list(root.get("populace_reactions"))) {
                reactions.add(String.valueOf(t));
            }
            if (reactions.isEmpty()) {
                // 봇이 안 옮겨 줬으면 여기서 옮긴다 (등록부는 양쪽이 같은 파일을 본다)
                REACTION_MAP.forEach((key, want) -> {
                    for (String w : want) {
                        if (tags.contains(w)) {
                            reactions.add(key);
                            return;
                        }
                    }
                });
            }
            Map<String, Integer> region = new LinkedHashMap<>();
            map(root.get("region")).forEach((k, v) -> region.put(k, num(v, 50)));
            Map<String, Integer> wanted = new LinkedHashMap<>();
            map(root.get("wanted")).forEach((k, v) -> wanted.put(k, num(v, 0)));
            Map<String, Map<String, Integer>> favor = new LinkedHashMap<>();
            map(root.get("favor")).forEach((who, table) -> {
                Map<String, Integer> mine = new LinkedHashMap<>();
                map(table).forEach((faction, v) -> mine.put(faction, num(v, 0)));
                favor.put(who, mine);
            });
            // 신원 — 접합된 몸만 여기 있다 (mc_uuid → 장부의 이름)
            Map<String, String> links = new LinkedHashMap<>();
            map(root.get("links")).forEach((who, info) ->
                    links.put(who, String.valueOf(map(info).getOrDefault("name", "?"))));
            Map<String, Integer> bounty = new LinkedHashMap<>();
            map(root.get("bounty")).forEach((who, v) -> bounty.put(who, num(v, 0)));
            int wantedMin = num(map(root.get("thresholds")).get("wanted"), 8);
            // ★ 접속의 문 — 봇이 제가 붙어 있는 채널로 만든 URL. 없으면 없는 것이다 (짓지 않는다)
            Object url = map(root.get("discord")).get("url");
            discordUrl = url == null || String.valueOf(url).isBlank() ? null : String.valueOf(url);
            // ★ 초대 — 서버 밖의 사람이 들어오는 유일한 길. 등록부가 비었으면 봇이 아예 안 싣는다
            Object inv = map(root.get("discord")).get("invite");
            discordInvite = inv == null || String.valueOf(inv).isBlank() ? null : String.valueOf(inv);
            // ★ 시트 — 몸에 실릴 캐릭터. 여기가 비면 마크는 다시 "경지 이류·능력치 0"의 세계가 된다
            Map<String, Sheet> sheets = new LinkedHashMap<>();
            map(root.get("sheet")).forEach((who, raw) -> sheets.put(who, sheet(map(raw))));

            State next = new State(num(root.get("world_day"), 0), tags, reactions, region,
                    wanted, favor, links, bounty, wantedMin, sheets);
            current = next;
            sweepGraves();   // ★ 이 스냅숏이 묘비보다 새것이면, 그 묘비는 할 일을 다했다
            for (Consumer<State> listener : LISTENERS) {
                try {
                    listener.accept(next);
                } catch (RuntimeException e) {
                    log.warning("되먹임 적용 실패: " + e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            log.warning("세계 상태 판독 실패: " + e.getMessage());
        }
    }

    // ══════════════ JSON — 외부 의존 없이 (페이로드는 평평하다) ══════════════

    static String json(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            quote(sb, s);
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else {
            quote(sb, String.valueOf(value));
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** 최소 JSON 판독기 — 스냅숏 하나 읽자고 의존성을 늘리지 않는다 */
    static final class Json {

        private final String src;
        private int pos;

        private Json(String src) {
            this.src = src;
        }

        static Object parse(String src) {
            Json p = new Json(src);
            p.ws();
            Object v = p.value();
            return v;
        }

        private Object value() {
            ws();
            if (pos >= src.length()) {
                return null;
            }
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> out = new LinkedHashMap<>();
            pos++;   // {
            ws();
            if (peek() == '}') {
                pos++;
                return out;
            }
            while (pos < src.length()) {
                ws();
                String key = string();
                ws();
                pos++;   // :
                out.put(key, value());
                ws();
                char c = peek();
                pos++;
                if (c == '}') {
                    return out;
                }
            }
            return out;
        }

        private List<Object> array() {
            List<Object> out = new ArrayList<>();
            pos++;   // [
            ws();
            if (peek() == ']') {
                pos++;
                return out;
            }
            while (pos < src.length()) {
                out.add(value());
                ws();
                char c = peek();
                pos++;
                if (c == ']') {
                    return out;
                }
            }
            return out;
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            pos++;   // "
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    break;
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char esc = src.charAt(pos++);
                switch (esc) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> sb.append(esc);
                }
            }
            return sb.toString();
        }

        private Object number() {
            int start = pos;
            while (pos < src.length() && "-+.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
                pos++;
            }
            String raw = src.substring(start, pos);
            try {
                return raw.contains(".") || raw.contains("e") || raw.contains("E")
                        ? (Object) Double.valueOf(raw) : (Object) Long.valueOf(raw);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private Object literal(String word, Object v) {
            pos += word.length();
            return v;
        }

        private char peek() {
            return pos < src.length() ? src.charAt(pos) : '\0';
        }

        private void ws() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }
    }

    // ─── 판독 도우미 ───

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    private static int num(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static double dec(Object value, double fallback) {
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 시트 한 장 판독 — 없는 칸은 "봇이 모르는 값"(음수·null)으로 남긴다. 몸의 것을 덮지 않는다 */
    private static Sheet sheet(Map<String, Object> raw) {
        Map<String, Double> attrs = new LinkedHashMap<>();
        map(raw.get("attrs")).forEach((k, v) -> attrs.put(k, dec(v, 0)));
        Map<String, Double> skills = new LinkedHashMap<>();
        map(raw.get("skill_days")).forEach((k, v) -> skills.put(k, dec(v, 0)));
        List<Kin> kin = new ArrayList<>();
        for (Object k : list(raw.get("kin"))) {
            Map<String, Object> m = map(k);
            kin.add(new Kin(text(m.get("name")), Boolean.TRUE.equals(m.get("elder")),
                    text(m.get("title"))));
        }
        return new Sheet(text(raw.get("realm")), attrs, dec(raw.get("naegong"), -1),
                text(raw.get("simbeop")), text(raw.get("primary_art")), skills,
                num(raw.get("marks_실전"), -1), num(raw.get("marks_사선"), -1),
                num(raw.get("money"), -1),
                text(raw.get("house")), text(raw.get("gender")), text(raw.get("start_anchor")),
                Boolean.TRUE.equals(raw.get("left_house")),
                text(raw.get("birth_rank")),   // ★ 적서 — 적자/서자 (null = 적서가 없는 집)
                // ★★ 가문 — **한 채의 집**. 「청하현 이가(李家)」 · 지역 · 형태(흥·쇠·멸)
                text(raw.get("house_name")), text(raw.get("house_region")), text(raw.get("house_state")),
                List.copyOf(kin));
    }
}
