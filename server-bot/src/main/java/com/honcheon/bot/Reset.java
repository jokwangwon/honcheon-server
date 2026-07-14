package com.honcheon.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.honcheon.core.rules.RulesConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 초기화(初期化) — <b>되돌리는 손.</b> 시험할 때마다 사람이 파일을 손으로 지우던 일을 명령으로 만든다.
 *
 * <p><b>몸이 둘이므로 손도 둘이다.</b> 봇은 <b>제 장부(SQLite)만</b> 지운다. 마크의 몸(playerdata·
 * ledgers.yml·dojang.yml)은 <b>MVT 만</b> 지운다 — 그것이 단일 작성자 규약이고, 기술적으로도 유일한 길이다
 * (플러그인의 메모리 캐시가 5분마다 파일을 다시 굽는다. 밖에서 지운 파일은 그냥 되살아난다).
 * 그래서 서로의 몫은 <b>다리로 청한다</b>: {@code run/bridge/reset/}. 남의 창고에 손을 넣지 않는다.
 *
 * <p><b>지키는 것 넷.</b>
 * <ol>
 *   <li><b>백업이 먼저다.</b> 저장소 방언이 살아 있는 DB의 일관된 사본을 만든다.
 *       <b>백업이 실패하면 한 행도 지우지 않는다</b> — 검사는 "파일이 생겼는가 · 비어 있지 않은가" 둘 다다.</li>
 *   <li><b>세계는 안 건드린다.</b> 지울 수 있는 표는 {@code config/reset.yml} 의 등록부가 정하고,
 *       {@code protected_tables}(지역·사람·소문·원장·달력)는 <b>어떤 범위로도 열리지 않는다.</b>
 *       기동 때 한 번, 지우기 직전에 또 한 번 검사한다 — 등록부에 실수로 적어 넣어도 그 자리에서 멈춘다.</li>
 *   <li><b>남의 것은 못 지운다.</b> 기본은 자기 자신. 남을 지우려면 부르는 쪽이 관리자를 검사한다.</li>
 *   <li><b>소리내어 말한다.</b> 지운 행을 <b>지우기 전에 읽어</b> 백업의 {@code deleted.json} 에 적고,
 *       사람에게 표별 행수를 그대로 보여 준다.</li>
 * </ol>
 *
 * <p><b>★ 이 클래스는 기동을 죽이지 않는다.</b> 등록부가 깨졌으면 초기화 기능만 잠그고
 * ({@link #locked()}) 봇은 그대로 산다 — 되돌리는 명령 하나 때문에 세계가 안 켜지면 그게 더 나쁘다.
 */
final class Reset {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 지울 표 한 줄 — 표 이름 · 매달린 축 · 추가 조건 (등록부가 준 그대로) */
    private record Spec(String table, String by, String where) {
    }

    /** 범위 하나 — 접합 · 캐릭터 · 전부 */
    private record Scope(String name, String say, List<String> tables, boolean mc) {
    }

    /** 결과 — 사람에게 보여 줄 것 전부 */
    record Report(String scope, String backup, Map<String, Integer> deleted, boolean askedMvt,
            List<String> notes) {

        int total() {
            return deleted.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private final ResetStore db;
    private final Map<String, Spec> specs = new LinkedHashMap<>();
    private final Map<String, Scope> scopes = new LinkedHashMap<>();
    private final List<String> protectedTables = new ArrayList<>();
    private Path backupRoot = Path.of("run/backup");
    private Path bridgeDir = Path.of("run/bridge/reset");
    private int pollSeconds = 2;
    private long ttlMillis = 120_000L;

    /** 등록부가 성했는가 — 깨졌으면 초기화만 잠그고 봇은 산다 */
    private String fault;
    private ScheduledExecutorService sched;

    Reset(ResetStore db, Path configDir) {
        this.db = db;
        try {
            load(configDir.resolve("reset.yml"));
        } catch (Exception e) {
            this.fault = String.valueOf(e.getMessage());
            System.err.println("초기화 등록부가 깨졌다 — /초기화 는 잠근다 (봇은 그대로 돈다): " + fault);
        }
    }

    boolean locked() {
        return fault != null;
    }

    String fault() {
        return fault;
    }

    List<String> scopeNames() {
        return List.copyOf(scopes.keySet());
    }

    String say(String scope) {
        Scope s = scopes.get(scope);
        return s == null ? "" : s.say();
    }

    // ══════════════ 등록부 ══════════════

    private void load(Path file) throws IOException {
        Map<String, Object> cfg = RulesConfig.load(file);
        if (cfg == null || cfg.isEmpty()) {
            throw new IOException("등록부를 못 읽었다: " + file);
        }
        Map<String, Object> backup = RulesConfig.section(cfg, "backup");
        this.backupRoot = Path.of(String.valueOf(backup.getOrDefault("dir", "run/backup")));
        // ★ 이 값은 스위치가 아니다. false 를 적어도 백업은 뜬다 — 안 뜨면 안 지운다.
        if (Boolean.FALSE.equals(backup.get("required"))) {
            System.err.println("초기화 — backup.required: false 는 **무시한다**. 백업 없이는 지우지 않는다.");
        }
        Map<String, Object> bridge = RulesConfig.section(cfg, "bridge");
        this.bridgeDir = Path.of(String.valueOf(bridge.getOrDefault("dir", "run/bridge/reset")));
        this.pollSeconds = intOf(bridge.get("poll_seconds"), 2);
        this.ttlMillis = intOf(bridge.get("ttl_seconds"), 120) * 1000L;

        for (Object t : list(cfg.get("protected_tables"))) {
            protectedTables.add(String.valueOf(t));
        }
        if (protectedTables.isEmpty()) {
            throw new IOException("protected_tables 가 비었다 — 세계를 지킬 목록이 없으면 지우지 않는다");
        }
        RulesConfig.section(cfg, "tables").forEach((table, raw) -> {
            Map<String, Object> m = asMap(raw);
            specs.put(table, new Spec(table,
                    String.valueOf(m.getOrDefault("by", "character_id")),
                    m.get("where") == null ? null : String.valueOf(m.get("where"))));
        });
        RulesConfig.section(cfg, "scopes").forEach((name, raw) -> {
            Map<String, Object> m = asMap(raw);
            List<String> tables = new ArrayList<>();
            for (Object t : list(m.get("bot"))) {
                tables.add(String.valueOf(t));
            }
            scopes.put(name, new Scope(name, String.valueOf(m.getOrDefault("말", name)),
                    tables, Boolean.TRUE.equals(m.get("mc"))));
        });
        if (scopes.isEmpty()) {
            throw new IOException("scopes 가 비었다");
        }
        // ★★ 【눈 ①】 기동 때 검사한다 — 등록부가 세계를 지우라고 적어 두었는가.
        //   여기서 걸리면 초기화는 잠기고, 봇은 그대로 돈다. 세계는 한 행도 안 지워진다.
        for (Scope s : scopes.values()) {
            for (String t : s.tables()) {
                guard(t);
                if (!specs.containsKey(t)) {
                    throw new IOException("범위 '" + s.name() + "' 가 등록부에 없는 표를 가리킨다: " + t
                            + " (tables: 에 축을 적어라)");
                }
            }
        }
        System.out.println("초기화 등록부 — 범위 " + scopes.keySet() + " · 지울 수 있는 표 "
                + specs.keySet() + " · 세계(보호) " + protectedTables);
    }

    /** ★★ 세계의 표인가 — 맞으면 던진다. 지우기 직전에도 한 번 더 부른다 (두 번 묻는다) */
    private void guard(String table) throws IOException {
        if (protectedTables.contains(table)) {
            throw new IOException("★ 세계의 표다 — 초기화가 열 수 없다: " + table
                    + " (config/reset.yml protected_tables)");
        }
    }

    // ══════════════ 지운다 ══════════════

    /**
     * 되돌린다. <b>백업 → 읽기 → 지우기</b> 순이고, 백업이 실패하면 <b>여기서 끝난다</b>.
     *
     * @param scopeName 접합 | 캐릭터 | 전부
     * @param discordId 누구의 것인가 (null 이면 mcUuid 로 찾는다)
     * @param mcUuidIn  마크에서 온 청이면 그 몸 (null 이면 mvt_link 에서 찾는다)
     * @param actor     누가 시켰나 (원장에 적는다)
     * @param stampIn   백업 폴더의 시각 (마크가 정한 것이 있으면 같은 폴더에 넣는다)
     */
    synchronized Report reset(String scopeName, String discordId, String mcUuidIn, String actor,
            String stampIn) throws Exception {
        // 마크에서 온 청이면 마크는 **이미 제 몫을 하고 있다** — 되받아 청하면 그 사람이 두 번 쫓겨난다
        return reset(scopeName, discordId, mcUuidIn, actor, stampIn, mcUuidIn == null);
    }

    synchronized Report reset(String scopeName, String discordId, String mcUuidIn, String actor,
            String stampIn, boolean askMvt) throws Exception {
        if (locked()) {
            throw new IllegalStateException("초기화가 잠겨 있다 (등록부): " + fault);
        }
        Scope scope = scopes.get(scopeName);
        if (scope == null) {
            throw new IllegalArgumentException("모르는 범위: " + scopeName);
        }
        List<String> notes = new ArrayList<>();

        // ── 누구인가 — 지우기 전에 다 찾아 둔다 (mvt_link 를 지우고 나면 몸을 못 찾는다)
        String discord = discordId;
        if (discord == null && mcUuidIn != null) {
            discord = db.discordOfMc(mcUuidIn).orElse(null);
        }
        List<Long> charIds = discord == null ? List.of() : db.characterIdsOf(discord);
        List<String> uuids = new ArrayList<>();
        if (mcUuidIn != null) {
            uuids.add(mcUuidIn);
        }
        for (long id : charIds) {
            for (String u : db.mcUuidsOf(id)) {
                if (!uuids.contains(u)) {
                    uuids.add(u);
                }
            }
        }
        if (charIds.isEmpty() && uuids.isEmpty()) {
            notes.add("지울 것이 없다 — 이 계정에는 캐릭터도 이어진 몸도 없다");
        }

        // ── ★★ 백업이 먼저다. 실패하면 한 행도 안 지운다
        String stamp = stampIn == null || stampIn.isBlank()
                ? LocalDateTime.now().format(STAMP) : stampIn;
        Path dir = Path.of(backupRoot + "-" + stamp).resolve("bot");
        Files.createDirectories(dir);
        Path snapshot = dir.resolve(db.snapshotFileName());
        Files.deleteIfExists(snapshot);
        db.snapshotTo(snapshot);
        if (!Files.isRegularFile(snapshot) || Files.size(snapshot) == 0) {
            throw new IOException("★ 백업이 뜨지 않았다 — 아무것도 지우지 않았다 (" + snapshot + ")");
        }

        // ── 무엇을 지우는가 — 지우기 **전에** 읽는다 (조용한 삭제 금지)
        List<Spec> plan = new ArrayList<>();
        for (String table : scope.tables()) {
            guard(table);   // ★★ 【눈 ②】 지우기 직전에 한 번 더 — 세계의 표는 여기서도 막힌다
            Spec spec = specs.get(table);
            if (!db.hasTable(table)) {
                // mvt_link_code 는 곧 사라지고 mvt_link_request 는 아직 없을 수 있다 — 없으면 없는 대로
                notes.add("표가 없다 (지나간다): " + table);
                continue;
            }
            plan.add(spec);
        }

        Map<String, Integer> deleted = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> taken = new LinkedHashMap<>();
        String transactionDiscord = discord;
        db.inTransaction(() -> {
            for (Spec spec : plan) {
                int n = 0;
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Object value : axis(spec, charIds, uuids, transactionDiscord)) {
                    rows.addAll(db.rowsOf(spec.table(), spec.by(), value, spec.where()));
                    n += db.deleteRows(spec.table(), spec.by(), value, spec.where());
                }
                if (n > 0 || !rows.isEmpty()) {
                    deleted.put(spec.table(), n);
                    taken.put(spec.table(), rows);
                }
            }
            return null;
        });

        // ── 백업 옆에 **무엇을 지웠는지** 적는다 (되돌리는 사람이 읽을 유일한 문서)
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("범위", scopeName);
        receipt.put("시킨이", actor);
        receipt.put("시각", LocalDateTime.now().toString());
        receipt.put("discord_id", discord);
        receipt.put("character_ids", charIds);
        receipt.put("mc_uuids", uuids);
        receipt.put("지운행수", deleted);
        receipt.put("지운행", taken);
        receipt.put("되돌리는법", db.restoreInstructions(snapshot));
        Files.writeString(dir.resolve("deleted.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(receipt),
                StandardCharsets.UTF_8);

        // ── 원장에 남긴다 — 지운 사실은 세계의 사건이다 (events 는 append-only, 안 지운다)
        db.logEvent("초기화", "world", String.valueOf(actor), "character",
                charIds.isEmpty() ? "" : String.valueOf(charIds.get(0)),
                Map.of("범위", scopeName, "표", deleted, "백업", dir.toString()));

        // ── 마크의 몫은 **청한다** (봇은 마크의 파일을 열지 않는다)
        //   ★ 단, 마크에서 온 청이면 청하지 않는다 — 마크는 이미 제 몫을 하는 중이다.
        //     되받아 청하면 그 사람이 **두 번 쫓겨나고** 백업이 두 번 뜬다 (서로를 향해 메아리친다).
        boolean asked = false;
        if (scope.mc() && askMvt) {
            if (uuids.isEmpty()) {
                notes.add("마크에 이어진 몸이 없다 — 마크 쪽은 청하지 않았다");
            } else {
                for (String uuid : uuids) {
                    ask(scopeName, discord, uuid, actor, stamp);
                }
                asked = true;
            }
        }
        String line = "초기화 [" + scopeName + "] — " + actor + " · 지운 행 " + deleted
                + " · 백업 " + dir + (asked ? " · 마크에 청했다" : "");
        System.out.println(line);
        return new Report(scopeName, dir.toString(), deleted, asked, notes);
    }

    /** 이 표는 어떤 값들에 매달려 있는가 — 등록부의 {@code by} 가 고른다 */
    private List<Object> axis(Spec spec, List<Long> charIds, List<String> uuids, String discord) {
        List<Object> out = new ArrayList<>();
        switch (spec.by()) {
            case "id", "character_id" -> charIds.forEach(out::add);
            case "owner_id" -> charIds.forEach(id -> out.add(String.valueOf(id)));   // registry.owner_id 는 TEXT
            case "mc_uuid" -> out.addAll(uuids);
            case "discord_id" -> {
                if (discord != null) {
                    out.add(discord);
                }
            }
            default -> System.err.println("초기화 — 모르는 축 (지나간다): " + spec.by());
        }
        return out;
    }

    // ══════════════ 다리 — 마크에게 청하고, 마크의 청을 듣는다 ══════════════

    /** 마크에게 "이 몸을 지워 달라" 고 청한다 — 봇은 마크의 파일을 열지 않는다 */
    private void ask(String scope, String discordId, String mcUuid, String actor, String stamp)
            throws IOException {
        Map<String, Object> req = new LinkedHashMap<>();
        String id = UUID.randomUUID().toString();
        req.put("id", id);
        req.put("at", System.currentTimeMillis());
        req.put("from", "bot");
        req.put("scope", scope);
        req.put("discord_id", discordId);
        req.put("mc_uuid", mcUuid);
        req.put("actor", actor);
        req.put("backup", stamp);
        write(bridgeDir.resolve(System.currentTimeMillis() + "-" + id + ".json"), req);
    }

    private void write(Path target, Map<String, Object> body) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, JSON.writeValueAsString(body), StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 다리를 연다 — 마크에서 친 초기화가 여기로 온다 */
    void start() {
        if (locked()) {
            return;
        }
        sched = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "honcheon-reset");
            t.setDaemon(true);
            return t;
        });
        sched.scheduleWithFixedDelay(this::drainQuietly, pollSeconds, pollSeconds, TimeUnit.SECONDS);
        System.out.println("초기화 다리 — 수신 " + bridgeDir + " (" + pollSeconds + "초)");
    }

    void stop() {
        if (sched != null) {
            sched.shutdownNow();
        }
    }

    private void drainQuietly() {
        try {
            if (!Files.isDirectory(bridgeDir)) {
                return;
            }
            List<Path> files;
            try (var s = Files.list(bridgeDir)) {
                files = s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
            }
            for (Path f : files) {
                consume(f);
            }
        } catch (Exception e) {
            System.err.println("초기화 다리 수신 실패: " + e.getMessage());
        }
    }

    private void consume(Path file) {
        Map<String, Object> req;
        try {
            req = JSON.readValue(Files.readString(file, StandardCharsets.UTF_8), Map.class);
        } catch (Exception broken) {
            System.err.println("초기화 청이 깨졌다 (버린다): " + file + " — " + broken.getMessage());
            done(file, null);
            return;
        }
        if (!"mvt".equals(String.valueOf(req.get("from")))) {
            return;   // 내가 쓴 청이다 — 마크가 가져간다
        }
        long at = ((Number) req.getOrDefault("at", 0L)).longValue();
        if (System.currentTimeMillis() - at > ttlMillis) {
            System.err.println("초기화 청이 늙었다 (버린다 — 뒤늦게 지우지 않는다): " + file);
            done(file, "만료");
            return;
        }
        String result;
        try {
            Report r = reset(String.valueOf(req.get("scope")), null,
                    (String) req.get("mc_uuid"), String.valueOf(req.get("actor")),
                    (String) req.get("backup"));
            result = "지운 행 " + r.deleted() + " · 백업 " + r.backup();
        } catch (Exception e) {
            result = "실패: " + e.getMessage();
            System.err.println("초기화 — 마크의 청을 못 이뤘다: " + e.getMessage());
        }
        done(file, result);
    }

    /** 처리한 청은 done/ 으로 옮긴다 — 두 번 지우지 않는다 (멱등) */
    private void done(Path file, String result) {
        try {
            Path doneDir = bridgeDir.resolve("done");
            Files.createDirectories(doneDir);
            if (result != null) {
                Files.writeString(doneDir.resolve(file.getFileName() + ".bot.txt"), result,
                        StandardCharsets.UTF_8);
            }
            Files.move(file, doneDir.resolve(file.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("초기화 청을 치우지 못했다 (다음 폴에서 또 본다): " + e.getMessage());
        }
    }

    // ══════════════ 잡동사니 ══════════════

    private static int intOf(Object o, int fallback) {
        return o instanceof Number n ? n.intValue() : fallback;
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** 사람에게 보여 줄 한 덩이 */
    static String render(Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("**초기화 — ").append(r.scope()).append("**\n");
        if (r.deleted().isEmpty()) {
            sb.append("지운 것이 없다.\n");
        } else {
            sb.append("지운 것 (봇의 장부):\n");
            r.deleted().forEach((t, n) -> sb.append("· `").append(t).append("` ").append(n)
                    .append("행\n"));
        }
        if (r.askedMvt()) {
            sb.append("마크에 청했다 — 몸·원장·금고는 서버가 지운다 (몇 초 걸린다).\n");
        }
        r.notes().forEach(n -> sb.append("· ").append(n).append('\n'));
        sb.append("\n백업: `").append(r.backup()).append("`\n");
        sb.append("*세계(지역·사람·소문·원장·달력)는 한 행도 건드리지 않았다.*");
        return sb.toString();
    }

    /** 마크가 부를 때 쓰는 이름 — 없으면 빈 값 */
    static Optional<String> scopeOf(String raw) {
        return raw == null || raw.isBlank() ? Optional.empty() : Optional.of(raw.trim());
    }
}
