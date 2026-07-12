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
        try {
            Files.createDirectories(outboxDir);
        } catch (IOException e) {
            log.warning("다리 디렉터리를 만들 수 없다: " + outboxDir + " — " + e.getMessage());
            bridgeDir = null;
            return;
        }
        log.info("세계 다리 — 발신 " + outboxDir + " · 수신(되먹임) " + snapshotFile
                + " · 등록 이벤트 " + KINDS.size() + "종 " + KINDS);
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
        data.put("cause", cause);
        if (killerUuid != null) {
            data.put("killer_uuid", killerUuid.toString());
        }
        if (killerName != null) {
            data.put("killer_name", killerName);
        }
        emit("npc_death", data);
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
    public record State(int day, Set<String> tags, Set<String> reactions, Map<String, Integer> region,
                        Map<String, Integer> wanted, Map<String, Map<String, Integer>> favor,
                        int wantedMin) {

        static State empty() {
            return new State(0, Set.of(), Set.of(), Map.of(), Map.of(), Map.of(), 8);
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

    /** 지금 읽힌 세계 상태 (아직 스냅숏이 없으면 빈 상태 — 세계는 조용하다) */
    public static State state() {
        return current;
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
            while (RUNNING.get()) {
                try {
                    flush();
                    long now = System.currentTimeMillis();
                    if (now - lastPoll >= pollSeconds * 1000L) {
                        lastPoll = now;
                        readSnapshot();
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
            Map<String, Object> root = map(Json.parse(
                    Files.readString(snapshotFile, StandardCharsets.UTF_8)));

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
            int wantedMin = num(map(root.get("thresholds")).get("wanted"), 8);

            State next = new State(num(root.get("world_day"), 0), tags, reactions, region,
                    wanted, favor, wantedMin);
            current = next;
            for (Consumer<State> listener : LISTENERS) {
                try {
                    listener.accept(next);
                } catch (RuntimeException e) {
                    log.warning("되먹임 적용 실패: " + e.getMessage());
                }
            }
        } catch (IOException | RuntimeException e) {
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
}
