package com.honcheon.mvt;

import com.honcheon.core.rules.JudgmentEngine;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * /혼천 — 관리자 검증 명령. 엔진을 직접 두드려 수치를 눈으로 확인한다.
 * 서브커맨드: 원장 · 판정 · 팔기 · 물가 · 정산 · 협공 · 도움말
 */
public final class MvtCommand implements CommandExecutor {

    private final HoncheonMvt plugin;

    public MvtCommand(HoncheonMvt plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return help(sender);
        }
        try {
            return switch (args[0]) {
                case "원장", "정보" -> ledger(sender);   // 원장 = 하위호환 (terminology)
                case "판정" -> judge(sender, args);
                case "팔기" -> sell(sender);
                case "물가" -> prices(sender, args);
                case "정산" -> settle(sender, args);
                case "협공" -> coop(sender, args);
                case "경지" -> realm(sender, args);
                case "병기" -> giveWeapon(sender, args);   // 관리자 지급 — 검증용
                case "병기상" -> weaponShop(sender);        // 장쇠 좌판
                case "재련" -> reforgeWeapon(sender);        // 야철수 — 품 1단 상승
                case "각인" -> inscribeWeapon(sender, args); // 각인 — 슬롯 안에서만
                case "격돌" -> recordClash(sender);          // 애병 카운터 (검증용)
                case "사냥터" -> census(sender);              // 구역 개체군
                case "비무" -> spar(sender, args);            // 죽지 않는 싸움
                case "소환" -> summon(sender, args);          // 영물은 등록제 (관리자)
                case "지도" -> showMap(sender);               // 세계 지도 — 등록 좌표·여정 일수
                case "시드검사" -> seedCheck(sender, args);    // 지형 적합성 점수 (관리자)
                case "세계조성" -> buildWorld(sender);         // 등록 지역을 제 좌표에 (관리자)   // 무공 검증용 — MVT엔 캐릭터 시트가 없다
                case "지역조성" -> buildRegion(sender, args);   // 원거리 등록지 하나를 짓는다 (관리자·콘솔 가능)
                case "지역검수" -> auditRegion(sender, args);   // 지역 자동 검산 — 도달성·계약·허공·광원·수묵
                case "환경검수" -> auditTerrain(sender, args);   // 조성물과 자연의 이음매 — 공동·수역·경계·연결성
                case "운기" -> meditate(sender);
                case "조성" -> buildTown(sender, args);
                case "검수" -> auditTown(sender);   // 규칙 린트 — 콘솔 가능 (앵커 기준)
                case "조감" -> renderTown(sender, args);   // 조감도 PNG — 콘솔 가능 (인자 = 지역id)
                case "연무장" -> dojangEnter(sender);        // 시험 월드로 (스킬·몹·허수아비)
                case "귀환" -> dojangLeave(sender);          // 세계로 돌아온다
                case "시험" -> dojangTune(sender, args);     // 경지·내력·무공 조정
                case "허수아비" -> dojangDummy(sender, args); // 맞아 주는 몸 (피해 계측)
                case "문장" -> crests(sender);
                default -> help(sender);
            };
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "오류: " + e.getMessage());
            return true;
        }
    }

    private boolean ledger(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        // 화면 UI — 채팅 대신 경락도 GUI (조작 가능한 인벤토리 창). 채팅판은 chatLedger 폴백
        LedgerGui.open(plugin, player);
        return true;
    }

    @SuppressWarnings("unused")   // 팩 미적용 환경 폴백 — /혼천 정보 채팅판 (필요 시 재배선)
    private boolean chatLedger(Player player) {
        PlayerLedger ledger = plugin.ledger(player.getUniqueId());
        player.sendMessage(ChatColor.GOLD + "── 수련 기록 ──");
        ledger.allSkills().forEach((skill, days) -> player.sendMessage(String.format(
                ChatColor.AQUA + "%s %s" + ChatColor.WHITE + " 누적 %.2f일 (숙련 %d → 다음 %.0f%%)",
                skill, Glyphs.gauge(ledger.progressToNext(skill, plugin.progression())),
                days, ledger.levelOf(skill, plugin.progression()),
                ledger.progressToNext(skill, plugin.progression()) * 100)));
        player.sendMessage(ChatColor.WHITE + "오늘 적립 " + String.format("%.2f", ledger.grantedToday())
                + "일 / 소지금 " + ChatColor.YELLOW + ledger.money() + "문"
                + ChatColor.WHITE + " / 마크: 실전 " + ledger.marks실전() + " · 사선 " + ledger.marks사선());
        return true;
    }

    private boolean judge(CommandSender sender, String[] args) {
        int exec = Integer.parseInt(args[1]);
        int resist = Integer.parseInt(args[2]);
        Random random = ThreadLocalRandom.current();
        int roll = random.nextInt(6) + 1 + random.nextInt(6) + 1;
        JudgmentEngine.Tier tier = plugin.judgment().resolve(exec, roll, resist);
        int margin = exec + roll - resist;
        sender.sendMessage(String.format("%s2d6=%d │ 실행력 %d vs 저항 %d │ 마진 %+d → %s",
                ChatColor.GOLD, roll, exec + roll, resist, margin, tier.name()));
        if (plugin.judgment().isAutoSuccess(exec, resist)) {
            sender.sendMessage(ChatColor.GRAY + "(기대 마진 +8 이상 — 실전이라면 판정 생략 자동 성공)");
        }
        return true;
    }

    private boolean sell(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        // 조성된 마을이 있으면 매각은 장터에서만 — 맵이 의미를 갖기 시작한다 (M2b)
        Location market = plugin.anchor("장터");
        if (market != null && (player.getWorld() != market.getWorld()
                || player.getLocation().distanceSquared(market) > 15 * 15)) {
            player.sendMessage(ChatColor.GRAY + "여기서는 아무도 가죽을 사지 않는다 — 장터로 가라. ("
                    + market.getBlockX() + ", " + market.getBlockZ() + " 부근, 붉은 차양 노점)");
            return true;
        }
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            String name = item == null || !item.hasItemMeta() ? null : item.getItemMeta().getDisplayName();
            if (name == null) {
                continue;
            }
            String plain = ChatColor.stripColor(name);
            Integer base = switch (plain) {
                case "늑대 가죽" -> plugin.economy().basePrice("사냥_부산물", "늑대_가죽");
                case "여우 가죽" -> plugin.economy().basePrice("사냥_부산물", "여우_가죽");
                default -> null;
            };
            if (base != null) {
                total += plugin.economy().npcBuyPrice(base, false) * item.getAmount();
                player.getInventory().remove(item);
            }
        }
        if (total == 0) {
            sender.sendMessage(ChatColor.GRAY + "팔 가죽이 없다.");
        } else {
            plugin.ledger(player.getUniqueId()).earn(total);
            sender.sendMessage(ChatColor.YELLOW + "매입가(시세 50%)로 " + total + "문을 받았다. (흥정은 상술 판정의 몫)");
        }
        return true;
    }

    private boolean prices(CommandSender sender, String[] args) {
        int economyIndex = args.length > 1 ? Integer.parseInt(args[1]) : 50;
        sender.sendMessage(ChatColor.GOLD + "── 물가 (지역 경제 지표 " + economyIndex + ") ──");
        for (Map.Entry<String, String> entry : Map.of(
                "만두_한_끼", "생활", "봉놋방_1박", "생활", "검_범철", "장비", "늑대_가죽", "사냥_부산물").entrySet()) {
            int base = plugin.economy().basePrice(entry.getValue(), entry.getKey());
            sender.sendMessage(String.format("%s%s: %d문 (기준 %d × %.1f)", ChatColor.WHITE,
                    entry.getKey(), plugin.economy().adjustedPrice(base, economyIndex),
                    base, plugin.economy().priceMultiplier(economyIndex)));
        }
        return true;
    }

    private boolean settle(CommandSender sender, String[] args) {
        int help = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int clamped = plugin.lifecycle().clampPlayerHand(help);
        int power = plugin.lifecycle().lifeCheckPower(3, 4, clamped);   // 한백: 화술3 + 고객_파악4
        int difficulty = plugin.lifecycle().rungDifficulty("상인의_길", "상단_지점주");
        int margin = plugin.lifecycle().seasonMargin(power, difficulty);
        sender.sendMessage(String.format("%s한백 계절 정산: 판정력 %d vs 다음 칸(상단_지점주) %d → 마진 %+d %s",
                ChatColor.GOLD, power, difficulty, margin,
                margin >= 0 ? ChatColor.GREEN + "상승 궤적 적립" : ChatColor.GRAY + "정체"));
        if (clamped != help) {
            sender.sendMessage(ChatColor.GRAY + "(개입 보정은 ±3 클램프 — 입력 " + help + " → " + clamped + ")");
        }
        return true;
    }

    private boolean coop(CommandSender sender, String[] args) {
        int attackers = Integer.parseInt(args[1]);
        sender.sendMessage(ChatColor.GOLD + "협공 " + attackers + "인 → 보정 +"
                + plugin.party().coopAttackBonus(attackers)
                + ChatColor.GRAY + " (캡 +3 — 그 위는 합격진의 영역)");
        return true;
    }

    /** /혼천 경지 <경지> [내공] — MVT엔 캐릭터 시트가 없다. 무공 검증용으로 몸을 세운다 */
    private boolean realm(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(ChatColor.GRAY + "/혼천 경지 <삼류|이류|일류|절정|초절정|화경> [내공]");
            return true;
        }
        plugin.skills().setRealm(player, args[1],
                args.length > 2 ? Double.parseDouble(args[2]) : 1.0);
        return true;
    }

    /** /혼천 병기 <계열> <등급> [속성...] — 관리자 지급 (검증용) */
    private boolean giveWeapon(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        if (!player.isOp()) {
            player.sendMessage(ChatColor.RED + "병기 지급은 관리자의 몫이다.");
            return true;
        }
        if (args.length < 3) {
            player.sendMessage(ChatColor.GRAY
                    + "/혼천 병기 <검|도|창|권갑|단검|부|겸|월아산|구> <범철|정련|보병|신병|마병> [속성…]");
            return true;
        }
        int i = 3;
        Weapons.Bias bias = Weapons.Bias.균;
        Weapons.Craft craft = Weapons.Craft.보통;
        try {   // 위치 인자 — 파싱되면 소비, 아니면 속성으로 넘긴다 (하위 호환)
            if (i < args.length) {
                bias = Weapons.Bias.of(args[i]);
                i++;
            }
        } catch (IllegalArgumentException notABias) {
            // 속성이다 — 그대로 둔다
        }
        try {
            if (i < args.length) {
                craft = Weapons.Craft.of(args[i]);
                i++;
            }
        } catch (IllegalArgumentException notACraft) {
            // 속성이다
        }
        java.util.List<String> properties = i < args.length
                ? java.util.List.of(java.util.Arrays.copyOfRange(args, i, args.length))
                : java.util.List.of();
        try {
            player.getInventory().addItem(Weapons.make(
                    Weapons.Series.of(args[1]), Weapons.Grade.of(args[2]), bias, craft, properties));
        } catch (IllegalArgumentException rejected) {   // 명공 범철·슬롯 초과 등
            player.sendMessage(ChatColor.RED + rejected.getMessage());
            return true;
        }
        player.sendMessage(ChatColor.GOLD + "병기를 손에 쥐었다 — " + args[2] + " " + args[1]
                + " · " + bias.name() + " · " + craft.name());
        return true;
    }

    /** /혼천 지도 — 세계 지도 (등록 좌표·거리·여정 일수). 청하현이 원점이다 */
    private boolean showMap(CommandSender sender) {
        WorldMap map = plugin.worldMap();
        if (map == null) {
            sender.sendMessage(ChatColor.RED + "world_map.yml 이 없다.");
            return true;
        }
        sender.sendMessage(ChatColor.GOLD + "── 세계 지도 (청하현 = 원점, 1블록 = 1m) ──");
        for (WorldMap.Place p : map.all()) {
            int days = map.travelDays(p);
            sender.sendMessage(String.format("%s%-14s %s(%+d, %+d) %s· %s · %s",
                    ChatColor.WHITE, p.name(), ChatColor.GRAY, map.worldX(p), map.worldZ(p),
                    ChatColor.DARK_GRAY, p.terrain(),
                    days <= 0 ? "여기" : days + "일 여정"));
        }
        return true;
    }

    /** /혼천 시드검사 <시드...> — 등록 좌표의 지형이 시드와 맞는가 (관리자) */
    private boolean seedCheck(CommandSender sender, String[] args) {
        if (sender instanceof Player p && !p.isOp()) {
            return true;
        }
        WorldMap map = plugin.worldMap();
        if (map == null || args.length < 2) {
            sender.sendMessage(ChatColor.GRAY + "/혼천 시드검사 <시드> [시드...]");
            return true;
        }
        java.util.List<Long> seeds = new java.util.ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            seeds.add(Long.parseLong(args[i]));
        }
        sender.sendMessage(ChatColor.GOLD + "시드 검사 " + seeds.size() + "개 — 지형을 생성하며 훑는다 "
                + ChatColor.GRAY + "(틱을 나눠 먹는다 · 서버는 계속 돈다)");
        new SeedProbe(plugin, map, seeds, sender).runTaskTimer(plugin, 1L, 1L);
        return true;
    }

    /**
     * 시드 검사 — <b>틱을 나눠 먹는 채점기</b>.
     *
     * <p>구판은 후보 수백 곳을 한 틱 안에서 훑었다. 후보마다 지형을 표본하며 청크를 동기 생성하니,
     * 시드 5개에 4,380청크였고 워치독이 "메인 스레드가 60초째 응답 없음"이라며 서버를 죽였다.
     * 지형을 보려면 지형을 만들어야 한다 — 그 값은 못 깎는다. 깎을 수 있는 건 <b>한 틱에 얼마나 하냐</b>다.
     *
     * <p>그래서 후보를 하나씩 꺼내 쓰고, 틱마다 예산(20ms)이 다하면 손을 뗀다. 서버는 계속 돈다.
     */
    private static final class SeedProbe extends org.bukkit.scheduler.BukkitRunnable {
        private static final long TICK_BUDGET_NANOS = 20_000_000L;   // 20ms — 한 틱(50ms)의 절반 이하

        private final HoncheonMvt plugin;
        private final WorldMap map;
        private final java.util.List<Long> seeds;
        private final CommandSender sender;

        private int seedIndex = -1;
        private World probe;
        private java.util.List<WorldMap.Place> places;
        private int placeIndex;
        private java.util.List<int[]> candidates;
        private int candIndex;
        private WorldMap.Fit best;
        private int bestShift;
        private java.util.List<String> lines = new java.util.ArrayList<>();
        private int sum;
        private int count;

        SeedProbe(HoncheonMvt plugin, WorldMap map, java.util.List<Long> seeds, CommandSender sender) {
            this.plugin = plugin;
            this.map = map;
            this.seeds = seeds;
            this.sender = sender;
        }

        @Override
        public void run() {
            long t0 = System.nanoTime();
            while (System.nanoTime() - t0 < TICK_BUDGET_NANOS) {
                if (!step()) {
                    cancel();
                    return;
                }
            }
        }

        /** 한 걸음 = 후보 한 곳 채점. false = 검사 끝 */
        private boolean step() {
            if (probe == null) {                      // 다음 시드로
                seedIndex++;
                if (seedIndex >= seeds.size()) {
                    return false;
                }
                probe = map.createProbeWorld(seeds.get(seedIndex));
                places = map.seedProbeTargets(false);
                placeIndex = 0;
                candidates = null;
                lines = new java.util.ArrayList<>();
                sum = 0;
                count = 0;
                return true;
            }
            if (candidates == null) {                 // 다음 지역으로
                if (placeIndex >= places.size()) {
                    finishSeed();
                    return true;
                }
                candidates = map.probeCandidates(places.get(placeIndex));
                candIndex = 0;
                best = null;
                bestShift = 0;
                return true;
            }
            WorldMap.Place place = places.get(placeIndex);
            if (candIndex >= candidates.size()) {     // 후보 소진 — 최고점 자리에 앉힌다
                recordPlace(place, best, bestShift);
                return true;
            }
            int[] c = candidates.get(candIndex++);
            WorldMap.Fit f = map.fitAt(probe, place, c[0], c[1]);
            if (f.pass()) {                           // 합격 — 더 볼 것 없다
                recordPlace(place, f, c[2]);
                return true;
            }
            if (best == null || f.score() > best.score()) {
                best = f;
                bestShift = c[2];
            }
            return true;
        }

        private void recordPlace(WorldMap.Place place, WorldMap.Fit fit, int shift) {
            sum += fit.score();
            count++;
            lines.add(String.format("  %-14s %3d점 %s%s", place.name(), fit.score(),
                    fit.pass() ? "적합" : "부적합 — " + fit.verdict(),
                    shift == 0 ? "" : " (인근 " + shift + "칸 이동)"));
            placeIndex++;
            candidates = null;
        }

        private void finishSeed() {
            long seed = seeds.get(seedIndex);
            int score = count == 0 ? 0 : sum / count;
            sender.sendMessage(ChatColor.GOLD + "시드 " + seed + " — 점수 " + score);
            lines.forEach(line -> sender.sendMessage(ChatColor.GRAY + line));
            // 콘솔(RCON)로 부르면 명령이 끝난 뒤엔 sender 로 보낸 말이 사라진다 — 검사는 몇 분이 걸린다.
            // 결과는 로그에도 남긴다: 루프의 눈이 결과를 못 보면 검사한 적 없는 것과 같다.
            plugin.getLogger().info("[시드검사] 시드 " + seed + " — 점수 " + score);
            lines.forEach(line -> plugin.getLogger().info("[시드검사] " + line));
            map.disposeProbeWorld(probe);
            probe = null;
        }

        @Override
        public synchronized void cancel() {
            map.disposeProbeWorld(probe);   // 중도 취소·플러그인 종료에도 임시 월드는 남기지 않는다
            probe = null;
            super.cancel();
        }
    }

    /**
     * /혼천 지역조성 &lt;id&gt; — <b>등록부의 좌표에 실제로 집을 세운다</b> (관리자·콘솔 가능).
     *
     * <p>세계 지도에 33곳이 좌표를 갖고 있는데 선 것은 청하현과 산길 도적뿐이었다. 나머지는 build: later —
     * 등록만 되고 서지 않은 곳은 여행의 목적지가 아니라 문서의 줄이다. 이 명령이 그 줄을 집으로 바꾼다.
     *
     * <p>부지는 <b>지도가 고른다</b>: 등록 좌표에서 지형 적합성을 재고, 안 맞으면 링(64~1024칸)을 돌며
     * 첫 합격지를 잡는다 (청하현과 같은 규칙 — 좌표는 흔들려도 여정 일수는 안 흔들린다).
     */
    private boolean buildRegion(CommandSender sender, String[] args) {
        if (sender instanceof Player p && !p.isOp()) {
            return true;
        }
        WorldMap map = plugin.worldMap();
        if (map == null || args.length < 2) {
            sender.sendMessage(ChatColor.GRAY + "/혼천 지역조성 <지역id>  (예: nokrim_sochae)");
            return true;
        }
        WorldMap.Place place = map.place(args[1]);
        if (place == null) {
            sender.sendMessage(ChatColor.RED + "지도에 없는 지역: " + args[1]);
            return true;
        }
        java.util.List<String> blockers = RemoteBuilder.unbuildableReasons(place);
        if (!blockers.isEmpty()) {
            sender.sendMessage(ChatColor.RED + place.name() + " — " + String.join(" · ", blockers));
            return true;
        }
        World world = sender instanceof Player p2 ? p2.getWorld() : org.bukkit.Bukkit.getWorlds().get(0);
        // 부지 탐색이 먼저 서버를 멈춘다 — 후보마다 지형을 표본하며 **청크를 동기 생성**하기 때문이다
        // (20km 밖의 땅은 아직 존재하지 않는다). 시드검사와 같은 병이고 같은 처방이다: 틱을 나눠 먹는다.
        sender.sendMessage(ChatColor.GRAY + place.name() + " 부지를 찾는다 — 지형을 표본한다 "
                + ChatColor.DARK_GRAY + "(틱을 나눠 먹는다 · 서버는 계속 돈다)");
        new SiteProbe(plugin, map, place, world,
                site -> preloadThenBuild(sender, world, place, site)).runTaskTimer(plugin, 1L, 1L);
        return true;
    }

    /** 부지가 정해진 뒤 — 땅을 비동기로 싣고, 실리면 짓는다 */
    private void preloadThenBuild(CommandSender sender, World world, WorldMap.Place place, WorldMap.Site site) {
        sender.sendMessage(ChatColor.GRAY + place.name() + " 부지 (" + site.x() + ", " + site.z()
                + ") · 지면 y" + site.groundY() + " · 지형 점수 " + site.fit().score()
                + (site.shift() == 0 ? "" : " (등록 좌표에서 " + site.shift() + "칸 이동)"));

        // 청크를 **비동기로** 실어 온다. 원거리 조성은 20km 밖의 땅을 처음 만드는 일이라,
        // 메인 스레드에서 동기 로드하면 서버가 15초 멈춘다(워치독이 스레드 덤프를 뜬다 — 시드검사와 같은 병).
        // 땅이 다 실린 뒤에 짓는다: 짓는 것 자체는 메인 스레드의 일이다 (블록 API 는 그것만 허락한다).
        java.util.List<java.util.concurrent.CompletableFuture<org.bukkit.Chunk>> loading =
                new java.util.ArrayList<>();
        for (int chunkX = (site.x() - 48) >> 4; chunkX <= (site.x() + 48) >> 4; chunkX++) {
            for (int chunkZ = (site.z() - 48) >> 4; chunkZ <= (site.z() + 48) >> 4; chunkZ++) {
                loading.add(world.getChunkAtAsync(chunkX, chunkZ, true));
            }
        }
        sender.sendMessage(ChatColor.GRAY + "땅을 싣는다 — 청크 " + loading.size() + "개 (비동기)");
        java.util.concurrent.CompletableFuture
                .allOf(loading.toArray(new java.util.concurrent.CompletableFuture[0]))
                .thenRun(() -> org.bukkit.Bukkit.getScheduler().runTask(plugin,
                        () -> finishRegion(sender, world, place, site)));
    }

    /**
     * 부지 탐색기 — <b>틱을 나눠 먹는다</b>.
     *
     * <p>지형 적합성은 후보 좌표마다 96×96 창을 표본한다. 그 땅이 아직 생성되지 않았으면(원거리는 늘 그렇다)
     * 표본이 곧 <b>청크 생성</b>이다. 한 틱에 다 하면 서버가 20초 멈춘다 — 워치독이 스레드 덤프를 뜬다.
     * 후보를 하나씩 꺼내 쓰고 틱 예산(20ms)이 다하면 손을 뗀다.
     */
    private static final class SiteProbe extends org.bukkit.scheduler.BukkitRunnable {
        private static final long TICK_BUDGET_NANOS = 20_000_000L;

        private final HoncheonMvt plugin;
        private final WorldMap map;
        private final WorldMap.Place place;
        private final World world;
        private final java.util.function.Consumer<WorldMap.Site> then;
        private final java.util.List<int[]> candidates;

        private int index;
        private WorldMap.Fit best;
        private int bestX;
        private int bestZ;
        private int bestShift;

        SiteProbe(HoncheonMvt plugin, WorldMap map, WorldMap.Place place, World world,
                  java.util.function.Consumer<WorldMap.Site> then) {
            this.plugin = plugin;
            this.map = map;
            this.place = place;
            this.world = world;
            this.then = then;
            this.candidates = map.probeCandidates(place);
        }

        /** 지금 후보의 땅을 싣는 중인가 — 실리기 전에는 표본하지 않는다 */
        private java.util.concurrent.CompletableFuture<Void> loading;

        /**
         * 틱 예산만으로는 부족하다 — <b>후보 하나의 표본이 이미 청크 36개를 만든다</b>(96×96 창).
         * 예산은 후보 <i>사이</i>에서만 물리므로, 후보 하나가 통째로 10초를 먹으면 서버는 그동안 멈춘다.
         * 그래서 표본하기 전에 그 후보의 땅을 <b>비동기로 싣는다</b>. 생성은 워커 스레드가 하고,
         * 메인 스레드는 이미 실린 블록을 읽기만 한다 — 그건 빠르다.
         */
        @Override
        public void run() {
            if (loading != null && !loading.isDone()) {
                return;   // 땅이 실리는 중 — 기다린다 (서버는 그동안 정상으로 돈다)
            }
            long t0 = System.nanoTime();
            while (System.nanoTime() - t0 < TICK_BUDGET_NANOS) {
                if (index >= candidates.size()) {          // 후보 소진 — 최고점 자리에 앉힌다
                    finish(bestX, bestZ, bestShift, best);
                    return;
                }
                int[] c = candidates.get(index);
                if (loading == null) {                     // 이 후보의 땅을 아직 안 실었다
                    loading = loadWindow(c[0], c[1]);
                    return;                                // 다음 틱에 다시 본다
                }
                index++;
                loading = null;
                WorldMap.Fit fit = map.fitAt(world, place, c[0], c[1]);
                if (fit.pass()) {
                    finish(c[0], c[1], c[2], fit);
                    return;
                }
                if (best == null || fit.score() > best.score()) {
                    best = fit;
                    bestX = c[0];
                    bestZ = c[1];
                    bestShift = c[2];
                }
            }
        }

        /** 후보의 표본 창(96×96 = 반경 48) 청크를 비동기로 싣는다 */
        private java.util.concurrent.CompletableFuture<Void> loadWindow(int x, int z) {
            java.util.List<java.util.concurrent.CompletableFuture<org.bukkit.Chunk>> futures =
                    new java.util.ArrayList<>();
            for (int chunkX = (x - 48) >> 4; chunkX <= (x + 48) >> 4; chunkX++) {
                for (int chunkZ = (z - 48) >> 4; chunkZ <= (z + 48) >> 4; chunkZ++) {
                    futures.add(world.getChunkAtAsync(chunkX, chunkZ, true));
                }
            }
            return java.util.concurrent.CompletableFuture
                    .allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]));
        }

        private void finish(int x, int z, int shift, WorldMap.Fit fit) {
            cancel();
            if (fit == null) {
                plugin.getLogger().warning("[지역조성] " + place.id() + " — 후보가 없다");
                return;
            }
            then.accept(new WorldMap.Site(x, z, WorldMap.groundY(world, x, z), shift, fit));
        }
    }

    /** 땅이 실린 뒤 — 짓고 구역을 등록한다 (메인 스레드) */
    private void finishRegion(CommandSender sender, World world, WorldMap.Place place, WorldMap.Site site) {
        // 지면은 **첫 조성이 잰 값**을 쓴다 (원장). 다시 재면 지난번에 세운 산을 지면으로 읽어
        // 그 위에 또 산을 쌓는다 — 조성할 때마다 화산파가 36켜씩 하늘로 자랐다.
        Integer remembered = plugin.regionBase(place.id());
        int baseY = remembered == null ? site.groundY() : remembered;
        if (remembered == null) {
            plugin.setRegionBase(place.id(), baseY);
        }
        // ─── 지형 계층이 먼저 땅을 빚는다 (계약: docs/design/terrain_layer.md) ───
        // 땅과 집은 관심사가 다르다. 지형이 부지를 보장하고(딛는 땅·경계·수역·주문 대조),
        // 건축은 그 위에만 세운다. 동굴은 이제 **우리가 판다** — 자연 동굴을 껐으니까.
        // 문파의 반경 44 는 봉우리(+36)를 못 받는다 — 계단 run 이 26칸이라 본전이 22켜에서 멎는다
        // (건축 담당의 계측). "천 계단"이 서려면 발치가 넓어야 한다.
        int forgeRadius = "noklim".equals(place.faction()) ? 24 : 64;
        TerrainForge.SiteSpec spec = TerrainForge.prepare(world, place, site.x(), baseY, site.z(), forgeRadius);
        plugin.getLogger().info("[지형] " + spec.summary());
        TerrainForge.CaveKind kind = TerrainForge.caveKind(place);
        if (kind != null) {
            TerrainForge.CaveSpec cave = TerrainForge.digCave(world, spec, kind);
            plugin.getLogger().info("[지형/동굴] " + place.id() + " — " + kind + " 입구 ("
                    + cave.mouthX() + "," + cave.mouthY() + "," + cave.mouthZ()
                    + ") · 파낸 칸 " + cave.carved());
            sender.sendMessage(ChatColor.GRAY + "굴 입구: /tp " + cave.mouthX() + " "
                    + cave.mouthY() + " " + cave.mouthZ());
        }

        // 건축 계층은 **부지 사양만** 받는다 (땅은 지형 계층이 이미 빚었다)
        java.util.List<Zone> built = RemoteBuilder.build(world, place, spec);
        if (built.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "원형이 없어 아무것도 서지 않았다.");
            return;
        }
        // 구역은 **더한다** — 청하현의 구역을 지우지 않는다 (세계는 하나고, 지역은 쌓인다)
        java.util.List<Zone> all = new java.util.ArrayList<>(plugin.zones());
        all.removeIf(z -> z.name().equals(place.name()));   // 재조성 = 덮어쓰기
        all.addAll(built);
        plugin.setZones(all);
        sender.sendMessage(ChatColor.GOLD + place.name() + " 이(가) 섰다 — (" + site.x() + ", "
                + site.groundY() + ", " + site.z() + ") · 구역 " + built.size() + "곳");
        sender.sendMessage(ChatColor.GRAY + "채문 앞: /tp " + site.x() + " " + (site.groundY() + 1)
                + " " + (site.z() + 24));
        plugin.getLogger().info("[지역조성] " + place.id() + " (" + place.name() + ") — ("
                + site.x() + ", " + site.groundY() + ", " + site.z() + ") · 구역 " + built.size() + "곳");
    }

    /** /혼천 세계조성 — 등록된 지역을 제 좌표에 짓는다 (관리자). 지금은 청하현 일대 */
    private boolean buildWorld(CommandSender sender) {
        if (sender instanceof Player p && !p.isOp()) {
            return true;
        }
        WorldMap map = plugin.worldMap();
        if (map == null) {
            sender.sendMessage(ChatColor.RED + "world_map.yml 이 없다.");
            return true;
        }
        WorldMap.Place home = map.place("cheongha_hyeon");
        if (home == null) {
            sender.sendMessage(ChatColor.RED + "청하현이 지도에 없다.");
            return true;
        }
        // 콘솔 가능 — 지면 높이는 지도가 계산한다 (플레이어 발밑이 아니라 지형이 정한다).
        // 부지 탐색은 틱을 나눠 먹는다 (새 월드의 원점도 아직 생성되지 않은 땅이다 — 표본이 곧 청크 생성이다).
        org.bukkit.World world = sender instanceof Player p2 ? p2.getWorld()
                : org.bukkit.Bukkit.getWorlds().get(0);
        sender.sendMessage(ChatColor.GRAY + "청하현 부지를 찾는다 — 지형을 표본한다");
        new SiteProbe(plugin, map, home, world, site -> {
            sender.sendMessage(ChatColor.GRAY + "청하현 부지 (" + site.x() + ", " + site.z()
                    + ") · 지면 y" + site.groundY()
                    + " · 지형 점수 " + site.fit().score() + " (" + site.fit().verdict() + ")");
            java.util.List<Zone> zones = new java.util.ArrayList<>();
            Map<String, Location> anchors = CheonghaBuilder.build(world,
                    site.x(), site.groundY(), site.z(), zones);
            plugin.setAnchors(anchors);
            plugin.setZones(zones);
            sender.sendMessage(ChatColor.GOLD + "세계가 섰다 — 청하현 (" + site.x() + ", " + site.z()
                    + ") · 장소 " + anchors.size() + "곳 · 구역 " + zones.size() + "곳");
            sender.sendMessage(ChatColor.GRAY + "원거리 지역: /혼천 지역조성 <id> (예: nokrim_sochae)");
            plugin.getLogger().info("[세계조성] 청하현 — (" + site.x() + ", " + site.groundY()
                    + ", " + site.z() + ") · 장소 " + anchors.size() + "곳");
        }).runTaskTimer(plugin, 1L, 1L);
        return true;
    }

    /** /혼천 사냥터 — 구역별 개체군 (정원 대비 현재) */
    private boolean census(CommandSender sender) {
        plugin.hunting().census().forEach(sender::sendMessage);
        return true;
    }

    /** /혼천 비무 <플레이어|수락|항복> — 죽지 않는 싸움 (표국 마당의 곽진은 우클릭) */
    private boolean spar(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        Sparring sparring = plugin.hunting().sparring();
        if (args.length < 2) {
            player.sendMessage(ChatColor.GRAY
                    + "/혼천 비무 <플레이어> | 수락 | 항복   (표국 마당의 곽진은 우클릭)");
        } else if ("수락".equals(args[1])) {
            sparring.accept(player);
        } else if ("항복".equals(args[1])) {
            sparring.yield(player);
        } else {
            Player target = plugin.getServer().getPlayer(args[1]);
            if (target == null) {
                player.sendMessage(ChatColor.GRAY + "그런 사람이 없다.");
            } else {
                sparring.challenge(player, target);
            }
        }
        return true;
    }

    /** /혼천 소환 <id> — 영물은 등록제다 (관리자) */
    private boolean summon(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || !player.isOp()) {
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(ChatColor.GRAY + "/혼천 소환 <"
                    + String.join("|", HuntingGrounds.registry()) + ">");
            return true;
        }
        var spawned = plugin.hunting().summon(args[1], player.getLocation());
        player.sendMessage(spawned == null ? ChatColor.RED + "등록되지 않은 개체: " + args[1]
                : ChatColor.GOLD + spawned.getCustomName() + ChatColor.GRAY + " 이(가) 섰다.");
        return true;
    }

    /** /혼천 재련 — 손에 든 병기의 품을 한 단계 올린다 (야철수 대역, 검증용) */
    private boolean reforgeWeapon(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        Weapons.ReforgeResult result = Weapons.reforge(player.getInventory().getItemInMainHand(), 2,
                java.util.concurrent.ThreadLocalRandom.current());   // config 등록 대기: 야철수 솜씨 2
        switch (result.outcome()) {
            case 성공 -> {
                player.getInventory().setItemInMainHand(result.item());
                player.sendMessage(ChatColor.GOLD + result.message());
            }
            case 손상 -> {
                player.getInventory().setItemInMainHand(result.item());
                player.sendMessage(ChatColor.YELLOW + result.message());
            }
            case 파손 -> {
                player.getInventory().setItemInMainHand(null);   // 쇠가 갈라졌다
                player.sendMessage(ChatColor.RED + result.message());
            }
            default -> player.sendMessage(ChatColor.GRAY + result.message());
        }
        return true;
    }

    /** /혼천 각인 <파사|한철|경명|음양쌍인|탈혼> — 슬롯 안에서만 */
    private boolean inscribeWeapon(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(ChatColor.GRAY + "/혼천 각인 <파사|한철|경명|음양쌍인|탈혼>");
            return true;
        }
        Weapons.ReforgeResult result =
                Weapons.inscribe(player.getInventory().getItemInMainHand(), args[1]);
        if (result.outcome() == Weapons.Reforge.성공) {
            player.getInventory().setItemInMainHand(result.item());
            player.sendMessage(ChatColor.LIGHT_PURPLE + result.message());
        } else {
            player.sendMessage(ChatColor.GRAY + result.message());
        }
        return true;
    }

    /** /혼천 격돌 — 애병 카운터 +1 (전투 훅이 설 때까지의 검증 수단) */
    private boolean recordClash(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        org.bukkit.inventory.ItemStack hand = player.getInventory().getItemInMainHand();
        if (!Weapons.isWeapon(hand)) {
            player.sendMessage(ChatColor.GRAY + "손에 병기가 없다.");
            return true;
        }
        player.getInventory().setItemInMainHand(Weapons.recordClash(hand, player.getUniqueId()));
        player.sendMessage(ChatColor.GRAY + "격돌 "
                + Weapons.clashesOf(player.getInventory().getItemInMainHand()) + "회");
        return true;
    }

    /** /혼천 병기상 — 장쇠의 병기 좌판 */
    private boolean weaponShop(CommandSender sender) {
        if (sender instanceof Player player) {
            WeaponShop.open(plugin, player);
        }
        return true;
    }

    /** /혼천 운기 — 운기조식 1구간 (내력 회복) */
    private boolean meditate(CommandSender sender) {
        if (sender instanceof Player player) {
            plugin.skills().meditate(player);
        }
        return true;
    }

    /** 청하현 조성 (M2b) — 관리자 전용, 재조성 = 같은 마을 (결정론 생성) */
    private boolean buildTown(CommandSender sender, String[] args) {
        java.util.List<Zone> zones = new java.util.ArrayList<>();
        Map<String, Location> anchors;
        // 좌표 지정: /혼천 조성 <x> <y> <z> — 마을을 특정 자리에 못박는다.
        // (플레이어가 엉뚱한 데 서서 조성하면 마을이 통째로 이사한다 — 물가·저지대면 부지가 망가진다)
        if (args.length >= 4) {
            org.bukkit.World world = sender instanceof Player p ? p.getWorld()
                    : org.bukkit.Bukkit.getWorlds().get(0);
            int bx = Integer.parseInt(args[1]);
            int by = Integer.parseInt(args[2]);
            int bz = Integer.parseInt(args[3]);
            anchors = CheonghaBuilder.build(world, bx, by, bz, zones);
            plugin.setAnchors(anchors);
            plugin.setZones(zones);
            sender.sendMessage(ChatColor.GOLD + "청하현이 섰다 (" + bx + ", " + by + ", " + bz + ") — 장소 "
                    + anchors.size() + "곳 · 구역 " + zones.size() + "곳");
            return true;
        }
        if (sender instanceof Player player) {
            if (!player.isOp()) {
                player.sendMessage(ChatColor.RED + "조성은 관리자의 몫이다.");
                return true;
            }
            anchors = CheonghaBuilder.build(player, zones);
        } else {
            // 콘솔·RCON — 직전 마을 중심(장터 앵커)에 재조성. 결정론이므로 같은 마을이 선다
            Location center = plugin.anchor("장터");
            if (center == null) {
                sender.sendMessage(ChatColor.RED
                        + "콘솔 조성은 기존 마을이 있어야 한다 (중심을 모른다) — 최초 1회는 인게임에서.");
                return true;
            }
            anchors = CheonghaBuilder.build(center.getWorld(), center.getBlockX(),
                    center.getBlockY() - 1, center.getBlockZ(), zones);
        }
        plugin.setAnchors(anchors);
        plugin.setZones(zones);
        sender.sendMessage(ChatColor.GOLD + "청하현이 섰다 — 장소 " + anchors.size()
                + "곳 · 구역 " + zones.size() + "곳 (입장 타이틀)");
        return true;
    }

    /**
     * 검수 — 조성된 마을을 가이드 규칙으로 린트 (building_style_guide.md).
     * 콘솔 가능: 좌표를 앵커에서 얻으므로 플레이어가 필요 없다 (자동 반복 루프의 눈).
     */
    private boolean auditTown(CommandSender sender) {
        Location center = plugin.anchor("장터");
        if (center == null) {
            sender.sendMessage(ChatColor.RED + "조성된 마을이 없다 — 먼저 /혼천 조성");
            return true;
        }
        for (String line : TownAudit.audit(center.getWorld(), plugin.anchors(), plugin.zones(),
                center.getBlockX(), center.getBlockY() - 1, center.getBlockZ())) {
            sender.sendMessage(line);
        }
        return true;
    }

    /** 조감 — 탑다운·아이소메트릭·건물별 PNG 렌더 (plugins/HoncheonMVT/render/). 콘솔 가능 */
    /**
     * /혼천 조감 [지역id] — 조감도 PNG (콘솔 가능).
     *
     * <p>인자가 있으면 <b>그 지역</b>을 그린다. 원거리 조성기가 지은 것을 볼 눈이 없으면
     * "섰다"는 로그만 믿게 된다 — 루프의 눈이 없는 조성은 검산이 아니다.
     */
    private boolean renderTown(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            return renderRegion(sender, args[1]);
        }
        Location center = plugin.anchor("장터");
        if (center == null) {
            sender.sendMessage(ChatColor.RED + "조성된 마을이 없다 — 먼저 /혼천 조성");
            return true;
        }
        java.io.File dir = new java.io.File(plugin.getDataFolder(), "render");
        for (String line : TownRender.render(center.getWorld(), plugin.anchors(),
                center.getBlockX(), center.getBlockY() - 1, center.getBlockZ(), dir)) {
            sender.sendMessage(ChatColor.GRAY + line);
        }
        return true;
    }

    /**
     * /혼천 지역검수 &lt;id&gt; — 지역의 자동 검산 (콘솔 가능).
     *
     * <p>청하현에는 검수 12종이 있는데 산채·문파에는 아무 눈도 없었다. 조감은 "보기에 이상하다"까지만
     * 말하고 <b>"오를 수 없다"는 말은 못 한다</b>. 지역 검수의 첫 질문이 그것이다 — 정말 걸어 올라가지는가.
     */
    private boolean auditRegion(CommandSender sender, String[] args) {
        WorldMap map = plugin.worldMap();
        if (map == null || args.length < 2) {
            sender.sendMessage(ChatColor.GRAY + "/혼천 지역검수 <지역id>  (예: hwasan · nokrim_sochae)");
            return true;
        }
        WorldMap.Place place = map.place(args[1]);
        if (place == null) {
            sender.sendMessage(ChatColor.RED + "지도에 없는 지역: " + args[1]);
            return true;
        }
        Zone zone = plugin.zones().stream().filter(z -> z.name().equals(place.name())).findFirst().orElse(null);
        if (zone == null) {
            sender.sendMessage(ChatColor.RED + place.name() + " 은 아직 서지 않았다 — /혼천 지역조성 " + args[1]);
            return true;
        }
        World world = org.bukkit.Bukkit.getWorld(zone.world());
        for (String line : RegionAudit.audit(world, place, zone)) {
            sender.sendMessage(line);
            plugin.getLogger().info("[지역검수] " + org.bukkit.ChatColor.stripColor(line));
        }
        return true;
    }

    /**
     * /혼천 환경검수 [지역id] — <b>조성물과 자연의 이음매</b>를 잰다 (콘솔 가능).
     *
     * <p>지금까지의 검수는 전부 <b>안</b>을 봤다. 그런데 인게임에서 드러난 파탄은 <b>바깥</b>이었다 —
     * 바닥 밑에서 잘린 동굴, 산 위에 남은 웅덩이, 뚝 끊긴 부지 경계. 조성기는 자연 위에 상자를
     * 찍어 넣었고, <b>그것을 볼 눈이 없었다.</b>
     */
    private boolean auditTerrain(CommandSender sender, String[] args) {
        World world;
        java.util.List<String> lines;
        if (args.length >= 2) {
            WorldMap map = plugin.worldMap();
            WorldMap.Place place = map == null ? null : map.place(args[1]);
            Zone zone = place == null ? null : plugin.zones().stream()
                    .filter(z -> z.name().equals(place.name())).findFirst().orElse(null);
            if (zone == null) {
                sender.sendMessage(ChatColor.RED + "서지 않은 지역이다: " + args[1]);
                return true;
            }
            world = org.bukkit.Bukkit.getWorld(zone.world());
            int cx = (zone.x1() + zone.x2()) / 2;
            int cz = (zone.z1() + zone.z2()) / 2;
            int radius = Math.max(zone.x2() - zone.x1(), zone.z2() - zone.z1()) / 2 + 8;
            lines = TerrainAudit.audit(world, place.name(), cx, zone.y1() + 6, cz, radius,
                    place.terrain() == null ? "평지" : place.terrain());
        } else {
            Location center = plugin.anchor("장터");
            if (center == null) {
                sender.sendMessage(ChatColor.RED + "조성된 마을이 없다 — 먼저 /혼천 세계조성");
                return true;
            }
            lines = TerrainAudit.auditTown(center.getWorld(), center, 61);
        }
        for (String line : lines) {
            sender.sendMessage(line);
            plugin.getLogger().info("[환경검수] " + ChatColor.stripColor(line));
        }
        return true;
    }

    /** 지역 조감 — 등록부의 좌표(지도가 고른 부지)를 중심으로 그린다 */
    private boolean renderRegion(CommandSender sender, String id) {
        WorldMap map = plugin.worldMap();
        WorldMap.Place place = map == null ? null : map.place(id);
        if (place == null) {
            sender.sendMessage(ChatColor.RED + "지도에 없는 지역: " + id);
            return true;
        }
        Zone zone = plugin.zones().stream().filter(z -> z.name().equals(place.name())).findFirst().orElse(null);
        if (zone == null) {
            sender.sendMessage(ChatColor.RED + place.name() + " 은 아직 서지 않았다 — /혼천 지역조성 " + id);
            return true;
        }
        World world = org.bukkit.Bukkit.getWorld(zone.world());
        int cx = (zone.x1() + zone.x2()) / 2;
        int cz = (zone.z1() + zone.z2()) / 2;
        // 창은 **구역이 정한다** — 마을의 창(반경 65·높이 25)으로 문파를 보면 산허리만 잘려 나온다.
        int cy = zone.y1() + 6;
        int radius = Math.min(120, Math.max(48,
                Math.max(zone.x2() - zone.x1(), zone.z2() - zone.z1()) / 2 + 12));
        int yUp = Math.min(90, Math.max(25, zone.y2() - cy));
        // 렌더 전에 땅을 싣는다 — 안 실린 청크는 공기로 읽혀 조감에 **검은 구멍**이 뚫린다.
        // 루프의 눈이 못 본 것을 "없다"고 그리면, 있는 결함도 없는 것이 된다.
        java.util.List<java.util.concurrent.CompletableFuture<org.bukkit.Chunk>> loading =
                new java.util.ArrayList<>();
        int pre = radius + 16;
        for (int chunkX = (cx - pre) >> 4; chunkX <= (cx + pre) >> 4; chunkX++) {
            for (int chunkZ = (cz - pre) >> 4; chunkZ <= (cz + pre) >> 4; chunkZ++) {
                loading.add(world.getChunkAtAsync(chunkX, chunkZ, true));
            }
        }
        java.io.File dir = new java.io.File(plugin.getDataFolder(), "render/" + id);
        java.util.concurrent.CompletableFuture
                .allOf(loading.toArray(new java.util.concurrent.CompletableFuture[0]))
                .thenRun(() -> org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                    for (String line : TownRender.render(world, cx, cy, cz, dir, radius, 6, yUp)) {
                        sender.sendMessage(ChatColor.GRAY + line);
                    }
                    plugin.getLogger().info("[조감] " + id + " → " + dir.getAbsolutePath());
                }));
        return true;
    }

    // ═══ 연무장 — 따로 두들겨 보는 자리 (별도 월드) ═══

    private boolean dojangEnter(CommandSender sender) {
        if (sender instanceof Player p) {
            plugin.dojang().enter(p);
        }
        return true;
    }

    private boolean dojangLeave(CommandSender sender) {
        if (sender instanceof Player p) {
            plugin.dojang().leave(p);
        }
        return true;
    }

    /** /혼천 시험 경지|내력|무공|몹|치움 … */
    private boolean dojangTune(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            return true;
        }
        if (args.length < 2) {
            p.sendMessage(ChatColor.GRAY + "/혼천 시험 경지 <경지> · 내력 <값> · 무공 <id> [일수] · "
                    + "몹 <id> · 치움");
            return true;
        }
        switch (args[1]) {
            case "경지" -> {
                if (args.length >= 3) {
                    plugin.dojang().setRealm(p, args[2]);
                }
            }
            case "내력" -> {
                if (args.length >= 3) {
                    plugin.dojang().setEnergy(p, Integer.parseInt(args[2]));
                }
            }
            case "무공" -> {
                if (args.length >= 3) {
                    double days = args.length >= 4 ? Double.parseDouble(args[3]) : 30.0;
                    plugin.dojang().grantSkill(p, args[2], days);
                }
            }
            case "몹" -> {
                if (args.length >= 3) {
                    plugin.dojang().mob(p, args[2]);
                }
            }
            case "치움" -> plugin.dojang().clear(p);
            default -> p.sendMessage(ChatColor.GRAY + "경지 · 내력 · 무공 · 몹 · 치움");
        }
        return true;
    }

    private boolean dojangDummy(CommandSender sender, String[] args) {
        if (sender instanceof Player p) {
            int durability = args.length >= 2 ? Integer.parseInt(args[1]) : 20;
            plugin.dojang().dummy(p, durability);
        }
        return true;
    }

    /** 경지 문장 글리프 시연 — 리소스팩 검증용 (E020~E027) */
    private boolean crests(CommandSender sender) {
        StringBuilder line = new StringBuilder(ChatColor.GOLD + "경지 문장: ");
        for (String realm : Glyphs.crestRealms()) {
            line.append(ChatColor.WHITE).append(Glyphs.realmCrest(realm))
                    .append(ChatColor.GRAY).append(realm).append("  ");
        }
        sender.sendMessage(line.toString());
        sender.sendMessage(ChatColor.DARK_GRAY + "□로 보이면 리소스팩 미적용 — 팩 재컴파일·재적용 필요");
        return true;
    }

    private boolean help(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "── 혼천 MVT ──");
        sender.sendMessage("/혼천 정보 — 수련·소지금·마크 / /혼천 판정 <실행력> <저항>");
        sender.sendMessage("/혼천 팔기 — 가죽 매각(50%) / /혼천 물가 [경제지수 0~100]");
        sender.sendMessage("/혼천 정산 [개입 -3~3] — 한백 계절 정산 / /혼천 협공 <인원>");
        sender.sendMessage("/혼천 조성 — 청하현 마을 생성 (관리자) / /혼천 문장 — 경지 문장 글리프 확인");
        sender.sendMessage(ChatColor.GRAY + "사냥 루프: 늑대·여우(격상) vs 가축(회색) — 기세·적립·감쇠·돌파를 몸으로 확인");
        return true;
    }
}
