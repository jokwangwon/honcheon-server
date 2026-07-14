package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 초기화(初期化) — <b>마크 쪽의 손.</b> 시험할 때마다 사람이 파일을 손으로 지우던 일을 명령으로 만든다.
 *
 * <p><b>왜 여기여야 하는가 (봇이 대신 못 지운다).</b> 마크의 몸은 파일에만 있는 것이 아니라 <b>돌고 있는
 * 서버의 메모리에</b> 있다. 밖에서 {@code ledgers.yml} 을 지워 봐야 5분짜리 타이머가
 * {@link HoncheonMvt#saveLedgers()} 로 <b>메모리에서 그대로 다시 굽는다</b>. 로그아웃 한 번이면
 * {@link HoncheonMvt#saveLedger} 가 지운 줄을 <b>되살린다</b> (그 메서드는 파일에 <b>합친다</b>).
 * 그러므로 마크의 몸을 지우는 손은 <b>서버 안에</b> 있어야 한다. 이 클래스가 그 손이다.
 *
 * <p><b>봇의 장부는 건드리지 않는다.</b> MVT 는 {@code run/bot/honcheon.db} 를 <b>열지 않는다</b>
 * (단일 작성자 규약). 봇의 몫은 다리로 <b>청한다</b>: {@code run/bridge/reset/}.
 * 봇이 꺼져 있으면 청은 파일로 남아 있다가 봇이 켜질 때 이뤄진다 — 세계 다리와 같은 성질이다.
 *
 * <p><b>지우는 순서가 곧 안전이다.</b>
 * <ol>
 *   <li><b>백업</b> — 실패하면 <b>여기서 끝난다</b> (한 파일도 안 지운다).</li>
 *   <li><b>내보낸다</b> (kick) — 접속 중이면 서버가 나가는 길에 {@code playerdata} 를 <b>다시 쓴다</b>.
 *       그러니 <b>먼저 내보내고</b>, 저장이 끝난 <b>뒤에</b> 지운다 (1초 뒤).</li>
 *   <li><b>메모리에서 지운다</b> — {@code ledgers} 맵 · 연무장 금고. 여기를 안 비우면 방금 지운 파일이
 *       5분 뒤 되살아난다.</li>
 *   <li><b>파일을 지운다</b> — 그 사람의 절(節)만. 남의 것이 같은 파일에 산다.</li>
 * </ol>
 *
 * <p><b>★ 이 클래스는 기동을 죽이지 않는다.</b> 등록부가 깨졌으면 초기화만 잠기고 플러그인은 그대로 산다
 * (한 줄이 {@code onEnable} 에서 터져 팩·나루·HUD 가 통째로 죽은 적이 있다 — 다시는 안 된다).
 */
final class Reset {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final HoncheonMvt plugin;
    private final Path repoRoot;

    private final List<String> scopes = new ArrayList<>();
    private final Map<String, String> says = new LinkedHashMap<>();
    private final Map<String, Boolean> touchesMc = new LinkedHashMap<>();
    private final List<String> perPlayer = new ArrayList<>();
    private final List<String> protectedPaths = new ArrayList<>();
    private String world = "honcheon";
    private String ledgerFile = "ledgers.yml";
    private String vaultFile = "dojang.yml";
    private Path backupRoot;
    private Path bridgeDir;
    private int pollSeconds = 2;
    private long ttlMillis = 120_000L;

    private String fault;
    /** 두 번 묻는다 — 한 번 친 사람은 여기 앉는다 (30초 안에 다시 치면 실행) */
    private final Map<UUID, long[]> pending = new LinkedHashMap<>();
    private final Map<UUID, String> pendingScope = new LinkedHashMap<>();

    Reset(HoncheonMvt plugin, Path configDir) {
        this.plugin = plugin;
        this.repoRoot = repoRoot(configDir);
        try {
            load(configDir.resolve("reset.yml"));
        } catch (Exception e) {
            this.fault = String.valueOf(e.getMessage());
            plugin.getLogger().warning("초기화 등록부가 깨졌다 — /혼천 초기화 는 잠근다 "
                    + "(플러그인은 그대로 돈다): " + fault);
        }
    }

    boolean locked() {
        return fault != null;
    }

    String fault() {
        return fault;
    }

    List<String> scopes() {
        return List.copyOf(scopes);
    }

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

    private void load(Path file) throws IOException {
        Map<String, Object> cfg = RulesConfig.load(file);
        if (cfg == null || cfg.isEmpty()) {
            throw new IOException("등록부를 못 읽었다: " + file);
        }
        Map<String, Object> backup = map(cfg.get("backup"));
        this.backupRoot = repoRoot.resolve(String.valueOf(backup.getOrDefault("dir", "run/backup")));
        Map<String, Object> bridge = map(cfg.get("bridge"));
        this.bridgeDir = repoRoot.resolve(String.valueOf(bridge.getOrDefault("dir", "run/bridge/reset")));
        this.pollSeconds = intOf(bridge.get("poll_seconds"), 2);
        this.ttlMillis = intOf(bridge.get("ttl_seconds"), 120) * 1000L;

        Map<String, Object> mc = map(cfg.get("mc"));
        this.world = String.valueOf(mc.getOrDefault("world", "honcheon"));
        this.ledgerFile = String.valueOf(mc.getOrDefault("ledgers", "ledgers.yml"));
        this.vaultFile = String.valueOf(mc.getOrDefault("vault", "dojang.yml"));
        for (Object p : list(mc.get("per_player"))) {
            perPlayer.add(String.valueOf(p));
        }
        if (perPlayer.isEmpty()) {
            throw new IOException("mc.per_player 가 비었다 — 지울 것을 모르면 지우지 않는다");
        }
        for (Object p : list(cfg.get("protected_paths"))) {
            protectedPaths.add(String.valueOf(p));
        }
        if (protectedPaths.isEmpty()) {
            throw new IOException("protected_paths 가 비었다 — 세계를 지킬 목록이 없으면 지우지 않는다");
        }
        map(cfg.get("scopes")).forEach((name, raw) -> {
            Map<String, Object> m = map(raw);
            scopes.add(name);
            says.put(name, String.valueOf(m.getOrDefault("말", name)));
            touchesMc.put(name, Boolean.TRUE.equals(m.get("mc")));
        });
        if (scopes.isEmpty()) {
            throw new IOException("scopes 가 비었다");
        }
        // ★★ 【눈 ①】 기동 때 검사한다 — 지울 경로가 **세계를 겨누고 있는가**
        for (String p : perPlayer) {
            guard(p);
        }
        plugin.getLogger().info("초기화 등록부 — 범위 " + scopes + " · 세계 " + world
                + " · 보호 " + protectedPaths + " · 저장소 " + repoRoot);
    }

    /** ★★ 이 경로가 세계를 겨누는가 — 겨누면 던진다. 지우기 직전에도 한 번 더 부른다 */
    private void guard(String path) throws IOException {
        for (String bad : protectedPaths) {
            // 경로 조각으로 걸린다 — "region" 이 "run/mvt/honcheon/region/..." 을 잡는다
            for (String piece : path.split("[/\\\\]")) {
                if (piece.equals(bad)) {
                    throw new IOException("★ 세계의 것이다 — 초기화가 열 수 없다: " + path
                            + " (config/reset.yml protected_paths: " + bad + ")");
                }
            }
        }
    }

    /**
     * ★★ <b>저장소 밖으로 나가지 않는다.</b> 등록부에 {@code ../../} 한 줄이 잘못 들어가면 초기화가
     * 저장소 바깥의 파일을 지울 수 있다 — 그런 문은 아예 없어야 한다. 실제로 지울 경로를
     * (플레이스홀더를 채운 <b>최종형</b>으로) 다시 검사하고, 뿌리 안에 있는지 확인한다.
     */
    private Path target(String pattern, UUID uuid) throws IOException {
        String filled = fill(pattern, uuid);
        guard(pattern);   // 등록부에 적힌 그대로 (템플릿)
        guard(filled);    // ★ 채운 뒤의 모습 — world: region 같은 장난을 여기서 잡는다
        Path p = repoRoot.resolve(filled).normalize();
        if (!p.startsWith(repoRoot.normalize())) {
            throw new IOException("★ 저장소 밖을 가리킨다 — 초기화가 열 수 없다: " + filled);
        }
        return p;
    }

    // ══════════════ 사람이 친다 ══════════════

    /**
     * {@code /혼천 초기화 <범위>} — <b>두 번 쳐야 지운다.</b> 첫 번째는 무엇을 지울지 말하고 멈춘다.
     * (되돌릴 수 없는 일이다. 오타 한 번으로 사라지면 안 된다.)
     */
    void command(Player player, String scope) {
        if (locked()) {
            player.sendMessage("§c초기화가 잠겨 있다 — 등록부(config/reset.yml)를 못 읽었다: " + fault);
            return;
        }
        if (!scopes.contains(scope)) {
            player.sendMessage("§c모르는 범위: " + scope + " §7— " + scopes);
            return;
        }
        UUID id = player.getUniqueId();
        long[] at = pending.get(id);
        boolean armed = at != null && System.currentTimeMillis() - at[0] < 30_000L
                && scope.equals(pendingScope.get(id));
        if (!armed) {
            pending.put(id, new long[]{System.currentTimeMillis()});
            pendingScope.put(id, scope);
            player.sendMessage("§6§l되돌리려는가 — " + scope);
            player.sendMessage("§7" + says.get(scope));
            if (Boolean.TRUE.equals(touchesMc.get(scope))) {
                player.sendMessage("§7· 마크: §f몸(playerdata)·원장·연무장 금고 §7— 당신은 §c서버에서 나간다");
            }
            player.sendMessage("§7· 봇: §f장부(캐릭터·접합·혈채) §7— 다리로 청한다");
            // ★ 봇이 꺼져 있으면 청은 **늙어 죽는다** (ttl_seconds). 조용히 놔두면 사람은
            //   "다 지웠다" 고 믿고 장부는 그대로 남는다 — 그러니 **누르기 전에** 말해 준다.
            //   (전부 범위는 여기서 내보내지므로, 지운 뒤에 말하면 그 말을 읽을 사람이 없다.)
            if (plugin.worldDay() == 0) {
                player.sendMessage("§c§l⚠ 봇이 꺼져 있다 §c— 장부(캐릭터·접합·혈채)는 §l안 지워진다§c. "
                        + "마크의 몸만 지워진다.");
                player.sendMessage("§c  봇을 켜고 디스코드에서 §f/초기화§c 를 치는 편이 낫다.");
            }
            player.sendMessage("§7백업은 항상 뜬다. §c§l되돌릴 수 없다.");
            player.sendMessage("§e확실하면 30초 안에 §f/혼천 초기화 " + scope + " §e를 §l한 번 더§e 쳐라.");
            return;
        }
        pending.remove(id);
        pendingScope.remove(id);
        run(scope, player.getUniqueId(), player.getName(), null, "mc:" + player.getName(), true);
    }

    /**
     * 되돌린다. <b>백업 → 내보내기 → 메모리 → 파일</b> 순. 백업이 실패하면 <b>아무것도 안 지운다.</b>
     *
     * @param askBot 봇에게 장부를 지워 달라고 청할 것인가 (마크에서 시작한 초기화면 true)
     */
    void run(String scope, UUID uuid, String name, String stampIn, String actor, boolean askBot) {
        List<String> said = new ArrayList<>();
        String stamp = stampIn == null || stampIn.isBlank()
                ? LocalDateTime.now().format(STAMP) : stampIn;
        Path dir = Path.of(backupRoot + "-" + stamp).resolve("mc");
        boolean wipesMc = Boolean.TRUE.equals(touchesMc.get(scope));

        // ── 봇에게 청한다 (마크에서 시작했을 때만). 순서: **지우기 전에** 청한다 —
        //    여기서 죽어도 봇은 제 몫을 한다. 봇은 제 장부만 지우므로 마크와 겹치지 않는다.
        if (askBot) {
            try {
                ask(scope, uuid, name, actor, stamp);
                said.add("봇에 청했다 (장부는 봇이 지운다)");
            } catch (IOException e) {
                plugin.getLogger().warning("초기화 — 봇에게 청하지 못했다: " + e.getMessage());
                said.add("§c봇에 청하지 못했다: " + e.getMessage());
            }
        }

        if (!wipesMc) {
            // 접합·캐릭터 — 마크의 몸은 그대로다. 봇이 접합을 끊으면 20초 안에 화면이 바뀐다
            // (SkillListener.syncSheet 가 스냅숏에 이 몸이 없으면 linked=false 로 내린다).
            tell(uuid, said, "마크의 몸·짐은 그대로다. 봇의 장부만 지운다.");
            return;
        }

        // ── ★★ 백업이 먼저다
        try {
            Files.createDirectories(dir);
            int copied = backup(dir, uuid);
            if (copied == 0) {
                said.add("백업할 것이 없었다 (이 몸은 아직 파일이 없다)");
            }
            // 백업이 실제로 떠졌는가 — 폴더가 있고, 원본이 있었다면 사본도 있어야 한다
            if (!Files.isDirectory(dir)) {
                throw new IOException("백업 폴더가 생기지 않았다: " + dir);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("★ 초기화 중단 — 백업 실패. **아무것도 지우지 않았다**: " + e.getMessage());
            tell(uuid, said, "§c초기화 중단 — 백업 실패. 아무것도 지우지 않았다: " + e.getMessage());
            return;
        }

        // ── 내보낸다. 나가는 길에 서버가 playerdata 를 **다시 쓴다** — 그 저장이 끝난 뒤에 지운다
        Player online = plugin.getServer().getPlayer(uuid);
        if (online != null && online.isOnline()) {
            online.kick(net.kyori.adventure.text.Component.text(
                    "초기화 — 되돌렸다. 다시 접속하면 나루(입도진)부터다."));
        }
        // 1초 뒤 — 퇴장 저장(원장 굽기 · playerdata 쓰기)이 끝난 자리를 지운다
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            List<String> done = new ArrayList<>();
            try {
                wipe(uuid, done);
                plugin.getLogger().info("초기화 [" + scope + "] " + name + " (" + actor + ") — "
                        + String.join(" · ", done) + " · 백업 " + dir);
                Files.writeString(dir.resolve("deleted.txt"),
                        "초기화 " + scope + "\n시킨이: " + actor + "\n몸: " + name + " (" + uuid + ")\n"
                                + "시각: " + LocalDateTime.now() + "\n지운 것:\n  "
                                + String.join("\n  ", done)
                                + "\n\n되돌리는 법: 서버를 끄고 이 폴더의 파일을 제자리에 되돌린 뒤 켠다\n"
                                + "  playerdata/*.dat → run/mvt/" + world + "/playerdata/\n"
                                + "  ledgers.yml · dojang.yml 의 이 uuid 절(節) → "
                                + "run/mvt/plugins/HoncheonMVT/ 의 같은 파일에 되붙인다\n",
                        StandardCharsets.UTF_8);
            } catch (Exception e) {
                plugin.getLogger().severe("초기화 — 지우다 실패했다 (백업은 " + dir + " 에 있다): "
                        + e.getMessage());
            }
        }, 20L);
    }

    /** 그 사람의 파일을 백업으로 옮겨 적는다 — 원본은 아직 그대로다 */
    private int backup(Path dir, UUID uuid) throws IOException {
        int n = 0;
        Path pd = dir.resolve("playerdata");
        for (String pattern : perPlayer) {
            Path src = target(pattern, uuid);   // ★★ 【눈 ②】 지우기 직전에 또 검사한다
            if (Files.isRegularFile(src)) {
                Files.createDirectories(pd);
                Files.copy(src, pd.resolve(src.getFileName().toString()),
                        StandardCopyOption.REPLACE_EXISTING);
                n++;
            }
        }
        // 원장·금고는 **파일 전체**를 뜬다 (그 사람 절만 지울 것이므로, 되돌릴 때 통째로 필요하다)
        for (String yml : List.of(ledgerFile, vaultFile)) {
            File src = new File(plugin.getDataFolder(), yml);
            if (src.isFile()) {
                Files.copy(src.toPath(), dir.resolve(yml), StandardCopyOption.REPLACE_EXISTING);
                n++;
            }
        }
        return n;
    }

    /** 실제로 지운다 — 메모리 먼저, 파일 나중 (거꾸로 하면 메모리가 파일을 되살린다) */
    private void wipe(UUID uuid, List<String> done) throws IOException {
        // ⓪ ★★ 묘비 — **가장 먼저 세운다.** 이것이 없어서 지운 사람이 3분 뒤 되살아났다.
        //    봇이 굽는 스냅숏(run/bridge/world_state.json)은 **캐시**다. 초기화 당시 그 파일은
        //    3분 전에 구워진 것이었고, 그 안에는 이 몸의 시트·접합이 그대로 살아 있었다.
        //    재접속 → syncSheet 가 그 낡은 시트를 실어 linked=true → saveLedgers 가 파일에 구웠다.
        //    ★ 지운 시각의 묘비를 세우면, **그보다 낡은 스냅숏은 이 몸에 대해 입을 다문다**.
        WorldBridge.forget(uuid, System.currentTimeMillis());
        done.add("묘비(낡은 스냅숏이 되살리지 못한다)");
        // ① 메모리 — 원장 맵. 여기를 안 비우면 5분 뒤 saveLedgers() 가 그대로 다시 굽는다
        if (plugin.swapLedger(uuid, null) != null) {
            done.add("원장(메모리)");
        }
        // ② 메모리+파일 — 연무장 금고. 여기 남아 있으면 **진짜 장부**가 되살아난다
        //    (fresh_start.sh 가 놓친 구멍이 정확히 이것이다)
        if (plugin.dojang() != null && plugin.dojang().forget(uuid)) {
            done.add("연무장 금고");
        }
        // ③ 파일 — 원장 yml 의 그 사람 절(節)만. **파일을 지우지 않는다** (남의 것이 같이 산다)
        if (dropSection(ledgerFile, uuid)) {
            done.add(ledgerFile + " [" + uuid + "]");
        }
        if (dropSection(vaultFile, uuid)) {
            done.add(vaultFile + " [" + uuid + "]");
        }
        // ④ 몸 — playerdata · stats · advancements
        for (String pattern : perPlayer) {
            Path target = target(pattern, uuid);   // ★★ 【눈 ③】 마지막 관문 — 여기서도 다시 묻는다
            if (Files.deleteIfExists(target)) {
                done.add(repoRoot.relativize(target).toString());
            }
        }
        if (done.isEmpty()) {
            done.add("(지울 것이 없었다)");
        }
        verify(uuid, done);
    }

    /**
     * ★★ <b>【눈】 "지웠다"고 적기 전에, 정말 지워졌는지 다시 본다.</b>
     *
     * <p>여태 이 로그는 <b>의도</b>를 적었지 <b>사실</b>을 적지 않았다. 01:46 의 로그는
     * "{@code ledgers.yml [05909c69…]} 를 지웠다"고 말했고, 그 말은 그 순간 참이었다 —
     * 그런데 3분 뒤 그 줄이 되살아났다. <b>로그가 참이면 세계도 참이어야 한다.</b>
     *
     * <p>그래서 지운 <b>직후에</b> 파일과 메모리를 <b>다시 읽어</b> 확인한다. 어긋나면 조용히 넘어가지 않는다.
     */
    private void verify(UUID uuid, List<String> done) {
        List<String> ghosts = new ArrayList<>();
        for (String yml : List.of(ledgerFile, vaultFile)) {
            File file = new File(plugin.getDataFolder(), yml);
            if (file.isFile() && YamlConfiguration.loadConfiguration(file).contains(uuid.toString())) {
                ghosts.add(yml);
            }
        }
        if (plugin.peekLedger(uuid) != null) {
            ghosts.add("원장(메모리)");
        }
        if (!WorldBridge.forgotten(uuid)) {
            // 봇이 **초기화보다 새 스냅숏**을 이미 구웠다는 뜻이다 — 그렇다면 그 스냅숏이 정본이다.
            // 그 안에 이 몸이 아직 있으면 봇의 장부가 안 지워진 것이다 (봇이 꺼져 있었을 수 있다).
            if (WorldBridge.state().sheet(uuid) != null) {
                ghosts.add("봇의 시트(스냅숏에 아직 있다 — 봇 쪽 장부가 안 지워졌다)");
            }
        }
        if (ghosts.isEmpty()) {
            done.add("✔ 되읽어 확인했다 (파일·메모리·스냅숏에 없다)");
            return;
        }
        done.add("§c✖ 지웠는데 남아 있다: " + String.join(", ", ghosts));
        plugin.getLogger().severe("★★ 초기화가 지운 것이 되살아났다 (또는 지워지지 않았다) — "
                + uuid + " : " + String.join(", ", ghosts)
                + "  ※ 이 줄이 보이면 tools/ResetTombstoneSelfTest 를 돌려라");
    }

    /** yml 에서 그 사람의 절만 떼어 낸다 — 없으면 조용히 지나간다 */
    private boolean dropSection(String yml, UUID uuid) throws IOException {
        File file = new File(plugin.getDataFolder(), yml);
        if (!file.isFile()) {
            return false;
        }
        YamlConfiguration conf = YamlConfiguration.loadConfiguration(file);
        String key = uuid.toString();
        if (!conf.contains(key)) {
            return false;
        }
        conf.set(key, null);
        conf.save(file);
        return true;
    }

    private String fill(String pattern, UUID uuid) {
        return pattern.replace("{world}", world).replace("{uuid}", uuid.toString());
    }

    private void tell(UUID uuid, List<String> said, String line) {
        said.add(line);
        Player p = plugin.getServer().getPlayer(uuid);
        if (p != null && p.isOnline()) {
            said.forEach(s -> p.sendMessage("§7[초기화] §f" + s));
        }
        plugin.getLogger().info("초기화 — " + String.join(" · ", said));
    }

    // ══════════════ 다리 — 봇에게 청하고, 봇의 청을 듣는다 ══════════════

    private void ask(String scope, UUID uuid, String name, String actor, String stamp)
            throws IOException {
        Map<String, Object> req = new LinkedHashMap<>();
        String id = UUID.randomUUID().toString();
        req.put("id", id);
        req.put("at", System.currentTimeMillis());
        req.put("from", "mvt");
        req.put("scope", scope);
        req.put("mc_uuid", uuid.toString());
        req.put("mc_name", name);
        req.put("actor", actor);
        req.put("backup", stamp);
        Path target = bridgeDir.resolve(System.currentTimeMillis() + "-" + id + ".json");
        Files.createDirectories(bridgeDir);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, json(req), StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 다리를 연다 — 디스코드에서 친 초기화가 여기로 온다 */
    void start() {
        if (locked()) {
            return;
        }
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::drainQuietly,
                20L * pollSeconds, 20L * pollSeconds);
        plugin.getLogger().info("초기화 다리 — 수신 " + bridgeDir + " (" + pollSeconds + "초)");
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
            plugin.getLogger().warning("초기화 다리 수신 실패: " + e.getMessage());
        }
    }

    private void consume(Path file) {
        Map<String, Object> req;
        try {
            req = map(parse(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (Exception broken) {
            plugin.getLogger().warning("초기화 청이 깨졌다 (버린다): " + file);
            done(file);
            return;
        }
        if (!"bot".equals(String.valueOf(req.get("from")))) {
            return;   // 내가 쓴 청이다 — 봇이 가져간다
        }
        long at = req.get("at") instanceof Number n ? n.longValue() : 0L;
        if (System.currentTimeMillis() - at > ttlMillis) {
            plugin.getLogger().warning("초기화 청이 늙었다 (버린다 — 뒤늦게 지우지 않는다): " + file);
            done(file);
            return;
        }
        String scope = String.valueOf(req.get("scope"));
        String rawUuid = String.valueOf(req.get("mc_uuid"));
        UUID uuid;
        try {
            uuid = UUID.fromString(rawUuid);
        } catch (IllegalArgumentException bad) {
            plugin.getLogger().warning("초기화 청의 uuid 가 uuid 가 아니다 (버린다): " + rawUuid);
            done(file);
            return;
        }
        done(file);   // 먼저 치운다 — 두 번 지우지 않는다 (지우는 일은 메인 스레드에서 이어진다)
        // ★ 파일·엔티티는 **메인 스레드**에서만 만진다 (kick·스케줄러)
        plugin.getServer().getScheduler().runTask(plugin, () ->
                run(scope, uuid, String.valueOf(req.getOrDefault("mc_name", rawUuid)),
                        (String) req.get("backup"), String.valueOf(req.get("actor")), false));
    }

    private void done(Path file) {
        try {
            Path doneDir = bridgeDir.resolve("done");
            Files.createDirectories(doneDir);
            Files.move(file, doneDir.resolve(file.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warning("초기화 청을 치우지 못했다: " + e.getMessage());
        }
    }

    // ══════════════ 잡동사니 — 최소 JSON (새 의존성을 들이지 않는다) ══════════════

    private static String json(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(esc(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(esc(String.valueOf(v))).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static Object parse(String s) {
        return WorldBridge.Json.parse(s);   // 다리가 쓰는 그 파서 (새 형식을 발명하지 않는다)
    }

    private static int intOf(Object o, int fallback) {
        return o instanceof Number n ? n.intValue() : fallback;
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
