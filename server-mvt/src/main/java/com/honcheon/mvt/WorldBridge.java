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

        // 신원 접합 — 코드의 알파벳·길이·수명 (등록부가 정한다. 0·O·1·I 가 없는 것은 옮겨 적는 값이기 때문)
        Map<String, Object> identity = map(root.get("identity"));
        codeAlphabet = String.valueOf(identity.getOrDefault("code_alphabet", codeAlphabet));
        codeLength = Math.max(4, num(identity.get("code_length"), codeLength));
        codeTtlSeconds = Math.max(30, num(identity.get("ttl_seconds"), codeTtlSeconds));

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

    // ─── 신원 접합 — 다리를 건너는 것은 사건이 아니라 사람이다 ───

    /** 접합 코드의 알파벳·길이·수명 (config/world_bridge.yml identity) — 여기서 발명하지 않는다 */
    private static String codeAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static int codeLength = 6;
    private static int codeTtlSeconds = 600;
    private static final java.security.SecureRandom RNG = new java.security.SecureRandom();

    /**
     * 접합 코드를 낸다 — <b>이 몸이 누구인지 봇에게 물어보는 손짓</b>. 여기서는 아직 아무것도 안 이어진다.
     *
     * <p><b>왜 마크가 코드를 내고 디스코드가 확정하는가.</b> 지켜야 할 것은 캐릭터(장부·혈채·세력)이고,
     * 훔칠 수 있는 것은 코드뿐이다. 최종 결속을 <b>인증된 자리</b>(디스코드)에 두면 남의 캐릭터를
     * 뺏으려면 남의 디스코드 계정이 필요해진다. 반대 방향(봇이 코드 발급 → 마크에서 입력)이면
     * 코드 한 줄이 곧 캐릭터의 열쇠가 된다 — 어깨너머로 본 사람이 남의 삶을 가져간다.
     *
     * <p>코드는 <b>부르는 쪽이 그 플레이어에게만</b> 보여 줘야 한다 (귓속말·액션바 — 공개 채팅 금지).
     *
     * @return 그 자리에서 보여 줄 코드 (다리가 안 섰으면 null — 세계의 절반이 꺼져 있다)
     */
    public static String requestLink(UUID player, String name) {
        if (bridgeDir == null || !KINDS.contains("link_request")) {
            return null;
        }
        StringBuilder sb = new StringBuilder(codeLength);
        for (int i = 0; i < codeLength; i++) {
            sb.append(codeAlphabet.charAt(RNG.nextInt(codeAlphabet.length())));
        }
        String code = sb.toString();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("mc_uuid", player.toString());
        data.put("mc_name", name);
        data.put("ttl_seconds", codeTtlSeconds);
        emit("link_request", data);
        return code;
    }

    /** 코드의 수명 (초) — 안내 문구에 쓴다 */
    public static int linkTtlSeconds() {
        return codeTtlSeconds;
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
    public record State(int day, Set<String> tags, Set<String> reactions, Map<String, Integer> region,
                        Map<String, Integer> wanted, Map<String, Map<String, Integer>> favor,
                        Map<String, String> links, Map<String, Integer> bounty, int wantedMin,
                        Map<String, Sheet> sheets) {

        static State empty() {
            return new State(0, Set.of(), Set.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                    Map.of(), 8, Map.of());
        }

        /** 이 몸에 실린 시트 — 봇의 확정값. 접합되지 않았으면 null (강호에 없는 사람이다) */
        public Sheet sheet(UUID player) {
            return player == null ? null : sheets.get(player.toString());
        }

        /** 접합된 몸인가 — 그의 장부 이름 (아니면 null. 이름 없는 자의 일은 세계의 배경음이다) */
        public String linkedName(UUID player) {
            return player == null ? null : links.get(player.toString());
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
    public record Sheet(String realm, Map<String, Double> attrs, double naegong, String simbeop,
                        String primaryArt, Map<String, Double> skillDays,
                        int marks실전, int marks사선, int money) {
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
            // 신원 — 접합된 몸만 여기 있다 (mc_uuid → 장부의 이름)
            Map<String, String> links = new LinkedHashMap<>();
            map(root.get("links")).forEach((who, info) ->
                    links.put(who, String.valueOf(map(info).getOrDefault("name", "?"))));
            Map<String, Integer> bounty = new LinkedHashMap<>();
            map(root.get("bounty")).forEach((who, v) -> bounty.put(who, num(v, 0)));
            int wantedMin = num(map(root.get("thresholds")).get("wanted"), 8);
            // ★ 시트 — 몸에 실릴 캐릭터. 여기가 비면 마크는 다시 "경지 이류·능력치 0"의 세계가 된다
            Map<String, Sheet> sheets = new LinkedHashMap<>();
            map(root.get("sheet")).forEach((who, raw) -> sheets.put(who, sheet(map(raw))));

            State next = new State(num(root.get("world_day"), 0), tags, reactions, region,
                    wanted, favor, links, bounty, wantedMin, sheets);
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
        return new Sheet(text(raw.get("realm")), attrs, dec(raw.get("naegong"), -1),
                text(raw.get("simbeop")), text(raw.get("primary_art")), skills,
                num(raw.get("marks_실전"), -1), num(raw.get("marks_사선"), -1),
                num(raw.get("money"), -1));
    }
}
